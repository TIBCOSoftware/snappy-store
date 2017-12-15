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
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.apache.derbyTesting.junit.JDBC;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;

@SuppressWarnings("serial")
public class TruncateTableDUnit extends DistributedSQLTestBase
{

  public TruncateTableDUnit(String name) {
    super(name);
  }

  public void testBug45094_replicate() throws Exception {

    // start some servers
    startVMs(1, 2, 0, null, null);

    // Start network server on the VMs
    final int netPort1 = startNetworkServer(1, null, null);
    startNetworkServer(2, null, null);

    // Use this VM as the network client
    TestUtil.loadNetDriver();

    String url = TestUtil.getNetProtocol("localhost", netPort1);
    Connection conn = DriverManager.getConnection(url);
    Statement st = conn.createStatement();
    st.execute("create schema test");
    st.execute("create table test.test_table (first_column int not null, "
        + "second_column int not null) replicate");
    st.execute("create table test.test_table2 (first_column int not null, "
        + "second_column int not null) replicate persistent");
    st.execute("create unique index test.first_index on test.test_table "
        + "(first_column)");
    st.execute("create index test.second_index on test.test_table "
        + "(second_column)");
    st.execute("create unique index test.first_index2 on test.test_table2 "
        + "(first_column)");
    st.execute("create index test.second_index2 on test.test_table2 "
        + "(second_column)");

    // populate tables
    PreparedStatement ps = conn
        .prepareStatement("insert into test.test_table values (?, ?)");
    for (int i = 0; i < 10; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 10);
      ps.execute();
    }
    ps = conn.prepareStatement("insert into test.test_table2 values (?, ?)");
    for (int i = 0; i < 10; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 10);
      ps.execute();
    }

    // verify the number of inserted rows
    ps = conn.prepareStatement("select count(*) from test.test_table");
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals(10, rs.getInt(1));
    assertFalse(rs.next());
    ps = conn.prepareStatement("select count(*) from test.test_table2");
    rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals(10, rs.getInt(1));
    assertFalse(rs.next());

    for (int vm = 1; vm <= 2; vm++) {
      if (vm == 2) {
        // restart VMs and use the peer client connection
        stopAllVMs();
        restartVMNums(-1, -2);
        restartVMNums(1);

        conn = TestUtil.getConnection();
        st = conn.createStatement();

        // verify the number of inserted rows
        ps = conn.prepareStatement("select count(*) from test.test_table");
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
        assertFalse(rs.next());
        rs.close();
        ps = conn.prepareStatement("select count(*) from test.test_table2");
        rs = ps.executeQuery();
        assertTrue(rs.next());
        assertEquals(20, rs.getInt(1));
        assertFalse(rs.next());
        rs.close();
      }

      // truncate tables
      ps = conn.prepareStatement("truncate table test.test_table");
      ps.execute();
      ps = conn.prepareStatement("truncate table test.test_table2");
      ps.execute();

      // verify the tables are empty
      ps = conn.prepareStatement("select count(*) from test.test_table");
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());
      ps = conn.prepareStatement("select count(*) from test.test_table2");
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
      assertFalse(rs.next());

      // query using unique index
      ps = conn.prepareStatement("select * from test.test_table "
          + "where first_column = ?");
      ps.setInt(1, 7);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      ps = conn.prepareStatement("select * from test.test_table2 "
          + "where first_column = ?");
      ps.setInt(1, 7);
      rs = ps.executeQuery();
      assertFalse(rs.next());

      // query using index
      ps = conn.prepareStatement("select * from test.test_table "
          + "where second_column = ?");
      ps.setInt(1, 12);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      ps = conn.prepareStatement("select * from test.test_table2 "
          + "where second_column = ?");
      ps.setInt(1, 12);
      rs = ps.executeQuery();
      assertFalse(rs.next());

      // check consistency
      rs = st.executeQuery("values SYSCS_UTIL.CHECK_TABLE("
          + "'TEST', 'TEST_TABLE')");
      JDBC.assertSingleValueResultSet(rs, "1");
      rs.close();
      rs = st.executeQuery("values SYSCS_UTIL.CHECK_TABLE("
          + "'TEST', 'TEST_TABLE2')");
      JDBC.assertSingleValueResultSet(rs, "1");
      rs.close();

      // Insert rows after truncate table
      ps = conn.prepareStatement("insert into test.test_table values (?, ?)");
      for (int i = 0; i < 20; i++) {
        ps.setInt(1, i + 100);
        ps.setInt(2, i + 1000);
        ps.execute();
      }
      ps = conn.prepareStatement("insert into test.test_table2 values (?, ?)");
      for (int i = 0; i < 20; i++) {
        ps.setInt(1, i + 100);
        ps.setInt(2, i + 1000);
        ps.execute();
      }

      // verify the number of inserted rows
      ps = conn.prepareStatement("select count(*) from test.test_table");
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(20, rs.getInt(1));
      assertFalse(rs.next());
      ps = conn.prepareStatement("select count(*) from test.test_table2");
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(20, rs.getInt(1));
      assertFalse(rs.next());

      // query using unique index
      ps = conn.prepareStatement("select * from test.test_table "
          + "where first_column = ?");
      ps.setInt(1, 7);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      ps.setInt(1, 107);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(107, rs.getInt(1));
      assertEquals(1007, rs.getInt(2));
      assertFalse(rs.next());
      ps = conn.prepareStatement("select * from test.test_table2 "
          + "where first_column = ?");
      ps.setInt(1, 7);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      ps.setInt(1, 107);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(107, rs.getInt(1));
      assertEquals(1007, rs.getInt(2));
      assertFalse(rs.next());

      // query using index
      ps = conn.prepareStatement("select * from test.test_table "
          + "where second_column = ?");
      ps.setInt(1, 12);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      ps.setInt(1, 1012);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(112, rs.getInt(1));
      assertEquals(1012, rs.getInt(2));
      assertFalse(rs.next());
      ps = conn.prepareStatement("select * from test.test_table2 "
          + "where second_column = ?");
      ps.setInt(1, 12);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      ps.setInt(1, 1012);
      rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals(112, rs.getInt(1));
      assertEquals(1012, rs.getInt(2));
      assertFalse(rs.next());

      conn.close();
    }
  }

  public void testBug45094_partitioned() throws Exception {
      // start some servers
      startVMs(0, 2, 0, null, null);

      // Start network server on the VMs
      final int netPort1 = startNetworkServer(1, null, null);
      startNetworkServer(2, null, null);

      // Use this VM as the network client
      TestUtil.loadNetDriver();

      String url = TestUtil.getNetProtocol("localhost", netPort1);
      Connection conn = DriverManager.getConnection(url);
      Statement st = conn.createStatement();         
      st.execute("create schema test");
      st.execute("create table test.test_table (first_column int not null, second_column int not null) partition by (first_column)");
      st.execute("create unique index test.first_index on test.test_table (first_column)");
      st.execute("create index test.second_index on test.test_table (second_column)");
        
      //populate table
      PreparedStatement ps = conn.prepareStatement("insert into test.test_table values (?, ?)");
      for (int i = 0; i < 10; i++) {
      	ps.setInt(1, i);
      	ps.setInt(2, i + 10);
      	ps.execute();
      }

      //verify the number of inserted rows
      ps = conn.prepareStatement("select count(*) from test.test_table");
      ResultSet rs = ps.executeQuery();      
      rs.next();					
		assertEquals(10, rs.getInt(1));
		
		//truncate table
      ps = conn.prepareStatement("truncate table test.test_table");
      ps.execute();
      
      //verify the table is empty
      ps = conn.prepareStatement("select count(*) from test.test_table");
      rs = ps.executeQuery();      
      rs.next();			
	  assertEquals(0, rs.getInt(1));

		//query using unique index
      ps = conn.prepareStatement("select * from test.test_table where first_column = ?");
      ps.setInt(1, 7);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      
      //query using index
      ps = conn.prepareStatement("select * from test.test_table where second_column = ?");
      ps.setInt(1, 12);
      rs = ps.executeQuery();
      assertFalse(rs.next());
      
      //check consistency
	  rs =st.executeQuery("values SYSCS_UTIL.CHECK_TABLE('TEST', 'TEST_TABLE')");
	  JDBC.assertSingleValueResultSet(rs, "1");
		
	  //Insert rows after truncate table
	  ps = conn.prepareStatement("insert into test.test_table values (?, ?)");
      for (int i = 0; i < 20; i++) {
      	ps.setInt(1, i + 100);
      	ps.setInt(2, i + 1000);
      	ps.execute();
      }

      //verify the number of inserted rows
      ps = conn.prepareStatement("select count(*) from test.test_table");
      rs = ps.executeQuery();        
      rs.next();			
	  assertEquals(20, rs.getInt(1));
  }     
 
}
