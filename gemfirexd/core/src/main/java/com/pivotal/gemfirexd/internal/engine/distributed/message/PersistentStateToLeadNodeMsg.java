package com.pivotal.gemfirexd.internal.engine.distributed.message;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdResultCollector;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class PersistentStateToLeadNodeMsg extends MemberExecutorMessage<Object> {

  public static class DiskPersistentView implements DataSerializable{

    private String regionPath;
    private final HashSet<PersistentMemberID> onlineMembers = new HashSet<>();
    private final HashSet<PersistentMemberID> offlineMembers = new HashSet<>();
    private final HashSet<PersistentMemberID> equalMembers = new HashSet<>();

    public DiskPersistentView(final String regionFullPath,
      final Set<PersistentMemberID> onlines,
      final Set<PersistentMemberID> offlines,
      final Set<PersistentMemberID> equals) {
      regionPath = regionFullPath;
      if (onlines != null && !onlines.isEmpty()) {
        onlineMembers.addAll(onlines);
      }
      if (offlines != null && !offlines.isEmpty()) {
        offlineMembers.addAll(offlines);
      }
      if (equals != null && !equals.isEmpty()) {
        equalMembers.addAll(equals);
      }
    }

    public DiskPersistentView () {}

    @Override
    public void toData(DataOutput out) throws IOException {
      out.writeUTF(regionPath);
      out.writeInt(onlineMembers.size());
      for(PersistentMemberID member : onlineMembers) {
        DataSerializer.writeObject(member, out);
      }
      out.writeInt(offlineMembers.size());
      for(PersistentMemberID member: offlineMembers) {
        DataSerializer.writeObject(member, out);
      }

      out.writeInt(equalMembers.size());
      for(PersistentMemberID member : equalMembers) {
        DataSerializer.writeObject(member, out);
      }
    }

    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      this.regionPath = in.readUTF();
      int cnt = in.readInt();
      for (int i=0; i < cnt; i++) {
        this.onlineMembers.add(DataSerializer.readObject(in));
      }
      cnt = in.readInt();
      for (int i=0; i < cnt; i++) {
        this.offlineMembers.add(DataSerializer.readObject(in));
      }
      cnt = in.readInt();
      for (int i=0; i < cnt; i++) {
        this.equalMembers.add(DataSerializer.readObject(in));
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("region: ");
      sb.append(this.regionPath);
      sb.append("onlines: ");
      sb.append(this.onlineMembers);
      sb.append(" : offlines: ");
      sb.append(this.offlineMembers);
      sb.append(" : equals: ");
      sb.append(this.equalMembers);
      return sb.toString();
    }
  }

  private final ArrayList<DiskPersistentView>
      allRegionView = new ArrayList<>();

  private InternalDistributedMember member = null;

  public PersistentStateToLeadNodeMsg(final ResultCollector<Object, Object> rc) {
    super(rc, null, false, true);
    member = Misc.getMyId();
  }

  public PersistentStateToLeadNodeMsg() {
    super(true);
  }

  public void addView(DiskPersistentView v) {
    this.allRegionView.add(v);
  }

  @Override
  public Set<DistributedMember> getMembers() {
    return Misc.getLeadNode();
  }

  @Override
  public void postExecutionCallback() {

  }

  @Override
  public byte getGfxdID() {
    return LEAD_DISK_STATE_MSG;
  }

  @Override
  public boolean isHA() {
    return true;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  protected void execute() throws Exception {

  }

  @Override
  protected GfxdFunctionMessage<Object> clone() {
    return null;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.member = DataSerializer.readObject(in);
    this.allRegionView.addAll(DataSerializer.readArrayList(in));
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeObject(this.member, out);
    DataSerializer.writeArrayList(this.allRegionView, out);
  }

  private transient String toStringString = null;

  @Override
  public void appendFields(final StringBuilder sb) {
    sb.append("member: ");
    sb.append(this.member);
    sb.append("\n");
    for (DiskPersistentView e : this.allRegionView) {
      sb.append(e);
    }
    sb.append("\n");
  }
}
