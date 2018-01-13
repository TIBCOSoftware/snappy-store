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
package com.gemstone.gemfire.pdx.internal.unsafe;

import java.lang.reflect.Field;

import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;

import sun.misc.Unsafe;

/**
 * This class wraps the sun.misc.Unsafe class which is only available on Sun
 * JVMs. It is also available on other JVMs (like IBM).
 * 
 * @author darrel
 * 
 */
public final class UnsafeWrapper {

  private static final Unsafe unsafe = UnsafeHolder.getUnsafe();

  public static Unsafe getUnsafe() {
    return unsafe;
  }

  public long objectFieldOffset(Field f) {
    return unsafe.objectFieldOffset(f);
  }

  public final int getInt(long offset) {
    return unsafe.getInt(offset);
  }

  public final int getInt(Object o, long offset) {
    return unsafe.getInt(o, offset);
  }

  public final int getIntVolatile(Object o, long offset) {
    return unsafe.getIntVolatile(o, offset);
  }

  /**
   * Returns 4 if this is a 32bit jvm; otherwise 8. Note it does not account for
   * compressed oops.
   */
  public int getAddressSize() {
    return unsafe.addressSize();
  }

  public void putInt(Object o, long offset, int v) {
    unsafe.putInt(o, offset, v);
  }

  public void putIntVolatile(Object o, long offset, int v) {
    unsafe.putIntVolatile(o, offset, v);
  }

  public boolean compareAndSwapInt(Object o, long offset, int expected, int v) {
    return unsafe.compareAndSwapInt(o, offset, expected, v);
  }

  public boolean getBoolean(Object o, long offset) {
    return unsafe.getBoolean(o, offset);
  }

  public void putBoolean(Object o, long offset, boolean v) {
    unsafe.putBoolean(o, offset, v);
  }

  public final byte getByte(Object o, long offset) {
    return unsafe.getByte(o, offset);
  }

  public void putByte(Object o, long offset, byte v) {
    unsafe.putByte(o, offset, v);
  }

  public final short getShort(long offset) {
    return unsafe.getShort(offset);
  }

  public short getShort(Object o, long offset) {
    return unsafe.getShort(o, offset);
  }

  public void putShort(Object o, long offset, short v) {
    unsafe.putShort(o, offset, v);
  }

  public final char getChar(long offset) {
    return unsafe.getChar(offset);
  }

  public char getChar(Object o, long offset) {
    return unsafe.getChar(o, offset);
  }

  public void putChar(Object o, long offset, char v) {
    unsafe.putChar(o, offset, v);
  }

  public final long getLong(long offset) {
    return unsafe.getLong(offset);
  }

  public final long getLong(Object o, long offset) {
    return unsafe.getLong(o, offset);
  }

  public final long getLongVolatile(Object o, long offset) {
    return unsafe.getLongVolatile(o, offset);
  }

  public void putLong(Object o, long offset, long v) {
    unsafe.putLong(o, offset, v);
  }

  public void putLongVolatile(Object o, long offset, long v) {
    unsafe.putLongVolatile(o, offset, v);
  }

  public boolean compareAndSwapLong(Object o, long offset, long expected, long v) {
    return unsafe.compareAndSwapLong(o, offset, expected, v);
  }

  public final float getFloat(long offset) {
    return unsafe.getFloat(offset);
  }

  public float getFloat(Object o, long offset) {
    return unsafe.getFloat(o, offset);
  }

  public void putFloat(Object o, long offset, float v) {
    unsafe.putFloat(o, offset, v);
  }

  public final double getDouble(long offset) {
    return unsafe.getDouble(offset);
  }

  public double getDouble(Object o, long offset) {
    return unsafe.getDouble(o, offset);
  }

  public void putDouble(Object o, long offset, double v) {
    unsafe.putDouble(o, offset, v);
  }

  public Object getObject(Object o, long offset) {
    return unsafe.getObject(o, offset);
  }

  public void putObject(Object o, long offset, Object v) {
    unsafe.putObject(o, offset, v);
  }

  public Object allocateInstance(Class<?> c) throws InstantiationException {
    return unsafe.allocateInstance(c);
  }

  public long allocateMemory(long size) {
    return unsafe.allocateMemory(size);
  }

  public final byte getByte(long addr) {
    return unsafe.getByte(addr);
  }

  public void putByte(long addr, byte value) {
    unsafe.putByte(addr, value);
  }

  public void copyMemory(long src, long dst, long size) {
    unsafe.copyMemory(src, dst, size);
  }

  public void freeMemory(long addr) {
    unsafe.freeMemory(addr);
  }

  public int arrayBaseOffset(Class<?> c) {
    return unsafe.arrayBaseOffset(c);
  }

  public int arrayScaleIndex(Class<?> c) {
    return unsafe.arrayIndexScale(c);
  }

  public long fieldOffset(Field f) {
    return unsafe.objectFieldOffset(f);
  }

  public int getPageSize() {
    return unsafe.pageSize();
  }

  public void setMemory(long addr, long size, byte v) {
    unsafe.setMemory(addr, size, v);
  }
}
