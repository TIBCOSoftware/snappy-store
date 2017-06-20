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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.InternalCacheEvent;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionHelper;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantReadWriteLock;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.partitioned.BucketListener;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionSingleKeyExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Singleton to manage the generated/retrieved identity values for different
 * tables. Also contains the
 * {@link IdentityValueManager.GetIdentityValueMessage} message class to be used
 * to get the next IDENTITY value from the IDENTITY generator node.
 * 
 * @author swale
 * @since 7.5
 */
public final class IdentityValueManager {

  /**
   * Map of tables to their current autoincrement values maintained on the
   * identity generator node.
   */
  private static final ConcurrentHashMap<Object, AtomicLong> autoIncMap =
      new ConcurrentHashMap<Object, AtomicLong>();

  /**
   * When a node retrieves an IDENTITY value from current central generator
   * node, then it puts this value in the map (the MAX/MIN as per increment). In
   * case of the central generator node fails, and a new node is selected (as
   * per the primary of the underlying PR), then it will send out a message to
   * get the MAX/MIN of retrieved values and MAX/MIN put in the actual table
   * (latter alone may not suffice since a node may have retrieved a value from
   * old generator node but not put it in the table yet).
   * 
   * This maps the table to 2 values: first is the long value retrieved, and
   * second is the member that last looked at it. In case a new generator node
   * comes up and looks up this retrieved value while this node is in the
   * process of acquiring a new one from the previous generator node, then this
   * node will have to retry on the new generator node.
   */
  private final ConcurrentHashMap<Object, Object[]> generatedValues =
      new ConcurrentHashMap<Object, Object[]>();

  private final NonReentrantReadWriteLock generatedValuesLock =
      new NonReentrantReadWriteLock();

  private static final IdentityValueManager instance =
      new IdentityValueManager();

  private IdentityValueManager() {
  }

  /**
   * Get the singleton instance of {@link IdentityValueManager}.
   */
  public static IdentityValueManager getInstance() {
    return instance;
  }

  /**
   * Get the generated IDENTITY value for given table next to the one last
   * retrieved by this node. Also mark the value to have been read by the new
   * generating node that is reading this IDENTITY value.
   */
  public long getAfterRetrievedValue(Object table, long start, long increment,
      DistributedMember idGeneratingNode) {
    this.generatedValuesLock.attemptWriteLock(-1);
    try {
      Object[] id = this.generatedValues.get(table);
      if (id != null) {
        AtomicLong currValue = (AtomicLong)id[0];
        id[1] = idGeneratingNode;
        return (currValue.get() + increment);
      }
      else {
        this.generatedValues.put(table, new Object[] { new AtomicLong(start),
            idGeneratingNode });
        return start;
      }
    } finally {
      this.generatedValuesLock.releaseWriteLock();
    }
  }

  /**
   * Clear the cache retrieved value for given table.
   */
  public void clearRetrievedValue(Object table) {
    this.generatedValuesLock.attemptWriteLock(-1);
    try {
      this.generatedValues.remove(table);
    } finally {
      this.generatedValuesLock.releaseWriteLock();
    }
  }

  /**
   * Set last generated value retrieved from the current generator node. In case
   * of generator node failures, this may not be the value we want to put, so
   * check against node that may have marked itself as the new generator node by
   * a call to {@link #getLastGeneratedValue} and return false if the generator
   * node is detected to have changed (in which case IDENTITY value will have to
   * be re-fetched from the new node) else return true.
   */
  public boolean setGeneratedValue(Object table, long value, long increment,
      DistributedMember idGeneratorNode) {
    this.generatedValuesLock.attemptReadLock(-1);
    try {
      Object[] id = this.generatedValues.get(table);
      if (id == null) {
        id = this.generatedValues.putIfAbsent(table, new Object[] {
            new AtomicLong(value), null });
      }
      if (id != null) {
        // check if generatorNode matches a new generatorNode, if any, that
        // may have retrieved the MAX/MIN value
        final Object markedGeneratorNode = id[1];
        if (markedGeneratorNode == null
            || markedGeneratorNode.equals(idGeneratorNode)) {
          final AtomicLong currAL = (AtomicLong)id[0];
          while (true) {
            long currValue = currAL.get();
            if ((increment > 0 && value > currValue)
                || (increment < 0 && value < currValue)) {
              if (currAL.compareAndSet(currValue, value)) {
                return true;
              }
            }
            else {
              return true;
            }
          }
        }
        else {
          // a new generatorNode has retrieved the value, so generate a new
          // value going to the new generatorNode
          return false;
        }
      }
      else {
        return true;
      }
    } finally {
      this.generatedValuesLock.releaseReadLock();
    }
  }

  /**
   * Get the next value for an identity column. Also doubles for destroying the
   * value by passing zero start and increment.
   * 
   * @author swale
   * @since 7.5
   */
  public static final class GetIdentityValueMessage extends
      RegionSingleKeyExecutorMessage {

    private long startBy;
    private long increment;
    private transient boolean forceFunctionExecutor;

    private static final class BListener implements BucketListener {

      /**
       * {@inheritDoc}
       */
      @Override
      public void primaryMoved(Bucket bucket) {
        final int bucketId = bucket.getId();
        final PartitionedRegion pr = bucket.getPartitionedRegion();
        synchronized (autoIncMap) {
          // remove all keys that belong to that bucket
          for (Object key : autoIncMap.keySet()) {
            if (bucketId == PartitionedRegionHelper.getHashKey(pr, key)) {
              autoIncMap.remove(key);
            }
          }
        }
      }

      /**
       * {@inheritDoc}
       */
      @Override
      public void regionClosed(InternalCacheEvent event) {
        synchronized (autoIncMap) {
          autoIncMap.clear();
        }
      }
    }

    /** for deserialization */
    public GetIdentityValueMessage() {
      super(true);
    }

    public GetIdentityValueMessage(LocalRegion region, Object key, long start,
        long inc, LanguageConnectionContext lcc) {
      super(region, key, null, null, false, null, getTimeStatsSettings(lcc));
      this.startBy = start;
      this.increment = inc;
    }

    protected GetIdentityValueMessage(
        final GetIdentityValueMessage other) {
      super(other);
      this.startBy = other.startBy;
      this.increment = other.increment;
    }

    public static void installBucketListener(PartitionedRegion identityRegion) {
      identityRegion.setBucketListener(new BListener());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void execute() throws Exception {

      long maxIdentity;

      if (this.increment != 0) {
        AtomicLong ident = autoIncMap.get(key);
        if (ident != null) {
          maxIdentity = ident.addAndGet(this.increment);
        }
        else {
          // may need to do further distribution, so reschedule this message
          // on an executor thread, freeing the shared P2P reader thread
          if (!this.forceFunctionExecutor && dm != null) {
            this.forceFunctionExecutor = true;
            schedule(dm);
            return;
          }
          synchronized (autoIncMap) {
            ident = autoIncMap.get(key);
            if (ident == null) {
              final GemFireContainer container = getContainer((String)key);
              final ExtraTableInfo tabInfo = container.getExtraTableInfo();
              maxIdentity = this.startBy;

              // first get any existing retrieved values that nodes may not have
              // put into the table yet (but are in the process)
              long retrievedIdentity = GetRetrievedIdentityValues.retrieve(key,
                  this.startBy, this.increment, this.startBy);

              // then also get from the region to handle cases where a node may
              // have retrieved and put in the table, but is no longer alive
              boolean hasRows;
              LocalRegion r = container.getRegion();
              if (r.getDataPolicy().withPartitioning()) {
                PartitionedRegion pr = (PartitionedRegion)r;
                // just check number of buckets and avoid sending size message
                hasRows = (pr.getRegionAdvisor().getCreatedBucketsCount() > 0);
              }
              else {
                // assume that there are rows for replicated tables
                hasRows = true;
              }
              if (hasRows) {
                // need to get the current value from the cluster
                final String columnName = tabInfo.getAutoGeneratedColumn(
                    tabInfo.getAutoGeneratedColumns()[0]).getColumnName();
                EmbedConnection conn = GemFireXDUtils
                    .createNewInternalConnection(false);
                try {
                  // get the autoInc column name from table schema
                  Statement stmt = conn.createStatement();
                  java.sql.ResultSet rs;
                  String query = null;
                  if (this.increment > 0) {
                    query = "select max(" + columnName
                        + ") from " + container.getQualifiedTableName();
                  }
                  else {
                    query = "select min(" + columnName
                        + ") from " + container.getQualifiedTableName();
                  }
                  if (container.getRegion().isHDFSReadWriteRegion()) {
                    query = query + " -- GEMFIREXD-PROPERTIES queryHDFS=true \n" ;
                  }
                  rs = stmt.executeQuery(query);
                  if (rs.next()) {
                    maxIdentity = rs.getLong(1);
                    if (rs.wasNull()) {
                      maxIdentity = this.startBy;
                    }
                    else {
                      maxIdentity += this.increment;
                    }
                  }
                  rs.close();
                  stmt.close();
                } finally {
                  conn.close();
                }
                if ((this.increment > 0 && retrievedIdentity > maxIdentity)
                    || (this.increment < 0 && retrievedIdentity < maxIdentity)) {
                  maxIdentity = retrievedIdentity;
                }
                SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
                    "IDENTITY: got new start value " + maxIdentity
                        + " for column " + columnName + " in table "
                        + container.getQualifiedTableName());
              }
              ident = new AtomicLong(maxIdentity);
              autoIncMap.put(key, ident);
            }
            else {
              maxIdentity = ident.addAndGet(this.increment);
            }
          }
        }
      }
      else { // for destroy
        autoIncMap.remove(key);
        // also remove all retrieved values cached by other nodes
        GetRetrievedIdentityValues.retrieve(key, 0, 0, 0);
        maxIdentity = 0;
      }

      lastResult(Long.valueOf(maxIdentity), false, false, false);
    }

    private GemFireContainer getContainer(String key) {
      GemFireContainer container = GemFireContainer
          .getContainerFromIdentityKey(key);
      if (container != null
          && container.getExtraTableInfo() != null) {
        return container;
      }
      else {
        throw new RegionDestroyedException(LocalizedStrings
            .PartitionMessage_0_COULD_NOT_FIND_PARTITIONED_REGION_WITH_ID_1
                .toLocalizedString(new Object[] {
                    Misc.getGemFireCache().getMyId(),
                    Integer.valueOf(this.prId) }), (String)key);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canStartRemoteTransaction() {
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMessageProcessorType() {
      if (this.forceFunctionExecutor) {
        return super.getMessageProcessorType();
      }
      else {
        // Make this serial so that it will be processed in the p2p msg reader
        // which gives it better performance.
        return DistributionManager.SERIAL_EXECUTOR;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected RegionSingleKeyExecutorMessage clone() {
      return new GetIdentityValueMessage(this);
    }

    @Override
    public void reset() {
      super.reset();
      // in reset, check if region of the table being looked up is still present
      getContainer((String)key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHA() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean optimizeForWrite() {
      return true;
    }

    @Override
    public byte getGfxdID() {
      return GET_IDENTITY_MSG;
    }

    @Override
    public void toData(DataOutput out)
        throws IOException {
      final long beginTime = XPLAINUtil
          .recordTiming(ser_deser_time == 0 ? ser_deser_time = -1 /*record*/
          : -2/*ignore nested call*/);
      super.toData(out);
      InternalDataSerializer.writeSignedVL(this.startBy, out);
      InternalDataSerializer.writeSignedVL(this.increment, out);
      if (beginTime != 0) {
        this.ser_deser_time = XPLAINUtil.recordTiming(beginTime);
      }
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      ser_deser_time = this.timeStatsEnabled ? (ser_deser_time == 0 ? -1 /*record*/
          : -2/*ignore nested call*/) : 0;
      super.fromData(in);
      this.startBy = InternalDataSerializer.readSignedVL(in);
      this.increment = InternalDataSerializer.readSignedVL(in);
      // recording end of de-serialization here instead of AbstractOperationMessage.
      if (this.timeStatsEnabled && ser_deser_time == -1) {
        this.ser_deser_time = XPLAINUtil.recordStdTiming(getTimestamp());
      }
    }

    @Override
    protected String getID() {
      return "GetIdentityValueMessage";
    }

    @Override
    protected void appendFields(final StringBuilder sb) {
      super.appendFields(sb);
      sb.append(";startBy=").append(this.startBy).append(";increment=")
          .append(this.increment);
    }
  }

  public static final class GetRetrievedIdentityValues extends
      MemberExecutorMessage<Object> {

    private Object table;
    private long start;
    private long increment;

    /** Default constructor for deserialization. Not to be invoked directly. */
    public GetRetrievedIdentityValues() {
      super(true);
    }

    private GetRetrievedIdentityValues(
        final ResultCollector<Object, Object> rc,
        final Object table, final long start, final long increment) {
      super(rc, null, false, true);
      this.table = table;
      this.start = start;
      this.increment = increment;
    }

    public static long retrieve(Object table, long start, long increment,
        long currentIdentity) throws StandardException, SQLException {
      GetRetrievedIdentityValues msg = new GetRetrievedIdentityValues(
          new GfxdListResultCollector(), table, start, increment);
      @SuppressWarnings("unchecked")
      ArrayList<Object> allResults = (ArrayList<Object>)msg.executeFunction();
      long result = currentIdentity;
      if (increment > 0) {
        for (Object o : allResults) {
          long v = ((Long)o).longValue();
          if ((increment > 0 && v > result) || (increment < 0 && v < result)) {
            result = v;
          }
        }
      }
      return result;
    }

    private GetRetrievedIdentityValues(final GetRetrievedIdentityValues other) {
      super(other);
      this.table = other.table;
      this.start = other.start;
      this.increment = other.increment;
    }

    @Override
    protected void execute() throws SQLException {
      final long result;
      if (this.increment != 0) {
        result = IdentityValueManager.getInstance().getAfterRetrievedValue(
            this.table, this.start, this.increment, getSenderForReply());
        if (GemFireXDUtils.TraceExecution | GemFireXDUtils.TraceFunctionException) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
              "IDENTITY: GetRetrievedIdentityValues: after identity generator "
                  + "start/failure, got retrieved value = " + result
                  + " for table " + this.table);
        }
      }
      else { // for clear
        IdentityValueManager.getInstance().clearRetrievedValue(this.table);
        result = 0;
        if (GemFireXDUtils.TraceExecution) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_EXECUTION,
              "IDENTITY: GetRetrievedIdentityValues: cleared any cached "
                  + "retrieved value for table " + this.table);
        }
      }
      lastResult(Long.valueOf(result), false, false, false);
    }

    @Override
    public Set<DistributedMember> getMembers() {
      return getAllGfxdServers();
    }

    @Override
    public boolean optimizeForWrite() {
      return false;
    }

    @Override
    public boolean isHA() {
      return true;
    }

    @Override
    public boolean canStartRemoteTransaction() {
      return false;
    }

    @Override
    protected boolean requiresTXFlushBeforeExecution() {
      return false;
    }

    @Override
    protected boolean requiresTXFlushAfterExecution() {
      return false;
    }

    @Override
    public void postExecutionCallback() {
    }

    @Override
    protected GetRetrievedIdentityValues clone() {
      return new GetRetrievedIdentityValues(this);
    }

    @Override
    public byte getGfxdID() {
      return GET_RETRIEVED_IDENTITY_MSG;
    }

    @Override
    public void toData(DataOutput out)
        throws IOException {
      super.toData(out);
      InternalDataSerializer.writeObject(this.table, out);
      InternalDataSerializer.writeSignedVL(this.start, out);
      InternalDataSerializer.writeSignedVL(this.increment, out);
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.table = InternalDataSerializer.readObject(in);
      this.start = InternalDataSerializer.readSignedVL(in);
      this.increment = InternalDataSerializer.readSignedVL(in);
    }

    @Override
    protected void appendFields(final StringBuilder sb) {
      super.appendFields(sb);
      sb.append(";table=").append(this.table).append(";start=")
          .append(this.start).append(";increment=").append(this.increment);
    }
  }
}
