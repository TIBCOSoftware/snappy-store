/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.execute.IndexRowToBaseRowResultSet

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
import com.gemstone.gemfire.internal.cache.TXState;
import com.pivotal.gemfirexd.internal.catalog.types.ReferencedColumnsDescriptorImpl;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeap;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapResourceHolder;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.CursorResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TemporaryRowHolder;
import com.pivotal.gemfirexd.internal.iapi.store.access.DynamicCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.xplain.XPLAINUtil;

/**
 * Takes a result set with a RowLocation as the last column, and uses the
 * RowLocation to get and return a row from the given base conglomerate.
 * Normally, the input result set will be a TableScanResultSet scanning an
 * index conglomerate.
 *
 */
// GemStone changes BEGIN
// changed accessibility from package to public
public final class IndexRowToBaseRowResultSet extends NoPutResultSetImpl 
 
// GemStone changes END
	implements CursorResultSet {

    // set in constructor and not altered during
    // life of object.
    public NoPutResultSet source;
	private GeneratedMethod restriction;
	public FormatableBitSet accessedHeapCols;
	//caching accessed columns (heap+index) beetle 3865
	private FormatableBitSet accessedAllCols;
	public String indexName;
	private int[] indexCols;
	private DynamicCompiledOpenConglomInfo dcoci;
	private StaticCompiledOpenConglomInfo scoci;

	// set in open() and not changed after that
	//private ConglomerateController	baseCC;
	//private boolean                 closeBaseCCHere;
	private ExecRow					resultRow;
	private boolean					forUpdate;
    
// GemStone changes BEGIN
  // fetch from base table using an ExecRow instead of a DataValueDescriptor[].
  // in the case where we are getting the 
  // entire row, then the resultRow is the same object as the baseRow.
	// private DataValueDescriptor[]	rowArray;
  private ExecRow baseRow;
  private boolean addRegionAndKey;
  private boolean addKeyForSelectForUpdate;
  private String regionName ;
  private boolean isReplicate;
  private final boolean delayScanOpening;

  /**
   * If index scan has both start and stop keys then we will fault in the values
   * from disk to LRU list. Can make it more intelligent depending on the actual
   * expected number of rows (using cost controller but it will not provide
   * useful information for prepared statements?).
   */
  private boolean faultInValues;

  @Override
  public boolean isReplicateIfSetOpSupported() {
    return isReplicate;
  }
  /* soubhik: 20090116:


   * stop qualifier while opening the scanController will be indexCol > 10
   * and indexCol != 5 will be evaluated here in the restriction list.
   * 
   * For equality it compares again and that appears to be unnecessary (specially 
   * string comparison), so introducing this variable to guard additional equality
   * comparison as compare() while fetching from the map has taken care of it. For
   * other operators we still need to do the check as headMap or tailMap can return
   * additional rows.
   */
  boolean isStartStopKeySame = false;
  protected final GemFireContainer gfc;
// GemStone changes END

	// changed a whole bunch
	RowLocation	baseRowLocation;

	/* Remember whether or not we have copied any
	 * columns from the source row to our row yet.
	 */
	boolean copiedFromSource;

	/* Run time statistics variables */
	public long restrictionTime;

	protected boolean currentRowPrescanned;
	private boolean sourceIsForUpdateIndexScan;
  private final boolean isSourceOfTypeOffHeapResourceHolder ;
	
    //
    // class interface
    //
    IndexRowToBaseRowResultSet(
					long conglomId,
					int scociItem,
					Activation a,
					NoPutResultSet source,
					GeneratedMethod resultRowAllocator,
					int resultSetNumber,
					String indexName,
					int heapColRefItem,
					int allColRefItem,
					int heapOnlyColRefItem,
					int indexColMapItem,
					GeneratedMethod restriction,
					boolean forUpdate,
					double optimizerEstimatedRowCount,
					double optimizerEstimatedCost,
					//GemStone changes BEGIN
					boolean delayScanOpening
					//GemStone changes END				
                    ) 
		throws StandardException
	{
		super(a, resultSetNumber, optimizerEstimatedRowCount, optimizerEstimatedCost);
		
		final Object[] saved = a.getSavedObjects();

		scoci = (StaticCompiledOpenConglomInfo)saved[scociItem];
		TransactionController tc = activation.getTransactionController();
		dcoci = tc.getDynamicCompiledConglomInfo(conglomId);
        this.source = source;
		this.indexName = indexName;
		this.forUpdate = forUpdate;
		this.restriction = restriction;
		if(this.source instanceof OffHeapResourceHolder) {
		  this.isSourceOfTypeOffHeapResourceHolder = true;
		}else {
		  this.isSourceOfTypeOffHeapResourceHolder = false;
		}
		//GemStone changes BEGIN
		this.delayScanOpening = delayScanOpening;
		//GemStone changes END
		/* RESOLVE - once we push Qualifiers into the store we
		 * need to clear their Orderable cache on each open/reopen.
		 */

		// retrieve the valid column list from
		// the saved objects, if it exists
		if (heapColRefItem != -1) {
			this.accessedHeapCols = (FormatableBitSet)saved[heapColRefItem];
		}
		if (allColRefItem != -1) {
			this.accessedAllCols = (FormatableBitSet)saved[allColRefItem];
		}

		// retrieve the array of columns coming from the index
		indexCols = 
			((ReferencedColumnsDescriptorImpl)
			 saved[indexColMapItem]).getReferencedColumnPositions();

    
// GemStone changes BEGIN
    this.gfc = ((MemHeap)scoci).getGemFireContainer();
    this.regionName = gfc.getQualifiedTableName();
    if (GemFireXDUtils.TraceOuterJoin
        && ((this.gfc != null) && this.gfc.isApplicationTable())) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
          "IndexRowToBaseRowResultset::IndexRowToBaseRow gfc is" + this.gfc
              + " activation is: " + System.identityHashCode(a)
              + " addregionandkey from a: " + a.isSpecialCaseOuterJoin());
    }
    if (this.lcc != null) {
      this.addRegionAndKey = a.isSpecialCaseOuterJoin();
      if (a.getFunctionContext() != null) {
        this.addKeyForSelectForUpdate = a.needKeysForSelectForUpdate();
      }
    }
    this.isReplicate = !gfc.isPartitioned();
    // RowFormatter will be null if this is not a byte array store table
    // Use the current RowFormatter for template row, which will be
    // adjusted with required RowFormatter for "select *" kind of queries
    // if required by MemHeapController
    final RowFormatter rf = gfc.getCurrentRowFormatter();
    // td is null if this is called during boot, but in that case
    // useBytes will be false in any case so it doesn't matter
    TableDescriptor td = gfc.getTableDescriptor();
    boolean getWholeRow = false;
    if (td != null) {
      int numCols = td.getNumberOfColumns();
      getWholeRow = true;
      if (accessedAllCols != null) {
        for (int i = 0; i < numCols; i++) {
          if (!accessedAllCols.isSet(i)) {
            getWholeRow = false;
            break;
          }
        } 
      }
    }

    // also, gets whole row only if accessedHeapCols is equal to accessedAllCols.
    // This should be the case if this is a SELECT, but may not be the case
    // for other cases such as DELETE
    // [sumedh] Is this really required? If getWholeRow is true then DELETE
    // and other DMLs should also work fine.
    if (getWholeRow && forUpdate) {
      getWholeRow = accessedAllCols == null
                       && accessedHeapCols == null
                    || accessedAllCols != null
                       && accessedAllCols.equals(accessedHeapCols);
    }

    // keep result rows in byte array format only if there are byte arrays
    // in the base table AND we are getting all the results columns
    // from the heap.
    boolean useBytes = gfc.isByteArrayStore() && getWholeRow;

    // if we are getting the whole row as bytes, then optimize by using null
    // bit sets and set the resultRow to be a CompactExecRow
    if (useBytes) {
      this.accessedAllCols = null;
      this.accessedHeapCols = null;
      this.resultRow = gfc.newTemplateRow();
    } else {
      /* Get the result row template */
      this.resultRow = (ExecRow) resultRowAllocator.invoke(activation);
    }

    // Note that getCompactRow will assign its return value to the
    // variable compactRow which can be accessed through
    // inheritance. Hence we need not collect the return value
    // of the method.
    getCompactRow(resultRow, accessedAllCols, false, useBytes, gfc, rf, true);

		/* If there's no partial row bit map, then we want the entire
		 * row, otherwise we need to diddle with the row array so that
		 * we only get the columns coming from the heap on the fetch.
		 */
		if (accessedHeapCols == null) {
        this.baseRow = this.resultRow;
		}
		else {
			// Figure out how many columns are coming from the heap
      DataValueDescriptor[] dvds;
      
			final DataValueDescriptor[] resultRowArray =
				resultRow.getRowArray();
			final FormatableBitSet heapOnly =
				(FormatableBitSet)saved[heapOnlyColRefItem];
			final int heapOnlyLen = heapOnly.getLength();

			// Need a separate DataValueDescriptor array in this case
			dvds =
 				new DataValueDescriptor[heapOnlyLen];
			final int minLen = Math.min(resultRowArray.length, heapOnlyLen);

			// Make a copy of the relevant part of rowArray
			for (int i = 0; i < minLen; ++i) {
				if (resultRowArray[i] != null && heapOnly.isSet(i)) {
					dvds[i] = resultRowArray[i];
				}
			}
      this.baseRow = gfc.newValueRow(dvds);
		}
		// ensure one initLocalTXState() call before getNextRowCore()
		initLocalTXState();
// GemStone changes END
		recordConstructorTime();
		
                // GemStone changes BEGIN
		printResultSetHierarchy();
                // GemStone changes END
	}

	//
	// ResultSet interface (leftover from NoPutResultSet)
	//

  //GemStone changes BEGIN
     public void openCore() throws StandardException {
       if(!this.delayScanOpening) {
         this.basicOpenCore();
       }
     }
     
   //GemStone changes END
	/**
     * open this ResultSet.
	 *
	 * @exception StandardException thrown if cursor finished.
     */
	public void	basicOpenCore() throws StandardException 
	{
		boolean						lockingRequired = false;
		TransactionController		tc;

		// REVISIT: through the direct DB API, this needs to be an
		// error, not an ASSERT; users can open twice. Only through JDBC
		// is access to open controlled and ensured valid.
		if (SanityManager.DEBUG)
		{
	    	SanityManager.ASSERT( ! isOpen,
								"IndexRowToBaseRowResultSet already open");
		}

		beginTime = statisticsTimingOn ? XPLAINUtil.nanoTime() : 0;

		isOpen = true;
		source.openCore();
// GemStone changes BEGIN
		faultInValues = false;
// GemStone changes END
                if (source instanceof TableScanResultSet) {
                  TableScanResultSet ts = (TableScanResultSet) source;
// GemStone changes BEGIN
                 
                  if (ts.startPosition != null && ts.stopPosition != null) {
                    faultInValues = true;
                    if (ts.startPosition.equals(ts.stopPosition)) {
                      isStartStopKeySame = true;
                    }
                  }
                   /* (original code)
                   // not used in GFXD
                   if(ts.indexCols != null)
                     sourceIsForUpdateIndexScan = true;
                   if( (ts.startPosition != null && ts.stopPosition != null) &&
                       ts.startPosition.equals(ts.stopPosition)
                     ) {
                     isStartStopKeySame = true;
                   }
                   */
// GemStone changes END
                }

		/* Get a ConglomerateController for the base conglomerate 
		 * NOTE: We only need to acquire locks on the data pages when
		 * going through the index when we are at READ COMMITTED and
		 * the source is a BulkTableScan or HashScan.  (The underlying
		 * row will not be guaranteed to be locked.)
		 */
		if (source.requiresRelocking())
		{
			lockingRequired = true;
		}

		tc = activation.getTransactionController();

		int openMode;
		int isolationLevel;
		
		if (forUpdate)
		{
			openMode = TransactionController.OPENMODE_FORUPDATE;
		}
		else
		{
			openMode = 0;
		}
		isolationLevel = source.getScanIsolationLevel();

		if (!lockingRequired)
		{
            // flag indicates that lock has already been acquired by access to
            // the secondary index, and need not be gotten again in the base
            // table.
			openMode |= TransactionController.OPENMODE_SECONDARY_LOCKED;
		}
		
		/* Try to get the ConglomerateController from the activation
		 * first, for the case that we are part of an update or delete.
		 * If so, then the RowChangerImpl did the correct locking.
		 * If not there, then we go off and open it ourself.
		 */
		/*
		if (forUpdate)
		{
			baseCC = activation.getHeapConglomerateController();
		}

		if (baseCC == null)
		{
			baseCC = 
		        tc.openCompiledConglomerate(
                    activation.getResultSetHoldability(),
				    openMode,
					// consistent with FromBaseTable's updateTargetLockMode
					TransactionController.MODE_RECORD,
	                isolationLevel,
					scoci,
					dcoci);
			closeBaseCCHere = true;
		}
		*/

		//isOpen = true;
		numOpens++;
		if (statisticsTimingOn) openTime += getElapsedNanos(beginTime);
	}

	//GemStone changes BEGIN
	/**
         * reopen this ResultSet.
         *
         * @exception StandardException thrown if cursor finished.
         */
        public void     reopenCore() throws StandardException {
           if(!this.delayScanOpening) {
             this.basicReopenCore();
           }
         
        }
        
      //GemStone changes END
	/**
	 * reopen this ResultSet.
	 *
	 * @exception StandardException thrown if cursor finished.
	 */
	public void	basicReopenCore() throws StandardException {

		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(isOpen,
					"IndexRowToBaseRowResultSet already open");
		}

		beginTime = statisticsTimingOn ? XPLAINUtil.nanoTime() : 0;

		source.reopenCore();

		numOpens++;
		if (statisticsTimingOn) openTime += getElapsedNanos(beginTime);
	}

	/**
     * Return the requested values computed
     * from the next row (if any) for which
     * the restriction evaluates to true.
     * <p>
     * restriction and projection parameters
     * are evaluated for each row.
	 *
	 * @exception StandardException thrown on failure.
	 * @exception StandardException ResultSetNotOpen thrown if not yet open.
	 *
	 * @return the next row in the result
	 */
	public ExecRow	getNextRowCore() throws StandardException {
	  
	//GemStone changes BEGIN
	  if(this.delayScanOpening) {
            if(!this.isOpen &&  numOpens == 0) {
              this.basicOpenCore();
            }else if(!this.isOpen ) {
              this.basicReopenCore();
              
            }       
          }
          //GemStone changes END
	    ExecRow sourceRow = null;
		ExecRow retval = null;
	    boolean restrict = false;
	    DataValueDescriptor restrictBoolean;
		long	beginRT = 0;

		beginTime = statisticsTimingOn ? XPLAINUtil.nanoTime() : 0;
	    if ( ! isOpen ) {
			throw StandardException.newException(SQLState.LANG_RESULT_SET_NOT_OPEN, "next");
		}

// GemStone changes BEGIN
	    final TXState localTXState = this.localTXState;
            if (GemFireXDUtils.TraceOuterJoin
                && ((this.gfc != null) && this.gfc.isApplicationTable())) {
              SanityManager.DEBUG_PRINT(
                  GfxdConstants.TRACE_OUTERJOIN_MERGING,
                  "IndexRowToBaseRowResultset::getNextRowCore addregionkeyinfo: "
                      + this.addRegionAndKey + " this is: "
                      + System.identityHashCode(this));
            }
// GemStone changes END
		/* beetle 3865, updateable cursor using index.  When in-memory hash table was full, we
		 * read forward and saved future row id's in a virtual-memory-like temp table.  So if
		 * we have rid's saved, and we are here, it must be non-covering index.  Intercept it
		 * here, so that we don't have to go to underlying index scan.  We get both heap cols
		 * and index cols together here for better performance.
		 */
// GemStone changes BEGIN
		ScanResultSet src;
		TemporaryRowHolder futureForUpdateRows;
		if (sourceIsForUpdateIndexScan && (futureForUpdateRows = (src =
		  (ScanResultSet)source).getFutureForUpdateRows()) != null)
		/* (original code)
		if (sourceIsForUpdateIndexScan && ((TableScanResultSet) source).futureForUpdateRows != null)
		*/
		{
			currentRowPrescanned = false;
// GemStone changes BEGIN
			TemporaryRowHolderResultSet futureRowRS;
			if ((futureRowRS = src.getFutureRowResultSet()) == null) {
			  futureRowRS = (TemporaryRowHolderResultSet)
			      futureForUpdateRows.getResultSet();
			  src.setFutureRowResultSet(futureRowRS);
			  futureRowRS.openCore();
                        }

                        ExecRow ridRow = futureRowRS.getNextRowCore();
			/* (original code)
			TableScanResultSet src = (TableScanResultSet) source;

			if (src.futureRowResultSet == null)
			{
				src.futureRowResultSet = (TemporaryRowHolderResultSet) src.futureForUpdateRows.getResultSet();
				src.futureRowResultSet.openCore();
			}

			ExecRow ridRow = src.futureRowResultSet.getNextRowCore();
			*/
// GemStone changes END

			currentRow = null;

			if (ridRow != null)
			{
				/* To maximize performance, we only use virtual memory style heap, no
				 * position index is ever created.  And we save and retrieve rows from the
				 * in-memory part of the heap as much as possible.  We can also insert after
				 * we start retrieving, the assumption is that we delete the current row right
				 * after we retrieve it.
				 */
// GemStone changes BEGIN
				futureRowRS.deleteCurrentRow();
				/* (original code)
				src.futureRowResultSet.deleteCurrentRow();
				*/
// GemStone changes END
				baseRowLocation = (RowLocation) ridRow.getColumn(1);
// GemStone changes BEGIN
				//TODO:Asif: Chcek this location for byte source release.
				baseRowLocation = source.fetch(baseRowLocation,
				    compactRow, accessedAllCols,
				    this.faultInValues, this.gfc);
				if (baseRowLocation != null) {
				  ridRow.setColumn(1,baseRowLocation);
				}
// GemStone changes END
				currentRow = compactRow;
				currentRowPrescanned = true;
			}
// GemStone changes BEGIN
			else if (src.sourceDrained())
			/* (original code)
			else if (src.sourceDrained)
			*/
// GemStone changes END
				currentRowPrescanned = true;

			if (currentRowPrescanned)
			{
				setCurrentRow(currentRow);

// GemStone changes BEGIN
				setRegionAndKeyInfo(this.currentRow);
				if (localTXState != null && this.forUpdate &&
				    this.isTopResultSet && this.baseRowLocation != null) {
				  upgradeReadLockToWrite(this.baseRowLocation, this.gfc);
				}
// GemStone changes END
				if (statisticsTimingOn) nextTime += getElapsedNanos(beginTime);
	 	   		return currentRow;
			}
		}

		/* Loop until we get a row from the base page that qualifies or
		 * there's no more rows from the index that qualify. (If the RID
		 * returned by the index does not qualify, then we have to go back
		 * to the index to see if there is another RID to consider.)
		 */
		do 
		{
			sourceRow = source.getNextRowCore();

			if (sourceRow != null) {

				if (SanityManager.DEBUG) {
					SanityManager.ASSERT(
						sourceRow.getLastColumn()
														instanceof RowLocation,
						"Last column of source row is not a RowLocation"
							);
				}

				baseRowLocation = (RowLocation)
						sourceRow.getLastColumn();

				// Fetch the columns coming from the heap
// GemStone changes BEGIN
				baseRowLocation = source.fetch(baseRowLocation,
				    this.baseRow, accessedHeapCols,
				    this.faultInValues, this.gfc);
				boolean row_exists = baseRowLocation != null;
// GemStone changes END

                if (row_exists)
                {
					/* We only need to copy columns from the index row 
					 * to our result row once as we will be reusing the
					 * wrappers in that case.
					 * NOTE: When the underlying ResultSet got an 
					 * instantaneous lock (BulkTableScan or HashScan)
					 * then we will be getting all of the columns anew
					 * from the index (indexCols == null).
					 */
                  
          // GemStone changes BEGIN
          // ???:ezoerner:20090107 not sure why this copying from index
          // columns is done, perhaps this is for updatable result sets?
          // No point in doing this if the compactRow is a CompactExecRow
          if (!(this.baseRow instanceof AbstractCompactExecRow)) {
            if (! copiedFromSource)
            {
              copiedFromSource = true;

              // Copy the columns coming from the index into resultRow
              for (int index = 0; index < indexCols.length; index++)
              {
                if (indexCols[index] != -1)
                {
                  compactRow.setColumn(
                        index + 1,
                        sourceRow.getColumn(indexCols[index] + 1));
                }
              }
            }
            setCurrentRow(compactRow);
          }
          else {
            setCurrentRow(this.baseRow);
          }

                    //setCurrentRow(compactRow);
          
         // GemStone changes END

                  if( ! isStartStopKeySame ) {
                    restrictBoolean = (DataValueDescriptor) 
                        ((restriction == null) ? 
                             null : restriction.invoke(activation));

                    if (statisticsTimingOn) restrictionTime += getElapsedNanos(beginRT);

                    // if the result is null, we make it false --
                    // so the row won't be returned.
                    restrict = (restrictBoolean == null) ||
                                ((! restrictBoolean.isNull()) &&
                                    restrictBoolean.getBoolean());
                  } 
                  else {
                    restrict = isStartStopKeySame;
                  }
                }

				if (! restrict || ! row_exists)
				{
					rowsFiltered++;
// GemStone changes BEGIN
					//if (localTXState != null) {
					  filteredRowLocationPostRead(localTXState);
					//}
// GemStone changes END
					  //This is now called from inside filteredRowLocation
					//clearCurrentRow();
					baseRowLocation = null;

				}
				else
				{
					currentRow = compactRow;
				}

				/* Update the run time statistics */
				rowsSeen++;

				retval = currentRow;
		    } else {
				clearCurrentRow();
				baseRowLocation = null;

				retval = null;
			}
	    } 
		while ( (sourceRow != null) && (! restrict ) );

// GemStone changes BEGIN
		setRegionAndKeyInfo(retval);
		if (localTXState != null && this.forUpdate &&
		    this.isTopResultSet && this.baseRowLocation != null) {
		  upgradeReadLockToWrite(this.baseRowLocation, this.gfc);
		}
// GemStone changes END
		if (statisticsTimingOn) nextTime += getElapsedNanos(beginTime);
    	return retval;
	}

	/**
	 * If the result set has been opened,
	 * close the open scan.
	 *
	 * @exception StandardException thrown on error
	 */
	public void	close(boolean cleanupOnError) throws StandardException
	{
		beginTime = statisticsTimingOn ? XPLAINUtil.nanoTime() : 0;
	    if ( isOpen ) {
        
	   
			// we don't want to keep around a pointer to the
			// row ... so it can be thrown away.
			// REVISIT: does this need to be in a finally
			// block, to ensure that it is executed?
	    	clearCurrentRow();

	    	/*
			if (closeBaseCCHere)
			{
                // This check should only be needed in the error case where
                // we may call this close() routine as part of transaction
                // backout cleanup if any of the following routines fail.
                // If one of the subsequent statements gets an error, we
                // will try to close this result set as part of transaction
                // cleanup, and without this check we get a null pointer
                // exception because we have null'd out baseCC.
              
                if (baseCC != null)
                    baseCC.close();
			}

			/* Make sure to null out baseCC since
			 * we check for null baseCC after looking
			 * in the StatementContext.
			 *
			baseCC = null;
			*/
	        source.close(cleanupOnError);

			super.close(cleanupOnError);
	    }
		else if (SanityManager.DEBUG) {
			SanityManager.DEBUG("CloseRepeatInfo","Close of IndexRowToBaseRowResultSet repeated");
		}

		if (statisticsTimingOn) closeTime += getElapsedNanos(beginTime);
	}

	/**
	 * Return the total amount of time spent in this ResultSet
	 *
	 * @param type	CURRENT_RESULTSET_ONLY - time spent only in this ResultSet
	 *				ENTIRE_RESULTSET_TREE  - time spent in this ResultSet and below.
	 *
	 * @return long		The total amount of time spent (in milliseconds).
	 */
	public final long getTimeSpent(int type, int timeType)
	{
                final long time = PlanUtils.getTimeSpent(constructorTime, openTime, nextTime, closeTime, timeType);
                
		if (type == NoPutResultSet.CURRENT_RESULTSET_ONLY)
		{
			return	time - source.getTimeSpent(ENTIRE_RESULTSET_TREE, timeType);
		}
		else
		{
                      // GemStone changes BEGIN
                      return timeType == ALL ? (time - constructorTime) : time;
                      /*(original code) return totTime; */
                      // GemStone changes END
		}
	}

	//
	// CursorResultSet interface
	//
	/**
	 * Return the RowLocation of the base row.
	 *
	 * @see CursorResultSet
	 *
	 * @return the row location of the current cursor row.
	 * @exception StandardException thrown on failure.
	 */
	public RowLocation getRowLocation() throws StandardException {
		return baseRowLocation;
	}

	/**
	 * @see NoPutResultSet#positionScanAtRowLocation
	 * 
	 * Also remembers row location so that subsequent invocations of
	 * getCurrentRow will not read the index row to look up the row
	 * location base row, but reuse the saved row location.
	 */
	public void positionScanAtRowLocation(RowLocation rl) 
		throws StandardException 
	{
		baseRowLocation = rl;
		source.positionScanAtRowLocation(rl);
	}
	
	
	public ExecRow getCurrentRow() throws StandardException {
     return this.activation.getCurrentRow(this.resultSetNumber);
    
	}

	/**	 * Gets last row returned.
	 *
	 * @see CursorResultSet
	 *
	 * @return the last row returned.
	 * @exception StandardException thrown on failure.
	 */
	/* RESOLVE - this should return activation.getCurrentRow(resultSetNumber),
	 * once there is such a method.  (currentRow is redundant)
	 */
	public ExecRow _getCurrentRow() throws StandardException {
	    ExecRow sourceRow = null;

		if (SanityManager.DEBUG) {
			SanityManager.ASSERT(isOpen,
					"IndexRowToBaseRowResultSet is expected to be open");
		}
                // Gemstone changes BEGIN
		if (currentRowPrescanned) {
		  setRegionAndKeyInfo(this.currentRow);
			return currentRow;
		}
		// Gemstone changes BEGIN

		/* Nothing to do if we're not currently on a row */
		if (currentRow == null)
		{
			return null;
		}

		// We do not need to read the row from the index first, since we already 
		// have the rowLocation of the current row and can read it directly from 
		// the heap.
		sourceRow = activation.getExecutionFactory().
				getValueRow(indexCols.length);
// GemStone changes BEGIN
		sourceRow.setRowArray(this.baseRow);
		// Fetch the columns coming from the heap
		baseRowLocation = source.fetch(baseRowLocation, sourceRow,
		    (FormatableBitSet) null, this.faultInValues, this.gfc);
		boolean row_exists = baseRowLocation != null;
// GemStone changes END
		if (row_exists) {
			setCurrentRow(sourceRow);
		} else {
			clearCurrentRow();
		}
		// Gemstone changes BEGIN
		setRegionAndKeyInfo(this.currentRow);
		// Gemstone changes END
		return currentRow;
	}

	/**
	 * Is this ResultSet or it's source result set for update.
	 * beetle 3865: updateable cursor using index scan.  We didn't need this function
	 * before because we couldn't use index for update cursor.
	 * 
	 * @return Whether or not the result set is for update.
	 */
	public boolean isForUpdate()
	{
		return source.isForUpdate();
	}

  // GemStone changes BEGIN
  private void setRegionAndKeyInfo(ExecRow currRow) {
    if (GemFireXDUtils.TraceOuterJoin && ((this.gfc != null) && this.gfc.isApplicationTable())) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_OUTERJOIN_MERGING,
          "IndexRowToBaseRowResultset::setRak: current row: " + currRow
              + ", addRegionAndKey = " + this.addRegionAndKey
              + ", region name = " + this.regionName + ", isreplicate = "
              + this.isReplicate + " IndexRowToBaseRowResultSet is: " + System.identityHashCode(this));
    }

    if (currRow != null) {
      if (this.addRegionAndKey || this.addKeyForSelectForUpdate) {
        Object currBaseKey = this.baseRowLocation.getKeyCopy();
        if (this.addRegionAndKey) {
          assert this.addKeyForSelectForUpdate == false;
          currRow.clearAllRegionAndKeyInfo();
          RegionAndKey rak = new RegionAndKey(this.regionName,
              currBaseKey, this.isReplicate);
          currRow.addRegionAndKey(rak);
          if (GemFireXDUtils.TraceOuterJoin
              && ((this.gfc != null) && this.gfc.isApplicationTable())) {
            SanityManager.DEBUG_PRINT(
                GfxdConstants.TRACE_OUTERJOIN_MERGING,
                "IndexRowToBaseRowResultset::setRak: added region and key info: "
                    + rak + " to current row: " + currRow
                    + " IndexRowToBaseRowResultSet is: "
                    + System.identityHashCode(this));
          }
        }
        if (this.addKeyForSelectForUpdate) {
          assert this.addRegionAndKey == false;
          currRow.clearAllRegionAndKeyInfo();
          currRow.addRegionAndKey(new RegionAndKey(null, currBaseKey,
              this.isReplicate));
        }
      }
    }
  }

  @Override
  public void updateRowLocationPostRead() throws StandardException {
    upgradeReadLockToWrite(this.baseRowLocation, this.gfc);
  }

 
  @Override
  public void filteredRowLocationPostRead(TXState localTXState) throws StandardException {
    if(localTXState != null ) {
      releaseRowLocationLock(this.baseRowLocation, this.gfc);
    }
    if(this.isSourceOfTypeOffHeapResourceHolder) {
      this.basicReleasePreviousByteSource();
    }
  }

  
  private void basicReleasePreviousByteSource() {
    if(this.gfc.isOffHeap() && this.baseRowLocation != null ) {
      ((OffHeapResourceHolder)this.source).releaseByteSource(0);
    }
  }
  
  @Override
  public void releasePreviousByteSource() {
    if( this.isSourceOfTypeOffHeapResourceHolder && 
        ((OffHeapResourceHolder)this.source).optimizedForOffHeap()) {
      this.basicReleasePreviousByteSource();
    }
  }
  
  /**
   * {@inheritDoc}
   */
  @Override
  public boolean supportsMoveToNextKey() {
    return this.source.supportsMoveToNextKey();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getScanKeyGroupID() {
    return this.source.getScanKeyGroupID();
  }

  @Override
  public void accept(
      ResultSetStatisticsVisitor visitor) {
    if (source != null) {
      visitor.setNumberOfChildren(1);
    }
    else {
      visitor.setNumberOfChildren(0);
    }

    visitor.visit(this);

    if (source != null) {
      source.accept(visitor);
    }
  }
  
  @Override
  public void resetStatistics() {
    restrictionTime = 0;
    super.resetStatistics();
    source.resetStatistics();
  }
  
  @Override
  public StringBuilder buildQueryPlan(final StringBuilder builder, final PlanUtils.Context context) {
    
    super.buildQueryPlan(builder, context);

    final RowFormatter rf = gfc.getCurrentRowFormatter();
    StringBuilder accessedCols = new StringBuilder(this.indexName).append(" : ");
    boolean first = true;
    if (accessedHeapCols != null && rf != null)
    {
      for (int inPosition = 0; inPosition < accessedHeapCols.getLength(); inPosition++)
      {
            if (accessedHeapCols.isSet(inPosition))
            {
                    ColumnDescriptor cd = rf.getColumnDescriptor(inPosition);
                    if (cd == null) {
                      continue;
                    }
                    if (!first) {
                      accessedCols.append(", ");
                    }
                    else {
                      first = false;
                    }
                    accessedCols.append(cd.getColumnName());
            }
      }
    }
    
    PlanUtils.xmlAttribute(builder, PlanUtils.DETAILS, accessedCols);
    
    PlanUtils.xmlTermTag(builder, context, PlanUtils.OP_INDEXSCAN);
    
    if(this.source != null) {
      this.source.buildQueryPlan(builder, context.pushContext());
    }
    
    PlanUtils.xmlCloseTag(builder, context, this);
    
    return builder;
  }
  
  @Override
  public void printResultSetHierarchy() {
    if (SanityManager.DEBUG) {
      if (GemFireXDUtils.TraceNCJ) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN,
            "ResultSet Created: "
                + this.getClass().getSimpleName()
                + " with resultSetNumber="
                + resultSetNumber
                + " with source = "
                + (this.source != null ? this.source.getClass().getSimpleName()
                    : null) + " and source ResultSetNumber = "
                + (this.source != null ? this.source.resultSetNumber() : -1));
      }
    }
  }
  // GemStone changes END
}
