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

#include "SystemPropertiesImpl.h"

#include <boost/algorithm/string.hpp>
#include <boost/filesystem.hpp>
#include <boost/filesystem/fstream.hpp>
#include <boost/property_tree/ptree.hpp>
#include <boost/property_tree/ini_parser.hpp>

#include "InternalUtils.h"

using namespace io::snappydata::impl;
using namespace io::snappydata::client::impl;

SystemPropertiesImpl SystemPropertiesImpl::s_instance;

SystemPropertiesImpl::SystemPropertiesImpl() {
}

SystemPropertiesImpl::~SystemPropertiesImpl() {
  // nothing to be done
}

void SystemPropertiesImpl::setProperty(const std::string& propName,
    const std::string& propValue) {
  m_props.put(propName, propValue);
}

bool SystemPropertiesImpl::getProperty(const std::string& propName,
    std::string& resultPropValue) const {
  return m_props.get(propName, &resultPropValue);
}

void SystemPropertiesImpl::load(const std::string& file) {
  std::locale currentLocale;
  load(file, currentLocale);
}

namespace _snappy_impl {
  struct IniPut {
    const std::string operator()(
        const boost::property_tree::ptree::const_iterator& iter) {
      return iter->second.get_value<std::string>();
    }
  };

  static IniPut iniPut;
}

void SystemPropertiesImpl::load(const std::string& file, const std::locale& loc) {
  const boost::filesystem::path filePath = InternalUtils::getPath(file);
  boost::filesystem::ifstream fileStream;

  fileStream.exceptions(std::ios::failbit | std::ios::badbit);
  fileStream.imbue(loc);
  fileStream.open(filePath);

  boost::property_tree::ptree pt;
  boost::property_tree::ini_parser::read_ini(fileStream, pt);

  m_props.clear();

  for (boost::property_tree::ptree::const_iterator iter = pt.begin();
      iter != pt.end(); ++iter) {
    // first level iterator is for each section in the ini file
    // we ignore them and put all key,value pairs in all sections flat
    m_props.putAll(iter->second.begin(), iter->second.end(),
        _snappy_impl::iniPut);
  }
}

bool SystemPropertiesImpl::getProperty(const std::string& propName,
    const std::string& def, std::string& resultPropValue) {
  if (instance().m_props.get(propName, &resultPropValue)) {
    return true;
  } else {
    if (&resultPropValue != &def) {
      resultPropValue = def;
    }
    return false;
  }
}

bool SystemPropertiesImpl::clearProperty(const std::string& propName) {
  return m_props.remove(propName);
}
