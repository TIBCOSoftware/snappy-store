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

package com.gemstone.gemfire.internal.statistics;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;

import com.gemstone.gemfire.Statistics;
import com.gemstone.gemfire.StatisticsType;
import com.gemstone.gemfire.internal.OSProcess;
import com.gemstone.gemfire.internal.StatisticsImpl;
import com.gemstone.gemfire.internal.StatisticsManager;
import com.gemstone.gemfire.internal.StatisticsTypeImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * An implementation of {@link Statistics} that stores its statistics in local
 * java memory.
 *
 * @see <A href="package-summary.html#statistics">Package introduction</A>
 *
 * @since 8.1
 */
public final class AtomicStatisticsImpl extends StatisticsImpl {

  /** In JOM Statistics, the values of the int statistics */
  private final AtomicIntegerArray intStorage;

  /** In JOM Statistics, the values of the long statistics */
  private final AtomicLongArray longStorage;

  /** In JOM Statistics, the values of the double statistics */
  private final AtomicLongArray doubleStorage;

  /** The StatisticsFactory that created this instance */
  private final StatisticsManager dSystem;

  /////////////////////// Constructors ///////////////////////

  /**
   * Creates a new statistics instance of the given type
   *
   * @param type
   *          A description of the statistics
   * @param textId
   *          Text that identifies this statistic when it is monitored
   * @param numericId
   *          A number that displayed when this statistic is monitored
   * @param uniqueId
   *          A number that uniquely identifies this instance
   * @param system
   *          The distributed system that determines whether or not these
   *          statistics are stored (and collected) in GemFire shared memory or
   *          in the local VM
   */
  public AtomicStatisticsImpl(StatisticsType type, String textId,
      long numericId, long uniqueId, StatisticsManager system) {
    super(type, calcTextId(system, textId), calcNumericId(system, numericId),
        uniqueId, 0);
    this.dSystem = system;

    StatisticsTypeImpl realType = (StatisticsTypeImpl)type;
    int intCount = realType.getIntStatCount();
    int longCount = realType.getLongStatCount();
    int doubleCount = realType.getDoubleStatCount();

    if (intCount > 0) {
      this.intStorage = new AtomicIntegerArray(intCount);
    }
    else {
      this.intStorage = null;
    }

    if (longCount > 0) {
      this.longStorage = new AtomicLongArray(longCount);
    }
    else {
      this.longStorage = null;
    }

    if (doubleCount > 0) {
      this.doubleStorage = new AtomicLongArray(doubleCount);
    }
    else {
      this.doubleStorage = null;
    }
  }

  ////////////////////// Static Methods //////////////////////

  private static long calcNumericId(StatisticsManager system, long userValue) {
    if (userValue != 0) {
      return userValue;
    }
    else {
      long result = OSProcess.getId(); // fix for bug 30239
      if (result == 0) {
        if (system != null) {
          result = system.getId();
        }
      }
      return result;
    }
  }

  private static String calcTextId(StatisticsManager system, String userValue) {
    if (userValue != null && !userValue.equals("")) {
      return userValue;
    }
    else {
      if (system != null) {
        return system.getName();
      }
      else {
        return "";
      }
    }
  }

  ////////////////////// Instance Methods //////////////////////

  @Override
  public final boolean isAtomic() {
    return true;
  }

  @Override
  public void close() {
    super.close();
    if (this.dSystem != null) {
      dSystem.destroyStatistics(this);
    }
  }

  //////////////////////// store() Methods ///////////////////////

  @Override
  protected final void _setInt(int offset, int value) {
    this.intStorage.set(offset, value);
  }

  @Override
  protected final void _setLong(int offset, long value) {
    this.longStorage.set(offset, value);
  }

  @Override
  protected final void _setDouble(int offset, double value) {
    this.doubleStorage.set(offset, Double.doubleToLongBits(value));
  }

  /////////////////////// get() Methods ///////////////////////

  @Override
  protected final int _getInt(int offset) {
    return this.intStorage.get(offset);
  }

  @Override
  protected final long _getLong(int offset) {
    return this.longStorage.get(offset);
  }

  @Override
  protected final double _getDouble(int offset) {
    return Double.longBitsToDouble(this.doubleStorage.get(offset));
  }

  //////////////////////// inc() Methods ////////////////////////

  @Override
  protected final void _incInt(int offset, int delta) {
    this.intStorage.getAndAdd(offset, delta);
  }

  @Override
  protected final void _incLong(int offset, long delta) {
    this.longStorage.getAndAdd(offset, delta);
  }

  @Override
  protected final void _incDouble(int offset, double delta) {
    final AtomicLongArray doubleStorage = this.doubleStorage;
    while (true) {
      final long longValue = doubleStorage.get(offset);
      final long newValue = Double.doubleToLongBits(Double
          .longBitsToDouble(longValue) + delta);
      if (doubleStorage.compareAndSet(offset, longValue, newValue)) {
        return;
      }
    }
  }

  /////////////////// internal package methods //////////////////

  final int[] _getIntStorage() {
    throw new IllegalStateException(
        LocalizedStrings.Atomic50StatisticsImpl_DIRECT_ACCESS_NOT_ON_ATOMIC50
            .toLocalizedString());
  }

  final long[] _getLongStorage() {
    throw new IllegalStateException(
        LocalizedStrings.Atomic50StatisticsImpl_DIRECT_ACCESS_NOT_ON_ATOMIC50
            .toLocalizedString());
  }

  final double[] _getDoubleStorage() {
    throw new IllegalStateException(
        LocalizedStrings.Atomic50StatisticsImpl_DIRECT_ACCESS_NOT_ON_ATOMIC50
            .toLocalizedString());
  }
}
