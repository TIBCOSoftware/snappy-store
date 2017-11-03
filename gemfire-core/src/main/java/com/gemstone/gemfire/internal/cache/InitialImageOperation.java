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

package com.gemstone.gemfire.internal.cache;

import static com.gemstone.gemfire.internal.offheap.annotations.OffHeapIdentifier.ABSTRACT_REGION_ENTRY_FILL_IN_VALUE;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.InternalGemFireException;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.TransactionDataNodeHasDepartedException;
import com.gemstone.gemfire.cache.hdfs.internal.AbstractBucketRegionQueue;
import com.gemstone.gemfire.cache.query.internal.CqQueryImpl;
import com.gemstone.gemfire.cache.query.internal.CqService;
import com.gemstone.gemfire.cache.query.internal.CqStateImpl;
import com.gemstone.gemfire.cache.query.internal.DefaultQueryService;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.LogWriterImpl;
import com.gemstone.gemfire.internal.NullDataOutputStream;
import com.gemstone.gemfire.internal.cache.InitialImageFlowControl.FlowControlPermitMessage;
import com.gemstone.gemfire.internal.cache.ha.HAContainerWrapper;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.gemstone.gemfire.internal.cache.persistence.PersistenceAdvisor;
import com.gemstone.gemfire.internal.cache.tier.InterestType;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientProxy;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.versions.DiskRegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.vmotion.VMotionObserverHolder;
import com.gemstone.gemfire.internal.cache.wan.serial.SerialGatewaySenderImpl;
import com.gemstone.gemfire.internal.concurrent.AI;
import com.gemstone.gemfire.internal.concurrent.CFactory;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.sequencelog.EntryLogger;
import com.gemstone.gemfire.internal.sequencelog.RegionLogger;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.concurrent.StoppableCountDownLatch;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TObjectIntProcedure;

/**
 * Handles requests for an initial image from a cache peer
 * 
 * @author Eric Zoerner
 */
public class InitialImageOperation  {
  /**
   * internal flag used by unit tests to test early disconnect from distributed system
   */
  public static volatile boolean abortTest = false;
  
  /**
   * maximum number of bytes to put in a single message
   */
  public static int CHUNK_SIZE_IN_BYTES =
    Integer.getInteger("GetInitialImage.chunkSize", 500 * 1024).intValue();
  
  /**
   * Allowed number of in flight GII chunks
   */
  public static int CHUNK_PERMITS =
    Integer.getInteger("gemfire.GetInitialImage.CHUNK_PERMITS", 16).intValue();

  /**
   * maximum number of unfinished operations to be supported by delta GII
   */
  public static int MAXIMUM_UNFINISHED_OPERATIONS =
    Integer.getInteger("gemfire.GetInitialImage.MAXIMUM_UNFINISHED_OPERATIONS", 10000).intValue();

  /**
   * Allowed number GIIs in parallel
   */
  public static int MAX_PARALLEL_GIIS =
    Integer.getInteger("gemfire.GetInitialImage.MAX_PARALLEL_GIIS", 5).intValue();
  
  /**
   * the region we are fetching
   */
  protected final DistributedRegion region;
  
  /**
   * the underlying Map to receive our values
   */
  private final RegionMap entries;

  /**
   * true if we have received the last piece of the image
   */
  protected volatile boolean gotImage = false;
  
  /** 
   * received region version holder for lost member, used by synchronizeWith only 
   */
  protected RegionVersionHolder rcvd_holderToSync;
  
  /**
   * true if this is delta gii
   */
  protected volatile boolean isDeltaGII = false;

  /**
   * for testing purposes
   */
  public static volatile int slowImageProcessing = 0;
  
  /**
   * for testing purposes
   */
  public static volatile int slowImageSleeps = 0;

  /**
   * for testing purposes
   */
  public static boolean VMOTION_DURING_GII = false;

  private boolean isSynchronizing;
  
  /** Creates a new instance of InitalImageOperation */
  InitialImageOperation(DistributedRegion region, RegionMap entries) {
    this.region = region;
    this.entries = entries;
  }

  /** a flag for inhibiting the use of StateFlushOperation before gii */
  private final static ThreadLocal inhibitStateFlush = new ThreadLocal(){
    @Override
      protected Object initialValue() {
        return Boolean.valueOf(false);
      }
    };
  
  
  /** inhibit use of StateFlush for the current thread */
  public static void setInhibitStateFlush(boolean inhibitIt) {
    inhibitStateFlush.set(Boolean.valueOf(inhibitIt));
  }

  public enum GIIStatus {
    NO_GII,
    GOTIMAGE_BY_FULLGII,
    GOTIMAGE_BY_DELTAGII; 

    public final boolean didGII() {
      return this != NO_GII;
    }

    public static boolean didDeltaGII(GIIStatus giiStatus) {
      return giiStatus == GIIStatus.GOTIMAGE_BY_DELTAGII;
    }

    public static boolean didFullGII(GIIStatus giiStatus) {
      return giiStatus == GIIStatus.GOTIMAGE_BY_FULLGII;
    }
  }

  private GIIStatus reportGIIStatus() {
    if (!this.gotImage) {
      return GIIStatus.NO_GII;
    } else {
      // got image
      if (this.isDeltaGII) {
        return GIIStatus.GOTIMAGE_BY_DELTAGII;
      } else {
        return GIIStatus.GOTIMAGE_BY_FULLGII;
      }
    }
  }

  private boolean hasLockedBucket = false;

  private void unlockTargetBucketLock(InternalDistributedMember recipient) {
    if (hasLockedBucket) {
      LogWriterI18n logger = this.region.getDistributionManager().getLoggerI18n();
      logger.info(LocalizedStrings.DEBUG, "Processing SnapshotBucketLockReleaseProcessor to " + recipient);
      try {
        SnapshotBucketLockReleaseMessage.send(recipient,
            this.region.getDistributionManager(), region.getFullPath());
        logger.info(LocalizedStrings.DEBUG, "Processing SnapshotBucketLockReleaseProcessor done " + recipient);
      } catch (Throwable t) {
        logger.warning(LocalizedStrings.DEBUG, t);
      }
    }
  }

  /**
   * Fetch an initial image from a single recipient
   * 
   * @param recipientSet list of candidates to fetch from
   * @param targetReinitialized true if candidate should wait until initialized
   *        before responding
   * @param advice
   * @param recoveredRVV recovered rvv 
   * @return true if succeeded to get image
   * @throws com.gemstone.gemfire.cache.TimeoutException
   */
  GIIStatus getFromOne(
      Set recipientSet,
      boolean targetReinitialized,
      CacheDistributionAdvisor.InitialImageAdvice advice,
      boolean recoveredFromDisk, RegionVersionVector recoveredRVV)
      throws com.gemstone.gemfire.cache.TimeoutException
  {
    if (VMOTION_DURING_GII) {
      /**
       * TODO (ashetkar): recipientSet may contain more than one member. Ensure
       * only the gii-source member is vMotioned. The test hook may need to be
       * placed at another point.
       */
      VMotionObserverHolder.getInstance().vMotionDuringGII(recipientSet, this.region);
    }
    // Make sure that candidates are regarded in random order
    ArrayList recipients = new ArrayList(recipientSet);

    if (this.region.isUsedForSerialGatewaySenderQueue()) {
      SerialGatewaySenderImpl sender = this.region.getSerialGatewaySender();
      if (sender != null) {
        InternalDistributedMember primary = sender.getSenderAdvisor()
            .advisePrimaryGatewaySender();
        if (primary != null) {
          recipients.remove(primary);
          recipients.add(0, primary);
        }
      }
    } else {
      if (recipients.size() > 1) {
        Collections.shuffle(recipients);
      }
    }
    long giiStart = this.region.getCachePerfStats().startGetInitialImage();

    InternalDistributedMember prevRecipient = null;

    this.region.getLogWriterI18n().info(LocalizedStrings.DEBUG,
        "InitialImageOperation starts for " + this.region.getFullPath() + ", Recipients are " + recipients);
    for (Iterator itr = recipients.iterator(); !this.gotImage && itr.hasNext();) {
      // if we got a partial image from the previous recipient, then clear it

      if(prevRecipient != null ){
        unlockTargetBucketLock(prevRecipient);
      }

      InternalDistributedMember recipient = (InternalDistributedMember)itr.next();
      prevRecipient = recipient;
//      log.info("InitialImageOperation for " + this.region.getFullPath() + " targeting " + recipient);
      
      // In case of HARegion, before getting the region snapshot(image) get the filters 
      // registered by the associated client and apply them.
      // As part of bug fix 39014, while creating the secondary HARegion/Queue, the 
      // filters registered by the client is applied first and then the HARegion 
      // initial image is applied. This is to process any events thats arriving while
      // GII is happening and is not part of the GII result.
      if (region instanceof HARegion){
        try {
//          HARegion r = (HARegion)region;
//          if (!r.isPrimaryQueue()) {
            if (!this.requestFilterInfo(recipient)) {
              if (this.region.getLogWriterI18n().infoEnabled()) {
                // TODO add localizedString
                this.region.getLogWriterI18n().info(LocalizedStrings.DEBUG, 
                    "Failed to receive interest and CQ information from " + recipient);
              }
            }
//          }
        } catch (Exception ex){
          if (this.region.getCache().getLoggerI18n().infoEnabled() && !itr.hasNext()) {
            // TODO add localizedString
            this.region.getCache().getLoggerI18n().info(LocalizedStrings.DEBUG, 
                "Failed while getting interest and CQ information from " + recipient);
            // To report exception.
            if (this.region.getCache().getLoggerI18n().fineEnabled()) {
              this.region.getCache().getLoggerI18n().fine("Failed while getting interest and CQ information from " + 
                  recipient, ex);
            }
          }
          continue;
        }
      }
      
      PersistenceAdvisor persistenceAdvisor = this.region.getPersistenceAdvisor();
      if(persistenceAdvisor != null) {
        try {
          persistenceAdvisor.updateMembershipView(recipient, targetReinitialized);
          persistenceAdvisor.setInitializing(this.region.getPersistentID());
        } catch(ReplyException e) {
          this.region.getCache().getLoggerI18n().fine("Failed to get membership view", e);
          continue;
        }
      }
      final DistributionManager dm = (DistributionManager)this.region.getDistributionManager();

      boolean allowDeltaGII = true;
      if (FORCE_FULL_GII) {
        allowDeltaGII = false;
      }
      Set keysOfUnfinishedOps = null;
      RegionVersionVector received_rvv = null;
      RegionVersionVector remote_rvv = null;
      if (this.region.concurrencyChecksEnabled) {
        if (internalBeforeRequestRVV != null && internalBeforeRequestRVV.getRegionName().equals(this.region.getName())) {
          internalBeforeRequestRVV.run();
        }
        RequestRVVProcessor rvvProcessor = getRVVDetailsFromProvider(dm, recipient, targetReinitialized);
        received_rvv = rvvProcessor.received_rvv;
        hasLockedBucket = rvvProcessor.snapshotGIIWriteLock;
        if (received_rvv == null) {
          continue;
        }

        // remote_rvv will be filled with the versions of unfinished keys
        // then if recoveredRVV is still newer than the filled remote_rvv, do fullGII
        remote_rvv = received_rvv.getCloneForTransmission();
        keysOfUnfinishedOps = processReceivedRVV(remote_rvv, recoveredRVV);
        if (internalAfterCalculatedUnfinishedOps != null && internalAfterCalculatedUnfinishedOps.getRegionName().equals(this.region.getName())) {
          internalAfterCalculatedUnfinishedOps.run();
        }
        if (keysOfUnfinishedOps == null) {
          // if got rvv, keysOfUnfinishedOps at least will be empty
          continue;
        }
      }

      Boolean inhibitFlush = (Boolean)inhibitStateFlush.get();
      if (!inhibitFlush.booleanValue() && !this.region.doesNotDistribute()) {
        if (region instanceof AbstractBucketRegionQueue) {
          // get the corresponding userPRs and do state flush on all of them
          // TODO we should be able to do this state flush with a single
          // message, but that will require changing the messaging layer,
          // which has implications for a rolling upgrade.
          Collection<BucketRegion> userPRBuckets = ((AbstractBucketRegionQueue)(this.region))
              .getCorrespondingUserPRBuckets();
          if (this.region.getLogWriterI18n().fineEnabled()) {
            this.region.getLogWriterI18n().fine(
                "The parent buckets of this shadowPR region are "
                    + userPRBuckets);
          }

          for (BucketRegion parentBucket : userPRBuckets) {
            if (this.region.getLogWriterI18n().fineEnabled()) {
              this.region.getLogWriterI18n().fine(
                  "Going to do state flush operation on the parent bucket.");
            }
            boolean interrupted = false;
            final StateFlushOperation sf;
            sf = new StateFlushOperation(parentBucket);
            final Set<InternalDistributedMember> r = new HashSet<InternalDistributedMember>();
            r.addAll(advice.replicates);
            r.addAll(advice.preloaded);
            r.addAll(advice.others);
            r.addAll(advice.empties);
            r.addAll(advice.uninitialized);
            r.addAll(region.activeTXNodes);
            int processorType = targetReinitialized ? DistributionManager.WAITING_POOL_EXECUTOR
                : DistributionManager.HIGH_PRIORITY_EXECUTOR;
            try {
              boolean success = sf.flush(r, recipient, processorType, true);
              if (!success) {
                continue;
              }
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              region.getCancelCriterion().checkCancelInProgress(ie);
              this.region.getCachePerfStats().endNoGIIDone(giiStart);
              interrupted = true;
              return GIIStatus.NO_GII;
            } finally {
              if (interrupted) {
                unlockTargetBucketLock(prevRecipient);
              }
            }
            if(this.region.getLogWriterI18n().fineEnabled()) {
              this.region.getLogWriterI18n().fine("Completed state flush operation on the parent bucket.");
            }
          }
        }
        boolean interrupted = false;
        final StateFlushOperation sf;
        sf = new StateFlushOperation(this.region);
        final Set<InternalDistributedMember> r = new HashSet<InternalDistributedMember>();
        r.addAll(advice.replicates);
        r.addAll(advice.preloaded);
        r.addAll(advice.others);
        r.addAll(advice.empties);
        r.addAll(advice.uninitialized);
        r.addAll(region.activeTXNodes);
        int processorType = targetReinitialized ? DistributionManager.WAITING_POOL_EXECUTOR
            : DistributionManager.HIGH_PRIORITY_EXECUTOR;
        try {

          if (internalNoGII != null && internalNoGII.getRegionName().equals(region.getName())) {
            Thread.currentThread().interrupt();
          }

          boolean success = sf.flush(r, recipient, processorType, false);
          if (!success) {
            continue;
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          region.getCancelCriterion().checkCancelInProgress(ie);
          this.region.getCachePerfStats().endNoGIIDone(giiStart);
          interrupted = true;
          return GIIStatus.NO_GII;
        } finally {
          if (interrupted) {
            unlockTargetBucketLock(prevRecipient);
          }
        }
      }
      
      RequestImageMessage m = new RequestImageMessage();
      m.regionPath = this.region.getFullPath();
      m.keysOnly = false;
      m.targetReinitialized = targetReinitialized;
      m.setRecipient(recipient);

      if (this.region.concurrencyChecksEnabled) {
        if (allowDeltaGII && recoveredFromDisk) {
          if (!this.region.getDiskRegion().getRVVTrusted()) {
            this.region.getCache().getLoggerI18n().info(LocalizedStrings.DEBUG, "Region " + this.region.getFullPath() + " recovered without EndGII flag, do full GII");
            m.versionVector = null;
          } else if (keysOfUnfinishedOps.size() > MAXIMUM_UNFINISHED_OPERATIONS) {
            this.region.getCache().getLoggerI18n().info(LocalizedStrings.DEBUG, "Region " + this.region.getFullPath() + " has "+keysOfUnfinishedOps.size()
                +" unfinished operations, which exceeded threshold "+MAXIMUM_UNFINISHED_OPERATIONS+", do full GII instead");
            m.versionVector = null;
          } else {
            if (recoveredRVV.isNewerThanOrCanFillExceptionsFor(remote_rvv)) {
              m.versionVector = null;
              this.region.getCache().getLoggerI18n().info(LocalizedStrings.DEBUG, "Region " + this.region.getFullPath() 
                  + ": after filled versions of unfinished keys, recovered rvv is still newer than remote rvv:"
                  +remote_rvv+". recovered rvv is "+recoveredRVV+". Do full GII");
            } else {
              m.versionVector = recoveredRVV;
              m.unfinishedKeys = keysOfUnfinishedOps;
              this.region.getCache().getLoggerI18n().info(LocalizedStrings.DEBUG, "Region " + this.region.getFullPath() + " recovered with EndGII flag, rvv is "
              +m.versionVector+". recovered rvv is "+recoveredRVV+". Do delta GII");
            }
          }
          m.checkTombstoneVersions = true;
        }
        // pack the original RVV, then save the received one
        if (internalBeforeSavedReceivedRVV != null && internalBeforeSavedReceivedRVV.getRegionName().equals(this.region.getName())) {
          internalBeforeSavedReceivedRVV.run();
        }
        saveReceivedRVV(received_rvv);
        if (internalAfterSavedReceivedRVV != null && internalAfterSavedReceivedRVV.getRegionName().equals(this.region.getName())) {
          internalAfterSavedReceivedRVV.run();
        }
      }

      ImageProcessor processor = new ImageProcessor(this.region.getSystem(),
                                                    recipient);
      dm.acquireGIIPermitUninterruptibly();
      try {
        m.processorId = processor.getProcessorId();
        if (region.isUsedForPartitionedRegionBucket() &&
            region.getDistributionConfig().getAckSevereAlertThreshold() > 0) {
          processor.enableSevereAlertProcessing();
          m.severeAlertEnabled = true;
        }
        if (!LocalRegion.isMetaTable(this.region.getFullPath())) {
          // do not remove the following log statement
          this.region.getCache().getLoggerI18n().info(
                  LocalizedStrings.InitialImageOperation_REGION_0_REQUESTING_INITIAL_IMAGE_FROM_1,
                  new Object[]{this.region.getName(), recipient});
        }

        dm.putOutgoing(m);
        this.region.cache.getCancelCriterion().checkCancelInProgress(null);
        if (internalAfterSentRequestImage != null && internalAfterSentRequestImage.getRegionName().equals(this.region.getName())) {
          internalAfterSentRequestImage.run();
        }
        try {
          processor.waitForRepliesUninterruptibly();
          if(!this.gotImage) {
            continue;
          }
          
          // review unfinished keys and remove untouched entries
          if (this.region.getDataPolicy().withPersistence() && keysOfUnfinishedOps != null && !keysOfUnfinishedOps.isEmpty()) {
            LogWriterI18n logger = this.region.getLogWriterI18n();
            final DiskRegion dr = this.region.getDiskRegion();
            assert dr != null;
            for (Object key:keysOfUnfinishedOps) {
              RegionEntry re = this.entries.getEntry(key);
              if (re == null) {
                continue;
              }
              if (TRACE_GII) {
                logger.info(LocalizedStrings.DEBUG, "Processing unfinished operation:entry="+re);
              }
              DiskEntry de = (DiskEntry)re;
              synchronized (de) {
                DiskId id = de.getDiskId();
                if (id != null && EntryBits.isRecoveredFromDisk(id.getUserBits())) {
                  this.region.destroyRecoveredEntry(key);
                  this.region.getLogWriterI18n().info(LocalizedStrings.DEBUG, "Deleted unfinished keys:key="+key);
                }
              }
            }
          }

          int numTimesRequested = 0;
          region.writeLockEnqueueDelta();
          try {
            ImageState imgState = region.getImageState();
            while (requestUnappliedDeltas(dm, recipient)) {
              imgState.setRequestedUnappliedDelta(true);
              LogWriterI18n logger = this.region.getLogWriterI18n();
              if (TRACE_GII || logger.fineEnabled()) {
                region.getLogWriterI18n().info(LocalizedStrings.DEBUG,
                    "Requested the base value for unapplied deltas for region: " +
                        this.region + " for " + (++numTimesRequested) + " time");
              }
            }
          } finally {
            region.writeUnlockEnqueueDelta();
          }
          continue;
        } catch (InternalGemFireException ex) {
          Throwable cause = ex.getCause();
          if (cause instanceof com.gemstone.gemfire.cache.TimeoutException) {
            throw (com.gemstone.gemfire.cache.TimeoutException)cause;
          }
          throw ex;
        } catch (ReplyException e) {
          if(!region.isDestroyed()) {
            e.handleAsUnexpected();
          }
        } finally {
          ImageState imgState = region.getImageState();
          
          if (imgState.getClearRegionFlag()) {
            // Asif : Since the operation has been completed clear flag
            imgState.setClearRegionFlag(false, null);
          }
          
          if (this.gotImage) {
            RegionLogger.logGII(this.region.getFullPath(), recipient,
                region.getDistributionManager().getDistributionManagerId(), region.getPersistentID());
          }
          if (this.region.getLogWriterI18n().infoEnabled()) {
            if (this.gotImage) {
              // TODO add localizedString
              if (!LocalRegion.isMetaTable(this.region.getFullPath())) {
                this.region.getLogWriterI18n().info(LocalizedStrings.DEBUG,
                        this.region.getName() + " is done getting image from "
                        + recipient + ". isDeltaGII is " + this.isDeltaGII
                        + " rvv is " + this.region.getVersionVector());
              }
            } else {
              // TODO add localizedString
              this.region.getLogWriterI18n().info(LocalizedStrings.DEBUG, this.region.getName() + " failed to get image from " + recipient);
            }
          }
          if (this.region.dataPolicy.withPersistence()
              && this.region.getLogWriterI18n().infoEnabled()
              && !LocalRegion.isMetaTable(this.region.getFullPath())) {
            this.region.getLogWriterI18n().info(
                    LocalizedStrings.InitialImageOperation_REGION_0_INITIALIZED_PERSISTENT_REGION_WITH_ID_1_FROM_2,
                    new Object[] {this.region.getName(),
                        this.region.getPersistentID(), recipient});
          }
          // bug 39050 - no partial images after GII when network partition
          // detection is enabled
          if (!this.gotImage) {
            this.region.cleanUpAfterFailedGII(recoveredFromDisk);           
          } else if (received_rvv != null) {
            checkForUnrecordedOperations(recipient);
          }
        }
      } finally {
        dm.releaseGIIPermit();
        processor.cleanup();
      }
    } // for

    if (this.gotImage) {
      this.region.getCachePerfStats().endGetInitialImage(giiStart);
      if (this.isDeltaGII) {
        this.region.getCachePerfStats().incDeltaGIICompleted();
      }
    } else {
      this.region.getCachePerfStats().endNoGIIDone(giiStart);
    }
    return reportGIIStatus();
  }
  
  /**
   * Request the base value for any unapplied ListOfDelta objects in the region.
   * With gemfirexd, a concurrent UPDATE statement can leave a ListOfDeltas object
   * in the region on the GII recipient.
   * 
   * With delta GII, it's possible the base value might never arrive. If that
   * happens, we need to explicitly request the base value from the source.
   * @param dm 
   * @return whether request was sent or not
   */
  private boolean requestUnappliedDeltas(DistributionManager dm, InternalDistributedMember recipient) {
    ImageState imgState = region.getImageState();
    LogWriterI18n logger = region.getLogWriterI18n();
    //See if there are any deltas that have not yet been merged
    Iterator<Object> deltas = imgState.getDeltaEntries();
    Set<Object> unappliedDeltas = new HashSet<Object>();
    while(deltas.hasNext()) {
      Object key = deltas.next();
      if(region.getRegionMap().isListOfDeltas(key)) {
        //GFXD keys can be the region entry itself. We need to make a copy
        //to send to the remote side.
        if(key instanceof RegionEntry) {
          key = ((RegionEntry) key).getKeyCopy();
        }
        unappliedDeltas.add(key);
      }
    }
    
    if(unappliedDeltas.isEmpty()) {
      return false;
    }
    
    //We have deltas that have not received the base value, request
    //the base value
    
    if(TRACE_GII || logger.fineEnabled()) {
      region.getLogWriterI18n().info(LocalizedStrings.DEBUG, "Requesting the base value for unapplied deltas: " + unappliedDeltas);
    }
    
    //Clear the gotImage flag
    this.gotImage = false;
    
    //Send a request Image message that will just get the values for the
    //keys which have not been applied.
    RequestImageMessage m = new RequestImageMessage();
    m.regionPath = this.region.getFullPath();
    m.unfinishedKeysOnly = true;
    m.setRecipient(recipient);
    m.unfinishedKeys = unappliedDeltas;
    ImageProcessor processor = new ImageProcessor(this.region.getSystem(),
        recipient);
    m.processorId = processor.getProcessorId();
    
    if (region.isUsedForPartitionedRegionBucket() &&
        region.getDistributionConfig().getAckSevereAlertThreshold() > 0) {
      processor.enableSevereAlertProcessing();
      m.severeAlertEnabled = true;
    }

    dm.putOutgoing(m);
    
    processor.waitForRepliesUninterruptibly();
    return true;
  }

  /**
   * synchronize with another member (delta GII from it).  If lostMember is not
   * null, then only changes that it made to the image provider will be sent
   * back.  Otherwise all changes made to the image provider will be compared
   * with those made to this cache and a full delta will be sent.
   */
  public void synchronizeWith(InternalDistributedMember target,
      VersionSource lostMemberVersionID, InternalDistributedMember lostMember) {
    final DistributionManager dm = (DistributionManager)this.region.getDistributionManager();

    this.isSynchronizing = true;
    RequestImageMessage m = new RequestImageMessage();
    m.regionPath = this.region.getFullPath();
    m.keysOnly = false;

    if (lostMemberVersionID != null)  {
      m.versionVector = this.region.getVersionVector().getCloneForTransmission(lostMemberVersionID);
      m.lostMemberVersionID = lostMemberVersionID;
      m.lostMemberID = lostMember;
    } else {
      m.versionVector = this.region.getVersionVector().getCloneForTransmission();
    }
    m.setRecipient(target);
    ImageProcessor processor = new ImageProcessor(this.region.getSystem(),
                                                  target);
    dm.acquireGIIPermitUninterruptibly();
    try {
      m.processorId = processor.getProcessorId();
      if (region.isUsedForPartitionedRegionBucket() &&
          region.getDistributionConfig().getAckSevereAlertThreshold() > 0) {
        processor.enableSevereAlertProcessing();
        m.severeAlertEnabled = true;
      }
    
      LogWriterI18n log = this.region.getLogWriterI18n();
      if (log.infoEnabled()) {
        log.info(LocalizedStrings.DEBUG, "Region " + this.region.getName() + " is requesting synchronization with " + target + " for " + lostMember);
      }

      long hisVersion = this.region.getVersionVector().getVersionForMember(lostMemberVersionID);

      dm.putOutgoing(m);
      this.region.cache.getCancelCriterion().checkCancelInProgress(null);
      try {
        processor.waitForRepliesUninterruptibly();
        ImageState imgState = region.getImageState();
        if (imgState.getClearRegionFlag()) {
          imgState.setClearRegionFlag(false, null);
        }
      } catch (InternalGemFireException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof com.gemstone.gemfire.cache.TimeoutException) {
          throw (com.gemstone.gemfire.cache.TimeoutException)cause;
        }
        throw ex;
      } catch (ReplyException e) {
        if(!region.isDestroyed()) {
          e.handleAsUnexpected();
        }
      } finally {
        if (this.gotImage) {
          this.region.getVersionVector().removeExceptionsFor(target, hisVersion);
          RegionVersionHolder holder = this.region.getVersionVector().getHolderForMember(lostMemberVersionID);
          if (this.rcvd_holderToSync != null && this.rcvd_holderToSync.isNewerThanOrCanFillExceptionsFor(holder)) {
            region.getLogWriterI18n().info(LocalizedStrings.DEBUG, "synchronizeWith detected mismatch region version holder for lost member "
                +lostMemberVersionID + ". Old is "+holder+", new is "+this.rcvd_holderToSync);
            this.region.getVersionVector().initializeVersionHolder(lostMemberVersionID, this.rcvd_holderToSync);
          }
          RegionLogger.logGII(this.region.getFullPath(), target,
              region.getDistributionManager().getDistributionManagerId(), region.getPersistentID());
        }
        if (this.region.getLogWriterI18n().fineEnabled()) {
          if (this.gotImage) {
            this.region.getLogWriterI18n().fine(this.region.getName() + " is done synchronizing with " + target);
          } else {
            this.region.getLogWriterI18n().fine(this.region.getName() + " received no synchronization data from " + target + " which could mean that we are already synchronized");
          }
        }
//        if (this.region.dataPolicy.withPersistence()
//            && this.region.getLogWriterI18n().infoEnabled()) {
//          this.region.getLogWriterI18n().info(
//                  LocalizedStrings.InitialImageOperation_REGION_0_INITIALIZED_PERSISTENT_REGION_WITH_ID_1_FROM_2,
//                  new Object[] {this.region.getName(),
//                      this.region.getPersistentID(), target});
//        }
      }
    } finally {
      dm.releaseGIIPermit();
      processor.cleanup();
    }
  }

  private void checkForUnrecordedOperations(InternalDistributedMember imageProvider) {
    // bug #48962 - a change could have been received from a member
    // that the image provider didn't see.  This can happen if the
    // image provider is creating the region in parallel with this member.
    // We have to check all of the received versions for members that
    // left during GII to see if the RVV contains them.
    RegionVersionVector rvv = this.region.getVersionVector();
    if (this.region.concurrencyChecksEnabled && rvv != null) {
      ImageState state = this.region.getImageState();
      LogWriterI18n log = this.region.getLogWriterI18n();
      if (state.hasLeftMembers()) {
        Set<VersionSource> needsSync = null;
        Set<VersionSource> leftMembers = state.getLeftMembers();
        Iterator<ImageState.VersionTagEntry> tags = state.getVersionTags();
        while (tags.hasNext()) {
          ImageState.VersionTagEntry tag = tags.next();
          if (log.finestEnabled()) {
            log.finest("checkForUnrecordedOperations: processing tag " + tag);
          }
          if (leftMembers.contains(tag.getMemberID())
              && !rvv.contains(tag.getMemberID(), tag.getRegionVersion())) {
            if (needsSync == null) {
              needsSync = new HashSet<VersionSource>();
            }
            needsSync.add(tag.getMemberID());
            rvv.recordVersion(tag.getMemberID(),  tag.getRegionVersion(), null);
          }
        }
        if (needsSync != null) {
          // we need to tell the image provider to request syncs on the given
          // member(s)  These will either be DistributedMember IDs or DiskStore IDs
          RequestSyncMessage msg = new RequestSyncMessage();
          msg.regionPath = this.region.getFullPath();
          msg.lostVersionSources = needsSync.toArray(new VersionSource[needsSync.size()]);
          msg.setRecipients(this.region.getCacheDistributionAdvisor().adviseReplicates());
          if (log.fineEnabled()) {
            log.fine("Local versions were found that the image provider has not seen for " + needsSync);
          }
          this.region.getDistributionManager().putOutgoing(msg);
        }
      }
    }
  }
  
  /**
   * test hook invokation
   */
  public static void beforeGetInitialImage(DistributedRegion region) {
    if (internalBeforeGetInitialImage != null && internalBeforeGetInitialImage.getRegionName().equals(region.getName())) {
      internalBeforeGetInitialImage.run();
    }

  }
  
  
  /**
   * transfer interest/cq registrations from the image provider to this VM
   * @param recipient
   * @return whether the operation succeeded in transferring anything
   */
  private boolean requestFilterInfo(InternalDistributedMember recipient){
    // Request for Filter Information before getting the
    // HARegion snapshot.
    final DM dm = this.region.getDistributionManager();
    RequestFilterInfoMessage filterInfoMsg = new RequestFilterInfoMessage();      
    filterInfoMsg.regionPath = this.region.getFullPath();
    filterInfoMsg.setRecipient(recipient);
    FilterInfoProcessor processor = new FilterInfoProcessor(this.region.getSystem(),
        recipient);    
    filterInfoMsg.processorId = processor.getProcessorId();
    dm.putOutgoing(filterInfoMsg);

    try {
      processor.waitForRepliesUninterruptibly();
      return processor.filtersReceived;
    } catch (InternalGemFireException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof com.gemstone.gemfire.cache.TimeoutException) {
        throw (com.gemstone.gemfire.cache.TimeoutException)cause;
      }
      throw ex;
    } catch (ReplyException e) {
      if(!region.isDestroyed()) {
        e.handleAsUnexpected();
      }
    }
    return false;
  }
  
  
  /** Called from separate thread when reply is processed.
   *  @param entries entries to add to the region
   *  @return false if should abort (region was destroyed or cache was closed)
   */
  boolean processChunk(List entries, ArrayList<EventID> failedEvents,
      InternalDistributedMember sender, Version remoteVersion)
      throws IOException, ClassNotFoundException {
    // one volatile read of test flag
    int slow = slowImageProcessing;
    final LogWriterI18n logger = this.region.getCache().getLoggerI18n();
    final CachePerfStats stats = this.region.getCachePerfStats();
    ImageState imgState = this.region.getImageState();
    //Asif : Can the image state be null here. Don't think so
    //Assert.assertTrue(imgState != null, "processChunk :ImageState should not have been null ");
    //Asif: Set the Htree Reference in Thread Local before the iteration begins so as
    //to detect a clear operation occurring while the put operation is in progress
    //It is Ok to set it every time the loop is executed, because a clear can happen
    //only once during GII life cycle & so it does not matter if the HTree ref changes after the clear
    //whenever a conflict is detected in DiskRegion it is Ok to abort the operation
    final DiskRegion dr = this.region.getDiskRegion();
    if (dr != null) {
      dr.setClearCountReference();
    }
    try {
      int entryCount = entries.size();
      THashSet keys = null;
      if (logger.fineEnabled() && entryCount <= 1000) {
        keys = new THashSet();
      }
      final boolean keyRequiresRegionContext = this.region
          .keyRequiresRegionContext();
      final boolean versioningEnabled = this.region
          .getConcurrencyChecksEnabled();
      final ByteArrayDataInput in = new ByteArrayDataInput();
      final HeapDataOutputStream out = new HeapDataOutputStream(
          Version.CURRENT);
      @Retained @Released(ABSTRACT_REGION_ENTRY_FILL_IN_VALUE) final Entry tmplEntry = versioningEnabled ? null : new Entry();
      for (int i = 0; i < entryCount; i++) {
        // stream is null-terminated
        if (internalDuringApplyDelta != null && !internalDuringApplyDelta.isRunning && internalDuringApplyDelta.getRegionName().equals(this.region.getName())) {
          internalDuringApplyDelta.run();
        }
        if (slow > 0) {
          // make sure we are still slow
          slow = slowImageProcessing;
          if (slow > 0) {
            boolean interrupted = Thread.interrupted();
            try {
              logger.fine("processChunk: Sleeping for " + slow + " ms for rgn "
                  + this.region.getFullPath());
              Thread.sleep(slow);
              slowImageSleeps++;
            }
            catch (InterruptedException e) {
              interrupted = true;
              region.getCancelCriterion().checkCancelInProgress(e);
            }
            finally {
              if (interrupted) {
                Thread.currentThread().interrupt();
              }
            }
          }
        }
        try {
          if (this.region.isDestroyed() || imgState.getClearRegionFlag()) {
            return false;
          }
        }
        catch (CancelException e) {
          return false;
        }

        Entry entry = (Entry)entries.get(i);

        stats.incGetInitialImageKeysReceived(1);

        final long lastModified = entry.getLastModified(this.region
            .getDistributionManager());

        Object tmpValue = entry.value;
        byte[] tmpBytes = null;
        if (!entry.isTombstone()) {
          if (entry.isSerialized()) {
            tmpBytes = (byte[]) tmpValue;
            // deserialize early for this case
            if (CachedDeserializableFactory.preferObject()) {
              tmpValue = EntryEventImpl
                  .deserialize(tmpBytes, remoteVersion, in);
              entry.setSerialized(false);
            }
          }
          // raw byte[] case
          else if (!entry.isEagerDeserialize()) {
            tmpBytes = (byte[]) tmpValue;
          }
        }
        if (keyRequiresRegionContext) {
          final KeyWithRegionContext key = (KeyWithRegionContext) entry.key;
          // deserialize value for passing to afterDeserializationWithValue
          Object keyObj = tmpValue;
          if (entry.isSerialized()) {
            tmpValue = keyObj = EntryEventImpl.deserialize(tmpBytes,
                remoteVersion, in);
            entry.setSerialized(false);
          }
          key.afterDeserializationWithValue(keyObj);
          key.setRegionContext(this.region);
        }

        boolean didIIP = false;
        boolean wasRecovered = false;

        VersionTag tag = entry.getVersionTag();

        if (dr != null) {
          // verify if entry from GII is the same as the one from recovery
          RegionEntry re = this.entries.getEntryInVM(entry.key);
          if (logger.finerEnabled() || InitialImageOperation.TRACE_GII_FINER) {
            logger.convertToLogWriter().info("processChunk:entry="+entry+",tag="+tag+",re="+re+",versioningEnabled="+versioningEnabled);
          }
          // re will be null if the gii chunk gives us a create
          if (re != null) {
            synchronized (re) { // fixes bug 41409
              if (dr.testIsRecovered(re, true)) {
                wasRecovered = true;
                if (tmpValue == null) {
                  tmpValue = entry.isLocalInvalid()
                    ? Token.LOCAL_INVALID
                    : Token.INVALID;
                }

                if (versioningEnabled) {
                  // Compare the version stamps, and if they are equal
                  // we can skip adding the entry we receive as part of GII.
                  final VersionStamp<?> stamp = re.getVersionStamp();
                  boolean entriesEqual = stamp != null
                      && stamp.asVersionTag().equals(tag);

                  // If the received entry and what we have in the cache
                  // actually are equal, don't put the received
                  // entry into the cache (this avoids writing a record to disk)
                  if (entriesEqual) {
                    if (logger.finerEnabled() || InitialImageOperation.TRACE_GII_FINER) {
                      logger.convertToLogWriter().info("processChunk:entry="+entry+",entriesEqual="+entriesEqual+", tag="+tag+"stamp="+stamp+"stamp as tag="+(stamp == null ? "null" : stamp.asVersionTag()));
                    }
                    continue;
                  }
                }
                else {
                  // if there are no versions, then compare raw bytes to avoid
                  // writing to disk if the incoming value is same as recovered
                  tmplEntry.clearForReuse((byte)0);
                  final DM dm = this.region.getDistributionManager();
                  if (re.fillInValue(this.region, tmplEntry, dm, null)) {
                    try {
                      if (tmplEntry.value != null) {
                        final byte[] valueInCache;
                        boolean areEqual = true;
                        final Class<?> vclass = tmplEntry.value.getClass();
                        if (vclass == byte[].class) {
                          valueInCache = (byte[])tmplEntry.value;
                        }
                        else if (vclass == byte[][].class) {
                          if (tmpValue instanceof byte[][]) {
                            final byte[][] v1 = (byte[][])tmplEntry.value;
                            final byte[][] v2 = (byte[][])tmpValue;
                            areEqual = ArrayUtils.areByteArrayArrayEquals(v1,
                                v2);
                            if (areEqual) {
                              continue;
                            }
                            else {
                              valueInCache = null;
                            }
                          }
                          else {
                            valueInCache = EntryEventImpl
                                .serialize(tmplEntry.value, out);
                          }
                        }
                        else if (ByteSource.class.isAssignableFrom(vclass)) {
                          ByteSource bs = (ByteSource)tmplEntry.value;
                          // TODO: PERF: Asif: optimize ByteSource to allow
                          // comparison without reading byte[][] into memory
                          Object storedObject = bs
                              .getValueAsDeserializedHeapObject();
                          final Class<?> cls;
                          if (storedObject == null) {
                            valueInCache = null;
                          }
                          else if ((cls = storedObject.getClass()) == byte[].class) {
                            valueInCache = (byte[])storedObject;
                          }
                          else if (cls == byte[][].class
                              && tmpValue instanceof byte[][]) {
                            final byte[][] v1 = (byte[][])storedObject;
                            final byte[][] v2 = (byte[][])tmpValue;
                            areEqual = ArrayUtils.areByteArrayArrayEquals(v1,
                                v2);
                            if (areEqual) {
                              continue;
                            }
                            else {
                              valueInCache = null;
                            }
                          }
                          else {
                            valueInCache = EntryEventImpl
                                .serialize(storedObject, out);
                          }
                        }
                        else if (HeapDataOutputStream.class
                            .isAssignableFrom(vclass)) {
                          valueInCache = ((HeapDataOutputStream)tmplEntry.value)
                              .toByteArray();
                        }
                        else {
                          valueInCache = EntryEventImpl
                              .serialize(tmplEntry.value, out);
                        }
                        // compare byte arrays
                        if (areEqual) {
                          if (tmpBytes == null) {
                            tmpBytes = EntryEventImpl.serialize(tmpValue, out);
                          }
                          if (Arrays.equals(valueInCache, tmpBytes)) {
                            continue;
                          }
                        }
                      }
                    } finally {
                      OffHeapHelper.release(tmplEntry.value);
                    }
                  }
                }

                if (entry.isSerialized()
                    && !Token.isInvalidOrRemoved(tmpValue)) {
                  if (CachedDeserializableFactory.preferObject()) {
                    tmpValue = EntryEventImpl.deserialize((byte[])tmpValue,
                        remoteVersion, in);
                  }
                  else {
                    tmpValue = CachedDeserializableFactory
                        .create((byte[])tmpValue);
                  }
                }
                try {
                  if (tag != null) {
                    tag.replaceNullIDs(sender);
                  }
                  boolean record;
                  if (this.region.getVersionVector() != null) {
                    this.region.getVersionVector().recordVersion(tag.getMemberID(), tag, null);
                    record = true;
                  } else {
                    // bug #50992
                    record = (tmpValue != Token.TOMBSTONE);
                  }
                  if (record) {
                    this.entries.initialImagePut(entry.key, lastModified, tmpValue,
                        wasRecovered, true, tag, sender, this.isSynchronizing);
                  }
                }
                catch (RegionDestroyedException e) {
                  return false;
                }
                catch (CancelException e) {
                  return false;
                }
                didIIP = true;
              }
            }
            //fix for 41814, java level deadlock
            this.entries.lruUpdateCallback();
          }
        }

        if (keys != null) {
          if (tag == null) {
            keys.add(entry.key);
          } else {
            keys.add(String.valueOf(entry.key) + ",v="+tag);
          }
        }
        if (!didIIP) {
          if (tmpValue == null) {
            tmpValue = entry.isLocalInvalid()
              ? Token.LOCAL_INVALID
              : Token.INVALID;
          } else if (entry.isSerialized()) {
            if (CachedDeserializableFactory.preferObject()) {
              tmpValue = EntryEventImpl.deserialize((byte[])tmpValue,
                  remoteVersion, in);
            }
            else {
              tmpValue = CachedDeserializableFactory.create((byte[])tmpValue);
            }
          }
          try {
            // null IDs in a version tag are meant to mean "this member", so
            // we need to change them to refer to the image provider.
            if (tag != null) {
              tag.replaceNullIDs(sender);
            }
            if (logger.finerEnabled() || InitialImageOperation.TRACE_GII_FINER) {
              logger.convertToLogWriter().info("processChunk:initialImagePut:key="+entry.key+",lastModified="
                  +lastModified+",tmpValue="+tmpValue+",wasRecovered="+wasRecovered+",tag="+tag);
            }
            if (this.region.getVersionVector() != null) {
              this.region.getVersionVector().recordVersion(tag.getMemberID(), tag, null);
            }
            this.entries.initialImagePut(entry.key, lastModified, tmpValue,
                wasRecovered, false, tag, sender, this.isSynchronizing);
          }
          catch (RegionDestroyedException e) {
            return false;
          }
          catch (CancelException e) {
            return false;
          }
          didIIP = true;
        }
      }
      // add failed events to ImageState
      if (failedEvents != null) {
        imgState.addFailedEvents(failedEvents);
      }

      if (keys != null) {
        if (logger.fineEnabled() || InitialImageOperation.TRACE_GII_FINER) {
          logger.convertToLogWriter().info("processed these initial image keys: " + keys);
        }
      }
      if (internalBeforeCleanExpiredTombstones != null && internalBeforeCleanExpiredTombstones.getRegionName().equals(this.region.getName())) {
        internalBeforeCleanExpiredTombstones.run();
      }
      if (internalAfterSavedRVVEnd != null && internalAfterSavedRVVEnd.getRegionName().equals(this.region.getName())) {
        internalAfterSavedRVVEnd.run();
      }
      return true;
    }
    finally {
      if (dr != null) {
        dr.removeClearCountReference();
      }
    }
  }

  void processTXIdChunk(Map<?, ?> txIdMap,
      StoppableCountDownLatch txIdChunkRecvLatch) {
    // first create TXRegionStates for all the TXIds being GIIed
    final TXManagerImpl txMgr = this.region.getCache()
        .getCacheTransactionManager();
    final ImageState imgState = this.region.getImageState();
    final CachePerfStats stats = this.region.getCachePerfStats();
    for (Map.Entry<?, ?> e : txIdMap.entrySet()) {
      final TXId txId = (TXId)e.getKey();
      final LockingPolicy lockingPolicy = (LockingPolicy)e.getValue();
      TXStateProxy txProxy;

      // lock image state to avoid changes
      imgState.lockPendingTXRegionStates(true, true);
      try {
        // first check for already existing TXRegionState in image state
        if (imgState.getPendingTXRegionState(txId, false) != null) {
          continue;
        }
        txProxy = txMgr.getOrCreateHostedTXState(txId,
            lockingPolicy, false /* don't check for TX finish */);
      } catch (TransactionDataNodeHasDepartedException te) {
        // source node of transaction has departed so log the error and move
        // on to next
        final LogWriterI18n logger = this.region.getLogWriterI18n();
        if (logger.fineEnabled() || DistributionManager.VERBOSE
            || TXStateProxy.LOG_FINE) {
          logger.info(LocalizedStrings.DEBUG, "IIOP#processTXIdChunk: "
              + "transaction coordinator departed exception when "
              + "deserializing -- ignoring TX", te);
        }
        continue;
      } finally {
        imgState.unlockPendingTXRegionStates(true);
      }

      TXState txState = txProxy.getTXStateForWrite(true, false /* checkTX */);
      // create the TXRegionState so that its finishOrder can be adjusted
      // later if it has been recorded as finished
      txState.writeRegion(this.region, Boolean.FALSE);
      stats.incGetInitialImageTransactionsReceived(1);
    }
    // merge the TXId ordering for committed/rolled back transactions as
    // recorded in TX manager into those received in ImageState so far and those
    // that will be received later
    @SuppressWarnings({ "unchecked", "rawtypes" })
    final Collection<TXId> txIds = (Collection)txIdMap.keySet();
    this.region.getImageState().mergeFinishedTXOrders(this.region, txIds);
    txIdChunkRecvLatch.countDown();
  }

  void processTXChunk(TXBatchMessage txEvents,
      StoppableCountDownLatch txIdChunkRecvLatch) {
    // wait for TXId chunk to be processed first
    int maxTries = 50;
    while (true) {
      try {
        if (txIdChunkRecvLatch.await(StoppableCountDownLatch.RETRY_TIME)) {
          break;
        }
        if (--maxTries <= 0) {
          // fail the GII due to TXId chunk not received or processed
          throw new com.gemstone.gemfire.cache.TimeoutException(
              LocalizedStrings.TIMED_OUT_WAITING_FOR_TXID_Chunk
                  .toLocalizedString(this.region.getFullPath()));
        }
      } catch (InterruptedException ie) {
        this.region.getCancelCriterion().checkCancelInProgress(ie);
        Thread.currentThread().interrupt();
      }
    }
    final TXManagerImpl txMgr = this.region.getCache()
        .getCacheTransactionManager();
    final TXId txId = txEvents.getTXId();
    TXState txState = null;
    boolean removeTXState = false;
    final ImageState imgState = this.region.getImageState();
    final CachePerfStats stats = this.region.getCachePerfStats();
    imgState.lockPendingTXRegionStates(true, true);
    try {
      TXRegionState txrs = imgState.getPendingTXRegionState(txId, false);
      if (txrs != null) {
        // first check if transaction has been already rolled back
        txState = txrs.getTXState();
        txState.lockTXState();
        try {
          if (txState.isRolledBack() || imgState.getFinishedTXOrder(txId) < 0) {
            // remove from ImageState and hosted list if present
            imgState.removePendingTXRegionState(txId);
            removeTXState = true;
            return;
          }
        } finally {
          txState.unlockTXState();
        }
        final int lockFlags = txEvents.conflictWithEX
            ? ExclusiveSharedSynchronizer.CONFLICT_WITH_EX : 0;
        // insert in a separate list that will be replayed at the very start
        // (can be invoked more than once due to failed GIIs)
        final List<Object> pendingOps = txEvents.pendingOps;
        final int numPendingOps;
        if (pendingOps != null && (numPendingOps = pendingOps.size()) > 0) {
          txrs.setGIITXOps(pendingOps, lockFlags);
          stats.incGetInitialImageKeysReceived(numPendingOps);
        }
      }
    } finally {
      imgState.unlockPendingTXRegionStates(true);
      if (removeTXState) {
        txMgr.removeHostedTXState(txId, Boolean.FALSE);
      }
    }
  }

  protected RequestRVVProcessor getRVVDetailsFromProvider(final DistributionManager dm, InternalDistributedMember recipient,
      boolean targetReinitialized) {
    RegionVersionVector received_rvv = null;
    // RequestRVVMessage is to send rvv of gii provider for both persistent and non-persistent region
    RequestRVVMessage rrm = new RequestRVVMessage();
    rrm.regionPath = this.region.getFullPath();
    rrm.targetReinitialized = targetReinitialized;
    rrm.setRecipient(recipient);

    RequestRVVProcessor rvv_processor = new RequestRVVProcessor(this.region.getSystem(),
        recipient);    
    rrm.processorId = rvv_processor.getProcessorId();
    dm.putOutgoing(rrm);
    if (internalAfterRequestRVV != null && internalAfterRequestRVV.getRegionName().equals(this.region.getName())) {
      internalAfterRequestRVV.run();
    }

    try {
      rvv_processor.waitForRepliesUninterruptibly();
    } catch (InternalGemFireException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof com.gemstone.gemfire.cache.TimeoutException) {
        throw (com.gemstone.gemfire.cache.TimeoutException)cause;
      }
      throw ex;
    } catch (ReplyException e) {
      if(!region.isDestroyed()) {
        e.handleAsUnexpected();
      }
    }
    return rvv_processor;
  }

  /** 
   * Compare the received RVV with local RVV and return a set of keys 
   * for unfinished operations. 
   * @param remoteRVV RVV from provider
   * @param localRVV RVV recovered from disk
   *  @return set for keys of unfinished operations.
   */
  protected Set processReceivedRVV(RegionVersionVector remoteRVV, RegionVersionVector localRVV) {
    if (remoteRVV == null) {
      return null;
    }
    // calculate keys for unfinished ops
    HashSet keys = new HashSet();
    if (this.region.getDataPolicy().withPersistence() && localRVV.isNewerThanOrCanFillExceptionsFor(remoteRVV)) {
      // only search for unfinished keys when localRVV has something newer 
      // and the region is persistent region
      Iterator it = this.region.getBestLocalIterator(false);
      int count = 0;
      LogWriterI18n logger = this.region.getLogWriterI18n();
      VersionSource<?> myId = this.region.getVersionMember();
      while (it.hasNext()) {
        RegionEntry mapEntry = (RegionEntry)it.next();
        VersionStamp<?> stamp = mapEntry.getVersionStamp();
        VersionSource<?> id = stamp.getMemberID();
        if (id == null) {
          id = myId;
        }
        if (!remoteRVV.contains(id, stamp.getRegionVersion())) {
          // found an unfinished operation
          keys.add(mapEntry.getKeyCopy());
          remoteRVV.recordVersion(id, stamp.getRegionVersion(), null);
          
          if (count<10) {
            if (TRACE_GII) {
              logger.info(LocalizedStrings.DEBUG, "Region:"+region.getFullPath()+" found unfinished operation key="
                  +mapEntry.getKeyCopy()+",member="+stamp.getMemberID()+",region version="+stamp.getRegionVersion());
            }
          }
          count++;
        }
      }
      if (!keys.isEmpty()) {
        if (TRACE_GII) {
          logger.info(LocalizedStrings.DEBUG, "Region:"+region.getFullPath()+" found "+keys.size()+" unfinished operations.");
        }
      }
    }
    return keys;
  }
  
  protected void saveReceivedRVV(RegionVersionVector rvv) {
    assert rvv != null;

    // Make sure the RVV is at least as current as
    // the provider's was when the GII began.  This ensures that a
    // concurrent clear() doesn't prevent the new region's RVV from being
    // initialized and that any vector entries that are no longer represented
    // by stamps in the region are not lost
    if (TRACE_GII) {
      region.getLogWriterI18n().info(LocalizedStrings.DEBUG, "Applying received version vector "+rvv.fullToString()+ " to " + region.getName());
    }
    //TODO - RVV - Our current RVV might reflect some operations 
    //that are concurrent updates. We want to keep those updates. However
    //it might also reflect things that we recovered from disk that we are going
    //to remove. We'll need to remove those from the RVV somehow.
    region.getVersionVector().recordVersions(rvv, null);
    if(region.getDataPolicy().withPersistence()) {
      region.getDiskRegion().writeRVV(region, false);
      region.getDiskRegion().writeRVVGC(region);
    }
    if (TRACE_GII) {
      region.getLogWriterI18n().info(LocalizedStrings.DEBUG, "version vector is now "
          + region.getVersionVector().fullToString());
    }
  }
  
  /**
   * This is the processor that handles {@link ImageReplyMessage}s that
   * arrive
   */
  class ImageProcessor extends ReplyProcessor21  {
    /**
     * true if this image has been rendered moot, esp. by a region destroy,
     * a clear, or a shutdown
     */
    private volatile boolean abort = false;
    
    /**
     * Tracks the status of this operation.
     * <p>
     * Keys are the senders (@link {@link InternalDistributedMember}), and
     * values are instances of {@link Status}.
     */
    private final Map statusMap = new HashMap();
    
    /**
     * number of outstanding executors currently in-flight on this request
     */
    private final AI msgsBeingProcessed = CFactory.createAI();

    /** used to wait for processing until TXId chunk is received */
    private final StoppableCountDownLatch txIdChunkRecvLatch =
        new StoppableCountDownLatch(region.getCancelCriterion(), 1);

    @Override
    public boolean isSevereAlertProcessingEnabled() {
      return isSevereAlertProcessingForced();
    }

    /** 
     * process the memberid:threadid -> sequence# information transmitted 
     * along with an initial image from another cache 
     */ 
    void processRegionStateMessage(RegionStateMessage msg) { 
      if (msg.eventState != null) { 
        if (region.cache.getLoggerI18n().fineEnabled() || EventTracker.VERBOSE) { 
          region.cache.getLoggerI18n().info(
              LocalizedStrings.DEBUG,
              "Applying event state to region " + region.getName() + " from "
                  + msg.getSender());
        } 
        region.recordEventState(msg.getSender(), msg.eventState); 
      }
    } 

    /**
     * Track the status of this request from (a given sender)
     */
    class Status  {
      /**
       * number of chunks we have received from this sender
       * <p>
       * Indexed by seriesNum, always 0)
       */
      int[] msgsProcessed = null;
      
      /**
       * Number of chunks total we need before we are done.
       * <p>
       * This is not set until the last chunk is received, so while it is
       * zero we know we are not done.
       * <p>
       * Indexed by seriesNum, always 0.
       */
      int[] numInSeries = null;

      /**
       * Have we received event state from the provider?
       */
      boolean eventStateReceived;
      
      /**
       * Have we received all of the chunked messages from the provider?
       */
      boolean allChunksReceived;
      
      /**
       * Obligatory logger, from the region's cache
       */
      final LogWriterI18n log 
          = InitialImageOperation.this.region.getCache().getLoggerI18n();
      
      /** Return true if this is the very last reply for this member */
      protected synchronized boolean trackMessage(ImageReplyMessage m) {
        if (this.msgsProcessed == null) {
          this.msgsProcessed = new int[m.numSeries];
        }
        if (this.numInSeries == null) {
          this.numInSeries = new int[m.numSeries];
        }
        this.msgsProcessed[m.seriesNum]++;

        if (m.lastInSeries) {
          this.numInSeries[m.seriesNum] = m.msgNum + 1;
        }
        if (log.fineEnabled()) {
          log.fine("InitialImage Message Tracking Status: Processor id " + getProcessorId() +
            ";Sender " + m.getSender() +
            ";Messages Processed: " + arrayToString(this.msgsProcessed) +
            ";NumInSeries:" + arrayToString(this.numInSeries));
        }

        // this.numInSeries starts out as zeros and gets initialized
        // for a series only when we get a lastInSeries true.
        // Since we increment msgsProcessed, the following condition
        // cannot be true until sometime after we've received the
        // lastInSeries for a given series.
        this.allChunksReceived = Arrays.equals(this.msgsProcessed, this.numInSeries);
        return(this.allChunksReceived);
      }
    }
    
    
    public ImageProcessor(final InternalDistributedSystem system,
                          InternalDistributedMember member) {
      super(system, member);
    }

    public ImageProcessor(InternalDistributedSystem system,
                          Set members) {
      super(system, members); 
    }
    
    /*
     * (non-Javadoc)
     * @see com.gemstone.gemfire.distributed.internal.ReplyProcessor21#process(com.gemstone.gemfire.distributed.internal.DistributionMessage)
     */
    @Override  
    public void process(DistributionMessage msg) {
        // ignore messages from members not in the wait list
      if (!waitingOnMember(msg.getSender())) {
        return;
      }
      Status status = getStatus(msg.getSender());
      this.msgsBeingProcessed.incrementAndGet();
      EntryLogger.setSource(msg.getSender(), "gii");
      try {
        boolean isDone;
        if (msg instanceof RegionStateMessage) {
          isDone = false;
          status.eventStateReceived = true;
          processRegionStateMessage((RegionStateMessage)msg);
        } else {
          isDone = true;
          ImageReplyMessage m = (ImageReplyMessage)msg;
          
          boolean isLast = true; // is last message for this member?
          if (m.entries != null) {
            try {
              if (internalAfterReceivedImageReply != null && internalAfterReceivedImageReply.getRegionName().equals(region.getName())) {
                internalAfterReceivedImageReply.run();
              }
              
              // for testing #48725
              if (imageProcessorTestLatch != null
                  && region.getFullPath()
                      .equals(imageProcessorTEST_REGION_NAME)) {
                imageProcessorTestLatchWaiting = true;
                try {
                  imageProcessorTestLatch.await();
                } catch (InterruptedException e) {
                  // ignore the exception. the above latch just used for testing
                }
                imageProcessorTestLatchWaiting = false;
              }
              
              // bug 37461: don't allow abort flag to be reset
              boolean isAborted = this.abort; // volatile fetch
              if (!isAborted) {
                if (m.isTXChunk) {
                  processTXChunk((TXBatchMessage)m.entries,
                      this.txIdChunkRecvLatch);
                }
                else if (m.isTXIdChunk) {
                  processTXIdChunk((Map<?, ?>)m.entries,
                      this.txIdChunkRecvLatch);
                }
                else {
                  isAborted = !processChunk((List<?>)m.entries, m.failedEvents,
                      m.getSender(), m.remoteVersion);
                }
                if (isAborted) {
                  this.abort = true; // volatile store
                }
              }
              isLast = trackMessage(m); // interpret series/msgNum
              isDone = isAborted || isLast;
              
              // @todo ericz send an abort message to image provider if
              // !doContinue (region was destroyed or cache closed)
              if (isDone) {
                if (this.abort) {
                  // Bug 48578: In deltaGII, if abort in processChunk, we should mark trustRVV=false
                  // to force full GII next time.
                  InitialImageOperation.this.gotImage = false;
                  InitialImageOperation.this.region.getLogWriterI18n().info(LocalizedStrings.DEBUG,
                      "processChunk is aborted for region "+InitialImageOperation.this.region.getFullPath()
                      +", rvv is "+InitialImageOperation.this.region.getVersionVector()+". Do full gii next time");
                } else {
                  InitialImageOperation.this.gotImage = true;
                }
              }
              if ((isDone || m.lastInSeries) && m.isDeltaGII) {
                InitialImageOperation.this.isDeltaGII = true;
              }
            }
            catch( DiskAccessException dae) { 
              ReplyException ex = new ReplyException("while processing entries", dae);
              ex.setSenderIfNull(region.getCache().getMyId());
              processException(ex);
            }
            // save exceptions so they can be thrown from waitForReplies
            catch (Throwable t) {
              Error err;
              if (t instanceof Error && SystemFailure.isJVMFailureError(
                  err = (Error)t)) {
                SystemFailure.initiateFailure(err);
                // If this ever returns, rethrow the error. We're poisoned
                // now, so don't let this thread continue.
                throw err;
              }
              // Whenever you catch Error or Throwable, you must also
              // check for fatal JVM error (see above).  However, there is
              // _still_ a possibility that you are dealing with a cascading
              // error condition, so you also need to check to see if the JVM
              // is still usable:
              SystemFailure.checkFailure();
              ReplyException ex = new ReplyException("while processing entries", t);
              ex.setSenderIfNull(region.getCache().getMyId());
              processException(ex);
            }
          }
          else {
            // if a null entries was received (no image was found), then
            // we're done with that member (TX chunks may still be in-flight)
            if (isDone && m.isDeltaGII) {
              if (trackMessage(m)) {
                InitialImageOperation.this.gotImage = true;
              }
              InitialImageOperation.this.isDeltaGII = true;
            }
          }
          if (m.holderToSend != null) {
            InitialImageOperation.this.rcvd_holderToSync = m.holderToSend;
          }
        }
        if (isDone) {
          super.process(msg, false); // removes from members and cause us to
                                     // ignore future messages received from that member
        }
      } catch (RegionDestroyedException e) {
        // bug #46135 - disk store can throw this exception
        InitialImageOperation.this.region.getCancelCriterion().checkCancelInProgress(e);
      }
      finally {
        this.msgsBeingProcessed.decrementAndGet();
        checkIfDone(); // check to see if decrementing msgsBeingProcessed requires signaling to proceed
        EntryLogger.clearSource();
      }
    }  

    /**
     * True if we have signalled to stop waiting
     * <p>
     * Contract of {@link ReplyProcessor21#stillWaiting()} is that it must
     * never return true after having returned false.
     */
    private volatile boolean finishedWaiting = false;
    
    /** Overridden to wait for messages being currently processed:
     *  This situation can come about if a member departs while we
     *  are still processing data from that member
     */
    @Override  
    protected boolean stillWaiting() {
      if (finishedWaiting) { // volatile fetch
        return false;
      }
      if (this.msgsBeingProcessed.get() > 0) {
        // to fix bug 37391 always wait for msgsBeingProcessed to go to 0;
        // even if abort is true.
        return true;
      }
      // Volatile fetches and volatile store:
      if (this.abort || !super.stillWaiting()) {
        finishedWaiting = true;
        return false;
      }
      else {
        return true;
      }
    }
    
        
    @Override  
    public String toString() {
      //bug 37189  These strings are a work-around for an escaped reference
      //in ReplyProcessor21 constructor
      String msgsBeingProcessedStr = (this.msgsBeingProcessed == null) ? "nullRef"
    		  : String.valueOf(this.msgsBeingProcessed.get());
      String regionStr = (InitialImageOperation.this.region == null) ? "nullRef"
    		  : InitialImageOperation.this.region.getFullPath();
      String numMembersStr = (this.members == null) ? "nullRef" : String.valueOf(numMembers());
//      String membersToStr = (this.members == null) ? "nullRef" : membersToString();
    	
      return "<" + this.getClass().getName() + " " + this.getProcessorId() +
        " waiting for " + numMembersStr + " replies" + 
        (exception == null ? "" : (" exception: " + exception)) +
        " from " + membersToString() 
        + "; waiting for " + msgsBeingProcessedStr +
            " messages in-flight; " 
        + "region=" + regionStr 
        + "; abort=" + this.abort + ">";
    }    
    
    private Status getStatus(InternalDistributedMember sender) {
      Status status;
      synchronized (this) {
        status = (Status)this.statusMap.get(sender);
        if (status == null) {
          status = new Status();
          this.statusMap.put(sender, status);
        }
      }
      return status;
    }
      
    private boolean trackMessage(ImageReplyMessage m) {
      return getStatus(m.getSender()).trackMessage(m);
    }
        
  }
  
  protected static String arrayToString(int[] a) {
    StringBuilder buf = new StringBuilder();
    buf.append("[");
    for (int i = 0; i < a.length; i++) {
      buf.append(String.valueOf(a[i]));
      if (i < (a.length - 1))
        buf.append(",");
    }
    buf.append("]");
    return buf.toString();
  }
  
  protected static LocalRegion getGIIRegion(final DistributionManager dm, final String regionPath,
      final boolean targetReinitialized) {

    final LogWriterI18n logger = dm.getLoggerI18n();
    LocalRegion lclRgn = null;
    int initLevel = targetReinitialized ? LocalRegion.AFTER_INITIAL_IMAGE
        : LocalRegion.ANY_INIT;
    int oldLevel = LocalRegion.setThreadInitLevelRequirement(initLevel);
    try {
      DistributedSystem system = dm.getSystem();
      //GemFireCache cache = (GemFireCache)CacheFactory.getInstance(system);
      if (logger.fineEnabled()) {
        logger.fine("RequestImageMessage: attempting to get region reference for " +
            regionPath + ", initlevel=" + initLevel);
      }
      lclRgn = LocalRegion.getRegionFromPath(system, regionPath);
      // if this is a targeted getInitialImage after a region was initialized,
      // make sure this is the region that was reinitialized.
      if (lclRgn != null && !lclRgn.isUsedForPartitionedRegionBucket()
          && targetReinitialized && !lclRgn.reinitialized_new()) {
        lclRgn = null; // got a region that wasn't reinitialized, so must not be the right one
        if (logger.fineEnabled()) {
          logger.fine("GII message process: Found region, but " +
              "wasn't reinitialized, so assuming region destroyed " +
              "and recreated");
        }
      }
    }
    finally {
      LocalRegion.setThreadInitLevelRequirement(oldLevel);
    }
    if (lclRgn == null || !lclRgn.isInitialized()) {
      String msg = lclRgn == null ? "region not found" :
        "region not initialized yet";
      logger.fine(msg + ", nothing to do");
      // allow finally block to send a failure message
      return null;
    }

    if (lclRgn.scope.isLocal()) {
      logger.fine("local scope region, nothing to do");
      // allow finally block to send a failure message
      return null;
    }
    return lclRgn;
  }

  /**
   * This is the message that initiates a request for an image
   */
  public static final class RequestImageMessage 
    extends DistributionMessage implements MessageWithReply {

    /**
     * a version vector is transmitted with the request if we are merely
     * synchronizing with an existing region, or providing missed updates
     * for a recreated region
     */
    public RegionVersionVector versionVector;
    
    /**
     * if a version vector is transmitted, this will be sent along with it
     * to tell the image provider that only changes made by this ID should
     * be sent back
     */
    public VersionSource lostMemberVersionID;

    /**
     * the distribution ID of the lost member (see above)
     */
    public InternalDistributedMember lostMemberID;
    
    /**
     * Name of the region we want
     * This field is public for test code.
     */
    public String regionPath;
    
    /**
     * Id of the {@link ImageProcessor} that will handle the replies
     */
    protected int processorId;
    
    /**
     * True if we only want keys for the region (no values)
     */
    protected boolean keysOnly;
    
    /**
     * true if we want to get a full GII if there are tombstone version problems
     */
    protected boolean checkTombstoneVersions;
    
    /**
     * If true, recipient should wait until fully initialized before
     * returning data.
     */
    protected boolean targetReinitialized;
        
    /**
     * whether severe alert processing should be performed in the reply processor
     * for this message
     */
    protected transient boolean severeAlertEnabled;

    /* key list for unfinished operations */
    protected Set unfinishedKeys;

    /**
     * True if we want to only fetch the values for the unfinished keys.
     */
    protected boolean unfinishedKeysOnly;

    public static volatile CountDownLatch testLatch;
    public static volatile boolean testLatchWaiting;
    public static volatile String TEST_REGION_NAME;

    @Override  
    public int getProcessorId() {
      return this.processorId;
    }

    @Override  
    final public int getProcessorType() {
      return this.targetReinitialized ? DistributionManager.WAITING_POOL_EXECUTOR :
                                DistributionManager.HIGH_PRIORITY_EXECUTOR;
    }
    
    public boolean goWithFullGII(DistributedRegion rgn, RegionVersionVector requesterRVV) {
      LogWriterI18n logger = rgn.getLogWriterI18n();
      if (!rgn.getDataPolicy().withPersistence()) {
        // non-persistent regions always do full GII
        if (logger.fineEnabled()) {
          logger.fine("Region " + rgn.getFullPath() + " is not a persistent region, do full GII");
        }
        return true;
      }
      if (!rgn.getVersionVector().isRVVGCDominatedBy(requesterRVV)) {
        if (logger.fineEnabled()) {
          logger.fine("Region " + rgn.getFullPath() + "'s local RVVGC is not dominated by remote RVV="+requesterRVV+", do full GII");
        }
        return true;
      }
      // TODO GGG: verify GII after UpgradeDiskStore
      return false;
    }

    @Override  
    protected void process(final DistributionManager dm) {
      Throwable thr = null;
      final LogWriterI18n logger = dm.getLoggerI18n();
      final boolean lclAbortTest = abortTest;
      if (lclAbortTest) abortTest = false;
      logger.info(LocalizedStrings.DEBUG, "processing GII for "+regionPath);
      boolean sendFailureMessage = true;
      DistributedRegion giiRegion = null;
      try {
        Assert.assertTrue(this.regionPath != null, "Region path is null.");
        final DistributedRegion rgn = (DistributedRegion)getGIIRegion(dm, this.regionPath, this.targetReinitialized);
        giiRegion = rgn;
        if (rgn == null) {
          return;
        }


        // can simulate gc tombstone in middle of packing
        if (internalAfterReceivedRequestImage != null && internalAfterReceivedRequestImage.getRegionName().equals(rgn.getName())) {
          internalAfterReceivedRequestImage.run();
        }

        // for testing
        if (testLatch != null && rgn.getFullPath().equals(TEST_REGION_NAME)) {
          testLatchWaiting = true;
          try {
            testLatch.await();
          } catch (InterruptedException e) {
            // ignore the exception. the above latch just used for testing
          }
          testLatchWaiting = false;
        }

        if (this.versionVector != null) {
          if (this.versionVector.isForSynchronization() && !rgn.getConcurrencyChecksEnabled()) {
            if (TRACE_GII) {
              logger.info(LocalizedStrings.DEBUG, "ignoring synchronization request as this region has no version vector");
            }
            replyNoData(dm, true, new ArrayList<EventID>());
            sendFailureMessage = false;
            return;
          }
          if (TRACE_GII) {
            logger.info(LocalizedStrings.DEBUG, "checking version vector against region's ("
                +rgn.getVersionVector().fullToString()+")");
          }
          // [bruce] I suppose it's possible to have this check return a list of
          // specific versions that the sender is missing.  The current check
          // just stops when it finds the first inconsistency
          if ( !rgn.getVersionVector().isNewerThanOrCanFillExceptionsFor(this.versionVector) ) {
            // Delta GII might have unfinished operations to send. Otherwise,
            // no need to send any data.  This is a synchronization request and this region's
            // vector doesn't have anything that the other region needs
            if (this.unfinishedKeys == null || this.unfinishedKeys.isEmpty()) {
              if (TRACE_GII) {
                logger.info(LocalizedStrings.DEBUG, "version vector reports that I have nothing that the requester hasn't already seen");
              }
              replyNoData(dm, true, rgn.getFailedEvents(sender));
              sendFailureMessage = false;
              return;
            }
          } else {
            // [bruce] I haven't seen this happen.  Once we know this works we can
            // change this to fine-level or remove it
            if (TRACE_GII) {
              logger.info(LocalizedStrings.DEBUG, "version vector reports that I have updates the requester hasn't seen, remote rvv is "+this.versionVector);
            }
          }
        }

        final int numSeries = 1; // @todo ericz parallelize using series
        final int seriesNum = 0;
        
        // chunkEntries returns false if didn't finish
        if (TRACE_GII) {
          logger.info(LocalizedStrings.DEBUG, "RequestImageMessage: Starting chunkEntries for " 
              + rgn.getFullPath());
        }
        
        final InitialImageFlowControl flowControl = InitialImageFlowControl.register(dm, getSender());
        
        if (rgn instanceof HARegion) {
          ((HARegion)rgn).startServingGIIRequest();
        }
        final ImageState imgState = rgn.getImageState();
        boolean markedOngoingGII = false;
        try {
          boolean recoveringForLostMember = (this.lostMemberVersionID != null);
          RegionVersionHolder holderToSync = null;
          if (recoveringForLostMember && this.lostMemberID != null) {
            // wait for the lost member to be gone from this VM's membership and all ops applied to the cache
            try {
              dm.getMembershipManager().waitForDeparture(this.lostMemberID);
              RegionVersionHolder rvh = rgn.getVersionVector().getHolderForMember(this.lostMemberVersionID);
              if (rvh != null) {
                holderToSync = rvh.clone();
              }
              if (TRACE_GII) {
                RegionVersionHolder holderOfRequest = this.versionVector.getHolderForMember(this.lostMemberVersionID);
                if (holderToSync.isNewerThanOrCanFillExceptionsFor(holderOfRequest)) {
                  logger.info(LocalizedStrings.DEBUG, "synchronizeWith detected mismatch region version holder for lost member "
                      +lostMemberVersionID + ". Old is "+holderOfRequest+", new is "+holderToSync);
                }
              }
            } catch (TimeoutException e) {
              if (TRACE_GII) {
                logger.info(LocalizedStrings.DEBUG, "timed out waiting for the departure of " + this.lostMemberID + " before processing delta GII request");
              }
            }
          }
            if (rgn instanceof HARegion) { 
            //long eventXferStart = System.currentTimeMillis();
              Map<? extends DataSerializable, ? extends DataSerializable> eventState = rgn.getEventState(); 
              if (eventState != null && eventState.size() > 0) {
                RegionStateMessage.send(dm, getSender(), this.processorId, eventState, true);
              }
            }
            if (this.checkTombstoneVersions && this.versionVector != null && rgn.concurrencyChecksEnabled) {
              synchronized(rgn.getCache().getTombstoneService().blockGCLock) {
              if (goWithFullGII(rgn, this.versionVector)) {
                if (TRACE_GII) {
                  logger.info(LocalizedStrings.DEBUG, "have to do fullGII");
                }
                this.versionVector = null; // full GII
              } else {
                // lock GIILock only for deltaGII
                int count = rgn.getCache().getTombstoneService().incrementGCBlockCount();
                markedOngoingGII = true;
                if (TRACE_GII) {
                  logger.info(LocalizedStrings.DEBUG, "There're "+count+" Delta GII on going");
                }
              }
              }
            }
            //logger.info(LocalizedStrings.DEBUG,
            //        "DEBUG: event state transfer time = " + (System.currentTimeMillis() - eventXferStart) + " size=" + eventState.size());
            if (this.unfinishedKeys != null && !this.unfinishedKeys.isEmpty()
                && rgn.keyRequiresRegionContext()) {
              for (Object uk : this.unfinishedKeys) {
                ((KeyWithRegionContext)uk).setRegionContext(rgn);
              }
            }

            final int fid = flowControl.getId();
            final int txMsgNum = chunkTXEntries(rgn, dm, CHUNK_SIZE_IN_BYTES,
                flowControl, fid, seriesNum, numSeries);

            final RegionVersionHolder holderToSend = holderToSync;

            boolean finished = rgn.chunkEntries(sender, CHUNK_SIZE_IN_BYTES, !keysOnly, versionVector,
                (HashSet)this.unfinishedKeys, unfinishedKeysOnly, flowControl, new TObjectIntProcedure() {
              int msgNum = txMsgNum;

              boolean last = false;
              /**
               * @param entList ArrayList of entries
               * @param b positive if last chunk
               * @return true to continue to next chunk
               */
              public boolean execute(Object entList, int b) {
                if (rgn.getCache().isClosed()) {
                  return false;
                }
                
                if (this.last) {
                  throw new InternalGemFireError(LocalizedStrings.InitialImageOperation_ALREADY_PROCESSED_LAST_CHUNK.toLocalizedString());
                }

                List entries = (List)entList;
                this.last = b > 0 && !lclAbortTest; // if abortTest, then never send last flag set to true
                try {
                  boolean abort = rgn.isDestroyed();
                  //                if (logger.fineEnabled()) {
                  //                  logger.fine("RequestImageMessage execute: abort = " + abort
                  //                      + "; b = " + b + "; last = " + this.last +
                  //                      "; lclAbortTest = " + lclAbortTest);
                  //                }
                  if (!abort) {
                      replyWithData(dm, entries,
                          this.last ? rgn.getFailedEvents(sender) : null,
                          seriesNum, msgNum++, numSeries, this.last, fid,
                          versionVector != null, holderToSend);
                  }
                  return !abort;
                }
                catch (CancelException e) {
                  //                if (logger.fineEnabled()) {
                  //                  logger.fine("RequestImageMessage execute: caught CacheClosed");
                  //                }
                  return false;
                }
              }
            });


            if (TRACE_GII || TRACE_GII_FINER) {
              logger.info(LocalizedStrings.DEBUG, "RequestImageMessage: ended chunkEntries for " 
                  + rgn.getFullPath() + ";  finished =" + finished);
            }

            // Call to chunkEntries above will have sent at least one
            // reply with last==true for the last message. (unless doing abortTest or
            // region is destroyed or cache closed)
            if (finished && !lclAbortTest) {
              sendFailureMessage = false;
              return; // sent msg with last indicated
            }
          // One more chance to discover region or cache destruction...
          rgn.checkReadiness();
        } finally {
          if (markedOngoingGII) {
            int count = rgn.getCache().getTombstoneService().decrementGCBlockCount();
            assert count >= 0;
            if (count == 0) {
              markedOngoingGII = false;
              if (TRACE_GII) {
                logger.info(LocalizedStrings.DEBUG, "Delta GII count is reset");
              }
            }
          }
          if (rgn instanceof HARegion) {
            ((HARegion)rgn).endServingGIIRequest();
          }
          flowControl.unregister();

        }
        // This should never happen in production code!!!!
        
        // Code specific to abortTest... :-(
        Assert.assertTrue(lclAbortTest, this + 
            ": Did not finish sending image, but region, cache, and DS are alive.");

        initiateLocalAbortForTest(dm);
      }
      catch (RegionDestroyedException e) {
//        thr = e; Don't marshal an exception here; just return null
        if (TRACE_GII) {
          logger.info(LocalizedStrings.DEBUG, this + "; Region destroyed: aborting image provision");
        }
      }
      catch (IllegalStateException e) {
//      thr = e; Don't marshal an exception here; just return null
        if (TRACE_GII) {
          logger.info(LocalizedStrings.DEBUG, this + "; disk region deleted? aborting image provision", e);
        }
      }
      catch (CancelException e) {
//      thr = e; Don't marshal an exception here; just return null
        if (TRACE_GII) {
          logger.info(LocalizedStrings.DEBUG, this + "; Cache Closed: aborting image provision");
        }
      }
      catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          sendFailureMessage = false; // Don't try to respond!
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        thr = t;
      }
      finally {
//        if (logger.fineEnabled()) {
//          logger.fine("RequestImageMessage: wrapping up, sendFailureMessage = " 
//              + sendFailureMessage);
//        }
        if (giiRegion instanceof BucketRegion) {
          ((BucketRegion)giiRegion).releaseSnapshotGIIWriteLock();
        }
        if (sendFailureMessage) {
          // if we get here then send reply possibly with an exception
          ReplyException rex = null;
          if (thr != null) {
            rex = new ReplyException(thr);
          }
          // null chunk signals receiver that we are aborting
          ImageReplyMessage.send(getSender(), processorId, rex, dm, 
              null, null, 0, 0, 1, true, 0, false, null);
        } // !success
        
        if (internalAfterSentImageReply != null && regionPath.endsWith(internalAfterSentImageReply.getRegionName())) {
          internalAfterSentImageReply.run();
        }
      }
    }

    protected int chunkTXEntries(DistributedRegion rgn, DistributionManager dm,
        final int chunkSizeInBytes, final InitialImageFlowControl flowControl,
        final int fid, final int seriesNum, final int numSeries)
        throws IOException {

      // loop through existing TXStates and register inProgress ones
      // (including those that are committing but ignoring those that
      // have completed the commit/rollback)
      final TXManagerImpl txMgr = rgn.getCache().getCacheTransactionManager();
      int offset, msgNum = 0;

      final ArrayList<TXRegionState> txrss = new ArrayList<TXRegionState>();
      final THashMap txIdMap = new THashMap();
      // first acquire locks on all TXStates in a particular order to avoid deadlock
      Collection<TXStateProxy> inProgressTXns = txMgr.getHostedTransactionsInProgress();
      final TXStateProxy[] orderedInProgressTXns = inProgressTXns
          .toArray(new TXStateProxy[inProgressTXns.size()]);
      Arrays.sort(orderedInProgressTXns,
          Comparator.comparing(t -> t.getTransactionId()));

      for (TXStateProxy proxy : orderedInProgressTXns) {
        final TXState txState = proxy.getLocalTXState();
        if (txState != null && txState.isInProgress()) {
          boolean added = false;
          txState.lockTXState();
          if (txState.isInProgress()) {
            final TXRegionState[] regions = txState.getTXRegionStatesSnap();
            for (TXRegionState txrs : regions) {
              if (txrs.region == rgn) {
                if (txState.isInProgress()) {
                  txrss.add(txrs);
                  txIdMap.put(txState.getTransactionId(),
                      txState.getLockingPolicy());
                  added = true;
                }
                break;
              }
            }
          }
          if (!added) {
            txState.unlockTXState();
          }
        }
      }
      if (txrss.isEmpty()) {
        return msgNum;
      }

      final TXBatchMessage batchMessage = new TXBatchMessage();
      final ArrayList<Object> txEvents = new ArrayList<Object>();
      final HeapDataOutputStream hdos = new HeapDataOutputStream(
          chunkSizeInBytes + 2048, this.sender.getVersionObject());

      try {
        // first send the list of TXIds
        ImageReplyMessage.send(getSender(), this.processorId, null, dm, txIdMap,
            null, seriesNum, msgNum++, numSeries, false, fid, false, null);
        for (TXRegionState txrs : txrss) {
          txrs.lock();
          boolean txrsLocked = true;
          try {
            final Collection<?> events = txrs.getInternalEntryMap().values();
            final int numEvents = events.size();
            if (numEvents == 0) {
              continue;
            }
            txEvents.addAll(events);
            batchMessage.initTXState(txrs.getTXState());
            offset = 0;
            while (true) {
              batchMessage.init(txEvents, offset, chunkSizeInBytes, null, rgn,
                  null, false);
              InternalDataSerializer.invokeToData(batchMessage, hdos);
              if (rgn.isDestroyed()) {
                return -1;
              }
              // send as a message but release TXRegionState lock to avoid
              // holding it for too long
              txrs.unlock();
              txrsLocked = false;
              flowControl.acquirePermit();
              replyWithData(dm, hdos, null, seriesNum, msgNum++, numSeries,
                  false, fid, false, null);
              hdos.clearForReuse();
              offset = batchMessage.getOffset();
              if (offset >= numEvents) {
                break;
              }
              else {
                txrs.lock();
                txrsLocked = true;
              }
            }
            txEvents.clear();
          } catch (CancelException ce) {
            return -1;
          } finally {
            if (txrsLocked) {
              txrs.unlock();
            }
          }
        }
      } finally {
        for (TXRegionState txrs : txrss) {
          txrs.getTXState().unlockTXState();
        }
      }

      return msgNum;
    }

    private void replyNoData(DistributionManager dm, boolean isDeltaGII, ArrayList<EventID> arrayList) {
      ImageReplyMessage.send(getSender(), this.processorId, null, dm, null,
          arrayList, 0, 0, 1, true, 0, isDeltaGII, null);
    }

    protected final void replyWithData(DistributionManager dm, Object entries,
                               ArrayList<EventID> failedEvents,
                               int seriesNum, int msgNum, int numSeries, boolean lastInSeries,
                               int flowControlId, boolean isDeltaGII, RegionVersionHolder holderToSend) {
      ImageReplyMessage.send(getSender(), this.processorId, null, dm, entries,
          failedEvents, seriesNum, msgNum, numSeries, lastInSeries,
          flowControlId, isDeltaGII, holderToSend);
    }  

    // test hook
    private void initiateLocalAbortForTest(final DM dm) {
      if (!dm.getSystem().isDisconnecting()) {
        LogWriterI18n logger = dm.getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("abortTest: Disconnecting from distributed system and sending null chunk to abort");
        }
        // can't disconnect the distributed system in a thread owned by the ds,
        // so start a new thread to do the work
        ThreadGroup group = LogWriterImpl.createThreadGroup(
            "InitialImageOperation abortTest Threads",
            dm.getLoggerI18n());
        Thread disconnectThread = 
            new Thread(group, "InitialImageOperation abortTest Thread") {
          @Override  
          public void run() {
            dm.getSystem().disconnect();
          }
        };
        disconnectThread.setDaemon(true);
        disconnectThread.start();
      } // !isDisconnecting
      // ...end of abortTest code
    }
    
    public int getDSFID() {
      return REQUEST_IMAGE_MESSAGE;
    }

    @Override  
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.regionPath = DataSerializer.readString(in);
      this.processorId = in.readInt();
      this.keysOnly = in.readBoolean();
      this.targetReinitialized = in.readBoolean();
      this.checkTombstoneVersions = in.readBoolean();
      this.unfinishedKeysOnly = in.readBoolean();
      this.lostMemberVersionID = (VersionSource)DataSerializer.readObject(in);
      this.versionVector = (RegionVersionVector)DataSerializer.readObject(in);
      this.lostMemberID = (InternalDistributedMember)DataSerializer.readObject(in);
      this.unfinishedKeys = (Set)DataSerializer.readObject(in);
    }
    
    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.regionPath, out);
      out.writeInt(this.processorId);
      out.writeBoolean(this.keysOnly);
      out.writeBoolean(this.targetReinitialized);
      out.writeBoolean(this.checkTombstoneVersions);
      out.writeBoolean(this.unfinishedKeysOnly);
      DataSerializer.writeObject(this.lostMemberVersionID, out);
      DataSerializer.writeObject(this.versionVector, out);
      DataSerializer.writeObject(this.lostMemberID, out);
      DataSerializer.writeObject(this.unfinishedKeys, out);
    }

    @Override  
    public String toString() {
      StringBuffer buff = new StringBuffer();
      String cname = getClass().getName().substring(
          getClass().getPackage().getName().length() + 1);
      buff.append(cname);
      buff.append("(region path='"); // make sure this is the first one
      buff.append(this.regionPath);
      buff.append("'; sender=");
      buff.append(getSender());
      buff.append("; keysOnly=");
      buff.append(this.keysOnly);
      buff.append("; processorId=");
      buff.append(this.processorId);
      buff.append("; waitForInit=");
      buff.append(this.targetReinitialized);
      buff.append("; checkTombstoneVersions=");
      buff.append(this.checkTombstoneVersions);
      if (this.lostMemberVersionID != null) {
        buff.append("; lostMember=").append(lostMemberVersionID);
      }
      buff.append("; versionVector=").append(versionVector);
      buff.append("; unfinished keys=").append(unfinishedKeys);
      buff.append("; unfinished keys only=").append(unfinishedKeysOnly);
      buff.append(")");
      return buff.toString();
    }

    @Override  
    public boolean isSevereAlertCompatible() {
      return severeAlertEnabled;
    }
  }

  /**
   * FilterInfo message processor.
   */
  class FilterInfoProcessor extends ReplyProcessor21  {
    boolean filtersReceived;
    
    public FilterInfoProcessor(final InternalDistributedSystem system,
                          InternalDistributedMember member) {
      super(system, member);
    }

    public FilterInfoProcessor(InternalDistributedSystem system,
                          Set members) {
      super(system, members); 
    }
        
    @Override  
    public void process(DistributionMessage msg) {
      // ignore messages from members not in the wait list
      if (!waitingOnMember(msg.getSender())) {
        return;
      }
      try {
        if ( !(msg instanceof FilterInfoMessage) ) {
          return;
        }
        FilterInfoMessage m = (FilterInfoMessage)msg;
        if (m.getException() != null) {
          return;
        }
        
        if (region.getLogWriterI18n().infoEnabled()) {
          try {
            CacheClientNotifier ccn = CacheClientNotifier.getInstance();
            CacheClientProxy proxy = ((HAContainerWrapper)ccn.getHaContainer()).getProxy(
                region.getName());      
            if (region.getCache().getLoggerI18n().fineEnabled()) {
              region.getCache().getLoggerI18n().fine("Processing FilterInfo for proxy: " + proxy + " : " + msg);
            }
          } catch (Exception ex) {
            // Ignore.
          }
        }
        
        try {
          m.registerFilters(region);
        } catch (Exception ex) {
          if (region.getCache().getLogger().infoEnabled()) {
            region.getCache().getLogger().info("Exception while registering " +
            " filters during GII. " + ex.getMessage());
          }
        }
        
        this.filtersReceived = true;
        
      } finally {
        super.process(msg);
      }
    }       
            
    @Override  
    public String toString() {
      String cname = getClass().getName().substring(
          getClass().getPackage().getName().length() + 1);
      return "<" + cname + " " + this.getProcessorId() + 
      " replies" + (exception == null ? "" : (" exception: " + exception)) +
        " from " + membersToString() + ">";
    }            
 
    @Override      
    protected boolean logMultipleExceptions() {
      return false;
    }
  }
  
  /**
   * This is the message thats sent to get Filter information.
   */
  public static final class RequestFilterInfoMessage 
    extends DistributionMessage implements MessageWithReply {

    /**
     * Name of the region.
     */
    protected String regionPath;
    
    /**
     * Id of the {@link ImageProcessor} that will handle the replies
     */
    protected int processorId;
            
    @Override  
    public int getProcessorId() {
      return this.processorId;
    }
        
    @Override  
    final public int getProcessorType() {
      return DistributionManager.HIGH_PRIORITY_EXECUTOR;
    }
    
    @Override  
    protected void process(final DistributionManager dm) {
      Throwable thr = null;
      final LogWriterI18n logger = dm.getLoggerI18n();            
      boolean sendFailureMessage = true;
      String failureMessage = "Failed to process filter info request. ";
      LocalRegion lclRgn = null;
      ReplyException rex = null;
      try {
        Assert.assertTrue(this.regionPath != null, "Region path is null.");        
        DistributedSystem system = dm.getSystem();
        lclRgn = LocalRegion.getRegionFromPath(system, this.regionPath);
        
        if (lclRgn == null) {
          failureMessage += " Region not found.";
          if (logger.fineEnabled()){
            logger.fine(this + failureMessage);
          }
          return;
        }
        if (!lclRgn.isInitialized()) {
          failureMessage += " Region not yet initialized.";
          if (logger.fineEnabled()){
            logger.fine(this + failureMessage);
          }
          return;
        }
        
        final DistributedRegion rgn = (DistributedRegion)lclRgn;
        FilterInfoMessage.send(dm, getSender(), this.processorId, rgn, null);         
        sendFailureMessage = false;
      } catch (CancelException e) {
        if (logger.fineEnabled()){
          logger.fine(this + "; Cache Closed: aborting filter info request.");
        }
        rex = new ReplyException("Cache Closed: filter info request aborted.");
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          sendFailureMessage = false; // Don't try to respond!
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        thr = t;
      } finally {
        if (sendFailureMessage) {
          // if we get here then send reply possibly with an exception
          if (thr != null) {
            rex = new ReplyException(thr);
          }
          
          if (rex == null){
            rex = new ReplyException(failureMessage);
          }
          FilterInfoMessage.send(dm, getSender(), this.processorId, lclRgn, rex);
        } // !success
      }
    }
        
    public int getDSFID() {
      return REQUEST_FILTERINFO_MESSAGE;
    }

    @Override  
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.regionPath = DataSerializer.readString(in);
      this.processorId = in.readInt();
    }
    
    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.regionPath, out);
      out.writeInt(this.processorId);
    }

    @Override  
    public String toString() {
      StringBuffer buff = new StringBuffer();
      String cname = getClass().getName().substring(
          getClass().getPackage().getName().length() + 1);
      buff.append(cname);
      buff.append("(region path='");
      buff.append(this.regionPath);
      buff.append("'; sender=");
      buff.append(getSender());
      buff.append("; processorId=");
      buff.append(this.processorId);
      buff.append(")");
      return buff.toString();
    }

  }

  /**
   * RequestRVV message processor.
   */
  class RequestRVVProcessor extends ReplyProcessor21  {
//    Set keysOfUnfinishedOps;
    RegionVersionVector received_rvv;
    boolean snapshotGIIWriteLock ;
    public RequestRVVProcessor(final InternalDistributedSystem system,
                          InternalDistributedMember member) {
      super(system, member);
    }

    public RequestRVVProcessor(InternalDistributedSystem system,
                          Set members) {
      super(system, members); 
    }
        
    @Override  
    public void process(DistributionMessage msg) {
      ReplyMessage reply = (ReplyMessage)msg;
      LogWriterI18n logger = region.getLogWriterI18n();
      try {
        // if remote member has exception or shutdown, just try next recipient
        if (reply == null) {
          // if remote member is shutting down, the reply will be null
          if (TRACE_GII) {
            logger.info(LocalizedStrings.DEBUG, "Did not received RVVReply from "+Arrays.toString(getMembers())+". Remote member might be down.");
          }
          return;
        }
        if (reply.getException()!=null) {
          if (TRACE_GII) {
            logger.info(LocalizedStrings.DEBUG, "Failed to get RVV from "+reply.getSender()+" due to "+reply.getException());
          }
          return;
        }
        if (reply instanceof RVVReplyMessage) {
          RVVReplyMessage rvv_reply = (RVVReplyMessage)reply;
          received_rvv = rvv_reply.versionVector;
          snapshotGIIWriteLock = rvv_reply.snapshotGIIWriteLock;
        }
      } finally {
        if (received_rvv == null) {
          if (TRACE_GII) {
            logger.info(LocalizedStrings.DEBUG, reply.getSender()+"did not send back rvv. Maybe it's non-persistent proxy region or remote region "
                +region.getFullPath()+ " not found or not initialized. Nothing to do.");
          }
        }
        super.process(msg);
      }
    }       
            
    @Override  
    public String toString() {
      String cname = getClass().getName().substring(
          getClass().getPackage().getName().length() + 1);
      StringBuffer sb = new StringBuffer();
      sb.append("<" + cname + " " + this.getProcessorId());
      sb.append(" ,from " + membersToString() + ">");
      return sb.toString();
    }            
 
    @Override      
    protected boolean logMultipleExceptions() {
      return false;
    }
  }
  
  /** 
   * RVVReplyMessage transmits the GII provider's RVV to requester 
   *  
   * @author Gester 
   */ 
  public static class RVVReplyMessage extends ReplyMessage { 
     
    @Override   
    public boolean getInlineProcess() { 
      return false; 
    } 
 
    RegionVersionVector versionVector;

    boolean snapshotGIIWriteLock;
     
    public RVVReplyMessage() { 
    } 
 
    private RVVReplyMessage(InternalDistributedMember mbr, int processorId,
        RegionVersionVector rvv, boolean snapshotGIIWriteLock) {
      setRecipient(mbr); 
      setProcessorId(processorId); 
      this.versionVector = rvv;
      this.snapshotGIIWriteLock = snapshotGIIWriteLock;
    } 
     
    public static void send(DM dm, InternalDistributedMember dest, int processorId, 
        RegionVersionVector rvv, ReplyException ex, boolean snapshotGIIWriteLock) {
      RVVReplyMessage msg = new RVVReplyMessage(dest, processorId, rvv, snapshotGIIWriteLock);
      if (ex != null) {
        msg.setException(ex);
      }
      dm.putOutgoing(msg); 
    }
     
    @Override   
    public void toData(DataOutput dop) throws IOException { 
      super.toData(dop);
      if (versionVector != null) {
        dop.writeBoolean(true);
        dop.writeBoolean(versionVector instanceof DiskRegionVersionVector);
        versionVector.toData(dop);
        dop.writeBoolean(snapshotGIIWriteLock);
      } else {
        dop.writeBoolean(false);
      }
    } 

    @Override   
    public String toString() { 
      String descr = super.toString(); 
      if (versionVector != null) {
        descr += "; versionVector=" + (RegionVersionVector.DEBUG? versionVector.fullToString() : versionVector);
      }
      return descr; 
    } 
     
    @Override   
    public void fromData(DataInput dip) throws IOException, ClassNotFoundException { 
      super.fromData(dip);
      boolean has = dip.readBoolean();
      if (has) {
        boolean persistent = dip.readBoolean();
        versionVector = RegionVersionVector.create(persistent, dip);
        snapshotGIIWriteLock = dip.readBoolean();
      }
    } 
     
    /* (non-Javadoc) 
     * @see com.gemstone.gemfire.internal.DataSerializableFixedID#getDSFID() 
     */ 
    @Override   
    public int getDSFID() { 
      return RVV_REPLY_MESSAGE; 
    } 
  }

  /**
   * This is the message thats sent to get RVV from GII provider.
   */
  public static final class RequestRVVMessage 
    extends DistributionMessage implements MessageWithReply {

    /**
     * Name of the region.
     */
    protected String regionPath;
    
    /**
     * Id of the {@link ImageProcessor} that will handle the replies
     */
    protected int processorId;
    
    /**
     * If true, recipient should wait until fully initialized before
     * returning data.
     */
    protected boolean targetReinitialized;

    @Override  
    public int getProcessorId() {
      return this.processorId;
    }
        
    @Override  
    final public int getProcessorType() {
      return this.targetReinitialized ? DistributionManager.WAITING_POOL_EXECUTOR :
                                DistributionManager.HIGH_PRIORITY_EXECUTOR;
    }

    /**
     * GII operation will wait if there are some running transaction after
     * StateFlush message. This method will make GII wait till all those transactions
     * are finished.
     */
    protected boolean waitForRunningTXs(DistributedRegion rgn,
                                     InternalDistributedMember sender) {
     // Wait for all write operations to get over.
     BucketRegion bucketRegion = (BucketRegion)rgn;
      InitializingBucketMembershipObserver listener =
              new InitializingBucketMembershipObserver(bucketRegion, ((BucketRegion) rgn).cache, sender);
     return bucketRegion.takeSnapshotGIIWriteLock(listener);
    }
    
    @Override  
    protected void process(final DistributionManager dm) {
      Throwable thr = null;
      final LogWriterI18n logger = dm.getLoggerI18n();            
      boolean sendFailureMessage = true;
      LocalRegion lclRgn = null;
      ReplyException rex = null;
      RegionVersionVector<?> rvv;
      boolean snapshotGIIWriteLock = false;
      try {
        Assert.assertTrue(this.regionPath != null, "Region path is null.");        
        final DistributedRegion rgn = (DistributedRegion)getGIIRegion(dm, this.regionPath, this.targetReinitialized);
        if (rgn == null) {
          return;
        }

        if( rgn instanceof BucketRegion) {
          snapshotGIIWriteLock = waitForRunningTXs(rgn, getSender());
        }

        if (internalAfterGIILock != null && internalAfterGIILock.getRegionName().equals(rgn.getName())) {
          internalAfterGIILock.run();
        }

        if (!rgn.getGenerateVersionTag() || (rvv = rgn.getVersionVector()) == null) {
          logger.fine(this + " non-persistent proxy region, nothing to do. Just reply");
          // allow finally block to send a failure message
          RVVReplyMessage.send(dm, getSender(), processorId, null, null, snapshotGIIWriteLock);
          sendFailureMessage = false;
          return;
        } else {
          rvv = rvv.getCloneForTransmission();
          RVVReplyMessage.send(dm, getSender(), processorId, rvv, null, snapshotGIIWriteLock);
          sendFailureMessage = false;
        }
      }
      catch (RegionDestroyedException e) {
        if (logger.fineEnabled()){
          logger.fine(this + "; Region destroyed: Request RVV aborting.");
        }
      }
      catch (CancelException e) {
        if (logger.fineEnabled()){
          logger.fine(this + "; Cache Closed: Request RVV aborting.");
        }
      } catch (VirtualMachineError err) {
        sendFailureMessage = false; // Don't try to respond!
        SystemFailure.initiateFailure(err);
        // If this ever returns, rethrow the error.  We're poisoned
        // now, so don't let this thread continue.
        throw err;
      } catch (Throwable t) {
        // Whenever you catch Error or Throwable, you must also
        // catch VirtualMachineError (see above).  However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        thr = t;
      } finally {
        if (sendFailureMessage) {
          // if we get here then send reply possibly with an exception
          if (thr != null) {
            rex = new ReplyException(thr);
          }
          
          RVVReplyMessage.send(dm, getSender(), processorId, null, rex, snapshotGIIWriteLock);
        } // !success
      }
    }
        
    public int getDSFID() {
      return REQUEST_RVV_MESSAGE;
    }

    @Override  
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.regionPath = DataSerializer.readString(in);
      this.processorId = in.readInt();
      this.targetReinitialized = in.readBoolean();
    }
    
    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.regionPath, out);
      out.writeInt(this.processorId);
      out.writeBoolean(this.targetReinitialized);
    }

    @Override  
    public String toString() {
      StringBuffer buff = new StringBuffer();
      String cname = getClass().getName().substring(
          getClass().getPackage().getName().length() + 1);
      buff.append(cname);
      buff.append("(region path='");
      buff.append(this.regionPath);
      buff.append("'; sender=");
      buff.append(getSender());
      buff.append("; processorId=");
      buff.append(this.processorId);
      buff.append("; targetReinitalized=");
      buff.append(this.targetReinitialized);
      buff.append(")");
      return buff.toString();
    }

  }

  /**
   * This is the message thats sent to get RVV from GII provider.
   */
  public static final class RequestSyncMessage 
    extends HighPriorityDistributionMessage {

    /**
     * Name of the region.
     */
    protected String regionPath;
    
    /**
     * IDs that destroyed the region or crashed during GII that the GII
     * recipient got events from that weren't sent to this member
     */
    protected VersionSource[] lostVersionSources;
    
    
    @Override  
    protected void process(final DistributionManager dm) {
      final LogWriterI18n logger = dm.getLoggerI18n();
      LocalRegion lclRgn = null;
      try {
        Assert.assertTrue(this.regionPath != null, "Region path is null.");        
        final DistributedRegion rgn = (DistributedRegion)getGIIRegion(dm, this.regionPath, false);
        if (rgn != null) {
          if (dm.getLoggerI18n().fineEnabled()) {
            dm.getLoggerI18n().fine("synchronizing region with " + Arrays.toString(lostVersionSources));
          }
          for (VersionSource lostSource: this.lostVersionSources) {
            InternalDistributedMember mbr = null;
            if (lostSource instanceof InternalDistributedMember) {
              mbr = (InternalDistributedMember)lostSource;
            }
            InitialImageOperation op = new InitialImageOperation(rgn, rgn.entries);
            op.synchronizeWith(getSender(), lostSource, mbr);
          }
        }
      }
      catch (RegionDestroyedException e) {
        if (logger.fineEnabled()){
          logger.fine(this + "; Region destroyed, nothing to do.");
        }
      }
      catch (CancelException e) {
        if (logger.fineEnabled()){
          logger.fine(this + "; Cache Closed, nothing to do.");
        }
      } catch (VirtualMachineError err) {
        SystemFailure.initiateFailure(err);
        throw err;
      } catch (Throwable t) {
        SystemFailure.checkFailure();
      }
    }
        
    public int getDSFID() {
      return REQUEST_SYNC_MESSAGE;
    }

    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.regionPath, out);
      out.writeBoolean(this.lostVersionSources[0] instanceof DiskStoreID);
      out.writeInt(this.lostVersionSources.length);
      for (VersionSource id: this.lostVersionSources) {
        id.writeEssentialData(out);
      }
    }

    @Override  
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.regionPath = DataSerializer.readString(in);
      boolean persistentIDs = in.readBoolean();
      int len = in.readInt();
      this.lostVersionSources = new VersionSource[len];
      for (int i=0; i<len; i++) {
        this.lostVersionSources[i] =
            (persistentIDs? DiskStoreID.readEssentialData(in)
                          : InternalDistributedMember.readEssentialData(in));
      }
    }
    
    @Override  
    public String toString() {
      StringBuffer buff = new StringBuffer();
      String cname = getClass().getName().substring(
          getClass().getPackage().getName().length() + 1);
      buff.append(cname);
      buff.append("(region path='");
      buff.append(this.regionPath);
      buff.append("'; sender=");
      buff.append(getSender());
      buff.append("; sources=").append(Arrays.toString(this.lostVersionSources));
      buff.append(")");
      return buff.toString();
    }

  }

  public static final class ImageReplyMessage extends ReplyMessage {

    public static final short IS_VERSIONED_LIST = (INTERNAL_FLAG << 1);
    public static final short IS_TXID_CHUNK = (IS_VERSIONED_LIST << 1);
    public static final short IS_TX_CHUNK = (IS_TXID_CHUNK << 1);

    /** the next entries in this chunk. Null means abort. */
    protected Object entries;
    protected transient Object rawEntries;

    /** set to true if "entries" has InitialImageVersionedEntryList */
    protected boolean isVersionedList;

    /** set to true if "entries" has list of TXIds */
    protected boolean isTXIdChunk;

    /** set to true if "entries" has TXRegionState chunk (TXBatchMessage) */
    protected boolean isTXChunk;

    /**
     * A list of events that failed to apply for replicated regions due to
     * GemFireXD constraints. These need to be sent across so that upcoming node
     * can ignore these events when received directly otherwise it may apply
     * on top of existing updated value successfully after enquequing it as
     * a suspect event.
     */
    protected ArrayList<EventID> failedEvents;

    /** total number of series, duplicated in each message */
    protected int numSeries;
    
    /** the series this message belongs to  (0-based) */
    protected int seriesNum;
    
    /** the number of this message within this series */
    protected int msgNum;
    
    /** whether this message is the last one in this series */
    protected boolean lastInSeries;
    
    private int flowControlId;

    private boolean isDeltaGII;
    
    /* The region version holder for the lost member. It's used for synchronizeWith() only */
    private boolean hasHolderToSend;
    private RegionVersionHolder holderToSend;

    /** the {@link Version} of the remote peer */
    private transient Version remoteVersion;

    @Override
    public boolean getInlineProcess() {
      return false;
    }

    /**
     * @param entries the data to send back, if null then all the following
     * parameters are ignored and any future replies from this member will
     * be ignored, and the streaming of chunks is considered aborted by the
     * receiver.
     * @param seriesNum series number for this message (0-based)
     * @param msgNum message number in this series (0-based)
     * @param numSeries total number of series
     * @param lastInSeries if this is the last message in this series
     * @param isDeltaGII if this message is for deltaGII
     * @param holderToSend higher version holder to sync for the lost member
     */
    public static void send(InternalDistributedMember recipient, int processorId, 
                            ReplyException exception,
                            DistributionManager dm,
                            Object entries,
                            ArrayList<EventID> failedEvents,
                            int seriesNum, int msgNum, int numSeries, boolean lastInSeries,
                            int flowControlId, boolean isDeltaGII, RegionVersionHolder holderToSend) {
      ImageReplyMessage m = new ImageReplyMessage();

      m.processorId = processorId;
      if (exception != null) {
        m.setException(exception);
       dm.getLoggerI18n().fine("Replying with exception: " + m, exception);
      }
      m.setRecipient(recipient);
      m.entries = entries;
      if (entries instanceof InitialImageVersionedEntryList) {
        m.isVersionedList = true;
      }
      else if (entries instanceof Map<?, ?>) {
        m.isTXIdChunk = true;
      }
      else if (entries != null && !(entries instanceof List<?>)) {
        m.isTXChunk = true;
      }
      m.failedEvents = failedEvents;
      m.seriesNum = seriesNum;
      m.msgNum = msgNum;
      m.numSeries = numSeries;
      m.lastInSeries = lastInSeries;
      m.flowControlId = flowControlId;
      m.isDeltaGII = isDeltaGII;
      m.holderToSend = holderToSend;
      m.hasHolderToSend = (holderToSend != null);
      dm.putOutgoing(m);
    }        
    
    
    
    
    @Override
    public void process(DM dm, ReplyProcessor21 processor) {
      //We have to do this here, rather than in the reply processor code,
      //because the reply processor may be null.
      try {
        super.process(dm, processor);
      } finally {
        //TODO we probably should send an abort message to the sender
        //if we have aborted, but at the very least we need to keep
        //the permits going.
        if (this.flowControlId != 0) {
          FlowControlPermitMessage.send(dm, getSender(), this.flowControlId);
        }
      }
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
      // TODO Auto-generated method stub
      return super.clone();
    }

    @Override  
    public int getDSFID() {
      return IMAGE_REPLY_MESSAGE;
    }

    @Override  
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      super.fromData(in);

      this.remoteVersion = InternalDataSerializer
          .getVersionForDataStreamOrNull(in);
      /*
      // 701 peers can get ArrayList from 700 peers so we always have to read
      // an ArrayList. This hack will be simplified in later versions (> 7.0.1)
      ArrayList list = DataSerializer.readArrayList(in);
      Object listData = null;
      if (list != null /* fix bug 46874 * && list.size() > 0) {
        listData = list.get(0);
      }
      if (listData instanceof InitialImageVersionedEntryList) {
        this.entries = (List)listData;
      } else {
        this.entries = list;
      }
      */
      if ((this.flags & IS_VERSIONED_LIST) != 0) {
        this.entries = DataSerializer.readObject(in);
        this.isVersionedList = true;
      }
      else if ((this.flags & IS_TX_CHUNK) != 0) {
        TXBatchMessage txEntries = new TXBatchMessage();
        InternalDataSerializer.invokeFromData(txEntries, in);
        // mark entries as having been received from GII
        for (Object txes : txEntries.pendingOps) {
          if (txes instanceof TXEntryState) {
            ((TXEntryState)txes).fromGII = true;
          }
        }
        this.entries = txEntries;
        this.isTXChunk = true;
      }
      else if ((this.flags & IS_TXID_CHUNK) != 0) {
        int size = InternalDataSerializer.readArrayLength(in);
        if (size > 0) {
          THashMap txIdMap = new THashMap(size);
          while (size-- > 0) {
            TXId txId = new TXId();
            InternalDataSerializer.invokeFromData(txId, in);
            LockingPolicy lockingPolicy = LockingPolicy
                .fromOrdinal((int)InternalDataSerializer.readUnsignedVL(in));
            txIdMap.put(txId, lockingPolicy);
          }
          this.entries = txIdMap;
        }
        else {
          this.entries = Collections.emptyMap();
        }
        this.isTXIdChunk = true;
      }
      else {
        this.entries = DataSerializer.readArrayList(in);
      }
      this.failedEvents = DataSerializer.readArrayList(in);
      this.seriesNum = in.readInt();
      this.msgNum = in.readInt();
      this.numSeries = in.readInt();
      this.lastInSeries = in.readBoolean();
      this.flowControlId = in.readInt();
      this.isDeltaGII = in.readBoolean();
      this.hasHolderToSend = in.readBoolean();
      if (this.hasHolderToSend) {
        this.holderToSend = new RegionVersionHolder(in);
      }
    }

    @Override  
    public void toData(DataOutput out) throws IOException {
      super.toData(out);

      this.remoteVersion = InternalDataSerializer
          .getVersionForDataStreamOrNull(out);
      // We still need to send an ArrayList for backward compatibility.
      // All 700 peers will always read an ArrayList. So we can not give
      // them InitialImageVersionedEntryList when they are expecting ArrayList.
      if (this.isVersionedList) {
        DataSerializer.writeObject(this.entries, out);
      }
      else if (this.isTXChunk) {
        if (this.entries instanceof HeapDataOutputStream) {
          ((HeapDataOutputStream)this.entries).sendTo(out);
        }
        else {
          InternalDataSerializer
              .invokeToData((TXBatchMessage)this.entries, out);
        }
      /*
      if (this.entries instanceof InitialImageVersionedEntryList) {
        ArrayList list = new ArrayList(1);
        list.add(this.entries);
        DataSerializer.writeArrayList(list, out);
      */
      }
      else if (this.isTXIdChunk) {
        Map<?, ?> txIdMap = (Map<?, ?>)this.entries;
        int size = txIdMap.size();
        InternalDataSerializer.writeArrayLength(size, out);
        if (size > 0) {
          for (Map.Entry<?, ?> e : txIdMap.entrySet()) {
            // key is TXId
            TXId txId = (TXId)e.getKey();
            InternalDataSerializer.invokeToData(txId, out);
            // value is LockingPolicy
            LockingPolicy lockPolicy = (LockingPolicy)e.getValue();
            InternalDataSerializer.writeUnsignedVL(lockPolicy.ordinal(), out);
          }
        }
      }
      else {
        DataSerializer.writeArrayList((ArrayList)this.entries, out);
      }
      DataSerializer.writeArrayList(this.failedEvents, out);
      out.writeInt(this.seriesNum);
      out.writeInt(this.msgNum);
      out.writeInt(this.numSeries);
      out.writeBoolean(this.lastInSeries);
      out.writeInt(this.flowControlId);
      out.writeBoolean(this.isDeltaGII);
      out.writeBoolean(this.hasHolderToSend);
      if (this.hasHolderToSend) {
        InternalDataSerializer.invokeToData(this.holderToSend, out);
      }
    }

    @Override
    protected short computeCompressedShort(short flags) {
      if (this.isVersionedList) {
        flags |= IS_VERSIONED_LIST;
      }
      else if (this.isTXChunk) {
        flags |= IS_TX_CHUNK;
      }
      else if (this.isTXIdChunk) {
        flags |= IS_TXID_CHUNK;
      }
      return flags;
    }

    @Override  
    public String toString() {
      StringBuilder buff = new StringBuilder();
      String cname = getClass().getName().substring(
          getClass().getPackage().getName().length() + 1);
      buff.append(cname);
      buff.append("(processorId=");
      buff.append(this.processorId);
      if (this.flags != 0) {
        buff.append(",flags=0x").append(Integer.toHexString(this.flags));
      }
      buff.append(" from ");
      buff.append(this.getSender());
      ReplyException ex = this.getException();
      if (ex != null) {
        buff.append(" with exception ");
        buff.append(ex);
      }
      if (entries == null) {
        buff.append("; with no data - abort");
      }
      else {
        if (this.isTXChunk) {
          TXBatchMessage txEntries;
          Exception txe = null;
          if (this.entries instanceof HeapDataOutputStream) {
            HeapDataOutputStream hdos = (HeapDataOutputStream)this.entries;
            ByteArrayDataInput dis = new ByteArrayDataInput();
            dis.initialize(hdos.toByteArray(), hdos.getVersion());
            try {
              txEntries = new TXBatchMessage();
              InternalDataSerializer.invokeFromData(txEntries, dis);
              // remove the reference added by TXBatchMessage
              TXStateProxy txProxy = txEntries.getTXProxy();
              if (txProxy != null) {
                txProxy.decRefCount();
                txProxy.removeSelfFromHostedIfEmpty(null);
              }
            } catch (ClassNotFoundException enfe) {
              txe = enfe;
              txEntries = null;
            } catch (IOException ioe) {
              txe = ioe;
              txEntries = null;
            }
          }
          else {
            txEntries = (TXBatchMessage)this.entries;
          }
          if (txEntries == null) {
            buff.append("; txEntriesException=").append(txe);
          }
          else if (txEntries.getCount() > 20) {
            buff.append("; txEntryCount=").append(txEntries.getCount());
          }
          else {
            buff.append("; ").append(txEntries.toString());
          }
        }
        else if (this.isTXIdChunk) {
          buff.append("; TXIds=");
          for (Object e : ((Map<?, ?>)this.entries).keySet()) {
            TXId txId = (TXId)e;
            buff.append(txId.memberId).append(':').append(txId.uniqId)
                .append(',');
          }
        }
        else {
          buff.append("; entryCount=");
          buff.append(((List<?>)this.entries).size());
        }
        if (this.failedEvents != null) {
          buff.append("; failedEventIds=").append(this.failedEvents);
        }
        buff.append("; msgNum=");
        buff.append(this.msgNum);
        buff.append("; Series=");
        buff.append(this.seriesNum);
        buff.append("/");
        buff.append(this.numSeries);
        buff.append("; lastInSeries=");
        buff.append(this.lastInSeries);
        buff.append("; flowControlId=");
        buff.append(this.flowControlId);
        buff.append("; isDeltaGII=");
        buff.append(this.isDeltaGII);
      }
      if (this.remoteVersion != null) {
        buff.append("; remoteVersion=").append(this.remoteVersion);
      }
      if (this.holderToSend != null) {
        buff.append("; holderToSend=").append(this.holderToSend);
      }
      buff.append(")");
      return buff.toString();
    }
  }
  
  /**
   * Represents a key/value pair returned from a peer as part of an
   * {@link InitialImageOperation}
   */
  public static final class Entry implements DataSerializableFixedID {
    /**
     * key for this entry.  Null if "end of chunk" marker entry
     */
    Object key;
    
    /**
     * value of this entry.  Null when invalid or local invalid
     */
    Object value = null;
    
    /**
     * Characterizes this entry
     * <p>
     * Defaults to invalid, not serialized, not local invalid.
     * The "invalid" flag is not used.  When invalid, localInvalid is false
     * and the values is null.
     * 
     * @see EntryBits
     */
    private byte entryBits = 0;

    /** lastModified is stored as "cache time milliseconds" */
    private long lastModified;

    /**
     * if the region has versioning enabled, we need to transfer the version
     * with the entry
     */
    private VersionTag versionTag;

    final void clearForReuse(byte entryBits) {
      this.key = null;
      this.value = null;
      this.entryBits = entryBits;
      this.lastModified = 0L;
      this.versionTag = null;
    }

    /** Given local milliseconds, store as cache milliseconds */
    final void setLastModified(DM dm, long localMillis) {
      this.lastModified = localMillis;
    }

    /** Return lastModified as local milliseconds */
    public long getLastModified(DM dm) {
      return this.lastModified;
    }

    public boolean isSerialized() {
      return EntryBits.isSerialized(this.entryBits);
    }

    void setSerialized(boolean isSerialized) {
      this.entryBits = EntryBits.setSerialized(this.entryBits, isSerialized);
    }

    public boolean isEagerDeserialize() {
      return EntryBits.isEagerDeserialize(this.entryBits);
    }

    void setEagerDeserialize() {
      this.entryBits = EntryBits.setEagerDeserialize(this.entryBits);
    }

    public boolean isInvalid() {
      return (this.value == null) && !EntryBits.isLocalInvalid(this.entryBits);
    }

    void setInvalid() {
      this.entryBits = EntryBits.setLocalInvalid(this.entryBits, false);
      this.value = null;
    }

    public boolean isLocalInvalid() {
      return EntryBits.isLocalInvalid(this.entryBits);
    }
    
    void setLocalInvalid() {
      this.entryBits = EntryBits.setLocalInvalid(this.entryBits, true);
      this.value = null;
    }

    public final boolean isTombstone() {
      return EntryBits.isTombstone(this.entryBits);
    }

    void setTombstone() {
      this.entryBits = EntryBits.setTombstone(this.entryBits, true);
    }

    public VersionTag getVersionTag() {
      return versionTag;
    }

    public void setVersionTag(VersionTag tag) {
      this.versionTag = tag;
    }

    public int getDSFID() {
      return IMAGE_ENTRY;
    }

    public void toData(DataOutput out) throws IOException {
      out.writeByte(this.entryBits);
      byte flags = (this.versionTag != null) ? HAS_VERSION : 0;
      flags |= (this.versionTag instanceof DiskVersionTag) ? PERSISTENT_VERSION : 0;
      out.writeByte(flags);
      DataSerializer.writeObject(this.key, out);
      if (!EntryBits.isTombstone(this.entryBits)) {
        if (!isEagerDeserialize()) {
          DataSerializer.writeObjectAsByteArray(this.value, out);
        }
        else {
          DataSerializer.writeObject(this.value, out);
        }
      }
      out.writeLong(this.lastModified);
      if (this.versionTag != null) {
        InternalDataSerializer.invokeToData(this.versionTag, out);
      }
    }

    static final byte HAS_VERSION = 0x01;
    static final byte PERSISTENT_VERSION = 0x02;

    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      this.entryBits = in.readByte();
      byte flags = in.readByte();
      this.key = DataSerializer.readObject(in);
      
      if (EntryBits.isTombstone(this.entryBits)) {
        this.value = Token.TOMBSTONE;
      }
      else {
        if (!isEagerDeserialize()) {
          this.value = DataSerializer.readByteArray(in);
        }
        else {
          this.value = DataSerializer.readObject(in);
        }
      }
      this.lastModified = in.readLong();
      if ((flags & HAS_VERSION) != 0) {
        // note that null IDs must be later replaced with the image provider's ID
        this.versionTag = VersionTag.create((flags & PERSISTENT_VERSION) != 0, in);
      }
    }

    public int calcSerializedSize(final NullDataOutputStream dos) {
      dos.reset();
      try {
        toData(dos);
        return dos.size();
      } catch (IOException ex) {
        RuntimeException ex2 = new IllegalArgumentException(LocalizedStrings.InitialImageOperation_COULD_NOT_CALCULATE_SIZE_OF_OBJECT.toLocalizedString());
        ex2.initCause(ex);
        throw ex2;
      }
    }
    @Override  
    public String toString() {
      return "GIIEntry[key=" + this.key + "]";
    }

    @Override
    public Version[] getSerializationVersions() {
      return null;
    }
  }

  /**
   * List to hold versioned entries for InitialImageOperation requested from a member.
   *
   */
  public static class InitialImageVersionedEntryList extends ArrayList<Entry> implements DataSerializableFixedID, Externalizable {
    
    public static final boolean DEBUG = Boolean.getBoolean("gemfire.InitialImageVersionedObjectList.DEBUG");

    /**
     * if the region has versioning enabled, we need to transfer the version
     * with the entry
     */
    List<VersionTag> versionTags;

    /**
     * InitialImageOperation.Entry list 
     *  
     */
    //List<Entry> entries;

    boolean isRegionVersioned = false;

    public InitialImageVersionedEntryList() {
      super();
      this.versionTags = new ArrayList();
    }

    public InitialImageVersionedEntryList(boolean isRegionVersioned, int size) {
      super(size);
      this.isRegionVersioned = isRegionVersioned;
      if (isRegionVersioned) {
        this.versionTags = new ArrayList(size);
      } else {
        this.versionTags = Collections.EMPTY_LIST;
      }
    }

    public static InitialImageVersionedEntryList create(DataInput in)
        throws IOException, ClassNotFoundException {
      InitialImageVersionedEntryList newList = new InitialImageVersionedEntryList();
      InternalDataSerializer.invokeFromData(newList, in);
      return newList;
    }

    @Override
    public boolean add(Entry entry) {
      VersionTag tag = entry.getVersionTag();
      // Remove duplicate before serialization
      entry.setVersionTag(null);
      return addEntryAndVersion(entry, tag);
    }

    private boolean addEntryAndVersion(Entry entry, VersionTag versionTag) {

      // version tag can be null if only keys are sent in InitialImage.
      if (this.isRegionVersioned && versionTag != null) { 
        int tagsSize = this.versionTags.size();
        if (tagsSize != super.size()) {
          // this should not happen - either all or none of the entries should have tags
          throw new InternalGemFireException();
        }
        this.versionTags.add(versionTag);
      }

      // Add entry without version tag in top-level ArrayList. 
      return super.add(entry);
    }

    /*
     * This should be called only on receiving side only as this call resets the
     * InitialImageOperation.Entry with version tag.
     */
    @Override
    public Entry get(int index) {
      Entry entry = super.get(index);

      VersionTag tag = getVersionTag(index);
      entry.setVersionTag(tag);

      return entry;
    }

    private VersionTag<VersionSource> getVersionTag(int index) {
      VersionTag tag = null;
      if (isRegionVersioned && this.versionTags != null) {
        tag = versionTags.get(index);
      }
      return tag;
    }

    @Override
    public int size() {
      // Sanity check for entries size and versions size.
      if (isRegionVersioned) {
        if (super.size() != versionTags.size()) {
          throw new InternalGemFireException();
        }
      }
      return super.size();
    }

    @Override
    public void clear() {
      super.clear();
      this.versionTags.clear();
    }
    /**
     * 
     * @return whether the source region had concurrency checks enabled
     */
    public boolean isRegionVersioned() {
      return this.isRegionVersioned;
    }
    
    /**
     * replace null membership IDs in version tags with the given member ID.
     * VersionTags received from a server may have null IDs because they were
     * operations  performed by that server.  We transmit them as nulls to cut
     * costs, but have to do the swap on the receiving end (in the client)
     * @param sender
     */
    public void replaceNullIDs(DistributedMember sender) {
      for (VersionTag versionTag: versionTags) {
        if (versionTag != null) {
          versionTag.replaceNullIDs((InternalDistributedMember) sender);
        }
      }
    }

    @Override
    public int getDSFID() {
      return DataSerializableFixedID.INITIAL_IMAGE_VERSIONED_OBJECT_LIST;
    }

    static final byte FLAG_NULL_TAG = 0;
    static final byte FLAG_FULL_TAG = 1;
    static final byte FLAG_TAG_WITH_NEW_ID = 2;
    static final byte FLAG_TAG_WITH_NUMBER_ID = 3;

    @Override
    public void toData(DataOutput out) throws IOException {
      LogWriterI18n log = null;
      int flags = 0;
      boolean hasEntries = false;
      boolean hasTags = false;

      if (!super.isEmpty()) {
        flags |= 0x02;
        hasEntries = true;
      }
      if (this.versionTags.size() > 0) {
        flags |= 0x04;
        hasTags = true;
        for (VersionTag tag : this.versionTags) {
          if (tag != null) {
            if (tag instanceof DiskVersionTag) {
              flags |= 0x20;
            }
            break;
          }
        }
      }
      if (this.isRegionVersioned) {
        flags |= 0x08;
      }

      if (DEBUG) {
        if (log == null) {
          log = InternalDistributedSystem.getLoggerI18n();
        }
        log.info(LocalizedStrings.DEBUG, "serializing " + this + " with flags 0x" + Integer.toHexString(flags));
      }

      out.writeByte(flags);
      
      if (hasEntries) {
        InternalDataSerializer.writeUnsignedVL(super.size(), out);
        for (int i=0; i < super.size(); i++) {
          DataSerializer.writeObject(super.get(i), out);
        }
      }
      if (hasTags) {
        InternalDataSerializer.writeUnsignedVL(this.versionTags.size(), out);
        Map<VersionSource, Integer> ids = new HashMap<VersionSource, Integer>(versionTags.size());
        int idCount = 0;
        for (VersionTag tag: this.versionTags) {
          if (tag == null) {
            out.writeByte(FLAG_NULL_TAG);
          } else {
            VersionSource id = tag.getMemberID();
            if (id == null) {
              out.writeByte(FLAG_FULL_TAG);
              InternalDataSerializer.invokeToData(tag, out);
            } else {
              Integer idNumber = ids.get(id);
              if (idNumber == null) {
                out.writeByte(FLAG_TAG_WITH_NEW_ID);
                idNumber = Integer.valueOf(idCount++);
                ids.put(id, idNumber);
                InternalDataSerializer.invokeToData(tag, out);
              } else {
                out.writeByte(FLAG_TAG_WITH_NUMBER_ID);
                tag.toData(out, false);
                tag.setMemberID(id);
                InternalDataSerializer.writeUnsignedVL(idNumber, out);
              }
            }
          }
        }
      }
    }
    
    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      LogWriterI18n log = null;
      if (DEBUG) {
        log = InternalDistributedSystem.getLoggerI18n();
      }
      int flags = in.readByte();
      boolean hasEntries = (flags & 0x02) == 0x02;
      boolean hasTags = (flags & 0x04) == 0x04;
      this.isRegionVersioned = (flags & 0x08) == 0x08;
      boolean persistent= (flags & 0x20) == 0x20;
      
      if (DEBUG) {
        log.info(LocalizedStrings.DEBUG, "deserializing a InitialImageVersionedObjectList with flags 0x" + Integer.toHexString(flags));
      }
      if (hasEntries) {
        int size = (int)InternalDataSerializer.readUnsignedVL(in);
        if (DEBUG) {
          log.info(LocalizedStrings.DEBUG, "reading " + size + " keys");
        }
        for (int i=0; i<size; i++) {
          super.add((Entry) DataSerializer.readObject(in));
        }
      }

      if (hasTags) {
        int size = (int)InternalDataSerializer.readUnsignedVL(in);
        if (DEBUG) {
          log.info(LocalizedStrings.DEBUG, "reading " + size + " version tags");
        }
        this.versionTags = new ArrayList<VersionTag>(size);
        List<VersionSource> ids = new ArrayList<VersionSource>(size);
        for (int i=0; i<size; i++) {
          byte entryType = in.readByte();
          switch (entryType) {
          case FLAG_NULL_TAG:
            this.versionTags.add(null);
            break;
          case FLAG_FULL_TAG:
            this.versionTags.add(VersionTag.create(persistent, in));
            break;
          case FLAG_TAG_WITH_NEW_ID:
            VersionTag tag = VersionTag.create(persistent, in);
            ids.add(tag.getMemberID());
            this.versionTags.add(tag);
            break;
          case FLAG_TAG_WITH_NUMBER_ID:
            tag = VersionTag.create(persistent, in);
            int idNumber = (int)InternalDataSerializer.readUnsignedVL(in);
            tag.setMemberID(ids.get(idNumber));
            this.versionTags.add(tag);
            break;
          }
        }
      } else {
        this.versionTags = new ArrayList<VersionTag>();
      }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      toData(out);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException,
        ClassNotFoundException {
      fromData(in);
    }

    @Override
    public Version[] getSerializationVersions() {
      return null;
    }    
  }

  /** 
   * EventStateMessage transmits the cache's memberId:threadId sequence# 
   * information so that a cache receiving an initial image will know what 
   * events that image represents. 
   *  
   * @author bruce 
   */ 
  public static class RegionStateMessage extends ReplyMessage { 
     
    // event state is processed in-line to ensure it is applied before 
    // the initial image state is received 
    @Override   
    public boolean getInlineProcess() { 
      return true; 
    } 
 
    Map eventState;
    private boolean isHARegion;
    RegionVersionVector versionVector; 
     
    public RegionStateMessage() { 
    } 
 
    private RegionStateMessage(InternalDistributedMember mbr, int processorId, Map eventState, boolean isHARegion) { 
      setRecipient(mbr); 
      setProcessorId(processorId); 
      this.eventState = eventState;
      this.isHARegion = isHARegion;
    } 
     
    private RegionStateMessage(InternalDistributedMember mbr, int processorId, RegionVersionVector rvv, boolean isHARegion) { 
      setRecipient(mbr); 
      setProcessorId(processorId); 
      this.versionVector = rvv;
      this.isHARegion = isHARegion;
    } 
     
    public static void send(DM dm, InternalDistributedMember dest, int processorId, 
        Map<? extends DataSerializable, ? extends DataSerializable> eventState, boolean isHARegion) { 
      RegionStateMessage msg = new RegionStateMessage(dest, processorId, eventState, isHARegion); 
      dm.putOutgoing(msg); 
    } 
     
    public static void send(DM dm, InternalDistributedMember dest, int processorId, 
        RegionVersionVector rvv, boolean isHARegion) { 
      RegionStateMessage msg = new RegionStateMessage(dest, processorId, rvv, isHARegion); 
      dm.putOutgoing(msg); 
    } 
     
    @Override   
    public void toData(DataOutput dop) throws IOException { 
      super.toData(dop);
      dop.writeBoolean(isHARegion);
      if (eventState != null) {
        dop.writeBoolean(true);
        EventStateHelper.toData(dop, eventState, isHARegion);
      } else {
        dop.writeBoolean(false);
      }
      if (versionVector != null) {
        dop.writeBoolean(true);
        dop.writeBoolean(versionVector instanceof DiskRegionVersionVector);
        InternalDataSerializer.invokeToData(versionVector, dop);
      } else {
        dop.writeBoolean(false);
      }
    } 
     

    @Override   
    public String toString() { 
      String descr = super.toString(); 
      if (eventState != null) { 
        descr += "; eventCount=" + eventState.size(); 
      }
      if (versionVector != null) {
        descr += "; versionVector=" + (RegionVersionVector.DEBUG? versionVector.fullToString() : versionVector);
      }
      return descr; 
    } 
     
    @Override   
    public void fromData(DataInput dip) throws IOException, ClassNotFoundException { 
      super.fromData(dip);
      isHARegion = dip.readBoolean();
      boolean has = dip.readBoolean();
      if (has) {
        eventState = EventStateHelper.fromData(dip, isHARegion);
      }
      has = dip.readBoolean();
      if (has) {
        boolean persistent = dip.readBoolean();
        versionVector = RegionVersionVector.create(persistent, dip);
      }
    } 
     
    /* (non-Javadoc) 
     * @see com.gemstone.gemfire.internal.DataSerializableFixedID#getDSFID() 
     */ 
    @Override   
    public int getDSFID() { 
      return REGION_STATE_MESSAGE; 
    } 
  }
  
  /** 
   * This Message is sent as response to RequestFilterInfo. 
   * The filters registered by the client owning the HARegion is sent 
   * as part of this message.
   */ 
  public static class FilterInfoMessage extends ReplyMessage { 
    
    private LocalRegion haRegion;
    
    private Map emptyRegionMap;

    static class InterestMaps {
      Map<String, String> allKeys;
    
      Map<String, String> allKeysInv;
    
      Map<String, Set> keysOfInterest;
    
      Map<String, Set> keysOfInterestInv;

      Map<String, Set> patternsOfInterest;
    
      Map<String, Set> patternsOfInterestInv;

      Map<String, Set> filtersOfInterest;

      Map<String, Set> filtersOfInterestInv;
    }

    private final InterestMaps interestMaps[] = new InterestMaps[] {
        new InterestMaps(), new InterestMaps() };

    /** index values for interestMaps[] */
    
    static final int NON_DURABLE = 0;
    static final int DURABLE = 1;
    
    private Map<String, CqQueryImpl> cqs;
        
    
    @Override   
    public boolean getInlineProcess() { 
      return false; 
    } 
    
    public FilterInfoMessage() { 
    } 
 
    private FilterInfoMessage(InternalDistributedMember mbr, int processorId, LocalRegion haRegion) { 
      setRecipient(mbr); 
      setProcessorId(processorId);
      this.haRegion = haRegion;
    } 
 
    /**
     * Collects all the filters registered by this client on regions.
     */
    public void fillInFilterInfo() {
      LocalRegion haReg = this.haRegion; 
      if (haReg == null || haReg.getName() == null) {
        throw new ReplyException("HARegion for the proxy is Null.");
      }
      LogWriterI18n logger = haReg.getLogWriterI18n();

      CacheClientNotifier ccn = CacheClientNotifier.getInstance();
      if (ccn == null || ccn.getHaContainer() == null) {
        if (logger.infoEnabled()) {
          // TODO add localizedString
          logger.info(LocalizedStrings.DEBUG, "HA Container not found during HA Region GII for " + haReg);
        }
        return;
      }

      CacheClientProxy clientProxy = null;
      ClientProxyMembershipID clientID = ((HAContainerWrapper)ccn.getHaContainer()).getProxyID(haReg.getName());
      if (clientID == null) {
        throw new ReplyException("Client proxy ID not found for queue " + haReg.getName());
      }
      clientProxy = ccn.getClientProxy(clientID);
      if (clientProxy == null) {
        throw new ReplyException("Client proxy not found for queue " + haReg.getName());
      }
      
      if (logger.fineEnabled()) {
        logger.fine("Gathering interest information for " + clientProxy);
      }

      this.emptyRegionMap = clientProxy.getRegionsWithEmptyDataPolicy();
      Set<String> regions = clientProxy.getInterestRegisteredRegions();
      
      // Get Filter Info from all regions.
      for (String rName : regions) {
        LocalRegion r = (LocalRegion)haReg.getCache().getRegion(rName);
        if (r == null) {
          continue;
        }
        if (logger.fineEnabled()) {
          logger.fine("Finding interest on region :" + 
              r.getName() + " for Client(ID) :" + clientID);
        }
        FilterProfile pf = r.getFilterProfile();
        
        getInterestMaps(pf, rName, NON_DURABLE, clientID);
        
        if (clientID.isDurable()) {
          getInterestMaps(pf, rName, DURABLE, clientID.getDurableId());
        }

        
      }
      
      // COllect CQ info.
      CqService cqService = CqService.getRunningCqService(); // fix for bug 43139
      if (cqService != null) {
        try {
          List<CqQueryImpl> cqsList = cqService.getAllClientCqs(clientID);
          if (!cqsList.isEmpty()) {
            this.cqs = new HashMap<String, CqQueryImpl>();
            for(CqQueryImpl cq : cqsList) {
              this.cqs.put(cq.getName(), cq);
            }
          }
        } catch (Exception ex) {
          if (logger.fineEnabled()) {
            logger.fine(this + "Failed to get CQ info. " + ex.getMessage());
          }
        }
      }
      if (logger.fineEnabled()) {
        logger.fine("Number of filters filled : " + this.toString());
      }
    }
    
    private void getInterestMaps(FilterProfile pf, String rName, int mapIndex, Object interestID) {
      try {
        // Check if interested in all keys.          
        boolean all = pf.isInterestedInAllKeys(interestID);
        if (all) {
          if (this.interestMaps[mapIndex].allKeys == null) {
            this.interestMaps[mapIndex].allKeys = new HashMap<String, String>();
          }
          this.interestMaps[mapIndex].allKeys.put(rName, ".*");
        }
        
        // Check if interested in all keys, for which updates are sent as invalidates.
        all = pf.isInterestedInAllKeysInv(interestID);
        if (all) {
          if (this.interestMaps[mapIndex].allKeysInv == null) {
            this.interestMaps[mapIndex].allKeysInv = new HashMap<String, String>();
          }
          this.interestMaps[mapIndex].allKeysInv.put(rName, ".*");
        }
        
        // Collect interest of type keys.
        Set keys = pf.getKeysOfInterest(interestID);
        if (keys != null) {
          if (this.interestMaps[mapIndex].keysOfInterest == null) {
            this.interestMaps[mapIndex].keysOfInterest = new HashMap<String, Set>();
          }
          this.interestMaps[mapIndex].keysOfInterest.put(rName, keys);
        }

        // Collect interest of type keys, for which updates are sent as invalidates.
        keys = pf.getKeysOfInterestInv(interestID);
        if (keys != null) {
          if (this.interestMaps[mapIndex].keysOfInterestInv == null) {
            this.interestMaps[mapIndex].keysOfInterestInv = new HashMap<String, Set>();
          }
          this.interestMaps[mapIndex].keysOfInterestInv.put(rName, keys);
        }

        // Collect interest of type expression.
        keys = pf.getPatternsOfInterest(interestID);
        if (keys != null) {
          if (this.interestMaps[mapIndex].patternsOfInterest == null) {
            this.interestMaps[mapIndex].patternsOfInterest = new HashMap<String, Set>();
          }
          this.interestMaps[mapIndex].patternsOfInterest.put(rName, keys);
        }

        // Collect interest of type expression, for which updates are sent as invalidates.
        keys = pf.getPatternsOfInterestInv(interestID);
        if (keys != null) {
          if (this.interestMaps[mapIndex].patternsOfInterestInv == null) {
            this.interestMaps[mapIndex].patternsOfInterestInv = new HashMap<String, Set>();
          }
          this.interestMaps[mapIndex].patternsOfInterestInv.put(rName, keys);
        }

        // Collect interest of type filter.
        keys = pf.getFiltersOfInterest(interestID);
        if (keys != null) {
          if (this.interestMaps[mapIndex].filtersOfInterest == null) {
            this.interestMaps[mapIndex].filtersOfInterest = new HashMap<String, Set>();
          }
          this.interestMaps[mapIndex].filtersOfInterest.put(rName, keys);
        }

        // Collect interest of type filter, for which updates are sent as invalidates.
        keys = pf.getFiltersOfInterestInv(interestID);
        if (keys != null) {
          if (this.interestMaps[mapIndex].filtersOfInterestInv == null) {
            this.interestMaps[mapIndex].filtersOfInterestInv = new HashMap<String, Set>();
          }
          this.interestMaps[mapIndex].filtersOfInterestInv.put(rName, keys);
        }
      } catch (Exception ex) {
        LogWriterI18n log = pf.getLogWriterI18n();
        if (log != null && log.fineEnabled()) {
          log.fine(this + "Failed to get Register interest info " +
              " for region :" + rName);
        }
      }
    }
    
    /**
     * Registers the filters associated with this client on current cache region.
     * @param region
     */
    public void registerFilters(LocalRegion region) {
      CacheClientNotifier ccn = CacheClientNotifier.getInstance();
      CacheClientProxy proxy;
      try {
        proxy = ((HAContainerWrapper)ccn.getHaContainer()).getProxy(
            region.getName());      
      } catch (Exception ex) {
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Unable to obtain the client proxy. " +
              " Failed to register Filters during HARegion GII. "
              + ex.getMessage() + " Region :" + region.getName());
        }
        return;
      }
      
      if (proxy == null) {
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Found null client proxy. " +
            " Failed to register Filters during HARegion GII. "
            + " Region :" + region.getName());
        }
        return;
      }
      
      registerFilters(region, proxy, false);
      if (proxy.getProxyID().isDurable()) {
        registerFilters(region, proxy, true);
      }

      // Register CQs.
      if (this.cqs != null && !this.cqs.isEmpty()) {
        try {
          CqService cqService = ((DefaultQueryService)(region.getCache().getQueryService()))
          .getCqService();

          for(Map.Entry<String, CqQueryImpl> e : this.cqs.entrySet()){
            CqQueryImpl cq = e.getValue();
            try {
              // Passing regionDataPolicy as -1, the actual value is
              // obtained in executeCQ once the CQs base region name is
              // found. 
              cqService.executeCq(e.getKey(), cq.getQueryString(), 
                  ((CqStateImpl)cq.getState()).getState(), proxy.getProxyID(), 
                  ccn, cq.isDurable(), true, -1, this.emptyRegionMap);
            } catch (Exception ex) {
              if (region.getCache().getLogger().infoEnabled()){
                region.getCache().getLogger().info("Failed to register CQ during HARegion GII . " +
                    " CQ: " + e.getKey() + " " + ex.getMessage());
              }
            }
          }
        } catch (Exception ex){
          if (region.getCache().getLogger().infoEnabled()){
            region.getCache().getLogger().info("Failed to get CqService for CQ registration during HARegion GII. "
                + ex.getMessage());
          }
        }
      }
    }
    
    private void registerFilters(LocalRegion region, CacheClientProxy proxy, boolean durable) {
      CacheClientNotifier ccn = CacheClientNotifier.getInstance();
      Set<String> regionsWithInterest = new HashSet<String>();
      
      int mapIndex = durable? DURABLE : NON_DURABLE;
      
      // Register interest for all keys.
      try {
        registerInterestKeys(this.interestMaps[mapIndex].allKeys, true, region, ccn, proxy, durable,
            false, InterestType.REGULAR_EXPRESSION, regionsWithInterest);
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type keys during HARegion GII.",
              ex);
        }
      }
      
      // Register interest for all keys, for which updates are sent as invalidates.
      try {
        registerInterestKeys(this.interestMaps[mapIndex].allKeysInv, true, region, ccn, proxy, durable,
            false, InterestType.REGULAR_EXPRESSION, regionsWithInterest);
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type keys during HARegion GII.",
              ex);
        }
      }
      
      // Register interest of type keys.
      try {
        registerInterestKeys(this.interestMaps[mapIndex].keysOfInterest, false, region, ccn, proxy, durable,
            false, InterestType.KEY, regionsWithInterest);
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type keys during HARegion GII"
              ,ex);
        }
      }

      // Register interest of type keys, for which updates are sent as invalidates.
      try {
        registerInterestKeys(this.interestMaps[mapIndex].keysOfInterestInv, false, region, ccn, proxy, durable,
            true, InterestType.KEY, regionsWithInterest);      
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type keys for invalidates during HARegion GII."
              ,ex);
        }
      }

      // Register interest of type expression.
      try {
        registerInterestKeys(this.interestMaps[mapIndex].patternsOfInterest, false, region, ccn, proxy, durable,
            false, InterestType.REGULAR_EXPRESSION, regionsWithInterest);
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type expression during HARegion GII." 
              ,ex);
        }
      }

      // Register interest of type expression, for which updates are sent as invalidates.
      try {
        registerInterestKeys(this.interestMaps[mapIndex].patternsOfInterestInv, false, region, ccn, proxy, durable,
            true, InterestType.REGULAR_EXPRESSION, regionsWithInterest);
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type expression for invalidates during HARegion GII."
              ,ex);
        }
      }

      // Register interest of type expression.
      try {
        registerInterestKeys(this.interestMaps[mapIndex].filtersOfInterest, false, region, ccn, proxy, durable,
            false, InterestType.FILTER_CLASS, regionsWithInterest);
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type filter during HARegion GII."
              ,ex);
        }
      }

      // Register interest of type expression, for which updates are sent as invalidates.
      try {      
        registerInterestKeys(this.interestMaps[mapIndex].filtersOfInterestInv, false, region, ccn, proxy, durable,
            true, InterestType.FILTER_CLASS, regionsWithInterest);
      } catch (Exception ex){
        if (region.getCache().getLogger().infoEnabled()){
          region.getCache().getLogger().info("Failed to register interest of type filter for invalidates during HARegion GII."
              ,ex);
        }
      }
      
      /**
       * now that interest is in place we need to flush operations to the
       * image provider
       */
      for (String regionName: regionsWithInterest) {
        proxy.flushForInterestRegistration(regionName, getSender());
      }
    }

    /**
     * Helper method to register the filters.
     */
    private void registerInterestKeys(Map regionKeys, boolean allKey, 
        LocalRegion region, CacheClientNotifier ccn, CacheClientProxy proxy,
        boolean isDurable,
        boolean updatesAsInvalidates, int interestType, Set<String>regionsWithInterest) throws IOException {
      
     
      if (regionKeys != null) {
        Iterator iter = regionKeys.entrySet().iterator();
        while (iter.hasNext()) {
          Map.Entry e = (Map.Entry)iter.next();
          String regionName = (String)e.getKey();     
          if (region.getCache().getRegion(regionName) == null) {
            if (region.getLogWriterI18n().fineEnabled()) {
              region.getLogWriterI18n().fine("Unable to register interests. " + 
                " Region not found :" + regionName);
            }
          } else {
            boolean manageEmptyRegions = false;
            if (this.emptyRegionMap != null) {
              manageEmptyRegions = this.emptyRegionMap.containsKey(regionName);
            }
            regionsWithInterest.add(regionName);
            if (allKey) {
              ccn.registerClientInterest(regionName, e.getValue(), 
                proxy.getProxyID(), interestType, isDurable, updatesAsInvalidates, 
                manageEmptyRegions, 0, false);       
            } else if (InterestType.REGULAR_EXPRESSION == interestType) {
              for (Iterator i = ((Set) e.getValue()).iterator(); i.hasNext();) {
                ccn.registerClientInterest(regionName, (String) i.next(), 
                    proxy.getProxyID(), interestType, isDurable, updatesAsInvalidates, 
                    manageEmptyRegions, 0, false);       
              }
            } else {
              ccn.registerClientInterest(regionName,  new ArrayList((Set)e.getValue()), 
                proxy.getProxyID(), isDurable, updatesAsInvalidates, 
                manageEmptyRegions, interestType, false);
            }
          }
        }
      }
    }

    public static void send(DM dm, InternalDistributedMember dest, int processorId, 
        LocalRegion rgn, ReplyException ex) { 
      FilterInfoMessage msg = new FilterInfoMessage(dest, processorId, rgn);
      if (ex != null) {
        msg.setException(ex);
      } else {
        try {
          msg.fillInFilterInfo();
        } catch (ReplyException e) {
          msg.setException(e);
        }
      }
      dm.putOutgoing(msg); 
    } 
     
    @Override   
    public String toString() { 
      String descr = super.toString(); 
      descr +=
        "; NON_DURABLE allKeys=" + (this.interestMaps[NON_DURABLE].allKeys != null?this.interestMaps[NON_DURABLE].allKeys.size():0) + 
        "; allKeysInv=" + (this.interestMaps[NON_DURABLE].allKeysInv != null?this.interestMaps[NON_DURABLE].allKeysInv.size():0) +
        "; keysOfInterest=" + (this.interestMaps[NON_DURABLE].keysOfInterest != null?this.interestMaps[NON_DURABLE].keysOfInterest.size():0) + 
        "; keysOfInterestInv=" + (this.interestMaps[NON_DURABLE].keysOfInterestInv != null?this.interestMaps[NON_DURABLE].keysOfInterestInv.size():0) + 
        "; patternsOfInterest=" + (this.interestMaps[NON_DURABLE].patternsOfInterest != null?this.interestMaps[NON_DURABLE].patternsOfInterest.size():0) + 
        "; patternsOfInterestInv=" + (this.interestMaps[NON_DURABLE].patternsOfInterestInv != null?this.interestMaps[NON_DURABLE].patternsOfInterestInv.size():0) + 
        "; filtersOfInterest=" + (this.interestMaps[NON_DURABLE].filtersOfInterest != null?this.interestMaps[NON_DURABLE].filtersOfInterest.size():0) + 
        "; filtersOfInterestInv=" + (this.interestMaps[NON_DURABLE].filtersOfInterestInv != null?this.interestMaps[NON_DURABLE].filtersOfInterestInv.size():0);
      descr +=
        "; DURABLE allKeys=" + (this.interestMaps[DURABLE].allKeys != null?this.interestMaps[DURABLE].allKeys.size():0) + 
        "; allKeysInv=" + (this.interestMaps[DURABLE].allKeysInv != null?this.interestMaps[DURABLE].allKeysInv.size():0) +
        "; keysOfInterest=" + (this.interestMaps[DURABLE].keysOfInterest != null?this.interestMaps[DURABLE].keysOfInterest.size():0) + 
        "; keysOfInterestInv=" + (this.interestMaps[DURABLE].keysOfInterestInv != null?this.interestMaps[DURABLE].keysOfInterestInv.size():0) + 
        "; patternsOfInterest=" + (this.interestMaps[DURABLE].patternsOfInterest != null?this.interestMaps[DURABLE].patternsOfInterest.size():0) + 
        "; patternsOfInterestInv=" + (this.interestMaps[DURABLE].patternsOfInterestInv != null?this.interestMaps[DURABLE].patternsOfInterestInv.size():0) + 
        "; filtersOfInterest=" + (this.interestMaps[DURABLE].filtersOfInterest != null?this.interestMaps[DURABLE].filtersOfInterest.size():0) + 
        "; filtersOfInterestInv=" + (this.interestMaps[DURABLE].filtersOfInterestInv != null?this.interestMaps[DURABLE].filtersOfInterestInv.size():0);
      descr +=
        "; cqs=" + (this.cqs != null?this.cqs.size():0); 
      return descr; 
    } 

    public boolean isEmpty() {
      if (this.interestMaps[NON_DURABLE].keysOfInterest != null || this.interestMaps[NON_DURABLE].keysOfInterestInv != null || 
          this.interestMaps[NON_DURABLE].patternsOfInterest != null || this.interestMaps[NON_DURABLE].patternsOfInterestInv != null || 
          this.interestMaps[NON_DURABLE].filtersOfInterest != null || this.interestMaps[NON_DURABLE].filtersOfInterestInv != null || 
          this.interestMaps[DURABLE].patternsOfInterest != null || this.interestMaps[DURABLE].patternsOfInterestInv != null || 
          this.interestMaps[DURABLE].filtersOfInterest != null || this.interestMaps[DURABLE].filtersOfInterestInv != null ||
          this.cqs != null) {
        return false;
      } 
      return true; 
    } 

    @Override   
    public void toData(DataOutput dop) throws IOException { 
      super.toData(dop); 
      //DataSerializer.writeString(this.haRegion.getName(), dop);
      DataSerializer.writeHashMap((HashMap)this.emptyRegionMap, dop);
      // Write interest info.
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].allKeys, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].allKeysInv, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].keysOfInterest, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].keysOfInterestInv, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].patternsOfInterest, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].patternsOfInterestInv, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].filtersOfInterest, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[NON_DURABLE].filtersOfInterestInv, dop);

      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].allKeys, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].allKeysInv, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].keysOfInterest, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].keysOfInterestInv, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].patternsOfInterest, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].patternsOfInterestInv, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].filtersOfInterest, dop);
      DataSerializer.writeHashMap((HashMap)this.interestMaps[DURABLE].filtersOfInterestInv, dop);
      
      // Write CQ info.
      DataSerializer.writeHashMap((HashMap)this.cqs, dop);
    } 
          
    @Override   
    public void fromData(DataInput dip) throws IOException, ClassNotFoundException { 
      super.fromData(dip); 
      //String regionName = DataSerializer.readString(dip);
      this.emptyRegionMap = DataSerializer.readHashMap(dip);
      // Read interest info.
      this.interestMaps[NON_DURABLE].allKeys = DataSerializer.readHashMap(dip);
      this.interestMaps[NON_DURABLE].allKeysInv = DataSerializer.readHashMap(dip);
      this.interestMaps[NON_DURABLE].keysOfInterest = DataSerializer.readHashMap(dip);
      this.interestMaps[NON_DURABLE].keysOfInterestInv = DataSerializer.readHashMap(dip); 
      this.interestMaps[NON_DURABLE].patternsOfInterest = DataSerializer.readHashMap(dip);
      this.interestMaps[NON_DURABLE].patternsOfInterestInv = DataSerializer.readHashMap(dip);
      this.interestMaps[NON_DURABLE].filtersOfInterest = DataSerializer.readHashMap(dip);
      this.interestMaps[NON_DURABLE].filtersOfInterestInv = DataSerializer.readHashMap(dip);  

      this.interestMaps[DURABLE].allKeys = DataSerializer.readHashMap(dip);
      this.interestMaps[DURABLE].allKeysInv = DataSerializer.readHashMap(dip);
      this.interestMaps[DURABLE].keysOfInterest = DataSerializer.readHashMap(dip);
      this.interestMaps[DURABLE].keysOfInterestInv = DataSerializer.readHashMap(dip); 
      this.interestMaps[DURABLE].patternsOfInterest = DataSerializer.readHashMap(dip);
      this.interestMaps[DURABLE].patternsOfInterestInv = DataSerializer.readHashMap(dip);
      this.interestMaps[DURABLE].filtersOfInterest = DataSerializer.readHashMap(dip);
      this.interestMaps[DURABLE].filtersOfInterestInv = DataSerializer.readHashMap(dip);  

      // read CQ info.
      this.cqs = DataSerializer.readHashMap(dip);
    } 
          
    /* (non-Javadoc) 
     * @see com.gemstone.gemfire.internal.DataSerializableFixedID#getDSFID() 
     */ 
    @Override   
    public int getDSFID() { 
      return FILTER_INFO_MESSAGE; 
    } 
  }

  public static abstract class GIITestHook implements Runnable {
    private GIITestHookType type;
    private String region_name;
    public boolean isRunning;
    public GIITestHook(GIITestHookType type, String region_name) {
      this.type = type;
      this.region_name = region_name;
      this.isRunning = false;
    }
    
    public GIITestHookType getType() {
      return this.type;
    }
    
    public String getRegionName() {
      return this.region_name;
    }

    public String toString() {
      return type+":"+region_name+":"+isRunning;
    }
    
    public abstract void reset();
    
    public abstract void run();
    
  }

  public static final boolean TRACE_GII = Boolean.getBoolean("gemfire.GetInitialImage.TRACE_GII");
  public static final boolean TRACE_GII_FINER = TRACE_GII || Boolean.getBoolean("gemfire.GetInitialImage.TRACE_GII_FINER");
  public static boolean FORCE_FULL_GII = Boolean.getBoolean("gemfire.GetInitialImage.FORCE_FULL_GII");
  
  // test hook to fail gii at the receiving end
  public static interface TEST_GII_EXCEPTION {
    public void throwException();
  }

  public static volatile TEST_GII_EXCEPTION giiExceptionSimulate = null;
  
  
  // test hooks should be applied and waited in strict order as following
  
  // internal test hooks at requester for sending request
  private static GIITestHook internalBeforeGetInitialImage;
  private static GIITestHook internalBeforeRequestRVV;
  private static GIITestHook internalAfterRequestRVV;
  private static GIITestHook internalAfterCalculatedUnfinishedOps;
  private static GIITestHook internalBeforeSavedReceivedRVV;
  private static GIITestHook internalAfterSavedReceivedRVV;
  private static GIITestHook internalAfterSentRequestImage;
  
  // internal test hooks at provider 
  private static GIITestHook internalAfterReceivedRequestImage;
  static GIITestHook internalDuringPackingImage;
  private static GIITestHook internalAfterSentImageReply;

  // internal test hooks at requester for processing ImageReply
  private static GIITestHook internalAfterReceivedImageReply;
  private static GIITestHook internalDuringApplyDelta;
  private static GIITestHook internalBeforeCleanExpiredTombstones;
  private static GIITestHook internalAfterSavedRVVEnd;
  private static GIITestHook internalAfterGIILock;
  private static GIITestHook internalNoGII;

  /**
   * For test purpose to be used in ImageProcessor
   * 
   */
  public static volatile CountDownLatch imageProcessorTestLatch;

  public static volatile boolean imageProcessorTestLatchWaiting;

  public static volatile String imageProcessorTEST_REGION_NAME;


  public enum GIITestHookType {
    BeforeGetInitialImage,
    BeforeRequestRVV,
    AfterRequestRVV,
    AfterCalculatedUnfinishedOps,
    BeforeSavedReceivedRVV,
    AfterSavedReceivedRVV,
    AfterSentRequestImage,
    
    AfterReceivedRequestImage,
    DuringPackingImage,
    AfterSentImageReply,
    
    AfterReceivedImageReply,
    DuringApplyDelta,
    BeforeCleanExpiredTombstones,
    AfterSavedRVVEnd,
    AfterGIILock,
    NoGIITrigger
  }
  
  public static GIITestHook getGIITestHookForCheckingPurpose(final GIITestHookType type) {
    switch (type) {
    case BeforeGetInitialImage: // 0
      return internalBeforeGetInitialImage;
    case BeforeRequestRVV: // 1
      return internalBeforeRequestRVV;
    case AfterRequestRVV: // 2
      return internalAfterRequestRVV;
    case AfterCalculatedUnfinishedOps: // 3
      return internalAfterCalculatedUnfinishedOps;
    case BeforeSavedReceivedRVV: // 4
      return internalBeforeSavedReceivedRVV;
    case AfterSavedReceivedRVV: // 5
      return internalAfterSavedReceivedRVV;
    case AfterSentRequestImage: // 6
      return internalAfterSentRequestImage;

    case AfterReceivedRequestImage: // 7
      return internalAfterReceivedRequestImage;
    case DuringPackingImage: // 8
      return internalDuringPackingImage;
    case AfterSentImageReply: // 9
      return internalAfterSentImageReply;

    case AfterReceivedImageReply: // 10
      return internalAfterReceivedImageReply;
    case DuringApplyDelta: // 11
      return internalDuringApplyDelta;
    case BeforeCleanExpiredTombstones: // 12
      return internalBeforeCleanExpiredTombstones;
    case AfterSavedRVVEnd: // 13
      return internalAfterSavedRVVEnd;
    case AfterGIILock: // 14
      return internalAfterGIILock;
      case NoGIITrigger: // 14
      return internalNoGII;
    default:
      throw new RuntimeException("Illegal test hook type");
    }
  }

  public static void setGIITestHook(final GIITestHook callback) {
    switch (callback.type) {
    case BeforeGetInitialImage: // 0
      assert internalBeforeGetInitialImage == null;
      internalBeforeGetInitialImage = callback;
      break;
    case BeforeRequestRVV: // 1
      assert internalBeforeRequestRVV == null;
      internalBeforeRequestRVV = callback;
      break;
    case AfterRequestRVV: // 2
      assert internalAfterRequestRVV == null;
      internalAfterRequestRVV = callback;
      break;
    case AfterCalculatedUnfinishedOps: // 3
      assert internalAfterCalculatedUnfinishedOps == null;
      internalAfterCalculatedUnfinishedOps = callback;
      break;
    case BeforeSavedReceivedRVV: // 4
      assert internalBeforeSavedReceivedRVV == null;
      internalBeforeSavedReceivedRVV = callback;
      break;
    case AfterSavedReceivedRVV: // 5
      internalAfterSavedReceivedRVV = callback;
      break;
    case AfterSentRequestImage: // 6
      internalAfterSentRequestImage = callback;
      break;

    case AfterReceivedRequestImage: // 7
      assert internalAfterReceivedRequestImage == null;
      internalAfterReceivedRequestImage = callback;
      break;
    case DuringPackingImage: // 8
      assert internalDuringPackingImage == null;
      internalDuringPackingImage = callback;
      break;
    case AfterSentImageReply: // 9
      assert internalAfterSentImageReply == null;
      internalAfterSentImageReply = callback;
      break;

    case AfterReceivedImageReply: // 10
      assert internalAfterReceivedImageReply == null;
      internalAfterReceivedImageReply = callback;
      break;
    case DuringApplyDelta: // 11
      assert internalDuringApplyDelta == null;
      internalDuringApplyDelta = callback;
      break;
    case BeforeCleanExpiredTombstones: // 12
      assert internalBeforeCleanExpiredTombstones == null;
      internalBeforeCleanExpiredTombstones = callback;
      break;
    case AfterSavedRVVEnd: // 13
      assert internalAfterSavedRVVEnd == null;
      internalAfterSavedRVVEnd = callback;
      break;
    case AfterGIILock: // 14
      assert internalAfterGIILock == null;
      internalAfterGIILock = callback;
      break;
      case NoGIITrigger: // 15
      assert internalNoGII == null;
        internalNoGII = callback;
      break;
    default:
      throw new RuntimeException("Illegal test hook type");
    }
  }

  public static boolean anyTestHookInstalled() {
    return (internalBeforeGetInitialImage != null ||
    internalBeforeRequestRVV != null ||
    internalAfterRequestRVV != null ||
    internalAfterCalculatedUnfinishedOps != null ||
    internalBeforeSavedReceivedRVV != null ||
    internalAfterSavedReceivedRVV != null ||
    internalAfterSentRequestImage != null ||
    internalAfterReceivedRequestImage != null ||
    internalDuringPackingImage != null ||
    internalAfterSentImageReply != null ||
    internalAfterReceivedImageReply != null ||
    internalDuringApplyDelta != null ||
    internalBeforeCleanExpiredTombstones != null ||
    internalAfterSavedRVVEnd != null ||
    internalAfterGIILock != null ||
    internalNoGII != null);
  }

  public static void resetGIITestHook(final GIITestHookType type, final boolean setNull) {
    switch (type) {
    case BeforeGetInitialImage: // 0
      if (internalBeforeGetInitialImage != null) {
        internalBeforeGetInitialImage.reset();
        if (setNull) {
          internalBeforeGetInitialImage = null;
        }
      }
      break;
    case BeforeRequestRVV: // 1
      if (internalBeforeRequestRVV != null) {
        internalBeforeRequestRVV.reset();
        if (setNull) {
          internalBeforeRequestRVV = null;
        }
      }
      break;
    case AfterRequestRVV: // 2
      if (internalAfterRequestRVV != null) {
        internalAfterRequestRVV.reset();
        if (setNull) {
          internalAfterRequestRVV = null;
        }
      }
      break;
    case AfterCalculatedUnfinishedOps: // 3
      if (internalAfterCalculatedUnfinishedOps != null) {
        internalAfterCalculatedUnfinishedOps.reset();
        if (setNull) {
          internalAfterCalculatedUnfinishedOps = null;
        }
      }
      break;
    case BeforeSavedReceivedRVV: // 4
      if (internalBeforeSavedReceivedRVV != null) {
        internalBeforeSavedReceivedRVV.reset();
        if (setNull) {
          internalBeforeSavedReceivedRVV = null;
        }
      }
      break;
    case AfterSavedReceivedRVV: // 5
      if (internalAfterSavedReceivedRVV != null) {
        internalAfterSavedReceivedRVV.reset();
        if (setNull) {
          internalAfterSavedReceivedRVV = null;
        }
      }
      break;
    case AfterSentRequestImage: // 6
      if (internalAfterSentRequestImage != null) {
        internalAfterSentRequestImage.reset();
        if (setNull) {
          internalAfterSentRequestImage = null;
        }
      }
      break;

    case AfterReceivedRequestImage: // 7
      if (internalAfterReceivedRequestImage != null) {
        internalAfterReceivedRequestImage.reset();
        if (setNull) {
          internalAfterReceivedRequestImage = null;
        }
      }
      break;
    case DuringPackingImage: // 8
      if (internalDuringPackingImage != null) {
        internalDuringPackingImage.reset();
        if (setNull) {
          internalDuringPackingImage = null;
        }
      }
      break;
    case AfterSentImageReply: // 9
      if (internalAfterSentImageReply != null) {
        internalAfterSentImageReply.reset();
        if (setNull) {
          internalAfterSentImageReply = null;
        }
      }
      break;

    case AfterReceivedImageReply: // 10
      if (internalAfterReceivedImageReply != null) {
        internalAfterReceivedImageReply.reset();
        if (setNull) {
          internalAfterReceivedImageReply = null;
        }
      }
      break;
    case DuringApplyDelta: // 11
      if (internalDuringApplyDelta != null) {
        internalDuringApplyDelta.reset();
        if (setNull) {
          internalDuringApplyDelta = null;
        }
      }
      break;
    case BeforeCleanExpiredTombstones: // 12
      if (internalBeforeCleanExpiredTombstones != null) {
        internalBeforeCleanExpiredTombstones.reset();
        if (setNull) {
          internalBeforeCleanExpiredTombstones = null;
        }
      }
      break;
    case AfterSavedRVVEnd: // 13
      if (internalAfterSavedRVVEnd != null) {
        internalAfterSavedRVVEnd.reset();
        if (setNull) {
          internalAfterSavedRVVEnd = null;
        }
      }
      break;
    case AfterGIILock: // 14
      if (internalAfterGIILock != null) {
        internalAfterGIILock.reset();
        if (setNull) {
          internalAfterGIILock = null;
        }
      }
      break;
      case NoGIITrigger: // 15
      if (internalNoGII != null) {
        internalNoGII.reset();
        if (setNull) {
          internalNoGII = null;
        }
      }
      break;
    default:
      throw new RuntimeException("Illegal test hook type");
    }
  }

  public static void resetAllGIITestHooks() {
    internalBeforeGetInitialImage = null;
    internalBeforeRequestRVV = null;
    internalAfterRequestRVV = null;
    internalAfterCalculatedUnfinishedOps = null;
    internalBeforeSavedReceivedRVV = null;
    internalAfterSavedReceivedRVV = null;
    internalAfterSentRequestImage = null;

    internalAfterReceivedRequestImage = null;
    internalDuringPackingImage = null;
    internalAfterSentImageReply = null;

    internalAfterReceivedImageReply = null;
    internalDuringApplyDelta = null;
    internalBeforeCleanExpiredTombstones = null;
    internalAfterSavedRVVEnd = null;
    internalAfterGIILock = null;
    internalNoGII = null;
  }

  /**
   * Monitors distributed membership for a given bucket for which GII is in progress
   * and a write lock is successfully taken.
   */
  private static class InitializingBucketMembershipObserver implements MembershipListener {
    final BucketRegion bucketToMonitor;

    final LogWriter logger;
    InternalDistributedMember requestingMember;

    public InitializingBucketMembershipObserver(BucketRegion b, GemFireCacheImpl cache,
                                                InternalDistributedMember member) {
      this.bucketToMonitor = b;
      this.logger = cache.getLogger();
      this.requestingMember = member;
    }

    public void memberJoined(InternalDistributedMember id) {
      if (logger.fineEnabled()) {
        logger.fine("InitializingBucketMembershipObserver for bucket " + this.bucketToMonitor
            + " member joined " + id);
      }
    }

    public void memberSuspect(InternalDistributedMember id,
        InternalDistributedMember whoSuspected) {
    }

    public void memberDeparted(InternalDistributedMember id, boolean crashed) {
      if (logger.fineEnabled()) {
        logger.fine("InitializingBucketMembershipObserver for bucket " + this.bucketToMonitor
            + " member departed " + id);
      }
      // Only release the lock iff requesting member has parted
      if (this.bucketToMonitor.isHosting() && id.equals(requestingMember)) {
        BucketRegion br = bucketToMonitor.getHostedBucketRegion();
        br.releaseSnapshotGIIWriteLock();
      }
    }

    @Override
    public void quorumLost(Set<InternalDistributedMember> failures,
        List<InternalDistributedMember> remaining) {
    }

  }

  public static final class SnapshotBucketLockReleaseMessage
      extends HighPriorityDistributionMessage
      implements MessageWithReply {

    /**
     * Name of the region.
     */
    protected String regionPath;

    /**
     * Id of the {@link InitialImageOperation.ImageProcessor} that will handle the replies
     */
    protected int processorId;

    public SnapshotBucketLockReleaseMessage() {
    }

    public SnapshotBucketLockReleaseMessage(String regionPath, int processorId) {
      this.regionPath = regionPath;
      this.processorId = processorId;
    }


    public static void send(
        InternalDistributedMember members, DM dm, String regionPath) throws ReplyException {
      ReplyProcessor21 processor = new ReplyProcessor21(dm, members);
      SnapshotBucketLockReleaseMessage msg =
          new SnapshotBucketLockReleaseMessage(regionPath, processor.getProcessorId());
      msg.setRecipient(members);
      dm.putOutgoing(msg);
      processor.waitForRepliesUninterruptibly();
    }


    @Override
    protected void process(DistributionManager dm) {
      boolean failed = true;
      ReplyException replyException = null;
      final LogWriterI18n logger = dm.getLoggerI18n();
      try {
        DistributedSystem system = dm.getSystem();

        if (logger.fineEnabled()) {
          logger.fine("SnapshotBucketLockReleaseMessage:" +
              " attempting to release the snapshot GII lock for " + regionPath);
        }
        LocalRegion rgn = LocalRegion.getRegionFromPath(system, regionPath);
        if (rgn instanceof BucketRegion) {
          BucketRegion bucketRegion = (BucketRegion)rgn;
          bucketRegion.releaseSnapshotGIIWriteLock();
        }
        failed = false; // nothing above threw anything
      } catch (RuntimeException e) {
        replyException = new ReplyException(e);
        throw e;
      } catch (Error e) {
        if (SystemFailure.isJVMFailureError(e)) {
          SystemFailure.initiateFailure(e);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          replyException = new ReplyException(e);
          throw e;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above).  However, there is
        SystemFailure.checkFailure();
        replyException = new ReplyException(e);
        throw e;
      } finally {
        if (failed) {
          // above code failed so now ensure reply is sent
          if (logger.fineEnabled()) {
            logger.fine(
                "SnapshotBucketLockReleaseMessage.process failed for <" + this + ">");
          }
          ReplyMessage replyMsg = new ReplyMessage();
          replyMsg.setProcessorId(this.processorId);
          replyMsg.setRecipient(getSender());
          replyMsg.setException(replyException);
          dm.putOutgoing(replyMsg);
        }
      }
    }

    @Override
    public int getDSFID() {
      return SNAPSHOT_GII_UNLOCK_MESSAGE;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      DataSerializer.writeString(this.regionPath, out);
      out.writeInt(this.processorId);
    }

    @Override
    public void fromData(DataInput in)
        throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.regionPath = DataSerializer.readString(in);
      this.processorId = in.readInt();
    }
  }

}
