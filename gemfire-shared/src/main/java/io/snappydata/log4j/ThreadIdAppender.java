/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package io.snappydata.log4j;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Custom appender to add thread ID to the thread name (%t) pattern.
 * <p>
 * Normally it is simpler to use the custom {@link PatternLayout} but that
 * will not work correctly for async logging with AsyncAppender, in which case
 * this appender needs to be attached on top of AsyncAppender to display
 * the thread ID in logs.
 */
public class ThreadIdAppender extends AppenderSkeleton {

  @Override
  protected void append(LoggingEvent event) {
    PatternLayout.addThreadIdToEvent(event);
  }

  @Override
  public boolean requiresLayout() {
    return false;
  }

  @Override
  public void close() {
    closed = true;
  }
}
