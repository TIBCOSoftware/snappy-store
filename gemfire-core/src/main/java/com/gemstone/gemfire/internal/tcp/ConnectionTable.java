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
package com.gemstone.gemfire.internal.tcp;

import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import java.io.*;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.SystemTimer;
import com.gemstone.gemfire.internal.concurrent.*;
import com.gemstone.gemfire.distributed.DistributedSystemDisconnectedException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.*;
import com.gemstone.gemfire.distributed.internal.membership.jgroup.JGroupMembershipManager;
import org.apache.commons.pool2.KeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/** <p>ConnectionTable holds all of the Connection objects in a conduit.
    Connections represent a pipe between two endpoints represented
    by generic Stubs.</p>

    @author Bruce Schuchardt
    @author Darrel Schneider
    @since 2.1
*/
/*
    Note: We no longer use InputMultiplexer
    If InputMux is reinstated then the manager needs to be
    initialized and all lines that have a NOMUX preface should be uncommented

*/
public final class ConnectionTable  {
  /** a random number generator for secondary connection selection */
  //static java.util.Random random = new java.util.Random();

  /** warning when descriptor limit reached */
  private static boolean ulimitWarningIssued;

  /**
   * true if the current thread wants non-shared resources
   */
  //private static ThreadLocal threadWantsOwnResources = new ThreadLocal();

  /**
   * Used for messages whose order must be preserved
   * Only connections used for sending messages,
   * and receiving acks, will be put in this map.
   */
  protected final Map orderedConnectionMap = CFactory.createCM();
  
  /**
   * ordered connections local to this thread.  Note that accesses to
   * the resulting map must be synchronized because of static cleanup.
   */
  // ThreadLocal<Map>
  private final ThreadLocal threadOrderedConnMap;
  
  /**
   * List of thread-owned ordered connection maps, for cleanup
   * 
   * Accesses to the maps in this list need to be synchronized on their instance.
   */
  private final List threadConnMaps;
  
  /**
   * Timer to kill idle threads
   * 
   * @guarded.By this
   */
  private SystemTimer idleConnTimer;
  
  /**
   * Used to find connections owned by threads.
   * The key is the same one used in threadOrderedConnMap.
   * The value is an ArrayList since we can have any number of connections
   * with the same key.
   */
  private CM threadConnectionMap;

  private static final class ConnKey {
    final Stub stub;
    final long startTime;
    final long ackTimeout;
    final long ackSATimeout;

    ConnKey(Stub stub) {
      this(stub, 0, 0, 0);
    }

    ConnKey(Stub stub, long startTime, long ackTimeout,
        long ackSATimeout) {
      this.stub = stub;
      this.startTime = startTime;
      this.ackTimeout = ackTimeout;
      this.ackSATimeout = ackSATimeout;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ConnKey && this.stub.equals(((ConnKey)obj).stub);
    }

    @Override
    public int hashCode() {
      return this.stub.hashCode();
    }

    @Override
    public String toString() {
      return this.stub.toString();
    }
  }

  /** The connection pool used for sending messages keyed by members. */
  private final QueryKeyedObjectPool<ConnKey, Connection> connectionPool;

  /**
   * Used for all non-ordered messages.
   * Only connections used for sending messages,
   * and receiving acks, will be put in this map.
   */
  protected final Map unorderedConnectionMap = CFactory.createCM();
  /**
   * Used for all accepted connections. These connections are read only;
   * we never send messages, except for acks; only receive.
   * 
   * Consists of a list of Connection
   */
  private final List receivers = new ArrayList();

  /**
   * the conduit for this table
   */
  protected final TCPConduit owner;
  // ARB: temp making this protected to provide access to Connection.
  //private final TCPConduit owner;
  
  /**
   * true if this table is no longer in use
   */
  private volatile boolean closed = false;

 
  private Executor connRWThreadPool;
  /**
   * The most recent instance to be created
   * 
   * TODO this assumes no more than one instance is created at a time?
   */
  private static final AR lastInstance = CFactory.createAR();
  
  /**
   * A set of sockets that are in the process of being connected
   */
  private final Map connectingSockets = new HashMap();

  private static ThreadLocal threadTSSFlags = new ThreadLocal();
  public final static int NO_MASK = 0x00;
  public final static int WANTS_OWN_RESOURCES_TSS_MASK = 0x01;
  public final static int WANTS_SHARED_RESOURCES_TSS_MASK = 0x02;
  public final static int RESOURCES_TSS_MASK =
      (WANTS_OWN_RESOURCES_TSS_MASK | WANTS_SHARED_RESOURCES_TSS_MASK);
  public final static int IS_READER_TSS_MASK = 0x04;
  public final static int SHOULD_DOMINO_TSS_MASK = 0x08;
  public final static int IS_SHARED_RESOURCE_TSS_MASK = 0x10;
  
  private final static long READER_POOL_KEEP_ALIVE_TIME = Long.getLong("p2p.READER_POOL_KEEP_ALIVE_TIME", 120).longValue();

  private final static void setTSSFlags(int flags) {
    final Integer v = (Integer)threadTSSFlags.get();
    if (v != null && v.intValue() != NO_MASK) {
      threadTSSFlags.set(Integer.valueOf(v.intValue() | flags));
    }
    else {
      threadTSSFlags.set(Integer.valueOf(flags));
    }
  }
  
  private final static void clearTSSFlags(int flags) {
    final Integer v = (Integer)threadTSSFlags.get();
    if (v != null) {
      threadTSSFlags.set(Integer.valueOf(v.intValue() & ~flags));
    }
  }

  public final static int threadTSSFlags() {
    Object o = threadTSSFlags.get();
    if (o == null) {
      return 0;
    }
    else {
      return ((Integer)o).intValue();
    }
  }

  private final static boolean isThreadTSSFlagSet(int flag) {
    Object o = threadTSSFlags.get();
    if (o == null) {
      return false;
    }
    else {
      return (((Integer)o).intValue() & flag) != 0;
    }
  }

  /**
   * mark this thread is a "reader" thread i.e. either a P2P reader thread or
   * one of the executor pool threads
   */
  public final static void makeReaderThread() {
    // mark this thread as a reader thread
    setTSSFlags(IS_READER_TSS_MASK);
  }
  
  public final static void makeSharedThread() {
    setTSSFlags(IS_SHARED_RESOURCE_TSS_MASK);
  }
  
  public final static boolean isSharedThread() {
    return isThreadTSSFlagSet(IS_SHARED_RESOURCE_TSS_MASK);
  }
  
  public final static void unsetTSSMask() {
    // mark this thread as a reader thread
    setTSSFlags(NO_MASK);
  }

  /**
   * return true if this thread is a "reader" thread i.e. either a P2P reader
   * thread or one of the executor pool threads
   */
  public final static boolean isReaderThread() {
    return isThreadTSSFlagSet(IS_READER_TSS_MASK);
  }

  /**
   * If true then readers for thread owned sockets will send all messages on
   * thread owned senders.
   * Even normally unordered msgs get send on TO socks.
   */
  private static final boolean DOMINO_THREAD_OWNED_SOCKETS =
      Boolean.getBoolean("p2p.ENABLE_DOMINO_THREAD_OWNED_SOCKETS");
  //private final static ThreadLocal isDominoThread = new ThreadLocal();
  // return true if this thread is a reader thread
  public final static boolean tipDomino() {
    if (DOMINO_THREAD_OWNED_SOCKETS) {
      // mark this thread as one who wants to send ALL on TO sockets
      ConnectionTable.threadWantsOwnResources();
      setTSSFlags(SHOULD_DOMINO_TSS_MASK);
      return true;
    } else {
      return false;
    }
  }
  public final static boolean isDominoThread() {
    return isThreadTSSFlagSet(SHOULD_DOMINO_TSS_MASK);
  }

  /**
   * Cause calling thread to share communication
   * resources with other threads.
   */
  public static void threadWantsSharedResources() {
    //threadWantsOwnResources.set(Boolean.FALSE);
    setTSSFlags(WANTS_SHARED_RESOURCES_TSS_MASK);
    clearTSSFlags(WANTS_OWN_RESOURCES_TSS_MASK);
  }
  /**
   * Cause calling thread to acquire exclusive access to
   * communication resources.
   * Exclusive access may not be available in which
   * case this call is ignored.
   */
  public static void threadWantsOwnResources() {
    //threadWantsOwnResources.set(Boolean.TRUE);
    setTSSFlags(WANTS_OWN_RESOURCES_TSS_MASK);
    clearTSSFlags(WANTS_SHARED_RESOURCES_TSS_MASK);
  }

  public static Boolean getThreadOwnsResourcesRegistration() {
    //return (Boolean)threadWantsOwnResources.get();
    final int flags = threadTSSFlags();
    Boolean result = null;
    if ((flags & ConnectionTable.RESOURCES_TSS_MASK) == 0) {
      // nothing set - return null to use default setting
    }
    else {
      // bug #47936 - check for thread-owned before shared.  ReaderThreads
      // set shared when created but then set thread-owned after a handshake
      if ((flags & ConnectionTable.WANTS_OWN_RESOURCES_TSS_MASK) != 0) {
        result = Boolean.TRUE;
      }
      else if ((flags & ConnectionTable.WANTS_SHARED_RESOURCES_TSS_MASK) != 0) {
        result = Boolean.FALSE;
      }
    }
    return result;
  }

  /*
//  public static void setThreadOwnsResourcesRegistration(
//      Boolean newValue) {
//    threadWantsOwnResources.set(newValue);
//  }
  */
  // private Map connections = new HashMap();
  /* NOMUX: private InputMuxManager inputMuxManager; */
  //private int lowWater;
  //private int highWater;

//   private static boolean TRACK_SERVER_CONNECTIONS =
//       System.getProperty("p2p.bidirectional", "true").equals("true");

  private ConnectionTable(TCPConduit c) throws IOException {
    this.owner = c;
    this.idleConnTimer = (this.owner.idleConnectionTimeout != 0) 
        ? new SystemTimer(c.getDM().getSystem(), true, getLogger())
        : null;
    this.threadOrderedConnMap = new ThreadLocal();
    this.threadConnMaps = new ArrayList();

    this.connRWThreadPool = createThreadPoolForIO(c.getDM().getSystem().isShareSockets()); 
    
  /*  NOMUX: if (TCPConduit.useNIO) {
      inputMuxManager = new InputMuxManager(this);
      inputMuxManager.start(c.getLogger());
    }*/

    if (!c.useNIOStream()) {
      this.threadConnectionMap = CFactory.createCM();
      this.connectionPool = null;
      return;
    }
    this.threadConnectionMap = null; // no thread-local maps with NIOStream
    // The factory for creating new Connections for connectionPool.
    KeyedPooledObjectFactory<ConnKey, Connection> connectionFactory;
    connectionFactory = new KeyedPooledObjectFactory<ConnKey, Connection>() {
      @Override
      public PooledObject<Connection> makeObject(ConnKey key) throws Exception {
        final TCPConduit owner = ConnectionTable.this.owner;
        Connection newConn = Connection.createSender(owner.getMembershipManager(),
            ConnectionTable.this, true /* preserveOrder */, key.stub,
            owner.getMemberForStub(key.stub, false), false /* shared */,
            true /* pooled */, key.startTime, key.ackTimeout, key.ackSATimeout);
        final LogWriterI18n logger = owner.getLogger();
        if (logger.fineEnabled()) {
          logger.fine("ConnectionTable: created a pooled ordered connection: " +
              newConn);
        }
        owner.stats.incSenders(false/* shared */, true /* preserveOrder */);
        return new DefaultPooledObject<>(newConn);
      }

      @Override
      public void destroyObject(ConnKey key,
          PooledObject<Connection> p) throws Exception {
        closeCon(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_BEING_DESTROYED
            .toLocalizedString(), p.getObject());
      }

      @Override
      public boolean validateObject(ConnKey key, PooledObject<Connection> p) {
        return p.getObject().connected;
      }

      @Override
      public void activateObject(ConnKey key,
          PooledObject<Connection> p) throws Exception {
      }

      @Override
      public void passivateObject(ConnKey key,
          PooledObject<Connection> p) throws Exception {
      }
    };
    this.connectionPool = new QueryKeyedObjectPool<>(connectionFactory,
        c.getCancelCriterion());
    final int numProcessors = Runtime.getRuntime().availableProcessors();
    final int numConnections = Math.max(DistributionManager.MAX_PR_THREADS_SET,
        // SNAP-1682
        Math.max(32, DistributionManager.MAX_PR_THREADS));
    this.connectionPool.setMaxTotalPerKey(numConnections);
    this.connectionPool.setMaxIdlePerKey(numConnections);
    this.connectionPool.setTestOnBorrow(true);
    this.connectionPool.setTestOnReturn(true);
    final int connectionTimeout = owner.idleConnectionTimeout;
    this.connectionPool.setTimeBetweenEvictionRunsMillis(connectionTimeout);
    // default idle-timeout for a connection is 5 minutes
    this.connectionPool.setMinEvictableIdleTimeMillis(connectionTimeout * 5);
  }
  
  private Executor createThreadPoolForIO(boolean conserveSockets) {
    Executor executor = null;
    final ThreadGroup connectionRWGroup = LogWriterImpl.createThreadGroup(
        "P2P Reader Threads", getLogger());
    if (conserveSockets) {
      executor = new Executor() {
        @Override
        public void execute(Runnable command) {
          Thread th = new Thread(connectionRWGroup, command);
          th.setDaemon(true);
          th.start();

        }
      };
    }
    else {

      BlockingQueue synchronousQueue = new SynchronousQueue();

      ThreadFactory tf = new ThreadFactory() {

        public Thread newThread(final Runnable command) {
          Thread thread = new Thread(connectionRWGroup, command);
          thread.setDaemon(true);
          return thread;
        }
      };
      executor = new ThreadPoolExecutor(1, Integer.MAX_VALUE, READER_POOL_KEEP_ALIVE_TIME,
          TimeUnit.SECONDS, synchronousQueue, tf);

    }
    return executor;
  }

  /** conduit sends connected() after establishing the server socket */
//   protected void connected() {
//   /*  NOMUX: if (TCPConduit.useNIO) {
//       inputMuxManager.connected();
//     }*/
//   }

  /** conduit calls acceptConnection after an accept */
  protected void acceptConnection(Socket sock) throws IOException, 
      ConnectionException {
    Connection connection = null;
    InetAddress connAddress = sock.getInetAddress(); // for bug 44736
    boolean finishedConnecting = false;
    Connection conn = null;
//    boolean exceptionLogged = false;
    try {
      conn = Connection.createReceiver(this, sock);

      // check for shutdown (so it doesn't get missed in the finally block)
      this.owner.getCancelCriterion().checkCancelInProgress(null);
      finishedConnecting = true;
    } catch (IOException ex) {
      // check for shutdown...
      this.owner.getCancelCriterion().checkCancelInProgress(ex);
      getLogger().warning(LocalizedStrings.ConnectionTable_FAILED_TO_ACCEPT_CONNECTION_FROM_0_BECAUSE_1,
                          new Object[] {(connAddress != null ? connAddress : "unavailable address"), ex});
      throw ex;
    } catch (ConnectionException ex) {
      // check for shutdown...
      this.owner.getCancelCriterion().checkCancelInProgress(ex);
      getLogger().warning(LocalizedStrings.ConnectionTable_FAILED_TO_ACCEPT_CONNECTION_FROM_0_BECAUSE_1,
                          new Object[] {(connAddress != null ? connAddress : "unavailable address"), ex});
      throw ex;
    } finally {
      // note: no need to call incFailedAccept here because it will be done
      // in our caller.
      // no need to log error here since caller will log warning
      
      if (conn != null && !finishedConnecting) {
        // we must be throwing from checkCancelInProgress so close the connection
        closeCon(LocalizedStrings.ConnectionTable_CANCEL_AFTER_ACCEPT.toLocalizedString(), conn);
        conn = null;
      }
    }
    
    //Stub id = conn.getRemoteId();
    if (conn != null) {
      synchronized (this.receivers) {
        this.owner.stats.incReceivers();
        if (this.closed) {
          closeCon(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_NO_LONGER_IN_USE.toLocalizedString(), conn);
          return;
        }
        this.receivers.add(conn);
      }
      if (getLogger().fineEnabled()) {
        String msg = "Accepted " + conn
          + " myAddr=" + getConduit().getLocalAddress()
          + " theirAddr=" + conn.remoteAddr;
        getLogger().fine(msg);
      }
    }
   // cleanupHighWater();
  }


//   /** returns the connection associated with the given key, or null if
//       no such connection exists */
//   protected Connection basicGet(Serializable id) {
//     synchronized (this.orderedConnectionMap) {
//       return (Connection) this.orderedConnectionMap.get(id);
//     }
//   }

//   protected Connection get(Serializable id) throws java.io.IOException {
//     return get(id, false);
//   }


  /**
   * Process a newly created PendingConnection
   * 
   * @param id Stub on which the connection is created
   * @param sharedResource whether the connection is used by multiple threads
   * @param preserveOrder whether to preserve order
   * @param m map to add the connection to
   * @param pc the PendingConnection to process
   * @param startTime the ms clock start time for the operation
   * @param ackThreshold the ms ack-wait-threshold, or zero
   * @param ackSAThreshold the ms ack-severe_alert-threshold, or zero
   * @return the Connection, or null if someone else already created or closed it
   * @throws IOException if unable to connect
   * @throws DistributedSystemDisconnectedException
   */
  private Connection handleNewPendingConnection(Stub id, boolean sharedResource,
      boolean preserveOrder,
      Map m, PendingConnection pc, long startTime, long ackThreshold, long ackSAThreshold)
      throws IOException, DistributedSystemDisconnectedException
  {
    // handle new pending connection
    Connection con = null;
    try {
      con = Connection.createSender(owner.getMembershipManager(), this, preserveOrder,
                                    id, this.owner.getMemberForStub(id, false),
                                    sharedResource, false /* pooled */,
                                    startTime, ackThreshold, ackSAThreshold);
      this.owner.stats.incSenders(sharedResource, preserveOrder);
    }
    finally {
      // our connection failed to notify anyone waiting for our pending con
      if (con == null) {
        this.owner.stats.incFailedConnect();
        synchronized (m) {
          // getLogger().info("DEBUG2: m.remove(" + id+ ")" + " m="+m);
          Object rmObj = m.remove(id);
          if (rmObj != pc && rmObj != null) {
            // put it back since it was not our pc
            m.put(id, rmObj);
          }
        }
        pc.notifyWaiters(null);
        // we must be throwing an exception
      }
    } // finally

    // Update our list of connections -- either the
    // orderedConnectionMap or unorderedConnectionMap
    //
    // Note that we added the entry _before_ we attempted the connect,
    // so it's possible something else got through in the mean time...
    synchronized (m) {
      Object e = m.get(id);
      if (e == pc) {
        // getLogger().info("DEBUG2: m.put(" + id+ ", " + con+")" + " m="+m);
        m.put(id, con);
      }
      else if (e == null) {
        // someone closed our pending connection
        // so cleanup the connection we created
        con.requestClose(LocalizedStrings.ConnectionTable_PENDING_CONNECTION_CANCELLED.toLocalizedString());
        con = null;
      }
      else {
        if (e instanceof Connection) {
          Connection newCon = (Connection)e;
          if (!newCon.connected) {
            // Fix for bug 31590
            // someone closed our pending connect
            // so cleanup the connection we created
            if (con != null) {
              con.requestClose(LocalizedStrings.ConnectionTable_PENDING_CONNECTION_CLOSED.toLocalizedString());
              con = null;
            }
          }
          else {
            // This should not happen. It means that someone else
            // created the connection which should only happen if
            // our Connection was rejected.
            // Assert.assertTrue(false);
            // The above assertion was commented out to try the
            // following with bug 32680
            if (con != null) {
              con.requestClose(LocalizedStrings.ConnectionTable_SOMEONE_ELSE_CREATED_THE_CONNECTION.toLocalizedString());
            }
            con = newCon;
          }
        }
      }
    }
    pc.notifyWaiters(con);
    if (con != null && getLogger().fineEnabled()) {
      String msg = "handleNewPendingConnection " + con + " myAddr="
          + getConduit().getLocalAddress() + " theirAddr=" + con.remoteAddr;
      getLogger().fine(msg);
    }

    return con;
  }

  /**
   * unordered or conserve-sockets
   * note that unordered connections are currently always shared
   * 
   * @param id the Stub on which we are creating a connection
   * @param threadOwnsResources whether unordered conn is owned by the current thread
   * @param preserveOrder whether to preserve order
   * @param startTime the ms clock start time for the operation
   * @param ackTimeout the ms ack-wait-threshold, or zero
   * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
   * @return the new Connection, or null if an error
   * @throws IOException if unable to create the connection
   * @throws DistributedSystemDisconnectedException
   */
  private Connection getUnorderedOrConserveSockets(Stub id, 
      boolean threadOwnsResources, boolean preserveOrder,
      long startTime, long ackTimeout, long ackSATimeout)
    throws IOException, DistributedSystemDisconnectedException
    {
    Connection result = null;
    
    final Map m = preserveOrder ? this.orderedConnectionMap 
        : this.unorderedConnectionMap;

    PendingConnection pc = null; // new connection, if needed
    Object mEntry = null; // existing connection (if we don't create a new one)
    
    // Look for pending connection
    synchronized (m) {
      mEntry = m.get(id);
      if (mEntry != null && (mEntry instanceof Connection)) {
        Connection existingCon = (Connection)mEntry;
        if (!existingCon.connected) {
          mEntry = null;
        }
      }
      if (mEntry == null) {
        pc = new PendingConnection(preserveOrder, id);
        //getLogger().info("DEBUG: m.put(" + id+ ", " + pc+")" + " m=" + m);
        m.put(id, pc);
      }
    } // synchronized
    
    if (pc != null) {
      result = handleNewPendingConnection(id, true /* fixes bug 43386 */, preserveOrder, m, pc,
                                          startTime, ackTimeout, ackSATimeout);
      if (!preserveOrder && threadOwnsResources) {
        // TODO we only schedule unordered shared cnxs for timeout
        // if we own sockets. This seems wrong. We should
        // be willing to time them out even if we don't own sockets.
        scheduleIdleTimeout(result);
      }
    } else {  // we have existing connection
      if (mEntry instanceof PendingConnection) {
        result = ((PendingConnection)mEntry).waitForConnect(
            this.owner.getMembershipManager(), startTime,
            ackTimeout, ackSATimeout);
        if (getLogger().fineEnabled()) {
          if (result != null) {
            String msg = "getUnorderedOrConserveSockets " + result
              + " myAddr=" + getConduit().getLocalAddress()
              + " theirAddr=" + result.remoteAddr;
            getLogger().fine(msg);
          } else {
            getLogger().fine("getUnorderedOrConserveSockets: Connect failed");
          }
        }
      } else {
        result = (Connection)mEntry;
      }
    } // we have existing connection
      
    return result;
    }

  private void checkClosing() {
    owner.getCancelCriterion().checkCancelInProgress(null);
    if (this.closed) {
      throw new DistributedSystemDisconnectedException(LocalizedStrings
          .ConnectionTable_CONNECTION_TABLE_IS_CLOSED.toLocalizedString());
    }
  }

  /**
   * Must be looking for an ordered connection that this thread owns
   * 
   * @param id stub on which to create the connection
   * @param startTime the ms clock start time for the operation
   * @param ackTimeout the ms ack-wait-threshold, or zero
   * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
   * @return the connection, or null if an error
   * @throws IOException if the connection could not be created
   * @throws DistributedSystemDisconnectedException
   */
  Connection getOrderedAndOwned(Stub id, long startTime, long ackTimeout, long ackSATimeout) 
      throws IOException, DistributedSystemDisconnectedException  {
    if (this.connectionPool != null) {
      try {
        return this.connectionPool.borrowObject(
            new ConnKey(id, startTime, ackTimeout, ackSATimeout));
      } catch (IOException | RuntimeException e) {
        checkClosing();
        throw e;
      } catch (Exception e) {
        checkClosing();
        throw new IllegalStateException(e);
      }
    }

    Connection result = null;
    
    // Look for result in the thread local
    Map m = (Map)this.threadOrderedConnMap.get();
    if (m == null) {
      // First time for this thread.  Create thread local
      m = new HashMap();
      synchronized (this.threadConnMaps) {
        if (this.closed) {
          owner.getCancelCriterion().checkCancelInProgress(null);
          throw new DistributedSystemDisconnectedException(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_IS_CLOSED.toLocalizedString());
        }
        // check for stale references and remove them.
        for (Iterator it=this.threadConnMaps.iterator(); it.hasNext();) {
          Reference r = (Reference)it.next();
          if (r.get() == null) {
            it.remove();
          }
        } // for
        this.threadConnMaps.add(new WeakReference(m)); // ref added for bug 38011
      } // synchronized
      this.threadOrderedConnMap.set(m);
    } else {
      // Consult thread local.
      synchronized (m) {
        result = (Connection)m.get(id);
      }
      if (result != null && result.timedOut) {
        result = null;
      }
    }
    if (result != null)
      return result;
    
    // OK, we have to create a new connection.
    result = Connection.createSender(owner.getMembershipManager(), 
        this, true /* preserveOrder */, id,
        this.owner.getMemberForStub(id, false), false /* shared */,
        false /* pooled */, startTime, ackTimeout, ackSATimeout);
    if (getLogger().fineEnabled()) {
      getLogger().fine("ConnectionTable: created an ordered connection:"+result);
    }
    this.owner.stats.incSenders(false/*shared*/, true /* preserveOrder */);
    
    // Update the list of connections owned by this thread....
    
    if (this.threadConnectionMap == null) {
      // This instance is being destroyed; fail the operation
      closeCon(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_BEING_DESTROYED.toLocalizedString(), result);
      return null;
    }
    
    ArrayList al = (ArrayList)this.threadConnectionMap.get(id);
    if (al == null) {
      // First connection for this Stub.  Make sure list for this
      // stub is created if it isn't already there.
      al = new ArrayList();
      
      // Since it's a concurrent map, we just try to put it and then
      // return whichever we got.
      Object o = this.threadConnectionMap.putIfAbsent(id, al);
      if (o != null) {
        al = (ArrayList)o;
      }
    }
    
    // Add our Connection to the list
    synchronized (al) {
      al.add(result);
    }
    
    // Finally, add the connection to our thread local map.
    synchronized (m) {
      m.put(id, result);
    }
    
    scheduleIdleTimeout(result);
    return result;
  }

  public final void releasePooledConnection(Connection conn) {
    if (conn.isPooled() && this.connectionPool != null) {
      try {
        this.connectionPool.returnObject(new ConnKey(conn.remoteId), conn);
      } catch (RuntimeException re) {
        // log any exception in returning to pool and move on
        getLogger().warning(LocalizedStrings.DEBUG,
            "Unexpected exception in returning connection to pool " + re);
      }
    }
  }

  /** schedule an idle-connection timeout task */
  private void scheduleIdleTimeout(Connection conn) {
    if (conn == null) {
      // fix for bug 43529
      return;
    }
    // Set the idle timeout
    if (this.owner.idleConnectionTimeout != 0) {
      try {
        synchronized(this) {
          if (!this.closed) {
            IdleConnTT task = new IdleConnTT(conn);
            conn.setIdleTimeoutTask(task);
            this.getIdleConnTimer().scheduleAtFixedRate(task, 
              this.owner.idleConnectionTimeout, this.owner.idleConnectionTimeout);
          }
        }
      }
      catch (IllegalStateException e) {
        if (conn.isClosing()) {
          // bug #45077 - connection is closed before we schedule the timeout task,
          // causing the task to be canceled
          return;
        }
        getLogger().fine("Got an illegal state exception", e);
        // Unfortunately, cancelInProgress() is not set until *after*
        // the shutdown message has been sent, so we need to check the
        // "closeInProgress" bit instead.
        owner.getCancelCriterion().checkCancelInProgress(null);
//        getLogger().severe("why is DM still running: " + this.owner.getDM());
        Throwable cause = owner.getShutdownCause();
        if (cause == null) {
          cause = e;
        }
        throw new DistributedSystemDisconnectedException(
          LocalizedStrings.ConnectionTable_THE_DISTRIBUTED_SYSTEM_IS_SHUTTING_DOWN.toLocalizedString(),
          cause);
      }
    }
  }

  /**
   * Get a new connection
   * @param id the Stub on which to create the connection
   * @param preserveOrder whether order should be preserved
   * @param startTime the ms clock start time
   * @param ackTimeout the ms ack-wait-threshold, or zero
   * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
   * @return the new Connection, or null if a problem
   * @throws java.io.IOException if the connection could not be created
   * @throws DistributedSystemDisconnectedException
   */
  protected Connection get(Stub id, boolean preserveOrder, long startTime,
      long ackTimeout, long ackSATimeout, boolean threadOwnsResources)
      throws java.io.IOException, DistributedSystemDisconnectedException {
    if (this.closed) {
      this.owner.getCancelCriterion().checkCancelInProgress(null);
      throw new DistributedSystemDisconnectedException(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_IS_CLOSED.toLocalizedString());
    }
    Connection result = null;
    if (!preserveOrder || !threadOwnsResources) {
      result = getUnorderedOrConserveSockets(id, threadOwnsResources, preserveOrder, startTime, ackTimeout, ackSATimeout);
    } else {
      result = getOrderedAndOwned(id, startTime, ackTimeout, ackSATimeout);
    }
    if (result != null) {
      Assert.assertTrue(result.preserveOrder == preserveOrder);
    }
    return result;
  }

  protected synchronized void fileDescriptorsExhausted() {
    if (!ulimitWarningIssued) {
      ulimitWarningIssued = true;
      owner.getLogger().severe(LocalizedStrings.ConnectionTable_OUT_OF_FILE_DESCRIPTORS_USING_SHARED_CONNECTION);
      InternalDistributedSystem.getAnyInstance().setShareSockets(true);
      int flags = threadTSSFlags();
      flags &= ~RESOURCES_TSS_MASK;
      threadTSSFlags = new ThreadLocal();
      if (flags != 0) {
        setTSSFlags(flags);
      }
    }
  }

  protected final TCPConduit getConduit() {
    return owner;
  }

  protected final LogWriterI18n getLogger() {
    return owner.getLogger();
  }

  public boolean isClosed() {
    return this.closed;
  }

  private static void closeCon(String reason, Object c) {
    closeCon(reason, c, false);
  }

  private static void closeCon(String reason, Object c, boolean beingSick) {
    if (c == null) {
      return;
    }
    if (c instanceof Connection) {
      ((Connection)c).closePartialConnect(reason, beingSick); // fix for bug 31666
    } else {
      ((PendingConnection)c).notifyWaiters(null);
    }
  }

  /**
   * returns the idle connection timer, or null if the connection table is closed.
   * guarded by a sync on the connection table
   */
  protected synchronized SystemTimer getIdleConnTimer() {
    if (this.closed) {
      return null;
    }
    if (this.idleConnTimer == null) {
      this.idleConnTimer = new SystemTimer(getDM().getSystem(), true, 
          getLogger());
    }
    return this.idleConnTimer;    
  }
  
  protected void close() {
   /* NOMUX if (inputMuxManager != null) {
      inputMuxManager.stop();
    }*/
    if (this.closed) {
      return;
    }
    this.closed = true;
    synchronized (this) {
      if (this.idleConnTimer != null) {
        this.idleConnTimer.cancel();
      }
    }
    synchronized (this.orderedConnectionMap) {
      for (Iterator it=this.orderedConnectionMap.values().iterator(); it.hasNext(); ) {
        closeCon(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_BEING_DESTROYED.toLocalizedString(), it.next());
      }
      this.orderedConnectionMap.clear();
    }
    synchronized (this.unorderedConnectionMap) {
      for (Iterator it=this.unorderedConnectionMap.values().iterator(); it.hasNext(); ) {
        closeCon(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_BEING_DESTROYED.toLocalizedString(), it.next());
      }
      this.unorderedConnectionMap.clear();
    }
    if (this.threadConnectionMap != null) {
      this.threadConnectionMap = null;
    }
    if (this.threadConnMaps != null) {
      synchronized (this.threadConnMaps) {
        for (Iterator it=this.threadConnMaps.iterator(); it.hasNext();) {
          Reference r = (Reference)it.next();
          Map m = (Map)r.get();
          if (m != null) {
            synchronized (m) {
              for (Iterator mit=m.values().iterator(); mit.hasNext(); ) {
                closeCon(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_BEING_DESTROYED.toLocalizedString(), mit.next());
              }
            }
          }
        }
        this.threadConnMaps.clear();
      }
    }
    Executor localExec = this.connRWThreadPool;
    if(localExec != null) {
      if(localExec instanceof ExecutorService) {
        ((ExecutorService)localExec).shutdown();  
      }
      
      this.connRWThreadPool = null;      
    }
    
    closeReceivers(false);
    
    
    Map m = (Map)this.threadOrderedConnMap.get();
    if(m != null)
    {
      synchronized (m) {
        m.clear();
      }        
    }
    if (this.connectionPool != null) {
      this.connectionPool.close();
    }
  }

  public void executeCommand(Runnable runnable) {
    Executor local = this.connRWThreadPool;
    if(local != null) {
      local.execute(runnable);
    }
  }
  /**
   * Close all receiving threads.  This is used during shutdown and is also
   * used by a test hook that makes us deaf to incoming messages.
   * @param beingSick a test hook to simulate a sick process
   */
  protected void closeReceivers(boolean beingSick) {
    synchronized (this.receivers) {
      for (Iterator it=this.receivers.iterator(); it.hasNext();) {
        Connection con = (Connection)it.next();
        if (!beingSick || con.preserveOrder) {
          closeCon(LocalizedStrings.ConnectionTable_CONNECTION_TABLE_BEING_DESTROYED.toLocalizedString(), con, beingSick);
          it.remove();
        }
      }
      // now close any sockets being formed
      SocketCreator.getDefaultInstance();
      synchronized(connectingSockets) {
        for (Iterator it = connectingSockets.entrySet().iterator(); it.hasNext(); ) {
          Map.Entry entry = (Map.Entry)it.next();
//          ConnectingSocketInfo info = (ConnectingSocketInfo)entry.getValue();
          try {
            ((Socket)entry.getKey()).close();
          } catch (IOException e) {
            // ignored - we're shutting down
          }
          it.remove();
        }
      }
    }
  }
  
  
  protected void removeReceiver(Object con) {
    synchronized (this.receivers) {
      this.receivers.remove(con);
    }
  }

  /**
   * Return true if our owner already knows that this endpoint is departing 
   */
  protected boolean isEndpointShuttingDown(Stub stub) {
    return this.owner.getMemberForStub(stub, true) == null;
  }
  
  /** remove an endpoint and notify the membership manager of the departure */
  protected void removeEndpoint(Stub stub, String reason) {
    removeEndpoint(stub, reason, true);
  }

  protected void removeEndpoint(Stub stub, String reason, boolean notifyDisconnect) {
    if (this.closed) {
      return;
    }
    boolean needsRemoval = false;
    synchronized (this.orderedConnectionMap) {
      if (this.orderedConnectionMap.get(stub) != null)
        needsRemoval = true;
    }
    if (!needsRemoval) {
      synchronized (this.unorderedConnectionMap) {
        if (this.unorderedConnectionMap.get(stub) != null)
          needsRemoval = true;
      }
    }
    if (!needsRemoval) {
      CM cm = this.threadConnectionMap;
      if (cm != null) {
        ArrayList al = (ArrayList)cm.get(stub);
        needsRemoval = al != null && al.size() > 0;
      }
    }
    ConnKey connKey = null;
    if (this.connectionPool != null) {
      connKey = new ConnKey(stub);
      needsRemoval = this.connectionPool.getNumTotal(connKey) > 0;
    }

    if (needsRemoval) {
      synchronized (this.orderedConnectionMap) {
           closeCon(reason, this.orderedConnectionMap.remove(stub));
      }
      synchronized (this.unorderedConnectionMap) {
         closeCon(reason, this.unorderedConnectionMap.remove(stub));
      }

      {
        CM cm = this.threadConnectionMap;
        if (cm != null) {
          ArrayList al = (ArrayList)cm.remove(stub);
          if (al != null) {
            synchronized (al) {
              for (Iterator it=al.iterator(); it.hasNext();)
                closeCon(reason, it.next());
              al.clear();
            }
          }
        }
      }
      // close all connections for the given stub
      if (this.connectionPool != null) {
        this.connectionPool.clear(connKey);
        this.connectionPool.foreachObject(connKey, c -> {
          closeCon(reason, c);
          return true;
        });
      }

      // close any sockets that are in the process of being connected
      Set toRemove = new HashSet();
      synchronized(connectingSockets) {
        for (Iterator it=connectingSockets.entrySet().iterator(); it.hasNext(); ) {
          Map.Entry entry = (Map.Entry)it.next();
          ConnectingSocketInfo info = (ConnectingSocketInfo)entry.getValue();
          if (info.peerAddress.equals(stub.getInetAddress())) {
            toRemove.add(entry.getKey());
            it.remove();
          }
        }
      }
      for (Iterator it=toRemove.iterator(); it.hasNext(); ) {
        Socket sock = (Socket)it.next();
        try {
          sock.close();
        }
        catch (IOException e) {
          if (getLogger().fineEnabled()) {
            getLogger().fine("caught exception while trying to close connecting socket for " + stub, e);
          }
        }
      }

      // close any receivers
      // avoid deadlock when a NIC has failed by closing connections outside
      // of the receivers sync (bug 38731)
      toRemove.clear();
      synchronized (this.receivers) {
        for (Iterator it=receivers.iterator(); it.hasNext();) {
          Connection con = (Connection)it.next();
          if (stub.equals(con.getRemoteId())) {
            it.remove();
            toRemove.add(con);
          }
        }
      }
      for (Iterator it=toRemove.iterator(); it.hasNext(); ) {
        Connection con = (Connection)it.next();
        closeCon(reason, con);
      }
      // call memberDeparted after doing the closeCon calls
      // so it can recursively call removeEndpoint
      if (notifyDisconnect) {
        owner.getMemberForStub(stub, false);
      }
    }
  }
  
  /** check to see if there are still any receiver threads for the given end-point */
  protected boolean hasReceiversFor(Stub endPoint) {
    synchronized (this.receivers) {
      for (Iterator it=receivers.iterator(); it.hasNext();) {
        Connection con = (Connection)it.next();
        if (endPoint.equals(con.getRemoteId())) {
          return true;
        }
      }
    }
    return false;
  }
  
  private static void removeFromThreadConMap(CM cm, Stub stub, Connection c) {
    if (cm != null) {
      ArrayList al = (ArrayList)cm.get(stub);
      if (al != null) {
        synchronized (al) {
          al.remove(c);
        }
      }
    }
  }
  protected void removeThreadConnection(Stub stub, Connection c) {
    /*if (this.closed) {
      return;
    }*/
    // no thread-locals when using NIOStream connection pooling
    if (this.connectionPool != null) return;
    removeFromThreadConMap(this.threadConnectionMap, stub, c);
    Map m = (Map)this.threadOrderedConnMap.get();
    if (m != null) {
      // Static cleanup thread might intervene, so we MUST synchronize
      synchronized (m) {
        if (m.get(stub) == c) {
          m.remove(stub);
        }
      } // synchronized
    } // m != null
  }
  void removeSharedConnection(String reason, Stub stub, boolean ordered, Connection c) {
    if (this.closed) {
      return;
    }
    if (ordered) {
      synchronized (this.orderedConnectionMap) {
        if (this.orderedConnectionMap.get(stub) == c) {
          closeCon(reason, this.orderedConnectionMap.remove(stub));
        }
      }
    } else {
      synchronized (this.unorderedConnectionMap) {
        if (this.unorderedConnectionMap.get(stub) == c) {
          closeCon(reason, this.unorderedConnectionMap.remove(stub));
        }
      }
    }
  }

  /**
   * Just ensure that this class gets loaded.
   * 
   * @see SystemFailure#loadEmergencyClasses()
   */
  public static void loadEmergencyClasses() {
    // don't go any further, Frodo!
  }
  
  /**
   * Clears lastInstance.  Does not yet close underlying sockets, but
   * probably not strictly necessary.
   * 
   * @see SystemFailure#emergencyClose()
   */
  public static void emergencyClose() {
    ConnectionTable ct = (ConnectionTable) lastInstance.get();
    if (ct == null) {
      return;
    }
    // ct.close(); // TODO implementing this is a quagmire, but not necessary,
    //                since recusing from JGroups takes care of our obligations
    //                to our peers.
    lastInstance.set(null);
  }
  
  public void removeAndCloseThreadOwnedSockets() {
    // no thread-locals when using NIOStream connection pooling
    if (this.connectionPool != null) return;
    Map m = (Map) this.threadOrderedConnMap.get();
    if (m != null) {
      // Static cleanup may intervene; we MUST synchronize.
     synchronized (m) {
       Iterator it = m.entrySet().iterator();
       while (it.hasNext()) {
         Map.Entry me = (Map.Entry)it.next();
         Stub stub = (Stub)me.getKey();
         Connection c = (Connection)me.getValue();
         removeFromThreadConMap(this.threadConnectionMap, stub, c);
         it.remove();
         closeCon(LocalizedStrings.ConnectionTable_THREAD_FINALIZATION.toLocalizedString(), c);
       } // while
     } // synchronized m
    }
  }

  public static void releaseThreadsSockets() {
    // clear TXContext from global list
    TXManagerImpl.TXContext context = TXManagerImpl.currentTXContext();
    if (context != null) {
      context.threadClose();
    }
    ConnectionTable ct = (ConnectionTable) lastInstance.get();
    if (ct == null) {
      return;
    }
    ct.removeAndCloseThreadOwnedSockets();
//    lastInstance = null; 
  }

  /**
   * records the current outgoing message count on all thread-owned
   * ordered connections.  This does not synchronize or stop new connections
   * from being formed or new messages from being sent
   * @since 5.1
   */
  protected void getThreadOwnedOrderedConnectionState(Stub member,
      HashMap result) {

    if (this.connectionPool != null) {
      this.connectionPool.foreachObject(new ConnKey(member), c -> {
        final Connection conn = (Connection)c;
        if (!conn.isSharedResource() && conn.getOriginatedHere()
            && conn.getPreserveOrder()) {
          result.put(conn.getUniqueId(), conn.getMessagesSent());
        }
        return true;
      });
      return;
    }
    CM cm = this.threadConnectionMap;
    if (cm != null) {
      ArrayList al = (ArrayList)cm.get(member);
      if (al != null) {
        synchronized(al) {
          al = new ArrayList(al);
        }
  
        for (Iterator it=al.iterator(); it.hasNext(); ) {
          Connection conn = (Connection)it.next();
          if (!conn.isSharedResource() && conn.getOriginatedHere() 
              && conn.getPreserveOrder()) {
            result.put(Long.valueOf(conn.getUniqueId()), Long.valueOf(conn.getMessagesSent()));
          }
        }
      }
    }
  }
  
  /**
   * wait for the given incoming connections to receive at least the associated
   * number of messages
   */
  protected void waitForThreadOwnedOrderedConnectionState(Stub member,
      HashMap connectionStates) throws InterruptedException {
    if (Thread.interrupted()) throw new InterruptedException(); // wisest to do this before the synchronize below
    List r = null;
    synchronized(receivers) {
      r = new ArrayList(receivers);
    }
    final LogWriterI18n logger = owner.getLogger();
    for (Iterator it=r.iterator(); it.hasNext();) {
      Connection con = (Connection)it.next();
      if (!con.stopped && !con.isClosing() && !con.getOriginatedHere() && con.getPreserveOrder()
          && member.equals(con.getRemoteId())) {
        Long state = (Long)connectionStates.remove(Long.valueOf(con.getUniqueId()));
        if (state != null) {
          long count = state.longValue();
          while (!con.stopped && !con.isClosing() && con.getMessagesReceived() < count) {
            if (DistributionManager.VERBOSE || logger.fineEnabled()) {
              logger.info(LocalizedStrings.DEBUG, "Waiting for connection " 
                  + con.getRemoteId() + "/" + con.getUniqueId() 
                  + " currently=" + con.getMessagesReceived() 
                  + " need=" + count);
            }
            Thread.sleep(100);
          }
        }
      }
    }
    if (connectionStates.size() > 0) {
      if (DistributionManager.VERBOSE || logger.fineEnabled()) {
        StringBuilder sb = new StringBuilder(1000);
        sb.append("These connections from ");
        sb.append(member);
        sb.append("could not be located during waitForThreadOwnedOrderedConnectionState: ");
        for (Iterator it=connectionStates.entrySet().iterator();
            it.hasNext(); ) {
          Map.Entry entry = (Map.Entry)it.next();
          sb.append(entry.getKey())
              .append('(')
              .append(entry.getValue())
              .append(')');
          if (it.hasNext()) {
            sb.append(',');
          }
        }
        logger.info(LocalizedStrings.DEBUG, sb.toString());
      }
    }
  }

  protected DM getDM() {
    return this.owner.getDM();
  }

//  public boolean isShuttingDown() {
//    return this.owner.isShuttingDown();
//  }

  //protected void cleanupHighWater() {
  //  cleanup(highWater);
  //}

  //protected void cleanupLowWater() {
 //   cleanup(lowWater);
  //}

  //private void cleanup(int maxConnections) {
  /*  if (maxConnections == 0 || maxConnections >= connections.size()) {
      return;
    }
    while (connections.size() > maxConnections) {
      Connection oldest = null;
      synchronized(connections) {
        for (Iterator iter = connections.values().iterator(); iter.hasNext(); ) {
          Connection c = (Connection)iter.next();
          if (oldest == null || c.getTimeStamp() < oldest.getTimeStamp()) {
            oldest = c;
          }
        }
      }
      // sanity check - don't close anything fresher than 10 seconds or
      // we'll start thrashing
      if (oldest.getTimeStamp() > (System.currentTimeMillis() - 10000)) {
        if (owner.lowWaterConnectionCount > 0) {
          owner.lowWaterConnectionCount += 10;
        }
        if (owner.highWaterConnectionCount > 0) {
          owner.highWaterConnectionCount += 10;
        }
        owner.getLogger().warning(LocalizedStrings.ConnectionTable_P2P_CONNECTION_TABLE_MAY_BE_THRASHING_INCREASING_WATER_LEVELS_TO_0_1.toLocalizedString(
          new Object[] {
            owner.lowWaterConnectionCount,
            owner.highWaterConnectionCount
          });
        break;
      }
      if (oldest != null) {
        oldest.close();
      }
    }*/
  //}

  /*
  public void dumpConnectionTable() {
    getLogger().info(LocalizedStrings.ConnectionTable_P2P_CONNECTION_TABLE_CONTENTS);
    Iterator iter = connectionMap.keySet().iterator();
    while (iter.hasNext()) {
      Object key = iter.next();
      Object val = connectionMap.get(key);
      getLogger().info(LocalizedStrings.ConnectionTable_KEY_0___VALUE_HASH_1__DESCR_2, new Object[] {key, val.hashCode(), val});
    }
  }
  */
  private /*static*/ class PendingConnection {
    /**
     * true if this connection is still pending
     */
    private boolean pending = true;
    
    /**
     * the connection we are waiting on
     */
    private Connection conn = null;
    
    /**
     * whether the connection preserves message ordering
     */
    private final boolean preserveOrder;
    
    /**
     * the stub we are connecting to
     */
    private final Stub id;
    
    private final Thread connectingThread;
    
    public PendingConnection(boolean preserveOrder, Stub id) {
      this.preserveOrder = preserveOrder;
      this.id = id;
      this.connectingThread = Thread.currentThread();
    }
    
    /**
     * Synchronously set the connection and notify waiters that we are ready.
     * 
     * @param c the new connection
     */
    public synchronized void notifyWaiters(Connection c) {
      if (!this.pending)
        return; // already done.

      this.conn = c;
      this.pending = false;
      if (getLogger().fineEnabled()) {
        getLogger().fine("Notifying waiters that pending "
                         + ((this.preserveOrder) ? "ordered" : "unordered")
                         + " connection  to " + this.id
                         + " is ready "
                         + this);
      }
      this.notifyAll();
    }
    
    /**
     * Wait for a connection
     * @param mgr the membership manager that can instigate suspect processing if necessary
     * @param startTime the ms clock start time for the operation
     * @param ackTimeout the ms ack-wait-threshold, or zero
     * @param ackSATimeout the ms ack-severe-alert-threshold, or zero
     * @return the new connection
     * @throws IOException 
     */
    public synchronized Connection waitForConnect(MembershipManager mgr,
        long startTime, long ackTimeout, long ackSATimeout) throws IOException
    {
      if(connectingThread == Thread.currentThread()) {
        throw new ReenteredConnectException("This thread is already trying to connect");
      }
      
      final Map m = this.preserveOrder ? orderedConnectionMap
          : unorderedConnectionMap;

      boolean severeAlertIssued = false;
      boolean suspected = false;
      InternalDistributedMember targetMember = null;
      if (ackSATimeout > 0) {
        targetMember =
          ((JGroupMembershipManager)mgr).getMemberForStub(this.id, false);
      }

      for (;;) {
        if (!this.pending) break;
        getConduit().getCancelCriterion().checkCancelInProgress(null);
        
        // wait a little bit...
        boolean interrupted = Thread.interrupted();
        try {
          this.wait(100); // spurious wakeup ok
        }
        catch (InterruptedException ignore) {
          interrupted = true;
          getConduit().getCancelCriterion().checkCancelInProgress(ignore);
        }
        finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }

        if (!this.pending) break;

        // Still pending...
        long now = System.currentTimeMillis();
        if (!severeAlertIssued && ackSATimeout > 0  &&  startTime + ackTimeout < now) {
          if (startTime + ackTimeout + ackSATimeout < now) {
            getLogger().severe( LocalizedStrings.
                ConnectionTable_UNABLE_TO_FORM_A_TCPIP_CONNECTION_TO_0_IN_OVER_1_SECONDS,
                new Object[] { targetMember, (ackSATimeout+ackTimeout)/1000 });
            //t.getLogger().severe("DEBUG: startTime="+startTime + ", ackTimeout=" + ackTimeout + ", ackSATimeout=" + ackSATimeout + ", now="+now);
            severeAlertIssued = true;
          }
          else if (!suspected) {
            getLogger().warning(LocalizedStrings.
                ConnectionTable_UNABLE_TO_FORM_A_TCPIP_CONNECTION_TO_0_IN_OVER_1_SECONDS,
                new Object[] { this.id, (ackTimeout)/1000 });
            ((JGroupMembershipManager)mgr).suspectMember(targetMember,
                "Unable to form a TCP/IP connection in a reasonable amount of time");
            suspected = true;
          }
        }
        
        Object e;
        //synchronized (m) {
          e = m.get(this.id);
        //}
        if (e == this) {
          if (getLogger().fineEnabled()) {
            getLogger().fine(
                "Waiting for pending connection to complete: "
                    + ((this.preserveOrder) ? "ordered" : "unordered")
                    + " connection  to " + this.id + " " + this);
          }
          continue;          
        }
        
        // Odd state change. Process and exit.
        if (getLogger().fineEnabled()) {
          getLogger().fine(
              "Pending connection changed to " + e + " unexpectedly");
        }
        
        if (e == null) {
          // We were removed
          notifyWaiters(null);
          break;
        }
        else if (e instanceof Connection) {
          notifyWaiters((Connection)e);
          break;
        }
        else {
          // defer to the new instance
          return ((PendingConnection)e).waitForConnect(mgr, startTime,
              ackTimeout, ackSATimeout);
        }
      
      } // for
      return this.conn;

    }
  }
  

  private static class IdleConnTT extends SystemTimer.SystemTimerTask {
    
    private Connection c;
    IdleConnTT(Connection c) {
      this.c = c;
    }
    @Override
    public boolean cancel() {
      this.c = null;
      return super.cancel();
    }
    
    @Override
    public LogWriterI18n getLoggerI18n() {
      return c.getLogger();
    }
    @Override
    public void run2() {
      Connection con = this.c;
      if (con != null) {
        if (con.checkForIdleTimeout()) {
          cancel();
        }
      }
    }
  }

  public static ConnectionTable create(TCPConduit conduit) throws IOException {
    ConnectionTable ct = new ConnectionTable(conduit);
    lastInstance.set(ct);  
    return ct;
  }

  /** keep track of a socket that is trying to connect() for shutdown purposes */
  public void addConnectingSocket(Socket socket, InetAddress addr) {
    synchronized(connectingSockets) {
      connectingSockets.put(socket, new ConnectingSocketInfo(addr));
    }
  }

  /** remove a socket from the tracked set.  It should be connected at this point */
  public void removeConnectingSocket(Socket socket) {
    synchronized(connectingSockets) {
      connectingSockets.remove(socket);
    }
  }
  
  
  private static class ConnectingSocketInfo {
    InetAddress peerAddress;
    Thread connectingThread;
    public ConnectingSocketInfo(InetAddress addr) {
      this.peerAddress = addr;
      this.connectingThread = Thread.currentThread();
    }
  }


}
