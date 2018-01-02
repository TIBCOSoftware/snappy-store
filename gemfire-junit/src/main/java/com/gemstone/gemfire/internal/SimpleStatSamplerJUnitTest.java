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
import java.util.List;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.internal.statistics.VMStats;

import io.snappydata.test.dunit.DistributedTestBase;
import io.snappydata.test.dunit.DistributedTestBase.WaitCriterion;

/**
 * Integration tests for SimpleStatSampler.
 * 
 * @author Kirk Lund
 * @since 7.0
 */
public class SimpleStatSamplerJUnitTest extends StatSamplerTestCase {

  private LocalStatisticsFactory statisticsFactory;
  
  public SimpleStatSamplerJUnitTest(String name) {
    super(name);
  }
  
  public void setUp() throws Exception {
    super.setUp();
    new File("SimpleStatSamplerJUnitTest").mkdir();
    System.setProperty(SimpleStatSampler.ARCHIVE_FILE_NAME_PROPERTY, 
        "SimpleStatSamplerJUnitTest" + File.separator + SimpleStatSampler.DEFAULT_ARCHIVE_FILE_NAME);
  }

  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty(SimpleStatSampler.ARCHIVE_FILE_NAME_PROPERTY);
    System.clearProperty(SimpleStatSampler.FILE_SIZE_LIMIT_PROPERTY);
    System.clearProperty(SimpleStatSampler.DISK_SPACE_LIMIT_PROPERTY);
    System.clearProperty(SimpleStatSampler.SAMPLE_RATE_PROPERTY);
    closeStatisticsFactory();
  }
  
  /**
   * Tests the majority of getters and the basic functionality of the sampler.
   */
  public void testBasics() throws Exception {
    initStatisticsFactory();

    SimpleStatSampler statSampler = getSimpleStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));
    
    assertEquals(new File(System.getProperty(SimpleStatSampler.ARCHIVE_FILE_NAME_PROPERTY)), 
                 statSampler.getArchiveFileName());
    assertEquals(0, statSampler.getArchiveFileSizeLimit());
    assertEquals(0, statSampler.getArchiveDiskSpaceLimit());
    assertEquals(SimpleStatSampler.DEFAULT_SAMPLE_RATE, statSampler.getSampleRate());
    assertEquals(true, statSampler.isSamplingEnabled());
    
    int statsCount = statSampler.getStatisticsManager().getStatisticsCount();
    
    assertEquals(statsCount, statSampler.getStatisticsModCount());
    assertEquals(statsCount, statSampler.getStatisticsManager().getStatisticsCount());
    assertEquals(statsCount, statSampler.getStatistics().length);
    
    assertTrue(statsCount > 0);
    
    assertEquals(getStatisticsManager().getId(), statSampler.getSystemId());
    assertTrue(statSampler.getSystemStartTime() < System.currentTimeMillis());
    assertEquals(SocketCreator.getHostName(SocketCreator.getLocalHost()), 
                 statSampler.getSystemDirectoryPath());

    VMStats vmStats = statSampler.getVMStats();
    assertNotNull(vmStats);
    /* NOTE: VMStats is not an instance of Statistics but instead its
     * instance contains 3 instances of Statistics:
     * 1) vmStats
     * 2) heapMemStats
     * 3) nonHeapMemStats
     */
    
    Method getProcessStats = null;
    try {
      getProcessStats = SimpleStatSampler.class.getMethod("getProcessStats");
      fail("SimpleStatSampler should not have the method getProcessStats()");
    } catch (NoSuchMethodException exected) {
      // passed
    }
    assertNull(getProcessStats);
    
    assertEquals("Unknown product", statSampler.getProductDescription());
  }

  /**
   * Tests that the configured archive file is created and exists. 
   */
  public void testArchiveFileExists() throws Exception {
    final String dir = "SimpleStatSamplerJUnitTest";
    final String archiveFileName = dir + File.separator + "testArchiveFileExists.gfs";
    final File archiveFile1 = new File(dir + File.separator + "testArchiveFileExists.gfs");
    System.setProperty(SimpleStatSampler.ARCHIVE_FILE_NAME_PROPERTY, archiveFileName);
    initStatisticsFactory();
    
    SimpleStatSampler statSampler = getSimpleStatSampler();
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
    initStatisticsFactory();

    SimpleStatSampler statSampler = getSimpleStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));

    assertEquals(SimpleStatSampler.DEFAULT_SAMPLE_RATE, statSampler.getSampleRate());
    
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
    DistributedTestBase.waitForCriterion(wc, 20000, 10, true);
  }
  
  /**
   * Tests lack of methods for supporting LocalStatListener.
   */
  public void testLocalStatListener() throws Exception {
    initStatisticsFactory();

    SimpleStatSampler statSampler = getSimpleStatSampler();
    assertTrue(statSampler.waitForInitialization(5000));
    
    Method getLocalListeners = null;
    try {
      getLocalListeners = getSimpleStatSampler().getClass().getMethod("getLocalListeners");
      fail("SimpleStatSampler should not have the method getLocalListeners()");
    } catch (NoSuchMethodException exected) {
      // passed
    }
    assertNull(getLocalListeners);

    Method addLocalStatListener = null;
    try {
      addLocalStatListener = getSimpleStatSampler().getClass().getMethod("addLocalStatListener", LocalStatListener.class, Statistics.class, String.class);
      fail("SimpleStatSampler should not have the method addLocalStatListener()");
    } catch (NoSuchMethodException exected) {
      // passed
    }
    assertNull(addLocalStatListener);

    Method removeLocalStatListener = null;
    try {
      removeLocalStatListener = getSimpleStatSampler().getClass().getMethod("removeLocalStatListener", LocalStatListener.class);
      fail("SimpleStatSampler should not have the method addLocalStatListener()");
    } catch (NoSuchMethodException exected) {
      // passed
    }
    assertNull(removeLocalStatListener);
  }
  
  /**
   * Invokes stop() and then validates that the sampler did in fact stop.
   */
  public void testStop() throws Exception{
    initStatisticsFactory();

    SimpleStatSampler statSampler = getSimpleStatSampler();
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
   * is specified. This feature is broken in SimpleStatSampler.
   */
  public void testArchiveRolling() throws Exception {
    // set the system property to use KB instead of MB for file size
    System.setProperty(HostStatSampler.TEST_FILE_SIZE_LIMIT_IN_KB_PROPERTY, "true");

    final String dir = "SimpleStatSamplerJUnitTest" + File.separator + "testArchiveRolling";
    new File(dir).mkdir();

    final String archiveFileName = dir + File.separator + "testArchiveRolling.gfs";
    System.setProperty(SimpleStatSampler.ARCHIVE_FILE_NAME_PROPERTY, archiveFileName);
    System.setProperty(SimpleStatSampler.FILE_SIZE_LIMIT_PROPERTY, "1");
    System.setProperty(SimpleStatSampler.DISK_SPACE_LIMIT_PROPERTY, "0");
    System.setProperty(SimpleStatSampler.SAMPLE_RATE_PROPERTY, "1000");
    initStatisticsFactory();

    final File archiveFile1 = new File(dir + File.separator + "testArchiveRolling-01-01.gfs");
    final File archiveFile2 = new File(dir + File.separator + "testArchiveRolling-01-02.gfs");
    final File archiveFile3 = new File(dir + File.separator + "testArchiveRolling-01-03.gfs");
    final File archiveFile4 = new File(dir + File.separator + "testArchiveRolling-01-04.gfs");

    assertTrue(getSimpleStatSampler().waitForInitialization(5000));

    assertTrue(getSimpleStatSampler().fileSizeLimitInKB());
    assertEquals(1024, getSimpleStatSampler().getArchiveFileSizeLimit());

    WaitCriterion wc = new WaitCriterion() {
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

    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile4.exists();
      }
      public String description() {
        return "archiveFile4.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4000, 10, true);
  }

  /**
   * Verifies that archive removal works correctly when archive-disk-space-limit
   * is specified. This feature is broken in SimpleStatSampler. 
   */
  public void testArchiveRemoval() throws Exception {
    // set the system property to use KB instead of MB for file size
    System.setProperty(HostStatSampler.TEST_FILE_SIZE_LIMIT_IN_KB_PROPERTY, "true");

    final String dir = "SimpleStatSamplerJUnitTest" + File.separator + "testArchiveRemoval";
    new File(dir).mkdir();
    
    final String archiveFileName = dir + File.separator + "testArchiveRemoval.gfs";
    final int sampleRate = 1000;
    System.setProperty(SimpleStatSampler.ARCHIVE_FILE_NAME_PROPERTY, archiveFileName);
    System.setProperty(SimpleStatSampler.FILE_SIZE_LIMIT_PROPERTY, "1");
    System.setProperty(SimpleStatSampler.DISK_SPACE_LIMIT_PROPERTY, "12");
    System.setProperty(SimpleStatSampler.SAMPLE_RATE_PROPERTY, String.valueOf(sampleRate));
    initStatisticsFactory();

    final File archiveFile1 = new File(dir + File.separator + "testArchiveRemoval-01-01.gfs");
    final File archiveFile2 = new File(dir + File.separator + "testArchiveRemoval-01-02.gfs");
    final File archiveFile3 = new File(dir + File.separator + "testArchiveRemoval-01-03.gfs");
    final File archiveFile4 = new File(dir + File.separator + "testArchiveRemoval-01-04.gfs");
    final File archiveFile5 = new File(dir + File.separator + "testArchiveRemoval-01-05.gfs");
    
    assertTrue(getSimpleStatSampler().waitForInitialization(5000));

    WaitCriterion wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile1.exists();
      }
      public String description() {
        return "archiveFile1.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4*sampleRate, 10, true);
    
    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile2.exists();
      }
      public String description() {
        return "archiveFile2.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4*sampleRate, 10, true);
    
    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile3.exists();
      }
      public String description() {
        return "archiveFile3.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4*sampleRate, 10, true);

    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile4.exists();
      }
      public String description() {
        return "archiveFile4.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4*sampleRate, 10, true);

    wc = new WaitCriterion() {
      public boolean done() {
        return archiveFile5.exists();
      }
      public String description() {
        return "archiveFile5.exists()";
      }
    };
    DistributedTestBase.waitForCriterion(wc, 4*sampleRate, 10, true);

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

  @Override
  protected StatisticsManager getStatisticsManager() {
    return this.statisticsFactory;
  }

  private SimpleStatSampler getSimpleStatSampler() {
    return this.statisticsFactory.getStatSampler();
  }
  
  private void initStatisticsFactory() {
    CancelCriterion stopper = new CancelCriterion() {
      public String cancelInProgress() {
        return null;
      }
      public RuntimeException generateCancelledException(Throwable e) {
        return null;
      }
    };
    this.statisticsFactory = new LocalStatisticsFactory(stopper);
  }
  
  private void closeStatisticsFactory() {
    if (this.statisticsFactory != null) {
      this.statisticsFactory.close();
      this.statisticsFactory = null;
    }
  }
}
