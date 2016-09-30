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
package com.pivotal.gemfirexd.internal.engine.management.impl;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Set;
import java.util.Map.Entry;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.deadlock.DeadlockDetector;
import com.gemstone.gemfire.distributed.internal.deadlock.Dependency;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.execute.FunctionStats;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import com.pivotal.gemfirexd.internal.engine.diag.DistributedMembers;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.NetworkServerConnectionStats;
import com.pivotal.gemfirexd.internal.engine.management.NetworkServerNestedConnectionStats;
import com.pivotal.gemfirexd.internal.engine.management.GfxdMemberMXBean.GfxdMemberMetaData;
import com.pivotal.gemfirexd.internal.engine.management.impl.InternalManagementService.ConnectionWrapperHolder;
import com.pivotal.gemfirexd.internal.engine.procedure.DistributedProcedureCallFunction;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;

/**
*
* @author Abhishek Chaudhari
* @since gfxd 1.0
*/
public class GfxdMemberMBeanBridge implements Cleanable, Updatable<GfxdMemberMBeanBridge> {
  private final String[] EMPTY_STRING_ARRAY = new String[0];

  private InternalDistributedMember member;
  private Set<String>               serverGroups;
  private ConnectionWrapperHolder   connectionWrapperHolder;
  private GfxdMemberMetaData        gfxdMemberMetaData;

  private NetworkServerConnectionStats clientConnectionStats;
  private NetworkServerConnectionStats peerConnectionStats;
  private NetworkServerNestedConnectionStats nestedConnectionStats;
  private NetworkServerNestedConnectionStats internalConnectionStats;

  private LogWriter logWriter;

  // cached statements
  private PreparedStatement metadataStatement;

  private FunctionStats distributedProcedureCallFunctionStats;

  public GfxdMemberMBeanBridge(InternalDistributedMember member, Set<String> serverGroups, ConnectionWrapperHolder connectionHolder) {
    this.member                  = member;
    this.serverGroups            = serverGroups;
    this.connectionWrapperHolder = connectionHolder;
    this.logWriter               = Misc.getCacheLogWriter();

    this.clientConnectionStats   = newNetworkServerConnectionStats("Client");
    this.peerConnectionStats     = newNetworkServerConnectionStats("Peer");
    this.nestedConnectionStats   = newNetworkServerNestedConnectionStats("Nested");
    this.internalConnectionStats = newNetworkServerNestedConnectionStats("Internal");
  }

  private static NetworkServerConnectionStats newNetworkServerConnectionStats(String type) {
    return new NetworkServerConnectionStats(type,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO);
  }

  private static NetworkServerNestedConnectionStats newNetworkServerNestedConnectionStats(String type) {
    return new NetworkServerNestedConnectionStats(type,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO);
  }

  public String getName() {
    return this.member.getName();
  }

  public String getId() {
    return this.member.getId();
  }

  public String[] getGroups() {
    return this.serverGroups.toArray(EMPTY_STRING_ARRAY);// NOTE: these are different from GemFire 'groups'
  }

  private VMKind getVmKind() {
    return GemFireXDUtils.getMyVMKind();
  }

  public boolean isDataStore() {
    VMKind vmKind = getVmKind();
    return vmKind != null && vmKind.isStore();
  }

  public boolean isLocator() {
    VMKind vmKind = getVmKind();
    return vmKind != null && vmKind.isLocator();
  }

  public boolean isLead() {
    GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
    GfxdDistributionAdvisor.GfxdProfile profile = advisor.getProfile((InternalDistributedMember)member);
    if(profile != null && profile.hasSparkURL()){
      return true;
    }else{
      return false;
    }
  }

  public NetworkServerConnectionStats listNetworkServerClientConnectionStats() {
    return this.clientConnectionStats;
  }

  public NetworkServerConnectionStats listNetworkServerPeerConnectionStats() {
    return this.peerConnectionStats;

  }

  public NetworkServerNestedConnectionStats listNetworkServerNestedConnectionStats() {
    return this.nestedConnectionStats;
  }

  public NetworkServerNestedConnectionStats listNetworkServerInternalConnectionStats() {
    return this.internalConnectionStats;
  }

  public int getProcedureCallsCompleted() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache != null) {
      if (distributedProcedureCallFunctionStats == null) {
        InternalDistributedSystem ds = cache.getDistributedSystem();
        distributedProcedureCallFunctionStats = FunctionStats.getFunctionStats(
            DistributedProcedureCallFunction.FUNCTIONID, ds);
      }
      return distributedProcedureCallFunctionStats.getFunctionExecutionsCompleted();
    }
    return ManagementConstants.ZERO;
  }

  public int getProcedureCallsInProgress() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache != null) {
      if (distributedProcedureCallFunctionStats == null) {
        InternalDistributedSystem ds = cache.getDistributedSystem();
        distributedProcedureCallFunctionStats = FunctionStats.getFunctionStats(
            DistributedProcedureCallFunction.FUNCTIONID, ds);
      }
      return distributedProcedureCallFunctionStats.getFunctionExecutionsRunning();
    }
    return ManagementConstants.ZERO;
  }

//  public TabularData getCachePerfStats() {
//    return null;
//  }

  public GfxdMemberMetaData fetchMetadata() {
    try {
      return retrieveMetadata();
    } catch (SQLException e) {
      this.logWriter.info("Error occurred while fetching MetaData for " + getId() +". Reason: " + e.getMessage());
      if (this.logWriter.fineEnabled()) {
        this.logWriter.fine(e);
      }
      return ManagementUtils.MEMBER_METADATA_NA;
    }
  }

  private GfxdMemberMetaData retrieveMetadata() throws SQLException {
    if (this.connectionWrapperHolder.hasConnection()) {
      if (this.metadataStatement == null) {
        this.metadataStatement = connectionWrapperHolder.getConnection().prepareStatement("<local>SELECT * FROM SYS.MEMBERS WHERE ID=?");
        this.metadataStatement.setString(1, getId());
      }
      ResultSet resultSet = this.metadataStatement.executeQuery();

      try {
        if (resultSet.next()) {
//          ResultSetMetaData metaData = resultSet.getMetaData();
//          int columnCount = metaData.getColumnCount();
//          for (int i = 1; i <= columnCount; i++) {
//            System.out.println(metaData.getColumnName(i) +"-"+metaData.getColumnLabel(i)+"-"+metaData.getColumnTypeName(i));
//          }
          if (this.gfxdMemberMetaData == null || this.gfxdMemberMetaData == ManagementUtils.MEMBER_METADATA_NA) {
            this.gfxdMemberMetaData = new GfxdMemberMetaData(
                resultSet.getString(DistributedMembers.MEMBERID),
                resultSet.getString(DistributedMembers.VMKIND),
                resultSet.getString(DistributedMembers.STATUS),
                resultSet.getString(DistributedMembers.HOSTDATA),
                resultSet.getString(DistributedMembers.ISELDER),
                resultSet.getString(DistributedMembers.IPADDRESS),
                resultSet.getString(DistributedMembers.HOST),
                resultSet.getString(DistributedMembers.PID),
                resultSet.getString(DistributedMembers.PORT),
                resultSet.getString(DistributedMembers.ROLES),
                resultSet.getString(DistributedMembers.NETSERVERS),
                resultSet.getString(DistributedMembers.THRIFTSERVERS),
                resultSet.getString(DistributedMembers.LOCATOR),
                resultSet.getString(DistributedMembers.SERVERGROUPS),
                resultSet.getString(DistributedMembers.MANAGERINFO),
                resultSet.getString(DistributedMembers.SYSTEMPROPS),
                resultSet.getString(DistributedMembers.GFEPROPS),
                resultSet.getString(DistributedMembers.GFXDPROPS));
          } else {
            this.gfxdMemberMetaData.updateMetadata(
                resultSet.getString(DistributedMembers.VMKIND),
                resultSet.getString(DistributedMembers.STATUS),
                resultSet.getString(DistributedMembers.HOSTDATA),
                resultSet.getString(DistributedMembers.ISELDER),
                resultSet.getString(DistributedMembers.IPADDRESS),
                resultSet.getString(DistributedMembers.HOST),
                resultSet.getString(DistributedMembers.PID),
                resultSet.getString(DistributedMembers.PORT),
                resultSet.getString(DistributedMembers.ROLES),
                resultSet.getString(DistributedMembers.NETSERVERS),
                resultSet.getString(DistributedMembers.THRIFTSERVERS),
                resultSet.getString(DistributedMembers.LOCATOR),
                resultSet.getString(DistributedMembers.SERVERGROUPS),
                resultSet.getString(DistributedMembers.MANAGERINFO),
                resultSet.getString(DistributedMembers.SYSTEMPROPS),
                resultSet.getString(DistributedMembers.GFEPROPS),
                resultSet.getString(DistributedMembers.GFXDPROPS));
          }
        }
      } finally {
        resultSet.close();
      }
    } else {
      this.gfxdMemberMetaData = ManagementUtils.MEMBER_METADATA_NA;
    }
    return this.gfxdMemberMetaData;
  }

  public float fetchEvictionPercent() {
    return GfxdSystemProcedures.GET_EVICTION_HEAP_PERCENTAGE();
  }

  public void updateEvictionPercent(float newValue) throws StandardException {    
    GfxdSystemProcedures.SET_EVICTION_HEAP_PERCENTAGE(newValue);
  }

  public float fetchCriticalPercent() {
    return GfxdSystemProcedures.GET_CRITICAL_HEAP_PERCENTAGE();
  }

  public void updateCriticalPercent(float newValue) throws StandardException {
    GfxdSystemProcedures.SET_CRITICAL_HEAP_PERCENTAGE(newValue);
  }

  @SuppressWarnings("rawtypes")
  public String detectDeadlocks() {
    Set<Dependency> dependencies = DeadlockDetector.collectAllDependencies(member);
    return DeadlockDetector.prettyFormat(dependencies);
  }

  public String detectDeadlocksAlt() {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    long[] findDeadlockedThreads = threadMXBean.findDeadlockedThreads();
    StringBuilder builder = new StringBuilder();

    if (findDeadlockedThreads == null || findDeadlockedThreads.length == 0) {
      builder.append("No deadlocks detected.");
    } else {
      ThreadInfo[] threadsInfo = threadMXBean.getThreadInfo(findDeadlockedThreads);
      for (ThreadInfo threadInfo : threadsInfo) {
        builder.append(threadInfo.toString()).append(ManagementUtils.LINE_SEPARATOR);
      }
    }
    return builder.toString();
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((member == null) ? 0 : member.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    GfxdMemberMBeanBridge other = (GfxdMemberMBeanBridge) obj;
    if (member == null) {
      if (other.member != null) {
        return false;
      }
    } else if (!member.equals(other.member)) {
      return false;
    }
    return true;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(GfxdMemberMBeanBridge.class.getSimpleName()).append(" [member=").append(member).append("]");
    return builder.toString();
  }

  @Override
  public void cleanUp() {
    try {
      if (this.metadataStatement != null && !this.metadataStatement.isClosed()) {
        this.metadataStatement.cancel();
        this.metadataStatement.close();
      }
    } catch (SQLException e) {
      if (this.logWriter.fineEnabled()) {
        this.logWriter.fine(e);
      }
    }
    this.member = null;
  }

//  @Override
  public void update() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache != null) {
      InternalDistributedSystem system = cache.getDistributedSystem();
      StatisticsType connectionStatsType = system.findType(ConnectionStats.name);
      if (connectionStatsType != null) {
        Statistics[] foundStatistics = system.findStatisticsByType(connectionStatsType);

        for (Statistics statistics : foundStatistics) {
          peerConnectionStats.updateNetworkServerConnectionStats(
              statistics.getLong("peerConnectionsOpened"),
              statistics.getLong("peerConnectionsClosed"),
              statistics.getLong("peerConnectionsAttempted"),
              statistics.getLong("peerConnectionsFailed"),
              statistics.getLong("peerConnectionsLifeTime"),
              statistics.getLong("peerConnectionsOpen"),
              0 );

          // Other peer connection stats
//          statistics.getLong("peerConnectionsOpenTime");

          nestedConnectionStats.updateNetworkServerConnectionStats(
              statistics.getLong("nestedConnectionsOpened"),
              statistics.getLong("nestedConnectionsClosed"),
              statistics.getLong("nestedConnectionsOpen") );

          // Other nested connection stats
//          statistics.getLong("nestedConnectionsOpen");

          internalConnectionStats.updateNetworkServerConnectionStats(
              statistics.getLong("internalConnectionsOpened"),
              statistics.getLong("internalConnectionsClosed"),
              statistics.getLong("internalConnectionsOpen") );

          // Other internal connection stats
//          statistics.getLong("internalConnectionsOpen");

          clientConnectionStats.updateNetworkServerConnectionStats(
              statistics.getLong("clientConnectionsOpened"),
              statistics.getLong("clientConnectionsClosed"),
              statistics.getLong("clientConnectionsAttempted"),
              statistics.getLong("clientConnectionsFailed"),
              statistics.getLong("clientConnectionsLifeTime"),
              statistics.getLong("clientConnectionsOpen"),
              statistics.getLong("clientConnectionsIdle") );

          // Other Client stats
//          statistics.getLong("clientConnectionsQueued");
//          statistics.getLong("clientConnectionsTotalBytesRead");
//          statistics.getLong("clientConnectionsTotalBytesWritten");
//          statistics.getLong("clientConnectionsCreateTime");
//          statistics.getLong("clientCommandsProcessed");
//          statistics.getLong("clientCommandsProcessTime");

          // drda stats
//          statistics.getLong("drdaServerThreads");
//          statistics.getLong("drdaServerWaitingThreads");
//          statistics.getLong("drdaThreadLongWaits");
//          statistics.getLong("drdaThreadIdleTime");
        }
//        System.out.println("ABHISHEK: GfxdMemberMBeanBridge.update(1) "+peerConnectionStats);
//        System.out.println("\tGfxdMemberMBeanBridge.update(2) "+nestedConnectionStats);
//        System.out.println("\tGfxdMemberMBeanBridge.update(3) "+internalConnectionStats);
//        System.out.println("\tGfxdMemberMBeanBridge.update(4) "+clientConnectionStats);
      }
    }
  }

//  @Override
  public GfxdMemberMBeanBridge getSelf() {
    return this;
  }

//  @Override
  public void setUpdateToken(int updateToken) {
    // NO-OP for now
  }

  public String[] activeThreads() {
	    if (Thread.getAllStackTraces().keySet().size() > 0) {
	      String[] strArr = new String[Thread.getAllStackTraces().keySet().size()];
	      int i = 0;
	      for (Thread threadObj : Thread.getAllStackTraces().keySet()) {
	        strArr[i] = "THREAD-NAME =" + threadObj.getName() + " THREAD-ID=" + threadObj.getId();
	        ++i;
	      }
	      return strArr;
	    } else {
	      return null;
	    }

	  }


  public String[] getStack(String id) {
    if (id != null && id.length() > 0) {
      Set<Entry<Thread, StackTraceElement[]>> threadEntrySet = Thread.getAllStackTraces().entrySet();
      for (Entry<Thread, StackTraceElement[]> threadStack : threadEntrySet){
        Thread thread = threadStack.getKey();
        try{
          Long.parseLong(id);
        }catch(Exception ex){
          return new String[]{"id =" + id + " is not a valid Thread ID."};
        }
        if(thread.getId() ==  Long.parseLong(id)  ){
          StringBuilder sb = new StringBuilder();
          for (StackTraceElement element : thread.getStackTrace()){
            sb.append(element.toString());
            sb.append("\n");
          }
          return new String[]{sb.toString()};
        }
      }

      return new String[]{"No matching thread id found."};
    } else {
      if (Thread.getAllStackTraces().keySet().size() > 0) {
        StringBuilder sb = new StringBuilder();
        Iterator<Entry<Thread, StackTraceElement[]>> it =  Thread.getAllStackTraces().entrySet().iterator();

        while(it.hasNext()){
          Entry<Thread, StackTraceElement[]> entry = it.next();
          sb.append("THREAD_ID=" + entry.getKey().getId());
          sb.append(System.getProperty("line.separator"));
          StackTraceElement[] stackTraceElement = entry.getValue();
          for (StackTraceElement threadStack :   stackTraceElement){
            sb.append( threadStack.toString());
            sb.append(System.getProperty("line.separator"));
          }
          sb.append(System.getProperty("line.separator"));
          sb.append(System.getProperty("line.separator"));
        }
        return new String[]{sb.toString()};
      } else {
        String str[] = new String[1];
        str[0] = "There are no stack traces for any live threads";
        return str;
      }
    }
  }
}
