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
import java.util.Iterator;
import java.util.Properties;

import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.*;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

public class MVCCDUnitTest extends DistributedSQLTestBase {

  String regionName = "APP.TABLE1";

  public MVCCDUnitTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "fine";
  }

  public String getSuffix() {
    return " replicate persistent ";
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
        "(100) not null) "+ getSuffix() +" enable concurrency checks");


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


    server1.invoke(commitTransactionInVM(txid));
    server2.invoke(validateResults(5,regionName));

    server1.invoke(new SerializableCallable() {
      @Override
      public Object call() {

        try {
          final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
          //final Region r = cache.getRegion(tableName);
          cache.getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          Connection conn = TestUtil.getConnection();
          conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
          Statement stmt = conn.createStatement();
          for (int i = 5; i < 10; i++) {
            stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");

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
                  stmt.execute("insert into " + regionName + " values(" + i + ",'test" + i + "')");

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
}
