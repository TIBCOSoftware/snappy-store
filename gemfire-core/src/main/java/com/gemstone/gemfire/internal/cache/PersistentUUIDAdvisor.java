package com.gemstone.gemfire.internal.cache;
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

import java.io.IOException;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionExistsException;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;

/**
 * Persistent version of {@link VMIdAdvisor} that will persist UUIDs
 * periodically to disk so that a node can restart from the last generated UUID.
 * 
 * @author swale
 * @since gfxd 1.4
 */
public class PersistentUUIDAdvisor extends VMIdAdvisor {

  /**
   * The region where local UUIDs are periodically persisted so that UUID
   * generation can work across restarts to generate unique IDs.
   */
  public static final String UUID_PERSIST_REGION = "__UUID_PERSIST";
  static final String UUID_PERSIST_REGION_PATH = "/" + UUID_PERSIST_REGION;

  protected final String fullPath;
  protected final GemFireCacheImpl cache;

  /**
   * This value indicates the steps of increments in local UUIDs after which
   * they are recorded to disk to avoid recording on every increment. On
   * recovering from disk, we add this to the last recorded value.
   */
  final int uuidRecordInterval;
  final int shortUUIDRecordInterval;

  static final String VMID_KEY_SUFFIX = "_VMID";
  static final String MAX_VMID_KEY_SUFFIX = "_MAX_VMID";
  static final String UUID_KEY_SUFFIX = "_UUID";
  static final String SHORT_UUID_KEY_SUFFIX = "_SHORT_UUID";
  static final String UUID_RECORD_INTERVAL = "_UUID_RECORD_INTERVAL";

  private final int initId;
  private final int initIdForShortUUID;

  private final LocalRegion uuidRegion;

  protected final boolean doPersist;
  protected volatile long persistedMaxVMId = INVALID_ID;

  /**
   * region used to persist the max VM ID when
   * {@link GemFireCacheImpl#getVMIDRegionPath()} is set
   */
  protected final LocalRegion vmIdRegion;

  private volatile boolean persistingUUID;
  private volatile boolean persistingShortUUID;
  private final Object uuidLock = new Object();
  private final Object shortUUIDLock = new Object();

  protected PersistentUUIDAdvisor(InternalDistributedSystem sys,
      String fullPath, int uuidRecordInterval, LocalRegion forRegion) {
    super(sys);
    this.fullPath = fullPath;
    this.cache = GemFireCacheImpl.getExisting();
    this.uuidRecordInterval = uuidRecordInterval;
    this.shortUUIDRecordInterval = Math.max(this.uuidRecordInterval / 4, 1);
    int longId = INVALID_INT_ID;
    int intId = INVALID_SHORT_ID;
    // set the IDs to the persisted ones, if any
    this.doPersist = doPersist();
    if (this.doPersist) {
      final GemFireCacheImpl cache = this.cache;
      LocalRegion uuidRegion = (LocalRegion)cache
          .getRegion(UUID_PERSIST_REGION);
      if (uuidRegion == null || uuidRegion.isDestroyed()) {
        cache.getCancelCriterion().checkCancelInProgress(null);
        AttributesFactory<Object, Object> afact =
            new AttributesFactory<Object, Object>();
        afact.setScope(Scope.LOCAL);
        afact.setDataPolicy(DataPolicy.PERSISTENT_REPLICATE);
        afact.setConcurrencyChecksEnabled(false);
        // we don't need it to be in memory
        afact.setEvictionAttributes(EvictionAttributes
            .createLRUEntryAttributes(100, EvictionAction.OVERFLOW_TO_DISK));
        // since this region is common to all PersistentUUIDAdvisors, we use the
        // default diskstore; for GemFireXD the default diskstore is its own one
        afact.setDiskStoreName(GemFireCacheImpl.getDefaultDiskStoreName());
        try {
          uuidRegion = (LocalRegion)cache.createVMRegion(UUID_PERSIST_REGION,
              afact.create(),
              new InternalRegionArguments().setDestroyLockFlag(true)
                  .setRecreateFlag(false).setIsUsedForMetaRegion(true));
        } catch (RegionExistsException ree) {
          uuidRegion = (LocalRegion)cache.getRegion(UUID_PERSIST_REGION);
        } catch (IOException ioe) {
          throw new InternalGemFireException(
              "failed to create UUID persistence region", ioe);
        } catch (ClassNotFoundException cnfe) {
          throw new InternalGemFireException(
              "failed to create UUID persistence region", cnfe);
        }
        Assert.assertTrue(uuidRegion != null && !uuidRegion.isDestroyed(),
            "failed to create UUID persistence region");
      }
      this.uuidRegion = uuidRegion;
      String vreg = cache.getVMIDRegionPath();
      if (vreg != null) {
        if (vreg.equals(fullPath)) {
          // self as the VM ID region itself
          this.vmIdRegion = forRegion;
        }
        else {
          this.vmIdRegion = (LocalRegion)cache.getRegionByPath(vreg, false);
        }
      }
      else {
        this.vmIdRegion = null;
      }
      final LogWriterI18n logger = getLogWriter();
      final String regionPrefix = fullPath;
      final Object vmId = uuidRegion.get(regionPrefix + VMID_KEY_SUFFIX);
      if (vmId != null) {
        if (logger.fineEnabled()) {
          logger.fine(toString() + ": Recovered vmId " + vmId);
        }
        this.vmUniqueId = ((Integer)vmId).intValue();
        setMaxVMIdLocally(this.vmUniqueId);
        longId = 0;
        intId = 0;
      }

      final Object maxVMId = uuidRegion.get(regionPrefix + MAX_VMID_KEY_SUFFIX);
      if (maxVMId != null) {
        if (logger.fineEnabled()) {
          logger.fine(toString() + ": Recovered maxVMId " + maxVMId);
        }
        setMaxVMIdLocally(((Integer)maxVMId).intValue());
      }

      // [fixes #50076,#50136,#50141,#50263]
      // Since record interval was reduced to 4 for DDLRegion from default 200
      // in r43183, we need to skip 200 before starting the sequence in this
      // run.
      // We skip 200 and add a key with current interval value.
      // In subsequent runs we check the presence of the key and don't
      // override but use the one passed in the region arguments.
      // The stored key/value for the interval is not used anywhere except
      // to determine whether to skip. It might as well have been a boolean.
      int initialInterval = uuidRecordInterval;
      boolean intervalChanged = false;
      Integer storedInterval = (Integer)uuidRegion.get(regionPrefix
          + UUID_RECORD_INTERVAL);

      if (storedInterval == null
          || storedInterval.intValue() != uuidRecordInterval) {
        initialInterval = storedInterval == null
            ? InternalRegionArguments.DEFAULT_UUID_RECORD_INTERVAL
            : storedInterval.intValue();
        uuidRegion.put(regionPrefix + UUID_RECORD_INTERVAL, uuidRecordInterval);
        intervalChanged = true;
      }

      final Object localUUID = uuidRegion.get(regionPrefix + UUID_KEY_SUFFIX);
      if (localUUID != null) {
        longId = ((Integer)localUUID).intValue() + initialInterval - 1;
        final long current = (longId & UINT_MASK);
        if (current > INVALID_UUID) {
          longId = INVALID_INT_ID;
        }

        if (intervalChanged && longId != INVALID_ID) {
          uuidRegion.put(regionPrefix + UUID_KEY_SUFFIX, longId);
        }

        if (logger.fineEnabled()) {
          logger.fine(toString() + ": Recovered local UUID " + localUUID
              + " longId " + longId);
        }
      }

      final Object localShortUUID = uuidRegion.get(regionPrefix
          + SHORT_UUID_KEY_SUFFIX);
      if (localShortUUID != null) {
        intId = ((Integer)localShortUUID).intValue() + initialInterval - 1;
        if (intId > INVALID_SHORT_ID) {
          intId = INVALID_SHORT_ID;
        }
        if (intervalChanged && intId != INVALID_SHORT_ID) {
          uuidRegion.put(regionPrefix + SHORT_UUID_KEY_SUFFIX, intId);
        }
        if (logger.fineEnabled()) {
          logger.fine(toString() + ": Recovered local short UUID "
              + localShortUUID + " intId " + intId);
        }
      }
      this.sequenceId.set(longId);
      this.sequenceIdForShortUUID.set(intId);
    }
    else {
      this.uuidRegion = null;
      this.vmIdRegion = null;
    }
    if (longId != INVALID_INT_ID) {
      this.initId = longId;
    }
    else {
      this.initId = 0;
    }
    if (intId != INVALID_SHORT_ID) {
      this.initIdForShortUUID = intId;
    }
    else {
      this.initIdForShortUUID = 0;
    }
  }

  public static PersistentUUIDAdvisor createPersistentUUIDAdvisor(
      InternalDistributedSystem sys, String fullPath, int uuidRecordInterval,
      LocalRegion forRegion) {
    PersistentUUIDAdvisor advisor = new PersistentUUIDAdvisor(sys, fullPath,
        uuidRecordInterval, forRegion);
    advisor.initialize();
    return advisor;
  }

  protected void invokeInitialize() {
    initialize();
  }

  protected void postInitialize() {
    if (this.vmIdRegion != null) {
      final Object maxVMId = this.vmIdRegion.get(getFullPath()
          + MAX_VMID_KEY_SUFFIX);
      if (maxVMId != null) {
        final LogWriterI18n logger = getLogWriter();
        if (logger.fineEnabled()) {
          logger.fine(toString() + ": Read maxVMId " + maxVMId + " from "
              + this.vmIdRegion.getFullPath());
        }
        final int id = ((Integer)maxVMId).intValue();
        if (id > this.maxVMUniqueId.get()) {
          setMaxVMIdLocally(id);
        }
      }
    }
  }

  public final String getFullPath() {
    return this.fullPath;
  }

  public final GemFireCacheImpl getCache() {
    return this.cache;
  }

  protected final LocalRegion getUUIDPersistentRegion() {
    if (this.uuidRegion != null) {
      return this.uuidRegion;
    }
    getCache().getCancelCriterion().checkCancelInProgress(null);
    throw new InternalGemFireError("no UUID persistence region");
  }

  @Override
  protected final boolean compareAndSetSequenceId(final int expect,
      final int update, final boolean reset) {
    if (this.uuidRegion == null) {
      return super.compareAndSetSequenceId(expect, update, reset);
    }
    // always persist for the reset case
    boolean doRecord = reset;
    if (!doRecord) {
      final long newId = ((update - this.initId) & UINT_MASK);
      doRecord = (newId % uuidRecordInterval) == 1;
    }
    if (!doRecord) {
      // if UUID is being persisted then wait for it to be done first
      while (this.persistingUUID) {
        synchronized (this.uuidLock) {
          if (!this.persistingUUID) {
            break;
          }
          boolean interrupted = Thread.interrupted();
          Throwable t = null;
          try {
            this.uuidLock.wait(100);
          } catch (InterruptedException ie) {
            t = ie;
            interrupted = true;
          } finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
              getCache().getCancelCriterion().checkCancelInProgress(t);
            }
          }
        }
      }
      return super.compareAndSetSequenceId(expect, update, reset);
    }
    else {
      this.persistingUUID = true;
      synchronized (this.uuidLock) {
        if (this.persistingUUID) {
          if (super.compareAndSetSequenceId(expect, update, reset)) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine(
                  toString() + ": Persisting generated local UUID=" + update);
            }
            getUUIDPersistentRegion().put(getFullPath() + UUID_KEY_SUFFIX,
                update);
            this.persistingUUID = false;
            this.uuidLock.notifyAll();
            return true;
          }
          else {
            this.persistingUUID = false;
          }
        }
      }
    }
    return false;
  }

  @Override
  protected final boolean compareAndSetSequenceIdForShortUUID(final int expect,
      final int update, final boolean reset) {
    if (this.uuidRegion == null) {
      return super.compareAndSetSequenceIdForShortUUID(expect, update, reset);
    }
    // always persist for the reset case
    boolean doRecord = reset;
    if (!doRecord) {
      final int newId = (update - this.initIdForShortUUID);
      doRecord = (newId % shortUUIDRecordInterval) == 1;
    }
    if (!doRecord) {
      // if UUID is being persisted then wait for it to be done first
      while (this.persistingShortUUID) {
        synchronized (this.shortUUIDLock) {
          if (!this.persistingShortUUID) {
            break;
          }
          boolean interrupted = Thread.interrupted();
          Throwable t = null;
          try {
            this.shortUUIDLock.wait(100);
          } catch (InterruptedException ie) {
            t = ie;
            interrupted = true;
          } finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
              getCache().getCancelCriterion().checkCancelInProgress(t);
            }
          }
        }
      }
      return super.compareAndSetSequenceIdForShortUUID(expect, update, reset);
    }
    else {
      this.persistingShortUUID = true;
      synchronized (this.shortUUIDLock) {
        if (this.persistingShortUUID) {
          if (super.compareAndSetSequenceIdForShortUUID(expect, update, reset)) {
            if (getLogWriter().fineEnabled()) {
              getLogWriter().fine(
                  toString() + ": Persisting generated local short UUID="
                      + update);
            }
            getUUIDPersistentRegion().put(
                getFullPath() + SHORT_UUID_KEY_SUFFIX, update);
            this.persistingShortUUID = false;
            this.shortUUIDLock.notifyAll();
            return true;
          }
          else {
            this.persistingShortUUID = false;
          }
        }
      }
    }
    return false;
  }

  @Override
  protected final void setNewVMId(final long newVMId, final long setMaxVMId) {
    super.setNewVMId(newVMId, setMaxVMId);
    // record the new VM ID and max VM ID
    if (getLogWriter().fineEnabled()) {
      getLogWriter().fine(
          toString() + ": Setting new VMId " + newVMId + "(maxVMId "
              + setMaxVMId + ')' + (this.doPersist ? " on disk" : ""));
    }
    if (this.doPersist) {
      getUUIDPersistentRegion().put(getFullPath() + VMID_KEY_SUFFIX,
          Integer.valueOf((int)newVMId));
      persistMaxVMID(setMaxVMId);
    }
  }

  @Override
  protected void setMaxVMId(final long maxVMId) {
    super.setMaxVMId(maxVMId);
    // record the new max VM ID if VMId has been initialized
    if (getLogWriter().fineEnabled()) {
      getLogWriter().fine(
          toString() + ": Setting new maxVMId " + maxVMId
              + (this.doPersist ? " on disk" : ""));
    }
    // persist max VMId even if VMId has not been initialized since
    // that may never get initialized; still accessors, for example,
    // will need the last max VMId to generate VMIds correctly
    if (this.doPersist) {
      persistMaxVMID(maxVMId);
    }
  }

  protected void setMaxVMIdLocally(long maxVMId) {
    super.setMaxVMId(maxVMId);
    this.persistedMaxVMId = maxVMId;
  }

  protected void persistMaxVMID(long maxVMId) {
    if (maxVMId > this.persistedMaxVMId) {
      final Integer vmId = Integer.valueOf((int)maxVMId);
      getUUIDPersistentRegion().put(getFullPath() + MAX_VMID_KEY_SUFFIX, vmId);
      if (this.vmIdRegion != null) {
        this.vmIdRegion.put(getFullPath() + MAX_VMID_KEY_SUFFIX, vmId);
      }
      this.persistedMaxVMId = maxVMId;
    }
  }

  protected boolean doPersist() {
    return true;
  }

  /**
   * Name of the distributed object that is locked using DistributedLockService
   * when trying to grab a new VM ID for region.
   */
  @Override
  protected String getDLockName() {
    return getFullPath() + "_NEWID";
  }

  @Override
  protected CancelCriterion getCancelCriterion(
      final InternalDistributedSystem sys) {
    return this.cache.getCancelCriterion();
  }

  /** Instantiate new distribution profile for this UUID generator. */
  @Override
  protected PersistentUUIDProfile instantiateProfile(
      final InternalDistributedMember memberId, final int version) {
    return new PersistentUUIDProfile(memberId, version, fullPath,
        this.vmUniqueId, this.maxVMUniqueId.get());
  }

  public void close(final RegionEventImpl ev) {
    super.close();
    // reset persisted IDs if region is being destroyed
    Operation op;
    if (this.doPersist && ev != null && (op = ev.getOperation()) != null
        && (op == Operation.REGION_DESTROY
            || op == Operation.REGION_LOCAL_DESTROY)) {
      final LocalRegion persistRegion = getUUIDPersistentRegion();
      if (!persistRegion.isDestroyed()) {
        final String[] allKeys = new String[] { UUID_KEY_SUFFIX,
            SHORT_UUID_KEY_SUFFIX, MAX_VMID_KEY_SUFFIX, VMID_KEY_SUFFIX };
        String key;
        for (int index = 0; index < allKeys.length; index++) {
          key = getFullPath() + allKeys[index];
          try {
            if (persistRegion.containsKey(key)) {
              persistRegion.destroy(key);
            }
          } catch (Exception e) {
            // ignore exceptions here
            getLogWriter().convertToLogWriter().warning(
                "unexpected exception in destroy of key " + key + " for "
                    + persistRegion.getFullPath(), e);
          }
        }
      }
      if (this.vmIdRegion != null && !this.vmIdRegion.isDestroyed()) {
        String key = getFullPath() + MAX_VMID_KEY_SUFFIX;
        try {
          if (this.vmIdRegion.containsKey(key)) {
            this.vmIdRegion.destroy(key);
          }
        } catch (Exception e) {
          // ignore exceptions here; can happen because of multiple nodes
          // trying to do this
          getLogWriter().finer(
              "exception in destroy of key " + key + " for "
                  + this.vmIdRegion.getFullPath(), e);
        }
      }
    }
  }

  @Override
  public LogWriterI18n getLogWriter() {
    return cache.getLoggerI18n();
  }

  @Override
  public String toString() {
    return "PersistentUUIDAdvisor for " + fullPath;
  }
}
