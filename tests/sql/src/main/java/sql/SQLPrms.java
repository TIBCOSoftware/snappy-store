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

import hydra.BasePrms;
import hydra.ClientPrms;
import hydra.HydraVector;
import hydra.Log;
import hydra.Prms;
import hydra.RemoteTestModule;
import hydra.TestConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sql.dmlStatements.AbstractDMLStmt;
import sql.hdfs.HDFSSqlTest;
import sql.sqlutil.DDLStmtsFactory;
import sql.sqlutil.DMLStmtsFactory;
import util.TestException;


/**
 * @author eshu
 *
 */
public class SQLPrms extends BasePrms{
  public static final String HDFS_EXTN_PARAMS = "hdfsExtnParams";
  public static final String HDFS_TRIGGER = "hdfsTriggers";
  public static final String HDFS_TRIGGER_STMT = "hdfsTriggersPrepStmt";
  public static final String CREATE_ONLY_TRIGGERS ="CreateOnlyTriggers";
  public static final String MAP_REDUCE_CLASSES = "mapReduceClasses";
  
  /** (String) The different drivers: embedded or client-server
   */
  public static Long driver;

  public static Long clientDriver;

  static {
    setValues( SQLPrms.class );
  }
   public static Long populateWithbatch;
  /**(int) dmlTables -- which tables to be used in the test based on ddl stmt in the conf
   * this is used by DMLStmtsFactory to create dmlStmt.
   * Space serparated statements
   */
  public static Long dmlTables;
  public static Long verifyByTid;
  /**
   * which dml statments will be used in the test based on the table created
   * @return int array used by DMLStmtsFactory
   */
  public static int[] getTables() {
    //Vector tables = TestConfig.tab().vecAt(SQLPrms.dmlTables, new HydraVector());
    //int[] tableArr = new int [tables.size()];
    /*
    String[] strArr = new String[tables.size()];
    for (int i = 0; i < tables.size(); i++) {
      strArr[i] = (String)tables.elementAt(i); //get what tables are in the tests
    }
    */
    String[] strArr  = getTableNames();
    int[] tableArr = new int [strArr.length];
    for (int i =0; i<strArr.length; i++) {
      tableArr[i] = DMLStmtsFactory.getInt(strArr[i]); //convert to int array
      //Log.getLogWriter().info("table " + strArr[i] + " is " + tableArr[i]);      
    }    
    return tableArr;
  }

  /**
   * which ddl statments will be used in the test
   * @return int array used by DDLStmtsFactory
   */
  @SuppressWarnings("unchecked")
  public static int[] getDDLs() {
    Vector ddls = TestConfig.tab().vecAt(SQLPrms.ddlOperations, new HydraVector());
    int[] ddlArr = new int [ddls.size()];
    String[] strArr = new String[ddls.size()];
    for (int i = 0; i < ddls.size(); i++) {
      strArr[i] = (String)ddls.elementAt(i); //get what ddl ops are in the tests
    }

    for (int i =0; i<strArr.length; i++) {
      ddlArr[i] = DDLStmtsFactory.getInt(strArr[i]); //convert to int array
    }
    return ddlArr;
  }
  /**
   * which join tables will be used to perform query
   * @return int array used by JoinTableStmtsFactory
   */
  public static int[] getJoinTables() {
    String[] strArr  = getJoinTableNames();
    int[] tableArr = new int [strArr.length];
    for (int i =0; i<strArr.length; i++) {
      tableArr[i] = sql.sqlutil.JoinTableStmtsFactory.getInt(strArr[i]); //convert to int array
    }
    return tableArr;
  }

  /**(int) dmlTables -- which tables to be used in the test based on ddl stmt in the conf
   * this is used by DMLStmtsFactory to create dmlStmt.
   * Space serparated statements
   */
  public static Long joinTables;

  /**
   * which tables are created
   * @return String array of table names
   */
  @SuppressWarnings("unchecked")
  public static String[] getTableNames() {
    Vector tables = tasktab().vecAt(SQLPrms.dmlTables,  TestConfig.tab().vecAt(SQLPrms.dmlTables, new HydraVector()));
    String[] strArr = new String[tables.size()];
    for (int i = 0; i < tables.size(); i++) {
      strArr[i] = (String)tables.elementAt(i); //get what tables are in the tests
    }
    return strArr;
  }
  
  @SuppressWarnings("unchecked")
  public static int[] getInitialRowCountToPopulateTable() {
    Vector counts = tasktab().vecAt(SQLPrms.initialRowCount,  TestConfig.tab().vecAt(SQLPrms.initialRowCount, new HydraVector()));
    int size = counts.size();
    int defaultCountSize = 1000;
    boolean useDefaultCount = false;
    if (counts.size() == 0) {
      Vector tables = tasktab().vecAt(SQLPrms.dmlTables,  TestConfig.tab().vecAt(SQLPrms.dmlTables, new HydraVector()));
      size = tables.size();
      useDefaultCount = true;
    }
    
    int[] intArr = new int[size];
    for (int i = 0; i < size; i++) {
      intArr[i] = useDefaultCount? defaultCountSize: Integer.parseInt((String)counts.elementAt(i)); 
    }
    return intArr;
  }
  
  /** (int) initial row count for each table to be populated in generic test framework
   * 
   */
  
  public static Long initialRowCount;

  /**
   * which tables are created
   * @return String array of join table names
   */
  @SuppressWarnings("unchecked")
  public static String[] getJoinTableNames() {
    Vector tables = TestConfig.tab().vecAt(SQLPrms.joinTables, new HydraVector());
    String[] strArr = new String[tables.size()];
    for (int i = 0; i < tables.size(); i++) {
      strArr[i] = (String)tables.elementAt(i); //get what tables are in the tests
    }
    return strArr;
  }

  /** (String) create table statement
   *  Space separated statements.
   */
  public static Long createTablesStatements;

  @SuppressWarnings("unchecked")
  public static String[] getCreateTablesStatements(boolean forDerby) {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.createTablesStatements, new HydraVector());
    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
      strArr[i] = (String)statements.elementAt(i);
      if (!forDerby && SQLTest.createTableWithGeneratedId) {
        if (strArr[i].contains("create table trade.customers")) {
          boolean startWith = SQLTest.random.nextBoolean();
          boolean incrementBy = SQLTest.random.nextBoolean();
          strArr[i] = "create table trade.customers (cid int not null GENERATED BY DEFAULT AS IDENTITY " 
          		+ (startWith || incrementBy ? "(" : "")
              + (startWith ? "START WITH 1 " : "") 
              + (startWith && incrementBy ? "," : "")
          		+ (incrementBy ? "INCREMENT BY 1 " : "") 
          		+ (startWith || incrementBy ? ")" : "")
          		+ ", cust_name varchar(100), since date, addr varchar(100), tid int, primary key (cid))";
        } else if (strArr[i].contains("create table trade.sellorders")) {
          boolean startWith = SQLTest.random.nextBoolean();
          boolean incrementBy = SQLTest.random.nextBoolean();
          strArr[i] = "create table trade.sellorders (oid int not null GENERATED BY DEFAULT AS IDENTITY " 
              + (startWith || incrementBy ? "(" : "")
              + (startWith ? "START WITH 1 " : "") 
              + (startWith && incrementBy ? "," : "")
              + (incrementBy ? "INCREMENT BY 1 " : "") 
              + (startWith || incrementBy ? ")" : "")
              + " constraint orders_pk primary key, cid int, sid int, qty int, ask decimal (30, 20), order_time timestamp, status varchar(10), " +
              		"tid int, constraint portf_fk foreign key (cid, sid) references trade.portfolio (cid, sid) on delete restrict, " +
              		"constraint status_ch check (status in ('cancelled', 'open', 'filled')))";
        }
      }
    }
    return strArr;
  }

  /** (String) Gfe DDL extension statement
   *  Space seperated statements.
   */
  public static Long gfeDDLExtension;

  public static Long createDiskStore;

  public static Long snappyDDLExtension;

  public static boolean isSnappyMode() {
    return TestConfig.tab().booleanAt(SQLPrms.snappyMode, false);
  }

  public static Long failOnMismatch;

  public static boolean failOnMismatch() {
    return TestConfig.tab().booleanAt(SQLPrms.failOnMismatch, true);
  }

  public static String[] getSnappyDDLExtension(String[] tables){
    Vector snappyExtn = TestConfig.tab().vecAt(SQLPrms.snappyDDLExtension, new HydraVector());
    if (snappyExtn.size() == 0)
      return tables;
    String[] strArr = new String[snappyExtn.size()];
    for (int i = 0; i < snappyExtn.size(); i++) {
      strArr[i] = tables[i] + " " + (String)snappyExtn.elementAt(i);
    }
    return strArr;
  }

  //combines create table statemensts with gfe DDL extensions
  public static String[] getGFEDDL() {
    String[] tables = getCreateTablesStatements(false);
    String[] finalDDL;
        
    //before adding any extension add JSON column to the table.
    for (int i = 0; i < tables.length; i++) {
      String currentTable = tables[i].substring(0,tables[i].indexOf("(")).toLowerCase();
    if ( SQLTest.hasJSON && ( currentTable.contains("trade.securities") || currentTable.contains("trade.buyorders") || currentTable.contains("trade.networth") || currentTable.contains("trade.customers") )) {      
         tables[i]=tables[i].trim().substring(0,tables[i].trim().length() - 1);
         String tableName = tables[i].substring(0,tables[i].indexOf("(")).toLowerCase();
         //customer table is used for multiple json objects in table testing and json with array and json with-in json testing
         if ( tableName.contains("trade.customers"))
               tables[i]+=" , json_details json , networth_json json , buyorder_json json )";
         else
               tables[i]+=" , json_details json )";
       }        
    }
    if(isSnappyMode())
      finalDDL = getGFEDDL(tables);
    else {
      if (AbstractDMLStmt.byTidList)
        finalDDL = getGFEDDLByTidList(tables);
      else if (AbstractDMLStmt.byCidRange)
        finalDDL = getGFEDDLByCidRange(tables);
      else
        finalDDL = getGFEDDL(tables);

      if (SQLTest.isOffheap) {
        for (int i = 0; i < finalDDL.length; i++) {
          if (finalDDL[i].toLowerCase().indexOf(SQLTest.OFFHEAPCLAUSE.toLowerCase()) < 0) { // don't add twice
            finalDDL[i] = finalDDL[i] + SQLTest.OFFHEAPCLAUSE;
          }
        }
      }
    }
    return finalDDL;
  }

  public static void setHdfsDDL(String[] ddlString) {
      getHdfsDDLExtn(ddlString);
  }
  
  public static String[] getHdfsDDL(String[] ddlString) {
    String[] hdfsDDL = getHdfsDDLExtn(ddlString);
    for (int i = 0; i < ddlString.length && i < hdfsDDL.length; i++) {
      ddlString[i] = ddlString[i] + hdfsDDL[i];
      Log.getLogWriter().info("hdfs extention: " + hdfsDDL[i]);
    }
    return ddlString;
  }
 
  @SuppressWarnings("unchecked")
  private static String[] getGFEDDL(String[] tables) {
    Vector<String> statements = TestConfig.tab().vecAt(SQLPrms.gfeDDLExtension, new HydraVector());
    if (statements.size() == 0)
      return tables;

    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
      String extension = statements.elementAt(i);
      if(isSnappyMode() && extension.trim().length() > 0 && !extension.contains("USING ROW")) {
        throw new TestException("GFXD syntax for create table is not supported, please use snappy" +
            " syntax");
      }
      strArr[i] = tables[i] + " " + (String)statements.elementAt(i);
    }
    return strArr;
  }

  @SuppressWarnings("unchecked")
  public static String[] getDiskStoreDDL() {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.createDiskStore, new HydraVector());
    if (statements.size() == 0)
      return null;

    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
      strArr[i] =  (String)statements.elementAt(i);
    }
    return strArr;
  }

  @SuppressWarnings("unchecked")
  public static String getRedundancyClause(int i) {
  	Vector statements = TestConfig.tab().vecAt(SQLPrms.redundancyClause, new HydraVector());
    if (statements.size() == 0)
      return " ";
    else if (i>statements.size())
    	throw new util.TestException("incorrect redundancy setting in the test config");

    else
    	return (String)statements.elementAt(i);

  }

  /** (String) Gfe persist table extension statement
   *  Space separated statements.
   */
  public static Long gfePersistExtension;

  /** (String) gfxd loader extension for table create statement
   *  Space separated statements.
   */
  public static Long loaderDDLExtension;

  //(VALUES (0, 1, 2, 3, 4, 5), VALUES (6, 7, 8, 9, 10, 11), VALUES (12, 13, 14, 15, 16, 17))
  @SuppressWarnings("unchecked")
  private static String[] getGFEDDLByTidList(String[] tables) {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.gfeDDLExtension, new HydraVector());
    if (statements.size() == 0)
      throw new util.TestException ("configuration issue, partition by list is not set");

    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
    	String gfePartition = (String)statements.elementAt(i);
    	strArr[i] = tables[i] + " " + gfePartition;
    	if (gfePartition.contains("partition by list (tid)")) {
    		if (i==0) //securities
    			strArr[i] += sql.sqlutil.GFXDTxHelper.getTidListPartition() + getRedundancyClause(i);
    		else
    		strArr[i] += sql.sqlutil.GFXDTxHelper.getTidListPartition()
    			+ "colocate with (trade.securities)" + getRedundancyClause(i);
    	} else if (gfePartition.contains("replicate")) {
        String tableName = null;
    		if (tables[i].contains("create table trade.customers" ))
    			tableName = "customers";
        else if (tables[i].contains("create table trade.securities"))
        	tableName = "securities";
        else if (tables[i].contains("create table trade.portfolio"))
        	tableName = "portfolio";
        else if (tables[i].contains("create table trade.networth"))
        	tableName = "networth";
        else if (tables[i].contains("create table trade.sellorders"))
        	tableName = "sellorders";
        else if (tables[i].contains("create table trade.buyorders"))
        	tableName = "buyorders";
        else if (tables[i].contains("create table trade.txhistory"))
        	tableName = "txhistory";

    		SQLBB.getBB().getSharedMap().put(tableName+"replicate", true);
    	}
    }
    return strArr;
  }

  //partition by range (cid) ( VALUES BETWEEN 0 AND 699, VALUES BETWEEN 699 AND 1102,
  // VALUES BETWEEN 1102 AND 1251, VALUES BETWEEN 1251 AND 1577, VALUES BETWEEN 1577 AND 1800,
  // VALUES BETWEEN 1800 AND 10000)
  @SuppressWarnings("unchecked")
  private static String[] getGFEDDLByCidRange(String[] tables) {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.gfeDDLExtension, new HydraVector());
    if (statements.size() == 0)
      throw new util.TestException ("configuration issue, partition by cid range is not set");

    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
    	String gfePartition = (String)statements.elementAt(i);
    	strArr[i] = tables[i] + " " + gfePartition;
    	if (gfePartition.contains("partition by range (cid) ")) {
    		if (i==1) //customers
    			strArr[i] += sql.sqlutil.GFXDTxHelper.getCidRangePartition() + getRedundancyClause(i);
    		else
    			strArr[i] += sql.sqlutil.GFXDTxHelper.getCidRangePartition()
    			  + "colocate with (trade.customers)" + getRedundancyClause(i);
    	}else if (gfePartition.contains("replicate")) {
        String tableName = null;
    		if (tables[i].contains("create table trade.customers" ))
    			tableName = "customers";
        else if (tables[i].contains("create table trade.securities"))
        	tableName = "securities";
        else if (tables[i].contains("create table trade.portfolio"))
        	tableName = "portfolio";
        else if (tables[i].contains("create table trade.networth"))
        	tableName = "networth";
        else if (tables[i].contains("create table trade.sellorders"))
        	tableName = "sellorders";
        else if (tables[i].contains("create table trade.buyorders"))
        	tableName = "buyorders";
        else if (tables[i].contains("create table trade.txhistory"))
        	tableName = "txhistory";

    		SQLBB.getBB().getSharedMap().put(tableName+"replicate", true);
    	}
    }
    return strArr;
  }

  /** (String) create schema statement
   *  Space seperated statements.
   */
  public static Long createSchemas;

  @SuppressWarnings("unchecked")
  public static String[] getGFESchemas() {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.createSchemas, new HydraVector());
    String sg = TestConfig.tab().stringAt(SQLPrms.schemaDefaultServerGroup, "random");
    Log.getLogWriter().info("sg is " + sg);
    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
      strArr[i] = (String)statements.elementAt(i);
      if (i==0 && SQLTest.testServerGroupsInheritence) {
        if (sg.equals("random")) {
          ArrayList groups = (ArrayList) SQLBB.getBB().getSharedMap().get("serverGroup_tables");
          String[] sGroups = (String[])groups.get(SQLTest.random.nextInt(groups.size()));
          sg = sGroups[SQLTest.random.nextInt(sGroups.length)];
        } else {
          sg = sg.replace('.',','); //parse to correct format in server groups
        }


        SQLBB.getBB().getSharedMap().put(SQLTest.tradeSchemaSG, sg);
        if (!sg.equals("default")) {
          strArr[i] += " DEFAULT SERVER GROUPS (" +sg +") ";  //only if it uses default server groups
        } else if ((SQLTest.hasAsyncDBSync || SQLTest.isWanTest) && SQLTest.isHATest) {
        	strArr[i] += " DEFAULT SERVER GROUPS (SG1,SG2,SG3,SG4) ";
         //this is to ensure that vm with DBSynchronizer does not partitioned region/tables
        } else {
        	//do nothing -- create schema on default (every data node)
        }
      } //i==0 is for trade schema
    }
    return strArr;
  }

  @SuppressWarnings("unchecked")
  public static String[] getSchemas() {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.createSchemas, new HydraVector());
    String[] strArr = new String[statements.size()];
    for (int i = 0; i < statements.size(); i++) {
      strArr[i] = (String)statements.elementAt(i);
    }
    return strArr;
  }
  
  /** (String) gfxd hdfs extension for table create statement
   *  Space separated statements.
   *  
   *  Example:
   *    "PERSISTENT HDFSSTORE (sqlhdfsStore)"
   *    "EVICTION BY CRITERIA (DATE(CREATION_TIME) < CURRENT_DATE - 7) EVICT INCOMING PERSISTENT HDFSSTORE (sqlhdfsStore)";
   */
  public static Long hdfsDDLExtn;

  public static String[] getHdfsDDLExtn(String[] ddlString) {
    Vector statements = TestConfig.tab().vecAt(SQLPrms.hdfsDDLExtn, new HydraVector());
    if (statements.size() == 0)
      return null;

    String[] strArr = new String[statements.size()];
    HashMap<String, String> hdfsExtnParams = new HashMap<String, String>();
    for (int i = 0; i < statements.size(); i++) {
      strArr[i] = ((String)statements.elementAt(i)).toUpperCase();
      strArr[i] = strArr[i].replaceAll(" +", " ");

      int tableNameStartIndex = ddlString[i].toUpperCase().indexOf("TABLE");
      int tableNameEndIndex = ddlString[i].indexOf("(", tableNameStartIndex);      
      String tableName = ddlString[i].substring(tableNameStartIndex + 6, tableNameEndIndex).trim().toUpperCase();

      if (strArr[i].contains("BY CRITERIA")) {
        int sqlPredicateStartIndex = strArr[i].indexOf("EVICTION BY CRITERIA") + 22;
        int sqlPredicateEndIndex = 0;
        if (strArr[i].contains("CASCADE")) {
          hdfsExtnParams.put(tableName + HDFSSqlTest.CASCADE, "CASCADE");
          sqlPredicateEndIndex = strArr[i].indexOf("CASCADE",sqlPredicateStartIndex);
        }
        else if (strArr[i].contains("EVICT INCOMING") || strArr[i].contains("EVICTION FREQUENCY")) {
          sqlPredicateEndIndex = strArr[i].indexOf("EVICT",sqlPredicateStartIndex);
        }
        else if (strArr[i].contains("HDFSSTORE")) {
          sqlPredicateEndIndex = strArr[i].indexOf("HDFSSTORE");

        }
        String sqlPredicate = strArr[i].substring(sqlPredicateStartIndex,sqlPredicateEndIndex - 2).trim();
        hdfsExtnParams.put(tableName + HDFSSqlTest.EVICTION_CRITERIA,sqlPredicate);
      }

      if (strArr[i].contains("EVICT INCOMING")) {
        hdfsExtnParams.put(tableName + HDFSSqlTest.EVICT_INCOMING, "EVICT INCOMING");
      }

      if (strArr[i].contains(" START ")) {
        int timestampStartIndex = strArr[i].indexOf(" START ") +7;
        int timestampEndIndex1 = strArr[i].indexOf(" ", timestampStartIndex );
        int timestampEndIndex2 = strArr[i].indexOf("'", timestampEndIndex1 + 2 ) + 1;
        String timestamp = strArr[i].substring(timestampStartIndex, timestampEndIndex2).trim();
        hdfsExtnParams.put(tableName + HDFSSqlTest.STARTTIME, timestamp);
      }

      if (strArr[i].contains("EVICTION FREQUENCY")) {
        int frequencyStartIndex = strArr[i].indexOf("EVICTION FREQUENCY") + 19;
        int frequencyEndIndex1 = strArr[i].indexOf(" ", frequencyStartIndex ) + 1 ;
        int frequencyEndIndex2 = strArr[i].indexOf(" ", frequencyEndIndex1 ) ;
        
        String frequency = strArr[i].substring(frequencyStartIndex,frequencyEndIndex2 );
        hdfsExtnParams.put(tableName + HDFSSqlTest.FREQUENCY, frequency);        
      } 

      if (strArr[i].contains("WRITEONLY")) {
        hdfsExtnParams.put(tableName + HDFSSqlTest.WRITEONLY, "WRITEONLY");
      }

      if (strArr[i].contains("HDFSSTORE")) {
        int storeNameStartIndex = strArr[i].indexOf("HDFSSTORE") +11;
        int storeNameEndIndex = strArr[i].indexOf(")", storeNameStartIndex );
        String storeName = strArr[i].substring(storeNameStartIndex,storeNameEndIndex );
        hdfsExtnParams.put(tableName + HDFSSqlTest.STORENAME, storeName);        
      }
    }
    SQLBB.getBB().getSharedMap().put(SQLPrms.HDFS_EXTN_PARAMS, hdfsExtnParams);
    Log.getLogWriter().info("HDFS extension params in BB : " + hdfsExtnParams);
    return strArr;
  }

  @SuppressWarnings("unchecked")
  public static String[] prepareFullSetTableCreateStmt() {
    String[] statements = getCreateTablesStatements(true);
    Pattern pattern = Pattern
        .compile("([a-z]*\\.[a-z]*)| constraint ([a-z]*_[a-z]*)");

    for (int i = 0; i < statements.length; i++) {
      StringBuffer statementAfterMatching = new StringBuffer();
      statements[i] = statements[i].trim().replaceAll(" +", " ").toLowerCase();

      Matcher patternMatcher = pattern.matcher(statements[i]);

      while (patternMatcher.find()) {
        if (! (patternMatcher.group().equalsIgnoreCase("trade.uuid") 
            || patternMatcher.group().equalsIgnoreCase("trade.udtprice"))){
          patternMatcher.appendReplacement(statementAfterMatching,
              patternMatcher.group() + SQLTest.DUPLICATE_TABLE_SUFFIX);
        }
      }
      patternMatcher.appendTail(statementAfterMatching);        
      statements[i] = statementAfterMatching.toString();
     
      // remove foreign key constrains 
      statements[i] = statements[i].replaceAll(",? (constraint [a-z_]* )?foreign key \\([a-z_, ]*\\) references [a-z._]* \\([a-z_, ]*\\)[ ]?(on delete restrict)?", "");
    
      // remove Primary key and other constrains 
      if ( SQLTest.hdfsMrJob )   {   
           statements[i] = statements[i].replaceAll(",?( constraint [a-z_]* )? ?primary key( ?\\(+[a-z_, ]*\\)?)?", " ");  
           statements[i] =  statements[i].replaceAll(", constraint [a-z _,\\(\\)\\' <>=0]*", ")");  
      }
      statements[i]  = statements[i] + " replicate";
    }
    return statements;
  }

 public static void setTriggerStmtInMap(){
    
    HashMap<String , String> TriggerStmt = new HashMap <String , String>();
    String identityStr = hasIdentityColumn() ? "DEFAULT , " : "";
    
    TriggerStmt.put("trade.securities_insert" ," INSERT INTO TRADE.SECURITIES_FULLDATASET VALUES (  " + identityStr + " NEWROW.SEC_ID ,  NEWROW.SYMBOL ,     NEWROW.PRICE ,      NEWROW.EXCHANGE ,  NEWROW.TID )");
    TriggerStmt.put("trade.customers_insert" , " INSERT INTO TRADE.CUSTOMERS_FULLDATASET  VALUES (  " + identityStr + " NEWROW.CID ,     NEWROW.CUST_NAME ,  NEWROW.SINCE ,      NEWROW.ADDR ,      NEWROW.TID )");
    TriggerStmt.put("trade.networth_insert" ,  " INSERT INTO TRADE.NETWORTH_FULLDATASET   VALUES (  NEWROW.CID ,     NEWROW.CASH ,       NEWROW.SECURITIES , NEWROW.LOANLIMIT , NEWROW.AVAILLOAN ,  NEWROW.TID )");
    TriggerStmt.put("trade.portfolio_insert" , " INSERT INTO TRADE.PORTFOLIO_FULLDATASET  VALUES (  NEWROW.CID ,     NEWROW.SID ,        NEWROW.QTY ,        NEWROW.AVAILQTY ,  NEWROW.SUBTOTAL ,   NEWROW.TID )");
    TriggerStmt.put("trade.sellorders_insert" ," INSERT INTO TRADE.SELLORDERS_FULLDATASET VALUES (  NEWROW.OID ,     NEWROW.CID ,        NEWROW.SID ,        NEWROW.QTY ,       NEWROW.ASK ,        NEWROW.ORDER_TIME ,  NEWROW.STATUS ,  NEWROW.TID )"); 
    TriggerStmt.put("trade.buyorders_insert" , " INSERT INTO TRADE.BUYORDERS_FULLDATASET  VALUES (  NEWROW.OID ,     NEWROW.CID ,        NEWROW.SID ,        NEWROW.QTY ,       NEWROW.BID ,        NEWROW.ORDERTIME ,   NEWROW.STATUS ,  NEWROW.TID )");
    TriggerStmt.put("trade.txhistory_insert" , " INSERT INTO TRADE.TXHISTORY_FULLDATASET  VALUES (  NEWROW.CID,      NEWROW.OID,         NEWROW.SID,         NEWROW.QTY,        NEWROW.PRICE,       NEWROW.ORDERTIME,    NEWROW.TYPE,     NEWROW.TID)");
    TriggerStmt.put("trade.trades_insert" ,    " INSERT INTO TRADE.TRADES_FULLDATASET     VALUES (  NEWROW.TID,      NEWROW.CID,         NEWROW.EID,         NEWROW.TRADEDATE)");
    TriggerStmt.put("trade.companies_insert" , " INSERT INTO TRADE.COMPANIES_FULLDATASET  SELECT * FROM  TRADE.COMPANIES WHERE SYMBOL =  NEWROW.SYMBOL  AND EXCHANGE = NEWROW.EXCHANGE");
    
    TriggerStmt.put("trade.securities_delete" ,"DELETE FROM TRADE.SECURITIES_FULLDATASET WHERE  SEC_ID =  OLDROW.SEC_ID"); 
    TriggerStmt.put("trade.customers_delete" , "DELETE FROM TRADE.CUSTOMERS_FULLDATASET  WHERE CID=OLDROW.CID");
    TriggerStmt.put("trade.networth_delete" ,  "DELETE FROM TRADE.NETWORTH_FULLDATASET   WHERE CID=OLDROW.CID");
    TriggerStmt.put("trade.portfolio_delete" , "DELETE FROM TRADE.PORTFOLIO_FULLDATASET  WHERE CID=OLDROW.CID AND SID =OLDROW.SID");
    TriggerStmt.put("trade.sellorders_delete" ,"DELETE FROM TRADE.SELLORDERS_FULLDATASET WHERE OID=OLDROW.OID ");
    TriggerStmt.put("trade.buyorders_delete" , "DELETE FROM TRADE.BUYORDERS_FULLDATASET  WHERE OID=OLDROW.OID ");
    TriggerStmt.put("trade.txhistory_delete" , "DELETE FROM TRADE.TXHISTORY_FULLDATASET  WHERE NVL(CID,0) = NVL(OLDROW.CID,0) AND NVL(OID,0) = NVL(OLDROW.OID,0) AND NVL(SID,0)=NVL(OLDROW.SID,0) AND    NVL(QTY,0)=NVL(OLDROW.QTY,0) AND NVL(PRICE,0) = NVL(OLDROW.PRICE,0) AND NVL(ORDERTIME,'2013-09-19 15:51:26') = NVL(OLDROW.ORDERTIME,'2013-09-19 15:51:26') AND NVL(TYPE,' ') = NVL(OLDROW.TYPE, ' ') AND NVL(TID,0)=NVL(OLDROW.TID,0)");
    TriggerStmt.put("trade.trades_delete" ,    "DELETE FROM TRADE.TRADES_FULLDATASET     WHERE TID=OLDROW.TID");
    TriggerStmt.put("trade.companies_delete" , "DELETE FROM TRADE.COMPANIES_FULLDATASET  WHERE SYMBOL=OLDROW.SYMBOL AND EXCHANGE = OLDROW.EXCHANGE");
   
    SQLBB.getBB().getSharedMap().put(SQLPrms.HDFS_TRIGGER_STMT, TriggerStmt);
  }
  

  @SuppressWarnings("unchecked")
  public static String[] prepareTriggerStmt() {
    ArrayList<String> triggerStmt = new ArrayList<String>();
    String[] statements = getCreateTablesStatements(true);
    String[] tableNames = getTableNames();
    ArrayList<String> StrTriggerName = new ArrayList<String>();
    String insertOp="_insert";
    String deleteOp="_delete";
    int j =0;
    int tableIndex=0;
    String insertTriggerStmt =  "CREATE  TRIGGER  $TABLENAME_INSERTTRIGGER AFTER INSERT ON $TABLENAME REFERENCING NEW AS NEWROW FOR EACH ROW  $INSERT " ;    
    String deleteTriggerStmt =  "CREATE  TRIGGER  $TABLENAME_DELETETRIGGER AFTER DELETE ON $TABLENAME REFERENCING OLD AS OLDROW  FOR EACH ROW $DELETE" ;
    String updateTriggerStmt =  "CREATE  TRIGGER  $TABLENAME_DELETEFORUPDATE AFTER UPDATE ON $TABLENAME REFERENCING NEW AS NEWROW OLD AS OLDROW FOR EACH ROW  $DELETE";
    String updateTriggerStmt1 = "CREATE  TRIGGER  $TABLENAME_INSERTFORUPDATE AFTER UPDATE ON $TABLENAME REFERENCING NEW AS NEWROW OLD AS OLDROW FOR EACH ROW $INSERT";
    
    HashMap<String, String> triggerPrepStmt = (HashMap<String, String>) SQLBB.getBB().getSharedMap().get(SQLPrms.HDFS_TRIGGER_STMT);

    for (int i = 0; i < statements.length && tableIndex < tableNames.length; i++) {           
          String tableName= tableNames[tableIndex].toLowerCase();
          triggerStmt.add(insertTriggerStmt.replaceAll("\\$TABLENAME",tableName).replaceAll("\\$INSERT",triggerPrepStmt.get(tableName+insertOp )));
          if (! SQLTest.updateWriteOnlyMr ) {
          triggerStmt.add(updateTriggerStmt.replaceAll("\\$TABLENAME", tableName).replaceAll("\\$DELETE",triggerPrepStmt.get(tableName+deleteOp)));
          }
          triggerStmt.add(updateTriggerStmt1.replaceAll("\\$TABLENAME", tableName).replaceAll("\\$INSERT",triggerPrepStmt.get(tableName+insertOp)));       
          
          if (! SQLTest.updateWriteOnlyMr ) {
          triggerStmt.add(deleteTriggerStmt.replaceAll("\\$TABLENAME",tableName).replaceAll("\\$DELETE",triggerPrepStmt.get(tableName+deleteOp)));
          }

          StrTriggerName.add(tableNames[tableIndex] + "_INSERTTRIGGER");
          if (! SQLTest.updateWriteOnlyMr ) {
          StrTriggerName.add(tableNames[tableIndex] + "_DELETEFORUPDATE");  
          }
          
          StrTriggerName.add(tableNames[tableIndex] + "_INSERTFORUPDATE"); 
          
          if (! SQLTest.updateWriteOnlyMr ) {
          StrTriggerName.add(tableNames[tableIndex] + "_DELETETRIGGER");
          }
          tableIndex++;
      
    }
    SQLBB.getBB().getSharedMap().put(SQLPrms.HDFS_TRIGGER, StrTriggerName);
    return triggerStmt.toArray(new String[triggerStmt.size()]);
  }

  /** (boolean) whether snapshotIsolation is enabled.
   *  default is true;.
   */
   public static Long isSnapshotEnabled;

  /** (boolean) whether a record is manipulated only by the thread which creates it.
   *  default is true;.
   */
  public static Long testUniqueKeys;

  /** (boolean) whether records could have same field values (none primary keys)
   *  default is false;.
   */
  public static Long randomData;

  /** (boolean) whether to create networth table.
   *  default is false;.
   */
  public static Long hasNetworth;

  /** (boolean) whether there is only one thread perform insert, update, delete statements
   *  default is false;.
   */
  public static Long isSingleDMLThread;

  /** (boolean) whether update table using a trigger.
   *  default is false;.
   */
  public static Long usingTrigger;

  /** (boolean) whether query can be performed at any time.
   *  default is true;.
   */
  public static Long queryAnyTime;

  /** (int) one of the dml operations (insert, update, delete or select)
   *
   */
  public static Long dmlOperations;

  /** (String) cycleVMTarget - which node to be cycled "store, server" etc
  *
  */
 public static Long cycleVMTarget;

  /** (boolean) whether buckets rebalancing occurred in the test.
  *
  */
  public static Long rebalanceBuckets;
 
 /** (int) how long (milliseconds) it should wait before Cycle VMs again
 *
 */
 public static Long waitTimeBeforeNextCycleVM;
  /**
   * (long) how long to wait to kill another vm
   */
  public static Long killInterval;

  /**
   * (boolean) whether create index in the test
   */
  public static Long createIndex;

  /**
   * (boolean) whether drop index in the test
   */
  public static Long dropIndex;

  /**
   * (boolean) whether drop index in the test
   */
  public static Long verifyIndex;
  
  /**
   * (boolean) whether rename index in the test
   */
  public static Long renameIndex;

  /**
   * (int) initSize for customers by each thread in populating cusomter table operation
   */
  public static Long initCustomersSizePerThread;

  /**
   * (int) initSize for customers by each thread in populating securities table operation
   */
  public static Long initSecuritiesSizePerThread;

  /**
   * (boolean) indicate partition by keys, used to work around bug #39913
   */
  public static Long testPartitionBy;

  /**
   * (String) indicate which server groups a dataStore belongs to
   */
  public static Long serverGroups;

  /**
   * (int) indicate how many store hosts in the test, used for setting server groups
   */
  public static Long storeHosts;

  /**
   * (boolean) indicate whether server group will be test
   */
  public static Long testServerGroups;

  /**
   * (boolean) indicate whether there is replicated tables mixed with partitioned tables
   */
  public static Long withReplicatedTables;

  /**
   * (boolean) whether test inheritence of server groups from schema
   */
  public static Long testServerGroupsInheritence;

  /**
   * (String) the default server groups for the schema
   */
  public static Long schemaDefaultServerGroup;

  /**
   * (boolean) whether create unique local index on the unique field
   */
  public static Long testUniqIndex;

  /** (int) one of the ddl operations (create/drop procedure, function, trigger etc)
   *
   */
  public static Long ddlOperations;

  /** (String) redundancy clause in the partition clause of the create table statement
   *
   */
  public static Long redundancyClause;

  /** (boolean) whether use a function to populate a table from dataSource
   *
   */
  public static Long populateTableUsingFunction;

  /** (boolean) whether use a Loader to populate a table from dataSource
   *
   */
  public static Long populateThruLoader;

  /** (boolean) whether security is turned on
   *
   */
  public static Long testSecurity;

  /** (int) total number of threads in accessor nodes
   *
   */
  public static Long totalAccessorThreads;

  /** (int) total number of threads in data store nodes
   *
   */
  public static Long totalStoreThreads;

  /** (boolean) use default value during inserts
   *
   */
  public static Long useDefaultValue;

  /** (boolean) whether test Initial DDL replay
   *
   */
  public static Long testInitDDLReplay;

  /** (boolean) whether multiple threads create tables
   *
   */
  public static Long multiThreadsCreateTables;

  /**(boolean) enable AsyncEventListener/AsyncDBSynchronizer queue conflation.
   */
  public static Long enableQueueConflation;

  /**(boolean) enable AsyncEventListener/AsyncDBSynchronizer queue persistence.
   */
  public static Long enableQueuePersistence;



  /** (boolean) test has procedure
   *
   */
  public static Long hasProcedure;

  /** (boolean) whether test install asyncDBSynchronizer
   *
   */
  public static Long hasAsyncDBSync;
  
  /** (boolean) whether test install asyncEventListener
  *
  */
 public static Long hasAsyncEventListener;

 /** (boolean) Whether test install HDFS
  */
 public static Long hasHDFS;
 public static Long hasJSON;
 public static Long hdfsMrJob;
 public static Long supportDuplicateTables;
 public static Long updateWriteOnlyMr;
 public static Long ticket49794fixed;
  /** (boolean) whether test connection with transaction
   *
   */
  public static Long hasTx;
  
  /** (boolean) whether test uses connection with txn for original non-txn tests
   *  This is to prepare that gfxd to be set default to txn.
   *
   */
 public static Long setTx;

  /** (boolean) whether table partitioned by range on cid
   *getCreateTablesStatements
   */
  public static Long byCidRange;

  /** (boolean) whether test paritioned by list on tid
   *
   */
  public static Long byTidList;

  /** (boolean) whether the dml op in a tx only work on single row
   *
   */
  public static Long singleRowTx;

  /** (int) the number of nodes hosting data
   *
   */
  public static Long numOfStores;

  /** (int) the number of threads performing dml operations
   *
   */
  public static Long numOfWorkers;


  /** (int) the number of cid used in partition by cid range
   *
   */
  public static Long totalCids;
  
  /** (boolean) whether the test uses BUILTIN schema for authentication
  *
  */
 public static Long useBUILTINSchema;
 
 /** (boolean) whether the test uses LDAP schema for authentication
 *
 */
 public static Long useLDAPSchema;

  /** (boolean) whether one transaction only has one dml operation
   *
   */
  public static Long oneOpPerTx;


  /** (int) max length for securities symbol
   *
   */
  public static Long maxSymbolLength;

  /** (int) min length for securities symbol
   *
   */
  public static Long minSymbolLength;

  /** (boolean) whether table created using loader which randomly compose an object
   * instead of return a result set from a back_end DB
   */
  public static Long testLoaderCreateRandomRow ;

  /** (boolean) whether table created using writer to write through to a back_end DB
   */
  public static Long useWriterForWriteThrough ;

  /** (String) the back_end DB url used for write through
   */
  public static Long backendDB_url;

  /**
   *  (boolean) whether test join for more than three tables
   */
  public static Long testMultiTableJoin;

  /**
   *  (boolean) whether gemfirexd thin client could automatically fail over to next available server
   */
  public static Long useGemFireXDHA;

  /**
   *  (boolean) whether use the new gemfirexd configuration
   */
  public static Long useGfxdConfig;

  /**
   *  (boolean) whether test only execute independent subquery (no colocation required)
   */
  public static Long independentSubqueryOnly;
  
  /**
   *  (boolean) whether gemfirexd will be booted by multiple system users (for authenication test)
   */
  public static Long hasMultiSystemUsers;
  
  /**
   *  (boolean) whether test grant execute on routine names
   */
  public static Long hasRoutineInSecurityTest;
  
  /**
   *  (boolean) whether in security test use public as grantees
   */
  public static Long usePublicAsGrantees;
  
  /** (boolean) whether the test encounters offline persistence
  *
  */
  public static Long isOfflineTest;
  
  /** (boolean) whether the test eviction
  *
  */
  public static Long testEviction;

  /** (boolean) whether the test should avoid the lockservice and proceed in case of snappy cluster
   *
   */
  public static Long isSnappyTest;

  /**
   * (boolean) whether the test should be run in a snappy cluster or gemfireXD cluster.
   */
  public static Long snappyMode;

  /** (boolean) whether the test eviction use heap percentage
  *
  */
  public static Long useHeapPercentage;
  
  /** (double) init eviction heap percentage
  *
  */
  public static Long initEvictionHeapPercent;
  
  /** (boolean) whether the test performs concurrent update in tx (to
  * create tables without partition on the columns to be updated)
  */
  public static Long isConcUpdateTx;
  
  /** (boolean) whether the test needs to set critical Heap
   */
  public static Long setCriticalHeap;
  
  /** (boolean) whether the test needs to force compaction
   */
  public static Long forceCompaction;
  
  
  /** (boolean) whether the test has multiple async event listeners installed
   */
  public static Long useMultipleAsyncEventListener;
  
  /** (boolean) whether single hop is set in the thin client driver
   */
  public static Long isSingleHop;
  
  /** (boolean) whether only limit number of threads performing subquery.
   */
  public static Long limitNumberOfSubquery;
  
  /** (boolean) whether using alter table to add AsyncEventListener.
   */
  public static Long addListenerUsingAlterTable;
  
  /** (boolean) whether ticket 45071 is fixed.
   */
  public static Long isTicket45071fixed;
  
  /** (boolean) whether ticket 42669 is fixed.
   */
  public static Long isTicket42669fixed;
  
  /** (boolean) for testing HA
   */
  public static Long cycleVms;
  
  /** (boolean) whether generate id always
   */
  public static Long generateIdAlways;
  
  /** (boolean) whether generate default id
   */
  public static Long generateDefaultId;
  
  /** (boolean) whether generate default id in the create table statement
   */
  public static Long createTableWithGeneratedId;
  
  /** (boolean) whether to drop procedure in the test in doDDLOp
   */
  public static Long dropProc;
  
  /** (boolean) whether to drop function in the test in doDDLOp
   */
  public static Long dropFunc;
  
  /** (boolean) whether to use PreparedStatment for batch operation
   */
  public static Long batchPreparedStmt;
  
  /** (boolean) whether ticket 45938 is fixed
   */
  public static Long ticket45938fixed;
  
  /** (boolean) whether securities is a replicate table
   */
  public static Long isTableSecuritiesReplicated;
  
  /** (boolean) whether load a large data file
   */
  public static Long loadUseCase2LargeDataFile;
  
  /** (boolean) whether to reproduce ticket #46311
   */
  public static Long toReproduce46311;
  
  /** (boolean) whether to reproduce ticket #39455
   */
  public static Long toReproduce39455;
  
  /** (boolean) whether to reproduce ticket #50010
   */
  public static Long toReproduce50010;
  
  /** (boolean) whether to reproduce ticket #50001
   */
  public static Long toReproduce50001;
  
  /** (boolean) whether to reproduce ticket #50115
   */
  public static Long toReproduce50115;
  
  /** (boolean) whether to reproduce ticket #50116
   */
  public static Long toReproduce50116;
  
  /** (boolean) whether to reproduce ticket #50118
   */
  public static Long toReproduce50118;
  
  /** (boolean) whether to reproduce ticket #50547
   */
  public static Long toReproduce50547;
  
  /** (boolean) whether to reproduce ticket #50546
   */
  public static Long toReproduce50546;
  
  /** (boolean) whether to reproduce ticket #50550
   */
  public static Long toReproduce50550;
  
  /** (boolean) whether to reproduce ticket #50609
   */
  public static Long toReproduce50609;
  /** (boolean) whether to reproduce ticket #50607
   */
  public static Long toReproduce50607;
  /** (boolean) whether to reproduce ticket #50608
   */
  public static Long toReproduce50608;
  /** (boolean) whether to reproduce ticket #50610
   */
  public static Long toReproduce50610;
  /** (boolean) whether to reproduce ticket #50649
   */
  public static Long toReproduce50649;
  /** (boolean) whether to reproduce ticket #50658
   */
  public static Long toReproduce50658;
  /** (boolean) whether to reproduce ticket #50676
   */
  public static Long toReproduce50676;
  /** (boolean) whether to reproduce ticket #50710
   */
  public static Long toReproduce50710;
  
  /** (boolean) whether to reproduce ticket #50962
   */
  public static Long toReproduce50962;
  
  /** (boolean) whether to reproduce ticket #51113
   */
  public static Long toReproduce51113;
  
  /** (boolean) whether to reproduce ticket #51276
   */
  public static Long toReproduce51276;
  
  /** (boolean) whether to reproduce ticket #51249
   */
  public static Long toReproduce51249;
  
  /** (boolean) whether to reproduce ticket #51090
   */
  public static Long toReproduce51090;
  
  /** (boolean) whether to reproduce ticket #51255
   */
  public static Long toReproduce51255;
  
  /** (boolean) whether to reproduce ticket #43511
   */
  public static Long toReproduce43511;
  
  /** (boolean) work around 49565 by setting socket-lease-time
   */
  public static Long workAround49565;
  
  /** (boolean) whether to use old listagg impl
   */
  public static Long useOldListAgg;
  
  /** (boolean) whether to use listagg new impl
   */
  public static Long useListAggNewImpl;
  
  /** (boolean) whether to use prepared stmt to call listagg 
   */
  public static Long useListAggPreparedStmt;
  
  /** (boolean) whether to use c3p0 connection pool 
   */
  public static Long useC3P0ConnectionPool;
  
  /** (boolean) whether to use DBCP connection pool 
   */
  public static Long useDBCPConnectionPool;
  
  /** (boolean) whether to use Tomcat connection pool 
   */
  public static Long useTomcatConnectionPool;
  
  /** (boolean) whether there is alter table drop column with data in the test
   */
  public static Long alterTableDropColumn;
  
  /** (boolean) whether there is trade.companies table in the test
   */
  public static Long hasCompanies;
  
  /** (boolean) whether off heap is enabled for tables
   */
  public static Long isOffheap;
  
  /** (boolean) whether some tables on heap and others offheap
   */
  public static long mixOffheapTables;

  /** (boolean) whether off-heap is randomized so that some tables have off-heap and others don't
   */
  public static Long randomizeOffHeap;
  
  /** (boolean) whether ticket #46799 is fixed
   */
  public static Long ticket46799fixed;
  
  /** (boolean) whether ticket #46803 is fixed
   */
  public static Long ticket46803fixed;
  
  /** (boolean) whether ticket #46980 is fixed
   */
  public static Long ticket46980fixed;
  
  /** (boolean) whether work around 51582 in the test.
   */
  public static Long workaround51582;
  
  
  /** (boolean) whether disable using statement to update date when oracle is back-end db (due to 48248)
   */
  public static Long disableUpdateStatement48248;
  
  /** (boolean) whether 51519 could be worked around
   */
  public static Long testworkaroundFor51519;
  
  /**
   *  partitioning for companies table ddl 
   */
  public static Long companiesTableDDLExtension;
  
  /**
   *  redundancy clause for companies table ddl 
   */
  public static Long companiesTableRedundancy;
  
  
  /**
   *  persistence clause for companies table ddl 
   */
  public static Long gfeCompaniesPersistExtension;
  
  /**
   *  whether allows a clob to be null 
   */
  public static Long noNullClob;
  
  
  /**
   *  whether this is a Functional test using tpc-e 
   */
  public static Long isFunctionalTest;
  
  /**
   *  whether this logs dml ops in tpc-e 
   */
  public static Long logDML;
  
  /**
   *  whether create trade_market table (used to track trades send to market) use defulat id 
   */
  public static Long tradeToMarketWithDefaultId;
  
  /**
   *  whether using skip constraints connection properties for import_table 
   */
  public static Long skipConstraints;
  
  
  /**
   *  whether update partitioned column in the certain tests
   */
  public static Long allowUpdateOnPartitionColumn;
  
  /*
   * (String) classpath-relative path to a SQL script
   */
  public static Long sqlFilePath;
  
  /*
   * (boolean) whether create recursive trigger in the test
   */
  public static Long createRecursiveTrigger;
  
  /*
   * (boolean) whether use default disk store for eviction overflow
   */
  public static Long useDefaultDiskStoreForOverflow;
  
  /*
   * (boolean) how long Master controller will wait before shut down cluster due to failure
   */
  public static Long sleepSecondBeforeShutdown;
  /*
   * (String) create table portfoliov1 statement
   */
  public static Long portfoliov1Statement;
  
  /*
   * (String) whether test has portfoliov1 table
   */
  public static Long hasPortfolioV1;
  
  /*
   * (String) whether test has customersDup table for triggered customers events
   */
  public static Long hasCustomersDup;
  
  /*
   * (double) eviction percentage used in the test.
   */
  public static Long evictionHeapPercentage;
  
  /*
   * (boolean) whether prevent concurrent dml during Offline situation
   */
  public static Long syncHAForOfflineTest;
  
  /**
   *  whether use md5 checksum to verify clob or long varchar field
   */
  public static Long useMD5Checksum;
  
  public static Long testMultipleUniqueIndex;
  
  public static long getRandSeed() {
    long seed = -1;
    Object val = TestConfig.tab().get(Prms.randomSeed);
    if (val !=null && val instanceof Long )
      seed = ( (Long) val ).longValue();
    else if (val !=null && val instanceof String )
      seed = Long.parseLong( (String) val );
    else
      seed = System.currentTimeMillis();
      
    // perturb the seed in a predictable way so each client vm has a different one
    String clientName = System.getProperty(ClientPrms.CLIENT_NAME_PROPERTY);
    if (clientName != null) { // running in client
      seed += RemoteTestModule.getMyVmid();
    } // else do nothing
    
    return seed;
  }
   
  /*
   * (boolean) whether verify table results using order by primary key in the test with
   * large table data 
   * default to false
   */
  public static Long verifyUsingOrderBy;
  
  /*
   * (boolean) whether allow concurrent ddl (create/drop index & procedure) in the test
   */
  public static Long allowConcDDLDMLOps;
  
  /*
   * (boolean) whether limit concurrent DDL ops in the test
   */
  public static Long limitConcDDLOps;
  
  /*
   * (boolean) whether dump threads concurrently in the test run
   */
  public static Long dumpThreads;
  
  /*
   * (int) time interval (ms) between dump threads in the test run
   */
  public static Long dumpThreadsInterval;
 
  /*
   * (int) total time (seconds) to be to dump threads (depends on maxResultWaitSec)
   */
  public static Long dumpThreadsTotalTime;
  
  /*
   * (boolean) whether add sec_id in projection list in select (for debugging purpose)
   */
  public static Long addSecidInProjection;
  
  /** (boolean) whether to fail the test when dml op retruns different update count vs derby
  *
  */
  public static Long failAtUpdateCountDiff;
  
  /** (boolean) whether use newer version of the tables for txn testing
  *
  */
  public static Long useNewTables;
  
  /** (boolean) whether any tables has a secondary bucket or is replicated
   * used for checking tx batching behavior using thin client driver
  *
  */
  public static Long hasSecondaryTables;
  
  /*
   * for DBSynchronizer whether it is parallel or not
   */
  public static Long isParallelDBSynchronizer;

  public static Long queryHDFSWhileVerifyingResults;
  
  /** (boolean) whether the identity column is set or not.
   *  default is false;.
   */
  public static Long setIdentityColumn;
  public static boolean hasIdentityColumn() {
    Long key = setIdentityColumn;
    return tasktab().booleanAt( key, tab().booleanAt( key, false));
  }
  

  public static String getSqlFilePath() {
    if (SQLPrms.tasktab().get(sql.SQLPrms.sqlFilePath) != null) {
      return SQLPrms.tasktab().stringAt(sql.SQLPrms.sqlFilePath);
    } else {
      return SQLPrms.tab().stringAt(sql.SQLPrms.sqlFilePath);
    }
  }
  /**
   * (boolean)
   * Whether to record data in BB or not.  Defaults to true.
   */
  public static Long insertInBB;
  public static boolean isInsertInBB() {
    Long key = insertInBB;
    return tasktab().booleanAt(key, tab().booleanAt(key, true));
  }
  public static Long traceFlags;
  public static String getTraceFlags() {
    return tasktab().stringAt(traceFlags, tab().stringAt(traceFlags, null));
  }

  public static Long dumpBackingMap;
  public static boolean getDumpBackingMap() {
    Long key = dumpBackingMap;
    boolean value = tasktab().booleanAt(key, tab().booleanAt(key, false));
    return value;
  }
  
  public static long mbeanTest;
  
  public static boolean isMBeanTest() {
    return tab().booleanAt(mbeanTest, false);
  }
  
  /*
   * (String) path to save sqlfire disk stores at common location
   * for disk compatibility testing
   */
  public static Long sqlfireDiskStorePath;
 
  /**
   * Use new Generic SQLF test framework when useGenericSQLModel=true,
   * otherwise use old model
   */
  public static Long useGenericSQLModel;
  
  /*
   * (String) hostname where the gfxd is running
   */
  public static Long host;
  
  /*
   * (String) port at which gfxd is running
   */
  public static Long port;
public static Long alterTableDMLs;

}
