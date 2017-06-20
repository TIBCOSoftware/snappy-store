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

import java.math.BigDecimal;
import java.sql.Connection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import sql.SQLHelper;
import sql.SQLTest;
import sql.sqlTx.SQLDistTxTest;
import sql.sqlTx.SQLTxBatchingFKBB;
import sql.sqlutil.ResultSetHelper;
import util.TestException;
import hydra.Log;
import sql.dmlDistTxStatements.TradeSellOrdersDMLDistTxStmt;
import util.TestHelper;


public class TradeSellOrdersDMLDistTxRRStmt extends
    TradeSellOrdersDMLDistTxStmt {


  protected boolean verifyConflict(HashMap<String, Integer> modifiedKeysByOp,
      HashMap<String, Integer>modifiedKeysByThisTx, SQLException gfxdse,
      boolean getConflict) {
    return verifyConflictForRR(modifiedKeysByOp, modifiedKeysByThisTx, gfxdse, getConflict);
  }
  
  public boolean queryGfxd(Connection gConn, boolean withDerby){
    if (!withDerby) {
      return queryGfxdOnly(gConn);
    }
    
    //TODO to implement query with derby, and add to read locked keys for the table
    return true;
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
