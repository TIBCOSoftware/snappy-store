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

package com.pivotal.gemfirexd.internal.engine.store;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;

import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.cache.KeyWithRegionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ValueRow;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * An instance of this class is used as the key in the Region when a primary key
 * is defined in create table or explicitly created through alter table command.
 * 
 * [sumedh] Note if this is made non-final and derived classes added, then
 * change the code in {@link GfxdObjectSizer#sizeof(Object)} to use instanceof
 * check instead of class matching.
 * 
 * @author rdubey
 */
public final class CompositeRegionKey implements Serializable, RegionKey {

  private static final long serialVersionUID = -5397414534166595916L;

  /** Array containing the primary key for the table. */
  private DataValueDescriptor[] primaryKey;

  /** Used for DataSerializer only */
  public CompositeRegionKey() {
  }

  /**
   * Allocates a new GemFireKey encapsulating a DvD array.
   * 
   * @param key
   *          a dvd array.
   */
  public CompositeRegionKey(DataValueDescriptor[] key) {

    if (key == null) {
      throw new IllegalArgumentException("key should never be null.");
    }
    this.primaryKey = key;
  }

  public void setPrimaryKey(DataValueDescriptor[] key) {
    if (key == null) {
      throw new IllegalArgumentException("key should never be null.");
    }
    this.primaryKey = key;
  }

  @Override
  public final void setRegionContext(final LocalRegion region) {
    // invoke for each key
    for (int index = 0; index < this.primaryKey.length; ++index) {
      this.primaryKey[index].setRegionContext(region);
    }
  }

  @Override
  public final KeyWithRegionContext beforeSerializationWithValue(
      boolean valueIsToken) {
    // no changes for serialization for DVDs
    return this;
  }

  @Override
  public final void afterDeserializationWithValue(Object val) {
    // no changes for serialization for DVDs
  }

  @Override
  public final int nCols() {
    return this.primaryKey.length;
  }

  @Override
  public DataValueDescriptor getKeyColumn(int index) {
    return this.primaryKey[index];
  }

  @Override
  public void getKeyColumns(final DataValueDescriptor[] keys) {
    try {
      for (int index = 0; index < keys.length; ++index) {
        if (keys[index] == null) {
          keys[index] = this.primaryKey[index];
        }
        else {
          keys[index].setValue(this.primaryKey[index]);
        }
      }
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "CRK.getKeyColumns: unexpected exception", se);
    }
  }

  @Override
  public void getKeyColumns(final Object[] keys) throws StandardException {
    for (int index = 0; index < keys.length; ++index) {
      keys[index] = this.primaryKey[index].getObject();
    }
  }

  /**
   * Returns the hash for this GemFireKey.
   * 
   * @return an integer as the hash code for this object.
   */
  @Override
  public final int hashCode() {
    return ResolverUtils.isUsingGFXD1302Hashing()
        ? hashCodeFromDVDs(this.primaryKey)
        : hashCodeFromDVDsPre1302(this.primaryKey);
  }

  public static int hashCodeFromDVDs(DataValueDescriptor[] val) {
    int hash = 0;
    DataValueDescriptor dvd;
    for (int index = 0; index < val.length; ++index) {
      dvd = val[index];
      /**
       * soubhik: Changing to underlying object's hashCode as done in the
       * resolvers. This avoids potential issue and benefits performance when
       * Map.get() is called during Hash1IndexScanController#initEnumeration()
       * {... getEntryLocally() };
       * 
       */
      if (dvd != null) {
        hash = ResolverUtils.addIntToHash(dvd.hashCode(), hash);
      }
      else {
        hash = ResolverUtils.addIntToHash(0, hash);
      }
    }
    return hash;
  }

  /**
   * This is the hashCode from pre GFXD 1.3.0.2 versions that caused huge number
   * of clashes in some cases of multiple integer columns etc (#51381)
   */
  public static int hashCodeFromDVDsPre1302(DataValueDescriptor[] val) {
    int hash = 0;
    DataValueDescriptor dvd;
    for (int index = 0; index < val.length; ++index) {
      dvd = val[index];
      /**
       * soubhik: Changing to underlying object's hashCode as done in the
       * resolvers. This avoids potential issue and benefits performance when
       * Map.get() is called during Hash1IndexScanController#initEnumeration()
       * {... getEntryLocally() };
       * 
       */
      if (dvd != null) {
        hash ^= dvd.hashCode();
      }
    }
    return hash;
  }

  /**
   * Equals implementation for this class. Compares this GemFireKey to specified
   * argument.
   * 
   * @return true if the argument is not null and argument's dvd array contains
   *         the same data value.
   */
  @Override
  public final boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (other instanceof CompositeRegionKey) {
      return dvdArrayEquals(this.primaryKey,
          ((CompositeRegionKey)other).primaryKey);
    }
    return false;
  }

  public static boolean dvdArrayEquals(DataValueDescriptor[] array1,
      DataValueDescriptor[] array2) {
    assert array1 != null: "DVD[] should never be null";
    assert array2 != null: "DVD[] should never be null";

    if (array1.length != array2.length) {
      return false;
    }
    try {
      for (int index = 0; index < array1.length; ++index) {
        final DataValueDescriptor dvd1 = array1[index];
        final DataValueDescriptor dvd2 = array2[index];
        if (dvd1 != null) {
          if (dvd2 == null || dvd2.compare(dvd1) != 0) {
            return false;
          }
        }
        else if (dvd2 != null) {
          return false;
        }
      }
    } catch (StandardException se) {
      return false;
    }
    return true;
  }

  public long estimateMemoryUsage() {
    return ValueRow.estimateDVDArraySize(this.primaryKey)
        /* primary key reference*/
        + ReflectionSingleObjectSizer.OBJECT_SIZE
        + ReflectionSingleObjectSizer.REFERENCE_SIZE;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("gemfire CRkey: ");
    if (this.primaryKey != null) {
      for (int i = 0; i < this.primaryKey.length; i++) {
        sb.append(" element [").append(i).append("]: ").append(
            this.primaryKey[i].toString());
      }
    }
    else {
      sb.append("is null");
    }
    return sb.toString();
  }

  // DataSerializableFixedID methods

  public final int getDSFID() {
    return DataSerializableFixedID.GFXD_GEMFIRE_KEY;
  }

  public final void toData(final DataOutput out) throws IOException {
    DataType.writeDVDArray(this.primaryKey, out);
  }

  public final void fromData(final DataInput in) throws IOException,
      ClassNotFoundException {
    this.primaryKey = DataType.readDVDArray(in);
  }

  @Override
  public Version[] getSerializationVersions() {
    return null;
  }
}
