/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
import java.util.function.ObjLongConsumer;
import java.util.function.ToLongFunction;

import com.koloboke.compile.KolobokeMap;
import com.koloboke.function.ObjLongToLongFunction;

@KolobokeMap
public abstract class ObjectLongHashMap<K> {

  public static <K> ObjectLongHashMap<K> withExpectedSize(int expectedSize) {
    return new KolobokeObjectLongHashMap<>(expectedSize);
  }

  public static <K> ObjectLongHashMap<K> from(Map<K, Long> map) {
    KolobokeObjectLongHashMap<K> m = new KolobokeObjectLongHashMap<>(map.size());
    for (Map.Entry<K, Long> entry : map.entrySet()) {
      m.put(entry.getKey(), entry.getValue());
    }
    return m;
  }

  public abstract long put(K key, long value);

  public abstract long getLong(K key);

  public abstract boolean containsKey(K key);

  public abstract long removeAsLong(K key);

  public abstract long computeIfAbsent(
      K key, ToLongFunction<? super K> mappingFunction);

  public abstract long computeIfPresent(
      K key, ObjLongToLongFunction<? super K> mappingFunction);

  public abstract void forEach(ObjLongConsumer<? super K> action);

  public abstract int size();

  public abstract void clear();

  public abstract long addValue(K key, long delta);
}
