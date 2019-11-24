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

public class LangScripts_OrderbyTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(LangScripts_OrderbyTest.class));
  }
  
  public LangScripts_OrderbyTest(String name) {
    super(name); 
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  // This test is the as-is LangScript conversion, without any partitioning clauses
  public void testLangScript_OrderbyTestNoPartitioning() throws Exception
  {
    // This is a JUnit conversion of the Derby Lang Orderby.sql script
    // without any GemFireXD extensions
	  
    // Catch exceptions from illegal syntax
    // Tests still not fixed marked FIXME
	  
    // Array of SQL text to execute and sqlstates to expect
    // The first object is a String, the second is either 
    // 1) null - this means query returns no rows and throws no exceptions
    // 2) a string - this means query returns no rows and throws expected SQLSTATE
    // 3) a String array - this means query returns rows which must match (unordered) given resultset
    //       - for an empty result set, an uninitialized size [0][0] array is used
    Object[][] Script_OrderbyUT = {
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 1,2,3", new String[][] {
		{"0","0","1"},{"0","1","0"},{"1","0","0"},{"1","0","1"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 1,3", new String[][] {
		{"0","1","0"},{"0","0","1"},{"1","0","0"},{"1","0","1"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 2,1", new String[][] {
		{"0","0","1"},{"1","0","0"},{"1","0","1"},{"0","1","0"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 2", new String[][] {
		{"0","0","1"},{"1","0","0"},{"1","0","1"},{"0","1","0"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 0", "42X77" },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 4", "42X77" },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0)", new String[][] {
		{"0","0","1"},{"0","1","0"},{"1","0","0"},{"1","0","1"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by SQLCol1", "42X78"},
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 1,1,2,3", new String[][] {
		{"0","0","1"},{"0","1","0"},{"1","0","0"},{"1","0","1"} } },
	{ "create table obt (i int, v varchar(40))" + getOffHeapSuffix(), null },
	{ "insert into obt (i) values (null)", null },
	{ "insert into obt values (1, 'hello')", null },
	{ "insert into obt values (2, 'planet')", null },
	{ "insert into obt values (1, 'world')", null },
	{ "insert into obt values (3, 'hello')", null },
	{ "select * from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by v", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select * from obt order by i,v", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by v,i", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select * from obt order by v desc, i asc", new String[][] {
		{null,null},{"1","world"},{"2","planet"},{"1","hello"},{"3","hello"} } },
	{ "select * from obt order by i asc, v desc", new String[][] {
		{"1","world"},{"1","hello"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by i asc, i desc", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by i, v, i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select v from obt order by i, v, i", new String[][] { {"hello"},{"world"},{"planet"},{"hello"},{null} } },
	{ "select v from obt order by i desc, v, i", new String[][] { {null},{"hello"},{"planet"},{"hello"},{"world"} } },
	{ "select * from obt order by 1, 0", "42X77" },
	{ "select * from obt order by 1,2,3,4,5,6,7,8,9", "42X77" },
	{ "select * from obt order by 32767", "42X77" },
	{ "create table obt2 (i2 int, v varchar(40))"+ getOffHeapSuffix(), null },
	{ "insert into obt2 values (3, 'hello'), (4, 'planet'), (1, 'shoe'), (3, 'planet')", null },
	//FIXME
	// Union all queries here throw 'visibleSize must match' - known bug
	//{ "select * from obt union all select * from obt2 order by v", new String[][] {
	//	{"3","hello"},{"3","hello"},{"1","hello"},
	//	{"3","planet"},{"4","planet"},{"2","planet"},
	//	{"1","shoe"},{"1","world"},{null,null} } },
	{ "select * from obt union all select * from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},
		{"1","world"},{"2","planet"},{"2","planet"},
		{"3","hello"},{"3","hello"},{null,null},{null,null} } },
	{ "select * from obt union all select * from obt order by i, i", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},
		{"1","world"},{"2","planet"},{"2","planet"},
		{"3","hello"},{"3","hello"},{null,null},{null,null} } },
	{ "select * from obt union all select * from obt2 order by i", "42X78" },
	{ "select * from obt union all values (1,'hello') order by i", "42X78" },
	{ "values (1,'hello') union all select * from obt order by i", "42X78" },
	{ "values (1,'hello') union all select * from obt", new String[][] {
		{"1","hello"},{null,null},{"1","hello"},{"2","planet"},{"1","world"},{"3","hello"} } },
	{ "values (1,'hello') union all select * from obt order by SQLCol1", "42X78" },
	{ "values (1,'hello') union all select * from obt order by 1", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "values (1,'hello') union all select * from obt order by 1, 1", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select i from obt union all values (1) order by 1", new String[][] { {"1"},{"1"},{"1"},{"2"},{"3"},{null} } },
	{ "values (1) union all select i from obt order by i", "42X78"},
	{ "select * from obt union all select * from obt2 order by i2", "42X78" },
	{ "select * from obt order by 1,i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by 1,v", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by 1,2,1", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by v,i,v", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select i as i2, v from obt order by i2", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select i as i2, v from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select i, v from obt order i", "42X01" },
	{ "select i, v from obt by i", "42X01" },
	{ "select order from obt", "42X01" },
	{ "select by from obt", "42X01" },
	{ "select i from obt order by c", "42X04" },
	{ "select i from obt order by v", new String[][] { {"1"},{"3"},{"2"},{"1"},{null} } }, // also should be syntax error?
	{ "select i from obt order by i+1", new String[][] { {"1"},{"1"},{"2"},{"3"},{null} } },
	{ "select i from obt t order by obt.i", "42X04" },
	{ "select i from obt t order by obt.notexists", "42X04" },
	{ "create table t1(c1 int)" + getOffHeapSuffix(), null },
	{ "create table t2(c1 int)" + getOffHeapSuffix(), null },
	{ "create table t3(c3 int)"+ getOffHeapSuffix(), null },
	{ "insert into t1 values 2, 1", null },
	{ "insert into t2 values 4, 3", null },
	{ "insert into t3 values 6, 5", null },
	{ "select t1.c1, t2.c1 from t1, t2 order by t1.c1", new String[][] {
		{"1","3"},{"1","4"},{"2","3"},{"2","4"} } },
	{ "select t1.c1, t2.c1 from t1, t2 order by t2.c1", new String[][] {
		{"1","3"},{"2","3"},{"1","4"},{"2","4"} } },
	{ "select t1.c1, t2.c1 from t1, t1 t2 order by t2.c1", new String[][] { 
		{"1","1"},{"2","1"},{"1","2"},{"2","2"} } },
	{ "select t1.c1, t2.c1 from t1, t1 t2 order by t1.c1", new String[][] {
		{"1","1"},{"1","2"},{"2","1"},{"2","2"} } },
	{ "select c1 from t1 union select c3 as c1 from t3 order by t1.c1", "42877" },
	{ "select * from obt union all select * from obt2 order by obt.v", "42877" },
	{ "select * from obt union all select * from obt2 order by obt2.v", "42877" },
	{ "select * from obt union all select * from obt2 order by abc.v", "42877" },
	{ "select * from t1 inner join t2 on 1=1 order by t1.c1", new String[][] {
		{"1","3"},{"1","4"},{"2","3"},{"2","4"} } },
	{ "select * from t1 inner join t2 on 1=1 order by t2.c1", new String[][] {
		{"1","3"},{"2","3"},{"1","4"},{"2","4"} } },
	{ "select c1 from t1 order by app.t1.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 order by app.t1.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 order by t1.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 order by c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 c order by c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 c order by c.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from t1 order by c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from t1 union select c3 from t3 order by t3.c3", "42877" },
	{ "select c1 from t1 union select c3 from t3 order by asdf.c3", "42877" },
	{ "select c1 from t1 order by sys.t1.c1", "42X04" },
	{ "select c1 from app.t1 order by sys.t1.c1", "42X04" },
	{ "select c1 from t1 c order by app.c.c1", "42X10" },
	{ "select c1 from app.t1 c order by app.t1.c1", "42X04" },
	{ "select 1 as a from t1 order by t1.a", "42X04" },
	{ "select * from t1, t3 order by t3.c1", "42X04" },
	//FIXME gives nulls first
	//{ "select obt.i, obt2.i2+1, obt2.v from obt, obt2 order by 2, 3", new String[][] {
	//	{"3","2","shoe"}, {"1","2","shoe"}, {"2","2","shoe"},
	//	{"1","2","shoe"},{null,"2","shoe"},{"3","4","hello"},
	//	{"1","4","hello"},{"2","4","hello"},{"1","4","hello"},
	//	{null,"4","hello"},{"3","4","planet"},{"1","4","planet"},
	//	{"2","4","planet"},{"1","4","planet"},{null,"4","planet"},
	//	{"3","5","planet"},{"1","5","planet"},{"2","5","planet"},
	//	{"1","5","planet"},{null,"5","planet"} } },
	{ "select obt.i, obt2.i2+1, obt2.v from obt2, obt where obt.i=obt2.i2 order by 2, 3", new String[][] {
		{"1","2","shoe"},{"1","2","shoe"},{"3","4","hello"},{"3","4","planet"} } },
	{ "values 'hello ', 'hello    ', 'hello  ', 'hello' order by 1", new String[][] {
		{"hello"},{"hello"},{"hello"},{"hello"} } },
	{ "select i+1, v, {fn length(v)} from obt order by 2, 1 desc, 3", new String[][] {
		{"4","hello","5"},{"2","hello","5"},{"3","planet","6"},{"2","world","5"},{null,null,null} } },
	{ "select distinct i from obt order by i", new String[][] { {"1"},{"2"},{"3"},{null} } },
	{ "select distinct i,v from obt order by v", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select distinct i,v from obt order by v desc, i desc, v desc", new String[][] {
		{null,null},{"1","world"},{"2","planet"},{"3","hello"},{"1","hello"} } },
	{ "select distinct i,v from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "delete from obt", null },
	{ "select * from obt order by 1", new String[0][0] },
	{ "select * from obt order by v", new String[0][0] },
	{ "create table d (d double precision)"+ getOffHeapSuffix(), null },
	{ "insert into d values 1e-300,2e-300", null },
	{ "select d,d/1e5 as dd from d order by dd,d", new String[][] {
		{"1.0E-300","1.0E-305"},{"2.0E-300","2.0E-305"} } },
	{ "create table v (v varchar(1200))"+ getOffHeapSuffix(), null },
	{ "insert into v values 'itsastart'", null },
	{ "insert into v values 'hereandt'", null },
	{ "update v set v = v || v || v", null },
	{ "update v set v = v || v || v", null },
	{ "update v set v = v || v", null },
	{ "update v set v = v || v", null },
	{ "update v set v = v || v", null },
	{ "update v set v = v || v", "22001" },
	{ "drop table v", null },
	{ "create table missed (s smallint, r real, d date, t time, ts timestamp, c char(10), l bigint)"+ getOffHeapSuffix(), null },
	{ "insert into missed values (1,1.2e4, '1992-01-01','23:01:01', '1993-02-04 12:02:00.001', 'theend', 2222222222222)", null },
	{ "insert into missed values (1,1.2e4, '1992-01-01', '23:01:01', '1993-02-04 12:02:00.001', 'theend', 3333333333333)", null },
	{ "insert into missed values (2,1.0e4, '1992-01-01', '20:01:01', '1997-02-04 12:02:00.001', 'theend', 4444444444444)", null },
	{ "insert into missed values (2,1.0e4, '1992-01-01', '20:01:01', '1997-02-04 12:02:00.001', null,     2222222222222)", null },
	{ "select s from missed order by s", new String[][] { {"1"},{"1"},{"2"},{"2"} } },
	{ "select r from missed order by r", new String[][] { {"10000.0"},{"10000.0"},{"12000.0"},{"12000.0"} } },
	{ "select d,c from missed order by c,d", new String[][] {
		{"1992-01-01","theend"},
		{"1992-01-01","theend"},
		{"1992-01-01","theend"},
		{"1992-01-01",null} } },
	{ "create table ut (u char(10))"+ getOffHeapSuffix(), null },
	{ "insert into ut values (null)", null },
	{ "insert into ut values (cast ('hello' as char(10)))", null },
	{ "insert into ut values ('world')", null },
	{ "insert into ut values ('hello')", null },
	{ "insert into ut values ('world  ')", null },
	{ "select v from obt where v='newval'", new String[0][0] },
	{ "select v from obt where i in (select i from obt2 order by i)", "42X01" },
	{ "select v from obt where i = (select i from obt2 order by i)", "42X01" },
	{ "select v from (select i,v from obt2 order by i)", "42X01" },
	{ "create table tab1 (i integer, tn integer, s integer, l integer,c char(10), v char(10),lvc char(10),d double precision,r real,dt date,t time,ts timestamp,dc decimal(2,1))"+ getOffHeapSuffix(), null },
	{ "insert into tab1 values (1, cast(1 as int), cast(1 as smallint), cast(1 as bigint), '1', '1', '1', cast(1.1 as double precision), cast(1.1 as real), '1996-01-01', '11:11:11','1996-01-01 11:10:10.1', cast(1.1 as decimal(2,1)))", null },
	{ "insert into tab1 values (2, cast(2 as int), cast(2 as smallint), cast(2 as bigint), '2', '2', '2', cast(2.2 as double precision), cast(2.2 as real), '1995-02-02', '12:12:12', '1996-02-02 12:10:10.1', cast(2.2 as decimal(2,1)))", null },
	{ "select * from tab1 order by 1", new String [][] {
		{"1","1","1","1","1","1","1","1.1","1.1","1996-01-01","11:11:11","1996-01-01 11:10:10.1","1.1"},
		{"2","2","2","2","2","2","2","2.2","2.2","1995-02-02","12:12:12","1996-02-02 12:10:10.1","2.2"} } },
	{ "create table bug2769(c1 int, c2 int)"+ getOffHeapSuffix(), null },
	{ "insert into bug2769 values (1, 1), (1, 2), (3, 2), (3, 3)", null },
	{ "select a.c1, sum(a.c1) from bug2769 a group by a.c1 order by a.c1", new String[][] { {"1","2"},{"3","6"} } },
	{ "select bug2769.c1 as x, sum(bug2769.c1) as y from bug2769 group by bug2769.c1 order by bug2769.c1", new String[][] { {"1","2"},{"3","6"} } },
	{ "select bug2769.c1 as x, sum(bug2769.c1) as y from bug2769 group by bug2769.c1 order by x", new String[][] { {"1","2"},{"3","6"} } },
	{ "select c1 as x, c2 as y from bug2769 group by bug2769.c1, bug2769.c2 order by c1 + c2", new String[][] { {"1","1"},{"1","2"},{"3","2"},{"3","3"} } },
	{ "select c1 as x, c2 as y from bug2769 group by bug2769.c1, bug2769.c2 order by -(c1 + c2)", new String[][] { {"3","3"},{"3","2"},{"1","2"},{"1","1"} } },
	{ "drop table obt", null },
	{ "drop table obt2", null },
  { "drop table missed", null },
  { "drop table ut", null },
  { "drop table tab1", null },
  { "drop table bug2769", null },
  { "create table t (a int, b int, c int)"+ getOffHeapSuffix(), null },
	{ "insert into t values (1, 2, null), (2, 3, null), (3, 0, null), (1, 3, null)", null },
	{ "select * from t order by a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select * from t order by a, a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select * from t order by a, a, a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select * from t order by a, b", new String[][] {
		{"1","2",null},{"1","3",null},{"2","3",null},{"3","0",null} } },
	{ "select a, b, c from t order by a, a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select a, b, c from t order by a, b", new String[][] {
		{"1","2",null},{"1","3",null},{"2","3",null},{"3","0",null} } },
	{ "select a, c from t order by b", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select a, c from t order by b, b", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select a, b, c from t order by b", new String[][] {
		{"3","0",null},{"1","2",null},{"1","3",null},{"2","3",null} } },
	{ "select a from t order by b, c", new String[][] { {"3"},{"1"},{"1"},{"2"} } },
	{ "select a, c from t order by b, c", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select a, c from t order by b, c, b, c", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select b, c from t order by app.t.a", new String[][] {
		{"3",null},{"2",null},{"3",null},{"0",null} } },
	{ "create table test_word(value varchar(32))"+ getOffHeapSuffix(), null },
	{ "insert into test_word(value) values('anaconda')", null },
	{ "insert into test_word(value) values('America')", null },
	{ "insert into test_word(value) values('camel')", null },
	{ "insert into test_word(value) values('Canada')", null },
	{ "select * from test_word order by value", new String[][] {
		{"America"},{"Canada"},{"anaconda"},{"camel"} } },
	{ "select * from test_word order by upper(value)", new String[][] {
		{"America"},{"anaconda"},{"camel"},{"Canada"} } },
	{ "drop table test_word", null },
	{ "create table test_number(value integer)"+ getOffHeapSuffix(), null },
	{ "insert into test_number(value) values(-1)", null },
	{ "insert into test_number(value) values(0)", null },
	{ "insert into test_number(value) values(1)", null },
	{ "insert into test_number(value) values(2)", null },
	{ "insert into test_number(value) values(3)", null },
	{ "insert into test_number(value) values(100)", null },
	{ "insert into test_number(value) values(1000)", null },
	{ "select * from test_number order by value", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by value + 1", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by value - 1", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by value * 1", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by 1 - value", new String[][] {
		{"1000"},{"100"},{"3"},{"2"},{"1"},{"0"},{"-1"} } },
	{ "select * from test_number where value <> 0 order by 6000 / value", new String[][] {
		{"-1"},{"1000"},{"100"},{"3"},{"2"},{"1"} } },
	{ "select * from test_number order by -1 + value", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by -1 - value", new String[][] {
		{"1000"},{"100"},{"3"},{"2"},{"1"},{"0"},{"-1"} } },
	{ "select * from test_number order by - 1 * value", new String[][] {
		{"1000"},{"100"},{"3"},{"2"},{"1"},{"0"},{"-1"} } },
	{ "select * from test_number order by abs(value)", new String[][] {
		{"0"},{"-1"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "drop table test_number", null },
	{ "create table test_number2(value1 integer,value2 integer)"+ getOffHeapSuffix(), null },
	{ "insert into test_number2(value1,value2) values(-2,2)", null },
	{ "insert into test_number2(value1,value2) values(-1,2)", null },
	{ "insert into test_number2(value1,value2) values(0,1)", null },
	{ "insert into test_number2(value1,value2) values(0,2)", null },
	{ "insert into test_number2(value1,value2) values(1,1)", null },
	{ "insert into test_number2(value1,value2) values(2,1)", null },
	{ "select * from test_number2 order by abs(value1),mod(value2,2)", new String[][] {
		{"0","2"},{"0","1"},{"-1","2"},{"1","1"},{"-2","2"},{"2","1"} } },
	{ "drop table test_number2", null },
	{ "select * from t order by d", "42X04" },
	{ "select t.* from t order by d", "42X04" },
	{ "select t.* from t order by t.d", "42X04" },
	{ "select s.* from t s order by s.d", "42X04" },
	{ "select *, d from t order by d", "42X01" },
	{ "select t.*, d from t order by d", "42X04" },
	{ "select t.*, d from t order by t.d", "42X04" },
	{ "select t.*, d from t order by app.t.d", "42X04" },
	{ "select s.*, d from t s order by s.d", "42X04" },
	{ "select t.*, t.d from t order by t.d", "42X04" },
	{ "select s.*, s.d from t s order by s.d", "42X04" },
	{ "select a, b, c from t order by d", "42X04" },
	{ "select a from t order by d", "42X04" },
	{ "select t.a from t order by t.d", "42X04" },
	{ "select s.a from t s order by s.d", "42X04" },
	{ "drop table t", null },
	{ "select * from (values (2),(1)) as t(x) order by t.x", new String[][] { {"1"},{"2"} } },
	{ "create table ta(id int)"+ getOffHeapSuffix(), null },
	{ "create table tb(id int,c1 int,c2 int)"+ getOffHeapSuffix(), null },
	{ "insert into ta(id)  values(1)", null },
	{ "insert into ta(id)  values(2)", null },
	{ "insert into ta(id)  values(3)", null },
	{ "insert into ta(id)  values(4)", null },
	{ "insert into ta(id)  values(5)", null },
	{ "insert into tb(id,c1,c2) values(1,5,3)", null },
	{ "insert into tb(id,c1,c2) values(2,4,3)", null },
	{ "insert into tb(id,c1,c2) values(3,4,2)", null },
	{ "insert into tb(id,c1,c2) values(4,4,1)", null },
	{ "insert into tb(id,c1,c2) values(5,4,2)", null },
	{ "select t1.id,t2.c1 from ta as t1 join tb as t2 on t1.id = t2.id order by t2.c1,t2.c2,t1.id", new String[][] {
		{"4","4"},{"3","4"},{"5","4"},{"2","4"}, {"1","5"} } },
	{ "drop table ta", null },
	{ "drop table tb", null },
	{ "create table derby147 (a int, b int, c int, d int)"+ getOffHeapSuffix(), null },
	{ "insert into derby147 values (1, 2, 3, 4)", null },
	{ "insert into derby147 values (6, 6, 6, 6)", null },
	{ "select t.* from derby147 t", new String[][] {
		{"1","2","3","4"},
		{"6","6","6","6"} } },
	{ "select t.a,t.b,t.* from derby147 t order by b", new String[][] {
		{"1","2","1","2","3","4"},
		{"6","6","6","6","6","6"} } },
	{ "select t.a,t.b,t.b,t.c from derby147 t", new String[][] {
		{"1","2","2","3"},
		{"6","6","6","6"} } },
	{ "select t.a,t.b,t.b,t.c from derby147 t order by t.b", new String[][] {
		{"1","2","2","3"},
		{"6","6","6","6"} } },
	{ "select a+b as e, c+d as e from derby147 order by e", "42X79"},
	{ "create table derby147_a (a int, b int, c int, d int)"+ getOffHeapSuffix(), null },
	{ "insert into derby147_a values (1,2,3,4), (40, 30, 20, 10), (1,50,3,50)", null },
	{ "create table derby147_b (a int, b int)"+ getOffHeapSuffix(), null },
	{ "insert into derby147_b values (4, 4), (10, 10), (2, 50)", null },
	{ "select t1.a,t2.a from derby147_a t1, derby147_b t2 where t1.d=t2.b order by a", "42X79" },
	{ "select t1.a,t2.a from derby147_a t1, derby147_b t2 where t1.d=t2.b order by t2.a", new String[][] {
		{"1","2"},{"1","4"},{"40","10"} } },
	{ "select c+d as a, t1.a, t1.b+t1.c as a from derby147_a t1 order by a, a desc", "42X79" },
	{ "select a, c+d as a from derby147_a", new String[][] {
		{"1","7"},{"40","30"},{"1","53"} } },
	{ "select a, c+d as a from derby147_a order by a", "42X79" },
	{ "select c+d as a, t1.a, t1.b+t1.c as b_plus_c from derby147_a t1 order by c+d", new String[][] {
		{"7","1","5"},{"30","40","50"},{"53","1","53"} } },
	{ "select c+d as a, t1.a, t1.b+t1.c as a from derby147_a t1 order by d-4, a", "42X79" },
	{ "select * from derby147_a order by c+2 desc, b asc, a desc", new String[][] {
		{"40","30","20","10"},
		{"1","2","3","4"},
		{"1","50","3","50"} } },
	{ "select a, b from derby147_a t order by derby147_a.b", "42X04" },
	{ "select t.a, sum(t.a) from derby147_a t group by t.a order by t.a", new String[][] { {"1","2"},{"40","40"} } },
	{ "create table derby1861 (a int, b int, c int, d int)"+ getOffHeapSuffix(), null },
	{ "insert into derby1861 values (1, 2, 3, 4)", null },
	{ "select * from derby1861 order by a, b, c+2", new String[][] { {"1","2","3","4"} } },
	{ "select a, c from derby1861 order by a, b, c-4", new String[][] { {"1","3"} } },
	{ "select t.* from derby1861 t order by t.a, t.b, t.c+2", new String[][] { {"1","2","3","4"} } },
	{ "select a, b, a, c, d from derby1861 order by b, c-1, a", new String[][] { {"1","2","1","3","4"} } },
	{ "select * from derby1861 order by a, c+2, a", new String[][] { {"1","2","3","4"} } },
	{ "select * from derby1861 order by c-1, c+1, a, b, c * 6", new String[][] { {"1","2","3","4" } } },
	{ "select t.*, t.c+2 from derby1861 t order by a, b, c+2", new String[][] { {"1","2","3","4","5"} } },
	{ "select * from derby1861 order by 3, 1", new String[][] { {"1","2","3","4"} } },
	{ "select * from derby1861 order by 2, a-2", new String[][] { {"1","2","3","4"} } },
	{ "create table d2459_A1 ( id char(1) ,value int ,ref char(1))"+ getOffHeapSuffix(), null },
	{ "create table d2459_A2 ( id char(1) ,value int ,ref char(1))"+ getOffHeapSuffix(), null },
	{ "create table d2459_B1 ( id char(1) ,value int)"+ getOffHeapSuffix(), null },
	{ "create table d2459_B2 ( id char(1) ,value int)"+ getOffHeapSuffix(), null },
	{ "insert into d2459_A1 (id, value, ref) values ('b', 1, null)", null },
	{ "insert into d2459_A1 (id, value, ref) values ('a', 12, 'e')", null },
	{ "insert into d2459_A2 (id, value, ref) values ('c', 3, 'g')", null },
	{ "insert into d2459_A2 (id, value, ref) values ('d', 8, null)", null },
	{ "insert into d2459_B1 (id, value) values ('f', 2)", null },
	{ "insert into d2459_B1 (id, value) values ('e', 4)", null },
	{ "insert into d2459_B2 (id, value) values ('g', 5)", null },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END", new String[][] {
		{"c","5"},{"d","8"} } },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by id", new String[][] {
		{"a","4"},{"b","1"},{"c","5"},{"d","8"} } },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by 2", new String[][] {
		{"b","1"},{"a","4"},{"c","5"},{"d","8"} } },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by t1.id", "42877"},
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END", "42878" },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by value", "42X78"},
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by CASE WHEN id IS NOT NULL THEN id ELSE 2 END", "42878" },    // Derby says it should work, but currently unsupported
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by id || 'abc'", "42878"},
	{ "select id from D2459_A1 union select ref from D2459_A2", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id from D2459_A1 union select ref from D2459_A2 order by id", "42X78"},
	{ "select id from D2459_A1 union select ref from D2459_A2 order by 1", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id i from D2459_A1 union select ref i from D2459_A2 order by i", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id i from D2459_A1 union select ref j from D2459_A2", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id i from D2459_A1 union select ref j from D2459_A2 order by i", "42X78"},
	{ "select id i from D2459_A1 union select ref j from D2459_A2 order by 1", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id from D2459_A1 union select id from D2459_A2 order by D2459_A1.id", "42877" },
	{ "select id from D2459_A1 union select id from D2459_A2 order by id||'abc'", "42878" },
	{ "select * from D2459_A1 union select id, value, ref from D2459_A2 order by value", new String[][] {
		{"b","1",null},{"c","3","g"},{"d","8",null},{"a","12","e"} } },
	{ "select id, value, ref from D2459_A1 union select * from D2459_A2 order by 2", new String[][] {
		{"b","1",null},{"c","3","g"},{"d","8",null},{"a","12","e"} } },
	{ "select id, id i from D2459_A1 union select id j, id from D2459_A2 order by id", "42X78" },
	{ "select id, id i from D2459_A1 union select id j, id from D2459_A2 order by 2", new String[][] {
		{"a","a"},{"b","b"},{"c","c"},{"d","d"} } },
	{ "select id, ref from D2459_A1 union select ref, id from D2459_A2", new String[][] {
		{"a","e"},{"b",null},{"g","c"},{null,"d"} } },
	{ "select id i, ref j from D2459_A1 union select ref i, id j from D2459_A2", new String[][] {
		{"a","e"},{"b",null},{"g","c"},{null,"d"} } },
	{ "drop table t1", null },
	{ "drop table t2", null },
  { "drop table t3", null },
  { "drop table d", null },
  { "drop table DERBY147", null },
  { "drop table d2459_A2", null },
  { "drop table d2459_A1", null },
  { "drop table d2459_B2", null },
  { "drop table d2459_B1", null },
  { "create table t1 (c1 int, c2 varchar(10))"+ getOffHeapSuffix(), null },
	{ "create table t2 (t2c1 int)"+ getOffHeapSuffix(), null },
	{ "insert into t1 values (3, 'a'), (4, 'c'), (2, 'b'), (1, 'c')", null },
	{ "insert into t2 values (4), (3)", null },
	{ "select distinct c1, c2 from t1 order by c1", new String[][] {
		{"1","c"},{"2","b"},{"3","a"},{"4","c"} } },
	{ "select distinct c1, c2 from t1 order by c1+1", new String[][] {
		{"1","c"},{"2","b"},{"3","a"},{"4","c"} } },
	{ "select distinct c2 from t1 order by c1", "42879" },
	{ "select distinct c2 from t1 order by c2", new String[][] { {"a"},{"b"},{"c"} } },
	{ "select distinct * from t1 order by c2", new String[][] {
		{"3","a"},{"2","b"},{"1","c"},{"4","c"} } },
	{ "select distinct * from t1 order by c1+1", new String[][] {
		{"1","c"},{"2","b"},{"3","a"},{"4","c"} } },
	{ "select distinct t1.* from t1, t2 where t1.c1=t2.t2c1 order by t2c1", "42879" },
	{ "select t1.* from t1, t2 where t1.c1=t2.t2c1 order by t2c1", new String[][] {
		{"3","a"},{"4","c"} } },
	{ "drop table t1", null },
  { "drop table t2", null },
	{ "create table person (name varchar(10), age int)"+ getOffHeapSuffix(), null },
	{ "insert into person values ('John', 10)", null },
	{ "insert into person values ('John', 30)", null },
	{ "insert into person values ('Mary', 20)", null },
	{ "SELECT DISTINCT name FROM person ORDER BY age", "42879" },
	{ "SELECT DISTINCT name FROM person ORDER BY name", new String[][] { {"John"},{"Mary"} } },
	{ "SELECT DISTINCT name FROM person ORDER BY name desc", new String[][] { {"Mary"},{"John"} } },
	{ "select distinct name from person order by upper(name)", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name from person order by age*2", new String[][] { {"John"},{"Mary"},{"John"} } },   // Very odd case, but legal
	{ "select distinct name as first_name from person order by name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as first_name from person order by first_name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct person.name from person order by name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as first_name from person order by person.name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as age from person order by age", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as age from person order by person.age", "42879" },
	{ "select distinct name, name from person order by name", new String[][] { {"John","John"},{"Mary","Mary"} } },
	{ "select distinct name, name as first_name from person order by name", new String[][] { {"John","John"},{"Mary","Mary"} } },
	{ "select distinct name, name as first_name from person order by 2", new String[][] { {"John","John"},{"Mary","Mary"} } },
	{ "select distinct name nm from person p order by name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name nm from person p order by nm", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name nm from person p order by p.name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name nm from person p order by person.name", "42X04" },
	{ "select distinct name nm from person p order by person.nm", "42X04" },
	{ "select distinct name nm from person p order by p.nm", "42X04" },
	{ "create table pets (name varchar(10), age int)"+ getOffHeapSuffix(), null },
	{ "insert into pets values ('Rover', 3), ('Fido', 5), ('Buster', 1)",  null},
	{ "select distinct name from person union select distinct name from pets order by name", new String[][] {
		{"Buster"},{"Fido"},{"John"},{"Mary"},{"Rover"} } },
	{ "select distinct name from person, pets order by name", "42X03" },
	{ "select distinct person.name as person_name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "select distinct person.name as person_name, pets.name from person,pets order by name", "42X79" },
	{ "select distinct person.name as person_name, pets.name from person,pets order by person.name", new String[][] {
		{"John","Buster"},{"John","Fido"},{"John","Rover"},
		{"Mary","Buster"},{"Mary","Fido"},{"Mary","Rover"} } },
	{ "select distinct person.name as name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "select distinct person.name as name, pets.name as pet_name from person,pets order by pets.name", new String[][] {
		{"John","Buster"},{"Mary","Buster"},{"John","Fido"},
		{"Mary","Fido"},{"John","Rover"},{"Mary","Rover"} } },
	{ "select name from person, pets order by name", "42X03" },
	{ "select person.name as person_name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "select person.name as person_name, pets.name from person,pets order by name", "42X79" },
	{ "select person.name as name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "drop table person", null },
	{ "drop table pets", null },
	{ "create table d2352 (c int)"+ getOffHeapSuffix(), null },
	{ "insert into d2352 values (1), (2), (3)", null },
	{ "select substr('abc', 1) from d2352 order by substr('abc', 1)", new String[][] { {"abc"},{"abc"},{"abc"} } },
	{ "select substr('abc', 1) from d2352 group by substr('abc', 1)", new String[][] { {"abc"} } },
	{ "select ltrim('abc') from d2352 order by ltrim('abc')", new String[][] { {"abc"},{"abc"},{"abc"} } },
	{ "select ltrim('abc') from d2352 group by ltrim('abc')", new String[][] { {"abc"} } },
	{ "select trim(trailing ' ' from 'abc') from d2352 order by trim(trailing ' ' from 'abc')", new String[][] { {"abc"},{"abc"},{"abc"} } },
	{ "select trim(trailing ' ' from 'abc') from d2352 group by trim(trailing ' ' from 'abc')", new String[][] { {"abc"} } },
	{ "drop table d2352", null },
	{ "create table d3303 (i int, j int, k int)"+ getOffHeapSuffix(), null },
	{ "insert into d3303 values (1, 1, 2), (1, 3, 3), (2, 3, 1), (2, 2, 4)", null },
	{ "select sum(j) as s from d3303 group by i order by 1", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by s", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by s desc", new String[][] { {"5"},{"4"} } },
	{ "select sum(j) as s from d3303 group by i order by abs(1), s", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by sum(k), s desc", new String[][] { {"5"},{"4"} } },
	{ "select sum(j) as s from d3303 group by k order by abs(k) desc", new String[][] { {"2"},{"3"},{"1"},{"3"} } },
	{ "select sum(j) as s from d3303 group by k order by abs(k) asc", new String[][] { {"3"},{"1"},{"3"},{"2"} } },
	{ "select sum(j) as s from d3303 group by i order by abs(i)", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by abs(i) desc", new String[][] { {"5"},{"4"} } },
	{ "select distinct sum(j) as s from d3303 group by i", new String[][] { {"4"},{"5"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) asc", new String[][] {
		{"2","3","1"},{"1","1","1"},{"2","2","2"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) desc", new String[][] {
		{"2","2","2"},{"2","3","1"},{"1","1","1"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) desc, m2 asc", new String[][] {
		{"2","2","2"},{"1","1","1"},{"2","3","1"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) desc, m2 desc", new String[][] {
		{"2","2","2"},{"2","3","1"},{"1","1","1"} } },
	{ "select d3303.i as old_i, sum(d3303.k), d3303.* from d3303 group by k, i, j order by j", new String[][] {
		{"1","2","1","1","2"},		{"2","4","2","2","4"},	{"1","3","1","3","3"},		{"2","1","2","3","1"} } },
	{ "select d3303.i as old_i, sum(d3303.k), d3303.* from d3303 group by k, i, j order by 4", new String[][] {
		{"1","2","1","1","2"},		{"2","4","2","2","4"},	{"1","3","1","3","3"},		{"2","1","2","3","1"} } },
	{ "select d3303.i as old_i, sum(d3303.k), d3303.* from d3303 group by k, i, j order by k+2", new String[][] {
		{"2","1","2","3","1"},
		{"1","2","1","1","2"},
		{"1","3","1","3","3"},
		{"2","4","2","2","4"} } },
	{ "select k as s from d3303 order by 2", "42X77" },
	{ "select sum(k) as s from d3303 group by i order by 2", "42X77" },
	{ "select k from d3303 group by i,k order by 2", "42X77" },
	{ "select k as s from d3303 group by i,k order by 2", "42X77" },
	{ "drop table d3303", null }
   };

    // Do not use partitioning as default, use replicate
    // (Some results are expected to be different with partitioning)
    skipDefaultPartitioned = true;

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,Script_OrderbyUT);
  }
  
  // This test is the script enhanced with partitioning
  public void testLangScript_OrderbyWithPartitioning() throws Exception
  {
    // This form of the Orderby.sql test has partitioning activated
    // TODO : Create specialized partitioning for these tables for Orderby
    //  Currently, default partitioning is active
    Object[][] Script_OrderbyUTPartitioning = {
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 1,2,3", new String[][] {
		{"0","0","1"},{"0","1","0"},{"1","0","0"},{"1","0","1"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 1,3", new String[][] {
		{"0","1","0"},{"0","0","1"},{"1","0","0"},{"1","0","1"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 2,1", new String[][] {
		{"0","0","1"},{"1","0","0"},{"1","0","1"},{"0","1","0"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 2", new String[][] {
		{"0","0","1"},{"1","0","0"},{"1","0","1"},{"0","1","0"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 0", "42X77" },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 4", "42X77" },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0)", new String[][] {
		{"0","0","1"},{"0","1","0"},{"1","0","0"},{"1","0","1"} } },
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by SQLCol1", "42X78"},
	{ "values (1,0,1),(1,0,0),(0,0,1),(0,1,0) order by 1,1,2,3", new String[][] {
		{"0","0","1"},{"0","1","0"},{"1","0","0"},{"1","0","1"} } },
	{ "create table obt (i int, v varchar(40))"+ getOffHeapSuffix(), null },
	{ "insert into obt (i) values (null)", null },
	{ "insert into obt values (1, 'hello')", null },
	{ "insert into obt values (2, 'planet')", null },
	{ "insert into obt values (1, 'world')", null },
	{ "insert into obt values (3, 'hello')", null },
	{ "select * from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by v", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select * from obt order by i,v", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by v,i", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select * from obt order by v desc, i asc", new String[][] {
		{null,null},{"1","world"},{"2","planet"},{"1","hello"},{"3","hello"} } },
	{ "select * from obt order by i asc, v desc", new String[][] {
		{"1","world"},{"1","hello"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by i asc, i desc", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by i, v, i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select v from obt order by i, v, i", new String[][] { {"hello"},{"world"},{"planet"},{"hello"},{null} } },
	{ "select v from obt order by i desc, v, i", new String[][] { {null},{"hello"},{"planet"},{"hello"},{"world"} } },
	{ "select * from obt order by 1, 0", "42X77" },
	{ "select * from obt order by 1,2,3,4,5,6,7,8,9", "42X77" },
	{ "select * from obt order by 32767", "42X77" },
	{ "create table obt2 (i2 int, v varchar(40))"+ getOffHeapSuffix(), null },
	{ "insert into obt2 values (3, 'hello'), (4, 'planet'), (1, 'shoe'), (3, 'planet')", null },
	//FIXME
	// Union all queries here throw 'visibleSize must match' - known bug
	//{ "select * from obt union all select * from obt2 order by v", new String[][] {
	//	{"3","hello"},{"3","hello"},{"1","hello"},
	//	{"3","planet"},{"4","planet"},{"2","planet"},
	//	{"1","shoe"},{"1","world"},{null,null} } },
	{ "select * from obt union all select * from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},
		{"1","world"},{"2","planet"},{"2","planet"},
		{"3","hello"},{"3","hello"},{null,null},{null,null} } },
	{ "select * from obt union all select * from obt order by i, i", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},
		{"1","world"},{"2","planet"},{"2","planet"},
		{"3","hello"},{"3","hello"},{null,null},{null,null} } },
	{ "select * from obt union all select * from obt2 order by i", "42X78" },
	{ "select * from obt union all values (1,'hello') order by i", "42X78" },
	{ "values (1,'hello') union all select * from obt order by i", "42X78" },
	{ "values (1,'hello') union all select * from obt", new String[][] {
		{"1","hello"},{null,null},{"1","hello"},{"2","planet"},{"1","world"},{"3","hello"} } },
	{ "values (1,'hello') union all select * from obt order by SQLCol1", "42X78" },
	{ "values (1,'hello') union all select * from obt order by 1", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "values (1,'hello') union all select * from obt order by 1, 1", new String[][] {
		{"1","hello"},{"1","world"},{"1","hello"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select i from obt union all values (1) order by 1", new String[][] { {"1"},{"1"},{"1"},{"2"},{"3"},{null} } },
	{ "values (1) union all select i from obt order by i", "42X78"},
	{ "select * from obt union all select * from obt2 order by i2", "42X78" },
	{ "select * from obt order by 1,i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by 1,v", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by 1,2,1", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select * from obt order by v,i,v", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select i as i2, v from obt order by i2", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select i as i2, v from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "select i, v from obt order i", "42X01" },
	{ "select i, v from obt by i", "42X01" },
	{ "select order from obt", "42X01" },
	{ "select by from obt", "42X01" },
	{ "select i from obt order by c", "42X04" },
	{ "select i from obt order by v", new String[][] { {"1"},{"3"},{"2"},{"1"},{null} } }, // also should be syntax error?
	{ "select i from obt order by i+1", new String[][] { {"1"},{"1"},{"2"},{"3"},{null} } },
	{ "select i from obt t order by obt.i", "42X04" },
	{ "select i from obt t order by obt.notexists", "42X04" },
	{ "create table t1(c1 int)"+ getOffHeapSuffix(), null },
	{ "create table t2(c1 int)"+ getOffHeapSuffix(), null },
	{ "create table t3(c3 int)"+ getOffHeapSuffix(), null },
	{ "insert into t1 values 2, 1", null },
	{ "insert into t2 values 4, 3", null },
	{ "insert into t3 values 6, 5", null },
	{ "select t1.c1, t2.c1 from t1, t2 order by t1.c1", new String[][] {
		{"1","3"},{"1","4"},{"2","3"},{"2","4"} } },
	{ "select t1.c1, t2.c1 from t1, t2 order by t2.c1", new String[][] {
		{"1","3"},{"2","3"},{"1","4"},{"2","4"} } },
	{ "select t1.c1, t2.c1 from t1, t1 t2 order by t2.c1", new String[][] { 
		{"1","1"},{"2","1"},{"1","2"},{"2","2"} } },
	{ "select t1.c1, t2.c1 from t1, t1 t2 order by t1.c1", new String[][] {
		{"1","1"},{"1","2"},{"2","1"},{"2","2"} } },
	{ "select c1 from t1 union select c3 as c1 from t3 order by t1.c1", "42877" },
	{ "select * from obt union all select * from obt2 order by obt.v", "42877" },
	{ "select * from obt union all select * from obt2 order by obt2.v", "42877" },
	{ "select * from obt union all select * from obt2 order by abc.v", "42877" },
	{ "select * from t1 inner join t2 on 1=1 order by t1.c1", new String[][] {
		{"1","3"},{"1","4"},{"2","3"},{"2","4"} } },
	{ "select * from t1 inner join t2 on 1=1 order by t2.c1", new String[][] {
		{"1","3"},{"2","3"},{"1","4"},{"2","4"} } },
	{ "select c1 from t1 order by app.t1.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 order by app.t1.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 order by t1.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 order by c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 c order by c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from app.t1 c order by c.c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from t1 order by c1", new String[][] { {"1"},{"2"} } },
	{ "select c1 from t1 union select c3 from t3 order by t3.c3", "42877" },
	{ "select c1 from t1 union select c3 from t3 order by asdf.c3", "42877" },
	{ "select c1 from t1 order by sys.t1.c1", "42X04" },
	{ "select c1 from app.t1 order by sys.t1.c1", "42X04" },
	{ "select c1 from t1 c order by app.c.c1", "42X10" },
	{ "select c1 from app.t1 c order by app.t1.c1", "42X04" },
	{ "select 1 as a from t1 order by t1.a", "42X04" },
	{ "select * from t1, t3 order by t3.c1", "42X04" },
	//FIXME gives nulls first
	//{ "select obt.i, obt2.i2+1, obt2.v from obt, obt2 order by 2, 3", new String[][] {
	//	{"3","2","shoe"}, {"1","2","shoe"}, {"2","2","shoe"},
	//	{"1","2","shoe"},{null,"2","shoe"},{"3","4","hello"},
	//	{"1","4","hello"},{"2","4","hello"},{"1","4","hello"},
	//	{null,"4","hello"},{"3","4","planet"},{"1","4","planet"},
	//	{"2","4","planet"},{"1","4","planet"},{null,"4","planet"},
	//	{"3","5","planet"},{"1","5","planet"},{"2","5","planet"},
	//	{"1","5","planet"},{null,"5","planet"} } },
	{ "select obt.i, obt2.i2+1, obt2.v from obt2, obt where obt.i=obt2.i2 order by 2, 3", new String[][] {
		{"1","2","shoe"},{"1","2","shoe"},{"3","4","hello"},{"3","4","planet"} } },
	{ "values 'hello ', 'hello    ', 'hello  ', 'hello' order by 1", new String[][] {
		{"hello"},{"hello"},{"hello"},{"hello"} } },
	{ "select i+1, v, {fn length(v)} from obt order by 2, 1 desc, 3", new String[][] {
		{"4","hello","5"},{"2","hello","5"},{"3","planet","6"},{"2","world","5"},{null,null,null} } },
	{ "select distinct i from obt order by i", new String[][] { {"1"},{"2"},{"3"},{null} } },
	{ "select distinct i,v from obt order by v", new String[][] {
		{"1","hello"},{"3","hello"},{"2","planet"},{"1","world"},{null,null} } },
	{ "select distinct i,v from obt order by v desc, i desc, v desc", new String[][] {
		{null,null},{"1","world"},{"2","planet"},{"3","hello"},{"1","hello"} } },
	{ "select distinct i,v from obt order by i", new String[][] {
		{"1","hello"},{"1","world"},{"2","planet"},{"3","hello"},{null,null} } },
	{ "delete from obt", null },
	{ "select * from obt order by 1", new String[0][0] },
	{ "select * from obt order by v", new String[0][0] },
	{ "create table d (d double precision)"+ getOffHeapSuffix(), null },
	{ "insert into d values 1e-300,2e-300", null },
	{ "select d,d/1e5 as dd from d order by dd,d", new String[][] {
		{"1.0E-300","1.0E-305"},{"2.0E-300","2.0E-305"} } },
	{ "create table v (v varchar(1200))"+ getOffHeapSuffix(), null },
	{ "insert into v values 'itsastart'", null },
	{ "insert into v values 'hereandt'", null },
	{ "update v set v = v || v || v", null },
	{ "update v set v = v || v || v", null },
	{ "update v set v = v || v", null },
	{ "update v set v = v || v", null },
	{ "update v set v = v || v", null },
	{ "update v set v = v || v", "22001" },
	{ "drop table v", null },
	{ "create table missed (s smallint, r real, d date, t time, ts timestamp, c char(10), l bigint)"+ getOffHeapSuffix(), null },
	{ "insert into missed values (1,1.2e4, '1992-01-01','23:01:01', '1993-02-04 12:02:00.001', 'theend', 2222222222222)", null },
	{ "insert into missed values (1,1.2e4, '1992-01-01', '23:01:01', '1993-02-04 12:02:00.001', 'theend', 3333333333333)", null },
	{ "insert into missed values (2,1.0e4, '1992-01-01', '20:01:01', '1997-02-04 12:02:00.001', 'theend', 4444444444444)", null },
	{ "insert into missed values (2,1.0e4, '1992-01-01', '20:01:01', '1997-02-04 12:02:00.001', null,     2222222222222)", null },
	{ "select s from missed order by s", new String[][] { {"1"},{"1"},{"2"},{"2"} } },
	{ "select r from missed order by r", new String[][] { {"10000.0"},{"10000.0"},{"12000.0"},{"12000.0"} } },
	{ "select d,c from missed order by c,d", new String[][] {
		{"1992-01-01","theend"},
		{"1992-01-01","theend"},
		{"1992-01-01","theend"},
		{"1992-01-01",null} } },
	{ "create table ut (u char(10))"+ getOffHeapSuffix(), null },
	{ "insert into ut values (null)", null },
	{ "insert into ut values (cast ('hello' as char(10)))", null },
	{ "insert into ut values ('world')", null },
	{ "insert into ut values ('hello')", null },
	{ "insert into ut values ('world  ')", null },
	{ "select v from obt where v='newval'", new String[0][0] },
	{ "select v from obt where i in (select i from obt2 order by i)", "42X01" },
	{ "select v from obt where i = (select i from obt2 order by i)", "42X01" },
	{ "select v from (select i,v from obt2 order by i)", "42X01" },
	{ "create table tab1 (i integer, tn integer, s integer, l integer,c char(10), v char(10),lvc char(10),d double precision,r real,dt date,t time,ts timestamp,dc decimal(2,1))"+ getOffHeapSuffix(), null },
	{ "insert into tab1 values (1, cast(1 as int), cast(1 as smallint), cast(1 as bigint), '1', '1', '1', cast(1.1 as double precision), cast(1.1 as real), '1996-01-01', '11:11:11','1996-01-01 11:10:10.1', cast(1.1 as decimal(2,1)))", null },
	{ "insert into tab1 values (2, cast(2 as int), cast(2 as smallint), cast(2 as bigint), '2', '2', '2', cast(2.2 as double precision), cast(2.2 as real), '1995-02-02', '12:12:12', '1996-02-02 12:10:10.1', cast(2.2 as decimal(2,1)))", null },
	{ "select * from tab1 order by 1", new String [][] {
		{"1","1","1","1","1","1","1","1.1","1.1","1996-01-01","11:11:11","1996-01-01 11:10:10.1","1.1"},
		{"2","2","2","2","2","2","2","2.2","2.2","1995-02-02","12:12:12","1996-02-02 12:10:10.1","2.2"} } },
	{ "create table bug2769(c1 int, c2 int)"+ getOffHeapSuffix(), null },
	{ "insert into bug2769 values (1, 1), (1, 2), (3, 2), (3, 3)", null },
	{ "select a.c1, sum(a.c1) from bug2769 a group by a.c1 order by a.c1", new String[][] { {"1","2"},{"3","6"} } },
	{ "select bug2769.c1 as x, sum(bug2769.c1) as y from bug2769 group by bug2769.c1 order by bug2769.c1", new String[][] { {"1","2"},{"3","6"} } },
	{ "select bug2769.c1 as x, sum(bug2769.c1) as y from bug2769 group by bug2769.c1 order by x", new String[][] { {"1","2"},{"3","6"} } },
	{ "select c1 as x, c2 as y from bug2769 group by bug2769.c1, bug2769.c2 order by c1 + c2", new String[][] { {"1","1"},{"1","2"},{"3","2"},{"3","3"} } },
	{ "select c1 as x, c2 as y from bug2769 group by bug2769.c1, bug2769.c2 order by -(c1 + c2)", new String[][] { {"3","3"},{"3","2"},{"1","2"},{"1","1"} } },
	{ "drop table obt", null },
	{ "drop table obt2", null },
  { "drop table missed", null },
  { "drop table ut", null },
  { "drop table tab1", null },
  { "drop table bug2769", null },
  { "create table t (a int, b int, c int)"+ getOffHeapSuffix(), null },
	{ "insert into t values (1, 2, null), (2, 3, null), (3, 0, null), (1, 3, null)", null },
	{ "select * from t order by a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select * from t order by a, a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select * from t order by a, a, a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select * from t order by a, b", new String[][] {
		{"1","2",null},{"1","3",null},{"2","3",null},{"3","0",null} } },
	{ "select a, b, c from t order by a, a", new String[][] {
		{"1","3",null},{"1","2",null},{"2","3",null},{"3","0",null} } },
	{ "select a, b, c from t order by a, b", new String[][] {
		{"1","2",null},{"1","3",null},{"2","3",null},{"3","0",null} } },
	{ "select a, c from t order by b", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select a, c from t order by b, b", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select a, b, c from t order by b", new String[][] {
		{"3","0",null},{"1","2",null},{"1","3",null},{"2","3",null} } },
	{ "select a from t order by b, c", new String[][] { {"3"},{"1"},{"1"},{"2"} } },
	{ "select a, c from t order by b, c", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select a, c from t order by b, c, b, c", new String[][] {
		{"3",null},{"1",null},{"1",null},{"2",null} } },
	{ "select b, c from t order by app.t.a", new String[][] {
		{"3",null},{"2",null},{"3",null},{"0",null} } },
	{ "create table test_word(value varchar(32))"+ getOffHeapSuffix(), null },
	{ "insert into test_word(value) values('anaconda')", null },
	{ "insert into test_word(value) values('America')", null },
	{ "insert into test_word(value) values('camel')", null },
	{ "insert into test_word(value) values('Canada')", null },
	{ "select * from test_word order by value", new String[][] {
		{"America"},{"Canada"},{"anaconda"},{"camel"} } },
	{ "select * from test_word order by upper(value)", new String[][] {
		{"America"},{"anaconda"},{"camel"},{"Canada"} } },
	{ "drop table test_word", null },
	{ "create table test_number(value integer)"+ getOffHeapSuffix(), null },
	{ "insert into test_number(value) values(-1)", null },
	{ "insert into test_number(value) values(0)", null },
	{ "insert into test_number(value) values(1)", null },
	{ "insert into test_number(value) values(2)", null },
	{ "insert into test_number(value) values(3)", null },
	{ "insert into test_number(value) values(100)", null },
	{ "insert into test_number(value) values(1000)", null },
	{ "select * from test_number order by value", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by value + 1", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by value - 1", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by value * 1", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by 1 - value", new String[][] {
		{"1000"},{"100"},{"3"},{"2"},{"1"},{"0"},{"-1"} } },
	{ "select * from test_number where value <> 0 order by 6000 / value", new String[][] {
		{"-1"},{"1000"},{"100"},{"3"},{"2"},{"1"} } },
	{ "select * from test_number order by -1 + value", new String[][] {
		{"-1"},{"0"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "select * from test_number order by -1 - value", new String[][] {
		{"1000"},{"100"},{"3"},{"2"},{"1"},{"0"},{"-1"} } },
	{ "select * from test_number order by - 1 * value", new String[][] {
		{"1000"},{"100"},{"3"},{"2"},{"1"},{"0"},{"-1"} } },
	{ "select * from test_number order by abs(value)", new String[][] {
		{"0"},{"-1"},{"1"},{"2"},{"3"},{"100"},{"1000"} } },
	{ "drop table test_number", null },
	{ "create table test_number2(value1 integer,value2 integer)"+ getOffHeapSuffix(), null },
	{ "insert into test_number2(value1,value2) values(-2,2)", null },
	{ "insert into test_number2(value1,value2) values(-1,2)", null },
	{ "insert into test_number2(value1,value2) values(0,1)", null },
	{ "insert into test_number2(value1,value2) values(0,2)", null },
	{ "insert into test_number2(value1,value2) values(1,1)", null },
	{ "insert into test_number2(value1,value2) values(2,1)", null },
	{ "select * from test_number2 order by abs(value1),mod(value2,2)", new String[][] {
		{"0","2"},{"0","1"},{"-1","2"},{"1","1"},{"-2","2"},{"2","1"} } },
	{ "drop table test_number2", null },
	{ "select * from t order by d", "42X04" },
	{ "select t.* from t order by d", "42X04" },
	{ "select t.* from t order by t.d", "42X04" },
	{ "select s.* from t s order by s.d", "42X04" },
	{ "select *, d from t order by d", "42X01" },
	{ "select t.*, d from t order by d", "42X04" },
	{ "select t.*, d from t order by t.d", "42X04" },
	{ "select t.*, d from t order by app.t.d", "42X04" },
	{ "select s.*, d from t s order by s.d", "42X04" },
	{ "select t.*, t.d from t order by t.d", "42X04" },
	{ "select s.*, s.d from t s order by s.d", "42X04" },
	{ "select a, b, c from t order by d", "42X04" },
	{ "select a from t order by d", "42X04" },
	{ "select t.a from t order by t.d", "42X04" },
	{ "select s.a from t s order by s.d", "42X04" },
	{ "drop table t", null },
	{ "select * from (values (2),(1)) as t(x) order by t.x", new String[][] { {"1"},{"2"} } },
	{ "create table ta(id int)"+ getOffHeapSuffix(), null },
	{ "create table tb(id int,c1 int,c2 int)"+ getOffHeapSuffix(), null },
	{ "insert into ta(id)  values(1)", null },
	{ "insert into ta(id)  values(2)", null },
	{ "insert into ta(id)  values(3)", null },
	{ "insert into ta(id)  values(4)", null },
	{ "insert into ta(id)  values(5)", null },
	{ "insert into tb(id,c1,c2) values(1,5,3)", null },
	{ "insert into tb(id,c1,c2) values(2,4,3)", null },
	{ "insert into tb(id,c1,c2) values(3,4,2)", null },
	{ "insert into tb(id,c1,c2) values(4,4,1)", null },
	{ "insert into tb(id,c1,c2) values(5,4,2)", null },
	{ "select t1.id,t2.c1 from ta as t1 join tb as t2 on t1.id = t2.id order by t2.c1,t2.c2,t1.id", new String[][] {
		{"4","4"},{"3","4"},{"5","4"},{"2","4"}, {"1","5"} } },
	{ "drop table ta", null },
	{ "drop table tb", null },
	{ "create table derby147 (a int, b int, c int, d int)"+ getOffHeapSuffix(), null },
	{ "insert into derby147 values (1, 2, 3, 4)", null },
	{ "insert into derby147 values (6, 6, 6, 6)", null },
	{ "select t.* from derby147 t", new String[][] {
		{"1","2","3","4"},
		{"6","6","6","6"} } },
	{ "select t.a,t.b,t.* from derby147 t order by b", new String[][] {
		{"1","2","1","2","3","4"},
		{"6","6","6","6","6","6"} } },
	{ "select t.a,t.b,t.b,t.c from derby147 t", new String[][] {
		{"1","2","2","3"},
		{"6","6","6","6"} } },
	{ "select t.a,t.b,t.b,t.c from derby147 t order by t.b", new String[][] {
		{"1","2","2","3"},
		{"6","6","6","6"} } },
	{ "select a+b as e, c+d as e from derby147 order by e", "42X79"},
	{ "create table derby147_a (a int, b int, c int, d int)"+ getOffHeapSuffix(), null },
	{ "insert into derby147_a values (1,2,3,4), (40, 30, 20, 10), (1,50,3,50)", null },
	{ "create table derby147_b (a int, b int)"+ getOffHeapSuffix(), null },
	{ "insert into derby147_b values (4, 4), (10, 10), (2, 50)", null },
	{ "select t1.a,t2.a from derby147_a t1, derby147_b t2 where t1.d=t2.b order by a", "42X79" },
	{ "select t1.a,t2.a from derby147_a t1, derby147_b t2 where t1.d=t2.b order by t2.a", new String[][] {
		{"1","2"},{"1","4"},{"40","10"} } },
	{ "select c+d as a, t1.a, t1.b+t1.c as a from derby147_a t1 order by a, a desc", "42X79" },
	{ "select a, c+d as a from derby147_a", new String[][] {
		{"1","7"},{"40","30"},{"1","53"} } },
	{ "select a, c+d as a from derby147_a order by a", "42X79" },
	{ "select c+d as a, t1.a, t1.b+t1.c as b_plus_c from derby147_a t1 order by c+d", new String[][] {
		{"7","1","5"},{"30","40","50"},{"53","1","53"} } },
	{ "select c+d as a, t1.a, t1.b+t1.c as a from derby147_a t1 order by d-4, a", "42X79" },
	{ "select * from derby147_a order by c+2 desc, b asc, a desc", new String[][] {
		{"40","30","20","10"},
		{"1","2","3","4"},
		{"1","50","3","50"} } },
	{ "select a, b from derby147_a t order by derby147_a.b", "42X04" },
	{ "select t.a, sum(t.a) from derby147_a t group by t.a order by t.a", new String[][] { {"1","2"},{"40","40"} } },
	{ "create table derby1861 (a int, b int, c int, d int)"+ getOffHeapSuffix(), null },
	{ "insert into derby1861 values (1, 2, 3, 4)", null },
	{ "select * from derby1861 order by a, b, c+2", new String[][] { {"1","2","3","4"} } },
	{ "select a, c from derby1861 order by a, b, c-4", new String[][] { {"1","3"} } },
	{ "select t.* from derby1861 t order by t.a, t.b, t.c+2", new String[][] { {"1","2","3","4"} } },
	{ "select a, b, a, c, d from derby1861 order by b, c-1, a", new String[][] { {"1","2","1","3","4"} } },
	{ "select * from derby1861 order by a, c+2, a", new String[][] { {"1","2","3","4"} } },
	{ "select * from derby1861 order by c-1, c+1, a, b, c * 6", new String[][] { {"1","2","3","4" } } },
	{ "select t.*, t.c+2 from derby1861 t order by a, b, c+2", new String[][] { {"1","2","3","4","5"} } },
	{ "select * from derby1861 order by 3, 1", new String[][] { {"1","2","3","4"} } },
	{ "select * from derby1861 order by 2, a-2", new String[][] { {"1","2","3","4"} } },
	{ "create table d2459_A1 ( id char(1) ,value int ,ref char(1))"+ getOffHeapSuffix(), null },
	{ "create table d2459_A2 ( id char(1) ,value int ,ref char(1))"+ getOffHeapSuffix(), null },
	{ "create table d2459_B1 ( id char(1) ,value int)"+ getOffHeapSuffix(), null },
	{ "create table d2459_B2 ( id char(1) ,value int)"+ getOffHeapSuffix(), null },
	{ "insert into d2459_A1 (id, value, ref) values ('b', 1, null)", null },
	{ "insert into d2459_A1 (id, value, ref) values ('a', 12, 'e')", null },
	{ "insert into d2459_A2 (id, value, ref) values ('c', 3, 'g')", null },
	{ "insert into d2459_A2 (id, value, ref) values ('d', 8, null)", null },
	{ "insert into d2459_B1 (id, value) values ('f', 2)", null },
	{ "insert into d2459_B1 (id, value) values ('e', 4)", null },
	{ "insert into d2459_B2 (id, value) values ('g', 5)", null },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END", new String[][] {
		{"c","5"},{"d","8"} } },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by id", new String[][] {
		{"a","4"},{"b","1"},{"c","5"},{"d","8"} } },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by 2", new String[][] {
		{"b","1"},{"a","4"},{"c","5"},{"d","8"} } },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by t1.id", "42877"},
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END", "42878" },
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by value", "42X78"},
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by CASE WHEN id IS NOT NULL THEN id ELSE 2 END", "42878" },    // Derby says it should work, but currently unsupported
	{ "select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A1 t1 left outer join d2459_B1 t2 ON t2.id = t1.ref union all select t1.id, CASE WHEN t2.value IS NOT NULL THEN t2.value ELSE t1.value END from d2459_A2 t1 left outer join d2459_B2 t2 ON t2.id = t1.ref order by id || 'abc'", "42878"},
	{ "select id from D2459_A1 union select ref from D2459_A2", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id from D2459_A1 union select ref from D2459_A2 order by id", "42X78"},
	{ "select id from D2459_A1 union select ref from D2459_A2 order by 1", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id i from D2459_A1 union select ref i from D2459_A2 order by i", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id i from D2459_A1 union select ref j from D2459_A2", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id i from D2459_A1 union select ref j from D2459_A2 order by i", "42X78"},
	{ "select id i from D2459_A1 union select ref j from D2459_A2 order by 1", new String[][] { {"a"},{"b"},{"g"},{null} } },
	{ "select id from D2459_A1 union select id from D2459_A2 order by D2459_A1.id", "42877" },
	{ "select id from D2459_A1 union select id from D2459_A2 order by id||'abc'", "42878" },
	{ "select * from D2459_A1 union select id, value, ref from D2459_A2 order by value", new String[][] {
		{"b","1",null},{"c","3","g"},{"d","8",null},{"a","12","e"} } },
	{ "select id, value, ref from D2459_A1 union select * from D2459_A2 order by 2", new String[][] {
		{"b","1",null},{"c","3","g"},{"d","8",null},{"a","12","e"} } },
	{ "select id, id i from D2459_A1 union select id j, id from D2459_A2 order by id", "42X78" },
	{ "select id, id i from D2459_A1 union select id j, id from D2459_A2 order by 2", new String[][] {
		{"a","a"},{"b","b"},{"c","c"},{"d","d"} } },
	{ "select id, ref from D2459_A1 union select ref, id from D2459_A2", new String[][] {
		{"a","e"},{"b",null},{"g","c"},{null,"d"} } },
	{ "select id i, ref j from D2459_A1 union select ref i, id j from D2459_A2", new String[][] {
		{"a","e"},{"b",null},{"g","c"},{null,"d"} } },
	{ "drop table t1", null },
	{ "drop table t2", null },
  { "drop table t3", null },
  { "drop table d", null },
  { "drop table DERBY147", null },
  { "drop table d2459_A2", null },
  { "drop table d2459_A1", null },
  { "drop table d2459_B2", null },
  { "drop table d2459_B1", null },
	{ "create table t1 (c1 int, c2 varchar(10))"+ getOffHeapSuffix(), null },
	{ "create table t2 (t2c1 int)"+ getOffHeapSuffix(), null },
	{ "insert into t1 values (3, 'a'), (4, 'c'), (2, 'b'), (1, 'c')", null },
	{ "insert into t2 values (4), (3)", null },
	{ "select distinct c1, c2 from t1 order by c1", new String[][] {
		{"1","c"},{"2","b"},{"3","a"},{"4","c"} } },
	{ "select distinct c1, c2 from t1 order by c1+1", new String[][] {
		{"1","c"},{"2","b"},{"3","a"},{"4","c"} } },
	{ "select distinct c2 from t1 order by c1", "42879" },
	{ "select distinct c2 from t1 order by c2", new String[][] { {"a"},{"b"},{"c"} } },
	{ "select distinct * from t1 order by c2", new String[][] {
		{"3","a"},{"2","b"},{"1","c"},{"4","c"} } },
	{ "select distinct * from t1 order by c1+1", new String[][] {
		{"1","c"},{"2","b"},{"3","a"},{"4","c"} } },
	{ "select distinct t1.* from t1, t2 where t1.c1=t2.t2c1 order by t2c1", "42879" },
	{ "select t1.* from t1, t2 where t1.c1=t2.t2c1 order by t2c1", new String[][] {
		{"3","a"},{"4","c"} } },
	{ "drop table t1", null },
  { "drop table t2", null },
	{ "create table person (name varchar(10), age int)"+ getOffHeapSuffix(), null },
	{ "insert into person values ('John', 10)", null },
	{ "insert into person values ('John', 30)", null },
	{ "insert into person values ('Mary', 20)", null },
	{ "SELECT DISTINCT name FROM person ORDER BY age", "42879" },
	{ "SELECT DISTINCT name FROM person ORDER BY name", new String[][] { {"John"},{"Mary"} } },
	{ "SELECT DISTINCT name FROM person ORDER BY name desc", new String[][] { {"Mary"},{"John"} } },
	{ "select distinct name from person order by upper(name)", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name from person order by age*2", new String[][] { {"John"},{"Mary"},{"John"} } },   // Very odd case, but legal
	{ "select distinct name as first_name from person order by name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as first_name from person order by first_name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct person.name from person order by name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as first_name from person order by person.name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as age from person order by age", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name as age from person order by person.age", "42879" },
	{ "select distinct name, name from person order by name", new String[][] { {"John","John"},{"Mary","Mary"} } },
	{ "select distinct name, name as first_name from person order by name", new String[][] { {"John","John"},{"Mary","Mary"} } },
	{ "select distinct name, name as first_name from person order by 2", new String[][] { {"John","John"},{"Mary","Mary"} } },
	{ "select distinct name nm from person p order by name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name nm from person p order by nm", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name nm from person p order by p.name", new String[][] { {"John"},{"Mary"} } },
	{ "select distinct name nm from person p order by person.name", "42X04" },
	{ "select distinct name nm from person p order by person.nm", "42X04" },
	{ "select distinct name nm from person p order by p.nm", "42X04" },
	{ "create table pets (name varchar(10), age int)"+ getOffHeapSuffix(), null },
	{ "insert into pets values ('Rover', 3), ('Fido', 5), ('Buster', 1)",  null},
	{ "select distinct name from person union select distinct name from pets order by name", new String[][] {
		{"Buster"},{"Fido"},{"John"},{"Mary"},{"Rover"} } },
	{ "select distinct name from person, pets order by name", "42X03" },
	{ "select distinct person.name as person_name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "select distinct person.name as person_name, pets.name from person,pets order by name", "42X79" },
	{ "select distinct person.name as person_name, pets.name from person,pets order by person.name", new String[][] {
		{"John","Buster"},{"John","Fido"},{"John","Rover"},
		{"Mary","Buster"},{"Mary","Fido"},{"Mary","Rover"} } },
	{ "select distinct person.name as name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "select distinct person.name as name, pets.name as pet_name from person,pets order by pets.name", new String[][] {
		{"John","Buster"},{"Mary","Buster"},{"John","Fido"},
		{"Mary","Fido"},{"John","Rover"},{"Mary","Rover"} } },
	{ "select name from person, pets order by name", "42X03" },
	{ "select person.name as person_name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "select person.name as person_name, pets.name from person,pets order by name", "42X79" },
	{ "select person.name as name, pets.name as pet_name from person,pets order by name", "42X79" },
	{ "drop table person", null },
	{ "drop table pets", null },
	{ "create table d2352 (c int)"+ getOffHeapSuffix(), null },
	{ "insert into d2352 values (1), (2), (3)", null },
	{ "select substr('abc', 1) from d2352 order by substr('abc', 1)", new String[][] { {"abc"},{"abc"},{"abc"} } },
	{ "select substr('abc', 1) from d2352 group by substr('abc', 1)", new String[][] { {"abc"} } },
	{ "select ltrim('abc') from d2352 order by ltrim('abc')", new String[][] { {"abc"},{"abc"},{"abc"} } },
	{ "select ltrim('abc') from d2352 group by ltrim('abc')", new String[][] { {"abc"} } },
	{ "select trim(trailing ' ' from 'abc') from d2352 order by trim(trailing ' ' from 'abc')", new String[][] { {"abc"},{"abc"},{"abc"} } },
	{ "select trim(trailing ' ' from 'abc') from d2352 group by trim(trailing ' ' from 'abc')", new String[][] { {"abc"} } },
	{ "drop table d2352", null },
	{ "create table d3303 (i int, j int, k int)"+ getOffHeapSuffix(), null },
	{ "insert into d3303 values (1, 1, 2), (1, 3, 3), (2, 3, 1), (2, 2, 4)", null },
	{ "select sum(j) as s from d3303 group by i order by 1", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by s", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by s desc", new String[][] { {"5"},{"4"} } },
	{ "select sum(j) as s from d3303 group by i order by abs(1), s", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by sum(k), s desc", new String[][] { {"5"},{"4"} } },
	{ "select sum(j) as s from d3303 group by k order by abs(k) desc", new String[][] { {"2"},{"3"},{"1"},{"3"} } },
	{ "select sum(j) as s from d3303 group by k order by abs(k) asc", new String[][] { {"3"},{"1"},{"3"},{"2"} } },
	{ "select sum(j) as s from d3303 group by i order by abs(i)", new String[][] { {"4"},{"5"} } },
	{ "select sum(j) as s from d3303 group by i order by abs(i) desc", new String[][] { {"5"},{"4"} } },
	{ "select distinct sum(j) as s from d3303 group by i", new String[][] { {"4"},{"5"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) asc", new String[][] {
		{"2","3","1"},{"1","1","1"},{"2","2","2"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) desc", new String[][] {
		{"2","2","2"},{"2","3","1"},{"1","1","1"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) desc, m2 asc", new String[][] {
		{"2","2","2"},{"1","1","1"},{"2","3","1"} } },
	{ "select max(i) as m1, max(j) as m2, sum(k) - max(j) as mdiff from d3303 group by j order by abs(sum(k) - max(j)) desc, m2 desc", new String[][] {
		{"2","2","2"},{"2","3","1"},{"1","1","1"} } },
	{ "select d3303.i as old_i, sum(d3303.k), d3303.* from d3303 group by k, i, j order by j", new String[][] {
		{"1","2","1","1","2"},		{"2","4","2","2","4"},	{"1","3","1","3","3"},		{"2","1","2","3","1"} } },
	{ "select d3303.i as old_i, sum(d3303.k), d3303.* from d3303 group by k, i, j order by 4", new String[][] {
		{"1","2","1","1","2"},		{"2","4","2","2","4"},	{"1","3","1","3","3"},		{"2","1","2","3","1"} } },
	{ "select d3303.i as old_i, sum(d3303.k), d3303.* from d3303 group by k, i, j order by k+2", new String[][] {
		{"2","1","2","3","1"},
		{"1","2","1","1","2"},
		{"1","3","1","3","3"},
		{"2","4","2","2","4"} } },
	{ "select k as s from d3303 order by 2", "42X77" },
	{ "select sum(k) as s from d3303 group by i order by 2", "42X77" },
	{ "select k from d3303 group by i,k order by 2", "42X77" },
	{ "select k as s from d3303 group by i,k order by 2", "42X77" },
	{ "drop table d3303", null }
    };

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,Script_OrderbyUTPartitioning);

  }
  
  protected String getOffHeapSuffix() {
    return " ";
  }
}
