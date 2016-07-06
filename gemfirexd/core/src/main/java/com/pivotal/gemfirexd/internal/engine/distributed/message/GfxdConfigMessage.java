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

package com.pivotal.gemfirexd.internal.engine.distributed.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.NotSerializableException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.sql.SQLException;
import java.util.*;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfigImpl;
import com.gemstone.gemfire.distributed.internal.DistributionStats;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryTimeStatistics;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryTimeStatistics.QueryStatistics;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryTimeStatistics.StatType;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.diag.QueryStatisticsVTI;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.RegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.sizer.ObjectSizer;
import io.snappydata.thrift.ServerType;

/**
 * Message used to obtain and set various configuration parameters of
 * distributed system members. These include java properties, GFE properties,
 * GFXD properties, server groups etc. Most of the member management in
 * GemFireXD will be using this message.
 * 
 * @author swale
 * 
 * @param <T>
 *          the type of result returned by the {@link ResultCollector} passed to
 *          the constructor
 */
public final class GfxdConfigMessage<T> extends MemberExecutorMessage<T> {

  private Set<DistributedMember> members;

  private final AllMembers useAllMembers;

  private Operation configOp;

  private Object opArg;

  private boolean haEnabled;

  private transient boolean serializeMembers;

  private enum AllMembers {
    GIVEN_MEMBERS,
    ALL_MEMBERS,
    ALL_MEMBERS_INCLUDING_ADMIN
  }

  // indexes of various miscelleneous properties as in the array returned by
  // Operation#GET_ALLPROPS
  public final static int SYSPROPS_INDEX = 0;
  public final static int GFEPROPS_INDEX = 1;
  public final static int GFXDPROPS_INDEX = 2;
  public final static int ALLPROPS_MAXINDEX = GFXDPROPS_INDEX;

  public enum Operation {
    GET_SYSPROPS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        final TreeMap<Object, Object> props = new TreeMap<>();
        addSYSProps(props);
        return props;
      }
    },
    GET_GFEPROPS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        final TreeMap<Object, Object> props = new TreeMap<>();
        addGFEProps(props);
        return props;
      }
    },
    GET_GFXDPROPS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        final TreeMap<Object, Object> props = new TreeMap<>();
        addGFXDProps(props);
        return props;
      }
    },
    GET_ALLPROPS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        Properties[] allProps = new Properties[ALLPROPS_MAXINDEX + 1];
        Properties props = new Properties();
        // system properties first
        addSYSProps(props);
        allProps[SYSPROPS_INDEX] = props;
        props = new Properties();
        // GFE properties next
        addGFEProps(props);
        allProps[GFEPROPS_INDEX] = props;
        props = new Properties();
        // GFXD properties next
        addGFXDProps(props);
        allProps[GFXDPROPS_INDEX] = props;
        return allProps;
      }
    },
    GET_JVMSTATS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        TreeMap<Object, Object> map = new TreeMap<>();
        // GC information
        for (GarbageCollectorMXBean gcBean : ManagementFactory
            .getGarbageCollectorMXBeans()) {
          final String gcPrefix = "gc-" + gcBean.getName();
          map.put(gcPrefix + "-collection-count", gcBean.getCollectionCount());
          map.put(gcPrefix + "-collection-time", gcBean.getCollectionTime());
          map.put(gcPrefix + "-memory-pools",
              GemFireXDUtils.toCSV(gcBean.getMemoryPoolNames()));
        }
        // some thread information
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        map.put("thread-count", threadBean.getThreadCount());
        map.put("thread-total-count", threadBean.getTotalStartedThreadCount());
        map.put("thread-peak-count", threadBean.getPeakThreadCount());
        // some memory information
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();
        map.put("memory-heap-max", heapUsage.getMax());
        map.put("memory-heap-committed", heapUsage.getCommitted());
        map.put("memory-heap-used", heapUsage.getUsed());
        map.put("memory-nonheap-max", nonHeapUsage.getMax());
        map.put("memory-nonheap-committed", nonHeapUsage.getCommitted());
        map.put("memory-nonheap-used", nonHeapUsage.getUsed());
        // some more runtime memory information
        Runtime rt = Runtime.getRuntime();
        map.put("memory-free", rt.freeMemory());
        map.put("memory-max", rt.maxMemory());
        map.put("memory-total", rt.totalMemory());
        map.put("available-processors", rt.availableProcessors());
        return map;
      }
    },
    ENABLE_QUERYSTATS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        final boolean enableOrDisable = (Boolean)arg;
        GemFireXDUtils.setOptimizerTrace(enableOrDisable);
        if (enableOrDisable) {
          GemFireXDQueryObserverHolder
              .putInstanceIfAbsent(new GemFireXDQueryTimeStatistics(0));
        }
        else {
          GemFireXDQueryObserverHolder
              .removeObserver(GemFireXDQueryTimeStatistics.class);
        }
        return null;
      }
    },
    GET_QUERYSTATS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        GemFireXDQueryTimeStatistics stats = GemFireXDQueryObserverHolder
            .getObserver(GemFireXDQueryTimeStatistics.class);
        if (stats != null) {
          final ArrayList<HashMap<String, Object>> statsMapList =
              new ArrayList<>();
          HashMap<String, Object> statsMap;
          Iterator<Map.Entry<String, QueryStatistics[]>> mapIter = stats
              .getIterator();
          while (mapIter.hasNext()) {
            Map.Entry<String, QueryStatistics[]> entry = mapIter.next();
            statsMap = new HashMap<>();
            QueryStatistics[] queryStats = entry.getValue();
            statsMap.put(QueryStatisticsVTI.QUERYSTRING, entry.getKey());
            statsMap.put(QueryStatisticsVTI.PARAMSSIZE, 0);
            QueryStatistics optimStats = queryStats[StatType.QUERY_OPTIMIZATION
                .ordinal()];
            String plan = (String)optimStats.getCustomObject();
            if (plan == null) {
              plan = "";
            }
            statsMap.put(QueryStatisticsVTI.QUERYPLAN, plan);
            QueryStatistics totalStats = queryStats[StatType.TOTAL_EXECUTION
                .ordinal()];
            statsMap.put(QueryStatisticsVTI.NUMINVOCATIONS,
                totalStats.getNumInvocations());
            statsMap.put(QueryStatisticsVTI.TOTALNANOS,
                totalStats.getTotalTimeInNanos());
            QueryStatistics distribStats = queryStats[StatType.TOTAL_DISTRIBUTION
                .ordinal()];
            statsMap.put(QueryStatisticsVTI.DISTRIBNANOS,
                distribStats.getTotalTimeInNanos());
            long serNanos = queryStats[StatType.RESULT_HOLDER_SERIALIZATION
                .ordinal()].getTotalTimeInNanos();
            if (serNanos <= 0) {
              serNanos = queryStats[StatType.RESULT_HOLDER_READ.ordinal()]
                  .getTotalTimeInNanos();
            }
            statsMap.put(QueryStatisticsVTI.SERNANOS, serNanos);
            QueryStatistics stmtStats = queryStats[StatType.RESULT_HOLDER_EXECUTE
                .ordinal()];
            long execNanos = stmtStats.getTotalTimeInNanos();
            if (execNanos <= 0) {
              stmtStats = queryStats[StatType.PREPSTATEMENT_QUERY_EXECUTION
                  .ordinal()];
              if ((execNanos = stmtStats.getTotalTimeInNanos()) <= 0) {
                stmtStats = queryStats[StatType.STATEMENT_QUERY_EXECUTION
                    .ordinal()];
                execNanos = stmtStats.getTotalTimeInNanos();
              }
            }
            statsMap.put(QueryStatisticsVTI.EXECNANOS, execNanos);
            QueryStatistics ormStats = queryStats[StatType.ORM_TIME.ordinal()];
            statsMap.put(QueryStatisticsVTI.ORMNANOS,
                ormStats.getTotalTimeInNanos());
            statsMapList.add(statsMap);
          }
          return statsMapList;
        }
        return null;
      }
    },
    GET_PREFERREDSERVER {

      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @SuppressWarnings("unchecked")
      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        try {
          final Collection<String> serverGroups;
          final Collection<String> intersectGroups;
          final String excludedServers;
          if (arg instanceof Object[]) {
            Object[] args = (Object[])arg;
            // new case of serverGroups+excludedServers
            serverGroups = (Collection<String>)args[0];
            intersectGroups = (Collection<String>)args[1];
            excludedServers = (String)args[2];
          }
          else {
            // old case of CSV excludedServers
            serverGroups = Collections.singletonList(ServerType.DRDA
                .getServerGroupName());
            intersectGroups = null;
            excludedServers = arg != null ? arg.toString() : null;
          }
          return GemFireXDUtils.getPreferredServer(serverGroups,
              intersectGroups, excludedServers, false);
        } catch (SQLException ex) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "failed in GET_PREFERREDSERVER", ex);
        }
      }
    },
    GET_PER_REGION_MEMORY_FOOTPRINT {

      private final static String logPrefix = "objectsizer: ";

      /*
      private Method sizerSizeMethod = null;

      private Method sizerSwitchTraceMethod = null;

      private Method sizerDoneMethod = null;
      */

      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {

        // [sb] now we have made necessary changes to route VTI on every node and 
        // locally returning results, so not controlling via this method.
        return null;
        //return GemFireXDUtils.getGfxdAdvisor().adviseDataStores(null);
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {

        final ObjectSizer sizer = ObjectSizer.getInstance(false);
        
        try {
          sizer.initialize(false, "!");
          sizer.setQueryHints(null);
          
          final LinkedHashMap<String, Object[]> result = new LinkedHashMap<>();

          ArrayList<GemFireContainer> targetContainers =
              new ArrayList<>();
          ObjectSizer.getTargetContainers(targetContainers);

          for (int idx = 0, size = targetContainers.size(); idx < size; idx++) {
            GemFireContainer c = targetContainers.get(idx);

            SanityManager.DEBUG_PRINT("TRACE", logPrefix
                + "Estimating for container " + c);

            final LinkedHashMap<String, Object[]> retVal = sizer.size(c, null);
            sizer.logSizes(retVal);

            /*
            // if not the last result, send it from here, else will get send via
            // lastResult.
            if (idx < size - 1) {
              msg.sendResult(result);
            }*/
            result.putAll(retVal);
            
            //invokeSizerSwitchTrace(false);
          }
          
          sizer.setQueryHints(null);

          return result;

        } catch (Exception e) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "Exception in GfxdCongiMessage#" + this.name(), e);
        } finally {
          //invokeSizerDone();
          sizer.done();
        }
      }

      /*
      private void getSizerMethods() throws SecurityException,
          NoSuchMethodException, ClassNotFoundException {
        Class<?> c = Class
            .forName("com.pivotal.gemfirexd.tools.sizer.ObjectSizer");

        sizerSizeMethod = c.getMethod("size", GemFireContainer.class);
        sizerSwitchTraceMethod = c.getMethod("switchStackTrace", boolean.class);
        sizerDoneMethod = c.getMethod("done");
      }

      private LinkedHashMap<?, ?> invokeSizerSize(GemFireContainer container) {

        try {

          if (sizerSizeMethod == null) {
            getSizerMethods();
          }

          Object ob = sizerSizeMethod.invoke(null, container);

          assert (ob instanceof LinkedHashMap<?, ?>);

          return (LinkedHashMap<?, ?>)ob;
        } catch (Throwable t) {
          SanityManager.DEBUG_PRINT("error:SIZER_SIZE",
              "Error while invoking SizerSize ", t);
          throw GemFireXDRuntimeException.newRuntimeException(
              "ObjectSizer.size(Region) invocation exception", t);
        } finally {
        }
      }

      private void invokeSizerSwitchTrace(boolean onOff) {
        try {
          sizerSwitchTraceMethod.invoke(null, onOff);
        } catch (Throwable t) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "ObjectSizer.switchTrace(onOff) invocation exception", t);
        }
      }

      private void invokeSizerDone() {
        try {

          if (sizerDoneMethod == null) {
            return;
          }

          sizerDoneMethod.invoke(null, (Object[])null);

        } catch (Throwable t) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "ObjectSizer.done() invocation exception", t);
        }
      }
      */
    },
    DUMP_BUCKETS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if (cache == null) {
          // do nothing if the cache has already closed
          return null;
        }
        final boolean dumpBackingMap;
        if (arg != null && arg instanceof Boolean) {
          dumpBackingMap = (Boolean)arg;
        }
        else {
          dumpBackingMap = false;
        }
        final String dumpBackingMapFlag = "DumpBackingMap";
        final String dumpFlag = "DumpBuckets";
        final String lineSep = SanityManager.lineSeparator;
        final String tabMarker = lineSep
            + "*******************************************";
        final Set<PartitionedRegion> prset = cache.getPartitionedRegions();
        for (PartitionedRegion preg : prset) {
          final GemFireContainer gfc;
          if (preg == null || preg.isDestroyed()
              || (gfc = (GemFireContainer)preg.getUserAttribute()) == null) {
            SanityManager.DEBUG_PRINT("warning:" + dumpFlag,
                "GemFire Region/Container is null or destroyed for "
                    + preg.getFullPath());
            continue;
          }
          // dumping global index separately below (dumpAllIndexes).
          if (gfc.isGlobalIndex()) {
            continue;
          }
          SanityManager.DEBUG_PRINT(dumpFlag, new StringBuilder(tabMarker)
              .append(lineSep).append("Dumping bucket info for table: ")
              .append(gfc.getTableName()).append(" region=").append(preg)
              .append(tabMarker).toString());
          try {
            PartitionedRegionDataStore store = preg.getDataStore();
            final RowFormatter rf = gfc.getCurrentRowFormatter();
            String[] columnNames = null;
            if (rf == null) {
              SanityManager.DEBUG_PRINT(dumpFlag, gfc.getTableName()
                  + " RowFormatter is found NULL for table "
                  + gfc.getTableName() + " here " + cache.getMyId());
            }
            else {
              columnNames = new String[rf.getNumColumns()];
              for (int index = 0; index < rf.getNumColumns(); ++index) {
                columnNames[index] = rf.getColumnDescriptor(index)
                    .getColumnName();
              }
            }
            if (store != null) {
              for (BucketRegion breg : store.getAllLocalBucketRegions()) {
                if (breg.isDestroyed()) {
                  SanityManager.DEBUG_PRINT(dumpFlag, gfc.getTableName()
                      + " bucket " + preg.bucketStringForLogs(breg.getId())
                      + " is destroyed. skipping here " + cache.getMyId());
                  continue;
                }
                final StringBuilder sb = new StringBuilder(lineSep);
                boolean isPrimary = breg.getBucketAdvisor().isPrimary();
                sb.append("======== Bucket ID=")
                    .append(preg.bucketStringForLogs(breg.getId()))
                    .append(" region=").append(breg.getName())
                    .append(" primary=").append(isPrimary)
                    .append(" contents: ========");
                final Iterator<?> iter = breg.getBestLocalIterator(true);
                while (iter.hasNext()) {
                  RegionEntry entry = (RegionEntry) iter.next();
                  final ExecRow row = RegionEntryUtils.getRowWithoutFaultIn(
                      gfc, breg, entry,
                      (ExtraTableInfo) entry.getContainerInfo());
                  try {
                    sb.append(lineSep).append('\t');
                    if (row != null) {
                      final DataValueDescriptor[] dvds = row.getRowArray();
                      for (int index = 0; index < dvds.length; ++index) {
                        if (index > 0) {
                          sb.append(',');
                        }
                        sb.append(
                            (columnNames != null ? columnNames[index] : "col"
                                + index)).append(':').append(dvds[index]);
                      }
                    } else {
                      sb.append("NULL").append(" for RE ").append(entry);
                    }
                  } finally {
                    //GemFireXDUtils.releaseByteSourceFromExecRow(row);
                    if(row != null) {
                      row.releaseByteSource();
                    }
                  }
                  if (sb.length() > (4 * 1024 * 1024)) {
                    SanityManager.DEBUG_PRINT(dumpFlag, sb.toString());
                    sb.setLength(0);
                  }
                }
                SanityManager.DEBUG_PRINT(dumpFlag, sb.toString());

                if (dumpBackingMap) {
                  SanityManager.DEBUG_PRINT(dumpBackingMapFlag, "Dumping backing map of " + breg);
                  breg.dumpBackingMap();
                }
              }
            }
            else {
              SanityManager.DEBUG_PRINT(dumpFlag,
                  gfc.getTableName() + " prId=" + preg.getPRId()
                      + " table is not a DataStore here " + cache.getMyId());
            }
            final GfxdIndexManager idxman = (GfxdIndexManager)preg
                .getIndexUpdater();
            if (idxman != null) {
              idxman.dumpAllIndexes();
            }
          } catch (Exception ex) {
            SanityManager.DEBUG_PRINT(dumpFlag,
                "exception while dumping contents of region: " + preg, ex);
          }
        }
        return null;
      }
    },
    SET_CLIENT_OR_SERVER {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        final Boolean isServer = (Boolean)arg;
        ServerGroupUtils.setIsServer(isServer.booleanValue());
        return null;
      }
    },
    SET_SERVERGROUPS {
      @Override
      public Set<DistributedMember> getTargetMembers(
          Set<DistributedMember> members) {
        return null;
      }

      @Override
      public Object process(Object arg, Set<DistributedMember> members,
          GfxdConfigMessage<?> msg) {
        final SortedSet<String> newServerGroups = SharedUtils.toSortedSet(
            (String)arg, false);
        ServerGroupUtils.setServerGroups(newServerGroups);
        return null;
      }
    };

    static Operation[] values = values();

    abstract Set<DistributedMember> getTargetMembers(
        Set<DistributedMember> members);

    abstract Object process(Object arg, Set<DistributedMember> members,
        GfxdConfigMessage<?> msg);

    private static void addSYSProps(Map<Object, Object> props) {
      props.putAll(System.getProperties());
    }

    private static void addGFEProps(Map<Object, Object> props) {
      final InternalDistributedSystem sys = Misc.getDistributedSystem();
      for (Map.Entry<Object, Object> entry : sys.getProperties().entrySet()) {
        final String key = entry.getKey().toString();
        // filter out the security properties, if any, since those may
        // be sensitive in nature (i.e. security module etc should also not
        // be published even though this already won't contain password etc)
        if (!key.startsWith(DistributionConfig.GEMFIRE_PREFIX
            + DistributionConfig.SECURITY_PREFIX_NAME)
            && !key.startsWith(DistributionConfigImpl.SECURITY_SYSTEM_PREFIX
                + DistributionConfig.SECURITY_PREFIX_NAME)) {
          props.put(key, entry.getValue());
        }
      }
    }

    private static void addGFXDProps(Map<Object, Object> props) {
      final GemFireStore memStore = GemFireStore.getBootedInstance();
      if (memStore == null) {
        return;
      }
      Map<Object, Object> bootProps = memStore.getBootProperties();
      if (bootProps.size() > 0) {
        props.putAll(bootProps);
      }
    }
  }

  /** Default constructor for deserialization. Not to be invoked directly. */
  public GfxdConfigMessage() {
    super(true);
    this.useAllMembers = AllMembers.GIVEN_MEMBERS;
    this.haEnabled = false;
  }

  /**
   * Send the configuration message to either the given members or to all
   * members when the members parameter is null. The former will fail with
   * exception when a member from among the set happens to go down while the
   * latter will do retries on all the available nodes of the DS.
   */
  public GfxdConfigMessage(final ResultCollector<Object, T> collector,
      final Set<DistributedMember> members, final Operation op,
      final Object arg, boolean allMembersIncludingAdmin)
      throws StandardException {
    super(collector, null /* no TX for config messages */,
        DistributionStats.enableClockStats, true);
    if (members != null) {
      this.members = new HashSet<>();
      final Set<DistributedMember> allMembers = getAllDSMembers();
      for (DistributedMember member : members) {
        if (allMembers.contains(member)) {
          this.members.add(member);
        }
        else {
          throw StandardException.newException(SQLState.MEMBER_NOT_FOUND,
              member);
        }
      }
      this.useAllMembers = AllMembers.GIVEN_MEMBERS;
      this.haEnabled = false;
    }
    else {
      this.members = null;
      this.haEnabled = true;
      this.useAllMembers = allMembersIncludingAdmin
          ? AllMembers.ALL_MEMBERS_INCLUDING_ADMIN
          : AllMembers.ALL_MEMBERS;
    }
    this.configOp = op;
    this.opArg = arg;
  }

  /** copy constructor */
  private GfxdConfigMessage(final GfxdConfigMessage<T> other)
      throws StandardException {
    super(other);
    if (other.members != null) {
      this.members = new HashSet<>();
      final Set<DistributedMember> allMembers = getAllDSMembers();
      for (DistributedMember member : other.members) {
        if (allMembers.contains(member)) {
          this.members.add(member);
        }
        else {
          throw StandardException.newException(SQLState.MEMBER_NOT_FOUND,
              member);
        }
      }
      this.useAllMembers = AllMembers.GIVEN_MEMBERS;
      this.haEnabled = false;
    }
    else {
      this.members = null;
      this.haEnabled = true;
      this.useAllMembers = other.useAllMembers;
    }
    this.configOp = other.configOp;
    this.opArg = other.opArg;
  }

  @Override
  protected void execute() {
    final Object result = this.configOp.process(this.opArg, this.members,
        this);
    if (GemFireXDUtils.TraceQuery | GemFireXDUtils.TraceNCJ) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_QUERYDISTRIB,
          "GfxdConfigMessage#execute: done processing for operation="
              + this.configOp + " args=" + this.opArg + " with result="
              + result);
    }
    lastResult(result);
  }

  @Override
  public Set<DistributedMember> getMembers() {
    switch (this.useAllMembers) {
      case ALL_MEMBERS:
        // refresh the list of all GFXD members so that it gives the updated
        // value when a node happens to go down
        this.members = getAllGfxdServers();
        break;
      case ALL_MEMBERS_INCLUDING_ADMIN:
        // refresh the list of all members so that it gives the updated
        // value when a node happens to go down
        this.members = getAllDSMembers();
        break;
      default:
        break;
    }
    Set<DistributedMember> sendMembers = this.configOp
        .getTargetMembers(this.members);
    // null sendMembers indicates that Operation#process() does not make use
    // of the members argument so no need to serialize members; also indicates
    // that the set of target members where message has to be sent is same as
    // given set
    if (sendMembers != null) {
      this.serializeMembers = true;
    }
    else {
      this.serializeMembers = false;
      sendMembers = this.members;
    }
    return sendMembers;
  }

  @Override
  public boolean isHA() {
    return this.haEnabled;
  }

  public void setHA(boolean enable) {
    this.haEnabled = enable;
  }

  @Override
  public boolean optimizeForWrite() {
    return true;
  }

  @Override
  public void postExecutionCallback() {
  }

  @Override
  protected boolean allowExecutionOnAdminMembers() {
    return true;
  }

  @Override
  protected GfxdFunctionReplyMessageProcessor<T> createReplyProcessor(DM dm,
      Set<DistributedMember> members) {
    return new GfxdConfigReplyProcessor<>(dm, members, this);
  }

  @Override
  public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writePrimitiveByte((byte)this.configOp.ordinal(), out);
    DataSerializer.writeObject(this.opArg, out);
    if (this.serializeMembers) {
      InternalDataSerializer.writeArrayLength(this.members.size(), out);
      for (Object member : this.members) {
        ((InternalDistributedMember)member).toData(out);
      }
    }
    else {
      InternalDataSerializer.writeArrayLength(0, out);
    }
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    int opOrdinal = DataSerializer.readPrimitiveByte(in);
    this.configOp = Operation.values[opOrdinal];
    this.opArg = DataSerializer.readObject(in);
    int numMembers = InternalDataSerializer.readArrayLength(in);
    if (numMembers > 0) {
      this.members = new HashSet<>(numMembers);
      for (int count = 1; count <= numMembers; ++count) {
        final InternalDistributedMember member = new InternalDistributedMember();
        member.fromData(in);
        this.members.add(member);
      }
      this.serializeMembers = true;
    }
    else {
      this.members = null;
      this.serializeMembers = false;
    }
  }

  @Override
  public byte getGfxdID() {
    return GFXD_CONFIG_MSG;
  }

  @Override
  protected void appendFields(final StringBuilder sb) {
    super.appendFields(sb);
    sb.append(";op=").append(this.configOp).append(";arg=").append(this.opArg)
        .append(";useAllMembers=").append(this.useAllMembers)
        .append(";members=").append(this.members);
  }

  @Override
  protected GfxdConfigMessage<T> clone() {
    try {
      return new GfxdConfigMessage<>(this);
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(null, se);
    }
  }

  protected static final class GfxdConfigReplyProcessor<T> extends
      GfxdFunctionReplyMessageProcessor<T> {

    public GfxdConfigReplyProcessor(DM dm, Set<DistributedMember> members,
        GfxdFunctionMessage<T> msg) {
      super(dm, members, msg);
    }

    // ignore NotSerializable exceptions and just skip those members
    @Override
    protected final void handleReplyException(String exPrefix,
        final ReplyException ex, GfxdFunctionMessage<?> fnMsg)
        throws SQLException, StandardException {
      final Map<DistributedMember, ReplyException> exceptions =
          getReplyExceptions();
      // There may be more than one exception, so check all of them
      if (exceptions != null) {
        ReplyException replyEx;
        for (Map.Entry<DistributedMember, ReplyException> entry : exceptions
            .entrySet()) {
          replyEx = entry.getValue();
          if (replyEx.getCause() instanceof NotSerializableException) {
            addResult(entry.getKey(), null);
          }
          else {
            handleProcessorReplyException(exPrefix, replyEx.getCause());
          }
        }
      }
      // if somehow nothing was done for all exception try for the passed one
      handleProcessorReplyException(exPrefix, ex.getCause());
    }
  }
}
