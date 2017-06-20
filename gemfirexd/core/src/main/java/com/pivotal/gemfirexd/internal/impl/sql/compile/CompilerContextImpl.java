/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.compile.CompilerContextImpl

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

package com.pivotal.gemfirexd.internal.impl.sql.compile;

import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.internal.catalog.UUID;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.JavaFactory;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextImpl;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.NodeFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Parser;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.TypeCompiler;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.TypeCompilerFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.StatementContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.Dependent;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.Provider;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.ProviderList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.AliasDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatementColumnPermission;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatementRolePermission;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatementRoutinePermission;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatementSchemaPermission;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.StatementTablePermission;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortCostController;
import com.pivotal.gemfirexd.internal.iapi.store.access.StoreCostController;
import com.pivotal.gemfirexd.internal.iapi.types.DataTypeDescriptor;
import com.pivotal.gemfirexd.internal.iapi.util.ReuseFactory;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.rules.ExecutionEngineRule.ExecutionEngine;
import java.sql.SQLWarning;
import java.util.Vector;
import java.util.Properties;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;
import java.util.ArrayList;

/**
 *
 * CompilerContextImpl, implementation of CompilerContext.
 * CompilerContext and hence CompilerContextImpl objects are private to a LanguageConnectionContext.
 *
 */
public final class CompilerContextImpl extends ContextImpl
	implements CompilerContext {

	//
	// Context interface       
	//

	/**
		@exception StandardException thrown by makeInvalid() call
	 */
	@Override
  public void cleanupOnError(Throwable error) throws StandardException {

		setInUse(false);
		resetContext();

		if (error instanceof StandardException) {

			StandardException se = (StandardException) error;
			// if something went wrong with the compile,
			// we need to mark the statement invalid.
			// REVISIT: do we want instead to remove it,
			// so the cache doesn't get full of garbage input
			// that won't even parse?
            
            int severity = se.getSeverity();

			if (severity < ExceptionSeverity.SYSTEM_SEVERITY) 
			{
				if (currentDependent != null)
				{
					currentDependent.makeInvalid(DependencyManager.COMPILE_FAILED,
												 lcc);
				}
				closeStoreCostControllers();
				closeSortCostControllers();
			}
			// anything system or worse, or non-DB errors,
			// will cause the whole system to shut down.
            
            if (severity >= ExceptionSeverity.SESSION_SEVERITY)
                popMe();
		}

	}

	/**
	  *	Reset compiler context (as for instance, when we recycle a context for
	  *	use by another compilation.
	  */
	@Override
  public	void	resetContext()
	{
		nextColumnNumber = 1;
		nextTableNumber = 0;
		nextSubqueryNumber = 0;
		resetNextResultSetNumber();
		nextEquivalenceClass = -1;
		compilationSchema = null;
		parameterList = null;
		parameterDescriptors = null;
		
		scanIsolationLevel = ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL;
		warnings = null;
		savedObjects = null;
		reliability = CompilerContext.SQL_LEGAL;
		returnParameterFlag = false;
		initRequiredPriv();
// GemStone changes Begin

		scope=true;
		cohort=false;
                createQueryInfo=true;
                optimizeForWrite = false;
                statementAlias = null;
                this.scopes.clear();
                this.currentQueryStatementOptimization = false;
                isPreparedStatement = false;
                this.allowStatementOptimization=false;
                if(constantTokenList != null) {
                   constantTokenList = new ArrayList<com.pivotal.gemfirexd.internal.engine.sql.compile.Token>();
                   
                }
                if (dynamicTokenList != null) {
                  dynamicTokenList = new ArrayList<com.pivotal.gemfirexd.internal.impl.sql.compile.Token>();
                }
                this.originalParamTypeCompilers = new ArrayList<TypeCompiler>(5);
                preparedStatement = null;
                generalizedQuery = null;
                allTablesReplicatedOnRemote = false;
                ncjMetaData = null;
                orderByListNullified = false;
                isOffsetOrFetchNext = false;
                allowSubqueryFlattening = true;
                foundNoncorrelatedsubquery = false;
                allowOrListOptimization = false;
                hasOrList = false;
                statementStats = null;
                hasQueryHDFS = false; 
                queryHDFS = false;
                originalExecFlags = 0;
                convertCharConstToVarchar = false;
                this.ddlForSnappyUse = false;
                this.snappyForcedDDLRouting = false;
                this.executionEngine = ExecutionEngine.NOT_DECIDED;
	}

	@Override
  public void resetNumTables() {
	  this.nextTableNumber = 0;
	  this.nextColumnNumber = 1;
	  this.nextSubqueryNumber = 0;
	}
// GemStone changes End

	//
	// CompilerContext interface
	//
	// we might want these to refuse to return
	// anything if they are in-use -- would require
	// the interface provide a 'done' call, and
	// we would mark them in-use whenever a get happened.
	@Override
  public Parser getParser() {
		return parser;
	}
	
        @Override
        public Parser getMatcher() {
          return matcher;
        }

	/**
	  *	Get the NodeFactory for this context
	  *
	  *	@return	The NodeFactory for this context.
	  */
	@Override
  public	NodeFactory	getNodeFactory()
	{	return lcf.getNodeFactory(); }


	@Override
  public int getNextColumnNumber()
	{
		return nextColumnNumber++;
	}

	@Override
  public int getNextTableNumber()
	{
		return nextTableNumber++;
	}

	@Override
  public int getNumTables()
	{
		return nextTableNumber;
	}

	/**
	 * Get the current next subquery number from this CompilerContext.
	 *
	 * @return int	The next subquery number for the current statement.
	 *
	 */

	@Override
  public int getNextSubqueryNumber()
	{
		return nextSubqueryNumber++;
	}

	/**
	 * Get the number of subquerys in the current statement from this CompilerContext.
	 *
	 * @return int	The number of subquerys in the current statement.
	 *
	 */

	@Override
  public int getNumSubquerys()
	{
		return nextSubqueryNumber;
	}

	@Override
  public int getNextResultSetNumber()
	{
		return nextResultSetNumber++;
	}

	@Override
  public void resetNextResultSetNumber()
	{
		nextResultSetNumber = 0;
	}

	@Override
  public int getNumResultSets()
	{
		return nextResultSetNumber;
	}

	@Override
  public String getUniqueClassName()
	{
		// REMIND: should get a new UUID if we roll over...
		if (SanityManager.DEBUG)
		{
    		SanityManager.ASSERT(nextClassName <= Long.MAX_VALUE);
    	}
		return classPrefix.concat(Long.toHexString(nextClassName++));
	}

	/**
	 * Get the next equivalence class for equijoin clauses.
	 *
	 * @return The next equivalence class for equijoin clauses.
	 */
	@Override
  public int getNextEquivalenceClass()
	{
		return ++nextEquivalenceClass;
	}

	@Override
  public ClassFactory getClassFactory()
	{
		return lcf.getClassFactory();
	}

	@Override
  public JavaFactory getJavaFactory()
	{
		return lcf.getJavaFactory();
	}

	@Override
  public void setCurrentDependent(Dependent d) {
		currentDependent = d;
	}

	// Gemstone changes BEGIN
	public Dependent getCurrentDependent() {
	  return this.currentDependent;
	}
	// Gemstone changes END
	/**
	 * Get the current auxiliary provider list from this CompilerContext.
	 *
	 * @return	The current AuxiliaryProviderList.
	 *
	 */

	@Override
  public ProviderList getCurrentAuxiliaryProviderList()
	{
		return currentAPL;
	}

	/**
	 * Set the current auxiliary provider list for this CompilerContext.
	 *
	 * @param apl	The new current AuxiliaryProviderList.
	 */

	@Override
  public void setCurrentAuxiliaryProviderList(ProviderList apl)
	{
		currentAPL = apl;
	}

	@Override
  public void createDependency(Provider p) throws StandardException {
		if (SanityManager.DEBUG)
		SanityManager.ASSERT(currentDependent != null,
				"no current dependent for compilation");

		if (dm == null)
			dm = lcc.getDataDictionary().getDependencyManager();
		dm.addDependency(currentDependent, p, getContextManager());
		addProviderToAuxiliaryList(p);
	}

	/**
	 * Add a dependency between two objects.
	 *
	 * @param d	The Dependent object.
	 * @param p	The Provider of the dependency.
	 * @exception StandardException thrown on failure.
	 *
	 */
	@Override
  public	void createDependency(Dependent d, Provider p) throws StandardException
	{
		if (dm == null)
			dm = lcc.getDataDictionary().getDependencyManager();

		dm.addDependency(d, p, getContextManager());
		addProviderToAuxiliaryList(p);
	}

	/**
	 * Add a Provider to the current AuxiliaryProviderList, if one exists.
	 *
	 * @param p		The Provider to add.
	 */
	private void addProviderToAuxiliaryList(Provider p)
	{
		if (currentAPL != null)
		{
			currentAPL.addProvider(p);
		}
	}

	@Override
  public  int addSavedObject(Object obj) {	  
		if (savedObjects == null) savedObjects = new Vector();
    savedObjects.addElement(obj);
		return  savedObjects.size() - 1;		
	}

	@Override
  public  Object[] getSavedObjects() {
		if (savedObjects == null) return null;
		Object[] retVal = new Object[savedObjects.size()];
		savedObjects.copyInto(retVal);	
		savedObjects = null; // erase to start over
		return retVal;
	}

	/** @see CompilerContext#setSavedObjects */
	@Override
  public void setSavedObjects(Object[] objs) 
	{
		if (objs == null)
		{
			return;
		}

		for (int i = 0; i < objs.length; i++)
		{
			addSavedObject(objs[i]);
		}		
	}

	/** @see CompilerContext#setCursorInfo */
	@Override
  public void setCursorInfo(Object cursorInfo)
	{
		this.cursorInfo = cursorInfo;
	}

	/** @see CompilerContext#getCursorInfo */
	@Override
  public Object getCursorInfo()
	{
		return cursorInfo;
	}

	
	/** @see CompilerContext#firstOnStack */
	@Override
  public void firstOnStack()
	{
		firstOnStack = true;
	}

	/** @see CompilerContext#isFirstOnStack */
	@Override
  public boolean isFirstOnStack()
	{
		return firstOnStack;
	}

	/**
	 * Set the in use state for the compiler context.
	 *
	 * @param inUse	 The new inUse state for the compiler context.
	 */
	@Override
  public void setInUse(boolean inUse)
	{
		this.inUse = inUse;

		/*
		** Close the StoreCostControllers associated with this CompilerContext
		** when the context is no longer in use.
		*/
		if ( ! inUse)
		{
			closeStoreCostControllers();
			closeSortCostControllers();
		}
	}

	/**
	 * Return the in use state for the compiler context.
	 *
	 * @return boolean	The in use state for the compiler context.
	 */
	@Override
  public boolean getInUse()
	{
		return inUse;
	}

	/**
	 * Sets which kind of query fragments are NOT allowed. Basically,
	 * these are fragments which return unstable results. CHECK CONSTRAINTS
	 * and CREATE PUBLICATION want to forbid certain kinds of fragments.
	 *
	 * @param reliability	bitmask of types of query fragments to be forbidden
	 *						see the reliability bitmasks in CompilerContext.java
	 *
	 */
	@Override
  public void	setReliability(int reliability) { this.reliability = reliability; }

	/**
	 * Return the reliability requirements of this clause. See setReliability()
	 * for a definition of clause reliability.
	 *
	 * @return a bitmask of which types of query fragments are to be forbidden
	 */
	@Override
  public int getReliability() { return reliability; }

	/**
	 * @see CompilerContext#getStoreCostController
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public StoreCostController getStoreCostController(long conglomerateNumber)
			throws StandardException
	{
		/*
		** Try to find the given conglomerate number in the array of
		** conglom ids.
		*/
		for (int i = 0; i < storeCostConglomIds.size(); i++)
		{
			Long conglomId = (Long) storeCostConglomIds.elementAt(i);
			if (conglomId.longValue() == conglomerateNumber)
				return (StoreCostController) storeCostControllers.elementAt(i);
		}

		/*
		** Not found, so get a StoreCostController from the store.
		*/
		StoreCostController retval =
						lcc.getTransactionCompile().openStoreCost(conglomerateNumber);

		/* Put it in the array */
		storeCostControllers.insertElementAt(retval,
											storeCostControllers.size());

		/* Put the conglomerate number in its array */
		storeCostConglomIds.insertElementAt(
// GemStone changes BEGIN
								// changed to use valueOf()
								Long.valueOf(conglomerateNumber),
								/* (original code)
								new Long(conglomerateNumber),
								*/
// GemStone changes END
								storeCostConglomIds.size());

		return retval;
	}

	/**
	 *
	 */
	private void closeStoreCostControllers()
	{
		for (int i = 0; i < storeCostControllers.size(); i++)
		{
			StoreCostController scc =
				(StoreCostController) storeCostControllers.elementAt(i);
			try {
				scc.close();
			} catch (StandardException se) {
			}
		}

		storeCostControllers.removeAllElements();
		storeCostConglomIds.removeAllElements();
	}

	/**
	 * @see CompilerContext#getSortCostController
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
  public SortCostController getSortCostController() throws StandardException
	{
		/*
		** Re-use a single SortCostController for each compilation
		*/
		if (sortCostController == null)
		{
			/*
			** Get a StoreCostController from the store.
			*/

			sortCostController =
				lcc.getTransactionCompile().openSortCostController((Properties) null);
		}

		return sortCostController;
	}

	/**
	 *
	 * @exception StandardException		Thrown on error
	 */
	private void closeSortCostControllers()
	{
		if (sortCostController != null)
		{
			sortCostController.close();
			sortCostController = null;	
		}
	}

	/**
	 * Get the compilation schema descriptor for this compilation context.
	   Will be null if no default schema lookups have occured. Ie.
	   the statement is independent of the current schema.
	 * 
	 * @return the compilation schema descirptor
	 */
	@Override
  public SchemaDescriptor getCompilationSchema()
	{
		return compilationSchema;
	}

	/**
	 * Set the compilation schema descriptor for this compilation context.
	 *
	 * @param newDefault	the compilation schema
	 * 
	 * @return the previous compilation schema descirptor
	 */
	@Override
  public SchemaDescriptor setCompilationSchema(SchemaDescriptor newDefault)
	{
		SchemaDescriptor tmpSchema = compilationSchema;
		compilationSchema = newDefault;
		return tmpSchema;
	}

	/**
	 * @see CompilerContext#setParameterList
	 */
	@Override
  public void setParameterList(Vector<ValueNode> parameterList)
	{
		this.parameterList = parameterList;

		/* Don't create param descriptors array if there are no params */
		int numberOfParameters = (parameterList == null) ? 0 : parameterList.size();

		if (numberOfParameters > 0)
		{
			parameterDescriptors = new DataTypeDescriptor[numberOfParameters];
			this.originalParamTypeCompilers = new ArrayList<TypeCompiler>(numberOfParameters);
			for(int i = 0  ; i < numberOfParameters; ++i) {
			  this.originalParamTypeCompilers.add(null);
			}
		}
	}

	/**
	 * @see CompilerContext#getParameterList
	 */
	@Override
  public Vector<ValueNode> getParameterList()
	{
		return parameterList;
	}

	/**
	 * @see CompilerContext#setReturnParameterFlag
	 */
	@Override
  public void setReturnParameterFlag()
	{
		returnParameterFlag = true;
	}

	/**
	 * @see CompilerContext#getReturnParameterFlag
	 */
	@Override
  public boolean getReturnParameterFlag()
	{
		return returnParameterFlag;
	}

	/**
	 * @see CompilerContext#getParameterTypes
	 */
	@Override
  public DataTypeDescriptor[] getParameterTypes()
	{
		return parameterDescriptors;
	}
	@Override
	public List<TypeCompiler> getOriginalParameterTypeCompilers()
        {
                return this.originalParamTypeCompilers;
        }

	/**
	 * @see CompilerContext#setScanIsolationLevel
	 */
	@Override
  public void setScanIsolationLevel(int isolationLevel)
	{
		scanIsolationLevel = isolationLevel;
	}

	/**
	 * @see CompilerContext#getScanIsolationLevel
	 */
	@Override
  public int getScanIsolationLevel()
	{
		return scanIsolationLevel;
	}

	/**
	 * @see CompilerContext#getTypeCompilerFactory
	 */
	@Override
  public TypeCompilerFactory getTypeCompilerFactory()
	{
		return typeCompilerFactory;
	}


	/**
		Add a compile time warning.
	*/
	@Override
  public void addWarning(SQLWarning warning) {
		if (warnings == null)
			warnings = warning;
		else
			warnings.setNextWarning(warning);
	}

	/**
		Get the chain of compile time warnings.
	*/
	@Override
  public SQLWarning getWarnings() {
		return warnings;
	}

	/////////////////////////////////////////////////////////////////////////////////////
	//
	// class interface
	//
	// this constructor is called with the parser
	// to be saved when the context
	// is created (when the first statement comes in, likely).
	//
	/////////////////////////////////////////////////////////////////////////////////////

	public CompilerContextImpl(ContextManager cm,
			LanguageConnectionContext lcc,
		TypeCompilerFactory typeCompilerFactory )
	{
		super(cm, CompilerContext.CONTEXT_ID);

		this.lcc = lcc;
		lcf = lcc.getLanguageConnectionFactory();
		this.parser = lcf.newParser(this);
		this.matcher = lcf.newParser(this);
		this.typeCompilerFactory = typeCompilerFactory;

		// the prefix for classes in this connection
		classPrefix = "ac"+lcf.getUUIDFactory().createUUID().toString().replace('-','x');

                constantTokenList = new ArrayList<com.pivotal.gemfirexd.internal.engine.sql.compile.Token>();
                this.originalParamTypeCompilers = new ArrayList<TypeCompiler>();
		dynamicTokenList = new ArrayList<com.pivotal.gemfirexd.internal.impl.sql.compile.Token>();
		initRequiredPriv();
	}

	private void initRequiredPriv()
	{
		currPrivType = Authorizer.NULL_PRIV;
		privTypeStack.clear();
		requiredColumnPrivileges = null;
		requiredTablePrivileges = null;
		requiredSchemaPrivileges = null;
		requiredRoutinePrivileges = null;
		requiredRolePrivileges = null;

                if (SanityManager.DEBUG) {
                  if (GemFireXDUtils.TraceAuthentication) {
                      StatementContext stmctx = lcc.getStatementContext();
                      SanityManager
                          .DEBUG_PRINT(
                              GfxdConstants.TRACE_AUTHENTICATION,
                              stmctx != null ? stmctx.getStatementText()
                                  : " NULL (mostly because lcc.GeneralizedStatement.pushCompilerContext ) "
                                      + " using SqlAuthorization = "
                                      + lcc.usesSqlAuthorization());
                  }
                }
		if( lcc.usesSqlAuthorization())
		{
			requiredColumnPrivileges = new HashMap();
			requiredTablePrivileges = new HashMap();
			requiredSchemaPrivileges = new HashMap();
			requiredRoutinePrivileges = new HashMap();
			requiredRolePrivileges = new HashMap();
		}
	} // end of initRequiredPriv

	/**
	 * Sets the current privilege type context. Column and table nodes do not know
	 * how they are being used. Higher level nodes in the query tree do not know what
	 * is being referenced.
	 * Keeping the context allows the two to come together.
	 *
	 * @param privType One of the privilege types in com.pivotal.gemfirexd.internal.iapi.sql.conn.Authorizer.
	 */
	@Override
  public void pushCurrentPrivType( int privType)
	{
		privTypeStack.push( ReuseFactory.getInteger( currPrivType));
		currPrivType = privType;
	}

	@Override
  public void popCurrentPrivType( )
	{
		currPrivType = ((Integer) privTypeStack.pop()).intValue();
	}
	
	/**
	 * Add a column privilege to the list of used column privileges.
	 *
	 * @param column The column whose privileges we're interested in.
	 */
	@Override
  public void addRequiredColumnPriv( ColumnDescriptor column)
	{
		if( requiredColumnPrivileges == null // Using old style authorization
			|| currPrivType == Authorizer.NULL_PRIV
			|| currPrivType == Authorizer.DELETE_PRIV // Table privilege only
			|| currPrivType == Authorizer.INSERT_PRIV // Table privilege only
			|| currPrivType == Authorizer.TRIGGER_PRIV // Table privilege only
			|| currPrivType == Authorizer.ALTER_PRIV // Table privilege only
			|| currPrivType == Authorizer.EXECUTE_PRIV
			|| column == null)
			return;
		/*
		* Note that to look up the privileges for this column,
		* we need to know what table the column is in. However,
		* not all ColumnDescriptor objects are associated with
		* a table object. Sometimes a ColumnDescriptor
		* describes a column but doesn't specify the table. An
		* example of this occurs in the set-clause of the
		* UPDATE statement in SQL, where we may have a
		* ColumnDescriptor which describes the expression that
		* is being used in the UPDATE statement to provide the
		* new value that will be computed by the UPDATE. In such a
		* case, there is no column privilege to be added, so we
		* just take an early return. DERBY-1583 has more details.
		*/
		TableDescriptor td = column.getTableDescriptor();
		if (td == null)
			return;
		if (td.getTableType() ==
		    TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE) {
		  return; // no priv needed, it is per session anyway
		}

		UUID tableUUID = td.getUUID();
		StatementTablePermission key = new StatementTablePermission( tableUUID, currPrivType);
		StatementColumnPermission tableColumnPrivileges
		  = (StatementColumnPermission) requiredColumnPrivileges.get( key);
		if( tableColumnPrivileges == null)
		{
			tableColumnPrivileges = new StatementColumnPermission( tableUUID,
																   currPrivType,
																   new FormatableBitSet( td.getNumberOfColumns()));
			requiredColumnPrivileges.put(key, tableColumnPrivileges);
		}
		tableColumnPrivileges.getColumns().set(column.getPosition() - 1);
	} // end of addRequiredColumnPriv

	/**
	 * Add a table or view privilege to the list of used table privileges.
	 *
	 * @see CompilerContext#addRequiredRoutinePriv
	 */
	@Override
  public void addRequiredTablePriv( TableDescriptor table)
	{
                {
                  if (GemFireXDUtils.TraceAuthentication) {
                      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                          "adding privilege for Table Descriptor="
                              + SanityManager.lineSeparator + table
                              + SanityManager.lineSeparator + " Required Table Privileges="
                              + requiredTablePrivileges);
                  }
                }
		if( requiredTablePrivileges == null || table == null)
			return;
		
		if (table.getTableType() ==
		    TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE) {
		  return; // no priv needed, it is per session anyway
		}

		StatementTablePermission key = new StatementTablePermission( table.getUUID(), currPrivType);
		requiredTablePrivileges.put(key, key);
	}

	/**
	 * Add a routine execute privilege to the list of used routine privileges.
	 *
	 * @see CompilerContext#addRequiredRoutinePriv
	 */
	@Override
  public void addRequiredRoutinePriv( AliasDescriptor routine)
	{
		// routine == null for built in routines
		if( requiredRoutinePrivileges == null || routine == null)
			return;

		// Ignore SYSFUN routines for permission scheme
		if (routine.getSchemaUUID().toString().equals(SchemaDescriptor.SYSFUN_SCHEMA_UUID))
			return;

 		if (requiredRoutinePrivileges.get(routine.getUUID()) == null)
 			requiredRoutinePrivileges.put(routine.getUUID(), ReuseFactory.getInteger(1));
	}

	/**
	 * Add a required schema privilege to the list privileges.
	 *
	 * @see CompilerContext#addRequiredSchemaPriv
	 */
	@Override
  public void addRequiredSchemaPriv(String schemaName, String aid, int privType)
	{
		if( requiredSchemaPrivileges == null || schemaName == null)
			return;

		StatementSchemaPermission key = new 
				StatementSchemaPermission(schemaName, aid, privType);

		requiredSchemaPrivileges.put(key, key);
	}


	/**
	 * Add a required role privilege to the list privileges.
	 *
	 * @see CompilerContext#addRequiredRolePriv
	 */
	@Override
  public void addRequiredRolePriv(String roleName, int privType)
	{
		if( requiredRolePrivileges == null)
			return;

		StatementRolePermission key = new
			StatementRolePermission(roleName, privType);

		requiredRolePrivileges.put(key, key);
	}


	/**
	 * @return The list of required privileges.
	 */
	@Override
  public List getRequiredPermissionsList()
	{
		int size = 0;
		if( requiredRoutinePrivileges != null)
			size += requiredRoutinePrivileges.size();
		if( requiredTablePrivileges != null)
			size += requiredTablePrivileges.size();
		if( requiredSchemaPrivileges != null)
			size += requiredSchemaPrivileges.size();
		if( requiredColumnPrivileges != null)
			size += requiredColumnPrivileges.size();
		if( requiredRolePrivileges != null)
			size += requiredRolePrivileges.size();
		
		ArrayList list = new ArrayList( size);
		if( requiredRoutinePrivileges != null)
		{
			for( Iterator itr = requiredRoutinePrivileges.keySet().iterator(); itr.hasNext();)
			{
				UUID routineUUID = (UUID) itr.next();
				
				list.add( new StatementRoutinePermission( routineUUID));
			}
		}
		if( requiredTablePrivileges != null)
		{
			for( Iterator itr = requiredTablePrivileges.values().iterator(); itr.hasNext();)
			{
				list.add( itr.next());
			}
		}
		if( requiredSchemaPrivileges != null)
		{
			for( Iterator itr = requiredSchemaPrivileges.values().iterator(); itr.hasNext();)
			{
				list.add( itr.next());
			}
		}
		if( requiredColumnPrivileges != null)
		{
			for( Iterator itr = requiredColumnPrivileges.values().iterator(); itr.hasNext();)
			{
				list.add( itr.next());
			}
		}
		if( requiredRolePrivileges != null)
		{
			for( Iterator itr = requiredRolePrivileges.values().iterator();
				 itr.hasNext();)
			{
				list.add( itr.next());
			}
		}
                if (GemFireXDUtils.TraceAuthentication) {
                    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                        "Returning required perimission list " + list);
                }
		return list;
	} // end of getRequiredPermissionsList

	/*
	** Context state must be reset in restContext()
	*/

	private final Parser 		parser;
        private final Parser            matcher;
	private final LanguageConnectionContext lcc;
	private final LanguageConnectionFactory lcf;
	private final TypeCompilerFactory	typeCompilerFactory;
	private Dependent			currentDependent;
	private DependencyManager	dm;
	private boolean				firstOnStack;
	private boolean				inUse;
	private int					reliability = CompilerContext.SQL_LEGAL;
	private	int					nextColumnNumber = 1;
	private int					nextTableNumber;
	private int					nextSubqueryNumber;
	private int					nextResultSetNumber;
	private int					scanIsolationLevel;
	private int					nextEquivalenceClass = -1;
	private long				nextClassName;
	private Vector				savedObjects;
	private final String				classPrefix;
	private SchemaDescriptor	compilationSchema;
	private ProviderList		currentAPL;
	private boolean returnParameterFlag;

	private final Vector				storeCostControllers = new Vector();
	private final Vector				storeCostConglomIds = new Vector();

	private SortCostController	sortCostController;

	private Vector<ValueNode> parameterList;

	/* Type descriptors for the ? parameters */
	private DataTypeDescriptor[]	parameterDescriptors;
        private List<TypeCompiler>    originalParamTypeCompilers;
	private Object				cursorInfo;

	private SQLWarning warnings;

	private final Stack privTypeStack = new Stack();
	private int currPrivType = Authorizer.NULL_PRIV;
	private HashMap requiredColumnPrivileges;
	private HashMap requiredTablePrivileges;
	private HashMap requiredSchemaPrivileges;
	private HashMap requiredRoutinePrivileges;
	private HashMap requiredRolePrivileges;
// GemStone changes BEGIN
//	private static final Comparator<ValueNode> CONSTANT_COMPARATOR = new Comparator<ValueNode>() {
//          @Override
//          public int compare(ValueNode o1, ValueNode o2) {
//              return o1.getBeginOffset() > o2.getBeginOffset() ? 1 : o1
//                  .getBeginOffset() < o2.getBeginOffset() ? -1 : 0;
//          }
//        };

        /*
         * Keep the constant in ascending order of their offsets to the main string so that
         * during string traversal in hashCode computation, we skip these offsets.  
         */
        private ArrayList<com.pivotal.gemfirexd.internal.engine.sql.compile.Token>
            constantTokenList;

	private ArrayList<com.pivotal.gemfirexd.internal.impl.sql.compile.Token>
			dynamicTokenList;

	private boolean createQueryInfo = true;
	
	private boolean isPreparedStatement = false;
	
        //private boolean isUpToDatePreparedStatement = false;
        
	private GenericPreparedStatement preparedStatement = null;
	
	private String generalizedQuery = null;

    private boolean allTablesReplicatedOnRemote = false;
    private boolean allowSubqueryFlattening = true;
    private boolean foundNoncorrelatedsubquery = false;
    private boolean allowOrListOptimization = false;
    private boolean hasOrList = false;
    private boolean optimizeForWrite;
    private boolean orderByListNullified = false;
    private boolean isOffsetOrFetchNext = false;
    private boolean withSecondaries;
    private String statementAlias;
    private StatementStats statementStats;
    private boolean allowStatementOptimization = false;//true;
    private boolean currentQueryStatementOptimization = false;
    private boolean scope=true;
    private boolean cohort = false;
    private THashMap ncjMetaData = null;
    private GenericPreparedStatement parentPS = null;
    private boolean queryHDFS = false;
    private boolean hasQueryHDFS = false;
    private boolean convertCharConstToVarchar = false;
	private boolean ddlForSnappyUse = false;
	private boolean snappyForcedDDLRouting = false;
    private ExecutionEngine executionEngine = ExecutionEngine.NOT_DECIDED;
    
    private short originalExecFlags = 0;
    // NOTE: when adding new flags here always take care to reset them in
    // resetContext() else stale flags may be carried over to compilation
    // of unrelated statements fired subsequently

	//public void disableDistributed(){
	//	this.distributed=false;
	//}
	//public boolean isDistributed(){
	//	  return this.distributed;
	//}
        
    private final Stack<DMLScope> scopes = new Stack<DMLScope>();
    
	private  static class DMLScope {

		private final int parenthesisCount;
		private boolean shouldOptimizeLiteral ;

		DMLScope(int paren) {
			this.parenthesisCount = paren;
			
		}

		public final boolean getOptimizationFlag() {
			return shouldOptimizeLiteral;
		}

		public final void setOptimizationFlag(boolean onOff) {
			this.shouldOptimizeLiteral = onOff;
		}

		public int getParenthesisCount() {
			return this.parenthesisCount;
		}
	}
	
	@Override
  public void setGlobalScope() {
	   this.scope=true;
	}
	

	@Override
  public void setLocalScope() {
	  this.scope=false;
	}
	
	@Override
  public boolean isGlobalScope() {
	  return this.scope;
	}

  @Override
  public boolean isCohort() {
    return this.cohort;
  }

  @Override
  public void setCohortFlag(boolean flag) {
    this.cohort = flag;
  }

  @Override
  /**
   * Clear the chain of compile time warnings.
   */
  public void clearWarnings() {
    this.warnings = null;
  }

  @Override
  public void disableQueryInfoCreation() {
    this.createQueryInfo = false;
  }

  @Override
  public boolean createQueryInfo() {
    return this.createQueryInfo;
  }

  @Override
  public final boolean isPreparedStatement() {
    return isPreparedStatement;
  }

  @Override
  public final void setPreparedStatement() {
    isPreparedStatement = true;
  }

  @Override
  public final void addConstantTokenToList(
      com.pivotal.gemfirexd.internal.engine.sql.compile.Token constnode) {
    constantTokenList.add(constnode);
  }
  
  @Override
  public final void storeTypeCompilerForParam(TypeCompiler tc) {
    this.originalParamTypeCompilers.add(tc);
  }

  @Override
  public final ArrayList<com.pivotal.gemfirexd.internal.engine.sql.compile.Token>
      getConstantTokenLists() {
    return constantTokenList;
  }

  @Override
  public final boolean canOptimizeLiteral() {
	return this.currentQueryStatementOptimization;
  }

  
  
  @Override
  public final void allowOptimizeLiteral(boolean onOff) {
	    this.allowStatementOptimization = onOff;
  }

  @Override
  public final boolean isOptimizeLiteralAllowed() {
    return this.allowStatementOptimization;
  }

  
  @Override
  public void pushDML(int parenthesisCount) {
	  if(this.scopes.size() > 0) {
		  this.scopes.peek().setOptimizationFlag(this.currentQueryStatementOptimization);
	  }
	  this.currentQueryStatementOptimization = this.allowStatementOptimization;
	  this.scopes.push(new DMLScope(parenthesisCount));
  }
  
  @Override
  public final boolean switchOptimizeLiteral(boolean onOff) {
		boolean existingVal = this.currentQueryStatementOptimization;
		this.currentQueryStatementOptimization = onOff;
		return existingVal;
  }
  
  @Override
  public boolean popDML(int parenthesisCount) {
	  if( this.scopes.size() > 0 && parenthesisCount == this.scopes.peek().getParenthesisCount() -1) {
	     this.scopes.pop();
	     if(scopes.size() > 0) {
	    	 this.currentQueryStatementOptimization = this.scopes.peek().getOptimizationFlag();
	     }
	     return true;
	  }
	  return false;
  }

  @Override
  public GenericPreparedStatement getPreparedStatement() {
    return preparedStatement;
  }

 /* @Override
  public boolean isPreparedStatementUpToDate() {
    return isUpToDatePreparedStatement;
  }*/

  @Override
  public void setPreparedStatement(GenericPreparedStatement preparedSt)
      throws StandardException {
    preparedStatement = preparedSt;
    //isUpToDatePreparedStatement = preparedStatement.upToDate();
    parameterDescriptors = preparedStatement.getParameterTypes();
    this.originalParamTypeCompilers = preparedStatement.getOriginalParameterTypeCompilers();
  }

 /* @Override
  public final String replaceQueryString(final String query) {
    final String orig = replaceIncomingQuery;
    replaceIncomingQuery = query;
    return orig;
  }*/

  @Override
  public void setSubqueryFlatteningFlag(boolean allowed) {
    this.allowSubqueryFlattening = allowed;
  }

  @Override
  public boolean subqueryFlatteningAllowed() {
    return this.allowSubqueryFlattening;
  }

  @Override
  public boolean containsNoncorrelatedSubquery() {
    return this.foundNoncorrelatedsubquery;
  }

  @Override
  public void setNoncorrelatedSubqueryFound() {
    this.foundNoncorrelatedsubquery = true;
  }

  @Override
  public boolean orListOptimizationAllowed() {
    return this.allowOrListOptimization;
  }

  @Override
  public void setOrListOptimizationFlag(boolean allowed) {
    this.allowOrListOptimization = allowed;
  }

  @Override
  public boolean hasOrList() {
    return this.hasOrList;
  }

  @Override
  public void setHasOrList(boolean flag) {
    this.hasOrList = flag;
  }

  @Override
  public boolean optimizeForWrite() {
    return this.optimizeForWrite;
  }
  
  @Override
  public boolean withSecondaries() {
    return this.withSecondaries;
  }

  @Override
  public void setOptimizeForWrite(boolean onlyPrimaries) {
    this.optimizeForWrite = onlyPrimaries;
  }
  
  @Override
  public void setWithSecondaries(boolean includeSecondaries) {
    this.withSecondaries = includeSecondaries;
  }
  
  @Override
  public void setStatementAlias(String alias) {
    this.statementAlias = alias;
  }
  
  @Override
  public String getStatementAlias() {
    return this.statementAlias;
  }

  @Override
  public void setStatementStats(StatementStats stats) {
    this.statementStats = stats;
  }
  
  @Override
  public StatementStats getStatementStats() {
    return this.statementStats;
  }

  @Override
  public void setParentPS(GenericPreparedStatement gps) {
    this.parentPS = gps;
  }

  @Override
  public GenericPreparedStatement getParentPS() {
    return this.parentPS;
  }

  @Override
  public boolean allTablesAreReplicatedOnRemote() {
    return this.allTablesReplicatedOnRemote;
  }

  @Override
  public void setAllTablesAreReplicatedOnRemote(boolean allReplicated) {
    this.allTablesReplicatedOnRemote = allReplicated;
  }
  
  @Override
  public boolean isNCJoinOnRemote() {
    return this.ncjMetaData != null;
  }

  @Override
  public THashMap getNCJMetaDataOnRemote() {
    return this.ncjMetaData;
  }
  
  @Override
  public void setNCJoinOnRemote(THashMap ncjMetaData) {
    this.ncjMetaData = ncjMetaData;
  }

  @Override
  public void setOrderByListNullified() {
    this.orderByListNullified = true;
  }
  
  @Override
  public boolean isOrderByListNullified() {
    return this.orderByListNullified;
  }
  
  @Override
  public void setIsOffsetOrFetchNext() {
    this.isOffsetOrFetchNext = true;
  }
  
  @Override
  public boolean isOffsetOrFetchNext() {
    return this.isOffsetOrFetchNext;
  }

  @Override
  public void setGeneralizedQueryString(String query) {
    generalizedQuery = query;
  }
  
  @Override
  public String getGeneralizedQueryString() {
    return generalizedQuery;
  }
  
  @Override
  public void setQueryHDFS(boolean val) {
    this.queryHDFS = val;
  }
  
  @Override
  public boolean getQueryHDFS() {
    return this.queryHDFS;
  }
   
  @Override
  public boolean getHasQueryHDFS() {
    return hasQueryHDFS;
  }

  @Override
  public void setHasQueryHDFS(boolean hasQueryHDFS) {
    this.hasQueryHDFS = hasQueryHDFS;
  }

  @Override
  public void setExecutionEngine(ExecutionEngine engine) { this.executionEngine = engine; }

  @Override
  public ExecutionEngine getExecutionEngine() { return this.executionEngine; }

  @Override
  public void setOriginalExecFlags(short execFlags) {
    this.originalExecFlags = execFlags;
  }

  @Override
  public short getOriginalExecFlags() {
    return originalExecFlags;
  }

  @Override
  public boolean convertCharConstToVarchar() {
    return this.convertCharConstToVarchar;
  }

  @Override
  public void setConvertCharConstToVarchar(boolean flag) { this.convertCharConstToVarchar = flag; }

  @Override
  public boolean isMarkedAsDDLForSnappyUse() { return this.ddlForSnappyUse; }

  @Override
  public void markAsDDLForSnappyUse(boolean flag) { this.ddlForSnappyUse = flag; }

  @Override
  public boolean isForcedDDLrouting() { return this.snappyForcedDDLRouting; }

  @Override
  public void setForcedDDLrouting(boolean flag) { this.snappyForcedDDLRouting = flag; }

	@Override
	public void addDynamicTokenToList(com.pivotal.gemfirexd.internal.impl.sql.compile.Token token) {
		this.dynamicTokenList.add(token);
	}

	@Override
	public ArrayList<com.pivotal.gemfirexd.internal.impl.sql.compile.Token> getDynamicTokenList() {
		return this.dynamicTokenList;
	}

// GemStone changes END

} // end of class CompilerContextImpl
