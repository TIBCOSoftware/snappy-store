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

#ifndef THREADSAFELIST_H_
#define THREADSAFELIST_H_

#include "common/Base.h"

#include <boost/thread/shared_mutex.hpp>
#include <boost/thread/locks.hpp>

#include <algorithm>

namespace io {
namespace snappydata {

  /**
   * A simple read-write synchronized thread-safe list using an underlying
   * STL compatible list.
   * <p>
   * This particular implementation is more like Java and does not follow
   * STL semantics. Latter has problem in exposing internals that are
   * inherently thread-unsafe. To get around that problem (and avoiding
   * copying where required), this class provides for overloads that
   * accepts functors that can act on the result of an index lookup,
   * or old value in a set/remove etc.
   */
  template<typename T, typename TLIST = std::vector<T>>
  class ThreadSafeList {
  public:
    typedef boost::shared_lock<boost::shared_mutex> SharedLock;
    typedef boost::lock_guard<boost::shared_mutex> LockGuard;

  private:
    TLIST m_list;
    mutable boost::shared_mutex m_lock;

  public:
    ThreadSafeList() : m_list(), m_lock() {
    }

    ThreadSafeList(size_t capacity) : m_list(capacity), m_lock() {
    }

    bool contains(const T& v) const {
      SharedLock sync(m_lock);
      sync.lock();

      return std::find(m_list.begin(), m_list.end(), v) != m_list.end();
    }

    void add(const T& v) {
      LockGuard sync(m_lock);

      m_list.push_back(v);
    }

    bool remove(const T& v) {
      LockGuard sync(m_lock);

      typename TLIST::iterator search = std::find(m_list.begin(),
          m_list.end(), v);
      if (search != m_list.end()) {
        m_list.erase(search);
        return true;
      } else {
        return false;
      }
    }

    void get(const int index, T& result) const {
      SharedLock sync(m_lock);
      sync.lock();

      result = m_list.at(index);
    }

    template<typename TPROC>
    void get(const int index, TPROC& proc) const {
      SharedLock sync(m_lock);
      sync.lock();

      proc(m_list.at(index));
    }

    template<typename TPROC>
    void getForWrite(const int index, TPROC& proc) const {
      LockGuard sync(m_lock);

      proc(m_list.at(index));
    }

    void set(const int index, const T& v) {
      LockGuard sync(m_lock);

      m_list[index] = v;
    }

    void set(const int index, const T& v, T& oldValue) {
      LockGuard sync(m_lock);

      oldValue = m_list[index];
      m_list[index] = v;
    }

    template<typename TPROC>
    void set(const int index, const T& v, TPROC& oldValueProc) {
      LockGuard sync(m_lock);

      oldValueProc(m_list[index]);
      m_list[index] = v;
    }

    void add(const int index, const T& v) {
      LockGuard sync(m_lock);

      m_list.insert(m_list.begin() + index, v);
    }

    void remove(const int index) {
      LockGuard sync(m_lock);

      m_list.erase(m_list.begin() + index);
    }

    void remove(const int index, T& oldValue) {
      LockGuard sync(m_lock);

      oldValue = m_list.at(index);
      m_list.erase(m_list.begin() + index);
    }

    template<typename TPROC>
    void remove(const int index, TPROC& oldValueProc) {
      LockGuard sync(m_lock);

      oldValueProc(m_list.at(index));
      m_list.erase(m_list.begin() + index);
    }

    template<typename L>
    void addAll(const L& list) {
      LockGuard sync(m_lock);

      for (typename L::const_iterator iter = list.begin();
          iter != list.end(); ++iter) {
        m_list.push_back(*iter);
      }
    }

    template<typename TITER>
    bool forEachRead(TITER& proc) const {
      SharedLock sync(m_lock);
      sync.lock();

      for (typename TLIST::const_iterator iter = m_list.begin();
          iter != m_list.end(); ++iter) {
        if (proc(*iter)) {
          continue;
        } else {
          return false;
        }
      }
      return true;
    }

    template<typename TITER>
    bool forEach(TITER& proc) const {
      return forEachRead(proc);
    }

    template<typename TITER>
    bool forEach(TITER& proc) {
      LockGuard sync(m_lock);

      for (typename TLIST::iterator iter = m_list.begin();
          iter != m_list.end(); ++iter) {
        if (proc(*iter)) {
          continue;
        } else {
          return false;
        }
      }
      return true;
    }

    bool empty() {
      SharedLock sync(m_lock);
      sync.lock();

      return m_list.empty();
    }

    size_t size() {
      SharedLock sync(m_lock);
      sync.lock();

      return m_list.size();
    }

    void clear() {
      LockGuard sync(m_lock);

      m_list.clear();
    }
  };

} /* namespace snappydata */
} /* namespace io */

#endif /* THREADSAFELIST_H_ */
