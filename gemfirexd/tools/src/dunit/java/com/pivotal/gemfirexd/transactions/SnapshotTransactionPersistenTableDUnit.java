package com.pivotal.gemfirexd.transactions;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Properties;

import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.TransactionObserver;
import com.gemstone.gemfire.internal.cache.TransactionObserverAdapter;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

public class SnapshotTransactionPersistenTableDUnit extends DistributedSQLTestBase {

  String tableName = "APP.TABLE1";
  boolean testBatchInsert = false;

  private final static String DISKSTORE = "SnapshotTransactionPersistenTableDUnit";


  public SnapshotTransactionPersistenTableDUnit(String name) {
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

  public String getSuffix() throws Exception {
    String suffix = " PERSISTENT " + "'" + DISKSTORE + "'";
    return suffix;
  }

  public void createDiskStore(boolean useClient, int vmNum) throws Exception {
    SerializableRunnable csr = getDiskStoreCreator(DISKSTORE);
    if (useClient) {
      if (vmNum == 1) {
        csr.run();
      } else {
        clientExecute(vmNum, csr);
      }
    } else {
      serverExecute(vmNum, csr);
    }
  }

  public void testNodeRecycleBatchConflict() throws Exception {
    Exception[] exception = new Exception[2];
    startVMs(1, 2);
    createDiskStore(true, 1);

    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);

    clientSQLExecute(1, "create table " + tableName + " (intcol int not null, text varchar" +
        "(100) not null, primary key (intcol))  partition by column(intcol)  buckets 7 redundancy 1 " + getSuffix() + " enable concurrency checks");

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
          boolean alreadyWaiting = false;

          @Override
          public void duringIndividualCommit(TXStateProxy tx,
              Object callbackArg) {

            if (callbackArg == "test") {
              synchronized (lock1) {
                lock1.notify();
              }
              return;
            }
            if (!alreadyWaiting) {
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
          boolean alreadyWaiting = false;

          @Override
          public void duringIndividualCommit(TXStateProxy tx,
              Object callbackArg) {

            if (callbackArg == "test") {
              synchronized (lock1) {
                lock1.notify();
              }
              return;
            }
            if (!alreadyWaiting) {
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
          for (int i = 0; i < 50; i++) {
            String stmtString = "insert into " + tableName + " values(" + i + ",'test" + i + "')";

            stmt.addBatch(stmtString);
            getLogWriter().info("Inserting " + i);
          }
          getLogWriter().info("Executing btach first time");
          stmt.executeBatch();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
    t.start();
    Thread.sleep(5000);

    try {
      Statement stmt = conn.createStatement();
      for (int i = 40; i < 100; i++) {
        String stmtString = "insert into " + tableName + " values(" + i + ",'test" + i + "')";
        stmt.addBatch(stmtString);
        getLogWriter().info("Inserting ..second " + i);
      }
      getLogWriter().info("Executing btach  second time ");
      stmt.executeBatch();
      //fail("Expected some exception, primary key violation");
    } catch (Exception e) {
      // fail with some exception
    }

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
    if (exception[0] != null) {
      throw exception[0];
    }
    t.join();

    try {
      Statement stmt2 = conn.createStatement();
      ResultSet rs = stmt2.executeQuery("select * from " + tableName);
      int num = 0;
      while (rs.next()) {
        num++;
      }
      assertTrue("Expected 50 but was " + num, num == 100);

    } catch (Exception e) {
      exception[0] = e;
      e.printStackTrace();
    }
    if (exception[0] != null) {
      throw exception[0];
    }

    restartVMNums(-1, -2);

    try {
      Statement stmt2 = conn.createStatement();
      ResultSet rs = stmt2.executeQuery("select * from " + tableName);
      int num = 0;
      while (rs.next()) {
        num++;
      }
      assertTrue("Expected 50 but was " + num, num == 100);

    } catch (Exception e) {
      exception[0] = e;
      e.printStackTrace();
    }
    if (exception[0] != null) {
      throw exception[0];
    }
  }

  public void testDefaultJDBCSnapshotUpdateDeleteConflict() throws Exception {
    Exception[] exception = new Exception[1];
    startVMs(1, 2);
    createDiskStore(true, 1);
    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);
    clientSQLExecute(1, "create table " + tableName + " (intcol int not null, text varchar" +
        "(100) not null) partition by column(intcol)  buckets 7 redundancy 1 " + getSuffix() + " enable concurrency checks");

    Statement stmt = conn.createStatement();
    for (int i = 0; i < 100; i++) {
      String stmtString = "insert into " + tableName + " values(" + i + ",'test" + i + "')";
      stmt.addBatch(stmtString);
    }
    stmt.executeBatch();

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
          boolean alreadyWaiting = false;
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
          boolean alreadyWaiting = false;
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

    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          final Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          stmt.executeUpdate("update " + tableName + " set text= 'suranjan' where intcol > 50");
        } catch (Exception e) {
          exception[0] = e;
          e.printStackTrace();
        }
      }
    });
    t.start();
    Thread.sleep(5000);
    getLogWriter().info("Did update which must be blocked ");

    try {
      stmt = conn.createStatement();
      stmt.executeUpdate("delete from " + tableName + " where intcol > 30");
      fail("Expected conflict exception");
    } catch (Exception e) {
      if (!(e instanceof SQLTransactionRollbackException)) {
        exception[0] = e;
        e.printStackTrace();
      }
    }
    if (exception[0] != null) {
      throw exception[0];
    }

    getLogWriter().info("Did delete ops.. ");
    server2.invoke(
        new SerializableRunnable() {
          @Override
          public void run() {
            try {
              Connection conn2 = TestUtil.getConnection();
              Statement stmt2 = conn2.createStatement();
              ResultSet rs = stmt2.executeQuery("select * from " + tableName + " where text='suranjan'");
              assert (!rs.next());
            } catch (Exception e) {
              exception[0] = e;
              e.printStackTrace();
            }
          }
        });
    if (exception[0] != null) {
      throw exception[0];
    }
    getLogWriter().info("SKSK Did select in both vm ");
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
    if (exception[0] != null) {
      throw exception[0];
    }

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
    getLogWriter().info("SKSK Did notify in both the vm. ");
    if (exception[0] != null) {
      throw exception[0];
    }
    t.join();

    getLogWriter().info("SKSK update completed. ");

    try {
      Statement stmt2 = conn.createStatement();
      ResultSet rs = stmt2.executeQuery("select * from " + tableName + " where text='suranjan'");
      int num = 0;
      while (rs.next()) {
        num++;
      }
      assertTrue("Expected 49 but was " + num, num == 49);

    } catch (Exception e) {
      exception[0] = e;
      e.printStackTrace();
    }
    if (exception[0] != null) {
      throw exception[0];
    }

    restartVMNums(-1, -2);

    try {
      Statement stmt2 = conn.createStatement();
      ResultSet rs = stmt2.executeQuery("select * from " + tableName + " where text='suranjan'");
      int num = 0;
      while (rs.next()) {
        num++;
      }
      assertTrue("Expected 49 but was " + num, num == 49);

    } catch (Exception e) {
      exception[0] = e;
      e.printStackTrace();
    }
    if (exception[0] != null) {
      throw exception[0];
    }
    getLogWriter().info("SKSK Test completed successfully.");
  }



  public void testNodeRecycle() throws  Exception {
    {
      Exception[] exception = new Exception[2];
      startVMs(1, 2);
      createDiskStore(true, 1);

      Properties props = new Properties();
      final Connection conn = TestUtil.getConnection(props);

      clientSQLExecute(1, "create table " + tableName + " (intcol int not null, text varchar" +
          "(100) not null, primary key (intcol))  partition by column(intcol)  buckets 7 redundancy 1 " + getSuffix() + " enable concurrency checks");

      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            final Connection conn = TestUtil.getConnection();
            Statement stmt = conn.createStatement();
            for (int i = 0; i < 50; i++) {
              String stmtString = "insert into " + tableName + " values(" + i + ",'test" + i + "')";

              stmt.addBatch(stmtString);
              getLogWriter().info("Inserting " + i);
            }
            getLogWriter().info("Executing btach first time");
            stmt.executeBatch();
          } catch (Exception e) {
            exception[0] = e;
            e.printStackTrace();
          }
        }
      });
      t.start();
      Thread.sleep(5000);
      t.join();

      try {
        Statement stmt = conn.createStatement();
        for (int i = 99; i > 40; i--) {
          String stmtString = "insert into " + tableName + " values(" + i + ",'test" + i + "')";
          stmt.addBatch(stmtString);
          getLogWriter().info("Inserting ..second " + i);
        }
        getLogWriter().info("Executing btach  second time ");
        stmt.executeBatch();
        //fail("Expected some exception, primary key violation");
      } catch (Exception e) {
        // fail with some exception
      }

      try {
        Statement stmt2 = conn.createStatement();
        ResultSet rs = stmt2.executeQuery("select * from " + tableName);
        int num = 0;
        while (rs.next()) {
          num++;
        }
        assertTrue("Expected 50 but was " + num, num == 100);

      } catch (Exception e) {
        exception[0] = e;
        e.printStackTrace();
      }
      if (exception[0] != null) {
        throw exception[0];
      }

      restartVMNums(-1, -2);

      try {
        Statement stmt2 = conn.createStatement();
        ResultSet rs = stmt2.executeQuery("select * from " + tableName);
        int num = 0;
        while (rs.next()) {
          num++;
        }
        assertTrue("Expected 50 but was " + num, num == 100);

      } catch (Exception e) {
        exception[0] = e;
        e.printStackTrace();
      }
      if (exception[0] != null) {
        throw exception[0];
      }
    }
  }

  public void testAutoCommitFalse() throws Exception {
    Exception[] exception = new Exception[2];
    startVMs(1, 2);
    createDiskStore(true, 1);

    Properties props = new Properties();
    final Connection conn = TestUtil.getConnection(props);

    clientSQLExecute(1, "create table " + tableName + " (intcol int not null, text varchar" +
        "(100) not null, primary key (intcol))  partition by column(intcol)  buckets 7 redundancy 1 " + getSuffix() + " enable concurrency checks");

    VM server1 = this.serverVMs.get(0);
    VM server2 = this.serverVMs.get(1);

    try {
      Statement stmt = conn.createStatement();
      for (int i = 0; i < 10; i++) {
        String stmtString = "insert into " + tableName + " values(" + i + ",'test" + i + "')";
        stmt.addBatch(stmtString);
        getLogWriter().info("Inserting ..second " + i);
      }
      getLogWriter().info("Executing batch first time ");
      stmt.executeBatch();
    } catch (Exception e) {
    }

    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        boolean success= false;
        try {
          final Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          Misc.getGemFireCache().getCacheTransactionManager().begin(IsolationLevel.SNAPSHOT, null);
          for (int i = 20; i > 5; i--) {
            String stmtString = "insert into " + tableName + " values(" + i + ",'test" + i + "')";
            stmt.addBatch(stmtString);
          }
          getLogWriter().info("Executing batch second time");
          stmt.executeBatch();
          success = true;
        } catch (Exception e) {
          if(!(e instanceof BatchUpdateException)) {
            exception[0] = e;
            e.printStackTrace();
          }
        }
        finally {
          if(success)
            Misc.getGemFireCache().getCacheTransactionManager().commit();
          else
            Misc.getGemFireCache().getCacheTransactionManager().rollback();
        }
      }
    });
    t.start();
    t.join();
    try {
      Statement stmt2 = conn.createStatement();
      ResultSet rs = stmt2.executeQuery("select * from " + tableName);
      int num = 0;
      while (rs.next()) {
        num++;
      }
      assertTrue("Expected 10 but was " + num, num == 10);

    } catch (Exception e) {
      exception[0] = e;
      e.printStackTrace();
    }
    if (exception[0] != null) {
      throw exception[0];
    }

    restartVMNums(-1, -2);

    try {
      Statement stmt2 = conn.createStatement();
      ResultSet rs = stmt2.executeQuery("select * from " + tableName);
      int num = 0;
      while (rs.next()) {
        num++;
      }
      assertTrue("Expected 10 but was " + num, num == 10);

    } catch (Exception e) {
      exception[0] = e;
      e.printStackTrace();
    }
    if (exception[0] != null) {
      throw exception[0];
    }
  }
}
