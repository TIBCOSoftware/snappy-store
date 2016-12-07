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
package com.pivotal.gemfirexd.internal.engine.distributed;

import java.util.Properties;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.snappy.CallbackFactoryProvider;

/**
 * This class is used to distribute connection IDs ( long []) which have been
 * closed, to the other nodes, so that the conenctions if present in the
 * GfxdConnectionHolder, could be closed.
 * 
 * @author Asif
 */
@SuppressWarnings("serial")
public final class DistributedConnectionCloseExecutorFunction implements
    Function, Declarable {

  public final static String ID =
    "gfxd-DistributedConnectionCloseExecutorFunction";

  /**
   * Added for tests that use XML for comparison of region attributes.
   * 
   * @see Declarable#init(Properties)
   */
  @Override
  public void init(Properties props) {
    // nothing required for this function
  }

  public void execute(FunctionContext fc) {
    final long[] connIds = (long[])fc.getArguments();
    final GfxdConnectionHolder connHolder = GfxdConnectionHolder.getHolder();
    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();
    if (observer != null) {
      observer.beforeConnectionCloseByExecutorFunction(connIds);
    }
    for (long id : connIds) {
      closeConnection(id, connHolder);
    }
    if (observer != null) {
      observer.afterConnectionCloseByExecutorFunction(connIds);
    }
  }

  public static void closeConnection(final long id,
      final GfxdConnectionHolder connHolder) {
    final Long connId = id;
    final GfxdConnectionWrapper wrapper = connHolder.removeWrapper(connId);
    if (wrapper != null) {
      // no need to synchronize since the wrapper has been removed from map
      wrapper.close(true, true);
    }

    //TODO - Check only if the product is Snappy then call the remove connection ref messages
    if (Misc.getMemStore().isSnappyStore()) {
      CallbackFactoryProvider.getClusterCallbacks()
          .clearSnappySessionForConnection(connId);
    }
  }

  public String getId() {
    return DistributedConnectionCloseExecutorFunction.ID;
  }

  public boolean hasResult() {
    return false;
  }

  public boolean optimizeForWrite() {
    return false;
  }

  public boolean isHA() {
    return false;
  }
}
