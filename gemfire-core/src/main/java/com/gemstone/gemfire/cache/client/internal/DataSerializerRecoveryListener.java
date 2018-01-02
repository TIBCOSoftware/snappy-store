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

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.cache.client.internal.PoolImpl.PoolTask;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.InternalDataSerializer.SerializerAttributesHolder;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

public class DataSerializerRecoveryListener extends EndpointManager.EndpointListenerAdapter {
  private final AtomicInteger endpointCount = new AtomicInteger();
  protected final InternalPool pool;
  protected final ScheduledExecutorService background;
  protected final long pingInterval;
  protected final Object recoveryScheduledLock = new Object();
  protected boolean recoveryScheduled;
  
  public DataSerializerRecoveryListener(ScheduledExecutorService background, InternalPool pool) {
    this.pool = pool;
    this.pingInterval = pool.getPingInterval();
    this.background = background;
  }
  
  @Override
  public void endpointCrashed(Endpoint endpoint) {
    int count = endpointCount.decrementAndGet();
    if(pool.getLoggerI18n().fineEnabled()) {
      pool.getLoggerI18n().fine("DataSerializerRecoveryTask - EndpointCrashed. Now have " + count  + " endpoints");
    }
  }

  @Override
  public void endpointNoLongerInUse(Endpoint endpoint) {
    int count = endpointCount.decrementAndGet();
    if(pool.getLoggerI18n().fineEnabled()) {
      pool.getLoggerI18n().fine("DataSerializerRecoveryTask - EndpointNoLongerInUse. Now have " + count  + " endpoints");
    }
  }

  @Override
  public void endpointNowInUse(Endpoint endpoint) {
    int count  = endpointCount.incrementAndGet();
    if(pool.getLoggerI18n().fineEnabled()) {
      pool.getLoggerI18n().fine("DataSerializerRecoveryTask - EndpointNowInUse. Now have " + count  + " endpoints");
    }
    if(count == 1) {
      synchronized(recoveryScheduledLock) {
        if(!recoveryScheduled) {
          try {
            recoveryScheduled = true;
            background.execute(new RecoveryTask());
            pool.getLoggerI18n().fine("DataSerializerRecoveryTask - Scheduled Recovery Task");
          } catch(RejectedExecutionException e) {
            //ignore, the timer has been cancelled, which means we're shutting down.
          }
        }
      }
    }
  }
  
  protected class RecoveryTask extends PoolTask {

    @Override
    public void run2() {
      if(pool.getCancelCriterion().cancelInProgress() != null) {
        return;
      }
      synchronized(recoveryScheduledLock) {
        recoveryScheduled = false;
      }
      pool.getLoggerI18n().fine("DataSerializerRecoveryTask - Attempting to recover dataSerializers");
      SerializerAttributesHolder[] holders= InternalDataSerializer.getSerializersForDistribution();
      if(holders.length == 0) {
        return;
      }
      EventID eventId = InternalDataSerializer.generateEventId();
      //Fix for bug:40930
      if (eventId == null) {
        background.schedule(new RecoveryTask(), pingInterval,
            TimeUnit.MILLISECONDS);
        recoveryScheduled = true;
      }
      else {
        try {
          RegisterDataSerializersOp.execute(pool, holders, eventId);
        } 
        catch (CancelException e) {
          throw e;
        }
        catch (RejectedExecutionException e) {
          // This is probably because we've started to shut down.
          pool.getCancelCriterion().checkCancelInProgress(e);
          throw e; // weird
        }
        catch(Exception e) {
          pool.getCancelCriterion().checkCancelInProgress(e);
          
          // If ClassNotFoundException occurred on server, don't retry
          Throwable cause = e.getCause();
          boolean cnfException = false;
          if (cause instanceof ClassNotFoundException) {
            pool.getLoggerI18n().warning(
                LocalizedStrings.DataSerializerRecoveryListener_ERROR_CLASSNOTFOUNDEXCEPTION,
                cause.getMessage());
            cnfException = true;
          }
          
          if(!recoveryScheduled && !cnfException) {
            pool.getLoggerI18n().warning(
              LocalizedStrings.DataSerializerRecoveryListener_ERROR_RECOVERING_DATASERIALIZERS,
              e);
            background.schedule(new RecoveryTask(), pingInterval, TimeUnit.MILLISECONDS);
            recoveryScheduled = true;
          }
        } finally {
          pool.releaseThreadLocalConnection();
        }
      }
    }
    
    
    @Override
    public LogWriterI18n getLogger() {
      return pool.getLoggerI18n();
    }
    
    
  }
}
