/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

import java.util.Enumeration;

import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.helpers.AppenderAttachableImpl;
import org.apache.log4j.spi.AppenderAttachable;
import org.apache.log4j.spi.LoggingEvent;

/**
 * Custom appender to add thread ID to the thread name (%t) pattern.
 * <p>
 * Normally it is simpler to use the custom {@link PatternLayout} but that
 * will not work correctly for async logging with AsyncAppender, in which case
 * this appender needs to be attached on top of AsyncAppender to display
 * the thread ID in logs.
 */
public class ThreadIdAppender extends AppenderSkeleton implements AppenderAttachable {

  private final AppenderAttachableImpl appenders = new AppenderAttachableImpl();

  @Override
  protected void append(LoggingEvent event) {
    synchronized (appenders) {
      appenders.appendLoopOnAppenders(PatternLayout.addThreadIdToEvent(event));
    }
  }

  @Override
  public void addAppender(final Appender newAppender) {
    synchronized (appenders) {
      appenders.addAppender(newAppender);
    }
  }

  @Override
  public Enumeration getAllAppenders() {
    synchronized (appenders) {
      return appenders.getAllAppenders();
    }
  }

  @Override
  public Appender getAppender(final String name) {
    synchronized (appenders) {
      return appenders.getAppender(name);
    }
  }


  @Override
  public boolean isAttached(final Appender appender) {
    synchronized (appenders) {
      return appenders.isAttached(appender);
    }
  }

  @Override
  public void removeAllAppenders() {
    synchronized (appenders) {
      appenders.removeAllAppenders();
    }
  }

  @Override
  public void removeAppender(final Appender appender) {
    synchronized (appenders) {
      appenders.removeAppender(appender);
    }
  }

  @Override
  public void removeAppender(final String name) {
    synchronized (appenders) {
      appenders.removeAppender(name);
    }
  }

  @Override
  public boolean requiresLayout() {
    return false;
  }

  @Override
  public void close() {
    closed = true;
    synchronized (appenders) {
      Enumeration<?> iter = appenders.getAllAppenders();
      if (iter != null) {
        while (iter.hasMoreElements()) {
          Object next = iter.nextElement();
          if (next instanceof Appender) {
            ((Appender)next).close();
          }
        }
      }
    }
  }
}
