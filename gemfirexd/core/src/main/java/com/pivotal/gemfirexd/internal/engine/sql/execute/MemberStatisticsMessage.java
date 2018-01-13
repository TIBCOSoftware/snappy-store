package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
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
import com.gemstone.gemfire.internal.WindowsSystemStats;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.gemstone.gemfire.internal.statistics.VMStats;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.gemstone.gemfire.management.internal.beans.stats.StatsKey;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.management.NetworkServerConnectionStats;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;
import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Logger;

public class MemberStatisticsMessage extends MemberExecutorMessage {

  private static final long MBFactor = 1024 * 1024;

  private GemFireCacheImpl gemFireCache;

  private Statistics vmStats;
  private Statistics vmHeapStats;
  private Statistics systemStat;
  private java.util.UUID diskStoreUUID;
  private String diskStoreName;


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

    this.gemFireCache = GemFireCacheImpl.getExisting();
    InternalDistributedSystem ids = gemFireCache.getDistributedSystem();
    String memberId = ids.getMemberId();

    updateStats(ids);

    // Members clients stats
    NetworkServerConnectionStats clientConnectionStats = getMemberClientConnectionStats(ids);

    // Memory Stats
    Map<String, Long> memoryStats = this.getMemoryStatistics();

    Map memberStatsMap = new HashMap();
    memberStatsMap.put("id", memberId);
    memberStatsMap.put("name", ids.getName());
    memberStatsMap.put("host", getHost());
    memberStatsMap.put("userDir", getUserDir());
    memberStatsMap.put("logFile", getLogFile());
    memberStatsMap.put("processId", getProcessId());
    memberStatsMap.put("locator", isLocator());
    memberStatsMap.put("dataServer", isDataServer());
    memberStatsMap.put("activeLead", isActiveLead(ids.getDistributedMember()));
    memberStatsMap.put("lead", isLead());
    memberStatsMap.put("maxMemory", getMaxMemory());
    memberStatsMap.put("freeMemory", getFreeMemory());
    memberStatsMap.put("totalMemory", getTotalMemory());
    memberStatsMap.put("usedMemory", getUsedMemory());

    int cpuActive = getHostCpuUsage();
    memberStatsMap.put("cpuActive", cpuActive >= 0 ? cpuActive : 0);

    memberStatsMap.put("clients", clientConnectionStats.getConnectionsOpen());
    memberStatsMap.put("diskStoreUUID", getDiskStoreUUID());
    memberStatsMap.put("diskStoreName", getDiskStoreName());

    memberStatsMap.put("heapStoragePoolUsed", memoryStats.get("heapStoragePoolUsed"));
    memberStatsMap.put("heapStoragePoolSize", memoryStats.get("heapStoragePoolSize"));
    memberStatsMap.put("heapExecutionPoolUsed", memoryStats.get("heapExecutionPoolUsed"));
    memberStatsMap.put("heapExecutionPoolSize", memoryStats.get("heapExecutionPoolSize"));
    memberStatsMap.put("offHeapStoragePoolUsed", memoryStats.get("offHeapStoragePoolUsed"));
    memberStatsMap.put("offHeapStoragePoolSize", memoryStats.get("offHeapStoragePoolSize"));
    memberStatsMap.put("offHeapExecutionPoolUsed", memoryStats.get("offHeapExecutionPoolUsed"));
    memberStatsMap.put("offHeapExecutionPoolSize", memoryStats.get("offHeapExecutionPoolSize"));

    memberStatsMap.put("heapMemorySize", ( memoryStats.get("heapStoragePoolSize") +
                                           memoryStats.get("heapExecutionPoolSize")));
    memberStatsMap.put("heapMemoryUsed", ( memoryStats.get("heapStoragePoolUsed") +
                                           memoryStats.get("heapExecutionPoolUsed")));
    memberStatsMap.put("offHeapMemorySize", ( memoryStats.get("offHeapStoragePoolSize") +
                                              memoryStats.get("offHeapExecutionPoolSize")));
    memberStatsMap.put("offHeapMemoryUsed", ( memoryStats.get("offHeapStoragePoolUsed") +
                                              memoryStats.get("offHeapExecutionPoolUsed")));

    lastResult(memberStatsMap);
  }

  private void updateStats(InternalDistributedSystem system){

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
        this.systemStat = systemStats[0];
      }
    }

    VMStats vmStats = system.getStatSampler().getVMStats();
    if (vmStats != null) {
      Statistics stats = vmStats.getVMStats();
      if (stats != null) {
        this.vmStats = stats;
      }

      Statistics vmHeapStats = vmStats.getVMHeapStats();
      if (vmHeapStats != null) {
        this.vmHeapStats = vmHeapStats;
      }

      // update disk store details
      Collection<DiskStoreImpl> diskStores = this.gemFireCache.listDiskStores();

      for (DiskStoreImpl dsi : diskStores) {
        if (dsi.getName().equals(GemFireCacheImpl.getDefaultDiskStoreName())) {
          this.diskStoreUUID = dsi.getDiskStoreUUID();
          this.diskStoreName = dsi.getName();
        }
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

  private String getUserDir(){
    return System.getProperty("user.dir");
  }

  private String getLogFile() {
    Logger rootLogger = Logger.getRootLogger();
    Appender appender;
    if (rootLogger != null) {
      Enumeration<?> e = rootLogger.getAllAppenders();
      while (e.hasMoreElements()) {
        appender = (Appender)e.nextElement();
        if (appender instanceof FileAppender) {
          return ((FileAppender)appender).getFile();
        }
      }
    }
    return "";
  }

  private String getProcessId(){
    return Integer.toString(NativeCalls.getInstance().getProcessId());
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
  private boolean isDataServer() {
    return ServerGroupUtils.isDataStore();
  }


  /**
   *
   * @return true if member is a active lead node
   */
  private boolean isActiveLead(InternalDistributedMember member) {
    GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
    GfxdDistributionAdvisor.GfxdProfile profile = advisor.getProfile(member);
    if(profile != null && profile.hasSparkURL()){
      return true;
    }else{
      return false;
    }
  }

  /**
   *
   * @return true if member is a member of leader group
   */
  private boolean isLead() {
    HashSet<String> leadGroup = CallbackFactoryProvider.getClusterCallbacks().getLeaderGroup();
    final boolean isLead = (leadGroup != null && leadGroup.size() > 0) && (ServerGroupUtils.isGroupMember(leadGroup));
    return isLead;
  }

  private int getHostCpuUsage() {
    if (systemStat != null) {
      return systemStat.get(StatsKey.SYSTEM_CPU_ACTIVE).intValue();
    } else {
      return ManagementConstants.NOT_AVAILABLE_INT;
    }
  }

  private long getMaxMemory() {
    return vmStats.get("maxMemory").longValue();
  }

  private long getFreeMemory() {
    return vmStats.get("freeMemory").longValue();
  }

  private long getTotalMemory() {
    return vmStats.get("totalMemory").longValue();
  }

  public long getUsedMemory() {
    return vmHeapStats.get("usedMemory").longValue();
  }

  public java.util.UUID getDiskStoreUUID() {
    return this.diskStoreUUID;
  }

  public String getDiskStoreName() {
    return this.diskStoreName;
  }

  public long getStoragePoolUsed() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getStoragePoolUsedMemory(false) +
        callbacks.getStoragePoolUsedMemory(true);
  }

  public long getStoragePoolSize() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getStoragePoolSize(false) +
        callbacks.getStoragePoolSize(true);
  }

  public long getExecutionPoolUsed() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getExecutionPoolUsedMemory(false) +
        callbacks.getExecutionPoolUsedMemory(true);
  }

  public long getExecutionPoolSize() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getExecutionPoolSize(false) +
        callbacks.getExecutionPoolSize(true);
  }

  public long getOffHeapMemorySize() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getStoragePoolSize(true) +  callbacks.getExecutionPoolSize(true);
  }

  public long getOffHeapMemoryUsed() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getStoragePoolUsedMemory(true) +  callbacks.getExecutionPoolUsedMemory(true);
  }

  public long getHeapMemorySize() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getStoragePoolSize(false) +  callbacks.getExecutionPoolSize(false);
  }

  public long getHeapMemoryUsed() {
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();
    return callbacks.getStoragePoolUsedMemory(false) +  callbacks.getExecutionPoolUsedMemory(false);
  }

  private Map<String, Long> getMemoryStatistics() {
    Map memoryStats = new HashMap<String, Long>();
    StoreCallbacks callbacks = CallbackFactoryProvider.getStoreCallbacks();

    memoryStats.put("heapStoragePoolSize", callbacks.getStoragePoolSize(false));
    memoryStats.put("heapStoragePoolUsed", callbacks.getStoragePoolUsedMemory(false));
    memoryStats.put("heapExecutionPoolSize", callbacks.getExecutionPoolSize(false));
    memoryStats.put("heapExecutionPoolUsed", callbacks.getExecutionPoolUsedMemory(false));

    memoryStats.put("offHeapStoragePoolSize", callbacks.getStoragePoolSize(true));
    memoryStats.put("offHeapStoragePoolUsed", callbacks.getStoragePoolUsedMemory(true));
    memoryStats.put("offHeapExecutionPoolSize", callbacks.getExecutionPoolSize(true));
    memoryStats.put("offHeapExecutionPoolUsed", callbacks.getExecutionPoolUsedMemory(true));

    return memoryStats;
  }

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
