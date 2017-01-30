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
package com.pivotal.gemfirexd.internal.engine.raw.store;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.store.raw.RawStoreFactory;
import com.pivotal.gemfirexd.internal.iapi.store.raw.StreamContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.io.DirFile;

/**
 * <P>
 * This is primarily responsible to handle file i/o during various type of
 * intermediate results.
 * </P>
 * <P>
 * Much of the code is from StreamFileContainer accept row read/write to disk. <br>
 * This reads/writes to a directByteBuffer without serializing. ByteBuffer
 * should be adequately sized up.
 * </P>
 * <P>
 * This implements Object I/O interface so that it can be used with
 * {@link java.io.Externalizable Externalizable} instead of heap I/O.
 * </P>
 * <P>
 * Only reason is we want to use primitive type methods instead of us taking
 * over and writing 1 byte at a time for all primitives. That will involve too
 * many back & forth movement across kernel memory & loose out on performance.
 * </P>
 * <P>
 * This class doesn't supports InternalDataSerializer.writeObject & readObject
 * because it doesn't implements InputStream/OutputStream.
 * InternalDataSerializer wraps this into a dumb InputStream and readFully (more
 * precisely <code> read(byte[], off, len))</code> interface is not implemented
 * properly.
 * </P>
 * 
 * @author soubhikc
 * 
 */
public final class FileStreamInputOutput implements StreamContainerHandle,
    ObjectOutput, ObjectInput {

  private final DirFile file;

  private final FileChannel channel;

  private final int capacity;

  private RowSource rowSource;

  private final ExecRow templateRow;

  /**
   * this is a read/write buffer allocated only once in MergeSort and used
   * across multiple instances of this class.
   * 
   * concurrent r/w is not supported as mergeRuns will happen sequentially via
   * all instances of this class sharing the rwBuffer.
   * 
   */
  private ByteBuffer rwBuffer;

  private long numRows;

  private final boolean isDvdArray;

  private final boolean isArray;

  // diagnostics data
  // ----------------
  private final ArrayList<String> rowObjectsWritten;

  private int rowObjectsindex = 0;

  private final boolean diagnoseFileData = SanityManager
      .DEBUG_ON("DiagnoseTempFileIO");

  // statistics data
  // ----------------

  private int totalBytesWritten;

  private int rowsWritten;

  private int totalBytesRead;

  private int rowsRead;

  // end of statistics data
  // -----------------------

  public FileStreamInputOutput(final long conglomId,
      final GemFireTransaction txn, final RowSource rowSource,
      final ByteBuffer rwBuffer) throws StandardException {

    final FabricDatabase db = Misc.getMemStore().getDatabase();

    this.file = db.getTempDir().createTemporaryFile(conglomId);
    this.channel = this.file.getChannel();
    this.capacity = rwBuffer.capacity();
    this.rwBuffer = rwBuffer;
    this.rwBuffer.clear();

    this.rowSource = rowSource;

    if (GemFireXDUtils.TraceTempFileIO) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
          "Created temporary file : " + this.file);
    }

    final ExecRow row = rowSource.getNextRowFromRowSource();
    assert (row != null): "atleast one row is expected... ";

    this.templateRow = row.getNewNullRow();

    final Object o = row.getRawRowValue(false);

    final Class<?> cls = o.getClass();
    if (cls == byte[].class) {
      isArray = true;
      isDvdArray = false;
      rowObjectsWritten = null;
      writeBuffer((byte[])o);
    }
    else if (cls == byte[][].class) {
      isArray = false;
      isDvdArray = false;
      rowObjectsWritten = null;
      writeBuffer(((byte[][])o)[0]);
    }
    else if (cls == DataValueDescriptor[].class) {
      isArray = false;
      isDvdArray = true;
      if (diagnoseFileData) {
        rowObjectsWritten = new ArrayList<String>();
      }
      else {
        rowObjectsWritten = null;
      }
      writeDVDBuffer((DataValueDescriptor[])o);
    }
    else {
      SanityManager.THROWASSERT("StreamFileHandler: unkown row format ");
      isArray = false;
      isDvdArray = false;
      rowObjectsWritten = null;
    }

    rowsWritten++;
    numRows++;

    load();
  }

  public final void load() throws StandardException {

    ExecRow row;
    if (isArray) {

      while ((row = rowSource.getNextRowFromRowSource()) != null) {
        writeBuffer((byte[])row.getRawRowValue(false));
        rowsWritten++;
        numRows++;
      }

    }
    else if (!isDvdArray) {

      while ((row = rowSource.getNextRowFromRowSource()) != null) {
        writeBuffer(((byte[][])row.getRawRowValue(false))[0]);
        rowsWritten++;
        numRows++;
      }

    }
    else {

      assert isDvdArray;

      while ((row = rowSource.getNextRowFromRowSource()) != null) {
        writeDVDBuffer((DataValueDescriptor[])row.getRawRowValue(false));
        rowsWritten++;
        numRows++;
      }

    }

    try {

      flushNoSync(false);
      channel.force(true);

    } catch (final IOException ioe) {
      throw StandardException.newException(SQLState.LOG_CANNOT_FLUSH, ioe);
    } finally {
      // rowSource.closeRowSource();
      rowSource = null;
      this.rwBuffer.clear();
      this.rwBuffer = null;
    }

  }

  /**
   * Test Method
   * @param conglomId
   * @param rwBuffer
   * @throws StandardException
   */
  public FileStreamInputOutput(final long conglomId, final ByteBuffer rwBuffer)
      throws StandardException {
    this.rwBuffer = rwBuffer;
    isArray = false;
    isDvdArray = true;
    final FabricDatabase db = Misc.getMemStore().getDatabase();

    this.file = db.getTempDir().createTemporaryFile(conglomId);
    this.channel = this.file.getChannel();
    this.rowSource = null;
    this.templateRow = null;
    this.capacity = rwBuffer.capacity();
    rowObjectsWritten = new ArrayList<String>();
  }

//  public FileStreamInputOutput(long nextTempConglomId,
//      Iterator iterator) {
//    
//  }

  public final void flipToRead() throws StandardException {
    try {
      this.channel.position(0);
      this.rwBuffer = realAllocate(this.capacity);
      if (GemFireXDUtils.TraceTempFileIO) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
            "Reading file : " + this.file);
      }
    } catch (final IOException ioe) {
      throw StandardException.newException(SQLState.LOG_CANNOT_FLUSH, ioe);
    }
    getNextBuffer();
  }

  private final void writeBuffer(final byte[] b) throws StandardException {

    final int r = rwBuffer.remaining();
    if (r < GemFireXDUtils.IntegerBytesLen) {
      try {
        flushNoSync(true);
      } catch (IOException ioe) {
        throw StandardException.newException(SQLState.LOG_CANNOT_FLUSH, ioe);
      }
    }

    try {
      rwBuffer.putInt(b.length);
      write(b, 0, b.length);
    } catch (IOException e) {
      Throwable t = e.getCause();
      if (t instanceof StandardException) {
        throw (StandardException)t;
      }
      throw GemFireXDRuntimeException.newRuntimeException(
          "writeBuffer:write exception", e);
    }
  }

  private final void writeDVDBuffer(final DataValueDescriptor[] dvdA)
      throws StandardException {
    try {
      if (GemFireXDUtils.TraceTempFileIO) {
        StringBuilder sb = new StringBuilder();
        sb.append("Writing DataValueDescriptors of length ")
            .append(dvdA.length);
        sb.append(" values ");
        StringBuilder dvdS = toString(dvdA, new StringBuilder());
        sb.append(dvdS);
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
            sb.toString());

        if (diagnoseFileData) {
          rowObjectsWritten.add(dvdS.toString());
        }
      }
      DataType.writeDVDArray(dvdA, this);
    } catch (final IOException ioe) {
      throw StandardException.newException(SQLState.LOG_CANNOT_FLUSH, ioe);
    }
  }

  private final DataValueDescriptor[] readDVDBuffer() throws StandardException {
    try {

      if (!diagnoseFileData) {
        return DataType.readDVDArray(this);
      }

      // we are in diagnostic mode ....
      final DataValueDescriptor[] dvd = DataType.readDVDArray(this);

      if (GemFireXDUtils.TraceTempFileIO) {
        final String srcDvd = rowObjectsWritten.get(rowObjectsindex++);
        String dvdS = toString(dvd, new StringBuilder()).toString();
        SanityManager.ASSERT(srcDvd.equals(dvdS), srcDvd + " != " + dvdS);
      }

      return dvd;

    } catch (final IOException ioe) {
      throw StandardException.newException(SQLState.LOG_CANNOT_FLUSH, ioe);
    } catch (final ClassNotFoundException cnf) {
      throw StandardException.newException(SQLState.LOG_CANNOT_FLUSH, cnf);
    }
  }

  private final boolean checkWriteBounds(final int expected,
      final boolean isByteArray) throws IOException {
    if (rwBuffer.remaining() >= expected) {
      return true;
    }

    /*
     * write(byte[] ...) method will take care of flushing after filling up 
     * the remaining bytes..
     */
    if (isByteArray) {
      return false;
    }

    if (GemFireXDUtils.TraceTempFileIO) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO, "["
          + this.file + "] flushing the current buffer as remaining "
          + rwBuffer.remaining() + " doesn't fits expected ... " + expected);
      // new Throwable());
    }

    flushNoSync(!isByteArray);

    return rwBuffer.remaining() >= expected;
  }

  private final void checkReadBounds(final int expected) throws IOException {
    if (rwBuffer.remaining() >= expected) {
      return;
    }

    if (GemFireXDUtils.TraceTempFileIO) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
          "[" + this.file + "] moving onto next buffer as remaining "
              + rwBuffer.remaining() + " doesn't fits expected " + expected);
      // new Throwable());
    }

    getNextBuffer();

    assert rwBuffer.remaining() >= expected: "remaining="
        + rwBuffer.remaining() + " expected=" + expected;
  }

  private final void flushNoSync(final boolean withBytePadding)
      throws IOException {
    try {
      /*
       * for byte[] we write back to back everything & read it accordingly.
       * for other types we incrementally convert data to byte & thus doesn't write
       * partially across buffer limit.
       */
      if (withBytePadding) {
        // pad blank bytes upto buffer boundary.
        for (int i = rwBuffer.remaining(); i > 0; i--)
          rwBuffer.put(Byte.MIN_VALUE);
      }

      rwBuffer.flip();
      do {

        assert rwBuffer.capacity() != 0;
        final int byteswritten = channel.write(rwBuffer);
        assert byteswritten > 0;
        totalBytesWritten += byteswritten;

        if (GemFireXDUtils.TraceTempFileIO) {

          final StringBuilder sb = new StringBuilder();
          sb.append("Written [").append(this.file).append("] ");
          sb.append(byteswritten).append(" bytes totaling ").append(
              totalBytesWritten).append(" bytes constituting rows ").append(
              rowsWritten).append(" total rows rises to ").append(numRows);

          if (diagnoseFileData) {
            sb.append("\n buffer [").append(this.rwBuffer).append(" ").append(
                (this.rwBuffer.hasArray() ? Arrays.toString(this.rwBuffer.array())
                    : "<direct buffer>")).append(" ] ");
          }
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO, sb
              .toString());
        }
        rowsWritten = 0;
      } while (rwBuffer.hasRemaining());

    } finally {
      rwBuffer.clear();
    }

    assert rwBuffer.remaining() == rwBuffer.capacity();
    assert rwBuffer.position() == 0;
  }

  private final void getNextBuffer() {
    // TODO:[sb] instead of capacity, change it to capacity/[no of merge runs]
    // while reading.
    this.rwBuffer = this.file.getReadBuffer(this.capacity, rwBuffer);
    totalBytesRead += this.file.bytesRead();

    if (GemFireXDUtils.TraceTempFileIO) {
      final StringBuilder sb = new StringBuilder();
      sb.append("Read [").append(this.file).append("] ");
      sb.append(this.file.bytesRead()).append(" bytes totaling ").append(
          totalBytesRead).append(" bytes out of ").append(totalBytesWritten)
          .append(" rows remaining ").append(numRows).append(
              ", rows read on last file read ").append(rowsRead);

      if (diagnoseFileData) {
        sb.append("\n buffer [").append(this.rwBuffer).append(" ").append(
            (this.rwBuffer.hasArray() ? Arrays.toString(this.rwBuffer.array())
                : "<direct buffer>")).append(" ] ");
      }
      SanityManager
          .DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO, sb.toString());
    }
    rowsRead = 0;
  }

  @Override
  public void close() {
    try {

      channel.close();
      file.delete();
      if (rowSource != null) {
        rowSource.closeRowSource();
      }
      rwBuffer = null;

    } catch (IOException e) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "FileStreamInputOutput:close Unexpected IOException ", e);
    }
  }

  @Override
  public boolean fetchNext(final ExecRow row) throws StandardException {

    if (numRows-- <= 0) {
      if (GemFireXDUtils.TraceTempFileIO) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
            "Returned all rows from file " + this.file);
      }
      return false;
    }

    // for byte[] & byte[][]
    if (isArray || !isDvdArray) {

      int remaining = rwBuffer.remaining();

      final int bytelength;
      if (remaining >= GemFireXDUtils.IntegerBytesLen) {
        bytelength = rwBuffer.getInt();
      }
      else {
        getNextBuffer();
        bytelength = rwBuffer.getInt();
      }

      // TODO: try to reuse across mergeRuns instead of allocation here.
      final byte[] db = new byte[bytelength];

      final AbstractCompactExecRow r = (AbstractCompactExecRow)row;
      try {
        readFully(db, 0, bytelength);
      } catch (IOException e) {
        Throwable t = e.getCause();
        if (t instanceof StandardException) {
          throw (StandardException)t;
        }
        throw GemFireXDRuntimeException.newRuntimeException(
            "FileStreamInputOutput:fetchNext exception", e);
      }

      // refresh the RowFormatter since schema of row may be different than the
      // current schema as provided in the template ExecRow
      final RowFormatter rf = r.getRowFormatter();
      final GemFireContainer container;
      // for multiple schemas and table row formatter use schema version
      // formatter
      if (rf.isTableFormatter()
          && (container = rf.container).singleSchema == null) {
        r.setRowArray(db, container.getRowFormatter(db));
      }
      else {
        r.setRowArray(db, rf);
      }
      rowsRead++;
      return true;
    }
    else {
      rwBuffer.remaining();

      assert isDvdArray;
      // TODO: adjust like above for DVD[]
      // we need to re-use here.
      row.setRowArray(readDVDBuffer());
      rowsRead++;
      return true;
    }
  }

  @Override
  public void getContainerProperties(final Properties prop)
      throws StandardException {
  }

  public static final ByteBuffer allocateBuffer(final int targetSize,
      final TransactionController tc) throws StandardException {

    final String pValue = PropertyUtil.getServiceProperty(tc,
        RawStoreFactory.STREAM_FILE_BUFFER_SIZE_PARAMETER);

    final int configuredSz = PropertyUtil.handleInt(pValue,
        RawStoreFactory.STREAM_FILE_BUFFER_SIZE_MINIMUM,
        RawStoreFactory.STREAM_FILE_BUFFER_SIZE_MAXIMUM,
        RawStoreFactory.STREAM_FILE_BUFFER_SIZE_DEFAULT);

    // won't allocate beyond configured size only exception is when targetSize
    // is zero.
    final int size = targetSize == 0 || targetSize > configuredSz ? configuredSz
        : targetSize;
    assert size > 0: "size=" + size + "targetSize=" + targetSize + " configuredSz="
        + configuredSz + " pValue=" + pValue;

    return realAllocate(size);
  }

  private static final ByteBuffer realAllocate(final int size) {
    // return ByteBuffer.allocateDirect(size);
    return ByteBuffer.allocate(size);
  }

  @Override
  public ContainerKey getId() {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:getId: shouldn't be called... ");
    return null;
  }

  @Override
  public void removeContainer() throws StandardException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:removeContainer: shouldn't be called... ");
  }

  @Override
  public ExecRow getTemplateRow() {
    return this.templateRow;
  }

  private StringBuilder toString(DataValueDescriptor[] dvd, StringBuilder sb)
      throws StandardException {
    for (int i = 0; i < dvd.length; i++) {
      sb.append(dvd[i].getString()).append("(").append(dvd[i].getTypeName())
          .append("),");
    }
    return sb;
  }

  // -------------------------------------------
  // DataOutput methods
  // -------------------------------------------
  @Override
  public final void write(final int b) throws IOException {
    checkWriteBounds(1, false);
    rwBuffer.put((byte)b);
  }

  @Override
  public final void write(final byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public final void write(final byte[] b, int off, int len) throws IOException {

    if (checkWriteBounds(len, true)) {
      rwBuffer.put(b, off, len);
      return;
    }

    int remaining = rwBuffer.remaining();

    do {
      if (len < remaining) {
        rwBuffer.put(b, off, len);
        return;
      }
      rwBuffer.put(b, off, remaining);
      off += remaining;
      len -= remaining;
      remaining = rwBuffer.capacity();
      flushNoSync(false);
    } while (len > 0);

  }

  @Override
  public void writeBoolean(final boolean v) throws IOException {
    checkWriteBounds(1, false);
    rwBuffer.put((byte)(v ? 1 : 0));
  }

  @Override
  public void writeByte(final int v) throws IOException {
    final boolean hasSpace = checkWriteBounds(1, false);
    assert hasSpace: "BufferOverflowException will occur "
        + rwBuffer.position() + " " + rwBuffer.capacity() + " "
        + rwBuffer.limit();
    rwBuffer.put((byte)v);
  }

  @Override
  public void writeBytes(final String s) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:writeBytes: String overload not allowed... ");
  }

  @Override
  public void writeChar(final int v) throws IOException {
    checkWriteBounds(2, false);
    rwBuffer.putChar((char)v);
  }

  @Override
  public void writeChars(final String s) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:writeChars: String overload not allowed... ");
  }

  @Override
  public void writeDouble(final double v) throws IOException {
    checkWriteBounds(8, false);
    rwBuffer.putDouble(v);
  }

  @Override
  public void writeFloat(final float v) throws IOException {
    checkWriteBounds(4, false);
    rwBuffer.putFloat(v);
  }

  @Override
  public void writeInt(final int v) throws IOException {
    checkWriteBounds(4, false);
    rwBuffer.putInt(v);
  }

  @Override
  public void writeLong(final long v) throws IOException {
    checkWriteBounds(8, false);
    rwBuffer.putLong(v);
  }

  @Override
  public void writeShort(final int v) throws IOException {
    checkWriteBounds(2, false);
    rwBuffer.putShort((short)v);
  }

  @Override
  public void writeUTF(final String s) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:writeUTF: String overload not allowed... ");
  }

  @Override
  public boolean readBoolean() throws IOException {
    checkReadBounds(1);
    return rwBuffer.get() != 0;
  }

  @Override
  public byte readByte() throws IOException {
    checkReadBounds(1);
    return rwBuffer.get();
  }

  @Override
  public char readChar() throws IOException {
    checkReadBounds(2);
    return rwBuffer.getChar();
  }

  @Override
  public double readDouble() throws IOException {
    checkReadBounds(8);
    return rwBuffer.getDouble();
  }

  @Override
  public float readFloat() throws IOException {
    checkReadBounds(4);
    return rwBuffer.getFloat();
  }

  @Override
  public void readFully(final byte[] b) throws IOException {
    readFully(b, 0, b.length);
  }

  @Override
  public void readFully(final byte[] b, int off, int len) throws IOException {

    int remaining = rwBuffer.remaining();

    if (remaining >= len) {
      rwBuffer.get(b, off, len);
      return;
    }

    while (len > 0) {
      if (remaining >= len) {
        rwBuffer.get(b, off, len);
        return;
      }
      rwBuffer.get(b, off, remaining);
      off += remaining;
      len -= remaining;
      getNextBuffer();
      remaining = rwBuffer.capacity();
    }
  }

  @Override
  public int readInt() throws IOException {
    checkReadBounds(4);
    return rwBuffer.getInt();
  }

  @Override
  public String readLine() throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:readLine: String overload not allowed... ");
    return null;
  }

  @Override
  public long readLong() throws IOException {
    checkReadBounds(8);
    return rwBuffer.getLong();
  }

  @Override
  public short readShort() throws IOException {
    checkReadBounds(2);
    return rwBuffer.getShort();
  }

  @Override
  public String readUTF() throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:readUTF: String overload not allowed... ");
    return null;
  }

  @Override
  public int readUnsignedByte() throws IOException {
    checkReadBounds(1);
    return rwBuffer.get() & 0xff;
  }

  @Override
  public int readUnsignedShort() throws IOException {
    checkReadBounds(2);
    return rwBuffer.getShort() & 0xffff;
  }

  @Override
  public int skipBytes(final int n) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:skipBytes:unexpected operation... ");
    return 0;
  }

  @Override
  public void flush() throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:flush:unexpected operation... ");
  }

  @Override
  public void writeObject(Object obj) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:writeObject:unexpected operation... ");
  }

  @Override
  public int available() throws IOException {
    return rwBuffer.remaining();
  }

  @Override
  public int read() throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:read:unexpected operation... ");
    return 0;
  }

  @Override
  public int read(byte[] b) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:read(byte[]):unexpected operation... ");
    return 0;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:read(byte[], off, len):unexpected operation... ");
    return 0;
  }

  @Override
  public Object readObject() throws ClassNotFoundException, IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:readObject:unexpected operation... ");
    return null;
  }

  @Override
  public long skip(long n) throws IOException {
    SanityManager
        .THROWASSERT("FileStreamInputOutput:skip:unexpected operation... ");
    return 0;
  }

  // Test Method
  public void flushAndSync(boolean isByteArray) throws StandardException,
      IOException {
    flushNoSync(!isByteArray);
    channel.force(true);
  }
}
