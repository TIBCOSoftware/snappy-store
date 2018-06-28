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

package com.gemstone.gemfire.internal.util;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Comparator;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholdListener;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.FinalizeHolder;
import com.gemstone.gemfire.internal.shared.FinalizeObject;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataInputStream;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataOutputStream;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gnu.trove.TObjectHashingStrategy;

/**
 * An extension to {@link ArraySortedCollection} to enable overflowing sorted arrays to
 * disk when memory pressure increases.
 * 
 * Use {@link #create} static method to get an instance.
 * 
 * @author swale
 * @since gfxd 1.1
 */
public class ArraySortedCollectionWithOverflow extends
    ArraySortedCollection implements Closeable {

  public static boolean TRACE_TEMP_FILE_IO = SystemProperties
      .getServerInstance().getBoolean("TraceTempFileIO", false);

  /** minimum chunk size to overflow */
  protected static final int MEMORY_CHUNK_SIZE_FOR_OVERFLOW = (8 * 1024 * 1024);

  /**
   * Input buffer size for overflow data. There will be multiple of them one for
   * each overflow segment, so don't make this too large.
   */
  protected static final int INBUF_SIZE = 8 * 1024;
  /** Buffer size used then too many elements have overflowed to disk */
  protected static final int SMALL_INBUF_SIZE = 1024;
  /**
   * max number of {@link #INBUF_SIZE} buffers before switching to
   * {@link #SMALL_INBUF_SIZE}
   */
  protected static final int MAX_LARGE_BUFFERS = 1024;

  /** check the {@link MemoryThresholdListener} after these many "add" calls */
  protected int MEMORY_CHECK_INTERVAL = 4;

  protected final ObjectSizer sizer;
  protected final InternalResourceManager resourceManager;
  protected final MemoryThresholdListener memListener;
  protected final File overflowDir;
  protected File overflowFile;
  protected transient FileChannel overflowOutputChannel;
  protected transient ChannelBufferUnsafeDataOutputStream overflowOutputStream;
  protected transient FileInputStream overflowInputStream;
  protected int lastMemoryCheck;
  protected int estimatedObjectSize;
  protected long lastMemoryDelta;
  protected int numOverflowedBlocks;
  protected long numOverflowed;
  protected FinalizeOverflow finalizer;

  protected ArraySortedCollectionWithOverflow(
      final Comparator<Object> comparator,
      final SortDuplicateObserver duplicateObserver,
      final TObjectHashingStrategy hashingStrategy, final ObjectSizer sizer,
      final int maxUnsortedArraySize, final long maxSortLimit,
      final MemoryThresholdListener mtl, String overflowDir,
      final GemFireCacheImpl cache) {
    super(comparator, duplicateObserver, hashingStrategy, maxUnsortedArraySize,
        maxSortLimit);
    this.sizer = sizer;
    this.resourceManager = cache.getResourceManager(false);
    this.memListener = mtl;
    this.overflowDir = new File(overflowDir);
    this.lastMemoryCheck = MEMORY_CHECK_INTERVAL;
    this.lastMemoryDelta = 0;
  }

  public static ArraySortedCollectionWithOverflow create(
      final Comparator<Object> comparator,
      final SortDuplicateObserver duplicateObserver,
      final TObjectHashingStrategy hashingStrategy, final ObjectSizer sizer,
      final int maxUnsortedArraySize, final int maxSortedLimit,
      final MemoryThresholdListener mtl, String overflowDir,
      final GemFireCacheImpl cache) {
    ArraySortedCollectionWithOverflow sortedSet =
        new ArraySortedCollectionWithOverflow(
            comparator, duplicateObserver, hashingStrategy, sizer,
            maxUnsortedArraySize, maxSortedLimit, mtl, overflowDir, cache);
    sortedSet.finalizer = new FinalizeOverflow(sortedSet);
    return sortedSet;
  }

  @Override
  public final boolean add(final Object v) {
    if (this.lastMemoryCheck > 0) {
      if (super.add(v)) {
        this.lastMemoryCheck--;
        return true;
      }
      else {
        return false;
      }
    }
    else {
      this.estimatedObjectSize = this.sizer.sizeof(v);
      this.lastMemoryDelta += (this.estimatedObjectSize *
          MEMORY_CHECK_INTERVAL);
      // update MEMORY_CHECK_INVERVAL with the new size
      this.lastMemoryCheck = MEMORY_CHECK_INTERVAL = Math.min(8,
          ((MEMORY_CHUNK_SIZE_FOR_OVERFLOW >> 2) / this.estimatedObjectSize));
      if (this.lastMemoryDelta > MEMORY_CHUNK_SIZE_FOR_OVERFLOW) {
        if (doOverflow(true)) {
          this.lastMemoryDelta = 0;
        }
      }
      return super.add(v);
    }
  }

  @Override
  protected boolean createNewArray(final boolean allowMemExpansion) {
    final boolean newArrayCreated = super.createNewArray(allowMemExpansion);
    // check for overflow when creating new array
    if (newArrayCreated) {
      if (this.lastMemoryCheck < MEMORY_CHECK_INTERVAL) {
        doOverflow(false);
      }
      return true;
    }
    else {
      return false;
    }
  }

  /**
   * Overflow the in-memory elements as long as required/possible. Returns true
   * if elements were overflowed to disk and false otherwise.
   */
  protected boolean doOverflow(final boolean checkLast) {
    final MemoryThresholdListener memListener = this.memListener;
    if (memListener.isEviction() || memListener.isCritical()) {
      final InternalResourceManager resourceManager = this.resourceManager;
      final boolean hasOffHeap = (resourceManager.getGemFireCache()
          .getOffHeapStore() != null);
      boolean didOverflow = false;
      // overflow the arrays as much as required to disk
      int arrayIndex = this.currentArrayIndex;
      if (arrayIndex > 0) {
        while (--arrayIndex >= 0
            && (memListener.isEviction() || memListener.isCritical())) {
          Object elementArray = this.elements[arrayIndex];
          // overflow in-memory arrays
          if (elementArray instanceof Object[]) {
            Object[] elements = (Object[])elementArray;
            this.elements[arrayIndex] = overflowElementArray(elements);
            didOverflow = true;

            // force update resource manager after overflowing the array
            resourceManager.getHeapMonitor().updateStateAndSendEvent();
            if (hasOffHeap) {
              resourceManager.getOffHeapMonitor().updateStateAndSendEvent();
            }
          }
        }
      }
      // also overflow the last array if we are still in eviction/critical
      if (checkLast && (memListener.isEviction() || memListener.isCritical())) {
        if (super.createNewArray(false)) {
          arrayIndex = this.currentArrayIndex - 1;
          Object elementArray = this.elements[arrayIndex];
          // overflow in-memory array
          if (elementArray instanceof Object[]) {
            Object[] elements = (Object[])elementArray;
            this.elements[arrayIndex] = overflowElementArray(elements);
            didOverflow = true;

            // force update resource manager after overflowing the array
            resourceManager.getHeapMonitor().updateStateAndSendEvent();
            if (hasOffHeap) {
              resourceManager.getOffHeapMonitor().updateStateAndSendEvent();
            }
          }
        }
      }
      return didOverflow;
    }
    return false;
  }

  protected OverflowData overflowElementArray(final Object[] elements) {
    this.resourceManager.getCancelCriterion().checkCancelInProgress(null);

    File overflowFile = this.overflowFile;
    if (overflowFile == null) {

      // check that overflowDir should be writable
      final File overflowDir = this.overflowDir;
      if (!overflowDir.isDirectory()) {
        throw new IllegalArgumentException("no such directory "
            + overflowDir.getPath());
      }
      else if (!overflowDir.canWrite()) {
        throw new IllegalStateException("directory "
            + overflowDir.getAbsolutePath() + " not writable");
      }

      int maxExceptionsTolerance = 100;
      while (true) {
        overflowFile = new File(overflowDir, "sortOverflow-"
            + IOUtils.newNonSecureRandomUUID().toString());
        try {
          if (overflowFile.createNewFile()) {
            break;
          }
        } catch (IOException ioe) {
          if (maxExceptionsTolerance-- <= 0) {
            // fail with exception
            handleIOException(ioe, this.resourceManager);
          }
        }
      }
      this.overflowFile = overflowFile;
      final FinalizeOverflow finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.overflowFile = overflowFile;
      }
    }

    try {
      ChannelBufferUnsafeDataOutputStream dos = this.overflowOutputStream;
      FileChannel fchannel = this.overflowOutputChannel;
      if (dos == null) {
        @SuppressWarnings("resource")
        final FileOutputStream outStream = new FileOutputStream(
            this.overflowFile, true);
        this.overflowOutputChannel = fchannel = outStream.getChannel();
        this.overflowOutputStream = dos =
            new ChannelBufferUnsafeDataOutputStream(fchannel);
        final FinalizeOverflow finalizer = this.finalizer;
        if (finalizer != null) {
          finalizer.dos = dos;
        }
      }
      final long startPos = fchannel.size();

      // number of elements need not be written since that is held by
      // OverflowData itself
      final int numElements = writeElements(elements, dos);
      dos.flush();

      final int bufSize;
      if (this.numOverflowedBlocks++ < MAX_LARGE_BUFFERS) {
        bufSize = INBUF_SIZE;
      }
      else {
        bufSize = SMALL_INBUF_SIZE;
      }
      this.numOverflowed += numElements;
      final long endPos = fchannel.size();
      FileInputStream fis = this.overflowInputStream;
      if (fis == null) {
        fis = this.overflowInputStream = new FileInputStream(this.overflowFile);
        final FinalizeOverflow finalizer = this.finalizer;
        if (finalizer != null) {
          finalizer.fis = fis;
        }
      }
      if (TRACE_TEMP_FILE_IO) {
        final LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();
        if (logger != null) {
          logger.info(LocalizedStrings.DEBUG, "ArraySorter: overflowing "
              + numElements + " objects with startPos=" + startPos + " endPos="
              + endPos + " to temp file: " + overflowFile.getAbsolutePath());
        }
      }
      return new OverflowData(fis, numElements, startPos, endPos, bufSize);
    } catch (IOException ioe) {
      handleIOException(ioe, this.resourceManager);
      throw new AssertionError("did not expect to reach", ioe);
    }
  }

  protected int writeElements(final Object[] elements,
      final ChannelBufferUnsafeDataOutputStream dos) throws IOException {
    int numElements = 0;
    for (Object element : elements) {
      // last array may have empty elements at the end
      if (element != null) {
        DataSerializer.writeObject(element, dos);
        numElements++;
      } else {
        break;
      }
    }
    return numElements;
  }

  protected Object readElement(final ChannelBufferUnsafeDataInputStream in)
      throws ClassNotFoundException, IOException {
    return DataSerializer.readObject(in);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Enumerator enumerator() {
    try {
      clearOutputStream();
    } catch (IOException ioe) {
      handleIOException(ioe, this.resourceManager);
    }
    return super.enumerator();
  }

  @Override
  protected Enumerator createIteratorForElementArray(int index) {
    final Object elementArray = this.elements[index];
    if (elementArray instanceof Object[]) {
      return super.createIteratorForElementArray(index);
    }
    else {
      return new OverflowDataItr((OverflowData)elementArray);
    }
  }

  @Override
  public final long longSize() {
    final Object[] elements = this.elements;
    long sz = 0;
    for (int i = 0; i < this.currentArrayIndex; i++) {
      final Object elementArray = elements[i];
      if (elementArray instanceof Object[]) {
        sz += ((Object[])elementArray).length;
      }
      else {
        sz += ((OverflowData)elementArray).size();
      }
    }
    return (sz + this.currentArrayPos);
  }

  public final int numOverflowedElements() {
    return this.numOverflowed < Integer.MAX_VALUE ? (int)this.numOverflowed
        : Integer.MAX_VALUE;
  }

  @Override
  public void clear() {
    super.clear();
    try {
      clearOutputStream();
    } catch (IOException ioe) {
      // ignore
    }

    final FileInputStream fis = this.overflowInputStream;
    if (fis != null) {
      try {
        fis.close();
      } catch (IOException ioe) {
        // ignore
      }
      this.overflowInputStream = null;
      final FinalizeOverflow finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.fis = null;
      }
    }

    final File overflowFile = this.overflowFile;
    if (overflowFile != null) {
      if (overflowFile.exists() && !overflowFile.delete()) {
        overflowFile.deleteOnExit();
      }
      this.overflowFile = null;
      final FinalizeOverflow finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.overflowFile = null;
      }
    }

    this.numOverflowedBlocks = 0;
    this.numOverflowed = 0;
  }

  protected final void clearOutputStream() throws IOException {
    final ChannelBufferUnsafeDataOutputStream dos = this.overflowOutputStream;
    if (dos != null) {
      dos.close();
      this.overflowOutputStream = null;
      final FileChannel fchannel = this.overflowOutputChannel;
      if (fchannel != null) {
        fchannel.close();
        this.overflowOutputChannel = null;
      }
      final FinalizeOverflow finalizer = this.finalizer;
      if (finalizer != null) {
        finalizer.dos = null;
      }
    }
  }

  @Override
  public void close() {
    final FinalizeOverflow finalizer = this.finalizer;
    if (finalizer != null) {
      finalizer.clearAll();
      this.finalizer = null;
    }
    clear();
  }

  static void handleIOException(Exception e,
      InternalResourceManager resourceManager) throws GemFireIOException {
    if (resourceManager == null) {
      resourceManager = GemFireCacheImpl.getExisting().getResourceManager();
    }
    resourceManager.getCancelCriterion().checkCancelInProgress(e);
    if (e != null) {
      throw new GemFireIOException(e.getMessage(), e);
    }
  }

  /**
   * Encapsulates a sorted array of elements that have been overflowed to disk.
   * 
   * @author swale
   * @since gfxd 1.1
   */
  public static final class OverflowData extends
      ChannelBufferUnsafeDataInputStream {

    // used for sizing of DirectByteBuffer
    private static final ByteBuffer TMP_BUF = ByteBuffer.allocateDirect(1);

    public static final long DIRECT_BUFFER_OVERHEAD =
        ReflectionSingleObjectSizer.sizeof(TMP_BUF.getClass());

    public static final long BASE_CLASS_OVERHEAD = ReflectionSingleObjectSizer
        .sizeof(OverflowData.class) + DIRECT_BUFFER_OVERHEAD;

    long currentPos;
    final long endPos;
    final int size;

    OverflowData(final FileInputStream fis, final int size,
        final long startPos, final long endPos, final int bufSize)
        throws IOException {
      super(fis.getChannel(), bufSize);
      this.currentPos = startPos;
      this.endPos = endPos;
      this.size = size;
    }

    @Override
    protected final int readIntoBuffer(final ByteBuffer buffer)
        throws IOException {
      // check the position and read only till endPos
      final FileChannel fchannel = (FileChannel)this.channel;
      // reposition the channel if it is being resued by others
      final long pos = fchannel.position();
      if (pos != this.currentPos) {
        fchannel.position(this.currentPos);
      }
      final long remaining = this.endPos - this.currentPos;
      if (remaining > 0) {
        final int bufRemaining = buffer.remaining();
        if (remaining < bufRemaining) {
          buffer.limit(buffer.limit() - (int)(bufRemaining - remaining));
        }
        final int readBytes = fchannel.read(buffer);
        if (readBytes > 0) {
          this.currentPos += readBytes;
        }
        return readBytes;
      }
      else {
        return -1;
      }
    }

    @Override
    protected final int readIntoBufferNoWait(final ByteBuffer buffer)
        throws IOException {
      return readIntoBuffer(buffer);
    }

    public final int size() {
      return this.size;
    }

    public final int bufferSize() {
      return this.buffer.capacity();
    }
  }

  /**
   * An iterator for elements overflowed to disk ({@link OverflowData}).
   */
  final class OverflowDataItr implements Enumerator {

    private final OverflowData data;
    private int size;

    protected OverflowDataItr(OverflowData overflowData) {
      this.data = overflowData;
      this.size = overflowData.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object nextElement() {
      if (this.size-- > 0) {
        try {
          return readElement(this.data);
        } catch (ClassNotFoundException | IOException e) {
          handleIOException(e, null);
        }
      }
      return null;
    }
  }

  @SuppressWarnings("serial")
  static final class FinalizeOverflow extends FinalizeObject {

    ChannelBufferUnsafeDataOutputStream dos;
    FileInputStream fis;
    File overflowFile;

    protected FinalizeOverflow(ArraySortedCollectionWithOverflow overflowSet) {
      super(overflowSet, true);
      this.overflowFile = overflowSet.overflowFile;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean doFinalize() {
      final ChannelBufferUnsafeDataOutputStream dos = this.dos;
      if (dos != null) {
        try {
          dos.close();
        } catch (IOException ioe) {
          // ignore
        }
        try {
          dos.getUnderlyingChannel().close();
        } catch (IOException ioe) {
          // ignore
        }
      }
      final FileInputStream fis = this.fis;
      if (fis != null) {
        try {
          fis.close();
        } catch (IOException ioe) {
          // ignore
        }
      }
      final File overflowFile = this.overflowFile;
      if (overflowFile != null && overflowFile.exists()) {
        if (!overflowFile.delete()) {
          overflowFile.deleteOnExit();
        }
      }
      return true;
    }

    @Override
    public final FinalizeHolder getHolder() {
      return getServerHolder();
    }

    @Override
    protected void clearThis() {
      this.dos = null;
      this.fis = null;
      this.overflowFile = null;
    }
  }
}
