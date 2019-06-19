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
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.delta.Delta;

/**
 * This message is to cleanup a {@link TXEntryState} by releasing the lock and
 * reverting the pending value.
 * 
 * @author swale
 * @since 7.0
 */
public final class TXCleanupEntryMessage extends TXMessage {

  private String regionPath;

  private Object regionKey;

  private byte op;

  private byte destroy;

  private boolean bulkOp;

  private Object originalValue;

  private Delta originalDelta;

  /** for serialization */
  public TXCleanupEntryMessage() {
  }

  private TXCleanupEntryMessage(final TXStateInterface tx,
      final ReplyProcessor21 processor, final String regionPath,
      final Object regionKey, final byte originalOp,
      final byte originalDestroy, final boolean originalBulkOp,
      final Object originalValue, final Delta originalDelta) {
    super(tx, processor);
    this.regionPath = regionPath;
    this.regionKey = regionKey;
    this.op = originalOp;
    this.destroy = originalDestroy;
    this.bulkOp = originalBulkOp;
    this.originalValue = originalValue;
    this.originalDelta = originalDelta;
  }

  public static ReplyProcessor21 send(final InternalDistributedSystem system,
      final DM dm, final TXStateInterface txState,
      final Set<InternalDistributedMember> recipients, final LocalRegion dataRegion,
      final Object regionKey, final byte originalOp,
      final byte originalDestroy, final boolean originalBulkOp,
      final Object originalValue, final Delta originalDelta) {
    final ReplyProcessor21 response = new ReplyProcessor21(system, recipients);
    final TXCleanupEntryMessage msg = new TXCleanupEntryMessage(txState,
        response, dataRegion.getFullPath(), regionKey, originalOp,
        originalDestroy, originalBulkOp, originalValue, originalDelta);
    msg.setRecipients(recipients);
    dm.putOutgoing(msg);
    return response;
  }

  /**
   * @see TXMessage#operateOnTX(TXStateProxy, DistributionManager)
   */
  @Override
  protected boolean operateOnTX(final TXStateProxy tx, DistributionManager dm) {
    final TXState txState;
    // if no TXState was created (e.g. due to only getEntry/size operations
    // that don't start remote TX) then ignore
    if (tx != null && (txState = tx.getLocalTXState()) != null) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null && !cache.isClosed()
          && cache.getCancelCriterion().cancelInProgress() == null) {
        final LocalRegion dataRegion = cache
            .getRegionByPathForProcessing(this.regionPath);
        if (dataRegion != null) {
          txState.revertFailedOp(dataRegion, this.regionKey, this.op,
              this.destroy, this.bulkOp, this.originalValue,
              this.originalDelta);
        }
      }
    }
    return true;
  }

  public int getDSFID() {
    return TX_CLEANUP_ENTRY_MESSAGE;
  }

  @Override
  public void toData(DataOutput out)
          throws IOException {
    super.toData(out);
    DataSerializer.writeString(this.regionPath, out);
    DataSerializer.writeObject(this.regionKey, out);
    out.writeByte(this.op);
    out.writeByte(this.destroy);
    out.writeBoolean(this.bulkOp);
    DataSerializer.writeObject(this.originalValue, out);
    DataSerializer.writeObject(this.originalDelta, out);
  }

  @Override
  public void fromData(DataInput in)
          throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.regionPath = DataSerializer.readString(in);
    this.regionKey = DataSerializer.readObject(in);
    this.op = in.readByte();
    this.destroy = in.readByte();
    this.bulkOp = in.readBoolean();
    this.originalValue = DataSerializer.readObject(in);
    this.originalDelta = DataSerializer.readObject(in);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected short computeCompressedShort(short flags) {
    return flags;
  }

  /**
   * @see AbstractOperationMessage#appendFields(StringBuilder)
   */
  @Override
  protected void appendFields(StringBuilder sb) {
    sb.append("; regionPath=").append(this.regionPath);
    sb.append("; regionKey=").append(this.regionKey);
    sb.append("; op=").append(this.op);
    sb.append("; destroy=").append(this.destroy);
    sb.append("; bulkOp=").append(this.bulkOp);
    sb.append("; originalValue=").append(this.originalValue);
    sb.append("; originalDelta=").append(this.originalDelta);
  }
}
