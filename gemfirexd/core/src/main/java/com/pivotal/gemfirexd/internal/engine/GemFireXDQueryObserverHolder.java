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

package com.pivotal.gemfirexd.internal.engine;

import java.io.Serializable;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.execute.QueryObserver;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ComparisonQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;
import com.pivotal.gemfirexd.internal.engine.procedure.ProcedureChunkMessage;
import com.pivotal.gemfirexd.internal.engine.procedure.cohort.ProcedureSender;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TriggerDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SelectNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import com.pivotal.gemfirexd.internal.impl.sql.rules.ExecutionEngineRule;

// Borrowed the idea from the current GFE OQL testing system

/**
 * This class is intended to hold a single 'observer' which will receive
 * callbacks from the gemfirexd query  when various events take place. There can
 * be only one such observer at a time.
 * 
 * Code which wishes to observe events during a query should do so using the
 * following technique:
 * 
 * class MyQueryObserver extends GemFireXDQueryObserverAdapter { // ... override methods
 * of interest ... }
 * 
 * GemFireXDQueryObserver old = GemFireXDQueryObserverHolder.setInstance(new MyQueryObserver());
 * try { //... call query methods here ... } finally { // reset to the original
 * GemFireXDQueryObserver.  GemFireXDQueryObserverHolder.setInstance(old); }
 * 
 * The query code will call methods on this static member using the following
 * technique:
 * 
 * GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance(); try {
 * observer.startMethod(arguments); doSomething(); } finally {
 * observer.stopMethod(arguments); }
 * 
 * Added a simple {@link GemFireXDQueryObserver} implementation that
 * holds a list of {@link GemFireXDQueryObserver}s and invokes each of the
 * instances not necessarily in order so those should not be order dependent.
 * 
 * @author Asif
 * @author swale
 * @see GemFireXDQueryObserver
 * @see GemFireXDQueryObserverAdapter
 * @since GemFireXD
 *
 */
public final class GemFireXDQueryObserverHolder implements GemFireXDQueryObserver {

  /**
   * The current observer list which will be notified of all query events.
   */
  private static volatile GemFireXDQueryObserverHolder _instance = null;

  // create an instance of GemFireXDQueryObserverAdapter so that the class is
  // included in gemfirexd.jar
  final static GemFireXDQueryObserverAdapter _adapterInstance =
    new GemFireXDQueryObserverAdapter();

  private static boolean _instanceSet = false;

  private static final Object _instanceLock = new Object();

  /** instance variable to hold the list of observers */
  private volatile GemFireXDQueryObserver[] observerCollection;

  /** instance variable to hold the list of observers */
  private volatile QueryObserver[] allObserverCollection;

  /**
   * test hook to force volatile read to ensure predictable read in
   * {@link #getInstance()}
   */
  private static final boolean noVolatileRead = !Boolean
      .getBoolean("gemfirexd-impl.observer-volatile-read");

  /**
   * Add a new {@link QueryObserver} to the list of observers for the VM. Note
   * that any number of observers of a given Class can be present at a time.
   * Restriction of only one observer of a given Class has been removed.
   */
  public static void putInstance(QueryObserver observer) {
    if (observer == null) {
      throw new NullPointerException("putInstance: null observer");
    }
    synchronized (_instanceLock) {
      final GemFireXDQueryObserverHolder holder;
      if (_instanceSet) {
        holder = _instance;
      }
      else {
        holder = new GemFireXDQueryObserverHolder();
      }
      final QueryObserver[] observers = holder.allObserverCollection;
      int sz = observers.length;
      final QueryObserver[] newObservers = new QueryObserver[sz + 1];
      System.arraycopy(observers, 0, newObservers, 0, sz);
      newObservers[sz] = observer;
      holder.allObserverCollection = newObservers;
      if (observer instanceof GemFireXDQueryObserver) {
        final GemFireXDQueryObserver[] qobservers = holder.observerCollection;
        sz = qobservers.length;
        final GemFireXDQueryObserver[] newqObservers =
            new GemFireXDQueryObserver[sz + 1];
        System.arraycopy(qobservers, 0, newqObservers, 0, sz);
        newqObservers[sz] = (GemFireXDQueryObserver)observer;
        holder.observerCollection = newqObservers;
      }
      if (!_instanceSet) {
        _instance = holder;
        _instanceSet = true;
      }
    }
  }

  /**
   * Add a new {@link GemFireXDQueryObserver} to the list of observers for the VM
   * if no existing observer of the same Class is found. Returns the existing
   * observer instance if one was found else null.
   */
  public static QueryObserver putInstanceIfAbsent(QueryObserver observer) {
    if (observer == null) {
      throw new NullPointerException("putInstanceIfAbsent: null observer");
    }
    synchronized (_instanceLock) {
      final GemFireXDQueryObserverHolder holder;
      if (_instanceSet) {
        holder = _instance;
      }
      else {
        holder = new GemFireXDQueryObserverHolder();
      }
      QueryObserver existingObserver = null;

      final QueryObserver[] observers = holder.allObserverCollection;
      for (QueryObserver collObserver : observers) {
        if (collObserver.getClass() == observer.getClass()) {
          existingObserver = collObserver;
          break;
        }
      }

      if (!_instanceSet) {
        _instance = holder;
        _instanceSet = true;
      }

      if (existingObserver == null) {
        putInstance(observer);
      }
      return existingObserver;
    }
  }

  /**
   * TEST METHOD: Replace the existing {@link GemFireXDQueryObserver}, if any,
   * with the given instance and return the old instance. Should not be used by
   * product code.
   */
  public static GemFireXDQueryObserver setInstance(
      final GemFireXDQueryObserver observer) {
    if (observer == null) {
      throw new NullPointerException("setInstance: null observer");
    }
    synchronized (_instanceLock) {
      final GemFireXDQueryObserverHolder holder;
      if (_instanceSet) {
        holder = _instance;
      }
      else {
        holder = new GemFireXDQueryObserverHolder();
      }
      GemFireXDQueryObserver existingObserver = null;
      final GemFireXDQueryObserver[] observers = holder.observerCollection;
      if (observers.length > 0) {
        existingObserver = observers[0];
      }
      holder.observerCollection = new GemFireXDQueryObserver[] { observer };
      holder.allObserverCollection = new QueryObserver[] { observer };
      if (!_instanceSet) {
        _instance = holder;
        _instanceSet = true;
      }
      return existingObserver;
    }
  }

  /** Return the current GemFireXDQueryObserver instance */
  public static GemFireXDQueryObserver getInstance() {
    if (noVolatileRead && !_instanceSet) {
      return null;
    }
    return _instance;
  }

  /**
   * Returns the first observer of the given class (registered using
   * {@link #putInstance(QueryObserver)} or
   * {@link #putInstanceIfAbsent(QueryObserver)} or
   * {@link #setInstance(GemFireXDQueryObserver)}), or null if none found.
   */
  @SuppressWarnings("unchecked")
  public static <T extends QueryObserver> T getObserver(Class<T> c) {
    if (c == null) {
      throw new NullPointerException("getObserver: null class");
    }
    final GemFireXDQueryObserverHolder holder;
    if (_instanceSet && (holder = _instance) != null) {
      final QueryObserver[] allObservers = holder.allObserverCollection;
      for (QueryObserver observer : allObservers) {
        if (observer.getClass().equals(c)) {
          return (T)observer;
        }
      }
    }
    return null;
  }

  /**
   * Returns all the observers of the given class (registered using
   * {@link #putInstance(QueryObserver)} or
   * {@link #putInstanceIfAbsent(QueryObserver)} or
   * {@link #setInstance(GemFireXDQueryObserver)}), or null if none found.
   */
  @SuppressWarnings("unchecked")
  public static <T extends QueryObserver> Collection<T> getObservers(
      final Class<T> c) {
    if (c == null) {
      throw new NullPointerException("getObservers: null class");
    }
    final GemFireXDQueryObserverHolder holder;
    if (_instanceSet && (holder = _instance) != null) {
      final QueryObserver[] allObservers = holder.allObserverCollection;
      final ArrayList<T> observers = new ArrayList<T>(4);
      for (QueryObserver observer : allObservers) {
        if (observer.getClass().equals(c)) {
          observers.add((T)observer);
        }
      }
      if (observers.size() > 0) {
        return observers;
      }
    }
    return null;
  }

  /**
   * Returns all the registered observers.
   */
  public static Collection<QueryObserver> getAllObservers() {
    final GemFireXDQueryObserverHolder holder;
    if (_instanceSet && (holder = _instance) != null) {
      final QueryObserver[] observers = holder.allObserverCollection;
      if (observers != null) {
        return new ArrayList<QueryObserver>(Arrays.asList(observers));
      }
    }
    return Collections.emptySet();
  }

  /**
   * Removes all observers of the given class (registered using
   * {@link #putInstance(QueryObserver)} or
   * {@link #putInstanceIfAbsent(QueryObserver)} or
   * {@link #setInstance(GemFireXDQueryObserver)}) and returns true if any
   * observer was removed else false.
   */
  public static boolean removeObserver(final Class<? extends QueryObserver> c) {
    if (c == null) {
      throw new NullPointerException("removeObserver: null class");
    }
    synchronized (_instanceLock) {
      final GemFireXDQueryObserverHolder holder;
      if (_instanceSet && (holder = _instance) != null) {
        int numRemoved1 = 0, numRemoved2 = 0;
        final QueryObserver[] observers = holder.allObserverCollection;
        final GemFireXDQueryObserver[] qobservers = holder.observerCollection;
        for (QueryObserver observer : observers) {
          if (observer.getClass().equals(c)) {
            if (observer instanceof GemFireXDQueryObserver) {
              for (GemFireXDQueryObserver qobserver : qobservers) {
                if (qobserver.equals(observer)) {
                  numRemoved2++;
                  break;
                }
              }
            }
            numRemoved1++;
          }
        }
        if (numRemoved1 > 0) {
          final QueryObserver[] newObservers =
              new QueryObserver[observers.length - numRemoved1];
          if (newObservers.length > 0) {
            copyAndRemove(observers, newObservers, c);
          }
          holder.allObserverCollection = newObservers;
          if (numRemoved2 > 0) {
            final GemFireXDQueryObserver[] newqObservers =
                new GemFireXDQueryObserver[qobservers.length - numRemoved2];
            if (newqObservers.length > 0) {
              copyAndRemove(qobservers, newqObservers, c);
            }
            holder.observerCollection = newqObservers;
          }
          return true;
        }
      }
      return false;
    }
  }

  /** remove the given observer instance */
  public static boolean removeObserver(QueryObserver removeObserver) {
    if (removeObserver == null) {
      throw new NullPointerException("removeObserver: null observer");
    }
    synchronized (_instanceLock) {
      final GemFireXDQueryObserverHolder holder;
      if (_instanceSet && (holder = _instance) != null) {
        int numRemoved1 = 0, numRemoved2 = 0;
        final QueryObserver[] observers = holder.allObserverCollection;
        final GemFireXDQueryObserver[] qobservers = holder.observerCollection;
        for (QueryObserver observer : observers) {
          if (observer.equals(removeObserver)) {
            numRemoved1++;
          }
        }
        for (GemFireXDQueryObserver qobserver : qobservers) {
          if (qobserver.equals(removeObserver)) {
            numRemoved2++;
          }
        }
        if (numRemoved1 > 0) {
          final QueryObserver[] newObservers =
              new QueryObserver[observers.length - numRemoved1];
          if (newObservers.length > 0) {
            copyAndRemove(observers, newObservers, removeObserver);
          }
          holder.allObserverCollection = newObservers;
        }
        if (numRemoved2 > 0) {
          final GemFireXDQueryObserver[] newqObservers =
              new GemFireXDQueryObserver[qobservers.length - numRemoved2];
          if (newqObservers.length > 0) {
            copyAndRemove(qobservers, newqObservers, removeObserver);
          }
          holder.observerCollection = newqObservers;
        }
        return (numRemoved1 > 0);
      }
      return false;
    }
  }

  private static void copyAndRemove(final QueryObserver[] observers,
      final QueryObserver[] newObservers, final Object remove) {
    int index = 0;
    final QueryObserver removeObserver;
    final Class<?> observerClass;
    if (remove instanceof QueryObserver) {
      removeObserver = (QueryObserver)remove;
      observerClass = null;
    }
    else {
      removeObserver = null;
      observerClass = (Class<?>)remove;
    }
    for (QueryObserver observer : observers) {
      if (removeObserver != null) {
        if (!observer.equals(removeObserver)) {
          newObservers[index++] = observer;
        }
      }
      else if (!observer.getClass().equals(observerClass)) {
        newObservers[index++] = observer;
      }
    }
  }

  public static void clearInstance() {
    synchronized (_instanceLock) {
      if (_instanceSet && _instance != null) {
        _instance.close();
        _instance.observerCollection = new GemFireXDQueryObserver[0];
        _instance.allObserverCollection = new QueryObserver[0];
      }
      _instanceSet = false;
      _instance = null;
    }
  }

  public GemFireXDQueryObserverHolder() {
    this.observerCollection = new GemFireXDQueryObserver[0];
    this.allObserverCollection = new QueryObserver[0];
  }

  // base QueryObserver implementations
  // NOTE: always use allObserverCollection for QueryObserver methods

  @Override
  public void afterBulkOpDBSynchExecution(Event.Type type, int numRowsModified,
      Statement ps, String dml) {
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      observer.afterBulkOpDBSynchExecution(type, numRowsModified, ps, dml);
    }
  }

  @Override
  public void afterPKBasedDBSynchExecution(Event.Type type,
      int numRowsModified, Statement ps) {
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      observer.afterPKBasedDBSynchExecution(type, numRowsModified, ps);
    }
  }

  @Override
  public void afterCommitDBSynchExecution(List<Event> batchProcessed) {
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      observer.afterCommitDBSynchExecution(batchProcessed);
    }
  }

  @Override
  public PreparedStatement afterQueryPrepareFailure(Connection conn,
      String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability, int autoGeneratedKeys, int[] columnIndexes,
      String[] columnNames, SQLException sqle) throws SQLException {
    PreparedStatement result = null;
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      PreparedStatement ps = observer.afterQueryPrepareFailure(conn, sql,
          resultSetType, resultSetConcurrency, resultSetHoldability,
          autoGeneratedKeys, columnIndexes, columnNames, sqle);
      if (ps != null) {
        result = ps;
      }
    }
    return result;
  }

  @Override
  public boolean afterQueryExecution(CallbackStatement stmt, SQLException sqle)
      throws SQLException {
    boolean result = false;
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      result |= observer.afterQueryExecution(stmt, sqle);
    }
    return result;
  }

  @Override
  public void afterCommit(Connection conn) {
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      observer.afterCommit(conn);
    }
  }

  @Override
  public void afterRollback(Connection conn) {
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      observer.afterRollback(conn);
    }
  }

  @Override
  public void afterConnectionClose(Connection conn) {
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      observer.afterConnectionClose(conn);
    }
  }

  @Override
  public void close() {
    final QueryObserver[] observers = this.allObserverCollection;
    for (QueryObserver observer : observers) {
      observer.close();
    }
  }

  // end base QueryObserver implementations

  @Override
  public void afterQueryParsing(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterQueryParsing(query, qt, lcc);
    }
  }

  @Override
  public void beforeOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeOptimizedParsedTree(query, qt, lcc);
    }
  }

  @Override
  public void afterOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterOptimizedParsedTree(query, qt, lcc);
    }
  }

  @Override
  public void beforeQueryExecution(EmbedStatement stmt, Activation activation)
      throws SQLException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeQueryExecution(stmt, activation);
    }
  }

  @Override
  public void beforeBatchQueryExecution(EmbedStatement stmt, int batchSize)
      throws SQLException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeBatchQueryExecution(stmt, batchSize);
    }
  }

  @Override
  public void afterBatchQueryExecution(EmbedStatement stmt, int batchSize) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterBatchQueryExecution(stmt, batchSize);
    }
  }

  @Override
  public void beforeGemFireActivationCreate(AbstractGemFireActivation ac) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeGemFireActivationCreate(ac);
    }
  }

  @Override
  public void afterGemFireActivationCreate(AbstractGemFireActivation ac) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterGemFireActivationCreate(ac);
    }
  }

  @Override
  public void beforeGemFireResultSetOpen(AbstractGemFireResultSet rs,
      LanguageConnectionContext lcc) throws StandardException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeGemFireResultSetOpen(rs, lcc);
    }
  }

  @Override
  public void afterGemFireResultSetOpen(AbstractGemFireResultSet rs,
      LanguageConnectionContext lcc) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterGemFireResultSetOpen(rs, lcc);
    }
  }

  @Override
  public void beforeGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeGemFireResultSetExecuteOnActivation(activation);
    }
  }

  @Override
  public void afterGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterGemFireResultSetExecuteOnActivation(activation);
    }
  }

  @Override
  public void beforeComputeRoutingObjects(AbstractGemFireActivation activation) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeComputeRoutingObjects(activation);
    }
  }

  @Override
  public void afterComputeRoutingObjects(AbstractGemFireActivation activation) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterComputeRoutingObjects(activation);
    }
  }

  @Override
  public <T extends Serializable> void beforeQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeQueryDistribution(executorMessage, streaming);
    }
  }

  @Override
  public <T extends Serializable> void afterQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterQueryDistribution(executorMessage, streaming);
    }
  }

  @Override
  public void beforeGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeGemFireResultSetClose(rs, query);
    }
  }
  
  @Override
  public void beforeEmbedResultSetClose(EmbedResultSet rs,
      String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeEmbedResultSetClose(rs, query);
    }
  }

  @Override
  public void afterGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterGemFireResultSetClose(rs, query);
    }
  }

  @Override
  public void beforeResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeResultHolderExecution(wrapper, es);
    }
  }

  @Override
  public void afterResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es, String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterResultHolderExecution(wrapper, es, query);
    }
  }

  @Override
  public void beforeResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeResultHolderIteration(wrapper, es);
    }
  }

  @Override
  public void afterResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterResultHolderIteration(wrapper, es);
    }
  }

  @Override
  public void beforeResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeResultHolderSerialization(wrapper, es);
    }
  }

  @Override
  public void afterResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterResultHolderSerialization(wrapper, es);
    }
  }

  @Override
  public void beforeResultSetHolderRowRead(RowFormatter rf, Activation act) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeResultSetHolderRowRead(rf, act);
    }
  }

  @Override
  public void afterResultSetHolderRowRead(RowFormatter rf, ExecRow row,
      Activation act) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterResultSetHolderRowRead(rf, row, act);
    }
  }

  @Override
  public void beforeQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeQueryExecutionByStatementQueryExecutor(wrapper, stmt,
          query);
    }
  }

  @Override
  public void afterQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer
          .afterQueryExecutionByStatementQueryExecutor(wrapper, stmt, query);
    }
  }

  @Override
  public void beforeQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeQueryExecutionByPrepStatementQueryExecutor(wrapper, pstmt,
          query);
    }
  }

  @Override
  public void afterQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterQueryExecutionByPrepStatementQueryExecutor(wrapper, pstmt,
          query);
    }
  }

  @Override
  public void createdGemFireXDResultSet(ResultSet rs) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.createdGemFireXDResultSet(rs);
    }
  }

  @Override
  public void beforeIndexUpdatesAtRegionLevel(LocalRegion owner,
      EntryEventImpl event, RegionEntry entry) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeIndexUpdatesAtRegionLevel(owner, event, entry);
    }
  }

  @Override
  public void beforeForeignKeyConstraintCheckAtRegionLevel() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeForeignKeyConstraintCheckAtRegionLevel();
    }
  }

  @Override
  public void beforeUniqueConstraintCheckAtRegionLevel() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeUniqueConstraintCheckAtRegionLevel();
    }
  }

  @Override
  public void beforeGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeGlobalIndexLookup(lcc, indexRegion, indexKey);
    }
  }

  @Override
  public void afterGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey, Object result) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterGlobalIndexLookup(lcc, indexRegion, indexKey, result);
    }
  }

  @Override
  public void scanControllerOpened(Object sc, Conglomerate conglom) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.scanControllerOpened(sc, conglom);
    }
  }

  @Override
  public void beforeConnectionCloseByExecutorFunction(long[] connectionIDs) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeConnectionCloseByExecutorFunction(connectionIDs);
    }
  }

  @Override
  public void afterConnectionCloseByExecutorFunction(long[] connectionIDs) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterConnectionCloseByExecutorFunction(connectionIDs);
    }
  }

  @Override
  public void beforeORM(Activation activation, AbstractGemFireResultSet rs) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeORM(activation, rs);
    }
  }

  @Override
  public void afterORM(Activation activation, AbstractGemFireResultSet rs) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterORM(activation, rs);
    }
  }

  @Override
  public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.queryInfoObjectFromOptmizedParsedTree(qInfo, gps, lcc);
    }
  }

  @Override
  public void queryInfoObjectAfterPreparedStatementCompletion(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.queryInfoObjectAfterPreparedStatementCompletion(qInfo, gps, lcc);
    }
  }

  @Override
  public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
      OpenMemIndex memIndex, double optimizerEvalutatedCost) {
    // return the last value, if any, that does not match the given
    // optimzerEvalutatedCost
    double resCost = optimizerEvalutatedCost;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      final double newCost = observer
          .overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(memIndex,
              optimizerEvalutatedCost);
      if (newCost != optimizerEvalutatedCost) {
        resCost = newCost;
      }
    }
    return resCost;
  }

  @Override
  public double overrideDerbyOptimizerCostForMemHeapScan(
      GemFireContainer gfContainer, double optimizerEvalutatedCost) {
    // return the last value, if any, that does not match the given
    // optimzerEvalutatedCost
    double resCost = optimizerEvalutatedCost;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      final double newCost = observer.overrideDerbyOptimizerCostForMemHeapScan(
          gfContainer, optimizerEvalutatedCost);
      if (newCost != optimizerEvalutatedCost) {
        resCost = newCost;
      }
    }
    return resCost;
  }

  @Override
  public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
      OpenMemIndex memIndex, double optimizerEvalutatedCost) {
    // return the last value, if any, that does not match the given
    // optimzerEvalutatedCost
    double resCost = optimizerEvalutatedCost;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      final double newCost = observer
          .overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(memIndex,
              optimizerEvalutatedCost);
      if (newCost != optimizerEvalutatedCost) {
        resCost = newCost;
      }
    }
    return resCost;
  }

  @Override
  public void beforeQueryExecution(GenericPreparedStatement stmt,
      LanguageConnectionContext lcc) throws StandardException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeQueryExecution(stmt, lcc);
    }
  }

  @Override
  public void afterQueryExecution(GenericPreparedStatement stmt,
      Activation activation) throws StandardException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterQueryExecution(stmt, activation);
    }
  }

  @Override
  public void afterResultSetOpen(GenericPreparedStatement stmt,
      LanguageConnectionContext lcc, ResultSet resultSet) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterResultSetOpen(stmt, lcc, resultSet);
    }
  }

  @Override
  public void onEmbedResultSetMovePosition(EmbedResultSet rs, ExecRow newRow,
      ResultSet theResults) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.onEmbedResultSetMovePosition(rs, newRow, theResults);
    }
  }

  @Override
  public void criticalUpMemoryEvent(GfxdHeapThresholdListener listener) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.criticalUpMemoryEvent(listener);
    }
  }

  @Override
  public void criticalDownMemoryEvent(GfxdHeapThresholdListener listener) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.criticalDownMemoryEvent(listener);
    }
  }

  @Override
  public void estimatingMemoryUsage(String stmtText, Object resultSet) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.estimatingMemoryUsage(stmtText, resultSet);
    }
  }

  @Override
  public long estimatedMemoryUsage(String stmtText, long memused) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.estimatedMemoryUsage(stmtText, memused);
    }
    return memused;
  }

  @Override
  public void putAllCalledWithMapSize(int size) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.putAllCalledWithMapSize(size);
    }
  }

  @Override
  public void afterClosingWrapperPreparedStatement(long wrapperPrepStatementID,
      long wrapperConnectionID) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterClosingWrapperPreparedStatement(wrapperPrepStatementID,
          wrapperConnectionID);
    }
  }

  @Override
  public void updatingColocationCriteria(ComparisonQueryInfo cqi) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.updatingColocationCriteria(cqi);
    }
  }

  @Override
  public void statementStatsBeforeExecutingStatement(StatementStats stats) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.statementStatsBeforeExecutingStatement(stats);
    }
  }

  @Override
  public void reset() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.reset();
    }
  }

  @Override
  public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
      GenericPreparedStatement gps, String subquery,
      List<Integer> paramPositions) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.subqueryNodeProcessedData(qInfo, gps, subquery, paramPositions);
    }
  }

  @Override
  public void insertMultipleRowsBeingInvoked(int numElements) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.insertMultipleRowsBeingInvoked(numElements);
    }
  }

  @Override
  public void keyAndContainerAfterLocalIndexInsert(Object key,
      Object rowLocation, GemFireContainer container) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer
          .keyAndContainerAfterLocalIndexInsert(key, rowLocation, container);
    }
  }

  @Override
  public void keyAndContainerAfterLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer
          .keyAndContainerAfterLocalIndexDelete(key, rowLocation, container);
    }
  }

  @Override
  public void keyAndContainerBeforeLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.keyAndContainerBeforeLocalIndexDelete(key, rowLocation,
          container);
    }
  }

  @Override
  public void getAllInvoked(int numKeys) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.getAllInvoked(numKeys);
    }
  }

  @Override
  public void getAllGlobalIndexInvoked(int numKeys) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.getAllGlobalIndexInvoked(numKeys);
    }
  }

  @Override
  public void getAllLocalIndexInvoked(int numKeys) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.getAllLocalIndexInvoked(numKeys);
    }
  }

  @Override
  public void getAllLocalIndexExecuted() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.getAllLocalIndexExecuted();
    }
  }
  
  @Override
  public void ncjPullResultSetOpenCoreInvoked() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.ncjPullResultSetOpenCoreInvoked();
    }
  }
  
  @Override
  public void getStatementIDs(long stID, long rootID, int stLevel) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.getStatementIDs(stID, rootID, stLevel);
    }
  }

  @Override
  public void ncjPullResultSetVerifyBatchSize(int value) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.ncjPullResultSetVerifyBatchSize(value);
    }
  }

  @Override
  public void ncjPullResultSetVerifyCacheSize(int value) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.ncjPullResultSetVerifyCacheSize(value);
    }
  }
  
  @Override
  public void ncjPullResultSetVerifyVarInList(boolean value) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.ncjPullResultSetVerifyVarInList(value);
    }
  }
  
  @Override
  public void independentSubqueryResultsetFetched(Activation activation,
      ResultSet results) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.independentSubqueryResultsetFetched(activation, results);
    }
  }

  @Override
  public void subQueryInfoObjectFromOptmizedParsedTree(
      List<SubQueryInfo> qInfoList, GenericPreparedStatement gps,
      LanguageConnectionContext lcc) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.subQueryInfoObjectFromOptmizedParsedTree(qInfoList, gps, lcc);
    }
  }

  @Override
  public void beforeInvokingContainerGetTxRowLocation(
      final RowLocation regionEntry) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeInvokingContainerGetTxRowLocation(regionEntry);
    }
  }

  @Override
  public void afterGetRoutingObject(Object routingObject) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterGetRoutingObject(routingObject);
    }
  }

  @Override
  public long overrideUniqueID(long actualUniqueID, boolean forRegionKey) {
    // return the last value, if any, that does not match the given
    // optimzerEvalutatedCost
    long retVal = actualUniqueID;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      final long newID = observer
          .overrideUniqueID(actualUniqueID, forRegionKey);
      if (newID != actualUniqueID) {
        retVal = newID;
      }
    }
    return retVal;
  }

  @Override
  public boolean beforeProcedureResultSetSend(final ProcedureSender sender,
      final EmbedResultSet rs) {
    boolean result = true;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      result &= observer.beforeProcedureResultSetSend(sender, rs);
    }
    return result;
  }

  @Override
  public boolean beforeProcedureOutParamsSend(final ProcedureSender sender,
      final ParameterValueSet pvs) {
    boolean result = true;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      result &= observer.beforeProcedureOutParamsSend(sender, pvs);
    }
    return result;
  }

  @Override
  public void beforeProcedureChunkMessageSend(final ProcedureChunkMessage msg) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeProcedureChunkMessageSend(msg);
    }
  }

  @Override
  public void lockingRowForTX(TXStateProxy tx, GemFireContainer container,
      RegionEntry entry, boolean writeLock) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.lockingRowForTX(tx, container, entry, writeLock);
    }
  }

  @Override
  public void attachingKeyInfoForUpdate(GemFireContainer container,
      RegionEntry entry) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.attachingKeyInfoForUpdate(container, entry);
    }
  }

  @Override
  public int overrideSortBufferSize(ColumnOrdering[] columnOrdering,
      int sortBufferMax) {
    int resBufferMax = sortBufferMax;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      final int newBufferMax = observer.overrideSortBufferSize(columnOrdering,
          sortBufferMax);
      if (newBufferMax != sortBufferMax) {
        resBufferMax = newBufferMax;
      }
    }
    return resBufferMax;
  }

  @Override
  public boolean avoidMergeRuns() {
    boolean avoid = true;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      final boolean newValue = observer.avoidMergeRuns();
      if (newValue != avoid) {
        avoid = newValue;
      }
    }
    return avoid;
  }

  @Override
  public void callAtOldValueSameAsNewValueCheckInSM2IIOp() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.callAtOldValueSameAsNewValueCheckInSM2IIOp();
    }
  }

  @Override
  public void onGetNextRowCore(ResultSet resultSet) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.onGetNextRowCore(resultSet);
    }
  }
  
  @Override
  public void onGetNextRowCoreOfBulkTableScan(ResultSet resultSet) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.onGetNextRowCoreOfBulkTableScan(resultSet);
    }
  }
  
  @Override
  public void onGetNextRowCoreOfGfxdSubQueryResultSet(ResultSet resultSet) {
    for (GemFireXDQueryObserver observer : this.observerCollection) {
      observer.onGetNextRowCoreOfGfxdSubQueryResultSet(resultSet);
    }
  }
  
  @Override
  public void onDeleteResultSetOpen(ResultSet resultSet) {
    for (GemFireXDQueryObserver observer : this.observerCollection) {
      observer.onDeleteResultSetOpen(resultSet);
    }
  }
  
  @Override
  public void onDeleteResultSetOpenAfterRefChecks(ResultSet resultSet) {
    for (GemFireXDQueryObserver observer : this.observerCollection) {
      observer.onDeleteResultSetOpenAfterRefChecks(resultSet);
    }
  }
  
  @Override
  public void onDeleteResultSetOpenBeforeRefChecks(ResultSet resultSet) {
    for (GemFireXDQueryObserver observer : this.observerCollection) {
      observer.onDeleteResultSetOpenBeforeRefChecks(resultSet);
    }
  }
  
  
  @Override
  public void setRoutingObjectsBeforeExecution(
      final Set<Object> routingKeysToExecute) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.setRoutingObjectsBeforeExecution(routingKeysToExecute);
    }
  }

  @Override
  public void beforeDropGatewayReceiver() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeDropGatewayReceiver();
    }
  }

  @Override
  public void beforeDropDiskStore() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeDropDiskStore();
    }
  }

  @Override
  public void memberConnectionAuthenticationSkipped(boolean skipped) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.memberConnectionAuthenticationSkipped(skipped);
    }
  }

  @Override
  public void userConnectionAuthenticationSkipped(boolean skipped) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.userConnectionAuthenticationSkipped(skipped);
    }
  }

  @Override
  public void regionSizeOptimizationTriggered(FromBaseTable fbt,
      SelectNode selectNode) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.regionSizeOptimizationTriggered(fbt, selectNode);
    }
  }
  
  @Override
  public void regionSizeOptimizationTriggered2(SelectNode selectNode) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.regionSizeOptimizationTriggered2(selectNode);
    }
  }

  @Override
  public void beforeFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeFlushBatch(rs, lcc);
    }
  }

  @Override
  public void afterFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterFlushBatch(rs, lcc);
    }
  }

  @Override
  public void invokeCacheCloseAtMultipleInsert() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.invokeCacheCloseAtMultipleInsert();
    }
  }

  @Override
  public boolean isCacheClosedForTesting() {
    boolean result = false;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      result |= observer.isCacheClosedForTesting();
    }
    return result;
  }

  @Override
  public void afterGlobalIndexInsert(boolean posDup) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterGlobalIndexInsert(posDup);
    }
  }

  @Override
  public boolean needIndexRecoveryAccounting() {
    boolean result = false;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      result |= observer.needIndexRecoveryAccounting();
    }
    return result;
  }

  @Override
  public void setIndexRecoveryAccountingMap(THashMap map) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.setIndexRecoveryAccountingMap(map);
    }
  }

  @Override
  public void beforeQueryReprepare(GenericPreparedStatement gpst,
      LanguageConnectionContext lcc) throws StandardException {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeQueryReprepare(gpst, lcc);
    }
  }

  @Override
  public boolean throwPutAllPartialException() {
    boolean result = false;
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      result |= observer.throwPutAllPartialException();
    }
    return result;
  }

  @Override
  public void afterIndexRowRequalification(Boolean success,
      CompactCompositeIndexKey ccKey, ExecRow row, Activation activation) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterIndexRowRequalification(success, ccKey, row, activation);
    }
  }

  @Override
  public void beforeRowTrigger(LanguageConnectionContext lcc, ExecRow execRow,
      ExecRow newRow) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeRowTrigger(lcc, execRow, newRow);
    }
  }

  @Override
  public void afterRowTrigger(TriggerDescriptor trigD,
      GenericParameterValueSet gpvs) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterRowTrigger(trigD, gpvs);
    }
  }

  @Override
  public void beforeGlobalIndexDelete() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeGlobalIndexDelete();
    }
  }
  
  @Override
  public void onSortResultSetOpen(ResultSet resultSet)  {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.onSortResultSetOpen(resultSet);
    }
  }
  
  
  @Override
  public void onGroupedAggregateResultSetOpen(ResultSet resultSet) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.onGroupedAggregateResultSetOpen(resultSet);
    }
  }
  
  @Override
  public void onUpdateResultSetOpen(ResultSet resultSet)  {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.onUpdateResultSetOpen(resultSet);
    }
  }
  
  @Override
  public void onUpdateResultSetDoneUpdate(ResultSet resultSet)  {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.onUpdateResultSetDoneUpdate(resultSet);
    }
  }
  
  @Override
  public void beforeDeferredUpdate()  {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeDeferredUpdate();
    }
  }
  
  @Override
  public void beforeDeferredDelete()  {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeDeferredDelete();
    }
  }

  @Override
  public void bucketIdcalculated(int bid) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.bucketIdcalculated(bid);
    }
  }

  @Override
  public void beforeReturningCachedVal(Serializable globalIndexKey,
      Object cachedVal) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.beforeReturningCachedVal(globalIndexKey, cachedVal);
    }
  }

  @Override
  public void afterPuttingInCached(Serializable globalIndexKey, Object result) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterPuttingInCached(globalIndexKey, result);
    }
  }

  @Override
  public void afterSingleRowInsert(Object routingObj) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterSingleRowInsert(routingObj);
    }
  }
  
  @Override
  public void afterLockingTableDuringImport() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterLockingTableDuringImport();
    }
  }

  @Override
  public boolean testIndexRecreate() {
    return false;
  }

  @Override
  public void afterQueryPlanGeneration() {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.afterQueryPlanGeneration();
    }
  }

  @Override
  public void testExecutionEngineDecision(QueryInfo queryInfo, ExecutionEngineRule.ExecutionEngine engine, String queryText) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.testExecutionEngineDecision(queryInfo, engine, queryText);
    }
  }

  @Override
  public void regionPreInitialized(GemFireContainer container) {
    final GemFireXDQueryObserver[] observers = this.observerCollection;
    for (GemFireXDQueryObserver observer : observers) {
      observer.regionPreInitialized(container);
    }
  }
}
