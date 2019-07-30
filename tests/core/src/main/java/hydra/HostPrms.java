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

import hydra.HostHelper.OSType;
import java.util.*;

/**
 *
 * A class used to store keys for host configuration settings.  The settings
 * are used to create instances of {@link HostDescription}.
 * <p>
 * The number of instances is gated by {@link #names}.  The remaining parameters
 * have the indicated default values.  If fewer non-default values than names
 * are given for these parameters, the remaining instances will use the last
 * value in the list.  See $JTESTS/hydra/hydra.txt for more details.
 *
 */

public class HostPrms extends BasePrms {

  public static final String HOST_NAME_PROPERTY = "hostName";

    /**
     *  (String(s))
     *  Logical names of the host descriptions.  Each name must be unique.
     *  Defaults to null.  See the include files in $JTESTS/hydraconfig for
     *  common configurations.
     */
    public static Long names;

  /**
   * (int(s))
   * Port on which the {@link hydra.Bootstrapper} was started on each host.
   * Defaults to {@link #NONE}.
   * <p>
   * This parameter is used to bootstrap a hydra hostagent on a remote host.
   * The port must match the one used to start a {@link hydra.Bootstrapper} on
   * the remote host.  A bootstrap port is required for remote hosts running
   * Windows, and for remote hosts running Unix when the hydra master is
   * on Windows.  All other hostagent bootstrapping relies on ssh.
   * </ul>
   *
   * @see hydra.Bootstrapper
   */
  public static Long bootstrapPort;

    /**
     *  (String(s) or List of list of String(s))
     *  Physical host names, if a simple list.  Otherwise, a list of lists where
     *  each list is a logical host {@link #name} prefix followed by a list of
     *  physical host names to which logical host names with that prefix are
     *  mapped round robin.
     *  <p>
     *  The local host can be specified as "localhost".  Default is the master
     *  host.
     */
    public static Long hostNames;
    public static SortedMap<String,Vector<String>> getHostNameMap() {
      Long key = hostNames;
      Vector list = tab().vecAt(key, null);
      if (list != null) {
        if (list.get(0) instanceof HydraVector) {
          // this is a list of lists, so treat it as a mapping
          SortedMap<String,Vector<String>> map = new TreeMap();
          for (int i = 0; i < list.size(); i++) {
            Vector<String> sublist = (Vector<String>)list.get(i);
            if (sublist.size() <= 1) {
              String s = "No physical host names given for logical host name "
                       + "prefix " + sublist.get(0);
              throw new HydraConfigException(s);
            }
            String logicalPrefix = sublist.get(0);
            Vector<String> physicalHosts = new Vector();
            for (int j = 1; j < sublist.size(); j++) {
              physicalHosts.add(sublist.get(j));
            }
            map.put(logicalPrefix, physicalHosts);
          }
          Log.getLogWriter().info("Host mapping: " + map);
          return map;
        } else {
          return null;
        }
      }
      return null;
    }

  /**
   * (String(s))
   * O/S type for each logical host.  For valid values, see {@link
   * HostHelper#OSType}.  Defaults to the O/S ltype of the hydra master.
   * <p>
   * This parameter must be configured appropriately in tests where at
   * least one host uses a different O/S than the hydra master.
   */
  public static Long osTypes;

    /**
     * (String(s))
     * Path to GemFire product directory for each logical host.  Defaults to
     * the value of -Dgemfire.home in the hydra master controller JVM.
     */
    public static Long gemfireHomes;

    /**
     *  (String(s))
     *  User directories.  Default is the master user directory.
     */
    public static Long userDirs;

    /**
     *  (String(s))
     *  Absolute base directories for managed resources that use them, for
     *  example, gemfire system logs and statarchives.  When set, a managed
     *  resource uses as its working directory the base plus the test name.
     *  Used when running tests on remote hosts to avoid costly network
     *  overhead.  Defaults to null, which is the master user.dir.
     *  <p>
     *  To move the remote directories back to the master user.dir after
     *  the test, run using {@link batterytest.BatteryTest} with
     *  <code>-DmoveRemoteDirs=true</code>.
     *  <p>
     *  See also {@link #resourceDirBaseMapFileName}.
     */
    public static Long resourceDirBases;

    /**
     *  (String)
     *  Absolute name of file containing a mapping of physical host names to
     *  absolute base directories, as a list of properties.  Overrides {@link
     *  #resourceDirBases}.  Defaults to null (no map file is used).
     */
    public static Long resourceDirBaseMapFileName;
    public static SortedMap getResourceDirBaseMap() {
      Long key = resourceDirBaseMapFileName;
      String fn = tab().stringAt(key, null);
      if (fn != null) {
        try {
          String newfn = EnvHelper.expandEnvVars(fn);
          return FileUtil.getPropertiesAsMap(newfn);
        } catch (Exception e) {
          String s = "Illegal value for: " + nameForKey(key);
          throw new HydraConfigException(s, e);
        }
      }
      return null;
    }

    /**
     * (String(s))
     * Path to test classes for each logical host.  Defaults to the value of
     * -DJTESTS in the hydra master controller JVM.
     */
    public static Long testDirs;

    /**
     * (String(s))
     * Path to auxiliary test classes for each logical host, used by GemFire
     * add-ons such as GFMon.  Defaults to the value of -DEXTRA_JTESTS in the
     * hydra master controller JVM.
     */
    public static Long extraTestDirs;

    /**
     * (String(s))
     * Path to Java home directories for each logical host.  Defaults to the
     * value of -Djava.home in the hydra master controller JVM.
     */
    public static Long javaHomes;

    /**
     *  (String(s))
     *  Vendors used by the VMs specified in {@link #javaHomes}. Default is
     *  the vendor used by the hydra master controller VM. Valid values are
     *  "Sun" (HotSpot), "IBM", "JRockit", "Apple", "Hitachi".
     */
    public static Long javaVendors;
    public static final String SUN = "Sun";
    public static final String IBM = "IBM";
    public static final String JROCKIT = "JRockit";
    public static final String APPLE = "Apple";
    public static final String HITACHI = "Hitachi";

    /**
     * Returns the java vendor for this VM. This is really the JVM type.
     * For example, it distinguishes Oracle HotSpot and Oracle JRockit.
     */
    public static String getJavaVendor() {
      String vendor = System.getProperty("java.vm.vendor");
      String vmname = System.getProperty("java.vm.name");
      if (vendor.contains("Sun")) {
        vendor = SUN;
      } else if (vendor.contains("IBM")) {
        vendor = IBM;
      } else if (vendor.contains("BEA")) {
        vendor = JROCKIT;
      } else if (vendor.contains("Hitachi")) {
        vendor = HITACHI;
      } else if (vendor.contains("Apple")) {
        vendor = APPLE;
      } else if (vendor.contains("Oracle") && vmname.contains("JRockit")) {
        vendor = JROCKIT;
      } else if ((vendor.contains("Oracle") || vendor.contains("OpenJDK")) &&
          (vmname.contains("HotSpot") || vmname.contains("OpenJDK"))) {
        vendor = SUN;
      } else {
        String s = "Unable to classify JVM with vendor: " + vendor
                 + " and name: " + vmname;
        throw new HydraConfigException(s);
      }
      return vendor;
    }

    /**
     * Returns the i'th value for {@link #javaVendors};  Defaults to the
     * vendor in the invoking VM, which, during test configuration, is the
     * hydra master controller.
     */
    public static String getJavaVendor( int i ) {
      Long key = javaVendors;
      String val = tab().stringAtWild(key, i, getJavaVendor());
      if ( val.equalsIgnoreCase( SUN ) ) {
        return SUN;
      } else if ( val.equalsIgnoreCase( IBM ) ) {
        return IBM;
      } else if ( val.equalsIgnoreCase( JROCKIT ) ) {
        return JROCKIT;
      } else if ( val.equalsIgnoreCase( HITACHI ) ) {
        return HITACHI;
      } else if ( val.equalsIgnoreCase( APPLE ) ) {
        return APPLE;
      } else {
        throw new HydraConfigException( "Illegal value for " + nameForKey( key ) + ": " + val );
      }
    }

    /**
     * (String(s))
     * Path to JProbe home directories for each logical host.  Defaults to the
     * value of -DJPROBE in the hydra master controller JVM.
     */
    public static Long jprobeHomes;

    /**
     * (String(s))
     * Path to Ant home directories.  Defaults to the value of -DANT_HOME
     * in the hydra master controller JVM.
     */
    public static Long antHomes;

    static {
        setValues( HostPrms.class );
    }

    public static void main( String args[] ) {
        Log.createLogWriter( "hostprms", "info" );
        dumpKeys();
    }
}
