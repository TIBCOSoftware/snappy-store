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

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

/**
 * Encapsulates information about an event captured by a callback object.
 */
public interface Event {

  /**
   * Get the <code>Type</code> of the event.
   * 
   * @return the Type of the event.
   */
  public Type getType();

  /**
   * Get the old row being updated as a <code>List</code> of the column values.
   * For a {@link Type#BULK_DML} or {@link Type#BULK_INSERT} operation, this
   * throws an {@link UnsupportedOperationException}.
   * 
   * @return the old row values or null if the old row is not available
   * 
   * @throws UnsupportedOperationException
   *           for {@link Type#BULK_DML} and {@link Type#BULK_INSERT} operations
   * 
   * @deprecated Will be removed in a future release. Use
   *             {@link #getOldRowAsResultSet()} instead which will return a
   *             valid {@link ResultSet} for all operation types and provide
   *             more flexible ways to extract the column values, avoiding
   *             having to create java objects for all the columns.
   */
  @Deprecated
  public List<Object> getOldRow() throws UnsupportedOperationException;

  /**
   * Get the old row being updated as a {@link ResultSet#TYPE_FORWARD_ONLY}
   * {@link ResultSet} of the column values. The returned {@link ResultSet} is
   * already positioned at the returned single row with {@link ResultSet#next()}
   * always returning false, whose column values can be retrieved using the
   * ResultSet getter methods. For a {@link Type#BULK_DML} or
   * {@link Type#BULK_INSERT} operation, this throws an
   * {@link UnsupportedOperationException}.
   * 
   * @return the old row values as a {@link ResultSet} or null if the old row is
   *         not available
   * 
   * @throws UnsupportedOperationException
   *           for {@link Type#BULK_DML} and {@link Type#BULK_INSERT} operations
   */
  public ResultSet getOldRowAsResultSet() throws UnsupportedOperationException;

  /**
   * Get the new or updated row as a <code>List</code> of the column values. For
   * a delete operation this returns null. For a {@link Type#BULK_DML}
   * operation, this is the set of parameters to the statement used for a
   * prepared statement, or null if there are no parameters. For a
   * {@link Type#BULK_INSERT} operation this throws an
   * {@link UnsupportedOperationException}.
   * 
   * @return the new or updated row values, or null if this is a delete
   *         operation, or a {@link Type#BULK_DML} operation having no
   *         parameters
   * 
   * @throws UnsupportedOperationException
   *           for a {@link Type#BULK_INSERT} operation
   * 
   * @deprecated Will be removed in a future release. Use
   *             {@link #getNewRowsAsResultSet()} instead which will return a
   *             valid {@link ResultSet} for all operation types and provide
   *             more flexible ways to extract the column values, avoiding
   *             having to create java objects for all the columns.
   */
  @Deprecated
  public List<Object> getNewRow() throws UnsupportedOperationException;

  /**
   * Get the new rows being inserted/updated as a
   * {@link ResultSet#TYPE_FORWARD_ONLY} {@link ResultSet}. For
   * {@link Type#BULK_INSERT} operation, it returns the set of rows being
   * inserted. For delete operations, it returns null. For all other operations,
   * the returned {@link ResultSet} is already positioned at the returned single
   * row with {@link ResultSet#next()} always returning false, whose column
   * values can be retrieved using the ResultSet getter methods.
   * 
   * The meta-data returned by the ResultSet's <code>getMetaData</code> method
   * will be a projection {@link TableMetaData} of the columns updated for the
   * update operation, a projection {@link ResultSetMetaData} of the columns
   * updated for {@link Type#BULK_DML} operation, and the full table meta-data
   * (i.e. same as {@link #getResultSetMetaData()} for other operations.
   * 
   * @return the rows being inserted/updated as a {@link ResultSet}
   */
  public ResultSet getNewRowsAsResultSet();

  /**
   * Gets the metadata information of the table being updated.
   * 
   * @return the <code>TableMetaData</code> of the table being updated.
   */
  public TableMetaData getResultSetMetaData();

  /**
   * Get the DML string for {@link Type#BEFORE_INSERT}, {@link Type#AFTER_INSERT}, 
   * {@link Type#BEFORE_UPDATE}, {@link Type#AFTER_UPDATE}, {@link Type#BEFORE_DELETE}
   * or {@link Type#AFTER_DELETE} operation when called from EventCallback and 
   * {@link Type#BULK_DML} or {@link Type#BULK_INSERT} when AsyncEventListener 
   * 
   * @return the DML string
   */
  public String getDMLString();

  /**
   * The schema for the current operation.
   * 
   * @return the current schema for this event
   */
  public String getSchemaName();

  /**
   * The fully qualified table name for the current operation.
   * 
   * @return the fully qualified table name for this event
   */
  public String getTableName();

  /**
   * A version number for the current row's table version. The version gets
   * incremented for every ALTER TABLE CREATE/DROP column.
   */
  public int getTableSchemaVersion();

  /**
   * Get the positions of the columns that were updated as an ordered array. The
   * details of the modified columns can be retrieved from the metadata returned
   * by {@link #getResultSetMetaData}. The modified column values can be
   * obtained from the new value from {@link #getNewRowsAsResultSet()}.
   * 
   * @return the int[] of 1-based column positions for the columns that were
   *         updated.
   * 
   * @throws UnsupportedOperationException
   *           for {@link Type#BULK_DML} and {@link Type#BULK_INSERT} operations
   * 
   * @deprecated Will be removed in a future release. Use returned
   *             {@link TableMetaData} by {@link ResultSet#getMetaData()}
   *             instead on the {@link #getNewRowsAsResultSet()} to get the
   *             meta-data of the updated columns.
   */
  @Deprecated
  public int[] getModifiedColumns() throws UnsupportedOperationException;

  /**
   * Returns the Primary key of the manipulated row. If the table has no primary
   * key defined, then a long value uniquely identifying the row would be
   * returned. In such cases, users can correlate the row ID during creation
   * with that during update &amp; delete.
   * 
   * @return Object[] of values of the primary key columns
   * 
   * @throws UnsupportedOperationException
   *           for {@link Type#BULK_DML} and {@link Type#BULK_INSERT} operations
   * 
   * @deprecated use {@link #getPrimaryKeysAsResultSet()} instead which will
   *             provide more flexible ways to extract the column values and
   *             avoid having to create java objects for all the columns
   */
  @Deprecated
  public Object[] getPrimaryKey() throws UnsupportedOperationException;

  /**
   * Get the primary key of the changed rows as a {@link ResultSet} having
   * {@link ResultSet#TYPE_FORWARD_ONLY} cursor. For {@link Type#BULK_INSERT}
   * operation, it returns all the primary key column values being inserted. If
   * there is no primary key defined on the table then it returns null for
   * {@link Type#BULK_INSERT}. For other operations, the returned
   * {@link ResultSet} does not support the cursor movement operations like
   * {@link ResultSet#next()} etc. returning just a single current row whose
   * column values can be retrieved using the ResultSet getter methods. A single
   * column having a unique long row ID is returned for the case there is no
   * primary key defined on the table.
   * 
   * @return values of the primary key columns as a {@link ResultSet}
   * 
   * @throws UnsupportedOperationException
   *           for a {@link Type#BULK_DML} operation
   */
  public ResultSet getPrimaryKeysAsResultSet()
      throws UnsupportedOperationException;

  /**
   * Returns true if this is a {@link Type#BULK_INSERT} operation or a
   * {@link Type#BULK_DML} operation having parameters for the prepared
   * statement and false otherwise.
   */
  public boolean hasParameters();

  /**
   * Returns true if the table for current operation has auto-generated columns.
   * For a table having auto-generated columns the {@link AsyncEventListener}
   * receives the operation as a {@link Type#BULK_DML} operation rather than as
   * an insert operation since the auto-generated column values will be skipped
   * from the parameter values.
   */
  public boolean tableHasAutogeneratedColumns();

  /**
   * Indicates whether this event originated in a VM other than this one. When
   * using JDBC clients, this will be false on the server to which the client is
   * connected and true on other servers when the event is propagated.
   * 
   * @return true if this event originated in another VM or false if originated
   *         in this VM.
   */
  public boolean isOriginRemote();

  /**
   * Indicates whether the event received is possibly a duplicate. Duplicate
   * events may be received when a fail-over happens during DML statement
   * execution.
   * 
   * @return true if the event is a possible duplicate.
   */
  public boolean isPossibleDuplicate();

  /**
   * Indicates whether the event received is from commit of a transaction.
   * 
   * @return true if the event is from commit of a transaction
   */
  public boolean isTransactional();

  /**
   * Returns whether the event is generated due to a {@link RowLoader}
   * invocation
   * 
   * @return true if the event got generated as a result of {@link RowLoader}
   *         invocation or false otherwise
   */
  public boolean isLoad();

  /**
   * Returns whether the event is generated due to expiration of a row.
   * 
   * @return true if yes false otherwise
   */
  public boolean isExpiration();

  /**
   * Returns whether the event is generated due to eviction of a row.
   * 
   * @return true if yes false otherwise
   */
  public boolean isEviction();

  /**
   * Enumeration of the types of callback events.
   */
  public enum Type {
    /** event raised before an insert into a table */
    BEFORE_INSERT,
    /** event raised before an update of a table */
    BEFORE_UPDATE,
    /** event raised before a delete in a table */
    BEFORE_DELETE,
    /** event raised after an insert into a table */
    AFTER_INSERT,
    /** event raised after an update of a table */
    AFTER_UPDATE,
    /** event raised after a delete in a table */
    AFTER_DELETE,
    /**
     * A bulk DML event raised for {@link AsyncEventListener}. Use
     * {@link Event#getDMLString()} to retrieve the DML string,
     * {@link Event#getNewRowsAsResultSet()} to get any parameters,
     * {@link ResultSet#getMetaData()} on the parameters ResultSet to get the
     * metadata for parameters, and {@link Event#getSchemaName()} to get the
     * current schema.
     */
    BULK_DML {
      @Override
      public boolean isBulkOperation() {
        return true;
      }
    },
    /**
     * An event raised for {@link AsyncEventListener} when a batch or bulk
     * insert is done on a table. Use {@link Event#getNewRowsAsResultSet()} to
     * get an iterator on the rows inserted in the table.
     */
    BULK_INSERT {
      @Override
      public boolean isBulkOperation() {
        return true;
      }

      @Override
      public boolean isBulkInsert() {
        return true;
      }
    };

    /**
     * Return true if this is a bulk operation event ({@link #BULK_DML} or
     * {@link #BULK_INSERT}).
     */
    public boolean isBulkOperation() {
      return false;
    }

    /**
     * Return true if this is a {@link #BULK_INSERT} operation.
     */
    public boolean isBulkInsert() {
      return false;
    }
  }
}
