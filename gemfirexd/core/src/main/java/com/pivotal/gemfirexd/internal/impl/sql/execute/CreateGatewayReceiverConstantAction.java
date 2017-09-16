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
package com.pivotal.gemfirexd.internal.impl.sql.execute;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.gemstone.gemfire.cache.wan.GatewayReceiver;
import com.gemstone.gemfire.cache.wan.GatewayReceiverFactory;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.tier.sockets.AcceptorImpl;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.operations.ReceiverCreateOperation;
import com.pivotal.gemfirexd.internal.engine.ddl.ServerGroupsTableAttribute;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.GfxdGatewayReceiverDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

public class CreateGatewayReceiverConstantAction extends DDLConstantAction {

  final private String id;

  final private ServerGroupsTableAttribute serverGroups;

  final private int startPort;

  final private int endPort;

  final private String bindAddress;

  final private int maxTimeBetPings;

  final private int socketBufferSize;

  private String hostNameForSenders;
  


  public static final String REGION_PREFIX_FOR_CONFLATION =
      "__GFXD_INTERNAL_GATEWAYRECEIVER_";
  
  // taken from 
  // http://www.java2s.com/Code/Java/Network-Protocol/DetermineifthegivenstringisavalidIPv4orIPv6address.htm
  private static final String ipv4Pattern = "(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])";
  private static Pattern VALID_IPV4_PATTERN = null;
  static {
    try {
      VALID_IPV4_PATTERN = Pattern.compile(ipv4Pattern, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
    }
  }

  CreateGatewayReceiverConstantAction(String id,
      ServerGroupsTableAttribute serverGroups, int startPort, int endPort,
      String bindAddress, int maxTimeBetPings, int socketBufferSize, String hostNameForSenders) throws StandardException {
    this.id = id;
    this.serverGroups = serverGroups;
    this.startPort = startPort;
    this.endPort = endPort;
    this.bindAddress = bindAddress;
    this.maxTimeBetPings = maxTimeBetPings;
    this.socketBufferSize = socketBufferSize;
    this.hostNameForSenders = determineHostNameForSenders(hostNameForSenders);
  }

  private String determineHostNameForSenders(String hostNameForSenders)
      throws StandardException {
    // 1. If the value is not defined then just set it to null
    // GFE layer will handle this condition
    if (hostNameForSenders == null || hostNameForSenders.isEmpty()) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "HostNameForSender is Empty");
      return null;
    }
    // 2. hostNameForSenders can be IP Address
    Matcher m1 = VALID_IPV4_PATTERN.matcher(hostNameForSenders);
    if (m1.matches()) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "HostNameForSender is valid IPv4 address");
      return hostNameForSenders;
    }

    // 3. input value can be localhost
    if (hostNameForSenders.equalsIgnoreCase("localhost")) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "HostNameForSender is localhost");
      return "localhost";
    }

    // 4. input value is custom env property
    // for example custom-NIC1 or -custom-NIC1
    // where NIC1 is the custom token expected to pass in
    // hostNameForSenders

    java.util.Properties udp = Misc.getDistributedSystem().getConfig()
        .getUserDefinedProps();
    if (udp == null) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
          "User defined properties missing ", "CREATE GATEWAYRECEIVER");
    }

    String value = udp
        .getProperty(DistributionConfig.GFXD_USERDEFINED_PREFIX_NAME
            + hostNameForSenders);
    if (value == null) {
      throw StandardException.newException(
          SQLState.LANG_INVALID_FUNCTION_ARGUMENT, "Env property "
              + hostNameForSenders + " not defined for HOSTNAMEFORSENDERS",
          "CREATE GATEWAYRECEIVER");
    }
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
        "HostNameForSender is custom property = " + value);
    value = value.replaceAll("^\"|\"$", "");
    return value;
  }
  
  @Override
  public String toString() {
    return "CREATE GATEWAYRECEIVER " + id;
  }

  // Override the getSchemaName/getObjectName to enable
  // DDL conflation of CREATE and DROP GATEWAYRECEIVER statements.
  @Override
  public final String getSchemaName() {
    // Gatewayreceivers have no schema, so return 'SYS'
    return SchemaDescriptor.STD_SYSTEM_SCHEMA_NAME;
  }

  @Override
  public final String getTableName() {
    return REGION_PREFIX_FOR_CONFLATION + id;
  }

  @Override
  public void executeConstantAction(Activation activation)
      throws StandardException {
    // If this node is not a data store node, nothing to do here
    if (!ServerGroupUtils.isDataStore()) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "Skipping create gateway receiver for " + id + " on JVM of kind "
              + GemFireXDUtils.getMyVMKind());
      return;
    }
    // If this node should create the sender, do so
    Set<DistributedMember> members = null;
    if (serverGroups != null) {
      members = GemFireXDUtils.getGfxdAdvisor().adviseOperationNodes(
          serverGroups.getServerGroupSet());
    }
    else {
      members = GemFireXDUtils.getGfxdAdvisor().adviseOperationNodes(null);
    }
    GemFireCacheImpl cache = Misc.getGemFireCache();
    // Validate the start and end ports for any invalid scenarios
    if (startPort > endPort) {
      // This is also handled in the Gemfire code, but let's throw our own
      // exception
      // earlier on, before the Receiver gets created
      throw StandardException.newException(
          SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
          "Gateway start port is greater than end port",
          "CREATE GATEWAYRECEIVER");
    }
    if ((startPort < 0) || (endPort < 0) || (startPort > 65535)
        || (endPort > 65535)) {
      // Valid port ranges are 0-65535
      throw StandardException.newException(
          SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
          "Start/end port out of range (0-65535)", "CREATE GATEWAYRECEIVER");
    }
    if (cache.getGatewayReceiver(id) != null) {
      // Throw 'object-already-exists' error here
      throw StandardException.newException(SQLState.LANG_OBJECT_ALREADY_EXISTS,
          "GATEWAYRECEIVER", id);
    }
    // Set DDL writing flag
    LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
    DataDictionary dd = lcc.getDataDictionary();
    GemFireTransaction tc = (GemFireTransaction)lcc.getTransactionExecute();
    dd.startWriting(lcc);

    final DistributedMember myId = cache.getMyId();
    boolean self = members.remove(myId);
    if (self) {
      GatewayReceiverFactory factory = cache.createGatewayReceiverFactory();
      factory.setBindAddress(bindAddress);
      factory.setStartPort(startPort);
      factory.setEndPort(endPort);
      factory.setMaximumTimeBetweenPings(maxTimeBetPings);
      factory.setSocketBufferSize(socketBufferSize);
      factory.setHostnameForSenders(hostNameForSenders);
      GatewayReceiver rcvr = factory.create(id);
      ReceiverCreateOperation startOp = new ReceiverCreateOperation(
          rcvr, serverGroups);
      InetAddress bindHostAddress = null;
      try {
        bindHostAddress = AcceptorImpl.getBindAddress(
            AcceptorImpl.calcBindHostName(cache, bindAddress));
        Misc.getMemStoreBooting().addPendingOperation(startOp, tc);
      } catch (IOException ioe) {
        // If host could not be found or not local, throw more user-friendly error
        if (ioe instanceof UnknownHostException || bindHostAddress == null ||
            !SocketCreator.isLocalHost(bindHostAddress)) {
          // Stop the GATEWAYRECEIVER cache object before we throw error
          // Or else it is in the cache but not in the catalog!
          GatewayReceiver server = cache.getGatewayReceiver(id);
          if (server != null) {
            try {
              cache.removeGatewayReceiver(server);
              server.stop();
            } catch (Exception ex) {
              throw StandardException.newException(
                  SQLState.UNEXPECTED_EXCEPTION_FOR_ASYNC_LISTENER, ex, id,
                  ex.toString());
            }
          }
          throw StandardException.newException(
              SQLState.LANG_INVALID_FUNCTION_ARGUMENT, "BINDADDRESS "
                  + bindAddress + " is not a reachable or bindable host name/address",
              "CREATE GATEWAYRECEIVER");
        }
        throw StandardException.newException(
            SQLState.DATA_UNEXPECTED_EXCEPTION, ioe);
      }
    }
    else {
      // For those nodes which are not part of the server group on which
      // the receiver is created, use '0' for runningPort, but still add
      // a row in GATEWAYRECEIVERS so the DROP can detect DROP of an
      // object that was never CREATEd.
      int runningPort = 0; // We don't know the running port
      UUID uuid = dd.getUUIDFactory().recreateUUID(String.valueOf(runningPort));
      String servers = SharedUtils.toCSV(serverGroups.getServerGroupSet());
      GfxdGatewayReceiverDescriptor ghd = new GfxdGatewayReceiverDescriptor(dd,
          uuid, id, servers, startPort, endPort, runningPort, socketBufferSize,
          maxTimeBetPings, bindAddress, hostNameForSenders);
      // Do not allow duplicates in the table
      dd.addDescriptor(ghd, null, DataDictionary.GATEWAYRECEIVERS_CATALOG_NUM,
          false, tc);
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_CONGLOM,
          "CreateGatewayReceiverNode:: inserted GatewayReceiver configuration for "
              + id + " in SYS table");
    }
  }
}
