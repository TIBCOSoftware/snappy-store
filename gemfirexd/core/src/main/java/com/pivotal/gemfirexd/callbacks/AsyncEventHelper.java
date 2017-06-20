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

package com.pivotal.gemfirexd.callbacks;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Formatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC40Translation;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Some utility methods for {@link AsyncEventListener} and WAN. Note that since
 * this is also used by the GemFireXD WAN layer, users should not change this
 * class, rather should extend it and then modify for custom changes to
 * {@link DBSynchronizer}.
 */
public class AsyncEventHelper {

  /** the name of the {@link Logger} used by GemFireXD */
  public static final String LOGGER_NAME = ClientSharedUtils.LOGGER_NAME;

  /** prefix for SQLStates of integrity violations */
  public static final String INTEGRITY_VIOLATION_PREFIX =
      SQLState.INTEGRITY_VIOLATION_PREFIX;
  /** prefix for SQLStates of connection exceptions */
  public static final String CONNECTIVITY_PREFIX =
      SQLState.CONNECTIVITY_PREFIX;
  /** prefix for SQLStates of syntax and other errors during query prepare */
  public static final String LSE_COMPILATION_PREFIX =
      SQLState.LSE_COMPILATION_PREFIX;

  /** the maximum size of BLOBs that will be kept in memory */
  static final int MAX_MEM_BLOB_SIZE = 128 * 1024 * 1024;
  /** the maximum size of CLOBs that will be kept in memory */
  static final int MAX_MEM_CLOB_SIZE = (MAX_MEM_BLOB_SIZE >>> 1);

  /**
   * When an event fails, then this keeps the last time when a failure was
   * logged. We don't want to swamp the logs in retries due to a syntax error,
   * for example, but we don't want to remove it from the retry map either lest
   * all subsequent dependent DMLs may start failing.
   */
  private final ConcurrentHashMap<Event, long[]> failureLogInterval =
      new ConcurrentHashMap<Event, long[]>(16, 0.75f, 2);

  /**
   * The maximum size of {@link #failureLogInterval} beyond which it will start
   * logging all failure instances. Hopefully this should never happen in
   * practise.
   */
  protected static final int FAILURE_MAP_MAXSIZE = Integer.getInteger(
      "gemfirexd.asyncevent.FAILURE_MAP_MAXSIZE", 1000000);

  /**
   * The maximum interval for logging failures of the same event in millis.
   */
  protected static final int FAILURE_LOG_MAX_INTERVAL = Integer.getInteger(
      "gemfirexd.asyncevent.FAILURE_LOG_MAX_INTERVAL", 300000);
  
  public static final boolean POSTGRESQL_SYNTAX = Boolean.getBoolean(
      "gemfirexd.asyncevent.POSTGRESQL_SYNTAX");
  
  public static final String EVENT_ERROR_LOG_FILE = "dbsync_failed_dmls.xml";
  public static final String EVENT_ERROR_LOG_ENTRIES_FILE = "dbsync_failed_dmls_entries.xml";
  
  private EventErrorLogger evErrorLogger;

  public static AsyncEventHelper newInstance() {
    return new AsyncEventHelper();
  }

  /**
   * Append the backtrace of given exception to provided {@link StringBuilder}.
   * 
   * @param t
   *          the exception whose backtrace is required
   * @param sb
   *          the {@link StringBuilder} to which the stack trace is to be
   *          appended
   */
  public final void getStackTrace(Throwable t, StringBuilder sb) {
    ClientSharedUtils.getStackTrace(t, sb, null);
  }

  /**
   * Returns true if fine-level logging is enabled in this GemFireXD instance.
   */
  public final boolean logFineEnabled() {
    return SanityManager.isFineEnabled;
  }

  /**
   * Returns true if minimal execution tracing is enabled in this GemFireXD
   * instance.
   */
  public final boolean traceExecute() {
    return GemFireXDUtils.TraceExecution;
  }

  /**
   * Returns true if "TraceDBSynchronizer" debug flag is enabled in this GemFireXD
   * instance (via gemfirexd.debug.true=TraceDBSynchronizer).
   */
  public final boolean traceDBSynchronizer() {
    return GemFireXDUtils.TraceDBSynchronizer;
  }

  /**
   * Returns true if "TraceDBSynchronizerHA" debug flag is enabled in this GemFireXD
   * instance (via gemfirexd.debug.true=TraceDBSynchronizerHA).
   */
  public final boolean traceDBSynchronizerHA() {
    return GemFireXDUtils.TraceDBSynchronizerHA;
  }
  
  /**
   * Log the given message and exception to the provided logger.
   * 
   * @param logger
   *          the {@link Logger} to log the message to
   * @param level
   *          the {@link Level} to use for logging the message
   * @param t
   *          the exception whose backtrace is to be logged; can be null in
   *          which case it is ignored
   * @param message
   *          the message to be logged
   */
  public final void log(Logger logger, Level level, Throwable t,
      final String message) {
    if (t != null) {
      StringBuilder sb = new StringBuilder();
      sb.append(message).append(": ");
      getStackTrace(t, sb);
      logger.log(level, sb.toString());
    }
    else {
      logger.log(level, message);
    }
  }

  /**
   * Log the given formatted message and exception to the provided logger. The
   * format expected is the same as supported by
   * {@link String#format(String, Object...)}.
   * 
   * @param logger
   *          the {@link Logger} to log the message to
   * @param level
   *          the {@link Level} to use for logging the message
   * @param t
   *          the exception whose backtrace is to be logged; can be null in
   *          which case it is ignored
   * @param format
   *          the message format
   * @param params
   *          the parameters to the message format
   * 
   * @see String#format(String, Object...)
   * @see Formatter#format(String, Object...)
   */
  public final void logFormat(Logger logger, Level level, Throwable t,
      String format, Object... params) {
    StringBuilder sb = new StringBuilder();
    Formatter fmt = new Formatter(sb);
    fmt.format(format, params);
    if (t != null) {
      sb.append(": ");
      getStackTrace(t, sb);
    }
    logger.log(level, sb.toString());
    fmt.close();
  }

  /**
   * Get a DML string that can be used to insert rows in given table. Any
   * auto-increment columns are skipped from the insert statement assuming that
   * they have to be re-generated by the backend database.
   * 
   * @param tableName
   *          name of the table
   * @param tableMetaData
   *          meta-data of the columns of the table
   * @param hasAutoIncrementColumns
   *          should be true if table has any auto-increment columns and those
   *          are to be skipped in the insert string
   * 
   * @throws SQLException
   *           in case of an error in getting column information from table
   *           meta-data
   */
  public static final String getInsertString(String tableName,
      TableMetaData tableMetaData, boolean hasAutoIncrementColumns)
      throws SQLException {
    // skip identity columns, if any
    StringBuilder sbuff = new StringBuilder().append("insert into ");
    // remove quotes from tableName
    if (POSTGRESQL_SYNTAX) {
      String unQuotedTableName = tableName.replace("\"", "");
      sbuff.append(unQuotedTableName);
    } else {
      sbuff.append(tableName);
    }
    
    final int numColumns = tableMetaData.getColumnCount();
    // need to specify only the non-autogen column names
    sbuff.append('(');
    for (int pos = 1; pos <= numColumns; pos++) {
      if (!hasAutoIncrementColumns || !tableMetaData.isAutoIncrement(pos)) {
        sbuff.append(tableMetaData.getColumnName(pos)).append(',');
      }
    }
    sbuff.setCharAt(sbuff.length() - 1, ')');
    sbuff.append(" values (");
    for (int pos = 1; pos <= numColumns; pos++) {
      if (!hasAutoIncrementColumns || !tableMetaData.isAutoIncrement(pos)) {
        sbuff.append("?,");
      }
    }
    sbuff.setCharAt(sbuff.length() - 1, ')');
    return sbuff.toString();
  }

  /**
   * Get a DML string that can be used to delete rows in given table. Caller
   * needs to pass the primary-key column information as a
   * {@link ResultSetMetaData} obtained from
   * {@link Event#getPrimaryKeysAsResultSet()}.
   * 
   * @param tableName
   *          name of the table
   * @param pkMetaData
   *          meta-data of the primary key columns of the table
   * 
   * @throws SQLException
   *           in case of an error in getting column information from primary
   *           key meta-data
   */
  public static final String getDeleteString(String tableName,
      ResultSetMetaData pkMetaData) throws SQLException {
    StringBuilder sbuff = new StringBuilder().append("delete from ");
    // remove quotes from tableName
    if (POSTGRESQL_SYNTAX) {
      String unQuotedTableName = tableName.replace("\"", "");
      sbuff.append(unQuotedTableName);
    } else {
      sbuff.append(tableName);
    }
    sbuff.append(" where ");
    // use the primary key columns to fire the delete on backend DB;
    // tables having no primary keys are received as BULK_DML statements
    // and will not reach here
    final int numCols = pkMetaData.getColumnCount();
    for (int col = 1; col < numCols; col++) {
      sbuff.append(pkMetaData.getColumnName(col));
      sbuff.append("=? and ");
    }
    sbuff.append(pkMetaData.getColumnName(numCols));
    sbuff.append("=?");
    return sbuff.toString();
  }

  /**
   * Get a DML string that can be used to update rows in given table. Caller
   * needs to pass the primary-key column information as a
   * {@link ResultSetMetaData} obtained from
   * {@link Event#getPrimaryKeysAsResultSet()}, and the meta-data of updated
   * columns as a {@link ResultSetMetaData} obtained from
   * {@link Event#getNewRowsAsResultSet()}.
   * 
   * @param tableName
   *          name of the table
   * @param pkMetaData
   *          meta-data of the primary key columns of the table
   * @param updateMetaData
   *          meta-data of the updated columns of the table
   * 
   * @throws SQLException
   *           in case of an error in getting column information from primary
   *           key meta-data, or from meta-data of updated columns
   */
  public static final String getUpdateString(String tableName,
      ResultSetMetaData pkMetaData, ResultSetMetaData updateMetaData)
      throws SQLException {
    StringBuilder sbuff = new StringBuilder().append("update ");
    // remove quotes from tableName
    if (POSTGRESQL_SYNTAX) {
      String unQuotedTableName = tableName.replace("\"", "");
      sbuff.append(unQuotedTableName);
    } else {
      sbuff.append(tableName);
    }
    sbuff.append(" set ");
    final int numPkCols = pkMetaData.getColumnCount();
    for (int col = 1; col <= updateMetaData.getColumnCount(); col++) {
      sbuff.append(updateMetaData.getColumnName(col));
      sbuff.append("=?,");
    }
    sbuff.setCharAt(sbuff.length() - 1, ' ');
    sbuff.append("where ");
    for (int col = 1; col < numPkCols; col++) {
      sbuff.append(pkMetaData.getColumnName(col));
      sbuff.append("=? and ");
    }
    sbuff.append(pkMetaData.getColumnName(numPkCols));
    sbuff.append("=?");
    return sbuff.toString();
  }

  /**
   * Set the parameters to the prepared statement for a
   * {@link Event.Type#BULK_DML} or {@link Event.Type#BULK_INSERT} operation.
   * The implementation creates a batch for {@link Event.Type#BULK_INSERT} and
   * also tries to add as a batch for {@link Event.Type#BULK_DML} in case the
   * previous prepared statement is same as this one.
   * 
   * @param event
   *          the {@link Event} object
   * @param evType
   *          the {@link Event.Type} of the event
   * @param ps
   *          the prepared statement to be used for prepare
   * @param prevPS
   *          the prepared statement used for the previous event; in case it is
   *          same as the current one the new update for a
   *          {@link Event.Type#BULK_DML} operation will be added as a batch
   * @param sync
   *          the {@link DBSynchronizer} object, if any; it is used to store
   *          whether the current driver is JDBC4 compliant to enable performing
   *          BLOB/CLOB operations {@link PreparedStatement#setBinaryStream},
   *          {@link PreparedStatement#setCharacterStream}
   * 
   * @return true if the event was {@link Event.Type#BULK_INSERT} and false
   *         otherwise
   * 
   * @throws SQLException
   *           in case of an exception in getting meta-data or setting
   *           parameters
   */
  public final boolean setParamsInBulkPreparedStatement(Event event,
      Event.Type evType, PreparedStatement ps, PreparedStatement prevPS,
      DBSynchronizer sync) throws SQLException {
    if (evType.isBulkInsert()) {
      // need to deserialize individual rows and execute as batch prepared
      // statement
      final ResultSetMetaData rsmd = event.getResultSetMetaData();
      final int numColumns = rsmd.getColumnCount();
      final ResultSet rows = event.getNewRowsAsResultSet();
      // prepared statement will already be set correctly to skip auto-increment
      // columns assuming getInsertString was used to get the SQL string
      final boolean skipAutoGenCols = (event.tableHasAutogeneratedColumns()
          && (sync == null || sync.skipIdentityColumns()));
      while (rows.next()) {
        int paramIndex = 1;
        for (int rowPos = 1; rowPos <= numColumns; rowPos++) {
          if (!skipAutoGenCols || !rsmd.isAutoIncrement(rowPos)) {
            setColumnInPrepStatement(rsmd.getColumnType(rowPos), ps, rows,
                rowPos, paramIndex, sync);
            paramIndex++;
          }
        }
        ps.addBatch();
      }
      return true;
    }
    else {
      if (prevPS == ps) {
        // add a new batch of values
        ps.addBatch();
      }
      final ResultSet params = event.getNewRowsAsResultSet();
      final ResultSetMetaData rsmd;
      final int columnCount;
      if (params != null
          && (columnCount = (rsmd = params.getMetaData()).getColumnCount()) > 0) {
        for (int paramIndex = 1; paramIndex <= columnCount; paramIndex++) {
          setColumnInPrepStatement(rsmd.getColumnType(paramIndex), ps, params,
              paramIndex, paramIndex, sync);
        }
      }
      return false;
    }
  }

  /**
   * Set column value at given index in a prepared statement. The implementation
   * tries using the matching underlying type to minimize any data type
   * conversions, and avoid creating wrapper Java objects (e.g. {@link Integer}
   * for primitive int).
   * 
   * @param javaSqlType
   *          the SQL type of the column as specified by JDBC {@link Types}
   *          class
   * @param ps
   *          the prepared statement where the column value has to be set
   * @param row
   *          the source row as a {@link ResultSet} from where the value has to
   *          be extracted
   * @param rowPosition
   *          the 1-based position of the column in the provided
   *          <code>row</code>
   * @param paramIndex
   *          the 1-based position of the column in the target prepared
   *          statement (provided <code>ps</code> argument)
   * @param sync
   *          the {@link DBSynchronizer} object, if any; it is used to store
   *          whether the current driver is JDBC4 compliant to enable performing
   *          BLOB/CLOB operations {@link PreparedStatement#setBinaryStream},
   *          {@link PreparedStatement#setCharacterStream}
   * 
   * @throws SQLException
   *           in case of an exception in setting parameters
   */
  public final void setColumnInPrepStatement(int javaSqlType,
      PreparedStatement ps, ResultSet row, int rowPosition, int paramIndex,
      final DBSynchronizer sync) throws SQLException {
    if (traceDBSynchronizer()) {
      final Logger logger = sync != null ? sync.logger : Logger
          .getLogger(LOGGER_NAME);
      logger.info("AsyncEventHelper::setColumnInPrepStatement: setting column "
          + "type=" + javaSqlType + " index=" + paramIndex + " value="
          + row.getObject(rowPosition) + ", for " + ps);
    }
    switch (javaSqlType) {
      case Types.ARRAY:
      case Types.DATALINK:
      case Types.DISTINCT:
      case Types.NCHAR:
      case Types.NCLOB:
      case Types.NULL:
      case Types.NVARCHAR:
      case Types.OTHER:
      case Types.REF:
      case Types.ROWID:
      case Types.STRUCT:
        throw new UnsupportedOperationException("java.sql.Type = "
            + javaSqlType + " not supported");

      case Types.BIGINT:
        final long longVal = row.getLong(rowPosition);
        if (!row.wasNull()) {
          ps.setLong(paramIndex, longVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.BIT:
      case Types.BOOLEAN:
        final boolean boolVal = row.getBoolean(rowPosition);
        if (!row.wasNull()) {
          ps.setBoolean(paramIndex, boolVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.BLOB:
        final Blob blob = row.getBlob(rowPosition);
        final long blen;
        if (blob != null) {
          if (sync == null) {
            ps.setBlob(paramIndex, blob);
            break;
          }
          else if ((blen = blob.length()) > MAX_MEM_BLOB_SIZE) {
            final byte isJDBC4Driver = sync.isJDBC4Driver();
            if (isJDBC4Driver == DBSynchronizer.JDBC4DRIVER_TRUE) {
              // The Oracle driver does not work with ps.setBlob
              // where it expects an Oracle Blob type
              ps.setBinaryStream(paramIndex, blob.getBinaryStream());
              break;
            }
            else if (isJDBC4Driver == DBSynchronizer.JDBC4DRIVER_UNKNOWN) {
              try {
                ps.setBinaryStream(paramIndex, blob.getBinaryStream(), blen);
                sync.setJDBC4Driver(DBSynchronizer.JDBC4DRIVER_TRUE);
                break;
              } catch (java.sql.SQLFeatureNotSupportedException e) {
              } catch (java.lang.AbstractMethodError e) {
              }
              sync.setJDBC4Driver(DBSynchronizer.JDBC4DRIVER_FALSE);
            }
          }
          // For small/medium Blobs and drivers which do not support
          // ps.setBinaryStream
          final byte[] bytes = blob.getBytes(1, (int)blen);
          ps.setBytes(paramIndex, bytes);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.CLOB:
        final Object clobVal = row.getObject(rowPosition);
        if (clobVal != null) {
          if (clobVal instanceof String) {
            // Ideally this should not be of type String
            ps.setString(paramIndex, (String)clobVal);
          }
          else {
            final Clob clob = (Clob)clobVal;
            final long len;
            if (sync == null) {
              ps.setClob(paramIndex, clob);
              break;
            }
            else if ((len = clob.length()) > MAX_MEM_CLOB_SIZE) {
              final byte isJDBC4Driver = sync.isJDBC4Driver();
              if (isJDBC4Driver == DBSynchronizer.JDBC4DRIVER_TRUE) {
                // The Oracle driver does not work with ps.setClob
                // where it expects an Oracle Clob type
                ps.setCharacterStream(paramIndex, clob.getCharacterStream());
                break;
              }
              else if (isJDBC4Driver == DBSynchronizer.JDBC4DRIVER_UNKNOWN) {
                try {
                  ps.setCharacterStream(paramIndex, clob.getCharacterStream(),
                      len);
                  sync.setJDBC4Driver(DBSynchronizer.JDBC4DRIVER_TRUE);
                  break;
                } catch (java.sql.SQLFeatureNotSupportedException e) {
                } catch (java.lang.AbstractMethodError e) {
                }
                sync.setJDBC4Driver(DBSynchronizer.JDBC4DRIVER_FALSE);
              }
            }
            // For small/medium Clobs and drivers which do not support
            // ps.setCharacterStream
            final String str = clob.getSubString(1, (int)len);
            ps.setString(paramIndex, str);
          }
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.DATE:
        final java.sql.Date dateVal = row.getDate(rowPosition);
        if (dateVal != null) {
          ps.setDate(paramIndex, dateVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.DECIMAL:
      case Types.NUMERIC:
        final BigDecimal decimalVal = row.getBigDecimal(rowPosition);
        if (decimalVal != null) {
          ps.setBigDecimal(paramIndex, decimalVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.DOUBLE:
        final double doubleVal = row.getDouble(rowPosition);
        if (!row.wasNull()) {
          ps.setDouble(paramIndex, doubleVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.FLOAT:
      case Types.REAL:
        final float floatVal = row.getFloat(rowPosition);
        if (!row.wasNull()) {
          ps.setFloat(paramIndex, floatVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.INTEGER:
      case Types.SMALLINT:
      case Types.TINYINT:
        final int intVal = row.getInt(rowPosition);
        if (!row.wasNull()) {
          ps.setInt(paramIndex, intVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.LONGNVARCHAR:
      case Types.LONGVARCHAR:
      case Types.VARCHAR:
      case Types.CHAR:
      case Types.SQLXML:
        final String strVal = row.getString(rowPosition);
        if (strVal != null) {
          ps.setString(paramIndex, strVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case JDBC40Translation.JSON:
          final String strVal2 = row.getString(rowPosition);
          ps.setString(paramIndex, strVal2);
        break;
      case Types.BINARY:
      case Types.LONGVARBINARY:
      case Types.VARBINARY:
        final byte[] bytesVal = row.getBytes(rowPosition);
        if (bytesVal != null) {
          ps.setBytes(paramIndex, bytesVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.TIME:
        final java.sql.Time timeVal = row.getTime(rowPosition);
        if (timeVal != null) {
          ps.setTime(paramIndex, timeVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.TIMESTAMP:
        final java.sql.Timestamp timestampVal = row.getTimestamp(rowPosition);
        if (timestampVal != null) {
          ps.setTimestamp(paramIndex, timestampVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      case Types.JAVA_OBJECT:
        final Object objVal = row.getObject(rowPosition);
        if (objVal != null) {
          ps.setObject(paramIndex, objVal);
        }
        else {
          ps.setNull(paramIndex, javaSqlType);
        }
        break;
      default:
        throw new UnsupportedOperationException("java.sql.Type = "
            + javaSqlType + " not supported");
    }
  }

  /**
   * Check if logging for the same event repeatedly has to be skipped. This
   * keeps a map of failed event against the last logged time and doubles the
   * time interval to wait before next logging on every subsequent call for the
   * same event.
   */
  public final boolean skipFailureLogging(Event event) {
    boolean skipLogging = false;
    // if map has become large then give up on new events but we don't expect
    // it to become too large in practise
    if (this.failureLogInterval.size() < FAILURE_MAP_MAXSIZE) {
      // first long in logInterval gives the last time when the log was done,
      // and the second tracks the current log interval to be used which
      // increases exponentially
      // multiple currentTimeMillis calls below may hinder performance
      // but not much to worry about since failures are expected to
      // be an infrequent occurance (and if frequent then we have to skip
      // logging for quite a while in any case)
      long[] logInterval = this.failureLogInterval.get(event);
      if (logInterval == null) {
        logInterval = this.failureLogInterval.putIfAbsent(event,
            new long[] { System.currentTimeMillis(), 1000 });
      }
      if (logInterval != null) {
        long currentTime = System.currentTimeMillis();
        if ((currentTime - logInterval[0]) < logInterval[1]) {
          skipLogging = true;
        }
        else {
          logInterval[0] = currentTime;
          // don't increase logInterval to beyond a limit (5 mins by default)
          if (logInterval[1] <= (FAILURE_LOG_MAX_INTERVAL / 4)) {
            logInterval[1] *= 4;
          }
          // TODO: should the retries be throttled by some sleep here?
        }
      }
    }
    return skipLogging;
  }

  /**
   * After a successful event execution remove from failure map if present (i.e.
   * if the event had failed on a previous try).
   */
  public final boolean removeEventFromFailureMap(Event event) {
    return this.failureLogInterval.remove(event) != null;
  }  
  /**
   * Return true if failure map has entries.
   */
  public final boolean hasFailures() {
    return this.failureLogInterval.size() > 0;
  }

  /**
   * Encrypt the password of a given user for storage in file or memory.
   * 
   * @param user
   *          the name of user
   * @param password
   *          the password to be encrypted
   * @param transformation
   *          the algorithm to use for encryption e.g. AES/ECB/PKCS5Padding or
   *          Blowfish (e.g. see <a href=
   *          "http://docs.oracle.com/javase/6/docs/technotes/guides/security/SunProviders.html"
   *          >Sun Providers</a> for the available names in Oracle's Sun JDK}
   * @param keySize
   *          the size of the private key of the given "transformation" to use
   */
  public static final String encryptPassword(String user, String password,
      String transformation, int keySize) throws Exception {
    return GemFireXDUtils.encrypt(password, transformation, GemFireXDUtils
        .getUserPasswordCipherKeyBytes(user, transformation, keySize));
  }

  /**
   * Decrypt the password of a given user encrypted using
   * {@link #encryptPassword(String, String, String, int)}.
   * 
   * @param user
   *          the name of user
   * @param encPassword
   *          the encrypted password to be decrypted
   * @param transformation
   *          the algorithm to use for encryption e.g. AES/ECB/PKCS5Padding or
   *          Blowfish (e.g. see <a href=
   *          "http://docs.oracle.com/javase/6/docs/technotes/guides/security/SunProviders.html"
   *          >Sun Providers</a> for the available names in Oracle's Sun JDK}
   * @param keySize
   *          the size of the private key of the given "transformation" to use
   */
  public static final String decryptPassword(String user, String encPassword,
      String transformation, int keySize) throws Exception {
    return GemFireXDUtils.decrypt(encPassword, transformation, GemFireXDUtils
        .getUserPasswordCipherKeyBytes(user, transformation, keySize));
  }

  /**
   * Any cleanup required when closing.
   */
  public void close() {
    this.failureLogInterval.clear();
  }

  /**
   * Return a new GemFireXD runtime exception to wrap an underlying exception with
   * optional message detail. When an external exception is received in a
   * callback, using this method to wrap it is recommended for GemFireXD engine to
   * deal with it cleanly.
   * 
   * @param message
   *          the detail message of the exception
   * @param t
   *          the underlying exception to be wrapped
   */
  public RuntimeException newRuntimeException(String message, Throwable t) {
    return GemFireXDRuntimeException.newRuntimeException(message, t);
  }
  
  public void createEventErrorLogger(String errorFileName) {
    // Use the default if not provided
    if (errorFileName == null) {
      errorFileName = EVENT_ERROR_LOG_FILE;
    }
    evErrorLogger = new EventErrorLogger(errorFileName);
  }
  
  public void logEventError(Event ev, Exception e) throws Exception {
    if (evErrorLogger == null) {
      throw new Exception("Event Error Logger not created");
    }
    evErrorLogger.logError(ev, e);
  }
}
