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

import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

import java.io.*;
import java.util.logging.*;
import java.util.Date;

/**
 * Implementation of the standard JDK formatter that formats a message
 * in GemFire's log format.
 */
public class GemFireFormatter extends Formatter {

  /**
   * Use the log writer to use some of its formatting code.
   */
  private final LogWriterImpl logWriter;

  public GemFireFormatter(LogWriterImpl logWriter) {
    this.logWriter = logWriter;
  }
    
  @Override
  public String format(LogRecord record) {
    java.io.StringWriter sw = new java.io.StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    pw.println();
    pw.print('[');
    pw.print(record.getLevel().getName());
    pw.print(' ');
    pw.print(logWriter.formatDate(new Date(record.getMillis())));
    String threadName = Thread.currentThread().getName();
    if (threadName != null) {
      pw.print(' ');
      pw.print(threadName);
    }
    pw.print(" tid=0x");
    pw.print(Long.toHexString(Thread.currentThread().getId()));
    pw.print("] ");
    pw.print("(msgTID=");
    pw.print(record.getThreadID());

    pw.print(" msgSN=");
    pw.print(record.getSequenceNumber());
//     if (record.getLoggerName() != null) {
//       pw.print(' ');
//       pw.print(record.getLoggerName());
//     }
//     if (record.getSourceClassName() != null) {
//       pw.print(' ');
//       pw.print(record.getSourceClassName());
//     }
//     if (record.getSourceMethodName() != null) {
//       pw.print(' ');
//       pw.print(record.getSourceMethodName());
//     }
    pw.print(") ");

    String msg = record.getMessage();
    if (msg != null) {
      try {
        LogWriterImpl.formatText(pw, msg, 40);
      } catch (RuntimeException e) {
        pw.println(msg);
        pw.println(LocalizedStrings.GemFireFormatter_IGNORING_THE_FOLLOWING_EXCEPTION.toLocalizedString());
        e.printStackTrace(pw);
      }
    } else {
      pw.println();
    }
    if (record.getThrown() != null) {
      record.getThrown().printStackTrace(pw);
    }
    pw.close();
    try {
      sw.close();
    } catch (java.io.IOException ignore) {}
    String result = sw.toString();
    return result;
  }
}
