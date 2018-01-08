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

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.execute.FunctionStats;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.procedure.DistributedProcedureCallFunction;
import com.pivotal.gemfirexd.internal.engine.procedure.ProcedureChunkMessage;
import com.pivotal.gemfirexd.procedure.OutgoingResultSet;
import com.pivotal.gemfirexd.procedure.ProcedureExecutionContext;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

@SuppressWarnings("serial")
public class ProcedureTestDUnit extends DistributedSQLTestBase {

  public ProcedureTestDUnit(String name) {
    super(name);
  }

  public static void serverGroupProc(int number, ResultSet[] rs1,
      ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4) throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:default:connection");
    getGlobalLogger().info("executing serverGroupProc on id: " +
        InternalDistributedSystem.getConnectedInstance().getProperties());
    ResultSet r = c.createStatement().executeQuery("values dsid()");
    assertTrue(r.next());
    String dsid = r.getString(1);
    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid + "'");
    }
    if (number > 1) {
      rs2[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid + "'");
    }
    if (number > 2) {
      rs3[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid + "'");
    }
    if (number > 3) {
      rs4[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid + "'");
    }
    c.close();
  }

  public static void serverGroupProcWith5SecWait(int number, ResultSet[] rs1,
      ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4) throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:default:connection");
    try {
      Thread.sleep(5000);
    } catch (InterruptedException e) {
      fail("why an uninterrpted exception in serverGroupProcWith5SecWait sleep");
    }
    getGlobalLogger().info("executing serverGroupProcWith5SecWait on id: " +
        InternalDistributedSystem.getConnectedInstance().getProperties());
    ResultSet r = c.createStatement().executeQuery("values dsid()");
    assertTrue(r.next());
    String dsid = r.getString(1);
    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid  + "'");
    }
    if (number > 1) {
      rs2[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid  + "'");
    }
    if (number > 2) {
      rs3[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid  + "'");
    }
    if (number > 3) {
      rs4[0] = c.createStatement().executeQuery(
          "select servergroups from sys.members where id = '" + dsid  + "'");
    }
    c.close();
  }

  public static void testLocal(int number, ResultSet[] rs1, ProcedureExecutionContext pec) throws SQLException {
    Connection c = pec.getConnection();
    getGlobalLogger().info("executing testLocal on id: " +
        InternalDistributedSystem.getConnectedInstance().getProperties());
    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery("<LOCAL> SELECT ID, NAME from APP.EMP where ID='1'");
    }
    else {
      rs1[0] = c.createStatement().executeQuery("SELECT ID, NAME from APP.EMP where ID='1'");
    }
    
    Misc.getCacheLogWriter().fine("KN: exec own");
    ResultSet rs = c.createStatement().executeQuery("<LOCAL> SELECT ID, NAME from APP.EMP where ID='1'");
    
    while(rs.next()) {
      Misc.getCacheLogWriter().fine("KN: " + rs.getObject(1) + ", " + rs.getObject(2));
    }
    Misc.getCacheLogWriter().fine("KN: exec own ends");
  }
  
  public void testDAPLocalBug() throws Exception {
    startVMs(1, 2);
    
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("CREATE TABLE APP.EMP (ID VARCHAR(10) NOT NULL, NAME VARCHAR(25), CONSTRAINT EMP_PK PRIMARY KEY (ID)) PARTITION BY PRIMARY KEY REDUNDANCY 1");
    //st.execute("CREATE TABLE APP.EMP (ID VARCHAR(10) NOT NULL, NAME VARCHAR(25), CONSTRAINT EMP_PK PRIMARY KEY (ID)) PARTITION BY column(NAME) REDUNDANCY 1");
    st.execute("insert into APP.EMP values('1', 'one'), ('2', 'two'), ('3', 'three')");
    st.execute("CREATE PROCEDURE TESTLOCAL(loc INT) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
        + ProcedureTestDUnit.class.getName() + ".testLocal' "
        + "DYNAMIC RESULT SETS 1");
    CallableStatement cs = conn.prepareCall(
    "CALL TESTLOCAL(?) on table APP.EMP");
    cs.setInt(1, 3);
    cs.execute();
    
    int rsIndex = 0;
    int cnt = 0;
    do {
      ++rsIndex;
      ResultSet rs = cs.getResultSet();
      while (rs.next()) {
        cnt++;

        System.out.println("KN: " + rs.getObject(1) + ", " + rs.getObject(2));
        
      }
    } while (cs.getMoreResults());
    assertEquals(1, rsIndex);
    assertEquals(1, cnt);
    conn.close();
  }
  
  public void testExecuteOnServerGroupProcedureCall() throws Exception {
    startServerVMs(3, 0, "sg1");
    startServerVMs(1, 0, "SG2");
    //startClientVMs(1, 0, null);

    int clientPort = startNetworkServer(2, null, null);
    Connection conn = TestUtil.getNetConnection(clientPort, null, null);

    serverSQLExecute(1, "CREATE PROCEDURE SERVER_GROUP_PROC(number INT) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
        + ProcedureTestDUnit.class.getName() + ".serverGroupProc' "
        + "DYNAMIC RESULT SETS 4");
    CallableStatement cs = conn.prepareCall(
        "CALL SERVER_GROUP_PROC(?) ON SERVER GROUPS (sg1)");
    cs.setInt(1, 3);
    cs.execute();

    int rsIndex = -1;
    do {
      ++rsIndex;
      int rowIndex = 0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row = "";
        for (int i = 1; i <= rowCount; ++i) {
          Object value = rs.getObject(i);
          row += value.toString();
          assertTrue(row, row.equalsIgnoreCase("sg1"));
        }
        getLogWriter().info(
            "testExecuteQueryWithDataAwareProcedureCall row=" + row
                + " resultset index=" + rsIndex + " rowIndex=" + rowIndex);
      }
    } while (cs.getMoreResults());
    conn.close();
  }

  public void testNCExecuteOnServerGroupProcedureCall() throws Exception {
    startServerVMs(3, 0, "sg1");
    startServerVMs(1, 0, "SG2");
    int netPort = startNetworkServer(2, null, null);
    final Connection netConn = TestUtil.getNetConnection(netPort, null, null);
    Statement stmt = netConn.createStatement();
    stmt.execute("CREATE PROCEDURE SERVER_GROUP_PROC(number INT) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
        + ProcedureTestDUnit.class.getName() + ".serverGroupProc' "
        + "DYNAMIC RESULT SETS 4");
    CallableStatement cs = netConn.prepareCall(
        "CALL SERVER_GROUP_PROC(4)");
    cs.execute();

    int rsNumber = 0;
    do {
      ++rsNumber;
      int rowIndex = 0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row = "";
        for (int i = 1; i <= rowCount; ++i) {
          Object value = rs.getObject(i);
          row += value.toString();
          assertTrue(row.equalsIgnoreCase("sg1") || row.equalsIgnoreCase("sg2"));
        }
        getLogWriter().info(
            "testNCExecuteQueryWithDataAwareProcedureCall row=" + row
                + " resultset index=" + rsNumber + " rowIndex=" + rowIndex);
      }
    } while (cs.getMoreResults());
    assertEquals(4, rsNumber);
    cs.close();
  }

  public void testExecuteOnServerGroupProcedureCallWithWait() throws Exception {
    startServerVMs(3, 0, "sg1");
    startServerVMs(1, 0, "SG2");
    startClientVMs(1, 0, null);
    clientSQLExecute(1, "CREATE PROCEDURE SERVER_GROUP_PROC_WAIT(number INT) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".serverGroupProcWith5SecWait' "
          + "DYNAMIC RESULT SETS 4");
    CallableStatement cs = prepareCall("CALL SERVER_GROUP_PROC_WAIT(?) ON SERVER GROUPS (sg1)");
    cs.setInt(1, 3);
    long start = System.currentTimeMillis();
    cs.execute();
    long end = System.currentTimeMillis();
    long wait = end - start;
    assertTrue(wait >= 5000);
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();
          assertTrue(row.equalsIgnoreCase("sg1"));
        }
        System.out.println("XXX testExecuteQueryWithDataAwareProcedureCall the row="+row+ " resultset index="+rsIndex+ " rowIndex="+rowIndex);
      }
    } while (cs.getMoreResults());
  }
  
  public void testExecuteOnServerGroupProcedureCallWithNoWait() throws Exception {
    startServerVMs(3, 0, "sg1");
    startServerVMs(1, 0, "SG2");
    startClientVMs(1, 0, null);
    clientSQLExecute(1, "CREATE PROCEDURE SERVER_GROUP_PROC_WAIT(number INT) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".serverGroupProcWith5SecWait' "
          + "DYNAMIC RESULT SETS 4");
    CallableStatement cs = prepareCall("CALL SERVER_GROUP_PROC_WAIT(?) ON SERVER GROUPS (sg1) NOWAIT");
    cs.setInt(1, 3);
    long start = System.currentTimeMillis();
    cs.execute();
    long end = System.currentTimeMillis();
    long wait = end - start;
    assertTrue(wait < 50);
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();
          assertTrue(row.equalsIgnoreCase("sg1"));
        }
        System.out.println("XXX testExecuteQueryWithDataAwareProcedureCall the row="+row+ " resultset index="+rsIndex+ " rowIndex="+rowIndex);
      }
    } while (cs.getMoreResults());
  }
  
  public void testExecuteQueryWithDataAwareProcedureCall()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_DYNAMIC_RESULTS(?) ON TABLE EMP.PARTITIONTESTTABLE WHERE SECONDID in (?,?,?) AND THIRDID='3'");
    cs.setInt(1, 2);
    cs.setInt(2, 3);
    cs.setInt(3, 4);
    cs.setInt(4, 5);
    cs.execute();
    
    String[][] results=new String[2][2];
    results[0][0]="1";
    results[0][1]="1";
    
    results[1][0]="1";
    results[1][1]="1";
             
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();          
          
        }
        System.out.println("XXX testExecuteQueryWithDataAwareProcedureCall the row="+row+ "resultset index="+rsIndex+ " rowIndex="+rowIndex);
        if(rsIndex>1 || rowIndex>1) {
          fail("the result is not correct!");
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
      if(rsIndex<=1 && rowIndex!=2) {      
         fail("the number of row to be excpected is "+2+ " not "+rowIndex);
      }      
    } while (cs.getMoreResults());
    
    if(rsIndex!=3) {
      fail("the number of result sets to be excpected is 4 not" + (rsIndex+1));
    }
    
  }
  


  // !!ezoerner
  // disabled because after the merge from fn_ha_admin branch, now getting an NPE
  // testExecuteQueryWithDataAwareProcedureCallWithGlobalEscape(com.pivotal.gemfirexd.dataawareprocedure.ProcedureTestDUnit)
  // java.lang.NullPointerException
  //    at com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData.getColumnCount(EmbedResultSetMetaData.java:81)
  //    at com.pivotal.gemfirexd.dataawareprocedure.ProcedureTestDUnit.testExecuteQueryWithDataAwareProcedureCallWithGlobalEscape(ProcedureTestDUnit.java:112)
  public void testExecuteQueryWithDataAwareProcedureCallWithGlobalEscape()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_DYNAMIC_RESULTS_WITH_GLOBAL(?) ON TABLE EMP.PARTITIONTESTTABLE WHERE SECONDID=4 AND THIRDID='3'");
    cs.setInt(1, 2);
    
    cs.execute();
    
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3') "); 
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (4, 4, '3') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (5, 5, '3') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '2') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4') ");
    Set<String> expectedResults=new HashSet<String>();
    expectedResults.add("223");   
    expectedResults.add("333");
    expectedResults.add("443");
    expectedResults.add("553");
    expectedResults.add("222");
    expectedResults.add("224");
   
         
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      Set<String> results=new HashSet<String>();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();          
          
        }
        if(expectedResults.contains(row)) {
           if(results.contains(row)) {
             fail("the duplicated row"+row);
           }
           else {
             results.add(row);
           }
        }
        else {
          fail("the expected results do not contain this row "+row);
        }
        System.out.println("XXX testExecuteQueryWithDataAwareProcedureCall the row="+row+ "resultset index="+rsIndex+ " rowIndex="+rowIndex);
     }
     if(rsIndex<2) {
       if(results.size()!=expectedResults.size()) {
         fail("The number of rows in the results is supposed to be "+expectedResults.size()+ " not "+results.size());
       }
         
     }
     else {
       if(results.size()!=0) {
         fail("The number of rows in the results is supposed to be "+0+ " not "+results.size());
       }
     }
     
    } while (cs.getMoreResults());
    
    if(rsIndex!=3) {
      fail("the number of result sets to be excpected is 4 not" + (rsIndex+1));
    }
    
  }
  
  // !!!:ezoerner:20090831 fails with
  // junit.framework.AssertionFailedError: The result set is supposed to be empty!
  // Neeraj::20100317
  // This test is invalid because optimizedForWrite has been made as true which
  // means procedure will be routed to a node irrespective of whether data is there
  // or not in the system.
  public void _testExecuteQueryWithDataAwareProcedureCallWithLocalEscape()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_DYNAMIC_RESULTS_WITH_LOCAL(?) ON TABLE EMP.PARTITIONTESTTABLE WHERE SECONDID=4 AND THIRDID='3'");
    cs.setInt(1, 2);
    
    cs.execute();
    
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3') "); 
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (4, 4, '3') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (5, 5, '3') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '2') ");
   // clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4') ");
   // Set<String> expectedResults=new HashSet<String>();
   // expectedResults.add("223");   
   // expectedResults.add("333");
   // expectedResults.add("443");
   // expectedResults.add("553");
   // expectedResults.add("224");
         
    int rsIndex=-1;
    do {
      ++rsIndex;      
      ResultSet rs = cs.getResultSet();    
      while (rs.next()) {
           fail("The result set is supposed to be empty!");
      }     
    } while (cs.getMoreResults());
    
    if(rsIndex!=3) {
      fail("the number of result sets to be excpected is 4 not" + (rsIndex+1));
    }
    
  }
  
  public void testDataAwareProcedureCallUsingGlobalIndex()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_DYNAMIC_RESULTS(?) ON TABLE EMP.PARTITIONTESTTABLE1 WHERE SECONDID in (?,?,?) AND THIRDID='3'");
    cs.setInt(1, 2);
    cs.setInt(2, 3);
    cs.setInt(3, 4);
    cs.setInt(4, 5);
    cs.execute();
    
    String[][] results=new String[2][2];
    results[0][0]="1";
    results[0][1]="1";
    
    results[1][0]="1";
    results[1][1]="1";
             
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();          
          
        }
        if(rsIndex>1 || rowIndex>1) {
          fail("the result is not correct!");
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
      if(rsIndex<=1 && rowIndex!=2) {      
         fail("the number of row to be excpected is "+2+ " not "+rowIndex);
      }      
    } while (cs.getMoreResults());
    
    if(rsIndex!=3) {
      fail("the number of result sets to be excpected is 4 not" + (rsIndex+1));
    }
  
  }
  
  // !!ezoerner disabled due to NPE
  // Caused by: java.lang.NullPointerException
  //      at com.pivotal.gemfirexd.internal.engine.procedure.coordinate.ProcedureResultCollector.initializeResultSets(ProcedureResultCollector.java:360)
  //      at com.pivotal.gemfirexd.internal.engine.procedure.coordinate.ProcedureProxy.execute(ProcedureProxy.java:250)
  public void testDataAwareProcedureWithoutResultSets()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL PROCEDURE_WITHOUT_RESULTSET(?, ?) ON TABLE EMP.PARTITIONTESTTABLE WHERE SECONDID=4 AND THIRDID='3'");
    cs.registerOutParameter(2, java.sql.Types.VARCHAR, 20);
    int number=2;
    String name="INOUTPARAMETER";
    cs.setInt(1, number);
    cs.setString(2, name);
   
    
    cs.execute();
    
    ResultSet rs=cs.getResultSet();
    if(rs!=null || cs.getMoreResults()) {
      fail("no dynamic result set for the procedure!");
    }
    
    ParameterMetaData pmd=cs.getParameterMetaData();  
    int numParameters=pmd.getParameterCount();
    assertTrue(" the number of parameter is 2", numParameters==2);
    try {
      cs.getInt(1);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }
    Object parameter2=cs.getObject(2);    
    assertTrue("the second inout parameter is "+name+number, parameter2.equals(name+number));
      
    

  }
  
  // !!ezoerner disabled due to NPE
  public void testDataAwareProcedureWithoutResultSetsUsingGlobalIndex()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL PROCEDURE_WITHOUT_RESULTSET(?, ?) ON TABLE EMP.PARTITIONTESTTABLE1 WHERE SECONDID=4 AND THIRDID='3'");
    cs.registerOutParameter(2, java.sql.Types.VARCHAR, 20);
    int number=2;
    String name="INOUTPARAMETER";
    cs.setInt(1, number);
    cs.setString(2, name);
   
    
    cs.execute();
    
    ResultSet rs=cs.getResultSet();
    if(rs!=null || cs.getMoreResults()) {
      fail("no dynamic result set for the procedure!");
    }
    
    ParameterMetaData pmd=cs.getParameterMetaData();  
    int numParameters=pmd.getParameterCount();
    assertTrue(" the number of parameter is 2", numParameters==2);
    try {
      cs.getInt(1);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }
    Object parameter2=cs.getObject(2);    
    assertTrue("the second inout parameter is "+name+number, parameter2.equals(name+number));
      
    

  }
    
  // !ezoerner disabled due to NPE
  public void testDataAwareProcedureWithoutResultSetsAndOutParameters()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL PROCEDURE_WITHOUT_RESULTSET_OUTPARAMETER(?, ?) " +
                "ON TABLE EMP.PARTITIONTESTTABLE WHERE SECONDID=4 AND THIRDID='3'");    
    int number=2;
    String name="INOUTPARAMETER";
    cs.setInt(1, number);
    cs.setString(2, name);
   
    
    cs.execute();
    
    ResultSet rs=cs.getResultSet();
    if(rs!=null || cs.getMoreResults()) {
      fail("no dynamic result set for the procedure!");
    }
    
    ParameterMetaData pmd=cs.getParameterMetaData();  
    int numParameters=pmd.getParameterCount();
    assertTrue(" the number of parameter is 2", numParameters==2);
    try {
      cs.getInt(1);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }
    
    try {
      cs.getObject(2);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }                   
  }
  
  // !!ezoerner disabled due to NPE
  public void testDataAwareProcedureWithoutResultSetsAndOutParametersAndQuestionMark()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL PROCEDURE_WITHOUT_RESULTSET_OUTPARAMETER(2, 'INOUTPARAMETER')" +
                " ON TABLE EMP.PARTITIONTESTTABLE WHERE SECONDID=4 AND THIRDID='3'");            
    cs.execute();
    
    ResultSet rs=cs.getResultSet();
    if(rs!=null || cs.getMoreResults()) {
      fail("no dynamic result set for the procedure!");
    }
    
    ParameterMetaData pmd=cs.getParameterMetaData();  
    int numParameters=pmd.getParameterCount();
    assertTrue(" the number of parameter is 0", numParameters==0);
    try {
      cs.getInt(1);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }
    
    try {
      cs.getObject(2);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }                   
  }
  
  public void testProcedureWithoutResultSetsAndOutParameters()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL PROCEDURE_WITHOUT_RESULTSET_OUTPARAMETER(?, ?)");    
    int number=2;
    String name="INOUTPARAMETER";
    cs.setInt(1, number);
    cs.setString(2, name);
   
    
    cs.execute();
    
    ResultSet rs=cs.getResultSet();
    if(rs!=null || cs.getMoreResults()) {
      fail("no dynamic result set for the procedure!");
    }
    
    ParameterMetaData pmd=cs.getParameterMetaData();  
    int numParameters=pmd.getParameterCount();
    assertTrue(" the number of parameter is 2", numParameters==2);
    try {
      cs.getInt(1);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }
    
    try {
      cs.getObject(2);
      fail("the in parameteter cannot be read!");
    } catch (Exception e) {
      
    }                   
  }
  
  // !!ezoerner disabled due to NPE
  // testDataAwareProcedureWithOutgoingResultSets(com.pivotal.gemfirexd.dataawareprocedure.ProcedureTestDUnit)
  // java.lang.NullPointerException
  //      at com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData.getColumnCount(EmbedResultSetMetaData.java:81)
  //      at com.pivotal.gemfirexd.dataawareprocedure.ProcedureTestDUnit.testDataAwareProcedureWithOutgoingResultSets(ProcedureTestDUnit.java:442)
  public void testDataAwareProcedureWithOutgoingResultSets()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_OUTGOING_RESULTS(?) ON TABLE EMP.PARTITIONTESTTABLE WHERE SECONDID=4 AND THIRDID='3'");
    int number=2;
    cs.setInt(1, number);   
    cs.execute();
    
    String[][] results=new String[2][10];
    results[0][0]="1";
    for(int i=0; i<10; i++) {
      results[1][i]=i+"String"+i;
    }  
    
    int[] numRows={0,9};
    
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();          
        }
        if(rsIndex>1 || rowIndex>numRows[rsIndex]) {
          fail("the result is not correct!");
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
    } while (cs.getMoreResults());           
  }
  
  
  public void testDataAwareProcedureWithOutgoingResultSetsAndNoRoutingObjects()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_OUTGOING_RESULTS(?) ON TABLE EMP.PARTITIONRANGETESTTABLE WHERE SECONDID in (?, ?, ?)");
    int number=2;
    cs.setInt(1, number);   
    
    cs.setInt(2, 2);
    cs.setInt(3, 3);
    cs.setInt(4, 4);
    
    cs.execute();
    
    String[][] results=new String[2][20];
    results[0][0]="1";
    results[0][1]="1";
    for(int i=0; i<10; i++) {
      results[1][i]=i+"String"+i;
      results[1][i+10]=i+"String"+i;
    }  
    
    int[] numRows={2,20,0,0};
    
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString(); 
          
        }
        if(rsIndex>1 || rowIndex>=numRows[rsIndex]) {
          fail("the result is not correct! "+" ResultSet Index="+rsIndex+" row count="+rowIndex+" expected Row count="+numRows[rsIndex] );
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
      if(rowIndex!=numRows[rsIndex]){
         fail("The "+rsIndex+" result set has "+numRows[rsIndex]+" instead of "+rowIndex);
      }
      
    } while (cs.getMoreResults());
    if(rsIndex!=3) {
       fail("The number of result sets is 4! instead of "+(rsIndex+1));
    }
  }

  
  public void testDataAwareProcedureWithOutgoingResultSetsOnAll()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_OUTGOING_RESULTS(?) ON ALL");
    int number=2;
    cs.setInt(1, number);   
    cs.execute();
    
    String[][] results=new String[2][30];
    results[0][0]="1";
    results[0][1]="1";
    results[0][2]="1";
    for(int i=0; i<10; i++) {
      results[1][i]=i+"String"+i;
      results[1][i+10]=i+"String"+i;
      results[1][i+20]=i+"String"+i; 
    }  
    
    int[] numRows={3,30,0,0};
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString(); 
          
        }
        if(rsIndex>1 || rowIndex>=numRows[rsIndex]) {
          fail("the result is not correct! "+" ResultSet Index="+rsIndex+" row count="+rowIndex+" expected Row count="+numRows[rsIndex] );
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
      if(rowIndex!=numRows[rsIndex]){
         fail("The "+rsIndex+" result set has "+numRows[rsIndex]+" instead of "+rowIndex);
      }
      
    } while (cs.getMoreResults());
    if(rsIndex!=3) {
       fail("The number of result sets is 4! instead of "+(rsIndex+1));
    }
  }
  
  //Neeraj::20100317
  // This test is invalid because optimizedForWrite has been made as true which
  // means procedure will be routed to a node irrespective of whether data is there
  // or not in the system.
  public void _testDataAwareProcedureWithOutgoingResultSetsWithoutNodesToExecute()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_OUTGOING_RESULTS(?) ON TABLE EMP.PARTITIONEMPTYTESTTABLE WHERE ID in (?, ?, ?)");
    int number=2;
    cs.setInt(1, number);  
    cs.setInt(2, 2);
    cs.setInt(3, 3);
    cs.setInt(4, 4);
    
    cs.execute();
        
    do {          
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      if(rowCount!=0 || rs.next()) {
         fail("The result set is supposed to be empty!");
      }
    } while (cs.getMoreResults());           
  }

  /**
   * This test also forces out of order result delivery and checks for results.
   */
  public void testDataAwareProcedureWithOutgoingResultSetsOnRangePartitionTable()
      throws Exception {

    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_OUTGOING_RESULTS(?) "
        + "ON TABLE EMP.PARTITIONRANGETESTTABLE WHERE ID>1 and ID<15");
    int number = 2;

    // select a random message between 1 and 4 to delay for disordered receive
    final int delaySeq = PartitionedRegion.rand.nextInt(4) + 1;
    serverExecute(1, new SerializableRunnable() {      
      @Override
      public void run() {
        // observer to force out of order delivery of ResultSets
        GemFireXDQueryObserverHolder.putInstance(
            new GemFireXDQueryObserverAdapter() {
          @Override
          public void beforeProcedureChunkMessageSend(ProcedureChunkMessage m) {
            if (m.getSeqNumber() == delaySeq) {
              // introduce an artificial delay
              try {
                Thread.sleep(2000);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                CacheFactory.getAnyInstance();
              }
            }
          }
        });
      }
    });

    cs.setInt(1, number);
    cs.execute();

    String[][] results = new String[2][20];
    results[0][0] = "1";
    results[0][1] = "1";
    for (int i = 0; i < 10; i++) {
      results[1][i] = i + "String" + i;
      results[1][i + 10] = i + "String" + i;
    }
    int[] numRows = { 2, 20, 0, 0 };
    int rsIndex = -1;
    do {
      ++rsIndex;
      int rowIndex = 0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row = "";
        for (int i = 1; i <= rowCount; ++i) {
          Object value = rs.getObject(i);
          row += value.toString();
        }
        getLogWriter().info("XXX row index=" + rowIndex + " row=" + row);
        if (rsIndex > 1 || rowIndex >= numRows[rsIndex]) {
          fail("the result is not correct! " + " ResultSet Index=" + rsIndex
              + " row count=" + rowIndex + " expected Row count="
              + numRows[rsIndex]);
        }
        if (!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
      if (rowIndex != numRows[rsIndex]) {
        fail("The " + rsIndex + " result set has " + numRows[rsIndex]
            + " instead of " + rowIndex);
      }
    } while (cs.getMoreResults());
    if (rsIndex != 3) {
      fail("The number of result sets should 4 instead of " + (rsIndex + 1));
    }

    serverExecute(1, new SerializableRunnable() {      
      @Override
      public void run() {
        GemFireXDQueryObserverHolder.clearInstance();
      }
    });
  }

  public void testDataAwareProcedureWithOutgoingResultSetsOnListPartitionTable()
  throws Exception {
    
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_OUTGOING_RESULTS(?) ON TABLE EMP.PARTITIONLISTTESTTABLE WHERE ID in (?, ?, ?)");
    int number=2;
    cs.setInt(1, number);   
    cs.setInt(2, 10);
    cs.setInt(3, 15);
    cs.setInt(4, 5);
    cs.execute();
    
    String[][] results=new String[2][20];
    results[0][0]="1";
    results[0][1]="1";
    for(int i=0; i<10; i++) {
      results[1][i]=i+"String"+i;
      results[1][i+10]=i+"String"+i;
    }  
    
    int[] numRows={2,20,0,0};
    
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString(); 
          
        }
        System.out.println("XXX row index="+rowIndex+ " row="+row);
        if(rsIndex>1 || rowIndex>=numRows[rsIndex]) {
          fail("the result is not correct! "+" ResultSet Index="+rsIndex+" row count="+rowIndex+" expected Row count="+numRows[rsIndex] );
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
      if(rowIndex!=numRows[rsIndex]){
         fail("The "+rsIndex+" result set has "+numRows[rsIndex]+" instead of "+rowIndex);
      }
      
    } while (cs.getMoreResults());
    if(rsIndex!=3) {
       fail("The number of result sets is 4! instead of "+(rsIndex+1));
    }
  }

  
  /**
   * Tests that <code>CallableStatement.executeQuery()</code> fails
   * when multiple result sets are returned.
   * @exception SQLException if a database error occurs
   */
  public void testExecuteQueryWithOutgoingResultSetInDerbyCall()
      throws Exception {
    setup();
    CallableStatement cs = prepareCall("CALL RETRIEVE_OUTGOING_RESULTS(?)");
    cs.setInt(1, 2);
    cs.execute();
    String[][] results=new String[2][10];
    results[0][0]="1";
    for(int i=0; i<10; i++) {
      results[1][i]=i+"String"+i;
    }  
    
    int[] numRows={0,9};
    
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();          
        }
        if(rsIndex>1 || rowIndex>numRows[rsIndex]) {
          fail("the result is not correct!");
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
    } while (cs.getMoreResults());           

  }
  
  public void testCallProcedureWithInAndOutParameter()
     throws Exception  {
    
    setup();
    int number=2;
    CallableStatement cs = prepareCall("CALL PROCEDURE_INOUT_PARAMETERS(?, ?, ?)");
    cs.setInt(1, number);
    cs.registerOutParameter(2, java.sql.Types.VARCHAR);
    cs.setString(2, "INOUT_PARAMETER");
    cs.registerOutParameter(3, java.sql.Types.INTEGER);
    cs.execute();
    
    String[][] results=new String[2][1];
    results[0][0]="1";
    results[1][0]="1";
             
    int rsIndex=-1;
    do {
      ++rsIndex;
      int rowIndex=0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row="";
        for (int i = 1; i <=rowCount; ++i) {
          Object value = rs.getObject(i);
          row+=value.toString();          
        }
        if(rsIndex>1 || rowIndex>1) {
          fail("the result is not correct!");
        }
        if(!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
    } while (cs.getMoreResults());    
    
    String outValue=cs.getString(2);
    String outParameter="INOUT_PARAMETER"+"Modified";
    if(!outValue.equals(outParameter)) {
      fail("the out parameter is supposed to "+outParameter+" but "+outValue);
    }
    
    int parameter3=cs.getInt(3);    
    if(parameter3!=number) {
      fail("the out parameter is supposed to "+number+" but "+parameter3);
    }
             
  }

  public void testDataAwareProcedureWithInAndOutParameter() throws Exception {
    setup();
    int number = 2;
    CallableStatement cs = prepareCall("CALL PROCEDURE_INOUT_PARAMETERS("
        + "?, ?, ?) ON TABLE EMP.PARTITIONTESTTABLE "
        + "WHERE SECONDID=4 AND THIRDID='3'");
    cs.setInt(1, number);
    cs.registerOutParameter(2, java.sql.Types.VARCHAR);
    cs.setString(2, "INOUT_PARAMETER");
    cs.registerOutParameter(3, java.sql.Types.INTEGER);
    cs.execute();

    String[][] results = new String[2][2];
    results[0][0] = "1";
    results[0][1] = "1";

    results[1][0] = "1";
    results[1][1] = "1";

    int rsIndex = -1;
    do {
      ++rsIndex;
      int rowIndex = 0;
      ResultSet rs = cs.getResultSet();
      ResultSetMetaData metaData = rs.getMetaData();
      int rowCount = metaData.getColumnCount();
      while (rs.next()) {
        String row = "";
        for (int i = 1; i <= rowCount; ++i) {
          Object value = rs.getObject(i);
          row += value.toString();

        }
        if (rsIndex > 1 || rowIndex > 1) {
          fail("the result is not correct!");
        }
        if (!row.equals(results[rsIndex][rowIndex])) {
          fail("the result is not correct!");
        }
        ++rowIndex;
      }
      if (rsIndex <= 1 && rowIndex != 1) {
        fail("the number of row to be excpected is " + 1 + " not " + rowIndex);
      }
    } while (cs.getMoreResults());

    if (rsIndex != 3) {
      fail("the number of result sets to be excpected is 4 not" + (rsIndex + 1));
    }

    String outValue = cs.getString(2);
    String outParameter = "INOUT_PARAMETER" + "Modified";
    if (!outValue.equals(outParameter)) {
      fail("the out parameter is supposed to " + outParameter + " but "
          + outValue);
    }

    int parameter3 = cs.getInt(3);
    if (parameter3 != number) {
      fail("the out parameter is supposed to " + number + " but " + parameter3);
    }

    // check for VM not crashing after a StackOverflowError

    Statement stmt = cs.getConnection().createStatement();
    stmt.execute("CREATE PROCEDURE CHECK_STACK_OVERFLOW(number INT) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
        + ProcedureTestDUnit.class.getName() + ".checkStackOverflow' "
        + "DYNAMIC RESULT SETS 1");
    try {
      stmt.execute("call CHECK_STACK_OVERFLOW(-1) on table SIMPLE_TABLE");
      fail("expected an exception due to stack overflow");
    } catch (SQLException sqle) {
      if (!"38000".equals(sqle.getSQLState())) {
        throw sqle;
      }
      Throwable cause = sqle.getCause();
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      if (!(cause instanceof StackOverflowError)) {
        throw sqle;
      }
    }

    // now check everything should be still working fine
    assertTrue(stmt
        .execute("call CHECK_STACK_OVERFLOW(1) on table SIMPLE_TABLE"));
    ResultSet rs = stmt.getResultSet();
    assertEquals(1, rs.getMetaData().getColumnCount());
    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    assertFalse(rs.next());
    assertFalse(stmt.getMoreResults());

    assertEquals(4, stmt.executeUpdate("insert into simple_table "
        + "values (1), (2), (3), (4)"));
    int cnt = 0;
    assertTrue(stmt
        .execute("call CHECK_STACK_OVERFLOW(10) on table SIMPLE_TABLE"));
    rs = stmt.getResultSet();
    assertEquals(1, rs.getMetaData().getColumnCount());
    assertTrue(rs.next());
    cnt += rs.getInt(1);
    assertTrue(rs.next());
    cnt += rs.getInt(1);
    assertFalse(rs.next());
    assertFalse(stmt.getMoreResults());
    assertEquals(4, cnt);
  }
  
  public void testDAPRelatedStatsOnTable() throws Exception {
    startVMs(1, 2);

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("CREATE TABLE APP.EMP (ID VARCHAR(10) NOT NULL, NAME VARCHAR(25), "
        + "CONSTRAINT EMP_PK PRIMARY KEY (ID)) PARTITION BY PRIMARY KEY REDUNDANCY 1");
    st.execute("insert into APP.EMP values('1', 'one'), ('2', 'two'), ('3', 'three')");
    st.execute("CREATE PROCEDURE TESTLOCAL(loc INT) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
        + ProcedureTestDUnit.class.getName() + ".testLocal' "
        + "DYNAMIC RESULT SETS 1");
    CallableStatement cs = conn
        .prepareCall("CALL TESTLOCAL(?) on table APP.EMP");
    cs.setInt(1, 3);
    cs.execute();

    assertEquals(true, verifyAccessorDapStats().booleanValue());

    VM dataNode1 = this.serverVMs.get(0);
    assertEquals(true, ((Boolean) dataNode1.invoke(ProcedureTestDUnit.class,
        "verifyDataNodeDapStats")).booleanValue());

    VM dataNode2 = this.serverVMs.get(0);
    assertEquals(true, ((Boolean) dataNode2.invoke(ProcedureTestDUnit.class,
        "verifyDataNodeDapStats")).booleanValue());
    conn.close();
  }

  public static Boolean verifyAccessorDapStats() {
    final InternalDistributedSystem iDS = Misc.getDistributedSystem();
    WaitCriterion waitForStat = new WaitCriterion() {
      @Override
      public boolean done() {
        FunctionStats stats = FunctionStats.getFunctionStats(
            DistributedProcedureCallFunction.FUNCTIONID, iDS);
        return stats.getFunctionExecutionsCompletedDN() == 0;
      }

      @Override
      public String description() {
        return "waiting for DAP call statistic on accessor";
      }
    };
    waitForCriterion(waitForStat, 60000, 500, false);
    FunctionStats stats = FunctionStats.getFunctionStats(
        DistributedProcedureCallFunction.FUNCTIONID, iDS);

    return stats.getFunctionExecutionsCompletedDN() == 0;
  }

  public static Boolean verifyDataNodeDapStats() {
    final InternalDistributedSystem iDS = Misc.getDistributedSystem();
    WaitCriterion waitForStat = new WaitCriterion() {
      @Override
      public boolean done() {
        FunctionStats stats = FunctionStats.getFunctionStats(
            DistributedProcedureCallFunction.FUNCTIONID, iDS);
        return stats.getFunctionExecutionsCompletedDN() != 0;
      }

      @Override
      public String description() {
        return "waiting for DAP call statistic on data node";
      }
    };
    waitForCriterion(waitForStat, 60000, 500, false);
    FunctionStats stats = FunctionStats.getFunctionStats(
        DistributedProcedureCallFunction.FUNCTIONID, iDS);

    return stats.getFunctionExecutionsCompletedDN() != 0;
  }

  public static final void checkStackOverflow(int arg, ResultSet[] rs)
      throws SQLException {
    if (arg == 0) {
      Connection conn = DriverManager.getConnection("jdbc:default:connection");
      rs[0] = conn.createStatement().executeQuery(
          "<local> select count(*) from simple_table");
    }
    else {
      checkStackOverflow(arg - 1, rs);
    }
  }

  /**
   * Procedures that should be created before the tests are run and dropped when
   * the tests have finished. First element in each row is the name of the
   * procedure, second element is SQL which creates it.
   */
  private static final String[] PROCEDURES = {

      "CREATE PROCEDURE RETRIEVE_DYNAMIC_RESULTS(number INT) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".retrieveDynamicResults' "
          + "DYNAMIC RESULT SETS 4",
          
      "CREATE PROCEDURE RETRIEVE_DYNAMIC_RESULTS_WITH_GLOBAL(number INT) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".retrieveDynamicResultsWithGlobalEscape' "
          + "DYNAMIC RESULT SETS 4",   
      
      "CREATE PROCEDURE RETRIEVE_DYNAMIC_RESULTS_WITH_LOCAL(number INT) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".retrieveDynamicResultsWithLocalEscape' "
          + "DYNAMIC RESULT SETS 4",   
         
          
          
      "CREATE PROCEDURE RETRIEVE_OUTGOING_RESULTS(number INT) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".retrieveDynamicResultsWithOutgoingResultSet'"
          + "DYNAMIC RESULT SETS 4",    

      "CREATE PROCEDURE PROCEDURE_INOUT_PARAMETERS(number INT, INOUT name VARCHAR(25), OUT total INT) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".procedureWithInAndOutParameters'"
          + "DYNAMIC RESULT SETS 4",        
        
      "CREATE PROCEDURE PROCEDURE_WITHOUT_RESULTSET(number INT, INOUT name VARCHAR(20) ) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".procedureWithoutDynamicResultSets'",       
          
      "CREATE PROCEDURE PROCEDURE_WITHOUT_RESULTSET_OUTPARAMETER(number INT, name VARCHAR(20) ) "
          + "LANGUAGE JAVA PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".procedureWithoutDynamicResultSetsAndOutParameters'",     
           
      "CREATE PROCEDURE RETRIEVE_CLOSED_RESULT() LANGUAGE JAVA "
          + "PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".retrieveClosedResult' "
          + "DYNAMIC RESULT SETS 1",

      "CREATE PROCEDURE RETRIEVE_EXTERNAL_RESULT("
          + "DBNAME VARCHAR(128), DBUSER VARCHAR(128), DBPWD VARCHAR(128)) LANGUAGE JAVA "
          + "PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".retrieveExternalResult' "
          + "DYNAMIC RESULT SETS 1",

      "CREATE PROCEDURE PROC_WITH_SIDE_EFFECTS(ret INT) LANGUAGE JAVA "
          + "PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".procWithSideEffects' "
          + "DYNAMIC RESULT SETS 2",

      "CREATE PROCEDURE NESTED_RESULT_SETS(proctext VARCHAR(128)) LANGUAGE JAVA "
          + "PARAMETER STYLE JAVA EXTERNAL NAME '"
          + ProcedureTestDUnit.class.getName() + ".nestedDynamicResultSets' "
          + "DYNAMIC RESULT SETS 6"

  };
  
  /**
   * Tables that should be created before the tests are run and
   * dropped when the tests have finished. The tables will be
   * cleared before each test case is run. First element in each row
   * is the name of the table, second element is the SQL text which
   * creates it.
   */
  private static final String[][] TABLES = {
  // SIMPLE_TABLE is used by PROC_WITH_SIDE_EFFECTS
  { "SIMPLE_TABLE", "CREATE TABLE SIMPLE_TABLE (id INT)" }, };

  /**
   * Stored procedure which returns 0, 1, 2, 3 or 4 <code>ResultSet</code>s.
   *
   * @param number the number of <code>ResultSet</code>s to return
   * @param rs1 first <code>ResultSet</code>
   * @param rs2 second <code>ResultSet</code>
   * @param rs3 third <code>ResultSet</code>
   * @param rs4 fourth <code>ResultSet</code>
   * @exception SQLException if a database error occurs
   */
  public static void retrieveDynamicResults(int number, ResultSet[] rs1,
      ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4) throws SQLException {
      Connection c = DriverManager.getConnection("jdbc:default:connection");

    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 1) {
      rs2[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 2) {
      rs3[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 3) {
      rs4[0] = c.createStatement().executeQuery("VALUES(1)");
    }
  }
  
  public static void retrieveDynamicResultsWithGlobalEscape(int number, ResultSet[] rs1,
      ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4) throws SQLException {
      Connection c = DriverManager.getConnection("jdbc:default:connection");

    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery("<GLOBAL> SELECT * FROM EMP.PARTITIONTESTTABLE ");
    }
    if (number > 1) {
      rs2[0] = c.createStatement().executeQuery("<GLOBAL> SELECT * FROM EMP.PARTITIONTESTTABLE ");
    }
    if (number > 2) {
      rs3[0] = c.createStatement().executeQuery("<GLOBAL> SELECT * FROM EMP.PARTITIONTESTTABLE ");
    }
    if (number > 3) {
      rs4[0] = c.createStatement().executeQuery("<GLOBAL> SELECT * FROM EMP.PARTITIONTESTTABLE ");
    }
  }
  
  public static void retrieveDynamicResultsWithLocalEscape(int number, ResultSet[] rs1,
      ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4) throws SQLException {
      Connection c = DriverManager.getConnection("jdbc:default:connection");

    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery("<LOCAL> SELECT * FROM EMP.PARTITIONTESTTABLE WHERE ID=5");
    }
    if (number > 1) {
      rs2[0] = c.createStatement().executeQuery("<LOCAL> SELECT * FROM EMP.PARTITIONTESTTABLE WHERE ID=5");
    }
    if (number > 2) {
      rs3[0] = c.createStatement().executeQuery("<LOCAL> SELECT * FROM EMP.PARTITIONTESTTABLE WHERE ID=5");
    }
    if (number > 3) {
      rs4[0] = c.createStatement().executeQuery("<LOCAL> SELECT * FROM EMP.PARTITIONTESTTABLE WHERE ID=5");
    }
  }
  
  
  public static void procedureWithInAndOutParameters(int number,
      String[] name,
      int[]    total,
      ResultSet[] rs1,
      ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4) throws SQLException {
      Connection c = DriverManager.getConnection("jdbc:default:connection");
    
    name[0]=name[0]+"Modified";
    total[0]=number;
    
    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 1) {
      rs2[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 2) {
      rs3[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 3) {
      rs4[0] = c.createStatement().executeQuery("VALUES(1)");
    }
  }
  
  public static void procedureWithoutDynamicResultSets(int number,
      String[] name) throws SQLException {        
    name[0]=name[0]+number;
        
  }
  
  public static void procedureWithoutDynamicResultSetsAndOutParameters(int number,
      String name) throws SQLException {
      
  }
  /**
   * Stored procedure which returns 0, 1, 2, 3 or 4 <code>ResultSet</code>s.
   *
   * @param number the number of <code>ResultSet</code>s to return
   * @param rs1 first <code>ResultSet</code>
   * @param rs2 second <code>ResultSet</code>
   * @param rs3 third <code>ResultSet</code>
   * @param rs4 fourth <code>ResultSet</code>
   * @exception SQLException if a database error occurs
   */
  public static void retrieveDynamicResultsWithOutgoingResultSet(int number, ResultSet[] rs1,
      ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4, ProcedureExecutionContext pec) 
      throws SQLException {
      Connection c = DriverManager.getConnection("jdbc:default:connection");

    if (number > 0) {
      rs1[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 1) {
      OutgoingResultSet ors=pec.getOutgoingResultSet(2);
      for(int i=0; i<10; ++i) {
        List<Object> row=new ArrayList<Object>();
        row.add(new Integer(i));
        row.add("String"+ i);          
        ors.addRow(row);
      }
      ors.endResults();      
    }
    if (number > 2) {
      rs3[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (number > 3) {
      rs4[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    c.close();
  }

  /**
   * Stored procedure which produces a closed result set.
   *
   * @param closed holder for the closed result set
   * @exception SQLException if a database error occurs
   */
  public static void retrieveClosedResult(ResultSet[] closed)
      throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:default:connection");
    closed[0] = c.createStatement().executeQuery("VALUES(1)");
    closed[0].close();
  }

  /**
   * Stored procedure which produces a result set in another
   * connection.
   *
   * @param external result set from another connection
   * @exception SQLException if a database error occurs
   */
  public static void retrieveExternalResult(String dbName, String user,
      String password, ResultSet[] external) throws SQLException {
    // Use a server-side connection to the same database.
    String url = "jdbc:derby:" + dbName;

    Connection conn = DriverManager.getConnection(url, user, password);

    external[0] = conn.createStatement().executeQuery("VALUES(1)");
  }

  /**
   * Stored procedure which inserts a row into SIMPLE_TABLE and
   * optionally returns result sets.
   *
   * @param returnResults if one, return one result set; if greater
   * than one, return two result sets; otherwise, return no result
   * set
   * @param rs1 first result set to return
   * @param rs2 second result set to return
   * @exception SQLException if a database error occurs
   */
  public static void procWithSideEffects(int returnResults, ResultSet[] rs1,
      ResultSet[] rs2) throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:default:connection");
    Statement stmt = c.createStatement();
    stmt.executeUpdate("INSERT INTO SIMPLE_TABLE VALUES (42)");
    if (returnResults > 0) {
      rs1[0] = c.createStatement().executeQuery("VALUES(1)");
    }
    if (returnResults > 1) {
      rs2[0] = c.createStatement().executeQuery("VALUES(1)");
    }
  }

  /**
   * Method for a Java procedure that calls another procedure
   * and just passes on the dynamic results from that call.
   */
  public static void nestedDynamicResultSets(String procedureText,
      ResultSet[] rs1, ResultSet[] rs2, ResultSet[] rs3, ResultSet[] rs4,
      ResultSet[] rs5, ResultSet[] rs6) throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:default:connection");

    CallableStatement cs = c.prepareCall("CALL " + procedureText);

    cs.execute();

    // Mix up the order of the result sets in the returned
    // parameters, ensures order is defined by creation
    // and not parameter order.
    rs6[0] = cs.getResultSet();
    if (!cs.getMoreResults(Statement.KEEP_CURRENT_RESULT))
      return;
    rs3[0] = cs.getResultSet();
    if (!cs.getMoreResults(Statement.KEEP_CURRENT_RESULT))
      return;
    rs4[0] = cs.getResultSet();
    if (!cs.getMoreResults(Statement.KEEP_CURRENT_RESULT))
      return;
    rs2[0] = cs.getResultSet();
    if (!cs.getMoreResults(Statement.KEEP_CURRENT_RESULT))
      return;
    rs1[0] = cs.getResultSet();
    if (!cs.getMoreResults(Statement.KEEP_CURRENT_RESULT))
      return;
    rs5[0] = cs.getResultSet();

  }

  /**
   * Creates the test suite and wraps it in a <code>TestSetup</code>
   * instance which sets up and tears down the test environment.
   * @return test suite
   */
  private void setup() throws Exception {
    setupVMs();
    setupData();
    setupTablesAndProcedures();
  }
  
  private void setupVMs() throws Exception {
    startVMs(1, 2);
  }

  private void setupData() throws Exception {    
    clientSQLExecute(1,"create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, PRIMARY KEY (SECONDID, THIRDID))");
     //   + " PARTITION BY COLUMN (ID)");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (4, 4, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (5, 5, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '2') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4') ");
    
    clientSQLExecute(1,"SELECT * FROM EMP.PARTITIONTESTTABLE WHERE (SECONDID=10 AND THIRDID='20') OR (SECONDID=10 AND THIRDID='20')");

    clientSQLExecute(1,"create table EMP.PARTITIONTESTTABLE1 (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)");
     //   + " PARTITION BY COLUMN (ID)");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE1 VALUES (2, 2, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE1 VALUES (3, 3, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE1 VALUES (4, 4, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE1 VALUES (5, 5, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE1 VALUES (2, 2, '2') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONTESTTABLE1 VALUES (2, 2, '4') ");

    clientSQLExecute(1,"create table EMP.PARTITIONRANGETESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, PRIMARY KEY (ID)) PARTITION BY RANGE ( ID )"
            + " ( VALUES BETWEEN 1 and 10, VALUES BETWEEN 10 and 20, "
            + "VALUES BETWEEN 20 and 30, VALUES BETWEEN 30 and 40 )");
     //   + " PARTITION BY COLUMN (ID)");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONRANGETESTTABLE VALUES (1, 2, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONRANGETESTTABLE VALUES (10, 3, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONRANGETESTTABLE VALUES (15, 4, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONRANGETESTTABLE VALUES (21, 5, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONRANGETESTTABLE VALUES (30, 2, '2') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONRANGETESTTABLE VALUES (35, 2, '4') ");

    clientSQLExecute(1,"create table EMP.PARTITIONLISTTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, PRIMARY KEY (ID)) PARTITION BY LIST ( ID )"
        + " ( VALUES (0, 5) , VALUES (10, 15), "
        + "VALUES (20, 23), VALUES(25, 30), VALUES(31, 100))");
     //   + " PARTITION BY COLUMN (ID)");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONLISTTESTTABLE VALUES (0, 2, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONLISTTESTTABLE VALUES (5, 3, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONLISTTESTTABLE VALUES (15, 4, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONLISTTESTTABLE VALUES (20, 4, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONLISTTESTTABLE VALUES (30, 5, '3') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONLISTTESTTABLE VALUES (31, 2, '2') ");
    clientSQLExecute(1,"INSERT INTO EMP.PARTITIONLISTTESTTABLE VALUES (25, 2, '4') ");

    clientSQLExecute(1,"create table EMP.PARTITIONEMPTYTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null, PRIMARY KEY (ID)) PARTITION BY COLUMN ( ID )");
  }
  
  
  /**
   * Creates the tables and the stored procedures used in the test cases.
   * 
   * @exception SQLException
   *                    if a database error occurs
   */
  private void setupTablesAndProcedures() throws Exception {
    
    for (int i = 0; i < PROCEDURES.length; i++) {
      clientSQLExecute(1,PROCEDURES[i]);
    }
    for (int i = 0; i < TABLES.length; i++) {
      clientSQLExecute(1,TABLES[i][1]);
    }    
  }
  
  public CallableStatement prepareCall(String sql) throws SQLException {
    /** get a new connection with default properties */
    Connection conn = TestUtil.getConnection();
    CallableStatement cs = conn.prepareCall(sql);   
    return cs;
  }
  
}
