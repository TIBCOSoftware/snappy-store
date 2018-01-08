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
package com.gemstone.gemfire.internal.util.concurrent;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import io.snappydata.collection.ObjectObjectHashMap;

/**
 * A copy on write hash map.
 * 
 * Note that the entryKey and keySet of this map are unmodifable.
 * Should be easy to make them modifiable at a future time.
 * 
 * @author dsmith
 *
 */
public class CopyOnWriteHashMap<K,V> extends AbstractMap<K, V> {
  private volatile Map<K,V> map = Collections.<K,V>emptyMap();

  public CopyOnWriteHashMap() {
    
  }
  
  public CopyOnWriteHashMap(Map map) {
    this.putAll(map);
  }
  

  @Override
  public V get(Object key) {
    return map.get(key);
  }



  @Override
  public synchronized V put(K key, V value) {
    ObjectObjectHashMap<K, V> tmp = ObjectObjectHashMap.from(map);
    V result = tmp.put(key, value);
    map = Collections.unmodifiableMap(tmp);
    return result;
  }



  @Override
  public synchronized void putAll(Map<? extends K, ? extends V> m) {
    ObjectObjectHashMap<K, V> tmp = ObjectObjectHashMap.from(map);
    tmp.putAll(m);
    map = Collections.unmodifiableMap(tmp);
  }

  @Override
  public synchronized V remove(Object key) {
    ObjectObjectHashMap<K, V> tmp = ObjectObjectHashMap.from(map);
    V result = tmp.remove(key);
    map = Collections.unmodifiableMap(tmp);
    return result;
  }
  


  @Override
  public synchronized void clear() {
    map = Collections.emptyMap();
  }


  @Override
  public Set<java.util.Map.Entry<K, V>> entrySet() {
    return map.entrySet();
  }



  @Override
  public int size() {
    return map.size();
  }



  @Override
  public boolean isEmpty() {
    return map.isEmpty();
  }



  @Override
  public boolean containsValue(Object value) {
    return map.containsValue(value);
  }



  @Override
  public boolean containsKey(Object key) {
    return map.containsKey(key);
  }




  @Override
  public Set<K> keySet() {
    return map.keySet();
  }



  @Override
  public Collection<V> values() {
    return map.values();
  }



  @Override
  public boolean equals(Object o) {
    return map.equals(o);
  }



  @Override
  public int hashCode() {
    return map.hashCode();
  }



  @Override
  public String toString() {
    return map.toString();
  }



  @Override
  protected Object clone() throws CloneNotSupportedException {
    CopyOnWriteHashMap<K, V>clone = new CopyOnWriteHashMap<K, V>();
    clone.map = map;
    return clone;
  }
  
  public Map<K,V> getInnerMap() {
    return map;
  }

}
