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

import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;

/**
 * Extra info used by local indexes to interpret the CompactCompositeKeys stored
 * in in the index.
 * 
 * @author dsmith
 */
public final class ExtraIndexInfo extends ExtraInfo {

  private final ExtraIndexInfo indexInfoForInsert;

  private GemFireContainer baseContainer;

  public ExtraIndexInfo() {
    super();
    this.indexInfoForInsert = new ExtraIndexInfo(true);
  }

  private ExtraIndexInfo(boolean ignored) {
    super();
    this.indexInfoForInsert = null;
  }

  public final void initRowFormatter(int[] baseColumnPositions,
      GemFireContainer baseContainer, TableDescriptor baseDescriptor)
      throws StandardException {
    // clone the incoming baseColumnPositions since we will manipulate them
    // independently in case of column drop etc later
    baseColumnPositions = baseColumnPositions.clone();
    this.primaryKeyColumns = baseColumnPositions;
    this.baseContainer = baseContainer;
    setPrimaryKeyFormatter(baseContainer, baseDescriptor, baseColumnPositions);
    if (this.indexInfoForInsert != null) {
      this.indexInfoForInsert.initRowFormatter(baseColumnPositions,
          baseContainer, baseDescriptor);
    }
  }

  @Override
  public final RowFormatter getRowFormatter(byte[] vbytes) {
    // obtain the formatter from base container
    return this.baseContainer.getRowFormatter(vbytes);
  }

  @Override
  public final RowFormatter getRowFormatter(OffHeapByteSource vbytes) {
    // obtain the formatter from base container
    return this.baseContainer.getRowFormatter(vbytes);
  }

  @Override
  public final RowFormatter getRowFormatter(final long memAddr,
      @Unretained final OffHeapByteSource vbytes) {
    // obtain the formatter from base container
    return this.baseContainer.getRowFormatter(memAddr, vbytes);
  }

  public final ExtraIndexInfo getIndexInfoForInsert() {
    return this.indexInfoForInsert != null ? this.indexInfoForInsert : this;
  }

  public final boolean forInsert() {
    return this.indexInfoForInsert == null;
  }

  @Override
  public void dropColumnForPrimaryKeyFormatter(int columnPos) {
    super.dropColumnForPrimaryKeyFormatter(columnPos);
    // also drop for indexInfoForInsert
    final ExtraIndexInfo forInsert = this.indexInfoForInsert;
    if (forInsert != null) {
      forInsert.dropColumnForPrimaryKeyFormatter(columnPos);
    }
  }

  
}
