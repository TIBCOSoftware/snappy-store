/**
 * Autogenerated by Thrift Compiler (0.14.1)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include "impl/pch.h"

#include "impl/LocatorService.h"

namespace io { namespace snappydata { namespace thrift {


LocatorService_getPreferredServer_args::~LocatorService_getPreferredServer_args() noexcept {
}


uint32_t LocatorService_getPreferredServer_args::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;


  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_SET) {
          {
            this->serverTypes.clear();
            uint32_t _size500;
            ::apache::thrift::protocol::TType _etype503;
            xfer += iprot->readSetBegin(_etype503, _size500);
            uint32_t _i504;
            for (_i504 = 0; _i504 < _size500; ++_i504)
            {
              ServerType::type _elem505;
              int32_t ecast506;
              xfer += iprot->readI32(ecast506);
              _elem505 = (ServerType::type)ecast506;
              this->serverTypes.insert(_elem505);
            }
            xfer += iprot->readSetEnd();
          }
          this->__isset.serverTypes = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_SET) {
          {
            this->serverGroups.clear();
            uint32_t _size507;
            ::apache::thrift::protocol::TType _etype510;
            xfer += iprot->readSetBegin(_etype510, _size507);
            uint32_t _i511;
            for (_i511 = 0; _i511 < _size507; ++_i511)
            {
              std::string _elem512;
              xfer += iprot->readString(_elem512);
              this->serverGroups.insert(_elem512);
            }
            xfer += iprot->readSetEnd();
          }
          this->__isset.serverGroups = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_SET) {
          {
            this->failedServers.clear();
            uint32_t _size513;
            ::apache::thrift::protocol::TType _etype516;
            xfer += iprot->readSetBegin(_etype516, _size513);
            uint32_t _i517;
            for (_i517 = 0; _i517 < _size513; ++_i517)
            {
              HostAddress _elem518;
              xfer += _elem518.read(iprot);
              this->failedServers.insert(_elem518);
            }
            xfer += iprot->readSetEnd();
          }
          this->__isset.failedServers = true;
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

  return xfer;
}

uint32_t LocatorService_getPreferredServer_args::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("LocatorService_getPreferredServer_args");

  xfer += oprot->writeFieldBegin("serverTypes", ::apache::thrift::protocol::T_SET, 1);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_I32, static_cast<uint32_t>(this->serverTypes.size()));
    std::set<ServerType::type> ::const_iterator _iter519;
    for (_iter519 = this->serverTypes.begin(); _iter519 != this->serverTypes.end(); ++_iter519)
    {
      xfer += oprot->writeI32((int32_t)(*_iter519));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>(this->serverGroups.size()));
    std::set<std::string> ::const_iterator _iter520;
    for (_iter520 = this->serverGroups.begin(); _iter520 != this->serverGroups.end(); ++_iter520)
    {
      xfer += oprot->writeString((*_iter520));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->failedServers.size()));
    std::set<HostAddress> ::const_iterator _iter521;
    for (_iter521 = this->failedServers.begin(); _iter521 != this->failedServers.end(); ++_iter521)
    {
      xfer += (*_iter521).write(oprot);
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}


LocatorService_getPreferredServer_pargs::~LocatorService_getPreferredServer_pargs() noexcept {
}


uint32_t LocatorService_getPreferredServer_pargs::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("LocatorService_getPreferredServer_pargs");

  xfer += oprot->writeFieldBegin("serverTypes", ::apache::thrift::protocol::T_SET, 1);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_I32, static_cast<uint32_t>((*(this->serverTypes)).size()));
    std::set<ServerType::type> ::const_iterator _iter522;
    for (_iter522 = (*(this->serverTypes)).begin(); _iter522 != (*(this->serverTypes)).end(); ++_iter522)
    {
      xfer += oprot->writeI32((int32_t)(*_iter522));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>((*(this->serverGroups)).size()));
    std::set<std::string> ::const_iterator _iter523;
    for (_iter523 = (*(this->serverGroups)).begin(); _iter523 != (*(this->serverGroups)).end(); ++_iter523)
    {
      xfer += oprot->writeString((*_iter523));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>((*(this->failedServers)).size()));
    std::set<HostAddress> ::const_iterator _iter524;
    for (_iter524 = (*(this->failedServers)).begin(); _iter524 != (*(this->failedServers)).end(); ++_iter524)
    {
      xfer += (*_iter524).write(oprot);
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}


LocatorService_getPreferredServer_result::~LocatorService_getPreferredServer_result() noexcept {
}


uint32_t LocatorService_getPreferredServer_result::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;


  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 0:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->success.read(iprot);
          this->__isset.success = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 1:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->error.read(iprot);
          this->__isset.error = true;
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

  return xfer;
}

uint32_t LocatorService_getPreferredServer_result::write(::apache::thrift::protocol::TProtocol* oprot) const {

  uint32_t xfer = 0;

  xfer += oprot->writeStructBegin("LocatorService_getPreferredServer_result");

  if (this->__isset.success) {
    xfer += oprot->writeFieldBegin("success", ::apache::thrift::protocol::T_STRUCT, 0);
    xfer += this->success.write(oprot);
    xfer += oprot->writeFieldEnd();
  } else if (this->__isset.error) {
    xfer += oprot->writeFieldBegin("error", ::apache::thrift::protocol::T_STRUCT, 1);
    xfer += this->error.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}


LocatorService_getPreferredServer_presult::~LocatorService_getPreferredServer_presult() noexcept {
}


uint32_t LocatorService_getPreferredServer_presult::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;


  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 0:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += (*(this->success)).read(iprot);
          this->__isset.success = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 1:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->error.read(iprot);
          this->__isset.error = true;
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

  return xfer;
}


LocatorService_getAllServersWithPreferredServer_args::~LocatorService_getAllServersWithPreferredServer_args() noexcept {
}


uint32_t LocatorService_getAllServersWithPreferredServer_args::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;


  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_SET) {
          {
            this->serverTypes.clear();
            uint32_t _size525;
            ::apache::thrift::protocol::TType _etype528;
            xfer += iprot->readSetBegin(_etype528, _size525);
            uint32_t _i529;
            for (_i529 = 0; _i529 < _size525; ++_i529)
            {
              ServerType::type _elem530;
              int32_t ecast531;
              xfer += iprot->readI32(ecast531);
              _elem530 = (ServerType::type)ecast531;
              this->serverTypes.insert(_elem530);
            }
            xfer += iprot->readSetEnd();
          }
          this->__isset.serverTypes = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_SET) {
          {
            this->serverGroups.clear();
            uint32_t _size532;
            ::apache::thrift::protocol::TType _etype535;
            xfer += iprot->readSetBegin(_etype535, _size532);
            uint32_t _i536;
            for (_i536 = 0; _i536 < _size532; ++_i536)
            {
              std::string _elem537;
              xfer += iprot->readString(_elem537);
              this->serverGroups.insert(_elem537);
            }
            xfer += iprot->readSetEnd();
          }
          this->__isset.serverGroups = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_SET) {
          {
            this->failedServers.clear();
            uint32_t _size538;
            ::apache::thrift::protocol::TType _etype541;
            xfer += iprot->readSetBegin(_etype541, _size538);
            uint32_t _i542;
            for (_i542 = 0; _i542 < _size538; ++_i542)
            {
              HostAddress _elem543;
              xfer += _elem543.read(iprot);
              this->failedServers.insert(_elem543);
            }
            xfer += iprot->readSetEnd();
          }
          this->__isset.failedServers = true;
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

  return xfer;
}

uint32_t LocatorService_getAllServersWithPreferredServer_args::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("LocatorService_getAllServersWithPreferredServer_args");

  xfer += oprot->writeFieldBegin("serverTypes", ::apache::thrift::protocol::T_SET, 1);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_I32, static_cast<uint32_t>(this->serverTypes.size()));
    std::set<ServerType::type> ::const_iterator _iter544;
    for (_iter544 = this->serverTypes.begin(); _iter544 != this->serverTypes.end(); ++_iter544)
    {
      xfer += oprot->writeI32((int32_t)(*_iter544));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>(this->serverGroups.size()));
    std::set<std::string> ::const_iterator _iter545;
    for (_iter545 = this->serverGroups.begin(); _iter545 != this->serverGroups.end(); ++_iter545)
    {
      xfer += oprot->writeString((*_iter545));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->failedServers.size()));
    std::set<HostAddress> ::const_iterator _iter546;
    for (_iter546 = this->failedServers.begin(); _iter546 != this->failedServers.end(); ++_iter546)
    {
      xfer += (*_iter546).write(oprot);
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}


LocatorService_getAllServersWithPreferredServer_pargs::~LocatorService_getAllServersWithPreferredServer_pargs() noexcept {
}


uint32_t LocatorService_getAllServersWithPreferredServer_pargs::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("LocatorService_getAllServersWithPreferredServer_pargs");

  xfer += oprot->writeFieldBegin("serverTypes", ::apache::thrift::protocol::T_SET, 1);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_I32, static_cast<uint32_t>((*(this->serverTypes)).size()));
    std::set<ServerType::type> ::const_iterator _iter547;
    for (_iter547 = (*(this->serverTypes)).begin(); _iter547 != (*(this->serverTypes)).end(); ++_iter547)
    {
      xfer += oprot->writeI32((int32_t)(*_iter547));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>((*(this->serverGroups)).size()));
    std::set<std::string> ::const_iterator _iter548;
    for (_iter548 = (*(this->serverGroups)).begin(); _iter548 != (*(this->serverGroups)).end(); ++_iter548)
    {
      xfer += oprot->writeString((*_iter548));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>((*(this->failedServers)).size()));
    std::set<HostAddress> ::const_iterator _iter549;
    for (_iter549 = (*(this->failedServers)).begin(); _iter549 != (*(this->failedServers)).end(); ++_iter549)
    {
      xfer += (*_iter549).write(oprot);
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}


LocatorService_getAllServersWithPreferredServer_result::~LocatorService_getAllServersWithPreferredServer_result() noexcept {
}


uint32_t LocatorService_getAllServersWithPreferredServer_result::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;


  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 0:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            this->success.clear();
            uint32_t _size550;
            ::apache::thrift::protocol::TType _etype553;
            xfer += iprot->readListBegin(_etype553, _size550);
            this->success.resize(_size550);
            uint32_t _i554;
            for (_i554 = 0; _i554 < _size550; ++_i554)
            {
              xfer += this->success[_i554].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.success = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 1:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->error.read(iprot);
          this->__isset.error = true;
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

  return xfer;
}

uint32_t LocatorService_getAllServersWithPreferredServer_result::write(::apache::thrift::protocol::TProtocol* oprot) const {

  uint32_t xfer = 0;

  xfer += oprot->writeStructBegin("LocatorService_getAllServersWithPreferredServer_result");

  if (this->__isset.success) {
    xfer += oprot->writeFieldBegin("success", ::apache::thrift::protocol::T_LIST, 0);
    {
      xfer += oprot->writeListBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->success.size()));
      std::vector<HostAddress> ::const_iterator _iter555;
      for (_iter555 = this->success.begin(); _iter555 != this->success.end(); ++_iter555)
      {
        xfer += (*_iter555).write(oprot);
      }
      xfer += oprot->writeListEnd();
    }
    xfer += oprot->writeFieldEnd();
  } else if (this->__isset.error) {
    xfer += oprot->writeFieldBegin("error", ::apache::thrift::protocol::T_STRUCT, 1);
    xfer += this->error.write(oprot);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}


LocatorService_getAllServersWithPreferredServer_presult::~LocatorService_getAllServersWithPreferredServer_presult() noexcept {
}


uint32_t LocatorService_getAllServersWithPreferredServer_presult::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;


  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 0:
        if (ftype == ::apache::thrift::protocol::T_LIST) {
          {
            (*(this->success)).clear();
            uint32_t _size556;
            ::apache::thrift::protocol::TType _etype559;
            xfer += iprot->readListBegin(_etype559, _size556);
            (*(this->success)).resize(_size556);
            uint32_t _i560;
            for (_i560 = 0; _i560 < _size556; ++_i560)
            {
              xfer += (*(this->success))[_i560].read(iprot);
            }
            xfer += iprot->readListEnd();
          }
          this->__isset.success = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 1:
        if (ftype == ::apache::thrift::protocol::T_STRUCT) {
          xfer += this->error.read(iprot);
          this->__isset.error = true;
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

  return xfer;
}

void LocatorServiceClient::getPreferredServer(HostAddress& _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers)
{
  send_getPreferredServer(serverTypes, serverGroups, failedServers);
  recv_getPreferredServer(_return);
}

void LocatorServiceClient::send_getPreferredServer(const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers)
{
  int32_t cseqid = 0;
  oprot_->writeMessageBegin("getPreferredServer", ::apache::thrift::protocol::T_CALL, cseqid);

  LocatorService_getPreferredServer_pargs args;
  args.serverTypes = &serverTypes;
  args.serverGroups = &serverGroups;
  args.failedServers = &failedServers;
  args.write(oprot_);

  oprot_->writeMessageEnd();
  oprot_->getTransport()->writeEnd();
  oprot_->getTransport()->flush();
}

void LocatorServiceClient::recv_getPreferredServer(HostAddress& _return)
{

  int32_t rseqid = 0;
  std::string fname;
  ::apache::thrift::protocol::TMessageType mtype;

  iprot_->readMessageBegin(fname, mtype, rseqid);
  if (mtype == ::apache::thrift::protocol::T_EXCEPTION) {
    ::apache::thrift::TApplicationException x;
    x.read(iprot_);
    iprot_->readMessageEnd();
    iprot_->getTransport()->readEnd();
    throw x;
  }
  if (mtype != ::apache::thrift::protocol::T_REPLY) {
    iprot_->skip(::apache::thrift::protocol::T_STRUCT);
    iprot_->readMessageEnd();
    iprot_->getTransport()->readEnd();
  }
  if (fname.compare("getPreferredServer") != 0) {
    iprot_->skip(::apache::thrift::protocol::T_STRUCT);
    iprot_->readMessageEnd();
    iprot_->getTransport()->readEnd();
  }
  LocatorService_getPreferredServer_presult result;
  result.success = &_return;
  result.read(iprot_);
  iprot_->readMessageEnd();
  iprot_->getTransport()->readEnd();

  if (result.__isset.success) {
    // _return pointer has now been filled
    return;
  }
  if (result.__isset.error) {
    throw result.error;
  }
  throw ::apache::thrift::TApplicationException(::apache::thrift::TApplicationException::MISSING_RESULT, "getPreferredServer failed: unknown result");
}

void LocatorServiceClient::getAllServersWithPreferredServer(std::vector<HostAddress> & _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers)
{
  send_getAllServersWithPreferredServer(serverTypes, serverGroups, failedServers);
  recv_getAllServersWithPreferredServer(_return);
}

void LocatorServiceClient::send_getAllServersWithPreferredServer(const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers)
{
  int32_t cseqid = 0;
  oprot_->writeMessageBegin("getAllServersWithPreferredServer", ::apache::thrift::protocol::T_CALL, cseqid);

  LocatorService_getAllServersWithPreferredServer_pargs args;
  args.serverTypes = &serverTypes;
  args.serverGroups = &serverGroups;
  args.failedServers = &failedServers;
  args.write(oprot_);

  oprot_->writeMessageEnd();
  oprot_->getTransport()->writeEnd();
  oprot_->getTransport()->flush();
}

void LocatorServiceClient::recv_getAllServersWithPreferredServer(std::vector<HostAddress> & _return)
{

  int32_t rseqid = 0;
  std::string fname;
  ::apache::thrift::protocol::TMessageType mtype;

  iprot_->readMessageBegin(fname, mtype, rseqid);
  if (mtype == ::apache::thrift::protocol::T_EXCEPTION) {
    ::apache::thrift::TApplicationException x;
    x.read(iprot_);
    iprot_->readMessageEnd();
    iprot_->getTransport()->readEnd();
    throw x;
  }
  if (mtype != ::apache::thrift::protocol::T_REPLY) {
    iprot_->skip(::apache::thrift::protocol::T_STRUCT);
    iprot_->readMessageEnd();
    iprot_->getTransport()->readEnd();
  }
  if (fname.compare("getAllServersWithPreferredServer") != 0) {
    iprot_->skip(::apache::thrift::protocol::T_STRUCT);
    iprot_->readMessageEnd();
    iprot_->getTransport()->readEnd();
  }
  LocatorService_getAllServersWithPreferredServer_presult result;
  result.success = &_return;
  result.read(iprot_);
  iprot_->readMessageEnd();
  iprot_->getTransport()->readEnd();

  if (result.__isset.success) {
    // _return pointer has now been filled
    return;
  }
  if (result.__isset.error) {
    throw result.error;
  }
  throw ::apache::thrift::TApplicationException(::apache::thrift::TApplicationException::MISSING_RESULT, "getAllServersWithPreferredServer failed: unknown result");
}

bool LocatorServiceProcessor::dispatchCall(::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol* oprot, const std::string& fname, int32_t seqid, void* callContext) {
  ProcessMap::iterator pfn;
  pfn = processMap_.find(fname);
  if (pfn == processMap_.end()) {
    iprot->skip(::apache::thrift::protocol::T_STRUCT);
    iprot->readMessageEnd();
    iprot->getTransport()->readEnd();
    ::apache::thrift::TApplicationException x(::apache::thrift::TApplicationException::UNKNOWN_METHOD, "Invalid method name: '"+fname+"'");
    oprot->writeMessageBegin(fname, ::apache::thrift::protocol::T_EXCEPTION, seqid);
    x.write(oprot);
    oprot->writeMessageEnd();
    oprot->getTransport()->writeEnd();
    oprot->getTransport()->flush();
    return true;
  }
  (this->*(pfn->second))(seqid, iprot, oprot, callContext);
  return true;
}

void LocatorServiceProcessor::process_getPreferredServer(int32_t seqid, ::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol* oprot, void* callContext)
{
  void* ctx = nullptr;
  if (this->eventHandler_.get() != nullptr) {
    ctx = this->eventHandler_->getContext("LocatorService.getPreferredServer", callContext);
  }
  ::apache::thrift::TProcessorContextFreer freer(this->eventHandler_.get(), ctx, "LocatorService.getPreferredServer");

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->preRead(ctx, "LocatorService.getPreferredServer");
  }

  LocatorService_getPreferredServer_args args;
  args.read(iprot);
  iprot->readMessageEnd();
  uint32_t bytes = iprot->getTransport()->readEnd();

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->postRead(ctx, "LocatorService.getPreferredServer", bytes);
  }

  LocatorService_getPreferredServer_result result;
  try {
    iface_->getPreferredServer(result.success, args.serverTypes, args.serverGroups, args.failedServers);
    result.__isset.success = true;
  } catch (SnappyException &error) {
    result.error = error;
    result.__isset.error = true;
  } catch (const std::exception& e) {
    if (this->eventHandler_.get() != nullptr) {
      this->eventHandler_->handlerError(ctx, "LocatorService.getPreferredServer");
    }

    ::apache::thrift::TApplicationException x(e.what());
    oprot->writeMessageBegin("getPreferredServer", ::apache::thrift::protocol::T_EXCEPTION, seqid);
    x.write(oprot);
    oprot->writeMessageEnd();
    oprot->getTransport()->writeEnd();
    oprot->getTransport()->flush();
    return;
  }

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->preWrite(ctx, "LocatorService.getPreferredServer");
  }

  oprot->writeMessageBegin("getPreferredServer", ::apache::thrift::protocol::T_REPLY, seqid);
  result.write(oprot);
  oprot->writeMessageEnd();
  bytes = oprot->getTransport()->writeEnd();
  oprot->getTransport()->flush();

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->postWrite(ctx, "LocatorService.getPreferredServer", bytes);
  }
}

void LocatorServiceProcessor::process_getAllServersWithPreferredServer(int32_t seqid, ::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol* oprot, void* callContext)
{
  void* ctx = nullptr;
  if (this->eventHandler_.get() != nullptr) {
    ctx = this->eventHandler_->getContext("LocatorService.getAllServersWithPreferredServer", callContext);
  }
  ::apache::thrift::TProcessorContextFreer freer(this->eventHandler_.get(), ctx, "LocatorService.getAllServersWithPreferredServer");

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->preRead(ctx, "LocatorService.getAllServersWithPreferredServer");
  }

  LocatorService_getAllServersWithPreferredServer_args args;
  args.read(iprot);
  iprot->readMessageEnd();
  uint32_t bytes = iprot->getTransport()->readEnd();

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->postRead(ctx, "LocatorService.getAllServersWithPreferredServer", bytes);
  }

  LocatorService_getAllServersWithPreferredServer_result result;
  try {
    iface_->getAllServersWithPreferredServer(result.success, args.serverTypes, args.serverGroups, args.failedServers);
    result.__isset.success = true;
  } catch (SnappyException &error) {
    result.error = error;
    result.__isset.error = true;
  } catch (const std::exception& e) {
    if (this->eventHandler_.get() != nullptr) {
      this->eventHandler_->handlerError(ctx, "LocatorService.getAllServersWithPreferredServer");
    }

    ::apache::thrift::TApplicationException x(e.what());
    oprot->writeMessageBegin("getAllServersWithPreferredServer", ::apache::thrift::protocol::T_EXCEPTION, seqid);
    x.write(oprot);
    oprot->writeMessageEnd();
    oprot->getTransport()->writeEnd();
    oprot->getTransport()->flush();
    return;
  }

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->preWrite(ctx, "LocatorService.getAllServersWithPreferredServer");
  }

  oprot->writeMessageBegin("getAllServersWithPreferredServer", ::apache::thrift::protocol::T_REPLY, seqid);
  result.write(oprot);
  oprot->writeMessageEnd();
  bytes = oprot->getTransport()->writeEnd();
  oprot->getTransport()->flush();

  if (this->eventHandler_.get() != nullptr) {
    this->eventHandler_->postWrite(ctx, "LocatorService.getAllServersWithPreferredServer", bytes);
  }
}

::std::shared_ptr< ::apache::thrift::TProcessor > LocatorServiceProcessorFactory::getProcessor(const ::apache::thrift::TConnectionInfo& connInfo) {
  ::apache::thrift::ReleaseHandler< LocatorServiceIfFactory > cleanup(handlerFactory_);
  ::std::shared_ptr< LocatorServiceIf > handler(handlerFactory_->getHandler(connInfo), cleanup);
  ::std::shared_ptr< ::apache::thrift::TProcessor > processor(new LocatorServiceProcessor(handler));
  return processor;
}

}}} // namespace

