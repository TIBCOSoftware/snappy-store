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

package com.pivotal.gemfirexd.internal.engine;

import java.sql.SQLException;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableHashtable;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.vti.VTICosting;
import com.pivotal.gemfirexd.internal.vti.VTIEnvironment;
import com.pivotal.gemfirexd.internal.vti.VTITemplate;

/**
 * This extends {@link VTITemplate} with GemFireXD specific needs. All
 * GemFireXD VTIs should extend this interface.
 * 
 * @author swale
 */
public abstract class GfxdVTITemplate extends VTITemplate implements VTICosting {

  /** Boolean that denotes if the last value returned was null. */
  protected boolean wasNull;
  
  protected FormatableHashtable compileTimeConstants;

  public void setSharedState(final FormatableHashtable compiledState) {
    this.compileTimeConstants = compiledState;
  }

  @Override
  public String getString(int columnNumber) throws SQLException {
    final Object res = getObject(columnNumber);
    return (res != null ? res.toString() : null);
  }

  @Override
  public final Object getObject(int columnNumber) throws SQLException {
    final Object res = getObjectForColumn(columnNumber);
    this.wasNull = (res == null);
    return res;
  }

  @Override
  public boolean getBoolean(int columnNumber) throws SQLException {
    final Object v = getObject(columnNumber);
    if (v != null) {
      if (v instanceof Boolean) {
        return ((Boolean)v).booleanValue();
      }
      else {
        return super.getBoolean(columnNumber);
      }
    }
    else {
      return false;
    }
  }

  @Override
  public int getInt(int columnNumber) throws SQLException {
    final Object v = getObject(columnNumber);
    if (v != null) {
      if (v instanceof Boolean) {
        return ((Boolean)v).booleanValue() ? 1 : 0;
      }
      else if (v instanceof Number) {
        return ((Number)v).intValue();
      }
      else {
        return super.getInt(columnNumber);
      }
    }
    else {
      return 0;
    }
  }

  @Override
  public long getLong(int columnNumber) throws SQLException {
    final Object v = getObject(columnNumber);
    if (v != null) {
      if (v instanceof Boolean) {
        return ((Boolean)v).booleanValue() ? 1 : 0;
      }
      else if (v instanceof Number) {
        return ((Number)v).longValue();
      }
      else {
        return super.getLong(columnNumber);
      }
    }
    else {
      return 0;
    }
  }

  @Override
  public double getDouble(int columnNumber) throws SQLException {
    final Object v = getObject(columnNumber);
    if (v != null) {
      if (v instanceof Boolean) {
        return ((Boolean)v).booleanValue() ? 1.0 : 0.0;
      }
      else if (v instanceof Number) {
        return ((Number)v).doubleValue();
      }
      else {
        return super.getDouble(columnNumber);
      }
    }
    else {
      return 0.0;
    }
  }

  @Override
  public float getFloat(int columnNumber) throws SQLException {
    final Object v = getObject(columnNumber);
    if (v != null) {
      if (v instanceof Boolean) {
        return ((Boolean)v).booleanValue() ? 1.0f : 0.0f;
      }
      else if (v instanceof Number) {
        return ((Number)v).floatValue();
      }
      else {
        return super.getFloat(columnNumber);
      }
    }
    else {
      return 0.0f;
    }
  }

  @Override
  public short getShort(int columnNumber) throws SQLException {
    final Object v = getObject(columnNumber);
    if (v != null) {
      if (v instanceof Boolean) {
        return (short)(((Boolean)v).booleanValue() ? 1 : 0);
      }
      else if (v instanceof Number) {
        return ((Number)v).shortValue();
      }
      else {
        return super.getShort(columnNumber);
      }
    }
    else {
      return 0;
    }
  }

  @Override
  public byte getByte(int columnNumber) throws SQLException {
    final Object v = getObject(columnNumber);
    if (v != null) {
      if (v instanceof Boolean) {
        return (byte)(((Boolean)v).booleanValue() ? 1 : 0);
      }
      else if (v instanceof Number) {
        return ((Number)v).byteValue();
      }
      else {
        return super.getByte(columnNumber);
      }
    }
    else {
      return 0;
    }
  }

  /**
   * The actual implementation of the {@link #getObject(int)} method required to
   * be implemented by sub-classes.
   */
  protected abstract Object getObjectForColumn(int columnNumber)
      throws SQLException;

  /**
   * @see java.sql.ResultSet#wasNull
   */
  @Override
  public final boolean wasNull() {
    return this.wasNull;
  }

  /**
   * @see java.sql.ResultSet#close
   */
  public void close() throws SQLException {
    this.wasNull = false;
  }

  /**
   * Return a type conversion exception from this type to another.
   */
  protected final SQLException dataTypeConversion(String targetType,
      ResultColumnDescriptor rcd) {
    return Util.generateCsSQLException(StandardException.newException(
        SQLState.LANG_DATA_TYPE_GET_MISMATCH,
        targetType, rcd.getType().getTypeName(), rcd.getName()));
  }

  /** VTI costing interface */

  /**
   * @see VTICosting#getEstimatedRowCount
   */
  public double getEstimatedRowCount(VTIEnvironment vtiEnvironment)
      throws SQLException {
    return VTICosting.defaultEstimatedRowCount;
  }

  /**
   * @see VTICosting#getEstimatedCostPerInstantiation
   */
  public double getEstimatedCostPerInstantiation(VTIEnvironment vtiEnvironment)
      throws SQLException {
    return VTICosting.defaultEstimatedCost;
  }

  /**
   * @return false
   * @see VTICosting#supportsMultipleInstantiations
   */
  public boolean supportsMultipleInstantiations(VTIEnvironment vtiEnvironment)
      throws SQLException {
    return true;
  }

  /**
   * Simple result collector for member results that will expect a single result
   * from each member that will be added to the map.
   * 
   * @author swale
   */
  public static class MemberSingleResultCollector<T> extends
      TreeMap<DistributedMember, T> implements
      ResultCollector<Object, TreeMap<DistributedMember, T>> {

    private static final long serialVersionUID = 1996707111592217057L;

    /** default constructor */
    public MemberSingleResultCollector() {
    }

    @SuppressWarnings("unchecked")
    public void addResult(DistributedMember memberId,
        Object resultOfSingleExecution) {
      put(memberId, (T)resultOfSingleExecution);
    }

    public TreeMap<DistributedMember, T> getResult() throws FunctionException {
      return this;
    }

    public TreeMap<DistributedMember, T> getResult(long timeout, TimeUnit unit)
        throws FunctionException, InterruptedException {
      throw new AssertionError(
          "getResult with timeout not expected to be invoked for GemFireXD");
    }

    public void clearResults() {
      clear();
    }

    public void endResults() {
      // nothing to be done
    }
  }
}
