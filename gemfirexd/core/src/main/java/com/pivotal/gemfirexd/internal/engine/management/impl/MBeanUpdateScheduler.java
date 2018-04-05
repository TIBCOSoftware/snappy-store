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
package com.pivotal.gemfirexd.internal.engine.management.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.internal.engine.Misc;


/**
 *
 * @author Abhishek Chaudhari
 * @since gfxd 1.0
 */
public class MBeanUpdateScheduler {
  private Map<String, ScheduledFuture<?>> registeredTasks = new ConcurrentHashMap<String, ScheduledFuture<?>>();

  private ScheduledExecutorService service;

  private int updateRate;

  public MBeanUpdateScheduler() {
    this.updateRate = getConfiguredUpdateRate();
    this.service    = Executors.newScheduledThreadPool(1, new MBeanUpdaterThreadFactory());
  }

  static int getConfiguredUpdateRate() {
    int configuredUpdateRate = 0;
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache != null) {
      configuredUpdateRate = cache.getDistributedSystem().getConfig().getJmxManagerUpdateRate();
    }

    return configuredUpdateRate > 0 ? configuredUpdateRate : ManagementUtils.DEFAULT_MBEAN_UPDATE_RATE_MILLIS;
  }

  // NOTE: Currently only for testing
  public int getUpdateRate() {
    return updateRate;
  }

  public void scheduleTaskWithFixedDelay(Runnable r, String serviceName, long initialDelay) {
    ScheduledFuture<?> scheduledFuture = this.service.scheduleWithFixedDelay(r, initialDelay, this.updateRate, TimeUnit.MILLISECONDS);
    this.registeredTasks.put(serviceName, scheduledFuture);
  }

  public void scheduleTaskAtFixedRate(Runnable r, String serviceName, long initialDelay) {
    ScheduledFuture<?> scheduledFuture = this.service.scheduleAtFixedRate(r, initialDelay, this.updateRate, TimeUnit.MILLISECONDS);
    this.registeredTasks.put(serviceName, scheduledFuture);
  }

  public void stopTask(String serviceName) {
    ScheduledFuture<?> scheduledFuture = this.registeredTasks.get(serviceName);
    if (scheduledFuture != null) {
      scheduledFuture.cancel(true);
      this.registeredTasks.remove(serviceName);
    }
  }

  public void stopAllTasks() {
    this.registeredTasks.clear();
    this.service.shutdownNow();
    this.service = null;
    this.registeredTasks = null;
  }

  private static class MBeanUpdaterThreadFactory implements ThreadFactory {
    private static final String THREAD_GROUP_NAME = "GemFireXD MBean Updater Threads ";
    private ThreadGroup mbeanUpdaterThreads;

    private AtomicInteger ai = new AtomicInteger();

    public MBeanUpdaterThreadFactory() {
      this.mbeanUpdaterThreads = new ThreadGroup(THREAD_GROUP_NAME);
//      mbeanUpdaterThreads.setDaemon(true);
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread newThread = new Thread(this.mbeanUpdaterThreads, r, THREAD_GROUP_NAME + ai.getAndIncrement());
      newThread.setDaemon(true);
      return newThread;
    }
  }

  @Override
  public String toString() {
    return MBeanUpdateScheduler.class.getSimpleName() + " [registeredTasks=" + registeredTasks + ", service="+ service + "]";
  }
}
