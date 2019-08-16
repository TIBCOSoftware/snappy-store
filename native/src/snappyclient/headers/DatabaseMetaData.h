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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

/**
 * DatabaseMetaData.h
 */

#ifndef DATABASEMETADATA_H_
#define DATABASEMETADATA_H_

#include "Types.h"

namespace io {
namespace snappydata {
namespace client {

  enum class RowIdLifetime {
    ROWID_UNSUPPORTED = thrift::RowIdLifetime::ROWID_UNSUPPORTED,
    ROWID_VALID_OTHER = thrift::RowIdLifetime::ROWID_VALID_OTHER,
    ROWID_VALID_SESSION = thrift::RowIdLifetime::ROWID_VALID_SESSION,
    ROWID_VALID_TRANSACTION = thrift::RowIdLifetime::ROWID_VALID_TRANSACTION,
    ROWID_VALID_FOREVER = thrift::RowIdLifetime::ROWID_VALID_FOREVER
  };

  class DatabaseMetaDataArgs {
  private:
    thrift::ServiceMetaDataArgs m_args;

    friend class Connection;

  public:
    /** default constructor */
    DatabaseMetaDataArgs();

    /** copy constructor */
    DatabaseMetaDataArgs(const DatabaseMetaDataArgs& other);

    /** move constructor */
    DatabaseMetaDataArgs(DatabaseMetaDataArgs&& other) noexcept;

    /** assignment operator */
    DatabaseMetaDataArgs& operator=(const DatabaseMetaDataArgs& other);

    /** move assignment operator */
    DatabaseMetaDataArgs& operator=(DatabaseMetaDataArgs&& other) noexcept;

    ~DatabaseMetaDataArgs();

    DatabaseMetaDataArgs& setSchema(const std::string& schema);
    DatabaseMetaDataArgs& setTable(const std::string& table);
    DatabaseMetaDataArgs& setTableTypes(
        const std::vector<std::string>& tableTypes);
    DatabaseMetaDataArgs& setColumnName(const std::string& columnName);
    DatabaseMetaDataArgs& setForeignSchema(
        const std::string& foreignSchema);
    DatabaseMetaDataArgs& setForeignTable(
        const std::string& foreignTable);
    DatabaseMetaDataArgs& setProcedureName(
        const std::string& procedureName);
    DatabaseMetaDataArgs& setFunctionName(
        const std::string& functionName);
    DatabaseMetaDataArgs& setAttributeName(
        const std::string& attributeName);
    DatabaseMetaDataArgs& setTypeName(const std::string& typeName);
    DatabaseMetaDataArgs& setType(const SQLType& type);
  };

  class DatabaseMetaData {
  private:
    thrift::ServiceMetaData m_metadata;

    DatabaseMetaData();

    friend class Connection;

    bool searchFeature(thrift::ServiceFeatureParameterized::type featureName,
        int32_t searchFor) const;

  public:
    /** copy constructor */
    DatabaseMetaData(const DatabaseMetaData& other);

    /** move constructor */
    DatabaseMetaData(DatabaseMetaData&& other) noexcept;

    /** assignment operator */
    DatabaseMetaData& operator=(const DatabaseMetaData& other);

    /** move operator */
    DatabaseMetaData& operator=(DatabaseMetaData&& other) noexcept;

    ~DatabaseMetaData();

    bool isFeatureSupported(DatabaseFeature::type feature) const;
    const std::string& getProductName() const noexcept;
    const std::string& getProductVersion() const noexcept;
    int32_t getProductMajorVersion() const noexcept;
    int32_t getProductMinorVersion() const noexcept;
    int32_t getJdbcMajorVersion() const noexcept;
    int32_t getJdbcMinorVersion() const noexcept;
    const std::string& getIdentifierQuote() const noexcept;
    const std::vector<std::string>& getSQLKeyWords() const noexcept;
    const std::vector<std::string>& getNumericFunctions() const noexcept;
    const std::vector<std::string>& getStringFunctions() const noexcept;
    const std::vector<std::string>& getSystemFunctions() const noexcept;
    const std::vector<std::string>& getDateTimeFunctions() const noexcept;
    const std::string& getSearchStringEscape() const noexcept;
    const std::string& getExtraNameCharacters() const noexcept;
    const std::string& getSchemaTerm() const noexcept;
    const std::string& getProcedureTerm() const noexcept;
    const std::string& getCatalogTerm() const noexcept;
    const std::string& getCatalogSeparator() const noexcept;
    int32_t getMaxBinaryLiteralLength() const noexcept;
    int32_t getMaxCharLiteralLength() const noexcept;
    int32_t getMaxColumnsInGroupBy() const noexcept;
    int32_t getMaxColumnsInIndex() const noexcept;
    int32_t getMaxColumnsInOrderBy() const noexcept;
    int32_t getMaxColumnsInSelect() const noexcept;
    int32_t getMaxColumnsInTable() const noexcept;
    int32_t getMaxConnections() const noexcept;
    int32_t getMaxIndexLength() const noexcept;
    int32_t getMaxRowSize() const noexcept;
    int32_t getMaxOpenStatements() const noexcept;
    int32_t getMaxTableNamesInSelect() const noexcept;
    int32_t getMaxColumnNameLength() const noexcept;
    int32_t getMaxCursorNameLength() const noexcept;
    int32_t getMaxSchemaNameLength() const noexcept;
    int32_t getMaxProcedureNameLength() const noexcept;
    int32_t getMaxCatalogNameLength() const noexcept;
    int32_t maxTableNameLength() const noexcept;
    int32_t maxUserNameLength() const noexcept;
    int32_t defaultTransactionIsolation() const noexcept;
    int32_t getDefaultResultSetType() const noexcept;
    ResultSetHoldability getDefaultHoldability() const noexcept;
    bool isSQLStateXOpen() const noexcept;
    RowIdLifetime getDefaultRowIdLifeTime() const noexcept;
    bool supportsConvert(SQLType fromType, SQLType toType) const;
    bool supportsTransactionIsolationLevel(IsolationLevel isolation) const;
    bool supportsResultSetReadOnly(ResultSetType rsType) const;
    bool supportsResultSetUpdatable(ResultSetType rsType) const;
    bool othersUpdatesVisible(ResultSetType rsType) const;
    bool othersDeletesVisible(ResultSetType rsType) const;
    bool othersInsertsVisible(ResultSetType rsType) const;
    bool ownUpdatesVisible(ResultSetType rsType) const;
    bool ownDeletesVisible(ResultSetType rsType) const;
    bool ownInsertsVisible(ResultSetType rsType) const;
    bool updatesDetected(ResultSetType rsType) const;
    bool deletesDetected(ResultSetType rsType) const;
    bool insertsDetected(ResultSetType rsType) const;
  };

} /* namespace client */
} /* namespace snappydata */
} /* namespace io */

#endif /* DATABASEMETADATA_H_ */
