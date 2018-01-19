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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;

import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THashMap;
import com.koloboke.function.LongObjPredicate;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.NcjHashMapWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import io.snappydata.collection.LongObjectHashMap;

/**
 * Wrapper class for Connections that provides for statement caching, convert to
 * soft reference etc. In general the class is not thread-safe so any
 * synchronization required should be done at higher level using an object like
 * {@link #getConnectionForSynchronization()} or this wrapper object itself.
 * 
 * @author asif
 * @author swale
 */
public final class GfxdConnectionWrapper {

  /**
   * The connection wrapped can either be an {@link EmbedConnection} or a
   * {@link SoftReference} to it.
   */
  private EmbedConnection embedConn;
  private SoftReference<EmbedConnection> embedConnRef;

  /** The default schema for the user for this connection (see bug #41524). */
  private volatile String defaultSchema;

  /** flags for this connection */
  private int flags;

  /** flag to indicate whether this connection is on a remote node */
  private static final int IS_REMOTE = 0x01;

  /** flag to indicate whether this connection is for a DDL on a remote node */
  private static final int IS_REMOTE_DDL = 0x02;

  /**
   * Map to store the {@link EmbedStatement}s seen so far for this connection.
   * Note that this uses a {@link SoftReference} to allow GC to remove the
   * statement which can happen when derby's statement cache also gets full. In
   * such a case the string shall be looked up from {@link #sqlMap}.
   */
  private final LongObjectHashMap<StmntWeakReference> stmntMap;

  /**
   * Connection ID of the incoming request.
   */
  private final long incomingConnId;

  /**
   * Version of connection sync that is incremented with every invocation of
   * {@link #getConnectionForSynchronization()}.
   */
  private int syncVersion;

  /**
   * Marks the current connection as being used, so any subsequent use can wait
   * by invoking {@link #waitFor} method.
   */
  private volatile boolean inUse;
  private volatile boolean hasWaiters;

  /** map used when a statement is no longer available in {@link #stmntMap} */
  private final LongObjectHashMap<String> sqlMap;

  /** queue to clean weak EmbedStatement references from the map */
  private final ReferenceQueue<EmbedStatement> refQueue;

  // statistics data
  // ----------------
  private ResultSetStatisticsVisitor visitor = null;
  // end of statistics data
  // -----------------------

  GfxdConnectionWrapper(String defaultSchema, long incomingConnId,
      boolean isCached, boolean isRemote, boolean isRemoteDDL, Properties props)
      throws SQLException {
    this.defaultSchema = defaultSchema;
    this.incomingConnId = incomingConnId;
    if (isCached) {
      final EmbedConnection conn = createConnection(defaultSchema, isRemote,
          isRemoteDDL, props);
      this.embedConn = conn;
      this.stmntMap = LongObjectHashMap.withExpectedSize(8);
      this.sqlMap = LongObjectHashMap.withExpectedSize(8);
      this.refQueue = new ReferenceQueue<EmbedStatement>();
    }
    else {
      try {
        this.embedConn = GemFireXDUtils.getTSSConnection(true, isRemote,
            isRemoteDDL);
        if (defaultSchema != null) {
          this.embedConn.setDefaultSchema(defaultSchema);
        }
        else {
          this.embedConn.setDefaultSchema(Property.DEFAULT_USER_NAME);
        }
      } catch (StandardException sqle) {
        throw Util.generateCsSQLException(sqle);
      }
      this.stmntMap = null;
      this.sqlMap = null;
      this.refQueue = null;
    }
  }

  /**
   * Create a new {@link EmbedConnection} using default properties and for given
   * default schema. This method also ensures underlying connection's remote
   * flag is set to proper state as provided in the arguments for this wrapper.
   */
  private EmbedConnection createConnection(String defaultSchema, boolean isRemote,
      boolean isRemoteDDL, Properties prop) throws SQLException {
    // check for GFXD boot before creating a connection otherwise it will
    // try to reboot the JVM failing later with arbitrary errors (#47367)
    Misc.getGemFireCache().getCancelCriterion().checkCancelInProgress(null);
    final Properties props = new Properties(prop);
    props.putAll(AuthenticationServiceBase.getPeerAuthenticationService()
        .getBootCredentials());
    GemFireStore store = Misc.getMemStoreBootingNoThrow();
    boolean isStoreSnappy = store != null ? store.isSnappyStore() : false;
    String protocol = !isStoreSnappy ? Attribute.PROTOCOL : Attribute.SNAPPY_PROTOCOL;
    final EmbedConnection conn = (EmbedConnection)InternalDriver.activeDriver()
        .connect(protocol, props,
            EmbedConnection.CHILD_NOT_CACHEABLE, this.incomingConnId, true, Connection.TRANSACTION_NONE);
    conn.setInternalConnection();
    if (isRemote) {
      this.flags = GemFireXDUtils.set(this.flags, IS_REMOTE);
      if (isRemoteDDL) {
        this.flags = GemFireXDUtils.set(this.flags, IS_REMOTE_DDL);
      }
      final LanguageConnectionContext lcc = conn.getLanguageConnection();
      lcc.setIsConnectionForRemote(true);
      lcc.setIsConnectionForRemoteDDL(isRemoteDDL);
      lcc.setSkipLocks(true);
    }
    //Set fresh connection's isolation level as none 
    conn.setTransactionIsolation(java.sql.Connection.TRANSACTION_NONE);
    conn.setAutoCommit(false);
    if (defaultSchema != null) {
      conn.setDefaultSchema(defaultSchema);
    }
    ConnectionStats stats = InternalDriver.activeDriver().getConnectionStats();
    if (stats != null) {
      stats.incInternalConnectionsOpen();
      stats.incInternalConnectionsOpened();
    }
    return conn;
  }

  /**
   * Get the Statement object for given SQL string.
   * @param isCallableStmtWithCohort TODO
   */
  public EmbedStatement getStatement(String sql, long stmtId,
      boolean isPrepStmnt, boolean needGfxdSubActivation,
      boolean flattenSubquery, boolean allReplicated, THashMap ncjMetaData,
      boolean isCallableStmtWithCohort, long rootID, int stmtLevel)
      throws SQLException {
    // For a given statement ID only one thread will be accesing it at a time.
    // Right? Yes I think so because from the originator point of view, access
    // to PrepStmnt has to be thread safe
    // yjing: using soft reference to release the cached the prepared statement
    // in order to release memory. However, a problem still exists in this
    // solution: the size of the hashmap. The better solution is using a hashmap
    // like the WeakHashMap.
    // [sumedh] hard to use WeakHashMap since that uses the key for expiring
    // things from cache while we depend on the value
    EmbedStatement stmnt = null;
    if (isPrepStmnt) {
      if (this.stmntMap != null) {
        final EmbedConnection conn = getConnection();
        // assert connection sync is held before sync on stmntMap else
        // possible deadlock
        assert Thread.holdsLock(conn.getConnectionSynchronization());
        synchronized (this.stmntMap) {
          // cleanup statement map first
          cleanUpStmntMap();
          Object sr = this.stmntMap.get(stmtId);
          if (sr != null) {
            stmnt = ((StmntWeakReference)sr).get();
          }
          if (stmnt == null || !stmnt.isActive() || isCallableStmtWithCohort) {
            if (sql == null) {
              // statement has been GCed and SQL never provided
              sql = (String)this.sqlMap.get(stmtId);
              if (sql == null) {
                throw new GemFireXDRuntimeException(
                    "unexpected null SQL string for statement with ID="
                        + stmtId + " for connId=" + this.incomingConnId
                        + " connection: " + conn + ", for " + this);
              }
            }
            if (!isCallableStmtWithCohort) {
              stmnt = getPreparedStatement(conn, stmtId, sql,
                  needGfxdSubActivation, flattenSubquery, allReplicated,
                  ncjMetaData, rootID, stmtLevel);
            } else {
              stmnt = (EmbedStatement)getConnection().prepareCall(sql, stmtId);
            }
            this.stmntMap.justPut(stmtId, new StmntWeakReference(stmnt, stmtId,
                this.refQueue));
            this.sqlMap.justPut(stmtId, sql);
            if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                  "GfxdConnectionWrapper: cached PreparedStatement with stmtId="
                      + stmtId + " for connId=" + this.incomingConnId
                      + " SQL: " + sql + ", for " + this);
            }
          }
        }
      }
      else {
        stmnt = getPreparedStatement(getConnection(), stmtId, sql,
            needGfxdSubActivation, flattenSubquery, allReplicated, ncjMetaData,
            rootID, stmtLevel);
      }
    }
    else {
      // un-prepared statement added to stmntMap so it statement handle can be 
      // found in case the query is to be cancelled
      if (this.stmntMap != null) {
        final EmbedConnection conn = getConnection();
        if (SanityManager.DEBUG) {
          // assert connection sync is held before sync on stmntMap else
          // possible deadlock
          Assert.assertHoldsLock(conn.getConnectionSynchronization(), true);
        }
        synchronized (this.stmntMap) {
          // cleanup statement map first
          cleanUpStmntMap();
          stmnt = (EmbedStatement)getConnection().createStatement(stmtId);
          this.stmntMap.justPut(stmtId, new StmntWeakReference(stmnt, stmtId,
              this.refQueue));
          if (GemFireXDUtils.TraceQuery) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                "GfxdConnectionWrapper: cached un-prepared statement with stmtId="
                    + stmtId + " for connId=" + this.incomingConnId
                    + " SQL: " + sql + ", for " + this);
          }
        }
      }
      else {
        stmnt = (EmbedStatement)getConnection().createStatement(stmtId);
      }
    }
    if (stmnt != null) {
      if (stmnt.getRootID() == 0 && rootID > 0) {
        stmnt.setRootID(rootID);
      }
      if (stmnt.getStatementLevel() == 0 && stmtLevel > 0) {
        stmnt.setStatementLevel(stmtLevel);
      }
    }
    return stmnt;
  }
  
  public EmbedStatement getStatementForCancellation(long stmtId, 
      long executionId) {
    EmbedStatement stmnt = null;
    if (this.stmntMap != null) {
      synchronized (this.stmntMap) {
        // cleanup statement map first
        cleanUpStmntMap();
        Object sr = this.stmntMap.get(stmtId);
        if (sr != null) {
          stmnt = ((StmntWeakReference)sr).get();
        }
        
        // return null if the stmt is not active or the passed in executionId
        // does not match
        if (stmnt != null ) {
          if (!stmnt.isActive()) {
            return null;
          }
          // if executionId passed is non-zero then 
          // match the executionId as well
          if (executionId != 0) {
            if (stmnt.getExecutionID() != executionId) {
              return null;
            }
          }
        }
      }
    }
    return stmnt;
  }

  /**
   * Private method to get the current connection or create one. Not
   * thread-safe, should be invoked in the context of a lock like
   * {@link #getConnectionForSynchronization()} or synchronized on this wrapper
   * object.
   */
  private EmbedConnection getEmbedConnection() {
    if (this.embedConn != null) {
      return this.embedConn;
    }
    else {
      assert this.embedConnRef != null;
      return this.embedConnRef.get();
    }
  }

  /**
   * Get the current connection or return null if there is no current connection
   * available.
   */
  public final EmbedConnection getConnectionOrNull() {
    final EmbedConnection conn = this.embedConn;
    if (conn != null && conn.isActive()) {
      return conn;
    }
    else {
      return null;
    }
  }

  /**
   * Private method to get the current connection or throw no current connection
   * exception if none found. Not thread-safe, should be invoked in the context
   * of a lock like {@link #getConnectionForSynchronization()} and before the
   * connection has not been converted to a {@link SoftReference}.
   */
  private EmbedConnection getConnection() throws SQLException {
    final EmbedConnection conn = getConnectionOrNull();
    if (conn != null) {
      return conn;
    }
    final SQLException sqle = Util.noCurrentConnection();
    // first check if cache is closing
    Misc.checkIfCacheClosing(sqle);
    throw sqle;
  }

  /**
   * Get the wrapped {@link EmbedConnection} for synchronization. This method
   * will create a new connection if the existing one has been GCed or gotten
   * closed.
   */
  public EmbedConnection getConnectionForSynchronization() throws SQLException {
    return getConnectionForSynchronization(true);
  }

  /**
   * Get the wrapped {@link EmbedConnection} for synchronization. This method
   * will create a new connection if the existing one has been GCed or gotten
   * closed.
   * 
   * @param incrementSyncVersion
   *          pass this as true when the intention is to sync on the connection
   *          object and then mark as unused at the end
   */
  public EmbedConnection getConnectionForSynchronization(
      boolean incrementSyncVersion) throws SQLException {
    EmbedConnection conn;
    boolean doClearStatementMap = false;
    // sync against other getConnectionForSynchronization and
    // convertToSoftReference calls
    synchronized (this) {
      conn = getEmbedConnection();
      if (conn == null || !conn.isActive()) {
        final int flags = this.flags;
        doClearStatementMap = true;
        conn = createConnection(this.defaultSchema, GemFireXDUtils.isSet(flags,
            IS_REMOTE), GemFireXDUtils.isSet(flags, IS_REMOTE_DDL), null);
        this.embedConn = conn;
        this.embedConnRef = null;
      }
      else if (this.embedConnRef != null) {
        this.embedConn = conn;
        this.embedConnRef = null;
      }
      if (incrementSyncVersion) {
        ++this.syncVersion;
      }
    }
    if (doClearStatementMap) {
      clearStatementMap();
    }
    if (GemFireXDUtils.TraceLock) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
          "GfxdConnectionWrapper: getting connection for lock on " + conn);
    }
    return conn;
  }

  /**
   * Convert the wrapped {@link EmbedConnection} into a normal reference if it
   * has been wrapped as a {@link SoftReference}. This does not create a new
   * connection if the {@link SoftReference} has been GCed, so top-level should
   * ensure that it is not GCed. Not thread-safe so should be invoked inside the
   * synchronization lock on the object obtained using
   * {@link #getConnectionForSynchronization()} if multiple threads may be
   * waiting on the connection (which is the case for streaming).
   * <p>
   * 
   * The normal sequence of usage will be thus:
   * 
   * <pre>
   * EmbedConnection conn = wrapper.getConnectionForSynchronization();
   * synchronized (conn.getConnectionSynchronization()) {
   *   wrapper.convertToHardReference(conn);
   *   ...
   *   wrapper.convertToSoftReference();
   * }
   * </pre>
   * 
   * @return the current synchronization version as in the last call to
   *         {@link #getConnectionForSynchronization()}
   */
  public int convertToHardReference(final EmbedConnection conn)
      throws SQLException {
    if (this.embedConn == null) {
      assert this.embedConnRef != null;
      this.embedConn = this.embedConnRef.get();
      if (this.embedConn == null) {
        this.embedConn = conn;
      }
      else if (conn != this.embedConn) {
        throw new GemFireXDRuntimeException(
            "unexpected change in Connection object while executing query");
      }
      this.embedConnRef = null;
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "converted " + conn + " to hard reference");
      }
    }
    this.inUse = true;
    return this.syncVersion;
  }

  /**
   * Convert the wrapped {@link EmbedConnection} into a {@link SoftReference}
   * eligible for GCing if need be. Even though this method is synchronized to
   * work concurrently with {@link #getConnectionForSynchronization()}, this
   * method is not thread-safe for other methods and it should be invoked inside
   * a synchronization lock on the object obtained using
   * {@link #getConnectionForSynchronization()} if multiple threads may be
   * waiting on the connection (which is the case for streaming).
   * 
   * @param syncVersion
   *          the expected synchronization version as obtained from the last
   *          call to {@link #convertToHardReference(EmbedConnection)}; if the
   *          version does not match the current one then it implies that an
   *          intermediate {@link #getConnectionForSynchronization()} has been
   *          invoked and this method is aborted
   */
  public synchronized boolean convertToSoftReference(int syncVersion) {
    if (this.embedConn != null && syncVersion == this.syncVersion) {
      if (GemFireXDUtils.TraceLock) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
            "GfxdConnectionWrapper: converting to SoftReference and releasing "
                + "any lock acquired for Connection: " + this.embedConn);
      }
      this.embedConnRef = new SoftReference<EmbedConnection>(this.embedConn);
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "converted " + this.embedConn + " to soft reference");
      }
      this.embedConn = null;
      return true;
    }
    return false;
  }

  /**
   * Signal any waiters on {@link #waitFor} after a previous call to
   * {@link #convertToHardReference(EmbedConnection)}.
   */
  public void markUnused() {
    if (this.inUse) {
      synchronized (this) {
        this.inUse = false;
        if (this.hasWaiters) {
          this.notifyAll();
        }
      }
    }
  }

  /**
   * Wait for any current executing operation on the connection that has invoked
   * {@link #convertToHardReference(EmbedConnection)} and set the {@link #inUse}
   * flag. NOTE: This method should *only* be used when the code in question is
   * unable to sync on the connection itself by invoking
   * {@link EmbedConnection#getConnectionSynchronization()} on the result of
   * {@link #getConnectionForSynchronization()}, else sync on that is always to
   * be preferred.
   * 
   * @param connForSync
   *          the result of
   *          {@link EmbedConnection#getConnectionSynchronization()} on the
   *          result of {@link #getConnectionForSynchronization()}
   */
  public void waitFor(Object connForSync) {
    if (this.inUse && !Thread.holdsLock(connForSync)) {
      try {
        synchronized (this) {
          this.hasWaiters = true;
          int numLoops = 0;
          while (this.inUse) {
            this.wait(5);
            numLoops++;
            if ((numLoops % 1000) == 0) {
              SanityManager.DEBUG_PRINT("warning:WAITING",
                  "still waiting for connection " + this.incomingConnId
                      + " to be available after " + numLoops + " tries");
            }
          }
          this.hasWaiters = false;
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        // check for JVM going down
        Misc.checkIfCacheClosing(ie);
      }
    }
  }

  /**
   * Restore the context stack for the given {@link EmbedStatement} and
   * {@link EmbedResultSet} ignoring any exceptions.
   * 
   * @param es
   *          the {@link EmbedStatement} for restoring context stack; cannot be
   *          null
   * @param ers
   *          the {@link EmbedResultSet} for restoring context stack; can be
   *          null
   */
  public static void restoreContextStack(final EmbedStatement es,
      final EmbedResultSet ers) {
    try {
      if (ers != null) {
        ers.popStatementContext();
      }
    } catch (Exception ignore) {
      if (GemFireXDUtils.TraceFunctionException) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
           "Got exception in popStatementContext " + ignore);
      }
    }
    finally {
      try {
        es.restoreContextStack();
      } catch (Exception ignore) {
        if (GemFireXDUtils.TraceFunctionException) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
             "Got exception in restoreContextStack " + ignore);
        }
      }
    }
  }

  public String getDefaultSchema() {
    return this.defaultSchema;
  }

  public void setDefaultSchema(String defaultSchema) throws SQLException {
    if (defaultSchema != null || this.defaultSchema != null) {
      if (!ArrayUtils.objectEquals(defaultSchema, this.defaultSchema)) {
        final EmbedConnection conn = getConnectionForSynchronization(false);
        synchronized (conn.getConnectionSynchronization()) {
          if (defaultSchema != null) {
            conn.setDefaultSchema(defaultSchema);
          }
          else {
            conn.setDefaultSchema(Property.DEFAULT_USER_NAME);
          }
          this.defaultSchema = defaultSchema;
        }
      }
    }
  }

  public long getIncomingConnectionId() {
    return this.incomingConnId;
  }

  private EmbedStatement getPreparedStatement(EmbedConnection conn,
      long stmtId, String sql, boolean needGfxdSubActivation,
      boolean flattenSubquery, boolean allReplicated, THashMap ncjMetaData,
      long rootID, int stmtLevel) throws SQLException {
    return (EmbedStatement)conn.prepareStatementByPassQueryInfo(stmtId, sql,
        needGfxdSubActivation, flattenSubquery, allReplicated, ncjMetaData,
        rootID, stmtLevel);
  }

  public Statement createStatement() throws SQLException {
    return getConnection().createStatement();
  }

  public PreparedStatement prepareStatement(String sql) throws SQLException {
    return getConnection().prepareStatement(sql);
  }

  public void enableOpLogger() throws SQLException {
    final LanguageConnectionContext lcc = getConnection()
        .getLanguageConnection();
    lcc.getTransactionExecute().enableLogging();
  }

  public void disableOpLogger() throws SQLException {
    assert this.embedConn != null;
    final EmbedConnection conn = this.embedConn;
    if (!conn.isClosed()) {
      final LanguageConnectionContext lcc = conn.getLanguageConnection();
      lcc.getTransactionExecute().disableLogging();
    }
  }

  /**
   * Close the wrapper clearing the Connection wrapper and cached statement map.
   */
  public void close() {
    close(true, true);
  }

  /**
   * Close the wrapper clearing the Connection wrapper and cached statement map.
   */
  public void close(final boolean closeEmbedConn, final boolean force) {
    final EmbedConnection conn = getEmbedConnection();
    if (closeEmbedConn && conn != null && !conn.isActive()) {
      return;
    }
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "Closing connection: " + conn);
    }
    if (clearStatementMap()) {
      this.sqlMap.clear();
    }
    if (closeEmbedConn && conn != null) {
      try {
        conn.close(false /* do not distribute */);
      } catch (Exception e) {
        if (force) {
          // ignore exception and close the connection forcibly
          conn.forceClose();
        }
        else {
          throw GemFireXDRuntimeException.newRuntimeException(
              "GfxdConnectionWrapper#close: unexpected exception", e);
        }
      }
    }
  }

  private boolean clearStatementMap() {
    if (this.stmntMap == null) {
      return false;
    }
    ArrayList<Object> stmts = null;
    synchronized (this.stmntMap) {
      if (this.stmntMap.size() > 0) {
        CollectStmts collectStmts = new CollectStmts();
        // Iterate over the map & close the statements
        // no need to synchronize since we expect only one thread to access
        // a connection at a time in any case
        this.stmntMap.forEachWhile(collectStmts);
        stmts = collectStmts.stmts;
        this.stmntMap.clear();
      }
    }
    if (stmts != null) {
      final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
          .getInstance();
      for (Object s : stmts) {
        final EmbedStatement stmt = (EmbedStatement)s;
        try {
          if (!stmt.isClosed()) {
            stmt.close();
          }
          if (observer != null) {
            observer.afterClosingWrapperPreparedStatement(stmt.getID(),
                this.incomingConnId);
          }
        } catch (SQLException sqle) {
          // ignore exception during statement close (e.g. due to conn close)
          if (GemFireXDUtils.TraceFunctionException) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
                "GfxdConnectionWrapper#close: unexpected exception", sqle);
          }
        }
      }
    }
    return true;
  }

  public boolean isClosed() {
    final EmbedConnection conn = getEmbedConnection();
    if (conn != null) {
      return !conn.isActive();
    }
    return true;
  }

  public boolean isCached() {
    return (this.stmntMap != null);
  }

  public void closeStatement(long statementID) throws SQLException {
    Object stmt;
    synchronized (this.stmntMap) {
      stmt = this.stmntMap.remove(statementID);
      // also cleanup statement map
      cleanUpStmntMap();
    }
    if (stmt != null) {
      EmbedStatement es = ((StmntWeakReference)stmt).get();
      if (es != null) {
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
            .getInstance();
        es.close();
        if (observer != null) {
          observer.afterClosingWrapperPreparedStatement(statementID,
              this.incomingConnId);
        }
      }
    }
  }

  public void commit() throws SQLException {
    getConnection().commit();
  }

  public void rollback() throws SQLException {
    getConnection().rollback();
  }

  /**
   * Test API only -- NOT THREAD-SAFE.
   */
  public final EmbedStatement getStatementForTEST(long statementID) {
    if (this.stmntMap != null) {
      final Object sr = this.stmntMap.get(statementID);
      if (sr != null) {
        return ((StmntWeakReference)sr).get();
      }
    }
    return null;
  }

  /**
   * Test API only -- NOT THREAD-SAFE.
   */
  public LongObjectHashMap<StmntWeakReference> getStatementMapForTEST() {
    return this.stmntMap;
  }

  public void setAutoCommit(boolean autoCommit) throws SQLException {
    getConnection().setAutoCommit(autoCommit);
  }

  public final LanguageConnectionContext getLanguageConnectionContext()
      throws SQLException {
    return getConnection().getLanguageConnection();
  }

  /**
   * Overload called by query messages. Set the given flags in LCC. Caller must
   * keep the old flags and restore them back at the end.
   */
  public void setLccFlags(final LanguageConnectionContext lcc,
      final boolean isPossibleDuplicate, final boolean enableStats,
      final boolean enableTimeStats, StatementExecutorMessage<?> message)
      throws SQLException {

    lcc.setPossibleDuplicate(isPossibleDuplicate);
    lcc.setStatsEnabled(enableStats, enableTimeStats,
        message != null && message.explainConnectionEnabled());
    lcc.setQueryHDFS(message != null && message.getQueryHDFS());
    if (lcc.getRunTimeStatisticsMode()) {
      try {
        visitor = lcc.getLanguageConnectionFactory().getExecutionFactory()
            .getXPLAINFactory()
            .getXPLAINVisitor(lcc, lcc.statsEnabled(), lcc.explainConnection());
      } catch (StandardException e) {
        throw PublicAPI.wrapStandardException(e);
      }
    }
    else {
      visitor = null;
    }

    if (message != null) {
      if (message.isSkipListeners()) {
        lcc.setSkipListeners();
      }
      lcc.setSkipConstraintChecks(message.isSkipConstraintChecks());
      if (message.getNCJMetaDataOnRemote() != null) {
        lcc.setNcjBatchSize(NcjHashMapWrapper.getBatchSize(message
            .getNCJMetaDataOnRemote()));
        lcc.setNcjCacheSize(NcjHashMapWrapper.getCacheSize(message
            .getNCJMetaDataOnRemote()));
      }
    }
  }

  /**
   * Set the given flags in LCC. Caller must keep the old flags and restore them
   * back at the end.
   */
  public void setLccFlags(final LanguageConnectionContext lcc,
      final boolean isPossibleDuplicate, final boolean enableStats,
      final boolean enableTimeStats, ParameterValueSet constantValueSet,
      StatementExecutorMessage<?> message) throws SQLException {

    setLccFlags(lcc, isPossibleDuplicate, enableStats, enableTimeStats, message);
    if (constantValueSet != null) {
      lcc.setConstantValueSet(null, constantValueSet);
    }
  }

  /**
   * Setup the TX state in {@link GemFireTransaction}. Always do this in
   * connection synchronization so that there are no concurrent ops on the same
   * TX trying to do the same.
   */
  public final static void checkForTransaction(final EmbedConnection conn,
      final GemFireTransaction tran, final TXStateInterface tx)
      throws SQLException {

    final int currentIsolationLevel = conn.getTransactionIsolation();
    if (GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "GfxdConnectionWrapper#checkForTransaction: GFE TX ID="
              + TXManagerImpl.getCurrentTXId() + " current isolation level="
              + currentIsolationLevel + "; required isolation level="
              + (tx == null ? Connection.TRANSACTION_NONE : tx
                  .getIsolationLevel().getJdbcIsolationLevel()));
    }
    if (tx == null) {
      // Asif: currrently derby does not allow degradation of isolation level
      // from higher to NONE
      // [sumedh] We require this for ADO.NET driver that has to avoid a new TX
      // after the commit call.
      /*
      assert currentIsolationLevel == Connection.TRANSACTION_NONE:
        "unexpected current isolation-level " + currentIsolationLevel
        + " and requested NONE";
      */
      // clear any old TXState lying in GemFireTransaction
      tran.clearActiveTXState(false, true);
      /*
      if (currentIsolationLevel == Connection.TRANSACTION_NONE) {
        // non-TX case and nothing changing
        return;
      }
      conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      final GemFireTransaction txn = (GemFireTransaction)conn
          .getLanguageConnection().getTransactionExecute();
      */
      return;
    }
    final int reqIsolationLevel = tx.getIsolationLevel()
        .getJdbcIsolationLevel();
    if (tx.isSnapshot()) {
      tran.setActiveTXState(tx, false);
      return;
    }
    if (reqIsolationLevel == currentIsolationLevel) {
      // GFE TX could have changed so set the new one in the GFXD transaction
      tran.setActiveTXState(tx, true);
      return;
    }
    if (currentIsolationLevel != Connection.TRANSACTION_NONE) {
      // tx boundaries are changing, so this is definitely a new transaction
      // On data store node we should be calling tx.commit while changing
      // isolation levels, but assuming that the previous tx will be null in
      // normal situation retaining it for a while
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "StatementQueryExecutor#checkForTransaction: TX"
                + " boundary changing -- executing commit");
      }
      conn.setTransactionIsolation(reqIsolationLevel);
    }
    else {
      // This transaction is surely comming on this node for first time.
      conn.setTransactionIsolation(reqIsolationLevel);
    }
    // GFE TX will have changed so set the new one in the GFXD transaction
    tran.setActiveTXState(tx, true);
  }

  private void cleanUpStmntMap() {
    StmntWeakReference sRef;
    while ((sRef = (StmntWeakReference)this.refQueue.poll()) != null) {
      final long stmntId = sRef.getStatementId();
      removeWeakReferenceFromStmntMap(stmntId, sRef);
    }
  }

  private void removeWeakReferenceFromStmntMap(long stmntId,
      StmntWeakReference sRef) {
    assert Thread.holdsLock(this.stmntMap);

    if (this.stmntMap.get(stmntId) == sRef) {
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "GfxdConnectionWrapper#removeWeakReferenceFromStmntMap: "
                + "removing statement with id: " + stmntId);
        this.stmntMap.remove(stmntId);
      }
    }
  }
  
  // called for un-prepared statements. Un-prepared statement
  // is added to stmntMap so as to make the statement handle available
  // for query cancellation. it is removed after execution.
  public void removeStmtFromMap(long stmntId) {
    if (this.stmntMap != null) {
      synchronized (this.stmntMap) {
        this.stmntMap.remove(stmntId);
      }
    }
  }

  public final <T> void process(final EmbedConnection conn,
      final ResultHolder rh, final EmbedStatement estmt,
      final StatementExecutorMessage<T> executorMessage)
      throws StandardException {

    if (visitor == null) {
      return;
    }

    if (rh != null) {
      visitor.process(conn, executorMessage, rh,
          executorMessage.isLocallyExecuted());
    }
    else {
      visitor.process(conn, executorMessage, estmt,
          executorMessage.isLocallyExecuted());
    }
    
    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      observer.afterQueryPlanGeneration();
    }
  }

  private static final class CollectStmts
      implements LongObjPredicate<StmntWeakReference> {

    ArrayList<Object> stmts;

    @Override
    public boolean test(final long id, StmntWeakReference stmtRef) {
      Object stmt = stmtRef.get();
      if (stmt != null) {
        if (this.stmts == null) {
          this.stmts = new ArrayList<Object>();
        }
        this.stmts.add(stmt);
      }
      return true;
    }
  }

  /**
   * This class will aid to clean up the statement map of WeakReference objects
   * themselves if the referent embed statements are garbage collected. If we don't
   * do this then the map might grow to a very large size gradually.
   * @author kneeraj
   *
   */
  public static class StmntWeakReference extends WeakReference<EmbedStatement> {

    private final long stmntId;

    public StmntWeakReference(final EmbedStatement stmnt, long stmntId,
        final ReferenceQueue<EmbedStatement> refQueue) {
      super(stmnt, refQueue);
      this.stmntId = stmntId;
    }

    public long getStatementId() {
      return this.stmntId;
    }
  }
}
