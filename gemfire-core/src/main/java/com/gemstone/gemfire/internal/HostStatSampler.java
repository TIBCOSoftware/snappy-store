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
import java.net.UnknownHostException;
import java.util.List;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.internal.ProcessorKeeper21;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.statistics.SampleCollector;
import com.gemstone.gemfire.internal.statistics.StatArchiveHandlerConfig;
import com.gemstone.gemfire.internal.statistics.StatisticsSampler;
import com.gemstone.gemfire.internal.statistics.VMStats;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCountDownLatch;

/**
 * HostStatSampler implements a thread which will monitor, sample, and archive
 * statistics. It only has the common functionality that any sampler needs.

 * @author Darrel Schneider
 * @author Kirk Lund
 */
public abstract class HostStatSampler 
    implements Runnable, StatisticsSampler, StatArchiveHandlerConfig {

  public static final String TEST_FILE_SIZE_LIMIT_IN_KB_PROPERTY = "gemfire.stats.test.fileSizeLimitInKB";
  public static final String OS_STATS_DISABLED_PROPERTY = "osStatsDisabled";

  protected static final String INITIALIZATION_TIMEOUT_PROPERTY = "gemfire.statSamplerInitializationTimeout";
  protected static final int INITIALIZATION_TIMEOUT_DEFAULT = 3000;
  protected static final long INITIALIZATION_TIMEOUT_MILLIS = 
      Long.getLong(INITIALIZATION_TIMEOUT_PROPERTY, INITIALIZATION_TIMEOUT_DEFAULT);
  
  /** 
   * Used to check if the sampler thread wake-up is delayed, and log a warning if it is delayed by longer than 
   * the amount of milliseconds specified by this property. The value of 0 disables the check. 
   */
  private static final long STAT_SAMPLER_DELAY_THRESHOLD = Long.getLong("gemfire.statSamplerDelayThreshold", 3000);
  private static final long STAT_SAMPLER_DELAY_THRESHOLD_NANOS = NanoTimer.millisToNanos(STAT_SAMPLER_DELAY_THRESHOLD);
  
  private static final int MIN_MS_SLEEP = 1;
  
  private static final int WAIT_FOR_SLEEP_INTERVAL = 10;
  
  private static Thread statThread = null;

  private volatile boolean stopRequested = false;
  
  private final boolean osStatsDisabled = Boolean.getBoolean(OS_STATS_DISABLED_PROPERTY);
  private final boolean fileSizeLimitInKB;
  private final LogWriterI18n log;
  private final StatSamplerStats samplerStats;
  
  private VMStats vmStats;
  private SampleCollector sampleCollector;

  /**
   * Used to signal thread that are waiting for the stat sampler to be initialized.
   */
  private final StoppableCountDownLatch statSamplerInitializedLatch;

  private final CancelCriterion stopper;
  
  protected HostStatSampler(CancelCriterion stopper, 
                            LogWriterI18n log,
                            StatSamplerStats samplerStats) {
    this.stopper = stopper;
    this.statSamplerInitializedLatch = new StoppableCountDownLatch(this.stopper, 1);
    this.log = log;
    this.samplerStats = samplerStats;
    this.fileSizeLimitInKB = Boolean.getBoolean(TEST_FILE_SIZE_LIMIT_IN_KB_PROPERTY);
  }
  
  public final StatSamplerStats getStatSamplerStats() {
    return this.samplerStats;
  }
  
  /**
   * Returns the logger this sampler should use.
   */
  @Override
  public final LogWriterI18n getLogger() {
    return this.log;
  }
  
  /**
   * Returns the number of times a statistics resource has been add or deleted.
   */
  @Override
  public final int getStatisticsModCount() {
    return getStatisticsManager().getStatListModCount();
  }

  /**
   * Returns an array of all the current statistic resource instances.
   */
  @Override
  public final Statistics[] getStatistics() {
    return getStatisticsManager().getStatistics();
  }

  /**
   * Returns a unique id for the sampler's system.
   */
  @Override
  public final long getSystemId() {
    return getStatisticsManager().getId();
  }

  /**
   * Returns the time this sampler's system was started.
   */
  @Override
  public final long getSystemStartTime() {
    return getStatisticsManager().getStartTime();
  }

  /**
   * Returns the path to this sampler's system directory; if it has one.
   */
  @Override
  public final String getSystemDirectoryPath() {
    try {
      return SocketCreator.getHostName(SocketCreator.getLocalHost());
    } catch (UnknownHostException ignore) {
      return "";
    }
  }

  @Override
  public boolean waitForSample(long timeout) throws InterruptedException {
    final long endTime = System.currentTimeMillis() + timeout;
    final int startSampleCount = this.samplerStats.getSampleCount();
    while (System.currentTimeMillis() < endTime && 
        this.samplerStats.getSampleCount() <= startSampleCount) {
      Thread.sleep(WAIT_FOR_SLEEP_INTERVAL);
    }
    return this.samplerStats.getSampleCount() > startSampleCount;
  }

  @Override
  public SampleCollector waitForSampleCollector(long timeout) throws InterruptedException {
    final long endTime = System.currentTimeMillis() + timeout;
    while (System.currentTimeMillis() < endTime && 
        this.sampleCollector == null || !this.sampleCollector.isInitialized()) {
      Thread.sleep(WAIT_FOR_SLEEP_INTERVAL);
    }
    return this.sampleCollector;
  }
  
  /**
   * This service's main loop
   */
  @Override
  public final void run() {
      NanoTimer timer = new NanoTimer();

      getLogger().fine("HostStatSampler started");
      boolean latchCountedDown = false;
      try {
        initSpecialStats();
        
        this.sampleCollector = new SampleCollector(this);
        this.sampleCollector.initialize(this, NanoTimer.getTime());
        
        this.statSamplerInitializedLatch.countDown();
        latchCountedDown = true;

        DiskCapacityMonitor diskMon = DiskCapacityMonitor.getInstance();

        timer.reset();
        // subtract getNanoRate from lastTS to force a quick initial sample
        long nanosLastTimeStamp = timer.getLastResetTime() - getNanoRate();
        while (!stopRequested()) {
          SystemFailure.checkFailure();
          if (Thread.currentThread().isInterrupted()) {
            break;
          }
          final long nanosBeforeSleep = timer.getLastResetTime();
          final long nanosToDelay = nanosLastTimeStamp + getNanoRate();
          delay(timer, nanosToDelay);
          nanosLastTimeStamp = timer.getLastResetTime();
          if (!stopRequested() && isSamplingEnabled()) {
            final long nanosTimeStamp = timer.getLastResetTime();
            final long nanosElapsedSleeping = nanosTimeStamp - nanosBeforeSleep;
            checkElapsedSleepTime(nanosElapsedSleeping);
            if (stopRequested()) break;
            prepareGFXDStats(false);
            if (stopRequested()) break;
            sampleSpecialStats(false);
            if (stopRequested()) break;
            checkListeners();
            if (stopRequested()) break;
            
            this.sampleCollector.sample(nanosTimeStamp);
            
            final long nanosSpentWorking = timer.reset();
            accountForTimeSpentWorking(nanosSpentWorking, nanosElapsedSleeping);
          } else if (!stopRequested() && !isSamplingEnabled()) {
            prepareGFXDStats(true);
            if (stopRequested()) break;
            sampleSpecialStats(true); // fixes bug 42527
          }
          if (diskMon != null) {
            diskMon.checkAvailableSpace();
          }
          if(!stopRequested()) {
            try {
              ProcessorKeeper21.cleanupProcessor();
            }catch(Exception ex){
              getLogger().fine("HostStatSampler thread caught exception while "
            + "cleaning Processorkeeper:",ex);
            }
          }
        }
      } 
      catch (InterruptedException ex) {
        // Silently exit
      }
      catch (CancelException ex) {
        // Silently exit
      }
      catch (RuntimeException ex) {
        getLogger().severe(ex);
        throw ex;
      }
      catch (Error ex) {
        if (SystemFailure.isJVMFailureError(ex)) {
          SystemFailure.initiateFailure(ex);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw ex;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        getLogger().severe(ex);
        throw ex;
      } finally {
        try {
        closeSpecialStats();
        if (this.sampleCollector != null) {
          this.sampleCollector.close();
        }
        } finally {
          if (!latchCountedDown) {
            // Make sure the latch gets counted down since
            // other threads wait for this to indicate that 
            // the sampler is initialized.
            this.statSamplerInitializedLatch.countDown();
          }
        }
        getLogger().fine("HostStatSampler stopped");
      }
  }

  /**
   * Starts the main thread for this service.
   * @throws IllegalStateException if an instance of the {@link #statThread} is still running from a previous DistributedSystem.
   */
  public final void start() {
    synchronized(HostStatSampler.class) { 
      if (statThread != null) {
        try {
          int msToWait = getSampleRate() + 100;
          statThread.join(msToWait);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        } 
        if (statThread.isAlive()) {
          throw new IllegalStateException(LocalizedStrings.HostStatSampler_STATISTICS_SAMPLING_THREAD_IS_ALREADY_RUNNING_INDICATING_AN_INCOMPLETE_SHUTDOWN_OF_A_PREVIOUS_CACHE.toLocalizedString());
        }
      }  
      ThreadGroup group = 
        LogWriterImpl.createThreadGroup("StatSampler Threads", (LogWriterI18n)null);
      statThread = new Thread(group, this);
      statThread.setName(statThread.getName() + " StatSampler");
      statThread.setPriority(Thread.MAX_PRIORITY);
      statThread.setDaemon(true);
      statThread.start();
      // fix #46310 (race between management and sampler init) by waiting for init here
      try {
        waitForInitialization(INITIALIZATION_TIMEOUT_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Tell this service's main thread to terminate.
   */
  public final void stop() {
    synchronized (HostStatSampler.class) {
      if ( statThread == null) {
        return; 
      }

      this.stopRequested = true;
      synchronized (this) {
        this.notify();
      }
      try {
        statThread.join();
      } catch (InterruptedException ignore) {
        //It is important that we shutdown so we'll continue trying for another 100ms
        try {
          statThread.join(2000);
        } catch (InterruptedException ex) {
        } finally {
          Thread.currentThread().interrupt();
        }
      } finally {
        if ( statThread.isAlive() ) {
          getLogger().warning(LocalizedStrings.HostStatSampler_HOSTSTATSAMPLER_WAS_INTERRUPTED_DURRING_SHUTDOWN);
        } else {
          this.stopRequested = false;
          statThread = null;
        }
      }
    }
  }
  
  public final boolean isAlive() {
    return statThread.isAlive();
  }

  /**
   * Waits for the special statistics to be initialized. For tests, please
   * use {@link #waitForInitialization(long)} instead.
   *
   * @see #initSpecialStats
   * @since 3.5
   */
  public final void waitForInitialization() throws InterruptedException {
    this.statSamplerInitializedLatch.await();
  }
  
  /**
   * Waits for the special statistics to be initialized. This overridden
   * version of {@link #waitForInitialization()} should always be used
   * within tests.
   *
   * @see #initSpecialStats
   * @since 7.0
   */
  public final boolean waitForInitialization(long ms) throws InterruptedException {
    return this.statSamplerInitializedLatch.await(ms);
  }
  
  public final void changeArchive(File newFile) {
    this.sampleCollector.changeArchive(newFile, NanoTimer.getTime());
  }
  
  /**
   * Returns the <code>VMStats</code> for this VM.
   *
   * @since 3.5
   */
  public final VMStats getVMStats() {
    return this.vmStats;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(getClass().getName());
    sb.append("@").append(System.identityHashCode(this));
    return sb.toString();
  }

  protected abstract void checkListeners();

  /**
   * Gets the sample rate in milliseconds
   */
  protected abstract int getSampleRate();
  
  /**
   * Returns true if sampling is enabled.
   */
  public abstract boolean isSamplingEnabled();
  
  /**
   * Returns the statistics manager using this sampler.
   */
  protected abstract StatisticsManager getStatisticsManager();

  protected OsStatisticsFactory getOsStatisticsFactory() {
    return null;
  }
  
  protected void initProcessStats(long id) {
    // do nothing by default
  }
  
  protected void sampleProcessStats(boolean prepareOnly) {
    // do nothing by default
  }
  
  private void prepareGFXDStats(boolean prepareOnly) {
    StatsSamplerCallback callback = GemFireCacheImpl.FactoryStatics.statSamplerCallback;
    if ( callback != null ) {
      callback.prepareSamples(prepareOnly);
    }
  }
    
  protected void closeProcessStats() {
    // do nothing by default
  }

  protected long getSpecialStatsId() {
    return getStatisticsManager().getId();
  }
  
  protected final boolean fileSizeLimitInKB() {
    return this.fileSizeLimitInKB;
  }
  
  protected final boolean osStatsDisabled() {
    return this.osStatsDisabled;
  }
  
  protected final boolean stopRequested() {
    return stopper.cancelInProgress() != null || this.stopRequested;
  }

  public final SampleCollector getSampleCollector() {
    return this.sampleCollector;
  }
  
  /**
   * Initialize any special sampler stats
   */
  private synchronized void initSpecialStats() {
    // add a vm resource
    long id = getSpecialStatsId();
    this.vmStats = new VMStats(getStatisticsManager(), id);
    initProcessStats(id);
  }

  /**
   * Closes down anything initialied by initSpecialStats.
   */
  private synchronized void closeSpecialStats() {
    if (this.vmStats != null) {
      this.vmStats.close();
    }
    closeProcessStats();
  }
  
  /**
   * Called when this sampler has spent some time working and wants
   * it to be accounted for.
   */
  private void accountForTimeSpentWorking(long nanosSpentWorking, 
                                          long nanosSpentSleeping) {
    this.samplerStats.tookSample(
        nanosSpentWorking, getStatisticsManager().getStatisticsCount(), nanosSpentSleeping);
  }
  
  /**
   * @param timer a NanoTimer used to compute the elapsed delay
   * @param nanosToDelay the timestamp to delay until it is the current time
   */
  private void delay(NanoTimer timer, final long nanosToDelay) throws InterruptedException {
    timer.reset();
    long now = timer.getLastResetTime();
    long remainingNanos = nanosToDelay - now;
    if (remainingNanos <= 0) {
      remainingNanos = NanoTimer.millisToNanos(MIN_MS_SLEEP);
    }
    while (remainingNanos > 0 && !stopRequested()) {
      long ms = NanoTimer.nanosToMillis(remainingNanos);
      if (ms <= 0) {
        Thread.yield();
      } else {
        if (ms > MIN_MS_SLEEP) {
          ms -= MIN_MS_SLEEP;
        }
        synchronized (this) {
          if (stopRequested()) {
            // check stopRequested inside the sync to prevent a race in which the wait misses the stopper's notify.
            return;
          }
          this.wait(ms); // spurious wakeup ok
        }
      }
      timer.reset();
      now = timer.getLastResetTime();
      remainingNanos = nanosToDelay - now;
    }
  }
  
  private long getNanoRate() {
    return NanoTimer.millisToNanos(getSampleRate());
  }
  
  /**
   * Collect samples of any operating system statistics
   * 
   * @param prepareOnly
   *          set to true if you only want to call prepareForSample
   */
  private void sampleSpecialStats(boolean prepareOnly) {
    List<Statistics> statsList = getStatisticsManager().getStatsList();
    synchronized (statsList) {
      for (Statistics s : statsList) {
        if (stopRequested()) return;
        if (s instanceof StatisticsImpl) {
          ((StatisticsImpl)s).prepareForSample();
        }
      }
    }

    if (!prepareOnly && this.vmStats != null) {
      if (stopRequested()) return;
      this.vmStats.refresh();
    }
    sampleProcessStats(prepareOnly);
  }

  /**
   * Check the elapsed sleep time upon wakeup, and log a warning if it is longer than the delay
   * threshold.
   * 
   * @param elapsedSleepTime duration of sleep in nanoseconds
   */
  private void checkElapsedSleepTime(long elapsedSleepTime) {
    if (STAT_SAMPLER_DELAY_THRESHOLD > 0) {
      final long wakeupDelay = elapsedSleepTime - getNanoRate();
      if (wakeupDelay > STAT_SAMPLER_DELAY_THRESHOLD_NANOS) {
        this.samplerStats.incJvmPauses();
        getLogger().warning(LocalizedStrings.HostStatSampler_STATISTICS_SAMPLING_THREAD_DETECTED_A_WAKEUP_DELAY_OF_0_MS_INDICATING_A_POSSIBLE_RESOURCE_ISSUE, NanoTimer.nanosToMillis(wakeupDelay));
      }
    }
  }

  /**
   * Statistics sampler callback to GemFireXD for periodic refresh
   * of gfxd specific stats collected.
   * 
   * @author soubhikc
   *
   */
  public static interface StatsSamplerCallback {

    /**
     * Collect the required set of samples that is 
     * to be accumulated periodically.
     * @param prepareOnly TODO
     */
    void prepareSamples(boolean prepareOnly);
    
  }
  
}
