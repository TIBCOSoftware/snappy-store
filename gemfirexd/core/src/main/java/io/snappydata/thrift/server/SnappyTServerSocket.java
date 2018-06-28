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

package io.snappydata.thrift.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import io.snappydata.thrift.common.SnappyTSocket;
import io.snappydata.thrift.common.SocketParameters;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side custom TServerSocket replacement allowing to increase
 * input/output buffer sizes and use NIO channels.
 */
public final class SnappyTServerSocket extends TNonblockingServerTransport {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(SnappyTServerSocket.class.getName());

  /**
   * Underlying ServerSocketChannel object.
   */
  private final ServerSocketChannel serverSockChannel;

  /**
   * Socket parameters like buffer sizes, keep-alive settings.
   */
  private final SocketParameters socketParams;

  /** True if client socket on accept has to use SSL (using SSLEngine). */
  private final boolean useSSL;

  /** Whether the client socket is in blocking or non-blocking mode. */
  private final boolean clientBlocking;

  /**
   * Creates a port listening server socket
   */
  public SnappyTServerSocket(InetSocketAddress bindAddress, boolean useSSL,
      boolean blocking, boolean clientBlocking, SocketParameters params)
      throws TTransportException {
    this.useSSL = useSSL;
    this.clientBlocking = clientBlocking;
    this.socketParams = params;
    try {
      // Make server socket
      this.serverSockChannel = ServerSocketChannel.open();
      this.serverSockChannel.configureBlocking(blocking);
      ServerSocket socket = this.serverSockChannel.socket();
      // Prevent 2MSL delay problem on server restarts
      socket.setReuseAddress(true);
      // Bind to listening port
      socket.bind(bindAddress);
    } catch (IOException ioe) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Could not bind to host:port " + bindAddress.toString(), ioe);
    }
  }

  @Override
  public void listen() throws TTransportException {
    // Make sure not to block on accept
    try {
      this.serverSockChannel.socket().setSoTimeout(0);
    } catch (SocketException se) {
      LOGGER.error("Could not set socket timeout to 0.", se);
    }
  }

  @Override
  protected SnappyTSocket acceptImpl() throws TTransportException {
    try {
      SocketChannel srvChannel = this.serverSockChannel.accept();
      return new SnappyTSocket(srvChannel, this.useSSL, this.clientBlocking,
          this.socketParams);
    } catch (IOException ioe) {
      throw new TTransportException(ioe);
    }
  }

  @Override
  public void close() {
    try {
      this.serverSockChannel.socket().close();
    } catch (IOException ioe) {
      LOGGER.warn("Could not close server socket.", ioe);
    }
    try {
      this.serverSockChannel.close();
    } catch (IOException ioe) {
      LOGGER.warn("Could not close server channel.", ioe);
    }
  }

  @Override
  public void interrupt() {
    // The thread-safeness of this is dubious, but Java documentation suggests
    // that it is safe to do this from a different thread context
    close();
  }

  public ServerSocket getServerSocket() {
    return this.serverSockChannel.socket();
  }

  public ServerSocketChannel getServerSocketChannel() {
    return this.serverSockChannel;
  }

  @Override
  public void registerSelector(Selector selector) {
    try {
      // Register the server socket channel, indicating an interest in
      // accepting new connections
      this.serverSockChannel.register(selector, SelectionKey.OP_ACCEPT);
    } catch (ClosedChannelException cce) {
      LOGGER.warn("Channel closed in selector register?", cce);
      throw new GemFireXDRuntimeException(cce);
    }
  }
}
