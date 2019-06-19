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
package com.pivotal.gemfirexd.internal.engine.management;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.management.EvictionAttributesData;
import com.gemstone.gemfire.management.internal.MBeanJMXAdapter;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.gemstone.gemfire.management.internal.beans.RegionMBeanCompositeDataFactory;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.diag.MemoryAnalyticsVTI;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.management.TableMXBean.TableMetadata;
import com.pivotal.gemfirexd.internal.engine.management.impl.GfxdManagementTestBase;
import com.pivotal.gemfirexd.internal.engine.management.impl.InternalManagementService;
import com.pivotal.gemfirexd.internal.engine.management.impl.ManagementUtils;
import com.pivotal.gemfirexd.internal.engine.management.impl.TableMBean;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.SYSTABLESRowFactory;
import io.snappydata.test.dunit.AvailablePortHelper;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.VM;

public class GfxdTableMBeanDUnit extends GfxdManagementTestBase {

  private static final long serialVersionUID = 1L;

  static final String grp1 = "GRP1";
  static final String grp2 = "GRP2";
  static final String grp3 = "GRP3";
  static final int    numberOfInserts = 10;
  static final int    numberOfDeletes = 2;
  static final int    numberOfUpdates = 2 ; 
  static final String SCHEMA = "TRADE";
  static final String TABLE = "CUSTOMERS";
  static final String PARTITION_TABLE = "PARTITION_CUSTOMERS";
  static final String POLICY = "REPLICATE";
  static final String PARTITION_POLICY = "PERSISTENT_PARTITION";
  static final String PARTITION_SCHEME = "PARTITION BY PRIMARY KEY";
  static final String PERSISTENCE_SCHEME = "SYNCHRONOUS";
    
  
  static final String column1 = "CID";
  static final String column2 = "CUST_NAME";
  static final String column3 = "SINCE";
  static final String column4 = "ADDR";
  static final String column5 = "TID";
  
  static final String ALTER_TABLE = "ALTER_CUSTOMERS";    
  
  final static int    redundancyValue   = 2;
  static final String redundancyClause  = "REDUNDANCY "+ redundancyValue;

  private static List<String> grps = new ArrayList<String>();
  static final String statemetTextForReplicatedTable = "create table "
      + GfxdTableMBeanDUnit.SCHEMA + "." + GfxdTableMBeanDUnit.TABLE
      + " ("+ column1 +" int not null, "
      + column2 + " varchar(100), "+ column3 +" date, "+ column4 +" varchar(100), "+ column5 + " int ) "
      + GfxdTableMBeanDUnit.POLICY + " SERVER GROUPS ("
      + GfxdTableMBeanDUnit.grp1 + ", " + GfxdTableMBeanDUnit.grp2
      + ") PERSISTENT " + GfxdTableMBeanDUnit.PERSISTENCE_SCHEME ;
  
  static final String statemetTextForPartitionTable = "create table "
      + GfxdTableMBeanDUnit.SCHEMA + "." + GfxdTableMBeanDUnit.PARTITION_TABLE
      + " ("+ column1 +" int not null, " + column2 + " varchar(100), "+ column3 +" date, "+ column4 +" varchar(100), "+ column5 + " int  , PRIMARY KEY ("+ column1+ ") ) " + 
      GfxdTableMBeanDUnit.PARTITION_SCHEME + 
      " " + redundancyClause +
      " SERVER GROUPS (" + GfxdTableMBeanDUnit.grp1 + ", " + GfxdTableMBeanDUnit.grp2+ ")" +
      " PERSISTENT " + GfxdTableMBeanDUnit.PERSISTENCE_SCHEME ;
  
  static final String statementTextForAlterTable = "create table "
      + GfxdTableMBeanDUnit.SCHEMA + "." + GfxdTableMBeanDUnit.ALTER_TABLE
      + " ("+ column1 +" int not null, " + column2 + " varchar(100), "+ column3 +" date, "+ column4 +" varchar(100), "+ column5 + " int  ) " ;
  
  public static MBeanServer mbeanServer = ManagementFactory
      .getPlatformMBeanServer();

  public GfxdTableMBeanDUnit(String name) {
    super(name);
    grps.add(GfxdMemberMBeanDUnit.grp1);
    grps.add(GfxdMemberMBeanDUnit.grp2);
    grps.add("DEFAULT");    
  }

  public void testAggregateMemberstats() throws Exception {
    try {
      Properties serverInfo = new Properties();
      serverInfo.setProperty("gemfire.enable-time-statistics", "true");      
      serverInfo.setProperty("gemfirexd.tableAnalyticsUpdateIntervalSeconds", "1");
      serverInfo.setProperty("statistic-sample-rate", "100");
      serverInfo.setProperty("statistic-sampling-enabled", "true");

      startServerVMs(1, 0, GfxdMemberMBeanDUnit.grp1, serverInfo);

      serverInfo.setProperty("jmx-manager", "true");
      serverInfo.setProperty("jmx-manager-start", "true");
      serverInfo.setProperty("jmx-manager-port", "0");// No need to start
                                                      // an
                                                      // Agent for this
                                                      // test      

      startServerVMs(1, 0, GfxdMemberMBeanDUnit.grp2, serverInfo);

      Properties info = new Properties();
      info.setProperty("host-data", "false");
      info.setProperty("gemfire.enable-time-statistics", "true");
      info.setProperty("gemfirexd.tableAnalyticsUpdateIntervalSeconds", "1");
      

      // start a client, register the driver.
      startClientVMs(1, 0, GfxdTableMBeanDUnit.grp3, info);

      // enable StatementStats for all connections in this VM
      System.setProperty(GfxdConstants.GFXD_ENABLE_STATS, "true");

      // check that stats are enabled with System property set
      Connection conn = TestUtil.getConnection(info);
      createTableForVerification(conn, true, 1);
      conn.close();

      stopVMNums(1, -1);
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();

    }
  }

  private void createTableForVerification(Connection conn,
      final boolean enableStats, final int numTimesSampled) throws Exception {

    System.out.println("serverVMs= " + this.serverVMs.size());
    final VM serverVM = this.serverVMs.get(1); // Server Started as a
                                               // manager

    final Statement stmt = conn.createStatement();

    final String createSchemaOrder = "create schema "+ SCHEMA;
    stmt.execute(createSchemaOrder);

    System.out.println("statemetTextForReplicatedTable= " + GfxdTableMBeanDUnit.statemetTextForReplicatedTable);
    System.out.println("statemetTextForPartitionTable= " + GfxdTableMBeanDUnit.statemetTextForPartitionTable);
    System.out.println("statementTextForAlterTable= " + GfxdTableMBeanDUnit.statementTextForAlterTable);    
    
    

    logInfo("Executing statemetTextForReplicatedTable");
    stmt.execute(GfxdTableMBeanDUnit.statemetTextForReplicatedTable);
    
    logInfo("Executing statemetTextForPartitionTable");
    stmt.execute(GfxdTableMBeanDUnit.statemetTextForPartitionTable);
    
    logInfo("Executing statementTextForAlterTable");
    stmt.execute(GfxdTableMBeanDUnit.statementTextForAlterTable); 
    

    logInfo("Executing psInsertCust");
    PreparedStatement psInsertCust = conn.prepareStatement("insert into "
        +  SCHEMA+ "."+ TABLE +" values (?,?,?,?,?)");
    
    for (int i = 0; i < GfxdTableMBeanDUnit.numberOfInserts; i++) {

      psInsertCust.setInt(1, i);
      psInsertCust.setString(2, "XXXX" + i);
      java.sql.Date since = new java.sql.Date(System.currentTimeMillis());
      psInsertCust.setDate(3, since);
      psInsertCust.setString(4, "XXXX" + i);
      psInsertCust.setInt(5, i);
      psInsertCust.executeUpdate();
    }

    logInfo("Executing psDeleteCust");
    PreparedStatement psDeleteCust = conn.prepareStatement("delete from "
        + SCHEMA+ "."+ TABLE + " where "+ column1 +" = ?");

    for (int i = 0; i < GfxdTableMBeanDUnit.numberOfDeletes; i++) {
      psDeleteCust.setInt(1, i);
      psDeleteCust.executeUpdate();
    }

    logInfo("Executing psInsertPartitionCust");
    PreparedStatement psInsertPartitionCust = conn.prepareStatement("insert into "
        +  SCHEMA+ "."+ PARTITION_TABLE +" values (?,?,?,?,?)");

    for (int i = 0; i < GfxdTableMBeanDUnit.numberOfInserts; i++) {

      psInsertPartitionCust.setInt(1, i);
      psInsertPartitionCust.setString(2, "XXXX" + i);
      java.sql.Date since = new java.sql.Date(System.currentTimeMillis());
      psInsertPartitionCust.setDate(3, since);
      psInsertPartitionCust.setString(4, "XXXX" + i);
      psInsertPartitionCust.setInt(5, i);
      psInsertPartitionCust.executeUpdate();
    }    
    
    logInfo("Executing psDeletePartition");
    PreparedStatement psDeletePartition = conn.prepareStatement("delete from "
        + SCHEMA + "." + PARTITION_TABLE + " where "+ column1 +" = ?");

    for (int i = 0; i < GfxdTableMBeanDUnit.numberOfDeletes; i++) {
      psDeletePartition.setInt(1, i);
      psDeletePartition.executeUpdate();
    }
    
    logInfo("Executing psUpdateCust");
    PreparedStatement psUpdateCust = conn.prepareStatement("update " + GfxdTableMBeanDUnit.SCHEMA + "." + GfxdTableMBeanDUnit.TABLE + 
        " set " + GfxdTableMBeanDUnit.column4 + " = 'new address' where " +  GfxdTableMBeanDUnit.column1 + " >  (?)"  );    

    for(int i = 0; i < GfxdTableMBeanDUnit.numberOfUpdates; i++ ){
      psUpdateCust.setInt(1, GfxdTableMBeanDUnit.numberOfInserts - i);
      psUpdateCust.executeUpdate();
    }
    
    //alter table
    logInfo("Executing psAlterTable");
    PreparedStatement psAlterTable = conn.prepareStatement("alter table " + GfxdTableMBeanDUnit.SCHEMA + "." + GfxdTableMBeanDUnit.ALTER_TABLE + 
        " drop column " + GfxdTableMBeanDUnit.column4 );
    psAlterTable.executeUpdate();   
    
    //verify replicated table attributes
    logInfo("Executing verifyReplicateTableMbeans");
    serverVM.invoke(this.getClass(), "verifyReplicateTableMbeans");
    
    //verify partitioned table attributes
    logInfo("Executing verifyPartitionTableMbeans");
    serverVM.invoke(this.getClass(), "verifyPartitionTableMbeans");
    
    //verify alter Table
    logInfo("Executing verifyAlterTableMbeans");
    serverVM.invoke(this.getClass(), "verifyAlterTableMbeans");
    
    //alter table
    PreparedStatement psDropTable = conn.prepareStatement("drop  table " + GfxdTableMBeanDUnit.SCHEMA + "." + GfxdTableMBeanDUnit.ALTER_TABLE );
    psDropTable.executeUpdate();
    
    //verify drop Table
    logInfo("Executing verifyDropTableMbeans");
    serverVM.invoke(this.getClass(), "verifyDropTableMbeans");
    
    
    
    psInsertCust.close();
    psDeleteCust.close();
    psInsertPartitionCust.close();
    psDeletePartition.close();
    psUpdateCust.close();
    psAlterTable.close();
    psDropTable.close();   
    stmt.close();
    logInfo("done verifyTableMbeans");

  }
  
  
  
  public static void verifyDropTableMbeans(){
    final InternalManagementService service = InternalManagementService
        .getInstance(Misc.getMemStore());
    
    GemFireCacheImpl cache = Misc.getGemFireCache();
    InternalDistributedMember thisMember = cache.getDistributedSystem()
        .getDistributedMember();
    
    String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);    

    final ObjectName distrObjectName = ManagementUtils.getTableMBeanName(
        "DEFAULT", memberNameOrId, SCHEMA + "." + GfxdTableMBeanDUnit.ALTER_TABLE );

    logInfo("verifyDropTableMbeans distrObjectName=" + distrObjectName);
    
    waitForCriterion(new WaitCriterion() {
      public String description() {
        return "Waiting for the partition verifyDropTableMbeans to get reflected at managing node";
      }

      public boolean done() {
        TableMXBean bean = service.getMBeanInstance(distrObjectName,
            TableMXBean.class);
        boolean done = (bean == null );
        return done;       
      }

    }, ManagementConstants.REFRESH_TIME * 5, 500, false);

    logInfo("verifyDropTableMbeans did not get MBean for =" + distrObjectName);

    TableMXBean bean = service.getMBeanInstance(distrObjectName,
        TableMXBean.class);
    
    //we should not get this mbean as table is dropped
    assertNull(bean);   
    
  }
  
  public static void verifyAlterTableMbeans(){
    final InternalManagementService service = InternalManagementService
        .getInstance(Misc.getMemStore());
    
    GemFireCacheImpl cache = Misc.getGemFireCache();
    InternalDistributedMember thisMember = cache.getDistributedSystem()
        .getDistributedMember();
    
    String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);    

    final ObjectName distrObjectName = ManagementUtils.getTableMBeanName(
        "DEFAULT", memberNameOrId, SCHEMA + "." + GfxdTableMBeanDUnit.ALTER_TABLE );

    logInfo("verifyAlterTableMbeans distrObjectName=" + distrObjectName);
    
    waitForCriterion(new WaitCriterion() {
      public String description() {
        return "Waiting for the partition verifyAlterTableMbeans to get reflected at managing node";
      }

      public boolean done() {
        TableMXBean bean = service.getMBeanInstance(distrObjectName,
            TableMXBean.class);
        boolean done = (bean != null );
        return done;
        
      }

    }, ManagementConstants.REFRESH_TIME * 5, 500, true);

    logInfo("verifyPartitionTableMbeans got MBean for =" + distrObjectName);

    TableMXBean bean = service.getMBeanInstance(distrObjectName,
        TableMXBean.class);
    
    List<String> definition = bean.getDefinition();
    logInfo("verifyAlterTableMbeans From bean Name=" + definition.size() + " definition = " + definition);
    assertEquals(definition.size(), 4 );
    
    //verify that column4 is not present in definition   
    boolean colmn4Validated = false;
    
    
    
    for(String def : definition){
      logInfo("verifyAlterTableMbeans def="+ def ); 
      if (def.contains(column4)){
        colmn4Validated = true;
        
      }
    }
    //column4 should not be present
    assertFalse(colmn4Validated);   
  }
  
  public static void verifyPartitionTableMbeans() {   
    final InternalManagementService service = InternalManagementService
        .getInstance(Misc.getMemStore());
    Set<String> serverGroupsToUse = new HashSet<String>();
    
    serverGroupsToUse.add(GfxdTableMBeanDUnit.grp1);
    serverGroupsToUse.add(GfxdTableMBeanDUnit.grp2);


    GemFireCacheImpl cache = Misc.getGemFireCache();
    InternalDistributedMember thisMember = cache.getDistributedSystem()
        .getDistributedMember();
    String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);    

    final ObjectName distrObjectName = ManagementUtils.getTableMBeanName(
        GfxdMemberMBeanDUnit.grp2, memberNameOrId, SCHEMA + "." + PARTITION_TABLE);

    logInfo("verifyPartitionTableMbeans distrObjectName=" + distrObjectName);
    
    waitForCriterion(new WaitCriterion() {
      public String description() {
        return "Waiting for the partition tablembean to get reflected at managing node";
      }

      public boolean done() {
        TableMXBean bean = service.getMBeanInstance(distrObjectName,
            TableMXBean.class);
        boolean done = (bean != null && bean.getDeletes() > 0);
        return done;
        
      }

    }, ManagementConstants.REFRESH_TIME * 5, 500, true);

    logInfo("verifyPartitionTableMbeans got MBean for =" + distrObjectName);

    TableMXBean bean = service.getMBeanInstance(distrObjectName,
        TableMXBean.class);
    
    String statsTxtId = CachePerfStats.REGIONPERFSTATS_TEXTID_PREFIX
        + CachePerfStats.REGIONPERFSTATS_TEXTID_PR_SPECIFIER
        + ((TableMBean) bean).getRegion().getName();
    
    logInfo("partition statsTxtId=  "+statsTxtId);

    int inserts = 0;
    int updates = 0;
    int deletes = 0;

    Statistics[] regionPerfStats = cache.getDistributedSystem().findStatisticsByTextId(statsTxtId);
    
    if(regionPerfStats.length > 0){
      logInfo("regionPerfStats size=" + regionPerfStats.length);
      
      //this loop should not running for long so restrict to 5
      for (int i = 0; i < regionPerfStats.length && i < 5 ; i++) {
        inserts = regionPerfStats[i].get("creates").intValue();
        updates = regionPerfStats[i].get("puts").intValue();
        deletes = regionPerfStats[i].get("destroys").intValue();        
      }
    }else{
      logInfo("verifyPartitionTableMbeans did not get regionPerfStats.  Fail the test" );  
      fail();
    }    

    logInfo("From partition bean Inserts=" + bean.getInserts() + " and actual =" + inserts);
    assertEquals(bean.getInserts() ,  inserts );

    logInfo("From partition bean updates=" + bean.getUpdates() + " and actual =" + updates);
    assertEquals(bean.getUpdates(), updates);

    logInfo("From partition bean deletes=" + bean.getDeletes() + " and actual =" + deletes);
    assertEquals(bean.getDeletes(), deletes);

    logInfo("From partition bean parentschema=" + bean.getParentSchema() + " and actual =" + GfxdTableMBeanDUnit.SCHEMA);
    assertEquals(bean.getParentSchema(), GfxdTableMBeanDUnit.SCHEMA );

    logInfo("From partition bean partitioningScheme=" + bean.getPartitioningScheme() + " and actual =" + GfxdTableMBeanDUnit.PARTITION_SCHEME );
    assertEquals(bean.getPartitioningScheme(), GfxdTableMBeanDUnit.PARTITION_SCHEME );

    logInfo("From partition bean policy=" + bean.getPolicy() + " and actual =" + GfxdTableMBeanDUnit.PARTITION_POLICY);
    assertEquals( bean.getPolicy(), GfxdTableMBeanDUnit.PARTITION_POLICY);

    assertTrue(grps.contains(bean.getServerGroups()[0]));
    assertTrue(grps.contains(bean.getServerGroups()[1]));

    logInfo("From partition bean entrySize=" + bean.getEntrySize() );
    assertTrue(bean.getEntrySize() >= 0 );

    logInfo("From partition bean keySize=" + bean.getKeySize() );
    assertTrue(bean.getKeySize() >= 0 );

    logInfo("From partition bean Name=" + bean.getName() + " and actual =" + GfxdTableMBeanDUnit.PARTITION_TABLE);
    assertEquals(bean.getName(), GfxdTableMBeanDUnit.PARTITION_TABLE );
    
    //verify table definition and check the presence of columns as the table has 5 columns
    List<String> definition = bean.getDefinition();
    logInfo("From partition bean Name=" + definition.size() + " definition = " + definition);
    assertEquals(definition.size(), 5 );
    
    //verify each column now
    boolean colmn1Validated = false;
    boolean colmn2Validated = false;
    boolean colmn3Validated = false;
    boolean colmn4Validated = false;
    boolean colmn5Validated = false;
    
    
    for(String def : definition){
      logInfo("partition def="+ def );
      if(def.contains(column1)){
        colmn1Validated = true;
      }else if (def.contains(column2)){
        colmn2Validated = true;
      }else if (def.contains(column3)){
        colmn3Validated = true;
      }else if (def.contains(column4)){
        colmn4Validated = true;
      }else if (def.contains(column5)){
        colmn5Validated = true;
      }
    }
    assertTrue(colmn1Validated);
    assertTrue(colmn2Validated);  
    assertTrue(colmn3Validated);  
    assertTrue(colmn4Validated);  
    assertTrue(colmn5Validated);  
  }
  

  public static void verifyReplicateTableMbeans() {
    System.setProperty("gemfirexd.tableAnalyticsUpdateIntervalSeconds", "1");
    
    final InternalManagementService service = InternalManagementService
        .getInstance(Misc.getMemStore());
    Set<String> serverGroupsToUse = new HashSet<String>();

    serverGroupsToUse.add(GfxdTableMBeanDUnit.grp1);
    serverGroupsToUse.add(GfxdTableMBeanDUnit.grp2);

    GemFireCacheImpl cache = Misc.getGemFireCache();
    InternalDistributedMember thisMember = cache.getDistributedSystem()
        .getDistributedMember();
    String memberNameOrId = MBeanJMXAdapter.getMemberNameOrId(thisMember);
    final ObjectName distrObjectName = ManagementUtils.getTableMBeanName(
        GfxdMemberMBeanDUnit.grp2, memberNameOrId,GfxdTableMBeanDUnit.SCHEMA + "." + GfxdTableMBeanDUnit.TABLE );

    logInfo("verifyReplicateTableMbeans distrObjectName=" + distrObjectName);
    
    waitForCriterion(new WaitCriterion() {
      public String description() {
        return "Waiting for the tablembean to get reflected at managing node";
      }

      public boolean done() {
        TableMXBean bean = service.getMBeanInstance(distrObjectName,
            TableMXBean.class);
        boolean done = (bean != null && bean.getInserts() == GfxdTableMBeanDUnit.numberOfInserts && 
            bean.getDeletes() == GfxdTableMBeanDUnit.numberOfDeletes);
        return done;
      }

    }, ManagementConstants.REFRESH_TIME * 5, 500, true);

    logInfo("verifyReplicateTableMbeans got Mbeanfor =" + distrObjectName);

    TableMXBean bean = service.getMBeanInstance(distrObjectName,
        TableMXBean.class); 

    logInfo("From replicated bean Inserts=" + bean.getInserts() + " and actual =" + GfxdTableMBeanDUnit.numberOfInserts);
    assertEquals(bean.getInserts() ,  GfxdTableMBeanDUnit.numberOfInserts );

    logInfo("From replicated bean numberOfrows=" + bean.getInserts() + " and actual =" + (GfxdTableMBeanDUnit.numberOfInserts - GfxdTableMBeanDUnit.numberOfDeletes ));
    assertEquals(bean.getNumberOfRows(), GfxdTableMBeanDUnit.numberOfInserts - GfxdTableMBeanDUnit.numberOfDeletes );

    logInfo("From replicated bean parentschema=" + bean.getParentSchema() + " and actual =" + GfxdTableMBeanDUnit.SCHEMA);
    assertEquals(bean.getParentSchema(), GfxdTableMBeanDUnit.SCHEMA );

    logInfo("From replicated bean partitioningScheme=" + bean.getPartitioningScheme() + " and actual =" + "NA");
    assertEquals(bean.getPartitioningScheme(), "NA" );

    logInfo("From replicated bean policy=" + bean.getPolicy() + " and actual =" + "PERSISTENT_REPLICATE");
    assertTrue( bean.getPolicy().contains(GfxdTableMBeanDUnit.POLICY.toUpperCase()) );

    assertTrue(grps.contains(bean.getServerGroups()[0]) );
    assertTrue(grps.contains(bean.getServerGroups()[1]) );

    
    int inserts = 0;
    int updates = 0;
    int deletes = 0;
    
    String statsTxtId = CachePerfStats.REGIONPERFSTATS_TEXTID_PREFIX + ((TableMBean) bean).getRegion().getName();    
    logInfo("for replicated table statsTxtId= " + statsTxtId);
    
    Statistics[] regionPerfStats = cache.getDistributedSystem().findStatisticsByTextId(statsTxtId);
    
    if(regionPerfStats.length > 0){
      logInfo("replicated regionPerfStats size=" + regionPerfStats.length);     
      for (int i = 0; i < regionPerfStats.length ;) {    
        inserts = regionPerfStats[i].get("creates").intValue();
        updates = regionPerfStats[i].get("puts").intValue();        
        deletes = regionPerfStats[i].get("destroys").intValue();      
        break;
      }
      logInfo("For replciated table from regionstats inserts=" + inserts);
      logInfo("For replciated table from regionstats updates=" + updates);      
      logInfo("For replciated table from regionstats deletes=" + deletes);
    } 
    
    logInfo("From replicated bean updates=" + bean.getUpdates() + " and actual =" + Math.abs(updates - inserts));
    assertEquals(bean.getUpdates(), Math.abs(updates - inserts));

    logInfo("From replicated bean deletes=" + bean.getDeletes() + " and actual =" + GfxdTableMBeanDUnit.numberOfDeletes);
    assertEquals(bean.getDeletes(), GfxdTableMBeanDUnit.numberOfDeletes);
    
    logInfo("From replicated bean entrySize=" + bean.getEntrySize() );
    assertEquals(true, bean.getEntrySize() >= 0 ? true : false);

    logInfo("From replicated bean keySize=" + bean.getKeySize() );
    assertEquals(true, bean.getKeySize() >= 0 ? true : false);

    logInfo("From replicated bean Name=" + bean.getName() + " and actual =" + GfxdTableMBeanDUnit.TABLE);
    assertEquals(bean.getName(), GfxdTableMBeanDUnit.TABLE );
    
    //verify table definition and check the presence of colmns as the table has 5 columns
    List<String> definition = bean.getDefinition();
    logInfo("From replicated bean Name=" + definition.size() + " definition = " + definition);
    assertEquals(definition.size(), 5 );
    
    //verify each column now
    boolean colmn1Validated = false;
    boolean colmn2Validated = false;
    boolean colmn3Validated = false;
    boolean colmn4Validated = false;
    boolean colmn5Validated = false;
    
    
    for(String def : definition){
      logInfo("replicated def="+ def );
      if(def.contains(column1)){
        colmn1Validated = true;
      }else if (def.contains(column2)){
        colmn2Validated = true;
      }else if (def.contains(column3)){
        colmn3Validated = true;
      }else if (def.contains(column4)){
        colmn4Validated = true;
      }else if (def.contains(column5)){
        colmn5Validated = true;
      }
    }
    assertTrue(colmn1Validated);
    assertTrue(colmn2Validated);  
    assertTrue(colmn3Validated);  
    assertTrue(colmn4Validated);  
    assertTrue(colmn5Validated);
   
 
    
    
    //validate operations   
    GfxdConnectionWrapper connWrapper = InternalManagementService.getAnyInstance().getConnectionWrapperForTEST();
    ResultSet resultSet = null;
    try {
      PreparedStatement  metadataStatement = connWrapper.getConnectionOrNull().prepareStatement("<local>SELECT * FROM SYS."
            +GfxdConstants.SYS_TABLENAME_STRING+" WHERE TABLENAME=? and TABLESCHEMANAME=?");
      metadataStatement.setString(1, bean.getName());
      metadataStatement.setString(2, bean.getParentSchema());
      resultSet = metadataStatement.executeQuery();
      TableMetadata  tableMetadata  = bean.fetchMetadata();
      String tableMetadataStr = tableMetadata.toString() ;
      logInfo("replicated tableMetadataStr="+tableMetadataStr);
      
      if(resultSet.next()){
        TableMetadata tableMetadataFromResultSet = new TableMetadata(
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLEID),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLENAME),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_TABLETYPE),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SCHEMAID),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SCHEMANAME),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LOCKGRANULARITY),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SERVERGROUPS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_DATAPOLICY),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_PARTITIONATTRS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_RESOLVER),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_EXPIRATIONATTRS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_EVICTIONATTRS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_DISKATTRS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LOADER),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_WRITER),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_LISTENERS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_ASYNCLISTENERS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_GATEWAYENABLED),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_SENDERIDS),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_OFFHEAPENABLED ),
            resultSet.getString(SYSTABLESRowFactory.SYSTABLES_ROW_LEVEL_SECURITY_ENABLED));
        
        logInfo("replicated tableMetadataFromResultSet="+tableMetadataFromResultSet.toString());
        assertTrue(tableMetadataStr.toString().equals(tableMetadataFromResultSet.toString()) );      
        
      }
      
      resultSet.close();
      
    }catch (SQLException e) {
      logInfo("Exception while verifying table meta data ="+ e);
    }finally{
      try {
        if(resultSet != null){
          resultSet.close();
        }
      } catch (SQLException e) {
      }
      connWrapper.close();
    }
    
    //verify eviction attributes
    EvictionAttributesData evictionAttributesDataFromMbean = bean.showEvictionAttributes();    
    EvictionAttributesData evictionAttributesData = RegionMBeanCompositeDataFactory.getEvictionAttributesData(((TableMBean) bean).getRegion().getAttributes());    
    assertTrue(evictionAttributesDataFromMbean.getAction().equals(evictionAttributesData.getAction() ));
    assertTrue(evictionAttributesDataFromMbean.getAlgorithm().equals(evictionAttributesData.getAlgorithm()));
    assertEquals(evictionAttributesDataFromMbean.getMaximum(),evictionAttributesData.getMaximum());
    
    
  }
  
  static double getEntrySize(){
    MemoryAnalyticsVTI memoryAnalyticsVTI = new MemoryAnalyticsVTI(true);
    int entrySizeIndex = 0 ;    
    int tableNameIndex = 0 ; 
    
    try {      
      ResultSetMetaData metaData = memoryAnalyticsVTI.getMetaData();
      int columnCount = metaData.getColumnCount();
      for (int i = 1; i <= columnCount; i++) {        
        if (metaData.getColumnName(i).equals(MemoryAnalyticsVTI.TABLE_NAME)) {
          tableNameIndex = i;
        } else if (metaData.getColumnName(i).equals(MemoryAnalyticsVTI.ENTRY_SIZE)) {
          entrySizeIndex = i;
        } 
        
        if(entrySizeIndex != 0 && tableNameIndex !=0){
          break;
        }
      }
      while (memoryAnalyticsVTI.next()) {
        try {
          String tableName = memoryAnalyticsVTI.getString(tableNameIndex);
          logInfo("replicated mbean tableName from VTI =" + tableName);
          
          if (tableName.equals(GfxdTableMBeanDUnit.SCHEMA + "."
              + GfxdTableMBeanDUnit.TABLE)) {
            String entrySizeIndexStr = memoryAnalyticsVTI
                .getString(entrySizeIndex);
            logInfo("replicated mbean entrySizeIndexStr from VTI ="
                + entrySizeIndexStr);

            return Double.parseDouble(entrySizeIndexStr);            
          }

        } catch (Exception e) {
          logInfo("replicated mbean exception in  memoryAnalyticsVTI =" + e);
        }
      }      
    } catch (SQLException e) {
      logInfo("Did not get memoryAnalyticsVTI " + e);
      fail();
    }  
    return 0.0;    
  }
  
 /* static double getKeySize(){
    
    MemoryAnalyticsVTI memoryAnalyticsVTI = new MemoryAnalyticsVTI(true);
    
    int keySizeIndex = 0 ;
    int tableNameIndex = 0 ;   
    
    try {      
      ResultSetMetaData metaData = memoryAnalyticsVTI.getMetaData();
      int columnCount = metaData.getColumnCount();
      for (int i = 1; i <= columnCount; i++) {
        logInfo("replicated memoryAnalyticsVTI metaData.getColumnName(i) " + metaData.getColumnName(i));
        if (metaData.getColumnName(i).equals(MemoryAnalyticsVTI.TABLE_NAME)) {
          tableNameIndex = i;
        } else if (metaData.getColumnName(i).equals(MemoryAnalyticsVTI.KEY_SIZE)) {
          keySizeIndex = i;
        }
        if(tableNameIndex !=0 && keySizeIndex != 0 ){
          break;
        }
      }

      while (memoryAnalyticsVTI.next()) {
        try {
          String tableName = memoryAnalyticsVTI.getString(tableNameIndex);
          logInfo("replicated mbean tableName from VTI =" + tableName);
          
          if (tableName.equals(GfxdTableMBeanDUnit.SCHEMA + "."
              + GfxdTableMBeanDUnit.TABLE)) {
            String keySizeIndexStr = memoryAnalyticsVTI.getString(keySizeIndex);
            logInfo("replicated mbean keySizeIndexStr from VTI ="
                + keySizeIndexStr);            
            return Double.parseDouble(keySizeIndexStr);
          }

        } catch (Exception e) {
          logInfo("replicated mbean exception in  memoryAnalyticsVTI =" + e);
        }
      }      
    } catch (SQLException e) {
      logInfo("Did not get memoryAnalyticsVTI " + e);
      fail();
    }
    return 0.0;        
    
  }*/
  
  private String getSqlQuery(String CREATE_TABLE_SQL  , String SPACE, String partitionByClause, 
      String policyClause, String persistenceClause, String redundancyClause) {
    return CREATE_TABLE_SQL + SPACE +
           (policyClause != null ? policyClause : partitionByClause) + SPACE +
           (redundancyClause != null ? redundancyClause : redundancyClause) + SPACE +
           (persistenceClause != null ? persistenceClause : persistenceClause);
  }
  
  static Properties getManagerConfig(boolean startManager) {
    Properties p = new Properties();

    p.setProperty(DistributionConfig.JMX_MANAGER_NAME, "true");
    p.setProperty(DistributionConfig.JMX_MANAGER_START_NAME, String.valueOf(startManager));
    p.setProperty(DistributionConfig.JMX_MANAGER_PORT_NAME, String.valueOf(
        AvailablePortHelper.getRandomAvailableTCPPort()));

    return p;
  }
  
  static void commitTx() throws SQLException {
    Connection connection = TestUtil.jdbcConn;
    if (connection != null && !connection.getAutoCommit()) {
      connection.commit();
    }
  }
  
  void doInserts(int start, int numOfInserts, String fullTableName) throws Exception {
    String insertTableSql = null;

    for (int i = start; i < (start+numOfInserts); i++) {
      insertTableSql = "INSERT INTO "+fullTableName+" VALUES ('"+i+"', 'Spring"+i+"','TAG"+i+"',"+((int)(Math.random()*200))+",'city"+i+"')";
      logInfo("doInserts: insertTableSql :: "+insertTableSql);
      serverSQLExecute(1, insertTableSql);
    }

    VM vm = Host.getHost(0).getVM(0);
    vm.invoke(GfxdTableMBeanDUnit.class, "commitTx"); // auto-commit is false by default for test
  }

  public void testGetIndexInfo() throws Exception {
    final String SPACE            = " ";
    final String TABLE_SCHEMA     = "APP";
    final String TABLE_NAME       = "BOOKS";
    final String CREATE_TABLE_SQL = "CREATE TABLE "+TABLE_SCHEMA+"."+TABLE_NAME+" (" +
                                          "ID VARCHAR(10) NOT NULL, " +
                                          "NAME VARCHAR(25), " +
                                          "TAG VARCHAR(25), " +
                                          "SIZE BIGINT, " +
                                          "LOCATION VARCHAR(25)" +
                                          ", CONSTRAINT BOOK_PK PRIMARY KEY (ID) " +
                                          ")";
    
    final String INDEX_BY_NAME = "CREATE INDEX INDEX_NAME ON " + TABLE_SCHEMA +"." + TABLE_NAME +  "(NAME)";
    
    final String INDEX_BY_LOCATION = "CREATE INDEX INDEX_LOCATION ON " + TABLE_SCHEMA +"." + TABLE_NAME 
    + "(LOCATION)";
    
    final String INDEX_BY_TAG = "CREATE INDEX INDEX_TAG ON " + TABLE_SCHEMA +"." + TABLE_NAME 
    + "(TAG)";

    String partitionByClause = "PARTITION BY PRIMARY KEY";
    int    redundancyValue   = 2;
    String redundancyClause  = "REDUNDANCY "+redundancyValue;
    String persistenceClause = "EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW PERSISTENT ASYNCHRONOUS";

    boolean serverVmStarted = false;
    try {
      String createTableSql = getSqlQuery(CREATE_TABLE_SQL  , SPACE, partitionByClause,  partitionByClause, persistenceClause, redundancyClause);

      startVMs(0, 1, AvailablePortHelper.getRandomAvailableTCPPort(), ManagementUtils.DEFAULT_SERVER_GROUP,
          getManagerConfig(true));
      serverVmStarted = true;

      serverSQLExecute(1, createTableSql);
      
      String fullTableName = TestUtil.getFullTableName(TABLE_SCHEMA, TABLE_NAME);
      int inserts = 100;
      serverSQLExecute(1, INDEX_BY_LOCATION);
      serverSQLExecute(1, INDEX_BY_NAME);
      serverSQLExecute(1, INDEX_BY_TAG);      
      doInserts(1,inserts, fullTableName);

      VM serverVM = Host.getHost(0).getVM(0);
      ConnectionEndpoint jmxHostPort = (ConnectionEndpoint) serverVM.invoke(GfxdTableMBeanDUnit.class,
          "retrieveJmxHostPort");
      
      MBeanServerConnection mbsc = startJmxClient(jmxHostPort.host, jmxHostPort.port);

      String memberNameOrId = (String) serverVM.invoke(GfxdTableMBeanDUnit.class, "getMemberNameOrId");
      ObjectName tableMBeanName = ManagementUtils.getTableMBeanName(ManagementUtils.DEFAULT_SERVER_GROUP,
          memberNameOrId, TestUtil.getFullTableName(TABLE_SCHEMA, TABLE_NAME));


      
      long rowCount;
      double entrySize;
      
      logInfo("Validating index info for all indexes ");

      Set<String> indexNames = new HashSet<String>();
      String pkIndexName = "2__" + TABLE_NAME + "__ID";
      indexNames.add("INDEX_LOCATION");
      indexNames.add("INDEX_TAG");
      indexNames.add("INDEX_NAME");
      indexNames.add(pkIndexName);
      waitForCriterion(new WaitCriterion() {
        @Override
        public boolean done() {
          try {
            CompositeData[] data = (CompositeData[])mbsc.invoke(tableMBeanName,
                "listIndexInfo", null, null);
            return data != null && data.length == 4;
          } catch (Exception e) {
            throw new AssertionError(e.getMessage(), e);
          }
        }

        @Override
        public String description() {
          return "waiting for index information to show up in metadata";
        }
      }, 30000, 500, true);
      CompositeData[] data = (CompositeData[]) mbsc.invoke(tableMBeanName, "listIndexInfo", null, null);
      assertEquals(4, data.length);
      for (CompositeData indexInfo : data) {
        String indexName = (String) indexInfo.get("indexName");
        logInfo("Validating index info for " + indexName);
        assertTrue(indexNames.contains(indexName));
        if ("INDEX_LOCATION".contains(indexName)) {
          assertEquals("+LOCATION", indexInfo.get("columnsAndOrder"));
        } else if ("INDEX_TAG".contains(indexName)) {
          assertEquals("+TAG", indexInfo.get("columnsAndOrder"));
        } else if ("INDEX_NAME".contains(indexName)) {
          assertEquals("+NAME", indexInfo.get("columnsAndOrder"));
        } else {
          assertEquals(pkIndexName, indexName);
          assertEquals("+ID", indexInfo.get("columnsAndOrder"));
        }
        assertTrue(((String) indexInfo.get("indexType")).contains("LOCAL")
            || indexInfo.get("indexType").equals("PRIMARY KEY"));
      }
      
      CompositeData[] indexStats = (CompositeData[]) mbsc.invoke(tableMBeanName, "listIndexStats", null, null);
      assertEquals(3, indexStats.length);
      for(CompositeData indexStat : indexStats) {
        String indexName = (String) indexStat.get("indexName");
        logInfo("Validating index stats for " + indexName 
             + " rowCount = " + indexStat.get("rowCount") + " entrySize " + indexStat.get("entrySize"));
        logInfo(indexName + " index entry size " + indexStat.get("entrySize"));
        logInfo(indexName + " index key size " + indexStat.get("keySize"));
        assertTrue(indexNames.contains(indexName));
        rowCount=(Long)indexStat.get("rowCount");
        assertEquals(inserts,(int)rowCount);
        entrySize = (Double) indexStat.get("entrySize");        
        assertTrue(entrySize > 0.0d);        
      }      

      serverSQLExecute(1, "DROP INDEX  " + TABLE_SCHEMA + ".INDEX_LOCATION");
      data = (CompositeData[]) mbsc.invoke(tableMBeanName, "listIndexInfo", null, null);
      assertEquals(3, data.length);
      
      indexStats = (CompositeData[]) mbsc.invoke(tableMBeanName, "listIndexStats", null, null);
      assertEquals(2, indexStats.length);  
      
      
      
    } finally {
      stopJmxClient();
      if (serverVmStarted) {
        stopVMNum(-1);
      }
    }
  
  }  

  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
  }
}
