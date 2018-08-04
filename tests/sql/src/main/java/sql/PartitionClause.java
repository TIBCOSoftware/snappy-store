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
package sql;

import hydra.Log;
import hydra.TestConfig;
import hydra.blackboard.SharedCounters;
import hydra.blackboard.SharedMap;

import java.util.ArrayList;
import java.util.HashMap;

import sql.hdfs.HDFSSqlTest;
import sql.sqlDAP.DAPHelper;
import sql.sqlDAP.SQLDAPTest;
import sql.sqlTx.SQLTxPartitionInfoBB;
import util.TestException;

/**
 * @author eshu
 *
 */
public class PartitionClause {
  //TODO partitionKeys are used to avoid hitting bug #39913, it is not needed after bug #39913 is fixed
	//however the some of this class, for example calculating numOfPRs are still needed for HA recovery testing
  protected static SharedMap partitionMap = SQLBB.getBB().getSharedMap(); //shared data in bb, 
  protected static boolean testServerGroups = TestConfig.tab().booleanAt(SQLPrms.testServerGroups, false);
  protected static boolean withReplicatedTables = TestConfig.tab().booleanAt(SQLPrms.withReplicatedTables, false);
  protected static boolean testServerGroupsInheritence = SQLTest.testServerGroupsInheritence;
  protected static SharedCounters counters = SQLBB.getBB().getSharedCounters();
  protected static boolean testMultiTableJoin = TestConfig.tab().booleanAt(SQLPrms.testMultiTableJoin, false);
  static final boolean  isConcUpdateTx = TestConfig.tab().booleanAt(SQLPrms.isConcUpdateTx, false);
  private static int sellordersClause = -1;
  private static SharedMap partitionInfoMap = SQLTxPartitionInfoBB.getBB().getSharedMap();
  static String tradeSchemaSG = SQLTest.tradeSchemaSG;
  static boolean isTicket46996Fixed = false;
  static boolean isTicket46993Fixed = false;
  static boolean isTicket46990Fixed = false;
  static boolean ticket47289resolved = true; //change this back to true once global hash index is not created when partitioned on subset of the unique key column
  static HashMap<String, String>  hdfsExtnParams = SQLTest.hasHdfs ? (HashMap<String, String>)SQLBB.getBB().getSharedMap().get(SQLPrms.HDFS_EXTN_PARAMS) : null; 
  /*
   * partition clauses for customers, need to update writeToBB method accordingly when there are changes to the clauses
   * first partition clause is default partition
   * last partition clause is "replicate"
   */
  protected static String[] customersPartitionClause = {
    " ",
    " partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) ",
    " partition by list (tid) (VALUES (0, 1, 3, 4, 5), VALUES (6, 10, 11), VALUES (12, 15, 17)) ",
    " partition by list (tid) (VALUES (14, 1, 2, 10, 4, 5), VALUES (6, 7, 16, 9, 3, 11), VALUES (12, 13, 0, 15, 8, 17)) ",
    " partition by range (since) (VALUES BETWEEN CAST('1998-01-01'  AS DATE) AND CAST('2000-08-11' AS DATE),  VALUES BETWEEN CAST('2003-09-01' AS DATE) AND  CAST('2007-12-31' AS DATE) ) ",
    " partition by (month(since)) ",
    " partition by column (cust_name) ",
    (SQLDAPTest.cidByRange) ? " partition by range (cid) " + DAPHelper.getCidRangePartition() + " BUCKETS " + (SQLTest.numOfStores + 1) + " ":
    " partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000) ",
    " replicate "  ,
  };

  protected static String[] customersPartitionClauseForSnappy = {
      " ",
      " partition_by 'tid' ",
      " partition_by 'since' ",
      " partition_by 'cust_name' ",
      " partition_by 'cid'",
      " " //replicate
  };

  //writes the partition key info to BB
  protected static void writeCustomersPartitionToBB(int whichClause) {
    ArrayList<String> partitionKey = new ArrayList<String>();
    int customersPRs = 2;

    if(SQLPrms.isSnappyMode()){
      switch(whichClause) {
        case 0:
          partitionKey.add("cid");
          partitionMap.put("custPartitionOn", "hash");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          --customersPRs;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.CUSTOMERS" + HDFSSqlTest.STORENAME) != null) {
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 1:
          partitionKey.add("tid");
          break;
        case 2:
          partitionKey.add("since");
          break;
        case 3:
            partitionKey.add("cust_name");
          break;
        case 4:
          partitionKey.add("cid");
          break;
        case 5:
          //replicated table, no partition key
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index and no PR
          customersPRs = 0;
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause);
      }
    } else {
      switch (whichClause) {
        case 0:
          partitionKey.add("cid");
          partitionMap.put("custPartitionOn", "hash");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          --customersPRs;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.CUSTOMERS" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 1:
            partitionKey.add("tid");
          break;
        case 2:
          if(SQLPrms.isSnappyMode())
            partitionKey.add("cust_name");
          else
            partitionKey.add("tid");
          break;
        case 3:
          if(SQLPrms.isSnappyMode()){

          }  else
            partitionKey.add("tid");
          break;
        case 4:
          partitionKey.add("since");
          break;
        case 5:
          partitionKey.add("since");
          break;
        case 6:
          partitionKey.add("cust_name");
          break;
        case 7:
          partitionKey.add("cid");
          partitionMap.put("custPartitionOn", "range");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          --customersPRs;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.CUSTOMERS" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 8:
          //replicated table, no partition key
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index and no PR
          customersPRs = 0;
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    }
    counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
    partitionMap.put("customersPartition", partitionKey); //put into BB 
            
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.CUSTOMERS" + HDFSSqlTest.STORENAME) != null){      
      counters.add(SQLBB.numOfPRs, 2); //PR and global index
      if (hdfsExtnParams.get("TRADE.CUSTOMERS" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        if (whichClause != 0 && whichClause !=7){
          counters.subtract(SQLBB.numOfPRs, 1); // no global index in no eviction criteria
        }
      }
    }
    
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
  
    if (SQLTest.hasCustomersDup) {
      //creates customersDup table with same partition as customers table
      counters.add(SQLBB.numOfPRs, customersPRs);
      Log.getLogWriter().info("Adding trade.customersdup's number of PRs: " + customersPRs);
    }
    
    SQLBB.getBB().getSharedMap().put(SQLTest.CUSTNUMOFPRS, customersPRs);
  
  }
  
  //writes the partition key info and server group info to BB
  protected static void writeCustomersPartitionSGToBB(int whichClause, String sg) {
    writeCustomersPartitionToBB(whichClause);
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
    
    partitionMap.put("customersSG", sg); //put into BB
  }
  
  //partition clauses for securities, need to update writeToBB method accordingly when there are changes to the clauses
  protected static String[] securitiesPartitionClause = {
    " ",
    " partition by range (sec_id) ( VALUES BETWEEN 0 AND 409, VALUES BETWEEN 409 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1477, VALUES BETWEEN 1477 AND 1700, VALUES BETWEEN 1700 AND 100000) ",
    " partition by list (tid) (VALUES (14, 1, 2, 10, 4, 5), VALUES (6, 7, 16, 9, 3, 11), VALUES (12, 13, 0, 15, 8, 17)) ",
    " partition by range (price) (VALUES BETWEEN 0.0 AND 25.0,  VALUES BETWEEN 25.0  AND 35.0 , VALUES BETWEEN 35.0  AND 49.0, VALUES BETWEEN 49.0  AND 69.0 , VALUES BETWEEN 69.0 AND 100.0) ",
    " partition by column (sec_id, price) ",
    " partition by range (symbol) (VALUES BETWEEN 'A' AND 'K', VALUES BETWEEN 'K' AND 'R', VALUES BETWEEN 'R' AND 'a', VALUES BETWEEN 'a' AND 'l', VALUES BETWEEN 'l' AND 'zzzzzzz')",
    " partition by list (exchange) (VALUES ('nasdaq'), VALUES ('nye', 'amex'), VALUES ('lse', 'fse', 'hkse', 'tse')) ",
    " replicate "  ,
  };

  protected static String[] securitiesPartitionClauseForSnappy = {
      " ",
      " partition_by 'sec_id,price' ",
      " "  , //replicate
  };

  protected static void writeSecuritiesPartitionToBB(int whichClause) {
    ArrayList<String>  partitionKey = new ArrayList<String> ();
    if(SQLPrms.isSnappyMode()){
      switch (whichClause) {
        case 0:
          partitionKey.add("sec_id");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.STORENAME) != null) {
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 1:
          partitionKey.add("price");
          partitionKey.add("sec_id");
          break;
        case 2:
        //replicated table
        counters.subtract(SQLBB.numOfPRs, 3); //no gloabl hash index for primary and unique key and no PR
        break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    } else {
      switch (whichClause) {
        case 0:
          partitionKey.add("sec_id");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.STORENAME) != null) {
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 1:
          partitionKey.add("sec_id");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.STORENAME) != null) {
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 2:
          partitionKey.add("tid");
          break;
        case 3:
          partitionKey.add("price");
          break;
        case 4:
          partitionKey.add("price");
          partitionKey.add("sec_id");
          break;
        case 5:
          partitionKey.add("symbol");
          //TODO, this handles that global hash index is created for hdfs table
          //need to cfm if this is necessary.
          if (ticket47289resolved)
            counters.decrement(SQLBB.numOfPRs); //no global hash index for unique keys -- partitioned by subset of the unique keys
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.STORENAME) != null) {
            counters.decrement(SQLBB.numOfPRs); // for #51443
            if (hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.EVICTION_CRITERIA) != null) {
              counters.increment(SQLBB.numOfPRs); // for #51443
              counters.increment(SQLBB.numOfPRs); // for #51443
            }
          }

          break;
        case 6:
          partitionKey.add("exchange");
          //TODO, this handles that global hash index is created for hdfs table
          //need to cfm if this is necessary.
          if (ticket47289resolved)
            counters.decrement(SQLBB.numOfPRs); //no global hash index for unique keys -- partitioned by subset of the unique keys
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.STORENAME) != null) {
            counters.decrement(SQLBB.numOfPRs); // for #51443
            if (hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.EVICTION_CRITERIA) != null) {
              counters.increment(SQLBB.numOfPRs); // for #51443
              counters.increment(SQLBB.numOfPRs); // for #51443
            }
          }
          break;
        case 7:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 3); //no gloabl hash index for primary and unique key and no PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    }
    counters.add(SQLBB.numOfPRs, 3); //PR and global hash index and unique key field
    partitionMap.put("securitiesPartition", partitionKey); //put into BB 
        
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.STORENAME) != null){      
      counters.add(SQLBB.numOfPRs, 3); //PR and global hash index
      
      if (hdfsExtnParams.get("TRADE.SECURITIES" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        if (whichClause == 0 || whichClause == 1 || whichClause == 5 || whichClause == 6){
          counters.subtract(SQLBB.numOfPRs, 1); //no hdfs global index if no eviction criteria
        }else{
          counters.subtract(SQLBB.numOfPRs, 2); //no hdfs global index if no eviction criteria
        }
      }
    }
    
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
  }
  
  //writes the partition key info and server group info to BB
  protected static void writeSecuritiesPartitionSGToBB(int whichClause, String sg) {
    writeSecuritiesPartitionToBB(whichClause);
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
      
    partitionMap.put("securitiesSG", sg); //put into BB
  }

  //partition clauses for networth, need to update writeToBB method accordingly when there are changes to the clauses
  protected static String[] networthPartitionClause = {
    " ", //default
    (SQLDAPTest.cidByRange) ? " partition by range (cid) " + DAPHelper.getCidRangePartition() + " BUCKETS " + (SQLTest.numOfStores + 1) + " ":
    " partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000) ",
    " partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) ",
    " partition by column (cash) ",
    " partition by range (securities)  ( VALUES BETWEEN 0.0 AND 9999.0, VALUES BETWEEN 12009.0 AND 31002.0, VALUES BETWEEN 31933.0 AND 51251.5, VALUES BETWEEN 51291.1 AND 71677.3, VALUES BETWEEN 71678.0 AND 100000.9) ",
    " partition by column(loanLimit, availloan)",
    " partition by (cash + securities + tid) ",
    " replicate ",
  };

  protected static String[] networthPartitionClauseForSnappy = {
      " ", //default
      " partition_by 'cash' ",
      " partition_by 'loanLimit,availloan' ",
      " ", //replicate
  };

  protected static void writeNetworthPartitionToBB(int whichClause) {
    ArrayList<String>  partitionKey = new ArrayList<String> ();
    int netwPRs = 2;
    if(SQLPrms.isSnappyMode()){
      switch (whichClause) {
        case 0:
          partitionKey.add("cid");
          partitionMap.put("netPartitionOn", "default");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          netwPRs--;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.NETWORTH" + HDFSSqlTest.STORENAME) != null) {
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 1:
          partitionKey.add("cash");
          break;
        case 2:
          partitionKey.add("loanLimit");
          partitionKey.add("availloan");
          break;
        case 3:
          //replicate
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index and PR
          netwPRs = 0;
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    } else {
      switch (whichClause) {
        case 0:
          partitionKey.add("cid");
          partitionMap.put("netPartitionOn", "default");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          netwPRs--;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.NETWORTH" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 1:
          partitionKey.add("cid");
          partitionMap.put("netPartitionOn", "range");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          netwPRs--;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.NETWORTH" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 2:
          partitionKey.add("tid");
          break;
        case 3:
          partitionKey.add("cash");
          break;
        case 4:
          partitionKey.add("securities");
          break;
        case 5:
          partitionKey.add("loanLimit");
          partitionKey.add("availloan");
          break;
        case 6:
          partitionKey.add("cash");
          partitionKey.add("securities");
          partitionKey.add("tid");
          break;
        case 7:
          //replicate
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index and PR
          netwPRs = 0;
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    }

    counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
    partitionMap.put("networthPartition", partitionKey); //put into BB 
    
         
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.NETWORTH" + HDFSSqlTest.STORENAME) != null){      
      counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
      if (hdfsExtnParams.get("TRADE.NETWORTH" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        if (whichClause != 0 && whichClause != 1){
          counters.subtract(SQLBB.numOfPRs, 1); //no hdfs pr for index if no eviction criteria 
        }
      }
    }
    
    SQLBB.getBB().getSharedMap().put(SQLTest.NETWORTHNUMOFPRS, netwPRs);
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
  }
  
  //writes the partition key info and server group info to BB
  protected static void writeNetworthPartitionSGToBB(int whichClause, String sg) {
    writeNetworthPartitionToBB(whichClause);
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
      
    partitionMap.put("networthSG", sg); //put into BB
  }
  
  //partition clauses for portfolio, need to update writeToBB method accordingly when there are changes to the clauses
  protected static String[] portfolioPartitionClause = {
    " ",
    (SQLDAPTest.cidByRange) ? " partition by range (cid) " + DAPHelper.getCidRangePartition() + " BUCKETS " + (SQLTest.numOfStores + 1) + " ":
    " partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000) ",
    " partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) ",
    " partition by column (qty) ",
    " partition by column (qty, availQty) ",
    " partition by range (subTotal)  ( VALUES BETWEEN 0.0 AND 9999.0, VALUES BETWEEN 12009.0 AND 31002.0, VALUES BETWEEN 31933.0 AND 51251.5, VALUES BETWEEN 51291.1 AND 71677.3, VALUES BETWEEN 71678.0 AND 100000.9) ",
    " partition by range (sid) ( VALUES BETWEEN 0 AND 409, VALUES BETWEEN 409 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1477, VALUES BETWEEN 1477 AND 1700, VALUES BETWEEN 1700 AND 100000) ",
    " replicate "  ,
  };

  protected static String[] portfolioPartitionClauseForSnappy = {
      " ",
      " partition_by 'qty' ",
      " partition_by 'qty, availQty' ",
      " "  , //replicate
  };

  @SuppressWarnings("unchecked")
  protected static void writePortfolioPartitionToBB(int whichClause) {
    ArrayList<String>  partitionKey = new ArrayList<String> ();
    int portfolioPRs = 2;
    if(SQLPrms.isSnappyMode()){
      switch (whichClause) {
        case 0:
          ArrayList<String>  custPartition = (ArrayList<String>) partitionMap.get("customersPartition");
          ArrayList<String>  secPartition = (ArrayList<String> ) partitionMap.get("securitiesPartition");
          if (custPartition.contains("cid")&& custPartition.size()==1) partitionKey.add("cid"); //cid is the first foreign key
          else if (secPartition.contains("sec_id") && secPartition.size()==1) partitionKey.add("sid");
          else {
            partitionKey.add("cid");
            partitionKey.add("sid");
          }
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          --portfolioPRs;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.PORTFOLIO" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 1:
            partitionKey.add("qty");
          break;
        case 2:
          partitionKey.add("qty");
          partitionKey.add("availQty");
          break;
        case 3:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index & PR
          portfolioPRs = 0;
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    } else {
      switch (whichClause) {
        case 0:
          ArrayList<String>  custPartition = (ArrayList<String>) partitionMap.get("customersPartition");
          ArrayList<String>  secPartition = (ArrayList<String> ) partitionMap.get("securitiesPartition");
          if (custPartition.contains("cid")&& custPartition.size()==1) partitionKey.add("cid"); //cid is the first foreign key
          else if (secPartition.contains("sec_id") && secPartition.size()==1) partitionKey.add("sid");
          else {
            partitionKey.add("cid");
            partitionKey.add("sid");
          }
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          --portfolioPRs;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.PORTFOLIO" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 1:
          partitionKey.add("cid");
          partitionMap.put("portfPartitionOn", "range");
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          --portfolioPRs;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.PORTFOLIO" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 2:
          partitionKey.add("tid");
          break;
        case 3:
          partitionKey.add("qty");
          break;
        case 4:
          partitionKey.add("qty");
          partitionKey.add("availQty");
          break;
        case 5:
          partitionKey.add("subTotal");
          break;
        case 6:
          partitionKey.add("sid");
          partitionMap.put("portfPartitionOn", "wrongRange"); //not colocated as the range are incompatiable
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          --portfolioPRs;
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.PORTFOLIO" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 7:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index & PR
          portfolioPRs = 0;
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    }

    counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
    partitionMap.put("portfolioPartition", partitionKey); //put into BB 
            
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.PORTFOLIO" + HDFSSqlTest.STORENAME) != null){      
      counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
      
      if (hdfsExtnParams.get("TRADE.PORTFOLIO" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        if (whichClause != 0 && whichClause != 1 && whichClause != 6 && whichClause != 7){
          counters.subtract(SQLBB.numOfPRs, 1); //no hdfs PR for global hash index if no eviction criteria
        }
      }
    }
    
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
    boolean reproduce50116 = TestConfig.tab().booleanAt(SQLPrms.toReproduce50116, true);
    if (SQLTest.hasPortfolioV1 && !reproduce50116) {
      counters.add(SQLBB.numOfPRs, portfolioPRs);
      Log.getLogWriter().info("add num of PR for portfoliov1 table: " + portfolioPRs
           + " as original portfoilio is not dropped to work around #50116");
    }
  
  }
  
  //writes the partition key info and server group info to BB
  @SuppressWarnings("unchecked")
  protected static void writePortfolioPartitionSGToBB(int whichClause, String sg) {
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
    
    if (whichClause !=0)       writePortfolioPartitionToBB(whichClause); //only default partitioning need server group clause
    else /* for defalut partitioning */{
      ArrayList<String>  partitionKey = new ArrayList<String> ();
      ArrayList<String>  custPartition = (ArrayList<String> ) partitionMap.get("customersPartition");
      ArrayList<String>  secPartition = (ArrayList<String> ) partitionMap.get("securitiesPartition");
      String custSG = (String)partitionMap.get("customersSG");
      String secSG = (String)partitionMap.get("securitiesSG");
      if (custPartition.contains("cid")&& custPartition.size()==1 && sg.equals(custSG)) 
        partitionKey.add("cid"); //cid is the first foreign key
      else if (secPartition.contains("sec_id") && secPartition.size()==1 && sg.equals(secSG)) 
        partitionKey.add("sid");
      else {
        partitionKey.add("cid");
        partitionKey.add("sid");
      } //default to primary key
      counters.increment(SQLBB.numOfPRs); //no gloabl hash index, only PR
      partitionMap.put("portfolioPartition", partitionKey); //put partition key into BB 
      partitionMap.put("portfPartitionOn", "default");
      Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs));
    }    
    partitionMap.put("portfolioSG", sg); //put SG into BB
  }

  //partition clauses for sellorders, need to update writeToBB method accordingly when there are changes to the clauses
  protected static String[] sellordersPartitionClause = {
    " ",
    (SQLDAPTest.cidByRange) ? " partition by range (cid) " + DAPHelper.getCidRangePartition() + " BUCKETS " + (SQLTest.numOfStores + 1) + " ":
    " partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000) ",
    " partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) ",
    " partition by column (qty) ",
    " partition by range (ask) (VALUES BETWEEN 0.0 AND 25.0, VALUES BETWEEN 25.0  AND 35.0 , VALUES BETWEEN 35.0  AND 49.0, VALUES BETWEEN 49.0  AND 69.0 , VALUES BETWEEN 69.0 AND 100.0) ",
    " partition by column (ask, status) ",
    " partition by column (order_time, status) ",
    " partition by column (sid, order_time, ask) ",
    " replicate ", 
  };

  protected static String[] sellordersPartitionClauseForSnappy = {
      " ",
      " partition_by 'qty' ",
      " partition_by 'ask, status' ",
      " partition_by 'order_time, status' ",
      " partition_by 'sid, order_time, ask' ",
      " ", //replicate
  };

  @SuppressWarnings("unchecked")
  protected static void writeSellordersPartitionToBB(int whichClause) {
    ArrayList<String>  partitionKey = new ArrayList<String> ();
    if(SQLPrms.isSnappyMode()){
      switch (whichClause) {
        case 0:
          ArrayList<String>  portfolioPartition = (ArrayList<String>) partitionMap.get("portfolioPartition");
          if (portfolioPartition.contains("cid")&& portfolioPartition.contains("sid")
              && portfolioPartition.size()==2) {
            // if portofio partitioned on primary keys, sellorders partition on foreign key
            partitionKey.add("cid");
            partitionKey.add("sid");
          } else if (testMultiTableJoin){
            partitionKey.add("cid"); //cid is colcated with customers
          } else {
            partitionKey.add("oid");
            counters.decrement(SQLBB.numOfPRs);  //should not create global hash index as the counter is incremented later
            if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SELLORDERS" + HDFSSqlTest.STORENAME) != null){
              counters.decrement(SQLBB.numOfPRs); //PR and global hash index
            }
          }
          break;
        case 1:
          partitionKey.add("qty");
          break;
        case 2:
          partitionKey.add("ask");
          partitionKey.add("status");
          break;
        case 3:
          partitionKey.add("order_time");
          partitionKey.add("status");
          break;
        case 4:
          partitionKey.add("sid");
          partitionKey.add("order_time");
          partitionKey.add("ask");
          break;
        case 5:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index and PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    } else {
      switch (whichClause) {
        case 0:
          ArrayList<String>  portfolioPartition = (ArrayList<String>) partitionMap.get("portfolioPartition");
          if (portfolioPartition.contains("cid")&& portfolioPartition.contains("sid")
              && portfolioPartition.size()==2) {
            // if portofio partitioned on primary keys, sellorders partition on foreign key
            partitionKey.add("cid");
            partitionKey.add("sid");
          } else if (testMultiTableJoin){
            partitionKey.add("cid"); //cid is colcated with customers
          } else {
            partitionKey.add("oid");
            counters.decrement(SQLBB.numOfPRs);  //should not create global hash index as the counter is incremented later
            if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SELLORDERS" + HDFSSqlTest.STORENAME) != null){
              counters.decrement(SQLBB.numOfPRs); //PR and global hash index
            }
          }
          break;
        case 1:
          partitionKey.add("cid");
          partitionMap.put("soPartitionOn", "range"); //needed when server groups/random join query are involved
          break;
        case 2:
          partitionKey.add("tid");
          break;
        case 3:
          partitionKey.add("qty");
          break;
        case 4:
          partitionKey.add("ask");
          break;
        case 5:
          partitionKey.add("ask");
          partitionKey.add("status");
          break;
        case 6:
          partitionKey.add("order_time");
          partitionKey.add("status");
          break;
        case 7:
          partitionKey.add("sid");
          partitionKey.add("order_time");
          partitionKey.add("ask");
          break;
        case 8:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index and PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    }
    counters.add(SQLBB.numOfPRs, 2); //PR and global hash index    	
    partitionMap.put("sellordersPartition", partitionKey); //put into BB 
            
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SELLORDERS" + HDFSSqlTest.STORENAME) != null){      
      counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
      if (hdfsExtnParams.get("TRADE.SELLORDERS" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        if (whichClause != 0){
          counters.subtract(SQLBB.numOfPRs, 1); //no hdfs PR for global hash index if no eviction criteria
        }
      }
    }
    
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
  }

  //writes the partition key info based on server groups to BB
  @SuppressWarnings("unchecked")
  protected static void writeSellordersPartitionSGToBB(int whichClause, String sg) {
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
    
    if (whichClause !=0)  writeSellordersPartitionToBB(whichClause); //only default partitioning need server group clause
    else /* for defalut partitioning */{
      ArrayList<String>  partitionKey = new ArrayList<String> ();     
      ArrayList<String>  portfolioPartition = (ArrayList<String> ) partitionMap.get("portfolioPartition");
      String portfolioSG = (String)partitionMap.get("portfolioSG");
      if (portfolioPartition.contains("cid")&& portfolioPartition.contains("sid")
          && portfolioPartition.size()==2 && sg.equals(portfolioSG)) {
        // if portfolio partitioned on primary keys and two tables in same group, sellorders partition on foreign key
            partitionKey.add("cid");
            partitionKey.add("sid"); 
      } else if (testMultiTableJoin){
        partitionKey.add("cid"); //cid is colcated with customers
      } else {
        partitionKey.add("oid"); //partition on primary key
        counters.decrement(SQLBB.numOfPRs); //no gloabl hash index, has PR only
      }
      counters.add(SQLBB.numOfPRs, 2); //PR and global hash inde      
      partitionMap.put("sellordersPartition", partitionKey); //put partition key into BB 
      partitionMap.put("soPartitionOn", "default");
      Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs));
    }    
    partitionMap.put("sellordersSG", sg); //put SG into BB, needed for colocation on join tests 
  }
  
  //partition clauses for buyorders, need to update writeToBB method accordingly when there are changes to the clauses
  @SuppressWarnings("unchecked")
  protected static String[] buyordersPartitionClause = {
    " ",
    " partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000) ",
    (SQLDAPTest.tidByList)?  " partition by list (tid) " + DAPHelper.getTidListPartition() + " BUCKETS " + ((ArrayList<Integer>[])SQLBB.getBB().getSharedMap().get("tidListArray")).length + " ":
    " partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) ",
    " partition by range (sid) ( VALUES BETWEEN 0 AND 409, VALUES BETWEEN 409 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1477, VALUES BETWEEN 1477 AND 1700, VALUES BETWEEN 1700 AND 100000) ",
    " partition by range (bid) (VALUES BETWEEN 0.0 AND 25.0,  VALUES BETWEEN 25.0  AND 35.0 , VALUES BETWEEN 35.0  AND 49.0, VALUES BETWEEN 49.0  AND 69.0 , VALUES BETWEEN 69.0 AND 100.0) ",
    " partition by column (bid, ordertime) ",
    " partition by column (ordertime, status) ",
    " replicate "  ,  
  };

  protected static String[] buyordersPartitionClauseForSnappy = {
      " ",
      " partition_by 'bid,ordertime' ",
      " partition_by 'ordertime,status' ",
      " "  , //replicate
  };
  @SuppressWarnings("unchecked")
  protected static void writeBuyordersPartitionToBB(int whichClause) {
    ArrayList<String>  partitionKey = new ArrayList<String> ();
    if(SQLPrms.isSnappyMode()){
      switch (whichClause) {
        case 0:
          ArrayList<String>  custPartition = (ArrayList<String> ) partitionMap.get("customersPartition");
          ArrayList<String>  secPartition = (ArrayList<String> ) partitionMap.get("securitiesPartition");
          if (custPartition.contains("cid")&& custPartition.size()==1) partitionKey.add("cid"); //cid is the first foreign key
          else if (testMultiTableJoin){
            partitionKey.add("cid"); //cid is colcated with customers
          } else if (secPartition.contains("sec_id") && secPartition.size()==1) partitionKey.add("sid");
          else {
            partitionKey.add("oid");
            counters.decrement(SQLBB.numOfPRs);  //should not create global hash index as the counter is incremented later
            if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.BUYORDERS" + HDFSSqlTest.STORENAME) != null){
              counters.decrement(SQLBB.numOfPRs); //PR and global hash index
            }
          }
          break;
        case 1:
          partitionKey.add("bid");
          partitionKey.add("ordertime");
          break;
        case 2:
          partitionKey.add("ordertime");
          partitionKey.add("status");
          break;
        case 3:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index & PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;

      }
    } else {
      switch (whichClause) {
        case 0:
          ArrayList<String>  custPartition = (ArrayList<String> ) partitionMap.get("customersPartition");
          ArrayList<String>  secPartition = (ArrayList<String> ) partitionMap.get("securitiesPartition");
          if (custPartition.contains("cid")&& custPartition.size()==1) partitionKey.add("cid"); //cid is the first foreign key
          else if (testMultiTableJoin){
            partitionKey.add("cid"); //cid is colcated with customers
          } else if (secPartition.contains("sec_id") && secPartition.size()==1) partitionKey.add("sid");
          else {
            partitionKey.add("oid");
            counters.decrement(SQLBB.numOfPRs);  //should not create global hash index as the counter is incremented later
            if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.BUYORDERS" + HDFSSqlTest.STORENAME) != null){
              counters.decrement(SQLBB.numOfPRs); //PR and global hash index
            }
          }
          break;
        case 1:
          partitionKey.add("cid");
          break;
        case 2:
          partitionKey.add("tid");
          break;
        case 3:
          partitionKey.add("sid");
          break;
        case 4:
          partitionKey.add("bid");
          break;
        case 5:
          partitionKey.add("bid");
          partitionKey.add("ordertime");
          break;
        case 6:
          partitionKey.add("ordertime");
          partitionKey.add("status");
          break;
        case 7:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index & PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;

      }
    }
    counters.add(SQLBB.numOfPRs, 2); //PR and global hash index    	
    partitionMap.put("buyordersPartition", partitionKey); //put into BB 
    
    
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.BUYORDERS" + HDFSSqlTest.STORENAME) != null){      
      counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
      if (hdfsExtnParams.get("TRADE.BUYORDERS" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        if (whichClause != 0){
          counters.subtract(SQLBB.numOfPRs, 1); //no hdfs PR for global hash index if no eviction criteria
        }
      }
    }
        
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
  }
  
  //writes the partition key info and server group info to BB
  @SuppressWarnings("unchecked")
  protected static void writeBuyordersPartitionSGToBB(int whichClause, String sg) {
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
    
    if (whichClause !=0)       writeBuyordersPartitionToBB(whichClause); //only default partitioning need server group clause
    else /* for defalut partitioning */{
      ArrayList<String>  partitionKey = new ArrayList<String> ();
      ArrayList<String>  custPartition = (ArrayList<String> ) partitionMap.get("customersPartition");
      ArrayList<String>  secPartition = (ArrayList<String> ) partitionMap.get("securitiesPartition");
      String custSG = (String)partitionMap.get("customersSG");
      String secSG = (String)partitionMap.get("securitiesSG");
      /* need to be in the same server groups to be able to 
       * partition on its first foreign key
       */
      if (custPartition.contains("cid")&& custPartition.size()==1 && sg.equals(custSG)) 
        partitionKey.add("cid"); //cid is the first foreign key
      else if (testMultiTableJoin){
        partitionKey.add("cid"); //cid is colcated with customers
      } else if (secPartition.contains("sec_id") && secPartition.size()==1 && sg.equals(secSG)) 
        partitionKey.add("sid");
      else {
        partitionKey.add("oid");
        counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
      } //default to primary key   
      counters.add(SQLBB.numOfPRs, 2); //PR and global hash index 
      partitionMap.put("buyordersPartition", partitionKey); //put partition key into BB 
      Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs));
    }    
    partitionMap.put("buyordersSG", sg); //put SG into BB, needed for colocation on join tests
  }

  //partition clauses for txhistory, need to update writeToBB method accordingly when there are changes to the clauses
  @SuppressWarnings("unchecked")
  protected static String[] txhistoryPartitionClause = {
    " ",
    " partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102, VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800, VALUES BETWEEN 1800 AND 100000) ",
    (SQLDAPTest.tidByList)?  " partition by list (tid) " + DAPHelper.getTidListPartition() + " BUCKETS " + ((ArrayList<Integer>[])SQLBB.getBB().getSharedMap().get("tidListArray")).length + " ":
      " partition by list (tid) (VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17)) ",
    " replicate "  ,
  };

  protected static String[] txhistoryPartitionClauseForSnappy = {
      " ",
      " "  , //replicate
  };

  protected static void writeTxhistoryPartitionToBB(int whichClause) {
    ArrayList<String>  partitionKey = new ArrayList<String> ();
    if(SQLPrms.isSnappyMode()){
      switch (whichClause) {
        case 0:
          if (testMultiTableJoin) {
            //add the partition on cid colocate with customer for join query
            partitionKey.add("cid");
            break;
          }
          //for others, no primary key or foreign key or unique key field in txhistory table
          break;
        case 1:
          //replicated table
          counters.decrement(SQLBB.numOfPRs); //no PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    } else {
      switch (whichClause) {
        case 0:
          if (testMultiTableJoin) {
            //add the partition on cid colocate with customer for join query
            partitionKey.add("cid");
            break;
          }
          //for others, no primary key or foreign key or unique key field in txhistory table
          break;
        case 1:
          partitionKey.add("cid");
          break;
        case 2:
          partitionKey.add("tid");
          break;
        case 3:
          //replicated table
          counters.decrement(SQLBB.numOfPRs); //no PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    }
    counters.increment(SQLBB.numOfPRs); //PR only 	
    partitionMap.put("txhistoryPartition", partitionKey); //put into BB 
            
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.TXHISTORY" + HDFSSqlTest.STORENAME) != null){      
      counters.increment(SQLBB.numOfPRs); //PR and global hash index
      if (SQLTest.testMultipleUniqueIndex) {
        counters.add(SQLBB.numOfPRs, 4); // 2 unique keys + 2 hdfs regions
        if (hdfsExtnParams.get("TRADE.TXHISTORY" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        // no hdfs pr for both global indexes
          if (whichClause == 0 || whichClause == 1){
            counters.subtract(SQLBB.numOfPRs, 3); 
          } else{
            counters.subtract(SQLBB.numOfPRs, 2);  
          }
        }
      }
    }
    
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
  }
  
  //writes the partition key info and server group info to BB
  protected static void writeTxhistoryPartitionSGToBB(int whichClause, String sg) {
    writeTxhistoryPartitionToBB(whichClause);
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
    
    partitionMap.put("txhistorySG", sg); //put into BB
  }
  /*
   * trade.companies table fields
   *   String symbol;
   *   String exchange;
   *   short companytype
   *   byte uid CHAR FOR BIT DATA
   *   UUID uuid (UUID)
   *   String companyname char (100)
   *   Clob companyinfo
   *   String note (LONG VARCHAR)
   *   UDTPrice histPrice (UDT price)
   *   long asset (bigint)
   *   byte logo VARCHAR FOR BIT DATA
   *   int tid; //for update or delete unique records to the thread
   */
  protected static String[] companiesPartitionClause = {
    " ",
    " partition by column (uid) ",
    (SQLTest.random.nextBoolean()?  " partition by list (companytype) (VALUES (0, 1), VALUES (2), VALUES (3), VALUES (5, 7), VALUES (6), VALUES (8), VALUES (4), VALUES (9)) "
        : " partition by list (companytype) (VALUES (0, 1), VALUES (2), VALUES (3), VALUES (5, 7), VALUES (6), VALUES (8), VALUES (4)) "),
    (isTicket46996Fixed ? " partition by range (trade.getLowPrice(histprice)) (VALUES BETWEEN 0.0 AND 25.0,  VALUES BETWEEN 25.0  AND 35.0 , VALUES BETWEEN 35.0  AND 49.0, VALUES BETWEEN 49.0  AND 69.0 , VALUES BETWEEN 69.0 AND 100.0) " :
      " partition by column(histprice)" ),
    " partition by column (companyname) ",
    " partition by range (symbol) (VALUES BETWEEN 'a' AND 'd', VALUES BETWEEN 'd' AND 'i', VALUES BETWEEN 'i' AND 'k', VALUES BETWEEN 'k' AND 'o', VALUES BETWEEN 'o' AND 'r', VALUES BETWEEN 'r' AND 'u', VALUES BETWEEN 'u' AND'zzzzzzz')",
    (SQLTest.random.nextBoolean() ? " partition by list (exchange) (VALUES ('nasdaq'), VALUES ('nye', 'amex'), VALUES ('lse'),  VALUES ('fse'), VALUES ('hkse'), VALUES ('tse')) " 
         : " partition by list (exchange) (VALUES ('nasdaq'), VALUES ('nye'), VALUES ('lse'),  VALUES ('fse'), VALUES ('hkse'), VALUES ('tse')) "),
    (isTicket46993Fixed ? " partition by (trade.getHighPrice(histprice))" :
         " partition by column(histprice)" ),
    " partition by column(companyinfo)" ,
    (isTicket46990Fixed ? " partition by range (note) (VALUES BETWEEN '!' AND ')', VALUES BETWEEN '(' AND '0', VALUES BETWEEN '0' AND '=', VALUES BETWEEN '=' AND 'A', VALUES BETWEEN 'A' AND 'K', VALUES BETWEEN 'K' AND 'Z', VALUES BETWEEN 'Z' AND 'c', VALUES BETWEEN 'c' AND 'r', VALUES BETWEEN 'r' AND 'z', VALUES BETWEEN 'z' AND '~')" :
      " partition by column (note) "),
    " partition by column(asset, companytype)" ,
    " partition by column(logo)" ,
    " partition by column(uuid)" ,
    " replicate "  ,
  };

  protected static String[] companiesPartitionClauseForSnappy = {
      " ",
      " partition_by 'uid' ",
      " partition_by 'histprice'",
      " partition_by 'companyname' ",
      " partition_by 'companyinfo'" ,
      " partition_by 'note' ",
      " partition_by 'asset, companytype'" ,
      " partition_by 'logo'" ,
      " partition_by 'uuid'" ,
      " ", //replicate
  };


  protected static void writeCompaniesPartitionToBB(int whichClause) {
    ArrayList<String>  partitionKey = new ArrayList<String> ();
    if(SQLPrms.isSnappyMode()) {
      switch (whichClause) {
        case 0:
          partitionKey.add("symbol");
          partitionKey.add("exchange");
          //TODO check if securities table is replicate table, if so, global hask key is not there
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.COMPANIES" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 1:
          partitionKey.add("_uid");
          break;
        case 2:
          partitionKey.add("histprice");
          break;
        case 3:
          partitionKey.add("companyname");
          break;
        case 4:
          partitionKey.add("companyinfo");
          break;
        case 5:
          partitionKey.add("note");
          break;
        case 6:
          partitionKey.add("asset");
          partitionKey.add("companytype");
          break;
        case 7:
          partitionKey.add("logo");
          break;
        case 8:
          partitionKey.add("uuid");
          break;
        case 9:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index for primary and no PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    } else {
      switch (whichClause) {
        case 0:
          partitionKey.add("symbol");
          partitionKey.add("exchange");
          //TODO check if securities table is replicate table, if so, global hask key is not there
          counters.decrement(SQLBB.numOfPRs); //no gloabl hash index
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.COMPANIES" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs);
          }
          break;
        case 1:
          partitionKey.add("_uid");
          break;
        case 2:
          partitionKey.add("companytype");
          break;
        case 3:
          partitionKey.add("histprice");
          break;
        case 4:
          partitionKey.add("companyname");
          break;
        case 5:
          partitionKey.add("symbol");
          //TODO check if securities table is replicate table,
          counters.decrement(SQLBB.numOfPRs); //no global hash index for unique keys -- partitioned by subset of the unique keys
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.COMPANIES" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 6:
          //TODO check if securities table is replicate table,
          partitionKey.add("exchange");
          counters.decrement(SQLBB.numOfPRs); //no global hash index for unique keys -- partitioned by subset of the unique keys
          if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.COMPANIES" + HDFSSqlTest.STORENAME) != null){
            counters.decrement(SQLBB.numOfPRs); //PR and global hash index
          }
          break;
        case 7:
          partitionKey.add("histprice");
          break;
        case 8:
          partitionKey.add("companyinfo");
          break;
        case 9:
          partitionKey.add("note");
          break;
        case 10:
          partitionKey.add("asset");
          partitionKey.add("companytype");
          break;
        case 11:
          partitionKey.add("logo");
          break;
        case 12:
          partitionKey.add("uuid");
          break;
        case 13:
          //replicated table
          counters.subtract(SQLBB.numOfPRs, 2); //no gloabl hash index for primary and no PR
          break;
        default:
          throw new TestException("Unknown partitionKey " + whichClause)
              ;
      }
    }
    counters.add(SQLBB.numOfPRs, 2); //PR and global hash index 
    partitionMap.put("companiesPartition", partitionKey); //put into BB 
    
        
    if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.COMPANIES" + HDFSSqlTest.STORENAME) != null){      
      counters.add(SQLBB.numOfPRs, 2); //PR and global hash index
      if (hdfsExtnParams.get("TRADE.COMPANIES" + HDFSSqlTest.EVICTION_CRITERIA) == null){
        if (whichClause != 0 && whichClause != 5 && whichClause !=6){
          counters.subtract(SQLBB.numOfPRs, 1); //no hdfs PR for global hash index if no eviction criteria
        }
      }
    }
    
    
    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS ignoring colocation" : " "));
  }
  
  //writes the partition key info and server group info to BB
  protected static void writeCompaniesPartitionSGToBB(int whichClause, String sg) {
    writeCompaniesPartitionToBB(whichClause);
    if (testServerGroupsInheritence && sg.equals("default")) {
      sg = (String) partitionMap.get(tradeSchemaSG);
    }  //portfolio in "default" server group and inheret from tradeSchema
      
    partitionMap.put("companiesSG", sg); //put into BB
  }

  //get the partition key and write it to bb for not updating partition key in preview
  @SuppressWarnings("unchecked")
  public static String getPartitionClause(String tableInfo){
    String partitionClause = null;
    if(SQLPrms.isSnappyMode())
      tableInfo = tableInfo.substring(tableInfo.indexOf("(") + 1, tableInfo.indexOf(")"));
    String[] strArray = tableInfo.split(":");
    String tableName = strArray[0];
    String partition = strArray[1]; //parse in the partition key
    String serverGroup = null;
    if (testServerGroups && strArray.length > 2) {
      serverGroup = processServerGroup(strArray[2]); //get the correct
      Log.getLogWriter().info("server group is " + serverGroup);
    }
    
    int whichClause;
    if (tableName.equalsIgnoreCase("trade.customers")) {
      if (partition.equals("random")) {
        if (withReplicatedTables) {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(customersPartitionClauseForSnappy.length);
          else
            whichClause = SQLTest.random.nextInt(customersPartitionClause.length);
        }
        else {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(customersPartitionClauseForSnappy.length-1);
          else
            whichClause = SQLTest.random.nextInt(customersPartitionClause.length-1); //last one is for replicated table
        }
      
        if (isConcUpdateTx && whichClause == 6)
          whichClause = 5;   //to avoid update on a partitioned field 
      } 
      else if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          whichClause = customersPartitionClauseForSnappy.length -1;
        else
          whichClause = customersPartitionClause.length-1; //last clause is "replicate"
      }
      else {
        whichClause = Integer.parseInt(partition);
      }
      boolean isReplicated = SQLPrms.isSnappyMode()?whichClause == customersPartitionClauseForSnappy
          .length-1:whichClause == customersPartitionClause.length -1;
      if (isReplicated)
        partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");

      String clause = (SQLPrms.isSnappyMode()) ? customersPartitionClauseForSnappy[whichClause]:
          customersPartitionClause[whichClause];
      partitionClause = getPartitionSG(clause, serverGroup); //which partition clause and server groups
      Log.getLogWriter().info("whichClause is " + whichClause);
      if (!testServerGroups) writeCustomersPartitionToBB(whichClause);  
      else writeCustomersPartitionSGToBB(whichClause, serverGroup); //to process default partitioning
      Log.getLogWriter().info("getPartitionClause customers: whichClause is " + whichClause
            + " and partition is " + partitionClause);
    } 
    else if (tableName.equalsIgnoreCase("trade.securities")){
      if (partition.equals("random")) {
        if (withReplicatedTables){
          if(!SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(securitiesPartitionClause.length);
          else
            whichClause = SQLTest.random.nextInt(securitiesPartitionClauseForSnappy.length);
        }
        else{
          if(!SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(securitiesPartitionClause.length-1);
          else
            whichClause = SQLTest.random.nextInt(securitiesPartitionClauseForSnappy.length-1);
        }
      } 
      else if (partition.equals("replicate")){
        if(!SQLPrms.isSnappyMode())
          whichClause = securitiesPartitionClause.length-1; //last clause is "replicate"
        else
          whichClause = securitiesPartitionClauseForSnappy.length-1;
      }
      else {
        whichClause = Integer.parseInt(partition);
      }   
      boolean isReplicated = SQLPrms.isSnappyMode()?whichClause == securitiesPartitionClauseForSnappy
          .length-1:whichClause == securitiesPartitionClause.length-1;

      if (isReplicated) partitionInfoMap.put(tableName,"replicate");
      else partitionInfoMap.put(tableName, "any");
      String clause = (SQLPrms.isSnappyMode()) ? securitiesPartitionClauseForSnappy[whichClause]:
        securitiesPartitionClause[whichClause];
      partitionClause = getPartitionSG(clause, serverGroup); //with the SG clause
      if (!testServerGroups) writeSecuritiesPartitionToBB(whichClause);
      else writeSecuritiesPartitionSGToBB(whichClause, serverGroup);
      Log.getLogWriter().info("getPartitionClause securities: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    } 
    else if (tableName.equalsIgnoreCase("trade.portfolio")){
      if (partition.equals("random")) {
        if (SQLDAPTest.cidByRange) whichClause = 1; //cid range
        else if (withReplicatedTables) {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(portfolioPartitionClauseForSnappy.length);
          else
            whichClause = SQLTest.random.nextInt(portfolioPartitionClause.length);
        }
        else{
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(portfolioPartitionClauseForSnappy.length -1);
          else
            whichClause = SQLTest.random.nextInt(portfolioPartitionClause.length -1);
        }
        
        if (isConcUpdateTx && (whichClause == 3 || whichClause == 4))
          whichClause = 2;   //to avoid update on a partitioned field        
      }       
      else if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          whichClause = portfolioPartitionClauseForSnappy.length -1;
        else
          whichClause = portfolioPartitionClause.length-1; //last clause is "replicate"
      }
      else {
        whichClause = Integer.parseInt(partition);
      }  
      // don't allow whichClause = 0 for HDFS (affects numOfPRs and waitForRebalRecov)
      // as this configures default colocation with the parent region
      if (SQLTest.hasHdfs && whichClause == 0) whichClause++;  
      boolean isReplicated = SQLPrms.isSnappyMode()? whichClause == portfolioPartitionClauseForSnappy
          .length-1 : whichClause == portfolioPartitionClause.length-1;
      if (isReplicated) partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");

      if (!testServerGroups) writePortfolioPartitionToBB(whichClause);
      else writePortfolioPartitionSGToBB(whichClause, serverGroup);
      String clause = SQLPrms.isSnappyMode()? portfolioPartitionClauseForSnappy[whichClause]:
          portfolioPartitionClause[whichClause];
      if (testServerGroupsInheritence && whichClause == 1 
          && ((ArrayList<String>)partitionMap.get("customersPartition")).size() ==1 
          && ((ArrayList<String>)partitionMap.get("customersPartition")).contains("cid")
          && ((String) partitionMap.get("custPartitionOn")).equals("range")
          && ((String) partitionMap.get("portfolioSG")).equals(partitionMap.get("customersSG"))) {
        String portfClause = clause + " colocate with (trade.customers)";
        //other than the default colocation, must use "colocated with" 
        partitionClause = getPartitionSG(portfClause, serverGroup);
      } else if (testMultiTableJoin) {
        String portfClause = null;
      //add replicate customers case
        if (((String)partitionInfoMap.get("trade.customers")).equalsIgnoreCase("replicate")) {
          if(SQLPrms.isSnappyMode())
            portfClause = " partition_by 'cid' ";
          else
            portfClause = " partition by column (cid) ";

        } else {
          if(SQLPrms.isSnappyMode())
            portfClause = " partition_by 'cid', colocate_with 'trade.customers'";
          else
            portfClause = " partition by column (cid) colocate with (trade.customers)";
        }
        partitionClause = getPartitionSG(portfClause, serverGroup);
      } else {
        partitionClause = getPartitionSG(clause, serverGroup); //with the SG clause
      }       
      
      if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.PORTFOLIO" + HDFSSqlTest.STORENAME) != null && partitionClause.contains(" colocate ")){      
        counters.decrement(SQLBB.numOfPRs); //PR and global hash index
        Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS including colocation for PORTFOLIO" : " "));
      }
      Log.getLogWriter().info("getPartitionClause portfolio: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    }  
    else if (tableName.equalsIgnoreCase("trade.networth")){
      String netClause = null; 
      if (partition.equals("random")) {
        if (withReplicatedTables) {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(networthPartitionClauseForSnappy.length);
          else
            whichClause = SQLTest.random.nextInt(networthPartitionClause.length);
        }
        else{
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(networthPartitionClauseForSnappy.length-1);
          else
            whichClause = SQLTest.random.nextInt(networthPartitionClause.length-1);
        }

        //avoid update partition key in procedure
        boolean hasProcedure = TestConfig.tab().booleanAt(SQLPrms.hasProcedure, false);
        if (hasProcedure && whichClause == 3) whichClause++; //do not partitioned on cash, which will be updated in procedure
        if (isConcUpdateTx &&  (whichClause == 4 || whichClause == 6)) {
          whichClause = 5;   //to avoid update on a partitioned field in conc updateTx
        }
      } 
      else if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          whichClause = networthPartitionClauseForSnappy.length-1;
        else
          whichClause = networthPartitionClause.length-1; //last clause is "replicate"
      }
      else {
        whichClause = Integer.parseInt(partition);
      }  
      // don't allow whichClause = 0 for HDFS (affects numOfPRs and waitForRebalRecov)
      // as this configures default colocation with the parent region
      if (SQLTest.hasHdfs && whichClause == 0) whichClause++;  

      boolean isReplicated = SQLPrms.isSnappyMode()?whichClause == networthPartitionClauseForSnappy
          .length-1 : whichClause == networthPartitionClause.length-1;
      if (isReplicated) partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");
           
      /* as fk cid in netWorth table is also a primary -- no mater whether 
       * customers and networth are in same server group or not, the default partitioning is on cid
       * however, server groups will be used to determint if tables are colocated or not
       * */
      if (!testServerGroups) writeNetworthPartitionToBB(whichClause);
      else writeNetworthPartitionSGToBB(whichClause, serverGroup);
      String clause = SQLPrms.isSnappyMode() ? networthPartitionClauseForSnappy[whichClause] :
          networthPartitionClause[whichClause];
      if (testServerGroupsInheritence && whichClause == 1 
          && ((ArrayList<String>)partitionMap.get("customersPartition")).size() ==1 
          && ((ArrayList<String>)partitionMap.get("customersPartition")).contains("cid")
          && ((String) partitionMap.get("custPartitionOn")).equals("range")
          && ((String) partitionMap.get("networthSG")).equals(partitionMap.get("customersSG"))) {
        //add replicate customers case
        if (((String)partitionInfoMap.get(tableName)).equalsIgnoreCase("replicate")) {
          netClause = clause;
        } else {
          netClause = clause + " colocate with (trade.customers)";
        }
        //other than the default colocation, must use "colocated with" 
        partitionClause = getPartitionSG(netClause, serverGroup);
      } else if (testMultiTableJoin) {
        //add replicate customers case
        if (((String)partitionInfoMap.get("trade.customers")).equalsIgnoreCase("replicate")) {
          if(SQLPrms.isSnappyMode())
            netClause = " partition_by 'cid' ";
          else
          netClause = " partition by column (cid) ";
        } else {
          if(SQLPrms.isSnappyMode())
            netClause = " partition_by 'cid', colocate_with 'trade.customers' ";
          else
          netClause = " partition by column (cid) colocate with (trade.customers)";
        }
      	partitionClause = getPartitionSG(netClause, serverGroup);
      } else {
        partitionClause = getPartitionSG(clause, serverGroup);
      }
      
      if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.NETWORTH" + HDFSSqlTest.STORENAME) != null && partitionClause.contains (" colocate" ) ){      
        counters.decrement(SQLBB.numOfPRs); //PR and global hash index
        Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS including colocation for NETWORTH" : " "));
      }
      Log.getLogWriter().info("getPartitionClause networth: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    } 
    else if (tableName.equalsIgnoreCase("trade.sellorders")){
      if (partition.equals("random")) {
        if (withReplicatedTables){
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(sellordersPartitionClauseForSnappy.length);
          else
            whichClause = SQLTest.random.nextInt(sellordersPartitionClause.length);
        }
        else {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(sellordersPartitionClauseForSnappy.length-1);
          else
            whichClause = SQLTest.random.nextInt(sellordersPartitionClause.length-1);
        }
        
        if (isConcUpdateTx && (whichClause == 4  || whichClause == 5 || whichClause == 7))
          whichClause = 6;   //to avoid update on a partitioned field 
      } 
      else if (partition.equals("replicate")) {
        if (SQLPrms.isSnappyMode())
          whichClause = sellordersPartitionClauseForSnappy.length - 1;
        else
          whichClause = sellordersPartitionClause.length - 1; //last clause is "replicate"
      }
      else {
        whichClause = Integer.parseInt(partition);
      } 
      // don't allow whichClause = 0 for HDFS (affects numOfPRs and waitForRebalRecov)
      // as this configures default colocation with the parent region
      if (SQLTest.hasHdfs && whichClause == 0) whichClause++;  

      boolean isReplicated = SQLPrms.isSnappyMode() ?
          whichClause == sellordersPartitionClauseForSnappy.length -1
          : whichClause == sellordersPartitionClause.length-1;
      if (isReplicated) partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");
      
      if (!testServerGroups) writeSellordersPartitionToBB(whichClause);
      else writeSellordersPartitionSGToBB(whichClause, serverGroup);

      String clause = SQLPrms.isSnappyMode()? sellordersPartitionClauseForSnappy[whichClause]:
          sellordersPartitionClause[whichClause];
      
      if (testServerGroupsInheritence && whichClause == 1  
          && ((ArrayList<String> )partitionMap.get("customersPartition")).size() ==1 
          && ((ArrayList<String> )partitionMap.get("customersPartition")).contains("cid")
          && ((String) partitionMap.get("custPartitionOn")).equals("range")
          && ((String) partitionMap.get("sellordersSG")).equals(partitionMap.get("customersSG"))) {
        String soClause = clause + " colocate with (trade.customers)";
        // test server group and will try to use colocate with customers
        partitionClause = getPartitionSG(soClause, serverGroup);
      } 
      else if (testServerGroupsInheritence && whichClause == 1  
          && ((ArrayList<String> )partitionMap.get("portfolioPartition")).size() ==1 
          && ((ArrayList<String> )partitionMap.get("portfolioPartition")).contains("cid")
          && ((String) partitionMap.get("portfPartitionOn")).equals("range")
          && ((String) partitionMap.get("sellordersSG")).equals(partitionMap.get("portfolioSG"))) {
        String soClause = clause + " colocate with (trade.portfolio)";
        // test server group and will try to use colocate with portfolio as customers may be replicated
        partitionClause = getPartitionSG(soClause, serverGroup);
      } 
      else if (testMultiTableJoin) {
        String soClause = null;
        //add replicate customers case
        if (((String)partitionInfoMap.get("trade.customers")).equalsIgnoreCase("replicate")) {
          if(SQLPrms.isSnappyMode())
            soClause = " partition_by 'cid' ";
          else
          soClause = " partition by column (cid) colocate with (trade.portfolio)";
        } else {
          if(SQLPrms.isSnappyMode())
            soClause = " partition_by 'cid', colocate_with 'trade.customers'";
          else
          soClause = " partition by column (cid) colocate with (trade.customers)";
        }
        partitionClause = getPartitionSG(soClause, serverGroup);        
      }
      else {
        partitionClause = getPartitionSG(clause, serverGroup);
      }
      sellordersClause = whichClause;
      if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.SELLORDERS" + HDFSSqlTest.STORENAME) != null && partitionClause.contains(" colocate ")){      
        counters.decrement(SQLBB.numOfPRs); //PR and global hash index
        Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS including colocation for SELLORDERS" : " "));
      }
      Log.getLogWriter().info("getPartitionClause sellorders: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    } 
    else if (tableName.equalsIgnoreCase("trade.buyorders")){
      if (partition.equals("random")) {
        if (SQLDAPTest.tidByList) whichClause = 2;
        else if (withReplicatedTables) {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(buyordersPartitionClauseForSnappy.length);
          else
            whichClause = SQLTest.random.nextInt(buyordersPartitionClause.length);
        }
        else{
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(buyordersPartitionClauseForSnappy.length -1);
          else
            whichClause = SQLTest.random.nextInt(buyordersPartitionClause.length-1);
        }
      } 
      else if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          whichClause = buyordersPartitionClause.length -1;
        else
          whichClause = buyordersPartitionClause.length-1; //last clause is "replicate"
      }
      else {
        whichClause = Integer.parseInt(partition);
      } 
      // don't allow whichClause = 0 for HDFS (affects numOfPRs and waitForRebalRecov)
      // as this configures default colocation with the parent region
      if (SQLTest.hasHdfs && whichClause == 0) whichClause++;  
      boolean isReplicated = SQLPrms.isSnappyMode() ?
          whichClause == buyordersPartitionClauseForSnappy.length-1
          : whichClause == buyordersPartitionClause.length -1;
      if (isReplicated) partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");
      
      if (!testServerGroups) writeBuyordersPartitionToBB(whichClause);
      else writeBuyordersPartitionSGToBB(whichClause, serverGroup);
      String clause = SQLPrms.isSnappyMode()? buyordersPartitionClauseForSnappy[whichClause]:
          buyordersPartitionClause[whichClause];
      if (testMultiTableJoin) {
        String boClause = null;
        //add replicate customers case
        if (((String)partitionInfoMap.get("trade.customers")).equalsIgnoreCase("replicate")) {
          if(SQLPrms.isSnappyMode())
            boClause = " partition_by 'cid' ";
          else
          boClause = " partition by column (cid) ";
        } else {
          if(SQLPrms.isSnappyMode())
            boClause = " partition_by 'cid', colocate_with 'trade.customers' ";
          else
          boClause = " partition by column (cid) colocate with (trade.customers)";
        }
        partitionClause = getPartitionSG(boClause, serverGroup);
      } else {
        partitionClause =  getPartitionSG(clause, serverGroup);
      }      
      if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.BUYORDERS" + HDFSSqlTest.STORENAME) != null && partitionClause.contains(" colocate ")){      
        counters.decrement(SQLBB.numOfPRs); //PR and global hash index
        Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS including colocation for BUYORDERS" : " "));
      }
      Log.getLogWriter().info("getPartitionClause buyorders: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    }
    else if (tableName.equalsIgnoreCase("trade.txhistory")){
      if (partition.equals("random")) {
        if (withReplicatedTables) {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(txhistoryPartitionClauseForSnappy.length);
          else
            whichClause = SQLTest.random.nextInt(txhistoryPartitionClause.length);
        }
        else {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(txhistoryPartitionClauseForSnappy.length -1);
          else
            whichClause = SQLTest.random.nextInt(txhistoryPartitionClause.length-1);
        }
      } 
      else if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          whichClause = txhistoryPartitionClauseForSnappy.length-1;
        else
          whichClause = txhistoryPartitionClause.length-1; //last clause is "replicate"
      }
      else {
        whichClause = Integer.parseInt(partition);
      }  
      
      if (SQLTest.testMultipleUniqueIndex) {
        if (SQLTest.hasHdfs && whichClause == 0) {
          whichClause++;
        }
      }
      boolean isReplicated = SQLPrms.isSnappyMode() ?
          whichClause == txhistoryPartitionClauseForSnappy.length-1
          :whichClause == txhistoryPartitionClause.length-1;
      if (isReplicated) partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");

      String clause = SQLPrms.isSnappyMode() ? txhistoryPartitionClauseForSnappy[whichClause]:
          txhistoryPartitionClause[whichClause];
      if (testMultiTableJoin) {
        String historyClause = null;
        //add replicate customers case
        if (((String)partitionInfoMap.get("trade.customers")).equalsIgnoreCase("replicate")) {
          if(SQLPrms.isSnappyMode())
            historyClause = " partition_by 'cid' ";
          else
          historyClause = " partition by column (cid) ";
        } else {
          if(SQLPrms.isSnappyMode())
            historyClause = " partition_by 'cid', colocate_with 'trade.customers' ";
          else
          historyClause = " partition by column (cid) colocate with (trade.customers)";
        }
        partitionClause = getPartitionSG(historyClause, serverGroup);
      } else {
        partitionClause = getPartitionSG(clause, serverGroup);
      }      
      /* no pk or fk in txhistory table, so default partition is no field is partitioned
       * no matter how server groups is defined
       * however, we need the server group info to determine colocation for join test
       * */
      if (!testServerGroups) writeTxhistoryPartitionToBB(whichClause);
      else writeTxhistoryPartitionSGToBB(whichClause, serverGroup);
      if (SQLTest.hasHdfs == true && hdfsExtnParams.get("TRADE.TXHISTORY" + HDFSSqlTest.STORENAME) != null && partitionClause.contains(" colocate ")){      
        counters.decrement(SQLBB.numOfPRs); //PR and global hash index
        Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS including colocation for TXHISTORY" : " "));
      }
      Log.getLogWriter().info("getPartitionClause txhistory: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    }
    else if (tableName.equalsIgnoreCase("trade.companies")){
      if (partition.equals("random")) {
        if (withReplicatedTables){
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(companiesPartitionClauseForSnappy.length);
          else
            whichClause = SQLTest.random.nextInt(companiesPartitionClause.length);
        }
        else {
          if(SQLPrms.isSnappyMode())
            whichClause = SQLTest.random.nextInt(companiesPartitionClauseForSnappy.length -1);
          else
            whichClause = SQLTest.random.nextInt(companiesPartitionClause.length-1);
        }
      } 
      else if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          whichClause = companiesPartitionClauseForSnappy.length -1;
        else
          whichClause = companiesPartitionClause.length-1; //last clause is "replicate"
      }
      else {
        whichClause = Integer.parseInt(partition);
      }   
      // don't allow whichClause = 0 for HDFS (affects numOfPRs and waitForRebalRecov)
      // as this configures default colocation with the parent region
      if (SQLTest.hasHdfs && whichClause == 0) whichClause++;  
      boolean isReplicated = SQLPrms.isSnappyMode() ?
          whichClause == companiesPartitionClauseForSnappy.length-1 :
          whichClause == companiesPartitionClause.length-1;
      if (isReplicated) partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");
      String clause = SQLPrms.isSnappyMode() ? companiesPartitionClauseForSnappy[whichClause] :
          companiesPartitionClause[whichClause];
      partitionClause = getPartitionSG(clause, serverGroup); //with the SG clause
      if (!testServerGroups) writeCompaniesPartitionToBB(whichClause);
      else writeCompaniesPartitionSGToBB(whichClause, serverGroup);
      Log.getLogWriter().info("getPartitionClause companies: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    } 
    else if (tableName.equalsIgnoreCase("trade.sellordersdup")){
      whichClause = sellordersClause; //make sure it is exactly same as sellorders partition

      boolean isReplicated = SQLPrms.isSnappyMode()?
          whichClause == sellordersPartitionClauseForSnappy.length-1
          :whichClause == sellordersPartitionClause.length-1;
      if (isReplicated) partitionInfoMap.put(tableName, "replicate");
      else partitionInfoMap.put(tableName, "any");
      
      if (!testServerGroups) writeSellordersPartitionToBB(whichClause);
      else writeSellordersPartitionSGToBB(whichClause, serverGroup);

      String clause = SQLPrms.isSnappyMode() ? sellordersPartitionClauseForSnappy[whichClause]
          :sellordersPartitionClause[whichClause];
      if (testServerGroupsInheritence && whichClause == 1  
          && ((ArrayList<String> )partitionMap.get("customersPartition")).size() ==1 
          && ((ArrayList<String> )partitionMap.get("customersPartition")).contains("cid")
          && ((String) partitionMap.get("custPartitionOn")).equals("range")
          && ((String) partitionMap.get("sellordersSG")).equals(partitionMap.get("customersSG"))) {
        String soClause = clause + " colocate with (trade.customers)";
        // test server group and will try to use colocate with customers
        partitionClause = getPartitionSG(soClause, serverGroup);
      } 
      else if (testServerGroupsInheritence && whichClause == 1  
          && ((ArrayList<String> )partitionMap.get("portfolioPartition")).size() ==1 
          && ((ArrayList<String> )partitionMap.get("portfolioPartition")).contains("cid")
          && ((String) partitionMap.get("portfPartitionOn")).equals("range")
          && ((String) partitionMap.get("sellordersSG")).equals(partitionMap.get("portfolioSG"))) {
        String soClause = clause + " colocate with (trade.portfolio)";
        // test server group and will try to use colocate with portfolio as customers may be replicated
        partitionClause = getPartitionSG(soClause, serverGroup);
      } 
      else if (testMultiTableJoin) {
        String soClause ="";
        //no in multitable join, if table added in the join, needs to be modified here as well.
        if(SQLPrms.isSnappyMode())
          soClause = " partition_by 'cid', colocate_with 'trade.customers' ";
        else
         soClause = " partition by column (cid) colocate with (trade.customers)";
        partitionClause = getPartitionSG(soClause, serverGroup);        
      }
      else {
        partitionClause = getPartitionSG(clause, serverGroup);
      }
     
      Log.getLogWriter().info("getPartitionClause sellordersdup: whichClause is " + whichClause
          + " and partition is " + partitionClause);
    } 
    
    else if (tableName.equalsIgnoreCase("emp.department")){
      if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          partitionClause = " ";
        else
        partitionClause = " replicate ";
      }
      else
    	partitionClause = " "; //TODO need to modify when the tables are used
    }
    else if (tableName.equalsIgnoreCase("emp.employees")){
      if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          partitionClause = " ";
        else
        partitionClause = " replicate ";
      }
      else {
          if(SQLPrms.isSnappyMode())
            partitionClause = " partition_by 'eid' ";
          else
            partitionClause = " partition by column (eid) "; //TODO need to modify when the tables are used
        
        ArrayList<String> partitionKey = new ArrayList<String>();
        partitionKey.add("eid");
        counters.add(SQLBB.numOfPRs, 1);
        if (SQLTest.hasHdfs == true && hdfsExtnParams.get("EMP.EMPLOYEES" + HDFSSqlTest.STORENAME) != null && partitionClause.contains(" colocate ")){      
          counters.add(SQLBB.numOfPRs, 1);
          Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs) + (SQLTest.hasHdfs ? " after calculating PR for HDFS including colocation for EMPLOYEES" : " "));
        }
        if (!testServerGroups) {
         partitionMap.put("empemployeesPartition", partitionKey); //put into BB 
         Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs));
        } else {
          String sg = serverGroup;
          if (testServerGroupsInheritence && sg.equals("default")) {
            sg = (String) partitionMap.get(tradeSchemaSG);
          }            
          partitionMap.put("employeesSG", sg); //put into BB
        }
      }
    }
    else if (tableName.equalsIgnoreCase("trade.customerprofile")){
      if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          partitionClause = " ";
        else
        partitionClause = " replicate ";
      }
      else {
        if(SQLPrms.isSnappyMode())
          partitionClause = " partition_by 'cid'";
        else
    	  partitionClause = " partition by column (cid) "; //TODO need to modify when the tables are used
    	  if (testMultiTableJoin) {
    	    counters.increment(SQLBB.numOfPRs); //PR only, used for quick HA coverage
    	    Log.getLogWriter().info("customerprofile is partitioned on cid");
    	    Log.getLogWriter().info("numOfPRs now is " + counters.read(SQLBB.numOfPRs));
    	  }
      }
    }
    else if (tableName.equalsIgnoreCase("trade.trades")){
      if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          partitionClause = " ";
        else
        partitionClause = " replicate ";
      }
      else
        partitionClause = " "; //TODO need to modify when the tables are used
    }
    else if (tableName.equalsIgnoreCase("default1.employees")){
      if (partition.equals("replicate")){
        if(SQLPrms.isSnappyMode())
          partitionClause = " ";
        else
        partitionClause = " replicate ";
      }
      else
        partitionClause = " "; //TODO need to modify when the tables are used
    }
    else {
      throw new TestException("Test issue, wrong table name in getPartitionClause method.");
    }
    
    //handle Buckets and colocate with
    if (partitionClause.contains("BUCKETS") && partitionClause.contains("colocate")) {
      String a = partitionClause.substring(0, partitionClause.indexOf("BUCKETS")-1);
      String b = partitionClause.substring(partitionClause.indexOf("BUCKETS"), 
          partitionClause.indexOf("colocate") -1 );
      if (!partitionClause.contains("SERVER GROUPS")) {
        String c = partitionClause.substring(partitionClause.indexOf("colocate"));
        partitionClause = a + c + " " + b;
      } else {
        String c = partitionClause.substring(partitionClause.indexOf("colocate"), 
            partitionClause.indexOf("SERVER GROUPS") -1);
        String s = partitionClause.substring(partitionClause.indexOf("SERVER GROUPS"));
        partitionClause = a + c + " "  + b + s;
      }
    }
    
    return partitionClause;

  }
  
  //process and retrun a String used in ddl table creation
  @SuppressWarnings("unchecked")
  public static String processServerGroup(String serverGroup) {
    if (serverGroup.equals("random")){
      ArrayList<String[]>  groups = (ArrayList<String[]> ) SQLBB.getBB().getSharedMap().get("serverGroup_tables");
      String[] group = groups.get(SQLTest.random.nextInt(groups.size()));
      if (SQLTest.random.nextBoolean())
        serverGroup = group[SQLTest.random.nextInt(group.length)];
      else
        serverGroup = "default"; //uss inheritence from schema
      Log.getLogWriter().info("random server group is "+ serverGroup);
    } else {
      serverGroup = serverGroup.replace('.', ',');
      Log.getLogWriter().info("server group is "+ serverGroup);
    }
    return serverGroup; //could be "default" server group
  }
  
  public static String getPartitionSG(String partitionClause, String sg){
    if (sg!=null && !sg.equals("default")) {
      return partitionClause + " SERVER GROUPS (" +sg +") ";
    } //default means no server groups specified -- create tables on all servers/stores
    else return partitionClause;
  }
}
