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

import java.sql.Connection;
import java.sql.Statement;

import com.pivotal.gemfirexd.TestUtil;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;

public class LangScripts_LOBTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(LangScripts_LOBTest.class));
  }
  
  public LangScripts_LOBTest(String name) {
    super(name); 
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  // This test is the as-is LangScript conversion, without any partitioning clauses
  public void testLangScript_LOB_NoPartitioning() throws Exception {
    // This is a JUnit conversion of the Derby Lang LOB.sql script
    // without any GemFireXD extensions
	  
    // Catch exceptions from illegal syntax

    // Array of SQL text to execute and sqlstates to expect
    // The first object is a String, the second is either 
    // 1) null - this means query returns no rows and throws no exceptions
    // 2) a string - this means query returns no rows and throws expected SQLSTATE
    // 3) a String array - this means query returns rows which must match (unordered) given resultset
    //       - for an empty result set, an uninitialized size [0][0] array is used
    Object[][] Script_LOBUT = {
      // Can we create a table called 'blob', 'clob', or 'nclob'?
      { "create table blob(a int)" + getOffHeapSuffix(), null },
      { "insert into blob values(3)", null },
      { "select blob.a from blob", new String[][]{ {"3"} } },
      { "create table clob(a int)"+ getOffHeapSuffix(), null },
      { "insert into clob values(3)", null },
      { "select clob.a from clob", new String[][]{ {"3"} } },
      { "create table nclob(a int)"+ getOffHeapSuffix(), null },
      { "insert into nclob values(3)", null },
      { "select nclob.a from nclob", new String[][]{ {"3"} } },
      // Can we create a table with columns called 'blob', 'clob' or 'nclob'?
      { "create table a(blob int, clob int, nclob int)"+ getOffHeapSuffix(), null },
      { "insert into a values(1,2,3)", null },
      { "insert into a(blob, clob, nclob) values(1,2,3)", null },
      { "select a.blob, a.clob, a.nclob from a", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.blob=1", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.clob=2", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.nclob=3", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.blob=1 and a.clob=2 and a.nclob=3", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      // Test for column names in insert
      { "create table b(blob blob(3K), clob clob(2M))"+ getOffHeapSuffix(), null },
      { "insert into b values(cast(X'0031' as blob(3K)), cast('2' as clob(2M)))", null },
      { "insert into b(blob, clob, nclob) values(cast(X'0031' as blob(3K)), cast('2' as clob(2M)))", "42X14" },
      { "select b.blob, b.clob, b.nclob from b", "42X04" },
      // Equality tests not allowed
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e0' as blob(7))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e000' as blob(7))", "42818" },
      { "select 1 from b where X'80' = cast(X'80' as blob(1))", "42818" },
      { "select 1 from b where cast(X'80' as blob(1)) = X'80'", "42818" },
      { "select 1 from b where cast(X'80' as blob(1)) = cast(X'80' as blob(1))", "42818" },
      { "select 1 from b where '1' = cast('1' as clob(1))", new String[][] { { "1" } } },
      { "select 1 from b where cast('1' as clob(1)) = '1'", new String[][] { { "1" } } },
      { "select 1 from b where cast('1' as clob(1)) = cast('1' as clob(1))", new String[][] { { "1" } } },
      // NCLOB not supported
      { "select 1 from b where '1' = cast('1' as nclob(1))", "0A000" },
      { "select 1 from b where cast('1' as nclob(1)) = '1'", "0A000" },
      { "select 1 from b where cast('1' as nclob(1)) = cast('1' as nclob(1))", "0A000" },
      { "select 1 from b where cast('1' as nclob(10)) = cast('1' as clob(10))", "0A000" },
      { "select 1 from b where cast('1' as clob(10)) = cast('1' as nclob(10))", "0A000" },
      { "select * from b as b1, b as b2 where b1.blob=b2.blob", "42818" },
      { "select * from b as b1, b as b2 where b1.blob!=b2.blob", "42818" },
      { "select * from b as b1, b as b2 where b1.blob=X'20'", "42818" },
      { "select * from b as b1, b as b2 where X'20'=b1.blob", "42818" },
      { "select * from b as b1, b as b2 where X'20'!=b1.blob", "42818" },
      { "select * from b as b1, b as b2 where b1.blob=X'7575'", "42818" },
      { "select * from b as b1, b as b2 where X'7575'=b1.blob", "42818" },
      { "select b.blob, b.clob, b.nclob from b where b.blob = '1' and b.clob = '2' and b.nclob = '3'", "42X04" },
      { "select b.blob from b where b.blob = '1'", "42818" }, 
      { "select b.clob from b where b.clob = '2'", new String[][] { { "2" } } },
      { "select b.nclob from b where b.nclob = '3'", "42X04" },
      // Test insert of null - missing nclob column as not supported though
      { "insert into b values (null, null, null)", "42802" },
      { "select * from b", new String[][] { {"0031","2"} } },   // char x31 is '1' from the blob
      { "drop table blob", null },
      { "drop table clob", null },
      { "drop table nclob", null },
      { "drop table a", null },
      { "drop table b", null },
      // Test insert limitations
      { "create table b(b blob(5))"+ getOffHeapSuffix(), null },
      { "create table c(c clob(5))"+ getOffHeapSuffix(), null },
      { "create table d(c string(5))"+ getOffHeapSuffix(), null },
      { "create table n(n nclob(5))"+ getOffHeapSuffix(), "0A000" },  // NCLOB not supported but tests included if it is ever supported, should succeed
      { "insert into b values(cast(X'01020304' as blob(10)))", null },
      { "insert into b values(cast(X'0102030405' as blob(10)))", null },
      { "insert into b values(cast(X'010203040506' as blob(10)))", "22001" },
      // Truncate before insert, no errors
      { "insert into b values(cast(X'01020304' as blob(5)))", null },
      { "insert into b values(cast(X'0102030405' as blob(5)))", null },
      { "insert into b values(cast(X'010203040506' as blob(5)))", null },
      // CLOB/NCLOB
      { "insert into c values('1234')", null },       // No cast necessary for char->clob
      { "insert into c values('12345')", null },    
      { "insert into c values('123456')", "22001" },    
      { "insert into n values('1234')", "42X05" },     
      { "insert into n values('12345')", "42X05" },    
      { "insert into n values('123456')", "42X05" },    
      { "insert into c values(cast('1234' as clob(5)))", null },    
      { "insert into c values(cast('12345' as clob(5)))", null },    
      { "insert into c values(cast('123456' as clob(5)))", null },
      { "insert into n values(cast('1234' as nclob(5)))", "0A000" },     // func-not-supported instead of table-not-found
      { "insert into d values('1234')", null },       // No cast necessary for char->clob
      { "insert into d values('12345')", null },
      { "insert into d values('123456')", "22001" },
      { "insert into d values(cast('1234' as string(5)))", null },
      { "insert into d values(cast('12345' as string(5)))", null },
      { "insert into d values(cast('123456' as string(5)))", null },
      { "insert into n values(cast('12345' as nclob(5)))", "0A000" },    
      { "insert into n values(cast('123456' as nclob(5)))", "0A000" },    
      { "select * from b", new String[][]{ {"01020304"},{"0102030405"},{"01020304"},{"0102030405"},{"0102030405"} } },
      { "select * from c", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from d", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from n", "42X05" },
      { "values cast('12' as clob(2)) || cast('34' as clob(2))", new String[][]{ {"1234"} } },
      { "values cast('12' as nclob(2)) || cast('34' as nclob(2))", "0A000" },
      { "select 1 from b where cast('12' as clob(2)) || cast('34' as clob(2)) = '1234'", new String[][] { { "1" }, { "1" }, { "1" }, { "1" }, { "1" } } },
      { "select 1 from b where cast('12' as nclob(2)) || cast('34' as nclob(2)) = '1234'", "0A000" },
      { "select 1 from b where cast('12' as clob(2)) || cast('34' as clob(2)) = cast('1234' as clob(4))", new String[][] { { "1" }, { "1" }, { "1" }, { "1" }, { "1" } } },
      { "select 1 from b where cast('12' as nclob(2)) || cast('34' as nclob(2)) = cast('1234' as nclob(4))", "0A000" },
      // LIKE
      { "select * from b where b like '0102%'", "42884" },
      { "select * from c where c like '12%'", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from d where c like '12%'", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from n where n like '12%'", "42X05" },
      { "select * from b where b like cast('0102%' as blob(10))", "42846" },
      { "select * from c where c like cast('12%' as clob(10))", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from c where c like cast('12%' as clob(10))", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from n where n like cast('12%' as nclob(10))", "0A000" },
      { "drop table b", null },
      { "drop table c", null },
      { "drop table d", null },
      { "drop table n", "42Y55" },
      // Test syntax of using long type names
      { "create table a(a binary large object(3K))"+ getOffHeapSuffix(), null },
      { "create table b(a character large object(3K))"+ getOffHeapSuffix(), null },
      { "create table c(a national character large object(3K))"+ getOffHeapSuffix(), "0A000" },
      { "create table d(a char large object(204K))"+ getOffHeapSuffix(), null },
      { "create table e(a string(204K))"+ getOffHeapSuffix(), null },
      // Create index (not allowed)
      { "create index ia on a(a)", "X0X67" },
      { "create index ib on b(a)", "42832" },
      { "create index ic on c(a)", "42Y55" },
      { "create index id on d(a)", "42832" },
      { "create index ie on e(a)", "42832" },
      { "drop table a", null },
      { "drop table c", "42Y55" },
      { "drop table d", null },
      { "drop table e", null },
      // Relops (not allowed)
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))!=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))<cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))>cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))<=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))>=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast('fish' as clob(5))=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))!=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))<cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))>cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))<=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))>=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as nclob(5))=cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))!=cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))<cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))>cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))<=cast('fish' as nclob(7))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))>=cast('fish' as nclob(7))", "0A000" },
      { "select 1 from b where cast('fish' as string(5))=cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))!=cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))<cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))>cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))<=cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))>=cast('fish' as string(5))", new String[0][0] },
      // Test operands on auto-cast
      { "create table testoperatorclob (colone clob(1K))"+ getOffHeapSuffix(), null },
      { "insert into testoperatorclob values (CAST('50' as clob(1K)))", null },
      { "insert into testoperatorclob values (CAST(cast('50' as varchar(80)) as clob(1K)))", null },
      { "select * from testoperatorclob", new String[][]{ {"50"},{"50"} } },
      { "create table testoperatorstring (colone string(1K))"+ getOffHeapSuffix(), null },
      { "insert into testoperatorstring values (CAST('50' as string(1K)))", null },
      { "insert into testoperatorstring values (CAST(cast('50' as varchar(80)) as string(1K)))", null },
      { "select * from testoperatorstring", new String[][]{ {"50"},{"50"} } },
      // These selects should raise an error (but DB2 autocasts)
      { "select * from testoperatorclob where colone > 10", "42818" },
      { "select * from testoperatorclob where colone > 5", "42818" },
      { "select * from testoperatorclob where colone < 70", "42818" },
      { "select * from testoperatorclob where colone = 50", "42818" },
      { "select * from testoperatorclob where colone != 10", "42818" },
      { "select * from testoperatorclob where colone <= 70", "42818" },
      { "select * from testoperatorclob where colone >= 10", "42818" },
      { "select * from testoperatorclob where colone <> 10", "42818" },
      { "select * from testoperatorstring where colone > 10", "42818" },
      { "select * from testoperatorclob where colone > '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone > '5'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone < '70'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone = '50'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone != '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone <= '70'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone >= '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone <> '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone > '10'", new String[][] { { "50" }, { "50" } } },
      { "drop table testoperatorclob", null },
      { "drop table testoperatorstring", null },
      // BLOB testing (inserts fail, otherwise results identical to clob)
      { "create table testoperatorblob (colone blob(1K))"+ getOffHeapSuffix(), null },
      { "insert into testoperatorblob values (CAST('50' as blob(1K)))", "42846" },
      { "insert into testoperatorblob values (CAST(cast('50' as varchar(80)) as blob(1K)))", "42846" },
      { "select * from testoperatorblob", new String[0][0] },  // empty result set
      // These selects should raise an error (but DB2 autocasts) - peer gets 22005 but thinclient gets 42818
      { "select * from testoperatorblob where colone > 10", "42818" },
      { "select * from testoperatorblob where colone > 5", "42818" },
      { "select * from testoperatorblob where colone < 99999", "42818" },
      { "select * from testoperatorblob where colone = 00350030", "42818" },
      { "select * from testoperatorblob where colone != 10", "42818" },
      { "select * from testoperatorblob where colone <= 99999", "42818" },
      { "select * from testoperatorblob where colone >= 10", "42818" },
      { "select * from testoperatorblob where colone <> 10", "42818" },
      { "select * from testoperatorblob where colone > '10'", "42818" },
      { "select * from testoperatorblob where colone > '5'", "42818" },
      { "select * from testoperatorblob where colone < '70'", "42818" },
      { "select * from testoperatorblob where colone = '50'", "42818" },
      { "select * from testoperatorblob where colone != '10'", "42818" },
      { "select * from testoperatorblob where colone <= '70'", "42818" },
      { "select * from testoperatorblob where colone >= '10'", "42818" },
      { "select * from testoperatorblob where colone <> '10'", "42818" },
      { "drop table testoperatorblob", null },
      // NCLOB testing
      { "create table testoperatornclob (colone nclob(1K))"+ getOffHeapSuffix(), "0A000" },
      { "insert into testoperatornclob values (CAST('50' as nclob(1K)))", "0A000" },
      { "insert into testoperatornclob values (CAST(cast('50' as varchar(80)) as nclob(1K)))", "0A000" },
      { "select * from testoperatornclob", "42X05" },  
      { "select * from testoperatornclob where colone > 10", "42X05" },
      { "select * from testoperatornclob where colone > 5", "42X05" },
      { "select * from testoperatornclob where colone < 70", "42X05" },
      { "select * from testoperatornclob where colone = 50", "42X05" },
      { "select * from testoperatornclob where colone != 10", "42X05" },
      { "select * from testoperatornclob where colone <= 70", "42X05" },
      { "select * from testoperatornclob where colone >= 10", "42X05" },
      { "select * from testoperatornclob where colone <> 10", "42X05" },
      { "select * from testoperatornclob where colone > '10'", "42X05" },
      { "select * from testoperatornclob where colone > '5'", "42X05" },
      { "select * from testoperatornclob where colone < '70'", "42X05" },
      { "select * from testoperatornclob where colone = '50'", "42X05" },
      { "select * from testoperatornclob where colone != '10'", "42X05" },
      { "select * from testoperatornclob where colone <= '70'", "42X05" },
      { "select * from testoperatornclob where colone >= '10'", "42X05" },
      { "select * from testoperatornclob where colone <> '10'", "42X05" },
      { "drop table testoperatornclob", "42Y55" },
      // Test method invocations on LOB (should disallow)
      { "drop table b", null },
      { "create table b(b blob(77))"+ getOffHeapSuffix(), null },
      { "insert into b values(cast('33' as blob(77)))", "42846" },
      { "create table c(c clob(77))"+ getOffHeapSuffix(), null },
      { "create table d(c string(77))"+ getOffHeapSuffix(), null },
      { "insert into c values(cast('33' as clob(77)))", null },
      { "insert into d values(cast('33' as string(77)))", null },
      { "values (cast('1' as blob(1M)))->toString()", "42846" },
      { "values (cast('1' as clob(1M)))->toString()", "42X01" },
      { "values (cast('1' as nclob(1M)))->toString()", "0A000" },
      { "values (cast('1' as string(1M)))->toString()", "42X01" },
      { "drop table b", null },
      { "drop table c", null },
      { "drop table d", null },
      // Test length functions on LOBs
      { "values length(cast('foo' as blob(10)))", "42846" },
      { "values {fn length(cast('foo' as blob(10)))}", "42846" },
      { "values length(cast('foo' as char(10)))", new String[][]{ {"10"} } },
      { "values {fn length(cast('foo' as char(10)))}", new String[][]{ {"3"} } },  // fn length is notrailingblanks
      { "values length(cast('foo' as clob(10)))", new String[][]{ {"3"} } },       // clobs have no trailingblanks
      { "values {fn length(cast('foo' as clob(10)))}", new String[][]{ {"3"} } },  // fn length is notrailingblanks
      { "values length(cast('foo' as string(10)))", new String[][]{ {"3"} } },       // clobs have no trailingblanks
      { "values {fn length(cast('foo' as string(10)))}", new String[][]{ {"3"} } },  // fn length is notrailingblanks
      { "values length(cast('foo' as nclob(10)))", "0A000" },      
      { "values {fn length(cast('foo' as nclob(10)))}", "0A000" },  
      // Long Varchar negative tests
      { "create table testPredicate1 (c1 long varchar)"+ getOffHeapSuffix(), null },
      { "create table testPredicate2 (c1 long varchar)"+ getOffHeapSuffix(), null },
      { "insert into testPredicate1 (c1) values 'a'", null },
      { "insert into testPredicate2 (c1) values 'a'", null },
      { "select * from testPredicate1 union select * from testPredicate2", new String[][]{ {"a"} } },  //GFXD allows LONG VARCHAR in UNION statements
      { "select c1 from testPredicate1 where c1 in (select c1 from testPredicate2)", new String[][]{ {"a"} } },
      { "select c1 from testPredicate1 where c1 not in (select c1 from testPredicate2)", new String[0][0] },
      { "select * from testPredicate1 order by c1", new String[][]{ {"a"} } },   // GFXD allows LV in ORDER BY
      { "select substr(c1,1,2) from testPredicate1 group by c1", new String[][]{ {"a"} } },   // and in GROUP BY
      { "select * from testPredicate1 t1, testPredicate2 t2 where t1.c1=t2.c1", new String[][]{ { "a", "a" } } },
      { "select * from testPredicate1 left outer join testPredicate2 on testPredicate1.c1=testPredicate2.c1", new String[][]{ { "a", "a" } } },
      { "create table testConst1(c1 long varchar not null primary key)"+ getOffHeapSuffix(), null },    // GemFireXD allows this!
      { "create table testConst2(col1 long varchar not null, constraint uk unique(col1))"+ getOffHeapSuffix(), null },
      { "create table testConst3(c1 char(10) not null, primary key(c1))"+ getOffHeapSuffix(), null },
      { "create table testConst4(c1 long varchar not null, constraint fk foreign key(c1) references testConst3(c1))"+ getOffHeapSuffix(), "X0Y44" },
      { "drop table testConst3", null },
      { "select max(c1) from testPredicate1", new String[][]{ {"a"} } },  // GemFireXD allows aggregation over LV
      { "select min(c1) from testPredicate1", new String[][]{ {"a"} } },
      { "drop table testPredicate1", null },
      { "drop table testPredicate2", null },
      // Test LOB limits and sizes
      { "create table DB2LIM.FB1(FB1C BLOB(3G))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FB2(FB2C BLOB(2049M))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FB3(FB3C BLOB(2097153K))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FB4(FB4C BLOB(2147483648))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.GB1(GB1C BLOB(2G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB2(GB2C BLOB(2048M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB3(GB3C BLOB(2097152K))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB4(GB4C BLOB(2147483647))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB5(GB5C BLOB(1G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB6(GB6C BLOB(2047M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB7(GB7C BLOB(20971521))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB8(GB8C BLOB(2147483646))"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GB5", null },
      { "drop table DB2LIM.GB6", null },
      { "drop table DB2LIM.GB7", null },
      { "drop table DB2LIM.GB8", null },
      { "create table DB2LIM.GB9(GB9C BLOB)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB10(GB10C BINARY LARGE OBJECT)"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GB9", null },
      { "drop table DB2LIM.GB10", null },
      { "create table DB2LIM.FC1(FC1C CLOB(3G))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC2(FC2C CLOB(2049M))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC3(FC3C CLOB(2097153K))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC4(FC4C CLOB(2147483648))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC1(FC1C STRING(3G))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC2(FC2C STRING(2049M))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC3(FC3C STRING(2097153K))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC4(FC4C STRING(2147483648))"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.GC1(GC1C CLOB(2G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC2(GC2C CLOB(2048M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC3(GC3C CLOB(2097152K))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC4(GC4C CLOB(2147483647))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC5(GC5C CLOB(1G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC6(GC6C CLOB(2047M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC7(GC7C CLOB(2097151K))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC8(GC8C CLOB(2147483646))"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GC5", null },
      { "drop table DB2LIM.GC6", null },
      { "drop table DB2LIM.GC7", null },
      { "drop table DB2LIM.GC8", null },
      { "create table DB2LIM.GC9(GC9C CLOB)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC10(GC10C CHARACTER LARGE OBJECT)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC11(GC11C CHAR LARGE OBJECT)"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GC9", null },
      { "drop table DB2LIM.GC10", null },
      { "drop table DB2LIM.GC11", null },
      // Catalog check
      { "SELECT CAST (TABLENAME AS CHAR(10)) AS T, CAST (COLUMNNAME AS CHAR(10)) AS C, "
        +"  CAST (COLUMNDATATYPE AS CHAR(30)) AS Y FROM SYS.SYSTABLES T, SYS.SYSSCHEMAS S, SYS.SYSCOLUMNS C "
        +"  WHERE S.SCHEMAID = T.SCHEMAID AND CAST(S.SCHEMANAME AS VARCHAR(128))= 'DB2LIM'" 
        +"  AND C.REFERENCEID = T.TABLEID ORDER BY 1",
        new String[][]{   {"GB1","GB1C","BLOB(2147483647)"},
                          {"GB2","GB2C","BLOB(2147483647)"},
                          {"GB3","GB3C","BLOB(2147483647)"},
                          {"GB4","GB4C","BLOB(2147483647)"},
                          {"GC1","GC1C","CLOB(2147483647)"},
                          {"GC2","GC2C","CLOB(2147483647)"},
                          {"GC3","GC3C","CLOB(2147483647)"},
                          {"GC4","GC4C","CLOB(2147483647)"} } },
      { "create table DB2LIM2.SGC1(GC1C STRING(2G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC2(GC2C STRING(2048M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC3(GC3C STRING(2097152K))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC4(GC4C STRING(2147483647))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC5(GC5C STRING(1G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC6(GC6C STRING(2047M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC7(GC7C STRING(2097151K))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC8(GC8C STRING(2147483646))"+ getOffHeapSuffix(), null },
      // Catalog check
      { "SELECT CAST (TABLENAME AS CHAR(10)) AS T, CAST (COLUMNNAME AS CHAR(10)) AS C, "
        +"  CAST (COLUMNDATATYPE AS CHAR(30)) AS Y FROM SYS.SYSTABLES T, SYS.SYSSCHEMAS S, SYS.SYSCOLUMNS C "
        +"  WHERE S.SCHEMAID = T.SCHEMAID AND CAST(S.SCHEMANAME AS VARCHAR(128))= 'DB2LIM2'"
        +"  AND C.REFERENCEID = T.TABLEID ORDER BY 1",
        new String[][]{   {"SGC1","GC1C","CLOB(2147483647)"},
                          {"SGC2","GC2C","CLOB(2147483647)"},
                          {"SGC3","GC3C","CLOB(2147483647)"},
                          {"SGC4","GC4C","CLOB(2147483647)"},
                          {"SGC5","GC5C","CLOB(1073741824)"},
                          {"SGC6","GC6C","CLOB(2146435072)"},
                          {"SGC7","GC7C","CLOB(2147482624)"},
                          {"SGC8","GC8C","CLOB(2147483646)"} } },
      { "create table b (colone blob(1K))"+ getOffHeapSuffix(), null },
      { "insert into b values '50'", "42821" },
      { "insert into b values cast('50' as varchar(80))", "42821" },
      { "values (cast('50' as blob(1K)))", "42846" },
      { "insert into b values (cast('50' as blob(1K)))", "42846" },
      { "values (cast(cast('50' as varchar(80)) as blob(1K)))", "42846" },
      { "insert into b values (cast(cast('50' as varchar(80)) as blob(1K)))", "42846" },
      { "insert into b values (cast('50' as long varchar))", "42821" },
      { "values cast('50' as blob(1K))", "42846" },
      { "insert into b values (cast('50' as blob(1K)))", "42846" },
      { "values cast('50' as clob(1K))", new String[][]{ {"50"} } },
      { "values cast('50' as string(1K))", new String[][]{ {"50"} } },
      { "insert into b values (cast('50' as clob(1K)))", "42821" },
      { "insert into b values (cast('50' as string(1K)))", "42821" },
      { "values cast('50' as nclob(1K))", "0A000" },
      { "insert into b values (cast('50' as nclob(1K)))", "0A000" },
      { "drop table b", null },
      { "create table c (colone clob(1K))"+ getOffHeapSuffix(), null },
      { "insert into c values '50'", null },
      { "insert into c values cast('50' as varchar(80))", null },
      { "insert into c values cast('50' as clob(1K))", null },
      { "values (CAST(cast('50' as varchar(80)) as clob(1K)))", new String[][]{ {"50"} } },
      { "values cast('50' as long varchar)", new String[][]{ {"50"} } },
      { "insert into c values cast('50' as long varchar)", null },
      { "values (cast('50' as blob(1K)))", "42846" },
      { "insert into c values (cast('50' as blob(1K)))", "42846" },
      { "insert into c values (cast('50' as clob(1K)))", null },
      { "values (cast('50' as nclob(1K)))", "0A000" },
      { "insert into c values (cast('50' as nclob(1K)))", "0A000" },
      { "drop table c", null },
      { "create table c (colone string(1K))"+ getOffHeapSuffix(), null },
      { "insert into c values '50'", null },
      { "insert into c values cast('50' as varchar(80))", null },
      { "insert into c values cast('50' as  string(1K))", null },
      { "values (CAST(cast('50' as varchar(80)) as  string(1K)))", new String[][]{ {"50"} } },
      { "values cast('50' as long varchar)", new String[][]{ {"50"} } },
      { "insert into c values cast('50' as long varchar)", null },
      { "values (cast('50' as blob(1K)))", "42846" },
      { "insert into c values (cast('50' as  string(1K)))", null },
      { "drop table c", null },
      { "create table c (colone string)"+ getOffHeapSuffix(), null },
      { "insert into c values '50'", null },
      { "insert into c values cast('50' as varchar(80))", null },
      { "insert into c values cast('50' as string)", null },
      { "values (CAST(cast('50' as varchar(80)) as string))", new String[][]{ {"50"} } },
      { "values cast('50' as long varchar)", new String[][]{ {"50"} } },
      { "insert into c values cast('50' as long varchar)", null },
      { "insert into c values (cast('50' as string))", null },
      { "drop table c", null }
    };

    // Force replication as default, not partitioning
    // (Some results are expected to be different with partitioning, will test seperately)
    skipDefaultPartitioned = true;

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,Script_LOBUT);
  }
  
  //This test is the script enhanced with partitioning
  public void testLangScript_LOB_WithPartitioning() throws Exception {
    // This test has to do with LOBs and is not very extendable for partitioning
    // Simply add column partitioning on all tables
    Object[][] Script_LOBUT_WithPartitioning = {
      // Can we create a table called 'blob', 'clob', or 'nclob'?
      { "create table blob(a int) partition by column(a)"+ getOffHeapSuffix(), null },
      { "insert into blob values(3)", null },
      { "select blob.a from blob", new String[][]{ {"3"} } },
      { "create table clob(a int) partition by column(a)"+ getOffHeapSuffix(), null },
      { "insert into clob values(3)", null },
      { "select clob.a from clob", new String[][]{ {"3"} } },
      { "create table nclob(a int) partition by column(a)"+ getOffHeapSuffix(), null },
      { "insert into nclob values(3)", null },
      { "select nclob.a from nclob", new String[][]{ {"3"} } },
      // Can we create a table with columns called 'blob', 'clob' or 'nclob'?
      { "create table a(blob int, clob int, nclob int) partition by column(blob)"+ getOffHeapSuffix(), null },
      { "insert into a values(1,2,3)", null },
      { "insert into a(blob, clob, nclob) values(1,2,3)", null },
      { "select a.blob, a.clob, a.nclob from a", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.blob=1", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.clob=2", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.nclob=3", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      { "select a.blob, a.clob, a.nclob from a where a.blob=1 and a.clob=2 and a.nclob=3", new String[][]{ {"1","2","3"},{"1","2","3"} } },
      // Test for column names in insert
      { "create table b(blob blob(3K), clob clob(2M)) partition by column(blob)"+ getOffHeapSuffix(), null }, 
      { "insert into b values(cast(X'0031' as blob(3K)), cast('2' as clob(2M)))", null },
      { "insert into b(blob, clob, nclob) values(cast(X'0031' as blob(3K)), cast('2' as clob(2M)))", "42X14" },
      { "select b.blob, b.clob, b.nclob from b", "42X04" },
      // Equality tests not allowed
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e0' as blob(7))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e000' as blob(7))", "42818" },
      { "select 1 from b where X'80' = cast(X'80' as blob(1))", "42818" },
      { "select 1 from b where cast(X'80' as blob(1)) = X'80'", "42818" },
      { "select 1 from b where cast(X'80' as blob(1)) = cast(X'80' as blob(1))", "42818" },
      { "select 1 from b where '1' = cast('1' as clob(1))", new String[][] { { "1" } } },
      { "select 1 from b where cast('1' as clob(1)) = '1'", new String[][] { { "1" } } },
      { "select 1 from b where cast('1' as clob(1)) = cast('1' as clob(1))", new String[][] { { "1" } } },
      // NCLOB not supported
      { "select 1 from b where '1' = cast('1' as nclob(1))", "0A000" },
      { "select 1 from b where cast('1' as nclob(1)) = '1'", "0A000" },
      { "select 1 from b where cast('1' as nclob(1)) = cast('1' as nclob(1))", "0A000" },
      { "select 1 from b where cast('1' as nclob(10)) = cast('1' as clob(10))", "0A000" },
      { "select 1 from b where cast('1' as clob(10)) = cast('1' as nclob(10))", "0A000" },
      { "select * from b as b1, b as b2 where b1.blob=b2.blob", "42818" },
      { "select * from b as b1, b as b2 where b1.blob!=b2.blob", "42818" },
      { "select * from b as b1, b as b2 where b1.blob=X'20'", "42818" },
      { "select * from b as b1, b as b2 where X'20'=b1.blob", "42818" },
      { "select * from b as b1, b as b2 where X'20'!=b1.blob", "42818" },
      { "select * from b as b1, b as b2 where b1.blob=X'7575'", "42818" },
      { "select * from b as b1, b as b2 where X'7575'=b1.blob", "42818" },
      { "select b.blob, b.clob, b.nclob from b where b.blob = '1' and b.clob = '2' and b.nclob = '3'", "42X04" },
      { "select b.blob from b where b.blob = '1'", "42818" },
      { "select b.clob from b where b.clob = '2'", new String[][] { { "2" } } },
      { "select b.nclob from b where b.nclob = '3'", "42X04" },
      // Test insert of null - missing nclob column as not supported though
      { "insert into b values (null, null, null)", "42802" },
      { "select * from b", new String[][] { {"0031","2"} } },   // char x31 is '1' from the blob
      { "drop table blob", null },
      { "drop table clob", null },
      { "drop table nclob", null },
      { "drop table a", null },
      { "drop table b", null },
      // Test insert limitations
      { "create table b(b blob(5)) partition by column(b)"+ getOffHeapSuffix(), null },
      { "create table c(c clob(5)) partition by column(c)"+ getOffHeapSuffix(), null },
      { "create table d(c string(5))"+ getOffHeapSuffix(), null },
      { "create table n(n nclob(5))"+ getOffHeapSuffix(), "0A000" },  // NCLOB not supported but tests included if it is ever supported, should succeed
      { "insert into b values(cast(X'01020304' as blob(10)))", null },
      { "insert into b values(cast(X'0102030405' as blob(10)))", null },
      { "insert into b values(cast(X'010203040506' as blob(10)))", "22001" },
      // Truncate before insert, no errors
      { "insert into b values(cast(X'01020304' as blob(5)))", null },
      { "insert into b values(cast(X'0102030405' as blob(5)))", null },
      { "insert into b values(cast(X'010203040506' as blob(5)))", null },
      // CLOB/NCLOB
      { "insert into c values('1234')", null },       // No cast necessary for char->clob
      { "insert into c values('12345')", null },    
      { "insert into c values('123456')", "22001" },    
      { "insert into n values('1234')", "42X05" },     
      { "insert into n values('12345')", "42X05" },    
      { "insert into n values('123456')", "42X05" },    
      { "insert into c values(cast('1234' as clob(5)))", null },    
      { "insert into c values(cast('12345' as clob(5)))", null },    
      { "insert into c values(cast('123456' as clob(5)))", null },    
      { "insert into n values(cast('1234' as nclob(5)))", "0A000" },     // func-not-supported instead of table-not-found
      { "insert into d values('1234')", null },       // No cast necessary for char->clob
      { "insert into d values('12345')", null },
      { "insert into d values('123456')", "22001" },
      { "insert into d values(cast('1234' as string(5)))", null },
      { "insert into d values(cast('12345' as string(5)))", null },
      { "insert into d values(cast('123456' as string(5)))", null },
      { "insert into n values(cast('12345' as nclob(5)))", "0A000" },    
      { "insert into n values(cast('123456' as nclob(5)))", "0A000" },    
      { "select * from b", new String[][]{ {"01020304"},{"0102030405"},{"01020304"},{"0102030405"},{"0102030405"} } },
      { "select * from c", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from d", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from n", "42X05" },
      { "values cast('12' as clob(2)) || cast('34' as clob(2))", new String[][]{ {"1234"} } },
      { "values cast('12' as nclob(2)) || cast('34' as nclob(2))", "0A000" },
      { "select 1 from b where cast('12' as clob(2)) || cast('34' as clob(2)) = '1234'", new String[][] { { "1" }, { "1" }, { "1" }, { "1" }, { "1" } } },
      { "select 1 from b where cast('12' as nclob(2)) || cast('34' as nclob(2)) = '1234'", "0A000" },
      { "select 1 from b where cast('12' as clob(2)) || cast('34' as clob(2)) = cast('1234' as clob(4))", new String[][] { { "1" }, { "1" }, { "1" }, { "1" }, { "1" } } },
      { "select 1 from b where cast('12' as nclob(2)) || cast('34' as nclob(2)) = cast('1234' as nclob(4))", "0A000" },
      // LIKE
      { "select * from b where b like '0102%'", "42884" },
      { "select * from c where c like '12%'", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from n where n like '12%'", "42X05" },
      { "select * from b where b like cast('0102%' as blob(10))", "42846" },
      { "select * from c where c like cast('12%' as clob(10))", new String[][]{ {"1234"},{"12345"},{"1234"},{"12345"},{"12345"} } },
      { "select * from n where n like cast('12%' as nclob(10))", "0A000" },
      { "drop table b", null },
      { "drop table c", null },
      { "drop table d", null },
      { "drop table n", "42Y55" },
      // Test syntax of using long type names
      { "create table a(a binary large object(3K)) partition by column(a)"+ getOffHeapSuffix(), null },
      { "create table b(a character large object(3K)) partition by column(a)"+ getOffHeapSuffix(), null },
      { "create table c(a national character large object(3K))"+ getOffHeapSuffix(), "0A000" },
      { "create table d(a char large object(204K)) partition by column(a)"+ getOffHeapSuffix(), null },
      { "create table e(a string(204K)) partition by column(a)"+ getOffHeapSuffix(), null },
      // Create index (not allowed)
      { "create index ia on a(a)", "X0X67" },
      { "create index ib on b(a)", "42832" },
      { "create index ic on c(a)", "42Y55" },
      { "create index id on d(a)", "42832" },
      { "create index ie on e(a)", "42832" },
      { "drop table a", null },
      { "drop table c", "42Y55" },
      { "drop table d", null },
      { "drop table e", null },
      // Relops (not allowed)
      { "select 1 from b where cast(X'e0' as blob(5))=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))!=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))<cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))>cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))<=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast(X'e0' as blob(5))>=cast(X'e0' as blob(5))", "42818" },
      { "select 1 from b where cast('fish' as clob(5))=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))!=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))<cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))>cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))<=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as clob(5))>=cast('fish' as clob(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as nclob(5))=cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))!=cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))<cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))>cast('fish' as nclob(5))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))<=cast('fish' as nclob(7))", "0A000" },
      { "select 1 from b where cast('fish' as nclob(5))>=cast('fish' as nclob(7))", "0A000" },
      { "select 1 from b where cast('fish' as string(5))=cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))!=cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))<cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))>cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))<=cast('fish' as string(5))", new String[0][0] },
      { "select 1 from b where cast('fish' as string(5))>=cast('fish' as string(5))", new String[0][0] },
      // Test operands on auto-cast
      { "create table testoperatorclob (colone clob(1K)) partition by column(colone)"+ getOffHeapSuffix(), null },
      { "insert into testoperatorclob values (CAST('50' as clob(1K)))", null },
      { "insert into testoperatorclob values (CAST(cast('50' as varchar(80)) as clob(1K)))", null },
      { "select * from testoperatorclob", new String[][]{ {"50"},{"50"} } },
      // These selects should raise an error (but DB2 autocasts)
      { "select * from testoperatorclob where colone > 10", "42818" },
      { "select * from testoperatorclob where colone > 5", "42818" },
      { "select * from testoperatorclob where colone < 70", "42818" },
      { "select * from testoperatorclob where colone = 50", "42818" },
      { "select * from testoperatorclob where colone != 10", "42818" },
      { "select * from testoperatorclob where colone <= 70", "42818" },
      { "select * from testoperatorclob where colone >= 10", "42818" },
      { "select * from testoperatorclob where colone <> 10", "42818" },
      { "select * from testoperatorclob where colone > '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone > '5'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone < '70'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone = '50'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone != '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone <= '70'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone >= '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone <> '10'", new String[][] { { "50" }, { "50" } } },
      { "select * from testoperatorclob where colone > '10'", new String[][] { { "50" }, { "50" } } },
      { "drop table testoperatorclob", null },
      // BLOB testing (inserts fail, otherwise results identical to clob)
      { "create table testoperatorblob (colone blob(1K)) partition by column(colone)"+ getOffHeapSuffix(), null },
      { "insert into testoperatorblob values (CAST('50' as blob(1K)))", "42846" },
      { "insert into testoperatorblob values (CAST(cast('50' as varchar(80)) as blob(1K)))", "42846" },
      { "select * from testoperatorblob", new String[0][0] },  // empty result set
      // These selects should raise an error (but DB2 autocasts) - peer gets 22005 but thinclient gets 42818
      { "select * from testoperatorblob where colone > 10", "42818" },
      { "select * from testoperatorblob where colone > 5", "42818" },
      { "select * from testoperatorblob where colone < 99999", "42818" },
      { "select * from testoperatorblob where colone = 00350030", "42818" },
      { "select * from testoperatorblob where colone != 10", "42818" },
      { "select * from testoperatorblob where colone <= 99999", "42818" },
      { "select * from testoperatorblob where colone >= 10", "42818" },
      { "select * from testoperatorblob where colone <> 10", "42818" },
      { "select * from testoperatorblob where colone > '10'", "42818" },
      { "select * from testoperatorblob where colone > '5'", "42818" },
      { "select * from testoperatorblob where colone < '70'", "42818" },
      { "select * from testoperatorblob where colone = '50'", "42818" },
      { "select * from testoperatorblob where colone != '10'", "42818" },
      { "select * from testoperatorblob where colone <= '70'", "42818" },
      { "select * from testoperatorblob where colone >= '10'", "42818" },
      { "select * from testoperatorblob where colone <> '10'", "42818" },
      { "drop table testoperatorblob", null },
      // NCLOB testing
      { "create table testoperatornclob (colone nclob(1K))"+ getOffHeapSuffix(), "0A000" },
      { "insert into testoperatornclob values (CAST('50' as nclob(1K)))", "0A000" },
      { "insert into testoperatornclob values (CAST(cast('50' as varchar(80)) as nclob(1K)))", "0A000" },
      { "select * from testoperatornclob", "42X05" },  
      { "select * from testoperatornclob where colone > 10", "42X05" },
      { "select * from testoperatornclob where colone > 5", "42X05" },
      { "select * from testoperatornclob where colone < 70", "42X05" },
      { "select * from testoperatornclob where colone = 50", "42X05" },
      { "select * from testoperatornclob where colone != 10", "42X05" },
      { "select * from testoperatornclob where colone <= 70", "42X05" },
      { "select * from testoperatornclob where colone >= 10", "42X05" },
      { "select * from testoperatornclob where colone <> 10", "42X05" },
      { "select * from testoperatornclob where colone > '10'", "42X05" },
      { "select * from testoperatornclob where colone > '5'", "42X05" },
      { "select * from testoperatornclob where colone < '70'", "42X05" },
      { "select * from testoperatornclob where colone = '50'", "42X05" },
      { "select * from testoperatornclob where colone != '10'", "42X05" },
      { "select * from testoperatornclob where colone <= '70'", "42X05" },
      { "select * from testoperatornclob where colone >= '10'", "42X05" },
      { "select * from testoperatornclob where colone <> '10'", "42X05" },
      { "drop table testoperatornclob", "42Y55" },
      // Test method invocations on LOB (should disallow)
      { "drop table b", null },
      { "create table b(b blob(77)) partition by column(b)"+ getOffHeapSuffix(), null },
      { "insert into b values(cast('33' as blob(77)))", "42846" },
      { "create table c(c clob(77)) partition by column(c)"+ getOffHeapSuffix(), null },
      { "insert into c values(cast('33' as clob(77)))", null },
      { "values (cast('1' as blob(1M)))->toString()", "42846" },
      { "values (cast('1' as clob(1M)))->toString()", "42X01" },
      { "values (cast('1' as nclob(1M)))->toString()", "0A000" },
      { "values (cast('1' as string(1M)))->toString()", "42X01" },
      { "drop table b", null },
      { "drop table c", null },
      // Test length functions on LOBs
      { "values length(cast('foo' as blob(10)))", "42846" },
      { "values {fn length(cast('foo' as blob(10)))}", "42846" },
      { "values length(cast('foo' as char(10)))", new String[][]{ {"10"} } },
      { "values {fn length(cast('foo' as char(10)))}", new String[][]{ {"3"} } },  // fn length is notrailingblanks
      { "values length(cast('foo' as clob(10)))", new String[][]{ {"3"} } },       // clobs have no trailingblanks
      { "values {fn length(cast('foo' as clob(10)))}", new String[][]{ {"3"} } },  // fn length is notrailingblanks
      { "values length(cast('foo' as nclob(10)))", "0A000" },      
      { "values {fn length(cast('foo' as nclob(10)))}", "0A000" },  
      // Long Varchar negative tests
      { "create table testPredicate1 (c1 long varchar) partition by column(c1)"+ getOffHeapSuffix(), null },
      { "create table testPredicate2 (c1 long varchar) partition by column(c1)"+ getOffHeapSuffix(), null },
      { "insert into testPredicate1 (c1) values 'a'", null },
      { "insert into testPredicate2 (c1) values 'a'", null },
      { "select * from testPredicate1 union select * from testPredicate2", new String[][]{ {"a"} } },  //GFXD allows LONG VARCHAR in UNION statements
      { "select c1 from testPredicate1 where c1 in (select c1 from testPredicate2)", new String[][]{ {"a"} } },
      { "select c1 from testPredicate1 where c1 not in (select c1 from testPredicate2)", new String[0][0] },
      { "select * from testPredicate1 order by c1", new String[][]{ {"a"} } },   // GFXD allows LV in ORDER BY
      { "select substr(c1,1,2) from testPredicate1 group by c1", new String[][]{ {"a"} } },   // and in GROUP BY
      { "select * from testPredicate1 t1, testPredicate2 t2 where t1.c1=t2.c1", new String[][]{ { "a", "a" } } },
      { "select * from testPredicate1 left outer join testPredicate2 on testPredicate1.c1=testPredicate2.c1", new String[][]{ { "a", "a" } } },
      { "create table testConst1(c1 long varchar not null primary key) partition by primary key"+ getOffHeapSuffix(), null },    // GemFireXD allows this!
      { "create table testConst2(col1 long varchar not null, constraint uk unique(col1)) partition by column(col1)"+ getOffHeapSuffix(), null },
      { "create table testConst3(c1 char(10) not null, primary key(c1)) partition by primary key"+ getOffHeapSuffix(), null },
      { "create table testConst4(c1 long varchar not null, constraint fk foreign key(c1) references testConst3(c1)) partition by column(c1) colocate with (testconst3)"+ getOffHeapSuffix(), "X0Y92" },   // colocation error test added here
      { "drop table testConst3", null },
      { "select max(c1) from testPredicate1", new String[][]{ {"a"} } },  // GemFireXD allows aggregation over LV
      { "select min(c1) from testPredicate1", new String[][]{ {"a"} } },
      { "drop table testPredicate1", null },
      { "drop table testPredicate2", null },
      // Test LOB limits and sizes
      { "create table DB2LIM.FB1(FB1C BLOB(3G)) partition by column(FB1C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FB2(FB2C BLOB(2049M)) partition by column(FB2C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FB3(FB3C BLOB(2097153K)) partition by column(FB3C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FB4(FB4C BLOB(2147483648)) partition by column(FB4C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.GB1(GB1C BLOB(2G)) partition by column (GB1C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB2(GB2C BLOB(2048M)) partition by column(GB2C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB3(GB3C BLOB(2097152K)) partition by column(GB3C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB4(GB4C BLOB(2147483647)) partition by column(GB4C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB5(GB5C BLOB(1G)) partition by column(GB5C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB6(GB6C BLOB(2047M)) partition by column(GB6C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB7(GB7C BLOB(20971521)) partition by column(GB7C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB8(GB8C BLOB(2147483646)) partition by column(GB8C)"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GB5", null },
      { "drop table DB2LIM.GB6", null },
      { "drop table DB2LIM.GB7", null },
      { "drop table DB2LIM.GB8", null },
      { "create table DB2LIM.GB9(GB9C BLOB) partition by column(GB9C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GB10(GB10C BINARY LARGE OBJECT) partition by column(GB10C)"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GB9", null },
      { "drop table DB2LIM.GB10", null },
      { "create table DB2LIM.FC1(FC1C CLOB(3G)) partition by column(FC1C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC2(FC2C CLOB(2049M)) partition by column(FC2C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC3(FC3C CLOB(2097153K)) partition by column(FC3C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.FC4(FC4C CLOB(2147483648)) partition by column(FC4C)"+ getOffHeapSuffix(), "42X44" },
      { "create table DB2LIM.GC1(GC1C CLOB(2G)) partition by column(GC1C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC2(GC2C CLOB(2048M)) partition by column(GC2C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC3(GC3C CLOB(2097152K)) partition by column(GC3C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC4(GC4C CLOB(2147483647)) partition by column(GC4C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC5(GC5C CLOB(1G)) partition by column(GC5C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC6(GC6C CLOB(2047M)) partition by column(GC6C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC7(GC7C CLOB(2097151K)) partition by column(GC7C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC8(GC8C CLOB(2147483646)) partition by column(GC8C)"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GC5", null },
      { "drop table DB2LIM.GC6", null },
      { "drop table DB2LIM.GC7", null },
      { "drop table DB2LIM.GC8", null },
      { "create table DB2LIM.GC9(GC9C CLOB) partition by column(GC9C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC10(GC10C CHARACTER LARGE OBJECT) partition by column(GC10C)"+ getOffHeapSuffix(), null },
      { "create table DB2LIM.GC11(GC11C CHAR LARGE OBJECT) partition by column(GC11C)"+ getOffHeapSuffix(), null },
      { "drop table DB2LIM.GC9", null },
      { "drop table DB2LIM.GC10", null },
      { "drop table DB2LIM.GC11", null },
      // Catalog check
      { "SELECT CAST (TABLENAME AS CHAR(10)) AS T, CAST (COLUMNNAME AS CHAR(10)) AS C, "
        +"  CAST (COLUMNDATATYPE AS CHAR(30)) AS Y FROM SYS.SYSTABLES T, SYS.SYSSCHEMAS S, SYS.SYSCOLUMNS C "
        +"  WHERE S.SCHEMAID = T.SCHEMAID AND CAST(S.SCHEMANAME AS VARCHAR(128))= 'DB2LIM'" 
        +"  AND C.REFERENCEID = T.TABLEID ORDER BY 1",
        new String[][]{   {"GB1","GB1C","BLOB(2147483647)"},
                          {"GB2","GB2C","BLOB(2147483647)"},
                          {"GB3","GB3C","BLOB(2147483647)"},
                          {"GB4","GB4C","BLOB(2147483647)"},
                          {"GC1","GC1C","CLOB(2147483647)"},
                          {"GC2","GC2C","CLOB(2147483647)"},
                          {"GC3","GC3C","CLOB(2147483647)"},
                          {"GC4","GC4C","CLOB(2147483647)"} } },
      { "create table DB2LIM2.SGC1(GC1C STRING(2G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC2(GC2C STRING(2048M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC3(GC3C STRING(2097152K))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC4(GC4C STRING(2147483647))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC5(GC5C STRING(1G))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC6(GC6C STRING(2047M))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC7(GC7C STRING(2097151K))"+ getOffHeapSuffix(), null },
      { "create table DB2LIM2.SGC8(GC8C STRING(2147483646))"+ getOffHeapSuffix(), null },
      // Catalog check
      { "SELECT CAST (TABLENAME AS CHAR(10)) AS T, CAST (COLUMNNAME AS CHAR(10)) AS C, "
        +"  CAST (COLUMNDATATYPE AS CHAR(30)) AS Y FROM SYS.SYSTABLES T, SYS.SYSSCHEMAS S, SYS.SYSCOLUMNS C "
        +"  WHERE S.SCHEMAID = T.SCHEMAID AND CAST(S.SCHEMANAME AS VARCHAR(128))= 'DB2LIM2'"
        +"  AND C.REFERENCEID = T.TABLEID ORDER BY 1",
        new String[][]{   {"SGC1","GC1C","CLOB(2147483647)"},
                          {"SGC2","GC2C","CLOB(2147483647)"},
                          {"SGC3","GC3C","CLOB(2147483647)"},
                          {"SGC4","GC4C","CLOB(2147483647)"},
                          {"SGC5","GC5C","CLOB(1073741824)"},
                          {"SGC6","GC6C","CLOB(2146435072)"},
                          {"SGC7","GC7C","CLOB(2147482624)"},
                          {"SGC8","GC8C","CLOB(2147483646)"} } },
      { "create table b (colone blob(1K)) partition by column(colone)"+ getOffHeapSuffix(), null },
      { "insert into b values '50'", "42821" },
      { "insert into b values cast('50' as varchar(80))", "42821" },
      { "values (cast('50' as blob(1K)))", "42846" },
      { "insert into b values (cast('50' as blob(1K)))", "42846" },
      { "values (cast(cast('50' as varchar(80)) as blob(1K)))", "42846" },
      { "insert into b values (cast(cast('50' as varchar(80)) as blob(1K)))", "42846" },
      { "insert into b values (cast('50' as long varchar))", "42821" },
      { "values cast('50' as blob(1K))", "42846" },
      { "insert into b values (cast('50' as blob(1K)))", "42846" },
      { "values cast('50' as clob(1K))", new String[][]{ {"50"} } },
      { "insert into b values (cast('50' as clob(1K)))", "42821" },
      { "values cast('50' as nclob(1K))", "0A000" },
      { "insert into b values (cast('50' as nclob(1K)))", "0A000" },
      { "drop table b", null },
      { "create table c (colone clob(1K)) partition by column(colone)"+ getOffHeapSuffix(), null },
      { "insert into c values '50'", null },
      { "insert into c values cast('50' as varchar(80))", null },
      { "insert into c values cast('50' as clob(1K))", null },
      { "values (CAST(cast('50' as varchar(80)) as clob(1K)))", new String[][]{ {"50"} } },
      { "values cast('50' as long varchar)", new String[][]{ {"50"} } },
      { "insert into c values cast('50' as long varchar)", null },
      { "values (cast('50' as blob(1K)))", "42846" },
      { "insert into c values (cast('50' as blob(1K)))", "42846" },
      { "insert into c values (cast('50' as clob(1K)))", null },
      { "values (cast('50' as nclob(1K)))", "0A000" },
      { "insert into c values (cast('50' as nclob(1K)))", "0A000" },
      { "drop table c", null },
      { "create table c (colone string(1K))"+ getOffHeapSuffix(), null },
      { "insert into c values '50'", null },
      { "insert into c values cast('50' as varchar(80))", null },
      { "insert into c values cast('50' as  string(1K))", null },
      { "values (CAST(cast('50' as varchar(80)) as  string(1K)))", new String[][]{ {"50"} } },
      { "values cast('50' as long varchar)", new String[][]{ {"50"} } },
      { "insert into c values cast('50' as long varchar)", null },
      { "values (cast('50' as blob(1K)))", "42846" },
      { "insert into c values (cast('50' as  string(1K)))", null },
      { "drop table c", null },
      { "create table c (colone string)"+ getOffHeapSuffix(), null },
      { "insert into c values '50'", null },
      { "insert into c values cast('50' as varchar(80))", null },
      { "insert into c values cast('50' as string)", null },
      { "values (CAST(cast('50' as varchar(80)) as string))", new String[][]{ {"50"} } },
      { "values cast('50' as long varchar)", new String[][]{ {"50"} } },
      { "insert into c values cast('50' as long varchar)", null },
      { "insert into c values (cast('50' as string))", null },
      { "drop table c", null }
    };

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,Script_LOBUT_WithPartitioning);
  }
  
  protected String getOffHeapSuffix() {
    return "  ";
  }
}
