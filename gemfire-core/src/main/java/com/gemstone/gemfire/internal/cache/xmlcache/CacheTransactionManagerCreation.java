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
package com.gemstone.gemfire.internal.cache.xmlcache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * Represents a {@link CacheTransactionManager} that is created declaratively.  
 *
 * @author Mitch Thomas
 *
 * @since 4.0
 */
public class CacheTransactionManagerCreation implements CacheTransactionManager {

  ///////////////////////  Instance Fields  ///////////////////////

  /** The TransactionListener instance set using the cache's CacheTransactionManager */
  private final ArrayList txListeners = new ArrayList();
  private TransactionWriter writer = null;

  ///////////////////////  Constructors  ///////////////////////
  /**
   * Creates a new <code>CacheTransactionManagerCreation</code>
   */
  public CacheTransactionManagerCreation() {
  }

  //////////////////////  Instance Methods  //////////////////////
  public TransactionListener setListener(TransactionListener newListener) {
    TransactionListener result = getListener();
    this.txListeners.clear();
    if (newListener != null) {
      this.txListeners.add(newListener);
    }
    return result;
  }
  public void initListeners(TransactionListener[] newListeners) {
    this.txListeners.clear();
    if (newListeners != null && newListeners.length > 0) {
      this.txListeners.addAll(Arrays.asList(newListeners));
    }
  }
  public void addListener(TransactionListener newListener) {
    if (!this.txListeners.contains(newListener)) {
      this.txListeners.add(newListener);
    }
  }
  public void removeListener(TransactionListener newListener) {
    this.txListeners.remove(newListener);
  }

  public TransactionListener[] getListeners() {
    TransactionListener[] result = new TransactionListener[this.txListeners.size()];
    this.txListeners.toArray(result);
    return result;
  }
  public final TransactionListener getListener() {
    if (this.txListeners.isEmpty()) {
      return null;
    } else if (this.txListeners.size() == 1) {
      return (TransactionListener)this.txListeners.get(0);
    } else {
      throw new IllegalStateException(LocalizedStrings.CacheTransactionManagerCreation_MORE_THAN_ONE_TRANSACTION_LISTENER_EXISTS.toLocalizedString());
    }
  }

  public TransactionId getTransactionId() {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_GETTING_A_TRANSACTIONID_NOT_SUPPORTED.toLocalizedString());
  }
  public void begin() {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

  public void begin(IsolationLevel isolationLevel,
      EnumSet<TransactionFlag> txFlags) {
    throw new UnsupportedOperationException(
        LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED
            .toLocalizedString());
  }

  public void commit() throws TransactionException {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }
  public void rollback() {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }
  public boolean exists() {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

  public final void setWriter(TransactionWriter writer) {
    this.writer = writer;
  }

  public TransactionWriter getWriter() {
    return writer;
  }

  public TransactionId suspend() {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

  @Override
  public void resume(TransactionId transactionId) {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

  @Override
  public boolean isSuspended(TransactionId transactionId) {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

  @Override
  public boolean tryResume(TransactionId transactionId) {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

  @Override
  public boolean tryResume(TransactionId transactionId, long time, TimeUnit unit) {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

  @Override
  public boolean exists(TransactionId transactionId) {
    throw new UnsupportedOperationException(LocalizedStrings.CacheTransactionManagerCreation_TRANSACTIONS_NOT_SUPPORTED.toLocalizedString());
  }

}
