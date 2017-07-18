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
package com.gemstone.gemfire;

/**
 * Abstract cancellation proxy for cancelling an operation, esp. a thread.
 * 
 * @author jpenney
 * @see CancelException
 * @since 5.1
 */
public abstract class CancelCriterion
{
  /**
   * Indicate if the service is in the progress of being cancelled.  The
   * typical use of this is to indicate, in the case of an {@link InterruptedException},
   * that the current operation should be cancelled.
   * @return null if the service is not shutting down
   */
  public abstract String cancelInProgress();
//import com.gemstone.gemfire.distributed.internal.DistributionManager;
//    * <p>
//    * In particular, a {@link DistributionManager} returns a non-null result if
//    * message distribution has been terminated.

  /**
   * See if the current operation is being cancelled.  If so, it either
   * throws a {@link RuntimeException} (usually a {@link CancelException}).
   * 
   * @param e an underlying exception, if any
   * @see #cancelInProgress()
   */
  public final void checkCancelInProgress(Throwable e) {
    SystemFailure.checkFailure();
    String reason = cancelInProgress();
    if (reason == null) {
      return;
    }
    throw generateCancelledException(e);
  }

  /**
   * Template factory method for generating the exception to be thrown by
   * {@link #checkCancelInProgress(Throwable)}. Override this to specify
   * different exception for checkCancelInProgress() to throw.
   * 
   * @param e
   *          an underlying exception, if any
   * @return RuntimeException to be thrown by checkCancelInProgress(), null if
   *         the receiver has not been cancelled.
   */
  abstract public RuntimeException generateCancelledException(Throwable e);
}
