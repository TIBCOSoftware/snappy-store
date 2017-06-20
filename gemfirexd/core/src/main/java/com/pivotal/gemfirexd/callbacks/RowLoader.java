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

package com.pivotal.gemfirexd.callbacks;

import java.sql.SQLException;

/**
 * This interface should be implemented by the user to insert an entire row. If
 * there is a select on the primary key and the fabric database does not have
 * the corresponding row in the table then it will call the load method of the
 * implementation of this interface to obtain the row corresponding to that
 * primary key.
 * 
 * @author Kumar Neeraj
 */
public interface RowLoader {
  /**
   * 
   * @param schemaName Name of the schema.
   * @param tableName Name of the table.
   * @param primarykey The primary key as Object[] as multiple column can make up
   *            the primary key.
   * @return The values of the columns as either a List&lt;Object&gt; or a
   *         {@link java.sql.ResultSet}. The order of values should be in the
   *         same order as the order of columns in the table. If a <code>
   *         ResultSet</code> is returned, then only the first row will be
   *         read from it.
   */
  public Object getRow(String schemaName, String tableName, Object[] primarykey)
      throws SQLException;

  /**
   * This method is called to initialize the RowLoader with the parameters
   * passed for the loader in the create table ddl.
   * 
   * @param initStr is the string passed to SYS.ATTACH_LOADER procedure
   */
  public void init(String initStr) throws SQLException;
}
