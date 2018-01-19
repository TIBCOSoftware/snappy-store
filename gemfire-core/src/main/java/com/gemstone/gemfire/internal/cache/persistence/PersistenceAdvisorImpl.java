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
package com.gemstone.gemfire.internal.cache.persistence;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.persistence.ConflictingPersistentDataException;
import com.gemstone.gemfire.cache.persistence.RevokedPersistentDataException;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor.Profile;
import com.gemstone.gemfire.distributed.internal.MembershipListener;
import com.gemstone.gemfire.distributed.internal.ProfileListener;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.GemFireUtilLauncher;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor.CacheProfile;
import com.gemstone.gemfire.internal.cache.CacheDistributionAdvisor.InitialImageAdvice;
import com.gemstone.gemfire.internal.cache.DiskRegionStats;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.ProxyBucketRegion;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberManager.MemberRevocationListener;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.process.StartupStatus;
import com.gemstone.gemfire.internal.util.TransformUtils;

/**
 * @author dsmith
 *
 */
public class PersistenceAdvisorImpl implements PersistenceAdvisor {
  
  protected CacheDistributionAdvisor advisor;
  private DistributedLockService dl;
  protected String regionPath;
  protected PersistentMemberView storage;
  protected volatile boolean online = false;
  private volatile Set<PersistentStateListener> listeners = Collections.emptySet();
  protected final LogWriterI18n logger;
  private DiskRegionStats stats;
  private PersistentMemberManager memberManager;
  private ProfileChangeListener listener;
  private volatile boolean initialized;
  private volatile boolean shouldUpdatePersistentView;
  protected volatile boolean isClosed;
  private volatile boolean holdingTieLock;
  private volatile boolean doNotWait = false;
  
  private Set<PersistentMemberID> recoveredMembers;
  private Set<PersistentMemberID> removedMembers = new HashSet<PersistentMemberID>();
  private Set<PersistentMemberID> equalMembers;
  private volatile Set<PersistentMemberID> allMembersWaitingFor;
  private volatile Set<PersistentMemberID> offlineMembersWaitingFor;
  protected final Object lock;
  private static PersistenceAdvisorObserver observer = null;
  
  public static final boolean TRACE = Boolean.getBoolean("gemfire.TRACE_PERSISTENCE_ADVISOR");
  
  public PersistenceAdvisorImpl(CacheDistributionAdvisor advisor, DistributedLockService dl, PersistentMemberView storage, String regionPath, DiskRegionStats diskStats, PersistentMemberManager memberManager) {
    this.advisor = advisor;
    this.dl = dl;
    this.regionPath = regionPath;
    this.storage = storage;
    this.stats = diskStats;
    this.listener = new ProfileChangeListener();
    logger = advisor.getLogWriter();
    this.memberManager = memberManager;
    
    //Prevent membership changes while we are persisting the membership view
    //online. TODO prpersist is this the best thing to sync on?
    //If we synchronize on something else, we need to be careful about
    //lock ordering because the membership notifications are called
    //with the advisor lock held.
    this.lock = advisor;
    
    //Remember which members we know about because of what
    //we have persisted
    //We will later use this to handle updates from peers.
    recoveredMembers = getPersistedMembers();
    
    //To prevent races if we crash during initialization,
    //mark equal members as online before we initialize. We will
    //still report these members as equal, but if we crash and recover
    //they will no longer be considered equal.
    equalMembers = new HashSet<PersistentMemberID>(storage.getOfflineAndEqualMembers());
    for(PersistentMemberID id : equalMembers) {
      storage.memberOnline(id);
    }
  }
  
  public void initialize() {
    if(initialized) {
      return;
    }
    
    if(wasAboutToDestroy()) {
      logger.info(LocalizedStrings.PersistenceAdvisorImpl_FINISHING_INCOMPLETE_DESTROY, regionPath);
      finishPendingDestroy();
    }
    
    advisor.addProfileChangeListener(listener);
    
    Set<PersistentMemberPattern> revokedMembers = this.memberManager.addRevocationListener(listener, storage.getRevokedMembers());
    
    for(PersistentMemberPattern pattern : revokedMembers) {
      memberRevoked(pattern);
    }

    // Start logging changes to the persistent view
    startMemberLogging();
    
    initialized = true;
  }  
 
  /**
   * Adds a PersistentStateListener whose job is to log changes in the persistent view.
   */
  protected void startMemberLogging() {
    this.addListener(new PersistentStateListener.PersistentStateAdapter() {
      /**
       * A persistent member has gone offline.  Log the offline member and log which persistent members
       * are still online (the current persistent view).
       */
      @Override
      public void memberOffline(InternalDistributedMember member,
          PersistentMemberID persistentID) {
        if(PersistenceAdvisorImpl.this.logger.fineEnabled()) {
          Set<String> onlineMembers = new HashSet<String>();
          
          Set<PersistentMemberID> members = new HashSet<PersistentMemberID>();
          members.addAll(PersistenceAdvisorImpl.this.advisor.adviseInitializedPersistentMembers().values());
          members.remove(persistentID);
          
          TransformUtils.transform(members, onlineMembers, TransformUtils.persistentMemberIdToLogEntryTransformer);
          
          PersistenceAdvisorImpl.this.logger.info(LocalizedStrings.PersistenceAdvisorImpl_PERSISTENT_VIEW,
              new Object[] {PersistenceAdvisorImpl.this.regionPath,TransformUtils.persistentMemberIdToLogEntryTransformer.transform(persistentID),onlineMembers});          
        }
      }
    });    
  }
  
  public boolean acquireTieLock() {
    holdingTieLock = dl.lock("PERSISTENCE_" + regionPath, 0, -1);
    return holdingTieLock;
  }
  
  public void releaseTieLock() {
    if(holdingTieLock) {
      dl.unlock("PERSISTENCE_" + regionPath);
      holdingTieLock = false;
    }
  }

  public PersistentStateQueryResults getMyStateOnMembers(
      Set<InternalDistributedMember> members) throws ReplyException {

    PersistentStateQueryResults results = PersistentStateQueryMessage
    .send(members, advisor.getDistributionManager(), regionPath, storage
        .getMyPersistentID(), storage.getMyInitializingID());
    
    return results;
  }

  /**
   * Return what state we have persisted for a given peer's id.
   */
  public PersistentMemberState getPersistedStateOfMember(PersistentMemberID id) {
    if(isRevoked(id)) {
      return PersistentMemberState.REVOKED;
    }
    
    //If the peer is marked as equal, indicate they are equal
    if(equalMembers != null && equalMembers.contains(id)) {
      return PersistentMemberState.EQUAL;
    }
    
    //If we have a member that is marked as online that
    //is an older version of the peers id, tell them they are online
    for(PersistentMemberID online : storage.getOnlineMembers()) {
      if(online.isOlderOrEqualVersionOf(id)) {
        return PersistentMemberState.ONLINE; 
      }
    }

    for(PersistentMemberID offlieOrEqual: storage.getOfflineAndEqualMembers()) {
      if(offlieOrEqual.isOlderOrEqualVersionOf(id)) {
        return PersistentMemberState.EQUAL;
      }
    }
    //If we have a member that is marked as offline that
    //is a newer version of the peers id, tell them they are online
    for(PersistentMemberID offline : storage.getOfflineMembers()) {
      if(id.isOlderOrEqualVersionOf(offline)) {
        return PersistentMemberState.OFFLINE; 
      }
    }
    return null;
  }
  
  public void updateMembershipView(InternalDistributedMember replicate, boolean targetReinitializing) {
    beginUpdatingPersistentView();
    DM dm = advisor.getDistributionManager();
    PersistentMembershipView view = MembershipViewRequest.send(replicate, dm, regionPath, targetReinitializing);
    if(traceOn()) {
      trace(" updating persistent view from " + replicate);
    }
    
    synchronized(lock) {
      PersistentMemberID myId = getPersistentID();
      Map<InternalDistributedMember, PersistentMemberID> peersOnlineMembers = view.getOnlineMembers();
      Set<PersistentMemberID> peersOfflineMembers = view.getOfflineMembers();

      for(PersistentMemberID id : peersOnlineMembers.values()) {
        if(!isRevoked(id) && !removedMembers.contains(id)) {
          if(!id.equals(myId) && !recoveredMembers.remove(id) && !id.diskStoreId.equals(getDiskStoreID())) {
            if(traceOn()) {
              trace(" Processing membership view from peer. Marking " + id + " as online because " + replicate + " says its online");
            }
            storage.memberOnline(id);
          }
        }
      }

      for(PersistentMemberID id : peersOfflineMembers) {
        if(!isRevoked(id) && !removedMembers.contains(id)) {
          //This method is called before the current member is online.
          //if the peer knows about a member that the current member doesn't know
          //about, that means that member must have been added to the DS after
          //the current member went offline. Therefore, that member is *newer* than
          //the current member. So mark that member as online (meaning, online later
          //than the current member).
          if(!id.equals(myId) && !recoveredMembers.remove(id) && !id.diskStoreId.equals(getDiskStoreID())) {
            if(traceOn()) {
              trace(" Processing membership view from peer. Marking " + id + " as online because " + replicate + " says its offline, but we have never seen it");
            }
            storage.memberOnline(id);
          }
        }
      }


      for(PersistentMemberID id  : recoveredMembers) {
        if(traceOn()) {
          trace(" Processing membership view from peer. Removing " + id + " because " + replicate + " doesn't have it");
        }
        storage.memberRemoved(id);
      }
    }
    
    //Update the set of revoked members from the peer
    //This should be called without holding the lock to
    //avoid deadlocks
    Set<PersistentMemberPattern> revokedMembers = view.getRevokedMembers();
    for(PersistentMemberPattern revoked : revokedMembers) {
      memberManager.revokeMember(revoked);
    }
  }
  
  protected boolean isRevoked(PersistentMemberID id) {
    return memberManager.isRevoked(this.regionPath, id);
  }

  /**
   * @throws ReplyException 
   * @throws IOException 
   */
  public void setOnline(boolean didGII, boolean atomicCreation,
      PersistentMemberID newId) throws ReplyException {
    if(online) {
      return;
    }

    if(!didGII) {
      setInitializing(newId);
    }
    
    synchronized(lock) {
      
      //Transition any members that are marked as online, but not actually
      //currently running, to offline.
      Set<PersistentMemberID> membersToMarkOffline = new HashSet<PersistentMemberID>(storage.getOnlineMembers());
      Map<InternalDistributedMember, PersistentMemberID> onlineMembers;
      if(!atomicCreation) {
        onlineMembers = advisor.adviseInitializedPersistentMembers();
      } else {
        //Fix for 41100 - If this is an atomic bucket creation, don't
        //mark our peers, which are concurrently intitializing, as offline
        //they have the exact same data as we do (none), so we are not
        //technically "newer," and this avoids a race where both members
        //can think the other is offline ("older").
        onlineMembers = advisor.advisePersistentMembers();
      }
      membersToMarkOffline.removeAll(onlineMembers.values());
      
      //Another fix for 41100
      //Don't mark equal members as offline if that are currently running.
      //We don't have newer data than these members
      //so this is safe, and it it avoids a race where we mark them offline
      //at this point, and then later they mark us as offline.
      if(equalMembers != null && !equalMembers.isEmpty()) {
        
        //This is slightly hacky. We're looking for a running member that has 
        //the same disk store as our equal members, because all have is a persistent
        //id of the equal members. The persistent id of the running member may be
        //different than what we have marked as equal, because the id in the profile
        //is the new id for the member.
        Collection<PersistentMemberID> allMembers = advisor.advisePersistentMembers().values();
        Set<DiskStoreID> runningDiskStores = new HashSet<DiskStoreID>();
        for(PersistentMemberID mem : allMembers) {
          runningDiskStores.add(mem.diskStoreId);
        }
        //Remove any equal members which are not actually running right now.
        for(Iterator<PersistentMemberID> itr = equalMembers.iterator(); itr.hasNext(); ) {
          PersistentMemberID id = itr.next();
          if(!runningDiskStores.contains(id.diskStoreId)) {
            itr.remove();
          }
        }
        membersToMarkOffline.removeAll(equalMembers);
      }
      for(PersistentMemberID id : membersToMarkOffline) {
        storage.memberOffline(id);
      }
      if(traceOn()) {
        logger.info(LocalizedStrings.DEBUG, "PersistenceAdvisor" + shortDiskStoreId() + " - " + regionPath
                + " persisting the new membership view and ID as online. Online members "
                + storage.getOnlineMembers() + " Offline members "
                + storage.getOfflineMembers() + "Equal members "
                + equalMembers);
      }
      
      storage.setInitialized();
      online = true;
      removedMembers = Collections.emptySet();
    }
    if(stats != null) {
      stats.incInitializations(!didGII);
    }
  }
  
  /**
   * Start listening for persistent view updates and apply any
   * updates that have already happened.
   * 
   * This method should be called after we have decided that there is
   * no conflicting persistent exception.
   * 
   * Fix for bug 44045.
   */
  protected void beginUpdatingPersistentView() {
    synchronized(lock) {
      //Only update the view if it is has not already happened.
      if(!shouldUpdatePersistentView) {
        shouldUpdatePersistentView = true;
        Map<InternalDistributedMember, PersistentMemberID> onlineMembers 
          = advisor.adviseInitializedPersistentMembers();
        for(Map.Entry<InternalDistributedMember, PersistentMemberID>entry 
            : onlineMembers.entrySet()) {
          memberOnline(entry.getKey(), entry.getValue());
        }
      }
    }
  }
  
  public void setInitializing(PersistentMemberID newId) {
    
    beginUpdatingPersistentView();
    
    DM dm = advisor.getDistributionManager();
    
    PersistentMemberID oldId = getPersistentID();
    PersistentMemberID initializingId = getInitializingID();
    
    Set profileUpdateRecipients = advisor.adviseProfileUpdate();
    if(newId == null || 
        (!newId.equals(oldId) && !newId.equals(initializingId))) {
      //If we have not yet prepared the old id, prepare it now.


      //This will only be the case if we crashed
      //while initializing previously. In the case, we are essentially
      //finishing what we started by preparing that ID first. This
      //will remove that ID from the peers.
      if(initializingId != null) {
        if(traceOn()) {
          trace(" We still have an initializing id: " + initializingId +
              "  Telling peers to remove the old id " +
              oldId + " and transitioning this initializing id to old id. recipients "
              + profileUpdateRecipients);
        }
        //TODO prpersist - clean this up
        long viewVersion = -1;
        viewVersion = advisor.startOperation();
        try {
          PrepareNewPersistentMemberMessage.send(profileUpdateRecipients,
              dm, regionPath, oldId, initializingId);
        } finally {
          if (viewVersion != -1) {
            advisor.endOperation(viewVersion);
          }
        }
        oldId = initializingId;
      }

      if(logger.fineEnabled()) {
        logger.fine("Persisting my new persistent ID " + newId);
      }
      storage.setInitializing(newId);
    }
    
    profileUpdateRecipients = advisor.adviseProfileUpdate();
    if(traceOn()) {
      trace("Sending the new ID to peers. They should remove the old id " + oldId + ". Recipients " + profileUpdateRecipients);
    }
    if(newId != null) {
      PrepareNewPersistentMemberMessage.send(profileUpdateRecipients,
          dm, regionPath, oldId,
          newId);
    }
  }

  public PersistentMemberID generatePersistentID() {
    return storage.generatePersistentID(); 
  }

  public PersistentMembershipView getMembershipView() {
    if(!initialized) { 
      return null;
    }
    Set<PersistentMemberID> offlineMembers = getPersistedMembers();
    Map<InternalDistributedMember, PersistentMemberID> onlineMembers = advisor
        .adviseInitializedPersistentMembers();
    offlineMembers.removeAll(onlineMembers.values());
    
    PersistentMemberID myId = getPersistentID();
    if(myId != null) {
      onlineMembers.put(advisor.getDistributionManager().getDistributionManagerId(), myId);
    }
    
    PersistentMembershipView view = new PersistentMembershipView(
        offlineMembers, onlineMembers, memberManager.getRevokedMembers());
    return view;
  }
  
  public Set<PersistentMemberID> getPersistedMembers() {
    Set<PersistentMemberID> offlineMembers = storage.getOfflineMembers();
    Set<PersistentMemberID> equalMembers = storage.getOfflineAndEqualMembers();
    Set<PersistentMemberID> onlineMembers = storage.getOnlineMembers();
    Set<PersistentMemberID> persistentMembers = new HashSet<PersistentMemberID>();
    persistentMembers.addAll(offlineMembers);
    persistentMembers.addAll(equalMembers);
    persistentMembers.addAll(onlineMembers);
    return persistentMembers;
  }
  
  public PersistentMemberID getPersistentIDIfOnline() {
    if(online) {
      return storage.getMyPersistentID();
    } else {
      return null;
    }
  }
  
  private void memberOffline(InternalDistributedMember distributedMember,
      PersistentMemberID persistentID) {
    if(traceOn()) {
      trace(" Member offine. id=" + distributedMember + ", persistentID= " + persistentID);
    }
    synchronized(lock) {
      boolean foundMember = false;
      foundMember |= recoveredMembers.remove(persistentID);
      foundMember |= equalMembers.remove(persistentID);
      foundMember |= getPersistedMembers().contains(persistentID);
      //Don't persist members as offline until we are online. Otherwise, we may
      //think we have later data than them during recovery.
      if(shouldUpdatePersistentView && online) {
        try {
          //Don't persistent members as offline if we have already persisted them as equal.
          if(storage.getOfflineAndEqualMembers().contains(persistentID)) {
            return;
          }
          //Don't mark the member as offline if we have never seen it. If we haven't seen it
          //that means it's not done initializing yet.
          if(foundMember) {
            if(PersistenceObserverHolder.getInstance().memberOffline(regionPath, persistentID)) {
              storage.memberOffline(persistentID);
            }
            PersistenceObserverHolder.getInstance().afterPersistedOffline(regionPath, persistentID);
          }
        } catch (DiskAccessException e) {
          logger.warning(
              LocalizedStrings.PersistenceAdvisorImpl_UNABLE_TO_PERSIST_MEMBERSHIP_CHANGE,
              e);
        }
      }
      notifyListenersMemberOffline(distributedMember, persistentID);
    }
    
  }

  private void memberOnline(InternalDistributedMember distributedMember,
      PersistentMemberID persistentID) {
    if(traceOn()) {
      trace(" Member online. id=" + distributedMember + ", persistentID= " + persistentID);
    }
    synchronized(lock) {
      if(shouldUpdatePersistentView) {
        recoveredMembers.remove(persistentID);
        try {
          if(PersistenceObserverHolder.getInstance().memberOnline(regionPath, persistentID)) {
            storage.memberOnline(persistentID);
          }
          PersistenceObserverHolder.getInstance().afterPersistedOnline(regionPath, persistentID);
        } catch (DiskAccessException e) {
          logger.warning(
              LocalizedStrings.PersistenceAdvisorImpl_UNABLE_TO_PERSIST_MEMBERSHIP_CHANGE,
              e);
        }
      } else {
        if(traceOn()) {
          trace(" not marking member online in persistent " +
          		"view because we're still in initialization.");
        }
      }

      notifyListenersMemberOnline(distributedMember, persistentID);
    }
  }
  
  private void memberRevoked(PersistentMemberPattern pattern) {
    //Persist the revoked member, so if we recover later we will 
    //remember that they were revoked.
    storage.memberRevoked(pattern);
    
    //Remove the revoked member from our view.
    for(PersistentMemberID id : storage.getOfflineMembers()) {
      if(pattern.matches(id)) {
        memberRemoved(id, true);
      }
    }
    for(PersistentMemberID id : storage.getOnlineMembers()) {
      if(pattern.matches(id)) {
        memberRemoved(id, true);
      }
    }
    for(PersistentMemberID id : storage.getOfflineAndEqualMembers()) {
      if(pattern.matches(id)) {
        memberRemoved(id, true);
      }
    }
  }

  private void unblockMember() {
    for(PersistentMemberID id : storage.getOnlineMembers()) {
      storage.memberOfflineAndEqual(id);
    }
  }

  private void memberRemoved(PersistentMemberID id, boolean revoked) {
    if(traceOn()) {
      trace(" Member removed. persistentID= " + id);
    }
    
    synchronized(lock) {
      recoveredMembers.remove(id);
      equalMembers.remove(id);
      if(!online) {
        removedMembers.add(id);
      }
      try {
        if(PersistenceObserverHolder.getInstance().memberRemoved(regionPath, id)) {
          storage.memberRemoved(id);
        }
        
        //Purge any IDs that are old versions of the the id that
        //we just removed
        for(PersistentMemberID persistedId : getPersistedMembers()) {
          if(persistedId.isOlderOrEqualVersionOf(id)) {
            storage.memberRemoved(persistedId);
          }
        }
        PersistenceObserverHolder.getInstance().afterRemovePersisted(regionPath, id);
      } catch (DiskAccessException e) {
        logger.warning(
            LocalizedStrings.PersistenceAdvisorImpl_UNABLE_TO_PERSIST_MEMBERSHIP_CHANGE,
            e);
      }
      notifyListenersMemberRemoved(id, revoked);
    }
  }

  public PersistentMemberID getPersistentID() {
    return storage.getMyPersistentID();
  }
  
  public PersistentMemberID getInitializingID() {
    return storage.getMyInitializingID();
  }
  
  public void addListener(PersistentStateListener listener) {
    synchronized(this) {
      HashSet<PersistentStateListener> tmpListeners = new HashSet<PersistentStateListener>(listeners);
      tmpListeners.add(listener);
      listeners = Collections.unmodifiableSet(tmpListeners);
    }
    
  }
  
  public void removeListener(PersistentStateListener listener) {
    synchronized(this) {
      HashSet<PersistentStateListener> tmpListeners = new HashSet<PersistentStateListener>(listeners);
      tmpListeners.remove(listener);
      listeners = Collections.unmodifiableSet(tmpListeners);
    }
  }
  
  private void notifyListenersMemberOnline(InternalDistributedMember member,
      PersistentMemberID persistentID) {
    for(PersistentStateListener listener : listeners) {
      listener.memberOnline(member, persistentID);
    }
  }
  private void notifyListenersMemberOffline(InternalDistributedMember member,
      PersistentMemberID persistentID) {
    for(PersistentStateListener listener : listeners) {
      listener.memberOffline(member, persistentID);
    }
  }
  
  private void notifyListenersMemberRemoved(PersistentMemberID persistentID, boolean revoked) {
    for(PersistentStateListener listener : listeners) {
      listener.memberRemoved(persistentID, revoked);
    }
    
  }
  
  public HashSet<PersistentMemberID> getPersistedOnlineOrEqualMembers() {
    HashSet<PersistentMemberID> members  = new HashSet<PersistentMemberID>(storage.getOnlineMembers());
    members.addAll(equalMembers);
    return members;
  }

  public void prepareNewMember(InternalDistributedMember sender, PersistentMemberID oldId,
      PersistentMemberID newId) {
    if(traceOn()) {
      trace(" Preparing new persistent Id - " + newId + ". Old id " + oldId);
    }
    synchronized(lock) {
      //Don't prepare the ID if the advisor doesn't have a profile. This prevents
      //A race with the advisor remove
      if(!advisor.containsId(sender)) {
        if(traceOn()) {
          trace(" Refusing to prepare id because " + sender + " is not in our advisor");
        }
        return;
      }
      //Persist new members even if we are not online yet
      //Two members can become online at once. This way,
      //they will know about each other.
      storage.memberOnline(newId);
      
      //The oldId and newId could be the same if the member
      //is retrying a GII. See bug #42051
      if(oldId != null && !oldId.equals(newId)) {
        if(initialized) {
          memberRemoved(oldId, false);
        }
      }
    }
  }
  
  protected void trace(String string) {
    if(logger.fineEnabled()) { 
      logger.fine("PersistenceAdvisor " + shortDiskStoreId() + " - " + regionPath + " - " + string);
    } else if(TRACE) {
      logger.info(LocalizedStrings.DEBUG, "PersistenceAdvisor " + shortDiskStoreId() + " - " + regionPath + " - " + string);
    }
  }

  protected String shortDiskStoreId() {
    DiskStoreID diskStoreID = getDiskStoreID();
    return diskStoreID == null ? "mem" : diskStoreID.abbrev();
  }
  
  protected boolean traceOn() {
    return logger.fineEnabled() || TRACE;
  }

  public void removeMember(PersistentMemberID id) {
    memberRemoved(id, false);
  }
  
  public void markMemberOffline(InternalDistributedMember member, PersistentMemberID id) {
    memberOffline(member, id);
  }

  public void setWaitingOnMembers(Set<PersistentMemberID> allMembersToWaitFor, Set<PersistentMemberID> offlineMembersToWaitFor) {
    this.allMembersWaitingFor = allMembersToWaitFor;
    this.offlineMembersWaitingFor = offlineMembersToWaitFor;
  }
  
  public boolean checkMyStateOnMembers(Set<InternalDistributedMember> replicates) throws ReplyException {
    PersistentStateQueryResults remoteStates = getMyStateOnMembers(replicates);

    boolean equal = false;
    if (observer != null) {
      observer.observe(regionPath);
    }

    for(Map.Entry<InternalDistributedMember, PersistentMemberState> entry: remoteStates.stateOnPeers.entrySet()) {
      InternalDistributedMember member = entry.getKey();
      PersistentMemberID remoteId = remoteStates.persistentIds.get(member);
      // check for same diskIds and check how old it is
      // may print the information and if allow then go ahead with the initialization
      final PersistentMemberID myId = getPersistentID();
      PersistentMemberState stateOnPeer = entry.getValue();

      if(PersistentMemberState.REVOKED.equals(stateOnPeer)) {
        throw new RevokedPersistentDataException(
            LocalizedStrings.PersistentMemberManager_Member_0_is_already_revoked
                .toLocalizedString(myId));
      }

      if(myId != null && stateOnPeer == null) {
        // This can be made as a property to make sure doesn't happen always
        if (true) {
          // There is nothign on remote or remote has same diskId.
          if (remoteId == null || (remoteId.diskStoreId == myId.diskStoreId)) {
            // continue..
            advisor.getLogWriter().info(LocalizedStrings.DEBUG,
                "Continuing as my DiskStoreId is same as remoteID" + member + " remoteID " + remoteId);
          } else {
            String message = LocalizedStrings.CreatePersistentRegionProcessor_SPLIT_DISTRIBUTED_SYSTEM
                .toLocalizedString(regionPath, member, remoteId, myId);
            throw new ConflictingPersistentDataException(message);
          }
        }
      }
      if(myId != null && stateOnPeer == PersistentMemberState.EQUAL) {
          equal = true;
      }
      
      //TODO prpersist - This check might not help much. The other member changes it's ID when it
      //comes back online.
      if(remoteId != null) {
        PersistentMemberState remoteState = getPersistedStateOfMember(remoteId);
        if(remoteState == PersistentMemberState.OFFLINE) {
          String message = LocalizedStrings.CreatePersistentRegionProcessor_INITIALIZING_FROM_OLD_DATA
              .toLocalizedString(regionPath, member, remoteId, myId);
          throw new ConflictingPersistentDataException(message);
        }
      }
    }
    return equal;
  }
  
  public void finishPendingDestroy() {
  //send a message to peers indicating that they should remove this profile
    long viewVersion = -1;
    viewVersion = advisor.startOperation();
    try {
      if(logger.infoEnabled()) {
        advisor.getLogWriter().info(LocalizedStrings.DEBUG, "The advisee is " + advisor.getAdvisee());
      }
      if (advisor.getAdvisee() != null && (advisor.getAdvisee() instanceof ProxyBucketRegion)) {
        ((ProxyBucketRegion)advisor.getAdvisee()).clearIndexes(advisor.getLogWriter());
      }

      RemovePersistentMemberMessage.send(advisor.adviseProfileUpdate(),
          advisor.getDistributionManager(), regionPath, getPersistentID(), getInitializingID());
      
      storage.finishPendingDestroy();
    } finally {
      if (viewVersion != -1) {
        advisor.endOperation(viewVersion);
      } 
    }
    synchronized(lock) {
      recoveredMembers.clear();
    }
  }
  
  /**
   * Returns the member id of the member who has the latest
   * copy of the persistent region. This may be the local member ID
   * if this member has the latest known copy.
   * 
   * This method will block until the latest member is online.
   * @throws ConflictingPersistentDataException if there are active members
   * which are not based on the state that is persisted in this member.
   */
  public CacheDistributionAdvisor.InitialImageAdvice getInitialImageAdvice(
      CacheDistributionAdvisor.InitialImageAdvice previousAdvice, boolean recoverFromDisk) {
    MembershipChangeListener listener = new MembershipChangeListener();
    advisor.addMembershipAndProxyListener(listener);
    addListener(listener);
    try {
      while(true) {
        Set<PersistentMemberID> previouslyOnlineMembers = getPersistedOnlineOrEqualMembers();

        advisor.getAdvisee().getCancelCriterion().checkCancelInProgress(null);
        try {
          InitialImageAdvice advice = advisor.adviseInitialImage(previousAdvice);

          if(!advice.getReplicates().isEmpty()) {
            trace("There are members currently online. Checking for our state on those members and then initializing");
            //We will go ahead and take the other members contents if we ourselves didn't recover from disk.
            if(recoverFromDisk) {
              //Check with these members to make sure that they
              //have heard of us
              //If any of them say we have the same data on disk, we don't need to do a GII
              if(checkMyStateOnMembers(advice.getReplicates())) {
                if(traceOn()) {
                  trace("We have the same data on disk as one of " + advice.getReplicates() + " recovering gracefully");
                }
                advice.getReplicates().clear();
              } else {
                //If we have to do a GII, we have not equal members anymore.
                synchronized(lock) {
                  equalMembers.clear();
                }
              }
            }
            return advice;
          }

          //If there are no currently online members, and no
          //previously online members, this member should just go with what's
          //on it's own disk
          if(previouslyOnlineMembers.isEmpty()) {
            logger.fine("No previously online members. Recovering with the data from the local disk");
            return advice;
          }


          Set<PersistentMemberID> offlineMembers = new HashSet<PersistentMemberID>();
          Set<PersistentMemberID> membersToWaitFor = getMembersToWaitFor(previouslyOnlineMembers, offlineMembers);

          if(membersToWaitFor.isEmpty()) {
            if(traceOn()) {
              trace("All of the previously online members are now online and waiting for us. "
                  + "Acquiring tie lock. Previously online members "
                  + previouslyOnlineMembers);
            }
            //We're tied for the latest copy of the data. try to get the distributed lock.
            if(acquireTieLock()) {
              advice = advisor.adviseInitialImage(previousAdvice);
              trace("Acquired the lock. This member will initialize");
              if(!advice.getReplicates().isEmpty()) {
                trace("Another member has initialized while we were getting the lock. We will initialize from that member");
                checkMyStateOnMembers(advice.getReplicates());
              }
              return advice;
            } else {
              trace("Failed to acquire the lock.");
            }
          } else {
            if(traceOn()) {
              trace("Going to wait for these member ids: " + membersToWaitFor);
            }
          }

          beginWaitingForMembershipChange(membersToWaitFor);
          try {
          //The persistence advisor needs to know which members are really not available
            //because the user uses this information to decide which members they
            //haven't started yet. membersToWaitFor includes members that
            //are still waiting to start up, but are waiting for members other than
            //the current member. So we pass the set of offline members here
            listener.waitForChange(membersToWaitFor, offlineMembers);
          } finally {
            endWaitingForMembershipChange();
          }
        } catch(InterruptedException e) {
          logger.fine("Interrupted while trying to determine latest persisted copy", e);
        }
      }
    } finally {
      if(traceOn()) {
        trace("Wait over. Going ahead." );
      }
      advisor.removeMembershipAndProxyListener(listener);
      removeListener(listener);
    }
  }

  /**
   * @param previouslyOnlineMembers the members we have persisted online
   *   in our persistence files
   * @param offlineMembers This method will populate this set with
   * any members that we are waiting for an are actually not running right now.
   * This is different that the set of members we need to wait for - this member
   * may end up waiting on member that is actually running.
   * @return the list of members that this member needs to wait for before
   * it can initialize.
   */
  public Set<PersistentMemberID> getMembersToWaitFor(
      Set<PersistentMemberID> previouslyOnlineMembers, 
      Set<PersistentMemberID> offlineMembers) throws ReplyException, InterruptedException {
    PersistentMemberID myPersistentID = getPersistentID();
    PersistentMemberID myInitializingId = getInitializingID();
    
    //This is the set of members that are currently waiting for this member
    //to come online.
    Set<PersistentMemberID> membersToWaitFor = new HashSet<PersistentMemberID>(previouslyOnlineMembers);
    offlineMembers.addAll(previouslyOnlineMembers);

    //If our persistent ID is null, we need to wait for all of the previously online members.
    if(myPersistentID != null || myInitializingId != null) {
      Set<InternalDistributedMember> members = advisor.adviseProfileUpdate();
      Set<InternalDistributedMember> membersHostingThisRegion = advisor.adviseGeneric();

      //Fetch the persistent view from all of our peers.
      PersistentStateQueryResults results = PersistentStateQueryMessage
      .send(members, advisor.getDistributionManager(), regionPath, myPersistentID, myInitializingId);

      // iterate through all of the peers. For each peer:
      // if the guy was previously online according to us, grab it's online
      // members and add them to the members to wait for set.
      // We may need to do this several times until we discover all of the
      // members that may have newer data than
      // us,
      boolean addedMembers = true;
      while(addedMembers) {
        addedMembers = false;
        for(Entry<InternalDistributedMember, Set<PersistentMemberID>> entry : results.onlineMemberMap.entrySet()) {
          InternalDistributedMember memberId = entry.getKey();
          Set<PersistentMemberID> peersOnlineMembers = entry.getValue();
          PersistentMemberID persistentID = results.persistentIds.get(memberId);
          PersistentMemberID initializingID = results.initializingIds.get(memberId);
          if(membersToWaitFor.contains(persistentID) || membersToWaitFor.contains(initializingID)) {
            for(PersistentMemberID peerOnlineMember : peersOnlineMembers) {
              if(!isRevoked(peerOnlineMember) && !peerOnlineMember.diskStoreId.equals(getDiskStoreID())) {
                if(membersToWaitFor.add(peerOnlineMember)) {
                  addedMembers = true;
                  //Make sure we also persist that this member is online.
                  storage.memberOnline(peerOnlineMember);
                  if(traceOn()) {
                    trace("Adding "
                        + peerOnlineMember
                        + " to the list of members we're wait for, because "
                        + memberId
                        + " has newer or equal data than is and is waiting for that member");
                  }
                }
              }
            }
          }
        }
      }
      if(traceOn()) {
        trace("Initial state of membersToWaitFor, before pruning " + membersToWaitFor);
      }
      
      //For each of our peers, see what our state is according to their view.
      for(Map.Entry<InternalDistributedMember, PersistentMemberState> entry : results.stateOnPeers.entrySet()) {
        InternalDistributedMember memberId = entry.getKey();
        PersistentMemberID persistentID = results.persistentIds.get(memberId);
        PersistentMemberID initializingID = results.initializingIds.get(memberId);
        DiskStoreID diskStoreID = results.diskStoreIds.get(memberId);
        PersistentMemberState state = entry.getValue();
        
        if(PersistentMemberState.REVOKED.equals(state)) {
          throw new RevokedPersistentDataException(
              LocalizedStrings.PersistentMemberManager_Member_0_is_already_revoked
                  .toLocalizedString(myPersistentID));
        }

        //If the peer thinks we are newer or equal to them, we don't
        //need to wait for this peer.
        if(membersHostingThisRegion.contains(memberId) &&
            persistentID != null && state != null && myInitializingId == null 
            && (state.equals(PersistentMemberState.ONLINE) || state.equals(PersistentMemberState.EQUAL))) {
          if(traceOn()) {
            trace("Not waiting for " + persistentID + " because it thinks we our state was " + state);
          }
          removeNewerPersistentID(membersToWaitFor, persistentID);
        }
        
        //If the peer has an initialized ID, they are no longer offline.
        if(persistentID != null) {
          removeNewerPersistentID(offlineMembers, persistentID);
        }
        
        //If the peer thinks we are newer or equal to them, we don't
        //need to wait for this peer.
        if(membersHostingThisRegion.contains(memberId) &&
            initializingID != null && state != null 
            && (state.equals(PersistentMemberState.ONLINE) || state.equals(PersistentMemberState.EQUAL))) {
          if(traceOn()) {
            trace("Not waiting for " + initializingID + " because it thinks we our state was " + state);
          }
          removeNewerPersistentID(membersToWaitFor, initializingID);
        }
        
        //If the peer has an initializing id, they are also not online.
        if(initializingID != null) {
          removeNewerPersistentID(offlineMembers, initializingID);
        }
        
        //If we were able to determine what disk store this member
        //is in, and it doesn't have a persistent ID, but we think
        //we should be waiting for it, stop waiting for it.
        if(initializingID == null && persistentID == null & diskStoreID != null) {
          removeByDiskStoreID(membersToWaitFor, diskStoreID);
          removeByDiskStoreID(offlineMembers, diskStoreID);
        }
      }
    }

    return membersToWaitFor;
  }
  
  /**
   * Remove all members with a given disk store id from the set of members to
   * wait for, who is newer than the real one.
   * The reason is: A is waiting for B2, but B sends B1<=A to A. That means A
   * knows more than B in both B1 and B2. B itself knows nothing about B2.
   * So we don't need to wait for B2 (since we don't need to wait for B1).
   */
  private void removeNewerPersistentID(Set<PersistentMemberID> membersToWaitFor, PersistentMemberID persistentID) {
    for(Iterator<PersistentMemberID> itr  = membersToWaitFor.iterator(); itr.hasNext(); ) {
      PersistentMemberID id = itr.next();
      if (persistentID.isOlderOrEqualVersionOf(id)) {
        if(traceOn()) {
          trace("Not waiting for " + id + " because local member knows more about it");
        }
        itr.remove();
      }
    }
  }

  /**
   * Remove all members with a given disk store id from the set of members
   * to wait for.
   */
  private void removeByDiskStoreID(Set<PersistentMemberID> membersToWaitFor,
      DiskStoreID diskStoreID) {
    for(Iterator<PersistentMemberID> itr  = membersToWaitFor.iterator(); itr.hasNext(); ) {
      PersistentMemberID id = itr.next();
      if(id.diskStoreId.equals(diskStoreID)) {
        if(traceOn()) {
          trace("Not waiting for " + id + " because it no longer has this region in it's disk store");
        }
        itr.remove();
        memberRemoved(id, false);
      }
    }
  }

  protected void beginWaitingForMembershipChange(Set<PersistentMemberID> membersToWaitFor) {
    //do nothing
  }
  
  protected void endWaitingForMembershipChange() {
    //do nothing
  }
  
  public boolean wasHosting() {
    return getPersistentID() != null || getInitializingID() != null;
  }
  
  protected String getRegionPathForOfflineMembers() {
    return regionPath;
  }
  
  /**
   * Returns the set of missing members that we report back to the
   * any admin DS looking for missing members.
   */
  protected Set<PersistentMemberID> getMissingMembers() {
    return offlineMembersWaitingFor;
  }
  
  /**
   * Returns the set of missing members that we report back to the
   * any admin DS looking for missing members.
   */
  public Set<PersistentMemberID> getAllMembersToWaitFor() {
    return allMembersWaitingFor;
  }
  
  protected void logWaitingForMember(Set<PersistentMemberID> allMembersToWaitFor, Set<PersistentMemberID> offlineMembersToWaitFor) {
    final StaticSystemCallbacks sysCb = GemFireCacheImpl
        .getInternalProductCallbacks();
    Set<String> membersToWaitForLogEntries = new HashSet<String>();
    if(offlineMembersToWaitFor != null && !offlineMembersToWaitFor.isEmpty()) {
      TransformUtils.transform(offlineMembersToWaitFor, membersToWaitForLogEntries, TransformUtils.persistentMemberIdToLogEntryTransformer);

      String message = LocalizedStrings
          .CreatePersistentRegionProcessor_WAITING_FOR_LATEST_MEMBER
          .toLocalizedString(new Object[] { regionPath,
              TransformUtils.persistentMemberIdToLogEntryTransformer
                  .transform(getPersistentID()), membersToWaitForLogEntries,
              GemFireUtilLauncher.getScriptName() });
      StartupStatus.startup(message);
      // also notify GemFireXD layer
      if (sysCb != null) {
        sysCb.waitingForDataSync(regionPath, offlineMembersToWaitFor, null,
            getPersistentID(), message);
      }
    } else {
      TransformUtils.transform(allMembersToWaitFor, membersToWaitForLogEntries, TransformUtils.persistentMemberIdToLogEntryTransformer);

      String message = LocalizedStrings
          .CreatePersistentRegionProcessor_WAITING_FOR_ONLINE_LATEST_MEMBER
          .toLocalizedString(new Object[] { regionPath,
              TransformUtils.persistentMemberIdToLogEntryTransformer
                  .transform(getPersistentID()), membersToWaitForLogEntries,
              GemFireUtilLauncher.getScriptName() });
      StartupStatus.startup(message);
      // also notify GemFireXD layer
      if (sysCb != null) {
        sysCb.waitingForDataSync(regionPath, allMembersToWaitFor, null,
            getPersistentID(), message);
      }
    }
  }

  protected void checkInterruptedByShutdownAll() {
  }

  protected class MembershipChangeListener implements MembershipListener, PersistentStateListener {
    
    public MembershipChangeListener() {
      
    }
    
    private boolean membershipChanged = false;
    public void waitForChange(Set<PersistentMemberID> allMembersToWaitFor, Set<PersistentMemberID> offlineMembersToWaitFor) throws InterruptedException {
      long waitThreshold = advisor.getDistributionManager().getConfig().getAckWaitThreshold();
      // reduce the first log time from 15secs so that higher layers can report
      // sooner to user
      if (waitThreshold >= 5) {
        waitThreshold = waitThreshold / 5;
      }
      long warningTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitThreshold);
      boolean warned = false;
      synchronized(this) {
        try {
          setWaitingOnMembers(allMembersToWaitFor, offlineMembersToWaitFor);
          while(!membershipChanged && !isClosed && !doNotWait) {
            checkInterruptedByShutdownAll();
            advisor.getAdvisee().getCancelCriterion().checkCancelInProgress(null);
            this.wait(100);
            if(!warned && System.nanoTime() > warningTime) {
              logWaitingForMember(allMembersToWaitFor, offlineMembersToWaitFor);
              warned=true;
            }
          }
          this.membershipChanged = false;
          doNotWait = false;
        } finally {
          setWaitingOnMembers(null, null);
          // also notify GemFireXD layer that wait has ended
          final StaticSystemCallbacks sysCb = GemFireCacheImpl
              .getInternalProductCallbacks();
          if (sysCb != null) {
            sysCb.endWaitingForDataSync(regionPath, getPersistentID());
          }
        }
      }
    }

    public void memberJoined(InternalDistributedMember id) {
      afterMembershipChange();
    }

    private void afterMembershipChange() {
      synchronized(this) {
        this.membershipChanged = true;
        this.notifyAll();
      }
    }

    public void memberDeparted(InternalDistributedMember id, boolean crashed) {
      afterMembershipChange();
    }

    public void memberSuspect(InternalDistributedMember id,
        InternalDistributedMember whoSuspected) {
    }

    @Override
    public void quorumLost(Set<InternalDistributedMember> failures, List<InternalDistributedMember> remaining) {
    }

    public void memberOffline(InternalDistributedMember member,
        PersistentMemberID persistentID) {
      afterMembershipChange();
    }

    public void memberOnline(InternalDistributedMember member,
        PersistentMemberID persistentID) {
      afterMembershipChange();
    }

    public void memberRemoved(PersistentMemberID id, boolean revoked) {
      afterMembershipChange();
    }
  }

  private class ProfileChangeListener implements ProfileListener, MemberRevocationListener {

    public void profileCreated(Profile profile) {
      profileUpdated(profile);
    }

    public void profileRemoved(Profile profile, boolean regionDestroyed) {
      CacheProfile cp = (CacheProfile) profile;
      if(cp.persistentID != null) {
        if(regionDestroyed) {
          memberRemoved(cp.persistentID, false);
        } else {
          memberOffline(profile.getDistributedMember(), cp.persistentID);
        }
      }
    }

    public void profileUpdated(Profile profile) {
      CacheProfile cp = (CacheProfile) profile;
      if(cp.persistentID != null && cp.persistenceInitialized) {
        memberOnline(profile.getDistributedMember(), cp.persistentID);
      }
    }

    public void revoked(PersistentMemberPattern pattern) {
      memberRevoked(pattern);
    }

    public Set<PersistentMemberID> getMissingMemberIds() {
      return getMissingMembers();
    }

    public String getRegionPath() {
      return getRegionPathForOfflineMembers();
    }

    @Override
    public boolean matches(PersistentMemberPattern pattern) {
      return pattern.matches(getPersistentID()) || pattern.matches(getInitializingID()); 
     }

    @Override
    public void addPersistentIDs(Set<PersistentMemberID> localData) {
      PersistentMemberID id = getPersistentID();
      if(id != null) {
        localData.add(id);
      }
      id = getInitializingID();
      if(id != null) {
        localData.add(id);
      }
    }

    @Override
    public void unblock() {
      doNotWait = true;
      unblockMember();
    }
  }

  public void close() {
    isClosed = true;
    memberManager.removeRevocationListener(listener);
    advisor.removeProfileChangeListener(listener);
    releaseTieLock();
  }

  private boolean wasAboutToDestroy() {
    return storage.wasAboutToDestroy() || storage.wasAboutToDestroyDataStorage();
  }

  protected synchronized void resetState() {
    this.online= false;
    this.removedMembers = new HashSet<PersistentMemberID>();
  }

  public void flushMembershipChanges() {
    try {
      advisor.waitForCurrentOperations(logger);
    } catch (RegionDestroyedException e) {
      // continue with the next region
    }
    
  }

  public void persistMembersOfflineAndEqual(
      Map<InternalDistributedMember, PersistentMemberID> map) {
    for (PersistentMemberID persistentID: map.values()) {
      storage.memberOfflineAndEqual(persistentID);
    }
  }

  public DiskStoreID getDiskStoreID() {
    return storage.getDiskStoreID();
  }
  
  public boolean isOnline() {
    return online;
  }

  public static interface PersistenceAdvisorObserver {
    default public void observe(String regionPath) {
    }
  }

  public static void setPersistenceAdvisorObserver(PersistenceAdvisorObserver o) {
     observer = o;
  }
}
