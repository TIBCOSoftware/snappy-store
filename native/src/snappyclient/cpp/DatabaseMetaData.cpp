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

/**
 * DatabaseMetaData.cpp
 */

#include "DatabaseMetaData.h"

#include <set>

using namespace io::snappydata;
using namespace io::snappydata::client;

/** default constructor */
DatabaseMetaDataArgs::DatabaseMetaDataArgs() : m_args() {
}

/** copy constructor */
DatabaseMetaDataArgs::DatabaseMetaDataArgs(const DatabaseMetaDataArgs& other) :
    m_args(other.m_args) {
}

/** move constructor */
DatabaseMetaDataArgs::DatabaseMetaDataArgs(DatabaseMetaDataArgs&& other)
    noexcept : m_args(std::move(other.m_args)) {
}

/** assignment operator */
DatabaseMetaDataArgs& DatabaseMetaDataArgs::operator=(
    const DatabaseMetaDataArgs& other) {
  m_args = other.m_args;
  return *this;
}

/** move assignment operator */
DatabaseMetaDataArgs& DatabaseMetaDataArgs::operator=(
    DatabaseMetaDataArgs&& other) noexcept {
  m_args = std::move(other.m_args);
  return *this;
}

DatabaseMetaDataArgs::~DatabaseMetaDataArgs() {
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setSchema(
    const std::string& schema) {
  m_args.__set_schema(schema);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setTable(
    const std::string& table) {
  m_args.__set_table(table);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setTableTypes(
    const std::vector<std::string>& tableTypes) {
  m_args.__set_tableTypes(tableTypes);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setColumnName(
    const std::string& columnName) {
  m_args.__set_columnName(columnName);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setForeignSchema(
    const std::string& foreignSchema) {
  m_args.__set_foreignSchema(foreignSchema);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setForeignTable(
    const std::string& foreignTable) {
  m_args.__set_foreignTable(foreignTable);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setProcedureName(
    const std::string& procedureName) {
  m_args.__set_procedureName(procedureName);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setFunctionName(
    const std::string& functionName) {
  m_args.__set_functionName(functionName);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setAttributeName(
    const std::string& attributeName) {
  m_args.__set_attributeName(attributeName);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setTypeName(
    const std::string& typeName) {
  m_args.__set_typeName(typeName);
  return *this;
}

DatabaseMetaDataArgs& DatabaseMetaDataArgs::setType(const SQLType& type) {
  m_args.__set_typeId(static_cast<thrift::SnappyType::type>(type));
  return *this;
}


DatabaseMetaData::DatabaseMetaData() : m_metadata() {
}

DatabaseMetaData::DatabaseMetaData(const DatabaseMetaData& other) :
    m_metadata(other.m_metadata) {
}

DatabaseMetaData::DatabaseMetaData(DatabaseMetaData&& other) noexcept :
    m_metadata(std::move(other.m_metadata)) {
}

DatabaseMetaData& DatabaseMetaData::operator=(
    const DatabaseMetaData& other) {
  m_metadata = other.m_metadata;
  return *this;
}

DatabaseMetaData& DatabaseMetaData::operator=(DatabaseMetaData&& other)
    noexcept {
  m_metadata = std::move(other.m_metadata);
  return *this;
}

DatabaseMetaData::~DatabaseMetaData() {
}

bool DatabaseMetaData::searchFeature(
    thrift::ServiceFeatureParameterized::type featureName,
    int32_t searchFor) const {
  std::map<thrift::ServiceFeatureParameterized::type, std::vector<int32_t> >
      ::const_iterator result = m_metadata.featuresWithParams.find(featureName);
  if (result != m_metadata.featuresWithParams.end()) {
    for (std::vector<int32_t>::const_iterator iter = result->second.begin();
        iter != result->second.end(); ++iter) {
      if (*iter == searchFor) {
        return true;
      }
    }
  }
  return false;
}

bool DatabaseMetaData::isFeatureSupported(
    DatabaseFeature::type feature) const {
  const std::set<thrift::ServiceFeature::type>& supportedFeatures =
      m_metadata.supportedFeatures;

  return supportedFeatures.find(feature) != supportedFeatures.end();
}

const std::string& DatabaseMetaData::getProductName() const noexcept {
  return m_metadata.productName;
}

const std::string& DatabaseMetaData::getProductVersion() const noexcept {
  return m_metadata.productVersion;
}

int32_t DatabaseMetaData::getProductMajorVersion() const noexcept {
  return m_metadata.productMajorVersion;
}

int32_t DatabaseMetaData::getProductMinorVersion() const noexcept {
  return m_metadata.productMinorVersion;
}

int32_t DatabaseMetaData::getJdbcMajorVersion() const noexcept {
  return m_metadata.jdbcMajorVersion;
}

int32_t DatabaseMetaData::getJdbcMinorVersion() const noexcept {
  return m_metadata.jdbcMinorVersion;
}

const std::string& DatabaseMetaData::getIdentifierQuote() const noexcept {
  return m_metadata.identifierQuote;
}

const std::vector<std::string>& DatabaseMetaData::getSQLKeyWords()
    const noexcept {
  return m_metadata.sqlKeywords;
}

const std::vector<std::string>& DatabaseMetaData::getNumericFunctions()
    const noexcept {
  return m_metadata.numericFunctions;
}

const std::vector<std::string>& DatabaseMetaData::getStringFunctions()
    const noexcept {
  return m_metadata.stringFunctions;
}

const std::vector<std::string>& DatabaseMetaData::getSystemFunctions()
    const noexcept {
  return m_metadata.systemFunctions;
}

const std::vector<std::string>& DatabaseMetaData::getDateTimeFunctions()
    const noexcept {
  return m_metadata.dateTimeFunctions;
}

const std::string& DatabaseMetaData::getSearchStringEscape() const noexcept {
  return m_metadata.searchStringEscape;
}

const std::string& DatabaseMetaData::getExtraNameCharacters() const noexcept {
  return m_metadata.extraNameCharacters;
}

const std::string& DatabaseMetaData::getSchemaTerm() const noexcept {
  return m_metadata.schemaTerm;
}

const std::string& DatabaseMetaData::getProcedureTerm() const noexcept {
  return m_metadata.procedureTerm;
}

const std::string& DatabaseMetaData::getCatalogTerm() const noexcept {
  return m_metadata.catalogTerm;
}

const std::string& DatabaseMetaData::getCatalogSeparator() const noexcept {
  return m_metadata.catalogSeparator;
}

int32_t DatabaseMetaData::getMaxBinaryLiteralLength() const noexcept {
  return m_metadata.maxBinaryLiteralLength;
}

int32_t DatabaseMetaData::getMaxCharLiteralLength() const noexcept {
  return m_metadata.maxCharLiteralLength;
}

int32_t DatabaseMetaData::getMaxColumnsInGroupBy() const noexcept {
  return m_metadata.maxColumnsInGroupBy;
}

int32_t DatabaseMetaData::getMaxColumnsInIndex() const noexcept {
  return m_metadata.maxColumnsInIndex;
}

int32_t DatabaseMetaData::getMaxColumnsInOrderBy() const noexcept {
  return m_metadata.maxColumnsInOrderBy;
}

int32_t DatabaseMetaData::getMaxColumnsInSelect() const noexcept {
  return m_metadata.maxColumnsInSelect;
}

int32_t DatabaseMetaData::getMaxColumnsInTable() const noexcept {
  return m_metadata.maxColumnsInTable;
}

int32_t DatabaseMetaData::getMaxConnections() const noexcept {
  return m_metadata.maxConnections;
}

int32_t DatabaseMetaData::getMaxIndexLength() const noexcept {
  return m_metadata.maxIndexLength;
}

int32_t DatabaseMetaData::getMaxRowSize() const noexcept {
  return m_metadata.maxRowSize;
}

int32_t DatabaseMetaData::getMaxOpenStatements() const noexcept {
  return m_metadata.maxOpenStatements;
}

int32_t DatabaseMetaData::getMaxTableNamesInSelect() const noexcept {
  return m_metadata.maxTableNamesInSelect;
}

int32_t DatabaseMetaData::getMaxColumnNameLength() const noexcept {
  return m_metadata.maxColumnNameLength;
}

int32_t DatabaseMetaData::getMaxCursorNameLength() const noexcept {
  return m_metadata.maxCursorNameLength;
}

int32_t DatabaseMetaData::getMaxSchemaNameLength() const noexcept {
  return m_metadata.maxSchemaNameLength;
}

int32_t DatabaseMetaData::getMaxProcedureNameLength() const noexcept {
  return m_metadata.maxProcedureNameLength;
}

int32_t DatabaseMetaData::getMaxCatalogNameLength() const noexcept {
  return m_metadata.maxCatalogNameLength;
}

int32_t DatabaseMetaData::maxTableNameLength() const noexcept {
  return m_metadata.maxTableNameLength;
}

int32_t DatabaseMetaData::maxUserNameLength() const noexcept {
  return m_metadata.maxUserNameLength;
}

int32_t DatabaseMetaData::defaultTransactionIsolation() const noexcept {
  return m_metadata.defaultTransactionIsolation;
}

int32_t DatabaseMetaData::getDefaultResultSetType() const noexcept {
  return m_metadata.defaultResultSetType;
}

ResultSetHoldability DatabaseMetaData::getDefaultHoldability()
    const noexcept {
  return m_metadata.defaultResultSetHoldabilityHoldCursorsOverCommit
      ? ResultSetHoldability::HOLD_CURSORS_OVER_COMMIT
      : ResultSetHoldability::CLOSE_CURSORS_OVER_COMMIT;
}

bool DatabaseMetaData::isSQLStateXOpen() const noexcept {
  return m_metadata.sqlStateIsXOpen;
}

RowIdLifetime DatabaseMetaData::getDefaultRowIdLifeTime() const noexcept {
  return static_cast<RowIdLifetime>(m_metadata.rowIdLifeTime);
}

bool DatabaseMetaData::supportsConvert(SQLType fromType,
    SQLType toType) const {
  std::map<thrift::SnappyType::type, std::set<thrift::SnappyType::type> >
      ::const_iterator result = m_metadata.supportedCONVERT.find(
          static_cast<thrift::SnappyType::type>(fromType));
  if (result != m_metadata.supportedCONVERT.end()) {
    return result->second.find(static_cast<thrift::SnappyType::type>(
        toType)) != result->second.end();
  } else {
    return false;
  }
}

bool DatabaseMetaData::supportsTransactionIsolationLevel(
    IsolationLevel isolation) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::TRANSACTIONS_SUPPORT_ISOLATION,
      static_cast<int32_t>(isolation));
}

bool DatabaseMetaData::supportsResultSetReadOnly(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_CONCURRENCY_READ_ONLY,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::supportsResultSetUpdatable(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_CONCURRENCY_UPDATABLE,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::othersUpdatesVisible(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_OTHERS_UPDATES_VISIBLE,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::othersDeletesVisible(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_OTHERS_DELETES_VISIBLE,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::othersInsertsVisible(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_OTHERS_INSERTS_VISIBLE,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::ownUpdatesVisible(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_OWN_UPDATES_VISIBLE,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::ownDeletesVisible(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_OWN_DELETES_VISIBLE,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::ownInsertsVisible(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_OWN_INSERTS_VISIBLE,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::updatesDetected(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_UPDATES_DETECTED,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::deletesDetected(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_DELETES_DETECTED,
      static_cast<int32_t>(rsType));
}

bool DatabaseMetaData::insertsDetected(
    ResultSetType rsType) const {
  return searchFeature(
      thrift::ServiceFeatureParameterized::RESULTSET_INSERTS_DETECTED,
      static_cast<int32_t>(rsType));
}
