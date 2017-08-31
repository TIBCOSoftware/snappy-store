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

#ifndef ARRAYLIST_H_
#define ARRAYLIST_H_

extern "C" {
#include <stdlib.h>
}

#include <sstream>
#include <stdexcept>
#include <utility>

namespace io {
namespace snappydata {

  /**
   * A simple std::vector like class with limited operations.
   * The main addition is a constructor taking a boolean argument
   * that does not initialize anything for use by placement new
   * when memory has already been allocated and initialized.
   * <p>
   * DO NOT USE THIS CLASS FOR TYPES HAVING CUSTOM NEW/DELETE OPERATORS.
   */
  template<typename T>
  class ArrayList {
  private:
    T* m_list;
    size_t m_capacity;
    size_t m_size;

    void init(const size_t capacity);
    void _clear();
    void changeCapacity(const size_t newCapacity);
    void shrinkCapacity();

  public:
    static const size_t INIT_CAPACITY = 4;

    template<typename Tp>
    class _Iter {
    private:
      T* m_p;

      _Iter(T* p) :
          m_p(p) {
      }

      friend class ArrayList<T>;

    public:
      _Iter() : m_p(0) {
      }

      _Iter(const _Iter& other) :
          m_p(other.m_p) {
      }

      _Iter& operator=(const _Iter& other) {
        m_p = other.m_p;
        return *this;
      }

      inline T& operator*() const {
        return *m_p;
      }

      inline Tp operator->() const {
        return m_p;
      }

      inline _Iter& operator++() {
        m_p++;
        return *this;
      }

      inline bool operator==(const _Iter& other) {
        return m_p == other.m_p;
      }

      inline bool operator!=(const _Iter& other) {
        return m_p != other.m_p;
      }
    };

    typedef _Iter<T*> iterator;
    typedef _Iter<const T*> const_iterator;

    ArrayList() : m_list(NULL), m_capacity(0), m_size(0) {
    }

    ArrayList(const size_t capacity) {
      init(capacity);
    }

    ArrayList(const ArrayList& other) {
      init(other.m_capacity);
      const size_t size = other.m_size;
      if (size == 0) {
        return;
      }
      push_back(other.begin(), size);
    }

    ArrayList& operator=(const ArrayList& other) {
      const size_t otherSize = other.m_size;
      if (otherSize == 0 && m_size == 0) {
        return *this;
      }
      clear();
      if (otherSize > 0) {
        push_back(other.begin(), other.size());
      }
      return *this;
    }

    /** special constructor to skip initialization for placement new */
    inline ArrayList(bool skipInitialize) {
    }

    ~ArrayList();

    void push_back(const T& v) {
      const size_t size = m_size;
      if (size < m_capacity) {
        new (m_list + size) T(v);
        m_size++;
      } else {
        changeCapacity(m_capacity < INIT_CAPACITY ? INIT_CAPACITY
            : (m_capacity + (m_capacity >> 1)));
        new (m_list + size) T(v);
        m_size++;
      }
    }

    T& push_back() {
      const size_t size = m_size;
      if (size >= m_capacity) {
        changeCapacity(m_capacity < INIT_CAPACITY ? INIT_CAPACITY
            : (m_capacity + (m_capacity >> 1)));
      }
      T* back = new (m_list + size) T();
      m_size++;
      return *back;
    }

    ArrayList(ArrayList&& other) : m_list(other.m_list),
        m_capacity(other.m_capacity), m_size(other.m_size) {
      other.m_list = NULL;
      other.m_capacity = 0;
      other.m_size = 0;
    }

    ArrayList& operator=(ArrayList&& other) {
      this->swap(other);
      return *this;
    }

    void push_back(T&& v) {
      const size_t size = m_size;
      if (size < m_capacity) {
        new (m_list + size) T(std::move(v));
        m_size++;
      } else {
        changeCapacity(m_capacity < INIT_CAPACITY ? INIT_CAPACITY
            : (m_capacity + (m_capacity >> 1)));
        new (m_list + size) T(std::move(v));
        m_size++;
      }
    }

    template<typename TIter>
    void push_back(TIter start, const size_t numItems);

    void pop_back() {
      if (m_size > 0) {
        (m_list + m_size - 1)->~T();
        --m_size;
        shrinkCapacity();
      } else {
        throw std::out_of_range("size=0");
      }
    }

    void erase(const size_t index) {
      const size_t size = m_size;
      if (index == (size - 1)) {
        pop_back();
      } else if (index < size) {
        const T* list = m_list;
        (list + index)->~T();
        m_size--;

        //size_t newCapacity;
        const size_t capacity = m_capacity;
        if (capacity > (INIT_CAPACITY * 2) && m_size < (capacity >> 1)) {
          m_capacity = m_size + INIT_CAPACITY;
        }
        m_list = static_cast<T*>(::malloc(sizeof(T) * m_capacity));
        if (index > 0) {
          ::memcpy(m_list, list, sizeof(T) * index);
        }
        ::memcpy(m_list + sizeof(T) * index, list + sizeof(T) * (index + 1),
            sizeof(T) * (size - index - 1));
      } else {
        std::ostringstream err;
        err << "index=" << index << " size=" << size;
        throw std::out_of_range(err.str());
      }
    }

    inline T& at(const size_t index) {
      if (index < m_size) {
        return m_list[index];
      } else {
        std::ostringstream err;
        err << "index=" << index << " size=" << size;
        throw std::out_of_range(err.str());
      }
    }

    inline const T& at(const size_t index) const {
      if (index < m_size) {
        return m_list[index];
      } else {
        std::ostringstream err;
        err << "index=" << index << " size=" << size;
        throw std::out_of_range(err.str());
      }
    }

    /**
     * Get the first element in list. No index check is done like
     * {@code operator[]}. Use {@link at} for range checks.
     */
    inline const T& first() const {
      return *m_list;
    }

    /**
     * Get the first element in list. No index check is done like
     * {@code operator[]}. Use {@link at} for range checks.
     */
    inline T& first() {
      return *m_list;
    }

    /**
     * Get the last element in list. No index check is done like
     * {@code operator[]}. Use {@link at} for range checks.
     */
    inline const T& last() const {
      return m_list[m_size - 1];
    }

    /**
     * Get the last element in list. No index check is done like
     * {@code operator[]}. Use {@link at} for range checks.
     */
    inline T& last() {
      return m_list[m_size - 1];
    }

    inline T& operator[](const size_t index) {
      return m_list[index];
    }

    inline const T& operator[](const size_t index) const {
      return m_list[index];
    }

    inline size_t size() const {
      return m_size;
    }

    inline bool empty() const {
      return (m_size == 0);
    }

    inline size_t capacity() const {
      return m_capacity;
    }

    size_t capacity(const size_t newSize);

    void resize(const size_t newSize);

    void reserve(const size_t newSize);

    void clear() {
      if (m_size > 0) {
        _clear();
        m_size = 0;
      }
    }

    bool operator==(const ArrayList& other) const;
    bool operator!=(const ArrayList& other) const;

    void swap(ArrayList& other) {
      std::swap(m_list, other.m_list);
      std::swap(m_size, other.m_size);
      std::swap(m_capacity, other.m_capacity);
    }

    iterator begin() {
      return iterator(m_list);
    }

    const_iterator begin() const {
      return const_iterator(m_list);
    }

    const_iterator cbegin() const {
      return const_iterator(m_list);
    }

    iterator end() {
      return iterator(m_list + m_size);
    }

    const_iterator end() const {
      return const_iterator(m_list + m_size);
    }

    const_iterator cend() const {
      return const_iterator(m_list + m_size);
    }
  };

} /* namespace snappydata */
} /* namespace io */

template<typename T>
void io::snappydata::ArrayList<T>::init(const size_t capacity) {
  if (capacity != 0) {
    m_list = static_cast<T*>(::malloc(sizeof(T) * capacity));
    m_capacity = capacity;
    m_size = 0;
  } else {
    m_list = NULL;
    m_capacity = 0;
    m_size = 0;
  }
}

template<typename T>
void io::snappydata::ArrayList<T>::changeCapacity(
    const size_t newCapacity) {
  m_list = static_cast<T*>(::realloc(m_list, sizeof(T) * newCapacity));
  m_capacity = newCapacity;
}

template<typename T>
void io::snappydata::ArrayList<T>::shrinkCapacity() {
  if (m_capacity > (INIT_CAPACITY * 2) && m_size < (m_capacity >> 1)) {
    changeCapacity(m_size + INIT_CAPACITY);
  }
}

template<typename T>
io::snappydata::ArrayList<T>::~ArrayList() {
  if (m_list != NULL) {
    if (m_size > 0) {
      _clear();
    }
    ::free(m_list);
  }
}

template<typename T>
template<typename TIter>
void io::snappydata::ArrayList<T>::push_back(TIter start,
    const size_t numItems) {
  if (numItems == 0) {
    return;
  }
  const size_t newCapacity = m_size + numItems;
  if (newCapacity > m_capacity) {
    changeCapacity(newCapacity);
  }
  T* tp = m_list;
  const T* const end = m_list + numItems;
  while (tp < end) {
    new (tp) T(*start);
    ++tp;
    ++start;
  }
  m_size += numItems;
}

template<typename T>
size_t io::snappydata::ArrayList<T>::capacity(const size_t newSize) {
  const size_t capacity = m_capacity;
  if (newSize != capacity) {
    changeCapacity(newSize);
  }
  return capacity;
}

template<typename T>
void io::snappydata::ArrayList<T>::resize(const size_t newSize) {
  if (newSize > m_capacity) {
    changeCapacity(newSize);
  }
  if (newSize > m_size) {
    T* tp = m_list + m_size;
    const T* const end = m_list + newSize;
    while (tp < end) {
      new (tp) T();
      tp++;
    }
    m_size = newSize;
  } else if (newSize < m_size) {
    T* tp = m_list + m_size;
    const T* const end = m_list + newSize;
    while (--tp >= end) {
      tp->~T();
    }
    m_size = newSize;
  }
  // reduce capacity if resize small enough
  if ((newSize * 3) < (m_capacity << 1)) {
    changeCapacity(newSize);
  }
}

template<typename T>
void io::snappydata::ArrayList<T>::reserve(const size_t newSize) {
  if (newSize > m_capacity) {
    changeCapacity(newSize);
  }
}

template<typename T>
void io::snappydata::ArrayList<T>::_clear() {
  T* tp = m_list;
  const T* const end = m_list + m_size;
  while (tp < end) {
    tp->~T();
    tp++;
  }
}

template<typename T>
bool io::snappydata::ArrayList<T>::operator==(
    const ArrayList<T>& other) const {
  if (m_size != other.m_size) {
    return false;
  }
  const_iterator start1 = cbegin();
  const_iterator start2 = other.cbegin();
  const_iterator end1 = cend();
  while (start1 != end1) {
    if (*start1 != *start2) {
      return false;
    }
    ++start1;
    ++start2;
  }
  return true;
}

template<typename T>
bool io::snappydata::ArrayList<T>::operator !=(
    const ArrayList<T>& other) const {
  return !operator ==(other);
}

namespace std {
  template<typename T>
  inline void swap(io::snappydata::ArrayList<T> list1,
      io::snappydata::ArrayList<T>& list2) {
    list1.swap(list2);
  }

  template<typename T>
  std::ostream& operator<<(std::ostream& out,
      const io::snappydata::ArrayList<T>& list) {
    out << '(';
    if (list.size() > 0) {
      typename io::snappydata::ArrayList<T>::const_iterator tp = list.cbegin();
      typename io::snappydata::ArrayList<T>::const_iterator end = list.cend();
      out << *tp;
      while (++tp != end) {
        out << ',' << *tp;
      }
    }
    return (out << ')');
  }
}

#endif /* ARRAYLIST_H_ */
