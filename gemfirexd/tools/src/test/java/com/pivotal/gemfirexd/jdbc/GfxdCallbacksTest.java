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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class GfxdCallbacksTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(GfxdCallbacksTest.class));
  }

  public GfxdCallbacksTest(String name) {
    super(name);
  }

  public static void addListener(String listenerID, String schemaName,
      String tableName, String functionStr, String initInfoStr, String serverGroups) throws SQLException {
    Connection conn = getConnection();
    CallableStatement cs = conn
        .prepareCall("CALL SYS.ADD_LISTENER(?,?,?,?,?,?)");
    cs.setString(1, listenerID);
    cs.setString(2, schemaName);
    cs.setString(3, tableName);
    cs.setString(4, functionStr);
    cs.setString(5, initInfoStr);
    cs.setString(6, serverGroups);
    cs.execute();
  }

  public static void addWriter(String schemaName, String tableName,
      String functionStr, String initInfoStr, String serverGroups) throws SQLException {
    Connection conn = getConnection();
    CallableStatement cs = conn
        .prepareCall("CALL SYS.ATTACH_WRITER(?,?,?,?,?)");
    cs.setString(1, schemaName);
    cs.setString(2, tableName);
    cs.setString(3, functionStr);
    cs.setString(4, initInfoStr);
    cs.setString(5, serverGroups);
    cs.execute();
  }
  
  public static void addLoader(String schemaName, String tableName,
      String functionStr, String initInfoStr) throws SQLException {
    Connection conn = getConnection();
    CallableStatement cs = conn
        .prepareCall("CALL SYS.ATTACH_LOADER(?,?,?,?)");
    cs.setString(1, schemaName);
    cs.setString(2, tableName);
    cs.setString(3, functionStr);
    cs.setString(4, initInfoStr);
    cs.execute();
  }
  
  public static void removeListener(String listenerID, String schemaName,
      String tableName) throws SQLException {
    Connection conn = getConnection();
    CallableStatement cs = conn
        .prepareCall("CALL SYS.REMOVE_LISTENER(?,?,?)");
    cs.setString(1, listenerID);
    cs.setString(2, schemaName);
    cs.setString(3, tableName);
    cs.execute();
  }
  
  public static void removeWriter(String schemaName,
      String tableName) throws SQLException {
    Connection conn = getConnection();
    CallableStatement cs = conn
        .prepareCall("CALL SYS.REMOVE_WRITER(?,?)");
    cs.setString(1, schemaName);
    cs.setString(2, tableName);
    cs.execute();
  }
  
  public static void removeLoader(String schemaName,
      String tableName) throws SQLException {
    Connection conn = getConnection();
    CallableStatement cs = conn
        .prepareCall("CALL SYS.REMOVE_LOADER(?,?)");
    cs.setString(1, schemaName);
    cs.setString(2, tableName);
    cs.execute();
  }
  
  // TODO check case sensitivity. Need to convert every thing to uppercase
  // that should suffice.
  public void testListenerAddition() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table EMP.TESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");
    addListener("ID1", "EMP", "TESTTABLE",
        "com.pivotal.gemfirexd.jdbc.GfxdTestCacheListener", "initInfoStrForListener",null);
    s.execute("insert into EMP.TESTTABLE values (1, 2, 3)");
    s.execute("drop table emp.testtable");
    s.execute("drop schema emp restrict");
  }

  public void testMultipleListenerAdditionAndRemoval() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table EMP.TESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");
    addListener("ID1", "EMP", "TESTTABLE",
        "com.pivotal.gemfirexd.jdbc.GfxdTestCacheListener", "initInfoStrForListener", "");
    addListener("ID2", "EMP", "TESTTABLE",
        "com.pivotal.gemfirexd.jdbc.GfxdTestCacheListener", "initInfoStrForListener_second", "");
    s.execute("insert into EMP.TESTTABLE values (1, 2, 3)");
    removeListener("ID1", "EMP", "TESTTABLE");
    s.execute("insert into EMP.TESTTABLE values (5, 6, 7)");
    s.execute("update EMP.TESTTABLE set thirdid=10 where id=1");
    s.execute("drop table emp.testtable");
    s.execute("drop schema emp restrict");
  }

  public void testWriterAddition() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table EMP.TESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");
    addWriter("EMP", "TESTTABLE",
        "com.pivotal.gemfirexd.jdbc.GfxdTestCacheWriter", "initInfoStrForWriter", "");
    s.execute("insert into EMP.TESTTABLE values (1, 2, 3)");
    s.execute("drop table emp.testtable");
    s.execute("drop schema emp restrict");
  }
  
  public void testMultipleWriterAddition() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table EMP.TESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");
    addWriter("EMP", "TESTTABLE",
        "com.pivotal.gemfirexd.jdbc.GfxdTestCacheWriter", "initInfoStrForWriter", "");
    addWriter("EMP", "TESTTABLE",
        "com.pivotal.gemfirexd.jdbc.GfxdTestCacheWriter", "initInfoStrForWriter", null);
    s.execute("insert into EMP.TESTTABLE values (1, 2, 3)");
    s.execute("drop table emp.testtable");
    s.execute("drop schema emp restrict");
  }

  public void testWriterRemoval() throws SQLException {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema emp");
    s.execute("create table EMP.TESTTABLE (ID int not null,"
        + " SECONDID int not null, THIRDID int not null,"
        + " primary key (ID))");
    addWriter("EMP", "TESTTABLE",
        "com.pivotal.gemfirexd.jdbc.GfxdTestCacheWriter", "initInfoStrForWriter", "");
    s.execute("insert into EMP.TESTTABLE values (1, 2, 3)");
    s.execute("update EMP.TESTTABLE set secondid=1 where id=1");
    removeWriter("EMP", "TESTTABLE");
    s.execute("insert into EMP.TESTTABLE values (5, 6, 7)");
    s.execute("drop table emp.testtable");
    s.execute("drop schema emp restrict");
  }
}
