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

import java.util.Properties;

import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.internal.cache.CachedDeserializableFactory;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

/**
 * 
 * @author kneeraj
 * 
 */
public final class GfxdObjectSizer implements ObjectSizer, Declarable {

  /**
   * @see ObjectSizer#sizeof(Object)
   */
  public int sizeof(Object o) {
    try {
      final Class<?> c;
      if (o instanceof DataValueDescriptor) {
        return ((DataValueDescriptor)o).getLengthInBytes(null)
            + ReflectionSingleObjectSizer.OBJECT_SIZE;
      }
      else if ((c = o.getClass()) == byte[][].class) {
        int size = 0;
        final byte[][] v = (byte[][])o;
        for (byte[] b : v) {
          if (b != null) {
            size += b.length;
            size += ReflectionSingleObjectSizer.OBJECT_SIZE;
          }
        }
        size += v.length * ReflectionSingleObjectSizer.REFERENCE_SIZE;
        size += ReflectionSingleObjectSizer.OBJECT_SIZE;
        return size;
      }
      else if (c == CompactCompositeRegionKey.class) {
        final CompactCompositeRegionKey ccrk = (CompactCompositeRegionKey)o;
        final byte[] kbytes = ccrk.getKeyBytes();
        int size = 0;
        if (kbytes != null) {
          size = kbytes.length;
        }
        size += ReflectionSingleObjectSizer.OBJECT_SIZE;
        return size;
      }
      else if (c == CompositeRegionKey.class) {
        final CompositeRegionKey crk = (CompositeRegionKey)o;
        DataValueDescriptor dvd;
        int size = 0;
        for (int index = 0; index < crk.nCols(); ++index) {
          dvd = crk.getKeyColumn(index);
          size += dvd.getLengthInBytes(null);
        }
        size += crk.nCols() * ReflectionSingleObjectSizer.REFERENCE_SIZE;
        size += ReflectionSingleObjectSizer.OBJECT_SIZE;
        return size;
      }
      else if (c == Long.class) {
        return (Long.SIZE / 8) + ReflectionSingleObjectSizer.OBJECT_SIZE;
      }
      else if (c == DataValueDescriptor[].class) {
        DataValueDescriptor[] dvdArr = (DataValueDescriptor[])o;
        int size = 0;
        for (DataValueDescriptor dvd : dvdArr) {
          size += dvd.getLengthInBytes(null);
          size += ReflectionSingleObjectSizer.OBJECT_SIZE;
        }
        size += dvdArr.length * ReflectionSingleObjectSizer.REFERENCE_SIZE;
        size += ReflectionSingleObjectSizer.OBJECT_SIZE;
        return size;
      }
      else if (c == byte[].class) {
        return ((byte[])o).length + ReflectionSingleObjectSizer.OBJECT_SIZE;
      }
      else if (Token.isInvalidOrRemoved(o)) {
        return 0;
      } else {
        return CachedDeserializableFactory.calcMemSize(o);
      }
    } catch (StandardException e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "GfxdObjectSizer failed", e);
    }
  }

  @Override
  public String toString() {
    return "GfxdObjectSizer";
  }

  @Override
  public void init(Properties props) {
    // nothing to be done
  }
}
