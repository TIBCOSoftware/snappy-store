package com.pivotal.gemfirexd.internal.engine.sql.execute;

import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.RecoveryModeResultHolder;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.PersistentStateInRecoveryMode;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class RecoveredMetadataRequestMessage extends MemberExecutorMessage {
  public RecoveredMetadataRequestMessage(
      ResultCollector collector) {
    super(collector, null, false, true);
  }

  private enum ListType {
    CATALOG_OBJECTS,
    OTHER_DDLS,
    REGION_VIEWS
  }


  private final Integer CATALOG_OBJECTS_SUBLIST_SIZE = 25;
  private final Integer OTHER_DDLS_SUBLIST_SIZE = 100;
  private final Integer REGION_VIEWS_SUBLIST_SIZE = 30;

  /**
   * Default constructor for deserialization. Not to be invoked directly.
   */
  public RecoveredMetadataRequestMessage() {
    super(true);
  }

  @Override
  protected void execute() throws Exception {
    GemFireXDUtils.waitForNodeInitialization();
    sendPersistentStateMsg(Misc.getMemStore().getPersistentStateMsg());
    this.lastResultSent = true;
  }

  @Override
  public Set<DistributedMember> getMembers() {
    final Set<DistributedMember> set = new HashSet<>();
    Set<DistributedMember> members = GemFireXDUtils.getGfxdAdvisor().adviseAllNodes(null);
    members.stream().filter(x -> {
      final GfxdDistributionAdvisor.GfxdProfile other = GemFireXDUtils
          .getGfxdProfile(x);
      return (other.getVMKind().isLocator() || other.getVMKind().isStore());
    }).forEach(x -> set.add(x));
    return set;
  }

  private void sendPersistentStateMsg(PersistentStateInRecoveryMode persistentStateMsg) {
    RecoveryModeResultHolder.PersistentStateInRMMetadata resultHolderMetadata = new RecoveryModeResultHolder.PersistentStateInRMMetadata(persistentStateMsg.getMember(), persistentStateMsg.getPrToNumBuckets(), persistentStateMsg.getReplicatedRegions(), persistentStateMsg.isServer());
    this.sendResult(resultHolderMetadata);
    sendList(persistentStateMsg.getCatalogObjects(), CATALOG_OBJECTS_SUBLIST_SIZE, ListType.CATALOG_OBJECTS);
    sendList(persistentStateMsg.getOtherDDLs(), OTHER_DDLS_SUBLIST_SIZE, ListType.OTHER_DDLS);
    sendList(persistentStateMsg.getAllRegionViews(), REGION_VIEWS_SUBLIST_SIZE, ListType.REGION_VIEWS);
  }

  public <T> void sendList(ArrayList<T> arrayList, Integer partSize, ListType type) {
    int n = arrayList.size();
    for (int i = 0; i < n; i += partSize) {
      ArrayList<T> arrayChunk = new ArrayList<>(arrayList.subList(i, Math.min(n, i + partSize)));

      if (type == ListType.CATALOG_OBJECTS) {
        sendResult(new RecoveryModeResultHolder.PersistentStateInRMCatalogObjectsList((java.util.ArrayList<java.lang.Object>)arrayChunk));
      } else if (type == ListType.OTHER_DDLS) {
        sendResult(new RecoveryModeResultHolder.PersistentStateInRMOtherDDLsList((ArrayList<String>)arrayChunk));
      } else if (type == ListType.REGION_VIEWS) {
        // RegionViews
        if ((i + partSize) < n) {
          this.sendResult((new RecoveryModeResultHolder.PersistentStateInRMAllRegionViews((ArrayList<PersistentStateInRecoveryMode
              .RecoveryModePersistentView>)arrayChunk)));
        } else { // last chunk of the list
          this.lastResult(new RecoveryModeResultHolder.PersistentStateInRMAllRegionViews((ArrayList<PersistentStateInRecoveryMode
            .RecoveryModePersistentView>)arrayChunk));
        }
      }
    }
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
    return false;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  protected GfxdFunctionMessage clone() {
    return null;
  }
}
