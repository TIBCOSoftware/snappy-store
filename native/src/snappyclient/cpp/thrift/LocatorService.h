/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#ifndef LocatorService_H
#define LocatorService_H

#include <thrift/TDispatchProcessor.h>
#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <thrift/cxxfunctional.h>
#include "snappydata_types.h"
#include "snappydata_struct_Decimal.h"
#include "snappydata_struct_BlobChunk.h"
#include "snappydata_struct_ClobChunk.h"
#include "snappydata_struct_TransactionXid.h"
#include "snappydata_struct_ServiceMetaData.h"
#include "snappydata_struct_ServiceMetaDataArgs.h"
#include "snappydata_struct_OpenConnectionArgs.h"
#include "snappydata_struct_ConnectionProperties.h"
#include "snappydata_struct_HostAddress.h"
#include "snappydata_struct_SnappyExceptionData.h"
#include "snappydata_struct_StatementAttrs.h"
#include "snappydata_struct_ColumnValue.h"
#include "snappydata_struct_ColumnDescriptor.h"
#include "snappydata_struct_Row.h"
#include "snappydata_struct_OutputParameter.h"
#include "snappydata_struct_RowSet.h"
#include "snappydata_struct_PrepareResult.h"
#include "snappydata_struct_UpdateResult.h"
#include "snappydata_struct_StatementResult.h"
#include "snappydata_struct_BucketOwners.h"
#include "snappydata_struct_CatalogStorage.h"
#include "snappydata_struct_CatalogSchemaObject.h"
#include "snappydata_struct_CatalogStats.h"
#include "snappydata_struct_CatalogTableObject.h"
#include "snappydata_struct_CatalogFunctionObject.h"
#include "snappydata_struct_CatalogPartitionObject.h"
#include "snappydata_struct_CatalogMetadataRequest.h"
#include "snappydata_struct_CatalogMetadataDetails.h"
#include "snappydata_struct_EntityId.h"

#include "snappydata_struct_SnappyException.h"


namespace io { namespace snappydata { namespace thrift {

#ifdef _WIN32
  #pragma warning( push )
  #pragma warning (disable : 4250 ) //inheriting methods via dominance 
#endif

class LocatorServiceIf {
 public:
  virtual ~LocatorServiceIf() {}
  virtual void getPreferredServer(HostAddress& _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers) = 0;
  virtual void getAllServersWithPreferredServer(std::vector<HostAddress> & _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers) = 0;
};

class LocatorServiceIfFactory {
 public:
  typedef LocatorServiceIf Handler;

  virtual ~LocatorServiceIfFactory() {}

  virtual LocatorServiceIf* getHandler(const ::apache::thrift::TConnectionInfo& connInfo) = 0;
  virtual void releaseHandler(LocatorServiceIf* /* handler */) = 0;
};

class LocatorServiceIfSingletonFactory : virtual public LocatorServiceIfFactory {
 public:
  LocatorServiceIfSingletonFactory(const boost::shared_ptr<LocatorServiceIf>& iface) : iface_(iface) {}
  virtual ~LocatorServiceIfSingletonFactory() {}

  virtual LocatorServiceIf* getHandler(const ::apache::thrift::TConnectionInfo&) {
    return iface_.get();
  }
  virtual void releaseHandler(LocatorServiceIf* /* handler */) {}

 protected:
  boost::shared_ptr<LocatorServiceIf> iface_;
};

class LocatorServiceNull : virtual public LocatorServiceIf {
 public:
  virtual ~LocatorServiceNull() {}
  void getPreferredServer(HostAddress& /* _return */, const std::set<ServerType::type> & /* serverTypes */, const std::set<std::string> & /* serverGroups */, const std::set<HostAddress> & /* failedServers */) {
    return;
  }
  void getAllServersWithPreferredServer(std::vector<HostAddress> & /* _return */, const std::set<ServerType::type> & /* serverTypes */, const std::set<std::string> & /* serverGroups */, const std::set<HostAddress> & /* failedServers */) {
    return;
  }
};

typedef struct _LocatorService_getPreferredServer_args__isset {
  _LocatorService_getPreferredServer_args__isset() : serverTypes(false), serverGroups(false), failedServers(false) {}
  bool serverTypes :1;
  bool serverGroups :1;
  bool failedServers :1;
} _LocatorService_getPreferredServer_args__isset;

class LocatorService_getPreferredServer_args {
 public:

  LocatorService_getPreferredServer_args(const LocatorService_getPreferredServer_args&);
  LocatorService_getPreferredServer_args(LocatorService_getPreferredServer_args&&) noexcept;
  LocatorService_getPreferredServer_args& operator=(const LocatorService_getPreferredServer_args&);
  LocatorService_getPreferredServer_args& operator=(LocatorService_getPreferredServer_args&&) noexcept;
  LocatorService_getPreferredServer_args() {
  }

  virtual ~LocatorService_getPreferredServer_args() noexcept;
  std::set<ServerType::type>  serverTypes;
  std::set<std::string>  serverGroups;
  std::set<HostAddress>  failedServers;

  _LocatorService_getPreferredServer_args__isset __isset;

  void __set_serverTypes(const std::set<ServerType::type> & val);

  void __set_serverGroups(const std::set<std::string> & val);

  void __set_failedServers(const std::set<HostAddress> & val);

  bool operator == (const LocatorService_getPreferredServer_args & rhs) const
  {
    if (!(serverTypes == rhs.serverTypes))
      return false;
    if (!(serverGroups == rhs.serverGroups))
      return false;
    if (!(failedServers == rhs.failedServers))
      return false;
    return true;
  }
  bool operator != (const LocatorService_getPreferredServer_args &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const LocatorService_getPreferredServer_args & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

};


class LocatorService_getPreferredServer_pargs {
 public:


  virtual ~LocatorService_getPreferredServer_pargs() noexcept;
  const std::set<ServerType::type> * serverTypes;
  const std::set<std::string> * serverGroups;
  const std::set<HostAddress> * failedServers;

  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

};

typedef struct _LocatorService_getPreferredServer_result__isset {
  _LocatorService_getPreferredServer_result__isset() : success(false), error(false) {}
  bool success :1;
  bool error :1;
} _LocatorService_getPreferredServer_result__isset;

class LocatorService_getPreferredServer_result {
 public:

  LocatorService_getPreferredServer_result(const LocatorService_getPreferredServer_result&);
  LocatorService_getPreferredServer_result(LocatorService_getPreferredServer_result&&) noexcept;
  LocatorService_getPreferredServer_result& operator=(const LocatorService_getPreferredServer_result&);
  LocatorService_getPreferredServer_result& operator=(LocatorService_getPreferredServer_result&&) noexcept;
  LocatorService_getPreferredServer_result() {
  }

  virtual ~LocatorService_getPreferredServer_result() noexcept;
  HostAddress success;
  SnappyException error;

  _LocatorService_getPreferredServer_result__isset __isset;

  void __set_success(const HostAddress& val);

  void __set_error(const SnappyException& val);

  bool operator == (const LocatorService_getPreferredServer_result & rhs) const
  {
    if (!(success == rhs.success))
      return false;
    if (!(error == rhs.error))
      return false;
    return true;
  }
  bool operator != (const LocatorService_getPreferredServer_result &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const LocatorService_getPreferredServer_result & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

};

typedef struct _LocatorService_getPreferredServer_presult__isset {
  _LocatorService_getPreferredServer_presult__isset() : success(false), error(false) {}
  bool success :1;
  bool error :1;
} _LocatorService_getPreferredServer_presult__isset;

class LocatorService_getPreferredServer_presult {
 public:


  virtual ~LocatorService_getPreferredServer_presult() noexcept;
  HostAddress* success;
  SnappyException error;

  _LocatorService_getPreferredServer_presult__isset __isset;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);

};

typedef struct _LocatorService_getAllServersWithPreferredServer_args__isset {
  _LocatorService_getAllServersWithPreferredServer_args__isset() : serverTypes(false), serverGroups(false), failedServers(false) {}
  bool serverTypes :1;
  bool serverGroups :1;
  bool failedServers :1;
} _LocatorService_getAllServersWithPreferredServer_args__isset;

class LocatorService_getAllServersWithPreferredServer_args {
 public:

  LocatorService_getAllServersWithPreferredServer_args(const LocatorService_getAllServersWithPreferredServer_args&);
  LocatorService_getAllServersWithPreferredServer_args(LocatorService_getAllServersWithPreferredServer_args&&) noexcept;
  LocatorService_getAllServersWithPreferredServer_args& operator=(const LocatorService_getAllServersWithPreferredServer_args&);
  LocatorService_getAllServersWithPreferredServer_args& operator=(LocatorService_getAllServersWithPreferredServer_args&&) noexcept;
  LocatorService_getAllServersWithPreferredServer_args() {
  }

  virtual ~LocatorService_getAllServersWithPreferredServer_args() noexcept;
  std::set<ServerType::type>  serverTypes;
  std::set<std::string>  serverGroups;
  std::set<HostAddress>  failedServers;

  _LocatorService_getAllServersWithPreferredServer_args__isset __isset;

  void __set_serverTypes(const std::set<ServerType::type> & val);

  void __set_serverGroups(const std::set<std::string> & val);

  void __set_failedServers(const std::set<HostAddress> & val);

  bool operator == (const LocatorService_getAllServersWithPreferredServer_args & rhs) const
  {
    if (!(serverTypes == rhs.serverTypes))
      return false;
    if (!(serverGroups == rhs.serverGroups))
      return false;
    if (!(failedServers == rhs.failedServers))
      return false;
    return true;
  }
  bool operator != (const LocatorService_getAllServersWithPreferredServer_args &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const LocatorService_getAllServersWithPreferredServer_args & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

};


class LocatorService_getAllServersWithPreferredServer_pargs {
 public:


  virtual ~LocatorService_getAllServersWithPreferredServer_pargs() noexcept;
  const std::set<ServerType::type> * serverTypes;
  const std::set<std::string> * serverGroups;
  const std::set<HostAddress> * failedServers;

  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

};

typedef struct _LocatorService_getAllServersWithPreferredServer_result__isset {
  _LocatorService_getAllServersWithPreferredServer_result__isset() : success(false), error(false) {}
  bool success :1;
  bool error :1;
} _LocatorService_getAllServersWithPreferredServer_result__isset;

class LocatorService_getAllServersWithPreferredServer_result {
 public:

  LocatorService_getAllServersWithPreferredServer_result(const LocatorService_getAllServersWithPreferredServer_result&);
  LocatorService_getAllServersWithPreferredServer_result(LocatorService_getAllServersWithPreferredServer_result&&) noexcept;
  LocatorService_getAllServersWithPreferredServer_result& operator=(const LocatorService_getAllServersWithPreferredServer_result&);
  LocatorService_getAllServersWithPreferredServer_result& operator=(LocatorService_getAllServersWithPreferredServer_result&&) noexcept;
  LocatorService_getAllServersWithPreferredServer_result() {
  }

  virtual ~LocatorService_getAllServersWithPreferredServer_result() noexcept;
  std::vector<HostAddress>  success;
  SnappyException error;

  _LocatorService_getAllServersWithPreferredServer_result__isset __isset;

  void __set_success(const std::vector<HostAddress> & val);

  void __set_error(const SnappyException& val);

  bool operator == (const LocatorService_getAllServersWithPreferredServer_result & rhs) const
  {
    if (!(success == rhs.success))
      return false;
    if (!(error == rhs.error))
      return false;
    return true;
  }
  bool operator != (const LocatorService_getAllServersWithPreferredServer_result &rhs) const {
    return !(*this == rhs);
  }

  bool operator < (const LocatorService_getAllServersWithPreferredServer_result & ) const;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);
  uint32_t write(::apache::thrift::protocol::TProtocol* oprot) const;

};

typedef struct _LocatorService_getAllServersWithPreferredServer_presult__isset {
  _LocatorService_getAllServersWithPreferredServer_presult__isset() : success(false), error(false) {}
  bool success :1;
  bool error :1;
} _LocatorService_getAllServersWithPreferredServer_presult__isset;

class LocatorService_getAllServersWithPreferredServer_presult {
 public:


  virtual ~LocatorService_getAllServersWithPreferredServer_presult() noexcept;
  std::vector<HostAddress> * success;
  SnappyException error;

  _LocatorService_getAllServersWithPreferredServer_presult__isset __isset;

  uint32_t read(::apache::thrift::protocol::TProtocol* iprot);

};

class LocatorServiceClient : virtual public LocatorServiceIf {
 public:
  LocatorServiceClient(boost::shared_ptr< ::apache::thrift::protocol::TProtocol> prot) {
    setProtocol(prot);
  }
  LocatorServiceClient(boost::shared_ptr< ::apache::thrift::protocol::TProtocol> iprot, boost::shared_ptr< ::apache::thrift::protocol::TProtocol> oprot) {
    setProtocol(iprot,oprot);
  }
 private:
  void setProtocol(boost::shared_ptr< ::apache::thrift::protocol::TProtocol> prot) {
  setProtocol(prot,prot);
  }
  void setProtocol(boost::shared_ptr< ::apache::thrift::protocol::TProtocol> iprot, boost::shared_ptr< ::apache::thrift::protocol::TProtocol> oprot) {
    piprot_=iprot;
    poprot_=oprot;
    iprot_ = iprot.get();
    oprot_ = oprot.get();
  }
 public:
  boost::shared_ptr< ::apache::thrift::protocol::TProtocol> getInputProtocol() {
    return piprot_;
  }
  boost::shared_ptr< ::apache::thrift::protocol::TProtocol> getOutputProtocol() {
    return poprot_;
  }
  void getPreferredServer(HostAddress& _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers);
  void send_getPreferredServer(const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers);
  void recv_getPreferredServer(HostAddress& _return);
  void getAllServersWithPreferredServer(std::vector<HostAddress> & _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers);
  void send_getAllServersWithPreferredServer(const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers);
  void recv_getAllServersWithPreferredServer(std::vector<HostAddress> & _return);
 protected:
  boost::shared_ptr< ::apache::thrift::protocol::TProtocol> piprot_;
  boost::shared_ptr< ::apache::thrift::protocol::TProtocol> poprot_;
  ::apache::thrift::protocol::TProtocol* iprot_;
  ::apache::thrift::protocol::TProtocol* oprot_;
};

class LocatorServiceProcessor : public ::apache::thrift::TDispatchProcessor {
 protected:
  boost::shared_ptr<LocatorServiceIf> iface_;
  virtual bool dispatchCall(::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol* oprot, const std::string& fname, int32_t seqid, void* callContext);
 private:
  typedef  void (LocatorServiceProcessor::*ProcessFunction)(int32_t, ::apache::thrift::protocol::TProtocol*, ::apache::thrift::protocol::TProtocol*, void*);
  typedef std::map<std::string, ProcessFunction> ProcessMap;
  ProcessMap processMap_;
  void process_getPreferredServer(int32_t seqid, ::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol* oprot, void* callContext);
  void process_getAllServersWithPreferredServer(int32_t seqid, ::apache::thrift::protocol::TProtocol* iprot, ::apache::thrift::protocol::TProtocol* oprot, void* callContext);
 public:
  LocatorServiceProcessor(boost::shared_ptr<LocatorServiceIf> iface) :
    iface_(iface) {
    processMap_["getPreferredServer"] = &LocatorServiceProcessor::process_getPreferredServer;
    processMap_["getAllServersWithPreferredServer"] = &LocatorServiceProcessor::process_getAllServersWithPreferredServer;
  }

  virtual ~LocatorServiceProcessor() {}
};

class LocatorServiceProcessorFactory : public ::apache::thrift::TProcessorFactory {
 public:
  LocatorServiceProcessorFactory(const ::boost::shared_ptr< LocatorServiceIfFactory >& handlerFactory) :
      handlerFactory_(handlerFactory) {}

  ::boost::shared_ptr< ::apache::thrift::TProcessor > getProcessor(const ::apache::thrift::TConnectionInfo& connInfo);

 protected:
  ::boost::shared_ptr< LocatorServiceIfFactory > handlerFactory_;
};

class LocatorServiceMultiface : virtual public LocatorServiceIf {
 public:
  LocatorServiceMultiface(std::vector<boost::shared_ptr<LocatorServiceIf> >& ifaces) : ifaces_(ifaces) {
  }
  virtual ~LocatorServiceMultiface() {}
 protected:
  std::vector<boost::shared_ptr<LocatorServiceIf> > ifaces_;
  LocatorServiceMultiface() {}
  void add(boost::shared_ptr<LocatorServiceIf> iface) {
    ifaces_.push_back(iface);
  }
 public:
  void getPreferredServer(HostAddress& _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers) {
    size_t sz = ifaces_.size();
    size_t i = 0;
    for (; i < (sz - 1); ++i) {
      ifaces_[i]->getPreferredServer(_return, serverTypes, serverGroups, failedServers);
    }
    ifaces_[i]->getPreferredServer(_return, serverTypes, serverGroups, failedServers);
    return;
  }

  void getAllServersWithPreferredServer(std::vector<HostAddress> & _return, const std::set<ServerType::type> & serverTypes, const std::set<std::string> & serverGroups, const std::set<HostAddress> & failedServers) {
    size_t sz = ifaces_.size();
    size_t i = 0;
    for (; i < (sz - 1); ++i) {
      ifaces_[i]->getAllServersWithPreferredServer(_return, serverTypes, serverGroups, failedServers);
    }
    ifaces_[i]->getAllServersWithPreferredServer(_return, serverTypes, serverGroups, failedServers);
    return;
  }

};

#ifdef _WIN32
  #pragma warning( pop )
#endif

}}} // namespace

#endif
