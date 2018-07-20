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
package com.pivotal.gemfirexd.internal.engine.ddl;

import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.compile.AlterTableNode;
import com.pivotal.gemfirexd.internal.impl.sql.execute.AlterTableConstantAction;

/**
 * An GfxdAlterTableNode is the root of a QueryTree that represents an ALTER
 * TABLE statement with GemFireXD extensions.
 * 
 * @author Sumedh Wale
 * @since 6.0
 */
public class GfxdAlterTableNode extends AlterTableNode {

  /**
   * the {@link GfxdAttributesMutator} object that represents the changes
   * required to the underlying GemFire region
   */
  private GfxdAttributesMutator mutator;

  /**
   * Initializer for a GfxdAlterTableNode
   * 
   * @param objectName
   *          The name of the table being altered
   * @param tableElementList
   *          The alter table action
   * @param mutator
   *          The {@link GfxdAttributesMutator} object containing the changes
   *          for underlying GemFire region
   * @param lockGranularity
   *          The new lock granularity, if any
   * @param changeType
   *          ADD_TYPE or DROP_TYPE
   * 
   * @exception StandardException
   *              Thrown on error
   */

  public void init(Object objectName, Object tableElementList, Object mutator,
      Object lockGranularity, Object changeType, Object isSet,
      Object behavior, Object sequential, Object rowLevelSecurity ) throws StandardException {
    super.init(objectName, tableElementList, lockGranularity, changeType,
        isSet, behavior, sequential, rowLevelSecurity);
    this.mutator = (GfxdAttributesMutator)mutator;
  }

  /**
   * Convert this object to a String. See comments in QueryTreeNode.java for how
   * this should be done for tree printing.
   * 
   * @return This object as a String
   */
  public String toString() {
    if (SanityManager.DEBUG) {
      return super.toString() + "attributesMutator: " + "\n" + this.mutator
          + "\n";
    }
    else {
      return "";
    }
  }

  /**
   * Create the Constant information that will drive the guts of Execution.
   * 
   * @exception StandardException
   *              Thrown on failure
   */
  public ConstantAction makeConstantAction() throws StandardException {
    AlterTableConstantAction action = (AlterTableConstantAction)super
        .makeConstantAction();
    action.setAttributesMutator(mutator);
    return action;
  }

}
