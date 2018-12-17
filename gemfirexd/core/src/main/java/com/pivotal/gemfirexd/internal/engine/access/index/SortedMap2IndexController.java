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

package com.pivotal.gemfirexd.internal.engine.access.index;

import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.operations.SortedMap2IndexInsertOperation;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

/**
 */
final class SortedMap2IndexController extends MemIndexController {

  @Override
  public int getType() {
    return MemConglomerate.SORTEDMAP2INDEX;
  }

  @Override
  public String toString() {
    return "SortedMap2IndexController, conglom: " + this.open_conglom;
  }

  /**
   * Puts the row into the skiplist.
   * 
   * @return Returns 0 if insert succeeded. Returns
   *         ConglomerateController.ROWISDUPLICATE if conglomerate supports
   *         uniqueness checks and has been created to disallow duplicates, and
   *         the row inserted had key columns which were duplicate of a row
   *         already in the table. Other insert failures will raise
   *         StandardExceptions.
   * 
   * @param row
   *          The row to insert.
   * 
   * @exception StandardException
   *              Standard exception policy.
   */
  @Override
  protected int doInsert(DataValueDescriptor[] row) throws StandardException {
    // make a copy for the key since the row may be reused by higher level
    Object key = this.open_conglom.getKey(row, true, true);
    // clone the RowLocation since higher level uses the same RowLocation
    // and we now use this itself for storage (no ExecRowLocation used for
    // carrying local index RowLocations)
    RowLocation rowLocation = this.open_conglom.getValue(row);
    assert rowLocation != DataValueFactory.DUMMY;
    try {
      final GemFireTransaction tran = this.open_conglom.getTransaction();
      final LanguageConnectionContext lcc = tran.getLanguageConnectionContext();
      final boolean skipConstraintChecks = lcc != null && lcc.isSkipConstraintChecks();
      final GemFireContainer container = this.open_conglom
          .getGemFireContainer();
      // this path is only taken for DataDictionary ops, so
      // isPutDML/skipConstraintChecks are always false
      SortedMap2IndexInsertOperation.doMe(tran,
          container.getActiveTXState(tran), container, key, rowLocation,
          this.open_conglom.isUnique(), null,
          false /* isPutDML */, skipConstraintChecks);
    } catch (StandardException se) {
      if (SQLState.LANG_DUPLICATE_KEY_CONSTRAINT.equals(se.getMessageId())) {
        // for system tables return back ROWISDUPLICATE else throw back the
        // original exception since it will have more information (#42504)
        if (!this.open_conglom.getBaseContainer()
            .isApplicationTableOrGlobalIndex()) {
          return ConglomerateController.ROWISDUPLICATE;
        }
      }
      throw se;
    }
    return 0;
  }
}
