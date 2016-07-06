/** Generated file. DO NOT EDIT */

#include "ClientAttribute.h"

namespace io { namespace snappydata { namespace client {

const char* ClientAttribute::addToHashSet(const char* k) {
  s_attributes.insert(k);
  return k;
}

void ClientAttribute::staticInitialize() {
}

std::unordered_set<std::string> ClientAttribute::s_attributes;

const std::string ClientAttribute::USERNAME = ClientAttribute::addToHashSet("user");
const std::string ClientAttribute::USERNAME_ALT = ClientAttribute::addToHashSet("UserName");
const std::string ClientAttribute::PASSWORD = ClientAttribute::addToHashSet("password");
const std::string ClientAttribute::READ_TIMEOUT = ClientAttribute::addToHashSet("read-timeout");
const std::string ClientAttribute::KEEPALIVE_IDLE = ClientAttribute::addToHashSet("keepalive-idle");
const std::string ClientAttribute::KEEPALIVE_INTVL = ClientAttribute::addToHashSet("keepalive-interval");
const std::string ClientAttribute::KEEPALIVE_CNT = ClientAttribute::addToHashSet("keepalive-count");
const std::string ClientAttribute::LOAD_BALANCE = ClientAttribute::addToHashSet("load-balance");
const std::string ClientAttribute::SECONDARY_LOCATORS = ClientAttribute::addToHashSet("secondary-locators");
const std::string ClientAttribute::SERVER_GROUPS = ClientAttribute::addToHashSet("server-groups");
const std::string ClientAttribute::SINGLE_HOP_ENABLED = ClientAttribute::addToHashSet("single-hop-enabled");
const std::string ClientAttribute::SINGLE_HOP_MAX_CONNECTIONS = ClientAttribute::addToHashSet("single-hop-max-connections");
const std::string ClientAttribute::DISABLE_STREAMING = ClientAttribute::addToHashSet("disable-streaming");
const std::string ClientAttribute::SKIP_LISTENERS = ClientAttribute::addToHashSet("skip-listeners");
const std::string ClientAttribute::SKIP_CONSTRAINT_CHECKS = ClientAttribute::addToHashSet("skip-constraint-checks");
const std::string ClientAttribute::TX_SYNC_COMMITS = ClientAttribute::addToHashSet("sync-commits");
const std::string ClientAttribute::DISABLE_THINCLIENT_CANCEL = ClientAttribute::addToHashSet("disable-cancel");
const std::string ClientAttribute::DISABLE_TX_BATCHING = ClientAttribute::addToHashSet("disable-tx-batching");
const std::string ClientAttribute::QUERY_HDFS = ClientAttribute::addToHashSet("query-HDFS");
const std::string ClientAttribute::LOG_FILE = ClientAttribute::addToHashSet("log-file");
const std::string ClientAttribute::LOG_LEVEL = ClientAttribute::addToHashSet("log-level");
const std::string ClientAttribute::LOG_APPEND = ClientAttribute::addToHashSet("log-append");
const std::string ClientAttribute::LOG_FILE_STAMP = ClientAttribute::addToHashSet("log-file-ns");
const std::string ClientAttribute::SECURITY_MECHANISM = ClientAttribute::addToHashSet("security-mechanism");
const std::string ClientAttribute::SSL = ClientAttribute::addToHashSet("ssl");
const std::string ClientAttribute::SSL_PROPERTIES = ClientAttribute::addToHashSet("ssl-properties");
const std::string ClientAttribute::THRIFT_USE_BINARY_PROTOCOL = ClientAttribute::addToHashSet("binary-protocol");

} } }
