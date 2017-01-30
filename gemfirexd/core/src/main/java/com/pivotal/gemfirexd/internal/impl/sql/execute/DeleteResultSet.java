/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.DeleteResultSet

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

import java.util.Properties;

import com.pivotal.gemfirexd.internal.iapi.db.TriggerExecutionContext;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.IndexRowGenerator;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.CursorResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.RowChanger;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.GenericActivationHolder;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
// GemStone changes BEGIN
import java.util.ArrayList;

import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.internal.cache.TXState;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.distributed.
    ReferencedKeyCheckerMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.
    ReferencedKeyCheckerMessage.ReferencedKeyReplyProcessor;
import com.pivotal.gemfirexd.internal.engine.distributed.
    message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapResourceHolder;
import com.pivotal.gemfirexd.internal.iapi.util.ReuseFactory;
// GemStone changes END

/**
 * Delete the rows from the specified
 * base table. This will cause constraints to be checked
 * and triggers to be executed based on the c's and t's
 * compiled into the insert plan.
 */
public class DeleteResultSet extends DMLWriteResultSet 
{
	private TransactionController   	tc;
	DeleteConstantAction		constants;
    protected ResultDescription 				resultDescription;
	protected  NoPutResultSet			source;
	NoPutResultSet			savedSource;
	int 							numIndexes;
	protected RowChanger 			rc;
	private ExecRow					row;

	protected ConglomerateController	deferredBaseCC;

	protected TemporaryRowHolderImpl	rowHolder;

	private int						numOpens; // number of opens w/o a close
	private boolean 				firstExecute;

	// cached across opens()s
	private FormatableBitSet 				baseRowReadList; 
	private int						rlColumnNumber;
	protected FKInfo[]				fkInfoArray;
	private TriggerInfo 			triggerInfo;
// GemStone changes BEGIN
	// rows that have been deleted (or will be deleted for deferred case)
	// that need to be checked for referenced key integrity in child tables
	private final ArrayList<RowLocation> referencedKeyCheckRows;
	private final GemFireContainer container;
// GemStone changes END
	private	RISetChecker			fkChecker;
	private TriggerEventActivator	triggerActivator;
	private boolean					noTriggersOrFks;

	ExecRow		deferredSparseRow; 
	ExecRow		deferredBaseRow;
	int lockMode; 
	protected  boolean cascadeDelete;
	ExecRow		deferredRLRow = null;
	int	numberOfBaseColumns = 0;
	protected final GemFireXDQueryObserver observer =
	            GemFireXDQueryObserverHolder.getInstance();

    /*
     * class interface
     *
     */
    DeleteResultSet
	(
		NoPutResultSet		source,
		Activation			activation
	)
		throws StandardException
    {
		this(source, activation.getConstantAction(), activation);
	}
    /**
     * REMIND: At present this takes just the conglomerate id
     * of the table. We can expect this to expand to include
     * passing information about triggers, constraints, and
     * any additional conglomerates on the underlying table
     * for access methods.
     *
	 * @exception StandardException		Thrown on error
     */
    DeleteResultSet
	(
		NoPutResultSet		source,
		ConstantAction		passedInConstantAction,
		Activation			activation
	)
		throws StandardException
    {
		super(activation, passedInConstantAction);
		this.source = source;

		tc = activation.getTransactionController();
		constants = (DeleteConstantAction) constantAction;
		fkInfoArray = constants.getFKInfo();
		triggerInfo = constants.getTriggerInfo();
		noTriggersOrFks = ((fkInfoArray == null) && (triggerInfo == null));
		baseRowReadList = constants.getBaseRowReadList();
		if(source != null)
			resultDescription = activation.getResultDescription();
		else
			resultDescription = constants.resultDescription;
// GemStone changes BEGIN
		this.container = ((MemConglomerate)this.constants.heapSCOCI
		    .getConglom()).getGemFireContainer();
                if ((!this.container.isTemporaryContainer())
                    && this.container.getExtraTableInfo().getReferencedKeyColumns() != null) {
		  this.referencedKeyCheckRows = new ArrayList<RowLocation>();
		}
		else {
		  this.referencedKeyCheckRows = null;
		}
   
// GemStone changes END
	}

	/**
		@exception StandardException Standard Derby error policy
	*/
	public void open() throws StandardException
	{
    try {
		setup();
	       
		boolean rowsFound = collectAffectedRows(); //this call also deletes rows , if not deferred
		if (! rowsFound)
		{
			activation.addWarning(StandardException
			    .newNoRowFoundWarning());
		}

// GemStone changes BEGIN
		checkCancellationFlag();
		// do the reference key checks
		if (this.referencedKeyCheckRows != null) {
		  final int numRefKeys = this.referencedKeyCheckRows.size();
		  // we need to do reference key processor wait below even for
		  // zero ref keys otherwise query collector will assume the
		  // results to be processor IDs (#44164)
		  if (this.observer != null) {
        this.observer.onDeleteResultSetOpenBeforeRefChecks(this);
      }
		  if (numRefKeys > 0) {
		    ReferencedKeyCheckerMessage.referencedKeyCheck(
		        this.container, this.lcc, null,
		        this.referencedKeyCheckRows, false,false, null,null,null, null, null);
		  }
	          if (this.observer != null) {
                      this.observer.onDeleteResultSetOpenAfterRefChecks(this);
                  }
		  checkCancellationFlag();
		  //fireBeforeTriggers();
		  final GemFireTransaction tran = (GemFireTransaction)this.tc;
		  boolean doDeletes = true;
		  // for non-TX case wait for replies from other nodes for
		  // constraint check result
		  if (tran.getActiveTXState() == null) {
		    final RegionExecutorMessage<?> msg =
		      (RegionExecutorMessage<?>)activation.getFunctionContext();
		    if (msg != null && msg.getNumRecipients() > 1) {
		      // Misc will return a DS will non-null DM
		      final DM dm = Misc.getDistributedSystem().getDM();
		      final ReferencedKeyReplyProcessor processor =
		        new ReferencedKeyReplyProcessor(dm, msg.getSender());
		      msg.getResultSender().sendResult(Integer.valueOf(
		          processor.getProcessorId()));
		      doDeletes = processor.waitForResult();
		    }
		  }
		  if (doDeletes) {
		    // proceed with deletes
		    if (numRefKeys > 0) {
		      for (RowLocation rl : this.referencedKeyCheckRows) {
		        if (this.rc.deleteRow(this.deferredBaseRow, rl)) {
		          this.rowCount++;
		        }
		        this.source.markRowAsDeleted();
		      }
		    }
		    this.rc.finish();
		    //fireAfterTriggers();
		  }
		}
		else
// GemStone changes END
		/*
		** If the delete is deferred, scan the temporary conglomerate to
		** get the RowLocations of the rows to be deleted.  Re-fetch the
		** rows and delete them using the RowChanger.
		*/
		if (constants.deferred)
		{
			runFkChecker(true); //check for only RESTRICT referential action rule violations
// GemStone changes BEGIN
			//fireBeforeTriggers();
// GemStone changes END
			deleteDeferredRows();
			runFkChecker(false); //check for all constraint violations
			// apply 
			rc.finish();
// GemStone changes BEGIN
			//fireAfterTriggers();
// GemStone changes END
		}
// GemStone changes BEGIN
		if(!lcc.isSkipListeners()) {
		  if(this.container.getRegion().isSerialWanEnabled()){
		    this.distributeBulkOpToDBSynchronizer();
		  }
		}
// GemStone changes END
	
		/* Cache query plan text for source, before it gets blown away */
		if (lcc.getRunTimeStatisticsMode())
		{
			/* savedSource nulled after run time statistics generation */
			savedSource = source;
		}
	}finally {

		cleanUp(false);
		endTime = statisticsTimingOn ? XPLAINUtil.nanoTime() : 0;
	}

    }
	

	//this routine open the source and find the dependent rows 
// GemStone changes BEGIN
	protected void  setup() throws StandardException
	/* void  setup() throws StandardException */
// GemStone changes END
	{
		super.setup();

		// Remember if this is the 1st execution
		firstExecute = (rc == null);

		try {

			//open the source for the parent tables
			if (numOpens++ == 0)
			{
				source.openCore();
			}
			else
			{
        		source.reopenCore();
			}
		} catch (StandardException se) {
			activation.checkStatementValidity();
			throw se;

		}

		activation.checkStatementValidity();

		/* Get or re-use the row changer.
		 * NOTE: We need to set ourself as the top result set
		 * if this is not the 1st execution.  (Done in constructor
		 * for 1st execution.)
		 */
		if (firstExecute)
		{
		       // IndexRowGenerator[] irg=;
			rc = lcc.getLanguageConnectionFactory().getExecutionFactory().
					     getRowChanger( 
								constants.conglomId,
								constants.heapSCOCI, 
								heapDCOCI,
// GemStone change BEGIN
/* disable index maintenance for delete */								
								ReuseFactory.getZeroLenIRGArray() /*constants.irgs*/,
								ReuseFactory.getZeroLenLongArray() /*constants.indexCIDS*/,
// GemStone change END
								constants.indexSCOCIs,
							    indexDCOCIs,
								constants.numColumns,
								tc,
								(int[])null,
								baseRowReadList,
								constants.getBaseRowReadMap(),
								constants.getStreamStorableHeapColIds(),
								activation);
		}
		else
		{
			lcc.getStatementContext().setTopResultSet(this, subqueryTrackingArray);
		}
		/* decode the lock mode for the execution isolation level */
		lockMode = decodeLockMode(constants.lockMode);

		/* Open the RowChanger before the source ResultSet so that
		 * the store will see the RowChanger's lock as a covering lock
		 * if it is a table lock.
		 */
		rc.open(lockMode); 

		/* The source does not know whether or not we are doing a
		 * deferred mode delete.  If we are, then we must clear the
		 * index scan info from the activation so that the row changer
		 * does not re-use that information (which won't be valid for
		 * a deferred mode delete).
		 */
		if (constants.deferred || cascadeDelete)
		{
			activation.clearIndexScanInfo();
		}
		 if (this.observer != null) {
       this.observer.onDeleteResultSetOpen(this);
     }
        rowCount = 0;
        if(!cascadeDelete)
			row = getNextRowCore(source);

		/*
		** We need the number of columns even if there are
		** no rows. Note that source.ressultDescription() may 
		** be null on a rep target doing a refresh.
		*/
		if (resultDescription == null)
		{
			if (SanityManager.DEBUG)
			{
				/*
				** We NEED a result description when we are going to
				** to have to kick off a trigger.  In a replicated environment
				** we don't get a result description when we are replaying
				** source xacts on the target, but we shouldn't be firing
				** a trigger in that case anyway.
				*/
				SanityManager.ASSERT(triggerInfo == null, "result description is needed to supply to trigger result sets");
			}
			numberOfBaseColumns = (row == null) ? 0 : row.nColumns();
		}
		else
		{
			numberOfBaseColumns = resultDescription.getColumnCount();
		}

		numIndexes = constants.irgs.length;

// GemStone changes BEGIN
		if (this.referencedKeyCheckRows != null) {
		}
		else
// GemStone changes END
		if (constants.deferred || cascadeDelete)
		{
			Properties properties = new Properties();

			// Get the properties on the old heap
			// Gemstone changes BEGIN
			//rc.getHeapConglomerateController().getInternalTablePropertySet(properties);
			// Gemstone changes END

			/*
			** If deferred and fk or trigger, we are going to grab
			** the entire row.  
			**
			** If we are deferred w/o a fk, then we only
			** save the row location.
			*/
			deferredRLRow = RowUtil.getEmptyValueRow(1, lcc);
			rlColumnNumber = noTriggersOrFks ? 1: numberOfBaseColumns;
			if(cascadeDelete)
			{
				rowHolder = new TemporaryRowHolderImpl(activation, properties, 
						 false);


			}else
			{

				rowHolder = new TemporaryRowHolderImpl(activation, properties);

			}

			rc.setRowHolder(rowHolder);
		}

// GemStone changes BEGIN
		// GFXD has to send bulk messages for referenced key check
		// rather than row by row check so never use fkChecker
		/* (original code)
		if (fkInfoArray != null)
		{
			if (fkChecker == null)
			{
				fkChecker = new RISetChecker(tc, fkInfoArray);
			}
			else
			{
				fkChecker.reopen();
			}
		}
		*/
// GemStone changes END
	}


	boolean  collectAffectedRows() throws StandardException
	{	

		DataValueDescriptor		rlColumn;
// GemStone changes BEGIN
		// Neeraj: In transactions we want that even if oneRowSource
		// is there it should go and try fetch next so that the
		// scan controller at least gets a chance to
		// release the lock if it did not eventually qualify .. #43222
		// [sumedh] No longer using the above approach.
		/*
		boolean ignoreNoNextEvenIfSingleRowSource = ((GemFireTransaction)
		    this.activation.getTransactionController()).getActiveTXState() == null;
		*/
		final TXState localTXState = this.source.initLocalTXState();
		RowLocation	baseRowLocation = null;
// GemStone changes END
		boolean rowsFound = false;

		if(cascadeDelete)
			row = getNextRowCore(source);

		while ( row != null )
		{
			/* By convention, the last column for a delete contains a SQLRef
			 * containing the RowLocation of the row to be deleted.  If we're
			 * doing a deferred delete, store the RowLocations in the
			 * temporary conglomerate.  If we're not doing a deferred delete,
			 * just delete the rows immediately.
			 */

			rowsFound = true;

// GemStone changes BEGIN
			/* (original code)
			rlColumn = row.getColumn( row.nColumns() );
			*/
			rlColumn = row.getLastColumn();

			if (this.referencedKeyCheckRows != null) {
			  baseRowLocation = (RowLocation)rlColumn.getObject();
			  if (SanityManager.DEBUG) {
			    SanityManager.ASSERT(baseRowLocation != null,
			        "baseRowLocation is null");
			  }
			  if (localTXState != null &&
			      baseRowLocation != null) {
			    this.source.upgradeReadLockToWrite(
			        baseRowLocation, this.container);
			  }
			  // For transactions we can delete directly
			  // here since constraint violation will lead
			  // to proper rollback, but for non-TX case
			  // we just collect rows here and check
			  // constraint + wait for result of other nodes
			  // and then proceed to delete. Now we are
			  // deferring delete even for TX case since it
			  // is hard to get the original value before
			  // delete for referenced key checking -- no wait
			  // for result from other nodes for TX though.
			  this.referencedKeyCheckRows.add(
			      baseRowLocation);
			  if (this.deferredBaseRow == null) {
			    this.deferredBaseRow = RowUtil.getEmptyValueRow(
			        this.numberOfBaseColumns - 1, this.lcc);
			    RowUtil.copyCloneColumns(this.deferredBaseRow,
			        this.row, this.numberOfBaseColumns - 1);
			    deferredSparseRow = makeDeferredSparseRow(
			        deferredBaseRow, baseRowReadList, lcc);
			  }
			}
			else
// GemStone changes END
			if (constants.deferred || cascadeDelete)
			{

				/*
				** If we are deferred because of a trigger or foreign
				** key, we need to save off the entire row.  Otherwise,
				** we just save the RID.
				*/
				if (noTriggersOrFks)
				{
					deferredRLRow.setColumn(1, rlColumn);
					rowHolder.insert(deferredRLRow);
				}
				else
				{
					rowHolder.insert(row);
				}
				
				/*
				** If we haven't already, lets get a template to
				** use as a template for our rescan of the base table.
				** Do this now while we have a real row to use
				** as a copy.
				**
				** There is one less column in the base row than
				** there is in source row, because the base row
				** doesn't contain the row location.
				*/
				if (deferredBaseRow == null)
				{
					deferredBaseRow = RowUtil.getEmptyValueRow(numberOfBaseColumns - 1, lcc);
			
					RowUtil.copyCloneColumns(deferredBaseRow, row, 
											numberOfBaseColumns - 1);
					deferredSparseRow = makeDeferredSparseRow(deferredBaseRow,
																baseRowReadList,
																lcc);
				}
// GemStone changes BEGIN
				baseRowLocation = 
                                  (RowLocation) (rlColumn).getObject();
				if (localTXState != null && baseRowLocation != null) {
				  this.source.upgradeReadLockToWrite(
				      baseRowLocation, this.container);
				}
// GemStone changes END
			}
			else
			{
// GemStone changes BEGIN
			  /* (original code)
				if (fkChecker != null)
				{
					fkChecker.doPKCheck(row, false);
				}
			  */
// GemStone changes END
        
				baseRowLocation = 
					(RowLocation) (rlColumn).getObject();

				if (SanityManager.DEBUG)
				{
					SanityManager.ASSERT(baseRowLocation != null,
							"baseRowLocation is null");
				}
// GemStone changes BEGIN
				if (localTXState != null &&
				    baseRowLocation != null) {
				  this.source.upgradeReadLockToWrite(
				      baseRowLocation, this.container);
				}
// GemStone changes END

				if (rc.deleteRow(row,baseRowLocation)) {
                                  this.rowCount++;
                                }
				source.markRowAsDeleted();
			}
			source.releasePreviousByteSource();

			// No need to do a next on a single row source
            if (constants.singleRowSource)
			{
				row = null;
			}
			else
			{
			  //this.source.releasePreviousByteSource();
				row = getNextRowCore(source);
			}
		}

		return rowsFound;
	}


	// execute the before triggers set on the table
    void fireBeforeTriggers() throws StandardException
	{
// GemStone changes BEGIN
      if (activation.getFunctionContext() != null) {
        return;
      }
      if (GemFireXDUtils.TraceActivation) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
            "DeleteResultSet::fireBeforeTriggers activation is: "
                + activation + " function context is null", new Throwable());
      }
// GemStone changes END

		if (triggerInfo != null)
		{
			if (triggerActivator == null)
			{
				triggerActivator = new TriggerEventActivator(lcc, 
															 tc, 
															 constants.targetUUID,
															 triggerInfo,
															 TriggerExecutionContext.DELETE_EVENT,
															 activation, null
															 );
			}
			else
			{
				triggerActivator.reopen();
			}

			// fire BEFORE trigger
			triggerActivator.notifyEvent(TriggerEvents.BEFORE_DELETE, 
										 rowHolder.getResultSet(), 
										 (CursorResultSet)null);
			triggerActivator.cleanup(false);

		}

	}

	//execute the after triggers set on the table.
	void fireAfterTriggers() throws StandardException
	{
// GemStone changes BEGIN
          if (activation.getFunctionContext() != null) {
            return;
          }
          if (GemFireXDUtils.TraceActivation) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                "DeleteResultSet::fireAfterTriggers activation is: "
                    + activation + " function context is null", new Throwable());
          }
// GemStone changes END

		// fire AFTER trigger
		if (triggerActivator != null)
		{
			triggerActivator.reopen();
			triggerActivator.notifyEvent(TriggerEvents.AFTER_DELETE, 
										 rowHolder.getResultSet(),
										 (CursorResultSet)null);
			triggerActivator.cleanup(false);
		}
		
	}


	//delete the rows that in case deferred case and
	//during cascade delete (All deletes are deferred during cascade action)
	void deleteDeferredRows() throws StandardException
	{
		
	  if (this.observer != null) {
      this.observer.beforeDeferredDelete();
    }
		DataValueDescriptor		rlColumn;
 		RowLocation	baseRowLocation;
		ExecRow		deferredRLRow = null;

		deferredBaseCC = tc.openCompiledConglomerate(false,
													 tc.OPENMODE_FORUPDATE|tc.OPENMODE_SECONDARY_LOCKED,
													 lockMode,
													 TransactionController.ISOLATION_SERIALIZABLE,
													 constants.heapSCOCI,
													 heapDCOCI);
			
		CursorResultSet rs = rowHolder.getResultSet();
		try
		{
			/*
			** We need to do a fetch doing a partial row
			** read.  We need to shift our 1-based bit
			** set to a zero based bit set like the store
			** expects.
			*/
			FormatableBitSet readBitSet = RowUtil.shift(baseRowReadList, 1);

			rs.open();
			while ((deferredRLRow = rs.getNextRow()) != null)
			{
				rlColumn = deferredRLRow.getColumn(rlColumnNumber);
// GemStone changes BEGIN
				baseRowLocation = 
					(RowLocation) (rlColumn).getObject();
				boolean row_exists   = baseRowLocation != null;
				// for delete restrict build up the rows to be
				// sent to ReferencedKeyCheckerMessage
				/* Get the base row at the given RowLocation */
				/* (original code)
				baseRowLocation = 
					deferredBaseCC.fetch(
										 baseRowLocation, deferredSparseRow.getRowArray(), 
							 readBitSet);				
				boolean row_exists   = baseRowLocation != null;          
				if(row_exists) {
                                  rlColumn.setValue(baseRowLocation);
                                }
                                */
// GemStone changes END
				// In case of cascade delete , things like before triggers can delete 
				// the rows before the dependent result get a chance to delete
				if(cascadeDelete && !row_exists)
					continue;

				if (SanityManager.DEBUG)
				{
					if (!row_exists)
					{
                        	SanityManager.THROWASSERT("could not find row "+baseRowLocation);
					}
				}
	
				if (rc.deleteRow(deferredBaseRow, baseRowLocation)) {
				  this.rowCount++;
				}
				source.markRowAsDeleted();
			}
		} finally
		{
				rs.close(false);
		}
	}


	// make sure foreign key constraints are not violated
    void runFkChecker(boolean restrictCheckOnly) throws StandardException
	{

		ExecRow		deferredRLRow = null;
		if (fkChecker != null)
		{
			/*
			** Second scan to make sure all the foreign key
			** constraints are ok.  We have to do this after
			** we have completed the deletes in case of self referencing
			** constraints.
			*/
			CursorResultSet rs = rowHolder.getResultSet();
			try
			{
				rs.open();
				while ((deferredRLRow = rs.getNextRow()) != null)
				{
					fkChecker.doPKCheck(deferredRLRow, restrictCheckOnly);
				}
			} finally
			{
				rs.close(false);
			}
		}
	}

	/**
	  *	create a source for the dependent table
	  *
	  * <P>Delete Cascade ResultSet class will override this method.
	  *
	  * @exception StandardException		Thrown on error
	  */
	NoPutResultSet createDependentSource(RowChanger rc)
		throws StandardException
	{
		return null;
	}


	/**
	 * @see ResultSet#cleanUp
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void	cleanUp(boolean cleanupOnError) throws StandardException
	{ 
		numOpens = 0;
		try {

		/* Close down the source ResultSet tree */
		if (source != null)
		{
			source.close(cleanupOnError);
			// source is reused across executions
		}
		if (rc != null)
		{
			rc.close();
			// rc is reused across executions
		}

		if (rowHolder != null)
		{
			rowHolder.close();
			// rowHolder is reused across executions
		}

		if (fkChecker != null)
		{
			fkChecker.close();
			// fkcheckers is reused across executions
		}

		if (deferredBaseCC != null)
			deferredBaseCC.close();
		deferredBaseCC = null;

		if (rc != null) {
			rc.close();
		}
		}finally {
		super.close(cleanupOnError);
		}
	}

	public void finish() throws StandardException {
		if (source != null)
			source.finish();
		super.finish();
	}

  // GemStone changes BEGIN
  @Override
  public void accept(ResultSetStatisticsVisitor visitor) {
    visitor.setNumberOfChildren(1);
    visitor.visit(this);
    source.accept(visitor);
  }
  // GemStone changes END
  
}












