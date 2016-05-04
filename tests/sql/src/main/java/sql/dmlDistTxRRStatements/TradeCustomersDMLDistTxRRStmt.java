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
package sql.dmlDistTxRRStatements;

import hydra.Log;
import hydra.blackboard.SharedMap;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLTest;
import sql.dmlDistTxStatements.TradeCustomersDMLDistTxStmt;
import sql.sqlTx.ReadLockedKey;
import sql.sqlTx.SQLDistRRTxTest;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxRRReadBB;
import sql.sqlutil.ResultSetHelper;
import util.TestException;

public class TradeCustomersDMLDistTxRRStmt extends TradeCustomersDMLDistTxStmt {

  protected boolean verifyConflict(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer>modifiedKeysByThisTx, SQLException gfxdse,
      boolean getConflict) {
    return verifyConflictForRR(modifiedKeysByOp, modifiedKeysByThisTx, gfxdse, getConflict);
  }
  
  public boolean queryGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return queryGfxdOnly(gConn);
    }
    
    int whichQuery = rand.nextInt(select.length); //randomly select one query sql
    int cid = rand.nextInt((int) SQLBB.getBB().getSharedCounters().read(SQLBB.tradeCustomersPrimary));
    //Date since = new Date ((rand.nextInt(10)+98),rand.nextInt(12), rand.nextInt(31));
    Date since = getSince();
    ResultSet gfxdRS = null;
    SQLException gfxdse = null;
    for (int i = 0; i < 10; i++) {
      try {
        Log.getLogWriter().info("RR: executing query " + i + "times");
        gfxdRS = query(gConn, whichQuery, cid, since, getMyTid());
        if (gfxdRS == null) {
          if (isHATest) {
            Log.getLogWriter().info("Testing HA and did not get GFXD result set");
            return true;
          } else
            throw new TestException("Not able to get gfxd result set");
        }
      } catch (SQLException se) {
        if (isHATest &&
            SQLHelper.gotTXNodeFailureException(se)) {
          SQLHelper.printSQLException(se);
          Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
          return false; //assume node failure exception causes the tx to rollback
        } else if (se.getSQLState().equals("X0Z02") && (i < 9)) {
          Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
          continue;
        }
        SQLHelper.printSQLException(se);
        gfxdse = se;
      }
      try {
        List<Struct> gfxdList = ResultSetHelper.asList(gfxdRS, false);

        if (gfxdList == null && isHATest) {
          Log.getLogWriter().info("Testing HA and did not get GFXD result set");
          return true; //do not compare query results as gemfirexd does not get any
        }

        addReadLockedKeys(gfxdList);

        addQueryToDerbyTx(whichQuery, cid, since, gfxdList, gfxdse);
        //only the first thread to commit the tx in this round could verify results
        //this is handled in the SQLDistTxTest doDMLOp
      } catch (TestException te) {
        if (te.getMessage().contains("Conflict detected in transaction operation and it will abort") && (i <9)) {
          Log.getLogWriter().info("RR: Retrying the query as we got conflicts");
          continue;
        } else throw te;
      }
      break;
    }
    
    return true;
  }  
  
  @SuppressWarnings("unchecked")
  protected void addReadLockedKeys(List<Struct> gfxdList) {
    int txId = (Integer) SQLDistRRTxTest.curTxId.get();
    SharedMap readLockedKeysByRRTx = SQLTxRRReadBB.getBB().getSharedMap();   
    
    Log.getLogWriter().info("adding the RR read keys to the Map for " +
        "this txId: " + txId);
    for (int i=0; i<gfxdList.size(); i++) {
      int cid = (Integer) gfxdList.get(i).get("CID");
      String key = getTableName()+"_"+cid;
      Log.getLogWriter().info("RR read key to be added is " + key);
      ((HashMap<String, Integer>) SQLDistRRTxTest.curTxRRReadKeys.get()).put(key, txId);
      
      ReadLockedKey readKey = (ReadLockedKey) readLockedKeysByRRTx.get(key);
      if (readKey == null) readKey = new ReadLockedKey(key);
      readKey.addKeyByCurTx(txId);
      readLockedKeysByRRTx.put(key, readKey);
    }
    
  }

  protected boolean queryGfxdOnly(Connection gConn){
    try {
      return super.queryGfxdOnly(gConn);
    } catch (TestException te) {
      if (te.getMessage().contains("X0Z02") && !reproduce49935) {
        Log.getLogWriter().info("hit #49935, continuing test");
        return false;
      } 
       else throw te;
    }
  }
}
