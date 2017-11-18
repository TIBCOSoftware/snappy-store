/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.distributed.utils;

import java.io.*;
import java.security.AlgorithmParameters;
import java.security.PrivilegedActionException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.GemFireIOException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEvent;
import com.gemstone.gemfire.cache.execute.FunctionInvocationTargetException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.Locator;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalLocator;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.distributed.internal.ServerLocator;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.FileUtil;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreFilter;
import com.gemstone.gemfire.internal.cache.persistence.OplogType;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.ClientSharedData;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.HostLocationBase;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArraySortedCollectionWithOverflow;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.management.internal.JmxManagerAdvisor.JmxManagerProfile;
import com.gemstone.gnu.trove.THashSet;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.GfxdBulkDMLCommand;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.WBCLEventImpl;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.messages.GfxdCBArgForSynchPrms;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdCallbackArgument;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdSingleResultCollector;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdConfigMessage;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.jdbc.GfxdDDLReplayInProgressException;
import com.pivotal.gemfirexd.internal.engine.locks.DefaultGfxdLockable;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLocalLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockService;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockSet;
import com.pivotal.gemfirexd.internal.engine.locks.GfxdLockable;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.stats.ConnectionStats;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.CompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore.VMKind;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.engine.store.entry.GfxdObjectFactoriesProvider;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.iapi.error.ShutdownException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.ContextId;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.services.loader.ClassInspector;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.LanguageProperties;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ConstraintDescriptorList;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ReferencedKeyConstraintDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.TypeId;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.StatementReader;
import com.pivotal.gemfirexd.internal.impl.jdbc.TransactionResourceImpl;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.impl.sql.compile.RelationalOperator;
import com.pivotal.gemfirexd.internal.jdbc.InternalDriver;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.AssertFailure;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import org.apache.log4j.Logger;

/**
 * Various static utility methods used by GemFireXD.
 * 
 * @author Asif
 * @author swale
 */
public final class GemFireXDUtils {

  public static final int IntegerBytesLen = Integer.SIZE / 8;

  private static boolean optimizerTrace = Boolean
      .getBoolean("gemfirexd.optimizer.trace");

  private static final ConcurrentHashMap<Object, GfxdLockable> lockableMap =
    new ConcurrentHashMap<Object, GfxdLockable>();

  private static long defaultRecoveryDelay =
    PartitionAttributesFactory.RECOVERY_DELAY_DEFAULT;
  
  private static long defaultStartupRecoveryDelay =
      PartitionAttributesFactory.STARTUP_RECOVERY_DELAY_DEFAULT;

  private static int defaultInitialCapacity =
      GfxdConstants.DEFAULT_INITIAL_CAPACITY;

  public static long DML_MAX_CHUNK_SIZE =
      GfxdConstants.DML_MAX_CHUNK_SIZE_DEFAULT;

  public static long DML_MIN_SIZE_FOR_STREAMING =
      GfxdConstants.DML_MIN_SIZE_FOR_STREAMING_DEFAULT;

  public static int DML_MAX_CHUNK_MILLIS =
      GfxdConstants.DML_MAX_CHUNK_MILLIS_DEFAULT;

  public static int DML_SAMPLE_INTERVAL =
      GfxdConstants.DML_SAMPLE_INTERVAL_DEFAULT;

  public static int DML_BULK_FETCH_SIZE =
      LanguageProperties.BULK_FETCH_DEFAULT_INT;

  public static boolean PROCEDURE_ORDER_RESULTS = false;

  public static boolean IS_TEST_MODE = false;

  private static VMIdAdvisor vmIdAdvisor;

  /**
   * Token object representing minimum possible value when in a map.
   */
  public static final Object MIN_KEY = new Object();
  /**
   * Token object representing maximum possible value when in a map.
   */
  public static final Object MAX_KEY = new Object();

  private final static ThreadLocal<EmbedConnection> tssConn =
      new ThreadLocal<EmbedConnection>();

  private GemFireXDUtils() {
    // cannot be constructed
  }

  public static void initConstants(GemFireStore store) throws StandardException {
    // set default-recovery-delay from the system property
    String defaultRecoveryDelayStr = PropertyUtil
        .getSystemProperty(GfxdConstants.DEFAULT_RECOVERY_DELAY_SYSPROP);
    if (defaultRecoveryDelayStr == null) {
      defaultRecoveryDelayStr = store
          .getBootProperty(Attribute.DEFAULT_RECOVERY_DELAY_PROP);
    }
    try {
      if (defaultRecoveryDelayStr != null) {
        defaultRecoveryDelay = Long.parseLong(defaultRecoveryDelayStr);
      }
      else {
        defaultRecoveryDelay = PartitionAttributesFactory
            .RECOVERY_DELAY_DEFAULT;
      }
    } catch (Exception ex) {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          new GemFireXDRuntimeException("unexpected format for boot/sys property "
              + Attribute.DEFAULT_RECOVERY_DELAY_PROP
              + " where a long was expected: " + defaultRecoveryDelayStr),
          TypeId.LONGINT_NAME, (String)null);
    }

    // set default-startup-recovery-delay from the system property
    String defaultStartupRecoveryDelayStr = PropertyUtil
        .getSystemProperty(GfxdConstants.DEFAULT_STARTUP_RECOVERY_DELAY_SYSPROP);
    if (defaultStartupRecoveryDelayStr == null) {
      defaultStartupRecoveryDelayStr = store
          .getBootProperty(GfxdConstants.DEFAULT_STARTUP_RECOVERY_DELAY_PROP);
    }
    try {
      if (defaultStartupRecoveryDelayStr != null) {
        setDefaultStartupRecoveryDelay(Long.parseLong(defaultStartupRecoveryDelayStr));
      }
      else {
        setDefaultStartupRecoveryDelay(PartitionAttributesFactory
            .STARTUP_RECOVERY_DELAY_DEFAULT);
      }
    } catch (Exception ex) {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          new GemFireXDRuntimeException("unexpected format for boot/sys property "
              + GfxdConstants.DEFAULT_STARTUP_RECOVERY_DELAY_PROP
              + " where a long was expected: " + defaultStartupRecoveryDelayStr),
          TypeId.LONGINT_NAME, (String)null);
    }

    // set initial-capacity for all tables from the system property if provided
    String defaultInitialCapacityStr = PropertyUtil
        .getSystemProperty(GfxdConstants.DEFAULT_INITIAL_CAPACITY_SYSPROP);
    if (defaultInitialCapacityStr == null) {
      defaultInitialCapacityStr = store
          .getBootProperty(Attribute.DEFAULT_INITIAL_CAPACITY_PROP);
    }
    try {
      if (defaultInitialCapacityStr != null) {
        defaultInitialCapacity = Integer.parseInt(defaultInitialCapacityStr);
      }
      else {
        defaultInitialCapacity = GfxdConstants.DEFAULT_INITIAL_CAPACITY;
      }
    } catch (Exception ex) {
      throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION,
          new GemFireXDRuntimeException("unexpected format for boot/sys property "
              + Attribute.DEFAULT_INITIAL_CAPACITY_PROP
              + " where an integer was expected: " + defaultInitialCapacityStr),
          TypeId.INTEGER_NAME, (String)null);
    }

    String propStr = PropertyUtil.getServiceProperty(store,
        GfxdConstants.DML_MAX_CHUNK_SIZE_PROP);
    if (propStr != null) {
      try {
        DML_MAX_CHUNK_SIZE = Long.parseLong(propStr);
      } catch (NumberFormatException ignore) {
      }
    }

    propStr = PropertyUtil.getServiceProperty(store,
        GfxdConstants.DML_MIN_SIZE_FOR_STREAMING_PROP);
    if (propStr != null) {
      try {
        DML_MIN_SIZE_FOR_STREAMING = Long.parseLong(propStr);
      } catch (NumberFormatException ignore) {
      }
    }

    DML_MAX_CHUNK_MILLIS = PropertyUtil.getServiceInt(store,
        GfxdConstants.DML_MAX_CHUNK_MILLIS_PROP, 0, Integer.MAX_VALUE,
        GfxdConstants.DML_MAX_CHUNK_MILLIS_DEFAULT);

    DML_SAMPLE_INTERVAL = PropertyUtil.getServiceInt(store,
        GfxdConstants.DML_SAMPLE_INTERVAL_PROP, 0, Integer.MAX_VALUE,
        GfxdConstants.DML_SAMPLE_INTERVAL_DEFAULT);

    DML_BULK_FETCH_SIZE = PropertyUtil.getServiceInt(store,
        LanguageProperties.BULK_FETCH_PROP, 0, Integer.MAX_VALUE,
        LanguageProperties.BULK_FETCH_DEFAULT_INT);

    PROCEDURE_ORDER_RESULTS = PropertyUtil.getServiceBoolean(store,
        GfxdConstants.PROCEDURE_ORDER_RESULTS_PROP, false);

    // any flags that need to be initialized at high GFE log-levels are below
    final GemFireCacheImpl cache = store.getGemFireCache();
    // cache the VMIdAdvisor
    vmIdAdvisor = cache.getDistributedSystem().getVMIdAdvisor();
    final LogWriterI18n logger = cache.getLoggerI18n();
    SanityManager.isFineEnabled = false;
    SanityManager.isFinerEnabled = false;
    if (logger.fineEnabled()) {
      SanityManager.isFineEnabled = true;
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_DDLQUEUE);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_DDLREPLAY);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_CONFLATION);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_CONGLOM);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_CONGLOM_UPDATE);
      SanityManager
          .TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_FUNCTION_EX);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_PROCEDURE_EXEC);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_APP_JARS);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_QUERYDISTRIB);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_EXECUTION);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_SYS_PROCEDURES);
      SanityManager
          .TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_BYTE_COMPARE_OPTIMIZATION);
      SanityManager.TRACE_SET_IF_ABSENT(SanityManager.TRACE_CLIENT_HA);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_STATEMENT_MATCHING);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_PLAN_GENERATION);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_STATS_GENERATION);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_PERSIST_INDEX);
      SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_THRIFT_API);
      if (logger.finerEnabled()) {
        SanityManager.isFinerEnabled = true;
        SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_ACTIVATION);
        SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_DB_SYNCHRONIZER);
        SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_TRAN);
        SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_AUTHENTICATION);
        SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_TEMP_FILE_IO);
        SanityManager
            .TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_CONNECTION_SIGNALLER);
        SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_TRIGGER);
        SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_DDLOCK);
        SanityManager
            .TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_NON_COLLOCATED_JOIN);
        SanityManager
            .TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_PERSIST_INDEX_FINER);
        SanityManager.TRACE_SET_IF_ABSENT("traceSavepoints");
        if (logger.finestEnabled()) {
          // enable most flags (deliberately not turning on auth trace)
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_AGGREG);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_CONGLOM_READ);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_GROUPBYITER);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_GROUPBYQI);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_HEAPTHRESH);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_INDEX);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_LOCK);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_MEMBERS);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_RSITER);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_NCJ_ITER);
          SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_TRAN_VERBOSE);
          SanityManager
              .TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_CONTEXT_MANAGER);
          SanityManager
              .TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_PERSIST_INDEX_FINEST);
          // not turning this on since it creates too many logs
          //SanityManager.TRACE_SET_IF_ABSENT(GfxdConstants.TRACE_ROW_FORMATTER);
        }
      }
    }
    initFlags();

    GfxdObjectFactoriesProvider.dummy();
    GfxdBulkDMLCommand.dummy();
  }

  public static RegionKey convertIntoGemfireRegionKey(DataValueDescriptor dvd,
      GemFireContainer container, boolean doClone) throws StandardException {
    if (container.isByteArrayStore()) {
      return new CompactCompositeRegionKey(dvd, container.getExtraTableInfo());
    } else if (container.isObjectStore()) {
      return container.getRowEncoder().fromRowToKey(
          new DataValueDescriptor[] { dvd }, container);
    }
    return (doClone ? dvd.getClone() : dvd);
  }

  public static RegionKey convertIntoGemfireRegionKey(
      DataValueDescriptor[] compositeKeys, GemFireContainer container,
      boolean doClone) throws StandardException {
    if (container.isByteArrayStore()) {
      return new CompactCompositeRegionKey(compositeKeys,
          container.getExtraTableInfo());
    } else if (container.isObjectStore()) {
      return container.getRowEncoder().fromRowToKey(compositeKeys, container);
    }
    else {
      if (compositeKeys.length == 1) {
        return (doClone ? compositeKeys[0].getClone() : compositeKeys[0]);
      }
      if (doClone) {
        DataValueDescriptor clone;
        for (int index = 0; index < compositeKeys.length; ++index) {
          clone = compositeKeys[index].getClone();
          compositeKeys[index] = clone;
        }
      }
      return new CompositeRegionKey(compositeKeys);
    }
  }
  
  /**
   * Extract and create primary key columns from given row as a GemFire Region
   * key used in GemFireXD. A null return value indicates that one or more primary
   * key columns of input row were null.
   */
  public static RegionKey convertIntoGemFireRegionKey(final ExecRow row,
      final GemFireContainer gfContainer, ExtraTableInfo tableInfo,
      int[] pkCols) throws StandardException {
    if (row instanceof AbstractCompactExecRow) {
      final AbstractCompactExecRow crow = (AbstractCompactExecRow)row;
      final Object keyRow = crow.getByteSource();
      if (keyRow == null || keyRow.getClass() == byte[].class) {
        final byte[] keyBytes = (byte[])keyRow;
        if (tableInfo == null) {
          tableInfo = gfContainer.getExtraTableInfo(keyBytes);
        }
        return new CompactCompositeRegionKey(keyBytes, tableInfo);
      }
      else {
        final OffHeapByteSource keyBS = (OffHeapByteSource)keyRow;
        if (tableInfo == null) {
          tableInfo = gfContainer.getExtraTableInfo(keyBS);
        }
        return new CompactCompositeRegionKey(keyBS, tableInfo);
      }
    }
    else {
      if (pkCols == null) {
        if (tableInfo == null) {
          tableInfo = gfContainer.getExtraTableInfo();
        }
        pkCols = tableInfo.getPrimaryKeyColumns();
      }
      if (pkCols.length == 1) {
        DataValueDescriptor dvd = row.getColumn(pkCols[0] + 1);
        if (dvd != null) {
          return convertIntoGemfireRegionKey(dvd, gfContainer, true);
        }
        else {
          return null;
        }
      }
      else {
        DataValueDescriptor[] dvdarr = new DataValueDescriptor[pkCols.length];
        for (int i = 0; i < pkCols.length; i++) {
          dvdarr[i] = row.getColumn(pkCols[i] + 1);
          if (dvdarr[i] == null) {
            return null;
          }
        }
        return convertIntoGemfireRegionKey(dvdarr, gfContainer, true);
      }
    }
  }

  @SuppressWarnings("unchecked")
  public static Object compare(Object obj1, Object obj2, int compOp)
      throws StandardException {

    if (obj1 == null || obj2 == null) {
      Boolean result = nullCompare(obj1, obj2, compOp);
      return result;
    }

    int r;
    if (obj1 instanceof Comparable && obj2 instanceof Comparable) {
      r = ((Comparable<Object>)obj1).compareTo(obj2);
    } // comparison of two arbitrary objects: use equals()
    else if (compOp == RelationalOperator.EQUALS_RELOP)
      return Boolean.valueOf(obj1.equals(obj2));
    else if (compOp == RelationalOperator.NOT_EQUALS_RELOP)
      return Boolean.valueOf(!obj1.equals(obj2));
    else {
      throw StandardException.newException(SQLState.LANG_INVALID_COMPARE_TO,
          obj1, ClassInspector.readableClassName(obj2.getClass()));
    }
    switch (compOp) {
      case RelationalOperator.EQUALS_RELOP:
        return Boolean.valueOf(r == 0);
      case RelationalOperator.LESS_THAN_RELOP:
        return Boolean.valueOf(r < 0);
      case RelationalOperator.LESS_EQUALS_RELOP:
        return Boolean.valueOf(r <= 0);
      case RelationalOperator.GREATER_THAN_RELOP:
        return Boolean.valueOf(r > 0);
      case RelationalOperator.GREATER_EQUALS_RELOP:
        return Boolean.valueOf(r >= 0);
      case RelationalOperator.NOT_EQUALS_RELOP:
        return Boolean.valueOf(r != 0);
      default:
        throw new IllegalArgumentException("Unknown operator: " + compOp);
    }
  }

  /**
   * Fetch the key value based on the required column list.
   * 
   * @return the number of fields has been accessed.
   */
  public static int fetchValue(DataValueDescriptor[] key,
      DataValueDescriptor[] keyValues, int[] keyScanList)
      throws StandardException {
    // keyScanList == null means all values needed
    if (keyScanList == null) {
      for (int index = 0; index < key.length; ++index) {
        assert keyValues[index] != null;

        keyValues[index].setValue(key[index]);
      }
      return key.length;
    }
    else {
      for (int index = 0; index < keyScanList.length; ++index) {
        assert keyScanList[index] < key.length;
        assert keyValues[keyScanList[index]] != null;

        keyValues[keyScanList[index]].setValue(key[keyScanList[index]]);
      }
      return keyScanList.length;
    }
  }

  public static int fetchValue(final CompactCompositeIndexKey key,
      final DataValueDescriptor[] keyValues,
      /*final DataValueDescriptor[] initializedColumns,*/ final int[] keyScanList)
      throws StandardException {
    // keyScanList == null means all values needed
    if (keyScanList == null) {
      int index = 0;
/*      if (initializedColumns != null) {
        for (; index < initializedColumns.length; index++) {
          assert keyValues[index] != null;

          keyValues[index].setValue(initializedColumns[index]);
        }
      }*/
      final int nCols = key.nCols();
      for (; index < nCols; index++) {
        assert keyValues[index] != null;
        key.setKeyColumn(keyValues[index], index);
      }
      return nCols;
    }
    else {
//      final int nInitColumns = initializedColumns == null ? 0
//          : initializedColumns.length;
      for (int colIndex : keyScanList) {
        assert colIndex < key.nCols();
        assert keyValues[colIndex] != null;
//See #47148
//        if (colIndex < nInitColumns) {
//          keyValues[colIndex].setValue(initializedColumns[colIndex]);
//        }
//        else {
          key.setKeyColumn(keyValues[colIndex], colIndex);
//        }
      }
      return keyScanList.length;
    }
  }

  // returns a Boolean or null if UNDEFINED
  private static Boolean nullCompare(Object obj1, Object obj2, int compOp) {
    switch (compOp) {
      case RelationalOperator.EQUALS_RELOP:
        if (obj1 == null)
          return Boolean.valueOf(obj2 == null);
        else
          // obj1 is not null obj2 must be
          return Boolean.valueOf(false);
      case RelationalOperator.NOT_EQUALS_RELOP:
        if (obj1 == null)
          return Boolean.valueOf(obj2 != null);
        else
          // obj1 is not null so obj2 must be
          return Boolean.valueOf(true);
      default:
        return null;
    }
  }

  @SuppressWarnings("unchecked")
  public static int compareKeys(Object firstKey, Object secondKey) {
    if (firstKey == secondKey) {
      return 0;
    }
    // MIN_KEY can come as first or second argument
    else if (firstKey == MIN_KEY || firstKey == null) {
      return -1;
    }
    else if (secondKey == MIN_KEY || secondKey == null) {
      return 1;
    }
    // MAX_KEY can come as first or second argument
    else if (secondKey == MAX_KEY) {
      return -1;
    }
    else if (firstKey == MAX_KEY) {
      return 1;
    }
    else if (secondKey.getClass().isInstance(firstKey)) {
      if (firstKey instanceof Comparable) {
        return ((Comparable<Object>)firstKey).compareTo(secondKey);
      }
      else if (firstKey.equals(secondKey)) {
        return 0;
      }
    }
    // if comparison is not possible, use some arbitrary criteria for
    // sorting like comparison of hashCode()s
    return ((firstKey.hashCode() < secondKey.hashCode()) ? -1 : 1);
  }

  /**
   * Get the {@link GfxdDistributionAdvisor} of this VM.
   */
  public static GfxdDistributionAdvisor getGfxdAdvisor() {
    final GfxdDistributionAdvisor advisor = Misc.getMemStoreBooting()
        .getDistributionAdvisor();
    if (advisor != null) {
      return advisor;
    }
    throw new CacheClosedException("GemFireXDUtils#getGfxdAdvisor: "
        + "no advisor found. GemFireXD not booted or closed down.");
  }

  /**
   * Get the {@link GfxdDistributionAdvisor.GfxdProfile} for the given member.
   */
  public static GfxdDistributionAdvisor.GfxdProfile getGfxdProfile(
      DistributedMember member) {
    return getGfxdAdvisor().getProfile((InternalDistributedMember)member);
  }

  /**
   * Get the {@link VMKind} for the given member.
   */
  public static VMKind getVMKind(DistributedMember member) {
    final GfxdDistributionAdvisor.GfxdProfile profile = getGfxdProfile(member);
    if (profile != null) {
      return profile.getVMKind();
    }
    return null;
  }
  
  /**
   * Get the {@link VMKind} for the given member.
   */
  public static String getManagerInfo(DistributedMember member) {
    
    GemFireCacheImpl gfc = GemFireCacheImpl.getExisting();
    if(null == gfc){
      return "Managed Node";
    }else{
      List<JmxManagerProfile> alreadyManaging = gfc.getJmxManagerAdvisor().adviseAlreadyManaging();
      if(!alreadyManaging.isEmpty()){
        for(JmxManagerProfile p : alreadyManaging){
          if(p.getDistributedMember().equals(member)){
            return "Manager Node: Running";
          }
        }
      }
      List<JmxManagerProfile> willingToManager = gfc.getJmxManagerAdvisor().adviseWillingToManage();
      if(!willingToManager.isEmpty()){
        for(JmxManagerProfile p : willingToManager){
          if(p.getDistributedMember().equals(member)){
            return "Manager Node: Not Running";
          }
        }
      }
    }
    return "Managed Node";
  }

  /** get the {@link VMKind} of this VM */
  public static VMKind getMyVMKind() {
    final GemFireStore store = GemFireStore.getBootingInstance();
    return store != null ? store.getMyVMKind() : null;
  }

  /**
   * Return a globally unique ID efficiently (with occasional messaging and not
   * for each call) which can be used as Connection ID, for example, that is
   * needed to be globally unique on remote nodes.
   * <p>
   * Note that there is a possibility of all possible unique IDs getting
   * exhausted after a long time, so usage has to take that into account (prefer
   * using Region specific UUIDs for such cases).
   * <p>
   * This UUID is not guaranteed to be unique across full DS restarts since it
   * is not persisted, so is useful only for transient in-memory data (e.g.
   * statement IDs). Use {@link LocalRegion#newUUID(boolean)} or
   * {@link #newUUIDForDD()} for persisted UUIDs.
   * 
   * @throws IllegalStateException
   *           thrown when the UUIDs have been exhausted in the distributed
   *           system; note that it is not necessary that all possible long
   *           values would have been used by someone (e.g. a VM goes down
   *           without using its "block" of IDs)
   * 
   * @return a globally unique ID
   */
  public static long newUUID() throws IllegalStateException {
    long retVal;
    if (vmIdAdvisor != null) {
      retVal = vmIdAdvisor.newUUID(false);
    }
    else {
      retVal = Misc.getDistributedSystem().newUUID(false);
    }
    final GemFireXDQueryObserver sqo = GemFireXDQueryObserverHolder.getInstance();
    if (sqo != null) {
      retVal = sqo.overrideUniqueID(retVal, false);
    }
    return retVal;
  }

  /**
   * Return a new persisted globally unique ID efficiently (with occasional
   * messaging and not for each call) that can be entered in the DataDictionary
   * and uses the DDL region's UUID generator. If the IDs have gotten exhaused
   * then an {@link IllegalStateException} is thrown.
   * 
   * @throws IllegalStateException
   *           thrown when the UUIDs have been exhausted in the distributed
   *           system; note that it is not necessary that all possible long
   *           values would have been used by someone (e.g. a VM goes down
   *           without using its "block" of IDs)
   * 
   * @throws StandardException
   *           if this VM is a stand-alone locator and cannot participate in
   *           UUID generation
   */
  public static long newUUIDForDD() throws IllegalStateException,
      StandardException {
    return Misc.getMemStore().getDDLStmtQueue().newUUID();
  }

  public static InternalDistributedMember getDistributedMemberFromUUID(
      final long uuid) {
    int vmUniqueId = (int)(uuid >>> Integer.SIZE & 0xFFFFFFFF);
    return vmIdAdvisor.adviseDistributedMember(vmUniqueId, false);
  }

  public static InternalDistributedMember getDistributedMemberFromVMId(
      final int vmUniqueId) {
    return vmIdAdvisor.adviseDistributedMember(vmUniqueId, false);
  }

  public static int getVMUniqueIdFromUUid(long uuid) {
    return (int)((uuid >>> Integer.SIZE) & 0xFFFFFFFF);
  }
  
  /**
   * Get a new integer identifier efficiently with no messaging for each call
   * that maybe globally unique in the distributed system. This returns an
   * integer so with large number of servers that are going down and coming up,
   * there is no guarantee of uniqueness and an {@link IllegalStateException}
   * may be thrown when such is detected.
   * <p>
   * It is recommended to always use {@link #newUUID} -- use this only if you
   * have to.
   * 
   * @throws IllegalStateException
   *           thrown when UUIDs have been exhaused in the distributed system;
   *           note that it is not necessary that all possible integer values
   *           would have been used by someone (e.g. a VM goes down without
   *           using its "block" of IDs)
   */
  public static int newShortUUID() throws IllegalStateException {
    return vmIdAdvisor.newShortUUID();
  }

  public static final byte set(final byte flags, final byte mask, boolean on) {
    return (byte)(on ? (flags | mask) : (flags & ~mask));
  }

  public static final byte set(final byte flags, final byte mask) {
    return (byte)(flags | mask);
  }

  public static final byte clear(final byte flags, final byte mask) {
    return (byte)(flags & ~mask);
  }

  public static final boolean isSet(final byte flags, final byte mask) {
    return (flags & mask) != 0;
  }
  
  public static final short set(final short flags, final short mask, boolean on) {
    return (short)(on ? (flags | mask) : (flags & ~mask));
  }

  public static final short set(final short flags, final short mask) {
    return (short)(flags | mask);
  }

  public static final short clear(final short flags, final short mask) {
    return (short)(flags & ~mask);
  }

  public static final boolean isSet(final short flags, final short mask) {
    return (flags & mask) != 0;
  }

  public static final int set(final int flags, final int mask, boolean on) {
    return (on ? (flags | mask) : (flags & ~mask));
  }

  public static final int set(final int flags, final int mask) {
    return (flags | mask);
  }

  public static final int clear(final int flags, final int mask) {
    return (flags & ~mask);
  }

  public static final boolean isSet(final int flags, final int mask) {
    return (flags & mask) == mask;
  }

  /**
   * Setup a new connection for use on a remote node that can be reused by
   * remote threads.
   * 
   * Warning: Use this only on remote nodes requiring transient services of
   * {@link LanguageConnectionContext} and/or {@link GemFireTransaction}. The
   * state in the returned context is not tied to any real connection
   * whatsoever. If using on query node then ensure that the thread context has
   * been already setup properly (via JDBC execution on an EmbedConnection or
   * explicit setupContextManager on the connection).
   */
  public static EmbedConnection getTSSConnection(boolean skipLocks,
      boolean remoteConnection, boolean remoteConnectionForDDL)
      throws StandardException {
    EmbedConnection conn = tssConn.get();
    if (conn == null || conn.isClosed()) {
      conn = createNewInternalConnection(remoteConnection);
      tssConn.set(conn);
    }
    final LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
    lcc.setIsConnectionForRemote(remoteConnection);
    lcc.setIsConnectionForRemoteDDL(remoteConnectionForDDL);
    lcc.setSkipLocks(skipLocks);
    // reset the TXState in GemFireTransaction
    final GemFireTransaction tran = (GemFireTransaction)lcc
        .getTransactionExecute();
    tran.resetActiveTXState(true);
    return conn;
  }

  public static EmbedConnection getTSSConnection() throws StandardException {
    EmbedConnection conn = tssConn.get();
    if (conn == null || conn.isClosed()) {
      conn = createNewInternalConnection(true);
      tssConn.set(conn);
    }
    LanguageConnectionContext lcc = conn.getLanguageConnectionContext();
    GemFireTransaction tran = (GemFireTransaction)lcc.getTransactionExecute();
    tran.resetActiveTXState(true);
    return conn;
  }

  public static EmbedConnection createNewInternalConnection(
      boolean remoteConnection) throws StandardException {
    try {
      final Properties props = new Properties();
      props.putAll(AuthenticationServiceBase.getPeerAuthenticationService()
          .getBootCredentials());
      boolean isSnappy = Misc.getMemStore().isSnappyStore();
      String protocol = isSnappy ? Attribute.SNAPPY_PROTOCOL : Attribute.PROTOCOL;
      final EmbedConnection conn = (EmbedConnection)InternalDriver
          .activeDriver().connect(protocol, props,
              EmbedConnection.CHILD_NOT_CACHEABLE,
              EmbedConnection.CHILD_NOT_CACHEABLE, remoteConnection, Connection.TRANSACTION_NONE);
      if (conn != null) {
        conn.setInternalConnection();
        ConnectionStats stats = InternalDriver.activeDriver()
            .getConnectionStats();
        if (stats != null) {
          stats.incInternalConnectionsOpen();
          stats.incInternalConnectionsOpened();
        }
        return conn;
      }
    } catch (final Throwable t) {
      Error err;
      if (t instanceof Error && SystemFailure.isJVMFailureError(
          err = (Error)t)) {
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error. We're poisoned
        // now, so don't let this thread continue.
        throw err;
      }
      SystemFailure.checkFailure();
      // in state of closing?
      Misc.checkIfCacheClosing(t);
      throw StandardException.newException(SQLState.NO_CURRENT_CONNECTION, t);
    }
    // node going down (#47413); wrap a CacheClosedException to retry (#47574)
    throw StandardException.newException(SQLState.NO_CURRENT_CONNECTION,
       new CacheClosedException(LocalizedStrings
          .CacheFactory_A_CACHE_HAS_NOT_YET_BEEN_CREATED.toLocalizedString()));
  }

  /**
   * This method will get the routing object if it requires a global index
   * lookup, using the "value" instead of global index lookup if non-null.
   * Intended usage is before invoking any of {@link Region#get},
   * {@link Region#put}, {@link Region#destroy} methods on accessor which will
   * lead to global index lookup by the resolver. To avoid looking up the global
   * index again at the datastore node, the result of this method should be set
   * as callback argument so that it is picked up by the resolver. The other
   * scenario where this is being used is when derby is doing a scan (e.g. scan
   * and update or destroy) for the operation and a handle to
   * {@link RegionEntry} object is already available. In such a case the routing
   * object can be determined using key+value without requiring any global index
   * lookup.
   */
  public static Object getRoutingObjectFromGlobalIndex(LocalRegion region,
      Object key, Object value) throws StandardException {
    GfxdPartitionResolver resolver = getResolver(region);
    if (resolver != null
        && (resolver.requiresGlobalIndex() || resolver
            .requiresConnectionContext())) {
      try {
        return resolver.getRoutingObject(key, value, region);
      } catch (GemFireException gfeex) {
        throw Misc.processGemFireException(gfeex, gfeex,
            "lookup of global index for key " + key, true);
      }
    }
    return null;
  }

  public static boolean isGlobalIndexCase(AbstractRegion region) {
    GfxdPartitionResolver resolver = getResolver(region);
    if (resolver != null && resolver.requiresGlobalIndex()) {
      return true;
    }
    return false;
  }

  /**
   * This method will get the routing object if it requires a global index
   * lookup, using the "entry" instead of global index lookup if non-null.
   * Intended usage is before invoking any of {@link Region#get},
   * {@link Region#put}, {@link Region#destroy} methods on accessor which will
   * lead to global index lookup by the resolver. To avoid looking up the global
   * index again at the datastore node, the result of this method should be set
   * as callback argument so that it is picked up by the resolver. The other
   * scenario where this is being used is when derby is doing a scan (e.g. scan
   * and update or destroy) for the operation and a handle to
   * {@link RegionEntry} object is already available. In such a case the routing
   * object can be determined using entry without requiring any global index
   * lookup.
   */
  public static Object getRoutingObjectFromGlobalIndex(Object key,
      RegionEntry entry, LocalRegion region) throws EntryDestroyedException,
      StandardException {
    GfxdPartitionResolver resolver = getResolver(region);
    if (resolver != null
        && (resolver.requiresGlobalIndex() || resolver
            .requiresConnectionContext())) {
      @Released Object value = entry.getValueOffHeapOrDiskWithoutFaultIn(region);
      if (value == null || Token.isRemoved(value)) {
        throw new EntryDestroyedException("getRoutingObjectFromGlobalIndex "
            + "for key=" + key + " cannot be done as the value is destroyed");
      }
      try {
        return resolver.getRoutingObject(key, value, region);
      } catch (GemFireException gfeex) {
        throw Misc.processGemFireException(gfeex, gfeex,
            "lookup of global index for key " + key, true);
      } finally {
        OffHeapHelper.release(value);
      }
    }
    return null;
  }

  public static GfxdPartitionResolver getResolver(AbstractRegion region) {
    PartitionAttributes<?, ?> pattrs = region.getPartitionAttributes();
    PartitionResolver<?, ?> resolver;
    if (pattrs != null && (resolver = pattrs.getPartitionResolver())
        instanceof GfxdPartitionResolver) {
      return (GfxdPartitionResolver)resolver;
    }
    return null;
  }

  public static InternalPartitionResolver<?, ?> getInternalResolver(
      AbstractRegion region) {
    PartitionAttributes<?, ?> pattrs = region.getPartitionAttributes();
    PartitionResolver<?, ?> resolver;
    if (pattrs != null && (resolver = pattrs.getPartitionResolver())
        instanceof InternalPartitionResolver<?, ?>) {
      return (InternalPartitionResolver<?, ?>)resolver;
    }
    return null;
  }

  /**
   * Get the routing object given a bucket ID. A value of -1 for the bucket ID
   * is taken to be an invalid value and null is returned.
   * 
   * @param bucketId
   *          int identifying the bucket ID. This can be -1 if the region is not
   *          a partitioned region or if it is not overflowing to disk
   * 
   * @return a valid routing object for the bucket with given ID
   */
  public static Object getRoutingObject(int bucketId) {
    if (bucketId >= 0) {
      return Integer.valueOf(bucketId);
    }
    return null;
  }

  /** A token to indicate the largest possible set. */
  public static final SortedSet<String> SET_MAX = new TreeSet<String>();

  /**
   * This method compares two SortSet's and returns 0 if the two are equal, 1 if
   * first is definitely proper superset of second, -1 if second is definitely
   * proper superset of first, -2 if the two are definitely not a subset of one
   * another, 3 if first has more elements than second and -3 if second has more
   * elements than first.
   */
  private static <T> int setCompareNoSubset(SortedSet<T> groups1,
      SortedSet<T> groups2) {
    if (groups1 == groups2) {
      return 0;
    }
    final int groups1size;
    final int groups2size;
    if (groups1 != null) {
      if (groups1 == SET_MAX) {
        return 1;
      }
      groups1size = groups1.size();
    }
    else {
      groups1size = 0;
    }
    if (groups2 != null) {
      if (groups2 == SET_MAX) {
        return -1;
      }
      groups2size = groups2.size();
    }
    else {
      groups2size = 0;
    }

    if (groups1size == groups2size) {
      if (groups1size > 0) {
        Iterator<T> iter1 = groups1.iterator();
        Iterator<T> iter2 = groups2.iterator();
        while (iter1.hasNext()) {
          if (!iter1.next().equals(iter2.next())) {
            return -2;
          }
        }
      }
      return 0;
    }
    else if (groups1size > groups2size) {
      return (groups2size > 0 ? 3 : 1);
    }
    else {
      return (groups1size > 0 ? -3 : -1);
    }
  }

  /**
   * Return true if the two set of groups are identical to each other.
   */
  public static <T> boolean setEquals(SortedSet<T> groups1,
      SortedSet<T> groups2) {
    return (setCompareNoSubset(groups1, groups2) == 0);
  }

  /**
   * Compare two sets and return -1 if first one is a proper subset of second, 0
   * if the two are equal, 1 if first one is a proper superset of second and a
   * value < -1 if none of these.
   */
  public static <T> int setCompare(SortedSet<T> groups1,
      SortedSet<T> groups2) {
    int res = setCompareNoSubset(groups1, groups2);
    if (res == 3) {
      if (groups1.containsAll(groups2)) {
        return 1;
      }
    }
    else if (res == -3) {
      if (groups2.containsAll(groups1)) {
        return -1;
      }
    }
    else {
      return res;
    }
    return -2;
  }

  /**
   * Return true if the two given sets have an intersecting element. If the
   * given "intersection" argument is non-null then also fill in all the
   * intersecting elements.
   */
  public static <S, T extends S> boolean setIntersect(Set<T> groups1,
      Set<T> groups2, Set<S> intersection) {
    if (groups1 == null || groups2 == null) {
      return false;
    }
    boolean intersect = false;
    if (groups1.size() > groups2.size()) {
      final Set<T> tmp = groups1;
      groups1 = groups2;
      groups2 = tmp;
    }
    for (T obj : groups1) {
      if (groups2.contains(obj)) {
        intersect = true;
        if (intersection != null) {
          intersection.add(obj);
        }
        else {
          break;
        }
      }
    }
    return intersect;
  }

  /**
   * @return 0 means that two sets are equal; 1 means that left is superset of
   *         right; -1 means that left is subset of right; < -1 means they are
   *         not related. Not the most efficient but callers are not required to
   *         be highly optimized ones.
   */
  public static int setCompare(int[] left, int[] right) {
    if (left == right) {
      return 0;
    }
    else if (left == null || right == null) {
      return -2;
    }

    final TreeSet<Integer> leftSet = new TreeSet<Integer>();
    final TreeSet<Integer> rightSet = new TreeSet<Integer>();
    for (int i : left) {
      leftSet.add(Integer.valueOf(i));
    }
    for (int i : right) {
      rightSet.add(Integer.valueOf(i));
    }
    return setCompare(leftSet, rightSet);
  }

  /**
   * Sort the given array of column positions and correspondingly also rearrange
   * their names in place. Not a very efficient implementation so should only be
   * used for small arrays or where perf is not of utmost importance.
   */
  public static void sortColumns(final int[] columnPositions,
      final String[] columnNames) {
    final int len = columnPositions.length;
    if (len == 1) {
      // nothing to be done for single column
      return;
    }
    final int[] originalPos = columnPositions.clone();
    final String[] newColumns = new String[len];
    Arrays.sort(columnPositions);
    for (int index = 0; index < len; index++) {
      int newIndex = Arrays.binarySearch(columnPositions, originalPos[index]);
      newColumns[newIndex] = columnNames[index];
    }
    for (int index = 0; index < len; index++) {
      columnNames[index] = newColumns[index];
    }
  }

  /**
   * Acquires a lock on the given object and returns
   * {@link GfxdLockSet#LOCK_SUCCESS} if lock was acquired the first time,
   * {@link GfxdLockSet#LOCK_REENTER} if lock was re-entered, and
   * {@link GfxdLockSet#LOCK_FAIL} if lock acquisition failed. Write locks must
   * check for re-enter condition and delay releasing the lock since the
   * implementation does not account for re-entrancy for WRITE locks as of now.
   * 
   * @throws StandardException
   *           if a retry has to be attempted at higher level due to possibility
   *           of deadlock when acquiring a distributed write lock
   */
  public static int lockObjectNoThrow(GfxdLockable lockable, Object lockObject,
      boolean forUpdate, boolean localOnly, TransactionController tc,
      long maxWaitMillis) throws StandardException {
    // initialize as GfxdLockSet.LOCK_REENTER so that if lock is not required
    // to be acquired, then higher layer interprets as successful lock
    // acquisition but not required to be released
    int success = GfxdLockSet.LOCK_REENTER;
    if (tc == null || !Misc.initialDDLReplayInProgress()) {
      final GemFireTransaction tran = (GemFireTransaction)tc;
      if (tran == null || !tran.skipLocks(lockObject, lockable)) {
        // set active state for TX in case it has become IDLE
        if (tran != null) {
          tran.setActiveState();
        }
        if (lockable == null) {
          lockable = getOrCreateLockable(lockObject);
        }
        if (lockable.traceLock()) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
              "lockObject: acquiring " + (forUpdate ? "write" : "read")
                  + " lock on object " + lockable + " [TX " + tran
                  + "] for lock " + lockable.getReadWriteLock());
        }
        if (tran != null) {
          success = tran.getLockSpace().acquireLock(lockable, maxWaitMillis,
              forUpdate, localOnly, forUpdate);
        }
        else {
          final GfxdLockService lockService = Misc.getMemStore()
              .getDDLLockService();
          final Object lockOwner = lockService.newCurrentOwner();
          success = GfxdLockSet.lock(lockService, lockable, lockOwner,
              maxWaitMillis, forUpdate, localOnly) ? GfxdLockSet.LOCK_SUCCESS
              : GfxdLockSet.LOCK_FAIL;
        }
        if (success != GfxdLockSet.LOCK_FAIL) {
          if (lockable.traceLock()) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
                "lockObject: acquired " + (forUpdate ? "write" : "read")
                    + " lock on object " + lockable + " [TX " + tran
                    + "] for lock " + lockable.getReadWriteLock());
          }
        }
        else {
          if (lockable.traceLock()) {
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
                "lockObject: FAILED " + (forUpdate ? "write" : "read")
                    + " lock on object " + lockable + " [TX " + tran
                    + "] for lock " + lockable.getReadWriteLock() + " in "
                    + maxWaitMillis + "ms");
          }
        }
      }
    }
    return success;
  }

  /**
   * Acquires a lock on the given object and returns true if lock was acquired
   * the first time, false if lock was re-entered, and throws timeout exception
   * if lock acquisition failed. Write locks must check for re-enter condition
   * and delay releasing the lock since the implementation does not account for
   * re-entrancy for WRITE locks as of now.
   */
  public static boolean lockObject(GfxdLockable lockable, Object lockObject,
      boolean forUpdate, TransactionController tc) throws StandardException {
    return lockObject(lockable, lockObject, forUpdate, false, tc,
        GfxdLockSet.MAX_LOCKWAIT_VAL);
  }

  /**
   * Acquires a lock on the given object and returns false if lock was
   * re-entered, true if lock was acquired the first time, and throws timeout
   * exception if lock acquisition failed. Write locks must check for re-enter
   * condition and delay releasing the lock since the implementation does not
   * account for re-entrancy for WRITE locks as of now.
   */
  public static boolean lockObject(GfxdLockable lockable, Object lockObject,
      boolean forUpdate, boolean localOnly, TransactionController tc,
      long maxWaitMillis) throws StandardException {
    int status;
    if ((status = lockObjectNoThrow(lockable, lockObject, forUpdate, localOnly,
        tc, maxWaitMillis)) == GfxdLockSet.LOCK_FAIL) {
      final GfxdLockService lockService;
      if (tc != null) {
        lockService = ((GemFireTransaction)tc).getLockSpace().getLockService();
      }
      else {
        lockService = Misc.getMemStore().getDDLLockService();
      }
      throw lockService.getLockTimeoutException(lockable != null ? lockable
          : lockObject, tc != null ? ((GemFireTransaction)tc).getLockSpace()
          .getOwner() : null, true);
    }
    else {
      return (status == GfxdLockSet.LOCK_SUCCESS);
    }
  }

  public static boolean unlockObject(GfxdLockable lockable, Object lockObject,
      boolean forUpdate, boolean localOnly, TransactionController tc) {
    if (tc == null || !Misc.initialDDLReplayInProgress()) {
      final GemFireTransaction tran = (GemFireTransaction)tc;
      if (tran == null || !tran.skipLocks()) {
        if (lockable == null) {
          lockable = getOrCreateLockable(lockObject);
        }
        if (lockable.traceLock()) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_LOCK,
              "unlockObject: releasing " + (forUpdate ? "write" : "read")
                  + " lock on object " + lockable + " [TX " + tran + ']');
        }
        if (tran != null) {
          return tran.getLockSpace().releaseLock(lockable,
              forUpdate, localOnly);
        }
        else {
          final GfxdLockService lockService = Misc.getMemStore()
              .getDDLLockService();
          final Object lockOwner = lockService.newCurrentOwner();
          GfxdLockSet.unlock(lockService, lockable, lockOwner, forUpdate,
              localOnly);
          return true;
        }
      }
    }
    return false;
  }

  public static void freeLockResources(Object lockObject,
      TransactionController tc) throws StandardException {
    GfxdLockable lockable = lockableMap.remove(lockObject);
    if (lockable != null) {
      GfxdLockSet lockSet = ((GemFireTransaction)tc).getLockSpace();
      lockSet.addToFreeResources(lockable);
    }
  }

  private static GfxdLockable getOrCreateLockable(Object lockObject) {
    GfxdLockable lockable = lockableMap.get(lockObject);
    if (lockable == null) {
      lockable = new DefaultGfxdLockable(lockObject, null);
      GfxdLockable oldLockable = lockableMap.putIfAbsent(lockObject, lockable);
      if (oldLockable != null) {
        lockable = oldLockable;
      }
    }
    return lockable;
  }

  /**
   * Acquire lock on an entry for reading. For updates the read lock will be
   * upgraded to write once the row is qualified.
   */
  public static boolean lockForRead(final TXState tx,
      final LockingPolicy lockPolicy, final LockMode mode, final int lockFlags,
      final RegionEntry entry, final GemFireContainer container,
      final LocalRegion dataRegion, final GemFireXDQueryObserver observer) {
    assert dataRegion != null: "unexpected null data region for " + entry
        + " in container " + container;

    final TXId txId = tx.getTransactionId();
    if (observer != null) {
      observer.lockingRowForTX(tx.getProxy(), container, entry, false);
    }
    lockPolicy.acquireLock(entry, mode, lockFlags, txId, dataRegion, null);
    if (!entry.isDestroyedOrRemoved()) {
      return true;
    }
    unlockEntryAfterRead(txId, lockPolicy, mode, entry, container, dataRegion);
    return false;
  }

  /**
   * Release the read lock acquired by {@link #lockForRead} before qualification
   * if the previous row was not qualified by higher layers.
   */
  public static boolean releaseLockForReadOnPreviousEntry(
      final RegionEntry currEntry, final TXState localTXState, final TXId txId,
      final LockingPolicy lockPolicy, final LockMode readLockMode,
      final GemFireContainer container, final LocalRegion currentDataRegion,
      final Object lockContext) {

    // release lock since higher layer has not qualified the current row
    final LocalRegion dataRegion = localTXState.removeReadLockForScan(currEntry,
        lockContext);
    
    if (dataRegion != null) {
      assert currentDataRegion == null || dataRegion == currentDataRegion:
        "unexpected mismatch of dataRegion, provided=" + currentDataRegion
        + ", recorded=" + dataRegion;

      unlockEntryAfterRead(txId, lockPolicy, readLockMode, currEntry, container,
          dataRegion);
      return true;
    }
    return false;
  }

  public static void unlockEntryAfterRead(final TXId txId,
      final LockingPolicy lockPolicy, final LockMode mode,
      final RegionEntry entry, final GemFireContainer container,
      final LocalRegion dataRegion) {
    assert dataRegion != null: "unexpected null data region for " + entry
        + " in container " + container;
    
    // check if any one has write lock
    // otherwise take a writelock before removing from the Map
    boolean entryRemoved = false;
    if (dataRegion.isHDFSReadWriteRegion() && entry.isMarkedForEviction()) {
      if (!lockPolicy.lockedForWrite(entry, null, dataRegion)) {
        try {
          lockPolicy.acquireLock(entry, lockPolicy.getWriteLockMode(), 0, txId, dataRegion, null);
          // release the entry if it is OffHeap.
          dataRegion.entries.removeEntry(entry.getKey(), entry, false);
          entryRemoved = true;
        } finally {
          lockPolicy.releaseLock(entry, lockPolicy.getWriteLockMode(), txId, false, dataRegion);
        }
      }
//      else{
//        lockPolicy.releaseLock(entry, mode, txId, false, dataRegion);
//      }
    } 
    //else {
      // release the read lock
    lockPolicy.releaseLock(entry, mode, txId, false, dataRegion);
    //}
    if(dataRegion.getEnableOffHeapMemory() && entryRemoved) {
      if (entry.isOffHeap()) {
        ((OffHeapRegionEntry)entry).release();
      }
    }
  }

  public interface Visitor<T> {
    boolean visit(T visited);
  }

  public static void forAllContexts(Visitor<LanguageConnectionContext> action) {
    final ContextService factory = ContextService.getFactory();
    LanguageConnectionContext lcc;
    if (factory != null) {
      synchronized (factory) {
        for (ContextManager cm : factory.getAllContexts()) {
          if (cm == null) {
            continue;
          }
          lcc = (LanguageConnectionContext)cm.getContext(
              ContextId.LANG_CONNECTION);
          if (lcc != null) {
            if (!action.visit(lcc)) {
              return;
            }
          }
        }
      }
    }
  }

  public static EnumSet<TransactionFlag> addTXFlag(TransactionFlag flag,
      EnumSet<TransactionFlag> flagSingleton,
      EnumSet<TransactionFlag> currentFlags) {
    if (currentFlags == null) {
      return flagSingleton;
    }
    // don't overwrite static single sized flags
    else if (currentFlags.size() == 1) {
      currentFlags = currentFlags.clone();
    }
    currentFlags.add(flag);
    return currentFlags;
  }

  /** write to DataOutput compressing high and low integers of given long */
  public static void writeCompressedHighLow(final DataOutput out, final long val)
      throws IOException {
    InternalDataSerializer.writeVLHighLow(val, out);
  }

  /** read from DataInput compressing high and low integers of given long */
  public static long readCompressedHighLow(final DataInput in)
      throws IOException {
    return InternalDataSerializer.readVLHighLow(in);
  }

  public static void executeSQLScripts(Connection conn, String[] scripts,
      boolean continueOnError, LogWriter logger, String cmdReplace,
      String cmdReplaceWith, boolean verbose) throws SQLException, IOException,
      PrivilegedActionException, StandardException {
    executeSQLScripts(conn, scripts, continueOnError, logger, null,
        cmdReplace, cmdReplaceWith, verbose);
  }

  public static void executeSQLScripts(Connection conn, String[] scripts,
      boolean continueOnError, Logger logger, String cmdReplace,
      String cmdReplaceWith, boolean verbose) throws SQLException, IOException,
      PrivilegedActionException, StandardException {
    executeSQLScripts(conn, scripts, continueOnError, null, logger,
        cmdReplace, cmdReplaceWith, verbose);
  }

  protected static void executeSQLScripts(Connection conn, String[] scripts,
      boolean continueOnError, LogWriter logger, Logger logger2, String cmdReplace,
      String cmdReplaceWith, boolean verbose) throws SQLException, IOException,
      PrivilegedActionException, StandardException {
    final boolean fineEnabled = verbose || (logger != null
        ? logger.fineEnabled() : logger2.isTraceEnabled());
    Reader[] scriptReaders = new Reader[scripts.length];
    for (int index = 0; index < scripts.length; ++index) {
      final String scriptPath = scripts[index].trim();
      /*
      final String initScript;
      if (scriptPath.charAt(0) == '/') {
        initScript = "file:" + scriptPath;
      }
      else if (scriptPath.matches("^[a-zA-Z]:.*$")) {
        initScript = "file:/" + scriptPath.replace('\\', '/');
      }
      else {
        initScript = scriptPath;
      }
      final URL scriptURL = AccessController
          .doPrivileged(new PrivilegedAction<URL>() {
            public URL run() {
              try {
                URI uri = new URI(initScript);
                return uri.toURL();
              } catch (Exception ex) {
                throw new IllegalArgumentException("SQLConnection: "
                    + "failed to load initial SQL script: " + initScript, ex);
              }
            }
          });
      InputStream sqlStream = AccessController
          .doPrivileged(new PrivilegedExceptionAction<InputStream>() {
            public InputStream run() throws IOException {
              return scriptURL.openStream();
            }
          });
      */
      final InputStream sqlStream;
      try {
        sqlStream = SharedUtils.openURL(scriptPath);
      } catch (Exception ex) {
        throw new IllegalArgumentException("SQLConnection: "
            + "failed to load initial SQL script: " + scriptPath, ex);
      }
      Reader scriptReader = new BufferedReader(new InputStreamReader(sqlStream));
      scriptReaders[index] = scriptReader;
    }
    final EmbedConnection embedConn;
    final TransactionResourceImpl tr;
    if (conn instanceof EmbedConnection) {
      embedConn = (EmbedConnection)conn;
      tr = embedConn.getTR();
    }
    else {
      embedConn = null;
      tr = null;
    }
    for (int index = 0; index < scripts.length; ++index) {
      final Reader scriptReader = scriptReaders[index];
      final Statement stmt = conn.createStatement();
      try {
        StatementReader commandFinder = new StatementReader(scriptReader);
        String command;
        String logPrefix = "SQLConnection: [script: " + scripts[index] + "] ";
        while ((command = commandFinder.nextStatement()) != null) {
          if (fineEnabled) {
            if (logger != null) {
              logger.info(logPrefix + "Starting execution of command: " +
                  command);
            } else {
              logger2.info(logPrefix + "Starting execution of command: " +
                  command);
            }
          }
          try {
            if(cmdReplace != null && cmdReplaceWith != null) {
              command = command.replace(cmdReplace, cmdReplaceWith);
            }
            stmt.execute(command);
            if (stmt.getWarnings() != null && logger != null) {
              GfxdMessage.logWarnings(stmt, command, logPrefix
                  + "SQL warning in execution of command: ", logger);
            }
          } catch (SQLException sqle) {
            if (continueOnError) {
              if (logger != null) {
                logger.error("Exception in execution of command: " + command,
                    sqle);
              } else {
                logger2.error("Exception in execution of command: " + command,
                    sqle);
              }
            }
            else {
              throw sqle;
            }
          } finally {
            if (embedConn != null) {
              tr.commit(embedConn);
              embedConn.clearLOBMapping();
            }
            else {
              conn.commit();
            }
          }
          if (fineEnabled) {
            if (logger != null) {
              logger.info(logPrefix + "Successfully executed command: " +
                  command);
            } else {
              logger2.info(logPrefix + "Successfully executed command: " +
                  command);
            }
          }
        }
      } finally {
        scriptReader.close();
        stmt.close();
      }
    }
  }

  private static final Pattern CREATE_USER_PATTERN = Pattern.compile(
      "\\bCREATE_USER\\s*\\([^,]*,([^\\)]*)\\)", Pattern.CASE_INSENSITIVE
          | Pattern.DOTALL);

  /**
   * Encrypt a given message for storage in file or memory.
   * 
   * @param msg
   *          the message to be encrypted
   * @param transformation
   *          the algorithm to use for encryption e.g. AES/ECB/PKCS5Padding or
   *          Blowfish (e.g. see <a href=
   *          "http://docs.oracle.com/javase/6/docs/technotes/guides/security/SunProviders.html"
   *          >Sun Providers</a> for the available names in Oracle's Sun JDK}
   * @param keyBytes
   *          the encoded private key bytes of the algorithm specified by
   *          "transformation"
   */
  public static String encrypt(final String msg, String transformation,
      final byte[] keyBytes) throws Exception {
    return encryptBytes(msg.getBytes(ClientSharedData.UTF8),
        transformation, keyBytes);
  }

  /**
   * Encrypt given bytes for storage in file or memory.
   * 
   * @param bytes
   *          the bytes to be encrypted
   * @param transformation
   *          the algorithm to use for encryption e.g. AES/ECB/PKCS5Padding or
   *          Blowfish (e.g. see <a href=
   *          "http://docs.oracle.com/javase/6/docs/technotes/guides/security/SunProviders.html"
   *          >Sun Providers</a> for the available names in Oracle's Sun JDK}
   * @param keyBytes
   *          the encoded private key bytes of the algorithm specified by
   *          "transformation"
   */
  public static String encryptBytes(final byte[] bytes,
      String transformation, final byte[] keyBytes) throws Exception {
    final String algo;
    if (transformation == null) {
      transformation = algo = GfxdConstants.PASSWORD_PRIVATE_KEY_ALGO_DEFAULT;
    }
    else {
      algo = getPrivateKeyAlgorithm(transformation);
    }
    final Cipher c = Cipher.getInstance(transformation);
    final SecretKeySpec secKey = new SecretKeySpec(keyBytes, algo);
    c.init(Cipher.ENCRYPT_MODE, secKey);
    final byte[] enc = c.doFinal(bytes);
    final AlgorithmParameters params = c.getParameters();
    String encMsg = ClientSharedUtils.toHexString(enc, 0, enc.length);
    if (params != null) {
      final byte[] paramsBytes = params.getEncoded();
      return encMsg + ':'
          + ClientSharedUtils.toHexString(paramsBytes, 0, paramsBytes.length);
    }
    else {
      return encMsg;
    }
  }

  /**
   * Decrypt a message encrypted using {@link #encrypt}.
   * 
   * @param encMsg
   *          the encrypted message to be decrypted
   * @param transformation
   *          the algorithm to use for encryption e.g. AES/ECB/PKCS5Padding or
   *          Blowfish (e.g. see <a href=
   *          "http://docs.oracle.com/javase/6/docs/technotes/guides/security/SunProviders.html"
   *          >Sun Providers</a> for the available names in Oracle's Sun JDK}
   * @param keyBytes
   *          the encoded private key bytes of the algorithm specified by
   *          "transformation"
   */
  public static String decrypt(final String encMsg, String transformation,
      final byte[] keyBytes) throws Exception {
    return new String(decryptBytes(encMsg, transformation, keyBytes),
        ClientSharedData.UTF8);
  }

  /**
   * Decrypt a message encrypted using {@link #encryptBytes}.
   * 
   * @param encMsg
   *          the encrypted message to be decrypted
   * @param transformation
   *          the algorithm to use for encryption e.g. AES/ECB/PKCS5Padding or
   *          Blowfish (e.g. see <a href=
   *          "http://docs.oracle.com/javase/6/docs/technotes/guides/security/SunProviders.html"
   *          >Sun Providers</a> for the available names in Oracle's Sun JDK}
   * @param keyBytes
   *          the encoded private key bytes of the algorithm specified by
   *          "transformation"
   */
  public static byte[] decryptBytes(final String encMsg, String transformation,
      final byte[] keyBytes) throws Exception {
    final String algo;
    if (transformation == null) {
      transformation = algo = GfxdConstants.PASSWORD_PRIVATE_KEY_ALGO_DEFAULT;
    }
    else {
      algo = getPrivateKeyAlgorithm(transformation);
    }
    final Cipher c = Cipher.getInstance(transformation);
    final SecretKeySpec secKey = new SecretKeySpec(keyBytes, algo);
    // check for AlgorithmParameters
    int paramIndex = encMsg.indexOf(':');
    int encMsgLen;
    if (paramIndex > 0) {
      final AlgorithmParameters params = AlgorithmParameters.getInstance(algo);
      params.init(ClientSharedUtils.fromHexString(encMsg, paramIndex + 1,
          encMsg.length() - paramIndex - 1));
      c.init(Cipher.DECRYPT_MODE, secKey, params);
      encMsgLen = paramIndex;
    }
    else {
      c.init(Cipher.DECRYPT_MODE, secKey);
      encMsgLen = encMsg.length();
    }
    return c.doFinal(ClientSharedUtils.fromHexString(encMsg, 0, encMsgLen));
  }

  public static void updateCipherKeyBytes(byte[] keyBytes, final byte[] salt) {
    if (salt != null && keyBytes != null && keyBytes.length > 0) {
      int bytesIndex;
      for (int index = 0; index < salt.length; index++) {
        bytesIndex = (index * 3) % keyBytes.length;
        keyBytes[bytesIndex] ^= salt[index];
      }
    }
  }

  private static String getPrivateKeyDBKey(String algo, int keySize) {
    return GfxdConstants.PASSWORD_PRIVATE_KEY_DB_PROP_PREFIX + "__" + algo
        + "__" + keySize;
  }

  public static String getPrivateKeyAlgorithm(String transformation) {
    int sidx = transformation.indexOf('/');
    if (sidx > 0) {
      return transformation.substring(0, sidx);
    }
    else {
      return transformation;
    }
  }

  public static byte[] getUserPasswordCipherKeyBytes(String user,
      String transformation, int keySize) throws Exception {
    // obtain the secret key to use for encryption from the distributed database
    // this is generated the first time a JVM forms a distributed system
    final String algo;
    if (transformation == null) {
      transformation = algo = GfxdConstants.PASSWORD_PRIVATE_KEY_ALGO_DEFAULT;
    }
    else {
      algo = getPrivateKeyAlgorithm(transformation);
    }
    if (keySize <= 0) {
      keySize = GfxdConstants.PASSWORD_PRIVATE_KEY_SIZE_DEFAULT;
    }
    String secret = PropertyUtil.getDatabaseProperty(Misc.getMemStoreBooting(),
        getPrivateKeyDBKey(algo, keySize));
    if (secret == null) {
      throw new IllegalStateException(
          "No private key found in the distributed system for algo=" + algo
              + " keysize=" + keySize + " !");
    }

    final byte[] keyBytes = ClientSharedUtils.fromHexString(secret, 0,
        secret.length());
    // salt the key with user name
    if (user == null || user.length() == 0) {
      user = "USER";
    }
    updateCipherKeyBytes(keyBytes, user.getBytes(ClientSharedData.UTF8));
    return keyBytes;
  }

  /**
   * Initialize the private key for given algorithm+keySize once in the lifetime
   * of distributed system.
   */
  public static String initializePrivateKey(String algo, int keySize,
      final LanguageConnectionContext lcc) throws Exception {
    // create the private key used for symmetric encryption of user provided
    // passwords and other secrets, if not created yet; this is done only
    // once in the entire lifetime of a distributed system for a particular
    // algorithm+keySize combination
    final GemFireStore store = Misc.getMemStoreBooting();
    if (algo == null) {
      algo = GfxdConstants.PASSWORD_PRIVATE_KEY_ALGO_DEFAULT;
    }
    if (keySize <= 0) {
      keySize = GfxdConstants.PASSWORD_PRIVATE_KEY_SIZE_DEFAULT;
    }
    final String secretProp = GemFireXDUtils.getPrivateKeyDBKey(algo, keySize);
    String secret = PropertyUtil.getDatabaseProperty(store, secretProp);
    if (secret == null) {
      final GfxdDataDictionary dd = store.getDatabase().getDataDictionary();
      // acquire DD write lock first
      final TransactionController tc = lcc != null ? lcc
          .getTransactionExecute() : null;
      final boolean ddLocked = dd.lockForWriting(tc, false);
      try {
        // check again
        secret = PropertyUtil.getDatabaseProperty(store, secretProp);
        if (secret == null) {
          // generate new private key
          KeyGenerator gen = KeyGenerator.getInstance(algo);
          gen.init(keySize);
          byte[] key = gen.generateKey().getEncoded();
          secret = ClientSharedUtils.toHexString(key, 0, key.length);
          store.setProperty(secretProp, secret, false);
          // lets publish this database property so that all JVMs see it
          GfxdSystemProcedures.publishMessage(
              new Object[] { secretProp, secret }, false,
              GfxdSystemProcedureMessage.SysProcMethod.setDatabaseProperty,
              true, true);
        }
      } finally {
        if (ddLocked) {
          dd.unlockAfterWriting(tc, false);
        }
      }
    }
    return secret;
  }

  /**
   * Returns SQL string after masking any password provided in a SYS.CREATE_USER
   * invocation, or for the case of a prepared statement having "?" token for
   * the password will return null.
   */
  public static String maskCreateUserPasswordFromSQLString(String sql) {
    Matcher m = CREATE_USER_PATTERN.matcher(sql);
    if (m.find()) {
      // check if password is the "?" token
      String pwd = m.group(1);
      if ("?".equals(pwd.trim())) {
        return null;
      }
      else {
        return sql.substring(0, m.start(1)) + "'***'" + sql.substring(m.end(1));
      }
    }
    else {
      return sql;
    }
  }

  public static void throwAssert(String msg) throws AssertFailure {
    SanityManager.THROWASSERT(msg);
  }

  private static boolean checkSQLStateForRetry(String sqlState) {
    return SQLState.GFXD_NODE_SHUTDOWN_PREFIX.equals(sqlState)
        || SQLState.GFXD_NODE_BUCKET_MOVED_PREFIX.equals(sqlState)
        || SQLState.NO_CURRENT_CONNECTION.equals(sqlState)
        || SQLState.DATABASE_NOT_FOUND.substring(0, 5).equals(sqlState)
        || SQLState.BOOT_DATABASE_FAILED.substring(0, 5).equals(sqlState)
        || SQLState.CREATE_DATABASE_FAILED.substring(0, 5).equals(sqlState)
        || SQLState.NO_SUCH_DATABASE.substring(0, 5).equals(sqlState);
  }

  /**
   * Check if the given exception denotes that retry needs to be done for the
   * operation.
   */
  private static boolean checkExceptionForRetry(Throwable t) {
    if (t instanceof CancelException
        || t instanceof FunctionInvocationTargetException
        || t instanceof ForceReattemptException
        || t instanceof DataLocationException
        || t instanceof ShutdownException
        || t instanceof BucketMovedException
        || t instanceof PrimaryBucketException
        || t instanceof GfxdDDLReplayInProgressException) {
      return true;
    }
    // If region is destroyed on some remote node then it should have been
    // locally destroyed there due to some disk exception or something, or cache
    // may have been closed else we should get a proper Standard/SQLException
    // from meta-data layers (see #45651)
    if (t instanceof RegionDestroyedException
        && ((RegionDestroyedException)t).isRemote()) {
      return true;
    }
    if (t instanceof DiskAccessException
        && ((DiskAccessException)t).isRemote()) {
      return true;
    }
    if (t instanceof GemFireXDRuntimeException) {
      return checkIfGfxdRuntimeProperForRetry((GemFireXDRuntimeException)t);
    }
    if (t instanceof StandardException) {
      final StandardException se = (StandardException)t;
      final String sqlState = se.getSQLState();
      return checkSQLStateForRetry(sqlState) ||
          (se.getErrorCode() >= ExceptionSeverity.DATABASE_SEVERITY
              && ("08006".equals(sqlState) || "XJ015".equals(sqlState)));
    }
    if (t instanceof SQLException) {
      final SQLException sqle = (SQLException)t;
      final String sqlState = sqle.getSQLState();
      return checkSQLStateForRetry(sqlState) ||
          (sqle.getErrorCode() >= ExceptionSeverity.DATABASE_SEVERITY
              && ("08006".equals(sqlState) || "XJ015".equals(sqlState)));
    }
    return false;
  }

  private static boolean checkNodeUnstableException(Throwable t,
      boolean checkRemote) {
    if (t instanceof ShutdownException || t instanceof CancelException
        || (checkRemote && t instanceof GfxdDDLReplayInProgressException)) {
      return true;
    }
    if (t instanceof StandardException) {
      final StandardException se = (StandardException)t;
      final String sqlState = se.getSQLState();
      if (SQLState.GFXD_NODE_SHUTDOWN_PREFIX.equals(sqlState)) {
        return true;
      }
      return (se.getErrorCode() >= ExceptionSeverity.DATABASE_SEVERITY
          && ("08006".equals(sqlState) || "XJ015".equals(sqlState)));
    }
    if (t instanceof SQLException) {
      final SQLException sqle = (SQLException)t;
      final String sqlState = sqle.getSQLState();
      if (SQLState.GFXD_NODE_SHUTDOWN_PREFIX.equals(sqlState)) {
        return true;
      }
      return (sqle.getErrorCode() >= ExceptionSeverity.DATABASE_SEVERITY
          && ("08006".equals(sqlState) || "XJ015".equals(sqlState)));
    }
    return false;
  }

  private static boolean checkIfGfxdRuntimeProperForRetry(
      GemFireXDRuntimeException sqle) {
    boolean ret = false;
    SQLException e = ((sqle.getCause() != null && sqle.getCause()
        instanceof SQLException) ? (SQLException)sqle.getCause() : null);
    while (e != null) {
      ret = "XCL16".equals(e.getSQLState());
      if (!ret) {
        e = ((e.getCause() != null && e.getCause() instanceof SQLException)
              ? (SQLException)e.getCause() : null);
      }
      else {
        break;
      }
    }
    return ret;
  }

  public static void processCancelException(final String method,
      final Throwable remoteEx, DistributedMember member)
      throws StandardException {
    Throwable t = remoteEx;
    StandardException stdEx = null;
    SQLException sqle = null;
    while (t != null) {
      // Check if remote node is going down, and in that case throw an exception
      // to indicate that the node needs to retry the query (bug #41471).
      if (retryToBeDone(t)) {
        member = StandardException.fixUpRemoteException(t, member);
        throw StandardException.newException(SQLState.GFXD_NODE_SHUTDOWN, t,
            member, method);
      }
      if (stdEx == null && t instanceof StandardException) {
        stdEx = (StandardException)t;
      }
      if (sqle == null && t instanceof SQLException) {
        sqle = (SQLException)t;
      }
      t = t.getCause();
    }
    if (stdEx != null) {
      member = StandardException.fixUpRemoteException(remoteEx, member);
      throw StandardException.newException(stdEx.getMessageId(), remoteEx,
          stdEx.getArguments());
    }
    if (sqle != null) {
      throw Misc.wrapRemoteSQLException(sqle, remoteEx, member);
    }
  }

  /**
   * If the given exception is an instance of RuntimException or Error then
   * throw it filling in stacktrace from current position, else do nothing.
   */
  public static void throwIfRuntimeException(Throwable t) {
    RuntimeException runtimeEx = GemFireXDRuntimeException.newRuntimeException(
        null, t);
    if (!(runtimeEx instanceof GemFireXDRuntimeException)) {
      throw runtimeEx;
    }
  }

  public static StandardException newDuplicateKeyViolation(
      String constraintType, String indexName, Object newValue,
      EntryExistsException cause) {
    return newDuplicateKeyViolation(constraintType, indexName,
        cause.getLocalizedMessage(), cause.getOldValue(), newValue, cause);
  }

  public static StandardException newDuplicateKeyViolation(
      String constraintType, String indexName, String msg, Object oldValue,
      Object newValue, EntryExistsException cause) {
    final EntryExistsException eee = new EntryExistsException(msg
        + (newValue != null ? "; for " + ArrayUtils.objectStringNonRecursive(
            newValue) : "") + "; myID: " + Misc.getGemFireCache().getMyId(),
            cause != null ? null : oldValue /* don't add oldValue twice */);
    eee.setOldValue(oldValue);
    if (cause != null) {
      eee.initCause(cause);
    }

    if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, "throwing "
          + constraintType + " violation for (" + msg + "), oldValue=("
          + oldValue + ") index=" + indexName, eee);
    }
    StandardException se = StandardException.newException(
        SQLState.LANG_DUPLICATE_KEY_CONSTRAINT, eee, constraintType, indexName);
    // don't report constraint violations to logs by default
    if (!GemFireXDUtils.TraceExecution) {
      se.setReport(StandardException.REPORT_NEVER);
    }
    return se;
  }

  public static StandardException newPutPartitionColumnViolation(
      String constraintType, String indexName, String msg, Object oldValue,
      Object newValue, EntryExistsException cause) {
    final EntryExistsException eee = new EntryExistsException(msg
        + (newValue != null ? "; for " + ArrayUtils.objectStringNonRecursive(
            newValue) : "") + "; myID: " + Misc.getGemFireCache().getMyId(),
            cause != null ? null : oldValue /* don't add oldValue twice */);
    eee.setOldValue(oldValue);
    if (cause != null) {
      eee.initCause(cause);
    }

    if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, "throwing "
          + constraintType + " violation for (" + msg + "), oldValue=("
          + oldValue + ") index=" + indexName, eee);
    }
    return StandardException.newException(SQLState.NOT_IMPLEMENTED,
        eee, "Modification of partitioning column in PUT INTO");
  }

  public static StandardException newDuplicateEntryViolation(String indexName,
      Object oldValue, Object newValue) {
    final String constraintType = "duplicate entry";
    final EntryExistsException eee = new EntryExistsException(constraintType
        + " for " + newValue, oldValue);
    if (GemFireXDUtils.TraceIndex | GemFireXDUtils.TraceQuery) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX, "throwing "
          + constraintType + " violation for (" + newValue + "), oldValue=("
          + oldValue + ") index=" + indexName, eee);
    }
    return StandardException.newException(
        SQLState.LANG_DUPLICATE_KEY_CONSTRAINT, eee, constraintType, indexName);
  }

  public static StandardException newOldValueNotFoundException(Object key,
      Object oldValue, Object currentValue, GemFireContainer container) {
    return StandardException.newException(
        SQLState.DATA_INDEX_MAINTENANCE_EXCEPTION,
        GemFireXDRuntimeException.newRuntimeException(
            "index replace: value to be replaced not found [" + oldValue
                + "] with existing ["
                + ArrayUtils.objectStringNonRecursive(currentValue)
                + "] for key [" + key + "] in: " + container, null));
  }

  public static boolean getOptimizerTrace() {
    return optimizerTrace;
  }

  public static void setOptimizerTrace(boolean onOrOff) {
    optimizerTrace = onOrOff;
  }

  public static Event convertToEvent(AsyncEvent<Object, Object> event) {
    if (event.getKey() != null) {
      return new WBCLEventImpl(event);
    }
    else {
      // event is a gemfirexd dml event
      return (GfxdCBArgForSynchPrms)event.getCallbackArgument();
    }
  }

  public static GfxdCallbackArgument wrapCallbackArgs(final Object rtObject,
      final LanguageConnectionContext lcc, final boolean isTransactional,
      boolean threadLocalType, boolean isCacheLoaded, boolean isPkBased,
      boolean skipListeners, boolean bulkFkChecksEnabled,
      boolean skipConstraintChecks) {
    // first check for fixed instance types
    if (rtObject == null && lcc == null && !bulkFkChecksEnabled
        && !skipConstraintChecks) {
      if (isTransactional) {
        if (!isCacheLoaded && !skipListeners) {
          return isPkBased ? GfxdCallbackArgument
              .getFixedInstanceTransactional() : GfxdCallbackArgument
              .getFixedInstanceTransactionalNoPkBased();
        }
      }
      else if (isCacheLoaded) {
        if (isPkBased) {
          return skipListeners ? GfxdCallbackArgument
              .getFixedInstanceCacheLoadedSkipListeners()
              : GfxdCallbackArgument.getFixedInstanceCacheLoaded();
        }
      }
      else if (!skipListeners) {
        return isPkBased ? GfxdCallbackArgument.getFixedInstance()
            : GfxdCallbackArgument.getFixedInstanceNoPkBased();
      }
    }

    final GfxdCallbackArgument cbArg;
    if (threadLocalType) {
      cbArg = GfxdCallbackArgument.getThreadLocalInstance();
    }
    else {
      cbArg = GfxdCallbackArgument.getWithInfoFieldsType();
    }
    cbArg.setRoutingObject((Integer)rtObject);
    if (lcc != null) {
      cbArg.setConnectionProperties(lcc.getConnectionId());
    }
    if (isCacheLoaded) {
      cbArg.setCacheLoaded();
    }
    if (isTransactional) {
      cbArg.setTransactional();
    }
    if (isPkBased) {
      cbArg.setPkBased();
    }
    if (skipListeners) {
      cbArg.setSkipListeners();
    }
    if (bulkFkChecksEnabled) {
      cbArg.setBulkFkChecksEnabled();
    }
    if (skipConstraintChecks) {
      cbArg.setSkipConstraintChecks();
    }
    return cbArg;
  }

  public static long getDefaultRecoveryDelay() {
    return defaultRecoveryDelay;
  }

  public static long getDefaultStartupRecoveryDelay() {
    return defaultStartupRecoveryDelay;
  }

  public static void setDefaultStartupRecoveryDelay(long delay) {
    defaultStartupRecoveryDelay = delay;
  }

  public static int getDefaultInitialCapacity() {
    return defaultInitialCapacity;
  }

  public static boolean isDefaultRecoveryDelay(long delay) {
    return (delay == PartitionAttributesFactory.RECOVERY_DELAY_DEFAULT);
  }

  /** returns true if the given partitioned region is persistent */
  public static final boolean isPersistent(final LocalRegion r) {
    // first check if we can find persistence on this node itself; for accessors
    // this information will be incorrect
    if (r.getDataPolicy().withPersistence()) {
      return true;
    }
    // fallback to DistributionDescriptor
    final GemFireContainer container = (GemFireContainer)r.getUserAttribute();
    if (container == null) return false;
    if (container.getDistributionDescriptor() != null) {
      return container.getDistributionDescriptor().getPersistence();
    }
    final TableDescriptor td = container.getTableDescriptor();
    try {
      DistributionDescriptor desc;
      if (td != null && (desc = td.getDistributionDescriptor()) != null) {
        return desc.getPersistence();
      }
    } catch (final StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception in getting DistributionDescriptor", se);
    }
    return false;
  }

  private static final String randChooseStr =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

  private static final char[] randChooseChars = randChooseStr.toCharArray();

  public static String getRandomString(boolean randomNull) {
    // set a random user name
    final Random rnd = PartitionedRegion.rand;
    if (!randomNull || rnd.nextBoolean()) {
      for (;;) {
        final int numBytes = rnd.nextInt(10) + 3;
        final char[] userBytes = new char[numBytes];
        for (int index = 0; index < numBytes; ++index) {
          userBytes[index] = randChooseChars[rnd
              .nextInt(randChooseChars.length)];
        }
        if (Character.toUpperCase(userBytes[0]) != 'S'
            || Character.toUpperCase(userBytes[1]) != 'Y'
            || Character.toUpperCase(userBytes[2]) != 'S') {
          return String.valueOf(userBytes);
        }
      }
    }
    else {
      return null;
    }
  }

  /**
   * Get the {@link TableDescriptor} for given table in provided schema.
   */
  public static TableDescriptor getTableDescriptor(String schemaName,
      String tableName, LanguageConnectionContext lcc) {
    final GemFireContainer container = getGemFireContainer(schemaName,
        tableName, lcc);
    if (container != null) {
      return container.getTableDescriptor();
    }
    return null;
  }

  /**
   * Get the {@link GemFireContainer} for given table in provided schema.
   */
  public static GemFireContainer getGemFireContainer(String schemaName,
      String tableName, LanguageConnectionContext lcc) {
    if (tableName != null) {
      final Region<?, ?> region = Misc.getRegionByPath(
          Misc.getRegionPath(schemaName, tableName, lcc), false);
      if (region != null) {
        return (GemFireContainer)region.getUserAttribute();
      }
    }
    return null;
  }

  /**
   * Get the {@link GemFireContainer} for given table name.
   */
  public static GemFireContainer getGemFireContainer(String tableName,
      boolean throwIfNotFound) {
    return (GemFireContainer)Misc.getRegionForTable(tableName,
        throwIfNotFound).getUserAttribute();
  }

  public static int[] getPrimaryKeyColumns(TableDescriptor td)
      throws StandardException {
    if (td != null) {
      final ReferencedKeyConstraintDescriptor rkcd = td.getPrimaryKey();
      if (rkcd != null) {
        return rkcd.getReferencedColumns();
      }
    }
    return null;
  }

  public static HashMap<String, Integer> getColumnNamesToIndexMap(
      TableDescriptor td, boolean primaryKeyIncluded) throws StandardException {
    HashMap<String, Integer> nameToIdxMap = new HashMap<String, Integer>();
    ColumnDescriptorList colDescpList = td.getColumnDescriptorList();
    Iterator<?> csItr = colDescpList.iterator();
    int[] pkIndexes = null;
    if (!primaryKeyIncluded) {
      pkIndexes = getPrimaryKeyColumns(td);
    }
    while (csItr.hasNext()) {
      ColumnDescriptor cd = (ColumnDescriptor)csItr.next();
      int idx = cd.getPosition();
      if (pkIndexes != null && !primaryKeyIncluded) {
        if (checkIfPartOfPrimary(idx, pkIndexes)) {
          continue;
        }
      }
      nameToIdxMap.put(cd.getColumnName(), Integer.valueOf(cd.getPosition()));
    }
    return nameToIdxMap;
  }

  private static boolean checkIfPartOfPrimary(int idx, int[] pkIndexes) {
    boolean retVal = false;
    for (int i = 0; i < pkIndexes.length; i++) {
      if (idx == pkIndexes[i]) {
        retVal = true;
        break;
      }
    }
    return retVal;
  }

  public static Map<String, Integer> getPrimaryKeyColumnNamesToIndexMap(
      TableDescriptor td, LanguageConnectionContext lcc)
      throws StandardException {
    ReferencedKeyConstraintDescriptor rkcd = td.getPrimaryKey();
    if (rkcd == null) {
      TransactionController tc = lcc.getTransactionExecute();
      DataDictionary dd = lcc.getDataDictionary();
      ConstraintDescriptorList constraints = dd.getConstraintDescriptors(dd
          .getTableDescriptor(td.getName(),
              dd.getSchemaDescriptor(td.getSchemaName(), tc, false), tc));
      rkcd = constraints.getPrimaryKey();
      if (rkcd == null) {
        return null;
      }
    }
    final LinkedHashMap<String, Integer> nameToIdxMap =
      new LinkedHashMap<String, Integer>();
    final int[] pkIndexes = rkcd.getKeyColumns();
    String[] columnNames = td.getColumnDescriptorList().getColumnNames();
    String[] pkColNames = new String[pkIndexes.length];
    if (pkColNames.length > columnNames.length) {
      return null;
    }
    for (int i = 0; i < pkIndexes.length; i++) {
      nameToIdxMap.put(columnNames[pkIndexes[i] - 1], pkIndexes[i]);
    }
    return nameToIdxMap;
  }

  public static final int[] getColumnPositionsFromBitSet(
      final FormatableBitSet bitSet) {
    final int columns = bitSet.getNumBitsSet();
    if (columns > 0) {
      final int[] columnPositions = new int[columns];
      int i = 0;
      for (int pos = bitSet.anySetBit(); pos != -1;
          pos = bitSet.anySetBit(pos), i++) {
        columnPositions[i] = pos;
      }
      assert i == columns;
      return columnPositions;
    }
    return null;
  }

  public static final FormatableBitSet getColumnBitSetFromPosition(
      final int[] cols) {
    int len = cols[cols.length - 1];
    final FormatableBitSet bitSet = new FormatableBitSet(len);
    for (int col : cols) {
      if (col > len) {
        bitSet.grow(col);
        len = col;
      }
      bitSet.set(col - 1);
    }
    return bitSet;
  }

  public static final void dropColumnAdjustColumnPositions(final int[] pkCols,
      final int columnPos) {
    if (pkCols != null) {
      for (int i = 0; i < pkCols.length; i++) {
        if (pkCols[i] > columnPos) {
          pkCols[i]--;
        }
      }
    }
  }

  public static boolean retryToBeDone(final Throwable t, int tryCnt) {
    boolean retry = false;
    Throwable exp = t;
    while (exp != null && !retry) {
      retry = checkExceptionForRetry(exp);
      exp = exp.getCause();
    }
    if (retry) {
      if (TraceFunctionException) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
            "GemFireXDUtils#retryToBeDone: retry called for " + (tryCnt + 1)
                + "th time and retry=" + retry + " with exception",
            new Throwable(t));
      }
      return true;
    }
    return false;
  }

  public static boolean retryToBeDone(final Throwable t) {
    Throwable exp = t;
    while (exp != null) {
      if (checkExceptionForRetry(exp)) {
        if (TraceFunctionException) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX,
              "GemFireXDUtils#retryToBeDone: retry called with exception",
              new Throwable(t));
        }
        return true;
      }
      exp = exp.getCause();
    }
    return false;
  }

  public static boolean nodeFailureException(final Throwable t) {
    return nodeFailureException(t, true);
  }

  public static boolean nodeFailureException(final Throwable t, boolean checkRemote) {
    Throwable exp = t;
    while (exp != null) {
      if (checkNodeUnstableException(exp, checkRemote)) {
        return true;
      }
      exp = exp.getCause();
    }
    return false;
  }

  /**
   * Result of {@link GemFireXDUtils#isTXAbort(Throwable)} method.
   */
  public enum TXAbortState {
    /** TX not aborted */
    NOT_ABORTED,
    /** TX abort due to conflict */
    CONFLICT,
    /** TX abort due to timeout */
    TIMEOUT,
    /** TX abort due to other issues */
    OTHER
  }

  /**
   * Checks if current exception will cause transaction to abort. Returns
   * NOT_ABORTED for false, CONFLICT for Conflict and TIMEOUT for LockTimeOut,
   * and OTHER for other TX aborts.
   */
  public static TXAbortState isTXAbort(Throwable t) {
    while (t != null) {
      if (t instanceof StandardException) {
        final StandardException se = (StandardException)t;
        final String sqlState = se.getSQLState();
        final LanguageConnectionContext lcc = Misc
            .getLanguageConnectionContext();
        if (lcc != null) {
          EmbedConnection.abortForConstraintViolationInTX(se, lcc);
        }
        if ("X0Z02".equals(sqlState)) {
          return TXAbortState.CONFLICT;
        }
        else if ("40XL1".equals(sqlState)) {
          return TXAbortState.TIMEOUT;
        }
        else if (se.getSeverity() >= ExceptionSeverity.TRANSACTION_SEVERITY) {
          return TXAbortState.OTHER;
        }
      }
      else if (t instanceof ConflictException) {
        return TXAbortState.CONFLICT;
      }
      else if (t instanceof LockTimeoutException) {
        return TXAbortState.TIMEOUT;
      }
      else if (t instanceof SQLException) {
        final SQLException sqle = (SQLException)t;
        final String sqlState = sqle.getSQLState();
        final LanguageConnectionContext lcc = Misc
            .getLanguageConnectionContext();
        final int errorCode = sqle.getErrorCode();
        if ("X0Z02".equals(sqlState)) {
          return TXAbortState.CONFLICT;
        }
        else if ("40XL1".equals(sqlState)) {
          return TXAbortState.TIMEOUT;
        }
        else if (lcc != null && (EmbedConnection.abortForConstraintViolationInTX(
            sqlState, errorCode, lcc)
            || errorCode >= ExceptionSeverity.TRANSACTION_SEVERITY)) {
          return TXAbortState.OTHER;
        }
      }
      t = t.getCause();
    }
    return TXAbortState.NOT_ABORTED;
  }

  /**
   * Get the drive name with colon (e.g. C:) of current working directory for
   * windows else return null for non-Windows platform.
   */
  public static String getCurrentDrivePrefix() {
    if (NativeCalls.getInstance().getOSType().isWindows()) {
      try {
        // get the current drive
        final String drivePrefix = new File(".").getCanonicalPath().substring(
            0, 2);
        final char driveC = drivePrefix.charAt(0);
        if (driveC != '/' && driveC != '\\' && drivePrefix.charAt(1) == ':') {
          return drivePrefix;
        }
      } catch (IOException ex) {
        throw new IllegalArgumentException(
            "Failed in setting the overflow directory", ex);
      }
    }
    return null;
  }

  public static String getExpectedExceptionString(Object exObj,
      boolean forRemove) {
    String exStr;
    if (exObj instanceof Class<?>) {
      exStr = ((Class<?>)exObj).getName();
    }
    else {
      exStr = exObj.toString();
    }
    exStr = exStr.replace('$', '.');
    if (forRemove) {
      return "<ExpectedException action=remove>" + exStr
          + "</ExpectedException>";
    }
    else {
      return "<ExpectedException action=add>" + exStr + "</ExpectedException>";
    }
  }

  public static void sleepForRetry(int numTries) {
    try {
      if ((numTries % 5) != 0) {
        Thread.sleep(SharedUtils.HA_RETRY_SLEEP_MILLIS);
      }
      else {
        Thread.sleep(SharedUtils.HA_RETRY_SLEEP_MILLIS * 20);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      // check for JVM going down
      Misc.checkIfCacheClosing(ie);
    }
  }

  /** convert a given array to a comma separated string */
  public static <T> String toCSV(T[] array) {
    final StringBuilder csv = new StringBuilder();
    if (array != null) {
      for (Object obj : array) {
        if (csv.length() > 0) {
          csv.append(',');
        }
        csv.append(obj);
      }
    }
    return csv.toString();
  }

  public static final SharedUtils.CSVVisitor<Set<ServerLocation>, int[]>
      addServerLocations = new SharedUtils.CSVVisitor<Set<ServerLocation>,
          int[]>() {
    @Override
    public void visit(String str, Set<ServerLocation> excludedServerSet,
        int[] port) {
      final String serverName = SharedUtils.getHostPort(str, port);
      excludedServerSet.add(new ServerLocation(serverName, port[0]));
    }
  };

  @SuppressWarnings("unchecked")
  public static ServerLocation getPreferredServer(
      Collection<String> serverGroups, Collection<String> intersectGroups,
      String excludedServers, boolean allowRedirect) throws SQLException {
    final Set<ServerLocation> excludedServerSet;
    if (excludedServers == null || excludedServers.length() == 0) {
      excludedServerSet = Collections.emptySet();
    }
    else {
      excludedServerSet = new THashSet(4);
      final int[] port = new int[1];
      SharedUtils.splitCSV(excludedServers, addServerLocations,
          excludedServerSet, port);
    }
    return getPreferredServer(serverGroups, intersectGroups, excludedServerSet,
        excludedServers, allowRedirect);
  }

  public static ServerLocation getPreferredServer(
      Collection<String> serverGroups, Collection<String> intersectGroups,
      Set<? extends HostLocationBase<?>> excludedServerSet,
      String excludedServers, boolean allowRedirect) throws SQLException {
    allowRedirect = allowRedirect
        && (GemFireXDUtils.getMyVMKind() != VMKind.LOCATOR);
    try {
      final GfxdDistributionAdvisor gfxdAdvisor = GemFireXDUtils
          .getGfxdAdvisor();
      /* below not needed since GFXD locators don't publish load profile at all
      // exclude network servers on locators from preferred server list
      for (String locatorStr : gfxdAdvisor.getGFXDLocatorNetworkServers()) {
        if (SanityManager.TraceClientHA) {
          SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
              "GET_PREFSERVER: excluding network server on a locator: "
                  + locatorStr);
        }
        try {
          String host = SharedUtils.getHostPort(locatorStr, port);
          excludedServerSet.add(new ServerLocation(host, port[0]));
        } catch (NumberFormatException ignored) {
          // ignore if port cannot be parsed
        }
      }
      */
      if (SanityManager.TraceClientHA) {
        SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
            "getPreferredServer(): full excluded server list: "
                + excludedServerSet);
      }
      Locator loc = Locator.getLocator();
      if (loc != null) {
        final ServerLocator serverLoc = ((InternalLocator)loc)
            .getServerLocatorAdvisee();
        if (serverLoc != null) {
          final ServerLocation server = serverLoc.getLoadSnapshot()
              .getServerForGroups(serverGroups, intersectGroups,
                  excludedServerSet);
          if (server != null) {
            if (SanityManager.TraceClientHA) {
              SanityManager.DEBUG_PRINT(SanityManager.TRACE_CLIENT_HA,
                  "getPreferredServer(): returning preferred server: "
                      + server);
            }
            return server;
          }
        }
      }
      // if no server found, then try to find a locator and send a message
      // to it
      if (allowRedirect) {
        final Set<DistributedMember> locatorMembers = gfxdAdvisor
            .adviseServerLocators(true);

        Iterator<DistributedMember> locators = locatorMembers.iterator();
        while (locators.hasNext()) {
          try {
            GfxdSingleResultCollector rc = new GfxdSingleResultCollector();
            if (excludedServers == null) {
              excludedServers = SharedUtils.toCSV(excludedServerSet);
            }
            final GfxdConfigMessage<Object> msg = new GfxdConfigMessage<Object>(
                rc, Collections.singleton(locators.next()),
                GfxdConfigMessage.Operation.GET_PREFERREDSERVER, new Object[] {
                    serverGroups != null && !serverGroups.isEmpty()
                        ? new ArrayList<String>(serverGroups) : null,
                    intersectGroups != null && !intersectGroups.isEmpty()
                        ? new ArrayList<String>(intersectGroups) : null,
                    excludedServers }, false);

            final ServerLocation prefServer = (ServerLocation)msg
                .executeFunction();
            if (prefServer != null) {
              return prefServer;
            }
            break;
          } catch (Exception e) {
            if (!locators.hasNext()) {
              throw e;
            }
          }
        }
      }
    } catch (Throwable t) {
      throw TransactionResourceImpl.wrapInSQLException(t);
    }
    return null;
  }

  public static synchronized void reset(boolean forBoot) {
    vmIdAdvisor = null;
    lockableMap.clear();
    optimizerTrace = Boolean.getBoolean("gemfirexd.optimizer.trace");
    defaultRecoveryDelay = PartitionAttributesFactory.RECOVERY_DELAY_DEFAULT;
    SanityManager.clearFlags(forBoot);
  }

  /**
   * function to dump any property bag with necessary changes to security.
   * 
   * @param p
   *          the property to be dumped
   * @param msg
   *          start message that should appear.
   * @param debugflag
   *          the flag check needed for context.
   * @param debugTraceOn
   *          trace indicator in the log file
   * @param writer
   *          mostly null to dump into SanityManager default stream. If non
   *          null, writes to it with only exception is
   *          SharedUtils.StringPrintWriter.
   * 
   * @return mostly null other than SharedUtils.StringPrintWriter object, if
   *         passed, returns the writer instead of dumping to the debug stream.
   *         The caller then have to write to the stream. if debugTraceOn is
   *         false, returns writer.
   */
  public static PrintWriter dumpProperties(final Properties p,
      final String msg, final String debugflag, final boolean debugTraceOn,
      final PrintWriter writer) {

    if (!debugTraceOn) {
      return writer;
    }

    final PrintWriter pw;
    final boolean isForWriteToStream = (writer == null
        || !(writer instanceof StringPrintWriter));
    if (isForWriteToStream) {
      pw = new StringPrintWriter();
    }
    else {
      pw = writer;
    }

    if (p == null) {
      pw.print("--- ");
      pw.print(msg);
      pw.println(" ---");
      pw.println("--- EMPTY ---");
    }
    else {
      pw.print("--- ");
      pw.print(msg);
      pw.println(" ---");

      // Sorting the properties....
      final Enumeration<?> e = p.propertyNames();
      final ArrayList<String> sortedProps = new ArrayList<String>();
      for (; e.hasMoreElements();) {
        sortedProps.add((String)e.nextElement());
      }
      Collections.sort(sortedProps);

      for (String key : sortedProps) {
        Object propval = p.getProperty(key);
        if (propval == null) {
          propval = p.get(key);
        }
        final Object val = AuthenticationServiceBase.maskProperty(key, propval);
        if (val == AuthenticationServiceBase.val) {
          // means doesn't want to print this key itself.
          continue;
        }

        pw.print(" ");
        pw.print(key);
        pw.print("=\"");
        pw.print(val);
        pw.println("\"");
      }
      pw.println("--- end --");
    }

    if (!isForWriteToStream) {
      return pw;
    }

    SanityManager.DEBUG_PRINT(debugflag, pw.toString(), null, writer);
    return null;
  }

  public static void dumpStacks(GemFireStore memStore, String header) {
    try {
      // try to get the GfxdDRWLockService and dump locks and threads
      if (memStore != null) {
        memStore.getDDLLockService().dumpAllRWLocks(header,
            false, false, true);
      }
      else {
        throw new ShutdownException();
      }
    } catch (Exception ex) {
      // in case of an exception fall back to generating thread dump only
      final String marker = "======================================="
          + "==========================\n";
      final StringBuilder msg = new StringBuilder(marker);
      msg.append(header).append(":\n\n");
      GfxdLocalLockService.generateThreadDump(msg);
      msg.append(marker);
      SanityManager.DEBUG_PRINT("DumpThreads", msg.toString());
    }
  }

  public static void checkForInsufficientDataStore(LocalRegion region)
      throws StandardException {
    if (!region.hasDataStores()) {
      // for persistent regions throw insufficient datastore
      // (for GFE PartitionOfflineException)
      if (region.getDataPolicy().withPersistence()) {
        throw StandardException.newException(SQLState.INSUFFICIENT_DATASTORE);
      }
      else {
        throw StandardException.newException(SQLState.NO_DATASTORE_FOUND,
            "execution on table " + ((GemFireContainer)region
                .getUserAttribute()).getQualifiedTableName());
      }
    }
  }

  /**
   * Wait for node to be fully initialized and DDL replay to complete.
   */
  public static void waitForNodeInitialization() {
    waitForNodeInitialization(-1L, true, false);
  }

  /**
   * Wait for node to be fully initialized for given max timeout and DDL replay
   * to complete.
   * 
   * @param timeout
   *          the maximum timeout to wait for node initialization; a negative
   *          value indicates infinite wait
   * @param waitForRegionInitializations
   *          also wait for region initializations to be complete (e.g. in case
   *          of persistent regions it can wait for other nodes to startup),
   *          else wait only till basic DDL replay part is done
   * @param endOnDDLReplayDDLockWaiting
   *          if true then end the wait whenever
   *          {@link GemFireStore#initialDDLReplayWaiting()} is detected
   *          to be true
   */
  public static boolean waitForNodeInitialization(final long timeout,
      final boolean waitForRegionInitializations,
      final boolean endOnDDLReplayDDLockWaiting) {
    final GemFireStore memStore = GemFireStore.getBootingInstance();
    if (memStore == null) {
      throw new CacheClosedException("GemFireXDUtils#waitForNodeInitialization: "
          + "no store found. GemFireXD not booted or closed down.");
    }
    if (ddlReplayDone(memStore, waitForRegionInitializations)) {
      return true;
    }
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
        "waitForNodeInitialization: waiting for this node to initialize for "
            + timeout + "ms, waitForRegions=" + waitForRegionInitializations);
    final long waitMillis = 500L;
    long start = 0;
    if (timeout >= 0) {
      start = System.currentTimeMillis();
    }
    final Object sync = memStore.getInitialDDLReplaySync();
    synchronized (sync) {
      while (!ddlReplayDone(memStore, waitForRegionInitializations)) {
        Throwable t = null;
        try {
          // if DDL replay has not started, then go back in the loop and wait
          // for it to first commence
          if (!memStore.initialDDLReplayInProgress()) {
            sync.wait(waitMillis);
            if (timeout >= 0
                && (System.currentTimeMillis() - start) >= timeout) {
              break;
            }
            continue;
          }
          if (endOnDDLReplayDDLockWaiting
              && memStore.initialDDLReplayWaiting()) {
            break;
          }
          // wait for notification
          sync.wait(waitMillis);
          if (timeout >= 0
              && (System.currentTimeMillis() - start) >= timeout) {
            break;
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          t = ie;
        }
        // check for cancellation
        Misc.checkIfCacheClosing(t);
      }
    }
    boolean replayDone = ddlReplayDone(memStore, waitForRegionInitializations);
    SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_DDLREPLAY,
        "waitForNodeInitialization: ending wait for this node to initialize, "
            + "replayDone=" + replayDone);
    return replayDone;
  }

  private static final boolean ddlReplayDone(GemFireStore memStore,
      boolean waitForRegionInitializations) {
    return waitForRegionInitializations ? memStore.initialDDLReplayDone()
        : memStore.initialDDLReplayPart1Done();
  }

  public final static String addressOf(final Object o) {
    if(o == null) {
      return " [NULL] ";
    }
    return o.getClass().getSimpleName() + "@"
        + Integer.toHexString(System.identityHashCode(o));
  }

  public final static boolean hasTable(Connection conn, String tablename)
      throws SQLException {
    return hasTable(conn, null, SharedUtils.SQLToUpperCase(tablename));
  }
  public static boolean isExecRowDetachedFromOffHeapByteSource(ExecRow row) {
    boolean detached = false;
    if( !(row instanceof AbstractCompactExecRow) ) {
      detached = true;
    }else if(row instanceof AbstractCompactExecRow ) {
      AbstractCompactExecRow acr = (AbstractCompactExecRow)row;
      if(!(acr.getBaseByteSource() instanceof OffHeapByteSource)) {
        detached = true;
      }
    }
    return detached;
  }
  
  public static boolean forceReleaseByteSourceFromExecRow(ExecRow row) {
    if(row != null && row instanceof AbstractCompactExecRow) {
      @Unretained @Released Object byteSource = ((AbstractCompactExecRow)row).getByteSource();
      if (OffHeapHelper.release(byteSource)) {
        return true;
      }
    }
    return false;
  }
  
  public final static boolean hasTable(Connection conn, String schema,
      String tableName) throws SQLException {
    // if this is an GFXD connection then use the internal Region API to get
    // the table
    if (conn instanceof EmbedConnection) {
      return hasTable((EmbedConnection)conn, schema, tableName);
    }
    else {
      // use the JDBC API
      final ResultSet rs = conn.getMetaData().getTables((String)null, schema,
          tableName.toUpperCase(), new String[] { "TABLE" });
      final boolean found = rs.next();
      rs.close();
      conn.commit();

      return found;
    }
  }
  
  public final static boolean isOffHeapEnabled() {   
    return SimpleMemoryAllocatorImpl.getAllocatorNoThrow() != null;
  }

  public final static IsolationLevel getIsolationLevel(int jdbcIsolationLevel,
      EnumSet<TransactionFlag> txFlags) throws StandardException {
    try {
      return IsolationLevel.fromJdbcIsolationLevel(jdbcIsolationLevel, null);
    } catch (UnsupportedOperationException e) {
      throw StandardException.newException(
          SQLState.UNIMPLEMENTED_ISOLATION_LEVEL, e,
          String.valueOf(jdbcIsolationLevel)
              + (txFlags != null ? (" with flags " + txFlags) : ""));
    }
  }

  public final static boolean hasTable(EmbedConnection conn, String schema,
      String tableName) throws SQLException {
    return Misc.getRegionByPath(Misc.getRegionPath(schema, tableName,
        conn.getLanguageConnectionContext()), false) != null;
  }
  
  public final static List<File> listFiles(String diskstoreName,
      List<String> diskDirs) {
    ArrayList<File> files = new ArrayList<File>();
    for (String d : diskDirs) {

      FilenameFilter diskFileFilter = new DiskStoreFilter(OplogType.BACKUP,
          true, diskstoreName) {
        @Override
        protected boolean selected(String fileName) {
          return super.selected(fileName)
              || fileName.endsWith(DiskInitFile.IF_FILE_EXT);
        }
      };
      File directory = new File(d);

      final File[] f = FileUtil.listFiles(directory, diskFileFilter);
      files.addAll(Arrays.asList(f));
      
    } // end of diskDirs
    
    return files;
  }
    
    public final static void renameFiles(List<File> files) {
      File[] targetFiles = new File[files.size()];
      for (int i = 0; i < files.size(); i++) {
        File dest = new File(files.get(i).getAbsolutePath().replaceAll("BACKUPSQLF", "BACKUPGFXD"));
        if (dest.exists()) {
          throw new GemFireIOException("Couldn't rename to destination file as it already exists " + dest);
        }
        targetFiles[i] = dest;
      }
      
      final String logMsg = "Renaming diskstore files from " + files + " to " + Arrays.toString(targetFiles);
      LogWriter logger = Misc.getCacheLogWriterNoThrow();
      if (logger != null) {
        logger.info(logMsg);
      }
      else {
        System.out.println(logMsg);
      }
      for (int i = 0; i < files.size(); i++) {
        files.get(i).renameTo(targetFiles[i]);
      }
    }

  public static final Version getCurrentDDLVersion() {
    // using the last pre 1.3.0.2 version by default
    // doesn't matter since only >= 1.3.0.2 read and process this in any case
    return checkUsingGFXD1302Hashing() ? Version.CURRENT : Version.GFXD_13;
  }

  /**
   * check whether this JVM is already setup to use GFXD 1.3.0.2 table hashing
   * or not, else check in the distributed system and try to set the hashing
   * required accordingly
   */
  private static boolean checkUsingGFXD1302Hashing() {
    if (ResolverUtils.isGFXD1302HashingStateSet()) {
      return ResolverUtils.isUsingGFXD1302Hashing();
    }
    else {
      // using distribution manager to determine version of others
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      final DM dm;
      if (cache != null && (dm = cache.getDistributionManager()) != null) {
        Version min = dm.leastCommonVersion(dm
            .getOtherNormalDistributionManagerIds());
        if (min != null && min.compareTo(Version.GFXD_1302) < 0) {
          ResolverUtils.setUsePre1302Hashing(true);
          return false;
        }
      }
      ResolverUtils.setUseGFXD1302Hashing(true);
      return true;
    }
  }

  // statics for various derby debug flags used for GemFireXD trace logging

  /**
   * Set to true if {@link GfxdConstants#TRACE_EXECUTION} or one of the debug
   * flags like {@link GfxdConstants#TRACE_QUERYDISTRIB} are set or fine level
   * logging enabled. This is to do minimal tracing of the execution of
   * different DDLs/DMLs/queries.
   */
  public static boolean TraceExecution;

  /**
   * Set to true if {@link GfxdConstants#TRACE_QUERYDISTRIB} debug flag is set.
   */
  public static boolean TraceQuery;

  /**
   * Set to true if {@link GfxdConstants#TRACE_INDEX} debug flag is set or at
   * finer level and greater.
   */
  public static boolean TraceIndex;

  /**
   * Set to true if {@link GfxdConstants#TRACE_LOCK} debug flag is set.
   */
  public static boolean TraceLock;

  /**
   * Set to true if {@link GfxdConstants#TRACE_CONGLOM} debug flag is set or at
   * finer level and greater.
   */
  public static boolean TraceConglom;

  /**
   * Set to true when {@link GfxdConstants#TRACE_CONGLOM_UPDATE} is enabled or
   * at finer level and greater.
   */
  public static boolean TraceConglomUpdate;

  /**
   * Set to true when {@link GfxdConstants#TRACE_CONGLOM_READ} is enabled or at
   * finer level and greater.
   */
  public static boolean TraceConglomRead;

  public static boolean TraceTran;

  public static boolean TraceTranVerbose;

  public static boolean TraceRSIter;

  public static boolean TraceAggreg;

  public static boolean TraceGroupByQI;

  public static boolean TraceGroupByIter;

  public static boolean TraceActivation;

  public static boolean TraceAuthentication;

  public static boolean TraceHeapThresh;

  public static boolean TraceMembers;

  public static boolean TraceDDLQueue;

  public static boolean TraceDDLReplay;

  public static boolean TraceConflation;

  public static boolean TraceDBSynchronizer;

  public static boolean TraceDBSynchronizerHA;
  
  public static boolean TraceSysProcedures;

  public static boolean TraceFabricServiceBoot;

  public static boolean TraceFunctionException;

  public static boolean TraceProcedureExecution;

  public static boolean TraceStatementMatching;

  public static boolean TraceConnectionSignaller;

  public static boolean TraceTrigger;

  public static boolean TraceByteComparisonOptimization;

  public static boolean TracePlanGeneration;
  
  public static boolean TracePlanAssertion;

  public static boolean TraceStatsGeneration;

  public static boolean TraceRowFormatter;

  public static boolean TraceCM;

  public static boolean TraceTempFileIO;

  public static boolean TraceApplicationJars;

  public static boolean TraceOuterJoin;

  public static boolean TraceSortTuning;

  public static boolean TraceImport;
  
  public static boolean TraceNCJ;
  
  public static boolean TraceNCJIter;
  
  public static boolean TraceNCJDump;
  
  public static boolean TracePersistIndex;
  
  public static boolean TracePersistIndexFiner;
  
  public static boolean TracePersistIndexFinest;

  public static boolean TraceThriftAPI;

  public static boolean TraceSavePoints;

  public static boolean TraceParseTree;
  
  public static boolean TraceStopAfterParse;
  
  static {
    initFlags();
  }

  public static void initFlags() {
    TraceQuery = SanityManager.TRACE_ON(GfxdConstants.TRACE_QUERYDISTRIB);
    TraceIndex = SanityManager.TRACE_ON(GfxdConstants.TRACE_INDEX);
    TraceLock = SanityManager.TRACE_ON(GfxdConstants.TRACE_LOCK);
    TraceTran = SanityManager.TRACE_ON(GfxdConstants.TRACE_TRAN);
    TraceTranVerbose = SanityManager.TRACE_ON(GfxdConstants.TRACE_TRAN_VERBOSE);
    TraceConglomRead = SanityManager.TRACE_ON(GfxdConstants.TRACE_CONGLOM_READ);
    TraceConglomUpdate = TraceConglomRead
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_CONGLOM_UPDATE);
    TraceRowFormatter = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_ROW_FORMATTER);
    TraceCM = SanityManager.TRACE_ON(GfxdConstants.TRACE_CONTEXT_MANAGER);
    TraceRSIter = TraceRowFormatter
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_RSITER);
    TraceAggreg = SanityManager.TRACE_ON(GfxdConstants.TRACE_AGGREG);
    TraceGroupByQI = SanityManager.TRACE_ON(GfxdConstants.TRACE_GROUPBYQI);
    TraceGroupByIter = TraceRowFormatter
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_GROUPBYITER);
    TraceActivation = SanityManager.TRACE_ON(GfxdConstants.TRACE_ACTIVATION);
    TraceAuthentication = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_AUTHENTICATION);
    TraceHeapThresh = SanityManager.TRACE_ON(GfxdConstants.TRACE_HEAPTHRESH);
    TraceMembers = SanityManager.TRACE_ON(GfxdConstants.TRACE_MEMBERS);
    TraceDDLQueue = SanityManager.TRACE_ON(GfxdConstants.TRACE_DDLQUEUE);
    TraceConglom = TraceQuery || TraceIndex || TraceDDLQueue
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_CONGLOM);
    TraceDDLReplay = TraceDDLQueue
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_DDLREPLAY);
    TraceConflation = TraceDDLQueue
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_CONFLATION);
    TraceDBSynchronizer = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_DB_SYNCHRONIZER);
    TraceDBSynchronizerHA = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_DB_SYNCHRONIZER_HA);
    TraceFunctionException = (TraceQuery || DistributionManager.VERBOSE
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_FUNCTION_EX))
        && !SanityManager.TRACE_OFF(GfxdConstants.TRACE_FUNCTION_EX);
    TraceProcedureExecution = (TraceQuery || DistributionManager.VERBOSE
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_PROCEDURE_EXEC))
        && !SanityManager.TRACE_OFF(GfxdConstants.TRACE_PROCEDURE_EXEC);

    TraceStatementMatching = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_STATEMENT_MATCHING);

    // following trace can be switched on anytime with info level logging.
    TraceFabricServiceBoot = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_FABRIC_SERVICE_BOOT);

    TraceConnectionSignaller = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_CONNECTION_SIGNALLER);

    TraceTrigger = SanityManager.TRACE_ON(GfxdConstants.TRACE_TRIGGER);

    TraceByteComparisonOptimization = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_BYTE_COMPARE_OPTIMIZATION);

    TracePlanGeneration = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_PLAN_GENERATION);
    
    TracePlanAssertion = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_PLAN_ASSERTION);
    
    TraceStatsGeneration = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_STATS_GENERATION);

    TraceTempFileIO = SanityManager.TRACE_ON(GfxdConstants.TRACE_TEMP_FILE_IO);

    // trace flag for client HA
    SanityManager.TraceClientHA = SanityManager
        .TRACE_ON(SanityManager.TRACE_CLIENT_HA);

    TraceApplicationJars = (TraceQuery || DistributionManager.VERBOSE
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_PROCEDURE_EXEC)
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_APP_JARS))
        && !SanityManager.TRACE_OFF(GfxdConstants.TRACE_APP_JARS);

    TraceOuterJoin = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_OUTERJOIN_MERGING);
    TraceSortTuning = SanityManager.TRACE_ON(GfxdConstants.TRACE_SORT_TUNING);
    TraceImport = SanityManager.TRACE_ON(GfxdConstants.TRACE_IMPORT);
    
    TraceNCJ = SanityManager.TRACE_ON(GfxdConstants.TRACE_NON_COLLOCATED_JOIN);
    
    TraceNCJIter = SanityManager.TRACE_ON(GfxdConstants.TRACE_NCJ_ITER);
    
    TraceNCJDump = SanityManager.TRACE_ON(GfxdConstants.TRACE_NCJ_DUMP);

    TracePersistIndexFinest = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_PERSIST_INDEX_FINEST);
    TracePersistIndexFiner = TracePersistIndexFinest
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_PERSIST_INDEX_FINER);
    TracePersistIndex = TracePersistIndexFiner
        || SanityManager.TRACE_ON(GfxdConstants.TRACE_PERSIST_INDEX);
    if (TracePersistIndex) {
      DiskStoreImpl.INDEX_LOAD_DEBUG = true;
      DiskStoreImpl.INDEX_LOAD_PERF_DEBUG = true;
    }

    TraceThriftAPI = SanityManager.TRACE_ON(GfxdConstants.TRACE_THRIFT_API);
    TraceSavePoints = SanityManager.TRACE_ON("traceSavepoints");
    TraceParseTree = SanityManager.TRACE_ON("DumpParseTree");
    TraceStopAfterParse = SanityManager.TRACE_ON("StopAfterParsing");
    if (TraceQuery || TraceIndex) {
      SanityManager.TRACE_SET_IF_ABSENT("AssertFailureTrace");
    }

    // initialize GFE layer verbose logging as required
    initGFEFlags();

    setTraceExecution(SanityManager.TRACE_ON(GfxdConstants.TRACE_EXECUTION));

    TraceSysProcedures = SanityManager
        .TRACE_ON(GfxdConstants.TRACE_SYS_PROCEDURES) ||
        (TraceExecution && !SanityManager.TRACE_OFF(
            GfxdConstants.TRACE_SYS_PROCEDURES));
  }

  private static void setTraceExecution(boolean force) {

    TraceExecution = force || TraceQuery || SanityManager.isFineEnabled
        || TXStateProxy.TRACE_EXECUTE || DistributionManager.VERBOSE
        || TraceDBSynchronizer || TraceDBSynchronizerHA || TraceActivation
        || TraceLock || ExclusiveSharedSynchronizer.TRACE_LOCK_COMPACT
        || TraceNCJ;
  }

  private static void initGFEFlags() {
    // also turn on GemFire lock tracing if required
    ExclusiveSharedSynchronizer.initProperties();
    if (TraceLock || SanityManager
        .TRACE_ON(ExclusiveSharedSynchronizer.TRACE_LOCK_PROPERTY)) {
      ExclusiveSharedSynchronizer.TRACE_LOCK = true;
    }
    if (SanityManager.TRACE_ON("DistributionManager.VERBOSE")) {
      DistributionManager.VERBOSE = true;
    }
    else if (SanityManager.TRACE_OFF("DistributionManager.VERBOSE")) {
      DistributionManager.VERBOSE = false;
    }
    if (TracePersistIndex) {
      DiskStoreImpl.INDEX_LOAD_DEBUG = true;
      DiskStoreImpl.INDEX_LOAD_PERF_DEBUG = true;
    }
    if (TracePersistIndexFiner) {
      DiskStoreImpl.INDEX_LOAD_DEBUG = true;
      DiskStoreImpl.INDEX_LOAD_DEBUG_FINER = true;
      DiskStoreImpl.INDEX_LOAD_PERF_DEBUG = true;
    }
    if (SanityManager.isFineEnabled || TraceTempFileIO) {
      ArraySortedCollectionWithOverflow.TRACE_TEMP_FILE_IO = true;
    }
    // also turn on GemFire layer transaction logging if required
    TXStateProxy.TRACE_SET(
        TraceTran || TXStateProxy.VERBOSE_ON()
            || SanityManager.TRACE_ON(TXStateProxy.VERBOSE_PROPERTY),
        TraceTranVerbose || TXStateProxy.VERBOSEVERBOSE_ON()
            || SanityManager.TRACE_ON(TXStateProxy.VERBOSEVERBOSE_PROPERTY),
        TraceQuery, GemFireCacheImpl.getInstance());
    // always wait on exceptions in GemFireXD (bug #41262)
    ReplyProcessor21.WAIT_ON_EXCEPTION = true;
  }

  public final static class Pair<K, V> implements Comparable<K>,
      Map.Entry<K, V>, Serializable {

    private static final long serialVersionUID = 8864720955309084190L;

    private final K key;

    private final V val;

    public Pair(K k, V v) {
      this.key = k;
      this.val = v;
    }

    @Override
    public final K getKey() {
      return this.key;
    }

    @Override
    public final V getValue() {
      return this.val;
    }

    @Override
    public V setValue(V value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
      int h = 0;
      if (this.key != null) {
        h ^= this.key.hashCode();
      }
      if (this.val != null) {
        h ^= this.val.hashCode();
      }
      return h;
    }

    @Override
    public boolean equals(final Object o) {
      if (o instanceof Pair<?, ?>) {
        final Pair<?, ?> other = (Pair<?, ?>)o;
        return ArrayUtils.objectEquals(this.key, other.key)
            && ArrayUtils.objectEquals(this.val, other.val);
      }
      return false;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public int compareTo(K o) {
      if (this.key instanceof Comparable<?>) {
        return ((Comparable)this.key).compareTo(o);
      }
      throw new IllegalArgumentException("key " + this.key + " not comparable");
    }

    @Override
    public String toString() {
      return "Pair<key=" + this.key + ",val=" + this.val + '>';
    }
  }
}
