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
package com.pivotal.gemfirexd.dataawareprocedure;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;

@SuppressWarnings("serial")
public class ListAggDUnit extends DistributedSQLTestBase {

	public ListAggDUnit(String name) {
		super(name);
	}

	private final String importOraJar = TestUtil.getResourcesDir()
			+ "/lib/import.jar";

	private final String dataFile = TestUtil.getResourcesDir()
			+ "/lib/fedexDataFile.dat";

	static volatile boolean failed = false;

	static final int numTimesEachThread = 100;

	static final int numThreads = 20;
	
	static volatile Exception exceptiongot = null;

	@Override
	protected String reduceLogging() {
	  // these tests generate lots of logs, so reducing them
	  return "config";
	}

	public void testDummy() {
	}

	public void _testListAggUnderLoad() throws Exception {
		startVMs(1, 4);
		startNetworkServer(1, null, null);
		startNetworkServer(2, null, null);
		startNetworkServer(3, null, null);
		startNetworkServer(4, null, null);

		Connection conn = TestUtil.getConnection();
		Statement s = conn.createStatement();

		String createTableddl = "CREATE TABLE CDSDBA.XML_DOC_1 ( "
				+ "XML_DOC_ID_NBR DECIMAL(19) NOT NULL, "
				+ "STRUCTURE_ID_NBR DECIMAL(22) NOT NULL, "
				+ "CREATE_MINT_CD CHAR(1) NOT NULL, "
				+ "MSG_PAYLOAD_QTY DECIMAL(22) NOT NULL, "
				+ "MSG_PAYLOAD1_IMG BLOB(2000) NOT NULL, "
				+ "MSG_PAYLOAD2_IMG BLOB(2000), "
				+ "MSG_PAYLOAD_SIZE_NBR DECIMAL(22), " + "MSG_PURGE_DT DATE, "
				+ "DELETED_FLG CHAR(1) NOT NULL, "
				+ "LAST_UPDATE_SYSTEM_NM VARCHAR(30), "
				+ "LAST_UPDATE_TMSTP TIMESTAMP NOT NULL, "
				+ "MSG_MAJOR_VERSION_NBR DECIMAL(22), "
				+ "MSG_MINOR_VERSION_NBR DECIMAL(22), "
				+ "OPT_LOCK_TOKEN_NBR DECIMAL(22) DEFAULT 1, "
				+ "PRESET_DICTIONARY_ID_NBR DECIMAL(22) DEFAULT 0 NOT NULL "
				+ ") " + "PARTITION BY COLUMN (STRUCTURE_ID_NBR) "
				+ "REDUNDANCY 1 ";

		String indexsql = "CREATE UNIQUE INDEX CDSDBA.XML_DOC_1_UK1 "
				+ "ON CDSDBA.XML_DOC_1(XML_DOC_ID_NBR,STRUCTURE_ID_NBR)";

		s.execute(createTableddl);
		s.execute(indexsql);
		s.execute("call sqlj.install_jar('" + importOraJar
				+ "', 'importOra', 0)");

		String createProcSql = "CREATE PROCEDURE CDSDBA.ListAgg"
				+ "(IN groupBy VARCHAR(256), IN ListAggCols VARCHAR(256), "
				+ "IN tableName VARCHAR(128), IN whereClause VARCHAR(256), "
				+ "IN whereParams VARCHAR(256), IN delimiter VARCHAR(10))"
				+ " LANGUAGE JAVA PARAMETER STYLE JAVA "
				+ "READS SQL DATA DYNAMIC RESULT SETS 1 "
				+ "EXTERNAL NAME 'com.pivotal.gemfirexd.dataawareprocedure.listAgg.ListAggProcedure.ListAgg'";
		
		String createAliasSQL = "CREATE ALIAS ListAggProcessor "
				+ "FOR 'com.pivotal.gemfirexd.dataawareprocedure.listAgg.LISTAGGPROCESSOR'";

		s.execute(createProcSql);
		s.execute(createAliasSQL);

		s.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX"
				+ "('CDSDBA','XML_DOC_1', '" + dataFile + "', '|', "
				+ "NULL, NULL, 0, 0 /* don't lock the table */, "
				+ "6 /* number of threads */, 0, "
				+ "'ImportOra' /* the Import class implementation*/, NULL)");

		Thread[] threads = new Thread[numThreads];
		
		for (int i=0; i<numThreads; i++) {
			threads[i] = new Thread(new RunListAggAndVerify());
		}
		
		for (Thread t : threads) {
			t.start();
		}
		
		for(Thread t : threads) {
			t.join();
		}
		
		if(exceptiongot != null) {
			fail("test failed due to exception ", exceptiongot);
		}
		assertFalse("test failed", failed);
	}

	static class RunListAggAndVerify implements Runnable {

		private static final String callProcStr = 
				"CALL CDSDBA.ListAgg('structure_id_nbr DESC','create_mint_cd'," +
				"'CDSDBA.XML_DOC_1','MSG_PAYLOAD_QTY=?','5',',') " +
				"WITH RESULT PROCESSOR ListAggProcessor ON TABLE CDSDBA.XML_DOC_1;";
		
		Connection conn;
		CallableStatement callableStmt;

		public void run() {
			try {
				int cnt = -1;
				while (cnt++ < numTimesEachThread) {
					if (conn == null) {
						conn = TestUtil.getConnection();
						callableStmt = conn
						        .prepareCall(callProcStr);
					}
					callableStmt.execute();
					verifyResults();
					
				}

			} catch (Exception ex) {
				failed = true;
				exceptiongot = ex;
			}
		}
		
		private void verifyResults() throws Exception {
			ResultSet thisResultSet = callableStmt.getResultSet();

            ResultSetMetaData resultMetaData = thisResultSet.getMetaData();
            resultMetaData.getColumnCount();

            assertTrue(thisResultSet != null);
            while (thisResultSet.next()) {
            }
		}
	}
}
