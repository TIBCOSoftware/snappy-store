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

#include "common/SystemProperties.h"

#include "../impl/SystemPropertiesImpl.h"

using namespace io::snappydata;
using namespace io::snappydata::impl;

void SystemProperties::setProperty(const std::string& propName,
    const std::string& propValue) {
  SystemPropertiesImpl::instance().setProperty(propName, propValue);
}

bool SystemProperties::getProperty(const std::string& propName,
    std::string& resultPropValue) {
  return SystemPropertiesImpl::instance().getProperty(propName, resultPropValue);
}

void SystemProperties::load(const std::string& file) {
  std::locale currentLocale;
  SystemPropertiesImpl::instance().load(file, currentLocale);
}

void SystemProperties::load(const std::string& file, const std::locale& loc) {
  SystemPropertiesImpl::instance().load(file, loc);
}

bool SystemProperties::getProperty(const std::string& propName,
    const std::string& def, std::string& resultPropValue) {
  return SystemPropertiesImpl::instance().getProperty(propName, def,
      resultPropValue);
}

bool SystemProperties::getBoolean(const std::string& propName, bool def) {
  return SystemPropertiesImpl::getValue(propName, def);
}

int32_t SystemProperties::getInteger(const std::string& propName, int32_t def) {
  return SystemPropertiesImpl::getValue(propName, def);
}

int64_t SystemProperties::getInt64(const std::string& propName, int64_t def) {
  return SystemPropertiesImpl::getValue(propName, def);
}

float SystemProperties::getFloat(const std::string& propName, float def) {
  return SystemPropertiesImpl::getValue(propName, def);
}

double SystemProperties::getDouble(const std::string& propName, double def) {
  return SystemPropertiesImpl::getValue(propName, def);
}

bool SystemProperties::clearProperty(const std::string& propName) {
  return SystemPropertiesImpl::instance().clearProperty(propName);
}
