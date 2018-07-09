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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import org.apache.derbyTesting.junit.JDBC;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.offheap.OffHeapMemoryStats;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.jdbc.LocalCSLMIndexTest;
import com.pivotal.gemfirexd.jdbc.TableDiskPersistenceTest;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase.RegionMapClearDetector;
import com.pivotal.gemfirexd.jdbc.offheap.OffHeapTest.DataGenerator;

public class OffHeapTableDiskPersistence  extends TableDiskPersistenceTest {
  private RegionMapClearDetector rmcd = null;
  
  public OffHeapTableDiskPersistence(String name) {
    super(name);
  }

  public static void main(String[] args)
  {
    TestRunner.run(new TestSuite(OffHeapTableDiskPersistence.class));
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
  
  
  @Override
  public String getSuffix() {
    return " offheap " + super.getSuffix();
  }
  
  
  //TODO:Asif: Enable it later
  @Override
  public void testRegionEntryTypeForPersistentOverflowPRTable()
      throws Exception
  {
    
  }
  
  //TODO:Asif: Enable it later
  @Override
  public void testRegionEntryTypeForPersistentOverflowRedundantPRTable()
      throws Exception
  {
    
  }
  
  
  //TODO:Asif: Enable it later
  @Override
  public void testRegionEntryTypeForPersistentOverflowReplicateTable()
      throws Exception
  {
    
  }
  
  public void testDiskPersistenceRecoveryForLobTables_1() throws Exception {

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
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname varchar(100), "
        + "companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, "
        + "logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange)) " + getSuffix());
    
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
   for(int i = 1; i < 2; i+=3) {
    DataGenerator dg = new DataGenerator();
    dg.insertIntoCompanies(psComp, "SYMB"+i, "NSE",0);
    //dg.insertIntoCompanies(psComp, "SYMB2"+(i+1), "BSE");
    //dg.insertIntoCompanies(psComp, "SYMB3"+(i+2), "LSE");
   }
    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);

    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);

    //shut down & recover
    conn.close();
    shutDown();
    // loadDriver();
    conn = getConnection();
    // wait for background tasks to finish
    for (DiskStoreImpl dsi : Misc.getGemFireCache().listDiskStores()) {
      dsi.waitForBackgroundTasks();
    }
    this.doOffHeapValidations();
    st = conn.createStatement();
    // Execute a scan query
    rs = st.executeQuery("select * from trade.companies where companyname is not null ");
    int numRows = 0;
    int numCols = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      for(int i =0; i < numCols;++i)
      rs.getObject(i+1);
      ++numRows;
    }
    assertEquals(1, numRows);
    
    //now do an update with lob columns unmodified
    int numUpdate= st
        .executeUpdate("update trade.companies set companyname = 'zzz'");
    assertEquals(1, numUpdate);
    assertEquals(JDBC.getTotalRefCount(ma), refCountAfterInsert);
    
    conn.close();
    shutDown();
    // loadDriver();
    conn = getConnection();
    st = conn.createStatement();
    // wait for background tasks to finish
    for (DiskStoreImpl dsi : Misc.getGemFireCache().listDiskStores()) {
      dsi.waitForBackgroundTasks();
    }
    this.doOffHeapValidations();
    rs = st.executeQuery("select companyname from trade.companies where companyname is not null ");
    assertTrue(rs.next());
    assertEquals("zzz", rs.getString(1));
   assertFalse(rs.next());
  }
  
  
  public void testDiskPersistenceRecoveryForLobTables_2() throws Exception {

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
        + " companytype smallint, uid CHAR(16) FOR BIT DATA, uuid trade.UUID, companyname varchar(100), "
        + "companyinfo clob, note long varchar, histprice trade.udtprice, asset bigint, "
        + "logo varchar(100) for bit data, tid int, constraint comp_pk primary key (symbol, exchange)) " + getSuffix());
    
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
   for(int i = 1; i < 2; i+=3) {
    DataGenerator dg = new DataGenerator();
    dg.insertIntoCompanies(psComp, "SYMB"+i, "NSE",0);
    //dg.insertIntoCompanies(psComp, "SYMB2"+(i+1), "BSE");
    //dg.insertIntoCompanies(psComp, "SYMB3"+(i+2), "LSE");
   }
    // get the free memory :
    final long refCountAfterInsert = JDBC.getTotalRefCount(ma);
    assertTrue(refCountBeforeInsert != refCountAfterInsert);

    long freesizeAfterInserts = stats.getFreeMemory();
    assertTrue(freesizeAfterInserts != freeSizeBeforeInserts);

    int numUpdate= st
        .executeUpdate("update trade.companies set companyname = 'zzz'");
    //shut down & recover
    conn.close();
    shutDown();
    // loadDriver();
    conn = getConnection();
    // wait for background tasks to finish
    for (DiskStoreImpl dsi : Misc.getGemFireCache().listDiskStores()) {
      dsi.waitForBackgroundTasks();
    }
    this.doOffHeapValidations();
   
    conn = getConnection();
    st = conn.createStatement();
    
    this.doOffHeapValidations();
    rs = st.executeQuery("select companyname from trade.companies where companyname is not null ");
    assertTrue(rs.next());
    assertEquals("zzz", rs.getString(1));
    
    numUpdate= st
        .executeUpdate("update trade.companies set companyname = 'yyy'");
    
    conn.close();
    shutDown();
    // loadDriver();
    conn = getConnection();
    // wait for background tasks to finish
    for (DiskStoreImpl dsi : Misc.getGemFireCache().listDiskStores()) {
      dsi.waitForBackgroundTasks();
    }
    this.doOffHeapValidations();
   
    conn = getConnection();
    st = conn.createStatement();
    
    this.doOffHeapValidations();
    rs = st.executeQuery("select companyname from trade.companies where companyname is not null ");
    assertTrue(rs.next());
    assertEquals("yyy", rs.getString(1));
    assertFalse(rs.next());
    
  }
  
  @Override
  public void waitTillAllClear() {
	try {  
      rmcd.waitTillAllClear();
	}catch(InterruptedException ie) {
	  Thread.currentThread().interrupt();
	  throw new GemFireXDRuntimeException(ie);
	}
  }
  
  
}
