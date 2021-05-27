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

/**
 * ClientService.cpp
 */

#include "impl/pch.h"

#include "impl/ClientService.h"

#include "impl/BufferedClientTransport.h"
#include "impl/ControlConnection.h"
#include "impl/DNSCacheService.h"
#include "impl/FramedClientTransport.h"
#include "impl/InternalLogger.h"

#ifdef __GNUC__
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Woverloaded-virtual"
#pragma GCC diagnostic ignored "-Wshadow"
#pragma GCC diagnostic ignored "-Wsign-conversion"
#endif
#include <boost/asio/ip/host_name.hpp>
#include <boost/chrono/system_clocks.hpp>
#ifdef __GNUC__
#pragma GCC diagnostic pop
#endif

#include "Connection.h"

extern "C" {
#ifdef _WINDOWS
#include <libloaderapi.h>
#endif
}

// avoid importing TimeUtils that includes the slew of boost date_time headers
extern std::string initializeSnappyDataTime();

using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;

namespace _snappy_impl {

#ifdef _WINDOWS
static const std::vector<std::string> s_systemCertificateBundles = {
  "C:/SSL/certs/ca-certificates.crt",
  "C:/SSL/certs/ca-bundle.crt",
  "C:/SSL/cert.pem",
  "C:/Program Files/Common Files/SSL/certs/ca-certificates.crt",
  "C:/Program Files/Common Files/SSL/certs/ca-bundle.crt",
  "C:/Program Files/Common Files/SSL/cert.pem",
  "C:/Program Files (x86)/Common Files/SSL/certs/ca-certificates.crt",
  "C:/Program Files (x86)/Common Files/SSL/certs/ca-bundle.crt",
  "C:/Program Files (x86)/Common Files/SSL/cert.pem"
};

static const std::vector<std::string> s_systemCertificateDirs = {
  "C:/SSL/certs",
  "C:/Program Files/Common Files/SSL/certs",
  "C:/Program Files (x86)/Common Files/SSL/certs"
};
#else
static const std::vector<std::string> s_systemCertificateBundles = {
  "/etc/ssl/certs/ca-certificates.crt",
  "/etc/ssl/certs/ca-bundle.crt",
  "/etc/ssl/cert.pem",
  "/etc/openssl/certs/ca-certificates.crt",
  "/etc/openssl/certs/ca-bundle.crt",
  "/etc/openssl/cert.pem",
  "/usr/local/etc/ssl/certs/ca-certificates.crt",
  "/usr/local/etc/ssl/certs/ca-bundle.crt",
  "/usr/local/etc/ssl/cert.pem",
  "/usr/local/etc/openssl/certs/ca-certificates.crt",
  "/usr/local/etc/openssl/certs/ca-bundle.crt",
  "/usr/local/etc/openssl/cert.pem"
};

static const std::vector<std::string> s_systemCertificateDirs = {
  "/etc/ssl/certs", "/etc/openssl/certs",
  "/usr/local/etc/ssl/certs", "/usr/local/etc/openssl/certs"
};
#endif

static void DEFAULT_THRIFT_OUTPUT_FUNCTION(const char* str) {
  LogWriter::info() << str << _SNAPPY_NEWLINE;
}

/**
 * Read and erase boolean properties from the property bag. Meant to be
 * used for client-side only properties that are not to be sent to the server.
 */
static void readAndEraseBooleanValue(std::map<std::string, std::string>& props,
    const std::string& key, bool defaultValue, bool& result, bool* exists) {
  auto const propValue = props.find(key);
  if (propValue != props.end()) {
    if (exists) *exists = true;
    result = defaultValue ? !boost::iequals(propValue->second, "false")
        : boost::iequals(propValue->second, "true");
    props.erase(propValue);
  }
}

/**
 * Read and erase comma-separated properties from the property bag. Meant to be
 * used for client-side only properties that are not to be sent to the server.
 */
static void readAndEraseCSVValue(std::map<std::string, std::string>& props,
    const std::string& key, std::function<void(const std::string&)> proc) {
  auto const propValue = props.find(key);
  if (propValue != props.end()) {
    InternalUtils::splitCSV(propValue->second, proc);
    props.erase(propValue);
  }
}

}

extern "C" void initializeSnappyDataService() {
  ClientService::staticInitialize();
}

bool thrift::HostAddress::operator <(const HostAddress& other) const {
  const int32_t myPort = port;
  const int32_t otherPort = other.port;
  if (myPort != otherPort) {
    return (myPort < otherPort);
  }

  return (hostName < other.hostName);
}

std::string ClientService::s_hostName;
std::string ClientService::s_hostId;
std::mutex ClientService::s_globalLock;
bool ClientService::s_initialized = false;

bool ClientService::globalInitialize() {
  // s_globalLock should be held
  if (s_hostName.empty()) {
    // first initialize any utilities used by other parts of product
    std::string currentTime = initializeSnappyDataTime();
    // dummy call to just ensure SQLState is loaded first
    SQLState::staticInitialize();
    // then initialize the common message library
    SQLStateMessage::staticInitialize();
    // static process/library addresses to enable manual translation
    // of the addresses shown in the stacktrace
    SQLException::staticInitialize();
    // dummy call to ensure ClientAttribute is loaded
    ClientAttribute::staticInitialize();
    // and the logger
    LogWriter::staticInitialize();
    // the ConnectionProperty class
    ConnectionProperty::staticInitialize();
    // ensure ControlConnection is loaded with its statics
    ControlConnection::staticInitialize();

    s_hostName = boost::asio::ip::host_name();
    // use process ID and timestamp for ID
#ifdef _WINDOWS
    s_hostId = std::to_string(GetCurrentProcessId());
#else
    s_hostId = std::to_string(::getpid());
#endif
    s_hostId.push_back('|');
    s_hostId.append(currentTime);
    return true;
  } else {
    return false;
  }
}

void ClientService::staticInitialize() {
  std::lock_guard<std::mutex> sync(s_globalLock);
  globalInitialize();
}

void ClientService::staticInitialize(
    std::map<std::string, std::string>& props) {
  std::lock_guard<std::mutex> sync(s_globalLock);

  if (!s_initialized) {
    globalInitialize();
    LogWriter& globalLogger = LogWriter::global();
    std::string logFile, logLevelStr;
    LogLevel::type logLevel = globalLogger.getLogLevel();
    std::map<std::string, std::string>::iterator search;

    search = props.find(ClientAttribute::LOG_FILE);
    if (search != props.end()) {
      logFile = search->second;
      props.erase(search);
    }
    search = props.find(ClientAttribute::LOG_LEVEL);
    if (search != props.end()) {
      logLevel = LogLevel::fromString(search->second, globalLogger);
      logLevelStr = search->second;
      props.erase(search);
    }
    // now check the SystemProperties
    SystemProperties::getProperty(ClientProperty::LOG_FILE_NAME, logFile,
        logFile);
    if (SystemProperties::getProperty(ClientProperty::LOG_LEVEL_NAME,
        logLevelStr, logLevelStr)) {
      logLevel = LogLevel::fromString(logLevelStr, globalLogger);
    }

    globalLogger.initialize(logFile, logLevel);
    apache::thrift::GlobalOutput.setOutputFunction(
        _snappy_impl::DEFAULT_THRIFT_OUTPUT_FUNCTION);

    if (LogWriter::infoEnabled()) {
      LogWriter::info() << "Starting client on '" << s_hostName
          << "' with ID='" << s_hostId << '\'' << _SNAPPY_NEWLINE;
    }
    s_initialized = true;
  }
}

void ClientService::checkConnection(const char *op) {
  if (!m_isOpen || m_connFailed || !m_client.getProtocol() || !m_transport
      || !m_transport->isTransportOpen()) {
    throw GET_SQLEXCEPTION2(SQLStateMessage::NO_CURRENT_CONNECTION_MSG2,
        m_currentHostAddr.toString().c_str(), op);
  }
}

void ClientService::handleStdException(const char* op,
    const std::exception& stde, bool checkClosed) {
  if (checkClosed) checkConnection(op);

  std::ostringstream reason;
  reason << "(Server=" << m_currentHostAddr << ", operation=" << op << ") "
      << stde;
  throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason.str());
}

void ClientService::handleUnknownException(const char* op, bool checkClosed) {
  if (checkClosed) checkConnection(op);

  std::ostringstream reason;
  reason << "Unknown exception (Server=" << m_currentHostAddr << ", operation="
      << op << ')';
  throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason.str());
}

void ClientService::handleSnappyException(const char* op, bool tryFailover,
    bool ignoreNodeFailure, bool createNewConnection,
    std::set<thrift::HostAddress>& failedServers,
    const thrift::SnappyException& se) {

  if (!handleThriftException(op, tryFailover, ignoreNodeFailure,
      createNewConnection, failedServers, se)) {
    return;
  }

  FailoverStatus status = NetConnection::getFailoverStatus(
      se.exceptionData.sqlState, se);
  if (status == FailoverStatus::NONE) {
    // convert DATA_CONTAINTER_CLOSED to connection errors for non-transactional case
    if (m_isolationLevel == IsolationLevel::NONE
        && !se.exceptionData.sqlState.compare(
            SQLState::DATA_CONTAINER_CLOSED.getSQLState())) {
      throwSQLExceptionForNodeFailure(op, se, status);
    } else {
      throw GET_SQLEXCEPTION(se);
    }
  } else {
    throwSQLExceptionForNodeFailure(op, se, status);
  }
}

void ClientService::handleTTransportException(const char* op,
    bool tryFailover, bool ignoreNodeFailure, bool createNewConnection,
    std::set<thrift::HostAddress>& failedServers,
    const TTransportException& tte) {

  if (handleThriftException(op, tryFailover, ignoreNodeFailure,
      createNewConnection, failedServers, tte)) {
    throwSQLExceptionForNodeFailure(op, tte, FailoverStatus::NEW_SERVER);
  }
}

void ClientService::handleTProtocolException(const char* op, bool tryFailover,
    bool ignoreNodeFailure, bool createNewConnection,
    std::set<thrift::HostAddress>& failedServers,
    const protocol::TProtocolException& tpe) {

  if (handleThriftException(op, tryFailover, ignoreNodeFailure,
      createNewConnection, failedServers, tpe)) {
    throw GET_SQLEXCEPTION2(SQLStateMessage::THRIFT_PROTOCOL_ERROR_MSG,
        tpe.what(), op);
  }
}

void ClientService::handleTException(const char* op, bool tryFailover,
    bool ignoreNodeFailure, bool createNewConnection,
    std::set<thrift::HostAddress>& failedServers, const TException& te) {

  if (handleThriftException(op, tryFailover, ignoreNodeFailure,
      createNewConnection, failedServers, te)) {
    handleStdException(op, te, false);
  }
}

void ClientService::throwSQLExceptionForNodeFailure(const char *op,
    const std::exception &se, FailoverStatus status) {
  destroyTransport();
  if (m_isolationLevel == IsolationLevel::NONE) {
    // throw 08001 or 08004 for this case
    if (status == FailoverStatus::NEW_SERVER) {
      throw GET_SQLEXCEPTION2(SQLStateMessage::CONNECTION_FAILED_MSG,
          m_currentHostAddr.toString().c_str(), se, op);
    } else {
      throw GET_SQLEXCEPTION2(SQLStateMessage::CONNECTION_REJECTED_MSG,
          m_currentHostAddr.toString().c_str(), se, op);
    }
  } else {
    // throw 40XD0 for this case
    throw GET_SQLEXCEPTION2(SQLStateMessage::DATA_CONTAINER_CLOSED_MSG,
        m_currentHostAddr.toString().c_str(), se, op);
  }
}

void ClientService::clearPendingTransactionAttrs() {
  if (m_hasPendingTXAttrs) {
    m_pendingTXAttrs.clear();
    m_hasPendingTXAttrs = false;
    m_currentTXAttrs.clear();
  }
}

void ClientService::flushPendingTransactionAttrs() {
  // TODO: we could just do a send_set... here and for the subsequent
  // operation, then recv_ both in order taking care to catch exception
  // from first and invoke second in any case (server side is expected
  // fail second one too with "piggybacked=true") and then throw back
  // the exception from first at the end
  m_client.setTransactionAttributes(m_connId, m_pendingTXAttrs, m_token);
  clearPendingTransactionAttrs();
}

void ClientService::setPendingTransactionAttrs(
    thrift::StatementAttrs& stmtAttrs) {
  stmtAttrs.__set_pendingTransactionAttrs(m_pendingTXAttrs);
}

// using TBufferedTransport with TCompactProtocol to match the server
// settings; this could become configurable in future
ClientService::ClientService(const std::string& host, const int port,
    thrift::OpenConnectionArgs& connArgs) :
        // default for load-balance is false for servers and true for locators
        m_connArgs(initConnectionArgs(connArgs)), m_loadBalance(false),
        m_loadBalanceInitialized(false),
        m_reqdServerType(thrift::ServerType::THRIFT_SNAPPY_CP),
        m_useFramedTransport(false), m_serverGroups(), m_transport(),
        m_client(createDummyProtocol()), m_connHosts(), m_connId(0), m_token(),
        m_isOpen(false), m_connFailed(false), m_passwordsInManager(false),
        m_autoReconnect(false), m_pendingTXAttrs(), m_hasPendingTXAttrs(false),
        m_isolationLevel(IsolationLevel::NONE), m_lock() {

  std::map<std::string, std::string>& props = connArgs.properties;
  std::map<std::string, std::string>::iterator propValue;
  {
    m_connHosts.resize(1);
    // hostAddr may not be valid later when m_connHosts is resized
    thrift::HostAddress& hostAddr = m_connHosts[0];
    Utils::getHostAddress(host, port, hostAddr);
    // mark the given host as the locator/server being connected to directly
    // so that search in the results will immediately pick up the same
    // locator/server that will have its isCurrent flag set by the server-side
    hostAddr.__set_isCurrent(true);
  }

  if (!props.empty()) {
    // check if passwords are stored in the manager (including for SSL keystores)
    _snappy_impl::readAndEraseBooleanValue(props,
        ClientAttribute::CREDENTIAL_MANAGER, false, m_passwordsInManager,
        nullptr);
    if (m_passwordsInManager) {
      // update the password property with the actual value
      std::string passwordKey;
      if (connArgs.__isset.password) {
        passwordKey = connArgs.password;
      } else if ((propValue = props.find(ClientAttribute::PASSWORD))
          != props.end()) {
        passwordKey = propValue->second;
      }
      if (!passwordKey.empty()) {
        std::string user;
        if (connArgs.__isset.userName) {
          user = connArgs.userName;
        } else if ((propValue = props.find(ClientAttribute::USERNAME))
            != props.end()) {
          user = propValue->second;
        } else if ((propValue = props.find(ClientAttribute::USERNAME_ALT))
            != props.end()) {
          user = propValue->second;
        }
        if (connArgs.__isset.password) {
          connArgs.__set_password(
              Utils::readPasswordFromManager(user, passwordKey));
        } else {
          props[ClientAttribute::PASSWORD] = Utils::readPasswordFromManager(
              user, passwordKey);
        }
      }
    }
    // default for load-balance is true on locators and false on servers
    // so tentatively set as true and adjust using the ControlConnection
    _snappy_impl::readAndEraseBooleanValue(props, ClientAttribute::LOAD_BALANCE,
        true, m_loadBalance, &m_loadBalanceInitialized);
    // setup the original host list
    _snappy_impl::readAndEraseCSVValue(props,
        ClientAttribute::SECONDARY_LOCATORS, [&](const std::string& s) {
          std::string h;
          int p;
          size_t oldSize = m_connHosts.size();
          m_connHosts.resize(oldSize + 1);
          thrift::HostAddress& addr = m_connHosts[oldSize];
          Utils::getHostPort(s, h, p);
          Utils::getHostAddress(h, p, addr);
        });
    // set the auto-reconnect property
    _snappy_impl::readAndEraseBooleanValue(props,
        ClientAttribute::AUTO_RECONNECT, true, m_autoReconnect, nullptr);
    // read the server groups to use for connection (currently unused)
    _snappy_impl::readAndEraseCSVValue(props, ClientAttribute::SERVER_GROUPS,
        [&](const std::string& s) {
          m_serverGroups.insert(s);
        });
    // read and verify the AQP properties
    if ((propValue = props.find(ClientAttribute::AQP_ERROR)) != props.end()) {
      try {
        double errorVal = boost::lexical_cast<double>(propValue->second);
        if (errorVal <= 0.0 || errorVal >= 1.0) {
          props.erase(propValue);
          throw std::invalid_argument(
              "Invalid AQP Error value " + propValue->second
                  + " outside of (0.0, 1.0)");
        }
      } catch (const boost::bad_lexical_cast& ex) {
        props.erase(propValue);
        throw ex;
      }
    }
    if ((propValue = props.find(ClientAttribute::AQP_CONFIDENCE))
        != props.end()) {
      try {
        double errorVal = boost::lexical_cast<double>(propValue->second);
        if (errorVal <= 0.0 || errorVal >= 1.0) {
          props.erase(propValue);
          throw std::invalid_argument(
              "Invalid AQP Confidence value " + propValue->second
                  + " outside of (0.0, 1.0)");
        }
      } catch (const boost::bad_lexical_cast& ex) {
        props.erase(propValue);
        throw ex;
      }
    }
    if ((propValue = props.find(ClientAttribute::AQP_BEHAVIOR))
        != props.end()) {
      if (propValue->second.empty()) {
        props.erase(propValue);
        throw std::invalid_argument("Invalid empty AQP Behavior value");
      }
    }
    // now check for the protocol details like SSL etc and required ServerType
    bool binaryProtocol = false;
    bool framedTransport = false;
    bool useSSL = false;

    _snappy_impl::readAndEraseBooleanValue(props,
        ClientAttribute::THRIFT_USE_BINARY_PROTOCOL, false, binaryProtocol,
        nullptr);
    _snappy_impl::readAndEraseBooleanValue(props,
        ClientAttribute::THRIFT_USE_FRAMED_TRANSPORT, false, framedTransport,
        nullptr);
    // read "ssl-properties" first so that the "ssl" property can clear these
    // if it is explicitly set to false
    SSLParameters sslParams;
    _snappy_impl::readAndEraseCSVValue(props, ClientAttribute::SSL_PROPERTIES,
        [&](const std::string& str) {
          size_t spos;
          if ((spos = str.find('=')) != std::string::npos) {
            sslParams.setSSLProperty(str.substr(0, spos), str.substr(spos + 1));
          } else {
            sslParams.setSSLProperty(str, "");
          }
        });
    // override "ssl-properties" if "ssl" is explicitly set to false
    useSSL = !sslParams.empty();
    _snappy_impl::readAndEraseBooleanValue(props, ClientAttribute::SSL, false,
        useSSL, nullptr);
    if (useSSL && !m_sslFactory) {
      m_sslFactory.reset(
          new SSLSocketFactory(std::move(sslParams), m_passwordsInManager));
    }

    m_reqdServerType = getServerType(true, binaryProtocol, useSSL);
    m_useFramedTransport = framedTransport;
  }

  std::set<thrift::HostAddress> failedServers;
  SQLException failure(__FILE__, __LINE__);
  openConnection(connArgs, m_connHosts[0], false, failedServers, failure);
}

void ClientService::openConnection(const thrift::OpenConnectionArgs& connArgs,
    const thrift::HostAddress& hostAddr, bool mutexAlreadyLocked,
    std::set<thrift::HostAddress>& failedServers, SQLException& failure) {
  // open the connection
  std::thread::id tid;
  CHRONO_NANO_CLOCK::time_point start;
  CHRONO_NANO_CLOCK::duration elapsed;
  if (TraceFlag::ClientStatementHA.global() | TraceFlag::ClientConn.global()) {
    start = CHRONO_NANO_CLOCK::now();
    tid = std::this_thread::get_id();
    std::unique_ptr<SQLException> ex(
        TraceFlag::ClientConn.global() ? new GET_SQLEXCEPTION(
            SQLState::UNKNOWN_EXCEPTION, "stack") : nullptr);
    InternalLogger::traceCompact(tid, "openConnection_S", nullptr, 0, true, 0,
        m_connId, m_token, ex.get());
  }

  while (true) {
    auto serviceGuard = mutexAlreadyLocked ? std::unique_lock<std::mutex>()
        : std::unique_lock<std::mutex>(m_lock);
    try {
      m_currentHostAddr = hostAddr;

      if (m_loadBalance || !m_loadBalanceInitialized) {
        ControlConnection& controlConn =
            ControlConnection::getOrCreateControlConnection(m_connHosts, this,
                failure);
        // if connected to the server then disable load-balance by default
        if (!m_loadBalanceInitialized) {
          // set default load-balance to false for servers and true for locators
          auto connectedHost = controlConn.getConnectedHost(hostAddr, true);
          thrift::ServerType::type serverType = connectedHost.serverType;
          if (serverType == thrift::ServerType::THRIFT_LOCATOR_BP
              || serverType == thrift::ServerType::THRIFT_LOCATOR_BP_SSL
              || serverType == thrift::ServerType::THRIFT_LOCATOR_CP
              || serverType == thrift::ServerType::THRIFT_LOCATOR_CP_SSL) {
            m_loadBalance = true;
          }
          m_loadBalanceInitialized = true;
        }
        if (m_loadBalance) {
          // at this point query the control service for preferred server
          controlConn.getPreferredServer(m_currentHostAddr, failure,
              failedServers, this->m_serverGroups, this, false);
        }
      }

      // first close any existing transport
      destroyTransport();

      std::shared_ptr<protocol::TProtocol> protocol(
          createProtocol(m_currentHostAddr, m_reqdServerType,
              m_useFramedTransport));

      m_client.resetProtocols(protocol, protocol);

      thrift::ConnectionProperties connProps;
      m_client.openConnection(connProps, connArgs);
      m_connId = connProps.connId;
      if (connProps.__isset.token) {
        m_token = connProps.token;
      }
      m_isOpen = true;
      m_connFailed = false;

      if (TraceFlag::ClientStatementHA.global()
          | TraceFlag::ClientConn.global()) {

        elapsed = (CHRONO_NANO_CLOCK::now() - start);
        InternalLogger::traceCompact(tid, "openConnection_E", nullptr, 0, false,
            elapsed.count(), m_connId, m_token);

        if (TraceFlag::ClientHA.global()) {
          if (m_token.empty()) {
            LogWriter::trace(TraceFlag::ClientHA) << "Opened connection @"
                << (int64_t)this << " ID=" << m_connId;
          } else {
            LogWriter::trace(TraceFlag::ClientHA) << "Opened connection @"
                << (int64_t)this << " ID=" << m_connId << " @"
                << hexstr(m_token);
          }
        }
      }
      return;
    } catch (const SQLException& sqle) {
      throw sqle;
    } catch (const thrift::SnappyException& sqle) {
      handleSnappyException("openConnection", m_loadBalance, false, false,
          failedServers, sqle);
    } catch (const TTransportException& tte) {
      handleTTransportException("openConnection", m_loadBalance, false, false,
          failedServers, tte);
    } catch (const protocol::TProtocolException& tpe) {
      handleTProtocolException("openConnection", m_loadBalance, false, false,
          failedServers, tpe);
    } catch (const TException& te) {
      handleTException("openConnection", m_loadBalance, false, false,
          failedServers, te);
    } catch (const std::exception& stde) {
      handleStdException("openConnection", stde, false);
    } catch (...) {
      handleUnknownException("openConnection", false);
    }
  }
}

void ClientService::destroyTransport() noexcept {
  m_connFailed = true;
  Utils::handleExceptionsInDestructor("connection service", [&]() {
    ClientTransport *transport = m_transport.get();
    if (transport) {
      if (transport->isTransportOpen()) {
        transport->closeTransport();
      }
      m_transport = nullptr;
    }
  });
}

ClientService::~ClientService() {
  // destructor should *never* throw an exception
  m_sslFactory = nullptr;
  m_isOpen = false;
  Utils::handleExceptionsInDestructor("connection service", [&]() {
    ControlConnection::removeService(this);
  });
  destroyTransport();
}

thrift::OpenConnectionArgs& ClientService::initConnectionArgs(
    thrift::OpenConnectionArgs& connArgs) {
  // first initialize the library if required
  staticInitialize(connArgs.properties);

  // set the global hostName and hostId into connArgs
  connArgs.__set_clientHostName(s_hostName);
  std::ostringstream hostId;
  hostId << s_hostId << '|' << Utils::threadName << "<0x" << std::hex
      << std::this_thread::get_id() << std::dec << '>';
  connArgs.__set_clientID(hostId.str());
  // TODO: fixed security mechanism for now
  connArgs.__set_security(thrift::SecurityMechanism::PLAIN);

  return connArgs;
}

thrift::ServerType::type ClientService::getServerType(bool isServer,
    bool useBinaryProtocol, bool useSSL) {
  if (isServer) {
    if (useSSL) {
      return
          useBinaryProtocol ? thrift::ServerType::THRIFT_SNAPPY_BP_SSL :
              thrift::ServerType::THRIFT_SNAPPY_CP_SSL;
    } else {
      return
          useBinaryProtocol ? thrift::ServerType::THRIFT_SNAPPY_BP :
              thrift::ServerType::THRIFT_SNAPPY_CP;
    }
  } else if (useSSL) {
    return
        useBinaryProtocol ? thrift::ServerType::THRIFT_LOCATOR_BP_SSL :
            thrift::ServerType::THRIFT_LOCATOR_CP_SSL;
  } else {
    return
        useBinaryProtocol ? thrift::ServerType::THRIFT_LOCATOR_BP :
            thrift::ServerType::THRIFT_LOCATOR_CP;
  }
}

protocol::TProtocol* ClientService::createDummyProtocol() {
  return new protocol::TBinaryProtocol(std::make_shared<TMemoryBuffer>(0));
}

protocol::TProtocol* ClientService::createProtocol(
    thrift::HostAddress& hostAddr, const thrift::ServerType::type serverType,
    bool useFramedTransport) {
  bool useBinaryProtocol;
  bool useSSL;
  switch (serverType) {
    case thrift::ServerType::THRIFT_SNAPPY_CP:
    case thrift::ServerType::THRIFT_LOCATOR_CP:
      // these are default settings
      useBinaryProtocol = false;
      useSSL = false;
      break;
    case thrift::ServerType::THRIFT_SNAPPY_BP:
    case thrift::ServerType::THRIFT_LOCATOR_BP:
      useBinaryProtocol = true;
      useSSL = false;
      break;
    case thrift::ServerType::THRIFT_SNAPPY_CP_SSL:
    case thrift::ServerType::THRIFT_LOCATOR_CP_SSL:
      useBinaryProtocol = false;
      useSSL = true;
      break;
    case thrift::ServerType::THRIFT_SNAPPY_BP_SSL:
    case thrift::ServerType::THRIFT_LOCATOR_BP_SSL:
      useBinaryProtocol = true;
      useSSL = true;
      break;
    default:
      std::string reason("unexpected server type for thrift driver = ");
      reason.append(std::to_string((int)serverType));
      throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason);
  }

  uint32_t rsz = SystemProperties::getUInt32(
      ClientProperty::SOCKET_INPUT_BUFFER_SIZE_NAME,
      ClientProperty::DEFAULT_INPUT_BUFFER_SIZE);
  uint32_t wsz = SystemProperties::getUInt32(
      ClientProperty::SOCKET_OUTPUT_BUFFER_SIZE_NAME,
      ClientProperty::DEFAULT_OUTPUT_BUFFER_SIZE);

  // resolve the hostAddr using DNSCacheService to minimize DNS lookup
  // from hostname (when hostnames are being used)
  // it is also required in case hostname lookups are not working from
  // client-side and only IP addresses provided by servers are supposed
  // to work
  DNSCacheService::instance().resolve(hostAddr);

  std::shared_ptr<TSocket> socket;
  if (useSSL) {
    if (!m_sslFactory) {
      throw new std::runtime_error(
          "unexpected null SSLSocketFactory in ClientService::createProtocol");
    }
    socket = createSSLSocket(hostAddr.hostName, hostAddr.port,
        *m_sslFactory);
  } else {
    socket.reset(new TSocket(hostAddr.ipAddressOrHostName(), hostAddr.port));
  }

  // socket->setKeepAlive(false);
  BufferedClientTransport* bufferedTransport = new BufferedClientTransport(
      socket, rsz, wsz, false);
  // setup framed transport if configured
  if (useFramedTransport) {
    m_transport.reset(
        new FramedClientTransport(
            std::shared_ptr<BufferedClientTransport>(bufferedTransport),
            wsz));
  } else {
    m_transport.reset(bufferedTransport);
  }
  if (useBinaryProtocol) {
    return new protocol::TBinaryProtocol(
        std::dynamic_pointer_cast<TTransport>(m_transport));
  } else {
    return new protocol::TCompactProtocol(
        std::dynamic_pointer_cast<TTransport>(m_transport));
  }
}

void ClientService::reconnectToServerIfRequired(IsolationLevel checkIsolation,
    std::set<thrift::HostAddress> failedServers) {
  if (m_autoReconnect && m_connFailed && m_isOpen
      && m_isolationLevel <= checkIsolation) {
    SQLException failure(__FILE__, __LINE__);
    openConnection(m_connArgs, m_connHosts[0], true, failedServers, failure);
  }
}

void ClientService::execute(thrift::StatementResult& result,
    const std::string& sql,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (!m_hasPendingTXAttrs) {
      m_client.execute(result, m_connId, sql, outputParams, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.execute(result, m_connId, sql, outputParams, newAttrs,
          m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("execute", true, false, true, failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("execute", true, false, true, failedServers,
        tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("execute", true, false, true, failedServers,
        tpe);
  } catch (const TException& te) {
    handleTException("execute", true, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("execute", stde);
  } catch (...) {
    handleUnknownException("execute");
  }
}

void ClientService::executeUpdate(thrift::UpdateResult& result,
    const std::vector<std::string>& sqls,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (!m_hasPendingTXAttrs) {
      m_client.executeUpdate(result, m_connId, sqls, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.executeUpdate(result, m_connId, sqls, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("executeUpdate", true, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeUpdate", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeUpdate", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("executeUpdate", true, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("executeUpdate", stde);
  } catch (...) {
    handleUnknownException("executeUpdate");
  }
}

void ClientService::executeQuery(thrift::RowSet& result,
    const std::string& sql, const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (!m_hasPendingTXAttrs) {
      m_client.executeQuery(result, m_connId, sql, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.executeQuery(result, m_connId, sql, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    // TODO :attrs.possibleDuplicate=false;
    handleSnappyException("executeQuery", true, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeQuery", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeQuery", true, false, true, failedServers,
        tpe);
  } catch (const TException& te) {
    handleTException("executeQuery", true, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("executeQuery", stde);
  } catch (...) {
    handleUnknownException("executeQuery");
  }
}

void ClientService::prepareStatement(thrift::PrepareResult& result,
    const std::string& sql,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (!m_hasPendingTXAttrs) {
      m_client.prepareStatement(result, m_connId, sql, outputParams, attrs,
          m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.prepareStatement(result, m_connId, sql, outputParams, newAttrs,
          m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("prepareStatement", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("prepareStatement", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("prepareStatement", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("prepareStatement", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("prepareStatement", stde);
  } catch (...) {
    handleUnknownException("prepareStatement");
  }
}

void ClientService::executePrepared(thrift::StatementResult& result,
    thrift::PrepareResult& prepResult, const thrift::Row& params,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePrepared(result, prepResult.statementId, params,
        outputParams, attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("executePrepared", true, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePrepared", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePrepared", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("executePrepared", true, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("executePrepared", stde);
  } catch (...) {
    handleUnknownException("executePrepared");
  }
}

void ClientService::executePreparedUpdate(thrift::UpdateResult& result,
    thrift::PrepareResult& prepResult, const thrift::Row& params,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedUpdate(result, prepResult.statementId, params,
        attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("executePreparedUpdate", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedUpdate", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedUpdate", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("executePreparedUpdate", true, false, true,
        failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("executePreparedUpdate", stde);
  } catch (...) {
    handleUnknownException("executePreparedUpdate");
  }
}

void ClientService::executePreparedQuery(thrift::RowSet& result,
    thrift::PrepareResult& prepResult, const thrift::Row& params,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedQuery(result, prepResult.statementId, params,
        attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("executePreparedQuery", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedQuery", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedQuery", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("executePreparedQuery", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("executePreparedQuery", stde);
  } catch (...) {
    handleUnknownException("executePreparedQuery");
  }
}

void ClientService::executePreparedBatch(thrift::UpdateResult& result,
    thrift::PrepareResult& prepResult,
    const std::vector<thrift::Row>& paramsBatch,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedBatch(result, prepResult.statementId, paramsBatch,
        attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("executePreparedBatch", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedBatch", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedBatch", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("executePreparedBatch", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("executePreparedBatch", stde);
  } catch (...) {
    handleUnknownException("executePreparedBatch");
  }
}

void ClientService::prepareAndExecute(thrift::StatementResult& result,
    const std::string& sql, const std::vector<thrift::Row>& paramsBatch,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (!m_hasPendingTXAttrs) {
      m_client.prepareAndExecute(result, m_connId, sql, paramsBatch,
          outputParams, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.prepareAndExecute(result, m_connId, sql, paramsBatch,
          outputParams, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("prepareAndExecute", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("prepareAndExecute", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("prepareAndExecute", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("prepareAndExecute", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("prepareAndExecute", stde);
  } catch (...) {
    handleUnknownException("prepareAndExecute");
  }
}

void ClientService::getNextResultSet(thrift::RowSet& result,
    const int64_t cursorId, const int8_t otherResultSetBehaviour) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getNextResultSet(result, cursorId, otherResultSetBehaviour,
        m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getNextResultSet", false, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getNextResultSet", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getNextResultSet", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("getNextResultSet", false, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("getNextResultSet", stde);
  } catch (...) {
    handleUnknownException("getNextResultSet");
  }
}

void ClientService::getBlobChunk(thrift::BlobChunk& result,
    const int32_t lobId, const int64_t offset, const int32_t size,
    const bool freeLobAtEnd) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getBlobChunk(result, m_connId, lobId, offset, size, freeLobAtEnd,
        m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getBlobChunk", false, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getBlobChunk", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getBlobChunk", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("getBlobChunk", false, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("getBlobChunk", stde);
  } catch (...) {
    handleUnknownException("getBlobChunk");
  }
}

void ClientService::getClobChunk(thrift::ClobChunk& result,
    const int32_t lobId, const int64_t offset, const int32_t size,
    const bool freeLobAtEnd) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getClobChunk(result, m_connId, lobId, offset, size, freeLobAtEnd,
        m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getClobChunk", false, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getClobChunk", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getClobChunk", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("getClobChunk", false, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("getClobChunk", stde);
  } catch (...) {
    handleUnknownException("getClobChunk");
  }
}

int64_t ClientService::sendBlobChunk(thrift::BlobChunk& chunk) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    return m_client.sendBlobChunk(chunk, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("sendBlobChunk", false, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("sendBlobChunk", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("sendBlobChunk", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("sendBlobChunk", false, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("sendBlobChunk", stde);
  } catch (...) {
    handleUnknownException("sendBlobChunk");
  }
  // never reached
  return -1;
}

int64_t ClientService::sendClobChunk(thrift::ClobChunk& chunk) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    return m_client.sendClobChunk(chunk, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("sendClobChunk", false, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("sendClobChunk", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("sendClobChunk", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("sendClobChunk", false, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("sendClobChunk", stde);
  } catch (...) {
    handleUnknownException("sendClobChunk");
  }
  // never reached
  return -1;
}

void ClientService::freeLob(const int32_t lobId) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.freeLob(m_connId, lobId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("freeLob", false, true, false, failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("freeLob", false, true, false, failedServers,
        tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("freeLob", false, true, false, failedServers,
        tpe);
  } catch (const TException& te) {
    handleTException("freeLob", false, true, false, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("freeLob", stde);
  } catch (...) {
    handleUnknownException("freeLob");
  }
}

void ClientService::scrollCursor(thrift::RowSet& result,
    const int64_t cursorId, const int32_t offset, const bool offsetIsAbsolute,
    const bool fetchReverse, const int32_t fetchSize) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.scrollCursor(result, cursorId, offset, offsetIsAbsolute,
        fetchReverse, fetchSize, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("scrollCursor", false, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("scrollCursor", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("scrollCursor", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("scrollCursor", false, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("scrollCursor", stde);
  } catch (...) {
    handleUnknownException("scrollCursor");
  }
}

void ClientService::executeCursorUpdate(const int64_t cursorId,
    const thrift::CursorUpdateOperation::type operation,
    const thrift::Row& changedRow, const std::vector<int32_t>& changedColumns,
    const int32_t changedRowIndex) {
  executeBatchCursorUpdate(cursorId, Utils::singleVector(operation),
      Utils::singleVector(changedRow), Utils::singleVector(changedColumns),
      Utils::singleVector(changedRowIndex));
}

void ClientService::executeBatchCursorUpdate(const int64_t cursorId,
    const std::vector<thrift::CursorUpdateOperation::type>& operations,
    const std::vector<thrift::Row>& changedRows,
    const std::vector<std::vector<int32_t> >& changedColumnsList,
    const std::vector<int32_t>& changedRowIndexes) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executeCursorUpdate(cursorId, operations, changedRows,
        changedColumnsList, changedRowIndexes, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("executeBatchCursorUpdate", false, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeBatchCursorUpdate", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeBatchCursorUpdate", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("executeBatchCursorUpdate", false, false, true,
        failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("executeBatchCursorUpdate", stde);
  } catch (...) {
    handleUnknownException("executeBatchCursorUpdate");
  }
}

void ClientService::beginTransaction(const IsolationLevel isolationLevel) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NO_CHANGE, failedServers);
    m_client.beginTransaction(m_connId, static_cast<int8_t>(isolationLevel),
        m_pendingTXAttrs, m_token);
    clearPendingTransactionAttrs();
    m_isolationLevel = isolationLevel;
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("beginTransaction", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("beginTransaction", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("beginTransaction", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("beginTransaction", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("beginTransaction", stde);
  } catch (...) {
    handleUnknownException("beginTransaction");
  }
}

void ClientService::setTransactionAttribute(const TransactionAttribute flag,
    bool isTrue) {
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_pendingTXAttrs[static_cast<thrift::TransactionAttribute::type>(flag)] =
        isTrue;
    m_hasPendingTXAttrs = true;
  } catch (const std::exception& stde) {
    handleStdException("setTransactionAttribute", stde);
  }
}

bool ClientService::getTransactionAttribute(const TransactionAttribute flag) {
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    const thrift::TransactionAttribute::type attr =
        static_cast<thrift::TransactionAttribute::type>(flag);
    std::map<thrift::TransactionAttribute::type, bool>::const_iterator res;
    if (m_pendingTXAttrs.size() > 0
        && (res = m_pendingTXAttrs.find(attr)) != m_pendingTXAttrs.end()) {
      return res->second;
    } else {
      if (m_currentTXAttrs.size() > 0
          && (res = m_currentTXAttrs.find(attr)) != m_currentTXAttrs.end()) {
        return res->second;
      } else {
        getTransactionAttributesNoLock(m_currentTXAttrs);
        return m_currentTXAttrs[attr];
      }
    }
  } catch (const std::exception& stde) {
    handleStdException("getTransactionAttribute", stde);
  }
  return false;
}

void ClientService::getTransactionAttributesNoLock(
    std::map<thrift::TransactionAttribute::type, bool>& result) {
  std::set<thrift::HostAddress> failedServers;
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getTransactionAttributes(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getTransactionAttributes", false, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getTransactionAttributes", false, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getTransactionAttributes", false, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("getTransactionAttributes", false, false, true,
        failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("getTransactionAttributes", stde);
  } catch (...) {
    handleUnknownException("getTransactionAttributes");
  }
}

void ClientService::getTransactionAttributes(
    std::map<thrift::TransactionAttribute::type, bool>& result) {
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    getTransactionAttributesNoLock(result);
  } catch (const std::exception& stde) {
    handleStdException("getTransactionAttributes", stde);
  }
}

void ClientService::commitTransaction(const bool startNewTransaction) {
  std::set<thrift::HostAddress> failedServers;
  bool tryFailOver = false;
  if (m_isolationLevel == IsolationLevel::NONE) tryFailOver = true;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_client.commitTransaction(m_connId, startNewTransaction,
        m_pendingTXAttrs, m_token);
    clearPendingTransactionAttrs();
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("commitTransaction", tryFailOver, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("commitTransaction", tryFailOver, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("commitTransaction", tryFailOver, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("commitTransaction", tryFailOver, false, true,
        failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("commitTransaction", stde);
  } catch (...) {
    handleUnknownException("commitTransaction");
  }
}

void ClientService::rollbackTransaction(const bool startNewTransaction) {
  std::set<thrift::HostAddress> failedServers;
  bool tryFailOver = false;
  if (m_isolationLevel == IsolationLevel::NONE) tryFailOver = true;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_client.rollbackTransaction(m_connId, startNewTransaction,
        m_pendingTXAttrs, m_token);
    clearPendingTransactionAttrs();
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("rollbackTransaction", tryFailOver, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("rollbackTransaction", tryFailOver, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("rollbackTransaction", tryFailOver, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("rollbackTransaction", tryFailOver, false, true,
        failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("rollbackTransaction", stde);
  } catch (...) {
    handleUnknownException("rollbackTransaction");
  }
}

void ClientService::fetchActiveConnections(
    std::vector<thrift::ConnectionProperties>& result) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.fetchActiveConnections(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("fetchActiveConnections", false, false, false,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("fetchActiveConnections", false, false, false,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("fetchActiveConnections", false, false, false,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("fetchActiveConnections", false, false, false,
        failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("fetchActiveConnections", stde);
  } catch (...) {
    handleUnknownException("fetchActiveConnections");
  }
}

void ClientService::fetchActiveStatements(
    std::map<int64_t, std::string>& result) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.fetchActiveStatements(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("fetchActiveStatements", false, false, false,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("fetchActiveStatements", false, false, false,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("fetchActiveStatements", false, false, false,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("fetchActiveStatements", false, false, false,
        failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("fetchActiveStatements", stde);
  } catch (...) {
    handleUnknownException("fetchActiveStatements");
  }
}

void ClientService::getServiceMetaData(thrift::ServiceMetaData& result) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getServiceMetaData(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getServiceMetaData", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getServiceMetaData", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getServiceMetaData", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("getServiceMetaData", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("getServiceMetaData", stde);
  } catch (...) {
    handleUnknownException("getServiceMetaData");
  }
}

void ClientService::getSchemaMetaData(thrift::RowSet& result,
    const thrift::ServiceMetaDataCall::type schemaCall,
    thrift::ServiceMetaDataArgs& metadataArgs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getSchemaMetaData(result, schemaCall, metadataArgs);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getSchemaMetaData", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getSchemaMetaData", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getSchemaMetaData", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("getSchemaMetaData", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("getSchemaMetaData", stde);
  } catch (...) {
    handleUnknownException("getSchemaMetaData");
  }
}

void ClientService::getIndexInfo(thrift::RowSet& result,
    thrift::ServiceMetaDataArgs& metadataArgs, const bool unique,
    const bool approximate) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getIndexInfo(result, metadataArgs, unique, approximate);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getIndexInfo", true, false, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getIndexInfo", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getIndexInfo", true, false, true, failedServers,
        tpe);
  } catch (const TException& te) {
    handleTException("getIndexInfo", true, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("getIndexInfo", stde);
  } catch (...) {
    handleUnknownException("getIndexInfo");
  }
}

void ClientService::getUDTs(thrift::RowSet& result,
    thrift::ServiceMetaDataArgs& metadataArgs,
    const std::vector<thrift::SnappyType::type>& types) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getUDTs(result, metadataArgs, types);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getUDTs", true, false, true, failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getUDTs", true, false, true, failedServers,
        tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getUDTs", true, false, true, failedServers,
        tpe);
  } catch (const TException& te) {
    handleTException("getUDTs", true, false, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("getUDTs", stde);
  } catch (...) {
    handleUnknownException("getUDTs");
  }
}

void ClientService::getBestRowIdentifier(thrift::RowSet& result,
    thrift::ServiceMetaDataArgs& metadataArgs, const int32_t scope,
    const bool nullable) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    reconnectToServerIfRequired(IsolationLevel::NONE, failedServers);
    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getBestRowIdentifier(result, metadataArgs, scope, nullable);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("getBestRowIdentifier", true, false, true,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getBestRowIdentifier", true, false, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getBestRowIdentifier", true, false, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("getBestRowIdentifier", true, false, true, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("getBestRowIdentifier", stde);
  } catch (...) {
    handleUnknownException("getBestRowIdentifier");
  }
}

void ClientService::closeResultSet(const int64_t cursorId) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_client.closeResultSet(cursorId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("closeResultSet", false, true, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("closeResultSet", false, true, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("closeResultSet", false, true, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("closeResultSet", false, true, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("closeResultSet", stde);
  } catch (...) {
    handleUnknownException("closeResultSet");
  }
}

void ClientService::cancelStatement(const int64_t stmtId) {
  // TODO: SW: use connection pooling for all operations including cancel

  // create a new connection to fire cancel since original statement
  // connection will be busy and locked; set load-balance to false
  thrift::OpenConnectionArgs connArgs(m_connArgs);
  connArgs.properties[ClientAttribute::LOAD_BALANCE] = "false";
  std::string host = m_currentHostAddr.hostName;
  if (m_currentHostAddr.__isset.ipAddress) {
    host.push_back('/');
    host.append(m_currentHostAddr.ipAddress);
  }
  std::unique_ptr<ClientService> service(
      new ClientService(host, m_currentHostAddr.port, connArgs));
  std::set<thrift::HostAddress> failedServers;
  try {
    if (stmtId == thrift::snappydataConstants::INVALID_ID) {
      // cancel currently active statement for the connection
      service->m_client.cancelCurrentStatement(m_connId, m_token);
    } else {
      service->m_client.cancelStatement(stmtId, m_token);
    }
    service->close();
  } catch (const thrift::SnappyException& sqle) {
    service->handleSnappyException("cancelStatement", false, false, false,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    service->handleTTransportException("cancelStatement", false, false, false,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    service->handleTProtocolException("cancelStatement", false, false, false,
        failedServers, tpe);
  } catch (const TException& te) {
    service->handleTException("cancelStatement", false, false, false,
        failedServers, te);
  } catch (const std::exception& stde) {
    service->handleStdException("cancelStatement", stde);
  } catch (...) {
    service->handleUnknownException("cancelStatement");
  }
}

void ClientService::closeStatement(const int64_t stmtId) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_client.closeStatement(stmtId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("closeStatement", false, true, true, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("closeStatement", false, true, true,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("closeStatement", false, true, true,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("closeStatement", false, true, true, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("closeStatement", stde);
  } catch (...) {
    handleUnknownException("closeStatement");
  }
}

void ClientService::bulkClose(const std::vector<thrift::EntityId>& entities) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_client.bulkClose(entities);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("bulkClose", false, true, false, failedServers,
        sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("bulkClose", false, true, false,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("bulkClose", false, true, false,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("bulkClose", false, true, false, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("bulkClose", stde);
  } catch (...) {
    handleUnknownException("bulkClose");
  }
}

void ClientService::close() {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_isOpen = false;
    ClientTransport* transport = m_transport.get();
    if (transport) {
      m_client.closeConnection(m_connId, true, m_token);
      if (transport->isTransportOpen()) {
        transport->closeTransport();
      }
      m_transport = nullptr;
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("close", false, true, false, failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("close", false, true, false, failedServers,
        tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("close", false, true, false, failedServers, tpe);
  } catch (const TException& te) {
    handleTException("close", false, true, false, failedServers, te);
  } catch (const std::exception& stde) {
    handleStdException("close", stde);
  } catch (...) {
    handleUnknownException("close");
  }
}

void ClientService::updateFailedServersForCurrent(
    std::set<thrift::HostAddress> &failedServers, bool checkAllFailed,
    SQLException &failure) {
  auto ret = failedServers.insert(m_currentHostAddr);
  try {
    if (!ret.second && checkAllFailed) {
      ControlConnection &controlService =
          ControlConnection::getOrCreateControlConnection(m_connHosts, this,
              failure);
      thrift::HostAddress pHost;
      controlService.searchRandomServer(failedServers, failure, pHost);
    }
  } catch (...) {
    // ignore any exceptions during update of failed server list
  }
}

bool ClientService::handleThriftException(const char* op, bool tryFailover,
    bool ignoreNodeFailure, bool createNewConnection,
    std::set<thrift::HostAddress>& failedServers, const TException& te) {

  bool result = true;
  if (createNewConnection) {
    checkConnection(op);
  }
  if (!m_loadBalance || m_isolationLevel != IsolationLevel::NONE) {
    tryFailover = false;
  }
  if (tryFailover && ignoreNodeFailure) {
    SQLException failure(std::move(GET_SQLEXCEPTION2(
            SQLStateMessage::CONNECTION_FAILED_MSG,
            m_currentHostAddr.toString().c_str(), te, op)));
    updateFailedServersForCurrent(failedServers, true, failure);
    result = false;
  }
  /*
  // [sumedh] New connection will not inherit session properties,
  // current schema etc, so skipping this for now.

  // create a new connection in any case for future operations
  if (createNewConnection && m_loadBalance) {
    updateFailedServersForCurrent(failedServers, false, se.what());
    openConnection(m_currentHostAddr, failedServers, se);
  }
  */
  return result;
}

std::shared_ptr<TSSLSocket> ClientService::createSSLSocket(
    const std::string &host, int port, SSLSocketFactory &sslSocketFactory) {
  std::string sslProperty = sslSocketFactory.getSSLPropertyName(
      SSLProperty::CLIENTAUTH);
  std::string propVal = sslSocketFactory.getSSLPropertyValue(sslProperty);
  if (!propVal.empty() && boost::iequals(propVal, "true")) {
    sslSocketFactory.authenticate(true);
    sslProperty = sslSocketFactory.getSSLPropertyName(SSLProperty::KEYSTORE);
    propVal = sslSocketFactory.getSSLPropertyValue(sslProperty);
    if (!propVal.empty()) {
      sslSocketFactory.loadPrivateKey(propVal.c_str());
    }
    sslProperty = sslSocketFactory.getSSLPropertyName(SSLProperty::CERTIFICATE);
    propVal = sslSocketFactory.getSSLPropertyValue(sslProperty);
    if (!propVal.empty()) {
      sslSocketFactory.loadCertificate(propVal.c_str());
    }
  } else {
    sslSocketFactory.authenticate(false);
  }

  sslProperty = sslSocketFactory.getSSLPropertyName(SSLProperty::TRUSTSTORE);
  propVal = sslSocketFactory.getSSLPropertyValue(sslProperty);
  if (!propVal.empty()) {
    // check if provided value is a directory
    boost::filesystem::path certPath = InternalUtils::getPath(propVal);
    if (boost::filesystem::exists(certPath)
        && boost::filesystem::is_directory(certPath)) {
      sslSocketFactory.loadTrustedCertificates(nullptr, propVal.c_str());
    } else {
      sslSocketFactory.loadTrustedCertificates(propVal.c_str(), nullptr);
    }
  } else {
    // try to load system installed certificates if available
    bool loaded = false;
    for (auto &certFile : _snappy_impl::s_systemCertificateBundles) {
      boost::filesystem::path certPath = InternalUtils::getPath(certFile);
      if (boost::filesystem::exists(certPath)) {
        sslSocketFactory.loadTrustedCertificates(certFile.c_str(), nullptr);
        loaded = true;
        break;
      }
    }
    if (!loaded) {
      for (auto &certDir : _snappy_impl::s_systemCertificateDirs) {
        boost::filesystem::path certPath = InternalUtils::getPath(certDir);
        if (boost::filesystem::exists(certPath)
            && boost::filesystem::is_directory(certPath)) {
          sslSocketFactory.loadTrustedCertificates(nullptr, certDir.c_str());
          loaded = true;
          break;
        }
      }
    }
#ifdef _WINDOWS
    // if no system installed certificates can be found then try searching
    // in product installation directory
    if (!loaded) {
      char dllPath[MAX_PATH];
      DWORD pathSize;
      HMODULE hm = nullptr;
      if (::GetModuleHandleEx(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS
          | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
          (LPCSTR)&initializeSnappyDataService, &hm) != 0
          && (pathSize = ::GetModuleFileName(hm, dllPath, sizeof(dllPath))) != 0
          && pathSize < sizeof(dllPath)) {
        boost::filesystem::path dllDir =
            InternalUtils::getPath(dllPath).parent_path();
        boost::filesystem::path certPath(dllDir);
        certPath += L"/ca-certificates.crt";
        if (boost::filesystem::exists(certPath)) {
          sslSocketFactory.loadTrustedCertificates(
              certPath.string().c_str(), nullptr);
          loaded = true;
        } else {
          certPath = dllDir;
          certPath += L"/../ca-certificates.crt";
          if (boost::filesystem::exists(certPath)) {
            sslSocketFactory.loadTrustedCertificates(
                certPath.string().c_str(), nullptr);
            loaded = true;
          }
        }
      }
    }
#endif
  }
  sslProperty = sslSocketFactory.getSSLPropertyName(SSLProperty::CIPHERSUITES);
  propVal = sslSocketFactory.getSSLPropertyValue(sslProperty);
  if (!propVal.empty()) {
    sslSocketFactory.ciphers(propVal);
  } else {
    sslSocketFactory.ciphers("ALL:!ADH:!LOW:!EXP:!MD5:@STRENGTH");
  }

  return sslSocketFactory.createSocket(host, port);
}
