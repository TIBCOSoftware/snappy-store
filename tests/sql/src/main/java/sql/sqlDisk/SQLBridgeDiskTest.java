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
package sql.sqlDisk;

import hydra.Log;
import hydra.TestConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Set;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.internal.engine.Misc;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.dmlStatements.DMLStmtIF;
import sql.sqlBridge.SQLBridgeTest;
import util.TestException;

public class SQLBridgeDiskTest extends SQLBridgeTest{
	protected static SQLBridgeDiskTest sbdt = null;
	int numOfPRs = 0; //only used for sync offline testing
	
	public static synchronized void HydraTask_initialize() {
		if (sbdt == null) sbdt = new SQLBridgeDiskTest();
	}
	
	public static void HydraTask_doDMLOp() {
           if (sbdt == null) sbdt = new SQLBridgeDiskTest();
		sbdt.doDiscDMLOp();
	}
	
  public static void HydraTask_doOpOffline() {
    sbdt.doDiscOpOffline();
  }
  
  protected void doDiscOpOffline() {   
    Log.getLogWriter().info("starts ops during offline");
    int num = 100;
    Connection gConn = getGFEConnection();
    if (random.nextInt(10) == 1 && getMyTid() == ddlThread) createIndex(gConn);
    for (int i=0; i<num; ++i) doDiscDMLOp();
  }
  
  public static void HydraTask_doOpOnline() {
    sbdt.doDiscOpOnline();
  }
  
  protected void doDiscOpOnline() {
    Log.getLogWriter().info("starts ops during online");
    int num = 500;
    Connection gConn = getGFEConnection();
    if (random.nextInt(10) == 1 && getMyTid() == ddlThread) createIndex(gConn);
    for (int i=0; i<num; ++i) doDiscDMLOp();
  }
	
  protected void doDiscDMLOp() {
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    doDiscDMLOp(dConn, gConn);    
    
    if (!setTx) {
      commit(dConn);
      closeDiscConnection(dConn);
  
      commit(gConn);
      closeGFEConnection(gConn);   
    } else {
      commit(gConn);
      closeGFEConnection(gConn); 
      
      commit(dConn);
      closeDiscConnection(dConn); //to be able to rollback the derby op is commit failed in gfxd
    }
    Log.getLogWriter().info("done dmlOp");
  }
  
  protected void doDiscDMLOp(Connection dConn, Connection gConn) {
    Log.getLogWriter().info("performing dmlOp, myTid is " + getMyTid());
    int table = dmlTables[random.nextInt(dmlTables.length)]; //get random table to perform dml
    DMLStmtIF dmlStmt= dmlFactory.createDiscDMLStmt(table); //dmlStmt of a table
    int numOfOp = 10;
    int size = 1;
  
    //perform the opeartions
    String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
    if (operation.equals("insert")) {
    	for (int i=0; i<numOfOp; i++) {
    	  if (setTx && isHATest) resetNodeFailureFlag();
    		dmlStmt.insert(dConn, gConn, size);
    		if (setTx && isHATest) {
          commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
          commit(dConn);
          break;
        }
    		commit(dConn);
    		commit(gConn);
    	}
    }
    else if (operation.equals("update")) {
    	for (int i=0; i<numOfOp; i++) {
    	  if (setTx && isHATest) resetNodeFailureFlag();
    		dmlStmt.update(dConn, gConn, size);
    		if (setTx && isHATest) {
          commit(gConn); //commit gfxd first, so that if it failed we can roll back derby op
          commit(dConn);
          break;
        }
    		commit(dConn);
    		commit(gConn);
    	}
    }
    else if (operation.equals("delete")) {
      if (setTx && isHATest) resetNodeFailureFlag();
      dmlStmt.delete(dConn, gConn);
    }
    else if (operation.equals("query")) {
      if (setTx && isHATest) resetNodeFailureFlag();
      dmlStmt.query(dConn, gConn);
    }
    else
      throw new TestException("Unknown entry operation: " + operation);
  }
  
  public static void HydraTask_flushDisk() {
  	sbdt.flushDisk();
  }
  
  protected void flushDisk() {
  	Connection gConn = getGFEConnection();
  	flushDisk(gConn);
  }
  
  @SuppressWarnings("unchecked")
	protected void flushDisk(Connection gConn) {
  	ArrayList<String[]> tables = getTableNames(gConn);
  	for(String[] table: tables) {
  		Region r = Misc.getRegionForTable(table[0]+"."+table[1], true);
  		DiskStoreImpl d = ((LocalRegion)r).getDiskStore();
  		if (d != null) {
  			Log.getLogWriter().info("flush async queue for table: " + 
  					table[0] + "." + table[1]);
  			d.flush();
  		}
  	}
  }
  
  public static void HydraTask_doSyncOfflineOps() {
    sbdt.doSyncOfflienOps();
  }

  protected void doSyncOfflienOps() {
    if (getMyTid() == ddlThread) {
      if (numOfPRs == 0) {
        GemFireCacheImpl cacheImpl = (GemFireCacheImpl) Misc.getGemFireCache();
        //only for peer driver, for client needs to get it from server, possibly add a method
        //to update bb
        Log.getLogWriter().info("prs are: ");
        Set<PartitionedRegion> prs = cacheImpl.getPartitionedRegions();
        for (PartitionedRegion pr: prs) {
          Log.getLogWriter().info(pr.getName());
        }
        
        numOfPRs = prs.size() - 1;
        //_identity region is not used in this test, so there is no rebalance expected.
        Log.getLogWriter().info("num Of PRs is " + numOfPRs);
      }
      
      stopVms();
    }
    waitForBarrier();
    
    doDiscOpOffline();
    
    waitForBarrier();
    
    if (getMyTid() == ddlThread) {
      startVms(numOfPRs);
    }
    
    waitForBarrier();
    
    doDiscOpOnline();
    
    waitForBarrier();
  }
}
