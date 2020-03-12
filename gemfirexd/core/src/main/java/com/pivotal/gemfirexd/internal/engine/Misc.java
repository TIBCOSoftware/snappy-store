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

package com.pivotal.gemfirexd.internal.engine;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.Condition;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.ForcedDisconnectException;
import com.gemstone.gemfire.GemFireException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.execute.EmptyRegionFunctionException;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionInvocationTargetException;
import com.gemstone.gemfire.cache.execute.NoMemberFoundException;
import com.gemstone.gemfire.cache.hdfs.HDFSIOException;
import com.gemstone.gemfire.cache.persistence.PartitionOfflineException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.GemFireStatSampler;
import com.gemstone.gemfire.internal.InsufficientDiskSpaceException;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.NoDataStoreAvailableException;
import com.gemstone.gemfire.internal.cache.PRHARedundancyProvider;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.StoreCallbacks;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.Constants;
import com.pivotal.gemfirexd.auth.callback.UserAuthenticator;
import com.pivotal.gemfirexd.internal.engine.distributed.FunctionExecutionException;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.error.DerbySQLException;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecutionContext;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.jdbc.Util;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.AuthenticationServiceBase;
import com.pivotal.gemfirexd.internal.impl.jdbc.authentication.LDAPAuthenticationSchemeImpl;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.planexporter.CreateXML;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Some global miscellaneous stuff with an initialize method which logs some
 * basic information about the GemFireXD configuration. Contains some utility
 * methods that, if proven to be useful, can be moved to a more specific utility
 * class in the future.
 * 
 * @author Eric Zoerner
 */
public abstract class Misc {

  /** no instance allowed */
  private Misc() {
  }

  /**
   * Get the {@link LogWriter} for the cache/DS.
   */
  public static LogWriter getCacheLogWriter() {
    return getDistributedSystem().getLogWriter();
  }

  /**
   * Get the {@link LogWriter} for the cache returning null instead of throwing
   * {@link CacheClosedException} if the cache has been closed.
   */
  public static LogWriter getCacheLogWriterNoThrow() {
    final GemFireCacheImpl cache = getGemFireCacheNoThrow();
    if (cache != null) {
      return cache.getLogger();
    }
    return null;
  }

  public static LogWriterI18n getI18NLogWriter() {
    return getDistributedSystem().getLogWriterI18n();
  }

  /**
   * Return the {@link GemFireStore} instance.
   */
  public final static GemFireStore getMemStore() {
    final GemFireStore memStore = GemFireStore.getBootedInstance();
    if (memStore != null) {
      return memStore;
    }
    throw new CacheClosedException("Misc#getMemStore: no store found."
        + " GemFireXD not booted or closed down.");
  }

  /**
   * Return the {@link GemFireStore} instance that may still be in the process
   * of being booted.
   */
  public final static GemFireStore getMemStoreBooting() {
    final GemFireStore memStore = GemFireStore.getBootingInstance();
    if (memStore != null) {
      return memStore;
    }
    throw new CacheClosedException("Misc#getMemStoreBooting: no store found."
        + " GemFireXD not booted or closed down.");
  }
  
  public final static GemFireStore getMemStoreBootingNoThrow() {
    return GemFireStore.getBootingInstance();
  }

  public static void waitForSamplerInitialization() {
    InternalDistributedSystem system = getDistributedSystem();
    final GemFireStatSampler sampler = system.getStatSampler();
    if (sampler != null) {
      try {
        sampler.waitForInitialization(system.getConfig().getAckWaitThreshold() * 1000L);
      } catch (InterruptedException ie) {
        checkIfCacheClosing(ie);
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Return true if initial DDL replay is in progress. It may happen that this
   * is false even when {@link #initialDDLReplayDone()} returns false since
   * there may be configuration SQL scripts running.
   */
  public static boolean initialDDLReplayInProgress() {
    final GemFireStore memStore = GemFireStore.getBootingInstance();
    return (memStore == null || memStore.initialDDLReplayInProgress());
  }

  /**
   * Return true if initial DDL replay is complete.
   */
  public static boolean initialDDLReplayDone() {
    final GemFireStore memStore = GemFireStore.getBootingInstance();
    return (memStore != null && memStore.initialDDLReplayDone());
  }

  /**
   * Return the GemFire Cache.
   */
  public static GemFireCacheImpl getGemFireCache() {
    return GemFireCacheImpl.getExisting();
  }

  /**
   * Return the GemFire Cache. This returns null instead of throwing
   * {@link CacheClosedException} if the cache is closed.
   */
  public static GemFireCacheImpl getGemFireCacheNoThrow() {
    final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache != null && !cache.isClosed()) {
      return cache;
    }
    return null;
  }

  /**
   * Return the connected GemFire DistributedSystem else throws a
   * DistributedSystemDisconnectedException if no DS found or if it has been
   * disconnected.
   */
  public static InternalDistributedSystem getDistributedSystem() {
    // try to extract DS from the singleton cache since that is likely to be
    // more efficient
    final GemFireCacheImpl cache = getGemFireCacheNoThrow();
    if (cache != null) {
      return cache.getDistributedSystem();
    }
    // if no cache found or closed then fallback to IDS
    // try to avoid getConnectedInstance due to locking involved
    InternalDistributedSystem sys = InternalDistributedSystem.getAnyInstance();
    if (sys == null || !sys.isConnected()) {
      sys = InternalDistributedSystem.getConnectedInstance();
    }
    if (sys == null) {
      throw InternalDistributedSystem.newDisconnectedException(null);
    }
    return sys;
  }

  public static InternalDistributedMember getMyId() {
    final InternalDistributedMember self = GemFireStore.getMyId();
    if (self != null) {
      return self;
    }
    else {
      return getDistributedSystem().getDistributedMember();
    }
  }

  public static Set<DistributedMember> getLeadNode() {
    GfxdDistributionAdvisor advisor = GemFireXDUtils.getGfxdAdvisor();
    InternalDistributedSystem ids = Misc.getDistributedSystem();
    if (ids.isLoner()) {
      return Collections.<DistributedMember>singleton(
          ids.getDistributedMember());
    }
    Set<DistributedMember> allMembers = ids.getAllOtherMembers();
    for (DistributedMember m : allMembers) {
      GfxdDistributionAdvisor.GfxdProfile profile = advisor
          .getProfile((InternalDistributedMember)m);
      if (profile != null && profile.hasSparkURL()) {
        Set<DistributedMember> s = new HashSet<DistributedMember>();
        s.add(m);
        return Collections.unmodifiableSet(s);
      }
    }
    throw new NoMemberFoundException("SnappyData Lead node is not available");
  }

  /**
   * Check if {@link GemFireCache} is closed or is in the process of closing and
   * throw {@link CacheClosedException} if so.
   */
  public static void checkIfCacheClosing(Throwable t)
      throws CacheClosedException {
    final GemFireStore memStore = GemFireStore.getBootingInstance();
    if (memStore != null) {
      memStore.getAdvisee().getCancelCriterion().checkCancelInProgress(t);
      // clear interrupted flag before waiting somewhere else
      if (t instanceof InterruptedException) {
        Thread.interrupted();
      }
    }
    else {
      // in case of forced disconnect we need to see if the system is still
      // around and shutting down
      InternalDistributedSystem sys = InternalDistributedSystem.getAnyInstance();
      if (sys != null) {
        sys.getCancelCriterion().checkCancelInProgress(t);
        // clear interrupted flag before waiting somewhere else
        if (t instanceof InterruptedException) {
          Thread.interrupted();
        }
      } else {
        throw new CacheClosedException("Misc#getMemStoreBooting: no store found."
            + " GemFireXD not booted or closed down.");
      }
    }
  }

  public static void awaitForCondition(Condition cond) {
    while (true) {
      try {
        cond.await();
        break;
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        Misc.checkIfCacheClosing(ie);
      }
    }
  }

  /**
   * Check if {@link GemFireCache} is closed or in process of closing, if not
   * throw proposed RunTimeException else ignore the exception.
   *
   * @param e the actual exception being thrown which will be cause
   */
  public static void throwIfCacheNotClosed(RuntimeException e) {
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache == null || cache.isClosed()) {
      return;
    }

    String isCancelling = cache.getCancelCriterion().cancelInProgress();
    if (isCancelling == null) {
      throw e;
    }
  }

  public static <K, V> PartitionResolver createPartitionResolverForSampleTable(final String reservoirRegionName) {
    // TODO: Should this be serializable?
    // TODO: Should this have call back from bucket movement module?
    return new PartitionResolver() {

      public String getName()
      {
        return "PartitionResolverForSampleTable";
      }

      public Serializable getRoutingObject(EntryOperation opDetails)
      {
        Object k = opDetails.getKey();
        StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
        int v = callback.getLastIndexOfRow(k);
        if (v != -1) {
          return (Serializable)v;
        } else {
          return (Serializable)k;
        }
      }

      public void close() {}
    };
  }

  public static <K, V> String getReservoirRegionNameForSampleTable(
      String schema, String resolvedBaseName) {
    Region<K, V> regionBase = Misc.getRegionForTable(resolvedBaseName, true);
    return schema + "_SAMPLE_INTERNAL_" + regionBase.getName();
  }

  public volatile static boolean reservoirRegionCreated = false;

  public static PartitionedRegion createReservoirRegionForSampleTable(
      String reservoirRegionName, String resolvedBaseName) {
    Region<?, ?> regionBase = Misc.getRegionForTable(resolvedBaseName, false);
    GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
    Region<?, ?> childRegion = cache.getRegion(reservoirRegionName);
    if (childRegion == null && regionBase != null) {
      RegionAttributes<?, ?> attributesBase = regionBase.getAttributes();
      PartitionAttributes<?, ?> partitionAttributesBase = attributesBase.getPartitionAttributes();
      AttributesFactory afact = new AttributesFactory();
      afact.setDataPolicy(attributesBase.getDataPolicy());
      PartitionAttributesFactory paf = new PartitionAttributesFactory();
      paf.setTotalNumBuckets(partitionAttributesBase.getTotalNumBuckets());
      paf.setRedundantCopies(partitionAttributesBase.getRedundantCopies());
      paf.setLocalMaxMemory(partitionAttributesBase.getLocalMaxMemory());
      PartitionResolver partResolver = createPartitionResolverForSampleTable(reservoirRegionName);
      paf.setPartitionResolver(partResolver);
      paf.setColocatedWith(regionBase.getFullPath());
      afact.setPartitionAttributes(paf.create());
      childRegion = cache.createRegion(reservoirRegionName, afact.create());
    }
    if (childRegion != null) reservoirRegionCreated = true;
    return (PartitionedRegion)childRegion;
  }

  public static PartitionedRegion getReservoirRegionForSampleTable(String reservoirRegionName) {
    if (reservoirRegionName != null) {
      GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
      Region<?, ?> childRegion = cache.getRegion(reservoirRegionName);
      if (childRegion != null) {
        return (PartitionedRegion)childRegion;
      }
    }
    return null;
  }

  public static PartitionedRegion.PRLocalScanIterator
  getLocalBucketsIteratorForSampleTable(PartitionedRegion reservoirRegion,
      Set<Integer> bucketSet, boolean fetchFromRemote) {
    if (reservoirRegion != null && bucketSet != null) {
      if (bucketSet.size() > 0) {
        return reservoirRegion.getAppropriateLocalEntriesIterator(bucketSet,
            true, false, true, null, fetchFromRemote);
      }
    }
    return null;
  }

  /**
   * Given a table name of the form "SCHEMA_NAME.TABLE_NAME", return the GemFire
   * Region that hosts its data. If the name of the table does not have dot in
   * it, then the region for the schema by that name is returned. Throw a
   * RegionDestroyedException if "throwIfNotFound" flag is true and the region
   * was not found.
   * 
   * @param tableName
   *          the fully qualified name of the table or schema
   */
  public static <K, V> Region<K, V> getRegionForTable(String tableName,
      boolean throwIfNotFound) {
    return getRegion(getRegionPath(tableName), throwIfNotFound, false);
  }

  public static String getRegionPath(String qualifiedTableName) {
    final StringBuilder sb = new StringBuilder(qualifiedTableName);
    if (sb.charAt(0) != '/') {
      sb.insert(0, '/');
    }
    int len = sb.length();
    for (int i = 0; i < len; ++i) {
      char ch = sb.charAt(i);
      if (ch == '.') {
        sb.setCharAt(i, '/');
      }
    }
    return sb.toString();
  }

  /**
   * Get the region corresponding to given path. Throw a
   * RegionDestroyedException if "throwIfNotFound" flag is true and the region
   * was not found.
   */
  @SuppressWarnings("unchecked")
  public static <K, V> Region<K, V> getRegion(String regionPath,
      boolean throwIfNotFound, final boolean returnUnInitialized) throws RegionDestroyedException {
    if (throwIfNotFound) {
      final Region<K, V> region = getGemFireCache().getRegion(
          regionPath, false, returnUnInitialized);
      if (region != null) {
        return region;
      }
      else {
        throw new RegionDestroyedException(
            LocalizedStrings.Region_CLOSED_OR_DESTROYED
                .toLocalizedString(regionPath), regionPath);
      }
    }
    else {
      return getGemFireCache().getRegion(
          regionPath, false, returnUnInitialized);
    }
  }

  /**
   * Get the region corresponding to given path; will return an uninitialized
   * region also instead of waiting for it to initialize. Throw a
   * RegionDestroyedException if region was not found.
   */
  public static LocalRegion getRegionByPath(String regionPath) {
    final LocalRegion region = getGemFireCache().getRegionByPath(regionPath, false);
    if (region != null) {
      return region;
    }
    else {
      throw new RegionDestroyedException(
          LocalizedStrings.Region_CLOSED_OR_DESTROYED
              .toLocalizedString(regionPath),
          regionPath);
    }
  }

  /**
   * Get the region corresponding to given path; will return an uninitialized
   * region also instead of waiting for it to initialize. If the "throwIfFound"
   * flag is true then throw a RegionDestroyedException if region was not found.
   */
  @SuppressWarnings("unchecked")
  public static <K, V> Region<K, V> getRegionByPath(String regionPath,
      boolean throwIfNotFound) {
    if (throwIfNotFound) {
      return getRegionByPath(regionPath);
    }
    else {
      return getGemFireCache().getRegionByPath(regionPath, false);
    }
  }

  /** get the region corresponding to the TableDescriptor */
  public static <K, V> Region<K, V> getRegion(TableDescriptor td,
      LanguageConnectionContext lcc, boolean throwIfNotFound,
      final boolean returnUnInitialized) {
    return getRegion(getRegionPath(td, lcc), throwIfNotFound,
        returnUnInitialized);
  }

  /**
   * Get the region corresponding to the TableDescriptor. Can return
   * uninitialized region.
   */
  public static <K, V> Region<K, V> getRegionByPath(TableDescriptor td,
      LanguageConnectionContext lcc, boolean throwIfNotFound) {
    return getRegionByPath(getRegionPath(td, lcc), throwIfNotFound);
  }

  /**
   * Given a table name of the form "SCHEMA_NAME.TABLE_NAME", return the GemFire
   * Region that hosts its data. Can return uninitialized region. If the name of
   * the table does not have dot in it, then the region for the schema by that
   * name is returned. Throw a RegionDestroyedException if "throwIfNotFound"
   * flag is true and the region was not found.
   * 
   * @param tableName
   *          the fully qualified name of the table or schema
   */
  public static <K, V> Region<K, V> getRegionForTableByPath(String tableName,
      boolean throwIfNotFound) {
    return getRegionByPath(getRegionPath(tableName), throwIfNotFound);
  }

  /** get the LanguageConnectionContext object */
  public static LanguageConnectionContext getLanguageConnectionContext() {
    return (LanguageConnectionContext)ContextService
        .getContextOrNull(LanguageConnectionContext.CONTEXT_ID);
  }

  /** get actual schema name */
  public static String getSchemaName(String schemaName,
      LanguageConnectionContext lcc) {
    return ((schemaName != null && schemaName.length() > 0) ? schemaName
        : getDefaultSchemaName(lcc));
  }

  public static String getDefaultSchemaName(LanguageConnectionContext lcc) {
    if (lcc == null) {
      lcc = getLanguageConnectionContext();
    }
    if (lcc == null) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "No current connection (LCC null).", Util.noCurrentConnection());
    }
    return lcc.getDefaultSchema().getSchemaName();
  }

  public static String getRegionPath(String schemaName, String tableName,
      LanguageConnectionContext lcc) {
    return new StringBuilder().append('/')
        .append(getSchemaName(schemaName, lcc)).append('/').append(tableName)
        .toString();
  }

  public static String getRegionPath(TableDescriptor td,
      LanguageConnectionContext lcc) {
    return getRegionPath(td.getSchemaName(), td.getName(), lcc);
  }

  public static String getFullTableNameFromRegionPath(final String regionPath) {
    if (regionPath != null && regionPath.length() > 0) {
      final int start = regionPath.charAt(0) == '/' ? 1 : 0;
      final int end = regionPath.indexOf('/', start + 1);
      return end != -1 ? (regionPath.substring(start, end) + '.' + regionPath
          .substring(end + 1)) : regionPath.substring(start);
    }
    else {
      return "";
    }
  }

  public static String getFullTableName(String schemaName, String tableName,
      LanguageConnectionContext lcc) {
    return generateFullTableName(new StringBuilder(), schemaName, tableName,
        lcc).toString();
  }

  /**
   * Generates full table name by appending to given StringBuilder and returning
   * it.
   */
  public static StringBuilder generateFullTableName(StringBuilder sb,
      String schemaName, String tableName, LanguageConnectionContext lcc) {
    return sb.append(getSchemaName(schemaName, lcc)).append('.')
        .append(tableName);
  }

  public static String getFullTableName(TableDescriptor td,
      LanguageConnectionContext lcc) {
    return getFullTableName(td.getSchemaName(), td.getName(), lcc);
  }

  /**
   * Convert a SQL ResultSet object to a XML Element. The
   * <code>addTypeInfo</code> argument specifies whether or not to add a "type"
   * attribute to each field that has the result of {@link Class#getName()} as
   * its value.
   * 
   * @param ignoreTypeInfo
   *          if type of the result set has to be ignored and just its
   *          toString() value is to be set in the XML
   */
  public static Element resultSetToXMLElement(ResultSet rs,
      boolean addTypeInfo, boolean ignoreTypeInfo) throws SQLException,
      IOException, ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.newDocument();
    Element resultSetElement = doc.createElement("resultSet");
    doc.appendChild(resultSetElement);
    if (rs != null) {
      ResultSetMetaData rsMD = rs.getMetaData();
      int numCols = rsMD.getColumnCount();
      while (rs.next()) {
        Element resultElement = doc.createElement("row");
        resultSetElement.appendChild(resultElement);
        for (int index = 1; index <= numCols; ++index) {
          String colName = rsMD.getColumnName(index);
          Element fieldElement = doc.createElement("field");
          fieldElement.setAttribute("name", colName);
          if (addTypeInfo && !ignoreTypeInfo) {
            Object result = rs.getObject(index);
            if (result != null) {
              fieldElement.setAttribute("type", result.getClass().getName());
            }
          }
          String valStr = rs.getString(index);
          if (valStr == null) {
            valStr = "NULL";
          }
          if (valStr.length() > 0) {
            fieldElement.appendChild(doc.createTextNode(valStr));
          }
          resultElement.appendChild(fieldElement);
        }
      }
      rs.close();
    }
    return resultSetElement;
  }

  /** serialize a given XML Element to a XML string representation */
  public static String serializeXML(Element el) throws TransformerException {
    TransformerFactory transfac = TransformerFactory.newInstance();
    Transformer trans = transfac.newTransformer();
    trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
    trans.setOutputProperty(OutputKeys.INDENT, "yes");

    // create string from xml tree
    StringWriter sw = new StringWriter();
    StreamResult result = new StreamResult(sw);
    DOMSource source = new DOMSource(el);
    trans.transform(source, result);
    return sw.toString();
  }

  public static char[] serializeXMLAsCharArr(List<Element> el,
      String xsltFileName) {
    try {

      TransformerFactory tFactory = TransformerFactory.newInstance();
      CharArrayWriter cw = new CharArrayWriter();

      ClassLoader cl = InternalDistributedSystem.class.getClassLoader();
      // fix for bug 33274 - null classloader in Sybase app server
      if (cl == null) {
        cl = ClassLoader.getSystemClassLoader();
      }

      final String style = CreateXML.class.getPackage().getName()
          .replaceAll("\\.", "/")
          + "/resources/" + xsltFileName;
      if (GemFireXDUtils.TracePlanGeneration) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
            "using stylesheet : " + style);
      }
      InputStream is = cl.getResourceAsStream(style);

      Transformer transformer = tFactory
          .newTransformer(new javax.xml.transform.stream.StreamSource(is));

      for (Element e : el) {
        DOMSource source = new DOMSource(e);
        StreamResult result = new StreamResult(cw);

        if (GemFireXDUtils.TracePlanGeneration) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PLAN_GENERATION,
              "about to transform " + e);
        }
        transformer.transform(source, result);
      }

      is.close();
      return cw.toCharArray();
    } catch (Exception te) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "serializeXMLAsCharArr: unexpected exception", te);
    }
  }

  public static int getHashCodeFromDVD(DataValueDescriptor dvd) {
    int hash = 0;
    if (dvd != null) {
      // The following line is an issue with SQLVarChar since
      // this returns a string and their is a difference in hash value
      // for SqlVarChar and string for the same varchar.
      /*Object o = dvd.getObject();
      if (o != null) {
        hash = o.hashCode();
      }*/// fix for #40407 # 40379
      hash = dvd.hashCode();
    }
    return hash;
  }

  public static int getUnifiedHashCodeFromDVD(DataValueDescriptor dvd,
      int numPartitions) {
    StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
    if (dvd != null) {
      return callback.getHashCodeSnappy(dvd, numPartitions);
    } else {
      return 0;
    }
  }

  public static int getUnifiedHashCodeFromDVD(DataValueDescriptor[] dvds,
      int numPartitions) {
    StoreCallbacks callback = CallbackFactoryProvider.getStoreCallbacks();
    if (dvds != null) {
      return callback.getHashCodeSnappy(dvds, numPartitions);
    } else {
      return 0;
    }
  }

  /**
   * Returns true if security is enabled for SnappyData.
   * Only LDAP scheme is supported currently.
   */
  public static boolean isSecurityEnabled() {
    AuthenticationServiceBase authService = Misc.getMemStoreBooting()
        .getDatabase().getAuthenticationService();
    return authService != null &&
        authService.getAuthenticationScheme() instanceof LDAPAuthenticationSchemeImpl;
  }

  /* Returns true if LDAP Security is Enabled */
  public static boolean checkLDAPAuthProvider(Map<Object, Object> map) {
    return Constants.AUTHENTICATION_PROVIDER_LDAP.equalsIgnoreCase(
        String.valueOf(map.get(Attribute.AUTH_PROVIDER)));
  }

  /**
   * Check if ldapGroupName is indeed name of a LDAP group. Expand that group and check if user
   * belongs to that group.
   */
  public static boolean checkLDAPGroupOwnership(String schemaName, String ldapGroupName, String user)
      throws StandardException {
    if (ldapGroupName.startsWith(Constants.LDAP_GROUP_PREFIX)) {
      UserAuthenticator auth = Misc.getMemStoreBooting()
          .getDatabase().getAuthenticationService().getAuthenticationScheme();
      if (auth instanceof LDAPAuthenticationSchemeImpl) {
        String group = ldapGroupName.substring(Constants.LDAP_GROUP_PREFIX.length());
        try {
          if (((LDAPAuthenticationSchemeImpl)auth).getLDAPGroupMembers(group).contains(user)) {
            if (GemFireXDUtils.TraceAuthentication) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
                  "Found user " + user + " in LDAP group " + group + " for schema " + schemaName);
            }
            return true;
          }
        } catch (Exception e) {
          throw StandardException.newException(
              SQLState.AUTH_INVALID_LDAP_GROUP, e, group);
        }
        if (GemFireXDUtils.TraceAuthentication) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_AUTHENTICATION,
              "Could not find user " + user + " in LDAP group " + group + " for schema "
                  + schemaName);
        }
      }
    }
    return false;
  }

  // added by jing for processing the exception
  public static StandardException processFunctionException(String op,
      final Throwable thr, DistributedMember member, Region<?, ?> region) {
    return processFunctionException(op, thr, member, region, true);
  }

  // added by jing for processing the exception
  public static StandardException processFunctionException(String op,
      final Throwable thr, DistributedMember member, Region<?, ?> region,
      boolean throwUnknownException) {
    Throwable t = thr;
    Throwable expEx = thr; // expected exception FunctionExecutionException
    StandardException stdEx = null;
    GemFireException gfeex = null;

    while (expEx != null) {
      if (expEx instanceof SQLException) {
        stdEx = wrapRemoteSQLException((SQLException)expEx, thr, member);
      }
      else if (expEx instanceof StandardException) {
        stdEx = (StandardException)expEx;
        member = StandardException.fixUpRemoteException(t, member);
        stdEx = StandardException.newException(stdEx.getMessageId(), thr,
            stdEx.getArguments());
      }
      else if (gfeex == null && expEx instanceof GemFireException) {
        // Asif: Bug#41679. In come cases we are getting TransactionException
        // (TransactionDUnit.testBug41679) and in case of hydra test mentioned
        // in the bug mail it is TransactionDataNotolocatedException.
        // Have not attempted to dig deeper.
        // [sumedh] no longer valid with new TX implementation
        gfeex = (GemFireException)expEx;
      }
      if (stdEx == null) {
        // some exceptions only do wrapping and can be local, so remove those to
        // correctly add remote member information to the stack trace
        while (t != null
            && (t instanceof FunctionExecutionException
                || t instanceof GemFireXDRuntimeException || (t.getClass()
                .equals(FunctionException.class) && t.getMessage().equals(
                String.valueOf(t.getCause()))))) {
          t = t.getCause();
        }
        expEx = expEx.getCause();
        continue;
      }
      break;
    }
    if (stdEx == null && gfeex != null) {
      member = StandardException.fixUpRemoteException(t, member);
      stdEx = processGemFireException(gfeex, thr, "execution on remote node "
          + member, false);
    }
    if (stdEx == null && throwUnknownException) {
      // check if this VM is shutting down
      getGemFireCache().getCancelCriterion().checkCancelInProgress(thr);
      // if everything else fails, wrap in unexpected exception
      StandardException.fixUpRemoteException(t, member);
      stdEx = StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, thr, thr.toString());
    }
    if (GemFireXDUtils.TraceFunctionException) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_FUNCTION_EX, op
          + " encountered exception during execution"
          + (region != null ? (" on region " + region.getFullPath()) : ""),
          (thr instanceof FunctionExecutionException) ? thr.getCause() : thr);
    }
    return stdEx;
  }

  public static StandardException processKnownGemFireException(
      GemFireException gfeex, final Throwable cause, final String op,
      final boolean getForUnknown) {
    // check if the exception is just wrapped
    if ((gfeex instanceof FunctionInvocationTargetException
        || gfeex instanceof FunctionExecutionException
        || gfeex.getClass().equals(FunctionException.class))
        && gfeex.getCause() instanceof GemFireException) {
      gfeex = (GemFireException)gfeex.getCause();
    }
    if (getForUnknown) {
      StandardException.fixUpRemoteException(gfeex, null);
    }
    if (gfeex instanceof ConflictException) {
      return StandardException.newException(SQLState.GFXD_OPERATION_CONFLICT,
          cause, gfeex.getMessage());
    }
    else if (gfeex instanceof IllegalTransactionStateException) {
      return StandardException.newException(SQLState.GFXD_TRANSACTION_ILLEGAL,
          cause, cause.getLocalizedMessage());
    }
    else if (gfeex instanceof TransactionStateReadOnlyException) {
      // this can happen if a transaction can only be rolled back e.g.
      // previous conflict
      return StandardException.newException(
          SQLState.GFXD_TRANSACTION_READ_ONLY, cause,
          cause.getLocalizedMessage());
    }
    else if (gfeex instanceof TransactionDataNodeHasDepartedException) {
      return StandardException.newException(SQLState.DATA_CONTAINER_CLOSED,
          cause, StandardException.getSenderFromExceptionOrSelf(cause), "");
    }
    else if (gfeex instanceof TransactionDataRebalancedException) {
      TransactionDataRebalancedException tdre =
          (TransactionDataRebalancedException)gfeex;
      return StandardException.newException(SQLState.DATA_CONTAINER_VANISHED,
          cause, getFullTableNameFromRegionPath(tdre.getRegionPath()));
    }
    else if (gfeex instanceof TransactionInDoubtException) {
      return StandardException.newException(SQLState.GFXD_TRANSACTION_INDOUBT,
          cause);
    }
    else if (gfeex instanceof BucketMovedException) {
      // for transactional case this will be TX exception state
      if (TXManagerImpl.getCurrentTXState() != null) {
        return StandardException.newException(SQLState.DATA_CONTAINER_CLOSED,
            cause, StandardException.getSenderFromExceptionOrSelf(cause), "");
      }
      final BucketMovedException bme = (BucketMovedException)gfeex;
      return StandardException.newException(SQLState.GFXD_NODE_BUCKET_MOVED,
          cause, bme.getBucketId(),
          getFullTableNameFromRegionPath(bme.getRegionName()));
    }
    else if (gfeex instanceof PartitionedRegionStorageException) {
      String expected = LocalizedStrings
          .PRHARRedundancyProvider_UNABLE_TO_FIND_ANY_MEMBERS_TO_HOST_A_BUCKET_IN_THE_PARTITIONED_REGION_0
            .toLocalizedString();
      expected = expected.substring(0, expected.indexOf('.'));
      String msgThis = gfeex.getLocalizedMessage();
      if (msgThis.contains(expected)
          || msgThis.contains(LocalizedStrings
              .PRHARRedundancyProvider_ALLOCATE_ENOUGH_MEMBERS_TO_HOST_BUCKET
                .toLocalizedString())
          || msgThis.contains(PRHARedundancyProvider
              .INSUFFICIENT_STORES_MSG.toLocalizedString())) {
        // try to extract the region path from message
        String target = op;
        int prStart = msgThis.indexOf(PRHARedundancyProvider.PRLOG_PREFIX);
        if (prStart >= 0) {
          prStart += PRHARedundancyProvider.PRLOG_PREFIX.length();
          final int len = msgThis.length();
          int index;
          for (index = prStart; index < len; index++) {
            if (Character.isWhitespace(msgThis.charAt(index))) {
              break;
            }
          }
          if (index > prStart) {
            target = getFullTableNameFromRegionPath(msgThis.substring(prStart,
                index));
          }
        }
        return StandardException.newException(SQLState.NO_DATASTORE_FOUND,
            cause, target);
      }
    }
    else if (gfeex instanceof NoMemberFoundException) {
      return StandardException.newException(SQLState.NO_DATASTORE_FOUND, cause,
          op);
    }
    else if (gfeex instanceof EmptyRegionFunctionException) {
      return StandardException.newException(SQLState.NO_DATASTORE_FOUND, cause,
          op);
    }
    else if (gfeex instanceof NoDataStoreAvailableException) {
      String[] messageParts = gfeex.getMessage().split(": ");
      return StandardException.newException(SQLState.NO_DATASTORE_FOUND, cause,
          messageParts.length > 1 ? messageParts[1] : op);
    }
    else if (gfeex instanceof PartitionOfflineException) {
      return StandardException.newException(SQLState.INSUFFICIENT_DATASTORE,
          cause);
    }
    else if (gfeex instanceof PutAllPartialResultException) {
      Throwable partialExceptionCause = ((PutAllPartialResultException)gfeex)
          .getFailure();
      if (partialExceptionCause instanceof PartitionedRegionStorageException) {
        String expected = LocalizedStrings.PRHARRedundancyProvider_UNABLE_TO_FIND_ANY_MEMBERS_TO_HOST_A_BUCKET_IN_THE_PARTITIONED_REGION_0
            .toLocalizedString();
        expected = expected.substring(0, expected.indexOf('.'));

        String msgThis = partialExceptionCause.getLocalizedMessage();
        if (msgThis.contains(expected)
            || partialExceptionCause
                .getLocalizedMessage()
                .contains(
                    LocalizedStrings.PRHARRedundancyProvider_ALLOCATE_ENOUGH_MEMBERS_TO_HOST_BUCKET
                        .toLocalizedString())
            || partialExceptionCause
                .getLocalizedMessage()
                .contains(
                    LocalizedStrings.PRHARRedundancyProvider_UNABLE_TO_FIND_ANY_MEMBERS_TO_HOST_A_BUCKET_IN_THE_PARTITIONED_REGION_0
                        .toLocalizedString())) {
          return StandardException.newException(SQLState.INSUFFICIENT_DATASTORE,
              cause, op);
        }
      }
    }
    else if (gfeex instanceof LowMemoryException) {
      return generateLowMemoryException(op);
    }
    else if (gfeex instanceof RegionDestroyedException) {
      final RegionDestroyedException rde = (RegionDestroyedException)gfeex;
      return StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND,
          cause, getFullTableNameFromRegionPath(rde.getRegionFullPath()));
    }
    else if (gfeex instanceof HDFSIOException) {
      return StandardException.newException(SQLState.HDFS_ERROR, gfeex, op);
    }
    else if (gfeex instanceof InsufficientDiskSpaceException) {
      return StandardException.newException(SQLState.GFXD_DISK_SPACE_FULL, gfeex, gfeex.getMessage());
    }
    else if (gfeex instanceof DiskAccessException) {
      return StandardException.newException(SQLState.DATA_UNEXPECTED_EXCEPTION,
          gfeex);
    }
    Throwable innerCause = gfeex.getCause();
    if (innerCause instanceof GemFireException) {
      StandardException se = processKnownGemFireException(
          (GemFireException)innerCause, innerCause, op, false);
      if (se != null) {
        return se;
      }
    }
    if (getForUnknown) {
      // try unwinding and looking for exception from remote node
      StandardException se = processFunctionException(op, gfeex, null, null);
      if (se != null) {
        return se;
      }
    }
    return null;
  }

  public static StandardException processGemFireException(
      final GemFireException gfeex, final Throwable cause, final String op,
      final boolean getForUnknown) {
    StandardException se = processKnownGemFireException(gfeex, cause, op,
        getForUnknown);
    if (se != null) {
      return se;
    }
    if (gfeex instanceof TransactionException) {
      return StandardException.newException(SQLState.GFXD_TRANSACTION_ILLEGAL,
          cause, cause.getLocalizedMessage());
    }
    else if (gfeex instanceof CacheException) {
      // throw back cache exceptions (e.g. EntryNotFoundException) to higher
      // layers for processing
      throw gfeex;
    }
    else if (gfeex instanceof CancelException) {
      // if this is due to shutdown itself, then don't mask actual exception
      // with another CancelException due to getGemFireCache() call (#48312)
      if (gfeex.getCause() instanceof ForcedDisconnectException) {
        return StandardException.newException(
            SQLState.GFXD_FORCED_DISCONNECT_EXCEPTION, gfeex);
      } else {
        throw gfeex;
      }
    }
    else {
      // check if this VM is shutting down
      getGemFireCache().getCancelCriterion().checkCancelInProgress(gfeex);
      // if everything else fails, wrap in unexpected exception
      return StandardException.newException(
          SQLState.LANG_UNEXPECTED_USER_EXCEPTION, gfeex, gfeex.toString());
    }
  }

  public static StandardException processRuntimeException(
      final Throwable e, final String op, final Region<?, ?> region) {
    Throwable t = e;
    do {
      if (t instanceof StandardException) {
        return (StandardException)t;
      }
      if (t instanceof SQLException) {
        return wrapRemoteSQLException((SQLException)t, e, null);
      }
      t = t.getCause();
    } while (t != null);
    return processFunctionException(op, e, null, region);
  }

  public static StandardException wrapRemoteSQLException(
      final SQLException sqle, final Throwable remoteEx,
      DistributedMember member) {
    StandardException.fixUpRemoteException(sqle, member);
    return wrapSQLException(sqle, remoteEx);
  }

  public static StandardException wrapSQLException(final SQLException sqle,
      final Throwable root) {
    final StandardException stde;
    if (sqle instanceof DerbySQLException) {
      final DerbySQLException dsqle = (DerbySQLException)sqle;
      if (dsqle.isSimpleWrapper()
          && sqle.getCause() instanceof StandardException) {
        stde = (StandardException)sqle.getCause();
        return StandardException.newException(stde.getMessageId(), root,
            stde.getArguments());
      }
      if (dsqle.getArguments() != null) {
        stde = StandardException.newException(dsqle.getMessageId(), root,
            dsqle.getArguments());
        stde.setSeverity(sqle.getErrorCode());
        return stde;
      }
    }
    // Return pre-localized exception else it expects arguments which may no
    // longer be available from SQLException (see bug #40016 for more details).
    stde = StandardException.newPreLocalizedException(sqle.getSQLState(), root,
        sqle.getLocalizedMessage());
    stde.setSeverity(sqle.getErrorCode());
    return stde;
  }

  public final static long estimateMemoryUsage(
      final LanguageConnectionContext lcc,
      final com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet,
      String stmtText) throws StandardException {
    final GemFireXDQueryObserver observer = GemFireXDQueryObserverHolder
        .getInstance();

    if (observer != null) {
      observer.estimatingMemoryUsage(stmtText, resultSet);
    }

    long memory = lcc.getLanguageConnectionFactory().getExecutionFactory()
        .getResultSetStatisticsFactory().getResultSetMemoryUsage(resultSet);

    if (GemFireXDUtils.TraceHeapThresh) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_HEAPTHRESH,
          "Misc: Estimated working memory for " + stmtText + " ("
              + resultSet.getActivation().getPreparedStatement() + ") is "
              + memory);
    }

    if (observer != null) {
      memory = observer.estimatedMemoryUsage(stmtText, memory);
    }

    return memory;
  }

  public final static StandardException generateLowMemoryException(
      final String query) {
    LogWriter logger = getCacheLogWriterNoThrow();
    if (logger != null && logger.warningEnabled()) {
      logger.warning(GfxdConstants.TRACE_HEAPTHRESH + " cancelling statement ["
          + query + "] due to low memory");
    }
    CallbackFactoryProvider.getStoreCallbacks().logMemoryStats();
    final StandardException se = StandardException.newException(
        SQLState.LANG_STATEMENT_CANCELLED_ON_LOW_MEMORY, query,
        GemFireStore.getMyId());
    se.setReport(StandardException.REPORT_NEVER);
    return se;
  }

  public final static void checkMemoryRuntime(
      final GfxdHeapThresholdListener thresholdListener, final String query,
      final long required) {
    final StandardException se = generateLowMemoryException(thresholdListener,
        query, required);
    if (se == null) {
      return;
    }
    else {
      throw GemFireXDRuntimeException.newRuntimeException(
          "Aborting  due to low memory", se);
    }
  }

  public final static void checkMemory(
      final GfxdHeapThresholdListener thresholdListener, final String query,
      final long required) throws StandardException {
    final StandardException se = generateLowMemoryException(thresholdListener,
        query, required);
    if (se == null) {
      return;
    }
    else {
      throw se;
    }
  }

  private final static StandardException generateLowMemoryException(
      final GfxdHeapThresholdListener thresholdListener, final String query,
      final long required) {
    if (thresholdListener == null || !thresholdListener.isCritical()) {
      return null;
    }
    Runtime jvm = Runtime.getRuntime();
    final long currentFreeMemory = jvm.freeMemory();
    final long currentTotalMemory = jvm.totalMemory();
    if (required == -1 || required > (currentTotalMemory - currentFreeMemory)) {
      return generateLowMemoryException(query);
    }
    return null;
  }
  
  final static int [] sizeTable = { 9, 99, 999, 9999, 99999, 999999, 9999999,
    99999999, 999999999, Integer.MAX_VALUE };
  
  public static int getLength(int v) {
    if (v == Integer.MIN_VALUE)
      return "-2147483648".length();
    
    int x = (v < 0) ? -v : v;
    
    for (int i = 0;; i++) {
      if (x <= sizeTable[i])
        return v < 0 ? (i + 2) : (i + 1);
    }
  }
  
  public static int getLength(long v) {
    if (v == Long.MIN_VALUE)
      return "-9223372036854775808".length();
    
    long x = (v < 0) ? -v + 1 : v;
    long p = 10;
    for (int i=1; i<19; i++) {
        if (x < p)
            return (v < 0 ? i + 1 : i);
        p = 10*p;
    }
    return 19;
  }

  public static boolean parseBoolean(String s) {
    return ClientSharedUtils.parseBoolean(s);
  }

  public static TreeSet<Map.Entry<Integer, Long>> sortByValue(
      Map<Integer, Long> distribution) {
    TreeSet<Map.Entry<Integer, Long>> entriesByVal = new TreeSet<Map.Entry<Integer, Long>>(
        new Comparator<Map.Entry<Integer, Long>>() {
          @Override
          public int compare(Entry<Integer, Long> o1, Entry<Integer, Long> o2) {
            int i;
            // order descending....
            return (i = o2.getValue().compareTo(o1.getValue())) != 0 ? i : o2
                .getKey().compareTo(o1.getKey());
          }
        });

    for (Map.Entry<Integer, Long> e : distribution.entrySet()) {
      entriesByVal.add(new GemFireXDUtils.Pair<Integer, Long>(e.getKey(), e
          .getValue()));
    }
    return entriesByVal;
  }

  public static TreeMap<Long, Double[]> createHistogram(
      final Map<Integer, Long> distribution,
      TreeSet<Map.Entry<Integer, Long>> sortedByValue) {

    if (sortedByValue == null) {
      if (SanityManager.ASSERT) {
        SanityManager.ASSERT(distribution != null);
      }
      sortedByValue = sortByValue(distribution);
    }

    final int numDataPoints = sortedByValue.size();

    final int numIntervals = (int)Math.ceil(Math.sqrt(numDataPoints));

    final Long min = sortedByValue.last().getValue();
    final Long max = sortedByValue.first().getValue();

    final long width = (max - min) / numIntervals;
    final long stepValue = width > 10 ? (long)Math.pow(10,
        Math.ceil(Math.log10(width))) : numIntervals < 10 ? 1 : 10;
    long start = min - (stepValue / 2), end = max + stepValue;

    TreeMap<Long, Double[]> histogram = new TreeMap<Long, Double[]>();
    for (long i = start; i <= end; i += stepValue) {
      histogram.put(i, new Double[] { 0d, 0d });
    }

    final Iterator<Map.Entry<Integer, Long>> i = sortedByValue.descendingIterator();
    final Iterator<Map.Entry<Long, Double[]>> h = histogram.entrySet().iterator();
    Map.Entry<Long, Double[]> currentHist = h.hasNext() ? h.next() : null;
    Map.Entry<Long, Double[]> nextHist = h.hasNext() ? h.next() : null;

    while (i.hasNext()) {
      if (currentHist == null)
        break;
      long v = i.next().getValue();
      if (nextHist == null || v <= nextHist.getKey()) {
        currentHist.getValue()[0]++;
      }
      else {
        do {
          currentHist = nextHist;
          nextHist = h.hasNext() ? h.next() : null;
        } while (nextHist != null && v > currentHist.getKey());
        assert v <= currentHist.getKey();
        currentHist.getValue()[0]++;
      }
    }

    if (SanityManager.ASSERT) {
      SanityManager.ASSERT(!i.hasNext());
    }

    for (Map.Entry<Long, Double[]> e : histogram.entrySet()) {
      double percent = (e.getValue()[0] / numDataPoints) * 100d;
      e.getValue()[1] = percent;
    }

    return histogram;
  }

  public static StringBuilder histogramToString(
      TreeMap<Long, Double[]> histogram, StringBuilder str) {
    str.append("histogram:").append(SanityManager.lineSeparator);
    
    int maxKeyLength = 0, maxValLength = 0, maxPercentLength = 0;
    int len = 0;

    for (Map.Entry<Long, Double[]> e : histogram.entrySet()) {
      maxKeyLength = (len = Misc.getLength(e.getKey())) > maxKeyLength ? len
          : maxKeyLength;
      maxValLength = (len = Misc.getLength(e.getValue()[0].intValue())) > maxValLength ? len
          : maxValLength;
      maxPercentLength = (len = Misc.getLength(e.getValue()[1].intValue())) > maxPercentLength ? len
          : maxPercentLength;
    }

    Iterator<Entry<Long, Double[]>> hI = histogram.entrySet().iterator();
    Map.Entry<Long, Double[]> currV = hI.hasNext() ? hI.next() : null;
    Map.Entry<Long, Double[]> nextV = null;
    Long key = null;
    Double val = null;

    while (hI.hasNext()) {
      nextV = hI.next();
      for (int l = Misc.getLength((key = currV.getKey())); l < maxKeyLength; l++)
        str.append(' ');
      str.append(key).append('-');
      for (int l = Misc.getLength((key = nextV.getKey()) - 1); l < maxKeyLength; l++)
        str.append(' ');
      str.append(key).append(' ');
      
      for (int l = Misc.getLength((val = currV.getValue()[0]).intValue() - 1); l < maxValLength; l++)
        str.append(' ');
      str.append(val.intValue())
          .append(' ');
      
      for (int l = Misc.getLength((val = currV.getValue()[1]).intValue() - 1); l < maxPercentLength; l++)
        str.append(' ');
      str.append(PlanUtils.format.format(val))
          .append("%").append(SanityManager.lineSeparator);
      currV = nextV;
    }

    return str;
  }

  public static final String SNAPPY_HIVE_METASTORE =
      SystemProperties.SNAPPY_HIVE_METASTORE;

  public static boolean isSnappyHiveMetaTable(String schemaName) {
    return SNAPPY_HIVE_METASTORE.equalsIgnoreCase(schemaName);
  }

  public static boolean routeQuery(LanguageConnectionContext lcc) {
    return Misc.getMemStore().isSnappyStore() && lcc.isQueryRoutingFlagTrue() &&
        // if isolation level is not NONE, autocommit should be true to enable query routing
        (lcc.getCurrentIsolationLevel() == ExecutionContext.UNSPECIFIED_ISOLATION_LEVEL ||
            lcc.getAutoCommit());
  }

  public static void invalidSnappyDataFeature(String featureDescription)
      throws SQLException {
    if(getMemStore().isSnappyStore()) {
      throw Util.generateCsSQLException(SQLState.NOT_IMPLEMENTED,
          featureDescription + " is not supported in SnappyData. " +
              "This feature is supported when product is started in rowstore mode");
    }
  }
}
