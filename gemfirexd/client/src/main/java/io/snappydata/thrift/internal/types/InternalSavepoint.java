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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.internal.types;

import java.sql.SQLException;
import java.sql.Savepoint;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.internal.ClientConnection;

/**
 * Simple implementation of {@link Savepoint} used to store the name/ID of the
 * savepoint.
 */
public class InternalSavepoint implements Savepoint {

  private final ClientConnection conn;
  private final int savepointId;
  private final String savepointName;

  // generated name prefix used internally for unnamed savepoints
  public static final String GENERATED_SAVEPOINT_NAME_PREFIX =
      "__SNAPPY_SAVEPOINT_GENENERATED_NAME_";

  public InternalSavepoint(ClientConnection conn, String savepointName) {
    this.conn = conn;
    this.savepointName = savepointName;
    this.savepointId = 0;
  }

  public InternalSavepoint(ClientConnection conn, int savepointId) {
    this.conn = conn;
    this.savepointId = savepointId;
    this.savepointName = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getSavepointId() throws SQLException {
    if (this.savepointId != 0) {
      return savepointId;
    }
    else {
      throw ThriftExceptionUtil
          .newSQLException(SQLState.NO_ID_FOR_NAMED_SAVEPOINT);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSavepointName() throws SQLException {
    if (this.savepointName != null) {
      return this.savepointName;
    }
    else {
      throw ThriftExceptionUtil
          .newSQLException(SQLState.NO_NAME_FOR_UNNAMED_SAVEPOINT);
    }
  }

  public String getRealSavepointName() {
    return (this.savepointName != null ? this.savepointName
        // generate the name for an un-named savepoint.
        : GENERATED_SAVEPOINT_NAME_PREFIX + this.savepointId);
  }

  public final ClientConnection getConnection() {
    return this.conn;
  }
}
