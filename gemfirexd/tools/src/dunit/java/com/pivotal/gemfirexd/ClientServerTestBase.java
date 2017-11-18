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

package com.pivotal.gemfirexd;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionByExpressionResolver;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;

abstract class ClientServerTestBase extends DistributedSQLTestBase {

  ClientServerTestBase(String name) {
    super(name);
  }

  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
    // delete the top-level datadictionary created by some tests in this suite
    File dir = new File("datadictionary");
    boolean result = TestUtil.deleteDir(dir);
    TestUtil.getLogger().info(
        "For Test: " + getClassName() + ":" + getTestName()
            + " found and deleted stray datadictionarydir at: "
            + dir.toString() + " : " + result);
  }

  @Override
  protected void setCommonProperties(Properties props, int mcastPort,
      String serverGroups, Properties extraProps) {
    super.setCommonProperties(props, mcastPort, serverGroups, extraProps);
    if (props != null) {
      props.setProperty(TestUtil.TEST_SKIP_DEFAULT_INITIAL_CAPACITY, "true");
    }
  }

  final void checkAndSetId(final AtomicInteger id, Object result)
      throws Throwable {
    if (result instanceof Integer) {
      id.set((Integer)result);
    } else if (result instanceof Throwable) {
      throw (Throwable)result;
    } else {
      fail("unexpected result " + result);
    }
  }

  RegionAttributesCreation getServerTestTableProperties() {
    // Create a set of expected region attributes for the table
    RegionAttributesCreation serverAttrs = new RegionAttributesCreation();
    serverAttrs.setDataPolicy(DataPolicy.PARTITION);
    serverAttrs.setConcurrencyChecksEnabled(false);
    PartitionAttributes<?, ?> pa = new PartitionAttributesFactory<>()
        .setPartitionResolver(new GfxdPartitionByExpressionResolver()).create();
    serverAttrs.setPartitionAttributes(pa);
    serverAttrs.setInitialCapacity(GfxdConstants.DEFAULT_INITIAL_CAPACITY);
    serverAttrs.setAllHasFields(true);
    serverAttrs.setHasScope(false);
    serverAttrs.setHasDiskDirs(false);
    serverAttrs.setHasDiskWriteAttributes(false);

    return serverAttrs;
  }

  RegionAttributesCreation[] checkTestTableProperties(String schemaName)
      throws Exception {
    return checkTestTableProperties(schemaName, false);
  }

  RegionAttributesCreation[] checkTestTableProperties(String schemaName,
      boolean isDataStore) throws Exception {

    RegionAttributesCreation serverAttrs = getServerTestTableProperties();

    if (schemaName == null) {
      schemaName = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
    }
    // Check the table attributes on the servers and the client
    serverVerifyRegionProperties(1, schemaName, "TESTTABLE", serverAttrs);
    serverVerifyRegionProperties(2, schemaName, "TESTTABLE", serverAttrs);

    // Check that the local-max-memory PR attribute is zero on the client
    RegionAttributesCreation clientAttrs = new RegionAttributesCreation(
        serverAttrs, false);
    final PartitionAttributes<?, ?> pa;
    if (isDataStore) {
      pa = new PartitionAttributesFactory<>(clientAttrs
          .getPartitionAttributes()).setLocalMaxMemory(
          PartitionAttributesFactory.LOCAL_MAX_MEMORY_DEFAULT).create();
    } else {
      pa = new PartitionAttributesFactory<>(clientAttrs
          .getPartitionAttributes()).setLocalMaxMemory(0).create();
    }
    clientAttrs.setPartitionAttributes(pa);
    TestUtil.verifyRegionProperties(schemaName, "TESTTABLE", TestUtil
        .regionAttributesToXML(clientAttrs));
    return new RegionAttributesCreation[]{serverAttrs, clientAttrs};
  }

  // Try some metadata calls
  void checkDBMetadata(Connection conn, String... urls) throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();
    String actualUrl = dbmd.getURL();
    // remove any trailing slash
    getLogWriter().info("Got DB " + dbmd.getDatabaseProductName() + ' '
        + dbmd.getDatabaseProductVersion() + " using URL " + actualUrl);
    actualUrl = actualUrl.replaceFirst("/$", "");
    boolean foundMatch = false;
    for (String url : urls) {
      url = url.replaceFirst("/$", "");
      if (url.equals(actualUrl)) {
        foundMatch = true;
        break;
      }
    }
    if (!foundMatch) {
      fail("Expected one of the provided URLs "
          + java.util.Arrays.toString(urls) + " to match " + actualUrl);
    }
    ResultSet rs = dbmd.getCatalogs();
    while (rs.next()) {
      getLogWriter().info("Got DB catalog: " + rs.getString(1));
    }
    rs.close();
    rs = dbmd.getSchemas();
    while (rs.next()) {
      getLogWriter().info("Got DB schema: " + rs.getString(1)
          + " in catalog=" + rs.getString(2));
    }
    rs.close();
    rs = dbmd.getProcedures(null, null, null);
    while (rs.next()) {
      getLogWriter().info("Got Procedure " + rs.getString(3) + " in catalog="
          + rs.getString(1) + ", schema=" + rs.getString(2));
    }
    rs.close();
    // also check for a few flags that are failing over network connection
    assertTrue(dbmd.othersInsertsAreVisible(ResultSet.TYPE_FORWARD_ONLY));
    assertTrue(dbmd.othersDeletesAreVisible(ResultSet.TYPE_FORWARD_ONLY));
  }
}
