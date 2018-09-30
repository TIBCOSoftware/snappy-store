/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.load.ImportBase

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
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

package com.pivotal.gemfirexd.internal.impl.load;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.CacheClosedException;
import com.gemstone.gemfire.cache.TransactionFlag;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTLongObjectHashMap;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.callbacks.ImportErrorLogger;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.PublicAPI;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.locks.LockOwner;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.util.IdUtil;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
// GemStone changes BEGIN
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.load.MTImport.QueueData;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
// GemStone changes END

/**
 * This class implements import of data from a URL into a table.
 * Import functions provided here in this class shouble be called through
 * Systement Procedures. Import uses VTI , which is supprted only through 
 * Systemem procedures mechanism. 
 */
public class ImportBase extends ImportAbstract{
    public static final int MINUS_ONE = -1;
    public static boolean TEST_IMPORT = false; 
    private static AtomicInteger _importCounter = new AtomicInteger(0);

    //
    // This hashtable stores Import instances, which keep the context needed
    // to correlate Derby errors with line numbers in the file that is being
    // imported. An importing thread will access this hashtable at the very
    // beginning and the very end of its run. We cannot use Hashmap
    // because different threads may simultaneously put and delete entries.
    //
    static ConcurrentTLongObjectHashMap<ImportBase> _importers =
        new ConcurrentTLongObjectHashMap<ImportBase>(4);

    private String inputFileName;
// GemStone changes BEGIN
    private final long offset;
    private final long endPosition;
    char[][] currentRows;
    int currentRowIndex;
    boolean eofReceived;
// GemStone changes END

	/**
	 * Constructior to Invoke Import from a select statement 
	 * @param inputFileName	 The URL of the ASCII file from which import will happen
	 * @exception Exception on error 
	 */
	public ImportBase(String inputFileName, String columnDelimiter,
                  String characterDelimiter,  String codeset, 
// GemStone changes BEGIN
                  long offset, long endPosition,
                  boolean hasColumnDefinition,
// GemStone changes END
                  int noOfColumnsExpected,  String columnTypes, 
                  boolean lobsInExtFile,
                  int importCounter,
                  String columnTypeNames, String udtClassNamesString ) throws SQLException 
	{
		try{
			this.inputFileName = inputFileName;
// GemStone changes BEGIN
			this.offset = offset;
			this.endPosition = endPosition;
// GemStone changes END
            this.noOfColumnsExpected = noOfColumnsExpected;
            this.tableColumnTypesStr = columnTypes;
            this.columnTypeNamesString = columnTypeNames;
            this.udtClassNamesString = udtClassNamesString;
			controlFileReader = new ControlInfo();
			controlFileReader.setControlProperties(characterDelimiter,
												   columnDelimiter, codeset);
			// GemStone changes BEGIN
			if (hasColumnDefinition) {
			  controlFileReader.setcolumnDefinition(ControlInfo.INTERNAL_TRUE);
			}
			// GemStone changes END
            this.lobsInExtFile = lobsInExtFile;

// GemStone changes BEGIN
            _importers.putPrimitive(importCounter, this);

            if (GemFireXDUtils.TraceImport) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT,
                  "Starting Import from " + inputFileName + ", columnDelimiter="
                      + columnDelimiter + ", charDelimiter=" + characterDelimiter
                      + ", codeset=" + codeset + ", offset=" + offset
                      + ", endPosition=" + endPosition + ", columnsExpected="
                      + noOfColumnsExpected + ", columnTypes=" + columnTypes
                      + ", lobsInExtFile=" + lobsInExtFile + ", columnTypeNames="
                      + columnTypeNames + ", udtClasses=" + udtClassNamesString
                      + ", importCounter=" + importCounter);
            }
            /* (original code)
            _importers.put( new Integer( importCounter ), this );
            */
// GemStone changes END
            
			doImport();

		}catch(Exception e)
		{
			throw importError(e);
		}
	}


	private void doImport() throws Exception
	{
		if (inputFileName == null)
			throw LoadError.dataFileNull();
		doAllTheWork();

	}

	
	/**
	 * IMPORT_TABLE  system Procedure from ij or from a Java application
	 * invokes  this method to perform import to a table from a file.
	 * @param connection	 The Derby database connection URL for the database containing the table
	 * @param schemaName	The name of the schema where table to import exists 
	 * @param tableName     Name of the Table the data has to be imported to.
	 * @param inputFileName Name of the file from which data has to be imported.
	 * @param columnDelimiter  Delimiter that seperates columns in the file
	 * @param characterDelimiter  Delimiter that is used to quiote non-numeric types
	 * @param codeset           Codeset of the data in the file
	 * @param replace          Indicates whether the data in table has to be replaced or
	 *                         appended.(0 - append , > 0 Replace the data)
     * @param lobsInExtFile true, if the lobs data is stored in an external file,
     *                      and the reference to it is stored in the main import file.
 	 * @exception SQLException on errors
	 */

	public static void importTable(Connection connection, String schemaName, 
                                   String tableName, String inputFileName,  
                                   String columnDelimiter, 
                                   String characterDelimiter,String codeset, 
// GemStone changes BEGIN
                                   short replace, boolean lockTable,
                                   int numThreads, String importClassName,
                                   boolean lobsInExtFile, String errorFile)
                                   /* (original code)
                                   short replace, boolean lobsInExtFile)
                                   */
// GemsTone changes END
		throws SQLException {


		performImport(connection,  schemaName,  null, //No columnList 
					  null , //No column indexes
					  tableName, inputFileName, columnDelimiter, 
// GemStone changes BEGIN
					  characterDelimiter, codeset, replace,
					  lockTable, numThreads, importClassName,
					  lobsInExtFile, errorFile);
					  /* (original code)
					  characterDelimiter, codeset, replace, lobsInExtFile);
					  */
// GemStone changes END
	}



		
	/**
	 * IMPORT_DATA  system Procedure from ij or from a Java application
	 * invokes  this method to perform import to a table from a file.
	 * @param connection	 The Derby database connection URL for the database containing the table
	 * @param schemaName	The name of the schema where table to import exists 
	 * @param tableName     Name of the Table the data has to be imported to.
	 * @param insertColumnList  Comma Seperated column name list to which data
	 *                          has to be imported from file.eg: 'c2,c2,'c3'.
	 * @param columnIndexes     Comma sepearted Lit Index of the columns in the file(first column
	                             starts at 1). eg: '3 ,4 , 5'
	 * @param inputFileName Name of the file from which data has to be imported.
	 * @param columnDelimiter  Delimiter that seperates columns in the file
	 * @param characterDelimiter  Delimiter that is used to quiote non-numeric types
	 * @param codeset           Codeset of the data in the file
	 * @param replace          Indicates whether the data in table has to be replaced or
	 *                         appended.(0 - append , > 0 Replace the data)
     * @param lobsInExtFile true, if the lobs data is stored in an external file,
     *                      and the reference is stored in the main import file.
	 * @exception SQLException on errors
	 */
	public static void importData(Connection connection, String schemaName,
                                  String tableName, String insertColumnList, 
                                  String columnIndexes, String inputFileName, 
                                  String columnDelimiter, 
                                  String characterDelimiter,
                                  String codeset, short replace, 
// GemStone changes BEGIN
                                  boolean lockTable, int numThreads,
                                  String importClassName,
// GemStone changes END
                                  boolean lobsInExtFile, String errorFile)
		throws SQLException 
	{
		

			performImport(connection,  schemaName,  insertColumnList,columnIndexes, 
						  tableName, inputFileName, columnDelimiter, 
// GemStone changes BEGIN
						  characterDelimiter, codeset, replace,
						  lockTable, numThreads, importClassName,
						  lobsInExtFile, errorFile);
						  /* (original code)
						  characterDelimiter, codeset, replace, lobsInExtFile);
						  */
// GemStone changes END
	}
  
    /**
     * This function creates and executes SQL Insert statement that performs the
     * the import using VTI. eg: insert into T1 select (cast column1 as DECIMAL),
     * (cast column2 as INTEGER) from new
     * com.pivotal.gemfirexd.internal.impl.load.ImportBase('extin/Tutor1.asc') as
     * importvti;
     */
    private static void performImport(final Connection connection,
        final String schemaName, final String insertColumnList,
        final String columnIndexes, final String tableName,
        final String inputFileName, final String columnDelimiter,
        final String characterDelimiter, final String codeset,
        final short replace, final boolean lockTable, int numThreads,
        final String importClass, final boolean lobsInExtFile,
        final String errorFile) throws SQLException {

      final int importCounter = bumpImportCounter();
      if (connection == null) {
        throw LoadError.connectionNull();
      }
      if (tableName == null) {
        throw LoadError.entityNameMissing();
      }
      if (inputFileName == null) {
        throw LoadError.dataFileNull();
      }

      final ColumnInfo columnInfo = new ColumnInfo(connection, schemaName,
          tableName, insertColumnList, columnIndexes, COLUMNNAMEPREFIX,
          inputFileName, columnDelimiter);

      final String columnTypeNames;
      final String udtClassNames;
      try {
        columnTypeNames = columnInfo.getColumnTypeNames();
        udtClassNames = columnInfo.getUDTClassNames();
      } catch (Throwable t) {
        throw formatImportError(_importers.getPrimitive(importCounter),
            inputFileName, t);
      }

      if (numThreads < 1) {
        throw LoadError.unexpectedError(new RuntimeException("numThreads="
            + numThreads));
      }
      if (GemFireXDUtils.TraceImport) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT,
            "Call performImport " + inputFileName + ", numThreads=" + numThreads
                + " ,errorFile=" + errorFile);
      }
      final String mtErrorFile;
      if (errorFile != null) {
        StringBuilder filePath = new StringBuilder();
        int indx = errorFile.lastIndexOf('.');
        if (indx > MINUS_ONE) {
          filePath.append(errorFile.substring(0, indx)); 
        }
        else {
          filePath.append(errorFile);
        }
        filePath.append("_");
        filePath.append(System.currentTimeMillis());
        
        if (errorFile.contains(File.separator)) {
          String basePath = null;
          GemFireStore store = Misc.getMemStore();
          if (store != null) {
            basePath = store.generatePersistentDirName(null);
            filePath.insert(0, File.separator);
            filePath.insert(0, basePath);
          }
        }

        mtErrorFile = filePath.toString();
        if (numThreads == 1) {
          // We only support multi threaded version for error logs
          numThreads++;
        }
      }
      else {
        mtErrorFile = null;
      }

      if (numThreads == 1) {
        performImport(connection, schemaName, insertColumnList, columnIndexes,
            tableName, inputFileName, columnDelimiter, characterDelimiter,
            codeset, replace, lockTable, importClass, lobsInExtFile, 0, 0,
            columnInfo, columnTypeNames, udtClassNames, importCounter,
            MTImport.QUEUE_INVALID_ID, null);
      }
      else {
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final int isolation = connection.getTransactionIsolation();
        final GemFireStore memStore = Misc.getMemStoreBooting();
        final long[] queueId = new long[] { MTImport.QUEUE_INVALID_ID };
        ImportBase importer = null;
        final int lccFlags;
        final boolean skipListeners;
        final EnumSet<TransactionFlag> txFlags;
        final LockOwner parentLockOwner;
        if (connection instanceof EmbedConnection) {
          EmbedConnection embedConn = (EmbedConnection)connection;
          LanguageConnectionContext lcc = embedConn.getLanguageConnection();
          parentLockOwner = lcc.getTransactionExecute().getLockSpace().getOwner();
          lccFlags = lcc.getFlags();
          skipListeners = lcc.isSkipListeners();
          txFlags = lcc.getTXFlags();
        }
        else {
          lccFlags = 0;
          skipListeners = false;
          txFlags = null;
          parentLockOwner = null;
        }
        numThreads--; // excluding this reader thread itself
        final Thread[] threads = new Thread[numThreads];
        final AtomicInteger threadId = new AtomicInteger(0);
        final EmbedConnection[] conns = new EmbedConnection[numThreads];
        int numConns;
        final Runnable mtImportTask = new Runnable() {
          @Override
          public void run() {
            int id = threadId.getAndIncrement();
            EmbedConnection conn = conns[id];
            try {
              performMTImport(conn, schemaName, insertColumnList, columnIndexes,
                  tableName, inputFileName, columnDelimiter, characterDelimiter,
                  codeset, replace, false, importClass, lobsInExtFile, 0, 0,
                  columnInfo, columnTypeNames, udtClassNames,
                  bumpImportCounter(), queueId[0], mtErrorFile);
            } catch (Throwable t) {
              checkThrowable(t, "Import: multi-threaded import failure");
              err.compareAndSet(null, t);
            }
          }
        };
        try {
          final Properties props = new Properties();
          props.putAll(memStore.getDatabase().getAuthenticationService()
              .getBootCredentials());
          // create the connections for threads to use
          for (numConns = 0; numConns < numThreads; numConns++) {
            EmbedConnection conn = (EmbedConnection)InternalDriver
                .activeDriver().connect(Attribute.PROTOCOL, props);
            // set the isolation level on the connection the same
            // as parent connection
            if (isolation != conn.getTransactionIsolation()) {
              conn.setAutoCommit(false);
              conn.setTransactionIsolation(isolation);
            }
            LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
            if (lccFlags != 0) {
              lcc.setFlags(lccFlags);
            }
            if (skipListeners) {
              lcc.setSkipListeners();
            }
            if (txFlags != null) {
              lcc.setTXFlags(txFlags);
            }
            // copy the parent lock owner
            if (parentLockOwner != null) {
              ((GfxdLockSet)lcc.getTransactionCompile().getLockSpace())
                  .setOwner(parentLockOwner);
              ((GfxdLockSet)lcc.getTransactionExecute().getLockSpace())
                  .setOwner(parentLockOwner);
            }
            conns[numConns] = conn;
          }
          // lock the table at this point if required
          if (lockTable) {
            Statement statement = connection.createStatement();
            String entityName = IdUtil.mkQualifiedName(schemaName, tableName);
            String lockSql = "LOCK TABLE " + entityName + " IN EXCLUSIVE MODE";
            statement.executeUpdate(lockSql);
            statement.close();

            GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
            if (observer != null) {
              observer.afterLockingTableDuringImport();
            }
          }

          for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(mtImportTask);
            threads[i].setDaemon(true);
          }
          // initialize the queue and data
          ArrayBlockingQueue<QueueData> dataQueue =
              new ArrayBlockingQueue<QueueData>(numThreads + 2);
          queueId[0] = MTImport.putNewQueue(dataQueue);
          for (Thread th : threads) {
            th.start();
          }
          // execute this thread's task of reading the file and pushing
          // into the queue
          try {
            importer = new ImportBase(inputFileName, columnDelimiter,
                characterDelimiter, codeset, 0, 0,
                columnInfo.hasColumnDefinitionInFile(),
                columnInfo.getExpectedNumberOfColumnsInFile(),
                columnInfo.getExpectedVtiColumnTypesAsString(), lobsInExtFile,
                importCounter, columnTypeNames, udtClassNames);
            // just read from file in loop and keep pushing into the queue
            // when enough of batch size has accumulated (same policy as
            // GemFireInsertResultSet)
            final int bufSize = (TEST_IMPORT? 10: 1024);
            // last row in batch is null to indicate actual length
            char[][] batch = new char[bufSize + 1][];
            char[] line;
            final GfxdHeapThresholdListener thresholdListener =
                memStore.thresholdListener();
            int bufIndex = 0;
            int batchSize = 0;
            int startLineNumber = 0;
            while ((line = importer.importReadData.readNextLine()) != null) {
              batchSize += (line.length * 2);
              if (bufIndex > 0
                  && (bufIndex >= bufSize || batchSize > GemFireXDUtils
                      .DML_MAX_CHUNK_SIZE || (thresholdListener
                      .isEviction() || thresholdListener.isCritical()))) {
                bufIndex = 0;
                if (!addQueueData(dataQueue,
                    new QueueData(batch, startLineNumber), err, threads)) {
                  break;
                }
                Throwable t = err.get();
                if (t != null) {
                  throw t;
                }
                batch = new char[bufSize + 1][];
                batchSize = 0;
                startLineNumber = importer.importReadData.getCurrentRowNumber();
              }
              batch[bufIndex++] = line;
            }
            if (bufIndex > 0) {
              addQueueData(dataQueue, new QueueData(batch, startLineNumber), err, threads);
            }
          } finally {
            // push EOF into the queue for all threads
            for (int i = 0; i < numThreads; i++) {
              addQueueData(dataQueue, MTImport.QUEUE_EOF_TOKEN, err, threads);
            }
            // wait for all to finish
            for (Thread th : threads) {
              try {
                while (th.isAlive()) {
                  if (err.get() != null) {
                    // try to interrupt as soon as possible
                    th.join(100);
                    // Not giving interrupt to all the threads as it can close the oplog file channel
                    // which can close the region and the network interfaces. See bug SNAP-1138.
                    // if (th.isAlive()) {
                      // th.interrupt();
                    // }
                    th.join(1000);
                    break;
                  }
                  else {
                    th.join(1000);
                  }
                }
              } catch (Throwable t) {
                checkThrowable(t, "Import: multi-threaded import failure in join");
                err.compareAndSet(null, t);
              }
            }
            // check whether to commit or rollback
            boolean doCommit = err.get() == null && numConns == numThreads;
            for (int i = 0; i < numConns; i++) {
              try {
                if (doCommit) {
                  ((GemFireTransaction)conns[i].getLanguageConnection()
                      .getTransactionExecute()).xa_prepare();
                }
                else {
                  conns[i].rollback();
                }
              } catch (Throwable t) {
                checkThrowable(t, "Import: multi-threaded import failure "
                    + (doCommit ? "in prepare" : "in rollback")
                    + " of connectionId=" + i);
                if (doCommit) {
                  doCommit = false;
                  err.compareAndSet(null, t);
                  for (int j = 0; j <= i; j++) {
                    try {
                      conns[j].rollback();
                    } catch (Throwable e) {
                      checkThrowable(e, "Import: multi-threaded import "
                          + "failure in rollback of connectionId=" + j);
                    }
                  }
                }
              }
            }
            if (doCommit) {
              // now do the second phase commit and keep going
              for (int i = 0; i < numConns; i++) {
                try {
                  ((GemFireTransaction)conns[i].getLanguageConnection()
                      .getTransactionExecute()).xa_commit(false);
                } catch (Throwable t) {
                  checkThrowable(t, "Import: multi-threaded import failure "
                      + (doCommit ? "in xa_commit" : "in rollback")
                      + " of connectionId=" + i);
                }
              }
            }
            if (importer != null) {
              importer.close();
              // The importer was put into a hashtable so that we could look up
              // line numbers for error messages. The Import constructor put
              // the importer in the hashtable. Now garbage collect that entry.
              _importers.removePrimitive(importCounter);
            }
            for (int i = 0; i < numConns; i++) {
              try {
                conns[i].close();
              } catch (Throwable t) {
                checkThrowable(t, "Import: multi-threaded import failure "
                    + "in close of connectionId=" + i);
              }
            }
          }
          Throwable t = err.get();
          if (t != null) {
            throw t;
          }
        } catch (Throwable t) {
          // check cancellation
          checkThrowable(t, "Import: failure for multi-threaded case");
          throw formatImportError(importer, inputFileName, t);
        } finally {
          if (queueId[0] != MTImport.QUEUE_INVALID_ID) {
            MTImport.removeData(queueId[0]);
          }
        }
      }
    }

    private static boolean addQueueData(ArrayBlockingQueue<QueueData> queue,
        QueueData data, AtomicReference<Throwable> err, Thread[] threads)
        throws CacheClosedException, InterruptedException {
      while (!queue.offer(data, 1, TimeUnit.SECONDS)) {
        // check cancellation
        Misc.checkIfCacheClosing(null);
        // check if a thread failed
        if (err.get() != null) {
          return false;
        }
        // should we have something else?
        boolean someAlive = false;
        for (Thread thr: threads) {
          if (thr.isAlive()) {
            someAlive = true;
            break;
          }
        }
        if (!someAlive) {
          return false;
        }
      }
      return true;
    }

    private static void checkThrowable(Throwable t, String traceString) {
      Error err = null;
      if (t instanceof Error
          && SystemFailure.isJVMFailureError(err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      // Whenever you catch Error or Throwable, you must also
      // check for fatal JVM error (see above). However, there is
      // check VM failure
      SystemFailure.checkFailure();

      Misc.checkIfCacheClosing(t);
      if (GemFireXDUtils.TraceImport || err != null
          || t instanceof RuntimeException) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT, traceString, t);
      }
    }

    /**
     * Get the java.sql.Types column type integer value for given 1 based
     * column.
     */
    public final int getColumnType(int columnIndex) {
      return this.tableColumnTypes[columnIndex - 1];
    }

    /**
     * Get the current row read from file as an array of Strings.
     */
    public final String[] getCurrentRow() {
      return this.nextRow;
    }

    private static void performMTImport(Connection connection,
        String schemaName, String insertColumnList, String columnIndexes,
        String tableName, String inputFileName, String columnDelimiter,
        String characterDelimiter, String codeset, short replace,
        boolean lockTable, String importClass, boolean lobsInExtFile,
        long offset, long endPosition, ColumnInfo columnInfo,
        String columnTypeNames, String udtClassNames, int importCounter,
        long mtImportId, String errorFile) throws SQLException {
      try {
        ImportErrorLogger imErrorLogger = null;
        if (errorFile != null) {
          imErrorLogger = new ImportErrorLogger(errorFile + "_"
              + (importCounter - 1) + ".xml", inputFileName, tableName);
        }
        boolean doRetry = false;
        do {
          if (GemFireXDUtils.TraceImport) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT,
                "performMTImport for " + inputFileName + ", importCounter="
                    + importCounter);
          }
          doRetry = performImport(connection, schemaName, insertColumnList,
              columnIndexes, tableName, inputFileName, columnDelimiter,
              characterDelimiter, codeset, replace, lockTable, importClass,
              lobsInExtFile, offset, endPosition, columnInfo, columnTypeNames,
              udtClassNames, importCounter, mtImportId, imErrorLogger);
          if (errorFile != null) {
            if (doRetry) {
              ImportBase importer = _importers.getPrimitive(importCounter);
              imErrorLogger.isRetry = true;
              imErrorLogger.currentRowIndex = importer.currentRowIndex;
              imErrorLogger.currentRows = importer.currentRows;
            }
            else {
              imErrorLogger.isRetry = false;
              imErrorLogger.currentRowIndex = MINUS_ONE;
              imErrorLogger.currentRows = null;
            }
          }
          if (GemFireXDUtils.TraceImport) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT,
                "performMTImport " + "doRetry=" + doRetry + " for "
                    + inputFileName);
          }
        } while (doRetry);
      } finally {
        // The importer was put into a hashtable so that we could look up
        // line numbers for error messages. The Import constructor put
        // the importer in the hashtable. Now garbage collect that entry.
        _importers.removePrimitive(importCounter);
      }
    }
    
    private static boolean performImport(Connection connection, String schemaName,
        String insertColumnList, String columnIndexes, String tableName,
        String inputFileName, String columnDelimiter, String characterDelimiter,
        String codeset, short replace, boolean lockTable, String importClass,
        boolean lobsInExtFile, long offset, long endPosition,
        ColumnInfo columnInfo, String columnTypeNames, String udtClassNames,
        int importCounter, long mtImportId, final ImportErrorLogger imErrorLogger) throws SQLException {
        PreparedStatement ips = null;
        ImportBase importer = null;
        try {
            StringBuilder sb = new StringBuilder("new ");
            if (mtImportId != MTImport.QUEUE_INVALID_ID) {
              sb.append(MTImport.class.getName());
              sb.append('(');
              sb.append(mtImportId).append(',');
              sb.append(quoteStringArgument(importClass)).append(',');
            }
            else {
              sb.append(importClass);
              sb.append('(');
            }
            /* (original code)
            StringBuilder sb = new StringBuilder("new ");
            sb.append("com.pivotal.gemfirexd.internal.impl.load.ImportBase");
            sb.append("(") ; 
            */
// GemStone changes END
            sb.append(quoteStringArgument(inputFileName));
            sb.append(",") ;
            sb.append(quoteStringArgument(columnDelimiter));
            sb.append(",") ;
            sb.append(quoteStringArgument(characterDelimiter));
            sb.append(",") ;
            sb.append(quoteStringArgument(codeset));
// GemStone changes BEGIN
            sb.append(',');
            sb.append(offset);
            sb.append(',');
            sb.append(endPosition);
            if(columnInfo.hasColumnDefinitionInFile()) {
              sb.append(',');
              sb.append(columnInfo.hasColumnDefinitionInFile());
            }
// GemStone changes END
            sb.append(", ");
            sb.append( columnInfo.getExpectedNumberOfColumnsInFile());
            sb.append(", ");
            sb.append(quoteStringArgument(
                    columnInfo.getExpectedVtiColumnTypesAsString()));
            sb.append(", ");
            sb.append(lobsInExtFile);
            sb.append(", ");
            sb.append( importCounter );
            sb.append(", ");
            sb.append(quoteStringArgument( columnTypeNames ) );
            sb.append(", ");
            sb.append(quoteStringArgument( udtClassNames ) );
            sb.append(" )") ;
            
            String importvti = sb.toString();
            // delimit the table and schema names with quotes.
            // because they might have been  created as quoted
            // identifiers(for example when reserved words are used, names are quoted)
            
            // Import procedures are to be called with case-senisitive names. 
            // Incase of delimited table names, they need to be passed as defined
            // and when they are not delimited, they need to be passed in upper
            // case, because all undelimited names are stored in the upper case 
            // in the database. 
            
            String entityName = IdUtil.mkQualifiedName(schemaName, tableName);
            
            final String insertModeValue;
            if (replace > 0) {
              insertModeValue = "PUT INTO ";
            }
            else {
              insertModeValue = "INSERT INTO ";
            }
            /* (original code)
            if(replace > 0)
                insertModeValue = "replace";
            else
                insertModeValue = "bulkInsert";
            */
            
            String cNamesWithCasts = columnInfo.getColumnNamesWithCasts();
            String insertColumnNames = columnInfo.getInsertColumnNames();
            if(insertColumnNames !=null)
                insertColumnNames = "(" + insertColumnNames + ") " ;
            else
                insertColumnNames = "";
            final String insertSql = insertModeValue + entityName
                + insertColumnNames + " SELECT " + cNamesWithCasts
                + " from " + importvti + " AS importvti";

            //prepare the import statement to hit any errors before locking the table
// GemStone changes BEGIN
            if (mtImportId != MTImport.QUEUE_INVALID_ID
                && connection instanceof EmbedConnection) {
              ips = ((EmbedConnection)connection).prepareMetaDataStatement(
                  insertSql);
            }
            else {
              ips = connection.prepareStatement(insertSql);
            }
            /* (original code)
            PreparedStatement ips = connection.prepareStatement(insertSql);
            */
// GemStone changes END
            
            //lock the table before perfoming import, because there may 
            //huge number of lockes aquired that might have affect on performance 
            //and some possible dead lock scenarios.
// GemStone changes BEGIN
            if (lockTable) {
              Statement statement = connection.createStatement();
              String lockSql = "LOCK TABLE " + entityName + " IN EXCLUSIVE MODE";
              statement.executeUpdate(lockSql);
              statement.close();
              
              GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder.getInstance();
              if (observer != null) {
                observer.afterLockingTableDuringImport();
              }
            }
            /* (original code)
            Statement statement = connection.createStatement();
            String lockSql = "LOCK TABLE " + entityName + " IN EXCLUSIVE MODE";
            statement.executeUpdate(lockSql);
            */
// GemStone changes END
            
            //execute the import operaton.
            try {
                ips.executeUpdate();
            }
            catch (Throwable t)
            {
                importer = _importers.getPrimitive(importCounter);
                int currLineNumber = importer.getCurrentLineNumber();
                if (GemFireXDUtils.TraceImport) {
                  final char[][] rows = importer.currentRows;
                  final String[] currentRows = new String[rows.length];
                  int index = 0;
                  for (char[] row : rows) {
                    currentRows[index++] = (row == null) ? "" : new String(row);
                  }
                  SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT,
                      "Caught and ignored an exception in Import from " + inputFileName
                        + " ,imErrorLogger=" + imErrorLogger
                          + ", lineNumber=" + importer.getCurrentLineNumber() 
                          + ", currentRows=" + Arrays.toString(currentRows)
                          + ", currentRows.length=" + currentRows.length
                          + ", currentRowIndex=" + importer.currentRowIndex 
                          + " ,exception="
                          + t.getClass().getSimpleName(), t);
                }
                
                if (imErrorLogger != null) {
                  if (t instanceof SQLException) {
                    SQLException se = (SQLException)t;
                    if (GemFireXDUtils.TraceImport) {
                      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT,
                          "SQLException errorCode=" + se.getErrorCode()
                              + ", SQLstate=" + se.getSQLState());
                    }
        
                    try {
                      String line = "null";
                      int index = importer.currentRowIndex - 1;
                      if (importer.currentRows != null
                          && importer.currentRows.length > index && index >= 0
                          && importer.currentRows[index] != null) {
                        line = arrayToString(importer.currentRows[index]);
                        if (imErrorLogger.isRetry
                            && importer.currentRows == imErrorLogger.currentRows
                            && importer.currentRowIndex == imErrorLogger.currentRowIndex) {
                          // Avoid going in infinite loop
                          return false;
                        }
                        imErrorLogger.logError(line, currLineNumber, insertSql, se);
                        return true;
                      }
                    } catch (Exception e) {
                      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_IMPORT,
                          "Exception in Import Error Logger", e);
                      throw formatImportError(importer, inputFileName, e);
                    }
                  }
                }

                throw formatImportError(importer, inputFileName, t);
            }
// GemStone changes BEGIN
            /* (original code)
            statement.close();
            */
        }
        finally
        {
          if (ips != null && !ips.isClosed()) {
            ips.close();
          }
// GemStone changes END
        }
        return false;
    }

	/** virtual method from the abstract class
	 * @exception	Exception on error
	 */
	ImportReadData getImportReadData() throws Exception {
// GemStone changes BEGIN
		return new ImportReadData(this.inputFileName, this.offset,
		    this.endPosition, this.controlFileReader);
		/* (original code)
		return new ImportReadData(inputFileName, controlFileReader);
		*/
// GemStone changes END
	}

    /*
     * Bump the import counter.
     *
     */
    private static int bumpImportCounter()
    {
        return _importCounter.incrementAndGet();
    }
    
    /*
     * Format a import error with line number
     *
     */
    private static  SQLException    formatImportError( ImportBase importer, String inputFile, Throwable t )
    {
        int     lineNumber = -1;

// GemStone changes BEGIN
        if (t instanceof SQLException && SQLState.UNEXPECTED_IMPORT_ERROR
            .substring(0, 5).equals(((SQLException)t).getSQLState())) {
          return (SQLException)t;
        }
        if (t instanceof StandardException && SQLState.UNEXPECTED_IMPORT_ERROR
            .equals(((StandardException)t).getMessageId())) {
          return PublicAPI.wrapStandardException((StandardException)t);
        }
// GemStone changes END
        if ( importer != null ) { lineNumber = importer.getCurrentLineNumber(); }
        
        StandardException se = StandardException.newException
            ( SQLState.UNEXPECTED_IMPORT_ERROR, Integer.valueOf( lineNumber ), inputFile, t.getMessage() );
        se.initCause(t);

        return PublicAPI.wrapStandardException(se);
    }

    /**
     * Quote a string argument so that it can be used as a literal in an
     * SQL statement. If the string argument is {@code null} an SQL NULL token
     * is returned.
     *
     * @param string a string or {@code null}
     * @return the string in quotes and with proper escape sequences for
     * special characters, or "NULL" if the string is {@code null}
     */
    private static String quoteStringArgument(String string) {
        if (string == null) {
            return "NULL";
        }
        return StringUtil.quoteStringLiteral(string);
    }
    
    /*
     * Copy of Arrays.toString
     */
    public static String arrayToString(char[] a) {
      if (a == null)
          return "";
      int iMax = a.length - 1;
      if (iMax == -1)
          return "";

      StringBuilder b = new StringBuilder();
      for (int i = 0; ; i++) {
          b.append(a[i]);
          if (i == iMax)
              return b.toString();
      }
  }
}
