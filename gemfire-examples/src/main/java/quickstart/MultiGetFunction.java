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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.cache.execute.FunctionAdapter;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.RegionFunctionContext;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;

/**
 * Application Function to retrieve values for multiple keys in a region.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.0
 */
public class MultiGetFunction extends FunctionAdapter {

  private static final long serialVersionUID = 7674120689730244854L;

  @Override
  public void execute(FunctionContext fc) {
    if (!(fc instanceof RegionFunctionContext)){
      throw new FunctionException("This is a data aware function, and has to be called using FunctionService.onRegion.");
    }
    RegionFunctionContext context = (RegionFunctionContext)fc;
    Set<String> keys = (Set<String>)context.getFilter();
    Set<String> keysTillSecondLast = new HashSet<String>();
    int setSize = keys.size();
    Iterator<String> keysIterator = keys.iterator();
    for (int i = 0; i < (setSize - 1); i++) {
      keysTillSecondLast.add(keysIterator.next());
    }
    for (Object k : keysTillSecondLast) {
      context.getResultSender().sendResult(
          PartitionRegionHelper.getLocalDataForContext(context).get(k));
    }
    Object lastResult = keysIterator.next();
    context.getResultSender().lastResult(
        PartitionRegionHelper.getLocalDataForContext(context).get(lastResult));
  }

  @Override
  public String getId() {
    return getClass().getName();
  }
}
