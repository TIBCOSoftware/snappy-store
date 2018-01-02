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
package com.gemstone.gemfire.internal;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.GemFireStatSampler.LocalStatListenerImpl;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.statistics.SampleCollector;
import com.gemstone.gemfire.internal.statistics.StatArchiveHandler;
import com.gemstone.gemfire.internal.statistics.StatArchiveHandlerConfig;
import com.gemstone.gemfire.internal.statistics.VMStats;

import io.snappydata.test.dunit.DistributedTestBase;
import io.snappydata.test.dunit.DistributedTestBase.WaitCriterion;

/**
 * Integration tests for GemFireStatSampler.
 *
 * @author Kirk Lund
 * @since 7.0
 */
public class GemFireStatSamplerJUnitTest extends StatSamplerTestCase {

  private static final int STAT_SAMPLE_RATE = 1000;

  private DistributedSystem system;

  public GemFireStatSamplerJUnitTest(String name) {
    super(name);
  }

  public void setUp() throws Exception {
    super.setUp();
    new File("GemFireStatSamplerJUnitTest").mkdir();
  }

  /**
   * Removes the loner DistributedSystem at the end of each test.
   */
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty(GemFireStatSampler.TEST_FILE_SIZE_LIMIT_IN_KB_PROPERTY);
    disconnect();
  }

  /**
   * Tests the majority of getters and the basic functionality of the sampler.
   */
  public void testBasics() throws Exception {
    connect(createGemFireProperties());

    GemFireStatSampler statSampler = getGemFireStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));

    assertEquals(0, statSampler.getArchiveFileSizeLimit());
    assertEquals(0, statSampler.getArchiveDiskSpaceLimit());
    assertEquals(STAT_SAMPLE_RATE, statSampler.getSampleRate());
    assertEquals(true, statSampler.isSamplingEnabled());

    int statsCount = statSampler.getStatisticsManager().getStatisticsCount();

    assertEquals(statsCount, statSampler.getStatisticsModCount());
    assertEquals(statsCount, statSampler.getStatisticsManager().getStatisticsCount());
    assertEquals(statsCount, statSampler.getStatistics().length);

    assertEquals(getStatisticsManager().getId(), statSampler.getSystemId());
    assertTrue(statSampler.getSystemStartTime() < System.currentTimeMillis());
    assertEquals(SocketCreator.getHostName(SocketCreator.getLocalHost()),
                 statSampler.getSystemDirectoryPath());

    AllStatistics allStats = new AllStatistics(statSampler);

    VMStats vmStats = statSampler.getVMStats();
    assertNotNull(vmStats);
    /* NOTE: VMStats is not an instance of Statistics but instead its
     * instance contains 3 instances of Statistics:
     * 1) vmStats
     * 2) heapMemStats
     * 3) nonHeapMemStats
     */

    Method getProcessStats = getGemFireStatSampler().getClass().getMethod("getProcessStats");
    assertNotNull(getProcessStats);

    ProcessStats processStats = statSampler.getProcessStats();
    String osName = System.getProperty("os.name", "unknown");
    if (osName.equals("SunOS")) {
      assertNotNull(processStats);
      assertTrue(PureJavaMode.osStatsAreAvailable());
      assertTrue(allStats.containsStatisticsType("SolarisProcessStats"));
      assertTrue(allStats.containsStatisticsType("SolarisSystemStats"));
    } else if (osName.startsWith("Windows")) {
      // fails on Windows 7: 45395 "ProcessStats are not created on Windows 7"
      assertNotNull(processStats);
      assertTrue(PureJavaMode.osStatsAreAvailable());
      assertTrue(allStats.containsStatisticsType("WindowsProcessStats"));
      assertTrue(allStats.containsStatisticsType("WindowsSystemStats"));
    } else if (osName.startsWith("Linux")) {
      assertNotNull(processStats);
      assertTrue(PureJavaMode.osStatsAreAvailable());
      assertTrue(allStats.containsStatisticsType("LinuxProcessStats"));
      assertTrue(allStats.containsStatisticsType("LinuxSystemStats"));
    } else if (osName.equals("Mac OS X")) {
      assertNull(processStats);
      assertFalse(PureJavaMode.osStatsAreAvailable());
      assertFalse(allStats.containsStatisticsType("OSXProcessStats"));
      assertFalse(allStats.containsStatisticsType("OSXSystemStats"));
    } else {
      assertNull(processStats);
    }

    String productDesc = statSampler.getProductDescription();
    assertTrue(productDesc.contains(GemFireVersion.getGemFireVersion()));
    assertTrue(productDesc.contains(GemFireVersion.getBuildId()));
    assertTrue(productDesc.contains(GemFireVersion.getSourceDate()));
  }

  /**
   * Tests that the configured archive file is created and exists.
   */
  public void testArchiveFileExists() throws Exception {
    final String dir = "GemFireStatSamplerJUnitTest";
    final String archiveFileName = dir + File.separator + "testArchiveFileExists.gfs";
    
    final File archiveFile1 = new File(dir + File.separator + "testArchiveFileExists.gfs");
    
    Properties props = createGemFireProperties();
    props.setProperty("statistic-archive-file", archiveFileName);
    connect(props);

    GemFireStatSampler statSampler = getGemFireStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));

    final File archiveFile = statSampler.getArchiveFileName();
    assertNotNull(archiveFile);
    assertEquals(archiveFile1, archiveFile);

    WaitCriterion wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile.exists();
      }
      public String description() {
        return "archiveFile.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 5000, 10, true);

    assertTrue("File name incorrect: archiveFile.getName()=" + archiveFile.getName() +
        " archiveFile.getAbsolutePath()=" + archiveFile.getAbsolutePath() +
        " getCanonicalPath()" + archiveFile.getCanonicalPath(),
        archiveFileName.contains(archiveFile.getName()));
  }

  /**
   * Tests the statistics sample rate within an acceptable margin of error.
   */
  public void testSampleRate() throws Exception {
    connect(createGemFireProperties());

    GemFireStatSampler statSampler = getGemFireStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));

    assertEquals(STAT_SAMPLE_RATE, statSampler.getSampleRate());

    assertTrue(getStatisticsManager().getStatListModCount() > 0);

    @SuppressWarnings("unchecked")
    List<Statistics> statistics = getStatisticsManager().getStatsList();
    assertNotNull(statistics);
    assertTrue(statistics.size() > 0);

    StatisticsType statSamplerType = getStatisticsManager().findType("StatSampler");
    Statistics[] statsArray = getStatisticsManager().findStatisticsByType(statSamplerType);
    assertEquals(1, statsArray.length);

    final Statistics statSamplerStats = statsArray[0];
    final int initialSampleCount = statSamplerStats.getInt("sampleCount");
    final int expectedSampleCount = initialSampleCount + 2;

    WaitCriterion wc = new WaitCriterion() {
      public boolean done() {
        return statSamplerStats.getInt("sampleCount") >= expectedSampleCount;
      }
      public String description() {
        return "Waiting for sampleCount >= " + expectedSampleCount;
      }
    };
    DistributedTestBase.waitForCriterion(wc, 5000, 10, true);
  }

  /**
   * Adds a LocalStatListener for an individual stat. Validates that it
   * receives notifications. Removes the listener and validates that it
   * was in fact removed and no longer receives notifications.
   */
  public void testLocalStatListener() throws Exception {
    connect(createGemFireProperties());

    GemFireStatSampler statSampler = getGemFireStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));

    Method getLocalListeners = getGemFireStatSampler().getClass().getMethod("getLocalListeners");
    assertNotNull(getLocalListeners);

    Method addLocalStatListener = getGemFireStatSampler().getClass().getMethod("addLocalStatListener", LocalStatListener.class, Statistics.class, String.class);
    assertNotNull(addLocalStatListener);

    Method removeLocalStatListener = getGemFireStatSampler().getClass().getMethod("removeLocalStatListener", LocalStatListener.class);
    assertNotNull(removeLocalStatListener);

    // validate that there are no listeners
    assertTrue(statSampler.getLocalListeners().isEmpty());

    // add a listener for sampleCount stat in StatSampler statistics
    StatisticsType statSamplerType = getStatisticsManager().findType("StatSampler");
    Statistics[] statsArray = getStatisticsManager().findStatisticsByType(statSamplerType);
    assertEquals(1, statsArray.length);

    final Statistics statSamplerStats = statsArray[0];
    final String statName = "sampleCount";
    final AtomicInteger sampleCountValue = new AtomicInteger(0);
    final AtomicInteger sampleCountChanged = new AtomicInteger(0);

    LocalStatListener listener = new LocalStatListener() {
      public void statValueChanged(double value) {
        sampleCountValue.set((int)value);
        sampleCountChanged.incrementAndGet();
      }
    };

    statSampler.addLocalStatListener(listener, statSamplerStats, statName);
    assertTrue(statSampler.getLocalListeners().size() == 1);

    // there's a level of indirection here and some protected member fields
    LocalStatListenerImpl lsli = (LocalStatListenerImpl)
        statSampler.getLocalListeners().iterator().next();
    assertEquals("sampleCount", lsli.stat.getName());

    // wait for the listener to update 4 times
    final int expectedChanges = 4;
    WaitCriterion wc = new WaitCriterion() {
      public boolean done() {
        return sampleCountChanged.get() >= expectedChanges;
      }
      public String description() {
        return "Waiting for sampleCountChanged >= " + expectedChanges;
      }
    };
    DistributedTestBase.waitForCriterion(wc, 10000, 10, true);

    // validate that the listener fired and updated the value
    assertTrue(sampleCountValue.get() > 0);
    assertTrue(sampleCountChanged.get() >= expectedChanges);

    // remove the listener
    statSampler.removeLocalStatListener(listener);
    final int expectedSampleCountValue = sampleCountValue.get();
    final int expectedSampleCountChanged = sampleCountChanged.get();

    // validate that there are no listeners now
    assertTrue(statSampler.getLocalListeners().isEmpty());

    // wait for 2 stat samples to occur
    wc = new WaitCriterion() {
      public boolean done() {
        return statSamplerStats.getInt("sampleCount") >= expectedSampleCountValue;
      }
      public String description() {
        return "Waiting for sampleCount >= " + expectedSampleCountValue;
      }
    };
    DistributedTestBase.waitForCriterion(wc, 5000, 10, true);

    // validate that the listener did not fire
    assertEquals(expectedSampleCountValue, sampleCountValue.get());
    assertEquals(expectedSampleCountChanged, sampleCountChanged.get());
  }

  /**
   * Invokes stop() and then validates that the sampler did in fact stop.
   */
  public void testStop() throws Exception{
    connect(createGemFireProperties());

    GemFireStatSampler statSampler = getGemFireStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));

    // validate the stat sampler is running
    StatisticsType statSamplerType = getStatisticsManager().findType("StatSampler");
    Statistics[] statsArray = getStatisticsManager().findStatisticsByType(statSamplerType);
    assertEquals(1, statsArray.length);

    final Statistics statSamplerStats = statsArray[0];
    final int initialSampleCount = statSamplerStats.getInt("sampleCount");
    final int expectedSampleCount = initialSampleCount + 2;

    WaitCriterion wc = new WaitCriterion() {
      public boolean done() {
        return statSamplerStats.getInt("sampleCount") >= expectedSampleCount;
      }
      public String description() {
        return "Waiting for sampleCount >= " + expectedSampleCount;
      }
    };
    DistributedTestBase.waitForCriterion(wc, 20000, 10, true);

    // stop the stat sampler
    statSampler.stop();

    // validate the stat sampler has stopped
    final int stoppedSampleCount = statSamplerStats.getInt("sampleCount");

    // the following WaitCriterion should fail
    wc = new WaitCriterion() {
      public boolean done() {
        return statSamplerStats.getInt("sampleCount") > stoppedSampleCount;
      }
      public String description() {
        return "Waiting for sampleCount > " + stoppedSampleCount;
      }
    };
    DistributedTestBase.waitForCriterion(wc, 5000, 10, false);

    assertEquals(stoppedSampleCount, statSamplerStats.getInt("sampleCount"));
  }

  /**
   * Verifies that archive rolling works correctly when archive-file-size-limit
   * is specified.
   */
  public void testArchiveRolling() throws Exception {
    final String dir = "GemFireStatSamplerJUnitTest" + File.separator + "testArchiveRolling";
    new File(dir).mkdirs();
    final String archiveFileName = dir + File.separator + "testArchiveRolling.gfs";

    final File archiveFile = new File(archiveFileName);
    final File archiveFile1 = new File(dir + File.separator + "testArchiveRolling-01-01.gfs");
    final File archiveFile2 = new File(dir + File.separator + "testArchiveRolling-01-02.gfs");
    final File archiveFile3 = new File(dir + File.separator + "testArchiveRolling-01-03.gfs");

    // set the system property to use KB instead of MB for file size
    System.setProperty(HostStatSampler.TEST_FILE_SIZE_LIMIT_IN_KB_PROPERTY, "true");
    Properties props = createGemFireProperties();
    props.setProperty("archive-file-size-limit", "1");
    props.setProperty("archive-disk-space-limit", "0");
    props.setProperty("statistic-archive-file", archiveFileName);
    connect(props);

    assertTrue(getGemFireStatSampler().waitForInitialization(5000));

    WaitCriterion wc = new WaitCriterion() {
      public boolean done() {
        return getSampleCollector() != null && 
               getSampleCollector().getStatArchiveHandler() != null;
      }
      public String description() {
        return "getSampleCollector().getStatArchiveHandler() != null";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4000, 10, true);

    StatArchiveHandler statArchiveHandler = getSampleCollector().getStatArchiveHandler();
    StatArchiveHandlerConfig config = statArchiveHandler.getStatArchiveHandlerConfig();
    assertEquals(1 * 1024, config.getArchiveFileSizeLimit());
    
    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile.exists();
      }
      public String description() {
        return "archiveFile.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4000, 10, true);
    
    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile1.exists();
      }
      public String description() {
        return "archiveFile1.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4000, 10, true);
    
    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile2.exists();
      }
      public String description() {
        return "archiveFile2.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4000, 10, true);

    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile3.exists();
      }
      public String description() {
        return "archiveFile3.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4000, 10, true);
  }

  /**
   * Verifies that archive removal works correctly when archive-disk-space-limit
   * is specified.
   */
  public void testArchiveRemoval() throws Exception {
    final String dir = "GemFireStatSamplerJUnitTest" + File.separator + "testArchiveRemoval";
    new File(dir).mkdirs();
    final String archiveFileName = dir + File.separator + "testArchiveRemoval.gfs";

    final File archiveFile = new File(archiveFileName);
    final File archiveFile1 = new File(dir + File.separator + "testArchiveRemoval-01-01.gfs");
    final File archiveFile2 = new File(dir + File.separator + "testArchiveRemoval-01-02.gfs");
    final File archiveFile3 = new File(dir + File.separator + "testArchiveRemoval-01-03.gfs");
    final File archiveFile4 = new File(dir + File.separator + "testArchiveRemoval-01-04.gfs");

    final int sampleRate = 1000;
    
    // set the system property to use KB instead of MB for file size
    System.setProperty(HostStatSampler.TEST_FILE_SIZE_LIMIT_IN_KB_PROPERTY, "true");
    Properties props = createGemFireProperties();
    props.setProperty("statistic-archive-file", archiveFileName);
    props.setProperty("archive-file-size-limit", "1");
    props.setProperty("archive-disk-space-limit", "12");
    props.setProperty("statistic-sample-rate", String.valueOf(sampleRate));
    connect(props);

    assertTrue(getGemFireStatSampler().waitForInitialization(5000));

    WaitCriterion wc = new WaitCriterion() {
      private boolean exists1;
      private boolean exists2;
      private boolean exists3;
      private boolean exists4;
      private boolean exists;
      public boolean done() {
        exists1 = exists1 || archiveFile1.exists();
        exists2 = exists2 || archiveFile2.exists();
        exists3 = exists3 || archiveFile3.exists();
        exists4 = exists4 || archiveFile4.exists();
        exists = exists || archiveFile.exists();
        return exists1 && exists2 && exists3 && exists4 && exists;
      }
      public String description() {
        return "Waiting for archive files to exist:" 
            + " exists1=" + exists1
            + " exists2=" + exists2
            + " exists3=" + exists3
            + " exists4=" + exists4
            + " exists=" + exists;
      }
    };
    DistributedTestBase.waitForCriterion(wc, 10*sampleRate, 10, true);

    wc = new WaitCriterion() {
      public boolean done() {
        return !archiveFile1.exists();
      }
      public String description() {
        return "!archiveFile1.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 10*sampleRate, 10, true);
  }

  public void testLocalStatListenerRegistration() throws Exception{
    connect(createGemFireProperties());

    final GemFireStatSampler statSampler = getGemFireStatSampler();
    statSampler.waitForInitialization(5000);

    final AtomicBoolean flag = new AtomicBoolean(false);
    final LocalStatListener listener = new LocalStatListener(){
      public void statValueChanged(double value) {
        flag.set(true);
      }
    };
    
    final String tenuredPoolName = HeapMemoryMonitor.getTenuredMemoryPoolMXBean().getName();
    this.system.getLogWriter().info("TenuredPoolName: " + tenuredPoolName);
    
    @SuppressWarnings("rawtypes")
    final List list = ((StatisticsManager)this.system).getStatsList();
    assertFalse(list.isEmpty());

    WaitCriterion wc = new WaitCriterion() {
      public boolean done() {
        int i=0;
        synchronized (list) {
          for (Object obj : list) {
            statSampler.getLogger().convertToLogWriter().info("List:"+(++i)+":"+obj);
            if (obj instanceof StatisticsImpl) {
              StatisticsImpl si = (StatisticsImpl)obj;
              statSampler.getLogger().convertToLogWriter().info("stat:"+si.getTextId());
              if (si.getTextId().contains(tenuredPoolName)) {
                statSampler.addLocalStatListener(listener, si, "currentUsedMemory");
                return true;
              }
            }
          }
        }
        return false;
      }
      public String description() {
        return "Waiting for " + tenuredPoolName + " statistics to be added to create listener for";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 5000, 10, true);
    
    assertTrue("expected atleast one stat listener, found " +
        statSampler.getLocalListeners().size(), 
        statSampler.getLocalListeners().size() > 0);     
    
    long maxTenuredMemory = HeapMemoryMonitor.getTenuredMemoryPoolMXBean()
                          .getUsage().getMax();

    //byte[] bytes = new byte[1024 * 1024 * 10];
    byte[] bytes = new byte[(int)(maxTenuredMemory*0.01)];
    Arrays.fill(bytes, Byte.MAX_VALUE);

    wc = new WaitCriterion() {
      public boolean done() {
        return flag.get();
      }
      public String description() {
        return "listener will set flag to true";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 5000, 10, true);
  }
  
  @Override
  protected StatisticsManager getStatisticsManager() {
    return (InternalDistributedSystem)this.system;
  }

  protected OsStatisticsFactory getOsStatisticsFactory() {
    return (InternalDistributedSystem)this.system;
  }

  private GemFireStatSampler getGemFireStatSampler() {
    return ((InternalDistributedSystem)this.system).getStatSampler();
  }
  
  private SampleCollector getSampleCollector() {
    return getGemFireStatSampler().getSampleCollector();
  }

  private Properties createGemFireProperties() {
    Properties props = new Properties();
    props.setProperty("statistic-sampling-enabled", "true"); // TODO: test true/false
    props.setProperty("enable-time-statistics", "true"); // TODO: test true/false
    props.setProperty("statistic-sample-rate", String.valueOf(STAT_SAMPLE_RATE));
    props.setProperty("archive-file-size-limit", "0");
    props.setProperty("archive-disk-space-limit", "0");
    props.setProperty("mcast-port", "0");
    props.setProperty("locators", "");
    return props;
  }

  /**
   * Creates a fresh loner DistributedSystem for each test. Note
   * that the DistributedSystem is the StatisticsManager/Factory/etc.
   */
  @SuppressWarnings("deprecation")
  private void connect(Properties props) {
    this.system = DistributedSystem.connect(props);
  }

  @SuppressWarnings("deprecation")
  private void disconnect() {
    if (this.system != null) {
      this.system.disconnect();
      this.system = null;
    }
  }

//  public static class AsyncInvoker {
//    public static AsyncInvocation invokeAsync(Runnable r) {
//      return invokeAsync(r, "run", new Object[0]);
//    }
//    public static AsyncInvocation invokeAsync(Callable c) {
//      return invokeAsync(c, "call", new Object[0]);
//    }
//    public static AsyncInvocation invokeAsync(
//        final Object o, final String methodName, final Object[] args) {
//      AsyncInvocation ai =
//        new AsyncInvocation(o, methodName, new Runnable() {
//          public void run() {
//            MethExecutorResult result = 
//                MethExecutor.executeObject(o, methodName, args);
//            if (result.exceptionOccurred()) {
//              throw new AsyncInvocationException(result.getException());
//            }
//            AsyncInvocation.setReturnValue(result.getResult());
//          }
//      });
//      ai.start();
//      return ai;
//    }
//    
//    public static class AsyncInvocationException extends RuntimeException {
//      private static final long serialVersionUID = -5522299018650622945L;
//      /**
//       * Creates a new <code>AsyncInvocationException</code>.
//       */
//      public AsyncInvocationException(String message) {
//        super(message);
//      }
//
//      /**
//       * Creates a new <code>AsyncInvocationException</code> that was
//       * caused by a given exception
//       */
//      public AsyncInvocationException(String message, Throwable thr) {
//        super(message, thr);
//      }
//
//      /**
//       * Creates a new <code>AsyncInvocationException</code> that was
//       * caused by a given exception
//       */
//      public AsyncInvocationException(Throwable thr) {
//        super(thr.getMessage(), thr);
//      }
//    }
//  }
}
