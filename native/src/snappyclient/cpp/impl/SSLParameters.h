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

#ifndef SSLPARAMETERS_H_
#define SSLPARAMETERS_H_

#include "ClientBase.h"
#include "thrift/transport/TSSLSocket.h"

using namespace apache::thrift::transport;

namespace io {
namespace snappydata {
namespace client {
namespace impl {

enum class SSLProperty {
  PROTOCOL,
  CIPHERSUITES,
  CLIENTAUTH,
  KEYSTORE,
  KEYSTOREPASSWORD,
  CERTIFICATE,
  CERTIFICATEPASSWORD,
  TRUSTSTORE,
  TRUSTSTOREPASSWORD
};

class SSLSocketFactory;

class SSLParameters {
private:
  static const std::set<std::string> s_sslProperties;
  std::map<std::string, std::string> m_sslPropValMap;
  SSLProperty m_currentProperty;

  friend class SSLSocketFactory;

public:
  SSLParameters() :
      m_sslPropValMap(), m_currentProperty(SSLProperty::PROTOCOL) {
  }

  void setSSLProperty(std::string &propertyName, std::string& value);
  std::string getSSLPropertyName(SSLProperty sslProperty);
  std::string getSSLPropertyValue(const std::string &propertyName) const;
  void operator()(const std::string& str);
};

class SSLSocketFactory : public TSSLSocketFactory {
public:
  /**
   * Constructor
   *
   * @param params SSL parameters to use.
   */
  SSLSocketFactory(SSLParameters& params);
  virtual ~SSLSocketFactory() {
  }

protected:
  virtual void getPassword(std::string& password, int size);

private:
  SSLParameters& m_params;

  static SSLProtocol getProtocol(const SSLParameters& params);
};

} /* namespace impl */
} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* SSLPARAMETERS_H_ */
