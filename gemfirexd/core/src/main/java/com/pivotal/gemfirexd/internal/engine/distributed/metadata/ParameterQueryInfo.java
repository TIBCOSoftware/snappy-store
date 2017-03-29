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
package com.pivotal.gemfirexd.internal.engine.distributed.metadata;

import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.ColumnRoutingObjectInfo;

/**
 * The instance of this class represents a parameterized value 
 * A node of this type gets created when query is executed using
 *  PreparedStatement 
 *   
 * @since GemFireXD
 * @author Asif
 *
 *
 */
public final class ParameterQueryInfo extends AbstractQueryInfo implements
    ValueQueryInfo {

  private final int paramIndex;

  private int maxwidth;

  public ParameterQueryInfo(int index) {
    this.paramIndex = index;
  }

  @Override
  public boolean isDynamic() {
    return true;
  }
 
  
  @Override
  public Object getPrimaryKey() {
    return new PrimaryDynamicKey(this);
  }
  
  @Override
  public Object getIndexKey() {
    return new PrimaryDynamicKey(this);
  }

//  int getIndex() {
//    return this.paramIndex;
//  }
  
  /** This class implements ValueQueryInfo interface which contains this method.
   * Both ConstantQueryInfo{@link ConstantQueryInfo#evaluate(Activation)} and ParameterQueryInfo implement this interface. The primary
   * reason for this is to distinguish those QueryInfo objects which evaluate to a yield
   * an underlying value ( of constant nature) . It helps avoid instanceof check &
   * extra type casting 
   * 
   */
  public Object evaluate(Activation activation) throws StandardException {
    ParameterValueSet pvs = activation.getParameterValueSet();
    return pvs.getParameter(this.paramIndex).getObject();
  }
 
  /**
   * This function provides the underlying DataValueDescriptor for the
   * parameter. It is used to create GemFireKey in the PrimaryDynamicKey
   * {@link PrimaryDynamicKey#getEvaluatedPrimaryKeyAsDataValueDescriptor(Activation)}.
   * The DataValueDescriptor holds the actual value.
   * 
   * @param activation
   * @return
   * @throws StandardException
   */
  public DataValueDescriptor evaluateToGetDataValueDescriptor(
      Activation activation) throws StandardException {
    ParameterValueSet pvs = activation.getParameterValueSet();
    return pvs.getParameter(this.paramIndex);
  }

  public DataValueDescriptor[] evaluateToGetDataValueDescriptorArray(
      Activation activation) throws StandardException {
    throw new UnsupportedOperationException(
        "This method is not supported . Invoke evaluateToGetDataValueDescriptor(Activation) instead.");
  }

  public int typeOfValue() {
    return ValueQueryInfo.PARAMETER_VALUE;
  }

  public int evaluateToGetParameterNumber(Activation activation)
      throws StandardException {
    return this.paramIndex;
  }

  public int[] evaluateToGetParameterArray(Activation activation)
      throws StandardException {
    throw new UnsupportedOperationException(
        "evaluateToGetParameterArray method is not supported . "
            + "Invoke evaluateToGetParameterNumber(Activation) instead.");
  }

  public DataValueDescriptor evaluateToGetConstant(Activation activation)
      throws StandardException {
    throw new UnsupportedOperationException(
        "evaluateToGetConstant should not be called for ParameterQueryInfo");
  }

  public DataValueDescriptor[] evaluateToGetConstantArray(Activation activation)
      throws StandardException {
    throw new UnsupportedOperationException(
        "evaluateToGetConstantArray should not be called for ParameterQueryInfo");
  }

  public Object[] evaluateToGetObjectArray(Activation activation)
      throws StandardException {
    throw new UnsupportedOperationException(
        "evaluateToGetObjectArray should not be called for ParameterQueryInfo");
  }

  public int getParamType(Activation activation) throws StandardException {
    activation.getParameterValueSet();
    ExecPreparedStatement ps = activation.getPreparedStatement();
    DataTypeDescriptor dtd = ps.getParameterTypes()[this.paramIndex];
    return dtd.getDVDTypeFormatId();
  }

  public ColumnRoutingObjectInfo[] evaluateToGetColumnInfos(
      Activation activation) {
    // TODO Auto-generated method stub
    return null;
  }

  public void setMaximumWidth(int length) {
    this.maxwidth = length;
  }

  public int getMaximumWidth() {
    return this.maxwidth;
  }

  @Override
  public int hashCode() {
    return this.paramIndex * 3; // avoid clashes with integers
  }

  @Override
  public boolean equals(Object other) {
    if (other != null && other.getClass() == ParameterQueryInfo.class) {
      return this.paramIndex == ((ParameterQueryInfo)other).paramIndex;
    }
    return false;
  }

  public int getParamIndex(){
    return this.paramIndex;
  }
}
