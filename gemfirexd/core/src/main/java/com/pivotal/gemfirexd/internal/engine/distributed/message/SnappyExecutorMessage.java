package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.execute.DefaultResultCollector;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;

/**
 * Updates the lead/driver node about the blockmanagerid of this executor.
 */
public class SnappyExecutorMessage extends MemberExecutorMessage<Object> {

  public static final short UPDATE_BLOCKMAP_ENTRY = 0x01;
  public static final short REMOVE_BLOCKMAP_ENTRY = (UPDATE_BLOCKMAP_ENTRY << 1);

  private DistributedMember dm;

  private Externalizable blockManagerId;

  private short flags = 0x0;

  public SnappyExecutorMessage(Externalizable blockId) {
    super(new DefaultResultCollector(), null, false, true);
    this.dm = Misc.getMyId();
    if (blockId == null) {
      this.flags |= REMOVE_BLOCKMAP_ENTRY;
    } else {
      this.flags |= UPDATE_BLOCKMAP_ENTRY;
      this.blockManagerId = blockId;
    }
  }

  /**
   * Default constructor for deserialization. Not to be invoked directly.
   */
  public SnappyExecutorMessage() {
    super(true);
  }

  @Override
  public Set<DistributedMember> getMembers() {
    return LeadNodeExecutorMsg.getLead();
  }

  @Override
  public void postExecutionCallback() {
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
    InternalDistributedMember m = this.getSenderForReply();
    CallbackFactoryProvider.getClusterCallbacks().updateBlockMap(this.dm, this.blockManagerId);
    this.lastResult(null);
    this.lastResultSent = true;
  }

  @Override
  protected SnappyExecutorMessage clone() {
    final SnappyExecutorMessage msg = new SnappyExecutorMessage(this.blockManagerId);
    return msg;
  }

  @Override
  public byte getGfxdID() {
    return UPDATE_SPARK_EXE_MSG;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.flags = DataSerializer.readShort(in);
    this.dm = DataSerializer.readObject(in);
    if ((this.flags & UPDATE_BLOCKMAP_ENTRY) == UPDATE_BLOCKMAP_ENTRY) {
      this.blockManagerId = DataSerializer.readObject(in);
    }
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeShort(this.flags, out);
    DataSerializer.writeObject(this.dm, out);
    if ((this.flags & UPDATE_BLOCKMAP_ENTRY) == UPDATE_BLOCKMAP_ENTRY) {
      DataSerializer.writeObject(this.blockManagerId, out);
    }
  }

}
