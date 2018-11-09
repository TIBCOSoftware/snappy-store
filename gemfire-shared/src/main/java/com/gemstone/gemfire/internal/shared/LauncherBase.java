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
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.shared;

import java.io.Console;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.internal.cache.Status;

public abstract class LauncherBase {

  public static final String LOG_FILE = "log-file";
  public static final String HOST_DATA = "host-data";

  public static final String THRESHOLD_THICKNESS_PROP =
      "gemfire.thresholdThickness";
  public static final String THRESHOLD_THICKNESS_EVICT_PROP =
      "gemfire.eviction-thresholdThickness";
  public static final String EVICTION_BURST_PERCENT_PROP =
      "gemfire.HeapLRUCapacityController.evictionBurstPercentage";

  public static final String CRITICAL_HEAP_PERCENTAGE =
      "critical-heap-percentage";
  public static final String EVICTION_HEAP_PERCENTAGE =
      "eviction-heap-percentage";
  public static final String CRITICAL_OFF_HEAP_PERCENTAGE =
      "critical-off-heap-percentage";
  public static final String EVICTION_OFF_HEAP_PERCENTAGE =
      "eviction-off-heap-percentage";
  public static final String POLLER_INTERVAL_PROP =
      "gemfire.heapPollerInterval";
  public static final String EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_PROP =
      "gemfire.HeapLRUCapacityController.evictHighEntryCountBucketsFirst";
  public static final String EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR_PROP =
      "gemfire.HeapLRUCapacityController.evictHighEntryCountBucketsFirstForEvictor";

  // no internalization here (bundles not part of GemFire for a long time)
  public static final String LAUNCHER_SEE_LOG_FILE = "See log file for details.";
  public static final String LAUNCHER_NO_AVAILABLE_STATUS =
      "No available status. Either status file \"{0}\" is not readable or " +
          "reading the status file timed out.";
  public static final String LAUNCHER_STOPPED =
      "The {0} on {1} has stopped.";
  public static final String LAUNCHER_TIMEOUT_WAITING_FOR_SHUTDOWN =
      "Timeout waiting for {0} to shutdown on {1}, status is: {2}";
  public static final String LAUNCHER_NO_STATUS_FILE =
      "The specified working directory ({0}) on {1} contains no status file";
  public static final String LAUNCHER_UNREADABLE_STATUS_FILE =
      "The status file {0} cannot be read due to: {1}. Delete the file if " +
          "no node is running in the directory.";
  public static final String LAUNCHER_UNKNOWN_ARGUMENT = "Unknown argument: {0}";
  public static final String LAUNCHER_WORKING_DIRECTORY_DOES_NOT_EXIST =
      "The input working directory does not exist:  {0}";
  public static final String LAUNCHER_LOGS_GENERATED_IN =
      "Logs generated in {0}";
  public static final String LAUNCH_IN_PROGRESS =
      "The server is still starting. " +
          "{0} seconds have elapsed since the last log message: \n {1}";
  public static final String LAUNCHER_IS_ALREADY_RUNNING_IN_DIRECTORY =
      "WARN: A {0} is already running in directory \"{1}\"";
  private static final String LAUNCHER_EXPECTED_BOOLEAN =
      "Expected true or false for \"{0}=<value>\" but was \"{1}\"";

  // in-built property names which are treated in a special way by launcher
  protected static final String DIR = "dir";
  protected static final String CLASSPATH = "classpath";
  protected static final String HEAP_SIZE = "heap-size";
  protected static final String WAIT_FOR_SYNC = "sync";
  protected static final String VMARGS = "vmArgs";
  protected static final String ENVARGS = "envArgs";

  protected static final String ENV1 = "env_1";
  protected static final String ENV2 = "env_2";
  protected static final String ENV_MARKER = "PLAIN:";

  protected static final int FORCE_STATUS_FILE_READ_ITERATION_COUNT = 10;
  private static final long STATUS_WAIT_TIME = SystemProperties.getServerInstance()
      .getLong("launcher.STATUS_WAIT_TIME_MS", 15000L);
  private static final long SHUTDOWN_WAIT_TIME = SystemProperties.getServerInstance()
      .getLong("launcher.SHUTDOWN_WAIT_TIME_MS", 15000L);

  public static final long LARGE_RAM_LIMIT = 14L << 30L;
  public static final double oneGB = 1024.0 * 1024.0 * 1024.0;
  private static final double twoGB = 2.0 * oneGB;

  protected final String baseName;
  protected final String defaultLogFileName;
  protected final String startLogFileName;
  protected final String pidFileName;
  protected final String statusName;
  protected final String hostName;
  protected volatile Status status;

  /**
   * wait for startup to complete, or exit once region GII wait begins
   */
  protected boolean waitForData;

  protected final String jvmVendor;
  protected String maxHeapSize;
  protected String initialHeapSize;
  @SuppressWarnings("WeakerAccess")
  protected boolean useThriftServerDefault =
      ClientSharedUtils.isThriftDefault();

  protected LauncherBase(String displayName, String baseName) {
    if (baseName == null) {
      baseName = getBaseName(displayName);
    }
    this.baseName = displayName;
    this.defaultLogFileName = baseName + ".log";
    this.startLogFileName = "start_" + this.defaultLogFileName;
    this.pidFileName = baseName + ".pid";
    this.statusName = "." + baseName + ".stat";

    InetAddress host = null;
    try {
      host = InetAddress.getLocalHost();
    } catch (Exception ex) {
      try {
        host = ClientSharedUtils.getLocalHost();
      } catch (Exception ignored) {
      }
    }
    this.hostName = host != null ? host.getCanonicalHostName() : "localhost";

    // wait for data sync by default
    this.waitForData = true;
    this.jvmVendor = System.getProperty("java.vendor");
  }

  protected String getBaseName(final String name) {
    return name != null ? name.toLowerCase().replace(" ", "") : null;
  }

  /** determine the total physical RAM in bytes */
  public static long getPhysicalRAMSize() {
    long totalMemory = 0L;
    OperatingSystemMXBean bean = ManagementFactory
        .getOperatingSystemMXBean();
    Object memSize = null;
    try {
      Method m = bean.getClass().getMethod("getTotalPhysicalMemorySize");
      m.setAccessible(true);
      memSize = m.invoke(bean);
    } catch (Exception e) {
      // ignore exception and return zero
    }
    if (memSize instanceof Number) {
      totalMemory = ((Number)memSize).longValue();
    }
    return totalMemory;
  }

  protected long getDefaultHeapSizeMB(boolean hostData) {
    return getDefaultHeapSizeMB(getPhysicalRAMSize(), hostData);
  }

  public static long getDefaultHeapSizeMB(long ramSize, boolean hostData) {
    int numProcs = Runtime.getRuntime().availableProcessors();
    if (hostData) {
      // use 6GB or 8GB for hosts having large number of cores and sufficient RAM else 4GB
      if (numProcs > 12 && ramSize > LARGE_RAM_LIMIT + (2L << 30L)) return 8192L;
      else if (numProcs > 8 && ramSize > LARGE_RAM_LIMIT) return 6144L;
      return 4096L;
    } else {
      // use 8GB or 6GB or 4GB or 2GB as per available RAM and number of cores
      if (numProcs > 16 && ramSize > LARGE_RAM_LIMIT + (4L << 30L)) return 8192L;
      else if (numProcs > 12 && ramSize > LARGE_RAM_LIMIT + (2L << 30L)) return 6144L;
      else if (numProcs > 2) return 4096L;
      else return 2048L;
    }
  }

  protected long getDefaultSmallHeapSizeMB(boolean hostData) {
    return hostData ? 2048L : 1024L;
  }

  protected void processHeapSize(String value, List<String> vmArgs) {
    if (this.maxHeapSize == null) {
      vmArgs.add("-Xmx" + value);
      this.maxHeapSize = value;
    }
    if (this.initialHeapSize == null) {
      vmArgs.add("-Xms" + value);
      this.initialHeapSize = value;
    }
  }

  protected void processWaitForSync(String value) {
    boolean isTrue = "true".equalsIgnoreCase(value);
    if (!isTrue && !"false".equalsIgnoreCase(value)) {
      throw new IllegalArgumentException(MessageFormat.format(
          LAUNCHER_EXPECTED_BOOLEAN, WAIT_FOR_SYNC, value));
    }
    this.waitForData = isTrue;
  }

  protected void processVMArg(String vmArg, List<String> vmArgs) {
    String thriftArg;
    if (vmArg.startsWith("-Xmx")) {
      this.maxHeapSize = vmArg.substring(4);
    } else if (vmArg.startsWith("-Xms")) {
      this.initialHeapSize = vmArg.substring(4);
    } else if (vmArg.startsWith(thriftArg = ("-D"
        + SystemProperties.getServerInstance().getSystemPropertyNamePrefix()
        + ClientSharedUtils.USE_THRIFT_AS_DEFAULT_PROP))) {
      int len = thriftArg.length();
      if (vmArg.length() > (len + 1) && vmArg.charAt(len) == '=') {
        this.useThriftServerDefault = Boolean.parseBoolean(vmArg.substring(
            len + 1).trim());
      }
    }
    vmArgs.add(vmArg);
  }

  protected void setDefaultVMArgs(Map<String, Object> map, boolean hostData,
      List<String> vmArgs) {

    // determine the total physical RAM
    long totalMemory = getPhysicalRAMSize();
    float evictPercent = 0.0f;
    float criticalPercent = 0.0f;
    // If either the max heap or initial heap is null, set the one that is null
    // equal to the one that isn't.
    if (this.maxHeapSize == null) {
      if (this.initialHeapSize != null) {
        vmArgs.add("-Xmx" + this.initialHeapSize);
        this.maxHeapSize = this.initialHeapSize;
      } else {
        long defaultMemoryMB = getDefaultHeapSizeMB(hostData);
        if (defaultMemoryMB > 0 && totalMemory > 0L) {
          // Try some sane default for heapSize if none specified.
          // Set it only if total RAM is more than 1.5X of the default.
          long defaultMemory = defaultMemoryMB * 1024L * 1024L;
          if ((totalMemory * 2) < (defaultMemory * 3)) {
            defaultMemoryMB = getDefaultSmallHeapSizeMB(hostData);
            defaultMemory = defaultMemoryMB * 1024L * 1024L;
            if ((totalMemory * 2) < (defaultMemory * 3)) {
              defaultMemoryMB = 0;
            }
          }
          if (defaultMemoryMB > 0) {
            this.maxHeapSize = this.initialHeapSize =
                Long.toString(defaultMemoryMB) + 'm';
            vmArgs.add("-Xmx" + this.maxHeapSize);
            vmArgs.add("-Xms" + this.initialHeapSize);
          }
        }
      }
    } else if (this.initialHeapSize == null) {
      vmArgs.add("-Xms" + this.maxHeapSize);
      this.initialHeapSize = this.maxHeapSize;
    }
    final String maxHeapStr = this.maxHeapSize;
    if (maxHeapStr != null && maxHeapStr.equals(this.initialHeapSize)) {
      String criticalHeapStr = (String)map.get(CRITICAL_HEAP_PERCENTAGE);
      if (criticalHeapStr == null) {
        // for larger heaps, keep critical as 95% and 90% for smaller ones;
        // also limit memory remaining beyond critical to 4GB
        double heapSize = ClientSharedUtils.parseMemorySize(maxHeapStr, 0L, 0);
        if (heapSize > (40.0 * 1024.0 * 1024.0 * 1024.0)) {
          // calculate percent that will leave out at max 4GB
          criticalPercent = (float)(100.0 * (1.0 - twoGB / heapSize));
          // don't exceed 98%
          if (criticalPercent > 98.0f) criticalPercent = 98.0f;
        } else if (heapSize >= twoGB) {
          criticalPercent = 95.0f;
        } else {
          criticalPercent = 90.0f;
        }
        map.put(CRITICAL_HEAP_PERCENTAGE, "-" + CRITICAL_HEAP_PERCENTAGE +
            '=' + criticalPercent);
      } else {
        criticalPercent = Float.parseFloat(criticalHeapStr.substring(
            criticalHeapStr.indexOf('=') + 1).trim());
      }

      String evictHeapStr = (String)map.get(EVICTION_HEAP_PERCENTAGE);
      if (evictHeapStr == null) {
        // reduce the critical-heap-percentage by 10% to get
        // eviction-heap-percentage
        evictPercent = criticalPercent * 0.9f;
        map.put(EVICTION_HEAP_PERCENTAGE, "-" + EVICTION_HEAP_PERCENTAGE +
            '=' + evictPercent);
      } else {
        evictPercent = Float.parseFloat(evictHeapStr.substring(
            evictHeapStr.indexOf('=') + 1).trim());
      }
    }
    if (jvmVendor != null &&
        (jvmVendor.contains("Sun") || jvmVendor.contains("Oracle"))) {
      vmArgs.add("-XX:+UseParNewGC");
      vmArgs.add("-XX:+UseConcMarkSweepGC");
      vmArgs.add("-XX:CMSInitiatingOccupancyFraction=50");
      vmArgs.add("-XX:+CMSClassUnloadingEnabled");
      vmArgs.add("-XX:-DontCompileHugeMethods");
      // reduce the compile threshold for generated code of low latency jobs
      vmArgs.add("-XX:CompileThreshold=2000");
      vmArgs.add("-XX:+UnlockDiagnosticVMOptions");
      vmArgs.add("-XX:ParGCCardsPerStrideChunk=4k");
      // limit thread-local cached direct buffers to reduce overhead
      vmArgs.add("-Djdk.nio.maxCachedBufferSize=131072");
    }

    // If heap and off-heap sizes were both specified, then the critical and
    // eviction values for heap will be used.
    if (evictPercent != 0.0f) {
      // set the thickness to a more reasonable value than 2%
      float criticalThickness = criticalPercent * 0.05f;
      vmArgs.add("-D" + THRESHOLD_THICKNESS_PROP + '=' + criticalThickness);
      // set the eviction thickness to a more reasonable value than 2%
      float evictThickness = evictPercent * 0.1f;
      vmArgs.add("-D" + THRESHOLD_THICKNESS_EVICT_PROP + '=' + evictThickness);
      // set the eviction burst percentage to a more reasonable value than 0.4%
      float evictBurstPercent = evictPercent * 0.02f;
      vmArgs.add("-D" + EVICTION_BURST_PERCENT_PROP + '=' + evictBurstPercent);
      // reduce the heap poller interval to something more practical for high
      // concurrency putAlls
      vmArgs.add("-D" + POLLER_INTERVAL_PROP + "=200");
      // always force EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST to false and
      // EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR to true
      vmArgs.add("-D" + EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_PROP + "=false");
      vmArgs.add("-D" + EVICT_HIGH_ENTRY_COUNT_BUCKETS_FIRST_FOR_EVICTOR_PROP + "=true");
    }
    vmArgs.add("-Dorg.codehaus.janino.source_debugging.enable=true");
  }

  /**
   * Get the path to working directory (should be absolute)
   */
  protected abstract Path getWorkingDirPath();

  protected Path getStatusPath() {
    return getWorkingDirPath().resolve(this.statusName);
  }

  protected long getLastModifiedStatusNanos() throws IOException {
    return Files.getLastModifiedTime(getStatusPath()).to(TimeUnit.NANOSECONDS);
  }

  /**
   * Verify and clear the status. If a server is detected as already running
   * then returns an error string else null.
   */
  protected String verifyAndClearStatus() throws IOException {
    final Status status = getStatus();
    if (status != null && status.state != Status.SHUTDOWN) {
      return MessageFormat.format(LAUNCHER_IS_ALREADY_RUNNING_IN_DIRECTORY,
          this.baseName, getWorkingDirPath());
    }
    deleteStatus();
    return null;
  }

  protected final void setStatusField(Status s) {
    this.status = s;
  }

  protected final Status createStatus(final int state, final int pid) {
    return Status.create(this.baseName, state, pid, getStatusPath());
  }

  protected final Status createStatus(final int state,
      final int pid, final String msg, final Throwable t) {
    return Status.create(this.baseName, state, pid, msg, t, getStatusPath());
  }

  /**
   * Returns the <code>Status</code> of the cache server in the
   * <code>workingDir</code>.
   */
  protected Status getStatus() {
    Path statusPath = getStatusPath();
    Status status;
    if (Files.exists(statusPath)) {
      status = spinReadStatus(statusPath); // See bug 32456
    } else {
      // no pid since the cache server is not running
      status = createStatus(Status.SHUTDOWN, 0);
    }
    return status;
  }

  /**
   * Reads a node's status. If the status file cannot be read
   * because of I/O problems, it will try again.
   */
  protected Status spinReadStatus(Path statusPath) {
    return Status.spinRead(this.baseName, statusPath);
  }

  /**
   * Removes a cache server's status file
   */
  protected void deleteStatus() throws IOException {
    Status.delete(getStatusPath());
  }

  /**
   * Wait for node to go to RUNNING.
   * Returns the exit code (success is 0 else failure).
   */
  protected int waitForRunning(String logFilePath)
      throws IOException, InterruptedException {
    Path statusPath = getStatusPath();
    Status status = spinReadStatus(statusPath);
    String lastReadMessage = null;
    String lastReportedMessage = null;
    long lastReadTime = System.nanoTime();
    if (status == null) {
      throw new IOException(MessageFormat.format(LAUNCHER_NO_AVAILABLE_STATUS,
          this.statusName));
    } else {
      if (logFilePath != null) {
        System.out.println(MessageFormat.format(
            LAUNCHER_LOGS_GENERATED_IN, logFilePath));
      }

      if (checkStatusForWait(status)) {
        long lastModified, oldModified = getLastModifiedStatusNanos();
        int count = 0;
        // re-read status for a while...
        while (checkStatusForWait(status)) {
          Thread.sleep(100); // fix for bug 36998
          lastModified = getLastModifiedStatusNanos();
          if (lastModified != oldModified ||
              count++ == FORCE_STATUS_FILE_READ_ITERATION_COUNT) {
            count = 0;
            oldModified = lastModified;
            status = spinReadStatus(statusPath);
          }

          if (status == null) {
            throw new IOException(MessageFormat.format(
                LAUNCHER_NO_AVAILABLE_STATUS, this.statusName));
          }

          //check to see if the status message has changed
          if (status.dsMsg != null && !status.dsMsg.equals(lastReadMessage)) {
            lastReadMessage = status.dsMsg;
            lastReadTime = System.nanoTime();
          }

          //if the status message has not changed for 15 seconds, print
          //out the message.
          long elapsed = System.nanoTime() - lastReadTime;
          if (TimeUnit.NANOSECONDS.toMillis(elapsed) > STATUS_WAIT_TIME
              && lastReadMessage != null &&
              !lastReadMessage.equals(lastReportedMessage)) {
            long elapsedSec = TimeUnit.NANOSECONDS.toSeconds(elapsed);
            System.out.println(MessageFormat.format(
                LAUNCH_IN_PROGRESS, elapsedSec, status.dsMsg));
            lastReportedMessage = lastReadMessage;
          }
        }
        if (status.state == Status.SHUTDOWN) {
          System.out.println(status);
          return 1;
        }
      }
      writePidToFile(status);
      System.out.println(status);
      return 0;
    }
  }

  /**
   * Creates a new pid file and writes this process's pid into it.
   */
  private void writePidToFile(Status status) throws IOException {
    final Path pidFile = getWorkingDirPath().resolve(this.pidFileName);
    try (OutputStreamWriter writer = new OutputStreamWriter(
        Files.newOutputStream(pidFile))) {
      writer.write(String.valueOf(status.pid));
      writer.flush();
    }
  }

  protected void pollCacheServerForShutdown(Path statusFile)
      throws IOException {
    long clock = System.currentTimeMillis();
    // wait for a default total of 15s
    final long end = clock + SHUTDOWN_WAIT_TIME;
    while (clock < end) {
      try {
        this.status = spinReadStatus(statusFile);
        if (this.status.state == Status.SHUTDOWN) {
          break;
        }
        Thread.sleep(200);
        clock = System.currentTimeMillis();
      } catch (InterruptedException ie) {
        break;
      }
    }
  }

  protected boolean checkStatusForWait(Status status) {
    // start node even in WAITING state if the "-sync" option is false
    return (status.state == Status.STARTING ||
        (this.waitForData && status.state == Status.WAITING));
  }

  public static String readPassword(String prompt) {
    final Console cons = System.console();
    if (cons == null) {
      throw new IllegalStateException(
          "No console found for reading the password.");
    }
    final char[] pwd = cons.readPassword(prompt);
    return pwd != null ? new String(pwd) : null;
  }
}
