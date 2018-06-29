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
package com.pivotal.gemfirexd.internal.engine.distributed.query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Arrays;
import java.util.Properties;
import java.util.Vector;

import com.gemstone.gemfire.internal.AvailablePort;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import io.snappydata.test.util.TestException;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class CharForBitDataPartitionTest extends JdbcTestBase {

  private static volatile Connection derbyConn = null;
  
  private static volatile Connection gfxdConn = null;
  
  private ResultSet rsGfxd = null;
  
  private ResultSet rsDerby = null;
  private volatile static int mcastPort;
  
  public CharForBitDataPartitionTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(CharForBitDataPartitionTest.class));
  }

  public static ResultSet executeQueryOnDerby(String sql) throws Exception {
    if (derbyConn == null) {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      // for some reason auto-load of Derby driver fails occasionally in full suite
      loadDerbyDriver();
      derbyConn = DriverManager.getConnection(derbyDbUrl);
    }
    ResultSet rs = null;
    rs = derbyConn.createStatement().executeQuery(sql);
    return rs;
  }

  public static int executeUpdateOnDerby(String sql) throws Exception {
    if (derbyConn == null) {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      // for some reason auto-load of Derby driver fails occasionally in full suite
      loadDerbyDriver();
      derbyConn = DriverManager.getConnection(derbyDbUrl);
    }
    int ret = 0;
    ret = derbyConn.createStatement().executeUpdate(sql);
    return ret;
  }  
  
  public static ResultSet executeQueryOnGfxd(String sql) throws Exception {
    if (gfxdConn == null) {
      Properties props = new Properties();
      props.put("mcast-port", String.valueOf(mcastPort));
      gfxdConn = TestUtil.getConnection(props);
    }
    ResultSet rs = null;
    rs = gfxdConn.createStatement().executeQuery(sql);
    return rs;
  }

  public static int executeUpdateOnGfxd(String sql) throws Exception {
    if (gfxdConn == null) {
      Properties props = new Properties();
      props.put("mcast-port", String.valueOf(mcastPort));
      gfxdConn = TestUtil.getConnection(props);
    }
    int ret = 0;
    ret = gfxdConn.createStatement().executeUpdate(sql);
    return ret;
  }  
 
  private Vector<byte[]> convertToVector(ResultSet rs) throws Exception {
    Vector<byte[]> v = new Vector<byte[]>();
    ResultSetMetaData rsmd = rs.getMetaData();
    while (rs.next()) {
      for (int i = 1; i <= rsmd.getColumnCount(); i++) {
        v.add(rs.getBytes(i));
      }
    }

    return v;
  }

  private String vectorToString(Vector<byte[]> v) {
    StringBuffer aStr = new StringBuffer();
    aStr.append("The size of list is " + v.size() + "\n");
    for (int i = 0; i < v.size(); i++) {
      byte[] aStruct = v.get(i);
      for (int j = 0; j < aStruct.length; j++) {
        aStr.append(aStruct[j]);
      }
      aStr.append("\n");
    }
    return aStr.toString();
  }

  private int findInVector(Vector<byte[]> v, byte[] b) {
    for (int i = 0; i < v.size(); i++) {
      if (Arrays.equals(v.get(i), b)) {
        return i;
      }
    }
    return -1;
  }

  private void compareResultSets(ResultSet rsGfxd, ResultSet rsDerby)
      throws Exception {
    Vector<byte[]> gfxdV = convertToVector(rsGfxd);
    Vector<byte[]> derbyV = convertToVector(rsDerby);

    Vector<byte[]> derbyVCopy = new Vector<byte[]>(derbyV);
    StringBuffer aStr = new StringBuffer();
    for (int i = 0; i < gfxdV.size(); i++) {
      int idx = findInVector(derbyVCopy, gfxdV.get(i));
      if (idx != -1) {
        derbyVCopy.remove(idx);
      }
    }
    Vector<byte[]> missing = derbyVCopy;

    if (gfxdV.size() != derbyV.size() || missing.size() > 0) {
      Vector<byte[]> gfxdVCopy = new Vector<byte[]>(gfxdV);
      for (int i = 0; i < derbyV.size(); i++) {
        int idx = findInVector(gfxdVCopy, derbyV.get(i));
        if (idx != -1) {
          gfxdVCopy.remove(idx);
        }
      }
      Vector<byte[]> unexpected = gfxdVCopy;

      if (unexpected.size() > 0) {
        aStr.append("the following " + unexpected.size()
            + " elements are unexpected from GemFireXD: "
            + vectorToString(unexpected));
      }
    }

    if (missing.size() > 0) {
      aStr.append("the following " + missing.size()
          + " elements are missing in GemFireXD: " + vectorToString(missing));
    }

    if (aStr.length() != 0) {
      logger.info(
          "ResultSet from GemFireXD is " + vectorToString(gfxdV));
      logger.info(
          "ResultSet from Derby is " + vectorToString(derbyV));
      logger.info("ResultSet difference is " + aStr.toString());
      throw new TestException(aStr.toString());

    }

    if (gfxdV.size() == derbyV.size()) {
      logger.info("verified that results are correct");
    }
    else if (gfxdV.size() < derbyV.size()) {
      throw new TestException("There are more data in Derby ResultSet");
    }
    else {
      throw new TestException("There are fewer data in Derby ResultSet");
    }

    rsGfxd.close();
    rsDerby.close();
  }
  
  public void testBug46046() throws Exception {
    mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    String partitionStrategy = "";
    partitionBy(partitionStrategy);
    
//    partitionStrategy = " partition by column (c1)";
//    partitionBy(partitionStrategy);
//    
//    partitionStrategy = " partition by range (c1) (values between x'20' and x'41', values between x'42' and x'43')";
//    partitionBy(partitionStrategy);
//    
//    partitionStrategy = " partition by (c1)";
//    partitionBy(partitionStrategy);
//    
//    partitionStrategy = " partition by list (c1) (values (x'41'), values (x'42'))";
//    partitionBy(partitionStrategy);
    
    if (gfxdConn != null) {
      gfxdConn.close();
    }
    
    if (derbyConn != null) {
      derbyConn.close();
    }
  }
  
  private void partitionBy(String partitionStrategy) throws Exception {

    try {


      // create a table with char columns of different lengths

      executeUpdateOnGfxd("create table charfbdtab (c1 char(10) for bit data, c2 char(5) for bit data)"
          + partitionStrategy);
      executeUpdateOnDerby("create table charfbdtab (c1 char(10) for bit data, c2 char(5) for bit data)");

      // insert some values

      executeUpdateOnGfxd("insert into charfbdtab values (x'20', x'2020202020')");
      executeUpdateOnGfxd("insert into charfbdtab values (x'41', x'4120202020')");
      executeUpdateOnGfxd("insert into charfbdtab values (x'42', x'4243444546')");
      executeUpdateOnGfxd("insert into charfbdtab values (null, null)");

      executeUpdateOnDerby("insert into charfbdtab values (x'20', x'2020202020')");
      executeUpdateOnDerby("insert into charfbdtab values (x'41', x'4120202020')");
      executeUpdateOnDerby("insert into charfbdtab values (x'42', x'4243444546')");
      executeUpdateOnDerby("insert into charfbdtab values (null, null)");

      // select each one in turn

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x'20'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'2020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'2020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x'41'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x'41'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'4120202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'4120202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x'42'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x'42'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'4243444546'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'4243444546'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x'202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x'202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x''");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'20'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x'412020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x'412020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'4220'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'4220'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x'4220202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x'4220202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'4243444546202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'4243444546202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'4243444520202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'4243444520202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      // now check null = null semantics

      rsGfxd = executeQueryOnGfxd("select c1, c2 from charfbdtab where c1 = c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from charfbdtab where c1 = c2");
      compareResultSets(rsGfxd, rsDerby);

      // test is null semantics
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 is null");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 is null");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 is not null");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 is not null");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where not c1 is null");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where not c1 is null");
      compareResultSets(rsGfxd, rsDerby);

      // Now test <>
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <> x'20'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <> x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'2020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'2020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <> x'41'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <> x'41'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'4120202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'4120202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <> x'42'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <> x'42'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'4243444546'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'4243444546'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 != x'20'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 != x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 != x'2020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 != x'2020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 != x'41'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 != x'41'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 != x'4120202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 != x'4120202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 != x'42'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 != x'42'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 != x'4243444546'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 != x'4243444546'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <> x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <> x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <> x'20202020202020202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <> x'20202020202020202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x''");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'20'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'20202020202020202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'20202020202020202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <> x'412020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <> x'412020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'4120'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'4120'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <> x'422020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <> x'422020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'424344454620202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'424344454620202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <> x'4243444520202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <> x'4243444520202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      // now check null <> null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from charfbdtab where c1 <> c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from charfbdtab where c1 <> c2");
      compareResultSets(rsGfxd, rsDerby);

      // Now test <
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 < x'20'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 < x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'2020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'2020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 < x'41'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 < x'41'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'4120202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'4120202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 < x'42'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 < x'42'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'4243444546'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'4243444546'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 < x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 < x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 < x'2020202020202020202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 < x'2020202020202020202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x''");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'20'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'2020202020202020202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'2020202020202020202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 < x'412020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 < x'412020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'4120'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'4120'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 < x'422020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 < x'422020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'424344454620202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'424344454620202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 < x'42434445202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 < x'42434445202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      // now check null < null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from charfbdtab where c1 < c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from charfbdtab where c1 < c2");
      compareResultSets(rsGfxd, rsDerby);

      // Now test >
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x'20'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'2020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'2020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x'41'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x'41'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'4120202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'4120202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x'42'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x'42'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'4243444546'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'4243444546'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x'20202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x'20202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'20'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'20202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'20202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x'41202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x'41202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'4120'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'4120'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 > x'422020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 > x'422020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'42434445462020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'42434445462020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 > x'4243444520202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 > x'4243444520202020202020'");
      compareResultSets(rsGfxd, rsDerby);

      // now check null > null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from charfbdtab where c1 > c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from charfbdtab where c1 > c2");
      compareResultSets(rsGfxd, rsDerby);

      // Now test <=
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <= x'20'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <= x'20'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'2020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'2020202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <= x'41'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <= x'41'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'4120202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'4120202020'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <= x'42'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <= x'42'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'4243444546'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'4243444546'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <= x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <= x''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <= x'20202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <= x'20202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x''");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'20'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'20'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'20202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'20202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <= x'412020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <= x'412020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'4120'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'4120'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 <= x'4220202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 <= x'4220202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'424344454620202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'424344454620202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 <= x'4243444520202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 <= x'4243444520202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      // now check null <= null semantics
      
      rsGfxd = executeQueryOnGfxd("select c1, c2 from charfbdtab where c1 <= c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from charfbdtab where c1 <= c2");
      compareResultSets(rsGfxd, rsDerby);
      
      // Now test >=
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 >= x'20'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 >= x'20'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'2020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'2020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 >= x'41'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 >= x'41'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'4120202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'4120202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 >= x'42'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 >= x'42'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'4243444546'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'4243444546'");
      compareResultSets(rsGfxd, rsDerby);
      
      // now check for end-of-string blank semantics
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 >= x''");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 >= x''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 >= x'20202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 >= x'20202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x''");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'20'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'20'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'20202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'20202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 >= x'412020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 >= x'412020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'4120'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'4120'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 >= x'422020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 >= x'422020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'42434445462020202020202020202020202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'42434445462020202020202020202020202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 >= x'4243444520202020202020'");
      rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 >= x'4243444520202020202020'");
      compareResultSets(rsGfxd, rsDerby);
      
      // now check null >= null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from charfbdtab where c1 >= c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from charfbdtab where c1 >= c2");
      compareResultSets(rsGfxd, rsDerby);
      
      //check #46490
    rsGfxd = executeQueryOnGfxd("select c2 from charfbdtab where c2 = x'424344454646'");
    rsDerby = executeQueryOnDerby("select c2 from charfbdtab where c2 = x'424344454646'");
    compareResultSets(rsGfxd, rsDerby);
    
    rsGfxd = executeQueryOnGfxd("select c1 from charfbdtab where c1 = x'412020202020202041'");
    rsDerby = executeQueryOnDerby("select c1 from charfbdtab where c1 = x'412020202020202041'");
    compareResultSets(rsGfxd, rsDerby);
      
    } finally {      
      if (rsGfxd != null) {
        rsGfxd.close();
      }
      if (rsDerby != null) {
        rsDerby.close();       
      }
      
      
      executeUpdateOnGfxd("drop table charfbdtab");
      executeUpdateOnDerby("drop table charfbdtab");
    }

  }
}
