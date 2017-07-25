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

import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

/**
 * 
 * Concrete clas representing the parameterized Primary Key (Single Key)
 * 
 * @author Asif
 * @since Sql Fabric
 * @see DynamicKey
 * @see CompositeDynamicKey
 * 
 */
public class PrimaryDynamicKey implements DynamicKey {

  ValueQueryInfo vqi;

  public PrimaryDynamicKey(ValueQueryInfo dpk) {
    vqi = dpk;
  }

  @Override
  public RegionKey getEvaluatedPrimaryKey(Activation activation,
      GemFireContainer container, boolean doClone) throws StandardException {
    return GemFireXDUtils.convertIntoGemfireRegionKey(vqi
        .evaluateToGetDataValueDescriptor(activation), container, doClone);
  }
  
  @Override
  public DataValueDescriptor getEvaluatedIndexKey(Activation activation) throws StandardException {
    return getEvaluatedAsDataValueDescriptor(activation);
  }

  /**
   * Returns the object present in the Parameter wrapped by a
   * DataValueDescriptor. It is invoked from CompositeDynamicKey
   * {@link CompositeDynamicKey#getEvaluatedPrimaryKey}
   * 
   * @param activation
   * @return
   * @throws StandardException
   */
  public DataValueDescriptor getEvaluatedAsDataValueDescriptor(
      Activation activation) throws StandardException {
    return vqi.evaluateToGetDataValueDescriptor(activation);
  }
}
