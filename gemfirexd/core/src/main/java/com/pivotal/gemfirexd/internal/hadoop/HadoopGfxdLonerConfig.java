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
package com.pivotal.gemfirexd.internal.hadoop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.hdfs.HDFSStore;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.DDLHoplogOrganizer;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.DDLHoplogOrganizer.DDLHoplog;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;

/**
 * Holds the config variables and functions for the GFXD loner mode for 
 * Hadoop and PXF.   
 * 
 * @author hemantb
 *
 */
public class HadoopGfxdLonerConfig {
  
  private Properties bootProps;
  private GemFireStore gemfireStore;
  private String namenodeUrl = null;
  private String hdfsHomeDirs = null;
  private Configuration config = null;
  
  public HadoopGfxdLonerConfig(final Properties properties, GemFireStore gemfireStore) {
    this.gemfireStore = gemfireStore;
    this.bootProps = properties;
    
    this.namenodeUrl = bootProps.getProperty(Property.GFXD_HD_NAMENODEURL);
    this.hdfsHomeDirs = bootProps.getProperty(Property.GFXD_HD_HOMEDIR);
    // hdfs home directory cannot be null when booted as an loner instance 
    // for hadoop map reduce
    assert this.hdfsHomeDirs != null;
    
    config = new Configuration();
    
    // iterate over the properties to get all the hadoop specific properties. 
    final Enumeration<?> propNames = properties.propertyNames();
    while (propNames.hasMoreElements()) {
      final String propName = (String)propNames.nextElement();
      final String propValue = properties.getProperty(propName);
      if (propName.startsWith(Property.HADOOP_GFXD_LONER_PROPS_PREFIX)) {
        String hadoopProp = propName.substring(Property.HADOOP_GFXD_LONER_PROPS_PREFIX.length());
        config.set(hadoopProp, propValue);
      }
      
    }
  }
  
  /**
   * Fetch the DDL statements from HDFS and insert them in the DDL queue. 
   */
  public void loadDDLQueueWithDDLsFromHDFS(GfxdDDLRegionQueue ddlStmtQueue) {
    try {
      String[] homedirs = this.hdfsHomeDirs.split(",");
      for (String homedir : homedirs) {
        if (homedir.length() == 0) {
          continue;
        }
        final String tempHDFSStoreName = "StoreForDDLFetching" + System.nanoTime();
        // create a temporary HDFS store to fetch the DDLs on the HDFS store 
        HDFSStoreFactoryImpl hdfsStoreFactory = new HDFSStoreFactoryImpl(this.gemfireStore.getGemFireCache());
        HDFSStore hdfsStore = hdfsStoreFactory.setConfiguration(this.config).
          setHomeDir(homedir).setNameNodeURL(namenodeUrl).
          create(tempHDFSStoreName);
        DDLHoplogOrganizer ddlOrganizer = new DDLHoplogOrganizer(hdfsStore);
        ArrayList<byte[]> ddls = null;
        final int LOOP_COUNT = 3;
        int repeat = 0; 
        
        // fetch the DDLs. 
        // Loop for three times in case of exception 
        // because the file can be deleted while we are reading it.
        // There is no easy way to do sync between a mapreduce job 
        // that is reading the file and gfxdfire cluster that is 
        // creating new files. 
        DDLHoplog ddlHoplog = null;
        while (true) {
          try {
            repeat++;
            ddlHoplog = ddlOrganizer.getDDLStatementsForReplay();
            break;
          } catch (IOException e) {
            if (repeat >= LOOP_COUNT)
              throw e;
          }
        }
        
        // Iterate over the DDLs and insert them in the DDL queue
        if (ddlHoplog != null && ddlHoplog.getDDLStatements() != null) {
          ddls = ddlHoplog.getDDLStatements();
          for (byte[] ddl : ddls) {
            DDLConflatable ddlConflatable = (DDLConflatable)BlobHelper.deserializeBlob(
                ddl, ddlHoplog.getDDLVersion(), null);
            try {
              ddlStmtQueue.put(ddlConflatable.getId(),ddlConflatable);
            } catch (EntryExistsException e) {
              // ignore this error. This can happen when same ddl from two hdfs stores are 
              // being replayed. 
            }
          }
        }
        
        // we are done with the HDFS store. Close it and remove it
        ddlOrganizer.close();
      }
      // TODO removing hdfs store is correct. Uncomment this step after removing
      // fs.close command from hdfs store impl
      //      this.gemfireStore.getGemFireCache().removeHDFSStore((HDFSStoreImpl)hdfsStore);
    }catch (IOException e) {
      throw new GemFireXDRuntimeException("Exception thrown while loading DDL " +
          "statements from HDFS. Internal exception: " + e);
    } catch (CacheException e) {
      throw new GemFireXDRuntimeException("Exception thrown while loading DDL " +
          "statements from HDFS. Internal exception: " + e);
    } catch (ClassNotFoundException e) {
      throw new GemFireXDRuntimeException("Exception thrown while loading DDL " +
          "statements from HDFS. Internal exception: " + e);
    } catch (InterruptedException e) {
      throw new GemFireXDRuntimeException("Exception thrown while loading DDL " +
          "statements from HDFS. Internal exception: " + e);
    }
    
  }

  /**
   * Remove Hadoop specific properties from the boot properties.
   * This function is currently called when you want to remove the 
   * hadoop properties that are polluting the boot props. 
   * 
   * @param properties
   * @return
   */
  public Properties removeHadoopProperties(Properties properties) {
    Properties newprops = new Properties();
    if (properties == null)
      return newprops;
    // iterate over the properties to remove all the hadoop specific properties. 
    final Enumeration<?> propNames = properties.propertyNames();
    while (propNames.hasMoreElements()) {
      final String propName = (String)propNames.nextElement();
      String propValue = properties.getProperty(propName);
      if (propName == null)
        continue;
      if (propValue == null)
        propValue = "";
      if (!propName.startsWith(Property.HADOOP_GFXD_LONER_PROPS_PREFIX)) {
        newprops.put(propName, propValue);
      }
    }
    return newprops;
  }
}
