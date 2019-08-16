/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.sql.SQLException;
import javax.sql.XAConnection;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import io.snappydata.thrift.internal.datasource.ClientXAConnection;

/**
 * This is SnappyData's network XADataSource for use with JDBC4.0.
 * <p>
 * See {@link ClientDataSource} for DataSource properties.
 *
 * @see javax.sql.XADataSource
 */
public class ClientXADataSource extends com.pivotal.gemfirexd.internal.jdbc.ClientXADataSource {

  /**
   * {@inheritDoc}
   */
  @Override
  public XAConnection getXAConnection(String user, String password)
      throws SQLException {
    if (ClientSharedUtils.isThriftDefault()) {
      return new ClientXAConnection(getServerName(), getPortNumber(),
          ClientDataSource.getThriftProperties(user, password, this),
          getLogWriter());
    } else {
      return super.getXAConnection(user, password);
    }
  }
}
