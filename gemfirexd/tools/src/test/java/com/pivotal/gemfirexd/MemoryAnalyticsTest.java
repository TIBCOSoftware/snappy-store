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
package com.pivotal.gemfirexd;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.diag.MemoryAnalyticsVTI;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ProjectRestrictResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.execute.VTIResultSet;
import com.pivotal.gemfirexd.internal.vti.IQualifyable;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class MemoryAnalyticsTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(MemoryAnalyticsTest.class));
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = true;
    MemoryAnalyticsVTI.TEST_MODE = true;
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    delete(new File("./myhdfs"));
    delete(new File("./gemfire"));
    DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = false;
    MemoryAnalyticsVTI.TEST_MODE = false;
  }

  public MemoryAnalyticsTest(String name) {
    super(name);
  }
  
  private boolean generateComparisonValues = false;
  private boolean testSimpleQueryCalledAgain = false;
  
  public void testSimpleQuerying() throws Exception {
    Properties props = new Properties();
    props.setProperty("log-level", "config");
    props.setProperty("table-default-partitioned", "false");
    props.setProperty("server-groups", "dbsync");
    props.setProperty("persist-dd", "true");
    Connection conn = getConnection(props);
    String useCase3Script = TestUtil.getResourcesDir()
        + "/lib/useCase3Data/schema.sql";
    if (!testSimpleQueryCalledAgain) {
      GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase3Script },
          false, getLogger(), null, null, false);

      String useCase3DataScript = TestUtil.getResourcesDir()
          + "/lib/useCase3Data/importAll.sql";

      GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase3DataScript },
          false, getLogger(), "<path_prefix>",
          TestUtil.getResourcesDir(), false);
    }

//    conn.close();
//    conn = TestUtil.startNetserverAndGetLocalNetConnection();
    
    Statement stmt = conn.createStatement();
    // turn this to true for just printing out the expected results.

    stmt.execute("drop table if exists tmp.t1");
    ResultSet rs = stmt
        .executeQuery("select * from sys.memoryanalytics ");

    String[] columnList = new String[] { MemoryAnalyticsVTI.TABLE_NAME,
        MemoryAnalyticsVTI.INDEX_NAME, MemoryAnalyticsVTI.INDEX_TYPE,
        MemoryAnalyticsVTI.CONSTANT_OVERHEAD, MemoryAnalyticsVTI.ENTRY_SIZE,
        MemoryAnalyticsVTI.KEY_SIZE, MemoryAnalyticsVTI.VALUE_SIZE,
        MemoryAnalyticsVTI.VALUE_SIZE_OFFHEAP, MemoryAnalyticsVTI.NUM_ROWS,
        MemoryAnalyticsVTI.NUM_KEYS_MEMORY,
        MemoryAnalyticsVTI.NUM_VALUES_MEMORY,
        MemoryAnalyticsVTI.NUM_VALUES_OFFHEAP, MemoryAnalyticsVTI.TOTAL_SIZE };

        HashMap<String, float[]> indexColValues = new HashMap<String, float[]>();
        indexColValues.put("TF_GEMFIRE_PAA_ID", new float[] {0.152f, 0.072f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.224f});
        indexColValues.put("TF_GEMFIRE_PAA_NUI", new float[] {0.152f, 0.072f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.224f});
        indexColValues.put("TF_EQRMS_PAA_ID", new float[] {0.152f, 0.072f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.224f});
        indexColValues.put("TF_EQRMS_PAA_NUI", new float[] {0.152f, 0.072f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.224f});
        indexColValues.put("TF_EDEALER_PAA_ID", new float[] {0.152f, 0.072f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.224f});
        indexColValues.put("TF_EDEALER_PAA_NUI", new float[] {0.152f, 0.072f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.224f});
        indexColValues.put("IDX_FACT1_TM_ID", new float[] {0.152f, 0.368f, 0.0f, 0.9f, 0.0f, 5.0f, 0.0f, 0.0f, 0.0f, 1.42f});
        indexColValues.put("IDX_SOURCE_ID", new float[] {0.152f, 5.392f, 0.0f, 0.472f, 0.0f, 97.0f, 0.0f, 0.0f, 0.0f, 6.016f});
        indexColValues.put("IDX_FACT3_POSN_ID", new float[] {0.152f, 4.912f, 0.0f, 0.484f, 0.0f, 97.0f, 0.0f, 0.0f, 0.0f, 5.548f});
        indexColValues.put("IDX_FACT2_POSN_ID", new float[] {0.152f, 4.944f, 0.0f, 0.484f, 0.0f, 97.0f, 0.0f, 0.0f, 0.0f, 5.58f});
        indexColValues.put("IDX_ADJ_POSN_ID", new float[] {0.152f, 4.912f, 0.0f, 0.484f, 0.0f, 97.0f, 0.0f, 0.0f, 0.0f, 5.548f});
        indexColValues.put("IDX_TRADER_FIRM_ID", new float[] {0.152f, 5.288f, 0.0f, 0.4f, 0.0f, 100.0f, 0.0f, 0.0f, 0.0f, 5.84f});
        indexColValues.put("IDX_EXT_ID", new float[] {0.152f, 4.992f, 0.0f, 0.428f, 0.0f, 99.0f, 0.0f, 0.0f, 0.0f, 5.572f});
        indexColValues.put("IDX_INSM_ID", new float[] {0.152f, 5.56f, 0.0f, 0.456f, 0.0f, 98.0f, 0.0f, 0.0f, 0.0f, 6.168f});
        
    /* eclipse output
    indexColValues.put("TF_EDEALER_PAA_ID", new float[] {0.2265625f, 0.109375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.3359375f});
    indexColValues.put("TF_EDEALER_PAA_NUI", new float[] {0.2265625f, 0.109375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.3359375f});
    indexColValues.put("IDX_FACT3_POSN_ID", new float[] {0.2265625f, 5.9140625f, 2.9033203f, 0.921875f, 0.0f, 97.0f, 97.0f, 0.0f, 0.0f, 9.96582f});
    indexColValues.put("TF_GEMFIRE_PAA_ID", new float[] {0.2265625f, 0.109375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.3359375f});
    indexColValues.put("TF_GEMFIRE_PAA_NUI", new float[] {0.2265625f, 0.109375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.3359375f});
    indexColValues.put("IDX_INSM_ID", new float[] {0.2265625f, 6.703125f, 2.9277344f, 0.875f, 0.0f, 98.0f, 98.0f, 0.0f, 0.0f, 10.732422f});
    indexColValues.put("IDX_FACT1_TM_ID", new float[] {0.2265625f, 0.3984375f, 0.19042969f, 1.71875f, 0.0f, 5.0f, 5.0f, 0.0f, 0.0f, 2.5341797f});
    indexColValues.put("IDX_EXT_ID", new float[] {0.2265625f, 6.8828125f, 2.959961f, 0.828125f, 0.0f, 99.0f, 99.0f, 0.0f, 0.0f, 10.897461f});
    indexColValues.put("IDX_SOURCE_ID", new float[] {0.2265625f, 5.6328125f, 2.875f, 0.890625f, 0.0f, 97.0f, 97.0f, 0.0f, 0.0f, 9.625f});
    indexColValues.put("TF_EQRMS_PAA_ID", new float[] {0.2265625f, 0.109375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.3359375f});
    indexColValues.put("TF_EQRMS_PAA_NUI", new float[] {0.2265625f, 0.109375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.3359375f});
    indexColValues.put("IDX_TRADER_FIRM_ID", new float[] {0.2265625f, 5.609375f, 3.0029297f, 0.78125f, 0.0f, 100.0f, 100.0f, 0.0f, 0.0f, 9.620117f});
    indexColValues.put("IDX_ADJ_POSN_ID", new float[] {0.2265625f, 5.9609375f, 2.9033203f, 0.921875f, 0.0f, 97.0f, 97.0f, 0.0f, 0.0f, 10.012695f});
    indexColValues.put("IDX_FACT2_POSN_ID", new float[] {0.2265625f, 5.3046875f, 2.9033203f, 0.921875f, 0.0f, 97.0f, 97.0f, 0.0f, 0.0f, 9.356445f});
    */

    String[] expectedOutputArr = new String[] {
        "APP.TX_PL_POSITION null null 1.952 0.0 0.0 0.0 0.0 0 0 0 0 1.952 ",
        "APP.TF_PL_POSITION_DLY null null 1.952 0.0 0.0 0.0 0.0 0 0 0 0 1.952 ",
        "APP.TF_GEMFIRE_PAA null null 1.952 0.0 0.0 0.0 0.0 0 0 0 0 1.952 ",
        "APP.TF_EQRMS_PAA null null 1.952 0.0 0.0 0.0 0.0 0 0 0 0 1.952 ",
        "APP.TF_EDEALER_PAA null null 1.952 0.0 0.0 0.0 0.0 0 0 0 0 1.952 ",
        "APP.TX_PL_USER_POSN_MAP null null 1.952 12.0 2.4 109.456 0.0 100 100 100 0 125.808 ",
        "APP.TL_SOURCE_SYSTEM null null 1.832 9.6 2.4 21.088 0.0 100 100 100 0 34.92 ",
        "APP.TL_REGION null null 1.832 0.0 0.0 0.0 0.0 0 0 0 0 1.832 ",
        "APP.TL_CURRENCY null null 1.832 0.0 0.0 0.0 0.0 0 0 0 0 1.832 ",
        "APP.TF_PL_POSITION_YTD null null 1.952 12.0 2.4 626.912 0.0 100 100 100 0 643.264 ",
        "APP.TF_PL_POSITION_PTD null null 1.952 12.0 2.4 299.88 0.0 100 100 100 0 316.232 ",
        "APP.TF_PL_POSITION_FUNC null null 1.952 0.0 0.0 0.0 0.0 0 0 0 0 1.952 ",
        "APP.TF_PL_ADJ_REPORT null null 1.952 12.0 2.4 622.6 0.0 100 100 100 0 638.952 ",
        "APP.TD_TRADER_SCD null null 1.832 9.6 2.4 217.448 0.0 100 100 100 0 231.28 ",
        "APP.TD_POSN_EXTENDED_KEY null null 1.832 9.6 2.4 25.472 0.0 100 100 100 0 39.304 ",
        "APP.TD_PL_POSITION_INDICATIVE null null 1.832 0.0 0.0 0.0 0.0 0 0 0 0 1.832 ",
        "APP.TD_INSTRUMENT_SCD null null 1.832 9.6 2.4 96.288 0.0 100 100 100 0 110.12 ",
        "APP.TD_FIRM_ACCOUNT_SCD null null 1.832 0.0 0.0 0.0 0.0 0 0 0 0 1.832 ",
        /*--eclipse output
        "APP.TF_EDEALER_PAA null null 2.578125 0.0 0.0 0.0 0.0 0 0 0 0 2.578125 ",
        "APP.TF_PL_POSITION_YTD null null 2.578125 15.625 2.34375 611.0762 0.0 100 100 100 0 631.62305 ",
        "APP.TF_GEMFIRE_PAA null null 2.578125 0.0 0.0 0.0 0.0 0 0 0 0 2.578125 ",
        "APP.TD_INSTRUMENT_SCD null null 2.3984375 13.28125 2.34375 92.94141 0.0 100 100 100 0 110.96484 ",
        "APP.TD_FIRM_ACCOUNT_SCD null null 2.3984375 0.0 0.0 0.0 0.0 0 0 0 0 2.3984375 ",
        "APP.TF_PL_POSITION_DLY null null 2.578125 0.0 0.0 0.0 0.0 0 0 0 0 2.578125 ",
        "APP.TL_CURRENCY null null 2.3984375 0.0 0.0 0.0 0.0 0 0 0 0 2.3984375 ",
        "APP.TX_PL_USER_POSN_MAP null null 2.578125 15.625 2.34375 105.74902 0.0 100 100 100 0 126.2959 ",
        "APP.TD_POSN_EXTENDED_KEY null null 2.3984375 13.28125 2.34375 23.756836 0.0 100 100 100 0 41.780273 ",
        "APP.TL_SOURCE_SYSTEM null null 2.3984375 13.28125 2.34375 19.46582 0.0 100 100 100 0 37.489258 ",
        "APP.TD_PL_POSITION_INDICATIVE null null 2.3984375 0.0 0.0 0.0 0.0 0 0 0 0 2.3984375 ",
        "APP.TL_REGION null null 2.3984375 0.0 0.0 0.0 0.0 0 0 0 0 2.3984375 ",
        "APP.TF_EQRMS_PAA null null 2.578125 0.0 0.0 0.0 0.0 0 0 0 0 2.578125 ",
        "APP.TD_TRADER_SCD null null 2.3984375 13.28125 2.34375 211.2539 0.0 100 100 100 0 229.27734 ",
        "APP.TF_PL_ADJ_REPORT null null 2.578125 15.625 2.34375 606.8916 0.0 100 100 100 0 627.4385 ",
        "APP.TF_PL_POSITION_FUNC null null 2.578125 0.0 0.0 0.0 0.0 0 0 0 0 2.578125 ",
        "APP.TF_PL_POSITION_PTD null null 2.578125 15.625 2.34375 291.68066 0.0 100 100 100 0 312.22754 ",
        "APP.TX_PL_POSITION null null 2.578125 0.0 0.0 0.0 0.0 0 0 0 0 2.578125 ",
        */
    };
    
    List<String> expectedOutput = Arrays.asList(expectedOutputArr);
    StringBuilder printOut = new StringBuilder("\n");
    StringBuilder printOut2 = new StringBuilder("\n");

    while (rs.next()) {
      final String indexName = rs.getString(MemoryAnalyticsVTI.INDEX_NAME);
      final String indexType = rs.getString(MemoryAnalyticsVTI.INDEX_TYPE);
      float constantOverhead = rs
          .getFloat(MemoryAnalyticsVTI.CONSTANT_OVERHEAD);
      float entrySize = rs.getFloat(MemoryAnalyticsVTI.ENTRY_SIZE);
      float keySize = rs.getFloat(MemoryAnalyticsVTI.KEY_SIZE);
      float valSize = rs.getFloat(MemoryAnalyticsVTI.VALUE_SIZE);
      float valOffheapSize = rs.getFloat(MemoryAnalyticsVTI.VALUE_SIZE_OFFHEAP);
      float numRows = rs.getFloat(MemoryAnalyticsVTI.NUM_ROWS);
      float numKeysInMemory = rs.getFloat(MemoryAnalyticsVTI.NUM_KEYS_MEMORY);
      float numValuesInMemory = rs.getFloat(MemoryAnalyticsVTI.NUM_VALUES_MEMORY);
      float numValuesInOffheap = rs.getFloat(MemoryAnalyticsVTI.NUM_VALUES_OFFHEAP);
      float totalSize = rs.getFloat(MemoryAnalyticsVTI.TOTAL_SIZE);

      // as index sizes are approximate rather than consistent due to skiplist.
      if ("LOCAL".equals(indexType)) {
        
        if (generateComparisonValues) {
          printOut
              .append(
                  "indexColValues.put(\"" + indexName + "\", new float[] {")
              .append(constantOverhead).append("f, ")
              .append(entrySize).append("f, ")
              .append(keySize).append("f, ")
              .append(valSize).append("f, ")
              .append(valOffheapSize).append("f, ")
              .append(numRows).append("f, ")
              .append(numKeysInMemory).append("f, ")
              .append(numValuesInMemory).append("f, ")
              .append(numValuesInOffheap).append("f, ")
              .append(totalSize).append("f});\n");
          continue;
        }
        
        final float[] expectedValues = indexColValues.get(indexName);

        float diff = Math.abs(expectedValues[0] - constantOverhead);
        assertTrue(indexName + " ConstantOverhead deviated by " + diff + "(" + constantOverhead + "," + expectedValues[0] + ")", diff < 1f);

        diff = Math.abs(expectedValues[1] - entrySize);
        assertTrue(indexName + " EntrySize deviated by " + diff + "(" + entrySize + "," + expectedValues[1] + ")", diff < 2f);

        diff = Math.abs(expectedValues[2] - keySize);
        assertTrue(indexName + " KeySize deviated by " + diff + "(" + keySize + "," + expectedValues[2] + ")", diff < 1f);

        diff = Math.abs(expectedValues[3] - valSize);
        assertTrue(indexName + " ValSize deviated by " + diff + "(" + valSize + "," + expectedValues[3] + ")", diff < 1f);

        diff = Math.abs(expectedValues[4] - valOffheapSize);
        assertTrue(indexName + " ValOffheapSize deviated by " + diff + "(" + valOffheapSize + "," + expectedValues[4] + ")", diff < 1f);
        
        assertEquals(indexName + " numRows=" + numRows + " expected=" + expectedValues[5], expectedValues[5], numRows);
        assertEquals(indexName + " numKeysInMemory=" + numKeysInMemory + " expected=" + expectedValues[6], expectedValues[6], numKeysInMemory);
        assertEquals(indexName + " numValuesInMemory=" + numValuesInMemory + " expected=" + expectedValues[7], expectedValues[7], numValuesInMemory);
        assertEquals(indexName + " numValuesInOffheap=" + numValuesInOffheap + " expected=" + expectedValues[8], expectedValues[8], numValuesInOffheap);
        
        diff = Math.abs(expectedValues[9] - totalSize);
        assertTrue(indexName + " TotalSize deviated by " + diff + "(" + totalSize + "," + expectedValues[9] + ")", diff < 2f);
        continue;
      }

      String output = checkOutput(rs, columnList, expectedOutput, generateComparisonValues ? printOut2 : null);
      if (output != null) {
        generateComparisonValues = true;
        testSimpleQueryCalledAgain = true;
        getLogger().info("testSimpleQuerying being called again for just generating the expected values");
        testSimpleQuerying();
        fail("not found in expected output: " + output);
      }

    }
    
    if (generateComparisonValues) {
      getLogger().info(printOut.toString());
      getLogger().info(printOut2.toString());
      printOut = new StringBuilder("\n");
      printOut2= new StringBuilder("\n");
    }

    rs = stmt
        .executeQuery("select table_name, sum(constant_overhead) as csz, sum(entry_size) as esz, sum(key_size) as ksz, sum(value_size) as vsz, sum(value_size_offheap) as vohsz," +
        		" sum(num_rows) as nr, sum(num_keys_in_memory) as nkim, sum(num_values_in_memory) as nvim, sum(num_values_in_offheap) as nvioh, " +
        		" sum(total_size) as totsz from sys.memoryanalytics group by table_name");

    indexColValues = new HashMap<String, float[]>();
    indexColValues.put("APP.TD_FIRM_ACCOUNT_SCD", new float[] {1.696f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.696f});
    indexColValues.put("APP.TD_INSTRUMENT_SCD", new float[] {1.8479999f, 15.16f, 2.4f, 96.744f, 0.0f, 198.0f, 100.0f, 100.0f, 0.0f, 116.152f});
    indexColValues.put("APP.TD_PL_POSITION_INDICATIVE", new float[] {1.696f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.696f});
    indexColValues.put("APP.TD_POSN_EXTENDED_KEY", new float[] {1.8479999f, 14.592001f, 2.4f, 25.9f, 0.0f, 199.0f, 100.0f, 100.0f, 0.0f, 44.739998f});
    indexColValues.put("APP.TD_TRADER_SCD", new float[] {1.8479999f, 14.8880005f, 2.4f, 217.84799f, 0.0f, 200.0f, 100.0f, 100.0f, 0.0f, 236.984f});
    indexColValues.put("APP.TF_EDEALER_PAA", new float[] {2.112f, 0.144f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.256f});
    indexColValues.put("APP.TF_EQRMS_PAA", new float[] {2.112f, 0.144f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.256f});
    indexColValues.put("APP.TF_GEMFIRE_PAA", new float[] {2.112f, 0.144f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.256f});
    indexColValues.put("APP.TF_PL_ADJ_REPORT", new float[] {1.9599999f, 16.912f, 2.4f, 623.084f, 0.0f, 197.0f, 100.0f, 100.0f, 0.0f, 644.35596f});
    indexColValues.put("APP.TF_PL_POSITION_DLY", new float[] {1.808f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.808f});
    indexColValues.put("APP.TF_PL_POSITION_FUNC", new float[] {1.808f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.808f});
    indexColValues.put("APP.TF_PL_POSITION_PTD", new float[] {1.9599999f, 16.944f, 2.4f, 300.364f, 0.0f, 197.0f, 100.0f, 100.0f, 0.0f, 321.668f});
    indexColValues.put("APP.TF_PL_POSITION_YTD", new float[] {1.9599999f, 16.912f, 2.4f, 627.396f, 0.0f, 197.0f, 100.0f, 100.0f, 0.0f, 648.66797f});
    indexColValues.put("APP.TL_CURRENCY", new float[] {1.696f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.696f});
    indexColValues.put("APP.TL_REGION", new float[] {1.696f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.696f});
    indexColValues.put("APP.TL_SOURCE_SYSTEM", new float[] {1.8479999f, 14.992001f, 2.4f, 21.56f, 0.0f, 197.0f, 100.0f, 100.0f, 0.0f, 40.8f});
    indexColValues.put("APP.TX_PL_POSITION", new float[] {1.808f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.808f});
    indexColValues.put("APP.TX_PL_USER_POSN_MAP", new float[] {1.9599999f, 12.368f, 2.4f, 110.356f, 0.0f, 105.0f, 100.0f, 100.0f, 0.0f, 127.084f});
    /*eclipse output
    indexColValues.put("APP.TD_FIRM_ACCOUNT_SCD", new float[] {2.3984375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.3984375f});
    indexColValues.put("APP.TD_INSTRUMENT_SCD", new float[] {2.625f, 19.984375f, 5.2714844f, 93.81641f, 0.0f, 198.0f, 198.0f, 100.0f, 0.0f, 121.697266f});
    indexColValues.put("APP.TD_PL_POSITION_INDICATIVE", new float[] {2.3984375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.3984375f});
    indexColValues.put("APP.TD_POSN_EXTENDED_KEY", new float[] {2.625f, 20.164062f, 5.303711f, 24.58496f, 0.0f, 199.0f, 199.0f, 100.0f, 0.0f, 52.677734f});
    indexColValues.put("APP.TD_TRADER_SCD", new float[] {2.625f, 18.890625f, 5.3466797f, 212.03516f, 0.0f, 200.0f, 200.0f, 100.0f, 0.0f, 238.89746f});
    indexColValues.put("APP.TF_EDEALER_PAA", new float[] {3.03125f, 0.21875f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 3.25f});
    indexColValues.put("APP.TF_EQRMS_PAA", new float[] {3.03125f, 0.21875f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 3.25f});
    indexColValues.put("APP.TF_GEMFIRE_PAA", new float[] {3.03125f, 0.21875f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 3.25f});
    indexColValues.put("APP.TF_PL_ADJ_REPORT", new float[] {2.8046875f, 21.585938f, 5.2470703f, 607.8135f, 0.0f, 197.0f, 197.0f, 100.0f, 0.0f, 637.4512f});
    indexColValues.put("APP.TF_PL_POSITION_DLY", new float[] {2.578125f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.578125f});
    indexColValues.put("APP.TF_PL_POSITION_FUNC", new float[] {2.578125f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.578125f});
    indexColValues.put("APP.TF_PL_POSITION_PTD", new float[] {2.8046875f, 20.929688f, 5.2470703f, 292.60254f, 0.0f, 197.0f, 197.0f, 100.0f, 0.0f, 321.58398f});
    indexColValues.put("APP.TF_PL_POSITION_YTD", new float[] {2.8046875f, 21.539062f, 5.2470703f, 611.99805f, 0.0f, 197.0f, 197.0f, 100.0f, 0.0f, 641.58887f});
    indexColValues.put("APP.TL_CURRENCY", new float[] {2.3984375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.3984375f});
    indexColValues.put("APP.TL_REGION", new float[] {2.3984375f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.3984375f});
    indexColValues.put("APP.TL_SOURCE_SYSTEM", new float[] {2.625f, 18.914062f, 5.21875f, 20.356445f, 0.0f, 197.0f, 197.0f, 100.0f, 0.0f, 47.114258f});
    indexColValues.put("APP.TX_PL_POSITION", new float[] {2.578125f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.578125f});
    indexColValues.put("APP.TX_PL_USER_POSN_MAP", new float[] {2.8046875f, 16.023438f, 2.5341797f, 107.46777f, 0.0f, 105.0f, 105.0f, 100.0f, 0.0f, 128.83008f});
    */
    
    while (rs.next()) {
      final String tableName = rs.getString(MemoryAnalyticsVTI.TABLE_NAME);
      float constantOverhead = rs.getFloat("csz");
      float entrySize = rs.getFloat("esz");
      float keySize = rs.getFloat("ksz");
      float valSize = rs.getFloat("vsz");
      float valOffheapSize = rs.getFloat("vohsz");
      float numRows = rs.getFloat("nr");
      float numKeysInMemory = rs.getFloat("nkim");
      float numValuesInMemory = rs.getFloat("nvim");
      float numValuesInOffheap = rs.getFloat("nvioh");
      float totalSize = rs.getFloat("totsz");
      
      if (generateComparisonValues) {
        printOut
        .append(
            "indexColValues.put(\"" + tableName + "\", new float[] {")
        .append(constantOverhead).append("f, ")
        .append(entrySize).append("f, ")
        .append(keySize).append("f, ")
        .append(valSize).append("f, ")
        .append(valOffheapSize).append("f, ")
        .append(numRows).append("f, ")
        .append(numKeysInMemory).append("f, ")
        .append(numValuesInMemory).append("f, ")
        .append(numValuesInOffheap).append("f, ")
        .append(totalSize).append("f});\n");
        continue;
      }

      final float[] expectedValues = indexColValues.get(tableName);

      float diff = Math.abs(expectedValues[0] - constantOverhead);
      assertTrue(tableName + " ConstantOverhead deviated by " + diff + "(" + constantOverhead + "," + expectedValues[0] + ")", diff < 1f);

      diff = Math.abs(expectedValues[1] - entrySize);
      assertTrue(tableName + " EntrySize deviated by " + diff + "(" + entrySize + "," + expectedValues[1] + ")", diff < 2f);

      diff = Math.abs(expectedValues[2] - keySize);
      assertTrue(tableName + " KeySize deviated by " + diff + "(" + keySize + "," + expectedValues[2] + ")", diff < 1f);

      diff = Math.abs(expectedValues[3] - valSize);
      assertTrue(tableName + " ValSize deviated by " + diff + "(" + valSize + "," + expectedValues[3] + ")", diff < 1f);

      diff = Math.abs(expectedValues[4] - valOffheapSize);
      assertTrue(tableName + " ValOffheapSize deviated by " + diff + "(" + valOffheapSize + "," + expectedValues[4] + ")", diff < 1f);

      assertEquals("numRows=" + numRows + " expected=" + expectedValues[5], expectedValues[5], numRows);
      assertEquals("numKeysInMemory=" + numKeysInMemory + " expected=" + expectedValues[6], expectedValues[6], numKeysInMemory);
      assertEquals("numValuesInMemory=" + numValuesInMemory + " expected=" + expectedValues[7], expectedValues[7], numValuesInMemory);
      assertEquals("numValuesInOffheap=" + numValuesInOffheap + " expected=" + expectedValues[8], expectedValues[8], numValuesInOffheap);
      
      diff = Math.abs(expectedValues[9] - totalSize);
      assertTrue(tableName + " TotalSize deviated by " + diff + "(" + totalSize + "," + expectedValues[9] + ")", diff < 3f);
    }

    if (generateComparisonValues) {
      getLogger().info(printOut.toString());
      printOut = new StringBuilder("\n");
    }

    rs = stmt
        .executeQuery("select table_name, sum(constant_overhead) + sum(entry_size) + sum(key_size) + sum(value_size) + sum(value_size_offheap) as totsz, sum(num_rows) as nr from ("
            + "   select table_name, constant_overhead, entry_size, key_size, value_size, value_size_offheap, case when index_name is null then num_rows else 0 end as num_rows from sys.memoryanalytics"
            + ") as memA group by table_name ");

    indexColValues = new HashMap<String, float[]>();
    indexColValues.put("APP.TD_FIRM_ACCOUNT_SCD", new float[] {1.696f, 0.0f});
    indexColValues.put("APP.TD_INSTRUMENT_SCD", new float[] {116.152f, 100.0f});
    indexColValues.put("APP.TD_PL_POSITION_INDICATIVE", new float[] {1.696f, 0.0f});
    indexColValues.put("APP.TD_POSN_EXTENDED_KEY", new float[] {44.739998f, 100.0f});
    indexColValues.put("APP.TD_TRADER_SCD", new float[] {236.984f, 100.0f});
    indexColValues.put("APP.TF_EDEALER_PAA", new float[] {2.256f, 0.0f});
    indexColValues.put("APP.TF_EQRMS_PAA", new float[] {2.256f, 0.0f});
    indexColValues.put("APP.TF_GEMFIRE_PAA", new float[] {2.256f, 0.0f});
    indexColValues.put("APP.TF_PL_ADJ_REPORT", new float[] {644.35596f, 100.0f});
    indexColValues.put("APP.TF_PL_POSITION_DLY", new float[] {1.808f, 0.0f});
    indexColValues.put("APP.TF_PL_POSITION_FUNC", new float[] {1.808f, 0.0f});
    indexColValues.put("APP.TF_PL_POSITION_PTD", new float[] {321.668f, 100.0f});
    indexColValues.put("APP.TF_PL_POSITION_YTD", new float[] {648.66797f, 100.0f});
    indexColValues.put("APP.TL_CURRENCY", new float[] {1.696f, 0.0f});
    indexColValues.put("APP.TL_REGION", new float[] {1.696f, 0.0f});
    indexColValues.put("APP.TL_SOURCE_SYSTEM", new float[] {40.8f, 100.0f});
    indexColValues.put("APP.TX_PL_POSITION", new float[] {1.808f, 0.0f});
    indexColValues.put("APP.TX_PL_USER_POSN_MAP", new float[] {127.084f, 100.0f});
    /* eclipse output
    indexColValues.put("APP.TD_FIRM_ACCOUNT_SCD", new float[] {2.3984375f, 0.0f});
    indexColValues.put("APP.TD_INSTRUMENT_SCD", new float[] {121.697266f, 100.0f});
    indexColValues.put("APP.TD_PL_POSITION_INDICATIVE", new float[] {2.3984375f, 0.0f});
    indexColValues.put("APP.TD_POSN_EXTENDED_KEY", new float[] {52.677734f, 100.0f});
    indexColValues.put("APP.TD_TRADER_SCD", new float[] {238.89746f, 100.0f});
    indexColValues.put("APP.TF_EDEALER_PAA", new float[] {3.25f, 0.0f});
    indexColValues.put("APP.TF_EQRMS_PAA", new float[] {3.25f, 0.0f});
    indexColValues.put("APP.TF_GEMFIRE_PAA", new float[] {3.25f, 0.0f});
    indexColValues.put("APP.TF_PL_ADJ_REPORT", new float[] {637.4512f, 100.0f});
    indexColValues.put("APP.TF_PL_POSITION_DLY", new float[] {2.578125f, 0.0f});
    indexColValues.put("APP.TF_PL_POSITION_FUNC", new float[] {2.578125f, 0.0f});
    indexColValues.put("APP.TF_PL_POSITION_PTD", new float[] {321.58398f, 100.0f});
    indexColValues.put("APP.TF_PL_POSITION_YTD", new float[] {641.58887f, 100.0f});
    indexColValues.put("APP.TL_CURRENCY", new float[] {2.3984375f, 0.0f});
    indexColValues.put("APP.TL_REGION", new float[] {2.3984375f, 0.0f});
    indexColValues.put("APP.TL_SOURCE_SYSTEM", new float[] {47.114258f, 100.0f});
    indexColValues.put("APP.TX_PL_POSITION", new float[] {2.578125f, 0.0f});
    indexColValues.put("APP.TX_PL_USER_POSN_MAP", new float[] {128.83008f, 100.0f});
    */
    
    while (rs.next()) {
      final String tableName = rs.getString(MemoryAnalyticsVTI.TABLE_NAME);
      float totalSize = rs.getFloat("totsz");
      float numRows = rs.getFloat("nr");

      if (generateComparisonValues) {
        printOut
            .append(
                "indexColValues.put(\"" + tableName + "\", new float[] {")
            .append(totalSize).append("f, ").append(numRows)            
            .append("f});\n");
        continue;
      }
      
      
      final float[] expectedValues = indexColValues.get(tableName);
      
      float diff = Math.abs(expectedValues[0] - totalSize);
      assertTrue(tableName + " TotalSize deviated by " + diff + "(" + totalSize + "," + expectedValues[0] + ")", diff < 3f);
      assertEquals("numRows=" + numRows + " expected=" + expectedValues[1], expectedValues[1], numRows);
    }

    if (generateComparisonValues) {
      getLogger().info(printOut.toString());
      printOut = new StringBuilder('\n');
    }

    // verify documented Queries.
    int rows = 0;

    rs = stmt
        .executeQuery("select table_name, sum(constant_overhead+entry_size) / case when sum(num_rows) > 0 then sum(num_rows) else 1 end from sys.memoryanalytics where index_name is null group by table_name");
    while(rs.next()) {
      rows ++;
    }
    
    if (generateComparisonValues) {
      getLogger().info("assertEquals(" + rows + ", rows);");
    }
    else {
      assertEquals(18, rows);
    }

    rows = 0;
    rs = stmt
        .executeQuery("select table_name, sum(constant_overhead+entry_size) / case when sum(num_rows) > 0 then sum(num_rows) else 1 end from sys.memoryanalytics group by table_name");
    while(rs.next()) {
      rows ++;
    }

    if (generateComparisonValues) {
      getLogger().info("assertEquals(" + rows + ", rows);");
    }
    else {
      assertEquals(18, rows);
    }
    
    rows = 0;
    rs = stmt
        .executeQuery("select index_name, sum(total_size) from sys.memoryanalytics where index_name like 'IDX%' group by index_name");
    while(rs.next()) {
      rows ++;
    }

    if (generateComparisonValues) {
      getLogger().info("assertEquals(" + rows + ", rows);");
    }
    else {
      assertEquals(8, rows);
    }

    rows = 0;
    rs = stmt
        .executeQuery("select host, sum(total_size) from sys.memoryanalytics group by host");
    while(rs.next()) {
      rows ++;
    }

    if (generateComparisonValues) {
      getLogger().info("assertEquals(" + rows + ", rows);");
    }
    else {
      assertEquals(1, rows);
    }

    rows = 0;
    rs = stmt
        .executeQuery("select table_name, index_name, key_size/ case when num_keys_in_memory > 0 then num_keys_in_memory else 1 end from sys.memoryanalytics");
    while(rs.next()) {
      rows ++;
    }

    if (generateComparisonValues) {
      getLogger().info("assertEquals(" + rows + ", rows);");
    }
    else {
      assertEquals(32, rows);    
    }

    rows = 0;
    rs = stmt
        .executeQuery("select table_name, "
            + "sum(value_size)/case when "
            + "                      sum(num_values_in_memory) > 0 then sum(num_values_in_memory) else 1 end, "
            + "sum(value_size_offheap)/ case when "
            + "                      sum(num_values_in_offheap) > 0 then sum(num_values_in_offheap) else 1 end "
            + "from sys.memoryanalytics where index_name is null group by table_name");
    while(rs.next()) {
      rows ++;
    }

    if (generateComparisonValues) {
      getLogger().info("assertEquals(" + rows + ", rows);");
    }
    else {
      assertEquals(18, rows);
    }
    
    rows = 0;
    rs = stmt
        .executeQuery("select table_name, index_name, max(total_size) as max_, min(total_size) as min_ from sys.memoryanalytics group by table_name, index_name");
    while(rs.next()) {
      rows ++;
    }

    if (generateComparisonValues) {
      getLogger().info("assertEquals(" + rows + ", rows);");
    }
    else {
      assertEquals(32, rows);    
    }

    rows = 0;
    // documented Queries END.
    
  }
  
  public void testPushDownQualifiers() throws Exception {
    Properties props = new Properties();
    props.setProperty("log-level", "config");
    props.setProperty("table-default-partitioned", "false");
    props.setProperty("server-groups", "dbsync");
    props.setProperty("persist-dd", "true");
    Connection conn = getConnection(props);
   
    {
      String useCase7Script = TestUtil.getResourcesDir()
          + "/lib/useCase7/schema.sql";
      GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase7Script }, false,
          getLogger(), null, null, false);

      String useCase7DataScript = TestUtil.getResourcesDir()
          + "/lib/useCase7/import.sql";

      GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase7DataScript },
          false, getLogger(), "<path_prefix>",
          TestUtil.getResourcesDir(), false);
    }

    {
      String useCase3Script = TestUtil.getResourcesDir()
          + "/lib/useCase3Data/schema.sql";
      GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase3Script }, false,
          getLogger(), null, null, false);

      String useCase3DataScript = TestUtil.getResourcesDir()
          + "/lib/useCase3Data/importAll.sql";

      GemFireXDUtils.executeSQLScripts(conn, new String[] { useCase3DataScript },
          false, getLogger(), "<path_prefix>",
          TestUtil.getResourcesDir(), false);
    }
    
//    conn.close();
//    conn = TestUtil.startNetserverAndGetLocalNetConnection();
    
    Statement stmt = conn.createStatement();
    
    final int[] expectedPartialRows = new int[1];
    final MemoryAnalyticsVTI[] userRS = new MemoryAnalyticsVTI[1];
    
    @SuppressWarnings("serial")
    final GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      @Override
      public void afterResultSetOpen(GenericPreparedStatement stmt, LanguageConnectionContext lcc, com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
        if (resultSet instanceof AbstractGemFireResultSet) {
          return;
        }
        // ensure topmost row is VTI because there is no projection.
        assertTrue(resultSet.getClass().getName(),
            resultSet instanceof VTIResultSet
                || resultSet instanceof ProjectRestrictResultSet);
        final VTIResultSet rs;
        if (resultSet instanceof ProjectRestrictResultSet) {
          NoPutResultSet r = ((ProjectRestrictResultSet) resultSet).getSource();
          assertTrue(r.getClass().getName(), r instanceof VTIResultSet);
          rs = (VTIResultSet)r;
        }
        else {
          rs = (VTIResultSet) resultSet;
        }
        assertTrue(rs.getFastPath() != null);
        ResultSet usrRS = rs.getUserVTI();
        assertTrue(usrRS instanceof IQualifyable);
        assertTrue(usrRS instanceof MemoryAnalyticsVTI);
        userRS[0] = ((MemoryAnalyticsVTI)usrRS);
      }
    };
    
    GemFireXDQueryObserverHolder.setInstance(observer);
    expectedPartialRows[0] = 40;

    int rows = 0;

    ResultSet rs = stmt
        .executeQuery("select * from sys.memoryanalytics where table_name like 'APP.C$_BO$_%' {escape '$'}");
    //  or table_name like 'APP.TF%'
    while(rs.next()) {
      rows++;
      String col = rs.getString(1);
      assertTrue(col, col.startsWith("APP.C_BO_") || col.startsWith("APP.TF"));
      final String report = rs.getString("MEMORY");
      assertMemReport(report);
    }
    
    ArrayList<DataValueDescriptor[]> res = userRS[0].getPartialResult();
    int numTablesQualified = 0;
    for (DataValueDescriptor[] dvdA : res) {
      if (dvdA[1].isNull() && dvdA[2].isNull()) {
        numTablesQualified++;
      }
    }
    assertTrue(res != null);
    assertEquals(expectedPartialRows[0], res.size());
    assertNotSame(userRS[0].getEstimatedRowCount(null), numTablesQualified);
    
    assertEquals(expectedPartialRows[0], rows);
    rs.close();
    
    rows = 0;
    rs = stmt
        .executeQuery("select * from sys.memoryanalytics where entry_size > 0.2");
    while(rs.next()) {
      rows++;
      assertTrue(rs.getFloat("ENTRY_SIZE") > 0.2f);
      final String report = rs.getString("MEMORY");
      assertMemReport(report);
      getLogger().info(
          rs.getString(1) + " " + rs.getFloat("ENTRY_SIZE") + " "
              + rs.getString("QUEUE_NAME") + " " + rs.getString("QUEUE_TYPE"));
    }
    
    res = userRS[0].getPartialResult();
    numTablesQualified = 0;
    for (DataValueDescriptor[] dvdA : res) {
      if (dvdA[1].isNull() && dvdA[2].isNull()) {
        numTablesQualified++;
      }
    }
    assertTrue(res != null);
    assertEquals(userRS[0].getEstimatedRowCount(null), (double)numTablesQualified);
    expectedPartialRows[0] = 151;
    assertEquals(expectedPartialRows[0], res.size());
    
    assertEquals(23, rows);
    rs.close();
  }
  
  public void testHDFSTables() throws Exception {
    checkDirExistence("./myhdfs");
    Properties props = new Properties();
    props.setProperty("log-level", "config");
    props.setProperty("table-default-partitioned", "false");
//    props.setProperty("server-groups", "dbsync");
//    props.setProperty("persist-dd", "true");
    Connection conn = getConnection(props);
    
    Statement st = conn.createStatement();
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs'");
    st.execute("create table t1 (col1 int primary key, col2 int) partition by primary key hdfsstore (myhdfs)");
    
    Set<GatewaySender> senders = Misc.getGemFireCache().getAllGatewaySenders();

    for (GatewaySender g : senders) {
      g.pause();
    }

    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");
    int NUM_ROWS = 5000;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    ResultSet rs = st
        .executeQuery("select * from sys.memoryanalytics ");
    //  or table_name like 'APP.TF%'
    while (rs.next()) {
      final String report = rs.getString("MEMORY");
      getLogger().info(
          rs.getString(1) + " constant=" + rs.getFloat("CONSTANT_OVERHEAD")
              + " ez=" + rs.getFloat("ENTRY_SIZE") + " qname="
              + rs.getString("QUEUE_NAME") + " type="
              + rs.getString("QUEUE_TYPE") + "\nreport\n" + report + "\n");
    }
    
    rs.close();
    
    for (GatewaySender g : senders) {
      g.resume();
      
      while(((AbstractGatewaySender)g).getQueue().size() > 0) {
        Thread.sleep(1000);
        rs = st
            .executeQuery("select * from sys.memoryanalytics where queue_type is not null");
        //  or table_name like 'APP.TF%'
        while (rs.next()) {
          final String report = rs.getString("MEMORY");
          getLogger().info(
              rs.getString(1) + " constant=" + rs.getFloat("CONSTANT_OVERHEAD")
                  + " ez=" + rs.getFloat("ENTRY_SIZE") + " qname="
                  + rs.getString("QUEUE_NAME") + " type="
                  + rs.getString("QUEUE_TYPE") + "\nreport\n" + report + "\n");
        }
        rs.close();
      }
    }

    rs = st
        .executeQuery("select * from sys.memoryanalytics where queue_type is not null");
    //  or table_name like 'APP.TF%'
    while (rs.next()) {
      final String report = rs.getString("MEMORY");
      getLogger().info(
          rs.getString(1) + " constant=" + rs.getFloat("CONSTANT_OVERHEAD")
              + " ez=" + rs.getFloat("ENTRY_SIZE") + " qname="
              + rs.getString("QUEUE_NAME") + " type="
              + rs.getString("QUEUE_TYPE") + "\nreport\n" + report + "\n");
    }
    
    rs.close();
    st.execute("drop table t1");
  }

  private void delete(File file) {
    if (!file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      if (file.list().length == 0) {
        file.delete();
      }
      else {
        File[] files = file.listFiles();
        for (File f : files) {
          delete(f);
        }        
        file.delete();        
      }
    }
    else {
      file.delete();
    }
  }
  
//Assume no other thread creates the directory at the same time
private void checkDirExistence(String path) {
 File dir = new File(path);
 if (dir.exists()) {
   delete(dir);
 }
}


  
  private void assertMemReport(String report) {
    if (report.length() > 3) {
      StringTokenizer st = new StringTokenizer(report);
      int tokens=st.countTokens();
      getLogger().info("asserting : " + report + " with numTokens=" + tokens);
      String ft = st.nextToken("\n");
      Pattern pt = Pattern.compile("Primary Bucket Distribution: [1-9]([0-9]{1,2})? buckets with min [1-9]([0-9]{1,2})? entries and max [1-9]([0-9]{1,2})? entries.");
      assertTrue(ft, pt.matcher(ft).find());
      getLogger().info(ft);
      
      ft = st.nextToken("\n{},");
      pt = Pattern.compile("B[0-9][0-9][0-9]=( )*([1-9])+");
      
      assertTrue(ft, pt.matcher(ft).find());
      getLogger().info(ft);
      int tgtLen = ft.length();
      boolean reachedHistogram = false;
      
      while(st.hasMoreTokens()) {
        String t = st.nextToken();
        
        if ( !reachedHistogram && (reachedHistogram = t.contains("histogram:")) ) {
          t = st.nextToken("\n");
          pt = Pattern.compile("[1-9][0-9]*-( )*[1-9][0-9]*( )+([0-9])+( )+([0-9])+(.([0-9])+)?%");
        }

        if (reachedHistogram) {
          getLogger().info(t);
          assertTrue(t, pt.matcher(t).find());
        }
        else {
          assertTrue(t, pt.matcher(t).find());
          assertEquals(tgtLen, t.length());
          getLogger().info(t);
        }
        
        tokens++;
      }
    }
  }
  
  private String checkOutput(ResultSet rs, String[] columnList,
      List<String> expectedOutput, StringBuilder printOut2) throws Exception {
    
      StringBuilder sb = new StringBuilder();
      for (String s : columnList) {
        sb.append(rs.getString(s)).append(' ');
      }
      String output = sb.toString();
      // getLogger().info(output);
      
      // just print the values and skip checks.
      if (printOut2 != null) {
         printOut2.append('"').append(output).append("\",\n");
         System.out.println("Output = "+ output);
         return null;
      }

      if (!expectedOutput.contains(output)) {
        return output;
      }
      return null;
    }
}
