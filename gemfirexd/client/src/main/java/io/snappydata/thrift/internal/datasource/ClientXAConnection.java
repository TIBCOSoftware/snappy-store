/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
package io.snappydata.thrift.internal.datasource;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;

import io.snappydata.thrift.internal.ClientPooledConnection;

/**
 * {@link XAConnection} implementation for a thrift based connection.
 */
public class ClientXAConnection extends ClientPooledConnection
    implements XAConnection {

  private final ClientXAResource xaResource;

  /**
   * Create a new XAConnection instance for user with given credentials.
   */
  public ClientXAConnection(String server, int port,
      Properties connectionProperties, PrintWriter logWriter)
      throws SQLException {
    super(server, port, true, connectionProperties, logWriter);
    this.xaResource = new ClientXAResource(this.clientService);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public XAResource getXAResource() throws SQLException {
    this.clientService.checkClosedConnection();
    return this.xaResource;
  }
}
