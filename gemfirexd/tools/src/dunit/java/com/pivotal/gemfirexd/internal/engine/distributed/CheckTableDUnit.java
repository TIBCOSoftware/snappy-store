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
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import io.snappydata.test.dunit.VM;

public class CheckTableDUnit extends DistributedSQLTestBase {

  public CheckTableDUnit(String name) {
    super(name);
  }

//  @Override
//  public String reduceLogging() {
//    return "fine";
//  }

  public void testLocalIndexConsistency() throws Exception {
    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);

    Connection conn = TestUtil.getNetConnection(netPort1, null, null);

    // should throw an error
    try {
      conn.createStatement().executeQuery("VALUES SYS.CHECK_TABLE_EX(NULL, 'TABLE1')");
      fail("null argument to SYS.CHECK_TABLE_EX() should throw an error");
    } catch(SQLException se) {
      if (!se.getSQLState().equals("22008")) {
        throw se;
      }
    }
    // for partitioned table
    checkLocalIndexConsistency(conn, false);
    // for replicated table
    checkLocalIndexConsistency(conn, true);
  }

  private void checkLocalIndexConsistency(Connection conn,
      boolean isReplicated) throws SQLException {
    Statement st = conn.createStatement();
    String createTableDDL = null;

    if (isReplicated) {
      createTableDDL = "CREATE TABLE TEST.TABLE1 (COL1 INT, COL2 INT," +
          " COL3 VARCHAR(10)) REPLICATE PERSISTENT";
    } else {
      createTableDDL = "CREATE TABLE TEST.TABLE1 (COL1 INT, COL2 INT," +
          " COL3 VARCHAR(10)) PARTITION BY RANGE (COL1) (VALUES BETWEEN" +
          " 0 AND 50, VALUES BETWEEN 50 AND 100) REDUNDANCY 1";
    }

    st.execute(createTableDDL);
    st.execute("CREATE INDEX TEST.IDX1 ON TEST.TABLE1(COL2)");
    PreparedStatement ps = conn.prepareStatement("INSERT INTO TEST.TABLE1" +
        " VALUES(?, ?, ?)");
    for (int i = 0; i < 100; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setString(3, "" + i);
      ps.addBatch();
    }
    ps.executeBatch();

    ResultSet rs0 = st.executeQuery("select count(*) from test.TABLE1");
    assertTrue(rs0.next());
    assertEquals(100, rs0.getInt(1));
    rs0.close();

    // should not throw exception
    rs0 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST', 'TABLE1')");

    VM serverVM1 = serverVMs.get(0);
    VM serverVM2 = serverVMs.get(1);

    // delete an entry from the index to make it inconsistent with the base table
    serverVM1.invoke(CheckTableDUnit.class, "deleteEntryFromLocalIndex",
        new Object[]{"/TEST/TABLE1", "IDX1"});
    serverVM2.invoke(CheckTableDUnit.class, "deleteEntryFromLocalIndex",
        new Object[]{"/TEST/TABLE1", "IDX1"});
    try {
      ResultSet rs1 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST', " +
          "'TABLE1')");
      fail("SYS.CHECK_TABLE_EX should have thrown an exception");
    } catch (SQLException se1) {
      if (!(se1.getSQLState().equals("X0Y55") || se1.getSQLState().equals("X0Y60"))) {
        throw se1;
      }
    }

    st.execute("DROP INDEX TEST.IDX1");

    // create another index
    st.execute("CREATE INDEX TEST.IDX2 ON TEST.TABLE1(COL2)");
    // update an entry in index and put a incorrect value to make index
    // inconsistent with the base table
    serverVM1.invoke(CheckTableDUnit.class, "updateEntryInLocalIndex",
        new Object[]{"/TEST/TABLE1", "IDX2"});
    serverVM2.invoke(CheckTableDUnit.class, "updateEntryInLocalIndex",
        new Object[]{"/TEST/TABLE1", "IDX2"});
    try {
      ResultSet rs1 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST'," +
          " 'TABLE1')");
      fail("SYS.CHECK_TABLE_EX should have thrown an exception");
    } catch (SQLException se2) {
      if (!se2.getSQLState().equals("X0X61")) {
        throw se2;
      }
    }

    st.execute("DROP TABLE TEST.TABLE1");
  }

  public static void logLocalIndexSize(String regionPath, String index) {
    LocalRegion r = (LocalRegion)Misc.getRegion(regionPath, false, false);
    GfxdIndexManager sqlim = (GfxdIndexManager)r.getIndexUpdater();
    List<GemFireContainer> list = sqlim.getAllIndexes();
    getGlobalLogger().info(
        "list of index containers are: " + Arrays.toString(list.toArray()));
    for (GemFireContainer gfc : list) {
      if (((String)gfc.getName()).contains(index)) {
        getGlobalLogger().info("size of the index is " + gfc.getSkipListMap().size());
      }
    }
  }

  // deletes an entry from the local index to make it inconsistent with the
  // base table
  public static void deleteEntryFromLocalIndex(String regionPath, String index) {
    LocalRegion r = (LocalRegion)Misc.getRegion(regionPath, false, false);
    GfxdIndexManager sqlim = (GfxdIndexManager)r.getIndexUpdater();
    List<GemFireContainer> list = sqlim.getAllIndexes();
    getGlobalLogger().info(
        "list of index containers are: " + Arrays.toString(list.toArray()));
    for (GemFireContainer gfc : list) {
      if (((String)gfc.getName()).contains(index)) {
        // just delete any entry
        Iterator<Object> keys = gfc.getSkipListMap().keySet().iterator();
        Object k = keys.next();
        gfc.getSkipListMap().remove(k);
      }
    }
  }

  // updates an entry in the local index such that value associated with a
  // key is incorrect
  public static void updateEntryInLocalIndex(String regionPath, String index) {
    LocalRegion r = (LocalRegion)Misc.getRegion(regionPath, false, false);
    GfxdIndexManager sqlim = (GfxdIndexManager)r.getIndexUpdater();
    List<GemFireContainer> list = sqlim.getAllIndexes();
    getGlobalLogger().info(
        "list of index containers are: " + Arrays.toString(list.toArray()));
    for (GemFireContainer gfc : list) {
      if (((String)gfc.getName()).contains(index)) {
        // just corrupt an entry
        Map.Entry first = gfc.getSkipListMap().firstEntry();
        Map.Entry last = gfc.getSkipListMap().lastEntry();

        getGlobalLogger().info("e1 =" + first + " e2 =" + last);
        gfc.getSkipListMap().put(first.getKey(), last.getValue());
      }
    }
  }

  public void testGlobalIndexConsistency() throws Exception {
    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);

    Connection conn = TestUtil.getNetConnection(netPort1, null, null);

    // pk index
    checkGlobalIndexConsistency(conn, false);
    // unique index
    checkGlobalIndexConsistency(conn, true);

    // composite pk
    //checkGlobalIndexConsistencyCompositeKey(conn);
  }

  private void checkGlobalIndexConsistency(Connection conn,
      boolean isUniqueIndex) throws SQLException {
    Statement st = conn.createStatement();
    String createTableDDL = null;

    if (!isUniqueIndex) {
      // primary key global index
      createTableDDL = "CREATE TABLE TEST.TABLE1 (COL1 INT, COL2 INT " +
          "PRIMARY KEY" +
          ", COL3 VARCHAR(10)) PARTITION BY RANGE (COL1) " +
          "(VALUES BETWEEN" +
          " 0 AND 50, VALUES BETWEEN 50 AND 100) REDUNDANCY 1";
    } else {
      // unique key global index
      createTableDDL = "CREATE TABLE TEST.TABLE1 (COL1 INT PRIMARY KEY, COL2 INT " +
          "NOT NULL UNIQUE" +
          ", COL3 VARCHAR(10)) PARTITION BY RANGE (COL1) " +
          "(VALUES BETWEEN" +
          " 0 AND 50, VALUES BETWEEN 50 AND 100) REDUNDANCY 1";
    }

    st.execute(createTableDDL);
    int numRows = 100;
    PreparedStatement ps = conn.prepareStatement("INSERT INTO TEST.TABLE1" +
        " VALUES(?, ?, ?)");
    for (int i = 0; i < numRows; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setString(3, "" + i);
      ps.addBatch();
    }
    ps.executeBatch();
    ResultSet rs0 = st.executeQuery("select count(*) from test.TABLE1");
    assertTrue(rs0.next());
    assertEquals(numRows, rs0.getInt(1));
    rs0.close();

    // should not throw exception
    rs0 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST', 'TABLE1')");

//    deleteEntryFromGlobalIndex("TEST/TABLE1", 2);
    VM serverVM1 = serverVMs.get(0);
    VM serverVM2 = serverVMs.get(1);

    // delete an entry from the index to make it inconsistent with the base table
    serverVM1.invoke(CheckTableDUnit.class, "deleteEntryFromGlobalIndex",
        new Object[]{"/TEST/TABLE1", new Object[]{new SQLInteger(2),
            new SQLInteger(90)}});

    try {
      rs0 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST', 'TABLE1')");
      fail("SYS.CHECK_TABLE_EX should have thrown an exception");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("X0X64")) {
        throw se;
      }
    }

    st.execute("DROP TABLE TEST.TABLE1");
    st.execute(createTableDDL);
    for (int i = 0; i < numRows; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setString(3, "" + i);
      ps.addBatch();
    }
    ps.executeBatch();

    // insert an entry into the global index region but not in the base region
    serverVM1.invoke(CheckTableDUnit.class, "insertEntryIntoGlobalIndex",
        new Object[]{"/TEST/TABLE1", 55555, "somevalue"});

    try {
      rs0 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST', 'TABLE1')");
      fail("SYS.CHECK_TABLE_EX should have thrown an exception");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("X0Y55")) {
        throw se;
      }
    }

    st.execute("DROP TABLE TEST.TABLE1");
  }


  private void checkGlobalIndexConsistencyCompositeKey(Connection conn)
      throws SQLException {
    Statement st = conn.createStatement();
    String createTableDDL = null;

    // primary key global index
    createTableDDL = "CREATE TABLE TEST.TABLE_CK (COL1 INT, COL2 INT" +
        ", COL3 VARCHAR(10), CONSTRAINT P_KEY PRIMARY KEY (COL2, COL3)) " +
        "PARTITION BY RANGE (COL1) (VALUES BETWEEN" +
        " 0 AND 50, VALUES BETWEEN 50 AND 100) REDUNDANCY 1";

    st.execute(createTableDDL);
    PreparedStatement ps = conn.prepareStatement("INSERT INTO TEST.TABLE_CK" +
        " VALUES(?, ?, ?)");
    for (int i = 0; i < 100; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setString(3, "" + i);
      ps.addBatch();
    }
    ps.executeBatch();
    ResultSet rs0 = st.executeQuery("select count(*) from test.TABLE_CK");
    assertTrue(rs0.next());
    assertEquals(100, rs0.getInt(1));
    rs0.close();

    // should not throw exception
    rs0 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST', 'TABLE_CK')");

    VM serverVM1 = serverVMs.get(0);

    DataValueDescriptor[] dvds1 = new DataValueDescriptor[] {new SQLInteger((Integer)2),
        new SQLVarchar("2")};
    CompositeRegionKey key1 = new CompositeRegionKey(dvds1);
    DataValueDescriptor[] dvds2 = new DataValueDescriptor[] { new SQLInteger((Integer)90),
        new SQLVarchar("90")};
    CompositeRegionKey key2 = new CompositeRegionKey(dvds2);

    // delete an entry from the index to make it inconsistent with the base table
    serverVM1.invoke(CheckTableDUnit.class, "deleteEntryFromGlobalIndex",
        new Object[]{"/TEST/TABLE_CK", new Object[]{key1, key2}});

    try {
      rs0 = st.executeQuery("VALUES SYS.CHECK_TABLE_EX('TEST', 'TABLE_CK')");
      fail("SYS.CHECK_TABLE_EX should have thrown an exception");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("X0X64")) {
        throw se;
      }
    }
    st.execute("DROP TABLE TEST.TABLE_CK");
  }


  // deletes an entry from the global index to make it inconsistent with the
  // base table
  public static void deleteEntryFromGlobalIndex(String regionPath, Object[] keys) {
    LocalRegion r = (LocalRegion)Misc.getRegion(regionPath, false, false);
    GfxdIndexManager sqlim = (GfxdIndexManager)r.getIndexUpdater();
    List<GemFireContainer> list = sqlim.getIndexContainers();
    getGlobalLogger().info(
        "list of index containers are: " + Arrays.toString(list.toArray()));
    for (GemFireContainer gfc : list) {
      if (gfc.isGlobalIndex()) {
        getGlobalLogger().info("global index region is =" + gfc.getRegion().getDisplayName());
        for (Object key : keys) {
//          DataValueDescriptor[] dvds = new DataValueDescriptor[]{new SQLInteger((Integer)key)};
//          CompositeRegionKey k = new CompositeRegionKey(dvds);
//          gfc.getRegion().destroy(k);
          gfc.getRegion().destroy(key);
        }
      }
    }
  }

  public static void insertEntryIntoGlobalIndex(String regionPath, Object key, Object value) {
    LocalRegion r = (LocalRegion)Misc.getRegion(regionPath, false, false);
    GfxdIndexManager sqlim = (GfxdIndexManager)r.getIndexUpdater();
    List<GemFireContainer> list = sqlim.getIndexContainers();
    getGlobalLogger().info(
        "list of index containers are: " + Arrays.toString(list.toArray()));
    for (GemFireContainer gfc : list) {
      if (gfc.isGlobalIndex()) {
        DataValueDescriptor[] dvds = new DataValueDescriptor[] { new SQLInteger((Integer)key) };
        CompositeRegionKey k = new CompositeRegionKey(dvds);
        gfc.getRegion().put(k, value);
      }
    }
  }
}
