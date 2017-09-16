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
import com.gemstone.gemfire.internal.shared.TCPSocketOptions;

/**
 * Implementation of {@link NativeCalls} for MacOSX platform.
 */
final class MacOSXNativeCalls extends POSIXNativeCalls {

  // #define values for keepalive options in /usr/include/netinet/tcp.h
  private static final int OPT_TCP_KEEPALIVE = 0x10;

  private static final int ENOPROTOOPT = 42;

  private static final int RLIMIT_NPROC = 7;

  /**
   * {@inheritDoc}
   */
  @Override
  public OSType getOSType() {
    return OSType.MACOSX;
  }

  @Override
  protected int getPlatformOption(TCPSocketOptions opt)
      throws UnsupportedOperationException {
    switch (opt) {
      case OPT_KEEPIDLE:
        return OPT_TCP_KEEPALIVE;
      case OPT_KEEPINTVL:
      case OPT_KEEPCNT:
        return UNSUPPORTED_OPTION;
      default:
        throw new UnsupportedOperationException("unknown option " + opt);
    }
  }

  @Override
  protected boolean isNoProtocolOptionCode(int errno) {
    return (errno == ENOPROTOOPT);
  }

  @Override
  protected int getRLimitNProcResourceId() {
    return RLIMIT_NPROC;
  }
}
