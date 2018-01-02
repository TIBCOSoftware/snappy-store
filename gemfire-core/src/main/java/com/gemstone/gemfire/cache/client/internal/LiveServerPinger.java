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
package com.gemstone.gemfire.cache.client.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.client.internal.EndpointManager.EndpointListenerAdapter;
import com.gemstone.gemfire.cache.client.internal.PoolImpl.PoolTask;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;

/**
 * Responsible for pinging live
 * servers to make sure they
 * are still alive.
 * @author dsmith
 *
 */
public class LiveServerPinger  extends EndpointListenerAdapter {
  private static final long NANOS_PER_MS = 1000000L;

  private final ConcurrentHashMap<Endpoint, Future> taskFutures =
      new ConcurrentHashMap<>();
  protected final InternalPool pool;
  protected final long pingIntervalNanos;
  
  public LiveServerPinger(InternalPool pool) {
    this.pool = pool;
    this.pingIntervalNanos = pool.getPingInterval() * NANOS_PER_MS;
  }

  @Override
  public void endpointCrashed(Endpoint endpoint) {
    cancelFuture(endpoint);
  }

  @Override
  public void endpointNoLongerInUse(Endpoint endpoint) {
    cancelFuture(endpoint);
  }

  @Override
  public void endpointNowInUse(Endpoint endpoint) {
    try {
      Future future = pool.getBackgroundProcessor().scheduleWithFixedDelay(
          new PingTask(endpoint), pingIntervalNanos, pingIntervalNanos,
          TimeUnit.NANOSECONDS);
      taskFutures.put(endpoint, future);
    } catch (RejectedExecutionException e) {
      if (pool.getCancelCriterion().cancelInProgress() == null) {
        throw e;
      }
    }
  }
  
  private void cancelFuture(Endpoint endpoint) {
    Future future = taskFutures.remove(endpoint);
    if(future != null) {
      future.cancel(false);
    }
  }
  
  private class PingTask extends PoolTask {
    private final Endpoint endpoint;
    
    public PingTask(Endpoint endpoint) {
      this.endpoint = endpoint;
    }

    @Override
    public LogWriterI18n getLogger() {
      return pool.getLoggerI18n();
    }

    @Override
    public void run2() {
      if(endpoint.timeToPing(pingIntervalNanos)) {
//      logger.fine("DEBUG pinging " + server);
        try {
          PingOp.execute(pool, endpoint.getLocation());
        } catch(Exception e) {
          if(getLogger().fineEnabled()) {
            getLogger().fine("Error occured while pinging server: " + endpoint.getLocation() + " - " + e.getMessage());
          }
          GemFireCacheImpl cache = GemFireCacheImpl.getInstance();          
          if (cache != null) {
            ClientMetadataService cms = cache.getClientMetadataService();
            cms.removeBucketServerLocation(endpoint.getLocation());
          }        
          //any failure to ping the server should be considered a crash (eg.
          //socket timeout exception, security exception, failure to connect).
          pool.getEndpointManager().serverCrashed(endpoint);
        }
      } else {
//      logger.fine("DEBUG skipping ping of " + server
//      + " because lastAccessed=" + endpoint.getLastAccessed());
      }
    }
  }
  
}
