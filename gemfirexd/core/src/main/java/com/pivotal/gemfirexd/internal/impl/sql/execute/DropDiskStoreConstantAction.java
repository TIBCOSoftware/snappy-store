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
package com.pivotal.gemfirexd.internal.impl.sql.execute;

import java.util.List;

import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.DataDictionaryImpl;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.TabInfoImpl;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

public class DropDiskStoreConstantAction extends DDLConstantAction {

  private final String diskStoreName;

  private final boolean onlyIfExists;

  DropDiskStoreConstantAction(String diskStoreName, boolean onlyIfExists) {
    this.diskStoreName = diskStoreName;
    this.onlyIfExists = onlyIfExists;
  }

  // Override the getSchemaName/getObjectName to enable
  // DDL conflation of CREATE and DROP DISKSTORE statements.
  @Override
  public final String getSchemaName() {
    // Disk stores have no schema, so return 'SYS'
    return SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME;
  }

  @Override
  public final String getTableName() {
    return CreateDiskStoreConstantAction.REGION_PREFIX_FOR_CONFLATION
        + diskStoreName;
  }

  @Override
  public final boolean isDropStatement() {
    return true;
  }

  @Override
  public void executeConstantAction(Activation activation)
      throws StandardException {
    // drop the delta store first
    if (Misc.getMemStore().isSnappyStore()) {
      executeConstantAction(diskStoreName +
          GfxdConstants.SNAPPY_DELTA_DISKSTORE_SUFFIX, onlyIfExists, activation);
    }
    // then drop the main disk store
    executeConstantAction(diskStoreName, onlyIfExists, activation);
  }

  private static void executeConstantAction(String diskStoreName,
      boolean onlyIfExists, Activation activation) throws StandardException {
    int rowsDeleted;
    // If this node is not hosting data, nothing to do
    if (!ServerGroupUtils.isDataStore()) {
      return;
    }
    // Stop user from dropping built-in diskstores
    // Diskstore in region has NULL for name, but users may try to
    // drop diskstore names from catalog
    if (diskStoreName.equals(GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME)
        || diskStoreName.equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)
        || diskStoreName.equals(GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE)) {
      // 0A000 until more appropriate sqlstate
      throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
          "Cannot DROP default diskstores");
    }
    GemFireCacheImpl cache = Misc.getGemFireCache();
    List<GemFireContainer> containers = Misc.getMemStore().getAllContainers();
    for (GemFireContainer container : containers) {
      if (container.getRegion() != null && container.isApplicationTable()) {
        // Application Tables on default diskstore have null diskstore name
        String regionDiskStoreName = container.getRegionAttributes()
            .getDiskStoreName();
        if (regionDiskStoreName != null
            && regionDiskStoreName.equalsIgnoreCase(diskStoreName)) {
          throw StandardException.newException(
              SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT, "DROP", "DiskStore "
                  + diskStoreName, "table", container.getQualifiedTableName());
        }
      }
    }
    // Also check GW and ASYNC objects to see if any are using this diskstore
    for (GatewaySender sender : cache.getGatewaySenders()) {
      if (sender.getDiskStoreName().equalsIgnoreCase(diskStoreName)) {
        throw StandardException.newException(
            SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT, "DROP", "DiskStore "
                + diskStoreName, "gatewaysender", sender.getId());
      }
    }
    for (AsyncEventQueue asyncQueue : cache.getAsyncEventQueues()) {
      if (asyncQueue.getDiskStoreName().equalsIgnoreCase(diskStoreName)) {
        throw StandardException.newException(
            SQLState.LANG_PROVIDER_HAS_DEPENDENT_OBJECT, "DROP", "DiskStore "
                + diskStoreName, "asynceventqueue", asyncQueue.getId());
      }
    }

    // OK, we're good to go - drop the object from the catalog first
    // and then drop the gemfire object second
    LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
    DataDictionaryImpl dd = (DataDictionaryImpl)lcc.getDataDictionary();
    TransactionController tc = lcc.getTransactionExecute();
    dd.startWriting(lcc);
    ExecIndexRow keyRow = dd.getExecutionFactory().getIndexableRow(1);
    keyRow.setColumn(1, new SQLVarchar(diskStoreName));

    TabInfoImpl ti = dd
        .getNonCoreTI(DataDictionaryImpl.SYSDISKSTORES_CATALOG_NUM);
    rowsDeleted = ti.deleteRow(tc, keyRow, 0);
    // If no row deleted from catalog, it's an error unless IF EXISTS specified
    if (rowsDeleted == 0) {
      if (onlyIfExists) {
        return;
      } else {
        // The diskstore wasn't in the catalog in the first place
        // Throw object-not-found exception
        throw StandardException.newException(
            SQLState.LANG_OBJECT_DOES_NOT_EXIST, "DROP DISKSTORE", diskStoreName);
      }
    }
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
        "DropDiskStore:: removed DiskStore " + diskStoreName + " from SYS table");

    DiskStoreImpl store = cache.findDiskStore(diskStoreName);
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
        "DropDiskStore :: found DiskStore " + store);
    cache.removeDiskStore(store);
    store.destroy();
  }

  // OBJECT METHODS

  @Override
  public String toString() {
    return constructToString("DROP DISKSTORE ", diskStoreName);
  }

  @Override
  public boolean isCancellable() {
    return false;
  }
}
