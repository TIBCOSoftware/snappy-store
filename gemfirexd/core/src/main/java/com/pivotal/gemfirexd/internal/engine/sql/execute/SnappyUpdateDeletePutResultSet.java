/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;

public final class SnappyUpdateDeletePutResultSet extends SnappySelectResultSet {

  private int rowCount = -1;

  SnappyUpdateDeletePutResultSet(Activation ac, boolean returnRows) {
    super(ac, returnRows);
  }

  @Override
  public boolean returnsRows() {
    return false;
  }

  @Override
  public int modifiedRowCount() {
    if (rowCount < 0) {
      rowCount = 0;
      try {
        ExecRow row;
        while ((row = getNextRow()) != null && row.nColumns() > 0) {
          rowCount += row.getLastColumn().getInt();
        }
      } catch (Exception ex) {
        throw GemFireXDRuntimeException.newRuntimeException("Update or Delete or Put on Snappy", ex);
      }
    }
    Misc.getCacheLogWriter().info("SnappyUpdateDeleteResultSet:::modifiedRowCount" + rowCount);
    return rowCount;
  }
}
