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
import java.util.function.BiPredicate;

import com.koloboke.compile.CustomKeyEquivalence;
import com.koloboke.compile.KolobokeMap;

@KolobokeMap
@CustomKeyEquivalence
public abstract class ObjectObjectHashMap<K, V> implements Map<K, V> {

  public static <K, V> ObjectObjectHashMap<K, V> withExpectedSize(int expectedSize) {
    return new KolobokeObjectObjectHashMap<>(expectedSize);
  }

  public static <K, V> ObjectObjectHashMap<K, V> from(Map<K, V> map) {
    KolobokeObjectObjectHashMap<K, V> m = new KolobokeObjectObjectHashMap<>(
        map.size());
    m.putAll(map);
    return m;
  }

  public abstract boolean forEachWhile(BiPredicate<? super K, ? super V> predicate);

  /**
   * Mix the hash code of key else for sequential values the hash map becomes
   * a linear search map. This is equivalent to ClientResolverUtils.fastHashInt
   * with the XOR-shift mixing being done by the generated implementation.
   */
  final int keyHashCode(K key) {
    return key.hashCode() * 0x9E3779B9 /* INT_PHI */;
  }

  final boolean keyEquals(K queriedKey, K keyInMap) {
    return queriedKey.equals(keyInMap);
  }
}
