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
package com.pivotal.gemfirexd.ddl;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.FabricLocator;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.RowLoader;
import com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdListPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdRangePartitionResolver;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GfxdObjectSizer;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.entry.*;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.jdbc.CreateTable2Test;
import com.pivotal.gemfirexd.jdbc.CreateTableTest;
import com.pivotal.gemfirexd.jdbc.GfxdCallbacksTest;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.RMIException;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;
import org.apache.derby.drda.NetworkServerControl;

/**
 * Tests for "CREATE TABLE" and GemFire extensions
 *
 * @author kneeraj
 * @since 6.0
 */
@SuppressWarnings("serial")
public class CreateTableDUnit extends DistributedSQLTestBase {

  private volatile Throwable threadEx;
  //private volatile boolean enableOffheapForTable;
 
  private static Random random = new Random(System.currentTimeMillis());
  
  public CreateTableDUnit(String name) {
    super(name);
  }
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    //off-heap-memory-size
   
  }


  public void testFabricServiceAPI() throws Throwable {
    // start a locator and bunch of servers
    Host host = Host.getHost(0);
    VM loc = host.getVM(0);
    VM server1 = host.getVM(1);
    VM server2 = host.getVM(2);

    final int locPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final int netPort1 = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final int netPort2 = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final int netPort3 = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final Runnable startLoc = new SerializableRunnable("start locator") {
      @Override
      public void run() {
        Properties props = new Properties();
        setCommonProperties(props, 0, null, null);
        props.remove("locators");
        props.remove("start-locator");
        FabricLocator locator = FabricServiceManager.getFabricLocatorInstance();
        try {
          locator.start("localhost", locPort, props);
          locator.startNetworkServer("localhost", netPort1, null);
        } catch (SQLException sqle) {
          throw new TestException("failed to start locator", sqle);
        }
      }
    };
    loc.invoke(startLoc);

    Properties serverProps = new Properties();
    serverProps.setProperty("host-data", "true");
    serverProps.setProperty("locators", "localhost[" + locPort + ']');
    AsyncInvocation async1, async2;
    async1 = server1.invokeAsync(new ServerStart(netPort2, serverProps));
    async2 = server2.invokeAsync(new ServerStart(netPort3, serverProps));
    async1.getResult();
    async2.getResult();

    // now try some operations from a client
    Connection conn = TestUtil.getNetConnection("localhost", netPort1,
        null, null);
    Statement stmt = conn.createStatement();
    stmt.execute("create table t1 (id int, name varchar(100)) persistent");
    stmt.execute("insert into t1 values (1, 'one'), (2, 'two'), "
        + "(3, 'three'), (4, 'four')");
    ResultSet rs = stmt.executeQuery("select sum(id) from t1");
    assertTrue(rs.next());
    assertEquals(10, rs.getInt(1));
    assertFalse(rs.next());

    rs.close();
    stmt.close();
    conn.close();

    // now restart the servers and locator
    final Runnable stopVM = new SerializableRunnable("stop VM") {
      @Override
      public void run() {
        try {
          FabricServiceManager.currentFabricServiceInstance().stop(null);
        } catch (SQLException sqle) {
          throw new TestException("failed to stop VM", sqle);
        }
      }
    };
    server2.invoke(stopVM);
    server1.invoke(stopVM);
    loc.invoke(stopVM);

    loc.invoke(startLoc);
    async1 = server1.invokeAsync(new ServerStart(netPort2, serverProps));
    async2 = server2.invokeAsync(new ServerStart(netPort3, serverProps));
    async1.getResult();
    async2.getResult();

    addExpectedException(null, new Object[] {
        "com.pivotal.gemfirexd.internal.client.am.DisconnectException",
        "java.sql.SQLNonTransientConnectionException" });
    conn = TestUtil.getNetConnection("localhost", netPort1, null, null);
    stmt = conn.createStatement();
    rs = stmt.executeQuery("select sum(id) from t1");
    assertTrue(rs.next());
    assertEquals(10, rs.getInt(1));
    assertFalse(rs.next());

    rs.close();
    stmt.close();
    conn.close();
    removeExpectedException(null, new Object[] {
        "com.pivotal.gemfirexd.internal.client.am.DisconnectException",
        "java.sql.SQLNonTransientConnectionException" });

    // stop everything
    server2.invoke(stopVM);
    server1.invoke(stopVM);
    loc.invoke(stopVM);
  }

  public final class ServerStart extends SerializableRunnable {

    private final int netPort;

    private final Properties extraProps;

    ServerStart(final int port, final Properties props) {
      this.netPort = port;
      this.extraProps = props;
    }

    /**
     * @see Runnable#run()
     */
    @Override
    public void run() {
      Properties props = new Properties();
      setCommonProperties(props, 0, null, this.extraProps);
      FabricServer server = FabricServiceManager.getFabricServerInstance();
      try {
        server.start(props);
        server.startNetworkServer("localhost", this.netPort, null);
      } catch (Exception ex) {
        throw new TestException("failed to start server", ex);
      }
    }
  }

  
  public void testDifferentDDLForTableCreate() throws Exception {
    this.basicDifferentDDLForTableCreate(false);
  }

  public void testDifferentDDLForTableCreateOffheap() throws Exception {
    this.basicDifferentDDLForTableCreate(true);
  }
  
  @SuppressWarnings("unchecked")
  private void basicDifferentDDLForTableCreate(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and four servers
    AsyncVM async1 = invokeStartServerVM(1, 0, "sg1", extra);
    AsyncVM async2 = invokeStartServerVM(2, 0, "Sg1 ", extra);
    AsyncVM async3 = invokeStartServerVM(3, 0, "SG2",  extra);
    AsyncVM async4 = invokeStartServerVM(4, 0, null, extra);
    startClientVMs(1, 0, null,extra);
    joinVMs(true, async1, async2, async3, async4);

    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Check for PARTITION BY RANGE
    clientSQLExecute(1,
        "create table EMP.PARTITIONTESTTABLE_ONE (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, primary key (ID))"
            + "PARTITION BY RANGE ( ID )"
            + " ( VALUES BETWEEN 20 and 40, VALUES BETWEEN 40 and 59, "
            + "VALUES BETWEEN 60 and 80 )" + getConditionalOffheapSuffix(enableOffHeap));

    // Create a set of expected region attributes for the table
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    List<ResolverUtils.GfxdRange> ranges = new ArrayList<ResolverUtils.GfxdRange>();
    ranges.add(new ResolverUtils.GfxdRange(null, new Integer(20), new Integer(40)));
    ranges.add(new ResolverUtils.GfxdRange(null, new Integer(40), new Integer(59)));
    ranges.add(new ResolverUtils.GfxdRange(null, new Integer(60), new Integer(80)));
    GfxdPartitionResolver resolver = new GfxdRangePartitionResolver("ID",
        ranges);
    PartitionAttributesFactory<?, ?> paf = new PartitionAttributesFactory()
        .setPartitionResolver(resolver);
    if(enableOffHeap) {
      paf.setLocalMaxMemory(500);
    }
    PartitionAttributes<? , ?> pa = paf.create();
    expectedAttrs.setPartitionAttributes(pa);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasScope(false);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    expectedAttrs.setEnableOffHeapMemory(enableOffHeap);
   
    // Check the table attributes on the servers
    serverVerifyRegionProperties(1, "EMP", "PARTITIONTESTTABLE_ONE",
        expectedAttrs);
    serverVerifyRegionProperties(2, "EMP", "PARTITIONTESTTABLE_ONE",
        expectedAttrs);

    // Check that the local-max-memory PR attribute is zero on the client
    pa = new PartitionAttributesFactory().setPartitionResolver(resolver)
        .setLocalMaxMemory(0).create();
    expectedAttrs.setPartitionAttributes(pa);
    expectedAttrs.setEnableOffHeapMemory(false);
    clientVerifyRegionProperties(1, "EMP", "PARTITIONTESTTABLE_ONE",
        expectedAttrs);

    // Insert some rows
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (20, 'Second')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (30, 'Third')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (40, 'Fourth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (60, 'Sixth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (70, 'Seventh')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (75, 'Eighth')");

    // Now check on the servers that the values in same range lie in the same
    // bucket
    for (int index = 0; index < 2; ++index) {
      this.serverVMs.get(index).invoke(
          CreateTableDUnit.class,
          "checkBucketValuesInList",
          new Object[] { "EMP", "PARTITIONTESTTABLE_ONE", ranges,
              Boolean.TRUE });
    }

    // Now also insert some values that do not lie in any of the ranges
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (10, 'First')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ONE values (50, 'Fifth')");

    // Verify the inserted rows
    sqlExecuteVerify(new int[] { 1 }, null,
        "select ID, DESCRIPTION from EMP.PARTITIONTESTTABLE_ONE", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "emp_partitionby_sel",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    // Check for PARTITION BY LIST
    clientSQLExecute(1,
        "create table EMP.PARTITIONTESTTABLE_ZERO (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, primary key (ID))"
            + "PARTITION BY LIST ( ID ) ( VALUES (10, 20 ),"
            + " VALUES (50, 60), VALUES (12, 34, 45))"+ getConditionalOffheapSuffix(enableOffHeap));

    RegionAttributesCreation expectedAttrsLst = new RegionAttributesCreation();
    expectedAttrsLst.setDataPolicy(DataPolicy.PARTITION);
    expectedAttrsLst.setConcurrencyChecksEnabled(false);

    List<List<Object>> listofLists = new ArrayList<List<Object>>();

    List<Object> list_1 = new ArrayList<Object>();
    list_1.add(new Integer(10));
    list_1.add(new Integer(20));
    listofLists.add(list_1);

    List<Object> list_2 = new ArrayList<Object>();
    list_2.add(new Integer(50));
    list_2.add(new Integer(60));
    listofLists.add(list_2);

    List<Object> list_3 = new ArrayList<Object>();
    list_3.add(new Integer(12));
    list_3.add(new Integer(34));
    list_3.add(new Integer(45));
    listofLists.add(list_3);

    GfxdPartitionResolver resolver_list = new GfxdListPartitionResolver(
        listofLists);

   paf = new PartitionAttributesFactory()
    .setPartitionResolver(resolver_list);
   if(enableOffHeap) {
     paf.setLocalMaxMemory(500);
   }

    PartitionAttributes pa_list = paf.create();
    expectedAttrsLst.setPartitionAttributes(pa_list);
    expectedAttrsLst.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrsLst.setConcurrencyChecksEnabled(false);
    expectedAttrsLst.setAllHasFields(true);
    expectedAttrsLst.setHasScope(false);
    expectedAttrsLst.setHasDiskDirs(false);
    expectedAttrsLst.setHasDiskWriteAttributes(false);
    expectedAttrsLst.setEnableOffHeapMemory(enableOffHeap);
    // Check the table attributes on the servers
    serverVerifyRegionProperties(1, "EMP", "PARTITIONTESTTABLE_ZERO",
        expectedAttrsLst);
    serverVerifyRegionProperties(2, "EMP", "PARTITIONTESTTABLE_ZERO",
        expectedAttrsLst);

    // Check that the local-max-memory PR attribute is zero on the client
    // and other servers
    pa_list = new PartitionAttributesFactory().setPartitionResolver(
        resolver_list).setLocalMaxMemory(0).create();
    expectedAttrsLst.setPartitionAttributes(pa_list);
    expectedAttrsLst.setEnableOffHeapMemory(false);
    clientVerifyRegionProperties(1, "EMP", "PARTITIONTESTTABLE_ZERO",
        expectedAttrsLst);
    serverVerifyRegionProperties(3, "EMP", "PARTITIONTESTTABLE_ZERO",
        expectedAttrsLst);
    serverVerifyRegionProperties(4, "EMP", "PARTITIONTESTTABLE_ZERO",
        expectedAttrsLst);

    // Insert some rows
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (10, 'First')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (20, 'Second')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (50, 'Third')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (60, 'Fourth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (12, 'Fifth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (34, 'Sixth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (45, 'Seventh')");

    // Now check on the servers that the values in same range lie in the same
    // bucket
    for (int index = 0; index < 2; ++index) {
      this.serverVMs.get(index).invoke(
          CreateTableDUnit.class,
          "checkBucketValuesInList",
          new Object[] { "EMP", "PARTITIONTESTTABLE_ZERO", listofLists,
              Boolean.FALSE });
    }

    // Now also insert some values that do not lie in any of the lists
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (11, 'Eighth')");
    clientSQLExecute(1,
        "insert into EMP.PARTITIONTESTTABLE_ZERO values (13, 'Ninth')");

    // Verify the inserted rows
    sqlExecuteVerify(new int[] { 1 }, null,
        "select ID, DESCRIPTION from EMP.PARTITIONTESTTABLE_ZERO", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "emp_partitionby_list",
        false /* do not use prep stmt */,
        false /* do not check for type information */);

    // ////////////////////

    clientSQLExecute(1,
        "create table EMP.PARTITIONTESTTABLE_TWO (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, primary key (ID))"
            + "PARTITION BY PRIMARY KEY BUCKETS 73 RECOVERYDELAY 100"+ getConditionalOffheapSuffix(enableOffHeap));

    // Create the expected region attributes
    RegionAttributesCreation partAttrs = new RegionAttributesCreation();
    partAttrs.setDataPolicy(DataPolicy.PARTITION);
    final PartitionAttributesFactory pafact = new PartitionAttributesFactory();
    pafact.setTotalNumBuckets(73)
    .setRecoveryDelay(100).setPartitionResolver(
        new GfxdPartitionByExpressionResolver());
    if(enableOffHeap) {
      pafact.setLocalMaxMemory(500);
    }
    partAttrs.setPartitionAttributes(pafact.create());
    partAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    partAttrs.setConcurrencyChecksEnabled(false);
    partAttrs.setAllHasFields(true);
    partAttrs.setHasScope(false);
    partAttrs.setHasDiskDirs(false);
    partAttrs.setHasDiskWriteAttributes(false);
    partAttrs.setEnableOffHeapMemory(enableOffHeap);
    // verify the expected attributes on data stores
    serverVerifyRegionProperties(1, "EMP", "PARTITIONTESTTABLE_TWO", partAttrs);
    serverVerifyRegionProperties(2, "EMP", "PARTITIONTESTTABLE_TWO", partAttrs);

    RegionAttributesCreation emptyAttrs = new RegionAttributesCreation();
    emptyAttrs.setDataPolicy(DataPolicy.PARTITION);
    emptyAttrs.setPartitionAttributes(pafact.setLocalMaxMemory(0).create());
    emptyAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    emptyAttrs.setConcurrencyChecksEnabled(false);
    emptyAttrs.setAllHasFields(true);
    emptyAttrs.setHasScope(false);
    emptyAttrs.setHasDiskDirs(false);
    emptyAttrs.setHasDiskWriteAttributes(false);
    emptyAttrs.setEnableOffHeapMemory(false);
    // verify the empty attributes on the client and other data stores
    clientVerifyRegionProperties(1, "EMP", "PARTITIONTESTTABLE_TWO", emptyAttrs);
    serverVerifyRegionProperties(3, "EMP", "PARTITIONTESTTABLE_TWO", emptyAttrs);
    serverVerifyRegionProperties(4, "EMP", "PARTITIONTESTTABLE_TWO", emptyAttrs);

    // /////////////////////////////////

    // INVALIDATE not supported in GemFireXD - test changed to use DESTROY
    serverSQLExecute(3, "create table EMP.TESTTABLE_ONE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "REPLICATE SERVER GROUPS (SG1, SG2) INITSIZE 100 "
        + "EXPIRE TABLE WITH TIMETOLIVE 100 ACTION DESTROY "
        + "EXPIRE ENTRY WITH IDLETIME 10 ACTION DESTROY");

    // Create the expected region attributes
    RegionAttributesCreation replAttrs = new RegionAttributesCreation();
    replAttrs.setDataPolicy(DataPolicy.REPLICATE);
    replAttrs.setScope(Scope.DISTRIBUTED_ACK);
    replAttrs.setConcurrencyChecksEnabled(false);
    ExpirationAttributes expAttrs = new ExpirationAttributes(10,
        ExpirationAction.DESTROY);
    replAttrs.setEntryIdleTimeout(expAttrs);
    expAttrs = new ExpirationAttributes(100, ExpirationAction.DESTROY);
    replAttrs.setRegionTimeToLive(expAttrs);
    replAttrs.setStatisticsEnabled(true);
    replAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    replAttrs.setConcurrencyChecksEnabled(false);
    replAttrs.setAllHasFields(true);
    replAttrs.setHasDiskDirs(false);
    replAttrs.setHasDiskWriteAttributes(false);
    // verify the expected attributes on data stores
    serverVerifyRegionProperties(1, "EMP", "TESTTABLE_ONE", replAttrs);
    serverVerifyRegionProperties(2, "EMP", "TESTTABLE_ONE", replAttrs);
    serverVerifyRegionProperties(3, "EMP", "TESTTABLE_ONE", replAttrs);

    emptyAttrs = new RegionAttributesCreation();
    emptyAttrs.setDataPolicy(DataPolicy.EMPTY);
    emptyAttrs.setScope(Scope.DISTRIBUTED_ACK);
    emptyAttrs.setConcurrencyChecksEnabled(false);
    emptyAttrs.setRegionTimeToLive(expAttrs);
    emptyAttrs.setStatisticsEnabled(true);
    emptyAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    emptyAttrs.setConcurrencyChecksEnabled(false);
    emptyAttrs.setAllHasFields(true);
    emptyAttrs.setHasDiskDirs(false);
    emptyAttrs.setHasDiskWriteAttributes(false);
    // verify the empty attributes on the client and other data stores
    clientVerifyRegionProperties(1, "EMP", "TESTTABLE_ONE", emptyAttrs);
    serverVerifyRegionProperties(4, "EMP", "TESTTABLE_ONE", emptyAttrs);
    serverSQLExecute(4, "create diskstore teststore compactionthreshold 100 " +
    " TimeInterval 20 MaxLogSize 1048576 WriteBufferSize 1024 autocompact true " +
    "queuesize  1024 ");
    serverSQLExecute(4, "create table EMP.TESTTABLE_TWO (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "REPLICATE SERVER GROUPS (sg1, sg2) "
        + "EVICTION BY LRUMEMSIZE 1000 EVICTACTION OVERFLOW "
        + " ASYNCHRONOUS "
        + " 'teststore' ");
    replAttrs = new RegionAttributesCreation();
    replAttrs.setDataPolicy(DataPolicy.REPLICATE);
    replAttrs.setScope(Scope.DISTRIBUTED_ACK);
    replAttrs.setConcurrencyChecksEnabled(false);
    replAttrs.setEvictionAttributes(EvictionAttributes
        .createLRUMemoryAttributes(1000, new GfxdObjectSizer(),
            EvictionAction.OVERFLOW_TO_DISK));
    replAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    replAttrs.setConcurrencyChecksEnabled(false);
    replAttrs.setAllHasFields(true);
    replAttrs.setHasDiskDirs(false);
    replAttrs.setHasDiskWriteAttributes(false);
    replAttrs.setDiskSynchronous(false);
    replAttrs.setDiskStoreName("TESTSTORE");
    // verify the expected attributes on data stores
    serverVerifyRegionProperties(1, "EMP", "TESTTABLE_TWO", replAttrs);
    //replAttrs.setDiskDirs(new File[0]);
    serverVerifyRegionProperties(2, "EMP", "TESTTABLE_TWO", replAttrs);
    //replAttrs.setDiskDirs(new File[0]);
    serverVerifyRegionProperties(3, "EMP", "TESTTABLE_TWO", replAttrs);

    // verify the empty attributes on the client and other data stores
    emptyAttrs = new RegionAttributesCreation();    
    emptyAttrs.setScope(Scope.DISTRIBUTED_ACK);
    emptyAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    emptyAttrs.setConcurrencyChecksEnabled(false);
    emptyAttrs.setAllHasFields(true);
    emptyAttrs.setDiskSynchronous(true);
    emptyAttrs.setDataPolicy(DataPolicy.EMPTY);
    emptyAttrs.setHasDiskWriteAttributes(false);
    emptyAttrs.setHasDiskDirs(false);

    //emptyAttrs.setDiskDirs(new File[0]);
    clientVerifyRegionProperties(1, "EMP", "TESTTABLE_TWO", emptyAttrs);
    serverVerifyRegionProperties(4, "EMP", "TESTTABLE_TWO", emptyAttrs);
  }

  public void testGfxdRowLoaderNullsALso() throws Exception {
    this.basicGfxdRowLoaderNullsALso(false);
  }
  
  public void testGfxdRowLoaderNullsALso_Offheap() throws Exception {
    this.basicGfxdRowLoaderNullsALso(false);
  }
  private void basicGfxdRowLoaderNullsALso(boolean enableOffHeap) throws Exception {
    
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and one server
    startClientVMs(2, 0, null,extra);
    startServerVMs(1, 0, "SG1", extra);
    startServerVMs(1, 0, "SG2", extra);

    // Create a schema
    clientSQLExecute(1, "create schema EMP");

    // Check for PARTITION BY RANGE
    clientSQLExecute(1, "create table EMP.PARTITIONTESTTABLE (ID int not null,"
        + " SECONDID CHAR(2), THIRDID varchar(100),"
        + " primary key (ID)) SERVER GROUPS (sg1)"+ getConditionalOffheapSuffix(enableOffHeap));
    GfxdCallbacksTest.addLoader("EMP", "PARTITIONTESTTABLE",
        "com.pivotal.gemfirexd.ddl.GfxdTestRowLoaderNullAndDifferentReturns",
        null);
    sqlExecuteVerify(new int[] { 2 }, null,
        "select ID from EMP.PARTITIONTESTTABLE where ID = 1", null, null);

    addExpectedException(new int[] { 1 }, new int[] { 1 },
        "from loader as primary key");
    try {
      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from EMP.PARTITIONTESTTABLE where ID = 11", null, null);
      fail("expected an exception from loader");
    } catch (SQLException ex) {
      if (!ex.getMessage().contains("from loader as primary key")) {
        throw ex;
      }
    }
    removeExpectedException(new int[] { 1 }, new int[] { 1 },
        "from loader as primary key");

    // sqlExecuteVerify(new int[] { 2 }, null,
    // "select * from EMP.PARTITIONTESTTABLE", null, null);
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select * from EMP.PARTITIONTESTTABLE");
    ResultSet rs = st.getResultSet();
    boolean gotResult = false;
    while (rs.next()) {
      gotResult = true;
      assertEquals("33", rs.getString(2));
    }
    assertTrue(gotResult);
  }

 public void testDDLForTableCreateLoader() throws Exception {
    this.basicDDLForTableCreateLoader(false);
  }
  
  //DISABLED for bug 48526
  public void testDDLForTableCreateLoader_Offheap() throws Exception {
    this.basicDDLForTableCreateLoader(true);
  }
  private void basicDDLForTableCreateLoader(boolean enableOffHeap) throws Exception {
    
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and one server
    startVMs(1, 1, -1,null,extra);

    // Create a schema
    clientSQLExecute(1, "create schema EMP");

    // Check for PARTITION BY RANGE
    clientSQLExecute(
        1,
        "create table EMP.PARTITIONTESTTABLE (ID int not null,"
            + " SECONDID int not null, THIRDID int not null,"
            + " primary key (ID))"+ getConditionalOffheapSuffix(enableOffHeap));
    GfxdCallbacksTest.addLoader("EMP", "PARTITIONTESTTABLE",
        "com.pivotal.gemfirexd.ddl.GfxdTestRowLoader",
        null);
    sqlExecuteVerify(new int[] { 1 }, null,
        "select ID from EMP.PARTITIONTESTTABLE where ID = 1", null, null);

    sqlExecuteVerify(new int[] { 1 }, null,
        "select ID from EMP.PARTITIONTESTTABLE where ID = 2", null, null);

    sqlExecuteVerify(new int[] { 1 }, null,
        "select ID from EMP.PARTITIONTESTTABLE where ID = 3", null, null);

    // Get the cache loader
    final Cache cache = Misc.getGemFireCache();
    final Region<?, ?> regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");
    final RegionAttributes<?, ?> rattr = regtwo.getAttributes();
    final CacheLoader<?, ?> ldr = rattr.getCacheLoader();
    final GfxdCacheLoader gfxdldr = (GfxdCacheLoader)ldr;
    assertNull(gfxdldr);

    assertEquals("Number of entries expected to be 3", regtwo.size(), 3);
    for (Object keyObj : regtwo.keySet()) {
      RegionKey key = (RegionKey)keyObj;
      assertNotNull(key);
      Object val = regtwo.get(key);
      assertNotNull(val);

      GemFireContainer gfContainer = (GemFireContainer)regtwo
          .getUserAttribute();
      if (gfContainer.isByteArrayStore()) {
           assertTrue(val instanceof byte[]);
      }
      else {
        assertTrue(val instanceof DataValueDescriptor[]);
      }
      assertNotNull(val);
    }
  }

  public void testDistributedInsert() throws Exception {
    this.basicDistributedInsert(false);
  }
  
  public void testDistributedInsert_OffHeap() throws Exception {
    this.basicDistributedInsert(true);
  }
  
  private void basicDistributedInsert(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startServerVMs(1, 0, "sg1", extra);
    startClientVMs(1, 0, null, extra);

    // clientSQLExecute(1, "create schema trade default server groups (SG1)");

    // clientSQLExecute(1, "create table trade.customers (cid int not null, " +
    // "cust_name varchar(100), " + "since int, addr varchar(100), tid int, " +
    // "primary key (cid)) REPLICATE SERVER GROUPS (SG1) ");

    // clientSQLExecute(1,
    // "insert into trade.customers values (1,'XXXX1',1,'BEAV1',1)");

    clientSQLExecute(1, "create schema trade default server groups (SG1)");

    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, " + "primary key (cid))"+ getConditionalOffheapSuffix(enableOffHeap));

    clientSQLExecute(1,
        "insert into trade.customers values (1,'XXXX1',1,'BEAV1',1)");
  }
  
  public void testUpdateColumn() throws Exception {
    this.basicUpdateColumn(false); 
  }

  public void testUpdateColumn_OffHeap() throws Exception {
    this.basicUpdateColumn(true); 
  }
  // test for insert/select/update in normal partitioned tables
  private void basicUpdateColumn(boolean enableOffHeap) throws Exception {
    
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and two servers
    startServerVMs(2, 0, "SG1", extra);
    startClientVMs(1, 0, null,extra);

    // Create a schema
    clientSQLExecute(1, "create schema trade default server groups (SG1)");

    // Create a set of expected region attributes for the schema
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setScope(Scope.DISTRIBUTED_NO_ACK);
    expectedAttrs.setDataPolicy(DataPolicy.EMPTY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    // Test the schema region attributes on client and servers
    clientVerifyRegionProperties(1, "TRADE", null, expectedAttrs);
    serverVerifyRegionProperties(1, "TRADE", null, expectedAttrs);
    serverVerifyRegionProperties(2, "TRADE", null, expectedAttrs);
   
    // Create a replicated table in the above schema
    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, " + "primary key (cid))"+ getConditionalOffheapSuffix(enableOffHeap));

    // Perform an insert
    clientSQLExecute(1, "insert into trade.customers values (1, 'XXXX1', "
        + "1, 'BEAV1', 1)");

    // Verify the inserted value using primary key lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers where cid=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_insert");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers where since=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_insert");

    // Verify the inserted value by region iteration
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_insert");

    // Now update one column of the table and perform the selects again
    clientSQLExecute(1, "update trade.customers set since=2 where cid=1");

    // Verify the inserted value using primary key lookup
    sqlExecuteVerify(
        new int[] { 1 },
        new int[] { 1, 2 },
        "select cust_name,cid,since,addr,tid from trade.customers where cid=1",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "trade_update");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers where since=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "empty");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers where since=2", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update");

    // Verify the inserted value by region iteration
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update");

    // Now update two columns of the table without using primary key
    // and perform the selects again
    clientSQLExecute(1, "update trade.customers set since=3, "
        + "cust_name='XXXX3' where tid=1");

    // Verify the inserted value using primary key lookup
    sqlExecuteVerify(
        new int[] { 1 },
        new int[] { 1, 2 },
        "select cust_name,cid,since,addr,tid from trade.customers where cid=1",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "trade_update2");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers where since=2", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "empty");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers where since=3", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update2");

    // Verify the inserted value by region iteration
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update2");
  }

  public void testReplicatedTable() throws Exception {
    this.basicReplicatedTable(false);
  }
  
  public void testReplicatedTable_OffHeap() throws Exception {
    this.basicReplicatedTable(true);
  }
  // test for insert/select/update in replicated tables
  private void basicReplicatedTable(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and three servers
    startServerVMs(2, 0, "SG1", extra);
    startVMs(1, 1, -1, null,extra);

    // Create a schema
    clientSQLExecute(1, "create schema trade default server groups (SG1)");

    // Create a set of expected region attributes for the schema
    RegionAttributesCreation expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setScope(Scope.DISTRIBUTED_NO_ACK);
    expectedAttrs.setDataPolicy(DataPolicy.EMPTY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    // Test the schema region attributes on client and servers
    clientVerifyRegionProperties(1, "TRADE", null, expectedAttrs);
    serverVerifyRegionProperties(1, "TRADE", null, expectedAttrs);
    serverVerifyRegionProperties(2, "TRADE", null, expectedAttrs);

    // Create a replicated table in the above schema
    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), "
        + "since int, addr varchar(100), tid int, "
        + "primary key (cid)) replicate"+ getConditionalOffheapSuffix(enableOffHeap));

    // Create a set of expected region attributes for the table
    expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.REPLICATE);
    expectedAttrs.setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    expectedAttrs.setEnableOffHeapMemory(enableOffHeap);
    
    // Test the region attributes on servers
    serverVerifyRegionProperties(1, "TRADE", "CUSTOMERS", expectedAttrs);
    serverVerifyRegionProperties(2, "TRADE", "CUSTOMERS", expectedAttrs);

    // Client and other servers must have a replicated table with no caching
    expectedAttrs = new RegionAttributesCreation();
    expectedAttrs.setDataPolicy(DataPolicy.EMPTY);
    expectedAttrs.setScope(Scope.DISTRIBUTED_ACK);
    expectedAttrs.setInitialCapacity(TestUtil.TEST_DEFAULT_INITIAL_CAPACITY);
    expectedAttrs.setConcurrencyChecksEnabled(false);
    expectedAttrs.setAllHasFields(true);
    expectedAttrs.setHasDiskDirs(false);
    expectedAttrs.setHasDiskWriteAttributes(false);
    expectedAttrs.setEnableOffHeapMemory(false);
    clientVerifyRegionProperties(1, "TRADE", "CUSTOMERS", expectedAttrs);
    serverVerifyRegionProperties(3, "TRADE", "CUSTOMERS", expectedAttrs);

    // Perform an insert
    clientSQLExecute(1, "insert into trade.customers values (1, 'XXXX1', "
        + "1, 'BEAV1', 1)");

    // Verify the inserted value using primary key lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers where cid=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_insert");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers where since=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_insert");

    // Verify the inserted value by region iteration
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_insert");

    // Now update one column of the table and perform the selects again
    clientSQLExecute(1, "update trade.customers set since=2 where cid=1");

    // Verify the inserted value using primary key lookup
    sqlExecuteVerify(new int[] { 1 }, null,
        "select cust_name, cid, since, addr, tid from trade.customers "
            + "where cid=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers where since=1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "empty");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers where since=2", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update");

    // Verify the inserted value by region iteration
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update");

    // Now update two columns of the table without using primary key
    // and perform the selects again
    clientSQLExecute(1, "update trade.customers set since=3, "
        + "cust_name='XXXX3' where tid=1");

    // Verify the inserted value using primary key lookup
    sqlExecuteVerify(
        new int[] { 1 },
        new int[] { 1, 2, 3 },
        "select cust_name,cid,since,addr,tid from trade.customers where cid=1",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "trade_update2");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers where since=2", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "empty");

    // Verify the inserted value using non-primary key field lookup
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers where since=3", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update2");

    // Verify the inserted value by region iteration
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "trade_update2");
  }

  private void checkSYSTABLES(int vmNum, final String schema,
      final String table, final String xmlElement) throws CacheException {
    final String schemaName = schema.toUpperCase(Locale.ENGLISH);
    final String tableName = table.toUpperCase(Locale.ENGLISH);
    final String resourceDir = TestUtil.getResourcesDir();
    SerializableRunnable checkSYS = new SerializableRunnable(
        "Clear cache and check tables") {
      @Override
      public void run() throws CacheException {
        // Test the fields from SYSTABLES on client with index lookup
        try {
          TestUtil.sqlExecuteVerifyText("SELECT sc.SCHEMANAME, tab.TABLENAME, "
              + "tab.SERVERGROUPS FROM SYS.SYSSCHEMAS sc, SYS.SYSTABLES tab "
              + "WHERE sc.SCHEMANAME='" + schemaName
              + "' AND tab.TABLENAME='" + tableName
              + "' AND sc.SCHEMAID = tab.SCHEMAID", resourceDir
              + "/lib/checkCreateTable.xml", xmlElement, true, false);

          // Test the fields from SYSSCHEMAS on client with non-index table scan
          TestUtil.sqlExecuteVerifyText("SELECT sc.SCHEMANAME, tab.TABLENAME, "
              + "tab.SERVERGROUPS FROM SYS.SYSSCHEMAS sc, SYS.SYSTABLES tab "
              + "WHERE sc.SCHEMANAME LIKE '" + schemaName
              + "%' AND tab.TABLENAME LIKE '" + tableName
              + "%' AND sc.SCHEMAID = tab.SCHEMAID", resourceDir
              + "/lib/checkCreateTable.xml", xmlElement, true, false);
        } catch (Exception ex) {
          throw new CacheException(ex) {
          };
        }
      }
    };
    VM vm;
    if (vmNum < 0) {
      vm = this.serverVMs.get(-vmNum - 1);
    }
    else {
      vm = this.clientVMs.get(vmNum - 1);
    }
    if (vm != null) {
      vm.invoke(checkSYS);
    }
    else {
      checkSYS.run();
    }
  }

  /** expect exceptions when there are no VMs in appropriate server groups */
  public void testServerGroupsNoVMs() throws Exception {
    this.basicServerGroupsNoVMs(false);
  }
  
  public void testServerGroupsNoVMs_OffHeap() throws Exception {
    this.basicServerGroupsNoVMs(true);
  }
  
  private void basicServerGroupsNoVMs(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and three servers
    startServerVMs(2, 0, "SG1", extra);
    startVMs(1, 1, -1, null,extra);

    // Create a schema
    clientSQLExecute(1, "create schema trade default server groups (SG1)");

    serverSQLExecute(1, "create table trade.customers (cid int not null, "
        + "primary key (cid)) server groups (sg1, SG2)" + getConditionalOffheapSuffix(enableOffHeap));
    TestUtil.checkServerGroups("trade.customers", "sg1", "sg2");

    // Check for both index and non-index lookup in SYSTABLES
    checkSYSTABLES(1, "trade", "customers", "cust_sg");
    checkSYSTABLES(-1, "trade", "customers", "cust_sg");
    checkSYSTABLES(-2, "trade", "customers", "cust_sg");
    checkSYSTABLES(-3, "trade", "customers", "cust_sg");

    // check success with matching server groups
    serverSQLExecute(2,
        "create table trade.securities (sec_id int not null, "
            + "primary key (sec_id)) server groups (sg2, sg1)" + getConditionalOffheapSuffix(enableOffHeap));
    TestUtil.checkServerGroups("trade.securities", "sg1", "sg2");

    // Check for both index and non-index lookup in SYSTABLES
    checkSYSTABLES(1, "trade", "securities", "sec_sg");
    checkSYSTABLES(-1, "trade", "securities", "sec_sg");
    checkSYSTABLES(-2, "trade", "securities", "sec_sg");
    checkSYSTABLES(-3, "trade", "securities", "sec_sg");

    // puts in tables with data stores should succeed
    clientSQLExecute(1, "insert into trade.customers values (1), (2)");
    serverSQLExecute(2, "insert into trade.securities values (4), (5)");

    // now create a table in SG2 which has no available servers
    try {
      serverSQLExecute(3, "create table trade.portfolio (cid int not null, "
          + "sid int not null, qty int not null, availQty int not null, "
          + "subTotal decimal(30,20), tid int, foreign key (sid) references "
          + "trade.securities(sec_id)) server groups (Sg2)"+ getConditionalOffheapSuffix(enableOffHeap));
      fail("Expected an exception while creating a replicated table with no datastore");
    } catch (RMIException ex) {
      assertTrue("Expected an SQLException",
          ex.getCause() instanceof SQLException);
      SQLException sqlEx = (SQLException)ex.getCause();
      if (!"X0Z08".equals(sqlEx.getSQLState())) {
        throw sqlEx;
      }
    }
    /*
    // check non-matching server groups have no default colocation
    TestUtil.checkServerGroups("trade.portfolio", "SG2");
    GfxdPartitionResolver resolver = TestUtil.checkColocation(
        "trade.portfolio", null, null);
    assertTrue(resolver instanceof GfxdPartitionByExpressionResolver
        && ((GfxdPartitionByExpressionResolver)resolver)
            .isDefaultPartitioning());

    // Check for both index and non-index lookup in SYSTABLES
    checkSYSTABLES(1, "trade", "portfolio", "port_sg");
    checkSYSTABLES(-1, "trade", "portfolio", "port_sg");
    checkSYSTABLES(-2, "trade", "portfolio", "port_sg");
    checkSYSTABLES(-3, "trade", "portfolio", "port_sg");
    // puts in table with no available store should throw an exception
    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        PartitionedRegionStorageException.class);
    try {
      sqlExecuteVerify(null, new int[] { 3 }, "insert into trade.portfolio "
          + "values (1, 4, 10, 20, 100.0, 5), (2, 5, 20, 25, 200.0, 6)",
          null, null);
      fail("Expected an exception while inserting in a partitioned table "
          + "with no datastore");
    } catch (RMIException ex) {
      assertTrue("Expected an SQLException",
          ex.getCause() instanceof SQLException);
      SQLException sqlEx = (SQLException)ex.getCause();
      assertEquals("Expected an SQL exception while inserting in a table "
              + "with no datastore", "X0Z08", sqlEx.getSQLState());
    }
    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        PartitionedRegionStorageException.class);
    */
  }

  /** expect exceptions when there are no VMs in appropriate server groups */
  public void testReplicatedServerGroupsNoVMs_bug40106() throws Exception {
    this.basicReplicatedServerGroupsNoVMs_bug40106(false);
  }
  
  public void testReplicatedServerGroupsNoVMs_bug40106_OffHeap() throws Exception {
    this.basicReplicatedServerGroupsNoVMs_bug40106(true);
  }
  
  /** expect exceptions when there are no VMs in appropriate server groups */
  private void basicReplicatedServerGroupsNoVMs_bug40106(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and three servers
    startServerVMs(2, 0, "SG1", extra);
    startVMs(1, 1, -1, null, extra);

    // Create a schema
    clientSQLExecute(1, "create schema trade default server groups (SG1)");

    serverSQLExecute(1, "create table trade.customers (cid int not null, "
        + "primary key (cid)) replicate server groups (sg1, SG2)"+ getConditionalOffheapSuffix(enableOffHeap));

    // Check for both index and non-index lookup in SYSTABLES
    checkSYSTABLES(1, "trade", "customers", "cust_sg");
    checkSYSTABLES(-1, "trade", "customers", "cust_sg");
    checkSYSTABLES(-2, "trade", "customers", "cust_sg");
    checkSYSTABLES(-3, "trade", "customers", "cust_sg");

    // check success with matching server groups
    serverSQLExecute(2,
        "create table trade.securities (sec_id int not null, "
            + "primary key (sec_id)) replicate server groups (sg2, sg1)"+ getConditionalOffheapSuffix(enableOffHeap));

    // Check for both index and non-index lookup in SYSTABLES
    checkSYSTABLES(1, "trade", "securities", "sec_sg");
    checkSYSTABLES(-1, "trade", "securities", "sec_sg");
    checkSYSTABLES(-2, "trade", "securities", "sec_sg");
    checkSYSTABLES(-3, "trade", "securities", "sec_sg");

    // puts in tables with data stores should succeed
    clientSQLExecute(1, "insert into trade.customers values (1), (2)");
    serverSQLExecute(2, "insert into trade.securities values (4), (5)");

    // now create a table in SG2 which has no available servers
    try {
      serverSQLExecute(3, "create table trade.portfolio (cid int not null, "
          + "sid int not null, qty int not null, availQty int not null, "
          + "subTotal decimal(30,20), tid int, foreign key (sid) references "
          + "trade.securities(sec_id)) replicate server groups (Sg2)"+ getConditionalOffheapSuffix(enableOffHeap));
      fail("Expected an exception while creating a replicated table with no datastore");
    } catch (RMIException ex) {
      assertTrue("Expected an SQLException",
          ex.getCause() instanceof SQLException);
      SQLException sqlEx = (SQLException)ex.getCause();
      if (!"X0Z08".equals(sqlEx.getSQLState())) {
        throw sqlEx;
      }
    }

    /*
    // Check for both index and non-index lookup in SYSTABLES
    checkSYSTABLES(1, "trade", "portfolio", "port_sg");
    checkSYSTABLES(-1, "trade", "portfolio", "port_sg");
    checkSYSTABLES(-2, "trade", "portfolio", "port_sg");
    checkSYSTABLES(-3, "trade", "portfolio", "port_sg");

    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        GemFireXDRuntimeException.class);
    try {
      sqlExecuteVerify(null, new int[] { 3 }, "insert into trade.portfolio "
          + "values (1, 4, 10, 20, 100.0, 5), (2, 5, 20, 25, 200.0, 6)",
          null, null);
      fail("Expected an exception while inserting in a replicated table "
          + "with no datastore");
    } catch (RMIException ex) {
      assertTrue("Expected an SQLException",
          ex.getCause() instanceof SQLException);
      SQLException sqlEx = (SQLException)ex.getCause();
      if (!"X0Z08".equals(sqlEx.getSQLState())) {
        throw sqlEx;
      }
    }
    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        GemFireXDRuntimeException.class);
    */
  }

  
  /**
   * test for different number of redundant copies in partitioned tables with
   * and without default colocation
   */
  public void testRedundantCopies_bug40141() throws Exception {
    this.basicRedundantCopies_bug40141(false);
  }
  
  public void testRedundantCopies_bug40141_OffHeap() throws Exception {
    this.basicRedundantCopies_bug40141(true);
  }
 
  /**
   * test for different number of redundant copies in partitioned tables with
   * and without default colocation
   */
  private void basicRedundantCopies_bug40141(boolean enableOffHeap) throws Exception {
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // Start one client and four servers
    startVMs(1, 4, -1, null, extra);

    // Create a table with no redundant copies
    serverSQLExecute(1, "create table trade.customers (cid int not null, "
        + "primary key (cid))"+ getConditionalOffheapSuffix(enableOffHeap));

    // Create another table with redundant copies
    serverSQLExecute(2,
        "create table trade.securities (sec_id int not null, "
            + "sec_name varchar(20), primary key (sec_id)) redundancy 2"+ getConditionalOffheapSuffix(enableOffHeap));

    // Create a table with same redundant copies and foreign key relationship
    clientSQLExecute(1, "create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, foreign key (sid) references "
        + "trade.securities(sec_id)) redundancy 2"+ getConditionalOffheapSuffix(enableOffHeap));

    // Create a table with different redundant copies and foreign key relationship
    clientSQLExecute(1, "create table trade.portfolio2 (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, foreign key (sid) references "
        + "trade.securities(sec_id)) redundancy 3"+ getConditionalOffheapSuffix(enableOffHeap));

    // do some inserts in the tables
    clientSQLExecute(1, "insert into trade.customers values (1), (2)");
    serverSQLExecute(3, "insert into trade.securities values (4, 'SEC1'), "
        + "(5, 'SEC2')");

    checkFKViolation(1, "insert into trade.portfolio "
        + "values (1, 3, 10, 20, 100.0, 5), (2, 2, 20, 25, 200.0, 6)");
    serverSQLExecute(1, "insert into trade.portfolio "
        + "values (1, 4, 10, 20, 100.0, 5), (2, 5, 20, 25, 200.0, 6)");

    checkFKViolation(-3, "insert into trade.portfolio2 "
        + "values (1, 3, 10, 20, 100.0, 5), (2, 2, 20, 25, 200.0, 6)");
    serverSQLExecute(1, "insert into trade.portfolio2 "
          + "values (1, 4, 10, 20, 100.0, 5), (2, 5, 20, 25, 200.0, 6)");

    // verify with selects
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "cust_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.securities", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "sec_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.portfolio", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "port_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.portfolio2", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "port_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.customers where cid < 10", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "cust_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.securities where sec_id > 1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "sec_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.portfolio where sid > 1 and qty > 5",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "port_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.portfolio2 where sid > 1 and qty > 5",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "port_insert");

    // perform some updates and verify with selects again
    clientSQLExecute(1, "update trade.securities set sec_name='SEC4' "
        + "where sec_id=4");
    serverSQLExecute(2, "update trade.securities set sec_name='SEC5' "
        + "where sec_id=5");
    serverSQLExecute(3, "update trade.portfolio set tid=sid");
    serverSQLExecute(2, "update trade.portfolio2 set tid=sid");

    // verify with selects
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.customers", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "cust_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.securities", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "sec_update");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.portfolio", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "port_update");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select * from trade.portfolio2", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "port_update");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.customers where cid < 10", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "cust_insert");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.securities where sec_id > 1", TestUtil.getResourcesDir()
            + "/lib/checkCreateTable.xml", "sec_update");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.portfolio where sid > 1 and qty > 5",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "port_update");
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select distinct * from trade.portfolio2 where sid > 1 and qty > 5",
        TestUtil.getResourcesDir() + "/lib/checkCreateTable.xml",
        "port_update");
  }
  
  /**
   * test for 40100 with Server groups.
   */

  /**
   * Test ddl in quick start.
   * @throws Exception on failure.
   */
  public void testQuickStart() throws Exception {
    startVMs(1, 1);
    clientSQLExecute(1, "CREATE TABLE AIRLINES(AIRLINE CHAR(2) NOT NULL CONSTRAINT " +
        "AIRLINES_PK PRIMARY KEY, AIRLINE_FULL VARCHAR(24), BASIC_RATE DOUBLE PRECISION," +
        "DISTANCE_DISCOUNT DOUBLE PRECISION, BUSINESS_LEVEL_FACTOR DOUBLE PRECISION," +
        "FIRSTCLASS_LEVEL_FACTOR DOUBLE PRECISION, ECONOMY_SEATS INTEGER," +
        "BUSINESS_SEATS INTEGER, FIRSTCLASS_SEATS INTEGER )");
    clientSQLExecute(1, "CREATE TABLE COUNTRIES ( COUNTRY VARCHAR(26) NOT NULL " +
        "CONSTRAINT COUNTRIES_UNQ_NM Unique, COUNTRY_ISO_CODE CHAR(2) NOT NULL " +
        "CONSTRAINT COUNTRIES_PK PRIMARY KEY, REGION VARCHAR(26)," +
        " CONSTRAINT COUNTRIES_UC CHECK (country_ISO_code = upper(country_ISO_code) " +
        ") )");
    clientSQLExecute(1, "CREATE TABLE CITIES ( CITY_ID INTEGER NOT NULL " +
        "CONSTRAINT CITIES_PK Primary Key , CITY_NAME VARCHAR(24) NOT NULL," +
        "COUNTRY VARCHAR(26) NOT NULL, AIRPORT VARCHAR(3), LANGUAGE  VARCHAR(16), " +
        "COUNTRY_ISO_CODE CHAR(2) CONSTRAINT COUNTRIES_FK REFERENCES " +
        "COUNTRIES (COUNTRY_ISO_CODE) )");
    clientSQLExecute(1, "CREATE TABLE FLIGHTS ( FLIGHT_ID CHAR(6) NOT NULL ," +
        " SEGMENT_NUMBER INTEGER NOT NULL , ORIG_AIRPORT CHAR(3), DEPART_TIME TIME," +
        " DEST_AIRPORT CHAR(3), ARRIVE_TIME TIME, MEAL CHAR(1), " +
        "FLYING_TIME DOUBLE PRECISION, MILES INTEGER, AIRCRAFT VARCHAR(6)," +
        " CONSTRAINT FLIGHTS_PK Primary Key ( FLIGHT_ID, SEGMENT_NUMBER)," +
        " CONSTRAINT MEAL_CONSTRAINT CHECK (meal IN ('B', 'L', 'D', 'S')) )");
    clientSQLExecute(1, "CREATE INDEX DESTINDEX ON FLIGHTS ( DEST_AIRPORT)");
    clientSQLExecute(1, "CREATE INDEX ORIGINDEX ON FLIGHTS ( ORIG_AIRPORT)");
    clientSQLExecute(1, "CREATE TABLE FLIGHTAVAILABILITY ( FLIGHT_ID CHAR(6) NOT NULL ," +
        " SEGMENT_NUMBER INTEGER NOT NULL , FLIGHT_DATE DATE NOT NULL ," +
        " ECONOMY_SEATS_TAKEN INTEGER DEFAULT 0, BUSINESS_SEATS_TAKEN INTEGER DEFAULT 0," +
        " FIRSTCLASS_SEATS_TAKEN INTEGER DEFAULT 0, CONSTRAINT " +
        "FLIGHTAVAIL_PK Primary Key ( FLIGHT_ID, SEGMENT_NUMBER, FLIGHT_DATE)," +
        " CONSTRAINT FLIGHTS_FK2 Foreign Key ( FLIGHT_ID, SEGMENT_NUMBER) " +
        "REFERENCES FLIGHTS ( FLIGHT_ID, SEGMENT_NUMBER) )");
    clientSQLExecute(1,"CREATE TABLE MAPS ( MAP_ID INTEGER NOT NULL GENERATED " +
        "ALWAYS AS IDENTITY, " +
        "MAP_NAME VARCHAR(24) NOT NULL, REGION VARCHAR(26), AREA DECIMAL(8,4) NOT NULL," +
        " PHOTO_FORMAT VARCHAR(26) NOT NULL, PICTURE BLOB(102400), " +
        "UNIQUE (MAP_ID, MAP_NAME) )");
    clientSQLExecute(1, "CREATE TABLE FLIGHTS_HISTORY ( FLIGHT_ID CHAR(6)," +
        " SEGMENT_NUMBER INTEGER, ORIG_AIRPORT CHAR(3), DEPART_TIME TIME," +
        " DEST_AIRPORT CHAR(3), ARRIVE_TIME TIME, MEAL CHAR(1), " +
        "FLYING_TIME DOUBLE PRECISION, MILES INTEGER, AIRCRAFT VARCHAR(6)," +
        " STATUS VARCHAR (20))");

    clientSQLExecute(1, "DROP TABLE AIRLINES");
    clientSQLExecute(1, "DROP TABLE CITIES");
    clientSQLExecute(1, "DROP TABLE COUNTRIES");
    clientSQLExecute(1, "DROP TABLE FLIGHTAVAILABILITY");
    clientSQLExecute(1, "DROP TABLE FLIGHTS");
    clientSQLExecute(1, "DROP TABLE MAPS");
    clientSQLExecute(1, "DROP TABLE FLIGHTS_HISTORY");

    clientSQLExecute(1, "CREATE TABLE AIRLINES ( AIRLINE CHAR(2) NOT NULL " +
        "CONSTRAINT AIRLINES_PK PRIMARY KEY, AIRLINE_FULL VARCHAR(24)," +
        " BASIC_RATE DOUBLE PRECISION, DISTANCE_DISCOUNT DOUBLE PRECISION, " +
        "BUSINESS_LEVEL_FACTOR DOUBLE PRECISION, FIRSTCLASS_LEVEL_FACTOR " +
        "DOUBLE PRECISION, ECONOMY_SEATS INTEGER, BUSINESS_SEATS INTEGER," +
        " FIRSTCLASS_SEATS INTEGER ) REPLICATE");
    clientSQLExecute(1, "CREATE TABLE COUNTRIES( COUNTRY VARCHAR(26) NOT NULL " +
        "CONSTRAINT COUNTRIES_UNQ_NM Unique, COUNTRY_ISO_CODE CHAR(2) NOT " +
        "NULL CONSTRAINT COUNTRIES_PK PRIMARY KEY, REGION VARCHAR(26), " +
        "CONSTRAINT COUNTRIES_UC CHECK (country_ISO_code = upper(country_ISO_code) ) )" +
        " REPLICATE");
    clientSQLExecute(1, "CREATE TABLE CITIES ( CITY_ID INTEGER NOT NULL " +
        "CONSTRAINT CITIES_PK Primary Key , CITY_NAME VARCHAR(24) NOT NULL, " +
        "COUNTRY VARCHAR(26) NOT NULL, AIRPORT VARCHAR(3), LANGUAGE  VARCHAR(16)," +
        " COUNTRY_ISO_CODE CHAR(2) CONSTRAINT COUNTRIES_FK REFERENCES " +
        "COUNTRIES (COUNTRY_ISO_CODE) ) REPLICATE");
    clientSQLExecute(1, "CREATE TABLE MAPS ( MAP_ID INTEGER NOT NULL GENERATED " +
        "ALWAYS AS IDENTITY, " +
        "MAP_NAME VARCHAR(24) NOT NULL, REGION VARCHAR(26), AREA DECIMAL(8,4) NOT NULL, " +
        "PHOTO_FORMAT VARCHAR(26) NOT NULL, PICTURE BLOB(102400), " +
        "UNIQUE (MAP_ID, MAP_NAME) ) REPLICATE");
    clientSQLExecute(1, "CREATE TABLE FLIGHTS ( FLIGHT_ID CHAR(6) NOT NULL ," +
        " SEGMENT_NUMBER INTEGER NOT NULL , ORIG_AIRPORT CHAR(3), DEPART_TIME TIME," +
        " DEST_AIRPORT CHAR(3), ARRIVE_TIME TIME, MEAL CHAR(1), FLYING_TIME DOUBLE PRECISION," +
        " MILES INTEGER, AIRCRAFT VARCHAR(6), CONSTRAINT FLIGHTS_PK " +
        "Primary Key ( FLIGHT_ID, SEGMENT_NUMBER), " +
        "CONSTRAINT MEAL_CONSTRAINT CHECK (meal IN ('B', 'L', 'D', 'S')) ) " +
        "PARTITION BY COLUMN (FLIGHT_ID)");
    clientSQLExecute(1, "CREATE INDEX DESTINDEX ON FLIGHTS (DEST_AIRPORT)");
    clientSQLExecute(1, "CREATE INDEX ORIGINDEX ON FLIGHTS (ORIG_AIRPORT)");
    clientSQLExecute(1, "CREATE TABLE FLIGHTAVAILABILITY ( FLIGHT_ID CHAR(6) " +
        "NOT NULL , SEGMENT_NUMBER INTEGER NOT NULL , FLIGHT_DATE DATE NOT NULL ," +
        " ECONOMY_SEATS_TAKEN INTEGER DEFAULT 0, BUSINESS_SEATS_TAKEN INTEGER DEFAULT 0," +
        " FIRSTCLASS_SEATS_TAKEN INTEGER DEFAULT 0, CONSTRAINT FLIGHTAVAIL_PK " +
        "Primary Key ( FLIGHT_ID, SEGMENT_NUMBER, FLIGHT_DATE), CONSTRAINT " +
        "FLIGHTS_FK2 Foreign Key (FLIGHT_ID, SEGMENT_NUMBER)" +
        " REFERENCES FLIGHTS ( FLIGHT_ID, SEGMENT_NUMBER) ) " +
        "PARTITION BY COLUMN (FLIGHT_ID) COLOCATE WITH (FLIGHTS)");
    clientSQLExecute(1, "CREATE TABLE FLIGHTS_HISTORY ( FLIGHT_ID CHAR(6)," +
        " SEGMENT_NUMBER INTEGER, ORIG_AIRPORT CHAR(3), DEPART_TIME TIME, " +
        "DEST_AIRPORT CHAR(3), ARRIVE_TIME TIME, MEAL CHAR(1), " +
        "FLYING_TIME DOUBLE PRECISION, MILES INTEGER, AIRCRAFT VARCHAR(6)," +
        " STATUS VARCHAR (20) ) PARTITION BY COLUMN (FLIGHT_ID)" +
        " COLOCATE WITH (FLIGHTS)");
    // Bring new server.
    startVMs(0, 1);

    // Drop every thing and restart.
    clientSQLExecute(1, "DROP TABLE AIRLINES");
    clientSQLExecute(1, "DROP TABLE CITIES");
    clientSQLExecute(1, "DROP TABLE COUNTRIES");
    clientSQLExecute(1, "DROP TABLE FLIGHTAVAILABILITY");
    addExpectedException(new int[] { 1 }, null,
        UnsupportedOperationException.class);
    try {
      clientSQLExecute(1, "DROP TABLE FLIGHTS");
      fail("Expected an exception while dropping dependent table");
    } catch (SQLException ex) {
      // expect the exception due to colocation dependency here
      if (!"X0Y98".equals(ex.getSQLState())) {
        throw ex;
      }
      removeExpectedException(new int[] { 1 }, null,
          UnsupportedOperationException.class);
    }
    clientSQLExecute(1, "DROP TABLE FLIGHTS_HISTORY");
    clientSQLExecute(1, "DROP TABLE FLIGHTS");
    clientSQLExecute(1, "DROP TABLE MAPS");
    // recreate pure partitioned tables, same as previous creates.
    clientSQLExecute(1, "CREATE TABLE AIRLINES(AIRLINE CHAR(2) NOT NULL CONSTRAINT " +
        "AIRLINES_PK PRIMARY KEY, AIRLINE_FULL VARCHAR(24), BASIC_RATE DOUBLE PRECISION," +
        "DISTANCE_DISCOUNT DOUBLE PRECISION, BUSINESS_LEVEL_FACTOR DOUBLE PRECISION," +
        "FIRSTCLASS_LEVEL_FACTOR DOUBLE PRECISION, ECONOMY_SEATS INTEGER," +
        "BUSINESS_SEATS INTEGER, FIRSTCLASS_SEATS INTEGER )");
    clientSQLExecute(1, "CREATE TABLE COUNTRIES ( COUNTRY VARCHAR(26) NOT NULL " +
        "CONSTRAINT COUNTRIES_UNQ_NM Unique, COUNTRY_ISO_CODE CHAR(2) NOT NULL " +
        "CONSTRAINT COUNTRIES_PK PRIMARY KEY, REGION VARCHAR(26)," +
        " CONSTRAINT COUNTRIES_UC CHECK (country_ISO_code = upper(country_ISO_code) " +
        ") )");
    clientSQLExecute(1, "CREATE TABLE CITIES ( CITY_ID INTEGER NOT NULL " +
        "CONSTRAINT CITIES_PK Primary Key , CITY_NAME VARCHAR(24) NOT NULL," +
        "COUNTRY VARCHAR(26) NOT NULL, AIRPORT VARCHAR(3), LANGUAGE  VARCHAR(16), " +
        "COUNTRY_ISO_CODE CHAR(2) CONSTRAINT COUNTRIES_FK REFERENCES " +
        "COUNTRIES (COUNTRY_ISO_CODE) )");
    clientSQLExecute(1, "CREATE TABLE FLIGHTS ( FLIGHT_ID CHAR(6) NOT NULL ," +
        " SEGMENT_NUMBER INTEGER NOT NULL , ORIG_AIRPORT CHAR(3), DEPART_TIME TIME," +
        " DEST_AIRPORT CHAR(3), ARRIVE_TIME TIME, MEAL CHAR(1), " +
        "FLYING_TIME DOUBLE PRECISION, MILES INTEGER, AIRCRAFT VARCHAR(6)," +
        " CONSTRAINT FLIGHTS_PK Primary Key ( FLIGHT_ID, SEGMENT_NUMBER)," +
        " CONSTRAINT MEAL_CONSTRAINT CHECK (meal IN ('B', 'L', 'D', 'S')) )");
    clientSQLExecute(1, "CREATE INDEX DESTINDEX ON FLIGHTS ( DEST_AIRPORT)");
    clientSQLExecute(1, "CREATE INDEX ORIGINDEX ON FLIGHTS ( ORIG_AIRPORT)");
    clientSQLExecute(1, "CREATE TABLE FLIGHTAVAILABILITY ( FLIGHT_ID CHAR(6) NOT NULL ," +
        " SEGMENT_NUMBER INTEGER NOT NULL , FLIGHT_DATE DATE NOT NULL ," +
        " ECONOMY_SEATS_TAKEN INTEGER DEFAULT 0, BUSINESS_SEATS_TAKEN INTEGER DEFAULT 0," +
        " FIRSTCLASS_SEATS_TAKEN INTEGER DEFAULT 0, CONSTRAINT " +
        "FLIGHTAVAIL_PK Primary Key ( FLIGHT_ID, SEGMENT_NUMBER, FLIGHT_DATE)," +
        " CONSTRAINT FLIGHTS_FK2 Foreign Key ( FLIGHT_ID, SEGMENT_NUMBER) " +
        "REFERENCES FLIGHTS ( FLIGHT_ID, SEGMENT_NUMBER) )");
    clientSQLExecute(1,"CREATE TABLE MAPS ( MAP_ID INTEGER NOT NULL GENERATED " +
        "ALWAYS AS IDENTITY, " +
        "MAP_NAME VARCHAR(24) NOT NULL, REGION VARCHAR(26), AREA DECIMAL(8,4) NOT NULL," +
        " PHOTO_FORMAT VARCHAR(26) NOT NULL, PICTURE BLOB(102400), " +
        "UNIQUE (MAP_ID, MAP_NAME) )");
    clientSQLExecute(1, "CREATE TABLE FLIGHTS_HISTORY ( FLIGHT_ID CHAR(6)," +
        " SEGMENT_NUMBER INTEGER, ORIG_AIRPORT CHAR(3), DEPART_TIME TIME," +
        " DEST_AIRPORT CHAR(3), ARRIVE_TIME TIME, MEAL CHAR(1), " +
        "FLYING_TIME DOUBLE PRECISION, MILES INTEGER, AIRCRAFT VARCHAR(6)," +
        " STATUS VARCHAR (20))");

    // start a new client.
    startClientVMs(1, 0, null);
  }



  public void testDDLRollback() throws Exception {
    // start a client and three servers
    startVMs(1, 3);

    Connection conn = TestUtil.jdbcConn;
    // create tables and populate data
    CreateTableTest.createTables(conn);
    CreateTableTest.populateData(conn, false, false);

    // now add duplicate data for a currently non-unique column on exactly
    // one data store
    Statement stmt = conn.createStatement();
    stmt.execute("alter table trade.customers drop constraint cust_uk");
    stmt.execute("insert into trade.customers (cid, cust_name, addr, tid) "
        + " values (200, 'CUST20', 'ADDR200', 20)");

    addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 }, new Object[] {
        EntryExistsException.class,
        "java.sql.SQLIntegrityConstraintViolationException",
        SQLException.class, RegionDestroyedException.class });
    // now trying to create a unique index should fail cleanly
    try {
      stmt.execute("alter table trade.customers "
          + "add constraint cust_uk1 unique (tid)");
      fail("expected unique constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      stmt.execute("alter table trade.customers "
          + "add constraint cust_uk1 unique (cust_name)");
      fail("expected unique constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      stmt.execute("alter table trade.customers "
          + "add constraint cust_uk2 unique (cust_name)");
      fail("expected unique constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // deleting the offending data should allow for creation of indexes
    stmt.execute("delete from trade.customers where cid=200");

    stmt.execute("alter table trade.customers "
        + "add constraint cust_uk1 unique (tid)");
    try {
      stmt.execute("alter table trade.customers "
          + "add constraint cust_uk1 unique (cust_name)");
      fail("expected duplicate index exception");
    } catch (SQLException ex) {
      if (!"X0Y32".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    stmt.execute("alter table trade.customers "
        + "add constraint cust_uk2 unique (cust_name)");

    // some sanity checks to verify that table is consistent
    try {
      stmt.execute("insert into trade.customers (cid, cust_name, addr, tid)"
          + " values (200, 'CUST200', 'ADDR200', 20)");
      fail("expected unique constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      stmt.execute("insert into trade.customers (cid, cust_name, addr, tid)"
          + " values (200, 'CUST20', 'ADDR200', 200)");
      fail("expected unique constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      stmt.execute("insert into trade.customers (cid, cust_name, addr, tid)"
          + " values (200, 'CUST20', 'ADDR200', 20)");
      fail("expected unique constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      stmt.execute("insert into trade.customers (cid, cust_name, addr, tid)"
          + " values (20, 'CUST200', 'ADDR200', 200)");
      fail("expected primary key violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
        new Object[] { EntryExistsException.class,
            "java.sql.SQLIntegrityConstraintViolationException",
            SQLException.class, RegionDestroyedException.class });

    stmt.execute("insert into trade.customers (cid, cust_name, addr, tid)"
        + " values (200, 'CUST200', 'ADDR200', 201)");
  }

  /**
   * Test for queries without any data when using global index. Also checks that
   * only expected number of global index lookups take place and no more.
   */
  public void testNoDataQueriesWithGlobalIndex_41271() throws Exception {
    this.basicNoDataQueriesWithGlobalIndex_41271(false);
  }
  
  public void testNoDataQueriesWithGlobalIndex_41271_OffHeap() throws Exception {
    this.basicNoDataQueriesWithGlobalIndex_41271(true);
  }
  /**
   * Test for queries without any data when using global index. Also checks that
   * only expected number of global index lookups take place and no more.
   */
  private void basicNoDataQueriesWithGlobalIndex_41271(boolean enableOffHeap) throws Exception {
    // start some clients and servers
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    startVMs(2, 3, -1, null, extra);

    // create a table with global index for PKs
    clientSQLExecute(1, "create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
        + "'lse', 'fse', 'hkse', 'tse')))  partition by list (exchange) "
        + "(VALUES ('nasdaq'), VALUES ('nye', 'amex'), "
        + "VALUES ('lse', 'fse', 'hkse', 'tse'))"+ getConditionalOffheapSuffix(enableOffHeap));

    final AtomicInteger invocationCount = new AtomicInteger(0);
    // observer to check the number of global index lookups
    GemFireXDQueryObserver countLookups = new GemFireXDQueryObserverAdapter() {

      @Override
      public void beforeGlobalIndexLookup(LanguageConnectionContext lcc,
          PartitionedRegion indexRegion, Serializable indexKey) {
        invocationCount.incrementAndGet();
      }
    };
    GemFireXDQueryObserverHolder.putInstance(countLookups);

    // do some inserts and delete all so that nothing remains in the table
    // but buckets are created
    PreparedStatement pstmt = TestUtil.getPreparedStatement("insert into "
        + "trade.securities values(?, ?, ?, ?, ?)");
    final String[] symbols = new String[] { "SUN", "IBM", "MSFT", "RHAT" };
    final String[] exchanges = new String[] { "nasdaq", "nye", "amex", "lse",
        "fse", "hkse", "tse" };
    final int numKeys = 20;
    for (int id = 1; id <= numKeys; ++id) {
      final String idStr = String.valueOf(id);
      pstmt.setInt(1, id);
      pstmt.setString(2, symbols[id % symbols.length]);
      pstmt.setBigDecimal(3, new BigDecimal(idStr + '.' + idStr));
      pstmt.setString(4, exchanges[id % exchanges.length]);
      pstmt.setInt(5, id + 5);
      pstmt.execute();
    }

    assertEquals(0, invocationCount.get());

    // an observer that will delete the row after a successful global index
    // lookup has taken place during select
    GemFireXDQueryObserver delRow = new GemFireXDQueryObserverAdapter() {

      private final Random rnd = new Random();

      @Override
      public void afterGlobalIndexLookup(LanguageConnectionContext lcc,
          PartitionedRegion indexRegion, Serializable indexKey, Object result) {
        try {
          serverSQLExecute(rnd.nextInt(3) + 1,
              "delete from trade.securities where sec_id=" + indexKey);
        } catch (Exception ex) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "unexpected exception", ex);
        }
      }
    };
    GemFireXDQueryObserverHolder.putInstance(delRow);

    // fire a series of get convertible queries that will not return any result
    // with the observer that will do the delete after global index lookup
    for (int id = 1; id <= numKeys; ++id) {
      sqlExecuteVerify(new int[] { 1 }, null,
          "select * from trade.securities where sec_id=" + id, TestUtil.getResourcesDir()
              + "/lib/checkCreateTable.xml", "empty");
      sqlExecuteVerify(new int[] { 1, 2 }, new int[] { 1, 2, 3 },
          "select * from trade.securities where sec_id=" + id, TestUtil.getResourcesDir()
              + "/lib/checkCreateTable.xml", "empty");
    }

    assertEquals(2 * numKeys, invocationCount.get());
    GemFireXDQueryObserverHolder.removeObserver(delRow.getClass());

    // an observer that will insert a row after an unsuccessful global index
    // lookup has taken place during select
    GemFireXDQueryObserver insRow = new GemFireXDQueryObserverAdapter() {

      private final Random rnd = new Random();

      @Override
      public void afterGlobalIndexLookup(LanguageConnectionContext lcc,
          PartitionedRegion indexRegion, Serializable indexKey,
          Object result) {
        try {
          final DataValueDescriptor key = (DataValueDescriptor)indexKey;
          final int id = key.getInt();
          final String idStr = key.getString();
          if (id % 2 == 1) {
            serverSQLExecute(rnd.nextInt(3) + 1,
                "insert into trade.securities values(" + idStr + ",'"
                    + symbols[id % symbols.length] + "',"
                    + (idStr + '.' + idStr) + ",'"
                    + exchanges[id % exchanges.length] + "'," + (id + 5) + ')');
          }
        } catch (Exception ex) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "unexpected exception", ex);
        }
      }
    };
    GemFireXDQueryObserverHolder.putInstance(insRow);

    // fire a series of get convertible queries some of which will not return
    // any result
    pstmt = TestUtil.getPreparedStatement("select * from "
        + "trade.securities where sec_id = ?");
    ResultSet rs;
    // first iteration will have no results since the inserts are done after
    // the global index lookup which will fail
    for (int id = 1; id <= numKeys; ++id) {
      pstmt.setInt(1, id);
      rs = pstmt.executeQuery();
      assertFalse(rs.next());
    }

    assertEquals(3 * numKeys, invocationCount.get());
    GemFireXDQueryObserverHolder.removeObserver(insRow.getClass());

    // second time around we will find alternate results
    for (int id = 1; id <= numKeys; ++id) {
      pstmt.setInt(1, id);
      rs = pstmt.executeQuery();
      if (id % 2 == 0) {
        assertFalse(rs.next());
      }
      else {
        assertTrue(rs.next());
        assertEquals(id, rs.getInt("SEC_ID"));
        assertEquals(id + 5, rs.getInt("TID"));
        assertFalse(rs.next());
      }
    }

    assertEquals(4 * numKeys, invocationCount.get());
    GemFireXDQueryObserverHolder.clearInstance();
  }

  /**
   * Bug test for #41311. This starts up a server when GfxdDDLMessage has been
   * sent but GfxdDDLFinishMessage has still not been sent. This is done by
   * waiting for VM to start in an observer during GfxdDDLMessage execution on
   * one of the already up servers.
   */
  public void testDDLReplay_41311() throws Exception {
    // start a client and couple of servers
    startVMs(1, 2);

    // add an observer to a server to wait till new VM is started
    VM server1 = this.serverVMs.get(1);
    final SerializableRunnable addObserver = new SerializableRunnable(
        "add observer") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserverHolder
            .putInstance(new GemFireXDQueryObserverAdapter() {
              @Override
              public boolean afterQueryExecution(CallbackStatement stmt,
                  SQLException sqle) {
                try {
                  Thread.sleep(15000);
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                }
                return false;
              }
            });
      }
    };
    server1.invoke(addObserver);

    // execute a DDL and bring up a new server concurrently
    this.threadEx = null;
    Thread t = new Thread(new SerializableRunnable("start VM and signal") {
      @Override
      public void run() throws CacheException {
        try {
          // wait for sometime to allow DDL execution to begin
          Thread.sleep(5000);
          startVMs(0, 1);
        } catch (Throwable t) {
          threadEx = t;
          fail("unexpected exception when start server VM", t);
        }
      }
    });
    t.start();
    clientSQLExecute(1, "create table test (id int, addr varchar(64)) ");
    t.join();
    if (this.threadEx != null) {
      fail("unexpected exception in thread", this.threadEx);
    }
  }

  /**
   * Test for concurrent DDL execution (bugs #41571 and #41541).
   */
  public void testConcurrentDDL() throws Exception {
	  if(isTransactional){
		  return;
	  }
    AsyncVM async1 = invokeStartServerVM(1, 0, "SG2", null);
    AsyncVM async2 = invokeStartServerVM(2, 0, "SG1, SG2", null);
    AsyncVM async3 = invokeStartServerVM(3, 0, "SG2,sg1", null);
    startClientVMs(1, 0, null);
    startClientVMs(1, 0, "SG1");
    joinVMs(true, async1, async2, async3);

    // run DDLs on all VMs concurrently
    final String scmCreate = "create schema trade default server groups (SG1)";
    final String tblCreate = "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), since int, addr varchar(100), tid int, "
        + "primary key (cid))";
    final SerializableRunnable createRun = new SerializableRunnable(
        "create schema and tables") {
      @Override
      public void run() throws CacheException {
        final Connection conn = TestUtil.jdbcConn;
        try {
          final Statement stmt = conn.createStatement();
          try {
            stmt.execute(scmCreate);
          } catch (SQLException ex) {
            if (!"X0Y68".equals(ex.getSQLState())) {
              throw ex;
            }
          }
          try {
            stmt.execute(tblCreate);
          } catch (SQLException ex) {
            if (!"X0Y32".equals(ex.getSQLState())) {
              throw ex;
            }
          }
        } catch (Exception ex) {
          throw new CacheException(ex) {
          };
        }
      }
    };
    ArrayList<AsyncInvocation> tasks = executeTaskAsync(new int[] { 2 },
        new int[] { 1, 2, 3 }, createRun);
    createRun.run();
    joinAsyncInvocation(tasks);

    // next run inserts on all VMs concurrently
    final SerializableRunnable insertRun = new SerializableRunnable(
        "insert data") {
      @Override
      public void run() throws CacheException {
        final Connection conn = TestUtil.jdbcConn;
        try {
          final PreparedStatement pstmt = conn.prepareStatement("insert into "
              + "trade.customers values (?, ?, ?, ?, ?)");
          for (int count = 1; count <= 100; ++count) {
            pstmt.setInt(1, count);
            pstmt.setString(2, "XXXX" + count);
            pstmt.setInt(3, count);
            pstmt.setString(4, "BEAV" + count);
            pstmt.setInt(5, count);
            try {
              pstmt.execute();
            } catch (SQLException ex) {
              if (!"23505".equals(ex.getSQLState())) {
                throw ex;
              }
            }
          }
        } catch (Exception ex) {
          throw new CacheException(ex) {
          };
        }
      }
    };
    tasks = executeTaskAsync(new int[] { 2 }, new int[] { 1, 2, 3 }, insertRun);
    insertRun.run();
    joinAsyncInvocation(tasks);

    // finally check if inserts have been done successfully
    final SerializableRunnable queryRun = new SerializableRunnable(
        "query data") {
      @Override
      public void run() throws CacheException {
        final Connection conn = TestUtil.jdbcConn;
        try {
          final ResultSet rs = conn.createStatement().executeQuery(
              "select * from trade.customers order by cid");
          for (int count = 1; count <= 100; ++count) {
            assertTrue(rs.next());
            assertEquals(count, rs.getInt(1));
            assertEquals("XXXX" + count, rs.getString(2));
            assertEquals(count, rs.getInt(3));
            assertEquals("BEAV" + count, rs.getString(4));
            assertEquals(count, rs.getInt(5));
          }
          assertFalse(rs.next());
        } catch (Exception ex) {
          throw new CacheException(ex) {
          };
        }
      }
    };
    tasks = executeTaskAsync(new int[] { 2 }, new int[] { 1, 2, 3 }, queryRun);
    queryRun.run();
    joinAsyncInvocation(tasks);
  }


  // TODO modify properly as per new loader procedure added
  public void DISABLED_testProperExceptionNoValidMembers() throws Exception {
    // Start one client and four servers
    startServerVMs(2, 0, "sg2");
    startClientVMs(1, 0, null);
    try {
      clientSQLExecute(1, "create schema EMP default server groups (SG1)");
      clientSQLExecute(1, "create table EMP.PARTITIONTESTTABLE (ID int "
          + "not null, SECONDID int not null, THIRDID int not null,"
          + " primary key (ID)) partition by column(secondid) LOADER ( "
          + "com.pivotal.gemfirexd.ddl.GfxdTestRowLoader.createGfxdLoader)");
      sqlExecuteVerify(new int[] { 1 }, null, "select secondid from "
          + "EMP.PARTITIONTESTTABLE where id = 1", null, "1");
      fail("this test was expected to fail due to insufficient stores");
    } catch (SQLException e) {
      /*if (!e.getSQLState().equalsIgnoreCase("X0Z08")) {
        fail("this test was expected to fail");
      }*/
      assertEquals(e.getSQLState().toUpperCase(),"X0Z08");
      return;
    }
    fail("this test was not expected to reach here");
  }

  /**
   * Despite the name, this test does not reproduce customer issue #6131 yet.
   */
  public void testLoaderNullSchema() throws Exception {
    this.basicLoaderNullSchema(false);
  }
  
  public void testLoaderNullSchema_OffHeap() throws Exception {
    this.basicLoaderNullSchema(true);
  }
  private void basicLoaderNullSchema(boolean enableOffHeap) throws Exception {
    boolean tablesCreated = false;
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    try {
      startServerVMs(2, -1, "SG1", extra);
      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2)  replicate "+ getConditionalOffheapSuffix(enableOffHeap));

      tablesCreated = true;
      startClientVMs(1, 0, null,extra);
      String schema = null;

      GfxdCallbacksTest.addLoader(schema, "TESTTABLE",
          "com.pivotal.gemfirexd.ddl.CreateTableDUnit$GfxdTestRowLoader", "");
      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

    } finally {
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
    }
  }

  public void testLoaderEmptySchema() throws Exception {
    boolean tablesCreated = false;
    try {
      startServerVMs(2, -1, "SG1");
      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2)  replicate ");

      tablesCreated = true;
      startClientVMs(1, 0, null);
      GfxdCallbacksTest.addLoader("", "TESTTABLE",
          "com.pivotal.gemfirexd.ddl.CreateTableDUnit$GfxdTestRowLoader", "");
      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

    } finally {
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
    }
  }

  //FIXME GemFireXD does not yet support START WITH/INCREMENT BY on GENERATED ALWAYS
  //public void testIdentityGeneratedAlways() throws Exception {
  public void _testIdentityGeneratedAlways() throws Exception {
    // start a client and couple of servers
    startVMs(1, 2);

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size
    stmt.execute("create table trade.customers (tid int, cid int not null "
        + "GENERATED ALWAYS AS IDENTITY (START WITH 8), "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");
    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    final int numRows = 2000;
    // insertion in this table should start with 8
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, 8, 2, this,true);

    // Now check for IDENTITY column with BIGINT size
    stmt.execute("drop table trade.customers");
    addExpectedException(new int[] { 1 }, new int[] { 2 }, SQLWarning.class);
    stmt.execute("create table trade.customers (tid int, cid bigint not null "
        + "GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 7), "
        + "addr varchar(100), primary key (cid), "
        + "constraint cust_ck check (cid >= 0))");
    removeExpectedException(new int[] { 1 }, new int[] { 2 }, SQLWarning.class);
    // expect warning for the non-default start
    sw = stmt.getWarnings();
    assertNotNull(sw);
    if (!"X0Z12".equals(sw.getSQLState()) || !sw.getMessage().contains("7")) {
      throw sw;
    }
    assertNull(sw.getNextWarning());

    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, 1, 2, this,true);

    stmt.execute("drop table trade.customers");
  }
  public void testIdentityGeneratedByDefault(boolean enableOffHeap) throws Exception {
    this.basicIdentityGeneratedByDefault(false);
  }
  
  public void testIdentityGeneratedByDefault_OffHeap(boolean enableOffHeap) throws Exception {
    this.basicIdentityGeneratedByDefault(true);
  }
  private void basicIdentityGeneratedByDefault(boolean enableOffHeap) throws Exception {
    // reduce logs
    reduceLogLevelForTest("config");
    Properties extra =null;
    if(enableOffHeap) {
      extra = new Properties();
      extra.put("gemfire.off-heap-memory-size","500m");
    }
    // start a client and couple of servers
    startVMs(1, 2, -1, null, extra);

    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size
    stmt.execute("create table trade.customers (tid int, cid int not null "
        + "GENERATED by default AS IDENTITY (START WITH 8), "
        + "primary key (cid), constraint cust_ck check (cid >= 0))"+ getConditionalOffheapSuffix(enableOffHeap));
    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    final int numRows = 2000;
    // insertion in this table should start with 8
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, 8, 2, this,false);

    // Now check for IDENTITY column with BIGINT size
    stmt.execute("drop table trade.customers");
    addExpectedException(new int[] { 1 }, new int[] { 2 }, SQLWarning.class);
    stmt.execute("create table trade.customers (tid int, cid bigint not null "
        + "GENERATED by default as IDENTITY (START WITH 1, INCREMENT BY 7), "
        + "addr varchar(100), primary key (cid), "
        + "constraint cust_ck check (cid >= 0))"+ getConditionalOffheapSuffix(enableOffHeap));
    removeExpectedException(new int[] { 1 }, new int[] { 2 }, SQLWarning.class);
    // expect warning for the non-default start
    sw = stmt.getWarnings();
    assertNull(sw);

    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, 1, 2, this,false);

    stmt.execute("drop table trade.customers");
  }
  

  public static void verifyFlagAndRegionEntryClass(String path, boolean expectedFlagValue, Class expectedRegionEntryClass) throws Exception {
    LocalRegion region = (LocalRegion)Misc.getRegion(path, true, false);
    assertTrue(region != null);
    assertEquals(expectedFlagValue, region.getAttributes().getConcurrencyChecksEnabled());
    
    Iterator<?> entryItr = region.getDataView().getLocalEntriesIterator(
        (InternalRegionFunctionContext)null, true, false, true, region);
    while (entryItr.hasNext()) {
      RowLocation re = (RowLocation)entryItr.next();
      assertEquals(expectedRegionEntryClass, re.getClass());
    }
  }


  /**
   * This test needs to be run individually as it sets the system property that
   * interferes with other tests.  
   */
  public void _testTestFlagToEnableVersioning_True() throws Exception {
    Properties props = new Properties();
    props.put(TestUtil.TEST_FLAG_ENABLE_CONCURRENCY_CHECKS, "true");
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    startVMs(0, 4, mcastPort, null, props);

    // Flag should be true for persistent partitioned table
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key persistent redundancy 1 ");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinDiskRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");
    
    
    // Flag should be true for non-persistent partitioned table since we have turned on the default flag
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key redundancy 1 ");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");
    
    // Flag should be true for non-persistent replicated table since we have turned on the default flag
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "replicate");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedLocalRowLocationThinRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");

    // Flag should be true for persistent replicated table since we have turned on the default flag
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "persistent replicate");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedLocalRowLocationThinDiskRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");
    
    stopAllVMs();
  }
  
  /**
   * This test needs to be run individually as it sets the system property that
   * interferes with other tests.  
   */
  public void _testTestFlagToEnableVersioning_False() throws Exception {
    
    Properties props = new Properties();
    props.put(TestUtil.TEST_FLAG_ENABLE_CONCURRENCY_CHECKS, "false");
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    startVMs(0, 4, mcastPort, null, props);
    
    // Flag should be false for persistent partitioned table 
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key persistent redundancy 1 ");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMBucketRowLocationThinDiskRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");
    
    
    // Flag should be false for non-persistent partitioned table since we have turned off the default flag
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key redundancy 1 ");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMBucketRowLocationThinRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");
    
    // Flag should be false for non-persistent replicated table since we have turned off the default flag
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "replicate");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");

    // Flag should be false for persistent replicated table since we have turned off the default flag
    serverSQLExecute(1, "create table APP.T1(id int primary key, name varchar(20)) "
        + "persistent replicate");
    serverSQLExecute(1, "insert into APP.T1 values(1, 'name1')");
    serverSQLExecute(1, "insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinDiskRegionEntryHeap.class });
    serverSQLExecute(1, "drop table APP.T1");
    
    stopAllVMs();
  }
  
  
  public void testVersionedEntriesCreatedForPersistentPartitionedTables() throws Exception {
    startVMs(1, 4);

    Connection conn = TestUtil.jdbcConn;
    Statement s = conn.createStatement();
    
    // Flag should be true for persistent partitioned table
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by primary key persistent redundancy 1 ");
    s.execute("insert into APP.T1 values(1, 'name1')");
    s.execute("insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinDiskRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // Flag should be true for persistent partitioned table
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + "partition by column(name) persistent redundancy 1 ");
    s.execute("insert into APP.T1 values(1, 'name1')");
    s.execute("insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", true,
            VersionedBucketRowLocationThinDiskRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // Flag should be false for non-persistent partitioned table
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + " partition by column(name) redundancy 1 ");
    s.execute("insert into APP.T1 values(1, 'name1')");
    s.execute("insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMBucketRowLocationThinRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // Flag should be false for replicated persistent table
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + " persistent replicate");
    s.execute("insert into APP.T1 values(1, 'name1')");
    s.execute("insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinDiskRegionEntryHeap.class });
    s.execute("drop table APP.T1");

    // Flag should be false for replicated non-persistent table
    s.execute("create table APP.T1(id int primary key, name varchar(20)) "
        + " replicate");
    s.execute("insert into APP.T1 values(1, 'name1')");
    s.execute("insert into APP.T1 values(2, 'name2')");
    getServerVM(1).invoke(
        this,
        "verifyFlagAndRegionEntryClass",
        new Object[] { "/APP/T1", false,
            VMLocalRowLocationThinRegionEntryHeap.class });
    s.execute("drop table APP.T1");
    
    s.close();
  }
  
  

  public void DISABLED_SPP_testFunctionTable() throws Exception {
    // start a client and couple of servers
    startVMs(1, 2, 0, "SG1", null);

    // create and start a DBSynchronizer
    final int derbyPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    final String derbyDbUrl = "jdbc:derby://localhost:" + derbyPort
        + "/newDB;create=true;";
    final NetworkServerControl derbyServer = DBSynchronizerTestBase
        .startNetworkServer(derbyPort);
    // set the port in all VMs
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        derbyServerPort = derbyPort;
      }
    });
    derbyServerPort = derbyPort;
    try {
      Runnable runnable = DBSynchronizerTestBase
          .getExecutorForWBCLConfiguration("SG1", "WBCL1",
              "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
              "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
              Integer.valueOf(1), null, false, null, null, null, 100000, "",false);
      runnable.run();
      Runnable startWBCL = DBSynchronizerTestBase.startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);

      // Create a replicated table
      serverSQLExecute(1, "create table trade.customers (cid int not null, "
          + "primary key (cid)) replicate");

      // Create another table with redundant copies
      final String secDDL = "create table trade.securities (sec_id int not null, "
          + "sec_name varchar(20), primary key (sec_id))";
      serverSQLExecute(1, secDDL + " redundancy 1 AsyncEventListener (WBCL1)");

      final Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      final Statement stmt = derbyConn.createStatement();
      stmt.execute(secDDL);
      stmt.execute("create table trade.portfolio (cid int not null, "
          + "sid int not null, qty int not null, availQty int not null, "
          + "subTotal decimal(30,20), tid int)");
      try {
        // now create a function table in GemFireXD that pulls results from derby
        clientSQLExecute(1, "CREATE FUNCTION trade.portfolio () "
            + "RETURNS TABLE (cid int, sid int, qty int, availQty int, "
            + "subTotal decimal(30,20), tid int) LANGUAGE JAVA "
            + "PARAMETER STYLE DERBY_JDBC_RESULT_SET READS SQL DATA "
            + "EXTERNAL NAME '" + CreateTableDUnit.class.getName()
            + ".readPortfolio'");

        // do inserts in gemfirexd
        clientSQLExecute(1, "insert into trade.customers values (1), (2)");
        serverSQLExecute(2,
            "insert into trade.securities values (4, 'sec4'), (5, 'sec5')");
        // wait for async callbacks to end
        Thread.sleep(3000);

        // then in derby
        stmt.execute("insert into trade.portfolio values "
            + "(1, 4, 10, 20, 100.0, 5), (2, 5, 20, 25, 200.0, 6), "
            + "(3, 6, 30, 40, 300.0, 7)");

        final Statement gfxdStmt = TestUtil.jdbcConn.createStatement();
        // try simple single table query first
        ResultSet rs = gfxdStmt.executeQuery("select f.cid, "
            + "f.sid, f.subTotal, f.qty from table (trade.portfolio()) f "
            + "where f.cid < 5 and (qty = availQty or subTotal > 100.0) "
            + "order by f.cid");
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertEquals(5, rs.getInt(2));
        assertEquals("200.00000000000000000000", rs.getString(3));
        assertEquals(20, rs.getInt(4));
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        assertEquals(6, rs.getInt(2));
        assertEquals("300.00000000000000000000", rs.getString(3));
        assertEquals(30, rs.getInt(4));
        assertFalse(rs.next());

        // now execute a join query in GemFireXD
        rs = gfxdStmt.executeQuery("select f.cid, "
            + "s.sec_id, s.sec_name, f.subTotal, f.qty from trade.customers c, "
            + "trade.securities s, table (trade.portfolio()) f "
            + "where c.cid = f.cid and sec_id = f.sid and f.cid < 5 "
            + "and (qty = availQty or subTotal > 100.0)");
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertEquals(5, rs.getInt(2));
        assertEquals("sec5", rs.getString(3));
        assertEquals("200.00000000000000000000", rs.getString(4));
        assertEquals(20, rs.getInt(5));
        assertFalse(rs.next());

        // try outer joins
        // with replicated table
        boolean gotTwo = false, gotThree = false;
        rs = gfxdStmt.executeQuery("select f.cid, c.cid, f.subTotal, f.qty from"
            + " table (trade.portfolio()) f left outer join trade.customers c"
            + " on f.cid = c.cid where f.cid < 5 and"
            + " (qty = availQty or subTotal > 100.0)");
        for (int i = 1; i <= 2; i++) {
          assertTrue(rs.next());
          if (rs.getInt(1) == 2) {
            assertEquals(2, rs.getInt(1));
            assertEquals(2, rs.getInt(2));
            assertEquals("200.00000000000000000000", rs.getString(3));
            assertEquals(20, rs.getInt(4));
            gotTwo = true;
          }
          else {
            assertEquals(3, rs.getInt(1));
            assertNull(rs.getObject(2));
            assertEquals("300.00000000000000000000", rs.getString(3));
            assertEquals(30, rs.getInt(4));
            gotThree = true;
          }
        }
        assertFalse(rs.next());
        assertTrue(gotTwo);
        gotTwo = false;
        assertTrue(gotThree);
        gotThree = false;
        // with partitioned table
        // TODO: returns extra rows due to the same problem as RT+PT outer join
        // issue -- see bug #43701
        /*
        rs = gfxdStmt.executeQuery("select f.cid, "
            + "s.sec_id, s.sec_name, f.subTotal, f.qty from "
            + "table (trade.portfolio()) f left outer join trade.securities s "
            + "on f.sid = s.sec_id where f.cid < 5 "
            + "and (qty = availQty or subTotal > 100.0)");
        for (int i = 1; i <= 2; i++) {
          assertTrue(rs.next());
          if (rs.getInt(1) == 2) {
            assertEquals(2, rs.getInt(1));
            assertEquals(5, rs.getInt(2));
            assertEquals("sec5", rs.getString(3));
            assertEquals("200.00000000000000000000", rs.getString(4));
            assertEquals(20, rs.getInt(5));
            gotTwo = true;
          }
          else {
            assertEquals(3, rs.getInt(1));
            assertNull(rs.getObject(2));
            assertNull(rs.getString(3));
            assertEquals("300.00000000000000000000", rs.getString(4));
            assertEquals(30, rs.getInt(5));
            gotThree = true;
          }
        }
        assertFalse(rs.next());
        assertTrue(gotTwo);
        gotTwo = false;
        assertTrue(gotThree);
        gotThree = false;
        */

        DBSynchronizerTestBase.stopAsyncEventListener("WBCL1").run();
        Thread.sleep(3000);

      } finally {
        stmt.execute("drop table trade.portfolio");
        stmt.execute("drop table trade.securities");
        stmt.close();
        derbyConn.close();
      }
    } finally {
      derbyServer.shutdown();
    }
  }

  private String getConditionalOffheapSuffix(boolean enableOffheapForTable) {
    if(enableOffheapForTable) {
      return " offheap ";
    }else {
      return " ";
    }
  }
  
  

  private static int derbyServerPort;

  public static ResultSet readPortfolio() throws Exception {
    final String derbyDbUrl = "jdbc:derby://localhost:" + derbyServerPort
        + "/newDB;";
    final Connection conn = DriverManager.getConnection(derbyDbUrl);
    PreparedStatement ps = conn
        .prepareStatement("select * from trade.portfolio");
    return ps.executeQuery();
  }

  public static class GfxdTestRowLoader implements RowLoader {

    private String params;

    /**
     * Very simple implementation which will load a value of 1 for all the
     * columns of the table.
     */
    public Object getRow(String schemaName, String tableName,
        Object[] primarykey) throws SQLException {
      System.out.println("Asif");
      SanityManager.DEBUG_PRINT("GfxdTestRowLoader", "load called with key="
          + primarykey[0] + " in VM "
          + Misc.getDistributedSystem().getDistributedMember());
      Integer num = (Integer)primarykey[0];
      Object[] values = new Object[] { num, "DESC" + num, "ADDR" + num, num };
      return values;
    }

    public String getParams() {
      return this.params;
    }

    public void init(String initStr) throws SQLException {
      this.params = initStr;
    }
  }


  /**
   * Iterate over all the buckets and for each bucket check that all entries are
   * in the same list/range of values as given in on of the list/range given in
   * <code>valueList</code> argument. Hence this is useful for checking if all
   * the keys/values has been distributed as specified by "PARTITION BY" clause.
   */
  public static void checkBucketValuesInList(Object schemaName,
      Object tableName, List<Object> valueList, Boolean isRangeList)
      throws Exception {
    String fullTableName = TestUtil.getFullTableName(schemaName, tableName);
    PartitionedRegion pRegion = (PartitionedRegion)Misc
        .getRegionForTable(fullTableName, false);
    assertNotNull("Partitioned region for table " + fullTableName
        + " not found", pRegion);
    for (Object bucket : pRegion.getDataStore().getAllLocalBuckets()) {
      BucketRegion bRegion = (BucketRegion)((Map.Entry<?, ?>)bucket).getValue();
      if (bRegion == null) {
        continue;
      }
      getGlobalLogger().info(
          "checkBucketValuesInList: Iterating over bucket region: "
              + bRegion.getFullPath());
      Object expectedValue = null;
      String keylog = "values in this bucketregion are: ";
      for (Object knkey : bRegion.keySet()) {
        keylog += knkey.toString() + ",";
      }
      getGlobalLogger().info(
          "keys in this bucket region = " + bRegion.getName() + " are: "
              + keylog);
      for (Object bEntry : bRegion.entrySet()) {
        final Object bucketValue = ((RegionKey)((Map.Entry<?, ?>)bEntry)
            .getKey()).getKeyColumn(0).getObject();
        getGlobalLogger().info(
            "checkBucketValuesInList: Checking for bucket entry: "
                + bucketValue);

        final ResolverUtils.GfxdComparableFuzzy bvFuzzy = new ResolverUtils.GfxdComparableFuzzy(bucketValue);

        final Visitor expectedListVisitor = new Visitor() {
          Object foundNode = null;

          public boolean visit(Object node) {
            getGlobalLogger().info(
                "KNS: visit being called for bvfuzzy: " + bvFuzzy
                    + ", in range: " + node.toString());
            boolean found;
            if (node instanceof ResolverUtils.GfxdRange) {
              found = (((ResolverUtils.GfxdRange)node).inRange(bvFuzzy) == 0);
            }
            else {
              found = node.equals(bucketValue);
            }
            if (found) {
              this.foundNode = node;
            }
            getGlobalLogger().info("KNS: returning found = " + found);
            return found;
          }

          public Object getState() {
            return this.foundNode;
          }
        };

        if (expectedValue == null) {
          // Find which valueList/range is the one that should be used for all
          // remaining bucket elements
          if (isRangeList.booleanValue()) {
            assertTrue("Value in bucket [" + bucketValue
                + "] not in the expected lists of values: " + valueList,
                forEach(valueList, expectedListVisitor));
            ResolverUtils.GfxdRange expectedRange = (ResolverUtils.GfxdRange)expectedListVisitor.getState();
            expectedValue = expectedRange.clone();
            getGlobalLogger().info(
                "invalidate called while checking for entry: " + bucketValue);
            expectedRange.invalidate();
          }
          else {
            final Visitor valueListVisitor = new Visitor() {
              Object foundList = null;

              public boolean visit(Object node) {
                if (node instanceof List<?>) {
                  if (forEach((List<?>)node, expectedListVisitor)) {
                    this.foundList = node;
                    return true;
                  }
                }
                return false;
              }

              public Object getState() {
                return this.foundList;
              }
            };
            assertTrue("Value in bucket [" + bucketValue
                + "] not in the expected lists of values: " + valueList,
                forEach(valueList, valueListVisitor));
            ArrayList<?> expectedList = (ArrayList<?>)valueListVisitor
                .getState();
            expectedValue = expectedList.clone();
            expectedList.clear();
          }
          assertNotNull(
              "checkBucketValuesInList: expected to find a list/range for "
                  + "first bucket value [" + bucketValue + ']', expectedValue);
          getGlobalLogger().info(
              "checkBucketValuesInList: found first bucket value ["
                  + bucketValue + "] in list/range: " + expectedValue);
        }
        else {
          if (isRangeList.booleanValue()) {
            assertTrue("Value in bucket [" + bucketValue
                + "] not in the expected range of values: " + expectedValue,
                ((ResolverUtils.GfxdRange)expectedValue).inRange(bvFuzzy) == 0);
          }
          else {
            assertTrue("Value in bucket [" + bucketValue
                + "] not in the expected list of values: " + expectedValue,
                forEach((List<?>)expectedValue, expectedListVisitor));
          }
          getGlobalLogger().info(
              "checkBucketValuesInList: found bucket value [" + bucketValue
                  + "] in list/range: " + expectedValue);
        }
      }
    }
  }

  /**
   * Visitor for iterating over a list of lists/ranges.
   *
   * @author swale
   * @since 6.0
   */
  public interface Visitor {
    /**
     * visit a node and return true if visit was successful and visiting further
     * nodes is not required
     */
    public abstract boolean visit(Object node);

    /** any state object after the visit to a collection/node is complete */
    public Object getState();
  }

  /**
   * Execute a visitor over a collection of values.
   *
   * @return true if {@link Visitor#visit(Object)} was successful on any node
   */
  private static boolean forEach(Collection<?> values, Visitor visitor) {
    boolean valueFound = false;
    for (Object value : values) {
      if (value instanceof ResolverUtils.GfxdRange) {
        if (((ResolverUtils.GfxdRange)value).isInvalid()) {
          continue;
        }
      }
      if ((valueFound = visitor.visit(value))) {
        break;
      }
    }
    return valueFound;
  }
  
}
