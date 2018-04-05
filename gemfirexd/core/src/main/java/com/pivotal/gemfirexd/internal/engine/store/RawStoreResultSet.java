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
package com.pivotal.gemfirexd.internal.engine.store;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;

import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.pivotal.gemfirexd.callbacks.TableMetaData;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.NewByteArrayInputStream;
import com.pivotal.gemfirexd.internal.impl.jdbc.ReaderToAscii;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import io.snappydata.ResultSetWithNull;

import static com.pivotal.gemfirexd.internal.engine.store.RowFormatter.OFFSET_AND_WIDTH_IS_NULL;

/**
 * Encapsulates a set of one or more rows in raw underlying storage format that
 * cannot be updated as a {@link ResultSet}.
 * 
 * @author swale
 * @since 7.0
 */
public final class RawStoreResultSet extends NonUpdatableRowsResultSet
    implements ResultSet, ResultWasNull, ResultSetWithNull {

  private byte[] currentRowBytes;

  private byte[][] currentRowByteArrays;

  private Iterator<Object> rows;

  private final int numColumns;

  private final GemFireContainer container;

  private RowFormatter formatter;

  private int[] changedColumns;

  private TableMetaData metadata;

  private GregorianCalendar cal;

  private boolean nextCalledForOneRowResult = false;

  public RawStoreResultSet(final byte[] row, final RowFormatter rf) {
    this.currentRowBytes = row;
    this.container = null;
    this.formatter = rf;
    this.numColumns = rf.getNumColumns();
  }

  public RawStoreResultSet(final byte[] row, final RowFormatter rf,
      final int[] changedColumns, final TableMetaData metadata) {
    this.currentRowBytes = row;
    this.container = null;
    this.formatter = rf;
    this.metadata = metadata;
    this.changedColumns = changedColumns;
    this.numColumns = changedColumns.length;
  }

  public RawStoreResultSet(final byte[][] row, final RowFormatter rf) {
    this.currentRowByteArrays = row;
    this.container = null;
    this.formatter = rf;
    this.numColumns = rf.getNumColumns();
  }

  public RawStoreResultSet(final byte[][] row, final RowFormatter rf,
      final int[] changedColumns, final TableMetaData metadata) {
    this.currentRowByteArrays = row;
    this.container = null;
    this.formatter = rf;
    this.metadata = metadata;
    this.changedColumns = changedColumns;
    this.numColumns = changedColumns.length;
  }

  public RawStoreResultSet(final Iterator<Object> rows,
      final GemFireContainer container, final RowFormatter rf) {
    this.rows = rows;
    this.container = container;
    this.formatter = rf;
    this.numColumns = rf.getNumColumns();
  }

  public RawStoreResultSet(final Iterator<Object> rows, final RowFormatter rf,
      final int[] changedColumns, final TableMetaData metadata) {
    this.rows = rows;
    this.container = null;
    this.formatter = rf;
    this.metadata = metadata;
    this.changedColumns = changedColumns;
    this.numColumns = changedColumns.length;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean next() throws SQLException {
    if (this.rows != null) {
      if (this.rows.hasNext()) {
        final GemFireContainer container = this.container;
        final Object next = this.rows.next();
        if (next.getClass() == byte[].class) {
          this.currentRowBytes = (byte[])next;
          if (container != null) {
            this.formatter = container.getRowFormatter(currentRowBytes);
          }
        }
        else {
          this.currentRowByteArrays = (byte[][])next;
          if (container != null) {
            this.formatter = container.getRowFormatter(currentRowByteArrays);
          }
        }
        return true;
      }
      else {
        return false;
      }
    }
    else {
      boolean prevVal = this.nextCalledForOneRowResult;
      if (prevVal) {
        this.currentRowByteArrays = null;
        this.currentRowBytes = null;
      }
      this.nextCalledForOneRowResult = true;
      return !prevVal
          && (this.currentRowByteArrays != null || this.currentRowBytes != null);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {
    this.currentRowBytes = null;
    this.currentRowByteArrays = null;
    this.rows = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isClosed() throws SQLException {
    return this.currentRowBytes == null && this.currentRowByteArrays == null
        && this.rows == null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getString(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsString(columnIndex,
                this.currentRowBytes, this);
          }
          else {
            return this.formatter.getAsString(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsString(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsString(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object getObject(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsObject(columnIndex,
                this.currentRowBytes, this);
          }
          else {
            return this.formatter.getAsObject(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsObject(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsObject(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsBoolean(columnIndex,
                this.currentRowBytes, this);
          }
          else {
            return this.formatter.getAsBoolean(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsBoolean(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsBoolean(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte getByte(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsByte(columnIndex, this.currentRowBytes,
                this);
          }
          else {
            return this.formatter.getAsByte(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsByte(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsByte(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public short getShort(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsShort(columnIndex, this.currentRowBytes,
                this);
          }
          else {
            return this.formatter.getAsShort(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsShort(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsShort(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getInt(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsInt(columnIndex, this.currentRowBytes,
                this);
          }
          else {
            return this.formatter.getAsInt(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsInt(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsInt(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long getLong(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsLong(columnIndex, this.currentRowBytes,
                this);
          }
          else {
            return this.formatter.getAsLong(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsLong(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsLong(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getFloat(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsFloat(columnIndex, this.currentRowBytes,
                this);
          }
          else {
            return this.formatter.getAsFloat(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsFloat(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsFloat(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double getDouble(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsDouble(columnIndex,
                this.currentRowBytes, this);
          }
          else {
            return this.formatter.getAsDouble(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsDouble(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsDouble(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal getBigDecimal(int columnIndex, int scale)
      throws SQLException {
    BigDecimal ret = getBigDecimal(columnIndex);
    if (ret != null) {
      return ret.setScale(scale, BigDecimal.ROUND_HALF_DOWN);
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsBytes(columnIndex, this.currentRowBytes,
                this);
          }
          else {
            return this.formatter.getAsBytes(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsBytes(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsBytes(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date getDate(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        final Calendar cal = getCal();
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsDate(columnIndex, this.currentRowBytes,
                cal, this);
          }
          else {
            return this.formatter
                .getAsDate(this.changedColumns[columnIndex - 1],
                    this.currentRowBytes, cal, this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsDate(columnIndex,
                this.currentRowByteArrays, cal, this);
          }
          else {
            return this.formatter.getAsDate(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, cal, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Time getTime(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        final Calendar cal = getCal();
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsTime(columnIndex, this.currentRowBytes,
                cal, this);
          }
          else {
            return this.formatter
                .getAsTime(this.changedColumns[columnIndex - 1],
                    this.currentRowBytes, cal, this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsTime(columnIndex,
                this.currentRowByteArrays, cal, this);
          }
          else {
            return this.formatter.getAsTime(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, cal, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        final Calendar cal = getCal();
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsTimestamp(columnIndex,
                this.currentRowBytes, cal, this);
          }
          else {
            return this.formatter
                .getAsTimestamp(this.changedColumns[columnIndex - 1],
                    this.currentRowBytes, cal, this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsTimestamp(columnIndex,
                this.currentRowByteArrays, cal, this);
          }
          else {
            return this.formatter.getAsTimestamp(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, cal, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsBigDecimal(columnIndex,
                this.currentRowBytes, this);
          }
          else {
            return this.formatter.getAsBigDecimal(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsBigDecimal(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            return this.formatter.getAsBigDecimal(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (cal == null) {
          cal = getCal();
        }
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsDate(columnIndex, this.currentRowBytes,
                cal, this);
          }
          else {
            return this.formatter
                .getAsDate(this.changedColumns[columnIndex - 1],
                    this.currentRowBytes, cal, this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsDate(columnIndex,
                this.currentRowByteArrays, cal, this);
          }
          else {
            return this.formatter.getAsDate(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, cal, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (cal == null) {
          cal = getCal();
        }
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsTime(columnIndex, this.currentRowBytes,
                cal, this);
          }
          else {
            return this.formatter
                .getAsTime(this.changedColumns[columnIndex - 1],
                    this.currentRowBytes, cal, this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsTime(columnIndex,
                this.currentRowByteArrays, cal, this);
          }
          else {
            return this.formatter.getAsTime(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, cal, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal)
      throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        this.wasNull = false;
        if (cal == null) {
          cal = getCal();
        }
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getAsTimestamp(columnIndex,
                this.currentRowBytes, cal, this);
          }
          else {
            return this.formatter
                .getAsTimestamp(this.changedColumns[columnIndex - 1],
                    this.currentRowBytes, cal, this);
          }
        }
        else {
          if (this.changedColumns == null) {
            return this.formatter.getAsTimestamp(columnIndex,
                this.currentRowByteArrays, cal, this);
          }
          else {
            return this.formatter.getAsTimestamp(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, cal, this);
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      final TableMetaData metadata = getMetaData();
      final int colType = metadata.getColumnType(columnIndex);
      switch (colType) {
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case Types.CLOB: // Embedded and JCC extension
          break;

        // JDBC says to support these, we match JCC by returning the raw bytes.
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BLOB:
          return getBinaryStream(columnIndex);

        default:
          throw dataConversionException(
              metadata.getColumnTypeName(columnIndex), "InputStream(ASCII)",
              columnIndex);
      }

      final Reader reader = getCharacterStream(columnIndex);
      if (reader != null) {
        return new ReaderToAscii(reader);
      }
      else {
        return null;
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    throw Util.notImplemented("getUnicodeStream");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      final TableMetaData metadata = getMetaData();
      final int columnType = metadata.getColumnType(columnIndex);
      switch (columnType) {
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BLOB:
          break;

        default:
          throw dataConversionException(
              metadata.getColumnTypeName(columnIndex), "InputStream(BINARY)",
              columnIndex);
      }
      try {
        final byte[] bytes;
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            bytes = this.formatter.getAsBytes(columnIndex,
                this.currentRowBytes, this);
          }
          else {
            bytes = this.formatter.getAsBytes(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            bytes = this.formatter.getAsBytes(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            bytes = this.formatter.getAsBytes(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
        if (bytes != null) {
          return new NewByteArrayInputStream(bytes);
        }
        else {
          return null;
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      final TableMetaData metadata = getMetaData();
      final int colType = metadata.getColumnType(columnIndex);
      switch (colType) {
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case Types.CLOB: // Embedded and JCC extension
          break;

        // JDBC says to support these, we match JCC by returning the raw bytes.
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BLOB:
          final InputStream is = getBinaryStream(columnIndex);
          if (is != null) {
            try {
              return new InputStreamReader(is, "UTF-16BE");
            } catch (UnsupportedEncodingException uee) {
              throw TransactionResourceImpl.wrapInSQLException(uee);
            }
          }
          else {
            return null;
          }

        default:
          throw dataConversionException(
              metadata.getColumnTypeName(columnIndex), "Reader", columnIndex);
      }
      try {
        final String str;
        this.wasNull = false;
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            str = this.formatter.getAsString(columnIndex, this.currentRowBytes,
                this);
          }
          else {
            str = this.formatter.getAsString(
                this.changedColumns[columnIndex - 1], this.currentRowBytes,
                this);
          }
        }
        else {
          if (this.changedColumns == null) {
            str = this.formatter.getAsString(columnIndex,
                this.currentRowByteArrays, this);
          }
          else {
            str = this.formatter.getAsString(
                this.changedColumns[columnIndex - 1],
                this.currentRowByteArrays, this);
          }
        }
        if (str != null) {
          return new StringReader(str);
        }
        else {
          return null;
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Blob getBlob(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      final TableMetaData metadata = getMetaData();
      if (metadata.getColumnType(columnIndex) != Types.BLOB) {
        throw dataConversionException(metadata.getColumnTypeName(columnIndex),
            "Blob", columnIndex);
      }
      try {
        this.wasNull = false;
        return this.formatter.getAsBlob(columnIndex, this.currentRowByteArrays,
            this);
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Clob getClob(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      final TableMetaData metadata = getMetaData();
      if (metadata.getColumnType(columnIndex) != Types.CLOB) {
        throw dataConversionException(metadata.getColumnTypeName(columnIndex),
            "Clob", columnIndex);
      }
      try {
        this.wasNull = false;
        return this.formatter.getAsClob(columnIndex, this.currentRowByteArrays,
            this);
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    }
    else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isNull(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        if (this.currentRowBytes != null) {
          if (this.changedColumns == null) {
            return this.formatter.getOffsetAndWidth(columnIndex,
                this.currentRowBytes) == OFFSET_AND_WIDTH_IS_NULL;
          } else {
            return this.formatter.getOffsetAndWidth(
                this.changedColumns[columnIndex - 1], this.currentRowBytes) ==
                OFFSET_AND_WIDTH_IS_NULL;
          }
        } else {
          CompactExecRowWithLobs execRow = new CompactExecRowWithLobs(
              this.currentRowByteArrays, this.formatter);
          if (this.changedColumns == null) {
            return execRow.isNull(columnIndex) == OFFSET_AND_WIDTH_IS_NULL;
          } else {
            return execRow.isNull(columnIndex) == OFFSET_AND_WIDTH_IS_NULL;
          }
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      }
    } else {
      throw invalidColumnException(columnIndex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int findColumn(String columnLabel) throws SQLException {
    return getMetaData().getColumnPosition(columnLabel);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public TableMetaData getMetaData() throws SQLException {
    if (this.metadata != null) {
      return this.metadata;
    }
    if (this.formatter != null) {
      if (this.changedColumns == null) {
        return (this.metadata = this.formatter.getMetaData());
      }
      else {
        return (this.metadata = new ProjectionMetaData(
            this.formatter.getMetaData(), this.changedColumns));
      }
    }
    else {
      return null;
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder().append("RawResultSet");
    try {
      boolean hasNext = (this.rows != null ? next() : true);
      while (hasNext) {
        sb.append(" {");
        for (int i = 1; i <= this.numColumns; i++) {
          sb.append(getObject(i)).append(',');
        }
        sb.setCharAt(sb.length() - 1, '}');
        hasNext = next();
      }
    } catch (SQLException sqle) {
      throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
    }
    return sb.toString();
  }

  private GregorianCalendar getCal() {
    final GregorianCalendar cal = this.cal;
    if (cal != null) {
      cal.clear();
      return cal;
    }
    return (this.cal = ClientSharedData.getDefaultCleanCalendar());
  }
}
