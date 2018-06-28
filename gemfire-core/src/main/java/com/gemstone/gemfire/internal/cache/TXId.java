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

import com.gemstone.gemfire.internal.ExternalizableDSFID;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.shared.ClientResolverUtils;
import com.gemstone.gemfire.cache.TransactionId;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;

import java.io.*;

/**
 * The implementation of the {@link TransactionId} interface stored in the
 * transaction state and used, among other things, to uniquely identify a
 * transaction in a confederation of transaction participants (currently VM in a
 * Distributed System).
 * <p>
 * [sumedh] Changed to use an integer for member ID that is globally unique. Now
 * this is supposed to be used in conjunction with
 * {@link PersistentUUIDAdvisor#newUUID}. This is now also persisted on disk so
 * that restarts of cluster will restart from previous values making it unique
 * across cluster restarts.
 * 
 * @author Mitch Thomas
 * 
 * @since 4.0
 * 
 * @see TXManagerImpl#begin
 * @see com.gemstone.gemfire.cache.CacheTransactionManager#getTransactionId
 */
public final class TXId extends ExternalizableDSFID implements TransactionId,
    ClusterUUID, Comparable<TXId> {

  private static final long serialVersionUID = -3534280936282486380L;

  /** The domain of a transaction, currently the VM's unique identifier */
  long memberId;

  /** Unique identifier for a transaction in a memberId. */
  int uniqId;

  /**
   * Default constructor meant for the Externalizable
   */
  public TXId() {
  }

  /**
   * Constructor for the Transaction Manager ID, the birth place of TXId
   * objects. The object is Serializable mainly because of the identifier type
   * provided by JGroups.
   */
  private TXId(final long memberId, final int uniqId) {
    this.memberId = memberId;
    this.uniqId = uniqId;
    //(new java.lang.Exception()).printStackTrace();
  }

  public static final TXId valueOf(final long memberId, final int uniqId) {
    return new TXId(memberId, uniqId);
  }

  public static final TXId newTXId(final GemFireCacheImpl cache) {
    //final PersistentUUIDAdvisor idAdvisor = cache.getTXIdAdvisor();
    final VMIdAdvisor idAdvisor = cache.getSystem().getVMIdAdvisor();
    final TXId newId = new TXId();
    idAdvisor.newUUID(newId);
    return newId;
  }

  @Override
  public final long getMemberId() {
    return this.memberId;
  }

  @Override
  public final int getUniqId() {
    return this.uniqId;
  }

  @Override
  public final void setUUID(long memberId, int uniqId, VMIdAdvisor advisor) {
    // strip off DSID from the full memberId; this can change in future if
    // we want to have unique TXIds across clusters too
    this.memberId = advisor.getVMId(memberId);
    this.uniqId = uniqId;
  }

  public final InternalDistributedMember getMember(
      final InternalDistributedSystem sys, final boolean initializeAdvisor) {
    final VMIdAdvisor advisor;
    if (sys != null && (advisor = sys.getVMIdAdvisor()) != null) {
      return advisor.adviseDistributedMember(this.memberId, initializeAdvisor);
    }
    return null;
  }

  @Override
  public final String toStringID() {
    return shortToString();
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    appendToString(sb, InternalDistributedSystem.getAnyInstance());
    return sb.toString();
  }

  final void appendToString(final StringBuilder sb,
      final InternalDistributedSystem sys) {
    sb.append("TXId[").append(this.memberId);
    sb.append(':').append(this.uniqId);
    if (sys != null) {
      sb.append(" => ").append(getMember(sys, false));
    }
    sb.append(']');
  }

  public final String shortToString() {
    return "TXId[" + this.memberId + ':' + this.uniqId + ']';
  }

  public final String stringFormat() {
    return new StringBuilder(40)
        .append(this.memberId).append(':').append(this.uniqId).toString();
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof TXId) {
      return equals((TXId)o);
    }
    return false;
  }

  public final boolean equals(final TXId other) {
    if (other != null) {
      if (this != other) {
        return (this.uniqId == other.uniqId && this.memberId == other.memberId);
      }
      return true;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return hashCode(this.memberId, this.uniqId);
  }

  static int hashCode(final long memberId, final int uniqId) {
    return ClientResolverUtils.addLongToHash(memberId, uniqId);
  }

  @Override
  public int getDSFID() {
    return TRANSACTION_ID;
  }

  @Override
  public final void toData(final DataOutput out) throws IOException {
    InternalDataSerializer.writeUnsignedVL(this.memberId, out);
    InternalDataSerializer.writeUnsignedVL(this.uniqId, out);
  }

  @Override
  public final void fromData(final DataInput in) throws IOException,
      ClassNotFoundException {
    this.memberId = InternalDataSerializer.readUnsignedVL(in);
    this.uniqId = (int)InternalDataSerializer.readUnsignedVL(in);
  }

  public static final TXId createFromData(final DataInput in)
      throws IOException, ClassNotFoundException {
    final long memberId = InternalDataSerializer.readUnsignedVL(in);
    final int uniqId = (int)InternalDataSerializer.readUnsignedVL(in);
    return TXId.valueOf(memberId, uniqId);
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }

  @Override
  public int compareTo(TXId other) {
    long thisValue = memberId + uniqId;
    long otherValue = other.memberId + other.uniqId;
    if (thisValue > otherValue)
      return 1;
    else if (thisValue < otherValue)
      return -1;
    else
      return 0;

  }
}
