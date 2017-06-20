/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
/*
 * QueuedSynchronizer class has been adapted from the JSR-166's
 * AbstractQueuedSynchronizer class to suit the needs of distributed
 * transactions and RegionEntry locking in GFE. The main reason why it needed
 * to be altered rather than extended is that in GFE transactions, for example,
 * we need to make the locks reentrant not based on the thread itself but based
 * on the transaction ID. For the same transactions if different function
 * execution threads are vying for the same entries then they should be allowed.
 *
 * The non-blocking lock state CAS portion has been split out into TryLockObject
 * interface to enable having the wait queue separate from lock state (that can
 *   live inside RegionEntry). This allows AbstractRegionEntry, for example, to
 * avoid having the waiters queue inside itself (a significant memory overhead
 *   particularly since queue will have at least the head object after the lock
 *   is acquired the first time) rather have it in some other place e.g. a map
 * of queues at region level that will only be consulted if lock cannot be
 * obtained immediately. Also Conditions have been removed from this
 * implementation. In addition this honours GemFire CancelCriterion rather
 * than throwing InterruptedException.
 *
 * The documentation has largely been kept unchanged and tweaked a bit
 * for the additional added code. Instead of the Sun's internal Unsafe
 * class for CAS operations, we use Atomic*FieldUpdater classes (created
 *   from a factory that in turn will use optimized Unsafe if possible).
 *
 * @author kneeraj, swale
 * 
 * Original license below:
 * 
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 *
 * JSR 166 interest web site:
 * http://gee.cs.oswego.edu/dl/concurrency-interest/
 *
 * File download location:
 * http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/src/jdk7/java/util/concurrent/locks/AbstractQueuedSynchronizer.java?revision=1.17&view=markup
 */

package com.gemstone.gemfire.internal.cache.locks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.LockSupport;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on first-in-first-out
 * (FIFO) wait queues. This class is designed to be a useful basis for most
 * kinds of synchronizers that rely on a single atomic <tt>int</tt> value to
 * represent state. Classes must implement the {@link TryLockObject} interface
 * (or extend an implementation like {@link ExclusiveSharedSynchronizer} to
 * define the methods that change this state, and which define what that state
 * means in terms of this object being acquired or released while containing an
 * object of this class to represent the thread waiter queue. Given these, the
 * other methods in this class carry out all queuing and blocking mechanics.
 * Lock classes can maintain other state fields, but only the atomically updated
 * <tt>int</tt> value manipulated in the {@link TryLockObject} interface
 * implementations is tracked with respect to synchronization.
 *
 * <p>
 * Classes should be defined as non-public internal helper classes that are used
 * to implement the synchronization properties of their enclosing class. Class
 * <tt>QueuedSynchronizer</tt> does not implement any synchronization interface.
 * Instead it defines methods such as {@link #tryAcquireNanos},
 * {@link #tryAcquireSharedNanos} that can be invoked as appropriate by concrete
 * locks and related synchronizers to implement their public methods.
 *
 * <p>
 * This class supports either or both a default <em>exclusive</em> mode and a
 * <em>shared</em> mode or more modes as required depending on the optional
 * integer argument passed to the lock acquire/release methods. When acquired in
 * exclusive mode, attempted acquires by other threads cannot succeed. Shared
 * mode acquires by multiple threads may (but need not) succeed. This class does
 * not &quot;understand&quot; these differences except in the mechanical sense
 * that when a shared mode acquire succeeds, the next waiting thread (if one
 * exists) must also determine whether it can acquire as well. Threads waiting
 * in the different modes share the same FIFO queue. Usually, implementation
 * subclasses support only one of these modes, but both can come into play for
 * example in a ReadWriteLock. Subclasses that support only exclusive or only
 * shared modes need not define the methods supporting the unused mode.
 *
 * <p>
 * This class provides inspection, instrumentation, and monitoring methods for
 * the internal queue, as well as similar methods for condition objects. These
 * can be exported as desired into classes using an <tt>QueuedSynchronizer</tt>
 * for their synchronization mechanics.
 *
 * <h3>Usage</h3>
 *
 * <p>
 * To use this class as the basis of a synchronizer, redefine the following
 * methods, as applicable, by inspecting and/or modifying the synchronization
 * state in the {@link TryLockObject} interface implementation:
 *
 * <ul>
 * <li> {@link TryLockObject#tryAcquire}
 * <li> {@link TryLockObject#tryRelease}
 * <li> {@link TryLockObject#tryAcquireShared}
 * <li> {@link TryLockObject#tryReleaseShared}
 * </ul>
 *
 * Implementations of these methods must be internally thread-safe, and should
 * in general be short and not block. Defining these methods is the
 * <em>only</em> supported means of using this class. All methods in this class
 * are declared <tt>final</tt> because they cannot be independently varied.
 *
 * <p>
 * Even though this class is based on an internal FIFO queue, it does not
 * automatically enforce FIFO acquisition policies. The core of exclusive
 * synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!lock.tryAcquire(arg, owner)) {
 *        &lt;em&gt;enqueue thread if it is not already queued&lt;/em&gt;;
 *        &lt;em&gt;possibly block current thread&lt;/em&gt;;
 *     }
 * Release:
 *     if (lock.tryRelease(arg, owner))
 *        &lt;em&gt;unblock the first queued thread&lt;/em&gt;;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p>
 * Because checks in acquire are invoked before enqueuing, a newly acquiring
 * thread may <em>barge</em> ahead of others that are blocked and queued.
 * However, you can, if desired, define <tt>tryAcquire</tt> and/or
 * <tt>tryAcquireShared</tt> to disable barging by internally invoking one or
 * more of the inspection methods. In particular, a strict FIFO lock can define
 * <tt>tryAcquire</tt> to immediately return <tt>false</tt> if
 * first queued thread is not the current thread. A normally
 * preferable non-strict fair version can immediately return <tt>false</tt> only
 * if {@link #hasQueuedThreads} returns <tt>null</tt> and
 * <tt>getFirstQueuedThread</tt> is not the current thread; or equivalently,
 * that <tt>getFirstQueuedThread</tt> is both non-null and not the current
 * thread. Further variations are possible.
 *
 * <p>
 * Throughput and scalability are generally highest for the default barging
 * (also known as <em>greedy</em>, <em>renouncement</em>, and
 * <em>convoy-avoidance</em>) strategy. While this is not guaranteed to be fair
 * or starvation-free, earlier queued threads are allowed to recontend before
 * later queued threads, and each recontention has an unbiased chance to succeed
 * against incoming threads. Also, while acquires do not &quot;spin&quot; in the
 * usual sense, they may perform multiple invocations of <tt>tryAcquire</tt>
 * interspersed with other computations before blocking. This gives most of the
 * benefits of spins when exclusive synchronization is only briefly held,
 * without most of the liabilities when it isn't. If so desired, you can augment
 * this by preceding calls to acquire methods with "fast-path" checks, possibly
 * prechecking {@link #hasContended} and/or {@link #hasQueuedThreads} to only do
 * so if the synchronizer is likely not to be contended.
 *
 * <p>
 * This class provides an efficient and scalable basis for synchronization in
 * part by specializing its range of use to synchronizers that can rely on
 * <tt>int</tt> state, acquire, and release parameters, and an internal FIFO
 * wait queue. When this does not suffice, you can build synchronizers from a
 * lower level using java.util.concurrent.atomic atomic classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>
 * Here is a non-reentrant mutual exclusion lock class that uses the value zero
 * to represent the unlocked state, and one to represent the locked state. While
 * a non-reentrant lock does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor. It also
 * supports conditions and exposes one of the instrumentation methods:
 *
 * <pre>
 * class Mutex implements Lock, java.io.Serializable {
 *   // Our internal helper class
 *   private static class Sync implements TryLockObject {
 *     // Report whether in locked state
 *     public boolean hasExclusiveLock() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquire the lock if state is zero
 *     public boolean tryAcquire(int acquires, Object owner) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwner(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Release the lock by setting state to zero
 *     protected boolean tryRelease(int releases, Object owner) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) {
 *         throw new IllegalMonitorStateException();
 *       }
 *       setExclusiveOwner(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provide a Condition
 *     Condition newCondition() {
 *       return new ConditionObject();
 *     }
 *
 *     // Deserialize properly
 *     private void readObject(ObjectInputStream s) throws IOException,
 *         ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   private final QueuedSynchronizer waiterQueue = new QueuedSynchronizer();
 *
 *   public void lock() {
 *     waiterQueue.acquire(1, Thread.currentThread(), this.sync);
 *   }
 *
 *   public boolean tryLock() {
 *     return sync.tryAcquire(1);
 *   }
 *
 *   public void unlock() {
 *     waiterQueue.release(1, Thread.currentThread(), this.sync);
 *   }
 *
 *   public Condition newCondition() {
 *     return sync.newCondition();
 *   }
 *
 *   public boolean isLocked() {
 *     return sync.hasExclusiveLock(Thread.currentThread());
 *   }
 *
 *   public boolean hasQueuedThreads() {
 *     return waiterQueue.hasQueuedThreads();
 *   }
 *
 *   public void lockInterruptibly() throws InterruptedException {
 *     attemptSharedLock(1, Thread.currentThread(), this.sync);
 *   }
 *
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return waiterQueue.tryAcquireNanos(1, Thread.currentThread(), this.sync,
 *         unit.toNanos(timeout));
 *   }
 * }
 * </pre>
 *
 * <p>
 * Here is a latch class that is like a <code>CountDownLatch</code> except that
 * it only requires a single <tt>signal</tt> to fire. Because a latch is
 * non-exclusive, it uses the <tt>shared</tt> acquire and release methods.
 *
 * <pre>
 * class BooleanLatch implements TryLockObject {
 *
 *   private final QueuedSynchronizer sync = new QueuedSynchronizer();
 *
 *   public boolean isSignalled() {
 *     return getState() != 0;
 *   }
 *
 *   public int tryAcquireShared(int ignore, Object owner) {
 *     return isSignalled() ? 1 : -1;
 *   }
 *
 *   public boolean tryReleaseShared(int ignore, Object owner) {
 *     setState(1);
 *     return true;
 *   }
 *
 *   public void signal() {
 *     this.sync.releaseShared(1, null, this);
 *   }
 *
 *   public void await() throws InterruptedException {
 *     this.sync.acquireSharedInterruptibly(1, null, this);
 *   }
 * }
 * </pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public final class QueuedSynchronizer extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

  private static final long serialVersionUID = 7373984972572414691L;

  private volatile int numWaiters;

  private static final AtomicIntegerFieldUpdater<QueuedSynchronizer>
      numWaitersUpdater = AtomicUpdaterFactory.newIntegerFieldUpdater(
          QueuedSynchronizer.class, "numWaiters");

  private static final AtomicIntegerFieldUpdater<Node> nodeStatusUpdater =
      AtomicUpdaterFactory.newIntegerFieldUpdater(Node.class, "waitStatus");

  private static final AtomicReferenceFieldUpdater<QueuedSynchronizer,
      Node> headUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
          QueuedSynchronizer.class, Node.class, "head");

  private static final AtomicReferenceFieldUpdater<QueuedSynchronizer,
      Node> tailUpdater = AtomicUpdaterFactory.newReferenceFieldUpdater(
          QueuedSynchronizer.class, Node.class, "tail");

  private static final AtomicReferenceFieldUpdater<Node, Node> nextNodeUpdater =
      AtomicUpdaterFactory.newReferenceFieldUpdater(
          Node.class, Node.class, "next");

  /**
   * Creates a new <tt>QueuedSynchronizer</tt> instance.
   */
  public QueuedSynchronizer() {
  }

  /**
   * Wait queue node class.
   *
   * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
   * Hagersten) lock queue. CLH locks are normally used for
   * spinlocks.  We instead use them for blocking synchronizers, but
   * use the same basic tactic of holding some of the control
   * information about a thread in the predecessor of its node.  A
   * "status" field in each node keeps track of whether a thread
   * should block.  A node is signalled when its predecessor
   * releases.  Each node of the queue otherwise serves as a
   * specific-notification-style monitor holding a single waiting
   * thread. The status field does NOT control whether threads are
   * granted locks etc though.  A thread may try to acquire if it is
   * first in the queue. But being first does not guarantee success;
   * it only gives the right to contend.  So the currently released
   * contender thread may need to rewait.
   *
   * <p>To enqueue into a CLH lock, you atomically splice it in as new
   * tail. To dequeue, you just set the head field.
   * <pre>
   *      +------+  prev +-----+       +-----+
   * head |      | <---- |     | <---- |     |  tail
   *      +------+       +-----+       +-----+
   * </pre>
   *
   * <p>Insertion into a CLH queue requires only a single atomic
   * operation on "tail", so there is a simple atomic point of
   * demarcation from unqueued to queued. Similarly, dequeuing
   * involves only updating the "head". However, it takes a bit
   * more work for nodes to determine who their successors are,
   * in part to deal with possible cancellation due to timeouts
   * and interrupts.
   *
   * <p>The "prev" links (not used in original CLH locks), are mainly
   * needed to handle cancellation. If a node is cancelled, its
   * successor is (normally) relinked to a non-cancelled
   * predecessor. For explanation of similar mechanics in the case
   * of spin locks, see the papers by Scott and Scherer at
   * http://www.cs.rochester.edu/u/scott/synchronization/
   *
   * <p>We also use "next" links to implement blocking mechanics.
   * The thread id for each node is kept in its own node, so a
   * predecessor signals the next node to wake up by traversing
   * next link to determine which thread it is.  Determination of
   * successor must avoid races with newly queued nodes to set
   * the "next" fields of their predecessors.  This is solved
   * when necessary by checking backwards from the atomically
   * updated "tail" when a node's successor appears to be null.
   * (Or, said differently, the next-links are an optimization
   * so that we don't usually need a backward scan.)
   *
   * <p>Cancellation introduces some conservatism to the basic
   * algorithms.  Since we must poll for cancellation of other
   * nodes, we can miss noticing whether a cancelled node is
   * ahead or behind us. This is dealt with by always unparking
   * successors upon cancellation, allowing them to stabilize on
   * a new predecessor, unless we can identify an uncancelled
   * predecessor who will carry this responsibility.
   *
   * <p>CLH queues need a dummy header node to get started. But
   * we don't create them on construction, because it would be wasted
   * effort if there is never contention. Instead, the node
   * is constructed and head and tail pointers are set upon first
   * contention.
   *
   * <p>Threads waiting on Conditions use the same nodes, but
   * use an additional link. Conditions only need to link nodes
   * in simple (non-concurrent) linked queues because they are
   * only accessed when exclusively held.  Upon await, a node is
   * inserted into a condition queue.  Upon signal, the node is
   * transferred to the main queue.  A special value of status
   * field is used to mark which queue a node is on.
   *
   * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
   * Scherer and Michael Scott, along with members of JSR-166
   * expert group, for helpful ideas, discussions, and critiques
   * on the design of this class.
   */
  static final class Node {
    /** Marker to indicate a node is waiting in shared mode */
    static final Node SHARED = new Node();
    /** Marker to indicate a node is waiting in exclusive mode */
    static final Node EXCLUSIVE = null;

    /** waitStatus value to indicate thread has cancelled */
    static final int CANCELLED =  1;
    /** waitStatus value to indicate successor's thread needs unparking */
    static final int SIGNAL    = -1;
    /** waitStatus value to indicate thread is waiting on condition */
    // static final int CONDITION = -2;
    /**
     * waitStatus value to indicate the next acquireShared should
     * unconditionally propagate
     */
    static final int PROPAGATE = -3;

    /**
     * Status field, taking on only the values:
     *   SIGNAL:     The successor of this node is (or will soon be)
     *               blocked (via park), so the current node must
     *               unpark its successor when it releases or
     *               cancels. To avoid races, acquire methods must
     *               first indicate they need a signal,
     *               then retry the atomic acquire, and then,
     *               on failure, block.
     *   CANCELLED:  This node is cancelled due to timeout or interrupt.
     *               Nodes never leave this state. In particular,
     *               a thread with cancelled node never again blocks.
     *   CONDITION:  This node is currently on a condition queue.
     *               It will not be used as a sync queue node
     *               until transferred, at which time the status
     *               will be set to 0. (Use of this value here has
     *               nothing to do with the other uses of the
     *               field, but simplifies mechanics.)
     *   PROPAGATE:  A releaseShared should be propagated to other
     *               nodes. This is set (for head node only) in
     *               doReleaseShared to ensure propagation
     *               continues, even if other operations have
     *               since intervened.
     *   0:          None of the above
     *
     * The values are arranged numerically to simplify use.
     * Non-negative values mean that a node doesn't need to
     * signal. So, most code doesn't need to check for particular
     * values, just for sign.
     *
     * The field is initialized to 0 for normal sync nodes, and
     * CONDITION for condition nodes.  It is modified using CAS
     * (or when possible, unconditional volatile writes).
     */
    volatile int waitStatus;

    /**
     * Link to predecessor node that current node/thread relies on
     * for checking waitStatus. Assigned during enqueuing, and nulled
     * out (for sake of GC) only upon dequeuing.  Also, upon
     * cancellation of a predecessor, we short-circuit while
     * finding a non-cancelled one, which will always exist
     * because the head node is never cancelled: A node becomes
     * head only as a result of successful acquire. A
     * cancelled thread never succeeds in acquiring, and a thread only
     * cancels itself, not any other node.
     */
    volatile Node prev;

    /**
     * Link to the successor node that the current node/thread
     * unparks upon release. Assigned during enqueuing, adjusted
     * when bypassing cancelled predecessors, and nulled out (for
     * sake of GC) when dequeued.  The enq operation does not
     * assign next field of a predecessor until after attachment,
     * so seeing a null next field does not necessarily mean that
     * node is at end of queue. However, if a next field appears
     * to be null, we can scan prev's from the tail to
     * double-check.  The next field of cancelled nodes is set to
     * point to the node itself instead of null, to make life
     * easier for isOnSyncQueue.
     */
    volatile Node next;

    /**
     * The thread that enqueued this node.  Initialized on
     * construction and nulled out after use.
     */
    volatile Thread thread;

    /**
     * Link to next node waiting on condition, or the special
     * value SHARED.  Because condition queues are accessed only
     * when holding in exclusive mode, we just need a simple
     * linked queue to hold nodes while they are waiting on
     * conditions. They are then transferred to the queue to
     * re-acquire. And because conditions can only be exclusive,
     * we save a field by using special value to indicate shared
     * mode.
     */
    Node nextWaiter;

    /**
     * Returns true if node is waiting in shared mode.
     */
    final boolean isShared() {
      return nextWaiter == SHARED;
    }

    /**
     * Returns previous node, or throws NullPointerException if null.
     * Use when predecessor cannot be null.  The null check could
     * be elided, but is present to help the VM.
     *
     * @return the predecessor of this node
     */
    final Node predecessor() throws NullPointerException {
      Node p = prev;
      if (p == null)
        throw new NullPointerException();
      else
        return p;
    }

    Node() {    // Used to establish initial head or SHARED marker
    }

    Node(final Thread thread, final Node mode) { // Used by addWaiter
      this.nextWaiter = mode;
      this.thread = thread;
    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="EQ_CHECK_FOR_OPERAND_NOT_COMPATIBLE_WITH_THIS")
    public final boolean equals(final Object other) {
      if (other instanceof QueuedSynchronizer) {
        return ((QueuedSynchronizer)other).head == this;
      }
      return other == this;
    }

    @Override
    public int hashCode() {
      // findbugs doesn't like the equals() method w/o a hashCode() method
      throw new UnsupportedOperationException("this class does not support hashing");
    }
  }

  /**
   * Head of the wait queue, lazily initialized.  Except for
   * initialization, it is modified only via method setHead.  Note:
   * If head exists, its waitStatus is guaranteed not to be
   * CANCELLED.
   */
  private transient volatile Node head;

  /**
   * Tail of the wait queue, lazily initialized.  Modified only via
   * method enq to add new wait node.
   */
  private transient volatile Node tail;

  // Queuing utilities

  /**
   * The number of nanoseconds for which it is faster to spin rather than to use
   * timed park. A rough estimate suffices to improve responsiveness with very
   * short timeouts.
   */
  static final long spinForTimeoutThreshold = 1000L;

  /**
   * Inserts node into queue, initializing if necessary. See picture above.
   *
   * @param node the node to insert
   * @return node's predecessor
   */
  private Node enq(final Node node) {
    for (;;) {
      Node t = tail;
      if (t == null) { // Must initialize
        if (compareAndSetHead(new Node()))
          tail = head;
      } else {
        node.prev = t;
        if (compareAndSetTail(t, node)) {
          t.next = node;
          return t;
        }
      }
    }
  }

  /**
   * Creates and enqueues node for current thread and given mode.
   *
   * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
   * @return the new node
   */
  private Node addWaiter(final Node mode) {
    Node node = new Node(Thread.currentThread(), mode);
    // Try the fast path of enq; backup to full enq on failure
    Node pred = tail;
    if (pred != null) {
      node.prev = pred;
      if (compareAndSetTail(pred, node)) {
        pred.next = node;
        return node;
      }
    }
    enq(node);
    return node;
  }

  /**
   * Sets head of queue to be node, thus dequeuing. Called only by
   * acquire methods.  Also nulls out unused fields for sake of GC
   * and to suppress unnecessary signals and traversals.
   *
   * @param node the node
   */
  private void setHead(final Node node) {
    head = node;
    node.thread = null;
    node.prev = null;
  }

  /**
   * Wakes up node's successor, if one exists.
   *
   * @param node the node
   */
  private void unparkSuccessor(final Node node) {
    /*
     * If status is negative (i.e., possibly needing signal) try
     * to clear in anticipation of signalling.  It is OK if this
     * fails or if status is changed by waiting thread.
     */
    int ws = node.waitStatus;
    if (ws < 0)
      compareAndSetWaitStatus(node, ws, 0);

    /*
     * Thread to unpark is held in successor, which is normally
     * just the next node.  But if cancelled or apparently null,
     * traverse backwards from tail to find the actual
     * non-cancelled successor.
     */
    Node s = node.next;
    if (s == null || s.waitStatus > 0) {
      s = null;
      for (Node t = tail; t != null && t != node; t = t.prev)
        if (t.waitStatus <= 0)
          s = t;
    }
    if (s != null)
      LockSupport.unpark(s.thread);
  }

  /**
   * Signal any waiting threads in the queue.
   */
  public final void signalWaiters() {
    final Node h = this.head;
    if (h != null && h.waitStatus != 0) {
      unparkSuccessor(h);
    }
  }

  /**
   * Release action for shared mode -- signals successor and ensures
   * propagation. (Note: For exclusive mode, release just amounts
   * to calling unparkSuccessor of head if it needs signal.)
   */
  public final void signalSharedWaiters() {
    /*
     * Ensure that a release propagates, even if there are other
     * in-progress acquires/releases.  This proceeds in the usual
     * way of trying to unparkSuccessor of head if it needs
     * signal. But if it does not, status is set to PROPAGATE to
     * ensure that upon release, propagation continues.
     * Additionally, we must loop in case a new node is added
     * while we are doing this. Also, unlike other uses of
     * unparkSuccessor, we need to know if CAS to reset status
     * fails, if so rechecking.
     */
    for (;;) {
      Node h = head;
      if (h != null && h != tail) {
        int ws = h.waitStatus;
        if (ws == Node.SIGNAL) {
          if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
            continue;            // loop to recheck cases
          unparkSuccessor(h);
        }
        else if (ws == 0 &&
            !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
          continue;                // loop on failed CAS
      }
      if (h == head)               // loop if head changed
        break;
    }
  }

  /**
   * Sets head of queue, and checks if successor may be waiting
   * in shared mode, if so propagating if either propagate > 0 or
   * PROPAGATE status was set.
   *
   * @param node the node
   * @param propagate the return value from a tryAcquireShared
   */
  private void setHeadAndPropagate(Node node, int propagate) {
    Node h = head; // Record old head for check below
    setHead(node);
    /*
     * Try to signal next queued node if:
     *   Propagation was indicated by caller,
     *     or was recorded (as h.waitStatus) by a previous operation
     *     (note: this uses sign-check of waitStatus because
     *      PROPAGATE status may transition to SIGNAL.)
     * and
     *   The next node is waiting in shared mode,
     *     or we don't know, because it appears null
     *
     * The conservatism in both of these checks may cause
     * unnecessary wake-ups, but only when there are multiple
     * racing acquires/releases, so most need signals now or soon
     * anyway.
     */
    if (propagate > 0 || h == null || h.waitStatus < 0) {
      Node s = node.next;
      if (s == null || s.isShared())
        signalSharedWaiters();
    }
  }

  // Utilities for various versions of acquire

  /**
   * Cancels an ongoing attempt to acquire.
   *
   * @param node the node
   */
  private void cancelAcquire(Node node) {
    // Ignore if node doesn't exist
    if (node == null)
      return;

    node.thread = null;

    // Skip cancelled predecessors
    Node pred = node.prev;
    while (pred.waitStatus > 0)
      node.prev = pred = pred.prev;

    // predNext is the apparent node to unsplice. CASes below will
    // fail if not, in which case, we lost race vs another cancel
    // or signal, so no further action is necessary.
    Node predNext = pred.next;

    // Can use unconditional write instead of CAS here.
    // After this atomic step, other Nodes can skip past us.
    // Before, we are free of interference from other threads.
    node.waitStatus = Node.CANCELLED;

    // If we are the tail, remove ourselves.
    if (node == tail && compareAndSetTail(node, pred)) {
      compareAndSetNext(pred, predNext, null);
    } else {
      // If successor needs signal, try to set pred's next-link
      // so it will get one. Otherwise wake it up to propagate.
      int ws;
      if (pred != head &&
          ((ws = pred.waitStatus) == Node.SIGNAL ||
              (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
          pred.thread != null) {
        Node next = node.next;
        if (next != null && next.waitStatus <= 0)
          compareAndSetNext(pred, predNext, next);
      } else {
        unparkSuccessor(node);
      }

      node.next = node; // help GC
    }
  }

  /**
   * Checks and updates status for a node that failed to acquire.
   * Returns true if thread should block. This is the main signal
   * control in all acquire loops.  Requires that pred == node.prev.
   *
   * @param pred node's predecessor holding status
   * @param node the node
   * @return {@code true} if thread should block
   */
  private static boolean shouldParkAfterFailedAcquire(Node pred,
      final Node node) {
    int ws = pred.waitStatus;
    if (ws == Node.SIGNAL) {
      /*
       * This node has already set status asking a release
       * to signal it, so it can safely park.
       */
      return true;
    }
    if (ws > 0) {
      /*
       * Predecessor was cancelled. Skip over predecessors and
       * indicate retry.
       */
      do {
        node.prev = pred = pred.prev;
      } while (pred.waitStatus > 0);
      pred.next = node;
    } else {
      /*
       * waitStatus must be 0 or PROPAGATE.  Indicate that we
       * need a signal, but don't park yet.  Caller will need to
       * retry to make sure it cannot acquire before parking.
       */
      compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
    }
    return false;
  }

  /*
   * Various flavors of acquire, varying in exclusive/shared and
   * control modes.  Each is mostly the same, but annoyingly
   * different.  Only a little bit of factoring is possible due to
   * interactions of exception mechanics (including ensuring that we
   * cancel if tryAcquire throws exception) and other control, at
   * least not without hurting performance too much.
   */

  /**
   * Acquires in exclusive timed mode.
   * 
   * @param arg
   *          the acquire argument
   * @param ownerId
   *          the lock owner, if any
   * @param lock
   *          the {@link TryLockObject} that will encapsulate the state of lock
   * @param nanosTimeout
   *          max wait time
   * @param context
   *          Any context object can be provided here (that can be passed to
   *          methods like
   *          {@link ExclusiveSharedSynchronizer#getOwnerId(Object)} by
   *          implementations of {@link TryLockObject}).
   * 
   * @return {@code true} if acquired
   */
  private boolean doAcquireNanos(final int arg, final Object ownerId,
      final TryLockObject lock, long nanosTimeout, final Object context,
      final CancelCriterion cc) {
    if (nanosTimeout <= 0L)
      return false;
    final long deadline = System.nanoTime() + nanosTimeout;
    final Node node = addWaiter(Node.EXCLUSIVE);
    boolean failed = true;
    try {
      for (;;) {
        final Node p = node.predecessor();
        if (p == this.head && lock.tryAcquire(arg, ownerId, context) >= 0) {
          setHead(node);
          p.next = null; // help GC
          setExclusiveOwnerThread(Thread.currentThread());
          failed = false;
          return true;
        }
        nanosTimeout = deadline - System.nanoTime();
        if (nanosTimeout <= 0L)
          return false;
        if (shouldParkAfterFailedAcquire(p, node) &&
            nanosTimeout > spinForTimeoutThreshold)
          LockSupport.parkNanos(this, nanosTimeout);
        if (Thread.interrupted()) {
          handleInterrupted(cc);
          return false;
        }
      }
    } finally {
      if (failed) {
        cancelAcquire(node);
      }
    }
  }

  /**
   * Acquires in shared timed mode.
   * 
   * @param arg
   *          the acquire argument
   * @param ownerId
   *          the lock owner, if any
   * @param lock
   *          the {@link TryLockObject} that will encapsulate the state of lock
   * @param nanosTimeout
   *          max wait time
   * @param context
   *          Any context object can be provided here (that can be passed to
   *          methods like
   *          {@link ExclusiveSharedSynchronizer#getOwnerId(Object)} by
   *          implementations of {@link TryLockObject}).
   * 
   * @return {@code true} if acquired
   */
  private boolean doAcquireSharedNanos(final int arg, final Object ownerId,
      final TryLockObject lock, long nanosTimeout, final Object context,
      final CancelCriterion cc) {
    if (nanosTimeout <= 0L)
      return false;
    final long deadline = System.nanoTime() + nanosTimeout;
    final Node node = addWaiter(Node.SHARED);
    boolean failed = true;
    try {
      for (;;) {
        final Node p = node.predecessor();
        if (p == this.head) {
          final int r = lock.tryAcquireShared(arg, ownerId, context);
          if (r >= 0) {
            setHeadAndPropagate(node, r);
            p.next = null; // help GC
            failed = false;
            return true;
          }
        }
        nanosTimeout = deadline - System.nanoTime();
        if (nanosTimeout <= 0L)
          return false;
        if (shouldParkAfterFailedAcquire(p, node) &&
            nanosTimeout > spinForTimeoutThreshold)
          LockSupport.parkNanos(this, nanosTimeout);
        if (Thread.interrupted()) {
          handleInterrupted(cc);
          return false;
        }
      }
    } finally {
      if (failed) {
        cancelAcquire(node);
      }
    }
  }

  private static void handleInterrupted(CancelCriterion cc) {
    Thread.currentThread().interrupt();
    // below call will also check for CancelCriterion
    if (cc == null) {
      GemFireCacheImpl.getExisting().getCancelCriterion()
          .checkCancelInProgress(null);
    } else {
      cc.checkCancelInProgress(null);
    }
  }

  // Main exported methods

  /**
   * Attempts to acquire in exclusive mode, aborting if interrupted,
   * and failing if the given timeout elapses.  Implemented by first
   * checking interrupt status, then invoking at least once {@link
   * TryLockObject#tryAcquire}, returning on success.  Otherwise, the thread is
   * queued, possibly repeatedly blocking and unblocking, invoking
   * {@link TryLockObject#tryAcquire} until success or the thread is
   * interrupted or the timeout elapses.
   *
   * @param arg
   *          the acquire argument. This value is conveyed to
   *          {@link TryLockObject#tryAcquire} but is otherwise uninterpreted
   *          and can represent anything you like.
   * @param ownerId
   *          the lock owner, if any
   * @param lock
   *          the {@link TryLockObject} that will encapsulate the state of lock
   * @param nanosTimeout
   *          the maximum number of nanoseconds to wait
   * @param context
   *          Any context object can be provided here (that can be passed to
   *          methods like
   *          {@link ExclusiveSharedSynchronizer#getOwnerId(Object)} by
   *          implementations of {@link TryLockObject}).
   * 
   * @return {@code true} if acquired; {@code false} if timed out
   */
  public final boolean tryAcquireNanos(final int arg, final Object ownerId,
      final TryLockObject lock, final long nanosTimeout, final Object context,
      final CancelCriterion cc) {
    if (Thread.interrupted()) {
      handleInterrupted(cc);
    }
    return lock.tryAcquire(arg, ownerId, context) >= 0 ||
        doAcquireNanos(arg, ownerId, lock, nanosTimeout, context, cc);
  }

  /**
   * Attempts to acquire in shared mode, aborting if interrupted, and
   * failing if the given timeout elapses.  Implemented by first
   * checking interrupt status, then invoking at least once {@link
   * TryLockObject#tryAcquireShared}, returning on success.  Otherwise, the
   * thread is queued, possibly repeatedly blocking and unblocking,
   * invoking {@link TryLockObject#tryAcquireShared} until success or
   * the thread is interrupted or the timeout elapses.
   *
   * @param arg
   *          the acquire argument. This value is conveyed to
   *          {@link TryLockObject#tryAcquireShared} but is otherwise
   *          uninterpreted and can represent anything you like.
   * @param ownerId
   *          the lock owner, if any
   * @param lock
   *          the {@link TryLockObject} that will encapsulate the state of lock
   * @param nanosTimeout
   *          the maximum number of nanoseconds to wait
   * @param context
   *          Any context object can be provided here (that can be passed to
   *          methods like
   *          {@link ExclusiveSharedSynchronizer#getOwnerId(Object)} by
   *          implementations of {@link TryLockObject}).
   * 
   * @return {@code true} if acquired; {@code false} if timed out
   */
  public final boolean tryAcquireSharedNanos(final int arg,
      final Object ownerId, final TryLockObject lock, final long nanosTimeout,
      final Object context, final CancelCriterion cc) {
    if (Thread.interrupted()) {
      handleInterrupted(cc);
    }
    return lock.tryAcquireShared(arg, ownerId, context) >= 0 ||
        doAcquireSharedNanos(arg, ownerId, lock, nanosTimeout, context, cc);
  }

  public final void setOwnerThread() {
    super.setExclusiveOwnerThread(Thread.currentThread());
  }

  public final void clearOwnerThread() {
    super.setExclusiveOwnerThread(null);
  }

  public final Thread getOwnerThread() {
    return super.getExclusiveOwnerThread();
  }

  // Queue inspection methods

  /**
   * Queries whether any threads are waiting to acquire. Note that
   * because cancellations due to interrupts and timeouts may occur
   * at any time, a {@code true} return does not guarantee that any
   * other thread will ever acquire.
   *
   * <p>In this implementation, this operation returns in
   * constant time.
   *
   * @return {@code true} if there may be other threads waiting to acquire
   */
  public final boolean hasQueuedThreads() {
    return head != tail;
  }

  /**
   * Queries whether any threads have ever contended to acquire this
   * synchronizer; that is, if an acquire method has ever blocked.
   *
   * <p>In this implementation, this operation returns in
   * constant time.
   *
   * @return {@code true} if there has ever been contention
   */
  public final boolean hasContended() {
    return head != null;
  }

  /**
   * Returns true if the given thread is currently queued.
   *
   * <p>
   * This implementation traverses the queue to determine presence of the given
   * thread.
   *
   * @param thread
   *          the thread
   * @return {@code true} if the given thread is on the queue
   * @throws NullPointerException
   *           if the thread is null
   */
  public final boolean isQueued(final Thread thread) {
    if (thread == null) {
      throw new NullPointerException("isQueued: null thread passed");
    }
    for (Node p = this.tail; p != null; p = p.prev) {
      if (p.thread == thread) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the apparent first queued thread, if one
   * exists, is waiting in exclusive mode.  If this method returns
   * {@code true}, and the current thread is attempting to acquire in
   * shared mode (that is, this method is invoked from {@link
   * TryLockObject#tryAcquireShared}) then it is guaranteed that the
   * current thread is not the first queued thread.  Used only as a
   * heuristic in ReentrantReadWriteLock.
   */
  public final boolean apparentlyFirstQueuedIsExclusive() {
    Node h, s;
    return (h = head) != null &&
        (s = h.next)  != null &&
        !s.isShared()         &&
        s.thread != null;
  }

  // Instrumentation and monitoring methods

  /**
   * Returns the number of threads currently waiting on this lock that must have
   * been set by the caller by invoking {@link #incrementNumWaiters()}/
   * {@link #decrementNumWaiters()} methods. Note that when this can be
   * different from the size of queue itself since a waiter node may be
   * added/removed multiple times for the same thread when waiting in a loop.
   */
  public final int getNumWaiters() {
    return this.numWaiters;
  }

  /**
   * Initialize the number of waiter threads to given value.
   */
  public final void initNumWaiters(final int numWaiters) {
    numWaitersUpdater.set(this, numWaiters);
  }

  /**
   * Increment the number of waiter threads. Normally should be invoked by the
   * caller before invoking one of the acquire* methods on this queue.
   */
  public final int incrementNumWaiters() {
    return numWaitersUpdater.incrementAndGet(this);
  }

  /**
   * Decrement the number of waiter threads. Normally should be invoked by the
   * caller after completion of one of the acquire* methods of this queue.
   */
  public final int decrementNumWaiters() {
    return numWaitersUpdater.decrementAndGet(this);
  }

  /**
   * Returns an estimate of the number of threads waiting to acquire. The value
   * is only an estimate because the number of threads may change dynamically
   * while this method traverses internal data structures. This method is
   * designed for use in monitoring system state, not for synchronization
   * control.
   * 
   * @return the estimated number of threads waiting to acquire
   */
  public final int getQueueLength() {
    int n = 0;
    for (Node p = this.tail; p != null; p = p.prev) {
      if (p.thread != null) {
        ++n;
      }
    }
    return n;
  }

  /**
   * Returns a collection containing threads that may be waiting to acquire.
   * Because the actual set of threads may change dynamically while constructing
   * this result, the returned collection is only a best-effort estimate. The
   * elements of the returned collection are in no particular order. This method
   * is designed to facilitate construction of subclasses that provide more
   * extensive monitoring facilities.
   * 
   * @return the collection of threads
   */
  public final Collection<Thread> getQueuedThreads() {
    final ArrayList<Thread> list = new ArrayList<Thread>();
    for (Node p = this.tail; p != null; p = p.prev) {
      final Thread t = p.thread;
      if (t != null) {
        list.add(t);
      }
    }
    return list;
  }

  /**
   * Returns a collection containing threads that may be waiting to acquire in
   * exclusive mode. This has the same properties as {@link #getQueuedThreads}
   * except that it only returns those threads waiting due to an exclusive
   * acquire.
   * 
   * @return the collection of threads
   */
  public final Collection<Thread> getExclusiveQueuedThreads() {
    final ArrayList<Thread> list = new ArrayList<Thread>();
    for (Node p = this.tail; p != null; p = p.prev) {
      if (!p.isShared()) {
        final Thread t = p.thread;
        if (t != null) {
          list.add(t);
        }
      }
    }
    return list;
  }

  /**
   * Returns a collection containing threads that may be waiting to acquire in
   * shared mode. This has the same properties as {@link #getQueuedThreads}
   * except that it only returns those threads waiting due to a shared acquire.
   * 
   * @return the collection of threads
   */
  public final Collection<Thread> getSharedQueuedThreads() {
    final ArrayList<Thread> list = new ArrayList<Thread>();
    for (Node p = this.tail; p != null; p = p.prev) {
      if (p.isShared()) {
        final Thread t = p.thread;
        if (t != null) {
          list.add(t);
        }
      }
    }
    return list;
  }

  /**
   * Returns a string identifying this synchronizer. Either {@code "nonempty"}
   * or {@code "empty"} depending on whether the queue is empty.
   * 
   * @return a string identifying this synchronizer
   */
  public final StringBuilder toString(final StringBuilder sb) {
    final String q = hasQueuedThreads() ? "non-" : "";
    return sb.append(", ").append(q).append("empty queue");
  }

  @Override
  public String toString() {
    return "QSync@"
        + Integer.toHexString(System.identityHashCode(this));
  }

  public StringBuilder toObjectString(StringBuilder sb) {
    sb.append("QSync@").append(
        Integer.toHexString(System.identityHashCode(this)));
    final Thread owner = super.getExclusiveOwnerThread();
    if (owner != null) {
      sb.append(",owner=").append(owner);
    }
    return sb;
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="EQ_CHECK_FOR_OPERAND_NOT_COMPATIBLE_WITH_THIS")
  public final boolean equals(final Object other) {
    if (other instanceof Node) {
      return other == this.head;
    }
    return other == this;
  }

  @Override
  public int hashCode() {
    // findbugs doesn't like the equals() method w/o a hashCode() method
    throw new UnsupportedOperationException("this class does not support hashing");
  }

  /**
   * CAS head field. Used only by {@link #enq(Node)}.
   */
  private final boolean compareAndSetHead(final Node update) {
    return headUpdater.compareAndSet(this, null, update);
  }

  /**
   * CAS tail field. Used only by {@link #enq(Node)} and
   * {@link #addWaiter(Node)}.
   */
  private final boolean compareAndSetTail(final Node expect,
      final Node update) {
    return tailUpdater.compareAndSet(this, expect, update);
  }

  /**
   * CAS waitStatus field of a node.
   */
  private final static boolean compareAndSetWaitStatus(final Node node,
      final int expect, final int update) {
    return nodeStatusUpdater.compareAndSet(node, expect, update);
  }

  /**
   * CAS next field of a node.
   */
  private final static boolean compareAndSetNext(final Node node,
      final Node expect, final Node update) {
    return nextNodeUpdater.compareAndSet(node, expect, update);
  }
}
