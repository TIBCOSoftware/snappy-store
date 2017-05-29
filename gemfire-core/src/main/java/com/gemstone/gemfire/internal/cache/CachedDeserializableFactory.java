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

package com.gemstone.gemfire.internal.cache;

import java.io.IOException;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.NullDataOutputStream;
import com.gemstone.gemfire.internal.SharedLibrary;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.pdx.PdxInstance;

/**
 * Produces instances that implement CachedDeserializable.
 * @author Darrel
 * @since 5.0.2
 *
 */
public class CachedDeserializableFactory {

  private static final boolean PREFER_DESERIALIZED = !Boolean
      .getBoolean("gemfire.PREFER_SERIALIZED");

  private static final boolean PREFER_RAW_OBJECT = GemFireCacheImpl
      .gfxdSystem() || Boolean.getBoolean("gemfire.PREFER_RAW_OBJECT");

  private static final boolean STORE_ALL_VALUE_FORMS = Boolean
      .getBoolean("gemfire.STORE_ALL_VALUE_FORMS");

  /**
   * Returns true when the cache has been configured to always store the raw
   * objects and not wrapped in a CachedDeserializable. All calls to
   * CachedDeserializable constructors should first consult this setting to
   * check if it should be invoked or not.
   * 
   * This is currently always set for GemFireXD to avoid extra wrapping and
   * unneeded serialization/deserialization/size-calculation costs associated
   * with CachedDeserializable when using the byte[][] as such will be always
   * more efficient.
   */
  public static boolean preferObject() {
    return PREFER_RAW_OBJECT;
  }

  /**
   * Creates and returns an instance of CachedDeserializable that contains the
   * specified byte array.
   * 
   * Always check for {@link #preferObject()} before invoking this.
   */
  public static CachedDeserializable create(byte[] v) {
    Assert.assertTrue(!PREFER_RAW_OBJECT,
        "should not be invoked for gemfire.PREFER_RAW_OBJECT");
    return createNoCheck(v);
  }

  /**
   * Creates and returns an instance of CachedDeserializable that contains the
   * specified byte array.
   */
  static CachedDeserializable createNoCheck(final byte[] v) {
    if (STORE_ALL_VALUE_FORMS) {
      return new StoreAllCachedDeserializable(v);
    }
    else if (PREFER_DESERIALIZED) {
      if (isPdxEncoded(v) && cachePrefersPdx()) {
        return new PreferBytesCachedDeserializable(v);
      } else {
        return new VMCachedDeserializable(v);
      }
    } else {
      return new PreferBytesCachedDeserializable(v);
    }
  }

  private static boolean isPdxEncoded(byte[] v) {
    // assert v != null;
    if (v.length > 0) {
      return v[0] == DSCODE.PDX;
    }
    return false;
  }

  /**
   * Creates and returns an instance of CachedDeserializable that contains the
   * specified object (that is not a byte[]).
   * 
   * Always check for {@link #preferObject()} before invoking this.
   */
  public static CachedDeserializable create(Object object, int serializedSize) {
    Assert.assertTrue(!PREFER_RAW_OBJECT,
        "should not be invoked for gemfire.PREFER_RAW_OBJECT");
    if (STORE_ALL_VALUE_FORMS) {
      return new StoreAllCachedDeserializable(object);
    }
    else if (PREFER_DESERIALIZED) {
      if (object instanceof PdxInstance && cachePrefersPdx()) {
        return new PreferBytesCachedDeserializable(object);

      } else {
        return new VMCachedDeserializable(object, serializedSize);
      }
    } else {
      return new PreferBytesCachedDeserializable(object);
    }
  }

  private static boolean cachePrefersPdx() {
    GemFireCacheImpl gfc = GemFireCacheImpl.getInstance();
    if (gfc != null) {
      return gfc.getPdxReadSerialized();
    }
    return false;
  }

  /**
   * Wrap cd in a new CachedDeserializable.
   */
  public static CachedDeserializable create(CachedDeserializable cd) {
    Assert.assertTrue(!PREFER_RAW_OBJECT,
        "should not be invoked for PREFER_RAW_OBJECT");
    if (STORE_ALL_VALUE_FORMS) {
      // storeAll cds are immutable just return it w/o wrapping
      return cd;
    }
    else if (PREFER_DESERIALIZED) {
      if (cd instanceof PreferBytesCachedDeserializable) {
        return cd;
      } else {
        return new VMCachedDeserializable((VMCachedDeserializable) cd);
      }
    } else {
      // preferBytes cds are immutable so just return it w/o wrapping
      return cd;
    }
  }

  /**
   * Return the heap overhead in bytes for each CachedDeserializable instance.
   */
  public static int overhead() {
    // TODO: revisit this code. If we move to per-region cds then this can no longer be static.
    // TODO: This method also does not work well with the way off heap is determined using the cache.
    
    if (STORE_ALL_VALUE_FORMS) {
      return StoreAllCachedDeserializable.MEM_OVERHEAD;
    }
    else if (PREFER_RAW_OBJECT) {
      return 0;
    }
    else if (PREFER_DESERIALIZED) {
      // PDX: this may instead be PreferBytesCachedDeserializable.MEM_OVERHEAD
      return VMCachedDeserializable.MEM_OVERHEAD;
    }
    else {
      return PreferBytesCachedDeserializable.MEM_OVERHEAD;
    }
  }

  /**
   * Return the number of bytes the specified byte array will consume
   * of heap memory.
   */
  public static int getByteSize(byte[] serializedValue) {
    // add 4 for the length field of the byte[]
    return serializedValue.length + Sizeable.PER_OBJECT_OVERHEAD + 4;
  }

  public static int getArrayOfBytesSize(final byte[][] value,
      final boolean addObjectOverhead) {
    int result = 4 * (value.length + 1);
    if (addObjectOverhead) {
      result += Sizeable.PER_OBJECT_OVERHEAD * (value.length + 1);
    }
    for (byte[] bytes : value) {
      if (bytes != null) {
        result += bytes.length;
      }
    }
    return result;
  }

  /**
   * Return an estimate of the amount of heap memory used for the object.
   * If it is not a byte[] then account for CachedDeserializable overhead.
   * when it is wrapped by a CachedDeserializable.
   */
  public static int calcMemSize(Object o) {
    return calcMemSize(o, null, true);
  }
  public static int calcMemSize(Object o, ObjectSizer os, boolean addOverhead) {
    return calcMemSize(o, os, addOverhead, true);
  }
  /**
   * If not calcSerializedSize then return -1 if we can't figure out the mem size.
   */
  public static int calcMemSize(Object o, ObjectSizer os, boolean addOverhead, boolean calcSerializedSize) {
    int result;
    if (o instanceof byte[]) {
      // does not need to be wrapped so overhead never added
      result = getByteSize((byte[])o);
      addOverhead = false;
    } else if (o == null) {
      // does not need to be wrapped so overhead never added
      result = 0;
      addOverhead = false;
    } else if (o instanceof String) {
      result = (((String)o).length() * 2)
        + 4 // for the length of the char[]
        + (Sizeable.PER_OBJECT_OVERHEAD * 2) // for String obj and Char[] obj
        + 4 // for obj ref to char[] on String; note should be 8 on 64-bit vm
        + 4 // for offset int field on String
        + 4 // for count int field on String
        + 4 // for hash int field on String
        ;
    } else if (o instanceof byte[][]) {
      result = getArrayOfBytesSize((byte[][])o, true);
      addOverhead = false;
    } else if (o instanceof Long) {
      result = Sizeable.PER_OBJECT_OVERHEAD + 8;
      addOverhead = false;
    } else if (o instanceof Sizeable) {
      result = ((Sizeable)o).getSizeInBytes();
      if (!preferObject() && o instanceof CachedDeserializable) {
        // overhead not added for CachedDeserializable
        addOverhead = false;
      }
    } else if (o instanceof Integer) {
      result = Sizeable.PER_OBJECT_OVERHEAD + 4;
      addOverhead = false;
    } else if (os != null) {
      result = os.sizeof(o);
    } else if (calcSerializedSize) {
      result = Sizeable.PER_OBJECT_OVERHEAD + 4;
      NullDataOutputStream dos = new NullDataOutputStream();
      try {
        DataSerializer.writeObject(o, dos);
        result += dos.size();
      } catch (IOException ex) {
        RuntimeException ex2 = new IllegalArgumentException(LocalizedStrings.CachedDeserializableFactory_COULD_NOT_CALCULATE_SIZE_OF_OBJECT.toLocalizedString());
        ex2.initCause(ex);
        throw ex2;
      }
    } else {
      // return -1 to signal the caller that we did not compute the size
      result = -1;
      addOverhead = false;
    }
    if (addOverhead) {
      result += overhead();
    }
//     GemFireCache.getInstance().getLogger().info("DEBUG calcMemSize: o=<" + o + "> o.class=" + (o != null ? o.getClass() : "<null>") + " os=" + os + " result=" + result, new RuntimeException("STACK"));
    // alignment of 8 for 64-bit JVM and 4 for 32-bit
    if (SharedLibrary.is64Bit()) {
      if ((result & 0x7) != 0) {
        result = (result & 0xfffffff8) + 8;
      }
    } else {
      if ((result & 0x3) != 0) {
        result = (result & 0xfffffffc) + 4;
      }
    }
    return result;
  }
  /**
   * Return an estimate of the number of bytes this object will consume
   * when serialized. This is the number of bytes that will be written
   * on the wire including the 4 bytes needed to encode the length.
   */
  public static int calcSerializedSize(Object o) {
    int result;
    if (o instanceof byte[]) {
      result = getByteSize((byte[])o) - Sizeable.PER_OBJECT_OVERHEAD;
    } else if (o instanceof byte[][]) {
      result = getArrayOfBytesSize((byte[][])o, false);
    } else if (o instanceof Sizeable) {
      result = ((Sizeable)o).getSizeInBytes() + 4;
      if (!preferObject() && o instanceof CachedDeserializable) {
        // overhead not added for CachedDeserializable
        result -= overhead();
      }
    } else if (o instanceof HeapDataOutputStream) {
      result = ((HeapDataOutputStream)o).size() + 4;
    } else {
      result = 4;
      NullDataOutputStream dos = new NullDataOutputStream();
      try {
        DataSerializer.writeObject(o, dos);
        result += dos.size();
      } catch (IOException ex) {
        RuntimeException ex2 = new IllegalArgumentException(LocalizedStrings.CachedDeserializableFactory_COULD_NOT_CALCULATE_SIZE_OF_OBJECT.toLocalizedString());
        ex2.initCause(ex);
        throw ex2;
      }
    }
//     GemFireCache.getInstance().getLogger().info("DEBUG calcSerializedSize: o=<" + o + "> o.class=" + (o != null ? o.getClass() : "<null>") + " result=" + result, new RuntimeException("STACK"));
    return result;
  }
  /**
   * Return how much memory this object will consume
   * if it is in serialized form
   */
  public static int calcSerializedMemSize(Object o) {
    int result = calcSerializedSize(o);
    result += Sizeable.PER_OBJECT_OVERHEAD;
    if (!(o instanceof byte[])) {
      result += overhead();
    }
    return result;
  }
}
