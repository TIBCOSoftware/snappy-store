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
package com.pivotal.gemfirexd.wan;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;
import com.gemstone.gemfire.internal.cache.wan.BatchException70;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.impl.GatewayConflictHelper;
import com.pivotal.gemfirexd.callbacks.impl.GatewayConflictResolver;
import com.pivotal.gemfirexd.callbacks.impl.GatewayConflictResolverWrapper;
import com.pivotal.gemfirexd.callbacks.impl.GatewayEvent;
import com.pivotal.gemfirexd.callbacks.impl.GatewayEvent.GatewayEventType;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import io.snappydata.test.dunit.SerializableRunnable;
import junit.framework.Assert;

@SuppressWarnings("serial")
public class GfxdWanConsistencyDUnit extends GfxdWanTestBase {

  public GfxdWanConsistencyDUnit(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  @Override
  protected void vmTearDown() throws Exception {
    super.vmTearDown();
  }


  
  /**
    * The test scenario is active/passive only in the sense that updates are not
   * propagated from site B to site A, and deliberately so. For the sake of
   * simulating a conflict, we do need to have a row inserted in site B by site
   * B itself.
   */
  public void testResolver_Parallel_ActivePassive_Update() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    /*
     * Test:
     * Don't create sender or receiver yet.
     * Insert the same row on both sites.
     * Create sender on A, receiver on B and install customer resolver on B.
     * Update on site A and verify update on site B.
     * The updated row should include column set by site A and column changed by custom resolver. 
     * 
     */

    startSites(2);

    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "CITY varchar(200) not null,"
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)"
        + "enable concurrency checks";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());

    executeSql(SITE_B,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'city1')");

    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'city1')");

    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);

    final String createGWS = "CREATE gatewaysender MySender (remotedsid 2 isparallel true ) server groups (sg1)";
    executeSql(SITE_A, createGWS);

    // this.installCustomResolver(SITE_B, 2, "customDesc", false, false);

    executeSql(
        SITE_B,
        "CALL SYS.ATTACH_GATEWAY_CONFLICT_RESOLVER('com.pivotal.gemfirexd.wan.GfxdWanConsistencyDUnit$CustomResolver','2,customDesc,false,false')");

    executeSql(SITE_A,
        "update EMP.PARTITIONED_TABLE set city = 'city2' where id = 1");

    // Site A changed row to (1,'First','A714','city2')
    // Custom resolver at site B is expected to change it to
    // (1,'customDesc','A714','city2')

    // waitForTheQueueToGetEmpty 
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");

    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION, CITY from EMP.PARTITIONED_TABLE WHERE ID = 1",
        goldenFile, "id12", true, false);

    executeSql(SITE_B, "CALL SYS.REMOVE_GATEWAY_CONFLICT_RESOLVER()");

    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('MySender')");
  }

  public void testGatewayEventAPI() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";
    startSites(2);

    // create PTable
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "CITY varchar(200) not null,"
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)"
        + "enable concurrency checks";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());

    executeSql(SITE_B,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'city1')");

    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714', 'city1')");

    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);

    final String createGWS = "CREATE gatewaysender MySender (remotedsid 2 isparallel true ) server groups (sg1)";
    executeSql(SITE_A, createGWS);

    executeSql(
        SITE_B,
        "CALL SYS.ATTACH_GATEWAY_CONFLICT_RESOLVER('com.pivotal.gemfirexd.wan.GfxdWanConsistencyDUnit$CustomResolver_API','')");

    executeSql(SITE_A,
        "update EMP.PARTITIONED_TABLE set city = 'city2' where id = 1");

    // Site A changed row to (1,'First','A714','city2')

    // waitForTheQueueToGetEmpty 
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");

    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION, CITY from EMP.PARTITIONED_TABLE WHERE ID = 1",
        goldenFile, "id14", true, false);

    executeSql(SITE_B, "CALL SYS.REMOVE_GATEWAY_CONFLICT_RESOLVER()");
  
    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('MySender')");
  }
  
  /**
   * The test scenario is active/passive only in the sense that updates are not
   * propagated from site B to site A, and deliberately so. For the sake of
   * simulating a conflict, we do need to have a row inserted in site B by site
   * B itself.
   */
  public void testResolver_Parallel_ActivePassive_Insert() throws Exception {
    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    startSites(2);

    // Create table with concurrency checks enabled
    final String createPTable = "create table EMP.PARTITIONED_TABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, "
        + "primary key (ID)) partition by column(ADDRESS)  redundancy 1 server groups (sg1)"
        + "enable concurrency checks";
    executeSql(SITE_A, createPTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createPTable + getSQLSuffixClause());

    // Install custom resolver on B
    // this.installCustomResolver(SITE_B, 2, "customDesc", false, false);

    executeSql(
        SITE_B,
        "CALL SYS.ATTACH_GATEWAY_CONFLICT_RESOLVER('com.pivotal.gemfirexd.wan.GfxdWanConsistencyDUnit$CustomResolver','2,customDesc,false,false')");

    // Insert a row on site B but not on A yet, since we want that row to have
    // a DSID of B.
    executeSql(SITE_B,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");

    // Create receiver on B
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);

    // Create sender on A
    final String createGWS = "CREATE gatewaysender MySender (remotedsid 2 isparallel true ) server groups (sg1)";
    executeSql(SITE_A, createGWS);

    // Insert the same row on A.
    executeSql(SITE_A,
        "insert into EMP.PARTITIONED_TABLE values (1, 'First', 'A714')");
    sqlExecuteVerify(SITE_A,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1",
        goldenFile, "id7", true, false);

    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, -1)");

    // verify
    sqlExecuteVerify(SITE_B,
        "select DESCRIPTION from EMP.PARTITIONED_TABLE WHERE ID = 1",
        goldenFile, "id11", true, false);

    executeSql(SITE_B, "CALL SYS.REMOVE_GATEWAY_CONFLICT_RESOLVER()");

    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('MySender')");
  }

  public void testSetGatewayFKChecks() throws Exception {

    String goldenFile = TestUtil.getResourcesDir()
        + "/lib/GemFireXDGatewayDUnit.xml";

    startSites(2);

    final String createParentTable = "create table trade.parent_customers "
        + "(cid int not null, " + "cust_name varchar(100), "
        + "addr varchar(100), " + "primary key (cid)) "
        + "partition by column(cid) redundancy 1" + "server groups (sgSender) "
        + "enable concurrency checks";

    // Create table with concurrency checks enabled
    executeSql(SITE_A, createParentTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createParentTable + getSQLSuffixClause());

    final String createChildTable = "create table trade.customers_status"
        + "(id int not null, "
        + "cust_id int not null, "
        + "status varchar(100) not null, "
        + "primary key (id),"
        + "constraint cust_fk foreign key (cust_id) references trade.parent_customers (cid) on delete restrict"
        + ") " + "partition by column(id) redundancy 1 "
        + "server groups (sgSender) " + "enable concurrency checks";

    executeSql(SITE_A, createChildTable + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_B, createChildTable + getSQLSuffixClause());

    // Create receiver on B
    final String createGWR = "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)";
    executeSql(SITE_B, createGWR);

    executeSql(SITE_B, "CALL SYS.SET_GATEWAY_FK_CHECKS('FALSE')");

    // Create sender on A
    final String createGWS = "CREATE gatewaysender MySender (remotedsid 2 isparallel true ) server groups (sg1)";
    executeSql(SITE_A, createGWS);

    addExpectedException(new int[] {}, new int[] { 5, 6, 7, 8 },
        new Object[] { SQLException.class, EntryExistsException.class,
            BatchException70.class });

    // Insert in correct order on site A
    executeSql(SITE_A,
        "insert into trade.parent_customers  values (1, 'custName1', 'addr1')");

    executeSql(SITE_A,
        "insert into trade.parent_customers  values (2, 'custName1', 'addr1')");

    executeSql(SITE_A,
        "insert into trade.customers_status  values (100, 1, 'active')");

    executeSql(SITE_A,
        "insert into trade.customers_status  values (200, 2, 'inactive')");

    // waitForTheQueueToGetEmpty
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, 0)");

    sqlExecuteVerify(SITE_B,
        "select STATUS from trade.customers_status where id = 100", goldenFile,
        "id13", true, false);

    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('MySender')");

  }

  public static class CustomResolver implements GatewayConflictResolver {

    private int index;

    private String value;

    private boolean skipInsert;

    private boolean skipUpdate;

    @Override
    public void init(String params) {
      if (params != null) {
        String[] p = params.split(",");
        assertEquals(4, p.length);
        index = new Integer(p[0]).intValue();
        value = p[1];
        skipInsert = new Boolean(p[2]).booleanValue();
        skipUpdate = new Boolean(p[3]).booleanValue();
      }
    }

    @Override
    public void onEvent(GatewayEvent event, GatewayConflictHelper helper) {
      LogWriterI18n logger = Misc.getGemFireCache().getLoggerI18n();
      try {
        GatewayEvent.GatewayEventType eventType = event.getType();
        if (eventType == null) {
          throw new Exception("Event type is null");
        }
        if (eventType.equals(GatewayEventType.INSERT) && skipInsert) {
          helper.disallowEvent();
          return;
        }
        if (event.getType().equals(GatewayEventType.UPDATE) && skipUpdate) {
          helper.disallowEvent();
          return;
        }
        helper.setColumnValue(index, value);
      } catch (Exception e) {
        helper.disallowEvent();
        logger.fine("Exception setting column" + e.getMessage(), e);
        Assert.fail("Exception setting column" + e.getMessage());
      }
    }

  }
  
  public static class CustomResolver_API implements GatewayConflictResolver {
    @Override
    public void init(String params) {
    }

    @Override
    public void onEvent(GatewayEvent event, GatewayConflictHelper helper) {
      LogWriterI18n logger = Misc.getGemFireCache().getLoggerI18n();
      try {
        GatewayEvent.GatewayEventType eventType = event.getType();
        
        logger.fine("GatewayEvent:"+event);
        ResultSet oldRow = event.getOldRow();
        logger.fine("OLD ROW:");
        ResultSetMetaData metadata = oldRow.getMetaData();
        int numCols = metadata.getColumnCount();
        logger.fine("ColCount=" + numCols);
        //while(oldRow.next()) 
        /*
        {
          for(int i = 1; i <= numCols; i++) {
            logger.fine("col=" + i + oldRow.getObject(i));
          }
        }*/
        
        assertTrue(metadata.getColumnName(1).equalsIgnoreCase("ID"));
        assertTrue(metadata.getColumnName(2).equalsIgnoreCase("DESCRIPTION"));
        assertTrue(metadata.getColumnName(3).equalsIgnoreCase("ADDRESS"));
        assertTrue(metadata.getColumnName(4).equalsIgnoreCase("CITY"));

        assertTrue(metadata.getColumnTypeName(1).equalsIgnoreCase("INTEGER"));
        assertTrue(metadata.getColumnTypeName(2).equalsIgnoreCase("VARCHAR"));
        assertTrue(metadata.getColumnTypeName(3).equalsIgnoreCase("VARCHAR"));
        assertTrue(metadata.getColumnTypeName(4).equalsIgnoreCase("VARCHAR"));
        
        assertEquals(1, oldRow.getInt(1));
        assertEquals("First", oldRow.getString(2));
        assertEquals("A714", oldRow.getString(3));
        assertEquals("city1", oldRow.getString(4));
        
        int[] modCols = event.getModifiedColumns();
        assertTrue(modCols != null);
        assertTrue(modCols.length == 1);
        assertEquals(4, modCols[0]);

        ResultSet newRow = event.getNewRow();
        logger.fine("NEW ROW:"); 
        ResultSetMetaData m = newRow.getMetaData();
        numCols = m.getColumnCount();
        logger.fine("ColCount=" + numCols);
        //while(newRow.next()) 
        /*
        {
          for(int i = 1; i <= numCols; i++) {
            logger.fine("col=" + i + newRow.getObject(i));
          }
        }*/
        
        assertEquals("city2",newRow.getString(1));
        
      } catch (Exception e) {
        helper.disallowEvent();
        logger.fine("Exception setting column" + e.getMessage(), e);
        Assert.fail("Exception setting column" + e.getMessage());
      }
    }

  }

  public void installCustomResolverDirectly(String site,
      int changedColumnIndex, String changedColumnValue, boolean skipInsert,
      boolean skipUpdate) throws Exception {
    // Set the custom resolver
    if (site.equals(SITE_A)) {
      for (int i = 1; i <= 4; i++) {
        serverExecute(
            i,
            setCustomResolver(changedColumnIndex, changedColumnValue,
                skipInsert, skipUpdate));
      }
    }
    if (site.equals(SITE_B)) {
      for (int i = 5; i <= 8; i++) {
        serverExecute(
            i,
            setCustomResolver(changedColumnIndex, changedColumnValue,
                skipInsert, skipUpdate));
      }
    }
  }

  public static Runnable setCustomResolver(int columnIndex, String columnValue,
      boolean skipInsertFlag, boolean skipUpdateFlag) {
    final int index = columnIndex;
    final String value = columnValue;
    final boolean skipInsert = skipInsertFlag;
    final boolean skipUpdate = skipUpdateFlag;
    SerializableRunnable senderConf = new SerializableRunnable(
        "Set Custom Resolver") {
      private static final long serialVersionUID = 1L;

      @Override
      public void run() throws CacheException {
        GatewayConflictResolver resolver = new GatewayConflictResolver() {
          @Override
          public void onEvent(GatewayEvent event, GatewayConflictHelper helper) {
            LogWriterI18n logger = Misc.getGemFireCache().getLoggerI18n();
            try {
              GatewayEvent.GatewayEventType eventType = event.getType();
              if (eventType == null) {
                throw new Exception("Event type is null");
              }
              if (eventType.equals(GatewayEventType.INSERT) && skipInsert) {
                helper.disallowEvent();
                return;
              }
              if (event.getType().equals(GatewayEventType.UPDATE) && skipUpdate) {
                helper.disallowEvent();
                return;
              }
              helper.setColumnValue(index, value);
            } catch (Exception e) {
              helper.disallowEvent();
              logger.fine("Exception setting column" + e.getMessage(), e);
            }
          }

          @Override
          public void init(String params) {
          }
        };
        GatewayConflictResolverWrapper resolverWrapper = new GatewayConflictResolverWrapper(
            resolver);
        GemFireCacheImpl.getExisting().setGatewayConflictResolver(
            resolverWrapper);
      }
    };
    return senderConf;
  }

  public void verifyActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled(
      boolean isParallel, boolean randomOps) throws Exception {
    
    logConfig(isParallel, false, false, true, true);
    
    startSites(2);
    
    if(randomOps) {
      addExpectedException(new String[] { SITE_A, SITE_B },
          new Object[] { //PRLocallyDestroyedException.class, // TODO: Review this
            EntryNotFoundException.class,
            EntryExistsException.class
          });
      addExpectedException(new String[] {SITE_A, SITE_B}, new String[]{"Foreign key constraint violation"});
    } else {
      addExpectedException(new String[] { SITE_A, SITE_B },
          new Object[] { //PRLocallyDestroyedException.class
          });
    }
    
    final String createParent = "create table emp.parent_table (ID int , "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, " + "CITY varchar(200) not null,"
        + "primary key (ID)) "
        + "partition by column(ADDRESS)  redundancy 1 server groups (sg1)";

    final String createChild = "create table emp.child_table(ID int, "
        + "PARENT_REF int,"
        + "CHILD_DESCRIPTION varchar(1024) not null,"
        + "CHILD_ADDRESS varchar(200) not null,"
        + "constraint parent_fk foreign key (parent_ref) references emp.parent_table (id) on delete restrict,"
        + "primary key(ID) )"
        + " partition by column(CHILD_ADDRESS) redundancy 1 server groups(sg1)";

    executeSql(SITE_A, createParent + " GatewaySender (MySender)" + getSQLSuffixClause());
    executeSql(SITE_A, createChild + " GatewaySender (MySender)" + getSQLSuffixClause());

    executeSql(SITE_B, createParent + getSQLSuffixClause());
    executeSql(SITE_B, createChild + getSQLSuffixClause());

    // Set up serial sender and receiver
    executeSql(SITE_B,
        "create gatewayreceiver myrcvr(bindaddress 'localhost') server groups(sgSender)");
    executeSql(
        SITE_A,
        "create gatewaysender MySender (remotedsid 2 isparallel " +
        (isParallel ? "true" : "false") + 
            " ) server groups (sg1)");

    // Relax FK checks for gateway events on B
    executeSql(SITE_B, "CALL SYS.SET_GATEWAY_FK_CHECKS('FALSE')");

    if(randomOps) {
      executeOnSite(SITE_A, doRandomDMLOpsOnParentChildTables());
    } else {
      executeOnSite(SITE_A, doInsertsIntoParentChildTables());
    }
  
    // Wait for queue flush
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('MYSENDER', 0, -1)");

    // TODO: Review queue flushing.  It seems to move ahead without draining completely.
    //Thread.currentThread().sleep(2000);
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.parent_table"));
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.child_table"));
    
    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('MYSENDER')");
  }

  public void verifyActiveActive_TablesWithoutFKs_ConcurrencyChecksEnabled(boolean isParallel)
      throws Exception {
    
    logConfig(isParallel, true, true, false, false);
    
    startTwoSites();
    
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { ConcurrentCacheModificationException.class,
            EntryExistsException.class
            //,PRLocallyDestroyedException.class // TODO: Review this
            ,FunctionExecutionException.class
            });  

    final String createTableOne = "create table emp.table_one (ID int , "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, " + "CITY varchar(200) not null,"
        + "primary key (ID)) "
        + "partition by column(ADDRESS)  redundancy 1 server groups (sg1)";

    final String createTableTwo = "create table emp.table_two(ID int, "
        + "PARENT_REF int,"
        + "CHILD_DESCRIPTION varchar(1024) not null,"
        + "CHILD_ADDRESS varchar(200) not null,"
        + "primary key(ID) )"
        + " partition by column(CHILD_ADDRESS) redundancy 1 server groups(sg1)";

    String enableConcurrencyChecks = " enable concurrency checks";
    executeSql(SITE_A, createTableOne + " GatewaySender (siteASender)" + enableConcurrencyChecks + getSQLSuffixClause());
    executeSql(SITE_A, createTableTwo + " GatewaySender (siteASender)" + enableConcurrencyChecks + getSQLSuffixClause());

    executeSql(SITE_B, createTableOne + " GatewaySender (siteBSender)" + enableConcurrencyChecks + getSQLSuffixClause());
    executeSql(SITE_B, createTableTwo + " GatewaySender (siteBSender)" + enableConcurrencyChecks + getSQLSuffixClause());

    // Set up serial sender and receiver
    executeSql(SITE_B,
        "create gatewayreceiver siteBReceiver(bindaddress 'localhost') server groups(sgSender)");
    
    executeSql(
        SITE_A,
        "create gatewaysender siteASender (remotedsid 2 " +
        "isparallel " +
        (isParallel ? "true" : "false ") +
        ") server groups (sg1)");

    // Set up serial sender and receiver
    executeSql(SITE_A,
        "create gatewayreceiver siteAReceiver(bindaddress 'localhost') server groups(sgSender)");
    
    executeSql(
        SITE_B,
        "create gatewaysender siteBSender (remotedsid 1 " +
        "isparallel " +
        (isParallel ? "true ": "false ") +
        ") server groups (sg1)");
    
    executeOnSitesAsync(new String[]{SITE_A, SITE_B}, doInsertsIntoUnrelatedTables());
    //executeOnSite(SITE_A, doInsertsIntoUnrelatedTables());
    
    // Wait for queue flush
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEASENDER', 0, -1)");
    executeSql(SITE_B, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEBSENDER', 0, -1)");

    // TODO: Review queue flushing.  It seems to move ahead without draining completely.
    //Thread.currentThread().sleep(2000);
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.table_one"));
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.table_two"));
    
    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('SITEASENDER')");
    executeSql(SITE_B, "CALL SYS.STOP_GATEWAYSENDER('SITEBSENDER')");
  }  

  public void verifyActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled(
      boolean isParallel, boolean randomOps) throws Exception {
    
    logConfig(isParallel, true, true, true, true);
    
    startTwoSites();

    if(randomOps) {
      addExpectedException(new String[] { SITE_A, SITE_B },
          new Object[] { //PRLocallyDestroyedException.class, // TODO: Review this
            EntryNotFoundException.class,
            EntryExistsException.class,
            ConcurrentCacheModificationException.class,
            FunctionExecutionException.class
          });
      // Add variations of exceptions caught and logged in the GatewayReceiverCommand
      addExpectedException(new String[] {SITE_A, SITE_B}, new String[]{"Foreign key constraint violation",
          "The statement was aborted because it would have caused a duplicate key value",
          "Failed to create or update entry"});
    } else {
      addExpectedException(new String[] { SITE_A, SITE_B },
          new Object[] { ConcurrentCacheModificationException.class,
              EntryExistsException.class,
              //PRLocallyDestroyedException.class, // TODO: Review this
              FunctionExecutionException.class
              });
    }
    
    
    final String createParent = "create table emp.parent_table (ID int , "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, " + "CITY varchar(200) not null,"
        + "primary key (ID)) "
        + "partition by column(ADDRESS)  redundancy 1 server groups (sg1)";

    final String createChild = "create table emp.child_table(ID int, "
        + "PARENT_REF int,"
        + "CHILD_DESCRIPTION varchar(1024) not null,"
        + "CHILD_ADDRESS varchar(200) not null,"
        + "constraint parent_fk foreign key (parent_ref) references emp.parent_table (id) on delete restrict,"
        + "primary key(ID) )"
        + " partition by column(CHILD_ADDRESS) redundancy 1 server groups(sg1)";

    String enableConcurrencyChecks = " enable concurrency checks";
    executeSql(SITE_A, createParent + " GatewaySender (siteASender)" + enableConcurrencyChecks + getSQLSuffixClause());
    executeSql(SITE_A, createChild + " GatewaySender (siteASender)" + enableConcurrencyChecks + getSQLSuffixClause());

    executeSql(SITE_B, createParent + " GatewaySender (siteBSender)" + enableConcurrencyChecks + getSQLSuffixClause());
    executeSql(SITE_B, createChild + " GatewaySender (siteBSender)" + enableConcurrencyChecks + getSQLSuffixClause());

    // Set up serial sender and receiver
    executeSql(SITE_B,
        "create gatewayreceiver siteBReceiver(bindaddress 'localhost') server groups(sgSender)");

    executeSql(
        SITE_A,
        "create gatewaysender siteASender (remotedsid 2 " +
        "isparallel " +
        (isParallel ? "true" : "false ") +
        ") server groups (sg1)");
    
    // Set up serial sender and receiver
    executeSql(SITE_A,
        "create gatewayreceiver siteAReceiver(bindaddress 'localhost') server groups(sgSender)");
    
    executeSql(
        SITE_B,
        "create gatewaysender siteBSender (remotedsid 1 " +
        "isparallel " +
        (isParallel ? "true " : "false ") +
        ") server groups (sg1)");
    
    
    // Relax FK checks for gateway events on B
    executeSql(SITE_A, "CALL SYS.SET_GATEWAY_FK_CHECKS('FALSE')");
    executeSql(SITE_B, "CALL SYS.SET_GATEWAY_FK_CHECKS('FALSE')");

    if(randomOps) {
      executeOnSitesAsync(new String[]{SITE_A, SITE_B}, doRandomDMLOpsOnParentChildTables());
    } else {
      executeOnSitesAsync(new String[]{SITE_A, SITE_B}, doInsertsIntoParentChildTables());
    }

    // Wait for queue flush
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEASENDER', 0, -1)");
    executeSql(SITE_B, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEBSENDER', 0, -1)");

    // TODO: Review queue flushing.  It seems to move ahead without draining completely.
    //Thread.currentThread().sleep(2000);
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.parent_table"));
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.child_table"));

    // Stop the senders explicity so that doCleanUpOnAll2 doesn't insert a
    // suspect string when dropping the senders
    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('SITEASENDER')");
    executeSql(SITE_B, "CALL SYS.STOP_GATEWAYSENDER('SITEBSENDER')");
  }
  

  public Runnable doInsertsIntoParentChildTables() {
    SerializableRunnable runnable = new SerializableRunnable(
        "doInsertsIntoParentChildTables") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.getConnection();
          Statement st = conn.createStatement();
          for (int i = 0; i < 50; i++) {
            st.execute("insert into emp.parent_table values (" + i + ",'Desc"
                + i + "','Addr" + i + "','city" + i + "')");
            st.execute("insert into emp.child_table values(" + i * 100 + ","
                + i + ",'childDesc" + i + "','childAddr" + i + "')");
          }          
          st.close();
          conn.close();
        } catch (Exception e) {
          getLogWriter().info("doInsertsIntoParentChildTables:" + e.getMessage());
        }
      }
    };
    return runnable;
  }
  
  public Runnable doDeletes() {
    SerializableRunnable runnable = new SerializableRunnable(
        "doDeletes") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.getConnection();
          Statement st = conn.createStatement();
          st.execute("delete from emp.child_table where parent_ref = 2");
          st.execute("delete from emp.child_table where id = 300");
          st.close();
          conn.close();
        } catch (Exception e) {
          getLogWriter().info("doInsertsIntoParentChildTables:" + e.getMessage());
        }
      }
    };
    return runnable;
  }
  
  public Runnable doRandomDMLOpsOnParentChildTables() {
    SerializableRunnable runnable = new SerializableRunnable(
        "doRandomDMLOpsOnParentChildTables") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.getConnection();
          Statement st = conn.createStatement();
          
          Random randomOp = new Random();
          Random randomUpdate = new Random();
          int i = 0;
          
          for(int opCount = 0; opCount < 100; opCount++) {
            int next = randomOp.nextInt();
            int op = next % 3;
            if(op == 0) {
              // INSERT
              String insertP = "insert into emp.parent_table values (" + i + ",'Desc"
                  + i + "','Addr" + i + "','city" + i + "')";
              getLogWriter().info("RandomDML:"+insertP);
              st.execute(insertP);

              String insertC = "insert into emp.child_table values(" + i * 100 + ","
                  + i + ",'childDesc" + i + "','childAddr" + i + "')";
              getLogWriter().info("RandomDML:"+insertC);
              st.execute(insertC);
              
              ++i;
            } else if(op == 1) {
              if(i < 10) {
                // Let some inserts only happen before we do any updates or deletes
                continue;
              }
              // UPDATE
              int nextUpdateOp = randomUpdate.nextInt() % 2;
              String update = null;
              if(nextUpdateOp == 0) {
                update = "update emp.parent_table set city = 'changedCity" + (i-1) + "' where id = " + (i-1); 
              } else {
                //st.execute("update emp.child_table set child_address = 'changedChildAddr" + i + "' where id = " + (i-1));
                update = "update emp.child_table set parent_ref = "+(i-2) + " where id = " + (i-1)*100;
              }
              getLogWriter().info("RandomDML:" + update);
              st.execute(update);
              
            } else {
              if(i < 10) {
                // Let some inserts only happen before we do any updates or deletes
                continue;
              }
              // DELETE
              String deleteC = "delete from emp.child_table where parent_ref = " + (i-1);
              getLogWriter().info("RandomDML:"+ deleteC);
              st.execute(deleteC);
              
              String deleteP = "delete from emp.parent_table where id = " + (i-1);
              getLogWriter().info("RandomDML:"+deleteP);
              st.execute(deleteP);
            }
          }
          st.close();
          conn.close();
        } catch (Exception e) {
          getLogWriter().info("doRandomDMLOpsOnParentChildTables:" + e.getMessage());
        }
      }
    };
    return runnable;
  }
  
  public Runnable doUpdate() {
    SerializableRunnable runnable = new SerializableRunnable(
        "doUpdate") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.getConnection();
          Statement st = conn.createStatement();
          st.execute("update emp.table_one set city = 'newcity' where id = 1");
          st.close();
          conn.close();
        } catch (Exception e) {
          getLogWriter().info("doUpdate:" + e.getMessage());
        }
      }
    };
    return runnable;
  }
  public Runnable doDelete() {
    SerializableRunnable runnable = new SerializableRunnable(
        "doUpdate") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.getConnection();
          Statement st = conn.createStatement();
          st.execute("delete from emp.table_one where id = 1");
          st.close();
          conn.close();
        } catch (Exception e) {
          getLogWriter().info("doUpdate:" + e.getMessage());
        }
      }
    };
    return runnable;
  }

  public Runnable doInserts() {
    SerializableRunnable runnable = new SerializableRunnable(
        "doInsertsIntoUnrelatedTables") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.getConnection();
          Statement st = conn.createStatement();
          for (int i = 0; i < 2; i++) {
            st.execute("insert into emp.table_one values (" + i + ",'oneDesc"
                + i + "','oneAddr" + i + "','city" + i + "')");
            st.execute("insert into emp.table_two values(" + i * 100 + ","
                + i + ",'twoDesc" + i + "','twoAddr" + i + "')");
          }          
          st.close();
          conn.close();
        } catch (Exception e) {
          getLogWriter().info("doInsertsIntoUnrelatedTables:" +e.getMessage());
        }
      }
    };
    return runnable;
  }
  
  public Runnable doInsertsIntoUnrelatedTables() {
    SerializableRunnable runnable = new SerializableRunnable(
        "doInsertsIntoUnrelatedTables") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.getConnection();
          Statement st = conn.createStatement();
          for (int i = 0; i < 50; i++) {
            st.execute("insert into emp.table_one values (" + i + ",'oneDesc"
                + i + "','oneAddr" + i + "','city" + i + "')");
            st.execute("insert into emp.table_two values(" + i * 100 + ","
                + i + ",'twoDesc" + i + "','twoAddr" + i + "')");
          }          
          st.close();
          conn.close();
        } catch (Exception e) {
          getLogWriter().info("doInsertsIntoUnrelatedTables:" +e.getMessage());
        }
      }
    };
    return runnable;
  }

  protected void logConfig(boolean isParallel, boolean isActiveActive,
      boolean concurrencyChecksEnabled, boolean tablesHaveFKs,
      boolean fkChecksRelaxed) {
    getLogWriter().info("TEST CONFIG:[" + ( isParallel ? "PARALLEL" : "SERIAL") + "-"
                                      + (isActiveActive ? "AA" : "AP") + "-" + 
                                      "concurrency checks"
                                      + (concurrencyChecksEnabled ? " enabled" : " disabled") + "-"
                                        + (tablesHaveFKs ? "FKs" : "No FKs") + "-"
                                      + (fkChecksRelaxed ? "FK relaxed" : "FK checked" ) + "]"); 
  }  
  
  /*
  * SERIAL WAN Test scenarios:
  *    Active-Passive
  *    1. - DML as string, tables with no FKs, no concurrency checks --> GFE case
  *    2. - DML as string, tables with FKs, no concurrency checks, FK checked  --> expected inconsistency in case of HA.  Current state of things.  Low priority test
  *    3. - DML as events, tables with no FKs, no concurrency checks --> expected to be consistent.  No FK violations since there no FKs.  Low priority test.
  *    4. - DML as events, tables with FKs, FK checked --> Update parent and child from different threads.  Expect FK violations and inconsistency.  Rare scenario. Low priority test.
  *    5. - DML as events, tables with FKs, FK relaxed --> Expect eventual consistency.  Test for consistency and integrity at the end.  IMPORTANT 
  *    Active-Active
  *    6. - DML as events, tables with no FKs, no concurrency checks --> GFE case. High rate of conflicts.  Expect inconsistency at the end.  Low priority test.
  *    7. - DML as events, tables with no FKs, concurrency checks enabled --> Expect consistency.  IMPORTANT.
  *    8. - DML as events, tables with FKs, FK checked, concurrency checks enabled --> Low consistency because of FKs.  IMPORTANT.
  *    9. - DML as events, tables with FKs, FK relaxed, concurrency checks enabled --> Expect consistency.  IMPORTANT.
  
  /**
   * SERIAL : Case-5
   * Active-Passive
   * Tables have FKs
   * FK checks relaxed
   * 
   */
  // [sjigyasu] Disable this if the hardcoded bulk-DML-string flag is turned on in CreateGatewaySenderConstantAction
  public void testSerial_ActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled()
      throws Exception {
    verifyActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled(false, false);
  }
  
  /*
   * Test how delete-update conflicts are resolved.
   */
  public void testDeleteUpdateConflict() throws Exception{
    boolean isParallel = true;
    logConfig(isParallel, true, true, false, false);
    
    startTwoSites();
    
    addExpectedException(new String[] { SITE_A, SITE_B },
        new Object[] { ConcurrentCacheModificationException.class,
            EntryExistsException.class,
            EntryNotFoundException.class
            //,PRLocallyDestroyedException.class // TODO: Review this
            ,FunctionExecutionException.class
            });  

    final String createTableOne = "create table emp.table_one (ID int , "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, " + "CITY varchar(200) not null,"
        + "primary key (ID)) "
        + "partition by column(ADDRESS)  redundancy 1 server groups (sg1)";

    final String createTableTwo = "create table emp.table_two(ID int, "
        + "PARENT_REF int,"
        + "CHILD_DESCRIPTION varchar(1024) not null,"
        + "CHILD_ADDRESS varchar(200) not null,"
        + "primary key(ID) )"
        + " partition by column(CHILD_ADDRESS) redundancy 1 server groups(sg1)";

    String enableConcurrencyChecks = " enable concurrency checks";
    executeSql(SITE_A, createTableOne + " GatewaySender (siteASender)" + enableConcurrencyChecks + getSQLSuffixClause());
    executeSql(SITE_A, createTableTwo + " GatewaySender (siteASender)" + enableConcurrencyChecks + getSQLSuffixClause());

    executeSql(SITE_B, createTableOne + " GatewaySender (siteBSender)" + enableConcurrencyChecks + getSQLSuffixClause());
    executeSql(SITE_B, createTableTwo + " GatewaySender (siteBSender)" + enableConcurrencyChecks + getSQLSuffixClause());

    // Set up serial sender and receiver
    executeSql(SITE_B,
        "create gatewayreceiver siteBReceiver(bindaddress 'localhost') server groups(sgSender)");
    
    executeSql(
        SITE_A,
        "create gatewaysender siteASender (remotedsid 2 " +
        "isparallel " +
        (isParallel ? "true" : "false ") +
        ") server groups (sg1)");

    // Set up serial sender and receiver
    executeSql(SITE_A,
        "create gatewayreceiver siteAReceiver(bindaddress 'localhost') server groups(sgSender)");
    
    executeSql(
        SITE_B,
        "create gatewaysender siteBSender (remotedsid 1 " +
        "isparallel " +
        (isParallel ? "true ": "false ") +
        ") server groups (sg1)");
    
    executeOnSitesAsync(new String[]{SITE_A, SITE_B}, doInserts());
    
    // Wait for queue flush
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEASENDER', 0, -1)");
    executeSql(SITE_B, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEBSENDER', 0, -1)");
    
    //executeOnSite(SITE_A, doInsertsIntoUnrelatedTables());
    executeOnSite(SITE_A, doDelete());
    executeOnSite(SITE_B, doUpdate());
    
    // Wait for queue flush
    executeSql(SITE_A, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEASENDER', 0, -1)");
    executeSql(SITE_B, "call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('SITEBSENDER', 0, -1)");

    // TODO: Review queue flushing.  It seems to move ahead without draining completely.
    //Thread.currentThread().sleep(2000);
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.table_one"));
    assertTrue(compareSites(SITE_A, SITE_B, "select * from emp.table_two"));
    
    executeSql(SITE_A, "CALL SYS.STOP_GATEWAYSENDER('SITEASENDER')");
    executeSql(SITE_B, "CALL SYS.STOP_GATEWAYSENDER('SITEBSENDER')");
    
  }
  
  
  // [sjigyasu] Disable this if the hardcoded bulk-DML-string flag is turned on in CreateGatewaySenderConstantAction
  public void testRandomOps_Serial_ActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled()
      throws Exception {
    verifyActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled(false, true);
  }

  /*
   * 7. - DML as events, tables with no FKs, concurrency checks enabled --> Expect consistency. 
   */
  // [sjigyasu] Disable this if the hardcoded bulk-DML-string flag is turned on in CreateGatewaySenderConstantAction
  public void testSerial_ActiveActive_TablesWithoutFKs_ConcurrencyChecksEnabled()
      throws Exception {
    verifyActiveActive_TablesWithoutFKs_ConcurrencyChecksEnabled(false);
  }

  
  /**
   * SERIAL : Case-9
   * DML as events, tables with FKs, FK relaxed, concurrency checks enabled --> Expect consistency.
   * 
   */
  // [sjigyasu] Disable if the hardcoded bulk-DML-string flag is turned on in CreateGatewaySenderConstantAction
  public void testSerial_ActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled()
      throws Exception {
    verifyActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled(false, false);
  }

  // [sjigyasu] Disable if the hardcoded bulk-DML-string flag is turned on in CreateGatewaySenderConstantAction
  public void DISABLED_Bug_48786_testRandomOps_Serial_ActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled()
      throws Exception {
    verifyActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled(false, true);
  }
  
  
  /*
  * PARALLEL WAN Test scenarios
  *    Active-Passive
  *    1. - DML as events, tables with no FKs, no concurrency checks --> GFE case.  Expect consistency.  Test for consistency at the end. No check for integrity required since there are no FKs.
  *    2. - DML as events, tables with FKs, no concurrency checks, FK checked --> Expect low consistency. Low priority test.
  *    3. - DML as events, tables with FKs, no concurrency checks, FK relaxed --> Expect consistency.  Test for consistency and integrity at the end. IMPORTANT
  *    Active-Active
  *    4. - DML as events, tables with no FKs, no concurrency checks --> GFE case.  High rate of conflicts.  Expect inconsistency at the end.  Low priority test.
  *    5. - DML as events, tables with no FKs, concurrency checks enabled --> GFE case. Expect consistency. IMPORTANT
  *    6. - DML as events, tables with FKs, FK checked, concurrency checks enabled --> Low consistency because of FKs.  IMPORTANT
  *    7. - DML as events, tables with FKs, FK relaxed, concurrency checks enabled --> Expect consistency.  IMPORTANT
*/  
  
  /**
   * PARALLEL: Case-3
   * Active-Passive
   * Tables have FKs
   * FK checks relaxed
   * 
   */
  public void testParallel_ActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled()
      throws Exception {
    verifyActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled(true, false);
  }

  public void testRandomOps_Parallel_ActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled()
      throws Exception {
    verifyActivePassive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksDisabled(true, true);
  }
  
  /**
   * PARALLEL: Case-5
   * Active-Active
   * Tables don't have FKs
   * Concurrency checks enabled.
   * 
   */
  public void testParallel_ActiveActive_TablesWithoutFKs_ConcurrencyChecksEnabled()
      throws Exception {
    verifyActiveActive_TablesWithoutFKs_ConcurrencyChecksEnabled(true);
  }

  /**
   * PARALLEL
   * Case-7 
   * DML as events, tables with FKs, FK relaxed, concurrency checks enabled --> Expect consistency
   */
  public void testParallel_ActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled()
      throws Exception {
    verifyActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled(true, false);
  }

  public void DISABLED_Bug_48786_testRandomOps_Parallel_ActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled()
      throws Exception {
    verifyActiveActive_TablesWithFKs_FKChecksRelaxed_ConcurrencyChecksEnabled(true, true);
  }
  
  
}
