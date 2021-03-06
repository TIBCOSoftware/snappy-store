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
/**
 * 
 */
package com.gemstone.gemfire;

import com.gemstone.gemfire.cache.CacheRuntimeException;

/**
 * Abstract root class of all GemFire exceptions representing system
 * cancellation
 * 
 * @author jpenney
 * @since 6.0
 */
public abstract class CancelException extends CacheRuntimeException {

  /**
   * for serialization
   */
  public CancelException() {
  }

  /**
   * Create instance with given message
   * @param message the message
   */
  public CancelException(String message) {
    super(message);
  }

  /**
   * Create instance with given message and cause
   * @param message the message
   * @param cause the cause
   */
  public CancelException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Create instance with empty message and given cause
   * @param cause the cause
   */
  public CancelException(Throwable cause) {
    super(cause);
  }

}
