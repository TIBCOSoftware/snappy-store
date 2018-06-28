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
package com.pivotal.gemfirexd.gii;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InitialImageOperation;
import com.gemstone.gemfire.internal.cache.InitialImageOperation.GIITestHook;
import com.gemstone.gemfire.internal.cache.InitialImageOperation.GIITestHookType;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.shared.Version;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;

public class DeltaGIIDUnit  extends DistributedSQLTestBase {
  private final static String DISKSTORE = "DeltaGIIDUnit";

  public DeltaGIIDUnit(String name) {
    super(name);
  }
  
  
  
  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
    invokeInEveryVM(InitialImageOperation.class, "resetAllGIITestHooks");
  }



  public String getSuffix() throws Exception {
    String suffix = " PERSISTENT " + "'" + DISKSTORE + "'";
    return suffix;
  }

  public void createDiskStore(boolean useClient, int vmNum) throws Exception {
    SerializableRunnable csr = getDiskStoreCreator(DISKSTORE);
    if (useClient) {
      if (vmNum == 1) {
        csr.run();
      }
      else {
        clientExecute(vmNum, csr);
      }
    }
    else {
      serverExecute(vmNum, csr);
    }
  }

  @Override
  protected String[] testSpecificDirectoriesForDeletion() {
    return new String[] { "test_dir" };
  }
  
  /**
   * Test insufficient data store behaviour for distributed/update/delete/select
   * and for primary key based select/update/delete
   * 
   * @throws Exception
   */
  public void testGFXDDeltaWithDeltaGII() throws Exception {
    startVMs(0, 2);
    startVMs(1, 0);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    Map<Integer, String> expected = new HashMap<Integer, String>();
    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) ENABLE CONCURRENCY CHECKS replicate "
        + getSuffix());
    Connection conn = TestUtil.getConnection();
    PreparedStatement psInsert = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 5; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "unmodified");
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
      expected.put(i,  "unmodified");
    }
    //Stop a VM, and create some modifications
    stopVMNums(-2);
    
    PreparedStatement psUpdate = conn
        .prepareStatement("update trade.customers set cust_name=? where cid=?");
    psUpdate.setString(1, "BeforeRestart");
    psUpdate.setInt(2, 2);
    psUpdate.execute();
    expected.put(2,  "BeforeRestart");
    
    blockGII(-2, GIITestHookType.BeforeRequestRVV);
    blockGII(-2, GIITestHookType.AfterReceivedImageReply);
    
    AsyncVM async2 = restartServerVMAsync(2, 0, null, null);
    
    waitForGIICallbackStarted(-2, GIITestHookType.BeforeRequestRVV);
    
    //Do an update before requesting the RVV. If we apply this update
    //to the RVV On the recipient, it will cause us to fail to fetch the base 
    //value for the row
    psUpdate.setString(1, "BeforeRequestRVV");
    psUpdate.setInt(2, 3);
    psUpdate.execute();
    expected.put(3,  "BeforeRequestRVV");
    
    //Let the GII proceeed until after we receive the initial image reply 
    //(but before we process it)
    unblockGII(-2, GIITestHookType.BeforeRequestRVV);
    
    waitForGIICallbackStarted(-2, GIITestHookType.AfterReceivedImageReply);
    
    //Do an update. If this is not applied to the RVV at some point
    //the RVV will not match the region contents.
    psUpdate.setString(1, "AfterReceivedImageReply");
    psUpdate.setInt(2, 4);
    psUpdate.execute();
    expected.put(4, "AfterReceivedImageReply");
    
    //unblock the GII. The GII should now finish
    unblockGII(-2, GIITestHookType.AfterReceivedImageReply);
    
    joinVM(false, async2);
    
    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);
    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" +rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }
    
    {
    //Make sure vm2 has the correct contents.
    //Now we want to validate the region contents and RVVs...
    Statement s = conn.createStatement();
    s.execute("select * from trade.customers");
    ResultSet rs = s.getResultSet();
    
    Map<Integer, String> received = new HashMap();
    while(rs.next()) {
      received.put(rs.getInt("cid"), rs.getString("cust_name"));
    }
    
    assertEquals(expected,received);
    }
    
    stopVMNums(-1);
    
    //Make sure vm2 has the correct contents.
    //Now we want to validate the region contents and RVVs...
    {
    Statement s = conn.createStatement();
    s.execute("select * from trade.customers");
    ResultSet rs = s.getResultSet();
    
    Map<Integer, String> received = new HashMap();
    while(rs.next()) {
      received.put(rs.getInt("cid"), rs.getString("cust_name"));
    }
    assertEquals(expected,received);
    }
    
  }
  
  /**
   * Test insufficient data store behaviour for distributed/update/delete/select
   * and for primary key based select/update/delete
   * 
   * @throws Exception
   */
  public void testGFXDDeltaWithDeltaGII_serverExecute() throws Exception {
    startVMs(0, 2);
    startVMs(1, 0);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    Map<Integer, String> expected = new HashMap<Integer, String>();
    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) ENABLE CONCURRENCY CHECKS replicate "
        + getSuffix());
    Connection conn = TestUtil.getConnection();
    for (int i = 1; i < 5; ++i) {
      serverSQLExecute(1, "insert into trade.customers values ("+i+", 'unmodified', "+i+")");
      expected.put(i,  "unmodified");
    }
    //Stop a VM, and create some modifications
    stopVMNums(-2);
    
    serverSQLExecute(1, "update trade.customers set cust_name='BeforeRestart' where cid=2");
    expected.put(2,  "BeforeRestart");
    
    blockGII(-2, GIITestHookType.BeforeRequestRVV);
    blockGII(-2, GIITestHookType.AfterReceivedImageReply);
    
    AsyncVM async2 = restartServerVMAsync(2, 0, null, null);
    
    waitForGIICallbackStarted(-2, GIITestHookType.BeforeRequestRVV);
    
    //Do an update before requesting the RVV. If we apply this update
    //to the RVV On the recipient, it will cause us to fail to fetch the base 
    //value for the row
    serverSQLExecute(1, "update trade.customers set cust_name='BeforeRequestRVV' where cid=3");
    expected.put(3,  "BeforeRequestRVV");
    
    //Let the GII proceeed until after we receive the initial image reply 
    //(but before we process it)
    unblockGII(-2, GIITestHookType.BeforeRequestRVV);
    
    waitForGIICallbackStarted(-2, GIITestHookType.AfterReceivedImageReply);
    
    //Do an update. If this is not applied to the RVV at some point
    //the RVV will not match the region contents.
    serverSQLExecute(1, "update trade.customers set cust_name='AfterReceivedImageReply' where cid=4");
    expected.put(4,  "AfterReceivedImageReply");
    
    //unblock the GII. The GII should now finish
    unblockGII(-2, GIITestHookType.AfterReceivedImageReply);
    
    joinVM(false, async2);
    
    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);
    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" +rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }
    
    {
    //Make sure vm2 has the correct contents.
    //Now we want to validate the region contents and RVVs...
    Statement s = conn.createStatement();
    s.execute("select * from trade.customers");
    ResultSet rs = s.getResultSet();
    
    Map<Integer, String> received = new HashMap();
    while(rs.next()) {
      received.put(rs.getInt("cid"), rs.getString("cust_name"));
    }
    
    assertEquals(expected,received);
    }
    
    stopVMNums(-1);
    
    //Make sure vm2 has the correct contents.
    //Now we want to validate the region contents and RVVs...
    {
    Statement s = conn.createStatement();
    s.execute("select * from trade.customers");
    ResultSet rs = s.getResultSet();
    
    Map<Integer, String> received = new HashMap();
    while(rs.next()) {
      received.put(rs.getInt("cid"), rs.getString("cust_name"));
    }
    assertEquals(expected,received);
    }
    
  }

  /**
   * Test insufficient data store behaviour for distributed/update/delete/select
   * and for primary key based select/update/delete
   *
   * @throws Exception
   */
  public void testGFXDDeltaWithDeltaGIINoAutoCommit() throws Exception {
    startVMs(0, 2);
    startVMs(1, 0);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    Map<Integer, String> expected = new HashMap<Integer, String>();
    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) ENABLE CONCURRENCY CHECKS replicate "
        + getSuffix());
    Connection conn = TestUtil.getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(false);
    PreparedStatement psInsert = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 5; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "unmodified");
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
      expected.put(i,  "unmodified");
    }
    conn.commit();
    //Stop a VM, and create some modifications
    stopVMNums(-2);

    PreparedStatement psUpdate = conn
        .prepareStatement("update trade.customers set cust_name=? where cid=?");
    psUpdate.setString(1, "BeforeRestart");
    psUpdate.setInt(2, 2);
    psUpdate.execute();
    conn.commit();
    expected.put(2,  "BeforeRestart");

    blockGII(-2, GIITestHookType.BeforeRequestRVV);
    blockGII(-2, GIITestHookType.AfterReceivedImageReply);

    AsyncVM async2 = restartServerVMAsync(2, 0, null, null);

    waitForGIICallbackStarted(-2, GIITestHookType.BeforeRequestRVV);

    //Do an update before requesting the RVV. If we apply this update
    //to the RVV On the recipient, it will cause us to fail to fetch the base
    //value for the row
    psUpdate.setString(1, "BeforeRequestRVV");
    psUpdate.setInt(2, 3);
    psUpdate.execute();
    conn.commit();
    expected.put(3,  "BeforeRequestRVV");

    //Let the GII proceeed until after we receive the initial image reply
    //(but before we process it)
    unblockGII(-2, GIITestHookType.BeforeRequestRVV);

    waitForGIICallbackStarted(-2, GIITestHookType.AfterReceivedImageReply);

    //Do an update. If this is not applied to the RVV at some point
    //the RVV will not match the region contents.
    psUpdate.setString(1, "AfterReceivedImageReply");
    psUpdate.setInt(2, 4);
    psUpdate.execute();
    conn.commit();
    expected.put(4,  "AfterReceivedImageReply");

    //unblock the GII. The GII should now finish
    unblockGII(-2, GIITestHookType.AfterReceivedImageReply);

    System.out.println("SKSK unblockGII AfterReceivedImageReply");
    joinVM(false, async2);



    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);
    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" +rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }

    {
      //Make sure vm2 has the correct contents.
      //Now we want to validate the region contents and RVVs...
      Statement s = conn.createStatement();
      s.execute("select * from trade.customers");
      ResultSet rs = s.getResultSet();

      Map<Integer, String> received = new HashMap();
      while(rs.next()) {
        received.put(rs.getInt("cid"), rs.getString("cust_name"));
      }

      assertEquals(expected,received);
    }

    stopVMNums(-1);

    //Make sure vm2 has the correct contents.
    //Now we want to validate the region contents and RVVs...
    {
      Statement s = conn.createStatement();
      s.execute("select * from trade.customers");
      ResultSet rs = s.getResultSet();

      Map<Integer, String> received = new HashMap();
      while(rs.next()) {
        received.put(rs.getInt("cid"), rs.getString("cust_name"));
      }
      assertEquals(expected,received);
    }
    conn.commit();
  }

  /**
   * Test insufficient data store behaviour for distributed/update/delete/select
   * and for primary key based select/update/delete
   *
   * @throws Exception
   */
  public void testGFXDDeleteWithDeltaGIITX() throws Exception {
    startVMs(0, 2);
    startVMs(1, 0);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    Map<Integer, String> expected = new HashMap<Integer, String>();
    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) ENABLE CONCURRENCY CHECKS replicate "
        + getSuffix());
    Connection conn = TestUtil.getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(true);
    PreparedStatement psInsert = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 5; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "unmodified");
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
      expected.put(i,  "unmodified");
    }
    //Stop a VM, and create some modifications
    stopVMNums(-2);

    PreparedStatement psUpdate = conn
        .prepareStatement("update trade.customers set cust_name=? where cid=?");
    psUpdate.setString(1, "BeforeRestart");
    psUpdate.setInt(2, 2);
    psUpdate.execute();
    expected.put(2,  "BeforeRestart");

    blockGII(-2, GIITestHookType.BeforeRequestRVV);
    blockGII(-2, GIITestHookType.AfterReceivedImageReply);

    AsyncVM async2 = restartServerVMAsync(2, 0, null, null);

    waitForGIICallbackStarted(-2, GIITestHookType.BeforeRequestRVV);

    //Do an update before requesting the RVV. If we apply this update
    //to the RVV On the recipient, it will cause us to fail to fetch the base
    //value for the row
    psUpdate.setString(1, "BeforeRequestRVV");
    psUpdate.setInt(2, 3);
    psUpdate.execute();

    expected.put(3,  "BeforeRequestRVV");

    //Let the GII proceeed until after we receive the initial image reply
    //(but before we process it)
    unblockGII(-2, GIITestHookType.BeforeRequestRVV);

    waitForGIICallbackStarted(-2, GIITestHookType.AfterReceivedImageReply);

    //Do a delete. If this is not applied to the RVV at some point
    //the RVV will not match the region contents.

    Statement st = conn.createStatement();
    boolean b = st.execute("delete from trade.customers where cid = 4");
    expected.remove(4);

    //unblock the GII. The GII should now finish
    unblockGII(-2, GIITestHookType.AfterReceivedImageReply);

    joinVM(false, async2);

    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);
    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" +rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }

    {
      //Make sure vm2 has the correct contents.
      //Now we want to validate the region contents and RVVs...
      Statement s = conn.createStatement();
      s.execute("select * from trade.customers");
      ResultSet rs = s.getResultSet();

      Map<Integer, String> received = new HashMap();
      while(rs.next()) {
        received.put(rs.getInt("cid"), rs.getString("cust_name"));
      }
      assertEquals(expected,received);
    }

    stopVMNums(-1);

    //Make sure vm2 has the correct contents.
    //Now we want to validate the region contents and RVVs...
    {
      Statement s = conn.createStatement();
      s.execute("select * from trade.customers");
      ResultSet rs = s.getResultSet();

      Map<Integer, String> received = new HashMap();
      while(rs.next()) {
        received.put(rs.getInt("cid"), rs.getString("cust_name"));
      }
      assertEquals(expected,received);
    }
  }



  /**
   * Test insufficient data store behaviour for distributed/update/delete/select
   * and for primary key based select/update/delete
   *
   * @throws Exception
   */
  public void testGFXDDeltaWithDeltaGIITX() throws Exception {
    startVMs(0, 2);
    startVMs(1, 0);
    createDiskStore(true, 1);
    // Create a schema
    clientSQLExecute(1, "create schema trade");

    Map<Integer, String> expected = new HashMap<Integer, String>();
    clientSQLExecute(1, "create table trade.customers (cid int not null, "
        + "cust_name varchar(100), tid int, primary key (cid)) ENABLE CONCURRENCY CHECKS replicate "
        + getSuffix());
    Connection conn = TestUtil.getConnection();
    conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    conn.setAutoCommit(true);
    PreparedStatement psInsert = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 5; ++i) {
      psInsert.setInt(1, i);
      psInsert.setString(2, "unmodified");
      psInsert.setInt(3, i);
      psInsert.executeUpdate();
      expected.put(i,  "unmodified");
    }
    //Stop a VM, and create some modifications
    stopVMNums(-2);

    PreparedStatement psUpdate = conn
        .prepareStatement("update trade.customers set cust_name=? where cid=?");
    psUpdate.setString(1, "BeforeRestart");
    psUpdate.setInt(2, 2);
    psUpdate.execute();
    expected.put(2,  "BeforeRestart");

    blockGII(-2, GIITestHookType.BeforeRequestRVV);
    blockGII(-2, GIITestHookType.AfterReceivedImageReply);

    AsyncVM async2 = restartServerVMAsync(2, 0, null, null);

    waitForGIICallbackStarted(-2, GIITestHookType.BeforeRequestRVV);

    //Do an update before requesting the RVV. If we apply this update
    //to the RVV On the recipient, it will cause us to fail to fetch the base
    //value for the row
    psUpdate.setString(1, "BeforeRequestRVV");
    psUpdate.setInt(2, 3);
    psUpdate.execute();

    expected.put(3,  "BeforeRequestRVV");

    //Let the GII proceeed until after we receive the initial image reply
    //(but before we process it)
    unblockGII(-2, GIITestHookType.BeforeRequestRVV);

    waitForGIICallbackStarted(-2, GIITestHookType.AfterReceivedImageReply);

    //Do an update. If this is not applied to the RVV at some point
    //the RVV will not match the region contents.
    psUpdate.setString(1, "AfterReceivedImageReply");
    psUpdate.setInt(2, 4);
    psUpdate.execute();
    expected.put(4,  "AfterReceivedImageReply");

    //unblock the GII. The GII should now finish
    unblockGII(-2, GIITestHookType.AfterReceivedImageReply);

    joinVM(false, async2);

    RegionVersionVector rvv1 = getRVV(-1);
    RegionVersionVector rvv2 = getRVV(-2);
    if(!rvv1.logicallySameAs(rvv2)) {
      fail("RVVS don't match. provider=" +rvv1.fullToString() + ", recipient=" + rvv2.fullToString());
    }

    {
      //Make sure vm2 has the correct contents.
      //Now we want to validate the region contents and RVVs...
      Statement s = conn.createStatement();
      s.execute("select * from trade.customers");
      ResultSet rs = s.getResultSet();

      Map<Integer, String> received = new HashMap();
      while(rs.next()) {
        received.put(rs.getInt("cid"), rs.getString("cust_name"));
      }

      assertEquals(expected,received);
    }

    stopVMNums(-1);

    //Make sure vm2 has the correct contents.
    //Now we want to validate the region contents and RVVs...
    {
      Statement s = conn.createStatement();
      s.execute("select * from trade.customers");
      ResultSet rs = s.getResultSet();

      Map<Integer, String> received = new HashMap();
      while(rs.next()) {
        received.put(rs.getInt("cid"), rs.getString("cust_name"));
      }
      assertEquals(expected,received);
    }
  }

  public void blockGII(int vmNum, GIITestHookType type) throws Exception {
    serverExecute(-vmNum, new BlockGII(type));
  }
  
  public void unblockGII(int vmNum, GIITestHookType type) throws Exception {
    serverExecute(-vmNum, new ReleaseGII(type));
  }
  
  public void waitForGIICallbackStarted(int vmNum, GIITestHookType type) throws Exception {
    serverExecute(-vmNum, new WaitForGIICallbackStarted(type));
  }
  
  private static class BlockGII implements Runnable, Serializable {
    private GIITestHookType type;

    public BlockGII(GIITestHookType type) {
      this.type = type;
    }

    @Override
    public void run() {
      InitialImageOperation.setGIITestHook(new Mycallback(type, "CUSTOMERS"));
    }
  }

  private static class WaitForGIICallbackStarted extends SerializableRunnable {
    private GIITestHookType type;
    
    public WaitForGIICallbackStarted(GIITestHookType type) {
      this.type = type;
    }

    @Override
    public void run() {
      final GIITestHook callback = InitialImageOperation.getGIITestHookForCheckingPurpose(type);
      WaitCriterion ev = new WaitCriterion() {

        public boolean done() {
          return (callback != null && callback.isRunning);
        }
        public String description() {
          return null;
        }
      };

      waitForCriterion(ev, 30000, 200, true);
      if (callback == null || !callback.isRunning) {
        fail("GII tesk hook is not started yet");
      }
    }
  }

  private static class ReleaseGII extends SerializableRunnable {
    private GIITestHookType type;

    public ReleaseGII(GIITestHookType type) {
      this.type = type;
    }

    @Override
    public void run() {
      InitialImageOperation.resetGIITestHook(type, true);
      
    }
  }
  
  private static class Mycallback extends GIITestHook {
    private final Object lockObject = new Object();
    private boolean resetDone;

    public Mycallback(GIITestHookType type, String region_name) {
      super(type, region_name);
    }
    
    @Override
    public void reset() {
      synchronized (this.lockObject) {
        this.resetDone = true;
        this.lockObject.notifyAll();
      }
    }

    @Override
    public void run() {
      synchronized (this.lockObject) {
        while (!resetDone) {
          try {
            isRunning = true;
            this.lockObject.wait(1000);
          } catch (InterruptedException e) {
          }
        }
      }
    }
  }

  protected RegionVersionVector getRVV(int vmNum) throws Exception {
    SerializableCallable getRVV = new SerializableCallable("getRVV") {

      public Object call() throws Exception {
        Cache cache = GemFireCacheImpl.getInstance();
        String path = Misc.getRegionPath("TRADE.CUSTOMERS");
        System.out.println("DAN DEBUG - Trying to get " + path + " regions=" + cache.rootRegions() + " TRADE subregions=" + cache.getRegion("TRADE").subregions(true));
        LocalRegion region = (LocalRegion) cache.getRegion(Misc.getRegionPath("TRADE.CUSTOMERS"));
        System.out.println("DAN DEBUG - Region is a " + region.getConcurrencyChecksEnabled());
        RegionVersionVector rvv = region.getVersionVector();
        rvv = rvv.getCloneForTransmission();
        HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);

        //Using gemfire serialization because 
        //RegionVersionVector is not java serializable
        DataSerializer.writeObject(rvv, hdos);
        return hdos.toByteArray();
      }
    };
    
    byte[] result= (byte[]) serverExecute(-vmNum, getRVV);
    ByteArrayInputStream bais = new ByteArrayInputStream(result);
    return DataSerializer.readObject(new DataInputStream(bais));
  }
}
