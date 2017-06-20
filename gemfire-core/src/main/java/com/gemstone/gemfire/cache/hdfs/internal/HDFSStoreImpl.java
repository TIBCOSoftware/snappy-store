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

package com.gemstone.gemfire.cache.hdfs.internal;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.Callable;

import com.gemstone.gemfire.cache.hdfs.HDFSEventQueueAttributes;
import com.gemstone.gemfire.cache.hdfs.HDFSIOException;
import com.gemstone.gemfire.cache.hdfs.HDFSStore;
import com.gemstone.gemfire.cache.hdfs.HDFSStoreMutator;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.DDLHoplogOrganizer;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSCompactionManager;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HDFSRegionDirector;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.Hoplog.HoplogWriter;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.HoplogConfig;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.mapreduce.HoplogUtil;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.cache.persistence.soplog.ComponentLogWriter;
import com.gemstone.gemfire.internal.cache.persistence.soplog.HFileStoreStatistics;
import com.gemstone.gemfire.internal.cache.persistence.soplog.hfile.HFileSortedOplogFactory;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.LogService;
import com.gemstone.gemfire.internal.util.SingletonCallable;
import com.gemstone.gemfire.internal.util.SingletonValue;
import com.gemstone.gemfire.internal.util.SingletonValue.SingletonBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.LruBlockCache;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.ConnectTimeoutException;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;

/**
 * Represents a HDFS based persistent store for region data.
 * 
 * @author Ashvin Agrawal
 */
public class HDFSStoreImpl implements HDFSStore {
  public static final String ALLOW_STANDALONE_HDFS_FILESYSTEM_PROP = "gemfire.ALLOW_STANDALONE_HDFS_FILESYSTEM";
  public static final int DEFAULT_BLOCKSIZE_SMALL = 8 * 1024;

  private final boolean ALLOW_TEST_FILE_SYSTEM = Boolean.getBoolean(ALLOW_STANDALONE_HDFS_FILESYSTEM_PROP);
  final LogWriterI18n logger;

  private volatile HDFSStoreConfigHolder configHolder; 
  
  private final SingletonValue<FileSystem> fs;

  /**
   * Used to make sure that only one thread creates the writer at a time. This prevents the dispatcher
   * threads from cascading the Connection lock in DFS client see bug 51195
   */
  private final SingletonCallable<HoplogWriter> singletonWriter = new SingletonCallable<HoplogWriter>();

  private final HFileStoreStatistics stats;
  private final BlockCache blockCache;
  private DDLHoplogOrganizer ddlOrganizer;
 
  private static HashSet<String> secureNameNodes = new HashSet<String>();
  
  private final boolean USE_FS_CACHE = Boolean.getBoolean(HoplogConfig.USE_FS_CACHE);
  
  private final boolean PERFORM_SECURE_HDFS_CHECK = Boolean.getBoolean(HoplogConfig.PERFORM_SECURE_HDFS_CHECK_PROP);
  
  static {
    HdfsConfiguration.init();
  }
  
  public HDFSStoreImpl(String name, final HDFSStore config) {
    this.configHolder = new HDFSStoreConfigHolder(config);
    configHolder.setName(name);

    logger = ComponentLogWriter.getHoplogLogWriter("HdfsStore:" + name, LogService.logger());
    
    stats = new HFileStoreStatistics(InternalDistributedSystem.getAnyInstance(), "HDFSStoreStatistics", name);

    final Configuration hconf;
    if (config instanceof HDFSStoreFactoryImpl
        && null != ((HDFSStoreFactoryImpl) config).getConfiguration()) {
      // this path is executed for loner
      hconf = ((HDFSStoreFactoryImpl) config).getConfiguration();
    } else {
      hconf = new Configuration();
    }
        
    // Set the block cache size.
    // Disable the static block cache. We keep our own cache on the HDFS Store
    // hconf.setFloat("hfile.block.cache.size", 0f);
    if (this.getBlockCacheSize() != 0) {
      long cacheSize = (long) (HeapMemoryMonitor.getTenuredPoolMaxMemory() * this.getBlockCacheSize() / 100);

      // TODO use an off heap block cache if we're using off heap memory?
      // See CacheConfig.instantiateBlockCache.
      // According to Anthony, the off heap block cache is still
      // expirimental. Our own off heap cache might be a better bet.
//      this.blockCache = new LruBlockCache(cacheSize,
//          StoreFile.DEFAULT_BLOCKSIZE_SMALL, hconf, HFileSortedOplogFactory.convertStatistics(stats));
      this.blockCache = new LruBlockCache(cacheSize,
          DEFAULT_BLOCKSIZE_SMALL, hconf);
    } else {
      this.blockCache = null;
    }
    
    final String clientFile = config.getHDFSClientConfigFile();
    fs = new SingletonValue<FileSystem>(new SingletonBuilder<FileSystem>() {
      @Override
      public FileSystem create() throws IOException {
        return createFileSystem(hconf, clientFile, false);
      }

      @Override
      public void postCreate() {
      }
      
      @Override
      public void createInProgress() {
      }
    });
    
    FileSystem fileSystem = null;
    try {
      fileSystem = fs.get();
    } catch (Throwable ex) {
      throw new HDFSIOException(ex.getMessage(),ex);
    }    
    //HDFSCompactionConfig has already been initialized
    long cleanUpIntervalMillis = getHDFSCompactionConfig().getOldFilesCleanupIntervalMins() * 60 * 1000;
    Path cleanUpIntervalPath = new Path(getHomeDir(), HoplogConfig.CLEAN_UP_INTERVAL_FILE_NAME);
    HoplogUtil.exposeCleanupIntervalMillis(fileSystem, cleanUpIntervalPath, cleanUpIntervalMillis);
  }
  
  /**
   * Creates a new file system every time.  
   */
  public FileSystem createFileSystem() {
    Configuration hconf = new Configuration();
    try {
      return createFileSystem(hconf, this.getHDFSClientConfigFile(), true);
    } catch (Throwable ex) {
      throw new HDFSIOException(ex.getMessage(),ex);
    }
  }
  
  private FileSystem createFileSystem(Configuration hconf, String configFile, boolean forceNew) throws IOException {
    FileSystem filesystem = null; 
    
      // load hdfs client config file if specified. The path is on local file
      // system
      if (configFile != null) {
        if (logger.fineEnabled()) {
          logger.fine("Adding resource config file to hdfs configuration:" + configFile);
        }
        hconf.addResource(new Path(configFile));
        
        if (! new File(configFile).exists()) {
          logger.warning(LocalizedStrings.HOPLOG_HDFS_CLIENT_CONFIG_FILE_ABSENT, configFile);
        }
      }
      
      // This setting disables shutdown hook for file system object. Shutdown
      // hook may cause FS object to close before the cache or store and
      // unpredictable behavior. This setting is provided for GFXD like server
      // use cases where FS close is managed by a server. This setting is not
      // supported by old versions of hadoop, HADOOP-4829
      hconf.setBoolean("fs.automatic.close", false);
      
      // Hadoop has a configuration parameter io.serializations that is a list of serialization 
      // classes which can be used for obtaining serializers and deserializers. This parameter 
      // by default contains avro classes. When a sequence file is created, it calls 
      // SerializationFactory.getSerializer(keyclass). This internally creates objects using 
      // reflection of all the classes that were part of io.serializations. But since, there is 
      // no avro class available it throws an exception. 
      // Before creating a sequenceFile, override the io.serializations parameter and pass only the classes 
      // that are important to us. 
      hconf.setStrings("io.serializations",
          new String[]{"org.apache.hadoop.io.serializer.WritableSerialization"});
      // create writer

      // [sumedh] should not be required with the new metrics2
      // SchemaMetrics.configureGlobally(hconf);
      
      String nameNodeURL = null;
      if ((nameNodeURL = getNameNodeURL()) == null) {
          nameNodeURL = hconf.get("fs.default.name");
      }
      
      URI namenodeURI = URI.create(nameNodeURL);

    if (! GemFireCacheImpl.getExisting().isHadoopGfxdLonerMode()) {
      String authType = hconf.get("hadoop.security.authentication");
      
      //The following code handles Gemfire XD with secure HDFS
      //A static set is used to cache all known secure HDFS NameNode urls.
      UserGroupInformation.setConfiguration(hconf);

      //Compare authentication method ignoring case to make GFXD future version complaint
      //At least version 2.0.2 starts complaining if the string "kerberos" is not in all small case.
      //However it seems current version of hadoop accept the authType in any case
      if (authType.equalsIgnoreCase("kerberos")) {
        
        String principal = hconf.get(HoplogConfig.KERBEROS_PRINCIPAL);
        String keyTab = hconf.get(HoplogConfig.KERBEROS_KEYTAB_FILE);
       
        if (!PERFORM_SECURE_HDFS_CHECK) {
          logger.fine("Ignore secure hdfs check");
        } else {
          if (!secureNameNodes.contains(nameNodeURL)) {
            logger.fine("Executing secure hdfs check");
             try{
              filesystem = FileSystem.newInstance(namenodeURI, hconf);
              //Make sure no IOExceptions are generated when accessing insecure HDFS. 
              filesystem.listFiles(new Path("/"),false);
              throw new HDFSIOException("Gemfire XD HDFS client and HDFS cluster security levels do not match. The configured HDFS Namenode is not secured.");
             } catch (IOException ex) {
               secureNameNodes.add(nameNodeURL);
             } finally {
             //Close filesystem to avoid resource leak
               if(filesystem != null) {
                 closeFileSystemIgnoreError(filesystem);
               }
             }
          }
        }

        // check to ensure the namenode principal is defined
        String nameNodePrincipal = hconf.get("dfs.namenode.kerberos.principal");
        if (nameNodePrincipal == null) {
          throw new IOException(LocalizedStrings.GFXD_KERBEROS_NAMENODE_PRINCIPAL_UNDEF.toLocalizedString());
        }
        
        // ok, the user specified a gfxd principal so we will try to login
        if (principal != null) {
          //If NameNode principal is the same as Gemfire XD principal, there is a 
          //potential security hole
          String regex = "[/@]";
          if (nameNodePrincipal != null) {
            String HDFSUser = nameNodePrincipal.split(regex)[0];
            String GFXDUser = principal.split(regex)[0];
            if (HDFSUser.equals(GFXDUser)) {
              logger.warning(LocalizedStrings.HDFS_USER_IS_SAME_AS_GFXD_USER, GFXDUser);
            }
          }
          
          // a keytab must exist if the user specifies a principal
          if (keyTab == null) {
            throw new IOException(LocalizedStrings.GFXD_KERBEROS_KEYTAB_UNDEF.toLocalizedString());
          }
          
          // the keytab must exist as well
          File f = new File(keyTab);
          if (!f.exists()) {
            throw new FileNotFoundException(LocalizedStrings.GFXD_KERBEROS_KEYTAB_FILE_ABSENT.toLocalizedString(f.getAbsolutePath()));
          }

          //Authenticate Gemfire XD principal to Kerberos KDC using Gemfire XD keytab file
          String principalWithValidHost = SecurityUtil.getServerPrincipal(principal, "");
          UserGroupInformation.loginUserFromKeytab(principalWithValidHost, keyTab);
        } else {
          logger.warning(LocalizedStrings.GFXD_KERBEROS_PRINCIPAL_UNDEF);
        }
      }
    }

    if (USE_FS_CACHE && !forceNew) {
      filesystem = FileSystem.get(namenodeURI, hconf);
    } else {
      filesystem = FileSystem.newInstance(namenodeURI, hconf);
    }
      
    if(filesystem instanceof LocalFileSystem && !ALLOW_TEST_FILE_SYSTEM) {
      closeFileSystemIgnoreError(filesystem);
      throw new IllegalStateException(LocalizedStrings.HOPLOG_TRYING_TO_CREATE_STANDALONE_SYSTEM.toLocalizedString(getNameNodeURL()));
    }
    
    if (logger.fineEnabled()) {
      logger.fine("Initialized FileSystem linked to " + filesystem.getUri()
          + " " + filesystem.hashCode());
    }
    return filesystem;
  }

  public FileSystem getFileSystem() throws IOException {
    return fs.get();
  }
  
  public FileSystem getCachedFileSystem() {
    return fs.getCachedValue();
  }

  public SingletonCallable<HoplogWriter> getSingletonWriter() {
    return this.singletonWriter;
  }

  private final SingletonCallable<Boolean> fsExists = new SingletonCallable<Boolean>();

  public boolean checkFileSystemExists() throws IOException {
    try {
      return fsExists.runSerially(new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          FileSystem fileSystem = getCachedFileSystem();
          if (fileSystem == null) {
            return false;
          }
          return fileSystem.exists(new Path("/"));
        }
      });
    } catch (Exception e) {
      if (e instanceof IOException) {
        throw (IOException)e;
      }
      throw new IOException(e);
    }
  }

  /**
   * This method executes a query on namenode. If the query succeeds, FS
   * instance is healthy. If it fails, the old instance is closed and a new
   * instance is created.
   */
  public void checkAndClearFileSystem() {
    FileSystem fileSystem = getCachedFileSystem();
    
    if (fileSystem != null) {
      if (logger.fineEnabled()) {
        logger.fine("Checking file system at " + fileSystem.getUri());
      }
      try {
        checkFileSystemExists();
        if (logger.fineEnabled()) {
          logger.fine("FS client is ok: " + fileSystem.getUri() + " "
              + fileSystem.hashCode());
        }
        return;
      } catch (ConnectTimeoutException e) {
        if (logger.fineEnabled()) {
          logger.fine("Hdfs unreachable, FS client is ok: "
              + fileSystem.getUri() + " " + fileSystem.hashCode());
        }
        return;
      } catch (IOException e) {
        logger.fine("IOError in filesystem checkAndClear ", e);
        
        // The file system is closed or NN is not reachable. It is safest to
        // create a new FS instance. If the NN continues to remain unavailable,
        // all subsequent read/write request will cause HDFSIOException. This is
        // similar to the way hbase manages failures. This has a drawback
        // though. A network blip will result in all connections to be
        // recreated. However trying to preserve the connections and waiting for
        // FS to auto-recover is not deterministic.
        if (e instanceof RemoteException) {
          e = ((RemoteException) e).unwrapRemoteException();
        }

        logger.warning(LocalizedStrings.HOPLOG_HDFS_UNREACHABLE,
            fileSystem.getUri(), e);
      }

      // compare and clear FS container. The fs container needs to be reusable
      boolean result = fs.clear(fileSystem, true);
      if (!result) {
        // the FS instance changed after this call was initiated. Check again
        logger.fine("Failed to clear FS ! I am inconsistent so retrying ..");
        checkAndClearFileSystem();
      } else {
        closeFileSystemIgnoreError(fileSystem);
      }      
    }
  }

  private void closeFileSystemIgnoreError(FileSystem fileSystem) {
    if (fileSystem == null) {
      logger.fine("Trying to close null file system");
      return;
    }

    try {
      if (logger.fineEnabled()) {
        logger.fine("Closing file system at " + fileSystem.getUri() + " "
            + fileSystem.hashCode());
      }
      fileSystem.close();
    } catch (Exception e) {
      if (logger.fineEnabled()) {
        logger.fine("Failed to close file system at " + fileSystem.getUri()
            + " " + fileSystem.hashCode(), e);
      }
    }
  }

  public HFileStoreStatistics getStats() {
    return stats;
  }
  
  public BlockCache getBlockCache() {
    return blockCache;
  }

  public void close() {
    logger.fine("Closing file system: " + getName());
    stats.close();
    blockCache.shutdown();
    //Might want to clear the block cache, but it should be dereferenced.
    
    // release DDL hoplog organizer for this store. Also shutdown compaction
    // threads. These two resources hold references to GemfireCacheImpl
    // instance. Any error is releasing this resources is not critical and needs
    // be ignored.
    try {
      HDFSCompactionManager manager = HDFSCompactionManager.getInstance(this);
      if (manager != null) {
        manager.reset();
      }
      if (ddlOrganizer != null) {
        ddlOrganizer.close();
      }
    } catch (Exception e) {
      logger.info(e);
    }
    
    // once this store is closed, this store should not be used again
    FileSystem fileSystem = fs.clear(false);
    if (fileSystem != null) {
      closeFileSystemIgnoreError(fileSystem);
    }    
  }
  
  /**
   * Test hook to remove all of the contents of the the folder
   * for this HDFS store from HDFS.
   * @throws IOException 
   */
  public void clearFolder() throws IOException {
    getFileSystem().delete(new Path(getHomeDir()), true);
  }
  
  public synchronized void setDDLHoplogOrganizer(DDLHoplogOrganizer ddlOrganizer) {
    this.ddlOrganizer = ddlOrganizer;
  }
  
  public synchronized DDLHoplogOrganizer getDDLHoplogOrganizer() {
    // ddl organizer can never be null. It should be set 
    // immediately after the store is created.
    assert ddlOrganizer != null;
    return this.ddlOrganizer;
  }
  
  @Override
  public void destroy() {
    Collection<String> regions = HDFSRegionDirector.getInstance().getRegionsInStore(this);
    if(!regions.isEmpty()) {
      throw new IllegalStateException("Cannot destroy a HDFS store that still contains regions: " + regions); 
    }
    close();
  }

  @Override
  public synchronized HDFSStore alter(HDFSStoreMutator mutator) {
    if (logger.fineEnabled()) {
      logger.fine("Altering hdfsStore " + this);
      logger.fine("Mutator " + mutator);
    }
    HDFSStoreConfigHolder newHolder = new HDFSStoreConfigHolder(configHolder);
    newHolder.copyFrom(mutator);
    newHolder.getHDFSCompactionConfig().validate();
    HDFSStore oldStore = configHolder;
    configHolder = newHolder;
    if (logger.fineEnabled()) {
      logger.fine("Resuult of Alter " + this);
    }
    return (HDFSStore) oldStore;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("HDFSStoreImpl [");
    if (configHolder != null) {
      builder.append("configHolder=");
      builder.append(configHolder);
    }
    builder.append("]");
    return builder.toString();
  }

  @Override
  public String getName() {
    return configHolder.getName();
  }

  @Override
  public String getNameNodeURL() {
    return configHolder.getNameNodeURL();
  }

  @Override
  public String getHomeDir() {
    return configHolder.getHomeDir();
  }

  @Override
  public String getHDFSClientConfigFile() {
    return configHolder.getHDFSClientConfigFile();
  }

  @Override
  public float getBlockCacheSize() {
    return configHolder.getBlockCacheSize();
  }

  @Override
  public int getMaxFileSize() {
    return configHolder.getMaxFileSize();
  }

  @Override
  public int getFileRolloverInterval() {
    return configHolder.getFileRolloverInterval();
  }

  @Override
  public HDFSEventQueueAttributes getHDFSEventQueueAttributes() {
    return configHolder.getHDFSEventQueueAttributes();
  }

  @Override
  public HDFSCompactionConfig getHDFSCompactionConfig() {
    return (HDFSCompactionConfig) configHolder.getHDFSCompactionConfig();
  }

  @Override
  public HDFSStoreMutator createHdfsStoreMutator() {
    return new HDFSStoreMutatorImpl();
  }
}
