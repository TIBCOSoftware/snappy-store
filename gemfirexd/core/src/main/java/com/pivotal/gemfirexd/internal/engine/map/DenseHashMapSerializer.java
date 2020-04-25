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
package com.pivotal.gemfirexd.internal.engine.map;

/**
 * An interface that serializes/deserializes key/value objects stored in {@link DenseIntValueHashMap}.
 * The interface primarily attempts to reduce garbage by avoiding de-serialization of the key to the extent possible.
 * Implementation of this must be stateless and immutable.
 *
 * @param <K> Any Java object as key.
 */
public interface DenseHashMapSerializer<K> {

  /**
   * Serialize a given (key, value) of a hashmap.
   * <p/>
   * note: length of the key is not required to be recorded due to
   * fixed width value. (byte[].length - 4) will be key length, thus
   * saves up to 4 bytes.
   *
   * @param key   key to be recorded
   * @param value int value.
   * @return serialized byte[]
   */
  byte[] serialize(K key, int value);

  /**
   * Returns hash code of the key object. This should be consistent with
   * {@code equals} method.
   *
   * @param key the object supplied by the user.
   * @return hash code of the key
   */
  int getHashCode(K key);

  /**
   * Returns the hash code of the key from an entry (byte[]) returned
   * from {@code serialize} method. This should be consistent with
   * {@code equals} method.
   *
   * @param entry
   * @return
   */
  int getHashCode(byte[] entry);

  /**
   * Returns keys' equality.
   *
   * @param key   incoming user key object.
   * @param entry byte[] returned using {@code serialize} method.
   * @return boolean {@code true} if equal, {@code false} otherwise.
   */
  boolean equals(K key, byte[] entry);

  /**
   * Returns keys' equality. Mainly used during rehashing.
   *
   * @param entry1 first entry (byte[]) returned using {@code serialize} method.
   * @param entry2 another entry (byte[]) returned using {@code serialize} method.
   * @return boolean {@code true} if both entries are equal, {@code false} otherwise.
   */
  boolean equals(byte[] entry1, byte[] entry2);

  /**
   * Mainly for debugging purposes, extract key from the serialized value.
   *
   * @param entry byte[] returned from {@code serialize} method.
   * @return key value with K type.
   */
  K getKey(byte[] entry);

  /**
   * Extract int value from the serialized entry.
   *
   * @param entry byte[] returnred from {@code serialize} method.
   * @return int value stored earlier.
   */
  int deserializeValue(byte[] entry);
}
