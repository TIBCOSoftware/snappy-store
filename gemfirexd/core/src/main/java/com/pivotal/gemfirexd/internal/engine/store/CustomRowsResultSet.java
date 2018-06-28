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

import java.sql.ResultSet;
import java.sql.SQLException;

import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSetMetaData;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;

/**
 * Allows exposing a set of a {@link DataValueDescriptor} array rows as as a
 * {@link ResultSet} that cannot be updated.
 * 
 * @author swale
 * @since 7.1
 */
public final class CustomRowsResultSet extends DVDStoreResultSet {

  private final FetchDVDRows fetchRows;
  private final DataValueDescriptor[] template;

  public CustomRowsResultSet(final FetchDVDRows fetchRows,
      final ResultColumnDescriptor[] columnInfo) throws StandardException {
    super(null, columnInfo.length, null, null, new EmbedResultSetMetaData(
        columnInfo));
    this.fetchRows = fetchRows;
    this.template = new DataValueDescriptor[columnInfo.length];
    for (int i = 0; i < columnInfo.length; i++) {
      this.template[i] = columnInfo[i].getType().getNull();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean next() throws SQLException {
    try {
      if (this.fetchRows.getNext(this.template)) {
        this.currentRowDVDs = this.template;
        return true;
      }
      else {
        this.currentRowDVDs = null;
        return false;
      }
    } catch (StandardException se) {
      throw Util.generateCsSQLException(se);
    } catch (SQLException sqle) {
      throw sqle;
    } catch (Exception e) {
      throw TransactionResourceImpl.wrapInSQLException(e);
    }
  }

  @Override
  public void close() throws SQLException {
    super.close();
    this.fetchRows.close();
  }

  /**
   * Allows fetching one row at a time as a DVD[] from arbitrary source.
   */
  public interface FetchDVDRows {

    /**
     * If next row is available then fill in template and return true, else
     * return false.
     */
    public boolean getNext(DataValueDescriptor[] template) throws SQLException,
        StandardException;

    default void close() throws SQLException {
    }
  }
}
