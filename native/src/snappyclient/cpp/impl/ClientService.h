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

#ifndef CLIENTSERVICE_H_
#define CLIENTSERVICE_H_

#include "ClientBase.h"

#include <boost/shared_ptr.hpp>
#include <boost/thread/mutex.hpp>

#include "../thrift/SnappyDataService.h"

using namespace apache::thrift;

namespace apache {
  namespace thrift {
    namespace transport {
      class TSocket;
    }
    namespace protocol {
      class TProtocol;
    }
  }
}

namespace io {
  namespace snappydata {
    namespace client {
      namespace impl {

        class ClientTransport;
        class ControlConnection;

        class SnappyDataClient : public thrift::SnappyDataServiceClient {
        public:
          SnappyDataClient(protocol::TProtocol* prot) :
              thrift::SnappyDataServiceClient(
                  boost::shared_ptr<protocol::TProtocol>(prot)) {
          }

          inline protocol::TProtocol* getProtocol() const noexcept {
            return iprot_;
          }

        private:
          void resetProtocols(
              const boost::shared_ptr<protocol::TProtocol>& iprot,
              const boost::shared_ptr<protocol::TProtocol>& oprot) {
            piprot_ = iprot;
            poprot_ = oprot;
            iprot_ = iprot.get();
            oprot_ = oprot.get();
          }

          friend class ClientService;
        };

        class ClientService {
        private:
          const thrift::OpenConnectionArgs m_connArgs;
          bool m_loadBalance;
          thrift::ServerType::type m_reqdServerType;
          bool m_useFramedTransport;
          std::set<std::string> m_serverGroups;

          boost::shared_ptr<ClientTransport> m_transport;
          SnappyDataClient m_client;

          thrift::HostAddress m_currentHostAddr;
          std::vector<thrift::HostAddress> m_connHosts;
          int64_t m_connId;
          std::string m_token;
          bool m_isOpen;

          std::map<thrift::TransactionAttribute::type, bool> m_pendingTXAttrs;
          bool m_hasPendingTXAttrs;
          IsolationLevel m_isolationLevel;
          std::map<thrift::TransactionAttribute::type, bool> m_currentTXAttrs;

          // using boost::mutex and not std::mutex due to superior implementation on
          // Windows compared to that provided by VS (which always does kernel call)
          boost::mutex m_lock;

          // no copy constructor or assignment operator due to obvious issues
          // with usage of same connection by multiple threads concurrently
          ClientService(const ClientService&) = delete;
          ClientService& operator=(const ClientService&) = delete;

          void clearPendingTransactionAttrs();

          static thrift::OpenConnectionArgs& initConnectionArgs(
              thrift::OpenConnectionArgs& connArgs);

          static protocol::TProtocol* createDummyProtocol();

          static protocol::TProtocol* createProtocol(
              thrift::HostAddress& hostAddr,
              const thrift::ServerType::type serverType,
              const bool useFramedTransport,
              //const SSLSocketParameters& sslParams,
              boost::shared_ptr<ClientTransport>& returnTransport);

          void updateFailedServersForCurrent(
              std::set<thrift::HostAddress>& failedServers,
              bool checkAllFailed, const std::exception& failure);

        protected:
          virtual void checkConnection(const char* op);

          virtual void handleSnappyException(const char* op, bool tryFailOver,
              bool ignoreNodeFailure, bool createNewConnection,
              std::set<thrift::HostAddress>& failedServers,
              const thrift::SnappyException& se);

          virtual void handleStdException(const char* op,
              const std::exception& stde);

          virtual void handleTTransportException(const char* op,
              bool tryFailover, bool ignoreNodeFailure,
              bool createNewConnection,
              std::set<thrift::HostAddress>& failedServers,
              const transport::TTransportException& tte);

          virtual void handleTProtocolException(const char* op,
              bool tryFailover, bool ignoreNodeFailure,
              bool createNewConnection,
              std::set<thrift::HostAddress>& failedServers,
              const protocol::TProtocolException& tpe);

          virtual void handleTException(const char* op, bool tryFailover,
              bool ignoreNodeFailure, bool createNewConnection,
              std::set<thrift::HostAddress>& failedServers,
              const TException& te);

          virtual void handleUnknownException(const char* op);

          BOOST_NORETURN void throwSQLExceptionForNodeFailure(const char* op,
              const std::exception& se);

          void openConnection(thrift::HostAddress& hostAddr,
              std::set<thrift::HostAddress>& failedServers,
              const std::exception& te);

          void flushPendingTransactionAttrs();

          void setPendingTransactionAttrs(thrift::StatementAttrs& stmtAttrs);

          void getTransactionAttributesNoLock(
              std::map<thrift::TransactionAttribute::type, bool>& result);

          void destroyTransport() noexcept;

          void newSnappyExceptionForConnectionClose(
              const thrift::HostAddress source,
              std::set<thrift::HostAddress>& failedServers,
              bool createNewConnection, const thrift::SnappyException& te);

          void newSnappyExceptionForConnectionClose(
              const thrift::HostAddress& source);

          void tryCreateNewConnection(thrift::HostAddress source,
              std::set<thrift::HostAddress>& failedServers,
              const thrift::SnappyException& te);

          BOOST_NORETURN void throwSnappyExceptionForNodeFailure(
              thrift::HostAddress source, const char* op,
              std::set<thrift::HostAddress>& failedServers,
              bool createNewConnection, const thrift::SnappyException& te);

          BOOST_NORETURN void throwSnappyExceptionForNodeFailure(
              thrift::HostAddress source, const char* op,
              std::set<thrift::HostAddress>& failedServers,
              bool createNewConnection, const std::exception& se);

          virtual bool handleException(const char* op, bool tryFailover,
                        bool ignoreNodeFailure, bool createNewConnection,
                        std::set<thrift::HostAddress>& failedServers,
                        const TException& te);


        private:
          // the static hostName and hostId used by all connections
          static std::string s_hostName;
          static std::string s_hostId;
          static boost::mutex s_globalLock;
          static bool s_initialized;

          /**
           * Global initialization that is done only once.
           * The s_globalLock must be held in the invocation.
           */
          static bool globalInitialize();

        public:
          ClientService(const std::string& host, const int port,
              thrift::OpenConnectionArgs& arguments);

          virtual ~ClientService();

          static void staticInitialize();

          static void staticInitialize(
              std::map<std::string, std::string>& props);

          static thrift::ServerType::type getServerType(bool isServer,
              bool useBinaryProtocol, bool useSSL);

          inline bool isOpen() const noexcept {
            return m_isOpen;
          }

          inline const boost::shared_ptr<ClientTransport>& getTransport() const
              noexcept {
            return m_transport;
          }

          const char* getTokenStr() const noexcept {
            return m_token.empty() ? NULL : m_token.c_str();
          }

          const thrift::HostAddress& getCurrentHostAddress() const noexcept {
            return m_currentHostAddr;
          }

          const thrift::OpenConnectionArgs& getConnectionArgs() const
              noexcept {
            return m_connArgs;
          }

          IsolationLevel getCurrentIsolationLevel() const noexcept {
            return m_isolationLevel;
          }

          void execute(thrift::StatementResult& result,
              const std::string& sql,
              const std::map<int32_t, thrift::OutputParameter>& outputParams,
              const thrift::StatementAttrs& attrs);

          void executeUpdate(thrift::UpdateResult& result,
              const std::vector<std::string>& sqls,
              const thrift::StatementAttrs& attrs);

          void executeQuery(thrift::RowSet& result, const std::string& sql,
              const thrift::StatementAttrs& attrs);

          void prepareStatement(thrift::PrepareResult& result,
              const std::string& sql,
              const std::map<int32_t, thrift::OutputParameter>& outputParams,
              const thrift::StatementAttrs& attrs);

          void executePrepared(thrift::StatementResult& result,
              thrift::PrepareResult& prepResult, const thrift::Row& params,
              const std::map<int32_t, thrift::OutputParameter>& outputParams,
              const thrift::StatementAttrs& attrs);

          void executePreparedUpdate(thrift::UpdateResult& result,
              thrift::PrepareResult& prepResult, const thrift::Row& params,
              const thrift::StatementAttrs& attrs);

          void executePreparedQuery(thrift::RowSet& result,
              thrift::PrepareResult& prepResult, const thrift::Row& params,
              const thrift::StatementAttrs& attrs);

          void executePreparedBatch(thrift::UpdateResult& result,
              thrift::PrepareResult& prepResult,
              const std::vector<thrift::Row>& paramsBatch,
              const thrift::StatementAttrs& attrs);

          void prepareAndExecute(thrift::StatementResult& result,
              const std::string& sql,
              const std::vector<thrift::Row>& paramsBatch,
              const std::map<int32_t, thrift::OutputParameter>& outputParams,
              const thrift::StatementAttrs& attrs);

          void getNextResultSet(thrift::RowSet& result,
              const int64_t cursorId, const int8_t otherResultSetBehaviour);

          void getBlobChunk(thrift::BlobChunk& result, const int32_t lobId,
              const int64_t offset, const int32_t size,
              const bool freeLobAtEnd);

          void getClobChunk(thrift::ClobChunk& result, const int32_t lobId,
              const int64_t offset, const int32_t size,
              const bool freeLobAtEnd);

          int64_t sendBlobChunk(thrift::BlobChunk& chunk);

          int64_t sendClobChunk(thrift::ClobChunk& chunk);

          void freeLob(const int32_t lobId);

          void scrollCursor(thrift::RowSet& result, const int64_t cursorId,
              const int32_t offset, const bool offsetIsAbsolute,
              const bool fetchReverse, const int32_t fetchSize);

          void executeCursorUpdate(const int64_t cursorId,
              const thrift::CursorUpdateOperation::type operation,
              const thrift::Row& changedRow,
              const std::vector<int32_t>& changedColumns,
              const int32_t changedRowIndex);

          void executeBatchCursorUpdate(const int64_t cursorId,
              const std::vector<thrift::CursorUpdateOperation::type>& operations,
              const std::vector<thrift::Row>& changedRows,
              const std::vector<std::vector<int32_t> >& changedColumnsList,
              const std::vector<int32_t>& changedRowIndexes);

          void beginTransaction(const IsolationLevel isolationLevel);

          void setTransactionAttribute(const TransactionAttribute flag,
              bool isTrue);

          bool getTransactionAttribute(const TransactionAttribute flag);

          void getTransactionAttributes(
              std::map<thrift::TransactionAttribute::type, bool>& result);

          void commitTransaction(const bool startNewTransaction);

          void rollbackTransaction(const bool startNewTransaction);

          void fetchActiveConnections(
              std::vector<thrift::ConnectionProperties>& result);

          void fetchActiveStatements(std::map<int64_t, std::string>& result);

          void getServiceMetaData(thrift::ServiceMetaData& result);

          void getSchemaMetaData(thrift::RowSet& result,
              const thrift::ServiceMetaDataCall::type schemaCall,
              thrift::ServiceMetaDataArgs& metadataArgs);

          void getIndexInfo(thrift::RowSet& result,
              thrift::ServiceMetaDataArgs& metadataArgs, const bool unique,
              const bool approximate);

          void getUDTs(thrift::RowSet& result,
              thrift::ServiceMetaDataArgs& metadataArgs,
              const std::vector<thrift::SnappyType::type>& types);

          void getBestRowIdentifier(thrift::RowSet& result,
              thrift::ServiceMetaDataArgs& metadataArgs, const int32_t scope,
              const bool nullable);

          void closeResultSet(const int64_t cursorId);

          void cancelStatement(const int64_t stmtId);

          void closeStatement(const int64_t stmtId);

          void bulkClose(const std::vector<thrift::EntityId>& entities);

          void close();

          const std::vector<thrift::HostAddress>& getLocators() const
              noexcept {
            return m_connHosts;
          }

          const std::set<std::string>& getServerGrps() const noexcept {
            return m_serverGroups;
          }

          inline bool isFrameTransport() const noexcept {
            return m_useFramedTransport;
          }

        };

      } /* namespace impl */
    } /* namespace client */
  } /* namespace snappydata */
} /* namespace io */

#endif /* CLIENTSERVICE_H_ */
