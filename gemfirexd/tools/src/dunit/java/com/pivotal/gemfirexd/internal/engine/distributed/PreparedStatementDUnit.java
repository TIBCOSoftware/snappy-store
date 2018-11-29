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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.control.RebalanceFactory;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.management.impl.InternalManagementService;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedDatabaseMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameter;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import org.eclipse.collections.api.iterator.LongIterator;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * Tests whether the statementID , connectionID etc are being passed correctly
 * or not
 * 
 * @author Asif
 * @since 6.0
 */
@SuppressWarnings("serial")
public class PreparedStatementDUnit extends DistributedSQLTestBase {

  private volatile static long connID = -1;

  private volatile static long stmtID = -1;

  // private volatile static String url;

  private volatile static String sql;

  private volatile static boolean executionUsingGemFireXDActivation = false;

  private volatile static boolean connectionCloseExecuted = false;

  public static String currentUserName = null;

  public static String currentUserPassword = null;

  public PreparedStatementDUnit(String name) {
    super(name);
  }

  private void checkOneResult(ResultSet rs) throws SQLException
  {
    assertTrue("Expected one result", rs.next());
    if (rs.next()) {
      fail("unexpected next element with ID: " + rs.getObject(1));
    }
  }

  private GemFireXDQueryObserver getQueryObserver(
      final boolean setConnectionCloseFlag)
  {
    return new GemFireXDQueryObserverAdapter() {
      @Override
      public void beforeQueryExecutionByPrepStatementQueryExecutor(
          GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt,
          String query) {
        getLogWriter().info(
            "setting the connectionId : " + wrapper.getIncomingConnectionId()
                + " and statementId : " + pstmt.getID());
        connID = wrapper.getIncomingConnectionId();
        stmtID = pstmt.getID();
        sql = query;
      }

      @Override
      public void beforeConnectionCloseByExecutorFunction(long[] connectionIDs)
      {
        if (setConnectionCloseFlag) {
          connectionCloseExecuted = true;
        }
      }
    };
  }

  public void testBug41222() throws Exception
  {
  
    try {
      // start a couple of VMs to use as datastore and this VM as accessor
      int n = Integer.getInteger("DistributionManager.MAX_FE_THREADS",
          Math.max(Runtime.getRuntime().availableProcessors() * 4, 16))
          .intValue();
      final int numThreads = n;
      startVMs(1, 1);
      clientSQLExecute(1, "create schema EMP");
      createDiskStore(true,1);
      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID)) " + getSuffix());
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");

      VM dataStore1 = this.serverVMs.get(0);

      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            String maxCid = "create function maxCidBug41222() "
                + "RETURNS INTEGER " + "PARAMETER STYLE JAVA "
                + "LANGUAGE JAVA " + "READS SQL DATA "
                + "EXTERNAL NAME 'sql.TestFunctions.getMaxCidBug41222'";
            TestUtil.setupConnection();
            Connection conn = TestUtil.jdbcConn;
            Statement stmt = conn.createStatement();
            stmt.executeUpdate(maxCid);
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter() {
                  final CyclicBarrier barrier = new CyclicBarrier(numThreads,
                      new Runnable() {
                        public void run()
                        {
                          GemFireXDQueryObserverHolder
                              .setInstance(new GemFireXDQueryObserverAdapter());
                        }
                      });

                  @Override
                  public void beforeQueryExecutionByPrepStatementQueryExecutor(
                      GfxdConnectionWrapper wrapper,
                      EmbedPreparedStatement pstmt, String query)
                  {
                    try {
                      barrier.await();
                    }
                    catch (Exception e) {
                      throw GemFireXDRuntimeException.newRuntimeException(null,
                          e);
                    }
                  }
                });
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      dataStore1.invoke(setObserver);
      // setObserver.run();
      TestUtil.setupConnection();

      final PreparedStatement[] eps = new PreparedStatement[numThreads];
      for (int i = 0; i < eps.length; ++i) {
        EmbedConnection mcon = (EmbedConnection)TestUtil.getConnection();
        eps[i] = mcon
            .prepareStatement("select * from EMP.TESTTABLE where ID = maxCidBug41222()");
      }
      final boolean failed[] = new boolean[] { false };
      Thread threads[] = new Thread[numThreads];
      int i = 0;
      for (PreparedStatement var : eps) {
        final PreparedStatement ps = var;
        threads[i] = new Thread(new Runnable() {
          public void run()
          {
            try {
              ResultSet rs = ps.executeQuery();
              rs.close();
            }
            catch (SQLException e) {
              e.printStackTrace();
              failed[0] = true;
            }

          }
        });
        threads[i].start();
        ++i;
      }
      for (Thread th : threads) {
        th.join();
      }
      assertFalse(failed[0]);

    }
    finally {
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testSerializationData() throws Exception
  {
    try {
      // Start one client and two servers
      startVMs(1, 2);
      createDiskStore(true,1);
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID)) " + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'Second', 'J 604')");
      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder.setInstance(getQueryObserver(false));
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);

      TestUtil.setupConnection();
      EmbedConnection mcon = (EmbedConnection)TestUtil.jdbcConn;
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE "
              + "where id >= ? and DESCRIPTION = ?");
      eps.setInt(1, 1);
      eps.setString(2, "First");
      ResultSet rs = eps.executeQuery();
      checkOneResult(rs);
      final long clientConnID = mcon.getConnectionID();
      final long clientStmntID = eps.getID();
      SerializableRunnable validate = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertEquals(clientConnID, connID);
            assertEquals(clientStmntID, stmtID);
            connID = -1;
            stmtID = -1;
            sql = null;
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      dataStore1.invoke(validate);
      dataStore2.invoke(validate);

      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }

  }

  /**
   * The nodes which have already seen the prepared statement should not get the
   * query string passed on every execution
   * 
   */
  public void testQueryStringNotPassedEveryTime() throws Exception
  {
    try {
      startServerVMs(2, 0, "SG2");
      startClientVMs(1, 0, null);
      createDiskStore(true,1);
      // Create a schema with default server groups GemFire extension
      clientSQLExecute(1, "create schema EMP default server groups (SG2)");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID) )" + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'Second', 'J 604')");

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter() {
                  @Override
                  public void beforeQueryExecutionByPrepStatementQueryExecutor(
                      GfxdConnectionWrapper wrapper,
                      EmbedPreparedStatement pstmt, String query)
                  {
                    connID = wrapper.getIncomingConnectionId();
                    stmtID = pstmt.getID();
                    sql = query;
                    assertNull("got query " + query, query);
                  }
                });
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };

      TestUtil.setupConnection();
      EmbedConnection mcon = (EmbedConnection)TestUtil.jdbcConn;
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE "
              + "where id  >= ? and DESCRIPTION = ? ");
      eps.setInt(1, 1);
      eps.setString(2, "First");
      ResultSet rs = eps.executeQuery();
      checkOneResult(rs);
      eps.setInt(1, 1);
      eps.setString(2, "First");
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      rs = eps.executeQuery();
      checkOneResult(rs);
      final long clientConnID = mcon.getConnectionID();
      final long clientStmntID = eps.getID();
      SerializableRunnable validate = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertEquals(clientConnID, connID);
            assertEquals(clientStmntID, stmtID);
            assertNull(sql);
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      // Rahul: this needs reivew by Asif. Similar reasons as in above test.
      dataStore1.invoke(validate);
      dataStore2.invoke(validate);
    }
    finally {
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  /**
   * First create a single data store. Execute the prepared statement query. Now
   * add another node. Rexecute the prepared statement. The source string should
   * be sent only to the newly created node. Rexecute the query. The source
   * string should not be sent to any node.
   * 
   */
  public void testPopluationOfPrepStmntIDToNodes() throws Exception {
    // Disabling create statement will need a primary key or explicit
    // partitioned by.
    startVMs(1, 1);
    createDiskStore(true,1);
    final String queryStr = "select ID, DESCRIPTION from EMP.TESTTABLE "
        + "where id  >= ? and DESCRIPTION = ? ";
    // Create a schema
    clientSQLExecute(1, "create schema EMP");

    // Create the table and insert a row
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
        + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
        + " primary key (ID) ) " + getSuffix());
    try {

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      // Execute the query on server1.
      TestUtil.setupConnection();
      EmbedConnection mcon = (EmbedConnection)TestUtil.jdbcConn;
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(queryStr);
      eps.setInt(1, 1);
      eps.setString(2, "First");
      ResultSet rs = eps.executeQuery();
      checkOneResult(rs);
      // Start data store on second node
      startServerVMs(1, 0, null);
      
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'Second', 'J 604')");
      // Attach a GemFireXDQueryObserver in both the VMs
      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      //TODO prpersist - We need to rebalance. Now that gets will trigger
      //bucket creation, all of the buckets have already been created on data store
      //1. So this test will fail, because we never create any buckets on data store 2,
      //so we never need to send the statement to the second data store.
      dataStore2.invoke(new SerializableRunnable("Rebalance") {
        @Override
        public void run() {
          RebalanceFactory rf = GemFireCacheImpl.getInstance().getResourceManager().createRebalanceFactory();
          try {
            rf.start().getResults();
          } catch (Exception e) {
            fail("Caught exception", e);
          }
        }
      });
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder.setInstance(getQueryObserver(false));
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };

      eps.setInt(1, 1);
      eps.setString(2, "First");
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      // Rexecute the query
      rs = eps.executeQuery();
      checkOneResult(rs);
      final long clientConnID = mcon.getConnectionID();
      final long clientStmntID = eps.getID();
      SerializableRunnable validateNoQuerySend = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertEquals(clientConnID, connID);
            assertEquals(clientStmntID, stmtID);
            // TODO:Asif:UNCOMMENT AFTER FIXING BUG 39888
            assertNull(sql);
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      SerializableRunnable validateQuerySend = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertEquals(clientConnID, connID);
            assertEquals(clientStmntID, stmtID);
            assertEquals(sql, queryStr);
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      dataStore1.invoke(validateNoQuerySend);
      dataStore2.invoke(validateQuerySend);
      // Rexecute the query
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);

      eps.setInt(1, 1);
      eps.setString(2, "First");
      rs = eps.executeQuery();
      checkOneResult(rs);
      dataStore1.invoke(validateNoQuerySend);
      dataStore2.invoke(validateNoQuerySend);
    }
    finally {
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug39364_1() throws Exception
  {
    try {
      startServerVMs(1, 0, "SG2");
      startClientVMs(1, 0, null);
      createDiskStore(true,1);
      final String queryStr = "select ID, DESCRIPTION from EMP.TESTTABLE "
          + "where id  IN (?,?,?) order by ID asc ";
      // Create a schema with default server groups GemFire extension
      clientSQLExecute(1, "create schema EMP default server groups (SG2)");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
          + "primary key (ID))" + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'First', 'J 604')");
      // Execute the query on server1.
      TestUtil.setupConnection();
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(queryStr);
      eps.setInt(1, 1);
      eps.setInt(2, 2);
      eps.setInt(3, 3);

      ResultSet rs = eps.executeQuery();
      HashSet<Integer> found = new HashSet<Integer>();
      HashSet<Integer> expected= new HashSet<Integer>();
      for (int i = 0; i < 3; ++i) {
        assertTrue(rs.next());
        found.add(rs.getInt(1));
        expected.add(i+1);
      }
      assertEquals(expected, found);
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug39364_2() throws Exception
  {
    try {
      startVMs(1, 1);
      createDiskStore(true,1);
      final String queryStr = "select ID, DESCRIPTION from EMP.TESTTABLE "
          + "where id  IN (?,2,?) order by ID asc ";
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
          + "primary key (ID))" + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'First', 'J 604')");
      // Execute the query on server1.
      TestUtil.setupConnection();
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(queryStr);
      eps.setInt(1, 1);
      eps.setInt(2, 3);
      // eps.setInt(3, 3);

      ResultSet rs = eps.executeQuery();
      HashSet<Integer> found = new HashSet<Integer>();
      HashSet<Integer> expected= new HashSet<Integer>();
      for (int i = 0; i < 3; ++i) {
        assertTrue(rs.next());
        found.add(rs.getInt(1));
        expected.add(i+1);
      }
      assertEquals(expected, found);
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug39369_1() throws Exception
  {
    try {
      startServerVMs(2, 0, "SG2");
      startClientVMs(1, 0, null);
      createDiskStore(true,1);
      final String queryStr = "select ID, DESCRIPTION from EMP.TESTTABLE "
          + "where ( ID  >= 1 ) ";
      // Create a schema with default server groups GemFire extension
      clientSQLExecute(1, "create schema EMP default server groups (SG2)");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int primary key, "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024))"
          + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'First', 'J 604')");

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter() {
                  @Override
                  public void beforeGemFireResultSetExecuteOnActivation(
                      AbstractGemFireActivation activation)
                  {
                    String query = activation.getStatementText();
                    // skip for queries on SYS.MEMORYANALYTICS which are now
                    // done periodically by GFXD mbeans thread
                    if (query == null || (!query.contains("SYS.MEMORYANALYTICS")
                        && !query.contains("SYS.MEMBERS"))) {
                      executionUsingGemFireXDActivation = true;
                    }
                  }

                });
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };

      SerializableRunnable validate = new SerializableRunnable(
          "validate no gfxd fabric activation used") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertFalse(executionUsingGemFireXDActivation);
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      // Execute the query on clien1, i.e on controller VM
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      sqlExecuteVerify(new int[] { 1 }, null, queryStr, null, null);
      dataStore1.invoke(validate);
      dataStore2.invoke(validate);
      // Now reset in all VMs
      invokeInEveryVM(this.getClass(), "reset");
      // Now set the observers in server 2, server1 & controller VM
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation)
            {
              String query = activation.getStatementText();
              // skip for queries on SYS.MEMORYANALYTICS which are now
              // done periodically by GFXD mbeans thread
              if (query == null || (!query.contains("SYS.MEMORYANALYTICS")
                  && !query.contains("SYS.MEMBERS"))) {
                executionUsingGemFireXDActivation = true;
              }
            }
          });

      SerializableRunnable validateGemFireXDActivation = new SerializableRunnable(
          "validate  gfxd fabric activation used") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertTrue(executionUsingGemFireXDActivation);
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      sqlExecuteVerify(null, new int[] { 1 }, queryStr, null, null);
      dataStore2.invoke(validate);
      dataStore1.invoke(validateGemFireXDActivation);
      assertFalse(executionUsingGemFireXDActivation);
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug39369_2() throws Exception
  {
    try {
      startVMs(1, 2);
      createDiskStore(true,1);
      final String queryStr = "select ID, DESCRIPTION from EMP.TESTTABLE "
          + "where (ID  >= ?)";
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int, "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + "primary key (ID))" + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'First', 'J 604')");
      // Execute the query on server1.
      TestUtil.setupConnection();
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(queryStr);
      eps.setInt(1, 1);
      ResultSet rs = eps.executeQuery();
      Set<Integer> rslts = new HashSet<Integer>();
      for (int i = 1; i < 4; ++i) {
        rslts.add(Integer.valueOf(i));
      }

      for (int i = 0; i < 3; ++i) {
        assertTrue(rs.next());
        assertTrue(rslts.remove(rs.getInt(1)));
      }
      assertTrue(rslts.isEmpty());
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug39321() throws Exception
  {
    try {
      startServerVMs(2, 0, "SG2");
      startClientVMs(1, 0, null);
      createDiskStore(true,1);
      final String queryStr = "select ID, DESCRIPTION from EMP.TESTTABLE "
          + "where (ID  >= ?)";
      // Create a schema with default server groups GemFire extension
      clientSQLExecute(1, "create schema EMP default server groups (SG2)");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int, "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024))"
          + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'First', 'J 604')");
      /*
       * clientSQLExecute(1, "insert into EMP.TESTTABLE values (3, 'First', 'J
       * 604')");
       */
      // First execute the query on client , so that the query on data store
      // node creates a non distributed activation object.
      TestUtil.setupConnection();
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(queryStr);
      eps.setInt(1, 1);
      ResultSet rs = eps.executeQuery();
      Set<Integer> rslts = new HashSet<Integer>();
      for (int i = 1; i < 3; ++i) {
        rslts.add(Integer.valueOf(i));
      }

      for (int i = 0; i < 2; ++i) {
        assertTrue(rs.next());
        assertTrue(rslts.remove(rs.getInt(1)));
      }
      assertTrue(rslts.isEmpty());

      // Now fire the same query on one of the data store & we should ideally
      // see two records but because of bug only one record being fetched
      VM dataStore1 = this.serverVMs.get(0);
      SerializableRunnable queryExecutor = new SerializableRunnable(
          "Query Executor") {
        @Override
        public void run() throws CacheException
        {
          try {
            TestUtil.setupConnection();
            EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
                .prepareStatement(queryStr);
            eps.setInt(1, 1);
            ResultSet rs = eps.executeQuery();
            Set<Integer> rslts = new HashSet<Integer>();
            for (int i = 1; i < 3; ++i) {
              rslts.add(Integer.valueOf(i));
            }

            for (int i = 0; i < 2; ++i) {
              assertTrue(rs.next());
              assertTrue(rslts.remove(rs.getInt(1)));
            }
            assertTrue(rslts.isEmpty());

          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      dataStore1.invoke(queryExecutor);
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  /**
   * Verify if the connection is being closed in a PR based table with the
   * distribution happening to data store nodes
   * 
   * @throws Exception
   */
  public void testDistributedConnectionCloseForPRTable_Bug40135_1()
      throws Exception
  {
    try {
      // Start one client and two servers
      startVMs(1, 3);
      createDiskStore(true,1);
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID)) " + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'Second', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'Third', 'J 604')");
      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder.setInstance(getQueryObserver(true));
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);

      TestUtil.setupConnection();
      EmbedConnection mcon = (EmbedConnection)TestUtil.jdbcConn;
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE "
              + "where id  >= ? and DESCRIPTION = ?");
      eps.setInt(1, 1);
      eps.setString(2, "First");
      ResultSet rs = eps.executeQuery();
      checkOneResult(rs);
      final long clientConnID = mcon.getConnectionID();
      final long clientStmntID = eps.getID();
      SerializableRunnable validate = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertEquals(clientConnID, connID);
            assertEquals(clientStmntID, stmtID);
            // Check if the connection holder still contains the connection
            // Chcek it in a loop as closing of conenction via function service
            // happens
            // asynchronously
            int tries = 0;
            boolean ok = false;
            while (tries < 100) {
              ok = GfxdConnectionHolder.getHolder().getExistingWrapper(
                  Long.valueOf(connID)) == null;
              if (ok) {
                break;
              }
              Thread.sleep(500);
              ++tries;
            }
            assertTrue(ok);
            tries = 0;
            ok = false;
            while (tries < 100) {
              ok = connectionCloseExecuted;
              if (ok) {
                break;
              }
              Thread.sleep(500);
              ++tries;
            }
            assertTrue(ok);
            // assertTrue(connectionCloseExecuted);
            connID = -1;
            stmtID = -1;
            sql = null;

          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };
      mcon.close();

      dataStore1.invoke(validate);
      dataStore2.invoke(validate);
      dataStore3.invoke(validate);

      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  /**
   * Verify if the connection is being closed in a Replication based table with
   * the distribution happening to data store nodes
   * 
   * @throws Exception
   */
  public void testDistributedConnectionCloseForReplicatedTable_Bug40135_2()
      throws Exception
  {
    try {
      // Start one client and two servers
      startVMs(1, 3);
      createDiskStore(true,1);
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID)) replicate " + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'First', 'J 604')");
      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);

      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder.setInstance(getQueryObserver(true));
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };

      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);

      // Attach observer in the controller VM also
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeConnectionCloseByExecutorFunction(
                long[] connectionIDs)
            {
              connectionCloseExecuted = true;
            }
          });

      TestUtil.setupConnection();
      EmbedConnection mcon = (EmbedConnection)TestUtil.jdbcConn;
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE "
              + "where id  >= ? and DESCRIPTION = ?");
      eps.setInt(1, 1);
      eps.setString(2, "First");
      ResultSet rs = eps.executeQuery();
      assertTrue(rs.next());
      assertTrue(rs.next());
      assertFalse(rs.next());
      SerializableRunnable validate = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            // Check if the connection holder still contains the connection

            // Check it in a loop as closing of conenction via function service
            // happens
            // asynchronously
            int tries = 0;
            boolean ok = false;
            while (tries < 100) {
              ok = connID == -1
                  || GfxdConnectionHolder.getHolder().getExistingWrapper(
                      Long.valueOf(connID)) == null;
              if (ok) {
                break;
              }
              Thread.sleep(500);
              ++tries;
            }
            assertTrue(ok);
            tries = 0;
            ok = false;
            while (tries < 100) {
              ok = connectionCloseExecuted;
              if (ok) {
                break;
              }
              Thread.sleep(500);
              ++tries;
            }
            assertTrue(ok);
            connID = -1;
            stmtID = -1;
            sql = null;
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };
      mcon.close();

      dataStore1.invoke(validate);
      dataStore2.invoke(validate);
      dataStore3.invoke(validate);
      assertTrue(connectionCloseExecuted);
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  /**
   * Verify if the connection is being closed in a PR based table with the
   * distribution happening to data store nodes
   * 
   * @throws Exception
   */
  public void testDistributedPrepStatementCloseForPRTable_Bug40136_1()
      throws Exception
  {
    try {
      startVMs(1, 3);
      createDiskStore(true,1);
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID)) " + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'Second', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'Third', 'J 604')");

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder.setInstance(getQueryObserver(false));
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);

      TestUtil.setupConnection();
      EmbedConnection mcon = (EmbedConnection)TestUtil.jdbcConn;
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE "
              + "where id  >= ? and DESCRIPTION = ?");
      eps.setInt(1, 1);
      eps.setString(2, "First");
      ResultSet rs = eps.executeQuery();
      checkOneResult(rs);
      final long clientConnID = mcon.getConnectionID();
      final long clientStmntID = eps.getID();
      eps.close();
      SerializableRunnable validate = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertEquals(clientConnID, connID);
            assertEquals(clientStmntID, stmtID);
            // Check if the connection holder still contains the connection
            GfxdConnectionWrapper scw = GfxdConnectionHolder.getHolder()
                .getExistingWrapper(Long.valueOf(connID));
            boolean ok = (scw == null);
            if (!ok) {
              // Check it in a loop as closing of PrepStatement via function
              // service happens asynchronously
              int tries = 0;
              while (tries < 100) {
                ok = scw.getStatementForTEST(Long.valueOf(stmtID)) == null;
                if (ok) {
                  break;
                }
                Thread.sleep(500);
                ++tries;
              }
            }
            assertTrue(ok);
            connID = -1;
            stmtID = -1;
            sql = null;
          } catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      dataStore1.invoke(validate);
      dataStore2.invoke(validate);
      dataStore3.invoke(validate);

      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  /**
   * Verify if the connection is being closed in a PR based table with the query
   * happening on the data store node
   * 
   * @throws Exception
   */
  public void testDistributedPrepStatementCloseForPRTable_Bug40136_2()
      throws Exception
  {
    try {
      startServerVMs(4, 0, null);
      createDiskStore(false,1);
      // startClientVMs(1, Integer.valueOf(mcastport).intValue());

      // Create a schema
      serverSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      serverSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID)) " + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      serverSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");

      serverSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'Second', 'J 604')");

      serverSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'Third', 'J 604')");

      serverSQLExecute(1,
          "insert into EMP.TESTTABLE values (4, 'Fourth', 'J 604')");
      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);
      VM dataStore4 = this.serverVMs.get(3);
      final GemFireXDQueryObserver soo = getQueryObserver(false);
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder.setInstance(soo);
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);
      dataStore4.invoke(setObserver);
      GemFireXDQueryObserverHolder.setInstance(soo);

      // Execute query from DataStore1
      SerializableRunnable queryExecutor = new SerializableRunnable(
          "queryExecutor") {
        @Override
        public void run() throws CacheException
        {
          try {
            // Obtain a new connection
            Connection conn = TestUtil.getConnection();
            // Create a new prepared statement
            EmbedPreparedStatement eps = (EmbedPreparedStatement)conn
                .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE "
                    + "where id  >= 3 and DESCRIPTION = 'abc'");
            eps.executeQuery();
            eps.close();

          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };

      dataStore1.invoke(queryExecutor);

      SerializableRunnable validate = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            assertTrue(connID != -1);
            assertTrue(stmtID != -1);
            GfxdConnectionWrapper scw = GfxdConnectionHolder.getHolder()
                .getExistingWrapper(Long.valueOf(connID));
            boolean ok = (scw == null);
            if (!ok) {
              getLogWriter().info(
                  "Got wrapper for statementId=" + stmtID + " connectionId="
                      + connID + " : " + scw);
              int tries = 0;
              while (tries < 100) {
                ok = scw.getStatementForTEST(Long.valueOf(stmtID)) == null;
                if (ok) {
                  break;
                }
                Thread.sleep(500);
                ++tries;
              }
              assertTrue(ok);
              connID = -1;
              stmtID = -1;
              sql = null;
            }
          } catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      dataStore1.invoke(validate);
      dataStore2.invoke(validate);
      dataStore3.invoke(validate);
      dataStore4.invoke(validate);
      // validate local behaviour. The query should not be distributed to local
      // node
      // as it does not hold buckets
      assertEquals(connID, -1);
      assertEquals(stmtID, -1);
      // Check if the connection holder still contains the connection
      GfxdConnectionWrapper scw = connID != -1 ? GfxdConnectionHolder.getHolder()
          .getExistingWrapper(Long.valueOf(connID)) : null;
      assertNull(scw);
      connID = -1;
      stmtID = -1;
      sql = null;
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      serverSQLExecute(1, "Drop table EMP.TESTTABLE ");
      serverSQLExecute(1, "Drop schema EMP restrict");
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  /**
   * Verify if the connection is being closed in a Replicated based table with
   * the query happening on the data store node
   * 
   * @throws Exception
   */

  public void testDistributedPrepStatementCloseForReplicatedTable_Bug40136_1()
      throws Exception
  {

    try {
      startVMs(1, 3);
      createDiskStore(true,1);
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024),"
          + " primary key (ID)) replicate " + getSuffix());

      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");

      // Attach a GemFireXDQueryObserver in the server VM

      VM dataStore1 = this.serverVMs.get(0);
      VM dataStore2 = this.serverVMs.get(1);
      VM dataStore3 = this.serverVMs.get(2);

      final GemFireXDQueryObserver soo = getQueryObserver(false);
      SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver") {
        @Override
        public void run() throws CacheException
        {
          try {
            GemFireXDQueryObserverHolder.setInstance(soo);
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      dataStore1.invoke(setObserver);
      dataStore2.invoke(setObserver);
      dataStore3.invoke(setObserver);

      GemFireXDQueryObserverHolder.setInstance(soo);

      // Execute query from Accessor1
      SerializableRunnable queryExecutor = new SerializableRunnable(
          "queryExecutor") {
        @Override
        public void run() throws CacheException
        {
          try {
            // Obtain a new connection
            Connection conn = TestUtil.getConnection();
            // Create a new prepared statement
            EmbedPreparedStatement eps = (EmbedPreparedStatement)conn
                .prepareStatement("select ID, DESCRIPTION from EMP.TESTTABLE "
                    + "where id  >= 3 and DESCRIPTION = 'abc'");
            eps.executeQuery();
            eps.close();

          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
      // Execute query on local client VM which is acessor
      queryExecutor.run();

      SerializableRunnable validate = new SerializableRunnable(
          "validate") {
        @Override
        public void run() throws CacheException
        {
          try {
            // Check if the connection holder still contains the connection
            GfxdConnectionWrapper scw = connID != -1 ? GfxdConnectionHolder
                .getHolder().getExistingWrapper(Long.valueOf(connID)) : null;
            if (scw != null) {
              int tries = 0;
              boolean ok = false;
              while (tries < 100) {
                ok = scw.getStatementForTEST(Long.valueOf(stmtID)) == null;
                if (ok) {
                  break;
                }
                Thread.sleep(500);
                ++tries;
              }
              assertTrue(ok);
            }
            connID = -1;
            stmtID = -1;
            sql = null;
          }
          catch (Exception e) {
            throw new CacheException(e) {
            };
          }
          finally {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter());
          }
        }
      };

      dataStore1.invoke(validate);
      dataStore2.invoke(validate);
      dataStore3.invoke(validate);

      // validate local behaviour. The query should not be distributed to local
      // node
      // as it does not hold buckets
      assertEquals(connID, -1);
      assertEquals(stmtID, -1);
      // Check if the connection holder still contains the connection
      GfxdConnectionWrapper scw = connID != -1 ? GfxdConnectionHolder
          .getHolder().getExistingWrapper(Long.valueOf(connID)) : null;
      assertNull(scw);
      connID = -1;
      stmtID = -1;
      sql = null;
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      clientSQLExecute(1, "Drop table EMP.TESTTABLE ");
      clientSQLExecute(1, "Drop schema EMP restrict");
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testCommon() throws Exception
  {
    try {
      startVMs(1, 3);
      createDiskStore(true,1);
      final String queryStr = "select ID, DESCRIPTION from EMP.TESTTABLE "
          + "where id  > ?";
      // Create a schema
      clientSQLExecute(1, "create schema EMP");

      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
          + "primary key (ID))" + getSuffix());

      // clientSQLExecute(1, "create index indx on EMP.TESTTABLE (ID)");
      for (int i = 0; i < 100; ++i) {
        clientSQLExecute(1, "insert into EMP.TESTTABLE values (" + (i + 1)
            + ", 'First', 'J 604')");
      }
      /*
       * clientSQLExecute(1, "insert into EMP.TESTTABLE values (2, 'First', 'J
       * 604')"); clientSQLExecute(1, "insert into EMP.TESTTABLE values (3,
       * 'First', 'J 604')");
       */
      // Execute the query on server1.
      TestUtil.setupConnection();
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(queryStr);
      eps.setInt(1, 50);

      // eps.setInt(3, 3);
      Set<Integer> results = new HashSet<Integer>();
      ResultSet rs = eps.executeQuery();
      for (int i = 0; i < 50; ++i) {
        assertTrue(rs.next());
        results.add(Integer.valueOf(rs.getInt(1)));
      }
      assertFalse(rs.next());

      for (int i = 51; i < 101; ++i) {
        results.remove(Integer.valueOf(i));
      }
      assertTrue(results.isEmpty());
    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testForBug40209And40220NoDistributionOccurs() throws Exception
  {
    try {
      startServerVMs(2, 0, "SG2");
      startClientVMs(1, 0, null);
      createDiskStore(true,1);
      final boolean usedGemFireXDActication[] = new boolean[] { false };
      final boolean queryInfoCreated[] = new boolean[] { false };
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void beforeGemFireResultSetExecuteOnActivation(
                AbstractGemFireActivation activation)
            {
              usedGemFireXDActication[0] = true;
            }

            @Override
            public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo, GenericPreparedStatement gps, LanguageConnectionContext lcc)
            {
              queryInfoCreated[0] = true;
            }
          });

      TestUtil.setupConnection();
      Connection conn = TestUtil.jdbcConn;
      conn.getMetaData().getIndexInfo(null, null, "SYS", true, true);
      assertFalse("The query should have been executed using Derby's "
          + "activation locally", usedGemFireXDActication[0]);
      assertFalse("The QueryInfo object should not have been created",
          queryInfoCreated[0]);
      // Reset
      queryInfoCreated[0] = false;
      usedGemFireXDActication[0] = false;
      ((EmbedDatabaseMetaData)conn.getMetaData()).getClientInfoProperties();
      assertFalse("The query should have been executed using Derby's "
          + "activation locally", usedGemFireXDActication[0]);
      assertFalse("The QueryInfo object should not have been created",
          queryInfoCreated[0]);

    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug40997() throws Exception
  {
    try {
      startVMs(1, 1);
      createDiskStore(true,1);
      // Create a schema
      clientSQLExecute(1, "create schema trade");

      // Create the table and insert a row
      clientSQLExecute(
          1,
          "create table trade.securities (sec_id int not null, symbol varchar(10) not null, " +
          "price decimal (30, 20), exchange varchar(10) not null, tid int," +
          " constraint sec_pk primary key (sec_id), constraint sec_uq unique (symbol, " +
          "exchange), constraint exc_ch check" +
          " (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))  replicate "
              + getSuffix());

      clientSQLExecute(
          1,
          "create table trade.customers (cid int not null, cust_name varchar(100), " +
          "since date, addr varchar(100), tid int, primary key (cid))   partition by range " +
          "(cid) ( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902, " +
          "VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, " +
          "VALUES BETWEEN 1678 AND 10000) "+ getSuffix());
      clientSQLExecute(
          1,
          "create table trade.networth (cid int not null, cash decimal (30, 20), " +
          "securities decimal (30, 20), loanlimit int, availloan decimal (30, 20), " +
          " tid int, constraint netw_pk primary key (cid), constraint cust_newt_fk" +
          " foreign key (cid) references trade.customers (cid) on delete restrict, " +
          "constraint cash_ch check (cash>=0), constraint sec_ch check (securities >=0), " +
          "constraint availloan_ck check (loanlimit>=availloan and availloan >=0)) " +
          "  partition by range (cid) ( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902," +
          " VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678," +
          " VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers) "
              + getSuffix());
      clientSQLExecute(
          1,
          "create table trade.portfolio (cid int not null, sid int not null, qty int not null," +
          " availQty int not null, subTotal decimal(30,20), tid int, " +
          "constraint portf_pk primary key (cid, sid), constraint cust_fk foreign key (cid)" +
          " references trade.customers (cid) on delete restrict, " +
          "constraint sec_fk foreign key (sid) references trade.securities (sec_id), " +
          "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and " +
          "availQty<=qty))  partition by column (cid, sid) "
              + getSuffix());
      clientSQLExecute(
          1,
          "create table trade.sellorders (oid int not null constraint orders_pk primary key, " +
          "cid int, sid int, qty int, ask decimal (30, 20), order_time timestamp, status" +
          " varchar(10) default 'open', tid int, constraint portf_fk foreign key (cid, sid)" +
          " references trade.portfolio (cid, sid) on delete restrict, constraint status_ch " +
          "check (status in ('cancelled', 'open', 'filled')))"
              + getSuffix());
      clientSQLExecute(
          1,
          "create table trade.buyorders(oid int not null constraint buyorders_pk primary key," +
          " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, " +
          "status varchar(10), tid int, constraint " +
          "bo_cust_fk foreign key (cid) references trade.customers (cid) on delete restrict," +
          " constraint bo_sec_fk foreign key (sid) references trade.securities (sec_id)," +
          " constraint bo_qty_ck check (qty>=0))"
              + getSuffix());
      clientSQLExecute(
          1,
          "create table trade.txhistory(cid int, oid int, sid int, qty int, " +
          "price decimal (30, 20), ordertime timestamp, type varchar(10), tid int,  " +
          "constraint type_ch check (type in ('buy', 'sell')))"
              + getSuffix());
      clientSQLExecute(
          1,
          "create table emp.employees (eid int not null constraint employees_pk primary key," +
          " emp_name varchar(100), since date, addr varchar(100), ssn varchar(9))"
              + getSuffix());
      clientSQLExecute(
          1,
          " create table trade.trades (tid int, cid int, eid int, tradedate date," +
          " primary Key (tid), foreign key (cid) references trade.customers (cid)," +
          " constraint emp_fk foreign key (eid) references emp.employees (eid))"
              + getSuffix());
      clientSQLExecute(
          1,
          "create function maxCid(DP1 Integer) RETURNS INTEGER PARAMETER STYLE JAVA LANGUAGE JAVA READS SQL DATA EXTERNAL NAME 'sql.TestFunctions.getMaxCid'");

      String subquery = "select * from trade.customers c where c.cid = maxCid( 3  )";
      TestUtil.setupConnection();
      Connection conn = TestUtil.jdbcConn;

      PreparedStatement psGFE = conn.prepareCall(subquery);

      psGFE.execute();
      ResultSet gfeRS = psGFE.getResultSet();
      assertFalse(gfeRS.next());

    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testBug41005_1() throws Exception
  {
    try {      
      startVMs(1, 3);
      createDiskStore(true,1);
      // Create a schema
      clientSQLExecute(1, "create schema trade");

      clientSQLExecute(
          1,
          "create table trade.customers (cid int not null, cust_name varchar(100), tid int, " +
          "primary key (cid))   partition by range (cid) ( VALUES BETWEEN 0 AND 599," +
          " VALUES BETWEEN 599 AND 902, VALUES BETWEEN 902 AND 1255," +
          " VALUES BETWEEN 1255 AND 1678, VALUES BETWEEN 1678 AND 10000)"
              + getSuffix());
      clientSQLExecute(
          1,
          "create table trade.networth (cid int not null, loanlimit int, tid int, " +
          "constraint netw_pk primary key (cid), constraint cust_newt_fk foreign key (cid)" +
          " references trade.customers (cid) on delete restrict)" +
          "   partition by range (cid) ( VALUES BETWEEN 0 AND 599, VALUES BETWEEN 599 AND 902," +
          " VALUES BETWEEN 902 AND 1255, VALUES BETWEEN 1255 AND 1678, " +
          "VALUES BETWEEN 1678 AND 10000) colocate with (trade.customers)"
              + getSuffix());
      clientSQLExecute(
          1,
          "create function maxCid(DP1 Integer) RETURNS INTEGER PARAMETER STYLE JAVA LANGUAGE JAVA READS SQL DATA EXTERNAL NAME 'sql.TestFunctions.getMaxCidForBug41005' ");
      clientSQLExecute(1, "insert into trade.customers values (1,'test' ,1)");
      clientSQLExecute(1, "insert into trade.networth values (1,1 ,1)");
      clientSQLExecute(1, "insert into trade.customers values (2,'test' ,2)");
      clientSQLExecute(1, "insert into trade.networth values (2,2 ,2)");
      clientSQLExecute(1, "insert into trade.customers values (3,'test' ,3)");
      clientSQLExecute(1, "insert into trade.networth values (3,3 ,3)");
      TestUtil.setupConnection();
      Connection conn = TestUtil.jdbcConn;
      String subquery = "select * from trade.customers c where c.cid = maxCid( 1  )";
      PreparedStatement psGFE = conn.prepareCall(subquery);

      psGFE.execute();
      ResultSet gfeRS = psGFE.getResultSet();
      assertTrue(gfeRS.next());

    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void testCallbackArgBehaviour() throws Exception
  {
    startVMs(1, 4);
    createDiskStore(true,1);
    clientSQLExecute(
        1,
        "create table TESTTABLE1 (ID1 int not null, ID2 int not null ,"
            + " DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID2 )");
    PreparedStatement ps = TestUtil
        .getPreparedStatement("Insert into TESTTABLE1 values (?,?,?,?)");
    // Make 8 inserts
    for (int i = 1; i < 9; ++i) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.setString(3, "DESC" + i);
      ps.setString(4, "ADD" + i);
      ps.executeUpdate();
    }

    // Make PK based selects
    ps = TestUtil
        .getPreparedStatement("select ID2 from TESTTABLE1 where ID1 = ?");
    for (int i = 1; i < 9; ++i) {
      ps.setInt(1, i);
      ResultSet rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(rs.getInt(1), i + 1);
    }

    // make PK based updates
    ps = TestUtil
        .getPreparedStatement("Update TESTTABLE1 set ADDRESS = ? where ID1 = ?");
    for (int i = 1; i < 9; ++i) {
      ps.setString(1, "ADD" + (i + 1));
      ps.setInt(2, i);
      ps.executeUpdate();
      // assertEquals(1,ps.executeUpdate());
      // System.out.println("Asif: executed update for i ="+i);
    }

    // Make PK based selects
    ps = TestUtil
        .getPreparedStatement("select ADDRESS from TESTTABLE1 where ID1 = ?");
    for (int i = 1; i < 9; ++i) {
      ps.setInt(1, i);
      ResultSet rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals("ADD" + (i + 1), rs.getString(1));
    }
  }

  public void testBugFixTicket5921_UseCase8() throws Exception {
    // Start two servers
    // startClientVMs(1);
    startServerVMs(2, 0, null);
    createDiskStore(false, 1);
    serverSQLExecute(1, "create table TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, "
        + "ADDRESS varchar(1024) not null, "
        + "primary key (ID)) PARTITION BY COLUMN ( ID )");
    // Insert values 1 to 4
    for (int i = 0; i <= 3; ++i) {
      serverSQLExecute(1, "insert into TESTTABLE values (" + (i + 1)
          + ", 'First1" + (i + 1) + "', 'J1 604')");
    }

    SerializableRunnable queryExecutor = new SerializableRunnable(
        "BugVerifier") {
      @Override
      public void run() throws CacheException {
        GemFireXDQueryObserver sqo = null;
        try {
          // Obtain a new connection
          final Connection conn = TestUtil.getConnection();
          final long connID = ((EmbedConnection)conn).getConnectionID();
          final Object lock = new Object();
          final Object testEndLock = new Object();
          final EmbedPreparedStatement eps2 = (EmbedPreparedStatement)conn
              .prepareStatement("select * from TESTTABLE  where ADDRESS = ?");
          final long stmtID = eps2.getID();
          eps2.setString(1, "J1 604");
          eps2.executeQuery();
          final boolean proceed[] = new boolean[] { false };
          final Throwable failure[] = new Throwable[1];
          final boolean connClosed[] = new boolean[] { false };
          final boolean executedOnce[] = new boolean[] { false };
          final Thread stmtCloseCheck = new Thread(new Runnable() {
            @Override
            public void run() {
              synchronized (lock) {
                try {
                  proceed[0] = true;
                  lock.notify();
                  lock.wait();
                } catch (Throwable t) {
                  failure[0] = t;
                }
              }
            }
          });
          sqo = new GemFireXDQueryObserverAdapter() {
            @Override
            public void afterClosingWrapperPreparedStatement(
                long wrapperPrepStatementID, long wrapperConnectionID) {
              if (wrapperPrepStatementID == stmtID && !executedOnce[0]) {
                executedOnce[0] = true;
                stmtCloseCheck.start();
              }
            }

            @Override
            public void afterConnectionCloseByExecutorFunction(long[] connIDs) {
              boolean notify = false;
              for (long id : connIDs) {
                if (id == connID) {
                  notify = true;
                  break;
                }
              }
              if (notify) {
                synchronized (lock) {
                  lock.notify();
                }
                synchronized (testEndLock) {
                  connClosed[0] = true;
                  testEndLock.notify();
                }
              }
            }
          };
          GemFireXDQueryObserverHolder.setInstance(sqo);
          eps2.close();
          synchronized (lock) {
            if (!proceed[0]) {
              lock.wait();
            }
          }
          conn.close();
          synchronized (testEndLock) {
            if (!connClosed[0]) {
              testEndLock.wait();
            }
          }
          stmtCloseCheck.join();
          assertTrue(executedOnce[0]);
          assertTrue(connClosed[0]);
          if (failure[0] != null) {
            throw failure[0];
          }
        } catch (Throwable t) {
          throw GemFireXDRuntimeException.newRuntimeException(null, t);
        } finally {
          if (sqo != null) {
            GemFireXDQueryObserverHolder.removeObserver(sqo);
          }
        }
      }
    };
    VM dataStore1 = this.serverVMs.get(0);
    dataStore1.invoke(queryExecutor);
  }

  public void testMainQueryOnPRAndNonCorrelatedSubqueryOnReplicatedRegion_WhereClause()
      throws Exception {
    nestedQuerySetupForPR_Main_RR_Sub();
    String[] queries = new String[] { "select ID1, DESCRIPTION1 from TESTTABLE1"
        + " where ID1 IN ( Select AVG(ID2) from Testtable2 " +
        "where description2 = 'First26')" };
    String[] prepQueries = new String[] { "select ID1, DESCRIPTION1 from TESTTABLE1"
        + " where ID1 IN ( Select AVG(ID2) from Testtable2 " +
        "where description2 = ?)" };
    Object[] prepQueryArgs = new Object[] { "First24" };
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    String derbyDbUrl = "jdbc:derby:newDB;create=true;";
    if (currentUserName != null) {
      derbyDbUrl += ("user=" + currentUserName + ";password="
          + currentUserPassword + ';');
    }
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    Statement derbyStmt = derbyConn.createStatement();
    try {
      for (int i = 0; i < queries.length; ++i) {
        // final SelectQueryInfo[] sqiArr = new SelectQueryInfo[1];
        // NodesPruningHelper.setupObserverOnClient(sqiArr);
        String queryString = queries[i];
        TestUtil.validateResults(derbyStmt, stmt, queryString, false);
      }
      for (int i = 0; i < prepQueries.length; ++i) {
        // final SelectQueryInfo[] sqiArr = new SelectQueryInfo[1];
        // NodesPruningHelper.setupObserverOnClient(sqiArr);
        String queryString = prepQueries[i];
        PreparedStatement pstmt = conn.prepareStatement(queryString);
        pstmt.setObject(1, prepQueryArgs[i]);
        PreparedStatement derbyPstmt = derbyConn.prepareStatement(queryString);
        derbyPstmt.setObject(1, prepQueryArgs[i]);
        TestUtil.validateResults(derbyPstmt, pstmt, queryString, false);
      }
    } finally {
      cleanDerbyArtifacts(derbyStmt, derbyConn);
      try {
        clientSQLExecute(1, "drop table TESTTABLE1");
      } catch (SQLException ignore) {
      }

      try {
        clientSQLExecute(1, "drop table TESTTABLE2");
      } catch (SQLException ignore) {
      }

      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
    }
  }

  public void testBug41607() throws Exception {

    try {
      startVMs(1, 1);
      createDiskStore(true,1);
      final String queryStr = "Delete  from EMP.TESTTABLE "
          + "where DESCRIPTION  IN ('third','second','First') ";
      
      // Create the table and insert a row
      clientSQLExecute(1, "create table EMP.TESTTABLE (ID int , "
          + "DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024), "
          + "primary key (ID)) replicate " + getSuffix());


      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (1, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (2, 'First', 'J 604')");
      clientSQLExecute(1,
          "insert into EMP.TESTTABLE values (3, 'First', 'J 604')");
      // Execute the query on accessor.
      TestUtil.setupConnection();
      EmbedPreparedStatement eps = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(queryStr);
      /*eps.setString(1, "second");
      eps.setString(2, "third");
      eps.setString(3, "First");*/
      assertEquals(eps.executeUpdate(),3);

    }
    finally {
      //invokeInEveryVM(this.getClass(), "reset");
    }
  }

  public void __testMainQueryOnPRAndCorrelatedSubQueryOnRR_Unsupported_for_now()
      throws Exception
  {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    try {
      nestedQuerySetupForPR_Main_RR_Sub();
      TestUtil.setupConnection();

      String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ( Select AVG(ID2) from Testtable2 where ID2 > ID1)";
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      TestUtil.setupConnection();

      clientSQLExecute(1, query, false, true, false);
      fail("should have thrown Exception");
    }
    catch (SQLException sqle) {
      // Ok
    }
    finally {
 
      cleanDerbyArtifacts(derbyStmt, derbyConn);
      try {   
        clientSQLExecute(1, "drop table TESTTABLE1");
      }catch(SQLException ignore) {};
      
      try {   
        clientSQLExecute(1, "drop table TESTTABLE2");
      }catch(SQLException ignore) {};
      
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
    }

  }

  private void nestedQuerySetupForPR_Main_RR_Sub() throws Exception
  {
    // Create a table from client using partition by column
    // Start one client and three servers
    startVMs(1, 3);
    createDiskStore(true,1);
    String table1DDL = "create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, "
        + "primary key (ID1))";
    String table2DDL = "create table TESTTABLE2 (ID2 int not null, "
        + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null,"
        + " primary key (ID2))";
    String derbyDbUrl = "jdbc:derby:newDB;create=true;";
    if (currentUserName != null) {
      derbyDbUrl += ("user=" + currentUserName + ";password="
          + currentUserPassword + ';');
    }
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    Statement derbyStmt = derbyConn.createStatement();
    derbyStmt.execute(table1DDL);
    derbyStmt.execute(table2DDL);

    clientSQLExecute(1, table1DDL + "PARTITION BY COLUMN ( ID1 )"
        + getSuffix());
    clientSQLExecute(1, table2DDL + "replicate " + getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i <= 8; ++i) {
      String insertT1 = "insert into TESTTABLE1 values (" + (i + 1)
          + ", 'First1" + (i + 1) + "', 'J1 604')";
      String insertT2 = "insert into TESTTABLE2 values (" + (i + 1)
          + ", 'First2" + (i + 1) + "', 'J2 604')";
      clientSQLExecute(1, insertT1);
      clientSQLExecute(1, insertT2);
      derbyStmt.execute(insertT1);
      derbyStmt.execute(insertT2);
      }
  }

  private void cleanDerbyArtifacts(Statement derbyStmt, Connection conn)
      throws Exception {
    if (derbyStmt != null) {
      final String[] tables = new String[] { "testtable", "testtable1",
          "testtable2", "testtable3" };
      for (String table : tables) {
        try {
          derbyStmt.execute("drop table " + table);
        } catch (SQLException ex) {
          // deliberately ignored
        }
      }
    }
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException ex) {
        // deliberately ignored
      }
    }
  }

  public void testBug47299() throws Exception {

    startVMs(2, 1);
    createDiskStore(true,1);
    
    // Create the table and insert a row
    clientSQLExecute(1, "create table oorder (cid       integer      not null "
        + " ,sid       integer " + " ,did       integer "
        + " ,price       integer " + " ,type       integer " + " ,qty integer "
        + ") replicate " + getSuffix());

    {
      final int type = 5;
      final int price = 2;
      for (int i = 1; i <= 2; i++) {
        for (int j = 1; j <= 5; j++) {
          for (int k = 1; k <= 10; k++) {
            clientSQLExecute(1, "Insert into  oorder values(" + i + "," + j
                + "," + k + "," + price + "," + type + "," + i + ")");
          }
        }
      }
    }

    clientExecute(2, new SerializableCallable("Client 2") {
      @Override
      public Object call() throws CacheException {
        String query = "select sid, count(*) from oorder  where cid>? and sid<? and did =? GROUP BY sid HAVING count(*) >=1";
        EmbedPreparedStatement ps1;
        try {
          ps1 = (EmbedPreparedStatement)TestUtil.jdbcConn
              .prepareStatement(query);
          ps1.setInt(1, 1);
          ps1.setInt(2, 2);
          ps1.setInt(3, 3);
          ResultSet rs = ps1.executeQuery();
          int count = 0;
          while (rs.next()) {
            assertEquals(1, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
            count++;
          }
          assertEquals(1, count);
        } catch (SQLException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        return null;
      }
    });

    // Execute the query on accessor.
    TestUtil.setupConnection();
    {
      String query = "select sid, did from oorder  where cid>? and sid<? GROUP BY sid, did  order by (sid+did)";
      EmbedPreparedStatement ps1 = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(query);
      {
        ps1.setInt(1, 1);
        ps1.setInt(2, 2);
        ResultSet rs = ps1.executeQuery();
        int count = 0;
        while (rs.next()) {
          assertEquals(1, rs.getInt(1));
          count++;
        }
        assertEquals(10, count);
      }
    }
    {
      String query = "select sid, count(*) from oorder  where cid>? and sid<? and did =? GROUP BY sid HAVING count(*) >=1";
      EmbedPreparedStatement ps1 = (EmbedPreparedStatement)TestUtil.jdbcConn
          .prepareStatement(query);
      {
        ps1.setInt(1, 1);
        ps1.setInt(2, 3);
        ps1.setInt(3, 3);
        ResultSet rs = ps1.executeQuery();
        int count = 1;
        while (rs.next()) {
          assertEquals(count, rs.getInt(1));
          assertEquals(1, rs.getInt(2));
          count++;
        }
        assertEquals(3, count);
      }
    }
  }
  
  public void testMainQueryOnPRAndNonCorrelatedSubqueryOnReplicatedRegion_WhereClause_Unsupported_For_Now_ServerGrp_Mismatch()
      throws Exception
  {
    // Create a table from client using partition by column
    // Start one client and three servers
    startClientVMs(1, 0, null);
    startServerVMs(2, -1, "SG1");
    startServerVMs(1, -1, "SG1,SG2");
    createDiskStore(true,1);
    String table1DDL = "create table TESTTABLE1 (ID1 int not null, "
        + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, "
        + "primary key (ID1))";
    String table2DDL = "create table TESTTABLE2 (ID2 int not null, "
        + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null,"
        + " primary key (ID2))";
    String derbyDbUrl = "jdbc:derby:newDB;create=true;";
    if (currentUserName != null) {
      derbyDbUrl += ("user=" + currentUserName + ";password="
          + currentUserPassword + ';');
    }
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    Statement derbyStmt = derbyConn.createStatement();
    derbyStmt.execute(table1DDL);
    derbyStmt.execute(table2DDL);

    clientSQLExecute(1, table1DDL + "PARTITION BY COLUMN ( ID1 )"
        + "server groups (SG1, SG2)" + getSuffix());
    clientSQLExecute(1, table2DDL + "replicate " + "server groups ( SG2)"
        + getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i <= 8; ++i) {
      String insertT1 = "insert into TESTTABLE1 values (" + (i + 1)
          + ", 'First1" + (i + 1) + "', 'J1 604')";
      String insertT2 = "insert into TESTTABLE2 values (" + (i + 1)
          + ", 'First2" + (i + 1) + "', 'J2 604')";
      clientSQLExecute(1, insertT1);
      clientSQLExecute(1, insertT2);
      derbyStmt.execute(insertT1);
      derbyStmt.execute(insertT2);
    }
    String[] queries = new String[] { "select ID1, DESCRIPTION1 from TESTTABLE1 where" +
    		" ID1 IN ( Select AVG(ID2) from Testtable2 where description2 = 'First26')  "

    };

    TestUtil.setupConnection();
    Statement stmt = TestUtil.getConnection().createStatement();
    try {
      for (int i = 0; i < queries.length; ++i) {
        // final SelectQueryInfo[] sqiArr = new SelectQueryInfo[1];
        // NodesPruningHelper.setupObserverOnClient(sqiArr);
        String queryString = queries[i];
        try {
          stmt.executeQuery(queryString);
          fail("the query should not have executed successfully");
        }
        catch (SQLException sqle) {
          // Ok.       
        }

      }
    }
    finally {

      cleanDerbyArtifacts(derbyStmt, derbyConn);
      try {   
        clientSQLExecute(1, "drop table TESTTABLE1");
      }catch(SQLException ignore) {};
      
      try {   
        clientSQLExecute(1, "drop table TESTTABLE2");
      }catch(SQLException ignore) {};
      
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }    
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      
    }
  }

  public void testBug42840_45715() throws Exception {
    startVMs(1, 1);

    clientSQLExecute(1, "create table trade.buyordersRep(oid int not null "
        + "constraint buyorders_pk primary key, cid int, sid int, "
        + "qty int, bid decimal (30, 20), status varchar(10), tid int) "
        + "replicate");
    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    // s
    // .execute("create table customers (cid int not null, cust_name varchar(100),"
    // + "  tid int, primary key (cid))");
    // s
    // .execute("create table securities (sec_id int not null, symbol varchar(10) not null, "
    // +
    // "price int,  tid int, constraint sec_pk primary key (sec_id)  )  replicate");
    // s
    // .execute("create table portfolio (cid int not null, sid int not null, qty int not null,"
    // +
    // " availQty int not null,tid int, constraint portf_pk primary key (cid, sid), "
    // +
    // "constraint cust_fk foreign key (cid) references customers (cid) on delete restrict,"
    // +
    // " constraint sec_fk foreign key (sid) references securities (sec_id) )   "
    // + "partition by column (cid) colocate with (customers)");

    s.execute("create table buyorders(oid int not null "
        + "constraint buyorders_pk primary key, cid int, sid int, "
        + "qty int, bid decimal (30, 20), status varchar(100),tid int) "
        + "partition by column (sid)");

    String query1 = "select * from buyorders  where status =? and "
        + "tid  >= ? ";

    for (int i = 0; i < 15; ++i) {
      s.executeUpdate("insert into buyorders values (" + i + "," + i + "," + i
          + "," + (10 * i) + "," + (101.75 * i) + ",'filled'," + i + ")");
    }
    for (int i = 0; i < 15; ++i) {
      s.executeUpdate("insert into trade.buyordersRep values (" + i + "," + i
          + "," + i + "," + (10 * i) + "," + (101.75 * i) + ",'filled'," + i
          + ")");
    }
//    stopServerVMNums(1);
//    stopServerVMNums(2);   
    try {      
      PreparedStatement ps = conn.prepareStatement(query1);
      ps.setString(1, "filled");
      ps.setInt(2, 0);      
      ResultSet rs = ps.executeQuery();

      final long connectionID = ((EmbedConnection)conn).getConnectionID();
      final long prepStatementID = ((EmbedPreparedStatement)ps).getID();
      // Use these to replace the parameter value object used for reading on the
      // data store node
      SerializableRunnable sr = getExecutorToManipulatePVS(connectionID,
          prepStatementID);
      // Rexecute the query;
      serverExecute(1, sr);
      ps.setString(1, "filled");
      ps.setInt(2, 0);
      addExpectedException(null, new int[] { 1 },
          com.gemstone.gemfire.cache.CacheClosedException.class);
      rs = ps.executeQuery();
      int numRes = 0;
      while (rs.next()) {
        ++numRes;
      }
      assertEquals(15, numRes);
      removeExpectedException(null, new int[] { 1 },
          com.gemstone.gemfire.cache.CacheClosedException.class);

      // check the query for #45715
      rs = s.executeQuery("select cid, max(qty*bid) as largest_order from "
          + "trade.buyordersRep where status = 'filled' GROUP BY cid HAVING "
          + "max(qty*bid) > 4000 ORDER BY max(qty*bid) DESC");
      numRes = 14;
      while (rs.next()) {
        assertEquals(numRes, rs.getInt(1));
        assertEquals(new BigDecimal(numRes * numRes * 10).multiply(
            new BigDecimal("101.75")).setScale(20), rs.getBigDecimal(2));
        numRes--;
      }
      assertEquals(1, numRes);
      rs = s.executeQuery("select cid, max(qty*bid) as largest_order from "
          + "buyorders where status = 'filled' GROUP BY cid HAVING "
          + "max(qty*bid) > 4000 ORDER BY max(qty*bid) DESC");
      numRes = 14;
      while (rs.next()) {
        assertEquals(numRes, rs.getInt(1));
        assertEquals(new BigDecimal(numRes * numRes * 10).multiply(
            new BigDecimal("101.75")).setScale(20), rs.getBigDecimal(2));
        numRes--;
      }
      assertEquals(1, numRes);
      // new query
      rs = s.executeQuery("select cid, max(qty*bid) as largest_order from "
              + "buyorders where status = 'filled' and tid=5 GROUP BY cid HAVING "
              + "max(qty*bid) > 4000 ORDER BY max(qty*bid) DESC");
      numRes = 5;
      while (rs.next()) {
          assertEquals(numRes, rs.getInt(1));
          assertEquals(new BigDecimal(numRes * numRes * 10).multiply(
              new BigDecimal("101.75")).setScale(20), rs.getBigDecimal(2));
          numRes--;
      }
      assertEquals(4, numRes);
      // above query replicated
      rs = s.executeQuery("select cid, max(qty*bid) as largest_order from "
              + "trade.buyordersRep where status = 'filled' and tid=5 GROUP BY cid HAVING "
              + "max(qty*bid) > 4000 ORDER BY max(qty*bid) DESC");
      numRes = 5;
      while (rs.next()) {
          assertEquals(numRes, rs.getInt(1));
          assertEquals(new BigDecimal(numRes * numRes * 10).multiply(
              new BigDecimal("101.75")).setScale(20), rs.getBigDecimal(2));
          numRes--;
      }
      assertEquals(4, numRes);
      // above query prepare stmt
      numRes = 5;
      query1 = "select cid, max(qty*bid) as largest_order from "
              + "buyorders where status=? and tid=? GROUP BY cid HAVING "
              + "max(qty*bid) > 4000 ORDER BY max(qty*bid) DESC";
      ps = conn.prepareStatement(query1);
      ps.setString(1, "filled");
      ps.setInt(2, numRes);      
      rs = ps.executeQuery();
      while (rs.next()) {
          assertEquals(numRes, rs.getInt(1));
          assertEquals(new BigDecimal(numRes * numRes * 10).multiply(
              new BigDecimal("101.75")).setScale(20), rs.getBigDecimal(2));
          numRes--;
      }
      assertEquals(4, numRes);
      // above query prep stmt and replicated
      numRes = 5;
      query1 = "select cid, max(qty*bid) as largest_order from "
              + "trade.buyordersRep where status=? and tid=? GROUP BY cid HAVING "
              + "max(qty*bid) > 4000 ORDER BY max(qty*bid) DESC";
      ps = conn.prepareStatement(query1);
      ps.setString(1, "filled");
      ps.setInt(2, numRes);      
      rs = ps.executeQuery();
      while (rs.next()) {
          assertEquals(numRes, rs.getInt(1));
          assertEquals(new BigDecimal(numRes * numRes * 10).multiply(
              new BigDecimal("101.75")).setScale(20), rs.getBigDecimal(2));
          numRes--;
      }
      assertEquals(4, numRes);
      
      conn.close(); // connection close
      
      // re-execute above query prep stmt and replicated; verify query cache
      numRes = 5;
      conn = TestUtil.getConnection();
      ps = conn.prepareStatement(query1);
      ps.setString(1, "filled");
      ps.setInt(2, numRes);      
      rs = ps.executeQuery();
      while (rs.next()) {
          assertEquals(numRes, rs.getInt(1));
          assertEquals(new BigDecimal(numRes * numRes * 10).multiply(
              new BigDecimal("101.75")).setScale(20), rs.getBigDecimal(2));
          numRes--;
      }
      assertEquals(4, numRes);
      
      conn.close(); // connection close
    } // fail("Should have got an Exception");
    finally {
      s = TestUtil.getConnection().createStatement();
      s.execute("drop table buyorders");
      s.execute("drop table trade.buyordersRep");
    }
  }

  protected static SerializableRunnable getExecutorToManipulatePVS(final long connectionID,
      final long statementID) {
    SerializableRunnable pvsManip = new SerializableRunnable(
        "PVS manipulator") {
      @Override
      public void run() throws CacheException {
        GfxdConnectionHolder holder = GfxdConnectionHolder.getHolder();
        EmbedPreparedStatement ps = (EmbedPreparedStatement)holder
            .getExistingWrapper(connectionID).getStatementForTEST(statementID);
        final GenericParameterValueSet pvs = (GenericParameterValueSet)ps
            .getParameterValueSet();

        final GenericParameter gp = pvs.getGenericParameter(0);
        final SQLVarchar sqlchar = new SQLVarchar("filled");
        DataValueDescriptor myDVD = new DataType() {
          @Override
          public final void fromDataForOptimizedResultHolder(DataInput dis)
              throws IOException, ClassNotFoundException {
            // reset the DVD to corect type
            gp.initialize(new SQLVarchar());
            try {
              Thread.sleep(5000);
            } catch (InterruptedException e) {
              throw new GemFireXDRuntimeException(e);
            }
            throw new CacheClosedException();
          }

          @Override
          public int compare(DataValueDescriptor other)
              throws StandardException {
            return sqlchar.compare(other);
          }

          @Override
          public int estimateMemoryUsage() {
            return 0;
          }

          @Override
          public DataValueDescriptor getClone() {
            return this;
          }

          @Override
          public int getLength() throws StandardException {
            return sqlchar.getLength();
          }

          @Override
          public DataValueDescriptor getNewNull() {
            return null;
          }

          @Override
          public String getString() throws StandardException {
            return "filled";
          }

          @Override
          public String getTypeName() {

            return sqlchar.getTypeName();
          }

          @Override
          public void readExternalFromArray(ArrayInputStream ais)
              throws IOException, ClassNotFoundException {
            throw new CacheClosedException();
          }

          @Override
          public void setValueFromResultSet(ResultSet resultSet, int colNumber,
              boolean isNullable) throws StandardException, SQLException {
          }

          @Override
          public boolean isNull() {
            return false;
          }

          @Override
          public void restoreToNull() {
          }

          @Override
          public void readExternal(ObjectInput in) throws IOException,
              ClassNotFoundException {
          }

          @Override
          public void writeExternal(ObjectOutput out) throws IOException {
          }

          @Override
          public int getTypeFormatId() {
            return sqlchar.getTypeFormatId();
          }

          @Override
          public int writeBytes(byte[] outBytes, int offset,
              DataTypeDescriptor dtd) {
            throw new UnsupportedOperationException("unexpected invocation");
          }

          @Override
          public int computeHashCode(int maxWidth, int hash) {
            throw new UnsupportedOperationException("unexpected invocation");
          }

          

          @Override
          public void toDataForOptimizedResultHolder(DataOutput dos)
              throws IOException {
            throw new UnsupportedOperationException("unexpected invocation");
          }

          @Override
          public int readBytes(byte[] inBytes, int offset, int columnWidth) {
            throw new UnsupportedOperationException("Not expected to be invoked for "
                + getClass());
          }

          @Override
          public int readBytes(long memOffset, int columnWidth, ByteSource bs) {
            throw new UnsupportedOperationException("Not expected to be invoked for "
                + getClass());
          }
        };
        gp.initialize(myDVD);
      }
    };
    return pvsManip;
  }

  public void testStmntMapCleanupInGfxdConnWrapper() throws Exception {
    startVMs(1, 1);
    
    Connection qConn = TestUtil.getConnection();
    Statement s = qConn.createStatement();
    s.execute("create table TESTTABLE (ID int not null, "
            + " DESCRIPTION varchar(1024) not null, ADDRESS varchar(1024) not null,"
            + " primary key (ID)) PARTITION BY COLUMN ( ID )");
    for (int i = 0; i <= 8; ++i) {
      s.execute("insert into TESTTABLE values (" + (i + 1)
          + ", 'First1" + (i + 1) + "', 'J1 604')");
    }
    String[] queries = { "select * from testtable where id > ?",
        "select DESCRIPTION, ADDRESS from testtable where id < ?" };

    final PreparedStatement ps1 = qConn.prepareStatement(queries[0]);
    final PreparedStatement ps2 = qConn.prepareStatement(queries[1]);
    ps1.setInt(1, 6);
    ps2.setInt(1, 6);
    ResultSet rs = ps1.executeQuery();
    int cnt = 0;
    while (rs.next()) {
      cnt++;
    }
    assertEquals(3, cnt);
    cnt = 0;
    rs = ps2.executeQuery();
    while (rs.next()) {
      cnt++;
    }
    assertEquals(5, cnt);

    WaitCriterion wc = new WaitCriterion() {
      Integer size;

      @Override
      public boolean done() {
        VM serverVM = serverVMs.get(0);
        this.size = (Integer)serverVM.invoke(PreparedStatementDUnit.class,
            "verifyWrapperSize", new Object[] { Integer.valueOf(1) });
        return this.size != null && this.size.intValue() < 2;
      }

      @Override
      public String description() {
        return "expected size of statement map to be < 2 but got " + this.size;
      }
    };
    // below rarely passes whatever one does in CC runs so no longer throwing
    // exception on timeout
    waitForCriterion(wc, 30000, 500, false);
  }

  public static void invokeGC() {
    System.gc();
    System.runFinalization();
  }

  public static int verifyWrapperSize(int expectedSize) throws SQLException {
    int mapSize = 0;
    for (int i = 1; i <= 5; i++) {
      if (i > 1) {
        // increase the memory pressure to force a GC
        long dummy = 0;
        for (int j = 1; j <= 100000; j++) {
          Object[] obj = new Object[50];
          dummy += obj.hashCode();
        }
        getGlobalLogger().info("statement not GCed, dummy=" + dummy);
      }
      invokeGC();
      GfxdConnectionHolder holder = GfxdConnectionHolder.getHolder();
      ConcurrentMap<Long, GfxdConnectionWrapper> idToConnMap = holder
          .getWrapperMap();
      assertNotNull(idToConnMap);
      // now there is one for GFXD mbean service
      GfxdConnectionWrapper wrapper = null;
      InternalManagementService ims = InternalManagementService
          .getAnyInstance();
      for (GfxdConnectionWrapper o : idToConnMap.values()) {
        if (ims != null && o == ims.getConnectionWrapperForTEST()) {
          continue;
        }
        if (wrapper == null) {
          wrapper = o;
        }
        else {
          fail("found more than one user connection wrappers");
        }
      }
      if (wrapper == null) {
        // can happen if the connection got GCed
        //fail("no user connection wrapper found");
        return 0;
      }

      LongObjectHashMap<GfxdConnectionWrapper.StmntWeakReference> stmntMap =
          wrapper.getStatementMapForTEST();
      // invoke a getStatement to force drain refQueue
      final GfxdConnectionWrapper connWrapper = wrapper;
      LongIterator keys = stmntMap.keySet().longIterator();
      if (keys.hasNext()) {
        EmbedConnection conn = connWrapper.getConnectionForSynchronization();
        synchronized (conn.getConnectionSynchronization()) {
          connWrapper.getStatement(null, keys.next(), true, false, false,
              false, null, false, 0, 0);
        }
      }
      mapSize = stmntMap.size();
      if (mapSize <= expectedSize) {
        return mapSize;
      }
    }
    return mapSize;
  }

  public void __testNonCorrelatedSubqueryOnPartitionedRegion() throws Exception
  {
    // Create a table from client using partition by column
    // Start one client and three servers
    startVMs(1, 3);

    clientSQLExecute(
        1,
        "create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )" + getSuffix());
    clientSQLExecute(
        1,
        "create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, " +
            		"primary key (ID2))  "
            + getSuffix());

    // Insert values 1 to 8
    for (int i = 0; i <= 8; ++i) {
      clientSQLExecute(1, "insert into TESTTABLE1 values (" + (i + 1)
          + ", 'First1" + (i + 1) + "', 'J1 604')");
      clientSQLExecute(1, "insert into TESTTABLE2 values (" + (i + 1)
          + ", 'First2" + (i + 1) + "', 'J2 604')");
    }
    String[] queries = new String[] { "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ( Select AVG(ID2) from Testtable2 where description2 = 'First26')  "

    };

    TestUtil.setupConnection();
    try {
      for (int i = 0; i < queries.length; ++i) {
        // final SelectQueryInfo[] sqiArr = new SelectQueryInfo[1];
        // NodesPruningHelper.setupObserverOnClient(sqiArr);
        String queryString = queries[i];
        try {
          clientSQLExecute(1, queryString, false, true, false);
          fail("Test should fail");
        }catch(SQLException sqle) {
          assertTrue(sqle.getMessage().indexOf("PR based subquery not supported") != -1);
        }
      }
    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      // clientSQLExecute(1, "Drop table TESTTABLE1 ");
      clientSQLExecute(1, "Drop table TESTTABLE2 ");
      clientSQLExecute(1, "Drop table TESTTABLE1 ");
    }
  }

  public void __testCorrelatedSubQueryOnPRUnsupported() throws Exception
  {

    // Create a table from client using partition by column
    // Start one client and three servers
    startVMs(1, 3);

    clientSQLExecute(
        1,
        "create table TESTTABLE1 (ID1 int not null, "
            + " DESCRIPTION1 varchar(1024) not null, ADDRESS1 varchar(1024) not null, primary key (ID1))"
            + "PARTITION BY COLUMN ( ID1 )" + getSuffix());
    clientSQLExecute(
        1,
        "create table TESTTABLE2 (ID2 int not null, "
            + " DESCRIPTION2 varchar(1024) not null, ADDRESS2 varchar(1024) not null, primary key (ID2)) " +
            		"partition by column( ID2)  colocate with (TESTTABLE1) "
            + getSuffix());
    try {
      // Insert values 1 to 8
      for (int i = 0; i <= 8; ++i) {
        clientSQLExecute(1, "insert into TESTTABLE1 values (" + (i + 1)
            + ", 'First1" + (i + 1) + "', 'J1 604')");
        clientSQLExecute(1, "insert into TESTTABLE2 values (" + (i + 1)
            + ", 'First2" + (i + 1) + "', 'J2 604')");
      }
      String query = "select ID1, DESCRIPTION1 from TESTTABLE1 where ID1 IN ( Select ID2 from Testtable2 where description2 = description1)";

      TestUtil.setupConnection();
      try {
        clientSQLExecute(1, query, false, true, false);
        fail("should have thrown Exception");
      }
      catch (SQLException sqle) {
        // Ok
      }

    }
    finally {
      GemFireXDQueryObserverHolder
          .setInstance(new GemFireXDQueryObserverAdapter());
      // clientSQLExecute(1, "Drop table TESTTABLE1 ");
      clientSQLExecute(1, "Drop table TESTTABLE2 ");
      clientSQLExecute(1, "Drop table TESTTABLE1 ");
    }
  }

  public static final void reset() {
    connID = -1;
    stmtID = -1;
    sql = "";
    executionUsingGemFireXDActivation = false;
    connectionCloseExecuted = false;
    GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter());
  }

  @Override
  protected void vmTearDown() throws Exception {
    super.vmTearDown();
    reset();
  }

  public String getSuffix() throws Exception
  {
    return " ";
  }
  
  public void createDiskStore(boolean useClient, int vmNum) throws Exception {    
  }

}
