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
package com.gemstone.gemfire.internal.cache.partitioned;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DirectReplyProcessor;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ReplyException;
import com.gemstone.gemfire.distributed.internal.ReplyMessage;
import com.gemstone.gemfire.distributed.internal.ReplyProcessor21;
import com.gemstone.gemfire.distributed.internal.ReplySender;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.EntryVersionsList;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.PutAllEntryData;
import com.gemstone.gemfire.internal.cache.PutAllPartialResultException.PutAllPartialResult;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.ha.ThreadIdentifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.ConcurrentCacheModificationException;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.snappy.UMMMemoryTracker;
import com.gemstone.gnu.trove.THashMap;

/**
 * A Partitioned Region update message.  Meant to be sent only to
 * a bucket's primary owner.  In addition to updating an entry it is also used to
 * send Partitioned Region event information.
 *
 * @author Gester Zhou
 * @since 6.0
 */
public final class PutAllPRMessage extends PartitionMessageWithDirectReply {

  private PutAllEntryData[] putAllPRData;

  private int putAllPRDataSize = 0;

  private Integer bucketId;

  /** An additional object providing context for the operation, e.g., for BridgeServer notification */
  ClientProxyMembershipID bridgeContext;

  /** true if no callbacks should be invoked */
  private boolean skipCallbacks;

  protected static final short HAS_BRIDGE_CONTEXT = UNRESERVED_FLAGS_START;
  protected static final short SKIP_CALLBACKS = (HAS_BRIDGE_CONTEXT << 1);
  protected static final short FETCH_FROM_HDFS = (SKIP_CALLBACKS << 1);
  //using the left most bit for IS_PUT_DML, the last available bit
  protected static final short IS_PUT_DML = (short) (FETCH_FROM_HDFS << 1);

  private transient InternalDistributedSystem internalDs;

  /** whether direct-acknowledgement is desired */
  private transient boolean directAck = false;

  /**
   * state from operateOnRegion that must be preserved for transmission
   * from the waiting pool
   */
  transient boolean result = false;

  transient VersionedObjectList versions = null;

  private PutAllResponse remoteResponse;

  /** whether this operation should fetch oldValue from HDFS */
  private boolean fetchFromHDFS;
  
  private boolean isPutDML;

  /**
   * Empty constructor to satisfy {@link DataSerializer}requirements
   */
  public PutAllPRMessage() {
  }

  public PutAllPRMessage(int bucketId, int size, boolean notificationOnly,
      boolean posDup, boolean skipCallbacks, final TXStateInterface tx, boolean fetchFromHDFS, boolean isPutDML) {
    super(tx);
    this.bucketId = Integer.valueOf(bucketId);
    putAllPRData = new PutAllEntryData[size];
    this.notificationOnly = notificationOnly;
    this.posDup = posDup;
    this.skipCallbacks = skipCallbacks;
    this.fetchFromHDFS = fetchFromHDFS;
    this.isPutDML = isPutDML; 
  }

  public void addEntry(PutAllEntryData entry) {
    this.putAllPRData[this.putAllPRDataSize++] = entry;
  }

  public void initMessage(PartitionedRegion r, Set recipients,
      boolean notifyOnly, DirectReplyProcessor p) {
    setInternalDs(r.getSystem());
    setDirectAck(false);
    this.resetRecipients();
    if (recipients != null) {
      setRecipients(recipients);
    }
    this.regionId = r.getPRId();
    this.processor = p;
    this.processorId = p==null? 0 : p.getProcessorId();
    if (p != null && this.isSevereAlertCompatible()) {
      p.enableSevereAlertProcessing();
    }
    this.notificationOnly = notifyOnly;
  }

  @Override
  public boolean isSevereAlertCompatible() {
    // allow forced-disconnect processing for all cache op messages
    return true;
  }

  public void setPossibleDuplicate(boolean posDup) {
    this.posDup = posDup;
  }

  // this method made unnecessary by entry versioning in 7.0 but kept here for merging
//  public void saveKeySet(PutAllPartialResult partialKeys) {
//    partialKeys.addKeysAndVersions(this.versions);
//  }

  public final int getSize() {
    return putAllPRDataSize;
  }

  public Collection<Object> getKeys() {
    ArrayList<Object> keys = new ArrayList<Object>(getSize());
    for (int i=0; i<putAllPRData.length; i++) {
      if (putAllPRData[i] != null) {
        keys.add(putAllPRData[i].getKey());
      }
    }
    return keys;
  }

  /**
   * Sends a PartitionedRegion PutAllPRMessage to the recipient
   * @param recipients the members to which the put message is sent
   * @param r  the PartitionedRegion for which the put was performed
   * @return the processor used to await acknowledgement that the update was
   *         sent, or null to indicate that no acknowledgement will be sent
   * @throws ForceReattemptException if the peer is no longer available
   */
  public PartitionResponse send(final Set<DistributedMember> recipients, PartitionedRegion r)
      throws ForceReattemptException {
    //Assert.assertTrue(recipient != null, "PutAllPRMessage NULL recipient");  recipient can be null for event notifications
    //Set recipients = Collections.singleton(recipient);
    PutAllResponseFromRemote p = new PutAllResponseFromRemote(r.getSystem(), recipients,
        getTXState(), this);
    initMessage(r, recipients, false, p);
    final LogWriter logger = r.getCache().getLogger();
    if (logger.fineEnabled()) {
      logger.fine("PutAllPRMessage.send: recipients are "+recipients+", msg is "+this);
    }

    Set failures =r.getDistributionManager().putOutgoing(this);
    if (failures != null && failures.size() > 0) {
      throw new ForceReattemptException("Failed sending <" + this + ">");
    }
    return p;
  }
  
  public void setBridgeContext(ClientProxyMembershipID contx) {
    Assert.assertTrue(contx != null);
    this.bridgeContext = contx;
  }

  public int getDSFID() {
    return PR_PUTALL_MESSAGE;
  }

  @Override
  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.bucketId = Integer.valueOf((int)InternalDataSerializer
        .readSignedVL(in));
    if ((flags & HAS_BRIDGE_CONTEXT) != 0) {
      this.bridgeContext = DataSerializer.readObject(in);
    }
    this.putAllPRDataSize = (int)InternalDataSerializer.readUnsignedVL(in);
    this.putAllPRData = new PutAllEntryData[putAllPRDataSize];
    if (this.putAllPRDataSize > 0) {
      final Version version = InternalDataSerializer
          .getVersionForDataStreamOrNull(in);
      final ByteArrayDataInput bytesIn = new ByteArrayDataInput();
      for (int i = 0; i < this.putAllPRDataSize; i++) {
        this.putAllPRData[i] = new PutAllEntryData(in, null, i, version,
            bytesIn);
      }

      boolean hasTags = in.readBoolean();
      if (hasTags) {
        EntryVersionsList versionTags = EntryVersionsList.create(in);
        for (int i = 0; i < this.putAllPRDataSize; i++) {
          this.putAllPRData[i].versionTag = versionTags.get(i);
        }
      }
    }
    
    // Overriding the putDML read earlier if received from old versioned peer
    final Version version = InternalDataSerializer.getVersionForDataStream(in);
    final boolean isPeerGfxd10 = (Version.GFXD_10.compareTo(version) == 0);
    if (isPeerGfxd10 && !fetchFromHDFS) {
      this.isPutDML = true;
    }
  }

  @Override
  public void toData(DataOutput out) throws IOException {

    super.toData(out);
    if (bucketId == null) {
      InternalDataSerializer.writeSignedVL(-1, out);
    } else {
      InternalDataSerializer.writeSignedVL(bucketId.intValue(), out);
    }
    if (this.bridgeContext != null) {
      DataSerializer.writeObject(this.bridgeContext, out);
    }
    InternalDataSerializer.writeUnsignedVL(this.putAllPRDataSize, out);
    if (this.putAllPRDataSize > 0) {
      EntryVersionsList versionTags = new EntryVersionsList(putAllPRDataSize);

      boolean hasTags = false;
      // get the "keyRequiresRegionContext" flag from first element assuming
      // all key objects to be uniform
      final boolean requiresRegionContext =
        (this.putAllPRData[0].getKey() instanceof KeyWithRegionContext);
      for (int i = 0; i < this.putAllPRDataSize; i++) {
        // If sender's version is >= 7.0.1 then we can send versions list.
        if (!hasTags && putAllPRData[i].versionTag != null) {
          hasTags = true;
        }

        VersionTag<?> tag = putAllPRData[i].versionTag;
        versionTags.add(tag);
        putAllPRData[i].versionTag = null;
        putAllPRData[i].toData(out, requiresRegionContext);
        putAllPRData[i].versionTag = tag;
        // PutAllEntryData's toData did not serialize eventID to save
        // performance for DR, but in PR,
        // we pack it for each entry since we used fake eventID
      }

      out.writeBoolean(hasTags);
      if (hasTags) {
        InternalDataSerializer.invokeToData(versionTags, out);
      }
    }
  }

  @Override
  protected short computeCompressedShort(short s) {
    s = super.computeCompressedShort(s);
    if (this.bridgeContext != null) s |= HAS_BRIDGE_CONTEXT;
    if (this.skipCallbacks) s |= SKIP_CALLBACKS;
    if (this.fetchFromHDFS) s |= FETCH_FROM_HDFS;
    if (this.isPutDML) s |= IS_PUT_DML;
    return s;
  }

  @Override
  protected void setBooleans(short s) {
    super.setBooleans(s);
    this.skipCallbacks = ((s & SKIP_CALLBACKS) != 0);
    this.fetchFromHDFS = ((s & FETCH_FROM_HDFS) != 0);
    this.isPutDML = ((s & IS_PUT_DML) != 0);
  }

  @Override
  public EventID getEventID() {
    if (this.putAllPRData.length > 0) {
      return this.putAllPRData[0].getEventID();
    }
    return null;
  }

  /**
   * This method is called upon receipt and make the desired changes to the
   * PartitionedRegion Note: It is very important that this message does NOT
   * cause any deadlocks as the sender will wait indefinitely for the
   * acknowledgement
   */
  @Override
  protected final boolean operateOnPartitionedRegion(DistributionManager dm,
      PartitionedRegion r, long startTime)  throws EntryExistsException,
      ForceReattemptException, DataLocationException
  {
    boolean sendReply = true;

    InternalDistributedMember eventSender = getSender();

    long lastModified = 0L;
    try {
      result = doLocalPutAll(r, eventSender, lastModified);
    }
    catch (ForceReattemptException fre) {
      sendReply(getSender(), getProcessorId(), dm, 
          new ReplyException(fre), r, startTime);
      return false;
    }

    if (sendReply) {
      sendReply(getSender(), getProcessorId(), dm, null, r, startTime);
    }
    return false;
  }

  /* we need a event with content for waitForNodeOrCreateBucket() */
  public EntryEventImpl getFirstEvent(PartitionedRegion r) {
    if (putAllPRDataSize == 0) {
      return null;
    }
    PutAllEntryData ped = putAllPRData[0]; 
    EntryEventImpl ev = EntryEventImpl.create(r, 
        ped.getOp(),ped.getKey(),ped.getValue()/* value */,ped.getCallbackArg(),
        false /* originRemote */, getSender(), true/* generate Callbacks */,
        ped.getEventID());
    return ev;
  }
  
  @Override
  protected Object clone() throws CloneNotSupportedException {
    // TODO Auto-generated method stub
    return super.clone();
  }

  /**
   * This method is called by both operateOnPartitionedRegion() when processing a remote msg
   * or by sendMsgByBucket() when processing a msg targeted to local Jvm. 
   * PartitionedRegion Note: It is very important that this message does NOT
   * cause any deadlocks as the sender will wait indefinitely for the
   * acknowledgment
   * @param r partitioned region
   *        eventSender the endpoint server who received request from client
   *        lastModified timestamp for last modification
   * @return If succeeds, return true, otherwise, throw exception
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="IMSE_DONT_CATCH_IMSE")
  public final boolean doLocalPutAll(PartitionedRegion r, InternalDistributedMember eventSender, long lastModified)
  throws EntryExistsException,
  ForceReattemptException,DataLocationException
  {
    final LogWriter logger = r.getCache().getLogger();
    final boolean logFineEnabled = logger.fineEnabled();
    long clientReadTimeOut = PoolFactory.DEFAULT_READ_TIMEOUT;
    if (r.hasServerProxy()) {
      clientReadTimeOut = r.getServerProxy().getPool().getReadTimeout();
      if (logFineEnabled) {
        logger.fine("PutAllPRMessage: doLocalPutAll: clientReadTimeOut is "
            + clientReadTimeOut);
      }
    }
    
    DistributedPutAllOperation dpao = null;
    EntryEventImpl baseEvent = null;
    BucketRegion bucketRegion = null;
    PartitionedRegionDataStore ds = r.getDataStore();
    InternalDistributedMember myId = r.getDistributionManager().getDistributionManagerId();
    final TXStateInterface txi = getTXState(r);
    final TXState tx = txi != null ? txi.getTXStateForWrite() : null;
    final InternalDataView view = (tx != null && tx.isSnapshot()) ? r.getSharedDataView() : r.getDataView(tx);
    boolean lockedForPrimary = false;
    UMMMemoryTracker memoryTracker = null;
    // needed for column store callbacks
    EntryEventImpl[] allEvents = null;
    try {
    
    if (!notificationOnly) {
      // bucketRegion is not null only when !notificationOnly
      bucketRegion = ds.getInitializedBucketForId(null, bucketId);

      this.versions = new VersionedObjectList(
          (tx == null || tx.isSnapshot()) ? this.putAllPRDataSize : 1, true, bucketRegion
              .getAttributes().getConcurrencyChecksEnabled());

      // create a base event and a DPAO for PutAllMessage distributed btw redundant buckets
      baseEvent = EntryEventImpl.create(
          bucketRegion, Operation.PUTALL_CREATE,
          null, null, null, true, eventSender, !skipCallbacks, true);
      // set baseEventId to the first entry's event id. We need the thread id for DACE
      baseEvent.setEventId(putAllPRData[0].getEventID());
      if (this.bridgeContext != null) {
        baseEvent.setContext(this.bridgeContext);
      }
      baseEvent.setPossibleDuplicate(this.posDup);
      baseEvent.setLockingPolicy(getLockingPolicy());
      baseEvent.setFetchFromHDFS(this.fetchFromHDFS);
      baseEvent.setPutDML(this.isPutDML);

      if (logFineEnabled) {
        logger.fine("PutAllPRMessage.doLocalPutAll: eventSender is "
            + eventSender + ", baseEvent is " + baseEvent + ", msg is " + this);
      }
      lastModified = baseEvent.getEventTime(lastModified);
      baseEvent.setEntryLastModified(lastModified);
      dpao = new DistributedPutAllOperation(r, baseEvent, putAllPRDataSize,
          false, this.putAllPRData);
    }

    // Fix the updateMsg misorder issue
    // Lock the keys when doing postPutAll
    Object keys[] = new Object[putAllPRDataSize];
    final boolean keyRequiresRegionContext = r.keyRequiresRegionContext();
    for (int i = 0; i < putAllPRDataSize; ++i) {
      keys[i] = putAllPRData[i].getKey();
      if (keyRequiresRegionContext) {
        // TODO: PERF: avoid this if a local putAll
        KeyWithRegionContext key = (KeyWithRegionContext)keys[i];
        final Object v = this.putAllPRData[i].getValue();
        if (v != null && !(v instanceof Delta)) {
          key.afterDeserializationWithValue(v);
        }
        key.setRegionContext(r);
      }
    }
    if (!notificationOnly) {
      //bucketRegion.columnBatchFlushLock.readLock().lock();
      boolean success = false;
      try {
        if (putAllPRData.length > 0) {
          if (this.posDup && bucketRegion.getConcurrencyChecksEnabled()) {
            // bug #48205 - versions may have already been generated for a posdup event
            // so try to recover them before wiping out the eventTracker's record
            // of the previous attempt
            for (int i = 0; i < putAllPRDataSize; i++) {
              if (putAllPRData[i].versionTag == null) {
                putAllPRData[i].versionTag = bucketRegion.findVersionTagForClientPutAll(putAllPRData[i].getEventID());
              }
            }
          }
          EventID eventID = putAllPRData[0].getEventID();
          ThreadIdentifier membershipID = new ThreadIdentifier(
              eventID.getMembershipID(), eventID.getThreadID());
          bucketRegion.recordPutAllStart(membershipID);
        }
        // no need to lock keys for transactions
        if (tx == null || tx.isSnapshot()) {
          bucketRegion.waitUntilLocked(keys);
        }

        final THashMap succeeded = logFineEnabled ? new THashMap(
            putAllPRDataSize) : null;

        PutAllPartialResult partialKeys = new PutAllPartialResult(
            putAllPRDataSize);

        Object key = keys[0];

        final boolean cacheWrite = bucketRegion.getBucketAdvisor()
            .isPrimary();
        // final boolean hasRedundancy = bucketRegion.getRedundancyLevel() > 0;
        try {
          if (tx == null || tx.isSnapshot()) {
            bucketRegion.doLockForPrimary(false, false);
            lockedForPrimary = true;
          } else {
            lockedForPrimary = false;
          }

          if (CallbackFactoryProvider.getStoreCallbacks().isSnappyStore()
                  && !r.isInternalRegion()) {
            // Setting thread local buffer to 0 here.
            // UMM will provide an initial estimation based on the first row
            memoryTracker = new UMMMemoryTracker(
                Thread.currentThread().getId(), putAllPRDataSize);
            if (r.isInternalColumnTable()) {
              allEvents = new EntryEventImpl[putAllPRDataSize];
            }
          }

        /* The real work to be synchronized, it will take long time. We don't
         * worry about another thread to send any msg which has the same key
         * in this request, because these request will be blocked by foundKey
         */
          for (int i = 0; i < putAllPRDataSize; i++) {
            r.operationStart();
            EntryEventImpl ev = getEventFromEntry(r, bucketRegion, myId,
                eventSender, i, null, putAllPRData, notificationOnly,
                bridgeContext, posDup, skipCallbacks, this.isPutDML);
            try {
              key = ev.getKey();

              ev.setPutAllOperation(dpao);
              ev.setTXState(txi);
              ev.setBufferedMemoryTracker(memoryTracker);

              // set the fetchFromHDFS flag
              ev.setFetchFromHDFS(this.fetchFromHDFS);

              // make sure a local update inserts a cache de-serializable
              ev.makeSerializedNewValue();
//            ev.setLocalFilterInfo(r.getFilterProfile().getLocalFilterRouting(ev));
              if (tx == null || tx.isSnapshot())
                ev.setEntryLastModified(lastModified);
              // ev will be added into dpao in putLocally()
              // oldValue and real operation will be modified into ev in putLocally()
              // then in basicPutPart3(), the ev is added into dpao


              boolean didPut;
              try {
                if (tx != null && !tx.isSnapshot()) {
                  didPut = tx.putEntryOnRemote(ev, false, false, null, false,
                      cacheWrite, lastModified, true);
              /*
              // cacheWrite is always true for this call
              didPut = tx.putEntryLocally(ev, false, false, null, false,
                  hasRedundancy, lastModified, true) != null;
              */
                } else {
                  didPut = view.putEntryOnRemote(ev, false, false, null,
                      false, cacheWrite, lastModified, true);
                  putAllPRData[i].setTailKey(ev.getTailKey());
                }
                if (didPut && allEvents != null) {
                  allEvents[i] = ev;
                }
                if (didPut && logger.fineEnabled()) {
                  logger.fine("PutAllPRMessage.doLocalPutAll:putLocally success for " + ev);
                }
              } catch (ConcurrentCacheModificationException e) {
                didPut = true;
                if (logger.fineEnabled()) {
                  logger.fine("PutAllPRMessage.doLocalPutAll:putLocally encountered concurrent cache modification for " + ev);
                }
              } catch (Exception ex) {
                if (logger.fineEnabled() || DistributionManager.VERBOSE) {
                  logger.info("PutAll operation encountered exception for key "
                      + key, ex);
                }
                r.checkReadiness();
                // these two exceptions are handled at top-level try-catch block
                if (ex instanceof IllegalMonitorStateException) {
                  throw (IllegalMonitorStateException)ex;
                } else if (ex instanceof CacheWriterException) {
                  throw (CacheWriterException)ex;
                } else if (ex instanceof TransactionException) {
                  throw (TransactionException)ex;
                }
                // GemFireXD can throw force reattempt exception wrapped in function
                // exception from index management layer for retries
                else if (ex.getCause() instanceof ForceReattemptException) {
                  throw (ForceReattemptException)ex.getCause();
                } else if (ex instanceof OperationReattemptException) {
                  throw new ForceReattemptException(ex.getMessage(), ex);
                }
                partialKeys.saveFailedKey(key, ex);
                // forcing didPut to true to avoid throwing
                // ForceReattemptException below
                didPut = true;
              }

              if (!didPut) { // make sure the region hasn't gone away
                r.checkReadiness();
                ForceReattemptException fre = new ForceReattemptException(
                    "unable to perform put in PutAllPR, but operation should not fail");
                fre.setHash(ev.getKey().hashCode());
                throw fre;
              } else {
                if (tx == null || tx.isSnapshot()) {
                  this.versions.addKeyAndVersion(putAllPRData[i].getKey(),
                      ev.getVersionTag());
                }
                if (logFineEnabled) {
                  succeeded.put(putAllPRData[i].getKey(),
                      putAllPRData[i].getValue());
                  logger.fine("PutAllPRMessage.doLocalPutAll:putLocally success "
                      + "for " + ev);
                }
              }
            } finally {
              r.operationCompleted();
              ev.release();
            }
          } // for

        } catch (IllegalMonitorStateException ex) {
          ForceReattemptException fre = new ForceReattemptException(
              "unable to get lock for primary, retrying... ");
          throw fre;
        } catch (CacheWriterException cwe) {
          // encounter cacheWriter exception
          partialKeys.saveFailedKey(key, cwe);
        } finally {
          try {
            // Only PutAllPRMessage knows if the thread id is fake. Event has no idea.
            // So we have to manually set useFakeEventId for this DPAO
            dpao.setUseFakeEventId(true);
            r.checkReadiness();
            if (tx != null && tx.isSnapshot()) {
              bucketRegion.getSharedDataView().postPutAll(dpao, this.versions,
                  bucketRegion);
            } else {
              bucketRegion.getDataView(tx).postPutAll(dpao, this.versions,
                  bucketRegion);
            }

          /*
          if (tx != null && hasRedundancy) {
            tx.flushPendingOps(null);
          }
          */
          } finally {
            // moved it below as we may be doing destroy on the bucket, so keep it primary till then
//            if (lockedForPrimary) {
//              bucketRegion.doUnlockForPrimary();
//            }
          }
        }
        if (partialKeys.hasFailure()) {
          if (tx == null || tx.isSnapshot()) {
            partialKeys.addKeysAndVersions(this.versions);
          }
          if (logFineEnabled) {
            logger.fine("PutAllPRMessage: partial keys applied, map to bucket "
                + bucketId + "'s keys:" + Arrays.toString(keys) + ". Applied " + succeeded);
          }
          throw new PutAllPartialResultException(partialKeys);
        }
        success = true;
        } catch (RegionDestroyedException e) {
          ds.checkRegionDestroyedOnBucket(bucketRegion, true, e);
        } finally {
          if (memoryTracker != null) {
            long unusedMemory = memoryTracker.freeMemory();
            if (unusedMemory > 0) {
              CallbackFactoryProvider.getStoreCallbacks().releaseStorageMemory(
                  memoryTracker.getFirstAllocationObject(), unusedMemory, false);
            }
          }
          // no need to lock keys for transactions
          if (tx == null || tx.isSnapshot()) {
            bucketRegion.removeAndNotifyKeys(keys);
          }
        //bucketRegion.columnBatchFlushLock.readLock().unlock();
        // TODO: For tx it may change.
        // TODO: For concurrent putALLs, this will club other putall as well
        // the putAlls in worst case so columnBatchSize may be large?
        if (success && bucketRegion.checkForColumnBatchCreation(txi)) {
          bucketRegion.createAndInsertColumnBatch(txi, false);
        }
        if (success && allEvents != null) {
           CallbackFactoryProvider.getStoreCallbacks()
               .invokeColumnStorePutCallbacks(bucketRegion, allEvents);
        }
        if (lockedForPrimary) {
          bucketRegion.doUnlockForPrimary();
        }
      }
    } else {
      for (int i=0; i<putAllPRDataSize; i++) {
        EntryEventImpl ev = getEventFromEntry(r, bucketRegion, myId,
            eventSender, i, null, putAllPRData, notificationOnly,
            bridgeContext, posDup, skipCallbacks, this.isPutDML);
        try {
        ev.setOriginRemote(true);
        r.invokePutCallbacks(ev.getOperation().isCreate() ? EnumListenerEvent.AFTER_CREATE
            : EnumListenerEvent.AFTER_UPDATE, ev, r.isInitialized(), true);
        } finally {
          ev.release();
        }
      }
    }
    } finally {
      if (baseEvent != null) baseEvent.release();
      if (dpao != null) dpao.freeOffHeapResources();
    }

    return true;
  }

  public final VersionedObjectList getVersions() {
    return this.versions;
  }

  public static EntryEventImpl getEventFromEntry(LocalRegion r, BucketRegion br,
      InternalDistributedMember myId, InternalDistributedMember eventSender,
      int idx, Object key, DistributedPutAllOperation.PutAllEntryData[] data,
      boolean notificationOnly, ClientProxyMembershipID bridgeContext,
      boolean posDup, boolean skipCallbacks, boolean isPutDML) {
    PutAllEntryData prd = data[idx];
    //EntryEventImpl ev = new EntryEventImpl(r, 
       // prd.getOp(),
       // prd.getKey(), null/* value */, null /* callbackArg */,
       // false /* originRemote */,
      //  eventSender, 
      //  true/* generate Callbacks */,
      //  prd.getEventID());

    if (key == null) {
      key = prd.getKey();
    }
    final EntryEventImpl ev;
    if (br != null) {
      // set bucketId directly from BucketRegion
      ev = EntryEventImpl.create(null, prd.getOp(), key, prd.getValue(),
          prd.getCallbackArg(), false, eventSender, !skipCallbacks,
          prd.getEventID());
      ev.setRegion(r);
      ev.setBucketId(br.getId());
    }
    else {
      ev = EntryEventImpl.create(r, prd.getOp(), key, prd.getValue(),
          prd.getCallbackArg(), false, eventSender, !skipCallbacks,
          prd.getEventID());
    }
    boolean evReturned = false;
    try {

    if (prd.getValue() == null 
        && ev.getRegion().getAttributes().getDataPolicy() == DataPolicy.NORMAL) {
      ev.setLocalInvalid(true);
    }
    ev.setNewValue(prd.getValue());
    ev.setOldValue(prd.getOldValue());
    if (bridgeContext != null) {
      ev.setContext(bridgeContext);
    }
    ev.setInvokePRCallbacks(!notificationOnly);
    ev.setPossibleDuplicate(posDup);
    if (prd.filterRouting != null) {
      ev.setLocalFilterInfo(prd.filterRouting.getFilterInfo(myId));
    }
    if (prd.versionTag != null) {
      prd.versionTag.replaceNullIDs(eventSender);
      ev.setVersionTag(prd.versionTag);
    }
    //ev.setLocalFilterInfo(r.getFilterProfile().getLocalFilterRouting(ev));
    if(notificationOnly){
      ev.setTailKey(-1L);
    } else {
      ev.setTailKey(prd.getTailKey());
    }
    ev.setPutDML(isPutDML);
    evReturned = true;
    return ev;
    } finally {
      if (!evReturned) {
        ev.release();
      }
    }
  }

  // override reply message type from PartitionMessage
  @Override
  protected void sendReply(InternalDistributedMember member, int procId, DM dm, ReplyException ex, PartitionedRegion pr, long startTime) {
//    if (!result && getOperation().isCreate()) {
//      System.err.println("DEBUG: put returning false.  ifNew=" + ifNew
//          +" ifOld="+ifOld + " message=" + this);
//    }
    if (pr != null) {
      if (startTime > 0) {
        pr.getPrStats().endPartitionMessagesProcessing(startTime);
      }
      if (!pr.getConcurrencyChecksEnabled() && this.versions != null) {
        this.versions.clear();
      }
    }
    PutAllReplyMessage.send(member, procId, getReplySender(dm), result,
        this.versions, ex, this);
  }

  @Override
  protected final void appendFields(StringBuilder buff) {
    super.appendFields(buff);
    buff.append("; putAllPRDataSize=").append(putAllPRDataSize)
        .append("; bucketId=").append(bucketId);
    if (this.bridgeContext != null) {
      buff.append("; bridgeContext=").append(this.bridgeContext);
    }

    buff.append("; directAck=")
        .append(this.directAck);
    
    for (int i=0; i<putAllPRDataSize; i++) {
//      buff.append("; entry"+i+":").append(putAllPRData[i]);
      buff.append("; entry"+i+":").append(putAllPRData[i].getKey())
        .append(",").append(putAllPRData[i].versionTag);
    }
  }

  public final InternalDistributedSystem getInternalDs()
  {
    return internalDs;
  }

  public final void setInternalDs(InternalDistributedSystem internalDs)
  {
    this.internalDs = internalDs;
  }

  public final void setDirectAck(boolean directAck)
  {
    this.directAck = directAck;
  }

  public static final class PutAllReplyMessage extends ReplyMessage {
    /** Result of the PutAll operation */
    boolean result;
    VersionedObjectList versions;

    @Override
    protected boolean getMessageInlineProcess() {
      return true;
    }

    /**
     * Empty constructor to conform to DataSerializable interface
     */
    public PutAllReplyMessage() {
    }

    private PutAllReplyMessage(int processorId, boolean result,
        VersionedObjectList versions, ReplyException ex,
        PutAllPRMessage sourceMessage) {
      super(sourceMessage, true, true);
      this.versions = versions;
      this.result = result;
      setProcessorId(processorId);
      setException(ex);
    }

    /** Send an ack */
    public static void send(InternalDistributedMember recipient,
        int processorId, ReplySender dm, boolean result,
        VersionedObjectList versions, ReplyException ex,
        PutAllPRMessage sourceMessage) {
      Assert.assertTrue(recipient != null, "PutAllReplyMessage NULL reply message");
      PutAllReplyMessage m = new PutAllReplyMessage(processorId, result,
          versions, ex, sourceMessage);
      m.setRecipient(recipient);
      dm.putOutgoing(m);
    }

    /**
     * Processes this message.  This method is invoked by the receiver
     * of the message.
     * @param dm the distribution manager that is processing the message.
     */
    @Override
    public void process(final DM dm, final ReplyProcessor21 rp) {
      final long startTime = getTimestamp();
      LogWriterI18n l = dm.getLoggerI18n();
      if (DistributionManager.VERBOSE) {
        l.fine("PutAllReplyMessage process invoking reply processor with id="
            + this.processorId + ":exception=" + getException());
      }

      //dm.getLogger().warning("PutAllResponse processor is " + ReplyProcessor21.getProcessor(this.processorId));
      if (rp == null) {
        if (DistributionManager.VERBOSE) {
          l.fine(this.toString() + ": processor not found");
        }
        return;
      }
      if (rp instanceof PutAllResponse) {
        PutAllResponse processor = (PutAllResponse)rp;
        processor.setResponse(this);
      }
      rp.process(this);

      if (DistributionManager.VERBOSE) {
        l.info(LocalizedStrings.PutMessage_0__PROCESSED__1, new Object[] {rp, this});
      }
      dm.getStats().incReplyMessageTime(NanoTimer.getTime()-startTime);
    }

    @Override
    public int getDSFID() {
      return PR_PUTALL_REPLY_MESSAGE;
    }

    @Override
    public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
      super.fromData(in);
      this.result = in.readBoolean();
      this.versions = (VersionedObjectList)DataSerializer.readObject(in);
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      out.writeBoolean(this.result);
      DataSerializer.writeObject(this.versions, out);
    }

    @Override
    public StringBuilder getStringBuilder() {
      StringBuilder sb = super.getStringBuilder();
      if (getException() == null) {
        sb.append(" returning result=").append(this.result);
      }
      sb.append(" versions=").append(this.versions);
      return sb;
    }
  }

  /**
   *  
   */
  public static interface PutAllResponse {
    public boolean isLocal();
    
    public PutAllPRMessage getPRMessage();

    public void setResponse(PutAllReplyMessage response);

    /**
     * @throws ForceReattemptException if the peer is no longer available
     * @throws CacheException if the peer generates an error
     */
    public void waitForResult() throws CacheException,
        ForceReattemptException;
    
    public void setContextObject(PRMsgResponseContext ctx);
    
    public PRMsgResponseContext getContextObject();
    
    public VersionedObjectList getResult(LocalRegion region);
  }

  public static class PutAllResponseFromLocal implements PutAllResponse {

    private volatile PutAllPRMessage prMessage;

    public PutAllResponseFromLocal() {
    }

    public PutAllResponseFromLocal(PutAllPRMessage prMsg) {
      this.prMessage = prMsg;
    }

    public boolean isLocal() {
      return true;
    }

    public PutAllPRMessage getPRMessage() {
      return this.prMessage;
    }

    public void setResponse(PutAllReplyMessage response) {
    }

    public void waitForResult() throws CacheException, ForceReattemptException {
      throw new IllegalStateException(
          "this method should not be called for local put all operation's response");
    }

    public void setContextObject(PRMsgResponseContext ctx) {
    }

    public PRMsgResponseContext getContextObject() {
      return null;
    }

    public VersionedObjectList getResult(LocalRegion region) {
      // setRegionContext is already done for keys for local execution
      return this.prMessage.versions;
    }
  }

  /**
   * A processor to capture the value returned by {@link PutAllPRMessage}
   * @author Gester Zhou
   * @since 5.8
   */
  public static class PutAllResponseFromRemote extends PartitionResponse implements PutAllResponse {
    private volatile boolean returnValue;
    private VersionedObjectList versions;
    private volatile PutAllPRMessage prMessage;
    private PRMsgResponseContext ctxObj;
    
    public PutAllResponseFromRemote(InternalDistributedSystem ds, Set recipients,
        final TXStateInterface tx, PutAllPRMessage prMsg) {
      super(ds, recipients, false, tx);
      this.prMessage = prMsg;
    }

    public boolean isLocal() {
      return false;
    }
    
    public PutAllPRMessage getPRMessage() {
      return this.prMessage;  
    }

    public void setResponse(PutAllReplyMessage response) {
      this.returnValue = response.result;
      if (response.versions != null) {
        this.versions = response.versions;
        this.versions.replaceNullIDs(response.getSender());
      }
    }

    /**
     * @throws ForceReattemptException if the peer is no longer available
     * @throws CacheException if the peer generates an error
     */
    public void waitForResult() throws CacheException,
        ForceReattemptException {
      try {
        waitForCacheException();
      }
      catch (ForceReattemptException e) {
        throw e;
      }
    }

    public void setContextObject(PRMsgResponseContext ctx) {
      this.ctxObj = ctx;
    }

    public PRMsgResponseContext getContextObject() {
      return this.ctxObj;
    }

    @Override
    public VersionedObjectList getResult(LocalRegion region) {
      if (this.versions != null) {
        this.versions.setRegionContext(region);
        return this.versions;
      }
      else {
        return null;
      }
    }
  }

  public static final class PRMsgResponseContext {
    final private InternalDistributedMember currTarget;
    final private Set<InternalDistributedMember> currTargets;
    final private Integer bktId;
    final private EntryEventImpl eeImpl;

    public PRMsgResponseContext(InternalDistributedMember currentTarget,
        Set<InternalDistributedMember> currentTargets, final Integer bucketId, final EntryEventImpl event) {
      this.currTarget = currentTarget;
      this.currTargets = currentTargets;
      this.bktId = bucketId;
      this.eeImpl = event;
    }
    
    public InternalDistributedMember getCurrTarget() {
      return this.currTarget;
    }
    
    public Set<InternalDistributedMember> getCurrTargets() {
      return this.currTargets;
    }
    
    public Integer getBucketId() {
      return this.bktId;
    }
    
    public EntryEventImpl getEvent() {
      return this.eeImpl;
    }
  }

  public void setRemoteResponse(PutAllResponse remoteResponse) {
    this.remoteResponse = remoteResponse;
  }

  public PutAllResponse getRemoteResponse() {
    return this.remoteResponse;
  }
}
