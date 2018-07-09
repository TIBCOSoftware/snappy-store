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

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.ReentrantReadWriteWriteShareLock;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.ServerResolverUtils;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRegionEntryUtils;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

/**
 * 
 * @author asifs
 *
 */
public class OffHeapByteCompareTest extends ByteCompareTest {
  private final int maxBatchSize;
  private final Map<Integer, ReentrantReadWriteWriteShareLock>map ;
  private OffHeapByteSource[] byteSources;
  public OffHeapByteCompareTest(String name) throws Exception{
    super(name);
    Class<?> clazz = OffHeapRegionEntryUtils.class;
    Field field = clazz.getDeclaredField("MAX_BATCH_SIZE");
    field.setAccessible(true);
    maxBatchSize = field.getInt(null);
     map = new HashMap<Integer, ReentrantReadWriteWriteShareLock>();
    
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(OffHeapByteCompareTest.class));
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();   
     System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."
        + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,
        "true");
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire."
        + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
  }

  public void _testBug() throws Exception {
   final int numObj = 500;
    for(int i = 0 ; i < numObj; ++i) {
      map.put(i, new ReentrantReadWriteWriteShareLock());
    }
    
    final int NUM_THREAD = 20;
    final CyclicBarrier barrier = new CyclicBarrier(NUM_THREAD);
    setupConnection();
    final LogWriter  logger = Misc.getDistributedSystem().getLogWriter();
    //final int LOOP_COUNT = 50;
    final int BATCH_SIZE = 20000;
    final int TOTAL_BYTE_SOURCES = 8000;
    byteSources = new OffHeapByteSource[BATCH_SIZE];
    Random rand = new Random();
    for(int i = 0 ; i < TOTAL_BYTE_SOURCES; ++i) {
      int num = -1;
      while(num == -1) {
        int temp = rand.nextInt(1000);
        if(temp > 10) {
          num = temp;
        }
      }
      String str = getMultiByteUTF_8String(num);
      byte[] data = str.getBytes("UTF-8");
      OffHeapByteSource bs = (OffHeapByteSource) OffHeapRegionEntryUtils
          .prepareValueForCreate(null, data, true);
      byteSources[i] = bs;

    }
    Runnable bulkPutAndRelease = new Runnable() {

      @Override
      public void run() {
        
        try {
          logger.info("waiting on barrier");
          barrier.await();
          logger.info("barrier broken");
           Random random = new Random();
           long startTime = System.currentTimeMillis();
           int loopCount =0;
           List<OffHeapByteSource> bss = new LinkedList<OffHeapByteSource>();
          
        while (true) {          
          char[] chars = new char[1000];
          for (int j = 0; j < BATCH_SIZE; ++j) {
           /* int num = -1;
            while(num == -1) {
              int temp = random.nextInt(1000);
              if(temp > 10) {
                num = temp;
              }
            }
            String str = getMultiByteUTF_8String(num);
            byte[] data = str.getBytes("UTF-8");
            OffHeapByteSource bs = (OffHeapByteSource)OffHeapRegionEntryUtils
                .prepareValueForCreate(null, data, true);
            logger.info("Written batch ="+j+" for loop count ="+loopCount);*/
            OffHeapByteSource bs = byteSources[random.nextInt(8000)];
            bs.retain();
            logger.info("Written batch ="+j+" for loop count ="+loopCount);
            bss.add(bs);
            
            ReentrantReadWriteWriteShareLock obj = map.get(j%numObj);
            assertTrue(obj.attemptLock(LockMode.SH, 5*60*1000, Thread.currentThread()));
          }
          logger.info("Written batch for loop count ="+loopCount);
          for (OffHeapByteSource bs : bss) {
            SQLChar.readIntoCharsForTesting(bs, 0, bs.getLength(), chars);
          }
          logger.info("read batch for loop count ="+loopCount);
          int k = 0;
          for (OffHeapByteSource bs : bss) {
            bs.release();
            map.get(k%numObj).releaseLock(LockMode.SH, false, Thread.currentThread());
            ++k;
          }
          logger.info("released batch for loop count ="+loopCount);
          
          bss.clear();
          if(loopCount % 100 == 0 ) {
            if(System.currentTimeMillis() - startTime > 60*60*1000) {
              break;
            }
          }
          ++loopCount;
        }
        } catch (Exception e) {

          e.printStackTrace();
        } 


      }

    };

    Thread[] ths = new Thread[NUM_THREAD];
    for (int i = 0; i < NUM_THREAD; ++i) {
      ths[i] = new Thread(bulkPutAndRelease);
    }
    for (Thread th : ths) {
      th.start();
    }

    for (Thread th : ths) {
      th.join();
    }
    for(int i =0 ; i < numObj;++i) {
      ReentrantReadWriteWriteShareLock lock = map.get(i);
      assertTrue(lock.attemptLock(LockMode.EX, 5*60*1000, Thread.currentThread()));
      lock.releaseLock(LockMode.EX,false, Thread.currentThread());
    }
    for(OffHeapByteSource bs:this.byteSources) {
      bs.release();
    }
  }

  public void testServerResolverUtilsHashCode() throws Exception {
    setupConnection();
    // controlled data
    byte[] data = new byte[10];
    for (int i = 0; i < data.length; ++i) {
      data[i] = (byte) (i % 0xff);
    }
    OffHeapRow bs = (OffHeapRow)OffHeapRegionEntryUtils
        .prepareValueForCreate(null, data, true);

    assertEquals(ResolverUtils.addBytesToHash(data, 0, data.length, 7),
        ServerResolverUtils.addBytesToHash(bs, 0, data.length, 7));
    OffHeapHelper.release(bs);

    // size less than max batch size
    data = new byte[maxBatchSize - 10];
    for (int i = 0; i < data.length; ++i) {
      data[i] = (byte) (i % 0xff);
    }
    bs = (OffHeapRow)OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);

    assertEquals(ResolverUtils.addBytesToHash(data, 5, data.length - 10, 7),
        ServerResolverUtils.addBytesToHash(bs, 5, data.length - 10, 7));
    OffHeapHelper.release(bs);

    // Size greater than basic batch size
    data = new byte[maxBatchSize * 5];
    for (int i = 0; i < data.length; ++i) {
      data[i] = (byte) (i % 0xff);
    }
    bs = (OffHeapRow)OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);

    assertEquals(ResolverUtils.addBytesToHash(data, 21, data.length - 103, 7),
        ServerResolverUtils.addBytesToHash(bs, 21, data.length - 103, 7));
    OffHeapHelper.release(bs);

    // Size equal to batch size
    data = new byte[maxBatchSize];
    for (int i = 0; i < data.length; ++i) {
      data[i] = (byte) (i % 0xff);
    }
    bs = (OffHeapRow)OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);

    assertEquals(ResolverUtils.addBytesToHash(data, 0, data.length, 7),
        ServerResolverUtils.addBytesToHash(bs, 0, data.length, 7));
    OffHeapHelper.release(bs);
    
    data = new byte[maxBatchSize];
    for (int i = 0; i < data.length; ++i) {
      data[i] = (byte) (i % 0xff);
    }
    bs = (OffHeapRow)OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);

    assertEquals(ResolverUtils.addBytesToHash(data, 0, data.length, 7),
        ServerResolverUtils.addBytesToHash(bs, 0, bs.getLength(), 7));
    OffHeapHelper.release(bs);
  }

  private static int compareString(OffHeapByteSource lhs,
      OffHeapByteSource rhs, int lhsOffset, int lhsColumnWidth, int rhsOffset,
      int rhsColumnWidth) {
    return SQLChar.compareString(UnsafeMemoryChunk.getUnsafeWrapper(),
        lhs.getUnsafeAddress(lhsOffset, lhsColumnWidth), lhsColumnWidth, lhs,
        rhs.getUnsafeAddress(rhsOffset, rhsColumnWidth), rhsColumnWidth, rhs);
  }

  private static int compareString(byte[] lhs, OffHeapByteSource rhs,
      int lhsOffset, int lhsColumnWidth, int rhsOffset, int rhsColumnWidth) {
    return SQLChar.compareString(UnsafeMemoryChunk.getUnsafeWrapper(), lhs,
        lhsOffset, lhsColumnWidth,
        rhs.getUnsafeAddress(rhsOffset, rhsColumnWidth), rhsColumnWidth, rhs);
  }

  private static int compareStringIgnoreCase(OffHeapByteSource lhs,
      OffHeapByteSource rhs, int lhsOffset, int lhsColumnWidth, int rhsOffset,
      int rhsColumnWidth) {
    return SQLChar.compareStringIgnoreCase(
        UnsafeMemoryChunk.getUnsafeWrapper(),
        lhs.getUnsafeAddress(lhsOffset, lhsColumnWidth), lhsColumnWidth, lhs,
        rhs.getUnsafeAddress(rhsOffset, rhsColumnWidth), rhsColumnWidth, rhs);
  }

  private static int compareStringIgnoreCase(byte[] lhs, OffHeapByteSource rhs,
      int lhsOffset, int lhsColumnWidth, int rhsOffset, int rhsColumnWidth) {
    return SQLChar.compareStringIgnoreCase(
        UnsafeMemoryChunk.getUnsafeWrapper(), lhs, lhsOffset, lhsColumnWidth,
        rhs.getUnsafeAddress(rhsOffset, rhsColumnWidth), rhsColumnWidth, rhs);
  }

  public void testSQLCharCompareString() throws Exception {
    setupConnection();
    // controlled data
    // test equality for size less than batch size
    byte[] data = new byte[this.maxBatchSize - 50];
    fillSingleByteUTF_8(data);
    OffHeapByteSource bs = (OffHeapByteSource)OffHeapRegionEntryUtils
        .prepareValueForCreate(null, data, true);

    assertEquals(0, compareString(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0, compareString(bs, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0,
        compareString(data, bs, 5, data.length - 5, 5, bs.getLength() - 5));

    bs.release();

    data = new byte[2 * this.maxBatchSize + 87];
    fillSingleByteUTF_8(data);
    bs = (OffHeapByteSource)OffHeapRegionEntryUtils.prepareValueForCreate(null,
        data, true);

    assertEquals(0, compareString(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0, compareString(bs, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0,
        compareString(data, bs, 5, data.length - 5, 5, bs.getLength() - 5));

    bs.release();

    // Test multibyte string equality
    String str = getMultiByteUTF_8String(this.maxBatchSize * 3 + 103);
    data = str.getBytes("UTF-8");
    bs = (OffHeapByteSource)OffHeapRegionEntryUtils.prepareValueForCreate(null,
        data, true);

    assertEquals(0, compareString(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0, compareString(bs, bs, 0, data.length, 0, bs.getLength()));

    bs.release();

    // Test inequality

    data = new byte[2 * this.maxBatchSize + 87];
    fillSingleByteUTF_8(data);
    String b = new String(data);
    b += "abcdefgh";
    byte[] bBytes = b.getBytes("UTF-8");
    bs = (OffHeapByteSource)OffHeapRegionEntryUtils.prepareValueForCreate(null,
        data, true);

    assertEquals(0, compareString(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0, compareString(bs, bs, 0, data.length, 0, bs.getLength()));
    assertTrue(SQLChar.compareString(bBytes, 0, bBytes.length, data, 0,
        data.length) > 0);
    assertTrue(compareString(bBytes, bs, 0, bBytes.length, 0, bs.getLength()) > 0);

    bs.release();
  }

  public void testSQLCharCompareStringIgnoreCase() throws Exception {
    setupConnection();
    // controlled data
    // test equality for size less than batch size
    byte[] data = new byte[this.maxBatchSize - 50];

    fillSingleByteUTF_8(data);
    String str = new String(data);
    data = str.toLowerCase().getBytes("UTF-8");

    OffHeapByteSource bs = (OffHeapByteSource)OffHeapRegionEntryUtils
        .prepareValueForCreate(null, data, true);
    data = str.toUpperCase().getBytes("UTF-8");

    assertEquals(0,
        compareStringIgnoreCase(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0,
        compareStringIgnoreCase(bs, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(
        0,
        compareStringIgnoreCase(data, bs, 5, data.length - 5, 5,
            bs.getLength() - 5));

    bs.release();

    data = new byte[2 * this.maxBatchSize + 87];
    fillSingleByteUTF_8(data);
    str = new String(data);
    data = str.toLowerCase().getBytes("UTF-8");

    bs = (OffHeapByteSource)OffHeapRegionEntryUtils.prepareValueForCreate(null,
        data, true);

    data = str.toUpperCase().getBytes("UTF-8");

    assertEquals(0,
        compareStringIgnoreCase(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0,
        compareStringIgnoreCase(bs, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(
        0,
        compareStringIgnoreCase(data, bs, 5, data.length - 5, 5,
            bs.getLength() - 5));

    bs.release();

    // Test multibyte string equality
    str = getMultiByteUTF_8String(this.maxBatchSize * 3 + 103);
    data = str.getBytes("UTF-8");
    bs = (OffHeapByteSource)OffHeapRegionEntryUtils.prepareValueForCreate(null,
        data, true);

    assertEquals(0,
        compareStringIgnoreCase(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0,
        compareStringIgnoreCase(bs, bs, 0, data.length, 0, bs.getLength()));

    bs.release();

    // Test inequality

    data = new byte[2 * this.maxBatchSize + 87];
    fillSingleByteUTF_8(data);
    String b = new String(data);
    b += "abcdefgh";
    byte[] bBytes = b.getBytes("UTF-8");
    bs = (OffHeapByteSource)OffHeapRegionEntryUtils.prepareValueForCreate(null,
        data, true);

    assertEquals(0,
        compareStringIgnoreCase(data, bs, 0, data.length, 0, bs.getLength()));
    assertEquals(0,
        compareStringIgnoreCase(bs, bs, 0, data.length, 0, bs.getLength()));
    assertTrue(compareStringIgnoreCase(bBytes, bs, 0, bBytes.length, 0,
        bs.getLength()) > 0);
    assertTrue(SQLChar.compareStringIgnoreCase(bBytes, 0, bBytes.length, data,
        0, data.length) > 0);

    bs.release();
  }

  public void testSQLCharReadIntoCharsFromByteSource()
      throws Exception {
    setupConnection();
    // controlled data single byte
    // exceed a single batch
    byte[] data = new byte[maxBatchSize * 2 + 53];
    fillSingleByteUTF_8(data);
    String controlData = new String(data, "utf-8");
    OffHeapByteSource bs = (OffHeapByteSource) OffHeapRegionEntryUtils
        .prepareValueForCreate(null, data, true);
    char[] chars = new char[data.length];
    SQLChar.readIntoCharsForTesting(bs, 0, data.length, chars);
    String controlOutput = new String(chars);
    assertEquals(controlData, controlOutput);
    OffHeapHelper.release(bs);

    // less than a single batch
    data = new byte[maxBatchSize - 53];
    fillSingleByteUTF_8(data);
    controlData = new String(data, "utf-8");
    bs = (OffHeapByteSource) OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);
    chars = new char[data.length];
    SQLChar.readIntoCharsForTesting(bs, 0, data.length, chars);
    controlOutput = new String(chars);
    assertEquals(controlData, controlOutput);
    OffHeapHelper.release(bs);

    // Equal to single batch
    data = new byte[maxBatchSize];
    fillSingleByteUTF_8(data);
    controlData = new String(data, "utf-8");
    bs = (OffHeapByteSource) OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);
    chars = new char[data.length];
    SQLChar.readIntoCharsForTesting(bs, 0, data.length, chars);
    controlOutput = new String(chars);
    assertEquals(controlData, controlOutput);
    OffHeapHelper.release(bs);
    
    String multiByteString = getMultiByteUTF_8String(maxBatchSize*2 + 76);
    data = multiByteString.getBytes("UTF-8");
    bs = (OffHeapByteSource) OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);
    chars = new char[data.length];
    int n = SQLChar.readIntoCharsForTesting(bs, 0, data.length, chars);
    controlOutput = new String(chars, 0, n);
    assertEquals(multiByteString, controlOutput);
    OffHeapHelper.release(bs);    
  }
 
  public void testSQLCharReadIntoCharsFromByteSourceSampleUTF_8()
      throws Exception {
    setupConnection();
    String sample = "Russian :На берегу пустынных волн"
        + "Стоял он, дум великих полн,"
        + "И вдаль глядел. Пред ним широко"
        + "Река неслася; бедный чёлн"
        + "По ней стремился одиноко."
        + "По мшистым, топким берегам"
        + "Чернели избы здесь и там,"
        + "Приют убогого чухонца;"
        + "И лес, неведомый лучам"
        + "В тумане спрятанного солнца,"
        + "Кругом шумел."
        + "Tamil"
        + "யாமறிந்த மொழிகளிலே தமிழ்மொழி போல் இனிதாவது எங்கும் காணோம்,"
        + "பாமரராய் விலங்குகளாய், உலகனைத்தும் இகழ்ச்சிசொலப் பான்மை கெட்டு,"
        + "நாமமது தமிழரெனக் கொண்டு இங்கு வாழ்ந்திடுதல் நன்றோ? சொல்லீர்!தேமதுரத் தமிழோசை உலகமெலாம் பரவும்வகை செய்தல் வேண்டும்."
        + "Kanada:" + "ಬಾ ಇಲ್ಲಿ ಸಂಭವಿಸು ಇಂದೆನ್ನ ಹೃದಯದಲಿ "
        + "ನಿತ್ಯವೂ ಅವತರಿಪ ಸತ್ಯಾವತಾರ" + "ಮಣ್ಣಾಗಿ ಮರವಾಗಿ ಮಿಗವಾಗಿ ಕಗವಾಗೀ..."
        + "ಮಣ್ಣಾಗಿ ಮರವಾಗಿ ಮಿಗವಾಗಿ ಕಗವಾಗಿ" + "ಭವ ಭವದಿ ಭತಿಸಿಹೇ ಭವತಿ ದೂರ"
        + "ನಿತ್ಯವೂ ಅವತರಿಪ ಸತ್ಯಾವತಾರ || ಬಾ ಇಲ್ಲಿ ||";

    byte[] utf_8_bytes = sample.getBytes("UTF-8");
    char[] chars = new char[utf_8_bytes.length];
    System.out.println("length=" + utf_8_bytes.length);
    OffHeapByteSource bs = (OffHeapByteSource) OffHeapRegionEntryUtils
        .prepareValueForCreate(null, utf_8_bytes, true);
    int numCharRead = SQLChar.readIntoCharsForTesting(bs, 0, utf_8_bytes.length, chars);
    String controlOutput = new String(chars, 0, numCharRead);
    assertEquals(sample, controlOutput);
    OffHeapHelper.release(bs);
  }
  
  public void testOffHeapByteSourceSerialization() throws Exception {
    setupConnection();
    // controlled data single byte
    // exceed a single batch
    byte[] data = new byte[maxBatchSize * 2 + 53];
    fillSingleByteUTF_8(data);
    OffHeapByteSource bs = (OffHeapByteSource) OffHeapRegionEntryUtils
        .prepareValueForCreate(null, data, true);
    ByteArrayOutputStream controlBaos = new ByteArrayOutputStream();
    DataOutput controlDao = new DataOutputStream(controlBaos);
    InternalDataSerializer.writeObject(data, controlDao);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutput dao = new DataOutputStream(baos);
    bs.sendTo(dao);
    baos.flush();
    byte[] outputBytes = baos.toByteArray();
    assertTrue(Arrays.equals(controlBaos.toByteArray(), outputBytes));
    OffHeapHelper.release(bs);

    // data equal to batch size
    data = new byte[maxBatchSize * 2];
    fillSingleByteUTF_8(data);
    bs = (OffHeapByteSource) OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);
    controlBaos = new ByteArrayOutputStream();
    controlDao = new DataOutputStream(controlBaos);
    InternalDataSerializer.writeObject(data, controlDao);
    baos = new ByteArrayOutputStream();
    dao = new DataOutputStream(baos);
    bs.sendTo(dao);
    baos.flush();
    outputBytes = baos.toByteArray();
    assertTrue(Arrays.equals(controlBaos.toByteArray(), outputBytes));
    OffHeapHelper.release(bs);

    // data less than batch size
    data = new byte[maxBatchSize - 50];
    fillSingleByteUTF_8(data);
    bs = (OffHeapByteSource) OffHeapRegionEntryUtils.prepareValueForCreate(
        null, data, true);
    controlBaos = new ByteArrayOutputStream();
    controlDao = new DataOutputStream(controlBaos);
    InternalDataSerializer.writeObject(data, controlDao);
    baos = new ByteArrayOutputStream();
    dao = new DataOutputStream(baos);
    bs.sendTo(dao);
    baos.flush();
    outputBytes = baos.toByteArray();
    assertTrue(Arrays.equals(controlBaos.toByteArray(), outputBytes));
    OffHeapHelper.release(bs);
  }

  public void testOffHeapByteSourceReadLong() throws Exception {
    setupConnection();
    // controlled data single byte
    // exceed a single batch
    ByteArrayOutputStream controlBaos = new ByteArrayOutputStream();
    DataOutput controlDao = new DataOutputStream(controlBaos);
    controlDao.writeLong(Long.MAX_VALUE);
    controlDao.writeLong(Long.MIN_VALUE);
    controlDao.writeLong(Byte.MAX_VALUE);
    controlDao.writeLong(Byte.MIN_VALUE);
    controlDao.writeLong(Short.MAX_VALUE);
    controlDao.writeLong(Short.MIN_VALUE);
    controlDao.writeLong(Integer.MAX_VALUE);
    controlDao.writeLong(Integer.MIN_VALUE);
    controlBaos.flush();
    byte[] data = controlBaos.toByteArray();

    OffHeapByteSource bs = (OffHeapByteSource) OffHeapRegionEntryUtils
        .prepareValueForCreate(null, data, true);
    DataInput result = bs.getDataInputStreamWrapper(0, bs.getLength());
    assertEquals(Long.MAX_VALUE, result.readLong());
    assertEquals(Long.MIN_VALUE, result.readLong());
    assertEquals(Byte.MAX_VALUE, result.readLong());
    assertEquals(Byte.MIN_VALUE, result.readLong());
    assertEquals(Short.MAX_VALUE, result.readLong());
    assertEquals(Short.MIN_VALUE, result.readLong());
    assertEquals(Integer.MAX_VALUE, result.readLong());
    assertEquals(Integer.MIN_VALUE, result.readLong());
    OffHeapHelper.release(bs);
  }
  
  private static void fillSingleByteUTF_8(byte[] data) {
    byte startChar = 0x00;
    byte endChar = 0x7F;
    byte charCount = startChar;
    for (int i = 0; i < data.length; ++i) {
      data[i] = charCount;
      if (charCount == endChar) {
        charCount = startChar;
      } else {
        ++charCount;
      }
    }
  }
  
  private static String getMultiByteUTF_8String(int numCodePoints) {
    StringBuilder sb = new StringBuilder();
    byte startSingleByteChar = 0x00;
    byte endSingleChar = 0x7F;
    byte singleCharCount = startSingleByteChar;

    int startDoubleByteChar = 0x0080;
    int endDoubleByteChar =0x07FF;
    int doubleCharCount = startDoubleByteChar;
   
    int startTripleByteChar =0x0800 ;
    int endTripleByteChar =0xFFFF;
    int tripleCharCount = startTripleByteChar;
   
    
    for(int i =0 ; i < numCodePoints; ) {
      sb.appendCodePoint(singleCharCount);
      ++i;
      if (singleCharCount ==endSingleChar ) {
        singleCharCount = startSingleByteChar;
      } else {
        ++singleCharCount;
      }
      if(i == numCodePoints) {
        break;
      }
      
      sb.appendCodePoint(doubleCharCount);
      ++i;
      if (doubleCharCount ==endDoubleByteChar ) {
        doubleCharCount = startDoubleByteChar;
      } else {
        ++doubleCharCount;
      }
      
      if(i == numCodePoints) {
        break;
      }
      
      sb.appendCodePoint(tripleCharCount);
      ++i;
      if (tripleCharCount ==endTripleByteChar ) {
        tripleCharCount = startTripleByteChar;
      } else {
        ++tripleCharCount;
      }
      
    }
    return sb.toString();
  }
  
  @Override
  protected String getOffHeapSuffix() {
    return " offheap ";
  }

}
