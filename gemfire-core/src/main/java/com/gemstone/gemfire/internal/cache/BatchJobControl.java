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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * Base class for submitting, executing and waiting for batch jobs.
 * 
 * @author swale
 * @since gfxd 1.0
 */
public abstract class BatchJobControl {

  protected final Executor executor;
  protected final LogWriterI18n logger;
  protected final CancelCriterion cancelCriterion;
  protected final AtomicReference<RuntimeException> failure;
  protected final AtomicInteger numBatches;
  protected BatchJob currentJob;
  protected final int maxQueuedJobs;

  protected static final int DEFAULT_MAX_QUEUED_JOBS = 256;

  protected BatchJobControl(Executor executor, LogWriterI18n logger,
      CancelCriterion cancelCriterion) {
    this(executor, logger, cancelCriterion, DEFAULT_MAX_QUEUED_JOBS);
  }

  protected BatchJobControl(Executor executor, LogWriterI18n logger,
      CancelCriterion cancelCriterion, int maxQueuedJobs) {
    this.executor = executor;
    this.logger = logger;
    this.cancelCriterion = cancelCriterion;
    this.failure = new AtomicReference<RuntimeException>(null);
    this.numBatches = new AtomicInteger(0);
    this.currentJob = initJob();
    this.maxQueuedJobs = maxQueuedJobs;
  }

  protected BatchJob initJob() {
    return newJob(null);
  }

  @SuppressWarnings("unchecked")
  protected final <J extends BatchJob> J getBatchJob(J currentResult) {
    if (this.currentJob.isFull()) {
      // check for failure before submitting a new job
      if (getFailure() != null) {
        // below call will itself throw back the failure
        waitForJobs(0);
      }
      int nJobs = this.numBatches.incrementAndGet();
      final BatchJob currJob = this.currentJob;
      this.currentJob = newJob(currentResult);
      this.executor.execute(currJob);
      if (nJobs > this.maxQueuedJobs) {
        // wait for at least half of submitted jobs to finish
        waitForJobs(this.maxQueuedJobs / 2);
      }
    }
    return (J)this.currentJob;
  }

  public final void waitForJobs(int maxRemaining) {
    final boolean doLog = doLog();
    if (this.numBatches.get() > maxRemaining) {
      if (doLog) {
        logger.info(LocalizedStrings.DEBUG, String.format(
            "BatchJobControl#endJobs waiting for total %s jobs, "
                + "maxRemaining=%s", this.numBatches, maxRemaining));
      }
      // check for failure after some number of waits
      int tries = 1;
      synchronized (this) {
        while (this.numBatches.get() > maxRemaining) {
          Throwable t = null;
          try {
            this.wait(200L);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            t = ie;
          }
          this.cancelCriterion.checkCancelInProgress(t);
          if ((tries % 10) == 0) {
            checkFailure(maxRemaining, doLog);
          }
          tries++;
        }
      }
    }
    checkFailure(maxRemaining, doLog);
  }

  protected void checkFailure(int maxRemaining, boolean doLog) {
    RuntimeException re = getFailure();
    if (doLog) {
      logger.info(LocalizedStrings.DEBUG,
          "BatchJobControl#endJobs end waiting for jobs with maxRemaining="
              + maxRemaining, re);
    }
    if (re != null) {
      this.cancelCriterion.checkCancelInProgress(re);
      throw re;
    }
  }

  public void submitLastJob() {
    final BatchJob lastJob = this.currentJob;
    if (lastJob != null && lastJob.numSubmitted() > 0) {
      // check for failure before submitting a new job
      if (getFailure() != null) {
        // below call will itself throw back the failure
        waitForJobs(0);
      }
      this.numBatches.incrementAndGet();
      this.executor.execute(lastJob);
      this.currentJob = null;
    }
  }

  public final void endJobs() {
    submitLastJob();
    waitForJobs(0);
  }

  protected RuntimeException getFailure() {
    return this.failure.get();
  }

  protected abstract BatchJob newJob(BatchJob currentResult);
  protected abstract void addJobResult(BatchJob job);
  protected abstract boolean doLog();

  /**
   * Base class to encapsulate a batch job that can be submitted to an
   * {@link Executor}.
   * 
   * @author swale
   * @since gfxd 1.0
   */
  public abstract class BatchJob implements Runnable {

    protected abstract void executeJob() throws Exception;
    protected abstract int numSubmitted();
    protected abstract boolean isFull();
    protected abstract String getCurrentStateString(Throwable t);
    protected abstract RuntimeException getFailureException(String reason,
        Throwable t);

    @Override
    public final void run() {
      Throwable fail = null;
      String error = null;
      try {
        executeJob();
        // check for failure before sending back result
        checkFailure(-1, doLog());
        addJobResult(this);
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error
            && SystemFailure.isJVMFailureError(err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // check VM failure
        SystemFailure.checkFailure();

        fail = t;
        error = "BatchJob#run failed in " + getCurrentStateString(t);
        logger.error(LocalizedStrings.ONE_ARG, error, t);
        fail = getFailureException(Thread.currentThread().getName() + ": "
            + error, t);
      } finally {
        synchronized (BatchJobControl.this) {
          numBatches.decrementAndGet();
          BatchJobControl.this.notifyAll();
        }
        if (fail != null) {
          if (fail instanceof RuntimeException) {
            failure.set((RuntimeException)fail);
          }
          else {
            failure.set(new FunctionException(fail));
          }
        }
      }
    }
  }
}
