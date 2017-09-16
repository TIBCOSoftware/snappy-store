/*

   Derby - Class org.apache.derby.jdbc.EmbeddedDriver

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata.jdbc;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import com.pivotal.gemfirexd.internal.iapi.jdbc.JDBCBoot;

/**
 * The embedded JDBC driver (Type 4) for SnappyData.
 * <p>
 * The driver automatically supports the correct JDBC specification version
 * for the Java Virtual Machine's environment.
 * <UL>
 * <LI> JDBC 4.0 - Java SE 6
 * <LI> JDBC 3.0 - Java 2 - JDK 1.4, J2SE 5.0
 * </UL>
 * <p>
 * <p>
 * Loading this JDBC driver boots the database engine
 * within the same Java virtual machine.
 * <p>
 * The correct code to load the SnappyData engine using this driver is
 * (with approriate try/catch blocks):
 * <PRE>
 * Class.forName("io.snappydata.jdbc.EmbeddedDriver").newInstance();
 * <p>
 * // or
 * <p>
 * new io.snappydata.jdbc.EmbeddedDriver();
 * <p>
 * <p>
 * </PRE>
 * When loaded in this way, the class boots the actual JDBC driver indirectly.
 * The JDBC specification recommends the Class.ForName method without the .newInstance()
 * method call, but adding the newInstance() guarantees
 * that SnappyData will be booted on any Java Virtual Machine.
 * <p>
 * <p>
 * Note that you do not need to manually load the driver this way if you are
 * running on Jave SE 6 or later. In that environment, the driver will be
 * automatically loaded for you when your application requests a connection to
 * a SnappyData database.
 * <p>
 * Any initial error messages are placed in the PrintStream
 * supplied by the DriverManager. If the PrintStream is null error messages are
 * sent to System.err. Once the SnappyData engine has set up an error
 * logging facility (by default to snappydata.log) all subsequent messages are sent to it.
 * <p>
 * By convention, the class used in the Class.forName() method to
 * boot a JDBC driver implements java.sql.Driver.
 * <p>
 * This class is not the actual JDBC driver that gets registered with
 * the Driver Manager. It proxies requests to the registered SnappyData JDBC driver.
 *
 * @see java.sql.DriverManager
 * @see java.sql.DriverManager#getLogStream
 * @see java.sql.Driver
 * @see java.sql.SQLException
 */
public class EmbeddedDriver implements Driver {

  static {
    EmbeddedDriver.boot();
  }

  private AutoloadedDriver _autoloadedDriver;

  // Boot from the constructor as well to ensure that
  // Class.forName(...).newInstance() reboots SnappyData
  // after a shutdown inside the same JVM.
  public EmbeddedDriver() {
    EmbeddedDriver.boot();
  }

	/*
  ** Methods from java.sql.Driver.
	*/

  /**
   * Accept anything that starts with <CODE>jdbc:snappydata:</CODE>.
   *
   * @throws SQLException if a database-access error occurs.
   * @see java.sql.Driver
   */
  public boolean acceptsURL(String url) throws SQLException {
    return getDriverModule().acceptsURL(url);
  }

  /**
   * Connect to the URL if possible
   *
   * @throws SQLException illegal url or problem with connectiong
   * @see java.sql.Driver
   */
  public Connection connect(String url, Properties info)
      throws SQLException {
    return getDriverModule().connect(url, info);
  }

  /**
   * Returns an array of DriverPropertyInfo objects describing possible properties.
   *
   * @throws SQLException if a database-access error occurs.
   * @see java.sql.Driver
   */
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
      throws SQLException {
    return getDriverModule().getPropertyInfo(url, info);
  }

  /**
   * Returns the driver's major version number.
   *
   * @see java.sql.Driver
   */
  public int getMajorVersion() {
    try {
      return (getDriverModule().getMajorVersion());
    } catch (SQLException se) {
      return 0;
    }
  }

  /**
   * Returns the driver's minor version number.
   *
   * @see java.sql.Driver
   */
  public int getMinorVersion() {
    try {
      return (getDriverModule().getMinorVersion());
    } catch (SQLException se) {
      return 0;
    }
  }

  /**
   * Report whether the Driver is a genuine JDBC COMPLIANT (tm) driver.
   *
   * @see java.sql.Driver
   */
  public boolean jdbcCompliant() {
    try {
      return (getDriverModule().jdbcCompliant());
    } catch (SQLException se) {
      return false;
    }
  }

  /**
   * Lookup the booted driver module appropriate to our JDBC level.
   */
  private Driver getDriverModule()
      throws SQLException {
    return AutoloadedDriver.getDriverModule();
  }

  /*
 ** Find the appropriate driver for our JDBC level and boot it.
 *  This is package protected so that AutoloadedDriver can call it.
 */
  // GemStone changes BEGIN
  // increasing visibility
  public static void boot() {
    // GemStone changes END
    PrintStream ps = DriverManager.getLogStream();

    if (ps == null)
      ps = System.err;

    new JDBCBoot().boot(com.pivotal.gemfirexd.Attribute.PROTOCOL, ps);
  }

  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }
}
