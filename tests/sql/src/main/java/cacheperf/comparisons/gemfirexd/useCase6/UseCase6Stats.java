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
package cacheperf.comparisons.gemfirexd.useCase6;

import cacheperf.comparisons.gemfirexd.QueryPerfException;
import com.gemstone.gemfire.StatisticDescriptor;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.internal.NanoTimer;
import perffmwk.HistogramStats;
import perffmwk.PerformanceStatistics;

/**
 * Implements statistics related to UseCase5.
 */
public class UseCase6Stats extends PerformanceStatistics {

  public static enum Stmt {
    selstm, inslog, updbal, insbal, updterm, insterm, instkt, instsn;
  }

  /** <code>UseCase6Stats</code> are maintained on a per-thread basis */
  private static final int SCOPE = THREAD_SCOPE;

  public static final String VM_COUNT = "vmCount";

  protected static final String SELECTUPDATE_TX_COMPLETED = "selectUpdateTxCompleted";
  protected static final String SELECTUPDATE_TX_TIME = "selectUpdateTxTime";


  ////////////////////////  Static Methods  ////////////////////////

  /**
   * Returns the statistic descriptors for <code>UseCase6Stats</code>.
   */
  public static StatisticDescriptor[] getStatisticDescriptors() {
    boolean largerIsBetter = true;
    return new StatisticDescriptor[] {
      factory().createIntGauge
      (
        VM_COUNT,
        "When aggregated, the number of VMs using this statistics object.",
        "VMs"
      ),
      factory().createIntCounter
      (
        SELECTUPDATE_TX_COMPLETED,
        "Number of select and update transactions completed (committed).",
        "operations",
        largerIsBetter
      ),
      factory().createLongCounter
      (
        SELECTUPDATE_TX_TIME,
        "Total time spent on select and update transactions that were completed (committed).",
        "nanoseconds",
        !largerIsBetter
      ),

    };
  }

  public static UseCase6Stats getInstance() {
    UseCase6Stats tps =
         (UseCase6Stats)getInstance(UseCase6Stats.class, SCOPE);
    tps.incVMCount();
    return tps;
  }
  public static UseCase6Stats getInstance(String name) {
    UseCase6Stats tps =
         (UseCase6Stats)getInstance(UseCase6Stats.class, SCOPE, name);
    tps.incVMCount();
    return tps;
  }
  public static UseCase6Stats getInstance( String name, String trimspecName ) {
    UseCase6Stats tps =
         (UseCase6Stats)getInstance(UseCase6Stats.class, SCOPE, name, trimspecName);
    tps.incVMCount();
    return tps;
  }

/////////////////// Construction / initialization ////////////////

  public UseCase6Stats( Class cls, StatisticsType type, int scope,
                    String instanceName, String trimspecName ) {
    super( cls, type, scope, instanceName, trimspecName );
  }

/////////////////// Updating stats /////////////////////////

// vm count --------------------------------------------------------------------

  /**
   * increase the count on the vmCount
   */
  private synchronized void incVMCount() {
    if (!VMCounted) {
      statistics().incInt(VM_COUNT, 1);
      VMCounted = true;
    }
  }
  private static boolean VMCounted = false;

// histogram -------------------------------------------------------------------

  /**
   * increase the time on the optional histogram by the supplied amount
   */
  public void incHistogram(HistogramStats histogram, long amount) {
    if (histogram != null) {
      histogram.incBin(amount);
    }
  }

// transactions ----------------------------------------------------------------

  public long startCommit() {
    return NanoTimer.getTime();
  }

  public long startTransaction() {
    return NanoTimer.getTime();
  }

  public void endTransaction(long start, long commitStart) {
    long end = NanoTimer.getTime();
    long elapsed = end - start;
    statistics().incInt(SELECTUPDATE_TX_COMPLETED, 1);
    statistics().incLong(SELECTUPDATE_TX_TIME, elapsed);
  }

}
