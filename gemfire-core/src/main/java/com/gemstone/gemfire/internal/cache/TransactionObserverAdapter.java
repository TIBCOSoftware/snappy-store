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

import java.io.Serializable;

/**
 * Adapter for {@link TransactionObserver}.
 * 
 * @author swale
 * @since 7.0
 */
public class TransactionObserverAdapter implements TransactionObserver,
    Serializable {

  private static final long serialVersionUID = 8631732877233085453L;

  public void afterApplyChanges(TXStateProxy tx) {
  }

  public void afterReleaseLocalLocks(TXStateProxy tx) {
  }

  public void duringIndividualCommit(TXStateProxy tx, Object callbackArg) {
  }

  public void beforeIndividualLockUpgradeInCommit(TXStateProxy tx,
      TXEntryState entry) {
  }

  public void afterIndividualCommitPhase1(TXStateProxy tx, Object callbackArg) {
  }

  public void afterIndividualCommit(TXStateProxy tx, Object callbackArg) {
  }

  public void duringIndividualRollback(TXStateProxy tx, Object callbackArg) {
  }

  public void afterIndividualRollback(TXStateProxy tx, Object callbackArg) {
  }

  public void beforeSend(TXStateProxy tx, boolean rollback) {
  }

  public void afterSend(TXStateProxy tx, boolean rollback) {
  }

  public void beforePerformOp(TXStateProxy tx){
  }
}
