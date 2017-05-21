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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.shared.unsafe;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.internal.shared.ChannelBufferFramedInputStream;
import com.gemstone.gemfire.internal.shared.ChannelBufferFramedOutputStream;
import com.gemstone.gemfire.internal.shared.ChannelBufferInputStream;
import com.gemstone.gemfire.internal.shared.ChannelBufferOutputStream;
import com.gemstone.gemfire.internal.shared.InputStreamChannel;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;
import org.apache.spark.unsafe.Platform;

/**
 * Holder for static sun.misc.Unsafe instance and some convenience methods. Use
 * other methods only if {@link UnsafeHolder#hasUnsafe()} returns true;
 *
 * @author swale
 * @since gfxd 1.1
 */
public abstract class UnsafeHolder {

  private static final class Wrapper {

    static final sun.misc.Unsafe unsafe;
    static final Constructor<?> directBufferConstructor;
    static final Field cleanerRunnableField;

    static {
      sun.misc.Unsafe v;
      Constructor<?> dbConstructor;
      Field runnableField = null;
      try {
        // try using "theUnsafe" field
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        v = (sun.misc.Unsafe)field.get(null);

        // get the constructor of DirectByteBuffer that accepts a Runnable
        Class<?> cls = Class.forName("java.nio.DirectByteBuffer");
        dbConstructor = cls.getDeclaredConstructor(
            Integer.TYPE, Long.TYPE, FileDescriptor.class, Runnable.class);
        dbConstructor.setAccessible(true);

        // search for the Runnable field in Cleaner
        Class<?> runnableClass = Runnable.class;
        Field[] fields = sun.misc.Cleaner.class.getDeclaredFields();
        for (Field f : fields) {
          if (runnableClass.isAssignableFrom(f.getType())) {
            if (runnableField == null || f.getName().contains("thunk")) {
              f.setAccessible(true);
              runnableField = f;
            }
          }
        }

      } catch (LinkageError le) {
        throw le;
      } catch (Throwable t) {
        throw new ExceptionInInitializerError(t);
      }
      if (v == null) {
        throw new ExceptionInInitializerError("theUnsafe not found");
      }
      unsafe = v;
      directBufferConstructor = dbConstructor;
      cleanerRunnableField = runnableField;
    }

    static void init() {
    }
  }

  private static final boolean hasUnsafe;

  static {
    boolean v;
    try {
      Wrapper.init();
      v = true;
    } catch (LinkageError le) {
      le.printStackTrace();
      v = false;
    }
    hasUnsafe = v;
  }

  private UnsafeHolder() {
    // no instance
  }

  public static boolean hasUnsafe() {
    return hasUnsafe;
  }

  @SuppressWarnings("serial")
  static final class FreeMemory extends AtomicLong implements Runnable {

    FreeMemory(long address) {
      super(address);
    }

    long tryFree() {
      // try hard to ensure freeMemory call happens only and only once
      final long address = get();
      return (address != 0 && compareAndSet(address, 0)) ? address : 0L;
    }

    @Override
    public void run() {
      final long address = tryFree();
      if (address != 0) {
        Platform.freeMemory(address);
      }
    }
  }

  private static int getAllocationSize(int size) {
    // round to word size
    size = ((size + 7) >>> 3) << 3;
    if (size > 0) return size;
    else throw new BufferOverflowException();
  }

  public static ByteBuffer allocateDirectBuffer(int size) {
    final int allocSize = getAllocationSize(size);
    final ByteBuffer buffer = allocateDirectBuffer(
        Platform.allocateMemory(allocSize), allocSize);
    buffer.limit(size);
    return buffer;
  }

  private static ByteBuffer allocateDirectBuffer(long address, int size) {
    try {
      return (ByteBuffer)Wrapper.directBufferConstructor.newInstance(
          size, address, null, new FreeMemory(address));
    } catch (Exception e) {
      Platform.throwException(e);
      throw new IllegalStateException("unreachable");
    }
  }

  public static long getDirectBufferAddress(ByteBuffer buffer) {
    return ((sun.nio.ch.DirectBuffer)buffer).address();
  }

  public static ByteBuffer reallocateDirectBuffer(ByteBuffer buffer,
      int newSize) {
    sun.nio.ch.DirectBuffer directBuffer = (sun.nio.ch.DirectBuffer)buffer;
    final long address = directBuffer.address();
    long newAddress = 0L;

    newSize = getAllocationSize(newSize);
    final sun.misc.Cleaner cleaner = directBuffer.cleaner();
    final Field runnableField = Wrapper.cleanerRunnableField;
    if (cleaner != null && runnableField != null) {
      // reset the runnable to not free the memory and clean it up
      try {
        Object freeMemory = runnableField.get(cleaner);
        // use the efficient realloc call if possible
        if ((freeMemory instanceof FreeMemory) &&
            ((FreeMemory)freeMemory).tryFree() != 0L) {
          newAddress = getUnsafe().reallocateMemory(address, newSize);
        }
      } catch (IllegalAccessException e) {
        // fallback to full copy
      }
    }
    if (newAddress == 0L) {
      newAddress = Platform.allocateMemory(newSize);
      Platform.copyMemory(null, address, null, newAddress,
          Math.min(newSize, buffer.limit()));
    }
    // clean only after copying is done if required
    if (cleaner != null) {
      cleaner.clean();
      cleaner.clear();
    }
    return allocateDirectBuffer(newAddress, newSize).order(buffer.order());
  }

  public static void releaseIfDirectBuffer(ByteBuffer buffer) {
    if (buffer != null && buffer.isDirect()) {
      releaseDirectBuffer(buffer);
    }
  }

  public static void releaseDirectBuffer(ByteBuffer buffer) {
    sun.misc.Cleaner cleaner = ((sun.nio.ch.DirectBuffer)buffer).cleaner();
    if (cleaner != null) {
      cleaner.clean();
      cleaner.clear();
    }
    releasePendingReferences();
  }

  public static void releasePendingReferences() {
    final sun.misc.JavaLangRefAccess refAccess =
        sun.misc.SharedSecrets.getJavaLangRefAccess();
    // retry while helping enqueue pending Cleaner Reference objects
    // noinspection StatementWithEmptyBody
    while (refAccess.tryHandlePendingReference()) ;
  }

  public static sun.misc.Unsafe getUnsafe() {
    return Wrapper.unsafe;
  }

  @SuppressWarnings("resource")
  public static InputStreamChannel newChannelBufferInputStream(
      ReadableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeInputStream(channel, bufferSize, false)
        : new ChannelBufferInputStream(channel, bufferSize));
  }

  @SuppressWarnings("resource")
  public static OutputStreamChannel newChannelBufferOutputStream(
      WritableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeOutputStream(channel, bufferSize, false)
        : new ChannelBufferOutputStream(channel, bufferSize));
  }

  @SuppressWarnings("resource")
  public static InputStreamChannel newChannelBufferFramedInputStream(
      ReadableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeFramedInputStream(channel, bufferSize, false)
        : new ChannelBufferFramedInputStream(channel, bufferSize));
  }

  @SuppressWarnings("resource")
  public static OutputStreamChannel newChannelBufferFramedOutputStream(
      WritableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeFramedOutputStream(channel, bufferSize, false)
        : new ChannelBufferFramedOutputStream(channel, bufferSize));
  }

  /**
   * Checks that the range described by {@code offset} and {@code size}
   * doesn't exceed {@code arrayLength}.
   */
  public static void checkBounds(int arrayLength, int offset, int len) {
    if ((offset | len) < 0 || offset > arrayLength ||
        arrayLength - offset < len) {
      throw new ArrayIndexOutOfBoundsException("Array index out of range: " +
          "length=" + arrayLength + " offset=" + offset + " length=" + len);
    }
  }
}
