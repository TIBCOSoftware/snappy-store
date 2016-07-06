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

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.ClobChunk;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.internal.ClientBlob.MemInputStream;
import io.snappydata.thrift.snappydataConstants;

public class ClientClob extends ClientLobBase implements Clob {

  private ClobChunk currentChunk;
  private InputStream dataStream;
  private int baseChunkSize;

  ClientClob(ClientService service) {
    super(service);
    this.dataStream = new MemInputStream(ClientBlob.ZERO_ARRAY);
    this.length = 0;
  }

  ClientClob(InputStream dataStream, ClientService service) {
    super(service);
    this.dataStream = dataStream;
  }

  ClientClob(ClobChunk firstChunk, ClientService service,
      HostConnection source) throws SQLException {
    super(service, firstChunk.last ? snappydataConstants.INVALID_ID
        : firstChunk.lobId, source);
    this.baseChunkSize = firstChunk.chunk.length();
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
    throw ThriftExceptionUtil.newSQLException(
        SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, null, "java.sql.Clob");
    /*
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
          SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, ioe, "java.sql.Clob");
    }
    */
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void clear() {
    this.currentChunk = null;
  }

  @Override
  public String getSubString(long pos, int length) throws SQLException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Reader getCharacterStream() throws SQLException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Reader getCharacterStream(long pos, long length) throws SQLException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public InputStream getAsciiStream() throws SQLException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public long position(String searchstr, long start) throws SQLException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long position(Clob searchstr, long start) throws SQLException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public int setString(long pos, String str) throws SQLException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public int setString(long pos, String str, int offset, int len)
      throws SQLException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public OutputStream setAsciiStream(long pos) throws SQLException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Writer setCharacterStream(long pos) throws SQLException {
    // TODO Auto-generated method stub
    return null;
  }
}
