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
package quickstart;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;

/**
 * MyArrayListResultCollector gathers result from all the function execution
 * nodes.
 * <p>
 * Using a custom ResultCollector a user can sort/aggregate the result. This
 * implementation stores the result in a List. The size of the list will be 
 * same as the no of nodes on which a function got executed
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.0
 */
public class MyArrayListResultCollector implements ResultCollector<Object, Object> {

  final ArrayList<Object> result = new ArrayList<Object>();

  /**
   * Adds a single function execution result from a remote node to the
   * ResultCollector
   */
  @Override
  public void addResult(DistributedMember memberID, Object resultOfSingleExecution) {
    this.result.add(resultOfSingleExecution);
  }

  /**
   * Waits if necessary for the computation to complete, and then retrieves its
   * result.
   * <p>
   * If {@link Function#hasResult()} is false, upon calling
   * {@link ResultCollector#getResult()} throws {@link FunctionException}.
   * <p>
   * 
   * @return the computed result
   * @throws FunctionException if something goes wrong while retrieving the result
   */
  @Override
  public Object getResult() throws FunctionException {
    return this.result;
  }

  /**
   * Waits if necessary for at most the given time for the computation to
   * complete, and then retrieves its result, if available.
   * <p>
   * If {@link Function#hasResult()} is false, upon calling
   * {@link ResultCollector#getResult()} throws {@link FunctionException}.
   * <p>
   * 
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return computed result
   * @throws FunctionException if something goes wrong while retrieving the result
   */
  @Override
  public Object getResult(long timeout, TimeUnit unit) throws FunctionException, InterruptedException {
    return this.result;
  }

  /**
   * GemFire will invoke this method before re-executing function (in case of
   * Function Execution HA) This is to clear the previous execution results from
   * the result collector
   * 
   * @since 6.3
   */
  @Override
  public void clearResults() {
    result.clear();
  }
  
  /**
   * Call back provided to caller, which is called after function execution is
   * complete and caller can retrieve results using
   * {@link ResultCollector#getResult()}
   */
  @Override
  public void endResults() {
    // do nothing
  }
}
