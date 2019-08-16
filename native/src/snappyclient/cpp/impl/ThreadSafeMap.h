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

#ifndef THREADSAFECONTAINER_H_
#define THREADSAFECONTAINER_H_

#include "common/Base.h"

#include <unordered_map>
#include <boost/thread/shared_mutex.hpp>
#include <boost/thread/locks.hpp>

namespace io {
namespace snappydata {

  /**
   * A simple read-write synchronized thread-safe map using an
   * underlying STL compatible map.
   * <p>
   * This particular implementation is more like Java and does not follow
   * STL semantics. Latter has problem in exposing internals that are
   * inherently thread-unsafe. To get around that problem (and avoiding
   * copying where required), this class provides for overloads that
   * accepts functors that can act on the result of a lookup, or old value
   * in a put/remove etc.
   * <p>
   * A generic concurrent segemented map will be better
   * but no pressing need so far.
   */
  template<typename K, typename V,
      typename TMAP = std::unordered_map<K, V> >
  class ThreadSafeMap {
  public:
    typedef boost::shared_lock<boost::shared_mutex> SharedLock;
    typedef boost::lock_guard<boost::shared_mutex> LockGuard;

  private:
    TMAP m_map;
    mutable boost::shared_mutex m_lock;

  public:
    ThreadSafeMap() : m_map(), m_lock() {
    }

    ThreadSafeMap(size_t capacity) : m_map(capacity), m_lock() {
    }

    bool get(const K& k, V* result) const {
      SharedLock sync(m_lock);

      typename TMAP::const_iterator search = m_map.find(k);
      if (search != m_map.end()) {
        if (result != NULL) {
          *result = search->second;
        }
        return true;
      } else {
        return false;
      }
    }

    template<typename TPROC>
    bool get(const K& k, TPROC& proc) const {
      SharedLock sync(m_lock);

      typename TMAP::const_iterator search = m_map.find(k);
      if (search != m_map.end()) {
        proc(search->first, search->second);
        return true;
      } else {
        return false;
      }
    }

    template<typename TPROC>
    bool getForWrite(const K& k, TPROC& proc) const {
      LockGuard sync(m_lock);

      typename TMAP::const_iterator search = m_map.find(k);
      if (search != m_map.end()) {
        proc(search->first, search->second);
        return true;
      } else {
        return false;
      }
    }

    bool containsKey(const K& k) const {
      SharedLock sync(m_lock);

      return m_map.find(k) != m_map.end();
    }

    bool remove(const K& k) {
      LockGuard sync(m_lock);

      return (m_map.erase(k) > 0);
    }

    bool remove(const K& k, V* oldValue) {
      LockGuard sync(m_lock);

      typename TMAP::iterator search = m_map.find(k);
      if (search != m_map.end()) {
        if (oldValue != NULL) {
          *oldValue = search->second;
        }
        m_map.erase(search);
        return true;
      } else {
        return false;
      }
    }

    template<typename TPROC>
    bool remove(const K& k, TPROC& oldValueProc) {
      LockGuard sync(m_lock);

      typename TMAP::iterator search = m_map.find(k);
      if (search != m_map.end()) {
        oldValueProc(search->second);
        m_map.erase(search);
        return true;
      } else {
        return false;
      }
    }

    void put(const K& k, const V& v) {
      LockGuard sync(m_lock);

      m_map[k] = v;
    }

    bool put(const K& k, const V& v, V* oldValue) {
      LockGuard sync(m_lock);

      std::pair<typename TMAP::iterator, bool> result = m_map.insert(
          std::make_pair(k, v));
      if (result.second) {
        return true;
      } else {
        if (oldValue != NULL) {
          *oldValue = (result.first)->second;
        }
        (result.first)->second = v;
        return false;
      }
    }

    template<typename TPROC>
    bool put(const K& k, const V& v, TPROC& oldValueProc) {
      LockGuard sync(m_lock);

      std::pair<typename TMAP::iterator, bool> result = m_map.insert(
          std::make_pair(k, v));
      if (result.second) {
        return true;
      } else {
        oldValueProc((result.first)->second);
        (result.first)->second = v;
        return false;
      }
    }

    bool putIfAbsent(const K& k, const V& v)
    {
      LockGuard sync(m_lock);

      return m_map.insert(std::make_pair(k, v)).second;
    }

    bool putIfAbsent(const K& k, const V& v, V* oldValue) {
      LockGuard sync(m_lock);

      std::pair<typename TMAP::iterator, bool> result = m_map.insert(
          std::make_pair(k, v));
      if (result.second) {
        return true;
      } else {
        if (oldValue != NULL) {
          *oldValue = (result.first)->second;
        }
        return false;
      }
    }

    template<typename PROC>
    bool putIfAbsent(const K& k, const V& v, PROC& oldValueProc) {
      LockGuard sync(m_lock);

      std::pair<typename TMAP::iterator, bool> result = m_map.insert(
          std::make_pair(k, v));
      if (result.second) {
        return true;
      } else {
        oldValueProc((result.first)->second);
        return false;
      }
    }

    template<typename TIter>
    void putAll(const TIter& begin, const TIter& end) {
      LockGuard sync(m_lock);

      for (TIter iter = begin; iter != end; ++iter) {
        m_map[iter->first] = iter->second;
      }
    }

    template<typename TIter, typename TProc>
    void putAll(const TIter& begin, const TIter& end, TProc& proc) {
      LockGuard sync(m_lock);

      for (TIter iter = begin; iter != end; ++iter) {
        m_map[iter->first] = proc(iter);
      }
    }

    template<typename TITER>
    bool forEachKeyRead(TITER& proc) const {
      SharedLock sync(m_lock);

      for (typename TMAP::const_iterator iter = m_map.begin();
          iter != m_map.end(); ++iter) {
        if (proc(iter->first)) {
          continue;
        } else {
          return false;
        }
      }
      return true;
    }

    template<typename TITER>
    bool forEachKey(TITER& proc) const {
      return forEachKeyRead(proc);
    }

    template<typename TITER>
    bool forEachKey(TITER& proc) {
      LockGuard sync(m_lock);

      for (typename TMAP::iterator iter = m_map.begin();
          iter != m_map.end(); ++iter) {
        if (proc(iter->first)) {
          continue;
        } else {
          return false;
        }
      }
      return true;
    }

    template<typename TITER>
    bool forEachEntryRead(TITER& proc) const {
      SharedLock sync(m_lock);

      for (typename TMAP::const_iterator iter = m_map.begin();
          iter != m_map.end(); ++iter) {
        if (proc(iter->first, iter->second)) {
          continue;
        } else {
          return false;
        }
      }
      return true;
    }

    template<typename TITER>
    bool forEachEntry(TITER& proc) const {
      return forEachEntryRead(proc);
    }

    template<typename TITER>
    bool forEachEntry(TITER& proc) {
      LockGuard sync(m_lock);

      for (typename TMAP::iterator iter = m_map.begin();
          iter != m_map.end(); ++iter) {
        if (proc(iter->first, iter->second)) {
          continue;
        } else {
          return false;
        }
      }
      return true;
    }

    bool empty() {
      SharedLock sync(m_lock);

      return m_map.empty();
    }

    size_t size() {
      SharedLock sync(m_lock);

      return m_map.size();
    }

    void clear() {
      LockGuard sync(m_lock);

      m_map.clear();
    }
  };

} /* namespace snappydata */
} /* namespace io */

#endif /* THREADSAFECONTAINER_H_ */
