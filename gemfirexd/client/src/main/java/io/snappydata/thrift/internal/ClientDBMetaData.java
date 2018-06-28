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

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.jdbc.ClientAttribute;
import io.snappydata.thrift.*;
import io.snappydata.thrift.common.Converters;
import io.snappydata.thrift.common.ThriftExceptionUtil;

/**
 * Implementation of JDBC {@link DatabaseMetaData} for the thrift JDBC driver.
 */
public class ClientDBMetaData implements DatabaseMetaData {

  private final ClientConnection conn;
  private final ClientService service;
  private ServiceMetaData serviceMetaData;
  private final StatementAttrs metaAttrs;

  ClientDBMetaData(ClientConnection conn) {
    this.conn = conn;
    this.service = conn.getClientService();
    this.metaAttrs = new StatementAttrs();
  }

  private void initServiceMetaData() throws SQLException {
    if (this.serviceMetaData == null) {
      try {
        this.serviceMetaData = this.service.getServiceMetaData();
      } catch (SnappyException se) {
        throw ThriftExceptionUtil.newSQLException(se);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean allProceduresAreCallable() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.ALL_PROCEDURES_CALLABLE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean allTablesAreSelectable() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.ALL_TABLES_SELECTABLE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isReadOnly() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return Boolean.TRUE.equals(this.serviceMetaData.getTransactionDefaults()
          .get(TransactionAttribute.READ_ONLY_CONNECTION));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nullsAreSortedHigh() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.NULLS_SORTED_HIGH);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nullsAreSortedLow() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.NULLS_SORTED_LOW);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nullsAreSortedAtStart() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.NULLS_SORTED_START);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nullsAreSortedAtEnd() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.NULLS_SORTED_END);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean usesLocalFiles() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.USES_LOCAL_FILES);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean usesLocalFilePerTable() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.USES_LOCAL_FILE_PER_TABLE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsMixedCaseIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.MIXEDCASE_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean storesUpperCaseIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORES_UPPERCASE_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean storesLowerCaseIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORES_LOWERCASE_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean storesMixedCaseIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORES_MIXEDCASE_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORES_MIXEDCASE_QUOTED_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORES_UPPERCASE_QUOTED_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORES_LOWERCASE_QUOTED_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORES_MIXEDCASE_QUOTED_IDENTIFIERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsAlterTableWithAddColumn() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.ALTER_TABLE_ADD_COLUMN);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsAlterTableWithDropColumn() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.ALTER_TABLE_DROP_COLUMN);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsColumnAliasing() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.COLUMN_ALIASING);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nullPlusNonNullIsNull() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.NULL_CONCAT_NON_NULL_IS_NULL);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsConvert() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.CONVERT);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsConvert(int fromType, int toType) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      Set<SnappyType> conversions = this.serviceMetaData.getSupportedCONVERT()
          .get(Converters.getThriftSQLType(fromType));
      return conversions != null
          && conversions.contains(Converters.getThriftSQLType(toType));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsTableCorrelationNames() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TABLE_CORRELATION_NAMES);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsDifferentTableCorrelationNames() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TABLE_CORRELATION_NAMES_DIFFERENT);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsExpressionsInOrderBy() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.ORDER_BY_EXPRESSIONS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsOrderByUnrelated() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.ORDER_BY_UNRELATED);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsGroupBy() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.GROUP_BY);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsGroupByUnrelated() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.GROUP_BY_UNRELATED);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsGroupByBeyondSelect() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.GROUP_BY_BEYOND_SELECT);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsLikeEscapeClause() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.LIKE_ESCAPE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsMultipleResultSets() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.MULTIPLE_RESULTSETS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsMultipleTransactions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.MULTIPLE_TRANSACTIONS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsNonNullableColumns() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.NON_NULLABLE_COLUMNS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsMinimumSQLGrammar() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SQL_GRAMMAR_MINIMUM);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsCoreSQLGrammar() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SQL_GRAMMAR_CORE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsExtendedSQLGrammar() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SQL_GRAMMAR_EXTENDED);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsANSI92EntryLevelSQL() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SQL_GRAMMAR_ANSI92_ENTRY);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsANSI92IntermediateSQL() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SQL_GRAMMAR_ANSI92_INTERMEDIATE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsANSI92FullSQL() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SQL_GRAMMAR_ANSI92_FULL);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsIntegrityEnhancementFacility() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.INTEGRITY_ENHANCEMENT);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsOuterJoins() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.OUTER_JOINS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsFullOuterJoins() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.OUTER_JOINS_FULL);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsLimitedOuterJoins() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.OUTER_JOINS_LIMITED);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isCatalogAtStart() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.isCatalogAtStart();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSchemasInDataManipulation() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SCHEMAS_IN_DMLS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSchemasInProcedureCalls() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SCHEMAS_IN_PROCEDURE_CALLS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSchemasInTableDefinitions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SCHEMAS_IN_TABLE_DEFS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSchemasInIndexDefinitions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SCHEMAS_IN_INDEX_DEFS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SCHEMAS_IN_PRIVILEGE_DEFS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsCatalogsInDataManipulation() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.CATALOGS_IN_DMLS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsCatalogsInProcedureCalls() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.CATALOGS_IN_PROCEDURE_CALLS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsCatalogsInTableDefinitions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.CATALOGS_IN_TABLE_DEFS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.CATALOGS_IN_INDEX_DEFS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.CATALOGS_IN_PRIVILEGE_DEFS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsPositionedDelete() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.POSITIONED_DELETE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsPositionedUpdate() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.POSITIONED_UPDATE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSelectForUpdate() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SELECT_FOR_UPDATE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsStoredProcedures() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORED_PROCEDURES);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSubqueriesInComparisons() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SUBQUERIES_IN_COMPARISONS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSubqueriesInExists() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SUBQUERIES_IN_EXISTS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSubqueriesInIns() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SUBQUERIES_IN_INS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSubqueriesInQuantifieds() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SUBQUERIES_IN_QUANTIFIEDS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsCorrelatedSubqueries() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.SUBQUERIES_CORRELATED);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsUnion() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.UNION);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsUnionAll() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.UNION_ALL);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.OPEN_CURSORS_ACROSS_COMMIT);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.OPEN_CURSORS_ACROSS_ROLLBACK);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.OPEN_STATEMENTS_ACROSS_COMMIT);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.OPEN_STATEMENTS_ACROSS_ROLLBACK);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.MAX_ROWSIZE_INCLUDES_BLOBSIZE);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsTransactions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TRANSACTIONS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsTransactionIsolationLevel(int level)
      throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedIsolationLevels = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.TRANSACTIONS_SUPPORT_ISOLATION);
      return (supportedIsolationLevels != null && supportedIsolationLevels
          .contains((int)Converters.getThriftTransactionIsolation(level)));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions()
      throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TRANSACTIONS_BOTH_DMLS_AND_DDLS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TRANSACTIONS_DMLS_ONLY);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TRANSACTIONS_DDLS_IMPLICIT_COMMIT);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TRANSACTIONS_DDLS_IGNORED);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsBatchUpdates() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.BATCH_UPDATES);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsSavepoints() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.TRANSACTIONS_SAVEPOINTS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsNamedParameters() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.CALLABLE_NAMED_PARAMETERS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsMultipleOpenResults() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.MULTIPLE_RESULTSETS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsGetGeneratedKeys() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.GENERATED_KEYS_RETRIEVAL);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean locatorsUpdateCopy() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.LOB_UPDATES_COPY);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsStatementPooling() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STATEMENT_POOLING);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.STORED_FUNCTIONS_USING_CALL);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.AUTOCOMMIT_FAILURE_CLOSES_ALL_RESULTSETS);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsResultSetType(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_TYPE);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency)
      throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes;
      switch (concurrency) {
        case ResultSet.CONCUR_READ_ONLY:
          supportedRSTypes = this.serviceMetaData.getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_CONCURRENCY_READ_ONLY);
          break;
        case ResultSet.CONCUR_UPDATABLE:
          supportedRSTypes = this.serviceMetaData.getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_CONCURRENCY_UPDATABLE);
          break;
        default:
          return false;
      }
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean ownUpdatesAreVisible(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_OWN_UPDATES_VISIBLE);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean ownDeletesAreVisible(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_OWN_DELETES_VISIBLE);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean ownInsertsAreVisible(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_OWN_INSERTS_VISIBLE);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean othersUpdatesAreVisible(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_OTHERS_UPDATES_VISIBLE);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean othersDeletesAreVisible(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_OTHERS_DELETES_VISIBLE);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean othersInsertsAreVisible(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_OTHERS_INSERTS_VISIBLE);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean updatesAreDetected(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_UPDATES_DETECTED);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean deletesAreDetected(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_DELETES_DETECTED);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean insertsAreDetected(int type) throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      List<Integer> supportedRSTypes = this.serviceMetaData
          .getFeaturesWithParams().get(
              ServiceFeatureParameterized.RESULTSET_INSERTS_DETECTED);
      return supportedRSTypes != null
          && supportedRSTypes.contains(Converters.getThriftResultSetType(type));
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsResultSetHoldability(int holdability)
      throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      Set<ServiceFeature> supportedFeatures = this.serviceMetaData
          .getSupportedFeatures();
      switch (holdability) {
        case ResultSet.CLOSE_CURSORS_AT_COMMIT:
          return supportedFeatures.contains(
              ServiceFeature.RESULTSET_HOLDABILITY_CLOSE_CURSORS_AT_COMMIT);
        case ResultSet.HOLD_CURSORS_OVER_COMMIT:
          return supportedFeatures.contains(
              ServiceFeature.RESULTSET_HOLDABILITY_HOLD_CURSORS_OVER_COMMIT);
        default:
          return false;
      }
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getResultSetHoldability() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData
          .isDefaultResultSetHoldabilityHoldCursorsOverCommit() ? ResultSet.HOLD_CURSORS_OVER_COMMIT
          : ResultSet.CLOSE_CURSORS_AT_COMMIT;
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getDatabaseProductName() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getProductName();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getDatabaseProductVersion() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getProductVersion();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getDriverName() throws SQLException {
    return ClientConfiguration.DRIVER_NAME;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getDriverVersion() throws SQLException {
    return ClientConfiguration.getProductVersionHolder().getVersionBuildString(
        true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getDriverMajorVersion() {
    return ClientConfiguration.getProductVersionHolder().getMajorVersion();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getDriverMinorVersion() {
    return ClientConfiguration.getProductVersionHolder().getMinorVersion();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getCatalogSeparator() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getCatalogSeparator();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getIdentifierQuoteString() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getIdentifierQuote();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSQLKeywords() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return SharedUtils.toCSV(this.serviceMetaData.getSqlKeywords());
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getNumericFunctions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return SharedUtils.toCSV(this.serviceMetaData.getNumericFunctions());
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getStringFunctions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return SharedUtils.toCSV(this.serviceMetaData.getStringFunctions());
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSystemFunctions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return SharedUtils.toCSV(this.serviceMetaData.getSystemFunctions());
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTimeDateFunctions() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return SharedUtils.toCSV(this.serviceMetaData.getDateTimeFunctions());
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSearchStringEscape() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSearchStringEscape();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getExtraNameCharacters() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getExtraNameCharacters();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSchemaTerm() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSchemaTerm();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getProcedureTerm() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getProcedureTerm();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getCatalogTerm() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getCatalogTerm();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxBinaryLiteralLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxBinaryLiteralLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxCharLiteralLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxCharLiteralLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxColumnNameLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxColumnNameLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxColumnsInGroupBy() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxColumnsInGroupBy();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxColumnsInIndex() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxColumnsInIndex();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxColumnsInOrderBy() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxColumnsInOrderBy();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxColumnsInSelect() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxColumnsInSelect();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxColumnsInTable() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxColumnsInTable();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxConnections() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxConnections();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxCursorNameLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxCursorNameLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxIndexLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxIndexLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxSchemaNameLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxSchemaNameLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxProcedureNameLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxProcedureNameLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxCatalogNameLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxCatalogNameLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxRowSize() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxRowSize();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxStatementLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxStatementLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxStatements() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxOpenStatements();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxTableNameLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxTableNameLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxTablesInSelect() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxTableNamesInSelect();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMaxUserNameLength() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getMaxUserNameLength();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getDefaultTransactionIsolation() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return Converters.getJdbcIsolation(this.serviceMetaData
          .getDefaultTransactionIsolation());
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getDatabaseMajorVersion() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getProductMajorVersion();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getDatabaseMinorVersion() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getProductMinorVersion();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getJDBCMajorVersion() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getJdbcMajorVersion();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getJDBCMinorVersion() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getJdbcMinorVersion();
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getSQLStateType() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.isSqlStateIsXOpen() ? sqlStateXOpen
          : sqlStateSQL;
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RowIdLifetime getRowIdLifetime() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      switch (this.serviceMetaData.getRowIdLifeTime()) {
        case ROWID_UNSUPPORTED:
          return RowIdLifetime.ROWID_UNSUPPORTED;
        case ROWID_VALID_OTHER:
          return RowIdLifetime.ROWID_VALID_OTHER;
        case ROWID_VALID_SESSION:
          return RowIdLifetime.ROWID_VALID_SESSION;
        case ROWID_VALID_TRANSACTION:
          return RowIdLifetime.ROWID_VALID_TRANSACTION;
        case ROWID_VALID_FOREVER:
          return RowIdLifetime.ROWID_VALID_FOREVER;
        default:
          return RowIdLifetime.ROWID_UNSUPPORTED;
      }
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getURL() throws SQLException {
    StringBuilder url = new StringBuilder();
    url.append(ClientConfiguration.CURRENT_DRIVER_PROTOCOL());
    final List<HostAddress> connHosts = this.service.connHosts;
    if (connHosts == null || connHosts.isEmpty()) {
      url.append(this.service.getCurrentHostConnection()
          .hostAddr.getHostString());
    } else {
      url.append(connHosts.get(0).getHostString());
      final int numHosts = connHosts.size();
      if (numHosts > 1) {
        // add secondary locators to the URL
        url.append("/;").append(ClientAttribute.SECONDARY_LOCATORS).append('=');
        for (int index = 1; index < numHosts; index++) {
          if (index > 1) {
            url.append(',');
          }
          url.append(connHosts.get(index).getHostString());
        }
      }
    }
    return url.toString();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getUserName() throws SQLException {
    return this.service.connArgs.getUserName();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ClientConnection getConnection() throws SQLException {
    return this.conn;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getProcedures(String catalog, String schemaPattern,
      String procedureNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.PROCEDURES,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setProcedureName(procedureNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getProcedureColumns(String catalog, String schemaPattern,
      String procedureNamePattern, String columnNamePattern)
      throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.PROCEDURECOLUMNS,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setProcedureName(procedureNamePattern)
              .setColumnName(columnNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getTables(String catalog, String schemaPattern,
      String tableNamePattern, String[] types) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.TABLES,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTable(tableNamePattern)
              .setTableTypes(types != null ? Arrays.asList(types) : null));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getSchemas() throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(ServiceMetaDataCall.SCHEMAS,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getCatalogs() throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(ServiceMetaDataCall.CATALOGS,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getTableTypes() throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.TABLETYPES, new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getColumns(String catalog, String schemaPattern,
      String tableNamePattern, String columnNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.COLUMNS,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTable(tableNamePattern)
              .setColumnName(columnNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getColumnPrivileges(String catalog, String schema,
      String table, String columnNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.COLUMNPRIVILEGES, new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE).setSchema(schema)
              .setTable(table).setColumnName(columnNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getTablePrivileges(String catalog, String schemaPattern,
      String tableNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.TABLEPRIVILEGES,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTable(tableNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getVersionColumns(String catalog, String schema,
      String table) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.VERSIONCOLUMNS, new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE).setSchema(schema)
              .setTable(table));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getPrimaryKeys(String catalog, String schema, String table)
      throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.PRIMARYKEYS, new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE).setSchema(schema)
              .setTable(table));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getImportedKeys(String catalog, String schema, String table)
      throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.IMPORTEDKEYS, new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE).setSchema(schema)
              .setTable(table));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getExportedKeys(String catalog, String schema, String table)
      throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.EXPORTEDKEYS, new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE).setSchema(schema)
              .setTable(table));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getCrossReference(String parentCatalog, String parentSchema,
      String parentTable, String foreignCatalog, String foreignSchema,
      String foreignTable) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.CROSSREFERENCE,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(parentSchema).setTable(parentTable)
              .setForeignSchema(foreignSchema).setForeignTable(foreignTable));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getTypeInfo() throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(ServiceMetaDataCall.TYPEINFO,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getSchemas(String catalog, String schemaPattern)
      throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.SCHEMAS,
          new ServiceMetaDataArgs().setDriverType(
              ClientConfiguration.DRIVER_TYPE).setSchema(schemaPattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getFunctions(String catalog, String schemaPattern,
      String functionNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.FUNCTIONS,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setFunctionName(functionNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getFunctionColumns(String catalog, String schemaPattern,
      String functionNamePattern, String columnNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.FUNCTIONCOLUMNS,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setFunctionName(functionNamePattern)
              .setColumnName(columnNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getSuperTypes(String catalog, String schemaPattern,
      String typeNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.SUPERTYPES,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTypeName(typeNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getSuperTables(String catalog, String schemaPattern,
      String tableNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.SUPERTABLES,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTable(tableNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getAttributes(String catalog, String schemaPattern,
      String typeNamePattern, String attributeNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.ATTRIBUTES,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTypeName(typeNamePattern)
              .setAttributeName(attributeNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getClientInfoProperties() throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.CLIENTINFOPROPS, new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getIndexInfo(String catalog, String schema, String table,
      boolean unique, boolean approximate) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getIndexInfo(new ServiceMetaDataArgs()
          .setDriverType(ClientConfiguration.DRIVER_TYPE).setSchema(schema)
          .setTable(table), unique, approximate);
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getBestRowIdentifier(String catalog, String schema,
      String table, int scope, boolean nullable) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getBestRowIdentifier(new ServiceMetaDataArgs()
          .setDriverType(ClientConfiguration.DRIVER_TYPE).setSchema(schema)
          .setTable(table), scope, nullable);
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet getUDTs(String catalog, String schemaPattern,
      String typeNamePattern, int[] types) throws SQLException {
    this.conn.lock();
    try {
      List<SnappyType> sqlTypes = null;
      if (types != null) {
        // convert to thrift SQL types
        sqlTypes = new ArrayList<>(types.length);
        for (int type : types) {
          sqlTypes.add(Converters.getThriftSQLType(type));
        }
      }
      RowSet rs = this.service.getUDTs(
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTypeName(typeNamePattern), sqlTypes);
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return this.conn.unwrap(iface);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return this.conn.isWrapperFor(iface);
  }

  // JDBC 4.1 methods

  public boolean generatedKeyAlwaysReturned() throws SQLException {
    this.conn.lock();
    try {
      initServiceMetaData();
      return this.serviceMetaData.getSupportedFeatures().contains(
          ServiceFeature.GENERATED_KEYS_ALWAYS_RETURNED);
    } finally {
      this.conn.unlock();
    }
  }

  public ResultSet getPseudoColumns(String catalog, String schemaPattern,
      String tableNamePattern, String columnNamePattern) throws SQLException {
    this.conn.lock();
    try {
      RowSet rs = this.service.getSchemaMetaData(
          ServiceMetaDataCall.PSEUDOCOLUMNS,
          new ServiceMetaDataArgs()
              .setDriverType(ClientConfiguration.DRIVER_TYPE)
              .setSchema(schemaPattern).setTable(tableNamePattern)
              .setColumnName(columnNamePattern));
      return new ClientResultSet(this.conn, this.metaAttrs, rs);
    } catch (SnappyException se) {
      throw ThriftExceptionUtil.newSQLException(se);
    } finally {
      this.conn.unlock();
    }
  }
}
