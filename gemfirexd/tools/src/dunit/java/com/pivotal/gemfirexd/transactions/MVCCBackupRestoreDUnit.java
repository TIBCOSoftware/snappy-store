package com.pivotal.gemfirexd.transactions;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import com.gemstone.gemfire.admin.internal.FinishBackupRequest;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.internal.FileUtil;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXId;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.persistence.BackupManager;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

public class MVCCBackupRestoreDUnit extends DistributedSQLTestBase {
  String regionName = "APP.TABLE1";

  public MVCCBackupRestoreDUnit(String name) {
    super(name);
  }

  protected static File getBackupDir() {
    File dir = getBackupDirOnly();
    dir.mkdirs();
    return dir;
  }


  protected static File getBackupDirOnly() {
    File tmpDir = new File(System.getProperty("java.io.tmpdir"));
    return new File(tmpDir, "backupDir");
  }

  private Throwable e;

  @Override
  protected String reduceLogging() {
    return "fine";
  }

  // Just testing if backup succeeds, it used to hang in hydra.
  public void testBackUP() throws Throwable {
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

    server2.invoke(new SerializableCallable() {
      @Override
      public Object call() {
        try {
          BackupManager backup = Misc.getGemFireCache().startBackup(Misc.getGemFireCache().getDistributedSystem().getDistributedMember());
          backup.prepareBackup();
          HashSet<PersistentID> set = backup.finishBackup(getBackupDir(), null, FinishBackupRequest.DISKSTORE_ALL);
          File incompleteBackup = FileUtil.find(getBackupDir(), ".*INCOMPLETE.*");
          assertNull(incompleteBackup);
          return set;
        } catch (IOException ioe) {
          e = ioe;
        }
        return 1;
      }
    });

    if (e != null) {
      throw e;
    }

    server1.invoke(new SerializableRunnable() {
      @Override
      public void run() {
        TXStateProxy txStateProxy = GemFireCacheImpl.getInstance().getCacheTransactionManager()
            .getHostedTXState(txid);
        //To invoke this operation without tx we need to unmasquerade
        TXManagerImpl.TXContext context=GemFireCacheImpl.getInstance()
            .getCacheTransactionManager().masqueradeAs(txStateProxy);
        GemFireCacheImpl.getInstance()
            .getCacheTransactionManager().commit();
      }
    });

    getLogWriter().info("SK Passed test.");
  }
}
