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

package com.pivotal.gemfirexd.internal.shared.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Properties;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Some common utilities shared by client and server code. Only has static
 * methods so no instance allowed.
 * 
 * @author swale
 */
public abstract class SharedUtils {

  /** no instance allowed */
  private SharedUtils() {
  }

  /** the number of bytes required for representation of SQL Timestamp */
  public static final int TIMESTAMP_RLENGTH = 26;

  /**
   * the value for <code>VMKind</code> that indicates that this VM is a GemFireXD
   * datastore
   */
  public static final String VM_DATASTORE = "datastore";

  /**
   * the value for <code>VMKind</code> that indicates that this VM is a GemFireXD
   * accessor that holds no data
   */
  public static final String VM_ACCESSOR = "accessor";

  /**
   * the value for <code>VMKind</code> that indicates that this VM is a GemFireXD
   * stand-alone locator started using <code>GfxdDistributionLocator</code>
   */
  public static final String VM_LOCATOR = "locator";

  /**
   * the value for <code>VMKind</code> that indicates that this VM is a GemFireXD
   * jmx agent started using <code>AdminDistributedSystem</code>
   */
  public static final String VM_AGENT = "agent";
  
  /**
   * the value for <code>VMKind</code> that indicates that this VM is a GemFireXD
   * admin started using <code>GfxdSystemAdmin</code>
   */
  public static final String VM_ADMIN = "admin";
  
  private static final Pattern csvPattern = Pattern
      .compile("[\\s]*(,[\\s]*)|$");

  /**
   * The default number of millis to sleep before retry during failover.
   */
  public static final int HA_RETRY_SLEEP_MILLIS = 10;

  public static final String GFXD_PRODUCT_NAME = "SnappyData";

  public static final String GFXD_VERSION_PROPERTIES =
      "com/pivotal/gemfirexd/internal/GemFireXDVersion.properties";

  /**
   * Adapted from derby client <code>DateTime#parseTimestampString</code>.
   * 
   * Parse a String of the form <code>yyyy-mm-dd-hh.mm.ss.ffffff</code> and
   * store the various fields into the received Calendar object.
   * 
   * @param timestamp
   *          Timestamp value to parse, as a String.
   * @param cal
   *          Calendar into which to store the parsed fields. Should not be
   *          null.
   * 
   * @return The microseconds field as parsed from the timestamp string. This
   *         cannot be set in the Calendar object but we still want to preserve
   *         the value, in case the caller needs it (for example, to create a
   *         java.sql.Timestamp with microsecond precision).
   */
  public static int parseTimestampString(String timestamp, Calendar cal) {
    final int zeroBase = '0';
    cal.set(Calendar.YEAR, 1000 * ((timestamp.charAt(0)) - zeroBase) + 100
        * ((timestamp.charAt(1)) - zeroBase) + 10
        * ((timestamp.charAt(2)) - zeroBase)
        + ((timestamp.charAt(3)) - zeroBase));

    cal.set(Calendar.MONTH, 10 * ((timestamp.charAt(5)) - zeroBase)
        + ((timestamp.charAt(6)) - zeroBase) - 1);

    cal.set(Calendar.DAY_OF_MONTH, 10 * ((timestamp.charAt(8)) - zeroBase)
        + ((timestamp.charAt(9)) - zeroBase));

    // should be 24-hour below
    cal.set(Calendar.HOUR_OF_DAY,
    /* (original code) cal.set(Calendar.HOUR, */
    10 * ((timestamp.charAt(11)) - zeroBase)
        + ((timestamp.charAt(12)) - zeroBase));

    cal.set(Calendar.MINUTE, 10 * ((timestamp.charAt(14)) - zeroBase)
        + ((timestamp.charAt(15)) - zeroBase));

    cal.set(Calendar.SECOND, 10 * ((timestamp.charAt(17)) - zeroBase)
        + ((timestamp.charAt(18)) - zeroBase));

    int micros = 100000 * ((timestamp.charAt(20)) - zeroBase) + 10000
        * ((timestamp.charAt(21)) - zeroBase) + 1000
        * ((timestamp.charAt(22)) - zeroBase) + 100
        * ((timestamp.charAt(23)) - zeroBase) + 10
        * ((timestamp.charAt(24)) - zeroBase)
        + ((timestamp.charAt(25)) - zeroBase);

    /* The "ffffff" that we parsed is microseconds.  In order to
     * capture that information inside of the MILLISECOND field
     * we have to divide by 1000.
     */
    cal.set(Calendar.MILLISECOND, micros / 1000);
    return micros;
  }

  /**
   * java.sql.Timestamp is converted to a character representation which is in
   * DERBY string representation of a timestamp:
   * <code>yyyy-mm-dd-hh.mm.ss.ffffff</code>. and then converted to bytes
   * 
   * @param timestamp
   *          timestamp value
   * @param cal
   *          the {@link java.util.Calendar} object that represents the
   *          timezone/locale of this timestamp
   * @return the byte array representing the given {@link java.sql.Timestamp}
   */
  public static byte[] timestampToTimestampChars(java.sql.Timestamp timestamp,
      java.util.Calendar cal) {
    final byte[] bytes = new byte[TIMESTAMP_RLENGTH];
    final int len = timestampToTimestampChars(bytes, 0, timestamp, cal, true);
    assert len == TIMESTAMP_RLENGTH;
    return bytes;
  }

  /**
   * java.sql.Timestamp is converted to a character representation which is in
   * DERBY string representation of a timestamp:
   * <code>yyyy-mm-dd-hh.mm.ss.ffffff</code>. and then converted to bytes using
   * ASCII encoding
   * 
   * @param buffer
   *          bytes in ASCII encoding of the timestamp
   * @param offset
   *          write into the buffer from this offset
   * @param timestamp
   *          timestamp value
   * @param cal
   *          the {@link java.util.Calendar} object that represents the
   *          timezone/locale of this timestamp
   * @return {@link #TIMESTAMP_RLENGTH}. This is the fixed length in bytes,
   *         taken to represent the timestamp value or -year if year exceeds
   *         9999
   */
  public static int timestampToTimestampChars(byte[] buffer, int offset,
      java.sql.Timestamp timestamp, java.util.Calendar cal) {
    return timestampToTimestampChars(buffer, offset, timestamp, cal, false);
  }

  /**
   * Adapted from derby client <code>DateTime#timestampToTimestampBytes</code>.
   * 
   * Given year, month, day, hour, minute, second, microsecond/nanos is
   * converted to a character representation which is in DERBY string
   * representation of a timestamp: <code>yyyy-mm-dd hh:mm:ss.ffffff</code> or
   * <code>yyyy-mm-dd hh:mm:ss.fffffffff</code>.
   */
  public static int dateTimeToString(final char[] buffer, int offset,
      int year, final int month, final int day, final int hour,
      final int minute, final int second, final int microsecond,
      int nanos, final boolean clientFormat) {
    final int zeroBase = '0';
    final char dateTimeSep;
    final char timeSep;
    if (clientFormat) {
      dateTimeSep = '-';
      timeSep = '.';
    }
    else {
      dateTimeSep = ' ';
      timeSep = ':';
    }
    if (month >= 0) {
      if (year > 9999) {
        year = 9999;
      }
      buffer[offset++] = (char)(year / 1000 + zeroBase);
      buffer[offset++] = (char)((year % 1000) / 100 + zeroBase);
      buffer[offset++] = (char)((year % 100) / 10 + zeroBase);
      buffer[offset++] = (char)(year % 10 + +zeroBase);
      buffer[offset++] = (char)'-';
      buffer[offset++] = (char)(month / 10 + zeroBase);
      buffer[offset++] = (char)(month % 10 + zeroBase);
      buffer[offset++] = (char)'-';
      buffer[offset++] = (char)(day / 10 + zeroBase);
      buffer[offset++] = (char)(day % 10 + zeroBase);
      if (hour >= 0) {
        buffer[offset++] = dateTimeSep;
      }
    }
    if (hour >= 0) {
      buffer[offset++] = (char)(hour / 10 + zeroBase);
      buffer[offset++] = (char)(hour % 10 + zeroBase);
      buffer[offset++] = timeSep;
      buffer[offset++] = (char)(minute / 10 + zeroBase);
      buffer[offset++] = (char)(minute % 10 + zeroBase);
      buffer[offset++] = timeSep;
      buffer[offset++] = (char)(second / 10 + zeroBase);
      buffer[offset++] = (char)(second % 10 + zeroBase);
    }
    if (nanos < 0) {
      if (microsecond >= 0) {
        buffer[offset++] = (char)'.';
        buffer[offset++] = (char)(microsecond / 100000 + zeroBase);
        buffer[offset++] = (char)((microsecond % 100000) / 10000 + zeroBase);
        buffer[offset++] = (char)((microsecond % 10000) / 1000 + zeroBase);
        buffer[offset++] = (char)((microsecond % 1000) / 100 + zeroBase);
        buffer[offset++] = (char)((microsecond % 100) / 10 + zeroBase);
        buffer[offset] = (char)(microsecond % 10 + zeroBase);
      }
    }
    else {
      buffer[offset++] = (char)'.';
      buffer[offset++] = (char)(nanos / 100000000 + zeroBase);
      /* originally
      buffer[offset++] = (char)((nanos % 100000000) / 10000000 + zeroBase);
      buffer[offset++] = (char)((nanos % 10000000) / 1000000 + zeroBase);
      buffer[offset++] = (char)((nanos % 1000000) / 100000 + zeroBase);
      buffer[offset++] = (char)((nanos % 100000) / 10000 + zeroBase);
      buffer[offset++] = (char)((nanos % 10000) / 1000 + zeroBase);
      buffer[offset++] = (char)((nanos % 1000) / 100 + zeroBase);
      buffer[offset++] = (char)((nanos % 100) / 10 + zeroBase);
      buffer[offset] = (char)(nanos % 10 + zeroBase);
      */
      if (nanos > 0) {
        int startFactor = 100000000, startDivisor = 10000000;
        // remove trailing zeros for server-side formatting
        if (!clientFormat) {
          while ((nanos % 10) == 0 && startDivisor > 0) {
            nanos /= 10;
            startFactor /= 10;
            startDivisor /= 10;
          }
        }
        while (startDivisor > 0) {
          buffer[offset++] = (char)(((nanos % startFactor) / startDivisor)
              + zeroBase);
          startFactor /= 10;
          startDivisor /= 10;
        }
      }
    }
    return offset;
  }

  /**
   * Adapted from derby client <code>DateTime#timestampToTimestampBytes</code>.
   * 
   * Given year, month, day, hour, minute, second, microsecond/nanos is
   * converted to a character representation which is in DERBY string
   * representation of a timestamp: <code>yyyy-mm-dd-hh.mm.ss.ffffff</code> or
   * <code>yyyy-mm-dd-hh.mm.ss.fffffffff</code> and then converted to bytes
   * using ASCII encoding.
   */
  public static int dateTimeToChars(final byte[] buffer, int offset,
      int year, final int month, final int day, final int hour,
      final int minute, final int second, final int microsecond,
      final int nanos, final boolean clientFormat) {
    final int zeroBase = '0';
    final byte dateTimeSep;
    final byte timeSep;
    if (clientFormat) {
      dateTimeSep = (byte)'-';
      timeSep = (byte)'.';
    }
    else {
      dateTimeSep = (byte)' ';
      timeSep = (byte)':';
    }
    if (month >= 0) {
      if (year > 9999) {
        year = 9999;
      }
      buffer[offset++] = (byte)(year / 1000 + zeroBase);
      buffer[offset++] = (byte)((year % 1000) / 100 + zeroBase);
      buffer[offset++] = (byte)((year % 100) / 10 + zeroBase);
      buffer[offset++] = (byte)(year % 10 + +zeroBase);
      buffer[offset++] = (byte)'-';
      buffer[offset++] = (byte)(month / 10 + zeroBase);
      buffer[offset++] = (byte)(month % 10 + zeroBase);
      buffer[offset++] = (byte)'-';
      buffer[offset++] = (byte)(day / 10 + zeroBase);
      buffer[offset++] = (byte)(day % 10 + zeroBase);
      if (hour >= 0) {
        buffer[offset++] = dateTimeSep;
      }
    }
    if (hour >= 0) {
      buffer[offset++] = (byte)(hour / 10 + zeroBase);
      buffer[offset++] = (byte)(hour % 10 + zeroBase);
      buffer[offset++] = timeSep;
      buffer[offset++] = (byte)(minute / 10 + zeroBase);
      buffer[offset++] = (byte)(minute % 10 + zeroBase);
      buffer[offset++] = timeSep;
      buffer[offset++] = (byte)(second / 10 + zeroBase);
      buffer[offset++] = (byte)(second % 10 + zeroBase);
    }
    if (nanos < 0) {
      if (microsecond >= 0) {
        buffer[offset++] = (byte)'.';
        buffer[offset++] = (byte)(microsecond / 100000 + zeroBase);
        buffer[offset++] = (byte)((microsecond % 100000) / 10000 + zeroBase);
        buffer[offset++] = (byte)((microsecond % 10000) / 1000 + zeroBase);
        buffer[offset++] = (byte)((microsecond % 1000) / 100 + zeroBase);
        buffer[offset++] = (byte)((microsecond % 100) / 10 + zeroBase);
        buffer[offset] = (byte)(microsecond % 10 + zeroBase);
      }
    }
    else {
      buffer[offset++] = (byte)'.';
      buffer[offset++] = (byte)(nanos / 100000000 + zeroBase);
      buffer[offset++] = (byte)((nanos % 100000000) / 10000000 + zeroBase);
      buffer[offset++] = (byte)((nanos % 10000000) / 1000000 + zeroBase);
      buffer[offset++] = (byte)((nanos % 1000000) / 100000 + zeroBase);
      buffer[offset++] = (byte)((nanos % 100000) / 10000 + zeroBase);
      buffer[offset++] = (byte)((nanos % 10000) / 1000 + zeroBase);
      buffer[offset++] = (byte)((nanos % 1000) / 100 + zeroBase);
      buffer[offset++] = (byte)((nanos % 100) / 10 + zeroBase);
      buffer[offset] = (byte)(nanos % 10 + zeroBase);
    }
    return offset;
  }

  /**
   * Adapted from derby client <code>DateTime#timestampToTimestampBytes</code>.
   * 
   * java.sql.Timestamp is converted to a character representation which is in
   * DERBY string representation of a timestamp:
   * <code>yyyy-mm-dd-hh.mm.ss.ffffff</code>. and then converted to bytes using
   * ASCII encoding
   * 
   * @param buffer
   *          bytes in ASCII encoding of the timestamp
   * @param offset
   *          write into the buffer from this offset
   * @param timestamp
   *          timestamp value
   * @param cal
   *          the {@link java.util.Calendar} object that represents the
   *          timezone/locale of this timestamp
   * @param truncateYear
   *          true to truncate year to 9999 if greater than 9999 else return
   *          -year
   * @return {@link #TIMESTAMP_RLENGTH}. This is the fixed length in bytes,
   *         taken to represent the timestamp value or -year if year exceeds
   *         9999 when <code>truncateYear</code> is true
   */
  private static int timestampToTimestampChars(byte[] buffer, int offset,
      java.sql.Timestamp timestamp, java.util.Calendar cal, boolean truncateYear) {
    cal.setTime(timestamp);
    int year = cal.get(Calendar.YEAR);
    if (year > 9999) {
      if (truncateYear) {
        year = 9999;
      }
      else {
        return -year;
      }
    }
    final int month = cal.get(Calendar.MONTH) + 1;
    final int day = cal.get(Calendar.DAY_OF_MONTH);
    final int hour = cal.get(Calendar.HOUR_OF_DAY);
    final int minute = cal.get(Calendar.MINUTE);
    final int second = cal.get(Calendar.SECOND);
    final int microsecond = timestamp.getNanos() / 1000;

    dateTimeToChars(buffer, offset, year, month, day, hour, minute, second,
        microsecond, -1, true);

    return TIMESTAMP_RLENGTH;
  }

  // START: Utilities below are copied from StringUtil

  /**
   * Convert string to uppercase Always use the java.util.ENGLISH locale
   * Copied from derby's StringUtil#SQLToUpperCase.
   * 
   * @param s
   *          string to uppercase
   * @return uppercased string
   */
  public static String SQLToUpperCase(String s) {
    return s.toUpperCase(Locale.ENGLISH);
  }

  // END methods from StringUtil

  /**
   * Open an input stream to read a URL or a file. URL is attempted first, if
   * the string does not conform to a URL then an attempt to open it as a
   * regular file is tried. <BR>
   * Attempting the file first can throw a security execption when a valid URL
   * is passed in. The security exception is due to not have the correct
   * permissions to access the bogus file path. To avoid this the order was
   * reversed to attempt the URL first and only attempt a file open if creating
   * the URL throws a MalformedURLException.
   * 
   * Copied from JarUtil.
   */
  public static InputStream openURL(final String externalPath)
      throws IOException {
    return openURL(null, externalPath);
  }

  public static InputStream openURL(final String scriptPath,
      final String externalPath) throws IOException {
    try {
      return AccessController.doPrivileged(
          new PrivilegedExceptionAction<InputStream>() {

            public InputStream run() throws IOException {
              try {
                return new URL(externalPath).openStream();
              } catch (MalformedURLException mfurle) {
                try {
                  return new FileInputStream(externalPath);
                }
                catch (FileNotFoundException fnfe) {
                  return new FileInputStream(new File(scriptPath,
                      externalPath));
                }
              }
            }
          });
    } catch (PrivilegedActionException e) {
      if (e.getException() instanceof IOException) {
        throw (IOException)e.getException();
      }
      else {
        throw new IOException(e.getException());
      }
    }
  }

  /**
   * Visitor for each string in a comma-separated string.
   * 
   * @author swale
   */
  public static interface CSVVisitor<A, C> {

    /**
     * Visit one string of the list provided as a comma-separated string.
     */
    public void visit(String str, A action, C context);
  }

  /** convert comma separated string to SortedSet */
  public static SortedSet<String> toSortedSet(final String csv,
      final boolean caseSensitive) {
    final TreeSet<String> strings = new TreeSet<String>();
    splitCSV(csv, stringAggregator, strings, Boolean.valueOf(caseSensitive));
    return strings;
  }

  public static final CSVVisitor<Collection<String>, Boolean> stringAggregator =
      new CSVVisitor<Collection<String>, Boolean>() {
    public void visit(String str, Collection<String> strings,
        Boolean caseSensitive) {
      if (caseSensitive.booleanValue()) {
        strings.add(str);
      }
      else {
        strings.add(SQLToUpperCase(str));
      }
    }
  };

  /** invoke a given callback for each string in a comma separated string */
  public static <A, C> void splitCSV(final String csv,
      final CSVVisitor<A, C> visitor, A action, C context) {
    final int csvLen;
    if (csv != null && (csvLen = csv.length()) > 0) {
      int start = 0;
      // skip leading spaces, if any
      while (start < csvLen && Character.isWhitespace(csv.charAt(start))) {
        ++start;
      }
      if (start < csvLen) {
        Matcher mt = csvPattern.matcher(csv);
        int end;
        while (mt.find()) {
          end = mt.start();
          // skip trailing blanks, if any
          while (end > 0 && Character.isWhitespace(csv.charAt(end - 1))) {
            --end;
          }
          final String str = csv.substring(start, end);
          if (str.length() > 0) {
            visitor.visit(str, action, context);
          }
          start = mt.end();
        }
      }
    }
  }

  /** convert a given collection to a comma separated string */
  public static String toCSV(final Collection<?> collection) {
    final StringBuilder csv = new StringBuilder();
    if (collection != null) {
      boolean firstObj = true;
      final Iterator<?> iter = collection.iterator();
      while (iter.hasNext()) {
        if (!firstObj) {
          csv.append(',');
        }
        else {
          firstObj = false;
        }
        csv.append(iter.next());
      }
    }
    return csv.toString();
  }

  /**
   * Split a given host[port] string into host and port parts.
   */
  public static String getHostPort(final String hostPort, final int[] port) {
    final int len = hostPort.length();
    int spos;
    try {
      if ((hostPort.charAt(len - 1)) == ']'
          && (spos = hostPort.indexOf('[')) >= 0) {
        port[0] = Integer.parseInt(hostPort.substring(spos + 1, len - 1));
        return hostPort.substring(0, spos);
      }
      else if ((spos = hostPort.indexOf(':')) >= 0) {
        port[0] = Integer.parseInt(hostPort.substring(spos + 1));
        return hostPort.substring(0, spos);
      }
    } catch (NumberFormatException nfe) {
      throw new NumberFormatException(
          "failed to parse integer port in given host[port] string '"
              + hostPort + "': " + nfe.getLocalizedMessage());
    }
    throw new NumberFormatException("failed to split given host[port] string: "
        + hostPort);
  }

  /**
   * Stripped from InternalDriver.getAttributes
   */
  public static Properties getAttributes(String url, Properties info)
      throws SQLException {

    // We use FormatableProperties here to take advantage
    // of the clearDefaults, method.
    Properties finfo = new Properties(info);
    info = null; // ensure we don't use this reference directly again.

    StringTokenizer st = new StringTokenizer(url, ";");
    st.nextToken(); // skip the first part of the url

    while (st.hasMoreTokens()) {

      String v = st.nextToken();

      int eqPos = v.indexOf('=');
      if (eqPos == -1) {
        throw new SQLException("The URL '" + url + "' is not properly formed.",
            "XJ028", 40000);
      }

      // if (eqPos != v.lastIndexOf('='))
      // throw Util.malformedURL(url);

      finfo
          .put((v.substring(0, eqPos)).trim(), (v.substring(eqPos + 1)).trim());
    }
    return finfo;
  }

  /**
   * Print a stacktrace from the throwable to given writer.
   */
  public static void printStackTrace(Throwable t, PrintWriter pw,
      boolean onlyStack) {
    int level = 0, commonTraceIndex = 0, commonTraceCauseIndex = 0;
    int framesInCommon = 0;
    StackTraceElement[] trace;
    StackTraceElement[] causedTrace = null;
    while (t != null) {
      trace = t.getStackTrace();
      if (level > 0) {
        commonTraceIndex = trace.length - 1;
        commonTraceCauseIndex = causedTrace.length - 1;
        while (commonTraceIndex >= 0
            && commonTraceCauseIndex >= 0
            && trace[commonTraceIndex]
                .equals(causedTrace[commonTraceCauseIndex])) {
          --commonTraceIndex;
          --commonTraceCauseIndex;
        }
        framesInCommon = trace.length - 1 - commonTraceIndex;
        pw.println("============= begin nested exception, level (" + level
            + ") ===========");
      }
      if (onlyStack) {
        pw.println(t.getClass());
      }
      else {
        pw.println(t.toString());
      }
      for (StackTraceElement traceElement : trace) {
        pw.print("\tat ");
        pw.println(traceElement);
        if (level > 0 && commonTraceIndex-- == 0) {
          break;
        }
      }
      if (framesInCommon > 0) {
        pw.println("\t... " + framesInCommon + " more");
      }
      if (t instanceof java.sql.SQLException) {
        Throwable next = ((java.sql.SQLException)t).getNextException();
        t = (next == null) ? t.getCause() : next;
      }
      else {
        t = t.getCause();
      }
      if (level > 0) {
        pw.println("============= end nested exception, level (" + level
            + ") ===========");
      }
      ++level;
      causedTrace = trace;
    }
  }

  public static StopWatch newTimer(long startTime) {
    return startTime > 0 ? new StopWatch(startTime) : null;
  }

  /**
   * Quote a string so that it can be used as an identifier or a string literal
   * in SQL statements. Identifiers are surrounded by double quotes and string
   * literals are surrounded by single quotes. If the string contains quote
   * characters, they are escaped.
   * 
   * @param source
   *          the string to quote
   * @param quote
   *          the character to quote the string with (' or &quot;)
   * @return a string quoted with the specified quote character
   */
  public static String quoteString(String source, char quote) {
    // Normally, the quoted string is two characters longer than the source
    // string (because of start quote and end quote).
    StringBuilder quoted = new StringBuilder(source.length() + 2);
    quoted.append(quote);
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      // if the character is a quote, escape it with an extra quote
      if (c == quote)
        quoted.append(quote);
      quoted.append(c);
    }
    quoted.append(quote);
    return quoted.toString();
  }
}
