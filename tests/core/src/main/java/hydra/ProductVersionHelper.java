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

import java.util.Properties;

public class ProductVersionHelper {

  public static final String PRODUCT_NAME = "Product-Name";
  public static final String PRODUCT_VERSION = "Product-Version";
  public static final String SOURCE_DATE = "Source-Date";
  public static final String SOURCE_REVISION = "Source-Revision";
  public static final String SOURCE_REPOSITORY = "Source-Repository";
  public static final String BUILD_ID = "Build-Id";
  public static final String BUILD_DATE = "Build-Date";
  public static final String BUILD_JAVA_VERSION = "Build-Java-Version";
  public static final String BUILD_PLATFORM = "Build-Platform";
  public static final String SNAPPYRELEASEVERSION = "Product-Version";

  public static Properties getInfo() {
    Properties p1 = null;
    String productVersion = "";
    String snappyReleaseVersion = "";
    try {
      Class c = Class.forName("com.pivotal.gemfirexd.internal.iapi.services.info.ProductVersionHolder");
      p1 = (Properties)MasterController.invoke("hydra.gemfirexd.GfxdTestConfig",
                                               "getGemFireXDProductVersion");
      if (p1 != null) {
        productVersion += p1.getProperty(PRODUCT_NAME) + " "
                        + p1.getProperty(PRODUCT_VERSION);
        snappyReleaseVersion = p1.getProperty(PRODUCT_VERSION);
      }
    } catch (ClassNotFoundException e) {
    }
    Properties p2 = null;
    try {
      Class c = Class.forName("com.gemstone.gemfire.DataSerializable");
      p2 = TestConfig.getGemFireProductVersion();
      if (p2 != null) {
        if (productVersion.length() > 0) {
          productVersion += " ";
        }
        productVersion += p2.getProperty(PRODUCT_NAME) + " "
                        + p2.getProperty(PRODUCT_VERSION);
        snappyReleaseVersion = p2.getProperty(PRODUCT_VERSION);
      }
    } catch (ClassNotFoundException e) {
    }
    /*
    if (p1 != null && p2 != null) {
      if (!p1.getProperty(SOURCE_DATE).equals(p2.getProperty(SOURCE_DATE)) ||
          !p1.getProperty(SOURCE_REVISION).equals(p2.getProperty(SOURCE_REVISION)) ||
          !p1.getProperty(SOURCE_REPOSITORY).equals(p2.getProperty(SOURCE_REPOSITORY)) ||
          !p1.getProperty(BUILD_ID).equals(p2.getProperty(BUILD_ID)) ||
          !p1.getProperty(BUILD_DATE).equals(p2.getProperty(BUILD_DATE)) ||
          !p1.getProperty(BUILD_JAVA_VERSION).equals(p2.getProperty(BUILD_JAVA_VERSION)) ||
          !p1.getProperty(BUILD_PLATFORM).equals(p2.getProperty(BUILD_PLATFORM))) {
            String s = "Products not from same build: " + p1 + " and " + p2;
            throw new HydraConfigException(s);
          }
    }
    */
    Properties p = (p1 == null) ? p2 : p1;
    if (p != null) {
      p.setProperty(PRODUCT_VERSION, productVersion);
      p.setProperty(SNAPPYRELEASEVERSION, snappyReleaseVersion);
    }
    return p;
  }
}
