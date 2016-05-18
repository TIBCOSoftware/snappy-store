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

import util.TestException;

import java.io.File;
import java.io.FilenameFilter;
import java.io.Serializable;
import java.util.*;

/**
 *
 *  Encodes information needed to describe and start a VM.
 *
 */
public class VmDescription extends AbstractDescription
implements Serializable {

  /** The logical name for vms with this description */
  private String name;

  /** The host description used to create vms */
  private HostDescription hd;

  /** The type of vm to use */
  private String type;

  /** The classpath for vms started from this description */
  private String classPath;

  /** The classpaths added by configuration methods in hydra/gemfirexd/*Description.java, if any */
  private List<String> gemfirexdClassPaths;

  /** The java library path for vms started from this description */
  private String libPath;

  /** The unconverted extra classpath */
  private transient Vector unconvertedExtraClassPath;

  /** The unconverted extra library path */
  private transient Vector unconvertedExtraLibPath;

  /** The extra options to use on the java command line that starts the vm */
  private String extraVMArgs;

  //////////////////////////////////////////////////////////////////////////////
  ////    CONSTRUCTORS                                                      ////
  //////////////////////////////////////////////////////////////////////////////

  public VmDescription() {
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
  public String getType() {
    return this.type;
  }
  protected void setType( String type ) {
    this.type = type;
  }
  public String getClassPath() {
    if (this.gemfirexdClassPaths == null) {
      return this.classPath;
    } else {
      String tmp = this.classPath;
      for (String gemfirexdClassPath : this.gemfirexdClassPaths) {
        tmp = gemfirexdClassPath + this.hd.getPathSep() + tmp;
      }
      return tmp;
    }
  }
  protected void setClassPath( String path ) {
    this.classPath = path;
  }
  public List<String> getGemFireXDClassPaths() {
    return this.gemfirexdClassPaths;
  }
  public void setGemFireXDClassPaths(List<String> paths) {
    this.gemfirexdClassPaths = paths;
  }
  public String getLibPath() {
    return this.libPath;
  }
  protected void setLibPath( String path ) {
    this.libPath = path;
  }
  public String getExtraVMArgs() {
    return this.extraVMArgs;
  }
  public void setExtraVMArgs( String args ) {
    this.extraVMArgs = args;
  }
  public void addExtraVMArg(String arg) {
    this.extraVMArgs += " " + arg;
  }
  public Vector getUnconvertedExtraClassPath() {
    return this.unconvertedExtraClassPath;
  }
  protected void setUnconvertedExtraClassPath(Vector path) {
    this.unconvertedExtraClassPath = path;
  }
  public Vector getUnconvertedExtraLibPath() {
    return this.unconvertedExtraLibPath;
  }
  protected void setUnconvertedExtraLibPath(Vector path) {
    this.unconvertedExtraLibPath = path;
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    PRINTING                                                          ////
  //////////////////////////////////////////////////////////////////////////////

  public SortedMap toSortedMap() {
    SortedMap map = new TreeMap();
    String header = this.getClass().getName() + "." + this.getName() + ".";
    map.put( header + "hostName", this.getHostDescription().getName() );
    map.put( header + "type", this.getType() );
    if ( this.getClassPath() != null )
      map.put( header + "classPath", this.getClassPath() );
    if ( this.getLibPath() != null )
      map.put( header + "libPath", this.getLibPath() );
    if ( this.getExtraVMArgs() != null )
      map.put( header + "extraVMArgs", this.getExtraVMArgs() );
    return map;
  }

  protected static String getSnappyJarPath(String jarPath, final String jarName) {
    String snappyJar = null;
    try {
      File parent = new File(jarPath);
      File[] files = parent.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          if (name.startsWith(jarName))
            return true;
          else return false;
        }
      });
      File snappyJarFile = files[0];
      snappyJar = snappyJarFile.getAbsolutePath();
    } catch (Exception e) {
      Log.getLogWriter().info("Unable to find " + jarName + " jar at " + jarPath + " location.");
    }
    return snappyJar;
  }

  //////////////////////////////////////////////////////////////////////////////
  ////    CONFIGURATION                                                     ////
  //////////////////////////////////////////////////////////////////////////////

  /**
   *  Creates vm descriptions from the vm parameters in the test
   *  configuration.
   */
  protected static void configure( TestConfig config ) {

    ConfigHashtable tab = config.getParameters();

    // create a description for each vm name
    Vector names = tab.vecAt( VmPrms.names, new HydraVector() );

    for ( int i = 0; i < names.size(); i++ ) {

      VmDescription vmd = new VmDescription();

      // name
      String name = (String) names.elementAt(i);
      vmd.setName( name );

      // hostName
      String hostName = tab.stringAtWild( VmPrms.hostNames, i, null );
      if ( hostName == null )
        throw new HydraConfigException( "Missing " + BasePrms.nameForKey( VmPrms.hostNames ) );
      HostDescription hd = config.getHostDescription( hostName );
      if ( hd == null )
        throw new HydraConfigException( "Undefined value in " + BasePrms.nameForKey( VmPrms.hostNames ) + ": " + hostName );
      vmd.setHostDescription( hd );

      // type
      String type = VmPrms.getType( i, hd.getJavaVendor() );
      vmd.setType( type );

      // classPath
      Vector classPath = new Vector();

      // classPath -- custom classpath
      {
        Long key = VmPrms.extraClassPaths;
        Vector paths = tab.vecAtWild(key, i, null);
        if (paths != null) {
          for (Iterator it = paths.iterator(); it.hasNext();) {
            String path = tab.getString(key, it.next());
            if (path == null || path.equalsIgnoreCase(BasePrms.NONE)) {
              it.remove();
            }
          }

          // cache unconverted extra classpaths for versioning
          vmd.setUnconvertedExtraClassPath(paths);

          if (paths.size() > 0) {
            paths = EnvHelper.expandEnvVars(paths, hd);
            classPath.addAll(paths);
          }
        }
        // @todo lises deal with case when this is meant to be used as-is
        //             and needs no conversion except perhaps pseudo-envvars
      }

      // classPath -- junit.jar
      classPath.add(hd.getTestDir() + hd.getFileSep() + "junit.jar");

      // classPath -- test classes
      classPath.add(hd.getTestDir());

      if (hd.getExtraTestDir() != null) {
        classPath.add(hd.getExtraTestDir());
      }

      // classPath -- product jars
      if (hd.getGemFireHome() != null) {
        classPath.add(getSnappyJarPath(hd.getGemFireHome() + hd.getFileSep() + ".." + hd.getFileSep() + "snappy" + hd.getFileSep() + "lib", "snappydata-assembly"));
      }

      // classPath -- test jars
      classPath.add(hd.getTestDir() + hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + "libs" + hd.getFileSep() + "gemfirexd-hydra-tests-" +
              ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + "-all.jar");
      classPath.add(getSnappyJarPath(hd.getGemFireHome() + hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + "dtests" + hd.getFileSep() +
              "build-artifacts" + hd.getFileSep() + "scala-2.10" + hd.getFileSep() + "libs", "gemfirexd-scala-tests"));

      // classPath -- set at last
      vmd.setClassPath(EnvHelper.asPath(classPath, hd));

      // libPath
      Vector libPath = new Vector();
      if ( hd.getGemFireHome() != null ) {
        libPath.add(hd.getGemFireHome() + hd.getFileSep() + "lib");
        libPath.add(hd.getGemFireHome() + hd.getFileSep() + ".."
                                        + hd.getFileSep() + "hidden"
                                        + hd.getFileSep() + "lib");
      }
      Vector extraLibPath = tab.vecAtWild( VmPrms.extraLibPaths, i, null );

      // cache unconverted extra library path for versioning
      vmd.setUnconvertedExtraLibPath(extraLibPath);

      if ( extraLibPath != null ) {
        extraLibPath = EnvHelper.expandEnvVars(extraLibPath, hd);
        // @todo lises deal with case when this is meant to be used as-is
        //             and needs no conversion except perhaps pseudo-envvars
        libPath.addAll( extraLibPath );
      }
      vmd.setLibPath(EnvHelper.asPath(libPath, hd));

      // extraVMArgs
      String extraVMArgs = VmPrms.getExtraVMArgs( i, hd.getJavaVendor(), vmd.getType() );
      vmd.setExtraVMArgs( extraVMArgs.trim() );

      config.addVmDescription( vmd );
    }
  }
}
