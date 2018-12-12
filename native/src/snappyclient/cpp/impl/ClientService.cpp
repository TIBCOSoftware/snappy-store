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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
#include <boost/make_shared.hpp>
#include <boost/asio.hpp>
#include <boost/log/attributes/current_process_id.hpp>
#include <boost/date_time/posix_time/posix_time.hpp>
#include <boost/thread/thread.hpp>
#include <boost/thread/lock_guard.hpp>

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
using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;

namespace _snappy_impl {
  struct CollectHostAddresses {
    std::vector<thrift::HostAddress>& m_connHosts;

    void operator()(const std::string& str) {
      std::string host;
      int port;
      thrift::HostAddress hostAddr;

      Utils::getHostPort(str, host, port);
      Utils::getHostAddress(host, port, hostAddr);
      m_connHosts.push_back(hostAddr);
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
boost::mutex ClientService::s_globalLock;
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
    // dummy call to ensure ClientAttribute is loaded
    ClientAttribute::staticInitialize();
    // and the logger
    LogWriter::staticInitialize();
    // lastly the ConnectionProperty class
    ConnectionProperty::staticInitialize();

    s_hostName = boost::asio::ip::host_name();
    // use process ID and timestamp for ID
    boost::log::process_id::native_type pid =
        boost::log::attributes::current_process_id().get().native_id();
    s_hostId = std::to_string(pid);
    s_hostId.append(1, '|');
    boost::posix_time::ptime currentTime =
        boost::posix_time::microsec_clock::universal_time();
    s_hostId.append(boost::posix_time::to_simple_string(currentTime));
    return true;
  } else {
    return false;
  }
}

void ClientService::staticInitialize() {
  boost::lock_guard<boost::mutex> sync(s_globalLock);
  globalInitialize();
}

void ClientService::staticInitialize(
    std::map<std::string, std::string>& props) {
  boost::lock_guard<boost::mutex> sync(s_globalLock);

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

void ClientService::checkConnection(const char* op) {
  protocol::TProtocol* protocol = m_client.getProtocol();
  boost::shared_ptr<transport::TTransport> transport;
  if (protocol == NULL || (transport = protocol->getTransport()) == NULL
      || !transport->isOpen()) {
    std::ostringstream server;
    Utils::toStream(server, m_currentHostAddr);
    throw GET_SQLEXCEPTION2(SQLStateMessage::NO_CURRENT_CONNECTION_MSG2,
        server.str().c_str(), op);
  }
}

void ClientService::handleStdException(const char* op,
    const std::exception& stde) {
  std::ostringstream reason;
  reason << "(Server=";
  Utils::toStream(reason, m_currentHostAddr) << ", operation=" << op << ") ";
  Utils::toStream(reason, stde);
  throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason.str());
}

void ClientService::handleUnknownException(const char* op) {
  checkConnection(op);

  std::string reason;
  reason.append("Unknown exception in operation ").append(op);
  throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason.c_str());
}

void ClientService::handleSnappyException(const thrift::SnappyException& se) {
  throw GET_SQLEXCEPTION(se);
}

void ClientService::handleTTransportException(const char* op,
    const TTransportException& tte) {
  checkConnection(op);

  throwSQLExceptionForNodeFailure(op, tte);
}

void ClientService::handleTProtocolException(const char* op,
    const protocol::TProtocolException& tpe) {
  checkConnection(op);

  throw GET_SQLEXCEPTION2(SQLStateMessage::THRIFT_PROTOCOL_ERROR_MSG,
      tpe.what(), op);
}

void ClientService::handleTException(const char* op, const TException& te) {
  checkConnection(op);

  handleStdException(op, te);
}
/*
void ClientService::handleException(const TException* te,
    const std::set<thrift::HostAddress>& failedServers, bool tryFailover, bool ignoreFailOver,
    bool createNewConnection, const std::string& op)
{

}*/

void ClientService::throwSQLExceptionForNodeFailure(const char* op,
    const std::exception& se) {
  std::ostringstream hostAddrStr;
  Utils::toStream(hostAddrStr, m_currentHostAddr);
  if (m_isolationLevel == IsolationLevel::NONE) {
    // throw X0Z01 for this case
    throw GET_SQLEXCEPTION2(SQLStateMessage::SNAPPY_NODE_SHUTDOWN_MSG,
        hostAddrStr.str().c_str(), se, op);
  } else {
    // throw 40XD0 for this case
    throw GET_SQLEXCEPTION2(SQLStateMessage::DATA_CONTAINER_CLOSED_MSG,
        hostAddrStr.str().c_str(), se, op);
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
            // default for load-balance is true
            m_connArgs(initConnectionArgs(connArgs)), m_loadBalance(true),
            m_reqdServerType(thrift::ServerType::THRIFT_SNAPPY_CP),
            m_useFramedTransport(false), m_serverGroups(),
            m_transport(), m_client(createDummyProtocol()),
            m_connHosts(1), m_connId(0), m_token(), m_isOpen(false),
            m_pendingTXAttrs(), m_hasPendingTXAttrs(false),
            m_isolationLevel(IsolationLevel::NONE), m_lock() {
  std::map<std::string, std::string>& props = connArgs.properties;
  std::map<std::string, std::string>::iterator propValue;

  thrift::HostAddress hostAddr;
  Utils::getHostAddress(host, port, hostAddr);

  m_connHosts.push_back(hostAddr);

  if (!props.empty()) {
    if ((propValue = props.find(ClientAttribute::LOAD_BALANCE))
        != props.end()) {
      m_loadBalance = !(boost::iequals("false", propValue->second));
      props.erase(propValue);
    }

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
      InternalUtils::CollectStrings<typename std::set<std::string> > cs(
          m_serverGroups);
      InternalUtils::splitCSV(propValue->second, cs);
      props.erase(propValue);
    }

    // now check for the protocol details like SSL etc
    // and reqd snappyServerType
    bool binaryProtocol = false;
    bool framedTransport = false;
    bool useSSL = false;
    //SSLSocketParameters sslParams = null;
    std::map<std::string, std::string>::iterator propValue;

    std::map<std::string, std::string>& props = connArgs.properties;
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
    if ((propValue = props.find(ClientAttribute::SSL)) != props.end()) {
      useSSL = boost::iequals(propValue->second, "true");
      props.erase(propValue);
    }
    if ((propValue = props.find(ClientAttribute::SSL_PROPERTIES))
        != props.end()) {
      useSSL = true;
      // TODO: SW: SSL params support
      //sslParams = Utils::getSSLParameters(propValue->second);
      props.erase(propValue);
    }
    m_reqdServerType = getServerType(true, binaryProtocol, useSSL);
    m_useFramedTransport = framedTransport;
  }

  std::set<thrift::HostAddress> failedServers;
  openConnection(hostAddr, failedServers);
}

void ClientService::openConnection(thrift::HostAddress& hostAddr,
    std::set<thrift::HostAddress>& failedServers) {
  // open the connection
  boost::thread::id tid;
  NanoTimeThread start;
  NanoDurationThread elapsed;
  if (TraceFlag::ClientStatementHA.global() | TraceFlag::ClientConn.global()) {
    start = InternalUtils::nanoTimeThread();
    tid = boost::this_thread::get_id();
    std::unique_ptr<SQLException> ex(
        TraceFlag::ClientConn.global() ? new GET_SQLEXCEPTION(
            SQLState::UNKNOWN_EXCEPTION, "stack"): NULL);
    InternalLogger::traceCompact(tid, "openConnection_S", NULL, 0, true, 0,
        m_connId, m_token, ex.get());
  }

  while (true) {
    boost::lock_guard<boost::mutex> serviceGuard(m_lock);
    if (m_loadBalance) {
      boost::optional<ControlConnection&> controlService = ControlConnection::getOrCreateControlConnection(m_connHosts,this,nullptr);
      // at this point query the control service for preferred server
      controlService->getPreferredServer(hostAddr ,nullptr,failedServers,this->m_serverGroups, false);
    }

    try {
      // first close any existing transport
      destroyTransport();

      boost::shared_ptr<protocol::TProtocol> protocol(createProtocol(
          hostAddr, m_reqdServerType, m_useFramedTransport, m_transport));
      m_client.resetProtocols(protocol, protocol);

      thrift::ConnectionProperties connProps;
      m_client.openConnection(connProps, m_connArgs);
      m_connId = connProps.connId;
      if (connProps.__isset.token) {
        m_token = connProps.token;
      }
      m_currentHostAddr = hostAddr;
      m_isOpen = true;

      if (TraceFlag::ClientStatementHA.global()
          | TraceFlag::ClientConn.global()) {

        elapsed = (InternalUtils::nanoTimeThread() - start);
        InternalLogger::traceCompact(tid, "openConnection_E", NULL, 0,
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
    } catch (const thrift::SnappyException& sqle) {
      handleSnappyException(sqle);
    } catch (const TTransportException& tte) {
      handleTTransportException("openConnection", tte);
    } catch (const protocol::TProtocolException& tpe) {
      handleTProtocolException("openConnection", tpe);
    } catch (const TException& te) {
      handleTException("openConnection", te);
    } catch (const std::exception& stde) {
      handleStdException("openConnection", stde);
    } catch (...) {
      handleUnknownException("openConnection");
    }
  }
}

void ClientService::destroyTransport() noexcept {
  // destructor should *never* throw an exception
  try {
    ClientTransport* transport = m_transport.get();
    if (transport != NULL) {
      if (transport->isTransportOpen()) {
        transport->closeTransport();
      }
      m_transport = NULL;
    }
  } catch (const SQLException& sqle) {
    Utils::handleExceptionInDestructor("connection service", sqle);
  } catch (const std::exception& stde) {
    Utils::handleExceptionInDestructor("connection service", stde);
  } catch (...) {
    Utils::handleExceptionInDestructor("connection service");
  }
}

ClientService::~ClientService() {
  // destructor should *never* throw an exception
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
      << boost::this_thread::get_id() << std::dec << '>';
  connArgs.__set_clientID(hostId.str());
  // TODO: fixed security mechanism for now
  connArgs.__set_security(thrift::SecurityMechanism::PLAIN);
  return connArgs;
}

thrift::ServerType::type ClientService::getServerType(bool isServer,
    bool useBinaryProtocol, bool useSSL) {
  if (isServer) {
    if (useSSL) {
      return useBinaryProtocol ? thrift::ServerType::THRIFT_SNAPPY_BP_SSL
          : thrift::ServerType::THRIFT_SNAPPY_CP_SSL;
    } else {
      return useBinaryProtocol ? thrift::ServerType::THRIFT_SNAPPY_BP
          : thrift::ServerType::THRIFT_SNAPPY_CP;
    }
  } else if (useSSL) {
    return useBinaryProtocol ? thrift::ServerType::THRIFT_LOCATOR_BP_SSL
        : thrift::ServerType::THRIFT_LOCATOR_CP_SSL;
  } else {
    return useBinaryProtocol ? thrift::ServerType::THRIFT_LOCATOR_BP
        : thrift::ServerType::THRIFT_LOCATOR_CP;
  }
}

protocol::TProtocol* ClientService::createDummyProtocol() {
  boost::shared_ptr<TMemoryBuffer> dummyTransport(new TMemoryBuffer(0));
  return new protocol::TBinaryProtocol(dummyTransport);
}

protocol::TProtocol* ClientService::createProtocol(
    thrift::HostAddress& hostAddr, const thrift::ServerType::type serverType,
    bool useFramedTransport,//const SSLSocketParameters& sslParams,
    boost::shared_ptr<ClientTransport>& returnTransport) {
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

  boost::shared_ptr<TSocket> socket;
  if (useSSL) {
    TSSLSocketFactory sslSocketFactory;
    sslSocketFactory.authenticate(false);
    socket = sslSocketFactory.createSocket(hostAddr.hostName, hostAddr.port);
  } else {
    socket.reset(new TSocket(hostAddr.hostName, hostAddr.port));
  }

  // socket->setKeepAlive(false);
  BufferedClientTransport* bufferedTransport = new BufferedClientTransport(
      socket, rsz, wsz, false);
  // setup framed transport if configured
  if (useFramedTransport) {
    returnTransport.reset(new FramedClientTransport(
        boost::shared_ptr<BufferedClientTransport>(bufferedTransport), wsz));
  } else {
    returnTransport.reset(bufferedTransport);
  }
  if (useBinaryProtocol) {
    return new protocol::TBinaryProtocol(
        boost::dynamic_pointer_cast<TTransport>(returnTransport));
  } else {
    return new protocol::TCompactProtocol(
        boost::dynamic_pointer_cast<TTransport>(returnTransport));
  }
}

void ClientService::execute(thrift::StatementResult& result,
    const std::string& sql,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (!m_hasPendingTXAttrs) {
      m_client.execute(result, m_connId, sql, outputParams, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.execute(result, m_connId, sql, outputParams, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("execute", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("execute", tpe);
  } catch (const TException& te) {
    handleTException("execute", te);
  } catch (const std::exception& stde) {
    handleStdException("execute", stde);
  } catch (...) {
    handleUnknownException("execute");
  }
}

void ClientService::executeUpdate(thrift::UpdateResult& result,
    const std::vector<std::string>& sqls,
    const thrift::StatementAttrs& attrs) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (!m_hasPendingTXAttrs) {
      m_client.executeUpdate(result, m_connId, sqls, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.executeUpdate(result, m_connId, sqls, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeUpdate", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeUpdate", tpe);
  } catch (const TException& te) {
    handleTException("executeUpdate", te);
  } catch (const std::exception& stde) {
    handleStdException("executeUpdate", stde);
  } catch (...) {
    handleUnknownException("executeUpdate");
  }
}

void ClientService::executeQuery(thrift::RowSet& result,
    const std::string& sql, const thrift::StatementAttrs& attrs) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (!m_hasPendingTXAttrs) {
      m_client.executeQuery(result, m_connId, sql, attrs, m_token);
    } else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.executeQuery(result, m_connId, sql, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeQuery", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeQuery", tpe);
  } catch (const TException& te) {
    handleTException("executeQuery", te);
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
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

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
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("prepareStatement", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("prepareStatement", tpe);
  } catch (const TException& te) {
    handleTException("prepareStatement", te);
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
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePrepared(result, prepResult.statementId, params,
        outputParams, attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePrepared", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePrepared", tpe);
  } catch (const TException& te) {
    handleTException("executePrepared", te);
  } catch (const std::exception& stde) {
    handleStdException("executePrepared", stde);
  } catch (...) {
    handleUnknownException("executePrepared");
  }
}

void ClientService::executePreparedUpdate(thrift::UpdateResult& result,
    thrift::PrepareResult& prepResult, const thrift::Row& params,
    const thrift::StatementAttrs& attrs) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedUpdate(result, prepResult.statementId, params,
        attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedUpdate", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedUpdate", tpe);
  } catch (const TException& te) {
    handleTException("executePreparedUpdate", te);
  } catch (const std::exception& stde) {
    handleStdException("executePreparedUpdate", stde);
  } catch (...) {
    handleUnknownException("executePreparedUpdate");
  }
}

void ClientService::executePreparedQuery(thrift::RowSet& result,
    thrift::PrepareResult& prepResult, const thrift::Row& params,
    const thrift::StatementAttrs& attrs) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedQuery(result, prepResult.statementId, params,
        attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedQuery", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedQuery", tpe);
  } catch (const TException& te) {
    handleTException("executePreparedQuery", te);
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
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedBatch(result, prepResult.statementId, paramsBatch,
        attrs, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedBatch", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedBatch", tpe);
  } catch (const TException& te) {
    handleTException("executePreparedBatch", te);
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
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

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
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("prepareAndExecute", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("prepareAndExecute", tpe);
  } catch (const TException& te) {
    handleTException("prepareAndExecute", te);
  } catch (const std::exception& stde) {
    handleStdException("prepareAndExecute", stde);
  } catch (...) {
    handleUnknownException("prepareAndExecute");
  }
}

void ClientService::getNextResultSet(thrift::RowSet& result,
    const int64_t cursorId, const int8_t otherResultSetBehaviour) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getNextResultSet(result, cursorId, otherResultSetBehaviour,
        m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getNextResultSet", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getNextResultSet", tpe);
  } catch (const TException& te) {
    handleTException("getNextResultSet", te);
  } catch (const std::exception& stde) {
    handleStdException("getNextResultSet", stde);
  } catch (...) {
    handleUnknownException("getNextResultSet");
  }
}

void ClientService::getBlobChunk(thrift::BlobChunk& result,
    const int32_t lobId, const int64_t offset, const int32_t size,
    const bool freeLobAtEnd) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getBlobChunk(result, m_connId, lobId, offset, size,
        freeLobAtEnd, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getBlobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getBlobChunk", tpe);
  } catch (const TException& te) {
    handleTException("getBlobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("getBlobChunk", stde);
  } catch (...) {
    handleUnknownException("getBlobChunk");
  }
}

void ClientService::getClobChunk(thrift::ClobChunk& result,
    const int32_t lobId, const int64_t offset, const int32_t size,
    const bool freeLobAtEnd) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getClobChunk(result, m_connId, lobId, offset, size,
        freeLobAtEnd, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getClobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getClobChunk", tpe);
  } catch (const TException& te) {
    handleTException("getClobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("getClobChunk", stde);
  } catch (...) {
    handleUnknownException("getClobChunk");
  }
}

int64_t ClientService::sendBlobChunk(thrift::BlobChunk& chunk) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    return m_client.sendBlobChunk(chunk, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("sendBlobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("sendBlobChunk", tpe);
  } catch (const TException& te) {
    handleTException("sendBlobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("sendBlobChunk", stde);
  } catch (...) {
    handleUnknownException("sendBlobChunk");
  }
  // never reached
  return -1;
}

int64_t ClientService::sendClobChunk(thrift::ClobChunk& chunk) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    return m_client.sendClobChunk(chunk, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("sendClobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("sendClobChunk", tpe);
  } catch (const TException& te) {
    handleTException("sendClobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("sendClobChunk", stde);
  } catch (...) {
    handleUnknownException("sendClobChunk");
  }
  // never reached
  return -1;
}

void ClientService::freeLob(const int32_t lobId) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.freeLob(m_connId, lobId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("freeLob", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("freeLob", tpe);
  } catch (const TException& te) {
    handleTException("freeLob", te);
  } catch (const std::exception& stde) {
    handleStdException("freeLob", stde);
  } catch (...) {
    handleUnknownException("freeLob");
  }
}

void ClientService::scrollCursor(thrift::RowSet& result,
    const int64_t cursorId, const int32_t offset, const bool offsetIsAbsolute,
    const bool fetchReverse, const int32_t fetchSize) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.scrollCursor(result, cursorId, offset, offsetIsAbsolute,
        fetchReverse, fetchSize, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("scrollCursor", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("scrollCursor", tpe);
  } catch (const TException& te) {
    handleTException("scrollCursor", te);
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
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executeCursorUpdate(cursorId, operations, changedRows,
        changedColumnsList, changedRowIndexes, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeBatchCursorUpdate", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeBatchCursorUpdate", tpe);
  } catch (const TException& te) {
    handleTException("executeBatchCursorUpdate", te);
  } catch (const std::exception& stde) {
    handleStdException("executeBatchCursorUpdate", stde);
  } catch (...) {
    handleUnknownException("executeBatchCursorUpdate");
  }
}

void ClientService::beginTransaction(const IsolationLevel isolationLevel) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_client.beginTransaction(m_connId, static_cast<int8_t>(isolationLevel),
        m_pendingTXAttrs, m_token);
    clearPendingTransactionAttrs();
    m_isolationLevel = isolationLevel;
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("beginTransaction", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("beginTransaction", tpe);
  } catch (const TException& te) {
    handleTException("beginTransaction", te);
  } catch (const std::exception& stde) {
    handleStdException("beginTransaction", stde);
  } catch (...) {
    handleUnknownException("beginTransaction");
  }
}

void ClientService::setTransactionAttribute(const TransactionAttribute flag,
    bool isTrue) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_pendingTXAttrs[static_cast<thrift::TransactionAttribute::type>(flag)] =
        isTrue;
    m_hasPendingTXAttrs = true;
  } catch (const std::exception& stde) {
    handleStdException("setTransactionAttribute", stde);
  }
}

bool ClientService::getTransactionAttribute(const TransactionAttribute flag) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

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
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getTransactionAttributes(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getTransactionAttributes", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getTransactionAttributes", tpe);
  } catch (const TException& te) {
    handleTException("getTransactionAttributes", te);
  } catch (const std::exception& stde) {
    handleStdException("getTransactionAttributes", stde);
  } catch (...) {
    handleUnknownException("getTransactionAttributes");
  }
}

void ClientService::getTransactionAttributes(
    std::map<thrift::TransactionAttribute::type, bool>& result) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    getTransactionAttributesNoLock(result);
  } catch (const std::exception& stde) {
    handleStdException("getTransactionAttributes", stde);
  }
}

void ClientService::commitTransaction(const bool startNewTransaction) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_client.commitTransaction(m_connId, startNewTransaction, m_pendingTXAttrs,
        m_token);
    clearPendingTransactionAttrs();
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("commitTransaction", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("commitTransaction", tpe);
  } catch (const TException& te) {
    handleTException("commitTransaction", te);
  } catch (const std::exception& stde) {
    handleStdException("commitTransaction", stde);
  } catch (...) {
    handleUnknownException("commitTransaction");
  }
}

void ClientService::rollbackTransaction(const bool startNewTransaction) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_client.rollbackTransaction(m_connId, startNewTransaction,
        m_pendingTXAttrs, m_token);
    clearPendingTransactionAttrs();
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("rollbackTransaction", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("rollbackTransaction", tpe);
  } catch (const TException& te) {
    handleTException("rollbackTransaction", te);
  } catch (const std::exception& stde) {
    handleStdException("rollbackTransaction", stde);
  } catch (...) {
    handleUnknownException("rollbackTransaction");
  }
}

void ClientService::fetchActiveConnections(
    std::vector<thrift::ConnectionProperties>& result) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.fetchActiveConnections(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("fetchActiveConnections", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("fetchActiveConnections", tpe);
  } catch (const TException& te) {
    handleTException("fetchActiveConnections", te);
  } catch (const std::exception& stde) {
    handleStdException("fetchActiveConnections", stde);
  } catch (...) {
    handleUnknownException("fetchActiveConnections");
  }
}

void ClientService::fetchActiveStatements(
    std::map<int64_t, std::string>& result) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.fetchActiveStatements(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("fetchActiveStatements", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("fetchActiveStatements", tpe);
  } catch (const TException& te) {
    handleTException("fetchActiveStatements", te);
  } catch (const std::exception& stde) {
    handleStdException("fetchActiveStatements", stde);
  } catch (...) {
    handleUnknownException("fetchActiveStatements");
  }
}

void ClientService::getServiceMetaData(thrift::ServiceMetaData& result) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getServiceMetaData(result, m_connId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getServiceMetaData", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getServiceMetaData", tpe);
  } catch (const TException& te) {
    handleTException("getServiceMetaData", te);
  } catch (const std::exception& stde) {
    handleStdException("getServiceMetaData", stde);
  } catch (...) {
    handleUnknownException("getServiceMetaData");
  }
}

void ClientService::getSchemaMetaData(thrift::RowSet& result,
    const thrift::ServiceMetaDataCall::type schemaCall,
    thrift::ServiceMetaDataArgs& metadataArgs) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getSchemaMetaData(result, schemaCall, metadataArgs);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getSchemaMetaData", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getSchemaMetaData", tpe);
  } catch (const TException& te) {
    handleTException("getSchemaMetaData", te);
  } catch (const std::exception& stde) {
    handleStdException("getSchemaMetaData", stde);
  } catch (...) {
    handleUnknownException("getSchemaMetaData");
  }
}

void ClientService::getIndexInfo(thrift::RowSet& result,
    thrift::ServiceMetaDataArgs& metadataArgs, const bool unique,
    const bool approximate) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getIndexInfo(result, metadataArgs, unique, approximate);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getIndexInfo", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getIndexInfo", tpe);
  } catch (const TException& te) {
    handleTException("getIndexInfo", te);
  } catch (const std::exception& stde) {
    handleStdException("getIndexInfo", stde);
  } catch (...) {
    handleUnknownException("getIndexInfo");
  }
}

void ClientService::getUDTs(thrift::RowSet& result,
    thrift::ServiceMetaDataArgs& metadataArgs,
    const std::vector<thrift::SnappyType::type>& types) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getUDTs(result, metadataArgs, types);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getUDTs", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getUDTs", tpe);
  } catch (const TException& te) {
    handleTException("getUDTs", te);
  } catch (const std::exception& stde) {
    handleStdException("getUDTs", stde);
  } catch (...) {
    handleUnknownException("getUDTs");
  }
}

void ClientService::getBestRowIdentifier(thrift::RowSet& result,
    thrift::ServiceMetaDataArgs& metadataArgs, const int32_t scope,
    const bool nullable) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    metadataArgs.connId = m_connId;
    if (m_token.size() > 0) {
      metadataArgs.__set_token(m_token);
    }
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getBestRowIdentifier(result, metadataArgs, scope, nullable);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getBestRowIdentifier", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getBestRowIdentifier", tpe);
  } catch (const TException& te) {
    handleTException("getBestRowIdentifier", te);
  } catch (const std::exception& stde) {
    handleStdException("getBestRowIdentifier", stde);
  } catch (...) {
    handleUnknownException("getBestRowIdentifier");
  }
}

void ClientService::closeResultSet(const int64_t cursorId) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_client.closeResultSet(cursorId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("closeResultSet", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("closeResultSet", tpe);
  } catch (const TException& te) {
    handleTException("closeResultSet", te);
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
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_client.cancelStatement(stmtId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("cancelStatement", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("cancelStatement", tpe);
  } catch (const TException& te) {
    handleTException("cancelStatement", te);
  } catch (const std::exception& stde) {
    handleStdException("cancelStatement", stde);
  } catch (...) {
    handleUnknownException("cancelStatement");
  }
}

void ClientService::closeStatement(const int64_t stmtId) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_client.closeStatement(stmtId, m_token);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("closeStatement", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("closeStatement", tpe);
  } catch (const TException& te) {
    handleTException("closeStatement", te);
  } catch (const std::exception& stde) {
    handleStdException("closeStatement", stde);
  } catch (...) {
    handleUnknownException("closeStatement");
  }
}

void ClientService::bulkClose(
    const std::vector<thrift::EntityId>& entities) {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    m_client.bulkClose(entities);
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("closeResultSet", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("closeResultSet", tpe);
  } catch (const TException& te) {
    handleTException("closeResultSet", te);
  } catch (const std::exception& stde) {
    handleStdException("closeResultSet", stde);
  } catch (...) {
    handleUnknownException("closeResultSet");
  }
}

void ClientService::close() {
  try {
    boost::lock_guard<boost::mutex> sync(m_lock);

    ClientTransport* transport = m_transport.get();
    if (transport != NULL) {
      m_client.closeConnection(m_connId, true, m_token);
      if (transport->isTransportOpen()) {
        transport->closeTransport();
      }
      m_transport = NULL;
    }
  } catch (const thrift::SnappyException& sqle) {
    handleSnappyException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("close", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("close", tpe);
  } catch (const TException& te) {
    handleTException("close", te);
  } catch (const std::exception& stde) {
    handleStdException("close", stde);
  } catch (...) {
    handleUnknownException("close");
  }
}
void ClientService::updateFailedServersForCurrent(std::set<thrift::HostAddress>& failedServers,
    bool checkAllFailed, std::exception* failure){

  //TODO:: Need to discuss with sumedh about this method
  thrift::HostAddress host = this->m_currentHostAddr;

  auto ret = failedServers.insert(host);
  if(ret.second==false && checkAllFailed){
    boost::optional<ControlConnection&> controlService = ControlConnection::getOrCreateControlConnection(m_connHosts,this,failure);
    thrift::HostAddress pHost;
    controlService->searchRandomServer(failedServers,failure,pHost);
  }

}
