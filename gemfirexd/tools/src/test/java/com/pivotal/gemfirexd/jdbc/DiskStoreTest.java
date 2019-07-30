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
package com.pivotal.gemfirexd.jdbc;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;

import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class DiskStoreTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(CreateTableOtherAttributesTest.class));
  }

  public DiskStoreTest(String name) {
    super(name);
  }

  public void testDiskStoreCreation() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    File f1 = new File("dir1");
    f1.mkdir();
    File f2 = new File("dir2");
    f2.mkdir();
    File f3 = new File("dir3");
    f3.mkdir();
    s.execute("create DiskStore testDiskStore maxlogsize 128 autocompact true " +
    " allowforcecompaction false compactionthreshold 80 TimeInterval 223344 " +
    "Writebuffersize 19292393 queuesize 17374  ('dir1' 3456, 'dir2', 'dir3' 55556 )");
    DiskStore ds = Misc.getGemFireCache().findDiskStore("TESTDISKSTORE");
    assertNotNull(ds);
    cleanUpDirs(new File[]{f1,f2,f3});
    s.execute("create DiskStore testDiskStore1"); 
    ds = Misc.getGemFireCache().findDiskStore("TESTDISKSTORE1");
    assertNotNull(ds);   
  }

  public void testTableDropBehaviour() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    //String schema = TestUtil.getCurrentDefaultSchemaName();
    try {
      s.execute("create diskstore store1");
      s.execute("create table t1 (c1 int , c2 int, c3 varchar(20), "
          + "PRIMARY KEY (c1, c2)) persistent 'store1'");

      s.execute("insert into t1 (c1, c2, c3) values (10, 15, 'YYYY')");
      s.execute("insert into t1 (c1, c2, c3) values (20, 25, 'XXXX')");
      s.execute("insert into t1 (c1, c2, c3) values (30, 35, 'ZZZZ')");
      s.execute("drop table t1");
      s.execute("create table t1 (c1 int , c2 int, c3 varchar(20), "
          + "PRIMARY KEY (c1, c2)) persistent 'store1'");

      s.execute("insert into t1 (c1, c2, c3) values (10, 15, 'YYYY')");
      s.execute("insert into t1 (c1, c2, c3) values (20, 25, 'XXXX')");
      s.execute("insert into t1 (c1, c2, c3) values (30, 35, 'ZZZZ')");

    } finally {
      try {
        s.execute("drop table t1");
      } catch (SQLException ignore) {
      }
      conn.close();
    }
  }

  public void testOverflow_43122() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create diskstore OverflowDiskStore 'overflow'");
    s.execute("create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
        + "'lse', 'fse', 'hkse', 'tse'))) eviction by lrucount 2 "
        + "evictaction overflow asynchronous 'OverflowDiskStore'");

    s.execute("insert into trade.securities (sec_id, symbol, exchange, tid) "
        + "values (1, 'SYM1', 'nasdaq', 2)");
    s.execute("insert into trade.securities (sec_id, symbol, exchange, tid) "
        + "values (2, 'SYM2', 'tse', 4)");
    s.execute("insert into trade.securities (sec_id, symbol, exchange, tid) "
        + "values (3, 'SYM3', 'hkse', 3)");

    s.execute("drop table trade.securities");
    conn.close();
  }
  
  public void testOverflowWithPersistence() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create diskstore OverflowDiskStore 'overflow'");
    s.execute("create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
        + "'lse', 'fse', 'hkse', 'tse'))) eviction by lrucount 2 "
        + "evictaction overflow persistent asynchronous 'OverflowDiskStore'");
    LocalRegion region = (LocalRegion)Misc.getRegion("TRADE/SECURITIES", true, false);
    RegionAttributes<?, ?> ra = region.getAttributes();
    assertTrue(ra.getDataPolicy().withPersistence());
    assertEquals(ra.getEvictionAttributes().getAction(),EvictionAction.OVERFLOW_TO_DISK);
    s.execute("insert into trade.securities (sec_id, symbol, exchange, tid) "
        + "values (1, 'SYM1', 'nasdaq', 2)");
    s.execute("insert into trade.securities (sec_id, symbol, exchange, tid) "
        + "values (2, 'SYM2', 'tse', 4)");
    s.execute("insert into trade.securities (sec_id, symbol, exchange, tid) "
        + "values (3, 'SYM3', 'hkse', 3)");
    TestUtil.shutDown();
    conn = getConnection();
    s = conn.createStatement();
    ResultSet rs = s.executeQuery("select * from trade.securities");
    int numRows =0;
    while(rs.next()) {
      ++numRows;
    }
    assertEquals(numRows,3);
    s.execute("drop table trade.securities");
    conn.close();
  }
  
  
  public void testOverflowWithPersistenceIncorrectSyntax() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create diskstore OverflowDiskStore 'overflow'");
    s.execute("create diskstore PersistentDiskStore 'overflow'");
    try {
    s.execute("create table trade.securities (sec_id int not null, "
        + "symbol varchar(10) not null, price decimal (30, 20), "
        + "exchange varchar(10) not null, tid int, constraint sec_pk "
        + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
        + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
        + "'lse', 'fse', 'hkse', 'tse'))) eviction by lrucount 2 "
        + "evictaction overflow 'OverflowDiskStore' synchronous  persistent 'PersistentDiskStore' ");
    fail("table creation should have failed");
    }catch(SQLSyntaxErrorException sqle) {      
      //ok;
      assertTrue(sqle.getLocalizedMessage().indexOf("OverflowDiskStore") != -1);
    }    
    
    try {
      s.execute("create table trade.securities (sec_id int not null, "
          + "symbol varchar(10) not null, price decimal (30, 20), "
          + "exchange varchar(10) not null, tid int, constraint sec_pk "
          + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
          + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
          + "'lse', 'fse', 'hkse', 'tse'))) persistent 'PersistentDiskStore'  eviction by lrucount 2 "
          + "   evictaction overflow   'OverflowDiskStore' synchronous ");
      fail("table creation should have failed");
      }catch(SQLSyntaxErrorException sqle) {      
        assertTrue(sqle.getLocalizedMessage().indexOf("PersistentDiskStore") != -1);
        //ok;
      }    
      
      try {
        s.execute("create table trade.securities (sec_id int not null, "
            + "symbol varchar(10) not null, price decimal (30, 20), "
            + "exchange varchar(10) not null, tid int, constraint sec_pk "
            + "primary key (sec_id), constraint sec_uq unique (symbol, exchange), "
            + "constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', "
            + "'lse', 'fse', 'hkse', 'tse'))) persistent 'PersistentDiskStore'  eviction by lrucount 2 "
            + "   evictaction overflow   synchronous   asynchronous ");
        fail("table creation should have failed");
        }catch(SQLSyntaxErrorException sqle) {   
          sqle.printStackTrace();
          assertTrue(sqle.getLocalizedMessage().indexOf("Synchronous") != -1);
          //ok;
        }    
    
    conn.close();
  }
  
}
