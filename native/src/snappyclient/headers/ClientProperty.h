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

#ifndef CLIENTPROPERTY_H_
#define CLIENTPROPERTY_H_

#include <string>

namespace io {
namespace snappydata {
namespace client {

  /**
   * Lists the valid client system properties that are interpreted by
   * different parts of the product. Users must use
   * {@code SystemProperties} to read/write system properties.
   */
  class ClientProperty {
  private:
    /** disabled constructors and assignment operator */
    ClientProperty();
    ClientProperty(const ClientProperty&);
    ClientProperty& operator=(const ClientProperty&);

  public:

    /**
     * The default prefix used by product for all client system properties.
     * Users can always define and use any other property names but
     * internally product will recognize the properties and adjust
     * behaviour (if applicable) only if prefixed by this string.
     */
    static const std::string DEFAULT_PROPERTY_NAME_PREFIX;

    static const int DEFAULT_INPUT_BUFFER_SIZE = 32 * 1024;
    static const int DEFAULT_OUTPUT_BUFFER_SIZE = 32 * 1024;
    static const int DEFAULT_DNS_CACHE_SIZE = 300;
    static const int DEFAULT_DNS_CACHE_FLUSH_INTERVAL = 3600;

    /** The name of the "log-file" property */
    static const std::string LOG_FILE_NAME;

    /** The name of the "log-level" property */
    static const std::string LOG_LEVEL_NAME;

    /**
     * The socket buffer size to use for reading.
     */
    static const std::string SOCKET_INPUT_BUFFER_SIZE_NAME;

    /**
     * The socket buffer size to use for writing.
     */
    static const std::string SOCKET_OUTPUT_BUFFER_SIZE_NAME;

    /**
     * TCP KeepAlive IDLE timeout in seconds for the network server and
     * client sockets. This is the idle time after which a TCP KeepAlive
     * probe is sent over the socket to determine if the other side
     * is alive or not.
     */
    static const std::string KEEPALIVE_IDLE_NAME;

    /**
     * TCP KeepAlive INTERVAL timeout in seconds for the network server
     * and client sockets. This is the time interval between successive TCP
     * KeepAlive probes if there is no response to the previous probe
     * ({@link #KEEPALIVE_IDLE_NAME}) to determine if other side is alive.
     *
     * Note that this may not be supported by all platforms (e.g. Solaris),
     * in which case this will be ignored and an info-level message logged
     * that the option could not be enabled on the socket.
     */
    static const std::string KEEPALIVE_INTVL_NAME;

    /**
     * TCP KeepAlive COUNT for the network server and client sockets.
     * This is the number of TCP KeepAlive probes sent before declaring
     * other side to be dead.
     *
     * Note that this may not be supported by all platforms (e.g. Solaris),
     * in which case this will be ignored and an info-level message logged
     * that the option could not be enabled on the socket.
     */
    static const std::string KEEPALIVE_CNT_NAME;

    /**
     * The maximum size of {@link DNSCacheService}. Zero or negative means
     * to not use any caching in application. Default is
     * {@link #DEFAULT_DNS_CACHE_SIZE}.
     */
    static const std::string DNS_CACHE_SIZE;

    /**
     * Time interval in seconds after which {@link DNSCacheService}
     * is flushed and refreshed on demand for DNS entries that do no
     * specify a TTL. A value of zero or negative means the cache is
     * never flushed of such records. Default is
     * {@link #DEFAULT_DNS_CACHE_FLUSH_INTERVAL} seconds.
     */
    static const std::string DNS_CACHE_FLUSH_INTERVAL;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* CLIENTPROPERTY_H_ */
