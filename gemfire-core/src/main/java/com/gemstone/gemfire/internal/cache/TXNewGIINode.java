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
import java.util.List;

import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gnu.trove.THashMap;

/**
 * Confirm the set of new regions for a transaction on a node. This is sent to a
 * new GII target node before commit or rollback of this transaction so that the
 * target node can record the fate of the transaction for longer (i.e. till the
 * end of GII of the region).
 * 
 * @author swale
 * @since Helios
 */
public class TXNewGIINode extends TXMessage {

  private ArrayList<RegionInfoShip> giiRegions;
  private boolean forCommit;

  /** for deserialization */
  public TXNewGIINode() {
  }

  private TXNewGIINode(final TXStateInterface tx,
      final ReplyProcessor21 processor,
      final ArrayList<RegionInfoShip> giiRegions, final boolean commit) {
    super(tx, processor);
    this.giiRegions = giiRegions;
    this.forCommit = commit;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public static void send(final InternalDistributedSystem system, final DM dm,
      final TXStateInterface tx, final InternalDistributedMember recipient,
      final List<Object> memberData, final boolean forCommit) {
    final int dataLen;
    if (memberData == null || (dataLen = memberData.size()) == 0) {
      return;
    }
    // first element could be events map; if so then remove it
    final ArrayList<RegionInfoShip> giiRegions;
    if (memberData.get(0) instanceof THashMap) {
      if (dataLen == 1) {
        return;
      }
      giiRegions = new ArrayList<RegionInfoShip>(dataLen - 1);
      for (int index = 1; index < dataLen; index++) {
        giiRegions.add((RegionInfoShip)memberData.get(index));
      }
    }
    else {
      giiRegions = (ArrayList)memberData;
    }
    final ReplyProcessor21 response = new ReplyProcessor21(system, recipient);
    final TXNewGIINode msg = new TXNewGIINode(tx, response, giiRegions,
        forCommit);
    msg.setRecipient(recipient);
    dm.putOutgoing(msg);

    try {
      response.waitForReplies();
    } catch (ReplyException re) {
      final Throwable cause = re.getCause();
      re.fixUpRemoteEx(cause);
      if (cause instanceof TransactionException) {
        throw (TransactionException)cause;
      }
      else {
        throw re;
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      dm.getCancelCriterion().checkCancelInProgress(ie);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected boolean operateOnTX(TXStateProxy txProxy, DistributionManager dm) {
    // processing requires us to just create the TXState with TXRegionState
    // which will automatically be registered with ImageState
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache != null && txProxy != null) {
      final TXState txState = txProxy.getTXStateForWrite();
      for (RegionInfoShip regionInfo : this.giiRegions) {
        LocalRegion region = regionInfo.lookupRegion(cache);
        if (region != null) {
          txState.writeRegion(region);
        }
      }
      // also lock the TXState so no changes can be applied by GII thread
      // from this point
      boolean txLocked = txState.lockTXRSAndTXState();
      // also check if the TXState has been marked as inconsistent before commit
      if (this.forCommit) {
        boolean success = false;
        try {
          txProxy.checkTXState();
          success = true;
        } finally {
          // release locks immediately if we acquired them
          if (!success && txLocked) {
            txState.unlockTXRSAndTXState();
          }
        }
      }
    }
    return true;
  }

  @Override
  public final boolean canStartRemoteTransaction() {
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getDSFID() {
    return TX_NEW_GII_NODE;
  }

  @Override
  public void toData(DataOutput out)
          throws IOException {
    super.toData(out);

    InternalDataSerializer.writeArrayLength(this.giiRegions.size(), out);
    for (RegionInfoShip regionInfo : this.giiRegions) {
      InternalDataSerializer.invokeToData(regionInfo, out);
    }
    out.writeBoolean(this.forCommit);
  }

  @Override
  public void fromData(DataInput in)
          throws IOException, ClassNotFoundException {
    super.fromData(in);

    int size = InternalDataSerializer.readArrayLength(in);
    this.giiRegions = new ArrayList<RegionInfoShip>(size);
    while (--size >= 0) {
      RegionInfoShip regionInfo = new RegionInfoShip();
      InternalDataSerializer.invokeFromData(regionInfo, in);
      this.giiRegions.add(regionInfo);
    }
    this.forCommit = in.readBoolean();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected short computeCompressedShort(short flags) {
    return flags;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void appendFields(StringBuilder sb) {
    sb.append("; giiRegions=").append(this.giiRegions).append("; forCommit=")
        .append(this.forCommit);
  }
}
