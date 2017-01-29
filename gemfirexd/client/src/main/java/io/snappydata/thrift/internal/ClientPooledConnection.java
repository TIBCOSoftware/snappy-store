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
package io.snappydata.thrift.internal;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.PooledConnection;
import javax.sql.StatementEvent;
import javax.sql.StatementEventListener;

import io.snappydata.thrift.SnappyException;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.snappydataConstants;

/**
 * {@link PooledConnection} implementation for a thrift based connection.
 */
public class ClientPooledConnection implements PooledConnection {

  protected final ClientService clientService;
  protected ClientFinalizer finalizer;

  /**
   * List of {@link ConnectionEventListener}s. Not thread-safe by design since
   * this is less frequently used and will be accessed in a synchronized block.
   */
  private final ArrayList<ConnectionEventListener> connListeners =
      new ArrayList<>();

  /**
   * thread-safe list of {@link StatementEventListener}s
   */
  private final CopyOnWriteArrayList<StatementEventListener> stmtListeners =
      new CopyOnWriteArrayList<>();

  /**
   * Create a new PooledConnection instance for user with given credentials.
   */
  public ClientPooledConnection(String server, int port,
      boolean forXA, Properties connProps, PrintWriter logWriter)
      throws SQLException {
    this.clientService = ClientService.create(server, port, forXA,
        connProps, logWriter);
    this.finalizer = new ClientFinalizer(this, this.clientService,
        snappydataConstants.BULK_CLOSE_CONNECTION);
    // don't need to call updateReferentData on finalizer for connection
    // since ClientFinalizer will extract the same from current host
    // information in ClientService for the special case of connection
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Connection getConnection() throws SQLException {
    // create a new wrapper ClientConnection on the fly
    return new ClientConnection(this.clientService, this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void close() throws SQLException {
    try {
      final ClientFinalizer finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.clearAll();
        this.finalizer = null;
      }
      if (!this.clientService.isClosed()) {
        this.clientService.closeConnection(0);
      }
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void addConnectionEventListener(
      ConnectionEventListener listener) {
    if (listener != null) {
      this.connListeners.add(listener);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public synchronized void removeConnectionEventListener(
      ConnectionEventListener listener) {
    this.connListeners.remove(listener);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addStatementEventListener(StatementEventListener listener) {
    if (listener != null) {
      this.stmtListeners.add(listener);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void removeStatementEventListener(StatementEventListener listener) {
    this.stmtListeners.remove(listener);
  }

  /**
   * Invoke the connectionClosed event for all the listeners.
   */
  protected synchronized void onConnectionClose() {
    final ArrayList<ConnectionEventListener> listeners = this.connListeners;
    if (!listeners.isEmpty()) {
      ConnectionEvent event = new ConnectionEvent(this);
      for (ConnectionEventListener listener : listeners) {
        listener.connectionClosed(event);
      }
    }
  }

  /**
   * Invoke the connectionErrorOccurred event of all the listeners.
   *
   * @param sqle The SQLException associated with the error
   */
  protected synchronized void onConnectionError(SQLException sqle) {
    final ArrayList<ConnectionEventListener> listeners = this.connListeners;
    if (!listeners.isEmpty()) {
      ConnectionEvent event = new ConnectionEvent(this, sqle);
      for (ConnectionEventListener listener : listeners) {
        listener.connectionErrorOccurred(event);
      }
    }
  }

  /**
   * Invoke the statementClosed event for all the listeners.
   *
   * @param stmt The PreparedStatement that was closed
   */
  protected void onStatementClose(PreparedStatement stmt) {
    final CopyOnWriteArrayList<StatementEventListener> listeners =
        this.stmtListeners;
    if (!listeners.isEmpty()) {
      StatementEvent event = new StatementEvent(this, stmt);
      for (StatementEventListener listener : listeners) {
        listener.statementClosed(event);
      }
    }
  }

  /**
   * Invoke the statementErrorOccurred event of all the listeners.
   *
   * @param stmt The PreparedStatement on which error occurred
   * @param sqle The SQLException associated with the error
   */
  protected void onStatementError(PreparedStatement stmt, SQLException sqle) {
    final CopyOnWriteArrayList<StatementEventListener> listeners =
        this.stmtListeners;
    if (!listeners.isEmpty()) {
      StatementEvent event = new StatementEvent(this, stmt, sqle);
      for (StatementEventListener listener : listeners) {
        listener.statementErrorOccurred(event);
      }
    }
  }
}
