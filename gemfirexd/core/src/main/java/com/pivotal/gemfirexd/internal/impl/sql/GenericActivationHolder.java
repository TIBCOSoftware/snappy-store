/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.GenericActivationHolder

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

package com.pivotal.gemfirexd.internal.impl.sql;

// GemStone changes BEGIN
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.procedure.cohort.ProcedureSender;
import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappyActivation;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
// GemStone changes END

import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultDescription;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ConstantAction;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.CursorResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TemporaryRowHolder;
import com.pivotal.gemfirexd.internal.iapi.store.access.ConglomerateController;
import com.pivotal.gemfirexd.internal.iapi.store.access.ScanController;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.BaseActivation;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Vector;
import java.util.Hashtable;

/**
 * This class holds an Activation, and passes through most of the calls
 * to the activation.  The purpose of this class is to allow a PreparedStatement
 * to be recompiled without the caller having to detect this and get a new
 * activation.
 *
 * In addition to the Activation, this class holds a reference to the
 * PreparedStatement that created it, along with a reference to the
 * GeneratedClass that was associated with the PreparedStatement at the time
 * this holder was created.  These references are used to validate the
 * Activation, to ensure that an activation is used only with the
 * PreparedStatement that created it, and to detect when recompilation has
 * happened.
 *
 * We detect recompilation by checking whether the GeneratedClass has changed.
 * If it has, we try to let the caller continue to use this ActivationHolder.
 * We create a new instance of the new GeneratedClass (that is, we create a
 * new Activation), and we compare the number and type of parameters.  If these
 * are compatible, we copy the parameters from the old to the new Activation.
 * If they are not compatible, we throw an exception telling the user that
 * the Activation is out of date, and they need to get a new one.
 *
 */
// Gemstone changes BEGIN
public final class GenericActivationHolder implements Activation
//Gemstone changes END
{
	BaseActivation			ac;
	ExecPreparedStatement	ps;
	GeneratedClass			gc;
	DataTypeDescriptor[]	paramTypes;
	private final LanguageConnectionContext lcc;
// GemStone changes BEGIN
	GenericActivationHolder(LanguageConnectionContext lcc, GeneratedClass gc,
	    ExecPreparedStatement ps, boolean scrollable, String statementText)
            throws StandardException {
	  this(lcc, gc, ps, scrollable, statementText, true);
	}
// GemStone changes END
	/**
	 * Constructor for an ActivationHolder
	 * @param gc	The GeneratedClass of the Activation
	 * @param ps	The PreparedStatement this ActivationHolder is associated
	 *				with
	 * @param statementText TODO
	 *
	 * @exception StandardException		Thrown on error
	 */
	GenericActivationHolder(LanguageConnectionContext lcc, GeneratedClass gc, ExecPreparedStatement ps,
	    boolean scrollable, String statementText, boolean addToLCC /* GemStoneAddition */)
			throws StandardException
	{
		this.lcc = lcc;
		if (SanityManager.DEBUG)
		{
// GemStone changes BEGIN
		  if (gc == null)
// GemStone changes END
			SanityManager.ASSERT(gc != null, "generated class is null , ps is a " + ps.getClass());
		}

		this.gc = gc;
		this.ps = ps;
		ac = (BaseActivation)gc.newInstance(lcc, addToLCC, ps);
		StandardException rePrepareEx = null;
		for (;;) {
		  if (rePrepareEx != null) {
		    // need to release DD and other locks before re-prepare
		    StatementContext ctx = lcc.getStatementContext();
		    if (ctx != null) {
		      ctx.cleanupOnError(rePrepareEx);
		    }
		    lcc.internalCleanup();
		    ps.rePrepare(lcc, ac);
		    rePrepareEx = null;
		  }
		  try {
		    ac.setupActivation(ps, scrollable, statementText);
		    break;
		  } catch (StandardException se) {
		    if (!SQLState.LANG_STATEMENT_NEEDS_RECOMPILE.equals(
		        se.getMessageId())) {
		      throw se;
		    }
		    // mark for re-prepare and continue in the loop
		    rePrepareEx = se;
		  }
		}
		paramTypes = ps.getParameterTypes();
	}        

        public String getObjectName() {
          return this.ac.getObjectName();
        }

        public void setObjectName(String name) {
          this.ac.setObjectName(name);
        }

        public void addNullEliminatedWarning() {
          this.ac.addNullEliminatedWarning();
        }

        public String getCurrentColumnName() {
          return this.ac.getCurrentColumnName();
        }

        public void setCurrentColumnName(String name) {
          this.ac.setCurrentColumnName(name);
        }
// GemStone changes END
	/* Activation interface */

	/**
	 * @see Activation#reset
	 *
	 * @exception StandardException thrown on failure
	 */
	public void	reset(boolean cleanupOnError) throws StandardException
	{
		ac.reset(false);
	}

	/**
	 * Temporary tables can be declared with ON COMMIT DELETE ROWS. But if the table has a held curosr open at
	 * commit time, data should not be deleted from the table. This method, (gets called at commit time) checks if this
	 * activation held cursor and if so, does that cursor reference the passed temp table name.
	 *
	 * @return	true if this activation has held cursor and if it references the passed temp table name
	 */
	public boolean checkIfThisActivationHasHoldCursor(String tableName)
	{
		return ac.checkIfThisActivationHasHoldCursor(tableName);
	}

	/**
	 * @see Activation#setCursorName
	 *
	 */
	public void	setCursorName(String cursorName)
	{
		ac.setCursorName(cursorName);
	}

	/**
	 * @see Activation#getCursorName
	 */
	public String	getCursorName()
	{
		return ac.getCursorName();
	}

	/**
	 * @see Activation#setResultSetHoldability
	 *
	 */
	public void	setResultSetHoldability(boolean resultSetHoldability)
	{
		ac.setResultSetHoldability(resultSetHoldability);
	}

	/**
	 * @see Activation#getResultSetHoldability
	 */
	public boolean	getResultSetHoldability()
	{
		return ac.getResultSetHoldability();
	}

	/** @see Activation#setAutoGeneratedKeysResultsetInfo */
	public void setAutoGeneratedKeysResultsetInfo(int[] columnIndexes, String[] columnNames)
	{
		ac.setAutoGeneratedKeysResultsetInfo(columnIndexes, columnNames);
	}

	/** @see Activation#getAutoGeneratedKeysResultsetMode */
	public boolean getAutoGeneratedKeysResultsetMode()
	{
		return ac.getAutoGeneratedKeysResultsetMode();
	}

	/** @see Activation#getAutoGeneratedKeysColumnIndexes */
	public int[] getAutoGeneratedKeysColumnIndexes()
	{
		return ac.getAutoGeneratedKeysColumnIndexes();
	}

	/** @see Activation#getAutoGeneratedKeysColumnNames */
	public String[] getAutoGeneratedKeysColumnNames()
	{
		return ac.getAutoGeneratedKeysColumnNames();
	}

	/** @see com.pivotal.gemfirexd.internal.iapi.sql.Activation#getLanguageConnectionContext */
	public	LanguageConnectionContext	getLanguageConnectionContext()
	{
		return	lcc;
	}

	public TransactionController getTransactionController()
	{
		return ac.getTransactionController();
	}

	/** @see Activation#getExecutionFactory */
	public	ExecutionFactory	getExecutionFactory()
	{
		return	ac.getExecutionFactory();
	}

	/**
	 * @see Activation#getParameterValueSet
	 */
	public ParameterValueSet	getParameterValueSet()
	{
		return ac.getParameterValueSet();
	}

	/**
	 * @see Activation#setParameters
	 */
	public void	setParameters(ParameterValueSet parameterValues, DataTypeDescriptor[] parameterTypes) throws StandardException
	{
		ac.setParameters(parameterValues, parameterTypes);
	}

	/** 
	 * @see Activation#execute
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public ResultSet execute() throws StandardException
	{
		/*
		** Synchronize to avoid problems if another thread is preparing
		** the statement at the same time we're trying to execute it.
		*/
		// synchronized (ps)
		{
			/* Has the activation class changed? */
			if (gc != ps.getActivationClass())
			{

                GeneratedClass newGC;

				// ensure the statement is valid by rePreparing it.
                // DERBY-3260: If someone else reprepares the statement at the
                // same time as we do, there's a window between the calls to
                // rePrepare() and getActivationClass() when the activation
                // class can be set to null, leading to NullPointerException
                // being thrown later. Therefore, synchronize on ps to close
                // the window.
// GemStone changes BEGIN
        // Acquire the DD read lock first since otherwise the locking order
        // will be PreparedStatement->DD-read, while a DDL invalidating the
        // same PreparedStatement will have DD-write->PreparedStatement (in
        //   GenericPreparedStatement#makeInvalid) -- see bug #40926).
        // However, make an upToDate check first to avoid some unnecessary
        // DD read locking.
        newGC = null;
        if (this.ps.upToDate()) {
          newGC = this.ps.getActivationClass();
        }
        if (newGC == null) {
          DataDictionary dd = this.lcc.getDataDictionary();
          int readMode = dd.startReading(this.lcc);
          try {
            synchronized (this.ps) {
              this.ps.rePrepare(lcc, this.getActivation());
              newGC = this.ps.getActivationClass();
            }
          } finally {
            dd.doneReading(readMode, this.lcc);
          }
        }
        /* (original Derby code)
                synchronized (ps) {
                    ps.rePrepare(getLanguageConnectionContext());
                    newGC = ps.getActivationClass();
                }
        */
// GemStone changes END

				/*
				** If we get here, it means the PreparedStatement has been
				** recompiled.  Get a new Activation and check whether the
				** parameters are compatible.  If so, transfer the parameters
				** from the old Activation to the new one, and make that the
				** current Activation.  If not, throw an exception.
				*/
// GemStone changes BEGIN 
				//BaseActivation		newAC = (BaseActivation) newGC.newInstance(lcc);
				BaseActivation newAC = (BaseActivation)newGC
				    .newInstance(lcc, ac.addToLCC, ps);
				newAC.setFunctionContext(this.ac.getFunctionContext());
				newAC.setProcedureSender(this.ac.getProcedureSender());
				newAC.setUpdatedColumns(this.ac.getUpdatedColumns());
				newAC.setProjectMapping(this.ac.getProjectMapping());
				this.ac.copyFlags(newAC);
				newAC.setHasQueryHDFS(this.ac.getHasQueryHDFS());
				newAC.setQueryHDFS(this.ac.getQueryHDFS());
// GemStone changes END
				DataTypeDescriptor[]	newParamTypes = ps.getParameterTypes();
                                 newAC.setConnectionID(this.ac.getConnectionID());
                                 newAC.setStatementID(this.ac.getStatementID());
                                 newAC.setExecutionID(this.ac.getExecutionID());
                                 newAC.setRootID(this.ac.getRootID());
                                 newAC.setStatementLevel(this.ac.getStatementLevel());
                                 newAC.setIsPrepStmntQuery(this.ac.getIsPrepStmntQuery());
				/*
				** Link the new activation to the prepared statement.
				*/
				newAC.setupActivation(ps, ac.getScrollable(), ac.getStatementText());

				// GemStone changes BEGIN
                                final ParameterValueSet oldPvs = ac.getParameterValueSet();
                                final ParameterValueSet newPvs = newAC.getParameterValueSet();
                                if (SanityManager.ASSERT) {
                                  if (oldPvs == GenericLanguageFactory.emptySet && newPvs != null
                                      && newPvs.isListOfConstants()) {
                                    SanityManager
                                        .THROWASSERT("About to loose valid ConstantValueSet. newAC.pvs "
                                            + GemFireXDUtils.addressOf(newAC)
                                            + "."
                                            + newPvs
                                            + " is about to set with ac.pvs "
                                            + GemFireXDUtils.addressOf(ac) + "." + oldPvs);
                                  }
                                }
                                newAC.setParameters(oldPvs, paramTypes);
				/*(original code)newAC.setParameters(ac.getParameterValueSet(), paramTypes);*/
				// GemStone changes END


				/*
				** IMPORTANT
				**
				** Copy any essential state from the old activation
				** to the new activation. This must match the state
				** setup in EmbedStatement.
				** singleExecution, cursorName, holdability, maxRows.
				*/

				if (ac.isSingleExecution())
					newAC.setSingleExecution();

				newAC.setCursorName(ac.getCursorName());

				newAC.setResultSetHoldability(ac.getResultSetHoldability());
				if (ac.getAutoGeneratedKeysResultsetMode()) //Need to do copy only if auto generated mode is on
					newAC.setAutoGeneratedKeysResultsetInfo(ac.getAutoGeneratedKeysColumnIndexes(),
					ac.getAutoGeneratedKeysColumnNames());
				newAC.setMaxRows(ac.getMaxRows());

				// break the link with the prepared statement
				ac.setupActivation(null, false, null);
				ac.close();

				/* Remember the new class information */
				ac = newAC;
				gc = newGC;
				paramTypes = newParamTypes;
			}
		}

		String cursorName = ac.getCursorName();
		if (cursorName != null)
		{
			// have to see if another activation is open
			// with the same cursor name. If so we can't use this name

			Activation activeCursor = lcc.lookupCursorActivation(cursorName);

			if ((activeCursor != null) && (activeCursor != ac)) {
				throw StandardException.newException(SQLState.LANG_CURSOR_ALREADY_EXISTS, cursorName);
			}
		}

		return ac.execute();
	}

	/**
	 * @see Activation#getResultSet
	 *
	 * @return the current ResultSet of this activation.
	 */
	public ResultSet getResultSet()
	{
		return ac.getResultSet();
	}

	/**
	 * @see Activation#setCurrentRow
	 *
	 */
	public void setCurrentRow(ExecRow currentRow, int resultSetNumber) 
	{
		ac.setCurrentRow(currentRow, resultSetNumber);
	}

	/**
	 * @see Activation#clearCurrentRow
	 */
	public void clearCurrentRow(int resultSetNumber) 
	{
		ac.clearCurrentRow(resultSetNumber);
	}

	/**
	 * @see Activation#getPreparedStatement
	 */
	public ExecPreparedStatement getPreparedStatement()
	{
		return ps;
	}

	public void checkStatementValidity() throws StandardException {
		ac.checkStatementValidity();
	}

	/**
	 * @see Activation#getResultDescription
	 */
	public ResultDescription getResultDescription()
	{
		return ac.getResultDescription();
	}

	/**
	 * @see Activation#getDataValueFactory
	 */
	public DataValueFactory getDataValueFactory()
	{
		return ac.getDataValueFactory();
	}

	/**
	 * @see Activation#getRowLocationTemplate
	 */
	public RowLocation getRowLocationTemplate(int itemNumber)
	{
		return ac.getRowLocationTemplate(itemNumber);
	}

	/**
	 * @see Activation#getHeapConglomerateController
	 */
	public ConglomerateController getHeapConglomerateController()
	{
		return ac.getHeapConglomerateController();
	}

	/**
	 * @see Activation#setHeapConglomerateController
	 */
	public void setHeapConglomerateController(ConglomerateController updateHeapCC)
	{
		ac.setHeapConglomerateController(updateHeapCC);
	}

	/**
	 * @see Activation#clearHeapConglomerateController
	 */
	public void clearHeapConglomerateController()
	{
		ac.clearHeapConglomerateController();
	}

	/**
	 * @see Activation#getIndexScanController
	 */
	public ScanController getIndexScanController()
	{
		return ac.getIndexScanController();
	}

	/**
	 * @see Activation#setIndexScanController
	 */
	public void setIndexScanController(ScanController indexSC)
	{
		ac.setIndexScanController(indexSC);
	}

	/**
	 * @see Activation#getIndexConglomerateNumber
	 */
	public long getIndexConglomerateNumber()
	{
		return ac.getIndexConglomerateNumber();
	}

	/**
	 * @see Activation#setIndexConglomerateNumber
	 */
	public void setIndexConglomerateNumber(long indexConglomerateNumber)
	{
		ac.setIndexConglomerateNumber(indexConglomerateNumber);
	}

	/**
	 * @see Activation#clearIndexScanInfo
	 */
	public void clearIndexScanInfo()
	{
		ac.clearIndexScanInfo();
	}

	/**
	 * @see Activation#close
	 *
	 * @exception StandardException		Thrown on error
	 */
	public void close() throws StandardException
	{
		ac.close();
	}

	/**
	 * @see Activation#isClosed
	 */
	public boolean isClosed()
	{
		return ac.isClosed();
	}

	/**
		Set the activation for a single execution.

		@see Activation#setSingleExecution
	*/
	public void setSingleExecution() {
		ac.setSingleExecution();
	}

	/**
		Is the activation set up for a single execution.

		@see Activation#isSingleExecution
	*/
	public boolean isSingleExecution() {
		return ac.isSingleExecution();
	}

	/**
		Get the number of subqueries in the entire query.
		@return int	 The number of subqueries in the entire query.
	 */
	public int getNumSubqueries() {
		return ac.getNumSubqueries();
	}

	/**
	 * @see Activation#setForCreateTable()
	 */
	public void setForCreateTable()
	{
		ac.setForCreateTable();
	}

	/**
	 * @see Activation#getForCreateTable()
	 */
	public boolean getForCreateTable()
	{
		return ac.getForCreateTable();
	}

	/**
	 * @see Activation#setDDLTableDescriptor
	 */
	public void setDDLTableDescriptor(TableDescriptor td)
	{
		ac.setDDLTableDescriptor(td);
	}

	/**
	 * @see Activation#getDDLTableDescriptor
	 */
	public TableDescriptor getDDLTableDescriptor()
	{
		return ac.getDDLTableDescriptor();
	}

	/**
	 * @see Activation#setMaxRows
	 */
	public void setMaxRows(int maxRows)
	{
		ac.setMaxRows(maxRows);
	}

	/**
	 * @see Activation#getMaxRows
	 */
	public int getMaxRows()
	{
		return ac.getMaxRows();
	}

	public void setTargetVTI(java.sql.ResultSet targetVTI)
	{
		ac.setTargetVTI(targetVTI);
	}

	public java.sql.ResultSet getTargetVTI()
	{
		return ac.getTargetVTI();
	}

	public void setNestedCurrentRole(String role) {
		ac.setNestedCurrentRole(role);
    }

    public String getNestedCurrentRole() {
		return ac.getNestedCurrentRole();
    }

	public void setCallActivation(Activation a) {
		ac.setCallActivation(a);
	}

	public Activation getCallActivation() {
		return ac.getCallActivation();
	}


	/* Class implementation */


	/**
	 * Mark the activation as unused.  
	 */
	public void markUnused()
	{
		ac.markUnused();
	}

	/**
	 * Is the activation in use?
	 *
	 * @return true/false
	 */
	public boolean isInUse()
	{
		return ac.isInUse();
	}
	/**
	  @see com.pivotal.gemfirexd.internal.iapi.sql.Activation#addWarning
	  */
	public void addWarning(SQLWarning w)
	{
		ac.addWarning(w);
	}
	
	public void addResultsetWarning(SQLWarning w)
        {
                ac.addResultsetWarning(w);
        }

	/**
	  @see com.pivotal.gemfirexd.internal.iapi.sql.Activation#getWarnings
	  */
	public SQLWarning getWarnings()
	{
		return ac.getWarnings();
	}
	
	public SQLWarning getResultsetWarnings()
        {
                return ac.getResultsetWarnings();
        }
	/**
	  @see com.pivotal.gemfirexd.internal.iapi.sql.Activation#clearWarnings
	  */
	public void clearWarnings()
	{
		ac.clearWarnings();
	}

	public void clearResultsetWarnings()
        {
                ac.clearResultsetWarnings();
        }
	/**
		@see Activation#informOfRowCount
		@exception StandardException	Thrown on error
	 */
	public void informOfRowCount(NoPutResultSet resultSet, long rowCount)
					throws StandardException
	{
		ac.informOfRowCount(resultSet, rowCount);
	}

	/**
	 * @see Activation#isCursorActivation
	 */
	public boolean isCursorActivation()
	{
		return ac.isCursorActivation();
	}

	public ConstantAction getConstantAction() {
		return ac.getConstantAction();
	}

	public void setParentResultSet(TemporaryRowHolder rs, String resultSetId)
	{
		ac.setParentResultSet(rs, resultSetId);
	}


	public Vector getParentResultSet(String resultSetId)
	{
		return ac.getParentResultSet(resultSetId);
	}

	public void clearParentResultSets()
	{
		ac.clearParentResultSets();
	}

	public Hashtable getParentResultSets()
	{
		return ac.getParentResultSets();
	}

	public void setForUpdateIndexScan(CursorResultSet forUpdateResultSet)
	{
		ac.setForUpdateIndexScan(forUpdateResultSet);
	}

	public CursorResultSet getForUpdateIndexScan()
	{
		return ac.getForUpdateIndexScan();
	}

	public java.sql.ResultSet[][] getDynamicResults() {
		return ac.getDynamicResults();
	}
	public int getMaxDynamicResults() {
		return ac.getMaxDynamicResults();
	}

// GemStone changes BEGIN

  /*public void setUrlString(String urlString) {
  //NO OP
  }*/

  final public void setConnectionID(long id) {
    this.ac.setConnectionID(id);
  }

  final public ContextManager getContextManager() {
    return this.ac.getContextManager();
  }

  final public void setStatementID(long id) {
    this.ac.setStatementID(id);
  }
  
  final public void setExecutionID(int id) {
    this.ac.setExecutionID(id);
  }
  
  final public void setRootID(long id) {
    this.ac.setRootID(id);
  }
  
  final public void setStatementLevel(int val) {
    this.ac.setStatementLevel(val);
  }

  final public void distributeClose() {
    this.ac.distributeClose();
  }

  public BaseActivation getActivation() {
    return this.ac;
  }

  public long getConnectionID() {
    throw new IllegalStateException(
        "GenericActivationHolder::getConnectionID:should not have been invoked");
  }

  public long getStatementID() {
    throw new IllegalStateException(
        "GenericActivationHolder::getStatementID:should not have been invoked");
  }
  
  public long getRootID() {
    throw new IllegalStateException(
        "GenericActivationHolder::getStatementID:should not have been invoked");
  }
  
  public int getStatementLevel() {
    throw new IllegalStateException(
        "GenericActivationHolder::getStatementID:should not have been invoked");
  }

  final public int getExecutionID() {
    return this.ac.getExecutionID();
  }
  
  public boolean getIsPrepStmntQuery() {
    throw new IllegalStateException(
        "GenericActivationHolder::getIsPrepStmntQuery:should not have been invoked");
  }

  final public void setIsPrepStmntQuery(boolean flag) {
    this.ac.setIsPrepStmntQuery(flag);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final FunctionContext getFunctionContext() {
    return this.ac.getFunctionContext();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setFunctionContext(final FunctionContext fc) {
    this.ac.setFunctionContext(fc);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setFlags(int flags) {
    this.ac.setFlags(flags);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean isSpecialCaseOuterJoin() {
    return this.ac.isSpecialCaseOuterJoin();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setSpecialCaseOuterJoin(boolean flag) {
    this.ac.setSpecialCaseOuterJoin(flag);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final boolean needKeysForSelectForUpdate() {
    return this.ac.needKeysForSelectForUpdate();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setNeedKeysForSelectForUpdate(boolean flag) {
    this.ac.setNeedKeysForSelectForUpdate(flag);
  }

  public final boolean isPreparedBatch() {
    return this.ac.isPreparedBatch();
  }

  public final void setPreparedBatch(boolean flag) {
    this.ac.setPreparedBatch(flag);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean getInsertAsSubselect() {
    return this.ac.getInsertAsSubselect();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setInsertAsSubselect(boolean flag) {
    this.ac.setInsertAsSubselect(flag);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ProcedureSender getProcedureSender() {
    return this.ac.getProcedureSender();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setProcedureSender(ProcedureSender sender) {
    this.ac.setProcedureSender(sender);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isPossibleDuplicate() {
    return this.ac.isPossibleDuplicate();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPossibleDuplicate(boolean flag) {
    this.ac.setPossibleDuplicate(flag);
  }

  public void cancelOnLowMemory() {
    this.ac.cancelOnLowMemory();
  }

  public long estimateMemoryUsage() throws StandardException {
    return this.ac.estimateMemoryUsage();
  }

  public final boolean isQueryCancelled() {
    return this.ac.isQueryCancelled();
  }
  

  public void distributeBulkOpToDBSynchronizer(LocalRegion rgn,
      boolean isDynamic, GemFireTransaction tran, boolean skipListeners,
      ArrayList<Object> bulkInsertRows) throws StandardException {
    this.ac.distributeBulkOpToDBSynchronizer(rgn, isDynamic, tran,
        skipListeners, bulkInsertRows);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(super.toString());
    sb.append(";Underlying Real activation object = " + this.ac);
    return sb.toString();
  }
  
  @Override
  public ExecRow getCurrentRow(int resultSetNumber) {
    return this.ac.getCurrentRow(resultSetNumber);
  }
  
  @Override
  public void setUseOnlyPrimaryBuckets(boolean b) {
    this.ac.setUseOnlyPrimaryBuckets(b);
  }

	public void setConnProps(Properties p) {
		if (this.ac instanceof SnappyActivation) {
			((SnappyActivation)this.ac).setConnProps(p);
		}
	}

	@Override
  public boolean getUseOnlyPrimaryBuckets() {
    return this.ac.getUseOnlyPrimaryBuckets();
  }
  
  @Override
  public void setQueryHDFS(boolean val) {
    this.ac.setQueryHDFS(val);
  }
  
  @Override
  public boolean getQueryHDFS() {
    return this.ac.getQueryHDFS();
  }  
  
  @Override
  public void setHasQueryHDFS(boolean val) {
    this.ac.setHasQueryHDFS(val);
  }
  
  @Override
  public boolean getHasQueryHDFS() {
    return this.ac.getHasQueryHDFS();
  }  
  
  @Override
  public final boolean isPutDML() {
    return this.ac.isPutDML();
  }
  
  @Override
  public void cancelOnTimeOut() {
    this.ac.cancelOnTimeOut();
  }
  
  @Override
  public void cancelOnUserRequest() {
    this.ac.cancelOnUserRequest();
  }
  
  @Override
  public void setTimeOutMillis(long timeOutMillis) {
    this.ac.setTimeOutMillis(timeOutMillis);
  }
  
  @Override
  public long getTimeOutMillis() {
    return this.ac.getTimeOutMillis();
  }

  @Override
  public void checkCancellationFlag() throws StandardException {
    this.ac.checkCancellationFlag();
  }
  
  /**
   *  Get the specified saved object.
   *
   *  @param  objectNum The object to get.
   *  @return the requested saved object.
   */
  public final Object getSavedObject(int objectNum) {
    return this.ac.getSavedObject(objectNum);
  }
//GemStone changes END
  @Override
  public Object[] getSavedObjects() {    
    return ac.getSavedObjects();
  }
}
