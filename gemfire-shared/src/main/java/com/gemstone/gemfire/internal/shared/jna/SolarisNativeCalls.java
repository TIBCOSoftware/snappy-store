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

import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.NativeErrorException;
import com.gemstone.gemfire.internal.shared.TCPSocketOptions;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

/**
 * Implementation of {@link NativeCalls} for Solaris platform.
 */
final class SolarisNativeCalls extends POSIXNativeCalls {

  static {
    Native.register("nsl");
    Native.register("socket");
  }

  // #define values for keepalive options in /usr/include/netinet/tcp.h
  // Below are only available on Solaris 11 and above but older platforms will
  // throw an exception which higher layers will handle appropriately
  private static final int OPT_TCP_KEEPALIVE_THRESHOLD = 0x16;
  private static final int OPT_TCP_KEEPALIVE_ABORT_THRESHOLD = 0x17;

  private static final int ENOPROTOOPT = 99;

  /**
   * {@inheritDoc}
   */
  @Override
  public OSType getOSType() {
    return OSType.SOLARIS;
  }

  @Override
  protected int getPlatformOption(TCPSocketOptions opt)
      throws UnsupportedOperationException {
    switch (opt) {
      case OPT_KEEPIDLE:
        return OPT_TCP_KEEPALIVE_THRESHOLD;
      case OPT_KEEPINTVL:
      case OPT_KEEPCNT:
        return UNSUPPORTED_OPTION;
      default:
        throw new UnsupportedOperationException("unknown option " + opt);
    }
  }

  @Override
  protected int setPlatformSocketOption(int sockfd, int level, int optName,
      TCPSocketOptions opt, Integer optVal, int optSize)
      throws NativeErrorException {
    try {
      switch (optName) {
        case OPT_TCP_KEEPALIVE_THRESHOLD:
          // value required is in millis
          final IntByReference timeout = new IntByReference(optVal * 1000);
          int result = setsockopt(sockfd, level, optName, timeout, optSize);
          if (result == 0) {
            // setting ABORT_THRESHOLD to be same as KEEPALIVE_THRESHOLD
            return setsockopt(sockfd, level,
                OPT_TCP_KEEPALIVE_ABORT_THRESHOLD, timeout, optSize);
          } else {
            return result;
          }
        default:
          throw new UnsupportedOperationException("unsupported option " + opt);
      }
    } catch (LastErrorException le) {
      throw new NativeErrorException(le.getMessage(), le.getErrorCode(),
          le.getCause());
    }
  }

  @Override
  protected boolean isNoProtocolOptionCode(int errno) {
    return (errno == ENOPROTOOPT);
  }
}
