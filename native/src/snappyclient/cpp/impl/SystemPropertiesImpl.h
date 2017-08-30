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

#ifndef SYSTEMPROPERTIESIMPL_H_
#define SYSTEMPROPERTIESIMPL_H_

#include "ThreadSafeMap.h"

#include <boost/lexical_cast.hpp>

namespace io {
namespace snappydata {
namespace impl {

  class SystemPropertiesImpl {
  private:
    ThreadSafeMap<std::string, std::string> m_props;

    /** disabled copy constructor and assignment operator */
    SystemPropertiesImpl(const SystemPropertiesImpl&);
    SystemPropertiesImpl& operator=(const SystemPropertiesImpl&);

    /** no public constructor */
    SystemPropertiesImpl();

    static SystemPropertiesImpl s_instance;

    template<typename T>
    struct CastString {
      T m_result;

      inline void operator()(const std::string& k, const std::string& v) {
        m_result = boost::lexical_cast<T>(v);
      }
    };

  public:
    ~SystemPropertiesImpl();

    /** Get the singleton instance of SystemProperties. */
    inline static SystemPropertiesImpl& instance() {
      return s_instance;
    }

    /**
     * Set value for given property name overwriting previous if one exists.
     */
    void setProperty(const std::string& propName, const std::string& propValue);

    /**
     * Get value for given property name.
     *
     * @return true if there was a current value associated with name
     */
    bool getProperty(const std::string& propName,
        std::string& resultPropValue) const;

    /** Load system properties expressed as key=value lines from a file */
    void load(const std::string& filePath);

    /** Load system properties expressed as key=value lines from a file */
    void load(const std::string& filePath, const std::locale &loc);

    /**
     * Get value for given property name.
     *
     * @return true if there was a current value associated with name
     */
    static bool getProperty(const std::string& propName,
        const std::string& def, std::string& resultPropValue);

    /**
     * Get string value for given property name. If the property name
     * is not found then return the passed default value.
     *
     * @return current value associated with name or "def"
     */
    inline static std::string getString(const std::string& propName,
        const std::string& def) {
      std::string propValue;
      if (instance().m_props.get(propName, &propValue)) {
        return propValue;
      } else {
        return def;
      }
    }

    /**
     * Get a value for a generic template type for given property name. If
     * the property name is not found then return the passed default value.
     * <p>
     * This method will compile for whichever types boost::lexical_cast
     * is available.
     *
     * @return current value associated with name or "def"
     */
    template<typename V>
    static V getValue(const std::string& propName, const V& def) {
      CastString<V> castResult = { (V)0 };
      try {
        if (s_instance.m_props.get(propName, castResult)) {
          return castResult.m_result;
        } else {
          return def;
        }
      } catch (const std::exception&) {
        // return default
        return def;
      }
    }

    /**
     * Clear value for given property name.
     *
     * @return pointer to old value associated with name or NULL.
     */
    bool clearProperty(const std::string& propName);
  };

} /* namespace impl */
} /* namespace snappydata */
} /* namespace io */

#endif /* SYSTEMPROPERTIESIMPL_H_ */
