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
package com.pivotal.gemfirexd.internal.engine.stats;

import com.gemstone.gemfire.StatisticDescriptor;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsFactory;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.StatisticsTypeFactory;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.GemFireStatSampler;
import com.gemstone.gemfire.internal.StatisticsTypeFactoryImpl;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

/**
 * peer Connection statistics tracking number or connections and connection
 * time.
 * 
 * @author rdubey
 * @author soubhikc, kneeraj, swale
 */
public final class ConnectionStats {

  private static boolean enableClockStats = false;
  static {
    // Enable time stats.
    setClockStats(false, true);
  }

  /** The Statistics object that we delegate most behavior to */
  private final Statistics stats;

  /** True if statistics sampling is enabled. */
  private static boolean samplingEnabled;

  /** Statistics type */
  private static final StatisticsType type;

  /** Index for connection open attempted */
  private static final int peerConnectionsAttemptedId;

  /** Index for connection currently open */
  private static final int peerConnectionsOpenId;

  /** Index for connection attempt failed due to any kind of exception */
  private static final int peerConnectionsFailedId;

  /** Index for connections opened */
  private static final int peerConnectionsOpenedId;

  /** Index for time taken to create a connections */
  private static final int peerConnectionsOpenTimeId;

  /** Index for connections closed */
  public static final int peerConnectionsClosedId;

  /** Index for time taken for each connection. */
  private static final int peerConnectionsLifeTimeId;

  /** Index for nested connections currently open */
  private static final int nestedConnectionsOpenId;

  /** Index for nested connections opened */
  private static final int nestedConnectionsOpenedId;

  /** Index for nested connections closed */
  public static final int nestedConnectionsClosedId;

  /** Index for internal connections currently open */
  private static final int internalConnectionsOpenId;

  /** Index for internal connections opened */
  private static final int internalConnectionsOpenedId;

  /** Index for internal connections closed */
  public static final int internalConnectionsClosedId;

  private static final int netServerThreadsId;
  
  private static final int netServerWaitingThreadsId;

  private static final int clientConnectionsTotalBytesReadId;

  private static final int clientConnectionsTotalBytesWrittenId;

  /** connections waiting due to network server thread pool limits.*/
  private static final int clientConnectionsQueuedId;

  /** Index for connection open attempted */
  private static final int clientConnectionAttemptedId;

  /** Index for connection attempt failed due to any kind of exception */
  private static final int clientConnectionsFailedId;

  /** Connections that are idle*/
  private static final int clientConnectionsIdleId;

  /** Number of open connections */
  private static final int clientConnectionsOpenId;
  
  /** Total number of connections opened until now*/
  private static final int clientConnectionsOpenedId;

  /** Index for time taken to create a connection */
  private static final int clientConnectionsOpenTimeId;

  /** Index for connections closed */
  private static final int clientConnectionsClosedId;

  /** Index for time taken for each connection. */
  private static final int clientConnectionsLifeTimeId;

  private static final int netServerThreadLongWaitsId;

  private static final int netServerThreadIdleTimeId;

  private static final int clientCommandsProcessedId;

  private static final int clientCommandsProcessTimeId;

  /** Statistics name */
  public static String name = "ConnectionStats";

  static {
    StatisticsTypeFactory f = StatisticsTypeFactoryImpl.singleton();

    type = f
        .createType(
            name,
            "Statistics about embedded and client connections",
            new StatisticDescriptor[] {
                f.createLongCounter("peerConnectionsAttempted",
                    "Number of peer connections attempted.",
                    "operations"),
                f.createLongGauge("peerConnectionsOpen",
                    "Number of peer connections open at this moment.",
                    "operations"),
                f.createLongCounter(
                    "peerConnectionsFailed",
                    "Number of peer connections creations failed due to exceptions.",
                    "operations"),
                f.createLongCounter("peerConnectionsOpened",
                    "Number of peer connections opened.", "operations"),
                f.createLongCounter("peerConnectionsOpenTime",
                    "Time taken to open peer connections.", "nanoseconds"),
                f.createLongCounter("peerConnectionsClosed",
                    "Number of peer connections closed.", "operations"),
                f.createLongCounter(
                    "peerConnectionsLifeTime",
                    "Time for which peer connections were alive, i.e. time taken between open and close of connections."
                        + "This divided by peerConnectionsOpened can yield average time a connection is retained on this server.",
                    "nanoseconds"),

                f.createLongGauge("nestedConnectionsOpen",
                    "Number of nested connections open at this moment.",
                    "operations"),
                f.createLongCounter("nestedConnectionsOpened",
                    "Number of nested connections opened.", "operations"),
                f.createLongCounter("nestedConnectionsClosed",
                    "Number of nested connections closed.", "operations"),

                f.createLongGauge("internalConnectionsOpen",
                    "Number of internal connections open at this moment.",
                    "operations"),
                f.createLongCounter("internalConnectionsOpened",
                    "Number of internal connections opened.", "operations"),
                f.createLongCounter("internalConnectionsClosed",
                    "Number of internal connections closed.", "operations"),

                f.createLongCounter("clientConnectionsAttempted",
                    "Number of client connections attempted", "operations"),
                f.createLongGauge(
                    "netServerThreads",
                    "Number of network server threads created for servicing client connections.",
                    "operations"),
                f.createLongGauge(
                    "netServerWaitingThreads",
                    "Number of network server threads that waited > 1 millisecond for client requests.",
                    "operations"),

                f.createLongGauge(
                    "clientConnectionsQueued",
                    "Number of client connections active but in the wait queue and yet to be serviced by network server threads.",
                    "operations"),
                f.createLongCounter(
                    "clientConnectionsTotalBytesRead",
                    "Total bytes read across all client connections to receive commands from client.",
                    "bytes"),
                f.createLongCounter(
                    "clientConnectionsTotalBytesWritten",
                    "Total bytes written as a response to client requests across all connections.",
                    "bytes"),
                f.createLongCounter(
                    "clientConnectionsFailed",
                    "Number of client connections creation failed due to exceptions.",
                    "operations"),
                f.createLongGauge("clientConnectionsIdle",
                    "Number of client connections idle at this moment.",
                    "operations"),
                f.createLongGauge("clientConnectionsOpen",
                    "Number of client connections open at this moment.", "operations"),
                f.createLongCounter("clientConnectionsOpened",
                    "Total number of client connections opened until now", "operations"),
                f.createLongCounter("clientConnectionsOpenTime",
                    "Time taken to open client connections.", "nanoseconds"),
                f.createLongCounter("clientConnectionsClosed",
                    "Number of client connections closed", "operations"),
                f.createLongCounter(
                    "clientConnectionsLifeTime",
                    "Time for which client connections were held open by the client, i.e. time between open and close of client connections."
                        + "This divided by clientConnectionsOpened will yield average time a connection is retained by the clients on this server.",
                    "nanoseconds"),

                f.createLongCounter(
                    "netServerThreadLongWaits",
                    "Number of times server network server threads waited > 5 millisecond for client requests.",
                    "operations"),
                f.createLongCounter(
                    "netServerThreadIdleTime",
                    "Time for which server network server threads were waiting for clients to submit requests. "
                        + "This divided by netServerThreadLongWaits gives average idle time across all receiving threads that waited beyond 5 millisecond. "
                        + "This divided by clientConnectionsIdle gives average per thread idle time.",
                    "milliseconds"),
                f.createLongCounter("clientCommandsProcessed",
                    "Number of commands processed by the network server threads.",
                    "operations"),
                f.createLongCounter(
                    "clientCommandsProcessTime",
                    " Total time taken by network server threads to process commands submitted.",
                    "nanoseconds"), });

    peerConnectionsAttemptedId = type
        .nameToId("peerConnectionsAttempted");
    peerConnectionsOpenId = type.nameToId("peerConnectionsOpen");
    peerConnectionsFailedId = type.nameToId("peerConnectionsFailed");
    peerConnectionsOpenedId = type.nameToId("peerConnectionsOpened");
    peerConnectionsOpenTimeId = type
        .nameToId("peerConnectionsOpenTime");
    peerConnectionsClosedId = type.nameToId("peerConnectionsClosed");
    peerConnectionsLifeTimeId = type.nameToId("peerConnectionsLifeTime");

    nestedConnectionsOpenId = type.nameToId("nestedConnectionsOpen");
    nestedConnectionsOpenedId = type.nameToId("nestedConnectionsOpened");
    nestedConnectionsClosedId = type.nameToId("nestedConnectionsClosed");

    internalConnectionsOpenId = type.nameToId("internalConnectionsOpen");
    internalConnectionsOpenedId = type.nameToId("internalConnectionsOpened");
    internalConnectionsClosedId = type.nameToId("internalConnectionsClosed");

    clientConnectionAttemptedId = type.nameToId("clientConnectionsAttempted");
    netServerThreadsId = type.nameToId("netServerThreads");
    netServerWaitingThreadsId = type.nameToId("netServerWaitingThreads");
    clientConnectionsQueuedId = type.nameToId("clientConnectionsQueued");
    clientConnectionsTotalBytesReadId = type
        .nameToId("clientConnectionsTotalBytesRead");
    clientConnectionsTotalBytesWrittenId = type
        .nameToId("clientConnectionsTotalBytesWritten");
    clientConnectionsFailedId = type.nameToId("clientConnectionsFailed");
    clientConnectionsIdleId = type.nameToId("clientConnectionsIdle");
    clientConnectionsOpenId   = type.nameToId("clientConnectionsOpen");
    clientConnectionsOpenedId = type.nameToId("clientConnectionsOpened");
    clientConnectionsOpenTimeId = type.nameToId("clientConnectionsOpenTime");
    clientConnectionsClosedId = type.nameToId("clientConnectionsClosed");
    clientConnectionsLifeTimeId = type.nameToId("clientConnectionsLifeTime");

    netServerThreadLongWaitsId = type
        .nameToId("netServerThreadLongWaits");
    netServerThreadIdleTimeId = type
        .nameToId("netServerThreadIdleTime");
    clientCommandsProcessedId = type.nameToId("clientCommandsProcessed");
    clientCommandsProcessTimeId = type
        .nameToId("clientCommandsProcessTime");
  }

  public ConnectionStats(StatisticsFactory factory, String name) {
    stats = factory.createAtomicStatistics(type, name);
    InternalDistributedSystem sys = (InternalDistributedSystem)factory;
    GemFireStatSampler sampler = sys.getStatSampler();
    samplingEnabled = sampler != null && sampler.isSamplingEnabled();
    setClockStats(sys.getConfig().getEnableTimeStatistics(), false);
  }

  public static void setClockStats(boolean force, boolean booting) {
    if (force) {
      enableClockStats = true;
    }
    else {
      enableClockStats = Boolean.getBoolean("gemfire.enable-time-statistics")
          || Boolean.getBoolean(GfxdConstants.GFXD_ENABLE_TIMESTATS);
      if (!enableClockStats && !booting && Monitor.getMonitor() != null) {
        String enableTimeStats = PropertyUtil.findAndGetProperty(null,
            Attribute.ENABLE_TIMESTATS, GfxdConstants.GFXD_ENABLE_TIMESTATS);
        if (enableTimeStats != null) {
          enableClockStats = Boolean.parseBoolean(enableTimeStats);
        }
      }
    }
  }

  public static boolean clockStatsEnabled() {
    return enableClockStats;
  }

  public static boolean isSamplingEnabled() {
    return samplingEnabled;
  }

  /**
   * Increment number of connections in progress.
   * 
   */
  public void incPeerConnectionsAttempt() {
    this.stats.incLong(peerConnectionsAttemptedId, 1);
  }

  public void incPeerConnectionsOpen() {
    this.stats.incLong(peerConnectionsOpenId, 1);
  }

  public void decPeerConnectionsOpen() {
    this.stats.incLong(peerConnectionsOpenId, -1);
  }

  public void incpeerConnectionsOpened() {
    this.stats.incLong(peerConnectionsOpenedId, 1);
  }

  public void incPeerConnectionsFailed() {
    this.stats.incLong(peerConnectionsFailedId, 1);
  }

  public void incPeerConnectionsOpenTime(long begin) {
    if (enableClockStats) {
      final long nanos = getStatTime() - begin;
      if (nanos > 0) {
        this.stats.incLong(peerConnectionsOpenTimeId, nanos);
      }
    }
  }

  public void incPeerConnectionsClosed() {
    this.stats.incLong(peerConnectionsClosedId, 1);
  }

  public void incPeerConnectionLifeTime(long begin) {
    if (enableClockStats) {
      long nanos = getStatTime() - begin;
      if (nanos > 0) {
        this.stats.incLong(peerConnectionsLifeTimeId, nanos);
      }
    }
  }

  // nested connections

  public void incNestedConnectionsOpen() {
    this.stats.incLong(nestedConnectionsOpenId, 1);
  }

  public void decNestedConnectionsOpen() {
    this.stats.incLong(nestedConnectionsOpenId, -1);
  }

  public void incNestedConnectionsOpened() {
    this.stats.incLong(nestedConnectionsOpenedId, 1);
  }

  public void incNestedConnectionsClosed() {
    this.stats.incLong(nestedConnectionsClosedId, 1);
  }

  // internal connections

  public void incInternalConnectionsOpen() {
    this.stats.incLong(internalConnectionsOpenId, 1);
  }

  public void decInternalConnectionsOpen() {
    this.stats.incLong(internalConnectionsOpenId, -1);
  }

  public void incInternalConnectionsOpened() {
    this.stats.incLong(internalConnectionsOpenedId, 1);
  }

  public void incInternalConnectionsClosed() {
    this.stats.incLong(internalConnectionsClosedId, 1);
  }

  /**
   * Increment number of connections in progress.
   * 
   */
  public void incClientConnectionsAttempt() {
    this.stats.incLong(clientConnectionAttemptedId, 1);
  }

  public void setNetServerThreads(long newValue) {
    this.stats.setLong(netServerThreadsId, newValue);
  }

  public void setNetServerWaitingThreads(long newValue) {
    this.stats.setLong(netServerWaitingThreadsId, newValue);
  }

  public void setClientConnectionsQueued(long newValue) {
    this.stats.setLong(clientConnectionsQueuedId, newValue);
  }

  public void incTotalBytesRead(long inc) {
    this.stats.incLong(clientConnectionsTotalBytesReadId, inc);
  }

  public void incTotalBytesWritten(long inc) {
    this.stats.incLong(clientConnectionsTotalBytesWrittenId, inc);
  }

  public void incClientConnectionsFailed() {
    this.stats.incLong(clientConnectionsFailedId, 1);
  }

  public void setClientConnectionsIdle(long newValue) {
    this.stats.setLong(clientConnectionsIdleId, newValue);
  }
  
  public void setClientConnectionsOpen(long newValue) {
    this.stats.setLong(clientConnectionsOpenId, newValue);
  }

  public void incClientConnectionsOpened() {
    this.stats.incLong(clientConnectionsOpenedId, 1);
  }

  public void incClientConnectionsOpenTime(long begin) {
    if (enableClockStats) {
      long nanos = getStatTime() - begin;
      if (nanos > 0) {
        this.stats.incLong(clientConnectionsOpenTimeId, nanos);
      }
    }
  }

  public void incClientConnectionsClosed() {
    this.stats.incLong(clientConnectionsClosedId, 1);
  }

  public void incClientConnectionsLifeTime(long begin) {
    if (enableClockStats) {
      long nanos = getStatTime() - begin;
      if (nanos > 0) {
        this.stats.incLong(clientConnectionsLifeTimeId, nanos);
      }
    }
  }

  public void incNetServerThreadLongWaits(long inc) {
    this.stats.incLong(netServerThreadLongWaitsId, inc);
  }

  public void incNetServerThreadIdleTime(long inc) {
    this.stats.incLong(netServerThreadIdleTimeId, inc);
  }

  public void incCommandsProcessed(long inc) {
    this.stats.incLong(clientCommandsProcessedId, inc);
  }

  public void incCommandsProcessTime(long inc) {
    this.stats.incLong(clientCommandsProcessTimeId, inc);
  }

  /**
   * Returns the current NanoTime or, if clock stats are disabled, zero.
   * 
   * @return long 0 if time stats disabled or the current time in nano secounds.
   */
  public static long getStatTime() {
    return enableClockStats ? XPLAINUtil.nanoTime() : 0;
  }

}
