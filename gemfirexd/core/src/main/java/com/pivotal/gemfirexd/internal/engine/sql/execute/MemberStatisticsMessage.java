package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.HostStatHelper;
import com.gemstone.gemfire.internal.LinuxSystemStats;
import com.gemstone.gemfire.internal.PureJavaMode;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.SolarisSystemStats;
import com.gemstone.gemfire.internal.VMStatsContract;
import com.gemstone.gemfire.internal.WindowsSystemStats;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.stats50.VMStats50;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.gemstone.gemfire.management.internal.ManagementStrings;
import com.gemstone.gemfire.management.internal.beans.stats.StatsKey;
import com.gemstone.gemfire.management.internal.beans.stats.VMStatsMonitor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdListResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.NetworkServerConnectionStats;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;

/**
 * Created by skapse on 24/11/16.
 */
public class MemberStatisticsMessage extends MemberExecutorMessage {

  private static final long MBFactor = 1024 * 1024;

  private Statistics vmStats;
  private Statistics vmHeapStats;
  private Statistics systemStat;


  /** Default constructor for deserialization. Not to be invoked directly. */
  public MemberStatisticsMessage() {
    super(true);
  }

  public MemberStatisticsMessage(
      final ResultCollector<Object, Object> rc) {
    super(rc, null, false, true);
  }

  private MemberStatisticsMessage(final MemberStatisticsMessage other){
    super(other);
  }

  @Override
  protected void execute() throws Exception {
    //lastResult(GemFireCacheImpl.getExisting().getDistributedSystem().getMemberId());

    InternalDistributedSystem ids = GemFireCacheImpl.getExisting().getDistributedSystem();
    String memberId = ids.getMemberId();

    updateStats(ids);

    /*Statistics systemStat = null;

    if (PureJavaMode.osStatsAreAvailable()) {
      Statistics[] systemStats = null;

      if (HostStatHelper.isSolaris()) {
        systemStats = ids.findStatisticsByType(SolarisSystemStats.getType());
      } else if (HostStatHelper.isLinux()) {
        systemStats = ids.findStatisticsByType(LinuxSystemStats.getType());
      } else if (HostStatHelper.isOSX()) {
        systemStats = null;//@TODO once OSX stats are implemented
      } else if (HostStatHelper.isWindows()) {
        systemStats = ids.findStatisticsByType(WindowsSystemStats.getType());
      }

      if (systemStats != null) {
        systemStat = systemStats[0];
      }
    }*/

    // Members clients stats
    NetworkServerConnectionStats clientConnectionStats = getMemberClientConnectionStats(ids);

    Map memberStatsMap = new HashMap();
    memberStatsMap.put("id", memberId);
    memberStatsMap.put("name", ids.getName());
    //memberStatsMap.put("stats", ids.getStatistics());
    memberStatsMap.put("host", getHost());
    memberStatsMap.put("locator", isLocator());
    memberStatsMap.put("cacheServer", isServer());
    memberStatsMap.put("lead", isLead(ids.getDistributedMember()));
    memberStatsMap.put("maxMemory", getMaxMemory());
    memberStatsMap.put("freeMemory", getFreeMemory());
    memberStatsMap.put("totalMemory", getTotalMemory());
    memberStatsMap.put("usedMemory", getUsedMemory());
    memberStatsMap.put("cpuActive", getHostCpuUsage());
    memberStatsMap.put("clients", clientConnectionStats.getConnectionsOpen());

    lastResult(memberStatsMap);
  }

  private void updateStats(InternalDistributedSystem system){

    //Statistics systemStat = null;

    if (PureJavaMode.osStatsAreAvailable()) {
      Statistics[] systemStats = null;

      if (HostStatHelper.isSolaris()) {
        systemStats = system.findStatisticsByType(SolarisSystemStats.getType());
      } else if (HostStatHelper.isLinux()) {
        systemStats = system.findStatisticsByType(LinuxSystemStats.getType());
      } else if (HostStatHelper.isOSX()) {
        systemStats = null;//@TODO once OSX stats are implemented
      } else if (HostStatHelper.isWindows()) {
        systemStats = system.findStatisticsByType(WindowsSystemStats.getType());
      }

      if (systemStats != null) {
        //systemStat = systemStats[0];
        this.systemStat = systemStats[0];
      }
    }

    VMStatsContract vmStatsContract = system.getStatSampler().getVMStats();
    //VMStatsMonitor vmStatsMonitor = new VMStatsMonitor(ManagementStrings.VM_STATS_MONITOR.toLocalizedString());

    if (vmStatsContract != null && vmStatsContract instanceof VMStats50){
      VMStats50 vmStats50 = (VMStats50) vmStatsContract;
      Statistics vmStats = vmStats50.getVMStats();
      if (vmStats != null) {
        //vmStatsMonitor.addStatisticsToMonitor(vmStats);
        this.vmStats = vmStats;
      }

      Statistics vmHeapStats = vmStats50.getVMHeapStats();
      if (vmHeapStats != null) {
        //vmStatsMonitor.addStatisticsToMonitor(vmHeapStats);
        this.vmHeapStats = vmHeapStats;
      }
    }
  }

  private String getHost(){
    try {
      InetAddress addr = SocketCreator.getLocalHost();
      return addr.getHostName();
    } catch (UnknownHostException ex) {
      return ManagementConstants.DEFAULT_HOST_NAME;
    }
  }

  /**
   *
   * @return true if member contains one locator.
   */
  private boolean isLocator() {
    return Locator.hasLocator();
  }

  /**
   *
   * @return true if member has a server
   */
  private boolean isServer() {
    return GemFireCacheImpl.getExisting().isServer();
  }


  /**
   *
   * @return true if member has a lead
   */
  private boolean isLead(InternalDistributedMember member) {
    GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
    GfxdDistributionAdvisor.GfxdProfile profile = advisor.getProfile(member);
    if(profile != null && profile.hasSparkURL()){
      return true;
    }else{
      return false;
    }
  }

  private int getHostCpuUsage() {
    if (systemStat != null) {
      return systemStat.get(StatsKey.SYSTEM_CPU_ACTIVE).intValue();
    } else {
      return ManagementConstants.NOT_AVAILABLE_INT;
    }
  }

  private long getMaxMemory() {
    /*Runtime rt = Runtime.getRuntime();
    return rt.maxMemory() / MBFactor;*/

    return vmStats.get("maxMemory").longValue()/MBFactor;
  }

  private long getFreeMemory() {
    /*Runtime rt = Runtime.getRuntime();
    return rt.freeMemory() / MBFactor;*/

    return vmStats.get("freeMemory").longValue()/MBFactor;
  }

  private long getTotalMemory() {
    /*Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() / MBFactor;*/

    return vmStats.get("totalMemory").longValue()/MBFactor;
  }

  public long getUsedMemory() {
    return vmHeapStats.get("usedMemory").longValue()/MBFactor;
  }

  /*public long getUsedMemory(InternalDistributedSystem system) {
    VMStatsContract vmStatsContract = system.getStatSampler().getVMStats();
    VMStatsMonitor vmStatsMonitor = new VMStatsMonitor(ManagementStrings.VM_STATS_MONITOR
        .toLocalizedString());

    if (vmStatsContract != null && vmStatsContract instanceof VMStats50){
      VMStats50 vmStats50 = (VMStats50) vmStatsContract;
      Statistics vmStats = vmStats50.getVMStats();
      if (vmStats != null) {
        vmStatsMonitor.addStatisticsToMonitor(vmStats);
      }

      Statistics vmHeapStats = vmStats50.getVMHeapStats();
      if (vmHeapStats != null) {
        vmStatsMonitor.addStatisticsToMonitor(vmHeapStats);
      }
    }

    return vmStatsMonitor.getStatistic(StatsKey.VM_USED_MEMORY).longValue() / MBFactor;
  }*/

  private NetworkServerConnectionStats getMemberClientConnectionStats(InternalDistributedSystem system){
    NetworkServerConnectionStats clientConnectionStats =
        new NetworkServerConnectionStats("Client",
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO,
        ManagementConstants.ZERO);

    StatisticsType connectionStatsType = system.findType(ConnectionStats.name);
    if (connectionStatsType != null) {
      Statistics[] foundStatistics = system.findStatisticsByType(connectionStatsType);

      for (Statistics statistics : foundStatistics) {
        clientConnectionStats.updateNetworkServerConnectionStats(
            statistics.getLong("clientConnectionsOpened"),
            statistics.getLong("clientConnectionsClosed"),
            statistics.getLong("clientConnectionsAttempted"),
            statistics.getLong("clientConnectionsFailed"),
            statistics.getLong("clientConnectionsLifeTime"),
            statistics.getLong("clientConnectionsOpen"),
            statistics.getLong("clientConnectionsIdle") );
      }
    }
    return clientConnectionStats;
  }

  @Override
  public Set<DistributedMember> getMembers() {
    return getAllGfxdMembers();
  }

  @Override
  public void postExecutionCallback() {

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
    return new MemberStatisticsMessage(this);
  }

  @Override
  public byte getGfxdID() {
    return MEMBER_STATISTICS_MESSAGE;
  }
}
