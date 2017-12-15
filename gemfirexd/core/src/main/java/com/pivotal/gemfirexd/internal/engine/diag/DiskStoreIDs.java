/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.diag;

import java.io.File;
import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Iterator;

import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;

/**
 * Display the {@link DiskStoreID}s with names and directories.
 */
public class DiskStoreIDs extends GfxdVTITemplate {

  private Iterator<DiskStoreImpl> diskStores;
  private DiskStoreImpl currentDiskStore;

  @Override
  public boolean next() throws SQLException {

    if (this.diskStores == null) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null && !cache.isClosed()) {
        this.diskStores = cache.listDiskStoresIncludingRegionOwned().iterator();
      }
    }
    if (this.diskStores != null && this.diskStores.hasNext()) {
      this.currentDiskStore = this.diskStores.next();
      this.wasNull = false;
      return true;
    } else {
      this.currentDiskStore = null;
      return false;
    }
  }

  @Override
  protected Object getObjectForColumn(int columnNumber) throws SQLException {
    final ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    final String columnName = desc.getName();
    final String res;
    if (MEMBERID.equals(columnName)) {
      res = this.currentDiskStore.getCache().getMyId().getId();
    } else if (NAME.equals(columnName)) {
      res = this.currentDiskStore.getName();
    } else if (ID.equals(columnName)) {
      res = this.currentDiskStore.getDiskStoreUUID().toString();
    } else if (DIRS.equals(columnName)) {
      StringBuilder dirs = new StringBuilder();
      for (File dir : this.currentDiskStore.getDiskDirs()) {
        if (dirs.length() > 0) {
          dirs.append(',');
        }
        String dirPath;
        try {
          dirPath = dir.getCanonicalPath();
        } catch (IOException ioe) {
          dirPath = dir.getAbsolutePath();
        }
        dirs.append(dirPath);
      }
      res = dirs.toString();
    } else {
      res = null;
    }
    return res;
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  /** Metadata */

  public static final String MEMBERID = "MEMBERID";
  public static final String NAME = "NAME";
  public static final String ID = "ID";
  public static final String DIRS = "DIRS";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(MEMBERID, Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(NAME, Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(ID, Types.CHAR, false, 36),
      EmbedResultSetMetaData.getResultColumnDescriptor(DIRS, Types.VARCHAR,
          false, Limits.DB2_VARCHAR_MAXWIDTH) };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(columnInfo);
}
