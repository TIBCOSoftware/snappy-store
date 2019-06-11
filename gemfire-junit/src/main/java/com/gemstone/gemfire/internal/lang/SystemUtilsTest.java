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

package com.gemstone.gemfire.internal.lang;

import static org.junit.Assert.*;

import java.lang.management.ManagementFactory;

import org.junit.Test;

/**
 * The SystemUtilsTest class is a test suite of test cases for testing the contract and functionality of the SystemUtils
 * class.
 * <p/>
 * @author John Blum
 * @see com.gemstone.gemfire.internal.lang.SystemUtils
 * @see org.junit.Assert
 * @see org.junit.Test
 * @since 6.8
 */
public class SystemUtilsTest {

  // NOTE this test adds some maintenance overhead but ensure the correct functioning of GemFire code that relies on
  // SystemUtils.isJavaVersionAtLeast
  @Test
  public void testIsJavaVersionAtLeast() {
    // note, the expected version value should be set to the minimum supported version of the Java Runtime Environment
    // (JRE) for GemFire
    assertTrue(SystemUtils.isJavaVersionAtLeast("1.7"));
    assertTrue(SystemUtils.isJavaVersionAtLeast("1.7.0_5"));
    // note, the expected version value should be set to the next version of the Java Runtime Environment (JRE)
    // not currently available.
    assertFalse(SystemUtils.isJavaVersionAtLeast("1.9"));
  }

  @Test
  public void testIsHotSpotVM() {
    final String vmName = ManagementFactory.getRuntimeMXBean().getVmName();
    final boolean expected = vmName.contains(SystemUtils.JAVA_HOTSPOT_JVM_NAME) ||
        vmName.contains(SystemUtils.OPENJDK_JVM_NAME);
    assertEquals(expected, SystemUtils.isHotSpotVM());
  }

  @Test
  public void testIsJ9VM() {
    final boolean expected = ManagementFactory.getRuntimeMXBean().getVmName().contains(SystemUtils.IBM_J9_JVM_NAME);
    assertEquals(expected, SystemUtils.isJ9VM());
  }

  @Test
  public void testIsJRockitVM() {
    final boolean expected = ManagementFactory.getRuntimeMXBean().getVmName().contains(SystemUtils.ORACLE_JROCKIT_JVM_NAME);
    assertEquals(expected, SystemUtils.isJRockitVM());
  }

  @Test
  public void testIsLinux() {
    final boolean expected = ManagementFactory.getOperatingSystemMXBean().getName().contains(SystemUtils.LINUX_OS_NAME);
    assertEquals(expected, SystemUtils.isLinux());
  }
  @Test
  public void testIsMacOSX() {
    final boolean expected = ManagementFactory.getOperatingSystemMXBean().getName().contains(SystemUtils.MAC_OSX_NAME);
    assertEquals(expected, SystemUtils.isMacOSX());
  }

  @Test
  public void testIsWindows() throws Exception {
    final boolean expected = ManagementFactory.getOperatingSystemMXBean().getName().contains(SystemUtils.WINDOWS_OS_NAME);
    assertEquals(expected, SystemUtils.isWindows());
  }

}
