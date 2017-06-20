/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 *
 * JSR 166 interest web site:
 * http://gee.cs.oswego.edu/dl/concurrency-interest/
 *
 * File download location:
 * http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/src/jdk7/java/util/concurrent/ConcurrentSkipListMap.java?revision=1.1
 */

/*
 * Customized version for GemFireXD distributed data platform.
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.gemstone.gemfire.internal.concurrent;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THashSet;

/**
 * A scalable concurrent {@link ConcurrentNavigableMap} implementation.
 * The map is sorted according to the {@linkplain Comparable natural
 * ordering} of its keys, or by a {@link Comparator} provided at map
 * creation time, depending on which constructor is used.
 *
 * <p>This class implements a concurrent variant of <a
 * href="http://en.wikipedia.org/wiki/Skip_list" target="_top">SkipLists</a>
 * providing expected average <i>log(n)</i> time cost for the
 * <tt>containsKey</tt>, <tt>get</tt>, <tt>put</tt> and
 * <tt>remove</tt> operations and their variants.  Insertion, removal,
 * update, and access operations safely execute concurrently by
 * multiple threads.  Iterators are <i>weakly consistent</i>, returning
 * elements reflecting the state of the map at some point at or since
 * the creation of the iterator.  They do <em>not</em> throw <code>
 * ConcurrentModificationException</code>, and may proceed concurrently with
 * other operations. Ascending key ordered views and their iterators
 * are faster than descending ones.
 *
 * <p>All <tt>Map.Entry</tt> pairs returned by methods in this class
 * and its views represent snapshots of mappings at the time they were
 * produced. They do <em>not</em> support the <tt>Entry.setValue</tt>
 * method. (Note however that it is possible to change mappings in the
 * associated map using <tt>put</tt>, <tt>putIfAbsent</tt>, or
 * <tt>replace</tt>, depending on exactly which effect you need.)
 *
 * <p>Beware that, unlike in most collections, the <tt>size</tt>
 * method is <em>not</em> a constant-time operation. Because of the
 * asynchronous nature of these maps, determining the current number
 * of elements requires a traversal of the elements, and so may report
 * inaccurate results if this collection is modified during traversal.
 * Additionally, the bulk operations <tt>putAll</tt>, <tt>equals</tt>,
 * <tt>toArray</tt>, <tt>containsValue</tt>, and <tt>clear</tt> are
 * <em>not</em> guaranteed to be performed atomically. For example, an
 * iterator operating concurrently with a <tt>putAll</tt> operation
 * might view only some of the added elements.
 *
 * <p>This class and its views and iterators implement all of the
 * <em>optional</em> methods of the {@link Map} and {@link Iterator}
 * interfaces. Like most other concurrent collections, this class does
 * <em>not</em> permit the use of <tt>null</tt> keys or values because some
 * null return values cannot be reliably distinguished from the absence of
 * elements.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @author Doug Lea
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 * @since 1.6
 */
@SuppressWarnings("unchecked")
public class ConcurrentSkipListMap<K, V> extends AbstractMap<K, V> implements
    ConcurrentNavigableMap<K, V>, Cloneable, java.io.Serializable {
  /*
   * This class implements a tree-like two-dimensionally linked skip
   * list in which the index levels are represented in separate
   * nodes from the base nodes holding data.  There are two reasons
   * for taking this approach instead of the usual array-based
   * structure: 1) Array based implementations seem to encounter
   * more complexity and overhead 2) We can use cheaper algorithms
   * for the heavily-traversed index lists than can be used for the
   * base lists.  Here's a picture of some of the basics for a
   * possible list with 2 levels of index:
   *
   * Head nodes          Index nodes
   * +-+    right        +-+                      +-+
   * |2|---------------->| |--------------------->| |->null
   * +-+                 +-+                      +-+
   *  | down              |                        |
   *  v                   v                        v
   * +-+            +-+  +-+       +-+            +-+       +-+
   * |1|----------->| |->| |------>| |----------->| |------>| |->null
   * +-+            +-+  +-+       +-+            +-+       +-+
   *  v              |    |         |              |         |
   * Nodes  next     v    v         v              v         v
   * +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+
   * | |->|A|->|B|->|C|->|D|->|E|->|F|->|G|->|H|->|I|->|J|->|K|->null
   * +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+  +-+
   *
   * The base lists use a variant of the HM linked ordered set
   * algorithm. See Tim Harris, "A pragmatic implementation of
   * non-blocking linked lists"
   * http://www.cl.cam.ac.uk/~tlh20/publications.html and Maged
   * Michael "High Performance Dynamic Lock-Free Hash Tables and
   * List-Based Sets"
   * http://www.research.ibm.com/people/m/michael/pubs.htm.  The
   * basic idea in these lists is to mark the "next" pointers of
   * deleted nodes when deleting to avoid conflicts with concurrent
   * insertions, and when traversing to keep track of triples
   * (predecessor, node, successor) in order to detect when and how
   * to unlink these deleted nodes.
   *
   * Rather than using mark-bits to mark list deletions (which can
   * be slow and space-intensive using AtomicMarkedReference), nodes
   * use direct CAS'able next pointers.  On deletion, instead of
   * marking a pointer, they splice in another node that can be
   * thought of as standing for a marked pointer (indicating this by
   * using otherwise impossible field values).  Using plain nodes
   * acts roughly like "boxed" implementations of marked pointers,
   * but uses new nodes only when nodes are deleted, not for every
   * link.  This requires less space and supports faster
   * traversal. Even if marked references were better supported by
   * JVMs, traversal using this technique might still be faster
   * because any search need only read ahead one more node than
   * otherwise required (to check for trailing marker) rather than
   * unmasking mark bits or whatever on each read.
   *
   * This approach maintains the essential property needed in the HM
   * algorithm of changing the next-pointer of a deleted node so
   * that any other CAS of it will fail, but implements the idea by
   * changing the pointer to point to a different node, not by
   * marking it.  While it would be possible to further squeeze
   * space by defining marker nodes not to have key/value fields, it
   * isn't worth the extra type-testing overhead.  The deletion
   * markers are rarely encountered during traversal and are
   * normally quickly garbage collected. (Note that this technique
   * would not work well in systems without garbage collection.)
   *
   * In addition to using deletion markers, the lists also use
   * nullness of value fields to indicate deletion, in a style
   * similar to typical lazy-deletion schemes.  If a node's value is
   * null, then it is considered logically deleted and ignored even
   * though it is still reachable. This maintains proper control of
   * concurrent replace vs delete operations -- an attempted replace
   * must fail if a delete beat it by nulling field, and a delete
   * must return the last non-null value held in the field. (Note:
   * Null, rather than some special marker, is used for value fields
   * here because it just so happens to mesh with the Map API
   * requirement that method get returns null if there is no
   * mapping, which allows nodes to remain concurrently readable
   * even when deleted. Using any other marker value here would be
   * messy at best.)
   *
   * Here's the sequence of events for a deletion of node n with
   * predecessor b and successor f, initially:
   *
   *        +------+       +------+      +------+
   *   ...  |   b  |------>|   n  |----->|   f  | ...
   *        +------+       +------+      +------+
   *
   * 1. CAS n's value field from non-null to null.
   *    From this point on, no public operations encountering
   *    the node consider this mapping to exist. However, other
   *    ongoing insertions and deletions might still modify
   *    n's next pointer.
   *
   * 2. CAS n's next pointer to point to a new marker node.
   *    From this point on, no other nodes can be appended to n.
   *    which avoids deletion errors in CAS-based linked lists.
   *
   *        +------+       +------+      +------+       +------+
   *   ...  |   b  |------>|   n  |----->|marker|------>|   f  | ...
   *        +------+       +------+      +------+       +------+
   *
   * 3. CAS b's next pointer over both n and its marker.
   *    From this point on, no new traversals will encounter n,
   *    and it can eventually be GCed.
   *        +------+                                    +------+
   *   ...  |   b  |----------------------------------->|   f  | ...
   *        +------+                                    +------+
   *
   * A failure at step 1 leads to simple retry due to a lost race
   * with another operation. Steps 2-3 can fail because some other
   * thread noticed during a traversal a node with null value and
   * helped out by marking and/or unlinking.  This helping-out
   * ensures that no thread can become stuck waiting for progress of
   * the deleting thread.  The use of marker nodes slightly
   * complicates helping-out code because traversals must track
   * consistent reads of up to four nodes (b, n, marker, f), not
   * just (b, n, f), although the next field of a marker is
   * immutable, and once a next field is CAS'ed to point to a
   * marker, it never again changes, so this requires less care.
   *
   * Skip lists add indexing to this scheme, so that the base-level
   * traversals start close to the locations being found, inserted
   * or deleted -- usually base level traversals only traverse a few
   * nodes. This doesn't change the basic algorithm except for the
   * need to make sure base traversals start at predecessors (here,
   * b) that are not (structurally) deleted, otherwise retrying
   * after processing the deletion.
   *
   * Index levels are maintained as lists with volatile next fields,
   * using CAS to link and unlink.  Races are allowed in index-list
   * operations that can (rarely) fail to link in a new index node
   * or delete one. (We can't do this of course for data nodes.)
   * However, even when this happens, the index lists remain sorted,
   * so correctly serve as indices.  This can impact performance,
   * but since skip lists are probabilistic anyway, the net result
   * is that under contention, the effective "p" value may be lower
   * than its nominal value. And race windows are kept small enough
   * that in practice these failures are rare, even under a lot of
   * contention.
   *
   * The fact that retries (for both base and index lists) are
   * relatively cheap due to indexing allows some minor
   * simplifications of retry logic. Traversal restarts are
   * performed after most "helping-out" CASes. This isn't always
   * strictly necessary, but the implicit backoffs tend to help
   * reduce other downstream failed CAS's enough to outweigh restart
   * cost.  This worsens the worst case, but seems to improve even
   * highly contended cases.
   *
   * Unlike most skip-list implementations, index insertion and
   * deletion here require a separate traversal pass occuring after
   * the base-level action, to add or remove index nodes.  This adds
   * to single-threaded overhead, but improves contended
   * multithreaded performance by narrowing interference windows,
   * and allows deletion to ensure that all index nodes will be made
   * unreachable upon return from a public remove operation, thus
   * avoiding unwanted garbage retention. This is more important
   * here than in some other data structures because we cannot null
   * out node fields referencing user keys since they might still be
   * read by other ongoing traversals.
   *
   * Indexing uses skip list parameters that maintain good search
   * performance while using sparser-than-usual indices: The
   * hardwired parameters k=1, p=0.5 (see method randomLevel) mean
   * that about one-quarter of the nodes have indices. Of those that
   * do, half have one level, a quarter have two, and so on (see
   * Pugh's Skip List Cookbook, sec 3.4).  The expected total space
   * requirement for a map is slightly less than for the current
   * implementation of java.util.TreeMap.
   *
   * Changing the level of the index (i.e, the height of the
   * tree-like structure) also uses CAS. The head index has initial
   * level/height of one. Creation of an index with height greater
   * than the current level adds a level to the head index by
   * CAS'ing on a new top-most head. To maintain good performance
   * after a lot of removals, deletion methods heuristically try to
   * reduce the height if the topmost levels appear to be empty.
   * This may encounter races in which it possible (but rare) to
   * reduce and "lose" a level just as it is about to contain an
   * index (that will then never be encountered). This does no
   * structural harm, and in practice appears to be a better option
   * than allowing unrestrained growth of levels.
   *
   * The code for all this is more verbose than you'd like. Most
   * operations entail locating an element (or position to insert an
   * element). The code to do this can't be nicely factored out
   * because subsequent uses require a snapshot of predecessor
   * and/or successor and/or value fields which can't be returned
   * all at once, at least not without creating yet another object
   * to hold them -- creating such little objects is an especially
   * bad idea for basic internal search operations because it adds
   * to GC overhead.  (This is one of the few times I've wished Java
   * had macros.) Instead, some traversal code is interleaved within
   * insertion and removal operations.  The control logic to handle
   * all the retry conditions is sometimes twisty. Most search is
   * broken into 2 parts. findPredecessor() searches index nodes
   * only, returning a base-level predecessor of the key. findNode()
   * finishes out the base-level search. Even with this factoring,
   * there is a fair amount of near-duplication of code to handle
   * variants.
   *
   * For explanation of algorithms sharing at least a couple of
   * features with this one, see Mikhail Fomitchev's thesis
   * (http://www.cs.yorku.ca/~mikhail/), Keir Fraser's thesis
   * (http://www.cl.cam.ac.uk/users/kaf24/), and Hakan Sundell's
   * thesis (http://www.cs.chalmers.se/~phs/).
   *
   * Given the use of tree-like index nodes, you might wonder why
   * this doesn't use some kind of search tree instead, which would
   * support somewhat faster search operations. The reason is that
   * there are no known efficient lock-free insertion and deletion
   * algorithms for search trees. The immutability of the "down"
   * links of index nodes (as opposed to mutable "left" fields in
   * true trees) makes this tractable using only CAS operations.
   *
   * Notation guide for local variables
   * Node:         b, n, f    for  predecessor, node, successor
   * Index:        q, r, d    for index node, right, down.
   *               t          for another index node
   * Head:         h
   * Levels:       j
   * Keys:         k, key
   * Values:       v, value
   * Comparisons:  c
   */


  private static final long serialVersionUID = -8627078645895051609L;

  /**
   * Generates the initial random seed for the cheaper per-instance random
   * number generators used in randomLevel.
   */
  private static final Random seedGenerator = new Random();

  /**
   * The topmost head index of the skiplist.
   */
  private transient volatile HeadIndex<K, V> head;

  /**
   * The comparator used to maintain order in this map, or null if using natural
   * ordering.
   * 
   * @serial
   */
  private final Comparator<Object> comparator;

  // GemStone addition
  /** factory to use for creating {@link SkipListNode}s */
  private final SkipListNodeFactory<K, V> nodeFactory;

  // GemStone addition
  /**
   * If true then use the Object.equals() method for equality of values, else
   * use reference equality.
   */
  private final boolean useEquals;

  /**
   * If true, then skip the range check for low/high keys representing a valid
   * range.
   */
  private final boolean skipSubMapRangeCheck;

  /**
   * Seed for simple random number generator. Not volatile since it doesn't
   * matter too much if different threads don't see updates.
   */
  private transient int randomSeed;

  /** Lazily initialized key set */
  private transient KeySet<K, V> keySet;

  /** Lazily initialized entry set */
  private transient EntrySet entrySet;

  /** Lazily initialized values collection */
  private transient Values<K, V> values;

  /** Lazily initialized descending key set */
  private transient ConcurrentNavigableMap<K, V> descendingMap;

  /**
   * Initializes or resets state. Needed by constructors, clone, clear,
   * readObject. and ConcurrentSkipListSet.clone. (Note that comparator must be
   * separately initialized.)
   */
  final void initialize() {
    keySet = null;
    entrySet = null;
    values = null;
    descendingMap = null;
    randomSeed = seedGenerator.nextInt() | 0x0100; // ensure nonzero
    head = new HeadIndex<K, V>(this.nodeFactory.newNode(null,
        SkipListNode.BASE_HEADER, null), null, null, 1);
  }

  // GemStone changes below to use JDK5's CAS primitives

  /** Updater for casHead */
  @SuppressWarnings("rawtypes")
  private static final AtomicReferenceFieldUpdater<ConcurrentSkipListMap,
      HeadIndex> headUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
          ConcurrentSkipListMap.class, HeadIndex.class, "head");

  /**
   * compareAndSet head node
   */
  private boolean casHead(HeadIndex<K, V> cmp, HeadIndex<K, V> val) {
    return headUpdater.compareAndSet(this, cmp, val);
  }

  /* (original code)
  private synchronized boolean casHead(HeadIndex cmp, HeadIndex val) {
      if (head == cmp) {
          head = val;
          return true;
      }
      else {
          return false;
      }
  }
  */
  // End GemStone changes for CAS primitives

  /**
   * This sizes internal Node objects used to hold index values. Neither skip
   * list Index/HeadIndex object nor objects within Node class is estimated.
   * 
   * The key size is to esitmated separately as it can be a complex object.
   * 
   * @return CSLM Entry Size (excluding key size), Skipping Index Size, Max No.
   *         of Skipping Index
   */
  // GemStone changes for entry size estimation.
  public long[] estimateMemoryOverhead(final Set<?> s,
      final SingleObjectSizer sizer, final boolean verbose) {
    final boolean isAgentAttached = sizer.isAgentAttached();
    final long entryOverhead = ((EntrySet)s).estimateEntryOverhead(sizer,
        isAgentAttached);
    long skipListSize = 0;
    boolean singleNodeSizeCalculated = false;
    long singleNodeSize = 0;
    long maxIndexDepth = 0;

    Index<K, V> r;
    Index<K, V> d;
    Index<K, V> t;

    // get a quick estimate on the size of seenList to avoid rehashing
    int numIndexes = 0;
    if (head != null) {
      r = d = head;
      numIndexes++;
      boolean isRightOfHeadIndex = true;
      do {
        if (!isRightOfHeadIndex) {
          if (d.node == null) {
            continue;
          }
          numIndexes++;
          r = d.right;
        }
        // fix for NPE seen in user testing
        if (r != null) {
          while ((r = r.right) != null) {
            if (r.node == null) {
              continue;
            }
            numIndexes++;
            t = r.down;
            if (t != null) {
              do {
                numIndexes++;
              } while ((t = t.down) != null);
            }
          } // end of right traversal
        }
        isRightOfHeadIndex = false;
      } while ((d = d.down) != null);
    }

    THashSet seenList = new THashSet(numIndexes,
        com.gemstone.gnu.trove.TObjectIdentityHashingStrategy.getInstance());
    final LogWriter l = verbose ? GemFireCacheImpl.getExisting().getLogger()
        : null;
    if (head != null) {
      maxIndexDepth = head.level;
      r = d = head;
      if (!singleNodeSizeCalculated) {
        singleNodeSize = sizer.sizeof(head);
        singleNodeSizeCalculated = true;
      }
      skipListSize += isAgentAttached ? sizer.sizeof(head) : singleNodeSize;
      seenList.add(head);
      boolean isRightOfHeadIndex = true;
      do {

        if (!isRightOfHeadIndex) {
          assert d != head;
          final SkipListNode<K, V> dn = d.node;
          if (dn == null || dn.getNext() == null || dn.getMapValue() == null) {
            continue;
          }
          if (seenList.add(d)) {
            skipListSize += isAgentAttached ? sizer.sizeof(d) : singleNodeSize;
          }
          r = d.right;
          if (verbose) {
            l.info("d.right 0x"
                + Integer.toHexString(System.identityHashCode(r)) + " of d 0x"
                + Integer.toHexString(System.identityHashCode(d)));
          }
        }
        else if (verbose) {
          l.info("head 0x" + Integer.toHexString(System.identityHashCode(head)));
        }

        // fix for NPE seen in user testing
        if (r != null) {
          while ((r = r.right) != null) {
            final SkipListNode<K, V> rn = r.node;
            if (rn == null || rn.getNext() == null || rn.getMapValue() == null) {
              continue;
            }
            if (verbose) {
              l.info("r.right 0x"
                  + Integer.toHexString(System.identityHashCode(r))
                  + " r.node.next = " + r.node.getNext() + " r.node.get() = "
                  + r.node.getMapValue());
            }

            if (seenList.add(r)) {
              skipListSize += isAgentAttached ? sizer.sizeof(r)
                  : singleNodeSize;
            }

            t = r.down;
            if (t != null) {
              do {
                final SkipListNode<K, V> tn = t.node;
                if (tn == null || tn.getNext() == null
                    || tn.getMapValue() == null) {
                  continue;
                }
                if (verbose) {
                  l.info("r.down 0x"
                      + Integer.toHexString(System.identityHashCode(t))
                      + " of r 0x"
                      + Integer.toHexString(System.identityHashCode(r))
                      + " t.node.next = " + t.node.getNext() + " t.node.get() = "
                      + t.node.getMapValue());
                }
                if (seenList.add(t)) {
                  skipListSize += isAgentAttached ? sizer.sizeof(t)
                      : singleNodeSize;
                }
              } while ((t = t.down) != null);
            }

          } // end of right traversal
        }

        isRightOfHeadIndex = false;

      } while ((d = d.down) != null); // end of SkipList indexes traversal
                                      // (both down & right)
    }

    return new long[] { entryOverhead, skipListSize, maxIndexDepth };
  }

  /* ---------------- Nodes -------------- */

  /**
   * Nodes hold keys and values, and are singly linked in sorted order, possibly
   * with some intervening marker nodes. The list is headed by a dummy node
   * accessible as head.node. The value field is declared only as Object because
   * it takes special non-V values for marker and header nodes.
   */
  @SuppressWarnings("serial")
  static final class Node<K, V> extends AtomicReference<Object> implements
      SkipListNode<K, V> {

    private final K key;

    // volatile Object value; // GemStone change; now in super.value
    volatile Node<K, V> next;

    /**
     * Creates a new regular node.
     */
    Node(K key, Object value, SkipListNode<K, V> next) {
      super(value);
      this.key = key;
      this.next = (Node<K, V>)next;
    }

    /**
     * Creates a new marker node. A marker is distinguished by having its value
     * field point to itself. Marker nodes also have null keys, a fact that is
     * exploited in a few places, but this doesn't distinguish markers from the
     * base-level header node (head.node), which also has a null key.
     */
    Node(SkipListNode<K, V> next) {
      this.key = null;
      super.set(this);
      this.next = (Node<K, V>)next;
    }

    // GemStoneAddition
    @Override
    public final K getMapKey() {
      return this.key;
    }

    // GemStoneAddition
    @Override
    public final Object getMapValue() {
      return super.get();
    }

    // GemStoneAddition
    @Override
    public final SkipListNode<K, V> getNext() {
      return this.next;
    }

    // GemStoneAddition
    @Override
    public int getVersion() {
      // no versions in default impl
      return 0;
    }

    // GemStoneAddition
    @Override
    public final void setNext(SkipListNode<K, V> next) {
      this.next = (Node<K, V>)next;
    }

    // GemStone changes below to use JDK5's CAS primitives

    /** Updater for casNext */
    @SuppressWarnings("rawtypes")
    static final AtomicReferenceFieldUpdater<SkipListNode, SkipListNode>
        nextUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
            (Class)Node.class, (Class)Node.class, "next");

    /*
    // Updater for casValue
    // Now extending AtomicReference instead of using below to avoid the
    // expensive instanceof checks in the AtomicReferenceFieldUpdater
    // compareAndSet implementation.
    static final AtomicReferenceFieldUpdater<SkipListNode, Object>
        valueUpdater = AtomicReferenceFieldUpdater.newUpdater
        (Node.class, Object.class, "value");
    */

    /**
     * compareAndSet value field
     */
    @Override
    public final boolean casValue(Object cmp, Object val) {
      return super.compareAndSet(cmp, val);
    }

    /**
     * compareAndSet next field
     */
    @Override
    public final boolean casNext(SkipListNode<K, V> cmp,
        SkipListNode<K, V> val) {
      return nextUpdater.compareAndSet(this, cmp, val);
    }

    /* (original code)
    synchronized boolean casValue(Object cmp, Object val) {
        if (value == cmp) {
            value = val;
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * compareAndSet next field
     *
    synchronized boolean casNext(Node cmp, Node val) {
        if (next == cmp) {
            next = val;
            return true;
        }
        else {
            return false;
        }
    }
    */
    // End GemStone changes for CAS primitives

    /**
     * Returns true if this node is a marker. This method isn't actually called
     * in any current code checking for markers because callers will have
     * already read value field and need to use that read (not another done
     * here) and so directly test if value points to node.
     * 
     * @return true if this node is a marker node
     */
    @Override
    public final boolean isMarker() {
      return get() == this;
    }

    /**
     * Returns true if this node is the header of base-level list.
     * 
     * @return true if this node is header node
     */
    @Override
    public final boolean isBaseHeader() {
      return get() == BASE_HEADER;
    }

    /**
     * Tries to append a deletion marker to this node.
     * 
     * @param f
     *          the assumed current successor of this node
     * @return true if successful
     */
    @Override
    public final boolean appendMarker(SkipListNode<K, V> f) {
      return casNext(f, new Node<K, V>(f));
    }

    /**
     * Helps out a deletion by appending marker or unlinking from predecessor.
     * This is called during traversals when value field seen to be null.
     * 
     * @param bi
     *          predecessor
     * @param fi
     *          successor
     */
    @Override
    public final void helpDelete(SkipListNode<K, V> bi, SkipListNode<K, V> fi) {
      final Node<K, V> b = (Node<K, V>)bi;
      final Node<K, V> f = (Node<K, V>)fi;
      /*
       * Rechecking links and then doing only one of the
       * help-out stages per call tends to minimize CAS
       * interference among helping threads.
       */
      if (f == next && this == b.next) {
        if (f == null || f.get() != f) // not already marked
          appendMarker(f);
        else
          b.casNext(this, f.next);
      }
    }

    /**
     * Returns value if this node contains a valid key-value pair, else null.
     * 
     * @return this node's value if it isn't a marker or header or is deleted,
     *         else null.
     */
    @Override
    public final V getValidValue() {
      final Object v = super.get();
      if (v != this && v != BASE_HEADER) {
        return (V)v;
      }
      else {
        return null;
      }
    }

    /**
     * Creates and returns a new SimpleImmutableEntry holding current mapping if
     * this node holds a valid value, else null.
     * 
     * @return new entry or null
     */
    @Override
    public final AbstractMap.SimpleImmutableEntry<K, V> createSnapshot() {
      final V v = getValidValue();
      if (v != null) {
        return new AbstractMap.SimpleImmutableEntry<K, V>(getMapKey(), v);
      }
      else {
        return null;
      }
    }

    @Override
    public final void clear() {
      // nothing to be done since the entire node object will be discarded
    }
  }

  // GemStoneAddition
  private static final class Factory<K, V> implements
      SkipListNodeFactory<K, V>, java.io.Serializable {

    private static final long serialVersionUID = 323646032976909765L;

    @Override
    public Node<K, V> newNode(K key, Object value,
        SkipListNode<K, V> next) {
      return new Node<K, V>(key, value, next);
    }
  }

  // GemStoneAddition
  static <K, V> SkipListNodeFactory<K, V> getDefaultFactory() {
    return new Factory<K, V>();
  }

  /* ---------------- Indexing -------------- */

  /**
   * Index nodes represent the levels of the skip list. Note that even though
   * both Nodes and Indexes have forward-pointing fields, they have different
   * types and are handled in different ways, that can't nicely be captured by
   * placing field in a shared abstract class.
   */
  static class Index<K, V> {
    final SkipListNode<K, V> node;

    final Index<K, V> down;

    volatile Index<K, V> right;

    /**
     * Creates index node with given values.
     */
    Index(SkipListNode<K, V> node, Index<K, V> down, Index<K, V> right) {
      this.node = node;
      this.down = down;
      this.right = right;
    }

    // GemStone changes below to use JDK5's CAS primitives

    /** Updater for casRight */
    @SuppressWarnings("rawtypes")
    static final AtomicReferenceFieldUpdater<Index, Index> rightUpdater =
        AtomicUpdaterFactory.newReferenceFieldUpdater(
            Index.class, Index.class, "right");

    /**
     * compareAndSet right field
     */
    final boolean casRight(Index<K, V> cmp, Index<K, V> val) {
      return rightUpdater.compareAndSet(this, cmp, val);
    }

    /* (original code)
    final synchronized boolean casRight(Index cmp, Index val) {
        if (right == cmp) {
            right = val;
            return true;
        }
        return false;
    }
    */
    // End GemStone changes for CAS primitives

    /**
     * Returns true if the node this indexes has been deleted.
     * 
     * @return true if indexed node is known to be deleted
     */
    final boolean indexesDeletedNode() {
      return node.getMapValue() == null;
    }

    /**
     * Tries to CAS newSucc as successor. To minimize races with unlink that may
     * lose this index node, if the node being indexed is known to be deleted,
     * it doesn't try to link in.
     * 
     * @param succ
     *          the expected current successor
     * @param newSucc
     *          the new successor
     * @return true if successful
     */
    final boolean link(Index<K, V> succ, Index<K, V> newSucc) {
      final SkipListNode<K, V> n = node;
      newSucc.right = succ;
      return n.getMapValue() != null && casRight(succ, newSucc);
    }

    /**
     * Tries to CAS right field to skip over apparent successor succ. Fails
     * (forcing a retraversal by caller) if this node is known to be deleted.
     * 
     * @param succ
     *          the expected current successor
     * @return true if successful
     */
    final boolean unlink(Index<K, V> succ) {
      return !indexesDeletedNode() && casRight(succ, succ.right);
    }
  }

  /* ---------------- Head nodes -------------- */

  /**
   * Nodes heading each level keep track of their level.
   */
  static final class HeadIndex<K, V> extends Index<K, V> {
    final int level;

    HeadIndex(SkipListNode<K, V> node, Index<K, V> down, Index<K, V> right,
        int level) {
      super(node, down, right);
      this.level = level;
    }
  }

  /* ---------------- Comparison utilities -------------- */

  /**
   * The default comparator assuming <code>Comparable</code> keys when no
   * explicit Comparator has been provided. This allows the code to consistently
   * use the comparator without requiring explicit checks.
   */
  public static final class ComparatorUsingComparable implements
      Comparator<Object>, Serializable {

    private static final long serialVersionUID = 4945776686139336020L;

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("rawtypes")
    @Override
    public int compare(Object o1, Object o2) {
      return ((Comparable)o1).compareTo(o2);
    }
  }

  /**
   * Returns true if given key greater than or equal to least and strictly less
   * than fence, bypassing either test if least or fence are null. Needed mainly
   * in submap operations.
   */
  final boolean inHalfOpenRange(K key, K least, K fence) {
    if (key == null)
      throw new NullPointerException();
    return ((least == null || comparator.compare(key, least) >= 0)
        && (fence == null || comparator.compare(key, fence) < 0));
  }

  /**
   * Returns true if given key greater than or equal to least and less or equal
   * to fence. Needed mainly in submap operations.
   */
  final boolean inOpenRange(K key, K least, K fence) {
    if (key == null)
      throw new NullPointerException();
    return ((least == null || comparator.compare(key, least) >= 0)
        && (fence == null || comparator.compare(key, fence) <= 0));
  }

  /* ---------------- Traversal -------------- */

  /**
   * Returns a base-level node with key strictly less than given key, or the
   * base-level header if there is no such node. Also unlinks indexes to deleted
   * nodes found along the way. Callers rely on this side-effect of clearing
   * indices to deleted nodes.
   * 
   * @param key
   *          the key
   * @return a predecessor of key
   */
  private SkipListNode<K, V> findPredecessor(Object key,
      final int[] numNodesCompared /* GemStoneAddition */) {
    if (key == null)
      throw new NullPointerException(); // don't postpone errors
    for (;;) {
      Index<K, V> q = head;
      Index<K, V> r = q.right;
      for (;;) {
        if (r != null) {
          final SkipListNode<K, V> n = r.node;
          final K k = n.getMapKey();
          if (n.getMapValue() == null) {
            if (!q.unlink(r))
              break; // restart
            r = q.right; // reread r
            continue;
          }
          if (comparator.compare(key, k) > 0) {
            // GemStone changes BEGIN
            if (numNodesCompared != null) {
              numNodesCompared[0]++;
            }
            // GemStone changes END
            q = r;
            r = r.right;
            continue;
          }
        }
        final Index<K, V> d = q.down;
        if (d != null) {
          q = d;
          r = d.right;
        }
        else
          return q.node;
      }
    }
  }

  /**
   * Returns node holding key or null if no such, clearing out any deleted nodes
   * seen along the way. Repeatedly traverses at base-level looking for key
   * starting at predecessor returned from findPredecessor, processing
   * base-level deletions as encountered. Some callers rely on this side-effect
   * of clearing deleted nodes.
   * 
   * Restarts occur, at traversal step centered on node n, if:
   * 
   * (1) After reading n's next field, n is no longer assumed predecessor b's
   * current successor, which means that we don't have a consistent 3-node
   * snapshot and so cannot unlink any subsequent deleted nodes encountered.
   * 
   * (2) n's value field is null, indicating n is deleted, in which case we help
   * out an ongoing structural deletion before retrying. Even though there are
   * cases where such unlinking doesn't require restart, they aren't sorted out
   * here because doing so would not usually outweigh cost of restarting.
   * 
   * (3) n is a marker or n's predecessor's value field is null, indicating
   * (among other possibilities) that findPredecessor returned a deleted node.
   * We can't unlink the node because we don't know its predecessor, so rely on
   * another call to findPredecessor to notice and return some earlier
   * predecessor, which it will do. This check is only strictly needed at
   * beginning of loop, (and the b.value check isn't strictly needed at all) but
   * is done each iteration to help avoid contention with other threads by
   * callers that will fail to be able to change links, and so will retry
   * anyway.
   * 
   * The traversal loops in doPut, doRemove, and findNear all include the same
   * three kinds of checks. And specialized versions appear in findFirst, and
   * findLast and their variants. They can't easily share code because each uses
   * the reads of fields held in locals occurring in the orders they were
   * performed.
   * 
   * @param key
   *          the key
   * @return node holding key, or null if no such
   */
  private SkipListNode<K, V> findNode(Object key,
      final int[] numNodesCompared /* GemStoneAddition */) {
    for (;;) {
      SkipListNode<K, V> b = findPredecessor(key,
          numNodesCompared /* GemStoneAddition */);
      SkipListNode<K, V> n = b.getNext();
      for (;;) {
        if (n == null)
          return null;
        final SkipListNode<K, V> f = n.getNext();
        if (n != b.getNext()) // inconsistent read
          break;
        final Object v = n.getMapValue();
        if (v == null) { // n is deleted
          n.helpDelete(b, f);
          break;
        }
        if (v == n || b.getMapValue() == null) // b is deleted
          break;
        final int c = comparator.compare(key, n.getMapKey());
        // GemStone changes BEGIN
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        // GemStone changes END
        if (c == 0)
          return n;
        if (c < 0)
          return null;
        b = n;
        n = f;
      }
    }
  }

  /**
   * Specialized variant of findNode to perform Map.get. Does a weak traversal,
   * not bothering to fix any deleted index nodes, returning early if it happens
   * to see key in index, and passing over any deleted base nodes, falling back
   * to getUsingFindNode only if it would otherwise return value from an ongoing
   * deletion. Also uses "bound" to eliminate need for some comparisons (see
   * Pugh Cookbook). Also folds uses of null checks and node-skipping because
   * markers have null keys.
   * 
   * @param key
   *          the key
   * @return the value, or null if absent
   */
  final V doGet(Object key, FetchFromMap fetch,
      final int[] numNodesCompared /* GemStoneAddition */) {
    SkipListNode<K, V> bound = null;
    Index<K, V> q = head;
    Index<K, V> r = q.right;
    SkipListNode<K, V> n;
    K k;
    int c;
    for (;;) {
      Index<K, V> d;
      // Traverse rights
      if (r != null && (n = r.node) != bound && (k = n.getMapKey()) != null) {
        // GemStone changes BEGIN
        c = comparator.compare(key, k);
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        if (c > 0) {
          /* (original code)
          if ((c = key.compareTo(k)) > 0) {
          */
          // GemStone changes END
          q = r;
          r = r.right;
          continue;
        }
        else if (c == 0) {
          if (fetch != null) {
            // read the version before reading the value
            final int version = n.getVersion();
            final V v = n.getValidValue();
            if (v != null) {
              fetch.setMapKey(k, version);
              return v;
            }
            else {
              return getUsingFindNode(key, fetch,
                  numNodesCompared /* GemStoneAddition */);
            }
          }
          else {
            final V v = n.getValidValue();
            return v != null ? v : getUsingFindNode(key, null,
                numNodesCompared /* GemStoneAddition */);
          }
        }
        else
          bound = n;
      }

      // Traverse down
      if ((d = q.down) != null) {
        q = d;
        r = d.right;
      }
      else
        break;
    }

    // Traverse nexts
    for (n = q.node.getNext(); n != null; n = n.getNext()) {
      if ((k = n.getMapKey()) != null) {
        // GemStone changes BEGIN
        c = comparator.compare(key, k);
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        if (c == 0) {
          /* (original code)
          if ((c = key.compareTo(k)) == 0) {
          */
          // GemStone changes END
          if (fetch != null) {
            // read the version before reading the value
            final int version = n.getVersion();
            final V v = n.getValidValue();
            if (v != null) {
              fetch.setMapKey(k, version);
              return v;
            }
            else {
              return getUsingFindNode(key, fetch,
                  numNodesCompared /* GemStoneAddition */);
            }
          }
          else {
            final V v = n.getValidValue();
            return v != null ? v : getUsingFindNode(key, null,
                numNodesCompared /* GemStoneAddition */);
          }
        }
        else if (c < 0)
          break;
      }
    }
    return null;
  }

  /**
   * Performs map.get via findNode. Used as a backup if doGet encounters an
   * in-progress deletion.
   * 
   * @param key
   *          the key
   * @return the value, or null if absent
   */
  private V getUsingFindNode(Object key, FetchFromMap fetch,
      final int[] numNodesCompared /* GemStoneAddition */) {
    /*
     * Loop needed here and elsewhere in case value field goes
     * null just as it is about to be returned, in which case we
     * lost a race with a deletion, so must retry.
     */
    for (;;) {
      final SkipListNode<K, V> n = findNode(key,
          numNodesCompared /* GemStoneAddition */);
      if (n == null) {
        return null;
      }
      if (fetch != null) {
        // read the version before reading the value
        final int version = n.getVersion();
        final V v = n.getValidValue();
        if (v != null) {
          fetch.setMapKey(n.getMapKey(), version);
          return v;
        }
      }
      else {
        final V v = n.getValidValue();
        if (v != null) {
          return v;
        }
      }
    }
  }

  /* ---------------- Insertion -------------- */

  /**
   * Main insertion method. Adds element if not present, or replaces value if
   * present and onlyIfAbsent is false.
   * 
   * @param key
   *          the key
   * @param value
   *          the value that must be associated with key
   * @param onlyIfAbsent
   *          if should not insert if already present
   * @return the old value, or null if newly inserted
   */
  final V doPut(K key, V value, boolean onlyIfAbsent,
      final int[] numNodesCompared /* GemStoneAddition */) {
    for (;;) {
      SkipListNode<K, V> b = findPredecessor(key,
          numNodesCompared /* GemStoneAddition */);
      SkipListNode<K, V> n = b.getNext();
      for (;;) {
        if (n != null) {
          final SkipListNode<K, V> f = n.getNext();
          if (n != b.getNext()) // inconsistent read
            break;
          ;
          final Object v = n.getMapValue();
          if (v == null) { // n is deleted
            n.helpDelete(b, f);
            break;
          }
          if (v == n || b.getMapValue() == null) // b is deleted
            break;
          final int c = comparator.compare(key, n.getMapKey());
          // GemStone changes BEGIN
          if (numNodesCompared != null) {
            numNodesCompared[0]++;
          }
          // GemStone changes END
          if (c > 0) {
            b = n;
            n = f;
            continue;
          }
          if (c == 0) {
            if (onlyIfAbsent || n.casValue(v, value))
              return (V)v;
            else
              break; // restart if lost race to replace value
          }
          // else c < 0; fall through
        }

        final SkipListNode<K, V> z = this.nodeFactory.newNode(key, value, n);
        if (!b.casNext(n, z)) {
          z.clear();
          break; // restart if lost race to append to b
        }
        final int level = randomLevel();
        if (level > 0)
          insertIndex(z, level, numNodesCompared /* GemStoneAddition */);
        return null;
      }
    }
  }

  // GemStoneAddition
  /**
   * Insert or update method. Adds element if not present, or replaces value if
   * present using given MapCallback.
   * 
   * @param key
   *          the key
   * @param creator
   *          the MapCavalue that must be associated with key
   * @param onlyIfAbsent
   *          skip insert if already present
   * @return the old value, or null if newly inserted
   */
  final <C, P> V doPut(K key, MapCallback<K, V, C, P> creator, C context,
      P params, boolean onlyIfAbsent, final int[] numNodesCompared) {
    // this will be set if an object was updated inline by updateValue callback
    // (i.e. no new object was created by callback e.g. for GFXD index value as
    // a set of RowLocation objects)
    V updatedValue = null;
    for (;;) {
      SkipListNode<K, V> b = findPredecessor(key, numNodesCompared);
      SkipListNode<K, V> n = b.getNext();
      for (;;) {
        if (n != null) {
          final SkipListNode<K, V> f = n.getNext();
          if (n != b.getNext()) { // inconsistent read
            break;
          }
          final Object v = n.getMapValue();
          if (v == null) { // n is deleted
            n.helpDelete(b, f);
            break;
          }
          if (v == n || b.getMapValue() == null) { // b is deleted
            break;
          }
          final K mapKey = n.getMapKey();
          final int c = comparator.compare(key, mapKey);
          if (numNodesCompared != null) {
            numNodesCompared[0]++;
          }
          if (c > 0) {
            b = n;
            n = f;
            continue;
          }
          if (c == 0) {
            final V val = (V)v;
            if (onlyIfAbsent) {
              // check if inline update already done
              if (updatedValue != null) {
                creator.onOperationFailed(key, null, updatedValue, null,
                    context, params);
              }
              return val;
            }
            // only invoke the callback if no inline update has not been done
            // on the value by a previous call to updateValue
            else if (updatedValue == null || updatedValue != val) {
              if ((updatedValue = creator.updateValue(mapKey, val, context,
                  params)) != null && n.casValue(val, updatedValue)) {
                creator.afterUpdate(key, mapKey, updatedValue, context);
                return val;
              }
              else {
                break; // restart if lost race to replace value
              }
            }
            // updateValue has been already invoked so just try CAS
            else if (n.casValue(val, updatedValue)) {
              return val;
            }
            else {
              break; // restart if lost race to replace value
            }
          }
          // else c < 0; fall through
        }

        final SkipListNode<K, V> z = this.nodeFactory.newNode(key,
            creator.newValue(key, context, params, null), n);
        if (!b.casNext(n, z)) {
          z.clear();
          break; // restart if lost race to append to b
        }
        final int level = randomLevel();
        if (level > 0) {
          insertIndex(z, level, numNodesCompared);
        }
        // check if inline update already done
        if (updatedValue != null) {
          return creator.onOperationFailed(key, null, updatedValue, null,
              context, params);
        }
        else {
          return null;
        }
      }
    }
  }

  /**
   * Returns a random level for inserting a new node. Hardwired to k=1, p=0.5,
   * max 31 (see above and Pugh's "Skip List Cookbook", sec 3.4).
   * 
   * This uses the simplest of the generators described in George Marsaglia's
   * "Xorshift RNGs" paper. This is not a high-quality generator but is
   * acceptable here.
   */
  private int randomLevel() {
    int x = randomSeed;
    x ^= x << 13;
    x ^= x >>> 17;
    randomSeed = x ^= x << 5;
    if ((x & 0x8001) != 0) // test highest and lowest bits
      return 0;
    int level = 1;
    while (((x >>>= 1) & 1) != 0)
      ++level;
    return level;
  }

  /**
   * Creates and adds index nodes for the given node.
   * 
   * @param z
   *          the node
   * @param level
   *          the level of the index
   */
  private void insertIndex(SkipListNode<K, V> z, int level,
      final int[] numNodesCompared /* GemStoneAddition */) {
    final HeadIndex<K, V> h = head;
    final int max = h.level;

    if (level <= max) {
      Index<K, V> idx = null;
      for (int i = 1; i <= level; ++i)
        idx = new Index<K, V>(z, idx, null);
      addIndex(idx, h, level, numNodesCompared /* GemStoneAddition */);
    }
    else { // Add a new level
      /*
       * To reduce interference by other threads checking for
       * empty levels in tryReduceLevel, new levels are added
       * with initialized right pointers. Which in turn requires
       * keeping levels in an array to access them while
       * creating new head index nodes from the opposite
       * direction.
       */
      level = max + 1;
      final Index<K, V>[] idxs = /*(Index[]) GemStoneAddition*/
          new Index[level + 1];
      Index<K, V> idx = null;
      for (int i = 1; i <= level; ++i)
        idxs[i] = idx = new Index<K, V>(z, idx, null);

      HeadIndex<K, V> oldh;
      int k;
      for (;;) {
        oldh = head;
        final int oldLevel = oldh.level;
        if (level <= oldLevel) { // lost race to add level
          k = level;
          break;
        }
        HeadIndex<K, V> newh = oldh;
        final SkipListNode<K, V> oldbase = oldh.node;
        for (int j = oldLevel + 1; j <= level; ++j)
          newh = new HeadIndex<K, V>(oldbase, newh, idxs[j], j);
        if (casHead(oldh, newh)) {
          k = oldLevel;
          break;
        }
      }
      addIndex(idxs[k], oldh, k, numNodesCompared /* GemStoneAddition */);
    }
  }

  /**
   * Adds given index nodes from given level down to 1.
   * 
   * @param idx
   *          the topmost index node being inserted
   * @param h
   *          the value of head to use to insert. This must be snapshotted by
   *          callers to provide correct insertion level
   * @param indexLevel
   *          the level of the index
   */
  private void addIndex(Index<K, V> idx, HeadIndex<K, V> h, int indexLevel,
      final int[] numNodesCompared /* GemStoneAddition */) {
    // Track next level to insert in case of retries
    int insertionLevel = indexLevel;
    final Object key = idx.node.getMapKey();
    if (key == null)
      throw new NullPointerException();

    // Similar to findPredecessor, but adding index nodes along
    // path to key.
    for (;;) {
      int j = h.level;
      Index<K, V> q = h;
      Index<K, V> r = q.right;
      Index<K, V> t = idx;
      for (;;) {
        if (r != null) {
          final SkipListNode<K, V> n = r.node;
          // compare before deletion check avoids needing recheck
          final int c = comparator.compare(key, n.getMapKey());
          // GemStone changes BEGIN
          if (numNodesCompared != null) {
            numNodesCompared[0]++;
          }
          // GemStone changes END
          if (n.getMapValue() == null) {
            if (!q.unlink(r))
              break;
            r = q.right;
            continue;
          }
          if (c > 0) {
            q = r;
            r = r.right;
            continue;
          }
        }

        if (j == insertionLevel) {
          // Don't insert index if node already deleted
          if (t.indexesDeletedNode()) {
            findNode(key, // cleans up
                numNodesCompared /* GemStoneAddition */);
            return;
          }
          if (!q.link(r, t))
            break; // restart
          if (--insertionLevel == 0) {
            // need final deletion check before return
            if (t.indexesDeletedNode())
              findNode(key, numNodesCompared /* GemStoneAddition */);
            return;
          }
        }

        if (--j >= insertionLevel && j < indexLevel)
          t = t.down;
        q = q.down;
        r = q.right;
      }
    }
  }

  /* ---------------- Deletion -------------- */

  /**
   * Main deletion method. Locates node, nulls value, appends a deletion marker,
   * unlinks predecessor, removes associated index nodes, and possibly reduces
   * head index level.
   * 
   * Index nodes are cleared out simply by calling findPredecessor. which
   * unlinks indexes to deleted nodes found along path to key, which will
   * include the indexes to this node. This is done unconditionally. We can't
   * check beforehand whether there are index nodes because it might be the case
   * that some or all indexes hadn't been inserted yet for this node during
   * initial search for it, and we'd like to ensure lack of garbage retention,
   * so must call to be sure.
   * 
   * @param key
   *          the key
   * @param value
   *          if non-null, the value that must be associated with key
   * @return the node, or null if not found
   */
  final V doRemove(final Object key, final Object value,
      final int[] numNodesCompared /* GemStoneAddition */) {
    for (;;) {
      SkipListNode<K, V> b = findPredecessor(key,
          numNodesCompared /* GemStoneAddition */);
      SkipListNode<K, V> n = b.getNext();
      for (;;) {
        if (n == null)
          return null;
        final SkipListNode<K, V> f = n.getNext();
        if (n != b.getNext()) // inconsistent read
          break;
        final Object v = n.getMapValue();
        if (v == null) { // n is deleted
          n.helpDelete(b, f);
          break;
        }
        if (v == n || b.getMapValue() == null) // b is deleted
          break;
        final K mapKey = n.getMapKey();
        final int c = comparator.compare(key, mapKey);
        // GemStone changes BEGIN
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        // GemStone changes END
        if (c < 0)
          return null;
        if (c > 0) {
          b = n;
          n = f;
          continue;
        }
        /*
        if (!objectEquals(value, v))
          return null;
        */
        // GemStone changes BEGIN
        final V nodeValue = (V)v;
        if (value != null && !objectEquals(value, v)) {
          return null;
        }
        // GemStone changes END
        if (!n.casValue(v, null))
          break;
        if (!n.appendMarker(f) || !b.casNext(n, f))
          findNode(key, // Retry via findNode
              numNodesCompared /* GemStoneAddition */);
        else {
          findPredecessor(key, // Clean index
              numNodesCompared /* GemStoneAddition */);
          if (head.right == null)
            tryReduceLevel();
        }
        return nodeValue;
      }
    }
  }

  // GemStoneAddition
  /**
   * Main deletion method. Locates node, nulls value, appends a deletion marker,
   * unlinks predecessor, removes associated index nodes, and possibly reduces
   * head index level.
   * 
   * Index nodes are cleared out simply by calling findPredecessor. which
   * unlinks indexes to deleted nodes found along path to key, which will
   * include the indexes to this node. This is done unconditionally. We can't
   * check beforehand whether there are index nodes because it might be the case
   * that some or all indexes hadn't been inserted yet for this node during
   * initial search for it, and we'd like to ensure lack of garbage retention,
   * so must call to be sure.
   * 
   * @param key
   *          the key
   * @param value
   *          if non-null, the value that must be associated with key
   * @return the node, or null if not found
   */
  final <C, P> V doRemove(final K key, final Object value,
      final MapCallback<K, V, C, P> cb, final C context, final P removeParams,
      final int[] numNodesCompared) {
    // this will be set if an object was updated inline by removeValue callback
    // (i.e. no new object was created by callback e.g. for GFXD index value as
    // a set of RowLocation objects)
    V updatedValue = null;
    for (;;) {
      SkipListNode<K, V> b = findPredecessor(key,
          numNodesCompared /* GemStoneAddition */);
      SkipListNode<K, V> n = b.getNext();
      for (;;) {
        if (n == null) {
          // abort the remove
          if (updatedValue != null) {
            return cb.onOperationFailed(key, value, updatedValue, null,
                context, removeParams);
          }
          else {
            return null;
          }
        }
        final SkipListNode<K, V> f = n.getNext();
        if (n != b.getNext()) // inconsistent read
          break;
        final Object v = n.getMapValue();
        if (v == null) { // n is deleted
          n.helpDelete(b, f);
          break;
        }
        if (v == n || b.getMapValue() == null) // b is deleted
          break;
        final K mapKey = n.getMapKey();
        final int c = comparator.compare(key, mapKey);
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        if (c < 0) {
          // abort the remove
          if (updatedValue != null) {
            return cb.onOperationFailed(key, value, updatedValue, null,
                context, removeParams);
          }
          else {
            return null;
          }
        }
        if (c > 0) {
          b = n;
          n = f;
          continue;
        }
        final V nodeValue = (V)v;
        Object val;
        // only invoke the callback if no inline update has not been done
        // on the value by a previous call to removeValue
        if (updatedValue == null || updatedValue != nodeValue) {
          if ((val = cb.removeValue(mapKey, value, nodeValue, context,
              removeParams)) != null) {
            if (val != MapCallback.ABORT_REMOVE_TOKEN) {
              updatedValue = (V)val;
              // perform replace
              if (doReplaceForCallback(mapKey, nodeValue, updatedValue, n, cb,
                  context, removeParams)) {
                return nodeValue;
              }
              else {
                break;
              }
            }
            else {
              // abort the remove
              if (updatedValue != null) {
                cb.onOperationFailed(key, value, updatedValue, null, context,
                    removeParams);
              }
              return null;
            }
          }
          else {
            // removeValue() succeeded, so store the result to be passed to
            // onOperationFailed() if this fails at the end due to any other
            // concurrent delete
            updatedValue = nodeValue;
          }
        }
        else {
          // perform replace
          if (doReplaceForCallback(mapKey, nodeValue, updatedValue, n, cb,
              context, removeParams)) {
            return nodeValue;
          }
          else {
            break;
          }
        }
        if (!n.casValue(v, null))
          break;
        if (!n.appendMarker(f) || !b.casNext(n, f))
          findNode(key, // Retry via findNode
              numNodesCompared /* GemStoneAddition */);
        else {
          findPredecessor(key, // Clean index
              numNodesCompared /* GemStoneAddition */);
          if (head.right == null)
            tryReduceLevel();
        }
        cb.postRemove(mapKey, value, nodeValue, context, removeParams);
        return nodeValue;
      }
    }
  }

  /**
   * Possibly reduce head level if it has no nodes. This method can (rarely)
   * make mistakes, in which case levels can disappear even though they are
   * about to contain index nodes. This impacts performance, not correctness. To
   * minimize mistakes as well as to reduce hysteresis, the level is reduced by
   * one only if the topmost three levels look empty. Also, if the removed level
   * looks non-empty after CAS, we try to change it back quick before anyone
   * notices our mistake! (This trick works pretty well because this method will
   * practically never make mistakes unless current thread stalls immediately
   * before first CAS, in which case it is very unlikely to stall again
   * immediately afterwards, so will recover.)
   * 
   * We put up with all this rather than just let levels grow because otherwise,
   * even a small map that has undergone a large number of insertions and
   * removals will have a lot of levels, slowing down access more than would an
   * occasional unwanted reduction.
   */
  private void tryReduceLevel() {
    final HeadIndex<K, V> h = head;
    HeadIndex<K, V> d;
    HeadIndex<K, V> e;
    if (h.level > 3 && (d = (HeadIndex<K, V>)h.down) != null
        && (e = (HeadIndex<K, V>)d.down) != null && e.right == null
        && d.right == null && h.right == null && casHead(h, d) && // try to set
        h.right != null) // recheck
      casHead(d, h); // try to backout
  }

  /* ---------------- Finding and removing first element -------------- */

  /**
   * Specialized variant of findNode to get first valid node.
   * 
   * @return first node or null if empty
   */
  final SkipListNode<K, V> findFirst() {
    for (;;) {
      final SkipListNode<K, V> b = head.node;
      final SkipListNode<K, V> n = b.getNext();
      if (n == null)
        return null;
      if (n.getMapValue() != null)
        return n;
      n.helpDelete(b, n.getNext());
    }
  }

  /**
   * Removes first entry; returns its snapshot.
   * 
   * @return null if empty, else snapshot of first entry
   */
  final Map.Entry<K, V> doRemoveFirstEntry() {
    for (;;) {
      final SkipListNode<K, V> b = head.node;
      final SkipListNode<K, V> n = b.getNext();
      if (n == null)
        return null;
      final SkipListNode<K, V> f = n.getNext();
      if (n != b.getNext())
        continue;
      final Object v = n.getMapValue();
      if (v == null) {
        n.helpDelete(b, f);
        continue;
      }
      if (!n.casValue(v, null))
        continue;
      if (!n.appendMarker(f) || !b.casNext(n, f))
        findFirst(); // retry
      clearIndexToFirst();
      return new AbstractMap.SimpleImmutableEntry<K, V>(n.getMapKey(), (V)v);
    }
  }

  /**
   * Clears out index nodes associated with deleted first entry.
   */
  private void clearIndexToFirst() {
    for (;;) {
      Index<K, V> q = head;
      for (;;) {
        final Index<K, V> r = q.right;
        if (r != null && r.indexesDeletedNode() && !q.unlink(r))
          break;
        if ((q = q.down) == null) {
          if (head.right == null)
            tryReduceLevel();
          return;
        }
      }
    }
  }

  /* ---------------- Finding and removing last element -------------- */

  /**
   * Specialized version of find to get last valid node.
   * 
   * @return last node or null if empty
   */
  final SkipListNode<K, V> findLast() {
    /*
     * findPredecessor can't be used to traverse index level
     * because this doesn't use comparisons.  So traversals of
     * both levels are folded together.
     */
    Index<K, V> q = head;
    for (;;) {
      Index<K, V> d, r;
      if ((r = q.right) != null) {
        if (r.indexesDeletedNode()) {
          q.unlink(r);
          q = head; // restart
        }
        else
          q = r;
      }
      else if ((d = q.down) != null) {
        q = d;
      }
      else {
        SkipListNode<K, V> b = q.node;
        SkipListNode<K, V> n = b.getNext();
        for (;;) {
          if (n == null)
            return (b.isBaseHeader()) ? null : b;
          final SkipListNode<K, V> f = n.getNext(); // inconsistent read
          if (n != b.getNext())
            break;
          final Object v = n.getMapValue();
          if (v == null) { // n is deleted
            n.helpDelete(b, f);
            break;
          }
          if (v == n || b.getMapValue() == null) // b is deleted
            break;
          b = n;
          n = f;
        }
        q = head; // restart
      }
    }
  }

  /**
   * Specialized variant of findPredecessor to get predecessor of last valid
   * node. Needed when removing the last entry. It is possible that all
   * successors of returned node will have been deleted upon return, in which
   * case this method can be retried.
   * 
   * @return likely predecessor of last node
   */
  private SkipListNode<K, V> findPredecessorOfLast() {
    for (;;) {
      Index<K, V> q = head;
      for (;;) {
        Index<K, V> d, r;
        if ((r = q.right) != null) {
          if (r.indexesDeletedNode()) {
            q.unlink(r);
            break; // must restart
          }
          // proceed as far across as possible without overshooting
          if (r.node.getNext() != null) {
            q = r;
            continue;
          }
        }
        if ((d = q.down) != null)
          q = d;
        else
          return q.node;
      }
    }
  }

  /**
   * Removes last entry; returns its snapshot. Specialized variant of doRemove.
   * 
   * @return null if empty, else snapshot of last entry
   */
  final Map.Entry<K, V> doRemoveLastEntry(
      final int[] numNodesCompared /* GemStoneAddition */) {
    for (;;) {
      SkipListNode<K, V> b = findPredecessorOfLast();
      SkipListNode<K, V> n = b.getNext();
      if (n == null) {
        if (b.isBaseHeader()) // empty
          return null;
        else
          continue; // all b's successors are deleted; retry
      }
      for (;;) {
        final SkipListNode<K, V> f = n.getNext();
        if (n != b.getNext()) // inconsistent read
          break;
        final Object v = n.getMapValue();
        if (v == null) { // n is deleted
          n.helpDelete(b, f);
          break;
        }
        if (v == n || b.getMapValue() == null) // b is deleted
          break;
        if (f != null) {
          b = n;
          n = f;
          continue;
        }
        if (!n.casValue(v, null))
          break;
        final K key = n.getMapKey();
        // GemStone changes BEGIN
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        // GemStone changes END
        if (!n.appendMarker(f) || !b.casNext(n, f))
          findNode(key, // Retry via findNode
              numNodesCompared /* GemStoneAddition */);
        else {
          findPredecessor(key, // Clean index
              numNodesCompared /* GemStoneAddition */);
          if (head.right == null)
            tryReduceLevel();
        }
        return new AbstractMap.SimpleImmutableEntry<K, V>(key, (V)v);
      }
    }
  }

  // GemStone addition
  private final boolean objectEquals(final Object v1, final Object v2) {
    return this.useEquals ? v1.equals(v2) : (v1 == v2);
  }

  /* ---------------- Relational operations -------------- */

  // Control values OR'ed as arguments to findNear

  private static final int EQ = 1;

  private static final int LT = 2;

  private static final int GT = 0; // Actually checked as !LT

  /**
   * Utility for ceiling, floor, lower, higher methods.
   * 
   * @param key
   *          the key
   * @param rel
   *          the relation -- OR'ed combination of EQ, LT, GT
   * @return nearest node fitting relation, or null if no such
   */
  final SkipListNode<K, V> findNear(K key, int rel,
      final int[] numNodesCompared /* GemStoneAddition */) {
    for (;;) {
      SkipListNode<K, V> b = findPredecessor(key,
          numNodesCompared /* GemStoneAddition */);
      SkipListNode<K, V> n = b.getNext();
      for (;;) {
        if (n == null)
          return ((rel & LT) == 0 || b.isBaseHeader()) ? null : b;
        final SkipListNode<K, V> f = n.getNext();
        if (n != b.getNext()) // inconsistent read
          break;
        final Object v = n.getMapValue();
        if (v == null) { // n is deleted
          n.helpDelete(b, f);
          break;
        }
        if (v == n || b.getMapValue() == null) // b is deleted
          break;
        final int c = comparator.compare(key, n.getMapKey());
        // GemStone changes BEGIN
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        // GemStone changes END
        if ((c == 0 && (rel & EQ) != 0) || (c < 0 && (rel & LT) == 0))
          return n;
        if (c <= 0 && (rel & LT) != 0)
          return (b.isBaseHeader()) ? null : b;
        b = n;
        n = f;
      }
    }
  }

  /**
   * Returns SimpleImmutableEntry for results of findNear.
   * 
   * @param key
   *          the key
   * @param rel
   *          the relation -- OR'ed combination of EQ, LT, GT
   * @return Entry fitting relation, or null if no such
   */
  final AbstractMap.SimpleImmutableEntry<K, V> getNear(K key, int rel) {
    for (;;) {
      final SkipListNode<K, V> n = findNear(key, rel,
          null /* GemStoneAddition */);
      if (n == null)
        return null;
      final AbstractMap.SimpleImmutableEntry<K, V> e = n.createSnapshot();
      if (e != null)
        return e;
    }
  }

  /* ---------------- Constructors -------------- */

  /**
   * Constructs a new, empty map, sorted according to the
   * {@linkplain Comparable natural ordering} of the keys.
   */
  public ConcurrentSkipListMap() {
    this.comparator = new ComparatorUsingComparable();
    this.useEquals = true;
    this.skipSubMapRangeCheck = false;
    // GemStoneAddition
    this.nodeFactory = ConcurrentSkipListMap.<K, V> getDefaultFactory();
    initialize();
  }

  /**
   * Constructs a new, empty map, sorted according to the specified comparator.
   * 
   * @param comparator
   *          the comparator that will be used to order this map. If
   *          <tt>null</tt>, the {@linkplain Comparable natural ordering} of the
   *          keys will be used.
   * @param useIdentityForValues
   *          if true, then when comparing values for replace/remove and other
   *          such API, only identity equality will be checked instead of equals
   *          method invocation
   * @param skipSubMapRangeCheck
   *          if true, then skip the range check for low/high keys representing
   *          a valid range
   */
  public ConcurrentSkipListMap(Comparator<? super K> comparator,
      boolean useIdentityForValues, boolean skipSubMapRangeCheck) {
    this(comparator, useIdentityForValues, skipSubMapRangeCheck,
        ConcurrentSkipListMap.<K, V> getDefaultFactory());
  }

  /**
   * Constructs a new, empty map, sorted according to the specified comparator.
   * 
   * @param comparator
   *          the comparator that will be used to order this map. If
   *          <tt>null</tt>, the {@linkplain Comparable natural ordering} of the
   *          keys will be used.
   * @param useIdentityForValues
   *          if true, then when comparing values for replace/remove and other
   *          such API, only identity equality will be checked instead of equals
   *          method invocation
   * @param skipSubMapRangeCheck
   *          if true, then skip the range check for low/high keys representing
   *          a valid range
   */
  @SuppressWarnings("rawtypes")
  public ConcurrentSkipListMap(Comparator<? super K> comparator,
      boolean useIdentityForValues, boolean skipSubMapRangeCheck,
      SkipListNodeFactory<K, V> nodeFactory /* GemStoneAddition */) {
    this.comparator = comparator != null ? (Comparator)comparator
        : new ComparatorUsingComparable();
    this.useEquals = !useIdentityForValues;
    this.skipSubMapRangeCheck = skipSubMapRangeCheck;
    this.nodeFactory = nodeFactory; // GemStoneAddition
    initialize();
  }

  /**
   * Constructs a new map containing the same mappings as the given map, sorted
   * according to the {@linkplain Comparable natural ordering} of the keys.
   * 
   * @param m
   *          the map whose mappings are to be placed in this map
   * @throws ClassCastException
   *           if the keys in <tt>m</tt> are not {@link Comparable}, or are not
   *           mutually comparable
   * @throws NullPointerException
   *           if the specified map or any of its keys or values are null
   */
  public ConcurrentSkipListMap(Map<K, V> m) {
    this(m, ConcurrentSkipListMap.<K, V> getDefaultFactory());
  }

  /**
   * Constructs a new map containing the same mappings as the given map, sorted
   * according to the {@linkplain Comparable natural ordering} of the keys.
   * 
   * @param m
   *          the map whose mappings are to be placed in this map
   * @throws ClassCastException
   *           if the keys in <tt>m</tt> are not {@link Comparable}, or are not
   *           mutually comparable
   * @throws NullPointerException
   *           if the specified map or any of its keys or values are null
   */
  public ConcurrentSkipListMap(Map<K, V> m,
      SkipListNodeFactory<K, V> nodeFactory /* GemStoneAddition */) {
    this.comparator = new ComparatorUsingComparable();
    this.useEquals = true;
    this.skipSubMapRangeCheck = false;
    this.nodeFactory = nodeFactory; // GemStoneAddition
    initialize();
    putAll(m);
  }

  /**
   * Constructs a new map containing the same mappings and using the same
   * ordering as the specified sorted map.
   * 
   * @param m
   *          the sorted map whose mappings are to be placed in this map, and
   *          whose comparator is to be used to sort this map
   * @throws NullPointerException
   *           if the specified sorted map or any of its keys or values are null
   */
  public ConcurrentSkipListMap(SortedMap<K, V> m) {
    this(m, false, false, ConcurrentSkipListMap.<K, V> getDefaultFactory());
  }

  /**
   * Constructs a new map containing the same mappings and using the same
   * ordering as the specified sorted map.
   * 
   * @param m
   *          the sorted map whose mappings are to be placed in this map, and
   *          whose comparator is to be used to sort this map
   * @param useIdentityForValues
   *          if true, then when comparing values for replace/remove and other
   *          such API, only identity equality will be checked instead of equals
   *          method invocation
   * @param skipSubMapRangeCheck
   *          if true, then skip the range check for low/high keys representing
   *          a valid range
   * @throws NullPointerException
   *           if the specified sorted map or any of its keys or values are null
   */
  public ConcurrentSkipListMap(SortedMap<K, V> m,
      boolean useIdentityForValues, boolean skipSubMapRangeCheck,
      SkipListNodeFactory<K, V> nodeFactory /* GemStoneAddition */) {
    Comparator<Object> cmp = (Comparator<Object>)m.comparator();
    this.comparator = cmp != null ? cmp : new ComparatorUsingComparable();
    this.useEquals = !useIdentityForValues;
    this.skipSubMapRangeCheck = skipSubMapRangeCheck;
    this.nodeFactory = nodeFactory; // GemStoneAddition
    initialize();
    buildFromSorted(m.entrySet().iterator());
  }

  /**
   * Returns a shallow copy of this <tt>ConcurrentSkipListMap</tt> instance.
   * (The keys and values themselves are not cloned.)
   * 
   * @return a shallow copy of this map
   */
  @Override
  // GemStoneAddition
  public final Object clone() {
    ConcurrentSkipListMap<K, V> clone = null;
    try {
      clone = (ConcurrentSkipListMap<K, V>)super.clone();
    } catch (final CloneNotSupportedException e) {
      throw new InternalError();
    }

    clone.initialize();
    clone.buildFromSorted(entrySet().iterator());
    return clone;
  }

  /**
   * Streamlined bulk insertion to initialize from elements of given sorted map.
   * Call only from constructor or clone method.
   */
  public void buildFromSorted(Iterator<Map.Entry<K, V>> it) {
    if (it == null)
      throw new NullPointerException();

    HeadIndex<K, V> h = head;

    if (h.right != null || h.down != null) {
      throw new IllegalStateException("buildFromSorted: map not empty!");
    }

    SkipListNode<K, V> basepred = h.node;

    // Track the current rightmost node at each level. Uses an
    // ArrayList to avoid committing to initial or maximum level.
    final ArrayList<Index<K, V>> preds = new ArrayList<Index<K, V>>();

    // initialize
    for (int i = 0; i <= h.level; ++i)
      preds.add(null);
    Index<K, V> q = h;
    for (int i = h.level; i > 0; --i) {
      preds.set(i, q);
      q = q.down;
    }

    while (it.hasNext()) {
      final Map.Entry<? extends K, ? extends V> e = it.next();
      int j = randomLevel();
      if (j > h.level) {
        j = h.level + 1;
      }
      final K k = e.getKey();
      final Object v = e.getValue();
      if (k == null || v == null) {
        throw new NullPointerException();
      }
      final SkipListNode<K, V> z = this.nodeFactory.newNode(k, v, null);
      basepred.setNext(z);
      basepred = z;
      if (j > 0) {
        Index<K, V> idx = null;
        for (int i = 1; i <= j; ++i) {
          idx = new Index<K, V>(z, idx, null);
          if (i > h.level)
            h = new HeadIndex<K, V>(h.node, h, idx, i);

          if (i < preds.size()) {
            preds.get(i).right = idx;
            preds.set(i, idx);
          }
          else
            preds.add(idx);
        }
      }
    }
    head = h;
  }

  /* ---------------- Serialization -------------- */

  /**
   * Save the state of this map to a stream.
   * 
   * @serialData The key (Object) and value (Object) for each key-value mapping
   *             represented by the map, followed by <tt>null</tt>. The
   *             key-value mappings are emitted in key-order (as determined by
   *             the Comparator, or by the keys' natural ordering if no
   *             Comparator).
   */
  private void writeObject(java.io.ObjectOutputStream s)
      throws java.io.IOException {
    // Write out the Comparator and any hidden stuff
    s.defaultWriteObject();

    // Write out keys and values (alternating)
    for (SkipListNode<K, V> n = findFirst(); n != null; n = n.getNext()) {
      final Object k = n.getMapKey();
      final Object v = n.getValidValue();
      if (v != null) {
        s.writeObject(k);
        s.writeObject(v);
      }
    }
    s.writeObject(null);
  }

  /**
   * Reconstitute the map from a stream.
   */
  private void readObject(final java.io.ObjectInputStream s)
      throws java.io.IOException, ClassNotFoundException {
    // Read in the Comparator and any hidden stuff
    s.defaultReadObject();
    // Reset transients
    initialize();

    /*
     * This is nearly identical to buildFromSorted, but is
     * distinct because readObject calls can't be nicely adapted
     * as the kind of iterator needed by buildFromSorted. (They
     * can be, but doing so requires type cheats and/or creation
     * of adapter classes.) It is simpler to just adapt the code.
     */

    HeadIndex<K, V> h = head;
    SkipListNode<K, V> basepred = h.node;
    final ArrayList<Index<K, V>> preds = new ArrayList<Index<K, V>>();
    for (int i = 0; i <= h.level; ++i)
      preds.add(null);
    Index<K, V> q = h;
    for (int i = h.level; i > 0; --i) {
      preds.set(i, q);
      q = q.down;
    }

    for (;;) {
      final Object k = s.readObject();
      if (k == null)
        break;
      final Object v = s.readObject();
      if (v == null)
        throw new NullPointerException();
      final K key = (K)k;
      int j = randomLevel();
      if (j > h.level)
        j = h.level + 1;
      final SkipListNode<K, V> z = this.nodeFactory.newNode(key, v, null);
      basepred.setNext(z);
      basepred = z;
      if (j > 0) {
        Index<K, V> idx = null;
        for (int i = 1; i <= j; ++i) {
          idx = new Index<K, V>(z, idx, null);
          if (i > h.level)
            h = new HeadIndex<K, V>(h.node, h, idx, i);

          if (i < preds.size()) {
            preds.get(i).right = idx;
            preds.set(i, idx);
          }
          else
            preds.add(idx);
        }
      }
    }
    head = h;
  }

  /* ------ Map API methods ------ */

  /**
   * Returns <tt>true</tt> if this map contains a mapping for the specified key.
   * 
   * @param key
   *          key whose presence in this map is to be tested
   * @return <tt>true</tt> if this map contains a mapping for the specified key
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key is null
   */
  @Override
  // GemStoneAddition
  public final boolean containsKey(Object key) {
    return doGet(key, null, null) != null;
  }

  /**
   * Returns the value to which the specified key is mapped, or {@code null} if
   * this map contains no mapping for the key.
   * 
   * <p>
   * More formally, if this map contains a mapping from a key {@code k} to a
   * value {@code v} such that {@code key} compares equal to {@code k} according
   * to the map's ordering, then this method returns {@code v}; otherwise it
   * returns {@code null}. (There can be at most one such mapping.)
   * 
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key is null
   */
  @Override
  // GemStoneAddition
  public final V get(Object key) {
    return doGet(key, null, null);
  }

  // GemStoneAddition
  /**
   * Returns the value to which the specified key is mapped, or {@code null} if
   * this map contains no mapping for the key. Allows passing a
   * {@link FetchFromMap} argument to fetch the map key.
   */
  // GemStoneAddition
  public final V get(Object key, FetchFromMap fetch, int[] numNodesCompared) {
    return doGet(key, fetch, null);
  }

  /**
   * Associates the specified value with the specified key in this map. If the
   * map previously contained a mapping for the key, the old value is replaced.
   * 
   * @param key
   *          key with which the specified value is to be associated
   * @param value
   *          value to be associated with the specified key
   * @return the previous value associated with the specified key, or
   *         <tt>null</tt> if there was no mapping for the key
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key or value is null
   */
  @Override
  // GemStoneAddition
  public final V put(K key, V value) {
    if (value == null)
      throw new NullPointerException();
    return doPut(key, value, false, null /* GemStoneAddition */);
  }

  /**
   * Removes the mapping for the specified key from this map if present.
   * 
   * @param key
   *          key for which mapping should be removed
   * @return the previous value associated with the specified key, or
   *         <tt>null</tt> if there was no mapping for the key
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key is null
   */
  @Override
  // GemStoneAddition
  public final V remove(Object key) {
    return doRemove(key, null, null);
  }

  /**
   * Returns <tt>true</tt> if this map maps one or more keys to the specified
   * value. This operation requires time linear in the map size.
   * 
   * @param value
   *          value whose presence in this map is to be tested
   * @return <tt>true</tt> if a mapping to <tt>value</tt> exists; <tt>false</tt>
   *         otherwise
   * @throws NullPointerException
   *           if the specified value is null
   */
  @Override
  // GemStoneAddition
  public final boolean containsValue(Object value) {
    if (value == null)
      throw new NullPointerException();
    for (SkipListNode<K, V> n = findFirst(); n != null; n = n.getNext()) {
      final Object v = n.getValidValue();
      if (v != null && objectEquals(value, v))
        return true;
    }
    return false;
  }

  /**
   * Returns the number of key-value mappings in this map. If this map contains
   * more than <tt>Integer.MAX_VALUE</tt> elements, it returns
   * <tt>Integer.MAX_VALUE</tt>.
   * 
   * <p>
   * Beware that, unlike in most collections, this method is <em>NOT</em> a
   * constant-time operation. Because of the asynchronous nature of these maps,
   * determining the current number of elements requires traversing them all to
   * count them. Additionally, it is possible for the size to change during
   * execution of this method, in which case the returned result will be
   * inaccurate. Thus, this method is typically not very useful in concurrent
   * applications.
   * 
   * @return the number of elements in this map
   */
  @Override
  // GemStoneAddition
  public final int size() {
    long count = 0;
    for (SkipListNode<K, V> n = findFirst(); n != null; n = n.getNext()) {
      if (n.getValidValue() != null)
        ++count;
    }
    return (count >= Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int)count;
  }

  /**
   * Returns <tt>true</tt> if this map contains no key-value mappings.
   * 
   * @return <tt>true</tt> if this map contains no key-value mappings
   */
  @Override
  // GemStoneAddition
  public final boolean isEmpty() {
    return findFirst() == null;
  }

  /**
   * Removes all of the mappings from this map.
   */
  @Override
  // GemStoneAddition
  public final void clear() {
    initialize();
  }

  /* ---------------- View methods -------------- */

  /*
   * Note: Lazy initialization works for views because view classes
   * are stateless/immutable so it doesn't matter wrt correctness if
   * more than one is created (which will only rarely happen).  Even
   * so, the following idiom conservatively ensures that the method
   * returns the one it created if it does so, not one created by
   * another racing thread.
   */

  /**
   * Returns a {@link NavigableSet} view of the keys contained in this map. The
   * set's iterator returns the keys in ascending order. The set is backed by
   * the map, so changes to the map are reflected in the set, and vice-versa.
   * The set supports element removal, which removes the corresponding mapping
   * from the map, via the {@code Iterator.remove}, {@code Set.remove},
   * {@code removeAll}, {@code retainAll}, and {@code clear} operations. It does
   * not support the {@code add} or {@code addAll} operations.
   * 
   * <p>
   * The view's {@code iterator} is a "weakly consistent" iterator that will
   * never throw {@link java.util.ConcurrentModificationException}, and
   * guarantees to traverse elements as they existed upon construction of the
   * iterator, and may (but is not guaranteed to) reflect any modifications
   * subsequent to construction.
   * 
   * <p>
   * This method is equivalent to method {@code navigableKeySet}.
   * 
   * @return a navigable set view of the keys in this map
   */
  @Override
  // GemStoneAddition
  public final NavigableSet<K> keySet() {
    final KeySet<K, V> ks = keySet;
    return (ks != null) ? ks : (keySet = new KeySet<K, V>(this));
  }

  public final NavigableSet<K> navigableKeySet() {
    final KeySet<K, V> ks = keySet;
    return (ks != null) ? ks : (keySet = new KeySet<K, V>(this));
  }

  /**
   * Returns a {@link Collection} view of the values contained in this map. The
   * collection's iterator returns the values in ascending order of the
   * corresponding keys. The collection is backed by the map, so changes to the
   * map are reflected in the collection, and vice-versa. The collection
   * supports element removal, which removes the corresponding mapping from the
   * map, via the <tt>Iterator.remove</tt>, <tt>Collection.remove</tt>,
   * <tt>removeAll</tt>, <tt>retainAll</tt> and <tt>clear</tt> operations. It
   * does not support the <tt>add</tt> or <tt>addAll</tt> operations.
   * 
   * <p>
   * The view's <tt>iterator</tt> is a "weakly consistent" iterator that will
   * never throw {@link java.util.ConcurrentModificationException}, and
   * guarantees to traverse elements as they existed upon construction of the
   * iterator, and may (but is not guaranteed to) reflect any modifications
   * subsequent to construction.
   */
  @Override
  // GemStoneAddition
  public final Collection<V> values() {
    final Values<K, V> vs = values;
    return (vs != null) ? vs : (values = new Values<K, V>(this));
  }

  /**
   * Returns a {@link Set} view of the mappings contained in this map. The set's
   * iterator returns the entries in ascending key order. The set is backed by
   * the map, so changes to the map are reflected in the set, and vice-versa.
   * The set supports element removal, which removes the corresponding mapping
   * from the map, via the <tt>Iterator.remove</tt>, <tt>Set.remove</tt>,
   * <tt>removeAll</tt>, <tt>retainAll</tt> and <tt>clear</tt> operations. It
   * does not support the <tt>add</tt> or <tt>addAll</tt> operations.
   * 
   * <p>
   * The view's <tt>iterator</tt> is a "weakly consistent" iterator that will
   * never throw {@link java.util.ConcurrentModificationException}, and
   * guarantees to traverse elements as they existed upon construction of the
   * iterator, and may (but is not guaranteed to) reflect any modifications
   * subsequent to construction.
   * 
   * <p>
   * The <tt>Map.Entry</tt> elements returned by <tt>iterator.next()</tt> do
   * <em>not</em> support the <tt>setValue</tt> operation.
   * 
   * @return a set view of the mappings contained in this map, sorted in
   *         ascending key order
   */
  @Override
  // GemStoneAddition
  public final Set<Map.Entry<K, V>> entrySet() {
    final EntrySet es = entrySet;
    return (es != null) ? es : (entrySet = new EntrySet(this, this.useEquals));
  }

  public final ConcurrentNavigableMap<K, V> descendingMap() {
    final ConcurrentNavigableMap<K, V> dm = descendingMap;
    return (dm != null) ? dm : (descendingMap = new SubMap<K, V>(this, null,
        false, null, false, true, null /* GemStoneAddition */));
  }

  // GemStoneAddition
  public final ConcurrentNavigableMap<K, V> descendingMap(
      final int[] numNodesCompared) {
    final ConcurrentNavigableMap<K, V> dm = descendingMap;
    return (dm != null) ? dm : (descendingMap = new SubMap<K, V>(this, null,
        false, null, false, true, numNodesCompared));
  }

  public final NavigableSet<K> descendingKeySet() {
    return descendingMap().navigableKeySet();
  }

  /* ---------------- AbstractMap Overrides -------------- */

  /**
   * Compares the specified object with this map for equality. Returns
   * <tt>true</tt> if the given object is also a map and the two maps represent
   * the same mappings. More formally, two maps <tt>m1</tt> and <tt>m2</tt>
   * represent the same mappings if <tt>m1.entrySet().equals(m2.entrySet())</tt>
   * . This operation may return misleading results if either map is
   * concurrently modified during execution of this method.
   * 
   * @param o
   *          object to be compared for equality with this map
   * @return <tt>true</tt> if the specified object is equal to this map
   */
  @Override
  // GemStoneAddition
  public final boolean equals(Object o) {
    if (o == this)
      return true;
    if (!(o instanceof Map<?, ?>))
      return false;
    final Map<K, V> m = (Map<K, V>)o;
    try {
      for (final Iterator<Map.Entry<K, V>> itr = this.entrySet().iterator(); itr
          .hasNext();) {
        final Map.Entry<K, V> e = itr.next();
        if (!objectEquals(e.getValue(), m.get(e.getKey())))
          return false;
      }
      for (final Iterator<Map.Entry<K, V>> itr = m.entrySet().iterator(); itr
          .hasNext();) {
        final Map.Entry<K, V> e = itr.next();
        final Object k = e.getKey();
        final Object v = e.getValue();
        if (k == null || v == null || !objectEquals(v, get(k)))
          return false;
      }
      return true;
    } catch (final ClassCastException unused) {
      return false;
    } catch (final NullPointerException unused) {
      return false;
    }
  }

  /* ------ ConcurrentMap API methods ------ */

  /**
   * {@inheritDoc}
   * 
   * @return the previous value associated with the specified key, or
   *         <tt>null</tt> if there was no mapping for the key
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key or value is null
   */
  public final V putIfAbsent(K key, V value) {
    if (value == null)
      throw new NullPointerException();
    return doPut(key, value, true, null /* GemStoneAddition */);
  }

  /**
   * {@inheritDoc}
   * 
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final boolean remove(Object key, Object value) {
    if (key == null)
      throw new NullPointerException();
    if (value == null)
      return false;
    return doRemove(key, value, null) != null;
  }

  /**
   * Remove a key from map, checking the condition or value to replace in
   * {@link MapCallback#removeValue} and invoking {@link MapCallback#postRemove}
   * after removal.
   * 
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final <C, P> boolean remove(K key, Object value,
      MapCallback<K, V, C, P> cb, C context, P removeParams,
      final int[] numNodesCompared) {
    if (key != null) {
      return doRemove(key, value, cb, context, removeParams,
          numNodesCompared) != null;
    }
    else {
      throw new NullPointerException();
    }
  }

  /**
   * {@inheritDoc}
   * 
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if any of the arguments are null
   */
  public final boolean replace(K key, V oldValue, V newValue) {
    if (oldValue == null || newValue == null)
      throw new NullPointerException();
    final SkipListNode<K, V> keyNode = key instanceof SkipListNode<?, ?>
        ? (SkipListNode<K, V>)key : null;
    for (;;) {
      final SkipListNode<K, V> n = keyNode != null
          && keyNode.getValidValue() != null ? keyNode : findNode(key, null);
      if (n == null)
        return false;
      final Object v = n.getMapValue();
      if (v != null) {
        if (!objectEquals(oldValue, v))
          return false;
        if (n.casValue(v, newValue))
          return true;
      }
    }
  }

  // GemStone changes BEGIN

  /**
   * Associates a possibly new value with the specified key in this map. If the
   * map previously contained a mapping for the key, the old value is replaced
   * by invoking the provided
   * {@link MapCallback#updateValue(Object, Object, Object, Object)} method . If
   * there was no previous mapping for the key, a new value is created by
   * invoking {@link MapCallback#newValue} (does not support {@link MapResult}).
   * 
   * @param key
   *          key with which the specified value is to be associated
   * @param creator
   *          callback to create or update value associated with the key
   * @param context
   *          any context object to be passed to MapCallback
   * @param params
   *          any object parameters to be passed to MapCallback
   * @param numNodesCompared
   *          if non-null then return the number of index nodes compared in this
   *          operation
   * 
   * @return the previous value associated with the specified key, or
   *         <tt>null</tt> if there was no mapping for the key
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key or creator is null
   */
  public final <C, P> V put(K key, MapCallback<K, V, C, P> creator, C context,
      P params, int[] numNodesCompared) {
    return doPut(key, creator, context, params, false, numNodesCompared);
  }

  /**
   * Like {@link #replace(Object, Object, Object)} but invokes
   * {@link MapCallback} methods <code>updateValue</code>,
   * <code>beforeReplace</code>, <code>afterReplace</code>,
   * <code>onReplaceFailed</code>.
   * 
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if any of the arguments are null
   */
  public final <C, P> boolean replace(final K key, final V oldValue,
      final V newValue, final MapCallback<K, V, C, P> cb, final C context,
      final P params, final int[] numNodesCompared) {
    if (oldValue == null || cb == null) {
      throw new NullPointerException();
    }
    // this will be set if an object was updated inline by replaceValue callback
    // (i.e. no new object was created by callback e.g. for GFXD index value as
    // a set of RowLocation objects)
    V updatedValue = null;

    final SkipListNode<K, V> keyNode = key instanceof SkipListNode<?, ?>
        ? (SkipListNode<K, V>)key : null;
    for (;;) {
      final SkipListNode<K, V> n = keyNode != null
          && keyNode.getValidValue() != null ? keyNode : findNode(key,
          numNodesCompared);
      if (n == null) {
        if (updatedValue != null) {
          return cb.onOperationFailed(key, oldValue, updatedValue, newValue,
              context, params) != null;
        }
        else {
          return false;
        }
      }
      final V v = n.getValidValue();
      if (v != null) {
        final K mapKey = n.getMapKey();
        // only invoke the callback if no inline update has not been done
        // on the value by a previous call to replaceValue
        if (updatedValue == null || updatedValue != v) {
          updatedValue = cb.replaceValue(mapKey, oldValue, v, newValue,
              context, params);
          if (updatedValue == null) {
            return false;
          }
        }
        if (doReplaceForCallback(mapKey, v, updatedValue, n, cb, context,
            params)) {
          return true;
        }
      }
    }
  }

  private final <C, P> boolean doReplaceForCallback(K mapKey, V nodeValue,
      V newValue, final SkipListNode<K, V> n, final MapCallback<K, V, C, P> cb,
      final C context, final P params) {
    final Object beforeResult = cb.beforeReplace(mapKey, newValue, context,
        params);
    if (n.casValue(nodeValue, newValue)) {
      cb.afterReplace(mapKey, newValue, beforeResult, context, params);
      return true;
    }
    else {
      cb.onReplaceFailed(mapKey, newValue, beforeResult, context, params);
      return false;
    }
  }

// GemStone changes END

  /**
   * {@inheritDoc}
   * 
   * @return the previous value associated with the specified key, or
   *         <tt>null</tt> if there was no mapping for the key
   * @throws ClassCastException
   *           if the specified key cannot be compared with the keys currently
   *           in the map
   * @throws NullPointerException
   *           if the specified key or value is null
   */
  public final V replace(K key, V value) {
    if (value == null)
      throw new NullPointerException();
    final SkipListNode<K, V> keyNode = key instanceof SkipListNode<?, ?>
        ? (SkipListNode<K, V>)key : null;
    for (;;) {
      final SkipListNode<K, V> n = keyNode != null
          && keyNode.getValidValue() != null ? keyNode : findNode(key, null);
      if (n == null)
        return null;
      final V v = n.getValidValue();
      if (v != null && n.casValue(v, value))
        return v;
    }
  }

  /* ------ SortedMap API methods ------ */

  public final Comparator<? super K> comparator() {
    return this.comparator instanceof ComparatorUsingComparable ? null
        : this.comparator;
  }

  /**
   * @throws NoSuchElementException
   *           {@inheritDoc}
   */
  public final K firstKey() {
    final SkipListNode<K, V> n = findFirst();
    if (n == null)
      throw new NoSuchElementException();
    return n.getMapKey();
  }

  /**
   * @throws NoSuchElementException
   *           {@inheritDoc}
   */
  public final K lastKey() {
    final SkipListNode<K, V> n = findLast();
    if (n == null)
      throw new NoSuchElementException();
    return n.getMapKey();
  }

  // GemStoneAddition
  /**
   * Like {@link #firstKey()} but does not throw {@link NoSuchElementException}
   * rather returns null. Also checks for a valid non-null value for the key
   * before returning.
   */
  public final K firstValidKey() {
    final SkipListNode<K, V> n = findFirst();
    if (n != null) {
      final K k = n.getMapKey();
      if (n.getValidValue() != null) {
        return k;
      }
    }
    return null;
  }

  // GemStoneAddition
  /**
   * Like {@link #lastKey()} but does not throw {@link NoSuchElementException}
   * rather returns null. Also checks for a valid non-null value for the key
   * before returning.
   */
  public final K lastValidKey() {
    final SkipListNode<K, V> n = findLast();
    if (n != null) {
      final K k = n.getMapKey();
      if (n.getValidValue() != null) {
        return k;
      }
    }
    return null;
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if {@code fromKey} or {@code toKey} is null
   * @throws IllegalArgumentException
   *           {@inheritDoc}
   */
  public final ConcurrentNavigableMap<K, V> subMap(K fromKey,
      boolean fromInclusive, K toKey, boolean toInclusive) {
    if (fromKey == null || toKey == null)
      throw new NullPointerException();
    return new SubMap<K, V>(this, fromKey, fromInclusive, toKey, toInclusive,
        false, null /* GemStoneAddition */);
  }

  // GemStoneAddition
  public final ConcurrentNavigableMap<K, V> subMap(K fromKey,
      boolean fromInclusive, K toKey, boolean toInclusive,
      final int[] numNodesCompared) {
    if (fromKey == null || toKey == null)
      throw new NullPointerException();
    return new SubMap<K, V>(this, fromKey, fromInclusive, toKey, toInclusive,
        false, numNodesCompared);
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if {@code toKey} is null
   * @throws IllegalArgumentException
   *           {@inheritDoc}
   */
  public final ConcurrentNavigableMap<K, V> headMap(K toKey,
      boolean inclusive) {
    if (toKey == null)
      throw new NullPointerException();
    return new SubMap<K, V>(this, null, false, toKey, inclusive, false,
        null /* GemStoneAddition */);
  }

  // GemStoneAddition
  public final ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive,
      final int[] numNodesCompared) {
    if (toKey == null)
      throw new NullPointerException();
    return new SubMap<K, V>(this, null, false, toKey, inclusive, false,
        numNodesCompared);
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if {@code fromKey} is null
   * @throws IllegalArgumentException
   *           {@inheritDoc}
   */
  public final ConcurrentNavigableMap<K, V> tailMap(K fromKey,
      boolean inclusive) {
    if (fromKey == null)
      throw new NullPointerException();
    return new SubMap<K, V>(this, fromKey, inclusive, null, false, false,
        null /* GemStoneAddition */);
  }

  // GemStoneAddition
  public final ConcurrentNavigableMap<K, V> tailMap(K fromKey,
      boolean inclusive, final int[] numNodesCompared) {
    if (fromKey == null)
      throw new NullPointerException();
    return new SubMap<K, V>(this, fromKey, inclusive, null, false, false,
        numNodesCompared);
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if {@code fromKey} or {@code toKey} is null
   * @throws IllegalArgumentException
   *           {@inheritDoc}
   */
  public final ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
    return subMap(fromKey, true, toKey, false);
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if {@code toKey} is null
   * @throws IllegalArgumentException
   *           {@inheritDoc}
   */
  public final ConcurrentNavigableMap<K, V> headMap(K toKey) {
    return headMap(toKey, false);
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if {@code fromKey} is null
   * @throws IllegalArgumentException
   *           {@inheritDoc}
   */
  public final ConcurrentNavigableMap<K, V> tailMap(K fromKey) {
    return tailMap(fromKey, true);
  }

  /* ---------------- Relational operations -------------- */

  /**
   * Returns a key-value mapping associated with the greatest key strictly less
   * than the given key, or <tt>null</tt> if there is no such key. The returned
   * entry does <em>not</em> support the <tt>Entry.setValue</tt> method.
   * 
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final Map.Entry<K, V> lowerEntry(K key) {
    return getNear(key, LT);
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final K lowerKey(K key) {
    final SkipListNode<K, V> n = findNear(key, LT, null /* GemStoneAddition */);
    return (n == null) ? null : n.getMapKey();
  }

  /**
   * Returns a key-value mapping associated with the greatest key less than or
   * equal to the given key, or <tt>null</tt> if there is no such key. The
   * returned entry does <em>not</em> support the <tt>Entry.setValue</tt>
   * method.
   * 
   * @param key
   *          the key
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final Map.Entry<K, V> floorEntry(K key) {
    return getNear(key, LT | EQ);
  }

  /**
   * @param key
   *          the key
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final K floorKey(K key) {
    final SkipListNode<K, V> n = findNear(key, LT | EQ,
        null /* GemStoneAddition */);
    return (n == null) ? null : n.getMapKey();
  }

  /**
   * Returns a key-value mapping associated with the least key greater than or
   * equal to the given key, or <tt>null</tt> if there is no such entry. The
   * returned entry does <em>not</em> support the <tt>Entry.setValue</tt>
   * method.
   * 
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final Map.Entry<K, V> ceilingEntry(K key) {
    return getNear(key, GT | EQ);
  }

  /**
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final K ceilingKey(K key) {
    final SkipListNode<K, V> n = findNear(key, GT | EQ, null);
    return (n == null) ? null : n.getMapKey();
  }

  /**
   * Returns a key-value mapping associated with the least key strictly greater
   * than the given key, or <tt>null</tt> if there is no such key. The returned
   * entry does <em>not</em> support the <tt>Entry.setValue</tt> method.
   * 
   * @param key
   *          the key
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final Map.Entry<K, V> higherEntry(K key) {
    return getNear(key, GT);
  }

  /**
   * @param key
   *          the key
   * @throws ClassCastException
   *           {@inheritDoc}
   * @throws NullPointerException
   *           if the specified key is null
   */
  public final K higherKey(K key) {
    final SkipListNode<K, V> n = findNear(key, GT, null /* GemStoneAddition */);
    return (n == null) ? null : n.getMapKey();
  }

  /**
   * Returns a key-value mapping associated with the least key in this map, or
   * <tt>null</tt> if the map is empty. The returned entry does <em>not</em>
   * support the <tt>Entry.setValue</tt> method.
   */
  public final Map.Entry<K, V> firstEntry() {
    for (;;) {
      final SkipListNode<K, V> n = findFirst();
      if (n == null)
        return null;
      final SimpleImmutableEntry<K, V> e = n.createSnapshot();
      if (e != null)
        return e;
    }
  }

  /**
   * Returns a key-value mapping associated with the greatest key in this map,
   * or <tt>null</tt> if the map is empty. The returned entry does <em>not</em>
   * support the <tt>Entry.setValue</tt> method.
   */
  public final Map.Entry<K, V> lastEntry() {
    for (;;) {
      final SkipListNode<K, V> n = findLast();
      if (n == null)
        return null;
      final SimpleImmutableEntry<K, V> e = n.createSnapshot();
      if (e != null)
        return e;
    }
  }

  /**
   * Removes and returns a key-value mapping associated with the least key in
   * this map, or <tt>null</tt> if the map is empty. The returned entry does
   * <em>not</em> support the <tt>Entry.setValue</tt> method.
   */
  public final Map.Entry<K, V> pollFirstEntry() {
    return doRemoveFirstEntry();
  }

  /**
   * Removes and returns a key-value mapping associated with the greatest key in
   * this map, or <tt>null</tt> if the map is empty. The returned entry does
   * <em>not</em> support the <tt>Entry.setValue</tt> method.
   */
  public final Map.Entry<K, V> pollLastEntry() {
    return doRemoveLastEntry(null /* GemStoneAddition */);
  }

  // GemStoneAddition
  public final int height() {
    Index<K, V> q = head;
    int h = 1;
    while ((q = q.down) != null) {
      h++;
    }
    return h;
  }

  /* ---------------- Iterators -------------- */

  /**
   * Base of iterator classes:
   */
  abstract class Iter<T> implements Iterator<T> {
    /** the last node returned by next() */
    SkipListNode<K, V> lastReturned;

    /** the next node to return from next(); */
    SkipListNode<K, V> next;

    /** Cache of next value field to maintain weak consistency */
    V nextValue;

    /** Cache of next node's version field */
    int nextVersion;

    /** Initializes ascending iterator for entire range. */
    Iter() {
      for (;;) {
        next = findFirst();
        if (next == null)
          break;
        // read the version before reading the value
        final int version = next.getVersion();
        final V x = next.getValidValue();
        if (x != null) {
          nextValue = x;
          nextVersion = version;
          break;
        }
      }
    }

    public final boolean hasNext() {
      return next != null;
    }

    /** Advances next to higher entry. */
    final void advance() {
      if ((lastReturned = next) == null)
        throw new NoSuchElementException();
      for (;;) {
        next = next.getNext();
        if (next == null)
          break;
        // read the version before reading the value
        final int version = next.getVersion();
        final V x = next.getValidValue();
        if (x != null) {
          nextValue = x;
          nextVersion = version;
          break;
        }
      }
    }

    public final void remove() {
      final SkipListNode<K, V> l = lastReturned;
      if (l == null)
        throw new IllegalStateException();
      // It would not be worth all of the overhead to directly
      // unlink from here. Using remove is fast enough.
      ConcurrentSkipListMap.this.remove(l.getMapKey());
      lastReturned = null;
    }
  }

  final class ValueIterator extends Iter<V> {
    public V next() {
      final V v = nextValue;
      advance();
      return v;
    }
  }

  final class KeyIterator extends Iter<K> {
    public K next() {
      final SkipListNode<K, V> n = next;
      advance();
      return n.getMapKey();
    }
  }

  final class EntryIterator extends Iter<Map.Entry<K, V>> {
    private final SimpleReusableEntry<K, V> mutable =
        new SimpleReusableEntry<K, V>();

    public Map.Entry<K, V> next() {
      final SkipListNode<K, V> n = next;
      final V v = nextValue;
      final int version = nextVersion;
      advance();
      // GemStone changes BEGIN
      // return new AbstractMap.SimpleImmutableEntry(n.key, v);
      mutable.key = n.getMapKey();
      mutable.value = v;
      mutable.version = version;
      return mutable;
      // GemStone changes END
    }
  }

  // Factory methods for iterators needed by ConcurrentSkipListSet etc

  final Iterator<K> keyIterator() {
    return new KeyIterator();
  }

  final Iterator<V> valueIterator() {
    return new ValueIterator();
  }

  final Iterator<Map.Entry<K, V>> entryIterator() {
    return new EntryIterator();
  }

  /* ---------------- View Classes -------------- */

  /*
   * View classes are static, delegating to a ConcurrentNavigableMap
   * to allow use by SubMaps, which outweighs the ugliness of
   * needing type-tests for Iterator methods.
   */

  static final <T> List<T> toList(Collection<T> c) {
    // Using size() here would be a pessimization.
    final ArrayList<T> list = new ArrayList<T>();
    for (final Iterator<T> itr = c.iterator(); itr.hasNext();) {
      list.add(itr.next());
    }
    return list;
  }

  static final class KeySet<K, V> extends AbstractSet<K> implements
      NavigableSet<K> {

    private final ConcurrentNavigableMap<K, V> m;

    KeySet(ConcurrentNavigableMap<K, V> map) {
      m = map;
    }

    @Override
    // GemStoneAddition
    public int size() {
      return m.size();
    }

    @Override
    // GemStoneAddition
    public boolean isEmpty() {
      return m.isEmpty();
    }

    @Override
    // GemStoneAddition
    public boolean contains(Object o) {
      return m.containsKey(o);
    }

    @Override
    // GemStoneAddition
    public boolean remove(Object o) {
      return m.remove(o) != null;
    }

    @Override
    // GemStoneAddition
    public void clear() {
      m.clear();
    }

    public K lower(K e) {
      return m.lowerKey(e);
    }

    public K floor(K e) {
      return m.floorKey(e);
    }

    public K ceiling(K e) {
      return m.ceilingKey(e);
    }

    public K higher(K e) {
      return m.higherKey(e);
    }

    public Comparator<? super K> comparator() {
      return m.comparator();
    }

    public K first() {
      return m.firstKey();
    }

    public K last() {
      return m.lastKey();
    }

    public K pollFirst() {
      final Map.Entry<K, V> e = m.pollFirstEntry();
      return e == null ? null : e.getKey();
    }

    public K pollLast() {
      final Map.Entry<K, V> e = m.pollLastEntry();
      return e == null ? null : e.getKey();
    }

    @Override
    // GemStoneAddition
    public Object[] toArray() {
      return toList(this).toArray();
    }

    @Override
    // GemStoneAddition
    public <T> T[] toArray(T[] a) {
      return toList(this).toArray(a);
    }

    @Override
    // GemStoneAddition
    public Iterator<K> iterator() {
      if (m instanceof ConcurrentSkipListMap<?, ?>)
        return ((ConcurrentSkipListMap<K, V>)m).keyIterator();
      else
        return ((SubMap<K, V>)m).keyIterator();
    }

    @Override
    // GemStoneAddition
    public boolean equals(Object o) {
      if (o == this)
        return true;
      if (!(o instanceof Set))
        return false;
      final Collection<K> c = (Collection<K>)o;
      try {
        return containsAll(c) && c.containsAll(this);
      } catch (final ClassCastException unused) {
        return false;
      } catch (final NullPointerException unused) {
        return false;
      }
    }

    public Iterator<K> descendingIterator() {
      return descendingSet().iterator();
    }

    public NavigableSet<K> subSet(K fromElement, boolean fromInclusive,
        K toElement, boolean toInclusive) {
      return new ConcurrentSkipListSet<K>(m.subMap(fromElement, fromInclusive,
          toElement, toInclusive));
    }

    public NavigableSet<K> headSet(K toElement, boolean inclusive) {
      return new ConcurrentSkipListSet<K>(m.headMap(toElement, inclusive));
    }

    public NavigableSet<K> tailSet(K fromElement, boolean inclusive) {
      return new ConcurrentSkipListSet<K>(m.tailMap(fromElement, inclusive));
    }

    public SortedSet<K> subSet(K fromElement, K toElement) {
      return subSet(fromElement, true, toElement, false);
    }

    public SortedSet<K> headSet(K toElement) {
      return headSet(toElement, false);
    }

    public SortedSet<K> tailSet(K fromElement) {
      return tailSet(fromElement, true);
    }

    public NavigableSet<K> descendingSet() {
      return new ConcurrentSkipListSet<K>(m.descendingMap());
    }
  }

  static final class Values<K, V> extends AbstractCollection<V> {

    private final ConcurrentNavigableMap<K, V> m;

    Values(ConcurrentNavigableMap<K, V> map) {
      this.m = map;
    }

    @Override
    // GemStoneAddition
    public Iterator<V> iterator() {
      if (m instanceof ConcurrentSkipListMap<?, ?>)
        return ((ConcurrentSkipListMap<K, V>)m).valueIterator();
      else
        return ((SubMap<K, V>)m).valueIterator();
    }

    @Override
    // GemStoneAddition
    public boolean isEmpty() {
      return m.isEmpty();
    }

    @Override
    // GemStoneAddition
    public int size() {
      return m.size();
    }

    @Override
    // GemStoneAddition
    public boolean contains(Object o) {
      return m.containsValue(o);
    }

    @Override
    // GemStoneAddition
    public void clear() {
      m.clear();
    }

    @Override
    // GemStoneAddition
    public Object[] toArray() {
      return toList(this).toArray();
    }

    @Override
    // GemStoneAddition
    public <T> T[] toArray(T[] a) {
      return toList(this).toArray(a);
    }
  }

  final class EntrySet extends AbstractSet<Map.Entry<K, V>> {

    private final ConcurrentNavigableMap<K, V> m;
    private final boolean useEquals;

    EntrySet(ConcurrentNavigableMap<K, V> map, boolean useEquals) {
      m = map;
      this.useEquals = useEquals;
    }

    @Override
    // GemStoneAddition
    public Iterator<Map.Entry<K, V>> iterator() {
      if (m instanceof ConcurrentSkipListMap<?, ?>)
        return ((ConcurrentSkipListMap<K, V>)m).entryIterator();
      else
        return ((SubMap<K, V>)m).entryIterator();
    }

    @Override
    // GemStoneAddition
    public boolean contains(Object o) {
      if (!(o instanceof Map.Entry))
        return false;
      final Map.Entry<K, V> e = (Map.Entry<K, V>)o;
      final Object v = m.get(e.getKey());
      return v != null
          && (this.useEquals ? v.equals(e.getValue()) : (v == e.getValue()));
    }

    @Override
    // GemStoneAddition
    public boolean remove(Object o) {
      if (!(o instanceof Map.Entry))
        return false;
      final Map.Entry<K, V> e = (Map.Entry<K, V>)o;
      return m.remove(e.getKey(), e.getValue());
    }

    @Override
    // GemStoneAddition
    public boolean isEmpty() {
      return m.isEmpty();
    }

    @Override
    // GemStoneAddition
    public int size() {
      return m.size();
    }

    @Override
    // GemStoneAddition
    public void clear() {
      m.clear();
    }

    @Override
    // GemStoneAddition
    public boolean equals(Object o) {
      if (o == this)
        return true;
      if (!(o instanceof Set))
        return false;
      final Collection<Map.Entry<K, V>> c = (Collection<Map.Entry<K, V>>)o;
      try {
        return containsAll(c) && c.containsAll(this);
      } catch (final ClassCastException unused) {
        return false;
      } catch (final NullPointerException unused) {
        return false;
      }
    }

    @Override
    // GemStoneAddition
    public Object[] toArray() {
      return toList(this).toArray();
    }

    @Override
    // GemStoneAddition
    public <T> T[] toArray(T[] a) {
      return toList(this).toArray(a);
    }

    // GemStoneAddition
    /**
     * Estimate the entry overhead. User supplied key/value can be complex
     * object whose estimation is done separately.
     */
    public long estimateEntryOverhead(SingleObjectSizer sizer,
        boolean isAgentAttached) {
      long totalSize = 0L;
      final EntryIterator entryIter = (EntryIterator)this.iterator();
      totalSize += sizer.sizeof(entryIter);
      boolean singleEntrySizeComputed = false;
      long singleEntrySize = 0;
      while (entryIter.hasNext()) {
        // simply advance to next item.
        entryIter.next();
        if (!singleEntrySizeComputed) {
          singleEntrySize = sizer.sizeof(entryIter.next);
          singleEntrySizeComputed = true;
        }
        totalSize += isAgentAttached ? sizer.sizeof(entryIter.next)
            : singleEntrySize;
      }
      return totalSize;
    }
  }

  /**
   * Submaps returned by {@link ConcurrentSkipListMap} submap operations
   * represent a subrange of mappings of their underlying maps. Instances of
   * this class support all methods of their underlying maps, differing in that
   * mappings outside their range are ignored, and attempts to add mappings
   * outside their ranges result in {@link IllegalArgumentException}. Instances
   * of this class are constructed only using the <tt>subMap</tt>,
   * <tt>headMap</tt>, and <tt>tailMap</tt> methods of their underlying maps.
   * 
   * @serial include
   */
  static final class SubMap<K, V> extends AbstractMap<K, V> implements
      ConcurrentNavigableMap<K, V>, Cloneable, java.io.Serializable {
    private static final long serialVersionUID = -7647078645895051609L;

    /** Underlying map */
    protected final ConcurrentSkipListMap<K, V> m;

    /** lower bound key, or null if from start */
    private final K lo;

    /** upper bound key, or null if to end */
    private final K hi;

    /** inclusion flag for lo */
    private final boolean loInclusive;

    /** inclusion flag for hi */
    private final boolean hiInclusive;

    /** direction */
    protected final boolean isDescending;

    // GemStoneAddition
    protected final int[] numNodesCompared;

    // Lazily initialized view holders
    private transient KeySet<K, V> keySetView;

    private transient Set<Map.Entry<K, V>> entrySetView;

    private transient Collection<V> valuesView;

    /**
     * Creates a new submap, initializing all fields
     */
    SubMap(ConcurrentSkipListMap<K, V> map, K fromKey, boolean fromInclusive,
        K toKey, boolean toInclusive, boolean isDescending,
        final int[] numNodesCompared /* GemStoneAddition */) {
      if (fromKey != null && toKey != null && !map.skipSubMapRangeCheck
          && map.comparator.compare(fromKey, toKey) > 0)
        throw new IllegalArgumentException("inconsistent range");
      this.m = map;
      this.lo = fromKey;
      this.hi = toKey;
      this.loInclusive = fromInclusive;
      this.hiInclusive = toInclusive;
      this.isDescending = isDescending;
      // GemStoneAddition
      this.numNodesCompared = numNodesCompared;
    }

    /* ----------------  Utilities -------------- */

    protected boolean tooLow(K key) {
      if (lo != null) {
        final int c = m.comparator.compare(key, lo);
        // GemStone changes BEGIN
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        // GemStone changes END
        if (c < 0 || (c == 0 && !loInclusive))
          return true;
      }
      return false;
    }

    protected boolean tooHigh(K key) {
      if (hi != null) {
        final int c = m.comparator.compare(key, hi);
        // GemStone changes BEGIN
        if (numNodesCompared != null) {
          numNodesCompared[0]++;
        }
        // GemStone changes END
        if (c > 0 || (c == 0 && !hiInclusive))
          return true;
      }
      return false;
    }

    protected boolean inBounds(K key) {
      return !tooLow(key) && !tooHigh(key);
    }

    private void checkKeyBounds(K key) throws IllegalArgumentException {
      if (key == null)
        throw new NullPointerException();
      if (!inBounds(key))
        throw new IllegalArgumentException("key out of range");
    }

    /**
     * Returns true if node key is less than upper bound of range
     */
    private boolean isBeforeEnd(SkipListNode<K, V> n) {
      if (n == null)
        return false;
      if (hi == null)
        return true;
      final K k = n.getMapKey();
      if (k == null) // pass by markers and headers
        return true;
      final int c = m.comparator.compare(k, hi);
      // GemStone changes BEGIN
      if (numNodesCompared != null) {
        numNodesCompared[0]++;
      }
      // GemStone changes END
      if (c > 0 || (c == 0 && !hiInclusive))
        return false;
      return true;
    }

    /**
     * Returns lowest node. This node might not be in range, so most usages need
     * to check bounds
     */
    protected SkipListNode<K, V> loNode() {
      if (lo == null)
        return m.findFirst();
      else if (loInclusive)
        return m
            .findNear(lo, GT | EQ, numNodesCompared /* GemStoneAddition */);
      else
        return m.findNear(lo, GT, numNodesCompared /* GemStoneAddition */);
    }

    /**
     * Returns highest node. This node might not be in range, so most usages
     * need to check bounds
     */
    protected SkipListNode<K, V> hiNode() {
      if (hi == null)
        return m.findLast();
      else if (hiInclusive)
        return m
            .findNear(hi, LT | EQ, numNodesCompared /* GemStoneAddition */);
      else
        return m.findNear(hi, LT, numNodesCompared /* GemStoneAddition */);
    }

    /**
     * Returns lowest absolute key (ignoring directonality)
     */
    private K lowestKey() {
      final SkipListNode<K, V> n = loNode();
      if (isBeforeEnd(n))
        return n.getMapKey();
      else
        throw new NoSuchElementException();
    }

    /**
     * Returns highest absolute key (ignoring directonality)
     */
    private K highestKey() {
      final SkipListNode<K, V> n = hiNode();
      if (n != null) {
        final K last = n.getMapKey();
        if (inBounds(last))
          return last;
      }
      throw new NoSuchElementException();
    }

    private Map.Entry<K, V> lowestEntry() {
      for (;;) {
        final SkipListNode<K, V> n = loNode();
        if (!isBeforeEnd(n))
          return null;
        final Map.Entry<K, V> e = n.createSnapshot();
        if (e != null)
          return e;
      }
    }

    private Map.Entry<K, V> highestEntry() {
      for (;;) {
        final SkipListNode<K, V> n = hiNode();
        if (n == null || !inBounds(n.getMapKey()))
          return null;
        final Map.Entry<K, V> e = n.createSnapshot();
        if (e != null)
          return e;
      }
    }

    private Map.Entry<K, V> removeLowest() {
      for (;;) {
        final SkipListNode<K, V> n = loNode();
        if (n == null)
          return null;
        final K k = n.getMapKey();
        if (!inBounds(k))
          return null;
        final V v = m.doRemove(k, null,
            numNodesCompared /* GemStoneAddition */);
        if (v != null)
          return new AbstractMap.SimpleImmutableEntry<K, V>(k, v);
      }
    }

    private Map.Entry<K, V> removeHighest() {
      for (;;) {
        final SkipListNode<K, V> n = hiNode();
        if (n == null)
          return null;
        final K k = n.getMapKey();
        if (!inBounds(k))
          return null;
        final V v = m.doRemove(k, null,
            numNodesCompared /* GemStoneAddition */);
        if (v != null)
          return new AbstractMap.SimpleImmutableEntry<K, V>(k, v);
      }
    }

    /**
     * Submap version of ConcurrentSkipListMap.getNearEntry
     */
    private Map.Entry<K, V> getNearEntry(K key, int rel) {
      if (isDescending) { // adjust relation for direction
        if ((rel & LT) == 0)
          rel |= LT;
        else
          rel &= ~LT;
      }
      if (tooLow(key))
        return ((rel & LT) != 0) ? null : lowestEntry();
      if (tooHigh(key))
        return ((rel & LT) != 0) ? highestEntry() : null;
      for (;;) {
        final SkipListNode<K, V> n = m
            .findNear(key, rel, numNodesCompared /* GemStoneAddition */);
        if (n == null || !inBounds(n.getMapKey()))
          return null;
        return n.createSnapshot();
      }
    }

    // Almost the same as getNearEntry, except for keys
    private K getNearKey(K key, int rel) {
      if (isDescending) { // adjust relation for direction
        if ((rel & LT) == 0)
          rel |= LT;
        else
          rel &= ~LT;
      }
      if (tooLow(key)) {
        if ((rel & LT) == 0) {
          final SkipListNode<K, V> n = loNode();
          if (isBeforeEnd(n))
            return n.getMapKey();
        }
        return null;
      }
      if (tooHigh(key)) {
        if ((rel & LT) != 0) {
          final SkipListNode<K, V> n = hiNode();
          if (n != null) {
            final K last = n.getMapKey();
            if (inBounds(last))
              return last;
          }
        }
        return null;
      }
      for (;;) {
        final SkipListNode<K, V> n = m
            .findNear(key, rel, numNodesCompared /* GemStoneAddition */);
        final K k;
        if (n == null || !inBounds((k = n.getMapKey())))
          return null;
        final V v = n.getValidValue();
        if (v != null)
          return k;
      }
    }

    /* ----------------  Map API methods -------------- */

    @Override
    // GemStoneAddition
    public boolean containsKey(Object key) {
      if (key == null)
        throw new NullPointerException();
      final K k = (K)key;
      return inBounds(k) && m.containsKey(k);
    }

    @Override
    // GemStoneAddition
    public V get(Object key) {
      if (key == null)
        throw new NullPointerException();
      final K k = (K)key;
      return ((!inBounds(k)) ? null : m.get(k));
    }

    @Override
    // GemStoneAddition
    public V put(K key, V value) {
      checkKeyBounds(key);
      return m.put(key, value);
    }

    @Override
    // GemStoneAddition
    public V remove(Object key) {
      final K k = (K)key;
      return (!inBounds(k)) ? null : m.remove(k);
    }

    @Override
    // GemStoneAddition
    public int size() {
      long count = 0;
      for (SkipListNode<K, V> n = loNode(); isBeforeEnd(n); n = n.getNext()) {
        if (n.getValidValue() != null)
          ++count;
      }
      return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)count;
    }

    @Override
    // GemStoneAddition
    public boolean isEmpty() {
      return !isBeforeEnd(loNode());
    }

    @Override
    // GemStoneAddition
    public boolean containsValue(Object value) {
      if (value == null)
        throw new NullPointerException();
      for (SkipListNode<K, V> n = loNode(); isBeforeEnd(n); n = n.getNext()) {
        final V v = n.getValidValue();
        if (v != null && m.objectEquals(value, v))
          return true;
      }
      return false;
    }

    @Override
    // GemStoneAddition
    public void clear() {
      for (SkipListNode<K, V> n = loNode(); isBeforeEnd(n); n = n.getNext()) {
        if (n.getValidValue() != null)
          m.remove(n.getMapKey());
      }
    }

    /* ----------------  ConcurrentMap API methods -------------- */

    public V putIfAbsent(K key, V value) {
      checkKeyBounds(key);
      return m.putIfAbsent(key, value);
    }

    public boolean remove(Object key, Object value) {
      final K k = (K)key;
      return inBounds(k) && m.remove(k, value);
    }

    public boolean replace(K key, V oldValue, V newValue) {
      checkKeyBounds(key);
      return m.replace(key, oldValue, newValue);
    }

    public V replace(K key, V value) {
      checkKeyBounds(key);
      return m.replace(key, value);
    }

    /* ----------------  SortedMap API methods -------------- */

    public Comparator<? super K> comparator() {
      final Comparator<? super K> cmp = m.comparator();
      if (isDescending)
        return Collections.reverseOrder(cmp);
      else
        return cmp;
    }

    /**
     * Utility to create submaps, where given bounds override unbounded(null)
     * ones and/or are checked against bounded ones.
     */
    private SubMap<K, V> newSubMap(K fromKey, boolean fromInclusive, K toKey,
        boolean toInclusive) {
      if (isDescending) { // flip senses
        final K tk = fromKey;
        fromKey = toKey;
        toKey = tk;
        final boolean ti = fromInclusive;
        fromInclusive = toInclusive;
        toInclusive = ti;
      }
      if (lo != null) {
        if (fromKey == null) {
          fromKey = lo;
          fromInclusive = loInclusive;
        }
        else {
          final int c = m.comparator.compare(fromKey, lo);
          // GemStone changes BEGIN
          if (numNodesCompared != null) {
            numNodesCompared[0]++;
          }
          // GemStone changes END
          if (c < 0 || (c == 0 && !loInclusive && fromInclusive))
            throw new IllegalArgumentException("key out of range");
        }
      }
      if (hi != null) {
        if (toKey == null) {
          toKey = hi;
          toInclusive = hiInclusive;
        }
        else {
          final int c = m.comparator.compare(toKey, hi);
          // GemStone changes BEGIN
          if (numNodesCompared != null) {
            numNodesCompared[0]++;
          }
          // GemStone changes END
          if (c > 0 || (c == 0 && !hiInclusive && toInclusive))
            throw new IllegalArgumentException("key out of range");
        }
      }
      return new SubMap<K, V>(m, fromKey, fromInclusive, toKey, toInclusive,
          isDescending, numNodesCompared /* GemStoneAddition */);
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey,
        boolean fromInclusive, K toKey, boolean toInclusive) {
      if (fromKey == null || toKey == null)
        throw new NullPointerException();
      return newSubMap(fromKey, fromInclusive, toKey, toInclusive);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive) {
      if (toKey == null)
        throw new NullPointerException();
      return newSubMap(null, false, toKey, inclusive);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
      if (fromKey == null)
        throw new NullPointerException();
      return newSubMap(fromKey, inclusive, null, false);
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
      return subMap(fromKey, true, toKey, false);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey) {
      return headMap(toKey, false);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey) {
      return tailMap(fromKey, true);
    }

    public ConcurrentNavigableMap<K, V> descendingMap() {
      return new SubMap<K, V>(m, lo, loInclusive, hi, hiInclusive,
          !isDescending, numNodesCompared /* GemStoneAddition */);
    }

    /* ----------------  Relational methods -------------- */

    public Map.Entry<K, V> ceilingEntry(K key) {
      return getNearEntry(key, (GT | EQ));
    }

    public K ceilingKey(K key) {
      return getNearKey(key, (GT | EQ));
    }

    public Map.Entry<K, V> lowerEntry(K key) {
      return getNearEntry(key, LT);
    }

    public K lowerKey(K key) {
      return getNearKey(key, LT);
    }

    public Map.Entry<K, V> floorEntry(K key) {
      return getNearEntry(key, (LT | EQ));
    }

    public K floorKey(K key) {
      return getNearKey(key, (LT | EQ));
    }

    public Map.Entry<K, V> higherEntry(K key) {
      return getNearEntry(key, GT);
    }

    public K higherKey(K key) {
      return getNearKey(key, GT);
    }

    public K firstKey() {
      return isDescending ? highestKey() : lowestKey();
    }

    public K lastKey() {
      return isDescending ? lowestKey() : highestKey();
    }

    public Map.Entry<K, V> firstEntry() {
      return isDescending ? highestEntry() : lowestEntry();
    }

    public Map.Entry<K, V> lastEntry() {
      return isDescending ? lowestEntry() : highestEntry();
    }

    public Map.Entry<K, V> pollFirstEntry() {
      return isDescending ? removeHighest() : removeLowest();
    }

    public Map.Entry<K, V> pollLastEntry() {
      return isDescending ? removeLowest() : removeHighest();
    }

    /* ---------------- Submap Views -------------- */

    @Override
    // GemStoneAddition
    public NavigableSet<K> keySet() {
      final KeySet<K, V> ks = keySetView;
      return (ks != null) ? ks : (keySetView = new KeySet<K, V>(this));
    }

    public NavigableSet<K> navigableKeySet() {
      final KeySet<K, V> ks = keySetView;
      return (ks != null) ? ks : (keySetView = new KeySet<K, V>(this));
    }

    @Override
    // GemStoneAddition
    public Collection<V> values() {
      final Collection<V> vs = valuesView;
      return (vs != null) ? vs : (valuesView = new Values<K, V>(this));
    }

    @Override
    // GemStoneAddition
    public Set<Map.Entry<K, V>> entrySet() {
      final Set<Map.Entry<K, V>> es = entrySetView;
      return (es != null) ? es : (entrySetView = m.new EntrySet(this,
          m.useEquals));
    }

    public NavigableSet<K> descendingKeySet() {
      return descendingMap().navigableKeySet();
    }

    Iterator<K> keyIterator() {
      return new SubMapKeyIterator();
    }

    Iterator<V> valueIterator() {
      return new SubMapValueIterator();
    }

    Iterator<Map.Entry<K, V>> entryIterator() {
      return new SubMapEntryIterator();
    }

    /**
     * Variant of main Iter class to traverse through submaps.
     */
    abstract class SubMapIter<T> implements Iterator<T> {
      /** the last node returned by next() */
      SkipListNode<K, V> lastReturned;

      /** the next node to return from next(); */
      SkipListNode<K, V> next;

      /** Cache of next value field to maintain weak consistency */
      V nextValue;

      /** Cache of next node's version field */
      int nextVersion;

      SubMapIter() {
        for (;;) {
          next = isDescending ? hiNode() : loNode();
          if (next == null)
            break;
          // read the version before reading the value
          final int version = next.getVersion();
          final V x = next.getValidValue();
          if (x != null) {
            if (!inBounds(next.getMapKey()))
              next = null;
            else {
              nextValue = x;
              nextVersion = version;
            }
            break;
          }
        }
      }

      public final boolean hasNext() {
        return next != null;
      }

      final void advance() {
        if ((lastReturned = next) == null)
          throw new NoSuchElementException();
        if (isDescending)
          descend();
        else
          ascend();
      }

      private void ascend() {
        for (;;) {
          next = next.getNext();
          if (next == null)
            break;
          // read the version before reading the value
          final int version = next.getVersion();
          final V x = next.getValidValue();
          if (x != null) {
            if (tooHigh(next.getMapKey()))
              next = null;
            else {
              nextValue = x;
              nextVersion = version;
            }
            break;
          }
        }
      }

      private void descend() {
        for (;;) {
          next = m.findNear(lastReturned.getMapKey(), LT,
              numNodesCompared /* GemStoneAddition */);
          if (next == null)
            break;
          // read the version before reading the value
          final int version = next.getVersion();
          final V x = next.getValidValue();
          if (x != null) {
            if (tooLow(next.getMapKey()))
              next = null;
            else {
              nextValue = x;
              nextVersion = version;
            }
            break;
          }
        }
      }

      public void remove() {
        final SkipListNode<K, V> l = lastReturned;
        if (l == null)
          throw new IllegalStateException();
        m.remove(l.getMapKey());
        lastReturned = null;
      }

    }

    final class SubMapValueIterator extends SubMapIter<V> {
      public V next() {
        final V v = nextValue;
        advance();
        return v;
      }
    }

    final class SubMapKeyIterator extends SubMapIter<K> {
      public K next() {
        final SkipListNode<K, V> n = next;
        advance();
        return n.getMapKey();
      }
    }

    final class SubMapEntryIterator extends SubMapIter<Map.Entry<K, V>> {
      private final SimpleReusableEntry<K, V> mutable = 
          new SimpleReusableEntry<K, V>();

      public Map.Entry<K, V> next() {
        final SkipListNode<K, V> n = next;
        final V v = nextValue;
        final int version = nextVersion;
        advance();
        // GemStone changes BEGIN
        // return new AbstractMap.SimpleImmutableEntry(n.key, v);
        mutable.key = n.getMapKey();
        mutable.value = v;
        mutable.version = version;
        return mutable;
        // GemStone changes END
      }
    }

    // GemStoneAddition
    @Override
    protected Object clone() throws CloneNotSupportedException {
      return new SubMap<K, V>(this.m, this.lo, this.loInclusive, this.hi,
          this.hiInclusive, this.isDescending, this.numNodesCompared);
    }
  }

  // GemStoneAddition
  public static final class SimpleReusableEntry<K, V> implements Entry<K, V> {
    private K key;
    private V value;
    private int version;

    public K getKey() {
      return key;
    }

    public V getValue() {
      return value;
    }

    public int getVersion() {
      return this.version;
    }

    public void setReusableKey(K key) {
      this.key = key;
    }

    public void setReusableValue(V value) {
      this.value = value;
    }

    public void setReusableVersion(int version) {
      this.version = version;
    }

    public V setValue(V value) {
      throw new InternalGemFireError(
          "SimpleReusableEntry#setValue: unexpected execution");
    }

    /**
     * Compares the specified object with this entry for equality. Returns
     * {@code true} if the given object is also a map entry and the two entries
     * represent the same mapping. More formally, two entries {@code e1} and
     * {@code e2} represent the same mapping if
     * 
     * <pre>
     * (e1.getKey() == null ? e2.getKey() == null : e1.getKey().equals(
     *     e2.getKey())) &amp;&amp; (e1.getValue() == null ?
     *         e2.getValue() == null : e1.getValue().equals(e2.getValue()))
     * </pre>
     * 
     * This ensures that the {@code equals} method works properly across
     * different implementations of the {@code Map.Entry} interface.
     * 
     * @param o
     *          object to be compared for equality with this map entry
     * @return {@code true} if the specified object is equal to this map entry
     * @see #hashCode
     */
    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof Map.Entry<?, ?>)) {
        return false;
      }
      final Map.Entry<?, ?> e = (Map.Entry<?, ?>)o;
      return ArrayUtils.objectEquals(this.key, e.getKey())
          && ArrayUtils.objectEquals(this.value, e.getValue());
    }

    /**
     * Returns the hash code value for this map entry. The hash code of a map
     * entry {@code e} is defined to be:
     * 
     * <pre>
     * (e.getKey() == null ? 0 : e.getKey().hashCode())
     *     &circ; (e.getValue() == null ? 0 : e.getValue().hashCode())
     * </pre>
     * 
     * This ensures that {@code e1.equals(e2)} implies that
     * {@code e1.hashCode()==e2.hashCode()} for any two Entries {@code e1} and
     * {@code e2}, as required by the general contract of
     * {@link Object#hashCode}.
     * 
     * @return the hash code value for this map entry
     * @see #equals
     */
    @Override
    public int hashCode() {
      return System.identityHashCode(this.key)
          ^ System.identityHashCode(this.value);
    }

    /**
     * Returns a String representation of this map entry. This implementation
     * returns the string representation of this entry's key followed by the
     * equals character ("<tt>=</tt>") followed by the string representation of
     * this entry's value.
     * 
     * @return a String representation of this map entry
     */
    @Override
    public String toString() {
      return this.key + "=" + this.value;
    }
  }

  public long estimateMemoryOverhead(SingleObjectSizer sizer) {
    // instead use the other override which returns breakdown of the sizes.
    return 0;
  }
}
