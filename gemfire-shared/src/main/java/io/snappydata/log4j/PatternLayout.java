/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.lang.reflect.Field;

import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Custom layout to add thread ID to the thread name (%t) pattern.
 * <p>
 * Normally it is simpler to use this custom class as the layout for any
 * appender but will not work correctly for async logging with AsyncAppender.
 * In that case use {@link ThreadIdAppender} on top of AsyncAppender.
 */
public class PatternLayout extends org.apache.log4j.PatternLayout {

  private static final Field threadNameField;
  private static final long threadNameOffset;

  static {
    try {
      threadNameField = LoggingEvent.class.getDeclaredField("threadName");
      threadNameField.setAccessible(true);
      threadNameOffset = UnsafeHolder.hasUnsafe()
          ? UnsafeHolder.getUnsafe().objectFieldOffset(threadNameField) : -1L;
    } catch (NoSuchFieldException nse) {
      throw new ExceptionInInitializerError(nse);
    }
  }

  public PatternLayout() {
    super();
  }

  public PatternLayout(String pattern) {
    super(pattern);
  }

  @Override
  public String format(LoggingEvent event) {
    return super.format(addThreadIdToEvent(event));
  }

  static LoggingEvent addThreadIdToEvent(LoggingEvent event) {
    try {
      String currentName = threadNameOffset != -1L
          ? (String)UnsafeHolder.getUnsafe().getObject(event, threadNameOffset)
          : (String)threadNameField.get(event);
      if (currentName == null ||
          currentName.charAt(currentName.length() - 1) != '>' ||
          !currentName.contains("<tid=0x")) {
        Thread currentThread = Thread.currentThread();
        String threadNameAndId = currentThread.getName() + "<tid=0x" +
            Long.toHexString(currentThread.getId()) + '>';
        if (threadNameOffset != -1L) {
          UnsafeHolder.getUnsafe().putObject(event, threadNameOffset, threadNameAndId);
        } else {
          threadNameField.set(event, threadNameAndId);
        }
      }
    } catch (IllegalArgumentException | IllegalAccessException ignored) {
    }
    return event;
  }
}
