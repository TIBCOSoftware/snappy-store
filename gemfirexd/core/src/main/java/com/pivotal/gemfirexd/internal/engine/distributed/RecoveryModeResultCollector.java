package com.pivotal.gemfirexd.internal.engine.distributed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.message.PersistentStateInRecoveryMode;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

public class RecoveryModeResultCollector extends ArrayList<Object> implements
    GfxdResultCollector<Object> {


  private HashMap<DistributedMember, ArrayList<Object>> memberNPartsMap;

  protected final GfxdResultCollectorHelper helper;
  protected boolean throwException = false;

  public RecoveryModeResultCollector() {
    this.helper = new GfxdResultCollectorHelper();
    memberNPartsMap = new HashMap<>();
  }

  public final Set<DistributedMember> getResultMembers() {
    return this.helper.getResultMembers();
  }

  public final boolean setupContainersToClose(
      Collection<GemFireContainer> containers, GemFireTransaction tran) {
    // non-streaming collector will not have anything to do with releasing locks
    return false;
  }

  public GfxdResultCollectorHelper getStreamingHelper() {
    return null;
  }

  public ArrayList<Object> getResult() throws FunctionException {
    if (GemFireXDUtils.TraceRecoveryMode) {
      throw new AssertionError("unexpected Throwable ");
    }
    return this;
  }

  public ArrayList<Object> getResult(long timeout, TimeUnit unit)
      throws FunctionException {
    if (GemFireXDUtils.TraceRecoveryMode) {
      if (this.throwException) {
        throw new AssertionError("unexpected Throwable ");
      }
    }
    return this;
  }

  public void setNumRecipients(int n) {
  }

  public final void setResultMembers(Set<DistributedMember> members) {
    this.helper.setResultMembers(members);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public GfxdResultCollector<Object> cloneCollector() {
    return new RecoveryModeResultCollector();
  }

  public void setProcessor(ReplyProcessor21 processor) {
    // nothing to be done for non-streaming collector
  }

  public ReplyProcessor21 getProcessor() {
    // not required to be implemented for non-streaming collector
    return null;
  }

  public void setException(Throwable exception) {
    if (GemFireXDUtils.TraceRSIter) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RSITER, toString()
          + "#processException: from member [" + Misc.getGemFireCache().getMyId() + "] got exception", exception);
    }
  }


  public void endResults() {
    assert(memberNPartsMap.entrySet().isEmpty() == false);
    for (Map.Entry<DistributedMember, ArrayList<Object>> entry : memberNPartsMap.entrySet()) {
      RecoveryModeResultHolder.PersistentStateInRMMetadata metadata = null;
      InternalDistributedMember member;
      HashMap<String, Integer> prToNumBuckets;
      HashSet<String> replicatedRegions;
      boolean isServer;
      ArrayList<Object> allCatalogObjects = new ArrayList<>();
      ArrayList<String> otherExtractedDDLText = new ArrayList<>();
      ArrayList<PersistentStateInRecoveryMode.RecoveryModePersistentView> allRegionViews = new ArrayList<>();

      for (Object obj : entry.getValue()) {
        switch (obj.getClass().getSimpleName()) {
          case "PersistentStateInRMMetadata":
            metadata = (RecoveryModeResultHolder.PersistentStateInRMMetadata)obj;
            break;

          case "PersistentStateInRMCatalogObjectsList":
            allCatalogObjects
                .addAll(((RecoveryModeResultHolder.PersistentStateInRMCatalogObjectsList)obj)
                    .getCatalogObjects());
            break;

          case "PersistentStateInRMOtherDDLsList":
            otherExtractedDDLText
                .addAll(((RecoveryModeResultHolder.PersistentStateInRMOtherDDLsList)obj)
                    .getOtherExtractedDDLText());
            break;

          case "PersistentStateInRMAllRegionViews":
            allRegionViews
                .addAll(((RecoveryModeResultHolder.PersistentStateInRMAllRegionViews)obj)
                    .getAllRegionView());
            break;

          default: SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RECOVERY_MODE,
              "Found illegal object " + obj);
        }
      }
      if (metadata == null) throw new AssertionError("RecoveryModeResultCollector could not properly" +
          " gather bits of PersistentStateInRecoveryMode");
      member = metadata.getMember();
      for (PersistentStateInRecoveryMode.RecoveryModePersistentView view : allRegionViews) {
        view.setMember(member);
      }
      prToNumBuckets = metadata.getPrToNumBuckets();
      replicatedRegions = metadata.getReplicatedRegions();
      isServer = metadata.isServer();

      PersistentStateInRecoveryMode persistentStateInRecoveryMode =
          new PersistentStateInRecoveryMode(member, allRegionViews, allCatalogObjects, otherExtractedDDLText,
              prToNumBuckets, replicatedRegions, isServer);
      add(persistentStateInRecoveryMode);
    }
  }


  public void clearResults() {
  }

  public void addResult(DistributedMember memberID,
      Object resultOfSingleExecution) {

    if (GemFireXDUtils.TraceRecoveryMode) {
      if (resultOfSingleExecution instanceof Throwable) {
        throwException = true;
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RECOVERY_MODE,
            "RecoveryModeResultCollector received unexpected throwable in addResult "
                + "from member " + memberID,
            (Throwable)resultOfSingleExecution);
      }
    }
    assert (resultOfSingleExecution != null);
      ArrayList<Object> list = memberNPartsMap.get(memberID);
      if (list != null) {
        list.add(resultOfSingleExecution);
      } else {
        list = new ArrayList<>();
        list.add(resultOfSingleExecution);
        memberNPartsMap.put(memberID, list);
      }
  }
}
