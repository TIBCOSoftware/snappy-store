/*
 * Adapted from Apache Kafka SslTransportLayer having license below.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

package io.snappydata.thrift.common;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import com.gemstone.gemfire.internal.shared.BufferAllocator;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.HeapBufferAllocator;
import com.gemstone.gemfire.internal.shared.unsafe.DirectBufferAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * A read-write SocketChannel implementation for SSL communication.
 */
public final class SSLSocketChannel
    implements ByteChannel, ScatteringByteChannel, GatheringByteChannel {

  private static final Logger log = LoggerFactory.getLogger(
      SSLSocketChannel.class);

  private final String channelId;
  private final SSLEngine sslEngine;
  private final SelectionKey key;
  private final SocketChannel socketChannel;
  private final boolean useDirectBuffers;
  private final boolean enableRenegotiation;
  private HandshakeStatus handshakeStatus;
  private SSLEngineResult handshakeResult;
  private boolean handshakeComplete = false;
  private boolean closing = false;
  private ByteBuffer netReadBuffer;
  private ByteBuffer netWriteBuffer;
  private ByteBuffer appReadBuffer;
  private ByteBuffer emptyBuf = ByteBuffer.allocate(0);
  private final boolean isTraceEnabled = log.isTraceEnabled();

  private static final String BUFFER_OWNER = "SSLCHANNEL";

  public static SSLSocketChannel create(String channelId,
      SocketChannel channel, SelectionKey key, SSLEngine sslEngine,
      boolean useDirectBuffers) throws IOException {
    if (key == null) {
      key = new DummyReadWriteKey(channel);
    }
    // Disable renegotiation by default until we have fixed the known issues
    // with the existing implementation
    SSLSocketChannel transportLayer = new SSLSocketChannel(channelId, key,
        sslEngine, useDirectBuffers, false);
    transportLayer.startHandshake();
    return transportLayer;
  }

  SSLSocketChannel(String channelId, SelectionKey key, SSLEngine sslEngine,
      boolean useDirectBuffers, boolean enableRenegotiation) {
    this.channelId = channelId;
    this.key = key;
    this.socketChannel = (SocketChannel)key.channel();
    this.sslEngine = sslEngine;
    this.useDirectBuffers = useDirectBuffers;
    this.enableRenegotiation = enableRenegotiation;
  }

  private static final class DummyReadWriteKey extends SelectionKey {
    private final SocketChannel channel;
    private int ops;

    private DummyReadWriteKey(SocketChannel channel) {
      this.channel = channel;
      this.ops = OP_READ | OP_WRITE;
    }

    public SelectableChannel channel() {
      return this.channel;
    }

    public Selector selector() {
      throw new IllegalArgumentException("not expected to be invoked");
    }

    public boolean isValid() {
      return true;
    }

    public void cancel() {
    }

    public int interestOps() {
      return this.ops;
    }

    public SelectionKey interestOps(int ops) {
      this.ops = ops;
      return this;
    }

    public int readyOps() {
      return this.ops;
    }
  }

  /**
   * starts sslEngine handshake process
   */
  protected void startHandshake() throws IOException {
    final BufferAllocator allocator = this.useDirectBuffers
        ? DirectBufferAllocator.instance() : HeapBufferAllocator.instance();
    this.netReadBuffer = allocator.allocateWithFallback(netReadBufferSize(),
        BUFFER_OWNER);
    this.netWriteBuffer = allocator.allocateWithFallback(netWriteBufferSize(),
        BUFFER_OWNER);
    this.appReadBuffer = allocator.allocateWithFallback(applicationBufferSize(),
        BUFFER_OWNER);

    // clear & set netRead & netWrite buffers
    netWriteBuffer.position(0);
    netWriteBuffer.limit(0);
    netReadBuffer.position(0);
    netReadBuffer.limit(0);
    handshakeComplete = false;
    closing = false;
    // initiate handshake
    sslEngine.beginHandshake();
    handshakeStatus = sslEngine.getHandshakeStatus();
  }

  public boolean finishConnect() throws IOException {
    boolean connected = socketChannel.finishConnect();
    if (connected) {
      key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT |
          SelectionKey.OP_READ);
    }
    return connected;
  }

  @Override
  public boolean isOpen() {
    return socketChannel.isOpen();
  }

  public boolean isConnected() {
    return socketChannel.isConnected();
  }

  /**
   * Sends a SSL close message and closes socketChannel.
   */
  @Override
  public void close() throws IOException {
    if (closing) return;
    closing = true;
    sslEngine.closeOutbound();
    try {
      if (isConnected()) {
        if (!flush(netWriteBuffer)) {
          throw new IOException("Remaining data in the network buffer, " +
              "can't send SSL close message.");
        }
        // prep the buffer for the close message
        netWriteBuffer.clear();
        // perform the close, since we called sslEngine.closeOutbound
        SSLEngineResult wrapResult = sslEngine.wrap(emptyBuf, netWriteBuffer);
        // we should be in a close state
        if (wrapResult.getStatus() != SSLEngineResult.Status.CLOSED) {
          throw new IOException("Unexpected status returned by " +
              "SSLEngine.wrap, expected CLOSED, received " +
              wrapResult.getStatus() + ". Will not send close message to peer.");
        }
        netWriteBuffer.flip();
        flush(netWriteBuffer);
      }
      if (this.useDirectBuffers) {
        ByteBuffer buffer = this.netReadBuffer;
        this.netReadBuffer = null;
        final DirectBufferAllocator allocator = DirectBufferAllocator.instance();
        allocator.release(buffer);
        buffer = this.netWriteBuffer;
        this.netWriteBuffer = null;
        allocator.release(buffer);
        buffer = this.appReadBuffer;
        this.appReadBuffer = null;
        allocator.release(buffer);
      }
    } catch (IOException ie) {
      log.warn("Failed to send SSL Close message.", ie);
    } finally {
      try {
        socketChannel.socket().close();
        socketChannel.close();
      } finally {
        key.attach(null);
        key.cancel();
      }
    }
  }

  /**
   * returns true if there are any pending contents in netWriteBuffer
   */
  public boolean hasPendingWrites() {
    return netWriteBuffer.hasRemaining();
  }

  /**
   * Flushes the buffer to the network, non blocking
   *
   * @param buf ByteBuffer
   * @return boolean true if the buffer has been emptied out, false otherwise
   */
  private boolean flush(ByteBuffer buf) throws IOException {
    int remaining = buf.remaining();
    if (remaining > 0) {
      int written = socketChannel.write(buf);
      return written >= remaining;
    }
    return true;
  }

  /**
   * Performs SSL handshake, non blocking.
   * Before application data can be sent, client & server must perform
   * SSL handshake.
   * During the handshake SSLEngine generates encrypted data that will be
   * transported over socketChannel.
   * Each SSLEngine operation generates SSLEngineResult, of which
   * SSLEngineResult.handshakeStatus field is used to
   * determine what operation needs to occur to move handshake along.
   * A typical handshake might look like this.
   * +-------------+----------------------------------+-------------+
   * |  client     |  SSL/TLS message                 | HSStatus    |
   * +-------------+----------------------------------+-------------+
   * | wrap()      | ClientHello                      | NEED_UNWRAP |
   * | unwrap()    | ServerHello/Cert/ServerHelloDone | NEED_WRAP   |
   * | wrap()      | ClientKeyExchange                | NEED_WRAP   |
   * | wrap()      | ChangeCipherSpec                 | NEED_WRAP   |
   * | wrap()      | Finished                         | NEED_UNWRAP |
   * | unwrap()    | ChangeCipherSpec                 | NEED_UNWRAP |
   * | unwrap()    | Finished                         | FINISHED    |
   * +-------------+----------------------------------+-------------+
   */
  public void handshake() throws IOException {
    boolean read = key.isReadable();
    boolean write = key.isWritable();
    handshakeComplete = false;
    handshakeStatus = sslEngine.getHandshakeStatus();
    if (!flush(netWriteBuffer)) {
      key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
      return;
    }
    try {
      switch (handshakeStatus) {
        case NEED_TASK:
          if (isTraceEnabled) {
            log.trace("SSLHandshake NEED_TASK channelId {}, appReadBuffer " +
                    "pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
                channelId, appReadBuffer.position(), netReadBuffer.position(),
                netWriteBuffer.position());
          }
          handshakeStatus = runDelegatedTasks();
          break;
        case NEED_WRAP:
          if (isTraceEnabled) {
            log.trace("SSLHandshake NEED_WRAP channelId {}, appReadBuffer " +
                    "pos {}, netReadBuffer pos {}, netWriteBuffer pos {}",
                channelId, appReadBuffer.position(), netReadBuffer.position(),
                netWriteBuffer.position());
          }
          handshakeResult = handshakeWrap(write);
          if (handshakeResult.getStatus() == Status.BUFFER_OVERFLOW) {
            int currentNetWriteBufferSize = netWriteBufferSize();
            netWriteBuffer.compact();
            netWriteBuffer = ClientSharedUtils.ensureCapacity(netWriteBuffer,
                currentNetWriteBufferSize, useDirectBuffers, BUFFER_OWNER);
            netWriteBuffer.flip();
            if (netWriteBuffer.limit() >= currentNetWriteBufferSize) {
              throw new IllegalStateException(
                  "Buffer overflow when available data size (" +
                      netWriteBuffer.limit() + ") >= network buffer size (" +
                      currentNetWriteBufferSize + ")");
            }
          } else if (handshakeResult.getStatus() == Status.BUFFER_UNDERFLOW) {
            throw new IllegalStateException(
                "Should not have received BUFFER_UNDERFLOW during handshake WRAP.");
          } else if (handshakeResult.getStatus() == Status.CLOSED) {
            throw new EOFException();
          }
          if (isTraceEnabled) {
            log.trace("SSLHandshake NEED_WRAP channelId {}, " +
                    "handshakeResult {}, appReadBuffer pos {}, " +
                    "netReadBuffer pos {}, netWriteBuffer pos {}",
                channelId, handshakeResult, appReadBuffer.position(),
                netReadBuffer.position(), netWriteBuffer.position());
          }
          // if handshake status is not NEED_UNWRAP or unable to flush
          // netWriteBuffer contents we will break here otherwise we can do
          // need_unwrap in the same call.
          if (handshakeStatus != HandshakeStatus.NEED_UNWRAP
              || !flush(netWriteBuffer)) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            break;
          }
        case NEED_UNWRAP:
          if (isTraceEnabled) {
            log.trace("SSLHandshake NEED_UNWRAP channelId {}, " +
                    "appReadBuffer pos {}, netReadBuffer pos {}, " +
                    "netWriteBuffer pos {}", channelId, appReadBuffer.position(),
                netReadBuffer.position(), netWriteBuffer.position());
          }
          do {
            handshakeResult = handshakeUnwrap(read);
            if (handshakeResult.getStatus() == Status.BUFFER_OVERFLOW) {
              int currentAppBufferSize = applicationBufferSize();
              appReadBuffer = ClientSharedUtils.ensureCapacity(appReadBuffer,
                  currentAppBufferSize, useDirectBuffers, BUFFER_OWNER);
              if (appReadBuffer.position() > currentAppBufferSize) {
                throw new IllegalStateException(
                    "Buffer underflow when available data size (" +
                        appReadBuffer.position() + ") > packet buffer size (" +
                        currentAppBufferSize + ")");
              }
            }
          } while (handshakeResult.getStatus() == Status.BUFFER_OVERFLOW);
          if (handshakeResult.getStatus() == Status.BUFFER_UNDERFLOW) {
            int currentNetReadBufferSize = netReadBufferSize();
            netReadBuffer = ClientSharedUtils.ensureCapacity(netReadBuffer,
                currentNetReadBufferSize, useDirectBuffers, BUFFER_OWNER);
            if (netReadBuffer.position() >= currentNetReadBufferSize) {
              throw new IllegalStateException(
                  "Buffer underflow when there is available data");
            }
          } else if (handshakeResult.getStatus() == Status.CLOSED) {
            throw new EOFException(
                "SSL handshake status CLOSED during handshake UNWRAP");
          }
          if (isTraceEnabled) {
            log.trace("SSLHandshake NEED_UNWRAP channelId {}, " +
                    "handshakeResult {}, appReadBuffer pos {}, " +
                    "netReadBuffer pos {}, netWriteBuffer pos {}",
                channelId, handshakeResult, appReadBuffer.position(),
                netReadBuffer.position(), netWriteBuffer.position());
          }
          // If handshakeStatus completed than fall-through to finished status.
          // after handshake is finished there is no data left to read/write
          // in socketChannel. So the selector won't invoke this channel
          // if we don't go through the handshakeFinished here.
          if (handshakeStatus != HandshakeStatus.FINISHED) {
            if (handshakeStatus == HandshakeStatus.NEED_WRAP) {
              key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            } else if (handshakeStatus == HandshakeStatus.NEED_UNWRAP) {
              key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
            break;
          }
        case FINISHED:
          handshakeFinished();
          break;
        case NOT_HANDSHAKING:
          handshakeFinished();
          break;
        default:
          throw new IllegalStateException(
              String.format("Unexpected status [%s]", handshakeStatus));
      }
    } catch (SSLException e) {
      handshakeFailure();
      throw e;
    }
  }

  private void renegotiate() throws IOException {
    if (!enableRenegotiation) {
      throw new SSLHandshakeException("Renegotiation is not supported");
    }
    handshake();
  }

  /**
   * Executes the SSLEngine tasks needed.
   *
   * @return HandshakeStatus
   */
  private HandshakeStatus runDelegatedTasks() {
    while (true) {
      Runnable task = delegatedTask();
      if (task == null) {
        break;
      }
      task.run();
    }
    return sslEngine.getHandshakeStatus();
  }

  /**
   * Checks if the handshake status is finished
   * Sets the interestOps for the selectionKey.
   */
  private void handshakeFinished() throws IOException {
    // SSLEngine.getHandshakeStatus is transient and it doesn't record FINISHED
    // status properly. It can move from FINISHED status to NOT_HANDSHAKING
    // after the handshake is completed. Hence we also need to check
    // handshakeResult.getHandshakeStatus() if the handshake finished or not.
    if (handshakeResult.getHandshakeStatus() == HandshakeStatus.FINISHED) {
      // we are complete if we have delivered the last package
      handshakeComplete = !netWriteBuffer.hasRemaining();
      // remove OP_WRITE if we are complete,
      // otherwise we still have data to write
      if (!handshakeComplete) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
      } else {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
      }
      if (isTraceEnabled) {
        log.trace("SSLHandshake FINISHED channelId {}, appReadBuffer pos {}, " +
                "netReadBuffer pos {}, netWriteBuffer pos {} ",
            channelId, appReadBuffer.position(), netReadBuffer.position(),
            netWriteBuffer.position());
      }
    } else {
      throw new IOException("NOT_HANDSHAKING during handshake");
    }
  }

  /**
   * Performs the WRAP function.
   */
  private SSLEngineResult handshakeWrap(boolean doWrite) throws IOException {
    if (isTraceEnabled) {
      log.trace("SSLHandshake handshakeWrap {}", channelId);
    }
    if (netWriteBuffer.hasRemaining()) {
      throw new IllegalStateException(
          "handshakeWrap called with netWriteBuffer not empty");
    }
    // this should never be called with a network buffer that contains data
    // so we can clear it here.
    netWriteBuffer.clear();
    SSLEngineResult result = sslEngine.wrap(emptyBuf, netWriteBuffer);
    // prepare the results to be written
    netWriteBuffer.flip();
    handshakeStatus = result.getHandshakeStatus();
    if (result.getStatus() == SSLEngineResult.Status.OK
        && result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      handshakeStatus = runDelegatedTasks();
    }
    if (doWrite) flush(netWriteBuffer);
    return result;
  }

  /**
   * Perform handshake unwrap
   */
  private SSLEngineResult handshakeUnwrap(boolean doRead) throws IOException {
    if (isTraceEnabled) {
      log.trace("SSLHandshake handshakeUnwrap {}", channelId);
    }
    SSLEngineResult result;
    boolean cont;
    int read;
    if (doRead) {
      read = socketChannel.read(netReadBuffer);
      if (read == -1) throw new EOFException("EOF during handshake.");
    }
    do {
      // prepare the buffer with the incoming data
      netReadBuffer.flip();
      result = sslEngine.unwrap(netReadBuffer, appReadBuffer);
      netReadBuffer.compact();
      handshakeStatus = result.getHandshakeStatus();
      if (result.getStatus() == SSLEngineResult.Status.OK
          && result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
        handshakeStatus = runDelegatedTasks();
      }
      cont = result.getStatus() == SSLEngineResult.Status.OK
          && handshakeStatus == HandshakeStatus.NEED_UNWRAP;
      if (isTraceEnabled) {
        log.trace("SSLHandshake handshakeUnwrap: handshakeStatus {} status {}",
            handshakeStatus, result.getStatus());
      }
    } while (netReadBuffer.position() != 0 && cont);
    return result;
  }

  /**
   * Reads a sequence of bytes from this channel into the given buffer.
   *
   * @param dst The buffer into which bytes are to be transferred
   * @return The number of bytes read, possible zero or -1 if the channel
   * has reached end-of-stream
   * @throws IOException if some other I/O error occurs
   */
  @Override
  public int read(ByteBuffer dst) throws IOException {
    if (closing) return -1;
    int read = 0;
    if (!handshakeComplete) return read;
    // if we have unread decrypted data in appReadBuffer read that into
    // dst buffer
    if (appReadBuffer.position() > 0) {
      read = readFromAppBuffer(dst);
    }
    if (dst.remaining() > 0) {
      netReadBuffer = ClientSharedUtils.ensureCapacity(netReadBuffer,
          netReadBufferSize(), useDirectBuffers, BUFFER_OWNER);
      if (netReadBuffer.remaining() > 0) {
        int netRead = socketChannel.read(netReadBuffer);
        if (netRead == 0 && netReadBuffer.position() == 0) return netRead;
        else if (netRead < 0) throw new EOFException("EOF during read");
      }
      do {
        netReadBuffer.flip();
        SSLEngineResult unwrapResult = sslEngine.unwrap(netReadBuffer,
            appReadBuffer);
        netReadBuffer.compact();
        // handle ssl renegotiation.
        if (unwrapResult.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING
            && unwrapResult.getStatus() == Status.OK) {
          if (isTraceEnabled) {
            log.trace("SSLChannel Read begin renegotiation channelId {}, " +
                    "appReadBuffer pos {}, netReadBuffer pos {}, " +
                    "netWriteBuffer pos {}", channelId, appReadBuffer.position(),
                netReadBuffer.position(), netWriteBuffer.position());
          }
          renegotiate();
          break;
        }
        if (unwrapResult.getStatus() == Status.OK) {
          read += readFromAppBuffer(dst);
        } else if (unwrapResult.getStatus() == Status.BUFFER_OVERFLOW) {
          int currentApplicationBufferSize = applicationBufferSize();
          appReadBuffer = ClientSharedUtils.ensureCapacity(appReadBuffer,
              currentApplicationBufferSize, useDirectBuffers, BUFFER_OWNER);
          if (appReadBuffer.position() >= currentApplicationBufferSize) {
            throw new IllegalStateException(
                "Buffer overflow when available data size (" +
                    appReadBuffer.position() + ") >= application buffer size (" +
                    currentApplicationBufferSize + ")");
          }
          // appReadBuffer will extended upto currentApplicationBufferSize
          // we need to read the existing content into dst before we can do
          // unwrap again. If there are no space in dst we can break here.
          if (dst.hasRemaining()) {
            read += readFromAppBuffer(dst);
          } else {
            break;
          }
        } else if (unwrapResult.getStatus() == Status.BUFFER_UNDERFLOW) {
          int currentNetReadBufferSize = netReadBufferSize();
          netReadBuffer = ClientSharedUtils.ensureCapacity(netReadBuffer,
              currentNetReadBufferSize, useDirectBuffers, BUFFER_OWNER);
          if (netReadBuffer.position() >= currentNetReadBufferSize) {
            throw new IllegalStateException(
                "Buffer underflow when available data size (" +
                    netReadBuffer.position() + ") > packet buffer size (" +
                    currentNetReadBufferSize + ")");
          }
          break;
        } else if (unwrapResult.getStatus() == Status.CLOSED) {
          throw new EOFException();
        }
      } while (netReadBuffer.position() != 0);
    }
    return read;
  }

  /**
   * Reads a sequence of bytes from this channel into the given buffers.
   *
   * @param dsts - The buffers into which bytes are to be transferred.
   * @return The number of bytes read, possibly zero, or -1 if the channel
   * has reached end-of-stream.
   * @throws IOException if some other I/O error occurs
   */
  @Override
  public long read(ByteBuffer[] dsts) throws IOException {
    return read(dsts, 0, dsts.length);
  }

  /**
   * Reads a sequence of bytes from this channel into a subsequence
   * of the given buffers.
   *
   * @param dsts   - The buffers into which bytes are to be transferred
   * @param offset - The offset within the buffer array of the first buffer
   *               into which bytes are to be transferred; must be non-negative
   *               and no larger than dsts.length.
   * @param length - The maximum number of buffers to be accessed; must be
   *               non-negative and no larger than dsts.length - offset
   * @return The number of bytes read, possibly zero, or -1 if the channel
   * has reached end-of-stream.
   * @throws IOException if some other I/O error occurs
   */
  @Override
  public long read(ByteBuffer[] dsts, int offset, int length)
      throws IOException {
    if ((offset < 0) || (length < 0) || (offset > dsts.length - length)) {
      throw new IndexOutOfBoundsException();
    }
    int totalRead = 0;
    int i = offset;
    while (i < length) {
      if (dsts[i].hasRemaining()) {
        int read = read(dsts[i]);
        if (read > 0)
          totalRead += read;
        else
          break;
      }
      if (!dsts[i].hasRemaining()) {
        i++;
      }
    }
    return totalRead;
  }

  /**
   * Writes a sequence of bytes to this channel from the given buffer.
   *
   * @param src The buffer from which bytes are to be retrieved
   * @return The number of bytes read, possibly zero, or -1 if the channel
   * has reached end-of-stream
   * @throws IOException If some other I/O error occurs
   */
  @Override
  public int write(ByteBuffer src) throws IOException {
    int written = 0;
    if (closing) throw new IllegalStateException("Channel is in closing state");
    if (!handshakeComplete) return written;
    if (!flush(netWriteBuffer))
      return written;
    netWriteBuffer.clear();
    SSLEngineResult wrapResult = sslEngine.wrap(src, netWriteBuffer);
    netWriteBuffer.flip();
    // handle ssl renegotiation
    if (wrapResult.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING
        && wrapResult.getStatus() == Status.OK) {
      renegotiate();
      return written;
    }
    if (wrapResult.getStatus() == Status.OK) {
      written = wrapResult.bytesConsumed();
      flush(netWriteBuffer);
    } else if (wrapResult.getStatus() == Status.BUFFER_OVERFLOW) {
      int currentNetWriteBufferSize = netWriteBufferSize();
      netWriteBuffer.compact();
      netWriteBuffer = ClientSharedUtils.ensureCapacity(netWriteBuffer,
          currentNetWriteBufferSize, useDirectBuffers, BUFFER_OWNER);
      netWriteBuffer.flip();
      if (netWriteBuffer.limit() >= currentNetWriteBufferSize)
        throw new IllegalStateException(
            "SSL BUFFER_OVERFLOW when available data size (" +
                netWriteBuffer.limit() + ") >= network buffer size (" +
                currentNetWriteBufferSize + ")");
    } else if (wrapResult.getStatus() == Status.BUFFER_UNDERFLOW) {
      throw new IllegalStateException("SSL BUFFER_UNDERFLOW during write");
    } else if (wrapResult.getStatus() == Status.CLOSED) {
      throw new EOFException();
    }
    return written;
  }

  /**
   * Writes a sequence of bytes to this channel from the subsequence of the
   * given buffers.
   *
   * @param srcs   The buffers from which bytes are to be retrieved
   * @param offset The offset within the buffer array of the first buffer
   *               from which bytes are to be retrieved; must be non-negative
   *               and no larger than srcs.length.
   * @param length - The maximum number of buffers to be accessed; must be
   *               non-negative and no larger than srcs.length - offset.
   * @return returns no.of bytes written , possibly zero.
   * @throws IOException If some other I/O error occurs
   */
  @Override
  public long write(ByteBuffer[] srcs, int offset, int length)
      throws IOException {
    if ((offset < 0) || (length < 0) || (offset > srcs.length - length)) {
      throw new IndexOutOfBoundsException();
    }
    int totalWritten = 0;
    int i = offset;
    while (i < length) {
      if (srcs[i].hasRemaining() || hasPendingWrites()) {
        int written = write(srcs[i]);
        if (written > 0) {
          totalWritten += written;
        }
      }
      if (!srcs[i].hasRemaining() && !hasPendingWrites()) {
        i++;
      } else {
        // if we are unable to write the current buffer to socketChannel we
        // should break, as we might have reached max socket send buffer size.
        break;
      }
    }
    return totalWritten;
  }

  /**
   * Writes a sequence of bytes to this channel from the given buffers.
   *
   * @param srcs The buffers from which bytes are to be retrieved
   * @return returns no.of bytes consumed by SSLEngine.wrap , possibly zero.
   * @throws IOException If some other I/O error occurs
   */
  @Override
  public long write(ByteBuffer[] srcs) throws IOException {
    return write(srcs, 0, srcs.length);
  }

  /**
   * returns delegatedTask for the SSLEngine.
   */
  protected Runnable delegatedTask() {
    return sslEngine.getDelegatedTask();
  }

  /**
   * transfers appReadBuffer contents (decrypted data) into dst bytebuffer
   *
   * @param dst ByteBuffer
   */
  private int readFromAppBuffer(ByteBuffer dst) {
    appReadBuffer.flip();
    int remaining = Math.min(appReadBuffer.remaining(), dst.remaining());
    if (remaining > 0) {
      int limit = appReadBuffer.limit();
      appReadBuffer.limit(appReadBuffer.position() + remaining);
      dst.put(appReadBuffer);
      appReadBuffer.limit(limit);
    }
    appReadBuffer.compact();
    return remaining;
  }

  protected int netReadBufferSize() {
    return sslEngine.getSession().getPacketBufferSize();
  }

  protected int netWriteBufferSize() {
    return sslEngine.getSession().getPacketBufferSize();
  }

  protected int applicationBufferSize() {
    return sslEngine.getSession().getApplicationBufferSize();
  }

  private void handshakeFailure() {
    // Release all resources such as internal buffers that SSLEngine is managing
    sslEngine.closeOutbound();
    try {
      sslEngine.closeInbound();
    } catch (SSLException e) {
      log.debug("SSLEngine.closeInBound() raised an exception.", e);
    }
  }
}
