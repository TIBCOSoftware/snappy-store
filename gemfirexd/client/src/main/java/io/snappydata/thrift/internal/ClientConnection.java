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

package io.snappydata.thrift.internal;

import java.io.PrintWriter;
import java.net.SocketException;
import java.sql.*;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

import com.pivotal.gemfirexd.internal.shared.common.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.*;
import io.snappydata.thrift.common.SocketTimeout;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.common.ThriftUtils;
import io.snappydata.thrift.internal.types.InternalSavepoint;
import org.apache.thrift.transport.TTransport;

/**
 * Implementation of JDBC {@link Connection} for the thrift JDBC driver.
 */
@SuppressWarnings("serial")
public final class ClientConnection extends ReentrantLock implements Connection {

  private final ClientService clientService;
  private final ClientPooledConnection clientServiceOwner;
  private volatile boolean isClosed;

  // records last transaction host
  // all operations fail in transactional mode, so no need to check for the host
  // in every operation under transactional context
  private HostConnection txHost;

  // connection properties
  private volatile int rsHoldability = DEFAULT_RS_HOLDABILITY;
  final EnumSet<TransactionAttribute> pendingTXFlags = EnumSet
      .noneOf(TransactionAttribute.class);
  StatementAttrs commonAttrs;

  private volatile SnappyExceptionData warnings;
  private int xaState;

  private ClientFinalizer finalizer;

  // defaults for connection properties
  static final int DEFAULT_RS_HOLDABILITY = ResultSet.CLOSE_CURSORS_AT_COMMIT;

  private int generatedSavepointId;

  ClientConnection(ClientService service,
      ClientPooledConnection serviceOwner) throws SQLException {
    this.clientService = service;
    this.clientServiceOwner = serviceOwner;
    initTXHost(this.clientService);
    this.finalizer = serviceOwner != null ? null : new ClientFinalizer(this,
        this.clientService, snappydataConstants.BULK_CLOSE_CONNECTION);
    // don't need to call updateReferentData on finalizer for connection
    // since ClientFinalizer will extract the same from current host
    // information in ClientService for the special case of connection
  }

  public static ClientConnection create(String host, int port,
      Properties connProperties, PrintWriter logWriter) throws SQLException {
    return new ClientConnection(ClientService.create(
        host, port, false, connProperties, logWriter), null);
  }

  public final ClientService getClientService() {
    return this.clientService;
  }

  final ClientPooledConnection getOwnerPooledConnection() {
    return this.clientServiceOwner;
  }

  final void checkClosedConnection() throws SQLException {
    if (this.isClosed || this.clientService.isClosed()) {
      throw ThriftExceptionUtil.newSQLException(SQLState.NO_CURRENT_CONNECTION,
          null);
    }
  }

  final SQLException informListeners(SQLException sqle) {
    // report only fatal errors
    if (this.clientServiceOwner != null &&
        sqle.getErrorCode() >= ExceptionSeverity.SESSION_SEVERITY) {
      this.clientServiceOwner.onConnectionError(sqle);
    }
    return sqle;
  }

  final Map<TransactionAttribute, Boolean> getPendingTXFlags() {
    if (this.pendingTXFlags.isEmpty()) {
      return null;
    } else {
      final EnumMap<TransactionAttribute, Boolean> txFlags = ThriftUtils
          .newTransactionFlags();
      for (TransactionAttribute pendingFlag : this.pendingTXFlags) {
        // default value sent as false in call to isTXFlagSet does not matter
        // since the flag is guaranteed to be set (to true or false) in any case
        txFlags.put(pendingFlag, this.clientService.isTXFlagSet(
            pendingFlag, false));
      }
      return txFlags;
    }
  }

  /** a set of attributes shared by all statements of this connection */
  public void setCommonStatementAttributes(StatementAttrs attrs) {
    super.lock();
    try {
      this.commonAttrs = attrs;
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientStatement createStatement() throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientStatement(this);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientPreparedStatement prepareStatement(String sql)
      throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientPreparedStatement(this, sql);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientCallableStatement prepareCall(String sql) throws SQLException {
    checkClosedConnection();
    super.lock();
    try {
      return new ClientCallableStatement(this, sql);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String nativeSQL(String sql) throws SQLException {
    checkClosedConnection();
    // no changes
    return sql;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setAutoCommit(boolean autoCommit) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      if (autoCommit != autoCommit()) {
        this.pendingTXFlags.add(TransactionAttribute.AUTOCOMMIT);
        this.clientService.setTXFlag(TransactionAttribute.AUTOCOMMIT,
            autoCommit);
      }
    } finally {
      super.unlock();
    }
  }

  private boolean autoCommit() {
    return this.clientService.isTXFlagSet(TransactionAttribute.AUTOCOMMIT,
        snappydataConstants.DEFAULT_AUTOCOMMIT);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getAutoCommit() throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return autoCommit();
    } finally {
      super.unlock();
    }
  }

  private void initTXHost(final ClientService service) {
    if (service != null) {
      this.txHost = service.getCurrentHostConnection();
    } else {
      this.txHost = null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void commit() throws SQLException {
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection();
      if (getTransactionIsolation() == TRANSACTION_NONE) {
        service.commitTransaction(service.getCurrentHostConnection(), true, null);
      } else {
        service.commitTransaction(txHost, true, null);
      }
      initTXHost(service);
    } catch (SnappyException se) {
      throw informListeners(ThriftExceptionUtil.newSQLException(se));
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollback() throws SQLException {
    final ClientService service = this.clientService;
    super.lock();
    try {
      checkClosedConnection();
      if (getTransactionIsolation() == TRANSACTION_NONE) {
        service.rollbackTransaction(service.getCurrentHostConnection(), true, null);
      } else {
        service.rollbackTransaction(txHost, true, null);
      }
      initTXHost(service);
    } catch (SnappyException se) {
      throw informListeners(ThriftExceptionUtil.newSQLException(se));
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {
    // fire callbacks for pooled connection and return
    if (this.clientServiceOwner != null) {
      if (isClosed()) {
        this.clientServiceOwner.onConnectionError(ThriftExceptionUtil
            .newSQLException(SQLState.PHYSICAL_CONNECTION_ALREADY_CLOSED));
      } else {
        this.clientServiceOwner.onConnectionClose();
      }
      this.isClosed = true;
      return;
    }
    super.lock();
    try {
      final ClientFinalizer finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.clearAll();
        this.finalizer = null;
      }
      // closing an already closed Connection is a no-op as per JDBC spec
      if (isClosed()) {
        return;
      }
      this.clientService.closeConnection(0);
      isClosed = true;
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isClosed() throws SQLException {
    return this.isClosed || this.clientService.isClosed();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public DatabaseMetaData getMetaData() throws SQLException {
    checkClosedConnection();
    return new ClientDBMetaData(this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      final ClientService service = this.clientService;
      if (readOnly != service.isTXFlagSet(
          TransactionAttribute.READ_ONLY_CONNECTION, false)) {
        this.pendingTXFlags.add(TransactionAttribute.READ_ONLY_CONNECTION);
        service.setTXFlag(TransactionAttribute.READ_ONLY_CONNECTION, readOnly);
      }
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isReadOnly() throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return this.clientService.isTXFlagSet(
          TransactionAttribute.READ_ONLY_CONNECTION, false);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setCatalog(String catalog) throws SQLException {
    // Per jdbc spec: if the driver does not support catalogs, it will silently
    // ignore this request.
    checkClosedConnection();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getCatalog() throws SQLException {
    // not supported by SnappyData
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTransactionIsolation(int level) throws SQLException {
    checkClosedConnection();
    // go to server only if there is a change in isolation level
    final ClientService service = this.clientService;
    if (level != service.isolationLevel) {
      super.lock();
      if (level != service.isolationLevel) {
        try {
          service.beginTransaction(level, getPendingTXFlags());
          initTXHost(service);
          // clear the pending transaction flags
          this.pendingTXFlags.clear();
        } catch (SnappyException se) {
          throw informListeners(ThriftExceptionUtil.newSQLException(se));
        } finally {
          super.unlock();
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getTransactionIsolation() throws SQLException {
    return this.clientService.isolationLevel;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLWarning getWarnings() throws SQLException {
    if (this.warnings != null) {
      super.lock();
      try {
        final SnappyExceptionData warnings = this.warnings;
        if (warnings != null) {
          return ThriftExceptionUtil.newSQLWarning(warnings, null);
        }
      } finally {
        super.unlock();
      }
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearWarnings() throws SQLException {
    this.warnings = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientStatement createStatement(int resultSetType,
      int resultSetConcurrency) throws SQLException {
    return createStatement(resultSetType, resultSetConcurrency,
        this.rsHoldability);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientPreparedStatement prepareStatement(String sql,
      int resultSetType, int resultSetConcurrency) throws SQLException {
    return prepareStatement(sql, resultSetType, resultSetConcurrency,
        this.rsHoldability);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientCallableStatement prepareCall(String sql, int resultSetType,
      int resultSetConcurrency) throws SQLException {
    return prepareCall(sql, resultSetType, resultSetConcurrency,
        this.rsHoldability);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<String, Class<?>> getTypeMap() throws SQLException {
    // nothing in SnappyData
    return new HashMap<>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    checkClosedConnection();
    if (map == null) {
      throw ThriftExceptionUtil.newSQLException(SQLState.INVALID_API_PARAMETER,
          null, null, "map", "setTypeMap");
    }
    if (!map.isEmpty()) {
      throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED, null,
          "setTypeMap(Map<String,Class<?>)");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setHoldability(int holdability) throws SQLException {
    checkClosedConnection();
    this.rsHoldability = holdability;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getHoldability() {
    return this.rsHoldability;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Savepoint setSavepoint() throws SQLException {
    super.lock();
    try {
      if (autoCommit()) { // Throw exception if auto-commit is on
        throw ThriftExceptionUtil
            .newSQLException(SQLState.NO_SAVEPOINT_WHEN_AUTO);
      }
      if (++this.generatedSavepointId < 0) {
        this.generatedSavepointId = 1; // restart from 1 on overflow
      }
      InternalSavepoint savepoint = new InternalSavepoint(this,
          this.generatedSavepointId);
      setSavepoint(savepoint);
      return savepoint;
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    super.lock();
    try {
      if (name == null) {
        throw ThriftExceptionUtil
            .newSQLException(SQLState.NULL_NAME_FOR_SAVEPOINT);
      }
      if (autoCommit()) { // Throw exception if auto-commit is on
        throw ThriftExceptionUtil
            .newSQLException(SQLState.NO_SAVEPOINT_WHEN_AUTO);
      }
      InternalSavepoint savepoint = new InternalSavepoint(this, name);
      setSavepoint(savepoint);
      return savepoint;
    } finally {
      super.unlock();
    }
  }

  private void setSavepoint(InternalSavepoint savepoint) throws SQLException {
    ClientStatement stmt = null;
    try {
      stmt = createStatement(ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_READ_ONLY, getHoldability());
      final String savepointName = savepoint.getRealSavepointName();
      String sql = "SAVEPOINT \"" + savepointName
          + "\" ON ROLLBACK RETAIN CURSORS";
      stmt.execute(sql, false, null, null);
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rollback(Savepoint savepoint) throws SQLException {
    ClientStatement stmt = null;
    int saveXaState = 0;
    super.lock();
    try {
      saveXaState = this.xaState;
      if (savepoint == null) { // Throw exception if savepoint is null
        throw ThriftExceptionUtil
            .newSQLException(SQLState.XACT_SAVEPOINT_RELEASE_ROLLBACK_FAIL);
      }
      if (autoCommit()) { // Throw exception if auto-commit is on
        throw ThriftExceptionUtil
            .newSQLException(SQLState.NO_SAVEPOINT_ROLLBACK_OR_RELEASE_WHEN_AUTO);
      }
      // Only allow to rollback to a savepoint from the connection that create
      // the savepoint.
      InternalSavepoint intSP;
      if (!(savepoint instanceof InternalSavepoint)
          || (intSP = (InternalSavepoint)savepoint).getConnection() != this) {
        throw ThriftExceptionUtil
            .newSQLException(SQLState.SAVEPOINT_NOT_CREATED_BY_CONNECTION);
      }

      // Construct and flow a savepoint rollback statement to server.
      stmt = createStatement(ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_READ_ONLY, getHoldability());
      final String savepointName = intSP.getRealSavepointName();
      String sql = "ROLLBACK TO SAVEPOINT \"" + savepointName + "\"";
      stmt.execute(sql, false, null, null);
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (Throwable ignored) {
        }
      }
      this.xaState = saveXaState;
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    ClientStatement stmt = null;
    int saveXaState = 0;
    super.lock();
    try {
      saveXaState = this.xaState;
      if (savepoint == null) { // Throw exception if savepoint is null
        throw ThriftExceptionUtil
            .newSQLException(SQLState.XACT_SAVEPOINT_RELEASE_ROLLBACK_FAIL);
      }
      if (autoCommit()) { // Throw exception if auto-commit is on
        throw ThriftExceptionUtil
            .newSQLException(SQLState.NO_SAVEPOINT_ROLLBACK_OR_RELEASE_WHEN_AUTO);
      }
      // Only allow to rollback to a savepoint from the connection that create
      // the savepoint.
      InternalSavepoint intSP;
      if (!(savepoint instanceof InternalSavepoint)
          || (intSP = (InternalSavepoint)savepoint).getConnection() != this) {
        throw ThriftExceptionUtil
            .newSQLException(SQLState.SAVEPOINT_NOT_CREATED_BY_CONNECTION);
      }

      // Construct and flow a savepoint rollback statement to server.
      stmt = createStatement(ResultSet.TYPE_FORWARD_ONLY,
          ResultSet.CONCUR_READ_ONLY, getHoldability());
      final String savepointName = intSP.getRealSavepointName();
      String sql = "RELEASE SAVEPOINT \"" + savepointName + "\"";
      stmt.execute(sql, false, null, null);
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (Throwable ignored) {
        }
      }
      this.xaState = saveXaState;
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientStatement createStatement(int resultSetType,
      int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientStatement(this, resultSetType, resultSetConcurrency,
          resultSetHoldability);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientPreparedStatement prepareStatement(String sql,
      int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientPreparedStatement(this, sql, resultSetType,
          resultSetConcurrency, resultSetHoldability);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientCallableStatement prepareCall(String sql, int resultSetType,
      int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientCallableStatement(this, sql, resultSetType,
          resultSetConcurrency, resultSetHoldability);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientPreparedStatement prepareStatement(String sql,
      int autoGeneratedKeys) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientPreparedStatement(this, sql,
          autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientPreparedStatement prepareStatement(String sql,
      int[] columnIndexes) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientPreparedStatement(this, sql, columnIndexes);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientPreparedStatement prepareStatement(String sql,
      String[] columnNames) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return new ClientPreparedStatement(this, sql, columnNames);
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Blob createBlob() throws SQLException {
    return new ClientBlob(this.clientService);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Clob createClob() throws SQLException {
    return new ClientClob(this.clientService);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public NClob createNClob() throws SQLException {
    throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED, null,
        "createNClob()");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SQLXML createSQLXML() throws SQLException {
    throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED, null,
        "createSQLXML()");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isValid(final int timeout) throws SQLException {
    if (timeout < 0) {
      throw ThriftExceptionUtil.newSQLException(SQLState.INVALID_API_PARAMETER,
          null, timeout, "timeout", "java.sql.Connection.isValid");
    }

    // Check if the connection is closed
    if (isClosed()) {
      return false;
    }

    // Do a simple query against the database
    super.lock();
    try {
      // Save the current network timeout value
      final int oldTimeout = getTimeout();
      // Set the required timeout value on the network connection
      if (oldTimeout != timeout) {
        setTimeout(timeout);
      }

      // Run a simple validation query against the database
      this.clientService.executeQuery("VALUES(1)", null);

      // Restore the previous timeout value
      if (oldTimeout != timeout) {
        setTimeout(oldTimeout);
      }
    } catch (SnappyException se) {
      // If an SQL exception is thrown the connection is not valid,
      // we ignore the exception and return false.
      return false;
    } finally {
      super.unlock();
    }
    return true; // The connection is valid
  }

  /**
   * <code>setClientInfo</code> will always throw a
   * <code>SQLClientInfoException</code> since SnappyData does not support any
   * properties.
   */
  @Override
  public void setClientInfo(String name, String value)
      throws SQLClientInfoException {
    if (name != null || value != null) {
      HashMap<String, ClientInfoStatus> failedProperties = new HashMap<>(1);
      if (name != null) {
        failedProperties.put(name, ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
      }
      throw ThriftExceptionUtil.newSQLClientInfoException(
          SQLState.PROPERTY_UNSUPPORTED_CHANGE, failedProperties, null, name,
          value);
    }
  }

  /**
   * <code>setClientInfo</code> will throw a <code>SQLClientInfoException</code>
   * unless the <code>properties</code> paramenter is empty, since SnappyData does
   * not support any properties.
   */
  @Override
  public void setClientInfo(Properties properties)
      throws SQLClientInfoException {
    if (properties != null && !properties.isEmpty()) {
      HashMap<String, ClientInfoStatus> failedProperties =
          new HashMap<>(properties.size());
      String firstKey = null;
      for (String key : properties.stringPropertyNames()) {
        if (firstKey == null) {
          firstKey = key;
        }
        failedProperties.put(key, ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
      }
      if (firstKey != null) {
        throw ThriftExceptionUtil.newSQLClientInfoException(
            SQLState.PROPERTY_UNSUPPORTED_CHANGE, failedProperties, null,
            firstKey, properties.getProperty(firstKey));
      }
    }
  }

  /**
   * <code>getClientInfo</code> always returns a <code>null String</code> since
   * SnappyData doesn't support ClientInfoProperties.
   */
  @Override
  public String getClientInfo(String name) throws SQLException {
    checkClosedConnection();
    return null;
  }

  /**
   * <code>getClientInfo</code> always returns an empty <code>Properties</code>
   * object since SnappyData doesn't support ClientInfoProperties.
   */
  @Override
  public Properties getClientInfo() throws SQLException {
    checkClosedConnection();
    return new Properties();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Array createArrayOf(String typeName, Object[] elements)
      throws SQLException {
    throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED, null,
        "createArrayOf(String,Object[])");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Struct createStruct(String typeName, Object[] attributes)
      throws SQLException {
    throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED, null,
        "createStruct(String,Object[])");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    checkClosedConnection();
    try {
      return iface.cast(this);
    } catch (ClassCastException cce) {
      throw ThriftExceptionUtil.newSQLException(SQLState.UNABLE_TO_UNWRAP, cce,
          iface);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    checkClosedConnection();
    return iface.isInstance(this);
  }

  // for JDBC 4.1

  @Override
  public void setSchema(String schema) throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      UpdateResult ur = this.clientService.executeUpdate(
          Collections.singletonList("SET SCHEMA \"" + schema + '"'), null);
      this.warnings = ur.warnings;
    } catch (SnappyException se) {
      throw informListeners(ThriftExceptionUtil.newSQLException(se));
    } finally {
      super.unlock();
    }
  }

  @Override
  public String getSchema() throws SQLException {
    String defaultSchema = this.clientService.getCurrentDefaultSchema();
    if (defaultSchema != null) {
      return defaultSchema;
    }

    super.lock();
    try {
      checkClosedConnection();
      RowSet rs = this.clientService
          .executeQuery("VALUES CURRENT SCHEMA", null);
      List<Row> rows = rs.getRows();
      if (rows != null && rows.size() > 0) {
        return (String)rows.get(0).getObject(0);
      } else {
        return null;
      }
    } catch (SnappyException se) {
      throw informListeners(ThriftExceptionUtil.newSQLException(se));
    } finally {
      super.unlock();
    }
  }

  @Override
  public void abort(Executor executor) throws SQLException {
    // no locking here since it is supposed to be used by admins when the socket
    // may be already in use
    checkClosedConnection();
    // check permission
    SecurityManager sm = System.getSecurityManager();
    if (sm != null) {
      sm.checkPermission(new SQLPermission("callAbort"));
    }
    if (executor == null) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, null,
          "ClientConnection.abort: null executor passed");
    }
    executor.execute(new Runnable() {
      @Override
      public void run() {
        // input and output protocol are identical in our usage
        clientService.getInputProtocol().getTransport().close();
      }
    });
  }

  @Override
  public void setNetworkTimeout(Executor executor, int milliseconds)
      throws SQLException {
    // no locking here since it is supposed to be used by admins when the socket
    // may be already in use
    checkClosedConnection();
    // check permission
    SecurityManager sm = System.getSecurityManager();
    if (sm != null) {
      sm.checkPermission(new SQLPermission("setNetworkTimeout"));
    }
    setTimeout(milliseconds);
  }

  @Override
  public int getNetworkTimeout() throws SQLException {
    super.lock();
    try {
      checkClosedConnection();
      return getTimeout();
    } finally {
      super.unlock();
    }
  }

  private void setTimeout(int milliseconds) throws SQLException {
    // input and output protocol are identical in our usage
    TTransport socket = this.clientService.getInputProtocol().getTransport();
    if (socket instanceof SocketTimeout) {
      try {
        ((SocketTimeout)socket).setSoTimeout(milliseconds);
      } catch (SocketException se) {
        throw informListeners(ThriftExceptionUtil.newSQLException(
            SQLState.SOCKET_EXCEPTION, se, se.getMessage()));
      }
    }
  }

  private int getTimeout() throws SQLException {
    // input and output protocol are identical in our usage
    TTransport socket = this.clientService.getInputProtocol().getTransport();
    if (socket instanceof SocketTimeout) {
      try {
        return ((SocketTimeout)socket).getSoTimeout();
      } catch (SocketException se) {
        throw informListeners(ThriftExceptionUtil.newSQLException(
            SQLState.SOCKET_EXCEPTION, se, se.getMessage()));
      }
    } else {
      return 0;
    }
  }
}
