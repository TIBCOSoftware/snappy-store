package com.gemstone.gemfire.internal.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.IsolationLevel;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.UnsupportedOperationInTransactionException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.locks.NonReentrantLock;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientProxyMembershipID;
import com.gemstone.gemfire.internal.cache.tier.sockets.VersionedObjectList;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.concurrent.CustomEntryConcurrentHashMap;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gnu.trove.THash;

/**
 * No need to implement all of TXStateInterface.
 * It just maintains the oldEntryMap
 * and snapshot.
 */
public class SnapshotTxState implements TXStateInterface {

  private final ConcurrentTHashSet<TXRegionState> regions;

  Map<String, Map<VersionSource,RegionVersionHolder>> snapshot;

  protected CustomEntryConcurrentHashMap<Object, Object/*RegionEntry*/> oldEntryMap;

  private final TXManagerImpl txManager;

  private final TXStateProxy proxy;

  final TXId txId;

  private final LockingPolicy lockPolicy;


  //TODO: Unused kept it till testing is passed
  public void addOldEntry(RegionEntry oldRe) {
    this.oldEntryMap.put(oldRe.getKey(), oldRe);
  }

  /**
   * Should only be created by {@link TXStateProxy}.
   */
  SnapshotTxState(final TXStateProxy proxy) {
    // don't care about concurrency here; just want to make the map thread-safe
    // using CM rather than synchronized maps to avoid read contention
    this.regions = new ConcurrentTHashSet<TXRegionState>(1, 3,
        THash.DEFAULT_LOAD_FACTOR, TXState.compareTXRS, null);
    this.txManager = proxy.getTxMgr();
    this.proxy = proxy;
    this.txId = proxy.getTransactionId();
    this.lockPolicy = proxy.getLockingPolicy();
    // dummy head

    if (getCache().snaphshotEnabled() && (lockPolicy == LockingPolicy.SNAPSHOT)) {
      this.snapshot = getCache().getSnapshotRVV();
    } else {
      this.snapshot = null;
    }

    this.oldEntryMap = new CustomEntryConcurrentHashMap<>();

    if (TXStateProxy.LOG_FINE) {
      this.txManager.getLogger().info(LocalizedStrings.DEBUG,
          toString() + ": created.");
    }
  }

  @Override
  public TXId getTransactionId() {
    return this.txId;
  }

  @Override
  public LockingPolicy getLockingPolicy() {
    return lockPolicy;
  }

  @Override
  public IsolationLevel getIsolationLevel() {
    return this.lockPolicy.getIsolationLevel();
  }

  @Override
  public TXState getLocalTXState() {
    return null;
  }

  @Override
  public TXState getTXStateForWrite() {
    return null;
  }

  @Override
  public TXState getTXStateForRead() {
    return null;
  }

  @Override
  public TXStateProxy getProxy() {
    return this.proxy;
  }

  @Override
  public TXRegionState readRegion(LocalRegion r) {
    return null;
  }

  @Override
  public long getBeginTime() {
    return 0;
  }

  @Override
  public int getChanges() {
    return 0;
  }

  @Override
  public boolean isInProgress() {
    return false;
  }

  @Override
  public void commit(Object callbackArg) throws TransactionException {

  }

  @Override
  public void rollback(Object callbackArg) {

  }

  @Override
  public GemFireCacheImpl getCache() {
    return null;
  }

  @Override
  public Collection<LocalRegion> getRegions() {
    return null;
  }

  @Override
  public InternalDistributedMember getCoordinator() {
    return null;
  }

  @Override
  public boolean isCoordinator() {
    return false;
  }

  @Override
  public long getCommitTime() {
    return 0;
  }

  @Override
  public TXEvent getEvent() {
    return null;
  }

  @Override
  public boolean txPutEntry(EntryEventImpl event, boolean ifNew, boolean requireOldValue,
      boolean checkResources, Object expectedOldValue) {
    return false;
  }

  @Override
  public void rmRegion(LocalRegion r) {

  }

  @Override
  public boolean isFireCallbacks() {
    return false;
  }

  @Override
  public void cleanupCachedLocalState(boolean hasListeners) {

  }

  @Override
  public void flushPendingOps(DM dm) {

  }

  @Override
  public boolean isJTA() {
    return false;
  }

  @Override
  public TXManagerImpl getTxMgr() {
    return null;
  }

  @Override
  public void setObserver(TransactionObserver observer) {

  }

  @Override
  public TransactionObserver getObserver() {
    return null;
  }

  @Override
  public Object lockEntryForRead(RegionEntry entry, Object key,
      LocalRegion dataRegion,
      int context,
      boolean allowTombstones, LockingPolicy.ReadEntryUnderLock reader) {
    return null;
  }

  @Override
  public Object lockEntry(RegionEntry entry, Object key,
      Object callbackArg,
      LocalRegion region,
      LocalRegion dataRegion,
      boolean writeMode, boolean allowReadFromHDFS, byte opType, int failureFlags) {
    return null;
  }

  @Override
  public void setExecutionSequence(int execSeq) {

  }

  @Override
  public int getExecutionSequence() {
    return 0;
  }

  @Override
  public Object getDeserializedValue(Object key, Object callbackArg, LocalRegion localRegion,
      boolean updateStats, boolean disableCopyOnRead, boolean preferCD, TXStateInterface lockState,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS) {
    return null;
  }

  @Override
  public Object getLocally(Object key, Object callbackArg, int bucketId, LocalRegion localRegion,
      boolean doNotLockEntry, boolean localExecution, TXStateInterface lockState, EntryEventImpl clientEvent,
      boolean returnTombstones, boolean allowReadFromHDFS) throws DataLocationException {
    return null;
  }

  @Override
  public void destroyExistingEntry(EntryEventImpl event, boolean cacheWrite, Object expectedOldValue)
      throws EntryNotFoundException {

  }

  @Override
  public void invalidateExistingEntry(EntryEventImpl event, boolean invokeCallbacks, boolean forceNewEntry) {

  }

  @Override
  public int entryCount(LocalRegion localRegion) {
    return 0;
  }

  @Override
  public Object getValueInVM(Object key, Object callbackArg, LocalRegion localRegion) {
    return null;
  }

  @Override
  public boolean containsKey(Object key, Object callbackArg, LocalRegion localRegion) {
    return false;
  }

  @Override
  public boolean containsKeyWithReadLock(Object key, Object callbackArg, LocalRegion localRegion) {
    return false;
  }

  @Override
  public boolean containsValueForKey(Object key, Object callbackArg, LocalRegion localRegion) {
    return false;
  }

  @Override
  public Region.Entry<?, ?> getEntry(Object key, Object callbackArg, LocalRegion localRegion,
      boolean allowTombstones) {
    return null;
  }

  @Override
  public EntrySnapshot getEntryOnRemote(KeyInfo keyInfo, LocalRegion localRegion, boolean allowTombstones)
      throws DataLocationException {
    return null;
  }

  @Override
  public boolean putEntry(EntryEventImpl event, boolean ifNew, boolean ifOld, Object expectedOldValue,
      boolean requireOldValue, boolean cacheWrite, long lastModified, boolean overwriteDestroyed) {
    return false;
  }

  @Override
  public boolean putEntryOnRemote(EntryEventImpl event, boolean ifNew, boolean ifOld,
      Object expectedOldValue, boolean requireOldValue, boolean cacheWrite,
      long lastModified, boolean overwriteDestroyed) throws DataLocationException {
    return false;
  }

  @Override
  public void destroyOnRemote(EntryEventImpl event, boolean cacheWrite, Object expectedOldValue)
      throws DataLocationException {

  }

  @Override
  public void invalidateOnRemote(EntryEventImpl event, boolean invokeCallbacks,
      boolean forceNewEntry) throws DataLocationException {

  }

  @Override
  public boolean isDeferredStats() {
    return false;
  }

  @Override
  public Object findObject(KeyInfo key, LocalRegion r, boolean isCreate, boolean generateCallbacks,
      Object value, boolean disableCopyOnRead, boolean preferCD, ClientProxyMembershipID requestingClient,
      EntryEventImpl clientEvent, boolean returnTombstones, boolean allowReadFromHDFS) {
    return null;
  }

  @Override
  public Region.Entry<?, ?> getEntryForIterator(KeyInfo key, LocalRegion currRgn, boolean allowTombstones) {
    return null;
  }

  @Override
  public Object getValueForIterator(KeyInfo key, LocalRegion currRgn, boolean updateStats, boolean preferCD,
      EntryEventImpl clientEvent, boolean allowTombstones) {
    return null;
  }

  @Override
  public Object getKeyForIterator(KeyInfo keyInfo, LocalRegion currRgn, boolean allowTombstones) {
    return null;
  }

  @Override
  public Object getKeyForIterator(Object key, LocalRegion currRgn) {
    return null;
  }

  @Override
  public Collection<?> getAdditionalKeysForIterator(LocalRegion currRgn) {
    return null;
  }

  @Override
  public Iterator<?> getRegionKeysForIteration(LocalRegion currRegion, boolean includeValues) {
    return null;
  }

  @Override
  public Iterator<?> getLocalEntriesIterator(InternalRegionFunctionContext context, boolean primaryOnly
      , boolean forUpdate, boolean includeValues, LocalRegion currRegion) {
    return null;
  }

  @Override
  public Iterator<?> getLocalEntriesIterator(Set<Integer> bucketSet, boolean primaryOnly, boolean forUpdate,
      boolean includeValues, LocalRegion currRegion, boolean fetchRemote) {
    return null;
  }

  @Override
  public Object getSerializedValue(LocalRegion localRegion, KeyInfo key, boolean doNotLockEntry,
      ClientProxyMembershipID requestingClient, EntryEventImpl clientEvent, boolean returnTombstones,
      boolean allowReadFromHDFS) throws DataLocationException {
    return null;
  }

  @Override
  public void checkSupportsRegionDestroy() throws UnsupportedOperationInTransactionException {

  }

  @Override
  public void checkSupportsRegionInvalidate() throws UnsupportedOperationInTransactionException {

  }

  @Override
  public Set<?> getBucketKeys(LocalRegion localRegion, int bucketId, boolean allowTombstones) {
    return null;
  }

  @Override
  public void postPutAll(DistributedPutAllOperation putallOp, VersionedObjectList successfulPuts, LocalRegion region) {

  }

  @Override
  public Region.Entry<?, ?> accessEntry(Object key, Object callbackArg, LocalRegion localRegion) {
    return null;
  }

  @Override
  public void updateEntryVersion(EntryEventImpl event) throws EntryNotFoundException {

  }

  public boolean isSnapshot() {
    return getLockingPolicy() == LockingPolicy.SNAPSHOT;
  }

  @Override
  public void recordVersionForSnapshot(Object member, long version, Region region) {

  }

}
