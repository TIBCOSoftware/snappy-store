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
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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

package io.snappydata.thrift.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.BlobChunk;
import io.snappydata.thrift.SnappyException;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.snappydataConstants;

public final class ClientBlob extends ClientLobBase implements Blob {

  private BlobChunk currentChunk;
  private InputStream dataStream;
  private int baseChunkSize;

  static final byte[] ZERO_ARRAY = new byte[0];

  ClientBlob(ClientService service) {
    super(service);
    this.dataStream = new MemInputStream(ZERO_ARRAY);
    this.length = 0;
  }

  ClientBlob(InputStream dataStream, ClientService service) {
    super(service);
    this.dataStream = dataStream;
  }

  ClientBlob(BlobChunk firstChunk, ClientService service,
      HostConnection source) throws SQLException {
    super(service, firstChunk.last ? snappydataConstants.INVALID_ID
        : firstChunk.lobId, source);
    this.baseChunkSize = firstChunk.chunk.remaining();
    this.currentChunk = firstChunk;
    if (firstChunk.isSetTotalLength()) {
      this.length = firstChunk.getTotalLength();
    } else if (firstChunk.last) {
      this.length = this.baseChunkSize;
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_NONPOSITIVE_LENGTH, null, 0);
    }
  }

  @Override
  protected long streamLength(boolean forceMaterialize) throws SQLException {
    try {
      final InputStream dataStream = this.dataStream;
      if (dataStream instanceof MemInputStream) {
        return ((MemInputStream)dataStream).getCount();
      } else if (!forceMaterialize && dataStream instanceof FileInputStream) {
        // need to seek into the stream but only for FileInputStream
        return ((FileInputStream)dataStream).getChannel().size();
      } else if (this.streamOffset == 0) {
        // materialize the stream
        final int bufSize = 32768;
        final byte[] buffer = new byte[bufSize];
        int readLen = readStream(dataStream, buffer, 0, bufSize);
        if (readLen < bufSize) {
          // fits into single buffer
          this.dataStream = new MemInputStream(buffer, 0, readLen);
          return readLen;
        } else {
          MemOutputStream out = new MemOutputStream(bufSize + (bufSize >>> 1));
          out.write(buffer, 0, bufSize);
          while ((readLen = readStream(dataStream, buffer, 0, bufSize)) > 0) {
            out.write(buffer, 0, readLen);
          }
          int streamSize = out.size();
          this.dataStream = new MemInputStream(out.getBuffer(), 0, streamSize);
          out.close(); // no-op to remove a warning
          return streamSize;
        }
      } else {
        return -1;
      }
    } catch (IOException ioe) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe, "java.sql.Blob");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void clear() {
    this.currentChunk = null;
    // don't need to do anything to close MemInputStream yet
    this.dataStream = null;
  }

  static int readStream(final InputStream is, byte[] buf, int offset, int len)
      throws IOException {
    int readLen = 0;
    int readBytes;
    while (len > 0 && (readBytes = is.read(buf, offset, len)) > 0) {
      readLen += readBytes;
      offset += readLen;
      len -= readLen;
    }
    return readLen;
  }

  final int readBytes(long offset, byte[] b, int boffset, int length)
      throws SQLException {
    BlobChunk chunk;
    if (this.streamedInput) {
      try {
        long skipBytes = this.streamOffset - offset;
        if (skipBytes > 0) {
          long skipped;
          while ((skipped = this.dataStream.skip(skipBytes)) > 0) {
            if ((skipBytes -= skipped) <= 0) {
              break;
            }
          }
        } else if (skipBytes < 0) {
          if (this.dataStream instanceof FileInputStream) {
            // need to seek into the stream but only for FileInputStream
            FileInputStream fis = (FileInputStream)this.dataStream;
            fis.getChannel().position(offset);
          } else {
            throw ThriftExceptionUtil.newSQLException(
                SQLState.BLOB_BAD_POSITION, null, offset + 1);
          }
        }
        // keep reading stream till end
        int readLen = readStream(this.dataStream, b, boffset, length);
        this.streamOffset = (offset + readLen);
        return readLen;
      } catch (IOException ioe) {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe,
            "java.sql.Blob");
      }
    } else if ((chunk = this.currentChunk) != null) {
      ByteBuffer buffer = chunk.chunk;
      // check if it lies outside the current chunk
      if (offset < chunk.offset
          || (offset + length) > (chunk.offset + buffer.remaining())) {
        // fetch new chunk
        try {
          this.currentChunk = chunk = service.getBlobChunk(
              getLobSource(true, "Blob.readBytes"), chunk.lobId, offset,
              Math.max(baseChunkSize, length), false);
          buffer = chunk.chunk;
        } catch (SnappyException se) {
          throw ThriftExceptionUtil.newSQLException(se);
        }
      }
      int bpos = buffer.position();
      length = Math.min(length, buffer.remaining());
      buffer.get(b, boffset, length);
      buffer.position(bpos);
      return length;
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.LOB_OBJECT_INVALID);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] getBytes(long pos, int length) throws SQLException {
    final long offset = pos - 1;
    length = (int)checkOffset(offset, length);

    if (length > 0) {
      byte[] result = new byte[length];
      int nbytes = readBytes(offset, result, 0, length);
      if (nbytes == length) {
        return result;
      } else {
        return Arrays.copyOf(result, nbytes);
      }
    } else {
      return ZERO_ARRAY;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public InputStream getBinaryStream() throws SQLException {
    if (this.streamedInput) {
      return this.dataStream;
    } else if (this.currentChunk != null) {
      return new Stream(0, this.length);
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.LOB_OBJECT_INVALID);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public InputStream getBinaryStream(long pos, long len) throws SQLException {
    final long offset = pos - 1;
    len = checkOffset(offset, len);
    return new Stream(offset, len);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int setBytes(long pos, byte[] bytes, int boffset, int len)
      throws SQLException {
    checkOffset(pos - 1, len);
    if (boffset < 0 || boffset > bytes.length) {
      throw ThriftExceptionUtil.newSQLException(SQLState.BLOB_INVALID_OFFSET,
          null, boffset);
    }

    if (len > 0) {
      MemInputStream ms;
      byte[] sbuffer;
      if (this.streamedInput) {
        if (dataStream instanceof MemInputStream) {
          ms = (MemInputStream)dataStream;
        } else if (streamLength(true) >= 0) {
          ms = (MemInputStream)dataStream;
        } else {
          throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED,
              null, "setBytes after get* invocation on stream");
        }
        sbuffer = ms.getBuffer();
      } else {
        // materialize remote blob data
        sbuffer = getBytes(1, (int)this.length);
        @SuppressWarnings("resource")
        MemInputStream mms = new MemInputStream(sbuffer);
        this.currentChunk = null;
        this.streamedInput = true;
        ms = mms;
      }
      final int offset = (int)(pos - 1);
      int newSize = offset + len;
      if (newSize <= sbuffer.length) {
        // just change the underlying buffer and update count if required
        System.arraycopy(bytes, boffset, sbuffer, offset, len);
        if (newSize > ms.getCount()) {
          ms.changeCount(newSize);
        }
      } else {
        // create new buffer and set into input stream
        sbuffer = Arrays.copyOf(sbuffer, newSize);
        System.arraycopy(bytes, boffset, sbuffer, offset, len);
        ms.changeBuffer(sbuffer);
        ms.changeCount(newSize);
      }
      return len;
    } else {
      return 0;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int setBytes(long pos, byte[] bytes) throws SQLException {
    return setBytes(pos, bytes, 0, bytes.length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public OutputStream setBinaryStream(long pos) throws SQLException {
    // TODO: implement
    throw ThriftExceptionUtil.newSQLException(
        SQLState.JDBC_METHOD_NOT_IMPLEMENTED, null, "Blob.setBinaryStream");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long position(byte[] pattern, long start) throws SQLException {
    // TODO: implement
    throw ThriftExceptionUtil.newSQLException(
        SQLState.JDBC_METHOD_NOT_IMPLEMENTED, null, "Blob.position");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long position(Blob pattern, long start) throws SQLException {
    // TODO: implement
    throw ThriftExceptionUtil.newSQLException(
        SQLState.JDBC_METHOD_NOT_IMPLEMENTED, null, "Blob.position");
  }

  final class Stream extends InputStream {

    private long blobOffset;
    private final long length;
    private final byte[] singleByte;

    Stream(long offset, long length) {
      this.blobOffset = offset;
      this.length = length;
      this.singleByte = new byte[1];
    }

    int read_(byte[] b, int offset, int len) throws IOException {
      if (this.length >= 0) {
        long remaining = this.length - this.blobOffset;
        if (remaining < Integer.MAX_VALUE) {
          len = Math.min((int)remaining, len);
        }
      }
      try {
        int nbytes = readBytes(this.blobOffset, b, offset, len);
        if (nbytes > 0) {
          this.blobOffset += nbytes;
        }
        return nbytes;
      } catch (SQLException sqle) {
        if (sqle.getCause() instanceof IOException) {
          throw (IOException)sqle.getCause();
        } else {
          throw new IOException(sqle.getMessage(), sqle);
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
      if (read_(this.singleByte, 0, 1) == 1) {
        return this.singleByte[0];
      } else {
        return -1;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(byte[] b, int offset, int len) throws IOException {
      if (b == null) {
        throw new NullPointerException();
      } else if (offset < 0 || len < 0 || len > (b.length - offset)) {
        throw new IndexOutOfBoundsException();
      }

      if (len != 0) {
        return read_(b, offset, len);
      } else {
        return 0;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(long n) throws IOException {
      if (n > 0) {
        if (this.length >= 0) {
          n = Math.min(this.length - this.blobOffset, n);
          if (n <= 0) {
            return 0;
          }
        }
        if (streamedInput) {
          n = dataStream.skip(n);
          if (n > 0) {
            this.blobOffset += n;
          }
        } else if (currentChunk != null) {
          this.blobOffset += n;
        } else {
          return 0;
        }
        return n;
      }
      return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int available() throws IOException {
      BlobChunk chunk;
      if (streamedInput) {
        return dataStream.available();
      } else if ((chunk = currentChunk) != null) {
        long coffset = this.blobOffset - chunk.offset;
        if (coffset >= 0 && coffset <= Integer.MAX_VALUE) {
          int remaining = chunk.chunk.remaining() - (int)coffset;
          if (remaining >= 0) {
            return remaining;
          }
        }
        return 0;
      } else {
        return 0;
      }
    }
  }

  static final class MemInputStream extends ByteArrayInputStream {

    public MemInputStream(byte[] b) {
      super(b);
    }

    public MemInputStream(byte[] b, int offset, int len) {
      super(b, offset, len);
    }

    final byte[] getBuffer() {
      return buf;
    }

    final int getCount() {
      return this.count;
    }

    final void changeBuffer(byte[] b) {
      this.buf = b;
    }

    final void changeCount(int count) {
      this.count = count;
    }
  }

  static final class MemOutputStream extends ByteArrayOutputStream {

    public MemOutputStream(int size) {
      super(size);
    }

    final byte[] getBuffer() {
      return buf;
    }
  }
}
