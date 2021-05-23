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

#include "impl/pch.h"

using namespace io::snappydata;
using namespace io::snappydata::client::impl;
using namespace io::snappydata::thrift;

const std::set<std::string> SSLParameters::s_sslProperties {
    "protocol", "cipher-suites", "client-auth", "enabled-protocols", "keystore",
    "keystore-password", "certificate", "certificate-password", "truststore",
    "truststore-password" };

void SSLParameters::setSSLProperty(const std::string &propertyName,
    const std::string &value) {
  auto itr = s_sslProperties.find(propertyName);
  if (itr != s_sslProperties.end()) {
    m_sslPropValMap.emplace(propertyName, value);
  } else {
    throw std::invalid_argument("Unknown SSL Property: " + propertyName);
  }
}

std::string SSLParameters::toSSLPropertyName(SSLProperty sslProperty) {
  switch (sslProperty) {
    case SSLProperty::PROTOCOL:
      return "protocol";
    case SSLProperty::CIPHERSUITES:
      return "cipher-suites";
    case SSLProperty::CLIENTAUTH:
      return "client-auth";
    case SSLProperty::KEYSTORE:
      return "keystore";
    case SSLProperty::KEYSTOREPASSWORD:
      return "keystore-password";
    case SSLProperty::CERTIFICATE:
      return "certificate";
    case SSLProperty::CERTIFICATEPASSWORD:
      return "certificate-password";
    case SSLProperty::TRUSTSTORE:
      return "truststore";
    case SSLProperty::TRUSTSTOREPASSWORD:
      return "truststore-password";
    default:
      throw std::invalid_argument(
          "Unknown SSL Property enum: "
              + std::to_string(static_cast<int>(sslProperty)));
  }
}

std::string SSLParameters::getSSLPropertyValue(
    const std::string &propertyName) const {
  auto exists = s_sslProperties.find(propertyName);
  if (exists != s_sslProperties.end()) {
    auto itr = m_sslPropValMap.find(propertyName);
    if (itr != m_sslPropValMap.end()) {
      return itr->second;
    } else {
      return "";
    }
  } else {
    throw std::invalid_argument("Unknown SSL Property: " + propertyName);
  }
}

SSLSocketFactory::SSLSocketFactory(bool passwordsInManager) :
    TSSLSocketFactory(SSLProtocol::SSLTLS), m_params(),
    m_passwordsInManager(passwordsInManager) {
  overrideDefaultPasswordCallback(); // use getPassword override
}

SSLSocketFactory::SSLSocketFactory(const SSLParameters &params,
    bool passwordsInManager) : TSSLSocketFactory(getProtocol(params)),
    m_params(params), m_passwordsInManager(passwordsInManager) {
  overrideDefaultPasswordCallback(); // use getPassword override
}

SSLProtocol SSLSocketFactory::getProtocol(const SSLParameters &params) {
  std::string propVal = params.getSSLPropertyValue(
      SSLParameters::toSSLPropertyName(SSLProperty::PROTOCOL));
  if (!propVal.empty()) {
    if (boost::iequals(propVal, "SSLTLS")) {
      return SSLProtocol::SSLTLS;
    } else if (boost::iequals(propVal, "SSL3")) {
      return SSLProtocol::SSLv3;
    } else if (boost::iequals(propVal, "TLS1.0")) {
      return SSLProtocol::TLSv1_0;
    } else if (boost::iequals(propVal, "TLS1.1")) {
      return SSLProtocol::TLSv1_1;
    } else if (boost::iequals(propVal, "TLS1.2")) {
      return SSLProtocol::TLSv1_2;
    } else {
      throw std::invalid_argument("Unknown SSL protocol: " + propVal);
    }
  } else {
    return SSLProtocol::SSLTLS;
  }
}

void SSLSocketFactory::getPassword(std::string& password, int size) {
  SKIP_UNUSED_WARNING(size); // comes from the base API
  SSLProperty sslProperty = m_params.m_currentProperty;
  std::string name = m_params.getSSLPropertyName(sslProperty);
  switch (sslProperty) {
    case SSLProperty::KEYSTORE:
      name = m_params.getSSLPropertyName(SSLProperty::KEYSTOREPASSWORD);
      break;
    case SSLProperty::CERTIFICATE:
      name = m_params.getSSLPropertyName(SSLProperty::CERTIFICATEPASSWORD);
      break;
    case SSLProperty::TRUSTSTORE:
      name = m_params.getSSLPropertyName(SSLProperty::TRUSTSTOREPASSWORD);
      break;
    default:
      throw std::invalid_argument("Expected password SSL Property: " + name);
  }
  if (m_passwordsInManager) {
    password = Utils::readPasswordFromManager(name,
        m_params.getSSLPropertyValue(name));
  } else {
    password = m_params.getSSLPropertyValue(name);
  }
}
