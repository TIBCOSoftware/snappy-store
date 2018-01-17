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

import java.util.function.LongFunction;

import com.koloboke.compile.KolobokeMap;
import com.koloboke.function.LongObjPredicate;

@KolobokeMap
public abstract class LongObjectHashMap<V> {

  private Object globalState;

  public static <V> LongObjectHashMap<V> withExpectedSize(int expectedSize) {
    return new KolobokeLongObjectHashMap<>(expectedSize);
  }

  public abstract void justPut(long key, V value);

  public abstract V computeIfAbsent(long key,
      LongFunction<? extends V> mappingFunction);

  public abstract V get(long key);

  public abstract boolean contains(long key);

  public abstract V remove(long key);

  public abstract boolean forEachWhile(LongObjPredicate<? super V> predicate);

  public final Object getGlobalState() {
    return this.globalState;
  }

  public final void setGlobalState(Object state) {
    this.globalState = state;
  }

  public abstract int size();

  public abstract void clear();
}
