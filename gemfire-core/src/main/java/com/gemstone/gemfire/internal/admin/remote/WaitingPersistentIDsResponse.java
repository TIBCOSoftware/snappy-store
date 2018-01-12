package com.gemstone.gemfire.internal.admin.remote;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberPattern;

public class WaitingPersistentIDsResponse extends AdminResponse {

  private Set<PersistentID> waitingIds;

  public WaitingPersistentIDsResponse() {
  }

  public WaitingPersistentIDsResponse(Set<PersistentID> waitingIds,
       InternalDistributedMember recipient) {
    this.waitingIds = waitingIds;
    this.setRecipient(recipient);
  }

  public int getDSFID() {
    return WAITING_PERSISTENT_IDS_RESPONSE;
  }

  @Override
  protected void process(DistributionManager dm) {
    super.process(dm);
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    int size = in.readInt();
    waitingIds = new HashSet<PersistentID>(size);
    for(int i =0; i < size; i++) {
      PersistentMemberPattern pattern = new PersistentMemberPattern();
      InternalDataSerializer.invokeFromData(pattern, in);
      waitingIds.add(pattern);
    }
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    super.toData(out);
    out.writeInt(waitingIds.size());
    for(PersistentID pattern : waitingIds) {
      InternalDataSerializer.invokeToData(pattern, out);
    }
  }

  public Set<PersistentID> getWaitingIds() {
    return waitingIds;
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  @Override
  public String toString() {
    return getClass().getName() + ": waiting=" + waitingIds ;
  }

}
