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

package com.gemstone.gemfire.internal.cache.control;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.control.ResourceManager;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisee;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.HighPriorityDistributionMessage;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.UpdateAttributesProcessor;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceType;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds.MemoryState;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * The advisor associated with a {@link ResourceManager}.  Allows knowledge of
 * remote {@link ResourceManager} state and distribution of local {@link ResourceManager} state.
 * 
 * @author Mitch Thomas
 * @since 6.0
 */
public class ResourceAdvisor extends DistributionAdvisor {
  /**
   * Message used to push event updates to remote VMs
   */
  public static class ResourceProfileMessage extends
      HighPriorityDistributionMessage {
    private volatile ResourceManagerProfile profile;
    private volatile int processorId;

    /**
     * Default constructor used for de-serialization (used during receipt)
     */
    public ResourceProfileMessage() {}

    /**
     * Constructor used to send profiles to other members.
     * 
     * @param recips Members to send the profile to.
     * @param profile Profile to send.
     */
    private ResourceProfileMessage(final Set<InternalDistributedMember> recips,
        final ResourceManagerProfile profile) {
      setRecipients(recips);
      this.processorId = 0;
      this.profile = profile;
    }

    /* (non-Javadoc)
     * @see com.gemstone.gemfire.distributed.internal.DistributionMessage#process(com.gemstone.gemfire.distributed.internal.DistributionManager)
     */
    @Override
    protected void process(DistributionManager dm) {
      Throwable thr = null;
      final LogWriterI18n logger = dm.getLoggerI18n();
      ResourceManagerProfile p = null;
      try {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if (cache != null && !cache.isClosed()) {
          final ResourceAdvisor ra = cache.getResourceManager().getResourceAdvisor();
          if (this.profile != null) {
            // Early reply to avoid waiting for the following putProfile call
            // to fire (remote) listeners so that the origin member can proceed with
            // firing its (local) listeners

            ra.putProfile(profile);
          }
        } else {
          if (logger.fineEnabled()) {
            logger.fine("no cache" + this);
          }
        }
      } catch (CancelException e) {
        if (logger.fineEnabled()) {
          logger.fine("cache closed" + this);
        }
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        thr = t;
      } finally {
        if (thr != null) {
          dm.getCancelCriterion().checkCancelInProgress(null);
          logger.info(LocalizedStrings.ResourceAdvisor_MEMBER_CAUGHT_EXCEPTION_PROCESSING_PROFILE,
              new Object[] {p, toString()}, thr);
        }
      }
    }

    /* (non-Javadoc)
     * @see com.gemstone.gemfire.internal.DataSerializableFixedID#getDSFID()
     */
    @Override
    public int getDSFID() {
      return RESOURCE_PROFILE_MESSAGE;
    }

    @Override
    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      this.processorId = in.readInt();
      this.profile = new ResourceManagerProfile();
      InternalDataSerializer.invokeFromData(this.profile, in);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      out.writeInt(this.processorId);
      InternalDataSerializer.invokeToData(this.profile, out);
    }

    /**
     * Send profiles to the provided members
     * @param irm The resource manager which is requesting distribution
     * @param recips The recipients of the message
     * @param profile Profile to send in this message
     * @throws InterruptedException
     * @throws ReplyException
     */
    public static void send(final InternalResourceManager irm, Set<InternalDistributedMember> recips,
        ResourceManagerProfile profile) {
      final DM dm = irm.getResourceAdvisor().getDistributionManager();
      ResourceProfileMessage r = new ResourceProfileMessage(recips, profile);
      dm.putOutgoing(r);
    }

    @Override
    public String getShortClassName() {
      return "ResourceProfileMessage";
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(getShortClassName())
      .append(" (processorId=").append(this.processorId)
      .append("; profile=").append(this.profile)
      .append(")");
      return sb.toString();
    }
  }

  /**
   * @param advisee The owner of this advisor 
   * @see ResourceManager
   */
  private ResourceAdvisor(DistributionAdvisee advisee) {
    super(advisee);
  }
  
  public static ResourceAdvisor createResourceAdvisor(DistributionAdvisee advisee) {
    ResourceAdvisor advisor = new ResourceAdvisor(advisee);
    advisor.initialize();
    return advisor;
  }
  
  @Override
  protected Profile instantiateProfile(InternalDistributedMember memberId,
      int version) {
    return new ResourceManagerProfile(memberId, version);
  }
  
  private InternalResourceManager getResourceManager() {
    return ((GemFireCacheImpl) getAdvisee()).getResourceManager(false);
  }

  @SuppressWarnings("synthetic-access")
  @Override
  protected boolean evaluateProfiles(final Profile newProfile,
      final Profile oldProfile) {

    ResourceManagerProfile oldRMProfile = (ResourceManagerProfile) oldProfile;
    ResourceManagerProfile newRMProfile = (ResourceManagerProfile) newProfile;
    
    List<ResourceEvent> eventsToDeliver = new ArrayList<ResourceEvent>();
    
    if (oldRMProfile == null) {
      eventsToDeliver.add(new MemoryEvent(ResourceType.HEAP_MEMORY, MemoryState.DISABLED, newRMProfile.heapState, newRMProfile.getDistributedMember(),
          newRMProfile.heapBytesUsed, false, newRMProfile.heapThresholds));
      eventsToDeliver.add(new MemoryEvent(ResourceType.OFFHEAP_MEMORY, MemoryState.DISABLED, newRMProfile.offHeapState, newRMProfile.getDistributedMember(),
          newRMProfile.offHeapBytesUsed, false, newRMProfile.offHeapThresholds));
      
    } else {
      if (oldRMProfile.heapState != newRMProfile.heapState) {
        eventsToDeliver.add(new MemoryEvent(ResourceType.HEAP_MEMORY, oldRMProfile.heapState, newRMProfile.heapState, newRMProfile.getDistributedMember(),
            newRMProfile.heapBytesUsed, false, newRMProfile.heapThresholds));
      }
  
      if (newRMProfile.heapState == MemoryState.DISABLED) {
        newRMProfile.setHeapData(oldRMProfile.heapBytesUsed, oldRMProfile.heapState, oldRMProfile.heapThresholds);
      }
      
      if (oldRMProfile.offHeapState != newRMProfile.offHeapState) {
        eventsToDeliver.add(new MemoryEvent(ResourceType.OFFHEAP_MEMORY, oldRMProfile.offHeapState, newRMProfile.offHeapState, newRMProfile.getDistributedMember(),
            newRMProfile.offHeapBytesUsed, false, newRMProfile.offHeapThresholds));
      }
      
      if (newRMProfile.offHeapState == MemoryState.DISABLED) {
        newRMProfile.setOffHeapData(oldRMProfile.offHeapBytesUsed, oldRMProfile.offHeapState, oldRMProfile.offHeapThresholds);
      }
    }

    for (ResourceEvent event : eventsToDeliver) {
      getResourceManager().deliverEventFromRemote(event);
    }
    
    return true;
  }

  @Override
  public String toString() {
    return new StringBuilder().append("ResourceAdvisor for ResourceManager " 
        + getAdvisee()).toString();
  }
  
  /**
   * Profile which shares state with other ResourceManagers.
   * The data available in this profile should be enough to 
   * deliver a {@link MemoryEvent} for any of the CRITICAL {@link MemoryState}s 
   * @author Mitch Thomas
   * @author David Hoots
   * @since 6.0
   */
  public static class ResourceManagerProfile extends Profile {
    //Resource manager related fields
    private long heapBytesUsed;
    private MemoryState heapState;
    private MemoryThresholds heapThresholds;
    
    private long offHeapBytesUsed;
    private MemoryState offHeapState;
    private MemoryThresholds offHeapThresholds;
    
    // Constructor for de-serialization
    public ResourceManagerProfile() {}
    
    // Constructor for sending purposes
    public ResourceManagerProfile(InternalDistributedMember memberId,
        int version) {
      super(memberId, version);
    }
    
    public synchronized ResourceManagerProfile setHeapData(final long heapBytesUsed, final MemoryState heapState,
        final MemoryThresholds heapThresholds) {
      this.heapBytesUsed = heapBytesUsed;
      this.heapState = heapState;
      this.heapThresholds = heapThresholds;
      return this;
    }

    public synchronized ResourceManagerProfile setOffHeapData(final long offHeapBytesUsed, final MemoryState offHeapState,
        final MemoryThresholds offHeapThresholds) {
      this.offHeapBytesUsed = offHeapBytesUsed;
      this.offHeapState = offHeapState;
      this.offHeapThresholds = offHeapThresholds;
      return this;
    }
    
    public synchronized MemoryEvent createDisabledMemoryEvent(ResourceType resourceType) {
      if (resourceType == ResourceType.HEAP_MEMORY) {
        return new MemoryEvent(ResourceType.HEAP_MEMORY, this.heapState, MemoryState.DISABLED, getDistributedMember(), this.heapBytesUsed,
            false, this.heapThresholds);
      }
      
      return new MemoryEvent(ResourceType.OFFHEAP_MEMORY, this.offHeapState, MemoryState.DISABLED, getDistributedMember(), this.offHeapBytesUsed,
          false, this.offHeapThresholds);
    }
    
    /**
     * Used to process incoming Resource Manager profiles. A reply is expected
     * to contain a profile with state of the local Resource Manager.
     * 
     * @since 6.0
     */
    @Override
    public void processIncoming(DistributionManager dm, String adviseePath,
        boolean removeProfile, boolean exchangeProfiles,
        final List<Profile> replyProfiles, LogWriterI18n logger) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null && !cache.isClosed()) {
        handleDistributionAdvisee(cache, removeProfile,
            exchangeProfiles, replyProfiles);
      }
    }

    @Override
    public StringBuilder getToStringHeader() {
      return new StringBuilder("ResourceAdvisor.ResourceManagerProfile");
    }

    @Override
    public void fillInToString(StringBuilder sb) {
      super.fillInToString(sb);
      synchronized (this) {
        sb.append("; heapState=").append(this.heapState)
        .append("; heapBytesUsed=").append(this.heapBytesUsed)
        .append("; heapThresholds=").append(this.heapThresholds)
        .append("; offHeapState=").append(this.offHeapState)
        .append("; offHeapBytesUsed=").append(this.offHeapBytesUsed)
        .append("; offHeapThresholds=").append(this.offHeapThresholds);
      }
    }
    
    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);
      
      final long heapBytesUsed = in.readLong();
      MemoryState heapState = MemoryState.fromData(in);
      MemoryThresholds heapThresholds = MemoryThresholds.fromData(in);
      setHeapData(heapBytesUsed, heapState, heapThresholds);
      
      final long offHeapBytesUsed = in.readLong();
      MemoryState offHeapState = MemoryState.fromData(in);
      MemoryThresholds offHeapThresholds = MemoryThresholds.fromData(in);
      setOffHeapData(offHeapBytesUsed, offHeapState, offHeapThresholds);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      final long heapBytesUsed; final MemoryState heapState; final MemoryThresholds heapThresholds;
      final long offHeapBytesUsed; final MemoryState offHeapState; final MemoryThresholds offHeapThresholds;
      synchronized(this) {
        heapBytesUsed = this.heapBytesUsed;
        heapState = this.heapState;
        heapThresholds = this.heapThresholds;
        
        offHeapBytesUsed = this.offHeapBytesUsed;
        offHeapState = this.offHeapState;
        offHeapThresholds = this.offHeapThresholds;
      }
      super.toData(out);
      
      out.writeLong(heapBytesUsed);
      heapState.toData(out);
      heapThresholds.toData(out);
      
      out.writeLong(offHeapBytesUsed);
      offHeapState.toData(out);
      offHeapThresholds.toData(out);
    }
    
    @Override
    public int getDSFID() {
      return RESOURCE_MANAGER_PROFILE; 
    }
    
    public synchronized MemoryState getHeapState() {
      return this.heapState;
    }
    
    public synchronized MemoryState getoffHeapState() {
      return this.offHeapState;
    }
  }

  /**
   * Get set of members whose {@linkplain ResourceManager#setCriticalHeapPercentage(float)
   * critical heap threshold} has been met or exceeded.  The set does not include the local VM.
   * The mutability of this set only effects the elements in the set, not the state
   * of the members.
   * @return a mutable set of members in the critical state otherwise {@link Collections#EMPTY_SET}
   */
  public Set<InternalDistributedMember> adviseCriticalMembers() {
    return adviseFilter(new Filter() {
      @Override
      public boolean include(Profile profile) {
        ResourceManagerProfile rmp = (ResourceManagerProfile)profile;
        return rmp.getHeapState().isCritical();
      }});
  }

  public final boolean isHeapCritical(final InternalDistributedMember member) {
    ResourceManagerProfile rmp = (ResourceManagerProfile)getProfile(member);
    return rmp != null ? rmp.getHeapState().isCritical() : false;
  }

  public synchronized void updateRemoteProfile() {
    Set<InternalDistributedMember> recips = adviseGeneric();
    ResourceManagerProfile profile = new ResourceManagerProfile(getDistributionManager().getId(), incrementAndGetVersion());
    getResourceManager().fillInProfile(profile);
    ResourceProfileMessage.send(getResourceManager(), recips, profile);
  }

  @Override
  protected void profileRemoved(Profile profile) {
    ResourceManagerProfile oldp = (ResourceManagerProfile) profile;
    getResourceManager().deliverEventFromRemote(oldp.createDisabledMemoryEvent(ResourceType.HEAP_MEMORY));
    getResourceManager().deliverEventFromRemote(oldp.createDisabledMemoryEvent(ResourceType.OFFHEAP_MEMORY));
  }

  @Override
  public void close() {
    new UpdateAttributesProcessor(this.getAdvisee(), true).distribute(false);
    super.close();
  }
}
