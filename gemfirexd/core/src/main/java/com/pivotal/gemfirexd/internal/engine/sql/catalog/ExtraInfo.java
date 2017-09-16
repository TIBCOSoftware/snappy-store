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

package com.pivotal.gemfirexd.internal.engine.sql.catalog;

import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;

/**
 * Base class for ExtraInfo used by local indexes or gemfire containers.
 * 
 * @author dsmith
 *
 */
public abstract class ExtraInfo {

  protected RowFormatter pkFormatter;
  protected int[] primaryKeyColumns;
  protected int[] primaryKeyFixedColumns;
  protected int[] primaryKeyVarColumns;

  public ExtraInfo() {
  }

  protected final void setPrimaryKeyFormatter(final GemFireContainer container,
      TableDescriptor td, final int[] pkColumns) {
    final TIntArrayList fixedCols = new TIntArrayList(pkColumns.length);
    final TIntArrayList varCols = new TIntArrayList(pkColumns.length);
    // since PK bytes are persisted and compared directly, we do not want any
    // schema version; keeping it as fixed zero for consistency across different
    // rows; this may need to be revisited in future if we allow for changing
    // PK columns also with data
    this.pkFormatter = getRowFormatter(pkColumns, container, td, fixedCols,
        varCols, 0, true);
    if (fixedCols.size() > 0) {
      this.primaryKeyFixedColumns = fixedCols.toNativeArray();
    }
    else {
      this.primaryKeyFixedColumns = null;
    }
    if (varCols.size() > 0) {
      this.primaryKeyVarColumns = varCols.toNativeArray();
    }
    else {
      this.primaryKeyVarColumns = null;
    }
  }

  protected final RowFormatter getRowFormatter(final int[] columnPositions,
      final GemFireContainer container, TableDescriptor td,
      final TIntArrayList fixedCols, final TIntArrayList varCols,
      final int schemaVersion, final boolean isPrimaryKeyFormatter) {
    final RowFormatter formatter = container.getRowFormatter(columnPositions,
        td, schemaVersion, isPrimaryKeyFormatter);
    for (int index = 0; index < columnPositions.length; index++) {
      if (formatter.getColumnDescriptor(index).fixedWidth > 0) {
        fixedCols.add(columnPositions[index]);
      }
      else {
        varCols.add(columnPositions[index]);
      }
    }
    return formatter;
  }

  /**
   * Get the {@link RowFormatter} for primary key columns of this table.
   */
  public final RowFormatter getPrimaryKeyFormatter() {
    return this.pkFormatter;
  }

  /**
   * Primary key columns for the table, if any are mentioned in create table.
   */
  public final int[] getPrimaryKeyColumns() {
    return this.primaryKeyColumns;
  }

  /**
   * Primary key columns that are variable width ones for the table, if any are
   * mentioned in create table.
   * <p>
   * This is initialized only if this table requires a RowFormatter for primary
   * key byte formatting ( {@link #getPrimaryKeyFormatter()}) i.e. if this is
   * for a multi-column primary key table with byte array storage.
   */
  public final int[] getPrimaryKeyFixedColumns() {
    return this.primaryKeyFixedColumns;
  }

  /**
   * Primary key columns that are variable width ones for the table, if any are
   * mentioned in create table.
   * <p>
   * This is initialized only if this table requires a RowFormatter for primary
   * key byte formatting ( {@link #getPrimaryKeyFormatter()}) i.e. if this is
   * for a multi-column primary key table with byte array storage.
   */
  public final int[] getPrimaryKeyVarColumns() {
    return this.primaryKeyVarColumns;
  }

  /**
   * Return true if the table has primary key defined and container is using raw
   * byte array storage.
   */
  public final boolean regionKeyPartOfValue() {
    return this.pkFormatter != null;
  }

  /**
   * Adjust the column positions for primary key columns for a column drop which
   * lies before one or more of the key columns.
   */
  public void dropColumnForPrimaryKeyFormatter(int columnPos) {
    if (this.pkFormatter != null) {
      GemFireXDUtils.dropColumnAdjustColumnPositions(this.primaryKeyColumns,
          columnPos);
      GemFireXDUtils.dropColumnAdjustColumnPositions(this.primaryKeyFixedColumns,
          columnPos);
      GemFireXDUtils.dropColumnAdjustColumnPositions(this.primaryKeyVarColumns,
          columnPos);
      this.pkFormatter.dropColumnAdjustPositionInCD(columnPos);
    }
  }

  public abstract RowFormatter getRowFormatter(byte[] vbytes);
  public abstract RowFormatter getRowFormatter(OffHeapByteSource vbytes);
  public abstract RowFormatter getRowFormatter(long memAddr,
      OffHeapByteSource vbytes);
}
