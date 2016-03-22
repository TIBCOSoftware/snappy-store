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
 * Encodes information used to configure hydra client JDK versions.
 */
public class JDKVersionDescription extends AbstractDescription
implements Serializable {

  /** The logical name of this JDK version description */
  private String name;

  /** Remaining parameters, in alphabetical order */
  private List<String> javaHomes;

//------------------------------------------------------------------------------
// Constructors
//------------------------------------------------------------------------------

  public JDKVersionDescription() {
  }

//------------------------------------------------------------------------------
// Accessors, in aphabetical order after name
//------------------------------------------------------------------------------

  /**
   * Returns the logical name of this JDK version description.
   */
  public String getName() {
    return this.name;
  }

  /**
   * Sets the logical name of this JDK version description.
   */
  private void setName(String str) {
    this.name = str;
  }

  /**
   * Returns the Java homes.
   */
  public List<String> getJavaHomes() {
    return this.javaHomes;
  }

  /**
   * Sets the Java homes.
   */
  private void setJavaHomes(List<String> str) {
    this.javaHomes = str;
  }

//------------------------------------------------------------------------------
// Client configuration
//------------------------------------------------------------------------------

  /**
   * Returns the Java home for the supplied Java home.
   * Used by master when spawning JDK-versioned clients.
   */
  protected String getJavaHome(VmDescription vmd, String javaHome) {
    System.out.println("HEY called JDKVersionDescription.getJavaHome with javaHome=" + javaHome);
    if (javaHome.equals(BasePrms.DEFAULT)) {
      return vmd.getHostDescription().getJavaHome();
    } else {
      return javaHome;
    }
  }

  /**
   * Returns the classpath for the given host.
   * Used by master when spawning JDK-only versioned clients.
   */
  protected String getClassPath(VmDescription vmd, String javaHome) {
    Vector classpath = new Vector();

    // create JDK-versioned host description
    HostDescription hd = vmd.getHostDescription().copy();
    hd.setJavaHome(javaHome);

    // extra classpaths for this JDK version
    Vector extra = vmd.getUnconvertedExtraClassPath();
    if (extra != null && extra.size() > 0) {
      extra = EnvHelper.expandEnvVars(extra, hd);
      classpath.addAll(extra);
    }

    // junit.jar
    classpath.add(hd.getTestDir() + hd.getFileSep() + "junit.jar");

    // jtests
    classpath.add(hd.getTestDir());

    // extra jtests
    if (hd.getExtraTestDir() != null) {
      classpath.add(hd.getExtraTestDir());
    }

    // product jars
    String gfh = vmd.getHostDescription().getGemFireHome();
    if (gfh != null) {
      classpath.add(gfh + hd.getFileSep() + "lib"
                        + hd.getFileSep() + "gemfirexd-" +
              ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + ".jar");
    }

    return EnvHelper.asPath(classpath, hd);
  }

  /**
   * Returns the library path for the given host.
   * Used by master when spawning JDK-only versioned clients.
   */
  protected String getLibPath(VmDescription vmd, String javaHome) {
    Vector libpath = new Vector();

    // create versioned host description
    HostDescription hd = vmd.getHostDescription().copy();
    hd.setJavaHome(javaHome);

    // gemfire library path
    String gfh = vmd.getHostDescription().getGemFireHome();
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
    map.put(header + "javaHomes", this.getJavaHomes());
    return map;
  }

//------------------------------------------------------------------------------
// Configuration
//------------------------------------------------------------------------------

  /**
   * Creates JDK version descriptions from the JDK version parameters in the
   * test configuration.
   */
  protected static void configure(TestConfig config) {

    ConfigHashtable tab = config.getParameters();

    // create a description for each JDK version name
    Vector names = tab.vecAt(JDKVersionPrms.names, new HydraVector());

    for (int i = 0; i < names.size(); i++) {

      String name = (String)names.elementAt(i);

      // create JDK version description from test configuration parameters
      JDKVersionDescription jvd = createJDKVersionDescription(name, config, i);

      // save configuration
      if (jvd != null) {
        config.addJDKVersionDescription(jvd);
      }
    }
  }

  /**
   * Creates the JDK version description using test configuration parameters
   * and defaults.
   */
  private static JDKVersionDescription createJDKVersionDescription(String name,
                                       TestConfig config, int index) {

    ConfigHashtable tab = config.getParameters();

    JDKVersionDescription jvd = new JDKVersionDescription();
    jvd.setName(name);

    // javaHomes
    {
      Long key = JDKVersionPrms.javaHomes;
      Vector strs = tab.vecAtWild(key, index, null);
      if (strs != null && strs.size() > 0) {
        List<String> tmp = new ArrayList<String>();
        for (Iterator i = strs.iterator(); i.hasNext();) {
          String str = tab.getString(key, i.next());
          if (str == null) { // default
            tmp.add(BasePrms.DEFAULT);
          } else {
            tmp.add(str);
          }
        }
        if (tmp.size() == 1 && tmp.get(0).equals(BasePrms.DEFAULT)) {
          tmp = null; // only default values used
        }
        jvd.setJavaHomes(tmp);
      }
    }

    return (jvd.getJavaHomes() == null) ? null : jvd;
  }
}
