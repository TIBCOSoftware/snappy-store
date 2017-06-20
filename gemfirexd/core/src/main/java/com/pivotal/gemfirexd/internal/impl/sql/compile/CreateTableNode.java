/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.compile.CreateTableNode

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package	com.pivotal.gemfirexd.internal.impl.sql.compile;

import com.gemstone.gemfire.cache.CustomEvictionAttributes;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.RegionAttributes;






import java.util.Properties;
import java.util.Set;
import java.util.Vector;

// GemStone changes BEGIN
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdEvictionCriteria;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.diag.SortedCSVProcedures;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.ServerGroupUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.ProviderList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecIndexRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLChar;
import com.pivotal.gemfirexd.internal.iapi.types.StringDataValue;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.DataDictionaryImpl;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdSysAsyncEventListenerRowFactory;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.TabInfoImpl;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ColumnInfo;
import com.pivotal.gemfirexd.internal.impl.sql.execute.CreateConstraintConstantAction;

/**
 * A CreateTableNode is the root of a QueryTree that represents a CREATE TABLE or DECLARE GLOBAL TEMPORARY TABLE
 * statement.
 *
 */

public class CreateTableNode extends DDLStatementNode
{
	private char				lockGranularity;
	private boolean				onCommitDeleteRows; //If true, on commit delete rows else on commit preserve rows of temporary table.
	private boolean				onRollbackDeleteRows; //If true, on rollback delete rows from temp table if it was logically modified in that UOW. true is the only supported value
	private Properties			properties;
	private TableElementList	tableElementList;
	protected int	tableType; //persistent table or global temporary table
	private ResultColumnList	resultColumns;
	private ResultSetNode		queryExpression;
//Gemstone changes BEGIN
	private DistributionDescriptor  distributionDesc;
//GEmstone Changes END	
	
    // the offset in SQL text where 'AS' clause begins (used for a query such as 'create table t1 
    // as select * from t2 with no data persistent')
    private int textToBeReplacedBegin;
    // the offset in SQL text where 'WITH NO DATA' ends
    private int textToBeReplacedEnd;
    // used only when there is a queryExpression otherwise null
    String sqlText; 
    // generated sqltext for 'create table as select *' query that replaces 
    // queryExpression with actual columns
    String generatedSqlTextForCTAS; 
	/**
	 * Initializer for a CreateTableNode for a base table
	 *
	 * @param newObjectName		The name of the new object being created (ie base table)
	 * @param tableElementList	The elements of the table: columns,
	 *				constraints, etc.
	 * @param properties		The optional list of properties associated with
	 *							the table.
	 * @param lockGranularity	The lock granularity.
	 *
	 * @exception StandardException		Thrown on error
	 */

	public void init(
			Object newObjectName,
			Object tableElementList,
			Object properties,
			Object lockGranularity)
		throws StandardException
	{
		tableType = TableDescriptor.BASE_TABLE_TYPE;
		this.lockGranularity = ((Character) lockGranularity).charValue();
		implicitCreateSchema = true;

		if (SanityManager.DEBUG)
		{
			if (this.lockGranularity != TableDescriptor.TABLE_LOCK_GRANULARITY &&
				this.lockGranularity != TableDescriptor.ROW_LOCK_GRANULARITY)
			{
				SanityManager.THROWASSERT(
				"Unexpected value for lockGranularity = " + this.lockGranularity);
			}
		}

		initAndCheck(newObjectName);
		this.tableElementList = (TableElementList) tableElementList;
		this.properties = (Properties) properties;
	}

	/**
	 * Initializer for a CreateTableNode for a global temporary table
	 *
	 * @param newObjectName		The name of the new object being declared (ie temporary table)
	 * @param tableElementList	The elements of the table: columns,
	 *				constraints, etc.
	 * @param properties		The optional list of properties associated with
	 *							the table.
	 * @param onCommitDeleteRows	If true, on commit delete rows else on commit preserve rows of temporary table.
	 * @param onRollbackDeleteRows	If true, on rollback, delete rows from temp tables which were logically modified. true is the only supported value
	 *
	 * @exception StandardException		Thrown on error
	 */

	public void init(
			Object newObjectName,
			Object tableElementList,
			Object properties,
			Object onCommitDeleteRows,
			Object onRollbackDeleteRows)
		throws StandardException
	{
		tableType = TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE;
		newObjectName = tempTableSchemaNameCheck(newObjectName);
		this.onCommitDeleteRows = ((Boolean) onCommitDeleteRows).booleanValue();
		this.onRollbackDeleteRows = ((Boolean) onRollbackDeleteRows).booleanValue();
		initAndCheck(newObjectName);
		this.tableElementList = (TableElementList) tableElementList;
		this.properties = (Properties) properties;

		if (SanityManager.DEBUG)
		{
			if (this.onRollbackDeleteRows == false)
			{
				SanityManager.THROWASSERT(
				"Unexpected value for onRollbackDeleteRows = " + this.onRollbackDeleteRows);
			}
		}
	}
	
	/**
	 * Initializer for a CreateTableNode for a base table create from a query
	 * 
	 * @param newObjectName		The name of the new object being created
	 * 	                        (ie base table).
	 * @param resultColumns		The optional column list.
	 * @param queryExpression	The query expression for the table.
	 */
	public void init(
			Object newObjectName,
			Object resultColumns,
			Object queryExpression)
		throws StandardException
	{
		tableType = TableDescriptor.BASE_TABLE_TYPE;
		lockGranularity = TableDescriptor.DEFAULT_LOCK_GRANULARITY;
		implicitCreateSchema = true;
		initAndCheck(newObjectName);
		this.resultColumns = (ResultColumnList) resultColumns;
		this.queryExpression = (ResultSetNode) queryExpression;
	}
	
   /**
     * Initializer for a CreateTableNode for a base table create from a query
     * 
     * @param newObjectName     The name of the new object being created
     *                          (ie base table).
     * @param resultColumns     The optional column list.
     * @param queryExpression   The query expression for the table.
     * @param textToBeReplacedBegin The offset in SQL text where 'AS' clause begins
     *                           (used for a query such as 'create table t1 
     *                           select * from t2 with no data persistent')
     * @param textToBeReplacedEnd The offset in SQL text where 'WITH NO DATA' ends
     * @param statementText     SQL text passed by the praser
     */
	public void init(
        Object newObjectName,
        Object resultColumns,
        Object queryExpression,
        Object textToBeReplacedBegin,
        Object textToBeReplacedEnd,
        Object statementText)
    throws StandardException 
    {
	   tableType = TableDescriptor.BASE_TABLE_TYPE;
	   lockGranularity = TableDescriptor.DEFAULT_LOCK_GRANULARITY;
	   implicitCreateSchema = true;
	   initAndCheck(newObjectName);
	   this.resultColumns = (ResultColumnList) resultColumns;
	   this.queryExpression = (ResultSetNode) queryExpression;
	   this.textToBeReplacedBegin = (Integer) textToBeReplacedBegin;
	   this.textToBeReplacedEnd = (Integer) textToBeReplacedEnd;
	   this.sqlText = (String) statementText;
	   this.properties = this.queryExpression.getDistributionNode().getTableProperties();
	}

	/**
	 * If no schema name specified for global temporary table, SESSION is the implicit schema.
	 * Otherwise, make sure the specified schema name for global temporary table is SESSION.
	 * @param objectName		The name of the new object being declared (ie temporary table)
	*/
	private Object tempTableSchemaNameCheck(Object objectName)
		throws StandardException {
		TableName	tempTableName = (TableName) objectName;
		if (tempTableName != null)
		{
			if (tempTableName.getSchemaName() == null)
				tempTableName.setSchemaName(SchemaDescriptor.STD_DECLARED_GLOBAL_TEMPORARY_TABLES_SCHEMA_NAME); //If no schema specified, SESSION is the implicit schema.
			else if (!(isSessionSchema(tempTableName.getSchemaName())))
				throw StandardException.newException(SQLState.LANG_DECLARED_GLOBAL_TEMP_TABLE_ONLY_IN_SESSION_SCHEMA);
		}
		return(tempTableName);
	}

	/**
	 * Convert this object to a String.  See comments in QueryTreeNode.java
	 * for how this should be done for tree printing.
	 *
	 * @return	This object as a String
	 */

	public String toString()
	{
		if (SanityManager.DEBUG)
		{
			String tempString = "tableElementList: " + "\n" + tableElementList + "\n";
			if (tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE)
			{
				tempString = tempString + "onCommitDeleteRows: " + "\n" + onCommitDeleteRows + "\n";
				tempString = tempString + "onRollbackDeleteRows: " + "\n" + onRollbackDeleteRows + "\n";
			} else
				tempString = tempString + "properties: " + "\n" + properties + "\n" + "lockGranularity: " + "\n" + lockGranularity + "\n";
			return super.toString() +  tempString;
		}
		else
		{
			return "";
		}
	}

	public String statementToString()
	{
		if (tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE)
			return "DECLARE GLOBAL TEMPORARY TABLE";
		else
			return "CREATE TABLE";
	}

	// We inherit the generate() method from DDLStatementNode.

	/**
	 * Bind this CreateTableNode.  This means doing any static error checking that can be
	 * done before actually creating the base table or declaring the global temporary table.
	 * For eg, verifying that the TableElementList does not contain any duplicate column names.
	 *
	 *
	 * @exception StandardException		Thrown on error
	 */

	public void bindStatement() throws StandardException
	{
		DataDictionary	dataDictionary = getDataDictionary();
		int numPrimaryKeys = 0;
		int numCheckConstraints = 0;
		int numReferenceConstraints = 0;
		int numUniqueConstraints = 0;

		if (queryExpression != null)
		{
			FromList fromList = (FromList) getNodeFactory().getNode(
					C_NodeTypes.FROM_LIST,
					getNodeFactory().doJoinOrderOptimization(),
					getContextManager());
			
			CompilerContext cc = getCompilerContext();
			ProviderList prevAPL = cc.getCurrentAuxiliaryProviderList();
			ProviderList apl = new ProviderList();
			
			try
			{
				cc.setCurrentAuxiliaryProviderList(apl);
				cc.pushCurrentPrivType(Authorizer.SELECT_PRIV);
				
				/* Bind the tables in the queryExpression */
				queryExpression =
					queryExpression.bindNonVTITables(dataDictionary, fromList);
				queryExpression = queryExpression.bindVTITables(fromList);
				
				/* Bind the expressions under the resultSet */
				queryExpression.bindExpressions(fromList);
				
				/* Bind the query expression */
				queryExpression.bindResultColumns(fromList);
				
				/* Reject any untyped nulls in the RCL */
				/* e.g. CREATE TABLE t1 (x) AS VALUES NULL WITH NO DATA */
				queryExpression.bindUntypedNullsToResultColumns(null);
			}
			finally
			{
				cc.popCurrentPrivType();
				cc.setCurrentAuxiliaryProviderList(prevAPL);
			}
			
			/* If there is an RCL for the table definition then copy the
			 * names to the queryExpression's RCL after verifying that
			 * they both have the same size.
			 */
			ResultColumnList qeRCL = queryExpression.getResultColumns();
			
			if (resultColumns != null)
			{
				if (resultColumns.size() != qeRCL.visibleSize())
				{
					throw StandardException.newException(
							SQLState.LANG_TABLE_DEFINITION_R_C_L_MISMATCH,
							getFullName());
				}
				qeRCL.copyResultColumnNames(resultColumns);
			}
			
			SchemaDescriptor sd = getSchemaDescriptor(tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE);
			int schemaCollationType = sd.getCollationType();
	    
			/* Create table element list from columns in query expression */
			tableElementList = new TableElementList();
			
			for (int index = 0; index < qeRCL.size(); index++)
			{
				ResultColumn rc = (ResultColumn) qeRCL.elementAt(index);
				if (rc.isGenerated()) 
				{
					continue;
				}
				/* Raise error if column name is system generated. */
				if (rc.isNameGenerated())
				{
					throw StandardException.newException(
							SQLState.LANG_TABLE_REQUIRES_COLUMN_NAMES);
				}

				DataTypeDescriptor dtd = rc.getExpression().getTypeServices();
				if ((dtd != null) && !dtd.isUserCreatableType())
				{
					throw StandardException.newException(
							SQLState.LANG_INVALID_COLUMN_TYPE_CREATE_TABLE,
							dtd.getFullSQLTypeName(),
							rc.getName());
				}
				//DERBY-2879  CREATE TABLE AS <subquery> does not maintain the 
				//collation for character types. 
				//eg for a territory based collation database
				//create table t as select tablename from sys.systables with no data;
				//Derby at this point does not support for a table's character 
				//columns to have a collation different from it's schema's
				//collation. Which means that in a territory based database, 
				//the query above will cause table t's character columns to
				//have collation of UCS_BASIC but the containing schema of t
				//has collation of territory based. This is not supported and
				//hence we will throw an exception below for the query above in
				//a territory based database. 
				if (dtd.getTypeId().isStringTypeId() && 
						dtd.getCollationType() != schemaCollationType)
				{
					throw StandardException.newException(
							SQLState.LANG_CAN_NOT_CREATE_TABLE,
							dtd.getCollationName(),
							DataTypeDescriptor.getCollationName(schemaCollationType));
				}

				ColumnDefinitionNode column = (ColumnDefinitionNode) getNodeFactory().getNode
                    ( C_NodeTypes.COLUMN_DEFINITION_NODE, rc.getName(), null, rc.getType(), null, getContextManager() );
				tableElementList.addTableElement(column);
			}
			// Gemstone changes BEGIN
			tableElementList.setContextManager(getContextManager());
			// Gemstone changes END
			tableElementList.addTableElement(this.queryExpression.getDistributionNode());
		} else {
			//Set the collation type and collation derivation of all the 
			//character type columns. Their collation type will be same as the 
			//collation of the schema they belong to. Their collation 
			//derivation will be "implicit". 
			//Earlier we did this in makeConstantAction but that is little too 
			//late (DERBY-2955)
			//eg 
			//CREATE TABLE STAFF9 (EMPNAME CHAR(20),
			//  CONSTRAINT STAFF9_EMPNAME CHECK (EMPNAME NOT LIKE 'T%'))
			//For the query above, when run in a territory based db, we need 
			//to have the correct collation set in bind phase of create table 
			//so that when LIKE is handled in LikeEscapeOperatorNode, we have 
			//the correct collation set for EMPNAME otherwise it will throw an 
			//exception for 'T%' having collation of territory based and 
			//EMPNAME having the default collation of UCS_BASIC
			tableElementList.setCollationTypesOnCharacterStringColumns(
					getSchemaDescriptor(tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE));
		}

		tableElementList.validate(this, dataDictionary, (TableDescriptor) null);

		/* Only 1012 columns allowed per table */
		if (tableElementList.countNumberOfColumns() > Limits.DB2_MAX_COLUMNS_IN_TABLE)
		{
			throw StandardException.newException(SQLState.LANG_TOO_MANY_COLUMNS_IN_TABLE_OR_VIEW,
				String.valueOf(tableElementList.countNumberOfColumns()),
				getRelativeName(),
				String.valueOf(Limits.DB2_MAX_COLUMNS_IN_TABLE));
		}

		numPrimaryKeys = tableElementList.countConstraints(
								DataDictionary.PRIMARYKEY_CONSTRAINT);

		/* Only 1 primary key allowed per table */
		if (numPrimaryKeys > 1)
		{
			throw StandardException.newException(SQLState.LANG_TOO_MANY_PRIMARY_KEY_CONSTRAINTS, getRelativeName());
		}

		/* Check the validity of all check constraints */
		numCheckConstraints = tableElementList.countConstraints(
									DataDictionary.CHECK_CONSTRAINT);

		numReferenceConstraints = tableElementList.countConstraints(
									DataDictionary.FOREIGNKEY_CONSTRAINT);

		numUniqueConstraints = tableElementList.countConstraints(
									DataDictionary.UNIQUE_CONSTRAINT);

		//temp tables can't have primary key or check or foreign key or unique constraints defined on them
		if ((tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE) &&
			(numPrimaryKeys > 0 || numCheckConstraints > 0 || numReferenceConstraints > 0 || numUniqueConstraints > 0))
				throw StandardException.newException(SQLState.LANG_NOT_ALLOWED_FOR_DECLARED_GLOBAL_TEMP_TABLE);

		//each of these constraints have a backing index in the back. We need to make sure that a table never has more
		//more than 32767 indexes on it and that is why this check.
		if ((numPrimaryKeys + numReferenceConstraints + numUniqueConstraints) > Limits.DB2_MAX_INDEXES_ON_TABLE)
		{
			throw StandardException.newException(SQLState.LANG_TOO_MANY_INDEXES_ON_TABLE, 
				String.valueOf(numPrimaryKeys + numReferenceConstraints + numUniqueConstraints),
				getRelativeName(),
				String.valueOf(Limits.DB2_MAX_INDEXES_ON_TABLE));
		}

// GemStone changes BEGIN
		RegionAttributes<?, ?> attrs = getRegionAttributes();
		PartitionAttributes<?, ?> pattrs;
		CustomEvictionAttributes ceAttrs;
		GfxdPartitionResolver resolver = null;
		GfxdEvictionCriteria evictionCriteria = null;
		if (attrs != null) {
		  if ((pattrs = attrs.getPartitionAttributes()) != null &&
		      pattrs.getPartitionResolver() instanceof GfxdPartitionResolver) {
		    resolver = (GfxdPartitionResolver)pattrs.getPartitionResolver();
		  }
		  if ((ceAttrs = attrs.getCustomEvictionAttributes()) != null) {
		    evictionCriteria = (GfxdEvictionCriteria)ceAttrs
		        .getCriteria();
		  }
		}
		if (numCheckConstraints > 0 || resolver != null
		    || evictionCriteria != null)
		/* if (numCheckConstraints > 0) */
// GemStone changes END
		{
			/* In order to check the validity of the check constraints
			 * we must goober up a FromList containing a single table,
			 * the table being created, with an RCL containing the
			 * new columns and their types.  This will allow us to
			 * bind the constraint definition trees against that
			 * FromList.  When doing this, we verify that there are
			 * no nodes which can return non-deterministic results.
			 */
			FromList fromList = (FromList) getNodeFactory().getNode(
									C_NodeTypes.FROM_LIST,
									getNodeFactory().doJoinOrderOptimization(),
									getContextManager());
			FromBaseTable table = (FromBaseTable)
									getNodeFactory().getNode(
										C_NodeTypes.FROM_BASE_TABLE,
										getObjectName(),
										null,
										null,
										null,
										getContextManager());
			table.setTableNumber(0);
			fromList.addFromTable(table);
			table.setResultColumns((ResultColumnList) getNodeFactory().getNode(
												C_NodeTypes.RESULT_COLUMN_LIST,
												getContextManager()));
			tableElementList.appendNewColumnsToRCL(table);

// GemStone changes BEGIN
			// Bind the ranges/lists/expressions for PartitionResolver
			// created for PARTITION BY clause
			if (resolver != null) {
                          // Check for aggregates in the partition expression
			  // (i.e. PARTITION BY (MAX(COL1)))
			  Vector		 aggregateVector = new Vector();

			  // Subqueries - the second parameter - should be blocked by the
			  // grammar, which does not allow a subquery inside the 
			  // partitioning expression
			  // However, aggs need to be detected
			  resolver.bindExpression(fromList, null, aggregateVector);
			  if (aggregateVector.size() > 0)
			  {
			    // Someone tried to push an agg into the partitioning expression
			    throw StandardException.newException(SQLState.LANG_NO_AGGREGATES_IN_WHERE_CLAUSE);
			  }
			}

			if (numCheckConstraints > 0)
// GemStone changes END
			/* Now that we've finally goobered stuff up, bind and validate
			 * the check constraints.
			 */
			tableElementList.bindAndValidateCheckConstraints(fromList);
			
// GemStone changes BEGIN
                        if (evictionCriteria != null) {
                          evictionCriteria.bindExpression(fromList, lcc);
                        }
// GemStone changes END
		}
// GemStone changes BEGIN
		if(tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE) {
                  this.distributionDesc = tableElementList
                      .validateAndResolveDistributionPolicy();
		}
		if (resolver != null) {
		  resolver.updateDistributionDescriptor(this.distributionDesc);
		}
		//validateWanServerGroups();
		//validateAsycnEventListenerServerGroups();
// GemStone changes END
  }

  // GemStone changes BEGIN
  private void validateWanServerGroups()
      throws StandardException {
    if (!ServerGroupUtils.isDataStore() || distributionDesc == null
        || distributionDesc.getServerGroups().isEmpty()) {
      return;
    }
    Set<String> senderIds = getGatewaySenderIds();
    // for each id verify if the table's server group is correct or not
    if (senderIds != null && !senderIds.isEmpty()) {
      for (String id : senderIds) {
        // check in the sys table
        TransactionController tc = lcc.getTransactionExecute();
        ExecIndexRow keyRow = getDataDictionary().getExecutionFactory()
            .getIndexableRow(1);
        keyRow.setColumn(1, new SQLChar(id));
        TabInfoImpl ti = ((DataDictionaryImpl)getDataDictionary())
            .getNonCoreTI(DataDictionaryImpl.GATEWAYSENDERS_CATALOG_NUM);
        ExecRow oldRow = ti.getRow(tc, keyRow, 0);
        if (oldRow != null) {
          DataValueDescriptor dvd = oldRow
              .getColumn(GfxdSysAsyncEventListenerRowFactory.SERVER_GROUPS);
          String senderGroups = dvd.getString();
          String tableGroups = "";
          for (String group : distributionDesc.getServerGroups()) {
            if (tableGroups.isEmpty()) {
              tableGroups = tableGroups.concat(group);
            }
            else {
              tableGroups = tableGroups.concat("," + group);
            }
          }
          // TODO: Yogesh, the serverGroups should be sorted
          if (SortedCSVProcedures.groupsIntersection(senderGroups, tableGroups)
              .isEmpty()) {
            throw StandardException
                .newException("Can not attatch a GatewaySender "
                    + id
                    + " to this table. At they should have one common server group. Table's server group are "
                    + tableGroups + " server groups for GatewaySender are "
                    + senderGroups);
          }
        }
      }
    }
  }

  private void validateAsycnEventListenerServerGroups() throws StandardException {
    if (!ServerGroupUtils.isDataStore() || distributionDesc == null
        || distributionDesc.getServerGroups().isEmpty()) {
      return;
    }
    Set<String> senderIds = getGatewaySenderIds();
    // for each id verify if the table's server group is correct or not
    if (senderIds != null && !senderIds.isEmpty()) {
      for (String id : senderIds) {
        // check in the sys table
        TransactionController tc = lcc.getTransactionExecute();
        ExecIndexRow keyRow = getDataDictionary().getExecutionFactory()
            .getIndexableRow(1);
        keyRow.setColumn(1, new SQLChar(id));
        TabInfoImpl ti = ((DataDictionaryImpl)getDataDictionary())
            .getNonCoreTI(DataDictionaryImpl.ASYNCEVENTLISTENERS_CATALOG_NUM);
        ExecRow oldRow = ti.getRow(tc, keyRow, 0);
        if (oldRow != null) {
          DataValueDescriptor dvd = oldRow
              .getColumn(GfxdSysAsyncEventListenerRowFactory.SERVER_GROUPS);
          String listenerGropus = dvd.getString();
          String tableGroups = "";
          for (String group : distributionDesc.getServerGroups()) {
            if (tableGroups.isEmpty()) {
              tableGroups = tableGroups.concat(group);
            }
            else {
              tableGroups = tableGroups.concat("," + group);
            }
          }
          // TODO: Yogesh, the serverGroups should be sorted
          if (SortedCSVProcedures.groupsIntersection(listenerGropus,
              tableGroups).isEmpty()) {
            throw StandardException
                .newException("Can not attatch a AsyncEventListener "
                    + id
                    + " to this table. At they should have one common server group. Table's server group are "
                    + tableGroups
                    + " server groups for AsyncEventListener are "
                    + listenerGropus);
          }
        }
      }
    }
  }
  
  private RegionAttributes<?, ?> getRegionAttributes() {
    Properties props = null;
    if (properties != null) {
      props = properties;
    }
    else if(this.queryExpression == null) {
      return null;
    }
    else if (this.queryExpression.getDistributionNode() != null) {
      props = this.queryExpression.getDistributionNode().getTableProperties();
    }
    if (props != null) {
      return (RegionAttributes<?, ?>)props
          .get(GfxdConstants.REGION_ATTRIBUTES_KEY);
    }
    else {
      return null;
    }
  }

  private Set<String> getGatewaySenderIds() {
    Properties props = null;
    if (properties != null) {
      props = properties;
    }
    else if(this.queryExpression == null) {
      return null;
    }
    else if (this.queryExpression.getDistributionNode() != null) {
      props = this.queryExpression.getDistributionNode().getTableProperties();
    }
    if (props != null) {
      RegionAttributes<?, ?> attrs = (RegionAttributes<?, ?>)props
          .get(GfxdConstants.REGION_ATTRIBUTES_KEY);
      if (attrs != null) {
        Set<String> ids = attrs.getGatewaySenderIds();
        return ids;
      }
    }
    return null;
  }
//GemStone changes END

	/**
	 * Return true if the node references SESSION schema tables (temporary or permanent)
	 *
	 * @return	true if references SESSION schema tables, else false
	 *
	 * @exception StandardException		Thrown on error
	 */
	public boolean referencesSessionSchema()
		throws StandardException
	{
		//If table being created/declared is in SESSION schema, then return true.
		return isSessionSchema(getSchemaDescriptor(tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE));
	}

	/**
	 * Create the Constant information that will drive the guts of Execution.
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public ConstantAction	makeConstantAction() throws StandardException
	{
		TableElementList		coldefs = tableElementList;

		// for each column, stuff system.column
		ColumnInfo[] colInfos = new ColumnInfo[coldefs.countNumberOfColumns()];

	    int numConstraints = coldefs.genColumnInfos(colInfos);
	    
	    RegionAttributes<?, ?> attrs = getRegionAttributes();
	    PartitionAttributes<?, ?> pattrs;
	    GfxdPartitionResolver resolver;
	    if (attrs != null) {
	      if ((pattrs = attrs.getPartitionAttributes()) != null &&
	          pattrs.getPartitionResolver() instanceof GfxdPartitionResolver) {
	        resolver = (GfxdPartitionResolver)pattrs
	            .getPartitionResolver();
	        String[] partition_columns = resolver.getColumnNames();
	        if (partition_columns != null && partition_columns.length > 0) {
	          for (String c : partition_columns) {
	            for (int i = 0; i < colInfos.length; i++) {
	              if (c.equalsIgnoreCase(colInfos[i].name) &&
	                  colInfos[i].dataType.getTypeId().isJSONTypeId()) {
	                throw StandardException.newException(SQLState.NOT_IMPLEMENTED,
	                    "JSON column '" + c + "' in partition by clause");
	              }
	            }
	          }
	        }
	      }
	    }

		/* If we've seen a constraint, then build a constraint list */
		CreateConstraintConstantAction[] conActions = null;

		SchemaDescriptor sd = getSchemaDescriptor(tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE);
		
		if (numConstraints > 0)
		{
			conActions =
                new CreateConstraintConstantAction[numConstraints];

			coldefs.genConstraintActions(true,
                conActions, getRelativeName(), sd, getDataDictionary());
		}
		// GemStone changes BEGIN
	        if(properties==null) {
	          if (queryExpression != null) {
	            properties=queryExpression.getDistributionNode().getTableProperties();
	          }
	        }
	        if(tableType != TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE) {
	          properties.put(GfxdConstants.DISTRIBUTION_DESCRIPTOR_KEY, this.distributionDesc);
	        }
	        
	        if (this.queryExpression != null) {
	          this.generatedSqlTextForCTAS = getSQLTextForCTAS(colInfos);
	        } 
	        // GemStone changes END
	        
        // if the any of columns are "long" and user has not specified a
        // page size, set the pagesize to 32k.
        // Also in case where the approximate sum of the column sizes is
        // greater than the bump threshold , bump the pagesize to 32k

        boolean table_has_long_column = false;
        int approxLength = 0;

        for (int i = 0; i < colInfos.length; i++)
        {
			DataTypeDescriptor dts = colInfos[i].dataType;
            if (dts.getTypeId().isLongConcatableTypeId())
            {
                table_has_long_column = true;
                break;
            }

            approxLength += dts.getTypeId().getApproximateLengthInBytes(dts);
        }

        if (table_has_long_column || (approxLength > Property.TBL_PAGE_SIZE_BUMP_THRESHOLD))
        {
			if (((properties == null) ||
                 (properties.get(Property.PAGE_SIZE_PARAMETER) == null)) &&
                (PropertyUtil.getServiceProperty(
                     getLanguageConnectionContext().getTransactionCompile(),
                     Property.PAGE_SIZE_PARAMETER) == null))
            {
                // do not override the user's choice of page size, whether it
                // is set for the whole database or just set on this statement.

                if (properties == null)
                    properties = new Properties();

                properties.put(
                    Property.PAGE_SIZE_PARAMETER,
                    Property.PAGE_SIZE_DEFAULT_LONG);
            }
        }

		return(
            getGenericConstantActionFactory().getCreateTableConstantAction(
                sd.getSchemaName(),
                getRelativeName(),
                tableType,
                colInfos,
                conActions,
                properties,
                lockGranularity,
                onCommitDeleteRows,
                onRollbackDeleteRows, 
                generatedSqlTextForCTAS));
	}
	
	/**
	 * For a query such as 'CREATE TABLE T1 AS SELECT * FROM T2 
	 * WITH NO DATA PERSISTENT', this returns an internally generated SQL text
	 * after replacing the query expression with actual column information.
	 *  
	 * <P>In other words, for the above mentioned query, SQL text that will be 
	 * returned will be 'CREATE TABLE T1 (COL1 INTEGER, COL2 DECIMAL(30,20))
	 *  PERSISTENT' 
	 *  
	 *  Return null if the query does not contain {@link #queryExpression} 
	 *  (SQL text is null)
	 * 
	 * @param colInfos array of ColumnInfo 
	 * @return returns an internally generated SQL text for a query with
	 * {@link #queryExpression}. 
	 */
	  public String getSQLTextForCTAS(ColumnInfo[] colInfos) {
	    if (this.sqlText == null)
	      return null;
	    StringBuilder sb = new StringBuilder();
	    sb.append(this.sqlText.substring(0, this.textToBeReplacedBegin));
	    sb.append("(");
	    for (int i = 0; i < colInfos.length; i++) {
	      sb.append(colInfos[i].name);
	      sb.append(" ");
	      // default text contains datatype and precision/scale for 
	      // column
	      if (colInfos[i].getDefaultInfo() != null) {
	        sb.append(colInfos[i].getDefaultInfo().getDefaultText());
	      } else {
	        sb.append(colInfos[i].getType().toString());
	      }
	      if (i != colInfos.length - 1) {
	        sb.append(", ");
	      }
	    }
	    sb.append(")");
	    sb.append(this.sqlText.substring(this.textToBeReplacedEnd + 1));
	    return sb.toString();
	  }

	/**
	 * Accept the visitor for all visitable children of this node.
	 * 
	 * @param v the visitor
	 *
	 * @exception StandardException on error
	 */
	void acceptChildren(Visitor v)
		throws StandardException
	{
		super.acceptChildren(v);

		if (tableElementList != null)
		{
			tableElementList.accept(v);
		}
	}

}
