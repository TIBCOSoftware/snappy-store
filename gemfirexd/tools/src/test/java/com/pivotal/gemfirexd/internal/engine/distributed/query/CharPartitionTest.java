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
import java.util.Properties;

import com.gemstone.gemfire.internal.AvailablePort;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class CharPartitionTest extends JdbcTestBase {

  private static volatile Connection derbyConn = null;
  
  private static volatile Connection gfxdConn = null;
  
  private ResultSet rsGfxd = null;
  
  private ResultSet rsDerby = null;
  private static int mcastPort;
  
  public CharPartitionTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(CharPartitionTest.class));
  }
  
  public static ResultSet executeQueryOnDerby(String sql) throws Exception {
    if (derbyConn == null) {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }      
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

  private void compareResultSets(ResultSet rsGfxd,
      ResultSet rsDerby) throws Exception {
    validateResults(rsDerby, rsGfxd, false);
  }

  public void testBug46046() throws Exception {
    mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    String partitionStrategy = "";
    partitionBy(partitionStrategy);
    
    partitionStrategy = " partition by column (c1)";
    partitionBy(partitionStrategy);
    
    partitionStrategy = " partition by range (c1) (values between ' ' and 'a', values between 'b' and 'c')";
    partitionBy(partitionStrategy);
    
    partitionStrategy = " partition by (c1)";
    partitionBy(partitionStrategy);
    
//    partitionStrategy = " partition by list (c1) (values ('a'), values ('b'))";
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
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();

      // create a table with char columns of different lengths

      executeUpdateOnGfxd("create table chartab (c1 char(10), c2 char(5))"
          + partitionStrategy);
      executeUpdateOnDerby("create table chartab (c1 char(10), c2 char(5))");

      // insert some values

      executeUpdateOnGfxd("insert into chartab values (' ', '     ')");
      executeUpdateOnGfxd("insert into chartab values ('a', 'a    ')");
      executeUpdateOnGfxd("insert into chartab values ('b', 'bcdef')");
      executeUpdateOnGfxd("insert into chartab values (null, null)");

      executeUpdateOnDerby("insert into chartab values (' ', '     ')");
      executeUpdateOnDerby("insert into chartab values ('a', 'a    ')");
      executeUpdateOnDerby("insert into chartab values ('b', 'bcdef')");
      executeUpdateOnDerby("insert into chartab values (null, null)");

      // select each one in turn

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = ' '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = '     '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = '     '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = 'a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = 'a'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = 'a    '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = 'a    '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = 'b'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = 'b'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = 'bcdef'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = 'bcdef'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = '                      '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = '                      '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = ''");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = ' '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = '                           '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = '                           '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = 'a        '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = 'a        '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = 'a '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = 'a '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = 'b             '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = 'b             '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = 'bcdef                '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = 'bcdef                '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = 'bcde       '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = 'bcde       '");
      compareResultSets(rsGfxd, rsDerby);

      // now check null = null semantics

      rsGfxd = executeQueryOnGfxd("select c1, c2 from chartab where c1 = c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from chartab where c1 = c2");
      compareResultSets(rsGfxd, rsDerby);

      // test is null semantics
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 is null");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 is null");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 is not null");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 is not null");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where not c1 is null");
      rsDerby = executeQueryOnDerby("select c1 from chartab where not c1 is null");
      compareResultSets(rsGfxd, rsDerby);

      // Now test <>
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <> ' '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <> ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> '     '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> '     '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <> 'a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <> 'a'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> 'a    '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> 'a    '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <> 'b'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <> 'b'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> 'bcdef'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> 'bcdef'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 != ' '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 != ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 != '     '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 != '     '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 != 'a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 != 'a'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 != 'a    '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 != 'a    '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 != 'b'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 != 'b'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 != 'bcdef'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 != 'bcdef'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <> ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <> ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <> '                      '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <> '                      '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> ''");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> ' '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> '                           '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> '                           '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <> 'a        '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <> 'a        '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> 'a '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> 'a '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <> 'b             '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <> 'b             '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> 'bcdef                '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> 'bcdef                '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <> 'bcde       '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <> 'bcde       '");
      compareResultSets(rsGfxd, rsDerby);

      // now check null <> null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from chartab where c1 <> c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from chartab where c1 <> c2");
      compareResultSets(rsGfxd, rsDerby);

      // Now test <
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 < ' '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 < ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < '     '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < '     '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 < 'a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 < 'a'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < 'a    '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < 'a    '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 < 'b'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 < 'b'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < 'bcdef'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < 'bcdef'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 < ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 < ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 < '                      '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 < '                      '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < ''");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < ' '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < '                           '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < '                           '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 < 'a        '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 < 'a        '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < 'a '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < 'a '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 < 'b             '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 < 'b             '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < 'bcdef                '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < 'bcdef                '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 < 'bcde       '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 < 'bcde       '");
      compareResultSets(rsGfxd, rsDerby);

      // now check null < null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from chartab where c1 < c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from chartab where c1 < c2");
      compareResultSets(rsGfxd, rsDerby);

      // Now test >
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > ' '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > '     '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > '     '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > 'a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > 'a'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > 'a    '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > 'a    '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > 'b'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > 'b'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > 'bcdef'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > 'bcdef'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > '                      '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > '                      '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > ''");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > ' '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > '                           '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > '                           '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > 'a        '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > 'a        '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > 'a '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > 'a '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 > 'b             '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 > 'b             '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > 'bcdef                '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > 'bcdef                '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 > 'bcde       '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 > 'bcde       '");
      compareResultSets(rsGfxd, rsDerby);

      // now check null > null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from chartab where c1 > c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from chartab where c1 > c2");
      compareResultSets(rsGfxd, rsDerby);

      // Now test <=
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <= ' '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <= ' '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= '     '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= '     '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <= 'a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <= 'a'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= 'a    '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= 'a    '");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <= 'b'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <= 'b'");
      compareResultSets(rsGfxd, rsDerby);

      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= 'bcdef'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= 'bcdef'");
      compareResultSets(rsGfxd, rsDerby);

      // now check for end-of-string blank semantics
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <= ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <= ''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <= '                      '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <= '                      '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= ''");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= ''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= ' '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= ' '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= '                           '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= '                           '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <= 'a        '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <= 'a        '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= 'a '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= 'a '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 <= 'b             '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 <= 'b             '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= 'bcdef                '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= 'bcdef                '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 <= 'bcde       '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 <= 'bcde       '");
      compareResultSets(rsGfxd, rsDerby);
      
      // now check null <= null semantics
      
      rsGfxd = executeQueryOnGfxd("select c1, c2 from chartab where c1 <= c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from chartab where c1 <= c2");
      compareResultSets(rsGfxd, rsDerby);
      
      // Now test >=
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 >= ' '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 >= ' '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= '     '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= '     '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 >= 'a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 >= 'a'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= 'a    '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= 'a    '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 >= 'b'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 >= 'b'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= 'bcdef'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= 'bcdef'");
      compareResultSets(rsGfxd, rsDerby);
      
      // now check for end-of-string blank semantics
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 >= ''");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 >= ''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 >= '                      '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 >= '                      '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= ''");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= ''");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= ' '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= ' '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= '                           '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= '                           '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 >= 'a        '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 >= 'a        '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= 'a '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= 'a '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 >= 'b             '");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 >= 'b             '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= 'bcdef                '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= 'bcdef                '");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 >= 'bcde       '");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 >= 'bcde       '");
      compareResultSets(rsGfxd, rsDerby);
      
      // now check null >= null semantics
      rsGfxd = executeQueryOnGfxd("select c1, c2 from chartab where c1 >= c2");
      rsDerby = executeQueryOnDerby("select c1, c2 from chartab where c1 >= c2");
      compareResultSets(rsGfxd, rsDerby);
      
      //check #46490
      rsGfxd = executeQueryOnGfxd("select c2 from chartab where c2 = 'bcdeff'");
      rsDerby = executeQueryOnDerby("select c2 from chartab where c2 = 'bcdeff'");
      compareResultSets(rsGfxd, rsDerby);
      
      rsGfxd = executeQueryOnGfxd("select c1 from chartab where c1 = 'a         a'");
      rsDerby = executeQueryOnDerby("select c1 from chartab where c1 = 'a         a'");
      compareResultSets(rsGfxd, rsDerby);
      
    } finally {      
      if (rsGfxd != null) {
        rsGfxd.close();
      }
      if (rsDerby != null) {
        rsDerby.close();       
      }
      
      
      executeUpdateOnGfxd("drop table chartab");
      executeUpdateOnDerby("drop table chartab");
    }

  }
}
