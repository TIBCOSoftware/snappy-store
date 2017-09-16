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
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
import java.io.Reader;
import java.sql.SQLException;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import io.snappydata.thrift.common.ThriftExceptionUtil;
import io.snappydata.thrift.snappydataConstants;

/**
 * Base class for client BLOB and CLOB implementations.
 *
 * @author swale
 */
abstract class ClientLobBase {

  protected final ClientService service;
  protected final long lobId;
  protected ClientFinalizer finalizer;
  protected boolean streamedInput;
  protected int streamOffset;
  protected int length;

  protected ClientLobBase(ClientService service) {
    this.service = service;
    this.lobId = snappydataConstants.INVALID_ID;
    this.finalizer = null;
    this.streamedInput = true;
    this.length = -1;
  }

  protected ClientLobBase(ClientService service, long lobId,
      HostConnection source) {
    this.service = service;
    this.lobId = lobId;
    // invalid LOB ID means single lob chunk so ignore finalizer for that case
    if (lobId != snappydataConstants.INVALID_ID) {
      this.finalizer = new ClientFinalizer(this, service,
          snappydataConstants.BULK_CLOSE_LOB);
      this.finalizer.updateReferentData(lobId, source);
    } else {
      this.finalizer = null;
    }
    this.streamedInput = false;
  }

  protected final HostConnection getLobSource(boolean throwOnFailure,
      String op) throws SQLException {
    final ClientFinalizer finalizer = this.finalizer;
    final HostConnection source;
    if (finalizer != null && (source = finalizer.source) != null) {
      return source;
    } else if (throwOnFailure) {
      throw (SQLException)service.newExceptionForNodeFailure(null, op,
          service.isolationLevel, null, false);
    } else {
      return null;
    }
  }

  protected final int getLength(boolean forceMaterialize) throws SQLException {
    final int len = this.length;
    if (len >= 0) {
      return len;
    } else {
      return (this.length = streamLength(forceMaterialize));
    }
  }

  /**
   * @see java.sql.Blob#length()
   * @see java.sql.Clob#length()
   */
  public final long length() throws SQLException {
    final int len = getLength(true);
    if (len >= 0) {
      return len;
    } else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LOB_OBJECT_LENGTH_UNKNOWN_YET);
    }
  }

  static int readStream(final InputStream is, byte[] buf, int offset, int len)
      throws IOException {
    int readLen = 0;
    int readBytes = 0;
    while (len > 0 && (readBytes = is.read(buf, offset, len)) > 0) {
      readLen += readBytes;
      offset += readLen;
      len -= readLen;
    }
    return readLen > 0 ? readLen : (readBytes < 0 ? -1 : 0);
  }

  static int readStream(final Reader reader, char[] buf, int offset, int len)
      throws IOException {
    int readLen = 0;
    int readBytes = 0;
    while (len > 0 && (readBytes = reader.read(buf, offset, len)) > 0) {
      readLen += readBytes;
      offset += readLen;
      len -= readLen;
    }
    return readLen > 0 ? readLen : (readBytes < 0 ? -1 : 0);
  }

  protected void checkOffset(long offset) throws SQLException {
    if (offset < 0) {
      throw ThriftExceptionUtil.newSQLException(SQLState.BLOB_BAD_POSITION,
          null, offset + 1);
    } else if (offset >= Integer.MAX_VALUE) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_POSITION_TOO_LARGE, null, offset + 1);
    }
  }

  protected void checkLength(long length) throws SQLException {
    if (length < 0) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_NONPOSITIVE_LENGTH, null, length);
    } else if (length > Integer.MAX_VALUE) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_LENGTH_TOO_LONG, null, length);
    }
  }

  protected int checkOffset(long offset, long length,
      boolean trimIfRequired) throws SQLException {
    checkOffset(offset);
    checkLength(length);
    if (trimIfRequired && this.length >= 0) {
      long maxLen = this.length - offset;
      if (maxLen < 0) {
        throw ThriftExceptionUtil.newSQLException(
            SQLState.BLOB_POSITION_TOO_LARGE, null, offset + 1);
      }
      // return trimmed length if blob was truncated
      length = Math.min(maxLen, length);
      offset = 0;
    }
    if ((offset + length) > Integer.MAX_VALUE) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_LENGTH_TOO_LONG, null, length);
    }
    return (int)length;
  }

  /**
   * @see java.sql.Blob#truncate(long)
   * @see java.sql.Clob#truncate(long)
   */
  public void truncate(long len) throws SQLException {
    checkLength(len);
    final int length = getLength(false);
    if (length >= 0 && length < len) {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.BLOB_LENGTH_TOO_LONG, null, len);
    }
    this.length = (int)len;
  }

  public final void free() {
    final ClientFinalizer finalizer = this.finalizer;
    if (finalizer != null) {
      finalizer.clear();
      finalizer.getHolder().addToPendingQueue(finalizer);
      this.finalizer = null;
    }
    this.streamedInput = false;
    this.streamOffset = 0;
    this.length = -1;
    clear();
  }

  protected abstract int streamLength(boolean forceMaterialize)
      throws SQLException;

  protected abstract void clear();
}
