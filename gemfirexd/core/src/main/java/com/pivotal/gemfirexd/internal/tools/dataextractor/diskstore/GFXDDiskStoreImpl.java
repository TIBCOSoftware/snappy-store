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
package com.pivotal.gemfirexd.internal.tools.dataextractor.diskstore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.internal.cache.DiskEntry;
import com.gemstone.gemfire.internal.cache.DiskEntry.RecoveredEntry;
import com.gemstone.gemfire.internal.cache.DiskId;
import com.gemstone.gemfire.internal.cache.DiskStoreAttributes;
import com.gemstone.gemfire.internal.cache.DiskStoreFactoryImpl;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.DiskStoreImplProxy;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskEntryPage;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskPosition;
import com.gemstone.gemfire.internal.cache.ExportDiskRegion;
import com.gemstone.gemfire.internal.cache.ExportDiskRegion.ExportWriter;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.InternalRegionArguments;
import com.gemstone.gemfire.internal.cache.PlaceHolderDiskRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.cache.snapshot.GFSnapshot.SnapshotWriter;
import com.gemstone.gemfire.internal.util.ArraySortedCollection;
import com.pivotal.gemfirexd.internal.tools.dataextractor.extractor.GemFireXDDataExtractorImpl;
import com.pivotal.gemfirexd.internal.tools.dataextractor.report.views.RegionViewInfoPerMember;
import com.pivotal.gemfirexd.internal.tools.dataextractor.snapshot.GFXDSnapshot;
import com.pivotal.gemfirexd.internal.tools.dataextractor.snapshot.GFXDSnapshotExportStat;

/***
 * 
 * @author bansods
 * @author jhuynh
 */
public class GFXDDiskStoreImpl extends DiskStoreImplProxy {
  private String stringDelimiter = "\"";
  private List listOfStats = new ArrayList();

  GFXDDiskStoreImpl(Cache cache, String name, DiskStoreAttributes props,
      boolean ownedByRegion, InternalRegionArguments internalRegionArgs,
      boolean offline, boolean upgradeVersionOnly, boolean offlineValidating,
      boolean offlineCompacting, boolean needsOplogs) {
    super(cache, name, props, ownedByRegion, internalRegionArgs, offline,
        upgradeVersionOnly, offlineValidating, offlineCompacting, needsOplogs);
  }

  private static DiskStoreImpl createForOffline(String dsName, File[] dsDirs,
      boolean offlineCompacting, boolean offlineValidate,
      boolean upgradeVersionOnly, long maxOplogSize, boolean needsOplogs)
          throws Exception {
    if (dsDirs == null) {
      dsDirs = new File[] { new File("") };
    }
    // need a cache so create a loner ds
    Properties props = new Properties();
    props.setProperty("locators", "");
    props.setProperty("mcast-port", "0");
    props.setProperty("cache-xml-file", "");
    if (!TRACE_RECOVERY) {
      props.setProperty("log-level", "warning");
    }
    DistributedSystem ds = GemFireCacheImpl.getInstance().getDistributedSystem();

    offlineDS = ds;
    Cache c = GemFireCacheImpl.getInstance();
    offlineCache = c;
    com.gemstone.gemfire.cache.DiskStoreFactory dsf = c
        .createDiskStoreFactory();
    dsf.setDiskDirs(dsDirs);
    if (offlineCompacting && maxOplogSize != -1L) {
      dsf.setMaxOplogSize(maxOplogSize);
    }
    DiskStoreImpl dsi = new GFXDDiskStoreImpl(c, dsName,
        ((DiskStoreFactoryImpl) dsf).getDiskStoreAttributes(), false, null,
        true, upgradeVersionOnly, offlineValidate, offlineCompacting,
        needsOplogs);
    ((GemFireCacheImpl) c).addDiskStore(dsi);
    return dsi;
  }


  public static List<GFXDSnapshotExportStat> exportOfflineSnapshotXD(String dsName, File[] dsDirs,
      File out, boolean forceKRFRecovery, String stringDelimiter) throws Exception {
    GFXDDiskStoreImpl dsi = null;
    try {  
      dsi = (GFXDDiskStoreImpl)createForOfflineXD(dsName, dsDirs);
      dsi.setStringDelimiter(stringDelimiter);

      if (forceKRFRecovery) {
        dsi.exportSnapshotUsingKRF(dsName, out);
      } else {
        dsi.exportSnapshot(dsName, out, false);
      }
    } 
    finally {
      //dsi.closeLockFile();
      if (dsi != null) {
        dsi.close();
      }
    }
    if (dsi != null) {
      return dsi.getListOfStats();
    }
    return new ArrayList();
  }


  protected void exportSnapshotUsingKRF(String name, File out) throws IOException {
    // Since we are recovering a disk store, the cast from DiskRegionView -->
    // PlaceHolderDiskRegion
    // and from RegionEntry --> DiskEntry should be ok.

    // In offline mode, we need to schedule the regions to be recovered
    // explicitly.
    setForceKRFRecovery(true); 
    setDataExtractionKrfRecovery(true);
    
    for (DiskRegionView drv : getKnown()) {
      scheduleForRecovery((PlaceHolderDiskRegion) drv);
    }

    try {
      recoverRegionsThatAreReady(false);
    }
    catch (NullPointerException e) {
      //this can occur when drf is missing
    }
    
    // coelesce disk regions so that partitioned buckets from a member end up in
    // the same file
    Map<String, List<PlaceHolderDiskRegion>> regions = new HashMap<String, List<PlaceHolderDiskRegion>>();

    for (DiskRegionView drv : getKnown()) {
      PlaceHolderDiskRegion ph = (PlaceHolderDiskRegion) drv;
      String regionName = (drv.isBucket() ? ph.getPrName() : drv.getName());
      List<PlaceHolderDiskRegion> views = regions.get(regionName);
      if (views == null) {
        views = new ArrayList<PlaceHolderDiskRegion>();
        regions.put(regionName, views);
      }
      views.add(ph);
    }

    for (Map.Entry<String, List<PlaceHolderDiskRegion>> entry : regions.entrySet()) {
      //right now this means that this has multiple regions/ PR
      for (DiskRegionView drv : entry.getValue()) {
        GFXDSnapshotExportStat stat = new GFXDSnapshotExportStat(drv);
        this.listOfStats.add(stat);
      }
    }
  }


  protected void exportSnapshot(String name, File out, boolean
      coelesceBuckets) throws IOException {
    Map<String, SnapshotWriter> regions = new HashMap<String, SnapshotWriter>();

    try {
      for (DiskRegionView drv: getKnown()) {
        
        boolean isHDFSQueue = false;
        PlaceHolderDiskRegion ph = (PlaceHolderDiskRegion) drv;
        String regionName = (drv.isBucket() ? ph.getPrName() : drv.getName());
        String lookupName = (drv.isBucket() ? regionName +
            drv.getName() : regionName);
        
        if(regionName.startsWith(GFXDSnapshot.QUEUE_REGION_PREFIX)
            || regionName.contains(GFXDSnapshot.QUEUE_REGION_PREFIX)) {
          isHDFSQueue = true;
          
        }
        
        if (isHDFSQueue) {
          //TO Shorten the name
          regionName = regionName.replaceAll(GFXDSnapshot.QUEUE_REGION_STRING, GFXDSnapshot.QUEUE_REGION_SHORT);
          lookupName = regionName;
        }
        SnapshotWriter writer = regions.get(lookupName);
        
        
        if (writer == null) {
          File f = null;
          String fname = lookupName.substring(1).replace('/', '-');
          //                if (!drv.isBucket()) {
          //                  f = new File(out, "snapshot-" + name + "-" + fname);
          //                  RegionViewInfoPerMember regionInfoView = new RegionViewInfoPerMember(drv);
          //                  writer = createGFSnapshotWriter(f, regionName, regionInfoView);
          //                } else {
          f = new File(out, "snapshot-" + name + "-" + fname);
          RegionViewInfoPerMember regionInfoView = new RegionViewInfoPerMember(drv);
          
          writer = createGFSnapshotWriter(f, lookupName, regionInfoView);
          if (writer == null) {
            if (f.exists()) {
              if (!f.delete()) {
                GemFireXDDataExtractorImpl.logInfo("Unable to delete unused file:" + f.getAbsolutePath());
              }
            }
            continue;
          }
          //                }
          regions.put(lookupName, writer);
        }

        final SnapshotWriter theWriter = writer;
        scheduleForRecovery(new ExportDiskRegion(this, drv, new ExportWriter() {
          @Override
          public void writeBatch(Map<Object, RecoveredEntry> entries)
              throws IOException {
            for (Map.Entry<Object, RecoveredEntry> re: entries.entrySet()) {
              Object key = re.getKey();
              // TODO:KIRK:OK Rusty's code was value =de.getValueWithContext(drv);
              Object value = re.getValue().getValue();
              //if (!Token.isInvalidOrRemoved(value)) {
                theWriter.snapshotEntry(key, value,
                    re.getValue().getVersionTag(), re.getValue().getLastModifiedTime());
              //}
            }
          }
        }));
      }
      try {
        recoverRegionsThatAreReady(false);
      }
      catch (NullPointerException e) {
        //this can occur when a drf is missing
        StringBuffer buffer = new StringBuffer("Unable to recover the following tables, possibly due to a missing .drf file:\n");
        for (String regionName: regions.keySet()) {
          buffer.append(regionName);
          buffer.append("\n");
        }
        GemFireXDDataExtractorImpl.logSevere(buffer.toString());
       
      }
    }
    finally {
      for (SnapshotWriter writer: regions.values()) {
        try {
          writer.snapshotComplete();
        }
        catch (IOException e) {
          GemFireXDDataExtractorImpl.logSevere(e.getMessage());
        }
      }
    }
  }


  public static class DiskSavyIterator implements Iterator <DiskEntryPage> {
    ArraySortedCollection diskMap; 
    Iterator <Object> iterator;
    Map<DiskRegionView, Integer> drvToIdMap = new HashMap<DiskRegionView, Integer>();
    Map<Integer, DiskRegionView> idToDrvMap = new HashMap<Integer, DiskRegionView>();

    private DiskSavyIterator() {
    }

    public DiskRegionView getDrv(int drvId) {
      return idToDrvMap.get(Integer.valueOf(drvId));
    }
    @Override
    public boolean hasNext() {
      return this.iterator.hasNext();
    }
    @Override
    public DiskEntryPage next() {
      return (DiskEntryPage) this.iterator.next();
    }
    @Override
    public void remove() {

    }

    public void clear() {
      diskMap.clear();
      drvToIdMap.clear();
      idToDrvMap.clear();
    }


    public static DiskSavyIterator getDiskSavyIterator(Collection<DiskRegionView> regions) {
      int numEntries = 0;
      DiskSavyIterator diskSavyIterator = new DiskSavyIterator();
      int drvId=0;

      //Get the TOTAL no of entries and generate ids for drv;
      for (DiskRegionView drv : regions) {
        int recoveredEntryCount = drv.getRecoveredEntryCount();
        if (recoveredEntryCount > 0) {
          //Generate a 
          if (diskSavyIterator.drvToIdMap.get(drv) == null) {
            diskSavyIterator.drvToIdMap.put(drv, ++drvId);
            diskSavyIterator.idToDrvMap.put(new Integer(drvId),  drv);
          }
          numEntries += recoveredEntryCount;
        }
      }
      //Build the diskSavyIterator for the entire diskStore
      diskSavyIterator.diskMap = new ArraySortedCollection(
          new DiskEntryPage.DEPComparator(), null, null, numEntries, 0);
      Set<DiskRegionView> diskRegions = diskSavyIterator.drvToIdMap.keySet();

      for (DiskRegionView diskRegion : diskRegions) {
        Collection<RegionEntry> regionEntries = diskRegion.getRecoveredEntryMap().regionEntries();
        Iterator<RegionEntry> regionEntryIterator = regionEntries.iterator();

        while (regionEntryIterator.hasNext()) {
          RegionEntry re = regionEntryIterator.next();
          if (re instanceof DiskEntry) {
            DiskEntry de = (DiskEntry) re;
            DiskPosition dp = new DiskPosition();
            DiskId did = de.getDiskId();
            dp.setPosition(did.getOplogId(), did.getOffsetInOplog());
            diskSavyIterator.diskMap.add(new DiskEntryPage(dp, re, null));
          }
        }
        regionEntries.clear();
      }

      diskSavyIterator.iterator = diskSavyIterator.diskMap.iterator();
      return diskSavyIterator;
    }
  }

  public static int getDiskStoreSizeInMB(String dsName, File[] dsDirs) throws Exception {
    int size = 0;
    GFXDDiskStoreImpl dsi = (GFXDDiskStoreImpl)createForOfflineXD(dsName, dsDirs);
    int []diskDirSizes = dsi.getDiskDirSizes();

    for (int diskDirSize : diskDirSizes) {
      size += diskDirSize;
    }
    return size;
  }

  private static DiskStoreImpl createForOfflineXD(String dsName, File[] dsDirs)
      throws Exception {
    return GFXDDiskStoreImpl.createForOffline(dsName, dsDirs, false, false,
        false/* upgradeVersionOnly */, 0, false);
  }

  protected SnapshotWriter createGFSnapshotWriter(File f, String regionName, RegionViewInfoPerMember regionViewInfo) throws IOException {
    return GFXDSnapshot.create(f, regionName, listOfStats,  regionViewInfo, stringDelimiter);
  }

  public List<GFXDSnapshotExportStat> getListOfStats() {
    return listOfStats;

  }

  public void setStringDelimiter(String stringDelimiter) {
    this.stringDelimiter = stringDelimiter;
  }

  public static void closeDiskStoreFiles(DiskStoreImpl diskStore) {
    DiskStoreImplProxy.closeDiskStoreFiles(diskStore);
  }

}

