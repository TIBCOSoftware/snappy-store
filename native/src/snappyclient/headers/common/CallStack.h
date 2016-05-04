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
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

#ifndef CALLSTACK_H_
#define CALLSTACK_H_

#include "common/Base.h"

extern "C"
{
#  ifdef __GNUC__
#    include <execinfo.h>
#    include <cxxabi.h>
#  ifndef _GNU_SOURCE
#  define _GNU_SOURCE
#  endif
#    include <dlfcn.h>
#  endif
#  include <stdlib.h>
}

#include <assert.h>
#include <vector>
#include <sstream>

namespace io {
namespace snappydata {

  class CallStack {
  public:

    static const int SNAPPY_STACK_MAX_SIZE = 50;
    typedef std::vector<void *> stack_t;

    class const_iterator;
    class Frame {
    public:

      Frame(void *addr = 0) :
          _addr(0), _dladdr_ret(false), _binary_name(0), _func_name(0),
          _demangled_func_name(0), _delta_sign('+'), _delta(0L),
          _source_file_name(0), _line_number(0) {
        resolve(addr);
      }

      Frame(stack_t::const_iterator const& it) :
          _addr(0), _dladdr_ret(false), _binary_name(0), _func_name(0),
          _demangled_func_name(0), _delta_sign('+'), _delta(0L),
          _source_file_name(0), _line_number(0) {
        resolve(*it);
      }

      Frame(Frame const& other) {
        resolve(other._addr);
      }

      Frame& operator=(Frame const& other) {
        if (this != &other) {
          resolve(other._addr);
        }
        return *this;
      }

      ~Frame() {
        resolve(0);
      }

      std::string toString() const {
        std::ostringstream s;
        s << "[" << std::hex << _addr << "] " << demangledFunction()
            << " (" << binaryFile() << _delta_sign << "0x" << std::hex
            << _delta << ")" << " in " << sourceFile() << ":"
            << lineNumber();
        return s.str();
      }

      const void* addr() const {
        return _addr;
      }
      const char* binaryFile() const {
        return safe(_binary_name);
      }
      const char* function() const {
        return safe(_func_name);
      }
      const char* demangledFunction() const {
        return safe(_demangled_func_name);
      }
      char deltaSign() const {
        return _delta_sign;
      }
      long delta() const {
        return _delta;
      }
      const char* sourceFile() const {
        return safe(_source_file_name);
      }
      int lineNumber() const {
        return _line_number;
      }

    private:

      const char* safe(const char* p) const {
        return p ? p : "??";
      }

      friend class const_iterator; // To call resolve()
      void resolve(const void * addr) {
        if (_addr == addr) return;

        _addr = addr;
        _dladdr_ret = false;
        _binary_name = 0;
        _func_name = 0;
        if (_demangled_func_name) {
          ::free(const_cast<char*>(_demangled_func_name));
          _demangled_func_name = 0;
        }
        _delta_sign = '+';
        _delta = 0L;
        _source_file_name = 0;
        _line_number = 0;

        if (!_addr) return;

        _dladdr_ret = (::dladdr(_addr, &_info) != 0);
        if (_dladdr_ret) {
          _binary_name = safe(_info.dli_fname);
          _func_name = safe(_info.dli_sname);
          _delta_sign = (_addr >= _info.dli_saddr) ? '+' : '-';
          _delta = ::labs(static_cast<const char *>(_addr)
              - static_cast<const char *>(_info.dli_saddr));

          int status = 0;
          _demangled_func_name = abi::__cxa_demangle(_func_name, 0, 0, &status);
        }
      }

    private:

      const void* _addr;
      bool _dladdr_ret;
      const char* _binary_name;
      const char* _func_name;
      const char* _demangled_func_name;
      char _delta_sign;
      long _delta;
      const char* _source_file_name; //TODO: libbfd
      int _line_number;

      Dl_info _info;
    }; // Frame

    class const_iterator : public std::iterator<
        std::bidirectional_iterator_tag, ptrdiff_t> {
    public:

      const_iterator(stack_t::const_iterator const& it) :
          _it(it), _frame(it) {
      }

      bool operator==(const const_iterator& other) const {
        return _frame.addr() == other._frame.addr();
      }

      bool operator!=(const const_iterator& x) const {
        return !(*this == x);
      }

      const Frame& operator*() const {
        return _frame;
      }
      const Frame* operator->() const {
        return &_frame;
      }

      const_iterator& operator++() {
        ++_it;
        _frame.resolve(*_it);
        return *this;
      }
      const_iterator operator++(int) {
        const_iterator tmp = *this;
        ++_it;
        _frame.resolve(*_it);
        return tmp;
      }

      const_iterator& operator--() {
        --_it;
        _frame.resolve(*_it);
        return *this;
      }
      const_iterator operator--(int) {
        const_iterator tmp = *this;
        --_it;
        _frame.resolve(*_it);
        return tmp;
      }

    private:

      const_iterator();

    private:

      stack_t::const_iterator _it;
      Frame _frame;
    }; //const_iterator

    CallStack() : _num_frames(0) {
      _num_frames = ::backtrace(_rstack, SNAPPY_STACK_MAX_SIZE);
      for (int index = 0; index < _num_frames; index++) {
        _stack.push_back(_rstack[index]);
      }
      assert(_num_frames >= 0 && _num_frames <= SNAPPY_STACK_MAX_SIZE);
    }

    std::string toString() {
      std::string s;
      const_iterator itEnd = end();
      for (const_iterator it = begin(); it != itEnd; ++it) {
        s += it->toString();
        s += _SNAPPY_NEWLINE_STR;
      }
      return s;
    }

    virtual ~CallStack() {
    }

    const_iterator begin() const {
      return _stack.begin();
    }
    const_iterator end() const {
      return stack_t::const_iterator(&_stack[_num_frames]);
    }

  private:

    stack_t _stack;
    void* _rstack[SNAPPY_STACK_MAX_SIZE];
    int _num_frames;
  };

} /* namespace snappydata */
} /* namespace io */

#endif /* CALLSTACK_H_ */
