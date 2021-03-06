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
/*
 * IndexInvalidException.java
 *
 * Created on March 16, 2005, 7:40 PM
 */

package com.gemstone.gemfire.cache.query;

import com.gemstone.gemfire.GemFireException;

/**
 * Thrown if the index definition is not valid.
 *
 * @author vaibhav
 * @since 4.0
 */

public class IndexInvalidException extends GemFireException {
private static final long serialVersionUID = 3285601274732772770L;
  
  /**
   * Construct an instance of IndexInvalidException
   * @param msg the error message
   */
  public IndexInvalidException(String msg) {
    super(msg);
  }
  
  /**
   * Construct an instance of IndexInvalidException
   * @param msg the error message
   * @param cause a Throwable cause
   */
  public IndexInvalidException(String msg, Throwable cause) {
    super(msg);
    initCause(cause);
  }
  
  /**
   * Construct an instance of IndexInvalidException
   * @param cause a Throwable cause
   */
  public IndexInvalidException(Throwable cause) {
    super(cause.getCause() != null ? cause.getCause().getMessage() : cause.getMessage());
    initCause(cause);
  }
  
}
