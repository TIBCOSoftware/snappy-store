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

/**
 * An observer interface for transaction processing (currently used mainly by
 * tests).
 * 
 * @author kneeraj, swale
 * @since 7.0
 */
public interface TransactionObserver {

  /**
   * Invoked after the transaction changes have been applied to committed state
   * (locally) but before local locks are released.
   */
  public void afterApplyChanges(TXStateProxy tx);

  /**
   * Invoked when the local locks are released after the commit is complete.
   */
  public void afterReleaseLocalLocks(TXStateProxy tx);

  /**
   * Invoked during commit processing of the current node.
   */
  public void duringIndividualCommit(TXStateProxy tx, Object callbackArg);

  /**
   * Invoked before lock upgrade of an entry during commit processing of the
   * current node.
   */
  public void beforeIndividualLockUpgradeInCommit(TXStateProxy tx,
      TXEntryState entry);

  /**
   * Invoked after phase1 commit processing of the current node.
   */
  public void afterIndividualCommitPhase1(TXStateProxy tx, Object callbackArg);

  /**
   * Invoked after commit processing of the current node.
   */
  public void afterIndividualCommit(TXStateProxy tx, Object callbackArg);

  /**
   * Invoked during rollback processing of the current node.
   */
  public void duringIndividualRollback(TXStateProxy tx, Object callbackArg);

  /**
   * Invoked after rollback processing of the current node.
   */
  public void afterIndividualRollback(TXStateProxy tx, Object callbackArg);

  /**
   * Invoked after the commit or rollback message is formed but before it is
   * sent.
   * 
   * @param rollback
   *          if this is invoked during rollback
   */
  public void beforeSend(TXStateProxy tx, boolean rollback);

  /**
   * Invoked after a commit or rollback has been sent to other nodes but before
   * applying to local cache.
   * 
   * @param rollback
   *          if this is invoked during rollback
   */
  public void afterSend(TXStateProxy tx, boolean rollback);

  public void beforePerformOp(TXStateProxy tx);
}
