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
package com.pivotal.gemfirexd.transactions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.*;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

public class MVCCDUnit extends DistributedSQLTestBase {

  String regionName = "APP.TABLE1";
  boolean testBatchInsert = false;
  public MVCCDUnit(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "fine";
  }


  @Override
  public void setUp() throws Exception {
    System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "true");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "true");
      }
    });
    super.setUp();
  }

  @Override
  public void tearDown2() throws Exception {
    System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "false");
    invokeInEveryVM(new SerializableRunnable() {
      @Override
      public void run() {
        System.setProperty("gemfire.cache.ENABLE_DEFAULT_SNAPSHOT_ISOLATION_TEST", "false");
      }
    });
    super.tearDown2();
  }

  public void testSnapshotInsertAPI() throws Exception {
    
    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
        "(100) not null) replicate persistent enable concurrency checks");

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);


    final TXId txid = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            String stmtString= "insert into " + regionName + " values(" + i + ",'test" + i + "')";
            if(testBatchInsert){
              stmt.addBatch(stmtString);
            } else {
              stmt.execute(stmtString);
            }
          }
          if(testBatchInsert) {
            stmt.executeBatch();
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });


    server2.invoke(validateResults(0,regionName));


    server1.invoke(commitTransactionInVM(txid));
    server2.invoke(validateResults(5,regionName));

    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {

        try {
          final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
          //final Region r = cache.getRegion(regionName);
          cache.getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Connection conn = TestUtil.getConnection();
          conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
          Statement stmt = conn.createStatement();
          for (int i = 5; i < 10; i++) {
            String stmtString = "insert into " + regionName + " values(" + i + ",'test" + i + "')";
            if(testBatchInsert) {
              stmt.addBatch(stmtString);
            } else {
              stmt.execute(stmtString);
            }
          }
          if(testBatchInsert) {
            stmt.executeBatch();
          }
          ResultSet rs = stmt.executeQuery("select * from " + regionName);

          int numRows = 0;
          while (rs.next()) {
            assertEquals("test" + rs.getInt(1), rs.getString(2));
            numRows++;
          }
          assertEquals(10, numRows);

          cache.getCacheTransactionManager().commit();

        } catch (Exception ex) {
          ex.printStackTrace();
          throw new RuntimeException(ex);
        }

        return null;
      }
    });

    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
          //take an snapshot again//gemfire level
          final Region r = cache.getRegion(regionName);
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          cache.getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          ResultSet rs = stmt.executeQuery("select * from " + regionName);


          // after this start another insert in a separate thread and those put shouldn't be visible
          Runnable run = new Runnable() {
            @Override
            public void run() {
              try {
                Connection conn = TestUtil.getConnection();
                Statement stmt = conn.createStatement();
                for (int i = 11; i <= 15; i++) {
                  String stmtString = "insert into " + regionName + " values(" + i + ",'test" + i + "')";
                  if (testBatchInsert) {
                    stmt.addBatch(stmtString);
                  } else {
                    stmt.execute(stmtString);
                  }

                }
                if(testBatchInsert) {
                  stmt.executeBatch();
                }
              } catch (SQLException ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex);
              }

            }
          };
          Thread t = new Thread(run);
          t.start();
          try {
            t.join();
          } catch (InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
          }

          int numRows = 0;
          while (rs.next()) {
            assertEquals("test" + rs.getInt(1), rs.getString(2));
            numRows++;
          }
          assertEquals(10, numRows);
          cache.getCacheTransactionManager().commit();
          rs = stmt.executeQuery("select * from " + regionName);
          numRows = 0;
          while (rs.next()) {
            assertEquals("test" + rs.getInt(1), rs.getString(2));
            numRows++;
          }

          assertEquals(15, numRows);
        } catch (Exception ex) {
          ex.printStackTrace();
          throw new RuntimeException(ex);
        }
        return null;
      }
    });
  }

  public void testSnapshotBatchInsertAPI() throws Exception {
    testBatchInsert=true;
    testSnapshotInsertAPI();
  }

  public void testParallelTransactions() throws Exception {
    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
        "(100) not null) replicate persistent enable concurrency checks");


    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);


    final TXId txid = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });


    server2.invoke(validateResults(0,regionName));


    final TXId txServer2 = (TXId) server2.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 25; i < 30; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });


    server1.invoke(commitTransactionInVM(txid));

    server1.invoke(validateResults(5,regionName));
    server2.invoke(validateResults(10,regionName));


    server2.invoke(commitTransactionInVM(txServer2));

    server1.invoke(validateResults(10, regionName));

  }



  public void testMixedOperations() throws Exception {
    
    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
        "(100) not null) replicate persistent enable concurrency checks");


    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);



    // Do initial inserts without starting transaction
    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 10; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });

    final TXId txid1 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 10; i < 15; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });


    //Only visible first 10 records
    server2.invoke(validateResults(10,regionName));

    server1.invoke(validateResults(15,regionName));


    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          TXManagerImpl txManager = GemFireCacheImpl.getExisting()
              .getCacheTransactionManager();
          TXStateProxy txStateProxy = txManager.getHostedTXState(txid1);
          //To invoke this operation without tx we need to unmasquerade
          TXManagerImpl.TXContext context = txManager.masqueradeAs(txStateProxy);

          txManager.unmasquerade(context, true);
          context.setSnapshotTXState(null);
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            stmt.execute("delete from "+regionName +" where intcol="+i);
          }
          conn.commit();
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });



    //Delete should be visible to all the servers as they were not performed in tx
    server2.invoke(validateResults(5,regionName));

    server1.invoke(validateResults(5,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(10, regionName, txid1));

    //Start second transaction
    final TXId txid2 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 30; i < 35; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });

    //Commit first transaction
    server1.invoke(commitTransactionInVM(txid1));


    server2.invoke(validateResults(10,regionName));

    server1.invoke(validateResults(10,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(15,regionName,txid2));


    //Commit second transaction
    server1.invoke(commitTransactionInVM(txid2));
    server2.invoke(validateResults(15,regionName));

    server1.invoke(validateResults(15,regionName));

  }

  public void testMixedOperationsGII() throws Exception {
    
    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);

    Statement st1 = conn.createStatement();

    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
        "(100) not null) replicate persistent enable concurrency checks");


    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    // Do initial inserts without starting transaction
    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 10; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });

    final TXId txid1 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 10; i < 15; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });


    //Only visible first 10 records
    server2.invoke(validateResults(10,regionName));

    server1.invoke(validateResults(15,regionName));


    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          TXManagerImpl txManager = GemFireCacheImpl.getExisting()
              .getCacheTransactionManager();
          TXStateProxy txStateProxy = txManager.getHostedTXState(txid1);
          //To invoke this operation without tx we need to unmasquerade
          TXManagerImpl.TXContext context = txManager.masqueradeAs(txStateProxy);

          txManager.unmasquerade(context, true);
          context.setSnapshotTXState(null);
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            stmt.execute("delete from "+regionName +" where intcol="+i);
          }
          conn.commit();
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });



    //Delete should be visible to all the servers as they were not performed in tx
    server2.invoke(validateResults(5,regionName));

    server1.invoke(validateResults(5,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(10, regionName, txid1));

    //Start second transaction
    final TXId txid2 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 30; i < 35; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });

    //Commit first transaction
    server1.invoke(commitTransactionInVM(txid1));


    server2.invoke(validateResults(10,regionName));

    server1.invoke(validateResults(10,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(15,regionName,txid2));


    //Commit second transaction
    server1.invoke(commitTransactionInVM(txid2));
    server2.invoke(validateResults(15,regionName));
    server1.invoke(validateResults(15,regionName));

    // start another server let it do GII and query it.
    // It should return all the results.
    startServerVMs(1, 0, null);
    VM server3 = this.serverVMs.get(2);
    server3.invoke(validateResults(15,regionName));
  }

  public void testMixedOperationsServerRestart() throws Exception {
    
    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
        "(100) not null) replicate persistent enable concurrency checks");

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    // Do initial inserts without starting transaction
    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 10; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });

    final TXId txid1 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 10; i < 15; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });


    //Only visible first 10 records
    server2.invoke(validateResults(10,regionName));

    server1.invoke(validateResults(15,regionName));

    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          TXManagerImpl txManager = GemFireCacheImpl.getExisting()
              .getCacheTransactionManager();
          TXStateProxy txStateProxy = txManager.getHostedTXState(txid1);
          //To invoke this operation without tx we need to unmasquerade
          TXManagerImpl.TXContext context = txManager.masqueradeAs(txStateProxy);

          txManager.unmasquerade(context, true);
          context.setSnapshotTXState(null);
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            stmt.execute("delete from "+regionName +" where intcol="+i);
          }
          conn.commit();
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });



    //Delete should be visible to all the servers as they were not performed in tx
    server2.invoke(validateResults(5,regionName));

    server1.invoke(validateResults(5,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(10, regionName, txid1));

    //Start second transaction
    final TXId txid2 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 30; i < 35; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });

    //Commit first transaction
    server1.invoke(commitTransactionInVM(txid1));
    server2.invoke(validateResults(10,regionName));
    server1.invoke(validateResults(10,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(15,regionName,txid2));


    //Commit second transaction
    server1.invoke(commitTransactionInVM(txid2));
    server2.invoke(validateResults(15,regionName));
    server1.invoke(validateResults(15,regionName));

    // start another server let it do GII and query it.
    // It should return all the results.

    startServerVMs(1, 0, null);
    VM server3 = this.serverVMs.get(2);
    server3.invoke(validateResults(15,regionName));

    restartVMNums(-3);
    server3 = this.serverVMs.get(2);
    server3.invoke(validateResults(15,regionName));
  }

  private final static String DISKSTORE = "MVCCDiskStore";


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

  // stop server
  // do some operations on another
  // start the stopped server
  // check if all values are coming.
  public void testMixedOperationsDeltaGII() throws Exception {
    
    startVMs(1, 2);
    createDiskStore(true,1);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
        "(100) not null) partition by column(intcol) buckets 7 redundancy 1 persistent 'MVCCDiskStore' enable concurrency checks");

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    // Do initial inserts without starting transaction
    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 10; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });

    final TXId txid1 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 10; i < 15; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });

    //Only visible first 10 records
    server2.invoke(validateResults(10,regionName));
    server1.invoke(validateResults(15,regionName));

    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          TXManagerImpl txManager = GemFireCacheImpl.getExisting()
              .getCacheTransactionManager();
          TXStateProxy txStateProxy = txManager.getHostedTXState(txid1);
          //To invoke this operation without tx we need to unmasquerade
          TXManagerImpl.TXContext context = txManager.masqueradeAs(txStateProxy);

          txManager.unmasquerade(context, true);
          context.setSnapshotTXState(null);
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            stmt.execute("delete from "+regionName +" where intcol="+i);
          }
          conn.commit();
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });

    //Delete should be visible to all the servers as they were not performed in tx
    server2.invoke(validateResults(5,regionName));

    server1.invoke(validateResults(5,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(10, regionName, txid1));

    //Start second transaction
    final TXId txid2 = (TXId)server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          GemFireCacheImpl.getInstance().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Statement stmt = conn.createStatement();
          for (int i = 30; i < 35; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return GemFireCacheImpl.getInstance().getCacheTransactionManager().getCurrentTXId();
      }
    });

    //Commit first transaction
    server1.invoke(commitTransactionInVM(txid1));
    server2.invoke(validateResults(10,regionName));

    server1.invoke(validateResults(10,regionName));
    server1.invoke(validateResultsWithMasqueradeTx(15,regionName,txid2));


    //Commit second transaction
    server1.invoke(commitTransactionInVM(txid2));
    server2.invoke(validateResults(15,regionName));
    server1.invoke(validateResults(15,regionName));

    // start another server let it do GII and query it.
    // It should return all the results.
    startServerVMs(1, 0, null);
    VM server3 = this.serverVMs.get(2);
    server3.invoke(validateResults(15,regionName));

    // Stop one of the Vms
    // do some operations in one VM
    // restart one vm and check again
    stopVMNum(-1);
    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 5; i < 15; i++) {
            String sql = "update  " + regionName + " set text='test2"  + i + "' where intcol =" + i;
            getLogWriter().info("sql : " + sql);
            stmt.execute(sql);
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return null;
      }
    });
    server3.invoke(validateUpdatedResults(15, regionName));
    getLogWriter().info("Restarting the vm 1");
    restartServerVMNums(new int[] { 1 }, 0, null, null);
    stopVMNum(-2);
    getLogWriter().info(",Stopped vm2 and restarted the vm 1");

    server1.invoke(validateUpdatedResults(15, regionName));
    getLogWriter().info("validated from vm1");
  }

  private SerializableCallable validateUpdatedResults(final int expectedResults, final String regionName) {
    SerializableCallable callable = new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("select * from " + regionName);

          int numRows = 0;
          while (rs.next()) {
            if (rs.getInt(1) >= 30) {
              assertEquals("test" + rs.getInt(1), rs.getString(2));
            } else {
              assertEquals("test2" + rs.getInt(1), rs.getString(2));
            }
            numRows++;
          }
          assertEquals(expectedResults, numRows);
        } catch (Exception ex) {
          ex.printStackTrace();
          throw new RuntimeException(ex);
        }
        return null;
      }
    };
    return callable;
  }

  private SerializableCallable validateResults(final int expectedResults, final String regionName) {
    SerializableCallable callable = new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("select * from " + regionName);

          int numRows = 0;
          while (rs.next()) {
            assertEquals("test" + rs.getInt(1), rs.getString(2));
            numRows++;
          }
          assertEquals(expectedResults, numRows);
        } catch (Exception ex) {
          ex.printStackTrace();
          throw new RuntimeException(ex);
        }
        return null;
      }
    };
    return callable;
  }


  private SerializableCallable commitTransactionInVM(final TXId txid) {
    SerializableCallable callable = new SerializableCallable() {
      @Override
      public Object call() {
        try {
          Connection conn = TestUtil.getConnection();
          TXStateProxy txStateProxy = GemFireCacheImpl.getInstance().getCacheTransactionManager()
              .getHostedTXState(txid);
          GemFireCacheImpl.getInstance().getCacheTransactionManager().masqueradeAs(txStateProxy);

          GemFireCacheImpl.getInstance().getCacheTransactionManager().commit();

        } catch (Exception ex) {
          ex.printStackTrace();
          throw new RuntimeException(ex);
        }
        return null;
      }
    };
    return callable;
  }

  private SerializableCallable validateResultsWithMasqueradeTx(final int expectedResults, final
  String regionName, final TXId txId) {
    SerializableCallable callable = new SerializableCallable() {
      @Override
      public Object call() {
        try {
          TXManagerImpl txManager = GemFireCacheImpl.getExisting()
              .getCacheTransactionManager();
          TXStateProxy txStateProxy = txManager.getHostedTXState(txId);
          TXManagerImpl.TXContext context = txManager.masqueradeAs(txStateProxy);
          context.setSnapshotTXState(txStateProxy);

          Connection conn = TestUtil.getConnection();
          conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery("select * from " + regionName);

          int numRows = 0;
          while (rs.next()) {
            assertEquals("test" + rs.getInt(1), rs.getString(2));
            numRows++;
          }
          assertEquals(expectedResults, numRows);
          txManager.unmasquerade(context, true);
          context.setSnapshotTXState(null);
        } catch (Exception ex) {
          ex.printStackTrace();
          throw new RuntimeException(ex);
        }
        return null;
      }
    };
    return callable;
  }
  public String getSuffix() {
    return "";
  }



  class ScanTestHook implements GemFireCacheImpl.RowScanTestHook {
    Object lockForTest = new Object();
    Object operationLock = new Object();


    public void notifyOperationLock() {
      synchronized(operationLock){
        operationLock.notify();
      }
    }

    public void notifyTestLock() {
      synchronized(lockForTest) {
        lockForTest.notify();
      }
    }

    public void waitOnTestLock() {
      try {
        synchronized (lockForTest) {
          lockForTest.wait();
        }
      } catch (InterruptedException ex) {
        ex.printStackTrace();
      }
    }

    public void waitOnOperationLock() {
      try {
        synchronized (operationLock) {
          operationLock.wait();
        }
      } catch (InterruptedException ex) {
        ex.printStackTrace();
      }
    }
  }


  public void testParallelTransactionsUsingTestHook() throws Exception {
    
    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    Statement st1 = conn.createStatement();

    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
            "(100) not null) replicate persistent enable concurrency checks");


    VM server1 = this.serverVMs.get(0);
    //VM server2 = this.serverVMs.get(1);


    final boolean success = (Boolean)server1.invoke(new SerializableCallable() {

      @Override
      public Object call() {
        try {
          ScanTestHook testHook = new ScanTestHook();
          GemFireCacheImpl.getInstance().setRowScanTestHook(testHook);
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
          }


          Callable<Boolean> insertTask = new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {

              boolean success = true;
              try {
                Connection conn1 = TestUtil.getConnection();
                Statement stmt1 = conn1.createStatement();
                GemFireCacheImpl.getInstance().waitOnScanTestHook();
                for (int i = 6; i < 11; i++) {
                  stmt1.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");
                }
                GemFireCacheImpl.getInstance().notifyRowScanTestHook();
              }catch(SQLException sqlex) {
                success= false;
                sqlex.printStackTrace();
              }catch(Exception ex) {
                fail(ex.getMessage(),ex);
                success= false;
                throw ex;

              }
              return success;
            }
          };



          ExecutorService pool = Executors.newFixedThreadPool(2);
          Future<Boolean> insertResult = pool.submit(insertTask);

          Callable<Boolean> scanTask = new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
               boolean success  = true;
              try {
                Connection conn1 = TestUtil.getConnection();
                Statement stmt1 = conn1.createStatement();
                Thread.sleep(2000);
                ResultSet rs =  stmt1.executeQuery("select * from " + regionName);
                int cnt=0;
                while(rs.next()) {
                  cnt=cnt+1;
                }
                assertEquals(5,cnt);
              }catch(SQLException sqlex) {
                sqlex.printStackTrace();
                success=false;
              }catch(InterruptedException iex) {
                iex.printStackTrace();
                success=false;
              }
              catch(Exception ex) {
                fail(ex.getMessage(),ex);
                success=false;
                throw ex;
              }
              return success;
            }
          };

          Future<Boolean> scanResult = pool.submit(scanTask);
          assertTrue(scanResult.get());

        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
        return true;
      }
    });
  }

  // test for default jdbc snapshot insert

  public void testDefaultJDBCSnapshotInsert() throws Exception {
    final Exception[] exception = new Exception[1];
    startVMs(1, 2);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    clientSQLExecute(1, "create table " + regionName + " (intcol int not null, text varchar" +
        "(100) not null) partition by column(intcol)  buckets 7 redundancy 1 persistent enable concurrency checks");

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);
    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        TXManagerImpl txMgr = GemFireCacheImpl.getExisting()
            .getCacheTransactionManager();
        txMgr.setObserver(new TransactionObserverAdapter() {
          Object lock1 = new Object();
          Object lock2 = new Object();
          volatile boolean alreadyWaiting= false;
          @Override
          public void duringIndividualCommit(TXStateProxy tx,
              Object callbackArg) {

            if (callbackArg == "test") {
              synchronized (lock1) {
                lock1.notify();
              }
              return;
            }
            if(!alreadyWaiting) {
              alreadyWaiting = true;
              try {
                synchronized (lock1) {
                  lock1.wait();
                }
              } catch (InterruptedException ie) {
                // ignore
              }
            }
          }

          public void notifyCommit() {
            synchronized (lock1) {
              lock1.notify();
            }
          }
        });
      }
    });

    server2.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        TXManagerImpl txMgr = GemFireCacheImpl.getExisting()
            .getCacheTransactionManager();
        txMgr.setObserver(new TransactionObserverAdapter() {
          Object lock1 = new Object();
          Object lock2 = new Object();
          volatile boolean alreadyWaiting= false;

          @Override
          public void duringIndividualCommit(TXStateProxy tx,
              Object callbackArg) {

            if (callbackArg == "test") {
              synchronized (lock1) {
                lock1.notify();
              }
              return;
            }
            // don't wait for read commit/ otherwise deadlock.
            if(!alreadyWaiting) {
              alreadyWaiting = true;
              try {
                synchronized (lock1) {
                  lock1.wait();
                }
              } catch (InterruptedException ie) {
                // ignore
              }
            }
          }

          public void notifyCommit() {
            synchronized (lock1) {
              lock1.notify();
            }
          }
        });
      }
    });

    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          final Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          for (int i = 0; i < 5; i++) {
            String stmtString = "insert into " + regionName + " values(" + i + ",'test" + i + "')";
            stmt.addBatch(stmtString);
          }
          stmt.executeBatch();
        } catch (Exception e) {
          exception[0] = e;
          e.printStackTrace();
        }
      }
    });
    t.start();
    Thread.sleep(1000);

    server1.invoke(
        new SerializableRunnable() {
          @Override
          public void run() {
            try {
              Connection conn2 = TestUtil.getConnection();
              Statement stmt2 = conn2.createStatement();
              ResultSet rs = stmt2.executeQuery("select * from " + regionName);
              assert (!rs.next());
            } catch (Exception e) {
              exception[0] = e;
              e.printStackTrace();
            }
          }
        });
    server2.invoke(
        new SerializableRunnable() {
          @Override
          public void run() {
            try {
              Connection conn2 = TestUtil.getConnection();
              Statement stmt2 = conn2.createStatement();
              ResultSet rs = stmt2.executeQuery("select * from " + regionName);
              assert (!rs.next());
            } catch (Exception e) {
              exception[0] = e;
              e.printStackTrace();
            }
          }
        });

    server1.invoke(
        new SerializableRunnable() {
          @Override
          public void run() {
            try {
              Collection<TXStateProxy> proxies = Misc.getGemFireCache().getCacheTransactionManager().getHostedTransactionsInProgress();
              for (TXStateProxy tx : proxies) {
                if (tx.isSnapshot()) {
                  final TransactionObserver observer = tx.getObserver();
                  observer.duringIndividualCommit(tx, "test");
                }
              }
            } catch (Exception e) {
              exception[0] = e;
            }
          }
        });

    server2.invoke(
        new SerializableRunnable() {
          @Override
          public void run() {
            try {
              Collection<TXStateProxy> proxies = Misc.getGemFireCache().getCacheTransactionManager().getHostedTransactionsInProgress();
              for (TXStateProxy tx : proxies) {
                if (tx.isSnapshot()) {
                  final TransactionObserver observer = tx.getObserver();
                  observer.duringIndividualCommit(tx, "test");
                }
              }
            } catch (Exception e) {
              exception[0] = e;
            }
          }
        });

    t.join();

    try {
      Statement stmt2 = conn.createStatement();
      ResultSet rs = stmt2.executeQuery("select * from " + regionName);
      int num = 0;
      while (rs.next()) {
        num++;
      }
      assertTrue(num == 5);

    } catch (Exception e) {
      exception[0] = e;
      e.printStackTrace();
    }
    if (exception[0] != null) {
      throw exception[0];
    }
  }
}
