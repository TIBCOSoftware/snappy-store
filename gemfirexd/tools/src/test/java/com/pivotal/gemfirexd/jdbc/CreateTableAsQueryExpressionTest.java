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

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.xml.sax.SAXException;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.ddl.GfxdTestRowLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdRangePartitionResolver;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.tools.sysinfo;

public class CreateTableAsQueryExpressionTest extends JdbcTestBase {

  public CreateTableAsQueryExpressionTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(CreateTableAsQueryExpressionTest.class));
  }

  public void testCreateTableAsQueryExpression() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table t1(id int not null, cid varchar(20))");
    stmt.execute("create table t2 as select * from t1 with no data");
  }

  public void testCreateTableAsQueryExpressionRCGiven() throws SQLException {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table t1(id int not null, cid varchar(20))");
    stmt
        .execute("create table t2 (id_der, cid_der)as select * from t1 with no data");
  }

  private final String goldenTextFile = TestUtil.getResourcesDir()
      + "/lib/checkQuery.xml";

  private final String[] xmlOutPutTags_one = new String[] { "leftouterJoin_one", "rightouterJoin_one"};
  
  public void testOuterJoin() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table emp.EMPLOYEE(lastname varchar(30) primary key, depid int) replicate");
    s.execute("create table emp.DEPT1(deptname varchar(30), depid int primary key) partition by column (depid)");
    s.execute("create table emp.DEPT as select * from emp.dept1 with no data");
    s.execute("insert into emp.employee values "
        + "('Jones', 33), ('Rafferty', 31), "
        + "('Robinson', 34), ('Steinberg', 33), "
        + "('Smith', 34), ('John', null)");
    s.execute("insert into emp.dept values ('sales', 31), "
        + "('engineering', 33), ('clerical', 34), ('marketing', 35)");
    s.execute("SELECT *" + "  FROM emp.employee "
        + "LEFT OUTER JOIN emp.dept ON emp.employee.depid = emp.dept.depid");
    
    String jdbcSQL = "SELECT emp.Employee.LastName as lname, " +
      "emp.Employee.DepID as did1, " +
      "emp.Dept.DeptName as depname, " +
      "emp.Dept.DepID as did2" +
      "  FROM emp.employee  "
      + "LEFT OUTER JOIN emp.dept ON emp.employee.depID = emp.dept.DepID";
    
    TestUtil.sqlExecuteVerifyText(jdbcSQL, goldenTextFile, xmlOutPutTags_one[0], false, false);
    
    s.execute("create table emp.DEPT3 as select * from emp.dept with no data replicate");
    
    jdbcSQL = "select tablename, datapolicy from sys.systables where tableschemaname = 'EMP'";
    
    s.execute(jdbcSQL);
    
    ResultSet rs = s.getResultSet();

    TestUtil.sqlExecuteVerifyText(jdbcSQL, goldenTextFile, "datapolicy_createTableTableExpr", false, false);
  }

  public void testCreateTable() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Get the cache;
    Cache cache = CacheFactory.getAnyInstance();

    // Check for PARTITION BY COLUMN
    s.execute("create table EMP.PARTITIONTESTTABLE_BASE "
        + "(ID int not null,  SECONDID int not null, THIRDID int not null, "
        + "primary key (ID, SECONDID)) PARTITION BY COLUMN (ID)");

    s.execute("create table EMP.PARTITIONTESTTABLE "
        + "as select * from EMP.PARTITIONTESTTABLE_BASE with no data");

    s.execute("alter table EMP.PARTITIONTESTTABLE add primary key(ID, SECONDID)");

    s.execute("select tablename, datapolicy from sys.systables where tableschemaname = 'EMP'");
    
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    while(rs.next()) {
      cnt++;
      String datapolicy = rs.getString(2);
      assertTrue(datapolicy.equalsIgnoreCase("partition"));
    }
    assertEquals(2, cnt);
    
    Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");

    RegionAttributes rattr = regtwo.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();

    assertNotNull(pr);

    GfxdPartitionByExpressionResolver scpr = (GfxdPartitionByExpressionResolver)pr;

    assertNotNull(scpr);

    assertTrue(scpr.columnsSubsetOfPrimary());

    assertEquals(2, scpr.getColumnNames().length);

    assertEquals("ID", scpr.getColumnNames()[0]);

    assertEquals("SECONDID", scpr.getColumnNames()[1]);

    assertTrue(scpr.isPartitioningKeyThePrimaryKey());
  }

  public void testRangesWithExpression() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    // Get the cache;
    Cache cache = CacheFactory.getAnyInstance();

    // Check for PARTITION BY RANGE
    s.execute("create table EMP.PARTITIONTESTTABLE_BASE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))");

    s.execute("create table EMP.PARTITIONTESTTABLE "
        + "as select * from EMP.PARTITIONTESTTABLE_BASE with no data"
        + " PARTITION BY RANGE ( ID ) "
        + "( VALUES BETWEEN 20 and 40, VALUES BETWEEN 40 and 60, "
        + "VALUES BETWEEN 60 and 80, VALUES BETWEEN 90 and 100 )");

    s.execute("alter table EMP.PARTITIONTESTTABLE add primary key(ID)");

    s.execute("select tablename, datapolicy from sys.systables where tableschemaname = 'EMP'");
    
    ResultSet rs = s.getResultSet();
    int cnt = 0;
    while(rs.next()) {
      cnt++;
      String datapolicy = rs.getString(2);
      assertTrue(datapolicy.equalsIgnoreCase("partition"));
    }
    assertEquals(2, cnt);
    Region regtwo = cache.getRegion("/EMP/PARTITIONTESTTABLE");
    RegionAttributes rattr = regtwo.getAttributes();
    PartitionResolver pr = rattr.getPartitionAttributes()
        .getPartitionResolver();
    GfxdRangePartitionResolver rpr = (GfxdRangePartitionResolver)pr;
    assertNotNull(rpr);

    DataValueDescriptor lowerBound = null;
    DataValueDescriptor upperBound = null;
    Object[] routingObjects = null;
    // case 1 - all inclusive bounds well inside.
    // array should contain 1, 2 and 3
    lowerBound = new SQLInteger(25);
    upperBound = new SQLInteger(75);
    routingObjects = rpr.getRoutingObjectsForRange(lowerBound, true,
        upperBound, true);
    assertEquals(3, routingObjects.length);
    assertEquals(1, routingObjects[0]);
    assertEquals(2, routingObjects[1]);
    assertEquals(3, routingObjects[2]);

    // case 2.1 - lower inclusive upper exclusive.
    // array should contain 1, 2 and 3
    routingObjects = rpr.getRoutingObjectsForRange(lowerBound, true,
        upperBound, false);
    assertEquals(3, routingObjects.length);
    assertEquals(1, routingObjects[0]);
    assertEquals(2, routingObjects[1]);
    assertEquals(3, routingObjects[2]);

    // case 2.2 - lower inclusive upper exclusive and one of the boundaries.
    // array should contain 1 and 2
    upperBound = new SQLInteger(60);
    routingObjects = rpr.getRoutingObjectsForRange(lowerBound, true,
        upperBound, false);
    assertEquals(2, routingObjects.length);
    assertEquals(1, routingObjects[0]);
    assertEquals(2, routingObjects[1]);
    // case 2.3 - lower inclusive upper exclusive and in between some range.
    // same as case 2.1

    // case 3.1 - lower exclusive upper inclusive.
    // array should contain 1, 2 and 3
    lowerBound = new SQLInteger(25);
    upperBound = new SQLInteger(75);
    routingObjects = rpr.getRoutingObjectsForRange(lowerBound, false,
        upperBound, true);
    assertEquals(3, routingObjects.length);
    assertEquals(1, routingObjects[0]);
    assertEquals(2, routingObjects[1]);
    assertEquals(3, routingObjects[2]);

    // case 3.2 - lower exclusive and one of the boundaries and upper inclusive.
    // array should contain 2 and 3
    lowerBound = new SQLInteger(40);
    upperBound = new SQLInteger(75);
    routingObjects = rpr.getRoutingObjectsForRange(lowerBound, true,
        upperBound, true);
    assertEquals(2, routingObjects.length);
    assertEquals(2, routingObjects[0]);
    assertEquals(3, routingObjects[1]);

    // case 3.3 - lower exclusive and in between some range upper inclusive.
    // array should contain 1, 2 and 3
    lowerBound = new SQLInteger(25);
    upperBound = new SQLInteger(75);
    routingObjects = rpr.getRoutingObjectsForRange(lowerBound, true,
        upperBound, true);
    assertEquals(3, routingObjects.length);
    assertEquals(1, routingObjects[0]);
    assertEquals(2, routingObjects[1]);
    assertEquals(3, routingObjects[2]);

    // case 4.1 - Both exclusive
    // array should contain null in this case as 15 to 20 not there and
    // so it is discontinuous.
    lowerBound = new SQLInteger(15);
    upperBound = new SQLInteger(75);
    routingObjects = rpr.getRoutingObjectsForRange(lowerBound, true,
        upperBound, true);
    assertNull(routingObjects);

    // case 4.2 - Both exclusive and boundary values.
    // array should contain only 2
    lowerBound = new SQLInteger(40);
    upperBound = new SQLInteger(60);
    routingObjects = ((GfxdRangePartitionResolver)pr)
        .getRoutingObjectsForRange(lowerBound, false, upperBound, false);
    assertEquals(1, routingObjects.length);
    assertEquals(2, routingObjects[0]);

    // Discontinuous range so null expected.
    lowerBound = new SQLInteger(40);
    upperBound = new SQLInteger(95);
    routingObjects = ((GfxdRangePartitionResolver)pr)
        .getRoutingObjectsForRange(lowerBound, true, upperBound, true);
    assertNull(routingObjects);

    // When lower bound and upper bound are in same range and well within.
    lowerBound = new SQLInteger(20);
    upperBound = new SQLInteger(30);
    routingObjects = ((GfxdRangePartitionResolver)pr)
        .getRoutingObjectsForRange(lowerBound, true, upperBound, true);
    assertEquals(1, routingObjects.length);
    assertEquals(1, routingObjects[0]);

    // uupper = lower and a boundary condition for bug #39530
    lowerBound = new SQLInteger(60);
    upperBound = new SQLInteger(60);
    routingObjects = ((GfxdRangePartitionResolver)pr)
        .getRoutingObjectsForRange(lowerBound, true, upperBound, true);
    assertEquals(1, routingObjects.length);
    assertEquals(3, routingObjects[0]);

    s.close();
    conn.close();
  }

  public void testInvalidColumnGivenAsPartitioningColumn() throws SQLException,
      StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema EMP");

    CacheFactory.getAnyInstance();

    // Check for PARTITION BY RANGE
    s.execute("create table EMP.PARTITIONTESTTABLE_BASE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))");

    boolean gotException = false;
    try {
      s.execute("create table EMP.PARTITIONTESTTABLE "
          + "as select * from EMP.PARTITIONTESTTABLE_BASE with no data "
          + "PARTITION BY RANGE ( ID1 ) "
          + "( VALUES BETWEEN 20 and 40, VALUES BETWEEN 40 and 60, "
          + "VALUES BETWEEN 60 and 80, VALUES BETWEEN 90 and 100 )");
    } catch (SQLException e) {
      gotException = true;
    }
    assertTrue(gotException);
  }
}
