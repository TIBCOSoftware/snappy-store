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

#include "BufferedClientTransport.h"

#include "common/Base.h"

extern "C" {
#ifdef _WINDOWS
#include <WinSock2.h>
#else
#include <arpa/inet.h>
#endif
#include <stdlib.h>
}
#include <assert.h>

using namespace io::snappydata::client::impl;

BufferedClientTransport::BufferedClientTransport(
    const boost::shared_ptr<TSocket>& socket, uint32_t rsz, uint32_t wsz,
    bool writeFramed) : TBufferedTransport(socket, rsz, wsz),
        m_writeFramed(writeFramed), m_doWriteFrameSize(true) {
  initStart();
  this->open();
}

void BufferedClientTransport::initStart() {
  if (m_writeFramed) {
    // position the buffer to skip the length of frame at the start
    wBase_ = wBuf_.get() + sizeof(uint32_t);
  } else {
    wBase_ = wBuf_.get();
  }
}

void BufferedClientTransport::writeFrameSize() {
  if (m_writeFramed) {
    uint32_t sz;

    assert(wBufSize_ > sizeof(sz));

    // Slip the frame size into the start of the buffer.
    sz = ::htonl(static_cast<uint32_t>(wBase_ - (wBuf_.get() + sizeof(sz))));
    ::memcpy(wBuf_.get(), &sz, sizeof(sz));
  }
}

void BufferedClientTransport::writeSlow(const uint8_t* buf, uint32_t len) {
  // modified from Thrift TBufferedTransport::writeSlow

  uint32_t have_bytes = static_cast<uint32_t>(wBase_ - wBuf_.get());
  uint32_t space = static_cast<uint32_t>(wBound_ - wBase_);

  // We should only take the slow path if we can't accomodate the write
  // with the free space already in the buffer.
  assert(wBound_ - wBase_ < static_cast<ptrdiff_t>(len));

  // Now here's the tricky question: should we copy data from buf into our
  // internal buffer and write it from there, or should we just write out
  // the current internal buffer in one syscall and write out buf in another.
  // If our currently buffered data plus buf is at least double our buffer
  // size, we will have to do two syscalls no matter what (except in the
  // degenerate case when our buffer is empty), so there is no use copying.
  // Otherwise, there is sort of a sliding scale.  If we have N-1 bytes
  // buffered and need to write 2, it would be crazy to do two syscalls.
  // On the other hand, if we have 2 bytes buffered and are writing 2N-3,
  // we can save a syscall in the short term by loading up our buffer, writing
  // it out, and copying the rest of the bytes into our buffer.  Of course,
  // if we get another 2-byte write, we haven't saved any syscalls at all,
  // and have just copied nearly 2N bytes for nothing.  Finding a perfect
  // policy would require predicting the size of future writes, so we're just
  // going to always eschew syscalls if we have less than 2N bytes to write.

  // The case where we have to do two syscalls.
  // This case also covers the case where the buffer is empty,
  // but it is clearer (I think) to think of it as two separate cases.
  if (((have_bytes + len) >= (2 * wBufSize_)) || (have_bytes == 0)) {
    if (have_bytes > 0) {
      if (m_doWriteFrameSize) {
        writeFrameSize();
        m_doWriteFrameSize = false;
      }
      transport_->write(wBuf_.get(), have_bytes);
    }
    transport_->write(buf, len);
    return;
  }

  // Fill up our internal buffer for a write.
  ::memcpy(wBase_, buf, space);
  buf += space;
  len -= space;
  if (m_doWriteFrameSize) {
    writeFrameSize();
    m_doWriteFrameSize = false;
  }
  transport_->write(wBuf_.get(), wBufSize_);

  // Copy the rest into our buffer.
  assert(len < wBufSize_);
  wBase_ = wBuf_.get();
  ::memcpy(wBase_, buf, len);
  wBase_ += len;
}

namespace _snappy_impl {
  struct PositionInit {
    BufferedClientTransport& m_trans;

    PositionInit(BufferedClientTransport& trans) : m_trans(trans) {
    }

    ~PositionInit() {
      m_trans.initStart();
    }

  private:
    // no copy constructor or assignment operator
    PositionInit(const PositionInit& other) = delete;
    PositionInit& operator=(const PositionInit& other) = delete;
  };
}

void BufferedClientTransport::flush() {
  _snappy_impl::PositionInit posInit(*this);

  if (m_doWriteFrameSize) {
    writeFrameSize();
    TBufferedTransport::flush();
  } else {
    TBufferedTransport::flush();
    m_doWriteFrameSize = true;
  }
}

void BufferedClientTransport::setReceiveBufferSize(uint32_t rsz) {
  if (rsz != rBufSize_) {
    this->flush();
    m_doWriteFrameSize = true;

    rBuf_.reset(new uint8_t[rsz]);
    rBufSize_ = rsz;
    initPointers();
    initStart();
  }
}

void BufferedClientTransport::setSendBufferSize(uint32_t wsz) {
  if (wsz != wBufSize_) {
    this->flush();
    m_doWriteFrameSize = true;

    wBuf_.reset(new uint8_t[wsz]);
    wBufSize_ = wsz;
    initPointers();
    initStart();
  }
}

uint32_t BufferedClientTransport::getReceiveBufferSize() noexcept {
  return rBufSize_;
}

uint32_t BufferedClientTransport::getSendBufferSize() noexcept {
  return wBufSize_;
}

TSocket* BufferedClientTransport::getSocket() noexcept {
  return static_cast<TSocket*>(transport_.get());
}
