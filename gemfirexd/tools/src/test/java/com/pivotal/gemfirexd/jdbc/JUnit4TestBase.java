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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

/**
 * Base class for JUnit 4 tests that want to do common setup for boot properties
 * like logs, stats etc while continuing to use TestUtil.
 */
public class JUnit4TestBase {

  @Rule
  public TestName name = new TestName();

  protected final Logger logger = LogManager.getLogger(getClass());

  @AfterClass
  public static void classTearDown() throws Exception {
    TestUtil.setCurrentTestClass(null);
    TestUtil.currentTest = null;
    // cleanup all tables
    if (GemFireStore.getBootedInstance() != null) {
      Properties props = new Properties();
      if (TestUtil.bootUserName != null) {
        props.setProperty("user", TestUtil.bootUserName);
        props.setProperty("password", TestUtil.bootUserPassword);
      }
      try {
        Connection conn = DriverManager.getConnection(
            TestUtil.getProtocol(), props);
        CleanDatabaseTestSetup.cleanDatabase(conn, false);
      } catch (SQLException ignored) {
      }
    }
    try {
      TestUtil.shutDown();
      // delete persistent DataDictionary files
      TestUtil.deleteDir(new File("datadictionary"));
      TestUtil.deleteDir(new File("globalIndex"));
    } catch (SQLException ignored) {
    }
  }

  @Before
  public void setUp() {
    TestUtil.setCurrentTestClass(getClass());
    TestUtil.currentTest = name.getMethodName();
  }

  @After
  public void tearDown() {
    TestUtil.setCurrentTestClass(null);
    TestUtil.currentTest = null;
  }

  protected void setLogLevelForTest(String logLevel) {
    TestUtil.reduceLogLevel(logLevel);
  }
}
