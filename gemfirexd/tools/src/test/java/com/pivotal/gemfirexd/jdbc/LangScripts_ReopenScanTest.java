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
import java.sql.Statement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.derbyTesting.junit.JDBC;

import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class LangScripts_ReopenScanTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(LangScripts_ReopenScanTest.class));
  }
  
  public LangScripts_ReopenScanTest(String name) {
    super(name); 
  }

  // This test is the as-is LangScript conversion, without any partitioning clauses
  public void testLangScript_ReopenScanTestNoPartitioning() throws Exception
  {
    // This is a JUnit conversion of the Derby Lang ReopenScan.sql script
    // without any GemFireXD extensions
	  
    // Catch exceptions from illegal syntax
    // Tests still not fixed marked FIXME
	  
    // Array of SQL text to execute and sqlstates to expect
    // The first object is a String, the second is either 
    // 1) null - this means query returns no rows and throws no exceptions
    // 2) a string - this means query returns no rows and throws expected SQLSTATE
    // 3) a String array - this means query returns rows which must match (unordered) given resultset
    //       - for an empty result set, an uninitialized size [0][0] array is used
    Object[][] Script_ReopenScanUT = {
	// Test reopening scans.  A in subquery generates a lot of reopen requests to the underlying scan.
	{ "create table x (x int)", null },
	{ "create table y (x int)", null },
	{ "create table z (x int)", null },
	{ "insert into x values 1,2,3", null },
	{ "insert into y values 1,2,3", null },
	{ "insert into z values 3,2,3,2", null },
	{ "select x from y where x in (select x from x)", new String[][] { {"1"},{"2"},{"3"} } },
	{ "select x from z where x in (1,2,3)", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "select x from z where x in (select x from y where x in (select x from x))", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "select x from z where x in (select x.x from x,y where x.x=y.x)", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "delete from y", null },
	{ "insert into y values 0,1,5,2,2", null },
	{ "select x.x from x left outer join y on (y.x=x.x)", new String[][] { {"1"},{"2"},{"2"},{"3"} } },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "delete from x", null },
	{ "insert into x values 0,1,5,2,2", null },
	{ "delete from y", null },
	{ "insert into y values 1,2,3", null },
	{ "select x.x from x left outer join y on (y.x=x.x)", new String[][] { {"0"},{"1"},{"5"},{"2"},{"2"} } },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"2"},{"2"} } },
	{ "insert into z values 1,5", null },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"2"},{"2"},{"1"},{"5"} } },
	{ "delete from x", null },
	{ "delete from y", null },
	{ "delete from z", null },
	{ "insert into x values 1,2,3", null },
	{ "insert into y values 1,2,3", null },
	{ "insert into z values 3,2,666,3,2,null,2", null },
	{ "select x from z where x in (select x from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "select x from z where x in (select max(x) from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "select x from z where x in (select max(x) from x)", new String[][] { {"3"},{"3"} } },
	{ "select x from z where x in (select sum(distinct x) from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "insert into x values 1,1,2,2,2,5,5,null,6", null },
	{ "select x from z where x in (select sum(distinct x) from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "delete from x", null },
	{ "delete from y", null },
	{ "delete from z", null },
	{ "insert into x values null,2,3", null },
	{ "insert into y values 1,2,null", null },
	{ "insert into z values 3,2,666,3,2,null,2", null },
	{ "select x from z where x in (select x from x union select x from y)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "delete from x", null },
	{ "delete from y", null },
	{ "delete from z", null },
	{ "create table n (x smallint)", null },
	{ "insert into n values 1,2,3", null },
	{ "insert into x values 1,2,3", null },
	{ "select * from x where x in (select x from n)", new String[][] { {"1"},{"2"},{"3"} } },
	{ "drop table n", null },
  { "drop table x", null },
  { "drop table y", null },
  { "drop table z", null }
   };

    // Do not use partitioning as default, use replicate
    // (Some results are expected to be different with partitioning)
    skipDefaultPartitioned = true;

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,Script_ReopenScanUT);
  }
  
  // This test is the script enhanced with partitioning
  public void testLangScript_ReopenScanWithPartitioning() throws Exception
  {
    // This form of the ReopenScan.sql test has partitioning activated
    // TODO : Create specialized partitioning for these tables for ReopenScan
    //  Currently, default partitioning is active
    Object[][] Script_ReopenScanUTPartitioning = {
	// Test reopening scans.  A in subquery generates a lot of reopen requests to the underlying scan.
	{ "create table x (x int)", null },
	{ "create table y (x int)", null },
	{ "create table z (x int)", null },
	{ "insert into x values 1,2,3", null },
	{ "insert into y values 1,2,3", null },
	{ "insert into z values 3,2,3,2", null },
	{ "select x from y where x in (select x from x)", new String[][] { {"1"},{"2"},{"3"} } },
	{ "select x from z where x in (1,2,3)", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "select x from z where x in (select x from y where x in (select x from x))", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "select x from z where x in (select x.x from x,y where x.x=y.x)", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "delete from y", null },
	{ "insert into y values 0,1,5,2,2", null },
	{ "select x.x from x left outer join y on (y.x=x.x)", new String[][] { {"1"},{"2"},{"2"},{"3"} } },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"3"},{"2"},{"3"},{"2"} } },
	{ "delete from x", null },
	{ "insert into x values 0,1,5,2,2", null },
	{ "delete from y", null },
	{ "insert into y values 1,2,3", null },
	{ "select x.x from x left outer join y on (y.x=x.x)", new String[][] { {"0"},{"1"},{"5"},{"2"},{"2"} } },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"2"},{"2"} } },
	{ "insert into z values 1,5", null },
	{ "select x from z where x in (select x.x from x left outer join y on (y.x=x.x))", new String[][] { {"2"},{"2"},{"1"},{"5"} } },
	{ "delete from x", null },
	{ "delete from y", null },
	{ "delete from z", null },
	{ "insert into x values 1,2,3", null },
	{ "insert into y values 1,2,3", null },
	{ "insert into z values 3,2,666,3,2,null,2", null },
	{ "select x from z where x in (select x from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "select x from z where x in (select max(x) from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "select x from z where x in (select max(x) from x)", new String[][] { {"3"},{"3"} } },
	{ "select x from z where x in (select sum(distinct x) from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "insert into x values 1,1,2,2,2,5,5,null,6", null },
	{ "select x from z where x in (select sum(distinct x) from x group by x)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "delete from x", null },
	{ "delete from y", null },
	{ "delete from z", null },
	{ "insert into x values null,2,3", null },
	{ "insert into y values 1,2,null", null },
	{ "insert into z values 3,2,666,3,2,null,2", null },
	{ "select x from z where x in (select x from x union select x from y)", new String[][] { {"3"},{"2"},{"3"},{"2"},{"2"} } },
	{ "delete from x", null },
	{ "delete from y", null },
	{ "delete from z", null },
	{ "create table n (x smallint)", null },
	{ "insert into n values 1,2,3", null },
	{ "insert into x values 1,2,3", null },
	{ "select * from x where x in (select x from n)", new String[][] { {"1"},{"2"},{"3"} } },
	{ "drop table n", null },
  { "drop table x", null },
  { "drop table y", null },
  { "drop table z", null }
  };

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,Script_ReopenScanUTPartitioning);

  }
}
