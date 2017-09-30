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

package com.pivotal.gemfirexd.internal;

import com.gemstone.gemfire.internal.GemFireVersion;
import com.gemstone.gemfire.internal.SharedLibrary;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;

import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.StringTokenizer;

/**
 * This class provides build and version information about GemFireXD.
 * It gathers this information from the resource property file
 * for this class.
 * 
 * @since 1.0
 */
public class GemFireXDVersion {

  private static final boolean isNativeLibLoaded;

  static {
    GemFireCacheImpl.setGFXDSystem(true);
    isNativeLibLoaded = NativeCalls.getInstance().loadNativeLibrary()
        && SharedLibrary.register("gemfirexd");
    GemFireVersion instance = GemFireVersion.getInstance(
        GemFireXDVersion.class, SharedUtils.GFXD_VERSION_PROPERTIES);
    if (isNativeLibLoaded) {
      instance.setNativeVersion(_getNativeVersion());
    }
    else {
      instance.setNativeVersion("gemfirexd " + instance.getNativeVersion());
    }
  }

  public static native String _getNativeVersion();

  public static void loadProperties() {
    GemFireVersion.getInstance(GemFireXDVersion.class,
        SharedUtils.GFXD_VERSION_PROPERTIES);
  }

  /**
   * Returns the name of this product
   */
  public static String getProductName() {
    loadProperties();
    return GemFireVersion.getProductName();
  }

  /**
   * Returns the version of GemFireXD being used
   */
  public static String getGemFireXDVersion() {
    loadProperties();
    return GemFireVersion.getProductVersion();
  }

  public static String getGemFireXDReleaseStage() {
    loadProperties();
    return GemFireVersion.getProductReleaseStage();
  }

  /**
   * Returns the version of GemFireXD native code library being used
   */
  public static String getNativeCodeVersion() {
    loadProperties();
    return GemFireVersion.getNativeCodeVersion();
  }

  public static String getJavaCodeVersion() {
    loadProperties();
    return GemFireVersion.getJavaCodeVersion();
  }

  /**
   * Returns the date of the source code from which GemFireXD was built
   */
  public static String getSourceDate() {
    loadProperties();
    return GemFireVersion.getSourceDate();
  }

  /**
   * Returns the revision of the source code on which GemFireXD was built.
   */
  public static String getSourceRevision() {
    loadProperties();
    return GemFireVersion.getSourceRevision();
  }

  /**
   * Returns the source code repository from which GemFireXD was built.
   */
  public static String getSourceRepository() {
    loadProperties();
    return GemFireVersion.getSourceRepository();
  }

  /**
   * Returns the date on which GemFireXD was built
   */
  public static String getBuildDate() {
    loadProperties();
    return GemFireVersion.getBuildDate();
  }

  /**
   * Returns the id of the GemFireXD build
   */
  public static String getBuildId() {
    loadProperties();
    return GemFireVersion.getBuildId();
  }

  /**
   * Returns the platform on which GemFireXD was built
   */
  public static String getBuildPlatform() {
    loadProperties();
    return GemFireVersion.getBuildPlatform();
  }

  /**
   * Returns the version of Java used to build GemFireXD
   */
  public static String getBuildJavaVersion() {
    loadProperties();
    return GemFireVersion.getBuildJavaVersion();
  }

  /** Public method that returns the URL of the gemfirexd.jar file */
  public static URL getJarURL() {
    java.security.CodeSource cs = GemFireXDVersion.class
        .getProtectionDomain().getCodeSource();
    if (cs != null) {
      return cs.getLocation();
    }
    // fix for bug 33274 - null CodeSource from protection domain in Sybase
    URL csLoc = null;
    StringTokenizer tokenizer = new StringTokenizer(
        System.getProperty("java.class.path"), File.pathSeparator);
    while (tokenizer.hasMoreTokens()) {
      String jar = tokenizer.nextToken();
      if (jar.indexOf("gemfirexd.jar") != -1) {
        File gemfireJar = new File(jar);
        try {
          csLoc = gemfireJar.toURI().toURL();
        } catch (Exception e) {
        }
        break;
      }
    }
    if (csLoc != null) {
      return csLoc;
    }
    // try the boot class path to fix bug 37394
    tokenizer = new StringTokenizer(
        System.getProperty("sun.boot.class.path"), File.pathSeparator);
    while (tokenizer.hasMoreTokens()) {
      String jar = tokenizer.nextToken();
      if (jar.indexOf("gemfirexd.jar") != -1) {
        File gemfireJar = new File(jar);
        try {
          csLoc = gemfireJar.toURI().toURL();
        } catch (Exception e) {
        }
        break;
      }
    }
    return csLoc;
  }

  public static void createVersionFile() {
    loadProperties();
    GemFireVersion.createVersionFile();
  }

  /**
   * Prints all available version information (excluding source code
   * information) to the given <code>PrintWriter</code> in a standard format.
   */
  public static void print(PrintWriter pw) {
    loadProperties();
    GemFireVersion.print(pw);
  }

  /**
   * Prints all available version information to the given
   * <code>PrintWriter</code> in a standard format.
   *
   * @param printSourceInfo
   *          Should information about the source code be printed?
   */
  public static void print(PrintWriter pw, boolean printSourceInfo) {
    loadProperties();
    GemFireVersion.print(pw, printSourceInfo);
  }

  /**
   * Prints all available version information (excluding information about the
   * source code) to the given <code>PrintStream</code> in a standard format.
   */
  public static void print(PrintStream ps) {
    loadProperties();
    GemFireVersion.print(ps);
  }

  /**
   * Prints all available version information to the given
   * <code>PrintStream</code> in a standard format.
   *
   * @param printSourceInfo
   *          Should information about the source code be printed?
   */
  public static void print(PrintStream ps, boolean printSourceInfo) {
    loadProperties();
    GemFireVersion.print(ps, printSourceInfo);
  }

  /**
   * Populates the gemfirexdVersion.properties file
   */
  public static void main(String[] args) {
    loadProperties();
    GemFireVersion.main(args);
  }
}
