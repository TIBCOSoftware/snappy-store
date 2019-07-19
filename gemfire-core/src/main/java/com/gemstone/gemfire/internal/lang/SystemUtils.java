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

/**
 * The SystemUtils class is an abstract utility class for working with, invoking methods and accessing properties of the
 * Java System class.
 * <p/>
 * @author John Blum
 * @see java.lang.System
 * @since 6.8
 */
@SuppressWarnings("unused")
public class SystemUtils {

  public static final String CURRENT_DIRECTORY = System.getProperty("user.dir");

  // Java Virtual Machine (JVM) Names
  public static final String IBM_J9_JVM_NAME = "J9";
  public static final String JAVA_HOTSPOT_JVM_NAME = "HotSpot";
  public static final String OPENJDK_JVM_NAME = "OpenJDK";
  public static final String ORACLE_JROCKIT_JVM_NAME = "JRockit";

  // Operating System Names
  public static final String LINUX_OS_NAME = "Linux";
  public static final String MAC_OSX_NAME = "Mac";
  public static final String WINDOWS_OS_NAME = "Windows";

  /**
   * Utility method to determine whether the installed Java Runtime Environment (JRE) is minimally at the specified,
   * expected version.  Typically, Java versions are of the form "1.6.0_31"...
   * </p>
   * @param expectedVersion an int value specifying the minimum expected version of the Java Runtime.
   * @return a boolean value indicating if the Java Runtime meets the expected version requirement.
   * @see java.lang.System#getProperty(String) with "java.version".
   */
  public static boolean isJavaVersionAtLeast(final String expectedVersion) {
    final String actualVersionDigits = StringUtils.getDigitsOnly(System.getProperty("java.version"));

    final String expectedVersionDigits = StringUtils.padEnding(StringUtils.getDigitsOnly(expectedVersion), '0',
      actualVersionDigits.length());

    try {
      return (Long.parseLong(actualVersionDigits) >= Long.parseLong(expectedVersionDigits));
    }
    catch (NumberFormatException ignore) {
      return false;
    }
  }

  /**
   * Utility method to determine whether the Java application process is executing on the Java HotSpot VM.
   * Client or Server VM does not matter.
   * <p/>
   * @return a boolean value indicating whether the Java application process is executing on the Java HotSpot VM.
   * @see java.lang.System#getProperty(String) with "java.vm.name".
   */
  public static boolean isHotSpotVM() {
    final String vm = System.getProperty("java.vm.name");
    return (vm != null &&
        (vm.contains(JAVA_HOTSPOT_JVM_NAME) || vm.contains(OPENJDK_JVM_NAME)));
  }

  /**
   * Utility method to determine whether the Java application process is executing on the IBM J9 VM.
   * <p/>
   * @return a boolean value indicating whether the Java application process is executing on the IBM J9 VM.
   * @see java.lang.System#getProperty(String) with "java.vm.name".
   */
  public static boolean isJ9VM() {
    final String vm = System.getProperty("java.vm.name");
    return (vm != null && vm.contains(IBM_J9_JVM_NAME));
  }

  /**
   * Utility method to determine whether the Java application process is executing on the Oracle JRockit VM.
   * Client or Server VM does not matter.
   * <p/>
   * @return a boolean value indicating whether the Java application process is executing on the Oracle JRockit VM.
   * @see java.lang.System#getProperty(String) with "java.vm.name".
   */
  public static boolean isJRockitVM() {
    final String vm = System.getProperty("java.vm.name");
    return (vm != null && vm.contains(ORACLE_JROCKIT_JVM_NAME));
  }

  /**
   * Utility method that determines whether the Java application process is executing in a Linux
   * operating system environment.
   * <p/>
   * @return a boolean value indicating whether the Java application process is executing in Linux.
   * @see java.lang.System#getProperty(String) with "os.name".
   */
  public static boolean isLinux() {
    final String os = System.getProperty("os.name");
    return (os != null && os.contains(LINUX_OS_NAME));
  }

  /**
   * Utility method that determines whether the Java application process is executing in a Apple Mac OSX
   * operating system environment.
   * <p/>
   * @return a boolean value indicating whether the Java application process is executing in Mac OSX.
   * @see java.lang.System#getProperty(String) with "os.name".
   */
  public static boolean isMacOSX() {
    final String os = System.getProperty("os.name");
    return (os != null && os.contains(MAC_OSX_NAME));
  }

  /**
   * Utility method that determines whether the Java application process is executing in a Microsoft Windows-based
   * operating system environment.
   * <p/>
   * @return a boolean value indicating whether the Java application process is executing in Windows.
   * @see java.lang.System#getProperty(String) with "os.name".
   */
  public static boolean isWindows() {
    final String os = System.getProperty("os.name");
    return (os != null && os.contains(WINDOWS_OS_NAME));
  }

}
