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
package com.gemstone.gemfire.internal.cache;

import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
/*
 * Purpose of this class to create threadfactory and other helper classes for GemfireCache.
 * If we keep these classes as inner class of GemFireCache then some time it holds reference of static cache
 * Which can cause leak if app is just  starting and closing cache 
 */
public class GemfireCacheHelper {

  public static ThreadFactory createThreadFactory(final ThreadGroup tg, final String threadName) {
    final ThreadFactory threadFactory = new ThreadFactory() {
      public Thread newThread(Runnable command) {
        Thread thread = new Thread(tg, command, threadName);
        thread.setDaemon(true);
        return thread;
      }
    };
    return threadFactory;
  }
}
