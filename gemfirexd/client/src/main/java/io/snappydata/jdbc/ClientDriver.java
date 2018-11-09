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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.client.am.Utils;
import com.pivotal.gemfirexd.internal.shared.common.error.ClientExceptionUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.jdbc.ClientDRDADriver;
import io.snappydata.thrift.internal.ClientConfiguration;
import io.snappydata.thrift.internal.ClientConnection;

/**
 * Client driver encapsulating both Thrift and DRDA protocols as per protocol
 * string. Default is determined by
 * {@link ClientSharedUtils#isThriftDefault()}
 */
public class ClientDriver extends ClientDRDADriver {

  private final static String SUBPROTOCOL = "(drda:|thrift:)?";
  private final static Pattern PROTOCOL_PATTERN = Pattern.compile(URL_PREFIX_REGEX +
      SUBPROTOCOL + "//.*", Pattern.CASE_INSENSITIVE);
  private final static Pattern URL_PATTERN = Pattern.compile(URL_PREFIX_REGEX +
      SUBPROTOCOL + URL_SUFFIX_REGEX, Pattern.CASE_INSENSITIVE);

  static {
    try {
      final ClientDRDADriver driver = registeredDriver__;
      registeredDriver__ = new ClientDriver();
      java.sql.DriverManager.registerDriver(registeredDriver__);
      if (driver != null) {
        java.sql.DriverManager.deregisterDriver(driver);
      }
    } catch (java.sql.SQLException e) {
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
  public boolean acceptsURL(String url) {
    return (url != null && matchProtocol(url).matches());
  }

  @Override
  protected boolean useThriftProtocol(Matcher m) {
    String drdaGroup = m.group(2);
    String protocol = m.group(1);
    // if USE_THRIFT_AS_DEFAULT_PROP has been set explicitly then use that
    // for default value else use the protocol string (jdbc:snappydata://
    //   defaults to thrift while jdbc:gemfirexd:// defaults to old DRDA)
    return drdaGroup == null || drdaGroup.length() == 0
        ? ClientSharedUtils.isUsingThrift(
        protocol.equalsIgnoreCase(SNAPPY_PROTOCOL))
        : "thrift:".equalsIgnoreCase(drdaGroup);
  }

  @Override
  protected Matcher matchURL(String url) {
    return URL_PATTERN.matcher(url);
  }

  @Override
  protected Matcher matchProtocol(String url) {
    return PROTOCOL_PATTERN.matcher(url);
  }

  @Override
  protected java.sql.Connection createThriftConnection(String server, int port,
      java.util.Properties props) throws SQLException {
    return ClientConnection.create(server, port, props,
        DriverManager.getLogWriter());
  }
}
