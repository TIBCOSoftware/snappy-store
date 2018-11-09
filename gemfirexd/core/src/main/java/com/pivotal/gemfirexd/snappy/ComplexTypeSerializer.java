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
package com.pivotal.gemfirexd.snappy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.SQLException;

import com.gemstone.gemfire.internal.ClassPathLoader;

/**
 * Serialization and deserialization facilities for complex types supported
 * by SnappyData: ARRAY, MAP, STRUCT.
 * <p>
 * The result of {@link ComplexTypeSerializer#serialize} should be used to
 * send across the bytes for the BLOB storage as used for these types. The
 * following type of java objects are allowed for respective types:
 * <ul>
 * <li><b>ARRAY: </b>Object[], generic arrays and java.util.Collection</li>
 * <li><b>MAP: </b>java.util.Map</li>
 * <li><b>STRUCT: </b>Object[], generic arrays and java.util.Collection</li>
 * </ul>
 * <p>
 * Given the bytes stored as BLOB, the {@link ComplexTypeSerializer#deserialize}
 * method can be used to create a java object for respective types:
 * <ul>
 * <li><b>ARRAY: </b>java.util.List</li>
 * <li><b>MAP: </b>java.util.Map</li>
 * <li><b>STRUCT: </b>java.util.Collection</li>
 * </ul>
 * <p>
 * A single serializer object for a column of a table cannot be shared by
 * multiple threads.
 */
public abstract class ComplexTypeSerializer {

  private static final Constructor<?> implConstructor;

  static {
    Constructor<?> cons;
    try {
      Class<?> implClass = ClassPathLoader.getLatest().forName(
          "io.snappydata.impl.ComplexTypeSerializerImpl");
      cons = implClass.getConstructor(String.class, String.class,
          Connection.class);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      cons = null;
    }
    implConstructor = cons;
  }

  protected ComplexTypeSerializer() {
  }

  public static ComplexTypeSerializer create(String tableName,
      String columnName, Connection connection) {
    if (implConstructor != null) {
      try {
        return (ComplexTypeSerializer)implConstructor.newInstance(tableName,
            columnName, connection);
      } catch (InstantiationException | IllegalAccessException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      } catch (InvocationTargetException ite) {
        Throwable cause = ite.getCause();
        throw new IllegalArgumentException(cause.getMessage(), cause);
      }
    } else {
      throw new UnsupportedOperationException(
          "complex types are supported only with the SnappyData product.");
    }
  }

  /**
   * Serialize an ARRAY, MAP or STRUCT depending on type of object.
   * Acceptable object types are as follows (including inside nested
   * ARRAY, MAP, STRUCT inner types):
   * <ul>
   * <li><b>ARRAY: </b>Object[], typed arrays, java.util.Collection,
   *     scala.collection.Seq</li>
   * <li><b>MAP: </b>java.util.Map, scala.collection.Map</li>
   * <li><b>STRUCT: </b>Object[], typed arrays, java.util.Collection,
   *     scala.collection.Seq, scala.Product</li>
   * </ul>
   * <p>
   * The first object serialized with this instance will be validated for
   * correctness against the table schema. To force validate of all objects
   * serialized with this instance, use {@link #serialize(Object, boolean)}
   * with "validateAll" as true.
   *
   * @param v the object to be serialized
   */
  public byte[] serialize(Object v) {
    return serialize(v, false);
  }

  /**
   * Serialize an ARRAY, MAP or STRUCT depending on type of object.
   * Acceptable object types are as follows (including inside nested
   * ARRAY, MAP, STRUCT inner types):
   * <ul>
   * <li><b>ARRAY: </b>Object[], typed arrays, java.util.Collection,
   *     scala.collection.Seq</li>
   * <li><b>MAP: </b>java.util.Map, scala.collection.Map</li>
   * <li><b>STRUCT: </b>Object[], typed arrays, java.util.Collection,
   *     scala.collection.Seq, scala.Product</li>
   * </ul>
   *
   * @param v           the object to be serialized
   * @param validateAll if true, then all objects serialized will be validated
   *                    for match against table schema, else the first
   *                    object serialized will be validated
   */
  public abstract byte[] serialize(Object v, boolean validateAll);

  /**
   * Deserialize an ARRAY, MAP or STRUCT from given bytes to obtain a
   * list, map or collection respectively.
   */
  public Object deserialize(byte[] bytes) {
    return deserialize(bytes, 0, bytes.length);
  }

  /**
   * Deserialize an ARRAY, MAP or STRUCT from given bytes to obtain a
   * list, map or collection respectively.
   */
  public abstract Object deserialize(byte[] bytes, int offset, int length);

  /**
   * Deserialize an ARRAY, MAP or STRUCT from given blob to obtain a
   * list, map or collection respectively.
   */
  public Object deserialize(Blob blob) throws SQLException {
    int length = (int)blob.length();
    byte[] bytes = blob.getBytes(1L, length);
    return deserialize(bytes, 0, length);
  }
}
