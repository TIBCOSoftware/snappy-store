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
import hydra.TestConfig;
import hydra.blackboard.SharedMap;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.dmlDistTxStatements.TradeSecuritiesDMLDistTxStmt;
import sql.sqlTx.ReadLockedKey;
import sql.sqlTx.SQLDistRRTxTest;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxRRReadBB;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import util.TestHelper;

public class TradeSecuritiesDMLDistTxRRStmt extends
    TradeSecuritiesDMLDistTxStmt {
  /*
  select = {"select * from trade.securities where tid = ?",
      "select avg( distinct price) as avg_distinct_price from trade.securities where tid=? and symbol >?", 
       "select price, symbol, exchange from trade.securities where (price<? or price >=?) and tid =?",    
       "select sec_id, symbol, price, exchange from trade.securities  where (price >=? and price<?) and exchange =? and tid =?",
       "select * from trade.securities where sec_id = ?",
       "select sec_id, price, symbol from trade.securities where symbol >?",
        "select price, symbol, exchange from trade.securities where (price<? or price >=?) ",
        "select sec_id, symbol, price, exchange from trade.securities  where (price >=? and price<?) and exchange =?"
        };
  */
  protected static boolean reproduce39455 = TestConfig.tab().booleanAt(SQLPrms.toReproduce39455, false);








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
    
    if (whichQuery > 3) {
      whichQuery = whichQuery-4; //avoid to hold all the keys to block other tx in the RR tests
    }
    
    int sec_id = rand.nextInt((int)SQLBB.getBB().getSharedCounters().read(SQLBB.tradeSecuritiesPrimary));
    String symbol = getSymbol();
    BigDecimal price = getPrice();
    BigDecimal price1 = price.add(new BigDecimal(rangePrice));
    String exchange = getExchange();
    int tid = testUniqueKeys ? getMyTid() : getRandomTid();
    String sql = null;
    
    ResultSet gfxdRS = null;
    SQLException gfxdse = null;
    List<Struct> noneTxGfxdList = null;
    
    if (!reproduce39455 && whichQuery == 1) whichQuery--; //hitting #39455, need to remove this line and uncomment the following once #39455 is fixed
    
    try {
      gfxdRS = query (gConn, whichQuery, sec_id, symbol, price, exchange, tid);   
      if (gfxdRS == null) {
        if (isHATest) {
          Log.getLogWriter().info("Testing HA and did not get GFXD result set");
          return true;
        }
        else     
          throw new TestException("Not able to get gfxd result set");
      }
    } catch (SQLException se) {
      if (isHATest &&
        SQLHelper.gotTXNodeFailureException(se) ) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("got node failure exception during Tx with HA support, continue testing");
        return false; //assume node failure exception causes the tx to rollback 
      }
      
      SQLHelper.printSQLException(se);
      gfxdse = se;
    }
    
    List<Struct> gfxdList = ResultSetHelper.asList(gfxdRS, false);
    if (gfxdList == null && isHATest) {
      Log.getLogWriter().info("Testing HA and did not get GFXD result set");
      return true; //do not compare query results as gemfirexd does not get any
    }    
    boolean[] success = new boolean[1];
    success[0] = false;
    
    
    if (whichQuery == 1) { //after #39455 is fixed
      //"select avg( distinct price) as avg_distinct_price from trade.securities where tid=? and symbol >?", 
      sql = "select sec_id from trade.securities where tid=" +tid + " and symbol > '" + symbol +"'" ;  
      while (!success[0]) { 
        noneTxGfxdList = getKeysForQuery(sql, success);
      } 
    } else if (whichQuery == 2 ) {
      // "select price, symbol, exchange from trade.securities where (price<? or price >=?) and tid =?",
      sql = "select sec_id from trade.securities where (price<" + price +
      		" or price >= " + price1 + ") and tid =" + tid;
      while (!success[0]) { 
        noneTxGfxdList = getKeysForQuery(sql, success);
      } 
    } 
    
    if (whichQuery == 1 ||  whichQuery == 2) {
      Log.getLogWriter().info("noneTxGfxdList size is " + noneTxGfxdList.size());
      addReadLockedKeys(noneTxGfxdList);
    }
    else
      addReadLockedKeys(gfxdList);
    
    addQueryToDerbyTx(whichQuery, sec_id, symbol, price, exchange, 
        tid, gfxdList, gfxdse);
    //only the first thread to commit the tx in this round could verify results
    //to avoid phantom read
    //this is handled in the SQLDistTxTest doDMLOp
    
    return true;
  }  
  
  protected List<Struct> getKeysForQuery(String sql, boolean[] success) {
    Connection noneTxConn = (Connection) SQLDistTxTest.gfxdNoneTxConn.get();
    
    try {
      Log.getLogWriter().info("executing the following query: " + sql);
      ResultSet noneTxGfxdRS = noneTxConn.createStatement().executeQuery(sql); 
      List<Struct> noneTxGfxdList = ResultSetHelper.asList(noneTxGfxdRS, false);
      if (noneTxGfxdList == null && isHATest) {
        Log.getLogWriter().info("Testing HA and did not get GFXD result set");
        success[0] = false;
      } else {
        success[0] = true;
      }
      return noneTxGfxdList;
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }  
    
    return null; //should not hit this as SQLHelper.handleSQLException(se) throws TestException.
  }
  
  @SuppressWarnings("unchecked")
  protected void addReadLockedKeys(List<Struct> gfxdList) {
    int txId = (Integer) SQLDistRRTxTest.curTxId.get();
    SharedMap readLockedKeysByRRTx = SQLTxRRReadBB.getBB().getSharedMap();   
    
    Log.getLogWriter().info("adding the RR read keys to the Map for " +
        "this txId: " + txId);
    for (int i=0; i<gfxdList.size(); i++) {
      int sid = (Integer) gfxdList.get(i).get("SEC_ID");
      String key = getTableName()+"_"+sid;
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
      if (te.getMessage().contains("X0Z02") && !reproduce49935 ) {
        Log.getLogWriter().info("hit #49935, continuing test");
        return false;
      }
       else throw te;
    }
  }

}
