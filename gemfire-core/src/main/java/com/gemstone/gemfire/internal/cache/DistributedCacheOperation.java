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

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InvalidDeltaException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.query.internal.CqService;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.CopyOnWriteHashSet;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.DistributedPutAllOperation.PutAllMessage;
import com.gemstone.gemfire.internal.cache.EntryEventImpl.OldValueImporter;
import com.gemstone.gemfire.internal.cache.FilterRoutingInfo.FilterInfo;
import com.gemstone.gemfire.internal.cache.UpdateOperation.UpdateMessage;
import com.gemstone.gemfire.internal.cache.partitioned.PartitionMessage;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.versions.DiskVersionTag;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapReference;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.sequencelog.EntryLogger;
import com.gemstone.gemfire.internal.shared.Version;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

/**
 * 
 * @author Eric Zoerner
 * @author Bruce Schuchardt
 */
public abstract class DistributedCacheOperation {

  public static double LOSS_SIMULATION_RATIO = 0; // test hook
  public static Random LOSS_SIMULATION_GENERATOR;
  
  public static long SLOW_DISTRIBUTION_MS = 0; // test hook
  
  // constants used in subclasses and distribution messages
  // should use enum in source level 1.5+
  /**
   * Deserialization policy: do not deserialize (for byte array, null or cases
   * where the value should stay serialized)
   * 
   * @since 5.7
   */
  public static final byte DESERIALIZATION_POLICY_NONE = (byte)0;

  /**
   * Deserialization policy: deserialize eagerly (for Deltas)
   * 
   * @since 5.7
   */
  public static final byte DESERIALIZATION_POLICY_EAGER = (byte)1;

  /**
   * Deserialization policy: deserialize lazily (for all other objects)
   * 
   * @since 5.7
   */
  public static final byte DESERIALIZATION_POLICY_LAZY = (byte)2;
  
  /**
   * @param deserializationPolicy must be one of the following: DESERIALIZATION_POLICY_NONE, DESERIALIZATION_POLICY_EAGER, DESERIALIZATION_POLICY_LAZY.
   */
  public static void writeValue(final byte deserializationPolicy, final Object vObj, final byte[] vBytes, final DataOutput out) throws IOException {
    if (vObj != null) {
      if (deserializationPolicy == DESERIALIZATION_POLICY_EAGER) {
        // for DESERIALIZATION_POLICY_EAGER avoid extra byte array serialization
        DataSerializer.writeObject(vObj, out);
      } else if (deserializationPolicy == DESERIALIZATION_POLICY_NONE) {
        // We only have NONE with a vObj when vObj is off-heap and not serialized.
        OffHeapReference ohref = (OffHeapReference) vObj;
        assert !ohref.isSerialized();
        DataSerializer.writeByteArray(ohref.getValueAsHeapByteArray(), out);
      } else { // LAZY
        DataSerializer.writeObjectAsByteArray(vObj, out);
      }
    } else {
      if (deserializationPolicy == DESERIALIZATION_POLICY_EAGER) {
        // object is already in serialized form in the byte array.
        // So just write the bytes to the stream.
        // fromData will call readObject which will deserialize to object form.
        out.write(vBytes);
      } else {
        DataSerializer.writeByteArray(vBytes, out);
      }
    }    
  }
  // static values for oldValueIsObject
  public static final byte VALUE_IS_BYTES = 0;
  public static final byte VALUE_IS_SERIALIZED_OBJECT = 1;
  public static final byte VALUE_IS_OBJECT = 2;

  /**
   * Given a VALUE_IS_* constant convert and return the corresponding DESERIALIZATION_POLICY_*.
   */
  public static byte valueIsToDeserializationPolicy(byte valueIs) {
    if (valueIs == VALUE_IS_BYTES) return DESERIALIZATION_POLICY_NONE;
    if (valueIs == VALUE_IS_OBJECT) return DESERIALIZATION_POLICY_EAGER;
    if (valueIs == VALUE_IS_SERIALIZED_OBJECT) return DESERIALIZATION_POLICY_LAZY;
    throw new IllegalStateException("expected 0, 1, or 2 but got " + valueIs);
  }


  public final static byte DESERIALIZATION_POLICY_NUMBITS =
          AbstractOperationMessage.getNumBits(DESERIALIZATION_POLICY_LAZY);

  public static final short DESERIALIZATION_POLICY_END =
          (short) (1 << DESERIALIZATION_POLICY_NUMBITS);
  public static final short DESERIALIZATION_POLICY_MASK =
          (short) (DESERIALIZATION_POLICY_END - 1);

  public static boolean testSendingOldValues;

  protected InternalCacheEvent event;

  protected CacheOperationReplyProcessor processor = null;

  protected Set departedMembers;

  protected Set originalRecipients;

  static Runnable internalBeforePutOutgoing;

  public static String deserializationPolicyToString(byte policy) {
    switch (policy) {
    case DESERIALIZATION_POLICY_NONE:
      return "NONE";
    case DESERIALIZATION_POLICY_EAGER:
      return "EAGER";
    case DESERIALIZATION_POLICY_LAZY:
      return "LAZY";
    default:
      throw new AssertionError("unknown deserialization policy");
    }
  }

  /** Creates a new instance of DistributedCacheOperation */
  public DistributedCacheOperation(CacheEvent event) {
    this.event = (InternalCacheEvent)event;
  }

  /**
   * Return true if this operation needs to check for reliable delivery. Return
   * false if not. Currently the only case it doesn't need to be is a
   * DestroyRegionOperation doing a "local" destroy.
   * 
   * @since 5.0
   */
  boolean isOperationReliable() {
    Operation op = this.event.getOperation();
    if (!op.isRegionDestroy()) {
      return true;
    }
    if (op.isDistributed()) {
      return true;
    }
    // must be a region destroy that is "local" which means
    // Region.localDestroyRegion or Region.close or Cache.clsoe
    // none of these should do reliability checks
    return false;
  }

  public boolean supportsDirectAck() {
    // force use of shared connection if we're already in a secondary
    // thread-owned reader thread.  See bug #49565
    int dominoCount = com.gemstone.gemfire.internal.tcp.Connection.getDominoCount();
//    if (dominoCount >= 2) {
//      InternalDistributedSystem.getLoggerI18n().info(LocalizedStrings.DEBUG, "forcing use of reply processor for a domino count of " + dominoCount);
//    }
    return dominoCount < 2;
  }

  /**
   * returns true if multicast can be used for this operation. The default is
   * true.
   */
  public boolean supportsMulticast() {
    return true;
  }

  /** returns true if the receiver can distribute during cache closure */
  public boolean canBeSentDuringShutdown() {
    return getRegion().isUsedForPartitionedRegionAdmin();
  }

  /**
   * returns true if adjunct messaging (piggybacking) is allowed for this
   * operation. Region-oriented operations typically do not allow adjunct
   * messaging, while Entry-oriented operations do. The default implementation
   * returns true.
   */
  protected boolean supportsAdjunctMessaging() {
    return true;
  }

  /**
   * returns true if this operation supports propagation of delta values instead
   * of full changes
   */
  protected boolean supportsDeltaPropagation() {
    return false;
  }

  /** does this operation change region content? */
  public boolean containsRegionContentChange() {
    return true;
  }

  /**
   * Distribute a cache operation to other members of the distributed system.
   * This method determines who the recipients are and handles careful delivery
   * of the operation to those members.
   */
  public void distribute() {
    DistributedRegion region = getRegion();
    DM mgr = region.getDistributionManager();
    LogWriterI18n logger = region.getCache().getLoggerI18n();
    boolean reliableOp = isOperationReliable()
        && region.requiresReliabilityCheck();
    
    if (SLOW_DISTRIBUTION_MS > 0) { // test hook
      try { Thread.sleep(SLOW_DISTRIBUTION_MS); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      SLOW_DISTRIBUTION_MS = 0;
    }
    
    boolean isPutAll = (this instanceof DistributedPutAllOperation);

    long viewVersion = -1;
    if (this.containsRegionContentChange()) {
      viewVersion = region.getDistributionAdvisor().startOperation();
    }
    if (StateFlushOperation.DEBUG) {
      logger.info(LocalizedStrings.DEBUG, "dispatching operation in view version " + viewVersion);
    }

    boolean sendingTwoMessages = false;
    try {
      // Recipients with CacheOp
      Set<InternalDistributedMember> recipients = getRecipients();
      Map<InternalDistributedMember, PersistentMemberID> persistentIds = null;
      if(region.getDataPolicy().withPersistence()) {
        persistentIds = region.getDistributionAdvisor().adviseInitializedPersistentMembers();
      }

      // some members requiring old value are also in the cache op recipients set
      Set needsOldValueInCacheOp = Collections.EMPTY_SET;

      // set client routing information into the event
      boolean routingComputed = false;
      FilterRoutingInfo filterRouting = null;
      // recipients that will get a cacheop msg and also a PR message
      Set twoMessages = Collections.EMPTY_SET;
      if (region.isUsedForPartitionedRegionBucket()) {
        twoMessages = ((BucketRegion)region).getBucketAdvisor().adviseRequiresTwoMessages();
        sendingTwoMessages = !twoMessages.isEmpty();
        routingComputed = true;
        filterRouting = getRecipientFilterRouting(recipients);
        if (filterRouting != null && logger.fineEnabled()) {
          logger.fine("Computed this filter routing: "
              + filterRouting);
        }
      }

      // some members need PR notification of the change for client/wan
      // notification
      Set adjunctRecipients = Collections.EMPTY_SET;

      // Partitioned region listener notification messages piggyback on this
      // operation's replyprocessor and need to be sent at the same time as
      // the operation's message
      if (this.supportsAdjunctMessaging()
          && region.isUsedForPartitionedRegionBucket()) {
        BucketRegion br = (BucketRegion)region;
        adjunctRecipients = getAdjunctReceivers(br, recipients,
            twoMessages, filterRouting);        
      }
      
      EntryEventImpl entryEvent = event.getOperation().isEntry()? getEvent() : null;

      if (entryEvent != null && entryEvent.hasOldValue()) {
        if (testSendingOldValues) {
          needsOldValueInCacheOp = new HashSet(recipients);
        }
        else {
          needsOldValueInCacheOp = region.getCacheDistributionAdvisor()
              .adviseRequiresOldValueInCacheOp();         
        }
        recipients.removeAll(needsOldValueInCacheOp);
      }

      Set cachelessNodes = Collections.EMPTY_SET;
      Set adviseCacheServers = Collections.EMPTY_SET;
      Set<InternalDistributedMember> cachelessNodesWithNoCacheServer = new HashSet<InternalDistributedMember>();
      if (region.getDistributionConfig().getDeltaPropagation()
          && this.supportsDeltaPropagation()) {
        cachelessNodes = region.getCacheDistributionAdvisor().adviseEmptys();
        if (!cachelessNodes.isEmpty()) {
          List list = new ArrayList(cachelessNodes);
          for (Object member : cachelessNodes) {
            if (!recipients.contains(member)) {
              // Don't include those originally excluded.
              list.remove(member);
            } else if (adjunctRecipients.contains(member)) {
              list.remove(member);
            }
          }
          cachelessNodes.clear();
          recipients.removeAll(list);
          cachelessNodes.addAll(list);
        }

        cachelessNodesWithNoCacheServer.addAll(cachelessNodes);
        adviseCacheServers = region.getCacheDistributionAdvisor()
            .adviseCacheServers();
        cachelessNodesWithNoCacheServer.removeAll(adviseCacheServers);
      }

      if (recipients.isEmpty() && adjunctRecipients.isEmpty()
          && needsOldValueInCacheOp.isEmpty() && cachelessNodes.isEmpty()) {
        if (logger.fineEnabled() && mgr.getNormalDistributionManagerIds().size() > 1) {
          if (region.isInternalRegion()) {
            if (logger.finerEnabled()) {
              // suppress this msg if we are the only member.
              logger.finer("<No Recipients> " + this);
            }
          } else {
            // suppress this msg if we are the only member.
            logger.fine("<No Recipients> " + this);
          }
        }
        if (!reliableOp || region.isNoDistributionOk()) {
          // nothing needs be done in this case
          //Asif: Used by GemFireXD. We need to know if system had only accessors
          //and no data stores due to which message was not sent
          //This check needs to done if the current region is non partitioned,
          //with dataPolicy.Empty and recipients list is empty( there may be other 
          //accesors to recieve adjunct messages)
          checkForDataStoreAvailability(region,recipients);
        } else {
          // create the message so it can be passed to
          // handleReliableDistribution
          // for queuing
          CacheOperationMessage msg = createMessage();
          initMessage(msg, null);
          msg.setRecipients(recipients); // it is going to no one
          region.handleReliableDistribution(msg, Collections.EMPTY_SET);
        }

        /** compute local client routing before waiting for an ack only for a bucket*/
        if (region.isUsedForPartitionedRegionBucket()) {
          FilterInfo filterInfo = getLocalFilterRouting(filterRouting);
          this.event.setLocalFilterInfo(filterInfo);
        }

      } else {
        boolean directAck = false;
        boolean useMulticast = region.getMulticastEnabled()
            && region.getSystem().getConfig().getMcastPort() != 0
            && this.supportsMulticast();
        ;
        boolean shouldAck = shouldAck();

        if (shouldAck) {
          if (this.supportsDirectAck() && adjunctRecipients.isEmpty()) {
            if (region.getSystem().threadOwnsResources()) {
              directAck = true;
            }
          }
        }
        // don't send to the sender of a remote-operation-message.  Those messages send
        // their own response.  fixes bug #45973
        if (entryEvent != null) {
          RemoteOperationMessage rmsg = entryEvent.getRemoteOperationMessage();
          if (rmsg != null) {
            recipients.remove(rmsg.getSender());
          }
          useMulticast = false; // bug #45106: can't mcast or the sender of the one-hop op will get it
        }
        if (logger.fineEnabled()) {
          StringBuilder msg = new StringBuilder(200);
          msg.append("recipients for ").append(this).append(": ").append(
              recipients);
          if (!adjunctRecipients.isEmpty()) {
            msg.append(" with adjunct messages to: ").append(adjunctRecipients);
          }
          logger.fine(msg.toString());
        }
        if (shouldAck) {
          // adjunct messages are sent using the same reply processor, so
          // add them to the processor's membership set
          Collection waitForMembers = null;
          if (recipients.size() > 0 && adjunctRecipients.size() == 0
                     && cachelessNodes.isEmpty()) { // the common case
            waitForMembers = recipients;
          } else if (!cachelessNodes.isEmpty()){
            waitForMembers = new HashSet(recipients);
            waitForMembers.addAll(cachelessNodes);
          } else {
            // note that we use a Vector instead of a Set for the responders
            // collection
            // because partitioned regions sometimes send both a regular cache
            // operation and a partitioned-region notification message to the
            // same recipient
            waitForMembers = new Vector(recipients);
            waitForMembers.addAll(adjunctRecipients);
            waitForMembers.addAll(needsOldValueInCacheOp);
            waitForMembers.addAll(cachelessNodes);
          }
          if (DistributedCacheOperation.LOSS_SIMULATION_RATIO != 0.0) {
            if (LOSS_SIMULATION_GENERATOR == null) {
              LOSS_SIMULATION_GENERATOR = new Random(this.hashCode());
            }
            if ( (LOSS_SIMULATION_GENERATOR.nextInt(100) * 1.0 / 100.0) < LOSS_SIMULATION_RATIO ) {
              logger.info(LocalizedStrings.DEBUG,
                  "loss simulation is inhibiting message transmission to " + recipients);
              if (waitForMembers == recipients) {
                waitForMembers.clear();
              }
              else {
                waitForMembers.removeAll(recipients);
              }
              recipients = Collections.EMPTY_SET;
            }
          }
          if (reliableOp) {
            this.departedMembers = new HashSet();
            this.processor = new ReliableCacheReplyProcessor(
                region.getSystem(), waitForMembers, this.departedMembers);
          } else {
            this.processor = new CacheOperationReplyProcessor(region
                .getSystem(), waitForMembers);
          }
        }

        Set failures = null;
        CacheOperationMessage msg = createMessage();
        initMessage(msg, this.processor);

        if (DistributedCacheOperation.internalBeforePutOutgoing != null) {
          DistributedCacheOperation.internalBeforePutOutgoing.run();
        }

        if (processor != null && msg.isSevereAlertCompatible()) {
          this.processor.enableSevereAlertProcessing();
          // if this message is distributing for a partitioned region message,
          // we can't wait as long as the full ack-severe-alert-threshold or
          // the sender might kick us out of the system before we can get an ack
          // back
          DistributedRegion r = getRegion();
          if (r.isUsedForPartitionedRegionBucket()
              && event.getOperation().isEntry()) {
            PartitionMessage pm = ((EntryEventImpl)event).getPartitionMessage();
            if (pm != null
                && pm.getSender() != null
                && !pm.getSender().equals(
                    r.getDistributionManager().getDistributionManagerId())) {
              // PR message sent by another member
              ReplyProcessor21.setShortSevereAlertProcessing(true);
            }
          }
        }

        msg.setMulticast(useMulticast);
        msg.directAck = directAck;
        if (region.isUsedForPartitionedRegionBucket()) {
          if (!isPutAll && filterRouting != null && filterRouting.hasMemberWithFilterInfo()) {
            if (logger.fineEnabled()) {
              logger.fine("Setting filter information for message to "
                  + filterRouting);
            }
            msg.filterRouting = filterRouting;
          }
        } else if (!routingComputed) {
          msg.needsRouting = true;
        }

        initProcessor(processor, msg);

        if (region.cache.isClosed() && !canBeSentDuringShutdown()) {
          throw region.cache
              .getCacheClosedException(
                  LocalizedStrings.DistributedCacheOperation_THE_CACHE_HAS_BEEN_CLOSED
                      .toLocalizedString(), null);
        }

        msg.setRecipients(recipients);
        failures = mgr.putOutgoing(msg);

        // distribute to members needing the old value now
        if (needsOldValueInCacheOp.size() > 0) {
          msg.appendOldValueToMessage((EntryEventImpl)this.event); // TODO OFFHEAP optimize
          msg.resetRecipients();
          msg.setRecipients(needsOldValueInCacheOp);
          Set newFailures = mgr.putOutgoing(msg);
          if (newFailures != null) {
            if (logger.fineEnabled()) {
              logger.fine("Failed sending {" + msg + "} to " + newFailures);
            }
            if (failures != null && failures.size() > 0) {
              failures.addAll(newFailures);
            }
            else {
              failures = newFailures;
            }
          }
        }

        if (cachelessNodes.size() > 0) {
          cachelessNodes.removeAll(cachelessNodesWithNoCacheServer);
          if (cachelessNodes.size() > 0) {
            msg.resetRecipients();
            msg.setRecipients(cachelessNodes);
            msg.setSendDelta(false);
            Set newFailures = mgr.putOutgoing(msg);
            if (newFailures != null) {
              if (failures != null && failures.size() > 0) {
                failures.addAll(newFailures);
              } else {
                failures = newFailures;
              }
            }
          }

          if (cachelessNodesWithNoCacheServer.size() > 0) {
            msg.resetRecipients();
            msg.setRecipients(cachelessNodesWithNoCacheServer);
            msg.setSendDelta(false);
            ((UpdateMessage)msg).setSendDeltaWithFullValue(false);
            Set newFailures = mgr.putOutgoing(msg);
            if (newFailures != null) {
              if (failures != null && failures.size() > 0) {
                failures.addAll(newFailures);
              } else {
                failures = newFailures;
              }
            }
          }
          // Add it back for size calculation ahead
          cachelessNodes.addAll(cachelessNodesWithNoCacheServer);
        }

        if (failures != null && !failures.isEmpty() && logger.fineEnabled()) {
          logger.fine("Failed sending {" + msg + "} to " + failures
              + " while processing event:" + event);
        }

        Set<InternalDistributedMember> adjunctRecipientsWithNoCacheServer = new HashSet<InternalDistributedMember>();
        // send partitioned region listener notification messages now
        if (!adjunctRecipients.isEmpty()) {
          if (cachelessNodes.size() > 0) {
            // add non-delta recipients back into the set for adjunct
            // calculations
            if (recipients.isEmpty()) {
              recipients = cachelessNodes;
            } else {
              recipients.addAll(cachelessNodes);
            }
          }

          adjunctRecipientsWithNoCacheServer.addAll(adjunctRecipients);
          adviseCacheServers = ((BucketRegion)region).getPartitionedRegion()
              .getCacheDistributionAdvisor().adviseCacheServers();
          adjunctRecipientsWithNoCacheServer.removeAll(adviseCacheServers);

          if (isPutAll) {
            ((BucketRegion)region).performPutAllAdjunctMessaging(
                (DistributedPutAllOperation)this, recipients,
                adjunctRecipients, filterRouting, this.processor);
          } else {
            boolean calculateDelta = adjunctRecipientsWithNoCacheServer.size() < adjunctRecipients
                .size();
            adjunctRecipients.removeAll(adjunctRecipientsWithNoCacheServer);
            if (!adjunctRecipients.isEmpty()) {
              ((BucketRegion)region).performAdjunctMessaging(getEvent(),
                  recipients, adjunctRecipients, filterRouting, this.processor,
                  calculateDelta, true);
            }
            if (!adjunctRecipientsWithNoCacheServer.isEmpty()) {
              ((BucketRegion)region).performAdjunctMessaging(getEvent(),
                  recipients, adjunctRecipientsWithNoCacheServer,
                  filterRouting, this.processor, calculateDelta, false);
            }
          }
        }

        if (viewVersion != -1) {
          region.getDistributionAdvisor().endOperation(viewVersion);
          viewVersion = -1;
        }

        /** compute local client routing before waiting for an ack only for a bucket*/
        if (region.isUsedForPartitionedRegionBucket()) {
          FilterInfo filterInfo = getLocalFilterRouting(filterRouting);
          event.setLocalFilterInfo(filterInfo);
        }

        waitForAckIfNeeded(msg, persistentIds);

        if (/* msg != null && */reliableOp) {
          Set successfulRecips = new HashSet(recipients);
          successfulRecips.addAll(cachelessNodes);
          successfulRecips.addAll(needsOldValueInCacheOp);
          if (failures != null && !failures.isEmpty()) {
            // logger.warning("distribute failures to " + failures);
            successfulRecips.removeAll(failures);
          }
          if (departedMembers != null) {
            successfulRecips.removeAll(departedMembers);
          }
          region.handleReliableDistribution(msg, successfulRecips);
        }
      }

    } catch (CancelException e) {
      if (logger.fineEnabled()) {
        logger.fine("distribution of message aborted by shutdown: " + this);
      }
      throw e;
    } catch (RuntimeException e) {
      EntryNotFoundException enfe = null;
      if (e instanceof EntryNotFoundException) {
        enfe = (EntryNotFoundException)e;
      }
      else if (e.getCause() instanceof EntryNotFoundException) {
        enfe = (EntryNotFoundException)e.getCause();
      }
      // ignore entry not found for destroy on secondary if it is still
      // initializing, since GII state may already have entry destroyed (#41877)
      final Operation op;
      if (enfe != null && region.isUsedForPartitionedRegionBucket()
          && (op = getEvent().getOperation()).isDestroy() && op.isDistributed()
          && sendingTwoMessages) {
        if (DistributionManager.VERBOSE || logger.fineEnabled()) {
          logger.info(LocalizedStrings.DEBUG, "distribution of message from "
              + "primary to secondary received EntryNotFoundException "
              + "due to secondary still in initialization: " + this, e);
        }
      }
      else {
        if (DistributionManager.VERBOSE || logger.fineEnabled()) {
          logger.info(LocalizedStrings.DEBUG,
              "Distribution of message aborted by runtime exception: " + this, e);
        }
        throw e;
      }
    } finally {
      ReplyProcessor21.setShortSevereAlertProcessing(false);
      if (viewVersion != -1) {
        if (StateFlushOperation.DEBUG) {
          logger.info(LocalizedStrings.DEBUG, "done dispatching operation in view version " + viewVersion);
        }
        region.getDistributionAdvisor().endOperation(viewVersion);
      }
    }
  }

  protected void checkForDataStoreAvailability(DistributedRegion region,
      Set<InternalDistributedMember> recipients) {
  }

  /**
   * Get the adjunct receivers for a partitioned region operation
   * 
   * @param br
   *          the PR bucket
   * @param cacheOpReceivers
   *          the receivers of the CacheOperationMessage for this op
   * @param twoMessages
   *          PR members that are creating the bucket and need both cache op
   *          and adjunct messages
   * @param routing
   *          client routing information
   */
  Set getAdjunctReceivers(BucketRegion br, Set cacheOpReceivers,
      Set twoMessages, FilterRoutingInfo routing) {
    return br.getAdjunctReceivers(this.getEvent(), cacheOpReceivers,
        twoMessages, routing);
  }

  /**
   * perform any operation-specific initialization on the given reply processor
   * 
   * @param p
   * @param msg
   */
  protected void initProcessor(CacheOperationReplyProcessor p,
      CacheOperationMessage msg) {
    // nothing to do here - see UpdateMessage
  }

  protected final void waitForAckIfNeeded(CacheOperationMessage msg, Map<InternalDistributedMember, PersistentMemberID> persistentIds) {
    if (this.processor == null) {
      return;
    }
    try {
      // keep waiting even if interrupted
      try {
        this.processor.waitForRepliesUninterruptibly();
        if (this.processor.closedMembers.size() == 0) {
          return;
        }
        Set<InternalDistributedMember> closedMembers = this.processor.closedMembers
            .getSnapshot();
        handleClosedMembers(closedMembers, persistentIds,
            msg.getSuccessfulRecipients());
      } catch (ReplyException e) {
        if (e.getCause() instanceof CancelException) {
          Set<InternalDistributedMember> closedMembers = this.processor.closedMembers
              .getSnapshot();
          handleClosedMembers(closedMembers, persistentIds,
              msg.getSuccessfulRecipients());
        }
        else {
          if (this instanceof DestroyRegionOperation) {
            getRegion()
                .getSystem()
                .getLogWriterI18n()
                .severe(
                    LocalizedStrings.DistributedCacheOperation_WAITFORACKIFNEEDED_EXCEPTION,
                    e);
          }
          e.handleAsUnexpected();
        }
      }
    } finally {
      this.processor = null;
    }
  }

  /**
   * @param closedMembers
   */
  private void handleClosedMembers(
      Set<InternalDistributedMember> closedMembers,
      Map<InternalDistributedMember, PersistentMemberID> persistentIds,
      Set<InternalDistributedMember> successulReceps) {
    if (closedMembers.size() == 0) {
      return;
    }

    if (persistentIds != null) {
     for (InternalDistributedMember member : closedMembers) {
      PersistentMemberID persistentId = persistentIds.get(member);
      if (persistentId != null) {
        //Fix for bug 42142 - In order for recovery to work, 
        //we must either
        // 1) persistent the region operation successfully on the peer
        // 2) record that the peer is offline
        //or
        // 3) fail the operation
        
        //if we have started to shutdown, we don't want to mark the peer
        //as offline, or we will think we have newer data when in fact we don't
        getRegion().getCancelCriterion().checkCancelInProgress(null);
        
        //Otherwise, mark the peer as offline, because it didn't complete
        //the operation.
        getRegion().getPersistenceAdvisor().markMemberOffline(member, persistentId);
      }
     }
    }
    DistributedRegion dr = this.getRegion();
    // Throw exception only if the operation is being distributed from accessor.
    if (!dr.isUsedForPartitionedRegionBucket()
        && !dr.getDataPolicy().withStorage() && this.event != null
        && !this.event.getOperation().isRegion()) {
      // If none of the sucessful recepients acted on the message, then throw
      // exception
      Iterator<InternalDistributedMember> receps = successulReceps.iterator();
      boolean atleastOneAppliedOp = false;
      while (receps.hasNext()) {
        if (!closedMembers.contains(receps.next())) {
          // atleast one successful
          atleastOneAppliedOp = true;
          break;
        }
      }
      if (!atleastOneAppliedOp) {
        LogWriterI18n logger = this.getRegion().getCache().getLoggerI18n();
        if (logger.fineEnabled()) {
          logger.fine("Members reporting as successfuly applied the operation="
              + successulReceps);
          logger.fine("Members reporting with closed system=" + closedMembers);
        }
        throw new NoDataStoreAvailableException(LocalizedStrings
            .DistributedRegion_NO_DATA_STORE_FOUND_FOR_DISTRIBUTION
                .toLocalizedString(this.getRegion()));
      }
    }
  }

  protected boolean shouldAck() {
    return getRegion().scope.isAck();
  }

  protected final DistributedRegion getRegion() {
    return (DistributedRegion)this.event.getRegion();
  }

  protected final EntryEventImpl getEvent() {
    return (EntryEventImpl)this.event;
  }

  protected Set getRecipients() {
    CacheDistributionAdvisor advisor = getRegion()
        .getCacheDistributionAdvisor();
    this.originalRecipients = advisor.adviseCacheOp();
    return this.originalRecipients;
  }

  protected FilterRoutingInfo getRecipientFilterRouting(Set cacheOpRecipients) {
    LocalRegion region = getRegion();
    if (!region.isUsedForPartitionedRegionBucket()) {
      return null;
    }
    CacheDistributionAdvisor advisor;
//    if (region.isUsedForPartitionedRegionBucket()) {
      advisor = ((BucketRegion)region).getPartitionedRegion()
          .getCacheDistributionAdvisor();
//    } else {
//      advisor = ((DistributedRegion)region).getCacheDistributionAdvisor();
//    }
    return advisor.adviseFilterRouting(this.event, cacheOpRecipients);
  }

  /**
   * @param frInfo the filter routing computed for distribution to peers
   * @return the filter routing computed for distribution to clients of this process
   */
  protected FilterInfo getLocalFilterRouting(FilterRoutingInfo frInfo) {
    FilterProfile fp = getRegion().getFilterProfile();
    if (fp == null) {
      return null;
    }
    FilterRoutingInfo fri = fp.getFilterRoutingInfoPart2(frInfo, this.event);
    if (fri == null) {
      return null;
    }
    return fri.getLocalFilterInfo();
  }

  protected abstract CacheOperationMessage createMessage();

  protected void initMessage(CacheOperationMessage msg, DirectReplyProcessor p) {
    final DistributedRegion region = getRegion();
    msg.regionPath = region.getFullPath();
    msg.processorId = p == null ? 0 : p.getProcessorId();
    msg.processor = p;
    if (this.event.getOperation().isEntry()) {
      EntryEventImpl entryEvent = getEvent();
      msg.callbackArg = entryEvent.getRawCallbackArgument();
      msg.possibleDuplicate = entryEvent.isPossibleDuplicate();

      VersionTag tag = entryEvent.getVersionTag();
      msg.setInhibitNotificationsBit(entryEvent.inhibitAllNotifications());
      if (tag != null && tag.hasValidVersion()) {
        msg.setVersionTag(tag);
      }
      
    } else {
      msg.callbackArg = ((RegionEventImpl)this.event).getRawCallbackArgument();
    }
    msg.op = this.event.getOperation();
    msg.owner = this;
    msg.regionAllowsConflation = region.getEnableAsyncConflation();
    
  }

  @Override
  public String toString() {
    String cname = getClass().getName().substring(
        getClass().getPackage().getName().length() + 1);
    return cname + "(" + this.event + ")";
  }

  /**
   * Add an internal callback which is run before the CacheOperationMessage is
   * distributed with dm.putOutgoing.
   */
  public static void setBeforePutOutgoing(Runnable beforePutOutgoing) {
    internalBeforePutOutgoing = beforePutOutgoing;
  }

  public static abstract class CacheOperationMessage extends
      AbstractOperationMessage implements MessageWithReply, DirectReplyMessage,
      ReliableDistributionData, OldValueImporter {

    protected final static short POSSIBLE_DUPLICATE_MASK = POS_DUP;
    protected final static short OLD_VALUE_MASK =
      AbstractOperationMessage.UNRESERVED_FLAGS_START;
    protected final static short DIRECT_ACK_MASK = (OLD_VALUE_MASK << 1);
    protected final static short FILTER_INFO_MASK = (DIRECT_ACK_MASK << 1);
    protected final static short CALLBACK_ARG_MASK = (FILTER_INFO_MASK << 1);
    protected final static short DELTA_MASK = (CALLBACK_ARG_MASK << 1);
    protected final static short NEEDS_ROUTING_MASK = (DELTA_MASK << 1);
    protected final static short VERSION_TAG_MASK = (NEEDS_ROUTING_MASK << 1);
    protected final static short PERSISTENT_TAG_MASK = (VERSION_TAG_MASK << 1);
    protected final static short UNRESERVED_FLAGS_START =
      (PERSISTENT_TAG_MASK << 1);

    /** extra bits */
    protected final static short INHIBIT_NOTIFICATIONS_MASK = 0x01;
    
    protected final static short FETCH_FROM_HDFS = 0x02;
    
    protected final static short IS_PUT_DML = 0x04;

    public boolean needsRouting;

    protected String regionPath;

    public DirectReplyProcessor processor;

    protected transient LocalRegion lclRgn;

    protected Object callbackArg;

    protected Operation op;

    public transient boolean directAck = false;

    protected transient DistributedCacheOperation owner;

    protected transient boolean appliedOperation = false;
    
    protected transient boolean closed = false;

    protected boolean hasOldValue;
    protected Object oldValue;
    protected byte oldValueIsObject;

    protected boolean hasDelta = false;

    protected FilterRoutingInfo filterRouting;

    protected transient boolean sendDelta = true;

    /** concurrency versioning tag */
    protected VersionTag versionTag;

    //protected transient short flags;

    protected boolean inhibitAllNotifications;

    protected CacheOperationMessage(){super((TXStateInterface)null);}

    protected CacheOperationMessage(TXStateInterface tx) {
      // this never carries TX information but may still require to wait for a
      // previous pending TX
      super(tx);
    }

    public Operation getOperation() {
      return this.op;
    }

    /** sets the concurrency versioning tag for this message */
    public final void setVersionTag(VersionTag tag) {
      this.versionTag = tag;
    }

    public final VersionTag getVersionTag() {
      return this.versionTag;
    }

    @Override
    public boolean isSevereAlertCompatible() {
      // allow forced-disconnect processing for all cache op messages
      return true;
    }

    public DirectReplyProcessor getDirectReplyProcessor() {
      return processor;
    }

    public void registerProcessor() {
      if (processor != null) {
        this.processorId = this.processor.register();
      }
      this.directAck = false;
    }

    public void setFilterInfo(FilterRoutingInfo fInfo) {
      this.filterRouting = fInfo;
    }

    public void setInhibitNotificationsBit(boolean inhibit) {
      this.inhibitAllNotifications = inhibit;
    }

    /**
     * process a reply
     * @param reply
     * @param processor
     * @return true if the reply-processor should continue to process this response
     */
    boolean processReply(ReplyMessage reply, CacheOperationReplyProcessor processor) {
      // notification that a reply has been received.  Most messages
      // don't do anything special here
      return true;
    }

    /**
     * Add the cache event's old value to this message.  We must propagate
     * the old value when the receiver is doing GII and has listeners (CQs)
     * that require the old value.
     * @since 5.5
     * @param event the entry event that contains the old value
     */
    public void appendOldValueToMessage(EntryEventImpl event) {
      {
        @Unretained Object val = event.getRawOldValue();
        if (val == null ||
            val == Token.NOT_AVAILABLE ||
            val == Token.REMOVED_PHASE1 ||
            val == Token.REMOVED_PHASE2 ||
            val == Token.DESTROYED ||
            val == Token.TOMBSTONE) {
          return;
        }
      }
      event.exportOldValue(this);
    }
    
    /**
     * Insert this message's oldValue into the given event.  This fixes
     * bug 38382 by propagating old values with Entry level
     * CacheOperationMessages during initial image transfer
     * @since 5.5
     */
    public void setOldValueInEvent(EntryEventImpl event) {
      if (CqService.isRunning()/* || event.getOperation().guaranteesOldValue()*/) {
        event.setOldValueForQueryProcessing();
        if (!event.hasOldValue() && this.hasOldValue) {
          if (this.oldValueIsObject == VALUE_IS_SERIALIZED_OBJECT) {
            event.setSerializedOldValue((byte[])this.oldValue);
          }
          else {
            event.setOldValue(this.oldValue);
          }
        }
      }
    }

    /**
     * Sets a flag in the message indicating that this message contains delta
     * bytes.
     * 
     * @since 6.1
     */
    protected void setHasDelta(boolean flag) {
      this.hasDelta = flag;
    }

    protected boolean hasDelta() {
      return this.hasDelta;
    }

    public FilterRoutingInfo getFilterInfo() {
      return this.filterRouting;
    }

    /**
     * @since 4.2.3
     */
    protected transient boolean regionAllowsConflation;

    public boolean possibleDuplicate;

    @Override
    public boolean containsRegionContentChange() {
      return true;
    }

    @Override
    protected final void basicProcess(final DistributionManager dm) {
      Throwable thr = null;
      LogWriterI18n logger = null;
      boolean sendReply = true;
      
      if (this.versionTag != null) {
        this.versionTag.replaceNullIDs(getSender());
      }

      EntryLogger.setSource(this.getSender(), "p2p");
      boolean resetOldLevel = true;
      // do this before CacheFactory.getInstance for bug 33471
      int oldLevel = LocalRegion.setThreadInitLevelRequirement(
                                  LocalRegion.BEFORE_INITIAL_IMAGE);
      try {
        if (dm.getDMType() == DistributionManager.ADMIN_ONLY_DM_TYPE) {
          // this was probably a multicast message
          return;
        }

        logger = dm.getLoggerI18n();
        Assert.assertTrue(this.regionPath != null, "regionPath was null");

        if (this.lclRgn == null) {
          GemFireCacheImpl gfc = (GemFireCacheImpl)CacheFactory.getInstance(dm
              .getSystem());
          this.lclRgn = gfc.getRegionByPathForProcessing(this.regionPath);
        }
        sendReply = false;
        basicProcess(dm, lclRgn);
      } catch (CancelException e) {
        this.closed = true;
        if (logger.fineEnabled())
          logger.fine(this + " Cancelled: nothing to do");
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        // GemFireXD can throw force reattempt exception wrapped in function
        // exception from index management layer for retries
        if (t.getCause() instanceof ForceReattemptException) {
          this.closed = true;
          if (logger.fineEnabled()) {
            logger.fine(this + " Cancelled: nothing to do for " + t);
          }
        }
        else {
          thr = t;
        }
      } finally {
        if (resetOldLevel) {
          LocalRegion.setThreadInitLevelRequirement(oldLevel);
        }
        if (sendReply) {
          ReplyException rex = null;
          if (thr != null) {
            rex = new ReplyException(thr);
          }
          sendReply(getSender(), processorId, rex, getReplySender(dm));
        } else if (thr != null) {
          if (logger.fineEnabled()) {
            logger
                .error(
                    LocalizedStrings.DistributedCacheOperation_IN__0_PROCESS_GOT_EXCEPTION_NO_ACK,
                    getClass().getName(), thr);
          }
        }
        EntryLogger.clearSource();
      }
    }

    protected void basicProcess(DistributionManager dm, LocalRegion lclRgn) {
      Throwable thr = null;
      LogWriterI18n logger = dm.getLoggerI18n();
      boolean sendReply = true;
      InternalCacheEvent event = null;

      if (logger.finerEnabled()) {
        logger.finer("DistributedCacheOperation.basicProcess: " + this);
      }
      try {
        // LocalRegion lclRgn = getRegionFromPath(dm.getSystem(),
        // this.regionPath);
        if (lclRgn == null) {
          this.closed = true;
          if (logger.fineEnabled())
            logger.fine(this + " region not found, nothing to do");
          return;
        }
        //Could this cause a deadlock, because this can block a P2P reader
        //thread which might be needed to read the create region reply?? 
        lclRgn.waitOnInitialization();
        // In some subclasses, lclRgn may be destroyed, so be careful not to
        // allow a RegionDestroyedException to be thrown on lclRgn access
        if (lclRgn.scope.isLocal()) {
          if (logger.fineEnabled())
            logger.fine(this + " local scope region, nothing to do");
          return;
        }
        DistributedRegion rgn = (DistributedRegion)lclRgn;

        // check to see if the region is in recovery mode, in which case
        // we only operate if this is a DestroyRegion operation
        if (rgn.getImageState().getInRecovery()) {
          return;
        }

        event = createEvent(rgn);
        try {
        final boolean isEntry = event.getOperation().isEntry();

        if (isEntry) {
          // set the locking policy for the operation
          final EntryEventImpl entryEvent = (EntryEventImpl)event;
          entryEvent.setLockingPolicy(getLockingPolicy());
          // no TX state for DistributedCacheOperations
          // Suranjan: There will be for snapshot operations
          // set it in particular operation
          entryEvent.setTXState(null);
          if (this.possibleDuplicate) {
            entryEvent.setPossibleDuplicate(true);
            // If the state of the initial image yet to be received is unknown,
            // we must not apply the event. It may already be reflected in the
            // initial image state and, in fact, have been modified by
            // subsequent
            // events. This code path could be modified to pass the event to
            // listeners and bridges, but it should not apply the change to the
            // region
            if (!rgn.isEventTrackerInitialized()
                && (rgn.getDataPolicy().withReplication() || rgn
                    .getDataPolicy().withPreloaded())) {
              if (DistributionManager.VERBOSE || BridgeServerImpl.VERBOSE) {
                dm.getLoggerI18n().info(LocalizedStrings.DEBUG,
                    "Ignoring possible duplicate event");
              }
              return;
            }
          }
        }

        sendReply = operateOnRegion(event, dm) && sendReply;
        } finally {
          if (event instanceof EntryEventImpl) {
            ((EntryEventImpl) event).release();
          }
        }
      } catch (RegionDestroyedException e) {
        this.closed = true;
        logger.fine(this + " Region destroyed: nothing to do");
      } catch (CancelException e) {
        this.closed = true;
        logger.fine(this + " Cancelled: nothing to do");
      } catch (DiskAccessException e) {
        this.closed = true;
        if(!lclRgn.isDestroyed()) {
          logger.error(LocalizedStrings.ONE_ARG,
              "Got disk access exception, expected region to be destroyed", e);
        }
      } catch (EntryNotFoundException e) {
        this.appliedOperation = true;
        if(lclRgn.getGemFireCache().isGFXDSystem()) {
          ReplyException re = new ReplyException(e);
          sendReply = false;
          sendReply(getSender(), processorId, re, getReplySender(dm));   
        }else {
          logger.fine(this + " Entry not found, nothing to do");
        }
        
      } catch (InvalidDeltaException ide) {
        ReplyException re = new ReplyException(ide);
        sendReply = false;
        sendReply(getSender(), processorId, re, getReplySender(dm));
        lclRgn.getCachePerfStats().incDeltaFullValuesRequested();
      } catch (Throwable t) {
        Error err;
        if (t instanceof Error && SystemFailure.isJVMFailureError(
            err = (Error)t)) {
          SystemFailure.initiateFailure(err);
          // If this ever returns, rethrow the error. We're poisoned
          // now, so don't let this thread continue.
          throw err;
        }
        // Whenever you catch Error or Throwable, you must also
        // check for fatal JVM error (see above). However, there is
        // _still_ a possibility that you are dealing with a cascading
        // error condition, so you also need to check to see if the JVM
        // is still usable:
        SystemFailure.checkFailure();
        thr = t;
      } finally {
        if (event != null) {
          checkVersionIsRecorded(this.versionTag, lclRgn,
              event.getOperation().isEntry() ? (EntryEventImpl)event : null);
        }
        if (sendReply) {
          // logger.fine("basicProcess: <" + this + ">: sending reply");
          ReplyException rex = null;
          if (thr != null) {
            rex = new ReplyException(thr);
          }
          sendReply(getSender(), processorId, rex, getReplySender(dm));
        } else if (thr != null) {
          logger
              .error(
                  LocalizedStrings.DistributedCacheOperation_EXCEPTION_OCCURRED_WHILE_PROCESSING__0,
                  this, thr);
        }
        // else {
        // logger.fine("basicProcess: <" + this + ">: bit-bucketed");
        // }
      } // finally
    }

    public void sendReply(InternalDistributedMember recipient, int pId,
        ReplyException rex, ReplySender dm) {
      if (pId == 0 && (dm instanceof DM) && !this.directAck) {//Fix for #41871
        // distributed-no-ack message.  Don't respond 
      }
      else {
        ReplyException exception = rex;
        ReplyMessage.send(recipient, pId, exception, dm,
            !this.appliedOperation, this.closed, this, false, isInternal());
      }
    }

    /**
     * Ensure that a version tag has been recorded in the region's version vector.
     * This makes note that the event has been received and processed but probably
     * didn't affect the cache's state or it would have been recorded earlier.
     * 
     * @param tag the version information
     * @param r the affected region
     */
    public void checkVersionIsRecorded(VersionTag tag, LocalRegion r, EntryEventImpl event) {
      if (tag != null && !tag.isRecorded()) { // oops - someone forgot to record the event
        if (r != null) {
          RegionVersionVector v = r.getVersionVector();
          if (v != null) {
            VersionSource mbr = tag.getMemberID();
            if (mbr == null) {
              mbr = getSender();
            }
            if (r.getLogWriterI18n().finerEnabled()) {
              r.getLogWriterI18n().finer("recording version tag in RVV in basicProcess since it wasn't done earlier");
            }
            v.recordVersion(mbr, tag, event);
          }
        }
      }
    }
    
    /**
     * When an event is discarded because of an attempt to overwrite a more
     * recent change we still need to deliver that event to clients.  Clients
     * can then perform their own concurrency checks on the event.
     * 
     * @param rgn
     * @param ev
     */
    protected void dispatchElidedEvent(LocalRegion rgn, EntryEventImpl ev) {
      if (rgn.getLogWriterI18n().fineEnabled()) {
        rgn.getLogWriterI18n().fine("dispatching elided event: " + ev);
      }
      ev.isConcurrencyConflict(true);
      if (this.needsRouting) {
        rgn.generateLocalFilterRouting(ev);
      }
      rgn.notifyBridgeClients(ev);
    }

    // protected LocalRegion getRegionFromPath(InternalDistributedSystem sys,
    // String path) {
    // return LocalRegion.getRegionFromPath(sys, path);
    // }

    protected abstract InternalCacheEvent createEvent(DistributedRegion rgn)
        throws EntryNotFoundException;

    /** Return true if a reply should be sent */
    protected abstract boolean operateOnRegion(CacheEvent event,
        DistributionManager dm) throws EntryNotFoundException;

    // override and extend in subclasses
    @Override
    protected void appendFields(StringBuilder buff) {
      buff.append("; region path='"); // make sure this is the first one
      buff.append(this.regionPath);
      buff.append("'");
      buff.append("; sender=");
      buff.append(getSender());
      buff.append("; callbackArg=");
      buff.append(this.callbackArg);
      buff.append("; op=");
      buff.append(this.op);
      buff.append("; applied=");
      buff.append(this.appliedOperation);
      buff.append("; directAck=");
      buff.append(this.directAck);
      buff.append("; posdup=");
      buff.append(this.possibleDuplicate);
      buff.append("; hasDelta=");
      buff.append(this.hasDelta);
      buff.append("; hasOldValue=");
      buff.append(this.hasOldValue);
      if (this.versionTag != null) {
        buff.append("; version=");
        buff.append(this.versionTag);
      }
      if (this.filterRouting != null) {
        buff.append(" ");
        buff.append(this.filterRouting.toString());
      }
    }

    @Override
    public void fromData(DataInput in) throws IOException,
            ClassNotFoundException {
      super.fromData(in);
      short bits = this.flags;
      short extBits = 0;
      // extra short added for 7.5
      Version version = InternalDataSerializer
              .getVersionForDataStreamOrNull(in);
      if (version == null || Version.GFE_75.compareTo(version) <= 0) {
        extBits = in.readShort();
      }
      this.regionPath = DataSerializer.readString(in);
      this.op = Operation.fromOrdinal(in.readByte());
      // TODO dirack There's really no reason to send this flag across the wire
      // anymore
      this.directAck = (bits & DIRECT_ACK_MASK) != 0;
      this.possibleDuplicate = (bits & POSSIBLE_DUPLICATE_MASK) != 0;
      if ((bits & CALLBACK_ARG_MASK) != 0) {
        this.callbackArg = DataSerializer.readObject(in);
      }
      this.hasDelta = (bits & DELTA_MASK) != 0;
      this.hasOldValue = (bits & OLD_VALUE_MASK) != 0;
      if (this.hasOldValue) {
        this.oldValueIsObject = in.readByte();
        if (this.oldValueIsObject == VALUE_IS_OBJECT) {
          this.oldValue = DataSerializer.readObject(in);
        }
        else {
          this.oldValue = DataSerializer.readByteArray(in);
        }
      }
      boolean hasFilterInfo = (bits & FILTER_INFO_MASK) != 0;
      this.needsRouting = (bits & NEEDS_ROUTING_MASK) != 0;
      if (hasFilterInfo) {
        this.filterRouting = new FilterRoutingInfo();
        InternalDataSerializer.invokeFromData(this.filterRouting, in);
      }
      if ((bits & VERSION_TAG_MASK) != 0) {
        boolean persistentTag = (bits & PERSISTENT_TAG_MASK) != 0;
        this.versionTag = VersionTag.create(persistentTag, in);
      }
      this.inhibitAllNotifications =
          ((extBits & INHIBIT_NOTIFICATIONS_MASK) != 0);
      if (this instanceof PutAllMessage) {
        ((PutAllMessage) this).setFetchFromHDFS((extBits & FETCH_FROM_HDFS) != 0);
        if(version!= null && (Version.GFXD_10.compareTo(version) == 0)) {
          ((PutAllMessage) this).setPutDML((extBits & FETCH_FROM_HDFS) == 0);
        }
        else {
          ((PutAllMessage) this).setPutDML((extBits & IS_PUT_DML) != 0);
        }
      }
      if (this.acker == null) {
        // avoid further messaging on shared P2P reader thread on secondaries
        // else it can lead to deadlocks
        boolean inlineProcess = (this.processorType == 0);
        GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        if (cache != null) {
          this.lclRgn = cache.getRegionByPath(this.regionPath, false);
          if (this.lclRgn instanceof BucketRegion) {
            inlineProcess = true;
            // don't try inline processing if there is a WAN queue update
            // that needs to be done from this thread
            BucketRegion breg = (BucketRegion)this.lclRgn;
            PartitionedRegion pr = breg.getPartitionedRegion();
            Set<String> gatewaySenderIds = pr.getGatewaySenderIds();
            if (gatewaySenderIds != null && gatewaySenderIds.size() > 0) {
              for (Object gs : cache.getGatewaySenders()) {
                AbstractGatewaySender gatewaySender = (AbstractGatewaySender)gs;
                if (gatewaySender != null
                    && gatewaySenderIds.contains(gatewaySender.getId())
                    && gatewaySender.isPrimary()) {
                  inlineProcess = false;
                  break;
                }
              }
            }
            if (inlineProcess) {
              // don't try inline processing if it is an operation on the global
              // index region
              if (cache.isGFXDSystem()) {
                // If lclRegion is a pr bucket region then absence of indexUpdater
                // will mean that this is a bucket for a global index region and in
                // that case let's avoid inline processing. For app table bucket region
                // let's not avoid inline processing as this distribution has come to a
                // secondary and there are no remote operations expected when applying 
                // this op here
                if (this.lclRgn.getIndexUpdater() == null) {
                  inlineProcess = false;
                }
              }
            }
          }
        }
        // skip processorType for PR primary to secondary distribution since
        // will not have a deadlock issue and there is a performance
        // penalty in using WAITING_POOL_EXECUTOR (#46360)
        if (inlineProcess) {
          this.processorType = 0;
        }
        else if (this.processorType == 0) {
          setProcessorType(true);
        }
      }
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      super.toData(out);
      // extra short added for 7.5
      Version version = InternalDataSerializer
              .getVersionForDataStreamOrNull(out);
      if (version == null || Version.GFE_75.compareTo(version) <= 0) {
        short extBits = computeCompressedExtBits((short) 0);
        out.writeShort(extBits);
      }
      DataSerializer.writeString(this.regionPath, out);
      out.writeByte(this.op.ordinal);
      if (this.callbackArg != null) {
        DataSerializer.writeObject(this.callbackArg, out);
      }
      if (this.hasOldValue) {
        out.writeByte(this.oldValueIsObject);
        final byte policy = valueIsToDeserializationPolicy(this.oldValueIsObject);
        final Object vObj;
        final byte[] vBytes;
        if (this.oldValueIsObject == VALUE_IS_BYTES && this.oldValue instanceof byte[]) {
          vObj = null;
          vBytes = (byte[])this.oldValue;
        } else {
          vObj = this.oldValue;
          vBytes = null;
        }
        writeValue(policy, vObj, vBytes, out);
      }
      if (this.filterRouting != null) {
        InternalDataSerializer.invokeToData(this.filterRouting, out);
      }
      if (this.versionTag != null) {
        InternalDataSerializer.invokeToData(this.versionTag,out);
      }
    }

    protected short computeCompressedShort(short bits) {
      if (this.hasOldValue) {
        bits |= OLD_VALUE_MASK;
      }
      if (this.directAck) {
        bits |= DIRECT_ACK_MASK;
      }
      if (this.possibleDuplicate) {
        bits |= POSSIBLE_DUPLICATE_MASK;
      }
      if (this.callbackArg != null) {
        bits |= CALLBACK_ARG_MASK;
      }
      if (this.hasDelta) {
        bits |= DELTA_MASK;
      }
      if (this.filterRouting != null) {
        bits |= FILTER_INFO_MASK;
      }
      if (this.needsRouting) {
        bits |= NEEDS_ROUTING_MASK;
      }
      if (this.versionTag != null) {
        bits |= VERSION_TAG_MASK;
        if (this.versionTag instanceof DiskVersionTag) {
          bits |= PERSISTENT_TAG_MASK;
        }
      }
      return bits;
    }

    protected short computeCompressedExtBits(short bits) {
      if (inhibitAllNotifications) {
        bits |= INHIBIT_NOTIFICATIONS_MASK;
      }
      return bits;
    }

    public final boolean supportsDirectAck() {
      return this.directAck;
    }

    // ////////////////////////////////////////////////////////////////////
    // ReliableDistributionData methods
    // ////////////////////////////////////////////////////////////////////

    public int getOperationCount() {
      return 1;
    }

    public List getOperations() {
      byte noDeserialize = DistributedCacheOperation.DESERIALIZATION_POLICY_NONE;
      QueuedOperation qOp = new QueuedOperation(getOperation(), null, null,
          null, noDeserialize, this.callbackArg);
      return Collections.singletonList(qOp);
    }

    public void setSendDelta(boolean sendDelta) {
      this.sendDelta = sendDelta;
    }

    /**
     * @see DistributionMessage#getProcessorType()
     */
    @Override
    public final int getProcessorType() {
      /*
      // don't use SERIAL_EXECUTOR if we may have to wait for a pending TX
      // else if may deadlock as the p2p msg reader thread will be blocked
      final IndexUpdater indexUpdater;
      if (this.pendingTXId != null
          || ((indexUpdater = region.getIndexUpdater()) != null && indexUpdater
              .avoidSerialExecutor(this.op))) {
        // ordering is not an issue here since these ops are always transactional
        this.processorType = DistributionManager.STANDARD_EXECUTOR;
      }
      */
      // for updates avoid SERIAL_EXECUTOR due to potentially expensive ops
      // in further distributions or listener invocations
      return this.processorType == 0 ? getMessageProcessorType()
          : this.processorType;
    }

    protected int getMessageProcessorType() {
      // by default try inline processing for better performance
      return DistributionManager.SERIAL_EXECUTOR;
    }

    @Override
    public void setProcessorType(boolean isReaderThread) {
      if (isReaderThread) {
        // if this thread is a pool processor thread then use
        // WAITING_POOL_EXECUTOR to avoid deadlocks (#42459, #44913)
        this.processorType = DistributionManager.WAITING_POOL_EXECUTOR;
      }
    }

    @Override
    public boolean prefersOldSerialized() {
      return true;
    }

    @Override
    public boolean isUnretainedOldReferenceOk() {
      return true;
    }

    @Override
    public boolean isCachedDeserializableValueOk() {
      return false;
    }
    
    private void setOldValueIsObject(boolean isSerialized) {
      if (isSerialized) {
        if (CachedDeserializableFactory.preferObject()) {
          this.oldValueIsObject = VALUE_IS_OBJECT;
        } else {
          this.oldValueIsObject = VALUE_IS_SERIALIZED_OBJECT;
        }
      } else {
        this.oldValueIsObject = VALUE_IS_BYTES;
      }
    }

    @Override
    public void importOldObject(Object ov, boolean isSerialized) {
      setOldValueIsObject(isSerialized);
      this.oldValue = ov;
      this.hasOldValue = true;
    }

    @Override
    public void importOldBytes(byte[] ov, boolean isSerialized) {
      setOldValueIsObject(isSerialized);
      this.oldValue = ov;
      this.hasOldValue = true;
    }
  }

  /** Custom subclass that keeps all ReplyExceptions */
  static private class ReliableCacheReplyProcessor extends
      CacheOperationReplyProcessor {

    private final Set failedMembers;

    private final DM dm;

    public ReliableCacheReplyProcessor(InternalDistributedSystem system,
        Collection initMembers, Set departedMembers) {
      super(system, initMembers);
      this.dm = system.getDistributionManager();
      this.failedMembers = departedMembers;
    }

    @Override
    protected synchronized void processException(DistributionMessage dmsg,
        ReplyException ex) {
      Throwable cause = ex.getCause();
      // only interested in CacheClosedException and RegionDestroyedException
      if (cause instanceof CancelException
          || cause instanceof RegionDestroyedException) {
        this.failedMembers.add(dmsg.getSender());
      } else {
        // allow superclass to handle all other exceptions
        this.failedMembers.add(dmsg.getSender());
        super.processException(dmsg, ex);
      }
    }

    @Override
    protected void process(DistributionMessage dmsg, boolean warn) {
      if (dmsg instanceof ReplyMessage && ((ReplyMessage)dmsg).getIgnored()) {
        if (this.dm.getLoggerI18n().fineEnabled()) {
          this.dm.getLoggerI18n().fine(
              dmsg.getSender() + " replied with ignored true");
        }
        this.failedMembers.add(dmsg.getSender());
      }
      super.process(dmsg, warn);
    }
  }

  static class CacheOperationReplyProcessor extends DirectReplyProcessor {
    public CacheOperationMessage msg;
    
    public CopyOnWriteHashSet<InternalDistributedMember> closedMembers = new CopyOnWriteHashSet<InternalDistributedMember>();
    
    public CacheOperationReplyProcessor(InternalDistributedSystem system,
        Collection initMembers) {
      super(system, initMembers);
    }

    @Override
    protected void process(final DistributionMessage dmsg, boolean warn) {
      if (dmsg instanceof ReplyMessage) {
        ReplyMessage replyMessage =(ReplyMessage)dmsg;
        if (msg != null) {
          boolean discard = !msg.processReply(replyMessage, this);
          if (discard) {
            return;
          }
        }
        final ReplyException ex;
        if (replyMessage.getClosed()
            // we may get a CancelException from remote node from code areas
            // hit even before (or just after) the message's operateOnRegion
            // method (#43242)
            || ((ex = replyMessage.getException()) != null
                 && ex.getCause() instanceof CancelException)) {
          closedMembers.add(replyMessage.getSender());
        }
      }

      super.process(dmsg, warn);
    }

    @Override
    public void memberDeparted(final InternalDistributedMember id,
        final boolean crashed) {
      if (removeMember(id, true)) {
        // add to closed members
        this.closedMembers.add(id);
      }
      checkIfDone();
    }
  }
}
