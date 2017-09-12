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
package com.gemstone.gemfire.internal;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class provides build and version information about GemFire.
 * It gathers this information from the resource property file
 * for this class.
 */
public class GemFireVersion {

  private static String RESOURCE_NAME = "GemFireVersion.properties";
  
  private static String ERROR_PROPERTY = "error";
  
  /** The singleton instance */
  private static GemFireVersion instance;

  /** Constant for the GemFire version Resource Property entry */
  private static final String PRODUCT_NAME = "Product-Name";

  /** Constant for the GemFire version Resource Property entry */
  private static final String GEMFIRE_VERSION = "Product-Version";

  /** Constant for the GemFire release stage Resource Property entry */
  private static final String GEMFIRE_RELEASE_STAGE = "Product-Stage";

  /**
   * For GemFireXD product, the constant for the underlying GemFire version
   * Resource Property entry. For GemFire product the one used is
   * {@link #GEMFIRE_VERSION}.
   */
  private static final String UNDERLYING_GEMFIRE_VERSION = "GemFire-Version";

  /** Constant for the source code date Resource Property entry */
  private static final String SOURCE_DATE = "Source-Date";

  /** Constant for the source code revision Resource Property entry */
  private static final String SOURCE_REVISION = "Source-Revision";

  /** Constant for the source code repository Resource Property entry */
  private static final String SOURCE_REPOSITORY = "Source-Repository";

  /** Constant for the build date Resource Property entry */
  private static final String BUILD_DATE = "Build-Date";

  /** Constant for the build id Resource Property entry */
  private static final String BUILD_ID = "Build-Id";

  /** Constant for the build Java version Resource Property entry */
  private static final String BUILD_PLATFORM = "Build-Platform";

  /** Constant for the build Java version Resource Property entry */
  private static final String BUILD_JAVA_VERSION = "Build-Java-Version";

  /** Constant for the GemFire enterprise edition Resource Property entry */
  private static final String ENTERPRISE_EDITION = "Enterprise-Edition";

  /** Constant for the SnappyData Cluster Type Resource Property entry */
  private static final String CLUSTER_TYPE = "Cluster-Type";

  ////////////////////  Instance Fields  ////////////////////

  /** The name of the properties resource used to load this instance */
  private String resourceName = null;
  
  /** The properties used to load the GemFireVersion values */
  private Properties properties = null;
  
  /** Error message to display instead of the version information */
  private String error = null;

  /** The name of this product */
  private String productName;

  /**
   * This product's version. For GemFire, this will be the version of GemFire
   * product while for GemFireXD this will be the version of GemFireXD product.
   */
  private String productVersion;

  private String productReleaseStage;

  /**
   * For GemFireXD product, the version of underlying GemFire layer. For GemFire,
   * this will not be set and instead {@link #productVersion} is used.
   */
  private String gemfireVersion;

  /** The version of GemFire native code library */
  private String nativeVersion;

  /** The date that the source code for GemFire was last updated */  
  private String sourceDate;

  /** The revision of the source code used to build GemFire */  
  private String sourceRevision;

  /** The repository in which the source code for GemFire resides */  
  private String sourceRepository;

  /** The date on which GemFire was built */
  private String buildDate;

  /** The ID of the GemFire build */
  private String buildId;

  /** The platform on which GemFire was built */
  private String buildPlatform;

  /** The version of Java that was used to build GemFire */
  private String buildJavaVersion;

  /** If the product is enterprise edition or not */
  private boolean enterpriseEdition;

  /** Cluster type, indicates any specifications for cluster */
  private String clusterType;

  ////////////////////  Static Methods  ////////////////////

  /**
   * Clears the static instance field. Only for tests.
   */
  synchronized static void clearInstance() {
    instance = null;
  }
  
  /**
   * Returns (or creates) the singleton instance of this class
   */
  public synchronized static GemFireVersion getInstance() {
    if (instance != null) {
      return instance;
    }
    return getInstance(GemFireVersion.class, getDefaultResource());
  }

  /**
   * Returns (or creates) the singleton instance of this class using the
   * properties identified by the named resource.
   * 
   * @param contextClass the class to use as a context for loading the 
   * properties as a resource
   * 
   * @param resourceName the fully qualified name of the properties resource
   */
  public synchronized static GemFireVersion getInstance(Class<?> contextClass, String resourceName) {
    if (resourceName == null) {
      throw new IllegalArgumentException("GemFireVersion requires resourceName");
    }
    if (instance != null && resourceName.equals(instance.resourceName)) {
      return instance;
    }
    Properties props = new Properties();
    InputStream is = ClassPathLoader.getLatest().getResourceAsStream(contextClass, resourceName);
    if (is == null) {
      props.put(ERROR_PROPERTY, LocalizedStrings.GemFireVersion_COULD_NOT_FIND_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_0.toLocalizedString(resourceName));
    }
    else {
      try {
        props.load(is);
      } 
      catch (Exception ex) {
        props.put(ERROR_PROPERTY, LocalizedStrings.GemFireVersion_COULD_NOT_READ_PROPERTIES_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_0_BECAUSE_1.toLocalizedString(new Object[] {resourceName, ex}));
      }
    }
    return getInstance(resourceName, props);
  }
  
  /**
   * Returns (or creates) the singleton instance of this class using the
   * properties.
   */
  private synchronized static GemFireVersion getInstance(String resourceName, Properties props) {
    if (resourceName == null) {
      throw new IllegalArgumentException("GemFireVersion requires resourceName");
    }
    if (props == null) {
      throw new IllegalArgumentException("GemFireVersion requires properties");
    }
    if (instance == null) {
       instance = new GemFireVersion(resourceName, props);
    }
    else if (!getDefaultResource().equals(resourceName)
             && !instance.properties.equals(props)) {
      instance = new GemFireVersion(resourceName, props);
    }

    return instance;
  }

  public String getError() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;
    }
    return null;
  }
  
  /**
   * Returns the name of this product
   */
  public static String getProductName() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.productName;
    }
  }

  /**
   * For GemFireXD product returns the underlying GemFire version being used. For
   * GemFire product this is same as {@link #getProductVersion()}.
   * 
   * Use this method instead of {@link #getProductVersion()} whenever the code
   * depends on version comparisons against known versions for compatibility
   * etc. This returns the actual GemFire version being used in GemFireXD for
   * those cases instead of returning the GemFireXD version which will nearly
   * always fail the checks.
   */
  public static String getGemFireVersion() {
    GemFireVersion v = getInstance();
    if (v.gemfireVersion != null) {
      return v.gemfireVersion;
    }
    else {
      return getProductVersion();
    }
  }

  /**
   * Returns the version of GemFire product being used. For GemFireXD returns the
   * version of GemFireXD.
   * 
   * If code depends on the actual GemFire product version in some way (as
   * opposed to displaying/dumping the version) then it should use
   * {@link #getGemFireVersion()} that will work correctly for GemFireXD too. For
   * example if the code depends on version comparisons against known versions
   * for compatibility etc. then it should almost certainly use
   * {@link #getGemFireVersion()} and not this method.
   */
  public static String getProductVersion() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;
    }
    else {
      return v.productVersion;
    }
  }

  public static String getProductReleaseStage() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;
    }
    else {
      return v.productReleaseStage;
    }
  }

  private static String stripSpaces(String s) {
    StringBuffer result = new StringBuffer(s);
    while (result.charAt(0) == ' ') {
      result.deleteCharAt(0);
    }
    while (result.charAt(result.length()-1) == ' ') {
      result.deleteCharAt(result.length()-1);
    }
    return result.toString();
  }
  
  /**
   * Returns the version of GemFire native code library being used
   */
  public static String getNativeCodeVersion() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.getNativeVersion();
    }
  }
  public static String getJavaCodeVersion() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      StringBuilder result = new StringBuilder(80);
      result.append(GemFireVersion.getProductVersion())
        .append(' ')
        .append(GemFireVersion.getBuildId())
        .append(' ')
        .append(GemFireVersion.getBuildDate())
        .append(" javac ")
        .append(GemFireVersion.getBuildJavaVersion());
      return result.toString();
    }
  }

  /**
   * Returns the date of the source code from which GemFire was built
   */
  public static String getSourceDate() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.sourceDate;
    }
  }

  /**
   * Returns the revision of the source code on which GemFire was
   * built.
   *
   * @since 4.0
   */
  public static String getSourceRevision() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.sourceRevision;
    }
  }

  /**
   * Returns the source code repository from which GemFire was built.
   *
   * @since 4.0
   */
  public static String getSourceRepository() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.sourceRepository;
    }
  }

  /**
   * Returns the date on which GemFire was built
   */
  public static String getBuildDate() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.buildDate;
    }
  }

  /**
   * Returns the id of the GemFire build
   */
  public static String getBuildId() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.buildId;
    }
  }

  /**
   * Returns the platform on which GemFire was built
   */
  public static String getBuildPlatform() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.buildPlatform;
    }
  }

  /**
   * Returns the version of Java used to build GemFire
   */
  public static String getBuildJavaVersion() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return v.error;

    } else {
      return v.buildJavaVersion;
    }
  }

  private static String getDefaultResource() {
    String name =
      GemFireVersion.class.getPackage().getName().replace('.', '/');
    return name + "/" + RESOURCE_NAME;
  }
  
  ////////////////////  Constructors  ////////////////////

  protected GemFireVersion(String resourceName, Properties properties) {
    Properties props = new Properties(properties); // copy to prevent mutation
    this.resourceName = resourceName;
    this.properties = props;
    
    String errorInProps = props.getProperty(ERROR_PROPERTY);
    if (errorInProps != null && errorInProps.trim().length() > 0) {
      this.error = errorInProps;
      return;
    }
    
    this.setNativeVersion(SmHelper.getNativeVersion());
    this.productName = props.getProperty(PRODUCT_NAME);
    if (this.productName == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {PRODUCT_NAME, resourceName});
      return;
    }
    this.productVersion = props.getProperty(GEMFIRE_VERSION);
    if (this.productVersion == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {GEMFIRE_VERSION, resourceName});
      return;
    }
    this.productReleaseStage = props.getProperty(GEMFIRE_RELEASE_STAGE);
    if (this.productReleaseStage == null) {
      this.productReleaseStage = "";
    }
    this.enterpriseEdition = Boolean.parseBoolean(props.getProperty(ENTERPRISE_EDITION, "false"));
    this.clusterType = props.getProperty(CLUSTER_TYPE, "");
    // below setting for GemFireXD is to indicate the underlying GemFire
    // version being used in GemFireXD product; for GemFire this will not
    // be set and instead this.productVersion is used where required
    String gfVersion = props.getProperty(UNDERLYING_GEMFIRE_VERSION);
    // special token "NOT SET" might be present that indicates no separate
    // GemFire version
    if (gfVersion != null && gfVersion.startsWith("NOT SET")) {
      gfVersion = null;
    }
    this.gemfireVersion = gfVersion;

    this.sourceDate = props.getProperty(SOURCE_DATE);
    if (this.sourceDate == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {SOURCE_DATE, resourceName});
      return;
    }
    this.sourceRevision = props.getProperty(SOURCE_REVISION);
    if (this.sourceRevision == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {SOURCE_REVISION, resourceName});
      return;
    }
    this.sourceRepository = props.getProperty(SOURCE_REPOSITORY);
    if (this.sourceRepository == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {SOURCE_REPOSITORY, resourceName});
      return;
    }
    this.buildDate = props.getProperty(BUILD_DATE);
    if (this.buildDate == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {BUILD_DATE, resourceName});
      return;
    }
    this.buildId = props.getProperty(BUILD_ID);
    if (this.buildId == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {BUILD_ID, resourceName});
      return;
    }
    this.buildPlatform = props.getProperty(BUILD_PLATFORM);
    if (this.buildPlatform == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {BUILD_PLATFORM, resourceName});
      return;
    }
    this.buildJavaVersion = props.getProperty(BUILD_JAVA_VERSION);
    if (this.buildJavaVersion == null) {
      error = LocalizedStrings.GemFireVersion_MISSING_PROPERTY_0_FROM_RESOURCE_COM_GEMSTONE_GEMFIRE_INTERNAL_1.toLocalizedString(new Object[] {BUILD_JAVA_VERSION, resourceName});
      return;
    }
  }

  /** Public method that returns the URL of the gemfire.jar/gemfirexd.jar file */
  public static URL getJarURL() {
    java.security.CodeSource cs = 
      GemFireVersion.class.getProtectionDomain().getCodeSource();
    if (cs != null) {
      return cs.getLocation();
    }
    // fix for bug 33274 - null CodeSource from protection domain in Sybase
    URL csLoc = null;
    StringTokenizer tokenizer = new StringTokenizer(System.getProperty("java.class.path"), File.pathSeparator);
    while(tokenizer.hasMoreTokens()) {
      String jar = tokenizer.nextToken();
      if (jar.indexOf("gemfire.jar") != -1 ||
          jar.indexOf("gemfirexd.jar") != -1) {
        File gemfireJar = new File(jar);
        try {
          csLoc = gemfireJar.toURL();
        } catch (Exception e) {}
        break;
      }
    }
    if (csLoc != null) {
      return csLoc;
    }
    // try the boot class path to fix bug 37394
    tokenizer = new StringTokenizer(System.getProperty("sun.boot.class.path"), File.pathSeparator);
    while(tokenizer.hasMoreTokens()) {
      String jar = tokenizer.nextToken();
      if (jar.indexOf("gemfire.jar") != -1 || jar.indexOf("gemfirexd.jar") != -1) {
        File gemfireJar = new File(jar);
        try {
          csLoc = gemfireJar.toURL();
        } catch (Exception e) {}
        break;
      }
    }
    return csLoc;
  }
  

  private final static String VER_FILE_NAME = "GemFireVersion.properties";
  private final static String JAR_VER_NAME = "gemfire.jar";

    public static void createVersionFile() {
        String jarVersion = stripSpaces(GemFireVersion.getJavaCodeVersion());
        File libDir = SystemAdmin.findGemFireLibDir();
        if (libDir == null) {
          throw new RuntimeException(LocalizedStrings.GemFireVersion_COULD_NOT_DETERMINE_PRODUCT_LIB_DIRECTORY.toLocalizedString());
        }
        File versionPropFile = new File(libDir, VER_FILE_NAME);
        Properties props = new Properties();
        props.setProperty(JAR_VER_NAME, jarVersion);
        try {
          FileOutputStream out = new FileOutputStream(versionPropFile);
          props.store(out, "Expected versions for this product build as of");
          out.close();
        } catch (IOException ex) {
          throw new RuntimeException(LocalizedStrings.GemFireVersion_COULD_NOT_WRITE_0_BECAUSE_1.toLocalizedString(new Object[] {versionPropFile, ex.toString()}));
        }
        System.out.println("Created \"" + versionPropFile + "\"");
    }
  /**
   * Encodes all available version information into a string and then
   * returns that string.
   */
  public static String asString() {
    StringWriter sw = new StringWriter(256);
    PrintWriter pw = new PrintWriter(sw);
    print(pw);
    pw.flush();
    return sw.toString();
  }
    /**
     * Prints all available version information (excluding source code
     * information) to the given <code>PrintWriter</code> in a
     * standard format.
     */
    public static void print(PrintWriter pw) {
      print(pw, true);
    }

    /**
     * Prints all available version information to the given
     * <code>PrintWriter</code> in a standard format.
     *
     * @param printSourceInfo
     *        Should information about the source code be printed?
     */
    public static void print(PrintWriter pw,
                             boolean printSourceInfo) {
        String jarVersion = stripSpaces(GemFireVersion.getJavaCodeVersion());
        pw.println("Java version:   " + jarVersion);
        String libVersion = stripSpaces(GemFireVersion.getNativeCodeVersion());
        pw.println("Native version: " + libVersion);
        SystemAdmin.findGemFireLibDir();
	
        if (printSourceInfo) {
          String sourceRevision = GemFireVersion.getSourceRevision();
          pw.println("Source revision: " + sourceRevision);

          String sourceRepository =
            GemFireVersion.getSourceRepository();
          pw.println("Source repository: " + sourceRepository);
        }

	InetAddress host = null;
	try {
	    host = SocketCreator.getLocalHost();
	} 
	catch (Throwable t) {
	     Error err;
	     if (t instanceof Error && SystemFailure.isJVMFailureError(
	         err = (Error)t)) {
	       SystemFailure.initiateFailure(err);
	       // If this ever returns, rethrow the error. We're poisoned
	       // now, so don't let this thread continue.
	       throw err;
	     }
	     // Whenever you catch Error or Throwable, you must also
	     // check for fatal JVM error (see above).  However, there is
	     // _still_ a possibility that you are dealing with a cascading
	     // error condition, so you also need to check to see if the JVM
	     // is still usable:
	     SystemFailure.checkFailure();
	}
        int cpuCount = Runtime.getRuntime().availableProcessors();
        pw.println(LocalizedStrings.GemFireVersion_RUNNING_ON_0.toLocalizedString(
                   host
                   + ", " + cpuCount + " cpu(s)"
                   + ", " + System.getProperty("os.arch")
                   + " " + System.getProperty("os.name")
                   + " " + System.getProperty("os.version")
                   ));
    }
	
    /**
     * Prints all available version information (excluding information
     * about the source code) to the given <code>PrintStream</code> in
     * a standard format.
     */
    public static void print(PrintStream ps) {
	print(ps, true);
    }

    /**
     * Prints all available version information to the given
     * <code>PrintStream</code> in a standard format.
     *
     * @param printSourceInfo
     *        Should information about the source code be printed?
     */
    public static void print(PrintStream ps,
                             boolean printSourceInfo) {
	PrintWriter pw = new PrintWriter(ps);
	print(pw, printSourceInfo);
	pw.flush();
    }
    
  ///////////////////////  Main Program  ////////////////////

//  private static final PrintStream out = System.out;
//  private static final PrintStream err = System.err;

  /**
   * Populates the gemfireVersion.properties file
   */
  public static void main(String[] args) {
      print(System.out);
  }

  private static final Pattern MAJOR_MINOR = Pattern.compile("(\\d+)\\.(\\d*)(.*)");
  private static final Pattern RELEASE = Pattern.compile("\\.(\\d*)(.*)");
  private static final Pattern MAJOR_MINOR_RELEASE = Pattern.compile("(\\d+)\\.(\\d*)\\.(\\d*)(.*)");
  
  public static int getMajorVersion(String v) {
    int majorVersion = 0;
    Matcher m = MAJOR_MINOR.matcher(v);
    if (m.matches()) {
      String digits = m.group(1);
      if (digits != null && digits.length() > 0) {
        majorVersion = Integer.decode(digits).intValue();
      }
    }
    return majorVersion;
  }

  public static int getMinorVersion(String v) {
    int minorVersion = 0;
    Matcher m = MAJOR_MINOR.matcher(v);
    if (m.matches()) {
      String digits = m.group(2);
      if (digits != null && digits.length() > 0) {
        minorVersion = Integer.decode(digits).intValue();
      }
    }
    return minorVersion;
  }
  public static int getRelease(String v) {
    int release = 0;
    Matcher m = MAJOR_MINOR.matcher(v);
    if (m.matches()) {
      String others = m.group(3);
      Matcher r = RELEASE.matcher(others);
      if (r.matches()) {
        String digits = r.group(1);
        if (digits != null && digits.length() > 0) {
          try {
            release = Integer.decode(digits).intValue();
          } catch (NumberFormatException e) {
            release = 0;
          }
        }
      }
    }
    return release;
  }
  
  public static int getBuild(String v) {
    int build = 0;
    Matcher m = MAJOR_MINOR_RELEASE.matcher(v);
    if (m.matches()) {
      String buildStr = m.group(4);
      Matcher b = RELEASE.matcher(buildStr);
      if (b.matches()) {
        String digits = b.group(1);
        if (digits != null && digits.length() > 0) {
          try {
            build = Integer.decode(digits).intValue();
          } catch (NumberFormatException e) {
            build = 0;
          }
        }
      }
    }
    return build;
  }
  
  /** 
   * Compare version's sections major, minor, release one by one
   * 
   * @return >0: v1 is newer than v2
   *          0: same
   *          <0: v1 is older than v2
   * @deprecated please use the {@link Version} class to read the version
   * of the local member and compare versions for backwards compatibility 
   * purposes. see also {@link SerializationVersions} for how to make backwards
   * compatible messages.
   */
  public static int compareVersions(String v1, String v2) {
    return compareVersions(v1, v2, true);
  }
  
  /* 
   * Compare version's sections major, minor, release one by one
   * 
   * @param v1 the first version
   * @param v2 the second version
   * @param includeBuild whether to also compare the build numbers
   * 
   * @return: >0: v1 is newer than v2
   *          0: same
   *          <0: v1 is older than v2
   */
  public static int compareVersions(String v1, String v2, boolean includeBuild) {
    int major1, minor1, release1, build1;
    int major2, minor2, release2, build2;
    
    if (v1 == null && v2 != null) return -1;
    if (v1 != null && v2 == null) return 1;
    if (v1 == null && v2 == null) return 0;
    
    major1 = getMajorVersion(v1);
    major2 = getMajorVersion(v2);
    
    minor1 = getMinorVersion(v1);
    minor2 = getMinorVersion(v2);
    
    release1 = getRelease(v1);
    release2 = getRelease(v2);

    if (major1 > major2) return 1;
    if (major1 < major2) return -1;
    
    if (minor1 > minor2) return 1;
    if (minor1 < minor2) return -1;

    if (release1 > release2) return 1;
    if (release1 < release2) return -1;
    
    if (includeBuild) {
      build1 = getBuild(v1);
      build2 = getBuild(v2);
      
      if (build1 > build2) return 1;
      if (build1 < build2) return -1;
    }

    return 0;
  }

  public String getNativeVersion() {
    return nativeVersion;
  }

  public void setNativeVersion(String nativeVersion) {
    this.nativeVersion = nativeVersion;
  }

  public static boolean isEnterpriseEdition() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return false;
    } else {
      return v.enterpriseEdition;
    }
  }

  public static String getClusterType() {
    GemFireVersion v = getInstance();
    if (v.error != null) {
      return "";
    } else {
      return v.clusterType;
    }
  }
}
