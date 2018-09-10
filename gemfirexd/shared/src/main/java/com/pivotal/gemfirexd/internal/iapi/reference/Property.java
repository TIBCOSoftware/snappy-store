/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.reference.Property

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

package com.pivotal.gemfirexd.internal.iapi.reference;

import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;

/**
	List of all properties understood by the system. It also has some other static fields.


	<P>
	This class exists for two reasons
	<Ol>
	<LI> To act as the internal documentation for the properties. 
	<LI> To remove the need to declare a java static field for the property
	name in the protocol/implementation class. This reduces the footprint as
	the string is final and thus can be included simply as a String constant pool entry.
	</OL>
	<P>
	This class should not be shipped with the product.

	<P>
	This class has no methods, all it contains are String's which by
	are public, static and final since they are declared in an interface.
*/

public interface Property { 

	/**
		By convention properties that must not be stored any persistent form of
		service properties start with this prefix.
	*/
	public static final String PROPERTY_RUNTIME_PREFIX = "gemfirexd.__rt.";

        public final String PROP_SQLF_BC_MODE_INDICATOR = Property.PROPERTY_RUNTIME_PREFIX
            + "isSQLFire";
	
	/**
	 * Indicates whether this is an admin system.
	 */
	public static final String PROPERTY_GEMFIREXD_ADMIN = PROPERTY_RUNTIME_PREFIX + "admin";
	
        /**
         * Indicates a DRDA internal connection.
         */
        public static final String DRDA_CLIENT_CONNECTION = PROPERTY_RUNTIME_PREFIX
            + "drdaClientConnection";
      
	/*
	** gemfirexd.service.* and related properties
	*/


	/*
	** gemfirexd.stream.* and related properties
	*/
	
	/**
		gemfirexd.stream.error.logSeverityLevel=integerValue
		<BR>
		Indicates the minimum level of severity for errors that are reported to the error stream.
		Default to 0 in a "sane" server, and SESSION_SEVERITY in the insane (and product) server.

		@see com.pivotal.gemfirexd.internal.iapi.error.ExceptionSeverity#SESSION_SEVERITY
	*/
	final static String LOG_SEVERITY_LEVEL = "gemfirexd.stream.error.logSeverityLevel";

        /**
		gemfirexd.stream.error.file=<b>absolute or relative error log filename</b>
		Takes precendence over gemfirexd.stream.error.method.
		Takes precendence over gemfirexd.stream.error.field
	*/
	
	final static String ERRORLOG_FILE_PROPERTY = "gemfirexd.stream.error.file";

        /**
		gemfirexd.stream.error.method=
			<className>.<methodName> returning an OutputStream or Writer object
		Takes precendence over gemfirexd.stream.error.field
	*/
	
	final static String ERRORLOG_METHOD_PROPERTY = "gemfirexd.stream.error.method";

        /**
		gemfirexd.stream.error.field=
			<className>.<fieldName> returning an OutputStream or Writer object>
	*/
	
	final static String ERRORLOG_FIELD_PROPERTY = "gemfirexd.stream.error.field";

	/** 
	gemfirexd.infolog.append={true,false}
	<BR>
	* If the info stream goes to a file and the file already exist, it can
	* either delete the existing file or append to it.  User can specifiy
	* whether info log file should append or not by setting
	* gemfirexd.infolog.append={true/false}
	*
	* The default behavior is that the exiting file will be deleted when a new
	* info stream is started.  
	*/
	final static String LOG_FILE_APPEND = "gemfirexd.infolog.append";

	/*
	** gemfirexd.service.* and related properties
	*/
	/**
		gemfirexd.system.home
		<BR>
		Property name for the home directory. Any relative path in the
		system should be accessed though this property
	*/
	final static String SYSTEM_HOME_PROPERTY = "gemfirexd.system.home";

	/**
		gemfirexd.system.bootAll
		<BR>
		Automatically boot any services at start up time. When set to true
		this services will  be booted at startup, otherwise services
		will be booted on demand.
	*/
	final static String BOOT_ALL = "gemfirexd.system.bootAll";

	/**
		gemfirexd.distributedsystem.noAutoBoot
		<BR>
		Don't automatically boot this service at start up time. When set to true
		this service will only be booted on demand, otherwise the service
		will be booted at startup time if possible.
	*/
	final static String NO_AUTO_BOOT = "gemfirexd.distributedsystem.noAutoBoot";
    
	/**
		gemfirexd.__deleteOnCreate
		<BR>
		Before creating this service delete any remenants (e.g. the directory)
		of a previous service at the same location.

		<P>
		<B>INTERNAL USE ONLY</B> 
	*/
	final static String DELETE_ON_CREATE = "gemfirexd.__deleteOnCreate";

	/**
        gemfirexd.distributedsystem.forceDatabaseLock
		<BR>
        Derby attempts to prevent two instances of Derby from booting
        the same database with the use of a file called db.lck inside the 
        database directory.

        On some platforms, Derby can successfully prevent a second 
        instance of Derby from booting the database, and thus prevents 
        corruption. If this is the case, you will see an SQLException like the
        following:

        ERROR XJ040: Failed to start database 'toursDB', see the next exception
        for details.
        ERROR XSDB6: Another instance of Derby may have already booted the
        database C:\databases\toursDB.

        The error is also written to the information log.

        On other platforms, Derby issues a warning message if an instance
        of Derby attempts to boot a database that may already have a
        running instance of Derby attached to it.
        However, it does not prevent the second instance from booting, and thus
        potentially corrupting, the database.

        If a warning message has been issued, corruption may already have 
        occurred.


        The warning message looks like this:

        WARNING: Derby (instance 80000000-00d2-3265-de92-000a0a0a0200) is
        attempting to boot the database /export/home/sky/wombat even though
        Derby (instance 80000000-00d2-3265-8abf-000a0a0a0200) may still be
        active. Only one instance of Derby 
        should boot a database at a time. Severe and non-recoverable corruption
        can result and may have already occurred.

        The warning is also written to the information log.

        This warning is primarily a Technical Support aid to determine the 
        cause of corruption. However, if you see this warning, your best 
        choice is to close the connection and exit the JVM. This minimizes the
        risk of a corruption. Close all instances of Derby, then restart
        one instance of Derby and shut down the database properly so that
        the db.lck file can be removed. The warning message continues to appear
        until a proper shutdown of the Derby system can delete the db.lck
        file.

        If the "gemfirexd.distributedsystem.forceDatabaseLock" property is set to true
        then this default behavior is altered on systems where Derby cannot
        prevent this dual booting.  If the to true, then if the platform does
        not provide the ability for Derby to guarantee no double boot, and
        if Derby finds a db.lck file when it boots, it will throw an 
        exception (TODO - mikem - add what exception), leave the db.lck file
        in place and not boot the system.  At this point the system will not 
        boot until the db.lck file is removed by hand.  Note that this 
        situation can arise even when 2 VM's are not accessing the same
        Derby system.  Also note that if the db.lck file is removed by 
        hand while a VM is still accessing a gemfirexd.distributedsystem, then there 
        is no way for Derby to prevent a second VM from starting up and 
        possibly corrupting the database.  In this situation no warning 
        message will be logged to the error log.

        To disable the default behavior of the db.lck file set property as 
        follows:

        gemfirexd.distributedsystem.forceDatabaseLock=true

	*/
	final static String FORCE_DATABASE_LOCK = "gemfirexd.distributedsystem.forceDatabaseLock";


	/*
	** gemfirexd.locks.* and related properties
	*/

	final static String LOCKS_INTRO = "gemfirexd.locks.";

	/**
		gemfirexd.locks.escalationThreshold
		<BR>
		The number of row locks on a table after which we escalate to
		table locking.  Also used by the optimizer to decide when to
		start with table locking.  The String value must be convertible
		to an int.
	 */
	final static String LOCKS_ESCALATION_THRESHOLD = "gemfirexd.locks.escalationThreshold";

	/**
		The default value for LOCKS_ESCALATION_THRESHOLD
	 */
	final static int DEFAULT_LOCKS_ESCALATION_THRESHOLD = 5000;

	/**
		The minimum value for LOCKS_ESCALATION_THRESHOLD
	 */
	final static int MIN_LOCKS_ESCALATION_THRESHOLD = 100;

	/**
		Configuration parameter for deadlock timeouts, set in seconds.
	*/
	public static final String DEADLOCK_TIMEOUT = "gemfirexd.locks.deadlockTimeout";

	/**
		Default value for deadlock timesouts (20 seconds)
	*/
	public static final int DEADLOCK_TIMEOUT_DEFAULT = 20;

	/**
		Default value for wait timeouts (60 seconds)
	*/
	public static final int WAIT_TIMEOUT_DEFAULT = 60;

	/**
		Turn on lock monitor to help debug deadlocks.  Default value is OFF.
		With this property turned on, all deadlocks will cause a tracing to be
		output to the db2j.LOG file.
		<BR>
		This property takes effect dynamically.
	 */
	public static final String DEADLOCK_MONITOR = "gemfirexd.locks.monitor";

	/**
		Turn on deadlock trace to help debug deadlocks.
        
        Effect 1: This property only takes effect if DEADLOCK_MONITOR is turned
        ON for deadlock trace.  With this property turned on, each lock object
        involved in a deadlock will output its stack trace to db2j.LOG.
        
        Effect 2: When a timeout occurs, a lockTable dump will also be output
        to db2j.LOG.  This acts independent of DEADLOCK_MONITOR.
		<BR>
		This property takes effect dynamically.
	 */
	public static final String DEADLOCK_TRACE = "gemfirexd.locks.deadlockTrace";

	/**
		Configuration parameter for lock wait timeouts, set in seconds.
	*/
	public static final String LOCKWAIT_TIMEOUT = "gemfirexd.locks.waitTimeout";

	/*
	** db2j.database.*
	*/
	
	/**
		gemfirexd.distributedsystem.classpath
		<BR>
		Consists of a series of two part jar names.
	*/
	final static String DATABASE_CLASSPATH = "gemfirexd.distributedsystem.classpath";

	/**
		internal use only, passes the database classpathinto the class manager
	*/
	final static String BOOT_DB_CLASSPATH = PROPERTY_RUNTIME_PREFIX + "distributedsystem.classpath";



	/**
		gemfirexd.distributedsystem.propertiesOnly
	*/
	final static String DATABASE_PROPERTIES_ONLY = "gemfirexd.distributedsystem.propertiesOnly";

    /**
     * Ths property is private to Derby.
     * This property is forcibly set by the Network Server to override
     * any values which the user may have set. This property is only used to
     * parameterize the Basic security policy used by the Network Server.
     * This property is the location of the derby jars.
     **/
    public static final String DERBY_INSTALL_URL = "gemfirexd.install.url";

    /**
     * Ths property is private to Derby.
     * This property is forcibly set by the Network Server to override
     * any values which the user may have set. This property is only used to
     * parameterize the Basic security policy used by the Network Server.
     * This property is the hostname which the server uses.
     **/
    public static final String DERBY_SECURITY_HOST = "gemfirexd.security.host";

	/*
	** gemfirexd.storage.*
	*/

    /**
     * Creation of an access factory should be done with no logging.
	 * This is a run-time property that should not make it to disk
	 * in the service.properties file.
     **/
	public static final String CREATE_WITH_NO_LOG =
		PROPERTY_RUNTIME_PREFIX + "storage.createWithNoLog";

    /**
     * The page size to create a table or index with.  Must be a multiple
     * of 2k, usual choices are: 2k, 4k, 8k, 16k, 32k, 64k.  The default
     * if property is not set is 4k.
     **/
    public static final String PAGE_SIZE_PARAMETER = "gemfirexd.storage.pageSize";

    /**
     * The default page size to use for tables that contain a long column.
     **/
    public static final String PAGE_SIZE_DEFAULT_LONG = "32768";

    /**
     * The bump threshold for pages sizes for create tables
     * If the approximate column sizes of a table is greater than this
     * threshold, the page size for the tbl is bumped to PAGE_SIZE_DEFAULT_LONG
     * provided the page size is not already specified as a property
     **/
    public static final int TBL_PAGE_SIZE_BUMP_THRESHOLD = 4096;

    /**
     * The bump threshold for pages size for index.
     * If the approximate key columns of an index is greater than this
     * threshold, the page size for the index is bumped to PAGE_SIZE_DEFAULT_LONG
     * provided the page size is not already specified as a property
     **/
    public static final int IDX_PAGE_SIZE_BUMP_THRESHOLD = 1024;

    /**
     * Derby supports Row Level Locking (rll),  but you can use this 
     * property to disable rll.  Applications which use rll will use more 
     * system resources, so if an application knows that it does not need rll 
     * then it can use this system property to force all locking in the system 
     * to lock at the table level.
     * 
     * This property can be set to the boolean values "true" or "false".  
     * Setting the property to true is the same as not setting the property at 
     * all, and will result in rll being enabled.  Setting the property to 
     * false disables rll.
     *
     **/
	public static final String ROW_LOCKING = "gemfirexd.storage.rowLocking";

	/**
		gemfirexd.storage.propertiesId
		<BR>
		Stores the id of the conglomerate that holds the per-database
		properties. Is stored in the service.properties file.

		<P>
		<B>INTERNAL USE ONLY</B> 
	*/
	final static String PROPERTIES_CONGLOM_ID = "gemfirexd.storage.propertiesId";

	/**
		gemfirexd.storage.tempDirectory
		<BR>
		Sets the temp directory for a database.
		<P>
	*/
	final static String STORAGE_TEMP_DIRECTORY = "gemfirexd.storage.tempDirectory";
	
    /**
     * gemfirexd.system.durability
     * <p>
     * Currently the only valid supported case insensitive value is 'test' 
     * Note, if this property is set to any other value other than 'test', this 
     * property setting is ignored
     * 
     * In the future, this property can be used to set different modes - for 
     * example a form of relaxed durability where database can recover to a 
     * consistent state, or to enable some kind of in-memory mode.
     * <BR>
     * When set to 'test', the store system will not force sync calls in the 
     * following cases  
     * - for the log file at each commit
     * - for the log file before data page is forced to disk
     * - for page allocation when file is grown
     * - for data writes during checkpoint
     * 
     * That means
     * - a commit no longer guarantees that the transaction's modification
     *   will survive a system crash or JVM termination
     * - the database may not recover successfully upon restart
     * - a near full disk at runtime may cause unexpected errors
     * - database can be in an inconsistent state
     * <p>
     * This setting is provided for performance reasons and should ideally
     * only be used when the system can withstand the above consequences.
     * <BR> 
     * One sample use would be to use this mode (gemfirexd.system.durability=test)
     * when using Derby as a test database, where high performance is required
     * and the data is not very important
     * <BR>
     * Valid supported values are test
     * <BR>
     * Example
     * gemfirexd.system.durability=test
     * One can set this as a command line option to the JVM when starting the
     * application or in the gemfirexd.properties file. It is a system level 
     * property.
     * <BR>
     * This property is static; if you change it while Derby is running, 
     * the change does not take effect until you reboot.  
     */
	public static final String DURABILITY_PROPERTY = 
        "gemfirexd.system.durability";
 	
    /**
     * This is a value supported for gemfirexd.system.durability
     * When gemfirexd.system.durability=test, the storage system does not
     * force syncs and the system may not recover. It is also possible that
     * the database might be in an inconsistent state
     * @see #DURABILITY_PROPERTY
     */
    public static final String DURABILITY_TESTMODE_NO_SYNC = "test";
    
	/**
     * gemfirexd.storage.fileSyncTransactionLog
     * <p>
     * When set, the store system will use sync() call on the log at 
     * commit instead of doing  a write sync on all writes to  the log;
	 * even if the write sync mode (rws) is supported in the JVM. 
     * <p>
     *
     **/
	public static final String FILESYNC_TRANSACTION_LOG = 
        "gemfirexd.storage.fileSyncTransactionLog";


	/**
	 *	gemfirexd.storage.logArchiveMode
	 *<BR>
	 *used to identify whether the log is being archived for the database or not.
	 *  It Is stored in the service.properties file.
	 * 
     * This property can be set to the boolean values "true" or "false".  
     * Setting the property to true means log is being archived, which could be 
	 * used for roll-forward recovery. Setting the property to 
     * false disables log archive mode.
	 *<P>
	 *<B>INTERNAL USE ONLY</B> 
	 */
	final static String LOG_ARCHIVE_MODE = "gemfirexd.storage.logArchiveMode";


	/**
	 *	gemfirexd.storage.logDeviceWhenBackedUp
	 *<BR>
	 *  This property indicates the logDevice location(path) when the backup was 
	 *  taken, used to restore the log to the same location while restoring from
	 *  backup.
	 *<P>
	 *<B>INTERNAL USE ONLY</B> 
	 */
	final static String LOG_DEVICE_AT_BACKUP = "gemfirexd.storage.logDeviceWhenBackedUp";
    
    /**
     * gemfirexd.module.modulename
     * <P>
     * Defines a new module. Modulename is a name used when loading the definition
     * of a module, it provides the linkage to other properties used to define the
     * module, gemfirexd.env.jdk.modulename and gemfirexd.env.classes.modulename.
     * 
     * The value is a Java class name that implements functionality required by
     * the other parts of a Derby system or database. The class can optionally implement
     * these classes to control its use and startup.
     * <UL>
     * <LI> com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleControl
     * <LI> com.pivotal.gemfirexd.internal.iapi.services.monitor.ModuleSupportable
     * </UL>
     */
	final static String MODULE_PREFIX = "gemfirexd.module.";

    /**
     *  gemfirexd.subSubProtocol.xxx
     *<p>
     *
     * A new subsubprotocol can be defined by specifying the class that handles storage for the
     * subsubprotocol by implementing the
     * {@link com.pivotal.gemfirexd.internal.io.StorageFactory StorageFactory} or
     * {@link com.pivotal.gemfirexd.internal.io.WritableStorageFactory WritableStorageFactory} interface. This
     * is done using a property named db2j.subsubprotocol.<i>xxx</i> where <i>xxx</i> is the subsubprotocol name.
     * Subsubprotocol names are case sensitive and must be at least 3 characters in length.
     *<p>
     *
     * For instance:
     *<br>
     * gemfirexd.subSubProtocol.mem=com.mycompany.MemStore
     *<br>
     * defines the "mem" subsubprotocol with class com.mycompany.MemStore as its StorageFactory implementation.
     * A database implemented using this subsubprotocol can be opened with the URL "jdbc:derby:mem:myDatabase".
     *<p>
     *
     * Subsubprotocols "directory", "classpath", "jar", "http", and "https" are built in and may not be overridden.
     */
	final static String SUB_SUB_PROTOCOL_PREFIX = "gemfirexd.subSubProtocol.";
    
    
    /**
     * Declare a minimum JDK level the class for a module or sub sub protocol supports.
     * Set to an integer value from the JVMInfo class to represent a JDK.
     * If the JDK is running at a lower level than the class requires
     * then the class will not be loaded and will not be used.
     * 
     * If there are multiple modules classes implementing the same functionality
     * and supported by the JVM, then the one with the highest JDK
     * requirements will be selected. This functionality is not present for
     * sub sub protocol classes yet.
     * 
     * See com.pivotal.gemfirexd.internal.iapi.services.info.JVMInfo.JDK_ID
     */
	final static String MODULE_ENV_JDK_PREFIX = "gemfirexd.env.jdk.";

    /**
     * Declare a set of classes that the class for a module or sub sub protocol requires.
     * Value is a comma separated list of classes. If the classes listed are not
     * loadable by the virtual machine then the module class will not be loaded and will not be used.
    */
	final static String MODULE_ENV_CLASSES_PREFIX = "gemfirexd.env.classes.";

    /*
	** gemfirexd.language.*
	*/

	/**
	 * The size of the table descriptor cache used by the
	 * data dictionary.  Database.  Static.
	 * <p>
	 * Undocumented.
	 */
	final static String	LANG_TD_CACHE_SIZE = "gemfirexd.language.tableDescriptorCacheSize";
	final static int		LANG_TD_CACHE_SIZE_DEFAULT = 64;

    /**
     * The size of the permissions cache used by the data dictionary.
     * Database.  Static.
	 * <p>
	 * Undocumented.
	 */
	final static String	LANG_PERMISSIONS_CACHE_SIZE = "gemfirexd.language.permissionsCacheSize";
	final static int		LANG_PERMISSIONS_CACHE_SIZE_DEFAULT = 64;
	/**
	 * The size of the stored prepared statment descriptor cache 
	 * used by the data dictionary.  Database.  Static.
	 * <p>
	 * Externally visible.
	 */
	final static String	LANG_SPS_CACHE_SIZE = "gemfirexd.language.spsCacheSize";
	final static int		LANG_SPS_CACHE_SIZE_DEFAULT =32;

	/**
	  gemfirexd.language.stalePlanCheckInterval

	  <P>
	  This property tells the number of times a prepared statement should
	  be executed before checking whether its plan is stale.  Database.
	  Dynamic.
	  <P>
	  Externally visible.
	 */
	final static String LANGUAGE_STALE_PLAN_CHECK_INTERVAL =
								"gemfirexd.language.stalePlanCheckInterval";

	
	/** Default value for above */
	final static int DEFAULT_LANGUAGE_STALE_PLAN_CHECK_INTERVAL = 100;

	/** Minimum value for above */
	final static int MIN_LANGUAGE_STALE_PLAN_CHECK_INTERVAL = 5;


	/*
		Statement plan cache size
		By default, 100 statements are cached
	 */
	final static String STATEMENT_CACHE_SIZE = "gemfirexd.language.statementCacheSize";
	final static int STATEMENT_CACHE_SIZE_DEFAULT = 100;

        final static String STATEMENT_EXPLAIN_MODE = "gemfirexd.distributedsystem.statement-explain-mode";
        final static String STATEMENT_STATISTICS_MODE = "gemfirexd.distributedsystem.statement-statistics-mode";
        final static String STATISTICS_SUMMARY_MODE = "gemfirexd.distributedsystem.statistics-summary-mode";

	/*
	** Transactions
	*/

    /** The property name used to get the default value for XA transaction
      * timeout in seconds. Zero means no timout.
      */
	final static String PROP_XA_TRANSACTION_TIMEOUT = "gemfirexd.jdbc.xaTransactionTimeout";

    /** The default value for XA transaction timeout if the corresponding
      * property is not found in system properties. Zero means no timeout.
      */
	final static int DEFAULT_XA_TRANSACTION_TIMEOUT = 0;


  /* some static fields */
	public static final String DEFAULT_USER_NAME = "APP";
	public static final String DATABASE_MODULE = "com.pivotal.gemfirexd.internal.database.Database";

	public static final String NO_ACCESS = "NOACCESS";
	public static final String READ_ONLY_ACCESS = "READONLYACCESS";
	public static final String FULL_ACCESS = "FULLACCESS";

    // This is the property that turn on/off authentication
	public static final String REQUIRE_AUTHENTICATION_PARAMETER =
								"gemfirexd.authentication.required";

        public static final String GFXD_SERVER_AUTH_PROVIDER = Attribute.GFXD_PREFIX
            + Attribute.SERVER_AUTH_PROVIDER;

        public static final String GFXD_AUTH_PROVIDER = Attribute.GFXD_PREFIX
            + Attribute.AUTH_PROVIDER;

        public static final String SQLF_SERVER_AUTH_PROVIDER = Attribute.SQLF_PREFIX
            + Attribute.SERVER_AUTH_PROVIDER;

        public static final String SQLF_AUTH_PROVIDER = Attribute.SQLF_PREFIX
            + Attribute.AUTH_PROVIDER;

  String SNAPPY_ENABLE_RLS = "snappydata.enable-rls";

  String SNAPPY_RESTRICT_TABLE_CREATE = "snappydata.RESTRICT_TABLE_CREATION";

  // GFXD HDFS PROPERTIES FOR EMBEDDED MODE - START
        
  public static final String HADOOP_GFXD_LONER = 
      "hadoop.gemfirexd.loner.";
  
  /**
   * Property if set to "true" indicates that the GFXD instance is running in embedded 
   * mode in a MapReduce/PXF job. 
   */
  public static final String HADOOP_IS_GFXD_LONER = HADOOP_GFXD_LONER + "mode";

  /**
   * HDFS NAMENODE URL of the HDFS Stores for the embedded mode. 
   */
  public static final String GFXD_HD_NAMENODEURL = HADOOP_GFXD_LONER + "namenodeurl";
  
  /**
   * HDFS home directories separated by commans of the HDFS Stores which will be processed in 
   * the embedded mode  
   */
  public static final String GFXD_HD_HOMEDIR = HADOOP_GFXD_LONER + "homedirs";
  
  /** 
   * Prefix for hadoop properties
   */
  public static final String HADOOP_GFXD_LONER_PROPS_PREFIX = 
      HADOOP_GFXD_LONER + "props.";
  
  // GFXD HDFS PROPERTIES FOR EMBEDDED MODE - END
  
	// GemStone changes END
	// This is the user property used by Derby and LDAP schemes
	public static final String USER_PROPERTY_PREFIX = "gemfirexd.user.";

        public static final String SQLF_USER_PROPERTY_PREFIX = "sqlfire.user.";
        
	// These are the different built-in providers gemfirexd supports

	/**
		Property name for specifying log switch interval
	 */
	public static final String LOG_SWITCH_INTERVAL = "gemfirexd.storage.logSwitchInterval";

	/**
		Property name for specifying checkpoint interval
	 */
	public static final String CHECKPOINT_INTERVAL = "gemfirexd.storage.checkpointInterval";

	/**
		Property name for specifying log archival location
	 */
	public static final String LOG_ARCHIVAL_DIRECTORY = "gemfirexd.storage.logArchive";

	/**
		Property name for specifying log Buffer Size
	 */
	public static final String LOG_BUFFER_SIZE = "gemfirexd.storage.logBufferSize";
	
	
	/*
	** Replication
	*/

	/** Property name for specifying the size of the replication log buffers */
	public static final String REPLICATION_LOG_BUFFER_SIZE= "gemfirexd.replication.logBufferSize";

	/** Property name for specifying the minimum log shipping interval*/
	public static final String REPLICATION_MIN_SHIPPING_INTERVAL = "gemfirexd.replication.minLogShippingInterval";

	/** Property name for specifying the maximum log shipping interval*/
	public static final String REPLICATION_MAX_SHIPPING_INTERVAL = "gemfirexd.replication.maxLogShippingInterval";

	/** Property name for specifying whether or not replication messages are
	 * written to the log*/
	public static final String REPLICATION_VERBOSE = "gemfirexd.replication.verbose";

	/*
	** Upgrade
	*/
	
	/**
	 * Allow database upgrade during alpha/beta time. Only intended
	 * to be used to allow Derby developers to test their upgrade code.
	 * Only supported as a system/application (gemfirexd.properties) property.
	 */
	final static String ALPHA_BETA_ALLOW_UPGRADE = "gemfirexd.distributedsystem.allowPreReleaseUpgrade";
	    
	/**
		db2j.inRestore
		<BR>
		This Property is used to indicate that we are in restore mode if
		if the system is doing a restore from backup.
		Used internally to set flags to indicate that service is not booted.
		<P>
		<B>INTERNAL USE ONLY</B> 
	*/
	final static String IN_RESTORE_FROM_BACKUP = PROPERTY_RUNTIME_PREFIX  + "inRestore";
	
		    
	/**
		db2j.deleteRootOnError
		<BR>
		If we a new root is created while doing restore from backup,
		it should be deleted if a error occur before we could complete restore 
		successfully.
		<P>
		<B>INTERNAL USE ONLY</B> 
	*/
	final static String DELETE_ROOT_ON_ERROR  = PROPERTY_RUNTIME_PREFIX  + "deleteRootOnError";
	
	public static final String HTTP_DB_FILE_OFFSET = "db2j.http.file.offset";
	public static final String HTTP_DB_FILE_LENGTH = "db2j.http.file.length";
	public static final String HTTP_DB_FILE_NAME =   "db2j.http.file.name";

    /**
     * gemfirexd.drda.startNetworkServer
     *<BR>
     * If true then we will attempt to start a DRDA network server when Derby 
     * boots, turning the current JVM into a server.
     *<BR>
     * Default: false
     */
    public static final String START_DRDA = "gemfirexd.drda.startNetworkServer";

    /*
	** Internal properties, mainly used by Monitor.
	*/
	public static final String SERVICE_PROTOCOL = "gemfirexd.serviceProtocol";
	public static final String SERVICE_LOCALE = "gemfirexd.serviceLocale";

	public static final String COLLATION = "gemfirexd.distributedsystem.collation";
	// These are the six possible values for collation type if the collation
	// derivation is not NONE. If collation derivation is NONE, then collation
	// type should be ignored. The TERRITORY_BASED collation uses the default
	// collator strength while the four with a colon uses a specific strength.
	public static final String UCS_BASIC_COLLATION =
								"UCS_BASIC";
	public static final String TERRITORY_BASED_COLLATION =
								"TERRITORY_BASED";
	public static final String TERRITORY_BASED_PRIMARY_COLLATION =
								"TERRITORY_BASED:PRIMARY";
	public static final String TERRITORY_BASED_SECONDARY_COLLATION =
								"TERRITORY_BASED:SECONDARY";
	public static final String TERRITORY_BASED_TERTIARY_COLLATION =
								"TERRITORY_BASED:TERTIARY";
	public static final String TERRITORY_BASED_IDENTICAL_COLLATION =
								"TERRITORY_BASED:IDENTICAL";
	// Define a static string for collation derivation NONE
	public static final String COLLATION_NONE =
		"NONE";

    /**
     * db2j.storage.dataNotSyncedAtCheckPoint
     * <p>
     * When set, the store system will not force a sync() call on the
     * containers during a checkpoint.
     * <p>
     * An internal debug system only flag.  The recovery system will not
     * work properly if this flag is enabled, it is provided to do performance
     * debugging to see whether the system is I/O bound based on checkpoint
     * synchronous I/O.
     * <p>
     *
     **/
	public static final String STORAGE_DATA_NOT_SYNCED_AT_CHECKPOINT = 
        "db2j.storage.dataNotSyncedAtCheckPoint";

    /**
     * db2j.storage.dataNotSyncedAtAllocation
     * <p>
     * When set, the store system will not force a sync() call on the
     * containers when pages are allocated.
     * <p>
     * An internal debug system only flag.  The recovery system will not
     * work properly if this flag is enabled, it is provided to do performance
     * debugging to see whether the system is I/O bound based on page allocation
     * synchronous I/O.
     * <p>
     *
     **/
	public static final String STORAGE_DATA_NOT_SYNCED_AT_ALLOCATION = 
        "db2j.storage.dataNotSyncedAtAllocation";

    /**
     * db2j.storage.logNotSynced
     * <p>
     * When set, the store system will not force a sync() call on the log at 
     * commit.
     * <p>
     * An internal debug system only flag.  The recovery system will not
     * work properly if this flag is enabled, it is provided to do performance
     * debugging to see whether the system is I/O bound based on log file
     * synchronous I/O.
     * <p>
     *
     **/
	public static final String STORAGE_LOG_NOT_SYNCED = 
        "db2j.storage.logNotSynced";
	
    /**
     * System property to set {@link Attribute#TX_SYNC_COMMITS} for all
     * connections.
     */
    public String GFXD_TX_SYNC_COMMITS = Attribute.GFXD_PREFIX
        + Attribute.TX_SYNC_COMMITS;

    /**
     * System property to set {@link Attribute#TX_SYNC_COMMITS} for all
     * connections (old SQLFire property).
     */
    public String SQLF_TX_SYNC_COMMITS = Attribute.SQLF_PREFIX
        + Attribute.TX_SYNC_COMMITS;

    /**
     * System property to set {@link Attribute#DISABLE_TX_BATCHING} for all
     * connections.
     */
    public String GFXD_DISABLE_TX_BATCHING = Attribute.GFXD_PREFIX
        + Attribute.DISABLE_TX_BATCHING;

    /**
     * System property to set {@link Attribute#DISABLE_TX_BATCHING} for all
     * connections.
     */
    public String SQLF_DISABLE_TX_BATCHING = Attribute.SQLF_PREFIX
        + Attribute.DISABLE_TX_BATCHING;

    /**
     * System property to set {@link Attribute#ENABLE_TX_WAIT_MODE} for all
     * connections.
     */
    public String GFXD_ENABLE_TX_WAIT_MODE = Attribute.GFXD_PREFIX
        + Attribute.ENABLE_TX_WAIT_MODE;
    
      
  /**
   * System property to set {@link Attribute#DISABLE_THINCLIENT_CANCEL} for 
   * all thin client connections.
   */
  String GFXD_DISABLE_THINCLINT_CANCEL = Attribute.GFXD_PREFIX
      + Attribute.DISABLE_THINCLIENT_CANCEL;
  
  /**
   * System property to set {@link Attribute#DISABLE_THINCLIENT_CANCEL} for 
   * all thin client connections.
   */
  public String SQLF_DISABLE_THINCLINT_CANCEL = com.vmware.sqlfire.Attribute.SQLF_PREFIX
      + com.vmware.sqlfire.Attribute.DISABLE_THINCLIENT_CANCEL;
  
      
  /**
   * A system property to set {@link Attribute#QUERY_TIMEOUT} globally
   */
  public String SQLF_QUERY_TIMEOUT = com.vmware.sqlfire.Attribute.SQLF_PREFIX
      + com.vmware.sqlfire.Attribute.QUERY_TIMEOUT;

  /**
   * Set this to true to force using pre GemFireXD 1.3.0.2 release hashing
   * schema. This will be required if adding pre 1.3.0.2 servers in a newer
   * cluster. Other way around will be handled automatically by the product or
   * when recovering from old data files.
   */
  public final static String GFXD_USE_PRE1302_HASHCODE =
      ResolverUtils.GFXD_USE_PRE1302_HASHCODE;
}
