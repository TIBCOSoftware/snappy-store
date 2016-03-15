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

import com.gemstone.gemfire.cache.execute.FunctionAdapter;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.RegionFunctionContext;

/**
 * A non-data aware function to retrieve the amount of free Java heap memory on 
 * the server, in MB.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 8.0
 */
public class ServerFreeMemFunction extends FunctionAdapter {

  private static final long serialVersionUID = 2908666709157111340L;

  @Override
  public void execute(FunctionContext fc) {
    if (fc instanceof RegionFunctionContext) {
      throw new FunctionException("This is a non-data aware function, and has to be called using FunctionService.onServer.");
    }
    
    fc.getResultSender().lastResult(Runtime.getRuntime().freeMemory()/(1024*1024));
  }

  @Override
  public String getId() {
    return getClass().getName();
  }
}
