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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.pivotal.gemfirexd.NetworkInterface.ConnectionListener;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SnappyThriftServerThreadPool extends TServer {

  private final Logger logger = LoggerFactory
      .getLogger(SnappyThriftServerThreadPool.class.getName());

  public static final class Args extends AbstractServerArgs<Args> {

    private ConnectionListener connListener;
    private int minWorkerThreads = 5;
    private int maxWorkerThreads = Integer.MAX_VALUE;
    private ExecutorService executorService;
    private int stopTimeoutVal = 60;
    private TimeUnit stopTimeoutUnit = TimeUnit.SECONDS;

    public Args(TServerTransport transport) {
      super(transport);
    }

    public ConnectionListener getConnectionListener() {
      return this.connListener;
    }

    public Args setConnectionListener(ConnectionListener connListener) {
      this.connListener = connListener;
      return this;
    }

    public int getMinWorkerThreads() {
      return this.minWorkerThreads;
    }

    public Args setMinWorkerThreads(int n) {
      minWorkerThreads = n;
      return this;
    }

    public int getMaxWorkerThreads() {
      return this.maxWorkerThreads;
    }

    public Args setMaxWorkerThreads(int n) {
      maxWorkerThreads = n;
      return this;
    }

    public ExecutorService getExecutorService() {
      return this.executorService;
    }

    public Args setExecutorService(ExecutorService executorService) {
      this.executorService = executorService;
      return this;
    }

    public int getStopTimeoutVal() {
      return this.stopTimeoutVal;
    }

    public TimeUnit getStopTimeoutUnit() {
      return this.stopTimeoutUnit;
    }

    public Args setStopTimeout(int timeout, TimeUnit timeoutUnit) {
      this.stopTimeoutVal = timeout;
      this.stopTimeoutUnit = timeoutUnit;
      return this;
    }
  }

  // Executor service for handling client connections
  private final ExecutorService executorService;
  private final ConnectionListener connListener;
  // Flag for stopping the server
  private volatile boolean stopped;
  private final TimeUnit stopTimeoutUnit;
  private final long stopTimeoutVal;
  private final AtomicInteger connectionCounter;

  public SnappyThriftServerThreadPool(Args args) {
    super(args);

    this.stopTimeoutUnit = args.stopTimeoutUnit;
    this.stopTimeoutVal = args.stopTimeoutVal;

    this.executorService = args.executorService != null ? args.executorService
        : createDefaultExecutorService(args);
    this.connListener = args.connListener;
    this.connectionCounter = new AtomicInteger(0);
  }

  private static ExecutorService createDefaultExecutorService(Args args) {
    SynchronousQueue<Runnable> executorQueue = new SynchronousQueue<Runnable>();
    return new ThreadPoolExecutor(args.minWorkerThreads, args.maxWorkerThreads,
        60, TimeUnit.SECONDS, executorQueue);
  }

  @Override
  public void serve() {
    try {
      serverTransport_.listen();
    } catch (TTransportException tte) {
      logger.error("Error occurred during listening.", tte);
      return;
    }

    // Run the preServe event
    if (eventHandler_ != null) {
      eventHandler_.preServe();
    }

    stopped = false;
    setServing(true);
    while (!stopped) {
      try {
        TTransport client = serverTransport_.accept();
        WorkerProcess wp = new WorkerProcess(client,
            this.connectionCounter.incrementAndGet());
        executorService.execute(wp);
      } catch (TTransportException tte) {
        if (!stopped) {
          logger.warn("Transport error occurred during accept of message.",
              tte);
        }
      }
    }

    executorService.shutdown();

    // Loop until awaitTermination finally does return without a interrupted
    // exception. If we don't do this, then we'll shut down prematurely. We want
    // to let the executorService clear it's task queue, closing client sockets
    // appropriately.
    long timeoutMS = stopTimeoutUnit.toMillis(stopTimeoutVal);
    long now = System.currentTimeMillis();
    while (timeoutMS >= 0) {
      try {
        executorService.awaitTermination(timeoutMS, TimeUnit.MILLISECONDS);
        break;
      } catch (InterruptedException ix) {
        long newnow = System.currentTimeMillis();
        timeoutMS -= (newnow - now);
        now = newnow;
      }
    }
    setServing(false);
  }

  @Override
  public void stop() {
    stopped = true;
    serverTransport_.interrupt();
  }

  private class WorkerProcess implements Runnable {

    /**
     * Client that this services.
     */
    private final TTransport client;
    private final int connectionNumber;

    /**
     * Default constructor.
     *
     * @param client
     *          Transport to process
     */
    private WorkerProcess(TTransport client, int connectionNumber) {
      this.client = client;
      this.connectionNumber = connectionNumber;
    }

    /**
     * Loops on processing a client forever
     */
    @Override
    public void run() {
      TProcessor processor = null;
      TTransport inputTransport = null;
      TTransport outputTransport = null;
      TProtocol inputProtocol = null;
      TProtocol outputProtocol = null;

      final TServerEventHandler eventHandler = getEventHandler();
      ServerContext connectionContext = null;

      final ConnectionListener listener = connListener;
      final TTransport client = this.client;

      try {
        processor = processorFactory_.getProcessor(client);
        inputTransport = inputTransportFactory_.getTransport(client);
        outputTransport = outputTransportFactory_.getTransport(client);
        inputProtocol = inputProtocolFactory_.getProtocol(inputTransport);
        outputProtocol = outputProtocolFactory_.getProtocol(outputTransport);

        if (eventHandler != null) {
          connectionContext = eventHandler.createContext(inputProtocol,
              outputProtocol);
        }
        // register with ConnectionListener
        if (listener != null) {
          listener.connectionOpened(client, processor, this.connectionNumber);
        }
        // we check stopped_ first to make sure we're not supposed to be
        // shutting down. this is necessary for graceful shutdown.
        while (true) {

          if (eventHandler != null) {
            eventHandler.processContext(connectionContext, inputTransport,
                outputTransport);
          }

          if (stopped || !processor.process(inputProtocol, outputProtocol)) {
            break;
          }
        }
      } catch (TTransportException te) {
        // Assume the client died and continue
        logger.debug("Thrift error occurred during processing of message.", te);
      } catch (TException te) {
        logger.warn("Thrift error occurred during processing of message.", te);
      } catch (Exception e) {
        logger.error("Error occurred during processing of message.", e);
      }

      if (eventHandler != null) {
        eventHandler.deleteContext(connectionContext, inputProtocol,
            outputProtocol);
      }

      if (inputTransport != null) {
        inputTransport.close();
      }

      if (outputTransport != null) {
        outputTransport.close();
      }

      // deregister with ConnectionListener
      if (listener != null) {
        listener.connectionClosed(client, processor, this.connectionNumber);
      }
    }
  }
}
