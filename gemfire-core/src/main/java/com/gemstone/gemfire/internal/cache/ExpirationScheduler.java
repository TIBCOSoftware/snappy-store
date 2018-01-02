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

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.SystemTimer;
import com.gemstone.gemfire.internal.concurrent.*;

/**
 * ExpirationScheduler uses a single instance of java.util.Timer (and
 * therefore a single thread) per VM to schedule and execute region and 
 * entry expiration tasks.
 */

public class ExpirationScheduler
  {
  private static final boolean DEBUG = Boolean.getBoolean("gemfire.DEBUG_EXPIRATION");
  private final SystemTimer timer;
  private final AtomicInteger pendingCancels = new AtomicInteger();
  private static final int MAX_PENDING_CANCELS = Integer.getInteger("gemfire.MAX_PENDING_CANCELS", 10000).intValue();

  public ExpirationScheduler(InternalDistributedSystem ds) {
    this.timer = new SystemTimer(ds, true, ds.getLogWriterI18n());
  }

  public void forcePurge() {
    pendingCancels.getAndSet(0);
    this.timer.timerPurge();
  }

  /**
   * Called when we have cancelled a scheduled timer task.
   * Do work, if possible to fix bug 37574.
   */
  public void incCancels() {
    int pc = pendingCancels.incrementAndGet();
    if (pc > MAX_PENDING_CANCELS) {
      pc = pendingCancels.getAndSet(0);
      if (pc > MAX_PENDING_CANCELS) {
        this.timer.timerPurge();
        // we could try to do some fancy stuff here but the value
        // of the atomic is just a hint so don't bother adjusting it
//           // take the diff between the number of actual cancels we purged
//           // "purgedCancels" and the number we said we would purge "pc".
//           int diff = purgedCancels - pc;
      } else {
        // some other thread beat us to it so add back in the cancels
        // we just removed by setting it to 0
        pendingCancels.addAndGet(pc);
      }
    }
  }

  /** schedules the given expiration task */
  public ExpiryTask addExpiryTask(ExpiryTask task) {
    LogWriterI18n log = task.getLocalRegion().getCache().getLoggerI18n();
    try {
      if (DEBUG) {
        log.info(LocalizedStrings.ExpirationScheduler_SCHEDULING__0__TO_FIRE_IN__1__MS, new Object[] {task, Long.valueOf(task.getExpiryMillis())});
      }
      // By using getExpirationTime and passing a Date to schedule
      // we get rid of two calls of System.currentTimeMillis().
      // The Date object creation is very simple and has a very short life.
      timer.schedule(task, new Date(task.getExpirationTime()));
    }
    catch (EntryNotFoundException e) {
      // ignore - there are unsynchronized paths that allow an entry to
      // be destroyed out from under us.
    }
    catch (IllegalStateException e) {
      // task must have been cancelled by another thread so don't schedule it
    }
    return task;
  }

  /** schedules the given entry expiration task */
  public boolean addEntryExpiryTask(EntryExpiryTask task) {
    LogWriterI18n log = task.getLocalRegion().getCache().getLoggerI18n();
    try {
      if (DEBUG) {
        log.info(LocalizedStrings.ExpirationScheduler_SCHEDULING__0__TO_FIRE_IN__1__MS, new Object[] {task, Long.valueOf(task.getExpiryMillis())});
      }
      // By using getExpirationTime and passing a Date to schedule
      // we get rid of two calls of System.currentTimeMillis().
      // The Date object creation is very simple and has a very short life.
      timer.schedule(task, new Date(task.getExpirationTime()));
    }
    catch (EntryNotFoundException e) {
      // ignore - there are unsynchronized paths that allow an entry to
      // be destroyed out from under us.
      return false;
    }
    catch (IllegalStateException e) {
      // task must have been cancelled by another thread so don't schedule it
      return false;
    }
    return true;
  }

  /** schedule a java.util.TimerTask for execution */
  public void schedule(SystemTimer.SystemTimerTask task, long when) {
    timer.schedule(task, when);
  }

  /** @see java.util.Timer#cancel() */
  public void cancel() {
    timer.cancel();
  }
}
