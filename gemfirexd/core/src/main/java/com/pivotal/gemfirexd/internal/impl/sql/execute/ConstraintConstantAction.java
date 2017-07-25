/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.ConstraintConstantAction

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

package com.pivotal.gemfirexd.internal.impl.sql.execute;

// GemStone changes BEGIN
import java.util.Set;

import com.gemstone.gemfire.internal.cache.BucketAdvisor;
import com.gemstone.gemfire.internal.cache.ColocationHelper;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion.RecoveryLock;
import com.gemstone.gemfire.internal.cache.partitioned.RegionAdvisor;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.PreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ForeignKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ReferencedKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.GroupFetchScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.NumberDataValue;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
// GemStone changes END
/**
 *	This class  describes actions that are ALWAYS performed for a
 *	constraint creation at Execution time.
 *
 *	@version 0.1
 */

public abstract class ConstraintConstantAction extends DDLSingleTableConstantAction 
{

	protected	String			constraintName;
	protected	int				constraintType;
	protected	String			tableName;
	protected	String			schemaName;
	protected	UUID			schemaId;
	protected  IndexConstantAction indexAction;

	// CONSTRUCTORS
	/**
	 *	Make one of these puppies.
	 *
	 *  @param constraintName	Constraint name.
	 *  @param constraintType	Constraint type.
	 *  @param tableName		Table name.
	 *  @param tableId			UUID of table.
	 *  @param schemaName		schema that table and constraint lives in.
	 *  @param indexAction		IndexConstantAction for constraint (if necessary)
	 *  RESOLVE - the next parameter should go away once we use UUIDs
	 *			  (Generated constraint names will be based off of uuids)
	 */
	ConstraintConstantAction(
		               String	constraintName,
					   int		constraintType,
		               String	tableName,
					   UUID		tableId,
					   String	schemaName,
					   IndexConstantAction indexAction)
	{
		super(tableId);
		this.constraintName = constraintName;
		this.constraintType = constraintType;
		this.tableName = tableName;
		this.indexAction = indexAction;
		this.schemaName = schemaName;

		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(schemaName != null, "Constraint schema name is null");
		}
	}

	// Class implementation

	/**
	 * Get the constraint type.
	 *
	 * @return The constraint type
	 */
	public	int getConstraintType()
	{
		return constraintType;
	}

	/**
	  *	Get the constraint name
	  *
	  *	@return	the constraint name
	  */
    public	String	getConstraintName() { return constraintName; }

	/**
	  *	Get the associated index constant action.
	  *
	  *	@return	the constant action for the backing index
	  */
    public	IndexConstantAction	getIndexAction() { return indexAction; }

	/**
	 * Make sure that the foreign key constraint is valid
	 * with the existing data in the target table.  Open
	 * the table, if there aren't any rows, ok.  If there
	 * are rows, open a scan on the referenced key with
	 * table locking at level 2.  Pass in the scans to
	 * the BulkRIChecker.  If any rows fail, barf.
	 *
	 * @param	tc		transaction controller
	 * @param	dd		data dictionary
	 * @param	fk		foreign key constraint
	 * @param	refcd	referenced key
	 * @param 	indexTemplateRow	index template row
	 *
	 * @exception StandardException on error
	 */
	static void validateFKConstraint
	(
		TransactionController				tc,
		DataDictionary						dd,
		ForeignKeyConstraintDescriptor		fk,
		ReferencedKeyConstraintDescriptor	refcd,
		ExecRow 							indexTemplateRow ,
		int [] partitionColIndexInRefKeyForSelectiveCheck,
		PartitionedRegion refTablePR
	)
		throws StandardException
	{

		GroupFetchScanController refScan = null;

		GroupFetchScanController fkScan = 
            tc.openGroupFetchScan(
                fk.getIndexConglomerateDescriptor(dd).getConglomerateNumber(),
                false,                       			// hold 
                0, 										// read only
                tc.MODE_TABLE,							// already locked
                tc.ISOLATION_READ_COMMITTED,			// whatever
                (FormatableBitSet)null, 							// retrieve all fields
                (DataValueDescriptor[])null,    	    // startKeyValue
                ScanController.GE,            			// startSearchOp
                null,                         			// qualifier
                (DataValueDescriptor[])null,  			// stopKeyValue
                ScanController.GT             			// stopSearchOp 
                );

		try
		{
			/*
			** If we have no rows, then we are ok.  This will 
			** catch the CREATE TABLE T (x int references P) case
			** (as well as an ALTER TABLE ADD CONSTRAINT where there
			** are no rows in the target table).
			*/	
			if (!fkScan.next())
			{
				fkScan.close();
				return;
			}

			fkScan.reopenScan(
					(DataValueDescriptor[])null,    		// startKeyValue
					ScanController.GE,            			// startSearchOp
					null,                         			// qualifier
					(DataValueDescriptor[])null,  			// stopKeyValue
					ScanController.GT             			// stopSearchOp
					// GemStone changes BEGIN
                                        , null
                                        // GemStone changes BEGIN
					);

			/*
			** Make sure each row in the new fk has a matching
			** referenced key.  No need to get any special locking
			** on the referenced table because it cannot delete
			** any keys we match because it will block on the table
			** lock on the fk table (we have an ex tab lock on
			** the target table of this ALTER TABLE command).
			** Note that we are doing row locking on the referenced
			** table.  We could speed things up and get table locking
			** because we are likely to be hitting a lot of rows
			** in the referenced table, but we are going to err
			** on the side of concurrency here.
			*/
			refScan = 
                tc.openGroupFetchScan(
					refcd.getIndexConglomerateDescriptor(dd).getConglomerateNumber(),
                        false,                       	// hold 
// GemStone changes BEGIN
                        GfxdConstants.SCAN_OPENMODE_FOR_REFERENCED_PK, // for FK reference
                        /* (original code)
                        0, 								// read only
                        */
// GemStone changes END
                        tc.MODE_RECORD,
                        tc.ISOLATION_READ_COMMITTED,	// read committed is good enough
                        (FormatableBitSet)null, 					// retrieve all fields
                        (DataValueDescriptor[])null,    // startKeyValue
                        ScanController.GE,            	// startSearchOp
                        null,                         	// qualifier
                        (DataValueDescriptor[])null,  	// stopKeyValue
                        ScanController.GT             	// stopSearchOp 
                        );

// GemStone changes BEGIN
			// store first failure
			final ValueRow firstKeyToFail = new ValueRow(
			    indexTemplateRow.nColumns());
			RIBulkChecker riChecker = new RIBulkChecker(refScan,
			    fkScan,
			    indexTemplateRow,
			    true, // fail on 1st failure
			    (ConglomerateController)null,
			    firstKeyToFail, partitionColIndexInRefKeyForSelectiveCheck, refTablePR);
			/* (original code)
			RIBulkChecker riChecker = new RIBulkChecker(refScan, 
										fkScan, 
										indexTemplateRow, 	
										true, 				// fail on 1st failure
										(ConglomerateController)null,
										(ExecRow)null);
			*/
			
			if(partitionColIndexInRefKeyForSelectiveCheck != null && !tc.skipLocks()) {
			      //Take region lock so that no rebalance occurs during constraint check
			      RecoveryLock recoveryLock = ColocationHelper.getLeaderRegion(refTablePR)
			          .getRecoveryLock();
			      recoveryLock.lock();
			      //check if all the hosted buckets have primary
			      int bucketIDWithNoPrimary = checkIfAllHostedBucketsHavePrimary(refTablePR);
			      if(bucketIDWithNoPrimary == -1 ) {
			          ((GemFireTransaction)tc).setRegionRecoveryLock(recoveryLock);
			      }else {
			        recoveryLock.unlock();
			        throw StandardException.newException(SQLState.GFXD_PRIMARY_NOT_PRESENT_FOR_BUCKET,
			            bucketIDWithNoPrimary, Misc.getFullTableNameFromRegionPath(refTablePR.getFullPath()));
			      }
			      
			 }
// GemStone changes END

			int numFailures = riChecker.doCheck();
			if (numFailures > 0)
			{
// GemStone changes BEGIN
			  StandardException se = StandardException.newException(
			      SQLState.LANG_ADD_FK_CONSTRAINT_VIOLATION,
			      fk.getConstraintName(),
			      fk.getTableDescriptor().getName(),
			      ("key1=" + firstKeyToFail.toString()));
			  /* (original code)
				StandardException se = StandardException.newException(SQLState.LANG_ADD_FK_CONSTRAINT_VIOLATION, 
									fk.getConstraintName(), 
									fk.getTableDescriptor().getName());
			  */
// GemStone changes END
				throw se;
			}
		}
		finally
		{
			if (fkScan != null)
			{
				fkScan.close();
				fkScan = null;
			}
			if (refScan != null)
			{
				refScan.close();
				refScan = null;
			}
		}
	}
	
	/**
	 * 
	 * @param refTablePR
	 * @return bucketID of the bucket which has no primary in the system or -1 if all are having primary
	 */
	private static int checkIfAllHostedBucketsHavePrimary(PartitionedRegion refTablePR) {
	  RegionAdvisor ra = refTablePR.getRegionAdvisor();
	  
          Set<Integer> allHostedBuckets = ra.getBucketSet();
          for( int bucketID:allHostedBuckets) {
            BucketAdvisor ba = ra.getBucketAdvisor(bucketID);
            if(!ba.hasPrimary()) {
              return bucketID;
            }
          }
          return -1;
	}

	/**
	 * Evaluate a check constraint or not null column constraint.  
	 * Generate a query of the
	 * form SELECT COUNT(*) FROM t where NOT(<check constraint>)
	 * and run it by compiling and executing it.   Will
	 * work ok if the table is empty and query returns null.
	 *
	 * @param constraintName	constraint name
	 * @param constraintText	constraint text
	 * @param td				referenced table
	 * @param lcc				the language connection context
	 * @param isCheckConstraint	the constraint is a check constraint
     *
	 * @return true if null constraint passes, false otherwise
	 *
	 * @exception StandardException if check constraint fails
	 */
	 static boolean validateConstraint
	(
		String							constraintName,
		String							constraintText,
		TableDescriptor					td,
		LanguageConnectionContext		lcc,
		boolean							isCheckConstraint
	)
		throws StandardException
	{
		StringBuilder checkStmt = new StringBuilder();
		/* should not use select sum(not(<check-predicate>) ? 1: 0) because
		 * that would generate much more complicated code and may exceed Java
		 * limits if we have a large number of check constraints, beetle 4347
		 */
		checkStmt.append("SELECT COUNT(*) FROM ");
		checkStmt.append(td.getQualifiedName());
		checkStmt.append(" WHERE NOT(");
		checkStmt.append(constraintText);
		checkStmt.append(")");
	
		ResultSet rs = null;
		try
		{
// GemStone changes BEGIN
		  short execFlags = 0x00;
		  execFlags = GemFireXDUtils.set(execFlags,GenericStatement.CREATE_QUERY_INFO);
			PreparedStatement ps = lcc.prepareInternalStatement(checkStmt.toString(),
			    execFlags);
// GemStone changes END

            // This is a substatement; for now, we do not set any timeout
            // for it. We might change this behaviour later, by linking
            // timeout to its parent statement's timeout settings.
			rs = ps.execute(lcc, false, 0L);
			ExecRow row = rs.getNextRow();
			if (SanityManager.DEBUG)
			{
				if (row == null)
				{
					SanityManager.THROWASSERT("did not get any rows back from query: "+checkStmt.toString());
				}
			}

			//DataValueDescriptor[] rowArray = row.getRowArray();
			Number value = ((Number)((NumberDataValue)row.getRowArray()[0]).getObject());
			/*
			** Value may be null if there are no rows in the
			** table.
			*/
			if ((value != null) && (value.longValue() != 0))
			{	
				//check constraint violated
				if (isCheckConstraint)
					throw StandardException.newException(SQLState.LANG_ADD_CHECK_CONSTRAINT_FAILED, 
						constraintName, td.getQualifiedName(), value.toString());
				/*
				 * for not null constraint violations exception will be thrown in caller
				 * check constraint will not get here since exception is thrown
				 * above
				 */
				return false;
			}
		}
		finally
		{
			if (rs != null)
			{
				rs.close(false);
			}
		}
		return true;
	}
// GemStone changes BEGIN
	 String getFullConstraintName() {
	   return this.schemaName + '.' + this.constraintName;
	 }
// GemStone changes END
}
