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

package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.cache.IllegalTransactionStateException;
import com.gemstone.gemfire.internal.cache.LocalRegion.IteratorType;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Set view of entries */
public class EntriesSet extends AbstractSet<Object> {

  final LocalRegion topRegion;

  final boolean recursive;

  final IteratorType iterType;

  protected final TXStateInterface myTX;

  protected final boolean allowTombstones;

  protected final InternalDataView view;

  protected final boolean skipTxCheckInIteration;

  protected boolean keepSerialized;

  EntriesSet(LocalRegion region, boolean recursive, IteratorType viewType,
      boolean allowTombstones) {
    this(region, recursive, viewType, region.getTXState(),
        false /* skipTxCheckInIteration */, allowTombstones);
  }

  EntriesSet(LocalRegion region, boolean recursive, IteratorType viewType,
      final TXStateInterface tx, final boolean skipTxCheckInIteration,
      boolean allowTombstones) {
    this.topRegion = region;
    this.recursive = recursive;
    this.iterType = viewType;
    this.myTX = tx;
    this.view = region.getDataView(tx);
    this.skipTxCheckInIteration = skipTxCheckInIteration ||
      region.getGemFireCache().isGFXDSystem();
    this.allowTombstones = allowTombstones;
  }

  protected final void checkTX() {
    checkTX(this.myTX);
  }

  protected static final void checkTX(final TXStateInterface myTX) {
    if (myTX != null) {
      if (!myTX.isInProgress()) {
        throw new IllegalTransactionStateException(
            LocalizedStrings.TRANSACTION_0_IS_NO_LONGER_ACTIVE
                .toLocalizedString(myTX.getTransactionId()));
      }
    /* [sumedh] the below check for matching TXState at each step is too
     * expensive and not really required since it will be fine to allow for
     * moving iterators across threads as long as the original TX is active
      if (this.myTX != this.topRegion.getTXState()) {
        throw new IllegalTransactionStateException(LocalizedStrings
            .LocalRegion_REGION_COLLECTION_WAS_CREATED_WITH_A_DIFFERENT_TRANSACTION
                .toLocalizedString(this.myTX.getTransactionId()));
      }
    }
    else {
      // Throw an exception if a set created outside a transaction is being used
      // in a transactional context (in the same thread)
      // Also protects against handing this set which contains the thread local
      // txState to another thread

      // Asif: Fix for Bug 42031. The GemFireXD needs iterator on non Tx Entry
      // while doing table scan etc.
      if (this.topRegion.isTX()) {
        throw new IllegalTransactionStateException(LocalizedStrings
            .LocalRegion_NON_TRANSACTIONAL_REGION_COLLECTION_IS_BEING_USED_IN_A_TRANSACTION
                .toLocalizedString(this.topRegion.getTXState().getTransactionId()));
      }
    */
    }
  }

  @Override
  public Iterator<Object> iterator() {
    checkTX();
    TXState txState = null;
    if (this.myTX != null) {
      txState = this.myTX.getLocalTXState();
    }
    return new EntriesIterator(this.topRegion, this.recursive, this.iterType,
        txState, false, this.skipTxCheckInIteration, this.keepSerialized,
        this.allowTombstones, this.iterType != IteratorType.KEYS);
  }

  static final class EntriesIterator implements Iterator<Object> {

    final List<LocalRegion> regions;

    final int numSubRegions;

    final IteratorType iterType;

    final InternalDataView view;

    final boolean keepSerialized;

    final boolean allowTombstones;

    final TXState myTX;

    final boolean forUpdate;

    final boolean skipTXCheckInIteration;

    final boolean includeValues;

    int regionsIndex;

    LocalRegion currRgn;

    // keep track of look-ahead on hasNext() call, used to filter out null
    // values
    Object nextElem; 

    Iterator<?> currItr;

    Collection<?> additionalKeysFromView;

    /** reusable KeyInfo */
    protected final KeyInfo keyInfo = new KeyInfo(null, null, null);

    @SuppressWarnings("unchecked")
    protected EntriesIterator(final LocalRegion topRegion,
        final boolean recursive, final IteratorType viewType,
        final TXState txState, final boolean forUpdate,
        final boolean skipTXCheckInIteration, final boolean keepSerialized,
        final boolean allowTombstones, final boolean includeValues) {
      if (recursive) {
        // FIFO queue of regions
        this.regions = new ArrayList<LocalRegion>(topRegion.subregions(true));
        this.numSubRegions = this.regions.size();
      }
      else {
        this.regions = null;
        this.numSubRegions = 0;
      }
      // for the LR/DR iterators, the calls for TX entries are never remoted so
      // we are interested only in local TXState if available
      this.myTX = txState;
      this.forUpdate = forUpdate;
      this.skipTXCheckInIteration = skipTXCheckInIteration;
      this.view = topRegion.getDataView(txState);
      this.keepSerialized = keepSerialized;
      this.allowTombstones = allowTombstones;
      this.iterType = viewType;
      this.includeValues = includeValues;
      createIterator(topRegion);
      this.nextElem = moveNext();
    }

    public void remove() {
      throw new UnsupportedOperationException(
          LocalizedStrings.LocalRegion_THIS_ITERATOR_DOES_NOT_SUPPORT_MODIFICATION
              .toLocalizedString());
    }

    public boolean hasNext() {
      if (!this.skipTXCheckInIteration) {
        checkTX(myTX);
      }
      return (this.nextElem != null);
    }

    public Object next() {
      if (!this.skipTXCheckInIteration) {
        checkTX(myTX);
      }
      final Object result = this.nextElem;
      if (result != null) {
        this.nextElem = moveNext();
        return result;
      }
      throw new NoSuchElementException();
    }

    private Object moveNext() {
      // keep looping until:
      // we find an element and return it
      // OR we run out of elements and return null
      for (;;) {
        if (this.currItr.hasNext()) {
          final Object currKey = this.currItr.next();
          final Object result;

          this.keyInfo.setKey(currKey);
          AbstractRegionEntry re = (AbstractRegionEntry) keyInfo.getKey();
          if (re != null && re.isMarkedForEviction() && !this.forUpdate) {
            // region entry faulted in from HDFS, skip
            continue;
          }
          if (iterType == IteratorType.KEYS) {
            result = view.getKeyForIterator(this.keyInfo, this.currRgn,
                allowTombstones);
            if (result != null) {
              return result;
            }
          }
          else if (iterType == IteratorType.ENTRIES) {
            result = view.getEntryForIterator(this.keyInfo, this.currRgn,
                allowTombstones);
            if (result != null) {
              return result;
            }
          }
          else if (iterType == IteratorType.RAW_ENTRIES) {
            // assume here that the raw entry was already fetched by
            // getRegionKeysForIteration
            if (myTX != null) {
              // TX may need to lookup the local state for any updates
              result = myTX.getLocalEntry(this.currRgn, this.currRgn,
                  -1 /* not used */, (AbstractRegionEntry)keyInfo.getKey(), this.forUpdate);
              // null result indicates that entry has been deleted in TX
              if (result != null) {
                return result;
              }
            }
            else {
              return this.keyInfo.getKey();
            }
          }
          else {
            result = view.getValueForIterator(this.keyInfo, this.currRgn,
                false, this.keepSerialized, null, this.allowTombstones);
            if (result != null) {
              if (result == Token.TOMBSTONE) {
                if (allowTombstones) {
                  return result;
                }
              }
              else if (!Token.isInvalidOrRemoved(result)) { // fix for bug 34583
                return result;
              }
            }
            // key disappeared or is invalid, go on to next
          }
        }
        else if (this.additionalKeysFromView != null) {
          this.currItr = this.additionalKeysFromView.iterator();
          this.additionalKeysFromView = null;
        }
        else if (this.regionsIndex < this.numSubRegions) {
          // advance to next region
          createIterator(this.regions.get(this.regionsIndex));
          ++this.regionsIndex;
        }
        else {
          return null;
        }
      }
    }

    private void createIterator(final LocalRegion rgn) {
      // TX iterates over KEYS.
      // NonTX iterates over RegionEntry instances
      this.currRgn = rgn;
      this.currItr = view.getRegionKeysForIteration(rgn, this.includeValues);
      this.additionalKeysFromView = view.getAdditionalKeysForIterator(rgn);
    }
  }

  @Override
  public int size() {
    checkTX();
    if (this.iterType == IteratorType.VALUES) {
      // if this is a values-view, then we have to filter out nulls to
      // determine the correct size
      int s = 0;
      for (Iterator<Object> itr = iterator(); itr.hasNext(); itr.next(), ++s)
        ;
      return s;
    }
    else if (this.recursive) {
      return this.topRegion.allEntriesSize(this.myTX);
    }
    else {
      return this.topRegion.entryCount(this.myTX);
    }
  }

  @Override
  public Object[] toArray() {
    return toArray(null);
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object[] toArray(final Object[] array) {
    final ArrayList<Object> temp = new ArrayList<Object>(this.size());
    final Iterator<Object> iter = this.iterator();
    while (iter.hasNext()) {
      temp.add(iter.next());
    }
    if (array == null) {
      return temp.toArray();
    }
    else {
      return temp.toArray(array);
    }
  }

  public void setKeepSerialized(boolean keepSerialized) {
    this.keepSerialized = keepSerialized;
  }

  public boolean isKeepSerialized() {
    return this.keepSerialized;
  }
}
