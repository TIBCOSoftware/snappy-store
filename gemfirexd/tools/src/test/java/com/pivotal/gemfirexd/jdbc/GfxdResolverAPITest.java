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
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Iterator;

import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.internal.cache.EntryOperationImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdListPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdRangePartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;

public class GfxdResolverAPITest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(GfxdResolverAPITest.class));
  }

  public GfxdResolverAPITest(String name) {
    super(name);
  }

  // /////////////////////
  // START RANGE TEST
  // /////////////////////

  // testRangeResolver has 4 tests. All of them are same, except in 1 the
  // partitioning column is
  // a subset of primary, in 2 is exactly primary, in 3 it is non primary but a
  // different primary key
  // defined and in 4 no primary key at all.
  public void testRangeResolver_1() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by range (sid) ( VALUES BETWEEN 0 AND 5, VALUES BETWEEN 10 AND 15, "
            + "VALUES BETWEEN 20 AND 23, VALUES BETWEEN 25 AND 30, VALUES BETWEEN 31 AND 100)");

    GfxdRangePartitionResolver rpr = (GfxdRangePartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(rpr);

    String[] sarr = rpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, rpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, rpr.getPartitioningColumnsCount());
    Integer robj = (Integer)rpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(5, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)rpr.getRoutingObjectFromDvdArray(values);
    assertEquals(1, robj.intValue());
  }

  public void testRangeResolver_2() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by range (sid) ( VALUES BETWEEN 0 AND 5, VALUES BETWEEN 10 AND 15, "
            + "VALUES BETWEEN 20 AND 23, VALUES BETWEEN 25 AND 30, VALUES BETWEEN 31 AND 100)");

    GfxdRangePartitionResolver rpr = (GfxdRangePartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(rpr);

    String[] sarr = rpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, rpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, rpr.getPartitioningColumnsCount());
    Integer robj = (Integer)rpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(5, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)rpr.getRoutingObjectFromDvdArray(values);
    assertEquals(1, robj.intValue());
  }

  public void testRangeResolver_3() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by range (sid) ( VALUES BETWEEN 0 AND 5, VALUES BETWEEN 10 AND 15, "
            + "VALUES BETWEEN 20 AND 23, VALUES BETWEEN 25 AND 30, VALUES BETWEEN 31 AND 100)");

    GfxdRangePartitionResolver rpr = (GfxdRangePartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(rpr);

    String[] sarr = rpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, rpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, rpr.getPartitioningColumnsCount());
    Integer robj = (Integer)rpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(5, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)rpr.getRoutingObjectFromDvdArray(values);
    assertEquals(1, robj.intValue());
  }

  public void testRangeResolver_4() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by range (sid) ( VALUES BETWEEN 0 AND 5, VALUES BETWEEN 10 AND 15, "
            + "VALUES BETWEEN 20 AND 23, VALUES BETWEEN 25 AND 30, VALUES BETWEEN 31 AND 100)");

    GfxdRangePartitionResolver rpr = (GfxdRangePartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(rpr);

    String[] sarr = rpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, rpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, rpr.getPartitioningColumnsCount());
    Integer robj = (Integer)rpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(5, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)rpr.getRoutingObjectFromDvdArray(values);
    assertEquals(1, robj.intValue());
  }

  // /////////////////////
  // END RANGE TEST
  // /////////////////////

  // /////////////////////
  // START LIST TEST
  // /////////////////////

  // testListResolver has 4 tests. All of them are same, except in 1 the
  // partitioning column is
  // a subset of primary, in 2 is exactly primary, in 3 it is non primary but a
  // different primary key
  // defined and in 4 no primary key at all.
  public void testListResolver_1() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by list (sid) ( VALUES (0, 5) , VALUES (10, 15), "
            + "VALUES (20, 23), VALUES(25, 30), VALUES(31, 100))");

    GfxdListPartitionResolver lpr = (GfxdListPartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(lpr);

    String[] sarr = lpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, lpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, lpr.getPartitioningColumnsCount());
    Integer robj = (Integer)lpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(0, robj.intValue());
    int maxWidth = DataTypeDescriptor
        .getBuiltInDataTypeDescriptor(Types.INTEGER).getMaximumWidth();
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)lpr.getRoutingObjectFromDvdArray(values);
    assertEquals(new SQLInteger(1).computeHashCode(maxWidth, 0),
        robj.intValue());
  }

  public void testListResolver_2() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by list (sid) ( VALUES (0, 5) , VALUES (10, 15), "
            + "VALUES (20, 23), VALUES(25, 30), VALUES(31, 100))");

    GfxdListPartitionResolver lpr = (GfxdListPartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(lpr);

    String[] sarr = lpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, lpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, lpr.getPartitioningColumnsCount());
    Integer robj = (Integer)lpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(0, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)lpr.getRoutingObjectFromDvdArray(values);
    assertEquals(1, robj.intValue());
  }

  public void testListResolver_3() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by list (sid) ( VALUES (0, 5) , VALUES (10, 15), "
            + "VALUES (20, 23), VALUES(25, 30), VALUES(31, 100))");

    GfxdListPartitionResolver lpr = (GfxdListPartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(lpr);

    String[] sarr = lpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, lpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, lpr.getPartitioningColumnsCount());
    Integer robj = (Integer)lpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(0, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)lpr.getRoutingObjectFromDvdArray(values);
    int maxWidth = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
        Types.INTEGER).getMaximumWidth();
    assertEquals(new SQLInteger(1).computeHashCode(maxWidth, 0),
        robj.intValue());
  }

  public void testListResolver_4() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by list (sid) ( VALUES (0, 5) , VALUES (10, 15), "
            + "VALUES (20, 23), VALUES(25, 30), VALUES(31, 100))");

    GfxdListPartitionResolver lpr = (GfxdListPartitionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(lpr);

    String[] sarr = lpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, lpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, lpr.getPartitioningColumnsCount());
    Integer robj = (Integer)lpr.getRoutingKeyForColumn(new SQLInteger(5));
    assertEquals(0, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)lpr.getRoutingObjectFromDvdArray(values);
    int maxWidth = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
        Types.INTEGER).getMaximumWidth();
    assertEquals(new SQLInteger(1).computeHashCode(maxWidth, 0),
        robj.intValue());
  }

  // /////////////////////
  // END LIST TEST
  // /////////////////////

  // /////////////////////
  // START COLUMNS TEST
  // /////////////////////
  public void testColumnResolver_1() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by column (sid)");

    GfxdPartitionByExpressionResolver cpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(cpr);

    String[] sarr = cpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, cpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, cpr.getPartitioningColumnsCount());
    DataValueDescriptor dvd = new SQLInteger(5);
    Integer robj = (Integer)cpr.getRoutingKeyForColumn(dvd);
    int expectedHash = GfxdPartitionByExpressionResolver.computeHashCode(dvd,
        null, 0);
    assertEquals(expectedHash, robj.intValue());
    dvd = new SQLInteger(1);
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), dvd };
    robj = (Integer)cpr.getRoutingObjectFromDvdArray(values);
    expectedHash = GfxdPartitionByExpressionResolver.computeHashCode(dvd, null,
        0);
    assertEquals(expectedHash, robj.intValue());
  }

  public void testColumnResolver_2() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by column (cid, sid)");

    GfxdPartitionByExpressionResolver cpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(cpr);

    String[] sarr = cpr.getColumnNames();
    assertEquals(2, sarr.length);
    assertEquals(0, cpr.getPartitioningColumnIndex("CID"));
    assertEquals(1, cpr.getPartitioningColumnIndex("SID"));
    assertEquals(2, cpr.getPartitioningColumnsCount());
    // Integer robj = (Integer)cpr.getRoutingKeyForColumn(new Integer(5)); //
    // this will give assertion error
    // assertEquals(5, robj.intValue());
    DataValueDescriptor cid = new SQLInteger(6);
    DataValueDescriptor sid = new SQLInteger(1);
    DataValueDescriptor[] values = new DataValueDescriptor[] { cid,
        new SQLInteger(71), new SQLInteger(10), new SQLInteger(100), sid };
    int hashcode = GfxdPartitionByExpressionResolver.computeHashCode(cid, null,
        0);
    hashcode = GfxdPartitionByExpressionResolver.computeHashCode(sid, null,
        hashcode);
    Integer robj = (Integer)cpr.getRoutingObjectFromDvdArray(values);
    assertEquals(hashcode, robj.intValue());
  }

  public void testColumnResolver_3() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by column (cid, sid)");

    GfxdPartitionByExpressionResolver cpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(cpr);

    String[] sarr = cpr.getColumnNames();
    assertEquals(2, sarr.length);
    assertEquals(0, cpr.getPartitioningColumnIndex("CID"));
    assertEquals(1, cpr.getPartitioningColumnIndex("SID"));
    assertEquals(2, cpr.getPartitioningColumnsCount());
    // Integer robj = (Integer)cpr.getRoutingKeyForColumn(new Integer(5)); //
    // this will give assertion error
    // assertEquals(5, robj.intValue());
    DataValueDescriptor cid = new SQLInteger(6);
    DataValueDescriptor sid = new SQLInteger(1);
    DataValueDescriptor[] values = new DataValueDescriptor[] { cid,
        new SQLInteger(71), new SQLInteger(10), new SQLInteger(100), sid };
    int hashcode = GfxdPartitionByExpressionResolver.computeHashCode(cid, null,
        0);
    hashcode = GfxdPartitionByExpressionResolver.computeHashCode(sid, null,
        hashcode);
    Integer robj = (Integer)cpr.getRoutingObjectFromDvdArray(values);
    assertEquals(hashcode, robj.intValue());
  }

  public void testColumnResolver_4() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by column (cid, sid)");

    GfxdPartitionByExpressionResolver cpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(cpr);

    String[] sarr = cpr.getColumnNames();
    assertEquals(2, sarr.length);
    assertEquals(0, cpr.getPartitioningColumnIndex("CID"));
    assertEquals(1, cpr.getPartitioningColumnIndex("SID"));
    assertEquals(2, cpr.getPartitioningColumnsCount());
    // Integer robj = (Integer)cpr.getRoutingKeyForColumn(new Integer(5)); //
    // this will give assertion error
    // assertEquals(5, robj.intValue());
    DataValueDescriptor cid = new SQLInteger(6);
    DataValueDescriptor sid = new SQLInteger(1);
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    int hashcode = GfxdPartitionByExpressionResolver.computeHashCode(cid, null,
        0);
    hashcode = GfxdPartitionByExpressionResolver.computeHashCode(sid, null,
        hashcode);
    Integer robj = (Integer)cpr.getRoutingObjectFromDvdArray(values);
    assertEquals(hashcode, robj.intValue());
  }

  public void testColumnResolver_5() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by primary key");

    GfxdPartitionByExpressionResolver cpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(cpr);

    String[] sarr = cpr.getColumnNames();
    assertEquals(2, sarr.length);
    assertEquals(0, cpr.getPartitioningColumnIndex("CID"));
    assertEquals(1, cpr.getPartitioningColumnIndex("SID"));
    assertEquals(2, cpr.getPartitioningColumnsCount());
    // Integer robj = (Integer)cpr.getRoutingKeyForColumn(new Integer(5)); //
    // this will give assertion error
    // assertEquals(5, robj.intValue());
    DataValueDescriptor cid = new SQLInteger(6);
    DataValueDescriptor sid = new SQLInteger(1);
    DataValueDescriptor[] values = new DataValueDescriptor[] { cid,
        new SQLInteger(71), new SQLInteger(10), new SQLInteger(100),
        new SQLInteger(1) };
    int hashcode = GfxdPartitionByExpressionResolver.computeHashCode(cid, null,
        0);
    hashcode = GfxdPartitionByExpressionResolver.computeHashCode(sid, null,
        hashcode);
    Integer robj = (Integer)cpr.getRoutingObjectFromDvdArray(values);
    assertEquals(hashcode, robj.intValue());
  }

  public void testColumnResolver_6() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))"
            + "partition by primary key");

    GfxdPartitionByExpressionResolver cpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(cpr);

    String[] sarr = cpr.getColumnNames();
    assertEquals(1, sarr.length);
    assertEquals(0, cpr.getPartitioningColumnIndex("SID"));
    assertEquals(1, cpr.getPartitioningColumnsCount());
    Integer robj = (Integer)cpr.getRoutingKeyForColumn(new SQLInteger(5)); // this
                                                                           // will
                                                                           // give
                                                                           // assertion
                                                                           // error
    assertEquals(5, robj.intValue());
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    robj = (Integer)cpr.getRoutingObjectFromDvdArray(values);
    assertEquals(1, robj.intValue());
  }

  // /////////////////////
  // END COLUMNS TEST
  // /////////////////////

  // /////////////////////
  // START DEFAULT TEST
  // /////////////////////
  // Invalid test because the two tables in question should not colocate
  // as 1st one has an expression so that the second should not colocate
  public void _testDefaultResolver_1() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid), "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))");

    GfxdPartitionByExpressionResolver dpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(dpr);
    assertTrue(dpr.isDefaultPartitioning());

    String[] sarr = dpr.getColumnNames();
    assertEquals(2, sarr.length);
    assertEquals(0, dpr.getPartitioningColumnIndex("CID"));
    assertEquals(1, dpr.getPartitioningColumnIndex("SID"));
    assertEquals(2, dpr.getPartitioningColumnsCount());
    DataValueDescriptor cid = new SQLInteger(5);
    DataValueDescriptor sid = new SQLInteger(10);
    Integer robj = (Integer)dpr
        .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
            new SQLInteger(5), new SQLInteger(10) });
    int hashcode = GfxdPartitionByExpressionResolver.computeHashCode(cid, null,
        0);
    hashcode = GfxdPartitionByExpressionResolver.computeHashCode(sid, null,
        hashcode);
    assertEquals(hashcode, robj.intValue());

    cid = new SQLInteger(6);
    sid = new SQLInteger(1);
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), sid };
    robj = (Integer)dpr.getRoutingObjectFromDvdArray(values);
    hashcode = GfxdPartitionByExpressionResolver.computeHashCode(cid, null, 0);
    hashcode = GfxdPartitionByExpressionResolver.computeHashCode(sid, null,
        hashcode);
    assertEquals(hashcode, robj.intValue());
  }

  public void testDefaultResolver_2() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint qty_ck check (qty>=0), constraint avail_ch check (availQty>=0 and availQty<=qty))");

    GfxdPartitionByExpressionResolver dpr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(dpr);
    assertTrue(dpr.isDefaultPartitioning());

    String[] sarr = dpr.getColumnNames();
    assertEquals(0, sarr.length);
    assertEquals(0, dpr.getPartitioningColumnsCount());
    Integer robj = (Integer)dpr.getRoutingObject(new EntryOperationImpl(null,
        Operation.CREATE, Long.valueOf(5), null, null));
    assertEquals(Long.valueOf(5).hashCode(), robj.intValue());
  }

  // /////////////////////
  // END DEFAULT TEST
  // /////////////////////

  // /////////////////////
  // START EXPRESSION TEST
  // /////////////////////

  private boolean nameArrayContains(String[] arr, String candidate) {
    for (int i = 0; i < arr.length; i++) {
      if (arr[i].equalsIgnoreCase(candidate)) {
        return true;
      }
    }
    return false;
  }

  private boolean idxArrayContains(int[] arr, int candidate) {
    for (int i = 0; i < arr.length; i++) {
      if (arr[i] == candidate) {
        return true;
      }
    }
    return false;
  }

  public void testExpressionResolver_1() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid)) "
            + "Partition by (qty + cid * tid)");
    GemFireContainer c = (GemFireContainer)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getUserAttribute();
    GfxdPartitionByExpressionResolver epr = (GfxdPartitionByExpressionResolver)
        c.getRegionAttributes().getPartitionAttributes().getPartitionResolver();

    assertNotNull(epr);

    String[] sarr = epr.getColumnNames();
    assertEquals(3, sarr.length);

    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(71), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc == null) {
      EmbedConnection embedConn = GemFireXDUtils.getTSSConnection(true, false,
          false);
      embedConn.getTR().setupContextStack();
    }
    Integer robj = (Integer)epr.getRoutingObjectFromDvdArray(values);
    int expectedhashcode = 6 * 100 + 71;
    assertEquals(expectedhashcode, robj.intValue());

    String[] cnames = epr.getColumnNames();
    assertTrue(nameArrayContains(cnames, "cid"));
    assertTrue(nameArrayContains(cnames, "qty"));
    assertTrue(nameArrayContains(cnames, "tid"));
    // assertTrue(expr.equalsIgnoreCase("qty + cid * tid"));
    cnames = c.getDistributionDescriptor().getPartitionColumnNames();
    assertTrue(nameArrayContains(cnames, "cid"));
    assertTrue(nameArrayContains(cnames, "qty"));
    assertTrue(nameArrayContains(cnames, "tid"));
    assertTrue(epr.getMasterTable(false /* immediate master*/)
        .equalsIgnoreCase("/trade/portfolio"));
    assertTrue(epr.getName().equalsIgnoreCase(
        "gfxd-expression-partition-resolver"));

    int[] expectedValues = new int[] { 0, 1, 2 };
    int idx = epr.getPartitioningColumnIndex("CID");
    assertTrue(idxArrayContains(expectedValues, idx));
    idx = epr.getPartitioningColumnIndex("TID");
    assertTrue(idxArrayContains(expectedValues, idx));
    idx = epr.getPartitioningColumnIndex("QTY");
    assertTrue(idxArrayContains(expectedValues, idx));

    assertEquals(3, epr.getPartitioningColumnsCount());
    boolean gotException = false;
    try {
      epr.getRoutingKeyForColumn(new SQLInteger(1));
    } catch (AssertionError ae) {
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      epr.getRoutingObjectsForList(new DataValueDescriptor[] {
          new SQLInteger(1) });
    } catch (AssertionError ae) {
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    Integer i = (Integer)epr
        .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
            new SQLInteger(1), new SQLInteger(2), new SQLInteger(1) });
    assertEquals(3, i.intValue());
    assertNull(epr.getRoutingObjectsForRange(new SQLInteger(1), true,
        new SQLInteger(1), true));
  }

  // testing some in-built functions
  public void testExpressionResolver_2() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null, id varchar(20) not null,"
            + " availQty int not null, tid int, sid int not null,"
            + " constraint portf_pk primary key (cid)) Partition"
            + " by (substr(id, 4))");
    GfxdPartitionByExpressionResolver epr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(epr);

    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLVarchar("IBM_INDIA"), new SQLInteger(10),
        new SQLInteger(100), new SQLInteger(1) };
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc == null) {
      GemFireXDUtils.getTSSConnection(true, false, false);
    }
    Integer robj = (Integer)epr.getRoutingObjectFromDvdArray(values);
    SQLVarchar sret = new SQLVarchar("_INDIA");
    int sretHashcode = sret.hashCode();
    int expectedhashcode = sretHashcode;
    assertEquals(expectedhashcode, robj.intValue());
  }

  // User defined function

  public static int myAdd(int a) {
    return 2 * a;
  }

  public void testExpressionResolver_3() throws SQLException, StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s.execute("create function f(raw int) returns int parameter style java"
        + " no sql language java "
        + "external name 'com.pivotal.gemfirexd.jdbc.GfxdResolverAPITest.myAdd'");

    s
        .execute("create table trade.portfolio (cid int not null, id varchar(20) not null,"
            + " availQty int not null, tid int, sid int not null,"
            + " constraint portf_pk primary key (cid)) Partition"
            + " by (f(cid))");
    GfxdPartitionByExpressionResolver epr = (GfxdPartitionByExpressionResolver)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO").getAttributes()
        .getPartitionAttributes().getPartitionResolver();

    assertNotNull(epr);

    final SQLInteger sret = new SQLInteger(2 * 6);
    final int expectedHashCode = sret.hashCode();
    GemFireXDQueryObserverHolder.setInstance(new GemFireXDQueryObserverAdapter() {
      @Override
      public void afterGetRoutingObject(Object routingObject) {
        assertEquals(expectedHashCode, routingObject.hashCode());
      }
    });
    s
        .execute("insert into trade.portfolio values (6, 'IBM_INDIA', 10, 100, 1)");
    String[] sarr = epr.getColumnNames();
    assertTrue(nameArrayContains(sarr, "cid"));
  }

  // Invalid test because the two tables in question should not colocate
  // as 1st one has an expression so that the second should not colocate
  public void _testExpressionResolverDefault_1() throws SQLException,
      StandardException {
    // Create a schema
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");

    s
        .execute("create table trade.portfolio (cid int not null,"
            + "qty int not null, availQty int not null, tid int, sid int not null, "
            + "constraint portf_pk primary key (cid, sid)) "
            + "Partition by (cid * sid)");

    s
        .execute("create table trade.portfolio_FK (cidf int not null, sidf int not null, "
            + "qty int not null, "
            + "constraint cust_newt_fk foreign key (cidf, sidf) references trade.portfolio (cid, sid))");

    GemFireContainer c = (GemFireContainer)Misc
        .getGemFireCache().getRegion("/TRADE/PORTFOLIO_FK").getUserAttribute();
    GfxdPartitionByExpressionResolver epr = (GfxdPartitionByExpressionResolver)
        c.getRegionAttributes().getPartitionAttributes().getPartitionResolver();

    assertNotNull(epr);

    String[] sarr = epr.getColumnNames();
    assertEquals(2, sarr.length);
    assertTrue(nameArrayContains(sarr, "cidf"));
    assertTrue(nameArrayContains(sarr, "sidf"));
    DataValueDescriptor[] values = new DataValueDescriptor[] {
        new SQLInteger(6), new SQLInteger(70), new SQLInteger(10) };
    LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
    if (lcc == null) {
      GemFireXDUtils.getTSSConnection(true, false, false);
    }
    Integer robj = (Integer)epr.getRoutingObjectFromDvdArray(values);
    int expectedhashcode = 6 * 70;
    assertEquals(expectedhashcode, robj.intValue());

    String[] cnames = epr.getColumnNames();
    assertTrue(nameArrayContains(cnames, "cidf"));
    assertTrue(nameArrayContains(cnames, "sidf"));

    cnames = c.getDistributionDescriptor().getPartitionColumnNames();
    assertTrue(nameArrayContains(cnames, "cidf"));
    assertTrue(nameArrayContains(cnames, "sidf"));

    assertTrue(epr.getMasterTable(false /* immediate master*/)
        .equalsIgnoreCase("/trade/portfolio"));
    assertTrue(epr.getName().equalsIgnoreCase(
        "gfxd-expression-partition-resolver"));

    int[] expectedValues = new int[] { 0, 1 };
    int idx = epr.getPartitioningColumnIndex("CIDF");
    assertTrue(idxArrayContains(expectedValues, idx));
    idx = epr.getPartitioningColumnIndex("SIDF");
    assertTrue(idxArrayContains(expectedValues, idx));

    assertEquals(2, epr.getPartitioningColumnsCount());

    boolean gotException = false;
    try {
      epr.getRoutingKeyForColumn(new SQLInteger(1));
    } catch (AssertionError e) {
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    try {
      epr
          .getRoutingObjectsForList(new DataValueDescriptor[] { new SQLInteger(
              1) });
    } catch (AssertionError e) {
      gotException = true;
    }
    assertTrue(gotException);
    gotException = false;
    Integer i = (Integer)epr
        .getRoutingObjectsForPartitioningColumns(new DataValueDescriptor[] {
            new SQLInteger(1), new SQLInteger(2) });
    assertEquals(2, i.intValue());
    assertNull(epr.getRoutingObjectsForRange(new SQLInteger(1), true,
        new SQLInteger(1), true));
  }

  public void testExpression_bug41002() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();

    stmt.execute("CREATE TABLE COFFEES (COF_NAME VARCHAR(32) "
        + "CONSTRAINT COF_PK PRIMARY KEY, SUP_ID INTEGER, PRICE FLOAT, "
        + "SALES INTEGER, TOTAL INTEGER) PARTITION BY (COF_NAME)");
    // expect syntax error here without parens
    try {
      stmt.execute("CREATE TABLE SUPPLIERS (SUP_ID INTEGER CONSTRAINT "
          + "SUP_PK PRIMARY KEY, SUP_NAME VARCHAR(40), STREET VARCHAR(40), "
          + "CITY VARCHAR(20), STATE VARCHAR(2), ZIP CHAR(5)) PARTITION BY "
          + "SUP_ID COLOCATE WITH (COFFEES)");
    } catch (SQLException ex) {
      if (!"42X01".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    stmt.execute("CREATE TABLE SUPPLIERS (SUP_ID INTEGER CONSTRAINT "
        + "SUP_PK PRIMARY KEY, SUP_NAME VARCHAR(40), STREET VARCHAR(40), "
        + "CITY VARCHAR(20), STATE VARCHAR(2), ZIP CHAR(5)) PARTITION BY "
        + "(SUP_ID) COLOCATE WITH (COFFEES)");

    // few inserts but no real colocation guarantee since the two tables are
    // partitioned by different columns
    stmt.execute("INSERT INTO COFFEES VALUES('French Roast', "
        + "49, 8.99, 0, 0)");
    stmt.execute("INSERT INTO SUPPLIERS VALUES(49, 'Nestle', "
        + "'Street1', 'City1', 'OR', '40000')");

    // queries
    sqlExecuteVerifyText("SELECT * FROM COFFEES", getResourcesDir()
        + "/lib/checkQuery.xml", "bug41002_coffees", true, false);
    sqlExecuteVerifyText("SELECT * FROM SUPPLIERS", getResourcesDir()
        + "/lib/checkQuery.xml", "bug41002_suppliers", false, false);

    // drop the tables and close the connection

    // exepect exception when dropping parent before child
    addExpectedException(UnsupportedOperationException.class);
    try {
      stmt.execute("drop table COFFEES");
    } catch (SQLException ex) {
      if (!"X0Y98".equals(ex.getSQLState())) {
        throw ex;
      }
    }
    removeExpectedException(UnsupportedOperationException.class);

    stmt.execute("drop table SUPPLIERS");
    stmt.execute("drop table COFFEES");
    conn.close();
  }

  private void createHashFunction(Connection conn) throws SQLException {
    String stmt = "CREATE FUNCTION myhash(value INTEGER) " + "RETURNS INTEGER "
        + "PARAMETER STYLE java " + "LANGUAGE java " + "NO SQL "
        + "EXTERNAL NAME "
        + "'com.pivotal.gemfirexd.jdbc.GfxdResolverAPITest.myhash'";
    Statement s = conn.createStatement();
    s.execute(stmt);
    s.close();
  }

  public static int myhash(int value) throws SQLException {
    int i = Integer.valueOf(value).hashCode();

    // Log.getLogWriter().info("Invoked myhash(" + value + ")=" + i);

    return i;
  }

  public void testExprResolver_2() throws Exception {

    String parentsql = "create table t.customer (    c_w_id         integer        not null,    "
        + "c_d_id         integer        not null,    c_id           integer        not null,    "
        + "c_discount     decimal(4,4),    c_credit       char(2),    c_last         varchar(16),    "
        + "c_first        varchar(16),    c_credit_lim   decimal(12,2),    c_balance      decimal(12,2),    "
        + "c_ytd_payment  float,    c_payment_cnt  integer,    c_delivery_cnt integer,    c_street_1     varchar(20),    "
        + "c_street_2     varchar(20),    c_city         varchar(20),    c_state        char(2),    "
        + "c_zip          char(9),    c_phone        char(16),    c_since        timestamp,    c_middle       char(2),    "
        + "c_data         varchar(500)  ) partition by (c_w_id)";

    String childsql = "create table t.stock (s_w_id integer not null, s_i_id   integer       not null,    "
        + "s_quantity   decimal(4,0),    s_ytd        decimal(8,2),    s_order_cnt  integer,    "
        + "s_remote_cnt integer,    s_data       varchar(50),    s_dist_01    char(24),    "
        + "s_dist_02    char(24),    s_dist_03    char(24),    s_dist_04    char(24),    "
        + "s_dist_05    char(24),    s_dist_06    char(24),    s_dist_07    char(24),    "
        + "s_dist_08    char(24),    s_dist_09    char(24),    s_dist_10    char(24)  ) "
        + "partition by (myhash(s_w_id)) colocate with (t.customer)";

    Connection conn = TestUtil.getConnection();
    createHashFunction(conn);
    Statement stmt = conn.createStatement();
    stmt.execute("create schema t");
    stmt.execute(parentsql);
    stmt
        .execute("alter table t.customer add constraint pk_customer primary key (c_w_id, c_d_id, c_id)");
    try {
      stmt.execute(childsql);
      fail("child create table should not succeed");
    } catch (SQLException sqle) {
      assertTrue("X0Y95".equals(sqle.getSQLState()));
    }
  }

  public void testExprResolver() throws Exception {

    String parentsql = "create table t.customer (    c_w_id         integer        not null,    "
        + "c_d_id         integer        not null,    c_id           integer        not null,    "
        + "c_discount     decimal(4,4),    c_credit       char(2),    c_last         varchar(16),    "
        + "c_first        varchar(16),    c_credit_lim   decimal(12,2),    c_balance      decimal(12,2),    "
        + "c_ytd_payment  float,    c_payment_cnt  integer,    c_delivery_cnt integer,    c_street_1     varchar(20),    "
        + "c_street_2     varchar(20),    c_city         varchar(20),    c_state        char(2),    "
        + "c_zip          char(9),    c_phone        char(16),    c_since        timestamp,    c_middle       char(2),    "
        + "c_data         varchar(500)  ) partition by (myhash(c_w_id))";

    Connection conn = TestUtil.getConnection();
    createHashFunction(conn);
    Statement stmt = conn.createStatement();
    stmt.execute("create schema t");
    stmt.execute(parentsql);
    stmt
        .execute("alter table t.customer add constraint pk_customer primary key (c_w_id, c_d_id, c_id)");

    stmt
        .execute("insert into t.customer values(1, 1, 1, 0.0, 'hi', 'man', 'kumar', "
            + "0.0, 0.0, 1.0, 1, 1, 'st1', 'st2', 'pune', 'ma', '909090909', '1234567891011111', null, 'hi', null)");
  }

  public void testBug43150() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table customers (cid int not null, cust_name varchar(100), "
            + " tid int, primary key (cid))  partition by list (tid) (VALUES (14, 1, 2, 10, 4, 5), "
            + "VALUES (6, 7, 16, 9, 3, 11), VALUES (12, 13, 0, 15, 8, 17))  ");

    s
        .execute("create table securities (sec_id int not null, symbol varchar(10) not null,"
            + "exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id))");

    s
        .execute("create table portfolio (cid int not null, sid int not null,"
            + " qty int not null,tid int, constraint portf_pk primary key (cid, sid),"
            + " constraint cust_fk foreign key (cid) references customers (cid), "
            + "constraint sec_fk foreign key (sid) references securities (sec_id))");

    PartitionedRegion pr1 = (PartitionedRegion)Misc.getRegion("/APP/PORTFOLIO",
        true, false);
    GfxdPartitionResolver rslvr1 = (GfxdPartitionResolver)pr1
        .getPartitionResolver();
    PartitionedRegion pr2 = (PartitionedRegion)Misc
        .getRegion("/APP/SECURITIES", true, false);
    pr2
        .getPartitionResolver();
    PartitionedRegion pr3 = (PartitionedRegion)Misc.getRegion("/APP/CUSTOMERS",
        true, false);
    pr3
        .getPartitionResolver();

    s.executeUpdate("insert into securities values(163,'1','1',1)");
    s.executeUpdate("insert into customers values(2,'2',1)");
    s.executeUpdate("insert into portfolio values(2,163,1,1)");

    assertEquals(1, rslvr1.getPartitioningColumnsCount());
    Iterator itr = pr1.keys().iterator();
    Object key = itr.next();
    Object val = pr1.get(key);
    Integer rtObj = (Integer)rslvr1.getRoutingObject(key, val, pr1);
    int maxWidth = DataTypeDescriptor.getBuiltInDataTypeDescriptor(
        Types.INTEGER).getMaximumWidth();
    assertEquals(new SQLInteger(163).computeHashCode(maxWidth, 0),
        rtObj.intValue());
  }

  public void testBug43688_1() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table customers (cid int not null, cust_name varchar(100), "
            + " tid int, primary key (cid))  partition by list (tid) (VALUES (14, 1, 2, 10, 4, 5), "
            + "VALUES (6, 7, 16, 9, 3, 11), VALUES (12, 13, 0, 15, 8, 17))  ");

    s
        .execute("create table securities (sec_id int not null, symbol varchar(10) not null,"
            + "exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id) )"
            + " partition by list (sec_id) ( VALUES (0, 5) , VALUES (10, 15), "
            + "VALUES (20, 23), VALUES(25, 30), VALUES(31, 163))");

    s
        .execute("create table portfolio (cid int not null, sid int not null,"
            + " qty int not null,tid int, constraint portf_pk primary key (cid, sid),"
            + " constraint cust_fk foreign key (cid) references customers (cid), "
            + "constraint sec_fk foreign key (sid) references securities (sec_id))");

    PartitionedRegion pr1 = (PartitionedRegion)Misc.getRegion("/APP/PORTFOLIO",
        true, false);
    GfxdPartitionResolver rslvr1 = (GfxdPartitionResolver)pr1
        .getPartitionResolver();
    PartitionedRegion pr2 = (PartitionedRegion)Misc
        .getRegion("/APP/SECURITIES", true, false);
    pr2
        .getPartitionResolver();
    PartitionedRegion pr3 = (PartitionedRegion)Misc.getRegion("/APP/CUSTOMERS",
        true, false);
    pr3
        .getPartitionResolver();

    s.executeUpdate("insert into securities values(163,'1','1',1)");
    s.executeUpdate("insert into customers values(2,'2',1)");
    s.executeUpdate("insert into portfolio values(2,163,1,1)");

    assertEquals(1, rslvr1.getPartitioningColumnsCount());
    Iterator itr = pr1.keys().iterator();
    Object key = itr.next();
    Object val = pr1.get(key);
    Integer rtObj = (Integer)rslvr1.getRoutingObject(key, val, pr1);
    assertEquals(4, rtObj.intValue());
  }

  public void testBug43688_2() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table customers (cid int not null, cust_name varchar(100), "
            + " tid int, primary key (cid))  partition by list (tid) (VALUES (14, 1, 2, 10, 4, 5), "
            + "VALUES (6, 7, 16, 9, 3, 11), VALUES (12, 13, 0, 15, 8, 17))  ");

    s
        .execute("create table securities (sec_id int not null, symbol varchar(10) not null,"
            + "exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id) )"
            + " partition by list (sec_id) ( VALUES (0, 5) , VALUES (10, 15), "
            + "VALUES (20, 23), VALUES(25, 30), VALUES(31))");

    s
        .execute("create table portfolio (cid int not null, sid int not null,"
            + " qty int not null,tid int, constraint portf_pk primary key (cid, sid),"
            + " constraint cust_fk foreign key (cid) references customers (cid), "
            + "constraint sec_fk foreign key (sid) references securities (sec_id))");

    PartitionedRegion pr1 = (PartitionedRegion)Misc.getRegion("/APP/PORTFOLIO",
        true, false);
    GfxdPartitionResolver rslvr1 = (GfxdPartitionResolver)pr1
        .getPartitionResolver();
    PartitionedRegion pr2 = (PartitionedRegion)Misc
        .getRegion("/APP/SECURITIES", true, false);
    GfxdPartitionResolver rslvr2 = (GfxdPartitionResolver)pr2
        .getPartitionResolver();
    PartitionedRegion pr3 = (PartitionedRegion)Misc.getRegion("/APP/CUSTOMERS",
        true, false);
    pr3
        .getPartitionResolver();

    s.executeUpdate("insert into securities values(163,'1','1',1)");
    s.executeUpdate("insert into customers values(2,'2',1)");
    s.executeUpdate("insert into portfolio values(2,163,1,1)");

    assertEquals(1, rslvr1.getPartitioningColumnsCount());
    Iterator itr1 = pr1.keys().iterator();
    Object key1 = itr1.next();
    Object val1 = pr1.get(key1);
    Integer rtObj1 = (Integer)rslvr1.getRoutingObject(key1, val1, pr1);
    
    assertEquals(1, rslvr2.getPartitioningColumnsCount());
    Iterator itr2 = pr2.keys().iterator();
    Object key2 = itr2.next();
    Object val2 = pr2.get(key2);
    Integer rtObj2 = (Integer)rslvr2.getRoutingObject(key2, val2, pr2);
    assertEquals(rtObj2.intValue(), rtObj1.intValue());
  }
  
  public void testBug43688_3() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table customers (cid int not null, cust_name varchar(100), "
            + " tid int, primary key (cid))  partition by list (tid) (VALUES (14, 1, 2, 10, 4, 5), "
            + "VALUES (6, 7, 16, 9, 3, 11), VALUES (12, 13, 0, 15, 8, 17))  ");

    s
        .execute("create table securities (sec_id int not null, symbol varchar(10) not null,"
            + "exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id) )"
            +"partition by range (sec_id) ( VALUES BETWEEN 0 AND 5, VALUES BETWEEN 10 AND 15, "
           + "VALUES BETWEEN 20 AND 23, VALUES BETWEEN 25 AND 30, VALUES BETWEEN 31 AND 200)");

    s
        .execute("create table portfolio (cid int not null, sid int not null,"
            + " qty int not null,tid int, constraint portf_pk primary key (cid, sid),"
            + " constraint cust_fk foreign key (cid) references customers (cid), "
            + "constraint sec_fk foreign key (sid) references securities (sec_id))");

    PartitionedRegion pr1 = (PartitionedRegion)Misc.getRegion("/APP/PORTFOLIO",
        true, false);
    GfxdPartitionResolver rslvr1 = (GfxdPartitionResolver)pr1
        .getPartitionResolver();
    PartitionedRegion pr2 = (PartitionedRegion)Misc
        .getRegion("/APP/SECURITIES", true, false);
    pr2
        .getPartitionResolver();
    PartitionedRegion pr3 = (PartitionedRegion)Misc.getRegion("/APP/CUSTOMERS",
        true, false);
    pr3
        .getPartitionResolver();

    s.executeUpdate("insert into securities values(163,'1','1',1)");
    s.executeUpdate("insert into customers values(2,'2',1)");
    s.executeUpdate("insert into portfolio values(2,163,1,1)");

    assertEquals(1, rslvr1.getPartitioningColumnsCount());
    Iterator itr = pr1.keys().iterator();
    Object key = itr.next();
    Object val = pr1.get(key);
    Integer rtObj = (Integer)rslvr1.getRoutingObject(key, val, pr1);
    assertEquals(5, rtObj.intValue());
  }

  public void testBug43688_4() throws Exception {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s
        .execute("create table customers (cid int not null, cust_name varchar(100), "
            + " tid int, primary key (cid))  partition by list (tid) (VALUES (14, 1, 2, 10, 4, 5), "
            + "VALUES (6, 7, 16, 9, 3, 11), VALUES (12, 13, 0, 15, 8, 17))  ");

    s
        .execute("create table securities (sec_id int not null, symbol varchar(10) not null,"
            + "exchange varchar(10) not null, tid int, constraint sec_pk primary key (sec_id) )"
            + " partition by range (sec_id) ( VALUES BETWEEN 0 AND 5, VALUES BETWEEN 10 AND 15, "
            + "VALUES BETWEEN 20 AND 23, VALUES BETWEEN 25 AND 30, VALUES BETWEEN 31 AND 160)");

    s
        .execute("create table portfolio (cid int not null, sid int not null,"
            + " qty int not null,tid int, constraint portf_pk primary key (cid, sid),"
            + " constraint cust_fk foreign key (cid) references customers (cid), "
            + "constraint sec_fk foreign key (sid) references securities (sec_id))");

    PartitionedRegion pr1 = (PartitionedRegion)Misc.getRegion("/APP/PORTFOLIO",
        true, false);
    GfxdPartitionResolver rslvr1 = (GfxdPartitionResolver)pr1
        .getPartitionResolver();
    PartitionedRegion pr2 = (PartitionedRegion)Misc
        .getRegion("/APP/SECURITIES", true, false);
    GfxdPartitionResolver rslvr2 = (GfxdPartitionResolver)pr2
        .getPartitionResolver();
    PartitionedRegion pr3 = (PartitionedRegion)Misc.getRegion("/APP/CUSTOMERS",
        true, false);
    pr3
        .getPartitionResolver();

    s.executeUpdate("insert into securities values(163,'1','1',1)");
    s.executeUpdate("insert into customers values(2,'2',1)");
    s.executeUpdate("insert into portfolio values(2,163,1,1)");

    assertEquals(1, rslvr1.getPartitioningColumnsCount());
    Iterator itr1 = pr1.keys().iterator();
    Object key1 = itr1.next();
    Object val1 = pr1.get(key1);
    Integer rtObj1 = (Integer)rslvr1.getRoutingObject(key1, val1, pr1);
    
    Iterator itr2 = pr2.keys().iterator();
    Object key2 = itr2.next();
    Object val2 = pr2.get(key2);
    Integer rtObj2 = (Integer)rslvr2.getRoutingObject(key2, val2, pr2);
    assertEquals(rtObj2.intValue(), rtObj1.intValue());
  }
  // /////////////////////
  // END EXPRESSION TEST
  // /////////////////////
}
