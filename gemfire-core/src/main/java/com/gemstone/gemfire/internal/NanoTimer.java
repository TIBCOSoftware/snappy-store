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

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.SystemProperties;

/**
 * A timer class that reports current or elapsed time in nanonseconds.
 * The static method {@link #getTime} reports the current time.
 * The instance methods support basic
 * stop-watch-style functions that are convenient for simple performance
 * measurements. For example:
 * <pre>
  class Example {
     void example() {
       NanoTimer timer = new NanoTimer();
       for (int i = 0; i < n; ++i) {
	  someComputationThatYouAreMeasuring();
	  long duration = timer.reset();
	  System.out.println("Duration: " + duration);
	  // To avoid contaminating timing with printing times,
	  // you could call reset again here.
       }
       long average = timer.getTimeSinceConstruction() / n;
       System.out.println("Average: " + average);
     }
   }
 * </pre>
 * 
 * @author Darrel Schneider
 * @author Kirk Lund
 */
public final class NanoTimer {

  public static final long NANOS_PER_MILLISECOND = 1000000;

  private static boolean isNativeTimer;

  private static final NativeCalls nativeCall = NativeCalls.getInstance();

  public static final int CLOCKID_BEST;
  public static boolean CLOCKID_USE_SYSNANOTIME;

  public final static String NATIVETIMER_TYPE_PROPERTY =
      "gemfire.nativetimer.type";

  public static int nativeTimerType;

  static {
    /*
     * currently _nanoTime(..) isn't implemented in gemfire lib.
     * for gemfirexd, its implemented only for Linux/Solaris as of now.
     * 
     * TODO:SB: check for Mac linux variants.
     */
    try {
      isNativeTimer = GemFireCacheImpl.gfxdSystem()
          && NativeCalls.getInstance().loadNativeLibrary()
          && SharedLibrary.register("gemfirexd");

      // test method call. can throw UnsatisfiedLinkError if unsuccessful.
      _nanoTime(NativeCalls.CLOCKID_REALTIME);
    } catch (Exception | UnsatisfiedLinkError e) {
      isNativeTimer = false;
      if (SharedLibrary.debug) {
        SharedLibrary.logInitMessage(LogWriterImpl.WARNING_LEVEL,
            "_nanoTime couldn't be invoked successfully.", e);
      }
    }

    int clockIdBest = NativeCalls.CLOCKID_MONOTONIC;
    if (isNativeTimer) {
      final NativeCalls n = NativeCalls.getInstance();
      if (n != null && n.isNativeTimerEnabled()) {
        final String msg = "nanoTime clock resolution: MONOTONIC="
            + n.clockResolution(NativeCalls.CLOCKID_MONOTONIC)
            + " CLOCK_PROCESS_CPUTIME_ID="
            + n.clockResolution(NativeCalls.CLOCKID_PROCESS_CPUTIME_ID)
            + " CLOCK_THREAD_CPUTIME_ID="
            + n.clockResolution(NativeCalls.CLOCKID_THREAD_CPUTIME_ID);
        SharedLibrary.logInitMessage(LogWriterImpl.INFO_LEVEL, msg, null);

        String testMsg = "nanoTime time taken for call+loop:";
        // don't really believe clock_getres
        // clockIds listed in order of preference; MONOTONIC highest
        // preference since if it is proper then it is most efficient
        // via System.nanoTime() itself
        int[] clockIds = { NativeCalls.CLOCKID_PROCESS_CPUTIME_ID,
            NativeCalls.CLOCKID_THREAD_CPUTIME_ID,
            NativeCalls.CLOCKID_MONOTONIC };
        // keep some warmup runs
        int numRuns = 5;
        while (numRuns-- > 0) {
          clockIdBest = NativeCalls.CLOCKID_MONOTONIC;
          for (int clockId : clockIds) {
            int sum = 0;
            long start = _nanoTime(clockId);
            for (int i = 0; i < 50; i++) {
              sum += i;
            }
            long end = _nanoTime(clockId);
            if (numRuns == 0) {
              testMsg += " CLOCKID=" + clockId + " time=" + (end - start)
                  + "nanos (sum=" + sum + ')';
            }
            if (end > start) {
              clockIdBest = clockId;
            }
          }
        }
        SharedLibrary.logInitMessage(LogWriterImpl.FINE_LEVEL, testMsg, null);
      }
    }
    CLOCKID_BEST = clockIdBest;
    SharedLibrary.logInitMessage(LogWriterImpl.FINE_LEVEL,
        "Choosing CLOCKID=" + CLOCKID_BEST, null);
    setNativeTimer(true,
        getNativeTimerTypeFromString(SystemProperties.getServerInstance()
            .getString(NATIVETIMER_TYPE_PROPERTY, "DEFAULT")));
  }

  /**
   * implemented in utils.c (@see com/pivotal/gemfirexd/internal/engine) & packed
   * into gemfirexd native library. any change in the implementation should be
   * recompiled using gfxd-rebuild-shared-library after incrementing the library
   * version (gemfirexd.native.version) in the build script.
   * 
   * @param clk_id
   *        enumeration constants as defined here.
   */
  public static native long _nanoTime(int clk_id);
  
  /**
   * The timestamp taken when this timer was constructed.
   */
  private final long constructionTime;

  /**
   * The timestamp taken when this timer was last reset or constructed.
   */
  private long lastResetTime;
  
  /**
   * Create a NanoTimer.
   */
  public NanoTimer() {
    this.lastResetTime = getTime();
    this.constructionTime = this.lastResetTime;
  }

  /**
   * Converts nanoseconds to milliseconds by dividing nanos by 
   * {@link #NANOS_PER_MILLISECOND}.
   * 
   * @param nanos value in nanoseconds
   * @return value converted to milliseconds
   */
  public static long nanosToMillis(long nanos) {
    return nanos / NANOS_PER_MILLISECOND;
  }
  
  /**
   * Converts milliseconds to nanoseconds by multiplying millis by 
   * {@link #NANOS_PER_MILLISECOND}.
   * 
   * @param millis value in milliseconds
   * @return value converted to nanoseconds
   */
  public static long millisToNanos(long millis) {
    return millis * NANOS_PER_MILLISECOND;
  }

  /**
   * Return the time in nanoseconds since some arbitrary time in the past.
   * The time rolls over to zero every 2^64 nanosecs (approx 584 years).
   * Interval computations spanning periods longer than this will be wrong. 
   */
  public static long getTime() {
    return java.lang.System.nanoTime();
  }

  /**
   * Indicates whether native library will be used.
   * 
   * @return returns whether JNI based timer is enabled.
   */
  public static final boolean isJNINativeTimerEnabled() {
    return isNativeTimer;
  }

  /**
   * Check native timer using jni/jna is implemented in this platform.
   * 
   * @return true if using o/s level system call via jni otherwise false.
   * @see #isJNINativeTimerEnabled()
   */
  public static final boolean isNativeTimerEnabled() {
    return isNativeTimer || nativeCall.isNativeTimerEnabled();
  }

  /**
   * Nanosecond precision performance counter using native system call. calling
   * overhead is listed below for various kinds of clocks. if high precision
   * counter is unsupported by the o/s or high precision clock isn't implemented
   * yet, falls back to java.lang.System.nanoTime.<br>
   * 
   * The values for <code>clock_id</code> argument now reside in
   * {@link NativeCalls} class.
   * 
   * @param clock_id
   *          <dl>
   *          <dt>CLOCK_THREAD_CPUTIME_ID</dt>
   *          <U>In Linux:<br>
   *          </U> Average overhead is ~120 nanosecond irrespective of number of
   *          threads. This is because it provides thread work time excluding
   *          unscheduled wait time by the o/s (@see clock_gettime). <br>
   *          <br>
   * 
   *          <dt>CLOCK_PROCESS_CPUTIME_ID</dt>
   *          <U>In Linux:<br>
   *          </U> Average overhead varies depending on number of concurrent
   *          threads. For single threaded call it incurs ~5 to ~11 microsecond
   *          <br>
   *          <br>
   * 
   *          <dt>CLOCK_REALTIME, CLOCK_MONOTONIC, CLOCK_MONOTONIC_RAW</dt>
   *          <U>In Linux:<br>
   *          </U> Yields similar performance as o/s clock resolution offered by
   *          java.lang.System.nanoTime.
   *          </dl>
   * 
   * @param useJNA
   *          if false, avoids JNA system call. if true, attempts to use jni for
   *          implemented platforms otherwise uses jna implementation. if
   *          neither is supported, returns System.nanoTime.
   * 
   * @see #isNativeTimerEnabled()
   * @return performance counter long value.
   */
  public static final long nativeNanoTime(final int clock_id,
      final boolean useJNA) {
    return (isNativeTimer ? _nanoTime(clock_id)
        : (useJNA ? NativeCalls.getInstance().nanoTime(clock_id)
            : java.lang.System.nanoTime()));
  }

  /**
   *
   * This function was added because the call to {@link #nativeNanoTime(int, boolean)} with  
   * CLOCKID_PROCESS_CPUTIME_ID is taking few milliseconds. With this, EXPLAIN query 
   * scenarios are terribly slow because there can be millions of calls to nanoTime.
   * java.lang.System.nanoTime() should be perfect in most of the cases because the 
   * timer resolution is 1 ns and timer speed is few tens of nano seconds. Making it 
   * as the default behavior. If {@link #NATIVETIMER_TYPE_PROPERTY} is set then
   * native timer of the specified type is used.
   */
  public static final long nanoTime() {
    return CLOCKID_USE_SYSNANOTIME ? java.lang.System.nanoTime()
        : nativeNanoTime(nativeTimerType, true);
  }

  /**
   * Return the construction time in nanoseconds since some arbitrary time
   * in the past.
   * 
   * @return timestamp in nanoseconds since construction.
   */
  public long getConstructionTime() {
    return this.constructionTime;
  }
  
  /**
   * Return the last reset time in naonseconds since some arbitrary time
   * in the past.
   * <p/>
   * The time rolls over to zero every 2^64 nanosecs (approx 584 years).
   * Interval computations spanning periods longer than this will be wrong.
   * If the timer has not yet been reset then the construction time
   * is returned.
   * 
   * @return timestamp in nanoseconds of construction or the last reset.
   */
  public long getLastResetTime() {
    return this.lastResetTime;
  }

  /**
   * Compute and return the time in nanoseconds since the last reset or
   * construction of this timer, and reset the timer to the current
   * {@link #getTime}.
   * 
   * @return time in nanoseconds since construction or last reset.
   */
  public long reset() {
    long save = this.lastResetTime;
    this.lastResetTime = getTime();
    return this.lastResetTime - save;
  }

  /**
   * Compute and return the time in nanoseconds since the last reset or
   * construction of this Timer, but does not reset this timer. 
   * 
   * @return time in nanoseconds since construction or last reset.
   */
  public long getTimeSinceReset() {
    return getTime() - this.lastResetTime;
  }

  /**
   * Compute and return the time in nanoseconds since this timer was
   * constructed. 
   * 
   * @return time in nanoseconds since construction.
   */
  public long getTimeSinceConstruction() {
    return getTime() - this.constructionTime;
  }

  static final int getNativeTimerTypeFromString(String timerType) {
    timerType = timerType.toUpperCase();
    if (timerType.equals("CLOCK_REALTIME")) {
      return NativeCalls.CLOCKID_REALTIME;
    } else if (timerType.equals("CLOCK_MONOTONIC")) {
      return NativeCalls.CLOCKID_MONOTONIC;
    } else if (timerType.equals("CLOCK_PROCESS_CPUTIME_ID")) {
      return NativeCalls.CLOCKID_PROCESS_CPUTIME_ID;
    } else if (timerType.equals("CLOCK_THREAD_CPUTIME_ID")) {
      return NativeCalls.CLOCKID_THREAD_CPUTIME_ID;
    } else if (timerType.equals("CLOCK_MONOTONIC_RAW")) {
      return NativeCalls.CLOCKID_MONOTONIC_RAW;
    } else if (timerType.equals("DEFAULT")) {
      return CLOCKID_BEST;
    } else {
      throw new IllegalArgumentException(
          "Unknown native clockId type = " + timerType);
    }
  }

  public static final void setNativeTimer(boolean nativeTimer,
      String timerType) {
    setNativeTimer(nativeTimer, getNativeTimerTypeFromString(timerType));
  }

  static final void setNativeTimer(boolean nativeTimer, int timerType) {
    if (nativeTimer) {
      nativeTimerType = timerType;
      CLOCKID_USE_SYSNANOTIME = (timerType == NativeCalls.CLOCKID_MONOTONIC);
    } else {
      // reset to default timer type ignoring the actual argument
      nativeTimerType = NativeCalls.CLOCKID_MONOTONIC;
      CLOCKID_USE_SYSNANOTIME = true;
    }
  }

  public static final boolean getIsNativeTimer() {
    return nativeTimerType != NativeCalls.CLOCKID_MONOTONIC;
  }

  public static final String getNativeTimerType() {
    switch (nativeTimerType) {
      case NativeCalls.CLOCKID_REALTIME:
        return "CLOCK_REALTIME";
      case NativeCalls.CLOCKID_MONOTONIC:
        return "CLOCK_MONOTONIC";
      case NativeCalls.CLOCKID_PROCESS_CPUTIME_ID:
        return "CLOCK_PROCESS_CPUTIME_ID";
      case NativeCalls.CLOCKID_THREAD_CPUTIME_ID:
        return "CLOCK_THREAD_CPUTIME_ID";
      case NativeCalls.CLOCKID_MONOTONIC_RAW:
        return "CLOCK_MONOTONIC_RAW";
      default:
        return "UNKNOWN";
    }
  }
}
