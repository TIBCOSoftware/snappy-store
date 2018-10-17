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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.derbyTesting.junit.JDBC;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalHashIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.Hash1Index;
import com.pivotal.gemfirexd.internal.engine.access.index.MemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2Index;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdRangePartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConglomerateDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;

public class AlterTableTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(AlterTableTest.class));
  }

  public AlterTableTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    // these tests generate lots of logs, so reducing them
    return "config";
  }
  public enum ConstraintNumber {
    CUST_PK {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.customers (cid, cust_name, "
              + "addr) values (1, 'CUST1', 'ADDR200')");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where "
                + "cid=1 and addr='ADDR200'");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }
    },
    CUST_PK2 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.customers (cid, cust_name, "
              + "tid) values (1, 'CUST1', 200)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where "
                + "cid=1 and tid=200");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }

      @Override
      public boolean defaultIgnore() {
        return true;
      }
    },
    CUST_PK3 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.customers (cid, "
              + "addr) values (1, 'ADDR200')");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where "
                + "cid=1 and addr='ADDR200'");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }

      @Override
      public boolean defaultIgnore() {
        return true;
      }
    },
    CUST_UK {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.customers (cid, cust_name, "
              + "addr, tid) values (200, 'CUST200', 'ADDR200', 50)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where cid=200");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }
    },
    CUST_UK2 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.customers (cid, cust_name, "
              + "tid) values (200, 'CUST200', 50)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where cid=200");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }

      @Override
      public boolean defaultIgnore() {
        return true;
      }
    },
    CUST_UK3 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.customers (cid, "
              + "addr, tid) values (200, 'ADDR200', 50)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where cid=200");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }

      @Override
      public boolean defaultIgnore() {
        return true;
      }
    },
    CUST_FK {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.portfolio (cid, sid, qty, availQty"
              + ", tid) values (101, 7, 50, 45, 102)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.portfolio where cid=101");
          }
        } catch (SQLException ex) {
          if (!valid || !"23503".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }
    },
    CUST_FK2 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.portfolio (cid, sid, availQty"
              + ", tid) values (101, 7, 45, 102)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.portfolio where cid=101");
          }
        } catch (SQLException ex) {
          if (!valid || !"23503".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }

      @Override
      public boolean defaultIgnore() {
        return true;
      }
    },
    PORT_PK {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.portfolio (cid, sid, qty, availQty"
              + ", tid) values (5, 7, 50, 45, 10)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where cid=5 and tid=10");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }
    },
    PORT_PK2 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.portfolio (cid, sid, availQty"
              + ", tid) values (5, 7, 45, 10)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.customers where cid=5 and tid=10");
          }
        } catch (SQLException ex) {
          if (!valid || !"23505".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }

      @Override
      public boolean defaultIgnore() {
        return true;
      }
    },
    PORT_CK1 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.portfolio (cid, sid, qty, availQty"
              + ", tid) values (50, 7, -10, -20, 50)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.portfolio where cid=50");
          }
        } catch (SQLException ex) {
          if (!valid || !"23513".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }
    },
    PORT_CK2 {
      @Override
      public void checkConstraint(Statement stmt, boolean valid)
          throws SQLException {
        try {
          stmt.execute("insert into trade.portfolio (cid, sid, qty, availQty"
              + ", tid) values (52, 7, 45, 50, 52)");
          if (valid) {
            fail("expected constraint violation in insert");
          }
          else {
            stmt.execute("delete from trade.portfolio where cid=52");
          }
        } catch (SQLException ex) {
          if (!valid || !"23513".equals(ex.getSQLState())) {
            throw ex;
          }
        }
      }
    };

    public abstract void checkConstraint(Statement stmt, boolean valid)
        throws SQLException;

    public boolean defaultIgnore() {
      return false;
    }
  }

  public static void checkConstraints(Statement stmt,
      final ConstraintNumber... validConstraints) throws SQLException {
    checkConstraints(stmt, null, validConstraints);
  }

  public static void checkConstraints(Statement stmt,
      final ConstraintNumber[] skipConstraints,
      final ConstraintNumber... validConstraints) throws SQLException {
    assert validConstraints != null;
    ConstraintNumber[] allConstraints = ConstraintNumber.values();
    Set<ConstraintNumber> invalidConstraints = new HashSet<ConstraintNumber>();
    for (ConstraintNumber constraint : allConstraints) {
      if (!constraint.defaultIgnore()) {
        invalidConstraints.add(constraint);
      }
    }
    for (ConstraintNumber constraint : validConstraints) {
      getLogger().info(
          "Checking that the constraint " + constraint + " is being applied");
      constraint.checkConstraint(stmt, true);
      invalidConstraints.remove(constraint);
    }
    if (skipConstraints != null) {
      invalidConstraints.removeAll(Arrays.asList(skipConstraints));
    }
    for (ConstraintNumber constraint : invalidConstraints) {
      getLogger().info(
          "Checking that the constraint " + constraint
              + " is *not* being applied");
      constraint.checkConstraint(stmt, false);
    }
  }

  public static GfxdPartitionResolver checkPartitioningColumns(
      String tableName, final String... columnNames) {
    tableName = tableName.toUpperCase();
    Region<Object, Object> reg = CacheFactory.getAnyInstance().getRegion(
        "/" + tableName.replace('.', '/'));
    assertNotNull("Could not find region for table " + tableName, reg);
    RegionAttributes<?, ?> rattr = reg.getAttributes();
    PartitionResolver<?, ?> pr = rattr.getPartitionAttributes()
        .getPartitionResolver();
    assertTrue(pr instanceof GfxdPartitionResolver);
    GfxdPartitionResolver rpr = (GfxdPartitionResolver)pr;

    String[] columns = rpr.getColumnNames();
    if (columns == null) {
      columns = new String[0];
    }
    assertEquals("unexpected columns: " + Arrays.toString(columns),
        columnNames.length, columns.length);
    for (int index = 0; index < columns.length; ++index) {
      assertTrue("Expected column: " + columnNames[index] + "; found: "
          + columns[index], columnNames[index].equalsIgnoreCase(columns[index]));
    }
    return rpr;
  }

  public static void checkDefaultPartitioning(String tableName,
      final String... columnNames) {
    GfxdPartitionResolver rpr = checkPartitioningColumns(tableName, columnNames);
    assertTrue(rpr instanceof GfxdPartitionByExpressionResolver
        && ((GfxdPartitionByExpressionResolver)rpr).isDefaultPartitioning());
  }

  public static void checkColumnPartitioning(String tableName,
      final String... columnNames) {
    GfxdPartitionResolver rpr = checkPartitioningColumns(tableName, columnNames);
    assertTrue(rpr instanceof GfxdPartitionByExpressionResolver);
  }

  public static void checkRangePartitioning(String tableName,
      final String... columnNames) {
    GfxdPartitionResolver rpr = checkPartitioningColumns(tableName, columnNames);
    assertTrue(rpr instanceof GfxdRangePartitionResolver);
  }

  public static MemIndex getMemIndex(long indexNumber) throws SQLException {
    EmbedConnection conn = null;
    boolean contextSet = false;
    try {
      conn = GemFireXDUtils.getTSSConnection(true, false, false);
      conn.getTR().setupContextStack();
      contextSet = true;
      LanguageConnectionContext lcc = conn.getLanguageConnection();
      TransactionController tc = lcc.getTransactionCompile();
      return (MemIndex)((GemFireTransaction)tc)
          .findExistingConglomerate(indexNumber);
    } catch (StandardException ex) {
      ex.printStackTrace();
      throw new SQLException("failed with exception: " + ex);
    } finally {
      if (contextSet) {
        conn.getTR().restoreContextStack();
      }
    }
  }

  public static TableDescriptor getTableDescriptor(String schemaName,
      String tableName) throws SQLException {
    EmbedConnection conn = null;
    boolean contextSet = false;
    try {
      schemaName = StringUtil.SQLToUpperCase(schemaName);
      tableName = StringUtil.SQLToUpperCase(tableName);
      conn = GemFireXDUtils.getTSSConnection(true, false, false);
      conn.getTR().setupContextStack();
      contextSet = true;
      LanguageConnectionContext lcc = conn.getLanguageConnection();
      DataDictionary dd = lcc.getDataDictionary();
      TransactionController tc = lcc.getTransactionCompile();
      SchemaDescriptor sd = dd.getSchemaDescriptor(schemaName, tc, true);
      TableDescriptor td = dd.getTableDescriptor(tableName, sd, tc);
      dd.getConstraintDescriptors(td);
      return td;
    } catch (StandardException ex) {
      ex.printStackTrace();
      throw new SQLException("failed with exception: " + ex);
    } finally {
      if (contextSet) {
        conn.getTR().restoreContextStack();
      }
    }
  }

  private static void columnsSQLUpperCase(String[] columnNames) {
    for (int index = 0; index < columnNames.length; ++index) {
      columnNames[index] = StringUtil.SQLToUpperCase(columnNames[index]);
    }
  }

  private static String[] getColumnNames(ConglomerateDescriptor conglom,
      TableDescriptor td) {
    IndexRowGenerator irg = conglom.getIndexDescriptor();
    int[] baseCols = irg.baseColumnPositions();
    String[] columnNames = new String[baseCols.length];
    ColumnDescriptorList colDL = td.getColumnDescriptorList();
    for (int i = 0; i < baseCols.length; i++) {
      columnNames[i] = colDL.elementAt(baseCols[i] - 1).getColumnName();
    }
    return columnNames;
  }

  public static void checkIndexType(String schemaName, String tableName,
      String expectedType, String... columnNames) throws SQLException {
    columnsSQLUpperCase(columnNames);
    TableDescriptor td = getTableDescriptor(schemaName, tableName);
    String fullTableName = td.getQualifiedName();
    ConglomerateDescriptor[] cds = td.getConglomerateDescriptors();
    boolean indexFound = false;
    String indexTypeFound = null;
    Arrays.sort(columnNames);
    for (ConglomerateDescriptor cd : cds) {
      if (cd.isIndex()) {
        String[] indexCols = getColumnNames(cd, td);
        Arrays.sort(indexCols);
        if (Arrays.equals(columnNames, indexCols)) {
          indexTypeFound = cd.getIndexDescriptor().indexType();
          if (expectedType.equals(indexTypeFound)) {
            indexFound = true;
            // also check the underlying MemIndex type
            MemIndex mi = getMemIndex(cd.getConglomerateNumber());
            if ("LOCALHASH1".equals(expectedType)) {
              assertTrue("Expected underlying conglomerate LOCALHASH1 "
                  + "but found: " + mi, mi instanceof Hash1Index);
            }
            else if ("GLOBALHASH".equals(expectedType)) {
              assertTrue("Expected underlying conglomerate GLOBALHASH "
                  + "but found: " + mi, mi instanceof GlobalHashIndex);
            }
            else if ("LOCALSORTEDMAP".equals(expectedType)) {
              assertTrue("Expected underlying conglomerate LOCALSORTEDMAP "
                  + "but found: " + mi, mi instanceof SortedMap2Index);
            }
            else {
              fail("Unknown index type: " + expectedType);
            }
            break;
          }
        }
      }
    }
    if (!indexFound) {
      if (indexTypeFound == null) {
        fail("Did not find index on columns " + Arrays.toString(columnNames)
            + " in table " + fullTableName);
      }
      else {
        fail("Did not find the expected index type [" + expectedType
            + "] found [" + indexTypeFound + "] in table " + fullTableName);
      }
    }
  }

  public static void checkNoIndexType(String schemaName, String tableName,
      String expectedType, String... columnNames) throws SQLException {
    columnsSQLUpperCase(columnNames);
    TableDescriptor td = getTableDescriptor(schemaName, tableName);
    String fullTableName = td.getQualifiedName();
    ConglomerateDescriptor[] cds = td.getConglomerateDescriptors();
    String indexTypeFound;
    Arrays.sort(columnNames);
    for (ConglomerateDescriptor cd : cds) {
      if (cd.isIndex()) {
        String[] indexCols = getColumnNames(cd, td);
        Arrays.sort(indexCols);
        if (Arrays.equals(columnNames, indexCols)) {
          indexTypeFound = cd.getIndexDescriptor().indexType();
          if (expectedType == null || expectedType.equals(indexTypeFound)) {
            fail("Did not expect to find index type [" + expectedType
                + "] found [" + indexTypeFound + "] in table " + fullTableName);
          }
        }
      }
    }
  }

  public static void checkNoIndex(String schemaName, String tableName,
      String... columnNames) throws SQLException {
    checkNoIndexType(schemaName, tableName, null, columnNames);
  }

  public static void checkDefaultIndexTypes() throws SQLException {
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkNoIndex("trade", "portfolio", "qty");
    checkNoIndex("trade", "portfolio", "availQty");
  }

  /** Test to check for add constraints using ALTER TABLE. */
  public void testAddConstraints() throws SQLException {

    // Create a schema
    Connection conn = getConnection();
    PreparedStatement prepStmt = conn.prepareStatement("create schema trade");
    prepStmt.execute();
    Statement stmt = conn.createStatement();

    addExpectedException(EntryExistsException.class);

    // check that PK add is disallowed with data
    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
    prepStmt.execute();
    CreateTableTest.populateData(conn, false, true);
    checkDefaultPartitioning("trade.customers");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK add in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkDefaultPartitioning("trade.customers");
    stmt.execute("truncate table trade.customers");

    // check that PK add is allowed without data
    checkDefaultPartitioning("trade.customers");
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    CreateTableTest.populateData(conn, false, true);
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    checkColumnPartitioning("trade.customers", "cid");
    stmt.execute("truncate table trade.customers");

    // add fk table to test fk constraints
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid, sid))");
    prepStmt.execute();
    checkDefaultPartitioning("trade.portfolio", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");

    // check that fk constraints without data do not change partitioning
    // or colocation
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid", "sid");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    //changed by yjing for the partition key is a subset of primary key
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");

    // check that constraints are being applied
    CreateTableTest.populateData(conn, true, false);
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK);

    // now drop the constraints
    stmt.execute("delete from trade.portfolio where 1=1");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint cust_fk");
    prepStmt.execute();
    // check that PK constraint drop fails with data
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop constraint cust_pk");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    stmt.execute("delete from trade.customers where 1=1");
    // check that partitioning and colocation does not change since there
    // still are buckets in the PR
    checkColumnPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid", "sid");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");

    // check that PK constraint creation fails with data
    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cid int primary key, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid, sid))");
    prepStmt.execute();
    stmt.execute("alter table trade.portfolio drop primary key");
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid", "sid");
    CreateTableTest.populateData(conn, true, false);
    stmt.execute("delete from trade.portfolio where 1=1");
    stmt.execute("delete from trade.customers where 1=1");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint portf_pk primary key (sid)");
    prepStmt.execute();
    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid, sid))");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    prepStmt.execute();
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");

    CreateTableTest.populateData(conn, true, false);

    // check that PK constraints are being applied but not FK
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.PORT_PK);

    // check fk failure with missing referenced keys
    stmt.execute("insert into trade.portfolio values (101, 7, 50, 45, 102)");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict");
    try {
      prepStmt.execute();
      fail("Expected FK violation with missing referenced key");
    } catch (SQLException ex) {
      if (!"X0Y45".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    for (int cid = 40; cid <= 101; ++cid) {
      stmt.execute("delete from trade.portfolio where cid="
          + String.valueOf(cid));
    }

    // check add UK constraint failure with non-unique keys
    stmt.execute("insert into trade.customers values "
        + "(101, 'CUST20', 'ADDR20', 25)");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_uk unique (tid)");

    addExpectedException("23505");
    try {
      prepStmt.execute();
      fail("Expected UK constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    removeExpectedException("23505");

    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    stmt.execute("delete from trade.customers where cid=101 and tid=25");

    // add constraints one by one and check that they are being applied

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_uk unique (tid)");
    prepStmt.execute();
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.PORT_PK);

    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict");
    prepStmt.execute();
    // check no change in partitioning when table has data
    checkColumnPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid", "sid");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK);

    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint qty_ck check (qty >= 0)");
    prepStmt.execute();
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK,
        ConstraintNumber.PORT_CK1);

    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint avail_ck check (availQty <= qty)");
    prepStmt.execute();

    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkNoIndex("trade", "portfolio", "qty");
    checkNoIndex("trade", "portfolio", "availQty");

    // check that all constraints are being applied
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK,
        ConstraintNumber.PORT_CK1, ConstraintNumber.PORT_CK2);

    removeExpectedException(EntryExistsException.class);
  }

  /** Test to check for drop constraints using ALTER TABLE. */
  public void testDropConstraints() throws SQLException {

    Connection conn = getConnection();
    PreparedStatement prepStmt;
    Statement stmt = conn.createStatement();

    addExpectedException(EntryExistsException.class);

    // Create the table with constraints
    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid),"
        + "constraint cust_uk unique (tid))");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");

    // check that PK drop is allowed without data and no change in partitioning
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop primary key");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkNoIndex("trade", "customers", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");

    // add PK constraint back and check that PK constraint is being applied
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    CreateTableTest.populateData(conn, false, true);
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    stmt.execute("delete from trade.customers where 1=1");

    // check that PK drop fails with data history
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop primary key");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    // check that partitioning does not change since there are buckets around
    checkDefaultPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    // but PK drop should succeed on recreating table
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid),"
        + "constraint cust_uk unique (tid))");
    prepStmt.execute();
    // check that PK constraint is no longer being applied and partitioning
    // does not change
    checkNoIndex("trade", "customers", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    CreateTableTest.populateData(conn, false, true);
    ConstraintNumber.CUST_PK.checkConstraint(stmt, false);

    // recreate table with constraints
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);

    // check partitioning and colocation
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();

    // check that dropping fk and pk does not change partitioning and colocation
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint cust_fk");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop primary key");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkNoIndex("trade", "customers", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");

    // add the constraints back and check colocation and partitioning
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();

    // check that PK drop is disallowed with data
    CreateTableTest.populateData(conn, true, false);
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop primary key");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint portf_pk");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkDefaultIndexTypes();

    // check that fk drop without data but with buckets does not change
    // partitioning or colocation
    stmt.execute("delete from trade.portfolio where 1=1");
    stmt.execute("delete from trade.customers where 1=1");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint cust_fk");
    prepStmt.execute();
    // check that pk drop with data history fails
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop primary key");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");

    // recreate the tables
    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);
    CreateTableTest.populateData(conn, true, false);
    // check that all constraints are being applied
    checkDefaultIndexTypes();
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK,
        ConstraintNumber.PORT_CK1, ConstraintNumber.PORT_CK2);

    // drop constraints one by one and check that they are no longer applied

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop constraint cust_uk");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK, ConstraintNumber.PORT_CK1,
        ConstraintNumber.PORT_CK2);

    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint cust_fk");
    prepStmt.execute();
    // with data in table check for no change in partitioning or colocation
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.PORT_PK,
        ConstraintNumber.PORT_CK1, ConstraintNumber.PORT_CK2);

    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint qty_ck");
    prepStmt.execute();
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    checkNoIndex("trade", "portfolio", "qty");
    checkNoIndex("trade", "portfolio", "availQty");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.PORT_PK,
        ConstraintNumber.PORT_CK2);

    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint avail_ck");
    prepStmt.execute();
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    checkNoIndex("trade", "portfolio", "qty");
    checkNoIndex("trade", "portfolio", "availQty");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.PORT_PK);

    removeExpectedException(EntryExistsException.class);
  }

  /** Test to check for add columns using ALTER TABLE. */
  public void testAddColumns() throws SQLException {

    // Create a schema
    Connection conn = getConnection();
    PreparedStatement prepStmt = conn.prepareStatement("create schema trade");
    prepStmt.execute();
    Statement stmt = conn.createStatement();

    addExpectedException(EntryExistsException.class);

    // check that column add is allowed with data
    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cust_name varchar(100), tid int)");
    prepStmt.execute();
    stmt.execute("insert into trade.customers values ('CUST1', 1)");
    checkDefaultPartitioning("trade.customers");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "tid");
    checkAddColumnWithData(conn);

    // also check for larger data with same column dropped/added with data
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int)");
    CreateTableTest.populateData(conn, false, true);
    stmt.execute("alter table trade.customers drop column cid");
    stmt.execute("alter table trade.customers drop column tid");
    stmt.execute("alter table trade.customers "
        + "add column tid int not null default 1");
    stmt.execute("alter table trade.customers "
        + "add column cid int not null default 1");
    verifyAddColumnWithData(conn, 100, false);

    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers "
        + "(cust_name varchar(100), tid int)");

    // check that column add and PK add is allowed without data
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_uk unique (tid)");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers");
    checkNoIndex("trade", "customers", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add column cid int not null default 1");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers");
    checkNoIndex("trade", "customers", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add column addr varchar(100)");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    CreateTableTest.populateData(conn, false, true);
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);
    checkColumnPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    stmt.execute("truncate table trade.customers");

    // add fk table to test fk constraints
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid))");
    prepStmt.execute();
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid");
    checkNoIndex("trade", "portfolio", "tid");

    // check that fk constraints without data also changes partitioning
    // and colocation
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkNoIndex("trade", "portfolio", "tid");

    // check that altering PK constraints without data does not change
    // partitioning
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add column sid int not null default 1");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint portf_pk");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint portf_pk primary key (cid, sid)");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "cid");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndexType("trade", "portfolio", "LOCALHASH1", "cid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");

    // check that constraints are being applied
    CreateTableTest.populateData(conn, true, false);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK);

    // now check that dropping the partitioning column fails
    stmt.execute("delete from trade.portfolio where 1=1");
    stmt.execute("delete from trade.customers where 1=1");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    CreateTableTest.populateData(conn, true, false);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK);
    stmt.execute("delete from trade.portfolio where 1=1");
    stmt.execute("delete from trade.customers where 1=1");

    // check dropping any other column works
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop constraint cust_uk");
    prepStmt.execute();
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    CreateTableTest.populateData(conn, true, false);
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK);
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column tid");
    prepStmt.execute();
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkConstraints(stmt, new ConstraintNumber[] { ConstraintNumber.CUST_UK },
        ConstraintNumber.CUST_PK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK);

    // KN: dropped full table here both customers and portfolio
    // check for change in partitioning on dropping partitioning column
    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cust_name varchar(100), tid int) partition by column (tid)");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid, sid))");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "tid");
    checkDefaultPartitioning("trade.portfolio", "cid", "sid");
    checkNoIndex("trade", "customers", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkNoIndex("trade", "portfolio", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add column cid int not null default 1");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "tid");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_uk unique (tid)");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "tid");
    checkNoIndex("trade", "customers", "cid");
    checkIndexType("trade", "customers", "LOCALSORTEDMAP", "tid");

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add constraint cust_pk primary key (cid)");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "tid");
    checkIndexType("trade", "customers", "GLOBALHASH", "cid");
    checkIndexType("trade", "customers", "LOCALSORTEDMAP", "tid");

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add column addr varchar(100)");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "tid");
    checkIndexType("trade", "customers", "GLOBALHASH", "cid");
    checkIndexType("trade", "customers", "LOCALSORTEDMAP", "tid");
    // check that dropping partitioning column is not supported
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column tid cascade");
    try {
      prepStmt.execute();
      fail("expected failure in partitioning column drop");
    } catch (SQLException sqle) {
      if (!"0A000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    // check that fk constraints without data does not change partitioning
    // or colocation
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "tid");
    checkDefaultPartitioning("trade.portfolio", "cid", "sid");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "GLOBALHASH", "cid");
    checkIndexType("trade", "customers", "LOCALSORTEDMAP", "tid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");

    removeExpectedException(EntryExistsException.class);
  }

  /** Test to check for drop columns using ALTER TABLE. */
  public void testDropColumns() throws Exception {

    Connection embedConn = getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    PreparedStatement prepStmt;
    Statement stmt = conn.createStatement();

    addExpectedException(EntryExistsException.class);

    // check that PK column drop is disallowed with data
    CreateTableTest.createTables(conn);
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();

    ConstraintNumber.CUST_PK.checkConstraint(stmt, false);

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    // dropping of partitioning column in trade.portfolio will also fail due
    // to creation of colocated buckets
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();
    // dropping of any PK column in trade.portfolio will also fail
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column sid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();

    stmt.execute("truncate table trade.portfolio");
    stmt.execute("delete from trade.customers where 1=1");

    // check that column drop is allowed without data but PK drop is not
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();

    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid),"
        + "constraint cust_uk unique (tid))");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column addr");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    stmt.execute("insert into trade.customers values (1, 'CUST1', 2)");
    try {
      stmt.execute("insert into trade.customers values (1, 'CUST1', 1)");
      fail("Expected PK constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    try {
      stmt.execute("insert into trade.customers values (3, 'CUST3', 2)");
      fail("Expected UK constraint violation");
    } catch (SQLException ex) {
      if (!"23505".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "tid int, primary key (cid),"
        + "constraint cust_uk unique (tid))");
    checkDefaultPartitioning("trade.customers", "cid");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add column addr varchar(100)");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    CreateTableTest.populateData(conn, false, true);
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);

    // check that dropping fk columns without data also changes
    // partitioning, colocation and index types
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid, sid), constraint cust_fk "
        + "foreign key (cid) references trade.customers (cid) on "
        + "delete restrict, constraint qty_ck check (qty >= 0), "
        + "constraint avail_ck check (availQty <= qty))");
    prepStmt.execute();
    addExpectedException(UnsupportedOperationException.class);
    try {
      stmt.execute("drop table trade.customers");
      fail("Expected colocated table drop to fail");
    } catch (SQLException ex) {
      if (!"X0Y98".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    removeExpectedException(UnsupportedOperationException.class);
    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop constraint cust_fk");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (tid) references "
        + "trade.customers (cid) on delete restrict");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "tid");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column sid");
    try {
      prepStmt.execute();
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column tid cascade");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "tid");

    // check that constraints are being applied
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add column tid int not null default 1");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint cust_fk foreign key (tid) references "
        + "trade.customers (cid) on delete restrict");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkNoIndex("trade", "portfolio", "cid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "tid");

    // now check that dropping PK/partitioning columns fails with data history
    CreateTableTest.populateData(conn, true, false);
    stmt.execute("delete from trade.portfolio where 1=1");
    stmt.execute("delete from trade.customers where 1=1");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column tid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for partitioning column drop " +
      		"in ALTER TABLE with data");
    } catch (SQLException ex) {
      if (!"X0Y25".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);
    checkDropColumnWithData(conn);

    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);
    checkDropColumnWithData(embedConn);

    // check that dropping partitioning columns is disallowed
    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    prepStmt = conn.prepareStatement("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid),"
        + "constraint cust_uk unique (tid)) partition by column (cust_name)");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("create table trade.portfolio "
        + "(cid int not null, sid int not null, qty int not null, "
        + "availQty int not null, tid int, "
        + "constraint portf_pk primary key (cid, sid), constraint cust_fk "
        + "foreign key (cid) references trade.customers (cid) on "
        + "delete restrict, constraint qty_ck check (qty >= 0), "
        + "constraint avail_ck check (availQty <= qty)) "
        + "partition by range (qty) (values between 0 and 20, "
        + "values between 20 and 40, values between 40 and 100)");
    prepStmt.execute();
    checkColumnPartitioning("trade.customers", "cust_name");
    checkRangePartitioning("trade.portfolio", "qty");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "GLOBALHASH", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "GLOBALHASH", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkNoIndex("trade", "portfolio", "qty");
    checkNoIndex("trade", "portfolio", "availQty");

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column cust_name");
    try {
      prepStmt.execute();
      fail("expected failure in dropping partitioning column");
    } catch (SQLException sqle) {
      if (!"0A000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    checkColumnPartitioning("trade.customers", "cust_name");
    checkRangePartitioning("trade.portfolio", "qty");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "GLOBALHASH", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "GLOBALHASH", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column qty cascade");
    try {
      prepStmt.execute();
      fail("expected failure in dropping partitioning column");
    } catch (SQLException sqle) {
      if (!"0A000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    checkColumnPartitioning("trade.customers", "cust_name");
    checkRangePartitioning("trade.portfolio", "qty");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "GLOBALHASH", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "GLOBALHASH", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    checkColumnPartitioning("trade.customers", "cust_name");
    checkRangePartitioning("trade.portfolio", "qty");
    checkColocation("trade.portfolio", null, null);
    checkIndexType("trade", "customers", "GLOBALHASH", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    checkIndexType("trade", "portfolio", "GLOBALHASH", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");

    removeExpectedException(EntryExistsException.class);
  }

  /**
   * Test to check for drop columns using ALTER TABLE which affects one or more
   * existing/new index positions.
   */
  public void testDropColumnsAffectingIndexes() throws Exception {

    Connection embedConn = getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    PreparedStatement pstmt, pstmt2, pstmt3, prepStmt;
    Statement stmt = embedConn.createStatement();
    ResultSet rs;

    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, primary key (cid, tid),"
        + "constraint cust_uk unique (tid))");
    // add fk table portfolio
    stmt.execute("create table trade.portfolio "
        + "(cid int not null, sid int, qty int not null, "
        + "availQty int not null, tid int not null, "
        + "constraint portf_pk primary key (tid), constraint cust_fk "
        + "foreign key (cid, tid) references trade.customers (cid, tid) on "
        + "delete restrict, constraint qty_ck check (qty >= 0), "
        + "constraint avail_ck check (availQty <= qty))");

    checkDefaultPartitioning("trade.customers", "cid", "tid");
    checkDefaultPartitioning("trade.portfolio", "cid", "tid");
    checkColocation("trade.portfolio", "trade", "customers");

    // create indexes that will be affected and unaffected by column drop
    stmt.execute("create index idx_cid on trade.customers(cid)");
    stmt.execute("create index idx_name on trade.customers(cust_name, tid)");
    stmt.execute("create index idxp_sid on trade.portfolio(sid, cid)");
    stmt.execute("create index idxp_cid on trade.portfolio(cid)");
    stmt.execute("create index idxp_qty on trade.portfolio(qty, tid)");

    CreateTableTest.populateData(embedConn, true, false, false, false, true,
        "trade.customers", "trade.portfolio");

    rs = stmt.executeQuery("select qty, c.cid, p.tid, cust_name, availQty "
        + "from trade.portfolio p, trade.customers c "
        + "where qty = 950 and p.cid = c.cid");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 950, 95, 96,
        "CUST95", 95 * 9 } }, false, true);

    pstmt = conn.prepareStatement("select cid, cust_name from trade.customers "
        + "where tid = ?");
    pstmt2 = embedConn.prepareStatement("select cid, qty from trade.portfolio "
        + "where tid = ?");
    pstmt3 = conn.prepareStatement("select qty, cid, tid from trade.portfolio "
        + "where qty = ? and tid = ?");

    // now drop columns from both tables affecting PK, FK and other indexes
    stmt.execute("alter table trade.customers drop column addr");
    addExpectedException("01506");
    stmt.execute("alter table trade.portfolio drop column sid cascade");
    removeExpectedException("01506");

    // more inserts as per the new schemas but that will violate constraints
    // if column adjustments were not done after column drops
    prepStmt = conn.prepareStatement(
        "insert into trade.customers(cid, cust_name, tid) values (?, ?, ?)");
    for (int i = 190; i <= 300; i++) {
      prepStmt.setInt(1, i);
      prepStmt.setString(2, "ADDR" + (i / 2));
      prepStmt.setInt(3, i + 1);
      prepStmt.execute();
    }
    prepStmt = embedConn.prepareStatement("insert into trade.portfolio"
        + "(qty, cid, tid, availQty) values (?, ?, ?, ?)");
    for (int i = 190; i <= 300; i++) {
      prepStmt.setInt(1, i * 5);
      prepStmt.setInt(2, i);
      prepStmt.setInt(3, i + 1);
      prepStmt.setInt(4, i * 4);
      prepStmt.execute();
    }

    // queries using indexes
    rs = stmt.executeQuery(
        "select * from trade.customers where cid = 50 and tid = 51");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 50, "CUST50",
        51 } }, false, true);
    rs = stmt.executeQuery(
        "select * from trade.customers where cid = 250 and tid = 251");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 250, "ADDR125",
        251 } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers where tid = 80");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 79, "CUST79",
        80 } }, false, true);
    rs = stmt.executeQuery(
        "select cid, cust_name from trade.customers where tid = 260");
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 259, "ADDR129" } }, false, true);
    pstmt.setInt(1, 280);
    rs = pstmt.executeQuery();
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 279, "ADDR139" } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers where tid = 180");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.customers where cid = 60");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 60, "CUST60",
        61 } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers where cid = 200");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 200,
        "ADDR100", 201 } }, false, true);

    rs = stmt.executeQuery(
        "select * from trade.portfolio where cid = 50 and tid = 51");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 50, 500,
        450, 51 } }, false, true);
    rs = stmt.executeQuery(
        "select * from trade.portfolio where cid = 240 and tid = 241");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 240, 1200,
        240 * 4, 241 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where tid = 80");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 79, 790,
        79 * 9, 80 } }, false, true);
    rs = stmt.executeQuery(
        "select cid, qty from trade.portfolio where tid = 260");
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 259, 259 * 5 } }, false, true);
    pstmt2.setInt(1, 280);
    rs = pstmt2.executeQuery();
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 279, 279 * 5 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where tid = 150");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where cid = 60");
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 60, 600, 540, 61 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where cid = 200");
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 200, 1000, 800, 201 } }, false, true);
    rs = stmt.executeQuery(
        "select * from trade.portfolio where qty = 945 and tid = 190");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select qty, cid, tid from trade.portfolio "
        + "where qty = 1000 and tid = 201");
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 1000, 200, 201 } }, false, true);
    pstmt3.setInt(1, 1200);
    pstmt3.setInt(2, 241);
    rs = pstmt3.executeQuery();
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 1200, 240, 241 } }, false, true);

    rs = stmt.executeQuery("select qty, p.tid, c.cid, cust_name, availQty "
        + "from trade.portfolio p, trade.customers c "
        + "where c.tid = p.tid and c.cid = p.cid "
        + "and qty = 950 and availQty = 760");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 950, 191, 190,
        "ADDR95", 760 } }, false, true);
    rs = stmt.executeQuery("select qty, c.cid, p.tid, cust_name, availQty "
        + "from trade.portfolio p, trade.customers c "
        + "where qty = 950 and p.cid = c.cid");
    JDBC.assertUnorderedResultSet(rs, new Object[][] {
        new Object[] { 950, 190, 191, "ADDR95", 760 },
        new Object[] { 950, 95, 96, "CUST95", 95 * 9 } }, false, true);
  }

  /**
   * Test to check for add/drop columns using ALTER TABLE which affects the
   * underlying storage type with LOB columns (byte[] vs byte[][])
   */
  public void testAddDropColumnsAffectingStoreType() throws Exception {

    Connection embedConn = getConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    PreparedStatement pstmt, pstmt2, pstmt3, prepStmt;
    Statement stmt = embedConn.createStatement();
    ResultSet rs;

    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, note clob, primary key (cid)) "
        + "PARTITION BY COLUMN (cust_name)");
    // add fk table portfolio
    stmt.execute("create table trade.portfolio "
        + "(cid int not null, sid int, qty int not null, "
        + "availQty int not null, tid int not null, "
        + "constraint portf_pk primary key (tid), constraint cust_fk "
        + "foreign key (cid) references trade.customers (cid) on "
        + "delete restrict, constraint qty_ck check (qty >= 0), "
        + "constraint avail_ck check (availQty <= qty))");

    checkColumnPartitioning("trade.customers", "cust_name");
    checkDefaultPartitioning("trade.portfolio", "tid");
    checkColocation("trade.portfolio", null, null);

    // create indexes that will be affected and unaffected by column drop
    stmt.execute("create index idx_cid on trade.customers(cid)");
    stmt.execute("create index idx_name on trade.customers(cust_name, tid)");
    stmt.execute("create index idxp_sid on trade.portfolio(sid, cid)");
    stmt.execute("create index idxp_cid on trade.portfolio(cid)");
    stmt.execute("create index idxp_qty on trade.portfolio(qty, tid)");

    CreateTableTest.populateData(embedConn, true, false, false, false, true,
        "trade.customers", "trade.portfolio");

    stmt.execute("update trade.customers set note='NONE'");

    rs = stmt.executeQuery("select qty, c.cid, p.tid, cust_name, availQty "
        + "from trade.portfolio p, trade.customers c "
        + "where qty = 950 and p.cid = c.cid");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 950, 95, 96,
        "CUST95", 95 * 9 } }, false, true);

    pstmt = conn.prepareStatement("select cid, cust_name from trade.customers "
        + "where tid = ?");
    pstmt2 = embedConn.prepareStatement("select cid, qty from trade.portfolio "
        + "where tid = ?");
    pstmt3 = conn.prepareStatement("select qty, cid, tid from trade.portfolio "
        + "where qty = ? and tid = ?");

    // now drop columns from both tables affecting PK, FK and other indexes
    // also add/drop CLOB column to affect the table storage scheme
    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.customers drop column note");
    addExpectedException("01506");
    stmt.execute("alter table trade.portfolio drop column sid cascade");
    removeExpectedException("01506");
    stmt.execute("alter table trade.portfolio add column "
        + "note clob default 'NONE'");

    // more inserts/updates as per the new schemas
    prepStmt = conn.prepareStatement(
        "insert into trade.customers(cid, cust_name, tid) values (?, ?, ?)");
    for (int i = 190; i <= 200; i++) {
      prepStmt.setInt(1, i);
      prepStmt.setString(2, "ADDR" + (i / 2));
      prepStmt.setInt(3, i + 1);
      prepStmt.execute();
    }
    prepStmt = embedConn.prepareStatement("insert into trade.portfolio"
        + "(qty, cid, tid, availQty, note) values (?, ?, ?, ?, ?)");
    for (int i = 190; i <= 200; i++) {
      prepStmt.setInt(1, i * 5);
      prepStmt.setInt(2, i);
      prepStmt.setInt(3, i + 1);
      prepStmt.setInt(4, i * 4);
      prepStmt.setString(5, "NOTE" + i);
      prepStmt.execute();
    }
    stmt.execute("update trade.customers set tid = 200 where cid > 190");
    stmt.execute("update trade.portfolio set note = 'NOTE' "
        + "where cid < 50 or cid > 195");

    // queries using indexes and otherwise
    rs = stmt.executeQuery("select * from trade.customers "
        + "where cid = 50 and tid = 51");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 50, "CUST50",
        51 } }, false, true);
    rs = stmt.executeQuery("select count(*) from trade.customers "
        + "where cust_name is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 111 } },
        false, true);
    rs = stmt.executeQuery("select * from trade.customers "
        + "where cust_name = 'CUST50' or cust_name = 'ADDR96'");
    JDBC.assertUnorderedResultSet(rs, new Object[][] {
        new Object[] { 50, "CUST50", 51 }, new Object[] { 192, "ADDR96", 200 },
        new Object[] { 193, "ADDR96", 200 } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers "
        + "where tid = 80 and cust_name is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 79, "CUST79",
        80 } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers where tid = 180");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.customers where cid = 60");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 60, "CUST60",
        61 } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers "
        + "where cid = 200 and cust_name is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 200,
        "ADDR100", 200 } }, false, true);

    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where cid = 50 and tid = 51");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 50, 500, 450,
        51, "NONE" } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where tid = 80");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 79, 790,
        79 * 9, 80, "NONE" } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where tid = 150");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where cid = 60");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 60, 600, 540,
        61, "NONE" } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where cid = 200 and note is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 200, 1000,
        800, 201, "NOTE" } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where qty = 945 and tid = 190");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where tid = 195 and note is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 194, 194 * 5,
        194 * 4, 195, "NOTE194" } }, false, true);

    rs = stmt.executeQuery("select qty, p.tid, c.cid, cust_name, availQty "
        + "from trade.portfolio p, trade.customers c "
        + "where c.tid = p.tid and c.cid = p.cid "
        + "and qty = 950 and availQty = 760");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 950, 191, 190,
        "ADDR95", 760 } }, false, true);
    rs = stmt.executeQuery("select qty, c.cid, p.tid, cust_name, availQty, "
        + "note from trade.portfolio p, trade.customers c "
        + "where qty = 950 and p.cid = c.cid order by cust_name");
    JDBC.assertFullResultSet(rs, new Object[][] {
        new Object[] { 950, 190, 191, "ADDR95", 760, "NOTE190" },
        new Object[] { 950, 95, 96, "CUST95", 95 * 9, "NONE" } }, false, true);

    // now add/drop CLOB column again
    stmt.execute("alter table trade.customers add column "
        + "note clob default 'NULL'");
    stmt.execute("alter table trade.portfolio drop column note");

    // more inserts as per the new schemas
    prepStmt = conn.prepareStatement("insert into trade.customers"
        + "(cid, cust_name, tid, note) values (?, ?, ?, ?)");
    for (int i = 201; i <= 300; i++) {
      prepStmt.setInt(1, i);
      prepStmt.setString(2, "ADDR" + (i / 2));
      prepStmt.setInt(3, i + 1);
      prepStmt.setString(4, "CUSTNOTE" + i);
      prepStmt.execute();
    }
    prepStmt = embedConn.prepareStatement("insert into trade.portfolio"
        + "(qty, cid, tid, availQty) values (?, ?, ?, ?)");
    for (int i = 201; i <= 300; i++) {
      prepStmt.setInt(1, i * 5);
      prepStmt.setInt(2, i);
      prepStmt.setInt(3, i + 1);
      prepStmt.setInt(4, i * 4);
      prepStmt.execute();
    }
    stmt.execute("update trade.customers set note = 'NOTE' " +
    		"where cid < 50 or cid > 256 or (cid > 192 and cid < 200)");
    stmt.execute("update trade.portfolio set qty = availQty*2 where cid > 250");

    // queries using indexes and otherwise
    rs = stmt.executeQuery("select * from trade.customers "
        + "where cid = 50 and tid = 51");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 50, "CUST50",
        51, "NULL" } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers "
        + "where cid = 250 and tid = 251");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 250,
        "ADDR125", 251, "CUSTNOTE250" } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers where (cid = 250 "
        + "and tid = 251) or tid = 300 or cid = 80 order by cust_name");
    JDBC.assertFullResultSet(rs, new Object[][] {
        new Object[] { 250, "ADDR125", 251, "CUSTNOTE250" },
        new Object[] { 299, "ADDR149", 300, "NOTE" },
        new Object[] { 80, "CUST80", 81, "NULL" } }, false, true);
    rs = stmt.executeQuery("select cid, cust_name from trade.customers "
        + "where tid = 260");
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 259, "ADDR129" } }, false, true);
    pstmt.setInt(1, 280);
    rs = pstmt.executeQuery();
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 279, "ADDR139" } }, false, true);
    rs = stmt.executeQuery("select count(*) from trade.customers "
        + "where cust_name is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 211 } },
        false, true);
    rs = stmt.executeQuery("select * from trade.customers "
        + "where cust_name = 'CUST50' or cust_name = 'ADDR96'");
    JDBC.assertUnorderedResultSet(rs, new Object[][] {
        new Object[] { 50, "CUST50", 51, "NULL" },
        new Object[] { 192, "ADDR96", 200, "NULL" },
        new Object[] { 193, "ADDR96", 200, "NOTE" } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers "
        + "where tid = 80 and cust_name is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 79, "CUST79",
        80, "NULL" } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers where tid = 180");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.customers where cid = 60");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 60, "CUST60",
        61, "NULL" } }, false, true);
    rs = stmt.executeQuery("select * from trade.customers "
        + "where cid = 200 and cust_name is not null");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 200,
        "ADDR100", 200, "NULL" } }, false, true);

    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where cid = 50 and tid = 51");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 50, 500, 450,
        51 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where tid = 80");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 79, 790,
        79 * 9, 80 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where tid = 150");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio where cid = 60");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 60, 600, 540,
        61 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where cid = 200");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 200, 1000,
        800, 201 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where qty = 945 and tid = 190");
    JDBC.assertFullResultSet(rs, new Object[][] {}, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where tid = 195");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 194, 194 * 5,
        194 * 4, 195 } }, false, true);
    rs = stmt.executeQuery("select * from trade.portfolio "
        + "where cid = 240 and tid = 241");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 240, 1200,
        240 * 4, 241 } }, false, true);
    rs = stmt
        .executeQuery("select cid, qty from trade.portfolio where tid = 260");
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 259, 259 * 8 } }, false, true);
    pstmt2.setInt(1, 280);
    rs = pstmt2.executeQuery();
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 279, 279 * 8 } }, false, true);
    pstmt3.setInt(1, 1200);
    pstmt3.setInt(2, 241);
    rs = pstmt3.executeQuery();
    JDBC.assertFullResultSet(rs,
        new Object[][] { new Object[] { 1200, 240, 241 } }, false, true);

    rs = stmt.executeQuery("select qty, p.tid, c.cid, cust_name, availQty "
        + "from trade.portfolio p, trade.customers c "
        + "where c.tid = p.tid and c.cid = p.cid "
        + "and qty = 950 and availQty = 760");
    JDBC.assertFullResultSet(rs, new Object[][] { new Object[] { 950, 191, 190,
        "ADDR95", 760 } }, false, true);
    rs = stmt.executeQuery("select qty, c.cid, p.tid, cust_name, availQty, "
        + "note from trade.portfolio p, trade.customers c "
        + "where (qty = 950 or qty = 1100) and p.cid = c.cid");
    JDBC.assertUnorderedResultSet(rs, new Object[][] {
        new Object[] { 950, 190, 191, "ADDR95", 760, "NULL" },
        new Object[] { 1100, 220, 221, "ADDR110", 880, "CUSTNOTE220" },
        new Object[] { 950, 95, 96, "CUST95", 95 * 9, "NULL" } }, false, true);
  }

  /**
   * Test for TRUNCATE TABLE particularly that constraints etc. are recreated
   * properly since this drops and recreates the table.
   */
  public void testTruncateTable() throws SQLException {

    Connection conn = getConnection();
    PreparedStatement prepStmt;
    Statement stmt = conn.createStatement();

    addExpectedException(EntryExistsException.class);

    // create tables and check for indices and partitioning
    CreateTableTest.createTables(conn);
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();
    // add a unique key constraint to portfolio
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "add constraint port_uk unique (tid)");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();
    checkIndexType("trade", "portfolio", "GLOBALHASH", "tid");
    // populate data and check again
    CreateTableTest.populateData(conn, true, false);
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();
    checkIndexType("trade", "portfolio", "GLOBALHASH", "tid");
    // check for constraints
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK,
        ConstraintNumber.PORT_CK1, ConstraintNumber.PORT_CK2);

    // truncate portfolio table and check the whole thing again
    stmt.execute("truncate table trade.portfolio");
    stmt.execute("delete from trade.customers where 1=1");
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();
    checkIndexType("trade", "portfolio", "GLOBALHASH", "tid");
    // populate data and check again
    CreateTableTest.populateData(conn, true, false);
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkDefaultIndexTypes();
    checkIndexType("trade", "portfolio", "GLOBALHASH", "tid");
    // check for constraints
    checkConstraints(stmt, ConstraintNumber.CUST_PK, ConstraintNumber.CUST_UK,
        ConstraintNumber.CUST_FK, ConstraintNumber.PORT_PK,
        ConstraintNumber.PORT_CK1, ConstraintNumber.PORT_CK2);

    // check that truncating customers table fails due to child table
    try {
      stmt.execute("truncate table trade.customers");
      fail("Expected exception in truncate table due to child table");
    } catch (SQLException ex) {
      if (!"XCL48".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    // drop child portfolio table and check that truncating customers works
    stmt.execute("drop table trade.portfolio");
    stmt.execute("truncate table trade.customers");
    checkDefaultPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    // populate data and check constraints
    CreateTableTest.populateData(conn, false, true);
    checkDefaultPartitioning("trade.customers", "cid");
    checkColocation("trade.customers", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);

    // do this once more then cleanup
    stmt.execute("truncate table trade.customers");
    checkDefaultPartitioning("trade.customers", "cid");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    // populate data and check constraints
    CreateTableTest.populateData(conn, false, true);
    checkDefaultPartitioning("trade.customers", "cid");
    checkColocation("trade.customers", null, null);
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkNoIndex("trade", "customers", "cust_name");
    checkNoIndex("trade", "customers", "addr");
    ConstraintNumber.CUST_PK.checkConstraint(stmt, true);
    ConstraintNumber.CUST_UK.checkConstraint(stmt, true);

    stmt.execute("drop table trade.customers");

    removeExpectedException(EntryExistsException.class);
  }

  public void testBug41062_1() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema TEST");
    s.execute("create table TEST.TESTTABLE1 (ID int primary key, "
        + "TYPE int, ADDRESS varchar(1024),DESCRIPTION varchar(1024))");
    s.execute("alter table TEST.TESTTABLE1 add constraint cust_fk1 "
        + "foreign key (type) references TEST.TESTTABLE1 ");
    s.execute("create table TEST.TESTTABLE2 (ID int primary key,  TYPE int, "
        + "ADDRESS varchar(1024), DESCRIPTION varchar(1024), constraint "
        + "cust_fk2 foreign key (type) references TEST.TESTTABLE2(ID))");
  }

  public void testBug41062_2() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create table TESTTABLE1 (ID int primary key,  TYPE int, "
        + "ADDRESS varchar(1024), DESCRIPTION varchar(1024), constraint "
        + "cust_fk1 foreign key (type) references TESTTABLE1(ID))");
    s.execute("create schema TEST");
    s.execute("create table TEST.TESTTABLE2 (ID int primary key, TYPE int, "
        + "ADDRESS varchar(1024), DESCRIPTION varchar(1024), constraint "
        + "cust_fk2 foreign key (type) references TEST.TESTTABLE2(ID))");
  }

  public void testUnsupportedFeatures_40712() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers "
        + "(cid int not null, cust_name varchar(100), "
        + "addr varchar(100), tid int, rid int GENERATED ALWAYS AS IDENTITY,"
        + " primary key (cid), constraint cust_uk unique (tid))");

    // check for exceptions in alter column
    try {
      stmt.execute("alter table trade.customers alter column cust_name "
          + "set data type varchar(200)");
      fail("exepected unsupported exception");
    } catch (SQLException sqle) {
      if (!"0A000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
//    try {
//      stmt.execute("alter table trade.customers alter column rid "
//          + "set increment by 2");
//      fail("exepected unsupported exception");
//    } catch (SQLException sqle) {
//      if (!"0A000".equals(sqle.getSQLState())) {
//        throw sqle;
//      }
//    }
//    try {
//      stmt.execute("alter table trade.customers alter column rid "
//          + "restart with 5");
//      fail("exepected unsupported exception");
//    } catch (SQLException sqle) {
//      if (!"0A000".equals(sqle.getSQLState())) {
//        throw sqle;
//      }
//    }
    try {
      stmt.execute("alter table trade.customers alter column addr "
          + "not null");
      fail("exepected unsupported exception");
    } catch (SQLException sqle) {
      if (!"0A000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("alter table trade.customers alter column cid "
          + "null");
      fail("exepected unsupported exception");
    } catch (SQLException sqle) {
      if (!"42Z20".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("alter table trade.customers alter column tid "
          + "default 10");
      fail("exepected unsupported exception");
    } catch (SQLException sqle) {
      if (!"0A000".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  public void testAlterTableDisAllowed() throws SQLException {
      Properties p = new Properties();
      p.setProperty("host-data", "true");
      p.setProperty("mcast-port", "0");
      //p.setProperty("log-level", "fine");
      p.setProperty("gemfire.enable-time-statistics", "true");
      p.setProperty("statistic-sample-rate", "100");
      p.setProperty("statistic-sampling-enabled", "true");
      p.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_STATS, "true");
      p.setProperty(com.pivotal.gemfirexd.Attribute.ENABLE_TIMESTATS, "true");
      p.setProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME, "client-" + 1 + ".gfs");
      
      p.put(com.pivotal.gemfirexd.Attribute.USERNAME_ATTR, "Soubhik");
      p.put(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "Soubhik");
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();

      st.execute("create table CHEESE (CHEESE_CODE VARCHAR(5) not null, "
          + "CHEESE_NAME VARCHAR(20), CHEESE_COST DECIMAL(7,4))");

      st.execute("INSERT INTO CHEESE (CHEESE_CODE, CHEESE_NAME, CHEESE_COST) "
          + "VALUES ('00000', 'GOUDA', 001.1234), ('00000', 'EDAM', "
          + "002.1111), ('54321', 'EDAM', 008.5646), ('12345', "
          + "'GORGONZOLA', 888.2309), ('AAAAA', 'EDAM', 999.8888), "
          + "('54321', 'MUENSTER', 077.9545)");

      st.execute("create index cheese_index on CHEESE "
          + "(CHEESE_CODE DESC, CHEESE_NAME DESC, CHEESE_COST DESC)");
      
      try {
      st.execute("alter table CHEESE add constraint pk_cheese PRIMARY KEY (CHEESE_CODE)");
      fail("alter table exception must have occurred");
      }
      catch(SQLException e) {
        if(!"0A000".equalsIgnoreCase(e.getSQLState())) {
          fail("Alter table exception should have occurred but have different exception", e);
        }
      }
  }

  public void testIdentityColumnWithWAN() throws Exception {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size added using ALTER TABLE
    stmt.execute("create table trade.customers (tid int, cid int not null, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    stmt.execute("drop table trade.customers");

    stmt.execute("create table trade.customers (tid int, cid bigint not null GENERATED ALWAYS AS IDENTITY, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");

    stmt.execute("alter table trade.customers SET GATEWAYSENDER (MYSENDER)");

    stmt.execute("drop table trade.customers");
  
    try {
      stmt.execute("create table trade.customers (tid int, cid int not null GENERATED ALWAYS AS IDENTITY, "
          + "primary key (cid), constraint cust_ck check (cid >= 0))");

      stmt.execute("alter table trade.customers SET GATEWAYSENDER (MYSENDER)");
    }
    catch (Exception e) {
      if (!e.getMessage().contains(
          "LTER TABLE statement cannot add gatewaysender to a table")) {
        fail("Unexpected exception", e);
      }
    }

  }
  
  public void testAddGeneratedIdentityColumn() throws Exception {
    setupConnection();
    Connection conn = TestUtil.jdbcConn;
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size added using ALTER TABLE
    stmt.execute("create table trade.customers (tid int, cid int not null, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");
    // first some inserts with gaps
    final int maxValue = 1000;
    int stepValue = 3;
    PreparedStatement pstmt = conn.prepareStatement("insert into "
        + "trade.customers (tid, cid) values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(1, v * stepValue);
      pstmt.setInt(2, v);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    SQLWarning sw = stmt.getWarnings();
    assertNull(sw);

    final int numRows = 2000;
    // insertion in this table should start with maxValue
    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, -maxValue, 0, null,true);

    // Now check for the same with BIGINT size
    stmt.execute("drop table trade.customers");
    stmt.execute("create table trade.customers (tid int, cid bigint not null, "
        + "addr varchar(100), primary key (cid), "
        + "constraint cust_ck check (cid >= 0))");

    stepValue = 2;
    pstmt = conn.prepareStatement(
        "insert into trade.customers (cid, tid) values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(2, v);
      pstmt.setInt(1, v * stepValue);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    assertNull(stmt.getWarnings());

    CreateTable2Test.runIdentityChecksForCustomersTable(conn, numRows,
        new int[] { 2 }, new String[] { "CID" }, 1, -(maxValue * stepValue), 0,
        null,true);

    stmt.execute("drop table trade.customers");
  }
  
  // none --> always
  public void testSetGeneratedAlways() throws Exception{
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table IDTABLE (ID int not null, phone int not null)");
    stmt.execute("alter table IDTABLE alter column id "
        + "SET GENERATED always AS IDENTITY");
    conn.createStatement().execute("insert into IDTABLE (id, phone) values (default,1)");
    conn.createStatement().execute("insert into IDTABLE (phone) values (1)");
    ResultSet rs = conn.createStatement().executeQuery("select max(id) from IDTABLE ");
    rs.next();
    assertEquals(2,rs.getInt(1));
  }  
  
  // none --> always
  public void testChangeStartWith() throws Exception{
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table IDTABLE (ID int not null generated by default as identity (start with 5, increment by 2), phone int not null)");
    conn.createStatement().execute("insert into IDTABLE (phone) values (1)");
    conn.createStatement().execute("insert into IDTABLE (phone) values (2)");
    stmt.execute("alter table IDTABLE alter column id restart with 20");
    stmt.execute("alter table IDTABLE alter column id set increment by 10");
    conn.createStatement().execute("insert into IDTABLE (phone) values (3)");
    conn.createStatement().execute("insert into IDTABLE (phone) values (4)");
    ResultSet rs = conn.createStatement().executeQuery("select id from IDTABLE order by phone");
    rs.next();
    assertEquals(5,rs.getInt(1));
    rs.next();
    assertEquals(7,rs.getInt(1));
    rs.next();
    assertEquals(20,rs.getInt(1));
    rs.next();
    assertEquals(30,rs.getInt(1));
  }  
  
  public void testNWEA() throws Exception {
    // start a client and some servers
    // starting servers first to give them lesser VMIds than the client

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();

    // Check for IDENTITY column with INT size added using ALTER TABLE
    stmt.execute("create table trade.customers (tid int, cid int not null, "
        + "primary key (cid), constraint cust_ck check (cid >= 0))");
    // first some inserts with gaps
    final int maxValue = 1000;
    int stepValue = 3;
    PreparedStatement pstmt = conn
        .prepareStatement("insert into trade.customers values (?, ?)");
    for (int v = 1; v <= maxValue; v += stepValue) {
      pstmt.setInt(1, v * stepValue);
      pstmt.setInt(2, v);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    stmt.execute("create table trade.customers1 (tid int, cid int not null, "
        + "primary key (cid), constraint cust1_ck check (cid >= 0))");
    // first some inserts with gaps
    final int maxValue1 = 1000;
    int stepValue1 = 3;
    PreparedStatement pstmt1 = conn
        .prepareStatement("insert into trade.customers1 values (?, ?)");
    for (int v = 1; v <= maxValue1; v += stepValue1) {
      pstmt1.setInt(1, v * stepValue1);
      pstmt1.setInt(2, v);
      pstmt1.addBatch();
    }
    pstmt1.executeBatch();

    // now add the GENERATED IDENTITY column specification
    stmt.execute("alter table trade.customers alter column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");

    stmt.execute("alter table trade.customers1 alter column cid "
        + "SET GENERATED ALWAYS AS IDENTITY");
  }

  // Test that an ALTER TABLE DROP COLUMN RESTRICT
  // properly undoes the actions if a constraint is defined on the target column
  // This surfaced as bug #46703
  public void testAlterRestrictCKUndo() throws Exception{
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
        + "price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id),"
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
        + "partition by range (sec_id) ( VALUES BETWEEN 0 AND 409, VALUES BETWEEN 409 AND 1102, VALUES BETWEEN 1102 AND 1251, "
        + "VALUES BETWEEN 1251 AND 1477, VALUES BETWEEN 1477 AND 1700, VALUES BETWEEN 1700 AND 10000)");
    try {
      // DROP COLUMN should fail as SEC_UQ unique constraint is using column SYMBOL
      stmt.execute("alter table trade.securities drop column symbol restrict");
      fail("alter table drop column restrict should have failed w/sqlstate X0Y25");
      }
      catch(SQLException e) {
        if(!"X0Y25".equalsIgnoreCase(e.getSQLState())) {
          fail("Alter table exception should have occurred but have different exception", e);
        }
      } 
    // Drop the unique constraint stopping the drop column
    stmt.execute("alter table trade.securities drop unique SEC_UQ");
    // Now drop the column for real - this should move the check constraint column info
    stmt.execute("alter table trade.securities drop column symbol restrict");
    // Add back the column and constraint
    stmt.execute("alter table trade.securities add column symbol varchar(10)");
    stmt.execute("alter table trade.securities add constraint sec_uq unique (symbol,exchange)");
    
    // Now try an UPDATE. Previously, this failed complaining that column EXCHANGE did not exist
    // because the first DROP COLUMN mangled the referencedColumns of the check constraint, and 
    // this did not get undone by the rollback of that statement, when constraint SEC_UQ was added
    // back, the referencedColumns pointed to a nonexistent column id
    stmt.execute("update trade.securities set price = 5.0 where sec_id = 5 and tid=5");
  }  

  // Test for bug 47044
  // After drop/create of column and adding back constraint on that column
  //  the symptom of the bug was that the index became a local sorted map instead of staying a global hash
  public void testAlterTableIndexTypes() throws Exception{
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    // Create TRADE.SECURITIES table partitioned by PRICE
    stmt.execute("create table trade.securities (sec_id int not null, symbol varchar(10) not null,"
        + "price decimal (30, 20), exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id),"
        + "constraint sec_uq unique (symbol, exchange), constraint exc_ch check (exchange in ('nasdaq', 'nye', 'amex', 'lse', 'fse', 'hkse', 'tse')))"
        + "partition by range (price) (VALUES BETWEEN 0.0 AND 25.0,  VALUES BETWEEN 25.0  AND 35.0 , VALUES BETWEEN 35.0  AND 49.0, VALUES "
        + "BETWEEN 49.0  AND 69.0 ,VALUES BETWEEN 69.0 AND 100.0)");
    // The type of the SEC_UQ index should be global hash
    // because it is not a superset of the partitioning column (RANGE)
    checkIndexType("trade", "securities", "GLOBALHASH", "symbol", "exchange");
    // Drop the unique constraint stopping the drop column
    stmt.execute("alter table trade.securities drop unique SEC_UQ");
    // Now drop the column itself
    stmt.execute("alter table trade.securities drop column symbol restrict");
    // Add back the column and constraint
    stmt.execute("alter table trade.securities add column symbol varchar(10) not null default 'a'");
    // This constraint should still be a global hash index even after alter table dropped/readded the column
    stmt.execute("alter table trade.securities add constraint sec_uq unique (symbol,exchange)");
    checkIndexType("trade", "securities", "GLOBALHASH", "symbol", "exchange");
  }  
  
  public static void checkAddColumnWithData(Connection conn)
      throws SQLException {
    Statement stmt = conn.createStatement();
    PreparedStatement prepStmt = conn.prepareStatement("alter table "
        + "trade.customers add column cid int not null default 1");
    prepStmt.execute();

    // add some more data and fire a few different queries to ensure that
    // everything is okay
    stmt.execute("insert into trade.customers (cust_name, tid) "
        + "values ('CUST1', 1)");
    stmt.execute("insert into trade.customers (cust_name, tid) "
        + "values ('CUST2', 2)");
    stmt.execute("insert into trade.customers values ('CUST3', 3, 3)");

    Object[][] expectedRows = new Object[][] {
       new Object[] { "CUST1", 1, 1 },
       new Object[] { "CUST1", 1, 1 },
       new Object[] { "CUST2", 2, 1 },
       new Object[] { "CUST3", 3, 3 },
    };
    ResultSet rs = stmt.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 1 },
        new Object[] { "CUST1", 1 },
        new Object[] { "CUST2", 1 },
        new Object[] { "CUST3", 3 },
    };
    rs = stmt.executeQuery("select cust_name, cid from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // fire some updates to ensure that seemless schema upgrade is working
    assertEquals(2,
        stmt.executeUpdate("update trade.customers set cid=2 where tid=1"));
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 1, 2 },
        new Object[] { "CUST1", 1, 2 },
        new Object[] { "CUST2", 2, 1 },
        new Object[] { "CUST3", 3, 3 },
    };
    rs = stmt.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2 },
        new Object[] { "CUST1", 2 },
        new Object[] { "CUST2", 1 },
        new Object[] { "CUST3", 3 },
    };
    rs = stmt.executeQuery("select cust_name, cid from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // also try updates on another column
    assertEquals(2,
        stmt.executeUpdate("update trade.customers set tid=2 where cid=2"));
    assertEquals(1, stmt.executeUpdate("update trade.customers set tid=20 "
        + "where cust_name='CUST2'"));
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2, 2 },
        new Object[] { "CUST1", 2, 2 },
        new Object[] { "CUST2", 20, 1 },
        new Object[] { "CUST3", 3, 3 },
    };
    rs = stmt.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2 },
        new Object[] { "CUST1", 2 },
        new Object[] { "CUST2", 20 },
        new Object[] { "CUST3", 3 },
    };
    rs = stmt.executeQuery("select cust_name, tid from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "add column addr varchar(100)");
    prepStmt.execute();
    checkDefaultPartitioning("trade.customers");
    checkNoIndex("trade", "customers", "cid");
    checkNoIndex("trade", "customers", "tid");
    checkNoIndex("trade", "customers", "addr");
    // now add more data and fire a few different queries to ensure that
    // everything is okay
    stmt.execute("insert into trade.customers (cust_name, tid) "
        + "values ('CUST1', 1)");
    stmt.execute("insert into trade.customers (cust_name, tid, cid) "
        + "values ('CUST2', 2, 2)");
    stmt.execute("insert into trade.customers values ('CUST3', 3, 3, 'ADDR3')");

    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2, 2, null },
        new Object[] { "CUST1", 2, 2, null },
        new Object[] { "CUST2", 20, 1, null },
        new Object[] { "CUST3", 3, 3, null },
        new Object[] { "CUST1", 1, 1, null },
        new Object[] { "CUST2", 2, 2, null },
        new Object[] { "CUST3", 3, 3, "ADDR3" },
    };
    rs = stmt.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2, null },
        new Object[] { "CUST1", 2, null },
        new Object[] { "CUST2", 20, null },
        new Object[] { "CUST3", 3, null },
        new Object[] { "CUST1", 1, null },
        new Object[] { "CUST2", 2, null },
        new Object[] { "CUST3", 3, "ADDR3" },
    };
    rs = stmt.executeQuery("select cust_name, tid, addr from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // fire some updates to ensure that seemless schema upgrade is working
    assertEquals(2,
        stmt.executeUpdate("update trade.customers set cid=2 where tid=3"));
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2, 2, null },
        new Object[] { "CUST1", 2, 2, null },
        new Object[] { "CUST2", 20, 1, null },
        new Object[] { "CUST3", 3, 2, null },
        new Object[] { "CUST1", 1, 1, null },
        new Object[] { "CUST2", 2, 2, null },
        new Object[] { "CUST3", 3, 2, "ADDR3" },
    };
    rs = stmt.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2, null },
        new Object[] { "CUST1", 2, null },
        new Object[] { "CUST2", 1, null },
        new Object[] { "CUST3", 2, null },
        new Object[] { "CUST1", 1, null },
        new Object[] { "CUST2", 2, null },
        new Object[] { "CUST3", 2, "ADDR3" },
    };
    rs = stmt.executeQuery("select cust_name, cid, addr from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);

    // also try updates on newly added column
    assertEquals(2, stmt.executeUpdate("update trade.customers set "
        + "addr='ADDR30' where cust_name='CUST3'"));
    assertEquals(2, stmt.executeUpdate("update trade.customers set "
        + "tid=30 where addr='ADDR30'"));
    verifyAddColumnWithData(conn);
  }

  public static void verifyAddColumnWithData(Connection conn) throws SQLException {
    Statement stmt = conn.createStatement();
    Object[][] expectedRows = new Object[][] {
        new Object[] { "CUST1", 2, 2, null },
        new Object[] { "CUST1", 2, 2, null },
        new Object[] { "CUST2", 20, 1, null },
        new Object[] { "CUST3", 30, 2, "ADDR30" },
        new Object[] { "CUST1", 1, 1, null },
        new Object[] { "CUST2", 2, 2, null },
        new Object[] { "CUST3", 30, 2, "ADDR30" },
    };
    ResultSet rs = stmt.executeQuery("select * from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);
    expectedRows = new Object[][] {
        new Object[] { "CUST1", 2, null },
        new Object[] { "CUST1", 2, null },
        new Object[] { "CUST2", 20, null },
        new Object[] { "CUST3", 30, "ADDR30" },
        new Object[] { "CUST1", 1, null },
        new Object[] { "CUST2", 2, null },
        new Object[] { "CUST3", 30, "ADDR30" },
    };
    rs = stmt.executeQuery("select cust_name, tid, addr from trade.customers");
    JDBC.assertUnorderedResultSet(rs, expectedRows, false);
  }

  public static void verifyAddColumnWithData(Connection conn, int numExpected,
      boolean afterUpdate) throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs;
    final Integer[] foundResults = new Integer[numExpected];

    rs = stmt.executeQuery("select * from trade.customers");
    int numResults = 0;
    while (rs.next()) {
      String custName = rs.getString(1);
      String addr = rs.getString(2);
      int index = Integer.parseInt(custName.substring(4));
      int tid = rs.getInt(3);
      int cid = rs.getInt(4);
      if (foundResults[index] != null) {
        fail("duplicate results in customers table for index=" + index);
      }
      foundResults[index] = cid;

      if (afterUpdate
          && (index == 2 || index == 3 || index == 10 || index == 30)) {
        switch (index) {
          case 2:
            assertEquals("ADDR2", addr);
            assertEquals(20, tid);
            assertEquals(1, cid);
            break;
          case 3:
          case 30:
            assertEquals("ADDR30", addr);
            assertEquals(30, tid);
            assertEquals(3, cid);
            break;
          case 10:
            assertEquals("ADDR10", addr);
            assertEquals(1, tid);
            assertEquals(10, cid);
            break;
          default:
            fail("unexpected index=" + index);
        }
      }
      else {
        assertEquals("ADDR" + index, addr);
        assertEquals(1, tid);
        assertEquals(1, cid);
      }
      numResults++;
    }
    assertEquals(numExpected, numResults);
    clearArray(foundResults);
    rs.close();

    rs = stmt.executeQuery("select cust_name, tid, addr from trade.customers");
    numResults = 0;
    while (rs.next()) {
      String custName = rs.getString(1);
      String addr = rs.getString(3);
      int index = Integer.parseInt(custName.substring(4));
      int tid = rs.getInt(2);
      if (foundResults[index] != null) {
        fail("duplicate results in customers table for index=" + index);
      }
      foundResults[index] = tid;

      if (afterUpdate
          && (index == 2 || index == 3 || index == 10 || index == 30)) {
        switch (index) {
          case 2:
            assertEquals("ADDR2", addr);
            assertEquals(20, tid);
            break;
          case 3:
          case 30:
            assertEquals("ADDR30", addr);
            assertEquals(30, tid);
            break;
          case 10:
            assertEquals("ADDR10", addr);
            assertEquals(1, tid);
            break;
          default:
            fail("unexpected index=" + index);
        }
      }
      else {
        assertEquals("ADDR" + index, addr);
        assertEquals(1, tid);
      }
      numResults++;
    }
    assertEquals(numExpected, numResults);
    clearArray(foundResults);
    rs.close();

    if (!afterUpdate) {
      // also try updates on newly added columns
      assertEquals(1, stmt.executeUpdate("update trade.customers set "
          + "addr='ADDR30' where cust_name='CUST3'"));
      assertEquals(2, stmt.executeUpdate("update trade.customers set "
          + "tid=30 where addr='ADDR30'"));
      assertEquals(2, stmt.executeUpdate("update trade.customers set "
          + "cid=3 where tid=30"));
      assertEquals(1, stmt.executeUpdate("update trade.customers set "
          + "cid=10 where cust_name='CUST10'"));
      assertEquals(1, stmt.executeUpdate("update trade.customers set "
          + "tid=20 where addr='ADDR2'"));

      verifyAddColumnWithData(conn, numExpected, true);
    }
  }

  public static void checkDropColumnWithData(Connection conn)
      throws SQLException {
    Statement stmt = conn.createStatement();
    CreateTableTest.populateData(conn, true, false);
    // dropping other columns should be possible with data
    PreparedStatement prepStmt = conn.prepareStatement("alter table "
        + "trade.customers drop column addr");
    prepStmt.execute();
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column qty cascade");
    prepStmt.execute();

    // check everything is fine after drop and constraints properly applied
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkConstraints(stmt, new ConstraintNumber[] { ConstraintNumber.CUST_PK,
        ConstraintNumber.CUST_UK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK, ConstraintNumber.PORT_CK1,
        ConstraintNumber.PORT_CK2 }, ConstraintNumber.CUST_PK2,
        ConstraintNumber.CUST_UK2, ConstraintNumber.CUST_FK2,
        ConstraintNumber.PORT_PK2);

    verifyDropColumnWithData(conn, false, false, false, false);

    // now add back the columns and check again (#47054)
    stmt.execute("alter table trade.customers add column addr varchar(100) "
        + "not null default 'NO_ADDRESS'");
    stmt.execute("alter table trade.portfolio add column qty int "
        + "not null default 0");
    verifyDropColumnWithData(conn, false, true, false, true);

    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.portfolio drop column qty");
    stmt.execute("alter table trade.portfolio add column qty int default 0");
    stmt.execute("alter table trade.customers add column addr varchar(100) "
        + "default 'NO_ADDRESS'");
    verifyDropColumnWithData(conn, false, true, false, true);

    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.portfolio drop column qty");
    stmt.execute("alter table trade.customers add column addr char(10) "
        + "not null default 'NO_ADDRESS'");
    stmt.execute("alter table trade.portfolio add column qty bigint "
        + "not null default 0");
    verifyDropColumnWithData(conn, false, true, false, true);

    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.portfolio drop column qty");
    stmt.execute("alter table trade.portfolio add column qty int");
    stmt.execute("alter table trade.customers add column addr char(100)");
    verifyDropColumnWithData(conn, false, true, true, true);

    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.portfolio drop column qty");
    stmt.execute("alter table trade.portfolio add column qty int");
    stmt.execute("alter table trade.customers add column addr varchar(100)");

    // add more data and check again
    CreateTableTest.populateData(conn, true, false, false, true, false,
        "trade.customers", "trade.portfolio");
    checkDefaultPartitioning("trade.customers", "cid");
    checkDefaultPartitioning("trade.portfolio", "cid");
    checkColocation("trade.portfolio", "trade", "customers");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkIndexType("trade", "portfolio", "LOCALHASH1", "cid", "sid");
    checkIndexType("trade", "portfolio", "LOCALSORTEDMAP", "cid");
    checkConstraints(stmt, new ConstraintNumber[] { ConstraintNumber.CUST_PK,
        ConstraintNumber.CUST_UK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK, ConstraintNumber.PORT_CK1,
        ConstraintNumber.PORT_CK2 }, ConstraintNumber.CUST_PK2,
        ConstraintNumber.CUST_UK2, ConstraintNumber.CUST_FK2,
        ConstraintNumber.PORT_PK2);

    verifyDropColumnWithData(conn, true, true, true, false);

    // now drop/add the columns and check again (#47054)
    stmt.execute("alter table trade.customers drop column addr cascade");
    stmt.execute("alter table trade.portfolio drop column qty cascade");
    stmt.execute("alter table trade.customers add column addr varchar(100) "
        + "not null default 'NO_ADDRESS'");
    stmt.execute("alter table trade.portfolio add column qty int "
        + "not null default 0");
    verifyDropColumnWithData(conn, true, true, false, true);

    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.portfolio drop column qty");
    stmt.execute("alter table trade.portfolio add column qty int default 0");
    stmt.execute("alter table trade.customers add column addr varchar(100) "
        + "default 'NO_ADDRESS'");
    verifyDropColumnWithData(conn, true, true, false, true);

    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.portfolio drop column qty");
    stmt.execute("alter table trade.customers add column addr char(10) "
        + "not null default 'NO_ADDRESS'");
    stmt.execute("alter table trade.portfolio add column qty bigint "
        + "not null default 0");
    verifyDropColumnWithData(conn, true, true, false, true);

    stmt.execute("alter table trade.customers drop column addr");
    stmt.execute("alter table trade.portfolio drop column qty");
    stmt.execute("alter table trade.portfolio add column qty int");
    stmt.execute("alter table trade.customers add column addr char(100)");
    verifyDropColumnWithData(conn, true, true, true, true);

    // check dropping others columns succeeds with data but fails for PK columns
    stmt.execute("drop table trade.portfolio");
    stmt.execute("drop table trade.customers");
    CreateTableTest.createTables(conn);
    CreateTableTest.populateData(conn, true, false);
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column cust_name");
    prepStmt.execute();
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    prepStmt = conn.prepareStatement("alter table trade.customers "
        + "drop column cid");
    try {
      prepStmt.execute();
      fail("Expected unsupported exception for PK column drop in ALTER TABLE");
    } catch (SQLException ex) {
      if (!"0A000".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    prepStmt = conn.prepareStatement("alter table trade.portfolio "
        + "drop column qty cascade");
    prepStmt.execute();

    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");

    // verify that the tables are in a good shape
    checkConstraints(stmt, new ConstraintNumber[] { ConstraintNumber.CUST_PK,
        ConstraintNumber.CUST_UK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK, ConstraintNumber.PORT_CK1,
        ConstraintNumber.PORT_CK2 }, ConstraintNumber.CUST_PK3,
        ConstraintNumber.CUST_UK3, ConstraintNumber.CUST_FK2,
        ConstraintNumber.PORT_PK2);

    // add more data and check again
    CreateTableTest.populateData(conn, true, false, true, true, false,
        "trade.customers", "trade.portfolio");
    checkIndexType("trade", "customers", "LOCALHASH1", "cid");
    checkIndexType("trade", "customers", "GLOBALHASH", "tid");
    checkConstraints(stmt, new ConstraintNumber[] { ConstraintNumber.CUST_PK,
        ConstraintNumber.CUST_UK, ConstraintNumber.CUST_FK,
        ConstraintNumber.PORT_PK, ConstraintNumber.PORT_CK1,
        ConstraintNumber.PORT_CK2 }, ConstraintNumber.CUST_PK3,
        ConstraintNumber.CUST_UK3, ConstraintNumber.CUST_FK2,
        ConstraintNumber.PORT_PK2);

    verifyDropColumnWithData(conn, true, false, false, false);
  }

  public static void verifyDropColumnWithData(Connection conn,
      boolean fullData, boolean hasDefaults, boolean nullDefaults,
      boolean allDefaults) throws SQLException {
    Statement stmt = conn.createStatement();

    final Integer[] foundResults;
    final int numExpected;
    if (fullData) {
      foundResults = new Integer[200];
      numExpected = 190;
    }
    else {
      foundResults = new Integer[100];
      numExpected = 100;
    }

    ResultSet rs = stmt.executeQuery("select * from trade.customers");
    int numResults = 0;
    int addrCol;
    try {
      addrCol = rs.findColumn("ADDR");
    } catch (SQLException sqle) {
      if ("S0022".equals(sqle.getSQLState())
          || "XIE08".equals(sqle.getSQLState())) {
        addrCol = -1;
      }
      else {
        throw sqle;
      }
    }
    while (rs.next()) {
      int cid = rs.getInt(1);
      int tid = rs.getInt(3);
      if (foundResults[cid] != null) {
        fail("duplicate results in customers table for cid=" + cid);
      }
      foundResults[cid] = tid;
      assertTrue(Integer.toString(cid), cid < 100 || cid >= 110);

      assertEquals(cid + 1, tid);
      if (addrCol > 0) {
        if (hasDefaults && (cid < 100 || allDefaults)) {
          if (nullDefaults) {
            assertNull(rs.getObject(addrCol));
            assertNull(rs.getString(addrCol));
            assertTrue(rs.wasNull());
          }
          else {
            assertEquals("NO_ADDRESS", rs.getObject(addrCol));
            assertEquals("NO_ADDRESS", rs.getString(addrCol));
          }
        }
        else if (hasDefaults || cid < 100) {
          assertEquals("ADDR" + cid, rs.getObject(addrCol));
          assertEquals("ADDR" + cid, rs.getString(addrCol));
        }
        else {
          assertNull(rs.getObject(addrCol));
          assertNull(rs.getString(addrCol));
          assertTrue(rs.wasNull());
        }
      }
      else {
        if (cid < 100) {
          assertEquals("CUST" + cid, rs.getObject(2));
          assertEquals("CUST" + cid, rs.getString(2));
        }
        else {
          assertNull(rs.getObject(2));
          assertNull(rs.getString(2));
          assertTrue(rs.wasNull());
        }
      }
      numResults++;
    }
    assertEquals(numExpected, numResults);
    clearArray(foundResults);
    rs.close();

    if (addrCol > 0 && hasDefaults) {
      if (fullData) {
        rs = stmt.executeQuery("select addr, cid from trade.customers");
      }
      else {
        rs = stmt.executeQuery("select addr from trade.customers");
      }
      numResults = 0;
      int cid;
      while (rs.next()) {
        if (fullData && !allDefaults && (cid = rs.getInt(2)) >= 100) {
          assertEquals("ADDR" + cid, rs.getObject(1));
          assertEquals("ADDR" + cid, rs.getString(1));
        }
        else if (nullDefaults) {
          assertNull(rs.getObject(1));
          assertNull(rs.getString(1));
          assertTrue(rs.wasNull());
        }
        else {
          assertEquals("NO_ADDRESS", rs.getObject(1));
          assertEquals("NO_ADDRESS", rs.getString(1));
        }
        numResults++;
      }
      assertEquals(numExpected, numResults);
      rs.close();
    }

    rs = stmt.executeQuery("select cid, tid from trade.customers");
    numResults = 0;
    while (rs.next()) {
      int cid = rs.getInt(1);
      int tid = rs.getInt(2);
      if (foundResults[cid] != null) {
        fail("duplicate results in customers table for cid=" + cid);
      }
      foundResults[cid] = tid;
      assertTrue(Integer.toString(cid), cid < 100 || cid >= 110);

      assertEquals(cid + 1, tid);
      numResults++;
    }
    assertEquals(numExpected, numResults);
    clearArray(foundResults);
    rs.close();

    rs = stmt.executeQuery("select * from trade.portfolio");
    numResults = 0;
    final int tidCol = rs.findColumn("TID");
    int qtyCol;
    boolean qtyIsBigInt = false;
    try {
      qtyCol = rs.findColumn("QTY");
      qtyIsBigInt = rs.getMetaData().getColumnType(qtyCol) == Types.BIGINT;
    } catch (SQLException sqle) {
      if ("S0022".equals(sqle.getSQLState())
          || "XIE08".equals(sqle.getSQLState())) {
        qtyCol = -1;
      }
      else {
        throw sqle;
      }
    }
    while (rs.next()) {
      int cid = rs.getInt(1);
      int sid = rs.getInt(2);
      int tid = rs.getInt(tidCol);
      if (foundResults[cid] != null) {
        fail("duplicate results in portfolio table for cid=" + cid);
      }
      foundResults[cid] = tid;
      assertTrue(Integer.toString(cid), cid < 100 || cid >= 110);

      assertEquals(cid + 2, sid);
      assertEquals(cid, tid);
      if (hasDefaults && (cid < 100 || allDefaults)) {
        if (nullDefaults) {
          assertEquals(null, rs.getObject(qtyCol));
          assertEquals(0, rs.getLong(qtyCol));
          assertTrue(rs.wasNull());
        }
        else {
          if (qtyIsBigInt) {
            assertEquals(Long.valueOf(0), rs.getObject(qtyCol));
          }
          else {
            assertEquals(Integer.valueOf(0), rs.getObject(qtyCol));
          }
          assertEquals(0, rs.getLong(qtyCol));
        }
      }
      else if (qtyCol > 0) {
        if (hasDefaults || cid < 100) {
          assertEquals(cid * 10, rs.getObject(qtyCol));
          assertEquals(cid * 10, rs.getLong(qtyCol));
        }
        else {
          assertEquals(null, rs.getObject(qtyCol));
          assertEquals(0, rs.getLong(qtyCol));
          assertTrue(rs.wasNull());
        }
      }
      numResults++;
    }
    assertEquals(numExpected, numResults);
    clearArray(foundResults);
    rs.close();

    if (qtyCol > 0 && hasDefaults) {
      if (fullData) {
        rs = stmt.executeQuery("select qty, cid from trade.portfolio");
      }
      else {
        rs = stmt.executeQuery("select qty from trade.portfolio");
      }
      numResults = 0;
      int cid;
      while (rs.next()) {
        if (fullData && !allDefaults && (cid = rs.getInt(2)) >= 100) {
          assertEquals(cid * 10, rs.getObject(1));
          assertEquals(cid * 10, rs.getLong(1));
        }
        else if (nullDefaults) {
          assertEquals(null, rs.getObject(1));
          assertTrue(rs.wasNull());
          assertEquals(0, rs.getLong(1));
        }
        else if (qtyIsBigInt) {
          assertEquals(Long.valueOf(0), rs.getObject(1));
          assertEquals(0, rs.getLong(1));
        }
        else {
          assertEquals(Integer.valueOf(0), rs.getObject(1));
          assertEquals(0, rs.getLong(1));
        }
        numResults++;
      }
      assertEquals(numExpected, numResults);
      rs.close();
    }

    rs = stmt.executeQuery("select cid, sid, tid from trade.portfolio");
    numResults = 0;
    while (rs.next()) {
      int cid = rs.getInt(1);
      int sid = rs.getInt(2);
      int tid = rs.getInt(3);
      if (foundResults[cid] != null) {
        fail("duplicate results in portfolio table for cid=" + cid);
      }
      foundResults[cid] = tid;
      assertTrue(Integer.toString(cid), cid < 100 || cid >= 110);

      assertEquals(cid + 2, sid);
      assertEquals(cid, tid);
      numResults++;
    }
    assertEquals(numExpected, numResults);
    clearArray(foundResults);
    rs.close();
  }

  private static void clearArray(Object[] array) {
    for (int i = 0; i < array.length; i++) {
      array[i] = null;
    }
  }
  
  public void testBug50116_1() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmnt = conn.createStatement();
    stmnt.execute("create table p1(col1 int primary key, col2 int) " +
       "partition by (col1) persistent");
    stmnt.execute("create table c1(a int, b int, constraint" +
       " fk_c1 foreign key (a) references p1(col1)) partition by (a)");
    stmnt.execute("alter table c1 drop constraint app.fk_c1");
    stmnt.execute("drop table p1");
    shutDown();
    conn = TestUtil.getConnection();
    stmnt = conn.createStatement();
    stmnt.execute("insert into c1 values(1, 1)");
    stmnt.execute("insert into c1 values(2, 2)");
    stmnt.execute("select * from c1");
    ResultSet rs = stmnt.getResultSet();
    assertTrue(rs.next());
    assertTrue(rs.next());
    shutDown();
    conn = TestUtil.getConnection();
  }
  
  public void testBug50116_2() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmnt = conn.createStatement();
    stmnt.execute("create table p1(col1 int primary key, col2 int) " +
       "partition by (col1) persistent");
    
    stmnt.execute("create table p2(col1 int primary key, col2 int) " +
        "partition by (col1) persistent");
    
    stmnt.execute("create table c1(a1 int, b1 int, constraint" +
       " fk_c1_1 foreign key (a1) references p1(col1), constraint " +
       "fk_c1_2 foreign key (b1) references p2(col1)) partition by (a1)");
    
    stmnt.execute("create table c2(a2 int primary key, b2 int, constraint" +
        " fk_c2 foreign key (a2) references p1(col1)) partition by (a2)");
    
    stmnt.execute("alter table c1 drop constraint fk_c1_1");
    stmnt.execute("alter table c1 drop constraint fk_c1_2");
    
    stmnt.execute("alter table c2 drop constraint fk_c2");
    stmnt.execute("alter table c2 drop primary key");
    
//    stmnt.execute("drop table p1");
    stmnt.execute("drop table p2");
    
    shutDown();
    conn = TestUtil.getConnection();
    stmnt = conn.createStatement();
    stmnt.execute("insert into c1 values(1, 1)");
    stmnt.execute("insert into c1 values(2, 2)");
    stmnt.execute("select * from c1");
    ResultSet rs = stmnt.getResultSet();
    assertTrue(rs.next());
    assertTrue(rs.next());
    
    stmnt.execute("insert into c2 values(1, 1)");
    stmnt.execute("insert into c2 values(2, 2)");
    stmnt.execute("select * from c2");
    rs = stmnt.getResultSet();
    assertTrue(rs.next());
    assertTrue(rs.next());
    
    shutDown();
    conn = TestUtil.getConnection();
  }
  
  public void testBug50116_3() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmnt = conn.createStatement();
    stmnt.execute("create table p1(col1 int primary key, col2 int) " +
       "partition by (col1) persistent");
    
    stmnt.execute("create table p2(col1 int primary key, col2 int) " +
        "partition by (col1) persistent");
    
    stmnt.execute("create table p3(col1 int primary key, col2 int) " +
        "partition by (col1) persistent");
    
    stmnt.execute("insert into p1 values(1, 1)");
    stmnt.execute("insert into p2 values(1, 1)");
    stmnt.execute("insert into p3 values(1, 1)");
    
    stmnt.execute("create table c1(a1 int, b1 int, c1 int, constraint" +
       " fk_c1_1 foreign key (a1) references p1(col1)) " +
       " partition by (a1)");
    
    stmnt.execute("alter table c1 add constraint fk_c1_2 " +
            "foreign key (b1) references p2(col1)");
    stmnt.execute("alter table c1 add constraint fk_c1_3 " +
            "foreign key (c1) references p3(col1)");
    
    stmnt.execute("alter table c1 drop constraint fk_c1_2");
    stmnt.execute("alter table c1 drop constraint fk_c1_3");
    
    shutDown();
    conn = TestUtil.getConnection();
    stmnt = conn.createStatement();
    stmnt.execute("insert into c1 values(1, 1, 1)");
    stmnt.execute("select * from c1");
    ResultSet rs = stmnt.getResultSet();
    assertTrue(rs.next());
    stmnt.execute("alter table c1 drop constraint fk_c1_1");
//    stmnt.execute("drop table p1");
    
    shutDown();
    conn = TestUtil.getConnection();
    stmnt = conn.createStatement();
    stmnt.execute("drop table c1");
    
    shutDown();
    conn = TestUtil.getConnection();
  }
  
  public void testBug50116_4() throws Exception {
    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
    Connection conn = TestUtil.getConnection(props);
    Statement stmnt = conn.createStatement();
    stmnt.execute("create table p1(col1 int primary key, col2 int) " +
       "partition by (col1) persistent");
    
    stmnt.execute("create table p2(col1 int primary key, col2 int) " +
        "partition by (col1) persistent");
    
    stmnt.execute("insert into p1 values(1, 1)");
    stmnt.execute("insert into p2 values(1, 1)");
    
    stmnt.execute("create table c1(a1 int, b1 int, c1 int) " +
       " partition by (a1)");
    
    stmnt.execute("alter table c1 add constraint fk_c1_1 " +
        "foreign key (a1) references p1(col1)");
    stmnt.execute("alter table c1 add constraint fk_c1_2 " +
            "foreign key (b1) references p2(col1)");
    
    stmnt.execute("alter table c1 drop constraint fk_c1_1");
    stmnt.execute("alter table c1 drop constraint fk_c1_2");
    
    shutDown();
    conn = TestUtil.getConnection();
    stmnt = conn.createStatement();
    stmnt.execute("insert into c1 values(1, 1, 1)");
    stmnt.execute("select * from c1");
    ResultSet rs = stmnt.getResultSet();
    assertTrue(rs.next());
    
    stmnt.execute("alter table c1 add constraint fk_c1_1 " +
        "foreign key (a1) references p1(col1)");
    stmnt.execute("alter table c1 add constraint fk_c1_2 " +
        "foreign key (b1) references p2(col1)");
    
    shutDown();
    conn = TestUtil.getConnection();
    stmnt = conn.createStatement();
    stmnt.execute("alter table c1 drop constraint fk_c1_1");
    stmnt.execute("alter table c1 drop constraint fk_c1_2");
    stmnt.execute("drop table p1");
    
    shutDown();
    conn = TestUtil.getConnection();
    stmnt = conn.createStatement();
  }

}
