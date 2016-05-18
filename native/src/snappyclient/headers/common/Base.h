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

/**
 * Base.h
 */

#ifndef BASE_H_
#define BASE_H_

#if defined(WIN32) || defined(__MINGW32__)
#  ifndef _WIN32
#    define _WIN32 1
#  endif
#endif
#if defined(WIN64) || defined(__MINGW64__)
#  ifndef _WIN64
#    define _WIN64 1
#  endif
#endif
#if defined(_WIN32) || defined(_WIN64)
#  ifndef _WINDOWS
#    define _WINDOWS 1
#  endif
#endif

#if defined(__linux__) || defined(__linux)
#  ifndef _LINUX
#    define _LINUX 1
#  endif
#  ifndef _POSIX
#    define _POSIX 1
#    define _HAS_EXTERN_TEMPLATE 1
#  endif
#endif

#ifdef __sun
#  ifndef _SOLARIS
#    define _SOLARIS 1
#  endif
#  ifndef _POSIX
#    define _POSIX 1
#  endif
#endif

#ifdef __APPLE__
#  ifdef __OSX__
#    ifndef _MACOSX
#      define _MACOSX 1
#    endif
#    ifndef _POSIX
#      define _POSIX 1
#    endif
#  else
#    ifndef _MACOS
#      define _MACOS 1
#    endif
#  endif
#endif

#ifdef __FreeBSD__
#  ifndef _FREEBSD
#    define _FREEBSD 1
#  endif
#  ifndef _POSIX
#    define _POSIX 1
#  endif
#endif

/* define the macro for DLL export/import on windows */
#ifdef _WINDOWS
#  define DLLEXPORT __declspec(dllexport)
#  define DLLIMPORT __declspec(dllimport)
#  ifdef DLLBUILD
#    define DLLPUBLIC DLLEXPORT
#  else
#    define DLLPUBLIC DLLIMPORT
#  endif
#else
#  define DLLPUBLIC
#endif

#if defined(_WINDOWS)
#  define _SNAPPY_NEWLINE "\r\n"
#  define _SNAPPY_NEWLINE_STR "\r\n"
#elif defined(_MACOS)
#  define _SNAPPY_NEWLINE '\r'
#  define _SNAPPY_NEWLINE_STR "\r"
#else
#  define _SNAPPY_NEWLINE '\n'
#  define _SNAPPY_NEWLINE_STR "\n"
#endif

#include <cstdint>
#include <string>

#define BOOST_FILESYSTEM_NO_DEPRECATED
#define BOOST_MPL_CFG_NO_PREPROCESSED_HEADERS
#define BOOST_MPL_LIMIT_LIST_SIZE 30
#define BOOST_MPL_LIMIT_VECTOR_SIZE 30

#endif /* BASE_H_ */
