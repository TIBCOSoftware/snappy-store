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

#include "SSLParameters.h"

#include <boost/algorithm/string.hpp>

using namespace io::snappydata;
using namespace io::snappydata::client::impl;
using namespace io::snappydata::thrift;

const std::set<std::string> SSLParameters::s_sslProperties {
    "protocol", "cipher-suites", "client-auth", "enabled-protocols", "keystore",
    "keystore-password", "certificate", "certificate-password", "truststore",
    "truststore-password" };

void SSLParameters::operator()(const std::string& str) {
  size_t spos;
  if ((spos = str.find('=')) != std::string::npos) {
    std::string propertyValue = str.substr(spos + 1);
    std::string propertyName = str.substr(0, spos);
    setSSLProperty(propertyName, propertyValue);
    return;
  }
}

void SSLParameters::setSSLProperty(std::string &propertyName,
    std::string& value) {
  auto itr = s_sslProperties.find(propertyName);
  if (itr != s_sslProperties.end()) {
    auto mapItr = m_sslPropValMap.find(propertyName);
    if (mapItr == m_sslPropValMap.end()) {
      m_sslPropValMap.insert(
          std::pair<std::string, std::string>(propertyName, value));
    }
  } else {
    throw std::invalid_argument(":Unknown SSL Property:" + propertyName);
  }
}

std::string SSLParameters::getSSLPropertyName(SSLProperty sslProperty) {
  m_currentProperty = sslProperty;
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
          ":Unknown SSL Property enum: "
              + std::to_string(static_cast<int>(sslProperty)));
  }
}

std::string SSLParameters::getSSLPropertyValue(
    const std::string &propertyName) const {
  auto itr = s_sslProperties.find(propertyName);
  if (itr != s_sslProperties.end()) {
    auto itr = m_sslPropValMap.find(propertyName);
    if (itr != m_sslPropValMap.end()) {
      return itr->second;
    } else {
      return "";
    }
  } else {
    throw std::invalid_argument(":Unknown SSL Property: " + propertyName);
  }
}

SSLSocketFactory::SSLSocketFactory(SSLParameters& params) :
    TSSLSocketFactory(getProtocol(params)), m_params(params) {
  overrideDefaultPasswordCallback(); // use getPassword override
}

SSLProtocol SSLSocketFactory::getProtocol(const SSLParameters& params) {
  std::string propVal = params.getSSLPropertyValue("protocol");
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
      throw std::invalid_argument(":Unknown SSL protocol: " + propVal);
    }
  } else {
    return SSLProtocol::SSLTLS;
  }
}

void SSLSocketFactory::getPassword(std::string& password, int size) {
  /* TODO: the password fields should be fetched from UI for ODBC */

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
      throw std::invalid_argument(":Expected password SSL Property: " + name);
  }
  password = m_params.getSSLPropertyValue(name);
}
