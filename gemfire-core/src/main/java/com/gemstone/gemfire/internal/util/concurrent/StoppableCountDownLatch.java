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
package com.gemstone.gemfire.internal.util.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.internal.Assert;

/**
 * This class is a "stoppable" cover for {@link CountDownLatch}.
 * @author jpenney
 */
public class StoppableCountDownLatch {

  /**
   * This is how often waiters will wake up to check for cancellation
   */
  public static final long RETRY_TIME = Long.getLong(
      "gemfire.stoppable-retry-interval", 2000).longValue();

  /**
   * The underlying latch
   */
  private final CountDownLatch latch;

  /**
   * The cancellation criterion
   */
  private final CancelCriterion stopper;
  
  /**
   * @param count the number of times {@link #countDown} must be invoked
   *        before threads can pass through {@link #await()}
   * @throws IllegalArgumentException if {@code count} is negative
   */
  public StoppableCountDownLatch(CancelCriterion stopper, int count) {
      Assert.assertTrue(stopper != null);
      this.latch = new CountDownLatch(count);
      this.stopper = stopper;
  }

  /**
   * @see CountDownLatch#await()
   * @throws InterruptedException
   */
  public void await() throws InterruptedException {
      for (;;) {
        stopper.checkCancelInProgress(null);
        if (latch.await(RETRY_TIME, TimeUnit.MILLISECONDS)) {
          break;
        }
      }
  }

  /**
   * @see CountDownLatch#await(long, TimeUnit)
   * @param msTimeout how long to wait in milliseconds
   * @return true if it was unlatched
   * @throws InterruptedException
   */
  public boolean await(long msTimeout) throws InterruptedException {
    stopper.checkCancelInProgress(null);
    return latch.await(msTimeout, TimeUnit.MILLISECONDS);
  }

  /**
   * @see CountDownLatch#countDown()
   */
  public synchronized void countDown() {
    latch.countDown();
  }

  /**
   * @see CountDownLatch#getCount()
   * @return the current count
   */
  public long getCount() {
    return latch.getCount();
  }

  /**
   * @return a string identifying this latch, as well as its state
   */
  @Override
  public String toString() {
      return "(Stoppable) " + latch.toString();
  }
}
