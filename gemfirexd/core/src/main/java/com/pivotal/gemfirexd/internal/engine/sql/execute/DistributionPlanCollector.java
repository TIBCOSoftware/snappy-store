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

import java.util.List;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.ResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.XPLAINDistPropsDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.execute.DistributionObserver.GlobalIndexStat;
import com.pivotal.gemfirexd.internal.engine.sql.execute.DistributionObserver.StatObjects;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.AbstractRSIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.GroupedIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.OrderedIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.RSIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.RoundRobinIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.RowCountIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.SequentialIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.SetOperatorIterator;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireDistributedResultSet.SpecialCaseOuterJoinIterator;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINResultSetDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINResultSetTimingsDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINScanPropsDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.XPLAINSortPropsDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ConstantActionActivation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.StatementPlanCollector;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Collect distribution plan which mostly resides in *DistributionActivation
 * classes <strong>locally</strong> and return XPLAIN* descriptors.
 * <p>
 * 
 * @author soubhikc
 * @see StatementPlanCollector
 * 
 */
public final class DistributionPlanCollector implements
    ActivationStatisticsVisitor, IteratorStatisticsVisitor {

  private static final long serialVersionUID = 1973771854223467210L;

  public DistributionPlanCollector(
      final StatementPlanCollector parentCollector,
      final List<XPLAINDistPropsDescriptor> dsets) {

    this.baseCollector = parentCollector;
  }

  private final StatementPlanCollector baseCollector;

  private boolean considerTimingInformation;

  private LanguageConnectionContext lcc;

  private BaseActivation ac;

  private DataDictionary dd;

  private XPLAINDistPropsDescriptor root_msg_descriptor;

  public void setup(
      BaseActivation activation) {
    this.ac = activation;
    this.lcc = ac.getLanguageConnectionContext();
    this.dd = lcc.getDataDictionary();

    // get the timings settings
    this.considerTimingInformation = lcc.getStatisticsTiming();
  }

  /**
   * must be called at the beginning of every <b>*</b>DistributionResultSet
   * processing.
   * 
   * @param rs
   */
  public void processDistribution(
      final ResultSet rs) {

    final BaseActivation ac = ((BaseActivation)rs.getActivation());

    // atleast a ResultSet will be there
    baseCollector.setNumberOfChildren(1);

    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            "DistributionPlanCollector: Processing activation " + ac);
      }
    }
    
    ac.accept(this);
  }

  /**
   * Query node processing GemFireDistributedResultSet.
   * 
   * @param rs
   */
  public void processGFDistResultSet(
      GemFireDistributedResultSet rs) {

    setup(rs.act);

    /*
     * This will create the activation hierarchy that gets executed before GFDistRS.
     */
    processDistribution(rs);

    /*
     * now, before GFDistRS, iterators gets created in the query plan tree.
     */
    if(rs.iterator != null) { //can be in case of Exceptions in executeFunction.
      rs.iterator.accept(this);
    }

    
    baseCollector.setNumberOfChildren(0);    
    /*
     * lastly create GFDistRS entry that will always mark the end of query node plan.
     */

    XPLAINResultSetTimingsDescriptor time_desc = null;
    if (considerTimingInformation) {
      UUID timingID = dd.getUUIDFactory().createUUID();

      time_desc = baseCollector.createResultSetTimingDescriptor(
          timingID,
          0,
          rs.openTime,
          rs.nextTime,
          rs.closeTime,
          rs.getTimeSpent(ResultSet.CURRENT_RESULTSET_ONLY, ResultSet.ALL),
          1,
          -1,
          -1);

    }

    baseCollector.createResultSetDescriptor(
        rs.getClass().getSimpleName(),
        time_desc,
        null,
        null,
        XPLAINUtil.OP_DISTRIBUTION_END,
        rs.getClass().getSimpleName(),
        -1,
        -1,
        -1,
        0,
        0,
        rs.rowsReturned,
        null,
        null, null);

    clean();
  }

  /**
   * Data node processing request/response messages.
   * @param rh replyMsg.singleResult
   * @param isLocallyExecuted TODO
   */
  public void processMessage(
      final StatementExecutorMessage<?> msg,
      ResultHolder rh, boolean isLocallyExecuted) {

    final UUID distID = dd.getUUIDFactory().createUUID();
    
    final String sender;
    if (msg.getSender() != null) {
      sender = msg.getSender().toString();
    }
    else {
      final GemFireCacheImpl c = Misc.getGemFireCacheNoThrow();
      if (c != null) {
        sender = c.getDistributedSystem().getDistributedMember().toString();
      }
      else {
        sender = null;
      }
    }

    final long connectionID = ac.getConnectionID();
    final UUID statementID = baseCollector.getStmtUUID();
    
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            "Collecting distribution plan for statementID="
                + statementID
                + (this.considerTimingInformation ? " with time stats "
                    : " without time stats"));
      }
    }
    final XPLAINDistPropsDescriptor distdesc = new XPLAINDistPropsDescriptor(
        distID,
        statementID,
        null,
        XPLAINUtil.DIRECTION.IN,
        connectionID == EmbedConnection.CHILD_NOT_CACHEABLE ? 2 : (short)1,
        XPLAINUtil.OP_QUERY_RECEIVE,
        sender);

    msg.setDistributionStatistics(distdesc, true);
    if (SanityManager.ASSERT) {
      if (distdesc.locallyExecuted != isLocallyExecuted) {
        SanityManager.THROWASSERT("Distribution information wrongly captured "
            + "distdesc. locallyExecuted=" + distdesc.locallyExecuted
            + " isLocallyExecuted=" + isLocallyExecuted);
      }
    }

    baseCollector.setNumberOfChildren(distdesc.memberSentMappedDesc.size() + distdesc.memberReplyMappedDesc.size());
    
    baseCollector.createDistPropDescriptor(distdesc);

    baseCollector.createResultSetDescriptor(
        msg.getClass().getSimpleName(),
        null,
        null,
        null,
        XPLAINUtil.OP_QUERY_RECEIVE,
        msg.getClass().getSimpleName(),
        -1,
        -1,
        -1,
        0,
        0,
        -1,
        null,
        null, distdesc);

    /*
     * here it will be only reply messages (sendResult & lastResult).
     */
    createSendReceiveResultSet(distdesc.memberSentMappedDesc, rh, statementID);
    createSendReceiveResultSet(distdesc.memberReplyMappedDesc, rh, statementID);
    
  }
  
  private void createSendReceiveResultSet(List<XPLAINDistPropsDescriptor> memberMappedDesc, ResultHolder rh, UUID statementID) {
    
    long[] prev_rh_stats = null;
    for (XPLAINDistPropsDescriptor desc : memberMappedDesc) {

      // for ResultHolder per message.
      // NOTE: for OP_RESULT_SEND streaming, resultHolder on remote node is reused, 
      // so we snapshot the statistics and keep it with individual reply messages.
      if (rh != null) {
        setNumberOfChildren(1);
      }
      else {
        setNumberOfChildren(0);
      }
      createSendReceiveResultSet(desc);
      
      // processing ResultHolder inside singleResult.
      UUID rh_distID = dd.getUUIDFactory().createUUID();

      if (rh != null) {
        
        final XPLAINDistPropsDescriptor rh_distdesc = new XPLAINDistPropsDescriptor(
            rh_distID,
            statementID,
            desc.getRSID(),
            XPLAINUtil.DIRECTION.OUT,
            (short)0,
            XPLAINUtil.OP_RESULT_HOLDER,
            null /*keeping it null as its redundant to RESULT-SEND*/);

        // TODO capture other RH attributes as well.
        if (SanityManager.ASSERT) {
          if (GemFireXDUtils.TracePlanGeneration) {
            SanityManager.ASSERT((rh.ser_deser_time + rh.process_time) != 0
                || rh.rows_returned == 0, " execute_time is "
                + rh.process_time + " serialization " + rh.ser_deser_time
                + " throttling time " + rh.throttle_time + " rows returned "
                + rh.rows_returned);
          }
        }
        
        long[] rh_stats = desc.getReplySingleResultStatistics();
        final int rows_returned;
        // partial streamed results are captured for this 
        // reply message on remote node.
        if (rh_stats != null && prev_rh_stats == null) {
          SanityManager.ASSERT(rh_stats.length == 4);
          rh_distdesc.setSerDeSerTime(rh_stats[0]); // ser_deser_time
          rh_distdesc.setProcessTime(rh_stats[1]); // process_time
          rh_distdesc.setThrottleTime(rh_stats[2]); //throttle_time
          rows_returned = (int)rh_stats[3]; // rows_returned
          prev_rh_stats = rh_stats;
        }
        // subsequent streamed results are captured for this 
        // reply message on remote node.
        else if (rh_stats != null && prev_rh_stats != null) {
          rh_distdesc.setSerDeSerTime(rh_stats[0] - prev_rh_stats[0]); // ser_deser_time
          rh_distdesc.setProcessTime(rh_stats[1] - prev_rh_stats[1]); // process_time
          rh_distdesc.setThrottleTime(rh_stats[2] - prev_rh_stats[2]); //throttle_time
          rows_returned = (int)(rh_stats[3] - prev_rh_stats[3]); // rows_returned
          prev_rh_stats = rh_stats;
        }
        else {
          rh_distdesc.setSerDeSerTime(rh.ser_deser_time);
          rh_distdesc.setProcessTime(rh.process_time);
          rh_distdesc.setThrottleTime(rh.throttle_time);
          rows_returned = rh.rows_returned;
        }
        

        baseCollector.createDistPropDescriptor(rh_distdesc);

        // for underlying resultSet that will be processed post this function.
        if (rh_stats == null) {
          setNumberOfChildren(1);
        }
        else {
          setNumberOfChildren(0);
        }
        baseCollector.createResultSetDescriptor(
            rh.getClass().getSimpleName(),
            null,
            null,
            null,
            XPLAINUtil.OP_RESULT_HOLDER,
            rh.getClass().getSimpleName(),
            1,
            -1,
            -1,
            -1,
            -1,
            rows_returned,
            null,
            null, rh_distdesc);
      }
    }
    
  }

  // read thread local captured statistics.
  private void processObserverStatistics(
      final AbstractGemFireDistributionActivation ac) {

    // these should be linked with some parent RS.
    if (SanityManager.ASSERT) {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.ASSERT(baseCollector.getNumberOfChildren() > 0);
      }
    }

    final Object[] stats = ac.observerStatistics;

    // if exception occurs, callback ain't called.
    if(stats == null) {
      return;
    }
    
    UUID rsID = baseCollector.popUUIDFromStack();
    
    processGlobalIndex(rsID, (GlobalIndexStat)stats[StatObjects.GLOBAL_INDEX
                           .ordinal()]);
  }
  
  private void processGlobalIndex(final UUID parentRSID, final GlobalIndexStat stat) {
    
    if (stat.indexName == null) {
      return;
    }

    XPLAINResultSetTimingsDescriptor time_desc = null;

    if (considerTimingInformation) {
      UUID timingID = dd.getUUIDFactory().createUUID();
      final long fetchTime = stat.seekTime;
      time_desc = baseCollector.createResultSetTimingDescriptor(
          timingID,
          0,
          0,
          fetchTime,
          0,
          fetchTime,
          1,
          -1,
          -1);
    }

    final UUID scanID = dd.getUUIDFactory().createUUID();

    final String lookupKey = stat.lookupKey.toString();

    final XPLAINScanPropsDescriptor scan_desc = baseCollector.createScanPropDescriptor(
        "G",
        stat.indexName,
        lookupKey,
        lookupKey,
        scanID,
        TransactionController.ISOLATION_READ_COMMITTED,
        1,
        null,
        null,
        stat.baseColPos);

    int returned_rows = 0;
    if (stat.result != null) {
      returned_rows = 1;
    }

    baseCollector.createResultSetDescriptor(
        XPLAINUtil.OP_GLOBALINDEXSCAN,
        time_desc,
        null,
        null,
        XPLAINUtil.OP_GLOBALINDEXSCAN,
        stat.indexName,
        Integer.valueOf(stat.numOpens),
        stat.esitmatedRowCount,
        stat.esitmatedCost,
        1,
        0,
        returned_rows,
        scan_desc,
        null, null);
  }

  @Override
  public void visit(
      final Activation ac,
      final int donotHonor) {
    throw new AssertionError("#visit(Activation, int) Method call not expected ");
  }

  @Override
  public void visit(
      final BaseActivation ac,
      final int donotHonor) {
    throw new AssertionError("#visit(BaseActivation, int) Method call not expected ");
  }

  @Override
  final public void visit(
      final AbstractGemFireActivation ac,
      final int donotHonor) {
    throw new AssertionError("#visit(AbstractGemFireActivation, int) Method call not expected ");
  }

  @Override
  public void visit(
      final AbstractGemFireDistributionActivation ac,
      final int donotHonor) {
    throw new AssertionError("#visit(AbstractGemFireDistributionActivation, int) Method call not expected ");
  }

  @Override
  public void visit(
      final GemFireSelectActivation ac) {
    // TODO Auto-generated method stub

  }

  @Override
  public void visit(
      final GemFireUpdateActivation ac) {
    // TODO Auto-generated method stub

  }

  @Override
  public void visit(
      final GemFireDeleteActivation ac) {
    // TODO Auto-generated method stub

  }

  @SuppressWarnings("unchecked")
  @Override
  public void visit(
      final GemFireSelectDistributionActivation ac) {
    // distribution props.

    final UUID distID = dd.getUUIDFactory().createUUID();

    final GemFireCacheImpl c = Misc.getGemFireCacheNoThrow();

    root_msg_descriptor = new XPLAINDistPropsDescriptor(
        distID,
        baseCollector.getStmtUUID(),
        XPLAINUtil.DIRECTION.OUT,
        ac.distributionLevel,
        XPLAINUtil.OP_QUERY_SCATTER,
        c != null ? c.getDistributedSystem().getDistributedMember().toString()
            : null,
        ac.routingKeysToExecute,
        ac.routingComputeTime,
        ac.prepStmntAwareMembers,
        ac.getParameterValueSet());
    
    ac.functionMsg.setDistributionStatistics(root_msg_descriptor, false);

    baseCollector.createDistPropDescriptor(root_msg_descriptor);

    // start with 'two' (one for ResultSet & other popped out in ObserverStats)
    // as we see more children we will push parentRSID one at
    // a time in getObserverStatistics for multiple entries leaving one for
    // later used by *DistributionResultSet handler.
    int numChildren = 1 + 1 + root_msg_descriptor.memberSentMappedDesc.size()
        + root_msg_descriptor.memberReplyMappedDesc.size()
        + getNumObserverStatistics(ac);
    
    baseCollector.setNumberOfChildren(numChildren);

    baseCollector.createResultSetDescriptor(
        ac.getClass().getSimpleName(),
        null,
        null,
        null,
        XPLAINUtil.OP_QUERY_SCATTER,
        "SELECT-DISTRIBUTION-ACTIVATION",
        -1,
        -1,
        -1,
        0,
        0,
        -1,
        null,
        null, root_msg_descriptor);

    processObserverStatistics(ac);

    for (XPLAINDistPropsDescriptor desc : root_msg_descriptor.memberSentMappedDesc) {
      
      setNumberOfChildren(0);
      createSendReceiveResultSet(desc);
      
      // find the equivalent reply and group together.
      for (XPLAINDistPropsDescriptor reply_desc : root_msg_descriptor.memberReplyMappedDesc) {
        if (reply_desc.getOriginator().equals(desc.getTargetMember())) {
          setNumberOfChildren(0);
          createSendReceiveResultSet(reply_desc);
          break;
        }
      }
    }

  }

  
  // read thread local captured statistics.
  private int getNumObserverStatistics(
      final AbstractGemFireDistributionActivation ac) {

    // these should be linked with some parent RS.
    int totalObserverStats = 0;
    final Object[] stats = ac.observerStatistics;

    // if exception occurs, callback ain't called.
    if(stats == null) {
      return 0;
    }
    
    GlobalIndexStat stat = (GlobalIndexStat)stats[StatObjects.GLOBAL_INDEX
                           .ordinal()];
    if (stat.indexName != null) {
      totalObserverStats++;
    }

    
    return totalObserverStats;
  }
  
  private void createSendReceiveResultSet(XPLAINDistPropsDescriptor desc) {

    baseCollector.createDistPropDescriptor(desc);

    baseCollector.createResultSetDescriptor(
        desc.getDistObjectName(),
        null,
        null,
        null,
        desc.getDistObjectType(),
        desc.getDistObjectName(),
        -1,
        -1,
        -1,
        0,
        0,
        -1,
        null,
        null, desc);
  }
  
  @Override
  public void visit(
      final GemFireUpdateDistributionActivation ac) {
    // TODO Auto-generated method stub

  }

  @Override
  public void visit(
      final GemFireDeleteDistributionActivation ac) {
    // TODO Auto-generated method stub

  }

  @Override
  public void visit(
      final ConstantActionActivation ac) {
    // TODO Auto-generated method stub

  }

  @Override
  public void reset() {
  }

  /**
   * This method cleans up things after explanation. It frees kept resources and
   * references.
   */
  private void clean() {

    // forget about all the system objects
    lcc = null;
    dd = null;
    ac = null;

    final GemFireXDQueryObserver ob = GemFireXDQueryObserverHolder
        .getInstance();
    if (ob != null) {
      ob.reset();
    }
  }

  @Override
  public void visit(
      RSIterator it,
      int donotHonor) {
    throw new AssertionError("#visit(RSIteration, int) Method call not expected ");
  }

  @Override
  public void visit(
      SequentialIterator it) {
    createIteratorDescriptor(it, XPLAINUtil.OP_SEQUENTIAL_ITERATOR, null, -1, -1);
  }

  @Override
  public void visit(
      RoundRobinIterator it) {
    createIteratorDescriptor(it, XPLAINUtil.OP_ROUNDROBIN_ITERATOR, null, -1, -1);
  }

  @Override
  public void visit(
      ResultHolder rh) {

    if (SanityManager.ASSERT) {
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.ASSERT(root_msg_descriptor != null,  "at this point we should have processed all the reply messages");
      }
    }

    XPLAINDistPropsDescriptor replyMsgDesc = null;
    for(XPLAINDistPropsDescriptor d : root_msg_descriptor.memberReplyMappedDesc) {
      if(d.replySingleResult == rh) {
        //got the reply message which have created this RH. reference will not change.
        replyMsgDesc = d;
      }
    }
    
    if(replyMsgDesc == null) {
      // for non-stores, rh will never be locally executed as no self distribution can occur.
      if (SanityManager.ASSERT) {
        if (GemFireXDUtils.TracePlanGeneration) {
          SanityManager.ASSERT((!GemFireXDUtils.getMyVMKind().isStore())
              || !rh.isLocallyExecuted(),
              "expected to be locally execute RH. IsStore="
                  + GemFireXDUtils.getMyVMKind().isStore());
        }
      }
      replyMsgDesc = root_msg_descriptor;
    }
    
    UUID distID = dd.getUUIDFactory().createUUID();

    final XPLAINDistPropsDescriptor distdesc = new XPLAINDistPropsDescriptor(
        distID,
        baseCollector.getStmtUUID(),
        replyMsgDesc.getRSID(),
        XPLAINUtil.DIRECTION.IN,
        (short)0,
        XPLAINUtil.OP_RESULT_HOLDER,
        replyMsgDesc.getOriginator());

    // TODO capture other RH attributes as well.
    distdesc.setSerDeSerTime(rh.ser_deser_time);
    distdesc.setProcessTime(rh.process_time);
    distdesc.setThrottleTime(rh.throttle_time);

    setNumberOfChildren(0);
    baseCollector.createDistPropDescriptor(distdesc);

    baseCollector.createResultSetDescriptor(
        rh.getClass().getSimpleName(),
        null,
        null,
        null,
        XPLAINUtil.OP_RESULT_HOLDER,
        rh.getClass().getSimpleName(),
        1,
        -1,
        -1,
        -1,
        -1,
        rh.rows_returned,
        null,
        null, distdesc);
  }

  @Override
  public void visit(
      OrderedIterator it) {

    UUID sortID = dd.getUUIDFactory().createUUID();

    if(SanityManager.ASSERT) {
      SanityManager.ASSERT(it.sortProperties != null,
          "Sort properties must have been got created by this time .");
    }
    
    final XPLAINSortPropsDescriptor sort_desc = baseCollector.createSortPropDescriptor(
        sortID,
        it.sortProperties,
        null,
        -1,
        -1,
        -1,
        it.sortDistinct,
        (it.source instanceof RoundRobinIterator)); // RoundRobin meaning n-way merge happening.

    createIteratorDescriptor(it, XPLAINUtil.OP_ORDERED_ITERATOR, sort_desc, -1,
        it.rowsInput);
  }

  @Override
  public void visit(
      GroupedIterator it) {
    
    createIteratorDescriptor(it, XPLAINUtil.OP_GROUPED_ITERATOR, null, -1, -1);
  }

  @Override
  public void visit(
      SpecialCaseOuterJoinIterator it) {
    
    createIteratorDescriptor(it, XPLAINUtil.OP_OUTERJOIN_ITERATOR, null, -1, -1);
    
  }

  @Override
  public void visit(
      SetOperatorIterator it) {
    
    createIteratorDescriptor(it, XPLAINUtil.OP_SET, null, -1, -1);
  }

  @Override
  public void visit(
      RowCountIterator it) {

    createIteratorDescriptor(it, XPLAINUtil.OP_ROWCOUNT_ITERATOR, null, -1, -1);
  }

  @Override
  public void setNumberOfChildren(
      int noChildren) {
    baseCollector.setNumberOfChildren(noChildren);
  }
  
  private void createIteratorDescriptor(AbstractRSIterator it, String opDetail,
      XPLAINSortPropsDescriptor sort, int rowsSeen, int rowsInput) {

    XPLAINResultSetTimingsDescriptor time_desc = null; 
    if (considerTimingInformation) {

      UUID timingID = dd.getUUIDFactory().createUUID();

//      if(SanityManager.ASSERT) {
//        SanityManager.ASSERT(it.rowsReturned == 0 || it.nextTime != 0, opDetail
//            + " next time is supposed to be non-zero for non-zero rows ");
//      }
      
      time_desc = baseCollector.createResultSetTimingDescriptor(
          timingID,
          -1,
          -1,
          it.nextTime,
          -1,
          it.nextTime == 0 ? 1 : it.nextTime, // in case clock didn't ticked.
          it.rowsReturned,
          -1,
          -1);
    }

    XPLAINResultSetDescriptor rdesc = baseCollector.createResultSetDescriptor(
        it.getClass().getSimpleName(),
        time_desc,
        null,
        null,
        opDetail,
        it.getClass().getSimpleName(),
        1,
        -1. - 1,
        -1,
        -1,
        -1,
        it.rowsReturned,
        null,
        sort, null);
    rdesc.setInputRows(rowsInput);
    rdesc.setRowsSeen(rowsSeen);
  }

}
