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
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

#ifndef SYSTEMPROPERTIES_H_
#define SYSTEMPROPERTIES_H_

#include "common/Base.h"

namespace io {
namespace snappydata {

  /** Singleton system-wide shared properties. This class is thread-safe. */
  class SystemProperties {
  private:
    /** disabled default, copy constructor and assignment operator */
    SystemProperties();
    SystemProperties(const SystemProperties&);
    SystemProperties& operator=(const SystemProperties&);

    ~SystemProperties();

  public:

    /**
     * Set value for given property name overwriting previous if one exists.
     */
    static void setProperty(const std::string& propName,
        const std::string& propValue);

    /**
     * Get value for given property name.
     *
     * @return true if there was a current value associated with name
     */
    static bool getProperty(const std::string& propName,
        std::string& resultPropValue);

    /**
     * Get value for given property name.
     *
     * @return true if there was a current value associated with name
     */
    static bool getProperty(const std::string& propName,
        const std::string& def, std::string& resultPropValue);

    /** Load system properties expressed as key=value lines from a file */
    static void load(const std::string& filePath);

    /** Load system properties expressed as key=value lines from a file */
    static void load(const std::string& filePath, const std::locale &loc);

    /**
     * Get string value for given property name. If the property name
     * is not found then return the passed default value.
     *
     * @return current value associated with name or "def"
     */
    static std::string getString(const std::string& propName,
        const std::string& def);

    /**
     * Get boolean value for given property name. If the property name
     * is not found or cannot parse to boolean then return the passed
     * default value.
     *
     * @return current value associated with name as boolean or "def"
     */
    static bool getBoolean(const std::string& propName, bool def);

    /**
     * Get integer value for given property name. If the property name
     * is not found or cannot parse to integer then return the passed
     * default value.
     *
     * @return current value associated with name as integer or "def"
     */
    static int32_t getInteger(const std::string& propName, int32_t def);

    /**
     * Get 64-bit long value for given property name. If the property name
     * is not found or cannot parse to integer then return the passed
     * default value.
     *
     * @return current value associated with name as 64-bit long or "def"
     */
    static int64_t getInt64(const std::string& propName, int64_t def);

    /**
     * Get float value for given property name. If the property name
     * is not found or cannot parse to integer then return the passed
     * default value.
     *
     * @return current value associated with name as float or "def"
     */
    static float getFloat(const std::string& propName, float def);

    /**
     * Get double value for given property name. If the property name
     * is not found or cannot parse to integer then return the passed
     * default value.
     *
     * @return current value associated with name as double or "def"
     */
    static double getDouble(const std::string& propName, double def);

    /**
     * Clear value for given property name.
     *
     * @return pointer to old value associated with name or NULL.
     */
    static bool clearProperty(const std::string& propName);
  };

} /* namespace snappydata */
} /* namespace io */

#endif /* SYSTEMPROPERTIES_H_ */
