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

package com.gemstone.gemfire.internal.shared;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import com.gemstone.gemfire.internal.shared.unsafe.DirectBufferAllocator;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import org.apache.log4j.LogManager;
import org.apache.log4j.PropertyConfigurator;

/**
 * Some shared methods now also used by GemFireXD clients so should not have any
 * dependency on other GemFire packages.
 * 
 * @since 7.5
 */
public abstract class ClientSharedUtils {

  private ClientSharedUtils() {
    // no instance allowed
  }

  /** The name of java.util.logging.Logger for GemFireXD. */
  public static final String LOGGER_NAME = "snappystore";

  /** Optional system property to enable GemFire usage of link-local addresses */
  public static final String USE_LINK_LOCAL_ADDRESSES_PROPERTY =
      "net.useLinkLocalAddresses";

  /** True if GemFire should use link-local addresses */
  private static final boolean useLinkLocalAddresses = SystemProperties
      .getClientInstance().getBoolean(USE_LINK_LOCAL_ADDRESSES_PROPERTY, false);

  /**
   * System property (with "gemfirexd." prefix) to specify that Thrift is to be
   * used as the default for default <code>jdbc:gemfirexd://</code> URL
   */
  public static final String USE_THRIFT_AS_DEFAULT_PROP = "thrift-default";

  /**
   * True if using Thrift as default network server and client, false if using
   * DRDA (default).
   */
  private static boolean USE_THRIFT_AS_DEFAULT = isUsingThrift(true);

  private static final Object[] staticZeroLenObjectArray = new Object[0];

  public static final boolean isLittleEndian = UnsafeHolder.littleEndian;

  /**
   * all classes should use this variable to determine whether to use IPv4 or
   * IPv6 addresses
   */
  private static boolean useIPv6Addresses = !Boolean
      .getBoolean("java.net.preferIPv4Stack")
      && Boolean.getBoolean("java.net.preferIPv6Addresses");

  /** the system line separator */
  public static final String lineSeparator = java.security.AccessController
      .doPrivileged(new PrivilegedAction<String>() {
        @Override
        public String run() {
          return System.getProperty("line.separator");
        }
      });

  /**
   * The default wait to use when waiting to read/write a channel
   * (when there is no selector to signal)
   */
  public static final long PARK_NANOS_FOR_READ_WRITE = 100L;

  /**
   * Retries before waiting for {@link #PARK_NANOS_FOR_READ_WRITE}
   * (when there is no selector to signal)
   */
  public static final int RETRIES_BEFORE_PARK = 20;

  /**
   * Maximum nanos to park thread to wait for reading/writing data in
   * non-blocking mode (if selector is present then it will explicitly signal)
   */
  public static final long PARK_NANOS_MAX = 30000000000L;

  public static boolean isUsingThrift(boolean defaultValue) {
    return SystemProperties.getClientInstance().getBoolean(
        USE_THRIFT_AS_DEFAULT_PROP, defaultValue);
  }

  public static boolean isThriftDefault() {
    return USE_THRIFT_AS_DEFAULT;
  }

  public static void setThriftDefault(boolean defaultValue) {
    USE_THRIFT_AS_DEFAULT = isUsingThrift(defaultValue);
  }

  /** we cache localHost to avoid bug #40619, access-violation in native code */
  private static final InetAddress localHost;

  private static CommonRunTimeException cre;

  // flags below help avoid logging warnings for failures in setting of
  // additional OS-specific socket TCP keep-alive options
  private static boolean socketKeepAliveIdleWarningLogged;
  private static boolean socketKeepAliveIntvlWarningLogged;
  private static boolean socketKeepAliveCntWarningLogged;

  public static char[] HEX_DIGITS = { '0', '1', '2', '3', '4', '5', '6', '7',
      '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
  public static char[] HEX_DIGITS_UCASE = { '0', '1', '2', '3', '4', '5', '6',
      '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

  // ------- Constants for date formatting

  private static final int SECONDS = 1000;

  private static final int MINUTES = SECONDS * 60;

  private static final int HOURS = MINUTES * 60;

  private static final long DAYS = HOURS * 24;

  private static final long NORMAL_YEAR = DAYS * 365L;

  private static final long LEAP_YEAR = NORMAL_YEAR + DAYS;

  private static final long FOURYEARS = (NORMAL_YEAR * 3L) + LEAP_YEAR;

  private static final long END_OF_FIRST_YEAR = NORMAL_YEAR;

  private static final long END_OF_SECOND_YEAR = END_OF_FIRST_YEAR + LEAP_YEAR;

  private static final long END_OF_THIRD_YEAR = END_OF_SECOND_YEAR
      + NORMAL_YEAR;

  private static final int[] DAYS_IN_MONTH = { 31, 28, 31, 30, 31, 30, 31, 31,
      30, 31, 30, 31 };

  private static final int FEBRUARY = 1;

  // ------- End constants for date formatting

  private static volatile Logger _DEFAULT_LOGGER;
  private static Logger logger;
  private static final Properties baseLoggerProperties = new Properties();

  private static final Constructor<?> stringInternalConstructor;
  private static final int stringInternalConsVersion;
  private static final Field bigIntMagnitude;

  // JDK5/6 String(int, int, char[])
  private static final int STRING_CONS_VER1 = 1;
  // JDK7 String(char[], boolean)
  private static final int STRING_CONS_VER2 = 2;
  // GCJ String(char[], int, int, boolean)
  private static final int STRING_CONS_VER3 = 3;

  static {
    InetAddress lh = null;
    try {
      lh = getHostAddress();
    } catch (UnknownHostException e) {
      e.printStackTrace();
    } catch (SocketException ignored) {
    }
    localHost = lh;
    cre = new ClientRunTimeException();

    // string constructor search by reflection
    Constructor<?> constructor = null;
    int version = 0;
    try {
      // first check the JDK7 version since an inefficient version of JDK5/6 is
      // present in JDK7
      constructor = String.class.getDeclaredConstructor(char[].class,
          boolean.class);
      constructor.setAccessible(true);
      version = STRING_CONS_VER2;
    } catch (Exception e1) {
      try {
        // next check the JDK6 version
        constructor = String.class.getDeclaredConstructor(int.class, int.class,
            char[].class);
        constructor.setAccessible(true);
        version = STRING_CONS_VER1;
      } catch (Exception e2) {
        // gcj has a different version
        try {
          constructor = String.class.getDeclaredConstructor(char[].class,
              int.class, int.class, boolean.class);
          constructor.setAccessible(true);
          version = STRING_CONS_VER3;
        } catch (Exception e3) {
          // ignored
        }
      }
    }
    stringInternalConstructor = constructor;
    stringInternalConsVersion = version;

    Field mag;
    try {
      mag = BigInteger.class.getDeclaredField("mag");
      mag.setAccessible(true);
      // check if we really got the required field
      if (mag.getType() != int[].class) {
        mag = null;
      }
    } catch (Exception e) {
      // try words for gcj
      try {
        mag = BigInteger.class.getDeclaredField("words");
        mag.setAccessible(true);
        // check if we really got the required field
        if (mag.getType() != int[].class) {
          mag = null;
        }
      } catch (Exception ex) {
        mag = null;
      }
    }
    bigIntMagnitude = mag;
  }

  private static synchronized Logger DEFAULT_LOGGER() {
    final Logger logger = _DEFAULT_LOGGER;
    if (logger != null) {
      return logger;
    }
    return (_DEFAULT_LOGGER = Logger.getLogger(""));
  }

  private static int getShiftMultipler(char unitChar) {
    switch (Character.toLowerCase(unitChar)) {
      case 'p': return 50;
      case 't': return 40;
      case 'g': return 30;
      case 'm': return 20;
      case 'k': return 10;
      case 'b': return 0;
      default: return -1;
    }
  }

  public static long parseMemorySize(String v, long defaultValue,
      int defaultShiftMultiplier) {
    if (v == null || v.length() == 0) {
      return defaultValue;
    }
    final int len = v.length();
    int unitShiftMultiplier = getShiftMultipler(v.charAt(len - 1));
    int trimChars = unitShiftMultiplier >= 0 ? 1 : 0;
    if (unitShiftMultiplier == 0) {
      // ends with 'b' so could be 'mb', 'gb' etc
      if (len > 1) {
        unitShiftMultiplier = getShiftMultipler(v.charAt(len - 2));
        if (unitShiftMultiplier > 0) {
          trimChars = 2;
        } else {
          unitShiftMultiplier = 0;
        }
      }
    } else if (unitShiftMultiplier < 0) {
      unitShiftMultiplier = defaultShiftMultiplier;
    }
    if (trimChars > 0) {
      v = v.substring(0, len - trimChars);
    }
    try {
      return Long.parseLong(v) << unitShiftMultiplier;
    } catch (NumberFormatException nfe) {
      throw new IllegalArgumentException("Memory size specification = " + v +
          ": memory size must be specified as <n>[p|t|g|m|k], where <n> is " +
          "the size and and [p|t|g|m|k] specifies the units in peta|tera|giga|" +
          "mega|kilobytes. No unit or with 'b' specifies in bytes. " +
          "Each of these units can be followed optionally by 'b' i.e. " +
          "pb|tb|gb|mb|kb.", nfe);
    }
  }

  public static String newWrappedString(final char[] chars, final int offset,
      final int size) {
    if (size >= 0) {
      try {
        switch (stringInternalConsVersion) {
          case STRING_CONS_VER1:
            return (String)stringInternalConstructor.newInstance(offset, size,
                chars);
          case STRING_CONS_VER2:
            if (offset == 0 && size == chars.length) {
              return (String)stringInternalConstructor.newInstance(chars, true);
            }
            else {
              return new String(chars, offset, size);
            }
          case STRING_CONS_VER3:
            return (String)stringInternalConstructor.newInstance(chars, offset,
                size, true);
          default:
            return new String(chars, offset, size);
        }
      } catch (Exception ex) {
        throw ClientSharedUtils.newRuntimeException("unexpected exception", ex);
      }
    }
    else {
      throw new AssertionError("unexpected size=" + size);
    }
  }

  public static String readChars(final InputStream in, final boolean noecho) {
    final Console console;
    if (noecho && (console = System.console()) != null) {
      return new String(console.readPassword());
    } else {
      final StringBuilder sb = new StringBuilder();
      char c;
      try {
        int value = in.read();
        if (value != -1 && (c = (char)value) != '\n' && c != '\r') {
          // keep waiting till a key is pressed
          sb.append(c);
        }
        while (in.available() > 0 && (value = in.read()) != -1
            && (c = (char)value) != '\n' && c != '\r') {
          // consume till end of line
          sb.append(c);
        }
      } catch (IOException ioe) {
        throw ClientSharedUtils.newRuntimeException(ioe.getMessage(), ioe);
      }
      return sb.toString();
    }
  }

  /**
   * All GemFire code should use this method instead of
   * InetAddress.getLocalHost(). See bug #40623
   */
  public static InetAddress getLocalHost() throws UnknownHostException {
    if (localHost == null) {
      throw new UnknownHostException();
    }
    return localHost;
  }

  public static InetAddress getCachedLocalHost() {
    return localHost;
  }

  /** All classes should use this instead of relying on the JRE system property */
  public static boolean preferIPv6Addresses() {
    return useIPv6Addresses;
  }

  /**
   * Tries hard to find a real host address for this machine even if the
   * hostname itself points to a dummy loopback address.
   */
  private static InetAddress getHostAddress() throws UnknownHostException,
      SocketException {
    InetAddress lh = InetAddress.getLocalHost();
    if (lh.isLoopbackAddress()) {
      InetAddress ipv4Fallback = null;
      InetAddress ipv6Fallback = null;
      // try to find a non-loopback address
      Set<InetAddress> myInterfaces = getMyAddresses(false);
      boolean preferIPv6 = useIPv6Addresses;
      String lhName = null;
      for (Iterator<InetAddress> it = myInterfaces.iterator(); lhName == null
          && it.hasNext();) {
        InetAddress addr = it.next();
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()) {
          break;
        }
        boolean ipv6 = addr instanceof Inet6Address;
        boolean ipv4 = addr instanceof Inet4Address;
        if ((preferIPv6 && ipv6) || (!preferIPv6 && ipv4)) {
          String addrName = reverseDNS(addr);
          if (lh.isLoopbackAddress()) {
            lh = addr;
            lhName = addrName;
          }
          else if (addrName != null) {
            lh = addr;
            lhName = addrName;
          }
        }
        else {
          if (preferIPv6 && ipv4 && ipv4Fallback == null) {
            ipv4Fallback = addr;
          }
          else if (!preferIPv6 && ipv6 && ipv6Fallback == null) {
            ipv6Fallback = addr;
          }
        }
      }
      // vanilla Ubuntu installations will have a usable IPv6 address when
      // running as a guest OS on an IPv6-enabled machine. We also look for
      // the alternative IPv4 configuration.
      if (lh.isLoopbackAddress()) {
        if (ipv4Fallback != null) {
          lh = ipv4Fallback;
          useIPv6Addresses = false;
        }
        else if (ipv6Fallback != null) {
          lh = ipv6Fallback;
          useIPv6Addresses = true;
        }
      }
    }
    return lh;
  }

  public static Method getAnyMethod(Class<?> c, String name, Class<?> returnType,
       Class<?>... parameterTypes) throws NoSuchMethodException, SecurityException {
    NoSuchMethodException firstEx = null;
    Method method = null;
    for (;;) {
      try {
        method =  c.getDeclaredMethod(name, parameterTypes);

        if (returnType == null ||
           (returnType != null && method.getReturnType().equals(returnType))) {
          return method;
        } else {
          throw new NoSuchMethodException();
        }
      } catch (NoSuchMethodException nsme) {
        if (firstEx == null) {
          firstEx = nsme;
        }
        if ((c = c.getSuperclass()) == null) {
          throw firstEx;
        }
        // else continue searching in superClass
      }
    }
  }

  public static Field getAnyField(Class<?> c, String name)
          throws NoSuchFieldException, SecurityException {
    NoSuchFieldException firstEx = null;
    for (;;) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException nsfe) {
        if (firstEx == null) {
          firstEx = nsfe;
        }
        if ((c = c.getSuperclass()) == null) {
          throw firstEx;
        }
        // else continue searching in superClass
      }
    }
  }
  /**
   * This method uses JNDI to look up an address in DNS and return its name.
   * 
   * @param addr
   *          the address to be looked up
   * 
   * @return the host name associated with the address or null if lookup isn't
   *         possible or there is no host name for this address
   */
  public static String reverseDNS(InetAddress addr) {
    byte[] addrBytes = addr.getAddress();
    // adjust the address to be suitable for reverse lookup
    StringBuilder lookupsb = new StringBuilder();
    for (int index = addrBytes.length - 1; index >= 0; index--) {
      lookupsb.append(addrBytes[index] & 0xff).append('.');
    }
    lookupsb.append("in-addr.arpa");
    final String lookup = lookupsb.toString();

    DirContext ctx = null;
    try {
      Hashtable<Object, Object> env = new Hashtable<Object, Object>();
      env.put(Context.INITIAL_CONTEXT_FACTORY,
          "com.sun.jndi.dns.DnsContextFactory");
      ctx = new InitialDirContext(env);
      Attributes attrs = ctx.getAttributes(lookup, new String[] { "PTR" });
      for (NamingEnumeration<?> ae = attrs.getAll(); ae.hasMoreElements();) {
        Attribute attr = (Attribute)ae.next();
        for (Enumeration<?> vals = attr.getAll(); vals.hasMoreElements();) {
          Object elem = vals.nextElement();
          if ("PTR".equals(attr.getID()) && elem != null) {
            return elem.toString();
          }
        }
      }
    } catch (Exception e) {
      // ignored
    } finally {
      if (ctx != null) {
        try {
          ctx.close();
        } catch (Exception e) {
          // ignored
        }
      }
    }
    return null;
  }

  /** returns a set of the non-loopback InetAddresses for this machine */
  public static Set<InetAddress> getMyAddresses(boolean includeLocal)
      throws SocketException {
    Set<InetAddress> result = new HashSet<InetAddress>();
    Set<InetAddress> locals = new HashSet<InetAddress>();
    Enumeration<NetworkInterface> interfaces = NetworkInterface
        .getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
      NetworkInterface face = interfaces.nextElement();
      boolean faceIsUp = false;
      try {
        faceIsUp = face.isUp();
      } catch (SocketException se) {
        final Logger log = getLogger();
        if (log != null) {
          LogRecord lr = new LogRecord(Level.INFO,
              "Failed to check if network interface is up. Skipping " + face);
          lr.setThrown(se);
          log.log(lr);
        }
      }
      if (faceIsUp) {
        Enumeration<InetAddress> addrs = face.getInetAddresses();
        while (addrs.hasMoreElements()) {
          InetAddress addr = addrs.nextElement();
          if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
              || (!useLinkLocalAddresses && addr.isLinkLocalAddress())) {
            locals.add(addr);
          }
          else {
            result.add(addr);
          }
        } // while
      }
    } // while
    // fix for bug #42427 - allow product to run on a standalone box by using
    // local addresses if there are no non-local addresses available
    if (result.size() == 0) {
      return locals;
    }
    else {
      if (includeLocal) {
        result.addAll(locals);
      }
      return result;
    }
  }

  /**
   * Set the keep-alive options on the socket from server-side properties.
   *
   * @see #setKeepAliveOptions
   */
  public static void setKeepAliveOptionsServer(Socket socket,
      InputStream socketStream) throws SocketException {
    final SystemProperties props = SystemProperties
        .getServerInstance();
    int defaultIdle = props.getInteger(SystemProperties.KEEPALIVE_IDLE,
        SystemProperties.DEFAULT_KEEPALIVE_IDLE);
    int defaultInterval = props.getInteger(SystemProperties.KEEPALIVE_INTVL,
        SystemProperties.DEFAULT_KEEPALIVE_INTVL);
    int defaultCount = props.getInteger(SystemProperties.KEEPALIVE_CNT,
        SystemProperties.DEFAULT_KEEPALIVE_CNT);
    ClientSharedUtils.setKeepAliveOptions(socket, socketStream,
        defaultIdle, defaultInterval, defaultCount);
  }

  /**
   * Enable TCP KeepAlive settings for the socket. This will use the native OS
   * API to set per-socket configuration, if available, else will log warning
   * (only once) if one or more settings cannot be enabled.
   *
   * @param sock         the underlying Java {@link Socket} to set the keep-alive
   * @param sockStream   the InputStream of the socket (can be null); if non-null then it
   *                     is used to determine the underlying socket kernel handle else if
   *                     null then reflection on the socket itself is used
   * @param keepIdle     keep-alive time between two transmissions on socket in idle
   *                     condition (in seconds)
   * @param keepInterval keep-alive duration between successive transmissions on socket if
   *                     no reply to packet sent after idle timeout (in seconds)
   * @param keepCount    number of retransmissions to be sent before declaring the other
   *                     end to be dead
   * @throws SocketException if the base keep-alive cannot be enabled on the socket
   * @see <a href="http://tldp.org/HOWTO/html_single/TCP-Keepalive-HOWTO/#programming">
   * TCP Keepalive HOWTO</a>
   * @see <a href="http://docs.oracle.com/cd/E19082-01/819-2724/6n50b07lr/index.html">
   * TCP Tunable Parameters</a>
   * @see <a href="http://msdn.microsoft.com/en-us/library/ms741621%28VS.85%29.aspx">
   * WSAIoctl function</a>
   * @see <a href="http://technet.microsoft.com/en-us/library/dd349797%28WS.10%29.aspx>
   * TCP/IP-Related Registry Entries</a>
   * @see <a href="http://msdn.microsoft.com/en-us/library/dd877220%28v=vs.85%29.aspx">
   * SIO_KEEPALIVE_VALS control code</a>
   */
  public static void setKeepAliveOptions(Socket sock, InputStream sockStream,
      int keepIdle, int keepInterval, int keepCount) throws SocketException {
    final Logger log = getLogger();
    if (log != null && log.isLoggable(Level.FINE)) {
      log.fine("setKeepAliveOptions: setting keepIdle=" + keepIdle
          + ", keepInterval=" + keepInterval + ", keepCount=" + keepCount
          + " for " + sock);
    }
    // first enable keep-alive flag
    sock.setKeepAlive(true);
    // now the OS-specific settings using NativeCalls
    NativeCalls nc = NativeCalls.getInstance();
    Map<TCPSocketOptions, Object> optValueMap = new HashMap<>(4);
    if (keepIdle >= 0) {
      optValueMap.put(TCPSocketOptions.OPT_KEEPIDLE, keepIdle);
    }
    if (keepInterval >= 0) {
      optValueMap.put(TCPSocketOptions.OPT_KEEPINTVL, keepInterval);
    }
    if (keepCount >= 0) {
      optValueMap.put(TCPSocketOptions.OPT_KEEPCNT, keepCount);
    }
    Map<TCPSocketOptions, Throwable> failed;
    try {
      failed = nc.setSocketOptions(sock, sockStream, optValueMap);
    } catch (UnsupportedOperationException e) {
      if (!socketKeepAliveIdleWarningLogged) {
        socketKeepAliveIdleWarningLogged = true;
        if (log != null) {
          log.warning("Failed to set options " + optValueMap + " on socket: "
              + e);
          if (log.isLoggable(Level.FINE)) {
            LogRecord lr = new LogRecord(Level.FINE, "Exception trace:");
            lr.setThrown(e);
            log.log(lr);
          }
        }
      }
      return;
    }
    if (failed != null && failed.size() > 0) {
      if (log != null) {
        for (Map.Entry<TCPSocketOptions, Throwable> e : failed.entrySet()) {
          final TCPSocketOptions opt = e.getKey();
          final Throwable ex = e.getValue();
          byte doLogWarning = 0;
          switch (opt) {
            case OPT_KEEPIDLE:
              if (ex instanceof UnsupportedOperationException) {
                if (!socketKeepAliveIdleWarningLogged) {
                  socketKeepAliveIdleWarningLogged = true;
                  // KEEPIDLE is the minumum required for this, so
                  // log as a warning
                  doLogWarning = 1;
                }
              } else {
                doLogWarning = 1;
              }
              break;
            case OPT_KEEPINTVL:
              if (ex instanceof UnsupportedOperationException) {
                if (!socketKeepAliveIntvlWarningLogged) {
                  socketKeepAliveIntvlWarningLogged = true;
                  // KEEPINTVL is not critical to have so log as information
                  doLogWarning = 2;
                }
              } else {
                doLogWarning = 2;
              }
              break;
            case OPT_KEEPCNT:
              if (ex instanceof UnsupportedOperationException) {
                if (!socketKeepAliveCntWarningLogged) {
                  socketKeepAliveCntWarningLogged = true;
                  // KEEPCNT is not critical to have so log as information
                  doLogWarning = 2;
                }
              } else {
                doLogWarning = 2;
              }
              break;
          }
          if (doLogWarning > 0) {
            if (doLogWarning == 1) {
              log.warning("Failed to set " + opt + " on socket: " + ex);
            } else {
              // just log as an information rather than warning
              if (log != DEFAULT_LOGGER()) { // SNAP-255
                log.info("Failed to set " + opt + " on socket: "
                    + ex.getMessage());
              }
            }
            if (log.isLoggable(Level.FINE)) {
              LogRecord lr = new LogRecord(Level.FINE, "Exception trace:");
              lr.setThrown(ex);
              log.log(lr);
            }
          }
        }
      }
    } else if (log != null && log.isLoggable(Level.FINE)) {
      log.fine("setKeepAliveOptions(): successful for " + sock);
    }
  }

  /**
   * Append the backtrace of given exception to provided {@link StringBuilder}.
   * 
   * @param t
   *          the exception whose backtrace is required
   * @param sb
   *          the {@link StringBuilder} to which the stack trace is to be
   *          appended
   * @param lineSep
   *          the line separator to use, or null to use the default from system
   *          "line.separator" property
   */
  public static void getStackTrace(final Throwable t, StringBuilder sb,
      String lineSep) {
    t.printStackTrace(new StringPrintWriter(sb, lineSep));
  }

  public static void dumpThreadStack(final ThreadInfo tInfo,
      final StringBuilder msg, final String lineSeparator) {
    msg.append('"').append(tInfo.getThreadName()).append('"').append(" Id=")
        .append(tInfo.getThreadId()).append(' ')
        .append(tInfo.getThreadState());
    if (tInfo.getLockName() != null) {
      msg.append(" on ").append(tInfo.getLockName());
    }
    if (tInfo.getLockOwnerName() != null) {
      msg.append(" owned by \"").append(tInfo.getLockOwnerName())
          .append("\" Id=").append(tInfo.getLockOwnerId());
    }
    if (tInfo.isSuspended()) {
      msg.append(" (suspended)");
    }
    if (tInfo.isInNative()) {
      msg.append(" (in native)");
    }
    msg.append(lineSeparator);
    final StackTraceElement[] stackTrace = tInfo.getStackTrace();
    for (int index = 0; index < stackTrace.length; ++index) {
      msg.append("\tat ").append(stackTrace[index].toString())
          .append(lineSeparator);
      if (index == 0 && tInfo.getLockInfo() != null) {
        final Thread.State ts = tInfo.getThreadState();
        switch (ts) {
          case BLOCKED:
            msg.append("\t-  blocked on ").append(tInfo.getLockInfo())
                .append(lineSeparator);
            break;
          case WAITING:
            msg.append("\t-  waiting on ").append(tInfo.getLockInfo())
                .append(lineSeparator);
            break;
          case TIMED_WAITING:
            msg.append("\t-  waiting on ").append(tInfo.getLockInfo())
                .append(lineSeparator);
            break;
          default:
        }
      }

      for (MonitorInfo mi : tInfo.getLockedMonitors()) {
        if (mi.getLockedStackDepth() == index) {
          msg.append("\t-  locked ").append(mi)
              .append(lineSeparator);
        }
      }
    }

    final LockInfo[] locks = tInfo.getLockedSynchronizers();
    if (locks.length > 0) {
      msg.append(lineSeparator)
          .append("\tNumber of locked synchronizers = ").append(locks.length)
          .append(lineSeparator);
      for (LockInfo li : locks) {
        msg.append("\t- ").append(li).append(lineSeparator);
      }
    }
    msg.append(lineSeparator);
  }

  public static Object[] getZeroLenObjectArray() {
    return staticZeroLenObjectArray;
  }

  /**
   * The cached timezone of this VM.
   */
  public static final TimeZone currentTimeZone = TimeZone.getDefault();

  /**
   * The cached timezone offset not including daylight savings.
   */
  public static final long currentTimeZoneOffset = currentTimeZone
      .getRawOffset();

  /** short display name of current timezone in current locale */
  public static final String currentTZShortName = currentTimeZone
      .getDisplayName(false, TimeZone.SHORT);

  /** short display name of current timezone+daylight in current locale */
  public static final String currentTZShortNameDST = currentTimeZone
      .getDisplayName(true, TimeZone.SHORT);

  /**
   * Cached current date information so don't have to create new one
   * everytime. It gets refreshed (individually by each thread,
   * if required) in intervals of {@link #CALENDAR_REFRESH_INTERVAL}.
   */
  private static DateTimeStatics currentDate = new DateTimeStatics(
      System.currentTimeMillis());

  private static final class DateTimeStatics {

    final int tzOffset;

    final long lastUpdatedTime;

    final String tzDisplayName;

    DateTimeStatics(final long currentTime) {
      this(currentTimeZone.getOffset(currentTime), currentTime);
    }

    DateTimeStatics(final int tzOffset, final long currentTime) {
      this(tzOffset, currentTime, (currentTimeZoneOffset == tzOffset)
          ? currentTZShortName : currentTZShortNameDST);
    }

    DateTimeStatics(final int tzOffset, final long currentTime,
        final String tzDisplayName) {
      this.tzOffset = tzOffset;
      this.lastUpdatedTime = currentTime;
      this.tzDisplayName = tzDisplayName;
    }
  }

  // we refresh the calendar after some intervals since it is only
  // used to determine the DST offset that will not change frequently
  private final static long CALENDAR_REFRESH_INTERVAL = 1000 * 60;

  public static void formatDate(final long currentTimeInMillis,
      final StringBuilder sb) {
    try {
      final DateTimeStatics dts = currentDate; // non-volatile snapshot
      int tzOffset = dts.tzOffset;
      String tzDisplayName = dts.tzDisplayName;
      if (currentTimeInMillis >
          (dts.lastUpdatedTime + CALENDAR_REFRESH_INTERVAL)) {
        final int newTzOffset = currentTimeZone.getOffset(
            currentTimeInMillis);
        if (tzOffset != newTzOffset) {
          tzOffset = newTzOffset;
          tzDisplayName = (currentTimeZoneOffset == tzOffset)
              ? currentTZShortName : currentTZShortNameDST;
          currentDate = new DateTimeStatics(tzOffset,
              currentTimeInMillis, tzDisplayName); // non-volatile write
        }
      }
      formatDate(currentTimeInMillis, tzOffset, sb);
      sb.append(' ').append(tzDisplayName);
    } catch (Exception e1) {
      // Fix bug 21857
      try {
        sb.append(new Date(currentTimeInMillis).toString());
      } catch (Exception e2) {
        try {
          sb.append(Long.toString(currentTimeInMillis));
        } catch (Exception e3) {
          sb.append("timestampFormatFailed");
        }
      }
    }
  }

  /**
   * Adapted from Derby's CheapDateFormatter to make use of given calendar and
   * make it more efficient using StringBuilder. Also moved to this class to
   * enable sharing between server and client code.
   * 
   * This method formats the current date into a String. The input is a long
   * representing the number of milliseconds since Jan. 1, 1970. The output is a
   * String in the form yyyy/mm/dd hh:mm:ss.ddd GMT.
   * 
   * The purpose of this class is to format date strings without paying the
   * price of instantiating ResourceBundles and Locales, which the
   * java.util.Date class does whenever you format a date string. As a result,
   * the output of this class is not localized, it does not take the local time
   * zone into account, and it is possible that it will not be as accurate as
   * the standard Date class. It is OK to use this method when, for example,
   * formatting timestamps to write to db2j.LOG, but not for manipulating dates
   * in language processing.
   * 
   * @param time
   *          The current time in milliseconds since Jan. 1, 1970
   * @param tzOffset
   *          The offset of TimeZone including DST.
   * @param sb
   *          Append the date formatted as yyyy/mm/dd hh:mm:ss.ddd to the
   *          StringBuilder.
   */
  public static void formatDate(long time, int tzOffset,
      final StringBuilder sb) {

    // adjust the time as per the given zone offset
    time += tzOffset;

    // Assume not a leap year until we know otherwise
    boolean leapYear = false;

    // How many four year periods since Jan. 1, 1970?
    int year = ((int)(time / FOURYEARS)) * 4;

    // How much time is left over after the four-year periods?
    long leftover = time % FOURYEARS;
    time = leftover;

    year += 1970;

    // Does time extend past end of first year in four-year period?
    if (leftover >= END_OF_FIRST_YEAR) {
      ++year;
      time -= NORMAL_YEAR;
    }

    // Does time extend past end of second year in four-year period?
    if (leftover >= END_OF_SECOND_YEAR) {
      ++year;
      time -= NORMAL_YEAR;
    }

    // Does time extend past end of third year in four-year period?
    if (leftover >= END_OF_THIRD_YEAR) {
      ++year;
      time -= LEAP_YEAR;
    }

    // It's a leap year if divisible by 4, unless divisible by 100,
    // unless divisible by 400.
    if ((year % 4) == 0) {
      if ((year % 100) == 0) {
        if ((year % 400) == 0) {
          leapYear = true;
        }
      }
      else {
        leapYear = true;
      }
    }

    // What day of the year is this, starting at 1?
    int days = (int)(time / DAYS) + 1;

    // What month is this, starting at 1?
    int month = 1;
    for (int i = 0; i < DAYS_IN_MONTH.length; i++) {
      int daysInMonth;

      if (leapYear && (i == FEBRUARY)) {
        // February has 29 days in a leap year
        daysInMonth = 29;
      }
      else {
        // Get number of days in next month
        daysInMonth = DAYS_IN_MONTH[i];
      }

      // Is date after the month we are looking at?
      if (days > daysInMonth) {
        // Count number of months
        month++;

        // Subtract number of days in month
        days -= daysInMonth;
      }
      else {
        // Don't bother to look any more - the date is within
        // the current month.
        break;
      }
    }

    // How much time is left after days are accounted for?
    int remaining = (int)(time % DAYS);

    int hours = remaining / HOURS;

    // How much time is left after hours are accounted for?
    remaining %= HOURS;

    int minutes = remaining / MINUTES;

    // How much time is left after minutes are accounted for?
    remaining %= MINUTES;

    int seconds = remaining / SECONDS;

    // How much time is left after seconds are accounted for?
    remaining %= SECONDS;

    sb.append(year).append('/');
    twoDigits(month, sb);
    sb.append('/');
    twoDigits(days, sb);
    sb.append(' ');
    twoDigits(hours, sb);
    sb.append(':');
    twoDigits(minutes, sb);
    sb.append(':');
    twoDigits(seconds, sb);
    sb.append('.');
    threeDigits(remaining, sb);
  }

  private static void twoDigits(int val, StringBuilder sb) {
    if (val < 10) {
      sb.append('0').append(val);
    }
    else {
      sb.append(val);
    }
  }

  private static void threeDigits(int val, StringBuilder sb) {
    if (val < 10) {
      sb.append("00").append(val);
    }
    else if (val < 100) {
      sb.append('0').append(val);
    }
    else {
      sb.append(val);
    }
  }

  /**
   * Convert a byte array to a String with a hexidecimal format. The String may
   * be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param data
   *          byte array
   * @param offset
   *          starting byte (zero based) to convert.
   * @param length
   *          number of bytes to convert.
   * @return the String (with hexidecimal format) form of the byte array
   */
  public static String toHexString(final byte[] data, int offset,
      final int length) {
    final char[] chars = toHexChars(data, offset, length);
    return newWrappedString(chars, 0, chars.length);
  }

  /**
   * Convert a byte array to a String with upper case hexidecimal format. The
   * String may be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param data
   *          byte array
   * @param offset
   *          starting byte (zero based) to convert.
   * @param length
   *          number of bytes to convert.
   * @return the String (with hexidecimal format) form of the byte array
   */
  public static String toHexStringUpperCase(final byte[] data, int offset,
      final int length) {
    final char[] chars = new char[length << 1];
    final int end = offset + length;
    int index = -1;
    while (offset < end) {
      final byte b = data[offset];
      final int highByte = (b & 0xf0) >>> 4;
      final int lowByte = (b & 0x0f);
      chars[++index] = HEX_DIGITS_UCASE[highByte];
      chars[++index] = HEX_DIGITS_UCASE[lowByte];
      ++offset;
    }
    return newWrappedString(chars, 0, chars.length);
  }

  /**
   * Convert a byte array to a char[] with a hexidecimal format. The char[] may
   * be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned char[].
   * 
   * @param data
   *          byte array
   * @param offset
   *          starting byte (zero based) to convert.
   * @param length
   *          number of bytes to convert.
   *          
   * @return the char[] (with hexidecimal format) form of the byte array
   */
  public static char[] toHexChars(final byte[] data, int offset,
      final int length) {
    final char[] chars = new char[length << 1];
    final int end = offset + length;
    int index = 0;
    while (offset < end) {
      index = toHexChars(data[offset], chars, index);
      ++offset;
    }
    return chars;
  }

  /**
   * Convert a ByteBuffer to a String with a hexidecimal format. The String may
   * be converted back to a byte array using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned String.
   * 
   * @param buffer
   *          the ByteBuffer to convert
   * 
   * @return the String (with hexidecimal format) form of the byte array
   */
  public static String toHexString(ByteBuffer buffer) {
    if (wrapsFullArray(buffer)) {
      final byte[] bytes = buffer.array();
      return toHexString(bytes, 0, bytes.length);
    }
    else {
      int pos = buffer.position();
      final int limit = buffer.limit();
      final char[] chars = new char[(limit - pos) << 1];
      int index = 0;
      while (pos < limit) {
        byte b = buffer.get(pos);
        ++pos;
        index = toHexChars(b, chars, index);
      }
      return newWrappedString(chars, 0, chars.length);
    }
  }

  public static boolean wrapsFullArray(ByteBuffer byteBuffer) {
    return byteBuffer.hasArray() && byteBuffer.position() == 0
        && byteBuffer.arrayOffset() == 0
        && byteBuffer.remaining() == byteBuffer.capacity();
  }

  public static String toString(final ByteBuffer buffer) {
    if (buffer != null) {
      StringBuilder sb = new StringBuilder();
      final int len = buffer.remaining();
      for (int i = buffer.position(); i < len; i++) {
        // terminate with ... for large number of bytes
        if (i > 128 * 1024) {
          sb.append(" ...");
          break;
        }
        sb.append(buffer.get(i)).append(", ");
      }
      return sb.toString();
    } else {
      return "null";
    }
  }

  /**
   * Convert a ByteBuffer to a string appending to given {@link StringBuilder}
   * with a hexidecimal format. The string may be converted back to a byte array
   * using fromHexString. <BR>
   * For each byte (b) two characaters are generated, the first character
   * represents the high nibble (4 bits) in hexidecimal (<code>b & 0xf0</code>),
   * the second character represents the low nibble (<code>b & 0x0f</code>). <BR>
   * The byte at <code>data[offset]</code> is represented by the first two
   * characters in the returned string.
   * 
   * @param buffer
   *          the ByteBuffer to convert
   * @param sb
   *          the StringBuilder to which conversion result is appended
   */
  public static void toHexString(ByteBuffer buffer, StringBuilder sb) {
    if (wrapsFullArray(buffer)) {
      final byte[] bytes = buffer.array();
      sb.append(toHexChars(bytes, 0, bytes.length));
    } else {
      final int limit = buffer.limit();
      for (int pos = buffer.position(); pos < limit; pos++) {
        byte b = buffer.get(pos);
        toHexChars(b, sb);
      }
    }
  }

  static int toHexChars(final byte b, final char[] chars, int index) {
    final int highByte = (b & 0xf0) >>> 4;
    final int lowByte = (b & 0x0f);
    chars[index] = HEX_DIGITS[highByte];
    chars[++index] = HEX_DIGITS[lowByte];
    return (index + 1);
  }

  static void toHexChars(final byte b, StringBuilder sb) {
    final int highByte = (b & 0xf0) >>> 4;
    final int lowByte = (b & 0x0f);
    sb.append(HEX_DIGITS[highByte]);
    sb.append(HEX_DIGITS[lowByte]);
  }

  /**
   * Convert a hexidecimal string generated by toHexString() back into a byte
   * array.
   * 
   * @param s
   *          String to convert
   * @param offset
   *          starting character (zero based) to convert
   * @param length
   *          number of characters to convert
   * 
   * @return the converted byte array
   * 
   * @throws IllegalArgumentException
   *           if the given string is not a hex-encoded one
   */
  public static byte[] fromHexString(final String s, int offset,
      final int length) {
    int j = 0;
    final byte[] bytes;
    if ((length % 2) != 0) {
      // prepend a zero at the start
      bytes = new byte[(length + 1) >>> 1];
      final int lowByte = hexDigitToBinary(s.charAt(offset));
      bytes[j++] = (byte)(lowByte & 0x0f);
      offset++;
    }
    else {
      bytes = new byte[length >>> 1];
    }

    final int end = offset + length;
    while (offset < end) {
      final int highByte = hexDigitToBinary(s.charAt(offset++));
      final int lowByte = hexDigitToBinary(s.charAt(offset++));
      bytes[j++] = (byte)(((highByte << 4) & 0xf0) | (lowByte & 0x0f));
    }
    return bytes;
  }

  static final int hexDigitToBinary(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    else if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    else if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    else {
      throw new IllegalArgumentException("Illegal hexadecimal char=" + c);
    }
  }

  public static void setCommonRuntimeException(CommonRunTimeException e) {
    cre = e;
  }

  public static RuntimeException newRuntimeException(String message,
      Throwable cause) {
    return cre.newRunTimeException(message, cause);
  }

  // Convert log4j.Level to java.util.logging.Level
  public static Level convertToJavaLogLevel(org.apache.log4j.Level log4jLevel) {
    Level javaLevel = Level.INFO;
    if (log4jLevel != null) {
      if (log4jLevel == org.apache.log4j.Level.ERROR) {
        javaLevel = Level.SEVERE;
      } else if (log4jLevel == org.apache.log4j.Level.WARN) {
        javaLevel = Level.WARNING;
      } else if (log4jLevel == org.apache.log4j.Level.INFO) {
        javaLevel = Level.INFO;
      } else if (log4jLevel == org.apache.log4j.Level.TRACE) {
        javaLevel = Level.FINE;
      } else if (log4jLevel == org.apache.log4j.Level.DEBUG) {
        javaLevel = Level.ALL;
      } else if (log4jLevel == org.apache.log4j.Level.OFF) {
        javaLevel = Level.OFF;
      }
    }
    return javaLevel;
  }

  public static String convertToLog4LogLevel(Level level) {
    String levelStr = "INFO";
    // convert to log4j level
    if (level == Level.SEVERE) {
      levelStr = "ERROR";
    } else if (level == Level.WARNING) {
      levelStr = "WARN";
    } else if (level == Level.INFO || level == Level.CONFIG) {
      levelStr = "INFO";
    } else if (level == Level.FINE || level == Level.FINER ||
        level == Level.FINEST) {
      levelStr = "TRACE";
    } else if (level == Level.ALL) {
      levelStr = "DEBUG";
    } else if (level == Level.OFF) {
      levelStr = "OFF";
    }
    return levelStr;
  }

  public static void initLog4J(String logFile,
      Level level) throws IOException {
    initLog4J(logFile, null, level);
  }

  public static Properties getLog4jConfProperties(
      String snappyHome) throws IOException {
    Path confFile = Paths.get(snappyHome, "conf", "log4j.properties");
    if (Files.isReadable(confFile)) {
      try (InputStream in = Files.newInputStream(confFile)) {
        Properties props = new Properties();
        props.load(in);
        return props;
      }
    }
    return null;
  }

  private static Properties getLog4JProperties(String logFile,
      Level level) throws IOException {
    // check for user provided properties file in "conf/"
    String snappyHome = NativeCalls.getInstance().getEnvironment("SNAPPY_HOME");
    if (snappyHome != null) {
      Properties props = getLog4jConfProperties(snappyHome);
      if (props != null) {
        return props;
      }
    }

    Properties props = new Properties();
    // fallback to defaults
    try (InputStream in = ClientSharedUtils.class.getResourceAsStream(
        "/store-log4j.properties")) {
      props.load(in);
    }

    // override file location and level
    if (level != null) {
      final String levelStr = convertToLog4LogLevel(level);
      if (logFile != null) {
        props.setProperty("log4j.rootCategory", levelStr + ", file");
      } else {
        props.setProperty("log4j.rootCategory", levelStr + ", console");
      }
    }
    if (logFile != null) {
      props.setProperty("log4j.appender.file.file", logFile);
    }
    // override with any user provided properties file
    try (InputStream in = ClientSharedUtils.class.getResourceAsStream(
        "/log4j.properties")) {
      if (in != null) {
        Properties setProps = new Properties();
        setProps.load(in);
        props.putAll(setProps);
      }
    }
    return props;
  }

  public static synchronized void initLog4J(String logFile,
      Properties userProps, Level level) throws IOException {
    Properties props;
    if (baseLoggerProperties.isEmpty() || logFile != null) {
      props = getLog4JProperties(logFile, level);
      baseLoggerProperties.clear();
      baseLoggerProperties.putAll(props);
    } else {
      props = (Properties)baseLoggerProperties.clone();
    }
    if (userProps != null) {
      props.putAll(userProps);
    }
    LogManager.resetConfiguration();
    PropertyConfigurator.configure(props);
  }

  public static synchronized void initLogger(String loggerName, String logFile,
      boolean initLog4j, boolean skipIfInitialized, Level level,
      final Handler handler) {
    Logger log = logger;
    if (skipIfInitialized && log != null && log != DEFAULT_LOGGER()) {
      return;
    }
    clearLogger();
    if (initLog4j) {
      try {
        initLog4J(logFile, level);
      } catch (IOException ioe) {
        throw newRuntimeException(ioe.getMessage(), ioe);
      }
    }
    log = Logger.getLogger(loggerName);
    log.addHandler(handler);
    log.setLevel(level);
    log.setUseParentHandlers(false);
    logger = log;
  }

  public static synchronized void setLogger(Logger log) {
    clearLogger();
    logger = log;
  }

  public static boolean isLoggerInitialized() {
    final Logger log = logger;
    return log != null && log != DEFAULT_LOGGER();
  }

  public static Logger getLogger() {
    Logger log = logger;
    if (log == null) {
      logger = log = DEFAULT_LOGGER();
    }
    return log;
  }

  public static final long MAG_MASK = 0xFFFFFFFFL;

  /**
   * Returns the internal int[] magnitude from given BigInteger, if possble,
   * else returns null.
   */
  public static int[] getBigIntInternalMagnitude(final BigInteger val) {
    if (bigIntMagnitude != null) {
      try {
        return (int[])bigIntMagnitude.get(val);
      } catch (Exception ex) {
        throw newRuntimeException("unexpected exception", ex);
      }
    }
    else {
      return null;
    }
  }

  public static int getBigIntMagnitudeSizeInBytes(final int[] magnitude) {
    final int size_1 = magnitude.length - 1;
    if (size_1 >= 0) {
      int numBytes = size_1 << 2;
      // check number of bytes encoded by first int (most significant value)
      final long firstInt = (magnitude[0] & MAG_MASK);
      return numBytes + numBytesWithoutZeros(firstInt);
    }
    return 0;
  }

  /**
   * Get the number of bytes occupied in the given unsigned integer value.
   */
  public static final int numBytesWithoutZeros(final long value) {
    if (value <= 0xFF) {
      return 1;
    }
    if (value <= 0xFFFF) {
      return 2;
    }
    if (value <= 0xFFFFFF) {
      return 3;
    }
    return 4;
  }

  public static void clear() {
    clearLogger();
    socketKeepAliveIdleWarningLogged = false;
    socketKeepAliveIntvlWarningLogged = false;
    socketKeepAliveCntWarningLogged = false;
  }

  private static synchronized void clearLogger() {
    final Logger log = logger;
    if (log != null && log != DEFAULT_LOGGER()) {
      for (Handler h : log.getHandlers()) {
        log.removeHandler(h);
        // try and close the handler ignoring any exceptions
        try {
          h.close();
        } catch (Exception ignore) {
        }
      }
    }
    logger = null;
    baseLoggerProperties.clear();
  }

  /**
   * Get proper string for an an object including arrays with upto one dimension
   * of arrays.
   */
  public static void objectStringNonRecursive(Object obj, StringBuilder sb) {
    if (obj instanceof Object[]) {
      sb.append('(');
      boolean first = true;
      for (Object o : (Object[])obj) {
        if (!first) {
          sb.append(',');
          sb.append(o);
        }
        else {
          first = false;
          // for GemFireXD show the first byte[] for byte[][] storage
          objectStringWithBytes(o, sb);
        }
      }
      sb.append(')');
    }
    else {
      objectStringWithBytes(obj, sb);
    }
  }
  
  /** Get proper string for an object including arrays. */
  public static void objectString(Object obj, StringBuilder sb) {
    if (obj instanceof Object[]) {
      sb.append('(');
      boolean first = true;
      for (Object o : (Object[])obj) {
        if (!first) {
          sb.append(',');
        }
        else {
          first = false;
        }
        objectString(o, sb);
      }
      sb.append(')');
    }
    else {
      objectStringWithBytes(obj, sb);
    }
  }

  private static void objectStringWithBytes(Object obj, StringBuilder sb) {
    if (obj instanceof byte[]) {
      sb.append('(');
      boolean first = true;
      final byte[] bytes = (byte[])obj;
      int numBytes = 0;
      for (byte b : bytes) {
        if (!first) {
          sb.append(',');
        }
        else {
          first = false;
        }
        sb.append(b);
        // terminate with ... for large number of bytes
        if (numBytes++ >= 5000 && numBytes < bytes.length) {
          sb.append(" ...");
          break;
        }
      }
      sb.append(')');
    }
    else {
      sb.append(obj);
    }
  }

  public static byte[] toBytes(ByteBuffer buffer) {
    final int bufferSize = buffer.remaining();
    return toBytes(buffer, bufferSize, bufferSize);
  }

  public static byte[] toBytes(ByteBuffer buffer, int bufferSize, int length) {
    if (length >= bufferSize && wrapsFullArray(buffer)) {
      return buffer.array();
    } else {
      return toBytesCopy(buffer, bufferSize, length);
    }
  }

  public static byte[] toBytesCopy(ByteBuffer buffer, int bufferSize,
      int length) {
    final int numBytes = Math.min(bufferSize, length);
    final byte[] bytes = new byte[numBytes];
    final int initPosition = buffer.position();
    buffer.get(bytes, 0, numBytes);
    buffer.position(initPosition);
    return bytes;
  }

  /**
   * State constants used by the FSM inside getStatementToken.
   *
   * @see #getStatementToken
   */
  private static final int TOKEN_OUTSIDE = 0;
  private static final int TOKEN_INSIDE_SIMPLECOMMENT = 1;
  private static final int TOKEN_INSIDE_BRACKETED_COMMENT = 2;

  /**
   * Minion of getStatementToken. If the input string starts with an
   * identifier consisting of letters only (like "select", "update"..),return
   * it, else return supplied string.
   *
   * @param sql input string
   * @return identifier or unmodified string
   * @see #getStatementToken
   */
  private static String isolateAnyInitialIdentifier(String sql) {
    int idx;
    for (idx = 0; idx < sql.length(); idx++) {
      char ch = sql.charAt(idx);
      if (!Character.isLetter(ch)
          && ch != '<' /* for <local>/<global> tags */) {
        // first non-token char found
        break;
      }
    }
    // return initial token if one is found, or the entire string otherwise
    return (idx > 0) ? sql.substring(0, idx) : sql;
  }

  /**
   * Moved from Derby's <code>Statement.getStatementToken</code> to shared between
   * client and server code.
   * <p>
   * Step past any initial non-significant characters to find first
   * significant SQL token so we can classify statement.
   *
   * @return first significant SQL token
   */
  public static String getStatementToken(String sql, int idx) {
    int bracketNesting = 0;
    int state = TOKEN_OUTSIDE;
    String tokenFound = null;
    char next;

    final int sqlLen = sql.length();
    while (idx < sqlLen && tokenFound == null) {
      next = sql.charAt(idx);

      switch (state) {
        case TOKEN_OUTSIDE:
          switch (next) {
            case '\n':
            case '\t':
            case '\r':
            case '\f':
            case ' ':
            case '(':
            case '{': // JDBC escape characters
            case '=': //
            case '?': //
              idx++;
              break;
            case '/':
              if (idx == sql.length() - 1) {
                // no more characters, so this is the token
                tokenFound = "/";
              } else if (sql.charAt(idx + 1) == '*') {
                state = TOKEN_INSIDE_BRACKETED_COMMENT;
                bracketNesting++;
                idx++; // step two chars
              }

              idx++;
              break;
            case '-':
              if (idx == sql.length() - 1) {
                // no more characters, so this is the token
                tokenFound = "/";
              } else if (sql.charAt(idx + 1) == '-') {
                state = TOKEN_INSIDE_SIMPLECOMMENT;
                idx++;
              }

              idx++;
              break;
            default:
              // a token found
              tokenFound = isolateAnyInitialIdentifier(
                  sql.substring(idx));

              break;
          }

          break;
        case TOKEN_INSIDE_SIMPLECOMMENT:
          switch (next) {
            case '\n':
            case '\r':
            case '\f':

              state = TOKEN_OUTSIDE;
              idx++;

              break;
            default:
              // anything else inside a simple comment is ignored
              idx++;
              break;
          }

          break;
        case TOKEN_INSIDE_BRACKETED_COMMENT:
          switch (next) {
            case '/':
              if (idx != sql.length() - 1 &&
                  sql.charAt(idx + 1) == '*') {

                bracketNesting++;
                idx++; // step two chars
              }
              idx++;

              break;
            case '*':
              if (idx != sql.length() - 1 &&
                  sql.charAt(idx + 1) == '/') {

                bracketNesting--;

                if (bracketNesting == 0) {
                  state = TOKEN_OUTSIDE;
                  idx++; // step two chars
                }
              }

              idx++;
              break;
            default:
              idx++;
              break;
          }

          break;
        default:
          break;
      }
    }

    return tokenFound;
  }

  public static boolean equalBuffers(final byte[] bytes,
      final ByteBuffer buffer) {
    final int len = bytes.length;
    if (len != buffer.remaining()) {
      return false;
    }
    // read in longs to minimize ByteBuffer get() calls
    int pos = buffer.position();
    final int endPos = (pos + len);
    final boolean sameOrder = ByteOrder.nativeOrder() == buffer.order();
    // round off to nearest factor of 8 to read in longs
    final int endRound8Pos = (len % 8) != 0 ? (endPos - 8) : endPos;
    long indexPos = UnsafeHolder.getByteArrayOffset();
    while (pos < endRound8Pos) {
      // splitting into longs is faster than reading one byte at a time even
      // though it costs more operations (about 20% in micro-benchmarks)
      final long s = UnsafeHolder.getUnsafe().getLong(bytes, indexPos);
      final long v = buffer.getLong(pos);
      if (sameOrder) {
        if (s != v) {
          return false;
        }
      } else if (s != Long.reverseBytes(v)) {
        return false;
      }
      pos += 8;
      indexPos += 8;
    }
    while (pos < endPos) {
      if (UnsafeHolder.getUnsafe().getByte(bytes, indexPos) != buffer.get(pos)) {
        return false;
      }
      pos++;
      indexPos++;
    }
    return true;
  }

  /**
   * Allocate new ByteBuffer if capacity of given ByteBuffer has exceeded.
   * The passed ByteBuffer may no longer be usable after this call.
   */
  public static ByteBuffer ensureCapacity(ByteBuffer buffer,
      int newLength, boolean useDirectBuffer, String owner) {
    if (newLength <= buffer.capacity()) {
      return buffer;
    }
    BufferAllocator allocator = useDirectBuffer ? DirectBufferAllocator.instance()
        : HeapBufferAllocator.instance();
    ByteBuffer newBuffer = allocator.allocate(newLength, owner);
    newBuffer.order(buffer.order());
    buffer.flip();
    newBuffer.put(buffer);
    allocator.release(buffer);
    return newBuffer;
  }

  public static int getUTFLength(final String str, final int strLen) {
    int utfLen = strLen;
    for (int i = 0; i < strLen; i++) {
      final char c = str.charAt(i);
      if ((c >= 0x0001) && (c <= 0x007F)) {
        // 1 byte for character
        continue;
      } else if (c > 0x07FF) {
        utfLen += 2; // 3 bytes for character
      } else {
        utfLen++; // 2 bytes for character
      }
    }
    return utfLen;
  }

  public static boolean isSocketToSameHost(Channel channel) {
    try {
      if (channel instanceof SocketChannel) {
        SocketChannel socketChannel = (SocketChannel)channel;
        return isSocketToSameHost(socketChannel.getLocalAddress(),
            socketChannel.getRemoteAddress());
      }
    } catch (IOException ignored) {
    }
    return false;
  }

  public static boolean isSocketToSameHost(SocketAddress localSockAddress,
      SocketAddress remoteSockAddress) {
    if ((localSockAddress instanceof InetSocketAddress) &&
        (remoteSockAddress instanceof InetSocketAddress)) {
      InetAddress localAddress = ((InetSocketAddress)localSockAddress)
          .getAddress();
      return localAddress != null && localAddress.equals(
          ((InetSocketAddress)remoteSockAddress).getAddress());
    } else {
      return false;
    }
  }

  public static long parkThreadForAsyncOperationIfRequired(
      final StreamChannel channel, long parkedNanos, int numTries)
      throws SocketTimeoutException {
    // at this point we are out of the selector thread and don't want to
    // create unlimited size buffers upfront in selector, so will use
    // simple signalling between selector and this thread to proceed
    if ((numTries % RETRIES_BEFORE_PARK) == 0) {
      if (channel != null) {
        channel.setParkedThread(Thread.currentThread());
      }
      LockSupport.parkNanos(PARK_NANOS_FOR_READ_WRITE);
      if (channel != null) {
        channel.setParkedThread(null);
        if ((parkedNanos += ClientSharedUtils.PARK_NANOS_FOR_READ_WRITE) >
            channel.getParkNanosMax()) {
          throw new SocketTimeoutException("Connection operation timed out.");
        }
      }
    }
    return parkedNanos;
  }

  public static final ThreadLocal ALLOW_THREADCONTEXT_CLASSLOADER =
      new ThreadLocal();

  /**
   * allow using Thread context ClassLoader to load classes
   */
  public static final class ThreadContextObjectInputStream extends
      ObjectInputStream {

    protected ThreadContextObjectInputStream() throws IOException,
        SecurityException {
      super();
    }

    public ThreadContextObjectInputStream(final InputStream in)
        throws IOException {
      super(in);
    }

    protected Class resolveClass(final ObjectStreamClass desc)
        throws IOException, ClassNotFoundException {
      try {
        return super.resolveClass(desc);
      } catch (ClassNotFoundException cnfe) {
        // try to load using Thread context ClassLoader, if required
        final Object allowTCCL = ALLOW_THREADCONTEXT_CLASSLOADER.get();
        if (allowTCCL == null || !Boolean.TRUE.equals(allowTCCL)) {
          throw cnfe;
        } else {
          return Thread.currentThread().getContextClassLoader()
              .loadClass(desc.getName());
        }
      }
    }
  }
}
