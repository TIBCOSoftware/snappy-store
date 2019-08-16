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

#ifndef BUFFEREDCLIENTTRANSPORT_H_
#define BUFFEREDCLIENTTRANSPORT_H_

#include <boost/shared_ptr.hpp>

#include <thrift/transport/TSocket.h>
#include <thrift/transport/TBufferTransports.h>

#include "ClientTransport.h"

using namespace apache::thrift::transport;

namespace io {
namespace snappydata {
namespace client {
namespace impl {

  class BufferedClientTransport;

  /**
   * This exposes a few protected members and enables writing "frames"
   * without the overhead of having to create buffer for entire message
   * just writing size of first buffer as expected by SnappyData selectors.
   */
  class BufferedClientTransport : public TBufferedTransport,
      public ClientTransport {
  public:
    BufferedClientTransport(const boost::shared_ptr<TSocket>& socket,
        uint32_t rsz, uint32_t wsz, bool writeFramed);

    virtual ~BufferedClientTransport() {
    }

    void initStart();

    void writeFrameSize();

    virtual void writeSlow(const uint8_t* buf, uint32_t len);

    virtual void flush();

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
    const bool m_writeFramed;
    bool m_doWriteFrameSize;
  };

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* BUFFEREDCLIENTTRANSPORT_H_ */
