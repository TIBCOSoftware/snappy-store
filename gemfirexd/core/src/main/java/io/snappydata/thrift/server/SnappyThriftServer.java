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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.tcp.ConnectionTable;
import com.gemstone.gnu.trove.TObjectProcedure;
import com.pivotal.gemfirexd.NetworkInterface.ConnectionListener;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.diag.SessionsVTI;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import io.snappydata.thrift.LocatorService;
import io.snappydata.thrift.common.SocketParameters;
import io.snappydata.thrift.common.TBinaryProtocolDirect;
import io.snappydata.thrift.common.TCompactProtocolDirect;
import io.snappydata.thrift.common.ThriftUtils;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * Encapsulates start/stop/process of a Thrift server.
 */
public final class SnappyThriftServer {

  private LocatorServiceImpl service;
  private volatile TServer thriftServer;
  private ThreadPoolExecutor thriftExecutor;
  private ThreadPoolExecutor thriftThreadPerConnExecutor;
  private Thread thriftMainThread;

  public synchronized void start(final InetAddress thriftAddress,
      final int thriftPort, int maxThreads, final boolean isServer,
      final boolean useBinaryProtocol, final boolean useFramedTransport,
      final boolean useSSL, final SocketParameters socketParams,
      final ConnectionListener listener) throws TTransportException {

    if (isServing()) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "A thrift server is already running", null);
    }

    final TServerTransport serverTransport;
    final InetSocketAddress bindAddress;
    final String hostAddress;
    if (thriftAddress != null) {
      bindAddress = new InetSocketAddress(thriftAddress, thriftPort);
    } else {
      try {
        bindAddress = new InetSocketAddress(SocketCreator.getLocalHost(),
            thriftPort);
      } catch (UnknownHostException uhe) {
        throw new TTransportException(
            "Could not determine localhost for default bind address.", uhe);
      }
    }

    serverTransport = useSSL
        ? SnappyTSSLServerSocketFactory.getServerSocket(bindAddress, socketParams)
        : new SnappyTServerSocket(bindAddress, false, true, true, socketParams);
    hostAddress = bindAddress.getAddress().toString();

    final TProcessor processor;
    if (isServer) {
      SnappyDataServiceImpl service = new SnappyDataServiceImpl(hostAddress,
          thriftPort);
      processor = new SnappyDataServiceImpl.Processor(service);
      this.service = service;
    } else {
      // only locator service on non-server VMs
      LocatorServiceImpl service = new LocatorServiceImpl(hostAddress,
          thriftPort);
      processor = new LocatorService.Processor<>(service);
      this.service = service;
    }

    final LogWriterImpl.LoggingThreadGroup interruptibleGroup = LogWriterImpl
        .createThreadGroup("SnappyThriftServer Threads", Misc.getI18NLogWriter());
    interruptibleGroup.setInterruptible();
    final ThreadFactory tf = new ThreadFactory() {
      private final AtomicInteger threadNum = new AtomicInteger(0);

      @Override
      public Thread newThread(@Nonnull final Runnable command) {
        final DM dm = Misc.getDistributedSystem().getDistributionManager();
        dm.getStats().incThriftProcessingThreadStarts();
        Runnable r = () -> {
          dm.getStats().incNumThriftProcessingThreads(1);
          try {
            command.run();
          } finally {
            dm.getStats().incNumThriftProcessingThreads(-1);
            ConnectionTable.releaseThreadsSockets();
          }
        };
        Thread thread = new Thread(interruptibleGroup, r,
            "ThriftProcessor-" + threadNum.getAndIncrement());
        thread.setDaemon(true);
        return thread;
      }
    };
    final int parallelism = Math.max(
        Runtime.getRuntime().availableProcessors(), 4);
    if (!ThriftUtils.isThriftSelectorServer()) {
      final SnappyThriftServerThreadPool.Args serverArgs =
          new SnappyThriftServerThreadPool.Args(serverTransport);
      TProtocolFactory protocolFactory = useBinaryProtocol
          ? new TBinaryProtocolDirect.Factory(true)
          : new TCompactProtocolDirect.Factory(true);

      serverArgs.processor(processor).protocolFactory(protocolFactory);
      if (useFramedTransport) {
        serverArgs.transportFactory(new TFramedTransport.Factory());
      }
      this.thriftExecutor = new ThreadPoolExecutor(parallelism * 2,
          maxThreads, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), tf);
      serverArgs.setExecutorService(this.thriftExecutor).setConnectionListener(
          listener);

      this.thriftServer = new SnappyThriftServerThreadPool(serverArgs);
    } else {
      // selector and per-thread hybrid server
      final SnappyThriftServerSelector.Args serverArgs =
          new SnappyThriftServerSelector.Args(serverTransport);
      TProtocolFactory protocolFactory = useBinaryProtocol
          ? new TBinaryProtocolDirect.Factory(true)
          : new TCompactProtocolDirect.Factory(true);

      final int numSelectors = parallelism * 2;
      final int numThreads = parallelism * 2;
      serverArgs.processor(processor).protocolFactory(protocolFactory)
          .setNumSelectors(numSelectors).setConnectionListener(listener);
      // Keep finite size of blocking queue to allow queueing.
      // Limit the maximum number of threads further to avoid OOMEs.
      int executorThreads = Math.min(Math.max(64, numThreads * 2), maxThreads);
      int maxQueued = Math.min(Math.max(1024, numThreads * 16), maxThreads);
      this.thriftExecutor = new ThreadPoolExecutor(executorThreads, executorThreads,
          60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(maxQueued), tf);
      this.thriftExecutor.allowCoreThreadTimeOut(true);
      this.thriftThreadPerConnExecutor = new ThreadPoolExecutor(1, numThreads,
          30L, TimeUnit.SECONDS, new SynchronousQueue<>(), tf);
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
        this.thriftMainThread.join(1000L);
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

  public void collectStatisticsSample() {
    // TODO: SW: create statistics for thrift (especially the
    //   selector mode since many below are not relevant for it)
    /*
    final ConnectionStats stats = InternalDriver.activeDriver()
        .getConnectionStats();

    int connOpen = 0;
    long bytesRead = 0, bytesWritten = 0;
    long totNumTimesWaited = 0, totalServerThreadIdleTime = 0;
    long totalNumCommandsProcessed = 0, totalProcessTime = 0;
    final long totalThreads;
    long waitingThreads = 0;
    int numClientConnectionsIdle = 0;

    synchronized (threadsSync) {
      totalThreads = threadList.size();

      final long[] connThreadStats = new long[5];
      for (int i = 0; i < totalThreads; i++) {
        final DRDAConnThread connThread = ((DRDAConnThread)threadList.get(i));
        if (connThread.hasSession()) {
          connOpen++;
        }
        bytesRead += connThread.getAndResetBytesRead();
        bytesWritten += connThread.getAndResetBytesWritten();

        connThread.getAndResetConnActivityStats(connThreadStats);
        // if begin is non-zero that means this thread is waiting.
        long beginWaitTimeMillis = connThreadStats[0];
        final long numTimesCurrentThreadWaited = connThreadStats[1];
        if (numTimesCurrentThreadWaited > 0) {
          totNumTimesWaited += numTimesCurrentThreadWaited; // numTimesWaited
          waitingThreads++;
        }
        long currWaitingTime = (beginWaitTimeMillis > 0 ? (System
            .currentTimeMillis() - beginWaitTimeMillis) : 0)
            /*in case this thread is stuck on readHeader().*;
        totalServerThreadIdleTime += connThreadStats[2];
        if (currWaitingTime > 0) {
          numClientConnectionsIdle++;
          totalServerThreadIdleTime += (currWaitingTime * 1000000);
        }
        totalNumCommandsProcessed += connThreadStats[3];
        totalProcessTime += connThreadStats[4];
      }
    }

    stats.setNetServerThreads(totalThreads);
    stats.setNetServerWaitingThreads(waitingThreads);
    stats.setClientConnectionsIdle(numClientConnectionsIdle);
    stats.setClientConnectionsOpen(connOpen);
    stats.setClientConnectionsQueued(queuedConn);
    stats.incTotalBytesRead(bytesRead);
    stats.incTotalBytesWritten(bytesWritten);

    stats.incNetServerThreadLongWaits(totNumTimesWaited);
    stats.incNetServerThreadIdleTime(totalServerThreadIdleTime / 1000000);
    stats.incCommandsProcessed(totalNumCommandsProcessed);
    stats.incCommandsProcessTime(totalProcessTime);
    */
  }

  public void getSessionInfo(final SessionsVTI.SessionInfo info) {
    final LocatorServiceImpl service = this.service;
    if (service instanceof SnappyDataServiceImpl) {
      final SnappyDataServiceImpl dataService = (SnappyDataServiceImpl)service;
      dataService.recordStatementStartTime = true;
      dataService.connectionMap.forEachValue(new TObjectProcedure() {
        @Override
        public boolean execute(Object holder) {
          ConnectionHolder connHolder = (ConnectionHolder)holder;
          final SessionsVTI.SessionInfo.ClientSession cs =
              new SessionsVTI.SessionInfo.ClientSession();
          cs.connNum = connHolder.getConnectionId();
          cs.isActive = connHolder.getConnection().isActive();
          cs.clientBindAddress = connHolder.getClientHostName() + ':' +
              connHolder.getClientID();
          cs.clientBindPort = dataService.hostPort;
          cs.hadConnectedOnce = dataService.clientTrackerMap.containsKey(
              connHolder.getClientHostId());
          cs.isConnected = dataService.connectionMap.containsKeyPrimitive(
              connHolder.getConnectionId());
          cs.userId = connHolder.getUserName();
          cs.connectionBeginTimeStamp = new Timestamp(connHolder.getStartTime());
          // statement information
          ConnectionHolder.StatementHolder activeStatement =
              connHolder.getActiveStatement();
          if (activeStatement != null) {
            Statement stmt = activeStatement.getStatement();
            EmbedStatement estmt = (stmt instanceof EmbedStatement)
                ? (EmbedStatement)stmt : null;
            cs.currentStatementUUID = estmt != null ? estmt.getStatementUUID()
                : "Statement@" + Integer.toHexString(
                System.identityHashCode(stmt));
            cs.currentStatement = String.valueOf(activeStatement.getSQL());
            cs.currentStatementStatus = activeStatement.getStatus();
            final long startTime = activeStatement.getStartTime();
            if (startTime > 0) {
              cs.currentStatementElapsedTime = Math.max(System.nanoTime()
                  - startTime, 0L) / 1000000000.0;
            }
            cs.currentStatementAccessFrequency =
                activeStatement.getAccessFrequency();
            if (estmt != null) {
              try {
                cs.currentStatementEstimatedMemUsage =
                    estmt.getEstimatedMemoryUsage();
              } catch (StandardException se) {
                throw new GemFireXDRuntimeException(se);
              }
            }
          }
          info.addClientSession(cs);
          return false;
        }
      });
    } else if (service != null) {
      final SessionsVTI.SessionInfo.ClientSession cs =
          new SessionsVTI.SessionInfo.ClientSession();
      cs.isActive = service.isActive();
      cs.clientBindAddress = service.hostAddress;
      cs.clientBindPort = service.hostPort;
      cs.currentStatementStatus = "LOCATOR";
      info.addClientSession(cs);
    }
  }
}
