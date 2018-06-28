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
package com.pivotal.gemfirexd.jdbc.offheap;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import javax.sql.rowset.serial.SerialClob;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.offheap.OffHeapMemoryStats;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.RowLoader;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.offheap.CollectionBasedOHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRegionEntryUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.VisitorAdaptor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.*;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialBlob;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.compile.IndexToBaseRowNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import com.pivotal.gemfirexd.internal.impl.sql.execute.*;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils.Context;
import com.pivotal.gemfirexd.jdbc.GfxdCallbacksTest;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;
import udtexamples.UDTPrice;

/**
 * 
 * @author asif
 * 
 */
public class OffHeapTest extends JdbcTestBase {

  private RegionMapClearDetector rmcd = null;	
  public OffHeapTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(OffHeapTest.class));
  }

  @Override
  public String reduceLogging() {
    return "config";
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();   
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"true");
    
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
    rmcd = new JdbcTestBase.RegionMapClearDetector();
    CacheObserverHolder.setInstance(rmcd);
    GemFireXDQueryObserverHolder.putInstance(rmcd);
  }
  
  @Override
  public void tearDown() throws Exception {
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
	CacheObserverHolder.setInstance(null);
	GemFireXDQueryObserverHolder.clearInstance();  
    super.tearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
    
  }

  public void testOffHeapMemoryReleaseWithoutLobsForInsertDeletes()
      throws Exception {

    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
    ResultSet rs = null;
    st.execute("create table trade.securities (sec_id int not null, exchange varchar(10) not null,  tid int, constraint sec_pk primary key (sec_id), "
        + " constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate offheap");
    OffHeapMemoryStats stats = SimpleMemoryAllocatorImpl.getAllocator()
        .getStats();
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();
    final long freeMemBeforeInsert = stats.getFreeMemory();
    final long refCountBeforeInserts = JDBC.getTotalRefCount(ma);
    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?)");
    final int sec_limit = 1;
    for (int i = 0; i < sec_limit; ++i) {
      psSec.setInt(1, i);
      psSec.setString(2, exchanges[i % exchanges.length]);
      psSec.setInt(3, 1);
      psSec.executeUpdate();
    }
    // get the free memory :
    long freeMemAfterInserts = stats.getFreeMemory();
    long refCountAfterInserts = JDBC.getTotalRefCount(ma);
    assertTrue(refCountBeforeInserts != refCountAfterInserts);
    assertTrue(freeMemBeforeInsert != freeMemAfterInserts);
    // Execute a scan query
    rs = st.executeQuery("select * from trade.securities");
    int numRows = 0;

    while (rs.next()) {
     rs.getInt(1);
     rs.getString(2);
      ++numRows;
    }
    assertEquals(sec_limit, numRows);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInserts);
    rs = st.executeQuery("select * from trade.securities order by sec_id");
    numRows = 0;

    while (rs.next()) {
      assertEquals(exchanges[numRows % exchanges.length], rs.getString(2));
      ++numRows;
    }
    assertEquals(sec_limit, numRows);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInserts);
    int numDeletes = st.executeUpdate("delete from trade.securities");
    assertEquals(sec_limit, numDeletes);
    assertEquals(freeMemBeforeInsert, stats.getFreeMemory());
  }

  public void testOffHeapMemoryReleaseLobsForInsertDeletes() throws Exception {

    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), "
        + "companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, "
        + "logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange)) offheap");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    OffHeapMemoryStats stats = SimpleMemoryAllocatorImpl.getAllocator()
        .getStats();
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();
    final long refCountBeforeInsert = JDBC.getTotalRefCount(ma);
    final long freeSizeBeforeInserts = stats.getFreeMemory();
    DataGenerator dg = new DataGenerator();
    dg.insertIntoCompanies(psComp, "SYMB1", "NSE",0);
    dg.insertIntoCompanies(psComp, "SYMB2", "BSE",0);
    dg.insertIntoCompanies(psComp, "SYMB3", "LSE",0);
    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);
    // Execute a scan query
    rs = st.executeQuery("select * from trade.companies");
    int numRows = 0;
    while (rs.next()) {
      rs.getString(1);
      ++numRows;
    }
    assertEquals(3, numRows);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);
    int numDeletes = st.executeUpdate("delete from trade.companies");
    assertEquals(3, numDeletes);
    assertEquals(freeSizeBeforeInserts, stats.getFreeMemory());
  }

  public void testOffHeapMemoryReleaseNoLobsForUpdate() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();

    String exchanges[] = { "nasdaq", "nye", "amex", "lse", "fse", "hkse", "tse" };
    ResultSet rs = null;
    st.execute("create table trade.securities (sec_id int not null, exchange varchar(10) not null,  "
        + "tid int, constraint sec_pk primary key (sec_id), "
        + " constraint exc_ch check (exchange in "
        + "('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate offheap");
    OffHeapMemoryStats stats = SimpleMemoryAllocatorImpl.getAllocator()
        .getStats();
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();

    final long freeSizeBeforeInserts = stats.getFreeMemory();
    final long refCountBeforeInsert = JDBC.getTotalRefCount(ma);

    PreparedStatement psSec = conn
        .prepareStatement("insert into trade.securities values (?, ?, ?)");
    final int sec_limit = 1;
    for (int i = 0; i < sec_limit; ++i) {
      psSec.setInt(1, i);
      psSec.setString(2, exchanges[i % exchanges.length]);
      psSec.setInt(3, 1);
      psSec.executeUpdate();
    }
    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);

    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);
    // Execute a scan query
    rs = st.executeQuery("select * from trade.securities");
    int numRows = 0;
    while (rs.next()) {
      rs.getInt(1);
      ++numRows;
    }
    assertEquals(sec_limit, numRows);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);

    int numUpdate = st.executeUpdate("update trade.securities set tid = 5");
    assertEquals(1, numUpdate);
    assertEquals(refCountAfterInsert, JDBC.getTotalRefCount(ma));

    int numDeletes = st.executeUpdate("delete from trade.securities");
    assertEquals(sec_limit, numDeletes);
    assertEquals(freeSizeBeforeInserts, stats.getFreeMemory());
  }

  public void testOffHeapMemoryUpdatesForLobTables_1() throws Exception {

    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), "
        + "companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, "
        + "logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange)) offheap");
    
    st.execute("create index company_tid on trade.companies(tid)");
    //st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    OffHeapMemoryStats stats = SimpleMemoryAllocatorImpl.getAllocator()
        .getStats();
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();

    final long freeSizeBeforeInserts = stats.getFreeMemory();
    final long refCountBeforeInsert = JDBC.getTotalRefCount(ma);
   for(int i = 1; i < 100; i+=3) {
    DataGenerator dg = new DataGenerator();
    dg.insertIntoCompanies(psComp, "SYMB"+i, "NSE",0);
    dg.insertIntoCompanies(psComp, "SYMB2"+(i+1), "BSE",0);
    dg.insertIntoCompanies(psComp, "SYMB3"+(i+2), "LSE",0);
   }
    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);

    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);

    // Execute a scan query
    rs = st.executeQuery("select * from trade.companies where companyname is not null ");
    int numRows = 0;
    int numCols = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      for(int i =0; i < numCols;++i)
      rs.getObject(i+1);
      ++numRows;
    }
    assertEquals(99, numRows);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);

    int numUpdate = st
        .executeUpdate("update trade.companies set companyname = 'xyzzz'");
    assertEquals(99, numUpdate);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);

    
   
    
    rs = st.executeQuery("select * from trade.companies");

    rs.next();
    StringBuilder sb = new StringBuilder(100);
    sb.append("xyzzz");
    for (int i = 0; i < 95; ++i) {
      sb.append(' ');
    }
    assertEquals(sb.toString(), rs.getString(6));
   // assertEquals("SYMB1", rs.getString(1));

    numUpdate = st
        .executeUpdate("update trade.companies set companyname = 'zzz'");
    assertEquals(99, numUpdate);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);
    
    rs = st.executeQuery("select * from trade.companies where " +
    		" tid = 10");
    numRows = 0;
    numCols = rs.getMetaData().getColumnCount();
    if (rs.next()) {
      for(int i =0; i < numCols;++i)
      rs.getObject(i+1);
      ++numRows;
    }
    assertEquals(1,numRows);
    
    int numDeletes = st.executeUpdate("delete from trade.companies");
    assertEquals(99, numDeletes);
    assertEquals(freeSizeBeforeInserts, stats.getFreeMemory());    
  }
  
  public void testLobMemoryReleaseOnRegionClose() throws Exception {
    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), "
        + "companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, "
        + "logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange)) offheap");

    st.execute("create index company_tid on trade.companies(tid)");
    // st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    OffHeapMemoryStats stats = SimpleMemoryAllocatorImpl.getAllocator()
        .getStats();
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();

    final long freeSizeBeforeInserts = stats.getFreeMemory();
    final long refCountBeforeInsert = JDBC.getTotalRefCount(ma);
    for (int i = 1; i < 2; ++i) {
      DataGenerator dg = new DataGenerator();
      dg.insertIntoCompanies(psComp, "SYMB" + i, "NSE",0);
      
    }
    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);

    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);
    
   int numUpdate = st
        .executeUpdate("update trade.companies set companyname = 'xyzzz'");
    assertEquals(1, numUpdate);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);

    numUpdate = st
        .executeUpdate("update trade.companies set companyname = 'zzz'");
    assertEquals(1, numUpdate);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);
  }

  public void testOffHeapMemoryReleaseOnUniqueConstraintViolation()
      throws Exception {

    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    s.execute("create table EMP.PARTITIONTESTTABLE (ID int not null, "
        + " SECONDID int not null, THIRD int not null, FOURTH int not null, FIFTH int not null,"
        + " primary key (ID, SECONDID))" + " PARTITION BY COLUMN (ID)"
        + "offheap");
    s.execute("create unique index third_index on EMP.PARTITIONTESTTABLE (THIRD)");
    // s.execute("create index fourth_index on EMP.PARTITIONTESTTABLE (FOURTH)");
    // s.execute("create unique index fifth_index on EMP.PARTITIONTESTTABLE (fifth)");

    s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(1,2,3,4, 10)");
    s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(4,5,6,4, 11)");
    // s.execute("INSERT INTO EMP.PARTITIONTESTTABLE values(7,8,9,4, 12)");
    addExpectedException(EntryExistsException.class);
    try {
      s.execute("UPDATE EMP.PARTITIONTESTTABLE SET third=6, fifth=20 where id=1 and secondid=2");
      fail("Exception is expected!");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    s.execute("delete from EMP.PARTITIONTESTTABLE  where id=1 and secondid=2");
    this.doOffHeapValidations();
    // drop the table
    s.execute("drop table EMP.PARTITIONTESTTABLE");
    rmcd.waitTillAllClear();
    // drop schema and shutdown
    s.execute("drop schema EMP RESTRICT");
    this.doOffHeapValidations();

  }
  
  public void testLobCols() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    int lobColCount = 900;
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    String tableDef = "create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), "
        + "companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, "
        + "logo varchar(100) for bit data, tid int,";
    // add 2000 lob cols more
    for (int i = 0; i < lobColCount; ++i) {
      tableDef += " lob" + i + " varchar(100) for bit data, ";
    }
    tableDef += " constraint comp_pk primary key (symbol, exchange)) offheap";
    st.execute(tableDef);
    String insertStatement = "insert into trade.companies (symbol, exchange, companytype,"
        + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid";
    for (int i = 0; i < lobColCount; ++i) {
      insertStatement += ",lob" + i;
    }
    insertStatement += ") values(";
    for (int i = 0; i < (12 + lobColCount); ++i) {
      insertStatement += "?,";
    }
    insertStatement = insertStatement
        .substring(0, insertStatement.length() - 1);
    insertStatement += ")";
    PreparedStatement psComp = conn.prepareStatement(insertStatement);
    OffHeapMemoryStats stats = SimpleMemoryAllocatorImpl.getAllocator()
        .getStats();
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();
    final long refCountBeforeInsert = JDBC.getTotalRefCount(ma);
    final long freeSizeBeforeInserts = stats.getFreeMemory();
    DataGenerator dg = new DataGenerator();
    dg.insertIntoCompanies(psComp, "SYMB1", "NSE", lobColCount);
    dg.insertIntoCompanies(psComp, "SYMB2", "BSE", lobColCount);
    dg.insertIntoCompanies(psComp, "SYMB3", "LSE", lobColCount);
    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);
    // Execute a scan query
    rs = st.executeQuery("select * from trade.companies");
    int numRows = 0;
    while (rs.next()) {
      for (int i = 12; i < lobColCount + 12; ++i) {
        byte[] bytes = rs.getBytes(i + 1);
        ByteBuffer buff = ByteBuffer.wrap(bytes);
        assertEquals(i - 12, buff.getInt());
      }
      ++numRows;
    }
    assertEquals(3, numRows);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);
    int numDeletes = st.executeUpdate("delete from trade.companies");
    assertEquals(3, numDeletes);
    assertEquals(freeSizeBeforeInserts, stats.getFreeMemory());
  }

  public void testOffHeapMemoryReleaseOnForeignkeyConstraintViolation()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null, "
        + "primary key(id), foreign key (fkId) references t2(id))"
        + "replicate offheap");
    // fk exists
    rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(2)");

    rowCount += s.executeUpdate("insert into t1 values(1, 1)");
    rowCount += s.executeUpdate("update t1 set fkId =2 ");

    // fk doesn't exist, should fail
    try {
      s.executeUpdate("update t1 set fkId =3 where id =1");
      fail("should have thrown an SQLException");
    } catch (SQLException ex) {
      if (!"23503".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    s.executeUpdate("delete from t1 where id = 1");

    conn.commit();
    s.close();
  }

  public void testOptimizedOHAddressCacheForUpdateNoIndexes() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null,"
        + "col1 int not null unique, "
        + "primary key(id), foreign key (fkId) references t2(id))"
        + "replicate offheap");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t2 values(2)");
    rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(3)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3, 2,3)");
    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void onUpdateResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            try {
              conditionResultSet((UpdateResultSet) resultset);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

        });

    rowCount += s.executeUpdate("update t1 set fkId =3 ");

    // s.executeUpdate("update t1 set col1 = col1 +1 ");
    s.executeUpdate("update t1 set col1 = col1 *3");

    s.executeUpdate("delete from t1 where id = 2");

    conn.commit();
    s.close();
  }

  // no primary key
  public void testOptimizedOHAddressCacheForUpdateNoPK_1() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null,"
        + "col1 int not null , " + " foreign key (fkId) references t2(id))"
        + "replicate offheap");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t2 values(2)");
    rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(3)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3, 2,3)");
    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void onUpdateResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            try {
              conditionResultSet((UpdateResultSet) resultset);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

        });

    rowCount += s.executeUpdate("update t1 set ID = 5,fkId =3, col1 = 7 ");

    // s.executeUpdate("update t1 set col1 = col1 +1 ");
    s.executeUpdate("update t1 set col1 = col1 *3");

    s.executeUpdate("delete from t1 where id = 2");

    conn.commit();
    s.close();
  }

  public void testOptimizedOHAddressCacheForUpdateNoPK_2() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null,"
        + "col1 int not null unique , "
        + " foreign key (fkId) references t2(id))" + "replicate offheap");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t2 values(2)");
    rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(3)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3, 2,3)");
    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void onUpdateResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            try {
              conditionResultSet((UpdateResultSet) resultset);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

        });

    rowCount += s
        .executeUpdate("update t1 set ID = 5,fkId =3, col1 = col1 *7 ");

    // s.executeUpdate("update t1 set col1 = col1 +1 ");
    s.executeUpdate("update t1 set col1 = col1 *3");

    s.executeUpdate("delete from t1 where id = 2");

    conn.commit();
    s.close();
  }

  public void testOptimizedOHAddressCacheForNonDeferrredDelete()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null,"
        + "col1 int  , " + " foreign key (fkId) references t2(id))"
        + "replicate offheap");
    s.execute("create index t1_col1 on t1(col1)");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t2 values(2)");
    rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(3)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3, 2,3)");
    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void onDeleteResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            try {
              conditionResultSet((DeleteResultSet) resultset);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

        });

    s.executeUpdate("delete from t1 where col1 = 2");

    conn.commit();
    s.close();
  }

  public void testOptimizedOHAddressCacheForUpdate() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null,"
        + "col1 int  , " + " foreign key (fkId) references t2(id))"
        + "replicate offheap");
    s.execute("create index t1_col1 on t1(col1)");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t2 values(2)");
    rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(3)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3, 2,3)");
    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void onUpdateResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            try {
              conditionResultSet((UpdateResultSet) resultset);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

        });

    rowCount += s.executeUpdate("update t1 set ID = 5,fkId =3, col1 = 7 ");

    // s.executeUpdate("update t1 set col1 = col1 +1 ");
    s.executeUpdate("update t1 set col1 = col1 *3");

    s.executeUpdate("delete from t1 where id = 2");

    conn.commit();
    s.close();
  }

  public void testOffHeapOHAddressCacheForDeferredDeleteRefConstraint()
      throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id), col1 int unique, "
        + "fkId int not null ,  foreign key (fkId) references t2(col1))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null,"
        + "col1 int  , " + " foreign key (fkId) references t2(col1))"
        + "replicate offheap");
    s.execute("create index t1_col1 on t1(col1)");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(1,1,1)");
    rowCount += s.executeUpdate("insert into t2 values(2,2,1)");

    rowCount += s.executeUpdate("insert into t2 values(3,3,1)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3, 2,3)");

    s.executeUpdate("delete from t1 where fkid = 2");
    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          private void reflectAndAssert(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet,
              Class<?> resultsetClass) throws Exception {
            Field sourceField = resultsetClass.getDeclaredField("source");
            sourceField.setAccessible(true);
            ProjectRestrictResultSet psRs = (ProjectRestrictResultSet) sourceField
                .get(resultSet);
           /* psRs = (ProjectRestrictResultSet) psRs.getSource();
            IndexRowToBaseRowResultSet irs = (IndexRowToBaseRowResultSet) psRs
                .getSource();*/
            TableScanResultSet ts = (TableScanResultSet)  psRs.getSource();

            
            Class<?> tableScanClazz = TableScanResultSet.class;
            Field field = tableScanClazz.getDeclaredField("ohAddressCache");
            field.setAccessible(true);
            OHAddressCache addressCache = (OHAddressCache) field.get(ts);
            Class<? extends OHAddressCache> addressCacheClass = TableScanResultSet.SingleOHAddressCache.class;
            assertEquals(addressCache.getClass(), addressCacheClass);

          }


          @Override
          public void onDeleteResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            try {
              this.reflectAndAssert(resultset, DeleteResultSet.class);
              populateIndexKeys((DeleteResultSet) resultset);
              
              rowLocToBytes = conditionResultSet((DeleteResultSet) resultset);

            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

          private Map<RowLocation, byte[]> rowLocToBytes;
          private List<CompactCompositeIndexKey> indexKeys = new ArrayList<CompactCompositeIndexKey>();
          
          @Override
          public void onDeleteResultSetOpenBeforeRefChecks(com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
            try {
           // The addresses of the entry have been released , we need to
              // reallocate
              Iterator<Map.Entry<RowLocation, byte[]>> iter = this.rowLocToBytes
                  .entrySet().iterator();
              while (iter.hasNext()) {
                Map.Entry<RowLocation, byte[]> entry = iter.next();
                RowLocation rowLoc = entry.getKey();
                Chunk chunk = (Chunk) OffHeapRegionEntryUtils
                    .prepareValueForCreate(null, entry.getValue(), false);

                Iterator<CompactCompositeIndexKey> keyIters = indexKeys
                    .iterator();
                List<OffHeapByteSource> matchedOHBS = new ArrayList<OffHeapByteSource>();
                while (keyIters.hasNext()) {
                  CompactCompositeIndexKey ccik = keyIters.next();
                  Class<?> compactCompositeKeyClass = CompactCompositeKey.class;
                  Field valueBytesField = compactCompositeKeyClass
                      .getDeclaredField("valueBytes");
                  valueBytesField.setAccessible(true);
                  Object valueBytes = valueBytesField.get(ccik);
                  if (valueBytes instanceof OffHeapByteSource) {
                    OffHeapByteSource obs = (OffHeapByteSource) valueBytes;
                    long address = obs.getMemoryAddress();
                    long oldAddress = ((OffHeapRegionEntry) rowLoc)
                        .getAddress();
                    if (address == oldAddress) {
                      matchedOHBS.add(obs);
                      keyIters.remove();

                    }

                  }

                }
                if (!matchedOHBS.isEmpty()) {
                  Class<?> chunkClass = Chunk.class;
                  Field memoryAddressField = chunkClass
                      .getDeclaredField("memoryAddress");
                  memoryAddressField.setAccessible(true);
                  for (OffHeapByteSource ohbs : matchedOHBS) {
                    memoryAddressField.setLong(ohbs, chunk.getMemoryAddress());
                  }
                }

                Class<?> entryClass = rowLoc.getClass();
                Field ohAddressField = entryClass.getDeclaredField("ohAddress");
                ohAddressField.setAccessible(true);
                ohAddressField.setLong(rowLoc, chunk.getMemoryAddress());
              }
              

            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }
         
              
          public void populateIndexKeys(DeleteResultSet resultset) {
            try {
              Class updateResultSetClass = DeleteResultSet.class;
              Field containerField = updateResultSetClass
                  .getDeclaredField("container");
              containerField.setAccessible(true);
              GemFireContainer container = (GemFireContainer) containerField
                  .get(resultset);
              List<GemFireContainer> containers = container.getIndexManager()
                  .getAllIndexes();
              for (GemFireContainer idxCont : containers) {

                ConcurrentSkipListMap<Object, Object> map = idxCont
                    .getSkipListMap();
                if (map != null) {
                  Iterator<Object> keys = map.keySet().iterator();
                  while (keys.hasNext()) {
                    Object key = keys.next();
                    if (key instanceof CompactCompositeIndexKey) {
                      indexKeys.add((CompactCompositeIndexKey) key);
                    }
                  }
                }

              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }

        });

    s.executeUpdate("delete from t2 where col1 = 2");

    conn.commit();
    s.close();
  }

  public void testOffHeapOHAddressCacheForDeferredDeleteWithSubquery()
      throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id), col1 int unique)"
        + "OFFHEAP");

    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");
    rowCount += s.executeUpdate("insert into t2 values(1,1)");
    rowCount += s.executeUpdate("insert into t2 values(2,2)");

    rowCount += s.executeUpdate("insert into t2 values(3,3)");

    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          private void reflectAndAssert(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet,
              Class<?> resultsetClass) throws Exception {
            Field sourceField = resultsetClass.getDeclaredField("source");
            sourceField.setAccessible(true);
            ProjectRestrictResultSet psRs = (ProjectRestrictResultSet) sourceField
                .get(resultSet);
            JoinResultSet jrs = (JoinResultSet) psRs.getSource();
            TableScanResultSet ts = (TableScanResultSet) jrs.leftResultSet;
            Class<?> tableScanClazz = TableScanResultSet.class;
            Field field = tableScanClazz.getDeclaredField("ohAddressCache");
            field.setAccessible(true);
            OHAddressCache addressCache = (OHAddressCache) field.get(ts);
            Class<? extends OHAddressCache> addressCacheClass = TableScanResultSet.SingleOHAddressCache.class;
            assertEquals(addressCache.getClass(), addressCacheClass);

          }

          @Override
          public void onDeleteResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            try {
              this.reflectAndAssert(resultset, DeleteResultSet.class);
              populateIndexKeys((DeleteResultSet) resultset);
              rowLocToBytes = conditionResultSet((DeleteResultSet) resultset);

            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

          private Map<RowLocation, byte[]> rowLocToBytes;
          private List<CompactCompositeIndexKey> indexKeys = new ArrayList<CompactCompositeIndexKey>();

          @Override
          public void beforeDeferredDelete() {
            try {
              // The addresses of the entry have been released , we need to
              // reallocate
              Iterator<Map.Entry<RowLocation, byte[]>> iter = this.rowLocToBytes
                  .entrySet().iterator();
              while (iter.hasNext()) {
                Map.Entry<RowLocation, byte[]> entry = iter.next();
                RowLocation rowLoc = entry.getKey();
                Chunk chunk = (Chunk) OffHeapRegionEntryUtils
                    .prepareValueForCreate(null, entry.getValue(), false);

                Iterator<CompactCompositeIndexKey> keyIters = indexKeys
                    .iterator();
                List<OffHeapByteSource> matchedOHBS = new ArrayList<OffHeapByteSource>();
                while (keyIters.hasNext()) {
                  CompactCompositeIndexKey ccik = keyIters.next();
                  Class<?> compactCompositeKeyClass = CompactCompositeKey.class;
                  Field valueBytesField = compactCompositeKeyClass
                      .getDeclaredField("valueBytes");
                  valueBytesField.setAccessible(true);
                  Object valueBytes = valueBytesField.get(ccik);
                  if (valueBytes instanceof OffHeapByteSource) {
                    OffHeapByteSource obs = (OffHeapByteSource) valueBytes;
                    long address = obs.getMemoryAddress();
                    long oldAddress = ((OffHeapRegionEntry) rowLoc)
                        .getAddress();
                    if (address == oldAddress) {
                      matchedOHBS.add(obs);
                      keyIters.remove();

                    }

                  }

                }
                if (!matchedOHBS.isEmpty()) {
                  Class<?> chunkClass = Chunk.class;
                  Field memoryAddressField = chunkClass
                      .getDeclaredField("memoryAddress");
                  memoryAddressField.setAccessible(true);
                  for (OffHeapByteSource ohbs : matchedOHBS) {
                    memoryAddressField.setLong(ohbs, chunk.getMemoryAddress());
                  }
                }

                Class<?> entryClass = rowLoc.getClass();
                Field ohAddressField = entryClass.getDeclaredField("ohAddress");
                ohAddressField.setAccessible(true);
                ohAddressField.setLong(rowLoc, chunk.getMemoryAddress());
              }
              assertTrue(indexKeys.isEmpty());

            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

          public void populateIndexKeys(DeleteResultSet resultset) {
            try {
              Class updateResultSetClass = DeleteResultSet.class;
              Field containerField = updateResultSetClass
                  .getDeclaredField("container");
              containerField.setAccessible(true);
              GemFireContainer container = (GemFireContainer) containerField
                  .get(resultset);
              List<GemFireContainer> containers = container.getIndexManager()
                  .getAllIndexes();
              for (GemFireContainer idxCont : containers) {

                ConcurrentSkipListMap<Object, Object> map = idxCont
                    .getSkipListMap();
                if (map != null) {
                  Iterator<Object> keys = map.keySet().iterator();
                  while (keys.hasNext()) {
                    Object key = keys.next();
                    if (key instanceof CompactCompositeIndexKey) {
                      indexKeys.add((CompactCompositeIndexKey) key);
                    }
                  }
                }

              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }

        });

    s.executeUpdate("delete from t2 where Col1 IN (select COL1 from t2 where ID in (1,2) )");

    conn.commit();
    s.close();
  }

  public void testOptimizedOHAddressCacheForDeferredUpdate() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    conn.setAutoCommit(true);

    int rowCount = 0;
    s.executeUpdate("create table t1(id int not null unique, " + "col1 int  "
        + ")" + "replicate offheap");

    s.executeUpdate("create table t2(id int not null, fkId int not null ,primary key(id),"
        + " foreign key (fkId) references t1(id))" + "OFFHEAP");
    s.execute("create index t1_col1 on t1(col1)");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t1 values(1, 1)");
    rowCount += s.executeUpdate("insert into t1 values(2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3,2)");

    GemFireXDQueryObserverHolder
        .putInstance(new GemFireXDQueryObserverAdapter() {
          private Map<RowLocation, byte[]> rowLocToBytes;
          private List<CompactCompositeIndexKey> indexKeys = new ArrayList<CompactCompositeIndexKey>();

          @Override
          public void onUpdateResultSetOpen(
              com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
            populateIndexKeys((UpdateResultSet) resultset);
            try {
              rowLocToBytes = conditionResultSet((UpdateResultSet) resultset);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

          @Override
          public void beforeDeferredUpdate() {
            try {
              // The addresses of the entry have been released , we need to
              // reallocate
              Iterator<Map.Entry<RowLocation, byte[]>> iter = this.rowLocToBytes
                  .entrySet().iterator();
              while (iter.hasNext()) {
                Map.Entry<RowLocation, byte[]> entry = iter.next();
                RowLocation rowLoc = entry.getKey();
                Chunk chunk = (Chunk) OffHeapRegionEntryUtils
                    .prepareValueForCreate(null, entry.getValue(), false);

                Iterator<CompactCompositeIndexKey> keyIters = indexKeys
                    .iterator();
                List<OffHeapByteSource> matchedOHBS = new ArrayList<OffHeapByteSource>();
                while (keyIters.hasNext()) {
                  CompactCompositeIndexKey ccik = keyIters.next();
                  Class<?> compactCompositeKeyClass = CompactCompositeKey.class;
                  Field valueBytesField = compactCompositeKeyClass
                      .getDeclaredField("valueBytes");
                  valueBytesField.setAccessible(true);
                  Object valueBytes = valueBytesField.get(ccik);
                  if (valueBytes instanceof OffHeapByteSource) {
                    OffHeapByteSource obs = (OffHeapByteSource) valueBytes;
                    long address = obs.getMemoryAddress();
                    long oldAddress = ((OffHeapRegionEntry) rowLoc)
                        .getAddress();
                    if (address == oldAddress) {
                      matchedOHBS.add(obs);
                      keyIters.remove();

                    }

                  }

                }
                if (!matchedOHBS.isEmpty()) {
                  Class<?> chunkClass = Chunk.class;
                  Field memoryAddressField = chunkClass
                      .getDeclaredField("memoryAddress");
                  memoryAddressField.setAccessible(true);
                  for (OffHeapByteSource ohbs : matchedOHBS) {
                    memoryAddressField.setLong(ohbs, chunk.getMemoryAddress());
                  }
                }

                Class<?> entryClass = rowLoc.getClass();
                Field ohAddressField = entryClass.getDeclaredField("ohAddress");
                ohAddressField.setAccessible(true);
                ohAddressField.setLong(rowLoc, chunk.getMemoryAddress());
              }
              assertTrue(indexKeys.isEmpty());

            } catch (Exception e) {
              throw new RuntimeException(e);
            }

          }

          public void populateIndexKeys(UpdateResultSet resultset) {
            try {
              Class updateResultSetClass = UpdateResultSet.class;
              Field containerField = updateResultSetClass
                  .getDeclaredField("container");
              containerField.setAccessible(true);
              GemFireContainer container = (GemFireContainer) containerField
                  .get(resultset);
              List<GemFireContainer> containers = container.getIndexManager()
                  .getAllIndexes();
              for (GemFireContainer idxCont : containers) {

                ConcurrentSkipListMap<Object, Object> map = idxCont
                    .getSkipListMap();
                Iterator<Object> keys = map.keySet().iterator();
                while (keys.hasNext()) {
                  Object key = keys.next();
                  if (key instanceof CompactCompositeIndexKey) {
                    indexKeys.add((CompactCompositeIndexKey) key);
                  }
                }

              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }

        });
    rowCount += s.executeUpdate("insert into t2 values(1,2)");
    rowCount += s.executeUpdate("insert into t2 values(2,2)");
    rowCount += s.executeUpdate("insert into t2 values(3,2)");

    // rowCount += s.executeUpdate("update t1 set ID = 5 where ID =1 ");

    s.executeUpdate("update t1 set col1 = col1 +1 ");
    s.executeUpdate("update t1 set col1 = col1 *3");

    conn.commit();
    s.close();
  }
  
  public void _testOrphanInHA() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t1(id int not null unique, " + "col1 int  "
        + ")" + "replicate offheap");

    s.executeUpdate("create table t2(id int not null, fkId int not null ,primary key(id),"
        + " foreign key (fkId) references t1(id))" + "OFFHEAP");
    s.execute("create index t1_col1 on t1(col1)");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t1 values(1, 1)");
    rowCount += s.executeUpdate("insert into t1 values(2,2)");
    rowCount += s.executeUpdate("insert into t1 values(3,2)");

    
    rowCount += s.executeUpdate("insert into t2 values(1,2)");
    rowCount += s.executeUpdate("insert into t2 values(2,2)");
    rowCount += s.executeUpdate("insert into t2 values(3,2)");

    // rowCount += s.executeUpdate("update t1 set ID = 5 where ID =1 ");
    // rowCount += s.executeUpdate("update t1 set ID = 5 where ID =1 ");
    GemFireXDQueryObserverHolder
    .putInstance(new GemFireXDQueryObserverAdapter() {
      private Map<RowLocation, byte[]> rowLocToBytes;
      private List<CompactCompositeIndexKey> indexKeys = new ArrayList<CompactCompositeIndexKey>();

      @Override
      public void onUpdateResultSetDoneUpdate(
          com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset) {
        try {
        Thread th = new Thread(new Runnable() {
          @Override 
          public void run() {
            Misc.getGemFireCache().close();            
          }
          
        });
        th.start();
        Thread.sleep(2000);
        }catch(InterruptedException ignore) {          
        }
      }
    });
    s.executeUpdate("update t1 set col1 = col1 +1 ");
   
    conn.commit();
    s.close();
  }

  protected Map<RowLocation, byte[]> conditionResultSet(
      com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultset)
      throws Exception {
    final Map<RowLocation, Long> rowLocToAddressMap = new HashMap<RowLocation, Long>();
    final Map<RowLocation, byte[]> rowLocToBytes = new HashMap<RowLocation, byte[]>();
    final int resultWidth;
    final boolean deferred;
    final boolean isUpdate = resultset instanceof UpdateResultSet;
    final Class<?> clazz;
    if (isUpdate) {
      clazz = UpdateResultSet.class;
      Field resultWidthField = clazz.getDeclaredField("resultWidth");
      resultWidthField.setAccessible(true);
      resultWidth = ((Integer) resultWidthField.get(resultset)).intValue();
      Field deferredField = clazz.getDeclaredField("deferred");
      deferredField.setAccessible(true);
      deferred = ((Boolean) deferredField.get(resultset)).booleanValue();

    } else {
      clazz = DeleteResultSet.class;
      Field constantsField = clazz.getDeclaredField("constants");
      constantsField.setAccessible(true);
      DeleteConstantAction constants = ((DeleteConstantAction) constantsField
          .get(resultset));
      Field deferredField = WriteCursorConstantAction.class
          .getDeclaredField("deferred");
      deferredField.setAccessible(true);
      boolean tempDef = ((Boolean) deferredField.get(constants)).booleanValue();
      deferred = tempDef;
      /*
       * Field fkInfoArrayField = clazz.getDeclaredField("fkInfoArray");
       * fkInfoArrayField.setAccessible(true); FKInfo[] fkInfoArray =
       * ((FKInfo[]) fkInfoArrayField.get(resultset)); if (tempDef &&
       * fkInfoArray == null) { deferred = true; } else { deferred = false; }
       */

      resultWidth = -1;

    }

    Field rowChangerField = isUpdate ? clazz.getDeclaredField("rowChanger")
        : clazz.getDeclaredField("rc");
    rowChangerField.setAccessible(true);
    final RowChanger rowChanger = (RowChanger) rowChangerField.get(resultset);
    RowChanger rowChangerPassThru = new RowChanger() {

      @Override
      public void open(int lockMode) throws StandardException {
        rowChanger.open(lockMode);
      }

      @Override
      public void setRowHolder(TemporaryRowHolder rowHolder) {
        rowChanger.setRowHolder(rowHolder);
      }

      @Override
      public void setIndexNames(String[] indexNames) {
        rowChanger.setIndexNames(indexNames);

      }

      @Override
      public void openForUpdate(boolean[] fixOnUpdate, int lockMode,
          boolean wait) throws StandardException {
        rowChanger.openForUpdate(fixOnUpdate, lockMode, wait);

      }

      @Override
      public void insertRow(ExecRow baseRow) throws StandardException {
        rowChanger.insertRow(baseRow);
      }

      @Override
      public boolean deleteRow(ExecRow baseRow, RowLocation baseRowLocation)
          throws StandardException {
        try {
          Class<?> rowChangerImplClass = RowChangerImpl.class;
          Field iscField = rowChangerImplClass.getDeclaredField("isc");
          iscField.setAccessible(true);
          IndexSetChanger isc = (IndexSetChanger) iscField.get(rowChanger);

          if (isc != null) {
            isc.delete(baseRow, baseRowLocation);
            // set the isc field of original row changer to null
            iscField.set(rowChanger, null);
          }
          if (!deferred) {
            long address = rowLocToAddressMap.get(baseRowLocation);
            Chunk.retain(address);
          }
          boolean result = rowChanger.deleteRow(baseRow, baseRowLocation);
          // reset the field
          if (isc != null) {
            iscField.set(rowChanger, isc);
          }
          return result;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public boolean updateRow(ExecRow oldBaseRow, ExecRow newBaseRow,
          RowLocation baseRowLocation) throws StandardException {
        try {
          Class<?> rowChangerImplClass = RowChangerImpl.class;
          Field iscField = rowChangerImplClass.getDeclaredField("isc");
          iscField.setAccessible(true);
          IndexSetChanger isc = (IndexSetChanger) iscField.get(rowChanger);

          if (isc != null) {
            isc.update(oldBaseRow, newBaseRow, baseRowLocation);
            // set the isc field of original row changer to null
            iscField.set(rowChanger, null);
          }
          if (!deferred) {
            long address = rowLocToAddressMap.get(baseRowLocation);
            Chunk.retain(address);
          }
          boolean result = rowChanger.updateRow(oldBaseRow, newBaseRow,
              baseRowLocation);
          // reset the field
          if (isc != null) {
            iscField.set(rowChanger, isc);
          }
          return result;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public void finish() throws StandardException {
        rowChanger.finish();
      }

      @Override
      public void close() throws StandardException {
        rowChanger.close();
      }

      @Override
      public ConglomerateController getHeapConglomerateController() {

        return rowChanger.getHeapConglomerateController();
      }

      @Override
      public void open(int lockMode, boolean wait) throws StandardException {
        rowChanger.open(lockMode, wait);
      }

    };
    rowChangerField.set(resultset, rowChangerPassThru);

    Field sourceField = clazz.getDeclaredField("source");
    sourceField.setAccessible(true);
    final NoPutResultSet noputResultSet = (NoPutResultSet) sourceField
        .get(resultset);
    NoPutResultSet testResultSetPassThru = new NoPutResultSet() {

      @Override
      public void deleteRowDirectly() throws StandardException {
        noputResultSet.deleteRowDirectly();
      }

      @Override
      public boolean canUpdateInPlace() {
        return noputResultSet.canUpdateInPlace();
      }

      @Override
      public boolean needsToClone() {
        return noputResultSet.needsToClone();
      }

      @Override
      public FormatableBitSet getValidColumns() {
        return noputResultSet.getValidColumns();
      }

      @Override
      public ExecRow getNextRowFromRowSource() throws StandardException {
        return noputResultSet.getNextRowFromRowSource();
      }

      @Override
      public void closeRowSource() {
        noputResultSet.closeRowSource();

      }

      @Override
      public void rowLocation(RowLocation rl) throws StandardException {
        noputResultSet.rowLocation(rl);

      }

      @Override
      public boolean needsRowLocation() {
        return noputResultSet.needsRowLocation();
      }

      @Override
      public ExecRow setBeforeFirstRow() throws StandardException {
        return noputResultSet.setBeforeFirstRow();
      }

      @Override
      public ExecRow setAfterLastRow() throws StandardException {
        return noputResultSet.setAfterLastRow();
      }

      @Override
      public boolean returnsRows() {
        return noputResultSet.returnsRows();
      }

      @Override
      public void resetStatistics() {
        noputResultSet.resetStatistics();
      }

      @Override
      public boolean releaseLocks(GemFireTransaction tran) {
        return noputResultSet.releaseLocks(tran);
      }

      @Override
      public void open() throws StandardException {
        noputResultSet.open();
      }

      @Override
      public int modifiedRowCount() {
        // TODO Auto-generated method stub
        return noputResultSet.modifiedRowCount();
      }

      @Override
      public void markLocallyExecuted() {
        noputResultSet.markLocallyExecuted();

      }

      @Override
      public boolean isDistributedResultSet() {
        return noputResultSet.isDistributedResultSet();

      }

      @Override
      public boolean isClosed() {
        return noputResultSet.isClosed();
      }

      @Override
      public boolean hasAutoGeneratedKeysResultSet() {
        return noputResultSet.hasAutoGeneratedKeysResultSet();
      }

      @Override
      public SQLWarning getWarnings() {
        return noputResultSet.getWarnings();
      }

      @Override
      public long getTimeSpent(int type, int timeType) {
        return noputResultSet.getTimeSpent(type, timeType);
      }

      @Override
      public NoPutResultSet[] getSubqueryTrackingArray(int numSubqueries) {
        return noputResultSet.getSubqueryTrackingArray(numSubqueries);
      }

      @Override
      public int getRowNumber() {
        // TODO Auto-generated method stub
        return noputResultSet.getRowNumber();
      }

      @Override
      public ExecRow getRelativeRow(int row) throws StandardException {
        return noputResultSet.getRelativeRow(row);
      }

      @Override
      public ExecRow getPreviousRow() throws StandardException {
        return noputResultSet.getPreviousRow();
      }

      @Override
      public ExecRow getNextRow() throws StandardException {
        return noputResultSet.getNextRow();
      }

      @Override
      public ExecRow getLastRow() throws StandardException {
        return noputResultSet.getLastRow();
      }

      @Override
      public ExecRow getFirstRow() throws StandardException {
        return noputResultSet.getFirstRow();
      }

      @Override
      public com.pivotal.gemfirexd.internal.catalog.UUID getExecutionPlanID() {
        return noputResultSet.getExecutionPlanID();
      }

      @Override
      public long getExecuteTime() {
        return noputResultSet.getExecuteTime();
      }

      @Override
      public Timestamp getEndExecutionTimestamp() {
        // TODO Auto-generated method stub
        return noputResultSet.getEndExecutionTimestamp();
      }

      @Override
      public String getCursorName() {
        // TODO Auto-generated method stub
        return noputResultSet.getCursorName();
      }

      @Override
      public Timestamp getBeginExecutionTimestamp() {
        // TODO Auto-generated method stub
        return noputResultSet.getBeginExecutionTimestamp();
      }

      @Override
      public com.pivotal.gemfirexd.internal.iapi.sql.ResultSet getAutoGeneratedKeysResultset() {
        // TODO Auto-generated method stub
        return noputResultSet.getAutoGeneratedKeysResultset();
      }

      @Override
      public Activation getActivation() {
        // TODO Auto-generated method stub
        return noputResultSet.getActivation();
      }

      @Override
      public ExecRow getAbsoluteRow(int row) throws StandardException {
        // TODO Auto-generated method stub
        return noputResultSet.getAbsoluteRow(row);
      }

      @Override
      public void flushBatch() throws StandardException {
        // TODO Auto-generated method stub
        noputResultSet.flushBatch();

      }

      @Override
      public void finish() throws StandardException {
        // TODO Auto-generated method stub
        noputResultSet.finish();
      }

      @Override
      public void closeBatch() throws StandardException {
        noputResultSet.closeBatch();

      }

      @Override
      public void close(boolean cleanupOnError) throws StandardException {
        noputResultSet.close(cleanupOnError);
      }

      @Override
      public void clearCurrentRow() {
        noputResultSet.clearCurrentRow();
      }

      @Override
      public void cleanUp(boolean cleanupOnError) throws StandardException {
        noputResultSet.cleanUp(cleanupOnError);
      }

      @Override
      public boolean checkRowPosition(int isType) throws StandardException {
        return noputResultSet.checkRowPosition(isType);
      }

      @Override
      public void checkCancellationFlag() throws StandardException {
        noputResultSet.checkCancellationFlag();
      }

      @Override
      public boolean addLockReference(GemFireTransaction tran) {
        // TODO Auto-generated method stub
        return noputResultSet.addLockReference(tran);
      }

      @Override
      public void accept(ResultSetStatisticsVisitor visitor) {
        noputResultSet.accept(visitor);

      }

      @Override
      public void upgradeReadLockToWrite(RowLocation rl,
          GemFireContainer container) throws StandardException {
        noputResultSet.upgradeReadLockToWrite(rl, container);

      }

      @Override
      public void updateRowLocationPostRead() throws StandardException {
        noputResultSet.updateRowLocationPostRead();
      }

      @Override
      public void updateRow(ExecRow row) throws StandardException {
        noputResultSet.updateRow(row);
      }

      @Override
      public boolean supportsMoveToNextKey() {
        return noputResultSet.supportsMoveToNextKey();
      }

      @Override
      public void setTargetResultSet(TargetResultSet trs) {
        noputResultSet.setTargetResultSet(trs);
      }

      @Override
      public void setNeedsRowLocation(boolean needsRowLocation) {
        noputResultSet.setNeedsRowLocation(needsRowLocation);
      }

      @Override
      public void setGfKeysForNCJoin(ArrayList<DataValueDescriptor> keys)
          throws StandardException {
        noputResultSet.setGfKeysForNCJoin(keys);

      }

      @Override
      public void setCurrentRow(ExecRow row) {
        noputResultSet.setCurrentRow(row);
      }

      @Override
      public int resultSetNumber() {
        // TODO Auto-generated method stub
        return noputResultSet.resultSetNumber();
      }

      @Override
      public boolean requiresRelocking() {
        // TODO Auto-generated method stub
        return noputResultSet.requiresRelocking();
      }

      @Override
      public void reopenCore() throws StandardException {
        noputResultSet.reopenCore();
      }

      @Override
      public void releasePreviousByteSource() {
        noputResultSet.releasePreviousByteSource();
      }

      @Override
      public void setMaxSortingLimit(long limit) {
        noputResultSet.setMaxSortingLimit(limit);
      }

      @Override
      public void positionScanAtRowLocation(RowLocation rLoc)
          throws StandardException {
        noputResultSet.positionScanAtRowLocation(rLoc);

      }

      @Override
      public void openCore() throws StandardException {
        noputResultSet.openCore();

      }

      @Override
      public void markRowAsDeleted() throws StandardException {
        noputResultSet.markRowAsDeleted();
      }

      @Override
      public void markAsTopResultSet() {
        noputResultSet.markAsTopResultSet();
      }

      @Override
      public boolean isForUpdate() {
        // TODO Auto-generated method stub
        return noputResultSet.isForUpdate();
      }

      @Override
      public TXState initLocalTXState() {
        // TODO Auto-generated method stub
        return noputResultSet.initLocalTXState();
      }

      @Override
      public int getScanKeyGroupID() {
        // TODO Auto-generated method stub
        return noputResultSet.getScanKeyGroupID();
      }

      @Override
      public int getScanIsolationLevel() {
        // TODO Auto-generated method stub
        return noputResultSet.getScanIsolationLevel();
      }

      @Override
      public int getPointOfAttachment() {
        // TODO Auto-generated method stub
        return noputResultSet.getPointOfAttachment();
      }

      private List<RowLocation> rowsScanned = new ArrayList<RowLocation>();

      @Override
      public ExecRow getNextRowCore() throws StandardException {

        ExecRow row = noputResultSet.getNextRowCore();
        if (row != null) {
          RowLocation baseRowLocation = isUpdate ? (RowLocation) (row
              .getColumn(resultWidth)).getObject() : (RowLocation) (row
              .getLastColumn().getObject());
          if (deferred) {
            rowsScanned.add(baseRowLocation);
          } else {
            OffHeapByteSource obs = (OffHeapByteSource) ((AbstractRegionEntry) baseRowLocation)
                .getValueInVM(null);
            byte[] bytes = obs.getRowBytes();
            obs.release();
            rowLocToBytes.put(baseRowLocation, bytes);
            long address = ((OffHeapRegionEntry) baseRowLocation).getAddress();
            // Release the address , so that it will be 1 . If there is any
            // early
            // release
            // we will come to know
            Chunk.release(address, true);
            rowLocToAddressMap.put(baseRowLocation, address);
          }
        } else {
          // end of scan
          if (deferred) {
            for (RowLocation baseRowLocation : rowsScanned) {
              OffHeapByteSource obs = (OffHeapByteSource) ((AbstractRegionEntry) baseRowLocation)
                  .getValueInVM(null);
              byte[] bytes = obs.getRowBytes();
              obs.release();
              rowLocToBytes.put(baseRowLocation, bytes);
              long address = ((OffHeapRegionEntry) baseRowLocation)
                  .getAddress();
              // Release the address , so that it will be 1 . If there is any
              // early
              // release
              // we will come to know
              Chunk.release(address, true);
              rowLocToAddressMap.put(baseRowLocation, address);

            }
          }
        }
        return row;
      }

      @Override
      public Context getNewPlanContext() {
        // TODO Auto-generated method stub
        return noputResultSet.getNewPlanContext();
      }

      @Override
      public double getEstimatedRowCount() {
        // TODO Auto-generated method stub
        return noputResultSet.getEstimatedRowCount();
      }

      @Override
      public void filteredRowLocationPostRead(TXState localTXState)
          throws StandardException {
        noputResultSet.filteredRowLocationPostRead(localTXState);

      }

      @Override
      public RowLocation fetch(RowLocation loc, ExecRow destRow,
          FormatableBitSet validColumns, boolean faultIn,
          GemFireContainer container) throws StandardException {
        // TODO Auto-generated method stub
        return noputResultSet.fetch(loc, destRow, validColumns, faultIn,
            container);
      }

      @Override
      public StringBuilder buildQueryPlan(StringBuilder builder, Context context) {
        // TODO Auto-generated method stub
        return noputResultSet.buildQueryPlan(builder, context);
      }
      
      @Override
      public void forceReOpenCore() throws StandardException { 
        noputResultSet.forceReOpenCore();
      }
    };
    sourceField.set(resultset, testResultSetPassThru);
    return rowLocToBytes;
  }

  public void testOffHeapMemoryReleaseOnForeignkeyConstraintViolation_1()
      throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null, "
        + "primary key(id), foreign key (fkId) references t2(id))"
        + "replicate offheap");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    try {
      rowCount += s.executeUpdate("insert into t1 values(1, 1)");
      fail("should have thrown an SQLException");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    rowCount += s.executeUpdate("insert into t2 values(2)");
    rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2)");

    rowCount += s.executeUpdate("update t1 set fkId =2 ");

    s.executeUpdate("delete from t1 where id = 2");

    conn.commit();
    s.close();
  }

  public void testOffHeapMemoryReleaseOnForeignkeyConstraintViolation_2()
      throws Exception {
    
    // Bug #51836
    if (isTransactional) {
      return;
    }

    
    Connection conn = getConnection();
    Statement s = conn.createStatement();

    int rowCount = 0;
    s.executeUpdate("create table t2(id int not null, primary key(id))"
        + "OFFHEAP");
    s.executeUpdate("create table t1(id int not null, fkId int not null, "
        + "primary key(id), foreign key (fkId) references t2(id))"
        + "replicate offheap");
    // fk exists
    // rowCount += s.executeUpdate("insert into t2 values(1)");

    try {
      rowCount += s.executeUpdate("insert into t1 values(1, 1)");
      fail("should have thrown an SQLException");
    } catch (SQLException sqle) {
      if (!"23503".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    rowCount += s.executeUpdate("insert into t2 values(2)");
    rowCount += s.executeUpdate("insert into t2 values(1)");

    rowCount += s.executeUpdate("insert into t1 values(2, 2)");

    rowCount += s.executeUpdate("update t1 set fkId =1 ");

    s.executeUpdate("delete from t1 where id = 2");

    conn.commit();
    s.close();
  }

  public void testBasicDDLForTableCreateLoader() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema EMP");

    stmt.execute("create table EMP.PARTITIONTESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID)) offheap");
    GfxdCallbacksTest.addLoader("EMP", "PARTITIONTESTTABLE",
        "com.pivotal.gemfirexd.ddl.GfxdTestRowLoader", null);
    try {
      ResultSet rs = stmt
          .executeQuery("select ID from EMP.PARTITIONTESTTABLE where ID = 1");
      rs.next();
      assertEquals(1, rs.getInt(1));
      rs.close();
      rs = stmt
          .executeQuery("select ID from EMP.PARTITIONTESTTABLE where ID = 2");
      rs.next();

      assertEquals(2, rs.getInt(1));
      rs.close();

      rs = stmt
          .executeQuery("select ID from EMP.PARTITIONTESTTABLE where ID = 3");
      rs.next();
      assertEquals(3, rs.getInt(1));
      rs.close();
      // Get the cache loader
      final Cache cache = Misc.getGemFireCache();
      final Region<?, ?> regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");
      final RegionAttributes<?, ?> rattr = regtwo.getAttributes();
      final CacheLoader<?, ?> ldr = rattr.getCacheLoader();
      final GfxdCacheLoader gfxdldr = (GfxdCacheLoader) ldr;
      assertNotNull(gfxdldr);

      assertEquals("Number of entries expected to be 3", regtwo.size(), 3);
      for (Object keyObj : regtwo.keySet()) {
        RegionKey key = (RegionKey) keyObj;
        assertNotNull(key);
        Object val = regtwo.get(key);
        assertNotNull(val);

        GemFireContainer gfContainer = (GemFireContainer) regtwo
            .getUserAttribute();
        if (gfContainer.isByteArrayStore()) {
          assertTrue(val instanceof OffHeapByteSource);
          ((OffHeapByteSource) val).release();
        } else {
          assertTrue(val instanceof DataValueDescriptor[]);
        }
        assertNotNull(val);
      }
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
  }

  public void testMemoryReleaseForOrderByAndGroupBy_1() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;
    Connection conn = TestUtil.getConnection();
    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(10) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);
      s.execute("create index i1 on trade.securities(symbol)");
      s.execute("create index i2 on trade.securities(exchange)");
      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");
      for (int i = -50; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }
      final IndexToBaseRowNode[] indexNode = new IndexToBaseRowNode[1];
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void afterOptimizedParsedTree(String query, StatementNode qt,
            LanguageConnectionContext lcc) {
          try {
            qt.accept(new VisitorAdaptor() {
              @Override
              public Visitable visit(Visitable node) throws StandardException {
                if (node instanceof IndexToBaseRowNode) {
                  indexNode[0] = (IndexToBaseRowNode) node;
                }
                return node;
              }

              @Override
              public boolean stopTraversal() {
                return indexNode[0] != null;
              }
            });
            assertNotNull(indexNode[0]);
            assertEquals("I2", indexNode[0].getSource().getTrulyTheBestAccessPath()
                .getConglomerateDescriptor().getConglomerateName());
          } catch (StandardException se) {
            throw new GemFireXDRuntimeException(se);
          }
        }
      });
      String query = "select * from trade.securities  order by exchange asc";
      ResultSet rs = s.executeQuery(query);
      while (rs.next()) {
        rs.getInt(1);
      }
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testBug49108() throws Exception {
    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname char(100), "
        + "companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, "
        + "logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol),"
        + "constraint exchange_unq unique ( exchange)) offheap");

    st.execute("create index company_tid on trade.companies(tid)");
    // st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid, uuid, companyname, companyinfo, note, histprice, asset, logo, tid) values (?,?,?,?,?,?,?,?,?,?,?,?)");
    OffHeapMemoryStats stats = SimpleMemoryAllocatorImpl.getAllocator()
        .getStats();
    SimpleMemoryAllocatorImpl ma = SimpleMemoryAllocatorImpl.getAllocator();

    final long freeSizeBeforeInserts = stats.getFreeMemory();
    final long refCountBeforeInsert = JDBC.getTotalRefCount(ma);

    DataGenerator dg = new DataGenerator();
    dg.insertIntoCompanies(psComp, "SYMB" + 1, "NSE",0);
    dg.insertIntoCompanies(psComp, "SYMB" + 2, "BSE",0);

    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);

    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);
    try {
      st.executeUpdate("update trade.companies set exchange = 'NSE' where symbol = 'SYMB2'");
      fail("unique constraint violation expected");
    } catch (SQLException sqle) {
      assertEquals("23505", sqle.getSQLState());
    }
    //assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);
    
  }
  
  public void testConcurrentDeletes() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(10) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

      s.execute("create index i1 on trade.securities(symbol)");
      s.execute("create index i2 on trade.securities(exchange)");

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -2000; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }
      Runnable runnable = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            st.executeUpdate("delete from trade.securities where sec_id < 0");
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };
      Thread th1 = new Thread(runnable);
      Thread th2 = new Thread(runnable);
      Thread th3 = new Thread(runnable);
      th1.start();
      th2.start();
      th3.start();
      th1.join();
      th2.join();
      th3.join();
//do an insert
      psInsert4.setInt(1, -2001);
      psInsert4.setString(2, "symbol" + (-2001) * -1);
      psInsert4.setFloat(3, 68.94F);
      psInsert4.setString(4, exchange[(-1 * (-2001)) % 7]);
      psInsert4.setInt(5, 2);
      assertEquals(1, psInsert4.executeUpdate());
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  
  public void testConcurrentDeletesAndUpdates() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

      s.execute("create index i1 on trade.securities(symbol)");
      s.execute("create index i2 on trade.securities(exchange)");

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -2000; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }
      Runnable deletes = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            st.executeUpdate("delete from trade.securities where sec_id < 0");
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };
      
      Runnable updates = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            st.executeUpdate("update  trade.securities set symbol = 'abc" + Thread.currentThread().getId() +"' where sec_id < 0");
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };

      Thread th1 = new Thread(deletes);
      Thread th2 = new Thread(deletes);
      Thread th3 = new Thread(deletes);
      Thread th4 = new Thread(updates);
      Thread th5 = new Thread(updates);
      th1.start();
      th2.start();
      th3.start();
      th4.start();
      th5.start();
      th1.join();
      th2.join();
      th3.join();
      th4.join();
      th5.join();
      
      this.doOffHeapValidations();
      
      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testConcurrentDeletesAndUpdates_1() throws Exception {


    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

      s.execute("create index i1 on trade.securities(symbol)");
      s.execute("create index i2 on trade.securities(exchange)");

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -500; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }
      Runnable deletes = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            for (int i = 0 ;  i > -5000;) {
              st.executeUpdate("delete from trade.securities where sec_id > " +i);
              i = i -200;
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };
      
      Runnable updates = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            for(int i = 1; i < 20; ++i) {
              st.executeUpdate("update  trade.securities set symbol = 'abc" + Thread.currentThread().getId() +"_"+i+"' where sec_id < 0");
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };

      Thread th1 = new Thread(deletes);
      Thread th2 = new Thread(deletes);
      Thread th3 = new Thread(deletes);
      Thread th4 = new Thread(updates);
      Thread th5 = new Thread(updates);
      th1.start();
      th2.start();
      th3.start();
      th4.start();
      th5.start();
      th1.join();
      th2.join();
      th3.join();
      th4.join();
      th5.join();
      
      this.doOffHeapValidations();
      
      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  
  }
  //TODO:Asif: If this test is enabled it needs to be modified
  // to prevent CacheObserver overriding .
  //This test sets a CacheObserver which will override the observer
  //set in the setUp. Modify the test if & when enabled.
  public void _testConcurrentDeletesAndUpdatesWithDiskException()
      throws Exception {

    // System.setProperty("gemfire.partitionedRegionRetryTimeout", "1000");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();
    final ThreadLocal<Boolean> raiseException = new ThreadLocal<Boolean>() {
      @Override
      protected Boolean initialValue() {
        return Boolean.FALSE;
      }
    };

    CacheObserverHolder.setInstance(new CacheObserverAdapter() {
      @Override
      public void afterSettingOplogOffSet(long offset) {
        if (raiseException.get().booleanValue()) {
          throw new com.gemstone.gemfire.cache.DiskAccessException("Test",
              "Test");
        }
      }
    });

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap persistent";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

      s.execute("create index i1 on trade.securities(symbol)");
      s.execute("create index i2 on trade.securities(exchange)");

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -500; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }
      LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
      Runnable deletes = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            for (int i = 0; i > -5000;) {
              if (i % 400 == 0) {
                raiseException.set(Boolean.TRUE);
              }
              try {
                st.executeUpdate("delete from trade.securities where sec_id > "
                    + i);

              } catch (DiskAccessException dae) {

                raiseException.set(Boolean.FALSE);

              } catch (SQLException sqle) {
                if (sqle.getCause().toString().indexOf("DiskAccessException ") != -1) {
                  raiseException.set(Boolean.FALSE);
                } else {
                  sqle.printStackTrace();
                  throw sqle;
                }

              }
              i = i - 200;
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };

      Runnable updates = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            for (int i = 1; i < 20; ++i) {
              if (i % 5 == 0) {
                raiseException.set(Boolean.TRUE);
              }
              try {
                st.executeUpdate("update  trade.securities set symbol = 'abc"
                    + Thread.currentThread().getId() + "_" + i
                    + "' where sec_id < 0");

              } catch (DiskAccessException dae) {

                raiseException.set(Boolean.FALSE);

              } catch (SQLException sqle) {
                if (sqle.getCause().toString().indexOf("DiskAccessException ") != -1) {
                  raiseException.set(Boolean.FALSE);
                } else {
                  sqle.printStackTrace();
                  throw sqle;
                }

              }
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };

      Thread th1 = new Thread(deletes);
      Thread th2 = new Thread(deletes);
      Thread th3 = new Thread(deletes);
      Thread th4 = new Thread(updates);
      Thread th5 = new Thread(updates);
      th1.start();
      th2.start();
      th3.start();
      th4.start();
      th5.start();
      th1.join();
      th2.join();
      th3.join();
      th4.join();
      th5.join();

      this.doOffHeapValidations();

      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      System.clearProperty("gemfire.partitionedRegionRetryTimeout");
      LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
      CacheObserverHolder.setInstance(null);
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testBug49193_1() throws Exception {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("create table customers (cid int not null, "
          + "cust_name varchar(100), col1 int , col2 int not null unique ,"
          + "tid int, primary key (cid))  offheap" + " partition by range(tid)"
          + "   ( values between 0  and 10"
          + "    ,values between 10  and 100)");

      stmt.execute("create index i1 on customers(col1)");
      PreparedStatement psInsert = conn
          .prepareStatement("insert into customers values (?,?,?,?,?)");
      for (int i = 1; i < 20; ++i) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setInt(3, i % 4);
        psInsert.setInt(4, i);
        psInsert.setInt(5, 1);
        psInsert.executeUpdate();
      }

      conn = TestUtil.getConnection();
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGlobalIndexDelete() {
              throw new PartitionOfflineException(null, "test ignore");
            }

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimizerEvalutatedCost) {
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimizerEvalutatedCost) {
              return Double.MIN_VALUE;
            }

          });

      addExpectedException(PartitionOfflineException.class);
      // Test bulk operations
      try {
        stmt.executeUpdate("delete from customers where cid > 0");
        fail("Test should fail due to problem in global index maintenance");
      } catch (Exception ignore) {
        removeExpectedException(PartitionOfflineException.class);
      }

      GemFireXDQueryObserverHolder.clearInstance();
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimizerEvalutatedCost) {
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimizerEvalutatedCost) {
              return Double.MIN_VALUE;
            }

          });
      stmt.executeUpdate("delete from customers where cid > 0");
      //do some insert to see if index contains valid index keys
      for (int i = 21; i <40; ++i) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setInt(3, i % 4);
        psInsert.setInt(4, i);
        psInsert.setInt(5, 1);
        psInsert.executeUpdate();
      }
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  public void testBug49193_2() throws Exception {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("create table customers (cid int not null, "
          + "cust_name varchar(100), col1 int , col2 int not null unique ,"
          + "tid int, primary key (cid)) offheap " + " partition by range(tid)"
          + "   ( values between 0  and 10"
          + "    ,values between 10  and 100)");

      stmt.execute("create index i1 on customers(col1)");
      PreparedStatement psInsert = conn
          .prepareStatement("insert into customers values (?,?,?,?,?)");
      for (int i = 1; i < 2; ++i) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setInt(3, i % 4);
        psInsert.setInt(4, i);
        psInsert.setInt(5, 1);
        psInsert.executeUpdate();
      }

      conn = TestUtil.getConnection();
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGlobalIndexDelete() {
              throw new PartitionOfflineException(null, "test ignore");
            }

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimizerEvalutatedCost) {
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimizerEvalutatedCost) {
              return Double.MIN_VALUE;
            }

          });

      addExpectedException(PartitionOfflineException.class);
      // Test bulk operations
      try {
        stmt.executeUpdate("delete from customers where cid > 0");
        fail("Test should fail due to problem in global index maintenance");
      } catch (Exception ignore) {
        removeExpectedException(PartitionOfflineException.class);
      }
      GemFireXDQueryObserverHolder.clearInstance();
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {

            @Override
            public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
                OpenMemIndex memIndex, double optimizerEvalutatedCost) {
              return Double.MAX_VALUE;
            }

            @Override
            public double overrideDerbyOptimizerCostForMemHeapScan(
                GemFireContainer gfContainer, double optimizerEvalutatedCost) {
              return Double.MIN_VALUE;
            }

          });

      stmt.executeUpdate("delete from customers where cid > 0");
      
    //do some insert to see if index contains valid index keys
      for (int i = 21; i <40; ++i) {
        psInsert.setInt(1, i);
        psInsert.setString(2, "name" + i);
        psInsert.setInt(3, i % 4);
        psInsert.setInt(4, i);
        psInsert.setInt(5, 1);
        psInsert.executeUpdate();
      }
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testIndexUpdateWithRowInsertedUsingLoader() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

     s.execute("create index i1 on trade.securities(symbol)");
     s.execute("create index i2 on trade.securities(exchange)");
     s.execute("create index i3 on trade.securities(price)");
      GfxdCallbacksTest.addLoader("trade", "securities",
          "com.pivotal.gemfirexd.jdbc.offheap.OffHeapTest$SecuritiesLoader", null);
      final int K = 1000;
      Runnable inserts = new Runnable() {

        public void run() {
          try {
            for (int i = 1; i < 500; i += 4) {
              Connection conn1 = TestUtil.getConnection();
              PreparedStatement pstmt = conn1
                  .prepareStatement("select * from trade.securities where sec_id in (?, ?, ?, ?,?)");
              pstmt.setInt(1, -1 * 1);
              pstmt.setInt(2, -(i + 1));
              pstmt.setInt(3, -(i + 2));
              pstmt.setInt(4, -(i + 3));
              pstmt.setInt(5, -(i + 4));
              addExpectedException(ConflictException.class);
              try{
              ResultSet rs = pstmt.executeQuery();
              int cnt = 0;
              while (rs.next()) {
                rs.getInt(1);
                ++cnt;
              }
              removeExpectedException(ConflictException.class);
              assertEquals(5, cnt);
              
              }catch (SQLException sqle) {
                removeExpectedException(ConflictException.class);
                if (sqle.getSQLState().indexOf("X0Z02") != -1) {
                  // expected ok
                } else {
                  throw sqle;
                }
              }    
              
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }
      };

      Runnable deletes = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            for (int i = 0; i > -K; i -=10) {
              st.executeUpdate("delete from trade.securities where sec_id > "
                  + i);
             
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };

      Thread th1 = new Thread(deletes);
      Thread th2 = new Thread(deletes);
     // Thread th3 = new Thread(deletes);
      Thread th4 = new Thread(inserts);
      Thread th5 = new Thread(inserts);
      th1.start();
      th2.start();
      //th3.start();
      th4.start();
      th5.start();
      th1.join();
      th2.join();
      //th3.join();
      th4.join();
      th5.join();
      s.executeUpdate("delete from trade.securities");
      this.doOffHeapValidations();

      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testIndexUpdateWithRowInsertedUsingLoader_2() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

    final  String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

     s.execute("create index i1 on trade.securities(symbol)");
     s.execute("create index i2 on trade.securities(exchange)");
     s.execute("create index i3 on trade.securities(price)");
      GfxdCallbacksTest.addLoader("trade", "securities",
          "com.pivotal.gemfirexd.jdbc.offheap.OffHeapTest$SecuritiesLoader", null);
      final int K = -500;
      final boolean [] fail = new boolean []{false};
      Runnable selects = new Runnable() {

        public void run() {
          try {
            for (int i = -1; i > K; i -= 4) {
              Connection conn1 = TestUtil.getConnection();
              PreparedStatement pstmt = conn1
                  .prepareStatement("select * from trade.securities where sec_id in (?, ?, ?, ?,?)");
              pstmt.setInt(1, i);
              pstmt.setInt(2, (i - 1));
              pstmt.setInt(3, (i - 2));
              pstmt.setInt(4, (i - 3));
              pstmt.setInt(5, (i - 4));
              addExpectedException(ConflictException.class);
              try {
                ResultSet rs = pstmt.executeQuery();
                int cnt = 0;
                while (rs.next()) {
                  rs.getInt(1);
                  ++cnt;
                }
                rs.close();
                removeExpectedException(ConflictException.class);
                assertEquals(5, cnt);
              } catch (SQLException sqle) {
                removeExpectedException(ConflictException.class);
                if (sqle.getSQLState().indexOf("X0Z02") != -1) {
                  // expected ok
                } else {
                  throw sqle;
                }
              }         
            }
          } catch (SQLException sqle) {            
            fail[0] = true;
            sqle.printStackTrace();
          }

        }
      };

      Runnable inserts = new Runnable() {

        @Override
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            Statement st = conn1.createStatement();
            PreparedStatement psInsert = conn1
                .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

            for (int i = -1; i > K; --i) {
              psInsert.setInt(1, i);
              psInsert.setString(2, "symbol" + -( i * 1));
              psInsert.setFloat(3, 68.94F);
              psInsert.setString(4, exchange[( -i) % 7]);
              psInsert.setInt(5, 2);
              try {
              assertEquals(1, psInsert.executeUpdate());
              } catch (SQLException sqle) {
                //sqle.printStackTrace();
              }
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }

        }

      };

     
     // Thread th3 = new Thread(deletes);
      Thread th4 = new Thread(selects);
      Thread th5 = new Thread(inserts);
     
      //th3.start();
      th5.start();
      th4.start();
     
      
      //th3.join();
      th4.join();
      th5.join();
      s.executeUpdate("delete from trade.securities");
      this.doOffHeapValidations();

      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }
      assertFalse(fail[0]);
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testIndexUpdateWithMultiPutUpdate() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id))" + " offheap";

      s.execute(tab1);

      s.execute("create index i3 on trade.securities(price)");
      Thread th = new Thread(new Runnable() {
        public void run() {
          try {
            Connection conn1 = TestUtil.getConnection();
            PreparedStatement psInsert1 = conn1
                .prepareStatement("insert into trade.securities values (?,?,?,?,?)");
            for (int i = 1; i < 2; ++i) {
              psInsert1.setInt(1, i);
              psInsert1.setString(2, "symb" + i);
              psInsert1.setFloat(3, 7.1f * i);
              psInsert1.setString(4, "exch" + i);
              psInsert1.setInt(5, 2);
              psInsert1.executeUpdate();
            }
          } catch (SQLException sqle) {
            sqle.printStackTrace();
          }
        }
      });
     
      Statement st = conn.createStatement();
      st.executeUpdate("insert into trade.securities values " +
      		"(114,'symb114-',114.43,'exch114-',2) ");
      th.start();
      th.join();
      try {
      st.executeUpdate("insert into trade.securities values  (6,'symb6-',6.43,'exch6-',2),"
      +	" (1,'symb1-',8.43,'exch1-',2) ," 
      + " (7,'symb7-',7.43,'exch7-',2) ");
      
        
        fail("insert should have failed");
      } catch (SQLException sqle) {
        sqle.printStackTrace();
      }
     
      
      s.executeUpdate("update  trade.securities set price = 10.73 where sec_id = 1");
      s.executeUpdate("delete from trade.securities");

      this.doOffHeapValidations();

      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testIndexUpdateWithRowInsertedUsingLoader_1() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

      // s.execute("create index i1 on trade.securities(symbol)");
      // s.execute("create index i2 on trade.securities(exchange)");
      // s.execute("create index i3 on trade.securities(price)");
      GfxdCallbacksTest.addLoader("trade", "securities",
          "com.pivotal.gemfirexd.jdbc.offheap.OffHeapTest$SecuritiesLoader",
          null);

      Connection conn1 = TestUtil.getConnection();
      PreparedStatement pstmt = conn1
          .prepareStatement("select * from trade.securities where sec_id in (?)");
      pstmt.setInt(1, -1 * 1);

      ResultSet rs = pstmt.executeQuery();
      int cnt = 0;
      while (rs.next()) {
        rs.getInt(1);
        ++cnt;
      }

      assertEquals(1, cnt);
      rs.close();
      rs = pstmt.executeQuery();
      cnt = 0;
      while (rs.next()) {
        rs.getInt(1);
        ++cnt;
      }
      rs.close();
      assertEquals(1, cnt);

      this.doOffHeapValidations();
      // s.executeUpdate("delete from trade.securities");
      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testMemoryReleaseOnRegionIteration() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(500) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
          + " offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

      s.execute("create index i1 on trade.securities(symbol)");
      s.execute("create index i2 on trade.securities(exchange)");

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -2000; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }
      Region rgn = Misc.getRegion("/TRADE/SECURITIES", true, false);
      Set<Object> keys = rgn.keySet();
      for(Object key : keys) {
        
      }
      
      this.doOffHeapValidations();
      
      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  



 
  public void testMemoryRelease_1() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(10) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) offheap";
      String tab2 = " create table trade.customers (cid int not null, cust_name varchar(100),"
          + " since date, addr varchar(100), tid int, primary key (cid)) offheap";

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_cust_fk foreign key (cid) references trade.customers (cid),"
          + " constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)"
          + " on delete restrict, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);
      s.execute(tab2);
      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -2000; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into trade.customers values (?, ?,?,?,?)");

      for (int i = 1; i < 2000; ++i) {
        psInsert1.setInt(1, i);
        psInsert1.setString(2, "name" + i);
        psInsert1.setDate(3, new java.sql.Date(2004, 12, 1));
        psInsert1.setString(4, "address" + i);
        psInsert1.setInt(5, 2);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 1800; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "open");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }
      this.doOffHeapValidations();
      String updt= "update trade.customers set cust_name = 'yyy' , addr ='xxx' where cid=40 and tid =5";
      s.executeUpdate(updt);
      this.doOffHeapValidations();
      String query = "select cid, cast (avg(qty*bid) as decimal (30, 20)) "
          + "as amount from trade.buyorders  where status ='open' and tid =5 GROUP BY cid ORDER BY amount";
      ResultSet rs = s.executeQuery(query);
      int cnt = 0;
      while (rs.next()) {
        rs.getBigDecimal(2);
        ++cnt;
      }
      assertTrue(cnt > 0);
      cnt = 0;
      query = "select * from trade.securities where symbol is not null and tid = 2";
      
      rs = s.executeQuery(query);
      
      while (rs.next()) {
        rs.getString(1);
        ++cnt;
      }
      assertTrue(cnt > 0);
      
      cnt = 0;
      query = "select sid, count(*) from trade.buyorders  where status ='open' and tid =5 GROUP BY sid HAVING count(*) >=0";
      rs = s.executeQuery(query);
      while (rs.next()) {
        rs.getInt(2);
        ++cnt;
      }
      assertTrue(cnt > 0);
      query = "select * from trade.buyorders where status = 'open' and tid = 5";
      cnt = 0;
      rs = s.executeQuery(query);

      while (rs.next()) {
        rs.getInt(2);
        ++cnt;
      }
      assertTrue(cnt > 0);

      query = "select cid, min(qty*bid) as smallest_order from trade.buyorders  where status ='open' and tid =5 GROUP BY cid";
      cnt = 0;
      rs = s.executeQuery(query);

      while (rs.next()) {
        rs.getInt(2);
        ++cnt;
      }
      assertTrue(cnt > 0);

      query = "select distinct sid from trade.buyorders "
          + "where qty >=0  and tid =5";

      cnt = 0;
      rs = s.executeQuery(query);

      while (rs.next()) {
        rs.getInt(1);
        ++cnt;
      }
      assertTrue(cnt > 0);
      PreparedStatement ps = conn.prepareStatement("select * from trade.buyorders where status = 'open' and tid = ?");
      ps.setInt(1, 5);
      rs = ps.executeQuery();
      rs.close();

      ps.setInt(1, 5);
      rs = ps.executeQuery();
      ps.close();
      
      
      ps = conn.prepareStatement("select * from trade.buyorders where status = 'open' and tid = ?");
      ps.setInt(1, 5);
      rs = ps.executeQuery();
      rs = ps.executeQuery();
      rs.close();
      this.doOffHeapValidations();
      ps.setInt(1, 5);
      rs = ps.executeQuery();
      int i =0;
      while(rs.next() && i <18) {
        rs.getInt(1);
        ++i;
      }
      rs.close();
      
      s.executeUpdate("delete from trade.buyorders");
      s.executeUpdate("delete from trade.customers");
      s.executeUpdate("delete from trade.securities");
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
        s.execute("drop table if exists trade.customers");
        rmcd.waitTillAllClear();
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testMemoryRelease_2() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(10) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id), constraint sec_uq unique (symbol, exchange),"
          + " constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse'))) offheap";
      String tab2 = " create table trade.customers (cid int not null, cust_name varchar(100),"
          + " since date, addr varchar(100), tid int, primary key (cid)) offheap";

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_cust_fk foreign key (cid) references trade.customers (cid),"
          + " constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)"
          + " on delete restrict, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);
      s.execute(tab2);
      s.execute(tab3);
     

      psInsert4 = conn
          .prepareStatement("insert into trade.securities values (?, ?, ?,?,?)");

      for (int i = -2000; i < 0; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i * -1);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[(-1 * i) % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }

      psInsert1 = conn
          .prepareStatement("insert into trade.customers values (?, ?,?,?,?)");

      for (int i = 1; i < 2000; ++i) {
        psInsert1.setInt(1, i);
        psInsert1.setString(2, "name" + i);
        psInsert1.setDate(3, new java.sql.Date(2004, 12, 1));
        psInsert1.setString(4, "address" + i);
        psInsert1.setInt(5, 2);
        assertEquals(1, psInsert1.executeUpdate());
      }

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 1800; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "open");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }
      this.doOffHeapValidations();
      String updt= "update trade.customers set cust_name = 'yyy' , addr ='xxx' where cid=40 and tid =5";
      s.executeUpdate(updt);
      this.doOffHeapValidations();
      String query = "select cid, cast (avg(qty*bid) as decimal (30, 20)) "
          + "as amount from trade.buyorders  where status ='open' and tid =5 GROUP BY cid ORDER BY amount";
      ResultSet rs = s.executeQuery(query);
      int cnt = 0;
      while (rs.next()) {
        rs.getBigDecimal(2);
        ++cnt;
      }
      assertTrue(cnt > 0);
      cnt = 0;
      query = "select * from trade.securities where symbol is not null and tid = 2";
      
      rs = s.executeQuery(query);
      
      while (rs.next()) {
        rs.getString(1);
        ++cnt;
      }
      assertTrue(cnt > 0);
      
      cnt = 0;
      query = "select sid, count(*) from trade.buyorders  where status ='open' and tid =5 GROUP BY sid HAVING count(*) >=0";
      rs = s.executeQuery(query);
      while (rs.next()) {
        rs.getInt(2);
        ++cnt;
      }
      assertTrue(cnt > 0);
      query = "select * from trade.buyorders where status = 'open' and tid = 5";
      cnt = 0;
      rs = s.executeQuery(query);

      while (rs.next()) {
        rs.getInt(2);
        ++cnt;
      }
      assertTrue(cnt > 0);

      query = "select cid, min(qty*bid) as smallest_order from trade.buyorders  where status ='open' and tid =5 GROUP BY cid";
      cnt = 0;
      rs = s.executeQuery(query);

      while (rs.next()) {
        rs.getInt(2);
        ++cnt;
      }
      assertTrue(cnt > 0);

      query = "select distinct sid from trade.buyorders "
          + "where qty >=0  and tid =5";

      cnt = 0;
      rs = s.executeQuery(query);

      while (rs.next()) {
        rs.getInt(1);
        ++cnt;
      }
      assertTrue(cnt > 0);
      PreparedStatement ps = conn.prepareStatement("select * from trade.buyorders where status = 'open' and tid = ?");
      ps.setInt(1, 5);
      rs = ps.executeQuery();
      rs.close();

      ps.setInt(1, 5);
      rs = ps.executeQuery();
      ps.close();
      
      
      ps = conn.prepareStatement("select * from trade.buyorders where status = 'open' and tid = ?");
      ps.setInt(1, 5);
      rs = ps.executeQuery();
      rs = ps.executeQuery();
      rs.close();
      this.doOffHeapValidations();
      ps.setInt(1,0);
      rs = ps.executeQuery();
      int i =0;
      while(rs.next() && i <18) {
        rs.getInt(1);
        ++i;
      }
      rs.close();
      
      s.executeUpdate("delete from trade.buyorders");
      s.executeUpdate("delete from trade.customers");
      s.executeUpdate("delete from trade.securities");
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
        s.execute("drop table if exists trade.customers");
        rmcd.waitTillAllClear();
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testAlterTableBug48333_1_ADD_COl() throws Exception {
    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, "
        + " tid int, constraint comp_pk primary key (symbol, exchange)) offheap");

    st.execute("create index company_tid on trade.companies(tid)");
    // st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid,  tid) values (?,?,?,?,?)");

    for (int i = 1; i < 2; ++i) {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;

      psComp.setString(1, "symb" + i);
      psComp.setString(2, "exchng" + i);
      psComp.setShort(3, companyType);
      psComp.setBytes(4, DataGenerator.getUidBytes(uid));
      psComp.setInt(5, 10);
      psComp.executeUpdate();

    }

    // Now alter table & add a lob column
    st.execute("alter table trade.companies add column companyinfo clob");

    int n = st.executeUpdate("update trade.companies set tid = 5 ");
    assertEquals(1, n);
    // Execute a scan query
    rs = st.executeQuery("select * from trade.companies ");
    int numRows = 0;
    int numCols = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      for (int i = 0; i < numCols; ++i) {
        String columnName = rs.getMetaData().getColumnName(i + 1);
        Object obj = rs.getObject(i + 1);
        if (columnName.equals("symbol")) {
          assertEquals("symb1", obj);
        } else if (columnName.equals("symbol")) {
          assertEquals("exchng1", obj);
        } else if (columnName.equals("companytype")) {
          assertEquals(8, ((Number) obj).intValue());
        } else if (columnName.equals("tid")) {
          assertEquals(5, ((Number) obj).intValue());
        }

      }

      ++numRows;
    }
    assertEquals(1, numRows);

    PreparedStatement updtClob = conn
        .prepareStatement("update trade.companies set" + " companyinfo = ?");
    DataGenerator dg = new DataGenerator();
    Clob clob = dg.getClob(1)[0];
    updtClob.setClob(1, clob);
    updtClob.executeUpdate();
    rs = st.executeQuery("select companyinfo from trade.companies");
    assertTrue(rs.next());
    assertEquals(DataGenerator.convertClobToString(clob),
        DataGenerator.convertClobToString(rs.getClob(1)));

  }

  public void testAlterTableBug48333_2_ADD_COL() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, "
        + " tid int, companyinfo1 clob, "
        + " constraint comp_pk primary key (symbol, exchange)) offheap");

    st.execute("create index company_tid on trade.companies(tid)");
    // st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid,  tid, companyinfo1) values (?,?,?,?,?,?)");
    DataGenerator dg1 = new DataGenerator();
    Clob clob1 = dg1.getClob(1)[0];
    for (int i = 1; i < 2; ++i) {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;

      psComp.setString(1, "symb" + i);
      psComp.setString(2, "exchng" + i);
      psComp.setShort(3, companyType);
      psComp.setBytes(4, DataGenerator.getUidBytes(uid));
      psComp.setInt(5, 10);
      psComp.setClob(6, clob1);
      psComp.executeUpdate();

    }

    // Now alter table & add a lob column
    st.execute("alter table trade.companies add column companyinfo2 clob");

    PreparedStatement updtClob = conn
        .prepareStatement("update trade.companies set" + " companyinfo2 = ?");
    DataGenerator dg2 = new DataGenerator();
    Clob clob2 = dg2.getClob(1)[0];
    updtClob.setClob(1, clob2);
    updtClob.executeUpdate();
    // Execute a scan query
    rs = st
        .executeQuery("select companyinfo1 , companyinfo2 from trade.companies ");
    assertTrue(rs.next());

    assertEquals(DataGenerator.convertClobToString(clob1),
        DataGenerator.convertClobToString(rs.getClob(1)));
    assertEquals(DataGenerator.convertClobToString(clob2),
        DataGenerator.convertClobToString(rs.getClob(2)));
  }

  public void testAlterTableBug48333_3_ADD_COL() throws Exception {
    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, "
        + " tid int, companyinfo1 clob, "
        + " constraint comp_pk primary key (symbol, exchange)) offheap");

    st.execute("create index company_tid on trade.companies(tid)");
    // st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid,  tid, companyinfo1) values (?,?,?,?,?,?)");
    DataGenerator dg1 = new DataGenerator();
    Clob clob1 = dg1.getClob(1)[0];
    for (int i = 1; i < 2; ++i) {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;

      psComp.setString(1, "symb" + i);
      psComp.setString(2, "exchng" + i);
      psComp.setShort(3, companyType);
      psComp.setBytes(4, DataGenerator.getUidBytes(uid));
      psComp.setInt(5, 10);
      psComp.setNull(6, Types.CLOB);
      psComp.executeUpdate();

    }

    // Now alter table & add a lob column
    st.execute("alter table trade.companies add column companyinfo2 clob");

    PreparedStatement updtClob = conn
        .prepareStatement("update trade.companies set" + " companyinfo2 = ? ");
    DataGenerator dg2 = new DataGenerator();
    Clob clob2 = dg2.getClob(1)[0];
    updtClob.setClob(1, clob2);
    updtClob.executeUpdate();
    // Execute a scan query
    rs = st
        .executeQuery("select companyinfo1 , companyinfo2 from trade.companies ");
    assertTrue(rs.next());
    assertEquals(null, rs.getClob(1));
    assertEquals(DataGenerator.convertClobToString(clob2),
        DataGenerator.convertClobToString(rs.getClob(2)));
  }
  
  public void testAlterTableBug48333_4_ADD_COL() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, "
        + " tid int, companyinfo1 clob, "
        + " constraint comp_pk primary key (symbol, exchange)) offheap");

    st.execute("create index company_tid on trade.companies(tid)");
    // st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid,  tid, companyinfo1) values (?,?,?,?,?,?)");
    DataGenerator dg1 = new DataGenerator();
    Clob clob1 = dg1.getClob(1)[0];
    for (int i = 1; i < 2; ++i) {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;

      psComp.setString(1, "symb" + i);
      psComp.setString(2, "exchng" + i);
      psComp.setShort(3, companyType);
      psComp.setBytes(4, DataGenerator.getUidBytes(uid));
      psComp.setInt(5, 10);
      psComp.setClob(6, clob1);
      psComp.executeUpdate();

    }

    // Now alter table & add a lob column
    st.execute("alter table trade.companies add column companyinfo2 clob");

    PreparedStatement updtClob = conn
        .prepareStatement("update trade.companies set" + " companyinfo1 = ? ");
    DataGenerator dg2 = new DataGenerator();
    Clob clob2 = dg2.getClob(1)[0];
    updtClob.setClob(1, clob2);
    updtClob.executeUpdate();
    // Execute a scan query
    rs = st
        .executeQuery("select companyinfo1 , companyinfo2 from trade.companies ");
    assertTrue(rs.next());
    assertEquals(null, rs.getClob(2));
    assertEquals(DataGenerator.convertClobToString(clob2),
        DataGenerator.convertClobToString(rs.getClob(1)));
  }

  public void testAlterTableBug48333_5_DEL_COL() throws Exception {
    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, "
        + " tid int, companyinfo1 clob, companyinfo2 clob, companyinfo3 clob,"
        + " constraint comp_pk primary key (symbol, exchange)) offheap");

    st.execute("create index company_tid on trade.companies(tid)");
    // st.execute("create index company_name on trade.companies(companyname)");
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid,  tid, companyinfo1, companyinfo2"
            + ", companyinfo3) values (?,?,?,?,?,?,?,?)");
    DataGenerator dg1 = new DataGenerator();
    Clob[] clobs = dg1.getClob(3);
    Clob clob1 = clobs[0];
    Clob clob2 = clobs[1];
    Clob clob3 = clobs[2];
    for (int i = 1; i < 2; ++i) {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;

      psComp.setString(1, "symb" + i);
      psComp.setString(2, "exchng" + i);
      psComp.setShort(3, companyType);
      psComp.setBytes(4, DataGenerator.getUidBytes(uid));
      psComp.setInt(5, 10);
      psComp.setClob(6, clob1);
      psComp.setClob(7, clob2);
      psComp.setClob(8, clob3);
      psComp.executeUpdate();

    }

    // Now alter table & add a lob column
    st.execute("alter table trade.companies drop column companyinfo2 ");

    PreparedStatement updtClob = conn
        .prepareStatement("update trade.companies set" + " companyinfo1 = ? ");
    DataGenerator dg2 = new DataGenerator();
    Clob clob1_ = dg2.getClob(1)[0];
    updtClob.setClob(1, clob1_);
    updtClob.executeUpdate();
    // Execute a scan query
    rs = st
        .executeQuery("select companyinfo1 , companyinfo3 from trade.companies ");
    assertTrue(rs.next());
    assertEquals(DataGenerator.convertClobToString(clob1_),
        DataGenerator.convertClobToString(rs.getClob(1)));
    assertEquals(DataGenerator.convertClobToString(clob3),
        DataGenerator.convertClobToString(rs.getClob(2)));
  }
  
  public void testAlterTableBug48333_6_ADD_COl() throws Exception {
    // System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "2G");
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    ResultSet rs = null;
    st.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
    st.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
    st.execute("create table trade.companies (symbol varchar(10) not null, "
        + "exchange varchar(10) not null,"
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, "
        + " tid int, constraint comp_pk primary key (symbol, exchange)) offheap");

   
    PreparedStatement psComp = conn
        .prepareStatement("insert into trade.companies (symbol, exchange, companytype,"
            + " uid,  tid) values (?,?,?,?,?)");

    for (int i = 1; i < 2; ++i) {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;

      psComp.setString(1, "symb" + i);
      psComp.setString(2, "exchng" + i);
      psComp.setShort(3, companyType);
      psComp.setBytes(4, DataGenerator.getUidBytes(uid));
      psComp.setInt(5, 10);
      psComp.executeUpdate();

    }

    // Now alter table & add a lob column
    st.execute("alter table trade.companies add column companyinfo clob");

    int n = st.executeUpdate("update trade.companies set tid = 5 ");
    assertEquals(1, n);
    // Execute a scan query
    rs = st.executeQuery("select * from trade.companies ");
    int numRows = 0;
    int numCols = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      for (int i = 0; i < numCols; ++i) {
        String columnName = rs.getMetaData().getColumnName(i + 1);
        Object obj = rs.getObject(i + 1);
        if (columnName.equals("symbol")) {
          assertEquals("symb1", obj);
        } else if (columnName.equals("symbol")) {
          assertEquals("exchng1", obj);
        } else if (columnName.equals("companytype")) {
          assertEquals(8, ((Number) obj).intValue());
        } else if (columnName.equals("tid")) {
          assertEquals(5, ((Number) obj).intValue());
        }

      }

      ++numRows;
    }
    assertEquals(1, numRows);

    PreparedStatement updtClob = conn
        .prepareStatement("update trade.companies set" + " companyinfo = ?");
    DataGenerator dg = new DataGenerator();
    Clob clob = dg.getClob(1)[0];
    updtClob.setClob(1, clob);
    updtClob.executeUpdate();
    rs = st.executeQuery("select companyinfo from trade.companies");
    assertTrue(rs.next());
    assertEquals(DataGenerator.convertClobToString(clob),
        DataGenerator.convertClobToString(rs.getClob(1)));

  }
  
  public void testMemoryReleaseOnFilteredRow() throws Exception {
   String query ="select oid, sid, ask, cid, status from trade.sellorders " +
   		" where ((status =? and ask <?) OR (status =? and ask >?))  " +
   		"and cid not IN (?, ?, ?, ?, ?) and tid =?";
   
   SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
   Properties props = new Properties();
   int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
   props.put("mcast-port", String.valueOf(mcastPort));
   Connection conn = TestUtil.getConnection(props);
   Statement st = conn.createStatement();
   ResultSet rs = null;
   st.execute("create table trade.sellorders (oid int not null constraint " +
   		"orders_pk primary key, cid int, sid int,  ask decimal (30, 20)," +
   		" status varchar(10) default 'open', tid int" +
   		", constraint status_ch check (status in ('cancelled', 'open', 'filled')))" +
   		" offheap");
   PreparedStatement psInsert = conn.prepareStatement("insert into trade.sellorders" +
      " values(?,?,?,?,?,?)" );
   for(int i = 0; i < 800; ++i) {
     psInsert.setInt(1, i);
     psInsert.setInt(2, i*3);
     psInsert.setInt(3, i*4);
     psInsert.setFloat(4, i*173.56f);
     psInsert.setString(5, i %3 == 0 ?"open":"cancelled");
     psInsert.setInt(6, i %2 ==0?1:2);
     psInsert.executeUpdate();
     
   }
   GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter()
   {
     @Override
     public double overrideDerbyOptimizerCostForMemHeapScan(
         GemFireContainer gfContainer, double optimzerEvalutatedCost) {
       return Double.MAX_VALUE;
     }
   }
       );
   st.execute("create index i1 on trade.sellorders(cid)");
   st.execute("create index i2 on trade.sellorders(ask)");
   PreparedStatement psq = conn.prepareStatement(query);
   psq.setString(1,"open");
   psq.setFloat(2,600.75f);
   psq.setString(3,"open");
   psq.setFloat(4,1300.75f);
   psq.setInt(5,9);
   psq.setInt(6,18);
   psq.setInt(7,72);
   psq.setInt(8,55);
   psq.setInt(9,24);
   psq.setInt(10, 2);
   int numRows = 0;
   rs = psq.executeQuery();
   while(rs.next()) {
     rs.getInt(1);
     ++numRows ;
   }
   
   assertTrue(numRows > 0);
   

  }

  public void testLRURegionEntryCreateUpdateAndFaultIn() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;
    Connection conn = TestUtil.getConnection();
    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.securities (sec_id int not null, "
          + "symbol varchar(10) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id), constraint sec_uq unique (symbol," +
          " exchange), constraint exc_ch "
          + "check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse'," +
          " 'hkse', 'tse'))) offheap eviction by lrucount 1 evictaction "
          + "overflow synchronous ";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...
      s.execute(tab1);

      psInsert4 = conn
          .prepareStatement("insert into trade.securities" +
          		" values (?, ?, ?,?,?)");

      for (int i = 0; i < 6; ++i) {
        psInsert4.setInt(1, i);
        psInsert4.setString(2, "symbol" + i);
        psInsert4.setFloat(3, 68.94F);
        psInsert4.setString(4, exchange[i % 7]);
        psInsert4.setInt(5, 2);
        assertEquals(1, psInsert4.executeUpdate());
      }
      this.doOffHeapValidations();
      String updt = "update trade.securities set tid = 5 ";
      int n = s.executeUpdate(updt);
      assertEquals(6, n);
      this.doOffHeapValidations();
      int cnt = 0;
      String query = "select * from trade.securities where symbol is" +
      		" not null and tid = 5 for update";
      ResultSet rs = s.executeQuery(query);
      while (rs.next()) {
        rs.getString(1);
        ++cnt;
      }
      assertEquals(6, cnt);
      s.execute("create index sec_tid on trade.securities(tid)");
      cnt = 0;
      rs = s.executeQuery(query);
      while (rs.next()) {
        rs.getString(1);
        ++cnt;
      }
      assertEquals(6, cnt);
      s.executeUpdate("delete from trade.securities");
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.securities");
        rmcd.waitTillAllClear();
      }
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testBug49014() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 3; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "cancelled");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }
      conn.commit();
      Statement st = conn.createStatement();
      st.executeUpdate("update trade.buyorders set status = 'xxxx' where cid =1");
      st.executeUpdate("update trade.buyorders set status = 'cancelled' where cid = 1");

      conn.commit();

      psInsert2.setInt(1, 4);
      psInsert2.setInt(2, 4);
      psInsert2.setInt(3, -1 * 4);
      psInsert2.setInt(4, 100 * 4);
      psInsert2.setFloat(5, 30.40f);
      psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert2.setString(7, "cancelled");
      psInsert2.setInt(8, 5);
      assertEquals(1, psInsert2.executeUpdate());
      this.doOffHeapValidations();
      conn.commit();
      s.executeUpdate("delete from trade.buyorders");
      conn.commit();
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  public void testForSingleOHAddressCache() throws Exception {
    // Bug #51836
    if (isTransactional) {
      return;
    }

    
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 100; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "cancelled");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }
      String query = "select * from trade.buyorders where status = 'cancelled' and tid > 0";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) tableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      rs.next();
      rs.close();

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  
  public void testSingleOHAddressCacheForUpdateAndSimpleDelete() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 100; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "cancelled");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            private void reflectAndAssert(com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet,
                Class<?> resultsetClass) throws Exception{
              Field sourceField = resultsetClass
                  .getDeclaredField("source");
              sourceField.setAccessible(true);
              ProjectRestrictResultSet psRs = (ProjectRestrictResultSet) sourceField
                  .get(resultSet);
              /*psRs = (ProjectRestrictResultSet) psRs.getSource();
              IndexRowToBaseRowResultSet irs = (IndexRowToBaseRowResultSet) psRs
                  .getSource();*/
              TableScanResultSet ts = (TableScanResultSet)  psRs.getSource();

              Class<?> tableScanClazz = TableScanResultSet.class;
              Field field = tableScanClazz.getDeclaredField("ohAddressCache");
              field.setAccessible(true);
              OHAddressCache addressCache = (OHAddressCache) field.get(ts);
              Class<? extends OHAddressCache> addressCacheClass = TableScanResultSet.SingleOHAddressCache.class;
              assertEquals(addressCache.getClass(), addressCacheClass);

            }
            @Override
            public void onUpdateResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {                
                this.reflectAndAssert(resultSet, UpdateResultSet.class);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }  
            
            @Override
            public void onDeleteResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {                
                this.reflectAndAssert(resultSet, DeleteResultSet.class);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }  
            
          });
      String update = "update trade.buyorders set status = 'open' where oid > 50 and tid > 0";      
      s.executeUpdate(update);
      
      String delete = "delete from trade.buyorders  where oid > 50 and tid > 0";      
      s.executeUpdate(delete);
      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  //Asif: This test is disabled due to Bug 50100 which has disabled the OR optimziation.
  //Once that bug is fixed , pls enable this test
  public void _testOffHeapReleaseForMultiColumnTableScanResultSet() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 500; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "cancelled");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }
      String query = "select cid, sid from trade.buyorders where  ( qty < 100 or qty > 150)";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) tableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      int num =0 ;
      while(rs.next()) {
        rs.getInt(1);
        ++num;
      }
      assertTrue(num > 0);
      rs.close();

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }
  
  
  public void _testBug() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    String[] status = {"open","filled", "cancelled"};
    st.execute("create table trade.sellorders "
        + "(oid int not null constraint orders_pk primary key, "
        + "cid int, sid int, qty int, status varchar(10), "
        + "tid int, "
        + "constraint status_ch check (status in ('cancelled', 'open', 'filled')))  offheap" );
    st.execute("create index cid_index on trade.sellorders(cid)");
    PreparedStatement psInsert = conn.prepareStatement("insert into trade.sellorders values (?,?,?,?,?,?)");
    for(int i = 0; i < 2; ++i) {
      psInsert.setInt(1,i);
      psInsert.setInt(2,i);
      psInsert.setInt(3,i);
      psInsert.setInt(4,i*1000);
      psInsert.setString(5, "open");
      psInsert.setInt(6,1);
      psInsert.executeUpdate();
    }
    ResultSet rs = null;
    int count = 0;
    
    rs = st.executeQuery("select * from TRADE.SELLORDERS where status = 'open' and tid =1");
   
    while(rs.next()) {
      rs.getInt(1);
      ++count;
    }
    assertTrue(count > 0);
    for(int i = 10; i < 10000; ++i) {
      psInsert.setInt(1,i);
      psInsert.setInt(2,i);
      psInsert.setInt(3,i);
      psInsert.setInt(4,i*1000);
      psInsert.setString(5, status[i%status.length]);
      psInsert.setInt(6,1);
      psInsert.executeUpdate();
    }
    
    rs = st.executeQuery("select * from TRADE.SELLORDERS where status = 'open' and tid =1");
    count = 0;
    while(rs.next()) {
      rs.getInt(1);
      ++count;
    }
    assertTrue(count > 0);
    
  }
  
  public void testForBatchOHAddressCache() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 100; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "cancelled");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }

      String query = "select * from trade.buyorders where  tid > 0";
      // Check for BulkTableScan.BatchOHAddressCache as it is a non index scan
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      rs.next();
      rs.close();

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testForCollectionBasedOHAddressCache() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement  psInsert2  = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 10; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "cancelled");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }

      String query = "select * from trade.buyorders where  tid > 0 order by qty";
      // Check for CollectionBasedOHAddressCache as it is an order by query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                if (!(addressCache instanceof CollectionBasedOHAddressCache)) {
                  fail("expected " + addressCache.getClass() + " to be an instanceof CollectionBasedOHAddressCache");
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      rs.next();
      rs.close();
      GemFireXDQueryObserverHolder.clearInstance();
      

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void _testBug50432() throws Exception {
    
    Connection conn = TestUtil.getConnection();

    Statement s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      byte[] blobBytes = new byte[] { -98, -126, 101, 99, -38,
          4, -115 };
      String tab = " create table portfoliov1 (cid int not null, sid int not null, data blob)  OFFHEAP";
      s.execute(tab);
      Blob data = new HarmonySerialBlob(blobBytes);
      PreparedStatement insert = conn
          .prepareStatement("insert into portfoliov1 values (?, ?,?)");
      insert.setInt(1, 659);
      insert.setInt(2, 753);
      insert.setBlob(3, data);
      insert.executeUpdate();

      ResultSet rs = s.executeQuery("select * from portfoliov1");
      assertTrue(rs.next());
      assertEquals(659, rs.getInt(1));
      assertEquals(753, rs.getInt(2));
      Blob out = rs.getBlob(3);
      InputStream is = out.getBinaryStream();
      int size = is.available();
      byte[] resultBytes = new byte[size];
      int read = 0;
      while(read != size) {
        int partRead = is.read(resultBytes,read,size-read);
        read += partRead;        
      }
      assertTrue(Arrays.equals(blobBytes, resultBytes));
       
    } finally {

      try {
        if (s != null) {
          s.execute("drop table if exists portfoliov1");
          rmcd.waitTillAllClear();
        }
      } catch (SQLException sqle) {
      }
    }
  }
  
  public void testBug49427() throws Exception {

    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;
    Connection conn = TestUtil.getConnection();
    s = conn.createStatement();
    try {

      s.execute("CREATE TYPE trade.UDTPrice EXTERNAL NAME 'udtexamples.UDTPrice' LANGUAGE JAVA");
      s.execute("CREATE TYPE trade.UUID EXTERNAL NAME 'java.util.UUID' LANGUAGE JAVA");
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.
      String tab1 = " create table trade.companies (symbol varchar(10) not null, exchange varchar(10) "
          + " not null, companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, "
          + "companyname char(100), companyinfo clob, note long varchar, histprice trade.udtprice, "
          + "asset bigint, logo varchar(100) for bit data, tid int, "
          + "constraint comp_pk primary key (symbol, exchange)) replicate offheap";

      // We create a table...
      s.execute(tab1);

      psInsert4 = conn.prepareStatement("insert into trade.companies"
          + " values (?, ?, ?,?,?,?,?,?,?,?,?,?)");

      DataGenerator
          .insertIntoCompanies(
              "s",
              "tse",
              (short) 7,
              new byte[] { 123, 119, -95, 80, 60, -94, 78, 12, -106, 37, 126,
                  44, -39, -111, 67, -44 },
              UUID.fromString("7b77a150-3ca2-4e0c-9625-7e2cd99143d4"),
              "i}\\h[?bW1xzZ :jBONH\\f@'A<6XNQh9N`)EkQ)7&!gAl=U$2mx'q3.-:xJ\"!R{@Zp}uG5)J7",
              null, "fcae89533a563a2e648d174f6a7748fb", new UDTPrice(
                  new BigDecimal(28.950000000000001f), new BigDecimal(
                      38.950000000000001f)), 2533297914191977240l, new byte[] {
                  125, -125, 71, 101, -76, -99, 33, -79, -99, 116, -82, -53,
                  -98, 83, 88, -75, -49, 84, 98, 68, 123, -46, -42, 50, -51,
                  -56, 6, -2, -67, 0, 124, 85, -98, -59, -93, 102, 58, -45, 75,
                  -14, 41, -93, 99, 82, -86, 30, -118, -66, -99, 116, -1, -70,
                  -44, 0, -79, -12, -44, 89, -122, -90, 17, 55, -36, -71, 25,
                  111, 13, 89, -65, -53, -25, -89, -72, -10, -57, -89, -119,
                  -92, -15, -67, -98, 111, -52, -106, 20, 19, 123, -43, -42,
                  -42, -27, 17, 92, -65, 22, 101, 29 }, 45, psInsert4);
      try {
        DataGenerator
            .insertIntoCompanies(
                "s",
                "tse",
                (short) 8,
                new byte[] { -81, 112, -67, 52, -86, -2, 78, -73, -91, -105,
                    110, -75, -104, 103, -126, 46 },

                UUID.fromString("af70bd34-aafe-4eb7-a597-6eb59867822e"),
                "2H1z_hP1LZ(Hi0!tPHf\\_Twa-;cJ\\=^3fvxbPTz5UY9xO{`>nSmI'uMFw79RbO wDW;Z>_iyp",
                null, "3c628efc7ab37a3da2f71c515edde23", new UDTPrice(
                    new BigDecimal(25.73), new BigDecimal(35.73)),
                -7292667050878051554l, new byte[] { -124, -90, 51, 9, -112, 9,
                    -83, 31, -86, 42, 29, -59, -116, -104, -126, 7, -54, 83,
                    -95, -90, -104, -96, 121, -116, -40, 71, -49, -49, 7, -62,
                    -6, 34, 84, 79, -115, -33, 46, 79, 37, -68, 104, -81, 7,
                    -108, 58, 71, 106, -86, -55, 91, -70, 47, 82, -103, -37,
                    78, 121, 29, 7, -102, 97, -103, -98, 106, -66, 22, 109,
                    -112, 26, 42, 58, -76, -44, 121, 3, 12, 41, -21, 62, -11 },
                14, psInsert4);
        fail("PK violation expected");
      } catch (SQLException sqle) {
        assertEquals("23505", sqle.getSQLState());
      }

      try {
        DataGenerator.insertIntoCompanies("s", "tse", (short) 1,
            new byte[] { 72, -28, 97, -127, 7, 26, 79, 11, -99, -28, 57, 80,
                84, 48, 30, -40 }, UUID
                .fromString("48e46181-071a-4f0b-9de4-395054301ed8"),
            "_2FOQU!_-\"tFam55(+5szXb*Ka/M7<=mLam>+S`)|ksVOTD", new SerialClob(
                "ceaf4ca3ebb26021d1c8f65a7ee4ac".toCharArray()),
            "535179aa524b229485b0572f992a959a", new UDTPrice(new BigDecimal(
                36.31f), new BigDecimal(46.31f)), -3134025377833192825l,
            new byte[] { 116, -91, 90, 14, 71, -122, 62, 39, -86, -20, 3, -18,
                -119, -113, 111, -21, 18, -36, 38, -68, -75, 11, 83, 6, 95, 11,
                -79, 72, 59, -29, 18, 102, 107, -28, 60, -35, -11, -32, -92, 4,
                -113, 96, 92, 82, -46, -97, -90, -42, 4, -34, 97, -100, -122,
                -117, -61, -118, 81, 28, -44, -21, -116, -17, -35, -109, 47,
                -104, 40, -65 }, 47, psInsert4);
        fail("PK violation expected");
      } catch (SQLException sqle) {
        assertEquals("23505", sqle.getSQLState());
      }

      try {
        DataGenerator
            .insertIntoCompanies(
                "s",
                "tse",
                (short) 6,
                new byte[] { 36, 121, 0, -67, -96, -103, 79, 104, -127, 101, 9,
                    -103, -5, 8, -91, -105 },

                UUID.fromString("247900bd-a099-4f68-8165-0999fb08a597"),
                "MgOiL'<,?w_L6m2w)DZT?V9[ah)MOa|9SVeL97c*eGcc&HziIS1z1aIp o{&4-w'j\"8Y&i#awy79C\"A)cX",
                new SerialClob("f01aa5c3c3d944d158dcc7c8cad05b".toCharArray()),
                "981825e8ac81c7a98b945aa2c63c40a", new UDTPrice(new BigDecimal(
                    36.06), new BigDecimal(46.06)), -9015588843748895269l,
                new byte[] { -64, -122, 60, 124, 53, -46, 111, 105, -47, 27,
                    46, 8, 113, -39, -89, 39, -1, 110, 57, 55, 30, -99, -76,
                    -98, 30, -100, 83, 45, -10, -89, 1, -125, -47, -98, -70,
                    -9, 100 }, 16, psInsert4);
        fail("PK violation expected");
      } catch (SQLException sqle) {
        assertEquals("23505", sqle.getSQLState());
      }

      try {
        DataGenerator.insertIntoCompanies("s", "tse", (short) 3, new byte[] {
            104, 38, -56, -11, 121, 58, 73, -63, -81, 54, 102, 46, -69, -15,
            60, 31 },

        UUID.fromString("6826c8f5-793a-49c1-af36-662ebbf13c1f"),
            "z(#(Adud!mcy'H/CvaHUbg:ygU|", new SerialClob(
                "2b168c8dc33a25cc22faa97f83d545".toCharArray()),
            "fd5b3e2731ffff2b3e442fb8d2844c", new UDTPrice(
                new BigDecimal(28.48), new BigDecimal(38.48)),
            -1353682257425529307l, new byte[] { -32, -23, -12, 116, -98, -106,
                -17, 51, -105, 124, 1, 56, -5, 77, -74, -10, 9, 45, -76, 80,
                -41, -40, 61, 65, -85, -114, -54, 37, 91, 121, -123, -1, -65,
                112, -43, 35, 103, 126, -123, -109, 80, -45, 78 }, 13,
            psInsert4);
        fail("PK violation expected");
      } catch (SQLException sqle) {
        assertEquals("23505", sqle.getSQLState());
      }

      try {
        DataGenerator.insertIntoCompanies("s", "tse", (short) 2, new byte[] {
            96, 62, 12, -118, 103, 28, 64, -104, -78, 31, -73, -114, -66, -104,
            53, 18 }, UUID.fromString("603e0c8a-671c-4098-b21f-b78ebe983512"),
            "cW{h%>dG*fQ4Q$F<,LX-{0yx8%Q;ai3mRQi`Cr0gmfy4xEI%$uuJGPp `%B",
            null, "29f8a66124314958358ce1bddad5d6", new UDTPrice(
                new BigDecimal(25.75f), new BigDecimal(35.75f)),
            -5335896508433741988l, new byte[] { 72, -2, 112, -52, -111, -29,
                43, -97, 113, -107, 72, -91, -36, 42, 59, 63, -119, 71, -62,
                -1, -52, -119, -12, -119, 15, -103, 95, -100, 84, -99, 104,
                124, -23, -88, 63, 71, 58, -116, -111, 81, 27, -76, 92, -84,
                -99, -100, 101, 26, 91, 23, -30, -62 }, 53, psInsert4);
        fail("PK violation expected");
      } catch (SQLException sqle) {
        assertEquals("23505", sqle.getSQLState());
      }

      this.doOffHeapValidations();

      s.executeUpdate("delete from trade.companies");
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.comapnies");
        rmcd.waitTillAllClear();
      }
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testBug51451_clob() throws Exception {
    //Properties props = new Properties();
    //props.setProperty("log-level", "fine");
    //props.setProperty("gemfirexd.debug.true", "QueryDistribution,TraceTran,TraceTranVerbose");
    
    Connection cxn = TestUtil.getConnection();
    
    String createTable1 = "create table trade.customers (cid int not null, " +
        "cust_name varchar(100), since date, addr varchar(100), tid int, " +
        "primary key (cid) , clob_details clob , networth_clob clob , " +
        "buyorder_clob clob )  replicate offheap";
//        "buyorder_clob clob )  replicate ";
    
    String createTable2 = "create table trade.networth (cid int not null, " +
        "cash decimal (30, 20), securities decimal (30, 20), " +
        "loanlimit int, availloan decimal (30, 20),  tid int, " +
        "constraint netw_pk primary key (cid), constraint cust_newt_fk " +
        "foreign key (cid) references trade.customers (cid) on delete restrict, " +
        "constraint cash_ch check (cash>=0), constraint sec_ch check (securities >=0), " +
        "constraint availloan_ck check (loanlimit>=availloan and availloan >=0) , " +
        "clob_details clob ) offheap";
//        "clob_details clob ) ";
    
    cxn.createStatement().execute(createTable1);
    cxn.createStatement().execute(createTable2);
    
    cxn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    cxn.setAutoCommit(false);
    
    java.sql.PreparedStatement ps1 = cxn.prepareStatement("insert into trade.customers values " +
        "(?, ?, ?, ?, ? , ?, ?, ?)");
    
    for (int i = 1; i <= 5; i++) {
      ps1.setInt(1, i);
      ps1.setString(2, "name" + i);
      ps1.setDate(3, new Date(1980, 1, 1));
      ps1.setString(4, "address" + i);
      ps1.setInt(5, i);
      ps1.setObject(6, "{f" + i + ":" +i + "}");
      ps1.setObject(7, "{f" + i + ":" +i + "}");
      ps1.setObject(8, "{f" + i + ":" +i + "}");
      ps1.addBatch();
    }
    ps1.executeBatch();
    cxn.commit();
    
    java.sql.PreparedStatement ps2 = cxn.prepareStatement("insert into trade.networth values " +
        "(?, ?, ?, ?, ? , ?, ?)");
    for (int i = 1; i <= 5; i++) {
      ps2.setInt(1, i);
      ps2.setInt(2, i);
      ps2.setInt(3, i);
      ps2.setInt(4, i*2);
      ps2.setInt(5, i);
      ps2.setInt(6, i);
      ps2.setObject(7, "{f1 : "+ i + "}");
      ps2.addBatch();
    }
    ps2.executeBatch();
    cxn.commit();
    
    String updateStatement = "update trade.networth set securities=? ,  " +
        "clob_details = ? where tid  = ?";
    
    //update a row
    java.sql.PreparedStatement ps3 = cxn.prepareStatement(updateStatement);
    ps3.setInt(1, 1000);
    ps3.setObject(2, "{f1 : " +  1000 + "}");
    ps3.setInt(3, 1); //update the row with tid=1
    ps3.execute();
      
//    cxn.commit();
    
    // update the same row again in the same tx
    ps3.setInt(1, 2000);
    ps3.setObject(2, "{f1 : " + 2000 + "}");
    ps3.setInt(3, 1);
    ps3.execute();
    
    cxn.commit();
    
    ResultSet rs = cxn.createStatement().executeQuery("select * from trade.networth order by tid");
    int i = 1;
    while(rs.next()) {
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      if (i == 1) {
        assertEquals(i * 2000, rs.getInt(3));
      } else {
        assertEquals(i, rs.getInt(3));
      }
      assertEquals(i*2, rs.getInt(4));
      assertEquals(i, rs.getInt(5));
      assertEquals(i, rs.getInt(6));
      rs.getString(7);
      i++;
    }
  }


  public static class DataGenerator {
    protected static String[] exchanges = { "nasdaq", "nye", "amex", "lse",
        "fse", "hkse", "tse" /* , "notAllowed" */
    };

    private Random rand = new Random();

    void insertIntoSecurities(PreparedStatement ps, int sec_id)
        throws SQLException {
      ps.setInt(1, sec_id);
      ps.setString(2, getSymbol(1, 8));
      ps.setBigDecimal(3, getPrice());
      ps.setString(4, getExchange());
      ps.setInt(5, 50);
      try {
        ps.executeUpdate();
      } catch (SQLException sqle) {
        if (sqle.getSQLState().equals("23505")) {
          // ok;
        } else {
          throw sqle;
        }
      }
    }

    public static void insertIntoCompanies(String symbol, String exchange,
        short companyType, byte[] uidBytes, UUID uuid, String companyName,
        Clob companyInfo, String note, UDTPrice udtPrice, long asset,
        byte[] logo, int tid, PreparedStatement ps) throws Exception {
      ps.setString(1, symbol);
      ps.setString(2, symbol);
      ps.setShort(3, companyType);
      ps.setBytes(4, uidBytes);
      ps.setObject(5, uuid);
      ps.setString(6, companyName);
      if(companyInfo == null) {
        ps.setNull(7, Types.CLOB);
      }else {
        ps.setClob(7, companyInfo);
      }
      ps.setString(8, note);
      ps.setObject(9, udtPrice);

      ps.setLong(10, asset);
      ps.setBytes(11, logo);
      ps.setInt(12, tid);
      ps.executeUpdate();
    }
    
    public void insertIntoCompanies(PreparedStatement ps, String symbol,
        String exchange, int extraLobColsCount) throws SQLException {
      UUID uid = UUID.fromString("3f90bd11-dfc3-4fd0-b758-08ed895b5c02");
      short companyType = 8;
      String companyName = "+=uqGd1w]bR6z[!j]|Chc>WTdf";
      Clob[] companyInfo = null;// getClob(1);
      String note = getString(31000, 32700);
      UDTPrice price = getRandomPrice();
      long asset = rand.nextLong();
      byte[] logo = getByteArray(100);
      ps.setString(1, symbol);
      ps.setString(2, exchange);
      ps.setShort(3, companyType);
      ps.setBytes(4, getUidBytes(uid));
      ps.setObject(5, uid);
      ps.setString(6, companyName);
      if (companyInfo == null)
        ps.setNull(7, Types.CLOB);
      else
        ps.setClob(7, companyInfo[0]);
      ps.setString(8, note);
      ps.setObject(9, price);

      ps.setLong(10, asset);
      ps.setBytes(11, logo);
      ps.setInt(12, 10);
      for(int i=0; i < extraLobColsCount;++i) {
        byte[] newBytes = new byte[90];
        ByteBuffer buff = ByteBuffer.wrap(newBytes);
        buff.putInt(i);       
        ps.setBytes(12+(i+1), newBytes);
      }
      ps.executeUpdate();

    }

    protected BigDecimal getPrice() {
      // if (!reproduce39418)
      return new BigDecimal(Double.toString((rand.nextInt(10000) + 1) * .01));
      // else
      // return new BigDecimal (((rand.nextInt(10000)+1) * .01)); //to reproduce
      // bug 39418
    }

    // get an exchange
    protected String getExchange() {
      return exchanges[rand.nextInt(exchanges.length)]; // get a random exchange
    }

    public static byte[] getUidBytes(UUID uuid) {
      /*
       * byte[] bytes = new byte[uidLength]; rand.nextBytes(bytes); return
       * bytes;
       */
      if (uuid == null)
        return null;
      long[] longArray = new long[2];
      longArray[0] = uuid.getMostSignificantBits();
      longArray[1] = uuid.getLeastSignificantBits();

      byte[] bytes = new byte[16];
      ByteBuffer bb = ByteBuffer.wrap(bytes);
      bb.putLong(longArray[0]);
      bb.putLong(longArray[1]);
      return bytes;
    }

    protected byte[] getByteArray(int maxLength) {
      int arrayLength = rand.nextInt(maxLength) + 1;
      byte[] byteArray = new byte[arrayLength];
      for (int j = 0; j < arrayLength; j++) {
        byteArray[j] = (byte) (rand.nextInt(256));
      }
      return byteArray;
    }

    protected UDTPrice getRandomPrice() {
      BigDecimal low = new BigDecimal(
          Double.toString((rand.nextInt(3000) + 1) * .01)).add(new BigDecimal(
          "20"));
      BigDecimal high = new BigDecimal("10").add(low);
      return new UDTPrice(low, high);
    }

    protected String getString(int minLength, int maxLength) {
      int length = rand.nextInt(maxLength - minLength + 1) + minLength;
      return getRandPrintableVarChar(length);
    }

    protected String getSymbol(int minLength, int maxLength) {
      int aVal = 'a';
      int symbolLength = rand.nextInt(maxLength - minLength + 1) + minLength;
      char[] charArray = new char[symbolLength];
      for (int j = 0; j < symbolLength; j++) {
        charArray[j] = (char) (rand.nextInt(26) + aVal); // one of the char in
                                                         // a-z
      }

      return new String(charArray);
    }
    
    public static String convertClobToString(Clob clob) throws IOException, 
    SQLException{
      Reader reader = clob.getCharacterStream();
      StringBuilder sb = new StringBuilder();
      int ch;
      while( (ch = reader.read()) != -1) {
        sb.append((char)ch);
      }
      return sb.toString();
    }

    protected String getRandPrintableVarChar(int length) {
      if (length == 0) {
        return "";
      }

      int sp = ' ';
      int tilde = '~';
      char[] charArray = new char[length];
      for (int j = 0; j < length; j++) {
        charArray[j] = (char) (rand.nextInt(tilde - sp) + sp);
      }
      return new String(charArray);
    }

    protected char[] getCharArray(int length) {
      /*
       * int cp1 = 56558; char c1 = (char)cp1; char ch[]; ch =
       * Character.toChars(cp1);
       * 
       * c1 = '\u01DB'; char c2 = '\u0908';
       * 
       * Log.getLogWriter().info("c1's UTF-16 representation is is " + c1);
       * Log.getLogWriter().info("c2's UTF-16 representation is is " + c2);
       * Log.getLogWriter().info(cp1 + "'s UTF-16 representation is is " +
       * ch[0]);
       */
      int arrayLength = rand.nextInt(length) + 1;
      char[] charArray = new char[arrayLength];
      for (int j = 0; j < arrayLength; j++) {
        charArray[j] = getValidChar();
      }
      return charArray;
    }

    protected char getValidChar() {

      // TODO, to add other valid unicode characters
      return (char) (rand.nextInt('\u0527'));
    }

    protected char[] getAsciiCharArray(int length) {
      int arrayLength = rand.nextInt(length) + 1;
      char[] charArray = new char[arrayLength];
      for (int j = 0; j < arrayLength; j++) {
        charArray[j] = (char) (rand.nextInt(128));
      }
      return charArray;
    }

    public Clob[] getClob(int size) {
      Clob[] profile = new Clob[size];
      int maxClobSize = 10000;

      try {
        for (int i = 0; i < size; i++) {
          if (rand.nextBoolean()) {
            char[] chars = (rand.nextInt(10) != 0) ? getAsciiCharArray(rand
                .nextInt(maxClobSize) + 1) : getCharArray(rand
                .nextInt(maxClobSize) + 1);
            profile[i] = new SerialClob(chars);

          } else  {
            char[] chars = new char[500];
            profile[i] = new SerialClob(chars);
          } // remaining are null profile
        }
      } catch (SQLException se) {
        fail("unexpected SQLException " + se, se);
      }
      return profile;
    }
  }
  
  public static class SecuritiesLoader implements RowLoader {
    private String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
        "hkse", "tse" };
    @Override
    public Object getRow(String schemaName, String tableName,
        Object[] primarykey) throws SQLException {
      Integer num = (Integer)primarykey[0];
      Object[] values = new Object[5];
      values[0] = num;
      
      values[1]= "symbol" + num.intValue() * -1;
      values[2] = new BigDecimal(17263548.73937f);
      values[3]= exchange[(-1 * num.intValue()) % 7];
      values[4] = Integer.valueOf(2);
      return values;
    }

    @Override
    public void init(String initStr) throws SQLException {
      // TODO Auto-generated method stub
      
    }
    
  }
}
