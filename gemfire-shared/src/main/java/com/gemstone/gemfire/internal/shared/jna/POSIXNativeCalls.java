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

package com.gemstone.gemfire.internal.shared.jna;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.NativeErrorException;
import com.gemstone.gemfire.internal.shared.TCPSocketOptions;
import com.sun.jna.Callback;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

/**
 * Implementation of {@link NativeCalls} for POSIX compatible platforms.
 */
class POSIXNativeCalls extends NativeCalls {

  static {
    Native.register(Platform.C_LIBRARY_NAME);
  }

  public static native int setenv(String name, String value, int overwrite)
      throws LastErrorException;

  public static native int unsetenv(String name) throws LastErrorException;

  public static native String getenv(String name);

  public static native int chdir(String path) throws LastErrorException;

  public static native int getpid();

  public static native int kill(int processId, int signal)
      throws LastErrorException;

  public static native int setsid() throws LastErrorException;

  public static native int umask(int mask);

  public static native int signal(int signum, SignalHandler handler);

  public static native int setsockopt(int sockfd, int level, int optName,
      IntByReference optVal, int optSize) throws LastErrorException;

  public static native int close(int fd) throws LastErrorException;

  public static native int isatty(int fd);

  public static native int getrlimit(int resource, RLimit rlim)
      throws LastErrorException;

  /**
   * Invokes mlockall(). Locks all pages mapped into the address space of the
   * calling process. This includes the pages of the code, data and stack
   * segment, as well as shared libraries, user space kernel data, shared
   * memory, and memory-mapped files. All mapped pages are guaranteed to be
   * resident in RAM when the call returns successfully; the pages are
   * guaranteed to stay in RAM until later unlocked.
   *
   * @param flags MCL_CURRENT 1 - Lock all pages which are currently mapped into
   *              the address space of the process.
   *              <p>
   *              MCL_FUTURE 2 - Lock all pages which will become mapped into the
   *              address space of the process in the future. These could be for
   *              instance new pages required by a growing heap and stack as well
   *              as new memory mapped files or shared memory regions.
   * @return 0 if success, non-zero if error and errno set
   */
  public static native int mlockall(int flags);

  private static final int EPERM = 1;
  private static final int ENOMEM = 12;
  private static final int ENOSPC = 28;

  private static final int SIGHUP = 1;
  private static final int SIGINT = 2;
  private static final int SIGCHLD = 20;
  private static final int SIGTSTP = 18;
  private static final int SIGTTOU = 21;
  private static final int SIGTTIN = 22;

  // for mlockall flags
  private static final int MCL_CURRENT = 1;
  private static final int MCL_FUTURE = 2;

  public static class RLimit extends Structure {
    public long rlim_cur;
    public long rlim_max;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("rlim_cur", "rlim_max");
    }
  }

  private static final Map<String, String> javaEnv = getModifiableJavaEnv();

  /**
   * Signal callback handler for <code>signal</code> native call.
   */
  interface SignalHandler extends Callback {
    void callback(int signum);
  }

  /**
   * holds a reference of {@link SignalHandler} installed in
   * {@link #daemonize} for SIGHUP to avoid it being GCed. Assumes that the
   * NativeCalls instance itself is a singleton and never GCed.
   */
  private SignalHandler hupHandler;

  protected final RLimit rlimit = new RLimit();

  /**
   * the <code>RehashServerOnSIGHUP</code> instance provided to
   * {@link #daemonize}
   */
  private RehashServerOnSIGHUP rehashCallback;

  /**
   * @see NativeCalls#getOSType()
   */
  @Override
  public OSType getOSType() {
    return OSType.GENERIC_POSIX;
  }

  /**
   * @see NativeCalls#setEnvironment(String, String)
   */
  @Override
  public synchronized void setEnvironment(final String name,
      final String value) {
    if (name == null) {
      throw new UnsupportedOperationException(
          "setEnvironment() for name=NULL");
    }
    int res = -1;
    Throwable cause = null;
    try {
      if (value != null) {
        res = setenv(name, value, 1);
      } else {
        res = unsetenv(name);
      }
    } catch (LastErrorException le) {
      cause = new NativeErrorException(le.getMessage(), le.getErrorCode(),
          le.getCause());
    }
    if (res != 0) {
      throw new IllegalArgumentException("setEnvironment: given name=" + name
          + " (value=" + value + ')', cause);
    }
    // also change in java cached map
    if (javaEnv != null) {
      if (value != null) {
        javaEnv.put(name, value);
      } else {
        javaEnv.remove(name);
      }
    }
  }

  /**
   * @see NativeCalls#setCurrentWorkingDirectory(String)
   */
  @Override
  public void setCurrentWorkingDirectory(String path)
      throws LastErrorException {
    // set the java property separately in any case
    System.setProperty("user.dir", path);
    // now change the OS path
    chdir(path);
  }

  /**
   * @see NativeCalls#getEnvironment(String)
   */
  @Override
  public synchronized String getEnvironment(final String name) {
    if (name == null) {
      throw new UnsupportedOperationException(
          "getEnvironment() for name=NULL");
    }
    return getenv(name);
  }

  /**
   * @see NativeCalls#getProcessId()
   */
  @Override
  public int getProcessId() {
    return getpid();
  }

  /**
   * @see NativeCalls#isProcessActive(int)
   */
  @Override
  public boolean isProcessActive(final int processId) {
    try {
      return super.isProcessActive(processId);
    } catch (UnsupportedOperationException ignored) {
      // ignore and try "kill -0"
    }
    try {
      return kill(processId, 0) == 0;
    } catch (LastErrorException le) {
      if (le.getErrorCode() == EPERM) {
        // Process exists; it probably belongs to another user (bug 27698).
        return true;
      }
    }
    return false;
  }

  /**
   * @see NativeCalls#killProcess(int)
   */
  @Override
  public boolean killProcess(final int processId) {
    try {
      return kill(processId, 9) == 0;
    } catch (LastErrorException le) {
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void daemonize(RehashServerOnSIGHUP callback)
      throws UnsupportedOperationException {
    UnsupportedOperationException err = null;
    try {
      setsid();
    } catch (LastErrorException le) {
      // errno=EPERM indicates already group leader
      if (le.getErrorCode() != EPERM) {
        err = new UnsupportedOperationException(
            "Failed in setsid() in daemonize() due to " + le.getMessage()
                + " (errno=" + le.getErrorCode() + ')');
      }
    }
    // set umask to something consistent for servers
    final int newMask = 022;
    int oldMask = umask(newMask);
    // check if old umask was more restrictive, and if so then set it back
    if ((oldMask & 077) > newMask) {
      umask(oldMask);
    }
    // catch the SIGHUP signal and invoke any callback provided
    this.rehashCallback = callback;
    this.hupHandler = new SignalHandler() {
      @Override
      public void callback(int signum) {
        // invoke the rehash function if provided
        final RehashServerOnSIGHUP rehashCb = rehashCallback;
        if (signum == SIGHUP && rehashCb != null) {
          rehashCb.rehash();
        }
      }
    };
    signal(SIGHUP, this.hupHandler);
    // ignore SIGCHLD and SIGINT
    signal(SIGCHLD, this.hupHandler);
    signal(SIGINT, this.hupHandler);
    // ignore tty signals
    signal(SIGTSTP, this.hupHandler);
    signal(SIGTTOU, this.hupHandler);
    signal(SIGTTIN, this.hupHandler);
    if (err != null) {
      throw err;
    }
  }

  @Override
  public void preBlow(String path, long maxSize, boolean preAllocate)
      throws IOException {
    final Logger logger = ClientSharedUtils.getLogger();
    if (logger != null && logger.isLoggable(Level.FINE)) {
      logger.fine("DEBUG preBlow called for path = " + path);
    }
    if (!preAllocate || !hasFallocate()) {
      super.preBlow(path, maxSize, preAllocate);
      if (logger != null && logger.isLoggable(Level.FINE)) {
        logger.fine("DEBUG preBlow super.preBlow 1 called for path = "
            + path);
      }
      return;
    }
    int fd = -1;
    boolean unknownError = false;
    try {
      fd = createFD(path, 00644);
      if (!isOnLocalFileSystem(path)) {
        super.preBlow(path, maxSize, preAllocate);
        if (logger != null && logger.isLoggable(Level.FINE)) {
          logger.fine("DEBUG preBlow super.preBlow 2 called as path = "
              + path + " not on local file system");
        }
        if (TEST_NO_FALLOC_DIRS != null) {
          TEST_NO_FALLOC_DIRS.add(path);
        }
        return;
      }
      fallocateFD(fd, 0L, maxSize);
      if (TEST_CHK_FALLOC_DIRS != null) {
        TEST_CHK_FALLOC_DIRS.add(path);
      }
      if (logger != null && logger.isLoggable(Level.FINE)) {
        logger.fine("DEBUG preBlow posix_fallocate called for path = " + path
            + " and ret = 0 maxsize = " + maxSize);
      }
    } catch (LastErrorException le) {
      if (logger != null && logger.isLoggable(Level.FINE)) {
        logger.fine("DEBUG preBlow posix_fallocate called for path = " + path
            + " and ret = " + le.getErrorCode() + " maxsize = " + maxSize);
      }
      // check for no space left on device
      if (le.getErrorCode() == ENOSPC) {
        unknownError = false;
        throw new IOException("Not enough space left on device");
      } else {
        unknownError = true;
      }
    } finally {
      if (fd >= 0) {
        try {
          close(fd);
        } catch (Exception e) {
          // ignore
        }
      }
      if (unknownError) {
        super.preBlow(path, maxSize, preAllocate);
        if (logger != null && logger.isLoggable(Level.FINE)) {
          logger.fine("DEBUG preBlow super.preBlow 3 called for path = "
              + path);
        }
      }
    }
  }

  protected boolean hasFallocate() {
    return false;
  }

  protected int createFD(String path, int flags) throws LastErrorException {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  protected void fallocateFD(int fd, long offset, long len)
      throws LastErrorException {
    throw new UnsupportedOperationException("not expected to be invoked");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<TCPSocketOptions, Throwable> setSocketOptions(Socket sock,
      InputStream sockStream, Map<TCPSocketOptions, Object> optValueMap)
      throws UnsupportedOperationException {
    return super.setGenericSocketOptions(sock, sockStream, optValueMap);
  }

  @Override
  protected int setPlatformSocketOption(int sockfd, int level, int optName,
      TCPSocketOptions opt, Integer optVal, int optSize)
      throws NativeErrorException {
    try {
      return setsockopt(sockfd, level, optName,
          new IntByReference(optVal), optSize);
    } catch (LastErrorException le) {
      throw new NativeErrorException(le.getMessage(), le.getErrorCode(),
          le.getCause());
    }
  }

  @Override
  public boolean isTTY() {
    return isatty(0) == 1;
  }

  @Override
  public void lockCurrentMemory() {
    if (mlockall(MCL_CURRENT) == 0) {
      return;
    }

    final int errno = Native.getLastError();
    final String msg, reason;
    if (errno == EPERM) {
      reason = "insufficient privileges";
    } else if (errno == ENOMEM) {
      reason = "insufficient free space";
    } else {
      reason = "errno=" + errno;
    }
    msg = "Unable to lock memory due to " + reason
        + ". Please check the RLIMIT_MEMLOCK soft resource limit "
        + "(ulimit -l) and increase the available memory if needed.";
    throw new IllegalStateException(msg);
  }

  protected int getRLimitNProcResourceId() {
    return -1;
  }

  @Override
  public synchronized long getSessionThreadLimit() {
    int nProcResourceId = getRLimitNProcResourceId();
    if (nProcResourceId >= 0) {
      try {
        rlimit.rlim_cur = 0;
        rlimit.rlim_max = 0;
        if (getrlimit(nProcResourceId, rlimit) == 0) {
          return rlimit.rlim_cur;
        }
      } catch (LastErrorException ignored) {
      }
    }
    return 0;
  }
}
