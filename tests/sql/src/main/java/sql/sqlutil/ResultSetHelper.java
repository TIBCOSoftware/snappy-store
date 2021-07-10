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
package sql.sqlutil;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.query.types.*;
import com.gemstone.gemfire.cache.query.internal.types.*;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.cache.query.Struct;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDumpLocalResultMessage;

import sql.*;
import sql.dmlStatements.TradeBuyOrdersDMLStmt;
import sql.dmlStatements.TradeCompaniesDMLStmt;
import sql.sqlDAP.SQLDAPTest;
import sql.sqlTx.SQLDistTxTest;

import util.*;
import hydra.*;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Blob;
import java.sql.Timestamp;

/**
 * @author eshu
 *
 */
public class ResultSetHelper {

  // since this class is also used by JUnit tests, so any hydra initialization
  // should be done in the static block below ignoring initialization exceptions

  static boolean setCriticalHeap;
  static boolean isTicket42171Fixed = false;
  // moving reference to TradeCompaniesDMLStmt to here since this is used in
  // unit tests also
  public static final boolean reproduce46886 = true;
  public static boolean useMD5Checksum;
  public static boolean testworkaroundFor51519;
  public static int longVarCharSize = 1000;
  //public static Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("PST"));
  public static String defaultTimeZone = "PST";

  static {
    try {
      setCriticalHeap = SQLTest.setCriticalHeap;
    } catch (ExceptionInInitializerError err) {
      // ignore for junit tests
      setCriticalHeap = false;
    }
    try {
      useMD5Checksum = TestConfig.tab().booleanAt(sql.SQLPrms.useMD5Checksum,
          true);
    } catch (ExceptionInInitializerError err) {
      // ignore for junit tests
      useMD5Checksum = false;
    } catch (NullPointerException npe) {
      // ignore for junit tests
      useMD5Checksum = false;
    }
    try {
      testworkaroundFor51519 = TestConfig.tab().booleanAt(sql.SQLPrms.testworkaroundFor51519,
          false) && !SQLTest.hasDerbyServer; //only see in multihost tests
    }  catch (ExceptionInInitializerError err) {
      // ignore for junit tests
      testworkaroundFor51519 = false;
    } catch (NullPointerException npe) {
      // ignore for junit tests
      testworkaroundFor51519 = false;
    }

  }

  /**
   * To construct StructType from the resultSet given.
   * @param rs -- resultSet used to get StructType
   * @return StructType for each row of the resultSet
   */
  public static StructTypeImpl getStructType (ResultSet rs) {
    int numOfColumns;
    ResultSetMetaData rsmd;
    try {
      rsmd = rs.getMetaData();
      numOfColumns = rsmd.getColumnCount();

    } catch (SQLException se) {
      throw new TestException ("could not get resultSet metaData" + TestHelper.getStackTrace(se));
    }

    ObjectType[] oTypes = new ObjectType[numOfColumns];
    String[] fieldNames = new String[numOfColumns];
    try {
      for (int i=0; i<numOfColumns; i++) {
        Class<?> clazz = null; 
        if (rsmd.getColumnClassName(i+1).contains("byte[]")) //work around no class for byte[] case
          clazz = Class.forName("sql.sqlutil.ResultSetHelper$ByteClass");
        /* to be added once gfxd consistently returns Short for both class and value
        else if (rsmd.getColumnClassName(i+1).contains("Short")) {
          clazz = Class.forName("java.lang.Integer"); 
        }
        */
        else {
          //for json get String value to compare
          if (rsmd.getColumnClassName(i+1).contains("JSON") )
            clazz = Class.forName("java.lang.String");
          else
          clazz = Class.forName(rsmd.getColumnClassName(i+1));
        }
        
        oTypes[i] = new ObjectTypeImpl(clazz); //resultSet column starts from 1
        fieldNames[i] = rsmd.getColumnName(i+1).toUpperCase();//resultSet column starts from 1
      }
    } catch (SQLException se) {
      throw new TestException ("could not getStruct from resultSet\n" + TestHelper.getStackTrace(se));
    } catch (ClassNotFoundException cnfe) {
      throw new TestException ("no class available for a column in result set\n" + TestHelper.getStackTrace(cnfe));
    }

    StructTypeImpl sType = new StructTypeImpl (fieldNames, oTypes);
    return sType;
  }


  /**
   * convert a resultSet to a list of struct based on the StructType provided
   * @param rs -- a resultSet to be converted
   * @param sType -- StructTypeImpl used to form the Struct object
   * @param isDerby -- whether this is a Derby resultSet
   * @return a list of Struct object from the resultSet
   */
  public static List<Struct> asList(ResultSet rs, StructTypeImpl sType, boolean isDerby) {
    if (rs == null) return null;
    boolean[] success = new boolean[1];
    List<Struct> aList = null;
    aList = asList(rs, sType, success, isDerby);
    if (success[0]) //successful
      return aList;
    else {
    	return null;
    }

  }

  /*
   * Really crude method for dumping the contents of a result
   * set to a log file.  Intended for debugging purposes only.
   */
  public static void logResultSet(LogWriter lw, ResultSet rs) {
    // IMPROVE_ME...  Should not hard-code max-column-width of 20 chars
    logResultSet(lw, rs, 20);
  }

  public static void logResultSet(LogWriter lw, ResultSet rs, int colWidths) {
    if (rs == null)
      return;
    try {
      ResultSetMetaData rsmd = rs.getMetaData();
      int colCount = rsmd.getColumnCount();
      StringBuffer colNamesSB = new StringBuffer();
      for (int i = 1; i <= colCount; i++) {
        String label = String.format("%1$#" + colWidths + "s", rsmd.getColumnLabel(i));
        colNamesSB.append(label + "|");
      }
      lw.info(colNamesSB.toString());
      while (rs.next()) {
        StringBuffer row = new StringBuffer();
        for (int i = 1; i <= colCount; i++) {
          String value = rs.getString(i);
          if (value == null) {
            value = "null";
          }
          value = value.substring(0, Math.min(20, value.length()));
          value = String.format("%1$#" + colWidths + "s", value.trim());
          row.append(value + "|");
        }
        lw.info(row.toString());
      }
    } catch (SQLException se) {
      throw new TestException("Error logging result set", se);
    }
  }

  /**
   * convert a ResultSet to a List
   * @param rs the ResultSet needs to be converted
   * @param isDerby whether this is a derby resultSet
   * @return a list converted from a resultSet
   */
  public static List<Struct> asList(ResultSet rs, boolean isDerby) {
    if (rs == null) return null;
    return asList(rs, getStructType(rs), isDerby);
  }

  public static List<Struct> asList(ResultSet rs, StructTypeImpl sType, boolean[] success, boolean isDerby) {
    if (rs == null) return null;
    success[0] = true;
    String[] fieldNames = sType.getFieldNames();
    ObjectType[] oTypes = sType.getFieldTypes();
    int columnSize = fieldNames.length;
    List<Struct> aList = new ArrayList<Struct>();
    if(SQLPrms.isSnappyMode())
      useMD5Checksum = false;
    try {
      while (rs.next()) {
        Object[] objects = new Object[columnSize];

        for (int i =0; i<columnSize; i++) {
        	if (java.sql.Blob.class.isAssignableFrom(oTypes[i].resolveClass())) {
        	//convert blob/byte array to String, so it can be compared for verification
        		Blob blob = rs.getBlob(i+1);
        		if (blob != null) {
        			if (blob.length()>0) {
        			  if (useMD5Checksum) {
        			    objects[i] = convertByteArrayToChecksum(blob.getBytes(1, (int) blob.length()), blob.length());
        			  } else
        			    objects[i] = convertByteArrayToString(blob.getBytes(1, (int)blob.length()));
        			} else
        				objects[i] = "empty";
        		}
        		else
        			objects[i] = blob;
        	} else if (java.sql.Clob.class.isAssignableFrom(oTypes[i].resolveClass())) {
          	//convert clob to String, so it can be compared for verification
          		Clob clob = rs.getClob(i+1);
          		if (clob != null) {
          			if (clob.length()>0) {
            			BufferedReader reader = new BufferedReader(clob.getCharacterStream());
            			//add MD5 checksum for comparison
            			if (useMD5Checksum) {
            			  objects[i] = convertBufferedReaderToChecksum(reader, clob.length());
            			} else objects[i] = convertCharArrayToString(reader, (int)clob.length());
          				try {
          					reader.close();
          				} catch (IOException e) {
            				throw new TestException("could not close the BufferedReader" +
            						TestHelper.getStackTrace(e));
            			}
          			} else objects[i] = "empty profile";
          		}
          		else
          			objects[i] = clob;
          } else if (oTypes[i].getSimpleClassName().equalsIgnoreCase("ResultSetHelper$ByteClass")) {
            byte[] bytes = rs.getBytes(i+1);
            if (bytes != null) {
              if (bytes.length >0)
                objects[i] = convertByteArrayToString(bytes);
              else
                objects[i] = "empty";
            }
            else 
              objects[i] = rs.getObject(i+1);
          } else if (oTypes[i].getSimpleClassName().equalsIgnoreCase("String")) {
            String s = rs.getString(i+1);
            if (s!=null && s.length()>longVarCharSize) objects[i] = convertStringToChecksum(s, s.length());
            else objects[i] = s;
            
          } else if (oTypes[i].getSimpleClassName().equalsIgnoreCase("Timestamp") && testworkaroundFor51519) {
            objects[i] = rs.getTimestamp(i+1, getCal());            
          } else if (oTypes[i].getSimpleClassName().equalsIgnoreCase("Date") && testworkaroundFor51519) {
            objects[i] = rs.getDate(i+1, getCal());            /*
            Date date = rs.getDate(i+1, myCal.get());   
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd-hh.mm.ss");
            formatter.setTimeZone(myCal.get().getTimeZone());
            objects[i] = formatter.format(date);
            */
          }
        	else {
        	  objects[i] = rs.getObject(i+1);
        	  //work around #46886 by comparing Integer value only
        	  if (objects[i] instanceof Short && !reproduce46886) { 
        	    objects[i] = Integer.valueOf(rs.getInt(i + 1)); 
        	  } 
        	}
        } //to get the values for each column
        com.gemstone.gemfire.cache.query.Struct aStruct = new GFXDStructImpl(sType, objects);

        aList.add(aStruct); //add the struct to the list
        
        //to track left outer join issue
        boolean logQueryResults = false;
        if(logQueryResults) Log.getLogWriter().info(aStruct.toString());
      }
    } catch (SQLException se) {
      if (isDerby) {
        handleException(se, success);
        return null;
      }
      else if (se.getSQLState().equals("23503") && !isTicket42171Fixed &&
          TradeBuyOrdersDMLStmt.testLoaderCreateRandomRow) {
        Log.getLogWriter().info("Got bug #42171");
      } //TODO comment out this after #42171 is fixed

      else	{
      	handleGFXDException(se, success);
      	return null;
      }
    }
    return aList;
  }
  
  public static Calendar getCal() {
    return (Calendar.getInstance(TimeZone.getTimeZone(ResultSetHelper.defaultTimeZone)));
  }

  //get the cid value of the rs to a list of cid, return null list if not successful
  public static List<Integer> getCidsAsList(Connection conn, ResultSet rs) {
    boolean[] success = new boolean[1];
    List<Integer> aList = null;
    aList = getCidsAsList(conn, rs, success);
    if (success[0]) //successful
      return aList;
    else
      return null;
  }

  public static List<Integer> getCidsAsList(Connection conn, ResultSet rs, boolean[] success) {
    success[0] = true;
    List<Integer> aList = new ArrayList<Integer>();
    int cid;
    try {
      while (rs.next()) {
        cid = rs.getInt("CID");
        aList.add(new Integer(cid)); //add the cid to the list
      }
    } catch (SQLException se) {
      if (SQLHelper.isDerbyConn(conn))
        handleException(se, success);
      else
      	handleGFXDException(se, success);
    }
    return aList;
  }

  
  public static List<String> getJsonAsList(Connection conn, ResultSet rs) throws SQLException{
    List<String> aList = new ArrayList<String>();
    String json;
    
      while (rs.next()) {
        json = rs.getString(1);
        aList.add(json); //add the cid to the list
      }
    return aList;
  }
  
  protected static void handleException(SQLException se, boolean[] success){
    if (se.getSQLState().equals("XN008")) { //handles the query server issue
      Log.getLogWriter().info("Query processing has been terminated due to an error on the server");
      success[0]= false;
   } else if (se.getSQLState().equals("40XL1")) {
     Log.getLogWriter().info("could not get lock during query processing");
     success[0]= false;
   } else if (se.getSQLState().equals("40001")) {
     Log.getLogWriter().info("could not be obtained a lock due to a deadlock, cycle of locks");
     success[0]= false;
   }
   else {
      SQLHelper.printSQLException(se);
      throw new TestException ("could not extract value from the resultSet\n" + TestHelper.getStackTrace(se));
    }
  }

  protected static void handleGFXDException(SQLException se, boolean[] success){
    if (se.getSQLState().equals("X0Z01") && SQLTest.isHATest) { //handles HA issue for #41471
      Log.getLogWriter().warning("GFXD_NODE_SHUTDOWN happened converting to list " +
      		"and need to retry the query");
      success[0]= false;
    } else  if ((se.getSQLState().equals("X0Z09") || se.getSQLState().equals("X0Z08"))
        && SQLTest.isOfflineTest) {
    	//TODO modify SQLState once #42443 is fixed
      Log.getLogWriter().warning("got expected Offline exception, continuing test");
      success[0]= false;
    } else if (setCriticalHeap && se.getSQLState().equals("XCL54")) {
      Log.getLogWriter().warning("memory runs low and get query cancellation exception");
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled == null) getCanceled = new boolean[1];
      getCanceled[0] = true;
      SQLTest.getCanceled.set(getCanceled);
      success[0]= false;
      return;  //do not check exception list if gfxd gets such exception
    } /* per discussion from #46968 procesing result set also gets X0Z01 with txn 
    else if (SQLHelper.gotTXNodeFailureException(se) && SQLTest.isHATest
        && SQLTest.hasTx) { //handles node failure in tx test
      Log.getLogWriter().warning("GFXD_NODE_SHUTDOWN happened during txn, " +
      		"txn should be rolled back by product and needs retry");
      SQLDistTxTest.convertTxnRSGotNodeFailure.set(true);
      success[0]= false;
    } */
     else {
      SQLHelper.printSQLException(se);
      throw new TestException ("could not extract value from resultSet\n" + TestHelper.getStackTrace(se));
    }
  }


  /**
   * convert two resultSets to two lists using their own StuctType 
   * and compare their values
   * @param derbyResultSet -- resultSet from derby
   * @param gfxdResultSet -- resultSet from GFE
   * @return false if we got processing query error on derby, we abort
   */
  public static boolean compareResultSets(ResultSet derbyResultSet, ResultSet gfxdResultSet) {
    StructTypeImpl sti = getStructType(derbyResultSet);
    List<Struct> derbyList = asList(derbyResultSet, sti, true);
    if (derbyList == null) return false; //operation will be abort

    StructTypeImpl gfxdsti = null;
    if (SQLDAPTest.cidByRange || SQLDAPTest.tidByList) gfxdsti = sti; //reuse derby structure to work around #46311 
    else gfxdsti = getStructType(gfxdResultSet);
    //Log.getLogWriter().info("Derby sti is : " + sti.toString());
    //Log.getLogWriter().info("Snappy sti is :" + gfxdsti.toString());
    List<Struct> GFEList = asList(gfxdResultSet, gfxdsti, false);
    if (GFEList == null && SQLTest.isHATest) {
      //due to #41471 in HA && possible #42443 for offline exception test coverage
      Log.getLogWriter().warning("Could not convert GFE resultSet to list due to #41471");
      return false;
    }

    //add the check to see if query cancellation exception or low memory exception thrown
    if (GFEList== null && setCriticalHeap) {      
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled[0] == true) {
        Log.getLogWriter().info("memory runs low -- avoiding the comparison");
        return false;  //do not check exception list if gfxd gets such exception
      }
    }
    compareResultSets(derbyList, GFEList);
    return true;
  }

  /**
   * convert two resultSets to two lists and compare their values and if they are in order
   * @param derbyResultSet -- resultSet from derby
   * @param GFEResultSet -- resultSet from GFE
   * @return false if we got processing query error on derby, needs to retry
   */
  public static boolean compareSortedResultSets(ResultSet derbyResultSet, ResultSet GFEResultSet) {
    StructTypeImpl sti = getStructType(derbyResultSet);
    List<Struct> derbyList = asList(derbyResultSet, sti, true);
    if (derbyList == null)  return false;  //could not parse the resultSet to list

    List<Struct> GFEList = asList(GFEResultSet, sti, false);
    if (GFEList == null && SQLTest.isHATest) {
    	//due to #41471 in HA
    	Log.getLogWriter().warning("Could not convert GFE resultSet to list due to #41471");
    	return false;
    }
    
    //add the check to see if query cancellation exception or low memory exception thrown
    if (GFEList == null && setCriticalHeap) {      
      boolean[] getCanceled = (boolean[]) SQLTest.getCanceled.get();
      if (getCanceled[0] == true) {
        Log.getLogWriter().info("memory runs low -- avoiding the comparison");
        return false;  //do not check exception list if gfxd gets such exception
      }
    }
    compareSortedResultSets(derbyList, GFEList);
    return true;
  }


  /**
   * compare two resultSets to see if they contain same values
   * @param derbyResultSet -- resultSet from derby as list
   * @param GFEResultSet -- resultSet from GFE as List
   */
  public static void compareResultSets(List<Struct> derbyResultSet,
  		List<Struct> GFEResultSet) {
  	//Log.getLogWriter().info("gfxd result list is " + listToString(GFEResultSet));
  	compareResultSets(derbyResultSet, GFEResultSet, "derby", "gfxd");
  }

  public static void compareResultSets(List<Struct> firstResultSet,
  		List<Struct> secondResultSet, String first, String second) {
    Log.getLogWriter().info("size of resultSet from " + first + " is " + firstResultSet.size());
    Log.getLogWriter().info("size of resultSet from " + second + " is " + secondResultSet.size());
    
    List<Struct> secondResultSetCopy = new ArrayList<Struct>(secondResultSet);

    StringBuffer aStr = new StringBuffer();    
    for (int i=0; i<firstResultSet.size(); i++) {
      secondResultSetCopy.remove(firstResultSet.get(i));
    }
    List<Struct> unexpected = secondResultSetCopy;
    List<Struct> missing = null;
    
    if (firstResultSet.size() != secondResultSet.size() || unexpected.size() > 0) {
      List<Struct> firstResultSetCopy = new ArrayList<Struct>(firstResultSet);
      for (int i=0; i<secondResultSet.size(); i++) {
        firstResultSetCopy.remove(secondResultSet.get(i));
      }
      missing = firstResultSetCopy;
      
      if (missing.size() > 0) {
        aStr.append("the following " + missing.size() + " elements were missing from "
            + second + " resultSet: " + listToString(missing));
      }
    }
          
    if (unexpected.size() > 0) {
      aStr.append("the following " + unexpected.size() + " unexpected elements resultSet: " + listToString(unexpected));
    }
    
    boolean findCompaniesDiff = false;
    Struct aMissingRow = null;
    StructType sType = null;
    String[] fieldNames  = null;
    ObjectType[] oTypes = null;
    Object[] values = null;
    //added to compared the difference   
    boolean addLoggingFor46886 = false;

    //ignore the failures that are due to decimal differences
    if (SQLPrms.isSnappyMode()) {
      Struct aUnexpectedRow = null;
      Object[] missingFieldValues, unexpectedFieldValues;
      if (missing != null && missing.size() > 0 && (missing.size() == unexpected.size())) {
        boolean isGenuineMismatch = false;
        for (int row = 0; row < missing.size(); row++) {
          isGenuineMismatch = false;
          aMissingRow = missing.get(row);
          aUnexpectedRow = unexpected.get(row);
          missingFieldValues = aMissingRow.getFieldValues();
          unexpectedFieldValues = aUnexpectedRow.getFieldValues();
          for (int col = 0; col < missingFieldValues.length; col++) {
            Object missingCol = missingFieldValues[col];
            Object unexpectedCol = unexpectedFieldValues[col];
            if (!missingCol.equals(unexpectedCol)) {
              if (missingCol.getClass().getName().contains("BigDecimal") && unexpectedCol
                  .getClass().getName().contains("BigDecimal")) {
                BigDecimal value1 = (BigDecimal)missingCol;
                BigDecimal value2 = (BigDecimal)unexpectedCol;
                Double diff;
                if(value1.doubleValue() > value2.doubleValue())
                  diff = value1.subtract(value2).doubleValue();
                else
                  diff = value2.subtract(value1).doubleValue();
                Log.getLogWriter().info("diff is " + diff);
                if (diff <= 0.01) {
                  isGenuineMismatch = false;
                } else {
                  isGenuineMismatch = true;
                  break;
                }
              } else {
                isGenuineMismatch = true;
                break;
              }
            }
          }
          if (isGenuineMismatch)
            break;
        }
        if (!isGenuineMismatch) return;
      }
    }


    if (missing!=null && missing.size() > 0) {      
      aMissingRow = missing.get(0);
      sType = aMissingRow.getStructType();
      fieldNames = sType.getFieldNames();
      oTypes = sType.getFieldTypes();
      values = aMissingRow.getFieldValues();
      
      if (addLoggingFor46886) {
        StringBuffer sb = new StringBuffer();
        for (ObjectType type: oTypes) {
          sb.append(type.getSimpleClassName() + " ");
        }
        Log.getLogWriter().info("missing row class names from rsmd are " + sb.toString());
        sb.delete(0, sb.length());
        for (String fieldName: fieldNames) {     
          sb.append(fieldName + " ");
        }
        Log.getLogWriter().info("missing row field names are " + sb.toString());
        sb.delete(0, sb.length());
        for (Object value: values) { 
          if (value != null) {
            sb.append(value.getClass().getName() + " ");
            if (value.getClass().getName().contains("Integer")) {
              sb.append("Integer " + value);        
            } else if (value.getClass().getName().contains("Short")) {
              sb.append("Short " + value);         
            }
          } else {
            sb.append("null ");
          }
        }
        Log.getLogWriter().info("missing row object class names are " + sb.toString());
      }
      
      for (String fieldName: fieldNames) {
        if (fieldName.equalsIgnoreCase("companyinfo")) {
          findCompaniesDiff = true;
        }
      }

      //found if the unexpected row has the same key (for companies)
      if (findCompaniesDiff) {
        String symbol = (String)aMissingRow.get("SYMBOL");
        String exchange = (String)aMissingRow.get("EXCHANGE");
        for (Struct unexpectedRow: unexpected) {
          if (symbol.equalsIgnoreCase((String)unexpectedRow.get("SYMBOL")) &&
              exchange.equalsIgnoreCase((String)unexpectedRow.get("EXCHANGE"))) {
            aStr.append("There is difference for symbol: " + symbol + " exchange: " + exchange + ":\n");
            
            for (String fieldName: fieldNames) {
              if ((fieldName.equalsIgnoreCase("COMPANYINFO") || fieldName.equalsIgnoreCase("NOTE")) &&
                  unexpectedRow.get(fieldName)!= null && 
                  aMissingRow.get(fieldName) != null &&
                  !unexpectedRow.get(fieldName).equals(aMissingRow.get(fieldName))) {
                aStr.append("column: " + fieldName + " missing is " + aMissingRow.get(fieldName)
                    + " and unexpected is " + unexpectedRow.get(fieldName) 
                    + "\n" + findStringDifference(aMissingRow.get(fieldName).toString(), 
                        unexpectedRow.get(fieldName).toString(), "missing", "unexpected")) ;
              }
            }
          }
        }
      }
    }
    
    if (unexpected!=null && unexpected.size() > 0) {
      if (addLoggingFor46886) {
        sType = unexpected.get(0).getStructType();
        fieldNames = sType.getFieldNames();
        oTypes = sType.getFieldTypes();
        values = unexpected.get(0).getFieldValues();
               
        StringBuffer sb = new StringBuffer();
        for (ObjectType type: oTypes) {
          sb.append(type.getSimpleClassName() + " ");
        }
        Log.getLogWriter().info("unexpected row class names from rsmd are " + sb.toString());
        sb.delete(0, sb.length());
        for (String fieldName: fieldNames) {
          sb.append(fieldName + " ");
        }
        Log.getLogWriter().info("unexpected row field names are " + sb.toString());
        sb.delete(0, sb.length());
        for (Object value: values) { 
          if (value!=null) {
            sb.append(value.getClass().getName() + " ");
            if (value.getClass().getName().contains("Integer")) {
              sb.append("Integer " + value);        
            } else if (value.getClass().getName().contains("Short")) {
              sb.append("Short " + value);         
            }
          } else {
            sb.append("null ");
          }
        }
        Log.getLogWriter().info("unexpected row object class names are " + sb.toString());
      }
    }
    
    if (aStr.length() != 0) {
      Log.getLogWriter().info("ResultSet from " +
      		first + " is " + listToString(firstResultSet));
      Log.getLogWriter().info("ResultSet from " +
      		second + " is " + listToString(secondResultSet));
      
      Log.getLogWriter().info("ResultSet difference is " + aStr.toString() );
      
      //throw new TestException(aStr.toString());
      
      //Throwable sme = sendResultMessage(); 
      //To be called by dumpResult as a client shut down hook so executed once in any failures
      //instead of being invoked for each result set miss match.
      //This may impact junit test result analysis
      
      //Throwable sbme = sendBucketDumpMessage();
      //when txn is enaled, we should not see #43754 -- should not have partially succeeded txn.
      if (SQLTest.isOfflineTest && !SQLTest.syncHAForOfflineTest && !SQLTest.setTx) {
        Log.getLogWriter().warning("possibly hit #43754, got the following data mismatch: " 
            + aStr.toString());
        return;
      } else {
        if(SQLPrms.isSnappyMode() && !SQLPrms.failOnMismatch()) {
          Log.getLogWriter().info("Got resultset mismatch, but continuing the test.");
          SQLTest.dumpResults();
          return;
        }
        throw new TestException(aStr.toString());
      }
      /*
      if (sme != null && sbme !=null)
      throw new TestException(aStr.toString() + TestHelper.getStackTrace(sme)
      		+ TestHelper.getStackTrace(sbme));
      else if (sme !=null)
      	throw new TestException(aStr.toString() + TestHelper.getStackTrace(sme));
      else if (sbme !=null)
      	throw new TestException(aStr.toString() + TestHelper.getStackTrace(sbme));
      else
      	throw new TestException(aStr.toString());
      */
    }

    if (firstResultSet.size() == secondResultSet.size()) {
      Log.getLogWriter().info("verified that results are correct");
    }
    else if (firstResultSet.size() < secondResultSet.size()) {
      throw new TestException("There are more data in " + second + " ResultSet");
    }
    else {
      throw new TestException("There are fewer data in " + second + " ResultSet");
    }
  }


  public static Throwable sendResultMessage() {
    try {
      GfxdDumpLocalResultMessage dmsg = new GfxdDumpLocalResultMessage();
      InternalDistributedSystem ds = getDistributedSystem();
      if (ds == null) {
      	Log.getLogWriter().info("no ds available, no msg is sent");
      } else {
	      //dmsg.send(ds, null); 
        //this is now called in client shutdown hook, so each vm will be 
        //invoked and thus no needs to send to other vm.
	      dmsg.executeLocally(ds.getDistributionManager(), SQLPrms.getDumpBackingMap());
      }
    } catch (Exception e) {
    	Log.getLogWriter().info("Unexpected exception thrown: " + TestHelper.getStackTrace(e));
    	return e;
    }
    return null;
  }

  //no longer being used, replaced by dumpLocalBucket
  public static Throwable sendBucketDumpMessage() {
    try {
      GfxdDumpLocalResultMessage.sendBucketInfoDumpMsg(null, SQLPrms.getDumpBackingMap());
    } catch (Exception e) {
      Log.getLogWriter().info(
          "Unexpected exception thrown: " + TestHelper.getStackTrace(e));
      return e;
    }
    return null;
  }
 
  //only dump bucket locally
  public static Throwable dumpLocalBucket() {
    try {
      GfxdDumpLocalResultMessage.sendBucketInfoDumpMsg(Collections
          .singleton((DistributedMember)Misc.getDistributedSystem()
              .getDistributedMember()), SQLPrms.getDumpBackingMap());
    } catch (Exception e) {
      Log.getLogWriter().info(
          "Unexpected exception thrown: " + TestHelper.getStackTrace(e));
      return e;
    }
    return null;
  }
  
  /**
   * compare two resultSets to see if they contain same values in same order
   * @param derbyResultSet -- resultSet from derby as list
   * @param GFEResultSet -- resultSet from GFE as List
   */
  public static void compareSortedResultSets(List<Struct> derbyResultSet, List<Struct> GFEResultSet) {
    compareSortedResultSets(derbyResultSet, GFEResultSet, "derby", "gfxd");
  }


  public static void compareSortedResultSets(List<Struct> firstResultSet, List<Struct> secondResultSet,
      String firstRs, String secondRs) {
    Log.getLogWriter().info("size of resultSet from " + firstRs + " is " + firstResultSet.size());
    Log.getLogWriter().info("size of resultSet from " + secondRs + " is " + secondResultSet.size());

    StringBuffer aStr = new StringBuffer();

    if (!firstResultSet.equals(secondResultSet)) {
      aStr.append("the results from order by clause are not equal\n");

      int size = firstResultSet.size() < secondResultSet.size() ? firstResultSet.size() : secondResultSet.size();
      
      for (int i=0; i<size; i++) {
        if (!firstResultSet.get(i).equals(secondResultSet.get(i))) {
          aStr.append("the " + (i+1) + "th element in the resultSet is different\n");
          aStr.append(firstRs + " has " + ((Struct) firstResultSet.get(i)).toString() + "\n");
          aStr.append(secondRs + " has " + ((Struct) secondResultSet.get(i)).toString() + "\n");          
        }
      }
      /*
      List<Struct> firstResultSetCopy = new ArrayList<Struct>(firstResultSet);
      List<Struct> SecondResultSetCopy = new ArrayList<Struct>(secondResultSet);

      for (int i=0; i<firstResultSet.size(); i++) {
        SecondResultSetCopy.remove(firstResultSet.get(i));
      }
      List<Struct> unexpected = SecondResultSetCopy;

      for (int i=0; i<secondResultSet.size(); i++) {
        firstResultSetCopy.remove(secondResultSet.get(i));
      }
      List<Struct> missing = firstResultSetCopy;

      if (unexpected.size() > 0) {
        aStr.append(secondRs + " has the following " + unexpected.size() + " unexpected elements compared to " +
        		firstRs + " resultSet: " + listToString(unexpected));
      }
      if (missing.size() > 0) {
        aStr.append(secondRs + " misses the following " + missing.size() + " elements compared to " +
            firstRs + " resultSet: " + listToString(missing));
      }
      if (unexpected.size() ==0 && missing.size() ==0) {
        //boolean notSameSortedResult = false;
        for (int i=0; i<firstResultSet.size(); i++) {
          if (!secondResultSet.get(i).equals( firstResultSet.get(i))) {
            aStr.append("the " + (i+1) + "th element in the resultSet is different\n");
            //notSameSortedResult = true;
            //Log.getLogWriter().warning("the " + (i+1) + "th element in the resultSet is different\n");
          }
        }
      }
      */
    }
    
    
    if (aStr.length() != 0) {
      Log.getLogWriter().info("ResultSet from " + firstRs + " is " + listToString(firstResultSet));
      Log.getLogWriter().info("ResultSet from " + secondRs + " is " + listToString(secondResultSet));
      if (SQLTest.isOfflineTest && !SQLTest.syncHAForOfflineTest) {
        Log.getLogWriter().warning("possibly hit #43754, got the following data mismatch: " 
            + aStr.toString());
        return;
      } else throw new TestException(aStr.toString());
    }

    if (firstResultSet.size() == secondResultSet.size()) {
      Log.getLogWriter().info("verified that results are correct");
    }
  }

  /**
   * convert a list of struct to string
   * @param aList -- aList of struct to be logged
   * @return the string for a list of struct
   */
  public static String listToString(List<Struct> aList) {
    if (aList == null) {
      throw new TestException ("test issue, need to check in the test and not pass in null list here");
    }
    StringBuffer aStr = new StringBuffer();
    aStr.append("The size of list is " + (aList == null ? "0" : aList.size()) + "\n");

    for (int i = 0; i < aList.size(); i++) {
      Object aStruct = aList.get(i);
      if (aStruct instanceof com.gemstone.gemfire.cache.query.Struct) {
         GFXDStructImpl si = (GFXDStructImpl)(aStruct);
         aStr.append(si.toString());
      }
      aStr.append("\n");
    }
    return aStr.toString();
  }

  private static InternalDistributedSystem getDistributedSystem() {
    try {
    	GemFireCacheImpl cache = (GemFireCacheImpl)Misc.getGemFireCache();
      return cache.getDistributedSystem();
    } catch (Exception e) {
    	Log.getLogWriter().info("could not get the distributed system");
    	Log.getLogWriter().severe(TestHelper.getStackTrace(e));
    	return null;
    }
  }

  public static String convertByteArrayToString(byte[] byteArray){
    if (byteArray == null) return "null";
       StringBuilder sb = new StringBuilder();
       sb.append('{');
       for (byte b : byteArray) {
         sb.append(b + ", ");
       }
       sb.deleteCharAt(sb.lastIndexOf(","));
       sb.append('}');
       return sb.toString();
  }

  public static String convertCharArrayToString(BufferedReader reader, int size){
  	//char[] charArray= new char[size];
    StringBuilder sb = new StringBuilder();
    int readChars;
    if (SQLPrms.isSnappyMode()) {
      try {
        while ((readChars = reader.read()) != -1) {
          sb.append((char)readChars);
        }
      } catch (Exception e) {
        throw new TestException("could not read in the charaters "
            + TestHelper.getStackTrace(e));
      }
    } else {
      sb.append('{');
      try {
        while ((readChars = reader.read()) != -1) {
          sb.append(readChars + ", ");
        }
      } catch (Exception e) {
        throw new TestException("could not read in the charaters "
            + TestHelper.getStackTrace(e));
      }

      sb.deleteCharAt(sb.lastIndexOf(","));
      sb.append('}');
    }
    return sb.toString();
  }
  
  public static String convertBufferedReaderToChecksum(BufferedReader reader, long size){
    //char[] charArray= new char[size];
    String readChars;
    //TODO as we are use check sum now, we may increase clob size
    if (size > Integer.MAX_VALUE) throw new TestException("test needs to handle large clob size");
    byte[] bytes = null;
    
    try {
      while ((readChars = reader.readLine()) != null) {   
        bytes = readChars.getBytes();
      }     
    } catch (Exception e) {
      //work around #47695 as it will not be addressed by product in near future
      if (SQLTest.isHATest && e instanceof IOException) {
        Log.getLogWriter().info("hit #47695, ignored");
        return null;
      } else 
      throw new TestException("could not read in the charaters "
          + TestHelper.getStackTrace(e));
    }
    if (bytes != null)
      return convertByteArrayToChecksum(bytes, bytes.length);
    else return null;
  }
  
  public static String convertByteArrayToChecksum(byte[] bytes, long size){
    if (size > Integer.MAX_VALUE) throw new TestException("test needs to handle large clob size");
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(bytes);         
      byte[] digest = md.digest();
      StringBuffer sb = new StringBuffer();
      for (byte b : digest) {
        sb.append(Integer.toHexString((int) (b & 0xff)));
      }
      //Log.getLogWriter().info("MD5 checksum is " + sb.toString());
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new TestException ("could not convert clob to MD5 checksum" + TestHelper.getStackTrace(e));
    }

  }
  
  public static String convertClobToChecksum(Clob clob, long size){
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(clob.getCharacterStream());
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }

    return convertBufferedReaderToChecksum(reader, size);
  }
  
  public static String convertStringToChecksum(String str, long size){
    if (size > Integer.MAX_VALUE) throw new TestException("test could not handle large string size yet");
    byte[] bytes;
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      bytes = str.getBytes();
      md.update(bytes);
      byte[] digest = md.digest();
      
      StringBuffer sb = new StringBuffer();
      for (byte b : digest) {
        sb.append(Integer.toHexString((int) (b & 0xff)));
      }
      //Log.getLogWriter().info("MD5 checksum is " + sb.toString());
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new TestException ("could not convert clob to MD5 checksum" + TestHelper.getStackTrace(e));
    }

  }

  public static boolean compareDuplicateResultSets(ResultSet derbyResultSet,
  		ResultSet GFEResultSet) {
  	Object o = SQLBB.getBB().getSharedMap().get("TxHistoryPosDup");

  	if (o != null) {
  		Log.getLogWriter().info("could not compare results in txHistory " +
  				"as duplicate row might be inserted");
  		return true;
  	}
  	else return compareResultSets(derbyResultSet, GFEResultSet);
  }
  
  class ByteClass {
    //used for comparison only
  }
  
  public static String findStringDifference(String one, String two, String first, String second) {
    int size = one.length() < two.length()? one.length() : two.length();
    for (int i =0; i< size; i++) {
      if (one.charAt(i) != two.charAt(i)) {
        return "difference starts from position " + i + " in " + first + " String is "
        + one.substring(i, (i+100 < size ? i+100 : size)) + " but in " + second + " String is " 
        + two.substring(i, (i+100 < size ? i+100 : size));
      }
        
    }
    if (one.length() != two.length()) {
      return "sizes of two strings are different " + first + " String size is " + one.length() 
      + " but the " + second + " String size is " + two.length();
    }
       
    throw new TestException("could not found difference for these two Strings");
  }
}
