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

import com.google.common.hash.Hashing;

/**
 * Default serializer for {@link DenseIntValueHashMap}
 * assumes 1 byte characters in key strings.
 */
public final class DHMDefaultSerializer implements DenseHashMapSerializer<String> {

  private static final int VALUE_WIDTH = 1;

  //FNV-1a hash
  private final long addToHash(char c, long hash) {
    return (int)((hash ^ (int)c) * 16777619);
  }

  protected long baseHashValue() {
    return 2166136261L;
  }

  @Override
  public boolean equals(String key, byte[] entry) {

    for (int i = 0, len = key.length(); i < len; i++) {
      if (key.charAt(i) != (char)entry[i]) {
        return false;
      }
    }

    return true;
  }

  @Override
  public boolean equals(byte[] entry1, byte[] entry2) {

    if (entry1.length != entry2.length - VALUE_WIDTH) {
      return false;
    }

    for (int i = 0, len = entry1.length; i < len; i++) {
      if (entry1[i] != entry2[i]) {
        return false;
      }
    }

    return true;
  }

  @Override
  public String getKey(byte[] entry) {
    int len = entry.length - VALUE_WIDTH;
    char[] v = new char[len];
    for (int i = 0; i < len; i++) {
      v[i] = (char)entry[i];
    }
    return new String(v);
  }

  @Override
  public int deserializeValue(byte[] entry) {
    return DenseIntValueHashMap.readInt(entry, entry.length - VALUE_WIDTH, VALUE_WIDTH);
  }

  @Override
  public int getHashCode(String key) {

    final int keyLen = key.length();

    long hash = baseHashValue();
    for (int i = 0; i < keyLen; i++) {
      hash = addToHash(key.charAt(i), hash);
    }

    //return Integer.rotateLeft((int)hash, 3);
    return Hashing.murmur3_32().hashInt((int)hash).asInt();
  }

  @Override
  public int getHashCode(byte[] entry) {
    int len = entry.length - VALUE_WIDTH;
    long hash = baseHashValue();
    for (int i = 0; i < len; i++) {
      hash = addToHash((char)entry[i], hash);
    }
    //return Integer.rotateLeft((int)hash, 3);
    return Hashing.murmur3_32().hashInt((int)hash).asInt();
  }

  @Override
  public byte[] serialize(String key, int value) {

    final int keyLen = key.length();

    final byte[] tgtVal;
    int beginWrite;

    tgtVal = new byte[keyLen + VALUE_WIDTH];
    beginWrite = 0;

    for (int i = 0; i < keyLen; i++) {
      tgtVal[beginWrite++] = (byte)key.charAt(i);
    }

    DenseIntValueHashMap.writeInt(tgtVal, value, beginWrite, VALUE_WIDTH);

    return tgtVal;
  }
}
