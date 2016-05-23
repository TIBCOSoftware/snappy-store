/**
 * Autogenerated by Thrift Compiler (1.0.0-dev)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <thrift/cxxfunctional.h>
#include "snappydata_types.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {

int _kSnappyTypeValues[] = {
  SnappyType::BOOLEAN,
  SnappyType::TINYINT,
  SnappyType::SMALLINT,
  SnappyType::INTEGER,
  SnappyType::BIGINT,
  SnappyType::FLOAT,
  SnappyType::REAL,
  SnappyType::DOUBLE,
  SnappyType::DECIMAL,
  SnappyType::CHAR,
  SnappyType::VARCHAR,
  SnappyType::LONGVARCHAR,
  SnappyType::DATE,
  SnappyType::TIME,
  SnappyType::TIMESTAMP,
  SnappyType::BINARY,
  SnappyType::VARBINARY,
  SnappyType::LONGVARBINARY,
  SnappyType::BLOB,
  SnappyType::CLOB,
  SnappyType::SQLXML,
  SnappyType::ARRAY,
  SnappyType::MAP,
  SnappyType::STRUCT,
  SnappyType::NULLTYPE,
  SnappyType::JSON,
  SnappyType::JAVA_OBJECT,
  SnappyType::OTHER
};
const char* _kSnappyTypeNames[] = {
  "BOOLEAN",
  "TINYINT",
  "SMALLINT",
  "INTEGER",
  "BIGINT",
  "FLOAT",
  "REAL",
  "DOUBLE",
  "DECIMAL",
  "CHAR",
  "VARCHAR",
  "LONGVARCHAR",
  "DATE",
  "TIME",
  "TIMESTAMP",
  "BINARY",
  "VARBINARY",
  "LONGVARBINARY",
  "BLOB",
  "CLOB",
  "SQLXML",
  "ARRAY",
  "MAP",
  "STRUCT",
  "NULLTYPE",
  "JSON",
  "JAVA_OBJECT",
  "OTHER"
};
const std::map<int, const char*> _SnappyType_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(28, _kSnappyTypeValues, _kSnappyTypeNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kTransactionAttributeValues[] = {
  TransactionAttribute::AUTOCOMMIT,
  TransactionAttribute::READ_ONLY_CONNECTION,
  TransactionAttribute::WAITING_MODE,
  TransactionAttribute::DISABLE_BATCHING,
  TransactionAttribute::SYNC_COMMITS
};
const char* _kTransactionAttributeNames[] = {
  "AUTOCOMMIT",
  "READ_ONLY_CONNECTION",
  "WAITING_MODE",
  "DISABLE_BATCHING",
  "SYNC_COMMITS"
};
const std::map<int, const char*> _TransactionAttribute_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(5, _kTransactionAttributeValues, _kTransactionAttributeNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kRowIdLifetimeValues[] = {
  RowIdLifetime::ROWID_UNSUPPORTED,
  RowIdLifetime::ROWID_VALID_OTHER,
  RowIdLifetime::ROWID_VALID_SESSION,
  RowIdLifetime::ROWID_VALID_TRANSACTION,
  RowIdLifetime::ROWID_VALID_FOREVER
};
const char* _kRowIdLifetimeNames[] = {
  "ROWID_UNSUPPORTED",
  "ROWID_VALID_OTHER",
  "ROWID_VALID_SESSION",
  "ROWID_VALID_TRANSACTION",
  "ROWID_VALID_FOREVER"
};
const std::map<int, const char*> _RowIdLifetime_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(5, _kRowIdLifetimeValues, _kRowIdLifetimeNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kServiceFeatureValues[] = {
  ServiceFeature::ALL_PROCEDURES_CALLABLE,
  ServiceFeature::ALL_TABLES_SELECTABLE,
  ServiceFeature::INTEGRITY_ENHANCEMENT,
  ServiceFeature::NULLS_SORTED_HIGH,
  ServiceFeature::NULLS_SORTED_LOW,
  ServiceFeature::NULLS_SORTED_START,
  ServiceFeature::NULLS_SORTED_END,
  ServiceFeature::USES_LOCAL_FILES,
  ServiceFeature::USES_LOCAL_FILE_PER_TABLE,
  ServiceFeature::MIXEDCASE_IDENTIFIERS,
  ServiceFeature::MIXEDCASE_QUOTED_IDENTIFIERS,
  ServiceFeature::STORES_UPPERCASE_IDENTIFIERS,
  ServiceFeature::STORES_LOWERCASE_IDENTIFIERS,
  ServiceFeature::STORES_MIXEDCASE_IDENTIFIERS,
  ServiceFeature::STORES_UPPERCASE_QUOTED_IDENTIFIERS,
  ServiceFeature::STORES_LOWERCASE_QUOTED_IDENTIFIERS,
  ServiceFeature::STORES_MIXEDCASE_QUOTED_IDENTIFIERS,
  ServiceFeature::ALTER_TABLE_ADD_COLUMN,
  ServiceFeature::ALTER_TABLE_DROP_COLUMN,
  ServiceFeature::COLUMN_ALIASING,
  ServiceFeature::NULL_CONCAT_NON_NULL_IS_NULL,
  ServiceFeature::CONVERT,
  ServiceFeature::TABLE_CORRELATION_NAMES,
  ServiceFeature::TABLE_CORRELATION_NAMES_DIFFERENT,
  ServiceFeature::ORDER_BY_EXPRESSIONS,
  ServiceFeature::ORDER_BY_UNRELATED,
  ServiceFeature::GROUP_BY,
  ServiceFeature::GROUP_BY_UNRELATED,
  ServiceFeature::GROUP_BY_BEYOND_SELECT,
  ServiceFeature::LIKE_ESCAPE,
  ServiceFeature::MULTIPLE_RESULTSETS,
  ServiceFeature::MULTIPLE_TRANSACTIONS,
  ServiceFeature::NON_NULLABLE_COLUMNS,
  ServiceFeature::SQL_GRAMMAR_MINIMUM,
  ServiceFeature::SQL_GRAMMAR_CORE,
  ServiceFeature::SQL_GRAMMAR_EXTENDED,
  ServiceFeature::SQL_GRAMMAR_ANSI92_ENTRY,
  ServiceFeature::SQL_GRAMMAR_ANSI92_INTERMEDIATE,
  ServiceFeature::SQL_GRAMMAR_ANSI92_FULL,
  ServiceFeature::OUTER_JOINS,
  ServiceFeature::OUTER_JOINS_FULL,
  ServiceFeature::OUTER_JOINS_LIMITED,
  ServiceFeature::SCHEMAS_IN_DMLS,
  ServiceFeature::SCHEMAS_IN_PROCEDURE_CALLS,
  ServiceFeature::SCHEMAS_IN_TABLE_DEFS,
  ServiceFeature::SCHEMAS_IN_INDEX_DEFS,
  ServiceFeature::SCHEMAS_IN_PRIVILEGE_DEFS,
  ServiceFeature::AUTOCOMMIT_FAILURE_CLOSES_ALL_RESULTSETS,
  ServiceFeature::CATALOGS_IN_DMLS,
  ServiceFeature::CATALOGS_IN_PROCEDURE_CALLS,
  ServiceFeature::CATALOGS_IN_TABLE_DEFS,
  ServiceFeature::CATALOGS_IN_INDEX_DEFS,
  ServiceFeature::CATALOGS_IN_PRIVILEGE_DEFS,
  ServiceFeature::POSITIONED_DELETE,
  ServiceFeature::POSITIONED_UPDATE,
  ServiceFeature::SELECT_FOR_UPDATE,
  ServiceFeature::STORED_PROCEDURES,
  ServiceFeature::SUBQUERIES_IN_COMPARISONS,
  ServiceFeature::SUBQUERIES_IN_EXISTS,
  ServiceFeature::SUBQUERIES_IN_INS,
  ServiceFeature::SUBQUERIES_IN_QUANTIFIEDS,
  ServiceFeature::SUBQUERIES_CORRELATED,
  ServiceFeature::UNION,
  ServiceFeature::UNION_ALL,
  ServiceFeature::OPEN_CURSORS_ACROSS_COMMIT,
  ServiceFeature::OPEN_CURSORS_ACROSS_ROLLBACK,
  ServiceFeature::OPEN_STATEMENTS_ACROSS_COMMIT,
  ServiceFeature::OPEN_STATEMENTS_ACROSS_ROLLBACK,
  ServiceFeature::MAX_ROWSIZE_INCLUDES_BLOBSIZE,
  ServiceFeature::TRANSACTIONS,
  ServiceFeature::TRANSACTIONS_BOTH_DMLS_AND_DDLS,
  ServiceFeature::TRANSACTIONS_DMLS_ONLY,
  ServiceFeature::TRANSACTIONS_DDLS_IMPLICIT_COMMIT,
  ServiceFeature::TRANSACTIONS_DDLS_IGNORED,
  ServiceFeature::TRANSACTIONS_SAVEPOINTS,
  ServiceFeature::CALLABLE_NAMED_PARAMETERS,
  ServiceFeature::CALLABLE_MULTIPLE_OPEN_RESULTSETS,
  ServiceFeature::GENERATED_KEYS_RETRIEVAL,
  ServiceFeature::GENERATED_KEYS_ALWAYS_RETURNED,
  ServiceFeature::BATCH_UPDATES,
  ServiceFeature::RESULTSET_FORWARD_ONLY,
  ServiceFeature::RESULTSET_SCROLL_INSENSITIVE,
  ServiceFeature::RESULTSET_SCROLL_SENSITIVE,
  ServiceFeature::RESULTSET_HOLDABILITY_CLOSE_CURSORS_AT_COMMIT,
  ServiceFeature::RESULTSET_HOLDABILITY_HOLD_CURSORS_OVER_COMMIT,
  ServiceFeature::LOB_UPDATES_COPY,
  ServiceFeature::STATEMENT_POOLING,
  ServiceFeature::STORED_FUNCTIONS_USING_CALL
};
const char* _kServiceFeatureNames[] = {
  "ALL_PROCEDURES_CALLABLE",
  "ALL_TABLES_SELECTABLE",
  "INTEGRITY_ENHANCEMENT",
  "NULLS_SORTED_HIGH",
  "NULLS_SORTED_LOW",
  "NULLS_SORTED_START",
  "NULLS_SORTED_END",
  "USES_LOCAL_FILES",
  "USES_LOCAL_FILE_PER_TABLE",
  "MIXEDCASE_IDENTIFIERS",
  "MIXEDCASE_QUOTED_IDENTIFIERS",
  "STORES_UPPERCASE_IDENTIFIERS",
  "STORES_LOWERCASE_IDENTIFIERS",
  "STORES_MIXEDCASE_IDENTIFIERS",
  "STORES_UPPERCASE_QUOTED_IDENTIFIERS",
  "STORES_LOWERCASE_QUOTED_IDENTIFIERS",
  "STORES_MIXEDCASE_QUOTED_IDENTIFIERS",
  "ALTER_TABLE_ADD_COLUMN",
  "ALTER_TABLE_DROP_COLUMN",
  "COLUMN_ALIASING",
  "NULL_CONCAT_NON_NULL_IS_NULL",
  "CONVERT",
  "TABLE_CORRELATION_NAMES",
  "TABLE_CORRELATION_NAMES_DIFFERENT",
  "ORDER_BY_EXPRESSIONS",
  "ORDER_BY_UNRELATED",
  "GROUP_BY",
  "GROUP_BY_UNRELATED",
  "GROUP_BY_BEYOND_SELECT",
  "LIKE_ESCAPE",
  "MULTIPLE_RESULTSETS",
  "MULTIPLE_TRANSACTIONS",
  "NON_NULLABLE_COLUMNS",
  "SQL_GRAMMAR_MINIMUM",
  "SQL_GRAMMAR_CORE",
  "SQL_GRAMMAR_EXTENDED",
  "SQL_GRAMMAR_ANSI92_ENTRY",
  "SQL_GRAMMAR_ANSI92_INTERMEDIATE",
  "SQL_GRAMMAR_ANSI92_FULL",
  "OUTER_JOINS",
  "OUTER_JOINS_FULL",
  "OUTER_JOINS_LIMITED",
  "SCHEMAS_IN_DMLS",
  "SCHEMAS_IN_PROCEDURE_CALLS",
  "SCHEMAS_IN_TABLE_DEFS",
  "SCHEMAS_IN_INDEX_DEFS",
  "SCHEMAS_IN_PRIVILEGE_DEFS",
  "AUTOCOMMIT_FAILURE_CLOSES_ALL_RESULTSETS",
  "CATALOGS_IN_DMLS",
  "CATALOGS_IN_PROCEDURE_CALLS",
  "CATALOGS_IN_TABLE_DEFS",
  "CATALOGS_IN_INDEX_DEFS",
  "CATALOGS_IN_PRIVILEGE_DEFS",
  "POSITIONED_DELETE",
  "POSITIONED_UPDATE",
  "SELECT_FOR_UPDATE",
  "STORED_PROCEDURES",
  "SUBQUERIES_IN_COMPARISONS",
  "SUBQUERIES_IN_EXISTS",
  "SUBQUERIES_IN_INS",
  "SUBQUERIES_IN_QUANTIFIEDS",
  "SUBQUERIES_CORRELATED",
  "UNION",
  "UNION_ALL",
  "OPEN_CURSORS_ACROSS_COMMIT",
  "OPEN_CURSORS_ACROSS_ROLLBACK",
  "OPEN_STATEMENTS_ACROSS_COMMIT",
  "OPEN_STATEMENTS_ACROSS_ROLLBACK",
  "MAX_ROWSIZE_INCLUDES_BLOBSIZE",
  "TRANSACTIONS",
  "TRANSACTIONS_BOTH_DMLS_AND_DDLS",
  "TRANSACTIONS_DMLS_ONLY",
  "TRANSACTIONS_DDLS_IMPLICIT_COMMIT",
  "TRANSACTIONS_DDLS_IGNORED",
  "TRANSACTIONS_SAVEPOINTS",
  "CALLABLE_NAMED_PARAMETERS",
  "CALLABLE_MULTIPLE_OPEN_RESULTSETS",
  "GENERATED_KEYS_RETRIEVAL",
  "GENERATED_KEYS_ALWAYS_RETURNED",
  "BATCH_UPDATES",
  "RESULTSET_FORWARD_ONLY",
  "RESULTSET_SCROLL_INSENSITIVE",
  "RESULTSET_SCROLL_SENSITIVE",
  "RESULTSET_HOLDABILITY_CLOSE_CURSORS_AT_COMMIT",
  "RESULTSET_HOLDABILITY_HOLD_CURSORS_OVER_COMMIT",
  "LOB_UPDATES_COPY",
  "STATEMENT_POOLING",
  "STORED_FUNCTIONS_USING_CALL"
};
const std::map<int, const char*> _ServiceFeature_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(88, _kServiceFeatureValues, _kServiceFeatureNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kServiceFeatureParameterizedValues[] = {
  ServiceFeatureParameterized::TRANSACTIONS_SUPPORT_ISOLATION,
  ServiceFeatureParameterized::RESULTSET_TYPE,
  ServiceFeatureParameterized::RESULTSET_CONCURRENCY_READ_ONLY,
  ServiceFeatureParameterized::RESULTSET_CONCURRENCY_UPDATABLE,
  ServiceFeatureParameterized::RESULTSET_OWN_UPDATES_VISIBLE,
  ServiceFeatureParameterized::RESULTSET_OWN_DELETES_VISIBLE,
  ServiceFeatureParameterized::RESULTSET_OWN_INSERTS_VISIBLE,
  ServiceFeatureParameterized::RESULTSET_OTHERS_UPDATES_VISIBLE,
  ServiceFeatureParameterized::RESULTSET_OTHERS_DELETES_VISIBLE,
  ServiceFeatureParameterized::RESULTSET_OTHERS_INSERTS_VISIBLE,
  ServiceFeatureParameterized::RESULTSET_UPDATES_DETECTED,
  ServiceFeatureParameterized::RESULTSET_DELETES_DETECTED,
  ServiceFeatureParameterized::RESULTSET_INSERTS_DETECTED
};
const char* _kServiceFeatureParameterizedNames[] = {
  "TRANSACTIONS_SUPPORT_ISOLATION",
  "RESULTSET_TYPE",
  "RESULTSET_CONCURRENCY_READ_ONLY",
  "RESULTSET_CONCURRENCY_UPDATABLE",
  "RESULTSET_OWN_UPDATES_VISIBLE",
  "RESULTSET_OWN_DELETES_VISIBLE",
  "RESULTSET_OWN_INSERTS_VISIBLE",
  "RESULTSET_OTHERS_UPDATES_VISIBLE",
  "RESULTSET_OTHERS_DELETES_VISIBLE",
  "RESULTSET_OTHERS_INSERTS_VISIBLE",
  "RESULTSET_UPDATES_DETECTED",
  "RESULTSET_DELETES_DETECTED",
  "RESULTSET_INSERTS_DETECTED"
};
const std::map<int, const char*> _ServiceFeatureParameterized_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(13, _kServiceFeatureParameterizedValues, _kServiceFeatureParameterizedNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kServiceMetaDataCallValues[] = {
  ServiceMetaDataCall::CATALOGS,
  ServiceMetaDataCall::SCHEMAS,
  ServiceMetaDataCall::TABLES,
  ServiceMetaDataCall::TABLETYPES,
  ServiceMetaDataCall::COLUMNS,
  ServiceMetaDataCall::TABLEPRIVILEGES,
  ServiceMetaDataCall::COLUMNPRIVILEGES,
  ServiceMetaDataCall::PRIMARYKEYS,
  ServiceMetaDataCall::IMPORTEDKEYS,
  ServiceMetaDataCall::EXPORTEDKEYS,
  ServiceMetaDataCall::CROSSREFERENCE,
  ServiceMetaDataCall::PROCEDURES,
  ServiceMetaDataCall::FUNCTIONS,
  ServiceMetaDataCall::PROCEDURECOLUMNS,
  ServiceMetaDataCall::FUNCTIONCOLUMNS,
  ServiceMetaDataCall::ATTRIBUTES,
  ServiceMetaDataCall::TYPEINFO,
  ServiceMetaDataCall::SUPERTYPES,
  ServiceMetaDataCall::SUPERTABLES,
  ServiceMetaDataCall::VERSIONCOLUMNS,
  ServiceMetaDataCall::CLIENTINFOPROPS,
  ServiceMetaDataCall::PSEUDOCOLUMNS
};
const char* _kServiceMetaDataCallNames[] = {
  "CATALOGS",
  "SCHEMAS",
  "TABLES",
  "TABLETYPES",
  "COLUMNS",
  "TABLEPRIVILEGES",
  "COLUMNPRIVILEGES",
  "PRIMARYKEYS",
  "IMPORTEDKEYS",
  "EXPORTEDKEYS",
  "CROSSREFERENCE",
  "PROCEDURES",
  "FUNCTIONS",
  "PROCEDURECOLUMNS",
  "FUNCTIONCOLUMNS",
  "ATTRIBUTES",
  "TYPEINFO",
  "SUPERTYPES",
  "SUPERTABLES",
  "VERSIONCOLUMNS",
  "CLIENTINFOPROPS",
  "PSEUDOCOLUMNS"
};
const std::map<int, const char*> _ServiceMetaDataCall_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(22, _kServiceMetaDataCallValues, _kServiceMetaDataCallNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kSecurityMechanismValues[] = {
  SecurityMechanism::PLAIN,
  SecurityMechanism::DIFFIE_HELLMAN
};
const char* _kSecurityMechanismNames[] = {
  "PLAIN",
  "DIFFIE_HELLMAN"
};
const std::map<int, const char*> _SecurityMechanism_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(2, _kSecurityMechanismValues, _kSecurityMechanismNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kServerTypeValues[] = {
  ServerType::DRDA,
  ServerType::THRIFT_LOCATOR_CP,
  ServerType::THRIFT_LOCATOR_BP,
  ServerType::THRIFT_LOCATOR_CP_SSL,
  ServerType::THRIFT_LOCATOR_BP_SSL,
  ServerType::THRIFT_SNAPPY_CP,
  ServerType::THRIFT_SNAPPY_BP,
  ServerType::THRIFT_SNAPPY_CP_SSL,
  ServerType::THRIFT_SNAPPY_BP_SSL
};
const char* _kServerTypeNames[] = {
  "DRDA",
  "THRIFT_LOCATOR_CP",
  "THRIFT_LOCATOR_BP",
  "THRIFT_LOCATOR_CP_SSL",
  "THRIFT_LOCATOR_BP_SSL",
  "THRIFT_SNAPPY_CP",
  "THRIFT_SNAPPY_BP",
  "THRIFT_SNAPPY_CP_SSL",
  "THRIFT_SNAPPY_BP_SSL"
};
const std::map<int, const char*> _ServerType_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(9, _kServerTypeValues, _kServerTypeNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

int _kCursorUpdateOperationValues[] = {
  CursorUpdateOperation::UPDATE_OP,
  CursorUpdateOperation::INSERT_OP,
  CursorUpdateOperation::DELETE_OP
};
const char* _kCursorUpdateOperationNames[] = {
  "UPDATE",
  "INSERT",
  "DELETE"
};
const std::map<int, const char*> _CursorUpdateOperation_VALUES_TO_NAMES(::apache::thrift::TEnumIterator(3, _kCursorUpdateOperationValues, _kCursorUpdateOperationNames), ::apache::thrift::TEnumIterator(-1, NULL, NULL));

}}} // namespace
