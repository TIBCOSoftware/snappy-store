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

import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.sql.ResultSetMetaData;

/**
 * An interface for retrieving rows as List&lt;Object&gt; from an incoming
 * result set.
 *
 * Uses blocking, balking, and timeout semantics in the style of a
 * BlockingQueue.
 * 
 * @see java.util.concurrent.BlockingQueue
 */

public interface IncomingResultSet {
  
  /**
   * A zero-length row when returned indicates that there are no more
   * results in this result set.
   */
  static final List<Object> END_OF_RESULTS = new ArrayList<Object>();
  
  /**
   * Retrieve and removes the head row in this result set as a List&lt;Object&gt;,
   * waiting if necessary until an element becomes available, or returning
   * END_OF_RESULTS if there are no more rows in this result set.
   *
   * @return the head row of this result set, or returns END_OF_RESULTS
   * if there are no more rows in this result set.
   * @throws InterruptedException if interrupted while waiting   
   */
  List<Object> takeRow() throws InterruptedException;
  
  /**
   * Retrieves and removes the head row of this result set,
   * returns <tt>null</tt> if there is no row currently available in this result
   * set, or returns END_OF_RESULTS if there are no more rows in this
   * result set.
   *
   * @return the head row of this result set if it is available, <tt>null</tt>
   *         if there is no row in this result set currently available,
   *         or END_OF_RESULTS if there are no more rows in this result set.
   */
  List<Object> pollRow();

  /**
   * Retrieves and removes the head row of this result set,
   * waiting up to the specified wait time if necessary for a row to become
   * available, or returns END_OF_RESULTS if there are no more rows in this
   * result set.
   *
   * @param timeout how long to wait before giving up, in units of <tt>unit</tt>
   * @param unit a TimeUnit determining how to interpret the timeout parameter
   * @return the head row of this queue, <tt>null</tt> if the specified waiting time
   *         elapses before an element is available, or END_OF_RESULTS if there
   *         are no more rows in this result set.
   * @throws InterruptedException if interrupted while waiting   
   */
  List<Object> pollRow(long timeout, TimeUnit unit)
  throws InterruptedException;
  
  /**
   * Retrieves, <em>but does not remove</em>, the head row of this result set,
   * waiting up to the specified wait time if necessary for a row to become
   * available, or returns END_OF_RESULTS if there are no more rows in this
   * result set.
   *
   * @param timeout how long to wait before giving up, in units of <tt>unit</tt>
   * @param unit a TimeUnit determining how to interpret the timeout parameter
   * @return the head row of this queue, <tt>null</tt> if the specified waiting time
   *         elapses before an element is available, or END_OF_RESULTS if there
   *         are no more rows in this result set.
   * @throws InterruptedException if interrupted while waiting   
   */
  List<Object> peekRow(long timeout, TimeUnit unit)
  throws InterruptedException;
  
  /**
   * Retrieves, <em>but does not remove</em>, the head row of this result set,
   * returns <tt>null</tt> if there is no row currently available in this result
   * set, or returns END_OF_RESULTS if there are no more rows in this
   * result set.
   *
   * @return the head row of this result set if it is available, <tt>null</tt>
   *         if there is no row in this result set currently available,
   *         or END_OF_RESULTS if there are no more rows in this result set.
   */
  List<Object> peekRow();
  
  /**
   * Retrieves, <em>but does not remove</em>, the head row of this result set,
   * waiting if necessary until an element becomes available, or returning
   * END_OF_RESULTS if there are no more rows in this result set.
   *
   * @return the head row of this result set, or returns END_OF_RESULTS
   * if there are no more rows in this result set.
   * 
   * @throws InterruptedException if interrupted while waiting   
   */
  List<Object> waitPeekRow()
  throws InterruptedException;
  
  /**
   * Return metadata information about the columns in this result set.
   * May block until the metadata is available.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  ResultSetMetaData getMetaData()
  throws InterruptedException;
}
