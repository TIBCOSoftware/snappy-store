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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.distributed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisee;
import com.gemstone.gemfire.distributed.internal.DistributionAdvisor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalLocator;
import com.gemstone.gemfire.distributed.internal.ServerLocator;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.BridgeServerAdvisor.BridgeServerProfile;
import com.gemstone.gemfire.internal.cache.ControllerAdvisor.ControllerProfile;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.GridAdvisor;
import com.gemstone.gemfire.internal.cache.UpdateAttributesProcessor;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.concurrent.StoppableReentrantReadWriteLock;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.NetworkInterface;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;
import io.snappydata.thrift.HostAddress;
import io.snappydata.thrift.ServerType;
import io.snappydata.thrift.common.ThriftUtils;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;

/**
 * This {@link DistributionAdvisor} keeps track of the server groups of various
 * VMs and their {@link VMKind} by exchanging profiles containing this
 * information with all other GemFireXD VMs in the distributed system.
 * 
 * @author swale
 */
public final class GfxdDistributionAdvisor extends DistributionAdvisor {

  /** read-write lock for the serverGroup and other maps */
  private final StoppableReentrantReadWriteLock mapLock;

  /**
   * Additional read-write lock for new member additions. This is separate from
   * {@link #mapLock} to avoid conditions like #50307.
   */
  private final StoppableReentrantReadWriteLock newMemberLock;

  /**
   * Current map of a server group to the members in that group with their
   * {@link VMKind}s.
   */
  private final Map<String, Map<InternalDistributedMember, VMKind>>
      serverGroupMap;

  /**
   * Map of all locator VMs in the distributed system to the host[port] of the
   * locator running on them.
   */
  private final Map<InternalDistributedMember, String> locatorMap;

  /**
   * Map of all VMs in the distributed system having thrift servers to the list
   * of <code>HostAddress</code> of thrift servers running on them.
   */
  private final Map<InternalDistributedMember, Set<HostAddress>> thriftServers;

  /**
   * Map of all VMs in the distributed system have DRDA servers to the list
   * of host[port] network servers running on them.
   */
  private final Map<InternalDistributedMember, Set<String>> drdaServerMap;

  /** cached profile of this VM */
  private volatile GfxdProfile myProfile;

  //------------------------ Static Constants ----------------------

  /** token for the default server group */
  private static final String DEFAULT_GROUP = "";

  /** token to start enclosure of member {@link VMKind} in a string */
  public static final char MEMBER_KIND_BEGIN = '{';

  /** token to end enclosure of member {@link VMKind} in a string */
  public static final char MEMBER_KIND_END = '}';

  //---------------------- End: Static Constants -------------------

  /**
   * Constructs a new GFXD distribution advisor. Also sets up this VM's profile
   * and exchanges it with other VMs.
   */
  private GfxdDistributionAdvisor(DistributionAdvisee advisee,
      GemFireCacheImpl cache) {
    super(advisee);
    this.mapLock = new StoppableReentrantReadWriteLock(false, true,
        cache.getCancelCriterion());
    this.newMemberLock = cache.getSystem().getVMIdAdvisor().getNewMemberLock();
    this.serverGroupMap = new HashMap<>();
    this.locatorMap = new HashMap<>();
    this.thriftServers = new HashMap<>();
    this.drdaServerMap = new HashMap<>();
  }

  public static GfxdDistributionAdvisor createGfxdDistributionAdvisor(
      DistributionAdvisee advisee, GemFireCacheImpl cache) {
    GfxdDistributionAdvisor advisor = new GfxdDistributionAdvisor(advisee,
        cache);
    advisor.initialize();
    return advisor;
  }

  /**
   * Sets up this VM's profile and exchanges it with other VMs. Normally should
   * be invoked soon after creation.
   */
  public void handshake(LogWriter logger) {
    // add this VM's kind and server groups
    synchronized (this) {
      this.myProfile = (GfxdProfile)createProfile();
      final SortedSet<String> groups = this.myProfile.getServerGroups();
      final VMKind kind = this.myProfile.getVMKind();
      final InternalDistributedMember member = this.myProfile
          .getDistributedMember();
      boolean mapLockAcquired = false;
      this.newMemberLock.writeLock().lock();
      try {
        this.mapLock.writeLock().lock();
        mapLockAcquired = true;
        // add self information
        addMemberGroups(member, groups, kind);
        // add any locator in this VM
        Locator locator = Locator.getLocator();
        if (locator != null) {
          ServerLocator serverLoc = ((InternalLocator)locator)
              .getServerLocatorAdvisee();
          if (serverLoc != null) {
            addMemberServer(member,
                serverLoc.getHostName() + '[' + serverLoc.getPort() + ']',
                this.locatorMap, false);
          }
        }
      } finally {
        try {
          if (mapLockAcquired) {
            this.mapLock.writeLock().unlock();
          }
        } finally {
          this.newMemberLock.writeLock().unlock();
        }
      }
      if (logger.configEnabled()) {
        logger.config("This JVM is setup with SnappyData " + kind.toString()
            + " role.");
        if (!groups.isEmpty()) {
          logger.config("Server groups this JVM is member of: "
              + SharedUtils.toCSV(groups));
        }
      }
    }
    // no need to update the status on self since that will be done in
    // the initial DDL replay code itself; if this is the only member in
    // the DS currently then it will select itself during config scripts
    // execution

    // send a dummy controller profile to gather information of all
    // controllers in the DS including non-GemFireXD locators
    new UpdateAttributesProcessor(new DummyControllerAdvisee())
        .distribute(true);
    // now initialize by exchanging the profile with other VMs
    initializationGate();

    Set<DistributedMember> servers = this.adviseAllNodes(null);
    for (DistributedMember server : servers) {
      final GfxdDistributionAdvisor.GfxdProfile other = GemFireXDUtils
          .getGfxdProfile(server);
      // Since every node joining the cluster is checked for the locale here,
      // it is possible to abort the check if we find that the current
      // node's locale matches the locale of the first node in the 'servers'
      // list. We are extra careful and still match the locale with all nodes
      if (!GemFireXDUtils.getMyVMKind().isAdmin()
          && other.getLocale() != null // Since locale wasn't available in versions prior to 1.2
          && !this.myProfile.getLocale().equals(other.getLocale())) {
        throw new GemFireXDRuntimeException(
            "Locale should be same on all nodes in the cluster. "
                + "Locale of the current node is " + this.myProfile.getLocale()
                + ", locale of the other node in the cluster is "
                + other.getLocale() + ". Other node's id: "
                + other.peerMemberId.getId());
      }
      // Yogesh: persist-dd should be consistent on all datastores
      if (GemFireXDUtils.getMyVMKind().isStore()) {
        if (other.vmKind.isStore()
            && other.isPersistDD() != this.myProfile.isPersistDD()) {
          throw new GemFireXDRuntimeException(
              "persist-dd should be same on all the servers");
        }
      }
    }
  }

  /** Instantiate new distribution profile for this member */
  @Override
  protected final GfxdProfile instantiateProfile(
      InternalDistributedMember memberId, int version) {
    final GfxdProfile currentProfile = this.myProfile;
    long catalogVersion = currentProfile != null
        ? currentProfile.getCatalogSchemaVersion() : 0;
    return new GfxdProfile(memberId, version, CallbackFactoryProvider
        .getClusterCallbacks().getDriverURL(), catalogVersion);
  }

  /**
   * Need ALL others (both normal members and admin members).
   */
  @Override
  public boolean useAdminMembersForDefault() {
    return true;
  }

  /**
   * @return true if new profile added, false if already had profile (but
   *         profile is still replaced with new one)
   */
  @Override
  protected final synchronized boolean basicAddProfile(Profile p) {
    boolean isAdded = false;
    boolean mapLockAcquired = false;
    final InternalDistributedMember m = p.getDistributedMember();
    this.newMemberLock.writeLock().lock();
    try {
      this.mapLock.writeLock().lock();
      mapLockAcquired = true;
      if (p instanceof GfxdProfile) {
        isAdded = super.basicAddProfile(p);
        final GfxdProfile profile = (GfxdProfile)p;
        // update node initialized status
        final GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
        if (cache != null) {
          cache.updateNodeStatus(m, profile.getInitialized());
        }
        // add the server groups and VMKind
        if (isAdded) {
          addMemberGroups(m, profile.getServerGroups(), profile.getVMKind());
        }
        else {
          removeMemberGroups(m);
          addMemberGroups(m, profile.getServerGroups(), profile.getVMKind());
        }
        // update own catalog version
        final GfxdProfile myProfile = this.myProfile;
        if (myProfile != null) {
          myProfile.updateCatalogSchemaVersion(profile.getCatalogSchemaVersion());
        }
      }
      else if (p instanceof BridgeServerProfile) {
        final BridgeServerProfile bp = (BridgeServerProfile)p;
        if (bp.getPort() > 0) {
          // check for the groups to determine the type of server
          String[] groups = bp.getGroups();
          // get serverType from groups[0] if set (will not be set for pre GFXD 1.1)
          final ServerType serverType;
          if (groups == null || groups.length == 0) {
            serverType = ServerType.DRDA;
          }
          else {
            serverType = ServerType.findByGroupName(groups[0]);
          }
          if (ServerType.DRDA.equals(serverType)) {
            isAdded = addMemberServer(m, bp.getHost() + '[' + bp.getPort()
                + ']', this.drdaServerMap, true);
          }
          else {
            isAdded = addMemberServer(m,
                ThriftUtils.getHostAddress(bp.getHost(), bp.getPort())
                    .setServerType(serverType), this.thriftServers, true);
          }
        }
      }
      else if (p instanceof ControllerProfile) {
        final ControllerProfile cp = (ControllerProfile)p;
        if (cp.getPort() > 0) {
          isAdded = addMemberServer(m, cp.getHost() + '[' + cp.getPort() + ']',
              this.locatorMap, false);
        }
      }
      else {
        Assert.fail("GfxdDistributionAdvisor: unexpected profile added: ("
            + p.getClass().getName() + ')' + p);
      }
    } finally {
      try {
        if (mapLockAcquired) {
          this.mapLock.writeLock().unlock();
        }
      } finally {
        this.newMemberLock.writeLock().unlock();
      }
    }
    return isAdded;
  }

  @Override
  protected final boolean evaluateProfiles(Profile newProfile,
      Profile oldProfile) {
    // if only the initialized status has changed then skip doing anything else
    if (oldProfile != null && oldProfile instanceof GfxdProfile
        && newProfile instanceof GfxdProfile) {
      return ((GfxdProfile)oldProfile).getInitialized() ==
        ((GfxdProfile)newProfile).getInitialized();
    }
    return true;
  }

  @Override
  public final synchronized boolean removeId(ProfileId memberId,
      boolean crashed, boolean destroyed, boolean fromMembershipListener) {

    boolean removed = false;
    this.mapLock.writeLock().lock();
    try {
      if (memberId instanceof InternalDistributedMember) {
        final InternalDistributedMember m = (InternalDistributedMember)memberId;
        if ((removed = super.removeId(memberId, crashed, destroyed,
            fromMembershipListener))) {
          removeMemberGroups(m);
        }
        this.thriftServers.remove(m);
        this.drdaServerMap.remove(m);
        this.locatorMap.remove(m);
      }
      else if (memberId instanceof GridAdvisor.GridProfileId) {
        final GridAdvisor.GridProfileId id =
          (GridAdvisor.GridProfileId)memberId;
        final InternalDistributedMember m = id.getMemberId();

        final Set<HostAddress> thriftServers = this.thriftServers.get(m);
        if (thriftServers != null
            && thriftServers.remove(ThriftUtils.getHostAddress(id.getHost(),
                id.getPort()))) {
          if (thriftServers.isEmpty()) {
            this.thriftServers.remove(m);
          }
          return true;
        }

        final String idStr = id.getHost() + '[' + id.getPort() + ']';
        final Set<String> netServers = this.drdaServerMap.get(m);
        if (netServers != null && netServers.remove(idStr)) {
          if (netServers.isEmpty()) {
            this.drdaServerMap.remove(m);
          }
          return true;
        }
        if (this.locatorMap.remove(m) != null) {
          return true;
        }
      }
      else {
        Assert.fail("GfxdDistributionAdvisor: unexpected profile ID to remove:"
            + " (" + memberId.getClass().getName() + ')' + memberId);
      }
    } finally {
      this.mapLock.writeLock().unlock();
    }
    return removed;
  }

  /**
   * Distribute an updated {@link GfxdProfile} to other VMs and also update the
   * information in this VM.
   */
  public final void distributeProfileUpdate() {
    // first update the information in this VM
    synchronized (this) {
      this.myProfile = (GfxdProfile)createProfile();
      final InternalDistributedMember m = this.myProfile.getDistributedMember();
      boolean mapLockAcquired = false;
      this.newMemberLock.writeLock().lock();
      try {
        this.mapLock.writeLock().lock();
        mapLockAcquired = true;
        removeMemberGroups(m);
        addMemberGroups(m, this.myProfile.getServerGroups(), this.myProfile
            .getVMKind());
      } finally {
        try {
          if (mapLockAcquired) {
            this.mapLock.writeLock().unlock();
          }
        } finally {
          this.newMemberLock.writeLock().unlock();
        }
      }
    }
    // distribute the updated profile to other VMs
    new UpdateAttributesProcessor(getAdvisee()).distribute(false);
  }

  /**
   * Distribute the initialized or uninitialized status of this node to other
   * VMs and also update the information in this VM.
   */
  public final void distributeNodeStatus(boolean initialized) {
    // first update the information in this VM
    final InternalDistributedMember myId;
    synchronized (this) {
      this.myProfile = (GfxdProfile)createProfile();
      myId = this.myProfile.getDistributedMember();
      this.myProfile.setInitialized(initialized);
    }
    final GemFireCacheImpl cache = Misc.getGemFireCache();
    if (DistributionManager.VERBOSE || GemFireXDUtils.TraceQuery
        || SanityManager.isFineEnabled || GemFireXDUtils.TraceNCJ) {
      cache.getLoggerI18n().fine(
          "GfxdProfile: set this node " + myId + " as initialized");
    }
    cache.updateNodeStatus(myId, initialized);
    // distribute the updated profile to other VMs
    new UpdateAttributesProcessor(getAdvisee()).distribute(false);
  }

  @Override
  public void close() {
    try {
      new UpdateAttributesProcessor(getAdvisee(), true/*removeProfile*/)
          .distribute(false);
      super.close();
    } catch (DistributedSystemDisconnectedException ignore) {
      // we are closing so ignore a shutdown exception.
    }
  }

  /** Get this VM's {@link GfxdProfile}. */
  public GfxdProfile getMyProfile() {
    return this.myProfile; // volatile read
  }

  /**
   * Returns all the datastores including self that are part of one of the given
   * server groups.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   */
  public final Set<DistributedMember> adviseDataStores(Set<String> groups) {
    return adviseVMsOfKind(groups, VMKind.DATASTORE);
  }

  /**
   * Returns all the accessors including self that are part of one of the given
   * server groups.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   */
  public final Set<DistributedMember> adviseAccessors(Set<String> groups) {
    return adviseVMsOfKind(groups, VMKind.ACCESSOR);
  }

  /**
   * Returns all the locators including self that are part of one of the given
   * server groups.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   */
  public final Set<DistributedMember> adviseLocators(Set<String> groups) {
    return adviseVMsOfKind(groups, VMKind.LOCATOR);
  }

  /**
   * Returns all the datastores or accessors including self that are part of one
   * of the given server groups i.e. those members where SQL operations can be
   * performed.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   */
  public final Set<DistributedMember> adviseOperationNodes(Set<String> groups) {
    return adviseVMsOfKind(groups, VMKindToken.SERVER);
  }

  /**
   * Returns all the datastores, accessors and locators in the GemFireXD system.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   */
  public final Set<DistributedMember> adviseAllNodes(Set<String> groups) {
    return adviseVMsOfKind(groups, VMKindToken.ALL);
  }

  /**
   * Returns all the members that have server locators running including self
   * and also including pure GFE locators when the "skipNonGfxd" parameter is
   * false.
   * 
   * @param skipNonGfxd
   *          when true then skip non GemFireXD locators else include pure GFE
   *          locators too
   */
  public final Set<DistributedMember> adviseServerLocators(boolean skipNonGfxd) {
    final UnifiedSet<DistributedMember> locators = new UnifiedSet<>();
    this.mapLock.readLock().lock();
    try {
      InternalDistributedMember member;
      Map<InternalDistributedMember, VMKind> gfxdMembers = null;
      if (skipNonGfxd) {
        gfxdMembers = this.serverGroupMap.get(DEFAULT_GROUP);
      }
      for (Map.Entry<InternalDistributedMember, String> entry : locatorMap
          .entrySet()) {
        if (entry.getValue() != null) {
          member = entry.getKey();
          if (gfxdMembers == null || gfxdMembers.containsKey(member)) {
            locators.add(member);
          }
        }
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
    return locators;
  }

  /**
   * Returns a single datastore that is part of one of the given server groups.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   * @param preferSelf
   *          prefer this VM if it is a datastore and belongs to one of the
   *          given server groups
   */
  public final DistributedMember adviseDataStore(Set<String> groups,
      boolean preferSelf) {
    return adviseSingleVMOfKind(groups, VMKind.DATASTORE, preferSelf);
  }

  /**
   * Returns a single accessor that is part of one of the given server groups.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   * @param preferSelf
   *          prefer this VM if it is a accessor and belongs to one of the
   *          given server groups
   */
  public final DistributedMember adviseAccessor(Set<String> groups,
      boolean preferSelf) {
    return adviseSingleVMOfKind(groups, VMKind.ACCESSOR, preferSelf);
  }

  /**
   * Returns a single datastore or accessor that is part of one of the given
   * server groups.
   * 
   * @param groups
   *          the server groups to pick the VM; a null or empty server group set
   *          indicates the default server group that includes all VMs.
   * @param preferSelf
   *          prefer this VM if it is a datastore or accessor and belongs to one
   *          of the given server groups
   */
  public final DistributedMember adviseOperationNode(Set<String> groups,
      boolean preferSelf) {
    return adviseSingleVMOfKind(groups, VMKindToken.SERVER, preferSelf);
  }

  /** Get the {@link Profile} for the given member including self. */
  public GfxdProfile getProfile(InternalDistributedMember member) {
    Profile profile = super.getProfile(member);
    if (profile != null || ((profile = getMyProfile()) != null &&
        profile.getDistributedMember().equals(member))) {
      return (GfxdProfile)profile;
    }
    return null;
  }

  /**
   * Get the {@link Profile} for the given member's canonicalString().
   */
  public GfxdProfile getProfile(String memberStr) {
    final Profile[] allProfiles = this.profiles; // volatile read
    final int numProfiles = allProfiles.length;
    for (int i = 0; i < numProfiles; i++) {
      final Profile profile = allProfiles[i];
      if (profile.getDistributedMember().canonicalString().equals(memberStr)) {
        return (GfxdProfile)profile;
      }
    }
    GfxdProfile profile = getMyProfile();
    if (profile != null &&
        profile.getDistributedMember().canonicalString().equals(memberStr)) {
      return profile;
    } else {
      return null;
    }
  }

  /**
   * Return true if the given non-locator member belongs to one of the provided
   * server groups.
   */
  public final boolean isGroupMember(DistributedMember member,
      Set<String> groups, boolean isDataStore) {
    VMKind kind;
    this.mapLock.readLock().lock();
    try {
      if (groups == null || groups.size() == 0) {
        final Map<InternalDistributedMember, VMKind> groupMembers =
          this.serverGroupMap.get(DEFAULT_GROUP);
        if (groupMembers != null &&
            (kind = groupMembers.get(member)) != null) {
          // need to ignore VMs of type LOCATOR
          return (isDataStore ? kind == VMKind.DATASTORE
              : kind != VMKind.LOCATOR);
        }
      }
      else {
        for (String group : groups) {
          final Map<InternalDistributedMember, VMKind> groupMembers =
            this.serverGroupMap.get(group);
          if (groupMembers != null &&
              (kind = groupMembers.get(member)) != null) {
            // need to ignore VMs of type LOCATOR
            return (isDataStore ? kind == VMKind.DATASTORE
                : kind != VMKind.LOCATOR);
          }
        }
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
    return false;
  }

  /**
   * Get all the DRDA servers running on the given member as a
   * comma-separated host[port] list.
   */
  public final String getDRDAServers(DistributedMember member) {
    this.mapLock.readLock().lock();
    try {
      final InternalDistributedMember myId = getDistributionManager()
          .getDistributionManagerId();
      final Set<String> networkServers;
      if (myId.equals(member)) {
        networkServers = new TreeSet<>();
        final FabricService service = FabricServiceManager
            .currentFabricServiceInstance();
        if (service != null) {
          for (NetworkInterface ni : service.getAllNetworkServers()) {
            if (ni.getServerType().isDRDA()) {
              networkServers.add(ni.asString());
            }
          }
        }
      }
      else {
        networkServers = this.drdaServerMap.get(member);
      }
      return SharedUtils.toCSV(networkServers);
    } finally {
      this.mapLock.readLock().unlock();
    }
  }

  /**
   * Get server locator running on the given member as host[port] or empty
   * string if no server locator is running.
   */
  public final String getServerLocator(DistributedMember member) {
    this.mapLock.readLock().lock();
    try {
      return this.locatorMap.get(member);
    } finally {
      this.mapLock.readLock().unlock();
    }
  }

  static final class FilterThriftHosts implements Consumer<HostAddress> {

    final Collection<HostAddress> outServers;
    private boolean keepLocators;
    private boolean keepServers;

    FilterThriftHosts(Collection<HostAddress> outServers) {
      this.outServers = outServers;
    }

    void setKeepLocators(boolean v) {
      this.keepLocators = v;
    }

    void setKeepServers(boolean v) {
      this.keepServers = v;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void accept(HostAddress hostAddr) {
      if (this.keepLocators) {
        if (hostAddr.getServerType().isThriftLocator()) {
          this.outServers.add(hostAddr);
        }
      }
      if (this.keepServers) {
        if (hostAddr.getServerType().isThriftSnappy()) {
          this.outServers.add(hostAddr);
        }
      }
    }
  }

  /**
   * Get all the thrift servers in the distributed system. The list will have
   * the <code>HostAddress</code>es of locators at the start.
   */
  public final void getAllThriftServers(Set<ServerType> serverTypes,
      Collection<HostAddress> outHosts) {
    final UnifiedSet<HostAddress> thriftHosts = new UnifiedSet<>();
    this.mapLock.readLock().lock();
    try {
      // first add for self
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service != null) {
        Collection<NetworkInterface> ifaces = service.getAllNetworkServers();
        ServerType serverType;
        for (NetworkInterface ni : ifaces) {
          serverType = ni.getServerType();
          if (serverTypes == null) {
            if (!serverType.isThrift()) {
              continue;
            }
          }
          else if (!serverTypes.contains(serverType)) {
            continue;
          }
          thriftHosts.add(ThriftUtils.getHostAddress(ni.getHostName(),
              ni.getPort()).setServerType(serverType).setIsCurrent(true));
        }
      }
      // then for all the other members of the DS
      for (Set<HostAddress> hostAddrs : this.thriftServers.values()) {
        if (serverTypes == null) {
          thriftHosts.addAll(hostAddrs);
        }
        else {
          for (HostAddress hostAddr : hostAddrs) {
            if (serverTypes.contains(hostAddr.getServerType())) {
              thriftHosts.add(hostAddr);
            }
          }
        }
      }
      // now sort to bring non-servers to the front
      final FilterThriftHosts filter = new FilterThriftHosts(outHosts);
      filter.setKeepLocators(true);
      filter.setKeepServers(false);
      thriftHosts.forEach(filter);

      filter.setKeepLocators(false);
      filter.setKeepServers(true);
      thriftHosts.forEach(filter);

      if (SanityManager.TraceClientHA) {
        UnifiedSet<HostAddress> allHosts = new UnifiedSet<>(thriftHosts.size());
        for (Set<HostAddress> hostAddrs : this.thriftServers.values()) {
          allHosts.addAll(hostAddrs);
        }
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "getAllThriftServers(): with types=" + serverTypes
                + ": returning hosts: " + thriftHosts + " from all hosts: "
                + allHosts);
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
  }

  /**
   * Get all the Thrift servers running on the given member as a comma-separated
   * host[port] list.
   */
  public final String getThriftServers(DistributedMember member) {
    this.mapLock.readLock().lock();
    try {
      final InternalDistributedMember myId = getDistributionManager()
          .getDistributionManagerId();
      final TreeSet<String> thriftServers = new TreeSet<String>();
      if (myId.equals(member)) {
        final FabricService service = FabricServiceManager
            .currentFabricServiceInstance();
        if (service != null) {
          for (NetworkInterface ni : service.getAllNetworkServers()) {
            if (ni.getServerType().isThrift()) {
              thriftServers.add(ni.asString());
            }
          }
        }
      }
      else {
        Set<HostAddress> hostAddrs = this.thriftServers.get(member);
        if (hostAddrs != null) {
          for (HostAddress hostAddr : hostAddrs) {
            thriftServers.add(hostAddr.toString());
          }
        }
      }
      return SharedUtils.toCSV(thriftServers);
    } finally {
      this.mapLock.readLock().unlock();
    }
  }

  /**
   * Get all the DRDA servers in the distributed system. The format of the
   * returned string is:
   * <p>
   * host1[port1]{kind1},host2[port2]{kind2},...
   * <p>
   * i.e. comma-separated list of each DRDA server followed by the
   * <code>VMKind</code> of the VM in curly braces. The DRDA servers on
   * stand-alone locators are given preference and appear at the front. Note
   * that we do not use ':' to separate host and port since host can be an ipv6
   * address in some rare cases when host name itself cannot be looked up.
   */
  public final String getAllDRDAServers() {
    StringBuilder allServers = new StringBuilder();
    this.mapLock.readLock().lock();
    try {
      VMKind kind = this.myProfile.getVMKind();
      // first add for self
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service != null) {
        for (NetworkInterface ni : service.getAllNetworkServers()) {
          if (!ni.getServerType().isDRDA()) {
            continue;
          }
          if (allServers.length() > 0) {
            allServers.append(',');
          }
          allServers.append(ni.asString()).append(MEMBER_KIND_BEGIN)
              .append(kind.toString()).append(MEMBER_KIND_END);
        }
      }
      // then for all the other members of the DS
      final Map<InternalDistributedMember, VMKind> gfxdMembers =
        this.serverGroupMap.get(DEFAULT_GROUP);
      Set<String> servers;
      StringBuilder serverSB;
      for (Map.Entry<InternalDistributedMember, Set<String>> entry :
          this.drdaServerMap.entrySet()) {
        servers = entry.getValue();
        if (servers != null && servers.size() > 0
            && (kind = gfxdMembers.get(entry.getKey())) != null) {
          // if a "locator" kind of VM, then insert at the front else at back
          if (kind != VMKind.LOCATOR) {
            serverSB = allServers;
          }
          else {
            serverSB = new StringBuilder();
          }
          for (String s : servers) {
            if (serverSB.length() > 0) {
              serverSB.append(',');
            }
            serverSB.append(s).append(MEMBER_KIND_BEGIN)
                .append(kind.toString()).append(MEMBER_KIND_END);
          }
          if (kind == VMKind.LOCATOR) {
            if (allServers.length() > 0) {
              allServers = serverSB.append(',').append(allServers);
            }
            else {
              allServers = serverSB;
            }
          }
        }
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
    return allServers.toString();
  }

  /**
   * Get the network servers hosted on this server, if any.
   */
  public final String getOwnNetServers() {
    this.mapLock.readLock().lock();
    try {
      return getSelfNetServers(ClientSharedUtils.isThriftDefault());
    } finally {
      this.mapLock.readLock().unlock();
    }
  }

  /**
   * Get the network servers hosted on this server, if any.
   * Should be invoked under mapLock.readLock().
   */
  private String getSelfNetServers(boolean useThrift) {
    StringBuilder serverSB = new StringBuilder();
    VMKind kind = this.myProfile.getVMKind();
    // first add for self
    final FabricService service = FabricServiceManager
        .currentFabricServiceInstance();
    if (service != null) {
      for (NetworkInterface ni : service.getAllNetworkServers()) {
        if (useThrift) {
          if (!ni.getServerType().isThrift()) continue;
        } else if (!ni.getServerType().isDRDA()) continue;
        if (serverSB.length() > 0) {
          serverSB.append(',');
        }
        serverSB.append(ni.asString()).append(MEMBER_KIND_BEGIN)
            .append(kind.toString()).append(MEMBER_KIND_END);
      }
    }
    return serverSB.toString();
  }

  /**
   * Get the mapping of InternalDistributedMember to corresponding DRDA/Thrift servers
   * they have in the entire system. The format of the network server string is:
   * <p>
   * host1[port1]{kind1},host2[port2]{kind2},...
   * <p>
   * i.e. comma-separated list of each DRDA server followed by the
   * <code>VMKind</code> of the VM in curly braces. The DRDA/Thrift servers on
   * stand-alone locators are given preference and appear at the front. Note
   * that we do not use ':' to separate host and port since host can be an ipv6
   * address in some rare cases when host name itself cannot be looked up.
   * <p>
   * The choice of DRDA servers vs Thrift servers is determined by
   */
  public final Map<InternalDistributedMember, String> getAllNetServersWithMembers() {
    HashMap<InternalDistributedMember, String> mbrToNetworkServerMap =
      new HashMap<InternalDistributedMember, String>();
    final boolean useThrift = ClientSharedUtils.isThriftDefault();
    this.mapLock.readLock().lock();
    try {
      // first add for self
      String selfNetServers = getSelfNetServers(useThrift);
      if (selfNetServers.length() > 0) {
        mbrToNetworkServerMap.put(this.myProfile.getDistributedMember(),
            selfNetServers);
      }
      // then for all the other members of the DS
      final Map<InternalDistributedMember, VMKind> gfxdMembers =
        this.serverGroupMap.get(DEFAULT_GROUP);
      Set<?> servers;
      Map<?, ?> serverMap = useThrift ? thriftServers : drdaServerMap;
      StringBuilder serverSB;
      VMKind kind;
      for (Map.Entry<?, ?> entry : serverMap.entrySet()) {
        InternalDistributedMember m = (InternalDistributedMember)entry.getKey();
        servers = (Set<?>)entry.getValue();
        if (servers != null && servers.size() > 0
            && (kind = gfxdMembers.get(m)) != null
            && kind != VMKind.LOCATOR) {
          serverSB = new StringBuilder();
          for (Object s : servers) {
            if (serverSB.length() > 0) {
              serverSB.append(',');
            }
            if (s instanceof HostAddress) {
              serverSB.append(((HostAddress)s).getHostAddressString());
            } else {
              serverSB.append(s);
            }
            serverSB.append(MEMBER_KIND_BEGIN).append(kind.toString())
                .append(MEMBER_KIND_END);
          }
          mbrToNetworkServerMap.put(m, serverSB.toString());
        }
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
    return mbrToNetworkServerMap;
  }

  /**
   * Returns the set of locator addresses in the system in 'host[port]' format
   * including GFE locators when the "skipNonGfxd" parameter is false.
   * 
   * @param skipNonGfxd
   *          when true then skip non GemFireXD locators else include pure GFE
   *          locators too
   */
  public final Set<String> getAllServerLocators(boolean skipNonGfxd) {
    final UnifiedSet<String> allLocators = new UnifiedSet<>();
    this.mapLock.readLock().lock();
    try {
      InternalDistributedMember member;
      Map<InternalDistributedMember, VMKind> gfxdMembers = null;
      if (skipNonGfxd) {
        gfxdMembers = this.serverGroupMap.get(DEFAULT_GROUP);
      }
      for (Map.Entry<InternalDistributedMember, String> entry : locatorMap
          .entrySet()) {
        final String locator = entry.getValue();
        if (locator != null && locator.length() > 0) {
          member = entry.getKey();
          if (gfxdMembers == null || gfxdMembers.containsKey(member)) {
            allLocators.add(locator);
          }
        }
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
    return allLocators;
  }

  /** add groups and kind of a single member to the map */
  private void addMemberGroups(InternalDistributedMember member,
      SortedSet<String> groups, VMKind vmKind) {
    Map<InternalDistributedMember, VMKind> members;
    if (groups != null && groups.size() > 0) {
      for (String group : groups) {
        members = this.serverGroupMap.get(group);
        if (members == null) {
          members = new HashMap<InternalDistributedMember, VMKind>();
          this.serverGroupMap.put(group, members);
        }
        members.put(member, vmKind);
      }
    }
    // add to the default server group in any case
    members = this.serverGroupMap.get(DEFAULT_GROUP);
    if (members == null) {
      members = new HashMap<InternalDistributedMember, VMKind>();
      this.serverGroupMap.put(DEFAULT_GROUP, members);
    }
    members.put(member, vmKind);
  }

  /**
   * Add server information (locator or network server) of a single member to
   * the provided map.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private boolean addMemberServer(InternalDistributedMember member,
      Object server, Map<InternalDistributedMember, ?> map,
      boolean allowMultiple) {
    boolean isAdded = false;
    // noinspection UnnecessaryLocalVariable
    final Map serverMap = map;
    if (allowMultiple) {
      Set servers = (Set)map.get(member);
      if (servers == null) {
        servers = new UnifiedSet<>();
        serverMap.put(member, servers);
        isAdded = true;
      }
      servers.add(server);
    }
    else {
      serverMap.put(member, server);
    }
    return isAdded;
  }

  /** remove all the groups of the given member */
  private void removeMemberGroups(InternalDistributedMember member) {
    for (Map<InternalDistributedMember, VMKind> groupMap : this.serverGroupMap
        .values()) {
      groupMap.remove(member);
    }
  }

  /**
   * Returns the members that have the given {@link VMKind} and are part of one
   * of the given server groups. A null or empty server group set indicates the
   * default server group that includes all VMs. The extra union types added in
   * {@link VMKindToken} are also honoured.
   */
  private Set<DistributedMember> adviseVMsOfKind(
      final Set<String> groups, final VMKind kind) {
    final UnifiedSet<DistributedMember> members = new UnifiedSet<>();
    this.mapLock.readLock().lock();
    try {
      // special default server group check for null/empty groups
      if (groups == null || groups.size() == 0) {
        addGroupMembers(DEFAULT_GROUP, kind, members);
      }
      else {
        for (String group : groups) {
          addGroupMembers(group, kind, members);
        }
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
    return members;
  }

  /**
   * Returns a single member that has the given {@link VMKind} and is part of
   * one of the given server groups. A null or empty server group set indicates
   * the default server group that includes all VMs. A null {@link VMKind}
   * indicates both datastores and accessors but not locatorStrings. If the
   * "preferSelf" argument is true then this VM is preferred in the result
   * provided it meets the {@link VMKind} and server groups criteria.
   */
  private final DistributedMember adviseSingleVMOfKind(
      final Set<String> groups, final VMKind kind, boolean preferSelf) {
    GfxdProfile profile = null;
    if (preferSelf) {
      profile = this.myProfile; // volatile read
    }
    this.mapLock.readLock().lock();
    try {
      // special default server group check for null/empty groups
      if (groups == null || groups.size() == 0) {
        if (preferSelf && checkVMKind(profile.getVMKind(), kind)) {
          return profile.getDistributedMember();
        }
        return getSingleGroupMember(DEFAULT_GROUP, kind);
      }
      else {
        if (preferSelf && checkVMKind(profile.getVMKind(), kind)
            && ServerGroupUtils.isGroupMember(profile.getServerGroups(),
                groups)) {
          return profile.getDistributedMember();
        }
        DistributedMember member;
        for (String group : groups) {
          if ((member = getSingleGroupMember(group, kind)) != null) {
            return member;
          }
        }
      }
    } finally {
      this.mapLock.readLock().unlock();
    }
    return null;
  }

  /**
   * Add all the members in given group and having given {@link VMKind} to the
   * provided set of members.
   */
  private void addGroupMembers(String group, VMKind kind,
      UnifiedSet<DistributedMember> members) {
    final Map<InternalDistributedMember, VMKind> groupMembers = serverGroupMap
        .get(group);
    if (groupMembers != null) {
      for (Map.Entry<InternalDistributedMember, VMKind> entry : groupMembers
          .entrySet()) {
        final VMKind vmKind = entry.getValue();
        if (checkVMKind(vmKind, kind)) {
          members.add(entry.getKey());
        }
      }
    }
  }

  /** get a single member in given group and having given {@link VMKind} */
  private InternalDistributedMember getSingleGroupMember(String group,
      VMKind kind) {
    final Map<InternalDistributedMember, VMKind> groupMembers = serverGroupMap
        .get(group);
    if (groupMembers != null) {
      for (Map.Entry<InternalDistributedMember, VMKind> entry : groupMembers
          .entrySet()) {
        final VMKind vmKind = entry.getValue();
        if (checkVMKind(vmKind, kind)) {
          return entry.getKey();
        }
      }
    }
    return null;
  }

  /**
   * Check if {@link VMKind} of a VM matches provided kind. The additional union
   * types added in {@link VMKindToken} are also honoured.
   */
  private boolean checkVMKind(final VMKind vmKind, final VMKind expectedKind) {
    if (expectedKind == VMKindToken.SERVER) {
      return (vmKind == VMKind.ACCESSOR) || (vmKind == VMKind.DATASTORE);
    }
    if (expectedKind == VMKindToken.ALL) {
      return true;
    }
    return vmKind == expectedKind;
  }

  /**
   * Profile used by GemFireXD for exchanging information including
   * {@link VMKind} and server groups will all other GemFireXD aware VMs in the
   * distributed system.
   * 
   * @author swale
   */
  public static final class GfxdProfile extends DistributionAdvisor.Profile
      implements GfxdSerializable {

    /** the kind of VM */
    private VMKind vmKind;

    /** the server groups of the VM */
    private SortedSet<String> serverGroups;

    // bitmasks for the "flags" member below
    /** for persist-dd property */
    private static final byte F_PERSISTDD = 0x1;
    /** for a consistent locale of the database across DS */
    private static final byte F_HASLOCALE = 0x2;

    private static final byte F_HAS_SPARK_DRIVERURL = 0x4;

    private static final byte F_HAS_PROCESSOR_COUNT = 0x8;

    private static final byte F_HAS_LONG_CATALOG_VERSION = 0x10;

    private static final byte F_HAS_USABLE_HEAP = 0x20;
    // end bitmasks

    /** OR of various bitmasks above */
    private byte flags;

    /**
     * true if the DDL replay is complete and node is completely ready for
     * DDL/DML executions
     */
    private boolean initialized;
    
    /**
     * String representation of locale of the database
     */
    private String dbLocaleStr;

    /**
     * The total number of processors on this node.
     */
    private int numProcessors;

    /**
     * Driver port for spark. Is set only if this node is the primary lead node.
     */
    private String sparkDriverUrl;

    /**
     * The schema version of ExternalCatalog implementation (SnappyHiveExternalCatalog)
     * is tracked in the profile because it needs to be exchanged with other nodes
     * to make sure that it is consistent on all the nodes.
     */
    private AtomicLong catalogSchemaVersion;

    /**
     * The usable heap size of this VM in bytes. This includes the memory till
     * the critical-heap-percentage limit.
     */
    private long usableHeap;

    /** for deserialization */
    public GfxdProfile() {
      this.initialized = true;
    }

    /** construct a new instance for given member and with given version */
    public GfxdProfile(InternalDistributedMember memberId, int version,
        String sparkUrl, long catalogVersion) {
      super(memberId, version);
      this.initialized = true;
      this.numProcessors = Runtime.getRuntime().availableProcessors();
      this.sparkDriverUrl = sparkUrl;
      boolean hasURL = sparkDriverUrl != null && !sparkDriverUrl.equals("");
      this.catalogSchemaVersion = new AtomicLong(catalogVersion);
      setHasSparkURL(hasURL);
      GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache != null) {
        MemoryThresholds thresholds = cache.getResourceManager()
            .getHeapMonitor().getThresholds();
        if (thresholds.isCriticalThresholdEnabled()) {
          this.usableHeap = thresholds.getCriticalThresholdBytes();
        }
      }
      if (this.usableHeap <= 0L) {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory > 0 && maxMemory != Long.MAX_VALUE) {
          this.usableHeap = maxMemory;
        } else {
          this.usableHeap = Runtime.getRuntime().totalMemory();
        }
      }
      initFlags();
    }

    private void initFlags() {
      this.flags |= F_HASLOCALE;
      this.flags |= F_HAS_PROCESSOR_COUNT;
      this.flags |= F_HAS_LONG_CATALOG_VERSION;
      this.flags |= F_HAS_USABLE_HEAP;
    }

    public final VMKind getVMKind() {
      return this.vmKind;
    }

    public final void setVMKind(VMKind kind) {
      this.vmKind = kind;
    }

    public final SortedSet<String> getServerGroups() {
      return this.serverGroups;
    }

    public final void setServerGroups(SortedSet<String> groups) {
      this.serverGroups = groups;
    }

    public final void setLocale(String l) {
      this.dbLocaleStr = l;
    }

    public final void setPersistentDD(boolean isPersistDD) {
      if (isPersistDD) {
        this.flags |= F_PERSISTDD;
      }
      else if (isPersistDD()) {
        this.flags &= ~F_PERSISTDD;
      }
    }

    public final boolean isPersistDD() {
      return (this.flags & F_PERSISTDD) != 0;
    }

    public final void setHasSparkURL(boolean hasSparkURL) {
      if (hasSparkURL) {
        this.flags |= F_HAS_SPARK_DRIVERURL;
      }
      else if (hasSparkURL()) {
        this.flags &= ~F_HAS_SPARK_DRIVERURL;
      }
    }

    public final boolean hasSparkURL() {
      return (this.flags & F_HAS_SPARK_DRIVERURL) != 0;
    }

    public final long getCatalogSchemaVersion() {
      return catalogSchemaVersion.get();
    }

    public final void updateCatalogSchemaVersion(long version) {
      // update the catalog version only if it is higher than the one already present
      while (true) {
        long currentVersion = catalogSchemaVersion.get();
        if (version > currentVersion) {
          if (catalogSchemaVersion.compareAndSet(currentVersion, version)) {
            return;
          }
        } else return;
      }
    }

    public final void incrementCatalogSchemaVersion() {
      this.catalogSchemaVersion.incrementAndGet();
    }

    public final void setInitialized(boolean initialized) {
      this.initialized = initialized;
    }

    public final boolean getInitialized() {
      return this.initialized;
    }
    
    public final String getLocale() {
      return this.dbLocaleStr;
    }

    public final int getNumProcessors() {
      return this.numProcessors;
    }

    public final long getUsableHeap() {
      return this.usableHeap;
    }

    @Override
    public void processIncoming(DistributionManager dm, String adviseePath,
        boolean removeProfile, boolean exchangeProfiles,
        final List<Profile> replyProfiles, LogWriterI18n logger) {
      final GemFireStore memStore = GemFireStore.getBootedInstance();
      final GemFireCacheImpl cache;
      if (memStore != null) {
        if (hasSparkURL() && (memStore.getMyVMKind() != VMKind.LOCATOR)) {
          CallbackFactoryProvider.getClusterCallbacks().
              launchExecutor(this.sparkDriverUrl, this.peerMemberId);
        }
        if ((cache = memStore.getGemFireCache()) != null) {
          if (cache.updateNodeStatus(getDistributedMember(), this.initialized)) {
            if (logger.fineEnabled()) {
              logger.fine("GfxdProfile: set the node " + getDistributedMember()
                  + " as " + (this.initialized ? "" : "un") + "initialized");
            }
          }
        }
        handleDistributionAdvisee(memStore.getAdvisee(), removeProfile,
            exchangeProfiles, replyProfiles);
        if (exchangeProfiles) {
          // also add profiles for all available network servers
          final FabricService service = FabricServiceManager
              .currentFabricServiceInstance();
          if (service != null) {
            for (NetworkInterface ni : service.getAllNetworkServers()) {
              replyProfiles.add(((DistributionAdvisee)ni).getProfile());
            }
          }
        }
      }
    }
    public String getSparkDriverURL() { return sparkDriverUrl; }

    @Override
    public byte getGfxdID() {
      return GFXD_PROFILE;
    }

    @Override
    public int getDSFID() {
      return GFXD_TYPE;
    }

    @Override
    public final void toData(DataOutput out) throws IOException {
      //GfxdDataSerializable.writeGfxdHeader(this, out);
      super.toData(out);
      out.writeByte(this.vmKind.ordinal());
      // maintain the sorted order but don't assume a TreeSet
      if (this.serverGroups == null) {
        InternalDataSerializer.writeArrayLength(-1, out);
      }
      else {
        InternalDataSerializer.writeArrayLength(this.serverGroups.size(), out);
        for (String group : this.serverGroups) {
          DataSerializer.writeString(group, out);
        }
      }
      Version version = InternalDataSerializer.getVersionForDataStream(out);
      boolean isPre12Version = Version.SQLF_11.compareTo(version) >= 0;
      boolean isPre20Version = Version.GFXD_20.compareTo(version) > 0;
      byte flgs = this.flags;
      // no locale in GFXD <= 1.1
      if (isPre12Version) {
        flgs &= ~F_HASLOCALE;
      }
      if (isPre20Version) {
        // no processor count
        flgs &= ~F_HAS_PROCESSOR_COUNT;
      }
      out.writeByte(flgs);
      out.writeBoolean(this.initialized);
      // write the locale
      if (!isPre12Version) {
        DataSerializer.writeString(dbLocaleStr, out);
      }
      if (!isPre20Version) {
        out.writeInt(this.numProcessors);
      }
      if (hasSparkURL()) {
        DataSerializer.writeString(sparkDriverUrl, out);
      }
      out.writeLong(this.catalogSchemaVersion.get());
      out.writeLong(this.usableHeap);
    }

    @Override
    public final void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      super.fromData(in);
      this.vmKind = VMKind.fromOrdinal(in.readByte());
      // restore the sorted order by reading into a TreeSet
      int numGroups = InternalDataSerializer.readArrayLength(in);
      if (numGroups == -1) {
        this.serverGroups = null;
      }
      else {
        this.serverGroups = new TreeSet<String>();
        while (numGroups-- > 0) {
          this.serverGroups.add(DataSerializer.readString(in));
        }
      }
      this.flags = in.readByte();
      this.initialized = in.readBoolean();
      if ((this.flags & F_HASLOCALE) != 0) {
        this.dbLocaleStr = DataSerializer.readString(in);
      }
      if ((this.flags & F_HAS_PROCESSOR_COUNT) != 0) {
        this.numProcessors = in.readInt();
      }
      // initialize the flags for possible further serializations
      initFlags();
      if (hasSparkURL()) {
        this.sparkDriverUrl = DataSerializer.readString(in);
      }
      // older version nodes have integer catalog version while new ones use long
      if ((this.flags & F_HAS_LONG_CATALOG_VERSION) != 0) {
        this.catalogSchemaVersion = new AtomicLong(in.readLong());
      } else {
        this.catalogSchemaVersion = new AtomicLong(in.readInt());
      }
      if ((this.flags & F_HAS_USABLE_HEAP) != 0) {
        this.usableHeap = in.readLong();
      }
    }

    @Override
    public final StringBuilder getToStringHeader() {
      return new StringBuilder("GfxdProfile");
    }

    @Override
    public final void fillInToString(StringBuilder sb) {
      super.fillInToString(sb);
      sb.append("; vmKind=").append(this.vmKind);
      sb.append("; serverGroups=").append(this.serverGroups);
      sb.append("; sparkDriverUrl=").append(this.sparkDriverUrl);
      sb.append("; flags=0x").append(Integer.toHexString(this.flags));
      sb.append("; initialized=").append(this.initialized);
      sb.append("; dbLocaleStr=").append(this.dbLocaleStr);
      sb.append("; numProcessors=").append(this.numProcessors);
      sb.append("; catalogVersion=").append(this.catalogSchemaVersion.get());
      sb.append("; usableHeap=").append(this.usableHeap);
    }
  }

  /**
   * A dummy {@link DistributionAdvisee} that is sent out to gather information
   * of remote locators including pure GFE ones.
   * 
   * @author swale
   */
  private final class DummyControllerAdvisee implements DistributionAdvisee {

    private final int serialNumber = createSerialNumber();

    @Override
    public Profile getProfile() {
      final ControllerProfile cp = new ControllerProfile(myProfile
          .getDistributedMember(), incrementAndGetVersion());
      cp.setHost("");
      // the negative value of port is an indicator to remote VMs to not
      // register this profile, rather only send theirs during profile exchange
      cp.setPort(-1);
      cp.serialNumber = this.serialNumber;
      cp.finishInit();
      return cp;
    }

    @Override
    public void fillInProfile(Profile profile) {
      // nothing to be done for the fake profile
    }

    @Override
    public DistributionAdvisor getDistributionAdvisor() {
      return GfxdDistributionAdvisor.this;
    }

    @Override
    public String getFullPath() {
      return "SnappyData.DummyCacheDistributionAdvisee";
    }

    @Override
    public String getName() {
      return getFullPath();
    }

    @Override
    public DistributionAdvisee getParentAdvisee() {
      return null;
    }

    @Override
    public int getSerialNumber() {
      return this.serialNumber;
    }

    @Override
    public InternalDistributedSystem getSystem() {
      return getAdvisee().getSystem();
    }

    @Override
    public DM getDistributionManager() {
      return getSystem().getDistributionManager();
    }

    @Override
    public CancelCriterion getCancelCriterion() {
      return getSystem().getCancelCriterion();
    }
  }

  /**
   * Extension to {@link VMKind} to add custom values used internally as tokens.
   */
  static final class VMKindToken extends VMKind {

    private VMKindToken(final String name, final int ordinal) {
      super(name, ordinal);
    }

    /** indicates all accessor and datastore VMs */
    static final VMKind SERVER = new VMKindToken("server", 4);

    /** indicates all VMs */
    static final VMKind ALL = new VMKindToken("all", 5);
  }
}
