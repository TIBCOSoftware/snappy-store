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

package com.gemstone.gemfire.internal.shared;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

public final class ClientSharedData {

  public static final Charset UTF8 = StandardCharsets.UTF_8;

  public static final byte[] BYTES_PREFIX_CLIENT_VERSION = new byte[] {
      0x7e, -0x3a, 0x74, -0x1f, 0x7d, -0x4d, 0x1b, 0x65 };

  public static final int BYTES_PREFIX_CLIENT_VERSION_LENGTH =
      BYTES_PREFIX_CLIENT_VERSION.length;

  // This token delimiter value is used to separate the tokens for multiple
  // error messages. This is used in DRDAConnThread
  /**
   * <code>SQLERRMC_MESSAGE_DELIMITER</code> When message argument tokes are
   * sent, this value separates the tokens for mulitiple error messages
   */
  public static final String SQLERRMC_MESSAGE_DELIMITER = new String(new char[] {
      (char)20, (char)20, (char)20 });

  /**
   * <code>SQLERRMC_TOKEN_DELIMITER</code> separates message argument tokens
   */
  public static final String SQLERRMC_TOKEN_DELIMITER = new String(
      new char[] { (char)20 });

  /**
   * <code>SQLERRMC_PREFORMATTED_MESSAGE_DELIMITER</code>, When full message
   * text is sent for severe errors. This value separates the messages.
   */
  public static String SQLERRMC_PREFORMATTED_MESSAGE_DELIMITER = "::";

  /** delimiter that separates the messageId and server information, if any */
  public static final String SQLERRMC_SERVER_DELIMITER = new String(new char[] {
      (char)24, (char)24 });

  public static final byte CLIENT_FAILOVER_CONTEXT_WRITTEN = (byte)1;

  public static final byte CLIENT_FAILOVER_CONTEXT_NOT_WRITTEN = (byte)0;

  public static final byte CLIENT_TXID_WRITTEN = (byte)1;

  public static final byte CLIENT_TXID_NOT_WRITTEN = (byte)0;

  public static final byte[] ZERO_ARRAY = new byte[0];

  public static final ByteBuffer NULL_BUFFER = ByteBuffer.wrap(ZERO_ARRAY);

  private static final ThreadLocal<GregorianCalendar> DEFAULT_CALENDAR =
      new ThreadLocal<GregorianCalendar>() {
    @Override
    public GregorianCalendar initialValue() {
      return new GregorianCalendar(TimeZone.getDefault(),
          Locale.getDefault(Locale.Category.FORMAT));
    }
  };

  public static GregorianCalendar getDefaultCalendar() {
    return DEFAULT_CALENDAR.get();
  }

  public static GregorianCalendar getDefaultCleanCalendar() {
    final GregorianCalendar cal = DEFAULT_CALENDAR.get();
    cal.clear();
    return cal;
  }
}
