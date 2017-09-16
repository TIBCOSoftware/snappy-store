/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.services.reflect.UpdateLoader

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

// GemStone changes BEGIN
// GemStone changes END


import java.io.InputStream;
import java.security.AccessController;
import java.util.Arrays;

import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.locks.DefaultGfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.impl.GfxdReentrantReadWriteLock;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.Module;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactory;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassFactoryContext;
import com.pivotal.gemfirexd.internal.iapi.services.loader.JarReader;
import com.pivotal.gemfirexd.internal.iapi.services.locks.C_LockFactory;
import com.pivotal.gemfirexd.internal.iapi.services.locks.CompatibilitySpace;
import com.pivotal.gemfirexd.internal.iapi.services.locks.Latch;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockFactory;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockOwner;
import com.pivotal.gemfirexd.internal.iapi.services.locks.ShExLockable;
import com.pivotal.gemfirexd.internal.iapi.services.locks.ShExQual;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.iapi.services.property.PersistentSet;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.services.stream.HeaderPrintWriter;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.depend.DependencyManager;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;

/**
 * UpdateLoader implements then functionality of
 * gemfirexd.distributedsystem.classpath. It manages the ClassLoaders
 * (instances of JarLoader) for each installed jar file.
 * Jar files are installed through the sqlj.install_jar procedure.
 * <BR>
 * Each JarLoader delegates any request through standard mechanisms
 * to load a class to this object, which will then ask each jarLoader in order of
 * gemfirexd.distributedsystem.classpath to load the class through an internal api.
 * This means if the third jar in gemfirexd.distributedsystem.classpath tries to load
 * a class, say from the class for a procedure's method making some
 * reference to it, then the request is delegated to UpdateLoader.
 * UpdateLoader will then try to load the class from each of the jars
 * in order of gemfirexd.distributedsystem.classpath using the jar's installed JarLoader.
 */
final class UpdateLoader implements LockOwner {
    
    /**
     * List of packages that Derby will not support being loaded
     * from an installed jar file.
     */
    private static final String[] RESTRICTED_PACKAGES = {
        // While loading java. classes is blocked by the standard
        // class loading mechanism, javax. ones are not. However
        // allowing database applications to override jvm classes
        // seems a bad idea.
        "javax.",
        
        // Allowing an application to possible override the engine's
        // own classes also seems dangerous.
        "com.pivotal.gemfirexd.",
    };

	private JarLoader[] jarList;
	private HeaderPrintWriter vs;
	private final ClassLoader myLoader;
	private boolean initDone;
	private String thisClasspath;
// GemStone changes BEGIN
	private final GfxdLockable classLoaderLock;
	/* (original derby code)
	private final LockFactory lf;
	private final ShExLockable classLoaderLock;
	*/
// GemStone changes END
	private int version;
    private boolean normalizeToUpper;
	private DatabaseClasses parent;
// GemStone changes BEGIN
	private final GfxdLockSet compat;
	/* private final CompatibilitySpace compat; */
// GemStone changes END

	private boolean needReload;
	private JarReader jarReader;

	UpdateLoader(String classpath, DatabaseClasses parent, boolean verbose, boolean normalizeToUpper) 
		throws StandardException {

        this.normalizeToUpper = normalizeToUpper;
		this.parent = parent;
// GemStone changes BEGIN
		this.compat = new GfxdLockSet(this, new GfxdLocalLockService(
		    "update-loader-service", new GfxdReentrantReadWriteLock(
		        "UpdateLoader", true), GfxdLockSet.MAX_VM_LOCKWAIT_VAL));
		/* (original derby code)
		lf = (LockFactory) Monitor.getServiceModule(parent, Module.LockFactory);
		compat = lf.createCompatibilitySpace(this);
		*/
// GemStone changes END

		if (verbose) {
			vs = Monitor.getStream();
		}
		
		myLoader = getClass().getClassLoader();

// GemStone changes BEGIN
		this.classLoaderLock = new DefaultGfxdLockable("UpdateLoader", null);
		/* this.classLoaderLock = new ClassLoaderLock(this); */
// GemStone changes END

		initializeFromClassPath(classpath);
	}

	private void initializeFromClassPath(String classpath) throws StandardException {

		final String[][] elements = IdUtil.parseDbClassPath(classpath);
		
		final int jarCount = elements.length;
// GemStone changes BEGIN
                final JarLoader[] prevJarList = this.jarList;
// GemStone changes END
		jarList = new JarLoader[jarCount];
			
        if (jarCount != 0) {
            // Creating class loaders is a restricted operation
            // so we need to use a privileged block.
            AccessController.doPrivileged
            (new java.security.PrivilegedAction(){
                
                public Object run(){    
    		      for (int i = 0; i < jarCount; i++) {
// GemStone changes BEGIN
    		        // copy from previous JarList if there is no change
    		        // for a JarLoader (#47783)
    		        if (prevJarList != null && prevJarList.length > 0) {
    		          for (JarLoader prevLoader : prevJarList) {
    		            if (!prevLoader.isInvalid() && Arrays.equals(
    		                prevLoader.name, elements[i])) {
    		              jarList[i] = prevLoader;
    		              break;
    		            }
    		          }
    		        }
    		        if (jarList[i] == null)
// GemStone changes END
    			     jarList[i] = new JarLoader(UpdateLoader.this, elements[i], vs);
    		      }
                  return null;
                }
            });
        }
		if (vs != null) {
// GemStone changes BEGIN
		  // no need to log if classpath is empty
		  if ((classpath != null && classpath.length() > 0)
		      || GemFireXDUtils.TraceApplicationJars) {
		    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
		        MessageService.getTextMessage(MessageId.CM_CLASS_LOADER_START,
		            classpath));
		  }
                  /* (original code)
			vs.println(MessageService.getTextMessage(MessageId.CM_CLASS_LOADER_START, classpath));
		  */
// GemStone changes END
		}
		
		thisClasspath = classpath;
		initDone = false;
	}

	/**
		Load the class from the class path. Called by JarLoader
        when it has a request to load a class to fulfill
        the sematics of gemfirexd.distributedsystem.classpath.
        <P>
        Enforces two restrictions:
        <UL>
        <LI> Do not allow classes in certain name spaces to be loaded
        from installed jars, see RESTRICTED_PACKAGES for the list.
        <LI> Referencing Derby's internal classes (those outside the
        public api) from installed is disallowed. This is to stop
        user defined routines bypassing security or taking advantage
        of security holes in Derby. E.g. allowing a routine to
        call a public method in derby would allow such routines
        to call public static methods for system procedures without
        having been granted permission on them, such as setting database
        properties.
        </UL>

		@exception ClassNotFoundException Class can not be found or
        the installed jar is restricted from loading it.
	*/
	Class loadClass(String className, boolean resolve) 
		throws ClassNotFoundException {

		JarLoader jl = null;

		boolean unlockLoader = false;
		try {
			unlockLoader = lockClassLoader(ShExQual.SH);

			synchronized (this) {

				if (needReload) {
					reload();
				}
			
				Class clazz = checkLoaded(className, resolve);
				if (clazz != null)
					return clazz;
                
                // Refuse to load classes from restricted name spaces
                // That is classes in those name spaces can be not
                // loaded from installed jar files.
                for (int i = 0; i < RESTRICTED_PACKAGES.length; i++)
                {
                    if (className.startsWith(RESTRICTED_PACKAGES[i]))
                        throw new ClassNotFoundException(className);
                }

				String jvmClassName = className.replace('.', '/').concat(".class");

				if (!initDone)
					initLoaders();

				for (int i = 0; i < jarList.length; i++) {

					jl = jarList[i];
					Class c = jl.loadClassData(className, jvmClassName, resolve);
					if (c != null) {
// GemStone changes BEGIN
				                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
				                      MessageService.getTextMessage(MessageId.CM_CLASS_LOAD,
				                          className, jl.getJarName()));
						  /* (original code)
                                                      if (vs != null)
							vs.println(MessageService.getTextMessage(MessageId.CM_CLASS_LOAD, className, jl.getJarName()));
						  */
// GemStone changes END

						return c;
					}
				}
			}
			return null;


		} catch (StandardException se) {
			throw new ClassNotFoundException(MessageService.getTextMessage(MessageId.CM_CLASS_LOAD_EXCEPTION, className, jl == null ? null : jl.getJarName(), se));
		} finally {
			if (unlockLoader) {
// GemStone changes BEGIN
			  releaseUpdateLoaderLock(false);
				/* lf.unlock(compat, this, classLoaderLock, ShExQual.SH); */
// GemStone changes END
			}
		}
	}

	InputStream getResourceAsStream(String name) {

		InputStream is = (myLoader == null) ?
			ClassLoader.getSystemResourceAsStream(name) :
			myLoader.getResourceAsStream(name);

		if (is != null)
			return is;

		// match behaviour of standard class loaders.
		// Fix for issue # SNAP-2026 . Closure cleaner reads classes as stream. This piece of code below
		// was returning null in such case. Hence commented to return proper stream.
/*		if (name.endsWith(".class"))
			return null;*/

		boolean unlockLoader = false;
		try {
			unlockLoader = lockClassLoader(ShExQual.SH);

			synchronized (this) {

				if (needReload) {
					reload();		
				}

				if (!initDone)
					initLoaders();

				for (int i = 0; i < jarList.length; i++) {

					JarLoader jl = jarList[i];

					is = jl.getStream(name);
					if (is != null) {
						return is;
					}
				}
			}
			return null;

		} catch (StandardException se) {
			return null;
		} finally {
			if (unlockLoader) {
// GemStone changes BEGIN
			  releaseUpdateLoaderLock(false);
				/* lf.unlock(compat, this, classLoaderLock, ShExQual.SH); */
// GemStone changes END
			}
		}
	}

	synchronized void modifyClasspath(String classpath)
		throws StandardException {

		// lock transaction classloader exclusively
// GemStone changes BEGIN
		final boolean unlockLoader = lockClassLoader(ShExQual.EX);
		try {
		/* (original code)
		lockClassLoader(ShExQual.EX);
		*/
// GemStone changes END
		version++;


		modifyJar(false);
		initializeFromClassPath(classpath);
// GemStone changes BEGIN
		} finally {
		  if (unlockLoader) {
		    releaseUpdateLoaderLock(true);
		  }
		}
// GemStone changes END
	}
	
// GemStone changes BEGIN

	void releaseUpdateLoaderLock() {
          final LanguageConnectionContext lcc = Misc
              .getLanguageConnectionContext();
          if (lcc == null || !lcc.skipLocks()) {
            // releasing all lock should always be done for local "compat"
            final GfxdLockSet lockSet = this.compat;
            if (GemFireXDUtils.TraceLock) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
                  "UpdateLoader: releasing all update loader locks for "
                  + lockSet + ", owner=" + lockSet.getOwner()
                  + ", skipLocks=" + (lcc != null ? lcc.skipLocks() : false));
            }
            lockSet.unlockAll(true, true);
          }
	}

	private void releaseUpdateLoaderLock(boolean forUpdate) {
	  final LanguageConnectionContext lcc = Misc
	      .getLanguageConnectionContext();
	  if (lcc == null || !lcc.skipLocks()) {
	    final GfxdLockSet lockSet = getLockSet();
	    if (GemFireXDUtils.TraceLock) {
	      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
	          "UpdateLoader: attempting release update loader lock for "
	          + lockSet + ", owner=" + lockSet.getOwner()
	          + ", skipLocks=" + (lcc != null ? lcc.skipLocks() : false));
	    }
	    lockSet.releaseLock(classLoaderLock, forUpdate, true);
	  }
	}

	private GfxdLockSet getLockSet() {
/*	  ClassFactoryContext cfc = (ClassFactoryContext)ContextService
	      .getContextOrNull(ClassFactoryContext.CONTEXT_ID);

	  // This method can be called from outside of the database
	  // engine, in which case tc will be null. In that case
	  // we lock the class loader only for the duration of
	  // the loadClass().
	  if (cfc != null) {
	    try {
	      final CompatibilitySpace lockSpace = cfc.getLockSpace();
	      if (lockSpace != null) {
	        return (GfxdLockSet)lockSpace;
	      }
	    } catch (StandardException se) {
	      // should never happen
	      throw GemFireXDRuntimeException.newRuntimeException(
	          "unexpected exception in getting lock set", se);
	    }
	  }*/
	  return this.compat;
	}

	synchronized void modifyClasspath(String classpath, int jar_op_type) {

          switch(jar_op_type) {
            case ClassFactory.JAR_ADD_OP_TYPE:
              if (this.thisClasspath == null || this.thisClasspath.isEmpty()) {
                this.thisClasspath = classpath;
              }
              else {
                this.thisClasspath += (':' + classpath);
              }
              break;

            case ClassFactory.JAR_REMOVE_OP_TYPE:
              if (this.thisClasspath != null && !this.thisClasspath.isEmpty()) {
                final StringBuilder sb = new StringBuilder(
                    this.thisClasspath.length());
                String[] classpaths = this.thisClasspath.split(":");
                for (String cp : classpaths) {
                  if (!cp.equals(classpath)) {
                    if (sb.length() > 0) {
                      sb.append(':');
                    }
                    sb.append(cp);
                  }
                }
                this.thisClasspath = sb.toString();
              }
              // GemStone changes BEGIN
              JarLoader jl = getJarLoaderForClassPath(classpath);
              if (jl != null) {
                jl.invalidateDependents(DependencyManager.DROP_JAR);
              }
              // GemStone changes END
              break;

            case ClassFactory.JAR_REPLACE_OP_TYPE:
            // GemStone changes BEGIN
              jl = getJarLoaderForClassPath(classpath);
              if (jl != null) {
                jl.invalidateDependents(DependencyManager.REPLACE_JAR);
              }
              // GemStone changes END
              break;

            default:
                throw new IllegalArgumentException(
                    "unknown jar op type=" + jar_op_type);
          }
        }

	private JarLoader getJarLoaderForClassPath(String cp) {
          for(JarLoader jl : this.jarList) {
            if (jl.getJarNameAsString().equals(cp)) {
              return jl;
            }
          }
          return null;
        }
	
	JarLoader close(String schemaName, String sqlName) {
	  String[] name;
	  for (JarLoader jarLoader : this.jarList) {
	    name = jarLoader.name;
	    if (name[0].equalsIgnoreCase(schemaName)
	        && name[1].equalsIgnoreCase(sqlName)) {
	      jarLoader.setInvalid();
	      return jarLoader;
	    }
	  }
	  return null;
	}
// GemStone changes END


	synchronized void modifyJar(boolean reload) throws StandardException {

		// lock transaction classloader exclusively
// GemStone changes BEGIN
		final boolean releaseLock = lockClassLoader(ShExQual.EX);
		try {
		/* (original code)
		lockClassLoader(ShExQual.EX);
		*/
// GemStone changes END
		version++;

		//if (!initDone)
		//	return;
        //thisClasspath = "app.sample2";
        // first close the existing jar file opens
// GemStone changes BEGIN
		// only modify for JarLoaders that have really gone away
		// else existing loaded classes by these JarLoaders
		// will all cause problems
        //close();
// GemStone changes END

		if (reload) {
			initializeFromClassPath(thisClasspath);
		}
// GemStone changes BEGIN
		} finally {
		  if (releaseLock) {
		    releaseUpdateLoaderLock(true);
		  }
		}
// GemStone changes END
	}

	private boolean lockClassLoader(ShExQual qualifier)
		throws StandardException {

// GemStone changes BEGIN
	  final GfxdLockSet lockSet = getLockSet();
	  final Object lockGroup = lockSet.getOwner();
	  if (GemFireXDUtils.TraceLock) {
	    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
	        "UpdateLoader: acquiring update loader lock for "
	            + lockSet + ", owner=" + lockGroup);
	  }
	  final LanguageConnectionContext lcc = Misc
	      .getLanguageConnectionContext();
	  if (lcc == null || !lcc.skipLocks()) {
	    return lockSet.acquireLock(this.classLoaderLock, GfxdLockSet
	        .MAX_LOCKWAIT_VAL, qualifier == ShExQual.EX, true, false) !=
	        GfxdLockSet.LOCK_FAIL;
	  }
	  else {
	    return false;
	  }
	  /* (original derby code)
		if (lf == null)
			return false;

		ClassFactoryContext cfc = (ClassFactoryContext) ContextService.getContextOrNull(ClassFactoryContext.CONTEXT_ID);

		// This method can be called from outside of the database
		// engine, in which case tc will be null. In that case
		// we lock the class loader only for the duration of
		// the loadClass().
		CompatibilitySpace lockSpace = null;
		
		if (cfc != null) {
			lockSpace = cfc.getLockSpace();
		}
		if (lockSpace == null)
			lockSpace = compat;

		Object lockGroup = lockSpace.getOwner();

		lf.lockObject(lockSpace, lockGroup, classLoaderLock, qualifier,
					  C_LockFactory.TIMED_WAIT);

		return (lockGroup == this);
	  */
// GemStone changes END
	}

	Class checkLoaded(String className, boolean resolve) {

		for (int i = 0; i < jarList.length; i++) {
			Class c = jarList[i].checkLoaded(className, resolve);
			if (c != null)
				return c;
		}
		return null;
	}

	void close() {

		for (int i = 0; i < jarList.length; i++) {
			jarList[i].setInvalid();
		}

	}

	private void initLoaders() {

		if (initDone)
			return;

		for (int i = 0; i < jarList.length; i++) {
			jarList[i].initialize();
		}
		initDone = true;
	}

	int getClassLoaderVersion() {
		return version;
	}

	synchronized void needReload() {
		version++;
		needReload = true;
	}

	private void reload() throws StandardException {
		thisClasspath = getClasspath();
		// first close the existing jar file opens
		close();
		initializeFromClassPath(thisClasspath);
		needReload = false;
	}


	private String getClasspath()
		throws StandardException {

		ClassFactoryContext cfc = (ClassFactoryContext) ContextService.getContextOrNull(ClassFactoryContext.CONTEXT_ID);

		PersistentSet ps = cfc.getPersistentSet();
		
		String classpath = PropertyUtil.getServiceProperty(ps, Property.DATABASE_CLASSPATH);

		//
		//In per database mode we must always have a classpath. If we do not
		//yet have one we make one up.
		if (classpath==null)
			classpath="";


		return classpath;
	}

	JarReader getJarReader() {
		if (jarReader == null) {

			ClassFactoryContext cfc = (ClassFactoryContext) ContextService.getContextOrNull(ClassFactoryContext.CONTEXT_ID);

			jarReader = cfc.getJarReader(); 
		}
		return jarReader;
	}

    /**
     * Tell the lock manager that we don't want timed waits to time out
     * immediately.
     *
     * @return {@code false}
     */
    public boolean noWait() {
        return false;
    }
}


class ClassLoaderLock extends ShExLockable {

	private final UpdateLoader myLoader;

	ClassLoaderLock(UpdateLoader myLoader) {
		this.myLoader = myLoader;
	}

	public void unlockEvent(Latch lockInfo)
	{
		super.unlockEvent(lockInfo);

		if (lockInfo.getQualifier().equals(ShExQual.EX)) {
			// how do we tell if we are reverting or not
			myLoader.needReload();
		}
	}
}
