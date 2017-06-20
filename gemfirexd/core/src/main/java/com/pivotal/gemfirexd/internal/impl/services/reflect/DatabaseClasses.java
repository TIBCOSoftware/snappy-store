/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.services.reflect.DatabaseClasses

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

package com.pivotal.gemfirexd.internal.impl.services.reflect;






import java.lang.reflect.Modifier;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ClassName;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.compiler.*;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FileUtil;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassInspector;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleSupportable;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.stream.HeaderPrintWriter;
import com.pivotal.gemfirexd.internal.iapi.sql.compile.CodeGeneration;
import com.pivotal.gemfirexd.internal.iapi.util.ByteArray;

import java.util.ArrayList;
import java.util.Properties;
import java.util.Hashtable;

import java.io.ObjectStreamClass;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;

/**

    An abstract implementation of the ClassFactory. This package can
	be extended to fully implement a ClassFactory. Implementations can
	differ in two areas, how they load a class and how they invoke methods
	of the generated class.

    <P>
	This class manages a hash table of loaded generated classes and
	their GeneratedClass objects.  A loaded class may be referenced
	multiple times -- each class has a reference count associated
	with it.  When a load request arrives, if the class has already
	been loaded, its ref count is incremented.  For a remove request,
	the ref count is decremented unless it is the last reference,
	in which case the class is removed.  This is transparent to users.

	@see com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory
*/

abstract class DatabaseClasses
	implements ClassFactory, ModuleControl
{
	/*
	** Fields
	*/

	private	ClassInspector	classInspector;
	private JavaFactory		javaFactory;

	private UpdateLoader		applicationLoader;

	/*
	** Constructor
	*/

	DatabaseClasses() {
	}

	/*
	** Public methods of ModuleControl
	*/

	public void boot(boolean create, Properties startParams)
		throws StandardException
	{

		classInspector = new ClassInspector(this);

		//
		//The ClassFactory runs per service (database) mode (booted as a service module after AccessFactory).
		//If the code that booted
		//us needs a per-database classpath then they pass in the classpath using
		//the runtime property BOOT_DB_CLASSPATH in startParams


		String classpath = null;
		if (startParams != null) {
			classpath = startParams.getProperty(Property.BOOT_DB_CLASSPATH);
		}

		if (classpath != null) {
			applicationLoader = new UpdateLoader(classpath, this, true,
                                                 true);
		}

		javaFactory = (JavaFactory) com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor.startSystemModule(com.pivotal.gemfirexd.internal.iapi.reference.Module.JavaFactory);
	}



	public void stop() {
		if (applicationLoader != null)
			applicationLoader.close();
// GemStone changes BEGIN
		// clear the GemFire class cache
		InternalDataSerializer.flushClassCache();
// GemStone changes END
	}

	/*
	**	Public methods of ClassFactory
	*/

	/**
		Here we load the newly added class now, rather than waiting for the
		findGeneratedClass(). Thus we are assuming that the class is going
		to be used sometime soon. Delaying the load would mean storing the class
		data in a file, this wastes cycles and compilcates the cleanup.

		@see ClassFactory#loadGeneratedClass

		@exception	StandardException Class format is bad.
	*/
	public final GeneratedClass loadGeneratedClass(String fullyQualifiedName, ByteArray classDump)
		throws StandardException {


			try {


				return loadGeneratedClassFromData(fullyQualifiedName, classDump);

			} catch (LinkageError le) {

			    WriteClassFile(fullyQualifiedName, classDump, le);

				throw StandardException.newException(SQLState.GENERATED_CLASS_LINKAGE_ERROR,
							le, fullyQualifiedName);

// GemStone changes BEGIN
			} catch (Error e) {
			  if (SystemFailure.isJVMFailureError(e)) {
			    WriteClassFile(fullyQualifiedName, classDump, e);
			    SystemFailure.initiateFailure(e);
			    // If this ever returns, rethrow the error. We're poisoned
			    // now, so don't let this thread continue.
			    throw e;
			  }
			  else {
			    throw e;
			  }
			}
		/* (original code)
    		} catch (VirtualMachineError vme) { // these may be beyond saving, but fwiw

			    WriteClassFile(fullyQualifiedName, classDump, vme);

			    throw vme;
		    }
		*/
// GemStone changes END

	}

    private static void WriteClassFile(String fullyQualifiedName, ByteArray bytecode, Throwable t) {

		// get the un-qualified name and add the extension
        int lastDot = fullyQualifiedName.lastIndexOf((int)'.');
        String filename = fullyQualifiedName.substring(lastDot+1,fullyQualifiedName.length()).concat(".class");

		Object env = Monitor.getMonitor().getEnvironment();
		File dir = env instanceof File ? (File) env : null;

		File classFile = FileUtil.newFile(dir,filename);

		// find the error stream
		HeaderPrintWriter errorStream = Monitor.getStream();

		try {
			FileOutputStream fis = new FileOutputStream(classFile);
			fis.write(bytecode.getArray(),
				bytecode.getOffset(), bytecode.getLength());
			fis.flush();
			if (t!=null) {				
				errorStream.printlnWithHeader(MessageService.getTextMessage(MessageId.CM_WROTE_CLASS_FILE, fullyQualifiedName, classFile, t));
			}
			fis.close();
		} catch (IOException e) {
			if (SanityManager.DEBUG)
				SanityManager.THROWASSERT("Unable to write .class file");
		}
	}

	public ClassInspector getClassInspector() {
		return classInspector;
	}


	public final Class loadApplicationClass(String className)
		throws ClassNotFoundException {
        
        if (className.startsWith("com.pivotal.gemfirexd.internal.")) {
            // Assume this is an engine class, if so
            // try to load from this class loader,
            // this ensures in strange class loader
            // environments we do not get ClassCastExceptions
            // when an engine class is loaded through a different
            // class loader to the rest of the engine.
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException cnfe)
            {
                // fall through to the code below,
                // could be client or tools class
                // in a different loader.
            }
        }
 
		Throwable loadError;
		try {
			try {
				return loadClassNotInDatabaseJar(className);
			} catch (ClassNotFoundException cnfe) {
				if (applicationLoader == null)
					throw cnfe;
// GemStone changes BEGIN
				// if DDL replay has not started then ignore
				final GemFireStore memStore = GemFireStore
				    .getBootingInstance();
				if (memStore == null || !(memStore
				    .initialDDLReplayInProgress()
				    || memStore.initialDDLReplayDone())) {
				  throw cnfe;
				}
// GemStone changes END
				Class c = applicationLoader.loadClass(className, true);
				if (c == null)
					throw cnfe;
				return c;
			}
		}
		catch (SecurityException se)
		{
			// Thrown if the class has been comprimised in some
			// way, e.g. modified in a signed jar.
			loadError = se;
		}
		catch (LinkageError le)
		{
			// some error linking the jar, again could
			// be malicious code inserted into a jar.
			loadError = le;
		}
		throw new ClassNotFoundException(className + " : " + loadError.getMessage());
	}

	abstract Class loadClassNotInDatabaseJar(String className)
		throws ClassNotFoundException;

	public final Class loadApplicationClass(ObjectStreamClass classDescriptor)
		throws ClassNotFoundException {
		return loadApplicationClass(classDescriptor.getName());
	}

	public final Class loadClassFromDB(String name) throws ClassNotFoundException {
		ClassNotFoundException cnfe = new ClassNotFoundException(name);
		if (applicationLoader == null) throw cnfe;
		// if DDL replay has not started then ignore
		final GemFireStore memStore = GemFireStore
				.getBootingInstance();
		if (memStore == null || !(memStore
				.initialDDLReplayInProgress()
				|| memStore.initialDDLReplayDone())) {
			throw cnfe;
		}
		Class c = applicationLoader.loadClass(name, true);
		if (c == null)
			throw cnfe;
		return c;

	}
	public boolean isApplicationClass(Class theClass) {

		return theClass.getClassLoader()
			instanceof JarLoader;
	}

	public void notifyModifyJar(boolean reload) throws StandardException  {
		if (applicationLoader != null) {
			applicationLoader.modifyJar(reload);
		}
	}

	/**
		Notify the class manager that the classpath has been modified.

		@exception StandardException thrown on error
	*/
	public void notifyModifyClasspath(String classpath) throws StandardException {

		if (applicationLoader != null) {
			applicationLoader.modifyClasspath(classpath);
		}
	}

// Gemstone changes BEGIN
	@Override
	public void notifyModifyClasspath(String name, int jar_op_type) {
	  if (applicationLoader != null) {
	    applicationLoader.modifyClasspath(name, jar_op_type);
	  }
	}

	@Override
	public void releaseUpdateLoaderLock() {
	  if (this.applicationLoader != null) {
	    this.applicationLoader.releaseUpdateLoaderLock();
	  }
	}

	@Override
	public JarLoader closeJarLoader(String schemaName, String sqlName) {
	  if (applicationLoader != null) {
	    return applicationLoader.close(schemaName, sqlName);
	  }
	  return null;
	}
// Gemstone changes END

	public int getClassLoaderVersion() {
		if (applicationLoader != null) {
			return applicationLoader.getClassLoaderVersion();
		}

		return -1;
	}

	public ByteArray buildSpecificFactory(String className, String factoryName)
		throws StandardException {

		ClassBuilder cb = javaFactory.newClassBuilder(this, CodeGeneration.GENERATED_PACKAGE_PREFIX,
			Modifier.PUBLIC | Modifier.FINAL, factoryName, "com.pivotal.gemfirexd.internal.impl.services.reflect.GCInstanceFactory");

		MethodBuilder constructor = cb.newConstructorBuilder(Modifier.PUBLIC);

		constructor.callSuper();
		constructor.methodReturn();
		constructor.complete();
		constructor = null;

		MethodBuilder noArg = cb.newMethodBuilder(Modifier.PUBLIC, ClassName.GeneratedByteCode, "getNewInstance");
		noArg.pushNewStart(className);
		noArg.pushNewComplete(0);
		noArg.methodReturn();
		noArg.complete();
		noArg = null;

		return cb.getClassBytecode();
	}

	/*
	** Class specific methods
	*/
	
	/*
	** Keep track of loaded generated classes and their GeneratedClass objects.
	*/

	abstract LoadedGeneratedClass loadGeneratedClassFromData(String fullyQualifiedName, ByteArray classDump); 
}
