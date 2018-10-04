/*
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
 * Adapted from org.apache.directory.seserver.EmbeddedADSVer157 example from
 * Apache Directory Server distribution having the license below.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.snappydata.ldap;

import java.io.File;
import java.util.Collections;
import java.util.Properties;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.directory.server.constants.ServerDNConstants;
import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.core.DirectoryService;
import org.apache.directory.server.core.partition.Partition;
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmPartition;
import org.apache.directory.server.core.partition.ldif.LdifPartition;
import org.apache.directory.server.core.schema.SchemaPartition;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.protocol.shared.store.LdifFileLoader;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.directory.shared.ldap.entry.EntryAttribute;
import org.apache.directory.shared.ldap.entry.Modification;
import org.apache.directory.shared.ldap.entry.ModificationOperation;
import org.apache.directory.shared.ldap.entry.ServerEntry;
import org.apache.directory.shared.ldap.entry.client.ClientModification;
import org.apache.directory.shared.ldap.entry.client.DefaultClientAttribute;
import org.apache.directory.shared.ldap.name.DN;
import org.apache.directory.shared.ldap.schema.ldif.extractor.impl.DefaultSchemaLdifExtractor;
import org.apache.directory.shared.ldap.schema.loader.ldif.LdifSchemaLoader;
import org.apache.directory.shared.ldap.schema.manager.impl.DefaultSchemaManager;

/**
 * Utility methods to start/stop and query an embedded LDAP server (Apache
 * Directory service).
 */
public class LdapTestServer {

  /** Singleton instance */
  private static volatile LdapTestServer instance;

  private static final Object instLock = new Object();

  public static LdapTestServer getInstance(String ldifFilePath)
      throws Exception {
    return getInstance("./apacheds", ldifFilePath);
  }

  public static LdapTestServer getInstance(String workingDir,
      String ldifFilePath) throws Exception {
    LdapTestServer inst = instance;
    if (inst != null) {
      return inst;
    } else {
      synchronized (instLock) {
        inst = instance;
        if (inst == null) {
          inst = instance = new LdapTestServer(workingDir, ldifFilePath);
        }
        return inst;
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("Require LDIF file. Usage: LdapTestServer <ldif-path>");
      System.exit(1);
    }
    LdapTestServer server = LdapTestServer.getInstance(args[0]);
    if (!server.isServerStarted()) {
      int port = new java.util.Random().nextInt(30000) + 10000;
      server.startServer("0.0.0.0", port);
    }
    int serverPort = server.getServerPort();
    Properties bootProps = new Properties();
    bootProps.setProperty("auth-provider", "LDAP");
    bootProps.setProperty("gemfirexd.auth-ldap-search-base",
        "ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
    bootProps.setProperty("gemfirexd.group-ldap-search-base",
        "ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
    bootProps.setProperty("gemfirexd.auth-ldap-server",
        "ldap://localhost:" + serverPort);
    bootProps.setProperty("security-log-level", "finest");
    bootProps.setProperty("Dgemfirexd.debug.true",
        "TraceAuthentication,TraceFabricServiceBoot");
    bootProps.setProperty("user", "gemfire10");
    bootProps.setProperty("password", "gemfire10");
    StringBuilder sb = new StringBuilder();
    for (java.util.Map.Entry<?, ?> e : bootProps.entrySet()) {
      sb.append(" -").append(e.getKey()).append('=').append(e.getValue());
    }
    System.out.println("Boot properties =" + sb);
  }

  /** The directory service */
  private DirectoryService service;

  /** The LDAP server */
  private LdapServer server;

  /**
   * The address to which LDAP TCP server will bind. Default localhost if not
   * set.
   */
  private String serverAddress;

  /** The port to use for LDAP TCP server */
  private int serverPort = 10389;

  /** The file to be loaded containing LDAP schema */
  private final String ldifLoadFile;

  /**
   * Add a new partition to the server
   *
   * @param partitionId
   *          The partition Id
   * @param partitionDn
   *          The partition DN
   * @return The newly added partition
   *
   * @throws Exception
   *           If the partition can't be added
   */
  private Partition addPartition(String partitionId, String partitionDn)
      throws Exception {
    // Create a new partition named 'foo'.
    JdbmPartition partition = new JdbmPartition();
    partition.setId(partitionId);
    partition.setPartitionDir(
        new File(service.getWorkingDirectory(), partitionId));
    partition.setSuffix(partitionDn);
    service.addPartition(partition);

    return partition;
  }

  /**
   * initialize schema manager and add the schema partition to directory service
   *
   * @throws Exception
   *           if the schema LDIF files are not found on the classpath
   */
  private void initSchemaPartition() throws Exception {
    SchemaPartition schemaPartition = service.getSchemaService()
        .getSchemaPartition();

    // Init the LdifPartition
    LdifPartition ldifPartition = new LdifPartition();
    String workingDirectory = service.getWorkingDirectory().getPath();
    ldifPartition.setWorkingDirectory(workingDirectory + "/schema");

    // Extract the schema on disk (a brand new one) and load the registries
    File schemaRepository = new File(workingDirectory, "schema");
    DefaultSchemaLdifExtractor extractor = new DefaultSchemaLdifExtractor(
        new File(workingDirectory));
    extractor.extractOrCopy(true);

    schemaPartition.setWrappedPartition(ldifPartition);

    LdifSchemaLoader loader = new LdifSchemaLoader(schemaRepository);
    DefaultSchemaManager schemaManager = new DefaultSchemaManager(loader);
    service.setSchemaManager(schemaManager);

    // We have to load the schema now, otherwise we won't be able
    // to initialize the Partitions, as we won't be able to parse
    // and normalize their suffix DN
    schemaManager.loadAllEnabled();

    schemaPartition.setSchemaManager(schemaManager);

    List<Throwable> errors = schemaManager.getErrors();
    if (errors.size() != 0) {
      throw new Exception("Schema load failed : " + errors);
    }
  }

  /**
   * Initialize the server. It creates the partition, injects the context
   * entries for the created partitions, and loads an LDIF file (
   * {@link #ldifLoadFile}) for initial entries.
   *
   * @param workDir
   *          the directory to be used for storing the data
   * @throws Exception
   *           if there were some problems while initializing the system
   */
  private void initDirectoryService(File workDir) throws Exception {
    // Initialize the LDAP service
    service = new DefaultDirectoryService();
    service.setWorkingDirectory(workDir);

    // first load the schema
    initSchemaPartition();

    // then the system partition
    // this is a MANDATORY partition
    Partition systemPartition = addPartition("system",
        ServerDNConstants.SYSTEM_DN);
    service.setSystemPartition(systemPartition);

    // create the partition for testing
    Partition testingPartition = addPartition("ldapTesting",
        "ou=ldapTesting,dc=pune,dc=gemstone,dc=com");

    // Disable the shutdown hook
    service.setShutdownHookEnabled(false);
    // Disable the ChangeLog system
    service.getChangeLog().setEnabled(false);
    service.setDenormalizeOpAttrsEnabled(true);

    // And start the service
    service.startup();

    // inject the entry for testing
    if (!service.getAdminSession().exists(testingPartition.getSuffixDn())) {
      DN dnTesting = new DN("ou=ldapTesting,dc=pune,dc=gemstone,dc=com");
      ServerEntry entryTesting = service.newEntry(dnTesting);
      entryTesting.add("objectClass", "top", "domain", "extensibleObject");
      entryTesting.add("dc", "pune");
      service.getAdminSession().add(entryTesting);
    }

    // load schema from LDIF
    if (ldifLoadFile != null) {
      LdifFileLoader ldifLoader = new LdifFileLoader(
          service.getAdminSession(), ldifLoadFile);
      int numLoaded = ldifLoader.execute();
      if (numLoaded <= 0) {
        throw new Exception(
            "Failed to load any entries from " + ldifLoadFile);
      } else {
        System.out.println(
            "LDAP loaded " + numLoaded + " entries from " + ldifLoadFile);
      }
    }
  }

  /**
   * Creates a new instance of LdapTestServer initializing the directory
   * service.
   */
  private LdapTestServer(String workingDir, String ldifFilePath)
      throws Exception {
    File workingDirF = new File(workingDir);
    FileUtils.deleteQuietly(workingDirF);
    workingDirF.mkdirs();

    this.ldifLoadFile = ldifFilePath;
    initDirectoryService(workingDirF);
  }

  /** starts the LdapServer */
  public void startServer() throws Exception {
    server = new LdapServer();
    TcpTransport transport;
    if (serverAddress != null) {
      transport = new TcpTransport(serverAddress, serverPort);
    } else {
      transport = new TcpTransport(serverPort);
    }
    server.setTransports(transport);
    server.setDirectoryService(service);

    server.start();
  }

  /** starts the LdapServer */
  public void startServer(int port) throws Exception {
    this.serverPort = port;
    startServer();
  }

  /** starts the LdapServer */
  public void startServer(String address, int port) throws Exception {
    this.serverAddress = address;
    this.serverPort = port;
    startServer();
  }

  public boolean isServerStarted() {
    final LdapServer server = this.server;
    return server != null && server.isStarted();
  }

  public String getServerAddress() {
    return this.serverAddress;
  }

  public int getServerPort() {
    return this.serverPort;
  }

  public void addAttribute(String dn, String attrName, String attrValue)
      throws Exception {
    EntryAttribute attr = new DefaultClientAttribute(attrName, attrValue);
    Modification addValue = new ClientModification(
        ModificationOperation.ADD_ATTRIBUTE, attr);
    service.getAdminSession().modify(new DN(dn),
        Collections.singletonList(addValue));
  }

  public void removeAttribute(String dn, String attrName, String attrValue)
      throws Exception {
    EntryAttribute attr = new DefaultClientAttribute(attrName, attrValue);
    Modification removeValue = new ClientModification(
        ModificationOperation.REMOVE_ATTRIBUTE, attr);
    service.getAdminSession().modify(new DN(dn),
        Collections.singletonList(removeValue));
  }

  /** stops the LdapServer */
  public void stopService() throws Exception {
    final LdapServer server = this.server;
    if (server != null && server.isStarted()) {
      server.stop();
    }
    try {
      service.shutdown();
    } finally {
      FileUtils.deleteQuietly(service.getWorkingDirectory());
      // null the singleton instance
      instance = null;
    }
  }
}
