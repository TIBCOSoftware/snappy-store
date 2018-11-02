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

#include "ClientProperty.h"

using namespace io::snappydata::client;

#define _SNAPPY_SYSPROP_PREFIX "snappydata.client."

const std::string ClientProperty::DEFAULT_PROPERTY_NAME_PREFIX(
    _SNAPPY_SYSPROP_PREFIX);
const std::string ClientProperty::LOG_FILE_NAME(
    _SNAPPY_SYSPROP_PREFIX "log-file");
const std::string ClientProperty::LOG_LEVEL_NAME(
    _SNAPPY_SYSPROP_PREFIX "log-level");
const std::string ClientProperty::SOCKET_INPUT_BUFFER_SIZE_NAME(
    _SNAPPY_SYSPROP_PREFIX "socket-input-buffer-size");
const std::string ClientProperty::SOCKET_OUTPUT_BUFFER_SIZE_NAME(
    _SNAPPY_SYSPROP_PREFIX "socket-output-buffer-size");
const std::string ClientProperty::KEEPALIVE_IDLE_NAME(
    _SNAPPY_SYSPROP_PREFIX "keepalive-idle");
const std::string ClientProperty::KEEPALIVE_INTVL_NAME(
    _SNAPPY_SYSPROP_PREFIX "keepalive-interval");
const std::string ClientProperty::KEEPALIVE_CNT_NAME(
    _SNAPPY_SYSPROP_PREFIX "keepalive-count");
const std::string ClientProperty::DNS_CACHE_SIZE(
    _SNAPPY_SYSPROP_PREFIX "dns-cache-size");
const std::string ClientProperty::DNS_CACHE_FLUSH_INTERVAL(
    _SNAPPY_SYSPROP_PREFIX "dns-cache-flush-interval");
