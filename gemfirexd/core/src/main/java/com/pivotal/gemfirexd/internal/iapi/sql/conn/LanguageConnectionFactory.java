/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionFactory

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

package com.pivotal.gemfirexd.internal.iapi.sql.conn;






import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.internal.iapi.db.Database;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.cache.CacheManager;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.JavaFactory;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyFactory;
import com.pivotal.gemfirexd.internal.iapi.services.uuid.UUIDFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.LanguageFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.Statement;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CompilerContext;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.NodeFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.OptimizerFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.Parser;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.TypeCompilerFactory;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;


/**
 * Factory interface for items specific to a connection in the language system.
 * This is expected to be used internally, and so is not in Language.Interface.
 * <p>
 * This Factory provides pointers to other language factories; the
 * LanguageConnectionContext holds more dynamic information, such as
 * prepared statements and whether a commit has occurred or not.
 * <p>
 * This Factory is for internal items used throughout language during a
 * connection. Things that users need for the Database API are in
 * LanguageFactory in Language.Interface.
 * <p>
 * This factory returns (and thus starts) all the other per-database
 * language factories. So there might someday be properties as to which
 * ones to start (attributes, say, like level of optimization).
 * If the request is relative to a specific connection, the connection
 * is passed in. Otherwise, they are assumed to be database-wide services.
 *
 * @see com.pivotal.gemfirexd.internal.iapi.sql.LanguageFactory
 *
 */
public interface LanguageConnectionFactory {
	/**
		Used to locate this factory by the Monitor basic service.
		There needs to be a language factory per database.
	 */
	String MODULE = "com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionFactory";


	/**
		Get a Statement
		@param compilationSchema schema
	 * @param statementText the text for the statement
	 * @param execFlags
	 * @param createGeneralizedStatement
	 
		@return	The Statement
	 */
//      GemStone changes BEGIN
        Statement getStatement(SchemaDescriptor compilationSchema,
            String statementText, short execFlags,
            boolean createGeneralizedStatement, THashMap ncjMetaData);
//      GemStone changes END

	/**
		Get a new LanguageConnectionContext. this holds things
		we want to remember about activity in the language system,
		where this factory holds things that are pretty stable,
		like other factories.
		<p>
		The returned LanguageConnectionContext is intended for use
		only by the connection that requested it.

		@return a language connection context for the context stack.
		@exception StandardException the usual
	 */
	LanguageConnectionContext
	newLanguageConnectionContext(ContextManager cm,
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
								String dbname)

		throws StandardException;

	/**
		Get the UUIDFactory to use with this language connection
	 */
	UUIDFactory	getUUIDFactory();

	/**
		Get the ClassFactory to use with this language connection
	 */
	ClassFactory	getClassFactory();

	/**
		Get the JavaFactory to use with this language connection
	 */
	JavaFactory	getJavaFactory();

	/**
		Get the NodeFactory to use with this language connection
	 */
	NodeFactory	getNodeFactory();

	/**
		Get the ExecutionFactory to use with this language connection
	 */
	ExecutionFactory	getExecutionFactory();

	/**
		Get the PropertyFactory to use with this language connection
	 */
	PropertyFactory	getPropertyFactory();

	/**
		Get the OptimizerFactory to use with this language connection
	 */
	OptimizerFactory	getOptimizerFactory();

	/**
		Get the TypeCompilerFactory to use with this language connection
	 */
	TypeCompilerFactory getTypeCompilerFactory();

	/**
		Get the DataValueFactory to use with this language connection
		This is expected to get stuffed into the language connection
		context and accessed from there.

	 */
	DataValueFactory		getDataValueFactory(); 

	public CacheManager getStatementCache();

    public Parser newParser(CompilerContext cc);
}
