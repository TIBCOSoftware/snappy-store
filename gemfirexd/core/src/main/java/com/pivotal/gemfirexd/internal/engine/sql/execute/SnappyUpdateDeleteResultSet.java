/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.sql.execute;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLInteger;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;

public final class SnappyUpdateDeleteResultSet extends SnappySelectResultSet {

  private boolean firstAccess = true;

  public SnappyUpdateDeleteResultSet(Activation ac, boolean returnRows) {
    super(ac, returnRows);
  }

  public ExecRow getNextRow() throws StandardException {
    try {
      if (firstAccess) {
        int sum = 0;
        ExecRow superRow;
        while ((superRow = super.getNextRow()) != null) {
          DataValueDescriptor dvd = superRow.getLastColumn();
          sum += dvd.getInt();
        }
        ValueRow vrow = new ValueRow(1);
        vrow.setColumn(1, new SQLInteger(sum));
        this.setCurrentRow(vrow);

        return vrow;
      } else {
        return null;
      }
    } catch(Exception ex) {
      throw Misc.processFunctionException("SnappyUpdateDeleteResultSet:getNextRow ", ex, null,
          null);
    } finally {
      firstAccess = false;
    }
  }
}
