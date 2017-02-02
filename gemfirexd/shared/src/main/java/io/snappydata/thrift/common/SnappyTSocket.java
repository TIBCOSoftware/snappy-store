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
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.InputStreamChannel;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.pivotal.gemfirexd.Attribute;
import io.snappydata.thrift.HostAddress;
import org.apache.thrift.transport.TNonblockingTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A custom TSocket allowing to increase input/output buffer sizes and use NIO
 * channels.
 */
public final class SnappyTSocket extends TNonblockingTransport implements
    SocketTimeout {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(SnappyTSocket.class.getName());

  // these are the defaults for the per-socket TCP keepalive parameters
  public static final int DEFAULT_KEEPALIVE_IDLE = 20;
  public static final int DEFAULT_KEEPALIVE_INTVL = 1;
  public static final int DEFAULT_KEEPALIVE_CNT = 10;

  public static final int DEFAULT_BUFFER_SIZE = 32 * 1024;

  /**
   * Wrapped SocketChannel object.
   */
  private final SocketChannel socketChannel;

  /**
   * The channel used to read/write data. This will be {@link #socketChannel}
   * for non-SSL protocol, but will be an {@link SSLSocketChannel} for SSL
   * to allow input/output streams to operate transparently of underlying
   * protocol and blocking vs non-blocking modes.
   */
  private final ByteChannel dataChannel;

  /**
   * Resolved remote host socket address and port.
   */
  private InetSocketAddress socketAddress;

  /**
   * Socket timeout
   */
  private volatile int timeout;

  private int inputBufferSize = SystemProperties.getClientInstance()
      .getInteger(Attribute.SOCKET_INPUT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
  private int outputBufferSize = SystemProperties.getClientInstance()
      .getInteger(Attribute.SOCKET_INPUT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);

  /**
   * Underlying inputStream
   */
  private InputStreamChannel inputStream;

  /**
   * Underlying outputStream
   */
  private OutputStreamChannel outputStream;

  private final boolean framedWrites;

  /**
   * Constructor that takes an already created socket.
   *
   * @param srvChannel Already created socket object from server accept
   * @param params     Socket parameters like buffer sizes, keep-alive settings
   * @param props      the system properties instance to initialize global
   *                   socket options like keepalive and buffer sizes that
   *                   have not been set in params
   * @throws TTransportException if there is an error setting up the streams
   */
  public SnappyTSocket(SocketChannel srvChannel, boolean useSSL,
      boolean blocking, int timeout, SocketParameters params,
      SystemProperties props) throws TTransportException {
    if (!srvChannel.isConnected())
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Socket must already be connected");

    this.socketChannel = srvChannel;
    this.socketAddress = new InetSocketAddress(getSocket().getInetAddress(),
        getSocket().getPort());
    try {
      srvChannel.configureBlocking(blocking);
      setProperties(srvChannel.socket(), timeout, params, props);

      this.dataChannel = initChannel(srvChannel.getRemoteAddress().toString(),
          null, useSSL, params, false);
      this.inputStream = UnsafeHolder.newChannelBufferFramedInputStream(
          this.dataChannel, this.inputBufferSize);
      this.outputStream = UnsafeHolder.newChannelBufferOutputStream(
          this.dataChannel, this.outputBufferSize);
      this.framedWrites = false;
    } catch (IOException ioe) {
      LOGGER.warn("Failed to create or configure socket for client.", ioe);
      close();
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Failed to create or configure socket for client.", ioe);
    }
  }

  /**
   * Creates a new socket that will connect to the given host on the given port.
   *
   * @param host   Remote HostAddress including port
   * @param params Socket parameters like buffer sizes, keep-alive settings
   * @param props  the system properties instance to initialize global
   *               socket options like keepalive and buffer sizes that
   *               have not been set in params
   */
  public SnappyTSocket(HostAddress host, String clientId, boolean useSSL,
      boolean blocking, boolean framedWrites, SocketParameters params,
      SystemProperties props) throws TTransportException {
    this(host.resolveHost(), host.getPort(), clientId, useSSL, blocking,
        framedWrites, params, props, params.getReadTimeout(0));
  }

  /**
   * Creates a new socket that will connect to the given server on given port.
   *
   * @param srvAddress Resolved remote server address
   * @param port       Remote port
   * @param params     Socket parameters like buffer sizes, keep-alive settings
   * @param props      the system properties instance to initialize global
   *                   socket options like keepalive and buffer sizes that
   *                   have not been set in params
   * @param timeout    Socket timeout
   */
  public SnappyTSocket(InetAddress srvAddress, int port, String clientId,
      boolean useSSL, boolean blocking, boolean framedWrites,
      SocketParameters params, SystemProperties props,
      int timeout) throws TTransportException {
    try {
      this.socketChannel = initSocket(blocking);
      this.socketAddress = new InetSocketAddress(srvAddress, port);
      this.framedWrites = framedWrites;

      setProperties(socketChannel.socket(), timeout, params, props);
      this.dataChannel = openChannel(clientId, useSSL, params);
    } catch (IOException ioe) {
      LOGGER.warn("Failed to create or configure socket.", ioe);
      close();
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Failed to create or configure socket.", ioe);
    }
  }

  /**
   * Initializes the socket object
   */
  private static SocketChannel initSocket(boolean blocking)
      throws TTransportException, IOException {
    SocketChannel socketChannel = SocketChannel.open();
    socketChannel.configureBlocking(blocking);
    return socketChannel;
  }

  private ByteChannel initChannel(String id, SelectionKey key, boolean ssl,
      SocketParameters params, boolean forClient)
      throws TTransportException, IOException {
    if (ssl) {
      // setup the SSL engine
      SSLEngine engine = SSLFactory.createEngine(this.socketAddress.getHostName(),
          this.socketAddress.getPort(), params, forClient);
      return SSLSocketChannel.create(id, socketChannel, key, engine, true);
    } else {
      return this.socketChannel;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getSoTimeout() throws SocketException {
    return getSocket().getSoTimeout();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getRawTimeout() {
    return this.timeout;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSoTimeout(int timeout) throws SocketException {
    getSocket().setSoTimeout(timeout);
    this.timeout = timeout;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setTimeout(int timeout, SocketParameters params,
      SystemProperties props) throws SocketException {
    this.timeout = setTimeout(getSocket(), timeout, params, props);
  }

  protected static int setTimeout(Socket socket, int timeout,
      SocketParameters params, SystemProperties props) throws SocketException {
    socket.setSoTimeout(timeout);
    int defaultIdle = props.getInteger(Attribute.KEEPALIVE_IDLE,
        DEFAULT_KEEPALIVE_IDLE);
    int defaultInterval = props.getInteger(Attribute.KEEPALIVE_INTVL,
        DEFAULT_KEEPALIVE_INTVL);
    int defaultCount = props.getInteger(Attribute.KEEPALIVE_CNT,
        DEFAULT_KEEPALIVE_CNT);
    ClientSharedUtils.setKeepAliveOptions(socket, null,
        params.getKeepAliveIdle(defaultIdle),
        params.getKeepAliveInterval(defaultInterval),
        params.getKeepAliveCount(defaultCount));
    return timeout;
  }

  /**
   * Sets the socket properties like timeout, keepalive, buffer sizes.
   *
   * @param timeout Milliseconds timeout
   * @param params  Socket parameters like buffer sizes, keep-alive settings
   * @param props   the system properties instance to initialize global
   *                socket options like keepalive and buffer sizes that
   *                have not been set in params
   */
  protected void setProperties(Socket socket, int timeout,
      SocketParameters params, SystemProperties props)
      throws TTransportException, IOException {
    this.inputBufferSize = params.getInputBufferSize(this.inputBufferSize);
    this.outputBufferSize = params.getOutputBufferSize(this.outputBufferSize);
    socket.setSoLinger(false, 0);
    socket.setTcpNoDelay(true);
    this.timeout = setTimeout(socket, timeout, params, props);
  }

  /**
   * Returns a reference to the underlying socket.
   */
  public final Socket getSocket() {
    return this.socketChannel.socket();
  }

  /**
   * Returns a reference to the underlying SocketChannel (for NIO).
   */
  public final SocketChannel getSocketChannel() {
    return this.socketChannel;
  }

  public final InputStreamChannel getInputStream() {
    return this.inputStream;
  }

  public final OutputStreamChannel getOutputStream() {
    return this.outputStream;
  }

  /**
   * Checks whether the socket is connected.
   */
  @Override
  public boolean isOpen() {
    // this is also invoked by base class constructor so need to check
    // for null even for final member
    final SocketChannel channel = this.socketChannel;
    return channel != null && channel.isConnected();
  }

  /**
   * Connects the socket, creating a new socket object if necessary.
   */
  private ByteChannel openChannel(String clientId, boolean useSSL,
      SocketParameters params) throws TTransportException, IOException {
    if (isOpen()) {
      throw new TTransportException(TTransportException.ALREADY_OPEN,
          "Socket already connected.");
    }

    if (this.socketAddress == null) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Cannot open null host.");
    }
    if (this.socketAddress.getPort() <= 0) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Cannot open without port.");
    }

    final Socket socket = getSocket();
    socket.connect(this.socketAddress, this.timeout);
    if (clientId == null) {
      clientId = socket.getLocalSocketAddress().toString();
    }
    ByteChannel channel = initChannel(clientId, null, useSSL, params, true);
    this.inputStream = UnsafeHolder.newChannelBufferFramedInputStream(
        channel, this.inputBufferSize);
    this.outputStream = this.framedWrites
        ? UnsafeHolder.newChannelBufferFramedOutputStream(channel, outputBufferSize)
        : UnsafeHolder.newChannelBufferOutputStream(channel, outputBufferSize);
    return channel;
  }

  @Override
  public void open() throws TTransportException {
    if (!isOpen()) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Expected the socket to be already connected.");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean startConnect() throws IOException {
    return this.socketChannel.connect(this.socketAddress);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean finishConnect() throws IOException {
    return this.socketChannel.finishConnect();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SelectionKey registerSelector(Selector selector, int interests)
      throws IOException {
    return this.socketChannel.register(selector, interests);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int read(ByteBuffer buffer) throws IOException {
    return this.inputStream.read(buffer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int read(byte[] buf, int off, int len)
      throws TTransportException {
    int bytesRead;
    try {
      bytesRead = this.inputStream.read(buf, off, len);
    } catch (ClosedChannelException cce) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Cannot read from closed channel.");
    } catch (IOException ioe) {
      throw new TTransportException(TTransportException.UNKNOWN, ioe);
    }
    if (bytesRead >= 0) {
      return bytesRead;
    } else {
      throw new TTransportException(TTransportException.END_OF_FILE,
          "Channel closed.");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final int write(ByteBuffer buffer) throws IOException {
    final OutputStreamChannel outStream = this.outputStream;
    final int numWritten = outStream.write(buffer);
    if (buffer.position() >= buffer.limit()) {
      outStream.flush();
    }
    return numWritten;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void write(byte[] buf, int off, int len)
      throws TTransportException {
    try {
      this.outputStream.write(buf, off, len);
    } catch (ClosedChannelException cce) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Cannot write to closed channel.");
    } catch (IOException ioe) {
      throw new TTransportException(TTransportException.UNKNOWN, ioe);
    }
  }

  /**
   * Flushes the underlying output stream if not null.
   */
  public void flush() throws TTransportException {
    try {
      this.outputStream.flush();
    } catch (ClosedChannelException cce) {
      throw new TTransportException(TTransportException.NOT_OPEN,
          "Cannot write to closed channel.");
    } catch (IOException ioe) {
      throw new TTransportException(TTransportException.UNKNOWN, ioe);
    }
  }

  /**
   * Closes the socket.
   */
  @Override
  public void close() {
    final ByteChannel channel = this.dataChannel != null
        ? this.dataChannel : this.socketChannel;
    if (channel == null || !channel.isOpen()) {
      return;
    }

    // Close the underlying streams
    final InputStreamChannel inStream = this.inputStream;
    final OutputStreamChannel outStream = this.outputStream;
    if (inStream != null) {
      try {
        inStream.close();
      } catch (IOException ioe) {
        // ignore
      }
    }
    if (outStream != null) {
      try {
        outStream.close();
      } catch (IOException ioe) {
        // ignore
      }
    }
    try {
      channel.close();
    } catch (IOException ioe) {
      // ignore
    }
  }
}
