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
package com.pivotal.gemfirexd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;

import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.offheap.OffHeapMemoryStats;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.pivotal.gemfirexd.internal.engine.store.offheap.CollectionBasedOHAddressCache;
import junit.framework.TestCase;

/**
 * Unit test for the OHAddressCache interface.
 * Each implementation of that interface should extend this class
 * with a junit test.
 * 
 * @author darrel
 *
 */
public abstract class CollectionBasedOHAddressCacheTestBase extends TestCase {

  protected abstract CollectionBasedOHAddressCache createOHAddressCacheInstance();
  
  private GemFireCacheImpl createCache() {
    Properties props = new Properties();
    props.setProperty("locators", "");
    props.setProperty("mcast-port", "0");
    props.setProperty("off-heap-memory-size", "500m");
    GemFireCacheImpl current = GemFireCacheImpl.getInstance();
    if (current != null) {
      current.close();
    }
    GemFireCacheImpl result = (GemFireCacheImpl) new CacheFactory(props).create();
    return result;
  }
  private void closeCache(GemFireCacheImpl gfc) {
    gfc.close();
  }
  
  private SimpleMemoryAllocatorImpl getAllocator() {
    return SimpleMemoryAllocatorImpl.getAllocator();
  }
  private OffHeapMemoryStats getStats() {
    return getAllocator().getStats();
  }
  
  private long allocateSmallObject() {
    Chunk mc = (Chunk) getAllocator().allocate(8, null);
    return mc.getMemoryAddress();
  }
  
  public void testEmptyCache() {
    CollectionBasedOHAddressCache c = createOHAddressCacheInstance();
    try {
      c.releaseByteSource(0);
      fail("expected IndexOutOfBoundsException");
    } catch (IndexOutOfBoundsException expected) {
    }
    assertEquals(0, c.testHook_getSize());
    assertEquals(Collections.emptyList(), c.testHook_copyToList());
    c.release();
  }
  
  public void testSingleAddRelease() {
    final GemFireCacheImpl gfc = createCache();
    try {
      final int startOHObjects = getStats().getObjects();
      final CollectionBasedOHAddressCache c = createOHAddressCacheInstance();
      try {
        assertEquals(startOHObjects, getStats().getObjects());
        long addr = allocateSmallObject();
        assertEquals(startOHObjects+1, getStats().getObjects());
        c.put(addr);
        assertEquals(1, c.testHook_getSize());
        ArrayList<Long> expected = new ArrayList<Long>(2);
        expected.add(0, addr);
        long addr2 = allocateSmallObject();
        c.put(addr2);
        assertEquals(2, c.testHook_getSize());
        expected.add(0, addr2);
        assertEquals(expected, c.testHook_copyToList());
      } finally {
        c.release();
      }
      assertEquals(0, c.testHook_getSize());
      assertEquals(startOHObjects, getStats().getObjects());
    } finally {
      closeCache(gfc);
      SimpleMemoryAllocatorImpl.freeOffHeapMemory();
    }
  }
  public void testBulkAddRelease() {
    final GemFireCacheImpl gfc = createCache();
    final int BULK_SIZE = 16*1024;
    try {
      final int startOHObjects = getStats().getObjects();
      final CollectionBasedOHAddressCache c = createOHAddressCacheInstance();
      try {
        assertEquals(startOHObjects, getStats().getObjects());
        for (int i=0; i < BULK_SIZE; i++) {
          c.put(allocateSmallObject());
          assertEquals(i+1, c.testHook_getSize());
        }
      } finally {
        c.release();
      }
      assertEquals(0, c.testHook_getSize());
      assertEquals(startOHObjects, getStats().getObjects());
    } finally {
      closeCache(gfc);
      SimpleMemoryAllocatorImpl.freeOffHeapMemory();
    }
  }
  
  public void testReleaseIdx0() {
    final GemFireCacheImpl gfc = createCache();
    final int BULK_SIZE = 16*1024;
    try {
      final int startOHObjects = getStats().getObjects();
      final CollectionBasedOHAddressCache c = createOHAddressCacheInstance();
      try {
        assertEquals(startOHObjects, getStats().getObjects());
        ArrayList<Long> expected = new ArrayList<Long>(BULK_SIZE);
        for (int i=0; i < BULK_SIZE; i++) {
          long addr = allocateSmallObject();
          c.put(addr);
          expected.add(0, addr);
          assertEquals(i+1, c.testHook_getSize());
          assertEquals(expected, c.testHook_copyToList());
        }
        for (int i=0; i < BULK_SIZE; i++) {
          c.releaseByteSource(0);
          expected.remove(0);
          assertEquals(BULK_SIZE-1-i, c.testHook_getSize());
          assertEquals(expected, c.testHook_copyToList());
        }
        assertEquals(startOHObjects, getStats().getObjects());
      } finally {
        c.release();
      }
    } finally {
      closeCache(gfc);
      SimpleMemoryAllocatorImpl.freeOffHeapMemory();
    }
  }
  
  public void testReleaseIdxEnd() {
    final GemFireCacheImpl gfc = createCache();
    final int BULK_SIZE = 16*1024;
    try {
      final int startOHObjects = getStats().getObjects();
      final CollectionBasedOHAddressCache c = createOHAddressCacheInstance();
      try {
        ArrayList<Long> expected = new ArrayList<Long>(BULK_SIZE);
        assertEquals(startOHObjects, getStats().getObjects());
        for (int i=0; i < BULK_SIZE; i++) {
          long addr = allocateSmallObject();
          c.put(addr);
          expected.add(0, addr);
          assertEquals(i+1, c.testHook_getSize());
        }
        for (int i=BULK_SIZE-1; i >= 0; i--) {
          c.releaseByteSource(i);
          expected.remove(i);
          assertEquals(i, c.testHook_getSize());
          assertEquals(expected, c.testHook_copyToList());
        }
        assertEquals(startOHObjects, getStats().getObjects());
      } finally {
        c.release();
      }
    } finally {
      closeCache(gfc);
      SimpleMemoryAllocatorImpl.freeOffHeapMemory();
    }
  }

  public void testReleaseIdxRandom() {
    final GemFireCacheImpl gfc = createCache();
    final int BULK_SIZE = 16*1024;
    try {
      final int startOHObjects = getStats().getObjects();
      final CollectionBasedOHAddressCache c = createOHAddressCacheInstance();
      try {
        ArrayList<Long> expected = new ArrayList<Long>(BULK_SIZE);
        assertEquals(startOHObjects, getStats().getObjects());
        for (int i=0; i < BULK_SIZE; i++) {
          long addr = allocateSmallObject();
          c.put(addr);
          expected.add(0, addr);
          assertEquals(i+1, c.testHook_getSize());
        }
        final Random rand = new Random();
        for (int i=BULK_SIZE-1; i >= 0; i--) {
          final int releaseIdx = rand.nextInt(i+1);
          c.releaseByteSource(releaseIdx);
          expected.remove(releaseIdx);
          assertEquals(i, c.testHook_getSize());
          assertEquals(expected, c.testHook_copyToList());
        }
        assertEquals(startOHObjects, getStats().getObjects());
      } finally {
        c.release();
      }
    } finally {
      closeCache(gfc);
      SimpleMemoryAllocatorImpl.freeOffHeapMemory();
    }
  }

  
}
