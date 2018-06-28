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

package com.pivotal.gemfirexd.internal.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.cache.Conflatable;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderEventImpl;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.ReplayableConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLQueueEntry;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
 * Helper class to handle conflation of entries in {@link GfxdDDLRegionQueue}
 * for example. This class uses a special comparator
 * {@link ConflatableComparator} to order the {@link Conflatable}s by both
 * region and keys, so that when dropping a region, for example, all the keys in
 * the region can be removed. In other words it allows for indexing by both
 * region as well as a particular key in the region using a single index.
 * This class does not handle {@link EventID}s at all.
 * 
 * @author swale
 * 
 * @param <TValue>
 *          the type of value that is to be stored in the collection against the
 *          conflatable key
 */
public final class GfxdOpConflationHandler<TValue> {

  private final SortedMap<Conflatable, TValue> conflationIndex;

  private String logPrefix;

  public GfxdOpConflationHandler() {
    this.conflationIndex = new TreeMap<Conflatable, TValue>(
        new ConflatableComparator());
    this.logPrefix = "GfxdOpConflationHandler";
  }

  /**
   * Add the given key and value to conflation index.
   */
  public void addToConflationIndex(Conflatable key, TValue value) {
    this.conflationIndex.put(key, value);
  }

  /**
   * Remove the given key from the conflation index.
   */
  public void removeFromConflationIndex(Conflatable key) {
    this.conflationIndex.remove(key);
  }

  /**
   * Conflate the queue for the given value. This can either conflate the
   * collection provided as argument directly and/or populate a list containing
   * the conflatable items. The latter is useful when it is being invoked during
   * iteration of queue itself (e.g. during
   * {@link GfxdDDLRegionQueue#populateQueue}).
   * 
   * @param confVal
   *          value for which conflation has to be tried
   * @param confKey
   *          the key to be searched in the conflation index; normally can just
   *          be {@link Conflatable#getKeyToConflate()}
   * @param removeList
   *          if non-null then populate this list with conflated
   *          {@link GfxdDDLRegionQueue.QueueValue}s instead of removing from
   *          conflatable collection directly
   * @param collection
   *          if non-null then also remove directly from this collection
   * @param removeFromIndex
   *          if true then also remove from conflation index
   * @param skipExecuting
   *          if true then skip any {@link ReplayableConflatable}s marked as
   *          executing and don't remove from collection or index; it will still
   *          be returned in <code>removeList</code> if provided
   * @return true if one or more items were conflated and false otherwise
   */
  public boolean doConflate(Conflatable confVal, Object confKey,
      TValue confValEntry, List<TValue> removeList,
      Collection<TValue> collection, boolean removeFromIndex,
      boolean skipExecuting) {
    if (confVal.shouldBeConflated()) {
      boolean result = applyConflate(confVal, confKey, confValEntry,
          removeList, null, collection, removeFromIndex, skipExecuting);
      // the item being checked will also be conflated in this case
      // remove DROP DDLs in every case since those imply a corresponding
      // CREATE statement else its an "if exists" case where there was
      // no existing entity in which case also it should be cleaned up
      if (removeList != null && (result ||
          (confVal instanceof DDLConflatable) ||
          (confVal instanceof GfxdSystemProcedureMessage))) {
        removeList.add(confValEntry);
      }
      return result;
    }
    else if (confVal.shouldBeMerged()) {
      final ArrayList<Map.Entry<Conflatable, TValue>> removeConflatables =
          new ArrayList<Map.Entry<Conflatable, TValue>>();
      if (confVal instanceof DDLConflatable &&
          ((DDLConflatable)confVal).isAlterTableDropFKConstraint()) {
        List<TValue> removeListIgnored = new ArrayList<TValue>();
        boolean merge = applyConflate(confVal, confKey, confValEntry, removeListIgnored,
            removeConflatables, null, false, skipExecuting);
        if (merge) {
          Map.Entry<Conflatable, TValue> cEntry = null;
          for (int index = 0; index < removeConflatables.size(); index++) {
            cEntry = removeConflatables.get(index);
            cEntry.getKey().merge(confVal); 
          }
        }
      } 
      else if (applyConflate(confVal, confKey, confValEntry, null,
          removeConflatables, null, false, skipExecuting)) {
          ArrayList<TValue> rremoveList = null;
          if (removeList != null) {
            rremoveList = new ArrayList<TValue>();
          }
          // merge in reverse order
          Map.Entry<Conflatable, TValue> cEntry = null;
          for (int index = removeConflatables.size() - 1; index >= 0; index--) {
            cEntry = removeConflatables.get(index);
            if (confVal.merge(cEntry.getKey())) {
              removeConflatables.remove(index);
              if (rremoveList != null) {
                rremoveList.add(cEntry.getValue());
              }
              if (collection != null) {
                collection.remove(cEntry.getValue());
              }
              if (removeFromIndex) {
                this.conflationIndex.remove(cEntry.getKey());
              }
            } else {
              break;
            }
          }
          // copy back in correct order
          if (removeList != null) {
            for (int index = rremoveList.size() - 1; index >= 0; index--) {
              removeList.add(rremoveList.get(index));
            }
          }
          // update the sequenceId to make the last one appear in the position
          // of first one
          if (cEntry != null) {
            final TValue firstValue = cEntry.getValue();
            if (confValEntry instanceof GfxdDDLQueueEntry
                && firstValue instanceof GfxdDDLQueueEntry) {
              GfxdDDLQueueEntry thisEntry = (GfxdDDLQueueEntry) confValEntry;
              GfxdDDLQueueEntry firstEntry = (GfxdDDLQueueEntry) firstValue;
              if (thisEntry.getSequenceId() <= 0
                  || thisEntry.getSequenceId() > firstEntry.getSequenceId()) {
                thisEntry.setSequenceId(firstEntry.getSequenceId());
              }
            }
          }
          // finally add the merged entry into the conflation index
          if (GemFireXDUtils.TraceConflation | DistributionManager.VERBOSE) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
                toString() + ": adding merged entry " + confValEntry);
        }
        addToConflationIndex(confVal, confValEntry);
      }
    }
    return false;
  }

  /**
   * Conflate the queue for the given value. This can either conflate the
   * collection provided as argument directly and/or populate a list containing
   * the conflatable items. The latter is useful when it is being invoked during
   * iteration of queue itself (e.g. during
   * {@link GfxdDDLRegionQueue#populateQueue}).
   * 
   * @param confVal
   *          value for which conflation has to be tried
   * @param confKey
   *          the key to be searched in the conflation index; normally can just
   *          be {@link Conflatable#getKeyToConflate()}
   * @param removeList
   *          if non-null then populate this list with conflated
   *          {@link GfxdDDLRegionQueue.QueueValue}s
   * @param removeConflatables
   *          if non-null then populate this list with the conflated keys
   * @param collection
   *          if non-null then also remove directly from this collection
   * @param removeFromIndex
   *          if true then also remove from conflation index
   * @param skipExecuting
   *          if true then skip any {@link ReplayableConflatable}s marked as
   *          executing and don't remove from collection or index; it will still
   *          be returned in <code>removeList</code> if provided
   * 
   * @return true if one or more items were conflated and false otherwise
   */
  public boolean applyConflate(Conflatable confVal, Object confKey,
      Object confValEntry, List<TValue> removeList,
      List<Map.Entry<Conflatable, TValue>> removeConflatables,
      Collection<TValue> collection, boolean removeFromIndex,
      boolean skipExecuting) {
    boolean conflationDone = false;
    // conflate all keys for the "region" or "keyToConflate"
    String regionName = confVal.getRegionToConflate();
    Conflatable sKey = new MinMaxConflatable(regionName, confKey, true);
    Conflatable eKey = new MinMaxConflatable(regionName, confKey, false);
    Iterator<Map.Entry<Conflatable, TValue>> sMapIter = this.conflationIndex
        .subMap(sKey, eKey).entrySet().iterator();
    Conflatable conflatable;
    if (removeList == null && collection == null) {
      // don't need to do any updates so just return if this particular
      // Conflatable matches any other in the current index
      while (sMapIter.hasNext()) {
        // don't conflate same "DROP" statements, for example
        conflatable = sMapIter.next().getKey();
        if (!skipConflation(confVal, conflatable)) {
          conflationDone = true;
          break;
        }
      }
    }
    else {
      final ArrayList<Conflatable> removeEntries = new ArrayList<>(4);
      while (sMapIter.hasNext()) {
        Map.Entry<Conflatable, TValue> confEntry = sMapIter.next();
        conflatable = confEntry.getKey();
        // don't conflate same "DROP" statements, for example
        if (skipConflation(confVal, conflatable)) continue;
        if (removeList != null) {
          removeList.add(confEntry.getValue());
        }
        if (removeConflatables != null) {
          removeConflatables.add(confEntry);
        }
        conflationDone = true;
        // skip executing entries
        if (skipExecuting && conflatable instanceof ReplayableConflatable
            && ((ReplayableConflatable)conflatable).isExecuting()) {
          continue;
        }
        if ((GemFireXDUtils.TraceConflation | DistributionManager.VERBOSE)
            && (collection != null || removeFromIndex)) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
              this.logPrefix + ": conflating entry [" + confEntry.getValue()
                  + "] for entry [" + confValEntry + ']');
        }
        if (collection != null) {
          collection.remove(confEntry.getValue());
        }
        if (removeFromIndex) {
          removeEntries.add(conflatable);
        }
      }
      if (removeEntries.size() > 0) {
        for (Conflatable c : removeEntries) {
          this.conflationIndex.remove(c);
        }
      }
    }
    if (conflationDone) {
      if (GemFireXDUtils.TraceConflation) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONFLATION,
            this.logPrefix + ": conflated for entry [" + confValEntry + ']');
      }
    }
    return conflationDone;
  }

  private static boolean skipConflation(Conflatable current, Conflatable check) {
    return check.shouldBeConflated()
        && current.shouldBeMerged() == check.shouldBeMerged()
        && current.getClass().equals(check.getClass())
        && ArrayUtils.objectEquals(current.getRegionToConflate(), check.getRegionToConflate())
        && ArrayUtils.objectEquals(current.getKeyToConflate(), check.getKeyToConflate());
  }

  public TValue indexGet(Conflatable key) {
    return this.conflationIndex.get(key);
  }

  public void close() {
    this.conflationIndex.clear();
  }

  public void setLogPrefix(String logPrefix) {
    this.logPrefix = logPrefix;
  }

  /**
   * A {@link Conflatable} that can be used as the start or end criteria to
   * search for all entries of a region or for all entries of a conflatable key
   * using {@link ConflatableComparator}.
   * 
   * @author swale
   */
  @SuppressWarnings("serial")
  static final class MinMaxConflatable implements Conflatable {

    private final String regionName;

    private final Object key;

    private final Object value;

    MinMaxConflatable(String regionName, Object key, boolean isMin) {
      this.regionName = regionName;
      this.key = (key != null ? key : (isMin ? GemFireXDUtils.MIN_KEY
          : GemFireXDUtils.MAX_KEY));
      this.value = (isMin ? GemFireXDUtils.MIN_KEY : GemFireXDUtils.MAX_KEY);
    }

    public String getRegionToConflate() {
      return this.regionName;
    }

    public Object getKeyToConflate() {
      return this.key;
    }

    public Object getValueToConflate() {
      return this.value;
    }

    public void setLatestValue(Object value) {
      throw new AssertionError("MinMaxConflatable#setLatestValue "
          + "not expected to be invoked");
    }

    public boolean shouldBeConflated() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldBeMerged() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean merge(Conflatable existing) {
      throw new AssertionError("not expected to be invoked");
    }

    public EventID getEventId() {
      throw new AssertionError("MinMaxConflatable#getEventId: "
          + "not expected to be invoked");
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof MinMaxConflatable) {
        final MinMaxConflatable otherMinMax = (MinMaxConflatable)other;
        if (!ArrayUtils.objectEquals(this.regionName, otherMinMax.regionName)) {
          return false;
        }
        if (GemFireXDUtils.compareKeys(this.key, otherMinMax.key) != 0) {
          return false;
        }
        return GemFireXDUtils.compareKeys(this.value, otherMinMax.value) == 0;
      }
      else {
        return false;
      }
    }

    @Override
    public String toString() {
      return "MinMaxConflatable: region=" + this.regionName + ", key="
          + this.key + ", value=" + this.value;
    }
  }

  /**
   * Comparator to order {@link Conflatable}s by their regions to allow for
   * range search for all entries of a region or of a conflatable key. Also
   * allows for using {@link GemFireXDUtils#MIN_KEY}, {@link GemFireXDUtils#MAX_KEY}
   * which is useful for {@link MinMaxConflatable} as start/stop conditions to
   * search for all entries corresponding to a region or for all entries for a
   * conflatable key.
   * 
   * @author swale
   */
  static final class ConflatableComparator implements Comparator<Conflatable> {

    @SuppressWarnings("unchecked")
    public int compare(Conflatable first, Conflatable second) {
      if (first instanceof Comparable<?> && second instanceof Comparable<?>) {
        return ((Comparable<Object>)first).compareTo(second);
      }
      int res = first.getRegionToConflate().compareTo(
          second.getRegionToConflate());
      if (res != 0) {
        return res;
      }
      res = GemFireXDUtils.compareKeys(first.getKeyToConflate(), second
          .getKeyToConflate());
      if (res != 0) {
        return res;
      }
      assert ! (first instanceof GatewaySenderEventImpl);
      assert ! (second instanceof GatewaySenderEventImpl);
      res = GemFireXDUtils.compareKeys(first.getValueToConflate(), second
          .getValueToConflate());
      if (res != 0) {
        return res;
      }
      // finally compare the objects themselves to decide whether they are equal
      // (two ALTER TABLES are not equal even if they have same SQL for example)
      if (first.equals(second)) {
        return 0;
      }
      else {
        // use some arbitrary criteria like hashCode comparison to decide
        return ((first.hashCode() < second.hashCode()) ? -1 : 1);
      }
    }
  }
}
