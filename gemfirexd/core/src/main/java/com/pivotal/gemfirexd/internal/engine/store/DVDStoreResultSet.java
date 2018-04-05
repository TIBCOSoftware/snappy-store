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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;
import java.util.GregorianCalendar;

import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.pivotal.gemfirexd.callbacks.TableMetaData;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.io.NewByteArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.StreamStorable;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialBlob;
import com.pivotal.gemfirexd.internal.iapi.types.HarmonySerialClob;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.iapi.types.SQLDecimal;
import com.pivotal.gemfirexd.internal.impl.jdbc.ReaderToAscii;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.UTF8Reader;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;

/**
 * Encapsulates a {@link DataValueDescriptor} array row as as a
 * {@link ResultSet} that cannot be updated.
 * 
 * @author swale
 * @since 7.0
 */
public class DVDStoreResultSet extends NonUpdatableRowsResultSet implements
    ResultSet, ResultWasNull {

  protected DataValueDescriptor[] currentRowDVDs;

  private GenericParameterValueSet pvs;

  private final int numColumns;

  private final RowFormatter formatter;

  private final FormatableBitSet changedColumns;

  private TableMetaData metadata;

  private GregorianCalendar cal;
  
  private boolean nextCalledOnce = false;

  public DVDStoreResultSet(final DataValueDescriptor[] row, int numColumns,
      final RowFormatter rf, final FormatableBitSet changedColumns,
      final TableMetaData metadata) {
    this.metadata = metadata;
    this.formatter = rf;
    if (changedColumns != null) {
      this.changedColumns = changedColumns;
      this.numColumns = changedColumns.getNumBitsSet();
      // compact the row
      this.currentRowDVDs = new DataValueDescriptor[this.numColumns];
      int index = 0;
      for (int pos = changedColumns.anySetBit(); pos != -1; pos = changedColumns
          .anySetBit(pos)) {
        this.currentRowDVDs[index++] = row[pos];
      }
    }
    else {
      this.changedColumns = null;
      this.currentRowDVDs = row;
      this.numColumns = numColumns;
    }
  }

  public DVDStoreResultSet(final GenericParameterValueSet pvs,
      final TableMetaData metadata) {
    this.pvs = pvs;
    this.metadata = metadata;
    this.formatter = null;
    this.changedColumns = null;
    this.numColumns = this.pvs.getParameterCount();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean next() throws SQLException {
    boolean prevVal = this.nextCalledOnce;
    if(prevVal) {
      this.currentRowDVDs = null;
    }
    this.nextCalledOnce = true;
    return  !prevVal && this.currentRowDVDs != null ;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws SQLException {
    this.currentRowDVDs = null;
    this.pvs = null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isClosed() throws SQLException {
    return this.currentRowDVDs == null && this.pvs == null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getString(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null) {
          final String result = dvd.getString();
          this.wasNull = (result == null);
          return result;
        }
        else {
          this.wasNull = true;
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
  public Object getObject(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null) {
          final Object result = dvd.getObject();
          this.wasNull = (result == null);
          return result;
        }
        else {
          this.wasNull = true;
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
  public boolean getBoolean(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getBoolean();
        }
        else {
          this.wasNull = true;
          return false;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getByte();
        }
        else {
          this.wasNull = true;
          return 0;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getShort();
        }
        else {
          this.wasNull = true;
          return 0;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getInt();
        }
        else {
          this.wasNull = true;
          return 0;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getLong();
        }
        else {
          this.wasNull = true;
          return 0;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getFloat();
        }
        else {
          this.wasNull = true;
          return 0.0f;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getDouble();
        }
        else {
          this.wasNull = true;
          return 0.0;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getBytes();
        }
        else {
          this.wasNull = true;
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
  public Date getDate(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getDate(getCal());
        }
        else {
          this.wasNull = true;
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
  public Time getTime(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getTime(getCal());
        }
        else {
          this.wasNull = true;
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
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return dvd.getTimestamp(getCal());
        }
        else {
          this.wasNull = true;
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
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return SQLDecimal.getBigDecimal(dvd);
        }
        else {
          this.wasNull = true;
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
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          if (cal == null) {
            cal = getCal();
          }
          return dvd.getDate(cal);
        }
        else {
          this.wasNull = true;
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
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          if (cal == null) {
            cal = getCal();
          }
          return dvd.getTime(cal);
        }
        else {
          this.wasNull = true;
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
  public Timestamp getTimestamp(int columnIndex, Calendar cal)
      throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          if (cal == null) {
            cal = getCal();
          }
          return dvd.getTimestamp(cal);
        }
        else {
          this.wasNull = true;
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
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      final TableMetaData metadata = getMetaData();
      final int colType = metadata.getColumnType(columnIndex);
      switch (colType) {
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case JDBC40Translation.JSON:
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          final StreamStorable ss = (StreamStorable)dvd;
          final InputStream stream = ss.returnStream();
          if (stream != null) {
            return stream;
          }
          else {
            bytes = dvd.getBytes();
          }
        }
        else {
          this.wasNull = true;
          return null;
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          final StreamStorable ss = (StreamStorable)dvd;
          final InputStream is = ss.returnStream();
          if (is != null) {
            return new UTF8Reader(is, 0, null, is);
          }
          else {
            str = dvd.getString();
          }
        }
        else {
          this.wasNull = true;
          return null;
        }
        if (str != null) {
          return new StringReader(str);
        }
        else {
          return null;
        }
      } catch (StandardException se) {
        throw Util.generateCsSQLException(se);
      } catch (IOException ioe) {
        throw TransactionResourceImpl.wrapInSQLException(ioe);
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
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          Object result = dvd.getObject();
          return result instanceof byte[]
              ? HarmonySerialBlob.wrapBytes((byte[])result) : (Blob)result;
        }
        else {
          this.wasNull = true;
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
  public Clob getClob(int columnIndex) throws SQLException {
    if (columnIndex > 0 && columnIndex <= this.numColumns) {
      final TableMetaData metadata = getMetaData();
      if (metadata.getColumnType(columnIndex) != Types.CLOB) {
        throw dataConversionException(metadata.getColumnTypeName(columnIndex),
            "Clob", columnIndex);
      }
      try {
        final DataValueDescriptor dvd;
        if (this.currentRowDVDs != null) {
          dvd = this.currentRowDVDs[columnIndex - 1];
        }
        else if (this.pvs != null) {
          dvd = this.pvs.getParameter(columnIndex - 1);
        }
        else {
          throw noCurrentRow();
        }
        if (dvd != null && !dvd.isNull()) {
          this.wasNull = false;
          return HarmonySerialClob.wrapChars(((SQLChar)dvd).getCharArray(true));
        }
        else {
          this.wasNull = true;
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
        final int[] changedCols = new int[this.numColumns];
        int index = 0;
        for (int colPos = this.changedColumns.anySetBit(); colPos != -1;
            colPos = this.changedColumns.anySetBit(colPos)) {
          changedCols[index++] = colPos + 1; // make position 1-based
        }
        return (this.metadata = new ProjectionMetaData(
            this.formatter.getMetaData(), changedCols));
      }
    }
    else {
      return null;
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder().append("DVDResultSet{");
    for (int i = 1; i <= this.numColumns; i++) {
      try {
        sb.append(getObject(i)).append(',');
      } catch (SQLException sqle) {
        throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
      }
    }
    sb.setCharAt(sb.length() - 1, '}');
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
