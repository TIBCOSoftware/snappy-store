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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

/**
 * Enumeration for various types of OSes supported by {@link NativeCalls} using
 * JNA ({@link NativeCallsJNAImpl}).
 */
public enum OSType {

  /**
   * Indicates a Linux family OS.
   */
  LINUX,

  /**
   * Indicates a Solaris family OS.
   */
  SOLARIS,

  /**
   * Indicates a MacOSX family OS.
   */
  MACOSX,

  /**
   * Indicates a FreeBSD family OS.
   */
  FREEBSD,

  /**
   * Indicates a generic POSIX complaint OS (at least to a reasonable degree).
   */
  GENERIC_POSIX,

  /**
   * Indicates a Microsoft Windows family OS.
   */
  WIN,

  /**
   * Indicates an OS whose kind cannot be determined or that is not supported by
   * JNA.
   */
  GENERIC;

  /**
   * Indicates a Microsoft Windows family OS.
   */
  public final boolean isWindows() {
    return this == WIN;
  }
}
