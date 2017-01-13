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

package com.gemstone.gemfire.internal.shared.unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import com.gemstone.gemfire.internal.shared.ChannelBufferFramedInputStream;
import com.gemstone.gemfire.internal.shared.ChannelBufferFramedOutputStream;
import com.gemstone.gemfire.internal.shared.ChannelBufferInputStream;
import com.gemstone.gemfire.internal.shared.ChannelBufferOutputStream;
import com.gemstone.gemfire.internal.shared.InputStreamChannel;
import com.gemstone.gemfire.internal.shared.OutputStreamChannel;

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

    static {
      sun.misc.Unsafe v;
      // try using "theUnsafe" field
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        v = (sun.misc.Unsafe)field.get(null);
      } catch (LinkageError le) {
        throw le;
      } catch (Throwable t) {
        throw new ExceptionInInitializerError(t);
      }
      if (v == null) {
        throw new ExceptionInInitializerError("theUnsafe not found");
      }
      unsafe = v;
    }

    static void init() {
    }
  }

  private static final boolean hasUnsafe;
  // Cached array base offset
  public static final long arrayBaseOffset;

  static {
    boolean v;
    long arrayOffset = -1;
    try {
      Wrapper.init();
      // try to access arrayBaseOffset via unsafe
      arrayOffset = (long)Wrapper.unsafe.arrayBaseOffset(byte[].class);
      v = true;
    } catch (LinkageError le) {
      le.printStackTrace();
      v = false;
    }
    hasUnsafe = v;
    arrayBaseOffset = arrayOffset;
  }

  private UnsafeHolder() {
    // no instance
  }

  public static boolean hasUnsafe() {
    return hasUnsafe;
  }

  public static long getDirectByteBufferAddress(Buffer buffer) {
    return ((sun.nio.ch.DirectBuffer)buffer).address();
  }

  public static void releaseDirectByteBuffer(Buffer buffer) {
    sun.misc.Cleaner cleaner = ((sun.nio.ch.DirectBuffer)buffer).cleaner();
    if (cleaner != null) {
      cleaner.clean();
    }
  }

  public static sun.misc.Unsafe getUnsafe() {
    return Wrapper.unsafe;
  }

  @SuppressWarnings("resource")
  public static InputStreamChannel newChannelBufferInputStream(
      ReadableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeInputStream(channel, bufferSize)
        : new ChannelBufferInputStream(channel, bufferSize));
  }

  @SuppressWarnings("resource")
  public static OutputStreamChannel newChannelBufferOutputStream(
      WritableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeOutputStream(channel, bufferSize)
        : new ChannelBufferOutputStream(channel, bufferSize));
  }

  @SuppressWarnings("resource")
  public static InputStreamChannel newChannelBufferFramedInputStream(
      ReadableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeFramedInputStream(channel, bufferSize)
        : new ChannelBufferFramedInputStream(channel, bufferSize));
  }

  @SuppressWarnings("resource")
  public static OutputStreamChannel newChannelBufferFramedOutputStream(
      WritableByteChannel channel, int bufferSize) throws IOException {
    return (hasUnsafe
        ? new ChannelBufferUnsafeFramedOutputStream(channel, bufferSize)
        : new ChannelBufferFramedOutputStream(channel, bufferSize));
  }

  public static boolean checkBounds(int off, int len, int size) {
    return ((off | len | (off + len) | (size - (off + len))) >= 0);
  }
}
