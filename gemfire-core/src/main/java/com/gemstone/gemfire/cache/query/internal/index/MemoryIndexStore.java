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
package com.gemstone.gemfire.cache.query.internal.index;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.TypeMismatchException;
import com.gemstone.gemfire.cache.query.internal.DefaultQuery;
import com.gemstone.gemfire.cache.query.internal.index.AbstractIndex.InternalIndexStatistics;
import com.gemstone.gemfire.cache.query.internal.parse.OQLLexerTokenTypes;
import com.gemstone.gemfire.cache.query.internal.types.TypeUtils;
import com.gemstone.gemfire.internal.cache.CachedDeserializable;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.cache.persistence.query.CloseableIterator;

/**
 * The in-memory index storage
 * 
 * @author Tejas Nomulwar
 * @since 7.5 
 */
public class MemoryIndexStore implements IndexStore {
  /**
   * Map for valueOf(indexedExpression)=>RegionEntries. SortedMap<Object,
   * (RegionEntry | List<RegionEntry>)>. Package access for unit tests.
   */
  final ConcurrentSkipListMap<Object, Object> valueToEntriesMap =
      new ConcurrentSkipListMap<Object, Object>(TypeUtils.getExtendedNumericComparator());

  // number of keys
  protected volatile AtomicInteger numIndexKeys = new AtomicInteger(0);

  // Map for RegionEntries=>value of indexedExpression (reverse map)
  private ConcurrentHashMap<RegionEntry, Object> entryToValuesMap;

  private InternalIndexStatistics internalIndexStats;

  private Region region;
  private boolean indexOnRegionKeys;
  private boolean indexOnValues;

  public MemoryIndexStore(Region region,
      InternalIndexStatistics internalIndexStats) {
    this.region = region;
    RegionAttributes ra = region.getAttributes();
    // Initialize the reverse-map if in-place modification is set by the
    // application.
    if (IndexManager.isObjectModificationInplace()) {
      this.entryToValuesMap = new ConcurrentHashMap<>(ra.getInitialCapacity(),
          ra.getLoadFactor(), ra.getConcurrencyLevel());
    }
    this.internalIndexStats = internalIndexStats;
  }

  @Override
  public void updateMapping(Object newKey, Object oldKey, RegionEntry entry)
      throws IMQException {
    try {
      if (DefaultQuery.testHook != null) {
        DefaultQuery.testHook.doTestHook(3);
      }
//      //Oldkey of null means it is an add only.  Do not update/remove
//      if (oldKey != null) {
        if (IndexManager.isObjectModificationInplace()
            && this.entryToValuesMap.containsKey(entry)) {
          oldKey = this.entryToValuesMap.get(entry);
          // If oldKey and the newKey are same there is no need to update the
          // index-maps.
          if (oldKey.equals(TypeUtils.indexKeyFor(newKey))) {
            return;
          }
        }
     // }

      boolean retry = false;
      newKey = TypeUtils.indexKeyFor(newKey);
      do {
        retry = false;
        Object regionEntries = this.valueToEntriesMap
            .putIfAbsent(newKey, entry);
        if (regionEntries == null) {
          internalIndexStats.incNumKeys(1);
          numIndexKeys.incrementAndGet();
        } else if (regionEntries instanceof RegionEntry) {
          IndexElemArray elemArray = new IndexElemArray();
          // Replace first so that we are sure that the set is placed in index
          // then we should add old elements in the new set.
          if (!this.valueToEntriesMap.replace(newKey, regionEntries, elemArray)) {
            retry = true;
          } else {
            elemArray.add(regionEntries);
            elemArray.add(entry);
          }
        } else if (regionEntries instanceof IndexConcurrentHashSet) {
          // This synchronized is for avoiding conflcts with remove of
          // ConcurrentHashSet when set size becomes zero during
          // basicRemoveMapping();
          synchronized (regionEntries) {
            ((IndexConcurrentHashSet) regionEntries).add(entry);
          }
          if (regionEntries != this.valueToEntriesMap.get(newKey)) {
            retry = true;
          }
        } else {
          IndexElemArray elemArray = (IndexElemArray) regionEntries;
          synchronized (elemArray) {
            if (elemArray.size() >= IndexManager.INDEX_ELEMARRAY_THRESHOLD) {
              IndexConcurrentHashSet set = new IndexConcurrentHashSet(
                  IndexManager.INDEX_ELEMARRAY_THRESHOLD + 20, 0.75f, 1);
              // Replace first so that we are sure that the set is placed in
              // index then we should add old elements in the new set.
              if (!this.valueToEntriesMap.replace(newKey, regionEntries, set)) {
                retry = true;
              } else {
                set.addAll(elemArray);
                set.add(entry);
              }
            } else {
              elemArray.add(entry);
              if (regionEntries != this.valueToEntriesMap.get(newKey)) {
                retry = true;
              }
            }
          }
        }

        // Add to reverse Map with the new value.
        if (!retry) {
          
          // remove from forward map in case of update
          // oldKey is not null only for an update
          if (oldKey != null) {
            basicRemoveMapping(oldKey, entry);
          }
          
          if (IndexManager.isObjectModificationInplace()) {
            this.entryToValuesMap.put(entry, newKey);
          }
        }

      } while (retry);

    } catch (TypeMismatchException ex) {
      throw new IMQException("Could not add object of type "
          + newKey.getClass().getName(), ex);
    }
    internalIndexStats.incNumValues(1);
  }

  @Override
  public void addMapping(Object newKey, RegionEntry entry) throws IMQException {
    // for add, oldkey is null
    updateMapping(newKey, null, entry);
  }

  @Override
  public void removeMapping(Object key, RegionEntry entry) throws IMQException {
    // Remove from forward map
    boolean found = basicRemoveMapping(key, entry);
    // Remove from reverse map.
    // We do NOT need to synchronize here as different RegionEntries will be
    // operating concurrently i.e. different keys in entryToValuesMap which
    // is a concurrent map.
    if (found && IndexManager.isObjectModificationInplace()) {
      this.entryToValuesMap.remove(entry);
    }
  }

  public boolean basicRemoveMapping(Object key, RegionEntry entry)
      throws IMQException {
    boolean found = false;
    try {
      boolean retry = false;
      Object newKey;
      if (IndexManager.isObjectModificationInplace()
          && this.entryToValuesMap.containsKey(entry)) {
        newKey = this.entryToValuesMap.get(entry);
      }
      else {
        newKey = TypeUtils.indexKeyFor(key);
      }
      
      do {
        retry = false;
        Object regionEntries = this.valueToEntriesMap.get(newKey);
        if (regionEntries != null) {
          if (regionEntries instanceof RegionEntry) {
            found = (regionEntries == entry);
            if (found) {
              if (this.valueToEntriesMap.remove(newKey, regionEntries)) {
                numIndexKeys.decrementAndGet();
                internalIndexStats.incNumKeys(-1);
              } else {
                retry = true;
              }
            }
          } else {
            Collection entries = (Collection) regionEntries;
            found = entries.remove(entry);
            // This could be IndexElementArray and might be changing to Set
            if (entries instanceof IndexElemArray) {
              if (!this.valueToEntriesMap.replace(newKey, entries, entries)) {
                retry = true;
                continue;
              }
            }

            if (entries.isEmpty()) {
              // because entries collection is empty, remove and decrement
              // value
              synchronized (entries) {
                if (entries.isEmpty()) {
                  if (valueToEntriesMap.remove(newKey, entries)) {
                    numIndexKeys.decrementAndGet();
                    internalIndexStats.incNumKeys(-1);
                  } else {
                    retry = true;
                  }
                }
              }
            }
          }
        }
      } while (retry);
    } catch (TypeMismatchException ex) {
      throw new IMQException("Could not add object of type "
          + key.getClass().getName(), ex);
    }
    if (found) {
      // Update stats if entry was actually removed
      internalIndexStats.incNumValues(-1);
    }
    if (!found && !IndexManager.isObjectModificationInplace()
        && (key != null && key != QueryService.UNDEFINED)) {
      // this.region.getCache().getLogger().fine("##### THROWING EXCEPTION ");
      throw new IMQException("index maintenance error: "
          + "entry not found for " + key);
    }
    return found;
  }

  /**
   * Convert a RegionEntry or THashSet<RegionEntry> to be consistently a
   * Collection
   */
  private Collection regionEntryCollection(Object regionEntries) {
    if (regionEntries instanceof RegionEntry) {
      return Collections.singleton(regionEntries);
    }
    return (Collection) regionEntries;
  }

  @Override
  public CloseableIterator<IndexStoreEntry> get(Object indexKey) {
    Object value = this.valueToEntriesMap.get(indexKey);
    if (value != null) {
      return new MemoryIndexStoreIterator(null, regionEntryCollection(value)
          .iterator(), indexKey, null);
    } else {
      return null;
    }
  }

  @Override
  public CloseableIterator<IndexStoreEntry> iterator(Object start,
      boolean startInclusive, Object end, boolean endInclusive,
      Collection keysToRemove) {
    if (start == null) {
      return new MemoryIndexStoreIterator(this.valueToEntriesMap
          .headMap(end, endInclusive).entrySet().iterator(), null,
          null, keysToRemove);
    }
    return new MemoryIndexStoreIterator(this.valueToEntriesMap
        .subMap(start, startInclusive, end, endInclusive)
        .entrySet().iterator(), null, null, keysToRemove);
  }

  @Override
  public CloseableIterator<IndexStoreEntry> iterator(Object start,
      boolean startInclusive, Collection keysToRemove) {
    return new MemoryIndexStoreIterator(this.valueToEntriesMap
        .tailMap(start, startInclusive).entrySet().iterator(),
        null, null, keysToRemove);
  }

  @Override
  public CloseableIterator<IndexStoreEntry> iterator(Collection keysToRemove) {
    return new MemoryIndexStoreIterator(this.valueToEntriesMap.entrySet()
        .iterator(), null, null, keysToRemove);
  }

  @Override
  public CloseableIterator<IndexStoreEntry> descendingIterator(Object start,
      boolean startInclusive, Object end, boolean endInclusive,
      Collection keysToRemove) {
    if (start == null) {
      return new MemoryIndexStoreIterator(this.valueToEntriesMap
          .headMap(end, endInclusive).descendingMap().entrySet().iterator(),
          null, null, keysToRemove);
    }
    return new MemoryIndexStoreIterator(this.valueToEntriesMap
        .subMap(start, startInclusive, end, endInclusive).descendingMap()
        .entrySet().iterator(), null, null, keysToRemove);
  }

  @Override
  public CloseableIterator<IndexStoreEntry> descendingIterator(Object start,
      boolean startInclusive, Collection keysToRemove) {
    return new MemoryIndexStoreIterator(this.valueToEntriesMap
        .tailMap(start, startInclusive).descendingMap().entrySet().iterator(),
        null, null, keysToRemove);
  }

  @Override
  public CloseableIterator<IndexStoreEntry> descendingIterator(
      Collection keysToRemove) {
    return new MemoryIndexStoreIterator(this.valueToEntriesMap
        .descendingMap().entrySet().iterator(), null, null, keysToRemove);
  }

  @Override
  public boolean isIndexOnRegionKeys() {
    return indexOnRegionKeys;
  }

  @Override
  public void setIndexOnRegionKeys(boolean indexOnRegionKeys) {
    this.indexOnRegionKeys = indexOnRegionKeys;
  }

  @Override
  public boolean isIndexOnValues() {
    return indexOnValues;
  }

  @Override
  public void setIndexOnValues(boolean indexOnValues) {
    this.indexOnValues = indexOnValues;
  }

  /**
   * Get the object of interest from the region entry. For now it always gets
   * the deserialized value.
   */

  public Object getTargetObject(RegionEntry entry) {
    if (indexOnValues) {
      Object o = entry.getValue((LocalRegion) MemoryIndexStore.this.region);
      try {
        if (o == Token.INVALID) {
          return null;
        }
        if (o instanceof CachedDeserializable) {
          return ((CachedDeserializable) o).getDeserializedValue(
              MemoryIndexStore.this.region, entry);
        }
      } catch (EntryDestroyedException ede) {
        return null;
      }
      return o;
    } else if (indexOnRegionKeys) {
      return entry.getKey();
    }
    return ((LocalRegion) MemoryIndexStore.this.region).new NonTXEntry(entry);
  }

  @Override
  public boolean clear() {
    this.valueToEntriesMap.clear();
    if (IndexManager.isObjectModificationInplace()) {
      this.entryToValuesMap.clear();
    }
    numIndexKeys.set(0);
    return true;
  }

  @Override
  public int size(Object key) {
    Object obj = valueToEntriesMap.get(key);
    if (obj != null) {
      return regionEntryCollection(obj).size();
    } else {
      return 0;
    }
  }

  @Override
  public int size() {
    return numIndexKeys.get();
  }

  /**
   * A bi-directional iterator over the CSL. Iterates over the entries of CSL
   * where entry is a mapping (value -> Collection) as well as over the
   * Collection.
   * 
   */
  private class MemoryIndexStoreIterator implements
      CloseableIterator<IndexStoreEntry> {
    final Iterator<Map.Entry> mapIterator;
    Iterator valuesIterator;
    Collection keysToRemove;
    Object indexKey;
    MemoryIndexStoreEntry currentEntry = new MemoryIndexStoreEntry();

    private MemoryIndexStoreIterator(Iterator mapIterator,
        Iterator valuesIterator, Object indexKey, Collection keysToRemove) {
      this.mapIterator = mapIterator;
      this.valuesIterator = valuesIterator;
      this.indexKey = indexKey;
      this.keysToRemove = keysToRemove;
    }

    /**
     * This iterator iterates over the CSL map as well as on the collection of
     * values for each entry in the map. If the map has next element, check if
     * the previous valuesIterator (iterator on the Collection) has been created
     * or has finished iterating. If not created, create an iterator on the
     * Collection. If created and not finished, return, so that the next() call
     * would create a new InMemoryIndexStorageEntry for the current key and next
     * RE from the collection of values.
     */
    public boolean hasNext() {
      // return previous collection of values if not over
      if (valuesIterator != null && valuesIterator.hasNext()) {
        return true;
      }
      // sets the next values iterator
      if (mapIterator != null && mapIterator.hasNext()) {
        // set the next entry in the map as current
        Map.Entry currentIndexEntry = mapIterator.next();
        // set the index key
        indexKey = currentIndexEntry.getKey();
        // if the index key in currentIndexEntry is present in the
        // keysToRemove collection or is Undefined or Null
        // skip the current map entry and advance to the
        // next entry in the index map.
        // skipping null & undefined is required so that they do not get
        // into results of range queries.
        // equality and not equality for null/undefined would create
        // valuesIterator so it would not come here
        if ((indexKey == QueryService.UNDEFINED)
            || (indexKey == IndexManager.NULL)
            || (keysToRemove != null && removeFromKeysToRemove(keysToRemove,
                indexKey))) {
          return hasNext();
        }
        // iterator on the collection of values for the index key
        valuesIterator = regionEntryCollection(currentIndexEntry.getValue())
            .iterator();
        return valuesIterator.hasNext();
      }

      return false;
    }

    /**
     * returns a new InMemoryIndexStorageEntry with current index key and its
     * next value which is the next RE in the collection. Make sure hasNext()
     * has been called before calling this method
     */
    public MemoryIndexStoreEntry next() {
      currentEntry.setMemoryIndexStoreEntry(indexKey,
          (RegionEntry) valuesIterator.next());
      return currentEntry;
    }

    public void remove() {
      mapIterator.remove();
    }

    public void close() {
      // do nothing
    }

    public boolean removeFromKeysToRemove(Collection keysToRemove, Object key) {
      Iterator iterator = keysToRemove.iterator();
      while (iterator.hasNext()) {
        try {
          if (TypeUtils
              .compare(key, iterator.next(), OQLLexerTokenTypes.TOK_EQ).equals(
                  Boolean.TRUE)) {
            iterator.remove();
            return true;
          }
        } catch (TypeMismatchException e) {
          // they are not equals, so we just continue iterating
        }
      }
      return false;
    }
  }

  /**
   * A wrapper over the entry in the CSL index map. It maps IndexKey ->
   * RegionEntry
   * 
   */
  class MemoryIndexStoreEntry implements IndexStoreEntry {
    private Object deserializedIndexKey;
    private RegionEntry regionEntry;

    private MemoryIndexStoreEntry() {
    }

    MemoryIndexStoreEntry(Object deserializedIndexKey, RegionEntry regionEntry) {
      this.regionEntry = regionEntry;
      this.deserializedIndexKey = deserializedIndexKey;
    }

    public void setMemoryIndexStoreEntry(Object deserializedIndexKey,
        RegionEntry regionEntry) {
      this.deserializedIndexKey = deserializedIndexKey;
      this.regionEntry = regionEntry;
    }

    @Override
    public Object getDeserializedKey() {
      return deserializedIndexKey;
    }

    @Override
    public Object getDeserializedValue() {
      return getTargetObject(regionEntry);
    }

    @Override
    public Object getDeserializedRegionKey() {
      return regionEntry.getKey();
    }

    public RegionEntry getRegionEntry() {
      return regionEntry;
    }
    
    @Override
    public boolean isUpdateInProgress() {
      return regionEntry.isUpdateInProgress();
    }
  }
}
