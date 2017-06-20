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
 *
 *  Encodes information needed to describe and start a hostagent process.
 *
 */
public class HostAgentDescription extends AbstractDescription
implements Serializable {

  /** The logical name for the hostagent with this description */
  private String name;

  /** The host description used to create hostagents */
  private HostDescription hd;

  /** The classpath for hostagents started from this description */
  private String classPath;

  /** The java library path for hostagents started from this description */
  private String libPath;

  /** Whether this hostagent should archive stats */
  private boolean archiveStats;

  //////////////////////////////////////////////////////////////////////////////
  ////    CONSTRUCTORS                                                      ////
  //////////////////////////////////////////////////////////////////////////////

  public HostAgentDescription() {
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    ACCESSORS                                                         ////
  //////////////////////////////////////////////////////////////////////////////

  public String getName() {
    return this.name;
  }
  protected void setName( String name ) {
    this.name = name;
  }
  public HostDescription getHostDescription() {
    return this.hd;
  }
  protected void setHostDescription( HostDescription hd ) {
    this.hd = hd;
  }
  public String getClassPath() {
    return this.classPath;
  }
  protected void setClassPath( String path ) {
    this.classPath = path;
  }
  public String getLibPath() {
    return this.libPath;
  }
  protected void setLibPath( String path ) {
    this.libPath = path;
  }
  public boolean getArchiveStats() {
    return this.archiveStats;
  }
  protected void setArchiveStats(boolean b) {
    this.archiveStats = b;
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    PRINTING                                                          ////
  //////////////////////////////////////////////////////////////////////////////

  public SortedMap toSortedMap() {
    SortedMap map = new TreeMap();
    String header = this.getClass().getName() + "." + this.getName() + ".";
    map.put( header + "hostName", this.getHostDescription().getName() );
    if ( this.getClassPath() != null )
      map.put( header + "classPath", this.getClassPath() );
    if ( this.getLibPath() != null )
      map.put( header + "libPath", this.getLibPath() );
    map.put( header + "archiveStats", this.getArchiveStats() );
    return map;
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    CONFIGURATION                                                     ////
  //////////////////////////////////////////////////////////////////////////////

  /**
   *  Creates hostagent descriptions from the hostagent parameters in the test
   *  configuration.
   */
  protected static void configure( TestConfig config ) {
    // create hostagents for hadoop cluster hosts
    for (HostDescription hd : config.getHadoopHostDescriptions().values()) {
      configure(config, hd, true /* archiveStats */);
    }
    // create hostagents for configured hosts
    for (HostDescription hd : config.getHostDescriptions().values()) {
      boolean b = config.tab().booleanAt(Prms.archiveStatsForHostAgent, false);
      configure(config, hd, b /* archiveStats */);
    }
  }

  private static void configure(TestConfig config, HostDescription hd, boolean archive) {
    String name = "hostagent_" + hd.getHostName();
    HostAgentDescription had = config.getHostAgentDescription(name);
    if (had == null) {
      had = new HostAgentDescription();
      had.setName(name);

      // host description
      had.setHostDescription( hd );

      // classPath -- test classes and product jars
      Vector classPath = new Vector();
      classPath.add( hd.getTestDir() );
      if ( hd.getExtraTestDir() != null ) {
        classPath.add( hd.getExtraTestDir() );
      }
      if (hd.getGemFireHome() != null) {
        classPath.add(hd.getGemFireHome() + hd.getFileSep() + "lib"
            + hd.getFileSep() + "snappydata-store-" +
            ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + ".jar");
        classPath.add(VmDescription.getAllSnappyJars(hd.getGemFireHome() + hd.getFileSep() +
            ".." + hd.getFileSep() + "snappy" + hd.getFileSep() + "jars"));
      }

      //Needed to run DUnit on multiple hosts
      classPath.add(hd.getTestDir() + hd.getFileSep() + "junit.jar");

      //Needed to run hydra tests on multiple hosts
      classPath.add(hd.getTestDir() + hd.getFileSep() + ".." + hd.getFileSep() + ".." +
          hd.getFileSep() + "libs" + hd.getFileSep() + "snappydata-store-hydra-tests-" +
          ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + "-all.jar");

      classPath.add(VmDescription.getSnappyJarPath(hd.getGemFireHome() +
          hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + ".." +
          hd.getFileSep(), "snappydata-store-scala-tests*tests.jar"));

      had.setClassPath(EnvHelper.asPath(classPath, hd));

      // libPath
      Vector libPath = new Vector();
      if ( hd.getGemFireHome() != null ) {
        libPath.add(hd.getGemFireHome() + hd.getFileSep() + "lib");
        libPath.add(hd.getGemFireHome() + hd.getFileSep() + ".."
                                        + hd.getFileSep() + "hidden"
                                        + hd.getFileSep() + "lib");
      }
      had.setLibPath(EnvHelper.asPath(libPath, hd));
      had.setArchiveStats(archive);

      config.addHostAgentDescription( had );

    } else {
      had.getHostDescription().checkEquivalent(hd);
    }
  }
}
