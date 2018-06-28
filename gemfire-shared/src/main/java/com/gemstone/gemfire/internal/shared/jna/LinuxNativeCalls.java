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

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.TCPSocketOptions;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;

/**
 * Implementation of {@link NativeCalls} for Linux platform.
 */
final class LinuxNativeCalls extends POSIXNativeCalls {

  static {
    Native.register("c");
  }

  // #define values for keepalive options in /usr/include/netinet/tcp.h
  private static final int OPT_TCP_KEEPIDLE = 4;
  private static final int OPT_TCP_KEEPINTVL = 5;
  private static final int OPT_TCP_KEEPCNT = 6;

  private static final int ENOPROTOOPT = 92;
  private static final int ENOPROTOOPT_ALPHA = 42;
  private static final int ENOPROTOOPT_MIPS = 99;
  private static final int ENOPROTOOPT_PARISC = 220;

  private static final int RLIMIT_NPROC = 6;

  /**
   * posix_fallocate returns error number rather than setting errno
   */
  public static native int posix_fallocate64(int fd, long offset, long len);

  public static native int creat64(String path, int flags)
      throws LastErrorException;

  /**
   * {@inheritDoc}
   */
  @Override
  public OSType getOSType() {
    return OSType.LINUX;
  }

  @Override
  protected int getPlatformOption(TCPSocketOptions opt)
      throws UnsupportedOperationException {
    switch (opt) {
      case OPT_KEEPIDLE:
        return OPT_TCP_KEEPIDLE;
      case OPT_KEEPINTVL:
        return OPT_TCP_KEEPINTVL;
      case OPT_KEEPCNT:
        return OPT_TCP_KEEPCNT;
      default:
        throw new UnsupportedOperationException("unknown option " + opt);
    }
  }

  @Override
  protected boolean isNoProtocolOptionCode(int errno) {
    switch (errno) {
      case ENOPROTOOPT:
        return true;
      case ENOPROTOOPT_ALPHA:
        return true;
      case ENOPROTOOPT_MIPS:
        return true;
      case ENOPROTOOPT_PARISC:
        return true;
      default:
        return false;
    }
  }

  static {
    if (Platform.is64Bit()) {
      StatFS64.dummy();
    } else {
      StatFS.dummy();
    }
  }

  private static boolean isJNATimerEnabled = false;

  public static class TimeSpec extends Structure {
    public int tv_sec;
    public int tv_nsec;

    static {
      try {
        Native.register("rt");
        TimeSpec res = new TimeSpec();
        int ret = clock_getres(CLOCKID_REALTIME, res);
        if (ret == 0) {
          isJNATimerEnabled = true;
        }
        ret = clock_gettime(CLOCKID_PROCESS_CPUTIME_ID, res);
        if (ret == 0) {
          isJNATimerEnabled = true;
        }
      } catch (Throwable t) {
        isJNATimerEnabled = false;
      }
    }

    static void init() {
      // just invoke the static block
    }

    public static native int clock_getres(int clkId, TimeSpec time)
        throws LastErrorException;

    public static native int clock_gettime(int clkId, TimeSpec time)
        throws LastErrorException;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("tv_sec", "tv_nsec");
    }
  }

  public static class TimeSpec64 extends Structure {
    public long tv_sec;
    public long tv_nsec;

    static {
      try {
        Native.register("rt");
        TimeSpec64 res = new TimeSpec64();
        int ret = clock_getres(CLOCKID_REALTIME, res);
        if (ret == 0) {
          isJNATimerEnabled = true;
        }
        ret = clock_gettime(CLOCKID_PROCESS_CPUTIME_ID, res);
        if (ret == 0) {
          isJNATimerEnabled = true;
        }
      } catch (Throwable t) {
        isJNATimerEnabled = false;
      }
    }

    static void init() {
      // just invoke the static block
    }

    public static native int clock_getres(int clkId, TimeSpec64 time)
        throws LastErrorException;

    public static native int clock_gettime(int clkId, TimeSpec64 time)
        throws LastErrorException;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("tv_sec", "tv_nsec");
    }
  }

  public boolean loadNativeLibrary() {
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isNativeTimerEnabled() {
    // initialize static blocks
    if (NativeCallsJNAImpl.is64BitPlatform) {
      TimeSpec64.init();
    } else {
      TimeSpec.init();
    }
    return isJNATimerEnabled;
  }

  private ThreadLocal<Structure> tSpecs = new ThreadLocal<>();

  /**
   * {@inheritDoc}
   */
  @Override
  public long clockResolution(int clock_id) {
    if (NativeCallsJNAImpl.is64BitPlatform) {
      TimeSpec64 res = new TimeSpec64();
      TimeSpec64.clock_getres(clock_id, res);
      return (res.tv_sec * 1000000000L) + res.tv_nsec;
    } else {
      TimeSpec res = new TimeSpec();
      TimeSpec.clock_getres(clock_id, res);
      return (res.tv_sec * 1000000000L) + res.tv_nsec;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long nanoTime(int clock_id) {
    Structure st = tSpecs.get();

    if (NativeCallsJNAImpl.is64BitPlatform) {
      TimeSpec64 tp;
      if (st == null) {
        tp = new TimeSpec64();
        tSpecs.set(tp);
      } else {
        tp = (TimeSpec64)st;
      }

      TimeSpec64.clock_gettime(clock_id, tp);
      return (tp.tv_sec * 1000000000L) + tp.tv_nsec;
    } else {
      TimeSpec tp;
      if (st == null) {
        tp = new TimeSpec();
        tSpecs.set(tp);
      } else {
        tp = (TimeSpec)st;
      }
      TimeSpec.clock_gettime(clock_id, tp);
      return (tp.tv_sec * 1000000000L) + tp.tv_nsec;
    }
  }

  private static boolean isStatFSEnabled;

  public static class FSIDIntArr2 extends Structure {

    @SuppressWarnings("unused")
    public int[] fsid = new int[2];

    protected List<String> getFieldOrder() {
      return Arrays.asList("fsid");
    }
  }

  public static class FSPAREIntArr5 extends Structure {

    @SuppressWarnings("unused")
    public int[] fspare = new int[5];

    protected List<String> getFieldOrder() {
      return Arrays.asList("fspare");
    }
  }

  public static class StatFS extends Structure {
    public int f_type;
    public int f_bsize;
    public int f_blocks;
    public int f_bfree;
    public int f_bavail;
    public int f_files;
    public int f_ffree;
    public FSIDIntArr2 f_fsid;
    public int f_namelen;
    public int f_frsize;
    public FSPAREIntArr5 f_spare;

    static final StatFS instance;

    static {
      StatFS struct;
      try {
        Native.register("rt");
        struct = new StatFS();
        int ret = statfs(".", struct);
        if (ret == 0) {
          isStatFSEnabled = true;
        } else {
          isStatFSEnabled = false;
        }
      } catch (Throwable t) {
        struct = null;
        isStatFSEnabled = false;
      }
      instance = struct;
    }

    public static native int statfs(String path, StatFS statfs)
        throws LastErrorException;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("f_type", "f_bsize", "f_blocks",
          "f_bfree", "f_bavail", "f_files", "f_ffree", "f_fsid", "f_namelen",
          "f_frsize", "f_spare");
    }

    // KN: TODO need to add more types which are type of remote.
    // Not sure about these file types which needs to be cheked and added
    // in the list below
    // COH, DEVFS, ISOFS, OPENPROM_SUPER_MAGIC, PROC_SUPER_MAGIC, UDF_SUPER_MAGIC
    // Do man 2 statfs on linux and will give all the file types.
    // Following identified as remote file system types
    // CIFS_MAGIC_NUMBER, CODA_SUPER_MAGIC, NCP_SUPER_MAGIC, NFS_SUPER_MAGIC,
    //    SMB_SUPER_MAGIC, TMPFS_MAGIC
    // 0xFF534D42       , 0x73757245      , 0x564c         , 0x6969         ,
    //    0x517B         , 0x01021994
    // 4283649346       , 1937076805      , 22092          , 26985          ,
    //    20859          , 16914836
    private static int[] REMOTE_TYPES = new int[]{ /*4283649346,*/ 1937076805,
        22092, 26985, 20859, 16914836};

    public boolean isTypeLocal() {
      for (int i = 0; i < REMOTE_TYPES.length; i++) {
        if (REMOTE_TYPES[i] == f_type) {
          return false;
        }
      }
      return true;
    }

    public static void dummy() {
    }
  }

  public static class FSPARELongArr5 extends Structure {

    @SuppressWarnings("unused")
    public long[] fspare = new long[5];

    protected List<String> getFieldOrder() {
      return Arrays.asList("fspare");
    }
  }

  public static class StatFS64 extends Structure {
    public long f_type;
    public long f_bsize;
    public long f_blocks;
    public long f_bfree;
    public long f_bavail;
    public long f_files;
    public long f_ffree;
    public FSIDIntArr2 f_fsid;
    public long f_namelen;
    public long f_frsize;
    public FSPARELongArr5 f_spare;

    // KN: TODO need to add more types which are type of remote.
    // Not sure about these file types which needs to be checked and added
    // in the list below
    // COH, DEVFS, ISOFS, OPENPROM_SUPER_MAGIC, PROC_SUPER_MAGIC, UDF_SUPER_MAGIC
    // Do man 2 statfs on linux and will give all the file types.
    // Following identified as remote file system types
    // CIFS_MAGIC_NUMBER, CODA_SUPER_MAGIC, NCP_SUPER_MAGIC, NFS_SUPER_MAGIC,
    //    SMB_SUPER_MAGIC, TMPFS_MAGIC
    // 0xFF534D42       , 0x73757245      , 0x564c         , 0x6969         ,
    //    0x517B         , 0x01021994
    // 4283649346       , 1937076805      , 22092          , 26985          ,
    //    20859          , 16914836
    private static long[] REMOTE_TYPES = {4283649346L, 1937076805L,
        22092L, 26985L, 20859L, 16914836L};

    static final StatFS64 instance;

    static {
      StatFS64 struct;
      try {
        Native.register("rt");
        struct = new StatFS64();
        int ret = statfs(".", struct);
        if (ret == 0) {
          isStatFSEnabled = true;
        } else {
          isStatFSEnabled = false;
        }
      } catch (Throwable t) {
        System.out.println("got error " + t.getMessage());
        t.printStackTrace();
        struct = null;
        isStatFSEnabled = false;
      }
      instance = struct;
    }

    public static native int statfs(String path, StatFS64 statfs)
        throws LastErrorException;

    @Override
    protected List<String> getFieldOrder() {
      return Arrays.asList("f_type", "f_bsize", "f_blocks",
          "f_bfree", "f_bavail", "f_files", "f_ffree", "f_fsid", "f_namelen",
          "f_frsize", "f_spare");
    }

    boolean isTypeLocal() {
      for (int i = 0; i < REMOTE_TYPES.length; i++) {
        if (REMOTE_TYPES[i] == f_type) {
          return false;
        }
      }
      return true;
    }

    public static void dummy() {
    }
  }

  /**
   * This will return whether the path passed in as arg is
   * part of a local file system or a remote file system.
   * This method is mainly used by the DiskCapacityMonitor thread
   * and we don't want to monitor remote fs available space as
   * due to network problems/firewall issues the call to getUsableSpace
   * can hang. See bug #49155. On platforms other than Linux this will
   * return false even if it on local file system for now.
   */
  public synchronized boolean isOnLocalFileSystem(final String path) {
    final Logger logger = ClientSharedUtils.getLogger();
    if (!isStatFSEnabled) {
      return false;
    }
    final int numTries = 10;
    for (int i = 1; i <= numTries; i++) {
      try {
        if (Platform.is64Bit()) {
          StatFS64 stat = StatFS64.instance;
          stat.f_type = 0;
          StatFS64.statfs(path, stat);
          return stat.isTypeLocal();
        } else {
          StatFS stat = StatFS.instance;
          stat.f_type = 0;
          StatFS.statfs(path, stat);
          return stat.isTypeLocal();
        }
      } catch (LastErrorException le) {
        // ignoring it as NFS mounted can give this exception
        // and we just want to retry to remove transient problem.
        if (logger != null && logger.isLoggable(Level.FINE)) {
          logger.fine("DEBUG isOnLocalFileSystem got ex = " + le + " msg = "
              + le.getMessage());
        }
      }
    }
    return false;
  }

  @Override
  protected boolean hasFallocate() {
    return true;
  }

  @Override
  protected int createFD(String path, int flags) throws LastErrorException {
    return creat64(path, flags);
  }

  @Override
  protected void fallocateFD(int fd, long offset, long len)
      throws LastErrorException {
    int errno = posix_fallocate64(fd, offset, len);
    if (errno != 0) {
      throw new LastErrorException(errno);
    }
  }

  @Override
  protected int getRLimitNProcResourceId() {
    return RLIMIT_NPROC;
  }
}
