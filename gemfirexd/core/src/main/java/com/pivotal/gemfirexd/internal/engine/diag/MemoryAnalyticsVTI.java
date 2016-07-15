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
package com.pivotal.gemfirexd.internal.engine.diag;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.NanoTimer;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.StoreStatistics;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.sql.execute.VTIResultSet;
import com.pivotal.gemfirexd.internal.vti.IFastPath;
import com.pivotal.gemfirexd.internal.vti.IQualifyable;
import com.pivotal.gemfirexd.internal.vti.VTIEnvironment;
import com.pivotal.gemfirexd.tools.sizer.ObjectSizer;

/**
 * Table that collates per region memory break ups using ObjectSizer from data
 * nodes
 * 
 * @author soubhikc
 */
public class MemoryAnalyticsVTI extends GfxdVTITemplate implements
    IQualifyable, IFastPath,
    com.pivotal.gemfirexd.tools.sizer.ObjectSizer.PreQualifier {
  
  public static boolean TEST_MODE = false;

  private Iterator<GemFireContainer> containerList;
  
  private final String host;
  
  private final String member;

  private Iterator<Map.Entry<String, Object[]>> memberResults;

  private Map.Entry<String, Object[]> currentMemoryReport;

  private ArrayList<DataValueDescriptor[]> partialResult; 
  
  private static final String keyDelimiter = "!";

  private final boolean isForInternalUse;
  
  private final StoreStatistics stats;
  
  private Qualifier[][] qualifiers;
  
  private long beginTime=-1;

  private ObjectSizer sizer;

  /**
   * default constructor used by VTIResultSet.
   */
  public MemoryAnalyticsVTI() {
    this(false);
  }

  public MemoryAnalyticsVTI(boolean internalOnly) {
    isForInternalUse = internalOnly;
    stats = Misc.getMemStore().getStoreStatistics();
    host = Misc.getMyId().getHost();
    member = Misc.getMyId().getId();
    sizer = ObjectSizer.getInstance(this.isForInternalUse);
    if (TEST_MODE) {
      partialResult = new ArrayList<DataValueDescriptor[]>();
    }
  }

  public void setContainer(GemFireContainer container){
    List<GemFireContainer> list = new ArrayList<GemFireContainer>();
    list.add(container);
    containerList = list.iterator();
    beginTime = -1;
  }

  @Override
  public boolean next() throws SQLException {

    boolean hasMoreContainers = false;
    try {
      
      final VMKind vmKind = GemFireXDUtils.getMyVMKind();
      if (!vmKind.isStore()) {
        return false;
      }
      
      if (beginTime == -1) {
        beginTime = NanoTimer.getTime();
        sizer.initialize(isForInternalUse, keyDelimiter);
        sizer.setQueryHints(compileTimeConstants);
      }
      
      if (containerList == null) {
        ArrayList<GemFireContainer> targetContainers = new ArrayList<GemFireContainer>();
        ObjectSizer.getTargetContainers(targetContainers);
        containerList = targetContainers.iterator();
      }

      LinkedHashMap<String, Object[]> retVal = null; 

      do {
        // always check, so that finally block don't unitinitiaze the sizer,
        // while
        // we consume rows generated from previous sizer output.
        hasMoreContainers = containerList.hasNext();

        if (memberResults != null && memberResults.hasNext()) {
          this.currentMemoryReport = memberResults.next();
          return true;
        }

        if (!hasMoreContainers) {
          containerList = null;
          beginTime = -1;
          return false;
        }

        retVal = sizer.size(containerList.next(), this);

      } while (retVal == null);
      
      sizer.logSizes(retVal);

      final LinkedHashMap<String, Object[]> result = new LinkedHashMap<String, Object[]>();
      for (Map.Entry<String, Object[]> e : retVal.entrySet()) {
        result.put(member + keyDelimiter
            + host + keyDelimiter + e.getKey(),
            e.getValue());
      }

      this.memberResults = result.entrySet().iterator();
      if (this.memberResults.hasNext()) {
        this.currentMemoryReport = memberResults.next();
        return true;
      }

    } catch (Exception e) {
      hasMoreContainers = false;
      throw GemFireXDRuntimeException.newRuntimeException(
          "Exception in MemoryAnalyticsVTI#next", e);
    } catch (Throwable t) {
      hasMoreContainers = false;
      throw Util.javaException(t);
    } finally {
      if (!hasMoreContainers) {
        sizer.done();
      }
    }

    stats.collectMemoryAnalyticsStats(NanoTimer.getTime() - beginTime, isForInternalUse);
    return false;
  }

  @Override
  protected Object getObjectForColumn(int columnNumber) throws SQLException {
    final ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    final String columnName = desc.getName();
    String[] keyAr = this.currentMemoryReport.getKey().split(keyDelimiter);
    final long[] currVal = (long[]) this.currentMemoryReport.getValue()[0];
    final Object res;
    if (TABLE_NAME.equals(columnName)) {
      res = keyAr[2];
    }
    else if (INDEX_NAME.equals(columnName)) {
      res = (keyAr.length > 3 && keyAr[3].length() > 0) ? keyAr[3] : null;
    }
    else if (INDEX_TYPE.equals(columnName)) {
      res = (keyAr.length > 4 && keyAr[4].length() > 0) ? keyAr[4] : null;
    }
    else if (QUEUE_NAME.equals(columnName)) {
      res = (keyAr.length > 5) ? keyAr[5] : null;
    }
    else if (QUEUE_TYPE.equals(columnName)) {
      res = (keyAr.length > 6) ? keyAr[6] : null;
    }
    else if (MEMBERID.equals(columnName)) {
      // res = currentMember.getId();
      res = keyAr[0];
    }
    else if (HOST.equals(columnName)) {
      // res = currentMember.getHost();
      res = keyAr[1];
    }
    else if (CONSTANT_OVERHEAD.equals(columnName)) {
      res = formatSizeValue(currVal[0],
          new StringBuilder());
    }
    else if (ENTRY_SIZE.equals(columnName)) {
      res = formatSizeValue(currVal[1],
          new StringBuilder());
    }
    else if (KEY_SIZE.equals(columnName)) {
      res = formatSizeValue(currVal[2],
          new StringBuilder());
    }
    else if (VALUE_SIZE.equals(columnName)) {
      res = formatSizeValue(currVal[3],
          new StringBuilder());
    }
    else if (VALUE_SIZE_OFFHEAP.equals(columnName)) {
      res = formatSizeValue(currVal[4],
          new StringBuilder());
    }
    else if (TOTAL_SIZE.equals(columnName)) {
      long totalsize = currVal[0] + currVal[1] + currVal[2] + currVal[3]
          + currVal[4];
      res = formatSizeValue(totalsize, new StringBuilder());
    }
    else if (NUM_ROWS.equals(columnName)) {
      if (currVal.length >9 && currVal[9] > 0 ) {
        res = currVal[9];
      } else {
        res = currVal[5];
      }
    }
    else if (NUM_KEYS_MEMORY.equals(columnName)) {
      res = currVal[6];
    }
    else if (NUM_VALUES_MEMORY.equals(columnName)) {
      res = currVal[7];
    }
    else if (NUM_VALUES_OFFHEAP.equals(columnName)) {
      res = currVal[8];
    }
    else if (MEMORY_REPORT.equals(columnName)) {

      if (this.currentMemoryReport.getValue().length > 1) {
        res = this.currentMemoryReport.getValue()[1];
      }
      else {
        res = "-";
      }

      Misc.getGemFireCache().getLogger().info("Memory Report : " + res);
    }
    else {
      throw new GemFireXDRuntimeException("unexpected columnName " + columnName);
    }

    //assert res != null: "null returning creates IndexOutOfBound in client driver";
    return res instanceof StringBuilder ? res.toString() : res;
  }

  public Float /*StringBuilder*/ formatSizeValue(long value, StringBuilder sb) {
    return Float.valueOf(value / 1000f); // convert into KB always
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  /** Metadata */

  public static final String TABLE_NAME = "TABLE_NAME";

  public static final String INDEX_NAME = "INDEX_NAME";

  public static final String INDEX_TYPE = "INDEX_TYPE";

  public static final String QUEUE_NAME = "QUEUE_NAME";

  public static final String QUEUE_TYPE = "QUEUE_TYPE";

  public static final String MEMBERID = "ID";

  public static final String HOST = "HOST";

  public static final String CONSTANT_OVERHEAD = "CONSTANT_OVERHEAD";

  public static final String ENTRY_SIZE = "ENTRY_SIZE";

  public static final String KEY_SIZE = "KEY_SIZE";

  public static final String VALUE_SIZE = "VALUE_SIZE";
  
  public static final String VALUE_SIZE_OFFHEAP = "VALUE_SIZE_OFFHEAP";

  public static final String NUM_ROWS = "NUM_ROWS";

  public static final String NUM_KEYS_MEMORY = "NUM_KEYS_IN_MEMORY";

  public static final String NUM_VALUES_MEMORY = "NUM_VALUES_IN_MEMORY";
  
  public static final String NUM_VALUES_OFFHEAP = "NUM_VALUES_IN_OFFHEAP";

  public static final String TOTAL_SIZE = "TOTAL_SIZE";

  public static final String MEMORY_REPORT = "MEMORY";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(TABLE_NAME,
          Types.VARCHAR, false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(INDEX_NAME,
          Types.VARCHAR, true, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(INDEX_TYPE,
          Types.VARCHAR, true, 32),
      EmbedResultSetMetaData.getResultColumnDescriptor(QUEUE_NAME,
          Types.VARCHAR, true, 256),
      EmbedResultSetMetaData.getResultColumnDescriptor(QUEUE_TYPE,
          Types.VARCHAR, true, 32),
      EmbedResultSetMetaData.getResultColumnDescriptor(MEMBERID, Types.VARCHAR,
          false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(HOST, Types.VARCHAR,
          false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(CONSTANT_OVERHEAD,
          Types.REAL, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(ENTRY_SIZE, Types.REAL,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(KEY_SIZE, Types.REAL,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(VALUE_SIZE, Types.REAL,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(VALUE_SIZE_OFFHEAP, Types.REAL,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(TOTAL_SIZE, Types.REAL,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(NUM_ROWS, Types.BIGINT,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(NUM_KEYS_MEMORY, Types.BIGINT,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(NUM_VALUES_MEMORY, Types.BIGINT,
          false),
      EmbedResultSetMetaData.getResultColumnDescriptor(NUM_VALUES_OFFHEAP, Types.BIGINT,
           false),
      EmbedResultSetMetaData.getResultColumnDescriptor(MEMORY_REPORT,
          Types.LONGVARCHAR, false), };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);

  @Override
  public double getEstimatedRowCount(VTIEnvironment vtiEnvironment)
      throws SQLException {
    ArrayList<GemFireContainer> targetContainers = new ArrayList<GemFireContainer>();
    ObjectSizer.getTargetContainers(targetContainers);
    return targetContainers.size(); 
  }
  
  @Override
  public double getEstimatedCostPerInstantiation(VTIEnvironment vtiEnvironment)
      throws SQLException {
    return 1d;
  }
  
  @Override
  public void setQualifiers(VTIEnvironment vtiEnvironment,
      Qualifier[][] qualifiers) throws SQLException {
    if (qualifiers == null || qualifiers.length == 0) {
      return;
    }
    
    this.qualifiers = qualifiers;
  }
  
  public boolean qualifiesRow(DataValueDescriptor[] row, boolean allowPartialRow)
      throws StandardException {
    if (this.qualifiers != null) {
      return RowUtil.qualifyRow(row, null, this.qualifiers, allowPartialRow);
    }
    
    // always say yes! if no qualifier specified.
    return true;
  }

  @Override
  public boolean qualifyPartialRow(String tableName, String indexName, String indexType, String queueName, String queueType, long constantOverhead) throws StandardException {
    if (this.qualifiers == null) {
      // qualify all rows.
      return true;
    }

    final DataValueDescriptor[] partialRow = new DataValueDescriptor[columnInfo.length];

    int idx = 0;
    (partialRow[idx] = columnInfo[idx].getType().getNull()).setValue(tableName);
    idx++;
    (partialRow[idx] = columnInfo[idx].getType().getNull()).setValue(indexName);
    idx++;
    (partialRow[idx] = columnInfo[idx].getType().getNull()).setValue(indexType);
    idx++;
    (partialRow[idx] = columnInfo[idx].getType().getNull()).setValue(queueName);
    idx++;
    (partialRow[idx] = columnInfo[idx].getType().getNull()).setValue(queueType);
    idx++;
    (partialRow[idx] = columnInfo[idx].getType().getNull()).setValue(member);
    idx++;
    (partialRow[idx] = columnInfo[idx].getType().getNull()).setValue(host);
    idx++;
    (partialRow[idx] = columnInfo[idx].getType().getNull())
        .setValue(constantOverhead);
    
    boolean retVal = qualifiesRow(partialRow, true);
    
    if (TEST_MODE) {
      if (retVal)
        partialResult.add(partialRow);
    }
    
    return retVal;
  }

  @Override
  public boolean executeAsFastPath() throws StandardException, SQLException {
    return true;
  }

  @Override
  public int nextRow(ExecRow row, VTIResultSet parentRS) throws StandardException,
      SQLException {

    do {
      if (!next()) {
        return IFastPath.SCAN_COMPLETED;
      }
      parentRS.populateFromResultSet(row);
    } while (!qualifiesRow(row.getRowArray(), false));

    return IFastPath.GOT_ROW;
  }

  @Override
  public void currentRow(ResultSet rs, DataValueDescriptor[] row)
      throws StandardException, SQLException {
    throw new AssertionError("UnExpected call ");
  }

  @Override
  public void rowsDone() throws StandardException, SQLException {
    throw new AssertionError("UnExpected call ");
  }
  
  /**
   * Test method.
   * 
   * @return
   */
  public ArrayList<DataValueDescriptor[]> getPartialResult() {
    return partialResult;
  }
}
