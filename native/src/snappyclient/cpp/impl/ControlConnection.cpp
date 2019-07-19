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
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

//--------Headers----------
#include <chrono>
#include <boost/assign/list_of.hpp>
#include <boost/assign.hpp>
#include <boost/thread.hpp>
#include <thrift/Thrift.h>
#include <thrift/transport/TSSLSocket.h>
#include <thrift/transport/TSocket.h>
#include <thrift/transport/TBufferTransports.h>
#include <thrift/protocol/TBinaryProtocol.h>
#include <thrift/protocol/TCompactProtocol.h>

#include <SQLState.h>
#include <Utils.h>
#include <boost/range/algorithm.hpp>
#include <boost/make_shared.hpp>
#include "ControlConnection.h"


using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;
using namespace io::snappydata::thrift;

//----private static data member initialiazation----
std::vector<std::unique_ptr<ControlConnection> > ControlConnection::s_allConnections;
boost::mutex ControlConnection::s_allConnsLock;

ControlConnection::ControlConnection(ClientService *const &service) :m_serverGroups(service->getServerGrps()){
  m_locators= service->getLocators();
  m_framedTransport = service->isFrameTransport();
  m_snappyServerType=service->getServerType(true,false,false);
  m_controlHost= service->getCurrentHostAddress();
  boost::assign::insert(m_snappyServerTypeSet)(service->getServerType(true,false,false));
  std::copy(m_locators.begin(),m_locators.end(),std::inserter(m_controlHostSet,m_controlHostSet.end()));
  m_controlLocator = nullptr;
}

const boost::optional<ControlConnection&> ControlConnection::getOrCreateControlConnection(
    const std::vector<thrift::HostAddress>& hostAddrs, ClientService *const &service, std::exception* failure){

  // loop through all ControlConnections since size of this global list is
  // expected to be in single digit (total number of distributed systems)

  boost::lock_guard<boost::mutex> globalGuard(s_allConnsLock);

  signed short index = static_cast<signed short>(s_allConnections.size());
  while (--index >= 0) {
    const std::unique_ptr<ControlConnection>& controlConn = s_allConnections.at(index);

    boost::lock_guard<boost::mutex> serviceGuard(controlConn->m_lock);
    std::vector<thrift::HostAddress> _locators = controlConn->m_locators;
    for(thrift::HostAddress hostAddr : hostAddrs){
      auto result = std::find(_locators.begin(),_locators.end(),hostAddr);
      if(result == _locators.end()){
        continue;
      }
      auto serviceServerType = service->getServerType(true,false,false); // TODO: need to discuss with sumedh about this getServerType method
      auto contrConnServerType = controlConn->m_snappyServerType;
      if(contrConnServerType == serviceServerType){
        return *controlConn;
      }else{
        thrift::SnappyException *ex = new thrift::SnappyException();
        std::string portStr;
        Utils::convertIntToString(hostAddr.port,portStr);
        std::string msg= hostAddr.hostName + ":" + portStr +
            " as registered but having different type " + Utils::getServerTypeString(contrConnServerType) +
            " than connection " + Utils::getServerTypeString( serviceServerType) ;
        SnappyExceptionData snappyExData;
        //  snappyExData.__set_sqlState("08006.C");// TODO: discuss with sumedh about correct SQLState
        snappyExData.__set_reason(msg);

        ex->__set_exceptionData(snappyExData);
        ex->__set_serverInfo(hostAddr.hostName + ":" + portStr );
        throw ex;
      }
    }
  }
  // if we reached here, then need to create a new ControlConnection
  std::unique_ptr<ControlConnection> controlService ( new ControlConnection(service));
  thrift::HostAddress preferredServer;
  controlService->getPreferredServer(preferredServer,failure, true);
  // check again if new control host already exist
  index =  static_cast<signed short>(s_allConnections.size());
  while (--index >= 0) {
    const std::unique_ptr<ControlConnection>& controlConn = s_allConnections.at(index);
    boost::lock_guard<boost::mutex> serviceGuard(controlConn->m_lock);
    std::vector<thrift::HostAddress> _locators = controlConn->m_locators;
    auto result = std::find(_locators.begin(),_locators.end(),preferredServer);
    if(result == _locators.end()){
      return *controlConn;
    }
  }
  s_allConnections.push_back(std::move(controlService));
  return *s_allConnections.back();
}
void ControlConnection::getLocatorPreferredServer(thrift::HostAddress& prefHostAddr,std::set<thrift::HostAddress>& failedServers,
    std::set<std::string>serverGroups){
  // TODO: SanityManager
  m_controlLocator->getPreferredServer(prefHostAddr,m_snappyServerTypeSet,serverGroups,failedServers);
  //TODO:SanityManager
}
void ControlConnection::getPreferredServer(thrift::HostAddress& preferredServer,std::exception* failure,bool forFailover){
  std::set<thrift::HostAddress> failedServers;
  std::set<std::string> serverGroups;
  return getPreferredServer(preferredServer,failure,failedServers,serverGroups,forFailover);
}
void ControlConnection::getPreferredServer(thrift::HostAddress& preferredServer,std::exception* failure,
    std::set<thrift::HostAddress>& failedServers,
    std::set<std::string>& serverGroups,bool forFailover){
  //boost::lock_guard<boost::mutex> localGuard(m_lock);
  if(m_controlLocator == nullptr)
  {
    failoverToAvailableHost(failedServers, false,failure);
    forFailover = true;
  }
  boost::lock_guard<boost::mutex> localGuard(m_lock);
  bool firstCall = true;
  while(true){

    try{
      if(forFailover){
        //TODO: SanityManager
        //refresh the full host list
        std::vector<HostAddress> prefServerAndAllHosts;
        m_controlLocator->getAllServersWithPreferredServer(prefServerAndAllHosts,m_snappyServerTypeSet,serverGroups,failedServers);
        //TODO :: refresh new server list--like java do.
        if(! prefServerAndAllHosts.empty())
        {
        std::vector<HostAddress> allHosts(prefServerAndAllHosts.begin() +1,prefServerAndAllHosts.end());
        refreshAllHosts(allHosts);
        preferredServer = prefServerAndAllHosts.at(0);
        }
        //TODO :SanityManger
      }else{
        getLocatorPreferredServer(preferredServer,failedServers,serverGroups);
      }
      // TODO: SanityManager
      if(preferredServer.port <=0){
        /*For this case we don't have a locator or locator unable to
         * determine a preferred server, so choose some server randomly
         * as the "preferredServer". In case all servers have failed then
         * the search below will also fail.
         * Remove controlHost from failedServers since it is known to be
         * working at this point (e.g after a reconnect)
         * */
        std::set<thrift::HostAddress> skipServers = failedServers;
        if( !failedServers.empty() && std::find(failedServers.begin(),failedServers.end(),m_controlHost)!= failedServers.end()){
          //don't change the original failure list since that is proper
          // for the current operation but change for random server search
          skipServers.erase(m_controlHost);
        }
        searchRandomServer(skipServers, failure,preferredServer);
      }
      //TODO: Sanitymanger
      return;
    }catch(thrift::SnappyException &snEx){
      // TODO:
      //Discuss with Sumedh
      throw unexpectedError(snEx, m_controlHost);
    }catch(TException &tex){
      // TODO: SanityManager
      //Search for a new host for locator query
      // for the first call do not mark controlhost as failed but retry(e.g. for a reconnect case)
      if(firstCall){
        firstCall = false;
      }else{
        failedServers.insert(m_controlHost);
      }
      m_controlLocator->getOutputProtocol()->getTransport()->close();
      failoverToAvailableHost(failedServers,true,failure);
      if(failure ==nullptr){
        failure = &(tex);// TODO: need to look again
      }
    }catch(std::exception &ex){
      throw unexpectedError(ex, m_controlHost);
    }
    forFailover = true;
  }
}

void ControlConnection::searchRandomServer(const std::set<thrift::HostAddress>& skipServers,std::exception* failure,
    thrift::HostAddress& hostAddress){

  //TODO: Need to discuss implemetation of this method and also ClientService:: updateFailedServersForCurrent with sumedh
  std::vector<thrift::HostAddress> searchServers;
  // Note: Do not use unordered_set -- reason is http://www.cplusplus.com/forum/general/198319/
  std::copy(m_controlHostSet.begin(),m_controlHostSet.end(),std::inserter(searchServers,searchServers.end()));
  if(searchServers.size() > 2){
    std::random_shuffle(searchServers.begin(),searchServers.end());
  }
  bool findIt = false;
  for(thrift::HostAddress host: searchServers){
    if(host.serverType == m_snappyServerType &&
        !(!skipServers.empty() &&
            std::find(skipServers.begin(),skipServers.end(),host)!=skipServers.end())){
      hostAddress = host;
      findIt = true;
      break;
    }
  }
  if(findIt) return;
  throw failoverExhausted(skipServers,failure);
}
void ControlConnection::failoverToAvailableHost(std::set<thrift::HostAddress>& failedServers,bool checkFailedControlHosts,
    std::exception* failure){
  boost::lock_guard<boost::mutex> localGuard(m_lock);
  for(auto iterator = m_controlHostSet.begin();iterator!= m_controlHostSet.end(); ++iterator ){
  // NEXT: for(thrift::HostAddress controlAddr : m_controlHostSet){
    thrift::HostAddress controlAddr = *iterator;
    if(checkFailedControlHosts && ! failedServers.empty() && (failedServers.find(controlAddr) != failedServers.end())){
      continue;
    }
    m_controlLocator.reset(nullptr);

    boost::shared_ptr<TTransport> inTransport =nullptr;
    boost::shared_ptr<TTransport> outTransport =nullptr;
    boost::shared_ptr<TProtocol>  inProtocol =nullptr;
    boost::shared_ptr<TProtocol>  outProtocol =nullptr;

    try{
      while(true){
        if(outTransport !=nullptr){
          outTransport->close();
        }
        boost::shared_ptr<TTransport> tTransport = nullptr;
        if(m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP_SSL ||
            m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_CP_SSL ||
            m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP_SSL ||
            m_snappyServerType==thrift::ServerType::THRIFT_SNAPPY_CP_SSL){
          // TODO: Find out whether SnappyTSSLSocket is needed or not, or any other thing is required
          TSSLSocketFactory sslSocketFactory;
          tTransport = sslSocketFactory.createSocket(controlAddr.hostName,controlAddr.port);
        }else if(m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP ||
            m_snappyServerType== thrift::ServerType::THRIFT_LOCATOR_CP ||
            m_snappyServerType== thrift::ServerType::THRIFT_SNAPPY_BP ||
            m_snappyServerType== thrift::ServerType::THRIFT_SNAPPY_CP){
          tTransport = boost::make_shared<TSocket>(controlAddr.hostName,controlAddr.port); // TODO: Find out whether SnappyTSocket is needed or not, or any other thing is required
        }
        tTransport->open();
        TTransportFactory* transportFactory = nullptr;
        if(m_framedTransport){
          transportFactory = new TFramedTransportFactory();
        }else{
          transportFactory = new TTransportFactory();
        }
        inTransport = transportFactory->getTransport(tTransport);
        outTransport = transportFactory->getTransport(tTransport);
        delete transportFactory;
        transportFactory= 0;

        TProtocolFactory* protocolFactory = nullptr;
        if(m_snappyServerType == thrift::ServerType::THRIFT_LOCATOR_BP ||
            m_snappyServerType==thrift::ServerType::THRIFT_LOCATOR_BP_SSL ||
            m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP ||
            m_snappyServerType == thrift::ServerType::THRIFT_SNAPPY_BP_SSL){
          protocolFactory = new TBinaryProtocolFactory();

        }else{
          protocolFactory = new TCompactProtocolFactory();
        }
        inProtocol=  protocolFactory->getProtocol(inTransport);
        outProtocol= protocolFactory->getProtocol(outTransport);

        delete protocolFactory;
        protocolFactory=0;
        break;
      }
    }catch(TException &tExp){
      failure = &tExp;  // TODO: need to look again
      failedServers.insert(controlAddr);
      if(outTransport != nullptr){
        outTransport->close();
      }
      continue;
      //goto NEXT;
    }catch(std::exception &ex){
      throw unexpectedError(ex,controlAddr);
    }

    m_controlHost = controlAddr;

    m_controlLocator.reset (new thrift::LocatorServiceClient(inProtocol,outProtocol));
    return;
  }
  throw failoverExhausted(failedServers,failure);
}

const thrift::SnappyException* ControlConnection:: unexpectedError(const std::exception& ex, const thrift::HostAddress& host){

  if(m_controlLocator != nullptr){
    m_controlLocator->getOutputProtocol()->getTransport()->close();
    m_controlLocator.reset(nullptr);
  }
  thrift::SnappyException *snappyEx = new thrift::SnappyException();
  SnappyExceptionData snappyExData;
  //snappyExData.__set_sqlState(std::string(SQLState::UNKNOWN_EXCEPTION));// TODO: discuss with sumedh about correct SQLState
  snappyExData.__set_reason(ex.what());
  snappyEx->__set_exceptionData(snappyExData);

  std::string portNum;
  Utils::convertIntToString(host.port,portNum);
  snappyEx->__set_serverInfo(host.hostName + host.ipAddress + portNum + Utils::getServerTypeString(host.serverType));

  return snappyEx;
}

void  ControlConnection::refreshAllHosts(const std::vector<thrift::HostAddress>& allHosts) {
  //refresh the locator list first(keep old but push current to front)
  std::vector<thrift::HostAddress> locators = m_locators;
  std::vector<thrift::HostAddress> newLocators(locators.size());

  for(HostAddress host: allHosts){
    thrift::ServerType::type sType = host.serverType;
    if(sType == ServerType::THRIFT_LOCATOR_BP || sType == ServerType::THRIFT_LOCATOR_BP_SSL ||
        sType == ServerType::THRIFT_LOCATOR_CP || sType == ServerType::THRIFT_LOCATOR_CP_SSL ||
        (std::find(locators.begin(),locators.end(), host)!=locators.end())){
      newLocators.push_back(host);
    }
  }
  for(HostAddress host: locators){
    if(!(std::find(newLocators.begin(),newLocators.end(), host)!=newLocators.end())){
      newLocators.push_back(host);
    }
  }
  m_locators = newLocators;
  // refresh the new server list

  // we remove all from the set and re-populate since we would like
  // to prefer the ones coming as "allServers" with "isServer" flag
  // correctly set rather than the ones in "secondary-locators"
  m_controlHostSet.clear();
  m_controlHostSet.insert(newLocators.begin(),newLocators.end());
  m_controlHostSet.insert(allHosts.begin(),allHosts.end());
}


thrift::SnappyException* ControlConnection::failoverExhausted(const std::set<thrift::HostAddress>& failedServers,
    std::exception* failure) {

  std::string failedServerString;
  for(thrift::HostAddress host : failedServers){
    std::string portStr;
    Utils::convertIntToString(host.port,portStr);
    failedServerString.append(host.hostName).append(":").append(portStr).append(",");
  }
  thrift::SnappyException *snappyEx = new thrift::SnappyException();
  SnappyExceptionData snappyExData;
  //snappyExData.__set_sqlState(std::string(SQLState::DATA_CONTAINER_CLOSED));// TODO: discuss with sumedh about correct SQLState
  std::string reason ="{Failed afer trying all the servers:}" ;
  snappyExData.__set_reason(reason);
  snappyEx->__set_exceptionData(snappyExData);
  //snappyExData.__set_sqlState();//
  // TODO:  complete this funtion
  snappyEx->__set_serverInfo(failedServerString);
  return snappyEx;
}


