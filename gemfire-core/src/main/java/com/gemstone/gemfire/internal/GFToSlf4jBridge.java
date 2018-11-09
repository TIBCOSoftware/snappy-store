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
package com.gemstone.gemfire.internal;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.org.jgroups.util.StringId;
import org.apache.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends LowWriterImpl to bridge log messages from GF to slf4j.
 * There on the handler will be managed by slf4j binding and logger
 * configuration
 */
public class GFToSlf4jBridge extends LogWriterImpl {
  private final String logFile;
  private final String logName;
  private int level;
  private final AtomicReference<Logger> logRef = new AtomicReference<>();

  public GFToSlf4jBridge(String logName, String logFile) {
    this(logName, logFile, INFO_LEVEL);
  }

  public GFToSlf4jBridge(String logName, String logFile, int level) {
    this.logName = logName;
    this.logFile = logFile;
    this.level = level;
  }

  @Override
  public int getLevel() {
    return level;
  }

  public void setLevel(int level) {
    this.level = level;
  }

  @Override
  public void put(int level, String msg, Throwable exception) {
    Logger log = getLogger();
    switch (level) {
      case INFO_LEVEL:
      case CONFIG_LEVEL:
        log.info(msg, exception);
        break;
      case WARNING_LEVEL:
        log.warn(msg, exception);
        break;
      case FINE_LEVEL:
        log.debug(msg, exception);
        break;
      case SEVERE_LEVEL:
      case ERROR_LEVEL:
        log.error(msg, exception);
        break;
      case FINER_LEVEL:
      case FINEST_LEVEL:
      case ALL_LEVEL:
        log.trace(msg, exception);
        break;
      case NONE_LEVEL:
        break;
      default:
        log.debug(msg, exception);
        break;
    }
  }

  public void setLevelForLog4jLevel(Level log4jlevel) {
    switch (log4jlevel.toInt()) {
      case Level.ALL_INT:
        level = FINEST_LEVEL;
        break;
      case Level.DEBUG_INT:
        level = FINE_LEVEL;
        break;
      case Level.INFO_INT:
        level = INFO_LEVEL;
        break;
      case Level.WARN_INT:
        level = WARNING_LEVEL;
        break;
      case Level.ERROR_INT:
        level = ERROR_LEVEL;
        break;
      case Level.FATAL_INT:
        level = SEVERE_LEVEL;
        break;
      case Level.OFF_INT:
        level = NONE_LEVEL;
        break;
      case Level.TRACE_INT:
        level = FINER_LEVEL;
        break;
      default:
        level = CONFIG_LEVEL;
        break;
    }
  }

  @Override
  public void put(int msgLevel, StringId msgId, Object[] params, Throwable ex) {
    String msg = msgId.toLocalizedString(params);
    put(msgLevel, msg, ex);
  }

  public Logger getLogger() {
    Logger logger = logRef.get();
    if (logger == null) {
      synchronized (logRef) {
        logger = logRef.get();
        if (logger == null) {
          final String name = this.logName != null && this.logName.length() > 0
              ? ClientSharedUtils.LOGGER_NAME + '.' + this.logName
              : ClientSharedUtils.LOGGER_NAME;
          try {
            ClientSharedUtils.initLog4j(this.logFile, ClientSharedUtils
                .convertToLog4LogLevel(GemFireLevel.create(this.level)));
          } catch (IOException ioe) {
            throw new GemFireIOException(ioe.getMessage(), ioe);
          }
          logger = LoggerFactory.getLogger(name);
          logRef.set(logger);
        }
      }
    }

    return logger;
  }
}
