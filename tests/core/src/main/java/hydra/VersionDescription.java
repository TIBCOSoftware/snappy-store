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

package hydra;

import java.io.Serializable;
import java.util.*;

/**
 * Encodes information used to configure hydra client versions.
 */
public class VersionDescription extends AbstractDescription
implements Serializable {

  /** The logical name of this version description */
  private String name;

  /** Remaining parameters, in alphabetical order */
  private String  gemfireHome;
  private String  version;
  private Set<String> versions;

  /** Map of versions to gemfire homes */
  private Map<String,String> mapping;

//------------------------------------------------------------------------------
// Constructors
//------------------------------------------------------------------------------

  public VersionDescription() {
  }

//------------------------------------------------------------------------------
// Accessors, in aphabetical order after name
//------------------------------------------------------------------------------

  /**
   * Returns the logical name of this version description.
   */
  public String getName() {
    return this.name;
  }

  /**
   * Sets the logical name of this version description.
   */
  private void setName(String str) {
    this.name = str;
  }

  /**
   * Returns the gemfire home.
   */
  public String getGemFireHome() {
    return this.gemfireHome;
  }

  /**
   * Sets the gemfire home.
   */
  private void setGemFireHome(String str) {
    this.gemfireHome = str;
  }

  /**
   * Returns the version.
   */
  public String getVersion() {
    return this.version;
  }

  /**
   * Sets the version.
   */
  private void setVersion(String str) {
    this.version = str;
  }

  /**
   * Returns the versions.
   */
  public Set<String> getVersions() {
    return this.versions;
  }

  /**
   * Sets the versions.
   */
  private void setVersions(Set<String> str) {
    this.versions = str;
  }

  /**
   * Returns the mapping of versions fo gemfire homes.
   */
  protected Map<String,String> getVersionMapping() {
    return this.mapping;
  }

  /**
   * Sets the mapping of versions fo gemfire homes.
   */
  private void setVersionMapping(Map<String,String> map) {
    this.mapping = map;
  }

//------------------------------------------------------------------------------
// Client configuration
//------------------------------------------------------------------------------

  /**
   * Returns the gemfire home for the version.
   * Used by master when spawning versioned clients.
   */
  protected String getGemFireHome(VmDescription vmd, String version) {
    if (version.equals(BasePrms.DEFAULT)) {
      if (this.mapping.get(version) != BasePrms.DEFAULT) {
        String s = "Unexpected mismatch: version=" + version
                 + " gemfireHome=" + this.mapping.get(version);
        throw new HydraInternalException(s);
      } else {
        return vmd.getHostDescription().getGemFireHome();
      }
    } else {
      return this.mapping.get(version);
    }
  }

  /**
   * Returns the classpath for the given host.
   * Used by master when spawning versioned clients.
   */
  protected String getClassPath(VmDescription vmd, String version,
                                                   String javaHome) {
    Vector classpath = new Vector();
    String gfh = getGemFireHome(vmd, version);

    // create versioned host description
    HostDescription hd = vmd.getHostDescription().copy();
    hd.setGemFireHome(gfh);
    if (javaHome != null) {
      hd.setJavaHome(javaHome);
    }

    // gemfirexd class path
    List<String> gemfirexdClassPaths = vmd.getGemFireXDClassPaths();
    if (gemfirexdClassPaths != null) {
      for (String gemfirexdClassPath : gemfirexdClassPaths) {
        String path = gemfirexdClassPath.replace(
            vmd.getHostDescription().getGemFireHome(), gfh);
        classpath.add(path);
      }
    }

    // extra classpaths for this version
    Vector extra = vmd.getUnconvertedExtraClassPath();
    if (extra != null && extra.size() > 0) {
      extra = EnvHelper.expandEnvVars(extra, hd);
      classpath.addAll(extra);
    }

    // junit.jar
    classpath.add(hd.getTestDir() + hd.getFileSep() + "junit.jar");

    // conversion classes for this version (must precede jtests)
    if (version != null && version != BasePrms.DEFAULT) {
      classpath.add(hd.getTestDir()
                + hd.getFileSep() + ".." + hd.getFileSep() + ".."
                + hd.getFileSep() + "tests" + version
                + hd.getFileSep() + "classes");
    }

    // jtests
    classpath.add(hd.getTestDir());

    // extra jtests
    if (hd.getExtraTestDir() != null) {
      classpath.add(hd.getExtraTestDir());
    }

    // test jars
    classpath.add(hd.getTestDir() + hd.getFileSep() + ".." + hd.getFileSep() + ".." +
        hd.getFileSep() + ".." + hd.getFileSep() + "libs" + hd.getFileSep() + "snappydata-store-hydra-tests-" +
        ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + "-all.jar");

    classpath.add(VmDescription.getSnappyJarPath(hd.getGemFireHome() +
        hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + ".." +
        hd.getFileSep(), "snappydata-store-scala-tests*tests.jar"));

    // product jars for this version
    classpath.add(gfh + hd.getFileSep() + "lib"
        + hd.getFileSep() + "snappydata-store-" +
        ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + ".jar");

    return EnvHelper.asPath(classpath, hd);
  }

  /**
   * Returns the library path for the given host.
   * Used by master when spawning versioned clients.
   */
  protected String getLibPath(VmDescription vmd, String version,
                                                 String javaHome) {
    Vector libpath = new Vector();
    String gfh = getGemFireHome(vmd, version);

    // create versioned host description
    HostDescription hd = vmd.getHostDescription().copy();
    hd.setGemFireHome(gfh);
    if (javaHome != null) {
      hd.setJavaHome(javaHome);
    }

    // gemfire library path for this version
    libpath.add(gfh + hd.getFileSep() + "lib");
    libpath.add(gfh + hd.getFileSep() + ".."
                    + hd.getFileSep() + "hidden"
                    + hd.getFileSep() + "lib");

    // extra library path for this version
    Vector extra = vmd.getUnconvertedExtraLibPath();
    if (extra != null && extra.size() > 0) {
      extra = EnvHelper.expandEnvVars(extra, hd);
      libpath.addAll(extra);
    }

    return EnvHelper.asPath(libpath, hd);
  }

//------------------------------------------------------------------------------
// Printing
//------------------------------------------------------------------------------

  public SortedMap toSortedMap() {
    SortedMap map = new TreeMap();
    String header = this.getClass().getName() + "." + this.getName() + ".";
    map.put(header + "gemfireHome", this.getGemFireHome());
    map.put(header + "version", this.getVersion());
    map.put(header + "versions", this.getVersions());
    map.put(header + "versionMapping", this.getVersionMapping());
    return map;
  }

//------------------------------------------------------------------------------
// Configuration
//------------------------------------------------------------------------------

  /**
   * Creates version descriptions from the version parameters in the test
   * configuration.
   */
  protected static void configure(TestConfig config) {

    ConfigHashtable tab = config.getParameters();

    // create a description for each version name
    Vector names = tab.vecAt(VersionPrms.names, new HydraVector());

    for (int i = 0; i < names.size(); i++) {

      String name = (String)names.elementAt(i);

      // create version description from test configuration parameters
      VersionDescription vd = createVersionDescription(name, config, i);

      // save configuration
      if (vd != null) {
        config.addVersionDescription(vd);
      }
    }
  }

  /**
   * Creates the version description using test configuration parameters and
   * product defaults.
   */
  private static VersionDescription createVersionDescription(String name,
                                           TestConfig config, int index) {

    ConfigHashtable tab = config.getParameters();

    VersionDescription vd = new VersionDescription();
    vd.setName(name);

    // gemfireHome
    {
      Long key = VersionPrms.gemfireHome;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str != null) {
        vd.setGemFireHome(str);
      }
    }
    // version
    {
      Long key = VersionPrms.version;
      String str = tab.getString(key, tab.getWild(key, index, null));
      if (str != null) {
        vd.setVersion(getVersion(str, key));
      }
    }
    // versions
    {
      Long key = VersionPrms.versions;
      Vector strs = tab.vecAtWild(key, index, null);
      if (strs != null && strs.size() > 0) {
        Set<String> tmp = new LinkedHashSet();
        for (Iterator i = strs.iterator(); i.hasNext();) {
          String str = tab.getString(key, i.next());
          if (str == null) { // default
            tmp.add(BasePrms.DEFAULT);
          } else {
            tmp.add(getVersion(str, key));
          }
        }
        vd.setVersions(tmp);
        vd.setVersion(null); //override version
      }
    }
    // postprocess
    {
      Map<String,String> map = new LinkedHashMap();
      if (vd.getVersions() == null) {
        // use the version parameter
        if (vd.getVersion() == null) {
          // version was set to "default"
          if (vd.getGemFireHome() == null) {
            // just default to the host description
            return null;
          } else {
            // use the specified gemfire home for version "default"
            map.put(BasePrms.DEFAULT, vd.getGemFireHome());
          }
        } else {
          // version was set to a supported release
          if (vd.getGemFireHome() == null) {
            // look up the gemfire home for the release
            map.put(vd.getVersion(), getGemFireHome(vd.getVersion()));
          } else {
            map.put(vd.getVersion(), vd.getGemFireHome());
          }
        }
      } else {
        // use the versions parameter
        for (String ver : vd.getVersions()) {
          // look up the gemfire home for the release
          map.put(ver, getGemFireHome(ver));
        }
      }
      vd.setVersionMapping(map);
    }
    return vd;
  }

//------------------------------------------------------------------------------
// Version configuration support
//------------------------------------------------------------------------------

  /**
   * Returns the approved version for the given string.
   * @throws HydraConfigException if the version is unsupported.
   */
  private static String getVersion(String str, Long key) {
    for (int i = 0; i < VersionPrms.SUPPORTED_VERSIONS.length; i++) {
      if (str.equals(VersionPrms.SUPPORTED_VERSIONS[i])) {
        return str;
      }
    }
    String s = BasePrms.nameForKey(key) + " is unsupported: " + str;
    throw new HydraConfigException(s);
  }

//------------------------------------------------------------------------------
// GemFire home configuration support
//------------------------------------------------------------------------------

  /**
   * Returns the path to the gemfire release corresponding to the given version.
   */
  public static String getGemFireHome(String version)
  {
    String gemfireHome;
    if (version.equals(BasePrms.DEFAULT)) {
      gemfireHome = BasePrms.DEFAULT;
    } else if (version.equals("100")) {
      gemfireHome = System.getProperty("RELEASE_DIR", "/export/gcm/where/gemfireXD/releases")
              + "/GemFireXD" + dotVersionFor(version) + "-all/product";
    } else {
      gemfireHome = System.getProperty("RELEASE_DIR", "/export/gcm/where/gemfireXD/releases")
              + "/GemFireXD" + dotVersionFor(version) + "-all/Linux/product";
    }
    return gemfireHome;
  }

  /**
   * Converts the version string to a dotted format, for example, "100" to "1.0.0".
   */
  public static String dotVersionFor(String version) {
    if (version == null) {
      throw new IllegalArgumentException("version is null");
    }
    String dotVersion = "" + version.charAt(0);
    for (int i = 1; i < version.length(); i++) {
      dotVersion += "." + version.charAt(i);
    }
    return dotVersion;
  }
}
