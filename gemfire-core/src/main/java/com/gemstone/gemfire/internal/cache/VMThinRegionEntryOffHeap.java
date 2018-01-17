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

/**
 * Do not modify this class. It was generated.
 * Instead modify LeafRegionEntry.cpp and then run
 * bin/generateRegionEntryClasses.sh from the directory
 * that contains your build.xml.
 */
package com.gemstone.gemfire.internal.cache;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.concurrent.AtomicUpdaterFactory;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap.HashEntry;
@SuppressWarnings("serial")
public class VMThinRegionEntryOffHeap extends VMThinRegionEntry
    implements OffHeapRegionEntry
{
  public VMThinRegionEntryOffHeap (RegionEntryContext context, Object key,
    @Retained
    Object value
      ) {
    super(context,
          value
        );
    this.key = key;
  }
  protected int hash;
  private HashEntry<Object, Object> next;
  @SuppressWarnings("unused")
  private volatile long lastModified;
  private static final AtomicLongFieldUpdater<VMThinRegionEntryOffHeap> lastModifiedUpdater
    = AtomicUpdaterFactory.newLongFieldUpdater(VMThinRegionEntryOffHeap.class, "lastModified");
  protected long getlastModifiedField() {
    return lastModifiedUpdater.get(this);
  }
  protected final boolean compareAndSetLastModifiedField(long expectedValue,
      long newValue) {
    return lastModifiedUpdater.compareAndSet(this, expectedValue, newValue);
  }
  @Override
  public final int getEntryHash() {
    return this.hash;
  }
  @Override
  protected final void setEntryHash(int v) {
    this.hash = v;
  }
  @Override
  public final HashEntry<Object, Object> getNextEntry() {
    return this.next;
  }
  @Override
  public final void setNextEntry(final HashEntry<Object, Object> n) {
    this.next = n;
  }
  private Object key;
  @Override
  public final Object getRawKey() {
    return this.key;
  }
  @Override
  protected final void _setRawKey(Object key) {
    this.key = key;
  }
  @Retained @Released private volatile long ohAddress;
  private final static AtomicLongFieldUpdater<VMThinRegionEntryOffHeap> ohAddrUpdater =
      AtomicUpdaterFactory.newLongFieldUpdater(VMThinRegionEntryOffHeap.class, "ohAddress");
  @Override
  public final boolean isOffHeap() {
    return true;
  }
  @Override
  public final Token getValueAsToken() {
    return OffHeapRegionEntryHelper.getValueAsToken(this);
  }
  @Override
  @Unretained
  protected final Object getValueField() {
    return OffHeapRegionEntryHelper._getValue(this);
  }
  @Override
  protected final void setValueField(@Unretained Object v) {
    OffHeapRegionEntryHelper.setValue(this, v);
  }
  @Override
  @Retained
  public final Object _getValueRetain(RegionEntryContext context,
      boolean decompress) {
    return OffHeapRegionEntryHelper._getValueRetain(this, decompress);
  }
  @Override
  public final long getAddress() {
    return ohAddrUpdater.get(this);
  }
  @Override
  public final boolean setAddress(long expectedAddr, long newAddr) {
    return ohAddrUpdater.compareAndSet(this, expectedAddr, newAddr);
  }
  @Override
  @Released
  public final void release() {
    OffHeapRegionEntryHelper.releaseEntry(this);
  }
  private static RegionEntryFactory factory = new RegionEntryFactory() {
    public final RegionEntry createEntry(RegionEntryContext context, Object key, Object value) {
      return new VMThinRegionEntryOffHeap(context, key, value);
    }
    public final Class<?> getEntryClass() {
      return VMThinRegionEntryOffHeap.class;
    }
    public RegionEntryFactory makeVersioned() {
      return VMThinRegionEntryOffHeap.getEntryFactory();
    }
    @Override
    public RegionEntryFactory makeOnHeap() {
      return VMThinRegionEntryHeap.getEntryFactory();
    }
  };
  public static RegionEntryFactory getEntryFactory() {
    return factory;
  }
}
