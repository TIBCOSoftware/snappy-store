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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.internal.shared.InputStreamChannel;
import com.pivotal.gemfirexd.NetworkInterface.ConnectionListener;
import com.pivotal.gemfirexd.internal.engine.Misc;
import io.snappydata.thrift.common.SnappyTSocket;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.server.ServerContext;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TServerEventHandler;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An optimized selector based server. There are multiple selectors having a set
 * of connections to it. The selector itself is recycled among the threads in
 * pool with the current selector thread itself processing the request and
 * handing over the SelectorProcess to the pool for further handling by other
 * available threads.
 * <p>
 * The idea, thus, is to handle any context switch in a single request from
 * client as long as a selector thread is currently available and doing the
 * select. There may be a slight delay if multiple concurrent requests come to
 * the same selector whereby selector thread goes out to process and a new
 * selector thread is chosen by the thread pool, but that will be comparable to
 * normal thread context switches in any implementation. For this reason number
 * of selector threads is chosen as some factor of total number of CPUs by
 * default. Overall this strategy should give a good combination of scalability
 * and minimal thread context switches.
 */
public final class SnappyThriftServerSelector extends TServer {

  private final Logger LOGGER = LoggerFactory
      .getLogger(SnappyThriftServerSelector.class.getName());

  public static final class Args extends AbstractServerArgs<Args> {

    private ConnectionListener connListener;
    private int numSelectors = 8;
    private int minWorkerThreads = 8;
    private int maxWorkerThreads = Short.MAX_VALUE;
    private ExecutorService executorService;
    private ThreadPoolExecutor threadPerConnExecutor;

    /**
     * The size of the blocking queue per selector thread for passing accepted
     * connections to the selector thread
     */
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

    public int getNumSelectors() {
      return this.numSelectors;
    }

    public Args setNumSelectors(int n) {
      this.numSelectors = n;
      return this;
    }

    public int getMinWorkerThreads() {
      return this.minWorkerThreads;
    }

    public Args setMinWorkerThreads(int n) {
      this.minWorkerThreads = n;
      return this;
    }

    public int getMaxWorkerThreads() {
      return this.maxWorkerThreads;
    }

    public Args setMaxWorkerThreads(int n) {
      this.maxWorkerThreads = n;
      return this;
    }

    public ExecutorService getExecutorService() {
      return this.executorService;
    }

    public Args setExecutorService(ExecutorService executorService) {
      this.executorService = executorService;
      return this;
    }

    public ExecutorService getThreadPerConnExecutor() {
      return this.threadPerConnExecutor;
    }

    public Args setThreadPerConnExecutor(ThreadPoolExecutor executorService) {
      this.threadPerConnExecutor = executorService;
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

    public void validate() {
      if (this.numSelectors <= 0) {
        throw new IllegalArgumentException("numSelectors must be positive.");
      }
      if (this.minWorkerThreads < 0) {
        throw new IllegalArgumentException(
            "minWorkerThreads must be non-negative.");
      }
      if (this.maxWorkerThreads <= 0) {
        throw new IllegalArgumentException(
            "maxWorkerThreads must be positive.");
      }
    }
  }

  // Executor service for handling client connections
  private final ExecutorService executorService;
  private final ThreadPoolExecutor threadPerConnExecutor;
  private final ConnectionListener connListener;

  // Flag for stopping the server
  private volatile boolean stopped;
  final int numSelectors;
  private final SelectorProcess[] selectorProcesses;
  private int currentSelectorIndex;
  private final TimeUnit stopTimeoutUnit;
  private final long stopTimeoutVal;
  private final AtomicInteger connectionCounter;
  final AtomicInteger numSelectorsInExecution;

  public SnappyThriftServerSelector(Args args) {
    super(args);

    args.validate();
    this.numSelectors = args.numSelectors;
    this.selectorProcesses = new SelectorProcess[this.numSelectors];
    this.stopTimeoutUnit = args.stopTimeoutUnit;
    this.stopTimeoutVal = args.stopTimeoutVal;

    this.executorService = args.executorService != null ? args.executorService
        : createDefaultExecutorService(args);
    this.threadPerConnExecutor = args.threadPerConnExecutor != null
        ? args.threadPerConnExecutor : createDefaultExecutorService(args);
    RejectedExecutionHandler handleRejected = new RejectedExecutionHandler() {
      @Override
      public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        try {
          // register the client socket channel in selector beyond this
          final ClientProcessData data = ((ThreadWorker)r).data;
          data.clientSocket.getSocketChannel().configureBlocking(false);
          registerClientDataInNextSelector(data);
        } catch (IOException ioe) {
          if (!stopped) {
            LOGGER.warn("Transport error occurred during "
                + "acceptance of connection.", ioe);
          }
        }
      }
    };
    this.threadPerConnExecutor.setRejectedExecutionHandler(handleRejected);
    this.connListener = args.connListener;
    this.connectionCounter = new AtomicInteger(0);
    this.numSelectorsInExecution = new AtomicInteger(0);
  }

  private static ThreadPoolExecutor createDefaultExecutorService(Args args) {
    SynchronousQueue<Runnable> executorQueue = new SynchronousQueue<>();
    return new ThreadPoolExecutor(args.minWorkerThreads,
        args.maxWorkerThreads, 60, TimeUnit.SECONDS, executorQueue);
  }

  @Override
  public void serve() {
    // start listening, or exit
    try {
      this.serverTransport_.listen();
    } catch (TTransportException tte) {
      LOGGER.error("Failed to start listening on server socket!", tte);
      return;
    }

    // Run the preServe event
    if (this.eventHandler_ != null) {
      this.eventHandler_.preServe();
    }

    this.stopped = false;
    setServing(true);

    while (!this.stopped) {
      try {
        SnappyTSocket client = (SnappyTSocket)serverTransport_.accept();
        this.threadPerConnExecutor.execute(new ThreadWorker(
            newClientProcessData(client)));
      } catch (TTransportException tte) {
        if (!this.stopped) {
          LOGGER.warn(
              "Transport error occurred during accept of connection.", tte);
        }
      }
    }

    for (SelectorProcess proc : this.selectorProcesses) {
      if (proc != null) {
        proc.stop();
      }
    }

    // try to gracefully shut down the executor service
    this.executorService.shutdown();

    // Loop until awaitTermination finally does return without a interrupted
    // exception. If we don't do this, then we'll shut down prematurely. We want
    // to let the executorService clear it's task queue, closing client sockets
    // appropriately.
    long timeoutMS = stopTimeoutUnit.toMillis(stopTimeoutVal);
    long now = System.currentTimeMillis();
    while (timeoutMS >= 0) {
      try {
        this.executorService.awaitTermination(timeoutMS,
            TimeUnit.MILLISECONDS);
        break;
      } catch (InterruptedException ix) {
        long newnow = System.currentTimeMillis();
        timeoutMS -= (newnow - now);
        now = newnow;
      }
    }

    setServing(false);
    serverTransport_.close();
  }

  /**
   * sync on this should be held by caller
   */
  private void registerClientDataInNextSelector_(
      final ClientProcessData clientData, final SelectorProcess skipProc)
      throws IOException {
    SelectorProcess proc;
    while (true) {
      this.currentSelectorIndex = (this.currentSelectorIndex + 1)
          % this.numSelectors;
      proc = this.selectorProcesses[this.currentSelectorIndex];
      if (skipProc != null && proc == skipProc) {
        continue;
      }
      if (proc == null || proc.stopped) {
        final Selector selector = SelectorProvider.provider().openSelector();
        proc = new SelectorProcess(selector);
        proc.registerClient(clientData);
        this.selectorProcesses[this.currentSelectorIndex] = proc;
        this.executorService.execute(proc);
      } else {
        synchronized (proc.sync) {
          if (proc.inExecution) {
            continue;
          }
          proc.registerClient(clientData);
        }
      }
      return;
    }
  }

  synchronized final void registerClientDataInNextSelector(
      final ClientProcessData clientData) throws IOException {
    registerClientDataInNextSelector_(clientData, null);
  }

  synchronized final void registerClientDataInNextSelector(
      final ArrayList<ClientProcessData> clientsData,
      final SelectorProcess skipProc) throws IOException {
    for (ClientProcessData clientData : clientsData) {
      registerClientDataInNextSelector_(clientData, skipProc);
    }
  }

  @Override
  public void stop() {
    this.stopped = true;
    this.serverTransport_.close();
  }

  protected final boolean handleRead(final ClientProcessData data,
      boolean readFrameSize, final boolean nonBlocking) {

    boolean success = false;
    try {
      // we check stopped_ first to make sure we're not supposed to be
      // shutting down. this is necessary for graceful shutdown.
      if (data.connectionContext != null) {
        data.eventHandler.processContext(data.connectionContext,
            data.inputTransport, data.outputTransport);
      }

      // change interest to include OP_WRITE at this point
      /*
      if (nonBlocking) {
        final SelectionKey key = data.key;
        if (key != null && key.isValid()) {
          try {
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
          } catch (CancelledKeyException cke) {
            // ignore
          }
        }
      }
      */

      final InputStreamChannel input = data.clientSocket.getInputStream();
      // continue with the processing loop as long as there is data available
      // e.g. multiple frames were read into the buffer already
      while (!this.stopped) {
        if (readFrameSize) {
          input.readInt();
        }
        success = (data.processor.process(data.inputProtocol,
            data.outputProtocol));
        if (!success) {
          break;
        } else if (input.available() <= 0) {
          data.idle = true;
          // change interest to remove OP_WRITE at this point
          /*
          if (nonBlocking) {
            final SelectionKey key = data.key;
            if (key != null && key.isValid()) {
              try {
                key.interestOps(SelectionKey.OP_READ);
              } catch (CancelledKeyException cke) {
                // ignore
              }
            }
          }
          */
          return true;
        }
        // for subsequent frames, need to read frame size in any case
        readFrameSize = true;
        // reset success for next frame read
        success = false;
      }

      cleanupSelectionKey(data);
    } catch (TTransportException te) {
      // Assume the client died and continue
      LOGGER.debug("Thrift error occurred during processing of message.", te);
      cleanupSelectionKey(data);
    } catch (TProtocolException tpe) {
      // Assume the client died and continue
      LOGGER.warn("Thrift protocol error occurred during processing of "
          + "message. Closing this connection.", tpe);
      cleanupSelectionKey(data);
    } catch (TException te) {
      LOGGER.warn("Thrift error occurred during processing of message.", te);
      cleanupSelectionKey(data);
    } catch (Exception e) {
      LOGGER.error("Error occurred during processing of message.", e);
      cleanupSelectionKey(data);
    } finally {
      if (!success) {
        data.idle = true;
        // check for channel state
        if (!data.clientSocket.isOpen()) {
          cleanupConnection(data);
        }
      }
    }
    return false;
  }

  protected void cleanupConnection(final ClientProcessData data) {
    data.idle = false;
    if (data.eventHandler != null) {
      data.eventHandler.deleteContext(data.connectionContext,
          data.inputProtocol, data.outputProtocol);
    }

    // deregister with ConnectionListener
    final ConnectionListener listener = connListener;
    if (listener != null) {
      listener.connectionClosed(data.clientSocket, data.processor,
          data.connectionNumber);
    }
  }

  /**
   * Do connection-close cleanup on a given SelectionKey.
   */
  protected static void cleanupSelectionKey(ClientProcessData data) {
    // cancel the selection key and close channel
    data.close();
    final SelectionKey key = data.key;
    if (key != null) {
      data.key = null;
      key.cancel();
    }
  }

  protected final void closeConnection(final ClientProcessData data) {
    if (data != null) {
      cleanupSelectionKey(data);
      cleanupConnection(data);
    }
  }

  protected static final class ClientProcessData {

    protected final SnappyTSocket clientSocket;
    protected final int connectionNumber;
    protected final TProcessor processor;
    protected final TTransport inputTransport;
    protected final TTransport outputTransport;
    protected final TProtocol inputProtocol;
    protected final TProtocol outputProtocol;
    protected final TServerEventHandler eventHandler;
    protected final ServerContext connectionContext;
    protected volatile SelectionKey key;
    protected volatile int remainingFrameSize;
    protected volatile boolean idle;

    protected ClientProcessData(SnappyTSocket socket, int connectionNumber,
        TProcessor proc, TTransport in, TTransport out, TProtocol inp,
        TProtocol outp, TServerEventHandler eventHandler) {
      this.clientSocket = socket;
      this.connectionNumber = connectionNumber;
      this.processor = proc;
      this.inputTransport = in;
      this.outputTransport = out;
      this.inputProtocol = inp;
      this.outputProtocol = outp;
      this.eventHandler = eventHandler;
      if (eventHandler != null) {
        this.connectionContext = eventHandler.createContext(inp, outp);
      } else {
        this.connectionContext = null;
      }
      this.idle = true;
    }

    protected void close() {
      this.clientSocket.close();
    }
  }

  protected ClientProcessData newClientProcessData(SnappyTSocket client) {
    TProcessor processor = processorFactory_.getProcessor(client);
    TProtocol inputProtocol = inputProtocolFactory_.getProtocol(client);
    TProtocol outputProtocol = outputProtocolFactory_.getProtocol(client);

    final int connectionNumber = connectionCounter.incrementAndGet();
    // register with ConnectionListener
    final ConnectionListener listener = connListener;
    if (listener != null) {
      listener.connectionOpened(client, processor, connectionNumber);
    }

    return new ClientProcessData(client, connectionNumber, processor, client,
        client, inputProtocol, outputProtocol, getEventHandler());
  }

  /**
   * Each selector process handles a single selector having a set of connections
   * to it. The selector itself is recycled among the threads in pool with the
   * current selector thread itself processing the request and handing over the
   * SelectorProcess to the pool for further handling by other available
   * threads. See {@link SnappyThriftServerSelector} class comments for details.
   */
  protected final class SelectorProcess implements Runnable {

    /**
     * Selector that this services.
     */
    private final Selector selector;
    private final ArrayList<ClientProcessData> pendingConnections;
    private final ArrayList<SelectionKey> selectedKeys;
    private volatile boolean stopped;
    private volatile boolean inExecution;
    private final Object sync;

    protected SelectorProcess(Selector selector) {
      this.selector = selector;
      this.pendingConnections = new ArrayList<>(4);
      this.selectedKeys = new ArrayList<>(4);
      this.sync = new Object();
    }

    /**
     * Select and process IO events appropriately: If there are existing
     * connections with data waiting to be read, read it, buffering until a
     * whole frame has been read. If there are any pending responses, buffer
     * them until their target client is available, and then send the data.
     */
    @Override
    public void run() {
      final ExecutorService executorService =
          SnappyThriftServerSelector.this.executorService;
      final ArrayList<SelectionKey> selectedKeys = this.selectedKeys;
      try {
        while (!this.stopped) {
          handlePendingConnections();
          if (selectedKeys.isEmpty()) {
            this.selector.select(1000L);
            final Set<SelectionKey> newSelectedKeys = this.selector
                .selectedKeys();
            if (newSelectedKeys.size() > 0) {
              selectedKeys.addAll(newSelectedKeys);
              newSelectedKeys.clear();
            } else {
              continue;
            }
          }

          final int numSelectedKeys = selectedKeys.size();
          if (numSelectedKeys == 0) {
            continue;
          }
          // process the io events we received
          ClientProcessData myData = null;
          // reverse iteration for better efficiency in remove
          ListIterator<SelectionKey> keysIter = selectedKeys.listIterator(
              numSelectedKeys);
          while (keysIter.hasPrevious()) {
            SelectionKey key = keysIter.previous();
            keysIter.remove();

            SnappyTSocket client;
            final ClientProcessData data = (ClientProcessData)key.attachment();
            // skip if not valid
            if (!key.isValid()) {
              closeConnection(data);
              continue;
            }
            final int readyOps = key.readyOps();
            if (this.stopped) {
              return;
            }
            if ((readyOps & SelectionKey.OP_READ) != 0) {
              // deal with reads and start inline processing if required
              if (data.idle) {
                client = data.clientSocket;
                // check if we have read large enough data (either full frame
                // or filled the buffer)
                int remainingFrameSize = data.remainingFrameSize;
                try {
                  if (remainingFrameSize == 0) {
                    remainingFrameSize = client.getInputStream()
                        .readFrame();
                  } else {
                    remainingFrameSize = client.getInputStream()
                        .readFrameFragment(remainingFrameSize);
                  }
                } catch (IOException ioe) {
                  if (client.isOpen()) {
                    LOGGER.trace("Got an IOException while reading frame",
                        ioe);
                  }
                  closeConnection(data);
                  continue;
                }
                if (remainingFrameSize == 0) {
                  if (myData == null) {
                    myData = data;
                  } else {
                    data.idle = false;
                    data.remainingFrameSize = 0;
                    // handover execution of completed frame to another thread
                    executorService.execute(new SelectorWorker(data));
                  }
                } else {
                  // we should get a new notification for this in selector
                  // when more data arrives for the frame so just skip it now
                  data.remainingFrameSize = remainingFrameSize;
                }
              } else {
                // unpark any waiting thread
                Thread parkedThread = data.clientSocket
                    .getInputStream().getParkedThread();
                if (parkedThread != null) {
                  LockSupport.unpark(parkedThread);
                } else if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                  if (!data.idle) {
                    parkedThread = data.clientSocket.getOutputStream()
                        .getParkedThread();
                    if (parkedThread != null) {
                      LockSupport.unpark(parkedThread);
                    }
                  }
                }
              }
            } else if ((readyOps & SelectionKey.OP_WRITE) != 0) {
              if (!data.idle) {
                final Thread parkedThread = data.clientSocket
                    .getOutputStream().getParkedThread();
                if (parkedThread != null) {
                  LockSupport.unpark(parkedThread);
                }
              }
            } else {
              LOGGER.warn("Unexpected state in select! " + key.interestOps());
            }
          }

          if (myData != null) {
            myData.idle = false;
            myData.remainingFrameSize = 0;
            // handover select to other selectors
            boolean inlineExecute = false;
            final AtomicInteger numSelectorsInExecute = numSelectorsInExecution;
            final int maxExecuteSelectors = numSelectors - 2;
            while (true) {
              final int numInExecution = numSelectorsInExecute.get();
              if (numInExecution >= maxExecuteSelectors) {
                break;
              }
              if (numSelectorsInExecute.compareAndSet(numInExecution,
                  numInExecution + 1)) {
                inlineExecute = true;
                break;
              }
            }
            if (!inlineExecute) {
              // handover execution to another thread
              executorService.execute(new SelectorWorker(myData));
              continue;
            }
            // execute in the current thread
            ArrayList<ClientProcessData> myClients = null;
            try {
              synchronized (this.sync) {
                this.inExecution = true;
                final Set<SelectionKey> keys = this.selector.keys();
                final int ksize = keys.size();
                if (ksize > 1) {
                  myClients = new ArrayList<>(ksize);
                  for (SelectionKey key : keys) {
                    if (!key.isValid()) {
                      continue;
                    }
                    ClientProcessData data = (ClientProcessData)key
                        .attachment();
                    myClients.add(data);
                    data.key = null;
                    key.cancel();
                  }
                }
                final int csize = this.pendingConnections.size();
                if (csize > 0) {
                  if (myClients == null) {
                    myClients = new ArrayList<>(csize);
                  }
                  for (int index = 0; index < csize; index++) {
                    myClients.add(this.pendingConnections.get(index));
                  }
                  this.pendingConnections.clear();
                }
              }
              if (myClients != null) {
                registerClientDataInNextSelector(myClients, this);
              }
              handleRead(myData, false, true);
            } finally {
              this.inExecution = false;
              numSelectorsInExecute.decrementAndGet();
            }
          }
        }
      } catch (ClosedChannelException | ClosedSelectorException ce) {
        Misc.checkIfCacheClosing(ce);
        this.stopped = true;
      } catch (IOException ioe) {
        Misc.checkIfCacheClosing(ioe);
        this.stopped = true;
        LOGGER.warn("Got an IOException while selecting!", ioe);
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error
            && SystemFailure.isJVMFailureError(err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable.
        SystemFailure.checkFailure();
        Misc.checkIfCacheClosing(t);
        this.stopped = true;
        if (!(t instanceof CancelException)) {
          LOGGER.error("SelectorProcess.run() exiting due to uncaught error",
              t);
        }
      } finally {
        if (this.stopped) {
          try {
            for (SelectionKey selectionKey : this.selector.keys()) {
              closeConnection((ClientProcessData)selectionKey.attachment());
            }
            this.selector.close();
          } catch (ClosedSelectorException cse) {
            // ignore
          } catch (IOException ioe) {
            LOGGER.error("SelectorProcess.run() error in selector close", ioe);
          }
        }
      }
    }

    void handlePendingConnections() throws IOException {
      synchronized (this.sync) {
        final int size = this.pendingConnections.size();
        if (size > 0) {
          ClientProcessData clientData = this.pendingConnections.get(0);
          // invoke a selectNow to remove any cancelled keys first
          this.selector.selectNow();
          final Set<SelectionKey> newSelectedKeys = this.selector
              .selectedKeys();
          if (newSelectedKeys.size() > 0) {
            this.selectedKeys.addAll(newSelectedKeys);
            newSelectedKeys.clear();
          }
          addNewClient(clientData);
          for (int index = 1; index < size; index++) {
            clientData = this.pendingConnections.get(index);
            addNewClient(clientData);
          }
          this.pendingConnections.clear();
        }
      }
    }

    protected void addNewClient(ClientProcessData clientData) {
      SelectionKey clientKey;
      try {
        // if client is already in execution then register OP_WRITE interest
        // too, else only OP_READ
        /*if (clientData.idle) {
          clientKey = clientData.clientSocket.registerSelector(this.selector,
              SelectionKey.OP_READ);
        }
        else*/ {
          clientKey = clientData.clientSocket.registerSelector(this.selector,
              SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }

        clientData.key = clientKey;
        clientKey.attach(clientData);
      } catch (ClosedChannelException cce) {
        cleanupSelectionKey(clientData);
      } catch (IOException ioe) {
        LOGGER.warn("Failed to register accepted connection to selector!",
            ioe);
        cleanupSelectionKey(clientData);
      }
    }

    /** sync on this should be held by caller */
    private boolean registerClient(ClientProcessData clientData) {
      if (!this.stopped) {
        this.pendingConnections.add(clientData);
        this.selector.wakeup();
        return true;
      } else {
        return false;
      }
    }

    protected void stop() {
      this.stopped = true;
      this.selector.wakeup();
    }
  }

  protected final class SelectorWorker implements Runnable {

    protected final ClientProcessData data;

    protected SelectorWorker(final ClientProcessData data) {
      this.data = data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      handleRead(this.data, false, true);
    }
  }

  protected final class ThreadWorker implements Runnable {

    protected final ClientProcessData data;

    protected ThreadWorker(final ClientProcessData data) {
      this.data = data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      // read and ignore framesize at the start
      while (handleRead(this.data, true, false))
        ;
    }
  }
}
