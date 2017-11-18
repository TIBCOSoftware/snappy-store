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
/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import java.util.Properties;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.iapi.services.info.JVMInfo;
import com.pivotal.gemfirexd.internal.iapi.services.info.ProductGenusNames;
import com.pivotal.gemfirexd.internal.iapi.services.info.ProductVersionHolder;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.error.ClientExceptionUtil;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.snappydataConstants;

/**
 * Static configuration properties for the thrift JDBC client including version,
 * driver name etc.
 */
public final class ClientConfiguration {

  public static final String DRIVER_NAME =
      "SnappyData Thrift Client JDBC Driver";

  public static String CURRENT_DRIVER_PROTOCOL() {
    return ClientSharedUtils.isThriftDefault()
        ? Attribute.SNAPPY_THRIFT_PROTOCOL : Attribute.SNAPPY_DNC_PROTOCOL;
  }

  public static final byte DRIVER_TYPE = snappydataConstants.DRIVER_JDBC;

  static final ProductVersionHolder thriftProductVersionHolder;

  // used by Client*Driver to accumulate load exceptions
  public static SQLException exceptionsOnLoadResources = null;

  public final static boolean jdbcCompliant = true;

  /** Constant for the GemFire version Resource Property entry */
  private static final String PRODUCT_NAME = "Product-Name";

  /** Constant for the GemFire version Resource Property entry */
  private static final String GEMFIRE_VERSION = "Product-Version";

  /**
   * For SnappyData product, the constant for the underlying GemFire version
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

  ////////////////////  Instance Fields  ////////////////////

  /** The name of the properties resource used to load this instance */
  private final String resourceName;

  /** Error message to display instead of the version information */
  private String error;

  /** The name of this product */
  private String productName;

  /**
   * This SnappyData store product version.
   */
  private String productVersion;

  /**
   * The version of underlying GemFire layer.
   */
  private String gemfireVersion;

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

  /** The singleton instance */
  private static final ClientConfiguration instance;

  static {
    ProductVersionHolder versionHolder = null;
    try {
      versionHolder = AccessController
          .doPrivileged(new PrivilegedExceptionAction<ProductVersionHolder>() {
            public ProductVersionHolder run() throws IOException {
              InputStream versionStream = ClientConfiguration.class
                  .getResourceAsStream(ProductGenusNames.THRIFT_INFO);
              return ProductVersionHolder
                  .getProductVersionHolderFromMyEnv(versionStream);
            }
          });
    } catch (java.security.PrivilegedActionException e) {
      exceptionsOnLoadResources = ClientExceptionUtil.newSQLException(
          SQLState.ERROR_PRIVILEGED_ACTION, e.getException());
    } catch (Throwable t) {
      exceptionsOnLoadResources = ClientExceptionUtil.newSQLException(
          SQLState.JAVA_EXCEPTION, t, t.getClass(), t.getMessage());
    }
    if (versionHolder == null && exceptionsOnLoadResources == null) {
      exceptionsOnLoadResources = ClientExceptionUtil.newSQLException(
          SQLState.MISSING_RESOURCE_BUNDLE, null,
          ProductGenusNames.THRIFT_INFO_PACKAGE,
          ProductGenusNames.THRIFT_INFO_FILE);
    }
    thriftProductVersionHolder = versionHolder;
    instance = new ClientConfiguration(SharedUtils.GFXD_VERSION_PROPERTIES);
  }

  protected ClientConfiguration(String resourceName) {
    this.resourceName = resourceName;

    Properties props = new Properties();
    InputStream is = getClass().getClassLoader().getResourceAsStream(
        resourceName);
    if (is == null) {
      is = ClassLoader.getSystemResourceAsStream(resourceName);
    }
    if (is == null) {
      this.error = "<Could not find resource " + resourceName + '>';
      return;
    }
    else {
      try {
        props.load(is);
        is.close();
      } catch (Exception ex) {
        this.error = "<Could not read properties from resource "
            + resourceName + " because: " + ex + '>';
        return;
      }
    }

    this.productName = props.getProperty(PRODUCT_NAME);
    this.productVersion = props.getProperty(GEMFIRE_VERSION);
    // below setting for SnappyData is to indicate the underlying GemFire
    // version being used in SnappyData product; for GemFire this will not
    // be set and instead this.productVersion is used where required
    String gfVersion = props.getProperty(UNDERLYING_GEMFIRE_VERSION);
    // special token "NOT SET" might be present that indicates no separate
    // GemFire version
    if (gfVersion != null && gfVersion.startsWith("NOT SET")) {
      gfVersion = null;
    }
    this.gemfireVersion = gfVersion;

    this.sourceDate = props.getProperty(SOURCE_DATE);
    this.sourceRevision = props.getProperty(SOURCE_REVISION);
    this.sourceRepository = props.getProperty(SOURCE_REPOSITORY);
    this.buildDate = props.getProperty(BUILD_DATE);
    this.buildId = props.getProperty(BUILD_ID);
    this.buildPlatform = props.getProperty(BUILD_PLATFORM);
    this.buildJavaVersion = props.getProperty(BUILD_JAVA_VERSION);
  }

  public static ClientConfiguration getInstance() {
    return instance;
  }

  public static ProductVersionHolder getProductVersionHolder() {
    return thriftProductVersionHolder;
  }

  /**
   * Check to see if the jvm version is such that JDBC 4.0 is supported
   */
  public static boolean supportsJDBC40() {
    return (JVMInfo.JDK_ID >= JVMInfo.J2SE_16);
  }

  /**
   * @return the productName
   */
  public String getProductName() {
    return productName;
  }

  /**
   * @return the productVersion
   */
  public String getProductVersion() {
    return productVersion;
  }

  /**
   * @return the gemfireVersion
   */
  public String getGemfireVersion() {
    return gemfireVersion;
  }

  /**
   * @return the sourceDate
   */
  public String getSourceDate() {
    return sourceDate;
  }

  /**
   * @return the sourceRevision
   */
  public String getSourceRevision() {
    return sourceRevision;
  }

  /**
   * @return the sourceRepository
   */
  public String getSourceRepository() {
    return sourceRepository;
  }

  /**
   * @return the buildDate
   */
  public String getBuildDate() {
    return buildDate;
  }

  /**
   * @return the buildId
   */
  public String getBuildId() {
    return buildId;
  }

  /**
   * @return the buildPlatform
   */
  public String getBuildPlatform() {
    return buildPlatform;
  }

  /**
   * @return the buildJavaVersion
   */
  public String getBuildJavaVersion() {
    return buildJavaVersion;
  }

  /**
   * @return the resourceName
   */
  public String getResourceName() {
    return resourceName;
  }

  /**
   * @return the error
   */
  public String getError() {
    return error;
  }
}
