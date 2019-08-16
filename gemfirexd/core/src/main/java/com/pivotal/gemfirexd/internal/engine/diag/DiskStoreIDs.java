/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
import java.io.Serializable;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplateNoAllNodesRoute;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;

/**
 * Display the {@link DiskStoreID}s with names and directories.
 */
public class DiskStoreIDs extends GfxdVTITemplate
    implements GfxdVTITemplateNoAllNodesRoute {

  static final class DiskStoreInformation implements Serializable {

    private static final long serialVersionUID = 2867776725673751094L;

    final String memberId;
    final String name;
    final String id;
    final String dirs;

    DiskStoreInformation(String memberId, String name, String id, String dirs) {
      this.memberId = memberId;
      this.name = name;
      this.id = id;
      this.dirs = dirs;
    }
  }

  private Iterator<DiskStoreInformation> diskStores;
  private DiskStoreInformation currentDiskStore;

  public static final class DiskStoreIDFunction implements Function {

    private static final long serialVersionUID = -3904345941647806334L;

    public static final String ID = "DiskStoreIDs";

    @Override
    public boolean hasResult() {
      return true;
    }

    @Override
    public void execute(FunctionContext context) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null && !cache.isClosed()) {
        Collection<DiskStoreImpl> diskStores =
            cache.listDiskStoresIncludingRegionOwned();
        ArrayList<DiskStoreInformation> diskStoreInfos = new ArrayList<>(
            diskStores.size());
        for (DiskStoreImpl ds : diskStores) {
          StringBuilder dirs = new StringBuilder();
          for (File dir : ds.getDiskDirs()) {
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
          diskStoreInfos.add(new DiskStoreInformation(cache.getMyId().getId(),
              ds.getName(), ds.getDiskStoreUUID().toString(), dirs.toString()));
        }
        context.getResultSender().lastResult(diskStoreInfos);
      } else {
        context.getResultSender().lastResult(new ArrayList<>(0));
      }
    }

    @Override
    public String getId() {
      return ID;
    }

    @Override
    public boolean optimizeForWrite() {
      return false;
    }

    @Override
    public boolean isHA() {
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean next() throws SQLException {

    if (this.diskStores == null) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null && !cache.isClosed()) {
        ArrayList<DiskStoreInformation> allDiskStores = new ArrayList<>();
        for (ArrayList<DiskStoreInformation> diskStores :
            (ArrayList<ArrayList<DiskStoreInformation>>)FunctionService
                .onMembers(GfxdMessage.getAllDSMembers()).execute(
                    DiskStoreIDFunction.ID).getResult()) {
          allDiskStores.addAll(diskStores);
        }
        this.diskStores = allDiskStores.iterator();
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
      res = this.currentDiskStore.memberId;
    } else if (NAME.equals(columnName)) {
      res = this.currentDiskStore.name;
    } else if (ID.equals(columnName)) {
      res = this.currentDiskStore.id;
    } else if (DIRS.equals(columnName)) {
      res = this.currentDiskStore.dirs;
    } else {
      res = null;
    }
    return res;
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  /**
   * Metadata
   */

  @SuppressWarnings("WeakerAccess")
  public static final String MEMBERID = "MEMBERID";
  public static final String NAME = "NAME";
  public static final String ID = "ID";
  @SuppressWarnings("WeakerAccess")
  public static final String DIRS = "DIRS";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(MEMBERID, Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(NAME, Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(ID, Types.CHAR, false, 36),
      EmbedResultSetMetaData.getResultColumnDescriptor(DIRS, Types.VARCHAR,
          false, Limits.DB2_VARCHAR_MAXWIDTH) };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(columnInfo);
}
