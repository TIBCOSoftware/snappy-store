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
package com.gemstone.gemfire.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TObjectIntHashMap;
import com.gemstone.gnu.trove.TObjectObjectProcedure;

/**
 * Message sent to remote nodes to perform the final commit of an active
 * transaction.
 * 
 * @author sbawaska, swale
 */
public final class TXRemoteCommitMessage extends TXMessage {

  private static final short CALLBACK_ARG_SET = UNRESERVED_FLAGS_START;
  private static final short HAS_COMMIT_TIME = (CALLBACK_ARG_SET << 1);
  private static final short HAS_TO_BE_PUBLISHED_EVENT = (HAS_COMMIT_TIME << 1);
  private static final short HAS_DISK_VERSION_SOURCES =
      (HAS_TO_BE_PUBLISHED_EVENT << 1);

  private Object callbackArg;
  private long commitTime;
  private THashMap/*<String, TObjectLongHashMap>*/ memberTailKeysMap;
  private THashMap regionDiskVersionSources;

  /**
   * try to optimize serialization of DiskStoreIds by removing dups when the
   * size of {@link #regionDiskVersionSources} exceeds this limit
   */
  private static final int OPTIMIZE_SERIALIZATION_LIMIT = 4;

  private static final Version[] serializationVersions =
      new Version[] { Version.GFXD_1302 };

  /** for deserialization */
  public TXRemoteCommitMessage() {
  }

  private TXRemoteCommitMessage(final TXStateProxy tx,
      final ReplyProcessor21 processor, final Object callbackArg,
      final long commitTime, final THashMap eventsMap,
      final THashMap versionSources) {
    super(tx, processor);
    this.callbackArg = callbackArg;
    this.commitTime = commitTime;
    this.memberTailKeysMap = eventsMap;
    this.regionDiskVersionSources = versionSources;
  }

  public static void send(final InternalDistributedSystem system, final DM dm,
      final TXStateProxy tx, final Object callbackArg, final Set<?> recipients,
      final TXStateProxy.MemberToGIIRegions finishRecipients,
      final TXManagerImpl.TXContext context, final boolean async,
      final long commitTime) {
    final CommitResponse response = new CommitResponse(system, dm, recipients,
        context, tx.getTransactionId());
    // if there are events to be published, then send separate member-wise
    // split messages as per hosted data to avoid sending full maps to all
    if (finishRecipients.eventsToBePublished != null) {
      finishRecipients.members.forEachKeyValue((mbr, data) -> {
        THashMap memberEvents = (THashMap)data.get(0);
        final TXRemoteCommitMessage msg = new TXRemoteCommitMessage(tx,
            response, callbackArg, commitTime, memberEvents,
            finishRecipients.regionDiskVersionSources);
        msg.setRecipient(mbr);
        dm.putOutgoing(msg);
      });
    }
    else {
      final TXRemoteCommitMessage msg = new TXRemoteCommitMessage(tx, response,
          callbackArg, commitTime, null,
          finishRecipients.regionDiskVersionSources);
      msg.setRecipients(recipients);
      dm.putOutgoing(msg);
    }

    // mark operation end for state flush
    finishRecipients.endOperationSend(tx);
    dm.getStats().incSentCommitMessages(1L);
    // if there is any pending commit from this thread then wait for it first
    context.waitForPendingCommit();
    if (async) {
      if (response.startWait()) {
        context.setPendingCommit(response);
      }
    }
    else {
      response.waitForCommitReplies();
    }
  }

  @Override
  protected boolean operateOnTX(final TXStateProxy txProxy,
      DistributionManager dm) {
    if (txProxy != null) {
      txProxy.setCommitVersionSources(this.commitTime,
          this.regionDiskVersionSources);
      txProxy.addToBePublishedEvents(this.memberTailKeysMap);
      txProxy.commit(null /* indicates remote commit */, this.callbackArg);
      return true;
    }
    else {
      // if no TXState was created (e.g. due to only getEntry/size operations
      // that don't start remote TX) then ignore, but try to remove from hosted
      // txStates neverthless
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      final TXManagerImpl txMgr;
      if (cache != null &&
          (txMgr = cache.getCacheTransactionManager()) != null) {
        txMgr.removeHostedTXState(getTXId(), Boolean.TRUE);
      }
      return true;
    }
  }

  @Override
  public boolean containsRegionContentChange() {
    // for TX state flush
    return true;
  }

  /**
   * @see TransactionMessage#useTransactionProxy()
   */
  @Override
  public boolean useTransactionProxy() {
    // if proxy is present then by default the commit/rollback should act on it
    return true;
  }

  @Override
  protected final boolean isTXEnd() {
    return true;
  }

  @Override
  public Version[] getSerializationVersions() {
    return serializationVersions;
  }

  public void toDataPre_GFXD_1_3_0_2(final DataOutput out) throws IOException {
    super.toData(out);
    if (this.callbackArg != null) {
      DataSerializer.writeObject(this.callbackArg, out);
    }

    InternalDataSerializer.writeUnsignedVL(this.commitTime, out);
    if (this.memberTailKeysMap != null && !this.memberTailKeysMap.isEmpty()) {
      InternalDataSerializer.writeTHashMap(this.memberTailKeysMap, out);
    }
  }


  @Override
  public void toData(final DataOutput out) throws IOException {
    toDataPre_GFXD_1_3_0_2(out);

    final THashMap versionSources = this.regionDiskVersionSources;
    final int nSources;
    if (versionSources != null && (nSources = versionSources.size()) > 0) {
      InternalDataSerializer.writeUnsignedVL(nSources, out);

      // if map is big enough, then replace dups by incremental number
      if (nSources  > OPTIMIZE_SERIALIZATION_LIMIT) {
        final TObjectIntHashMap versionToIds = new TObjectIntHashMap(nSources);
        versionSources.forEachEntry(new TObjectObjectProcedure() {
          private transient int sourceId;
          @Override
          public boolean execute(final Object r, final Object vs) {
            try {
              InternalDataSerializer.writeObject(r, out);

              int currId;
              if ((currId = versionToIds.putIfAbsent(vs, sourceId, -1)) != -1) {
                out.writeByte(DSCODE.INTEGER);
                InternalDataSerializer.writeUnsignedVL(currId, out);
              }
              else {
                sourceId++;
                InternalDataSerializer.writeObject(vs, out);
              }
            } catch (IOException e) {
              return false;
            }
            return true;
          }
        });
      }
      else {
        versionSources.forEachEntry(new InternalDataSerializer.WriteKeyValue(
            out));
      }
    }
  }

  public void fromDataPre_GFXD_1_3_0_2(DataInput in)
          throws IOException, ClassNotFoundException {
    super.fromData(in);
    if ((flags & CALLBACK_ARG_SET) != 0) {
      this.callbackArg = DataSerializer.readObject(in);
    }
    if ((flags & HAS_COMMIT_TIME) != 0) {
      this.commitTime = InternalDataSerializer.readUnsignedVL(in);
    }
    if ((flags & HAS_TO_BE_PUBLISHED_EVENT) != 0) {
      this.memberTailKeysMap = InternalDataSerializer.readTHashMap(in);
    }
  }

  @Override
  public void fromData(DataInput in)
          throws IOException, ClassNotFoundException {
    fromDataPre_GFXD_1_3_0_2(in);
    if ((flags & HAS_DISK_VERSION_SOURCES) != 0) {
      final int nSources = (int)InternalDataSerializer.readUnsignedVL(in);
      if (nSources > OPTIMIZE_SERIALIZATION_LIMIT) {
        final THashMap versionSources = new THashMap(nSources);
        final ArrayList<Object> sourceIds = new ArrayList<Object>(nSources / 2);
        for (int i = 0; i < nSources; i++) {
          final Object r = InternalDataSerializer.readObject(in);
          final Object vs;
          final byte header = in.readByte();
          if (header == DSCODE.INTEGER) {
            int sourceId = (int)InternalDataSerializer.readUnsignedVL(in);
            // we expect the ID at that index to be already populated
            vs = sourceIds.get(sourceId);
          }
          else {
            vs = InternalDataSerializer.readObject(header, in);
            sourceIds.add(vs);
          }
          versionSources.put(r, vs);
        }
        this.regionDiskVersionSources = versionSources;
      }
      else {
        final THashMap versionSources = new THashMap(nSources);
        for (int i = 0; i < nSources; i++) {
          final Object r = InternalDataSerializer.readObject(in);
          final Object vs = InternalDataSerializer.readObject(in);
          versionSources.put(r, vs);
        }
        this.regionDiskVersionSources = versionSources;
      }
    }
  }

  @Override
  protected short computeCompressedShort(short flags) {
    if (this.callbackArg != null) {
      flags |= CALLBACK_ARG_SET;
    }
    // always setting the flag for commitTime but sending the commitTime only
    // for newer releases in virtualToData since older versions will ignore this
    // flag and commitTime
    flags |= HAS_COMMIT_TIME;
    if (this.memberTailKeysMap != null && !this.memberTailKeysMap.isEmpty()) {
      flags |= HAS_TO_BE_PUBLISHED_EVENT;
    }
    if (this.regionDiskVersionSources != null
        && !this.regionDiskVersionSources.isEmpty()) {
      flags |= HAS_DISK_VERSION_SOURCES;
    }
    return flags;
  }

  /**
   * @see com.gemstone.gemfire.internal.DataSerializableFixedID#getDSFID()
   */
  public int getDSFID() {
    return TX_REMOTE_COMMIT_MESSAGE;
  }

  /**
   * @see AbstractOperationMessage#appendFields(StringBuilder)
   */
  @Override
  protected void appendFields(final StringBuilder sb) {
    if (this.callbackArg != null) {
      sb.append("; callbackArg=").append(this.callbackArg);
    }
    sb.append("; commitTime=").append(this.commitTime);
    if (this.regionDiskVersionSources != null) {
      sb.append("; diskStoreIdMap: ").append(this.regionDiskVersionSources);
    }
  }

  public final static class CommitResponse extends ReplyProcessor21 {

    private final TXManagerImpl.TXContext commitContext;

    private final TXId txId;

    private boolean commitWaitsIncremented;

    public CommitResponse(final InternalDistributedSystem system, final DM dm,
        final Collection<?> initMembers, final TXManagerImpl.TXContext context,
        final TXId txId) {
      super(dm, system, initMembers, null, true);
      this.commitContext = context;
      this.txId = txId;
    }

    public final TXId getPendingTXId() {
      return this.txId;
    }

    public final void waitForCommitReplies() throws TransactionException,
        ReplyException {
      boolean success = false;
      Throwable thr = null;
      try {
        // invoked with cleanup as false since checkIfDone handles cleanup
        waitForReplies(0L, getLatch(), false);
        success = true;
      } catch (ReplyException re) {
        final Throwable cause = re.getCause();
        re.fixUpRemoteEx(cause);
        thr = re;
        if (cause instanceof TransactionException) {
          throw (TransactionException)cause;
        }
        else {
          throw re;
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        thr = ie;
      } finally {
        if (!success) {
          getDistributionManager().getCancelCriterion().checkCancelInProgress(
              thr);
        }
      }
    }

    @Override
    protected final boolean stopBecauseOfExceptions() {
      // never stop because of exceptions
      return false;
    }

    @Override
    protected final void checkIfDone() {
      if (!stillWaiting()) {
        try {
          // remove self from the context
          this.commitContext.clearPendingCommit(this);
          // other remaining cleanup
          if (this.startedWaiting) {
            try {
              postWait();
            } finally {
              cleanup();
            }
          }
        } finally {
          finished();
        }
      }
    }

    /**
     * We're still waiting if there is any member still left in the set and an
     * exception hasn't been returned from anyone yet.
     * 
     * @return true if we are still waiting for a response
     */
    @Override
    protected final boolean stillWaiting() {
      if (this.shutdown) {
        // Create the exception here, so that the call stack reflects the
        // failed computation.  If you set the exception in onShutdown,
        // the resulting stack is not of interest.
        ReplyException re = new ReplyException(
            new DistributedSystemDisconnectedException(LocalizedStrings
                .ReplyProcessor21_ABORTED_DUE_TO_SHUTDOWN.toLocalizedString()));
        this.exception = re;
        return false;
      }

      // All else is good, keep waiting if we have members to wait on.

      // We do incCommitWaits() invocation in the sync block to prevent a
      // possible race with waitForCommitReplies() that can cause
      // incCommitWaits() invocation to happen after waitForCommitReplies()
      int sz = 0;
      final InternalDistributedMember[] members = this.members;
      synchronized (members) {
        for (int index = 0; index < members.length; ++index) {
          if (members[index] != null) {
            ++sz;
          }
        }
        if (sz > 0) {
          return true;
        }
        else {
          if (!this.commitWaitsIncremented) {
            getDistributionManager().getStats().incCommitWaits();
            this.commitWaitsIncremented = true;
          }
          return false;
        }
      }
    }

    /**
     * Overridden to avoid cancellation check since this is now invoked in a
     * background thread.
     */
    @Override
    protected final void checkCancellationInPostWait(DM dm) {
    }

    @Override
    public String toString() {
      return "<CommitResponse " + this.getProcessorId() + " for " + this.txId
          + " waiting for " + numMembers() + " replies"
          + (exception == null ? "" : (" exception: " + exception)) + " from "
          + membersToString() + ">";
    }
  }
}
