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

package com.gemstone.gemfire.cache;

import java.sql.Connection;

import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * Enumeration of the supported isolation-levels for transactions initiated by
 * {@link CacheTransactionManager}.
 * 
 * @author swale
 * @since 7.0
 */
public enum IsolationLevel {

  /**
   * No isolation.
   */
  NONE {

    @Override
    public final int getJdbcIsolationLevel() {
      return Connection.TRANSACTION_NONE;
    }

    @Override
    final int getAlternativeJdbcIsolationLevel() {
      return NO_ALTERNATIVE_JDBC_LEVEL;
    }
  },

  /**
   * Represents an isolation level of READ_COMMITTED.
   */
  READ_COMMITTED {

    @Override
    public final int getJdbcIsolationLevel() {
      return Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    final int getAlternativeJdbcIsolationLevel() {
      return Connection.TRANSACTION_READ_UNCOMMITTED;
    }
  },

  /**
   * Represents an isolation level of REPEATABLE_READ.
   */
  REPEATABLE_READ {

    @Override
    public final int getJdbcIsolationLevel() {
      return Connection.TRANSACTION_REPEATABLE_READ;
    }

    @Override
    final int getAlternativeJdbcIsolationLevel() {
      return NO_ALTERNATIVE_JDBC_LEVEL;
    }
  },

  /**
   * Represents an isolation level of SNAPSHOT. Shouldn't map to any JDBC isolation level
   */
  SNAPSHOT {

    @Override
    public final int getJdbcIsolationLevel() {
      return  NO_JDBC_LEVEL;
    }

    @Override
    final int getAlternativeJdbcIsolationLevel() {
      return NO_ALTERNATIVE_JDBC_LEVEL;
    }
  },
  ;

  /**
   * The default isolation level is {@link #READ_COMMITTED}.
   */
  public static final IsolationLevel DEFAULT = READ_COMMITTED;

  /**
   * Get the JDBC isolation-level (i.e.
   * {@link Connection#TRANSACTION_READ_COMMITTED},
   * {@link Connection#TRANSACTION_REPEATABLE_READ} etc.) corresponding to this
   * {@link IsolationLevel}.
   */
  public abstract int getJdbcIsolationLevel();

  /**
   * Returned by {@link #getAlternativeJdbcIsolationLevel()} when no alternative
   * JDBC level is applicable that has to be upgraded to this
   * {@link IsolationLevel}.
   */
  final static int NO_ALTERNATIVE_JDBC_LEVEL = -1;

  public final static int NO_JDBC_LEVEL = -1;

  /**
   * If some other JDBC isolation level is to be upgraded to this one, then
   * return that else return {@link #NO_ALTERNATIVE_JDBC_LEVEL}.
   */
  abstract int getAlternativeJdbcIsolationLevel();

  /**
   * Mapping of JDBC isolation-level (i.e.
   * {@link Connection#TRANSACTION_READ_COMMITTED},
   * {@link Connection#TRANSACTION_REPEATABLE_READ} etc.) to corresponding
   * {@link IsolationLevel}.
   */
  private final static IsolationLevel[] jdbcMapping;

  static {
    // initialize the jdbcMapping array by iterating through all the supported
    // isolation levels
    int max = -1;
    for (IsolationLevel level : values()) {
      final int jdbcLevel = level.getJdbcIsolationLevel();
      final int alternativeLevel = level.getAlternativeJdbcIsolationLevel();
      if (jdbcLevel != NO_JDBC_LEVEL
        && jdbcLevel > max) {
        max = jdbcLevel;
      }
      if (alternativeLevel != NO_ALTERNATIVE_JDBC_LEVEL
          && alternativeLevel > max) {
        max = alternativeLevel;
      }
    }
    jdbcMapping = new IsolationLevel[max + 1];
    for (IsolationLevel level : values()) {
      int jdbcLevel = level.getJdbcIsolationLevel();
      if(jdbcLevel != NO_JDBC_LEVEL) {
        jdbcMapping[jdbcLevel] = level;
      }
      final int alternativeLevel = level.getAlternativeJdbcIsolationLevel();
      if (alternativeLevel != NO_ALTERNATIVE_JDBC_LEVEL) {
        jdbcMapping[alternativeLevel] = level;
      }
    }
  }

  /**
   * Get the {@link IsolationLevel} given the JDBC isolation-level (i.e.
   * {@link Connection#TRANSACTION_READ_COMMITTED},
   * {@link Connection#TRANSACTION_REPEATABLE_READ} etc.).
   * 
   * @param jdbcIsolationLevel
   *          the JDBC isolation level (e.g.
   *          {@link Connection#TRANSACTION_READ_COMMITTED})
   * @param logWarningIfUpgraded
   *          if the given JDBC isolation level is upgraded and returned, then
   *          log a warning using this {@link LogWriterI18n} if non-null;
   *          currently this only happens when
   *          {@link Connection#TRANSACTION_READ_UNCOMMITTED} is upgraded to
   *          {@link IsolationLevel#READ_COMMITTED}.
   */
  public final static IsolationLevel fromJdbcIsolationLevel(
      final int jdbcIsolationLevel, final LogWriterI18n logWarningIfUpgraded) {
    final IsolationLevel level;
    if (jdbcIsolationLevel >= 0 && jdbcIsolationLevel < jdbcMapping.length
        && (level = jdbcMapping[jdbcIsolationLevel]) != null) {
      if (logWarningIfUpgraded != null
          && level.getJdbcIsolationLevel() != jdbcIsolationLevel) {
        logWarningIfUpgraded.warning(
            LocalizedStrings.Transaction_ISOLATION_IMPLICIT_UPGRADE,
            new Object[] { jdbcIsolationLevel, level });
      }
      return level;
    }
    throw new UnsupportedOperationException("Unsupported JDBC isolation level "
        + jdbcIsolationLevel);
  }

  /**
   * Get the {@link IsolationLevel} corresponding to the ordinal value obtained
   * from {@link IsolationLevel#ordinal()}.
   */
  public final static IsolationLevel fromOrdinal(final int ordinal) {
    return values()[ordinal];
  }
}
