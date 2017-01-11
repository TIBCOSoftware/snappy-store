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

package com.pivotal.gemfirexd.internal.engine.access;

import java.util.Properties;

import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.DynamicCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.TransactionManager;
import com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

/**
 * Interface that extends {@link Conglomerate} with some common functionality
 * for GemFireXD.
 * 
 * @author swale
 */
public interface MemConglomerate extends Conglomerate, StaticCompiledOpenConglomInfo {

  // Unique IDs for each conglomerate type below. Returned by
  // MemConglomerate#getType, MemScanController#getType,
  // MemConglomerateController#getType to enable reuse of these by
  // GemFireTransaction.
  // Keep these values from 0 to NUMCONGLOMTYPES-1

  public static final int HEAP = 0;

  public static final int HASH1INDEX = 1;

  public static final int GLOBALHASHINDEX = 2;

  public static final int SORTEDMAP2INDEX = 3;

  public static final int NUMCOMGLOMTYPES = 4;

  /**
   * Allocate memory and initialize a newly created conglomerate.
   * <p>
   * Initialize a heap conglomerate. This method is called from the conglomerate
   * factory to allocate memory, initialize etc. for a newly created instance of
   * a conglomerate.
   * <p>
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  void create(GemFireTransaction tran, int segmentId, long containerId,
      DataValueDescriptor[] template, ColumnOrdering[] columnOrder,
      int[] collationIds, Properties conglomProperties, int tmpFlag)
      throws StandardException;

  /**
   * Open a conglomerate controller.
   * <p>
   * 
   * @return The open MemScanController.
   * 
   * @param tran
   *          The {@link GemFireTransaction} to associate all ops on cc with.
   * @param openMode
   *          A bit mask of TransactionController.MODE_* bits, indicating info
   *          about the open.
   * @param lockLevel
   *          Either TransactionController.MODE_TABLE or
   *          TransactionController.MODE_RECORD, as passed into the
   *          openConglomerate() call.
   * @param locking
   *          The LockingPolicy to use to open the conglomerate.
   * 
   * @exception StandardException
   *              Standard exception policy.
   **/
  MemConglomerateController open(GemFireTransaction tran, int openMode,
      int lockLevel, LockingPolicy locking) throws StandardException;

  /**
   * Open a scan controller.
   * 
   * @exception StandardException
   *              Standard exception policy.
   **/
  MemScanController openScan(TransactionManager xact_manager,
      Transaction rawtran, boolean hold, int open_mode, int lock_level,
      LockingPolicy locking_policy, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier qualifier[][],
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      StaticCompiledOpenConglomInfo static_info,
      DynamicCompiledOpenConglomInfo dynamic_info) throws StandardException;

  /**
   * Open a scan controller with local function context
   * {@linkplain FunctionContext} provided by GemFire function service
   * {@linkplain com.gemstone.gemfire.cache.execute.FunctionService}.
   * 
   * @throws StandardException
   */
  MemScanController openScan(TransactionManager xact_manager,
      Transaction rawtran, boolean hold, int open_mode, int lock_level,
      LockingPolicy locking_policy, int isolation_level,
      FormatableBitSet scanColumnList, DataValueDescriptor[] startKeyValue,
      int startSearchOperator, Qualifier qualifier[][],
      DataValueDescriptor[] stopKeyValue, int stopSearchOperator,
      StaticCompiledOpenConglomInfo static_info,
      DynamicCompiledOpenConglomInfo dynamic_info, Activation act)
      throws StandardException;

  /**
   * Online compress table.
   * 
   * Returns a ScanManager which can be used to move rows around in a table,
   * creating a block of free pages at the end of the table. The process of
   * executing the scan will move rows from the end of the table toward the
   * beginning. The GroupFetchScanController will return the old row location,
   * the new row location, and the actual data of any row moved. Note that this
   * scan only returns moved rows, not an entire set of rows, the scan is
   * designed specifically to be used by either explicit user call of the
   * ONLINE_COMPRESS_TABLE() procedure, or internal background calls to
   * compress the table.
   * 
   * The old and new row locations are returned so that the caller can update
   * any indexes necessary.
   * 
   * This scan always returns all collumns of the row.
   * 
   * All inputs work exactly as in openScan(). The return is a
   * GroupFetchScanController, which only allows fetches of groups of rows from
   * the conglomerate.
   * <p>
   * Note that all Conglomerates may not implement openCompressScan(), currently
   * only the Heap conglomerate implements this scan.
   * <p>
   * Note that this is currently not implemented for GemFireXD
   * 
   * @return The MemScanController to be used to fetch the rows.
   * 
   * @param hold
   *          see openScan()
   * @param open_mode
   *          see openScan()
   * @param lock_level
   *          see openScan()
   * @param isolation_level
   *          see openScan()
   * 
   * @exception StandardException
   *              Standard exception policy.
   **/
  MemScanController defragmentConglomerate(TransactionManager xact_manager,
      Transaction rawtran, boolean hold, int open_mode, int lock_level,
      LockingPolicy locking_policy, int isolation_level)
      throws StandardException;

  /**
   * Return true if this Conglomerate requires a {@link GemFireContainer} to
   * store its data.
   */
  boolean requiresContainer();

  /** Get the {@link GemFireContainer} for this {@link Conglomerate}. */
  GemFireContainer getGemFireContainer();

  /** Set the {@link GemFireContainer} for this {@link Conglomerate}. */
  void setGemFireContainer(GemFireContainer container);

  /**
   * Open the underlying {@link GemFireContainer} with the given locking policy
   * and mode.
   */
  boolean openContainer(GemFireTransaction tran, int openMode, int lockLevel,
      LockingPolicy locking) throws StandardException;

  /**
   * Get the type of this conglomerate: one of {@link #HEAP},
   * {@link #HASH1INDEX}, {@link #SORTEDMAP2INDEX}, {@link #GLOBALHASHINDEX}.
   */
  int getType();
}
