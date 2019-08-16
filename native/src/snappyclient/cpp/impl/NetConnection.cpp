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
/*
 * NetConnection.cpp
 *
 *  Created on: 15-Jul-2019
 *      Author: pbisen
 */
#include "SQLState.h"
#include "SQLException.h"
#include "NetConnection.h"


using namespace io::snappydata;
using namespace io::snappydata::client;
using namespace io::snappydata::client::impl;

std::set<std::string> NetConnection::failoverSQLStateSet={ "08001",
 "08003", "08004", "08006", "X0J15", "X0Z32", "XN001", "XN014", "XN016",
 "58009", "58014", "58015", "58016", "58017", "57017", "58010", "30021",
 "XJ040", "XJ041", "XSDA3", "XSDA4", "XSDAJ", "XJ217" };

FailoverStatus NetConnection::getFailoverStatus(const std::string& sqlState,
    const int32_t& errorCode, const TException& snappyEx){

  if( ! sqlState.compare(SQLState::SNAPPY_NODE_SHUTDOWN.getSQLState())
      || !sqlState.compare(SQLState::NODE_BUCKET_MOVED.getSQLState())){
    return FailoverStatus::RETRY;
  }
  /* for 08001 we have to, unfortunately, resort to string search to
  * determine if failover makes sense or it is due to some problem
  * with authentication or invalid properties */
  else if(!sqlState.compare("08001")){
    std::string msg(snappyEx.what());
    if(!msg.empty() &&
        ((msg.find("rror")!=std::string::npos)  // cater to CONNECT_UNABLE_TO_CONNECT_TO_SERVER
            || (msg.find("xception")!=std::string::npos ) // cater to CONNECT_SOCKET_EXCEPTION
            ||(msg.find("ocket")!=std::string::npos))// cater to CONNECT_UNABLE_TO_OPEN_SOCKET_STREAM
      ){
      return FailoverStatus::NEW_SERVER;
    }
  }
  /* for 08004 we have to, unfortunately, resort to string search to
   *  determine if failover makes sense or it is due to some problem
   *  with authentication
   */
  else if(!sqlState.compare("08004")){
      std::string msg(snappyEx.what());
      if(!msg.empty() &&
         (msg.find("connection refused") !=std::string::npos)
         ){
        return FailoverStatus::NEW_SERVER;
      }
    }
  else if(failoverSQLStateSet.find(sqlState)!= failoverSQLStateSet.end()){
    return FailoverStatus::NEW_SERVER;
  }
  return FailoverStatus::NONE;
}


