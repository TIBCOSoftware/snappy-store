/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package io.snappydata.collection;

import java.util.Map;
import java.util.Set;

import com.koloboke.compile.KolobokeMap;

@KolobokeMap
public abstract class ObjectObjectHashMap<K, V> {

  public static <K, V> ObjectObjectHashMap<K, V> withExpectedSize(int expectedSize) {
    return new KolobokeObjectObjectHashMap<>(expectedSize);
  }

  public abstract V put(K key, V value);

  public abstract V get(K key);

  public abstract boolean contains(Object key);

  public abstract V remove(K key);

  public abstract Set<Map.Entry<K, V>> entrySet();

  public abstract Set<K> keySet();

  public abstract int size();

  public abstract void clear();
}
