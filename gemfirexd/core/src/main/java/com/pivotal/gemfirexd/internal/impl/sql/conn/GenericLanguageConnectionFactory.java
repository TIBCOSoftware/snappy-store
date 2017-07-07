/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.sql.conn.GenericLanguageConnectionFactory

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

package com.pivotal.gemfirexd.internal.impl.sql.conn;



















import java.util.Properties;
import java.util.Locale;
import java.util.Dictionary;
import java.io.Serializable;

import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.internal.engine.sql.GeneralizedStatement;
import com.pivotal.gemfirexd.internal.iapi.db.Database;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.EngineType;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC20Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.JDBC30Translation;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.CacheFactory;
import com.pivotal.gemfirexd.internal.iapi.services.cache.CacheManager;
import com.pivotal.gemfirexd.internal.iapi.services.cache.Cacheable;
import com.pivotal.gemfirexd.internal.iapi.services.cache.CacheableFactory;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.JavaFactory;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.daemon.Serviceable;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.LocaleFinder;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleFactory;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleSupportable;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyFactory;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertySetCallback;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.uuid.UUIDFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.LanguageFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.Statement;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.NodeFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.OptimizerFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Parser;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.TypeCompilerFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.AccessFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.sql.GenericStatement;
import com.pivotal.gemfirexd.internal.impl.sql.conn.CachedStatement;

/**
 * LanguageConnectionFactory generates all of the items
 * a language system needs that is specific to a particular
 * connection. Alot of these are other factories.
 *
 */
public class GenericLanguageConnectionFactory
	implements LanguageConnectionFactory, CacheableFactory, PropertySetCallback, ModuleControl, ModuleSupportable {

	/*
		fields
	 */
	private 	ExecutionFactory		ef;
	private 	OptimizerFactory		of;
	private		TypeCompilerFactory		tcf;
	private 	DataValueFactory		dvf;
	private 	UUIDFactory				uuidFactory;
	private 	JavaFactory				javaFactory;
	private 	ClassFactory			classFactory;
	private 	NodeFactory				nodeFactory;
	private 	PropertyFactory			pf;

// GemStone changes BEGIN
	private final java.util.concurrent.atomic.AtomicInteger nextLCCInstanceNumber =
	    new java.util.concurrent.atomic.AtomicInteger(0);
	/* (original code)
	private		int						nextLCCInstanceNumber;
	*/
// GemStone changes END

	/*
	  for caching prepared statements 
	*/
	private int cacheSize = com.pivotal.gemfirexd.internal.iapi.reference.Property.STATEMENT_CACHE_SIZE_DEFAULT;
	private CacheManager singleStatementCache;

	/*
	   constructor
	*/
	public GenericLanguageConnectionFactory() {
	}

	/*
	   LanguageConnectionFactory interface
	*/

	/*
		these are the methods that do real work, not just look for factories
	 */

	/**
		Get a Statement for the connection
		@param compilationSchema schema
	 * @param statementText the text for the statement
	 * @param forReadOnly if concurrency is CONCUR_READ_ONLY
		@return	The Statement
	 */
//       GemStone changes BEGIN
        public Statement getStatement(SchemaDescriptor compilationSchema, String statementText,
           short execFlags, boolean createGeneralizedStatement, THashMap ncjMetaData)
        {
            if(createGeneralizedStatement) {
              return new GeneralizedStatement(compilationSchema, statementText, execFlags, ncjMetaData) ;
            }else {
	      return new GenericStatement(compilationSchema, statementText,execFlags, ncjMetaData);
            }
//            GemStone changes END
	}


	/**
		Get a LanguageConnectionContext. this holds things
		we want to remember about activity in the language system,
		where this factory holds things that are pretty stable,
		like other factories.
		<p>
		The returned LanguageConnectionContext is intended for use
		only by the connection that requested it.

		@return a language connection context for the context stack.
		@exception StandardException the usual -- for the subclass
	 */

	public LanguageConnectionContext newLanguageConnectionContext(
		ContextManager cm,
		TransactionController tc,
		LanguageFactory lf,
		Database db,
		String userName,
		String authToken,
		String drdaID,
// GemStone changes BEGIN
		long connId,
		boolean isRemote,
// GemStone changes END
		String dbname) throws StandardException {
		
		return new GenericLanguageConnectionContext(cm,tc,lf,this,db,userName,
// GemStone changes BEGIN
		    authToken, getNextLCCInstanceNumber(), drdaID, connId, isRemote, dbname);
		    /* (original code)
		    getNextLCCInstanceNumber(),	drdaID, dbname);
		    */
// GemStone changes END
	}

	public Cacheable newCacheable(CacheManager cm) {
		return new CachedStatement();
	}

	/*
		these methods all look for factories that we booted.
	 */
	 
	 /**
		Get the UUIDFactory to use with this language connection
		REMIND: this is only used by the compiler; should there be
		a compiler module control class to boot compiler-only stuff?
	 */
	public UUIDFactory	getUUIDFactory()
	{
		return uuidFactory;
	}

	/**
		Get the ClassFactory to use with this language connection
	 */
	public ClassFactory	getClassFactory()
	{
		return classFactory;
	}

	/**
		Get the JavaFactory to use with this language connection
		REMIND: this is only used by the compiler; should there be
		a compiler module control class to boot compiler-only stuff?
	 */
	public JavaFactory	getJavaFactory()
	{
		return javaFactory;
	}

	/**
		Get the NodeFactory to use with this language connection
		REMIND: is this only used by the compiler?
	 */
	public NodeFactory	getNodeFactory()
	{
		return nodeFactory;
	}

	/**
		Get the ExecutionFactory to use with this language connection
	 */
	public final ExecutionFactory	getExecutionFactory() {
		return ef;
	}

	/**
		Get the PropertyFactory to use with this language connection
	 */
	public PropertyFactory	getPropertyFactory() 
	{
		return pf;
	}	

	/**
		Get the OptimizerFactory to use with this language connection
	 */
	public OptimizerFactory	getOptimizerFactory() {
		return of;
	}
	/**
		Get the TypeCompilerFactory to use with this language connection
	 */
	public TypeCompilerFactory getTypeCompilerFactory() {
		return tcf;
	}

	/**
		Get the DataValueFactory to use with this language connection
	 */
	public DataValueFactory		getDataValueFactory() {
		return dvf;
	}

	/*
		ModuleControl interface
	 */

	/**
		this implementation will not support caching of statements.
	 */
	public boolean canSupport(String identifier, Properties startParams) {

		return Monitor.isDesiredType( startParams,
                EngineType.STANDALONE_DB | EngineType.STORELESS_ENGINE);
	}

	private	int	statementCacheSize(Properties startParams)
	{
		String wantCacheProperty = null;

		wantCacheProperty =
			PropertyUtil.getPropertyFromSet(startParams, com.pivotal.gemfirexd.internal.iapi.reference.Property.STATEMENT_CACHE_SIZE);

		if (SanityManager.DEBUG)
			SanityManager.DEBUG("StatementCacheInfo", "Cacheing implementation chosen if null or 0<"+wantCacheProperty);

		if (wantCacheProperty != null) {
			try {
			    cacheSize = Integer.parseInt(wantCacheProperty);
			} catch (NumberFormatException nfe) {
				cacheSize = com.pivotal.gemfirexd.internal.iapi.reference.Property.STATEMENT_CACHE_SIZE_DEFAULT; 
			}
		}

		return cacheSize;
	}
	
	/**
	 * Start-up method for this instance of the language connection factory.
	 * Note these are expected to be booted relative to a Database.
	 *
	 * @param startParams	The start-up parameters (ignored in this case)
	 *
	 * @exception StandardException	Thrown on failure to boot
	 */
	public void boot(boolean create, Properties startParams) 
		throws StandardException {

		//The following call to Monitor to get DVF is going to get the already
		//booted DVF (DVF got booted by BasicDatabase's boot method. 
		//BasicDatabase also set the correct Locale in the DVF. There after,
		//DVF with correct Locale is available to rest of the Derby code.
		dvf = (DataValueFactory) Monitor.bootServiceModule(create, this, com.pivotal.gemfirexd.internal.iapi.reference.ClassName.DataValueFactory, startParams);
		javaFactory = (JavaFactory) Monitor.startSystemModule(com.pivotal.gemfirexd.internal.iapi.reference.Module.JavaFactory);
		uuidFactory = Monitor.getMonitor().getUUIDFactory();
		classFactory = (ClassFactory) Monitor.getServiceModule(this, com.pivotal.gemfirexd.internal.iapi.reference.Module.ClassFactory);
		if (classFactory == null)
 			classFactory = (ClassFactory) Monitor.findSystemModule(com.pivotal.gemfirexd.internal.iapi.reference.Module.ClassFactory);

		//set the property validation module needed to do propertySetCallBack
		//register and property validation
		setValidation();

		ef = (ExecutionFactory) Monitor.bootServiceModule(create, this, ExecutionFactory.MODULE, startParams);
		of = (OptimizerFactory) Monitor.bootServiceModule(create, this, OptimizerFactory.MODULE, startParams);
		tcf =
		   (TypeCompilerFactory) Monitor.startSystemModule(TypeCompilerFactory.MODULE);
		nodeFactory = (NodeFactory) Monitor.bootServiceModule(create, this, NodeFactory.MODULE, startParams);

		// If the system supports statement caching boot the CacheFactory module.
		int cacheSize = statementCacheSize(startParams);
		if (cacheSize > 0) {
			CacheFactory cacheFactory = (CacheFactory) Monitor.startSystemModule(com.pivotal.gemfirexd.internal.iapi.reference.Module.CacheFactory);
			singleStatementCache = cacheFactory.newCacheManager(this,
												"StatementCache",
												cacheSize/4,
												cacheSize);
		}

	}

	/**
	 * returns the statement cache that this connection should use; currently
	 * there is a statement cache per connection.
	 */
	

	public CacheManager getStatementCache()
	{
		return singleStatementCache;
	}

	/**
	 * Stop this module.  In this case, nothing needs to be done.
	 */
	public void stop() {
	}

	/*
	** Methods of PropertySetCallback
	*/

	public void init(boolean dbOnly, Dictionary p) {
		// not called yet ...
	}

	/**
	  @see PropertySetCallback#validate
	  @exception StandardException Thrown on error.
	*/
	public boolean validate(String key,
						 Serializable value,
						 Dictionary p)
		throws StandardException {
		if (value == null)
			return true;
		else if (key.equals(com.pivotal.gemfirexd.Property.AUTHZ_DEFAULT_CONNECTION_MODE))
		{
			String value_s = (String)value;
			if (value_s != null &&
				!StringUtil.SQLEqualsIgnoreCase(value_s, Property.NO_ACCESS) &&
				!StringUtil.SQLEqualsIgnoreCase(value_s, Property.READ_ONLY_ACCESS) &&
				!StringUtil.SQLEqualsIgnoreCase(value_s, Property.FULL_ACCESS))
				throw StandardException.newException(SQLState.AUTH_INVALID_AUTHORIZATION_PROPERTY, key, value_s);

			return true;
		}
		else if (key.equals(com.pivotal.gemfirexd.Property.AUTHZ_READ_ONLY_ACCESS_USERS) ||
				 key.equals(com.pivotal.gemfirexd.Property.AUTHZ_FULL_ACCESS_USERS))
		{
			String value_s = (String)value;

			/** Parse the new userIdList to verify its syntax. */
			String[] newList_a;
			try {newList_a = IdUtil.parseIdList(value_s);}
			catch (StandardException se) {
                throw StandardException.newException(SQLState.AUTH_INVALID_AUTHORIZATION_PROPERTY, se, key,value_s);
			}

			/** Check the new list userIdList for duplicates. */
			String dups = IdUtil.dups(newList_a);
			if (dups != null) throw StandardException.newException(SQLState.AUTH_DUPLICATE_USERS, key,dups);

			/** Check for users with both read and full access permission. */
			String[] otherList_a;
			String otherList;
			if (key.equals(com.pivotal.gemfirexd.Property.AUTHZ_READ_ONLY_ACCESS_USERS))
				otherList = (String)p.get(com.pivotal.gemfirexd.Property.AUTHZ_FULL_ACCESS_USERS);
			else
				otherList = (String)p.get(com.pivotal.gemfirexd.Property.AUTHZ_READ_ONLY_ACCESS_USERS);
			otherList_a = IdUtil.parseIdList(otherList);
			String both = IdUtil.intersect(newList_a,otherList_a);
			if (both != null) throw StandardException.newException(SQLState.AUTH_USER_IN_READ_AND_WRITE_LISTS, both);
			
			return true;
		}

		return false;
	}
	/** @see PropertySetCallback#apply */
	public Serviceable apply(String key,
							 Serializable value,
							 Dictionary p)
	{
			 return null;
	}
	/** @see PropertySetCallback#map */
	public Serializable map(String key, Serializable value, Dictionary p)
	{
		return null;
	}

	protected void setValidation() throws StandardException {
		pf = (PropertyFactory) Monitor.findServiceModule(this,
			com.pivotal.gemfirexd.internal.iapi.reference.Module.PropertyFactory);
		pf.addPropertySetNotification(this);
	}

    public Parser newParser(CompilerContext cc)
    {
        return new com.pivotal.gemfirexd.internal.impl.sql.compile.ParserImpl(cc);
    }

	// Class methods

	/**
	 * Get the instance # for the next LCC.
	 * (Useful for logStatementText=true output.
	 *
	 * @return instance # of next LCC.
	 */
// GemStone changes BEGIN
	protected final int getNextLCCInstanceNumber() {
	  return nextLCCInstanceNumber.incrementAndGet();
	}
	/* (original code)
	protected synchronized int getNextLCCInstanceNumber()
	{
		return nextLCCInstanceNumber++;
	}
	*/
// GemStone changes END
}
