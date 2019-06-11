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

package com.pivotal.gemfirexd.internal.engine.ddl;

/**
 * Marker interface for messages/objects that should be pre-processed in the DDL
 * queue before any other messages/objects during DDL replay regardless of its
 * position in the queue.
 * 
 * @author swale
 * @since 7.5
 */
public interface GfxdDDLPreprocessOrPostProcess {

  /**
   * @return if this message/object should be pre-processed in the DDL queue
   *         before any other messages/objects
   */
  public boolean preprocess();

  public boolean postprocess();

  /** Get a string representation of this message/object */
  public void appendFields(StringBuilder sb);
}
