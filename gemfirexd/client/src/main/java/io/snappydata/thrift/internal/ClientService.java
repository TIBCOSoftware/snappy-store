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

package io.snappydata.thrift.internal;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.client.LogHandler;
import com.pivotal.gemfirexd.internal.client.am.LogWriter;
import com.pivotal.gemfirexd.internal.client.am.SqlException;
import com.pivotal.gemfirexd.internal.client.net.NetConnection;
import com.pivotal.gemfirexd.internal.jdbc.ClientBaseDataSource;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils.CSVVisitor;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.jdbc.ClientAttribute;
import io.snappydata.thrift.*;
import io.snappydata.thrift.common.*;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of client service that wraps a {@link SnappyDataService.Client}
 * thrift transport to add exception handling, connection pooling, failover and
 * single-hop.
 * <p>
 * This class is NOT THREAD-SAFE and only one thread should be using an instance
 * of this class at a time (or use synchronization at higher layers).
 */
@SuppressWarnings("serial")
public final class ClientService extends ReentrantLock implements LobService {

  private SnappyDataService.Client clientService;
  private volatile boolean isClosed;
  private HostConnection currentHostConnection;
  private HostAddress currentHostAddress;
  private String currentDefaultSchema;
  final OpenConnectionArgs connArgs;
  final Map<String, String> connectionProps;
  final List<HostAddress> connHosts;
  private final boolean explicitLoadBalance;
  private boolean loadBalance;
  final SocketParameters socketParams;
  final boolean framedTransport;
  final boolean useDirectBuffers;
  final int lobChunkSize;
  final Set<String> serverGroups;
  private final Logger logger = LoggerFactory.getLogger(getClass().getName());
  volatile int isolationLevel = Converters
      .getJdbcIsolation(snappydataConstants.DEFAULT_TRANSACTION_ISOLATION);

  static final int NUM_TXFLAGS = TransactionAttribute.values().length;

  /**
   * Stores tri-state for TransactionAttributes:
   * <p>
   * 0 for unset, -1 for false, 1 for true
   */
  private final byte[] txFlags = new byte[NUM_TXFLAGS];

  private static final String hostName;
  private static final String hostId;

  private static final SanityManager.PrintWriterFactory pwFactory;
  private static String gfxdLogFileNS;

  private static final CSVVisitor<Collection<HostAddress>, int[]> addHostAddresses;
  private static final FinalizeInvoker invokeFinalizers;
  private static final Thread finalizerThread;

  static {
    ThriftExceptionUtil.init();

    InetAddress hostAddr;
    String host;
    try {
      hostAddr = ClientSharedUtils.getLocalHost();
      host = hostAddr.getCanonicalHostName();
      if (host == null || host.length() == 0) {
        host = hostAddr.getHostAddress();
      }
    } catch (UnknownHostException uhe) {
      // use localhost as fallback
      host = "localhost";
    }
    hostName = host;

    // initialize the system logger, if any
    pwFactory = new SanityManager.PrintWriterFactory() {
      public final PrintWriter newPrintWriter(String file,
          boolean appendToFile) {
        try {
          return LogWriter.getPrintWriter(file, appendToFile);
        } catch (SqlException e) {
          throw new RuntimeException(e.getCause());
        }
      }
    };
    Level level = null;
    try {
      level = getLogLevel(null);
    } catch (SQLException sqle) {
      // ignore exception during static initialization
    }
    initClientLogger(null, null, level);

    addHostAddresses = new CSVVisitor<Collection<HostAddress>, int[]>() {
      @Override
      public void visit(String str, Collection<HostAddress> collectAddresses,
          int[] port) {
        final String serverName = SharedUtils.getHostPort(str, port);
        collectAddresses.add(ThriftUtils.getHostAddress(serverName, port[0]));
      }
    };

    // use process ID and timestamp for ID
    int pid = NativeCalls.getInstance().getProcessId();
    long currentTime = System.currentTimeMillis();
    StringBuilder sb = new StringBuilder();
    sb.append(pid).append('|');
    ClientSharedUtils.formatDate(currentTime, sb);
    hostId = sb.toString();

    // thread for periodic cleanup of finalizers
    invokeFinalizers = new FinalizeInvoker();
    finalizerThread = new Thread(invokeFinalizers, "FinalizeInvoker");
    finalizerThread.setDaemon(true);
    finalizerThread.start();
    Runtime.getRuntime().addShutdownHook(
        new FinalizeInvoker.StopFinalizer(invokeFinalizers));
  }

  // empty method for static initialization
  public static void init() {
  }

  private static Level getLogLevel(Properties props) throws SQLException {
    Level logLevel = null;
    String level;
    level = ClientBaseDataSource.readSystemProperty(
        Attribute.CLIENT_JVM_PROPERTY_PREFIX + ClientAttribute.LOG_LEVEL);
    if (level == null && props != null) {
      level = props.getProperty(ClientAttribute.LOG_LEVEL);
    }
    if (level != null) {
      try {
        logLevel = Level.parse(level.trim().toUpperCase());
      } catch (IllegalArgumentException iae) {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LOGLEVEL_FORMAT_INVALID, iae, level);
      }
    }
    return logLevel;
  }

  public static void initClientLogger(Properties incomingProps,
      PrintWriter logWriter, Level logLevel) {
    // also honour GemFireXD specific log-file setting for SanityManager
    // but do not set it as agent's PrintWriter else all kinds of client
    // network tracing will start
    String gfxdLogFile = null;
    if (logWriter == null) {
      gfxdLogFile = ClientBaseDataSource.readSystemProperty(
          Attribute.CLIENT_JVM_PROPERTY_PREFIX + ClientAttribute.LOG_FILE);
      if (gfxdLogFile == null && incomingProps != null) {
        gfxdLogFile = incomingProps.getProperty(ClientAttribute.LOG_FILE);
      }
      if (gfxdLogFile == null) {
        synchronized (ClientService.class) {
          if (gfxdLogFileNS == null) {
            final String logFileNS = ClientBaseDataSource
                .readSystemProperty(Attribute.CLIENT_JVM_PROPERTY_PREFIX
                    + ClientAttribute.LOG_FILE_STAMP);
            if (logFileNS != null) {
              do {
                gfxdLogFileNS = logFileNS + '.' + System.nanoTime();
              } while (new File(gfxdLogFileNS).exists());
            }
          }
          if (gfxdLogFileNS != null) {
            gfxdLogFile = gfxdLogFileNS;
          }
        }
      }
      if (gfxdLogFile != null) {
        synchronized (ClientService.class) {
          // try new file if it exists
          String currLog = SanityManager.clientGfxdLogFile;
          if (currLog == null || !gfxdLogFile.equals(currLog)) {
            if (new File(gfxdLogFile).exists()) {
              int dotIndex = gfxdLogFile.lastIndexOf('.');
              final String logName;
              final String extension;
              if (dotIndex > 0) {
                logName = gfxdLogFile.substring(0, dotIndex);
                extension = gfxdLogFile.substring(dotIndex);
              } else {
                logName = gfxdLogFile;
                extension = "";
              }
              int rollIndex = 1;
              String logFile;
              do {
                logFile = logName + '-' + rollIndex + extension;
                rollIndex++;
              } while (((currLog = SanityManager.clientGfxdLogFile) == null ||
                  !logFile.equals(currLog)) && new File(logFile).exists());
              gfxdLogFile = logFile;
            }
          }
          SanityManager.SET_DEBUG_STREAM(gfxdLogFile, pwFactory);
        }
      } else {
        logLevel = null;
      }
    } else {
      SanityManager.SET_DEBUG_STREAM_IFNULL(logWriter);
    }
    // also set the ClientSharedUtils logger
    if (logLevel == null) {
      // preserve existing log4j level if it is higher
      org.apache.log4j.Level current =
          org.apache.log4j.Logger.getRootLogger().getLevel();
      if (current != null && current.isGreaterOrEqual(org.apache.log4j.Level.WARN)) {
        logLevel = ClientSharedUtils.convertToJavaLogLevel(current);
      } else {
        logLevel = Level.CONFIG;
      }
    }
    if (logLevel != null) {
      ClientSharedUtils.initLogger(ClientSharedUtils.LOGGER_NAME,
          gfxdLogFile, true, true, logLevel, new LogHandler(logLevel));
    }
  }

  static ClientService create(String host, int port, boolean forXA,
      Properties connProperties, PrintWriter logWriter) throws SQLException {
    // first initialize logger
    initClientLogger(connProperties, logWriter, getLogLevel(connProperties));

    Map<String, String> props = null;
    String userName = null;
    String password = null;
    if (connProperties != null) {
      final THashMap connProps = new THashMap();
      // noinspection unchecked
      props = connProps;
      for (String propName : connProperties.stringPropertyNames()) {
        if (ClientAttribute.USERNAME.equals(propName)
            || ClientAttribute.USERNAME_ALT.equals(propName)) {
          userName = connProperties.getProperty(propName);
        } else if (ClientAttribute.PASSWORD.equals(propName)) {
          password = connProperties.getProperty(propName);
        } else {
          connProps.put(propName, connProperties.getProperty(propName));
        }
      }
      if (connProps.size() == 0) {
        props = null;
      }
    }
    Thread currentThread = Thread.currentThread();
    String clientId = hostId + '|' + currentThread.getName() + "<0x"
        + Long.toHexString(currentThread.getId()) + '>';
    // TODO: currently hardcoded security mechanism to PLAIN
    // implement Diffie-Hellman and additional like SASL (see Hive driver)
    OpenConnectionArgs connArgs = new OpenConnectionArgs(hostName, clientId,
        SecurityMechanism.PLAIN).setUserName(userName)
        .setPassword(password).setForXA(forXA).setProperties(props);
    try {
      HostAddress hostAddr = ThriftUtils.getHostAddress(host, port);
      return new ClientService(hostAddr, connArgs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  private ClientService(HostAddress hostAddr, OpenConnectionArgs connArgs)
      throws SnappyException {

    ClientConfiguration config = ClientConfiguration.getInstance();
    ClientSharedUtils.getLogger().info("Starting client on '" + hostName +
        "' with ID='" + hostId + "' Source-Revision=" +
        config.getSourceRevision());

    this.isClosed = true;

    this.currentHostConnection = null;
    this.currentHostAddress = null;
    this.connArgs = connArgs;

    Map<String, String> props = connArgs.getProperties();
    this.connectionProps = props != null ? new HashMap<>(props) : new HashMap<>();

    String propValue;
    boolean hasLoadBalance = props != null &&
        props.containsKey(ClientAttribute.LOAD_BALANCE);
    this.explicitLoadBalance = hasLoadBalance && "true".equalsIgnoreCase(
        props.remove(ClientAttribute.LOAD_BALANCE));
    // default for load-balance is true on locators and false on servers
    // so tentatively set as true and adjust using the ControlConnection
    this.loadBalance = this.explicitLoadBalance || !hasLoadBalance;

    // setup the original host list
    if (props != null && (propValue = props.remove(
        ClientAttribute.SECONDARY_LOCATORS)) != null) {
      this.connHosts = new ArrayList<>(4);
      this.connHosts.add(hostAddr);
      final int[] portHolder = new int[1];
      SharedUtils.splitCSV(propValue, addHostAddresses, this.connHosts,
          portHolder);
    } else {
      this.connHosts = Collections.singletonList(hostAddr);
    }

    // read the server groups to use for connection
    if (props != null
        && (propValue = props.remove(ClientAttribute.SERVER_GROUPS)) != null) {
      @SuppressWarnings("unchecked")
      Set<String> groupsSet = new THashSet(4);
      SharedUtils.splitCSV(propValue, SharedUtils.stringAggregator,
          groupsSet, Boolean.TRUE);
      this.serverGroups = Collections.unmodifiableSet(groupsSet);
    } else {
      this.serverGroups = null;
    }

    this.socketParams = new SocketParameters();
    // initialize read-timeout from properties first
    if (props != null &&
        (propValue = props.remove(ClientAttribute.READ_TIMEOUT)) != null) {
      SocketParameters.Param.READ_TIMEOUT.setParameter(this.socketParams,
          propValue);
    } else {
      // else try with DriverManager login timeout
      int loginTimeout = DriverManager.getLoginTimeout();
      if (loginTimeout != 0) {
        SocketParameters.Param.READ_TIMEOUT.setParameter(this.socketParams,
            Integer.toString(loginTimeout));
      }
    }
    // now check for the protocol details like SSL etc and thus get the required
    // SnappyData ServerType
    boolean binaryProtocol = false;
    boolean framedTransport = false;
    boolean useSSL = false;
    boolean directBuffers = false;
    int lobChunkSize = -1;
    if (props != null) {
      binaryProtocol = Boolean.parseBoolean(props
          .remove(ClientAttribute.THRIFT_USE_BINARY_PROTOCOL));
      framedTransport = Boolean.parseBoolean(props
          .remove(ClientAttribute.THRIFT_USE_FRAMED_TRANSPORT));
      useSSL = Boolean.parseBoolean(props.remove(ClientAttribute.SSL));
      // set SSL properties (csv format) into SSL params in SocketParameters
      propValue = props.remove(ClientAttribute.THRIFT_SSL_PROPERTIES);
      if (propValue != null) {
        useSSL = true;
        ThriftUtils.getSSLParameters(this.socketParams, propValue);
      }
      // parse remaining properties like socket buffer sizes, read timeout
      // and keep alive settings
      for (SocketParameters.Param p : SocketParameters.getAllParamsNoSSL()) {
        propValue = props.remove(p.getPropertyName());
        if (propValue != null) {
          p.setParameter(this.socketParams, propValue);
        }
      }
      // check direct ByteBuffers for large LOB chunks (default is true)
      directBuffers = !"false".equalsIgnoreCase(props.remove(
          ClientAttribute.THRIFT_LOB_DIRECT_BUFFERS));
      // set the chunk size for LOBs if set
      String chunkSize = props.remove(ClientAttribute.THRIFT_LOB_CHUNK_SIZE);
      if (chunkSize != null) {
        try {
          lobChunkSize = Integer.parseInt(chunkSize);
          if (lobChunkSize <= 0) {
            throw new NumberFormatException("Input string: " + lobChunkSize);
          }
        } catch (NumberFormatException nfe) {
          throw new IllegalArgumentException("Expected valid integer for " +
              ClientAttribute.THRIFT_LOB_CHUNK_SIZE + " but got: " +
              chunkSize, nfe);
        }
      }
    }
    this.socketParams.setServerType(ServerType.getServerType(true,
        binaryProtocol, useSSL));
    this.framedTransport = framedTransport;
    this.useDirectBuffers = directBuffers;
    this.lobChunkSize = lobChunkSize;

    connArgs.setProperties(props);

    openConnection(hostAddr, null, null);
  }

  void openConnection(HostAddress hostAddr, Set<HostAddress> failedServers,
      Throwable failure) throws SnappyException {
    // open the connection
    if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("openConnection_S", null, 0, ns,
          true, SanityManager.TraceClientConn ? new Throwable() : null);
    }

    while (true) {
      super.lock();
      try {
        this.currentHostAddress = hostAddr;
        if (this.loadBalance) {
          final ControlConnection controlService = ControlConnection
              .getOrCreateControlConnection(connHosts, this, failure);
          // if connected to server then disable load-balance by default
          if (!this.explicitLoadBalance) {
            // check the actual host type of "locators"
            Set<HostAddress> locators = controlService.getLocatorsCopy();
            locators.retainAll(connHosts);
            boolean hasLocator = false;
            if (!locators.isEmpty()) {
              for (HostAddress addr : locators) {
                if (addr.getServerType() == null ||
                    addr.getServerType().isThriftLocator()) {
                  hasLocator = true;
                  break;
                }
              }
            }
            if (!hasLocator) {
              this.loadBalance = false;
              controlService.close(true);
            }
          }
          if (this.loadBalance) {
            // at this point query the control service for preferred server
            this.currentHostAddress = hostAddr = controlService.getPreferredServer(
                failedServers, this.serverGroups, false, failure);
          }
        }
        this.connectionProps.put(ClientAttribute.LOAD_BALANCE,
            Boolean.toString(this.loadBalance));

        final TTransport currentTransport;
        int readTimeout;
        if (this.clientService != null) {
          currentTransport = this.clientService
              .getOutputProtocol().getTransport();
          if (currentTransport instanceof SocketTimeout) {
            readTimeout = ((SocketTimeout)currentTransport).getRawTimeout();
            if (readTimeout == 0) { // not set
              readTimeout = socketParams.getReadTimeout();
            }
          } else {
            readTimeout = socketParams.getReadTimeout();
          }
        } else {
          currentTransport = null;
          readTimeout = socketParams.getReadTimeout();
        }

        TTransport inTransport, outTransport;
        TProtocol inProtocol, outProtocol;
        final TTransport transport;
        if (getServerType().isThriftSSL()) {
          transport = new SnappyTSSLSocket(hostAddr.resolveHost(),
              hostAddr.getPort(), readTimeout, socketParams);
        } else {
          transport = new SnappyTSocket(
              hostAddr.resolveHost(), hostAddr.getPort(), connArgs.clientID,
              false, true, ThriftUtils.isThriftSelectorServer(),
              readTimeout, socketParams);
        }
        if (this.framedTransport) {
          inTransport = outTransport = new TFramedTransport(transport);
        } else {
          inTransport = outTransport = transport;
        }
        if (getServerType().isThriftBinaryProtocol()) {
          inProtocol = new TBinaryProtocolDirect(inTransport,
              this.useDirectBuffers);
          outProtocol = new TBinaryProtocolDirect(outTransport,
              this.useDirectBuffers);
        } else {
          inProtocol = new TCompactProtocolDirect(inTransport,
              this.useDirectBuffers);
          outProtocol = new TCompactProtocolDirect(outTransport,
              this.useDirectBuffers);
        }

        SnappyDataService.Client service = new SnappyDataService.Client(
            inProtocol, outProtocol);

        // for failures, set the default schema to the previous one
        if (this.currentDefaultSchema != null) {
          Map<String, String> props = this.connArgs.getProperties();
          if (props == null) {
            props = Collections.singletonMap(ClientAttribute.DEFAULT_SCHEMA,
                this.currentDefaultSchema);
            this.connArgs.setProperties(props);
          } else {
            props.put(ClientAttribute.DEFAULT_SCHEMA,
                this.currentDefaultSchema);
          }
        }
        ConnectionProperties connProps = service.openConnection(this.connArgs);
        if (currentTransport != null) {
          currentTransport.close();
        }
        this.clientService = service;
        this.currentHostConnection = new HostConnection(hostAddr,
            connProps.connId, connProps.token);
        this.currentHostAddress = hostAddr;
        this.currentDefaultSchema = connProps.getDefaultSchema();
        if (SanityManager.TraceClientStatementHA
            | SanityManager.TraceClientConn) {
          if (SanityManager.TraceClientHA) {
            SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                "Opened connection @" + hashCode() + " ID=" + connProps.connId
                    + (connProps.token == null ? "" : (" @"
                    + ClientSharedUtils.toHexString(connProps.token))));
          } else {
            final long ns = System.nanoTime();
            SanityManager.DEBUG_PRINT_COMPACT("openConnection_E", null,
                connProps.connId, connProps.token, ns, false, null);
          }
        }

        // set transaction isolation and other attributes on connection (for
        // the case of failover, for example)
        final int isolationLevel = this.isolationLevel;
        final EnumMap<TransactionAttribute, Boolean> txFlags = getTXFlags();
        if (isolationLevel != Converters.getJdbcIsolation(
            snappydataConstants.DEFAULT_TRANSACTION_ISOLATION)) {
          beginTransaction(isolationLevel, txFlags);
        } else if (txFlags != null) {
          setTransactionAttributes(txFlags);
        }

        this.isClosed = false;
        return;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, false,
            "openConnection");
      } finally {
        super.unlock();
      }
    }
  }

  final ServerType getServerType() {
    return this.socketParams.getServerType();
  }

  final TProtocol getInputProtocol() {
    return this.clientService.getInputProtocol();
  }

  public final HostConnection getCurrentHostConnection() {
    return this.currentHostConnection;
  }

  public final String getCurrentDefaultSchema() {
    return this.currentDefaultSchema;
  }

  final void setTXFlag(TransactionAttribute txFlag, boolean val) {
    this.txFlags[txFlag.ordinal()] = val ? (byte)1 : -1;
  }

  final boolean isTXFlagSet(TransactionAttribute txFlag, boolean defaultValue) {
    switch (this.txFlags[txFlag.ordinal()]) {
      case 1:
        return true;
      case -1:
        return false;
      default:
        return defaultValue;
    }
  }

  final EnumMap<TransactionAttribute, Boolean> getTXFlags() {
    final byte[] txFlags = this.txFlags;
    EnumMap<TransactionAttribute, Boolean> attrs = null;
    for (int ordinal = 0; ordinal < NUM_TXFLAGS; ordinal++) {
      int flag = txFlags[ordinal];
      switch (flag) {
        case 1:
        case -1:
          if (attrs == null) {
            attrs = ThriftUtils.newTransactionFlags();
          }
          TransactionAttribute attr = TransactionAttribute.findByValue(ordinal);
          if (attr != null) {
            attrs.put(attr, flag == 1);
          }
          break;
        default:
          break;
      }
    }
    return attrs;
  }

  private Set<HostAddress> updateFailedServersForCurrent(
      Set<HostAddress> failedServers, boolean checkAllFailed,
      Throwable cause) throws SnappyException {
    if (failedServers == null) {
      @SuppressWarnings("unchecked")
      Set<HostAddress> servers = new THashSet(2);
      failedServers = servers;
    }
    final HostAddress host = this.currentHostAddress;
    if (host != null && !failedServers.add(host) && checkAllFailed) {
      // have we come around full circle?
      ControlConnection controlService = ControlConnection
          .getOrCreateControlConnection(connHosts, this, cause);
      // Below call will throw failure if no servers are available.
      // This is required to be done explicitly to break the infinite loop
      // for cases where server is otherwise healthy (so doesn't get detected
      //   in failover which takes out control host for reconnect cases)
      // but the operation throws a CancelException every time for some
      // reason (e.g. the simulation in BugsDUnit.testInsertFailoverbug_47407).
      controlService.searchRandomServer(failedServers, cause);
    }
    return failedServers;
  }

  protected Set<HostAddress> handleException(final Throwable t,
      Set<HostAddress> failedServers, boolean tryFailover,
      boolean ignoreNodeFailure, boolean createNewConnection,
      String op) throws SnappyException {
    if (SanityManager.TraceClientHA) {
      logger.info("ClientService@" + System.identityHashCode(this) +
              " received exception for " + op + ". Failed servers=" +
              failedServers + " tryFailover=" + tryFailover +
              " ignoreNodeFailure=" + ignoreNodeFailure +
              " createNewConn=" + createNewConnection, t);
    }
    final HostConnection source = this.currentHostConnection;
    final HostAddress sourceAddr = this.currentHostAddress;
    if (this.isClosed && createNewConnection) {
      throw newSnappyExceptionForConnectionClose(source, failedServers,
          true, t);
    }
    final int isolationLevel = this.isolationLevel;
    if (!this.loadBalance
        // no failover for transactions yet
        || isolationLevel != Connection.TRANSACTION_NONE) {
      tryFailover = false;
    }

    if (t instanceof SnappyException) {
      SnappyException se = (SnappyException)t;
      SnappyExceptionData seData = se.getExceptionData();
      String sqlState = seData.getSqlState();
      NetConnection.FailoverStatus status;
      if ((status = NetConnection.getFailoverStatus(sqlState,
          seData.getErrorCode(), se)).isNone()) {
        // convert DATA_CONTAINTER_CLOSED to "X0Z01" for non-transactional case
        if (isolationLevel == Connection.TRANSACTION_NONE
            && SQLState.DATA_CONTAINER_CLOSED.equals(sqlState)) {
          throw newSnappyExceptionForNodeFailure(source, op, isolationLevel,
              failedServers, createNewConnection, se);
        } else {
          throw se;
        }
      } else if (!tryFailover) {
        if (ignoreNodeFailure) return failedServers;
        throw newSnappyExceptionForNodeFailure(source, op, isolationLevel,
            failedServers, createNewConnection, se);
      } else if (status == NetConnection.FailoverStatus.RETRY) {
        return failedServers;
      }
    } else if (t instanceof TException) {
      if (t instanceof TApplicationException) {
        // no failover for application level exceptions
        throw ThriftExceptionUtil.newSnappyException(
            SQLState.DATA_UNEXPECTED_EXCEPTION, t,
            source != null ? source.hostAddr.toString() : null);
      } else if (!tryFailover) {
        if (ignoreNodeFailure) return failedServers;
        throw newSnappyExceptionForNodeFailure(source, op, isolationLevel,
            failedServers, createNewConnection, t);
      }
    } else {
      throw ThriftExceptionUtil.newSnappyException(SQLState.JAVA_EXCEPTION, t,
          sourceAddr != null ? sourceAddr.toString() : null, t.getClass(),
          t.getMessage());
    }
    // need to do failover to new server, so get the next one
    failedServers = updateFailedServersForCurrent(failedServers, true, t);
    if (createNewConnection) {
      // try and close the connection explicitly since server may still be alive
      try {
        // lock should already be held so no timeout
        closeConnection(0);
      } catch (SnappyException se) {
        // ignore
      }
      openConnection(source.hostAddr, failedServers, t);
    }
    return failedServers;
  }

  private Set<HostAddress> tryCreateNewConnection(HostConnection source,
      Set<HostAddress> failedServers, Throwable cause) {
    // create a new connection in any case for future operations
    if (this.loadBalance) {
      try {
        failedServers = updateFailedServersForCurrent(failedServers,
            false, cause);
        openConnection(source.hostAddr, failedServers, cause);
      } catch (RuntimeException | SnappyException ignored) {
        // deliberately ignored at this point
      }
    }
    return failedServers;
  }

  SnappyException newSnappyExceptionForConnectionClose(HostConnection source,
      Set<HostAddress> failedServers, boolean createNewConnection,
      Throwable cause) {
    // if cause is a node failure exception then return it
    if (cause instanceof SnappyException) {
      SnappyException se = (SnappyException)cause;
      SnappyExceptionData seData = se.getExceptionData();
      if (SQLState.GFXD_NODE_SHUTDOWN_PREFIX.equals(seData.getSqlState())
          || SQLState.DATA_CONTAINER_CLOSED.equals(seData.getSqlState())) {
        if (createNewConnection) {
          tryCreateNewConnection(source, failedServers, cause);
        }
        return se;
      }
    }
    return ThriftExceptionUtil.newSnappyException(
        SQLState.NO_CURRENT_CONNECTION, cause,
        source != null ? source.hostAddr.toString() : null);
  }

  Exception newExceptionForNodeFailure(HostConnection expectedSource,
      String op, int isolationLevel, Throwable cause, boolean snappyException) {
    final HostConnection source = this.currentHostConnection;
    String opNode = op + " {current node = " + source + '}';
    if (isolationLevel == Connection.TRANSACTION_NONE) {
      // throw X0Z01 for this case
      String err = expectedSource
          + (cause instanceof TException ? " {caused by: "
          + ThriftExceptionUtil.getExceptionString(cause) + '}' : "");
      return snappyException ? ThriftExceptionUtil.newSnappyException(
          SQLState.GFXD_NODE_SHUTDOWN, cause,
          source != null ? source.hostAddr.toString() : null, err, op)
          : ThriftExceptionUtil.newSQLException(SQLState.GFXD_NODE_SHUTDOWN,
          cause, err, opNode);
    } else {
      // throw 40XD0 for this case
      String err = " operation=" + opNode
          + (cause instanceof TException ? " caused by: "
          + ThriftExceptionUtil.getExceptionString(cause) : "");
      return snappyException ? ThriftExceptionUtil.newSnappyException(
          SQLState.DATA_CONTAINER_CLOSED, cause, source != null
              ? source.hostAddr.toString() : null, expectedSource, err)
          : ThriftExceptionUtil.newSQLException(SQLState.DATA_CONTAINER_CLOSED,
          cause, expectedSource, err);
    }
  }

  SnappyException newSnappyExceptionForNodeFailure(HostConnection source,
      String op, int isolationLevel, Set<HostAddress> failedServers,
      boolean createNewConnection, Throwable cause) {
    if (this.isClosed) {
      return newSnappyExceptionForConnectionClose(source, failedServers,
          createNewConnection, cause);
    }
    // try and close the connection explicitly since server may still be alive
    try {
      // lock should already be held so no timeout
      closeConnection(0);
    } catch (SnappyException se) {
      // ignore
    }
    // create a new connection in any case for future operations
    if (createNewConnection && this.loadBalance) {
      try {
        failedServers = updateFailedServersForCurrent(failedServers,
            false, cause);
        openConnection(source.hostAddr, failedServers, cause);
      } catch (RuntimeException | SnappyException ignored) {
        // deliberately ignored at this point
      }
    }
    return (SnappyException)newExceptionForNodeFailure(source, op,
        isolationLevel, cause, true);
  }

  final void checkUnexpectedNodeFailure(final HostConnection expectedSource,
      final String op) throws SnappyException {
    if (expectedSource != null &&
        expectedSource.equals(currentHostConnection)) {
      return;
    }
    // In a rare case a server may have come back in the meantime (e.g. between
    // two operations) on the same host:port and could be using the assigned
    // connection ID for some other client while token mode may be disabled. For
    // that case now only checking the HostConnection by reference only.
    throw (SnappyException)newExceptionForNodeFailure(expectedSource, op,
        isolationLevel, null, true);
  }

  public final HostConnection getLobSource(boolean throwOnFailure,
      String op) throws SQLException {
    // get the current source from service; if the original source has failed
    // and this is new source after failover, then use it in any case and
    // actual call will fail due to token mismatch on server but send
    // it nevertheless to simplify client-side code as well as possible future
    // support for LOB retrieval after failover
    final HostConnection source = this.currentHostConnection;
    if (source != null) {
      return source;
    } else if (throwOnFailure) {
      throw (SQLException)newExceptionForNodeFailure(null, op,
          this.isolationLevel, null, false);
    } else {
      return null;
    }
  }

  final void setSourceConnection(RowSet rs) throws SnappyException {
    final HostConnection source = this.currentHostConnection;
    rs.setConnId(source.connId);
    rs.setToken(source.token);
    rs.setSource(source.hostAddr);
    // initialize finalizers for LOBs in the rows, if any
    final List<Row> rows = rs.rows;
    if (rows != null && rows.size() > 0) {
      // create LOBs for each row, if any
      try {
        for (Row row : rows) {
          row.initializeLobs(this);
        }
      } catch (SQLException sqle) {
        SnappyExceptionData data = new SnappyExceptionData(sqle.getMessage(),
            sqle.getErrorCode()).setSqlState(sqle.getSQLState());
        SnappyException se = new SnappyException(data, source.toString());
        se.initCause(sqle);
        throw se;
      }
    }
  }

  final void setSourceConnection(StatementResult sr) throws SnappyException {
    RowSet rs = sr.resultSet;
    if (rs != null) {
      setSourceConnection(rs);
    }
    rs = sr.generatedKeys;
    if (rs != null) {
      setSourceConnection(rs);
    }
  }

  private boolean acquireLock(long lockTimeoutMillis) {
    if (lockTimeoutMillis <= 0) {
      super.lock();
      return true;
    } else {
      try {
        return super.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  public final boolean isClosed() {
    return this.isClosed;
  }

  public StatementResult execute(String sql,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs)
      throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("execute_S", sql, source.connId,
          source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.execute(source.connId, sql,
            outputParams, attrs, source.token);
        setSourceConnection(sr);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (sr.getNewDefaultSchema() != null) {
          this.currentDefaultSchema = sr.getNewDefaultSchema();
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("execute_E", sql, source.connId,
              source.token, ns, false, null);
        }
        return sr;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "executeStatement");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public UpdateResult executeUpdate(List<String> sqls, StatementAttrs attrs)
      throws SnappyException {
    HostConnection source = this.currentHostConnection;
    String sqlStr = null;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      sqlStr = sqls.toString();
      SanityManager.DEBUG_PRINT_COMPACT("executeUpdate_S", sqlStr,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        UpdateResult ur = this.clientService.executeUpdate(source.connId,
            sqls, attrs, source.token);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (ur.getNewDefaultSchema() != null) {
          this.currentDefaultSchema = ur.getNewDefaultSchema();
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executeUpdate_E", sqlStr,
              source.connId, source.token, ns, false, null);
        }
        return ur;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "executeUpdate");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet executeQuery(String sql, StatementAttrs attrs)
      throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("executeQuery_S", sql, source.connId,
          source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        RowSet rs = this.clientService.executeQuery(source.connId, sql,
            attrs, source.token);
        setSourceConnection(rs);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executeQuery_E", sql,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "executeQuery");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public PrepareResult prepareStatement(String sql,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs)
      throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("prepareStatement_S", sql,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        PrepareResult pr = this.clientService.prepareStatement(source.connId,
            sql, outputParams, attrs, source.token);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("prepareStatement_E", sql
                  + ":ID=" + pr.statementId, source.connId, source.token, ns,
              false, null);
        }
        return pr;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "prepareStatement");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public StatementResult executePrepared(HostConnection source, long stmtId,
      Row params, Map<Integer, OutputParameter> outputParams,
      PrepareResultHolder prh) throws SnappyException {
    long logStmtId = -1;
    StatementAttrs attrs = prh.getAttributes();
    Set<HostAddress> failedServers;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePrepared");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        logStmtId = stmtId;
        SanityManager.DEBUG_PRINT_COMPACT("executePrepared_S", logStmtId,
            source.connId, source.token, ns, true, null);
      }

      StatementResult sr = this.clientService.executePrepared(stmtId, params,
          outputParams, attrs, source.token);
      setSourceConnection(sr);
      if (attrs != null) {
        attrs.setPossibleDuplicate(false);
      }
      if (sr.getNewDefaultSchema() != null) {
        this.currentDefaultSchema = sr.getNewDefaultSchema();
      }
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePrepared_E", logStmtId,
            source.connId, source.token, ns, false, null);
      }
      return sr;
    } catch (Throwable t) {
      failedServers = handleException(t, null, true, false, true,
          "executePrepared");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        logStmtId = stmtId;
        SanityManager.DEBUG_PRINT_COMPACT("executePrepared_S", logStmtId,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    List<Row> paramsBatch = Collections.singletonList(params);
    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, outputParams, attrs,
            source.token);
        setSourceConnection(sr);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (sr.getNewDefaultSchema() != null) {
          this.currentDefaultSchema = sr.getNewDefaultSchema();
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePrepared_E", logStmtId,
              source.connId, source.token, ns, false, null);
        }
        return sr;
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "executePrepared");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public UpdateResult executePreparedUpdate(HostConnection source,
      long stmtId, Row params, PrepareResultHolder prh) throws SnappyException {
    StatementAttrs attrs = prh.getAttributes();
    Set<HostAddress> failedServers;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePreparedUpdate");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_S", null,
            source.connId, source.token, ns, true, null);
      }

      UpdateResult ur = this.clientService.executePreparedUpdate(stmtId,
          params, attrs, source.token);
      if (attrs != null) {
        attrs.setPossibleDuplicate(false);
      }
      if (ur.getNewDefaultSchema() != null) {
        this.currentDefaultSchema = ur.getNewDefaultSchema();
      }
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_E", null,
            source.connId, source.token, ns, false, null);
      }
      return ur;
    } catch (Throwable t) {
      failedServers = handleException(t, null, true, false, true,
          "executePreparedUpdate");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_S", null,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    List<Row> paramsBatch = Collections.singletonList(params);
    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, null, attrs,
            source.token);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (sr.getNewDefaultSchema() != null) {
          this.currentDefaultSchema = sr.getNewDefaultSchema();
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePreparedUpdate_E", null,
              source.connId, source.token, ns, false, null);
        }
        return new UpdateResult().setUpdateCount(sr.getUpdateCount())
            .setGeneratedKeys(sr.getGeneratedKeys())
            .setWarnings(sr.getWarnings());
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "executePreparedUpdate");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet executePreparedQuery(HostConnection source, long stmtId,
      Row params, PrepareResultHolder prh) throws SnappyException {
    StatementAttrs attrs = prh.getAttributes();
    Set<HostAddress> failedServers;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePreparedQuery");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_S", null,
            source.connId, source.token, ns, true, null);
      }

      RowSet rs = this.clientService.executePreparedQuery(stmtId, params,
          attrs, source.token);
      setSourceConnection(rs);
      if (attrs != null) {
        attrs.setPossibleDuplicate(false);
      }
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_E", null,
            source.connId, source.token, ns, false, null);
      }
      return rs;
    } catch (Throwable t) {
      failedServers = handleException(t, null, true, false, true,
          "executePreparedQuery");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_S", null,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    List<Row> paramsBatch = Collections.singletonList(params);
    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, null, attrs,
            source.token);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (sr.getNewDefaultSchema() != null) {
          this.currentDefaultSchema = sr.getNewDefaultSchema();
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePreparedQuery_E", null,
              source.connId, source.token, ns, false, null);
        }
        RowSet rs = sr.getResultSet();
        setSourceConnection(rs);
        return rs;
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "executePreparedQuery");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public UpdateResult executePreparedBatch(HostConnection source, long stmtId,
      List<Row> paramsBatch, PrepareResultHolder prh) throws SnappyException {
    StatementAttrs attrs = prh.getAttributes();
    Set<HostAddress> failedServers;
    super.lock();
    try {
      // check if node has failed sometime before; keeping inside try-catch
      // block allows automatic failover if possible
      checkUnexpectedNodeFailure(source, "executePreparedBatch");

      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_S", null,
            source.connId, source.token, ns, true, null);
      }

      UpdateResult ur = this.clientService.executePreparedBatch(stmtId,
          paramsBatch, attrs, source.token);
      if (attrs != null) {
        attrs.setPossibleDuplicate(false);
      }
      if (ur.getNewDefaultSchema() != null) {
        this.currentDefaultSchema = ur.getNewDefaultSchema();
      }
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_E", null,
            source.connId, source.token, ns, false, null);
      }
      return ur;
    } catch (Throwable t) {
      failedServers = handleException(t, null, true, false, true,
          "executePreparedBatch");

      source = this.currentHostConnection;
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_S", null,
            source.connId, source.token, ns, true, null);
      }
    } finally {
      super.unlock();
    }

    if (attrs == null) {
      attrs = new StatementAttrs();
    }
    attrs.setPossibleDuplicate(true);
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, prh.getSQL(), paramsBatch, null, attrs,
            source.token);
        prh.updatePrepareResult(sr.getPreparedResult());
        attrs.setPossibleDuplicate(false);
        if (sr.getNewDefaultSchema() != null) {
          this.currentDefaultSchema = sr.getNewDefaultSchema();
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("executePreparedBatch_E", null,
              source.connId, source.token, ns, false, null);
        }
        return new UpdateResult()
            .setBatchUpdateCounts(sr.getBatchUpdateCounts())
            .setGeneratedKeys(sr.getGeneratedKeys())
            .setWarnings(sr.getWarnings());
      } catch (Throwable t) {
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "executePreparedBatch");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public StatementResult prepareAndExecute(String sql, List<Row> paramsBatch,
      Map<Integer, OutputParameter> outputParams, StatementAttrs attrs)
      throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("prepareAndExecute_S", sql,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        StatementResult sr = this.clientService.prepareAndExecute(
            source.connId, sql, paramsBatch, outputParams, attrs,
            source.token);
        setSourceConnection(sr);
        if (attrs != null) {
          attrs.setPossibleDuplicate(false);
        }
        if (sr.getNewDefaultSchema() != null) {
          this.currentDefaultSchema = sr.getNewDefaultSchema();
        }
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("preparedAndExecute_E", sql,
              source.connId, source.token, ns, false, null);
        }
        return sr;
      } catch (Throwable t) {
        if (attrs == null) {
          attrs = new StatementAttrs();
        }
        attrs.setPossibleDuplicate(false);
        failedServers = handleException(t, failedServers, true, false, true,
            "prepareAndExecute");
        attrs.setPossibleDuplicate(true);
      } finally {
        super.unlock();
      }
    }
  }

  public void beginTransaction(int jdbcIsolationLevel,
      Map<TransactionAttribute, Boolean> flags) throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("beginTransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        this.isolationLevel = Converters.getJdbcIsolation(this.clientService
            .beginTransaction(source.connId,
                Converters.getThriftTransactionIsolation(jdbcIsolationLevel),
                flags, source.token));
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("beginTransaction_E", null,
              source.connId, source.token, ns, false, null);
        }
        return;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, true,
            "beginTransaction");
      } finally {
        super.unlock();
      }
    }
  }

  public void setTransactionAttributes(
      Map<TransactionAttribute, Boolean> flags) throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("setTransactionAttributes_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        this.clientService.setTransactionAttributes(source.connId, flags,
            source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("setTransactionAttributes_E",
              null, source.connId, source.token, ns, false, null);
        }
        return;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, true,
            "setTransactionAttributes");
      } finally {
        super.unlock();
      }
    }
  }

  public void commitTransaction(final HostConnection source,
      boolean startNewTransaction, Map<TransactionAttribute, Boolean> flags)
      throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("commitTransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      if (this.isolationLevel != Connection.TRANSACTION_NONE) {
        checkUnexpectedNodeFailure(source, "commitTransaction");
      }
      this.clientService.commitTransaction(source.connId,
          startNewTransaction, flags, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("commitTransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // at isolation level NONE failover to new server and return since
      // it will be a no-op on the new server-side connection
      handleException(t, null, this.isolationLevel == Connection.TRANSACTION_NONE,
            false, true, "commitTransaction");
    } finally {
      super.unlock();
    }
  }

  public void rollbackTransaction(final HostConnection source,
      boolean startNewTransaction, Map<TransactionAttribute, Boolean> flags)
      throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("rollbackTransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      if (this.isolationLevel != Connection.TRANSACTION_NONE) {
        checkUnexpectedNodeFailure(source, "rollbackTransaction");
      }
      this.clientService.rollbackTransaction(source.connId,
          startNewTransaction, flags, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("rollbackTransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // at isolation level NONE failover to new server and return since
      // it will be a no-op on the new server-side connection
      handleException(t, null, this.isolationLevel == Connection.TRANSACTION_NONE,
            false, true, "rollbackTransaction");
    } finally {
      super.unlock();
    }
  }

  public RowSet getNextResultSet(final HostConnection source, long cursorId,
      byte otherResultSetBehaviour) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getNextResultSet_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "getNextResultSet");
      RowSet rs = this.clientService.getNextResultSet(cursorId,
          otherResultSetBehaviour, source.token);
      setSourceConnection(rs);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getNextResultSet_E", null,
            source.connId, source.token, ns, false, null);
      }
      return rs;
    } catch (Throwable t) {
      // no failover possible
      handleException(t, null, false, false, true, "getNextResultSet");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public BlobChunk getBlobChunk(final HostConnection source, long lobId,
      long offset, int chunkSize, boolean freeLobAtEnd) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getBlobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "getBlobChunk");

      BlobChunk blob = this.clientService.getBlobChunk(source.connId, lobId,
          offset, chunkSize, freeLobAtEnd, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getBlobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return blob;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, false, true, "getBlobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public ClobChunk getClobChunk(final HostConnection source, long lobId,
      long offset, int chunkSize, boolean freeLobAtEnd) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "getClobChunk");

      ClobChunk clob = this.clientService.getClobChunk(source.connId, lobId,
          offset, chunkSize, freeLobAtEnd, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return clob;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, false, true, "getClobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Blob createBlob(BlobChunk firstChunk,
      boolean forStream) throws SQLException {
    return new ClientBlob(firstChunk, this, getLobSource(true,
        "createBlob"), forStream);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Blob createBlob(InputStream stream,
      long length) throws SQLException {
    return new ClientBlob(stream, length, this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Clob createClob(ClobChunk firstChunk,
      boolean forStream) throws SQLException {
    return new ClientClob(firstChunk, this, getLobSource(true,
        "createClob"), forStream);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Clob createClob(Reader reader, long length) throws SQLException {
    return new ClientClob(reader, length, this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final Clob createClob(InputStream asciiStream,
      long length) throws SQLException {
    return new ClientClob(asciiStream, length, this);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final BlobChunk getBlobChunk(long lobId, long offset, int chunkSize,
      boolean freeLobAtEnd) throws SQLException {
    try {
      return getBlobChunk(getLobSource(true, "getBlobChunk"), lobId,
          offset, chunkSize, freeLobAtEnd);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final ClobChunk getClobChunk(long lobId, long offset, int chunkSize,
      boolean freeLobAtEnd) throws SQLException {
    try {
      return getClobChunk(getLobSource(true, "getClobChunk"), lobId,
          offset, chunkSize, freeLobAtEnd);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    }
  }

  // TODO: currently higher layers send lobs in single first chunk

  public long sendBlobChunk(final HostConnection source, BlobChunk chunk)
      throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("sendBlobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "sendBlobChunk");

      long lobId = this.clientService.sendBlobChunk(chunk, source.connId,
          source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getBlobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return lobId;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, false, true, "sendBlobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public long sendClobChunk(final HostConnection source, ClobChunk chunk)
      throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "sendClobChunk");

      long lobId = this.clientService.sendClobChunk(chunk, source.connId,
          source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("getClobChunk_E", null,
            source.connId, source.token, ns, false, null);
      }
      return lobId;
    } catch (Throwable t) {
      // no failover possible for multiple chunks
      handleException(t, null, false, false, true, "sendClobChunk");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public RowSet scrollCursor(final HostConnection source, long cursorId,
      int offset, boolean offsetIsAbsolute, boolean fetchReverseForAbsolute,
      int fetchSize) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("scrollCursor_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "scrollCursor");

      RowSet rs = this.clientService.scrollCursor(cursorId, offset,
          offsetIsAbsolute, fetchReverseForAbsolute, fetchSize, source.token);
      setSourceConnection(rs);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("scrollCursor_E", null,
            source.connId, source.token, ns, false, null);
      }
      return rs;
    } catch (Throwable t) {
      // no failover possible
      handleException(t, null, false, false, true, "scrollCursor");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void executeCursorUpdate(final HostConnection source, long cursorId,
      List<CursorUpdateOperation> operations, List<Row> changedRows,
      List<List<Integer>> changedColumnsList, List<Integer> changedRowIndexes)
      throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("executeCursorUpdate_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "executeCursorUpdate");

      this.clientService.executeCursorUpdate(cursorId, operations,
          changedRows, changedColumnsList, changedRowIndexes, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("executeCursorUpdate_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover possible
      handleException(t, null, false, false, true, "executeCursorUpdate");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void executeCursorUpdate(final HostConnection source, long cursorId,
      CursorUpdateOperation operation, Row changedRow,
      List<Integer> changedColumns, int changedRowIndex)
      throws SnappyException {
    executeCursorUpdate(source, cursorId,
        Collections.singletonList(operation),
        Collections.singletonList(changedRow),
        Collections.singletonList(changedColumns),
        Collections.singletonList(changedRowIndex));
  }

  public void startXATransaction(TransactionXid xid,
      int timeoutInSeconds, int flags) throws SnappyException {
    final HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("startXATransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      this.clientService.startXATransaction(source.connId, xid,
          timeoutInSeconds, flags, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("startXATransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, false, true, "startXATransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public int prepareXATransaction(final HostConnection source,
      TransactionXid xid) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("prepareXATransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      final int result = this.clientService.prepareXATransaction(source.connId,
          xid, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("prepareXATransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
      return result;
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, false, true, "prepareXATransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void commitXATransaction(final HostConnection source,
      TransactionXid xid, boolean onePhase) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("commitXATransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      this.clientService.commitXATransaction(source.connId, xid,
          onePhase, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("commitXATransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, false, true, "commitXATransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void rollbackXATransaction(final HostConnection source,
      TransactionXid xid) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("rollbackXATransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      this.clientService.rollbackXATransaction(source.connId, xid,
          source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("rollbackXATransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, false, true, "rollbackXATransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void forgetXATransaction(final HostConnection source,
      TransactionXid xid) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("forgetXATransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      this.clientService.forgetXATransaction(source.connId, xid, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("forgetXATransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, false, true, "forgetXATransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public void endXATransaction(final HostConnection source,
      TransactionXid xid, int flags) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("endXATransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      this.clientService.endXATransaction(source.connId, xid, flags,
          source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("endXATransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, false, true, "endXATransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public List<TransactionXid> recoverXATransaction(final HostConnection source,
      int flag) throws SnappyException {
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("reoverXATransaction_S", null,
          source.connId, source.token, ns, true, null);
    }
    super.lock();
    try {
      List<TransactionXid> recoveredIds = this.clientService
          .recoverXATransaction(source.connId, flag, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("reoverXATransaction_E", null,
            source.connId, source.token, ns, false, null);
      }
      return recoveredIds;
    } catch (Throwable t) {
      // no failover for transactions yet
      handleException(t, null, false, false, true, "reoverXATransaction");
      // never reached
      throw new AssertionError("unexpectedly reached end");
    } finally {
      super.unlock();
    }
  }

  public ServiceMetaData getServiceMetaData() throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getServiceMetaData_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        ServiceMetaData smd = this.clientService.getServiceMetaData(
            source.connId, source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getServiceMetaData_E", null,
              source.connId, source.token, ns, false, null);
        }
        return smd;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, true,
            "getServiceMetaData");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getSchemaMetaData(ServiceMetaDataCall schemaCall,
      ServiceMetaDataArgs metadataArgs) throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getSchemaMetaData_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getSchemaMetaData(schemaCall,
            metadataArgs);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getSchemaMetaData_E", null,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, true,
            "getSchemaMetaData");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getIndexInfo(ServiceMetaDataArgs metadataArgs,
      boolean unique, boolean approximate) throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getIndexInfo_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getIndexInfo(metadataArgs, unique,
            approximate);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getIndexInfo_E", null,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, true,
            "getIndexInfo");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getUDTs(ServiceMetaDataArgs metadataArgs,
      List<SnappyType> types) throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getUDTs_S", null, source.connId,
          source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getUDTs(metadataArgs, types);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getUDTs_E", null, source.connId,
              source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, true,
            "getUDTs");
      } finally {
        super.unlock();
      }
    }
  }

  public RowSet getBestRowIdentifier(ServiceMetaDataArgs metadataArgs,
      int scope, boolean nullable) throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("getBestRowIdentifier_S", null,
          source.connId, source.token, ns, true, null);
    }
    Set<HostAddress> failedServers = null;
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        metadataArgs.setConnId(source.connId).setToken(source.token);
        RowSet rs = this.clientService.getBestRowIdentifier(metadataArgs,
            scope, nullable);
        setSourceConnection(rs);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("getBestRowIdentifier_E", null,
              source.connId, source.token, ns, false, null);
        }
        return rs;
      } catch (Throwable t) {
        failedServers = handleException(t, failedServers, true, false, true,
            "getBestRowIdentifier");
      } finally {
        super.unlock();
      }
    }
  }

  public List<ConnectionProperties> fetchActiveConnections()
      throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("fetchActiveConnections_S", null,
          source.connId, source.token, ns, true, null);
    }
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        List<ConnectionProperties> conns = this.clientService
            .fetchActiveConnections(source.connId, source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("fetchActiveConnections_E", null,
              source.connId, source.token, ns, false, null);
        }
        return conns;
      } catch (Throwable t) {
        // no failover required
        handleException(t, null, false, false, true, "fetchActiveConnections");
      } finally {
        super.unlock();
      }
    }
  }

  public Map<Long, String> fetchActiveStatements() throws SnappyException {
    HostConnection source = this.currentHostConnection;
    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("fetchActiveStatements_S", null,
          source.connId, source.token, ns, true, null);
    }
    while (true) {
      super.lock();
      try {
        source = this.currentHostConnection;
        Map<Long, String> stmts = this.clientService
            .fetchActiveStatements(source.connId, source.token);
        if (SanityManager.TraceClientStatement) {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("fetchActiveStatements_E", null,
              source.connId, source.token, ns, false, null);
        }
        return stmts;
      } catch (Throwable t) {
        // no failover required
        handleException(t, null, false, false, true, "fetchActiveStatements");
      } finally {
        super.unlock();
      }
    }
  }

  public void cancelStatement(final HostConnection source, long stmtId)
      throws SnappyException {
    if (source == null) {
      return;
    }

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("cancelStatement_S", null,
          source.connId, source.token, ns, true, null);
    }
    // TODO: SW: use connection pooling for all operations including cancel

    // create a new connection to fire cancel since original statement
    // connection will be busy and locked; set load-balance to false
    OpenConnectionArgs connArgs = new OpenConnectionArgs(this.connArgs);
    Map<String, String> props = connArgs.getProperties();
    if (props == null) {
      props = new HashMap<>(1);
      connArgs.setProperties(props);
    }
    props.put(ClientAttribute.LOAD_BALANCE, "false");
    ClientService service = new ClientService(source.hostAddr, connArgs);
    try {
      if (stmtId == snappydataConstants.INVALID_ID) {
        // cancel currently active statement for the connection
        service.clientService.cancelCurrentStatement(source.connId,
            source.token);
      } else {
        service.clientService.cancelStatement(stmtId, source.token);
      }
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("cancelStatement_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover should be attempted
      service.handleException(t, null, false, false, false, "cancelStatement");
    } finally {
      service.closeService();
    }
  }

  public void closeResultSet(final HostConnection source, long cursorId,
      long lockTimeoutMillis) throws SnappyException {
    if (source == null || cursorId == snappydataConstants.INVALID_ID) {
      return;
    }

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("closeResultSet_S", null,
          source.connId, source.token, ns, true, null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      throw ThriftExceptionUtil.newSnappyException(
          SQLState.LOCK_TIMEOUT, null, source.toString());
    }
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "closeResultSet");

      this.clientService.closeResultSet(cursorId, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("closeResultSet_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover should be attempted and node failures ignored
      handleException(t, null, false, true, true, "closeResultSet");
    } finally {
      super.unlock();
    }
  }

  public void closeStatement(final HostConnection source, long stmtId,
      long lockTimeoutMillis) throws SnappyException {
    if (source == null || stmtId == snappydataConstants.INVALID_ID) {
      return;
    }

    if (SanityManager.TraceClientStatement) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("closeStatement_S", null,
          source.connId, source.token, ns, true, null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      throw ThriftExceptionUtil.newSnappyException(
          SQLState.LOCK_TIMEOUT, null, source.toString());
    }
    try {
      // check if node has failed sometime before
      checkUnexpectedNodeFailure(source, "closeStatement");

      this.clientService.closeStatement(stmtId, source.token);
      if (SanityManager.TraceClientStatement) {
        final long ns = System.nanoTime();
        SanityManager.DEBUG_PRINT_COMPACT("closeStatement_E", null,
            source.connId, source.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover should be attempted and node failures ignored
      handleException(t, null, false, true, true, "closeStatement");
    } finally {
      super.unlock();
    }
  }

  public void closeConnection(long lockTimeoutMillis) throws SnappyException {
    if (isClosed()) {
      return;
    }

    HostConnection source = this.currentHostConnection;
    if (source == null || source.connId == snappydataConstants.INVALID_ID) {
      closeService();
      return;
    }

    if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("closeConnection_S", null,
          source.connId, source.token, ns, true,
          SanityManager.TraceClientConn ? new Throwable() : null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      throw ThriftExceptionUtil.newSnappyException(
          SQLState.LOCK_TIMEOUT, null, source.toString());
    }
    try {
      if (isClosed()) {
        return;
      }

      source = this.currentHostConnection;
      if (source == null || source.connId == snappydataConstants.INVALID_ID) {
        return;
      }
      // closeSocket=true for now but will change with clientService pooling
      this.clientService.closeConnection(source.connId, true, source.token);
      if (SanityManager.TraceClientStatementHA
          | SanityManager.TraceClientConn) {
        if (SanityManager.TraceClientHA) {
          StringBuilder sb = new StringBuilder();
          sb.append("Closed connection @").append(hashCode()).append(" ID=")
              .append(source.connId);
          if (source.token != null && source.token.hasRemaining()) {
            sb.append('@');
            ClientSharedUtils.toHexString(source.token, sb);
          }
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              sb.toString());
        } else {
          final long ns = System.nanoTime();
          SanityManager.DEBUG_PRINT_COMPACT("closeConnection_E", null,
              source.connId, source.token, ns, false, null);
        }
      }
    } catch (TException te) {
      // ignore socket exception during connection close
    } catch (Throwable t) {
      // no failover should be attempted and node failures ignored
      handleException(t, null, false, true, false, "closeConnection");
      // succeed if above returns
    } finally {
      closeService();
      super.unlock();
    }
  }

  final void closeService() {
    final TTransport outTransport = this.clientService.getOutputProtocol()
        .getTransport();
    try {
      outTransport.close();
    } catch (Throwable t) {
      // ignore at this point
    }
    final TTransport inTransport = this.clientService.getInputProtocol()
        .getTransport();
    if (inTransport != outTransport) {
      try {
        inTransport.close();
      } catch (Throwable t) {
        // ignore at this point
      }
    }
    this.isClosed = true;
  }

  public boolean bulkClose(HostConnection thisSource,
      List<EntityId> entities, List<ClientService> closeServices,
      long lockTimeoutMillis) throws SnappyException {
    final HostConnection hostConn = this.currentHostConnection;
    if (thisSource == null || hostConn == null || !thisSource.equals(hostConn)) {
      throw new SnappyException(new SnappyExceptionData("Incorrect host = " +
          thisSource + ", current = " + hostConn, 0).setSqlState(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION), null);
    }

    if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
      final long ns = System.nanoTime();
      SanityManager.DEBUG_PRINT_COMPACT("bulkClose_S", null, hostConn.connId,
          hostConn.token, ns, true, null);
    }
    if (!acquireLock(lockTimeoutMillis)) {
      return false;
    }
    try {
      if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
        if (closeServices != null) {
          final long ns = System.nanoTime();
          HostConnection source;
          for (ClientService service : closeServices) {
            if (service == null
                || (source = service.currentHostConnection) == null) {
              continue;
            }
            SanityManager.DEBUG_PRINT_COMPACT("bulkCloseConnection_S", null,
                source.connId, source.token, ns, true,
                SanityManager.TraceClientConn ? new Throwable() : null);
          }
        }
      }

      this.clientService.bulkClose(entities);

      if (closeServices != null) {
        for (ClientService service : closeServices) {
          if (service != null) {
            service.closeService();
          }
        }
      }

      if (SanityManager.TraceClientStatement | SanityManager.TraceClientConn) {
        final long ns = System.nanoTime();
        if (closeServices != null) {
          HostConnection source;
          for (ClientService service : closeServices) {
            if (service == null
                || (source = service.currentHostConnection) == null) {
              continue;
            }
            SanityManager.DEBUG_PRINT_COMPACT("bulkCloseConnection_E", null,
                source.connId, source.token, ns, false, null);
            if (SanityManager.TraceClientHA) {
              StringBuilder sb = new StringBuilder();
              sb.append("Closed connection @").append(service.hashCode())
                  .append(" ID=").append(source.connId);
              if (source.token != null && source.token.hasRemaining()) {
                sb.append('@');
                ClientSharedUtils.toHexString(source.token, sb);
              }
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  sb.toString());
            }
          }
        }

        SanityManager.DEBUG_PRINT_COMPACT("bulkClose_S", null,
            hostConn.connId, hostConn.token, ns, false, null);
      }
    } catch (Throwable t) {
      // no failover should be attempted and node failures ignored
      handleException(t, null, false, true, false, "bulkClose");
    } finally {
      super.unlock();
    }
    return true;
  }

  public final void checkClosedConnection() throws SQLException {
    if (this.isClosed) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.NO_CURRENT_CONNECTION, null);
    }
  }
}
