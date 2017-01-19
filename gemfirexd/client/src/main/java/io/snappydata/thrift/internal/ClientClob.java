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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.SQLException;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.ClobChunk;
import io.snappydata.thrift.SnappyException;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.snappydataConstants;
import org.apache.commons.io.input.ReaderInputStream;

public class ClientClob extends ClientLobBase implements Clob {

  private ClobChunk currentChunk;
  private Reader reader;
  private int baseChunkSize;
  private final boolean freeForStream;

  ClientClob(ClientService service) {
    super(service);
    this.reader = new MemStringReader("");
    this.length = 0;
    this.freeForStream = false;
  }

  ClientClob(InputStream asciiStream, long length,
      ClientService service) throws SQLException {
    super(service);
    checkLength(length);
    this.reader = new MemStreamReader(asciiStream, StandardCharsets.US_ASCII);
    this.length = (int)length;
    this.freeForStream = true;
  }

  ClientClob(Reader reader, long length,
      ClientService service) throws SQLException {
    super(service);
    checkLength(length);
    this.reader = reader;
    this.length = (int)length;
    this.freeForStream = true;
  }

  ClientClob(ClobChunk firstChunk, ClientService service,
      HostConnection source, boolean freeForStream) throws SQLException {
    super(service, firstChunk.last ? snappydataConstants.INVALID_ID
        : firstChunk.lobId, source);
    this.baseChunkSize = firstChunk.chunk.length();
    this.currentChunk = firstChunk;
    this.freeForStream = freeForStream;
    long length = -1;
    if (firstChunk.isSetTotalLength()) {
      length = firstChunk.getTotalLength();
    } else if (firstChunk.last) {
      length = this.baseChunkSize;
    }
    checkLength(length);
    this.length = (int)length;
  }

  @Override
  protected int streamLength(boolean forceMaterialize) throws SQLException {
    try {
      final Reader reader = this.reader;
      if (reader instanceof MemStringReader) {
        return ((MemStringReader)reader).length();
      } else if (forceMaterialize) {
        // materialize the stream
        final int bufSize = 32768;
        final char[] buffer = new char[bufSize];
        int readLen = readStream(reader, buffer, 0, bufSize);
        if (readLen < bufSize) {
          // fits into single buffer
          this.reader = new MemStringReader(ClientSharedUtils.newWrappedString(
              buffer, 0, readLen));
          return readLen;
        } else {
          StringPrintWriter out = new StringPrintWriter(new StringBuilder());
          out.write(buffer, 0, bufSize);
          while ((readLen = readStream(reader, buffer, 0, bufSize)) > 0) {
            out.write(buffer, 0, readLen);
          }
          String output = out.toString();
          this.reader = new MemStringReader(output);
          out.close(); // no-op to remove a warning
          return output.length();
        }
      } else {
        return -1;
      }
    } catch (IOException ioe) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe, "java.sql.Clob");
    }
  }

  final String readString(long offset, int length) throws SQLException {
    ClobChunk chunk;
    checkOffset(offset);
    if (this.streamedInput) {
      try {
        char[] buffer = new char[length];
        long skipBytes = offset - this.streamOffset;
        if (this.reader instanceof MemStringReader) {
          // negative skip also works for StringReader
          skipBytes -= this.reader.skip(skipBytes);
        }
        if (skipBytes > 0) {
          long skipped;
          while ((skipped = this.reader.skip(skipBytes)) > 0) {
            if ((skipBytes -= skipped) <= 0) {
              break;
            }
          }
          if (skipBytes > 0) {
            int readBytes;
            while ((readBytes = this.reader.read(buffer)) > 0) {
              if ((skipBytes -= readBytes) <= 0) {
                break;
              }
            }
          }
        } else if (skipBytes < 0) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.BLOB_BAD_POSITION, null, offset + 1);
        }
        // keep reading stream till end
        int readLen = readStream(this.reader, buffer, 0, length);
        checkOffset(offset + readLen);
        this.streamOffset = (int)(offset + readLen);
        return ClientSharedUtils.newWrappedString(buffer, 0, readLen);
      } catch (IOException ioe) {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe, "java.sql.Clob");
      }
    } else if ((chunk = this.currentChunk) != null) {
      String buffer = chunk.chunk;
      // check if it lies outside the current chunk
      if (offset < chunk.offset
          || (offset + length) > (chunk.offset + buffer.length())) {
        // fetch new chunk
        try {
          this.currentChunk = chunk = service.getClobChunk(
              getLobSource(true, "Clob.readBytes"), chunk.lobId, offset,
              Math.max(baseChunkSize, length), false);
          buffer = chunk.chunk;
        } catch (SnappyException se) {
          throw ThriftExceptionUtil.newSQLException(se);
        }
      }
      if (offset > chunk.offset) {
        int startIndex = (int)(offset - chunk.offset);
        return buffer.substring(startIndex, startIndex + length);
      } else {
        return buffer.substring(0, length);
      }
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.LOB_OBJECT_INVALID);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void clear() {
    this.currentChunk = null;
    this.reader = null;
  }

  @Override
  public String getSubString(long pos, int length) throws SQLException {
    final long offset = pos - 1;
    length = checkOffset(offset, length);

    if (length > 0) {
      return readString(offset, length);
    } else {
      return "";
    }
  }

  @Override
  public Reader getCharacterStream() throws SQLException {
    if (this.streamedInput) {
      return this.reader;
    } else if (this.currentChunk != null) {
      return new LobReader(0, this.length);
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.LOB_OBJECT_INVALID);
    }
  }

  @Override
  public Reader getCharacterStream(long pos, long length) throws SQLException {
    if (this.streamedInput || this.currentChunk != null) {
      final long offset = pos - 1;
      length = checkOffset(offset, length);
      return new LobReader(offset, length);
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.LOB_OBJECT_INVALID);
    }
  }

  @Override
  public InputStream getAsciiStream() throws SQLException {
    if (this.streamedInput) {
      if (this.reader instanceof MemStreamReader) {
        return ((MemStreamReader)this.reader).getInputStream();
      } else {
        return new ReaderInputStream(this.reader, StandardCharsets.US_ASCII);
      }
    } else if (this.currentChunk != null) {
      return new ReaderInputStream(new LobReader(0, this.length),
          StandardCharsets.US_ASCII);
    } else {
      throw ThriftExceptionUtil.newSQLException(SQLState.LOB_OBJECT_INVALID);
    }
  }

  @Override
  public int setString(long pos, String str) throws SQLException {
    return setString(pos, str, 0, str.length());
  }

  @Override
  public int setString(long pos, String str, int offset, int len)
      throws SQLException {
    len = checkOffset(pos - 1, len);
    final int clobOffset = (int)(pos - 1);
    if (offset < 0 || offset > str.length()) {
      throw ThriftExceptionUtil.newSQLException(SQLState.BLOB_INVALID_OFFSET,
          null, offset);
    }

    if (len > 0) {
      String sbuffer;
      if (this.streamedInput) {
        MemStringReader ms;
        if (clobOffset < this.streamOffset) {
          throw ThriftExceptionUtil.newSQLException(
              SQLState.BLOB_BAD_POSITION, null, pos);
        }
        if (this.reader instanceof MemStringReader) {
          ms = (MemStringReader)this.reader;
        } else if (streamLength(true) >= 0) {
          ms = (MemStringReader)this.reader;
        } else {
          throw ThriftExceptionUtil.newSQLException(SQLState.NOT_IMPLEMENTED,
              null, "setString after get* invocation on stream");
        }
        sbuffer = ms.getString();
      } else {
        // materialize remote blob data
        sbuffer = getSubString(1, this.length);
        this.currentChunk = null;
        this.streamedInput = true;
      }
      // create a new string modifying the fragment with provided string
      final int reqLen = len + clobOffset;
      final int slen = sbuffer.length();
      // check the special case of string being set into zero sized buffer
      if (slen == 0) {
        this.reader = new MemStringReader(str.substring(offset, offset + len));
      } else {
        StringBuilder sb = new StringBuilder(Math.max(reqLen, slen));
        if (clobOffset > 0) {
          sb.append(sbuffer.substring(0, clobOffset));
        }
        sb.append(str.substring(offset, offset + len));
        if (reqLen < this.length) {
          sb.append(sbuffer.substring(reqLen, this.length));
        }
        this.reader = new MemStringReader(sb.toString());
      }
      return len;
    } else {
      return 0;
    }
  }

  @Override
  public OutputStream setAsciiStream(long pos) throws SQLException {
    // TODO: implement
    throw ThriftExceptionUtil.newSQLException(
        SQLState.JDBC_METHOD_NOT_IMPLEMENTED, null, "Clob.setAsciiStream");
  }

  @Override
  public Writer setCharacterStream(long pos) throws SQLException {
    // TODO: implement
    throw ThriftExceptionUtil.newSQLException(
        SQLState.JDBC_METHOD_NOT_IMPLEMENTED, null, "Clob.setCharacterStream");
  }

  @Override
  public long position(String searchstr, long start) throws SQLException {
    // TODO: implement
    throw ThriftExceptionUtil.newSQLException(
        SQLState.JDBC_METHOD_NOT_IMPLEMENTED, null, "Clob.position");
  }

  @Override
  public long position(Clob searchstr, long start) throws SQLException {
    // TODO: implement
    throw ThriftExceptionUtil.newSQLException(
        SQLState.JDBC_METHOD_NOT_IMPLEMENTED, null, "Clob.position");
  }

  final class LobReader extends Reader {

    private long clobOffset;
    private final long length;
    private final char[] singleChar;

    LobReader(long offset, long length) {
      this.clobOffset = offset;
      this.length = length;
      this.singleChar = new char[1];
    }

    int read_(char[] buf, int offset, int len) throws IOException {
      if (this.length >= 0) {
        long remaining = this.length - this.clobOffset;
        if (remaining < Integer.MAX_VALUE) {
          len = Math.min((int)remaining, len);
        }
      }
      try {
        String str = readString(this.clobOffset, len);
        int nbytes = str.length();
        if (nbytes > 0) {
          this.clobOffset += nbytes;
          str.getChars(0, nbytes, buf, offset);
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
      if (read_(this.singleChar, 0, 1) == 1) {
        return this.singleChar[0];
      } else {
        return -1;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(@SuppressWarnings("NullableProblems") char[] buf,
        int offset, int len) throws IOException {
      if (buf == null) {
        throw new NullPointerException();
      } else if (offset < 0 || len < 0 || len > (buf.length - offset)) {
        throw new IndexOutOfBoundsException();
      }

      if (len != 0) {
        return read_(buf, offset, len);
      } else {
        return 0;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
      if (freeForStream) {
        free();
      }
    }
  }

  /**
   * Adapted from android's StringReader.
   */
  static final class MemStringReader extends StringReader {
    private String str;

    /**
     * Creates a new string reader.
     *
     * @param s String providing the character stream.
     */
    public MemStringReader(String s) {
      super(s);
      this.str = s;
    }

    public String getString() {
      return this.str;
    }

    public int length() {
      final String str = this.str;
      return str != null ? str.length() : 0;
    }
  }

  static final class MemStreamReader extends InputStreamReader {

    private final InputStream in;

    public MemStreamReader(InputStream in, Charset cs) {
      super(in, cs);
      this.in = in;
    }

    public InputStream getInputStream() {
      return this.in;
    }
  }
}
