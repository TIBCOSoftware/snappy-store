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

package io.snappydata.thrift.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.internal.SocketCreator;
import com.pivotal.gemfirexd.NetworkInterface.ConnectionListener;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import io.snappydata.thrift.common.SocketParameters;
import io.snappydata.thrift.common.ThriftUtils;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * Encapsulates start/stop/process of a Thrift server.
 */
public final class SnappyThriftServer {

  private InetAddress thriftAddress;
  private int thriftPort;

  public InetAddress getThriftAddress() {
    return thriftAddress;
  }

  public int getThriftPort() {
    return thriftPort;
  }

  private LocatorServiceImpl service;
  private TServer thriftServer;
  private ExecutorService thriftExecutor;
  private ThreadPoolExecutor thriftThreadPerConnExecutor;
  private Thread thriftMainThread;

  public synchronized void start(final InetAddress thriftAddress,
      final int thriftPort, int maxThreads, final boolean isServer,
      final boolean useBinaryProtocol, final boolean useSSL,
      final SocketParameters socketParams, final ConnectionListener listener)
      throws TTransportException {

    this.thriftAddress = thriftAddress;
    this.thriftPort = thriftPort;

    if (isServing()) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "A thrift server is already running", null);
    }

    final TServerTransport serverTransport;
    final InetSocketAddress bindAddress;
    final String hostAddress;
    if (this.thriftAddress != null) {
      bindAddress = new InetSocketAddress(this.thriftAddress, this.thriftPort);
    }
    else {
      try {
        bindAddress = new InetSocketAddress(SocketCreator.getLocalHost(),
            this.thriftPort);
      } catch (UnknownHostException uhe) {
        throw new TTransportException(
            "Could not determine localhost for default bind address.", uhe);
      }
    }

    serverTransport = useSSL
        ? SnappyTSSLServerSocketFactory.getServerSocket(bindAddress, socketParams)
        : new SnappyTServerSocket(bindAddress, true, true, socketParams);
    hostAddress = bindAddress.getAddress().toString();

    final TProcessor processor;
    if (isServer) {
      SnappyDataServiceImpl service = new SnappyDataServiceImpl(hostAddress,
          this.thriftPort);
      processor = new SnappyDataServiceImpl.Processor(service);
      this.service = service;
    }
    else {
      // only locator service on non-server VMs
      LocatorServiceImpl service = new LocatorServiceImpl(hostAddress,
          this.thriftPort);
      processor = new LocatorServiceImpl.Processor(service);
      this.service = service;
    }

    final int parallelism = Math.max(
        Runtime.getRuntime().availableProcessors(), 4);
    if (useSSL || !ThriftUtils.isThriftSelectorServer()) {
      final SnappyThriftServerThreadPool.Args serverArgs =
          new SnappyThriftServerThreadPool.Args(serverTransport);
      TProtocolFactory protocolFactory = useBinaryProtocol
          ? new TBinaryProtocol.Factory() : new TCompactProtocol.Factory();

      serverArgs.processor(processor).protocolFactory(protocolFactory);
      this.thriftExecutor = new ThreadPoolExecutor(parallelism * 2,
          maxThreads, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
      serverArgs.setExecutorService(this.thriftExecutor).setConnectionListener(
          listener);

      this.thriftServer = new SnappyThriftServerThreadPool(serverArgs);
    }
    else {
      // selector and per-thread hybrid server
      final SnappyThriftServerSelector.Args serverArgs =
          new SnappyThriftServerSelector.Args(serverTransport);
      TProtocolFactory protocolFactory = useBinaryProtocol
          ? new TBinaryProtocol.Factory() : new TCompactProtocol.Factory();

      final int numSelectors = parallelism * 2;
      final int numThreads = parallelism * 2;
      serverArgs.processor(processor).protocolFactory(protocolFactory)
          .setNumSelectors(numSelectors).setConnectionListener(listener);
      this.thriftExecutor = new ThreadPoolExecutor(1, maxThreads, 30L,
          TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
      this.thriftThreadPerConnExecutor = new ThreadPoolExecutor(1, numThreads,
          30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
      serverArgs.setExecutorService(this.thriftExecutor);
      serverArgs.setThreadPerConnExecutor(this.thriftThreadPerConnExecutor);
      this.thriftServer = new SnappyThriftServerSelector(serverArgs);
    }
    thriftMainThread = new Thread(new Runnable() {
      @Override
      public void run() {
        thriftServer.serve();
      }
    }, "ThriftServerThread");

    thriftMainThread.setDaemon(true);
    thriftMainThread.start();
  }

  public synchronized void stop() {
    final TServer thriftServer = this.thriftServer;
    if (thriftServer != null) {
      this.service.stop();
      thriftServer.stop();
      final ThreadPoolExecutor connExecutor = this.thriftThreadPerConnExecutor;
      if (connExecutor != null) {
        connExecutor.shutdown();
      }
      this.thriftExecutor.shutdown();
      try {
        this.thriftMainThread.join(5000L);
        // force stop the executor if required
        if (this.thriftMainThread.isAlive()) {
          if (connExecutor != null) {
            connExecutor.shutdownNow();
          }
          this.thriftExecutor.shutdownNow();
          this.thriftMainThread.join();
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public final boolean isServing() {
    final TServer thriftServer = this.thriftServer;
    return thriftServer != null && thriftServer.isServing();
  }
}
