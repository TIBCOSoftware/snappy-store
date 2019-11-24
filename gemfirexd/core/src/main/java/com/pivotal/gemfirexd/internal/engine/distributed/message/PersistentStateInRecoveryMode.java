/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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
package com.pivotal.gemfirexd.internal.engine.distributed.message;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.AbstractDiskRegion;
import com.gemstone.gemfire.internal.cache.DiskInitFile;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.persistence.PRPersistentConfig;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

public class PersistentStateInRecoveryMode {

  private InternalDistributedMember member = null;
  private final ArrayList<RecoveryModePersistentView>
      allRegionView = new ArrayList<>();
  private final ArrayList<Object> catalogObjects = new ArrayList<>();
  private ArrayList<String> otherExtractedDDLText = new ArrayList<>();
  private final HashMap<String, Integer> prToNumBuckets = new HashMap<>();
  private HashSet<String> replicatedRegions = new HashSet<>();
  private boolean isServer;

  public PersistentStateInRecoveryMode(
      List<Object> allEntries,
      List<DDLConflatable> extractedDDLs) {
    member = Misc.getMyId();
    if (allEntries != null && !allEntries.isEmpty()) {
      catalogObjects.addAll(allEntries);
    }
    if (extractedDDLs != null && !extractedDDLs.isEmpty()) {
      extractedDDLs.forEach(x -> otherExtractedDDLText.add(x.getValueToConflate()));
    }
    this.isServer = Misc.getMemStore().getMyVMKind().isStore();
  }

  public PersistentStateInRecoveryMode(InternalDistributedMember member,
      ArrayList<RecoveryModePersistentView> allRegionView,
      ArrayList<Object> catalogObjects,
      ArrayList<String> otherExtractedDDLText,
      HashMap<String, Integer> prToNumBuckets,
      HashSet<String> replicatedRegions, boolean isServer) {
    this.member = member;
    this.allRegionView.addAll(allRegionView);
    this.catalogObjects.addAll(catalogObjects);
    this.otherExtractedDDLText.addAll(otherExtractedDDLText);
    this.prToNumBuckets.putAll(prToNumBuckets);
    this.replicatedRegions = replicatedRegions;
    this.isServer = isServer;
  }

  public PersistentStateInRecoveryMode() {

  }

  public void addView(RecoveryModePersistentView v) {
    this.allRegionView.add(v);
  }

  public void addPRConfigs() {
    GemFireCacheImpl cache = Misc.getGemFireCache();
    Collection<DiskStoreImpl> diskStores = cache.listDiskStores();

    for (DiskStoreImpl ds : diskStores) {
      String dsName = ds.getName();
      if ((!dsName.equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME) ||
          dsName.equals(GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE) ||
          dsName.endsWith(GfxdConstants.SNAPPY_DELTA_DISKSTORE_SUFFIX))) {
        DiskInitFile dif = ds.getDiskInitFile();
        Map<String, PRPersistentConfig> prConfigs = dif.getAllPRs();

        for (Map.Entry<String, PRPersistentConfig> e : prConfigs.entrySet()) {
          this.prToNumBuckets.put(e.getKey(), e.getValue().getTotalNumBuckets());
        }

        Set<String> diskRegionNames = new HashSet<>();
        for (AbstractDiskRegion adr : ds.getAllDiskRegions().values()) {
          if (!adr.isBucket()) {
            diskRegionNames.add(adr.getFullPath());
          }
        }

        replicatedRegions.addAll(diskRegionNames);
      }
    }
  }

  public ArrayList<RecoveryModePersistentView> getAllRegionViews() {
    return this.allRegionView;
  }

  public InternalDistributedMember getMember() {
    return this.member;
  }

  public Boolean isServer() {
    return this.isServer;
  }

  public ArrayList<String> getOtherDDLs() {
    return this.otherExtractedDDLText;
  }

  public ArrayList<Object> getCatalogObjects() {
    return this.catalogObjects;
  }

  public HashMap<String, Integer> getPrToNumBuckets() {
    return this.prToNumBuckets;
  }

  public HashSet<String> getReplicatedRegions() {
    return this.replicatedRegions;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("member: ");
    sb.append(this.member);
    sb.append("\n");
    sb.append("RecoveryModePersistentView objects");
    for (RecoveryModePersistentView e : this.allRegionView) {
      sb.append(e);
      sb.append("\n");
    }
    sb.append("prToNumBuckets:");
    for (Map.Entry<String, Integer> e : prToNumBuckets.entrySet()) {
      sb.append("\nbucket: " + e.getKey() + " ::: numBuckets: " + e.getValue());
    }
    sb.append("\n");
    sb.append("replicatedRegions\n");
    sb.append(replicatedRegions);
    sb.append("\n");
    sb.append("Catalog Objects\n");
    for (Object obj : this.catalogObjects) {  /// since like catalogtableobjects, other types also
      // has implicit tostring  conversion, pattern matching isn't required ?
      sb.append(obj);
      sb.append("\n");
    }
    sb.append("Other Extracted ddls\n");
    for (String ddl : this.otherExtractedDDLText) {
      sb.append(ddl);
      sb.append("\n");
    }
    return sb.toString();
  }

  public static long getLatestModifiedTime(AbstractDiskRegion adr) {
    Optional<RegionEntry> rmax = adr.getRecoveredEntryMap()
        .regionEntries().stream().max((t1, t2) -> {
          if (t1.getLastModified() <= t2.getLastModified()) return -1;
          return 1;
        });
    assert (rmax.isPresent());
    return rmax.get().getLastModified();
  }

  public static class RecoveryModePersistentView
      implements Comparable<RecoveryModePersistentView>, DataSerializable {

    private String regionPath;
    private String diskStoreName;
    private transient RegionVersionVector rvv;
    private long mostRecentEntryModifiedTime;
    private long latestOplogTime;
    private transient InternalDistributedMember member;

    public void setMember(InternalDistributedMember member) {
      this.member = member;
    }

    public RecoveryModePersistentView(
        final String diskStoreName, final String regionFullPath,
        final RegionVersionVector regionVersionVector,
        long recentModifiedTime, long latestOplogTime) {
      this.regionPath = regionFullPath;
      this.diskStoreName = diskStoreName;
      this.rvv = regionVersionVector.getCloneForTransmission();
      this.mostRecentEntryModifiedTime = recentModifiedTime;
      this.latestOplogTime = latestOplogTime;
    }

    public RecoveryModePersistentView() {
    }

    public RecoveryModePersistentView(RecoveryModePersistentView view) {
      this.member = view.getMember();
      this.regionPath = view.getRegionPath();
      this.diskStoreName = view.getDiskStoreName();
      this.rvv = view.getRvv().getCloneForTransmission();
      this.mostRecentEntryModifiedTime = view.getMostRecentEntryModifiedTime();
      this.latestOplogTime = view.getLatestOplogTime();
    }

    public long getMostRecentEntryModifiedTime() {
      return mostRecentEntryModifiedTime;
    }

    public long getLatestOplogTime() {
      return latestOplogTime;
    }

    public RegionVersionVector getRvv() {
      return this.rvv;
    }

    public String getRegionPath() {
      return this.regionPath;
    }

    public InternalDistributedMember getMember() {
      return this.member;
    }

    public String getExecutorHost() {
      return this.member.canonicalString();
    }

    public String getDiskStoreName() {
      return this.diskStoreName;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      out.writeUTF(regionPath);
      out.writeUTF(diskStoreName);
      DataSerializer.writeObject(this.rvv, out);
      DataSerializer.writeLong(mostRecentEntryModifiedTime, out);
      DataSerializer.writeLong(latestOplogTime, out);
    }

    @Override
    public void fromData(DataInput in) throws IOException, ClassNotFoundException {
      this.regionPath = in.readUTF();
      this.diskStoreName = in.readUTF();
      this.rvv = DataSerializer.readObject(in);
      this.mostRecentEntryModifiedTime = DataSerializer.readLong(in);
      this.latestOplogTime = DataSerializer.readLong(in);
    }

    @Override
    public String toString() {
      String sb = "RecoveryModePersistentView member: " +
          this.member +
          ": region: " +
          this.regionPath +
          ": latestOplogTime:" +
          this.latestOplogTime +
          ": mostRecentEntryModifiedTime:" +
          this.mostRecentEntryModifiedTime +
          ": rvv:" +
          this.rvv +
          ": diskStoreName:" +
          this.diskStoreName;
      return sb;
    }

    void log(String str) {
      if(GemFireXDUtils.TraceRecoveryMode) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RECOVERY_MODE, str);
      }
    }

    // todo: add test to check if compareTo method is commutative and transitive
    @Override
    public int compareTo(RecoveryModePersistentView other) {
      // They should be called for the same region
      log("Comparing RecoveryModePersistentViews :::\n" +
          "this view: " + this + "\n other view: " + other);
      assert this.regionPath.equals(other.regionPath);
      if (mostRecentEntryModifiedTime == other.mostRecentEntryModifiedTime &&
          latestOplogTime == other.latestOplogTime &&
          Objects.equals(regionPath, other.regionPath) &&
          Objects.equals(diskStoreName, other.diskStoreName) &&
          rvv.sameAs(other.rvv) &&
          member.equals(other.member)) {
        log("Comparing same object. Doesn't make sense to do this. Please check.");
        return 0;
      }
      if (this.rvv.logicallySameAs(other.rvv) ||
          (!this.rvv.dominates(other.rvv) && !other.rvv.dominates(this.rvv))) {
        log("RVV based approach is not usable.");
        if (this.latestOplogTime <= other.latestOplogTime) {
          SanityManager.DEBUG_PRINT("info", " 1. this.latestOplogTime <= other.latestOplogTime");
          log("Deciding on basis of Oplog file time: return -1");
          return -1;
        }
        if (this.mostRecentEntryModifiedTime < other.mostRecentEntryModifiedTime) {
          // replacing "<=" with "<" as former makes it non-commutative
          log("Deciding on basis of Modified entry time: return -1");
          return -1;
        }
        log("Oplog file time AND Modified entry time are greater for LHS: return 1");
        return 1;
      } else {
        log("Use RVV based approach.");
        if (this.rvv.dominates(other.rvv)) {
          log("RVV of LHS dominates RHS: return 1");
          return 1;
        } else {
          log("RVV of RHS dominates LHS: return -1");
          return -1;
        }
      }
    }
  }

  public static class RecoveryModePersistentViewPair
      implements Comparable<RecoveryModePersistentViewPair> {

    private RecoveryModePersistentView rowView = null;
    private RecoveryModePersistentView colView = null;

    public RecoveryModePersistentView getRowView() {
      return rowView;
    }

    public RecoveryModePersistentView getColView() {
      return colView;
    }

    public RecoveryModePersistentViewPair(RecoveryModePersistentView rowView,
        RecoveryModePersistentView colView) {
      this.rowView = rowView;
      this.colView = colView;
    }

    @Override
    public String toString() {
      String theString = "RecoveryModePersistentViewPair\nROW VIEW: " +
          this.getRowView() +
          "\nCOL VIEW: " +
          this.getColView();
      return theString;
    }

    @Override
    public int compareTo(RecoveryModePersistentViewPair other) {
      // They should be called for the same region
      log("Comparing RecoveryModePersistentViewPairs :::\n" +
          "this view: " + this + "\n other view: " + other);

      RecoveryModePersistentView thisRowView = this.getRowView();
      RecoveryModePersistentView thisColView = this.getColView();
      RecoveryModePersistentView otherRowView = other.getRowView();
      RecoveryModePersistentView otherColView = other.getColView();

      if ((thisRowView.mostRecentEntryModifiedTime == otherRowView.mostRecentEntryModifiedTime &&
          thisRowView.latestOplogTime == otherRowView.latestOplogTime &&
          Objects.equals(thisRowView.regionPath, otherRowView.regionPath) &&
          Objects.equals(thisRowView.diskStoreName, otherRowView.diskStoreName) &&
          thisRowView.rvv.sameAs(otherRowView.rvv) &&
          thisRowView.member.equals(otherRowView.member))
          &&
          (thisColView.mostRecentEntryModifiedTime == otherColView.mostRecentEntryModifiedTime &&
              thisColView.latestOplogTime == otherColView.latestOplogTime &&
              Objects.equals(thisColView.regionPath, otherColView.regionPath) &&
              Objects.equals(thisColView.diskStoreName, otherColView.diskStoreName) &&
              thisColView.rvv.sameAs(otherColView.rvv) &&
              thisColView.member.equals(otherColView.member) ||
              (thisColView == null && otherColView == null))
      ) {
        SanityManager.DEBUG_PRINT("info", "Comparing same object. Doesn't make sense to do this. Please check.");
        return 0;
      }

      int compareValue = getCompareValue(thisRowView, thisColView, otherRowView, otherColView);
      return compareValue;
    }

    void log(String str) {
      if(GemFireXDUtils.TraceRecoveryMode) {
      SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_RECOVERY_MODE, str);
      }
    }

    private int getCompareValue(
        RecoveryModePersistentView thisRowView,
        RecoveryModePersistentView thisColView,
        RecoveryModePersistentView otherRowView,
        RecoveryModePersistentView otherColView) {

      if (thisColView == null || otherColView == null) {
        // row tables as col regs are absent
        int retVal = (new RecoveryModePersistentView(thisRowView).compareTo(otherRowView));
        log("Row table: return value = " + retVal);
        int retVal1 = retVal;
        return retVal1;
      } else {
        // col tables
        boolean rowAndColRVVLogicallySame =
            thisRowView.rvv.logicallySameAs(otherRowView.rvv) &&
                (thisColView.rvv.logicallySameAs(otherColView.rvv));
        boolean rowAndColLatestOplogTimeSame =
            thisColView.latestOplogTime == otherColView.latestOplogTime &&
                thisRowView.latestOplogTime == otherRowView.latestOplogTime;
        boolean otherRowDominates = otherRowView.rvv.dominates(thisRowView.rvv);
        boolean otherColDominates = otherColView.rvv.dominates(thisColView.rvv);
        boolean thisRowDominates = thisRowView.rvv.dominates(otherRowView.rvv);
        boolean thisColDominates = thisColView.rvv.dominates(otherColView.rvv);

//              t, t | t, t
//              t, t | f, f
//              f, f | t, t
//              f, f | f, f
//              t, f | f, t
//              f, t | t, f
        boolean isRVVNotUsable =
            ((thisRowDominates == otherRowDominates &&
                otherColDominates == thisColDominates) ||
                (thisRowDominates != otherRowDominates &&
                    otherColDominates != thisColDominates));

        log("thisRowView Dominates otherRowView: " + thisRowView.rvv.dominates(otherRowView.rvv));
        log("otherRowView Dominates thisRowView: " + otherRowView.rvv.dominates(thisRowView.rvv));
        log("thisColView Dominates otherColView: " + thisColView.rvv.dominates(otherColView.rvv));
        log("otherColView Dominates thisColView: " + otherColView.rvv.dominates(thisColView.rvv));

        if (isRVVNotUsable) {
          log("RVV based approach is not usable.");
          if (rowAndColLatestOplogTimeSame) {
            int retVal = compareTime(thisColView.getMostRecentEntryModifiedTime(),
                otherColView.getMostRecentEntryModifiedTime(),
                thisRowView.getMostRecentEntryModifiedTime(),
                otherRowView.getMostRecentEntryModifiedTime());
            log("Decide on basis of MostRecentEntryModifiedTime: return value = " + retVal);
            return retVal;
          } else {
            int retVal = compareTime(thisColView.getLatestOplogTime(),
                otherColView.getLatestOplogTime(),
                thisRowView.getLatestOplogTime(),
                otherRowView.getLatestOplogTime());
            log("Decide on basis of LatestOplogTime: return value = " + retVal);
            return retVal;
          }
        } else {
// NOTES : At this point there can't be a case when all 4 combinations are false or all are true.
// Also cannot be a case when row combinations are true and col combinations are false(and vice versa).

// Also there cannot be a case when there is conflict.
// eg: (thisrowdominates)true & (otherrowdominates)false for row AND
//     (thiscoldominates)false & (othercoldominates)true for col


          if (thisColDominates == otherColDominates) { // true == true , false == false
            // Decide on basis of row rvv
//              t, t | f, t
//              t, t | t, f
//              f, f | t, f
//              f, f | f, t
            return thisRowDominates ? 1 : -1;
          } else if (thisRowDominates == otherRowDominates) { // true == true , false == false
//              t, t | f, t
//              t, t | t, f
//              f, f | t, f
//              f, f | f, t
            // Decide on basis of col rvv
            return thisColDominates ? 1 : -1;
          } else {
//              t, f | t, f
//              f, t | f, t
            // conflicting case  - not sure when will this happen?
            return thisColDominates && thisRowDominates ? 1 : -1;
          }

        }
      }
    }

    private int compareTime(Long thisColViewTime, Long otherColViewTime, Long thisRowViewTime, Long otherRowViewTime) {
      //              |   equal col,      thisRowGreater
      //              |   thisColgreater, equalrow
      //         1 ---|   thisColGreater, thisRowGreater
      //              |   thisColGreater, otherRowGreater

      //              |    equal col,       otherRowGreater
      //              |    otherColGreater, equal row
      //        -1 ---|    otherColGreater, thisRowGreater
      //              |    otherColGreater, otherRowGreater
      if ((thisColViewTime > otherColViewTime &&
          thisRowViewTime >= otherRowViewTime) ||
          (thisColViewTime == otherColViewTime &&
              thisRowViewTime > otherRowViewTime)) {
        return 1;
      } else {
        return -1;
      }
    }
  }
}
