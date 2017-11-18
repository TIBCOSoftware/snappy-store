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

import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.NativeErrorException;
import com.gemstone.gemfire.internal.shared.TCPSocketOptions;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * Implementation of {@link NativeCalls} for Windows platforms.
 */
final class WinNativeCalls extends NativeCalls {

  static {
    // for socket operations
    Native.register("Ws2_32");
  }

  @SuppressWarnings("unused")
  public static final class TcpKeepAlive extends Structure {
    public int enabled;
    public int keepalivetime;
    public int keepaliveinterval;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("enabled", "keepalivetime",
          "keepaliveinterval");
    }
  }

  public static native int WSAIoctl(NativeLong sock, int controlCode,
      TcpKeepAlive value, int valueSize, Pointer outValue, int outValueSize,
      IntByReference bytesReturned, Pointer overlapped,
      Pointer completionRoutine) throws LastErrorException;

  private static final int WSAENOPROTOOPT = 10042;
  private static final int SIO_KEEPALIVE_VALS = -1744830460;

  private static final class Kernel32 {

    static {
      /*
      // kernel32 requires stdcall calling convention
      Map<String, Object> kernel32Options = new HashMap<>();
      kernel32Options.put(Library.OPTION_CALLING_CONVENTION,
          StdCallLibrary.STDCALL_CONVENTION);
      kernel32Options.put(Library.OPTION_FUNCTION_MAPPER,
          StdCallLibrary.FUNCTION_MAPPER);
      */
      final NativeLibrary kernel32Lib = NativeLibrary.getInstance("kernel32",
          W32APIOptions.DEFAULT_OPTIONS);
      Native.register(kernel32Lib);
    }

    // Values below from windows.h header are hard-coded since there
    // does not seem any simple way to get those at build or run time.
    // Hopefully these will never change else all hell will break
    // loose in Windows world ...
    static final int PROCESS_QUERY_INFORMATION = 0x0400;
    static final int PROCESS_TERMINATE = 0x0001;
    static final int STILL_ACTIVE = 259;
    static final int INVALID_HANDLE = -1;

    public static native boolean SetEnvironmentVariableA(String name,
        String value) throws LastErrorException;

    public static native int GetEnvironmentVariableA(String name,
        byte[] pvalue, int psize);

    public static native boolean SetCurrentDirectory(String path)
        throws LastErrorException;

    public static native int GetCurrentProcessId();

    public static native Pointer OpenProcess(int desiredAccess,
        boolean inheritHandle, int processId) throws LastErrorException;

    public static native boolean TerminateProcess(Pointer processHandle,
        int exitCode) throws LastErrorException;

    public static native boolean GetExitCodeProcess(Pointer processHandle,
        IntByReference exitCode) throws LastErrorException;

    public static native boolean CloseHandle(Pointer handle)
        throws LastErrorException;
  }

  private static final Map<String, String> javaEnv =
      getModifiableJavaEnvWIN();

  /**
   * @see NativeCalls#getOSType()
   */
  @Override
  public OSType getOSType() {
    return OSType.WIN;
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
    boolean res = false;
    Throwable cause = null;
    try {
      res = Kernel32.SetEnvironmentVariableA(name, value);
    } catch (LastErrorException le) {
      // error code ERROR_ENVVAR_NOT_FOUND (203) indicates variable was not
      // found so ignore
      if (value == null && le.getErrorCode() == 203) {
        res = true;
      } else {
        cause = new NativeErrorException(le.getMessage(), le.getErrorCode(),
            le.getCause());
      }
    }
    if (!res) {
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
    Kernel32.SetCurrentDirectory(path);
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
    int psize = Kernel32.GetEnvironmentVariableA(name, null, 0);
    if (psize > 0) {
      for (; ; ) {
        byte[] result = new byte[psize];
        psize = Kernel32.GetEnvironmentVariableA(name, result, psize);
        if (psize == (result.length - 1)) {
          return new String(result, 0, psize);
        } else if (psize <= 0) {
          return null;
        }
      }
    } else {
      return null;
    }
  }

  /**
   * @see NativeCalls#getProcessId()
   */
  @Override
  public int getProcessId() {
    return Kernel32.GetCurrentProcessId();
  }

  /**
   * @see NativeCalls#isProcessActive(int)
   */
  @Override
  public boolean isProcessActive(final int processId) {
    try {
      final Pointer procHandle = Kernel32.OpenProcess(
          Kernel32.PROCESS_QUERY_INFORMATION, false, processId);
      final long hval;
      if (procHandle == null || (hval = Pointer.nativeValue(procHandle)) ==
          Kernel32.INVALID_HANDLE || hval == 0) {
        return false;
      } else {
        final IntByReference status = new IntByReference();
        final boolean result = Kernel32.GetExitCodeProcess(procHandle, status)
            && status.getValue() == Kernel32.STILL_ACTIVE;
        Kernel32.CloseHandle(procHandle);
        return result;
      }
    } catch (LastErrorException le) {
      // some problem in getting process status
      return false;
    }
  }

  /**
   * @see NativeCalls#killProcess(int)
   */
  @Override
  public boolean killProcess(final int processId) {
    try {
      final Pointer procHandle = Kernel32.OpenProcess(
          Kernel32.PROCESS_TERMINATE, false, processId);
      final long hval;
      if (procHandle == null || (hval = Pointer.nativeValue(procHandle)) ==
          Kernel32.INVALID_HANDLE || hval == 0) {
        return false;
      } else {
        final boolean result = Kernel32.TerminateProcess(procHandle, -1);
        Kernel32.CloseHandle(procHandle);
        return result;
      }
    } catch (LastErrorException le) {
      // some problem in killing the process
      return false;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void daemonize(RehashServerOnSIGHUP callback)
      throws UnsupportedOperationException, IllegalStateException {
    throw new IllegalStateException(
        "daemonize() not applicable for Windows platform");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Map<TCPSocketOptions, Throwable> setSocketOptions(Socket sock,
      InputStream sockStream, Map<TCPSocketOptions, Object> optValueMap)
      throws UnsupportedOperationException {
    final TcpKeepAlive optValue = new TcpKeepAlive();
    final int optSize = (Integer.SIZE / Byte.SIZE) * 3;
    TCPSocketOptions errorOpt = null;
    Throwable error = null;
    for (Map.Entry<TCPSocketOptions, Object> e : optValueMap.entrySet()) {
      TCPSocketOptions opt = e.getKey();
      Object value = e.getValue();
      // all options currently require an integer argument
      if (value == null || !(value instanceof Integer)) {
        throw new IllegalArgumentException("bad argument type "
            + (value != null ? value.getClass().getName() : "NULL") + " for "
            + opt);
      }
      switch (opt) {
        case OPT_KEEPIDLE:
          optValue.enabled = 1;
          // in millis
          optValue.keepalivetime = (Integer)value * 1000;
          break;
        case OPT_KEEPINTVL:
          optValue.enabled = 1;
          // in millis
          optValue.keepaliveinterval = (Integer)value * 1000;
          break;
        case OPT_KEEPCNT:
          errorOpt = opt;
          error = new UnsupportedOperationException(
              getUnsupportedSocketOptionMessage(opt));
          break;
        default:
          throw new UnsupportedOperationException("unknown option " + opt);
      }
    }
    final int sockfd = getSocketKernelDescriptor(sock, sockStream);
    final IntByReference nBytes = new IntByReference(0);
    try {
      if (WSAIoctl(new NativeLong(sockfd), SIO_KEEPALIVE_VALS, optValue,
          optSize, null, 0, nBytes, null, null) != 0) {
        errorOpt = TCPSocketOptions.OPT_KEEPIDLE; // using some option here
        error = new SocketException(getOSType() + ": error setting options: "
            + optValueMap);
      }
    } catch (LastErrorException le) {
      // check if the error indicates that option is not supported
      errorOpt = TCPSocketOptions.OPT_KEEPIDLE; // using some option here
      if (le.getErrorCode() == WSAENOPROTOOPT) {
        error = new UnsupportedOperationException(
            getUnsupportedSocketOptionMessage(errorOpt),
            new NativeErrorException(le.getMessage(), le.getErrorCode(),
                le.getCause()));
      } else {
        final SocketException se = new SocketException(getOSType()
            + ": failed to set options: " + optValueMap);
        se.initCause(le);
        error = se;
      }
    }
    return errorOpt != null ? Collections.singletonMap(errorOpt, error)
        : null;
  }
}
