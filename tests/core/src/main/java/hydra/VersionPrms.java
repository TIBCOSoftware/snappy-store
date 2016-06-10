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

/**
 * A class used to store keys for version configuration settings.  The settings
 * are used to create instances of {@link VersionDescription}.  These can be
 * referenced by instances of {@link ClientDescription} as configured using
 * {@link ClientPrms-versionNames}.
 * <p>
 * The number of description instances is gated by {@link #names}.  For other
 * parameters, if fewer values than names are given, the remaining instances
 * will use the last value in the list.  See $JTESTS/hydra/hydra.txt for more
 * details.
 * <p>
 * Unused parameters default to null, except where noted.  This uses the
 * default, except where noted.
 * <p>
 * Values and fields of a parameter can be set to {@link #DEFAULT},
 * except where noted.  This uses the default, except where noted.
 * <p>
 * Values and fields of a parameter can use oneof, range, or robing
 * except where noted, but each description created will use a fixed value
 * chosen at test configuration time.  Use as a task attribute is illegal.
 */
public class VersionPrms extends BasePrms {

  /** Supported values for {@link #version}. */
  public static final String[] SUPPORTED_VERSIONS = {"100", "130", "131", "141"};

  /**
   * (String(s))
   * Logical names of the version descriptions.  Each name must be unique.
   * Defaults to null.  Not for use with oneof, range, or robing.
   */
  public static Long names;

  /**
   * (String(s))
   * GemFire home directories used by hydra client VMs configured to use this
   * description.  If supplied, values must be absolute paths to the product
   * directory.
   * <p>
   * Defaults to null if {@link #version} is not set, otherwise defaults to the
   * release for the version found in "/export/gcm/where/gemfire/releases".
   * The release location can be modified by passing the system property
   * <code>RELEASE_DIR</code> to BatteryTest or MasterController.
   */
  public static Long gemfireHome;

  /**
   * (String(s))
   * Version of the GemFire product used by hydra client VMs configured to use
   * this description.  Legal values are given in {@link #SUPPORTED_VERSIONS}.
   * The version must be consistent with the corresponding value in {@link
   * #gemfireHome}.  The version is used to prepend hydra conversion code and
   * version-specific test code in $JTESTS/../../tests<version>/classes to the
   * client classpath.  Clients are configured to use version description using
   * {@link ClientPrms#versionNames}.
   */
  public static Long version;

  /**
   * (Comma-separated lists of String(s))
   * Versions of the GemFire product used by hydra client VMs configured to use
   * this description. Legal values are given in {@link #SUPPORTED_VERSIONS}.
   * Overrides {@link #version} and ignores {@link #gemfireHome}, so the version
   * is always the one found in /export/gcm/where/gemfireXD/releases.
   * <p>
   * Hydra starts a JVM that has a configured list of versions at the first
   * version given. Each time the JVM is restarted, it is brought back at
   * the next version in its list. Once the last version is reached, the JVM
   * stays at that version for all further restarts.
   * <p>
   * This feature was put in place for tests that do rolling upgrades.
   */
  public static Long versions;

//------------------------------------------------------------------------------
// parameter setup
//------------------------------------------------------------------------------

  static {
    setValues(VersionPrms.class);
  }
}
