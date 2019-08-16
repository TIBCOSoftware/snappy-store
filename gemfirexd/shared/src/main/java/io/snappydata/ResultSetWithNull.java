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

package io.snappydata;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Extends {@link ResultSet} with a pre-operation "isNull" method unlike
 * "wasNull" that can be invoked only after reading the value.
 */
public interface ResultSetWithNull extends ResultSet {

  /**
   * Return whether the current value at given index is null or not
   * even before invoking any getter method.
   */
  boolean isNull(int columnIndex) throws SQLException;
}
