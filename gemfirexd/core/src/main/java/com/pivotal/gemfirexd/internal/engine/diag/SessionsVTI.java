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
package com.pivotal.gemfirexd.internal.engine.diag;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Iterator;

import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.NetworkInterface;
import com.pivotal.gemfirexd.internal.engine.GfxdVTITemplate;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl.NetworkInterfaceImpl;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;

/**
 * Lists currently active DRDA/Thrift client sessions on this & other nodes.
 *
 * @author soubhikc
 */
public class SessionsVTI extends GfxdVTITemplate {

  Iterator<SessionInfo> iter = null;

  SessionInfo current = null;

  @Override
  public boolean next() throws SQLException {

    if (iter == null) {
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service != null) {
        ArrayList<SessionInfo> sessions = new ArrayList<>();
        assert service instanceof FabricServiceImpl;
        Iterator<NetworkInterface> nwIter = service
            .getAllNetworkServers().iterator();
        while (nwIter.hasNext()) {
          NetworkInterfaceImpl nwImpl = (NetworkInterfaceImpl)nwIter.next();
          sessions.add(nwImpl.getSessionInfo());
        }

        iter = sessions.iterator();
      }
    }

    if (current != null && current.moveNext()) {
      return true;
    }

    while (iter.hasNext()) {
      current = iter.next();
      if (current.moveNext()) {
        return true;
      }
    }

    iter = null;
    return false;
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  /** Metadata */

  public static final String MEMBERID = "ID";

  public static final String SESSIONID = "SESSION_ID";

  public static final String USERID = "USER_ID";

  public static final String CLIENT_BIND_ADDRESS = "CLIENT_BIND_ADDRESS";

  public static final String CLIENT_BIND_PORT = "CLIENT_BIND_PORT";

  public static final String SOCKET_CONNECTION_STATUS = "SOCKET_CONNECTION_STATUS";

  public static final String SESSION_STATUS = "SESSION_STATUS";

  public static final String SESSION_BEGIN_TIME = "SESSION_BEGIN_TIME";

  public static final String SESSION_INFO = "SESSION_INFO";

  public static final String CURRENT_STATEMENT_UUID = "CURRENT_STATEMENT_UUID";

  public static final String CURRENT_STATEMENT = "CURRENT_STATEMENT";

  public static final String CURRENT_STATEMENT_STATUS = "CURRENT_STATEMENT_STATUS";

  public static final String CURRENT_STATEMENT_ELAPSED_TIME = "CURRENT_STATEMENT_ELAPSED_TIME";

  public static final String CURRENT_STATEMENT_ACCESS_FREQUENCY = "CURRENT_STATEMENT_ACCESS_FREQUENCY";

  public static final String CURRENT_STATEMENT_MEMORY_USAGE = "CURRENT_STATEMENT_MEMORY_USAGE";

  public static final String HOSTNAME = "HOSTNAME";

  public static final String SERVER_LISTENING_PORT = "SERVER_LISTENING_PORT";

  public static final String NETWORK_INTERFACE_INFO = "NETWORK_INTERFACE_INFO";

  private static final ResultColumnDescriptor[] columnInfo = {
      EmbedResultSetMetaData.getResultColumnDescriptor(MEMBERID, Types.VARCHAR,
          false, 128),
      EmbedResultSetMetaData.getResultColumnDescriptor(SESSIONID,
          Types.INTEGER, false),
      EmbedResultSetMetaData.getResultColumnDescriptor(HOSTNAME, Types.VARCHAR,
          false, 64),
      EmbedResultSetMetaData.getResultColumnDescriptor(SERVER_LISTENING_PORT,
          Types.INTEGER, false),

      EmbedResultSetMetaData.getResultColumnDescriptor(USERID, Types.VARCHAR,
          false, 128),

      EmbedResultSetMetaData.getResultColumnDescriptor(CLIENT_BIND_ADDRESS,
          Types.VARCHAR, false, 64),

      EmbedResultSetMetaData.getResultColumnDescriptor(CLIENT_BIND_PORT,
          Types.INTEGER, false),

      EmbedResultSetMetaData.getResultColumnDescriptor(
          SOCKET_CONNECTION_STATUS, Types.VARCHAR, false, 128),

      EmbedResultSetMetaData.getResultColumnDescriptor(SESSION_STATUS,
          Types.VARCHAR, false, 128),

      EmbedResultSetMetaData.getResultColumnDescriptor(SESSION_BEGIN_TIME,
          Types.TIMESTAMP, false),

      EmbedResultSetMetaData.getResultColumnDescriptor(SESSION_INFO,
          Types.VARCHAR, false, 128),

      EmbedResultSetMetaData.getResultColumnDescriptor(CURRENT_STATEMENT_UUID,
          Types.VARCHAR, true, 1024),

      EmbedResultSetMetaData.getResultColumnDescriptor(CURRENT_STATEMENT,
          Types.VARCHAR, true, 1024),

      EmbedResultSetMetaData.getResultColumnDescriptor(
          CURRENT_STATEMENT_STATUS, Types.VARCHAR, true, 32),

      EmbedResultSetMetaData.getResultColumnDescriptor(
          CURRENT_STATEMENT_ELAPSED_TIME, Types.DOUBLE, true),

      EmbedResultSetMetaData.getResultColumnDescriptor(
          CURRENT_STATEMENT_ACCESS_FREQUENCY, Types.BIGINT, true),

      EmbedResultSetMetaData.getResultColumnDescriptor(
          CURRENT_STATEMENT_MEMORY_USAGE, Types.BIGINT, true),

      EmbedResultSetMetaData.getResultColumnDescriptor(NETWORK_INTERFACE_INFO,
          Types.VARCHAR, false, 256),

  };

  private static final ResultSetMetaData metadata = new EmbedResultSetMetaData(
      columnInfo);

  @Override
  protected Object getObjectForColumn(int columnNumber) throws SQLException {
    final ResultColumnDescriptor desc = columnInfo[columnNumber - 1];
    final String columnName = desc.getName();
    final Object res;
    if (MEMBERID.equals(columnName)) {
      res = current.memberid;
    }
    else if (SESSIONID.equals(columnName)) {
      res = current.current().connNum;
    }
    else if (USERID.equals(columnName)) {
      res = current.current().userId;
    }
    else if (CLIENT_BIND_ADDRESS.equals(columnName)) {
      res = current.current().clientBindAddress;
    }
    else if (CLIENT_BIND_PORT.equals(columnName)) {
      res = current.current().clientBindPort;
    }
    else if (SOCKET_CONNECTION_STATUS.equals(columnName)) {
      res = current.current().isConnected ? "CONNECTED"
          : current.current().hadConnectedOnce ? "DISCONNECTED"
              : "NOTCONNECTED";
    }
    else if (SESSION_STATUS.equals(columnName)) {
      res = current.current().isActive ? "ACTIVE" : "QUEUED";
    }
    else if (SESSION_INFO.equals(columnName)) {
      SessionInfo.ClientSession s = current.current();
      res = s.connectionProperties;
    }
    else if (CURRENT_STATEMENT_UUID.equals(columnName)) {
      res = current.current().currentStatementUUID;
    }
    else if (CURRENT_STATEMENT.equals(columnName)) {
      res = current.current().currentStatement;
    }
    else if (CURRENT_STATEMENT_STATUS.equals(columnName)) {
      res = current.current().currentStatementStatus;
    }
    else if (CURRENT_STATEMENT_ELAPSED_TIME.equals(columnName)) {
      res = current.current().currentStatementElapsedTime;
    }
    else if (CURRENT_STATEMENT_ACCESS_FREQUENCY.equals(columnName)) {
      res = current.current().currentStatementAccessFrequency;
    }
    else if (CURRENT_STATEMENT_MEMORY_USAGE.equals(columnName)) {
      res = current.current().currentStatementEstimatedMemUsage;
    }
    else if (HOSTNAME.equals(columnName)) {
      res = current.hostname;
    }
    else if (SERVER_LISTENING_PORT.equals(columnName)) {
      res = current.serverListeningPort;
    }
    else if (NETWORK_INTERFACE_INFO.equals(columnName)) {
      res = current.networkInterfaceInfo;
    }
    else {
      res = null;
    }

    return res;
  }

  public java.sql.Timestamp getTimestamp(int columnIndex) throws SQLException {
    final ResultColumnDescriptor desc = columnInfo[columnIndex - 1];
    final String columnName = desc.getName();
    final Timestamp res;
    if (SESSION_BEGIN_TIME.equals(columnName)) {
      res = current.current().connectionBeginTimeStamp;
    }
    else {
      return super.getTimestamp(columnIndex);
    }

    return res;
  }

  /**
   * SessionInfo struct that is used internally within drda classes & engine.
   *
   * This class resides here due to drda compiling after engine code.
   *
   * @author soubhikc
   *
   */
  public final static class SessionInfo {

    public String memberid;

    public String hostname;

    public int serverListeningPort;

    public String networkInterfaceInfo;

    public final static class ClientSession {
      public boolean isActive;

      public String clientBindAddress;

      public int clientBindPort;

      public boolean hadConnectedOnce;

      public boolean isConnected;

      public long connNum;

      public String userId;

      public String connectionProperties;

      public Timestamp connectionBeginTimeStamp;

      public String currentStatementUUID;

      public String currentStatement;

      public String currentStatementStatus;

      public double currentStatementElapsedTime;

      public long currentStatementAccessFrequency;

      public long currentStatementEstimatedMemUsage;
    }

    public final void addClientSession(ClientSession session) {
      sessions.add(session);
    }

    Iterator<ClientSession> iter = null;

    public final boolean moveNext() {
      if (iter == null) {
        iter = sessions.iterator();
      }

      if (iter.hasNext()) {
        current = iter.next();
        return true;
      }

      current = null;
      return false;
    }

    public final ClientSession current() {
      return current;
    }

    private ClientSession current;

    private ArrayList<ClientSession> sessions = new ArrayList<>();
  }
}
