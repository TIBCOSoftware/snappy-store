
package org.apache.ddlutils.platform.gemfirexd;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Adapted from DerbyPlatform for GemFireXD distributed data platform.
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

import java.io.IOException;
import java.io.Writer;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.ddlutils.DatabaseOperationException;
import org.apache.ddlutils.platform.SqlBuilder;
import org.apache.ddlutils.platform.derby.DerbyPlatform;

/**
 * The platform implementation for GemFireXD. To be completed - right now, it is
 * creating like Derby.
 * 
 * @version $Revision: 231306 $
 */
public class GemFireXDPlatform extends DerbyPlatform {

  /** Database name of this platform. */
  public static final String DATABASENAME = "GemFireXD";

  /** The GemFireXD jdbc driver for use as a client for a normal server. */
  public static final String JDBC_CLIENT_DRIVER =
      "com.pivotal.gemfirexd.jdbc.ClientDriver";

  /** The subprotocol used by the GemFireXD drivers. */
  public static final String JDBC_SUBPROTOCOL = "gemfirexd";

  /**
   * Creates a new GemFireXD platform instance.
   */
  public GemFireXDPlatform() {
    super();
    // cannot use explicitly inserted identity column values
    getPlatformInfo().setIdentityOverrideAllowed(false);
    // supports getting identity values using JDBC Statement API
    getPlatformInfo().setIdentityValueReadableUsingStatement(true);
    // supports adding identity column using ALTER TABLE
    getPlatformInfo().setSupportsAddIdentityUsingAlterTable(true);
    // supports exporting all DDLs as SQL strings
    getPlatformInfo().setDDLExportSupported(true);
    setSqlBuilder(new GemFireXDBuilder(this));
    setModelReader(new GemFireXDModelReader(this));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getName() {
    return DATABASENAME;
  }

  protected String getDriver() {
    return JDBC_CLIENT_DRIVER;
  }

  /**
   * {@inheritDoc} You should never try to create a GemFireXD database. Any
   * GemFireXD distributed system hosts a single database. It is implicitly
   * created
   */
  @Override
  public void createDatabase(String jdbcDriverClassName, String connectionUrl,
      String username, String password, Map parameters)
      throws DatabaseOperationException, UnsupportedOperationException {
    // For GemFireXD, no need to create databases
    if (getDriver().equals(jdbcDriverClassName)) {
      StringBuilder creationUrl = new StringBuilder();
      Connection connection = null;

      creationUrl.append(connectionUrl);
      if ((parameters != null) && !parameters.isEmpty()) {
        for (Iterator it = parameters.entrySet().iterator(); it.hasNext();) {
          Map.Entry entry = (Map.Entry)it.next();

          // no need to specify create (and create=false wouldn't help anyway)
          if (!"create".equalsIgnoreCase(entry.getKey().toString())) {
            creationUrl.append(";");
            creationUrl.append(entry.getKey().toString());
            creationUrl.append("=");
            if (entry.getValue() != null) {
              creationUrl.append(entry.getValue().toString());
            }
          }
        }
      }
      if (getLog().isDebugEnabled()) {
        getLog().debug("About to create database using this URL: "
            + creationUrl.toString());
      }
      try {
        Class.forName(jdbcDriverClassName);
        connection = DriverManager.getConnection(creationUrl.toString(),
            username, password);
        logWarnings(connection);
      } catch (Exception ex) {
        throw new DatabaseOperationException(
            "Error while trying to create a database", ex);
      } finally {
        if (connection != null) {
          try {
            connection.close();
          } catch (SQLException ex) {
          }
        }
      }
    }
    else {
      throw new UnsupportedOperationException(
          "Unable to create a GemFireXD database via the driver "
              + jdbcDriverClassName);
    }
  }

  @Override
  protected void writeAllDDLs(Connection conn, Writer writer, boolean exportAll)
      throws SQLException, IOException {
    CallableStatement cstmt = conn.prepareCall("call SYS.EXPORT_ALL_DDLS(?)");
    cstmt.setBoolean(1, exportAll);
    ResultSet rs = cstmt.executeQuery();
    while (rs.next()) {
      writer.write(rs.getString(3));
      writer.write(';');
      writer.write(SqlBuilder.LINE_SEPARATOR);
      writer.write(SqlBuilder.LINE_SEPARATOR);
    }
    rs.close();
    writer.flush();
  }
}
