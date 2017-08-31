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
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.common;

import io.snappydata.thrift.PrepareResult;
import io.snappydata.thrift.StatementAttrs;

/**
 * Describes a holder for thrift's <code>PrepareResult</code> typically a
 * client-side prepared statement.
 */
public interface PrepareResultHolder {

  /**
   * the SQL string that was prepared
   */
  String getSQL();

  /**
   * the statement ID assigned by the current server
   */
  long getStatementId();

  /**
   * update the {@link PrepareResult} e.g. after a failover
   */
  void updatePrepareResult(PrepareResult pr);

  /**
   * return the statement attributes used when preparing the statement
   */
  StatementAttrs getAttributes();
}
