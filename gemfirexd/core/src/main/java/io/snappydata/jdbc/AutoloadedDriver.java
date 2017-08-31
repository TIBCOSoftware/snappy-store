/*

   Derby - Class org.apache.derby.jdbc.AutoloadedDriver

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

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;

/**
 * This is the dummy driver which is registered with the DriverManager and
 * which is autoloaded by JDBC4. Loading this class will NOT automatically
 * boot the SnappyData engine, but it will register this class as a valid
 * Driver with the DriverManager.
 * Instead, the engine boots lazily when you ask for a
 * Connection. Alternatively, you can force the engine to boot as follows:
 * <p>
 * <PRE>
 * Class.forName("io.snappydata.jdbc.EmbeddedDriver").newInstance();
 * <p>
 * // or
 * <p>
 * new io.snappydata.jdbc.EmbeddedDriver();
 * <p>
 * </PRE>
 */
public class AutoloadedDriver implements Driver {
  // This flag is set if the engine is forcibly brought down.
  private static boolean _engineForcedDown = false;

  //
  // This is the driver that's specific to the JDBC level we're running at.
  // It's the module which boots the whole SnappyData engine.
  //
  private static Driver _driverModule;

  static {
    try {
      DriverManager.registerDriver(new AutoloadedDriver());
    } catch (SQLException se) {
      String message = MessageService.getTextMessage
          (MessageId.JDBC_DRIVER_REGISTER_ERROR, se.getMessage());

      throw new IllegalStateException(message);
    }
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

    //
    // We don't want to accidentally boot the engine just because
    // the application is looking for a connection from some other
    // driver.
    //
    return !_engineForcedDown && InternalDriver.embeddedDriverAcceptsURL(url);
  }


  /**
   * Connect to the URL if possible
   *
   * @throws SQLException illegal url or problem with connectiong
   * @see java.sql.Driver
   */
  public Connection connect(String url, Properties info)
      throws SQLException {
    //
    // This pretty piece of logic compensates for the following behavior
    // of the DriverManager: When asked to get a Connection, the
    // DriverManager cycles through all of its autoloaded drivers, looking
    // for one which will return a Connection. Without this pretty logic,
    // the embedded driver module will be booted by any request for
    // a connection which cannot be satisfied by drivers ahead of us
    // in the list.
    if (!InternalDriver.embeddedDriverAcceptsURL(url)) {
      return null;
    }

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

  ///////////////////////////////////////////////////////////////////////
  //
  // Support for booting and shutting down the engine.
  //
  ///////////////////////////////////////////////////////////////////////

  /*
  ** Retrieve the driver which is specific to our JDBC level.
  ** We defer real work to this specific driver.
  */
  // GemStone changes BEGIN
  // increasing visibility
  public static Driver getDriverModule() throws SQLException {
    // GemStone changes END

    if (!isBooted()) {
      EmbeddedDriver.boot();
    }
    if (_engineForcedDown) {
      // Driver not registered
      throw new SQLException
          (MessageService.getTextMessage(MessageId.CORE_JDBC_DRIVER_UNREGISTERED));
    }

    //if ( !isBooted() ) { EmbeddedDriver.boot(); }

    return _driverModule;
  }

  /*
  ** Record which driver module actually booted.
  */
  public static void registerDriverModule(Driver driver) {
    _driverModule = driver;
    _engineForcedDown = false;
  }

  /*
  ** Unregister the driver. This happens when the engine is
  ** forcibly shut down.
  */
  public static void unregisterDriverModule() {
    _driverModule = null;
    _engineForcedDown = true;
  }

  /*
  ** Return true if the engine has been booted.
  */
  private static boolean isBooted() {
    return (_driverModule != null);
  }

  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }
}
