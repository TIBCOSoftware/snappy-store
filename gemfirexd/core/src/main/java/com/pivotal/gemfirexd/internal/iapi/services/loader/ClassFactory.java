/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory

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

package com.pivotal.gemfirexd.internal.iapi.services.loader;


import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.util.ByteArray;
import com.pivotal.gemfirexd.internal.impl.services.reflect.JarLoader;

import java.io.ObjectStreamClass;


/**
	A class factory module to handle application classes
	and generated classes.
*/

// GemStone changes BEGIN
public interface ClassFactory extends
    com.gemstone.gemfire.internal.ClassPathLoader.ClassLoaderInterface {
/* (original code)
public interface ClassFactory {
*/
// GemStone changes END

	/**
		Add a generated class to the class manager's class repository.

		@exception 	StandardException	Standard Derby error policy

	*/
	public GeneratedClass loadGeneratedClass(String fullyQualifiedName, ByteArray classDump)
		throws StandardException;

	/**
		Return a ClassInspector object
	*/
	public ClassInspector	getClassInspector();

	/**
		Load an application class, or a class that is potentially an application class.

		@exception ClassNotFoundException Class cannot be found, or
		a SecurityException or LinkageException was thrown loading the class.
	*/
	public Class loadApplicationClass(String className)
		throws ClassNotFoundException;

	/**
		Load an application class, or a class that is potentially an application class.

		@exception ClassNotFoundException Class cannot be found, or
		a SecurityException or LinkageException was thrown loading the class.
	*/
	public Class loadApplicationClass(ObjectStreamClass classDescriptor)
		throws ClassNotFoundException;



	/**
	 Load an application class, or a class that is potentially an application class.

	 @exception ClassNotFoundException Class cannot be found, or
	 a SecurityException or LinkageException was thrown loading the class.
	 */
	public Class loadClassFromDB(String className)
			throws ClassNotFoundException;
	/**
		Was the passed in class loaded by a ClassManager.

		@return true if the class was loaded by a Derby class manager,
		false it is was loaded by the system class loader, or another class loader.
	*/
	public boolean isApplicationClass(Class theClass);

	/**
		Notify the class manager that a jar file has been modified.
		@param reload Restart any attached class loader

		@exception StandardException thrown on error
	*/
	public void notifyModifyJar(boolean reload) throws StandardException ;

	/**
		Notify the class manager that the classpath has been modified.

		@exception StandardException thrown on error
	*/
	public void notifyModifyClasspath(String classpath) throws StandardException ;

// GemStone changes BEGIN
	public static int JAR_ADD_OP_TYPE = 0;
	public static int JAR_REMOVE_OP_TYPE = 1;
	public static int JAR_REPLACE_OP_TYPE = 2;

	public void notifyModifyClasspath(String classpath, int jar_op_type);

	public void releaseUpdateLoaderLock();

	public JarLoader closeJarLoader(String schemaName, String sqlName);
// GemStone changes END
	/**
		Return the in-memory "version" of the class manager. The version
		is bumped everytime the classes are re-loaded.
	*/
	public int getClassLoaderVersion();
}
