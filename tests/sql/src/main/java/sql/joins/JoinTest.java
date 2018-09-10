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
package sql.joins;

import java.sql.Connection;
import java.sql.SQLException;

import hydra.Log;
import hydra.RemoteTestModule;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.SQLTest;
import sql.joinStatements.MultiTablesJoinStmt;
import util.PRObserver;

public class JoinTest extends SQLTest {
	public static JoinTest joinTest = null;
	
	public static synchronized void HydraTask_initialize() {
		if (joinTest == null) {
			joinTest = new JoinTest();
			sqlTest = new SQLTest();
			
			PRObserver.installObserverHook();
			PRObserver.initialize(RemoteTestModule.getMyVmid());
		    
		  joinTest.initialize();
		}
  }
	
	public static void  HydraTask_createViewsForJoin() {
		if(joinTest==null)
			joinTest = new JoinTest();
		joinTest.createViews();
	}
	
  public static void HydraTask_queryOnJoinOp() {
     if(joinTest==null)
        joinTest = new JoinTest();
    joinTest.queryOnJoinOp();
  }

  protected void queryOnJoinOp() {
    if (random.nextBoolean()) {
      super.queryOnJoinOp();
      return;
    }
    Log.getLogWriter().info("performing queryOnJoin for multitables, myTid is " + getMyTid());
    Connection dConn =null;
    if (hasDerbyServer) {
      dConn = getDiscConnection();
    }  //when not test uniqueKeys and not in serial execution, only connection to gfe is provided.
    Connection gConn = getGFEConnection();
    
    if (setCriticalHeap) resetCanceledFlag();
    if (setTx && isHATest) resetNodeFailureFlag();
    if (setTx && testEviction) resetEvictionConflictFlag();
    
    //perform the opeartions
    new MultiTablesJoinStmt().query(dConn, gConn);

  }
	
	protected void createViews() {		
		if (hasDerbyServer) {
			Connection dConn = getDiscConnection();
			new MultiTablesJoinStmt().createViewsForJoin(dConn);
		}
		Connection conn = getGFEConnection();
		new MultiTablesJoinStmt().createViewsForJoin(conn);
		closeGFEConnection(conn);
	}
}
