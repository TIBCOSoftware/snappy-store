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
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.client.am.SqlException;
import com.pivotal.gemfirexd.internal.client.am.Utils;
import com.pivotal.gemfirexd.internal.shared.common.error.ClientExceptionUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.MessageId;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.internal.ClientConfiguration;

/**
 * Client Driver with the capability to maintain connection
 * pool inside it and return connections from the pool.
 */
public class ClientPoolDriver implements Driver {

  private static String SNAPPY_PROTOCOL = "jdbc:snappydata:";

  private final static String SUBPROTOCOL = "(pool:){1}";

  protected final static String URL_PREFIX_REGEX = "(" + SNAPPY_PROTOCOL + ")";
  protected final static String URL_SUFFIX_REGEX =
      "//(([^:]+:[0-9]+)|([^\\[]+\\[[0-9]+\\]))(/(snappydata;)?;?(.*)?)?";

  private final static Pattern PROTOCOL_PATTERN = Pattern.compile(URL_PREFIX_REGEX +
      SUBPROTOCOL + "//.*", Pattern.CASE_INSENSITIVE);

  private final static Pattern URL_PATTERN = Pattern.compile(URL_PREFIX_REGEX +
      SUBPROTOCOL + URL_SUFFIX_REGEX, Pattern.CASE_INSENSITIVE);

  private static SQLException exceptionsOnLoadDriver__ = null;

  static {
    try {
      ClientPoolDriver driver = new ClientPoolDriver();
      java.sql.DriverManager.registerDriver(driver);

    } catch (SQLException e) {
      // A null log writer is passed, because jdbc 1 sql exceptions are
      // automatically traced
      exceptionsOnLoadDriver__ = ClientExceptionUtil.newSQLException(
          SQLState.JDBC_DRIVER_REGISTER, e);
    }
    // This may possibly hit the race-condition bug of java 1.1.
    // The Configuration static clause should execute before the following line
    // does.
    if (ClientConfiguration.exceptionsOnLoadResources != null) {
      exceptionsOnLoadDriver__ = Utils.accumulateSQLException(
          ClientConfiguration.exceptionsOnLoadResources,
          exceptionsOnLoadDriver__);
    }
  }

  /**
   * {@inheritDoc}
   */
  public boolean acceptsURL(String url) throws SQLException {
    return (url != null && URL_PATTERN.matcher(url).matches());
  }

  @Override
  public Connection connect(String url, Properties properties) throws SQLException {

    if (!acceptsURL(url)) {
      return null;
    }

    properties = (properties == null) ? new Properties() : properties;
    String clientDriverURL = url.replaceFirst(PROTOCOL_PATTERN.matcher(url)
        .replaceFirst(SUBPROTOCOL), "");
    properties.setProperty(TomcatConnectionPool.PoolProps.URL.key,
        clientDriverURL);
    properties.setProperty(TomcatConnectionPool.PoolProps.DRIVER_NAME.key,
        ClientDriver.class.getName());

    //Read connection from the pool and return. 
    return TomcatConnectionPool.getConnection(properties);
  }

  @Override
  public int getMajorVersion() {
    return ClientConfiguration.getProductVersionHolder().getMajorVersion();
  }

  @Override
  public int getMinorVersion() {
    return ClientConfiguration.getProductVersionHolder().getMinorVersion();
  }

  @Override
  public boolean jdbcCompliant() {
    return ClientConfiguration.jdbcCompliant;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new AssertionError("should be overridden in JDBC 4.1");
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties)
      throws SQLException {

    java.sql.DriverPropertyInfo driverPropertyInfo[] = new java.sql.DriverPropertyInfo[2];

    // If there are no properties set already,
    // then create a dummy properties just to make the calls go thru.
    if (properties == null) {
      properties = new java.util.Properties();
    }

    boolean isUserNameAttribute = false;
    String userName = properties.getProperty(Attribute.USERNAME_ATTR);
    if (userName == null) {
      userName = properties.getProperty(Attribute.USERNAME_ALT_ATTR);
      if (userName != null) {
        isUserNameAttribute = true;
      }
    }

    driverPropertyInfo[0] = new java.sql.DriverPropertyInfo(
        isUserNameAttribute ? Attribute.USERNAME_ALT_ATTR
            : Attribute.USERNAME_ATTR, userName);

    driverPropertyInfo[1] =
        new java.sql.DriverPropertyInfo(Attribute.PASSWORD_ATTR,
            properties.getProperty(Attribute.PASSWORD_ATTR));

    driverPropertyInfo[0].description =
        SqlException.getMessageUtil().getTextMessage(
            MessageId.CONN_USERNAME_DESCRIPTION);
    driverPropertyInfo[1].description =
        SqlException.getMessageUtil().getTextMessage(
            MessageId.CONN_PASSWORD_DESCRIPTION);

    driverPropertyInfo[0].required = true;
    driverPropertyInfo[1].required = false; // depending on the security mechanism

    return driverPropertyInfo;
  }
}