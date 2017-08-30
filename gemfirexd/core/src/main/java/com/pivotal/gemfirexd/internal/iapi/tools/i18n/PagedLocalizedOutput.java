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
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.iapi.tools.i18n;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.StopWatch;

/**
 * This {@link PrintWriter} implementation shows the output provided one page at
 * a time in interactive sessions waiting for user input to continue or allowing
 * the user to terminate the output at a particular point. The write and other
 * methods throw an {@link IllegalStateException} to indicate force quit by
 * user.
 * 
 * @author swale
 * @since 7.0
 */
public class PagedLocalizedOutput extends LocalizedOutput {

  private final InputStream in;
  private final int terminalWidth;
  private final int terminalHeight;
  private final String continuePrompt;
  private final StopWatch timer;
  private char[] cbuf;
  private static final int BUFSIZE = 1024;

  private int currentWidth;
  private int currentLines;

  public PagedLocalizedOutput(OutputStream o, InputStream in,
      int terminalWidth, int terminalHeight, String continuePrompt,
      final StopWatch timer) {
    super(o);
    this.in = in;
    this.terminalWidth = terminalWidth - 1;
    // reduce by 1 to accomodate the continue prompt
    this.terminalHeight = terminalHeight - 1;
    this.continuePrompt = continuePrompt;
    this.timer = timer;
  }

  public PagedLocalizedOutput(OutputStream o, String enc, InputStream in,
      int terminalWidth, int terminalHeight, String continuePrompt,
      final StopWatch timer) throws UnsupportedEncodingException {
    super(o, enc);
    this.in = in;
    this.terminalWidth = terminalWidth - 1;
    // reduce by 1 to accomodate the continue prompt
    this.terminalHeight = terminalHeight - 1;
    this.continuePrompt = continuePrompt;
    this.timer = timer;
  }

  public PagedLocalizedOutput(Writer writer, InputStream in, int terminalWidth,
      int terminalHeight, String continuePrompt, final StopWatch timer) {
    super(writer);
    this.in = in;
    this.terminalWidth = terminalWidth - 1;
    // reduce by 1 to accomodate the continue prompt
    this.terminalHeight = terminalHeight - 1;
    this.continuePrompt = continuePrompt;
    this.timer = timer;
  }

  public void write(String s, int off, int len) {
    if (this.cbuf == null) {
      this.cbuf = new char[BUFSIZE > len ? BUFSIZE : len];
    }
    else if (this.cbuf.length < len) {
      this.cbuf = new char[len];
    }
    s.getChars(off, off + len, this.cbuf, 0);
    checkPage(this.cbuf, 0, len);
    super.write(this.cbuf, 0, len);
  }

  public void write(char[] buf, int off, int len) {
    checkPage(buf, off, len);
    super.write(buf, off, len);
  }

  public void println() {
    super.println();
    this.currentLines++;
    if (this.currentLines >= this.terminalHeight) {
      if (waitForPage()) {
        throw new IllegalStateException("user ending output");
      }
      this.currentLines = 0;
    }
    this.currentWidth = 0;
  }

  private void checkPage(final char[] buf, int off, int len)
      throws IllegalStateException {
    // check if string will cause the width to exceed the terminal width
    // TODO: ideally this should get the actual length after encoding etc.
    // that is being consumed on the screen, but it may be too late by then
    // if we first print it on screen so it will be an approximation either way;
    // probably can use some encoder API to get the actual width on screen
    int lineWidth = this.currentWidth;
    int newLines = 0;
    for (int index = off; index < (off + len); index++) {
      if (buf[index] == '\n' || ++lineWidth >= this.terminalWidth) {
        lineWidth = 0;
        newLines++;
        if ((this.currentLines + newLines) >= this.terminalHeight) {
          if (waitForPage()) {
            throw new IllegalStateException("user ending output");
          }
          this.currentLines = newLines;
          newLines = 0;
        }
      }
    }
    this.currentLines += newLines;
    this.currentWidth = lineWidth;
  }

  private boolean waitForPage() {
    if (this.timer != null) {
      this.timer.stop();
    }
    // wait for user input before further output
    super.write(this.continuePrompt, 0, this.continuePrompt.length());
    super.flush();
    String chars = ClientSharedUtils.readChars(this.in, false);
    super.println();
    if (chars != null && chars.length() > 0
        && (chars.charAt(0) == 'q' || chars.charAt(0) == 'Q')) {
      super.flush();
      if (this.timer != null) {
        this.timer.start();
      }
      return true;
    }
    if (this.timer != null) {
      this.timer.start();
    }
    return false;
  }
}
