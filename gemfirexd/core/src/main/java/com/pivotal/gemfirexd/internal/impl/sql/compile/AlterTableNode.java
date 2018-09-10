/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.compile.AlterTableNode

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







import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.Limits;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.C_NodeTypes;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitable;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Visitor;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.types.StringDataValue;
import com.pivotal.gemfirexd.internal.impl.sql.execute.AlterTableConstantAction;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ColumnInfo;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ConstraintConstantAction;

/**
 * A AlterTableNode represents a DDL statement that alters a table.
 * It contains the name of the object to be created.
 *
 */

public class AlterTableNode extends DDLStatementNode
{
	// The alter table action
	public	TableElementList	tableElementList = null;
	public  char				lockGranularity;
	public	boolean				compressTable = false;
	public	boolean				sequential = false;
	public	int					behavior;	// currently for drop column

	public	TableDescriptor		baseTable;

	protected	int						numConstraints;

	private		int				changeType = UNKNOWN_TYPE;

	private boolean             truncateTable = false;

	// constant action arguments

	protected	SchemaDescriptor			schemaDescriptor = null;
	protected	ColumnInfo[] 				colInfos = null;
	protected	ConstraintConstantAction[]	conActions = null;
        private boolean isSet;
  private int rowLevelSecurityAction = AlterTableConstantAction.ROW_LEVEL_SECURITY_UNCHANGED;


	/**
	 * Initializer for a TRUNCATE TABLE
	 *
	 * @param objectName		The name of the table being truncated
	 * @exception StandardException		Thrown on error
	 */

	public void init(Object objectName)
		throws StandardException
	{
		initAndCheck(objectName);
		/* For now, this init() only called for truncate table */
		truncateTable = true;
		schemaDescriptor = getSchemaDescriptor();
	}

	/**
	 * Initializer for a AlterTableNode for COMPRESS
	 *
	 * @param objectName		The name of the table being altered
	 * @param sequential		Whether or not the COMPRESS is SEQUENTIAL
	 *
	 * @exception StandardException		Thrown on error
	 */

	public void init(Object objectName,
					 Object sequential)
		throws StandardException
	{
		initAndCheck(objectName);

		this.sequential = ((Boolean) sequential).booleanValue();
		/* For now, this init() only called for compress table */
		compressTable = true;

		schemaDescriptor = getSchemaDescriptor();
	}

	/**
	 * Initializer for a AlterTableNode
	 *
	 * @param objectName		The name of the table being altered
	 * @param tableElementList	The alter table action
	 * @param lockGranularity	The new lock granularity, if any
	 * @param changeType		ADD_TYPE or DROP_TYPE
	 *
	 * @exception StandardException		Thrown on error
	 */

	public void init(
							Object objectName,
							Object tableElementList,
							Object lockGranularity,
							Object changeType,
							Object isSet,
							Object behavior,
							Object sequential, Object rowLevelSecurity )
		throws StandardException
	{
		initAndCheck(objectName);
		this.tableElementList = (TableElementList) tableElementList;
		this.lockGranularity = ((Character) lockGranularity).charValue();

		int[]	ct = (int[]) changeType, bh = (int[]) behavior;
		this.changeType = ct[0];
		this.behavior = bh[0];
		boolean[]	seq = (boolean[]) sequential;
		this.sequential = seq[0];
		this.rowLevelSecurityAction = ((Integer)rowLevelSecurity).intValue();
		// GemStone changes BEGIN
		this.isSet = ((boolean[])isSet)[0];
		// GemStone changes END
		switch ( this.changeType )
		{
		    case ADD_TYPE:
		    case DROP_TYPE:
		    case MODIFY_TYPE:
		    case LOCKING_TYPE:

				break;

		    default:

				throw StandardException.newException(SQLState.NOT_IMPLEMENTED);
		}

		schemaDescriptor = getSchemaDescriptor();
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
			return super.toString() +
				"objectName: " + "\n" + getObjectName() + "\n" +
				"tableElementList: " + "\n" + tableElementList + "\n" +
				"lockGranularity: " + "\n" + lockGranularity + "\n" +
				"compressTable: " + "\n" + compressTable + "\n" +
				"sequential: " + "\n" + sequential + "\n" +
				"truncateTable: " + "\n" + truncateTable + "\n";
		}
		else
		{
			return "";
		}
	}

	public String statementToString()
	{
		if(truncateTable)
			return "TRUNCATE TABLE";
		else
			return "ALTER TABLE";
	}

	public boolean isTruncateTable()
	{
		return truncateTable;
	}

	public	int	getChangeType() { return changeType; }

	// We inherit the generate() method from DDLStatementNode.

	/**
	 * Bind this AlterTableNode.  This means doing any static error
	 * checking that can be done before actually creating the table.
	 * For example, verifying that the user is not trying to add a 
	 * non-nullable column.
	 *
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void bindStatement() throws StandardException
	{
		DataDictionary	dd = getDataDictionary();
		int					numCheckConstraints = 0;
		int numBackingIndexes = 0;

		/*
		** Get the table descriptor.  Checks the schema
		** and the table.
		*/
		baseTable = getTableDescriptor();
		//throw an exception if user is attempting to alter a temporary table
		if (baseTable.getTableType() == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE)
		{
				throw StandardException.newException(SQLState.LANG_NOT_ALLOWED_FOR_DECLARED_GLOBAL_TEMP_TABLE);
		}

		/* Statement is dependent on the TableDescriptor */
		getCompilerContext().createDependency(baseTable);

		//If we are dealing with add column character type, then set that 
		//column's collation type to be the collation type of the schema.
		//The collation derivation of such a column would be "implicit".
		if (changeType == ADD_TYPE) {//the action is of type add.
			if (tableElementList != null) {//check if is is add column
				for (int i=0; i<tableElementList.size();i++) {
					if (tableElementList.elementAt(i) instanceof ColumnDefinitionNode) {
						ColumnDefinitionNode cdn = (ColumnDefinitionNode) tableElementList.elementAt(i);
						//check if we are dealing with add character column
						if (cdn.getType().getTypeId().isStringTypeId()) {
							//we found what we are looking for. Set the 
							//collation type of this column to be the same as
							//schema descriptor's collation. Set the collation
							//derivation as implicit
							cdn.setCollationType(schemaDescriptor.getCollationType());
			        	}						
					}
				}
				
			}
		}
		if (tableElementList != null)
		{
			tableElementList.validate(this, dd, baseTable);

			/* Only 1012 columns allowed per table */
			if ((tableElementList.countNumberOfColumns() + baseTable.getNumberOfColumns()) > Limits.DB2_MAX_COLUMNS_IN_TABLE)
			{
				throw StandardException.newException(SQLState.LANG_TOO_MANY_COLUMNS_IN_TABLE_OR_VIEW,
					String.valueOf(tableElementList.countNumberOfColumns() + baseTable.getNumberOfColumns()),
					getRelativeName(),
					String.valueOf(Limits.DB2_MAX_COLUMNS_IN_TABLE));
			}
			/* Number of backing indexes in the alter table statment */
			numBackingIndexes = tableElementList.countConstraints(DataDictionary.PRIMARYKEY_CONSTRAINT) +
									tableElementList.countConstraints(DataDictionary.FOREIGNKEY_CONSTRAINT) +
									tableElementList.countConstraints(DataDictionary.UNIQUE_CONSTRAINT);
			/* Check the validity of all check constraints */
			numCheckConstraints = tableElementList.countConstraints(
									DataDictionary.CHECK_CONSTRAINT);
		}

		//If the sum of backing indexes for constraints in alter table statement and total number of indexes on the table
		//so far is more than 32767, then we need to throw an exception 
		if ((numBackingIndexes + baseTable.getTotalNumberOfIndexes()) > Limits.DB2_MAX_INDEXES_ON_TABLE)
		{
			throw StandardException.newException(SQLState.LANG_TOO_MANY_INDEXES_ON_TABLE, 
				String.valueOf(numBackingIndexes + baseTable.getTotalNumberOfIndexes()),
				getRelativeName(),
				String.valueOf(Limits.DB2_MAX_INDEXES_ON_TABLE));
		}

		if (numCheckConstraints > 0)
		{
			/* In order to check the validity of the check constraints
			 * we must goober up a FromList containing a single table, 
			 * the table being alter, with an RCL containing the existing and
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
			fromList.addFromTable(table);
			fromList.bindTables(dd,
							(FromList) getNodeFactory().getNode(
								C_NodeTypes.FROM_LIST,
								getNodeFactory().doJoinOrderOptimization(),
								getContextManager()));
			tableElementList.appendNewColumnsToRCL(table);

			/* Now that we've finally goobered stuff up, bind and validate
			 * the check constraints.
			 */
			tableElementList.bindAndValidateCheckConstraints(fromList);

		}
		
    if (isPrivilegeCollectionRequired()) {
      CompilerContext cc = getCompilerContext();
      cc.pushCurrentPrivType(Authorizer.ALTER_PRIV);
      getCompilerContext().addRequiredTablePriv(baseTable);
      cc.popCurrentPrivType();
    }

		/* Unlike most other DDL, we will make this ALTER TABLE statement
		 * dependent on the table being altered.  In general, we try to
		 * avoid this for DDL, but we are already requiring the table to
		 * exist at bind time (not required for create index) and we don't
		 * want the column ids to change out from under us before
		 * execution.
		 */
		getCompilerContext().createDependency(baseTable);
	}

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
		//If alter table is on a SESSION schema table, then return true. 
		return isSessionSchema(baseTable.getSchemaName());
	}

	/**
	 * Create the Constant information that will drive the guts of Execution.
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public ConstantAction	makeConstantAction() throws StandardException
	{
		prepConstantAction();

		return	getGenericConstantActionFactory().getAlterTableConstantAction(schemaDescriptor,
											 getRelativeName(),
											 baseTable.getUUID(),
											 baseTable.getHeapConglomerateId(),
											 TableDescriptor.BASE_TABLE_TYPE, // TODO check if column table?
											 colInfos,
											 conActions,
											 lockGranularity,
											 compressTable,
											 isSet,
											 behavior,
        								                 sequential,
 										         truncateTable, this.rowLevelSecurityAction);
	}

	/**
	  *	Generate arguments to constant action. Called by makeConstantAction() in this class and in
	  *	our subclass RepAlterTableNode.
	  *
	  *
	  * @exception StandardException		Thrown on failure
	  */
	private void	prepConstantAction() throws StandardException
	{
		if (tableElementList != null)
		{
			genColumnInfo();
		}

		/* If we've seen a constraint, then build a constraint list */

		if (numConstraints > 0)
		{
			conActions = new ConstraintConstantAction[numConstraints];

			tableElementList.genConstraintActions(false, conActions, getRelativeName(), schemaDescriptor,
												  getDataDictionary());
		}
	}
	  
	/**
	  *	Generate the ColumnInfo argument for the constant action. Return the number of constraints.
	  */
	public	void	genColumnInfo()
	{
		// for each column, stuff system.column
		colInfos = new ColumnInfo[tableElementList.countNumberOfColumns()]; 

	    numConstraints = tableElementList.genColumnInfos(colInfos);
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

	/*
	 * class interface
	 */
}




