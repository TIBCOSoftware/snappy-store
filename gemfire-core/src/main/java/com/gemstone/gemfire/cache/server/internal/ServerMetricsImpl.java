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
package com.gemstone.gemfire.cache.server.internal;

import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.server.ServerMetrics;

/**
 * Metrics describing the load on a  bridge server.
 * @author dsmith
 * @since 5.7
 *
 */
public class ServerMetricsImpl implements ServerMetrics {
  private final AtomicInteger clientCount = new AtomicInteger();
  private final AtomicInteger connectionCount = new AtomicInteger();
  private final AtomicInteger queueCount = new AtomicInteger();
  private final int maxConnections;
  
  public ServerMetricsImpl(int maxConnections) {
    this.maxConnections = maxConnections;
  }

  public int getClientCount() {
    return clientCount.get();
  }

  public int getConnectionCount() {
    return connectionCount.get();
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  public int getSubscriptionConnectionCount() {
    return queueCount.get();
  }
  
  public void incClientCount() {
    clientCount.incrementAndGet();
  }
  
  public void decClientCount() {
    clientCount.decrementAndGet();
  }
  
  public void incConnectionCount() {
    connectionCount.incrementAndGet();
  }
  
  public void decConnectionCount() {
    connectionCount.decrementAndGet();
  }
  
  public void incQueueCount() {
    queueCount.incrementAndGet();
  }
  
  public void decQueueCount() {
    queueCount.decrementAndGet();
  }
  
}
