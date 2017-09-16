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
package com.gemstone.gemfire.internal.cache.wan;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.cache.wan.GatewayReceiver;
import com.gemstone.gemfire.cache.wan.GatewayTransportFilter;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ResourceEvent;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.BridgeServerImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.tier.sockets.AcceptorImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * @author Suranjan Kumar
 * @author Yogesh Mahajan
 * 
 * @since 7.0
 */
public final class GatewayReceiverImpl implements GatewayReceiver {

  private String host;

  private final int startPort;
  
  private final int endPort;
  
  private int port;

  private final int timeBetPings;

  private final int socketBufferSize;

  private final List<GatewayTransportFilter> filters;

  private final String bindAdd;
  
  private CacheServer receiver;

  private final GemFireCacheImpl cache;
  
  private final LogWriterI18n logger ;

  private final String id;
    
  public static final String RECEIVER_GROUP = "__recv__group";

  public GatewayReceiverImpl(Cache cache, String id, int startPort,
      int endPort, int timeBetPings, int buffSize, String bindAdd,
      List<GatewayTransportFilter> filters, String hostnameForSenders) {
    this.cache = (GemFireCacheImpl)cache;
    this.id = id;
    this.logger = this.cache.getLoggerI18n();
    
    /*
     * If user has set hostNameForSenders then it should take precedence over
     * bindAddress. If user hasn't set either hostNameForSenders or bindAddress
     * then getLocalHost().getHostName() should be used.
     */
    if (hostnameForSenders == null || hostnameForSenders.isEmpty()) {
      if (bindAdd == null || bindAdd.isEmpty()) {
        try {
          if (this.logger.warningEnabled()) {
            this.logger
                .warning(LocalizedStrings.GatewayReceiverImpl_USING_LOCAL_HOST);
          }
          this.host = SocketCreator.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
          throw new IllegalStateException(
              LocalizedStrings.GatewayReceiverImpl_COULD_NOT_GET_HOST_NAME
                  .toLocalizedString(),
              e);
        }
      } else {
        this.host = bindAdd;
      }
    } else {
      this.host = hostnameForSenders;
    }

    this.startPort = startPort;
    this.endPort = endPort;
    this.timeBetPings = timeBetPings;
    this.socketBufferSize = buffSize;
    this.bindAdd = bindAdd;
    this.filters = filters;
  }

  public String getId() {
    return this.id;
  }

  public List<GatewayTransportFilter> getGatewayTransportFilters() {
    return this.filters;
  }

  public int getMaximumTimeBetweenPings() {
    return this.timeBetPings;
  }

  public int getPort() {
    return this.port;
  }

  public int getStartPort() {
    return this.startPort;
  }
  
  public int getEndPort() {
    return this.endPort;
  }
  
  public int getSocketBufferSize() {
    return this.socketBufferSize;
  }

  public CacheServer getServer() {
    return receiver;
  }
  
  public void start() throws IOException {
    if (receiver == null) {
      receiver = this.cache.addCacheServer(true);
    }
    if (receiver.isRunning()) {
      return;
    }
    boolean started = false;
    this.port = getPortToStart();
    while (!started && this.port != -1) {
      receiver.setPort(this.port);
      receiver.setSocketBufferSize(socketBufferSize);
      receiver.setMaximumTimeBetweenPings(timeBetPings);
      receiver.setHostnameForClients(host);
      receiver.setBindAddress(bindAdd);
      receiver.setGroups(new String[] { GatewayReceiverImpl.RECEIVER_GROUP });
      ((BridgeServerImpl)receiver).setGatewayTransportFilter(this.filters);
      try {
        receiver.start();
        started = true;
      } catch (BindException be) {
        InetAddress bindHostAddress = AcceptorImpl.getBindAddress(
            AcceptorImpl.calcBindHostName(cache, getBindAddress()));
        if (!AcceptorImpl.treatAsBindException(be, bindHostAddress)) {
          throw be;
        }
        // ignore as this port might have been used by other threads.
        logger.warning(LocalizedStrings.GatewayReceiver_Address_Already_In_Use,
            this.port);
        this.port = getPortToStart();
      } catch (SocketException se) {
        if (se.getMessage().contains("Address already in use")) {
          logger.warning(
              LocalizedStrings.GatewayReceiver_Address_Already_In_Use,
              this.port);
          this.port = getPortToStart();

        } else {
          throw se;
        }

      }
    }
    if (!started) {
      throw new IllegalStateException(
          "No available free port found in the given range.");
    }
    logger.info(LocalizedStrings.GatewayReceiver_STARTED_ON_PORT, this.port);

    InternalDistributedSystem system = this.cache.getDistributedSystem();
    system.handleResourceEvent(ResourceEvent.GATEWAYRECEIVER_START, this);

  }
  
  private int getPortToStart(){
    // choose a random port from the given port range
    int rPort;
    if (this.startPort == this.endPort) {
      rPort = this.startPort;
    } else {
      rPort = AvailablePort.getRandomAvailablePortInRange(this.startPort,
          this.endPort, AvailablePort.SOCKET);
    }
    return rPort;
  }
  
  public void stop() {
    if (receiver != null) {
      receiver.stop();
    }

//    InternalDistributedSystem system = ((GemFireCacheImpl) this.cache)
//        .getDistributedSystem();
//    system.handleResourceEvent(ResourceEvent.GATEWAYRECEIVER_STOP, this);

  }

  public String getHost() {
    return this.host;
  }

  public String getBindAddress() {
    return this.bindAdd;
  }

  public boolean isRunning() {
    if (this.receiver != null) {
      return this.receiver.isRunning();
    }
    return false;
  }
  
  @Override
  public String toString() {
    return new StringBuilder()
      .append("Gateway Receiver")
      .append("@").append(Integer.toHexString(hashCode()))
      .append(" [")
      .append("host='").append(getHost())
      .append("'; port=").append(getPort())
      .append("; bindAddress=").append(getBindAddress())
      .append("; maximumTimeBetweenPings=").append(getMaximumTimeBetweenPings())
      .append("; socketBufferSize=").append(getSocketBufferSize()) 
      .append("; group=").append(Arrays.toString(new String[]{GatewayReceiverImpl.RECEIVER_GROUP}))
      .append("]")
      .toString();
  }
   
}
