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

import com.koloboke.compile.KolobokeMap;
import com.koloboke.function.LongObjPredicate;

@KolobokeMap
public abstract class LongObjectHashMap<V> {

  public static <V> LongObjectHashMap<V> withExpectedSize(int expectedSize) {
    return new KolobokeLongObjectHashMap<>(expectedSize);
  }

  public abstract V put(long key, V value);

  public final void update(long key, V value) {
    put(key, value);
  }

  public abstract V get(long key);

  public abstract boolean contains(long key);

  public abstract V remove(long key);

  public abstract boolean forEachWhile(LongObjPredicate<? super V> predicate);

  public abstract int size();

  public abstract void clear();
}
