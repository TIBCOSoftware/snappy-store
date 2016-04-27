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

package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.MembershipManager;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor;
import com.gemstone.gemfire.internal.cache.DistributedRegion;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.AckResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.StatementCloseExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdQueryResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdQueryStreamingResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.PrepStatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.DMLQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

/**
 * The Activation object which distributes the query to the member nodes and
 * keeps track of those nodes which have seen a prepared statement before *
 * 
 * @author Asif
 */
public abstract class AbstractGemFireDistributionActivation extends
    AbstractGemFireActivation {

  // TODO:Asif: Write the code to update the nodes when a node
  // crashes/disappears.
  // [sumedh] GfxdDistributionAdvisor can listen to membership events so can use
  // that to clean this list. However, then we may need a global map like before
  // for easy cleanup instead of iterating through all the activation objects.
  // Instead do an explicit cleanup looping through the set consulting
  // GfxdDistributionAdvisor when its size becomes more than some limit.
  protected final Set<DistributedMember> prepStmntAwareMembers;

  protected final boolean orderedReplies;

  protected final MembershipManager membershipManager;

  protected volatile long lastUpdatedViewId;

  // TODO:Asif: Find a better way of caching the old nodes
  protected THashSet staticRoutingKeys;

  // statistics data
  // ----------------
  protected THashSet routingKeysToExecute;
  protected long routingComputeTime;
  
  protected boolean isVTIInvolved;

  protected short distributionLevel = 1;
  protected GfxdFunctionMessage<Object> functionMsg;

  @SuppressWarnings("unchecked")
  public AbstractGemFireDistributionActivation(ExecPreparedStatement st,
      LanguageConnectionContext _lcc, DMLQueryInfo qi) throws StandardException {
    super(st, _lcc, qi);
    this.prepStmntAwareMembers = new THashSet();
    this.orderedReplies = (qi.getQueryFlag() & (QueryInfo.HAS_DISTINCT
        | QueryInfo.HAS_DISTINCT_SCAN | QueryInfo.HAS_ORDERBY)) != 0
        || ((qi.getQueryFlag() & QueryInfo.HAS_GROUPBY) != 0 && ((SelectQueryInfo)qi)
            .getGroupByQI().doReGrouping());
    this.connectionID = _lcc.getConnectionId();
    this.membershipManager = Misc.getDistributedSystem().getDM()
        .getMembershipManager();
    this.lastUpdatedViewId = membershipManager != null ? membershipManager
        .getViewId() : -1;
  }

  @SuppressWarnings("unchecked")
  @Override
  public final void setupActivation(ExecPreparedStatement ps,
      boolean scrollable, String stmt_text) throws StandardException {
    super.setupActivation(ps, scrollable, stmt_text);
    //[sb] #42482 skip anything while unlinking of activation during
    // rePrepare. see GenericAcitvationHolder#execute()
    // ac.setupActivation(null, false, null);
    if (ps == null) {
      return;
    }

    // If the query is static, compute nodes once & store it
    if ((isVTIInvolved = this.qInfo.isTableVTI())) {
      this.staticRoutingKeys = new THashSet();
      this.staticRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);
    }
    else if (!this.qInfo.isDynamic()) {
      this.staticRoutingKeys = new THashSet();
      this.staticRoutingKeys.add(ResolverUtils.TOK_ALL_NODES);

      computeNodesForStaticRoutingKeys(this.staticRoutingKeys);
    }
    else {
      this.staticRoutingKeys = null;
    }
    //explicitly call after setup callabck
    if (observer != null) {
      observer.afterGemFireActivationCreate(this);
    }
  }

  protected void computeNodesForStaticRoutingKeys(
      final Set<Object> staticRoutingKeys) throws StandardException {
    // Convert routing keys into nodes
    final DataPolicy dataPolicy = this.qInfo.getRegion().getDataPolicy();
    if (dataPolicy.withPartitioning()) {
      this.qInfo.computeNodes(staticRoutingKeys, this, false);
    }
    else if (!dataPolicy.withStorage()) {
      /*
      // move this code to computeNode in InsertQueryInfo.computeNode.
      assert staticRoutingKeys.size() == 1;
      assert staticRoutingKeys.contains(ResolverUtils.TOK_ALL_NODES);
      staticRoutingKeys.clear();
      this.staticNodestoExecute
          .addAll((Set<DistributedMember>)((DistributedRegion)rgn)
              .getCacheDistributionAdvisor().adviseNewReplicates(
                  staticRoutingKeys));*/
    }
    else {
      throw new UnsupportedOperationException(
          "Region handling unknown for region=" + this.qInfo.getRegion());
    }
  }

  @Override
  void invokeAfterSetupCallback() {
  }

  @SuppressWarnings("unchecked")
  @Override
  protected void executeWithResultSet(AbstractGemFireResultSet rs)
      throws StandardException {
    // Currently the code assumes a single region query.
    // Later the QueryInfo will provide some API to get the
    // handles of all the Regions involved in the query etc.
    // Also we will utilise the computeNodes method of QueryInfo object
    // to get the nodes on which we need to distribute the query.

    //Neeraj: This flag is being added for the outer join case where 
    // when the left table is a replicate table and the right table is 
    // a PR then there are chances of getting incorrect results. The
    // key obtained for each row from all nodes will help identify
    // merge results so that correct results can be obtained.
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "AbstractGemFireDistributionActivation#executeWithResultSet entered");
    }
    boolean needKeysForRows = false;
    final boolean isOuterJoin = this.qInfo.isOuterJoin();
    LocalRegion opRgn;
    if (isVTIInvolved) {
      opRgn = Misc.getMemStore().getDDLQueueNoThrow().getRegion();
    }
    else {
      opRgn = this.qInfo.getRegion();
    }
    PartitionedRegion preg = null;
    List<?> outerTablesList = null;
    if (opRgn.getPartitionAttributes() != null) {
      preg = (PartitionedRegion)opRgn;
      if (isOuterJoin) {
        outerTablesList = this.qInfo.getOuterJoinRegions();
        final Iterator<?> itr = outerTablesList.iterator();
        if (GemFireXDUtils.TraceOuterJoin) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
              "AbstractGemfireDistributionActivation::executeWithResultSet"
                  + "opRgnName: " + opRgn.getName()
                  + " outerTablesList's size: " + outerTablesList.size());
        }
        //while(itr.hasNext()) {
        if (itr.hasNext()) {
          final LocalRegion r = (LocalRegion)itr.next();
          if (GemFireXDUtils.TraceOuterJoin) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                "AbstractGemfireDistributionActivation::executeWithResultSet"
                    + "opRgnName: " + opRgn.getName()
                    + " left region name is " + r);
          }
          if (r.getPartitionAttributes() == null) {
            needKeysForRows = true;
            if (GemFireXDUtils.TraceOuterJoin) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
                  "AbstractGemfireDistributionActivation::executeWithResultSet"
                      + "opRgnName: " + opRgn.getName()
                      + " left region number is " + r + " so set need keys for rows to true");
            }
            this.qInfo.setOuterJoinSpecialCase();
            // in case the PR has no data, use this region to drive the
            // function routing else function may go nowhere
            if (!preg.getRegionAdvisor().hasCreatedBuckets()) {
              opRgn = r;
              preg = null;
            }
            // break;
          }
        }
      }
    }
    if (GemFireXDUtils.TraceOuterJoin) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
          "AbstractGemfireDistributionActivation::executeWithResultSet"
              + "opRgnName: " + opRgn.getName() + " isouterjoin: "
              + isOuterJoin + " needKeysForRows: " + needKeysForRows
              + " and outerTablesList: " + outerTablesList);
    }
    final LanguageConnectionContext lcc = getLanguageConnectionContext();
    final String defaultSchema = lcc.getDefaultSchema().getSchemaName();
    final boolean isPossibleDuplicate = lcc.isPossibleDuplicate();
    GfxdResultCollector<Object>  rc = null;
    try {
      if (observer != null) {
        observer.beforeGemFireResultSetExecuteOnActivation(this);
      }

      final boolean enableStreaming = enableStreaming(lcc);
      Object ls = null;
      try {
        if (preg != null) {
          long newrootID = this.rootID;
          int newstmtLevel = this.statementLevel;
          if (newstmtLevel == 0) {
            // Increase statement level before distribution
            newstmtLevel = 1;
            if (this.rootID == 0) {
              newrootID = this.statementID;
            }
          }
          if (this.isPrepStmntQuery) {
            if (observer != null) {
              observer.beforeComputeRoutingObjects(this);
            }
            // Find the routing keys which need to execute this query.
            routingKeysToExecute = null;
            if (!isOuterJoin) {
              routingKeysToExecute = new THashSet();
              if (this.qInfo.isDynamic()) {
                computeNodesForDynamicRoutingKeys(routingKeysToExecute);
              }
              else {
                // No need to check for null as it is already taken care of in
                // the init
                routingKeysToExecute.addAll(this.staticRoutingKeys);
              }
            }
            if (observer != null) {
              observer.afterComputeRoutingObjects(this);
              observer.setRoutingObjectsBeforeExecution(this.routingKeysToExecute);
            }
            // TODO:ASIF: FOR Correct caching, it is necessary that
            // TOK_ALL_NODES gets converted into routing keys representing each
            // member. For now it is Ok as we are not caching anything in
            // PrepStatementID cache.
            if (isOuterJoin || !routingKeysToExecute.isEmpty()) {
              if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                    "AbstractGemFireDistributionActivation::execute: "
                        + "Prep Statement being excecuted has conn ID = "
                        + this.connectionID + " statement ID = "
                        + this.statementID + " root ID = " + this.rootID
                        + " new-root ID = " + newrootID
                        + " orig-statementLevel = " + this.statementLevel
                        + " new-statementLevel = " + newstmtLevel);
              }
              if (routingKeysToExecute != null
                  && routingKeysToExecute
                      .contains(ResolverUtils.TOK_ALL_NODES)) {
                routingKeysToExecute = null;
              }
              rc = getResultCollector(enableStreaming, rs);
              // Add the nodes to which this prep statement has been sent to
              // into the aware set.
              // Members which have seen the source will be not null only if the
              // current connection's ID is !=
              // EmbedConnction.CHILD_NOT_CACHEABLE
              if (this.connectionID != EmbedConnection.CHILD_NOT_CACHEABLE) {
                rc.setResultMembers(this.prepStmntAwareMembers);
              }
              if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                if (!this.prepStmntAwareMembers.isEmpty()) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                      "AbstractGemFireDistributionActivation::execute: "
                          + "Prepared statement aware members: "
                          + this.prepStmntAwareMembers
                          + ". Query will be sent with query string");
                }
                else {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                      "AbstractGemFireDistributionActivation::execute: "
                          + "No prepared statement aware members. "
                          + "Query will be sent with query string");
                }
              }
              throwIfMissingParms();
              final PrepStatementExecutorMessage<Object> prepMsg;
              boolean abortOnLowMemory = GfxdHeapThresholdListener.isCancellableQuery(this);
              functionMsg = prepMsg = new PrepStatementExecutorMessage<Object>(
                  rc, defaultSchema, this.connectionID, this.statementID,
                  this.executionID, newrootID, newstmtLevel,
                  this.preStmt.getSource(), this.qInfo.isSelect(),
                  this.qInfo.optimizeForWrite(), this.qInfo.withSecondaries(),
                  needKeysForRows,
                  this.qInfo.isRemoteGfxdSubActivationNeeded(),
                  this.qInfo.isSubqueryFlatteningAllowed(),
                  (this.qInfo.isSelectForUpdateQuery() && this.qInfo
                      .needKeysForSelectForUpdate()), getParameterValueSet(),
                  preg, routingKeysToExecute, this.qInfo.isInsertAsSubSelect(),
                  lcc, this.getTimeOutMillis(), abortOnLowMemory);
              prepMsg.setPartitionRegions(this.qInfo.getOtherRegions());
              // Handle Union, Intersect and Except
              if (this.qInfo.hasUnionNode()
                  || this.qInfo.hasIntersectOrExceptNode()) {
                this.qInfo.verifySetOperationSupported(); // sanity check
                if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                  SanityManager.DEBUG_PRINT(
                      GfxdConstants.TRACE_QUERYDISTRIB,
                      "AbstractGemFireDistributionActivation::execute: Handle "
                          + "Union, Intersect or Except " + " for union ="
                          + this.qInfo.hasUnionNode()
                          + " and intersect or except = "
                          + this.qInfo.hasIntersectOrExceptNode());
                }
                prepMsg.setHasSetOperatorNode(true,
                    this.qInfo.hasIntersectOrExceptNode());
              }
              if (this.qInfo.isNCJoinOnQN()) {
                if (SanityManager.DEBUG) {
                  if (GemFireXDUtils.TraceNCJ) {
                    SanityManager.DEBUG_PRINT(
                        GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                        "AbstractGemFireDistributionActivation::execute: "
                            + "Set Flag for Non Collocated Join ");
                  }
                }
                prepMsg.setNCJoinOnQN(this.qInfo.getNCJMetaData(), lcc);
              }
              ls = functionMsg.executeFunction(enableStreaming,
                  isPossibleDuplicate, rs, this.orderedReplies);
            }
          }
          else {
            if (isOuterJoin
                || (this.staticRoutingKeys != null && !this.staticRoutingKeys
                    .isEmpty()) || this.qInfo.isDynamic()) {
              routingKeysToExecute = null;
              if(this.qInfo.isDynamic()) {
                routingKeysToExecute = new THashSet();
                this.computeNodesForDynamicRoutingKeys(routingKeysToExecute);
              }else {
                this.routingKeysToExecute = this.staticRoutingKeys; 
              }
              if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                    "AbstractGemFireDistributionActivation::execute: Nodes to "
                        + "execute query using statement  = "
                        + (isOuterJoin ? ResolverUtils.TOK_ALL_NODES
                            : this.routingKeysToExecute));
              }
              
              
              if (isOuterJoin
                  || routingKeysToExecute.contains(ResolverUtils.TOK_ALL_NODES)) {
                routingKeysToExecute = null;
              }
              rc = getResultCollector(enableStreaming, rs);
              boolean abortOnLowMemory = GfxdHeapThresholdListener.isCancellableQuery(this);
              final StatementExecutorMessage<Object> unprepMsg;
              functionMsg = unprepMsg = new StatementExecutorMessage<Object>(
                  rc, defaultSchema, this.connectionID, this.statementID,
                  this.executionID, newrootID, newstmtLevel,
                  this.preStmt.getSource(), this.qInfo.isSelect(),
                  this.qInfo.optimizeForWrite(), this.qInfo.withSecondaries(),
                  needKeysForRows,
                  this.qInfo.isRemoteGfxdSubActivationNeeded(),
                  this.qInfo.isSubqueryFlatteningAllowed(),
                  (this.qInfo.isSelectForUpdateQuery() && this.qInfo
                      .needKeysForSelectForUpdate()), this.pvs, preg,
                  routingKeysToExecute /* set of routing objects */,
                  this.qInfo.isInsertAsSubSelect(), lcc,
                  this.getTimeOutMillis(), abortOnLowMemory);
              unprepMsg.setPartitionRegions(this.qInfo.getOtherRegions());
              // Handle Union, Intersect and Except
              if (this.qInfo.hasUnionNode()
                  || this.qInfo.hasIntersectOrExceptNode()) {
                this.qInfo.verifySetOperationSupported(); // sanity check
                if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
                      "AbstractGemFireDistributionActivation::execute: Handle "
                          + "Union, Intersect or Except ");
                }
                unprepMsg.setHasSetOperatorNode(true,
                    this.qInfo.hasIntersectOrExceptNode());
              }
              if (this.qInfo.isNCJoinOnQN()) {
                if (SanityManager.DEBUG) {
                  if (GemFireXDUtils.TraceNCJ | GemFireXDUtils.TraceNCJ) {
                    SanityManager.DEBUG_PRINT(
                        GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
                        "AbstractGemFireDistributionActivation::execute: "
                            + "Set Flag for Non Collocated Join ");
                  }
                }
                unprepMsg.setNCJoinOnQN(this.qInfo.getNCJMetaData(), lcc);
              }
              ls = functionMsg.executeFunction(enableStreaming,
                  isPossibleDuplicate, rs, this.orderedReplies);
            }
          }
        }
        else {
          // operate on distributed or replicated or Empty regions.
          rc = getResultCollector(enableStreaming, rs);
          ls = operateOnNonPartitionedRegion(opRgn, rs, rc, defaultSchema,
              needKeysForRows, enableStreaming, lcc);
        }
      } catch (EmptyRegionFunctionException erfe) {
        // Fix for Bug #40626
        ls = null;
      } catch (GemFireException gfeex) {
        throw Misc.processGemFireException(gfeex, gfeex, "execution of "
            + this.preStmt.getSource(), true);
      }
      if (ls == null) {
        rs.setup(null, 0);
      }
      if (observer != null) {
        observer.afterGemFireResultSetExecuteOnActivation(this);
      }
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        if (ls instanceof Collection<?>) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "AbstractGemFireDistributionActivation#execute: streaming="
                  + enableStreaming + " results list size="
                  + ((Collection<?>)ls).size());
        }
        else {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
              "AbstractGemFireDistributionActivation#execute: streaming="
                  + enableStreaming + " result: " + ls);
        }
      }
    } catch (StandardException se) {
      throw se;
    } catch (Exception e) {
      throw Misc.processFunctionException(
          "AbstractGemFireDistribution::execute", e, null, opRgn);
    } finally {
      cleanupPrepStatementAwareMemberList();
    }
  }

  private void computeNodesForDynamicRoutingKeys(THashSet routingKeysToExecute)
      throws StandardException {
    routingKeysToExecute.add(ResolverUtils.TOK_ALL_NODES);
    this.qInfo.computeNodes(routingKeysToExecute, this, false);
  }

  private void cleanupPrepStatementAwareMemberList() {
    // if there has been a view change then cleanup prepStmntAwareMembers
    final long lastViewId = this.lastUpdatedViewId;
    final long viewId;
    if (this.membershipManager != null
        && (viewId = this.membershipManager.getViewId()) != lastViewId) {
      final Set<DistributedMember> members = this.prepStmntAwareMembers;
      synchronized (members) {
        // TODO KN: check if instead of passing null we can just pass the
        // server groups of the tables to which this prep statement goes
        Set<DistributedMember> liveMembers = GemFireXDUtils.getGfxdAdvisor()
            .adviseDataStores(null);
        if (liveMembers != null) {
          members.retainAll(liveMembers);
        }
        else {
          // This really should not happen.
          members.clear();
        }
        this.lastUpdatedViewId = viewId;
      }
    }
  }

  protected abstract boolean enableStreaming(LanguageConnectionContext lcc);

  protected GfxdResultCollector<Object> getResultCollector(
      final boolean enableStreaming, final AbstractGemFireResultSet rs)
      throws StandardException {
    final GfxdResultCollector<Object> rc;
    if (enableStreaming) {
      rc = new GfxdQueryStreamingResultCollector();
    }
    else {
      rc = new GfxdQueryResultCollector();
    }
    rs.setupRC(rc);
    return rc;
  }

  /**
   * operate a function on non partitioned region.
   * @throws SQLException 
   * @throws StandardException 
   * 
   * @throws Exception
   */
  protected final Object operateOnNonPartitionedRegion(
      final LocalRegion rgn, final AbstractGemFireResultSet rs,
      final ResultCollector<Object, Object> rc,
      final String defaultSchema, final boolean isSpecialCaseOuterJoin,
      final boolean enableStreaming, LanguageConnectionContext lcc)
      throws StandardException, SQLException {
    final boolean isPossibleDuplicate = lcc.isPossibleDuplicate();
    final boolean isForSelectForUpdateQuery = this.qInfo
        .isSelectForUpdateQuery();
    final Object result;
    long newrootID = this.rootID;
    int newstmtLevel = this.statementLevel;
    if (newstmtLevel == 0) {
      // Increase statement level before distribution
      newstmtLevel = 1;
      if (this.rootID == 0) {
       newrootID = this.statementID; 
      }
    }
    if (this.isPrepStmntQuery) {
      final StatementStats stats = lcc.getActiveStats();
      long begin = -1;

      if (stats != null) {
        begin = stats.getStatTime();
      }

      throwIfMissingParms();
      boolean abortOnLowMemory = GfxdHeapThresholdListener.isCancellableQuery(this);
      functionMsg = new PrepStatementExecutorMessage<Object>(
          rc,
          defaultSchema,
          this.connectionID,
          this.statementID,
          this.executionID,
          newrootID,
          newstmtLevel,
          this.preStmt.getSource(),
          this.qInfo.isSelect(),
          this.qInfo.optimizeForWrite(),
          this.qInfo.withSecondaries(),
          isSpecialCaseOuterJoin,
          this.qInfo.isRemoteGfxdSubActivationNeeded(),
          this.qInfo.isSubqueryFlatteningAllowed(),
          (isForSelectForUpdateQuery && this.qInfo.needKeysForSelectForUpdate()),
          this.getParameterValueSet(), rgn, null, this.qInfo
              .isInsertAsSubSelect(), lcc, this.getTimeOutMillis(), abortOnLowMemory);
      setAllAreNonPartitionedRegion(rs, isVTIInvolved);
      if (isVTIInvolved) {
        functionMsg.setSendToAllReplicates(includeAdminForVTI());
      }

      // Handle Union, Intersect and Except
      if (this.qInfo.hasUnionNode() || this.qInfo.hasIntersectOrExceptNode()) {
        this.qInfo.verifySetOperationSupported(); // sanity check
        if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "AbstractGemFireDistributionActivation::operateOnNonPartitionedRegion: " +
          "Handle Union, Intersect or Except ");
        }
        ((RegionExecutorMessage<Object>)this.functionMsg)
            .setHasSetOperatorNode(true, this.qInfo.hasIntersectOrExceptNode());
      }
      result = functionMsg.executeFunction(enableStreaming,
          isPossibleDuplicate, rs, this.orderedReplies);
      if (stats != null) {
        assert begin != -1;
        stats.incRemoteExecutionTime(begin);
        stats.incNumRemoteExecution();
      }
    }
    else {
      boolean abortOnLowMemory = GfxdHeapThresholdListener.isCancellableQuery(this);
      functionMsg = new StatementExecutorMessage<Object>(
          rc,
          defaultSchema,
          this.connectionID,
          this.statementID,
          this.executionID,
          newrootID,
          newstmtLevel,
          this.preStmt.getSource(),
          this.qInfo.isSelect(),
          this.qInfo.optimizeForWrite(),
          this.qInfo.withSecondaries(),
          isSpecialCaseOuterJoin,
          this.qInfo.isRemoteGfxdSubActivationNeeded(),
          this.qInfo.isSubqueryFlatteningAllowed(),
          (isForSelectForUpdateQuery && this.qInfo.needKeysForSelectForUpdate()),
          this.pvs, rgn, null, this.qInfo.isInsertAsSubSelect(), lcc, this
              .getTimeOutMillis(), abortOnLowMemory);
      setAllAreNonPartitionedRegion(rs, isVTIInvolved);
      if (isVTIInvolved) {
        functionMsg.setSendToAllReplicates(includeAdminForVTI());
      }
      
      // Handle Union, Intersect and Except
      if (this.qInfo.hasUnionNode() || this.qInfo.hasIntersectOrExceptNode()) {
        this.qInfo.verifySetOperationSupported(); // sanity check
        if (GemFireXDUtils.TraceQuery) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "AbstractGemFireDistributionActivation::operateOnNonPartitionedRegion: " +
          "Handle Union, Intersect or Except ");
        }
        ((RegionExecutorMessage<Object>)this.functionMsg)
            .setHasSetOperatorNode(true, this.qInfo.hasIntersectOrExceptNode());
      }
      result = functionMsg.executeFunction(enableStreaming,
          isPossibleDuplicate, rs, this.orderedReplies);
    }

    return result;
  }

  private boolean includeAdminForVTI() {
    // check if there is a driver table which is a regular table then don't
    // route to locator/agent/manager JVMs (#46036)
    if (this.qInfo.isDriverTableInitialized()) {
      LocalRegion r = this.qInfo.getRegion();
      if (r != null && !r.getScope().isLocal()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Handle case if all Tables are Replicated? If operateOnNonPartitionedRegion
   * is called, means driver table is replicated. If so, then all tables must be
   * replicated.
   * 
   * @param rs
   *          resultset
   * @param isVTIInvolved
   *          - If true, Ignore this flag/method
   */
  private void setAllAreNonPartitionedRegion(final AbstractGemFireResultSet rs,
      boolean isVTIInvolved) {
    boolean isValidCase = !isVTIInvolved;
    if (SanityManager.DEBUG
        && (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ)) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "AbstractGemFireDistributionActivation::setAllAreNonPartitionedRegion: "
              + "Setting flag AllTablesReplicatedOnRemote to " + isValidCase
              + " isVTIInvolved=" + isVTIInvolved);
    }

    ((RegionExecutorMessage<Object>)this.functionMsg)
        .setAllTablesAreReplicatedOnRemote(isValidCase);
    rs.setAllTablesReplicatedOnRemote(isValidCase);
  }

  @Override
  public final void setIsPrepStmntQuery(boolean flag) {
    this.isPrepStmntQuery = flag;
  }

  @Override
  public final boolean getIsPrepStmntQuery() {
    return this.isPrepStmntQuery;
  }

  /*
  public static void cleanUp() {
    prepStmntIDToNodes.clear();
    // prepStmntIDToNodes = null;
  }*/

  @SuppressWarnings({ "unchecked", "rawtypes" })
  @Override
  final public void distributeClose() {
    // TODO:Asif: Till we start caching routing keys / distributed member IDs
    // etc distribute to all nodes.
    // nothing to be done for uncached connections
    if (this.connectionID == EmbedConnection.UNINITIALIZED
        || this.connectionID == EmbedConnection.CHILD_NOT_CACHEABLE) {
      return;
    }
    try {
      final LocalRegion rgn;
      if (isVTIInvolved) {
        rgn = Misc.getMemStore().getDDLQueueNoThrow().getRegion();
      }
      else {
        rgn = this.qInfo.getRegion();
      }
      final DataPolicy dp = rgn.getDataPolicy();
      Set<InternalDistributedMember> members;
      final DM dm;
      if (dp.withPartitioning()) {
        // we want statement close to go to all members having the region
        final RegionAdvisor ra = ((PartitionedRegion)rgn).getRegionAdvisor();
        members = ra.adviseGeneric();
        dm = ra.getDistributionManager();
      }
      else {
        // We are here this itself implies that the local VM is having a
        // replicated region with Data Policy Empty
        final CacheDistributionAdvisor cda = ((DistributedRegion)rgn)
            .getCacheDistributionAdvisor();
        members = cda.adviseReplicates();
        dm = cda.getDistributionManager();
      }
      if (members.isEmpty()) {
        members = Collections.singleton(dm.getDistributionManagerId());
      }
      else {
        members.add(dm.getDistributionManagerId());
      }
      StatementCloseExecutorMessage msg = new StatementCloseExecutorMessage(
          AckResultCollector.INSTANCE, (Set)members, this.connectionID,
          this.statementID);
      final TXStateInterface tx = TXManagerImpl.getCurrentTXState();
      msg.executeFunction(false, false, null, false, tx == null);
      // for transactions wait add to pending RC list so that TX can wait
      // for results in commit phase1
      if (tx != null) {
        tx.getProxy().addPendingResultCollector(msg.getReplyProcessor());
      }
    } catch (RegionDestroyedException rde) {
      // in case region has been destroyed then no need of a warning message
      if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "AbstractGemfireDistributionActivation::distributeClose: "
            + "RegionDestroyedException in distributing Statement.close on "
            + "remote nodes for table=" + this.qInfo.getFullTableName(), rde);
      }
    } catch (EmptyRegionFunctionException erfe) {
      // Fix for Bug #40626; ignore when no member to execute
    } catch (Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      SystemFailure.checkFailure();

      if (SanityManager.isFinerEnabled) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
            "AbstractGemfireDistributionActivation::distributeClose: "
                + "Exception in distributing Statement.close on remote nodes "
                + "for table=" + this.qInfo.getFullTableName(), t);
      }
      // also ignore exceptions from a node going down
      if (!GemFireXDUtils.retryToBeDone(t)) {
        SanityManager.DEBUG_PRINT("TRACE",
            "AbstractGemfireDistributionActivation::distributeClose: "
                + "Exception in distributing Statement.close on remote nodes "
                + "for table=" + this.qInfo.getFullTableName(), t);
      }
    }
  }
}
