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
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.distributed.DistributedMember;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

import io.snappydata.test.dunit.VM;

/**
 * Tests the pruning behaviour of distributed queries based on partition
 * resolver, global indexes etc
 *
 * @author Asif
 * @since 6.0
 */
@SuppressWarnings("serial")
public class StatementNodesPruningDUnit extends DistributedSQLTestBase {

  public StatementNodesPruningDUnit(String name) {
    super(name);
  }

  /**
   * Tests node pruning for a query with single condition with inequality
   * operator for range partitioning. All the conditions are parameterized
   */
  public void testMultiConditionWhereClauseWithRangePartitioning()
      throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where "
        + "ID >= 1 and ID <= 3";
    startVMs(1, 3);

    VM dataStore1 = this.serverVMs.get(0);
    VM dataStore2 = this.serverVMs.get(1);
    VM dataStore3 = this.serverVMs.get(2);
    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, primary key (ID))"
        + "PARTITION BY RANGE ( ID )"
        + " ( VALUES BETWEEN 1 and 4, VALUES BETWEEN  4 and 8 )" + getSuffix());

    // Insert values 1 to 7
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604')");
    }
    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];
      setupObservers(new VM[] { dataStore1, dataStore2, dataStore3 }, sqi);

      TestUtil.setupConnection();
      EmbedStatement stmnt = (EmbedStatement)TestUtil.jdbcConn
          .createStatement();

      Set<Integer> rslts = new HashSet<Integer>();
      for (int i = 1; i < 4; ++i) {
        rslts.add(Integer.valueOf(i));
      }

      ResultSet rs = stmnt.executeQuery(query);
      for (int i = 1; i < 4; ++i) {
        assertTrue(rs.next());
        assertTrue(rslts.remove(Integer.valueOf(rs.getInt(1))));
      }
      assertTrue(rslts.isEmpty());
      assertFalse(rs.next());
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.byrange), new SQLInteger(1),
          Boolean.TRUE, new SQLInteger(3), Boolean.FALSE } };
      Set<DistributedMember> expectedPrunedNodes = NodesPruningHelper
          .getExpectedNodes(query, sqi[0], routingInfo, getLogWriter());
      Set<DistributedMember> allNodes = NodesPruningHelper.getExpectedNodes(
          query, sqi[0], new Object[][] { { new Integer(
              NodesPruningHelper.allnodes) } }, getLogWriter());
      allNodes.removeAll(expectedPrunedNodes);
      verifyQueryExecution(sqi[0], expectedPrunedNodes, allNodes, 1, 2);
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  /**
   * Tests node pruning for a query with single condition where range
   * partitioning is defined but the partitioning column is an equality which
   * falls outside the range
   */
  public void testRangePartitionBehaviourForPartitioningColumnEqualityOutsideRange()
      throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where "
        + "PartitionColumn = 7";
    startVMs(1, 3);

    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, PartitionColumn int, "
        + "primary key (ID)) PARTITION BY RANGE ( PartitionColumn)"
        + " ( VALUES BETWEEN 0 and 5)" + getSuffix());

    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];

      final Activation[] actArr = new Activation[1];
      NodesPruningHelper.setupObserverOnClient(sqi, actArr);
      TestUtil.setupConnection();
      TestUtil.jdbcConn.prepareStatement(query);
      Set<Object> actualRoutingKeys = new HashSet<Object>();
      actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
      sqi[0].computeNodes(actualRoutingKeys, null, false);
      assertEquals(actualRoutingKeys.size(), 1);
      assertEquals(actualRoutingKeys.iterator().next(), Integer.valueOf(7));
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  /**
   * Tests node pruning for a query with single condition USING lIST
   * where range partitioning is defined
   * but the partitioning column is an equality which falls
   * outside the range & within the range
   */
  public void testRangePartitionBehaviourForPartitioningColumnEqualityListOutsideRange()
      throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where "
        + "PartitionColumn IN (1,2, 7)";
    startVMs(1, 3);

    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, PartitionColumn int, "
        + "primary key (ID)) PARTITION BY RANGE ( PartitionColumn)"
        + " ( VALUES BETWEEN 0 and 5)"+ getSuffix());

    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];

      final Activation[] actArr = new Activation[1];
      NodesPruningHelper.setupObserverOnClient(sqi, actArr);
      TestUtil.setupConnection();
      TestUtil.jdbcConn.prepareStatement(query);
      Set<Object> actualRoutingKeys = new HashSet<Object>();
      actualRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
      sqi[0].computeNodes(actualRoutingKeys, null, false);
      assertEquals(actualRoutingKeys.size(), 2);
      assertTrue(actualRoutingKeys.contains(Integer.valueOf(7)));
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  /**
   * Tests node pruning for a query with single condition where range
   * partitioning is defined but the partitioning column is an equality which
   * falls outside the range
   */
  public void testBug39911() throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where "
        + "PartitionColumn = 7";
    startVMs(1, 3);

    VM dataStore1 = this.serverVMs.get(0);
    VM dataStore2 = this.serverVMs.get(1);
    VM dataStore3 = this.serverVMs.get(2);
    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, PartitionColumn int, "
        + "primary key (ID)) PARTITION BY RANGE ( PartitionColumn)"
        + " ( VALUES BETWEEN 0 and 5)"+ getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604', " + (i + 1) + ")");
    }
    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];
      setupObservers(new VM[] { dataStore1, dataStore2, dataStore3 }, sqi);
      TestUtil.setupConnection();
      EmbedStatement stmnt = (EmbedStatement)TestUtil.jdbcConn
          .createStatement();
      Set<Integer> rslts = new HashSet<Integer>();
      rslts.add(Integer.valueOf(7));
      ResultSet rs = stmnt.executeQuery(query);

      assertTrue(rs.next());
      assertTrue(rslts.remove(Integer.valueOf(rs.getInt(1))));
      assertTrue(rslts.isEmpty());
      assertFalse(rs.next());
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.bycolumn), new SQLInteger(7) } };
      Set<DistributedMember> expectedPrunedNodes = NodesPruningHelper
          .getExpectedNodes(query, sqi[0], routingInfo, getLogWriter());
      Set<DistributedMember> allNodes = NodesPruningHelper.getExpectedNodes(
          query, sqi[0], new Object[][] { { new Integer(
              NodesPruningHelper.allnodes) } }, getLogWriter());
      allNodes.removeAll(expectedPrunedNodes);
      verifyQueryExecution(sqi[0], expectedPrunedNodes, allNodes, 1, 2);
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  /**
   * Tests node pruning for a query with single condition with inequality
   * operator for range partitioning. All the conditions are parameterized
   */
  public void testSingleConditionWhereClauseWithRangePartitioning_1()
      throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where ID  >= 5 ";
    startVMs(1, 3);

    VM dataStore1 = this.serverVMs.get(0);
    VM dataStore2 = this.serverVMs.get(1);
    VM dataStore3 = this.serverVMs.get(2);
    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, primary key (ID))"
        + "PARTITION BY RANGE ( ID )" + " ( VALUES BETWEEN -Infinity and 5, "
        + "VALUES BETWEEN  5 and +Infinity )"+ getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604')");
    }
    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];
      setupObservers(new VM[] { dataStore1, dataStore2, dataStore3 }, sqi);
      TestUtil.setupConnection();
      EmbedStatement stmnt = (EmbedStatement)TestUtil.jdbcConn
          .createStatement();
      Set<Integer> rslts = new HashSet<Integer>();
      for (int i = 5; i < 9; ++i) {
        rslts.add(Integer.valueOf(i));
      }

      ResultSet rs = stmnt.executeQuery(query);
      for (int i = 5; i < 9; ++i) {
        assertTrue(rs.next());
        assertTrue(rslts.remove(Integer.valueOf(rs.getInt(1))));
      }
      assertTrue(rslts.isEmpty());
      assertFalse(rs.next());
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.byrange), new SQLInteger(5),
          Boolean.TRUE, null, Boolean.TRUE } };
      Set<DistributedMember> expectedPrunedNodes = NodesPruningHelper
          .getExpectedNodes(query, sqi[0], routingInfo, getLogWriter());
      Set<DistributedMember> allNodes = NodesPruningHelper.getExpectedNodes(
          query, sqi[0], new Object[][] { { new Integer(
              NodesPruningHelper.allnodes) } }, getLogWriter());
      allNodes.removeAll(expectedPrunedNodes);
      verifyQueryExecution(sqi[0], expectedPrunedNodes, allNodes, 1, 2);
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  /**
   * Tests node pruning for a query with single condition with inequality
   * operator for range partitioning. All the conditions are parameterized
   */
  public void testSingleConditionWhereClauseWithRangePartitioning_2()
      throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where ID  >= 5 ";
    startVMs(1, 3);

    VM dataStore1 = this.serverVMs.get(0);
    VM dataStore2 = this.serverVMs.get(1);
    VM dataStore3 = this.serverVMs.get(2);
    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, primary key (ID))"
        + "PARTITION BY RANGE ( ID )" + " ( VALUES BETWEEN -Infinity and 5, "
        + "VALUES BETWEEN  5 and +Infinity )"+ getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604')");
    }
    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];
      setupObservers(new VM[] { dataStore1, dataStore2, dataStore3 }, sqi);
      TestUtil.setupConnection();
      EmbedStatement stmnt = (EmbedStatement)TestUtil.jdbcConn
          .createStatement();
      Set<Integer> rslts = new HashSet<Integer>();
      for (int i = 5; i < 9; ++i) {
        rslts.add(Integer.valueOf(i));
      }
      ResultSet rs = stmnt.executeQuery(query);

      for (int i = 5; i < 9; ++i) {
        assertTrue(rs.next());
        assertTrue(rslts.remove(Integer.valueOf(rs.getInt(1))));
      }
      assertFalse(rs.next());
      assertTrue(rslts.isEmpty());
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.byrange), new SQLInteger(5),
          Boolean.TRUE, null, Boolean.TRUE } };

      Set<DistributedMember> expectedPrunedNodes = NodesPruningHelper
          .getExpectedNodes(query, sqi[0], routingInfo, getLogWriter());
      Set<DistributedMember> allNodes = NodesPruningHelper.getExpectedNodes(
          query, sqi[0], new Object[][] { { new Integer(
              NodesPruningHelper.allnodes) } }, getLogWriter());
      allNodes.removeAll(expectedPrunedNodes);

      verifyQueryExecution(sqi[0], expectedPrunedNodes, allNodes, 1, 2);

    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  /**
   * If partition by clause is not defined, then default resolver should be
   * attached to the primary key
   */
  public void testNoExplicitResolverDefined_Bug39680() throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where ID  = 5 "
        + "and DESCRIPTION = 'First5' ";
    startVMs(1, 3);

    VM dataStore1 = this.serverVMs.get(0);
    VM dataStore2 = this.serverVMs.get(1);
    VM dataStore3 = this.serverVMs.get(2);
    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, primary key (ID))"+ getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604')");
    }
    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];
      setupObservers(new VM[] { dataStore1, dataStore2, dataStore3 }, sqi);
      TestUtil.setupConnection();
      EmbedStatement stmnt = (EmbedStatement)TestUtil.jdbcConn
          .createStatement();
      Set<Integer> rslts = new HashSet<Integer>();
      rslts.add(Integer.valueOf(5));

      ResultSet rs = stmnt.executeQuery(query);
      assertTrue(rs.next());
      assertTrue(rslts.remove(Integer.valueOf(rs.getInt(1))));
      assertTrue(rslts.isEmpty());
      assertFalse(rs.next());
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.bycolumn), new SQLInteger(5) } };

      Set<DistributedMember> expectedPrunedNodes = NodesPruningHelper
          .getExpectedNodes(query, sqi[0], routingInfo, getLogWriter());
      Set<DistributedMember> allNodes = NodesPruningHelper.getExpectedNodes(
          query, sqi[0], new Object[][] { { new Integer(
              NodesPruningHelper.allnodes) } }, getLogWriter());
      allNodes.removeAll(expectedPrunedNodes);
      verifyQueryExecution(sqi[0], expectedPrunedNodes, allNodes, 1, 2);
    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  /**
   * If partition by clause is not defined, then default resolver should be
   * attached to the primary key
   */
  public void testListPartitioningForDefaultResolver() throws Exception {
    // Create a table from client using range partitioning enabled.
    // Check for PARTITION BY RANGE
    // Start one client a two servers
    String query = "select ID, DESCRIPTION from TESTTABLE where ID  IN (5,6) "
        + "and DESCRIPTION <> '' ";
    startVMs(1, 3);

    VM dataStore1 = this.serverVMs.get(0);
    VM dataStore2 = this.serverVMs.get(1);
    VM dataStore3 = this.serverVMs.get(2);
    clientSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, primary key (ID))"+ getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i < 8; ++i) {
      clientSQLExecute(1, "insert into TESTTABLE values (" + (i + 1)
          + ", 'First" + (i + 1) + "', 'J 604')");
    }
    try {
      // Create a prepared statement for the query & store the queryinfo so
      // obtained to get
      // region reference
      final SelectQueryInfo[] sqi = new SelectQueryInfo[1];
      setupObservers(new VM[] { dataStore1, dataStore2, dataStore3 }, sqi);
      TestUtil.setupConnection();
      EmbedStatement stmnt = (EmbedStatement)TestUtil.jdbcConn
          .createStatement();
      Set<Integer> rslts = new HashSet<Integer>();
      rslts.add(Integer.valueOf(5));
      rslts.add(Integer.valueOf(6));
      ResultSet rs = stmnt.executeQuery(query);

      assertTrue(rs.next());
      assertTrue(rslts.remove(Integer.valueOf(rs.getInt(1))));
      assertTrue(rs.next());
      assertTrue(rslts.remove(Integer.valueOf(rs.getInt(1))));
      assertFalse(rs.next());
      assertTrue(rslts.isEmpty());
      Object[][] routingInfo = new Object[][] { {
          new Integer(NodesPruningHelper.bylist), new SQLInteger(5),
          new SQLInteger(6) } };

      Set<DistributedMember> expectedPrunedNodes = NodesPruningHelper
          .getExpectedNodes(query, sqi[0], routingInfo, getLogWriter());
      Set<DistributedMember> allNodes = NodesPruningHelper.getExpectedNodes(
          query, sqi[0], new Object[][] { { new Integer(
              NodesPruningHelper.allnodes) } }, getLogWriter());
      allNodes.removeAll(expectedPrunedNodes);

      verifyQueryExecution(sqi[0], expectedPrunedNodes, allNodes, 2, 1);

    } finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      isQueryExecutedOnNode = false;
      //invokeInEveryVM(DistributedSQLTestBase.class, "reset");
      clientSQLExecute(1, "Drop table TESTTABLE ");
    }
  }

  public String getSuffix() {
    return  "  ";
  }

  @Override
  protected void vmTearDown() throws Exception {
    super.vmTearDown();
    reset();
  }
}
