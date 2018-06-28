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
package com.pivotal.gemfirexd.internal.engine.access.operations;

import java.io.IOException;

import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.DiskStoreFactory;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.LimitObjectInput;
import com.pivotal.gemfirexd.internal.iapi.services.uuid.UUIDFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.GfxdDiskStoreDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Compensation;
import com.pivotal.gemfirexd.internal.iapi.store.raw.Transaction;
import com.pivotal.gemfirexd.internal.iapi.store.raw.log.LogInstant;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * @author ymahajan
 */
public class DiskStoreCreateOperation extends MemOperation {

  private final DiskStoreFactory dsf;

  private final String storeName;

  private String dirPathsAndSizes;

  public DiskStoreCreateOperation(DiskStoreFactory dsf, String storeName,
      String dirPathsAndSizes) {
    super(null);
    this.dsf = dsf;
    this.storeName = storeName;
    this.dirPathsAndSizes = dirPathsAndSizes;
  }

  @Override
  public void doMe(Transaction xact, LogInstant instant, LimitObjectInput in)
      throws StandardException, IOException {
    final GemFireTransaction tc = (GemFireTransaction)xact;
    final LanguageConnectionContext lcc = tc.getLanguageConnectionContext();
    DataDictionary dd = lcc.getDataDictionary();
    dd.startWriting(lcc);
    // Check to see if this diskstore already exists in the cache
    GemFireCacheImpl cache = Misc.getGemFireCache();
    if (cache.findDiskStore(storeName) != null) {
      throw StandardException.newException(
          SQLState.LANG_OBJECT_ALREADY_EXISTS, "DISKSTORE", storeName);
    }
    DiskStore ds = GemFireStore.createDiskStore(dsf,
        SharedUtils.SQLToUpperCase(storeName), cache.getCancelCriterion());
    UUIDFactory factory = dd.getUUIDFactory();
    String diskStoreName = ds.getName();
    UUID id = factory.recreateUUID(diskStoreName);

    GfxdDiskStoreDescriptor dsd = new GfxdDiskStoreDescriptor(dd, id, ds,
        dirPathsAndSizes);
    dd.addDescriptor(dsd, null, DataDictionary.SYSDISKSTORES_CATALOG_NUM,
        false, tc);

    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
        "CreateDiskStoreNode: added diskstore configuration for "
            + diskStoreName + " in SYS table");
  }

  @Override
  public Compensation generateUndo(Transaction xact, LimitObjectInput in)
      throws StandardException, IOException {
    return new DiskStoreDropOperation(this.storeName);
  }
}
