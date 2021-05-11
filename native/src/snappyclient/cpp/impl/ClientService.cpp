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

#include "ClientService.h"

#include <boost/algorithm/string.hpp>
#include <boost/asio.hpp>
#include <boost/date_time/posix_time/posix_time.hpp>
#include <boost/filesystem.hpp>

#include <thrift/transport/TTransportException.h>
#include <thrift/transport/TSSLSocket.h>
#include <thrift/transport/TBufferTransports.h>
#include <thrift/protocol/TBinaryProtocol.h>
#include <thrift/protocol/TCompactProtocol.h>
#include <thrift/protocol/TProtocolException.h>

#include "common/SystemProperties.h"

#include "ClientProperty.h"
#include "ClientAttribute.h"
#include "Connection.h"

#include "SQLException.h"
#include "LogWriter.h"
#include "Utils.h"

#include "BufferedClientTransport.h"
#include "FramedClientTransport.h"
#include "DNSCacheService.h"
#include "InternalLogger.h"
#include "InternalUtils.h"
#include "ControlConnection.h"
#include "NetConnection.h"

extern "C" {
#ifdef _WINDOWS
#include <windows.h>
#else
#include <unistd.h>
#endif
}

using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;

namespace _snappy_impl {

struct CollectHostAddresses final {
  std::vector<thrift::HostAddress> &m_connHosts;

  void operator()(const std::string &str) {
    std::string host;
    int port;

    size_t oldSize = m_connHosts.size();
    m_connHosts.resize(oldSize + 1);
    thrift::HostAddress &hostAddr = m_connHosts[oldSize];
    Utils::getHostPort(str, host, port);
    Utils::getHostAddress(host, port, hostAddr);
  }
};

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

void DEFAULT_OUTPUT_FN(const char *str) {
  LogWriter::info() << str << _SNAPPY_NEWLINE;
}

bool ClientService::globalInitialize() {
  // s_globalLock should be held
  if (s_hostName.empty()) {
    // first initialize any utilities used by other parts of product
    InternalUtils::staticInitialize();
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
    boost::posix_time::ptime currentTime =
        boost::posix_time::microsec_clock::universal_time();
    s_hostId.append(boost::posix_time::to_simple_string(currentTime));
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
    apache::thrift::GlobalOutput.setOutputFunction(DEFAULT_OUTPUT_FN);

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
      se.exceptionData.sqlState, se.exceptionData.errorCode, se);
  if (status == FailoverStatus::NONE) {
    // convert DATA_CONTAINTER_CLOSED to "X0Z01" for non-transactional case
    if (m_isolationLevel == IsolationLevel::NONE
        && !se.exceptionData.sqlState.compare(
            SQLState::DATA_CONTAINER_CLOSED.getSQLState())) {
      throwSQLExceptionForNodeFailure(op, se);
    } else {
      throw GET_SQLEXCEPTION(se);
    }
  } else {
    throwSQLExceptionForNodeFailure(op, se);
  }
}

void ClientService::handleTTransportException(const char* op,
    bool tryFailover, bool ignoreNodeFailure, bool createNewConnection,
    std::set<thrift::HostAddress>& failedServers,
    const TTransportException& tte) {

  if (handleThriftException(op, tryFailover, ignoreNodeFailure,
      createNewConnection, failedServers, tte)) {
    throwSQLExceptionForNodeFailure(op, tte);
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

void ClientService::throwSQLExceptionForNodeFailure(const char* op,
    const std::exception& se) {
  destroyTransport();
  if (m_isolationLevel == IsolationLevel::NONE) {
    // throw X0Z01 for this case
    throw GET_SQLEXCEPTION2(SQLStateMessage::SNAPPY_NODE_SHUTDOWN_MSG,
        m_currentHostAddr.toString().c_str(), se, op);
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
    m_loadBalanceInitialized(false), m_reqdServerType(thrift::ServerType::THRIFT_SNAPPY_CP),
    m_useFramedTransport(false), m_serverGroups(), m_transport(),
    m_client(createDummyProtocol()), m_connHosts(), m_connId(0), m_token(),
    m_isOpen(false), m_connFailed(false), m_encryptedPasswords(false),
    m_pendingTXAttrs(), m_hasPendingTXAttrs(false),
    m_isolationLevel(IsolationLevel::NONE), m_lock() {
  std::map<std::string, std::string>& props = connArgs.properties;
  std::map<std::string, std::string>::iterator propValue;

  size_t oldSize = m_connHosts.size();
  m_connHosts.resize(oldSize + 1);
  thrift::HostAddress& hostAddr = m_connHosts[oldSize];
  Utils::getHostAddress(host, port, hostAddr);
  // mark the given host as the locator/server being connected to directly
  // so that search in the results will immediately pick up the same
  // locator/server that will have its isCurrent flag set by on the server-side
  hostAddr.__set_isCurrent(true);

  {
    // check if passwords are encrypted (including those of SSL keystore etc)
    if ((propValue = props.find(
        ClientAttribute::ENCRYPTED_PASSWORDS)) != props.end()) {
      m_encryptedPasswords = boost::iequals(propValue->second, "true");
      props.erase(propValue);
    }

    // need to keep outside if (!props.empty()) as comes empty if user
    // doesn't give any property
    bool hasLoadBalance = ((propValue = props.find(
        ClientAttribute::LOAD_BALANCE)) != props.end());
    //default for load-balance is true on locators and false on servers
    // so tentatively set as true and adjust using the ControlConnection
    if (hasLoadBalance) {
      m_loadBalance = (boost::iequals(propValue->second, "true"));
      props.erase(propValue);
      m_loadBalanceInitialized = true;
    }
  }
  if (!props.empty()) {
    // setup the original host list
    if ((propValue = props.find(ClientAttribute::SECONDARY_LOCATORS))
        != props.end()) {
      _snappy_impl::CollectHostAddresses addHostAddresses = { m_connHosts };
      InternalUtils::splitCSV(propValue->second, addHostAddresses);
      props.erase(propValue);
    }

    // read the server groups to use for connection
    if ((propValue = props.find(ClientAttribute::SERVER_GROUPS))
        != props.end()) {
      InternalUtils::splitCSV(propValue->second, [&](const std::string &s) {
        m_serverGroups.insert(s);
      });
      props.erase(propValue);
    }

    // read and verify the AQP properties
    if ((propValue = props.find(ClientAttribute::AQP_ERROR)) != props.end()) {
      try {
        double errorVal = boost::lexical_cast<double>(propValue->second);
        if (errorVal < 0.0 || errorVal > 1.0) {
          props.erase(propValue);
          throw std::invalid_argument("Invalid AQP Error value:");
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
        if (errorVal < 0.0 || errorVal > 1.0) {
          props.erase(propValue);
          throw std::invalid_argument("Invalid AQP Confidence value:");
        }
      } catch (const boost::bad_lexical_cast& ex) {
        props.erase(propValue);
        throw ex;
      }
    }
    if ((propValue = props.find(ClientAttribute::AQP_BEHAVIOR)) != props.end()) {
      if (propValue->second.empty()) {
        props.erase(propValue);
        throw std::invalid_argument("Invalid AQP Behavior value:");
      }
    }
    // now check for the protocol details like SSL etc
    // and reqd snappyServerType
    bool binaryProtocol = false;
    bool framedTransport = false;
    bool useSSL = false;

    if ((propValue = props.find(ClientAttribute::THRIFT_USE_BINARY_PROTOCOL))
        != props.end()) {
      binaryProtocol = boost::iequals(propValue->second, "true");
      props.erase(propValue);
    }
    if ((propValue = props.find(ClientAttribute::THRIFT_USE_FRAMED_TRANSPORT))
        != props.end()) {
      framedTransport = boost::iequals(propValue->second, "true");
      props.erase(propValue);
    }
    if ((propValue = props.find(ClientAttribute::SSL_PROPERTIES))
        != props.end()) {
      useSSL = true;
      SSLParameters sslParams;
      InternalUtils::splitCSV(propValue->second, [&](const std::string &str) {
        size_t spos;
        if ((spos = str.find('=')) != std::string::npos) {
          sslParams.setSSLProperty(str.substr(0, spos), str.substr(spos + 1));
        } else {
          sslParams.setSSLProperty(str, "");
        }
      });
      if (!m_sslFactory) {
        m_sslFactory.reset(new SSLSocketFactory(sslParams, m_encryptedPasswords));
      }
      props.erase(propValue);
    }
    if ((propValue = props.find(ClientAttribute::SSL)) != props.end()) {
      useSSL = boost::iequals(propValue->second, "true");
      // override ssl-properties if ssl-mode is explicitly set to false
      if (!useSSL) {
        m_sslFactory.reset(nullptr);
      }
      props.erase(propValue);
    }
    m_reqdServerType = getServerType(true, binaryProtocol, useSSL);
    m_useFramedTransport = framedTransport;
  }

  std::set<thrift::HostAddress> failedServers;
  std::string failure;
  openConnection(hostAddr, failedServers, failure);
}

void ClientService::openConnection(thrift::HostAddress &hostAddr,
    std::set<thrift::HostAddress> &failedServers, std::string &failure) {
  // open the connection
  std::thread::id tid;
  NanoTimeThread start;
  NanoDurationThread elapsed;
  if (TraceFlag::ClientStatementHA.global()
      | TraceFlag::ClientConn.global()) {
    start = InternalUtils::nanoTimeThread();
    tid = std::this_thread::get_id();
    std::unique_ptr<SQLException> ex(
        TraceFlag::ClientConn.global() ? new GET_SQLEXCEPTION(
            SQLState::UNKNOWN_EXCEPTION, "stack"): nullptr);
    InternalLogger::traceCompact(tid, "openConnection_S", nullptr, 0, true, 0,
        m_connId, m_token, ex.get());
  }

  while (true) {
    std::lock_guard<std::mutex> serviceGuard(m_lock);
    try {
      m_currentHostAddr = hostAddr;

      if (m_loadBalance || !m_loadBalanceInitialized) {
        ControlConnection &controlConn =
            ControlConnection::getOrCreateControlConnection(m_connHosts, this,
                failure);
        // if connected to the server then disable load-balance by default
        if (!m_loadBalanceInitialized) {
          // set default load-balance to false for servers and true for locators
          auto connectedHost = controlConn.getConnectedHost(hostAddr);
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
          controlConn.getPreferredServer(hostAddr, failure, failedServers,
              this->m_serverGroups, this, false);
        }

        m_currentHostAddr = hostAddr;
      }

      // first close any existing transport
      destroyTransport();

      std::shared_ptr<protocol::TProtocol> protocol(
          createProtocol(hostAddr, m_reqdServerType, m_useFramedTransport));
      m_client.resetProtocols(protocol, protocol);

      thrift::ConnectionProperties connProps;
      m_client.openConnection(connProps, m_connArgs);
      m_connId = connProps.connId;
      if (connProps.__isset.token) {
        m_token = connProps.token;
      }
      m_isOpen = true;
      m_connFailed = false;

      if (TraceFlag::ClientStatementHA.global()
          | TraceFlag::ClientConn.global()) {

        elapsed = (InternalUtils::nanoTimeThread() - start);
        InternalLogger::traceCompact(tid, "openConnection_E", nullptr, 0,
            false, elapsed.count(), m_connId, m_token);

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
    } catch (const SQLException &sqle) {
      throw sqle;
    } catch (const thrift::SnappyException& sqle) {
      handleSnappyException("openConnection", true, false, false,
          failedServers, sqle);
    } catch (const TTransportException& tte) {
      handleTTransportException("openConnection", true, false, false,
          failedServers, tte);
    } catch (const protocol::TProtocolException& tpe) {
      handleTProtocolException("openConnection", true, false, false,
          failedServers, tpe);
    } catch (const TException& te) {
      handleTException("openConnection", true, false, false, failedServers,
          te);
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

  auto &props = connArgs.properties;
  auto propValue = props.find(ClientAttribute::ENCRYPTED_PASSWORDS);
  // decrypt password if encrypted
  if (propValue != props.end() && boost::iequals(propValue->second, "true")) {
    std::string encryptedPassword;
    if (connArgs.__isset.password) {
      encryptedPassword = connArgs.password;
    } else if ((propValue = props.find(
        ClientAttribute::PASSWORD)) != props.end()) {
      encryptedPassword = propValue->second;
    }
    if (!encryptedPassword.empty()) {
      std::string user;
      if (connArgs.__isset.userName) {
        user = connArgs.userName;
      } else if ((propValue = props.find(
          ClientAttribute::USERNAME)) != props.end()) {
        user = propValue->second;
      } else if ((propValue = props.find(
          ClientAttribute::USERNAME_ALT)) != props.end()) {
        user = propValue->second;
      }
      if (connArgs.__isset.password) {
        connArgs.__set_password(Utils::decryptPassword(
            user, encryptedPassword));
      } else {
        props[ClientAttribute::PASSWORD] = Utils::decryptPassword(
            user, encryptedPassword);
      }
    }
  }

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

  int32_t rsz = SystemProperties::getInteger(
      ClientProperty::SOCKET_INPUT_BUFFER_SIZE_NAME,
      ClientProperty::DEFAULT_INPUT_BUFFER_SIZE);
  int32_t wsz = SystemProperties::getInteger(
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
    // m_lock should be held by caller
    if (!m_sslFactory) {
      m_sslFactory.reset(new SSLSocketFactory(m_encryptedPasswords));
    }
    socket = createSSLSocket(hostAddr.hostName, hostAddr.port, *m_sslFactory);
  } else {
    socket.reset(new TSocket(hostAddr.hostName, hostAddr.port));
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

void ClientService::execute(thrift::StatementResult& result,
    const std::string& sql,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

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

    if (!m_hasPendingTXAttrs) {
      m_client.executeQuery(result, m_connId, sql, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.executeQuery(result, m_connId, sql, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    //TODO :attrs.possibleDuplicate=false;
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
  // TODO: SW: need a separate connection for this to work
  // Preferably the whole class should be changed to use pool of connections
  // with key being server+port+connProps and a queue of pooled connections
  if (true) throw GET_SQLEXCEPTION(SQLState::FUNCTION_NOT_SUPPORTED,
      "cancelStatement not supported");
  std::set<thrift::HostAddress> failedServers;
  try {
    std::lock_guard<std::mutex> sync(m_lock);

    m_client.cancelStatement(stmtId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException("cancelStatement", false, false, false,
        failedServers, sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("cancelStatement", false, false, false,
        failedServers, tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("cancelStatement", false, false, false,
        failedServers, tpe);
  } catch (const TException& te) {
    handleTException("cancelStatement", false, false, false, failedServers,
        te);
  } catch (const std::exception& stde) {
    handleStdException("cancelStatement", stde);
  } catch (...) {
    handleUnknownException("cancelStatement");
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
    std::string &failure) {
  thrift::HostAddress host = this->m_currentHostAddr;

  auto ret = failedServers.insert(host);
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
    std::string failure(te.what());
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
