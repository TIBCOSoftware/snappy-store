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
package com.pivotal.gemfirexd.internal.engine.store;

import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.NoMemberFoundException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.FunctionUtils;
import com.pivotal.gemfirexd.internal.engine.sql.execute.FunctionUtils.GetFunctionMembers;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Various server groups related utility methods.
 *
 * @author swale
 */
public final class ServerGroupUtils {

  private ServerGroupUtils() {
    // disallow construction
  }

  public static final String LEADER_SERVERGROUP = "IMPLICIT_LEADER_SERVERGROUP";

  /**
   * Set whether this VM is a server or client and distribute to other VMs.
   */
  public static void setIsServer(boolean serverOrClient) {
    final GemFireStore memStore = Misc.getMemStore();
    memStore.getAdvisee().setVMKind(
        serverOrClient ? VMKind.DATASTORE : VMKind.ACCESSOR);
    // distribute the updated profile to this and other VMs
    memStore.getDistributionAdvisor().distributeProfileUpdate();
  }

  /**
   * Set the GFXD VM server groups of this VM to the given set and distribute to
   * other VMs.
   */
  public static void setServerGroups(SortedSet<String> serverGroups) {
    final GemFireStore memStore = Misc.getMemStore();
    memStore.getAdvisee().setServerGroups(serverGroups);
    // distribute the updated profile to this and other VMs
    memStore.getDistributionAdvisor().distributeProfileUpdate();
  }

  /**
   * Set the GFXD VM server groups of this VM to the given set and distribute to
   * other VMs.
   */
  public static void sendUpdateProfile() {
    final GemFireStore memStore = Misc.getMemStore();
    // distribute the updated profile to this and other VMs
    memStore.getDistributionAdvisor().distributeProfileUpdate();
  }


  /** get the server groups of this VM as a comma-separated string */
  public static String getMyGroups() {
    return SharedUtils.toCSV(Misc.getMemStore().getAdvisee()
        .getServerGroups());
  }

  /** get the server groups of this VM as an array */
  public static String[] getMyGroupsArray() {
    final SortedSet<String> vmGroups = Misc.getMemStore().getAdvisee()
        .getServerGroups();
    String[] groups = new String[vmGroups.size()];
    return vmGroups.toArray(groups);
  }

  /** get the server groups of this VM as a Sorted Set */
  public static SortedSet<String> getMyGroupsSet() {
    final SortedSet<String> vmGroups = Misc.getMemStore().getAdvisee()
        .getServerGroups();
    return new TreeSet<String>(vmGroups);
  }

  /** return true if this VM is member of one of the given groups */
  public static boolean isGroupMember(Set<String> groups) {
    if (groups == null || groups.size() == 0) {
      return true;
    }
    return isGroupMember(Misc.getMemStoreBooting().getAdvisee()
        .getServerGroups(), groups);
  }

  /**
   * Return true if given non-{@link VMKind#LOCATOR} VM is in one of the given
   * server groups.
   */
  public static boolean isGroupMember(DistributedMember member,
      Set<String> groups, boolean isDataStore) {
    return GemFireXDUtils.getGfxdAdvisor().isGroupMember(member, groups,
        isDataStore);
  }

  /**
   * Return true if groups of a VM intersect one of the given groups.
   *
   * @param vmGroups
   *          the server groups of a VM
   * @param groups
   *          the server groups to check
   */
  public static boolean isGroupMember(final Set<String> vmGroups,
      final Set<String> groups) {
    if (groups.size() == 0) {
      return true;
    }
    for (String group : groups) {
      if (vmGroups.contains(group)) {
        return true;
      }
    }
    return false;
  }

  /** return true if the node is having a data store role */
  public static boolean isDataStore() {
    return GemFireXDUtils.getMyVMKind() == VMKind.DATASTORE;
  }
  /** return true if the node is having a accessor role */
  public static boolean isAccessor() {
    return GemFireXDUtils.getMyVMKind() == VMKind.ACCESSOR;
  }
  /**
   * Check if this VM is setup to be a data store for the table in given set of
   * server groups.
   */
  public static boolean isDataStore(String table, Set<String> serverGroups) {
    final boolean isStore = isDataStore();
    boolean isDataStore;
    if (serverGroups == null || serverGroups.size() == 0) {
      isDataStore = isStore;
    } else {
      isDataStore = isStore && isGroupMember(serverGroups);
    }
    if (GemFireXDUtils.TraceConglom ||
        !(table.startsWith("SYSSTAT") || table.startsWith(Misc.SNAPPY_HIVE_METASTORE))) {
      if (isDataStore) {
        LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
        SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_CONGLOM,
            "Using the JVM as an GemFireXD datastore for table [" + table
                + "] with table-default-partitioned="
                + Misc.getMemStore().isTableDefaultPartitioned() + (lcc != null
                    ? " default-persistent=" + lcc.isDefaultPersistent() : ""));
      }
      else {
        LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
        SanityManager.DEBUG_PRINT("info:" + GfxdConstants.TRACE_CONGLOM,
            "Using the JVM as an GemFireXD accessor for table [" + table
                + "] with table-default-partitioned="
                + Misc.getMemStore().isTableDefaultPartitioned() + (lcc != null
                ? " default-persistent=" + lcc.isDefaultPersistent() : ""));
      }
    }
    return isDataStore;
  }

  public static SortedSet<String> getServerGroupsFromContainer(GemFireContainer container) {
    SortedSet<String> tableServerGroups = new TreeSet<String>();
    ExtraTableInfo    extraTableInfo    = container.getExtraTableInfo();
    TableDescriptor   tableDescriptor   = extraTableInfo.getTableDescriptor();
    try {
      DistributionDescriptor distributionDescriptor = tableDescriptor.getDistributionDescriptor(); // TODO Abhishek: when should we use this & why does it throw an exception?
      tableServerGroups.addAll(distributionDescriptor.getServerGroups());
    } catch (StandardException e) { // ignored - tableDescriptor.getDistributionDescriptor() is only an accessor method
    }

    return tableServerGroups;
  }

  /** convert comma separated server group string to SortedSet */
  @SuppressWarnings("unchecked")
  public static SortedSet<String> getServerGroups(String serverGroupsString) {
    return SharedUtils.toSortedSet(serverGroupsString, false);
  }

  /**
   * Get the server groups for given member.
   */
  public static SortedSet<String> getServerGroups(DistributedMember member) {
    final GfxdDistributionAdvisor.GfxdProfile profile = GemFireXDUtils
        .getGfxdProfile(member);
    if (profile != null) {
      return profile.getServerGroups();
    }
    return null;
  }

  /**
   * Returns a {@link FunctionUtils.GfxdExecution} object that can be used to
   * execute a data independent function on the set of {@link DistributedMember}
   * s of the {@link DistributedSystem} having one of the given server groups.
   * If the given set of server groups is null or empty then all members of the
   * {@link DistributedSystem} that are GemFireXD datastores are used for
   * function execution. If one of the members goes down while dispatching or
   * executing the function, an Exception will be thrown.
   *
   * @param serverGroups
   *          set of server group names on which a function is to be executed
   * @param flushTXPendingOps
   *          if true then any batched transactional operations will be flushed
   *
   * @throws DistributedSystemDisconnectedException
   *           if this VM has not yet booted up the GemFireXD database
   */
  public static FunctionUtils.GfxdExecution onServerGroups(
      Set<String> serverGroups, boolean flushTXPendingOps) {
    final DistributedSystem dsys = Misc.getDistributedSystem();
    return new GetGroupMembersFunctionExecutor(dsys, new GetServerGroupMembers(
        serverGroups, false), flushTXPendingOps);
  }

  /**
   * Returns a {@link FunctionUtils.GfxdExecution} object that can be used to
   * execute a data independent function on the set of {@link DistributedMember}
   * s of the {@link DistributedSystem} having one of the given server groups.
   * If the given set of server groups is null or empty then all members of the
   * {@link DistributedSystem} that are GemFireXD datastores are used for
   * function execution. If one of the members goes down while dispatching or
   * executing the function, an Exception will be thrown.
   *
   * @param serverGroups
   *          set of comma separated server group names on which a function is
   *          to be executed
   * @param flushTXPendingOps
   *          if true then any batched transactional operations will be flushed
   *
   * @throws DistributedSystemDisconnectedException
   *           if this VM has not yet booted up the GemFireXD database
   */
  public static FunctionUtils.GfxdExecution onServerGroups(
      String serverGroups, boolean flushTXPendingOps) {
    return onServerGroups(getServerGroups(serverGroups), flushTXPendingOps);
  }

  /**
   * Implementation of {@link GetFunctionMembers} to get the set of members in
   * the given set of server groups.
   *
   * @author swale
   */
  public static final class GetServerGroupMembers implements GetFunctionMembers {

    private final Set<String> serverGroups;

    private final boolean anyOneDataStoreMember;

    private boolean preferablySelf;

    private String tablename;

    public GetServerGroupMembers(final Set<String> serverGroups,
        boolean anyOneMember) {
      this.serverGroups = serverGroups;
      this.anyOneDataStoreMember = anyOneMember;
      this.preferablySelf = false;
      this.tablename = null;
    }

    public void setSelfPreference() {
      this.preferablySelf = true;
    }

    public Set<DistributedMember> getMembers() {
      if (this.anyOneDataStoreMember) {
        final DistributedMember member = GemFireXDUtils.getGfxdAdvisor()
            .adviseDataStore(this.serverGroups, this.preferablySelf);
        if (member != null) {
          return Collections.singleton(member);
        }
        return Collections.emptySet();
      }
      else {
        if (this.serverGroups == null || this.serverGroups.size() == 0) {
          // for default group including all VMs return only datastores
          return GemFireXDUtils.getGfxdAdvisor().adviseDataStores(null);
        }
        // return both datastores and any accessors to execute the DAP
        return GemFireXDUtils.getGfxdAdvisor().adviseOperationNodes(
            this.serverGroups);
      }
    }

    public String getTableName() {
      return this.tablename;
    }

    public void setTableName(String tableName) {
      this.tablename = tableName;
    }

    @Override
    public Set<String> getServerGroups() {
      return this.serverGroups;
    }

    @Override
    public void postExecutionCallback() {
    }
  }

  /**
   * A member executor extension to {@link FunctionUtils.GetMembersFunctionExecutor}
   * that checks for non-null member list in addition to using
   * {@link GetFunctionMembers} interface to obtain the set of members for
   * initial message and failover. The array of member IDs on which the
   * execution happened can also be obtained from
   * {@link FunctionUtils.GfxdExecution#getExecutionNodes()}
   *
   * @author swale
   */
  private static final class GetGroupMembersFunctionExecutor extends
      FunctionUtils.GetMembersFunctionExecutor {

    GetGroupMembersFunctionExecutor(DistributedSystem dsys,
        GetFunctionMembers fMembers, boolean flushTXPendingOps) {
      super(dsys, fMembers, flushTXPendingOps, false);
    }

    @Override
    protected void checkMembers(final Set<DistributedMember> members,
        final Function function) {
      super.checkMembers(members, function);
      for (DistributedMember m : members) {
        if (m == null) {
          // unexpected null member
          throw new NoMemberFoundException(LocalizedStrings
              .MemberFunctionExecutor_NO_MEMBER_FOUND_FOR_EXECUTING_FUNCTION_0
                  .toLocalizedString(function.getId()
                      + " got unexpected null member in server groups ["
                      + this.getMembers.getServerGroups() + ']'), null);
        }
      }
    }
  }
}
