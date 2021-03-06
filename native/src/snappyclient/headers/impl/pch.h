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

/**
 * Precompiled header file used by the project compilation.
 */

#ifndef IMPL_PCH_H_
#define IMPL_PCH_H_

// this header defines some boost constants so has to be included first

#include "common/Base.h"

extern "C" {
#ifdef _WINDOWS
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#else
#include <unistd.h>
#endif
#include <stdio.h>
#include <string.h>
}

// additional common standard classes

#include <algorithm>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

// common boost and thrift headers

#include "impl/system.h"

// some public and internal headers

#ifdef min
#undef min
#endif

#ifdef max
#undef max
#endif

#include "common/SystemProperties.h"
#include "messages/SQLStateMessage.h"
#include "ClientProperty.h"
#include "ClientAttribute.h"
#include "LogWriter.h"
#include "Utils.h"
#include "impl/InternalUtils.h"

#endif /* IMPL_PCH_H_ */
