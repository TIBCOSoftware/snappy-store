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

#ifndef FRAMEDCLIENTTRANSPORT_H_
#define FRAMEDCLIENTTRANSPORT_H_

#include "BufferedClientTransport.h"

namespace io {
namespace snappydata {
namespace client {
namespace impl {

  class FramedClientTransport : virtual public TFramedTransport,
      virtual public ClientTransport {
  public:
    FramedClientTransport(
        const boost::shared_ptr<BufferedClientTransport>& transport,
        uint32_t wsz);

    virtual ~FramedClientTransport() {
    }

    bool isTransportOpen() {
      return isOpen();
    }

    void closeTransport() {
      close();
    }

    void setReceiveBufferSize(uint32_t rsz);

    void setSendBufferSize(uint32_t wsz);

    uint32_t getReceiveBufferSize() noexcept;

    uint32_t getSendBufferSize() noexcept;

    TSocket* getSocket() noexcept;

  private:
    inline boost::shared_ptr<BufferedClientTransport> getClientTransport()
        noexcept {
      return boost::static_pointer_cast<BufferedClientTransport>(
          getUnderlyingTransport());
    }
  };

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* FRAMEDCLIENTTRANSPORT_H_ */
