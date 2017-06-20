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

package com.pivotal.gemfirexd.internal.engine.ddl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Vector;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EvictionCriteria;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap.SimpleReusableEntry;
import com.gemstone.gemfire.internal.offheap.Releasable;
import com.pivotal.gemfirexd.internal.catalog.types.RoutineAliasInfo;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.expression.ExpressionCompiler;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.CursorResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.SQLBoolean;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SubqueryList;
import com.pivotal.gemfirexd.internal.impl.sql.compile.ValueNode;

/**
 * Implementation of EVICTION BY CRITERIA clause for GemFireXD.
 *
 * @author swale
 * @since gfxd 1.0
 */
public class GfxdEvictionCriteria implements EvictionCriteria<Object, Object> {

  private final ExpressionCompiler predicateCompiler;
  private final String predicateString;
  private PreparedStatement queryStatement;
  private Observer evictionObserver;

  public GfxdEvictionCriteria(ValueNode predicate, String sqlText,
      int beginOffset, int endOffset) {
    this.predicateCompiler = new ExpressionCompiler(predicate,
        new HashMap<String, Integer>(), "EVICTION BY CRITERIA");
    this.predicateString = sqlText.substring(beginOffset, endOffset);
  }

  public void setObserver(Observer observer) {
    this.evictionObserver = observer;
  }

  public final String getPredicateString() {
    return this.predicateString;
  }

  /**
   * Bind this expression. This means binding the sub-expressions, as well as
   * checking that the return type for this expression is a boolean. This should
   * be invoked at the time when the rest of CREATE TABLE is being bound.
   *
   * @param fromList
   *          The FROM list for the query this expression is in, for binding
   *          columns.
   * @param lcc
   *          The current {@link LanguageConnectionContext} being used for the
   *          bind operation.
   *
   * @exception StandardException
   *              Thrown on error
   */
  public void bindExpression(FromList fromList, LanguageConnectionContext lcc)
      throws StandardException {

    // Check for no aggregates in EVICTION BY CRITERIA
    Vector<?> aggregates = new Vector<>();
    SubqueryList subqueries = (SubqueryList)lcc.getLanguageConnectionFactory()
        .getNodeFactory()
        .getNode(C_NodeTypes.SUBQUERY_LIST, lcc.getContextManager());
    final String[] exprCols = this.predicateCompiler.bindExpression(fromList,
        subqueries, aggregates);
    if (exprCols == null || exprCols.length == 0) {
      throw StandardException
          .newException(SQLState.LANG_TABLE_REQUIRES_COLUMN_NAMES);
    }
    if (aggregates.size() > 0) {
      throw StandardException
          .newException(SQLState.LANG_NO_AGGREGATES_IN_WHERE_CLAUSE);
    }
    // TODO: HDFS: should subqueries (EXISTS) be allowed? if we should allow 
    // then expression compiler will need to be generalized to handle that
    if (subqueries.size() > 0) {
      throw StandardException.newException(
          SQLState.LANG_NO_SUBQUERIES_IN_WHERE_CLAUSE, "EVICTION BY CRITERIA");
    }
    this.predicateCompiler.normalizeExpression(true);
  }

  public void initialize(GemFireContainer container,
      LanguageConnectionContext lcc) throws StandardException {
    // create the SELECT query to be used for getting the keys to be evicted
    this.queryStatement = lcc.prepareInternalStatement("SELECT * FROM "
        + container.getQualifiedTableName() + " WHERE " + this.predicateString,
        (short)0);
    // also prepare the activation for the predicate portion separately for
    // doEvict and EVICT INCOMING cases
    this.predicateCompiler.compileExpression(container.getTableDescriptor(),
        lcc);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Iterator<Map.Entry<Object, Object>> getKeysToBeEvicted(
      long currentMillis, Region<Object, Object> region) {
    return new Itr(currentMillis, region);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean doEvict(EntryEvent<Object, Object> event) {
    final EntryEventImpl ev = (EntryEventImpl)event;
    final RegionEntry re = ev.getRegionEntry();
    LocalRegion region = ev.getRegion();
    CachePerfStats stats;
    if (region instanceof BucketRegion) {
      stats = region.getPartitionedRegion().getCachePerfStats();
    } else {
      stats = region.getCachePerfStats();
    }
    long startTime = stats.startEvaluation();
    try {
      if (region.getLogWriterI18n().fineEnabled()) {
        region.getLogWriterI18n().fine(
            " The entry is " + re + " and the event is " + event
                + " re marked for eviction " + re.isMarkedForEviction());
      }

      if (re != null) {
        if (ev.getTXState() == null && re.hasAnyLock()) {
          return false;
        }
        if (re.isMarkedForEviction()) {
          return true;
        }
        else {
          final Observer observer = this.evictionObserver;
          if (observer != null) {
            observer.onDoEvictCompare(ev);
          }
          final RowLocation rl = (RowLocation)re;
          final GemFireContainer container = (GemFireContainer)ev.getRegion()
              .getUserAttribute();
          EmbedConnection conn = null;
          boolean contextSet = false;
          LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
          try {
            if (lcc == null) {
              // Refer Bug 42810.In case of WAN, a PK based insert is converted
              // into
              // region.put since it bypasses GemFireXD layer, the LCC can be
              // null.
              conn = GemFireXDUtils.getTSSConnection(true, true, false);
              conn.getTR().setupContextStack();
              contextSet = true;
              lcc = conn.getLanguageConnectionContext();
              // lcc can be null if the node has started to go down.
              if (lcc == null) {
                Misc.getGemFireCache().getCancelCriterion()
                    .checkCancelInProgress(null);
              }
            }
            DataValueDescriptor res = this.predicateCompiler
                .evaluateExpression(ev.getKey(), rl, container, lcc);
            if (res != null) {
              assert res instanceof SQLBoolean: "unexpected DVD type="
                  + res.getClass() + ": " + res.toString();
              return ((SQLBoolean)res).getBoolean();
            }
          }
          catch (StandardException se) {
            // skip eviction for an exception
          }
          finally {
            if (contextSet) {
              conn.getTR().restoreContextStack();
            }

          }
        }
      }
      return false;
    }
    finally {
      if (region.getLogWriterI18n().fineEnabled()) {
        region.getLogWriterI18n().fine("Getting called finally");
      }
      stats.incEvaluations();
      stats.endEvaluation(startTime, 0);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isEquivalent(EvictionCriteria<Object, Object> other) {
    if (other instanceof GfxdEvictionCriteria) {
      GfxdEvictionCriteria otherCriteria = (GfxdEvictionCriteria)other;
      return this.predicateString.equals(otherCriteria.predicateString);
    }
    else {
      return false;
    }
  }

  /**
   * An observer for eviction by criteria.
   */
  public interface Observer {

    void keyReturnedForEviction(Object key, Object routingObject,
        long currentMillis, Region<Object, Object> region);

    void onIterationComplete(Region<Object, Object> region);

    void onDoEvictCompare(EntryEventImpl event);
  }

  private final class Itr implements Iterator<Map.Entry<Object, Object>>,
      Releasable {

    private final long currentMillis;
    private final GemFireContainer container;
    private final SimpleReusableEntry entry;
    private final EmbedConnection conn;
    private final boolean contextSet;
    private final LanguageConnectionContext lcc;
    private final boolean popLCCContext;
    private final StatementContext statementContext;
    private final CursorResultSet resultSet;
    private RowLocation currentRowLocation;

    private Itr(long currentTimeMillis, Region<Object, Object> region) {
      this.currentMillis = currentTimeMillis;
      this.container = (GemFireContainer)region.getUserAttribute();
      this.entry = new SimpleReusableEntry();
      this.currentRowLocation = null;

      // setup LCC and get the ResultSet
      LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
      EmbedConnection conn = null;
      StatementContext statementContext = null;
      boolean contextSet = false;
      boolean popContext = false;
      CursorResultSet resultSet = null;
      Throwable t = null;
      try {
        if (lcc == null) {
          // Refer Bug 42810.In case of WAN, a PK based insert is converted into
          // region.put since it bypasses GemFireXD layer, the LCC can be null.
          conn = GemFireXDUtils.getTSSConnection(true, true, false);
          conn.getTR().setupContextStack();
          contextSet = true;
          lcc = conn.getLanguageConnectionContext();
          // lcc can be null if the node has started to go down.
          if (lcc == null) {
            Misc.getGemFireCache().getCancelCriterion()
                .checkCancelInProgress(null);
          }
        }
        else {
          contextSet = false;
        }

        if (lcc != null) {
          lcc.pushMe();
          popContext = true;
          assert ContextService
              .getContextOrNull(LanguageConnectionContext.CONTEXT_ID) != null;
          statementContext = lcc.pushStatementContext(false, false,
              predicateString, null, false, 0L, true);
          statementContext.setSQLAllowed(RoutineAliasInfo.READS_SQL_DATA,
              true);

          resultSet = (CursorResultSet)queryStatement.execute(lcc, false, 0L);

          if (resultSet.getNextRow() != null) {
            this.currentRowLocation = resultSet.getRowLocation();
          }
        }
      } catch (Exception e) {
        t = e;
        // terminate the iteration immediately
        this.currentRowLocation = null;
        LogWriter logger = Misc.getCacheLogWriterNoThrow();
        if(logger != null) {
          logger.warning("GfxdEvictionCriteria: Error in Iterator creation", e);
        }
      } finally {
        if (this.currentRowLocation == null) {
          if (statementContext != null) {
            lcc.popStatementContext(statementContext, t);
          }
          if (lcc != null && popContext) {
            lcc.popMe();
          }
          if (contextSet) {
            conn.getTR().restoreContextStack();
          }
          final Observer observer = evictionObserver;
          if (observer != null) {
            observer.onIterationComplete(region);
          }
        }
      }
      this.conn = conn;
      this.contextSet = contextSet;
      this.lcc = lcc;
      this.popLCCContext = popContext;
      this.statementContext = statementContext;
      this.resultSet = resultSet;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
      return (this.currentRowLocation != null);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public Map.Entry<Object, Object> next() {
      final RowLocation rl = this.currentRowLocation;
      if (rl != null) {
        Object key = null;
        Object routingObject = null;
        Throwable t = null;
        this.currentRowLocation = null;
        try {
          key = rl.getKeyCopy();
          routingObject = rl.getBucketID();
          if(GemFireXDUtils.isOffHeapEnabled()) {
            ((NoPutResultSet)this.resultSet).releasePreviousByteSource();
          }
          // move to the next row
          if (this.resultSet.getNextRow() != null) {
            this.currentRowLocation = this.resultSet.getRowLocation();
          }
        } catch (Exception e) {
          // ignore and terminate iteration
          this.currentRowLocation = null;
          t = e;
          LogWriter logger = Misc.getCacheLogWriterNoThrow();
          if(logger != null) {
            logger.warning("GfxdEvictionCriteria::Itr:next: Error in iterator.next", e);
          }
        } finally {
          if (this.currentRowLocation == null) {
            if (this.statementContext != null) {
              this.lcc.popStatementContext(this.statementContext, t);
            }
            if (this.lcc != null && this.popLCCContext) {
              this.lcc.popMe();
            }
            if (this.contextSet) {
              this.conn.getTR().restoreContextStack();
            }
          }
        }
        final Observer observer = evictionObserver;
        if (observer != null) {
          LocalRegion region = this.container.getRegion();
          observer.keyReturnedForEviction(key, routingObject,
              this.currentMillis, region);
          if (this.currentRowLocation == null) {
            observer.onIterationComplete(region);
          }
        }
        this.entry.setReusableKey(key);
        this.entry.setReusableValue(routingObject);
        return this.entry;
      }
      else {
        throw new NoSuchElementException();
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void release() {
      if(this.lcc != null) {
        ((GemFireTransaction)this.lcc.getTransactionExecute()).
        release();
      }
    }
  }
}
