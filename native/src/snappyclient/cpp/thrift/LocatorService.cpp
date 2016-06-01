/**
 * Autogenerated by Thrift Compiler (1.0.0-dev)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include "LocatorService.h"

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
            uint32_t _size333;
            ::apache::thrift::protocol::TType _etype336;
            xfer += iprot->readSetBegin(_etype336, _size333);
            uint32_t _i337;
            for (_i337 = 0; _i337 < _size333; ++_i337)
            {
              ServerType::type _elem338;
              int32_t ecast339;
              xfer += iprot->readI32(ecast339);
              _elem338 = (ServerType::type)ecast339;
              this->serverTypes.insert(_elem338);
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
            uint32_t _size340;
            ::apache::thrift::protocol::TType _etype343;
            xfer += iprot->readSetBegin(_etype343, _size340);
            uint32_t _i344;
            for (_i344 = 0; _i344 < _size340; ++_i344)
            {
              std::string _elem345;
              xfer += iprot->readString(_elem345);
              this->serverGroups.insert(_elem345);
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
            uint32_t _size346;
            ::apache::thrift::protocol::TType _etype349;
            xfer += iprot->readSetBegin(_etype349, _size346);
            uint32_t _i350;
            for (_i350 = 0; _i350 < _size346; ++_i350)
            {
              HostAddress _elem351;
              xfer += _elem351.read(iprot);
              this->failedServers.insert(_elem351);
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
    std::set<ServerType::type> ::const_iterator _iter352;
    for (_iter352 = this->serverTypes.begin(); _iter352 != this->serverTypes.end(); ++_iter352)
    {
      xfer += oprot->writeI32((int32_t)(*_iter352));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>(this->serverGroups.size()));
    std::set<std::string> ::const_iterator _iter353;
    for (_iter353 = this->serverGroups.begin(); _iter353 != this->serverGroups.end(); ++_iter353)
    {
      xfer += oprot->writeString((*_iter353));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->failedServers.size()));
    std::set<HostAddress> ::const_iterator _iter354;
    for (_iter354 = this->failedServers.begin(); _iter354 != this->failedServers.end(); ++_iter354)
    {
      xfer += (*_iter354).write(oprot);
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
    std::set<ServerType::type> ::const_iterator _iter355;
    for (_iter355 = (*(this->serverTypes)).begin(); _iter355 != (*(this->serverTypes)).end(); ++_iter355)
    {
      xfer += oprot->writeI32((int32_t)(*_iter355));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>((*(this->serverGroups)).size()));
    std::set<std::string> ::const_iterator _iter356;
    for (_iter356 = (*(this->serverGroups)).begin(); _iter356 != (*(this->serverGroups)).end(); ++_iter356)
    {
      xfer += oprot->writeString((*_iter356));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>((*(this->failedServers)).size()));
    std::set<HostAddress> ::const_iterator _iter357;
    for (_iter357 = (*(this->failedServers)).begin(); _iter357 != (*(this->failedServers)).end(); ++_iter357)
    {
      xfer += (*_iter357).write(oprot);
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
            uint32_t _size358;
            ::apache::thrift::protocol::TType _etype361;
            xfer += iprot->readSetBegin(_etype361, _size358);
            uint32_t _i362;
            for (_i362 = 0; _i362 < _size358; ++_i362)
            {
              ServerType::type _elem363;
              int32_t ecast364;
              xfer += iprot->readI32(ecast364);
              _elem363 = (ServerType::type)ecast364;
              this->serverTypes.insert(_elem363);
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
            uint32_t _size365;
            ::apache::thrift::protocol::TType _etype368;
            xfer += iprot->readSetBegin(_etype368, _size365);
            uint32_t _i369;
            for (_i369 = 0; _i369 < _size365; ++_i369)
            {
              std::string _elem370;
              xfer += iprot->readString(_elem370);
              this->serverGroups.insert(_elem370);
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
            uint32_t _size371;
            ::apache::thrift::protocol::TType _etype374;
            xfer += iprot->readSetBegin(_etype374, _size371);
            uint32_t _i375;
            for (_i375 = 0; _i375 < _size371; ++_i375)
            {
              HostAddress _elem376;
              xfer += _elem376.read(iprot);
              this->failedServers.insert(_elem376);
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
    std::set<ServerType::type> ::const_iterator _iter377;
    for (_iter377 = this->serverTypes.begin(); _iter377 != this->serverTypes.end(); ++_iter377)
    {
      xfer += oprot->writeI32((int32_t)(*_iter377));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>(this->serverGroups.size()));
    std::set<std::string> ::const_iterator _iter378;
    for (_iter378 = this->serverGroups.begin(); _iter378 != this->serverGroups.end(); ++_iter378)
    {
      xfer += oprot->writeString((*_iter378));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>(this->failedServers.size()));
    std::set<HostAddress> ::const_iterator _iter379;
    for (_iter379 = this->failedServers.begin(); _iter379 != this->failedServers.end(); ++_iter379)
    {
      xfer += (*_iter379).write(oprot);
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
    std::set<ServerType::type> ::const_iterator _iter380;
    for (_iter380 = (*(this->serverTypes)).begin(); _iter380 != (*(this->serverTypes)).end(); ++_iter380)
    {
      xfer += oprot->writeI32((int32_t)(*_iter380));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("serverGroups", ::apache::thrift::protocol::T_SET, 2);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRING, static_cast<uint32_t>((*(this->serverGroups)).size()));
    std::set<std::string> ::const_iterator _iter381;
    for (_iter381 = (*(this->serverGroups)).begin(); _iter381 != (*(this->serverGroups)).end(); ++_iter381)
    {
      xfer += oprot->writeString((*_iter381));
    }
    xfer += oprot->writeSetEnd();
  }
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("failedServers", ::apache::thrift::protocol::T_SET, 3);
  {
    xfer += oprot->writeSetBegin(::apache::thrift::protocol::T_STRUCT, static_cast<uint32_t>((*(this->failedServers)).size()));
    std::set<HostAddress> ::const_iterator _iter382;
    for (_iter382 = (*(this->failedServers)).begin(); _iter382 != (*(this->failedServers)).end(); ++_iter382)
    {
      xfer += (*_iter382).write(oprot);
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
            uint32_t _size383;
            ::apache::thrift::protocol::TType _etype386;
            xfer += iprot->readListBegin(_etype386, _size383);
            this->success.resize(_size383);
            uint32_t _i387;
            for (_i387 = 0; _i387 < _size383; ++_i387)
            {
              xfer += this->success[_i387].read(iprot);
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
      std::vector<HostAddress> ::const_iterator _iter388;
      for (_iter388 = this->success.begin(); _iter388 != this->success.end(); ++_iter388)
      {
        xfer += (*_iter388).write(oprot);
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
            uint32_t _size389;
            ::apache::thrift::protocol::TType _etype392;
            xfer += iprot->readListBegin(_etype392, _size389);
            (*(this->success)).resize(_size389);
            uint32_t _i393;
            for (_i393 = 0; _i393 < _size389; ++_i393)
            {
              xfer += (*(this->success))[_i393].read(iprot);
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


LocatorService_closeConnection_args::~LocatorService_closeConnection_args() noexcept {
}


uint32_t LocatorService_closeConnection_args::read(::apache::thrift::protocol::TProtocol* iprot) {

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
    xfer += iprot->skip(ftype);
    xfer += iprot->readFieldEnd();
  }

  xfer += iprot->readStructEnd();

  return xfer;
}

uint32_t LocatorService_closeConnection_args::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("LocatorService_closeConnection_args");

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}


LocatorService_closeConnection_pargs::~LocatorService_closeConnection_pargs() noexcept {
}


uint32_t LocatorService_closeConnection_pargs::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("LocatorService_closeConnection_pargs");

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
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

void LocatorServiceClient::closeConnection()
{
  send_closeConnection();
}

void LocatorServiceClient::send_closeConnection()
{
  int32_t cseqid = 0;
  oprot_->writeMessageBegin("closeConnection", ::apache::thrift::protocol::T_ONEWAY, cseqid);

  LocatorService_closeConnection_pargs args;
  args.write(oprot_);

  oprot_->writeMessageEnd();
  oprot_->getTransport()->writeEnd();
  oprot_->getTransport()->flush();
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
  void* ctx = NULL;
  if (this->eventHandler_.get() != NULL) {
    ctx = this->eventHandler_->getContext("LocatorService.getPreferredServer", callContext);
  }
  ::apache::thrift::TProcessorContextFreer freer(this->eventHandler_.get(), ctx, "LocatorService.getPreferredServer");

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->preRead(ctx, "LocatorService.getPreferredServer");
  }

  LocatorService_getPreferredServer_args args;
  args.read(iprot);
  iprot->readMessageEnd();
  uint32_t bytes = iprot->getTransport()->readEnd();

  if (this->eventHandler_.get() != NULL) {
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
    if (this->eventHandler_.get() != NULL) {
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

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->preWrite(ctx, "LocatorService.getPreferredServer");
  }

  oprot->writeMessageBegin("getPreferredServer", ::apache::thrift::protocol::T_REPLY, seqid);
  result.write(oprot);
  oprot->writeMessageEnd();
  bytes = oprot->getTransport()->writeEnd();
  oprot->getTransport()->flush();

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->postWrite(ctx, "LocatorService.getPreferredServer", bytes);
  }
}

void LocatorServiceProcessor::process_getAllServersWithPreferredServer(int32_t seqid, ::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol* oprot, void* callContext)
{
  void* ctx = NULL;
  if (this->eventHandler_.get() != NULL) {
    ctx = this->eventHandler_->getContext("LocatorService.getAllServersWithPreferredServer", callContext);
  }
  ::apache::thrift::TProcessorContextFreer freer(this->eventHandler_.get(), ctx, "LocatorService.getAllServersWithPreferredServer");

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->preRead(ctx, "LocatorService.getAllServersWithPreferredServer");
  }

  LocatorService_getAllServersWithPreferredServer_args args;
  args.read(iprot);
  iprot->readMessageEnd();
  uint32_t bytes = iprot->getTransport()->readEnd();

  if (this->eventHandler_.get() != NULL) {
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
    if (this->eventHandler_.get() != NULL) {
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

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->preWrite(ctx, "LocatorService.getAllServersWithPreferredServer");
  }

  oprot->writeMessageBegin("getAllServersWithPreferredServer", ::apache::thrift::protocol::T_REPLY, seqid);
  result.write(oprot);
  oprot->writeMessageEnd();
  bytes = oprot->getTransport()->writeEnd();
  oprot->getTransport()->flush();

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->postWrite(ctx, "LocatorService.getAllServersWithPreferredServer", bytes);
  }
}

void LocatorServiceProcessor::process_closeConnection(int32_t, ::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol*, void* callContext)
{
  void* ctx = NULL;
  if (this->eventHandler_.get() != NULL) {
    ctx = this->eventHandler_->getContext("LocatorService.closeConnection", callContext);
  }
  ::apache::thrift::TProcessorContextFreer freer(this->eventHandler_.get(), ctx, "LocatorService.closeConnection");

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->preRead(ctx, "LocatorService.closeConnection");
  }

  LocatorService_closeConnection_args args;
  args.read(iprot);
  iprot->readMessageEnd();
  uint32_t bytes = iprot->getTransport()->readEnd();

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->postRead(ctx, "LocatorService.closeConnection", bytes);
  }

  try {
    iface_->closeConnection();
  } catch (const std::exception&) {
    if (this->eventHandler_.get() != NULL) {
      this->eventHandler_->handlerError(ctx, "LocatorService.closeConnection");
    }
    return;
  }

  if (this->eventHandler_.get() != NULL) {
    this->eventHandler_->asyncComplete(ctx, "LocatorService.closeConnection");
  }

  return;
}

::boost::shared_ptr< ::apache::thrift::TProcessor > LocatorServiceProcessorFactory::getProcessor(const ::apache::thrift::TConnectionInfo& connInfo) {
  ::apache::thrift::ReleaseHandler< LocatorServiceIfFactory > cleanup(handlerFactory_);
  ::boost::shared_ptr< LocatorServiceIf > handler(handlerFactory_->getHandler(connInfo), cleanup);
  ::boost::shared_ptr< ::apache::thrift::TProcessor > processor(new LocatorServiceProcessor(handler));
  return processor;
}

}}} // namespace

