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

import com.gemstone.gemfire.cache.CacheStatistics;
import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.cache.IllegalTransactionStateException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.StatisticsDisabledException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;

/** ******************* Class Entry ***************************************** */

/**
 * Note: if changing to non-final, then change the getClass() comparison in
 * GemFireContainer.PREntriesFullIterator.extractRowLocationFromEntry
 */
public final class TXEntry implements Region.Entry<Object, Object>, TXEntryId {

  private final LocalRegion localRegion;

  private final LocalRegion dataRegion;

  private final Object key;

  private final TXEntryState txes;

  private final TXState myTX;

  /**
   * Create an Entry given a key. The returned Entry may or may not be
   * destroyed.
   */
  TXEntry(final LocalRegion localRegion, final LocalRegion dataRegion,
      final Object key, final TXEntryState tx, final TXState txState) {
    this.localRegion = localRegion;
    this.dataRegion = dataRegion;
    this.key = key;
    this.txes = tx;
    this.myTX = txState;

    //Assert that these contructors are invoked only 
    // via factory path. I am not able to make them private
    // because GemFireXDTxEntry needs extending TxEntry
    /*LogWriter logger = localRegion.getCache().getLogger();
    if(logger.fineEnabled()) {
      StackTraceElement[] traces =Thread.currentThread().getStackTrace();
      //The third element should be the factory one
      StackTraceElement trace = traces[2];
      Assert.assertTrue(TxEntryFactory.class.isAssignableFrom(trace.getClass()));
      Assert.assertTrue(trace.getMethodName().equals("createEntry"));
    }*/
  }

  public boolean isLocal() {
    return true;
  }

  protected void checkTX() {
    // Protect against the case where this instance was handed to a different
    // thread w/ or w/o a transaction
    // Protect against the case where the transaction associated with this entry
    // is not in progress
    // [sumedh] avoiding checking for LocalRegion's current TX since it involves
    // an expensive ThreadLocal lookup and it is okay to live with switching to
    // different thread as long as original transaction is alive
    //if (!this.myTX.isInProgressAndSameAs(this.localRegion.getTXState())) {
    if (!this.myTX.isInProgress()) {
      throw new IllegalTransactionStateException(LocalizedStrings
          .LocalRegion_REGIONENTRY_WAS_CREATED_WITH_TRANSACTION_THAT_IS_NO_LONGER_ACTIVE
              .toLocalizedString(this.myTX.getTransactionId()));
    }
  }

  public boolean isDestroyed() {
    checkTX();
    return !this.txes.existsLocally();
  }

  public Object getKey() {
    checkEntryDestroyed();
    return this.key;
  }

  @Unretained
  public Object getValue() {
    checkTX();
    @Unretained final Object value = this.myTX.getTXValue(this.txes, this.localRegion,
        this.dataRegion, false, false);
    if (value == null) {
      throw new EntryDestroyedException(this.key.toString());
    }
    else if (Token.isInvalid(value)) {
      return null;
    }
    return value;
  }

  public LocalRegion getRegion() {
    checkEntryDestroyed();
    return this.localRegion;
  }

  public final TXEntryState getTXEntryState() {
    return this.txes;
  }

  public CacheStatistics getStatistics() {
    // prefer entry destroyed exception over statistics disabled exception
    checkEntryDestroyed();
    checkTX();
    if (!this.localRegion.statisticsEnabled) {
      throw new StatisticsDisabledException(
          LocalizedStrings.LocalRegion_STATISTICS_DISABLED_FOR_REGION_0
              .toLocalizedString(this.localRegion.getFullPath()));
    }
    // On a TXEntry stats are non-existent so return a dummy impl
    return new CacheStatistics() {
      public long getLastModifiedTime() {
        return (getRegion() != null) ? ((LocalRegion) getRegion())
            .cacheTimeMillis() : System.currentTimeMillis();
      }

      public long getLastAccessedTime() {
        return (getRegion() != null) ? ((LocalRegion) getRegion())
            .cacheTimeMillis() : System.currentTimeMillis();
      }

      public long getMissCount() {
        return 0;
      }

      public long getHitCount() {
        return 0;
      }

      public float getHitRatio() {
        return 0;
      }

      public void resetCounts() {
      }
    };
  }

  public Object getUserAttribute() {
    throw new UnsupportedOperationException(
        LocalizedStrings.TXEntry_UA_NOT_SUPPORTED.toLocalizedString());
  }

  public Object setUserAttribute(Object value) {
    throw new UnsupportedOperationException(
        LocalizedStrings.TXEntry_UA_NOT_SUPPORTED.toLocalizedString());
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof TXEntry)) {
      return false;
    }
    final TXEntry txe = (TXEntry)obj;
    return this.txes == txe.txes;
  }

  @Override
  public int hashCode() {
    return this.key.hashCode() ^ this.localRegion.hashCode();
  }

  @Override
  public String toString() {
    return "key=" + this.key + ";pendingValue=" + this.txes.getPendingValue();
  }

  ////////////////// Private Methods
  // /////////////////////////////////////////

  /*
   * throws CacheClosedException or EntryDestroyedException if this entry is
   * destroyed.
   */
  private void checkEntryDestroyed() {
    if (isDestroyed()) {
      throw new EntryDestroyedException(this.key.toString());
    }
  }

  /**
   * @since 5.0
   */
  public Object setValue(Object value) {
    return this.localRegion.put(this.getKey(), value);
  }

  /**
   * @see TXEntryId#getTXId()
   */
  public TXId getTXId() {
    return this.myTX.getTransactionId();
  }
}
