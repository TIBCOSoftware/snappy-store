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

package com.gemstone.gemfire.internal.cache;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.internal.FileUtil;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl.OplogEntryIdSet;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreFilter;
import com.gemstone.gemfire.internal.cache.persistence.OplogType;
import com.gemstone.gemfire.internal.cache.persistence.PRPersistentConfig;

import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.sequencelog.EntryLogger;
import com.gemstone.gnu.trove.TLongHashSet;

public class PersistentOplogSet implements OplogSet {
  
  /** The active oplog * */
  protected volatile Oplog child;
  
  /** variable to generate sequential unique oplogEntryId's* */
  private final AtomicLong oplogEntryId = new AtomicLong(DiskStoreImpl.INVALID_ID);
  
  /** counter used for round-robin logic * */
  int dirCounter = -1;

  /**
   * Contains all the oplogs that only have a drf (i.e. the crf has been deleted).
   */
  final Map<Long, Oplog> drfOnlyOplogs = new LinkedHashMap<Long, Oplog>();

  /** oplogs that are ready to compact */
  final Map<Long, Oplog> oplogIdToOplog = new LinkedHashMap<Long, Oplog>();
  /** oplogs that are done being written to but not yet ready to compact */
  private final Map<Long, Oplog> inactiveOplogs = new LinkedHashMap<Long, Oplog>(16, 0.75f, true);
  
  private final DiskStoreImpl parent;
  
  final AtomicInteger inactiveOpenCount = new AtomicInteger();
  
  private final Map<Long, DiskRecoveryStore> pendingRecoveryMap
  = new HashMap<Long, DiskRecoveryStore>();
  private final Map<Long, DiskRecoveryStore> currentRecoveryMap
  = new HashMap<Long, DiskRecoveryStore>();

  final AtomicBoolean alreadyRecoveredOnce = new AtomicBoolean(false);

  /**
   * The maximum oplog id we saw while recovering
   */
  private volatile long maxRecoveredOplogId = 0;

  
  public PersistentOplogSet(DiskStoreImpl parent) {
    this.parent = parent;
  }
  
  /**
   * returns the active child
   */
  public final Oplog getChild() {
    return this.child;
  }
  
  /**
   * set the child to a new oplog
   *
   */
  void setChild(Oplog oplog) {
    this.child = oplog;
//     oplogSetAdd(oplog);
  }
  
  public Oplog[] getAllOplogs() {
    synchronized (this.oplogIdToOplog) {
      int rollNum = this.oplogIdToOplog.size();
      int inactiveNum = this.inactiveOplogs.size();
      int drfOnlyNum = this.drfOnlyOplogs.size();
      int num = rollNum + inactiveNum + drfOnlyNum + 1;
      Oplog[] oplogs = new Oplog[num];
      oplogs[0] = getChild();
      {
        Iterator<Oplog> itr = this.oplogIdToOplog.values().iterator();
        for (int i = 1; i <= rollNum; i++) {
          oplogs[i] = itr.next();
        }
      }
      {
        Iterator<Oplog> itr = this.inactiveOplogs.values().iterator();
        for (int i = 1; i <= inactiveNum; i++) {
          oplogs[i+rollNum] = itr.next();
        }
      }
      {
        Iterator<Oplog> itr = this.drfOnlyOplogs.values().iterator();
        for (int i = 1; i <= drfOnlyNum; i++) {
          oplogs[i+rollNum+inactiveNum] = itr.next();
        }
      }

      //Special case - no oplogs found
      if(oplogs.length == 1 && oplogs[0] == null) {
        return new Oplog[0];
      }
      return oplogs;
    }
    
  }
  
  public TreeSet<Oplog> getSortedOplogs() {
    TreeSet<Oplog> result = new TreeSet<Oplog>(new Comparator() {
        public int compare(Object arg0, Object arg1) {
          return Long.signum(((Oplog)arg1).getOplogId() - ((Oplog)arg0).getOplogId());
        }
      });
    for (Oplog oplog: getAllOplogs()) {
      if (oplog != null) {
        result.add(oplog);
      }
    }
    return result;
  }

  /**
   * Get the oplog specified
   *
   * @param id
   *          int oplogId to be got
   * @return Oplogs the oplog corresponding to the oplodId, id
   */
  public Oplog getChild(long id) {
    Oplog localOplog = this.child;
    if (localOplog != null && id == localOplog.getOplogId()) {
      return localOplog;
    } else {
      Long key = Long.valueOf(id);
      synchronized (this.oplogIdToOplog) {
        Oplog result = oplogIdToOplog.get(key);
        if (result == null) {
          result = inactiveOplogs.get(key);
        }
        return result;
      }
    }
  }
  
  @Override
  public void create(LocalRegion region, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, boolean async) {
    getChild().create(region, entry, value, async);
  }
  
  @Override
  public void modify(LocalRegion region, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, boolean async) {
    getChild().modify(region, entry, value, async);
  }

  @Override
  public void remove(LocalRegion region, DiskEntry entry, boolean async,
      boolean isClear) {
    getChild().remove(region, entry, async, isClear);
  }
  
  public void forceRoll(DiskRegion dr) {
    Oplog child = getChild();
    if (child != null) {
      child.forceRolling(dr, false);
    }
  }
  
  public Map<File, DirectoryHolder> findFiles(String partialFileName) {
    this.dirCounter = 0;
    Map<File, DirectoryHolder> backupFiles = new HashMap<File, DirectoryHolder>();
    FilenameFilter backupFileFilter = getFileNameFilter(partialFileName);
    for (DirectoryHolder dh: parent.directories) {
      File dir = dh.getDir();
      File[] backupList = FileUtil.listFiles(dir, backupFileFilter);
      for (File f: backupList) {
        backupFiles.put(f, dh);
      }
    }
    
    return backupFiles;
  }

  protected FilenameFilter getFileNameFilter(String partialFileName) {
    return new DiskStoreFilter(OplogType.BACKUP, false, partialFileName);
  }
  
  public void createOplogs(boolean needsOplogs,
      Map<File, DirectoryHolder> backupFiles) {
    TLongHashSet foundCrfs = new TLongHashSet();
    TLongHashSet foundDrfs = new TLongHashSet();
    

    for (Map.Entry<File, DirectoryHolder> entry: backupFiles.entrySet()) {
      File file = entry.getKey();
      String absolutePath = file.getAbsolutePath();
      int underscorePosition = absolutePath.lastIndexOf("_");
      int pointPosition = absolutePath.indexOf(".", underscorePosition);
      String opid = absolutePath.substring(underscorePosition + 1,
                                           pointPosition);
      long oplogId = Long.parseLong(opid);
      maxRecoveredOplogId = Math.max(maxRecoveredOplogId, oplogId);
      //here look diskinit file and check if this opid already deleted or not
      //if deleted then don't process it.
      if(Oplog.isCRFFile(file.getName())) {
        if(!isCrfOplogIdPresent(oplogId)) {            
          deleteFileOnRecovery(file);
          try
          { 
            String krfFileName = Oplog.getKRFFilenameFromCRFFilename(file.getAbsolutePath());
            File krfFile = new File(krfFileName);
            deleteFileOnRecovery(krfFile);
          }catch(Exception ex) {//ignore              
          }
          continue; //this file we unable to delete earlier 
        }
      } else if(Oplog.isDRFFile(file.getName())) {
        if(!isDrfOplogIdPresent(oplogId)) {
          deleteFileOnRecovery(file);
          continue; //this file we unable to delete earlier 
        }
      } else if (Oplog.isIRFFile(file.getName())) {
        if(!isIrfOplogIdPresent(oplogId)) {
          deleteFileOnRecovery(file);
          continue;
        }
      }
      
      Oplog oplog = getChild(oplogId);
      if (oplog == null) {
        oplog = new Oplog(oplogId, this);
        //oplogSet.add(oplog);
        addRecoveredOplog(oplog);
      }
      oplog.addRecoveredFile(file, entry.getValue(), foundCrfs, foundDrfs);
    }
    if(needsOplogs) {
      verifyOplogs(foundCrfs, foundDrfs);
    }
  }

  protected boolean isDrfOplogIdPresent(long oplogId) {
    return parent.getDiskInitFile().isDRFOplogIdPresent(oplogId);
  }

  protected boolean isCrfOplogIdPresent(long oplogId) {
    return parent.getDiskInitFile().isCRFOplogIdPresent(oplogId);
  }
  
  protected boolean isIrfOplogIdPresent(long oplogId) {
    DiskInitFile initFile = parent.getDiskInitFile();
    return initFile.isCRFOplogIdPresent(oplogId) && initFile.hasKrf(oplogId) && initFile.hasIrf(oplogId);
  }

  protected void verifyOplogs(TLongHashSet foundCrfs, TLongHashSet foundDrfs) {
    parent.getDiskInitFile().verifyOplogs(foundCrfs, foundDrfs);
  }
  
  
  private void deleteFileOnRecovery(File f) {
    try {
      if(f.delete()) {
        parent.logger.info(LocalizedStrings.DiskStoreImpl_DELETE_ON_RECOVERY, new Object[] {f.getName(), parent.getName()});
      }
    }catch(Exception e) {
      //ignore, one more attempt to delete the file failed
    }
  }
  
  void addRecoveredOplog(Oplog oplog) {
    basicAddToBeCompacted(oplog);
    // don't schedule a compaction here. Wait for recovery to complete
  }
  
  /**
   * Taking a lock on the LinkedHashMap oplogIdToOplog as it the operation of
   * adding an Oplog to the Map & notifying the Compactor thread , if not already
   * compaction has to be an atomic operation. add the oplog to the to be compacted
   * set. if compactor thread is active and recovery is not going on then the
   * compactor thread is notified of the addition
   */
  void addToBeCompacted(Oplog oplog) {
    basicAddToBeCompacted(oplog);
    parent.scheduleCompaction();
  }
  private void basicAddToBeCompacted(Oplog oplog) {
    if (!oplog.isRecovering() && oplog.hasNoLiveValues()) {
      oplog.cancelKrf();
      oplog.close(); // fix for bug 41687
      oplog.deleteFiles(oplog.getHasDeletes());
    } else {
      int inactivePromotedCount = 0;
      parent.getStats().incCompactableOplogs(1);
      Long key = Long.valueOf(oplog.getOplogId());
      synchronized (this.oplogIdToOplog) {
        if (this.inactiveOplogs.remove(key) != null) {
          if (oplog.isRAFOpen()) {
            inactiveOpenCount.decrementAndGet();
          }
          inactivePromotedCount++;
        }
//         logger.info(LocalizedStrings.DEBUG, "DEBUG addToBeCompacted #" + oplog.getOplogId());
        this.oplogIdToOplog.put(key, oplog);
      }
      if (inactivePromotedCount > 0) {
        parent.getStats().incInactiveOplogs(-inactivePromotedCount);
      }
    }
  }

  private static class ValidateModeColocationChecker {
    // Each element of the list will be one colocation map
    // Each such map will have colocated prnames as the key and a list of bucket ids
    private final List<Map<String, List<VdrBucketId>>> prSetsWithBuckets = new ArrayList<>();
    private final DiskInitFile dif;
    private boolean inconsistent = false;
    public ValidateModeColocationChecker(DiskInitFile dif) {
      this.dif = dif;
    }

    private static class VdrBucketId {
      private final ValidatingDiskRegion vdr;
      private final int bucketId;
      private final boolean rootPR;

      VdrBucketId(ValidatingDiskRegion vdr, int bid, boolean isRootPR) {
        this.bucketId = bid;
        this.vdr = vdr;
        this.rootPR = isRootPR;
      }

      public String toString() {
        return "VdrBucketId: " + vdr.getName() + "(" + bucketId + ") rootPR - " + rootPR;
      }
    }

    public void add(ValidatingDiskRegion vdr) {
      assert vdr.isBucket();
      final String prName = vdr.getPrName();
      final PRPersistentConfig prPersistentConfig = this.dif.getPersistentPR(prName);
      final String colocateWith = prPersistentConfig != null ? prPersistentConfig.getColocatedWith() : null;
      final int bucketId = PartitionedRegionHelper.getBucketId(vdr.getName());
      VdrBucketId vdb = new VdrBucketId(vdr, bucketId, ((colocateWith == null) || colocateWith.isEmpty()));
      // System.out.println(vdb);
      Iterator<Map<String, List<VdrBucketId>>> itr = prSetsWithBuckets.iterator();
      boolean added = false;
      while (itr.hasNext()) {
        Map<String, List<VdrBucketId>> m = itr.next();
        if (m.containsKey(prName)) {
          List<VdrBucketId> s = m.get(prName);
          s.add(vdb);
          added = true;
          break;
        } else if (colocateWith != null && m.containsKey(colocateWith)) {
          // add an entry for prName
          List<VdrBucketId> s = new ArrayList<>(10);
          m.put(prName, s);
          s.add(vdb);
          added = true;
          break;
        }
      }
      if (!added) {
        Map<String, List<VdrBucketId>> m = new HashMap<>();
        List<VdrBucketId> s = new ArrayList<>(10);
        s.add(vdb);
        m.put(prName, s);
        if (colocateWith != null && colocateWith.length() > 0) {
          List<VdrBucketId> sc = new ArrayList<>(10);
          m.put(colocateWith, sc);
        }
        this.prSetsWithBuckets.add(m);
      }
    }

    public void findInconsistencies() {
      StringBuffer sb = new StringBuffer();
      sb.append("\n");
      sb.append("--- Child buckets with no parent buckets\n");
      sb.append("\n");
      prSetsWithBuckets.forEach(x -> {
        if (x.size() > 1) {
          findInconsistencyInternal(x, sb);
        }
      });
      if (inconsistent) {
        this.dif.setInconsistent(sb.toString());
      }
    }

    private void findInconsistencyInternal(Map<String, List<VdrBucketId>> oneSet, final StringBuffer sb) {
      List<VdrBucketId> smallest = null;
      String rootRegion = null;
      boolean allSizesEqual = true;
      Iterator<Map.Entry<String, List<VdrBucketId>>> itr = oneSet.entrySet().iterator();
      // find root PR
      int numbuckets_of_root = 0;
      String rootPR = "";
      String prn = "";
      Set<Integer> bucketIdsOfRoot = new HashSet<>();
      while (itr.hasNext()) {
        Map.Entry<String, List<VdrBucketId>> e = itr.next();
        List<VdrBucketId> currlist = e.getValue();
        if (currlist.size() > 0) {
          prn = currlist.get(0).vdr.getPrName();
          String colocatedwith = this.dif.getPersistentPR(prn).getColocatedWith();
          if (colocatedwith == null || colocatedwith.length() == 0) {
            numbuckets_of_root = currlist.size();
            rootPR = prn;
            currlist.forEach(x -> bucketIdsOfRoot.add(x.bucketId));
            break;
          }
          else {
            // sb.append("\n#### Colocated PR " + prn + " ####\n");
          }
        }
      }

      // sb.append("\n#### Root PR " + rootPR + " ####\n");

      final HashSet<VdrBucketId> badBuckets = new HashSet<>();
      final Boolean printedRootPR = Boolean.FALSE;
      itr = oneSet.entrySet().iterator();
      while (itr.hasNext()) {
        Map.Entry<String, List<VdrBucketId>> e = itr.next();
        List<VdrBucketId> currlist = e.getValue();
        if (currlist.size() > numbuckets_of_root) {
          currlist.forEach(x -> {
            if (!bucketIdsOfRoot.contains(x.bucketId)) {
              badBuckets.add(x);
              if (!this.inconsistent) {
                this.inconsistent = true;
              }
            }
          });
        }
      }

      if (badBuckets.size() > 0) {
        sb.append("In " + rootPR + " co-location group, parent bucket is missing for these buckets\n");
        badBuckets.forEach(x -> sb.append(x.vdr.getName() + "\n"));
        sb.append("\n");
      }
    }
  }

  public final void recoverRegionsThatAreReady(boolean initialRecovery) {
    // The following sync also prevents concurrent recoveries by multiple regions
    // which is needed currently.
    synchronized (this.alreadyRecoveredOnce) {
      // need to take a snapshot of DiskRecoveryStores we will recover
      synchronized (this.pendingRecoveryMap) {
        this.currentRecoveryMap.clear();
        this.currentRecoveryMap.putAll(this.pendingRecoveryMap);
        this.pendingRecoveryMap.clear();
      }
      if (this.currentRecoveryMap.isEmpty() && this.alreadyRecoveredOnce.get()) {
        // no recovery needed
        return;
      }

      for (DiskRecoveryStore drs: this.currentRecoveryMap.values()) {
        // Call prepare early to fix bug 41119.
        drs.getDiskRegionView().prepareForRecovery();
        // logger.info(LocalizedStrings.DEBUG, "DEBUG preparing "
        // + drs.getDiskRegionView().getName() + " for recovery" + " drId="
        // + drs.getDiskRegionView().getId() + " class=" + drs.getClass());
      }
//       logger.info(LocalizedStrings.DEBUG, "DEBUG recoverRegionsThatAreReady alreadyRecoveredOnce=" + this.alreadyRecoveredOnce
//                   + " currentRecoveryMap=" + this.currentRecoveryMap);
      //      logger.info(LocalizedStrings.DEBUG, "DEBUG recoverRegionsThatAreReady alreadyRecoveredOnce=" + this.alreadyRecoveredOnce);
      if (!this.alreadyRecoveredOnce.get()) {
        initOplogEntryId();
        //Fix for #43026 - make sure we don't reuse an entry
        //id that has been marked as cleared.
        updateOplogEntryId(parent.getDiskInitFile().getMaxRecoveredClearEntryId());
      }

      final long start = parent.getStats().startRecovery();
      long byteCount = 0;
      EntryLogger.setSource(parent.getDiskStoreID(), "recovery");
      try {
        byteCount = recoverOplogs(byteCount, initialRecovery);
        
      } finally {
        Map<String, Integer> prSizes = null;
        Map<String, Integer> prBuckets = null;
        ValidateModeColocationChecker vchkr = new ValidateModeColocationChecker(parent.getDiskInitFile());
        if (parent.isValidating()) {
          prSizes = new HashMap<String, Integer>();
          prBuckets = new HashMap<String, Integer>();
        }
        for (DiskRecoveryStore drs: this.currentRecoveryMap.values()) {
          for (Oplog oplog: getAllOplogs()) {
            if (oplog != null) {
              // Need to do this AFTER recovery to protect from concurrent compactions
              // trying to remove the oplogs.
              // We can't remove a dr from the oplog's unrecoveredRegionCount
              // until it is fully recovered.
              // This fixes bug 41119.
              oplog.checkForRecoverableRegion(drs.getDiskRegionView());
            }
          }
          if (parent.isValidating()) {
            if (drs instanceof ValidatingDiskRegion) {
              ValidatingDiskRegion vdr = ((ValidatingDiskRegion)drs);
              if (parent.TRACE_RECOVERY) {
                vdr.dump(System.out);
              }
              if (vdr.isBucket()) {
                vchkr.add(vdr);
                String prName = vdr.getPrName();
                if (prSizes.containsKey(prName)) {
                  int oldSize = prSizes.get(prName);
                  oldSize += vdr.size();
                  prSizes.put(prName, oldSize);
                  int oldBuckets = prBuckets.get(prName);
                  oldBuckets++;
                  prBuckets.put(prName, oldBuckets);
                } else {
                  prSizes.put(prName, vdr.size());
                  prBuckets.put(prName, 1);
                }
              } else {
                parent.incLiveEntryCount(vdr.size());
                System.out.println(vdr.getName() + ": entryCount=" + vdr.size());
              }
            }
          }
        }
        if (parent.isValidating()) {
          for (Map.Entry<String, Integer> me: prSizes.entrySet()) {
            parent.incLiveEntryCount(me.getValue());
            System.out.println(me.getKey() + " entryCount=" + me.getValue()
                               + " bucketCount=" + prBuckets.get(me.getKey()));
          }
          vchkr.findInconsistencies();
        }
        parent.getStats().endRecovery(start, byteCount);
        this.alreadyRecoveredOnce.set(true);
        this.currentRecoveryMap.clear();
        EntryLogger.clearSource();
      }
    }
  }
  
  private long recoverOplogs(long byteCount, boolean initialRecovery) {
    OplogEntryIdSet deletedIds = new OplogEntryIdSet();

    TreeSet<Oplog> oplogSet = getSortedOplogs();
    Set<Oplog> oplogsNeedingValueRecovery = new LinkedHashSet<Oplog>();
    //      logger.info(LocalizedStrings.DEBUG, "DEBUG recoverRegionsThatAreReady oplogSet=" + oplogSet);
    if (!this.alreadyRecoveredOnce.get()) {
      if (getChild() != null && !getChild().hasBeenUsed()) {
        // Then remove the current child since it is empty
        // and does not need to be recovered from
        // and it is important to not call initAfterRecovery on it.
        oplogSet.remove(getChild());
      }
    }
    if (oplogSet.size() > 0) {
      long startOpLogRecovery = System.currentTimeMillis();
      // first figure out all entries that have been destroyed
      boolean latestOplog = true;
      for (Oplog oplog: oplogSet) {
        byteCount += oplog.recoverDrf(deletedIds,
                                      this.alreadyRecoveredOnce.get(),
                                      latestOplog);
        latestOplog = false;
        if (!this.alreadyRecoveredOnce.get()) {
          updateOplogEntryId(oplog.getMaxRecoveredOplogEntryId());
        }
      }
      parent.incDeadRecordCount(deletedIds.size());
      // now figure out live entries
      latestOplog = true;
      for (Oplog oplog: oplogSet) {
        long startOpLogRead = parent.getStats().startOplogRead();
        long bytesRead = oplog.recoverCrf(deletedIds,
                                          // @todo make recoverValues per region
                                          recoverValues(),
                                          recoverValuesSync(),
                                          this.alreadyRecoveredOnce.get(),
                                          oplogsNeedingValueRecovery, 
                                          latestOplog,
                                          initialRecovery);
        latestOplog = false;
        if (!this.alreadyRecoveredOnce.get()) {
          updateOplogEntryId(oplog.getMaxRecoveredOplogEntryId());
        }
        byteCount += bytesRead;
        parent.getStats().endOplogRead(startOpLogRead, bytesRead);
        
        //Callback to the disk regions to indicate the oplog is recovered
        //Used for offline export
        for (DiskRecoveryStore drs: this.currentRecoveryMap.values()) {
          drs.getDiskRegionView().oplogRecovered(oplog.oplogId);
        }
      }
      long endOpLogRecovery = System.currentTimeMillis();
      long elapsed = endOpLogRecovery - startOpLogRecovery;
      if (parent.logger != null) {
        parent.logger.info(LocalizedStrings.DiskRegion_OPLOG_LOAD_TIME,
                    elapsed);
      }
    }
    if (!parent.isOfflineCompacting()) {
      long startRegionInit = System.currentTimeMillis();
      // create the oplogs now so that loadRegionData can have them available
      //Create an array of Oplogs so that we are able to add it in a single shot
      // to the map
      for (DiskRecoveryStore drs: this.currentRecoveryMap.values()) {
        drs.getDiskRegionView().initRecoveredEntryCount();
      }
      if (!this.alreadyRecoveredOnce.get()) {
        for (Oplog oplog: oplogSet) {
          if (oplog != getChild()) {
            oplog.initAfterRecovery(parent.isOffline());
          }
        }
        if (getChild() == null) {
          setFirstChild(getSortedOplogs(), false);
        }
      }
 
      if (!parent.isOffline()) {
        // schedule GFXD index recovery first
        parent.scheduleIndexRecovery(oplogSet, false);
        if(recoverValues() && !recoverValuesSync()) {
          //TODO DAN - should we defer compaction until after
          //value recovery is complete? Or at least until after
          //value recovery for a given oplog is complete?
          //Right now, that's effectively what we're doing
          //because this uses up the compactor thread.
          parent.scheduleValueRecovery(oplogsNeedingValueRecovery, this.currentRecoveryMap);
        }
        if(!this.alreadyRecoveredOnce.get()) {
          //Create krfs for oplogs that are missing them
          for (Oplog oplog : oplogSet) {
            if (oplog.needsKrf()) {
              parent.logger.info(LocalizedStrings.DEBUG, "recoverOplogs oplog: "
                  + oplog + " parent: " + parent.getName() + ", needskrf: "
                  + oplog.needsKrf());
              oplog.createKrfAsync();
            }
          }
          parent.scheduleCompaction();
        }
        
        long endRegionInit = System.currentTimeMillis();
        if (parent.logger != null) {
          parent.logger.info(LocalizedStrings.DiskRegion_REGION_INIT_TIME,
                      endRegionInit - startRegionInit);
        }
      }
    }
    return byteCount;
  }

  protected boolean recoverValuesSync() {
    return parent.RECOVER_VALUES_SYNC;
  }

  protected boolean recoverValues() {
    return parent.RECOVER_VALUES;
  }
  
  private void setFirstChild(TreeSet<Oplog> oplogSet, boolean force) {
    if (parent.isOffline() && !parent.isOfflineCompacting()) return;
    if (!oplogSet.isEmpty()) {
      Oplog first = oplogSet.first();
      DirectoryHolder dh = first.getDirectoryHolder();
      dirCounter = dh.getArrayIndex();
      dirCounter = (++dirCounter) % parent.dirLength;
      // we want the first child to go in the directory after the directory
      // used by the existing oplog with the max id.
      // This fixes bug 41822.
    }
    if (force || maxRecoveredOplogId > 0) {
      setChild(new Oplog(maxRecoveredOplogId + 1, this, getNextDir()));
    }
  }
  
  private final void initOplogEntryId() {
    this.oplogEntryId.set(DiskStoreImpl.INVALID_ID);
  }

  /**
   * Sets the last created oplogEntryId to the given value
   * if and only if the given value is greater than the current
   * last created oplogEntryId
   */
  private final void updateOplogEntryId(long v) {
    long curVal;
    do {
      curVal = this.oplogEntryId.get();
      if (curVal >= v) {
        // no need to set
        return;
      }
    } while (!this.oplogEntryId.compareAndSet(curVal, v));
//     logger.info(LocalizedStrings.DEBUG, "DEBUG DiskStore " + getName() + " updateOplogEntryId="+v);
  }

  /**
   * Returns the last created oplogEntryId.
   * Returns INVALID_ID if no oplogEntryId has been created.
   */
  final long getOplogEntryId() {
    parent.initializeIfNeeded(false);
    return this.oplogEntryId.get();
  }
  
  /**
   * Creates and returns a new oplogEntryId for the given key. An oplogEntryId
   * is needed when storing a key/value pair on disk. A new one is only needed
   * if the key is new. Otherwise the oplogEntryId already allocated for a key
   * can be reused for the same key.
   * 
   * @return A disk id that can be used to access this key/value pair on disk
   */
  final long newOplogEntryId() {
    long result = this.oplogEntryId.incrementAndGet();
    // logger.info(LocalizedStrings.DEBUG, "DEBUG DiskStore " + getName() +
    // " newOplogEntryId="+result);
    return result;
  }
  
  /**
   * Returns the next available DirectoryHolder which has space. If no dir has
   * space then it will return one anyway if compaction is enabled.
   * 
   * @param minAvailableSpace
   *          the minimum amount of space we need in this directory.
   */
  DirectoryHolder getNextDir(int minAvailableSpace) {
    DirectoryHolder dirHolder = null;
    DirectoryHolder selectedHolder = null;
    synchronized (parent.directories) {
      for (int i = 0; i < parent.dirLength; ++i) {
        dirHolder = parent.directories[this.dirCounter];
        // Asif :Increment the directory counter to next position so that next
        // time when this operation is invoked, it checks for the Directory
        // Space in a cyclical fashion.
        dirCounter = (++dirCounter) % parent.dirLength;
        // if the current directory has some space, then quit and
        // return the dir
        if (dirHolder.getAvailableSpace() >= minAvailableSpace) {
          selectedHolder = dirHolder;
          break;
        }
        // logger.info(LocalizedStrings.DEBUG, "DEBUG getNextDir  no space in "
        // + dirHolder);
      }

      if (selectedHolder == null) {
        if (parent.isCompactionEnabled()) {
          /*
           * try { this.isThreadWaitingForSpace = true;
           * this.directories.wait(MAX_WAIT_FOR_SPACE); } catch
           * (InterruptedException ie) { throw new
           * DiskAccessException(LocalizedStrings.
           * DiskRegion_UNABLE_TO_GET_FREE_SPACE_FOR_CREATING_AN_OPLOG_AS_THE_THREAD_ENCOUNETERD_EXCEPTION_WHILE_WAITING_FOR_COMPACTOR_THREAD_TO_FREE_SPACE
           * .toLocalizedString(), ie); }
           */

          selectedHolder = parent.directories[this.dirCounter];
          // Increment the directory counter to next position
          this.dirCounter = (++this.dirCounter) % parent.dirLength;
          if (selectedHolder.getAvailableSpace() < minAvailableSpace) {
            /*
             * throw new DiskAccessException(LocalizedStrings.
             * DiskRegion_UNABLE_TO_GET_FREE_SPACE_FOR_CREATING_AN_OPLOG_AFTER_WAITING_FOR_0_1_2_SECONDS
             * .toLocalizedString(new Object[] {MAX_WAIT_FOR_SPACE, /,
             * (1000)}));
             */
            if (parent.logger.warningEnabled()) {
              parent.logger
                  .warning(
                      LocalizedStrings.DiskRegion_COMPLEXDISKREGIONGETNEXTDIR_MAX_DIRECTORY_SIZE_WILL_GET_VIOLATED__GOING_AHEAD_WITH_THE_SWITCHING_OF_OPLOG_ANY_WAYS_CURRENTLY_AVAILABLE_SPACE_IN_THE_DIRECTORY_IS__0__THE_CAPACITY_OF_DIRECTORY_IS___1,
                      new Object[] {
                          Long.valueOf(selectedHolder.getUsedSpace()),
                          Long.valueOf(selectedHolder.getCapacity()) });
            }
          }
        } else {
          throw new DiskAccessException(
              LocalizedStrings.DiskRegion_DISK_IS_FULL_COMPACTION_IS_DISABLED_NO_SPACE_CAN_BE_CREATED
                  .toLocalizedString(), parent);
        }
      }
    }
    return selectedHolder;

  }
  
  DirectoryHolder getNextDir() {
    return getNextDir(DiskStoreImpl.MINIMUM_DIR_SIZE);
  }

  void addDrf(Oplog oplog) {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG addDrf oplog#" +
    // oplog.getOplogId());
    synchronized (this.oplogIdToOplog) {
      this.drfOnlyOplogs.put(Long.valueOf(oplog.getOplogId()), oplog);
    }
  }

  void removeDrf(Oplog oplog) {
    synchronized (this.oplogIdToOplog) {
      this.drfOnlyOplogs.remove(Long.valueOf(oplog.getOplogId()));
    }
  }
  
  /**
   * Return true if id is less than all the ids in the oplogIdToOplog map. Since
   * the oldest one is in the LINKED hash map is first we only need to compare
   * ourselves to it.
   */
  boolean isOldestExistingOplog(long id) {
    synchronized (this.oplogIdToOplog) {
      Iterator<Long> it = this.oplogIdToOplog.keySet().iterator();
      while (it.hasNext()) {
        long otherId = it.next().longValue();
        if (id > otherId) {
          // logger.info(LocalizedStrings.DEBUG, "id#" + id
          // + " is not the oldest because of compactable id#"
          // + otherId,
          // new RuntimeException("STACK"));
          return false;
        }
      }
      // since the inactiveOplogs map is an LRU we need to check each one
      it = this.inactiveOplogs.keySet().iterator();
      while (it.hasNext()) {
        long otherId = it.next().longValue();
        if (id > otherId) {
          // logger.info(LocalizedStrings.DEBUG, "id#" + id
          // + " is not the oldest because of inactive id#"
          // + otherId);
          return false;
        }
      }
      // logger.info(LocalizedStrings.DEBUG, "isOldest(id=" + id
      // + ") compactable=" + this.oplogIdToOplog.keySet()
      // + " inactive=" + this.inactiveOplogs.keySet());
    }
    return true;
  }
  
  /**
   * Destroy all the oplogs that are: 1. the oldest (based on smallest oplog id)
   * 2. empty (have no live values)
   */
  void destroyOldestReadyToCompact() {
    synchronized (this.oplogIdToOplog) {
      if (this.drfOnlyOplogs.isEmpty())
        return;
    }
    Oplog oldestLiveOplog = getOldestLiveOplog();
    ArrayList<Oplog> toDestroy = new ArrayList<Oplog>();
    if (oldestLiveOplog == null) {
      // remove all oplogs that are empty
      synchronized (this.oplogIdToOplog) {
        toDestroy.addAll(this.drfOnlyOplogs.values());
      }
    } else {
      // remove all empty oplogs that are older than the oldest live one
      synchronized (this.oplogIdToOplog) {
        for (Oplog oplog : this.drfOnlyOplogs.values()) {
          if (oplog.getOplogId() < oldestLiveOplog.getOplogId()) {
            toDestroy.add(oplog);
            // } else {
            // // since drfOnlyOplogs is sorted any other ones will be even
            // bigger
            // // so we can break out of this loop
            // break;
          }
        }
      }
    }
    for (Oplog oplog : toDestroy) {
      oplog.destroy();
    }
  }
  
  /**
   * Returns the oldest oplog that is ready to compact. Returns null if no
   * oplogs are ready to compact. Age is based on the oplog id.
   */
  private Oplog getOldestReadyToCompact() {
    Oplog oldest = null;
    synchronized (this.oplogIdToOplog) {
      Iterator<Oplog> it = this.oplogIdToOplog.values().iterator();
      while (it.hasNext()) {
        Oplog oldestCompactable = it.next();
        if (oldest == null
            || oldestCompactable.getOplogId() < oldest.getOplogId()) {
          oldest = oldestCompactable;
        }
      }
      it = this.drfOnlyOplogs.values().iterator();
      while (it.hasNext()) {
        Oplog oldestDrfOnly = it.next();
        if (oldest == null || oldestDrfOnly.getOplogId() < oldest.getOplogId()) {
          oldest = oldestDrfOnly;
        }
      }
    }
    return oldest;
  }
  
  private Oplog getOldestLiveOplog() {
    Oplog result = null;
    synchronized (this.oplogIdToOplog) {
      Iterator<Oplog> it = this.oplogIdToOplog.values().iterator();
      while (it.hasNext()) {
        Oplog n = it.next();
        if (result == null || n.getOplogId() < result.getOplogId()) {
          result = n;
        }
      }
      // since the inactiveOplogs map is an LRU we need to check each one
      it = this.inactiveOplogs.values().iterator();
      while (it.hasNext()) {
        Oplog n = it.next();
        if (result == null || n.getOplogId() < result.getOplogId()) {
          result = n;
        }
      }
    }
    return result;
  }
  
  void inactiveAccessed(Oplog oplog) {
    Long key = Long.valueOf(oplog.getOplogId());
    synchronized (this.oplogIdToOplog) {
      // update last access time
      this.inactiveOplogs.get(key);
    }
  }
  
  void inactiveReopened(Oplog oplog) {
    addInactive(oplog, true);
  }

  void addInactive(Oplog oplog) {
    addInactive(oplog, false);
  }

  private void addInactive(Oplog oplog, boolean reopen) {
    Long key = Long.valueOf(oplog.getOplogId());
    ArrayList<Oplog> openlist = null;
    synchronized (this.oplogIdToOplog) {
      boolean isInactive = true;
      if (reopen) {
        // It is possible that 'oplog' is compactable instead of inactive.
        // So set the isInactive.
        isInactive = this.inactiveOplogs.get(key) != null;
      } else {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG addInactive #" +
        // oplog.getOplogId());
        this.inactiveOplogs.put(key, oplog);
      }
      if ((reopen && isInactive) || oplog.isRAFOpen()) {
        if (inactiveOpenCount.incrementAndGet() > DiskStoreImpl.MAX_OPEN_INACTIVE_OPLOGS) {
          openlist = new ArrayList<Oplog>();
          for (Oplog o : this.inactiveOplogs.values()) {
            if (o.isRAFOpen()) {
              // add to my list
              openlist.add(o);
            }
          }
        }
      }
    }

    if (openlist != null) {
      for (Oplog o : openlist) {
        if (o.closeRAF()) {
          if (inactiveOpenCount.decrementAndGet() <= DiskStoreImpl.MAX_OPEN_INACTIVE_OPLOGS) {
            break;
          }
        }
      }
    }

    if (!reopen) {
      parent.getStats().incInactiveOplogs(1);
    }
  }
  
  public void clear(DiskRegion dr, RegionVersionVector rvv) {
    // call clear on each oplog
    // to fix bug 44336 put them in another collection
    ArrayList<Oplog> oplogsToClear = new ArrayList<Oplog>();
    synchronized (this.oplogIdToOplog) {
      for (Oplog oplog : this.oplogIdToOplog.values()) {
        oplogsToClear.add(oplog);
      }
      for (Oplog oplog : this.inactiveOplogs.values()) {
        oplogsToClear.add(oplog);
      }
      {
        Oplog child = getChild();
        if (child != null) {
          oplogsToClear.add(child);
        }
      }
    }
    for (Oplog oplog : oplogsToClear) {
      oplog.clear(dr, rvv);
    }

    if (rvv != null) {
      parent.getDiskInitFile().clearRegion(dr, rvv);
    } else {
      long clearedOplogEntryId = getOplogEntryId();
      parent.getDiskInitFile().clearRegion(dr, clearedOplogEntryId);
    }
  }
  
  public RuntimeException close() {
    RuntimeException rte = null;
    try {
      closeOtherOplogs();
    } catch (RuntimeException e) {
      if (rte != null) {
        rte = e;
      }
    }

    if (this.child != null) {
      try {
        this.child.finishKrf();
      } catch (RuntimeException e) {
        if (rte != null) {
          rte = e;
        }
      }

      try {
        this.child.close();
      } catch (RuntimeException e) {
        if (rte != null) {
          rte = e;
        }
      }
    }
    
    return rte;
  }
  
  /** closes all the oplogs except the current one * */
  private void closeOtherOplogs() {
    // get a snapshot to prevent CME
    Oplog[] oplogs = getAllOplogs();
    // if there are oplogs which are to be compacted, destroy them
    // do not do oplogs[0]
    for (int i = 1; i < oplogs.length; i++) {
      oplogs[i].finishKrf();
      oplogs[i].close();
      removeOplog(oplogs[i].getOplogId());
    }
  }
  
  /**
   * Removes the oplog from the map given the oplogId
   * 
   * @param id
   *          id of the oplog to be removed from the list
   * @return oplog Oplog which has been removed
   */
  Oplog removeOplog(long id) {
    return removeOplog(id, false, null);
  }

  Oplog removeOplog(long id, boolean deleting, Oplog olgToAddToDrfOnly) {
    // logger.info(LocalizedStrings.DEBUG, "DEBUG: removeOplog(" + id + ", " +
    // deleting + ")");
    Oplog oplog = null;
    boolean drfOnly = false;
    boolean inactive = false;
    Long key = Long.valueOf(id);
    synchronized (this.oplogIdToOplog) {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG: before oplogs=" +
      // this.oplogIdToOplog);
      oplog = this.oplogIdToOplog.remove(key);
      if (oplog == null) {
        oplog = this.inactiveOplogs.remove(key);
        if (oplog != null) {
          // logger.info(LocalizedStrings.DEBUG, "DEBUG: rmFromInactive #" +
          // id);
          if (oplog.isRAFOpen()) {
            inactiveOpenCount.decrementAndGet();
          }
          inactive = true;
        } else {
          oplog = this.drfOnlyOplogs.remove(key);
          if (oplog != null) {
            drfOnly = true;
          }
        }
        // } else {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG: rmFromCompacted #" + id);
      }
      // logger.info(LocalizedStrings.DEBUG, "DEBUG: after oplogs=" +
      // this.oplogIdToOplog);
      if (olgToAddToDrfOnly != null) {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG: added to drf oplog#"+
        // id);
        addDrf(olgToAddToDrfOnly);
      }
    }
    if (oplog != null) {
      if (!drfOnly) {
        if (inactive) {
          parent.getStats().incInactiveOplogs(-1);
        } else {
          parent.getStats().incCompactableOplogs(-1);
        }
      }
      if (!deleting && !oplog.isOplogEmpty()) {
        // we are removing an oplog whose files are not deleted
        parent.undeletedOplogSize.addAndGet(oplog.getOplogSize());
      }
    }
    return oplog;
  }
  
  public void basicClose(DiskRegion dr) {
    ArrayList<Oplog> oplogsToClose = new ArrayList<Oplog>();
    synchronized (this.oplogIdToOplog) {
      oplogsToClose.addAll(this.oplogIdToOplog.values());
      oplogsToClose.addAll(this.inactiveOplogs.values());
      oplogsToClose.addAll(this.drfOnlyOplogs.values());
      {
        Oplog child = getChild();
        if (child != null) {
          oplogsToClose.add(child);
        }
      }
    }
    for (Oplog oplog : oplogsToClose) {
      oplog.close(dr);
    }
  }
  
  public void prepareForClose() {
    ArrayList<Oplog> oplogsToPrepare = new ArrayList<Oplog>();
    synchronized (this.oplogIdToOplog) {
      oplogsToPrepare.addAll(this.oplogIdToOplog.values());
      oplogsToPrepare.addAll(this.inactiveOplogs.values());
    }
    boolean childPreparedForClose = false;
    long child_oplogid = this.getChild() == null ? -1 : this.getChild().oplogId;
    for (Oplog oplog : oplogsToPrepare) {
      oplog.prepareForClose();
      if (child_oplogid != -1 && oplog.oplogId == child_oplogid) {
        childPreparedForClose = true;
      }
    }
    if (!childPreparedForClose && this.getChild() != null) {
      this.getChild().prepareForClose();
    }
  }
  
  public void basicDestroy(DiskRegion dr) {
    ArrayList<Oplog> oplogsToDestroy = new ArrayList<Oplog>();
    synchronized (this.oplogIdToOplog) {
      for (Oplog oplog : this.oplogIdToOplog.values()) {
        oplogsToDestroy.add(oplog);
      }
      for (Oplog oplog : this.inactiveOplogs.values()) {
        oplogsToDestroy.add(oplog);
      }
      for (Oplog oplog : this.drfOnlyOplogs.values()) {
        oplogsToDestroy.add(oplog);
      }
      {
        Oplog child = getChild();
        if (child != null) {
          oplogsToDestroy.add(child);
        }
      }
    }
    for (Oplog oplog : oplogsToDestroy) {
      oplog.destroy(dr);
    }
  }

  public void destroyAllOplogs() {
 // get a snapshot to prevent CME
    for (Oplog oplog : getAllOplogs()) {
      if (oplog != null) {
        // logger.info(LocalizedStrings.DEBUG, "DEBUG destroying oplog=" +
        // oplog.getOplogId());
        oplog.destroy();
        removeOplog(oplog.getOplogId());
      }
    }
  }
  
  /**
   * Add compactable oplogs to the list, up to the maximum size.
   * @param l
   * @param max
   */
  public void getCompactableOplogs(List<CompactableOplog> l, int max) {
    synchronized (this.oplogIdToOplog) {
      // Sort this list so we compact the oldest first instead of the one
      // that was
      // compactable first.
      // ArrayList<CompactableOplog> l = new
      // ArrayList<CompactableOplog>(this.oplogIdToOplog.values());
      // Collections.sort(l);
      // Iterator<Oplog> itr = l.iterator();
      {
        Iterator<Oplog> itr = this.oplogIdToOplog.values().iterator();
        while (itr.hasNext() && l.size() < max) {
          Oplog oplog = itr.next();
          if (oplog.needsCompaction()) {
            l.add(oplog);
          }
        }
      }
    }
  }

  public void scheduleForRecovery(DiskRecoveryStore drs) {
    DiskRegionView dr = drs.getDiskRegionView();
    if (dr.isRecreated()
        && (dr.getMyPersistentID() != null || dr.getMyInitializingID() != null)) {
      // If a region does not have either id then don't pay the cost
      // of scanning the oplogs for recovered data.
      DiskRecoveryStore p_drs = drs;
      synchronized (this.pendingRecoveryMap) {
        this.pendingRecoveryMap.put(dr.getId(), p_drs);
        // this.logger.info(LocalizedStrings.DEBUG, "DEBUG adding drId=" +
        // dr.getId() + " to pendingRecoveryMap drs=" + drs.getClass());
      }
      // } else {
      // this.logger.info(LocalizedStrings.DEBUG, "DEBUG NOT adding drId=" +
      // dr.getId() + " to pendingRecoveryMap drs=" + drs.getClass());
    }
  }
  
  /**
   * Returns null if we are not currently recovering the DiskRegion with the
   * given drId.
   */
  public DiskRecoveryStore getCurrentlyRecovering(long drId) {
    return this.currentRecoveryMap.get(drId);
  }
  
  public void initChild() {
    if (getChild() == null) {
      setFirstChild(getSortedOplogs(), true);
    }
  }
  
  public void offlineCompact() {
    if (getChild() != null) {
      // check active oplog and if it is empty delete it
      getChild().krfClose(true, false);
      if (getChild().isOplogEmpty()) {
        getChild().destroy();
      }
    }

    { // remove any oplogs that only have a drf to fix bug 42036
      ArrayList<Oplog> toDestroy = new ArrayList<Oplog>();
      synchronized (this.oplogIdToOplog) {
        Iterator<Oplog> it = this.oplogIdToOplog.values().iterator();
        while (it.hasNext()) {
          Oplog n = it.next();
          if (n.isDrfOnly()) {
            toDestroy.add(n);
          }
        }
      }
      for (Oplog oplog : toDestroy) {
        oplog.destroy();
      }
      destroyOldestReadyToCompact();
    }
  }

  public DiskStoreImpl getParent() {
    return parent;
  }
  
  public void updateDiskRegion(AbstractDiskRegion dr) {
    for (Oplog oplog: getAllOplogs()) {
      if (oplog != null) {
        oplog.updateDiskRegion(dr);
      }
    }
  }
  
  public void flushChild() {
    Oplog oplog = getChild();
    if (oplog != null) {
      oplog.flushAll();
    }
  }

  public String getPrefix() {
    return OplogType.BACKUP.getPrefix();
  }

  public void crfCreate(long oplogId) {
    getParent().getDiskInitFile().crfCreate(oplogId);
  }

  public void drfCreate(long oplogId) {
    getParent().getDiskInitFile().drfCreate(oplogId);
  }

  public void crfDelete(long oplogId) {
    getParent().getDiskInitFile().crfDelete(oplogId);
  }
  
  public void drfDelete(long oplogId) {
    getParent().getDiskInitFile().drfDelete(oplogId);
  }

  public boolean couldHaveKrf() {
    return getParent().couldHaveKrf();
  }

  public boolean isCompactionPossible() {
    return getParent().isCompactionPossible();
  }
}


