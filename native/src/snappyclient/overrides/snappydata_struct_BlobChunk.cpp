/*
 * Changes for GemFireXD distributed data platform.
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
 * Portions Copyright (c) 2017-2021 TIBCO Software Inc. All rights reserved.
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

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include "snappydata_struct_BlobChunk.h"

#include <algorithm>
#include <ostream>

#include "Utils.h"

namespace io { namespace snappydata { namespace thrift {


BlobChunk::~BlobChunk() noexcept {
}


void BlobChunk::__set_chunk(const std::string& val) {
  this->chunk = val;
}

void BlobChunk::__set_last(const bool val) {
  this->last = val;
}

void BlobChunk::__set_lobId(const int64_t val) {
  this->lobId = val;
__isset.lobId = true;
}

void BlobChunk::__set_offset(const int64_t val) {
  this->offset = val;
__isset.offset = true;
}

void BlobChunk::__set_totalLength(const int64_t val) {
  this->totalLength = val;
__isset.totalLength = true;
}

std::ostream& operator<<(std::ostream& out, const BlobChunk& obj) {
  obj.printTo(out);
  return out;
}

uint32_t BlobChunk::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_chunk = false;
  bool isset_last = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->chunk);
          isset_chunk = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_BOOL) {
          xfer += iprot->readBool(this->last);
          isset_last = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->lobId);
          this->__isset.lobId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->offset);
          this->__isset.offset = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->totalLength);
          this->__isset.totalLength = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      default:
        xfer += iprot->skip(ftype);
        break;
    }
    xfer += iprot->readFieldEnd();
  }

  xfer += iprot->readStructEnd();

  if (!isset_chunk)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_last)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t BlobChunk::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("BlobChunk");

  xfer += oprot->writeFieldBegin("chunk", ::apache::thrift::protocol::T_STRING, 1);
  xfer += oprot->writeBinary(this->chunk);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("last", ::apache::thrift::protocol::T_BOOL, 2);
  xfer += oprot->writeBool(this->last);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.lobId) {
    xfer += oprot->writeFieldBegin("lobId", ::apache::thrift::protocol::T_I64, 3);
    xfer += oprot->writeI64(this->lobId);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.offset) {
    xfer += oprot->writeFieldBegin("offset", ::apache::thrift::protocol::T_I64, 4);
    xfer += oprot->writeI64(this->offset);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.totalLength) {
    xfer += oprot->writeFieldBegin("totalLength", ::apache::thrift::protocol::T_I64, 5);
    xfer += oprot->writeI64(this->totalLength);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(BlobChunk &a, BlobChunk &b) {
  using ::std::swap;
  swap(a.chunk, b.chunk);
  swap(a.last, b.last);
  swap(a.lobId, b.lobId);
  swap(a.offset, b.offset);
  swap(a.totalLength, b.totalLength);
  swap(a.__isset, b.__isset);
}

BlobChunk::BlobChunk(const BlobChunk& other4) {
  chunk = other4.chunk;
  last = other4.last;
  lobId = other4.lobId;
  offset = other4.offset;
  totalLength = other4.totalLength;
  __isset = other4.__isset;
}
BlobChunk::BlobChunk(BlobChunk&& other5) {
  chunk = std::move(other5.chunk);
  last = std::move(other5.last);
  lobId = std::move(other5.lobId);
  offset = std::move(other5.offset);
  totalLength = std::move(other5.totalLength);
  __isset = std::move(other5.__isset);
}
BlobChunk& BlobChunk::operator=(const BlobChunk& other6) {
  chunk = other6.chunk;
  last = other6.last;
  lobId = other6.lobId;
  offset = other6.offset;
  totalLength = other6.totalLength;
  __isset = other6.__isset;
  return *this;
}
BlobChunk& BlobChunk::operator=(BlobChunk&& other7) {
  chunk = std::move(other7.chunk);
  last = std::move(other7.last);
  lobId = std::move(other7.lobId);
  offset = std::move(other7.offset);
  totalLength = std::move(other7.totalLength);
  __isset = std::move(other7.__isset);
  return *this;
}
void BlobChunk::printTo(std::ostream& out) const {
  // print the bytes in hex form and not as string
  out << "BlobChunk(chunk=0x";
  client::Utils::toHexString(chunk.data(), chunk.size(), out);
  if (last) {
    out << ", last=true";
  }
  if (__isset.lobId) {
    out << ", lobId=" << lobId;
  }
  if (__isset.offset) {
    out << ", offset=" << offset;
  }
  if (__isset.totalLength) {
    out << ", totalLength=" << totalLength;
  }
  out << ')';
}

}}} // namespace
