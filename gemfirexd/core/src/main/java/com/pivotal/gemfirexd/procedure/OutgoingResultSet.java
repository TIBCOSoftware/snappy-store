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
package com.pivotal.gemfirexd.procedure;

import java.util.List;

/**
 * An interface for constructing an outgoing result set by adding rows as
 * List&lt;Object&gt;.
 *
 * {@link #addColumn} is used for specifying the column names for this result
 * set. If {@link #addRow} is called before {@link #addColumn}, then
 * the column names will default to "c1", "c2", etc.
 *
 * The types of the columns will always be {@link java.sql.Types#JAVA_OBJECT}.
 *
 * Once {{addRow}} has been called, an invocation of {{addColumn}} will throw
 * an IllegalStateException.
 */
public interface OutgoingResultSet {

  /**
   * The default number of rows batched up before being flushed as a message to
   * the originating node.
   */
  int DEFAULT_BATCH_SIZE = 100;

  /**
   * Specify the name of the next column for this results set.
   * The names for all the columns should be specified before {@link #addRow}
   * is called, unless the default names are desired.
   * Default column names will be used for any columns that have not been
   * named by calling this method: "c1", "c2", etc.
   *
   * @param name the name of the column
   * @throws IllegalStateException if rows have already been added when
   *                               this method is called.
   */
  void addColumn(String name);

  /**
   * Add a row to this result set, to be sent to the
   * ResultCollector. The column descriptions should be added
   * with {@link #addColumn} before this method is called,
   * otherwise default column descriptions will be inferred
   * based on the first row added with columns named "c1", "c2", etc.
   *
   * @param row a List&lt;Object&gt; for this row. Each element will be
   *            converted to the corresponding SQL type.
   * @throws IllegalArgumentException if the row is not of the correct length
   *         based on previous calls to addColumn.
   */
  void addRow(List<Object> row);

  /**
   * Invoke this method to let the result set know that there are no more rows
   * to be added.
   */
  void endResults();

  /**
   * Get the number of rows batched up before being flushed as a message to the
   * originating node. If not set explicitly for this result set using
   * {@link #setBatchSize(int)} then default is {@link #DEFAULT_BATCH_SIZE}.
   */
  int getBatchSize();

  /**
   * Set the number of rows to be batched up before being flushed as a message
   * to the originating node.
   */
  void setBatchSize(int size);
}
