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

package com.gemstone.gemfire.cache;

import java.util.EnumSet;


/**
 * Flags that can be combined and passed as an {@link EnumSet} to
 * {@link CacheTransactionManager} to define additional properties for the
 * transaction.
 *
 * Properties marked as [Transient] will be sent across messages and set on the
 * remote node transaction.
 * 
 * @author swale
 * @since 7.0
 */
public enum TransactionFlag {

  /**
   * use waiting mode for transaction conflicts that will wait for other
   * transactions to finish instead of eagerly throwing conflict exception
   */
  WAITING_MODE,

  /**
   * disable batching for operations on a data store node that is designed to
   * minimize messaging; instead send each operation to all recipients
   * immediately (among other things this will always ensure immediate conflict
   * detection instead of somewhat lazy one in the default batching behaviour).
   * 
   * Note that function execution on remote nodes always uses batching
   * regardless of this setting to avoid sending operation and locking
   * information with each operation, rather sends it only once at the end of
   * the function execution.
   */
  DISABLE_BATCHING,

  /**
   * wait on 2nd phase commit to be complete on all the nodes; the default is to
   * do the 2nd phase commit in background when possible where other threads may
   * not see committed data for sometime (the thread that initializes the commit
   * will always see committed data)
   */
  SYNC_COMMITS,

  /**
   * [Transient] disable column table delta rollover for the current operation
   */
  DISABLE_DELTA_ROLLOVER {
    @Override
    public int bitmask() {
      return 0x1;
    }
  },

  ;

  /**
   * The bitmask to use for transient transaction flags.
   */
  public int bitmask() {
    return 0x0;
  }
}
