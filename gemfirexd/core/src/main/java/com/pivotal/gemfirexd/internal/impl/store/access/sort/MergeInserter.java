/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.store.access.sort.MergeInserter

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

package com.pivotal.gemfirexd.internal.impl.store.access.sort;

import com.gemstone.gnu.trove.TIntArrayList;
import com.gemstone.gnu.trove.TLongArrayList;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortController;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.TransactionManager;


/**


**/

final class MergeInserter implements SortController
{
	/**
	The sort this inserter is for.
	**/
	private MergeSort sort;

	/**
	The transaction this inserter is in.
	**/
	private TransactionManager tran;

	/**
	A vector of the conglomerate ids of the merge runs.
	**/
	private TLongArrayList mergeRuns;

	/**
	An in-memory ordered set that is used to sort rows
	before they're sent to merge runs.
	**/
	private SortBuffer sortBuffer;

	/**
	Information about memory usage to dynamically tune the
	in-memory sort buffer size.
	*/
	private long beginFreeMemory;
	private long beginTotalMemory;
	private long estimatedMemoryUsed;
	private boolean avoidMergeRun;		// try to avoid merge run if possible
    private int runSize;
    private int totalRunSize;

    String  stat_sortType;
    int     stat_numRowsInput;
    int     stat_numRowsOutput;
    int     stat_numMergeRuns;
    TIntArrayList  stat_mergeRunsSize;


// GemStone changes BEGIN
    final GfxdHeapThresholdListener thresholdListener = Misc.getMemStore()
        .thresholdListener();
// GemStone changes END
	/*
	 * Methods of SortController
	 */

	/**
    Insert a row into the sort.
	@see SortController#insert
    **/
    public boolean insert(ExecRow row)
		throws StandardException
	{
		if (SanityManager.DEBUG)
		{
			// If the sort is null, probably the caller forgot
			// to call initialize.
			SanityManager.ASSERT(sort != null);
		}

		// Check that the inserted row is of the correct type
		sort.checkColumnTypes(row);

		// Insert the row into the sort buffer, which will
		// sort it into the right order with the rest of the
		// rows and remove any duplicates.
        int insertResult = sortBuffer.insert(row);
        stat_numRowsInput++;
        if (insertResult != SortBuffer.INSERT_DUPLICATE)
            stat_numRowsOutput++;
        if (insertResult == SortBuffer.INSERT_FULL)
		{
// GemStone changes BEGIN
      if (SanityManager.ASSERT) {
        final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
            .getInstance();
        if (observer != null) {
            avoidMergeRun = observer.avoidMergeRuns();
        }
      }

      if (this.thresholdListener.isEviction()
          || this.thresholdListener.isCritical()) {
        avoidMergeRun = false;
      }

      avoidBufferGrowth:
      if (avoidMergeRun) {

        Runtime jvm = Runtime.getRuntime();
        final long currentFreeMemory = jvm.freeMemory();
        final long currentTotalMemory = jvm.totalMemory();
        estimatedMemoryUsed = (currentTotalMemory - currentFreeMemory)
            - (beginTotalMemory - beginFreeMemory);

        if (SanityManager.DEBUG) {
            if (GemFireXDUtils.TraceSortTuning) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_SORT_TUNING,
                "Growing sortBuffer dynamically, "
                    + "current sortBuffer capacity= " + sortBuffer.capacity()
                    + " estimatedMemoryUsed = " + estimatedMemoryUsed
                    + " currentTotalMemory = " + currentTotalMemory
                    + " currentFreeMemory = " + currentFreeMemory
                    + " numcolumn = " + row.nColumns()
                    + " real per row memory = "
                    + (estimatedMemoryUsed / sortBuffer.capacity()));

          }
        }

        if (this.thresholdListener.isEvictionDisabled()) {
          // read the comments below from original code. 
          if (estimatedMemoryUsed > 0
              && !((2 * estimatedMemoryUsed) < (estimatedMemoryUsed + currentFreeMemory) / 2)
              && !(2 * estimatedMemoryUsed < ExternalSortFactory.DEFAULT_MEM_USE
                  && currentTotalMemory < (5 * 1024 * 1024))) {
            avoidMergeRun = false; // once we did it, too late to do in
            // memory sort
            break avoidBufferGrowth;
          }
        }
        // ok, double the sort buffer size
        boolean tryInsert = false;
        if(sortBuffer.capacity() < (Integer.MAX_VALUE/2 - 1)) {
          tryInsert = true;
          sortBuffer.grow(100);
        }

	if (tryInsert && (sortBuffer.insert(row) != SortBuffer.INSERT_FULL))
          //GemStone changes BEGIN
          //return;
          return true;
          //GemStone changes END
        avoidMergeRun = false; 
      }
                      /* (original code)
// GemStone changes END
			if (avoidMergeRun)
			{
				Runtime jvm = Runtime.getRuntime();
				if (SanityManager.DEBUG)
                {
                    if (SanityManager.DEBUG_ON("SortTuning"))
                    {
                        jvm.gc();
                        jvm.gc();
                        jvm.gc();
                    }
                }

                long currentFreeMemory = jvm.freeMemory();
                long currentTotalMemory = jvm.totalMemory();

				// before we create an external sort, which is expensive, see if
                // we can use up more in-memory sort buffer
				// we see how much memory has been used between now and the
				// beginning of the sort.  Not all of this memory is used by
				// the sort and GC may have kicked in and release some memory.
				// But it is a rough guess.
        		estimatedMemoryUsed = (currentTotalMemory-currentFreeMemory) -
		   			(beginTotalMemory-beginFreeMemory);

 				if (SanityManager.DEBUG)
                {
                    if (SanityManager.DEBUG_ON("SortTuning"))
                    {
						SanityManager.DEBUG("SortTuning",
							"Growing sortBuffer dynamically, " +
							"current sortBuffer capacity= " + 
                                sortBuffer.capacity() +
							" estimatedMemoryUsed = " + estimatedMemoryUsed +
							" currentTotalMemory = " + currentTotalMemory +
							" currentFreeMemory = " + currentFreeMemory +
							" numcolumn = " + row.nColumns() +
							" real per row memory = " + 
                                (estimatedMemoryUsed / sortBuffer.capacity()));
                    }
                }

				// we want to double the sort buffer size if that will result
				// in the sort to use up no more than 1/2 of all the free
				// memory (including the sort memory)
				// or if GC is so effective we are now using less memory than before
				// or if we are using less than 1Meg of memory and the jvm is
				// using < 5 meg of memory (this indicates that the JVM can
				// afford to be more bloated ?)
				if (estimatedMemoryUsed < 0 ||
					((2*estimatedMemoryUsed) < (estimatedMemoryUsed+currentFreeMemory)/2) ||
					(2*estimatedMemoryUsed < ExternalSortFactory.DEFAULT_MEM_USE &&
					 currentTotalMemory < (5*1024*1024)))
				{
					// ok, double the sort buffer size
					sortBuffer.grow(100);

					if (sortBuffer.insert(row) != SortBuffer.INSERT_FULL)
						return;
				}

				avoidMergeRun = false; // once we did it, too late to do in
									   // memory sort
			}

                        //GemStone changes BEGIN
                        //[soubhik] removing guard for 41586, 41800, 41801
			// @see #40348
			//if (mergeRuns == null) { // just to avoid below code to be flagged unreachable.
                                                 // We might got for external sort later.
                        //  throw StandardException
                        //      .newException(SQLState.OVERFLOW_TO_DISK_UNSUPPORTED);
                        //}
                        */
			//GemStone changes END
			
			// The sort buffer became full.  Empty it into a
			// merge run, and add the merge run to the vector
			// of merge runs.
            stat_sortType = "external";
			long conglomid = sort.createMergeRun(tran, sortBuffer);
			if (mergeRuns == null)
				mergeRuns = new TLongArrayList();
			mergeRuns.add(conglomid);

            stat_numMergeRuns++;
            // calculate size of this merge run
            // buffer was too full for last row
            runSize = stat_numRowsInput - totalRunSize - 1;
            totalRunSize += runSize;
// GemStone changes BEGIN
            // changed to use Integer.valueOf()
            stat_mergeRunsSize.add(runSize);
            /* (original code)
            stat_mergeRunsSize.addElement(new Integer(runSize));
            */
// GemStone changes END

			// Re-insert the row into the sort buffer.
			// This is guaranteed to work since the sort
			// buffer has just been emptied.
			sortBuffer.insert(row);
			//GemStone changes BEGIN
			return true;
			//GemStone changes END
		}
    //GemStone changes BEGIN
    else {
      return insertResult != SortBuffer.INSERT_DUPLICATE;
          
    }
        //GemStone changes END
	}

	/**
     * Called when the caller has completed
     * inserting rows into the sorter.

	@see SortController#completedInserts
	**/

	public void completedInserts()
	{
		// Tell the sort that we're closed, and hand off
		// the sort buffer and the vector of merge runs.
		if (sort != null)
			sort.doneInserting(this, sortBuffer, mergeRuns);

        // if this is an external sort, there will actually
        // be one last merge run with the contents of the
        // current sortBuffer. It will be created when the user
        // reads the result of the sort using openSortScan
        if (stat_sortType == "external")
        {
            stat_numMergeRuns++;
            stat_mergeRunsSize.add(stat_numRowsInput - totalRunSize);
        }

        // close the SortController in the transaction.
        tran.closeMe(this);

		// Clean up.
		sort = null;
		tran = null;
		mergeRuns = null;
		sortBuffer = null;
	}

	/*
	 * Methods of MergeInserter.  Arranged alphabetically.
	 */

    @Override
    public long estimateMemoryUsage(ExecRow sortResultRow)
        throws StandardException {
      // using what RealResultSetStatisticsFactory originally used
      return (stat_numRowsOutput * (sortResultRow != null ? sortResultRow
          .estimateRowSize() : 1));
    }

    /**
     * Return SortInfo object which contains information about the current
     * sort.
     * <p>
     *
     * @see SortInfo
     *
	 * @return The SortInfo object which contains info about current sort.
     *
	 * @exception  StandardException  Standard exception policy.
     **/
    public SortInfo getSortInfo()
		throws StandardException
    {
        return(new MergeSortInfo(this));
    }


	/**
	Initialize this inserter.
	@return true if initialization was successful
	**/
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "DM_GC")
	boolean initialize(MergeSort sort, TransactionManager tran)
	{
		Runtime jvm = Runtime.getRuntime();
		if (SanityManager.DEBUG)
        {
            //GemStone changes BEGIN
            if (GemFireXDUtils.TraceSortTuning)
            // if (SanityManager.DEBUG_ON("SortTuning"))
            //GemStone changes END
            {
                jvm.gc();
                jvm.gc();
                jvm.gc();
            }
        }

		beginFreeMemory = jvm.freeMemory();
		beginTotalMemory = jvm.totalMemory();
		estimatedMemoryUsed = 0;
		avoidMergeRun = true;		// not an external sort
        stat_sortType = "internal";
        stat_numMergeRuns = 0;
        stat_numRowsInput = 0;
        stat_numRowsOutput = 0;
        stat_mergeRunsSize = new TIntArrayList();
        runSize = 0;
        totalRunSize = 0;


		if (SanityManager.DEBUG)
        {
            if (SanityManager.DEBUG_ON("testSort"))
            {
                avoidMergeRun = false;
            }
        }

		this.sort = sort;
		this.tran = tran;
		sortBuffer = new SortBuffer(sort);
		if (sortBuffer.init() == false)
			return false;
		return true;
	}

}
