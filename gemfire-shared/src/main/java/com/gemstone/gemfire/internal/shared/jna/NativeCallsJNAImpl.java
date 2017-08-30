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
import com.sun.jna.Platform;

/**
 * Implementation of {@link NativeCalls} interface that encapsulates native C
 * calls via JNA. To obtain an instance of JNA based implementation for the
 * current platform, use {@link NativeCallsJNAImpl#getInstance()}.
 * <p>
 * BridJ is supposed to be cleaner, faster but it does not support Solaris/SPARC
 * yet and its not a mature library yet, so not using it. Can revisit once this
 * changes.
 *
 * @author swale
 * @since 7.5
 */
public final class NativeCallsJNAImpl {

  // no instance allowed
  private NativeCallsJNAImpl() {
  }

  /**
   * The static instance of the JNA based implementation for this platform.
   */
  private static final NativeCalls instance = getImplInstance();

  static final boolean is64BitPlatform = Platform.is64Bit();

  private static NativeCalls getImplInstance() {
    if (Platform.isLinux()) {
      return new LinuxNativeCalls();
    }
    if (Platform.isWindows()) {
      return new WinNativeCalls();
    }
    if (Platform.isSolaris()) {
      return new SolarisNativeCalls();
    }
    if (Platform.isMac()) {
      return new MacOSXNativeCalls();
    }
    if (Platform.isFreeBSD()) {
      return new FreeBSDNativeCalls();
    }
    return new POSIXNativeCalls();
  }

  /**
   * Get an instance of JNA based implementation of {@link NativeCalls} for the
   * current platform.
   */
  public static NativeCalls getInstance() {
    return instance;
  }
}
