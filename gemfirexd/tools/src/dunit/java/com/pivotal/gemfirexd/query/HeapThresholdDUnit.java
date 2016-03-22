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

package com.pivotal.gemfirexd.query;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.control.ResourceAdvisor;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.query.HeapThresholdHelper;
import com.pivotal.gemfirexd.internal.engine.distributed.query.HeapThresholdHelper.PauseVariants;
import com.pivotal.gemfirexd.internal.engine.distributed.query.HeapThresholdHelper.QueryExecutor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.dunit.standalone.DUnitBB;

@SuppressWarnings("serial")
public class HeapThresholdDUnit extends DistributedSQLTestBase {

  private static final Map<String, String> queryExecutionErrorStatus =
      new HashMap<>();

  public HeapThresholdDUnit(String name) {
    super(name);
  }

  @Override
  protected void vmTearDown() throws Exception {
    queryExecutionErrorStatus.clear();
    super.vmTearDown();
  }

  @Override
  protected String reduceLogging() {
    // these tests generate lots of logs, so reducing them
    return "config";
  }

  public void testBug41438() throws Exception {

      int netPort = startNetworkServer(1, null, null);

      // Use this VM as the network client
      Connection conn = TestUtil.getNetConnection(netPort, null, null);

      startServerVMs(2, 0, null);

      TestUtil.jdbcConn = conn;

      assertTrue("Connection shouldn't be null", conn != null);
      
      HeapThresholdHelper.prepareTables(conn);
      conn.close();

      stopNetworkServer(1);
      stopVMNums(-2, -3);
      TestUtil.shutDown();

      restartVMNums(-1, -2, -3);
      netPort = startNetworkServer(1, null, null);

      // Use this VM as the network client
      conn = TestUtil.getNetConnection(netPort, null, null);

      TestUtil.jdbcConn = conn;

      assertTrue("Connection shouldn't be null", conn != null);

      HeapThresholdHelper.prepareTables(conn);
      conn.close();
  }

  public void testQueryCancelation() throws Exception {
    startVMs(1, 3);

    HeapThresholdHelper.prepareTables(null);
    
    final String[] queries = new String[] {
        "Select ID1 from testtable1 where id1 > 12"
       ,"Select * from testtable1 where id1 > 10"
       ,"Select ID1 from testtable1 where id1 > 9 order by description1"
       ,"Select ID1 from testtable1 order by description1"
       ,"Select ID1, ADDRESS1 from testtable1 order by address1"
       ,"Select ID1 from testtable1 t1 join testtable2 t2 on t1.id1 = t2.id2"
       ,"Select t1.id1, t2.id2 from testtable1 t1 left outer join testtable2 t2 on t1.id1 = t2.id2"
       ,"Select * from testtable1 t1 join testtable2 t2 on t1.id1 = t2.id2"
    };
    
    QueryExecutor executors[] = new DistributedTestQueryExecutor[queries.length];
    
    VM pausevm = this.serverVMs.get(0);
    // add expected exceptions
    Object[] expectedEx = new Object[] { SQLException.class,
        "heap critical threshold" };
    addExpectedException(null, expectedEx);
    addExpectedException(pausevm, expectedEx);
    Thread executionThrds[] = new Thread[queries.length];
    try {
        for(int i = 0; i < queries.length; i++) {
        executors[i] = new DistributedTestQueryExecutor(0, queries[i],
            PauseVariants.PAUSE_AFTER_QUERY_EXECUTE, null); //null constantList because first time won't have pattern matching ?
          executionThrds[i] = executeQueryInVM(pausevm, executors[i]);
        }

        dumpSharedMap("Going to wait for compilation ");
        for(QueryExecutor executor : executors) {
          executor.waitForCompilation();
        }
        
        dumpSharedMap("Done Waiting for compilation ");
        getLogWriter().info("About to raise CRITICAL_UP event in VM " + pausevm.getPid());
        pausevm.invoke(HeapThresholdHelper.class, "raiseMemoryEvent", new Object[] {true, false});

        // wait for memory event to be propagated
        waitForCriticalUpMembers(10000);

        for(QueryExecutor exec1 : executors) {
          exec1.notifyMemoryEvent();
        }
        
        dumpSharedMap("Notified Memory Status ");
        for(Thread t : executionThrds ) { 
          getLogWriter().info("Waiting for thread " + t.getName());
          t.join();
        }
        
        dumpSharedMap("Done Memory Status processing ");
        
        getLogWriter().info("About to raise CRITICAL_DOWN event in VM " + pausevm.getPid());
        pausevm.invoke(HeapThresholdHelper.class, "raiseMemoryEvent", new Object[] {false, true});
        
        if(queryExecutionErrorStatus.size() > 0) {
          for( Iterator<Entry<String, String>> iter = queryExecutionErrorStatus.entrySet().iterator() 
                ;
                iter.hasNext();
             )
          {
            Entry<String, String> mapEnt = iter.next();
            getLogWriter().error("Query " + mapEnt.getKey() + " FailMessage " + mapEnt.getValue());
          }
          fail("Execution failed with errors: " + queryExecutionErrorStatus);
        }
    }
    finally {
      pausevm.invoke(new SerializableRunnable("reset observer") 
                {
                    @Override
                    public void run() throws CacheException {
                      try {
                        GemFireXDQueryObserverHolder
                            .setInstance(new GemFireXDQueryObserverAdapter());
                      }
                      catch (Exception e) {
                        throw new CacheException(e) {
                        };
                      }
                    }
                  });
      removeExpectedException(pausevm, expectedEx);
      removeExpectedException(null, expectedEx);
    }
  }

  public void testNetworkQueryCancelation() throws Exception {

    final int netPort = startNetworkServer(1, null, null);

    final Connection conn = TestUtil.getNetConnection(netPort, null, null);

    startServerVMs(2, 0, null);
    
    TestUtil.jdbcConn = conn;

    assertTrue("Connection shouldn't be null", conn != null);
    
    HeapThresholdHelper.prepareTables(conn);
    
    
    final String[] queries = new String[] {
        "Select ID1 from testtable1 where id1 > 12"
       ,"Select * from testtable1 where id1 > 10"
       ,"Select ID1 from testtable1 where id1 > 9 order by description1"
       ,"Select ID1 from testtable1 order by description1"
       ,"Select ID1, ADDRESS1 from testtable1 order by address1"
       ,"Select ID1 from testtable1 t1 join testtable2 t2 on t1.id1 = t2.id2"
       ,"Select t1.id1, t2.id2 from testtable1 t1 left outer join testtable2 t2 on t1.id1 = t2.id2"
       ,"Select * from testtable1 t1 join testtable2 t2 on t1.id1 = t2.id2"
    };
    
    QueryExecutor executors[] = new DistributedTestQueryExecutor[queries.length];
    
    VM pausevm = this.serverVMs.get(1);
    // add expected exceptions
    Object[] expectedEx = new Object[] { SQLException.class,
        "heap critical threshold" };
    addExpectedException(null, expectedEx);
    addExpectedException(pausevm, expectedEx);
    Thread executionThrds[] = new Thread[queries.length];
    try {
        for(int i = 0; i < queries.length; i++) {
        executors[i] = new DistributedTestQueryExecutor(netPort, queries[i],
            PauseVariants.PAUSE_AFTER_QUERY_EXECUTE, null); //null constantList because first time won't have pattern matching ?
          executionThrds[i] = executeQueryInVM(pausevm, executors[i]);
        }

        dumpSharedMap("Going to wait for compilation ");
        for(QueryExecutor executor : executors) {
          executor.waitForCompilation();
        }

        dumpSharedMap("Done Waiting for compilation ");
        getLogWriter().info("About to raise CRITICAL_UP event in VM " + pausevm.getPid());
        pausevm.invoke(HeapThresholdHelper.class, "raiseMemoryEvent", new Object[] {true, false});

        // wait for memory event to be propagated
        waitForCriticalUpMembers(5000);

        for(QueryExecutor exec1 : executors) {
          exec1.notifyMemoryEvent();
        }
        
        dumpSharedMap("Notified Memory Status ");
        for(Thread t : executionThrds ) { 
          getLogWriter().info("Waiting for thread " + t.getName());
          t.join();
        }
        
        dumpSharedMap("Done Memory Status processing ");
        
        getLogWriter().info("About to raise CRITICAL_DOWN event in VM " + pausevm.getPid());
        pausevm.invoke(HeapThresholdHelper.class, "raiseMemoryEvent", new Object[] {false, true});
        
        if(queryExecutionErrorStatus.size() > 0) {
          for( Iterator<Entry<String, String>> iter = queryExecutionErrorStatus.entrySet().iterator() 
                ;
                iter.hasNext();
             )
          {
            Entry<String, String> mapEnt = iter.next();
            getLogWriter().error("Query " + mapEnt.getKey() + " FailMessage " + mapEnt.getValue());
          }
          fail("Execution failed with errors: " + queryExecutionErrorStatus);
        }
    }
    finally {
      pausevm.invoke(new SerializableRunnable("reset observer") 
                {
                    @Override
                    public void run() throws CacheException {
                      try {
                        GemFireXDQueryObserverHolder
                            .setInstance(new GemFireXDQueryObserverAdapter());
                      }
                      catch (Exception e) {
                        throw new CacheException(e) {
                        };
                      }
                    }
                  });
    }
    removeExpectedException(pausevm, expectedEx);
    removeExpectedException(null, expectedEx);
  }
  
  /**
   * ResultHolder buffer expansion should cancel the query.
   * @throws Exception 
   */
  public void testCancellationFromBufferExpansion() throws Exception {
    startVMs(1, 3);

    HeapThresholdHelper.prepareTables(null);
    
  }

  /** disabled due to hangs in this till we figure out the problem */
  public void DISABLED_testBug44265() throws Exception {

    // atleast 2 VMs need to respond for GfxdQueryStreamingResultCollector to return from GemFireDistributedResultSet#setup(Collection<?>)
    startVMs(1, 3);

    HeapThresholdHelper.prepareTables(null);

    VM pausevm1 = this.serverVMs.get(0);
    
    SerializableRunnable pauseQuery =  new SerializableRunnable() {
      public void run() {
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
              private String queryStr;

              @Override
              public boolean afterQueryExecution(CallbackStatement stmt,
                  SQLException sqle) {
                queryStr = stmt.getSQLText();
                TestUtil.getLogger().info(
                    "afterQueryExecution: honoring callback "
                        + stmt.getSQLText());
                while (true) {
                  try {
                    updateQueryStatus(QueryStatus.COMPILED, queryStr);                    
                    TestUtil.getLogger().info("Waiting forever .... ");
                    synchronized (this) {
                      this.wait();
                    }
                  } catch (InterruptedException e) {
                    break;
                  }
                }
                return false;
              }

              @Override
              public void onEmbedResultSetMovePosition(EmbedResultSet rs,
                  ExecRow newRow,
                  com.pivotal.gemfirexd.internal.iapi.sql.ResultSet theResults) {
              }

              @Override
              public long estimatedMemoryUsage(String stmtText, long memused) {
                TestUtil.getLogger().info(
                    "afterQueryExecution: estimated memory for " + stmtText
                        + " is " + memused);
                return memused;
              }
            });
      }
    };
    
    pausevm1.invoke(pauseQuery);
    
    final boolean[] hasMemoryEstimationTriggered = new boolean[] {false};
    final String queryStr = "Select ID1 from testtable1 where id1 > 1";
    GemFireXDQueryObserverHolder
    .setInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void estimatingMemoryUsage(String stmtText, Object resultSet) {
            if(!( resultSet instanceof GemFireDistributedResultSet) ) {
              TestUtil.getLogger().info(
                  " Not yet reached GemFireDistributedResultSet ... ", new Throwable() );
              return;
            }
            
            hasMemoryEstimationTriggered[0] = true;
            TestUtil.getLogger().info(
                "estimating memory on query node for " + stmtText);
          }
    });
    
    // add expected exceptions
    Object[] expectedEx = new Object[] { SQLException.class,
        "heap critical threshold", CacheClosedException.class };
    addExpectedException(null, expectedEx);
    addExpectedException(pausevm1, expectedEx);
    final Thread executionThread = new Thread(new Runnable() {
      public void run() {
        Connection c;
        try {
          c = TestUtil.getConnection();
          
          assertTrue(c instanceof EmbedConnection);
          
          EmbedConnection ec = (EmbedConnection)c;
          ec.getLanguageConnection().setEnableStreaming(true);
          PreparedStatement ps = c
              .prepareStatement(queryStr);
          ResultSet rs = ps.executeQuery();
          TestUtil.getLogger().info("Execute Query done... doing rs.next ");
          
          while (rs.next())
            ;
          rs.close();
        } catch (SQLException e) {
          if(!"08006".equals(e.getSQLState())) {
            fail("Execution failed", e);
          }
        }
      }
    });
    
    executionThread.start();

    waitForQueryStatus(QueryStatus.COMPILED, queryStr);
    try {
      TestUtil.getLogger().info("Raising critical_up memory event ");
      HeapThresholdHelper.raiseMemoryEvent(true, false);

      while(hasMemoryEstimationTriggered[0] == false) {
        Thread.sleep(100);
      }
      Thread.sleep(1000);
      
      TestUtil.getLogger().info("Shutting down the current VM ");
      stopVMNum(1);
    }
    finally {
    }

    TestUtil.getLogger().info(
        "Closing pending execution thread and exiting test. ");
    executionThread.interrupt();
    
    executionThread.join();

    removeExpectedException(null, expectedEx);
    removeExpectedException(pausevm1, expectedEx);
  }

  class QueryStatus {
    static final String INIT= "INIT";
    static final String COMPILED= "COMPILED";
    static final String EXECUTING= "EXECUTING";
    static final String DONE= "DONE";
  }

  class DistributedTestQueryExecutor extends HeapThresholdHelper.QueryExecutor
      implements Serializable {

      private final int netPort;

      DistributedTestQueryExecutor() {
        super();
        this.netPort = 0;
        useThreadLocal = false;
      }

      DistributedTestQueryExecutor(int netPort, String queryStr,
          PauseVariants variant, String[] constantList) {
        super(queryStr, variant, constantList);
        this.netPort = netPort;
        useThreadLocal = false;
      }

      @Override
      protected Connection getConnection() throws SQLException {
        if (this.netPort != 0) {
          return TestUtil.getNetConnection(this.netPort, null, null);
        }
        return super.getConnection();
      }

      @Override
      protected void setFailedStatus(String query, String failmsg) {
        addToExecutionErrorStatus(query, failmsg);
      }
  
      public void run() {
        try {
          execute(false);
        }
        catch (SQLException e) {
          e.printStackTrace();
        }
      }
      
     @Override
     public void waitForCompilation() throws InterruptedException {
       TestUtil.getLogger().info("Start waiting for compilation " + queryStr);
       waitForQueryStatus(QueryStatus.COMPILED, queryStr);
       TestUtil.getLogger().info("Done waiting for compilation " + queryStr);
     }
     
     @Override
     public void notifyCompilation() {
       TestUtil.getLogger().info("Done compilation of " + queryStr + " ");         
       updateQueryStatus(QueryStatus.COMPILED, queryStr);
     }
     
     @Override
     public void waitForMemoryEvent() {
       if(minRowsExpected != -1 && ++numOfNexts < minRowsExpected) {
         return;
       }

       notifyCompilation();
       TestUtil.getLogger().info("Start waiting for memory event from " +
           DUnitBB.getBB().get(queryStr) + " state " + queryStr);
       
       waitForQueryStatus(QueryStatus.EXECUTING, queryStr);
       
       TestUtil.getLogger().info("Done waiting for memory event " + queryStr);
     }
     
     @Override
     public void notifyMemoryEvent() {
       TestUtil.getLogger().info("Notifying memoryEvent for " + queryStr + " ");         
       updateQueryStatus(QueryStatus.EXECUTING, queryStr);
     }
     
  }

  public static void updateQueryStatus(String qs, String queryStr) {
    DUnitBB.getBB().put(queryStr, qs);
  }

  public static void waitForQueryStatus(String expectedStatus, String queryStr) {
    final long maxWait = System.currentTimeMillis() + 30000;
    Thread t = Thread.currentThread();
    if (t.getName().startsWith("DRDA")) {
      return;
    }
    Object val;
    try {
      do {
        Thread.sleep(500);
        if (System.currentTimeMillis() > maxWait) {
          break;
        }
        val = DUnitBB.getBB().get(queryStr);
        assertTrue("val not instance of String", (val == null || val instanceof String));
      } while (val == null || !val.equals(expectedStatus));
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private static Thread executeQueryInVM(final VM vm, final HeapThresholdHelper.QueryExecutor executor) throws SQLException, InterruptedException {
        //install the observer in remote VM 
        getGlobalLogger().info("Installing observer for " + executor.query() +
            " on VM " + vm.getPid() + " Host " + vm.getHost());
        vm.invoke(new SerializableRunnable("set observer") {
          @Override
          public void run() {
            getGlobalLogger().info("Setting the observer for variant " +
                executor.variant().name() + " query " + executor.query());
            GemFireXDQueryObserverHolder.putInstance(executor.observer());
          }
          
        });
        
        //now, normally execute the query in local vm. 
        getGlobalLogger().info("Executing Query ..." + executor.query());
        Thread t = HeapThresholdHelper.executeQueryInThread(executor);
        
        return t;
  }

  public static void reset() {
    DistributedSQLTestBase.reset();
    synchronized(queryExecutionErrorStatus) {
      queryExecutionErrorStatus.clear();
    }
  }

  private static void addToExecutionErrorStatus(String query, String failmsg) {
    synchronized(queryExecutionErrorStatus) {
      queryExecutionErrorStatus.put(query, failmsg);
    }
  }

  private static void waitForCriticalUpMembers(long maxWait) throws Exception {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache == null) {
      Thread.sleep(maxWait);
    } else {
      ResourceAdvisor adviser = cache.getResourceAdvisor();
      long start = System.currentTimeMillis();
      while (adviser.adviseCriticalMembers().size() == 0) {
        Thread.sleep(100);
        if (maxWait >= 0 && (System.currentTimeMillis() - start) > maxWait) {
          break;
        }
      }
    }
  }

  private static void dumpSharedMap(String msg) {
    Map<Object, Object> map = DUnitBB.getBB().getMapCopy();
    TestUtil.getLogger().info(
        "dumpSharedMap: " + msg + "Dumping shared map of size "
            + map.size());
    Iterator<?> iter = map.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<?, ?> entry = (Map.Entry<?, ?>)iter.next();
      getGlobalLogger().info("dumpSharedMap: Key=" + entry.getKey() + " value="
          + entry.getValue());
    }
  }
}
