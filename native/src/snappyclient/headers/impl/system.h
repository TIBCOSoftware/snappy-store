/*
 * Copyright (c) 2017-2021 TIBCO Software Inc. All rights reserved.
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

#ifndef IMPL_SYSTEM_H_
#define IMPL_SYSTEM_H_

// marked as system header to avoid compilation warnings

#pragma GCC system_header

// common boost includes

#include <boost/config.hpp>
#include <boost/algorithm/string.hpp>
#include <boost/filesystem.hpp>
#include <boost/filesystem/fstream.hpp>
#include <boost/lexical_cast.hpp>
#include <boost/numeric/conversion/cast.hpp>

// common thrift includes

#include <thrift/Thrift.h>
#include <thrift/TToString.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TBinaryProtocol.h>
#include <thrift/protocol/TCompactProtocol.h>
#include <thrift/transport/TBufferTransports.h>
#include <thrift/transport/TSocket.h>
#include <thrift/transport/TSSLSocket.h>

#endif /* IMPL_SYSTEM_H_ */
