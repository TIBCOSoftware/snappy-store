/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialBlob

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.iapi.types;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.SQLException;

import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import io.snappydata.thrift.BlobChunk;
import io.snappydata.thrift.common.BufferedBlob;

/**
 * Copied from the Harmony project's implementation of javax.sql.rowset.serial.SerialBlob
 * at subversion revision 946981.
 */
public class HarmonySerialBlob implements Blob, BufferedBlob,
    Serializable, Cloneable {

    private static final long serialVersionUID = -8144641928112860441L;

    // required by serialized form
    private byte[] buf;

    // required by serialized form
// GemStone changes BEGIN
    private int len;
    /* (original code)
    private Blob blob;

    // required by serialized form
    private long len;

// GemStone changes BEGIN
    // required by serialized form
    private long origLen;

    /**
     * Constructs an instance by the given <code>blob</code>
     * 
     * @param blob
     *            the given blob
     * @throws SQLException
     *             if an error is encountered during serialization
     * @throws SQLException
     *             if <code>blob</code> is null
     *
    public HarmonySerialBlob(Blob blob) throws SQLException {
        if (blob == null) { throw new IllegalArgumentException(); }
        
        this.blob = blob;
        buf = blob.getBytes(1, (int) blob.length());
        len = buf.length;
        //origLen = len;
    }
// GemStone changes END

    /**
     * Constructs an instance by the given <code>buf</code>
     * 
     * @param buf
     *            the given buffer
     */
    public HarmonySerialBlob(byte[] buf) /* GemStone change throws SQLException */{
        this.buf = new byte[buf.length];
        len = buf.length;
        //origLen = len;
        System.arraycopy(buf, 0, this.buf, 0, len);
    }
// GemStone changes BEGIN
    public HarmonySerialBlob(OffHeapByteSource ohbs) {
      this.buf = ohbs.getRowBytes();
      this.len = this.buf.length;
      // no need to make a copy of buf since getRowBytes gave us our own copy.
    }

    /**
     * Constructs an instance by the given <code>buf</code> without making
     * a copy.
     * 
     * @param buf
     *          the buffer to wrap
     * @param len
     *          the length of buffer
     */
    private HarmonySerialBlob(byte[] buf, int len) {
        this.buf = buf;
        this.len = len;
    }

    /**
     * Constructs an instance by the given <code>buf</code> without making
     * a copy.
     * 
     * @param buf
     *          the given buffer
     */
    public static HarmonySerialBlob wrapBytes(byte[] buf) {
        return new HarmonySerialBlob(buf, buf.length);
    }
// GemStone changes END

    /**
     * Returns an input stream of this SerialObject.
     * 
     * @throws SQLException
     *             if an error is encountered
     */
    public InputStream getBinaryStream() throws SQLException {
        checkValidation(); // GemStoneAddition
        return new ByteArrayInputStream(buf);
    }

    /**
     * Returns a copied array of this SerialObject, starting at the
     * <code> pos </code> with the given <code> length</code> number. If
     * <code> pos </code> + <code> length </code> - 1 is larger than the length
     * of this SerialObject array, the <code> length </code> will be shortened
     * to the length of array - <code>pos</code> + 1.
     * 
     * @param pos
     *            the starting position of the array to be copied.
     * @param length
     *            the total length of bytes to be copied
     * @throws SQLException
     *             if an error is encountered
     */
    public byte[] getBytes(long pos, int length) throws SQLException {

// GemStone changes BEGIN
        checkValidation();
        if (pos < 1 || pos > (len + 1))
        /* (original code)
        if (pos < 1 || pos > len)
        */
// GemStone changes END
        {
            throw makeSQLException( SQLState.BLOB_BAD_POSITION, new Object[] {new Long(pos)} );
        }
        if (length < 0)
        {
            throw makeSQLException( SQLState.BLOB_NONPOSITIVE_LENGTH, new Object[] {new Integer(length)} );
        }

        if (length > len - pos + 1) {
            length = (int) (len - pos + 1);
        }
        byte[] copiedArray = new byte[length];
        System.arraycopy(buf, (int) pos - 1, copiedArray, 0, length);
        return copiedArray;
    }

    /**
     * Gets the number of bytes in this SerialBlob object.
     * 
     * @return an long value with the length of the SerialBlob in bytes
     * @throws SQLException
     *             if an error is encoutnered
     */
    public long length() throws SQLException {
        checkValidation(); // GemStoneAddition
        return len;
    }

    /**
     * Search for the position in this Blob at which a specified pattern begins,
     * starting at a specified position within the Blob.
     * 
     * @param pattern
     *            a Blob containing the pattern of data to search for in this
     *            Blob
     * @param start
     *            the position within this Blob to start the search, where the
     *            first position in the Blob is 1
     * @return a long value with the position at which the pattern begins. -1 if
     *         the pattern is not found in this Blob.
     * @throws SQLException
     *             if an error occurs accessing the Blob
     * @throws SQLException
     *             if an error is encountered
     */
    public long position(Blob pattern, long start) throws SQLException {
        checkValidation(); // GemStoneAddition
        byte[] patternBytes = pattern.getBytes(1, (int) pattern.length());
        return position(patternBytes, start);
    }

    /**
     * Search for the position in this Blob at which the specified pattern
     * begins, starting at a specified position within the Blob.
     * 
     * @param pattern
     *            a byte array containing the pattern of data to search for in
     *            this Blob
     * @param start
     *            the position within this Blob to start the search, where the
     *            first position in the Blob is 1
     * @return a long value with the position at which the pattern begins. -1 if
     *         the pattern is not found in this Blob.
     * @throws SQLException
     *             if an error is encountered
     * @throws SQLException
     *             if an error occurs accessing the Blob
     */
    public long position(byte[] pattern, long start) throws SQLException {
        checkValidation(); // GemStoneAddition
        if (start < 1 || len - (start - 1) < pattern.length) {
            return -1;
        }

        for (int i = (int) (start - 1); i <= (len - pattern.length); ++i) {
            if (match(buf, i, pattern)) {
                return i + 1;
            }
        }
        return -1;
    }

    /*
     * Returns true if the bytes array contains exactly the same elements from
     * start position to start + subBytes.length as subBytes. Otherwise returns
     * false.
     */
    private boolean match(byte[] bytes, int start, byte[] subBytes) {
        for (int i = 0; i < subBytes.length;) {
            if (bytes[start++] != subBytes[i++]) {
                return false;
            }
        }
        return true;
    }

    public OutputStream setBinaryStream(long pos) throws SQLException {
// GemStone changes BEGIN
        checkValidation();
        throw Util.generateCsSQLException(SQLState.NOT_IMPLEMENTED,
            "SerialBlob: setBinaryStream");
        /* (original code)
        if (blob == null) { throw new IllegalStateException(); }
        OutputStream os = blob.setBinaryStream(pos);
        if (os == null) { throw new IllegalStateException(); }
        return os;
        */
// GemStone changes END
    }

    public int setBytes(long pos, byte[] theBytes) throws SQLException {
        return setBytes(pos, theBytes, 0, theBytes.length);
    }

    public int setBytes(long pos, byte[] theBytes, int offset, int length)
            throws SQLException {
// GemStone changes BEGIN
        checkValidation();
        if (pos < 1 || length < 0) {
            throw makeSQLException(SQLState.BLOB_BAD_POSITION,
                new Object[] {new Long(pos)});
        }
        if (pos > (len + 1)) {
            throw makeSQLException(SQLState.BLOB_POSITION_TOO_LARGE,
                new Object[] {new Long(pos)});
        }
        /* (original code)
        if (pos < 1 || length < 0 || pos > (len - length + 1))
        {
            throw makeSQLException( SQLState.BLOB_BAD_POSITION, new Object[] {new Long(pos)} );
        }
        */
// GemStone changes END
        if (offset < 0 || length < 0 || offset > (theBytes.length - length))
        {
            throw makeSQLException( SQLState.BLOB_INVALID_OFFSET, new Object[] {new Integer(offset)} );
        }
// GemStone changes BEGIN
        // expand the buffer if required
        if (pos > (len - length + 1)) {
          final long newLen = (length + pos - 1);
          if (newLen > Integer.MAX_VALUE) {
            throw makeSQLException(SQLState.BLOB_LENGTH_TOO_LONG,
                new Object[] { newLen });
          }
          final byte[] newBuf = new byte[(int)newLen];
          System.arraycopy(this.buf, 0, newBuf, 0, this.len);
          this.buf = newBuf;
          this.len = (int)newLen;
        }
// GemStone changes END
        System.arraycopy(theBytes, offset, buf, (int) pos - 1, length);
        return length;
    }

    public void truncate(long length) throws SQLException {
        checkValidation(); // GemStoneAddition
        if (length > this.len)
        {
            throw makeSQLException( SQLState.BLOB_LENGTH_TOO_LONG, new Object[] {new Long(len)} );
        }
// GemStone changes BEGIN
      if (length < this.len) {
// GemStone changes END
        buf = getBytes(1, (int) length);
// GemStone changes BEGIN
        len = (int)length;
      }
// GemStone changes END
    }

    public void free() throws SQLException {
// GemStone changes BEGIN
        if (this.len != -1) {
          this.len = -1;
          this.buf = null;
        }
        /* (original code)
        throw new UnsupportedOperationException("Not supported");
        */
// GemStone changes END
    }

    public InputStream getBinaryStream(long pos, long length)
            throws SQLException {
        checkValidation(); // GemStoneAddition
        if (len < 0)
        {
            throw makeSQLException( SQLState.BLOB_NONPOSITIVE_LENGTH, new Object[] {new Long(len)} );
        }
        if (length < 0)
        {
            throw makeSQLException( SQLState.BLOB_NONPOSITIVE_LENGTH, new Object[] {new Long(length)} );
        }
        if (pos < 1 || pos + length > len)
        {
            throw makeSQLException( SQLState.POS_AND_LENGTH_GREATER_THAN_LOB, new Object[] {new Long(pos), new Long(length)} );
        }
        return new ByteArrayInputStream(buf, (int) (pos - 1), (int) length);
    }

    /**
     * Create a SQLException from Derby message arguments.
     */
    public static SQLException makeSQLException( String messageID, Object[] args )
    {
        StandardException se = StandardException.newException( messageID, args );

        return new SQLException( se.getMessage(), se.getSQLState() );
    }
// GemStone changes BEGIN
    @Override
    public BlobChunk getAsLastChunk() throws SQLException {
      return new BlobChunk(ByteBuffer.wrap(this.buf, 0, this.len), true);
    }

    private void checkValidation() throws SQLException {
      if (len == -1) {
        throw HarmonySerialBlob.makeSQLException(SQLState.LOB_OBJECT_INVALID,
            null);
      }
    }
// GemStone changes END
}
