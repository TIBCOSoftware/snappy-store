/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

#include "FramedClientTransport.h"

#include "snappydata_constants.h"
#include "common/Base.h"

using namespace io::snappydata::client::impl;

FramedClientTransport::FramedClientTransport(
    const boost::shared_ptr<BufferedClientTransport>& transport, uint32_t wsz) :
    TFramedTransport(boost::static_pointer_cast<TTransport>(transport), wsz) {
  setMaxFrameSize(
      io::snappydata::thrift::snappydataConstants::DEFAULT_LOB_CHUNKSIZE * 2);
}

void FramedClientTransport::setReceiveBufferSize(uint32_t rsz) {
  getClientTransport()->setReceiveBufferSize(rsz);
}

void FramedClientTransport::setSendBufferSize(uint32_t wsz) {
  getClientTransport()->setSendBufferSize(wsz);
}

uint32_t FramedClientTransport::getReceiveBufferSize() noexcept {
  return getClientTransport()->getReceiveBufferSize();
}

uint32_t FramedClientTransport::getSendBufferSize() noexcept {
  return getClientTransport()->getSendBufferSize();
}

TSocket* FramedClientTransport::getSocket() noexcept {
  return getClientTransport()->getSocket();
}
