/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef SNAPPYDATA_STRUCT_CLOBCHUNK_H
#define SNAPPYDATA_STRUCT_CLOBCHUNK_H


#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_BlobChunk.h"

#include "snappydata_types.h"

namespace io { namespace snappydata { namespace thrift {

typedef struct _ClobChunk__isset {
  _ClobChunk__isset() : lobId(false), offset(false), totalLength(false) {}
  bool lobId :1;
  bool offset :1;
  bool totalLength :1;
} _ClobChunk__isset;

class ClobChunk {
 public:

  ClobChunk(const ClobChunk&);
  ClobChunk(ClobChunk&&) noexcept;
  ClobChunk& operator=(const ClobChunk&);
  ClobChunk& operator=(ClobChunk&&) noexcept;
  ClobChunk() : chunk(), last(0), lobId(0), offset(0), totalLength(0) {
  }

  virtual ~ClobChunk() noexcept;
  std::string chunk;
  bool last;
  int64_t lobId;
  int64_t offset;
  int64_t totalLength;

  _ClobChunk__isset __isset;

  void __set_chunk(const std::string& val);

  void __set_last(const bool val);

  void __set_lobId(const int64_t val);

  void __set_offset(const int64_t val);

  void __set_totalLength(const int64_t val);

  bool operator == (const ClobChunk & rhs) const
  {
    if (!(chunk == rhs.chunk))
      return false;
    if (!(last == rhs.last))
      return false;
    if (__isset.lobId != rhs.__isset.lobId)
      return false;
    else if (__isset.lobId && !(lobId == rhs.lobId))
      return false;
    if (__isset.offset != rhs.__isset.offset)
      return false;
    else if (__isset.offset && !(offset == rhs.offset))
      return false;
    if (__isset.totalLength != rhs.__isset.totalLength)
      return false;
    else if (__isset.totalLength && !(totalLength == rhs.totalLength))
      return false;
    return true;
  }
  bool operator != (const ClobChunk &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const ClobChunk & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

  virtual void printTo(std::ostream& out) const;
};

void swap(ClobChunk &a, ClobChunk &b) noexcept;

inline std::ostream& operator<<(std::ostream& out, const ClobChunk& obj)
{
  obj.printTo(out);
  return out;
}

}}} // namespace

#endif
