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
/**
 * 
 */
package sql.joinStatements;

import hydra.Log;
import hydra.TestConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.gemstone.gemfire.cache.query.Struct;

import sql.SQLHelper;
import sql.SQLPrms;
import sql.dmlStatements.AbstractDMLStmt;
import sql.sqlutil.ResultSetHelper;
import util.TestException;

/**
 * @author eshu
 *
 */
public class CustPortfSoJoinStmt extends AbstractJoinStmt {
  /*
   *    * trade.customers table fields
   *   private int cid;
   *   String cust_name;
   *   Date since;
   *   String addr;
   *   int tid; //for update or delete unique records
   *
   * trade.Portfolio table fields
   *   int cid;
   *   int sid;
   *   int qty;
   *   int availQty;
   *   BigDecimal subTotal;
   *   int tid; //for update or delete unique records to the thread
   *   
   * trade.orders table fields
   *   int oid;
   *   int cid; //ref customers
   *   int sid; //ref securities sec_id
   *   int qty;
   *   BigDecimal ask;
   *   Timestamp order_time;
   *   String stauts;
   *   int tid; //for update or delete unique records to the thread
   */
  

  protected static String[] uniqSelect = 
  {"select * from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid and c.tid=? order by ask",
   "select f.cid, cust_name, f.sid, so.sid, f.qty, subTotal, ask from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid and status=? and f.tid = ? order by ask",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, oid, order_time, ask from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and subTotal >10000 and c.cid>? and f.tid = ? order by order_time",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, order_time, ask from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and so.cid<? and ask >? and ask <? and f.tid = ? order by so.qty desc, subtotal",
   "select * from trade.customers c LEFT OUTER JOIN trade.portfolio f LEFT OUTER JOIN trade.sellorders so on f.cid = so.cid " +
       (SQLPrms.isSnappyMode() ? " and " : " on ") +
       "c.cid= f.cid where f.tid = ? ",
   "select * from trade.customers c LEFT JOIN trade.portfolio f on c.cid= f.cid LEFT JOIN trade.sellorders so on f.cid = so.cid where f.tid = ? order by so.cid, so.oid"
  };

  protected static String[] nonUniqSelect = {
   "select * from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid order by ask",
   "select f.cid, cust_name, f.sid, so.sid, f.qty, subTotal, ask from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid and status=?  order by ask",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, oid, order_time, ask from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and subTotal >10000 and f.cid>? order by order_time",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, order_time, ask from trade.customers c, trade.portfolio f, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and so.cid<? and ask >? and ask <? order by so.qty desc, subtotal",
   "select * from trade.customers c LEFT OUTER JOIN trade.portfolio f LEFT OUTER JOIN trade.sellorders so on f.cid = so.cid " +
       (SQLPrms.isSnappyMode() ? " and " : " on ") +
       "c.cid= f.cid where so.sid < 100 and so.qty > 500" ,
  };
  

  protected static String[] uniqSelectHdfs = 
  {"select * from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid and c.tid=? order by ask",
   "select f.cid, cust_name, f.sid, so.sid, f.qty, subTotal, ask from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid and status=? and f.tid = ? order by ask",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, oid, order_time, ask from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and subTotal >10000 and c.cid>? and f.tid = ? order by order_time",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, order_time, ask from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and so.cid<? and ask >? and ask <? and f.tid = ? order by so.qty desc, subtotal",
   "select * from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n LEFT OUTER JOIN trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n LEFT OUTER JOIN trade.sellorders so on f.cid = so.cid on c.cid= f.cid where f.tid = ? ",
   "select * from (trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n LEFT JOIN trade.portfolio f  -- GEMFIREXD-PROPERTIES queryHDFS=true \n on c.cid= f.cid) LEFT JOIN trade.sellorders so on f.cid = so.cid where f.tid = ? order by so.cid, so.oid"
  };

  protected static String[] nonUniqSelectHdfs = {
   "select * from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid order by ask",
   "select f.cid, cust_name, f.sid, so.sid, f.qty, subTotal, ask from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and c.cid = so.cid and f.tid = so.tid and status=?  order by ask",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, oid, order_time, ask from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and subTotal >10000 and f.cid>? order by order_time",
   "select f.cid, cust_name, f.sid, so.sid, so.qty, subTotal, order_time, ask from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n, trade.sellorders so where c.cid= f.cid and f.sid = so.sid and c.cid = so.cid and so.cid<? and ask >? and ask <? order by so.qty desc, subtotal",
   "select * from trade.customers c -- GEMFIREXD-PROPERTIES queryHDFS=true \n LEFT OUTER JOIN trade.portfolio f -- GEMFIREXD-PROPERTIES queryHDFS=true \n LEFT OUTER JOIN trade.sellorders so on f.cid = so.cid on c.cid= f.cid where so.sid < 100 and so.qty > 500" ,  
  };
  
  protected static boolean isColocated = checkColocation();
  
  String[] statuses = {                                       
      "cancelled", "open", "filled"                                       
  };
  
  /* (non-Javadoc)
   * @see sql.joinStatements.AbstractJoinStmt#query(java.sql.Connection, java.sql.Connection)
   */
  public void query(Connection dConn, Connection gConn) {
    int whichQuery;
    ResultSet derbyRS;
    ResultSet gfeRS;
    int cid = (dConn !=null ) ? AbstractDMLStmt.getCid(dConn) : AbstractDMLStmt.getCid(gConn); //cid to this thread if uniq
    int maxAsk = 100; //max ask price in the test is 100
    BigDecimal ask1 = new BigDecimal(Integer.toString(rand.nextInt(maxAsk/2))); 
    BigDecimal ask2 = ask1.add(new BigDecimal(Integer.toString(rand.nextInt(maxAsk/2))));
    String status = statuses[rand.nextInt(statuses.length)];
    int tid = AbstractDMLStmt.getMyTid(); 
    
    if (dConn!=null) {
      if (testUniqueKeys) { //test uniqKeys
        whichQuery = rand.nextInt(uniqSelect.length); 
        derbyRS = getUniqQuery(dConn, whichQuery, cid, ask1, ask2, status, tid);
        if (derbyRS == null) {
          Log.getLogWriter().info("Did not execute this query");
          return;
        }
        List<Struct> derbyList = ResultSetHelper.asList(derbyRS, true);
        if (derbyList == null) {
          Log.getLogWriter().info("Not able to convert derby resultSet to a list");
          return;
        }
        gfeRS = getUniqQuery (gConn, whichQuery, cid, ask1, ask2, status, tid);
        if (gfeRS==null && !isColocated) {
          Log.getLogWriter().info("tables are not colocated so not able to verify results");
          return;
        }                	
        List<Struct> gfeList = ResultSetHelper.asList(gfeRS, false);
        if (gfeList==null && isHATest) {
          Log.getLogWriter().info("could not get gfxd results set due to HA");
          return;
        }
        ResultSetHelper.compareResultSets(derbyList, gfeList);   
      } else {
        whichQuery = rand.nextInt(nonUniqSelect.length); 
        derbyRS = getNonUniqQuery(dConn, whichQuery, cid, ask1, ask2, status, tid);
        if (derbyRS == null) {
          Log.getLogWriter().info("Did not execute this query");
          return;
        }
        List<Struct> derbyList = ResultSetHelper.asList(derbyRS, true);
        if (derbyList == null) {
          Log.getLogWriter().info("Not able to convert derby resultSet to a list");
          return;
        }
        gfeRS = getNonUniqQuery (gConn, whichQuery, cid, ask1, ask2, status,tid);
        List<Struct> gfeList = ResultSetHelper.asList(gfeRS, false);
        if (gfeList==null && isHATest) {
          Log.getLogWriter().info("could not get gfxd results set due to HA");
          return;
        }
        ResultSetHelper.compareResultSets(derbyList, gfeList);   
      }
    }// we can verify resultSet
    else {
      whichQuery = rand.nextInt(nonUniqSelect.length);
      gfeRS = getNonUniqQuery (gConn, whichQuery, cid, ask1, ask2, status, tid);
      //could not verify results.
      ResultSetHelper.asList(gfeRS, false);      
    }   
  }
  
  public static ResultSet getUniqQuery(Connection conn, int whichQuery, int cid, 
      BigDecimal ask1, BigDecimal ask2, String status, int tid) {
    boolean[] success = new boolean[1];
    ResultSet rs = null;
    rs = getUniqQuery(conn, whichQuery, cid, ask1, ask2, status, tid, success);
    int count=0;
    while (!success[0]) {
      if (count>=maxNumOfTries) {
        Log.getLogWriter().info("Could not get the lock to finish update in derby, abort this operation");
        return rs;
    }
      count++;
      rs = getUniqQuery(conn, whichQuery, cid, ask1, ask2, status, tid, success);
    } //retry
    return rs;
  }
  
  public static ResultSet getUniqQuery(Connection conn, int whichQuery, int cid, 
      BigDecimal ask1, BigDecimal ask2, String status, int tid, boolean[] success) {
    PreparedStatement stmt;
    ResultSet rs = null;
    success[0] = true;
   try{     
      Boolean hasHdfs = TestConfig.tab().booleanAt(SQLPrms.hasHDFS, false);      
      String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";
      String sql = (!SQLHelper.isDerbyConn(conn) && hasHdfs) ? uniqSelectHdfs[whichQuery] :
          uniqSelect[whichQuery];
      if(whichQuery==4 && SQLHelper.isDerbyConn(conn)) sql = sql.replace("and","on");
      String query =  " QUERY: " +  sql;
      stmt =  conn.prepareStatement(sql) ;
 
      switch (whichQuery){
      case 0:
        Log.getLogWriter().info(database + "Querying CustPortSO  with TID:"+ tid + query);
        stmt.setInt(1, tid);
        break;
      
      case 1:        
        Log.getLogWriter().info(database + "Querying CustPortSO  with STATUS:" + status + ",TID:"+ tid + query);
        stmt.setString(1, status);
        stmt.setInt(2, tid); 
        break;
      
      case 2: 
        Log.getLogWriter().info(database + "Querying CustPortSO  with CID:" + cid  + ",TID:"+ tid + query);
        stmt.setInt(1, cid); 
        stmt.setInt(2, tid);
        break;
  
      case 3:
        Log.getLogWriter().info(database + "Querying CustPortSO  with CID:" + cid + ",1_PRICE:"+ ask1 
            + ",2_PRICE:" + ask2 + ",TID:"+ tid + query);
        stmt.setInt(1, cid); //set cid>?
        stmt.setBigDecimal(2, ask1); 
        stmt.setBigDecimal(3, ask2);
        stmt.setInt(4, tid);
        break;
        
      case 4:
        Log.getLogWriter().info(database + "Querying CustPortSO with LEFT OUTER JOIN  TID:"+ tid + query);
        stmt.setInt(1, tid); 
        break; 
        
      case 5:
        Log.getLogWriter().info(database + "Querying CustPortSO with LEFT JOIN TID:"+ tid + query);
        stmt.setInt(1, tid); 
        break; 
        
      default:
        throw new TestException("incorrect select statement, should not happen");
      }
      rs = stmt.executeQuery();
      /*
      Log.getLogWriter().info("query rs is " +
      		ResultSetHelper.listToString(ResultSetHelper.asList(rs, 
      				isDerby(conn))));
      */
    } catch (SQLException se) {
    	SQLHelper.printSQLException(se);
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire or deadlock
      else if (se.getSQLState().equals("0A000") && testServerGroup && !isColocated) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("got the expected non colocated exception, continuing tests.");
      } //if it is not colocated, it may throw exception
      else      SQLHelper.handleSQLException(se);
    }
    return rs;
  }
  
  //retries when lock could not obtained
  public static ResultSet getNonUniqQuery(Connection conn, int whichQuery, int cid,
      BigDecimal ask1, BigDecimal ask2, String status, int tid) {
    boolean[] success = new boolean[1];
    ResultSet rs = null;
    rs = getNonUniqQuery(conn, whichQuery, cid, ask1, ask2, status, tid, success);
    while (!success[0]) {
      rs = getNonUniqQuery(conn, whichQuery, cid, ask1, ask2, status, tid, success);
    } //retry
    return rs;
  }
  
  public static ResultSet getNonUniqQuery(Connection conn, int whichQuery, int cid,
      BigDecimal ask1, BigDecimal ask2, String status, int tid, boolean[] success) {
    PreparedStatement stmt;
    ResultSet rs = null;
    success[0] = true;
    try {
      
      Boolean hasHdfs = TestConfig.tab().booleanAt(SQLPrms.hasHDFS, false);      
      String database = SQLHelper.isDerbyConn(conn)?"Derby - " :"gemfirexd - ";        
      String query = (! SQLHelper.isDerbyConn(conn) && hasHdfs ) ? " QUERY: " +  nonUniqSelectHdfs[whichQuery] : " QUERY: " +  nonUniqSelect[whichQuery];        
      stmt = (! SQLHelper.isDerbyConn(conn) && hasHdfs )  ? conn.prepareStatement(nonUniqSelectHdfs[whichQuery]) :  conn.prepareStatement(nonUniqSelect[whichQuery]) ; 
      
      
      switch (whichQuery){
      case 0:
        Log.getLogWriter().info(database + "Querying CustPortSO with no data " + query);
        break;
      case 1:
        Log.getLogWriter().info(database + "Querying CustPortSO with STATUS:" + status + query);
        stmt.setString(1, status);
        break;

      case 2:
        Log.getLogWriter().info(database + "Querying CustPortSO with CID:" + cid + query);
        stmt.setInt(1, cid ); 
        break;

      case 3:
        Log.getLogWriter().info(database + "Querying CustPortSO with CID:" + cid + ",1_PRICE:"+ ask1 
            + ",2_PRICE:" + ask2 + query);
        stmt.setInt(1, cid); //set cid>?
        stmt.setBigDecimal(2, ask1); 
        stmt.setBigDecimal(3, ask2);
        break;
        
      case 4:
        Log.getLogWriter().info(database + "Querying CustPortSO with LEFT OUTER JOIN " + query);
        break;

      default:
        throw new TestException("incorrect select statement, should not happen");
      }
      rs = stmt.executeQuery();
    } catch (SQLException se) {
      if (!SQLHelper.checkDerbyException(conn, se)) success[0] = false; //handle lock could not acquire or deadlock
      else if (se.getSQLState().equals("0A000") && testServerGroup && !isColocated) {
        SQLHelper.printSQLException(se);
        Log.getLogWriter().info("got the expected non colocated exception, continuing tests.");
      } //if it is not colocated, it may throw exception
      else      SQLHelper.handleSQLException(se);
    }
    return rs;
  }
  
  //to check if the three tables are colocated
  @SuppressWarnings("unchecked")
	protected static boolean checkColocation() {
    if (testServerGroup) {
      boolean isCustPortoCol = false;
      boolean isPortoSoCol = false;
      boolean isCustSoCol = false;
      
      String custSG = (String) map.get("customersSG");
      String soSG = (String) map.get("sellordersSG");
      String portoSG = (String) map.get("portfolioSG");
      String schemaSG = (String) map.get(tradeSchemaSG);
      String[] custSGArr = custSG.split(",");
      String[] soSGArr = soSG.split(",");
      String[] portoSGArr = portoSG.split(",");
      String[] schemaSGArr = schemaSG.split(",");
      
      ArrayList<String> portoSGList = new ArrayList<String>();
      ArrayList<String> soSGList = new ArrayList<String>();
      ArrayList<String> custSGList = new ArrayList<String>();
      ArrayList<String> schemaSGList = new ArrayList<String>();
      
      for (int i=0; i<schemaSGArr.length; i++) 
        schemaSGList.add(schemaSGArr[i]); //get sever groups for schema
      
      if (portoSG.equals("default")) {
        portoSGList.remove("default");
        portoSGList.addAll(schemaSGList);  //portoSG has the same SG as schema, "default" could be added back indicating in all SG
      } else {
        for (int i=0; i<portoSGArr.length; i++) 
          portoSGList.add(portoSGArr[i]); //get sever groups for portfolio
      }

      if (soSG.equals("default")) {
        soSGList.remove("default");
        soSGList.addAll(schemaSGList);  //secSG has the same SG as schema
      } else { 
        for (int i=0; i<soSGArr.length; i++) 
        soSGList.add(soSGArr[i]); //get sever groups for so
      }
      
      if (custSG.equals("default")) {
        custSGList.remove("default");
        custSGList.addAll(schemaSGList);  //portoSG has the same SG as schema
      } else {
        for (int i=0; i<custSGArr.length; i++) 
          custSGList.add(custSGArr[i]); //get sever groups for customers
      }
      
      ArrayList<String> custParti = (ArrayList<String>) map.get("customersPartition");
      ArrayList<String> soParti = (ArrayList<String>) map.get("sellordersPartition");
      ArrayList<String> portoParti = (ArrayList<String>) map.get("portfolioPartition");
      
      if (custParti.size() > 0 && soParti.size()>0 && portoParti.size()>0) {
        if (custSG.equals(portoSG) && portoSG.equals(soSG)) {
          if (soParti.size()== 1 && soParti.get(0).equals("cid")
              && portoParti.size()== 1 && portoParti.get(0).equals("cid")
              && custParti.size()== 1 && custParti.get(0).equals("cid")
              && ((String)map.get("custPartitionOn")).equals("range")
              && ((String)map.get("soPartitionOn")).equals("range")
              && (((String)map.get("portfPartitionOn")).equals("deafult")
                  ||((String)map.get("portfPartitionOn")).equals("range"))) {
            isCustPortoCol = true;
            isCustSoCol = true;
            isPortoSoCol = true;     
          } //all partitioned on cid, test need to make sure in same partition strategy
        } //all three tables are in same server groups        
      } //all tables are partitioned, no replicate
      
      else if (custParti.size() == 0 && soParti.size()>0 && portoParti.size()>0) {
        if (custSGList.contains("default")) {
          isCustPortoCol = true;
          isCustSoCol = true;
        } //customers is replicated to all servers
        else {                 
          if (custSGList.contains(portoSGList)) 
            isCustPortoCol = true; //portfolio in the server groups that are a subset of customers'
          if (custSGList.contains(soSGList)) 
            isCustSoCol = true; //so in the server groups that are a subset of customers'
        } 
        //now check if so and Portfolio are colocated
        if (!soSG.equals(portoSG)) isPortoSoCol = false; //please note xxxSG are set string, otherwise need to parse to array also
        else {
          if (soParti.size()== 1 && soParti.get(0).equals("cid")
              && portoParti.size()== 1 && portoParti.get(0).equals("cid")
              && ((String)map.get("soPartitionOn")).equals("range")
              && ((String)map.get("portfPartitionOn")).equals("range")) 
            //in the PartitionClause.java, only possible colocation of these two on cid is for range
            //if this condition changed, need to change the code here accordingly
            isPortoSoCol = true;  
        } //so, portfolio in same server group
      } /*only customers table is replicate */ 
      
      else if (soParti.size()==0 && custParti.size() >0  && portoParti.size()>0) {
        if (custSGList.contains("default")) {
          isPortoSoCol = true;
          isCustSoCol = true;
        } //so is replicated to all servers
        else {                 
          if (soSGList.contains(portoSGList)) 
            isPortoSoCol = true; //portfolio in the server groups that are a subset of so
          if (soSGList.contains(custSGList)) 
            isCustSoCol = true;
        } 
        //check if customers and portfolio are colocated
        if (!custSG.equals(portoSG)) isCustPortoCol = false; //please note xxxSG are set string, otherwise need to parse the array
        else {
          if (custParti.size()== 1 && custParti.get(0).equals("cid")
              && portoParti.size()== 1 && portoParti.get(0).equals("cid")
              && (((String)map.get("portfPartitionOn")).equals("deafult")
                  ||(((String)map.get("custPartitionOn")).equals("range")
                    && ((String)map.get("portfPartitionOn")).equals("range")))) 
            //in the PartitionClause.java, possible colocations of these two on cid 
            //are range partitioning on both cusotmers and portfolio or portfolio on default partition
            //if this condition changed, need to change the code here accordingly
            isCustPortoCol = true;  //needs to be default colocation in the test 
        }        
      } /*only so table is replicate */ 
      
      else if (portoParti.size()==0 && soParti.size()>0 && custParti.size() >0 ){
        if (portoSGList.contains("default")) {
          isCustPortoCol = true;
          isPortoSoCol = true;
        } //portfolio is replicated to all servers 
        else {        
          if (portoSGList.contains(soSGList)) 
            isPortoSoCol = true; //so in the server groups that are a subset of portfolio               
          if (portoSGList.contains(custSGList)) 
            isCustPortoCol = true; //customers in the server groups that are a subset of portfolio
        } 
        //check if customers and portfolio are colocated
        if (custSG.equals(soSG)) {
          if (custParti.size()== 1 && custParti.get(0).equals("cid")
              && soParti.size()== 1 && soParti.get(0).equals("cid")
              && ((String)map.get("custPartitionOn")).equals("range")
              && ((String)map.get("soPartitionOn")).equals("range")) 
            //in the PartitionClause.java, only possible colocation of these two on cid is for range
            //if this condition changed, need to change the code here accordingly
          isCustSoCol = true; //please note xxxSG are set string, otherwise need to parse the array
        }
      }  /*only portfolio table is replicate, it will not be colocated */ 
      
      else if (portoParti.size()>0 && soParti.size()==0 && custParti.size() ==0 ){
        if ((soSGList.contains(portoSGList) || soSGList.contains("default"))
            && (custSGList.contains(portoSGList) || custSGList.contains("default"))) {
          isPortoSoCol = true;
          isCustSoCol = true;
          isCustPortoCol = true;
        } //customers and so are replicated to all server groups that portfolio is partitioned
      } //only portfolio is partitioned
      
      else if (portoParti.size()==0 && soParti.size()>0 && custParti.size() ==0 ){
        if ((portoSGList.contains(soSGList) || portoSGList.contains("default"))
            && (custSGList.contains(soSGList) || custSGList.contains("default"))) {
          isCustPortoCol = true;
          isCustSoCol = true;
          isPortoSoCol = true;
        } //customers and portfolio are replicated to all server groups that so is partitioned
      } //only sellorders table is partitioned
      
      else if (portoParti.size()==0 && soParti.size()==0 && custParti.size() >0 ){
        if ((portoSGList.contains(custSGList) || portoSGList.contains("default"))
            && (soSGList.contains(custSGList) || soSGList.contains("default"))) {
          isCustPortoCol = true;
          isCustSoCol = true;
          isPortoSoCol = true;
        } //so and portfolio are replicated to all server groups that customer table is partitioned
      } //only customers table is partitioned

      else {  
        if (!custSGList.contains("default") && !soSGList.contains("default") 
                  &&  !portoSGList.contains("default")) {  
          for (int i=0; i<portoSGArr.length; i++) {
            if (custSGList.contains(portoSGArr[i]) && soSGList.contains(portoSGArr[i])) {
              isCustPortoCol = true;
              isCustSoCol = true;
              isPortoSoCol = true;
              break;
            }
          }
        } //none has "default" case
        
        else if (custSGList.contains("default") && !soSGList.contains("default") 
            &&  !portoSGList.contains("default")) {
          for (int i=0; i<portoSGArr.length; i++) {
            if (soSGList.contains(portoSGArr[i])) {
              isCustPortoCol = true;
              isCustSoCol = true;
              isPortoSoCol = true;
              break;
            }
          }          
        } //only cust has default server groups
        else if (!custSGList.contains("default") && soSGList.contains("default") 
            &&  !portoSGList.contains("default")) {
          for (int i=0; i<portoSGArr.length; i++) {
            if (custSGList.contains(portoSGArr[i])) {
              isCustPortoCol = true;
              isCustSoCol = true;
              isPortoSoCol = true;
              break;
            }
          }                  
        } //only so has default server groups
        else if (!custSGList.contains("default") && !soSGList.contains("default") 
            &&  portoSGList.contains("default")) {
          for (int i=0; i<soSGArr.length; i++) {
            if (custSGList.contains(soSGArr[i])) {
              isCustPortoCol = true;
              isCustSoCol = true;
              isPortoSoCol = true;
              break;
            } //if a server group has both customer and sellorders replicated
          }                  
        } //only portofolio has default server group
        else {
          isCustPortoCol = true;
          isCustSoCol = true;
          isPortoSoCol = true;
        } //two all more tables replicate to default server group
      } //all three tables are replicated
           
      if (isCustPortoCol && isPortoSoCol && isCustSoCol) {
        Log.getLogWriter().info("Customers, portfolio and sellorders tables are colocated");
        return true; //if both are colocated
      }
      else {
        Log.getLogWriter().info("Customers, portfolio and sellorders tables are not colocated");
        return false;
      }
    }
   
    return true; //default to true, any test config does not involve server group need to make sure the tables are colocated.
  }

}
