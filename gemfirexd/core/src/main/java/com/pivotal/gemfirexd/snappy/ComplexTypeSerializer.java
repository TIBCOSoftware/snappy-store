/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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
package com.pivotal.gemfirexd.snappy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Map;

import com.gemstone.gemfire.internal.ClassPathLoader;

/**
 * Serialization facilities for complex types supported by SnappyData:
 * ARRAY, MAP, STRUCT.
 * <p>
 * The result of {@link ComplexTypeSerializer#getBytes} should be used to
 * send across the bytes for the BLOB storage as used for these types. The
 * following type of java objects are allowed for respective types:
 * <ul>
 * <li><b>ARRAY: </b>Object[], generic arrays and java.util.Collection</li>
 * <li><b>MAP: </b>java.util.Map</li>
 * <li><b>STRUCT: </b>Object[], generic arrays and java.util.Collection</li>
 * </ul>
 */
public abstract class ComplexTypeSerializer {

  private static final Constructor<?> implConstructor;

  static {
    Constructor<?> cons;
    try {
      Class<?> implClass = ClassPathLoader.getLatest().forName(
          "io.snappydata.impl.ComplexTypeSerializerImpl");
      cons = implClass.getConstructor(Object.class);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      cons = null;
    }
    implConstructor = cons;
  }

  protected ComplexTypeSerializer() {
  }

  public static ComplexTypeSerializer create(Object dataType) {
    if (implConstructor != null) {
      try {
        return (ComplexTypeSerializer)implConstructor.newInstance(dataType);
      } catch (InstantiationException | IllegalAccessException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      } catch (InvocationTargetException ite) {
        Throwable cause = ite.getCause();
        throw new IllegalArgumentException(cause.getMessage(), cause);
      }
    } else {
      throw new UnsupportedOperationException(
          "Compound types are supported only with the SnappyData product.");
    }
  }

  /**
   * Serialize an ARRAY or STRUCT type having values in order in an array.
   */
  public abstract <T> byte[] getBytes(T[] v);

  /**
   * Serialize an ARRAY or STRUCT type having values in order in a collection.
   */
  public abstract <T> byte[] getBytes(Collection<T> v);

  /**
   * Serialize a MAP.
   */
  public abstract <K, V> byte[] getBytes(Map<K, V> v);

  /**
   * Serialize an ARRAY, MAP or STRUCT depending on type of object.
   */
  public abstract byte[] getBytes(Object v);
}
