/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

#ifndef CLIENTTRANSPORT_H_
#define CLIENTTRANSPORT_H_

#include <thrift/transport/TSocket.h>

namespace io {
namespace snappydata {
namespace client {
namespace impl {

  /**
   * Base abstract class for extensions to TTransport used by connections.
   */
  class ClientTransport {
  public:
    virtual ~ClientTransport() {
    }

    virtual bool isTransportOpen() = 0;

    virtual void closeTransport() = 0;

    virtual void setReceiveBufferSize(uint32_t rsz) = 0;

    virtual void setSendBufferSize(uint32_t wsz) = 0;

    virtual uint32_t getReceiveBufferSize() noexcept = 0;

    virtual uint32_t getSendBufferSize() noexcept = 0;

    virtual apache::thrift::transport::TSocket* getSocket() noexcept = 0;
  };

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* CLIENTTRANSPORT_H_ */
