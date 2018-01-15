/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.UpdateResultSet

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

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gnu.trove.TIntHashSet;
import com.gemstone.gnu.trove.TIntIntHashMap;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.distributed.ReferencedKeyCheckerMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.ReferencedKeyCheckerMessage.ReferencedKeyReplyProcessor;
import com.pivotal.gemfirexd.internal.engine.distributed.message.RegionExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapResourceHolder;
import com.pivotal.gemfirexd.internal.iapi.db.TriggerExecutionContext;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.io.StreamStorable;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.CursorResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.RowChanger;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.BooleanDataValue;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.util.ReuseFactory;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;
import io.snappydata.collection.LongObjectHashMap;


/**
 * Update the rows from the specified
 * base table. This will cause constraints to be checked
 * and triggers to be executed based on the c's and t's
 * compiled into the update plan.
 *
 */
public final class UpdateResultSet extends DMLWriteResultSet implements OffHeapResourceHolder
{
	private TransactionController 	tc;
	private ExecRow					newBaseRow;
	private ExecRow 					row;
	private ExecRow 					deferredSparseRow;
	UpdateConstantAction		constants;
	
	private NoPutResultSet			source;
	NoPutResultSet			savedSource;
	private RowChanger				rowChanger;

	protected ConglomerateController	deferredBaseCC;

	protected long[]				deferredUniqueCIDs;
	protected boolean[]				deferredUniqueCreated;
	protected ConglomerateController	deferredUniqueCC[];
	protected ScanController[]		deferredUniqueScans;

	private	TemporaryRowHolderImpl	deletedRowHolder;
	private	TemporaryRowHolderImpl	insertedRowHolder;

	// cached 
	private RISetChecker			riChecker;
	private	TriggerInfo				triggerInfo;
	private TriggerEventActivator	triggerActivator;
	private boolean					updatingReferencedKey;
	private boolean					updatingForeignKey;
	private	int						numOpens;
	private long					heapConglom; 
	private FKInfo[]				fkInfoArray;
	private FormatableBitSet 				baseRowReadList;
	private GeneratedMethod			checkGM;
	private int						resultWidth;
	private int						numberOfBaseColumns;
	private ExecRow					deferredTempRow;
	private ExecRow					deferredBaseRow;
	private ExecRow					oldDeletedRow;

	int lockMode;
	boolean deferred;
	boolean beforeUpdateCopyRequired = false;
	
	private final GemFireContainer container;
	private final ArrayList<DataValueDescriptor[]> referencedKeyCheckRows;
	private  OHAddressCache deferredRowsOHCache = null;
	/**
	 * The refColsImpacted refers to the column 
	 * numbers of  the referenced  with atleast one column of the constraint is modified.
	 * It may include unmodified columns of the constraint .
	 *
	 */
	private final int[] refColsImpacted;
	private final FormatableBitSet refColsUpdtdBits;
	private final TIntIntHashMap refCol2IndexMap;
	
	/** The refColsModified refers to all the constraint columns which are also part
	 * of the modification list.In effect it is the intersection set of the modified columns
	 * and the refColsImpacted.
	 */
	private final LongObjectHashMap<TIntHashSet> refColUpdtd2DependentCols;
	protected final GemFireXDQueryObserver observer =
      GemFireXDQueryObserverHolder.getInstance();

    /*
     * class interface
     *
     */
    /**
	 * @param source update rows come from source
	 * @param checkGM	Generated method for enforcing check constraints
	 * @exception StandardException thrown on error
     */
    UpdateResultSet(NoPutResultSet source,
						   GeneratedMethod checkGM,
						   Activation activation)
      throws StandardException
    {
		this(source, checkGM , activation, activation.getConstantAction(),null);
		
	}

    /*
     * class interface
     *
     */
    /**
	 * @param source update rows come from source
	 * @param checkGM	Generated method for enforcing check constraints
	 * @param activation Activation
	 * @param constantActionItem  id of the update constant action saved objec
	 * @param rsdItem  id of the Result Description saved object
	 * @exception StandardException thrown on error
     */
    UpdateResultSet(NoPutResultSet source,
						   GeneratedMethod checkGM,
						   Activation activation, 
						   int constantActionItem,
						   int rsdItem)
      throws StandardException
    {
		this(source, checkGM , activation,
			  ((ConstantAction)activation.getSavedObject(constantActionItem)),
			 (ResultDescription) activation.getSavedObject(rsdItem));
	
		// In case of referential action update, we do a deferred updates
		deferred = true;
		if(this.container.isOffHeap() && this.deferredRowsOHCache == null) {
		  this.deferredRowsOHCache = new TableScanResultSet.SingleOHAddressCache();
		}
		
	}


    /*
     * class interface
     *
     */
    /**
	 * @param source update rows come from source
	 * @param checkGM	Generated method for enforcing check constraints
	 * @exception StandardException thrown on error
     */
    UpdateResultSet(NoPutResultSet source,
						   GeneratedMethod checkGM,
						   Activation activation,
						   ConstantAction passedInConstantAction,
						   ResultDescription passedInRsd)
      throws StandardException
 {
    super(activation, passedInConstantAction);

    // Get the current transaction controller
    tc = activation.getTransactionController();
    this.source = source;
    this.checkGM = checkGM;

    constants = (UpdateConstantAction)constantAction;
    fkInfoArray = constants.getFKInfo();
    triggerInfo = constants.getTriggerInfo();

    heapConglom = constants.conglomId;

    baseRowReadList = constants.getBaseRowReadList();
    ResultDescription resultDescription;
    if (passedInRsd == null)
      resultDescription = activation.getResultDescription();
    else
      resultDescription = passedInRsd;
    /*
    ** We NEED a result description when we are going to
    ** to have to kick off a trigger.  In a replicated environment
    ** we don't get a result description when we are replaying
    ** source xacts on the target, which should never be the
    ** case for an UpdateResultSet.
    */
    if (SanityManager.DEBUG) {
      if (resultDescription == null) {
        SanityManager
            .ASSERT(triggerInfo == null,
                "triggers need a result description to pass to result sets given to users");
      }
    }

    if (fkInfoArray != null) {
      for (int i = 0; i < fkInfoArray.length; i++) {
        if (fkInfoArray[i].type == FKInfo.REFERENCED_KEY) {
          // GemStone changes BEGIN
          // Since the Referenced Key check is being done explcitly by defering
          // the
          // updates the RI checker function can hence ignore the reference key
          // checks

          // updatingReferencedKey = true;

          // GemStone changes END
          if (SanityManager.DEBUG) {
            SanityManager.ASSERT(constants.deferred,
                "updating referenced key but update not deferred, wuzzup?");
          }
        }
        else {
          updatingForeignKey = true;
        }
      }
    }

    /* Get the # of columns in the ResultSet */
    resultWidth = resultDescription.getColumnCount();

    /*
    ** Calculate the # of columns in the base table.  The result set
    ** contains the before columns, the after columns, and the RowLocation,
    ** so the number of base columns is half of the number of result set
    ** columns, after subtracting one for the row location column.
    */
    numberOfBaseColumns = (resultWidth - 1) / 2;

    /* Get the new base row */
    newBaseRow = RowUtil.getEmptyValueRow(numberOfBaseColumns, lcc);

    deferred = constants.deferred;

    // update can be marked for deferred mode because the scan is being done
    // using index. But it is not necesary to keep the before copy
    // of the row in the temporary row holder (deletedRowHolder) unless
    // there are RI constraint or Triggers.(beetle:5301)
    if (triggerInfo != null || fkInfoArray != null) {
      beforeUpdateCopyRequired = true;
    }

    // GemStone changes BEGIN
    this.container = ((MemConglomerate)this.constants.heapSCOCI.getConglom())
        .getGemFireContainer();
    if (!this.container.isTemporaryContainer()) {
      LongObjectHashMap<TIntHashSet> tempRefColUpdtd2DependentCols =
          LongObjectHashMap.withExpectedSize(4);
      
      Map<Integer, Boolean> refColsImpactedMap =
          this.getReferencedUpdateCols(tempRefColUpdtd2DependentCols);
      if (refColsImpactedMap != null) {
        this.refColUpdtd2DependentCols = tempRefColUpdtd2DependentCols;
        
        this.refColsImpacted = new int[refColsImpactedMap.size()];
        this.refColsUpdtdBits = new FormatableBitSet(
            this.refColsImpacted.length);
        this.refCol2IndexMap = new TIntIntHashMap();
        int j = 0;

        for (Map.Entry<Integer, Boolean> entry : refColsImpactedMap.entrySet()) {
          int colNum = entry.getKey().intValue();
          this.refColsImpacted[j] = colNum;
          if (entry.getValue()) {
            this.refColsUpdtdBits.set(j);
          }
          this.refCol2IndexMap.put(colNum, j);
          ++j;
        }
      }
      else {
        this.refColsImpacted = null;
        this.refColsUpdtdBits = null;
        this.refCol2IndexMap = null;
        this.refColUpdtd2DependentCols = null;
      }

    }
    else {
      this.refColsImpacted = null;
      this.refColsUpdtdBits = null;
      this.refColUpdtd2DependentCols = null;
      this.refCol2IndexMap = null;

    }

    if (this.refColsImpacted != null) {
      this.referencedKeyCheckRows = new ArrayList<DataValueDescriptor[]>();
      deferred = true;
    }
    else {
      this.referencedKeyCheckRows = null;
    }
    
    if(deferred && this.container.isOffHeap()) {
      this.deferredRowsOHCache = new TableScanResultSet.SingleOHAddressCache();
    }
    // GemStone changes END
   
  }

  private Map<Integer, Boolean> getReferencedUpdateCols(
      LongObjectHashMap<TIntHashSet> tempRefColUpdtd2DependentCols) {
    int[] refKeyCols = this.container.getExtraTableInfo()
        .getReferencedKeyColumns();
    // check if any of the modified cols is a ref key column of a
    // reference
    // integrity constraint
    // refKeyCols = fkInfo.colArray;
    if (refKeyCols != null) {
      SortedMap<Integer, Boolean> referencedImpactedColsMap = new TreeMap<Integer, Boolean>();
      // SortedSet<Integer> onlyRefUpadtedCols = new TreeSet();
      for (int i = 0; i < constants.changedColumnIds.length; ++i) {
        int modColID = constants.changedColumnIds[i];
        for (int refKeyColID : refKeyCols) {
          if (refKeyColID == modColID) {
            // onlyRefUpadtedCols.add(refKeyColID);
            TIntHashSet dependentCols = new TIntHashSet(refKeyCols.length);
            tempRefColUpdtd2DependentCols.put(refKeyColID, dependentCols);
            referencedImpactedColsMap.put(refKeyColID, Boolean.TRUE);
            addCompanionRefColsToMap(refKeyColID, referencedImpactedColsMap,
                dependentCols);
          }
        }
      }
      return referencedImpactedColsMap;

    }
    else {
      return null;
    }
  }

  private void addCompanionRefColsToMap(int refColID,
      Map<Integer, Boolean> referencedImpactedColsMap, TIntHashSet dependentCols) {
    Set<int[]> refColsSet = this.container.getExtraTableInfo()
        .getReferencedKeyColumns2IndexNumbers().keySet();
    for (int[] cols : refColsSet) {
      inner: for (int col : cols) {
        if (col == refColID) {
          for (int colx : cols) {
            if (colx != refColID) {
              if (!referencedImpactedColsMap.containsKey(colx)) {
                referencedImpactedColsMap.put(colx, Boolean.FALSE);
              }
              dependentCols.add(colx);
            }
          }
          break inner;
        }
      }
    }

  }
	/**
		@exception StandardException Standard Derby error policy
	*/
	public void open() throws StandardException
	{
    if(this.deferredRowsOHCache != null) {
      this.registerWithGemFireTransaction(this);
    }
	  try {
		setup();
		if (this.observer != null) {
      this.observer.onUpdateResultSetOpen(this);
    }
		collectAffectedRows();
//              GemStone changes BEGIN
		if(!lcc.isSkipListeners()) {
		  if(this.container.getRegion().isSerialWanEnabled()){
		    this.distributeBulkOpToDBSynchronizer();
		  }
		}
		checkCancellationFlag();
//              GemStone changes END   
		/*
		** If this is a deferred update, read the new rows and RowLocations
		** from the temporary conglomerate and update the base table using
		** the RowChanger.
		*/
		if (deferred)
		{

			runChecker(true); //check for only RESTRICT referential action rule violations
			//fireBeforeTriggers();
			 boolean doUpdates = true;
			//GemStone changes BEGIN
			if (this.referencedKeyCheckRows != null) {
				  final int numRefKeys = this.referencedKeyCheckRows.size();
				  // we need to do reference key processor wait below even for
				  // zero ref keys otherwise query collector will assume the
				  // results to be processor IDs (#44164)
				  if (numRefKeys > 0) {
				    ReferencedKeyCheckerMessage.referencedKeyCheck(
				        this.container, this.lcc, this.referencedKeyCheckRows,
				        null, false, true, this.refColsImpacted, 
				        this.refColsUpdtdBits, this.refColUpdtd2DependentCols,refCol2IndexMap, constants.getBaseRowReadMap());
				  }
				  checkCancellationFlag();
				  //fireBeforeTriggers();
				  final GemFireTransaction tran = (GemFireTransaction)this.tc;
				 
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
				      doUpdates = processor.waitForResult();
				    }
				  }
				  
			}
			
			if(doUpdates) {
			//GemStone changes END
		
			updateDeferredRows();
			/* Apply deferred inserts to unique indexes */
			rowChanger.finish();
			runChecker(false); //check for all  violations
			//fireAfterTriggers();
			}
		}
		else{
		/* Apply deferred inserts to unique indexes */
		rowChanger.finish();
		}
		if (this.observer != null) {
      this.observer.onUpdateResultSetDoneUpdate(this);
    }
	
	  }finally {
		  cleanUp(false);
	  }
    
	}


	/**
		@exception StandardException Standard Derby error policy
	*/
// GemStone changes BEGIN
	protected void setup() throws StandardException
	/* void setup() throws StandardException */
// GemStone changes END
	{
		super.setup();

		/* decode lock mode */
		lockMode = decodeLockMode(constants.lockMode);

		boolean firstOpen = (rowChanger == null);

		rowCount = 0;
		
		/* Cache query plan text for source, before it gets blown away */
		if (lcc.getRunTimeStatisticsMode())
		{
			/* savedSource nulled after run time statistics generation */
			savedSource = source;
		}

		/* Get or re-use the row changer.
		 * NOTE: We need to set ourself as the top result set
		 * if this is not the 1st execution.  (Done in constructor
		 * for 1st execution.)
		 */
		if (firstOpen)
		{
			rowChanger = lcc.getLanguageConnectionFactory().getExecutionFactory()
				             .getRowChanger( heapConglom, 
										 constants.heapSCOCI, 
										 heapDCOCI,
//Gemstone change BEGINS										 
										 ReuseFactory.getZeroLenIRGArray() /*constants.irgs*/,
										 ReuseFactory.getZeroLenLongArray() /*constants.indexCIDS*/,
//Gemstone change ENDS										 
										 constants.indexSCOCIs,
										 indexDCOCIs,
										 constants.numColumns,
										 tc,
										 constants.changedColumnIds,
										 constants.getBaseRowReadList(),
										 constants.getBaseRowReadMap(),
										 constants.getStreamStorableHeapColIds(),
										 activation);
			rowChanger.setIndexNames(constants.indexNames);
		}
		else
		{
			lcc.getStatementContext().setTopResultSet(this, subqueryTrackingArray);
		}


		/* Open the RowChanger before the source ResultSet so that
		 * the store will see the RowChanger's lock as a covering lock
		 * if it is a table lock.
		 */
		rowChanger.open(lockMode);

		if (numOpens++ == 0)
		{
			source.openCore();
		}
		else
		{
			source.reopenCore();
		}

		/* The source does not know whether or not we are doing a
		 * deferred mode update.  If we are, then we must clear the
		 * index scan info from the activation so that the row changer
		 * does not re-use that information (which won't be valid for
		 * a deferred mode update).
		 */
		if (deferred)
		{
			activation.clearIndexScanInfo();
		}

		if (fkInfoArray != null)
		{
			if (riChecker == null)
			{
				riChecker = new RISetChecker(tc, fkInfoArray);
			}
			else
			{
				riChecker.reopen();
			}
		}

		if (deferred)
		{
			/* Allocate the temporary rows and get result description
			 * if this is the 1st time that we are executing.
			 */
			if (deferredTempRow == null)
			{
				deferredTempRow = RowUtil.getEmptyValueRow(numberOfBaseColumns+1, lcc);
				oldDeletedRow = RowUtil.getEmptyValueRow(numberOfBaseColumns, lcc);
			}

			Properties properties = new Properties();

			// Get the properties on the heap
			// Gemstone changes BEGIN
			//rowChanger.getHeapConglomerateController().getInternalTablePropertySet(properties);
			// Gemstone changes END
			if(beforeUpdateCopyRequired){
				deletedRowHolder =
					new TemporaryRowHolderImpl(activation, properties);
			}
			insertedRowHolder =
				new TemporaryRowHolderImpl(activation, properties);

			rowChanger.setRowHolder(insertedRowHolder);
		}

	}	

	/* Following 2 methods are for checking and make sure we don't have one un-objectified stream
	 * to be inserted into 2 temp table rows for deferred update.  Otherwise it would cause problem
	 * when writing to disk using the stream a second time.  In other cases we don't want to
	 * unnecessarily objectify the stream. beetle 4896.
	 */
	private FormatableBitSet checkStreamCols()
	{
		DataValueDescriptor[] cols = row.getRowArray();
		FormatableBitSet streamCols = null;
		for (int i = 0; i < numberOfBaseColumns; i++)
		{
			if (cols[i+numberOfBaseColumns] instanceof StreamStorable)  //check new values
			{
				if (streamCols == null) streamCols = new FormatableBitSet(numberOfBaseColumns);
				streamCols.set(i);
			}
		}
		return streamCols;
	}

	private void objectifyStream(ExecRow tempRow, FormatableBitSet streamCols) throws StandardException
	{
		DataValueDescriptor[] cols = tempRow.getRowArray();
		for (int i = 0; i < numberOfBaseColumns; i++)
		{
			if (cols[i] != null && streamCols.get(i))
				((StreamStorable)cols[i]).loadStream();
		}
	}

	public boolean collectAffectedRows() throws StandardException
	{

// GemStone changes BEGIN
	  // Neeraj: In transactions we want that even if oneRowSource is there it should
	  // go and try fetch next so that the scan controller at least gets a chance to 
	  // release the lock if it did not eventually qualify .. #43222
	  // [sumedh] No longer using the above approach.
	  /*
          boolean ignoreNoNextEvenIfSingleRowSource = ((GemFireTransaction)this.activation
             .getTransactionController()).getActiveTXState() == null;
          */
	  final TXState localTXState = this.source.initLocalTXState();
// GemStone changes END
		boolean rowsFound = false;
		row = getNextRowCore(source);
		if (row!=null)
			rowsFound = true;
		else
		{
			activation.addWarning(StandardException
			    .newNoRowFoundWarning());
		}

		//beetle 3865, update cursor use index.
		TableScanResultSet tableScan = (TableScanResultSet) activation.getForUpdateIndexScan();
// GemStone changes BEGIN
		boolean notifyCursor = false;
		/* (original code)
		boolean notifyCursor = ((tableScan != null) && ! tableScan.sourceDrained);
		*/
// GemStone changes END
		boolean checkStream = (deferred && rowsFound && ! constants.singleRowSource);
		FormatableBitSet streamCols = (checkStream ? checkStreamCols() : null);
		checkStream = (streamCols != null);
        while ( row != null )
        {

// GemStone changes BEGIN
          
          RowLocation baseRowLocation = null;
// GemStone changes END
			/* By convention, the last column in the result set for an
			 * update contains a SQLRef containing the RowLocation of
			 * the row to be updated.
			 */

			/*
			** If we're doing deferred update, write the new row and row
			** location to the temporary conglomerate.  If we're not doing
			** deferred update, update the permanent conglomerates now
			** using the RowChanger.
			*/
			if (deferred)
 {
        
        /*
         * * If we have a before trigger, we must evaluate the* check constraint
         * after we have executed the trigger.* Note that we have compiled
         * checkGM accordingly (to* handle the different row shape if we are
         * evaluating* against the input result set or a temporary row holder*
         * result set).
         */
        if (triggerInfo == null) {
          evaluateCheckConstraints(checkGM, activation);
        }

        /*
         * * We are going to only save off the updated* columns and the RID. For
         * a trigger, all columns* were marked as needed so we'll copy them all.
         */
        RowUtil.copyRefColumns(deferredTempRow, row, numberOfBaseColumns,
            numberOfBaseColumns + 1);
        if (checkStream)
          objectifyStream(deferredTempRow, streamCols);

        insertedRowHolder.insert(deferredTempRow);

        /*
         * * Grab a copy of the row to delete. We are* going to use this for
         * deferred RI checks.
         */
        if (beforeUpdateCopyRequired) {
          RowUtil.copyRefColumns(oldDeletedRow, row, numberOfBaseColumns);

          deletedRowHolder.insert(oldDeletedRow);
        }

        /*
         * * If we haven't already, lets get a template to* use as a template
         * for our rescan of the base table.* Do this now while we have a real
         * row to use* as a copy.** There is one less column in the base row
         * than* there is in source row, because the base row* doesn't contain
         * the row location.
         */
        if (deferredBaseRow == null) {
          deferredBaseRow = RowUtil.getEmptyValueRow(numberOfBaseColumns, lcc);

          RowUtil.copyCloneColumns(deferredBaseRow, row, numberOfBaseColumns);

          /*
           * * While we're here, let's also create a sparse row for* fetching
           * from the store.
           */
          deferredSparseRow = makeDeferredSparseRow(deferredBaseRow,
              baseRowReadList, lcc);
        }
// GemStone changes BEGIN
        // RowLocation baseRowLocation = (RowLocation)
        baseRowLocation = (RowLocation) (row.getColumn(resultWidth))
            .getObject();
        if (this.referencedKeyCheckRows != null) {
          boolean doRICheck = false;
          int pos = this.refColsUpdtdBits.anySetBit();
          while (pos != -1) {
            int[] baseRowReadMap = constants.getBaseRowReadMap();
            int posInExecRow = baseRowReadMap != null ? baseRowReadMap[this.refColsImpacted[pos] - 1]
                : this.refColsImpacted[pos] - 1;
            if (!row.getColumn(posInExecRow + 1).equals(
                row.getColumn((resultWidth - 1) / 2 + posInExecRow + 1))) {
              doRICheck = true;
              break;
            }
            pos = this.refColsUpdtdBits.anySetBit(pos);

          }

          if (doRICheck) {
            this.referencedKeyCheckRows.add(row.getRowArrayClone());
          }
        }
        if (localTXState != null && baseRowLocation != null) {
          this.source.upgradeReadLockToWrite(baseRowLocation, null);
        }
        
        // GemStone changes END

      }
			    
			else
			{
			  
			 
				evaluateCheckConstraints( checkGM, activation );

				/* Get the RowLocation to update 
			 	* NOTE - Column #s in the Row are 1 based.
			 	*/
				// Gemstone changes BEGIN
				// RowLocation baseRowLocation = (RowLocation)
				baseRowLocation = (RowLocation)
				// Gemstone changes END
					(row.getColumn(resultWidth)).getObject();

				RowUtil.copyRefColumns(newBaseRow,
										row,
										numberOfBaseColumns,
										numberOfBaseColumns);
			 
				if (riChecker != null)
				{
					/*
					** Make sure all foreign keys in the new row
					** are maintained.  Note that we don't bother 
					** checking primary/unique keys that are referenced
					** here.  The reason is that if we are updating
					** a referenced key, we'll be updating in deferred
					** mode, so we wont get here.
					*/
					riChecker.doFKCheck(newBaseRow);
				}
// GemStone changes BEGIN
				if (localTXState != null &&
				    baseRowLocation != null) {
				  this.source.upgradeReadLockToWrite(
				      baseRowLocation, null);
				}
// GemStone changes END

				source.updateRow(newBaseRow);
				if (rowChanger.updateRow(row,newBaseRow,baseRowLocation)) {
				  this.rowCount++;
				}

				//beetle 3865, update cursor use index.
				if (notifyCursor)
					notifyForUpdateCursor(row.getRowArray(),newBaseRow.getRowArray(),baseRowLocation,
											tableScan);
			  
			}

			//rowCount++;
          
      this.source.releasePreviousByteSource();
			// No need to do a next on a single row source
			if (constants.singleRowSource)
			{
				row = null;
			}
			else
			{
				row = getNextRowCore(source);
			}
		}

		return rowsFound;
	}

	/* beetle 3865, updateable cursor use index. If the row we are updating has new value that
	 * falls into the direction of the index scan of the cursor, we save this rid into a hash table
	 * (for fast search), so that when the cursor hits it again, it knows to skip it.  When we get
	 * to a point that the hash table is full, we scan forward the cursor until one of two things
	 * happen: (1) we hit a record whose rid is in the hash table (we went through it already, so
	 * skip it), we remove it from hash table, so that we can continue to use hash table. OR, (2) the scan
	 * forward hit the end.  If (2) happens, we can de-reference the hash table to make it available
	 * for garbage collection.  We save the future row id's in a virtual mem heap.  In any case,
	 * next read will use a row id that we saved.
	 */
	private void notifyForUpdateCursor(DataValueDescriptor[] row, DataValueDescriptor[] newBaseRow,
										RowLocation rl, TableScanResultSet tableScan)
		throws StandardException
	{
		int[] indexCols = tableScan.indexCols;
		int[] changedCols = constants.changedColumnIds;
		boolean placedForward = false, ascending, decided = false, overlap = false;
		int basePos, k;
		/* first of all, we see if there's overlap between changed column ids and index key
		 * columns.  If so, we see if the new update value falls into the future range of the
		 * index scan, if so, we need to save it in hash table.
		 */
		for (int i = 0; i < indexCols.length; i++)
		{
			basePos = indexCols[i];
			if (basePos > 0)
				ascending = true;
			else
			{
				ascending = false;
				basePos = -basePos;
			}
			for (int j = 0; j < changedCols.length; j++)
			{
				if (basePos == changedCols[j])
				{
					decided = true;		//we pretty much decided if new row falls in front
										//of the cursor or behind
					/* the row and newBaseRow we get are compact base row that only have
				 	 * referenced columns.  Our "basePos" is index in sparse heap row, so
					 * we need the BaseRowReadMap to map into the compact row.
					 */
					int[] map = constants.getBaseRowReadMap();
					if (map == null)
						k = basePos - 1;
					else
						k =  map[basePos - 1];

					DataValueDescriptor key;
					/* We need to compare with saved most-forward cursor scan key if we
					 * are reading records from the saved RowLocation temp table (instead
					 * of the old column value) because we only care if new update value
					 * jumps forward the most-forward scan key.
					 */
					if (tableScan.compareToLastKey())
						key = tableScan.lastCursorKey.getColumn(i + 1);
					else
						key = row[k];

					/* Starting from the first index key column forward, we see if the direction
					 * of the update change is consistent with the direction of index scan.
					 * If so, we save it in hash table.
					 */
					if ((ascending && key.greaterThan(newBaseRow[k], key).equals(true)) ||
						(!ascending && key.lessThan(newBaseRow[k], key).equals(true)))
						placedForward = true;
					else if (key.equals(newBaseRow[k], key).equals(true))
					{
						decided = false;
						overlap = true;
					}
					break;
				}
			}
			if (decided)  // already decided if new row falls in front or behind
				break;
		}
		/* If index row gets updated but key value didn't actually change, we still
		 * put it in hash table because it can either fall in front or behind.  This
		 * can happen if the update explicitly sets a value, but same as old.
		 */
		if (overlap && !decided)
			placedForward = true;

		if (placedForward)		// add it to hash table
		{
			/* determining initial capacity of hash table from a few factors:
			 * (1) user specified MAX_MEMORY_PER_TABLE property, (2) min value 100
			 * (3) optimizer estimated row count.  We want to avoid re-hashing if
			 * possible, for performance reason, yet don't waste space.  If initial
			 * capacity is greater than max size divided by load factor, no rehash
			 * is ever needed.
			 */
			int maxCapacity = lcc.getOptimizerFactory().getMaxMemoryPerTable() / 16;
			if (maxCapacity < 100)
				maxCapacity = 100;

			if (tableScan.past2FutureTbl == null)
			{
				double rowCount = tableScan.getEstimatedRowCount();
				int initCapacity = 32 * 1024;
				if (rowCount > 0.0)
				{
					rowCount = rowCount / 0.75 + 1.0;	// load factor
					if (rowCount < initCapacity)
						initCapacity = (int) rowCount;
				}
				if (maxCapacity < initCapacity)
					initCapacity = maxCapacity;

				tableScan.past2FutureTbl = new Hashtable(initCapacity);
			}

			Hashtable past2FutureTbl = tableScan.past2FutureTbl;
			/* If hash table is not full, we add it in.  The key of the hash entry
			 * is the string value of the RowLocation.  If the hash table is full,
			 * as the comments above this function say, we scan forward.
			 *
			 * Need to save a clone because when we get cached currentRow, "rl" shares the
			 * same reference, so is changed at the same time.
			 */
			RowLocation updatedRL = (RowLocation) rl.getClone();

			if (past2FutureTbl.size() < maxCapacity)
				past2FutureTbl.put(updatedRL, updatedRL);
			else
			{
				tableScan.setSkipFutureRowHolder(true);
				ExecRow rlRow = new ValueRow(1);

				for (;;)
				{
					ExecRow aRow = tableScan.getNextRowCore();
					if (aRow == null)
					{
						tableScan.setSourceDrained(true);
						tableScan.past2FutureTbl = null;	// de-reference for garbage coll.
						break;
					}
					RowLocation rowLoc = (RowLocation) aRow.getLastColumn();

					if (updatedRL.equals(rowLoc))  //this row we are updating jumped forward
					{
						saveLastCusorKey(tableScan, aRow);
						break;	// don't need to worry about adding this row to hash any more
					}

					if (tableScan.futureForUpdateRows == null)
					{
						// virtual memory heap. In-memory part size 100. With the co-operation
						// of hash table and in-memory part of heap (hash table shrinks while
						// in-memory heap grows), hopefully we never spill temp table to disk.

						tableScan.futureForUpdateRows = new TemporaryRowHolderImpl
							(activation, null, 100, false, true);
					}

					rlRow.setColumn(1, rowLoc);
					tableScan.futureForUpdateRows.insert(rlRow);
					if (past2FutureTbl.size() < maxCapacity) //we got space in the hash table now, stop!
					{
						past2FutureTbl.put(updatedRL, updatedRL);
						saveLastCusorKey(tableScan, aRow);
						break;
					}
				}
				tableScan.setSkipFutureRowHolder(false);
			}
		}
	}

	private void saveLastCusorKey(TableScanResultSet tableScan, ExecRow aRow) throws StandardException
	{
		/* We save the most-forward cursor scan key where we are stopping, so
		 * that next time when we decide if we need to put an updated row id into
		 * hash table, we can compare with this key.  This is an optimization on
		 * memory usage of the hash table, otherwise it may be "leaking".
		 */
		if (tableScan.lastCursorKey == null)
			tableScan.lastCursorKey = new ValueRow(aRow.nColumns() - 1);
		for (int i = 1; i <= tableScan.lastCursorKey.nColumns(); i++)
		{
			DataValueDescriptor aCol = aRow.getColumn(i);
			if (aCol != null)
				tableScan.lastCursorKey.setColumn(i, aCol.getClone());
		}
	}

	void fireBeforeTriggers() throws StandardException
	{
	  // Gemstone changes BEGIN
	  if (activation.getFunctionContext() != null) {
	    return;
	  }
	  if (GemFireXDUtils.TraceActivation) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
                "UpdateResultSet::fireAfterTriggers activation is: "
                    + activation + " function context is null", new Throwable());
          }
	  // Gemstone changes END
		if (deferred)
		{
			if (triggerInfo != null)
			{
				if (triggerActivator == null)
				{
				triggerActivator = new TriggerEventActivator(lcc, 
											tc, 
											constants.targetUUID,
											triggerInfo,
											TriggerExecutionContext.UPDATE_EVENT,
											activation, null);
				}
				else
				{
					triggerActivator.reopen();
				}

				// fire BEFORE trigger, do this before checking constraints
				triggerActivator.notifyEvent(TriggerEvents.BEFORE_UPDATE, 
												deletedRowHolder.getResultSet(),
												insertedRowHolder.getResultSet());

			}
		}
	}

    void fireAfterTriggers() throws StandardException
	{
    // Gemstone changes BEGIN
    if (activation.getFunctionContext() != null) {
      return;
    }
    if (GemFireXDUtils.TraceActivation) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_ACTIVATION,
          "UpdateResultSet::fireAfterTriggers activation is: "
              + activation + " function context is null", new Throwable());
    }
    // Gemstone changes END
		if (deferred)
		{
			if (triggerActivator != null)
			{
				triggerActivator.notifyEvent(TriggerEvents.AFTER_UPDATE, 
										deletedRowHolder.getResultSet(),
										insertedRowHolder.getResultSet());
			}
		}
	}



	void updateDeferredRows() throws StandardException
	{
		if (deferred)
		{
		  if (this.observer != null) {
	      this.observer.beforeDeferredUpdate();
	    }
			// we already have everything locked 
			deferredBaseCC = 
                tc.openCompiledConglomerate(
                    false,
                    tc.OPENMODE_FORUPDATE|tc.OPENMODE_SECONDARY_LOCKED,
                    lockMode,
                    TransactionController.ISOLATION_SERIALIZABLE,
                    constants.heapSCOCI,
                    heapDCOCI);
			
			CursorResultSet rs = insertedRowHolder.getResultSet();
			try
			{
				/*
				** We need to do a fetch doing a partial row
				** read.  We need to shift our 1-based bit
				** set to a zero based bit set like the store
				** expects.
				*/
				FormatableBitSet readBitSet = RowUtil.shift(baseRowReadList, 1);
				ExecRow deferredTempRow2;

				rs.open();
				while ((deferredTempRow2 = rs.getNextRow()) != null)
				{
					/*
					** Check the constraint now if we have triggers.
					** Otherwise we evaluated them as we read the
					** rows in from the source.
					*/
					if (triggerInfo != null)
					{
						source.setCurrentRow(deferredTempRow2);
						evaluateCheckConstraints(checkGM, activation);
					}

					/* 
					** The last column is a Ref, which contains a 
					** RowLocation.
					*/
					DataValueDescriptor rlColumn = deferredTempRow2.getColumn(numberOfBaseColumns + 1);
					RowLocation baseRowLocation = 
							(RowLocation) (rlColumn).getObject();
	
					/* Get the base row at the given RowLocation */
// GemStone change BEGIN
					baseRowLocation = deferredBaseCC.fetch(
					    baseRowLocation, deferredSparseRow,
					    readBitSet, false, this.deferredRowsOHCache != null ? this:
					      (GemFireTransaction)this.lcc.getTransactionExecute());
					boolean row_exists = baseRowLocation!= null;
					if (!row_exists) {
					  // Rahul : in case of gemfirexd the underyling region entry
					  // could have been deleted/destroyed by the time we are doing
					  // deferred deletes on collected rows.
					  continue;
					}
					else {
					  rlColumn.setValue(baseRowLocation);
					}
// GemStone change END
					if (SanityManager.DEBUG)
					{
					  // Rahul : changed message to include thie rowloaction.
					  if (!row_exists) {
						SanityManager.THROWASSERT(
						    "did not find base row in deferred update for base rowlocation"+baseRowLocation);
					  }
					}
					// Gemstone changes end.
	
					/*
					** Copy the columns from the temp row to the base row.
					** The base row has fewer columns than the temp row,
					** because it doesn't contain the row location.
					*/
					RowUtil.copyRefColumns(newBaseRow,
											deferredTempRow2,
											numberOfBaseColumns);

					if (rowChanger.updateRow(deferredBaseRow,
										newBaseRow,
										baseRowLocation)) {
					  this.rowCount++;
					}
					source.updateRow(newBaseRow);
					if(this.deferredRowsOHCache != null) {
					  this.deferredRowsOHCache.releaseByteSource(0);
					}
				}
			} finally
			{
				source.clearCurrentRow();
				rs.close(false);
			}
		}
	}


	
	void runChecker(boolean restrictCheckOnly) throws StandardException
	{

		/*
		** For a deferred update, make sure that there
		** aren't any primary keys that were removed which
		** are referenced.  
		*/
		//Asif: The below if block will never be executed because the updatingReferencedKey
		// boolean is always set to false.As the RI check is done in our custom code
		// only the FK constraint and other checks should happen through runChecker
		if (deferred && updatingReferencedKey)
		{
			ExecRow	deletedRow;
			CursorResultSet deletedRows; 

			/*
			** For each referenced key that was modified
			*/
			for (int i = 0; i < fkInfoArray.length; i++)
			{
				if (fkInfoArray[i].type == FKInfo.FOREIGN_KEY)
				{
					continue;
				}

				deletedRows = deletedRowHolder.getResultSet();
				try
				{
					/*
					** For each delete row
					*/	
					deletedRows.open();
					while ((deletedRow = deletedRows.getNextRow()) != null)
					{
						if (!foundRow(deletedRow, 
										fkInfoArray[i].colArray, 
										insertedRowHolder))
						{
							riChecker.doRICheck(i, deletedRow, restrictCheckOnly);
						}
					}	
				}
				finally
				{
					deletedRows.close(false);
				}
			}
		}

		/*
		** For a deferred update, make sure that there
		** aren't any foreign keys that were added that
 		** aren't referenced.  
		*/
		if (deferred && updatingForeignKey)
		{
			ExecRow	insertedRow;
			CursorResultSet insertedRows; 

			/*
			** For each foreign key that was modified
			*/
			for (int i = 0; i < fkInfoArray.length; i++)
			{
				if (fkInfoArray[i].type == FKInfo.REFERENCED_KEY)
				{
					continue;
				}

				insertedRows = insertedRowHolder.getResultSet();
				try
				{
					/*
					** For each inserted row
					*/	
					insertedRows.open();
					while ((insertedRow = insertedRows.getNextRow()) != null)
					{
						if (!foundRow(insertedRow, 
										fkInfoArray[i].colArray, 
										deletedRowHolder))
						{
							riChecker.doRICheck(i, insertedRow, restrictCheckOnly);
						}
					}	
				}
				finally
				{
					insertedRows.close(false);
				}
			}
		}

	}

	public static boolean foundRow
	(
		ExecRow					checkRow, 
		int[]					colsToCheck,
		TemporaryRowHolderImpl	rowHolder
	)
		throws StandardException
	{
		ExecRow				scanRow;
		boolean				foundMatch = false;
		Object[] 			checkRowArray = checkRow.getRowArray();
		DataValueDescriptor	checkCol;
		DataValueDescriptor	scanCol;

		CursorResultSet rs = rowHolder.getResultSet();
		try
		{	
			/*
			** For each inserted row
			*/	
			rs.open();
			while ((scanRow = rs.getNextRow()) != null)
			{
				Object[] scanRowArray = scanRow.getRowArray();
				int i;
				for (i = 0; i < colsToCheck.length; i++)
				{
					checkCol = (DataValueDescriptor)checkRowArray[colsToCheck[i]-1];
					scanCol = (DataValueDescriptor)scanRowArray[colsToCheck[i]-1];

					BooleanDataValue result = checkCol.equals(
											scanCol,
											checkCol); // result
					if (!result.getBoolean())
					{
						break;
					}
				}
				if (i == colsToCheck.length)
				{
					foundMatch = true;
					break;
				}	
			}
		}
		finally
		{
			rs.close(false);
		}
		return foundMatch;
	}


	/**
	 * @see ResultSet#cleanUp
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void	cleanUp(final boolean cleanupOnError) throws StandardException
	{ 
		numOpens = 0;
    try {
		/* Close down the source ResultSet tree */
		if (source != null)
		{
			source.close(cleanupOnError);
			// cache source across open()s
		}

		if (triggerActivator != null)
		{
			triggerActivator.cleanup(cleanupOnError);
			// cache triggerActivator across open()s
		}

		if (rowChanger != null)
			rowChanger.close();

		if (deferredBaseCC != null)
			deferredBaseCC.close();
		deferredBaseCC = null;

		if (insertedRowHolder != null)
		{
			insertedRowHolder.close();
		}
	
		if (deletedRowHolder != null)
		{
			deletedRowHolder.close();
		}

		if (riChecker != null)
		{
			riChecker.close();
			// cache riChecker across open()s
		}        
    
    }finally {
		  super.close(cleanupOnError);
		  endTime = statisticsTimingOn ? XPLAINUtil.nanoTime() : 0;
    }
	}

	void rowChangerFinish() throws StandardException
	{
		rowChanger.finish();
	}

  // GemStone changes BEGIN
  @Override
  public void accept(ResultSetStatisticsVisitor visitor) {
    if (this.savedSource != null) {
      visitor.setNumberOfChildren(1);
    }
    else {
      visitor.setNumberOfChildren(0);
    }

    // pre-order, depth-first traversal
    // me first
    visitor.visit(this);
    
    // then my child
    if (savedSource != null) {
      savedSource.accept(visitor);
    }
  }
  // GemStone changes END

  @Override
  public void release() {
    this.deferredRowsOHCache.release();
  }

  @Override
  public void addByteSource(@Unretained OffHeapByteSource byteSource) {
    if (byteSource != null) {
      this.deferredRowsOHCache.put(byteSource.getMemoryAddress());
    }
    else {
      this.deferredRowsOHCache.put(0);
    }
  }

  @Override
  public void registerWithGemFireTransaction(OffHeapResourceHolder owner) {
   GemFireTransaction tran =  ((GemFireTransaction)this.lcc
        .getTransactionExecute());
   tran.registerOffHeapResourceHolder(owner);    
  }

  @Override
  public boolean optimizedForOffHeap() {    
    return true;
  }

  @Override
  public void releaseByteSource(int positionFromEnd) {
    this.deferredRowsOHCache.release();
  }

}
