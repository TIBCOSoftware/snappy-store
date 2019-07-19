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
package com.pivotal.gemfirexd.internal.impl.store.raw.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.cache.RegionExistsException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegion.RegionValue;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdDDLRegionQueue;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdDistributionAdvisor;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.store.access.FileResource;
import com.pivotal.gemfirexd.internal.io.StorageFile;

/**
 * 
 * @author kneeraj
 * 
 * 
 */
public final class GfxdJarResource implements FileResource {

  private final GfxdDDLRegionQueue ddlRegionQ;

  private final LinkedHashMap<String, Long> sqlNameToIdMap;

  public GfxdJarResource(boolean persistDD, GemFireCacheImpl cache)
      throws TimeoutException, RegionExistsException, IOException,
      ClassNotFoundException, StandardException {
    this.ddlRegionQ = Misc.getMemStoreBooting().getDDLStmtQueue();
    this.sqlNameToIdMap = new LinkedHashMap<String, Long>();
  }

  public final Map<String, Long> getNameToIDMap() {
    return Collections.unmodifiableMap(this.sqlNameToIdMap);
  }

  private byte[] getByteArrayFromInputStream(String name, InputStream source)
      throws StandardException {
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: getting byte array from input stream for " + name);
    }
    if (source != null) {
      byte[] data = new byte[4096];
      int len;
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      try {
        while ((len = source.read(data)) != -1) {
          bos.write(data, 0, len);
        }
        byte[] barr = bos.toByteArray();
        if (GemFireXDUtils.TraceApplicationJars) {
          SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
              "GfxdJarResource: getting byte array for " + name + " returning "
                  + Arrays.toString(barr));
        }
        return bos.toByteArray();
      } catch (IOException ioe) {
        throw StandardException.newException(
            SQLState.FILE_UNEXPECTED_EXCEPTION, ioe);
      }
    }
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: getting byte array for " + name + " returning "
              + null);
    }
    return null;
  }

  @Override
  public long add(String name, InputStream source, LanguageConnectionContext lcc)
      throws StandardException {
    if (this.sqlNameToIdMap.containsKey(name)) {
      throw StandardException.newException(SQLState.FILE_EXISTS, name);
    }
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: add called for " + name);
    }
    byte[] bytes = getByteArrayFromInputStream(name, source);
    GfxdJarMessage sjm = new GfxdJarMessage(name, GfxdJarMessage.JAR_INSTALL,
        bytes, lcc);
    long id = -1;
    try {
      id = GemFireXDUtils.newUUIDForDD();
      sjm.setReplayKey(id);
      sjm.setCurrId(id);
      this.ddlRegionQ.put(id, sjm);
      sjm.sendByteArray(false);
      Set<DistributedMember> members = GfxdMessage.getOtherServers();

      if(Misc.getMemStore().isSnappyStore()){
        members.addAll(getLeadMembers());
      }
      
      if (GemFireXDUtils.TraceApplicationJars) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
            "GfxdJarResource: sending GfxdJarMessage to add to " + members
                + " with id " + id);
      }
      if (members.size() > 0) {
        sjm.send(Misc.getDistributedSystem(), members);
      }
    } catch (Exception e) {
      throw StandardException.newException(SQLState.SQLJ_INVALID_JAR, name);
    } finally {
      sjm.sendByteArray(true);
    }

    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: putting in the map name:value " + name + ":" + id);
    }
    this.sqlNameToIdMap.put(name, id);
    return id;
  }

  public Set<DistributedMember> getLeadMembers() {
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
    return Collections.emptySet();
   }

  @Override
  public void remove(String name, long currentGenerationId,
      LanguageConnectionContext lcc) throws StandardException {
    long id = this.sqlNameToIdMap.get(name);
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: removing jar " + name + " with id " + id);
    }
    if (id != currentGenerationId) {
      Assert.fail("id not same as stored, id=" + id + ", currentGenerationId="
          + currentGenerationId);
    }
    GfxdJarMessage sjm = new GfxdJarMessage(name, GfxdJarMessage.JAR_REMOVE,
        null, lcc);
    try {
      id = GemFireXDUtils.newUUIDForDD();
      sjm.setReplayKey(id);
      sjm.setCurrId(currentGenerationId);
      this.ddlRegionQ.put(id, sjm);
      Set<DistributedMember> members = GfxdMessage.getOtherServers();
      if (GemFireXDUtils.TraceApplicationJars) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
            "GfxdJarResource: sending GfxdJarMessage to remove to " + members
                + " with id " + id);
      }
      if (members.size() > 0) {
        sjm.send(Misc.getDistributedSystem(), members);
      }
    } catch (InterruptedException ie) {
      Misc.getDistributedSystem().getCancelCriterion()
          .checkCancelInProgress(ie);
      Thread.currentThread().interrupt();
    } catch (SQLException sqle) {
      // can this happen?
      throw Misc.wrapSQLException(sqle, sqle);
    }
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: removing from the map name " + name);
    }
    this.sqlNameToIdMap.remove(name);
    // clear the GemFire layer class cache else it may have reference to old
    // classes being removed
    InternalDataSerializer.flushClassCache();
  }

  @Override
  public long replace(String name, long currentGenerationId,
      InputStream source, LanguageConnectionContext lcc)
      throws StandardException {
    long id = this.sqlNameToIdMap.get(name);
    if (id != currentGenerationId) {
      Assert.fail("id not same as stored, id=" + id + ", currentGenerationId="
          + currentGenerationId);
    }
    // TODO generate long id by using region specific for jars?
    byte[] bytes = getByteArrayFromInputStream(name, source);
    GfxdJarMessage sjm = new GfxdJarMessage(name, GfxdJarMessage.JAR_REPLACE,
        bytes, lcc);
    try {
      id = GemFireXDUtils.newUUIDForDD();
      sjm.setReplayKey(id);
      sjm.setOldId(currentGenerationId);
      sjm.setCurrId(id);
      this.ddlRegionQ.put(id, sjm);
      sjm.sendByteArray(false);
      Set<DistributedMember> members = GfxdMessage.getOtherServers();
      if (GemFireXDUtils.TraceApplicationJars) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
            "GfxdJarResource: sending replacing jar for " + name + " with id "
                + id + " and old id " + currentGenerationId + " to members "
                + members);
      }
      if (members.size() > 0) {
        sjm.send(Misc.getDistributedSystem(), members);
      }
    } catch (InterruptedException ie) {
      Misc.getDistributedSystem().getCancelCriterion()
          .checkCancelInProgress(ie);
      Thread.currentThread().interrupt();
    } catch (SQLException sqle) {
      // can this happen?
      throw Misc.wrapSQLException(sqle, sqle);
    } finally {
      sjm.sendByteArray(true);
    }
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: replacing from the map for name " + name
              + " with id " + id + " old id " + currentGenerationId);
    }
    this.sqlNameToIdMap.put(name, id);
    // clear the GemFire layer class cache else it may have reference to old
    // classes being removed
    InternalDataSerializer.flushClassCache();
    return id;
  }

  @Override
  public StorageFile getAsFile(String name, long generationId) {
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource#getAsFile called for name " + name + " with id "
              + generationId);
    }
    assert this.sqlNameToIdMap.containsKey(name);
    long id = this.sqlNameToIdMap.get(name);
    if (id != generationId) {
      Assert.fail("id not same as stored, id=" + id + ", currentGenerationId="
          + generationId);
    }
    RegionValue value = (RegionValue)this.ddlRegionQ.getRegion().get(id);
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource#getAsFile called for name " + name + " with id "
              + generationId + " value returned is " + value);
    }
    if (value != null) {
      return (StorageFile)value.getValue();
    }
    return null;
  }

  @Override
  public char getSeparatorChar() {
    throw new UnsupportedOperationException(
        "GfxdJarResource.getSeparatorChar() should not be called");
  }

  @Override
  public long add(String name, byte[] source, long id) throws StandardException {
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: adding entry for " + name + " on remote node");
    }
    if (this.sqlNameToIdMap.containsKey(name)) {
      throw StandardException.newException(SQLState.FILE_EXISTS, name);
    }
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: putting in map name:value " + name + ":" + id);
    }
    this.sqlNameToIdMap.put(name, id);
    return id;
  }

  @Override
  public void remove(String name, long currentGenerationId, boolean remote)
      throws StandardException {

    Long id = this.sqlNameToIdMap.get(name);
    if (id == null) {
      int dotIndex = name.indexOf('.');
      String schema = "";
      if (dotIndex > 0) {
        schema = name.substring(0, dotIndex);
        name = name.substring(dotIndex + 1);
      }
      throw StandardException.newException(SQLState.LANG_FILE_DOES_NOT_EXIST,
          name, schema);
    }
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: removing entry for " + name + " with id " + id);
    }
    if (id != currentGenerationId) {
      Assert.fail("id not same as stored, id=" + id + ", currentGenerationId="
          + currentGenerationId);
    }
    this.sqlNameToIdMap.remove(name);
    // clear the GemFire layer class cache else it may have reference to old
    // classes being removed
    InternalDataSerializer.flushClassCache();
  }

  @Override
  public long replace(String name, long currentGenerationId,
      long newGenerationId, byte[] source) throws StandardException {
    if (GemFireXDUtils.TraceApplicationJars) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_APP_JARS,
          "GfxdJarResource: replacing entry for " + name + " with id "
              + newGenerationId + " old is " + currentGenerationId);
    }
    long id = this.sqlNameToIdMap.get(name);
    if (id != currentGenerationId) {
      Assert.fail("id not same as stored, id=" + id + ", currentGenerationId="
          + currentGenerationId);
    }
    this.sqlNameToIdMap.put(name, newGenerationId);
    // clear the GemFire layer class cache else it may have reference to old
    // classes being removed
    InternalDataSerializer.flushClassCache();
    return 0;
  }
}
