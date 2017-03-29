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
/**
 * 
 */
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireUpdateDistributionActivation;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

/**
 * Tests whether the statementID , connectionID etc are being passed correctly
 * or not
 * 
 * @author Asif
 * @since 6.0
 */
@SuppressWarnings("serial")
public class UpdateStatementDUnit extends DistributedSQLTestBase {
  static volatile boolean isQueryExecutedOnNode = false;

  public UpdateStatementDUnit(String name) {
    super(name);
  }

  public void testBasicNodePruningNoPrimaryKey() throws Exception {
    try {
      // Start one client and three servers
      startServerVMs(3, 0, "SG1");
      startClientVMs(1, 0, null);
      String updateQuery = "Update EMP.TESTTABLE set ADDRESS = 'gemstone' where ID > 0 AND ID < 3";
      // Create a schema with default server groups GemFire extension
      clientSQLExecute(1, "create schema EMP default server groups (SG1)");

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table EMP.TESTTABLE (ID int , "
              + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null ) PARTITION BY RANGE ( ID )"
              + " ( VALUES BETWEEN 0 and 3, VALUES BETWEEN  3 and 6 , VALUES BETWEEN 6 and  +Infinity )"+ getOverflowSuffix());

      // Insert values 1 to 8
      for (int i = 0; i < 8; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
            + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "')");
      }

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = getGfxdQueryObserverIntializerForDataStore();
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      QueryInfo[] qi = new QueryInfo[1];
      NodesPruningHelper.setupObserverOnClient(qi);

      TestUtil.setupConnection();
      EmbedStatement es = (EmbedStatement)TestUtil.jdbcConn.createStatement();

      int n = es.executeUpdate(updateQuery);
      assertEquals(2, n);
      Object[][] routingInfo = new Object[][] { 
          { new Integer(NodesPruningHelper.byrange), new SQLInteger(0),
            Boolean.FALSE, new SQLInteger(3), Boolean.FALSE 
          } 
      };

      Set expectedNodesToUpdate = NodesPruningHelper.getExpectedNodes(
          updateQuery, qi[0], routingInfo, getLogWriter());
      Set allNodes = ((PartitionedRegion)(qi[0].getRegion())).getRegionAdvisor()
          .adviseDataStore();

      SerializableRunnable validateNoQuerySend = getQueryNonExecutionValidator();

      SerializableRunnable validateQuerySend =getQueryExecutionValidator();
      executeOnVMs(expectedNodesToUpdate, validateQuerySend);
      allNodes.removeAll(expectedNodesToUpdate);
      executeOnVMs(allNodes, validateNoQuerySend);
      ResultSet rs = es.executeQuery("Select ID, ADDRESS from EMP.TESTTABLE");
      while (rs.next()) {
        if (rs.getInt(1) > 0 && rs.getInt(1) < 3) {
          assertEquals(rs.getString(2), "gemstone");
        }
        else {
          assertFalse(rs.getString(2).equals("gemstone"));
        }
      }
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
    }
    finally {
      GemFireXDQueryObserverHolder
      .setInstance(new GemFireXDQueryObserverAdapter());
      //invokeInEveryVM(this.getClass(), "reset");         
    }
  }

  public void testBasicNodePruningWithPrimaryKey() throws Exception {
      // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    String updateQuery = "Update EMP.TESTTABLE set ADDRESS = 'gemstone' "
        + "where ID > 0 AND ID < 3";
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null) PARTITION BY RANGE ( ID )"
        + " (VALUES BETWEEN 0 and 3, VALUES BETWEEN 3 and 6, "
        + "VALUES BETWEEN 6 and +Infinity)"+getOverflowSuffix());

    try {
      // Insert values 1 to 8
      for (int i = 0; i < 8; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
            + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "')");
      }

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = getGfxdQueryObserverIntializerForDataStore();
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      QueryInfo[] qi = new QueryInfo[1];
      NodesPruningHelper.setupObserverOnClient(qi);

      TestUtil.setupConnection();
      EmbedStatement es = (EmbedStatement)TestUtil.jdbcConn.createStatement();

      int n = es.executeUpdate(updateQuery);
      assertEquals(2, n);
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.byrange), new SQLInteger(0),
          Boolean.FALSE, new SQLInteger(3), Boolean.FALSE } };

      Set expectedNodesToUpdate = NodesPruningHelper.getExpectedNodes(
          updateQuery, qi[0], routingInfo, getLogWriter());
      Set allNodes = ((PartitionedRegion)qi[0].getRegion()).getRegionAdvisor()
          .adviseDataStore();

      SerializableRunnable validateNoQuerySend = getQueryNonExecutionValidator();

      SerializableRunnable validateQuerySend =  getQueryExecutionValidator();
      this.executeOnVMs(expectedNodesToUpdate, validateQuerySend);      
      allNodes.removeAll(expectedNodesToUpdate);
      this.executeOnVMs(allNodes, validateNoQuerySend);
      
      ResultSet rs = es.executeQuery("Select ID, ADDRESS from EMP.TESTTABLE" );
      while(rs.next()) {
        if(rs.getInt(1) > 0 && rs.getInt(1) < 3) {
          assertEquals(rs.getString(2), "gemstone");
        }else {
          assertFalse(rs.getString(2).equals("gemstone"));
        }
      }
      
      
    }
    finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
        clientSQLExecute(1, "Drop schema EMP restrict");
        //invokeInEveryVM(this.getClass(), "reset");         
    }
  }

  /**
   * Test to make sure an UPDATE on PK lookup with no existing row
   * updates zero rows
   */
  public void testUpdateMissPrimaryKey() throws Exception {
    try {
      // Start one client and three servers
      startVMs(1, 3);
      String updateQuery = "Update EMP.TESTTABLE set ADDRESS = 'gemstone' where ID = ?";
      // Create a schema
      clientSQLExecute(1, "create schema EMP");
      
      // Create the table and insert a row
      clientSQLExecute(
                       1,
                       "create table EMP.TESTTABLE (ID int primary key, "
                       + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null ) PARTITION BY RANGE ( ID )"
                       + " ( VALUES BETWEEN 0 and 3, VALUES BETWEEN  3 and 6 , VALUES BETWEEN 6 and  +Infinity )"+getOverflowSuffix());
      
      // Insert values 1 to 8
      for (int i = 0; i < 8; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
                         + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "')");
      }
      
      // Attach a GemFireXDQueryObserver in the server VM
      
      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = getGfxdQueryObserverIntializerForDataStore();
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      QueryInfo[] qi = new QueryInfo[1];
      NodesPruningHelper.setupObserverOnClient(qi);
      
      TestUtil.setupConnection();
      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);

      // update some ID values, try to cover local as well as remote buckets
      // Expected ReplyException carrying an EntryNotFoundException, shows up
      // in DM Verbose logging
      addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
          EntryNotFoundException.class);

      try {
        for (int i = 42; i <= 420; i += 42) {
          ps.setInt(1, i);
          int n = ps.executeUpdate();
          assertEquals(0, n);
        }
      } finally {
        removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
            EntryNotFoundException.class);
      }
    }
    finally {
      GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }  

  public void testBasicNodePruningWithParameter() throws Exception {
    try {
      // Start one client and three servers
      startServerVMs(3, 0, "SG1");
      startClientVMs(1, 0, null);
      String updateQuery = "Update EMP.TESTTABLE set ADDRESS = ? where ID > ? AND ID < ?";
      // Create a schema with default server groups GemFire extension
      clientSQLExecute(1, "create schema EMP default server groups (SG1)");

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table EMP.TESTTABLE (ID int primary key, "
              + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null ) PARTITION BY RANGE ( ID )"
              + " ( VALUES BETWEEN 0 and 3, VALUES BETWEEN  3 and 6 , VALUES BETWEEN 6 and  +Infinity )");

      // Insert values 1 to 8
      for (int i = 0; i < 8; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
            + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "')");
      }

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = getGfxdQueryObserverIntializerForDataStoreForPrepStmnt();
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      QueryInfo[] qi = new QueryInfo[1];
      Activation actArr[] = new Activation[1];
      NodesPruningHelper.setupObserverOnClient(qi,actArr);

      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn.prepareStatement(updateQuery);
      es.setString(1,"gemstone");
      es.setInt(2,0);
      es.setInt(3,3);
      int n = es.executeUpdate();
      assertEquals(2, n);
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.byrange), new SQLInteger(0),
          Boolean.FALSE, new SQLInteger(3), Boolean.FALSE } };

      Set expectedNodesToUpdate = NodesPruningHelper.getExpectedNodes(
          updateQuery, qi[0], routingInfo, getLogWriter());
      Set allNodes = ((PartitionedRegion)qi[0].getRegion()).getRegionAdvisor()
          .adviseDataStore();

      SerializableRunnable validateNoQuerySend = getQueryNonExecutionValidator();

      SerializableRunnable validateQuerySend =  getQueryExecutionValidator();
      this.executeOnVMs(expectedNodesToUpdate, validateQuerySend);      
      allNodes.removeAll(expectedNodesToUpdate);
      this.executeOnVMs(allNodes, validateNoQuerySend);
      
      EmbedStatement s = (EmbedStatement)TestUtil.jdbcConn.createStatement();
      ResultSet rs = s.executeQuery("Select ID, ADDRESS from EMP.TESTTABLE" );
      while(rs.next()) {
        if(rs.getInt(1) > 0 && rs.getInt(1) < 3) {
          assertEquals(rs.getString(2), "gemstone");
        }else {
          assertFalse(rs.getString(2).equals("gemstone"));
        }
      }
      
      
    }
    finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
        clientSQLExecute(1, "Drop schema EMP restrict");
        //invokeInEveryVM(this.getClass(), "reset");         
    }
  }

  public void testUpdateHavingParameterizedExpression_Bug39646_1()
      throws Exception {
    // Start one client and three servers
    startVMs(1, 3);
    String updateQuery = "Update EMP.TESTTABLE set type = type + ? "
        + "where ID > ? AND ID < ?";
    // Create a schema
    clientSQLExecute(1, "create schema EMP");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) PARTITION BY RANGE ( ID )"
        + " (VALUES BETWEEN 0 and 3, VALUES BETWEEN 3 and 6, "
        + "VALUES BETWEEN 6 and +Infinity)"+getOverflowSuffix());

    try {
      // Insert values 1 to 8
      for (int i = 0; i < 8; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
            + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "'," + (i + 1)
            + ")");
      }

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = getGfxdQueryObserverIntializerForDataStoreForPrepStmnt();
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      QueryInfo[] qi = new QueryInfo[1];
      Activation actArr[] = new Activation[1];
      NodesPruningHelper.setupObserverOnClient(qi,actArr);

      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn.prepareStatement(updateQuery);
      es.setInt(1,1);
      es.setInt(2,0);
      es.setInt(3,3);
      int n = es.executeUpdate();
      assertEquals(2, n);
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.byrange), new SQLInteger(0),
          Boolean.FALSE, new SQLInteger(3), Boolean.FALSE } };

      Set expectedNodesToUpdate = NodesPruningHelper.getExpectedNodes(
          updateQuery, qi[0], routingInfo, getLogWriter());
      Set allNodes = ((PartitionedRegion)qi[0].getRegion()).getRegionAdvisor()
          .adviseDataStore();

      SerializableRunnable validateNoQuerySend = getQueryNonExecutionValidator();

      SerializableRunnable validateQuerySend =  getQueryExecutionValidator();
      this.executeOnVMs(expectedNodesToUpdate, validateQuerySend);      
      allNodes.removeAll(expectedNodesToUpdate);
      this.executeOnVMs(allNodes, validateNoQuerySend);
      
      EmbedStatement s = (EmbedStatement)TestUtil.jdbcConn.createStatement();
      ResultSet rs = s.executeQuery("Select ID, TYPE from EMP.TESTTABLE" );
      while(rs.next()) {
        if(rs.getInt(1) > 0 && rs.getInt(1) < 3) {
          assertEquals(rs.getInt(2), rs.getInt(1)+1);
        }else {
          assertEquals(rs.getInt(2),rs.getInt(1));
        }
      }
      
      
    }
    finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
        clientSQLExecute(1, "Drop schema EMP restrict");
        //invokeInEveryVM(this.getClass(), "reset");         
    }
  }

  public void testUpdateWithoutInsert() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    String updateQuery = "Update EMP.TESTTABLE set type = ? where ID = ? ";
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) "+getOverflowSuffix());

    try {
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 1);
      es.setInt(2, 2);
      int n = es.executeUpdate();
      assertEquals(0, n);

    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testUpdateHavingExpression_Bug39646_2() throws Exception {
    // Start one client and three servers
    startVMs(1, 3);
    try {
      String updateQuery = "Update EMP.TESTTABLE set type = type + 1 where ID > 0 AND ID < 3";
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table EMP.TESTTABLE (ID int primary key, "
              + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) ,type int ) PARTITION BY RANGE ( ID )"
              + " ( VALUES BETWEEN 0 and 3, VALUES BETWEEN  3 and 6 , VALUES BETWEEN 6 and  +Infinity )"+getOverflowSuffix());

      // Insert values 1 to 8
      for (int i = 0; i < 8; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
            + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "',"+(i+1)+")");
      }

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = getGfxdQueryObserverIntializerForDataStore();
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      QueryInfo[] qi = new QueryInfo[1];
      Activation actArr[] = new Activation[1];
      NodesPruningHelper.setupObserverOnClient(qi,actArr);

      TestUtil.setupConnection();
      EmbedStatement es = (EmbedStatement)TestUtil.jdbcConn.createStatement();
      
      int n = es.executeUpdate(updateQuery);
      assertEquals(2, n);
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.byrange), new SQLInteger(0),
          Boolean.FALSE, new SQLInteger(3), Boolean.FALSE } };

      Set expectedNodesToUpdate = NodesPruningHelper.getExpectedNodes(
          updateQuery, qi[0], routingInfo, getLogWriter());
      Set allNodes = ((PartitionedRegion)qi[0].getRegion()).getRegionAdvisor()
          .adviseDataStore();

      SerializableRunnable validateNoQuerySend = getQueryNonExecutionValidator();

      SerializableRunnable validateQuerySend =  getQueryExecutionValidator();
      this.executeOnVMs(expectedNodesToUpdate, validateQuerySend);      
      allNodes.removeAll(expectedNodesToUpdate);
      this.executeOnVMs(allNodes, validateNoQuerySend);
      
      EmbedStatement s = (EmbedStatement)TestUtil.jdbcConn.createStatement();
      ResultSet rs = s.executeQuery("Select ID, TYPE from EMP.TESTTABLE" );
      while(rs.next()) {
        if(rs.getInt(1) > 0 && rs.getInt(1) < 3) {
          assertEquals(rs.getInt(2), rs.getInt(1)+1);
        }else {
          assertEquals(rs.getInt(2),rs.getInt(1));
        }
      }
      
      
    }
    finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
        clientSQLExecute(1, "Drop schema EMP restrict");
        //invokeInEveryVM(this.getClass(), "reset");         
    }
  }
  
  public void testUpdateOnTableHavingNoPrimaryOrPartitionKeyDefined_Bug39921() throws Exception {
    try {
      // Start one client and three servers
      startServerVMs(3, 0, "SG1");
      startClientVMs(1, 0, null);
      String updateQuery = "Update EMP.TESTTABLE set type = 10 where ID > 0 ";
      // Create a schema with default server groups GemFire extension
      clientSQLExecute(1, "create schema EMP default server groups (SG1)");

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table EMP.TESTTABLE (ID int , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024) ,type int ) "+getOverflowSuffix());

      // Insert values 1 to 8
      for (int i = 0; i < 8; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
            + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "',"+(i+1)+")");
      }

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = getGfxdQueryObserverIntializerForDataStore();
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      QueryInfo[] qi = new QueryInfo[1];
      Activation actArr[] = new Activation[1];
      NodesPruningHelper.setupObserverOnClient(qi,actArr);

      TestUtil.setupConnection();
      EmbedStatement es = (EmbedStatement)TestUtil.jdbcConn.createStatement();
      
      int n = es.executeUpdate(updateQuery);
      assertEquals(8, n);
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.allnodes) } };

      Set expectedNodesToUpdate = NodesPruningHelper.getExpectedNodes(
          updateQuery, qi[0], routingInfo, getLogWriter());
      Set allNodes = ((PartitionedRegion)qi[0].getRegion()).getRegionAdvisor()
          .adviseDataStore();

      SerializableRunnable validateNoQuerySend = getQueryNonExecutionValidator();

      SerializableRunnable validateQuerySend =  getQueryExecutionValidator();
      this.executeOnVMs(expectedNodesToUpdate, validateQuerySend);      
      allNodes.removeAll(expectedNodesToUpdate);
      this.executeOnVMs(allNodes, validateNoQuerySend);      
      
    }
    finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
        clientSQLExecute(1, "Drop schema EMP restrict");
        //invokeInEveryVM(this.getClass(), "reset");         
    }
  }
  
  public void testBug40025() throws Exception {
    try {
      // Start one client and one server
      startVMs(1, 3);
      String updateQuery =  "update Child set sector_id2 = ? where id2 = ?";
      

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table INSTRUMENTS (id1 int primary key, sector_id1 int, subsector_id1 int)  replicate");
      clientSQLExecute(
          1,
          "create table Child ( id2 int primary key, sector_id2 int, subsector_id2 int, foreign key (sector_id2) references instruments (id1) ) replicate");
       
      clientSQLExecute(1,"insert into instruments values (1,1,1)");
      clientSQLExecute(1,"insert into Child values (1,1,1)");
      TestUtil.setupConnection();
      final boolean[] executedOnGFEDistributionActvn = new boolean[]{false};

      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation) {
              executedOnGFEDistributionActvn[0] = activation instanceof
                  GemFireUpdateDistributionActivation;
              System.out.println("Type of Activation" + activation);
            }
          });

      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);

      es.setInt(1, 2); 
      es.setInt(2, 1);      
      try {
        es.executeUpdate();
        assertTrue(executedOnGFEDistributionActvn[0]);
        fail("Expected foreign key violation");
      } catch (SQLException sqle) {
        getLogWriter().info("Got exception: ", sqle);
        if (!"23503".equals(sqle.getSQLState())) {
          throw sqle;
        }
        assertTrue("Unexpected exception " + sqle, sqle.getMessage().indexOf(
            "violation of foreign key constraint") != -1);
      }
    } finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table Child ");
        clientSQLExecute(1, "Drop table INSTRUMENTS ");
        //invokeInEveryVM(this.getClass(), "reset");         
    } 
  }
  
  /**
   * The test is very similar to the testBug40025 but has a different
   * update statement which is not put convertible.
   * @throws Exception
   */
  public void testBug40025WithOutUpdatesToPut() throws Exception {
    // Start one client and one server
    startVMs(1, 1);
    // Rahul :this following update will not be put convertible. Other than
    // that this test is exactly similar to the one checked in for
    // this ticket number.
    String updateQuery = "update Child set sector_id2 = ?, "
        + "subsector_id2= subsector_id2-5 where id2 = ?";
    // Create the table and insert a row
    clientSQLExecute(1, "create table INSTRUMENTS (id1 int primary key, "
        + "sector_id1 int, subsector_id1 int)  replicate");
    clientSQLExecute(1, "create table Child ( id2 int primary key, "
        + "sector_id2 int, subsector_id2 int, "
        + "foreign key (sector_id2) references instruments (id1) ) "
        + "replicate");
    try {
      clientSQLExecute(1, "insert into instruments values (1,1,1)");
      clientSQLExecute(1, "insert into Child values (1,1,10)");
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 2);
      es.setInt(2, 1);
      try {
        es.executeUpdate();
        fail("Update should not have occured due to foreign key violation");
      } catch (SQLException sqle) {
        this.getLogWriter().info("Expected exception=" + sqle.getMessage());
        assertTrue(sqle.getMessage().indexOf(
            "violation of foreign key constraint") != -1);
      }
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
      clientSQLExecute(1, "Drop table Child ");
      clientSQLExecute(1, "Drop table INSTRUMENTS ");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug40025_1() throws Exception {
    try {
      // Start one client and one server
      startVMs(1, 3);
      String updateQuery =  "update Child set sector_id2 = ? where id2 = ?";
      

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table INSTRUMENTS (id1 int primary key, sector_id1 int, subsector_id1 int)  partition by primary key");
      clientSQLExecute(
          1,
          "create table Child ( id2 int primary key, sector_id2 int, subsector_id2 int, foreign key (sector_id2) references instruments (id1) ) partition by primary key");
       
      clientSQLExecute(1,"insert into instruments values (1,1,1)");
      clientSQLExecute(1,"insert into Child values (1,1,1)");
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn.prepareStatement(updateQuery);
      es.setInt(1, 2);
      es.setInt(2, 1);
      addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
          FunctionException.class);
      try {
        es.executeUpdate();
        fail("Update should not have occured as "
            + "foreign key violation would occur");
      } catch (SQLException sqle) {
        if (!"23503".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
          FunctionException.class);
    }
    finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table Child ");
        clientSQLExecute(1, "Drop table INSTRUMENTS ");
        //invokeInEveryVM(this.getClass(), "reset");         
    } 
  }

  public void testConstraintExceptionForPartitionedRegionTable_Bug40016_2() throws Exception {
    // Start one client and one server
    startVMs(1, 3);
    try {
      String updateQuery =  "update Child set sector_id2 = ? where subsector_id2 = ?";
      

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table INSTRUMENTS (id1 int primary key, sector_id1 int, subsector_id1 int)  partition by primary key");
      clientSQLExecute(
          1,
          "create table Child ( id2 int primary key, sector_id2 int, subsector_id2 int, foreign key (sector_id2) references instruments (id1) ) partition by primary key");
       
      clientSQLExecute(1,"insert into instruments values (1,1,1)");
      clientSQLExecute(1,"insert into Child values (1,1,1)");
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 2);
      es.setInt(2, 1);
      addExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
          FunctionException.class);
      try {
        es.executeUpdate();
        fail("Update should not have occured as foreign key "
            + "violation would occur");
      } catch (SQLException sqle) {
        if (!"23503".equals(sqle.getSQLState())) {
          throw sqle;
        }
      }
      removeExpectedException(new int[] { 1 }, new int[] { 1, 2, 3 },
          FunctionException.class);
    } finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table Child ");
        clientSQLExecute(1, "Drop table INSTRUMENTS ");
        //invokeInEveryVM(this.getClass(), "reset");         
    }    
  }

  public void testConstraintExceptionForReplicatedRegionTable_Bug40016_1() throws Exception {
      // Start one client and one server
    startVMs(1, 3);
    String updateQuery =  "update Child set sector_id2 = ? where subsector_id2 = ?";

    try {
      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table INSTRUMENTS (id1 int primary key, sector_id1 int, subsector_id1 int)  replicate");
      clientSQLExecute(
          1,
          "create table Child ( id2 int primary key, sector_id2 int, subsector_id2 int, foreign key (sector_id2) references instruments (id1) ) replicate");
       
      clientSQLExecute(1,"insert into instruments values (1,1,1)");
      clientSQLExecute(1,"insert into Child values (1,1,1)");
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn.prepareStatement(updateQuery);
      es.setInt(1, 2); 
      es.setInt(2, 1);
      try {  
        es.executeUpdate();
        fail("Update should not have occured as foreign key violation would occur");
      }catch(SQLException sqle) { 
        this.getLogWriter().info("Expected exception="+sqle.getMessage());
         assertTrue(sqle.getMessage().indexOf("violation of foreign key constraint") != -1);      
      }            
    }
    finally {
        GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter());
        clientSQLExecute(1, "Drop table Child ");
        clientSQLExecute(1, "Drop table INSTRUMENTS ");
        //invokeInEveryVM(this.getClass(), "reset");         
    }    
  }
  
  public void testNotNullCheck_Bug40018_1() throws Exception
  {
    startVMs(1, 3);

    clientSQLExecute(
        1,
        "create table Child ( id2 int primary key, sector_id2 int not null, subsector_id2 int)"+getOverflowSuffix());

    try {
      // Start one client and one server
      String updateQuery = "update Child set sector_id2 = ? where subsector_id2 = ?";     
      clientSQLExecute(1, "insert into Child values (1,1,1)");
      clientSQLExecute(1, "insert into Child values (2,1,1)");
      clientSQLExecute(1, "insert into Child values (3,1,1)");
      TestUtil.setupConnection();
      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);
      ps.setNull(1, Types.INTEGER);
      ps.setInt(2, 1);
      try {
        ps.executeUpdate();
        fail("Update should not have occured as not null column should not get assigned null value");
      }
      catch (SQLException sqle) {
        this.getLogWriter().info("Expected exception=" + sqle.getMessage());
        assertTrue(sqle.getMessage().indexOf("cannot accept a NULL value") != -1);
      }
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");     
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testNotNullCheck_Bug40018_2() throws Exception
  {
    startVMs(1, 3);

    clientSQLExecute(
        1,
        "create table Child ( id2 int primary key, sector_id2 int not null, subsector_id2 int)"+getOverflowSuffix());

    try {
      // Start one client and one server
      String updateQuery = "update Child set subsector_id2 = ?, sector_id2 = sector_id2 + ? where subsector_id2 = ?";     
      clientSQLExecute(1, "insert into Child values (1,1,1)");
      clientSQLExecute(1, "insert into Child values (2,1,1)");
      clientSQLExecute(1, "insert into Child values (3,1,1)");
      TestUtil.setupConnection();
      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);
      ps.setNull(1, Types.INTEGER);
      ps.setInt(2, 2);
      ps.setInt(3, 1);
      int n = ps.executeUpdate();
      assertEquals(3,n);      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");     
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug41985() throws Exception {
    startVMs(1, 3);

    clientSQLExecute(
        1,
        "create table Child ( id2 int primary key, sector_id2 int not null, subsector_id2 int)"+getOverflowSuffix());

    try {
      // Start one client and one server
      String updateQuery = "update Child set subsector_id2 = ?, sector_id2 =  ? where id2 = ?";     
      clientSQLExecute(1, "insert into Child values (1,1,1)");
      clientSQLExecute(1, "insert into Child values (2,1,1)");
      clientSQLExecute(1, "insert into Child values (3,1,1)");
      TestUtil.setupConnection();
      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);
      ps.setNull(1, Types.INTEGER);
      ps.setInt(2, 2);
      ps.setInt(3, 1);
      int n = ps.executeUpdate();
      assertEquals(1,n);      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");     
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }
  
  public void testBug41985_1() throws Exception {
    startVMs(1, 3);

    clientSQLExecute(
        1,
        "create table Child ( id2 int primary key, sector_id2 int not null, subsector_id2 int)"+getOverflowSuffix());

    try {          
     
      TestUtil.setupConnection();
      Statement stmt = TestUtil.jdbcConn.createStatement();
      
      int n = stmt.executeUpdate("insert into Child values (1,1,1)");
      assertEquals(1,n);      
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");     
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testNotNullCheck_Bug40018_3() throws Exception
  {
    startVMs(1, 3);
    clientSQLExecute(
        1,
        "create table Child ( id2 int primary key, sector_id2 int not null, subsector_id2 int not null)"+getOverflowSuffix());

    try {
      String updateQuery = "update Child set subsector_id2 = ?, sector_id2 = sector_id2 + ? where subsector_id2 = ?";     
      clientSQLExecute(1, "insert into Child values (1,1,1)");
      clientSQLExecute(1, "insert into Child values (2,1,1)");
      clientSQLExecute(1, "insert into Child values (3,1,1)");
      TestUtil.setupConnection();
      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);
      ps.setNull(1, Types.INTEGER);
      ps.setInt(2, 2);
      ps.setInt(3, 1);
      try {
        ps.executeUpdate();
        fail("Update should not have occured as not null column should not get assigned null value");
      }
      catch (SQLException sqle) {
        this.getLogWriter().info("Expected exception=" + sqle.getMessage());
        assertTrue(sqle.getMessage().indexOf("cannot accept a NULL value") != -1);
      }
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");     
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testPKUpdateOnNonExistentRowForReplicateTable_Bug42862()
      throws Exception {
    startVMs(1, 3);
    clientSQLExecute(1, "create table Child ( id2 int primary key, sector_id2 "
        + "int not null, subsector_id2 int not null) replicate"
        + getOverflowSuffix());
    try {
      clientSQLExecute(1, "insert into Child values (3,1,1)");
      String updateQuery = "update Child set subsector_id2 = ? where id2 = ?";
      TestUtil.setupConnection();
      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);

      ps.setInt(1, 1);
      ps.setInt(2, 2);

      int num = ps.executeUpdate();
      assertEquals(0, num);
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testPKUpdateOnNonExistentRowForPartitionedTable_Bug42862()
      throws Exception {
    startVMs(1, 3);
    clientSQLExecute(1, "create table Child ( id2 int primary key, sector_id2 "
        + "int not null, subsector_id2 int not null) " + getOverflowSuffix());
    try {
      clientSQLExecute(1, "insert into Child values (3,1,1)");
      String updateQuery = "update Child set subsector_id2 = ? where id2 = ?";
      TestUtil.setupConnection();

      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);

      ps.setInt(1, 1);
      ps.setInt(2, 2);

      int num = ps.executeUpdate();
      assertEquals(0, num);
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testPKUpdateOnExistingRowForPartitionedTable_Bug42862()
      throws Exception {
    startVMs(1, 3);
    clientSQLExecute(1, "create table Child ( id2 int primary key, sector_id2 "
        + "int not null, subsector_id2 int not null) PARTITION BY RANGE "
        + "(id2) (VALUES BETWEEN 0 and 3, VALUES BETWEEN 3 and 6, "
        + "VALUES BETWEEN 6 and +Infinity)" + getOverflowSuffix());
    try {
      clientSQLExecute(1, "insert into Child values (1,1,1)");
      clientSQLExecute(1, "insert into Child values (2,1,1)");
      clientSQLExecute(1, "insert into Child values (3,1,1)");
      String updateQuery = "update Child set subsector_id2 = ? where id2 = ?";
      TestUtil.setupConnection();
      PreparedStatement ps = TestUtil.jdbcConn.prepareStatement(updateQuery);

      ps.setInt(1, 1);
      ps.setInt(2, 2);

      int num = ps.executeUpdate();
      assertEquals(1, num);
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table Child ");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testUpdatePrimarykeyWithExtraAnds() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) " + getOverflowSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "'," + (i + 1)
          + ")");
    }
    try {
      TestUtil.setupConnection();
      int n = TestUtil.jdbcConn.createStatement().executeUpdate("Update EMP.TESTTABLE set type = " +
          "1 where ID = 2 and DESCRIPTION = 'First2'");
      assertEquals(1, n);

    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testUpdatePrimarykeyWithExtraAndsPrepStmt() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    String updateQuery = "Update EMP.TESTTABLE set type = ? where ID = ? and DESCRIPTION = ?";
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) " + getOverflowSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "'," + (i + 1)
          + ")");
    }
    try {
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 1);
      es.setInt(2, 2);
      es.setString(3, "First2");
      int n = es.executeUpdate();
      assertEquals(1, n);

    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testCASUpdatePerf() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    String updateQuery = "Update EMP.TESTTABLE set type = ? where ID = ? and DESCRIPTION = ?";
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) " + getOverflowSuffix());


    try {
      TestUtil.setupConnection();

      PreparedStatement ps = TestUtil.jdbcConn
          .prepareStatement("insert into EMP.TESTTABLE values (?, ?, ?, ?)");
      for (int i = 0; i < 10000; ++i) {
        ps.setInt(1, i);
        ps.setString(2, "descp");
        ps.setString(3, "addr");
        ps.setInt(4, i);
        ps.executeUpdate();
      }

      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 1);
      es.setInt(2, 1);
      es.setString(3, "descp");
      long startTime = System.currentTimeMillis();
      for(int i = 0 ; i < 100 ; i++ ){
        int n = es.executeUpdate();
        assertEquals(1, n);
      }
      long endTime = System.currentTimeMillis();
      getLogWriter().info("UPDATE-PERF-CAS Based= " + (endTime - startTime));

    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");

    }
  }

  public void testNonCASUpdatePerf() throws Exception {
    System.setProperty("snappy.store.disableCASUpdate", "true");
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    String updateQuery = "Update EMP.TESTTABLE set type = ? where ID = ? and DESCRIPTION = ?";
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) " + getOverflowSuffix());


    try {
      TestUtil.setupConnection();

      PreparedStatement ps = TestUtil.jdbcConn
          .prepareStatement("insert into EMP.TESTTABLE values (?, ?, ?, ?)");
      for (int i = 0; i < 10000; ++i) {
        ps.setInt(1, i);
        ps.setString(2, "descp");
        ps.setString(3, "addr");
        ps.setInt(4, i);
        ps.executeUpdate();
      }

      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 1);
      es.setInt(2, 1);
      es.setString(3, "descp");
      long startTime = System.currentTimeMillis();
      for(int i = 0 ; i < 100 ; i++ ){
        int n = es.executeUpdate();
        assertEquals(1, n);
      }
      long endTime = System.currentTimeMillis();
      getLogWriter().info("UPDATE-PERF-Non-CAS = " + (endTime - startTime));
      //Disable CAS Updates


    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
      System.clearProperty("snappy.store.disableCASUpdate");
    }
  }

  public void testPKUpdatePerf() throws Exception {
    System.setProperty("snappy.store.disableCASUpdate", "true");
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    String updateQuery = "Update EMP.TESTTABLE set type = ? where ID = ?";
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) " + getOverflowSuffix());


    try {
      TestUtil.setupConnection();

      PreparedStatement ps = TestUtil.jdbcConn
          .prepareStatement("insert into EMP.TESTTABLE values (?, ?, ?, ?)");
      for (int i = 0; i < 10000; ++i) {
        ps.setInt(1, i);
        ps.setString(2, "descp");
        ps.setString(3, "addr");
        ps.setInt(4, i);
        ps.executeUpdate();
      }

      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);
      es.setInt(1, 1);
      es.setInt(2, 1);
      long startTime = System.currentTimeMillis();
      for(int i = 0 ; i < 100 ; i++ ){
        int n = es.executeUpdate();
        assertEquals(1, n);
      }
      long endTime = System.currentTimeMillis();
      getLogWriter().info("UPDATE-PERF-PKBased= " + (endTime - startTime));
      //Disable CAS Updates


    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
      System.clearProperty("snappy.store.disableCASUpdate");
    }
  }

  public void testInternalQueryCompilation() throws Exception {
    // Start one client and three servers
    startServerVMs(3, 0, "SG1");
    startClientVMs(1, 0, null);
    String updateQuery = "Update EMP.TESTTABLE set type = ? where ID = ? and DESCRIPTION = ?";
    // Create a schema with default server groups GemFire extension
    clientSQLExecute(1, "create schema EMP default server groups (SG1)");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
        + "DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) ,type int ) " + getOverflowSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604" + (i + 1) + "'," + (i + 1)
          + ")");
    }
    try {
      TestUtil.setupConnection();
      EmbedPreparedStatement es = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(updateQuery);

      for(int i = 1 ; i<= 10 ; i++){
        es.setInt(1, i);
        es.setInt(2, 2);
        es.setString(3, "First2");

        int n = es.executeUpdate();
        //assertEquals(1, n);
      }



    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }


  /** ***************************************Helper *************************** */
  
  public static final void reset() {
    isQueryExecutedOnNode = false;
  }

  private void executeOnVMs(Set<DistributedMember> members, SerializableRunnable runnable) {
    Iterator itr = members.iterator();
    while (itr.hasNext()) {
      DistributedMember member = (DistributedMember)itr.next();
      VM vm = this.getHostVMForMember(member);
      vm.invoke(runnable);
    }
  }
  
  private  SerializableRunnable getGfxdQueryObserverIntializerForDataStore() {
    return new SerializableRunnable(
    "Set GemFireXDObserver") {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void beforeQueryExecutionByStatementQueryExecutor(
                    GfxdConnectionWrapper wrapper, EmbedStatement stmt,
                    String query) {
                  isQueryExecutedOnNode = true;
                }
              });
        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
  }
   
  private  SerializableRunnable getGfxdQueryObserverIntializerForDataStoreForPrepStmnt() {
    return new SerializableRunnable(
    "Set GemFireXDObserver") {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void beforeQueryExecutionByPrepStatementQueryExecutor(
                    GfxdConnectionWrapper wrapper,
                    EmbedPreparedStatement pstmt, String query) {
                  isQueryExecutedOnNode = true;
                }
              });
        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
  }
 
  private static SerializableRunnable getQueryExecutionValidator() {
    return new SerializableRunnable("validate Query execution") {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter());
          assertTrue(isQueryExecutedOnNode);
        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
  }

  private static SerializableRunnable getQueryNonExecutionValidator() {
    return new SerializableRunnable("validate no query execution") {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter());
          assertFalse(isQueryExecutedOnNode);
        }
        catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
  }
  

  public String getOverflowSuffix() {
    return  " ";
  }

  @Override
  protected void vmTearDown() throws Exception {
    super.vmTearDown();
    reset();
  }
}
