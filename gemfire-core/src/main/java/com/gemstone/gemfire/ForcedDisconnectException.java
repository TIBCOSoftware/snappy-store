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
 * An <code>ForcedDisconnectException</code> is thrown when a GemFire
 * application is removed from the distributed system due to membership
 * constraints such as network partition detection.
 * 
 * @since 5.7
 */
public class ForcedDisconnectException extends CancelException {
private static final long serialVersionUID = 4977003259880566257L;

  //////////////////////  Constructors  //////////////////////
  /**
   * Creates a new <code>SystemConnectException</code>.
   */
  public ForcedDisconnectException(String message) {
    super(message);
    // In case of ForcedDisconnect, dump the heap if dumpheap system property is set as true
    HeapDumper.dumpHeap(/* live= */ true);
  }
  
  public ForcedDisconnectException(String message, Throwable cause) {
    super(message, cause);
    // In case of ForcedDisconnect, dump the heap if dumpheap system property is set as true
    HeapDumper.dumpHeap(/* live= */ true);
  }

}
