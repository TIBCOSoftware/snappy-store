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
 * Connection.cpp
 *
 * Wrapper around thrift connection to carry minimal state as required
 * for ID, flags etc.
 */

#include "Connection.h"

#include <boost/algorithm/string.hpp>

#include "impl/ClientService.h"
#include "impl/InternalUtils.h"

#include "PreparedStatement.h"
#include "ParametersBatch.h"
#include "ClientAttribute.h"
#include "impl/ClientTransport.h"

using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;

static const char* TRUE_FALSE[] = { "true", "false", NULL };
static const char* FALSE_TRUE[] = { "false", "true", NULL };
static const char* LOG_LEVELS[] = { "none", "fatal", "error", "warn", "info",
                                    "debug", "trace", "all" };
static const char* SECURITY_MODES[] = { "plain", "diffie-hellman", NULL };
static const char* SSL_MODES[] = { "none", "basic", "peer-auth", NULL };

ConnectionProperty::ConnectionProperty(const std::string& propName,
    const char* helpMessage, const char** possibleValues,
    const char* defaultValue, const int flags) :
    m_propName(propName), m_helpMessage(helpMessage),
    m_possibleValues(possibleValues), m_numPossibleValues(0),
    m_defaultValue(defaultValue), m_flags(flags) {
  if (possibleValues != NULL) {
    while (*possibleValues != NULL) {
      m_numPossibleValues++;
      possibleValues++;
    }
  }
}

std::map<std::string, ConnectionProperty> ConnectionProperty::s_properties;

void ConnectionProperty::staticInitialize() {
  char readTimeoutHelp[256];
  char keepAliveIdleHelp[256];
  char keepAliveIntvlHelp[256];
  char keepAliveCntHelp[256];
  char singleHopMaxConnHelp[256];

  ::snprintf(readTimeoutHelp, sizeof(readTimeoutHelp) - 1,
      "Timeout in milliseconds to wait for connection creation or reply "
          "from server (default is %d)",
      ClientAttribute::DEFAULT_LOGIN_TIMEOUT);
  ::snprintf(keepAliveIdleHelp, sizeof(keepAliveIdleHelp) - 1,
      "TCP keepalive time in seconds between two transmissions on "
          "socket in idle condition (default is %d)",
      ClientAttribute::DEFAULT_KEEPALIVE_IDLE);
  ::snprintf(keepAliveIntvlHelp, sizeof(keepAliveIntvlHelp) - 1,
      "TCP keepalive duration in seconds between successive transmissions on "
          "socket if no reply to packet sent after idle timeout (default is %d)",
      ClientAttribute::DEFAULT_KEEPALIVE_INTVL);
  ::snprintf(keepAliveCntHelp, sizeof(keepAliveCntHelp) - 1,
      "Number of retransmissions for TCP keepalive to be sent before "
          "declaring the other end to be dead (default is %d)",
      ClientAttribute::DEFAULT_KEEPALIVE_CNT);
  ::snprintf(singleHopMaxConnHelp, sizeof(singleHopMaxConnHelp) - 1,
      "The maximum connection pool size for each server when single hop is "
          "enabled (default is %d)",
      ClientAttribute::DEFAULT_SINGLE_HOP_MAX_CONN_PER_SERVER);

  addProperty_(ClientAttribute::USERNAME, "User name to connect with",
      NULL, NULL, F_IS_USER | F_IS_UTF8);
  addProperty_(ClientAttribute::USERNAME_ALT,
      "" /* empty to skip displaying it */, NULL, NULL, F_IS_USER | F_IS_UTF8);
  addProperty_(ClientAttribute::PASSWORD, "Password of the user", NULL, NULL,
      F_IS_PASSWD | F_IS_UTF8);
  addProperty_(ClientAttribute::LOAD_BALANCE,
      "Enable/Disable transparent load balancing of the connections "
          "on available servers (default enabled)", TRUE_FALSE, NULL,
      F_IS_BOOLEAN);
  addProperty_(ClientAttribute::SECONDARY_LOCATORS,
      "Specify additional locators or servers to try for initial "
          "connection before giving up", NULL, NULL, F_IS_UTF8);
  addProperty_(ClientAttribute::READ_TIMEOUT, readTimeoutHelp, NULL, NULL,
      F_NONE);
  addProperty_(ClientAttribute::KEEPALIVE_IDLE, keepAliveIdleHelp, NULL, NULL,
      F_NONE);
  addProperty_(ClientAttribute::KEEPALIVE_INTVL, keepAliveIntvlHelp, NULL,
      NULL, F_NONE);
  addProperty_(ClientAttribute::KEEPALIVE_CNT, keepAliveCntHelp, NULL, NULL,
      F_NONE);
  addProperty_(ClientAttribute::SINGLE_HOP_ENABLED,
      "Enable single hop queries and update/delete DML operations for "
          "partitioned tables (default disabled)", FALSE_TRUE, NULL,
      F_IS_BOOLEAN);
  addProperty_(ClientAttribute::SINGLE_HOP_MAX_CONNECTIONS,
      singleHopMaxConnHelp, NULL, NULL, F_NONE);
  addProperty_(ClientAttribute::DISABLE_STREAMING,
      "Disable streaming of query results from servers to client "
          "(default is false i.e. streaming is enabled)", FALSE_TRUE, NULL,
      F_IS_BOOLEAN);
  addProperty_(ClientAttribute::SKIP_LISTENERS, "Skip write-through/"
      "write-behind/listener and other callback invocations for DML "
      "operations from this connection (default is false)", FALSE_TRUE, NULL,
      F_IS_BOOLEAN);
  addProperty_(ClientAttribute::SKIP_CONSTRAINT_CHECKS,
      "Skip primary key, foreign key, unique and check constraint "
          "checks. For the case of primary key, an insert is converted "
          "into PUT DML so row will retain the last values without "
          "throwing a constraint violation (default is false)", FALSE_TRUE,
      NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::LOG_FILE,
      "Path of the log file for any client side logging for function tracing "
          " or product logs/errors", NULL, NULL, F_IS_UTF8 | F_IS_FILENAME);
  addProperty_(ClientAttribute::LOG_LEVEL,
      "Logging level to use; one of none, fatal, error, warn, info, "
          "debug, trace, all ", LOG_LEVELS, "error", F_IS_UTF8);
  addProperty_(ClientAttribute::SECURITY_MECHANISM,
      "The security mechanism to use for authentication client to server;"
          " one of plain (default), or diffie-helman", SECURITY_MODES, NULL,
      F_NONE);
  addProperty_(ClientAttribute::SSL,
      "Specifies the mode for SSL communication from server to client;"
          " one of basic, peer-auth, or none (the default)", SSL_MODES, NULL,
      F_NONE);
  addProperty_(ClientAttribute::SSL_PROPERTIES,
      "A comma-separated SSL property key=value pairs that can be set for a "
          "client SSL connection. See docs for the supported properties",
      NULL, NULL, F_NONE);
  addProperty_(ClientAttribute::TX_SYNC_COMMITS,
      "Wait for 2nd phase commit to complete on all nodes instead of "
          "returning as soon as current server's 2nd phase commit is "
          "done (default is false)", FALSE_TRUE, NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::DISABLE_TX_BATCHING,
      "Disable all batching in transactions, flushing ops immediately "
          "to detect conflicts immediately. Note that this can have a "
          "significant performance impact so turn this on only if it "
          "is really required by application (default is false)", FALSE_TRUE,
      NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::QUERY_HDFS,
      "A connection level property to enable querying data in HDFS, "
          "else only in-memory data is queried. (default false)", FALSE_TRUE,
      NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::DISABLE_THINCLIENT_CANCEL,
      "A connection level property. If true, then Statement.cancel() "
          "through thin driver will not be supported. The driver will "
          "not ask for statementUUID from the server.(default false)",
      FALSE_TRUE, NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::ROUTE_QUERY,
      "A connection level property. Whether to route queries to lead node. "
          "Default behaviour is to route column table or complex queries to lead "
          "node to be executed by SnappyData engine. Setting this to false will "
          "force using the Store engine only which does not support a bunch of "
          "features like column tables, non-collocated joins etc (default true)",
      TRUE_FALSE, NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::THRIFT_USE_BINARY_PROTOCOL,
      "A connection level property. Use binary protocol instead of compact "
          "protocol for client-server communication. Requires servers with "
          "with binary protocol running in the cluster.(default false)",
      FALSE_TRUE, NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::THRIFT_USE_FRAMED_TRANSPORT,
      "A connection level property. Use framed transport instead of normal "
          "transport for client-server communication. Unlike other settings, "
          "requires all servers to be configured running in framed transport. "
          "Framed transport is a less efficient path and not recommended to "
          "be used unless there are specialized needs (default false)",
      FALSE_TRUE, NULL, F_IS_BOOLEAN);
  addProperty_(ClientAttribute::SERVER_GROUPS,
      "A connection level property. Restrict connection to servers in the "
          "given server-groups.", FALSE_TRUE, NULL, F_IS_BOOLEAN);
}

void ConnectionProperty::addProperty_(const std::string& propName,
    const char* helpMessage, const char** possibleValues,
    const char* defaultValue, const int flags) {
  // check for valid property name
  const std::unordered_set<std::string>& attrs =
      ClientAttribute::getAllAttributes();
  if (attrs.find(propName) != attrs.end()) {
    s_properties.insert(std::make_pair(propName, ConnectionProperty(propName,
        helpMessage, possibleValues, defaultValue, flags)));
  } else {
    throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION,
        std::string("Unknown connection property in initialization: ")
            + propName);
  }
}

const std::unordered_set<std::string>&
ConnectionProperty::getValidPropertyNames() {
  return ClientAttribute::getAllAttributes();
}

const ConnectionProperty& ConnectionProperty::getProperty(
    const std::string& propertyName) {
  std::map<std::string, ConnectionProperty>::iterator iter;
  if ((iter = s_properties.find(propertyName)) != s_properties.end()) {
    return iter->second;
  } else {
    throw GET_SQLEXCEPTION2(SQLStateMessage::INVALID_CONNECTION_PROPERTY_MSG,
        propertyName.c_str());
  }
}

void Connection::initializeService() {
  ClientService::staticInitialize();
}

void Connection::open(const std::string& host, const int port,
    thrift::OpenConnectionArgs& connArgs) {
  // close any existing service instance first
  close();

  m_service = std::shared_ptr<ClientService>(
      new ClientService(host, port, connArgs));
}

void Connection::checkProperty(const std::string& propertyName) {
  const std::unordered_set<std::string>& attrs =
      ClientAttribute::getAllAttributes();
  if (attrs.find(propertyName) == attrs.end()) {
    SQLWarning* warning = new SQLWarning(__FILE__, __LINE__,
        SQLState::INVALID_CONNECTION_PROPERTY,
        SQLStateMessage::INVALID_CONNECTION_PROPERTY_MSG.format(
            propertyName.c_str()));
    SQLWarning* existing = m_warnings.get();
    if (existing == NULL) {
      m_warnings.reset(warning);
    } else {
      existing->setNextWarning(warning);
    }
  }
}

void Connection::open(const std::string& host, int port) {
  thrift::OpenConnectionArgs args;
  open(host, port, args);
}

void Connection::open(const std::string& host, int port,
    const std::string& user, const std::string& password) {
  thrift::OpenConnectionArgs args;
  args.__set_userName(user);
  args.__set_password(password);
  open(host, port, args);
}

void Connection::open(const std::string& host, int port,
    const std::map<std::string, std::string>& properties) {
  thrift::OpenConnectionArgs args;
  args.__set_properties(properties);
  open(host, port, args);
}

void Connection::open(const std::string& host, int port,
    const std::string& user, const std::string& password,
    const std::map<std::string, std::string>& properties) {
  thrift::OpenConnectionArgs args;
  args.__set_userName(user);
  args.__set_password(password);
  args.__set_properties(properties);
  open(host, port, args);
}

const thrift::HostAddress& Connection::getCurrentHostAddress() const noexcept {
  return checkAndGetService().getCurrentHostAddress();
}

const thrift::OpenConnectionArgs& Connection::getConnectionArgs() const noexcept {
  return checkAndGetService().getConnectionArgs();
}

void Connection::setSendBufferSize(uint32_t sz) {
  ClientService& service = checkAndGetService();

  service.getTransport()->setSendBufferSize(sz);
}

uint32_t Connection::getSendBufferSize() const {
  ClientService& service = checkAndGetService();

  return service.getTransport()->getSendBufferSize();
}

void Connection::setReceiveBufferSize(uint32_t sz) {
  ClientService& service = checkAndGetService();

  service.getTransport()->setReceiveBufferSize(sz);
}

uint32_t Connection::getReceiveBufferSize() const {
  ClientService& service = checkAndGetService();

  return service.getTransport()->getReceiveBufferSize();
}

void Connection::setConnectTimeout(int millis) {
  ClientService& service = checkAndGetService();

  service.getTransport()->getSocket()->setConnTimeout(millis);
}

int Connection::getConnectTimeout() const {
  ClientService& service = checkAndGetService();

  return service.getTransport()->getSocket()->getConnTimeout();
}

void Connection::setSendTimeout(int millis) {
  ClientService& service = checkAndGetService();

  service.getTransport()->getSocket()->setSendTimeout(millis);
}

int Connection::getSendTimeout() const {
  ClientService& service = checkAndGetService();

  return service.getTransport()->getSocket()->getSendTimeout();
}

void Connection::setReceiveTimeout(int millis) {
  ClientService& service = checkAndGetService();

  service.getTransport()->getSocket()->setRecvTimeout(millis);
}

int Connection::getReceiveTimeout() const {
  ClientService& service = checkAndGetService();

  return service.getTransport()->getSocket()->getRecvTimeout();
}

void Connection::convertOutputParameters(
    const std::map<int32_t, OutputParameter>& outputParams,
    std::map<int32_t, thrift::OutputParameter>& result) {
  for (std::map<int32_t, OutputParameter>::const_iterator iter =
      outputParams.begin(); iter != outputParams.end(); ++iter) {
    result[iter->first] = iter->second.getThriftOutputParameter();
  }
}

std::unique_ptr<Result> Connection::execute(const std::string& sql,
    const std::map<int32_t, OutputParameter>& outputParams,
    const StatementAttributes& attrs) {
  ClientService& service = checkAndGetService();

  Result* result = new Result(m_service, attrs);
  std::unique_ptr<Result> resultp(result);
  if (outputParams.size() == 0) {
    service.execute(result->m_result, sql, EMPTY_OUT_PARAMS,
        attrs.getAttrs());
    return resultp;
  } else {
    std::map<int32_t, thrift::OutputParameter> resultOutParams;
    convertOutputParameters(outputParams, resultOutParams);
    service.execute(result->m_result, sql, resultOutParams, attrs.getAttrs());
    return resultp;
  }
}

std::unique_ptr<ResultSet> Connection::executeQuery(const std::string& sql,
    const StatementAttributes& attrs) {
  ClientService& service = checkAndGetService();

  int32_t batchSize;
  bool updatable, scrollable;
  Result::getResultSetArgs(attrs, batchSize, updatable, scrollable);

  thrift::RowSet* rs = new thrift::RowSet();
  std::unique_ptr<ResultSet> result(
      new ResultSet(rs, m_service, attrs, batchSize, updatable, scrollable));
  service.executeQuery(*rs, sql, attrs.getAttrs());
  return result;
}

int32_t Connection::executeUpdate(const std::string& sql,
    const StatementAttributes& attrs) {
  ClientService& service = checkAndGetService();

  thrift::UpdateResult result;
  service.executeUpdate(result, Utils::singleVector(sql), attrs.getAttrs());
  if (result.__isset.warnings) {
    // set back in Connection
    m_warnings.reset(new GET_SQLWARNING(result.warnings));
  } else {
    m_warnings.reset();
  }
  if (result.__isset.updateCount) {
    return result.updateCount;
  } else {
    return -1;
  }
}

std::unique_ptr<std::vector<int32_t> > Connection::executeBatch(
    const std::vector<std::string>& batchSQLs,
    const StatementAttributes& attrs) {
  ClientService& service = checkAndGetService();

  thrift::UpdateResult result;
  service.executeUpdate(result, batchSQLs, attrs.getAttrs());
  if (result.__isset.warnings) {
    // set back in Connection
    m_warnings.reset(new GET_SQLWARNING(result.warnings));
  } else {
    m_warnings.reset();
  }
  if (result.__isset.batchUpdateCounts) {
    return std::unique_ptr<std::vector<int32_t> >(
        new std::vector<int32_t>(result.batchUpdateCounts));
  } else {
    return std::unique_ptr<std::vector<int32_t> >();
  }
}

std::unique_ptr<PreparedStatement> Connection::prepareStatement(
    const std::string& sql,
    const std::map<int32_t, OutputParameter>& outputParams,
    const StatementAttributes& attrs) {
  ClientService& service = checkAndGetService();

  std::unique_ptr<PreparedStatement> pstmt(
      new PreparedStatement(m_service, attrs));
  if (outputParams.size() == 0) {
    service.prepareStatement(pstmt->m_prepResult, sql, EMPTY_OUT_PARAMS,
        attrs.getAttrs());
  } else {
    std::map<int32_t, thrift::OutputParameter> resultOutParams;
    convertOutputParameters(outputParams, resultOutParams);
    service.prepareStatement(pstmt->m_prepResult, sql, resultOutParams,
        attrs.getAttrs());
  }
  return pstmt;
}

std::unique_ptr<Result> Connection::prepareAndExecute(const std::string& sql,
    const Parameters& params,
    const std::map<int32_t, OutputParameter>& outputParams,
    const StatementAttributes& attrs) {
  ClientService& service = checkAndGetService();

  std::unique_ptr<Result> result(new Result(m_service, attrs));
  if (outputParams.size() == 0) {
    service.prepareAndExecute(result->m_result, sql,
        Utils::singleVector<thrift::Row>(params), EMPTY_OUT_PARAMS,
        attrs.getAttrs());
    return result;
  } else {
    std::map<int32_t, thrift::OutputParameter> resultOutParams;
    convertOutputParameters(outputParams, resultOutParams);
    service.prepareAndExecute(result->m_result, sql,
        Utils::singleVector<thrift::Row>(params), resultOutParams,
        attrs.getAttrs());
    return result;
  }
}

std::unique_ptr<Result> Connection::prepareAndExecuteBatch(
    const std::string& sql, const ParametersBatch& paramsBatch,
    const std::map<int32_t, OutputParameter>& outputParams,
    const StatementAttributes& attrs) {
  ClientService& service = checkAndGetService();

  std::unique_ptr<Result> result(new Result(m_service, attrs));
  if (outputParams.size() == 0) {
    service.prepareAndExecute(result->m_result, sql, paramsBatch.m_batch,
        EMPTY_OUT_PARAMS, attrs.getAttrs());
    return result;
  } else {
    std::map<int32_t, thrift::OutputParameter> resultOutParams;
    convertOutputParameters(outputParams, resultOutParams);
    service.prepareAndExecute(result->m_result, sql, paramsBatch.m_batch,
        resultOutParams, attrs.getAttrs());
    return result;
  }
}

// transactions

void Connection::beginTransaction(const IsolationLevel isolationLevel) {
  ClientService& service = checkAndGetService();

  service.beginTransaction(isolationLevel);
}

IsolationLevel Connection::getCurrentIsolationLevel() const {
  ClientService& service = checkAndGetService();

  return service.getCurrentIsolationLevel();
}

void Connection::setTransactionAttribute(const TransactionAttribute flag,
    bool isTrue) {
  ClientService& service = checkAndGetService();

  service.setTransactionAttribute(flag, isTrue);
}

bool Connection::getTransactionAttribute(const TransactionAttribute flag) {
  ClientService& service = checkAndGetService();

  return service.getTransactionAttribute(flag);
}

void Connection::commitTransaction(bool startNewTransaction) {
  ClientService& service = checkAndGetService();

  service.commitTransaction(startNewTransaction);
}

void Connection::rollbackTransaction(bool startNewTransaction) {
  ClientService& service = checkAndGetService();

  service.rollbackTransaction(startNewTransaction);
}

const std::string Connection::getNativeSQL(const std::string& sql) const {
  // SnappyData can handle the escape syntax directly so only needs escape
  // processing for { ? = CALL  ....}
  std::string trimSql = boost::algorithm::trim_copy(sql);
  if (trimSql.size() > 0 && trimSql[0] == '{') {
    size_t pos = trimSql.rfind('}');
    if (pos != std::string::npos) {
      return trimSql.substr(1, pos - 1);
    }
  }

  return trimSql;
}

ResultSetHoldability Connection::getResultSetHoldability()
    const noexcept {
  if (m_defaultHoldability == ResultSetHoldability::NONE) {
    return thrift::snappydataConstants::DEFAULT_RESULTSET_HOLD_CURSORS_OVER_COMMIT
        ? ResultSetHoldability::HOLD_CURSORS_OVER_COMMIT
        : ResultSetHoldability::CLOSE_CURSORS_OVER_COMMIT;
  } else {
    return m_defaultHoldability;
  }
}

void Connection::setResultSetHoldability(ResultSetHoldability holdability) {
  m_defaultHoldability = holdability;
}

// metadata API

const DatabaseMetaData* Connection::getServiceMetaData() {
  ClientService& service = checkAndGetService();

  if (m_metadata == NULL) {
    m_metadata.reset(new DatabaseMetaData());
    service.getServiceMetaData(m_metadata->m_metadata);
  }
  return m_metadata.get();
}

std::unique_ptr<ResultSet> Connection::getSchemaMetaData(
    const DatabaseMetaDataCall method, DatabaseMetaDataArgs& args) {
  ClientService& service = checkAndGetService();

  thrift::RowSet* rs = new thrift::RowSet();
  std::unique_ptr<ResultSet> resultSet(new ResultSet(rs, m_service));
  args.m_args.driverType = static_cast<int8_t>(DRIVER_TYPE);
  service.getSchemaMetaData(*rs,
      static_cast<thrift::ServiceMetaDataCall::type>(method), args.m_args);
  return resultSet;
}

std::unique_ptr<ResultSet> Connection::getIndexInfo(
    DatabaseMetaDataArgs& args, bool unique, bool approximate) {
  ClientService& service = checkAndGetService();

  thrift::RowSet* rs = new thrift::RowSet();
  std::unique_ptr<ResultSet> resultSet(new ResultSet(rs, m_service));
  args.m_args.driverType = static_cast<int8_t>(DRIVER_TYPE);
  service.getIndexInfo(*rs, args.m_args, unique, approximate);
  return resultSet;
}

std::unique_ptr<ResultSet> Connection::getUDTs(DatabaseMetaDataArgs& args,
    const std::string& typeNamePattern,
    const std::vector<SQLType>& types) {
  ClientService& service = checkAndGetService();

  thrift::RowSet* rs = new thrift::RowSet();
  std::unique_ptr<ResultSet> resultSet(new ResultSet(rs, m_service));
  args.m_args.driverType = static_cast<int8_t>(DRIVER_TYPE);
  std::vector<thrift::SnappyType::type> thriftTypes(types.size());
  for (auto type : types) {
    thriftTypes.push_back(static_cast<thrift::SnappyType::type>(type));
  }
  service.getUDTs(*rs, args.m_args, thriftTypes);
  return resultSet;
}

std::unique_ptr<ResultSet> Connection::getBestRowIdentifier(
    DatabaseMetaDataArgs& args, int32_t scope, bool nullable) {
  ClientService& service = checkAndGetService();

  thrift::RowSet* rs = new thrift::RowSet();
  std::unique_ptr<ResultSet> resultSet(new ResultSet(rs, m_service));
  args.m_args.driverType = static_cast<int8_t>(DRIVER_TYPE);
  service.getBestRowIdentifier(*rs, args.m_args, scope, nullable);
  return resultSet;
}

// end metadata API

void Connection::close() {
  if (m_service != NULL) {
    // close the service and NULL it to decrement the shared_ptr reference
    m_service->close();
    m_service = NULL;
  }
}

Connection::~Connection() {
  // destructor should *never* throw an exception
  // TODO: close from destructor should use bulkClose if valid handle
  try {
    close();
  } catch (const SQLException& sqle) {
    Utils::handleExceptionInDestructor("connection", sqle);
  } catch (const std::exception& stde) {
    Utils::handleExceptionInDestructor("connection", stde);
  } catch (...) {
    Utils::handleExceptionInDestructor("connection");
  }
}
