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

package com.pivotal.gemfirexd.internal.engine.access.index;

import java.io.Serializable;

import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowUtil;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.iapi.store.raw.LockingPolicy;
import com.pivotal.gemfirexd.internal.iapi.types.BinarySQLHybridType;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

/***
 * 
 * @author yjing
 *
 */
public final class OpenMemIndex {

  private GemFireTransaction tran;

  private MemIndex conglomerate;

  //private boolean forUpdate;

  /**
   * A template of info about the classes in the returned row.
   * <p>
   * This template is allocated on demand, and is used to efficiently create new
   * rows for export from this class. This variable is for use by
   * get_row_for_export().
   **/
  private DataValueDescriptor[] rowForExportTemplate;

  /**
   * Initialize the open conglomerate.
   *
   * <p>If container is null, open the container, otherwise use the container
   * passed in. The container is always opened with no locking, it is up to the
   * caller to make the appropriate container locking call.
   *
   * <p>
   *
   * @return  The identifier to be used to open the conglomerate later.
   *
   * @param  open_user_scans  The user transaction which opened this btree.
   * @param  xact_manager  The current transaction, usually the same as
   *                       "open_user_scans", but in the case of split it is the
   *                       internal xact nested below the user xact.
   * @param  input_container  The open container holding the index, if it is
   *                          already open, else null which will mean this
   *                          routine will open it.
   * @param  rawtran  The current raw store transaction.
   * @param  open_mode  The opening mode for the ContainerHandle.
   * @param  conglomerate  Readonly description of the conglomerate.
   * @param  undo  Logical undo object to associate with all updates done on
   *               this open btree.
   *
   * @exception  StandardException  Standard exception policy.
   */

  /**
   * DOCUMENT ME!
   *
   * @throws StandardException
   *           DOCUMENT ME!
   */
  void init(GemFireTransaction tran, MemIndex conglomerate, int openMode,
      int lockLevel, LockingPolicy locking) throws StandardException {

    this.tran = tran;
    this.conglomerate = conglomerate;
    // do not care about the return value of openContainer since it can be
    // false if this is a remote node; if there is a real timeout then
    // GFContainerLocking will throw the appropriate exception and in this
    // respect it deviates from the contract of LockingPolicy
    conglomerate.openContainer(this.tran, openMode, lockLevel, locking);
    //this.forUpdate = ((openMode & ContainerHandle.MODE_FORUPDATE) != 0);
  }

  /**
   * DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   *
   * @throws  StandardException  DOCUMENT ME!
   */
  public boolean[] getColumnSortOrderInfo() throws StandardException {
    return this.conglomerate.ascDescInfo;
  }

  /**
   * DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   */
  public final ContainerKey getContainerKey() {
    return this.conglomerate.id;
  }

  /**
   * DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   */
  public final GemFireTransaction getTransaction() {
    return this.tran;
  }

  public final GemFireContainer getGemFireContainer() {
    return this.conglomerate.container;
  }

  public void close() {
    this.tran = null;
    this.rowForExportTemplate = null;
  }

  public final boolean isClosed() {
    return (this.tran == null);
  }

  public final RegionKey newGlobalKeyObject(DataValueDescriptor[] keyValue)
      throws StandardException {
    assert keyValue != null && keyValue.length >= 1;
    if (this.conglomerate.container.numColumns() == 1) {
      return keyValue[0];
    }
    else {
      return new CompositeRegionKey(keyValue);
    }
  }

  public static final Object newLocalKeyObject(
      final DataValueDescriptor[] keyValue, final GemFireContainer container)
      throws StandardException {
    // change the key to BinarySQLHybridType if there is any byte[] comparison
    // that can be done for a key column
    assert keyValue != null && keyValue.length >= 1;
    final GemFireContainer baseContainer = container.getBaseContainer();
    if (baseContainer.isByteArrayStore()) {
      RowFormatter rf = container.getExtraIndexInfo().getPrimaryKeyFormatter();
      if (container.numColumns() == 1) {
        // all application indexes now use CompactCompositeIndexKey
        final DataTypeDescriptor dtd = rf.getColumnDescriptor(0).columnType;
        return BinarySQLHybridType.getHybridTypeIfNeeded(keyValue[0], dtd);
      }
      else {
        final int numColumns = keyValue.length;
        boolean needHybrid = false;
        for (int index = 0; index < numColumns; index++) {
          if ((needHybrid = BinarySQLHybridType.needsTransformToEquivalent(
              keyValue[index], rf.getColumnDescriptor(index).columnType))) {
            break;
          }
        }
        if (needHybrid) {
          DataValueDescriptor[] newKey = new DataValueDescriptor[numColumns];
          DataTypeDescriptor dtd;
          int index;
          for (index = 0; index < numColumns; index++) {
            dtd = rf.getColumnDescriptor(index).columnType;
            newKey[index] = BinarySQLHybridType.getHybridTypeIfNeeded(
                keyValue[index], dtd);
          }
          return newKey;
        }
        else {
          return keyValue;
        }
      }
    }
    else {
      if (container.numColumns() == 1) {
        return keyValue[0];
      }
      return keyValue;
    }
  }

  public static final Object newLocalKeyObject(final Object keyValue,
      final GemFireContainer container) throws StandardException {
    // change the key to BinarySQLHybridType if there is any byte[] comparison
    // that can be done for a key column
    assert keyValue != null;

    final Class<?> cls = keyValue.getClass();
    if (cls == DataValueDescriptor[].class) {
      return newLocalKeyObject((DataValueDescriptor[])keyValue, container);
    }
    if (cls == CompactCompositeIndexKey.class) {
      return keyValue;
    }

    assert keyValue instanceof DataValueDescriptor;
    final GemFireContainer baseContainer = container.getBaseContainer();
    if (baseContainer.isByteArrayStore()) {
      RowFormatter rf = container.getExtraIndexInfo().getPrimaryKeyFormatter();
      // all application indexes now use CompactCompositeIndexKey
      final DataTypeDescriptor dtd = rf.getColumnDescriptor(0).columnType;
      return BinarySQLHybridType.getHybridTypeIfNeeded(
          (DataValueDescriptor)keyValue, dtd);
    }
    else {
      return keyValue;
    }
  }

  /**
   * DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   */
  public final MemIndex getConglomerate() {
    return this.conglomerate;
  }

  /**
   * DOCUMENT ME!
   *
   * @return  DOCUMENT ME!
   */
  public final GemFireContainer getBaseContainer() {
    return this.conglomerate.baseContainer;
  }

  /*****************************************************************************
   * Generate a key object for a input row.
   * <p>
   * Each row in a MemIndex meets the following protocol: the last field in the
   * row is an RowLocation Object;other fields contains the indexed column
   * values.
   * 
   * 
   * @param row
   *          the SortedMap2Index row
   * @param doClone
   *          true if the row needs to be cloned else false
   * 
   * @return the key
   */
  protected final Serializable getKey(DataValueDescriptor[] row,
      boolean doClone, boolean isLocalIndex) throws StandardException {

    assert row.length >= 2;

    final Serializable key;
    final DataValueDescriptor value = row[row.length - 1];
    //If this is a local index, create a compact composite key
    //to hold the key.
    if (isLocalIndex && value instanceof RegionEntry) {
      //TODO: Asif:Handle case of Chunk/DataAsAddress
      final Object rawValue = ((RegionEntry) value)._getValue();
      boolean notExists = false;
      if (rawValue != null) {
        final Class<?> valueClass = rawValue.getClass();
        if (valueClass == byte[].class) {
          // Return a wrapper around the value bytes
          return new CompactCompositeIndexKey(  (byte[])rawValue,
              conglomerate.container.getExtraIndexInfo());
        }
        else if (valueClass == byte[][].class) {
          // Return a wrapper around the value bytes
          return new CompactCompositeIndexKey( ((byte[][])rawValue)[0],
              conglomerate.container.getExtraIndexInfo());
        }
        else if (Token.isInvalidOrRemoved(rawValue)) {
          notExists = true;
        }else if( StoredObject.class.isAssignableFrom(valueClass)){
          throw new UnsupportedOperationException("asif: implement it");
        }
      }
      else {
        notExists = true;
      }
      if (notExists) {
        //If the rawValue is null, that means the entry was already
        //faulted out (perhaps we're creating an index on an overflow region)
        //Create a compact composite key that holds a snapshot of the key
        //fields.
        DataValueDescriptor[] keyValues = new DataValueDescriptor[row.length - 1];
        for (int i = 0; i < keyValues.length; ++i) {
          keyValues[i] = row[i];
        }
        return new CompactCompositeIndexKey(
            conglomerate.container.getExtraIndexInfo(), keyValues);
      }
    }

    // System.arraycopy(row, 0, keyValues, 0, keyValues.length);
    if (doClone) {
      if (row.length > 2) {
        DataValueDescriptor[] keyValues = new DataValueDescriptor[row.length - 1];
        for (int i = 0; i < keyValues.length; ++i) {
          keyValues[i] = row[i].getClone();
        }
        key = keyValues;
      }
      else {
        key = row[0].getClone();
      }
    }
    else {
      if (row.length > 2) {
        DataValueDescriptor[] keyValues = new DataValueDescriptor[row.length - 1];
        for (int i = 0; i < keyValues.length; ++i) {
          keyValues[i] = row[i];
        }
        key = keyValues;
      }
      else {
        key = row[0];
      }
    }
    if (isLocalIndex) {
      return key;
    }
    else {
      return this.conglomerate.getGlobalHashIndexKey(key);
    }
  }

  /*****************************************************************************
   * Get the RowLocation value in a row.
   * 
   * @param row
   */
  protected RowLocation getValue(DataValueDescriptor[] row) {
    assert row != null && row.length > 1
        && row[row.length - 1] instanceof RowLocation;
    return (RowLocation)row[row.length - 1];
  }

  protected long getKeySize() {
    return 1;
  }

  // determine if this index is unique or not
  public final boolean isUnique() {
    // TODO: currently does not handle uniqueWithDuplicateNulls properly
    return this.conglomerate.unique;
  }

  /**
   * Return an empty template (possibly partial) row to be given back to a
   * client.
   * <p>
   * The main use of this is for fetchSet() and fetchNextGroup() which allocate
   * rows and then give them back entirely to the caller.
   * <p>
   * 
   * @return The row to use.
   * 
   * @exception StandardException
   *              Standard exception policy.
   **/
  public DataValueDescriptor[] getRowForExport() throws StandardException {
    // Allocate a new row based on the class template.
    return RowUtil.newRowFromTemplate(getRowForExportTemplate());
  }

  public final DataValueDescriptor[] getRowForExportTemplate()
      throws StandardException {
    // Create a partial row class template template from the initial scan
    // parameters.
    if (this.rowForExportTemplate == null) {
      final GemFireTransaction tran = this.tran;
      this.rowForExportTemplate = RowUtil.newTemplate(
          tran != null ? tran.getDataValueFactory() : Misc.getMemStoreBooting()
              .getDatabase().getDataValueFactory(), null,
          this.conglomerate.format_ids, this.conglomerate.collation_ids);
    }
    return this.rowForExportTemplate;
  }

  @Override
  public String toString() {
    return "OpenMemIndex on " + this.conglomerate;
  }
}
