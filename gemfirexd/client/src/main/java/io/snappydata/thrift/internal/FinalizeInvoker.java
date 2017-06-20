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

package io.snappydata.thrift.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.internal.shared.FinalizeObject;

/**
 * 
 * 
 * @author kneeraj
 *
 */
public class FinalizeInvoker implements Runnable {

  private final CountDownLatch stopper;
  private volatile boolean keepRunning;
  private static final long FREQUENCY_IN_MILLISECONDS = 200;

  public FinalizeInvoker() {
    this.stopper = new CountDownLatch(1);
    this.keepRunning = true;
  }

  @Override
  public void run() {
    try {
      // System.out.println("KN: starting cleaner");
      while (this.keepRunning) {
        long start = System.currentTimeMillis();
        FinalizeObject.getClientHolder().invokePendingFinalizers();
        long end = System.currentTimeMillis();
        long gap = end - start;
        long toSleep;
        if (gap >= 0 && ((toSleep = (FREQUENCY_IN_MILLISECONDS - gap)) > 0)) {
          synchronized (this) {
            if (this.keepRunning) {
              try {
                this.wait(toSleep);
              } catch (InterruptedException e) {
                // ignore. The thread should be closed only when the shutdown
                // hook is called.
              }
            }
          }
        }
      }
    } finally {
      stopper.countDown();
      // System.out.println("KN: stopped cleaner");
    }
  }

  public void stopFinalizeInvoker() {
    synchronized (this) {
      this.keepRunning = false;
      this.notify();
    }
    try {
      this.stopper.await(60L, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // ignore;
    }
  }

  public static class StopFinalizer extends Thread {

    private FinalizeInvoker toBeStopped;

    public StopFinalizer(FinalizeInvoker toStop) {
      this.toBeStopped = toStop;
    }

    @Override
    public void run() {
      if (this.toBeStopped != null) {
        this.toBeStopped.stopFinalizeInvoker();
      }
    }
  }
}
