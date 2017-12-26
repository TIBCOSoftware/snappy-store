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

package com.pivotal.gemfirexd.internal.engine.store;

import java.io.DataOutput;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SerializationException;
import com.gemstone.gemfire.cache.EntryExistsException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.DataSerializableFixedID;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.cache.*;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholdListener;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.execute.BucketMovedException;
import com.gemstone.gemfire.internal.cache.lru.Sizeable;
import com.gemstone.gemfire.internal.cache.partitioned.Bucket;
import com.gemstone.gemfire.internal.cache.persistence.PersistentMemberID;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderAttributes;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.ChunkType;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.DataAsAddress;
import com.gemstone.gemfire.internal.offheap.UnsafeMemoryChunk;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.offheap.annotations.Unretained;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gnu.trove.TIntArrayList;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.GfxdDataSerializable;
import com.pivotal.gemfirexd.internal.engine.GfxdSerializable;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.ServerResolverUtils;
import com.pivotal.gemfirexd.internal.engine.fabricservice.FabricServiceImpl;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraInfo;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.ExtraTableInfo;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter.ColumnProcessor;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter.ColumnProcessorOffHeap;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.ColumnDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.iapi.types.WrapperRowLocationForTxn;
import com.pivotal.gemfirexd.internal.impl.sql.catalog.GfxdDataDictionary;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import org.apache.spark.unsafe.Platform;

public final class RegionEntryUtils {

  // no instance
  private RegionEntryUtils() {
  }

  private static final boolean isValueToken(Object value) {
    if (!Misc.getMemStoreBooting().isHadoopGfxdLonerMode()) {
      return Token.isInvalidOrRemoved(value) || value == Token.NOT_AVAILABLE;
    } else {
      return Token.isInvalidOrRemoved(value);
    }
  }

  /**
   * Returns either a byte[] or byte[][] or OffHeapByteSource depending upon the type of entry & data
   * @param region
   * @param entry
   * @return
   */
  public static Object getValue(final LocalRegion region,
      final RegionEntry entry) {
    final Object value = entry.getValue(getDataRegion(region, entry));
    if (!isValueToken(value)) {
      return value;
    }
    return null;
  }

  /**
   * Returns either a byte[] or byte[][] or OffHeapByteSource depending upon the type of entry & data
   * @param region
   * @param entry
   * @return
   */
  @Retained
  public static Object getValueWithoutFaultIn(final LocalRegion region,
      final RegionEntry entry) {
    @Retained final Object value = entry.getValueOffHeapOrDiskWithoutFaultIn(getDataRegion(
        region, entry));
    if (!isValueToken(value)) {
      return value;
    }
    return null;
  }

  /**
   * Returns either the byte[] or byte[][] or the OffHeapRegionEntry itself , depending upon the case
   * @param region
   * @param entry
   * @return
   */
  public static Object getValueWithoutFaultInOrOffHeapEntry(
      final LocalRegion region, final RowLocation entry) {
    final Object value = entry.getValueWithoutFaultInOrOffHeapEntry(region);
    if (!isValueToken(value)) {
      return value;
    }
    return null;
  }

  public static Object getValueOrOffHeapEntry(final LocalRegion region,
      final RowLocation entry) {
    final Object value = entry.getValueOrOffHeapEntry(region);
    if (!isValueToken(value)) {
      return value;
    }
    return null;
  }

  private static LocalRegion getDataRegion(final LocalRegion region,
      final RegionEntry entry) {
    if (region == null || region.getPartitionAttributes() == null) {
      return region;
    }
    PartitionedRegion pr = (PartitionedRegion)region;
    if (region.getPartitionAttributes().getPartitionResolver() == null) {
      // determine bucketId from hashCode of the key
      int bucketId = PartitionedRegionHelper.getHashKey(pr, entry.getKey());
      return getBucketRegion(pr, bucketId);
    }
    else {
      // unexpected call
      Assert.fail("unexpected call to getDataRegion for " + region
          + ", entry: " + entry);
      // never reached
      return null;
    }
  }

  @Retained
  public static Object getByteSource(RowLocation rowLocation,
      GemFireContainer container, boolean faultIn, boolean valueInVMOnly) {
    LocalRegion localRgn = null;
    if (container != null) {
      localRgn = container.getRegion();
      if (!valueInVMOnly) {
        if (container.isPartitioned()) {
          localRgn = getBucketRegion((PartitionedRegion)localRgn,
              rowLocation.getBucketID());
        }
      }
    }
    return convertOffHeapEntrytoByteSourceRetain((RegionEntry)rowLocation,
        localRgn, faultIn, valueInVMOnly);
  }

  @Retained
  public static Object getValueInVM(final RegionEntry entry,
      final RegionEntryContext context) {
    @Retained final Object value = entry.getValueInVM(context);
    if (!isValueToken(value)) {
      return value;
    }
    return null;
  }

  public static Object getValue(final GemFireContainer baseContainer,
      final int bucketId, final RegionEntry entry) throws BucketMovedException {
    assert bucketId != -1 : "The bucket ID should not be -1: " + baseContainer;
    // need BucketRegion only if overflow to get value
    final PartitionedRegion pr = (PartitionedRegion) baseContainer.getRegion();
    return getValue(getBucketRegion(pr, bucketId), entry);
  }

  @Retained
  public static Object getValueWithoutFaultIn(
      final GemFireContainer baseContainer, final int bucketId,
      final RegionEntry entry) {

    assert bucketId != -1 : "The bucket ID should not be -1: " + baseContainer;
    // need BucketRegion only if overflow to get value
    final PartitionedRegion pr = (PartitionedRegion) baseContainer.getRegion();
    return getValueWithoutFaultIn(getBucketRegion(pr, bucketId), entry);
  }

  /**
   *
   * TODO: PERF: now this is deserialized only for EXPRESSION and RANGE
   * partitioning; can even that be avoided?
   * 
   * @param val
   * @param validColumn
   *          - this is 1 based i.e. logicalPosition and will be substracted by
   *          1 for DVD[] access.
   * @param gfContainer
   * @return
   */
  public static DataValueDescriptor getDVDFromValue(@Unretained final Object val,
      final int validColumn, final GemFireContainer gfContainer) {
    // this case of new row being inserted will always have the current
    // RowFormatter and not any of the previous ones before ALTER TABLEs
    final RowFormatter rf = gfContainer.getCurrentRowFormatter();
    // @yjing: Index maintenance before row inserted into the table,
    // hence the input value is DVD[] and isByteArrayStore is true. This old
    // code will throw an exception.
    // We can do transform based on the type of the val instead of
    // isByteArrayStore.
    // In addition, we can assume the val is not null; otherwise, some errors
    // existed in other place.
    // 2/19/2009

    assert val != null: "the value is supposed to be non-null!";

    try {
      final Class<?> cls = val.getClass();
      if (cls == byte[].class) {
        // even for LOBs, the getColumns will correctly return default value
        // without having to provide a byte[][] value
        return rf.getColumn(validColumn, (byte[])val);
      }
      else if (cls == byte[][].class) {
        return rf.getColumn(validColumn, (byte[][])val);
      }
      else if (cls == OffHeapRow.class) {
        return rf.getColumn(validColumn, (OffHeapRow)val);
      }
      else if (cls == OffHeapRowWithLobs.class) {
        return rf.getColumn(validColumn, (OffHeapRowWithLobs)val);
      }
      else if (cls == DataValueDescriptor[].class) {
        return ((DataValueDescriptor[])val)[validColumn - 1];
      }
      else {
        throw new AssertionError("Unexpected value type: "
            + val.getClass().getName());
      }
    } catch (StandardException ex) {
      final LogWriter logger = Misc.getCacheLogWriter();
      if (logger.warningEnabled()) {
        logger.warning("Got exception while getting DVD from value", ex);
      }
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception while getting DVD from value", ex);
    } 
  }

  /**
   * 
   * TODO: PERF: now this is deserialized only for EXPRESSION; can even that be
   * avoided?
   * 
   * @param val
   * @param validColumns
   *          - this is 1 based i.e. logicalPosition and will be substracted by
   *          1 for DVD[] access.
   * @param gfContainer
   * @return
   */
  public static DataValueDescriptor[] getDVDArrayFromValue(@Unretained final Object val,
      final int[] validColumns, final GemFireContainer gfContainer) {
    // this case of new row being inserted will always have the current
    // RowFormatter and not any of the previous ones before ALTER TABLEs
    final RowFormatter rf = gfContainer.getCurrentRowFormatter();
    final int length;
    if (validColumns == null) {
      length = rf.getNumColumns();
    }
    else {
      length = validColumns.length;
    }
    final DataValueDescriptor[] dvdarr = new DataValueDescriptor[length];
    // @yjing: Index maintenance before row inserted into the table,
    // hence the input value is DVD[] and isByteArrayStore is true. This old
    // code will throw an exception.
    // We can do transform based on the type of the val instead of
    // isByteArrayStore.
    // In addition, we can assume the val is not null; otherwise, some errors
    // existed in other place.
    // 2/19/2009

    assert val != null: "the value is supposed to be non-null!";

    try {
      final Class<?> cls = val.getClass();
      if (cls == byte[].class) {
        // even for LOBs, the getColumns will correctly return default values
        // without having to provide a byte[][] value
        rf.getColumns((byte[])val, dvdarr, validColumns);
      }
      else if (cls == byte[][].class) {
        rf.getColumns((byte[][])val, dvdarr, validColumns);
      }
      else if (cls == OffHeapRow.class) {
        rf.getColumns((OffHeapRow)val, dvdarr, validColumns);
      }
      else if (cls == OffHeapRowWithLobs.class) {
        rf.getColumns((OffHeapRowWithLobs)val, dvdarr, validColumns);
      }
      else if (cls == DataValueDescriptor[].class) {
        DataValueDescriptor[] valarr = (DataValueDescriptor[])val;
        for (int i = 0; i < validColumns.length; i++) {
          dvdarr[i] = valarr[validColumns[i] - 1];
        }
      }
      else {
        throw new AssertionError("Unexpected value type: "
            + val.getClass().getName());
      }
    } catch (StandardException ex) {
      final LogWriter logger = Misc.getCacheLogWriter();
      if (logger.warningEnabled()) {
        logger.warning("Got exception while getting DVD[] from value", ex);
      }
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception while getting DVD[] from value", ex);
    } 
    return dvdarr;
  }

  public static final BucketRegion getBucketRegion(PartitionedRegion pr,
      int bucketId) throws BucketMovedException {
    // need BucketRegion only if overflow to get value
    final ProxyBucketRegion pbr = pr.getRegionAdvisor().getProxyBucketArray()[bucketId];
    final BucketRegion br = pbr.getCreatedBucketRegion();
    if (br != null) {
      return br;
    } else {
      throw new BucketMovedException(
          LocalizedStrings.FunctionService_BUCKET_MIGRATED_TO_ANOTHER_NODE
              .toLocalizedString(),
          bucketId, pr.getFullPath());
    }
  }

  public static ExecRow getRow(final GemFireContainer baseContainer,
      final LocalRegion region, final RegionEntry entry,
      final ExtraTableInfo tableInfo) {
    final Object value = getValueOrOffHeapEntry(getDataRegion(region, entry),
        (RowLocation)entry);
    if (value != null) {
      return baseContainer.newExecRow(entry, value, tableInfo, true);
    }
    return null;
  }

  public static ExecRow getRow(final GemFireContainer baseContainer,
      final int bucketId, final RegionEntry entry,
      final ExtraTableInfo tableInfo) {
    assert bucketId != -1 : "The bucket ID should not be -1: " + baseContainer;
    // need BucketRegion only if overflow to get value
    final PartitionedRegion pr = (PartitionedRegion) baseContainer.getRegion();

    final Object value = getValueOrOffHeapEntry(getBucketRegion(pr, bucketId),
        (RowLocation)entry);
    if (value != null) {
      return baseContainer.newExecRow(entry, value, tableInfo, true);
    }
    return null;
  }

  public static ExecRow getRowWithoutFaultIn(
      final GemFireContainer baseContainer, final LocalRegion region,
      final RegionEntry entry, final ExtraTableInfo tableInfo) {
    final Object value = getValueWithoutFaultInOrOffHeapEntry(
        getDataRegion(region, entry), (RowLocation)entry);
    if (value != null) {
      return baseContainer.newExecRow(entry, value, tableInfo, false);
    }
    return null;
  }

  public static ExecRow getRowWithoutFaultIn(
      final GemFireContainer baseContainer, final int bucketId,
      final RegionEntry entry, final ExtraTableInfo tableInfo) {
    assert bucketId != -1 : "The bucket ID should not be -1: " + baseContainer;
    // need BucketRegion only if overflow to get value
    final PartitionedRegion pr = (PartitionedRegion) baseContainer.getRegion();
    Object value = getValueWithoutFaultInOrOffHeapEntry(
        getBucketRegion(pr, bucketId), (RowLocation)entry);

    if (value != null) {
      return baseContainer.newExecRow(entry, value, tableInfo, false);
    }
    return null;
  }


  

  /**
   * @return possible OFF_HEAP_OBJECT (caller must release)
   */
  @Retained
  public static Object convertOffHeapEntrytoByteSourceRetain(
      RegionEntry offheap, LocalRegion rgn, boolean faultIn,
      boolean useValueInVMOnly) {

    @Retained Object obj = null;
    if (useValueInVMOnly) {
      obj = offheap.getValueInVM(null);
    }
    else {
      if (faultIn) {
        obj = offheap.getValue(rgn);
      }
      else {
        obj = offheap.getValueOffHeapOrDiskWithoutFaultIn(rgn);
      }
    }
    if (!isValueToken(obj)) {
      if (!(obj instanceof DataAsAddress)) {
        return obj;
      }
      else {
        return encodedAddressToObject(((DataAsAddress)obj).getEncodedAddress());
      }
    }
    return null;
  }

  /**
   * @return possible OFF_HEAP_OBJECT (NOT retained for caller)
   */
  public static Object convertLOBAddresstoByteSourceNoRetain(long ohAddress) {

    Object obj = null;
    if (OffHeapRegionEntryHelper.isOffHeap(ohAddress)) {
      return OffHeapRow.TYPE.newChunk(ohAddress);
    }
    else {
      obj = encodedAddressToObject(ohAddress);
    }
    if (!isValueToken(obj)) {
      return obj;
    }
    return null;
  }

  /**
   * @return byte[] for the LOB
   */
  public static byte[] convertLOBAddresstoBytes(final UnsafeWrapper unsafe,
      final long ohAddress) {
    if (OffHeapRegionEntryHelper.isOffHeap(ohAddress)) {
      return OffHeapRow.getBaseRowBytes(
          OffHeapByteSource.getBaseDataAddress(ohAddress),
          OffHeapByteSource.getDataSize(unsafe, ohAddress));
    }
    else {
      return OffHeapRegionEntryHelper.encodedAddressToExpectedRawBytes(
          ohAddress, true);
    }
  }

  /**
   * @return serialize LOB as byte[]
   */
  public static void serializeLOBAddresstoBytes(final UnsafeWrapper unsafe,
      final long ohAddress, final DataOutput out) throws IOException {
    if (OffHeapRegionEntryHelper.isOffHeap(ohAddress)) {
      OffHeapRow.serializeBaseRowBytes(unsafe,
          OffHeapByteSource.getBaseDataAddress(ohAddress),
          OffHeapByteSource.getDataSize(unsafe, ohAddress), out);
    }
    else {
      InternalDataSerializer.writeByteArray(OffHeapRegionEntryHelper
          .encodedAddressToExpectedRawBytes(ohAddress, true), out);
    }
  }

  private  static Object encodedAddressToObject(long address) {
    return OffHeapRegionEntryHelper.encodedAddressToObject(address, false, true);
  }

  public static boolean fillRowWithoutFaultIn(
      final GemFireContainer baseContainer, final LocalRegion region,
      final RegionEntry entry, final AbstractCompactExecRow row)
      throws StandardException {

    final Object value = getValueWithoutFaultInOrOffHeapEntry(region,
        (RowLocation) entry);
    if (value != null) {
      final Class<?> valClass = value.getClass();
      if (valClass == byte[].class) {
        fillRowUsingByteArray(baseContainer, row, (byte[])value);
      }
      else if (valClass == byte[][].class) {
        fillRowUsingByteArrayArray(baseContainer, row, (byte[][])value);
      }
      else if (value == Token.NOT_AVAILABLE
          && Misc.getMemStoreBooting().isHadoopGfxdLonerMode()) {
        final Object containerInfo = entry.getContainerInfo();
        final ExtraTableInfo tableInfo = containerInfo != null
            ? (ExtraTableInfo)containerInfo
            : baseContainer.getExtraTableInfo();
        final int[] keyColumns = tableInfo.getPrimaryKeyColumns();
        if (keyColumns != null) {
          //We have a primary key. Convert that to a full row with just the primary
          //key columns filled in.
          RowFormatter kf = tableInfo.getPrimaryKeyFormatter();
          RowFormatter rf = row.getRowFormatter();
          final Object key = entry.getRawKey();
          if (key != null) {
            final byte[] newRow;
            if (key.getClass() == byte[].class) {
              newRow = rf.copyColumns(keyColumns, (byte[])key, kf);
            }
            else {
              newRow = rf.copyColumns(keyColumns, (OffHeapByteSource)key, kf);
            }
            row.setRowArray(newRow, rf);
          }
        } else {
          //If there is no primary key, just return a row of nulls
          row.resetRowArray();
        }
      }
      else if (OffHeapRegionEntry.class.isAssignableFrom(valClass)) {
        AbstractCompactExecRow filledRow = fillRowUsingAddress(baseContainer,
            region, (OffHeapRegionEntry)entry, row, false);
        if (filledRow == null) {
          return false;
        }
      }
      else if (Delta.class.isAssignableFrom(valClass)) {
        // The RegionEntry has at present deltas & the base row has not arrived,
        // return null for this case
        return false;
      }
      else {
        GemFireXDUtils.throwAssert("fillRow:: unexpected value type: "
            + valClass.getName());
      }
      return true;
    }
    return false;
  }

  public static boolean fillRowWithoutFaultInOptimized(
      final GemFireContainer baseContainer, final LocalRegion region,
      final RowLocation entry, final AbstractCompactExecRow row)
      throws StandardException {

    final Object value = entry.getValueWithoutFaultInOrOffHeapEntry(region);
    if (value != null) {
      final Class<?> valClass = value.getClass();
      if (valClass == byte[][].class) {
        fillRowUsingByteArrayArray(baseContainer, row, (byte[][])value);
      } else if (valClass == byte[].class) {
        fillRowUsingByteArray(baseContainer, row, (byte[])value);
      } else if (region.getEnableOffHeapMemory() &&
          OffHeapRegionEntry.class.isAssignableFrom(valClass)) {
        AbstractCompactExecRow filledRow = fillRowUsingAddress(baseContainer,
            region, (OffHeapRegionEntry)entry, row, false);
        if (filledRow == null) {
          return false;
        }
      } else if (Token.class.isAssignableFrom(valClass)) {
        return false;
      } else if (Delta.class.isAssignableFrom(valClass)) {
        // The RegionEntry has at present deltas & the base row has not arrived,
        // return null for this case
        return false;
      } else {
        GemFireXDUtils.throwAssert("fillRow:: unexpected value type: "
            + valClass.getName());
      }
      return true;
    }
    return false;
  }

  private static void fillRowUsingByteArray(
      final GemFireContainer baseContainer,
      final AbstractCompactExecRow row, final byte[] value) {
    final RowFormatter rf = baseContainer.getRowFormatter(value);
    if (!rf.hasLobs()) {
      row.setRowArray(value, rf);
    } else {
      row.setRowArray(rf.createByteArraysWithDefaultLobs(value), rf);
    }
  }

  private static void fillRowUsingByteArrayArray(
      final GemFireContainer baseContainer,
      final AbstractCompactExecRow row, final byte[][] value) {
    final RowFormatter rf = baseContainer.getRowFormatter(value[0]);
    if (rf.hasLobs()) {
      row.setRowArray(value, rf);
    } else {
      row.setRowArray(value[0], rf);
    }
  }

  public static AbstractCompactExecRow fillRowUsingAddress(
      final GemFireContainer baseContainer, final LocalRegion region,
      final OffHeapRegionEntry entry, final AbstractCompactExecRow row,
      boolean faultIn) {
    SimpleMemoryAllocatorImpl.setReferenceCountOwner(row);
    Object source = convertOffHeapEntrytoByteSourceRetain(entry, region,
        faultIn, false);
    SimpleMemoryAllocatorImpl.setReferenceCountOwner(null);

    if (source != null) {
      final RowFormatter rf = baseContainer.getRowFormatter(source);
      row.setByteSource(source, rf);
    }
    else {
      return null;
    }

    return row;
  }

  public static int getBucketId(Object b) {
    if (b.getClass() == PlaceHolderDiskRegion.class) {
      return PartitionedRegionHelper.getBucketId(((PlaceHolderDiskRegion) b)
          .getName());
    }
    return ((Bucket) b).getId();
  }
  
  // Methods related to key manipulation from the serialized row as value

  public static final StaticSystemCallbacks gfxdSystemCallbacks =
      new StaticSystemCallbacks() {

    private final String[] allPrefixes = new String[]{
        GfxdConstants.SNAPPY_PREFIX,
        GfxdConstants.GFXD_PREFIX,
        DistributionConfig.GEMFIRE_PREFIX};

    @Override
    public void logAsync(final Object []line) {
      SanityManager.DEBUG_PRINT_COMPACT_LINE(line);
    }
    
    @Override
    public final boolean initializeForDeferredRegionsRecovery(long timeout) {
      // wait for DDL replay to complete
      return GemFireXDUtils.waitForNodeInitialization(timeout, true, false);
    }

    @Override
    public final boolean waitBeforeAsyncDiskTask(long waitMillis,
        DiskStoreImpl ds) {
      if (GemFireXDUtils.TracePersistIndex) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PERSIST_INDEX,
            "RegionEntryUtils#waitBeforeAsyncDiskTask: called");
      }
      if (ds.waitForIndexRecoveryEnd(waitMillis)) {
        GemFireStore memStore = Misc.getMemStoreBooting();
        GfxdDataDictionary dd = memStore.getDatabase().getDataDictionary();
        if (dd != null) {
          if (!memStore.initialDDLReplayDone()) {
            return dd.lockForReadingInDDLReplayNoThrow(memStore, waitMillis,
                false);
          }
          else {
            return dd.lockForReadingNoThrow(null, waitMillis);
          }
        }
      }
      return false;
    }

    @Override
    public final void endAsyncDiskTask(DiskStoreImpl ds) {
      if (GemFireXDUtils.TracePersistIndex) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_PERSIST_INDEX,
            "RegionEntryUtils#endAsyncDiskTask: called");
      }
      GemFireStore memStore = Misc.getMemStoreBooting();
      GfxdDataDictionary dd = memStore.getDatabase().getDataDictionary();
      if (dd != null) {
        dd.unlockAfterReading(null);
      }
    }

    @Override
    public boolean persistIndexes(DiskStoreImpl ds) {
      return (!ds.isUsedForInternalUse() && !ds.isOffline() && Misc
          .getMemStoreBooting().isPersistIndexes());
    }

    @Override
    public void waitForAsyncIndexRecovery(DiskStoreImpl dsi) {
      if (dsi.isOffline()) {
        return;
      }
      final GemFireStore memStore = Misc.getMemStoreBooting();
      if (GemFireXDUtils.TracePersistIndex) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
            "RegionEntryUtils#waitForAsyncIndexRecovery: "
                + "waiting for index creation for " + dsi);
      }
      memStore.waitForIndexLoadBegin(-1);
      if (GemFireXDUtils.TracePersistIndex) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_INDEX,
            "RegionEntryUtils#waitForAsyncIndexRecovery: "
                + "done wait for index creation for " + dsi);
      }
    }

    @Override
    public Set<SortedIndexContainer> getAllLocalIndexes(
        final DiskStoreImpl ds) {
      final GemFireStore memStore = Misc.getMemStoreBooting();
      @SuppressWarnings("unchecked")
      Set<SortedIndexContainer> allIndexes = new THashSet();
      for (GemFireContainer container : memStore.getAllContainers()) {
        if (container.isLocalIndex() && container.isInitialized()) {
          // check if index will use the provided diskstore for persistence
          if (ds == null || ds.getName().equals(container.getBaseContainer()
              .getRegionAttributes().getDiskStoreName())) {
            assert ds == null || ds == container.getBaseContainer().getRegion()
                .getDiskStore(): "ds=" + ds + ", regionDS="
                + container.getBaseContainer().getRegion().getDiskStore();
            allIndexes.add(container);
          }
        }
      }
      return allIndexes;
    }

    @Override
    public MemoryThresholdListener getMemoryThresholdListener() {
      return Misc.getMemStoreBooting().thresholdListener();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSystemPropertyNamePrefix() {
      return CallbackFactoryProvider.getStoreCallbacks().isSnappyStore()
          ? GfxdConstants.SNAPPY_PREFIX : GfxdConstants.GFXD_PREFIX;
    }

    @Override
    public String getSystemProperty(final String key,
        SystemProperties properties) {
      final GemFireStore store = GemFireStore.getBootingInstance();
      String propValue;
      try {
        if (store != null) {
          propValue = PropertyUtil.getDatabaseProperty(store, key);
          if (propValue != null) {
            return propValue;
          }
        }
      } catch (StandardException se) {
        throw GemFireXDRuntimeException.newRuntimeException(null, se);
      }

      boolean hasPrefix = false;
      for (String prefix : allPrefixes) {
        if (key.startsWith(prefix)) {
          hasPrefix = true;
          break;
        }
      }
      if (!hasPrefix) {
        for (String prefix : allPrefixes) {
          propValue = PropertyUtil.getSystemProperty(prefix + key, null);
          if (propValue != null) {
            return propValue;
          }
        }
      }
      return null;
    }

    @Override
    public void initializeForOffline() {
      // need to register GFXD types
      if (GfxdDataSerializable.initTypes()) {
        DataType.init();
      }
    }
    
    @Override
    public boolean isConstraintViolation(Exception ex) {
      if (ex instanceof StandardException && 
          (((StandardException) ex).getSQLState().equals("23505") || 
          ((StandardException) ex).getSQLState().equals("23503"))) {
        return true;
      } else {
        Throwable failure = ex;
        while (failure != null) {
          if (failure instanceof EntryExistsException) {
            return true;
          }
          failure = failure.getCause();
        }
      }
      return false;
    }

    @Override
    public boolean supportsRegionConcurrencyChecks(LocalRegion region) {
      // [sjigyasu] Allow GFE like behavior of the default is set to true
      if (defaultRegionConcurrencyChecksEnabled()) {
        return ((!region.isSecret() || region.getDataPolicy().withPersistence())
            && !GemFireStore.DDL_STMTS_REGION.equals(region.getName()));
      }
      // Return false for regions which are for DDL or secret
      return (!region.isSecret() && !GemFireStore.DDL_STMTS_REGION
          .equals(region.getName()));
    }

    @Override
    public boolean allowConcurrencyChecksOverride(LocalRegion region) {
      // [sjigyasu] Allow override only if the default concurrency checks are
      // enabled.
      if (defaultRegionConcurrencyChecksEnabled()) {
        // Allow override if the flag is set but not for DDL meta-region
        return !GemFireStore.DDL_STMTS_REGION.equals(region.getName());
      }

      // currently we never allow overriding the concurrency-checks-enabled flag
      // for any region
      return false;
    }

    @Override
    public boolean shouldDeleteWinOnConflict() {
      // [sjigyasu] In GemFireXD, for delete-update or delete-insert conflicts, we
      // make the delete trump regardless of the timestamp.
      return true;
    }

    @Override
    public boolean defaultRegionConcurrencyChecksEnabled() {
      return GfxdConstants.TABLE_DEFAULT_CONCURRENCY_CHECKS_ENABLED;
    }

    @Override
    public boolean createUUIDPersistentRegion(LocalRegion region) {
      // we always persist region UUIDs in GemFireXD
      return true;
    }

    @Override
    public final Object entryRefreshKey(final Object key,
        final Object oldValue, final Object newValue, final Object containerInfo)
        throws IllegalAccessException {

      final ExtraTableInfo tableInfo = (ExtraTableInfo) containerInfo;
      final Class<?> newValClass;
      if (newValue != null
          && ((newValClass = newValue.getClass()) == byte[].class
              || newValClass == byte[][].class
              || newValClass == OffHeapRow.class
              || newValClass == OffHeapRowWithLobs.class
              || newValClass == DataAsAddress.class)) {
        // everything is present in value; no longer require the key
        // but only if tableInfo is present
        if (tableInfo != null && tableInfo.regionKeyPartOfValue()) {
          return null;
        }
      } else if (key == null) {
        if (oldValue != null) {
          final Class<?> oldValClass = oldValue.getClass();
          if (oldValClass == byte[].class) {
            byte[] rowBytes = (byte[]) oldValue;
            final ExtraTableInfo rowInfo = tableInfo.getExtraTableInfo(rowBytes);
            return rowInfo.getRowFormatter().generateColumns(rowBytes,
                rowInfo.getPrimaryKeyColumns(),
                tableInfo.getPrimaryKeyFormatter());
          }
          else if (oldValClass == byte[][].class) {
            byte[] rowBytes = ((byte[][]) oldValue)[0];
            final ExtraTableInfo rowInfo = tableInfo.getExtraTableInfo(rowBytes);
            return rowInfo.getRowFormatter().generateColumns(rowBytes,
                rowInfo.getPrimaryKeyColumns(),
                tableInfo.getPrimaryKeyFormatter());
          }
          else if (oldValClass == OffHeapRow.class) {
            final OffHeapRow bs = (OffHeapRow)oldValue;
            return tableInfo.getRowFormatter(bs).generateColumns(bs,
                tableInfo.getPrimaryKeyColumns(),
                tableInfo.getPrimaryKeyFormatter());
          }
          else if (oldValClass == OffHeapRowWithLobs.class) {
            final OffHeapRowWithLobs bs = (OffHeapRowWithLobs)oldValue;
            return tableInfo.getRowFormatter(bs).generateColumns(bs,
                tableInfo.getPrimaryKeyColumns(),
                tableInfo.getPrimaryKeyFormatter());
          }
          else if (oldValClass == DataAsAddress.class) {
            byte[] rowBytes = (byte[])encodedAddressToObject(
                ((DataAsAddress)oldValue).getEncodedAddress());
            final ExtraTableInfo rowInfo = tableInfo.getExtraTableInfo(rowBytes);
            return rowInfo.getRowFormatter().generateColumns(rowBytes,
                rowInfo.getPrimaryKeyColumns(),
                tableInfo.getPrimaryKeyFormatter());
          }
          else if (!isValueToken(oldValue)) {
            GemFireXDUtils.throwAssert("entryRefreshKey:: unexpected oldValue "
                + "type: " + oldValClass.getName());
          }
        }
        // indicates retry for caller
        throw new IllegalAccessException("key and value both invalid");
      }
      else if (newValue == null
          && key.getClass() == CompactCompositeRegionKey.class) {
        // snapshot key bytes if we have an old inserted CCRK
        return ((CompactCompositeRegionKey)key).snapshotKeyFromValue(false);
      }
      return key;
    }

    @Override
    public final Object entryGetKey(final Object key,
        final AbstractRegionEntry entry) {

      final ExtraTableInfo tableInfo;
      final Class<?> keyClass = key != null ? key.getClass() : null;
      if (keyClass == byte[].class) {
        return entry;
      } else if ((tableInfo = (ExtraTableInfo) entry.getContainerInfo()) != null) {
        if (tableInfo.regionKeyPartOfValue()
            && keyClass != CompactCompositeRegionKey.class) {
          return entry;
        } else {
          return key;
        }
      }
      return key;
    }

    @Override
    public final Object entryGetKeyCopy(final Object key,
        final AbstractRegionEntry entry) {

      ExtraTableInfo tableInfo = (ExtraTableInfo) entry.getContainerInfo();
      final Object val;
      final Class<?> keyClass = key != null ? key.getClass() : null;
      if (keyClass == byte[].class) {
        return new CompactCompositeRegionKey(tableInfo, (byte[]) key);
      } else if (tableInfo != null) {
        if (tableInfo.regionKeyPartOfValue()) {
          if (keyClass == CompactCompositeRegionKey.class) {
            return key;
          }
          else if (entry.isOffHeap()) {
            return new CompactCompositeRegionKey((OffHeapRegionEntry)entry,
                tableInfo);
          }
          else if ((val = entry._getValue()) != null) {
            final Class<?> valClass = val.getClass();
            if (valClass == byte[].class) {
              final byte[] vbytes = (byte[]) val;
              tableInfo = tableInfo.getExtraTableInfo(vbytes);
              return new CompactCompositeRegionKey(vbytes, tableInfo);
            } else if (valClass == byte[][].class) {
              final byte[] vbytes = ((byte[][]) val)[0];
              // may need to update tableInfo
              tableInfo = tableInfo.getExtraTableInfo(vbytes);
              return new CompactCompositeRegionKey(vbytes, tableInfo);
            } 
            else if (isValueToken(val)) {
              return null; // indicates retry for the caller
            }
            GemFireXDUtils
                .throwAssert("entryGetKeyCopy:: unexpected key,value types: "
                    + (keyClass != null ? keyClass.getName() : "(null)") + ','
                    + valClass.getName());
          }
          return null; // indicates retry for the caller
        }
        return key;
      } else {
        // tableInfo is null so cannot do anything here
        return key;
      }
    }

    @Override
    public final void entryCheckValue(final Object val) {
      if (val != null) {
        final Class<?> valClass = val.getClass();
        if (!(valClass == byte[].class || valClass == byte[][].class
            || valClass == OffHeapRow.class
            || valClass == OffHeapRowWithLobs.class || isValueToken(val))) {
          GemFireXDUtils.throwAssert("entryCheckValue:: unexpected value type: "
              + valClass.getName());
        }
      }
    }

    @Override
    public final int entryKeySizeInBytes(final Object key,
        final AbstractRegionEntry entry) {
      if (key != null) {
        final Class<?> keyClass = key.getClass();
        if (keyClass == byte[].class) {
          final byte[] kbytes = (byte[]) key;
          return ReflectionSingleObjectSizer.OBJECT_SIZE + kbytes.length
          /* ExtraInfo reference */
          + ReflectionSingleObjectSizer.REFERENCE_SIZE;
        } else if (keyClass == CompactCompositeRegionKey.class) {
          return (int)((CompactCompositeRegionKey) key).estimateMemoryUsage();
        } else if (keyClass == Long.class) {
          return (Long.SIZE / 8) + ReflectionSingleObjectSizer.OBJECT_SIZE;
        } else if (keyClass == CompositeRegionKey.class) {
          return (int)((CompositeRegionKey)key).estimateMemoryUsage();
        } else if (Sizeable.class.isAssignableFrom(keyClass)) {
          return ((Sizeable)key).getSizeInBytes();
        }
        else if (keyClass == DataValueDescriptor[].class) {
          DataValueDescriptor[] arr = (DataValueDescriptor[])key;
          int memoryUsage = 2 * ReflectionSingleObjectSizer.OBJECT_SIZE;
          for (int i = 0; i < arr.length; i++) {
            memoryUsage += arr[i].estimateMemoryUsage();
          }
          return memoryUsage;
        } 
        // Fix for #51110
        else if (DataValueDescriptor.class.isAssignableFrom(keyClass)) {
          return ((DataValueDescriptor)key).estimateMemoryUsage();
        }
        else {
          GemFireXDUtils
              .throwAssert("entryKeySizeInBytes:: unexpected key type: "
                  + keyClass.getName());
        }
      }
      final ExtraTableInfo tableInfo = (ExtraTableInfo) entry
          .getContainerInfo();
      if (tableInfo != null) {
        OffHeapByteSource ohValue = null;
        GemFireContainer gfc = null;
        try {
          gfc = tableInfo.getGemFireContainer();
        } catch (StandardException se) {
          throw new GemFireXDRuntimeException(se);
        }
        @Retained @Released final Object value = RegionEntryUtils.getByteSource(
            (RowLocation) entry, gfc, false, true /* use value in vm only*/); //._getValue();
        try {
          if (value != null) {
            final Class<?> valueClass = value.getClass();
            if (valueClass == byte[].class) {
              byte[] heapValue = (byte[]) value;
              RowFormatter rf = tableInfo.getRowFormatter(heapValue);
              return ReflectionSingleObjectSizer.OBJECT_SIZE
                  + rf.getColumnsWidth(heapValue,
                      tableInfo.getPrimaryKeyFixedColumns(),
                      tableInfo.getPrimaryKeyVarColumns(),
                      tableInfo.getPrimaryKeyFormatter())
                  /* ExtraTableInfo reference */
                  + ReflectionSingleObjectSizer.REFERENCE_SIZE;
            } else if (valueClass == byte[][].class) {
              byte[] heapValue = ((byte[][]) value)[0];
              RowFormatter rf = tableInfo.getRowFormatter(heapValue);
              return ReflectionSingleObjectSizer.OBJECT_SIZE
                  + rf.getColumnsWidth(heapValue,
                      tableInfo.getPrimaryKeyFixedColumns(),
                      tableInfo.getPrimaryKeyVarColumns(),
                      tableInfo.getPrimaryKeyFormatter())
                  /* ExtraTableInfo reference */
                  + ReflectionSingleObjectSizer.REFERENCE_SIZE;
            } else if (OffHeapByteSource.isOffHeapBytesClass(valueClass)) {
              ohValue = (OffHeapByteSource) value;
              RowFormatter rf = tableInfo.getRowFormatter(ohValue);
              return ReflectionSingleObjectSizer.OBJECT_SIZE
                  + rf.getColumnsWidth(ohValue,
                      tableInfo.getPrimaryKeyFixedColumns(),
                      tableInfo.getPrimaryKeyVarColumns(),
                      tableInfo.getPrimaryKeyFormatter())
                  /* ExtraTableInfo reference */
                  + ReflectionSingleObjectSizer.REFERENCE_SIZE;
            } else if (isValueToken(value)) {
              return -1;
            } else {
              GemFireXDUtils
              .throwAssert("entryKeySizeInBytes:: unexpected value type: "
                  + valueClass.getName());
            }

          } else {
            // indicates retry for caller
            return -1;
          }
        } finally {
          if (ohValue != null) {
            ohValue.release();
          }
        }
      }
      throw checkCacheForNullTableInfo("entryKeySizeInBytes");
    }

    @Override
    public final Object fromVersion(byte[] bytes, int bytesLen,
        boolean serialized, final Version version, ByteArrayDataInput in) {
      // handle change in shape of byte[]s for ALTER TABLE
      // add a schemaVersion == TOKEN_RECOVERY_VERSION to indicate the the
      // schema version after recovery should be used for the row
      if (bytes != null) {
        if (serialized) {
          // check for byte[][] values which will need to be dealt with
          // separately
          try {
            if (in == null) {
              in = new ByteArrayDataInput();
            }
            in.initialize(bytes, 0, bytesLen, version);
            if (bytes[0] == DSCODE.ARRAY_OF_BYTE_ARRAYS
                && Version.SQLF_1099.compareTo(version) > 0) {
              // advance a byte to skip the byte[][] DSCODE
              in.readByte();
              return RowFormatter.convertPre11RowWithLobs(in);
            } else {
              return BlobHelper.deserializeBuffer(in, bytes.length);
            }
          } catch (IOException e) {
            throw new SerializationException(
                LocalizedStrings.EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_DESERIALIZING
                    .toLocalizedString(), e);
          } catch (ClassNotFoundException e) {
            throw new SerializationException(
                LocalizedStrings.EntryEventImpl_A_CLASSNOTFOUNDEXCEPTION_WAS_THROWN_WHILE_TRYING_TO_DESERIALIZE_CACHED_VALUE
                    .toLocalizedString(), e);
          }
        } else if (Version.SQLF_1099.compareTo(version) > 0) {
          return RowFormatter.convertPre11Row(bytes);
        } else {
          return bytes;
        }
      } else {
        return null;
      }
    }

    @Override
    public final byte[] fromVersionToBytes(byte[] bytes, int bytesLen,
        boolean serialized, final Version version, ByteArrayDataInput in,
        final HeapDataOutputStream hdos) {
      // handle change in shape of byte[]s for ALTER TABLE
      // add a schemaVersion == TOKEN_RECOVERY_VERSION to indicate the the
      // schema version after recovery should be used for the row
      if (bytes != null) {
        if (serialized) {
          // check for byte[][] values which will need to be dealt with
          // separately
          try {
            final byte dscode = bytes[0];
            switch (dscode) {
            case DSCODE.ARRAY_OF_BYTE_ARRAYS:
              if (Version.SQLF_1099.compareTo(version) > 0) {
                in.initialize(bytes, 0, bytesLen, version);
                // advance a byte to skip the byte[][] DSCODE
                in.readByte();
                return RowFormatter.convertPre11RowWithLobsToBytes(in,
                    DSCODE.ARRAY_OF_BYTE_ARRAYS);
              } else {
                return bytes;
              }
            case DSCODE.DS_FIXED_ID_BYTE:
              if ((bytes[1] == DataSerializableFixedID.GFXD_TYPE
                    && bytes[2] == GfxdSerializable.COMPOSITE_REGION_KEY)
                  || bytes[1] == DataSerializableFixedID.GFXD_GEMFIRE_KEY
                  || bytes[1] == DataSerializableFixedID.GFXD_GLOBAL_ROWLOC
                  || bytes[1] == DataSerializableFixedID.GFXD_FORMATIBLEBITSET
                  || bytes[2] != GfxdDataSerializable.DDL_REGION_VALUE) {
                return bytes;
              } else {
                in.initialize(bytes, 0, bytesLen, version);
                hdos.clearForReuse();
                // register GemFireXD types explicitly for offline mode
                if (GfxdDataSerializable.initTypes()) {
                  DataType.init();
                }
                Object o = BlobHelper.deserializeBuffer(in, bytes.length);
                DataSerializer.writeObject(o, hdos);
                return hdos.toByteArray();
              }
            default:
              // right now only anticipate changes in DSFID types
              return bytes;
            }
          } catch (IOException e) {
            throw new SerializationException(
                LocalizedStrings.EntryEventImpl_AN_IOEXCEPTION_WAS_THROWN_WHILE_DESERIALIZING
                    .toLocalizedString(), e);
          } catch (ClassNotFoundException e) {
            throw new SerializationException(
                LocalizedStrings.EntryEventImpl_A_CLASSNOTFOUNDEXCEPTION_WAS_THROWN_WHILE_TRYING_TO_DESERIALIZE_CACHED_VALUE
                    .toLocalizedString(), e);
          }
        } else if (Version.SQLF_1099.compareTo(version) > 0) {
          return RowFormatter.convertPre11Row(bytes);
        } else {
          return bytes;
        }
      } else {
        return null;
      }
    }

    @Override
    public final Object entryGetContainerInfoForKey(
        final AbstractRegionEntry entry) {
      final Object tableInfo = entry.getContainerInfo();
      return tableInfo != null
          && ((ExtraTableInfo) tableInfo).regionKeyPartOfValue() ? tableInfo
          : null;
    }

    @Override
    public LocalRegion getRegionFromContainerInfo(Object containerInfo) {
      if (containerInfo != null) {
        try {
          final ExtraTableInfo tableInfo = (ExtraTableInfo) containerInfo;
          return tableInfo.getGemFireContainer().getRegion();
        } catch (StandardException se) {
          throw GemFireXDRuntimeException.newRuntimeException("unexpected "
              + "failure in getting GemFireContainer from ExtraTableInfo", se);
        }
      } else {
        return null;
      }
    }

    @Override
    public final int entryHashCode(final Object key,
        final AbstractRegionEntry entry) throws IllegalAccessException {
      final Class<?> keyClass = key != null ? key.getClass() : null;
      final ExtraTableInfo tableInfo;
      if (keyClass == byte[].class) {
        return ResolverUtils.addBytesToHash((byte[]) key, 0);
      } else if ((tableInfo = (ExtraTableInfo) entry.getContainerInfo()) != null) {
        if (tableInfo.regionKeyPartOfValue()) {
          if (keyClass == CompactCompositeRegionKey.class) {
            return key.hashCode();
          }
          else if (keyClass != null) {
            GemFireXDUtils.throwAssert("entryHashCode:: unexpected key type: "
                + keyClass.getName());
          }

          GemFireContainer gfc = null;
          try {
            gfc = tableInfo.getGemFireContainer();
          } catch (StandardException se) {
            throw new GemFireXDRuntimeException(se);
          }
          
          // need to match the hashCode from key bytes only (those need to be
          // evaluated even when no tableInfo is set during recovery)
          @Retained @Released final Object value =  RegionEntryUtils.getByteSource(
              (RowLocation) entry, gfc, false, false); //entry._getValue();
          OffHeapByteSource ohValue = null;
          try {
          if (value != null) {
            final Class<?> valueClass = value.getClass();
            if (valueClass == byte[].class) {
              return entryValueHashCode((byte[]) value, tableInfo);
            } else if (valueClass == byte[][].class) {
              return entryValueHashCode(((byte[][]) value)[0], tableInfo);
            } else if (OffHeapByteSource.isOffHeapBytesClass(valueClass)) {
              ohValue = (OffHeapByteSource)value;
              return entryValueHashCode(ohValue, tableInfo);
            } else if (!isValueToken(value)) {
              GemFireXDUtils
                  .throwAssert("entryHashCode:: unexpected value type: "
                      + valueClass.getName());
            } else {
              // indicates retry for caller
              throw new IllegalAccessException("key and value both invalid");
            }
          } else {
            // indicates retry for caller
            throw new IllegalAccessException("key and value both invalid");
          }
          } finally {
            if (ohValue != null) {
              ohValue.release();
            }
          }
        } else {
          return key.hashCode();
        }
      } else if (key != null) {
        return key.hashCode();
      }
      throw checkCacheForNullTableInfo("entryHashCode");
    }

    @Override
    public final boolean entryEquals(final Object key, final Object value,
        final AbstractRegionEntry entry, final Object other)
        throws IllegalAccessException {
      if (other != null) {
        final Class<?> otherClass = other.getClass();
        if (entry.getClass() == otherClass) {
          return entry == other;
        }
        final ExtraTableInfo tableInfo = (ExtraTableInfo)entry
            .getContainerInfo();
        final Class<?> keyClass = key != null ? key.getClass() : null;
        if (keyClass == byte[].class) {
          return entryEqualsKeyBytes((byte[])key, value, tableInfo, other,
              otherClass);
        }
        else if (tableInfo != null) {
          if (tableInfo.regionKeyPartOfValue()
              && keyClass != CompactCompositeRegionKey.class) {
            return entryEqualsInternal(key, value, null, null, tableInfo,
                entry, other, otherClass);
          }
          else {
            return key.equals(other);
          }
        }
        else if (key != null) {
          return key.equals(other);
        }
        throw checkCacheForNullTableInfo("entryEquals");
      }
      return false;
    }

    @Override
    public final RuntimeException checkCacheForNullKeyValue(String method) {
      return RegionEntryUtils.checkCacheForNullKeyValue(method);
    }

    @Override
    public final void waitingForDataSync(String regionPath,
        Set<PersistentMemberID> membersToWaitFor, Set<Integer> missingBuckets,
        PersistentMemberID myId, String message) {
      // set the service status
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service instanceof FabricServiceImpl) {
        ((FabricServiceImpl) service).notifyWaiting(regionPath,
            membersToWaitFor, missingBuckets, myId, message);
      }
      else {
        FabricServiceImpl.notifyWaitingInLauncher(regionPath, membersToWaitFor,
            missingBuckets, myId, message);
      }
    }

    @Override
    public final void endWaitingForDataSync(String regionPath,
        PersistentMemberID myId) {
      // set the service status
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service != null) {
        ((FabricServiceImpl) service).endNotifyWaiting();
      }
    }

    @Override
    public final void stopNetworkServers() {
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service != null) {
        service.stopAllNetworkServers();
      }
    }

    @Override
    public final void emergencyClose() {
      stopNetworkServers();
    }

    @Override
    public final NonLocalRegionEntry newNonLocalRegionEntry() {
      return new NonLocalRowLocationRegionEntry();
    }

    @Override
    public final NonLocalRegionEntry newNonLocalRegionEntry(
        final RegionEntry re, final LocalRegion region,
        final boolean allowTombstones) {
      return new NonLocalRowLocationRegionEntry(re, region, allowTombstones);
    }

    @Override
    public NonLocalRegionEntry newNonLocalRegionEntry(RegionEntry re, LocalRegion region,
        boolean allowTombstones, boolean faultInValue) {
      return new NonLocalRowLocationRegionEntry(re, region, allowTombstones, faultInValue);
    }

        @Override
    public final NonLocalRegionEntry newNonLocalRegionEntry(final Object key,
        final Object value, final LocalRegion region,
        final VersionTag<?> versionTag) {
      return new NonLocalRowLocationRegionEntry(key, value, region, versionTag);
    }

    @Override
    public final NonLocalRegionEntryWithStats newNonLocalRegionEntryWithStats() {
      return new NonLocalRowLocationRegionEntryWithStats();
    }

    @Override
    public final NonLocalRegionEntryWithStats newNonLocalRegionEntryWithStats(
        final RegionEntry re, final LocalRegion region,
        final boolean allowTombstones) {
      return new NonLocalRowLocationRegionEntryWithStats(re, region,
          allowTombstones);
    }

    @Override
    public final boolean supportsCommandService() {
      return false;
    }

    @Override
    public boolean destroyExistingRegionInCreate(DiskStoreImpl ds,
        LocalRegion region) {
      // if table is being created after DDL replay, then any existing
      // persistence information certainly needs to be cleaned up as it is a new
      // table create (otherwise DataDictionary would have complained)
      final GemFireStore store = Misc.getMemStoreBooting();
      return (store != null && store.initialDDLReplayDone());
    }

    @Override
    public long getRegionUUID(LocalRegion region, InternalRegionArguments ira) {
      long uuid = ira.getUUID();
      if (uuid == 0 || uuid == AbstractDiskRegion.INVALID_UUID) {
        LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
        if (lcc != null) {
          uuid = ((GemFireTransaction)lcc.getTransactionExecute()).getDDLId();
        }
      }
      return uuid;
    }

    @Override
    public long getGatewayUUID(AbstractGatewaySender sender,
        GatewaySenderAttributes attrs) {
      LanguageConnectionContext lcc = Misc.getLanguageConnectionContext();
      if (lcc != null) {
        return ((GemFireTransaction)lcc.getTransactionExecute()).getDDLId();
      }
      else {
        return 0;
      }
    }

    @Override
    public void printStacks(PrintWriter pw) {
      Misc.getMemStoreBooting().getDDLLockService().getLocalLockService()
          .dumpAllRWLocks("PRINTSTACKS", pw, true);
    }

    @Override
    public TXStateProxy getJTAEnlistedTX(LocalRegion region) {
      // GemFireXD should always start JTA transactions at its layer
      return null;
    }

    @Override
    public void beforeReturningOffHeapMemoryToAllocator(long parentAddress, 
        ChunkType chunkType, int dataSizeDelta) {
      OffHeapRowWithLobs.freeLobsIfPresent(parentAddress, chunkType, dataSizeDelta);
      
    }

    @Override
    public EventErrorHandler getEventErrorHandler() {
      return EventErrorHandlerWrapper.getInstance();
    }

    @Override
    public boolean tracePersistFinestON() {
      return GemFireXDUtils.TracePersistIndexFinest;
    }

    @Override
    public String getDDLStatementRegionName() {
      return GemFireStore.DDL_STMTS_REGION;
    }

    @Override
    public boolean isAdmin() {
      GemFireStore.VMKind myVMKind = GemFireXDUtils.getMyVMKind();
      return myVMKind != null && myVMKind.isAdmin();
    }

    @Override
    public boolean isSnappyStore() {
      return Misc.getMemStoreBooting().isSnappyStore();
    }

    @Override
    public boolean isAccessor() {
      GemFireStore.VMKind myVMKind = GemFireXDUtils.getMyVMKind();
      return myVMKind != null && myVMKind.isAccessor();
    }

    @Override
    public boolean isOperationNode() {
      GemFireStore.VMKind myVMKind = GemFireXDUtils.getMyVMKind();
      return myVMKind != null && myVMKind.isAccessorOrStore();
    }

    @Override
    public void log(String traceFlag, String logLine, Throwable t) {
      SanityManager.DEBUG_PRINT(traceFlag, logLine, t);
    }

    @Override
    public boolean isPRForGlobalIndex(PartitionedRegion pr) {
      GemFireContainer container = (GemFireContainer)pr.getUserAttribute();
      if (container != null) {
        return container.isGlobalIndex();
      }
      return false;
    }
    
    @Override
    public String dumpCompactCompositeRegionKey(Object key, String regionPath) {
      if (key instanceof CompactCompositeRegionKey && regionPath != null) {
        CompactCompositeRegionKey ccrk = (CompactCompositeRegionKey)key;
        Region<Object, Object> reg = Misc.getRegion(regionPath, false, false);
        if (reg != null && reg instanceof LocalRegion) {
          LocalRegion lr = (LocalRegion)reg;
          ccrk.setRegionContext(lr);
          return ccrk.toString() + ":" + regionPath;
        }
      }

      return key.getClass().getSimpleName() + ":region[" + regionPath + "]:"
          + key;
    }

    @Override
    public Set<DistributedMember> getDataStores() {
      // KN: TODO write implementation
      return null;
    }

    // assumes val to be non null
    public final int getBucketIdFromRegionEntry(RegionEntry val) {
      return ((RowLocation)val).getBucketID();
    }

    @Override
    public Properties getSecurityPropertiesForReconnect() {
      FabricServiceImpl impl = ((FabricServiceImpl) FabricServiceImpl
          .getInstance());
      if (impl != null) {
        return impl.getSecurityPropertiesFromRestarter();
      } else {
        return null;
      }
    }

    public ExternalTableMetaData fetchSnappyTablesHiveMetaData(
        PartitionedRegion region) {
      GemFireContainer container = (GemFireContainer)region.getUserAttribute();
      if (container != null) {
        ExternalTableMetaData metaData = container.fetchHiveMetaData(false);
        if (metaData == null) {
          throw new IllegalStateException("Table for container " +
              container.getQualifiedTableName() + " not found in hive metadata");
        }
        return metaData;
      } else {
        throw new IllegalStateException("Table for " + region.getFullPath() +
            " not found in containers");
      }
    }
  };

  /**
   * Get fixed and var column positions for the key bytes
   * 
   * @param pkrf
   * @param fixedCols
   * @param varCols
   */
  private static void getFixedAndVarColumns(RowFormatter pkrf,
      TIntArrayList fixedCols, TIntArrayList varCols) {
    int[] positionMap = pkrf.positionMap;

    for (int i = 0; i < positionMap.length; i++) {
      ColumnDescriptor cd = pkrf.getColumnDescriptor(i);
      if (!cd.isLob) {
        if ((positionMap[i] < 0) && (cd.fixedWidth == -1)) {
          varCols.add(i + 1);
        } else if ((positionMap[i] >= 0) && (cd.fixedWidth != -1)) {
          fixedCols.add(i + 1);
        }
      }
    }

  }

  /**
   * Returns the hash for the given RegionEntry using the key bytes;
   * 
   * @return an integer as the hash code for the key of given RegionEntry
   */
  public static final int entryHashCode(final byte[] key, final byte[] vbytes,
      final OffHeapByteSource bs, final ExtraInfo tableInfo)
      throws IllegalAccessException {
    if (key != null) {
      /*
       * original code return ResolverUtils.addBytesToHash(key, 0);
       */
      // This is similar to entryValueHashCode().
      // The difference in the byte[] is that there is no versionByte at the
      // beginning
      // And the RowFormatter is different
      int hash = 0;
      boolean needSpacePadding = false, varcharTrimmed = false;
      if (tableInfo != null) {
        RowFormatter pkrf = tableInfo.getPrimaryKeyFormatter();
        if (pkrf != null) {
          final TIntArrayList fixedCols = new TIntArrayList(
              pkrf.getNumColumns());
          final TIntArrayList varCols = new TIntArrayList(pkrf.getNumColumns());
          getFixedAndVarColumns(pkrf, fixedCols, varCols);
          int keyIndex, offset, width, offsetFromMap;
          int varOffset = 0;
          final int numFixedCols = fixedCols.size();
          if (numFixedCols > 0) {
            for (int index = 0; index < numFixedCols; ++index) {
              keyIndex = fixedCols.getQuick(index) - 1;
              offsetFromMap = pkrf.positionMap[keyIndex];
              width = pkrf.getColumnDescriptor(keyIndex).fixedWidth;
              hash = ResolverUtils.addBytesToHash(key, offsetFromMap, width,
                  hash);
              varOffset += width;
            }
          }
          final int numVarWidths = varCols.size();
          if (numVarWidths > 0) {
            // next add the variable width columns to hash
            final int[] varOffsets = new int[numVarWidths];
            for (int index = 0; index < numVarWidths; ++index) {
              keyIndex = varCols.getQuick(index) - 1;
              offsetFromMap = pkrf.positionMap[keyIndex];
              assert offsetFromMap <= 0 : "unexpected offsetFromMap="
                  + offsetFromMap;
              if (offsetFromMap < 0) {

                final long offsetAndWidth = pkrf.getOffsetAndWidth(keyIndex,
                    key, offsetFromMap, pkrf.columns[keyIndex]);
                if (offsetAndWidth >= 0) {
                  int idx = 0;
                  offset = (int)(offsetAndWidth >>> Integer.SIZE);
                  width = (int)offsetAndWidth;
                  // hash = ResolverUtils.addBytesToHash(key, offset, width,
                  // hash);
                  int typeId = pkrf.getColumnDescriptor(keyIndex).getType()
                      .getTypeId().getTypeFormatId();
                  if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
                    idx = offset + width - 1;
                    while (idx >= offset && key[idx] == 0x20) {
                      idx--;
                    }
                    hash = ResolverUtils.addBytesToHash(key, offset, idx + 1
                        - offset, hash);
                    varcharTrimmed = true;
                  } else {
                    hash = ResolverUtils.addBytesToHash(key, offset, width,
                        hash);
                  }
                  if (/*
                       * (typeId == StoredFormatIds.VARCHAR_TYPE_ID) ||
                       */(typeId == StoredFormatIds.CHAR_TYPE_ID)
                      || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
                    int maxWidth = pkrf.getColumnDescriptor(keyIndex).getType()
                        .getMaximumWidth();
                    if (width < maxWidth) {
                      needSpacePadding = true;
                    }
                    while (width++ < maxWidth) {
                      // blank padding for CHAR/VARCHAR/VARCHAR FOR BIT DATA
                      hash = ResolverUtils.addByteToHash((byte) 0x20, hash);
                    }
                    width--;
                  }
                  varOffsets[index] = varOffset;
                  // varOffset += width;
                  if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
                    varOffset = varOffset + (idx + 1 - offset);
                  } else {
                    varOffset += width;
                  }
                } else if (offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
                  varOffsets[index] = pkrf.getNullIndicator(varOffset);
                } else {
                  assert offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT;
                  varOffsets[index] = pkrf.getOffsetDefaultToken();
                }
              } else {
                varOffsets[index] = pkrf.getOffsetDefaultToken();
              }
            }
            // lastly add the offset values including those for null and default
            final int offsetBytes = pkrf.getNumOffsetBytes();
            for (int index = 0; index < numVarWidths; ++index) {
              hash = computeIntHash(varOffsets[index], offsetBytes, hash);
            }
          }

        } // pkrf != null
      } // tableInfo != null
      if (needSpacePadding || varcharTrimmed) {
        return hash;
      } else {
        return ResolverUtils.addBytesToHash(key, 0);
      }
    } else if (tableInfo != null) {
      if (tableInfo.regionKeyPartOfValue()) {
        if (vbytes != null) {
          return entryValueHashCode(vbytes, tableInfo);
        }
        else if (bs != null) {
          return entryValueHashCode(bs, tableInfo);
        }
        else {
          // indicates retry for caller
          throw new IllegalAccessException("key and value both invalid");
        }
      }
    }
    throw checkCacheForNullTableInfo("entryHashCode");
  }

  private static final int entryValueHashCode(final byte[] vbytes,
      final ExtraInfo tableInfo) {
    int hash = 0;
    final int[] fixedColumnPositions = tableInfo.getPrimaryKeyFixedColumns();
    final int[] varColumnPositions = tableInfo.getPrimaryKeyVarColumns();

    final RowFormatter rf = tableInfo.getRowFormatter(vbytes);
    // first add the fixed width columns to hash
    int keyIndex, offset, width, offsetFromMap;
    int varOffset = 0;

    if (fixedColumnPositions != null) {
      for (int index = 0; index < fixedColumnPositions.length; ++index) {
        keyIndex = fixedColumnPositions[index] - 1;
        offsetFromMap = rf.positionMap[keyIndex];
        assert offsetFromMap > 0 : "unexpected offsetFromMap=" + offsetFromMap;
        width = rf.getColumnDescriptor(keyIndex).fixedWidth;
        hash = ResolverUtils.addBytesToHash(vbytes, offsetFromMap, width, hash);
        varOffset += width;
      }
    }
    if (varColumnPositions != null) {
      // next add the variable width columns to hash
      final int numVarWidths = varColumnPositions.length;
      final int[] varOffsets = new int[numVarWidths];
      final RowFormatter pkrf = tableInfo.getPrimaryKeyFormatter();
      for (int index = 0; index < numVarWidths; ++index) {
        keyIndex = varColumnPositions[index] - 1;
        offsetFromMap = rf.positionMap[keyIndex];

        assert offsetFromMap <= 0 : "unexpected offsetFromMap=" + offsetFromMap;
        if (offsetFromMap < 0) {
          final long offsetAndWidth;
          offsetAndWidth = rf.getOffsetAndWidth(keyIndex, vbytes, offsetFromMap, rf.columns[keyIndex]);
          if (offsetAndWidth >= 0) {
            int varCharWidth = 0;
            offset = (int)(offsetAndWidth >>> Integer.SIZE);
            width = (int)offsetAndWidth;
            // hash = ResolverUtils.addBytesToHash(vbytes, offset, width, hash);
            int typeId = rf.getColumnDescriptor(keyIndex).getType().getTypeId()
                .getTypeFormatId();
            if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
              // modify width, truncate trailing blanks for varchar                
              int idx = offset + width - 1;
              while (idx >= offset && vbytes[idx] == 0x20) {
                idx--;
              }
              varCharWidth = idx + 1 - offset;
              hash = ResolverUtils.addBytesToHash(vbytes, offset, varCharWidth, hash);
            }
            else {
              hash = ResolverUtils.addBytesToHash(vbytes, offset, width, hash);
            }
            if (/*
                 * (typeId == StoredFormatIds.VARCHAR_TYPE_ID) ||
                 */(typeId == StoredFormatIds.CHAR_TYPE_ID)
                || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
              int maxWidth = rf.getColumnDescriptor(keyIndex).getType()
                  .getMaximumWidth();
              while (width++ < maxWidth) {
                // blank padding for CHAR/VARCHAR/VARCHAR FOR BIT DATA
                hash = ResolverUtils.addByteToHash((byte) 0x20, hash);
              }
              width--;
            }
            varOffsets[index] = varOffset;
            // varOffset += width;
            if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
              varOffset += varCharWidth;
            } else {
              varOffset += width;
            }
          } else if (offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
            varOffsets[index] = rf.getNullIndicator(varOffset);
          } else {
            assert offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT;
            varOffsets[index] = pkrf.getOffsetDefaultToken();
          }
        } else {
          varOffsets[index] = pkrf.getOffsetDefaultToken();
        }
      }
      // lastly add the offset values including those for null and default
      final int offsetBytes = pkrf.getNumOffsetBytes();
      for (int index = 0; index < numVarWidths; ++index) {
        hash = computeIntHash(varOffsets[index], offsetBytes, hash);
      }
    }
    return hash;
  }

  private static final int entryValueHashCode(
      @Unretained final OffHeapByteSource bs, final ExtraInfo tableInfo) {
    int hash = 0;
    final int[] fixedColumnPositions = tableInfo.getPrimaryKeyFixedColumns();
    final int[] varColumnPositions = tableInfo.getPrimaryKeyVarColumns();

    final RowFormatter rf = tableInfo.getRowFormatter(bs);
    // first add the fixed width columns to hash
    int keyIndex, offset, width, offsetFromMap;
    int varOffset = 0;

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bs.getLength();
    final long memAddr = bs.getUnsafeAddress(0, bytesLen);

    if (fixedColumnPositions != null) {
      for (int index = 0; index < fixedColumnPositions.length; ++index) {
        keyIndex = fixedColumnPositions[index] - 1;
        offsetFromMap = rf.positionMap[keyIndex];
        assert offsetFromMap > 0 : "unexpected offsetFromMap=" + offsetFromMap;
        width = rf.getColumnDescriptor(keyIndex).fixedWidth;
        hash = ServerResolverUtils.addBytesToHash(unsafe, memAddr
            + offsetFromMap, width, hash);
        varOffset += width;
      }
    }
    if (varColumnPositions != null) {
      // next add the variable width columns to hash
      final int numVarWidths = varColumnPositions.length;
      final int[] varOffsets = new int[numVarWidths];
      final RowFormatter pkrf = tableInfo.getPrimaryKeyFormatter();
      for (int index = 0; index < numVarWidths; ++index) {
        keyIndex = varColumnPositions[index] - 1;
        offsetFromMap = rf.positionMap[keyIndex];

        assert offsetFromMap <= 0 : "unexpected offsetFromMap=" + offsetFromMap;
        if (offsetFromMap < 0) {
          final long offsetAndWidth;
          offsetAndWidth = rf.getOffsetAndWidth(keyIndex, unsafe, memAddr,
              bytesLen, offsetFromMap, rf.columns[keyIndex]);
          if (offsetAndWidth >= 0) {
            int varCharWidth = 0;
            offset = (int)(offsetAndWidth >>> Integer.SIZE);
            width = (int)offsetAndWidth;
            int typeId = rf.getColumnDescriptor(keyIndex).getType().getTypeId()
                .getTypeFormatId();
            if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
              // modify width, truncate trailing blanks for varchar
              final long memOffset = memAddr + offset;
              varCharWidth = getVarLengthWithWhiteSpaceTruncated(unsafe,
                  memOffset, width);
              hash = ServerResolverUtils.addBytesToHash(unsafe, memOffset,
                  varCharWidth, hash);
            }
            else {
              hash = ServerResolverUtils.addBytesToHash(unsafe, memAddr
                  + offset, width, hash);
            }
            if (/*
                 * (typeId == StoredFormatIds.VARCHAR_TYPE_ID) ||
                 */(typeId == StoredFormatIds.CHAR_TYPE_ID)
                || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
              int maxWidth = rf.getColumnDescriptor(keyIndex).getType()
                  .getMaximumWidth();
              while (width++ < maxWidth) {
                // blank padding for CHAR/VARCHAR/VARCHAR FOR BIT DATA
                hash = ResolverUtils.addByteToHash((byte) 0x20, hash);
              }
              width--;
            }
            varOffsets[index] = varOffset;
            // varOffset += width;
            if (typeId == StoredFormatIds.VARCHAR_TYPE_ID) {
              varOffset += varCharWidth;
            } else {
              varOffset += width;
            }
          } else if (offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_NULL) {
            varOffsets[index] = rf.getNullIndicator(varOffset);
          } else {
            assert offsetAndWidth == RowFormatter.OFFSET_AND_WIDTH_IS_DEFAULT;
            varOffsets[index] = pkrf.getOffsetDefaultToken();
          }
        } else {
          varOffsets[index] = pkrf.getOffsetDefaultToken();
        }
      }
      // lastly add the offset values including those for null and default
      final int offsetBytes = pkrf.getNumOffsetBytes();
      for (int index = 0; index < numVarWidths; ++index) {
        hash = computeIntHash(varOffsets[index], offsetBytes, hash);
      }
    }
    return hash;
  }

  private static int getVarLengthWithWhiteSpaceTruncated(
      final UnsafeWrapper unsafe, final long memOffset, final int width) {
    long memIdx = memOffset + width - 1;
    while (memIdx >= memOffset && unsafe.getByte(memIdx) == 0x20) {
      memIdx--;
    }
    return (int)(memIdx + 1 - memOffset);
  }

  /**
   * Get size of key object in bytes. A negative return value indicates both
   * serialized key and value bytes were null and caller should retry the
   * operation (can happen if update and read happen concurrently).
   */
  public static final int entryKeySizeInBytes(final Object key,
      final Object vbytes, final ExtraInfo tableInfo) {
    if (key != null) {
      final Class<?> keyClass = key.getClass();
      if (keyClass == byte[].class) {
        final byte[] kbytes = (byte[]) key;
        return ReflectionSingleObjectSizer.OBJECT_SIZE + kbytes.length
        /* ExtraInfo reference */
        + ReflectionSingleObjectSizer.REFERENCE_SIZE;
      } else if (keyClass == CompactCompositeRegionKey.class) {
        return (int)((CompactCompositeRegionKey) key).estimateMemoryUsage();
      } else if (keyClass == Long.class) {
        return (Long.SIZE / 8) + ReflectionSingleObjectSizer.OBJECT_SIZE;
      } else if (keyClass == CompositeRegionKey.class) {
        return (int)((CompositeRegionKey)key).estimateMemoryUsage();
      } else {
        GemFireXDUtils.throwAssert("entryKeySizeInBytes:: unexpected key type: "
            + keyClass.getName());
      }
    }
    if (tableInfo != null) {
      if (vbytes != null) {
        final Class<?> vclass = vbytes.getClass();
        final byte[] bytes;
        if (vclass == byte[].class) {
          bytes = (byte[])vbytes;
        }
        else if (vclass == byte[][].class) {
          bytes = ((byte[][])vbytes)[0];
        }
        else {
          final OffHeapByteSource bs = (OffHeapByteSource)vbytes;
          RowFormatter rf = tableInfo.getRowFormatter(bs);
          return ReflectionSingleObjectSizer.OBJECT_SIZE
              + rf.getColumnsWidth(
                  bs, tableInfo.getPrimaryKeyFixedColumns(),
                  tableInfo.getPrimaryKeyVarColumns(),
                  tableInfo.getPrimaryKeyFormatter()) 
              /* ExtraTableInfo reference */
              + ReflectionSingleObjectSizer.REFERENCE_SIZE;
        }
        RowFormatter rf = tableInfo.getRowFormatter(bytes);
        return ReflectionSingleObjectSizer.OBJECT_SIZE
            + rf.getColumnsWidth(
                bytes, tableInfo.getPrimaryKeyFixedColumns(),
                tableInfo.getPrimaryKeyVarColumns(),
                tableInfo.getPrimaryKeyFormatter()) 
            /* ExtraTableInfo reference */
            + ReflectionSingleObjectSizer.REFERENCE_SIZE;
      } else {
        // indicates retry for caller
        return -1;
      }
    }
    throw checkCacheForNullTableInfo("entryKeySizeInBytes");
  }

  /**
   * Get DVD for column at given index. Null return value indicates both
   * serialized key and value bytes were null and caller should retry the
   * operation (can happen if update and read happen concurrently).
   */
  public static final DataValueDescriptor entryKeyColumn(final byte[] kbytes,
      final Object vbytes, final ExtraInfo tableInfo, final int index) {
    if (tableInfo != null) {
      try {
        if (kbytes != null) {
          final RowFormatter rf = tableInfo.getPrimaryKeyFormatter();
          return rf.getColumn(index + 1, kbytes);
        }
        else {
          if (vbytes != null) {
            final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
            final Class<?> vclass = vbytes.getClass();
            if (vclass == byte[].class) {
              final byte[] bytes = (byte[])vbytes;
              final RowFormatter rf = tableInfo.getRowFormatter(bytes);
              return rf.getColumn(keyPositions[index], bytes);
            }
            else if (vclass == byte[][].class) {
              final byte[] bytes = ((byte[][])vbytes)[0];
              final RowFormatter rf = tableInfo.getRowFormatter(bytes);
              return rf.getColumn(keyPositions[index], bytes);
            }
            else if (vclass == OffHeapRow.class) {
              final OffHeapRow bs = (OffHeapRow)vbytes;
              final RowFormatter rf = tableInfo.getRowFormatter(bs);
              return rf.getColumn(keyPositions[index], bs);
            }
            else {
              final OffHeapRowWithLobs bs = (OffHeapRowWithLobs)vbytes;
              final RowFormatter rf = tableInfo.getRowFormatter(bs);
              return rf.getColumn(keyPositions[index], bs);
            }
          }
          else {
            // indicates retry for the caller
            return null;
          }
        }
      } catch (StandardException se) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "entryKeyColumn: unexpected exception", se);
      }
    }
    throw checkCacheForNullTableInfo("entryKeyColumn");
  }

  /**
   * Set the value in given DVD for column at given index. Null return value
   * indicates both serialized key and value bytes were null and caller should
   * retry the operation (can happen if update and read happen concurrently).
   */
  public static final boolean entrySetKeyColumn(final DataValueDescriptor dvd,
      final byte[] kbytes, final Object vbytes, final ExtraInfo tableInfo,
      final int index) {
    if (tableInfo != null) {
      try {
        if (kbytes != null) {
          final RowFormatter rf = tableInfo.getPrimaryKeyFormatter();
          rf.setDVDColumn(dvd, index, kbytes);
          return true;
        }
        else if (vbytes != null) {
          final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
          final Class<?> vclass = vbytes.getClass();
          if (vclass == byte[].class) {
            final byte[] bytes = (byte[])vbytes;
            final RowFormatter rf = tableInfo.getRowFormatter(bytes);
            rf.setDVDColumn(dvd, keyPositions[index] - 1, bytes);
          }
          else if (vclass == OffHeapRow.class) {
            final OffHeapRow bs = (OffHeapRow)vbytes;
            final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
            final int bytesLen = bs.getLength();
            final long memAddr = bs.getUnsafeAddress(0, bytesLen);

            final RowFormatter rf = tableInfo.getRowFormatter(memAddr,
                bs);
            rf.setDVDColumn(dvd, keyPositions[index] - 1, unsafe, memAddr,
                bytesLen, bs);
          }
          else {
            final OffHeapRowWithLobs bs = (OffHeapRowWithLobs)vbytes;
            final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
            final int bytesLen = bs.getLength();
            final long memAddr = bs.getUnsafeAddress(0, bytesLen);

            final RowFormatter rf = tableInfo.getRowFormatter(memAddr,
                bs);
            rf.setDVDColumn(dvd, keyPositions[index] - 1, unsafe, memAddr,
                bytesLen, bs);
          }
          return true;
        }
        else {
          // indicates retry for caller
          return false;
        }
      } catch (StandardException se) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "entryKeyColumn: unexpected exception", se);
      }
    }
    throw checkCacheForNullTableInfo("entrySetKeyColumn");
  }

  /**
   * Get DVDs for all key columns. A false return value indicates both
   * serialized key and value bytes were null and caller should retry the
   * operation (can happen if update and read happen concurrently).
   */
  public static final boolean entryKeyColumns(final byte[] kbytes,
      final Object vbytes, final ExtraInfo tableInfo,
      final DataValueDescriptor[] keys) {
    if (tableInfo != null) {
      try {
        if (kbytes != null) {
          final RowFormatter rf = tableInfo.getPrimaryKeyFormatter();
          DataValueDescriptor dvd;

          for (int index = 0; index < keys.length; ++index) {
            dvd = rf.getColumn(index + 1, kbytes);
            if (keys[index] == null) {
              keys[index] = dvd;
            } else {
              keys[index].setValue(dvd);
            }
          }
        } else if (vbytes != null) {
          final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
          final Class<?> vclass = vbytes.getClass();
          if (vclass == byte[].class) {
            final byte[] bytes = (byte[])vbytes;
            final RowFormatter rf = tableInfo.getRowFormatter(bytes);
            DataValueDescriptor dvd;
            for (int index = 0; index < keys.length; ++index) {
              dvd = rf.getColumn(keyPositions[index], bytes);
              if (keys[index] == null) {
                keys[index] = dvd;
              }
              else {
                keys[index].setValue(dvd);
              }
            }
          }
          else if (vclass == byte[][].class) {
            final byte[] bytes = ((byte[][])vbytes)[0];
            final RowFormatter rf = tableInfo.getRowFormatter(bytes);
            DataValueDescriptor dvd;
            for (int index = 0; index < keys.length; ++index) {
              dvd = rf.getColumn(keyPositions[index], bytes);
              if (keys[index] == null) {
                keys[index] = dvd;
              }
              else {
                keys[index].setValue(dvd);
              }
            }
          }
          else if (vclass == OffHeapRow.class) {
            final OffHeapRow bs = (OffHeapRow)vbytes;
            final RowFormatter rf = tableInfo.getRowFormatter(bs);
            DataValueDescriptor dvd;
            for (int index = 0; index < keys.length; ++index) {
              dvd = rf.getColumn(keyPositions[index], bs);
              if (keys[index] == null) {
                keys[index] = dvd;
              }
              else {
                keys[index].setValue(dvd);
              }
            }
          }
          else {
            final OffHeapRowWithLobs bs = (OffHeapRowWithLobs)vbytes;
            final RowFormatter rf = tableInfo.getRowFormatter(bs);
            DataValueDescriptor dvd;
            for (int index = 0; index < keys.length; ++index) {
              dvd = rf.getColumn(keyPositions[index], bs);
              if (keys[index] == null) {
                keys[index] = dvd;
              }
              else {
                keys[index].setValue(dvd);
              }
            }
          }
        } else {
          // indicates retry for caller
          return false;
        }
        return true;
      } catch (StandardException se) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "entryKeyColumns: unexpected exception", se);
      }
    } else {
      throw checkCacheForNullTableInfo("entryKeyColumns");
    }
  }

  /**
   * Get DVD for column at given index. Null return value indicates both
   * serialized key and value bytes were null and caller should retry the
   * operation (can happen if update and read happen concurrently).
   */
  public static final DataValueDescriptor entryKeyColumn(final Object key,
      final Object value, final ExtraInfo tableInfo, final int index) {
    if (tableInfo != null) {
      try {
        if (key != null) {
          final Class<?> keyClass = key.getClass();
          if (keyClass == byte[].class) {
            final RowFormatter rf = tableInfo.getPrimaryKeyFormatter();
            final byte[] kbytes = (byte[]) key;

            return rf.getColumn(index + 1, kbytes);
          } else if (key instanceof CompactCompositeKey) {
            return ((CompactCompositeKey) key).getKeyColumn(index);
          } else {
            GemFireXDUtils.throwAssert("entryKeyColumn:: unexpected key type: "
                + keyClass.getName());
          }
        } else {
          if (value != null) {
            final Class<?> valClass = value.getClass();
            if (valClass == byte[].class) {
              byte[] heapValue = (byte[]) value;
              final RowFormatter rf = tableInfo.getRowFormatter(heapValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              return  rf.getColumn(  keyPositions[index], heapValue) ;
            } else if (valClass == byte[][].class) {
              byte[] heapValue = ((byte[][]) value)[0];
              final RowFormatter rf = tableInfo.getRowFormatter(heapValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              return  rf.getColumn(  keyPositions[index], heapValue) ;
            } else if (valClass == OffHeapRow.class) {
              OffHeapRow ohValue = (OffHeapRow) value;
              final RowFormatter rf = tableInfo.getRowFormatter(ohValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              return  rf.getColumn(  keyPositions[index], ohValue) ;
            } else if (valClass == OffHeapRowWithLobs.class) {
              OffHeapRowWithLobs ohValue = (OffHeapRowWithLobs) value;
              final RowFormatter rf = tableInfo.getRowFormatter(ohValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              return  rf.getColumn(  keyPositions[index], ohValue) ;
            } else if (isValueToken(value)) {
              return null;
            } else {
              GemFireXDUtils
                  .throwAssert("entryKeyColumn:: unexpected value type: "
                      + valClass.getName());
            }
          }
          // indicates retry for caller
          return null;
        }
      } catch (StandardException se) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "entryKeyColumn: unexpected exception", se);
      }
    }
    throw checkCacheForNullTableInfo("entryKeyColumn");
  }

  /**
   * Get DVDs for all key columns. A false return value indicates both
   * serialized key and value bytes were null and caller should retry the
   * operation (can happen if update and read happen concurrently).
   */
  public static final boolean entryKeyColumns(final Object key,
      final Object value, final ExtraInfo tableInfo,
      final DataValueDescriptor[] keys) {
    if (tableInfo != null) {
      try {
        if (key != null) {
          final Class<?> keyClass = key.getClass();
          if (keyClass == byte[].class) {
            final byte[] kbytes = (byte[]) key;
            assert kbytes != null : "unexpected null kbytes";
            final RowFormatter rf = tableInfo.getPrimaryKeyFormatter();
            DataValueDescriptor dvd;

            for (int index = 0; index < keys.length; ++index) {
              dvd = rf.getColumn(index + 1, kbytes);
              if (keys[index] == null) {
                keys[index] = dvd;
              } else {
                keys[index].setValue(dvd);
              }
            }
          } else if (key instanceof CompactCompositeKey) {
            ((CompactCompositeKey) key).getKeyColumns(keys);
          } else {
            GemFireXDUtils.throwAssert("entryKeyColumns:: unexpected key type: "
                + keyClass.getName());
          }
          return true;
        } else {
          if (value != null) {
            final Class<?> valClass = value.getClass();
            if (valClass == byte[].class) {
              byte[] heapValue = (byte[]) value;
              final RowFormatter rf = tableInfo.getRowFormatter(heapValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              DataValueDescriptor dvd;
              for (int index = 0; index < keys.length; ++index) {
                dvd =  rf.getColumn(keyPositions[index],  heapValue) ;
                if (keys[index] == null) {
                  keys[index] = dvd;
                } else {
                  keys[index].setValue(dvd);
                }
              }
              return true;
            } else if (valClass == byte[][].class) {
              byte[] heapValue = ((byte[][]) value)[0];
              final RowFormatter rf = tableInfo.getRowFormatter(heapValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              DataValueDescriptor dvd;
              for (int index = 0; index < keys.length; ++index) {
                dvd =  rf.getColumn(keyPositions[index],  heapValue) ;
                if (keys[index] == null) {
                  keys[index] = dvd;
                } else {
                  keys[index].setValue(dvd);
                }
              }
              return true;
            } else if (valClass == OffHeapRow.class) {
              OffHeapRow ohValue = (OffHeapRow) value;
              final RowFormatter rf = tableInfo.getRowFormatter(ohValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              DataValueDescriptor dvd;
              for (int index = 0; index < keys.length; ++index) {
                dvd =  rf.getColumn(keyPositions[index],  ohValue) ;
                if (keys[index] == null) {
                  keys[index] = dvd;
                } else {
                  keys[index].setValue(dvd);
                }
              }
              return true;
            } else if (valClass == OffHeapRowWithLobs.class) {
              OffHeapRowWithLobs ohValue = (OffHeapRowWithLobs) value;
              final RowFormatter rf = tableInfo.getRowFormatter(ohValue);
              final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
              DataValueDescriptor dvd;
              for (int index = 0; index < keys.length; ++index) {
                dvd =  rf.getColumn(keyPositions[index],  ohValue) ;
                if (keys[index] == null) {
                  keys[index] = dvd;
                } else {
                  keys[index].setValue(dvd);
                }
              }
              return true;
            } else if (isValueToken(value)) {
              // indicates retry for caller
              return false;
            }
          }
          // indicates retry for caller
          return false;
        }
      } catch (StandardException se) {
        throw GemFireXDRuntimeException.newRuntimeException(
            "entryKeyColumns: unexpected exception", se);
      }
    } else {
      throw checkCacheForNullTableInfo("entryKeyColumns");
    }
  }

  public static final void entryKeyString(final Object key, final Object value,
      final ExtraInfo tableInfo, final StringBuilder sb) {
    try {
      if (tableInfo != null) {
        if (tableInfo.regionKeyPartOfValue()) {
          DataValueDescriptor dvd;
          final int nCols = tableInfo.getPrimaryKeyColumns().length;
          assert nCols > 0 : "unexpected zero nCols";
          sb.append('(');
          if (nCols == 1) {
            dvd = entryKeyColumn(key, value, tableInfo, 0);
            sb.append(dvd);
          } else {
            final DataValueDescriptor[] keys = new DataValueDescriptor[nCols];
            entryKeyColumns(key, value, tableInfo, keys);
            for (int index = 0; index < keys.length; ++index) {
              dvd = keys[index];
              if (index > 0) {
                sb.append(',');
              }
              sb.append("element[").append(index).append("]=").append(dvd);
            }
          }
          sb.append(')');
        } else if (key instanceof byte[]) {
          sb.append(Arrays.toString((byte[]) key));
        } else {
          sb.append(key);
        }
      } else if (key instanceof byte[]) {
        sb.append(Arrays.toString((byte[]) key));
      } else {
        sb.append(key);
      }
    } catch (Throwable e) {
      sb.append("RegionEntryUtils::entryKeyString:Exception in getting "
          + "String for the entry. ExceptionString = ");
      sb.append(e.toString());
      sb.append(";value = ");
      ArrayUtils.objectString(value);
      // Asif: Not putting additional info to it as in case of OffHeap , there
      // is risk of recursion if invoked
      // toString methods..
    }
  }

  public static final Object entryGetRegionKey(final Object key,
      final Object value) {
    if (key != null && key.getClass() == CompactCompositeRegionKey.class) {
      // if we have a valid value then no key required and just return null
      if (value != null) {
        final Class<?> valClass = value.getClass();
        if (valClass == byte[].class || valClass == byte[][].class) {
          return null;
        }
        else if (OffHeapByteSource.isOffHeapBytesClass(valClass)
            || valClass == DataAsAddress.class) {
          return null;
        }
      }

      final CompactCompositeRegionKey ccrk = (CompactCompositeRegionKey)key;
      final byte[] keyBytes = ccrk.getKeyBytes();
      if (keyBytes != null) {
        return keyBytes;
      }
      // (unneeded snapshot that will very soon be discarded in insert */
      //return ccrk.snapshotKeyFromValue(false);
    }
    return key;
  }

  public static final ExtraTableInfo entryGetTableInfo(final Object owner,
      final Object key, final Object value) {
    if (owner instanceof LocalRegion) {
      final GemFireContainer container = (GemFireContainer) ((LocalRegion) owner)
          .getUserAttribute();
      if (container != null) {
        return container.getExtraTableInfo(value);
      }
    }
    if (key != null && key.getClass() == CompactCompositeRegionKey.class) {
      return ((CompactCompositeRegionKey) key).getTableInfo();
    }
    return null;
  }

  public static final RuntimeException checkCacheForNullTableInfo(
      final String method) {
    Misc.checkIfCacheClosing(null);
    return new GemFireXDRuntimeException(method
        + ": tableInfo should be non-null by this time");
  }

  public static final RuntimeException checkCacheForNullKeyValue(
      final String method) {
    Misc.checkIfCacheClosing(null);
    return new GemFireXDRuntimeException(method
        + ": either key or value should be valid by this time");
  }

  private static final boolean entryEqualsCCKey(final byte[] kbytes,
      final Object value, final ExtraInfo tableInfo,
      final CompactCompositeKey other) throws IllegalAccessException {
    int tries = 1;
    do {
      final byte[] otherKbytes = other.getKeyBytes();
      if (otherKbytes == null) {
        final ExtraInfo otherTableInfo = other.tableInfo;
        if (otherTableInfo != null) {
          @Retained @Released
          final Object otherVbytes = other.getValueByteSource();
          if (otherVbytes != null) {
            final Class<?> vclass = otherVbytes.getClass();
            final byte[] vbytes;
            if (vclass == byte[].class) {
              vbytes = (byte[])otherVbytes;
            }
            else if (vclass == byte[][].class) {
              vbytes = ((byte[][])otherVbytes)[0];
            }
            else {
              final OffHeapByteSource vbs =
                  (OffHeapByteSource)otherVbytes;
              try {
                return compareRowBytesToKeyBytes(vbs,
                    otherTableInfo.getRowFormatter(vbs), kbytes,
                    tableInfo.getPrimaryKeyFormatter(),
                    tableInfo.getPrimaryKeyColumns());
              } finally {
                vbs.release();
              }
            }
            return compareRowBytesToKeyBytes(vbytes,
                otherTableInfo.getRowFormatter(vbytes), kbytes,
                tableInfo.getPrimaryKeyFormatter(),
                tableInfo.getPrimaryKeyColumns());
          }
        } else {
          throw checkCacheForNullTableInfo("equals");
        }
      } else {
        return Arrays.equals(kbytes, otherKbytes);
      }
      gfxdSystemCallbacks.entryCheckValue(value);
      if ((tries % AbstractRegionEntry.MAX_READ_TRIES_YIELD) == 0) {
        // enough tries; give other threads a chance to proceed
        Thread.yield();
      }
    } while (tries++ <= AbstractRegionEntry.MAX_READ_TRIES);
    throw checkCacheForNullKeyValue("RegionEntryUtils#entryEqualsCCKey");
  }

  static final boolean entryEqualsKey(final Object key, final Object value,
      byte[] vbytes, OffHeapByteSource vbs, final ExtraInfo tableInfo,
      final CompactCompositeKey other) throws IllegalAccessException {
    final ExtraInfo otherTableInfo = other.tableInfo;

    if (key != null) {
      final Class<?> keyClass = key.getClass();
      if (keyClass == byte[].class) {
        return entryEqualsCCKey((byte[]) key, value, otherTableInfo, other);
      } else if (keyClass == CompactCompositeRegionKey.class
          || keyClass == Long.class || keyClass == CompositeRegionKey.class) {
        return key.equals(other);
      } else {
        GemFireXDUtils.throwAssert("entryEqualsKey:: unexpected key type: "
            + keyClass.getName());
      }
      throw checkCacheForNullKeyValue("RegionEntryUtils#entryEqualsKey");
    } else {
      int tries = 1;
      do {
        final byte[] otherKbytes = other.getKeyBytes();
        if (vbytes == null && vbs == null) {
          if (value != null) {
            final Class<?> valClass = value.getClass();
            if (valClass == byte[].class) {
              vbytes = (byte[])value;
            }
            else if (valClass == byte[][].class) {
              vbytes = ((byte[][]) value)[0];
            }
            else if (OffHeapByteSource.isOffHeapBytesClass(valClass)) {
              vbs = (OffHeapByteSource)value;
            }
            else if (valClass == DataAsAddress.class) {
              vbytes = (byte[])encodedAddressToObject(((DataAsAddress)value)
                  .getEncodedAddress());
            }
            else if (isValueToken(value)) {
              // indicates retry for caller
              throw new IllegalAccessException("key and value both invalid");
            } else {
              GemFireXDUtils.throwAssert("entryEqualsKey:: unexpected value " + "type: " + valClass.getName());
            }
          } else {
            // indicates retry for caller
            throw new IllegalAccessException("key and value both invalid");
          }
        }
        if (otherKbytes != null) {
          if (vbs == null) {
            return compareRowBytesToKeyBytes(vbytes,
                tableInfo.getRowFormatter(vbytes), otherKbytes,
                otherTableInfo.getPrimaryKeyFormatter(),
                otherTableInfo.getPrimaryKeyColumns());
          }
          else {
            return compareRowBytesToKeyBytes(vbs,
                tableInfo.getRowFormatter(vbs), otherKbytes,
                otherTableInfo.getPrimaryKeyFormatter(),
                otherTableInfo.getPrimaryKeyColumns());
          }
        }
        // compare the two row byte arrays
        SimpleMemoryAllocatorImpl.skipRefCountTracking();
        @Retained @Released final Object otherVbytes = other.getValueByteSource();
        SimpleMemoryAllocatorImpl.unskipRefCountTracking();
        if (otherVbytes != null) {
          try {
            if (otherTableInfo == null) {
              throw checkCacheForNullTableInfo("entryEqualsKey");
            }
            final int[] keyPositions = tableInfo.getPrimaryKeyColumns();
            final RowFormatter rf = vbytes != null ? tableInfo.getRowFormatter(
                vbytes) : tableInfo.getRowFormatter(vbs);
            int keyIndex, targetKeyIndex;
            if (otherVbytes.getClass() == byte[].class) {
              byte[] typedOtherVbytes = (byte[]) otherVbytes;
              final RowFormatter otherFormatter = other.tableInfo
                  .getRowFormatter(typedOtherVbytes);
              if (vbs == null) {
                for (int index = 0; index < keyPositions.length; index++) {
                  keyIndex = keyPositions[index] - 1;
                  if (rf.processColumn(vbytes, keyIndex,
                      checkColumnEqualityWithRow, typedOtherVbytes, -1,
                      otherFormatter, keyIndex) < 0) {
                    return false;
                  }
                }
              }
              else {
                final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
                final int bytesLen = vbs.getLength();
                final long memAddr = vbs.getUnsafeAddress(0, bytesLen);
                for (int index = 0; index < keyPositions.length; index++) {
                  keyIndex = keyPositions[index] - 1;
                  if (rf.processColumn(unsafe, memAddr, bytesLen, keyIndex,
                      checkColumnEqualityWithRowOffHeap, typedOtherVbytes, -1,
                      otherFormatter, keyIndex) < 0) {
                    return false;
                  }
                }
              }
            }
            else {
              OffHeapByteSource typedOtherVbytes = (OffHeapByteSource) otherVbytes;
              final RowFormatter otherFormatter = other.tableInfo
                  .getRowFormatter(typedOtherVbytes);
              if (vbytes != null) {
                for (int index = 0; index < keyPositions.length; index++) {
                  keyIndex = keyPositions[index] - 1;
                  if (rf.processColumn(vbytes, keyIndex,
                      checkOffHeapColumnEqualityWithRow, typedOtherVbytes, -1,
                      otherFormatter, keyIndex) < 0) {
                    return false;
                  }
                }
              }
              else {
                final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
                final int bytesLen = vbs.getLength();
                final long memAddr = vbs.getUnsafeAddress(0, bytesLen);
                for (int index = 0; index < keyPositions.length; index++) {
                  keyIndex = keyPositions[index] - 1;
                  if (rf.processColumn(unsafe, memAddr, bytesLen, keyIndex,
                      checkOffHeapColumnEqualityWithRowOffHeap,
                      typedOtherVbytes, -1, otherFormatter, keyIndex) < 0) {
                    return false;
                  }
                }
              }
            }
            return true;
          } finally {
            SimpleMemoryAllocatorImpl.skipRefCountTracking();
            other.releaseValueByteSource(otherVbytes);
            SimpleMemoryAllocatorImpl.unskipRefCountTracking();
          }
        }
        if ((tries % AbstractRegionEntry.MAX_READ_TRIES_YIELD) == 0) {
          // enough tries; give other threads a chance to proceed
          Thread.yield();
        }
      } while (tries++ <= AbstractRegionEntry.MAX_READ_TRIES);
      throw RegionEntryUtils
          .checkCacheForNullKeyValue("RegionEntryUtils#entryEqualsKey");
    }
  }

  private static final boolean entryEqualsInternal(final Object key,
      final Object value, final byte[] vbytes,
      final OffHeapByteSource vbs, final ExtraTableInfo tableInfo,
      final RegionEntry entry, final Object other, final Class<?> otherClass)
      throws IllegalAccessException {
    if (otherClass == CompactCompositeRegionKey.class) {
      return entryEqualsKey(key, value, vbytes, vbs, tableInfo,
          (CompactCompositeRegionKey)other);
    }
    else if (RegionKey.class.isAssignableFrom(otherClass)
        || RegionEntry.class.isAssignableFrom(otherClass)) {
      // can get compared against DVD in the TXRegionState map
      return false;
    }
    else {
      // Fix for 43915, we can end up comparing a region
      // entry with a WrappedRowLocation in the index code.
      if (!(otherClass == WrapperRowLocationForTxn.class)) {
        Assert.fail("unknown object compared in entryEquals: " + other
            + ", type=" + otherClass.getName());
      }
    }
    return false;
  }

  private static final boolean entryEqualsKeyBytes(final byte[] key,
      final Object value, final ExtraTableInfo tableInfo, final Object other,
      final Class<?> otherClass) throws IllegalAccessException {
    if (otherClass == CompactCompositeRegionKey.class) {
      return entryEqualsCCKey(key, value, tableInfo,
          (CompactCompositeRegionKey)other);
    }
    else if (RegionKey.class.isAssignableFrom(otherClass)
        || RegionEntry.class.isAssignableFrom(otherClass)) {
      // can get compared against DVD in the TXRegionState map
      return false;
    }
    else {
      // Fix for 43915, we can end up comparing a region
      // entry with a WrappedRowLocation in the index code.
      if (!(otherClass == WrapperRowLocationForTxn.class)) {
        Assert.fail("unknown object compared in entryEquals: " + other
            + ", type=" + otherClass.getName());
      }
    }
    return false;
  }

  private static final int computeIntHash(int intValue,
      final int numBytesToBeWritten, int hash) {
    // keep this compatible with RowFormatter.writeInt(byte[],int,int,int)
    for (int i = 0; i < numBytesToBeWritten; ++i, intValue >>>= 8) {
      hash = ResolverUtils.addByteToHash((byte) intValue, hash);
    }
    return hash;
  }

  public static final ColumnProcessor<byte[]> checkColumnEqualityWithRow =
      new ColumnProcessor<byte[]>() {

    private final int compareBytes(final byte[] row, int columnOffset,
        final int columnWidth, final byte[] columnBytes, int columnPos) {
      final int endColumnPos = columnPos + columnWidth;
      while (columnPos < endColumnPos) {
        if (columnBytes[columnPos] != row[columnOffset]) {
          // indicates failure in equality comparison
          return -1;
        }
        ++columnPos;
        ++columnOffset;
      }
      return 0;
    }

    /**
     * Compare two byte[], ignore the trailing blanks in the longer byte[]
     * 
     * @param row
     * @param columnOffset
     * @param columnWidth
     * @param targetColumnBytes
     * @param targetOffset
     * @param targetWidth
     * @return
     */
    private final int compareCharBytes(final byte[] row,
        final int columnOffset, final int columnWidth,
        final byte[] targetColumnBytes, final int targetOffset,
        final int targetWidth) {
      int shorter = columnWidth < targetWidth ? columnWidth : targetWidth;
      if (compareBytes(row, columnOffset, shorter, targetColumnBytes,
          targetOffset) == -1) {
        return -1;
      }

      // see if the rest of the longer byte array is space
      if (columnWidth < targetWidth) {
        int targetCurPos = targetOffset + shorter;
        while (targetCurPos < targetOffset + targetWidth) {
          if (targetColumnBytes[targetCurPos] != 0x20) {
            return -1;
          }
          targetCurPos++;
        }
      }
      else if (columnWidth > targetWidth) {
        int columnCurPos = columnOffset + shorter;
        while (columnCurPos < columnOffset + columnWidth) {
          if (row[columnCurPos] != 0x20) {
            return -1;
          }
          columnCurPos++;
        }
      }
      return 0;
    }

    @Override
    public final int handleNull(final byte[] columnBytes, int pos,
        final RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      // check for null value in targetFormat

      if (targetOffsetFromMap < 0) {
        final int offsetOffset = columnBytes.length + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(columnBytes,
            offsetOffset);
        if (offset < 0 && !targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      else if (targetOffsetFromMap == 0 && cd.columnDefault == null) {
        // treat both nulls as equal (see DataTypeUtilities.compare or
        // DataType.compare that use the same convention)
        return 0;
      }
      return -1;
    }

    @Override
    public final int handleDefault(final byte[] columnBytes, int pos,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {

      // check for default value in targetFormat
      if (targetOffsetFromMap < 0) {
        final int offsetOffset = columnBytes.length + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(columnBytes,
            offsetOffset);
        if (targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      return -1;
    }

    @Override
    public final int handleFixed(final byte[] row, final int columnOffset,
        final int columnWidth, final byte[] columnBytes, int pos,
        final RowFormatter formatter, int colIndex,
        final RowFormatter targetFormat, final int targetIndex,
        final int targetOffsetFromMap) {
      // check the widths first
      final int targetWidth = targetFormat.columns[targetIndex].fixedWidth;
      if (columnWidth == targetWidth) {
        return compareBytes(row, columnOffset, columnWidth, columnBytes,
            targetOffsetFromMap);
      }
      return -1;
    }

    @Override
    public final int handleVariable(final byte[] row, final int columnOffset,
        final int columnWidth, final byte[] columnBytes, int pos,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, final int targetIndex,
        final int targetOffsetFromMap) {
      // check the widths first
      final ColumnDescriptor targetCD = targetFormat.columns[targetIndex];
      final long offsetAndWidth = targetFormat.getOffsetAndWidth(targetIndex,
          columnBytes, targetOffsetFromMap, targetCD);
      if (offsetAndWidth >= 0) {
        final int targetWidth = (int)offsetAndWidth;
        if (columnWidth == targetWidth) {
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareBytes(row, columnOffset, columnWidth, columnBytes,
              targetOffset);
        }
        int typeId = cd.getType().getTypeId().getTypeFormatId();
        if ((typeId == StoredFormatIds.VARCHAR_TYPE_ID)
            || (typeId == StoredFormatIds.CHAR_TYPE_ID)
            || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
          // special handling for CHAR/VARCHAR/VARCHAR FOR BIT DATA
          // compareCharBytes() ignores the trailing blanks in the longer byte[]
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareCharBytes(row, columnOffset, columnWidth, columnBytes,
              targetOffset, targetWidth);
        }
      }
      return -1;
    }
  };

  public static final ColumnProcessorOffHeap<byte[]> checkColumnEqualityWithRowOffHeap =
      new ColumnProcessorOffHeap<byte[]>() {

    private final int compareBytes(final long memAddr, int columnOffset,
        final int columnWidth, final byte[] columnBytes, int columnPos) {
      final int endColumnPos = columnPos + columnWidth;
      long rowAddr = memAddr + columnOffset;
      while (columnPos < endColumnPos) {
        if (columnBytes[columnPos] != Platform.getByte(null, rowAddr)) {
          // indicates failure in equality comparison
          return -1;
        }
        ++columnPos;
        ++rowAddr;
      }
      return 0;
    }

    /**
     * Compare two byte[], ignore the trailing blanks in the longer byte[]
     */
    private final int compareCharBytes(final long memAddr,
        final int columnOffset, final int columnWidth,
        final byte[] targetColumnBytes, final int targetOffset,
        final int targetWidth) {
      int shorter = columnWidth < targetWidth ? columnWidth : targetWidth;
      if (compareBytes(memAddr, columnOffset, shorter,
          targetColumnBytes, targetOffset) == -1) {
        return -1;
      }

      // see if the rest of the longer byte array is space
      if (columnWidth < targetWidth) {
        int targetCurPos = targetOffset+shorter;
        while (targetCurPos < targetOffset + targetWidth) {
          if (targetColumnBytes[targetCurPos] != 0x20) {
            return -1;
          }
          targetCurPos++;
        }
      }
      else if (columnWidth > targetWidth) {
        long rowAddr = memAddr + columnOffset + shorter;
        long rowAddrEnd = rowAddr + (columnWidth - shorter);
        while (rowAddr < rowAddrEnd) {
          if (Platform.getByte(null, rowAddr) != 0x20) {
            return -1;
          }
          ++rowAddr;
        }
      }
      return 0;
    }

    @Override
    public final int handleNull(final byte[] columnBytes, int pos,
        final RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      // check for null value in targetFormat

      if (targetOffsetFromMap < 0) {
        final int offsetOffset = columnBytes.length + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(columnBytes,
            offsetOffset);
        if (offset < 0 && !targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      else if (targetOffsetFromMap == 0 && cd.columnDefault == null) {
        // treat both nulls as equal (see DataTypeUtilities.compare or
        // DataType.compare that use the same convention)
        return 0;
      }
      return -1;
    }

    @Override
    public final int handleDefault(final byte[] columnBytes, int pos,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {

      // check for default value in targetFormat
      if (targetOffsetFromMap < 0) {
        final int offsetOffset = columnBytes.length + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(columnBytes,
            offsetOffset);
        if (targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      return -1;
    }

    @Override
    public final int handleFixed(final long memAddr, final int columnOffset, final int columnWidth,
        final byte[] columnBytes, int pos, final RowFormatter formatter,
        int colIndex, final RowFormatter targetFormat, final int targetIndex,
        final int targetOffsetFromMap) {
      // check the widths first
      final int targetWidth = targetFormat.columns[targetIndex].fixedWidth;
      if (columnWidth == targetWidth) {
        return compareBytes(memAddr, columnOffset, columnWidth,
            columnBytes, targetOffsetFromMap);
      }
      return -1;
    }

    @Override
    public final int handleVariable(final long memAddr, final int columnOffset, final int columnWidth,
        final byte[] columnBytes, int pos, RowFormatter formatter,
        ColumnDescriptor cd, int colIndex, final RowFormatter targetFormat,
        final int targetIndex, final int targetOffsetFromMap) {
      // check the widths first
      final ColumnDescriptor targetCD = targetFormat.columns[targetIndex];
      final long offsetAndWidth = targetFormat.getOffsetAndWidth(targetIndex,
          columnBytes, targetOffsetFromMap, targetCD);
      if (offsetAndWidth >= 0) {
        final int targetWidth = (int)offsetAndWidth;
        if (columnWidth == targetWidth) {
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareBytes(memAddr, columnOffset, columnWidth,
              columnBytes, targetOffset);
        }
        int typeId = cd.getType().getTypeId().getTypeFormatId();
        if ((typeId == StoredFormatIds.VARCHAR_TYPE_ID)
            || (typeId == StoredFormatIds.CHAR_TYPE_ID)
            || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
          // special handling for CHAR/VARCHAR/VARCHAR FOR BIT DATA
          // compareCharBytes() ignores the trailing blanks in the longer byte[]
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareCharBytes(memAddr, columnOffset, columnWidth,
              columnBytes, targetOffset, targetWidth);
        }
      }
      return -1;
    }
  };

  public static final ColumnProcessor<OffHeapByteSource> checkOffHeapColumnEqualityWithRow =
      new ColumnProcessor<OffHeapByteSource>() {

    private final int compareBytes(final byte[] typedRow, int columnOffset,
        final int columnWidth, final UnsafeWrapper unsafe, long columnAddr) {
      if (columnWidth == 0) {
        return 0;
      }
      final long columnEndPos = columnAddr + columnWidth;
      while (columnAddr < columnEndPos) {
        if (unsafe.getByte(columnAddr) != typedRow[columnOffset]) {
          return -1;
        }
        columnAddr++;
        columnOffset++;
      }
      return 0;
    }

    /**
     * Compare two byte[], ignore the trailing blanks in the longer byte[]
     */
    private final int compareCharBytes(final byte[] row,
        final int columnOffset, final int columnWidth,
        final UnsafeWrapper unsafe, long targetColumnAddr,
        final int targetWidth) {
      final int shorter = columnWidth < targetWidth ? columnWidth : targetWidth;
      if (compareBytes(row, columnOffset, shorter, unsafe,
          targetColumnAddr) == -1) {
        return -1;
      }
      // see if the rest of the longer byte array is space
      if (columnWidth < targetWidth) {
        final long targetColumnEndPos = targetColumnAddr + targetWidth;
        targetColumnAddr += shorter;
        while (targetColumnAddr < targetColumnEndPos) {
          if (unsafe.getByte(targetColumnAddr) != 0x20) {
            return -1;
          }
          targetColumnAddr++;
        }
      }
      else if (columnWidth > targetWidth) {
        int columnCurPos = columnOffset + shorter;
        final int columnEndPos = columnOffset + columnWidth;
        while (columnCurPos < columnEndPos) {
          if (row[columnCurPos] != 0x20) {
            return -1;
          }
          columnCurPos++;
        }
      }
      return 0;
    }

    @Override
    public final int handleNull(final OffHeapByteSource columnBytes, int pos,
        final RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      // check for null value in targetFormat

      if (targetOffsetFromMap < 0) {
        final int bytesLen = columnBytes.getLength();
        final long memAddr = columnBytes.getUnsafeAddress(0, bytesLen);
        final int offsetOffset = bytesLen + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, offsetOffset);
        if (offset < 0 && !targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      else if (targetOffsetFromMap == 0 && cd.columnDefault == null) {
        // treat both nulls as equal (see DataTypeUtilities.compare or
        // DataType.compare that use the same convention)
        return 0;
      }
      return -1;
    }

    @Override
    public final int handleDefault(final OffHeapByteSource columnBytes, int pos,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {

      // check for default value in targetFormat
      if (targetOffsetFromMap < 0) {
        final int bytesLen = columnBytes.getLength();
        final long memAddr = columnBytes.getUnsafeAddress(0, bytesLen);
        final int offsetOffset = bytesLen + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, offsetOffset);
        if (targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      return -1;
    }

    @Override
    public final int handleFixed(final byte[] row, final int columnOffset,
        final int columnWidth, final OffHeapByteSource columnBytes, int pos,
        final RowFormatter formatter, int colIndex,
        final RowFormatter targetFormat, final int targetIndex,
        final int targetOffsetFromMap) {
      // check the widths first
      final int targetWidth = targetFormat.columns[targetIndex].fixedWidth;
      if (columnWidth == targetWidth) {
        final long columnAddr = columnBytes.getUnsafeAddress(
            targetOffsetFromMap, columnWidth);
        return compareBytes(row, columnOffset, columnWidth,
            UnsafeMemoryChunk.getUnsafeWrapper(), columnAddr);
      }
      return -1;
    }

    @Override
    public final int handleVariable(final byte[] row, final int columnOffset,
        final int columnWidth, final OffHeapByteSource columnBytes, int pos,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, final int targetIndex,
        final int targetOffsetFromMap) {
      // check the widths first
      final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
      final int bytesLen = columnBytes.getLength();
      final long targetMemAddr = columnBytes.getUnsafeAddress(0, bytesLen);
      final ColumnDescriptor targetCD = targetFormat.columns[targetIndex];
      final long offsetAndWidth = targetFormat.getOffsetAndWidth(targetIndex,
          unsafe, targetMemAddr, bytesLen, targetOffsetFromMap, targetCD);
      if (offsetAndWidth >= 0) {
        final int targetWidth = (int)offsetAndWidth;
        if (columnWidth == targetWidth) {
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareBytes(row, columnOffset, columnWidth, unsafe,
              targetMemAddr + targetOffset);
        }
        int typeId = cd.getType().getTypeId().getTypeFormatId();
        if ((typeId == StoredFormatIds.VARCHAR_TYPE_ID)
            || (typeId == StoredFormatIds.CHAR_TYPE_ID)
            || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
          // special handling for CHAR/VARCHAR/VARCHAR FOR BIT DATA
          // compareCharBytes() ignores the trailing blanks in the longer byte[]
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareCharBytes(row, columnOffset, columnWidth, unsafe,
              targetMemAddr + targetOffset, targetWidth);
        }
      }
      return -1;
    }
  };

  public static final ColumnProcessorOffHeap<OffHeapByteSource>
      checkOffHeapColumnEqualityWithRowOffHeap =
      new ColumnProcessorOffHeap<OffHeapByteSource>() {

    private final int compareBytes(final long memAddr, int columnOffset,
        final int columnWidth, long columnAddr) {
      if (columnWidth == 0) {
        return 0;
      }
      long rowAddr = memAddr + columnOffset;
      final long rowEndPos = rowAddr + columnWidth;
      while (rowAddr < rowEndPos) {
        if (Platform.getByte(null, rowAddr) != Platform.getByte(null, columnAddr)) {
          return -1;
        }
        rowAddr++;
        columnAddr++;
      }
      return 0;
    }

    /**
     * Compare two byte[], ignore the trailing blanks in the longer byte[]
     */
    private final int compareCharBytes(final long memAddr,
        final int columnOffset, final int columnWidth,
        long targetColumnAddr, final int targetWidth) {
      final int shorter = columnWidth < targetWidth ? columnWidth : targetWidth;
      if (compareBytes(memAddr, columnOffset, shorter, targetColumnAddr) == -1) {
        return -1;
      }
      // see if the rest of the longer byte array is space
      if (columnWidth < targetWidth) {
        final long targetEndPos = targetColumnAddr + targetWidth;
        targetColumnAddr += shorter;
        while (targetColumnAddr < targetEndPos) {
          if (Platform.getByte(null, targetColumnAddr) != 0x20) {
            return -1;
          }
          targetColumnAddr++;
        }
      } else if (columnWidth > targetWidth) {
          long rowColumnAddr = memAddr + columnOffset + shorter;
          final long rowColumnEnd = rowColumnAddr + columnWidth - shorter;
          while (rowColumnAddr < rowColumnEnd) {
            if (Platform.getByte(null, rowColumnAddr) != 0x20) {
              return -1;
            }
            rowColumnAddr++;
          }
      }
      return 0;
    }

    @Override
    public final int handleNull(final OffHeapByteSource columnBytes, int pos,
        final RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {
      // check for null value in targetFormat

      if (targetOffsetFromMap < 0) {
        final int bytesLen = columnBytes.getLength();
        final long memAddr = columnBytes.getUnsafeAddress(0, bytesLen);
        final int offsetOffset = bytesLen + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, offsetOffset);
        if (offset < 0 && !targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      else if (targetOffsetFromMap == 0 && cd.columnDefault == null) {
        // treat both nulls as equal (see DataTypeUtilities.compare or
        // DataType.compare that use the same convention)
        return 0;
      }
      return -1;
    }
 
    @Override
    public final int handleDefault(final OffHeapByteSource columnBytes, int pos,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, int targetIndex,
        final int targetOffsetFromMap) {

      // check for default value in targetFormat
      if (targetOffsetFromMap < 0) {
        final int bytesLen = columnBytes.getLength();
        final long memAddr = columnBytes.getUnsafeAddress(0, bytesLen);
        final int offsetOffset = bytesLen + targetOffsetFromMap;
        final int offset = targetFormat.readVarDataOffset(
            UnsafeMemoryChunk.getUnsafeWrapper(), memAddr, offsetOffset);
        if (targetFormat.isVarDataOffsetDefaultToken(offset)) {
          return 0;
        }
      }
      return -1;
    }

    @Override
    public final int handleFixed(final long memAddr, final int columnOffset, final int columnWidth,
        final OffHeapByteSource columnBytes, int pos,
        final RowFormatter formatter, int colIndex,
        final RowFormatter targetFormat, final int targetIndex,
        final int targetOffsetFromMap) {
      // check the widths first
      final int targetWidth = targetFormat.columns[targetIndex].fixedWidth;
      if (columnWidth == targetWidth) {
        final long columnAddr = columnBytes.getUnsafeAddress(
            targetOffsetFromMap, columnWidth);
        return compareBytes(memAddr, columnOffset, columnWidth, columnAddr);
      }
      return -1;
    }

    @Override
    public final int handleVariable(final long memAddr, final int columnOffset,
        final int columnWidth, final OffHeapByteSource columnBytes, int pos,
        RowFormatter formatter, ColumnDescriptor cd, int colIndex,
        final RowFormatter targetFormat, final int targetIndex,
        final int targetOffsetFromMap) {
      // check the widths first
      final int bytesLen = columnBytes.getLength();
      final long targetMemAddr = columnBytes.getUnsafeAddress(0, bytesLen);
      final ColumnDescriptor targetCD = targetFormat.columns[targetIndex];
      final long offsetAndWidth = targetFormat.getOffsetAndWidth(targetIndex,
          UnsafeMemoryChunk.getUnsafeWrapper(), targetMemAddr, bytesLen,
          targetOffsetFromMap, targetCD);
      if (offsetAndWidth >= 0) {
        final int targetWidth = (int)offsetAndWidth;
        if (columnWidth == targetWidth) {
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareBytes(memAddr, columnOffset, columnWidth,
              targetMemAddr + targetOffset);
        }
        int typeId = cd.getType().getTypeId().getTypeFormatId();
        if ((typeId == StoredFormatIds.VARCHAR_TYPE_ID)
            || (typeId == StoredFormatIds.CHAR_TYPE_ID)
            || (typeId == StoredFormatIds.BIT_TYPE_ID)) {
          // special handling for CHAR/VARCHAR/VARCHAR FOR BIT DATA
          // compareCharBytes() ignores the trailing blanks in the longer byte[]
          final int targetOffset = (int)(offsetAndWidth >>> Integer.SIZE);
          return compareCharBytes(memAddr, columnOffset, columnWidth,
              targetMemAddr + targetOffset, targetWidth);
        }
      }
      return -1;
    }
  };

  static final boolean compareRowBytesToKeyBytes(final byte[] rowBytes,
      final RowFormatter rowFormat, final byte[] keyBytes,
      final RowFormatter keyFormat, final int[] keyPositions) {
    for (int index = 0; index < keyPositions.length; index++) {
      if (rowFormat.processColumn(rowBytes, keyPositions[index] - 1,
          checkColumnEqualityWithRow, keyBytes, -1, keyFormat, index) < 0) {
        return false;
      }
    }
    return true;
  }

  static final boolean compareRowBytesToKeyBytes(
      final OffHeapByteSource bs, final RowFormatter rowFormat,
      final byte[] keyBytes, final RowFormatter keyFormat,
      final int[] keyPositions) {
    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = bs.getLength();
    final long memAddr = bs.getUnsafeAddress(0, bytesLen);
    for (int index = 0; index < keyPositions.length; index++) {
      if (rowFormat.processColumn(unsafe, memAddr, bytesLen,
          keyPositions[index] - 1, checkColumnEqualityWithRowOffHeap, keyBytes,
          -1, keyFormat, index) < 0) {
        return false;
      }
    }
    return true;
  }

  static final boolean compareValueBytesToValueBytes(final RowFormatter rf,
      final int[] keyPositions, final byte[] vbytes, final byte[] otherVbytes,
      final RowFormatter otherFormatter) {

    int keyIndex;
    for (int index = 0; index < keyPositions.length; index++) {
      keyIndex = keyPositions[index] - 1;
      if (rf.processColumn(vbytes, keyIndex, checkColumnEqualityWithRow,
          otherVbytes, -1, otherFormatter, keyIndex) < 0) {
        return false;
      }
    }
    return true;
  }

  static final boolean compareValueBytesToValueBytes(final RowFormatter rf,
      final int[] keyPositions, @Unretained final OffHeapByteSource vbytes,
      final byte[] otherVbytes, final RowFormatter otherFormatter) {

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = vbytes.getLength();
    final long memAddr = vbytes.getUnsafeAddress(0, bytesLen);

    int keyIndex;
    for (int index = 0; index < keyPositions.length; index++) {
      keyIndex = keyPositions[index] - 1;
      if (rf.processColumn(unsafe, memAddr, bytesLen, keyIndex,
          checkColumnEqualityWithRowOffHeap, otherVbytes, -1, otherFormatter,
          keyIndex) < 0) {
        return false;
      }
    }
    return true;
  }

  static final boolean compareValueBytesToValueBytes(final RowFormatter rf,
      final int[] keyPositions, final byte[] vbytes,
      @Unretained final OffHeapByteSource otherVbytes,
      final RowFormatter otherFormatter) {

    int keyIndex;
    for (int index = 0; index < keyPositions.length; index++) {
      keyIndex = keyPositions[index] - 1;
      if (rf.processColumn(vbytes, keyIndex, checkOffHeapColumnEqualityWithRow,
          otherVbytes, -1, otherFormatter, keyIndex) < 0) {
        return false;
      }
    }
    return true;
  }

  static final boolean compareValueBytesToValueBytes(final RowFormatter rf,
      final int[] keyPositions, @Unretained final OffHeapByteSource vbytes,
      @Unretained final OffHeapByteSource otherVbytes,
      final RowFormatter otherFormatter) {

    final UnsafeWrapper unsafe = UnsafeMemoryChunk.getUnsafeWrapper();
    final int bytesLen = vbytes.getLength();
    final long memAddr = vbytes.getUnsafeAddress(0, bytesLen);

    int keyIndex;
    for (int index = 0; index < keyPositions.length; index++) {
      keyIndex = keyPositions[index] - 1;
      if (rf.processColumn(unsafe, memAddr, bytesLen, keyIndex,
          checkOffHeapColumnEqualityWithRowOffHeap, otherVbytes, -1,
          otherFormatter, keyIndex) < 0) {
        return false;
      }
    }
    return true;
  }

  // End methods related to key manipulation from the serialized row as value
}
