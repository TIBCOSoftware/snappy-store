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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.SerializationException;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.distributed.OplogCancelledException;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.HeapDataOutputStream;
import com.gemstone.gemfire.internal.InsufficientDiskSpaceException;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.InternalStatisticsDisabledException;
import com.gemstone.gemfire.internal.Sendable;
import com.gemstone.gemfire.internal.cache.DiskInitFile.DiskRegionFlag;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl.OplogCompactor;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl.OplogEntryIdSet;
import com.gemstone.gemfire.internal.cache.DistributedRegion.DiskPosition;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl.StaticSystemCallbacks;
import com.gemstone.gemfire.internal.cache.OplogIndex.IndexData;
import com.gemstone.gemfire.internal.cache.delta.Delta;
import com.gemstone.gemfire.internal.cache.locks.LockMode;
import com.gemstone.gemfire.internal.cache.locks.LockingPolicy;
import com.gemstone.gemfire.internal.cache.lru.EnableLRU;
import com.gemstone.gemfire.internal.cache.persistence.BytesAndBits;
import com.gemstone.gemfire.internal.cache.persistence.DiskRecoveryStore;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.cache.persistence.DiskStoreID;
import com.gemstone.gemfire.internal.cache.versions.CompactVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionHolder;
import com.gemstone.gemfire.internal.cache.versions.RegionVersionVector;
import com.gemstone.gemfire.internal.cache.versions.VersionHolder;
import com.gemstone.gemfire.internal.cache.versions.VersionSource;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.offheap.OffHeapHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.StoredObject;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.gemstone.gemfire.internal.sequencelog.EntryLogger;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import io.snappydata.collection.OpenHashSet;
import com.gemstone.gemfire.internal.shared.UnsupportedGFXDVersionException;
import com.gemstone.gemfire.internal.shared.Version;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataInputStream;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataOutputStream;
import com.gemstone.gemfire.internal.snappy.CallbackFactoryProvider;
import com.gemstone.gemfire.internal.util.IOUtils;
import com.gemstone.gemfire.internal.util.TransformUtils;
import com.gemstone.gemfire.pdx.internal.PdxWriterImpl;
import com.gemstone.gnu.trove.TLongHashSet;
import io.snappydata.collection.ObjectObjectHashMap;

/**
 * Implements an operation log to write to disk.
 * As of prPersistSprint2 this file only supports persistent regions.
 * For overflow only regions see {@link OverflowOplog}.
 * 
 * @author Darrel Schneider
 * @author Mitul Bid
 * @author Asif
 * 
 * @since 5.1
 */

public final class Oplog implements CompactableOplog {

  /** Extension of the oplog file * */
  public static final String CRF_FILE_EXT = ".crf";
  public static final String DRF_FILE_EXT = ".drf";
  public static final String KRF_FILE_EXT = ".krf";
  public static final Pattern IDX_PATTERN = Pattern.compile(".*\\.([0-9]+)\\.idxkrf");
  
  /** The file which will be created on disk * */
  private File diskFile;

  /** boolean marked true when this oplog is closed * */
  private volatile boolean closed;

  private final OplogFile crf = new OplogFile();
  private final OplogFile drf = new OplogFile();
  private final KRFile krf = new KRFile();
  private final OplogIndex idxkrf;
  final ConcurrentTHashSet<SortedIndexContainer> indexesWritten =
      new ConcurrentTHashSet<SortedIndexContainer>(2);

  /** preallocated space available for writing to* */
  // volatile private long opLogSpace = 0L;
  /** The stats for this store */
  private final DiskStoreStats stats;

  /** The store that owns this Oplog* */
  private final DiskStoreImpl parent;
  
  /**
   * The oplog set this oplog is part of 
   */
  private final PersistentOplogSet oplogSet;

  /** oplog id * */
  protected final long oplogId;
  
  /** recovered gemfire version * */
  protected Version gfversion;

  /**
   * Recovered version of the data. Usually this is same as {@link #gfversion}
   * except for the case of upgrading disk store from previous version in which
   * case the keys/values are carried forward as is and need to be interpreted
   * in load by latest product code if required.
   */
  protected Version dataVersion;

  /** Directory in which the file is present* */
  private DirectoryHolder dirHolder;

  /** The max Oplog size (user configurable) * */
  private final long maxOplogSize;
  private long maxCrfSize;
  private long maxDrfSize;

  private final AtomicBoolean hasDeletes = new AtomicBoolean();

  private boolean firstRecord = true;

  /**
   * The HighWaterMark of recentValues.
   */
  private final AtomicLong totalCount = new AtomicLong(0);
  /**
   * The number of records in this oplog that contain the most recent
   * value of the entry.
   */
  private final AtomicLong totalLiveCount = new AtomicLong(0);

  private final ConcurrentMap<Long, DiskRegionInfo> regionMap
    = new ConcurrentHashMap<Long, DiskRegionInfo>();
  
  /**
   * Set to true once compact is called on this oplog.
   * @since prPersistSprint1
   */
  private volatile boolean compacting = false;

  /**
   * Set to true after the first drf recovery.
   */
  private boolean haveRecoveredDrf = true;
  /**
   * Set to true after the first crf recovery.
   */
  private boolean haveRecoveredCrf = true;
  private OpState opState;
  
  /** OPCODES - byte appended before being written to disk* */

  /**
   * Written to CRF, and DRF.
   */
  private static final byte OPLOG_EOF_ID = 0;
  private static final byte END_OF_RECORD_ID = 21;

  /**
   * Written to CRF and DRF.
   * Followed by 16 bytes which is the leastSigBits and mostSigBits of a UUID
   * for the disk store we belong to.
   * 1: EndOfRecord
   * Is written once at the beginning of every oplog file.
   */
  private static final byte OPLOG_DISK_STORE_ID = 62;
  static final int OPLOG_DISK_STORE_REC_SIZE = 1+16+1;

  /**
   * Written to CRF.
   * Followed by 8 bytes which is the BASE_ID to use for any NEW_ENTRY records.
   * 1: EndOfRecord
   * Only needs to be written once per oplog and must preceed any OPLOG_NEW_ENTRY_0ID records.
   * @since prPersistSprint1
   */
  private static final byte OPLOG_NEW_ENTRY_BASE_ID = 63;
  static final int OPLOG_NEW_ENTRY_BASE_REC_SIZE = 1+8+1;
  /**
   * Written to CRF.
   * The OplogEntryId is +1 the previous new_entry OplogEntryId.
   * Byte Format:
   * 1: userBits
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_NEW_ENTRY_0ID = 64;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 1 byte.
   * Byte Format:
   * 1: userBits
   * 1: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_1ID = 65;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 2 bytes.
   * Byte Format:
   * 1: userBits
   * 2: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_2ID = 66;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 3 bytes.
   * Byte Format:
   * 1: userBits
   * 3: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_3ID = 67;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 4 bytes.
   * Byte Format:
   * 1: userBits
   * 4: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_4ID = 68;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 5 bytes.
   * Byte Format:
   * 1: userBits
   * 5: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_5ID = 69;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 6 bytes.
   * Byte Format:
   * 1: userBits
   * 6: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_6ID = 70;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 7 bytes.
   * Byte Format:
   * 1: userBits
   * 7: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_7ID = 71;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 8 bytes.
   * Byte Format:
   * 1: userBits
   * 8: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_8ID = 72;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 1 byte.
   * Byte Format:
   * 1: userBits
   * 1: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_1ID = 73;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 2 bytes.
   * Byte Format:
   * 1: userBits
   * 2: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_2ID = 74;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 3 bytes.
   * Byte Format:
   * 1: userBits
   * 3: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_3ID = 75;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 4 bytes.
   * Byte Format:
   * 1: userBits
   * 4: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_4ID = 76;

  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 5 bytes.
   * Byte Format:
   * 1: userBits
   * 5: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_5ID = 77;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 6 bytes.
   * Byte Format:
   * 1: userBits
   * 6: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_6ID = 78;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 7 bytes.
   * Byte Format:
   * 1: userBits
   * 7: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_7ID = 79;
  /**
   * Written to CRF.
   * The OplogEntryId is relative to the previous mod_entry OplogEntryId.
   * The signed difference is encoded in 8 bytes.
   * Byte Format:
   * 1: userBits
   * 8: OplogEntryId
   * RegionId
   * 4: valueLength (optional depending on bits)
   * valueLength: value bytes (optional depending on bits)
   * 4: keyLength
   * keyLength: key bytes
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_MOD_ENTRY_WITH_KEY_8ID = 80;

  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 1 byte.
   * Byte Format:
   * 1: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_1ID = 81;
  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 2 bytes.
   * Byte Format:
   * 2: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_2ID = 82;

  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 3 bytes.
   * Byte Format:
   * 3: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_3ID = 83;

  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 4 bytes.
   * Byte Format:
   * 4: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_4ID = 84;

  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 5 bytes.
   * Byte Format:
   * 5: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_5ID = 85;
  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 6 bytes.
   * Byte Format:
   * 6: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_6ID = 86;
  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 7 bytes.
   * Byte Format:
   * 7: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_7ID = 87;
  /**
   * Written to DRF.
   * The OplogEntryId is relative to the previous del_entry OplogEntryId.
   * The signed difference is encoded in 8 bytes.
   * Byte Format:
   * 8: OplogEntryId
   * 1: EndOfRecord
   *
   * @since prPersistSprint1
   */
  private static final byte OPLOG_DEL_ENTRY_8ID = 88;

  /**
   * The maximum size of a DEL_ENTRY record in bytes.
   * Currenty this is 10; 1 for opcode and 8 for oplogEntryId and 1 for END_OF_RECORD_ID
   */
  private static final int MAX_DELETE_ENTRY_RECORD_BYTES = 1+8+1;
  
  /**
   * Written to beginning of each CRF. Contains the RVV for 
   * all regions in the CRF.
   * Byte Format
   * 8: number of regions (variable length encoded number)
   * for each region
   *   4: number of members (variable length encoded number)
   *   for each member
   *     4: canonical member id (variable length encoded number)
   *     8: version id (variable length encoded number)
   *     4: number of exceptions (variable length encoded number)
   *     variable: exceptions
   */
  private static final byte OPLOG_RVV = 89;

  /**
   * When detected conflict, besides persisting the golden copy by modify(),
   * also persist the conflict operation's region version and member id. and failedWritten to beginning of each CRF. Contains the RVV for 
   * all regions in the CRF.
   * Byte Format
   * regionId
   * versions
   */
  private static final byte OPLOG_CONFLICT_VERSION = 90;

  /**
   * persist Gemfire version string into crf, drf, krf
   * Byte Format
   * variable gemfire version string, such as 7.0.0.beta
   * EndOfRecord
   */
  private static final byte OPLOG_GEMFIRE_VERSION = 91;
  static final int OPLOG_GEMFIRE_VERSION_REC_SIZE = 1+3+1;

  /** Compact this oplogs or no. A client configurable property * */
  private final boolean compactOplogs;

  /**
   * Asif: This object is used to correctly identify the OpLog size so as to
   * cause a switch of oplogs
   */
  final Object lock = new Object();

  private boolean lockedForKRFcreate = false;
  
  /**
   * Set to true when this oplog will no longer be written to.
   * Never set to false once it becomes true.
   */
  private boolean doneAppending = false;

  protected final LogWriterI18n logger;

  static final int DEFAULT_BUFFER_SIZE = 32 * 1024;
  static final int LARGE_BUFFER_SIZE = 128 * 1024;

  // ///////////////////// Constructors ////////////////////////
  /**
   * Creates new <code>Oplog</code> for the given region.
   * 
   * @param oplogId
   *          int identifying the new oplog
   * @param dirHolder
   *          The directory in which to create new Oplog
   * 
   * @throws DiskAccessException
   *           if the disk files can not be initialized
   */
  Oplog(long oplogId, PersistentOplogSet parent, DirectoryHolder dirHolder) {
    if (oplogId > DiskId.MAX_OPLOG_ID) {
      throw new IllegalStateException("Too many oplogs. The oplog id can not exceed " + DiskId.MAX_OPLOG_ID);
    }
    this.oplogId = oplogId;
    this.oplogSet = parent;
    this.parent = parent.getParent();
    this.dirHolder = dirHolder;
    // Pretend we have already seen the first record.
    // This will cause a large initial record to force a switch
    // which allows the maxDirSize to be checked.
    this.firstRecord = false; 
    this.logger = getParent().getCache().getLoggerI18n();
    this.opState = new OpState();
    long maxOplogSizeParam = getParent().getMaxOplogSizeInBytes();
    long availableSpace = this.dirHolder.getAvailableSpace();
    if (availableSpace < maxOplogSizeParam) {
      if (DiskStoreImpl.PREALLOCATE_OPLOGS && !DiskStoreImpl.SET_IGNORE_PREALLOCATE) {
        throw new InsufficientDiskSpaceException(
            LocalizedStrings.Oplog_PreAllocate_Failure_Init.toLocalizedString(
                this.dirHolder, maxOplogSizeParam), new IOException(
                "not enough space left to create and pre grow oplog files, available="
                    + availableSpace + ", required=" + maxOplogSizeParam),
            getParent());
      }
      this.maxOplogSize = availableSpace;
      if (this.logger.warningEnabled()) {
        logger.warning(LocalizedStrings.DEBUG, "Reducing maxOplogSize to " + availableSpace + " because that is all the room remaining in the directory.");
      }
    } else {
      long diff = availableSpace - maxOplogSizeParam;
      long minRequired = DiskStoreImpl.MIN_DISK_SPACE_FOR_LOGS * 1024 * 1024;
      if (minRequired > diff) {
        if (DiskStoreImpl.PREALLOCATE_OPLOGS && !DiskStoreImpl.SET_IGNORE_PREALLOCATE) {
          throw new InsufficientDiskSpaceException(
              LocalizedStrings.Oplog_PreAllocate_Failure_Init.toLocalizedString(
                  this.dirHolder, maxOplogSizeParam), new IOException(
                  "not enough space left to create and pre grow oplog files, available="
                      + availableSpace + ", required=" + maxOplogSizeParam),
              getParent());
        }
      }
      this.maxOplogSize = maxOplogSizeParam;
    }
    setMaxCrfDrfSize();
    this.stats = getParent().getStats();
    this.compactOplogs = getParent().getAutoCompact();

    this.closed = false;
    String n = getParent().getName();
    this.diskFile = new File(this.dirHolder.getDir(),
                             oplogSet.getPrefix()
                             + n + "_" + oplogId);
    this.idxkrf = new OplogIndex(this);
    try {
      createDrf(null);
      createCrf(null);
      // open krf for offline compaction
      if (getParent().isOfflineCompacting()) {
        krfFileCreate();
      }
    }
    catch (Exception ex) {
      close();
      
      getParent().getCancelCriterion().checkCancelInProgress(ex);
      if (ex instanceof DiskAccessException) {
        throw (DiskAccessException) ex;
      }
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_CREATING_OPERATION_LOG_BECAUSE_0.toLocalizedString(ex), getParent());
    }
  }

  /**
   * Asif: A copy constructor used for creating a new oplog based on the
   * previous Oplog. This constructor is invoked only from the function
   * switchOplog
   * 
   * @param oplogId
   *          integer identifying the new oplog
   * @param dirHolder
   *          The directory in which to create new Oplog
   * @param prevOplog
   *          The previous oplog
   */
  private Oplog(long oplogId, DirectoryHolder dirHolder, Oplog prevOplog) {
    if (oplogId > DiskId.MAX_OPLOG_ID) {
      throw new IllegalStateException("Too many oplogs. The oplog id can not exceed " + DiskId.MAX_OPLOG_ID);
    }
    this.oplogId = oplogId;
    this.parent = prevOplog.parent;
    this.oplogSet = prevOplog.oplogSet;
    this.dirHolder = dirHolder;
    this.opState = new OpState();
    this.logger = prevOplog.logger;
    long maxOplogSizeParam = getParent().getMaxOplogSizeInBytes();
    long availableSpace = this.dirHolder.getAvailableSpace();
    if (prevOplog.compactOplogs) {
      this.maxOplogSize = maxOplogSizeParam;
    } else {
      if (availableSpace < maxOplogSizeParam) {
        this.maxOplogSize = availableSpace;
        if (this.logger.warningEnabled()) {
          logger.warning(LocalizedStrings.DEBUG, "Reducing maxOplogSize to " + availableSpace + " because that is all the room remaining in the directory.");
        }
      } else {
        this.maxOplogSize = maxOplogSizeParam;
      }
    }
    setMaxCrfDrfSize();
    this.stats = prevOplog.stats;
    this.compactOplogs = prevOplog.compactOplogs;
    // copy over the previous Oplog's data version since data is not being
    // transformed at this point
    this.dataVersion = prevOplog.getDataVersionIfOld();

    this.closed = false;
    String n = getParent().getName();
    this.diskFile = new File(this.dirHolder.getDir(),
                             oplogSet.getPrefix()
                             + n + "_" + oplogId);
    this.idxkrf = new OplogIndex(this);
    try {
      createDrf(prevOplog);
      createCrf(prevOplog);
      // open krf for offline compaction
      if (getParent().isOfflineCompacting()) {
        krfFileCreate();
      }
    }
    catch (Exception ex) {
      close();
      
      getParent().getCancelCriterion().checkCancelInProgress(ex);
      if (ex instanceof DiskAccessException) {
        throw (DiskAccessException) ex;
      }
      logger.warning(LocalizedStrings.Oplog_FAILED_CREATING_OPERATION_LOG_BECAUSE_0, ex);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_CREATING_OPERATION_LOG_BECAUSE_0.toLocalizedString(ex), getParent());
    }
  }
  
  public void replaceIncompatibleEntry(DiskRegionView dr, DiskEntry old, DiskEntry repl) {
    boolean useNextOplog = false;
    synchronized (this.lock) {
      if (getOplogSet().getChild() != this) {
        // make sure to only call replaceIncompatibleEntry for child, because this.lock
        // can only sync with compaction thread on child oplog
        useNextOplog = true;
      } else {
        // This method is use in recovery only and will not be called by compaction.
        // It's only called before or after compaction. It will replace DiskEntry
        // in DiskRegion without modifying DiskId (such as to a new oplogId),
        // Not to change the entry count in oplog either. While doing that,
        // this.lock will lock the current child to sync with compaction thread.
        // If replace thread got this.lock, DiskEntry "old" will not be removed from
        // current oplog (maybe not child). If compaction thread got this.lock,
        // DiskEntry "old" should have been moved to child oplog when replace thread
        // processes it.

        // See #48032.  A new region entry has been put into the region map, but we
        // also have to replace it in the oplog live entries that are used to write
        // the krf.  If we don't, we will recover the wrong (old) value.
        getOrCreateDRI(dr).replaceLive(old, repl);
        if (DiskStoreImpl.TRACE_RECOVERY) {
          logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY replacing incompatible entry" 
              + " key = " + old.getKey()
              + " old = " + System.identityHashCode(old)
              + " new = " + System.identityHashCode(repl)
              + " old diskId = " + old.getDiskId()
              + " new diskId = " + repl.getDiskId()
              + " tag = " + old.getVersionStamp()
              + " in child oplog #"+this.getOplogId());
        }
      }
    }
    if (useNextOplog) {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterSwitchingOplog();
      }
      Assert.assertTrue(getOplogSet().getChild() != this);
      getOplogSet().getChild().replaceIncompatibleEntry(dr, old, repl);
    }
  }

  public Collection<DiskRegionInfo> getRegionRecoveryMap() {
    return Collections.unmodifiableCollection(this.regionMap.values());
  }

  private void writeDiskStoreRecord(OplogFile olf) throws IOException {
    this.opState = new OpState();
    this.opState.initialize(getParent().getDiskStoreID());
    writeOpLogBytes(olf, false, true); // fix for bug 41928
    olf.currSize += getOpStateSize();
    this.dirHolder.incrementTotalOplogSize(getOpStateSize());
  }

  private void writeGemfireVersionRecord(OplogFile olf) throws IOException {
    if (this.gfversion == null) {
      this.gfversion = Version.CURRENT;
    }
    Version dataVersion = getDataVersionIfOld();
    if (dataVersion == null) {
      dataVersion = Version.CURRENT;
    }
    // if gfversion and dataVersion are not same, then write a special token
    // version and then write both, else write gfversion as before
    // this is for backward compatibility with 7.0
    this.opState = new OpState();
    if (this.gfversion == dataVersion) {
      writeProductVersionRecord(this.gfversion, olf);
    }
    else {
      writeProductVersionRecord(Version.TOKEN, olf);
      clearOpState();
      writeProductVersionRecord(this.gfversion, olf);
      clearOpState();
      writeProductVersionRecord(dataVersion, olf);
    }
  }

  private void writeProductVersionRecord(Version version, OplogFile olf)
      throws IOException {
    this.opState.initialize(version.ordinal());
    writeOpLogBytes(olf, false, true);
    olf.currSize += getOpStateSize();
    this.dirHolder.incrementTotalOplogSize(getOpStateSize());
  }

  public final Version currentRecoveredGFVersion() {
    return this.gfversion;
  }
  
  /**
   * Write an RVV record containing all of the live disk regions.
   */
  private void writeRVVRecord(OplogFile olf, boolean writeGCRVV) throws IOException {
    writeRVVRecord(olf, getParent().getAllDiskRegions(), writeGCRVV);
  }
  
  /**
   * Write the RVV record for the given regions.
   * @param olf the oplog to write to 
   * @param diskRegions the set of disk regions we should write the RVV of
   * @param writeGCRVV true to write write the GC RVV
   * @throws IOException
   */
  private void writeRVVRecord(OplogFile olf,
      Map<Long, AbstractDiskRegion> diskRegions, boolean writeGCRVV)
      throws IOException {
    this.opState = new OpState();
    this.opState.initialize(diskRegions, writeGCRVV);
    writeOpLogBytes(olf, false, true); // fix for bug 41928
    olf.currSize += getOpStateSize();
    this.dirHolder.incrementTotalOplogSize(getOpStateSize());
  }
  
  private boolean wroteNewEntryBase = false;
  /**
   * Write a OPLOG_NEW_ENTRY_BASE_ID to this oplog.
   * Must be called before any OPLOG_NEW_ENTRY_0ID records are written
   * to this oplog.
   */
  private boolean writeNewEntryBaseRecord(boolean async) throws IOException {
    if (this.wroteNewEntryBase) return false;
    this.wroteNewEntryBase = true;
    long newEntryBase = getOplogSet().getOplogEntryId();
//     logger.info(LocalizedStrings.DEBUG, "DEBUG newEntryBase=" + newEntryBase + " oplog#" + getOplogId());

    OpState saved = this.opState;
    try {
      this.opState = new OpState();
      this.opState.initialize(newEntryBase);
      writeOpLogBytes(this.crf, async, false/*no need to flush this record*/);
      this.dirHolder.incrementTotalOplogSize(getOpStateSize());
    } finally {
      this.opState = saved;
    }
    //       {
    //         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
    //         l.info(LocalizedStrings.DEBUG, "base inc=" + OPLOG_NEW_ENTRY_BASE_REC_SIZE + " currSize=" + this.crf.currSize);
    //       }
    return true;
  }

  /**
   * Return true if this oplog has a drf but does not have a crf
   */
  boolean isDrfOnly() {
    return this.drf.f != null && this.crf.f == null;
  }
  
  /**
   * This constructor will get invoked only in case of persistent region
   * when it is recovering an oplog.
   * @param oplogId
   * @param parent
   */
  Oplog(long oplogId, PersistentOplogSet parent) {
    // @todo have the crf and drf use different directories.
    if (oplogId > DiskId.MAX_OPLOG_ID) {
      throw new IllegalStateException("Too many oplogs. The oplog id can not exceed " + DiskId.MAX_OPLOG_ID);
    }
    this.isRecovering = true;
    this.oplogId = oplogId;
    this.parent = parent.getParent();
    this.oplogSet = parent;
    this.logger = getParent().getCache().getLoggerI18n();
    this.opState = new OpState();
    long maxOplogSizeParam = getParent().getMaxOplogSizeInBytes();
    this.maxOplogSize = maxOplogSizeParam;
    setMaxCrfDrfSize();
    this.stats = getParent().getStats();
    this.compactOplogs = getParent().getAutoCompact();
    this.closed = true;
    this.crf.RAFClosed = true;
    this.deleted.set(true);
    this.haveRecoveredCrf = false;
    this.haveRecoveredDrf = false;
    this.newOplog = false;
    this.idxkrf = new OplogIndex(this);
  }

  private boolean newOplog = true;
  /**
   * Returns true if added file was crf; false if drf
   * @param foundDrfs 
   * @param foundCrfs 
   */
  boolean addRecoveredFile(File f, DirectoryHolder dh, TLongHashSet foundCrfs, TLongHashSet foundDrfs) {
    String fname = f.getName();
    if (this.dirHolder != null) {
      if (!dh.equals(this.dirHolder)) {
        throw new DiskAccessException("Oplog#" + getOplogId()
                                      + " has files in two different directories: \""
                                      + this.dirHolder
                                      + "\", and \""
                                      + dh
                                      + "\". Both the crf and drf for this oplog should be in the same directory.",
                                      getParent());
      }
    } else {
      this.dirHolder = dh;
    }
    if (fname.endsWith(Oplog.CRF_FILE_EXT)) {
      this.crf.f = f;
      foundCrfs.add(this.oplogId);
    } else if (fname.endsWith(Oplog.DRF_FILE_EXT)) {
      this.drf.f = f;
      foundDrfs.add(this.oplogId);
//    } else if (fname.endsWith(Oplog.KRF_FILE_EXT)) {
//      this.krf.f = f;
    } else if (Oplog.IDX_PATTERN.matcher(fname).matches()) {
      idxkrf.addRecoveredFile(fname);
    }else {
      assert false : fname;
    }
    return false;
  }

  void setRecoveredDrfSize(long size) {
    this.drf.currSize += size;
    this.drf.bytesFlushed += size;
  }
  void setRecoveredCrfSize(long size) {
    this.crf.currSize += size;
    this.crf.bytesFlushed += size;
//       {
//         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//         l.info(LocalizedStrings.DEBUG, "setRecoveredCrfSize to=" + this.crf.currSize);
//       }
  }
  private boolean isRecovering;
  boolean isRecovering() {
    return this.isRecovering;
  }

  public final DiskStoreImpl getParent() {
    return this.parent;
  }
  
  private PersistentOplogSet getOplogSet() {
    return oplogSet;
  }

  void initAfterRecovery(boolean offline) {
    this.isRecovering = false;
    this.closed = false;
    this.deleted.set(false);
    String n = getParent().getName();
    // crf might not exist; but drf always will
    this.diskFile = new File(this.drf.f.getParentFile(),
                             oplogSet.getPrefix()
                             + n + "_" + this.oplogId);
    try {
      // This is a recovered oplog and we only read from its crf.
      // No need to open the drf.
      this.doneAppending = true;
      if (this.crf.f != null && !hasNoLiveValues()) {
        this.closed = false;
        // truncate crf/drf if their actual size is less than their pre-blow size
        this.crf.raf = new RandomAccessFile(this.crf.f, "rw");
        this.crf.RAFClosed = false;
        this.crf.channel = this.crf.raf.getChannel();
        unpreblow(this.crf, getMaxCrfSize());
        this.crf.raf.close();
        // make crf read only
        this.crf.raf = new RandomAccessFile(this.crf.f, "r");
        this.crf.channel = this.crf.raf.getChannel();
        this.stats.incOpenOplogs();
        
        //drf.raf is null at this point. create one and close it to retain existing behavior
        try {
          this.drf.raf = new RandomAccessFile(this.drf.f, "rw");
          this.drf.RAFClosed = false;
          this.drf.channel = this.drf.raf.getChannel();
          unpreblow(this.drf, getMaxDrfSize());
        } finally {
          this.drf.raf.close();
          this.drf.raf = null;
          this.drf.RAFClosed = true;
        }
        // no need to seek to the end; we will not be writing to a recovered oplog; only reading
        // this.crf.raf.seek(this.crf.currSize);
      } else if (!offline) {
        // drf exists but crf has been deleted (because it was empty).
        // I don't think the drf needs to be opened. It is only used during recovery.
        // At some point the compacter my identify that it can be deleted.
        this.crf.RAFClosed = true;
        deleteCRF();
        this.closed = true;
        this.deleted.set(true);
      }
      this.drf.RAFClosed = true; // since we never open it on a recovered oplog
    }catch (IOException ex) {
      getParent().getCancelCriterion().checkCancelInProgress(ex);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_CREATING_OPERATION_LOG_BECAUSE_0.toLocalizedString(ex), getParent());
    }
    if (hasNoLiveValues() && !offline) {
      getOplogSet().removeOplog(getOplogId(), true, getHasDeletes() ? this : null);
      if (!getHasDeletes()) {
        getOplogSet().drfDelete(this.oplogId);
        deleteFile(this.drf);
      }
    } else if (needsCompaction()) {
      // just leave it in the list it is already in
    } else {
      // remove it from the compactable list
      getOplogSet().removeOplog(getOplogId(), true/* say we are deleting so that undeletedOplogSize is not inced */, null);
      // add it to the inactive list
      getOplogSet().addInactive(this);
    }
  }

  boolean getHasDeletes() {
    return this.hasDeletes.get();
  }
  private void setHasDeletes(boolean v) {
    this.hasDeletes.set(v);
  }

  private void closeAndDeleteAfterEx(IOException ex, OplogFile olf) {
    if (olf == null) {
      return;
    }
    
    if (olf.raf != null) {
      try {
        olf.raf.close();
      } catch (IOException e) {
        logger.warning(LocalizedStrings.Oplog_Close_Failed, olf.f.getAbsolutePath(), e);
      }
    }
    olf.RAFClosed = true;
    if (!olf.f.delete() && olf.f.exists()) {
      throw new DiskAccessException(
          LocalizedStrings.Oplog_COULD_NOT_DELETE__0_.toLocalizedString(olf.f
              .getAbsolutePath()), ex, getParent());
    }
  }
  
  private void preblow(OplogFile olf, long maxSize) throws IOException {
    GemFireCacheImpl.StaticSystemCallbacks ssc = GemFireCacheImpl.getInternalProductCallbacks();
    if (!getParent().isOfflineCompacting() && ssc != null && ssc.isSnappyStore() && ssc.isAccessor()
        && this.getParent().getName().equals(GemFireCacheImpl.getDefaultDiskStoreName())) {
      logger.warning(LocalizedStrings.SHOULDNT_INVOKE, "Pre blow is invoked on Accessor Node.");
      return;
    }

//     logger.info(LocalizedStrings.DEBUG, "DEBUG preblow(" + maxSize + ")  dirAvailSpace=" + this.dirHolder.getAvailableSpace());
    long availableSpace = this.dirHolder.getAvailableSpace();
    if (availableSpace >= maxSize) {
      try {
        NativeCalls.getInstance().preBlow(olf.f.getAbsolutePath(), maxSize,
            (DiskStoreImpl.PREALLOCATE_OPLOGS && !DiskStoreImpl.SET_IGNORE_PREALLOCATE));
      }
      catch (IOException ioe) {
        logger.warning(LocalizedStrings.DEBUG, "Could not pregrow oplog to " + maxSize + " because: " + ioe);
//         if (this.logger.warningEnabled()) {
//           this.logger.warning(
//               LocalizedStrings.Oplog_OPLOGCREATEOPLOGEXCEPTION_IN_PREBLOWING_THE_FILE_A_NEW_RAF_OBJECT_FOR_THE_OPLOG_FILE_WILL_BE_CREATED_WILL_NOT_BE_PREBLOWNEXCEPTION_STRING_IS_0,
//               ioe, null);
//         }
        // I don't think I need any of this. If setLength throws then
        // the file is still ok.
        // I need this on windows. I'm seeing this in testPreblowErrorCondition:
// Caused by: java.io.IOException: The parameter is incorrect
// 	at sun.nio.ch.FileDispatcher.write0(Native Method)
// 	at sun.nio.ch.FileDispatcher.write(FileDispatcher.java:44)
// 	at sun.nio.ch.IOUtil.writeFromNativeBuffer(IOUtil.java:104)
// 	at sun.nio.ch.IOUtil.write(IOUtil.java:60)
// 	at sun.nio.ch.FileChannelImpl.write(FileChannelImpl.java:206)
// 	at com.gemstone.gemfire.internal.cache.Oplog.flush(Oplog.java:3377)
// 	at com.gemstone.gemfire.internal.cache.Oplog.flushAll(Oplog.java:3419)
        /*
        {
          String os = System.getProperty("os.name");
          if (os != null) {
	    if (os.indexOf("Windows") != -1) {
              olf.raf.close();
              olf.RAFClosed = true;
              if (!olf.f.delete() && olf.f.exists()) {
                throw new DiskAccessException(LocalizedStrings.Oplog_COULD_NOT_DELETE__0_.toLocalizedString(olf.f.getAbsolutePath()), getParent());
              }
              if (logger.fineEnabled()) {
                logger.fine("recreating operation log file " + olf.f);
              }
              olf.raf = new RandomAccessFile(olf.f, SYNC_WRITES ? "rwd" : "rw");
              olf.RAFClosed = false;
            }
          }
        }
        */
        closeAndDeleteAfterEx(ioe, olf);
        throw new InsufficientDiskSpaceException(
            LocalizedStrings.Oplog_PreAllocate_Failure.toLocalizedString(
                olf.f.getAbsolutePath(), maxSize), ioe, getParent());
      }
    }
    // TODO: Perhaps the test flag is not requierd here. Will re-visit.
    else if (DiskStoreImpl.PREALLOCATE_OPLOGS && !DiskStoreImpl.SET_IGNORE_PREALLOCATE) {
      throw new InsufficientDiskSpaceException(
          LocalizedStrings.Oplog_PreAllocate_Failure.toLocalizedString(
              olf.f.getAbsolutePath(), maxSize), new IOException(
              "not enough space left to pre-blow, available=" + availableSpace
                  + ", required=" + maxSize), getParent());
    }
  }

  private void unpreblow(OplogFile olf, long maxSize) {
    synchronized (/*olf*/this.lock) {
      if (!olf.RAFClosed && !olf.unpreblown) {
        olf.unpreblown = true;
        if (olf.currSize < maxSize) {
          try {
             olf.raf.setLength(olf.currSize);
//             {
//               LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//               l.info(LocalizedStrings.DEBUG, "after setLength setting size to=" + olf.currSize
//                      + " fp=" + olf.raf.getFilePointer()
//                      + " oplog#" + getOplogId());
//             }
          }
          catch (IOException ignore) {
          }
        }
      }
    }
  }
  /**
   * Creates the crf oplog file
   * 
   * @throws IOException
   */
  private void createCrf(Oplog prevOplog) throws IOException
  {
    File f = new File(this.diskFile.getPath() + CRF_FILE_EXT);
    if (logger.fineEnabled()) {
      logger.fine("Creating operation log file " + f);
    }
    this.crf.f = f;
    preblow(this.crf, getMaxCrfSize());
    this.crf.raf = new RandomAccessFile(f,
        getParent().getSyncWrites() ? "rwd" : "rw");
    this.crf.RAFClosed = false;
    this.crf.channel = this.crf.raf.getChannel();
    oplogSet.crfCreate(this.oplogId);
    if (this.crf.outputStream != null) {
      this.crf.outputStream.close();
    }
    this.crf.outputStream = createOutputStream(prevOplog,
        prevOplog != null ? prevOplog.crf : null, this.crf);

    if (logger.infoEnabled()) {
      logger.info(LocalizedStrings.Oplog_CREATE_0_1_2,
                  new Object[] {toString(),
                                getFileType(this.crf),
                                getParent().getName()});
    }

    this.stats.incOpenOplogs();
    writeDiskStoreRecord(this.crf);
    writeGemfireVersionRecord(this.crf);    
    writeRVVRecord(this.crf, false);
    
    //Fix for bug 41654 - don't count the header
    //size against the size of the oplog. This ensures that
    //even if we have a large RVV, we can still write up to
    //max-oplog-size bytes to this oplog.
    this.maxCrfSize += this.crf.currSize;
  }

  private static OplogFile.FileChannelOutputStream createOutputStream(
      final Oplog prevOplog, final OplogFile prevOlf, final OplogFile olf)
      throws IOException {
    if (prevOlf != null) {
      synchronized (prevOplog.crf) {
        // release the old stream buffer
        final OplogFile.FileChannelOutputStream outputStream = prevOlf.outputStream;
        if (outputStream != null) {
          outputStream.close();
          prevOlf.outputStream = null;
        }
      }
    }
    final int bufSize = Integer.getInteger("WRITE_BUF_SIZE", 32768);
    return olf.new FileChannelOutputStream(bufSize);
  }

  /**
   * Creates the drf oplog file
   * 
   * @throws IOException
   */
  private void createDrf(Oplog prevOplog) throws IOException
  {
    String drfFilePath = this.diskFile.getPath() + DRF_FILE_EXT;
    File f = new File(drfFilePath);
    this.drf.f = f;
    if (logger.fineEnabled()) {
      logger.fine("Creating operation log file " + f);
    }
    preblow(this.drf, getMaxDrfSize());
    this.drf.raf = new RandomAccessFile(f,
        getParent().getSyncWrites() ? "rwd" : "rw");
    this.drf.RAFClosed = false;
    this.drf.channel = this.drf.raf.getChannel();
    this.oplogSet.drfCreate(this.oplogId);
    this.drf.outputStream = createOutputStream(prevOplog,
        prevOplog != null ? prevOplog.drf : null, this.drf);
    if (logger.infoEnabled()) {
      logger.info(LocalizedStrings.Oplog_CREATE_0_1_2,
                  new Object[] {toString(),
                                getFileType(this.drf),
                                getParent().getName()});
    }
    writeDiskStoreRecord(this.drf);
    writeGemfireVersionRecord(this.drf);
    writeRVVRecord(this.drf, true);
  }
  
  /**
   * Returns the <code>DiskStoreStats</code> for this oplog
   */
  public DiskStoreStats getStats()
  {
    return this.stats;
  }
  
  /**
   * Flushes any pending writes to disk.
   * 
   * public final void flush() { forceFlush(); }
   */

  /**
   * Test Method to be used only for testing purposes. Gets the underlying File
   * object for the Oplog . Oplog class uses this File object to obtain the
   * RandomAccessFile object. Before returning the File object , the dat present
   * in the buffers of the RandomAccessFile object is flushed. Otherwise, for
   * windows the actual file length does not match with the File size obtained
   * from the File object
   * 
   * @throws IOException
   * @throws SyncFailedException
   */
  File getOplogFile() throws SyncFailedException, IOException
  {
    // @todo check callers for drf
    synchronized (this.lock/*crf*/) {
      if (!this.crf.RAFClosed) {
        this.crf.raf.getFD().sync();
      }
      return this.crf.f;
    }
  }

  /**
   * Given a set of Oplog file names return a Set of the oplog files that match those names that are 
   * managed by this Oplog.
   * @param baselineFiles a Set of operation log file names in the baseline
   * @param filesNeedingBackup a set of files still needing backup. Files will be removed
   * from this set if they are found in the baseline.
   */
  Set<String> gatherMatchingOplogFiles(Set<String> baselineFiles, Set<File> filesNeedingBackup) {
    Set<String> matchingFiles = new LinkedHashSet<String>();

    for(Iterator<File> itr = filesNeedingBackup.iterator(); itr.hasNext(); ) {
      File file = itr.next();
      // If the file is in the baseline, add it to the baseline map and remove
      // it from the set of files to backup.
      if(baselineFiles.contains(file.getName())) {
        matchingFiles.add(file.getName());
        itr.remove();
      }
    }
    
    return matchingFiles;
  }
  
  /**
   * Returns the set of valid files assocatiated with this oplog - the
   * crf, drf, krf, and idxkrf if they are present.
   */
  public Set<File> getAllFiles() {
 // Check for crf existence
    Set<File> files = new LinkedHashSet<File>(4);
    if((null != this.crf.f) && this.crf.f.exists()) {
      files.add(IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(this.crf.f));
    }
    
    // Check for drf existence
    if((null != this.drf.f) && this.drf.f.exists()) {
      files.add(IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(this.drf.f));
    }
    
    // Check for krf existence
    if(getParent().getDiskInitFile().hasKrf(this.oplogId)) {
      File krfFile = new File(getKrfFilePath());
      if(krfFile.exists()) {
        files.add(IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(krfFile));      
      }
      
      File idxFile = getIndexFileIfValid(false);
      if(idxFile != null && idxFile.exists()) {
        files.add(IOUtils.tryGetCanonicalFileElseGetAbsoluteFile(idxFile));      
      }
    }
    return files;
  }
  
  /**
   * Returns a map of baseline oplog files to copy that match this oplog's files for a currently running backup.
   * @param baselineOplogFiles a List of files to match this oplog's filenames against.
   * @param allFiles - a set of all files for the oplog. This set will be modified to remove all of the files
   * that are present in the baseline.
   * @return a map of baslineline oplog files to copy.  May be empty if total current set for this oplog
   * does not match the baseline.
   */
  Map<File,File> mapBaseline(List<File> baselineOplogFiles, Set<File> allFiles) {
    // Map of baseline oplog file name to oplog file
    Map<String,File> baselineOplogMap = TransformUtils.transformAndMap(baselineOplogFiles,TransformUtils.fileNameTransformer);
    
    // Returned Map of baseline file to current oplog file
    Map<File,File> baselineToOplogMap = ObjectObjectHashMap.withExpectedSize(16);
    for(Iterator<File> itr = allFiles.iterator(); itr.hasNext(); ) {
      File file = itr.next();
      // If the file is in the baseline, add it to the baseline map and remove
      // it from the set of files to backup.
      if(baselineOplogMap.containsKey(file.getName())) {
        baselineToOplogMap.put(baselineOplogMap.get(file.getName()), file);
        itr.remove();
      }
    }
    
    return baselineToOplogMap;
  }

  /** the oplog identifier * */
  public long getOplogId()
  {
    return this.oplogId;
  }

  /** Returns the unserialized bytes and bits for the given Entry. 
   * If Oplog is destroyed while querying, then the DiskRegion is queried again to
   * obatin the value This method should never get invoked for an entry which
   * has been destroyed
   * 
   * @since 3.2.1 
   * @param id The DiskId for the entry @param offset The offset in
   *        this OpLog where the entry is present. @param faultingIn @param
   *        bitOnly boolean indicating whether to extract just the UserBit or
   *        UserBit with value @return BytesAndBits object wrapping the value &
   *        user bit
   */
  public final BytesAndBits getBytesAndBits(DiskRegionView dr, DiskId id, boolean faultingIn,
                                            boolean bitOnly)
  {
    Oplog retryOplog = null;
    long offset = 0;
    synchronized (id) {
      long opId = id.getOplogId();
      if (opId != getOplogId()) {
        // the oplog changed on us so we need to do a recursive
        // call after unsyncing
        retryOplog = getOplogSet().getChild(opId);
      } else {
        // fetch this while synced so it will be consistent with oplogId
        offset = id.getOffsetInOplog();
      }
    }
    if (retryOplog != null) {
      return retryOplog.getBytesAndBits(dr, id, faultingIn, bitOnly);
    }
    BytesAndBits bb;
    long start = this.stats.startRead();

    // Asif: If the offset happens to be -1, still it is possible that
    // the data is present in the current oplog file.
    if (offset == -1) {
      // Asif: Since it is given that a get operation has alreadty
      // taken a
      // lock on an entry , no put operation could have modified the
      // oplog ID
      // there fore synchronization is not needed
      // synchronized (id) {
      // if (id.getOplogId() == this.oplogId) {
      offset = id.getOffsetInOplog();
      // }
      // }
    }

    if (DiskStoreImpl.TRACE_READS) {
      logger.info(LocalizedStrings.DEBUG,
          "TRACE_READS getBytesAndBits: id=<" + abs(id.getKeyId())
              + " valueOffset=" + offset
              + " userBits=" + id.getUserBits()
              + " valueLen=" + id.getValueLength()
              + " drId=" + dr.getId()
              + " oplog#" + getOplogId());
    }
    // Asif :If the current OpLog is not destroyed ( its opLogRaf file
    // is still open) we can retrieve the value from this oplog.
    try {
      bb = basicGet(dr, offset, bitOnly, id.getValueLength(), id.getUserBits());
    }
    catch (DiskAccessException dae) {
      if (this.logger.errorEnabled()) {
        this.logger
          .error(
                 LocalizedStrings.Oplog_OPLOGBASICGET_ERROR_IN_READING_THE_DATA_FROM_DISK_FOR_DISK_ID_HAVING_DATA_AS_0,
                 id, dae);
      }
      throw dae;
    }

    if (bb == null) {
      throw new EntryDestroyedException(LocalizedStrings.Oplog_NO_VALUE_WAS_FOUND_FOR_ENTRY_WITH_DISK_ID_0_ON_A_REGION_WITH_SYNCHRONOUS_WRITING_SET_TO_1
                                        .toLocalizedString(new Object[] {id, Boolean.valueOf(dr.isSync())}));
    }
    if (bitOnly) {
      dr.endRead(start, this.stats.endRead(start, 1), 1);
    } else {
      final int numRead = bb.size();
      dr.endRead(start, this.stats.endRead(start, numRead), numRead);
    }
    return bb;

  }

  /**
   * Returns the object stored on disk with the given id. This method is used
   * for testing purposes only. As such, it bypasses the buffer and goes
   * directly to the disk. This is not a thread safe function , in the sense, it
   * is possible that by the time the OpLog is queried , data might move HTree
   * with the oplog being destroyed
   * 
   * @param id
   *                A DiskId object for which the value on disk will be fetched
   * 
   */
  public final BytesAndBits getNoBuffer(DiskRegion dr, DiskId id)
  {
    if (logger.finerEnabled()) {
      logger
          .finer("Oplog::getNoBuffer:Before invoking Oplog.basicGet for DiskID ="
              + id);
    }

    try {
      BytesAndBits bb = basicGet(dr, id.getOffsetInOplog(), false,
                                 id.getValueLength(), id.getUserBits());
      return bb;
    }
    catch (DiskAccessException dae) {
      if (logger.errorEnabled()) {
        logger.error(
            LocalizedStrings.Oplog_OPLOGGETNOBUFFEREXCEPTION_IN_RETRIEVING_VALUE_FROM_DISK_FOR_DISKID_0,
            id, dae);
      }
      throw dae;
    }
    catch (IllegalStateException ise) {
      if (logger.errorEnabled()) {
        logger.error(
            LocalizedStrings.Oplog_OPLOGGETNOBUFFEREXCEPTION_IN_RETRIEVING_VALUE_FROM_DISK_FOR_DISKID_0,
            id, ise);
      }
      throw ise;
    }
  }

  void close(DiskRegion dr) {
    // while a krf is being created can not close a region
    lockCompactor();
    try {
//      if (logger.infoEnabled()) {
//        logger.info(LocalizedStrings.DEBUG, "DEBUG closing dr=" + dr.getId()
//            + " on oplog " + this);
//      }
    addUnrecoveredRegion(dr.getId());
    DiskRegionInfo dri = getDRI(dr);
    if (dri != null) {
      long clearCount = dri.clear(null);
      if (clearCount != 0) {
        this.totalLiveCount.addAndGet(-clearCount);
        // no need to call handleNoLiveValues because we now have an unrecovered region.
      }
      this.regionMap.remove(dr.getId(), dri);
    }
    } finally {
      unlockCompactor();
    }
  }

  void clear(DiskRegion dr, RegionVersionVector rvv) {
    DiskRegionInfo dri = getDRI(dr);
    if (dri != null) {
      long clearCount = dri.clear(rvv);
      if (clearCount != 0) {
        this.totalLiveCount.addAndGet(-clearCount);
        if (!isCompacting() || calledByCompactorThread()) {
          handleNoLiveValues();
        }
      }
    }
  }

  void destroy(DiskRegion dr) {
    DiskRegionInfo dri = getDRI(dr);
    if (dri != null) {
      long clearCount = dri.clear(null);
      if (clearCount != 0) {
        this.totalLiveCount.addAndGet(-clearCount);
        if (!isCompacting() || calledByCompactorThread()) {
          handleNoLiveValues();
        }
      }
      this.regionMap.remove(dr.getId(), dri);
    }
  }

  long getMaxRecoveredOplogEntryId() {
    long result = this.recoverNewEntryId;
    if (this.recoverModEntryIdHWM > result) {
      result = this.recoverModEntryIdHWM;
    }
    if (this.recoverDelEntryIdHWM > result) {
      result = this.recoverDelEntryIdHWM;
    }
    return result;
  }

  /**
   * Used during recovery to calculate the OplogEntryId of the next NEW_ENTRY record.
   * @since prPersistSprint1
   */
  private long recoverNewEntryId = DiskStoreImpl.INVALID_ID;
  /**
   * Used during writing to remember the last MOD_ENTRY OplogEntryId written to this oplog.
   * @since prPersistSprint1
   */
  private long writeModEntryId = DiskStoreImpl.INVALID_ID;
  /**
   * Used during recovery to calculate the OplogEntryId of the next MOD_ENTRY record.
   * @since prPersistSprint1
   */
  private long recoverModEntryId = DiskStoreImpl.INVALID_ID;
  /**
   * Added to fix bug 41301. High water mark of modified entries.
   */
  private long recoverModEntryIdHWM = DiskStoreImpl.INVALID_ID;
  /**
   * Added to fix bug 41340. High water mark of deleted entries.
   */
  private long recoverDelEntryIdHWM = DiskStoreImpl.INVALID_ID;
  /**
   * Used during writing to remember the last DEL_ENTRY OplogEntryId written to this oplog.
   * @since prPersistSprint1
   */
  private long writeDelEntryId = DiskStoreImpl.INVALID_ID;
  /**
   * Used during recovery to calculate the OplogEntryId of the next DEL_ENTRY record.
   * @since prPersistSprint1
   */
  private long recoverDelEntryId = DiskStoreImpl.INVALID_ID;

  private void setRecoverNewEntryId(long v) {
    this.recoverNewEntryId = v;
  }
  private long incRecoverNewEntryId() {
    this.recoverNewEntryId++;
    return this.recoverNewEntryId;
  }
  /**
   * Given a delta calculate the OplogEntryId for a MOD_ENTRY.
   */
  public long calcModEntryId(long delta) {
    long oplogKeyId = this.recoverModEntryId + delta;
    if (DiskStoreImpl.TRACE_RECOVERY) {
      logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY calcModEntryId delta=" + delta
                  + " recoverModEntryId=" + this.recoverModEntryId
                  + " oplogKeyId=" + oplogKeyId);
    }
    this.recoverModEntryId = oplogKeyId;
    if (oplogKeyId > this.recoverModEntryIdHWM) { 
      this.recoverModEntryIdHWM = oplogKeyId; // fixes bug 41301
    }
    return oplogKeyId;
  }
  /**
   * Given a delta calculate the OplogEntryId for a DEL_ENTRY.
   */
  public long calcDelEntryId(long delta) {
    long oplogKeyId = this.recoverDelEntryId + delta;
    if (DiskStoreImpl.TRACE_RECOVERY) {
      logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY calcDelEntryId delta=" + delta
                  + " recoverDelEntryId=" + this.recoverDelEntryId
                  + " oplogKeyId=" + oplogKeyId);
    }
    this.recoverDelEntryId = oplogKeyId;
    if (oplogKeyId > this.recoverDelEntryIdHWM) { 
      this.recoverDelEntryIdHWM = oplogKeyId; // fixes bug 41340
    }
    return oplogKeyId;
  }
  private boolean crashed;
  boolean isCrashed() {
    return this.crashed;
  }
  
  /**
   * Return bytes read.
   */
  long recoverDrf(OplogEntryIdSet deletedIds,
                  boolean alreadyRecoveredOnce,
                  boolean latestOplog) {
    File drfFile = this.drf.f;
    if (drfFile == null) {
      this.haveRecoveredDrf = true;
      return 0L;
    }
    lockCompactor();
    try {
      if (this.haveRecoveredDrf && !getHasDeletes()) return 0L; // do this while holding lock
      if (!this.haveRecoveredDrf) {
        this.haveRecoveredDrf = true;
      }
    if (logger.infoEnabled()) {
      logger.info(LocalizedStrings.DiskRegion_RECOVERING_OPLOG_0_1_2,
                  new Object[] {toString(),
                                drfFile.getAbsolutePath(),
                                getParent().getName()});
    }
    this.recoverDelEntryId = DiskStoreImpl.INVALID_ID;
    boolean readLastRecord = true;
    CountingDataInputStream dis = null;
    try {
      int recordCount = 0;
      boolean foundDiskStoreRecord = false;
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(drfFile);
        dis = new CountingDataInputStream(new BufferedInputStream(fis,
            DEFAULT_BUFFER_SIZE), drfFile.length());
        boolean endOfLog = false;
        while (!endOfLog) {
          if (dis.atEndOfFile()) {
            endOfLog = true;
            break;
          }
          readLastRecord = false;
          byte opCode = dis.readByte();
          if (DiskStoreImpl.TRACE_RECOVERY) {
            logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY drf byte=" + opCode + " location=" + Long.toHexString(dis.getCount()));
          }
          switch (opCode) {
          case OPLOG_EOF_ID:
            // we are at the end of the oplog. So we need to back up one byte
            dis.decrementCount();
            endOfLog = true;
            break;
          case OPLOG_DEL_ENTRY_1ID:
          case OPLOG_DEL_ENTRY_2ID:
          case OPLOG_DEL_ENTRY_3ID:
          case OPLOG_DEL_ENTRY_4ID:
          case OPLOG_DEL_ENTRY_5ID:
          case OPLOG_DEL_ENTRY_6ID:
          case OPLOG_DEL_ENTRY_7ID:
          case OPLOG_DEL_ENTRY_8ID:
            readDelEntry(dis, opCode, deletedIds, parent);
            recordCount++;
            break;
          case OPLOG_DISK_STORE_ID:
            readDiskStoreRecord(dis, this.drf.f);
            foundDiskStoreRecord = true;
            recordCount++;
            break;
          case OPLOG_GEMFIRE_VERSION:
            readGemfireVersionRecord(dis, this.drf.f);
            recordCount++;
            break;
            
          case OPLOG_RVV:
              dis.getCount();
            readRVVRecord(dis, this.drf.f, true, latestOplog);
            recordCount++;
            break;

          default:
            throw new DiskAccessException(LocalizedStrings.Oplog_UNKNOWN_OPCODE_0_FOUND_IN_DISK_OPERATION_LOG.toLocalizedString(opCode), getParent());
          }
          readLastRecord = true;
          // @todo
//           if (rgn.isDestroyed()) {
//             break;
//           }
        } // while
      }
      finally {
        if (dis != null) {
          dis.close();
        }
        if (fis != null) {
          fis.close();
        }
      }
      if (!foundDiskStoreRecord && recordCount > 0) {
        throw new DiskAccessException("The oplog file \""
                                      + this.drf.f
                                      + "\" does not belong to the init file \""
                                      + getParent().getInitFile() + "\". Drf did not contain a disk store id.",
                                      getParent());
      }
    }
    catch (EOFException ex) {
      // ignore since a partial record write can be caused by a crash
//       if (byteCount < fileLength) {
//         throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_READING_FILE_DURING_RECOVERY_FROM_0
//             .toLocalizedString(drfFile.getPath()), ex, getParent());
//       }// else do nothing, this is expected in crash scenarios
    }
    catch (IOException ex) {
      getParent().getCancelCriterion().checkCancelInProgress(ex);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_READING_FILE_DURING_RECOVERY_FROM_0
          .toLocalizedString(drfFile.getPath()), ex, getParent());
    }
    catch (CancelException ignore) {
      if (logger.fineEnabled()) {
        logger.fine("Oplog::readOplog:Error in recovery as Cache was closed",
            ignore);
      }
    }
    catch (RegionDestroyedException ignore) {
      if (logger.fineEnabled()) {
        logger.fine(
            "Oplog::readOplog:Error in recovery as Region was destroyed",
            ignore);
      }
    }
    catch (IllegalStateException ex) {
      // @todo
//       if (!rgn.isClosed()) {
        throw ex;
//       }
    }
    //Add the Oplog size to the Directory Holder which owns this oplog,
    // so that available space is correctly calculated & stats updated. 
    long byteCount = 0;
    if (!readLastRecord) {
      // this means that there was a crash
      // and hence we should not continue to read
      // the next oplog
      this.crashed = true;
      if (dis != null) {
        byteCount = dis.getFileLength();
      }
    } else {
      if (dis != null) {
        byteCount = dis.getCount();
      }
    }
    if (!alreadyRecoveredOnce) {
      setRecoveredDrfSize(byteCount);
//       {
//         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//         l.info(LocalizedStrings.DEBUG, "drfSize inc=" + byteCount);
//       }
      this.dirHolder.incrementTotalOplogSize(byteCount);
    }
    return byteCount;
    } finally {
      unlockCompactor();
    }
  }

  /**
   * This map is used during recovery to keep track of what entries were
   * recovered. Its keys are the oplogEntryId; its values are the actual
   * logical keys that end up in the Region's keys.
   * It used to be a local variable in basicInitializeOwner
   * but now that it needs to live longer than that method
   * I made it an instance variable
   * It is now only alive during recoverRegionsThatAreReady so it could
   * once again be passed down into each oplog.
   * <p> If offlineCompaction the value in this map will have the key bytes,
   * values bytes, user bits, etc (any info we need to copy forward).
   */
  private OplogEntryIdMap kvMap;
  public final OplogEntryIdMap getRecoveryMap() {
    return this.kvMap;
  }

  private volatile OplogEntryIdMap kvInitMap;
  public final OplogEntryIdMap getInitRecoveryMap() {
    return this.kvInitMap;
  }

  final void clearInitRecoveryMap() {
    this.kvInitMap = null;
  }

  /**
   * This map is used during recover to keep track of keys
   * that are skipped. Later modify records in the same oplog
   * may use this map to retrieve the correct key.
   */
  private OplogEntryIdMap skippedKeyBytes;

  private boolean readKrf(OplogEntryIdSet deletedIds, 
      boolean recoverValues, 
      boolean recoverValuesSync, 
      Set<Oplog> oplogsNeedingValueRecovery, 
      boolean latestOplog) {
    File f = new File(this.diskFile.getPath() + KRF_FILE_EXT);
    if (!f.exists()) {
      return false;
    }
    
    if(!getParent().getDiskInitFile().hasKrf(this.oplogId)) {
      logger.info(LocalizedStrings.Oplog_REMOVING_INCOMPLETE_KRF, new Object[] {
          f.getName(), this.oplogId, getParent().getName() });
      f.delete();
      return false;
    }
    // Set krfCreated to true since we have a krf.
    if (logger.fineEnabled()) {
      logger.info(LocalizedStrings.DEBUG,
          "readKrf:: setting krfcreated to true for oplog: " + this);
    }
    this.krfCreated.set(true);
    
    //Fix for 42741 - we do this after creating setting the krfCreated flag
    //so that we don't try to recreate the krf.
    if(recoverValuesSync) {
      return false;
    }

    FileInputStream fis;
    ChannelBufferUnsafeDataInputStream dis = null;
    try {
      fis = new FileInputStream(f);
    } catch (FileNotFoundException ex) {
      return false;
    }
    try {
    if (getParent().isOffline() && !getParent().FORCE_KRF_RECOVERY) {
      return false;
    }
    if (logger.infoEnabled()) {
      logger
          .info(LocalizedStrings.DiskRegion_RECOVERING_OPLOG_0_1_2,
              new Object[] { toString(), f.getAbsolutePath(),
                  getParent().getName() });
    }
    this.recoverNewEntryId = DiskStoreImpl.INVALID_ID;
    this.recoverModEntryId = DiskStoreImpl.INVALID_ID;
    this.recoverModEntryIdHWM = DiskStoreImpl.INVALID_ID;
    long oplogKeyIdHWM = DiskStoreImpl.INVALID_ID;
    int krfEntryCount = 0;
    //DataInputStream dis = new DataInputStream(new BufferedInputStream(fis,
    //    LARGE_BUFFER_SIZE));
    final Version version = getProductVersionIfOld();
    final ByteArrayDataInput in = new ByteArrayDataInput();
    final long currentTime = getParent().getCache().cacheTimeMillis();
    dis = new ChannelBufferUnsafeDataInputStream(fis.getChannel(),
        LARGE_BUFFER_SIZE);
    try {
      readDiskStoreRecord(dis, f);
      readGemfireVersionRecord(dis, f);
      readTotalCountRecord(dis, f);
      readRVVRecord(dis, f, false, latestOplog);
      long lastOffset = 0;
      byte[] keyBytes = DataSerializer.readByteArray(dis);
      while (keyBytes != null) {
        byte userBits = dis.readByte();
        int valueLength = InternalDataSerializer.readArrayLength(dis);
        byte[] valueBytes = null;
        long drId = DiskInitFile.readDiskRegionID(dis);
        DiskRecoveryStore drs = getOplogSet().getCurrentlyRecovering(drId);
        
        // read version
        VersionTag tag = null;
        long lastModifiedTime = 0;
        if (EntryBits.isWithVersions(userBits)) {
          tag = readVersionsFromOplog(dis);
          // Update the RVV with the new entry
          if (drs != null) {
            drs.recordRecoveredVersionTag(tag);
          }
        }
        // read last modified time for no-versions case
        else if (EntryBits.isLastModifiedTime(userBits)) {
          lastModifiedTime = InternalDataSerializer.readUnsignedVL(dis);
        }
        else {
          lastModifiedTime = currentTime;
        }

        long oplogKeyId = InternalDataSerializer.readVLOld(dis);
        long oplogOffset;
        if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
          oplogOffset = -1;
        } else {
          oplogOffset = lastOffset + InternalDataSerializer.readVLOld(dis);
          lastOffset = oplogOffset;
        }
        
        if (oplogKeyId > oplogKeyIdHWM) {
          oplogKeyIdHWM = oplogKeyId;
        }
        if (okToSkipModifyRecord(deletedIds, drId, drs, oplogKeyId, true, tag).skip()) {
          if (DiskStoreImpl.TRACE_RECOVERY) {
            logger.info(LocalizedStrings.DEBUG,
                "TRACE_RECOVERY readNewEntry skipping oplogKeyId=<" + oplogKeyId + ">"
                    + " drId=" + drId + " userBits="
                    + userBits + " oplogOffset=" + oplogOffset + " valueLen="
                    + valueLength);
          }
          // logger.info(LocalizedStrings.DEBUG,
          // "DEBUG: recover krf skipping oplogKeyId=" + oplogKeyId);
          this.stats.incRecoveryRecordsSkipped();
          incSkipped();
        } else {
          if (EntryBits.isAnyInvalid(userBits)) {
            if (EntryBits.isInvalid(userBits)) {
              valueBytes = DiskEntry.INVALID_BYTES;
            } else {
              valueBytes = DiskEntry.LOCAL_INVALID_BYTES;
            }
          } else if (EntryBits.isTombstone(userBits)) {
            valueBytes = DiskEntry.TOMBSTONE_BYTES;
          }
          Object key = deserializeKey(keyBytes, version, in);
          // logger.info(LocalizedStrings.DEBUG, "DEBUG: recover krf key=" + key
          // + " id=" + oplogKeyId);
          /*
          {
            Object oldValue = getRecoveryMap().put(oplogKeyId, key);
            if (oldValue != null) {
              throw new AssertionError(LocalizedStrings.Oplog_DUPLICATE_CREATE
                  .toLocalizedString(oplogKeyId));
            }
          }
          */
          DiskEntry de = drs.getDiskEntry(key);
          if (de == null) {
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG,
                  "TRACE_RECOVERY readNewEntry oplogKeyId=<" + oplogKeyId + ">"
                      + " drId=" + drId + " key=<" + key + ">" + " userBits="
                      + userBits + " oplogOffset=" + oplogOffset + " valueLen="
                      + valueLength);
              // + " kvMapSize=" + getRecoveryMap().size()
              // + " kvMapKeys=" + laToString(getRecoveryMap().keys()));
            }
            DiskEntry.RecoveredEntry re = createRecoveredEntry(valueBytes,
                valueLength, userBits, getOplogId(), oplogOffset, oplogKeyId,
                false, version, in);
            if (tag != null) {
              re.setVersionTag(tag);
            }
            if (lastModifiedTime != 0) {
              re.setLastModifiedTime(lastModifiedTime);
            }
            de = initRecoveredEntry(drs.getDiskRegionView(), drs
                .initializeRecoveredEntry(key, re));
            drs.getDiskRegionView().incRecoveredEntryCount();
            if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
              drs.getDiskRegionView().incInvalidOrTombstoneEntryCount();
            }
            this.stats.incRecoveredEntryCreates();
            krfEntryCount++;
          } else {
            DiskId curdid = de.getDiskId();
            //assert curdid.getOplogId() != getOplogId();
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG,
                  "TRACE_RECOVERY ignore readNewEntry because getOplogId()="
                      + getOplogId() + " != curdid.getOplogId()="
                      + curdid.getOplogId() + " for drId=" + drId + " key="
                      + key);
            }
          }
          Object oldEntry = getRecoveryMap().put(oplogKeyId, de);
          if (oldEntry != null) {
            throw new AssertionError(LocalizedStrings.Oplog_DUPLICATE_CREATE
                .toLocalizedString(oplogKeyId));
          }
        }
        keyBytes = DataSerializer.readByteArray(dis);
      } // while
      setRecoverNewEntryId(oplogKeyIdHWM);
    } catch (IOException ex) {
      try {
        if (dis != null) {
          dis.close();
          dis = null;
        }
        fis.close();
        fis = null;
      } catch (IOException ignore) {
      }
      throw new DiskAccessException(
          "Unable to recover from krf file for oplogId="
              + oplogId
              + ", file="
              + f.getName()
              + ". This file is corrupt, but may be safely deleted.",
          ex, getParent());
    }
    if (recoverValues && krfEntryCount > 0) {
      oplogsNeedingValueRecovery.add(this);
      // TODO optimize this code and make it async
      // It should also honor the lru limit
      // The fault in logic might not work until
      // the region is actually created.
      // Instead of reading the crf it might be better to iterate the live entry
      // list that was built during KRF recovery. Just fault values in until we
      // hit the LRU limit (if we have one). Only fault in values for entries
      // recovered from disk that are still in this oplog.
      // Defer faulting in values until all oplogs for the ds have been
      // recovered.
    }
    } finally {
      // fix for bug 42776
      if (dis != null) {
        dis.close();
        dis = null;
      }
      if (fis != null) {
        try {
          fis.close();
          fis = null;
        } catch (IOException ignore) {
        }
      }
    }
    return true;
  }
  /**
   * Return number of bytes read
   */
  private long readCrf(OplogEntryIdSet deletedIds,
                       boolean recoverValues, boolean latestOplog) {
    this.recoverNewEntryId = DiskStoreImpl.INVALID_ID;
    this.recoverModEntryId = DiskStoreImpl.INVALID_ID;
    this.recoverModEntryIdHWM = DiskStoreImpl.INVALID_ID;
    boolean readLastRecord = true;
    CountingDataInputStream dis = null;
    try {
      final LocalRegion currentRegion = LocalRegion.getInitializingRegion();
      final boolean keyRequiresRegionContext = currentRegion != null
          ? currentRegion.keyRequiresRegionContext() : false;
      final Version version = getProductVersionIfOld();
      final ByteArrayDataInput in = new ByteArrayDataInput();
      final HeapDataOutputStream hdos = new HeapDataOutputStream(Version.CURRENT);
      final long currentTime = getParent().getCache().cacheTimeMillis();
      int recordCount = 0;
      boolean foundDiskStoreRecord = false;
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(this.crf.f);
        dis = new CountingDataInputStream(new BufferedInputStream(fis,
            LARGE_BUFFER_SIZE), this.crf.f.length());
        boolean endOfLog = false;
        while (!endOfLog) {
          // long startPosition = byteCount;
          if (dis.atEndOfFile()) {
            endOfLog = true;
            break;
          }
          readLastRecord = false;
          byte opCode = dis.readByte();
          if (DiskStoreImpl.TRACE_RECOVERY) {
            logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY Oplog opCode=" + opCode);
          }
          switch (opCode) {
          case OPLOG_EOF_ID:
            // we are at the end of the oplog. So we need to back up one byte
            dis.decrementCount();
            endOfLog = true;
            break;
          case OPLOG_CONFLICT_VERSION:
            this.readVersionTagOnlyEntry(dis, opCode);
            break;
          case OPLOG_NEW_ENTRY_BASE_ID:
            {
              long newEntryBase = dis.readLong();
              if (DiskStoreImpl.TRACE_RECOVERY) {
                logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY newEntryBase=" + newEntryBase);
              }
              readEndOfRecord(dis);
              setRecoverNewEntryId(newEntryBase);
              recordCount++;
            }
            break;
          case OPLOG_NEW_ENTRY_0ID:
            readNewEntry(dis, opCode, deletedIds, recoverValues,
                currentRegion, keyRequiresRegionContext, version, in, hdos,
                currentTime);
            recordCount++;
            break;
          case OPLOG_MOD_ENTRY_1ID:
          case OPLOG_MOD_ENTRY_2ID:
          case OPLOG_MOD_ENTRY_3ID:
          case OPLOG_MOD_ENTRY_4ID:
          case OPLOG_MOD_ENTRY_5ID:
          case OPLOG_MOD_ENTRY_6ID:
          case OPLOG_MOD_ENTRY_7ID:
          case OPLOG_MOD_ENTRY_8ID:
            readModifyEntry(dis, opCode, deletedIds, recoverValues, 
                currentRegion, keyRequiresRegionContext, version, in, hdos,
                currentTime);
            recordCount++;
            break;
          case OPLOG_MOD_ENTRY_WITH_KEY_1ID:
          case OPLOG_MOD_ENTRY_WITH_KEY_2ID:
          case OPLOG_MOD_ENTRY_WITH_KEY_3ID:
          case OPLOG_MOD_ENTRY_WITH_KEY_4ID:
          case OPLOG_MOD_ENTRY_WITH_KEY_5ID:
          case OPLOG_MOD_ENTRY_WITH_KEY_6ID:
          case OPLOG_MOD_ENTRY_WITH_KEY_7ID:
          case OPLOG_MOD_ENTRY_WITH_KEY_8ID:
            readModifyEntryWithKey(dis, opCode, deletedIds, recoverValues,
                currentRegion, keyRequiresRegionContext, version, in, hdos,
                currentTime);
            recordCount++;
            break;

          case OPLOG_DISK_STORE_ID:
            readDiskStoreRecord(dis, this.crf.f);
            foundDiskStoreRecord = true;
            recordCount++;
            break;
          case OPLOG_GEMFIRE_VERSION:
            readGemfireVersionRecord(dis, this.crf.f);
            recordCount++;
            break;
          case OPLOG_RVV:
            readRVVRecord(dis, this.drf.f, false, latestOplog);
            recordCount++;
            break;
          default:
            throw new DiskAccessException(LocalizedStrings.Oplog_UNKNOWN_OPCODE_0_FOUND_IN_DISK_OPERATION_LOG.toLocalizedString(opCode), getParent());
          }
          readLastRecord = true;
          // @todo
//           if (rgn.isDestroyed()) {
//             break;
//           }
        } // while
      }
      finally {
        if (dis != null) {
          dis.close();
        }
        if (fis != null) {
          fis.close();
        }
      }
      if (!foundDiskStoreRecord && recordCount > 0) {
        throw new DiskAccessException("The oplog file \""
                                      + this.crf.f
                                      + "\" does not belong to the init file \""
                                      + getParent().getInitFile() + "\". Crf did not contain a disk store id.",
                                      getParent());
      }
    }
    catch (EOFException ex) {
      // ignore since a partial record write can be caused by a crash
//       if (byteCount < fileLength) {
//         throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_READING_FILE_DURING_RECOVERY_FROM_0
//             .toLocalizedString(this.crf.f.getPath()), ex, getParent());
//       }// else do nothing, this is expected in crash scenarios
    }
    catch (IOException ex) {
      getParent().getCancelCriterion().checkCancelInProgress(ex);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_READING_FILE_DURING_RECOVERY_FROM_0
          .toLocalizedString(this.crf.f.getPath()), ex, getParent());
    }
    catch (CancelException ignore) {
      if (logger.fineEnabled()) {
        logger.fine("Oplog::readOplog:Error in recovery as Cache was closed",
            ignore);
      }
    }
    catch (RegionDestroyedException ignore) {
      if (logger.fineEnabled()) {
        logger.fine(
            "Oplog::readOplog:Error in recovery as Region was destroyed",
            ignore);
      }
    }
    catch (IllegalStateException ex) {
      // @todo
//       if (!rgn.isClosed()) {
        throw ex;
//       }
    }

    //Add the Oplog size to the Directory Holder which owns this oplog,
    // so that available space is correctly calculated & stats updated.
    long byteCount = 0;
    if (!readLastRecord) {
      // this means that there was a crash
      // and hence we should not continue to read
      // the next oplog
      this.crashed = true;
      if (dis != null) {
        byteCount = dis.getFileLength();
      }
    } else {
      if (dis != null) {
        byteCount = dis.getCount();
      }
    }
    return byteCount;
  }

  /**
   * @throws DiskAccessException if this file does not belong to our parent
   */
  private void readDiskStoreRecord(DataInput dis, File f)
    throws IOException
  { long leastSigBits = dis.readLong();
    long mostSigBits = dis.readLong();
    DiskStoreID readDSID = new DiskStoreID(mostSigBits, leastSigBits);
    if (DiskStoreImpl.TRACE_RECOVERY) {
      logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY diskStoreId=" + readDSID);
    }
    readEndOfRecord(dis);
    DiskStoreID dsid = getParent().getDiskStoreID();
    if (!readDSID.equals(dsid)) {
      throw new DiskAccessException("The oplog file \""
                                    + f
                                    + "\" does not belong to the init file \""
                                    + getParent().getInitFile() + "\".",
                                    getParent());
    }
  }

  /**
   * @throws DiskAccessException if this file does not belong to our parent
   */
  private void readGemfireVersionRecord(DataInput dis, File f)
  throws IOException
  {
    Version recoveredGFVersion = readProductVersionRecord(dis, f);
    final boolean hasDataVersion;
    if ((hasDataVersion = (recoveredGFVersion == Version.TOKEN))) {
      // actual GFE version will be the next record in this case
      byte opCode = dis.readByte();
      if (opCode != OPLOG_GEMFIRE_VERSION) {
        throw new DiskAccessException(LocalizedStrings
            .Oplog_UNKNOWN_OPCODE_0_FOUND_IN_DISK_OPERATION_LOG
            .toLocalizedString(opCode), getParent());
      }
      recoveredGFVersion = readProductVersionRecord(dis, f);
    }
    if (this.gfversion == null) {
      this.gfversion = recoveredGFVersion;      
    } else {
      assert this.gfversion == recoveredGFVersion;
    }
    if (hasDataVersion) {
      byte opCode = dis.readByte();
      if (opCode != OPLOG_GEMFIRE_VERSION) {
        throw new DiskAccessException(LocalizedStrings
            .Oplog_UNKNOWN_OPCODE_0_FOUND_IN_DISK_OPERATION_LOG
            .toLocalizedString(opCode), getParent());
      }
      recoveredGFVersion = readProductVersionRecord(dis, f);
      if (this.dataVersion == null) {
        this.dataVersion = recoveredGFVersion;
      }
      else {
        assert this.dataVersion == recoveredGFVersion;
      }
    }
  }

  private Version readProductVersionRecord(DataInput dis, File f)
      throws IOException {
    Version recoveredGFVersion;
    short ver = Version.readOrdinal(dis);
    try {
      recoveredGFVersion = Version.fromOrdinal(ver, false);
    } catch (UnsupportedGFXDVersionException e) {
      throw new DiskAccessException(LocalizedStrings
          .Oplog_UNEXPECTED_PRODUCT_VERSION_0.toLocalizedString(ver), e,
          getParent());
    }
    if (DiskStoreImpl.TRACE_RECOVERY) {
      logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY version=" + recoveredGFVersion);
    }
    readEndOfRecord(dis);
    return recoveredGFVersion;
  }

  private void readTotalCountRecord(DataInput dis, File f)
      throws IOException
    { 
      long recoveredCount = InternalDataSerializer.readUnsignedVL(dis);
      this.totalCount.set(recoveredCount);
    
      if (DiskStoreImpl.TRACE_RECOVERY) {
        logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY totalCount=" + totalCount);
      }
      readEndOfRecord(dis);
    }
  
  private void readRVVRecord(DataInput dis, File f, boolean gcRVV, boolean latestOplog)
      throws IOException
  { 
    long numRegions = InternalDataSerializer.readUnsignedVL(dis);
    if (DiskStoreImpl.TRACE_RECOVERY) {
      logger.info(LocalizedStrings.DEBUG,
          "TRACE_RECOVERY readRVV entry numRegions=" + numRegions);
    }
    for(int region =0; region < numRegions; region++) {
      long drId = InternalDataSerializer.readUnsignedVL(dis);
      //Get the drs. This may be null if this region is not currently recovering
      DiskRecoveryStore drs = getOplogSet().getCurrentlyRecovering(drId);
      if (drs instanceof AbstractRegion
          && !((AbstractRegion)drs).getConcurrencyChecksEnabled()) {
        drs = null;
      }
      if (DiskStoreImpl.TRACE_RECOVERY) {
        logger.info(LocalizedStrings.DEBUG,
            "TRACE_RECOVERY readRVV drId=" + drId + " region=" + drs);
      }

      if(gcRVV) {

        //Read the GCC RV
        long rvvSize = InternalDataSerializer.readUnsignedVL(dis);
        for(int memberNum = 0; memberNum < rvvSize; memberNum++) {
          //for each member, read the member id and version
          long memberId = InternalDataSerializer.readUnsignedVL(dis);
          long gcVersion = InternalDataSerializer.readUnsignedVL(dis);
          
          //if we have a recovery store, add the recovered regions
          if(drs != null) {
            Object member = getParent().getDiskInitFile().getCanonicalObject((int)memberId);
            drs.recordRecoveredGCVersion((VersionSource) member, gcVersion);
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG,
                  "TRACE_RECOVERY adding gcRVV entry drId=" + drId + ",member="
                      + memberId + ",version=" + gcVersion);
            }
          } else {
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG,
                  "TRACE_RECOVERY skipping gcRVV entry drId=" + drId + ",member="
                      + memberId + ",version=" + gcVersion);
            }
          }
        }
      } else {
        boolean rvvTrusted = InternalDataSerializer.readBoolean(dis);
        if(drs != null) {
          if (latestOplog) {
            // only set rvvtrust based on the newest oplog recovered 
            drs.setRVVTrusted(rvvTrusted);
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG,
                  "TRACE_RECOVERY marking RVV trusted drId=" + drId + ",rvvTrusted="
                      + rvvTrusted);
            }
          }
        }
        //Read a regular RVV
        long rvvSize = InternalDataSerializer.readUnsignedVL(dis);
        for(int memberNum = 0; memberNum < rvvSize; memberNum++) {

          //for each member, read the member id and version
          long memberId = InternalDataSerializer.readUnsignedVL(dis);
          RegionVersionHolder versionHolder = new RegionVersionHolder(dis);
          if(drs != null) {
            Object member = getParent().getDiskInitFile().getCanonicalObject((int)memberId);
            drs.recordRecoveredVersonHolder((VersionSource) member, versionHolder, latestOplog);
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG,
                  "TRACE_RECOVERY adding RVV entry drId=" + drId + ",member="
                      + memberId + ",versionHolder=" + versionHolder+",latestOplog="+latestOplog+",oplogId="+getOplogId());
            }
          } else {
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG,
                  "TRACE_RECOVERY skipping RVV entry drId=" + drId + ",member="
                      + memberId + ",versionHolder=" + versionHolder);
            }
          }
        }
      }
    }
    readEndOfRecord(dis);
  }
  
  /**
   * Recovers one oplog
   * @param latestOplog - true if this oplog is the latest oplog in the disk
   * store. 
   */
  long recoverCrf(OplogEntryIdSet deletedIds,
                  boolean recoverValues,
                  boolean recoverValuesSync,
                  boolean alreadyRecoveredOnce, 
                  Set<Oplog> oplogsNeedingValueRecovery, 
                  boolean latestOplog,
                  boolean initialRecovery)
  {
    // crf might not exist; but drf always will
    this.diskFile = new File(this.drf.f.getParentFile(),
                        oplogSet.getPrefix() + getParent().getName() + "_" + this.oplogId);

    File crfFile = this.crf.f;
    if (crfFile == null) {
      this.haveRecoveredCrf = true;
//       logger.info(LocalizedStrings.DEBUG, "DEBUG crfFile=null");
      return 0L;
    }

    lockCompactor();
    this.kvMap = new OplogEntryIdMap();
    this.skippedKeyBytes = new OplogEntryIdMap();
    try {
      // logger.info(LocalizedStrings.DEBUG, "DEBUG haveRecoveredCrf="
      // + this.haveRecoveredCrf + " isDestroyed()=" + isDeleted()
      // + " alreadyRecoveredOnce=" + alreadyRecoveredOnce);
      if (this.haveRecoveredCrf && isDeleted()) return 0; // do this check while holding lock
      if (!this.haveRecoveredCrf) {
        this.haveRecoveredCrf = true;
      }

      long byteCount;
      // if we have a KRF then read it and delay reading the CRF.
      // Unless we are in synchronous recovery mode
      if (!readKrf(deletedIds, recoverValues, recoverValuesSync, oplogsNeedingValueRecovery, latestOplog)) {
        
        //If the data extraction tool is trying to recover keys using 
        // the FORCE_KRF_RECOVERY flag and for any reason the readKrf returns false
        //DO NOT proceed further and read the CRF file.
        if (getParent().FORCE_KRF_RECOVERY && getParent().dataExtractionKrfRecovery) {
          return 0L;
        }
        if (logger.infoEnabled()) {
          logger.info(LocalizedStrings.DiskRegion_RECOVERING_OPLOG_0_1_2,
              new Object[] { toString(), crfFile.getAbsolutePath(),
                  getParent().getName() });
        }
        byteCount = readCrf(deletedIds, recoverValues, latestOplog);
//        if (this.idxBuilder != null) {
//          this.idxBuilder.sortRecords();
//        }
      } else {
        byteCount = this.crf.f.length();
      }
      if (!isPhase2()) {
        if (getParent().isOfflineCompacting()) {
          getParent().incLiveEntryCount(getRecoveryMap().size());
        }
        getParent().incDeadRecordCount(getRecordsSkipped());
      }
      if (getParent().isOfflineCompacting()) {
        offlineCompact(deletedIds, latestOplog);
      }
      if (!alreadyRecoveredOnce) {
        setRecoveredCrfSize(byteCount);
//       {
//         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//         l.info(LocalizedStrings.DEBUG, "crfSize inc=" + byteCount);
//       }
        this.dirHolder.incrementTotalOplogSize(byteCount);
      }
      if (getParent().isOfflineCompacting()) {
        if (isOplogEmpty()) {
          this.deleted.set(false);
          destroy();
        }
      }
      // initialize the kvMap used by index recovery if required; index recovery
      // is now done in IndexRecoveryTask so the condition for copying
      // here should be same as that for creating an IndexRecoveryTask
      else if (!getParent().isOffline()) {
        this.kvInitMap = this.kvMap;
      }
      return byteCount;
    } finally {
      this.kvMap = null;
      this.skippedKeyBytes = null;
      unlockCompactor();
    }
  }

  private boolean offlineCompactPhase2 = false;
  private boolean isPhase1() {
    return !this.offlineCompactPhase2;
  }
  private boolean isPhase2() {
    return this.offlineCompactPhase2;
  }

  private void offlineCompact(OplogEntryIdSet deletedIds, boolean latestOplog) {
    // If we only do this if "(getRecordsSkipped() > 0)" then it will only compact
    // an oplog that has some garbage in it.
    // Instead if we do every oplog in case they set maxOplogSize
    // then  all oplogs will be converted to obey maxOplogSize.
    // 45777: for normal offline compaction, we only do it when getRecordsSkipped() > 0
    // but for upgrade disk store, we have to do it for pure creates oplog
    if (getRecordsSkipped() > 0 || getHasDeletes() || getParent().isUpgradeVersionOnly()) {
      this.offlineCompactPhase2 = true;
      if(getOplogSet().getChild() == null) {
        getOplogSet().initChild();
      }
      readCrf(deletedIds, true, latestOplog);
      this.deleted.set(false);
      destroyCrfOnly();
    } else {
      // For every live entry in this oplog add it to the deleted set
      // so that we will skip it when we recovery the next oplogs.
      for (OplogEntryIdMap.Iterator it = getRecoveryMap().iterator(); it.hasNext();) {
        it.advance();
        deletedIds.add(it.key());
      }
      close();
    }
  }

  private DiskEntry.RecoveredEntry createRecoveredEntry(byte[] valueBytes,
                                                               int valueLength,
                                                               byte userBits,
                                                               long oplogId,
                                                               long offsetInOplog,
                                                               long oplogKeyId,
                                                               boolean recoverValue,
                                                               Version version,
                                                               ByteArrayDataInput in)
  {
    DiskEntry.RecoveredEntry re = null;
    if (recoverValue || EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
      Object value;
      if (EntryBits.isLocalInvalid(userBits)) {
        value = Token.LOCAL_INVALID;
        valueLength = 0;
      }
      else if (EntryBits.isInvalid(userBits)) {
        value = Token.INVALID;
        valueLength = 0;
      }
      else if (EntryBits.isSerialized(userBits)) {
        value = DiskEntry.Helper
            .readSerializedValue(valueBytes, version, in, false);
      }
      else if (EntryBits.isTombstone(userBits)) {
        value = Token.TOMBSTONE;
      }
      else {
        final StaticSystemCallbacks sysCb;
        if (version != null && (sysCb = GemFireCacheImpl
            .getInternalProductCallbacks()) != null) {
          // may need to change serialized shape for GemFireXD
          value = sysCb
              .fromVersion(valueBytes, valueLength, false, version, in);
        }
        else {
          value = valueBytes;
        }
      }
      re = new DiskEntry.RecoveredEntry(oplogKeyId, oplogId, offsetInOplog,
                                        userBits, valueLength, value);
    }
    else {
      re = new DiskEntry.RecoveredEntry(oplogKeyId, oplogId, offsetInOplog,
                                        userBits, valueLength);
    }
    return re;
  }

  private void readEndOfRecord(DataInput di) throws IOException {
    int b = di.readByte();
    if (b != END_OF_RECORD_ID) {
      if (b == 0) {
        logger.warning(LocalizedStrings.Oplog_PARTIAL_RECORD);
        
        // this is expected if this is the last record and we died while writing it.
        throw new EOFException("found partial last record");
      } else {
        // Our implementation currently relies on all unwritten bytes having
        // a value of 0. So throw this exception if we find one we didn't expect.
        throw new IllegalStateException("expected end of record (byte=="
                                        + END_OF_RECORD_ID
                                        + ") or zero but found " + b);
      }
    }
  }

  private static void forceSkipBytes(CountingDataInputStream dis, int len) throws IOException {
    int skipped = dis.skipBytes(len);
    while (skipped < len) {
      dis.readByte();
      skipped++;
    }
  }

  private int recordsSkippedDuringRecovery = 0;

  private void incSkipped() {
    this.recordsSkippedDuringRecovery++;
  }
  
  int getRecordsSkipped() {
    return this.recordsSkippedDuringRecovery;
  }
  
  private VersionTag readVersionsFromOplog(DataInput dis) throws IOException {
    if (Version.GFE_70.compareTo(currentRecoveredGFVersion()) <= 0) {
      // this version format is for gemfire 7.0
      // if we have different version format in 7.1, it will be handled in "else if"
      int entryVersion = (int)InternalDataSerializer.readSignedVL(dis);
      long regionVersion = InternalDataSerializer.readUnsignedVL(dis);
      int memberId = (int)InternalDataSerializer.readUnsignedVL(dis);
      Object member = getParent().getDiskInitFile().getCanonicalObject(memberId);
      long timestamp = InternalDataSerializer.readUnsignedVL(dis);
      int dsId = (int) InternalDataSerializer.readSignedVL(dis);
      VersionTag vt = VersionTag.create((VersionSource)member);
      vt.setEntryVersion(entryVersion);
      vt.setRegionVersion(regionVersion);
      vt.setMemberID((VersionSource)member);
      vt.setVersionTimeStamp(timestamp);
      vt.setDistributedSystemId(dsId);
      return vt;
    } else {
      // pre-7.0
      return null;
    }
  }

  private synchronized VersionTag createDummyTag(DiskRecoveryStore drs,
      long currentTime) {
    DiskStoreID member = getParent().getDiskStoreID();
    getParent().getDiskInitFile().getOrCreateCanonicalId(member);
    long regionVersion = drs.getVersionForMember(member);
    VersionTag vt = VersionTag.create(member);
    vt.setEntryVersion(1);
    vt.setRegionVersion(regionVersion+1);
    vt.setMemberID(member);
    vt.setVersionTimeStamp(currentTime);
    vt.setDistributedSystemId(-1);
    return vt;
  }

  /**
   * Reads an oplog entry of type Create
   * 
   * @param dis
   *          DataInputStream from which the oplog is being read
   * @param opcode
   *          byte whether the id is short/int/long
   * @param recoverValue          
   * @throws IOException
   */
  private void readNewEntry(CountingDataInputStream dis,
                            byte opcode,
                            OplogEntryIdSet deletedIds,
                            boolean recoverValue,
                            final LocalRegion currentRegion,
                            boolean keyRequiresRegionContext,
                            Version version,
                            ByteArrayDataInput in,
                            HeapDataOutputStream hdos,
                            final long currentTime)
    throws IOException
  {
      long oplogOffset = -1;
      byte userBits = dis.readByte();
      byte[] objValue = null;
      int valueLength =0;
      long oplogKeyId = incRecoverNewEntryId();
      long drId = DiskInitFile.readDiskRegionID(dis);
      DiskRecoveryStore drs = getOplogSet().getCurrentlyRecovering(drId);
      // read versions
      VersionTag tag = null;
      final boolean withVersions = EntryBits.isWithVersions(userBits);
      long lastModifiedTime = 0L;
      if (withVersions) {
        tag = readVersionsFromOplog(dis);
      } else if (getParent().isUpgradeVersionOnly() && drs != null
          /* Sqlfire 1.1 1099 has no version tags */
          && !Version.CURRENT.equals(Version.SQLF_1099)
          && !Version.CURRENT.equals(Version.SQLF_11)) {
        tag = this.createDummyTag(drs, currentTime);
        userBits = EntryBits.setWithVersions(userBits, true);
      }
      // read last modified time for no-versions case
      if (!withVersions) {
        if (EntryBits.isLastModifiedTime(userBits)) {
          lastModifiedTime = InternalDataSerializer.readUnsignedVL(dis);
        }
        else if (tag == null) {
          lastModifiedTime = currentTime;
        }
      }

      OkToSkipResult skipResult = okToSkipModifyRecord(deletedIds, drId, drs,
        oplogKeyId, true, tag);
      if (skipResult.skip()) {
        if (!isPhase2()) {
          this.stats.incRecoveryRecordsSkipped();
          incSkipped();
        }
      } else if (recoverValue && drs.lruLimitExceeded() && !getParent().isOfflineCompacting()) {
        this.stats.incRecoveredValuesSkippedDueToLRU();
        recoverValue = false;
      }
      CompactionRecord p2cr = null;
      long crOffset;
      if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
        if (EntryBits.isInvalid(userBits)) {
          objValue = DiskEntry.INVALID_BYTES;
        } else if (EntryBits.isTombstone(userBits)) {
          objValue = DiskEntry.TOMBSTONE_BYTES;
        } else {
          objValue = DiskEntry.LOCAL_INVALID_BYTES;
        }
        crOffset = dis.getCount();
        if (!skipResult.skip()) {
          if (isPhase2()) {
            p2cr = (CompactionRecord)getRecoveryMap().get(oplogKeyId);
            if (p2cr.getOffset() != crOffset) {
              skipResult = OkToSkipResult.SKIP_RECORD;
            }
          }
        }
      } 
      else {
        int len = dis.readInt();
        oplogOffset = dis.getCount();
        crOffset = oplogOffset;
        valueLength = len;
        if (!skipResult.skip()) {
          if (isPhase2()) {
            p2cr = (CompactionRecord)getRecoveryMap().get(oplogKeyId);
            if (p2cr.getOffset() != crOffset) {
              skipResult = OkToSkipResult.SKIP_RECORD;
            }
          }
        }
        if (recoverValue && !skipResult.skip()) {
          byte[] valueBytes = new byte[len];
          dis.readFully(valueBytes);
          objValue = valueBytes;
          validateValue(valueBytes, userBits, version, in);
        } else {
          forceSkipBytes(dis, len);
        }
      }
      {
        int len = dis.readInt();
        incTotalCount();
        if (skipResult.skip()) {
          if(skipResult.skipKey()) {
            forceSkipBytes(dis, len);
          } else {
            byte[] keyBytes = new byte[len];
            dis.readFully(keyBytes);
            skippedKeyBytes.put(oplogKeyId, keyBytes);
          }
          readEndOfRecord(dis);
          
          if(drs != null && tag != null) {
            //Update the RVV with the new entry
            //This must be done after reading the end of record to make sure
            //we don't have a corrupt record. See bug #45538
            drs.recordRecoveredVersionTag(tag);
          }
          
          if (DiskStoreImpl.TRACE_RECOVERY) {
            logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readNewEntry SKIPPING oplogKeyId=<" + oplogKeyId + ">"
                        + " drId=" + drId
                        + " userBits=" + userBits
                        + " keyLen=" + len
                        + " valueLen=" + valueLength
                        + " tag=" + tag
                        );
          }
        } else {
          byte[] keyBytes = null;
          if (isPhase2()) {
            forceSkipBytes(dis, len);
          } else {
            keyBytes = new byte[len];
            dis.readFully(keyBytes);
          }
          readEndOfRecord(dis);
          
          if(drs != null && tag != null) {
            //Update the RVV with the new entry
            //This must be done after reading the end of record to make sure
            //we don't have a corrupt record. See bug #45538
            drs.recordRecoveredVersionTag(tag);
          }
          if (getParent().isOfflineCompacting()) {
            if (isPhase1()) {
              CompactionRecord cr = new CompactionRecord(keyBytes, crOffset);
              getRecoveryMap().put(oplogKeyId, cr);
              drs.getDiskRegionView().incRecoveredEntryCount();
              if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
                drs.getDiskRegionView().incInvalidOrTombstoneEntryCount();
              }
              this.stats.incRecoveredEntryCreates();
            } else { // phase2
              assert p2cr != null;
              // may need to change the key/value bytes for GemFireXD
              keyBytes = p2cr.getKeyBytes();
              if (version != null && !Version.CURRENT.equals(version)) {
                final StaticSystemCallbacks sysCb = GemFireCacheImpl
                    .getInternalProductCallbacks();
                if (sysCb != null) {
                  keyBytes = sysCb.fromVersionToBytes(keyBytes, keyBytes.length,
                      true, version, in, hdos);
                  objValue = sysCb.fromVersionToBytes(objValue, objValue.length,
                      EntryBits.isSerialized(userBits), version, in, hdos);
                }
              }
              getOplogSet().getChild().copyForwardForOfflineCompact(oplogKeyId,
                  keyBytes, objValue, userBits, drId, tag, lastModifiedTime,
                  currentTime);
              if (DiskStoreImpl.TRACE_RECOVERY) {
                logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readNewEntry copyForward oplogKeyId=<" + oplogKeyId + ">");
              }
              // add it to the deletedIds set so we will ignore it in earlier oplogs
              deletedIds.add(oplogKeyId);
            }
          } else {
            Object key = deserializeKey(keyBytes, version, in);
            if (keyRequiresRegionContext) {
              ((KeyWithRegionContext)key).setRegionContext(currentRegion);
            }
            /*
            {
              Object oldValue = getRecoveryMap().put(oplogKeyId, key);
              if (oldValue != null) {
                throw new AssertionError(LocalizedStrings.Oplog_DUPLICATE_CREATE.toLocalizedString(oplogKeyId));
              }
            }
            */
            DiskEntry de = drs.getDiskEntry(key);
            if (de == null) {
              if (DiskStoreImpl.TRACE_RECOVERY) {
                logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readNewEntry oplogKeyId=<" + oplogKeyId + ">"
                            + " drId=" + drId
                            + " key=<"+ key + ">"
                            + " userBits=" + userBits
                            + " oplogOffset=" + oplogOffset
                            + " valueLen=" + valueLength
                            + " tag=" + tag
                            );
                //                         + " kvMapSize=" + getRecoveryMap().size()
                //                         + " kvMapKeys=" + laToString(getRecoveryMap().keys()));
              }
            DiskEntry.RecoveredEntry re = createRecoveredEntry(objValue,
                valueLength, userBits, getOplogId(), oplogOffset, oplogKeyId,
                recoverValue, version, in);
              if (tag != null) {
                re.setVersionTag(tag);
              }
              if (lastModifiedTime != 0) {
                re.setLastModifiedTime(lastModifiedTime);
              }
              de = initRecoveredEntry(drs.getDiskRegionView(), drs.initializeRecoveredEntry(key, re));
              drs.getDiskRegionView().incRecoveredEntryCount();
              if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
                drs.getDiskRegionView().incInvalidOrTombstoneEntryCount();
              }
              this.stats.incRecoveredEntryCreates();
              
            } else {
              DiskId curdid = de.getDiskId();
              if (DiskStoreImpl.TRACE_RECOVERY) {
                logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY ignore readNewEntry because getOplogId()="
                    + getOplogId()
                    + " != curdid.getOplogId()=" + curdid.getOplogId()
                    + " oplogKeyId=<" + oplogKeyId + ">"
                    + " for drId=" + drId
                    + " tag=" + tag
                    + " key=" + key);
              }
              assert curdid.getOplogId() != getOplogId();
            }
            Object oldEntry = getRecoveryMap().put(oplogKeyId, de);
            if (oldEntry != null) {
              throw new AssertionError(
                  LocalizedStrings.Oplog_DUPLICATE_CREATE
                      .toLocalizedString(oplogKeyId));
            }
          }
        }        
      }
  }

  /**
   * Reads an oplog entry of type Modify
   * 
   * @param dis
   *          DataInputStream from which the oplog is being read
   * @param opcode
   *          byte whether the id is short/int/long
   * @param recoverValue
   * @param currentRegion 
   * @param keyRequiresRegionContext 
   * @throws IOException
   */
  private void readModifyEntry(CountingDataInputStream dis,
                               byte opcode,
                               OplogEntryIdSet deletedIds,
                               boolean recoverValue, 
                               LocalRegion currentRegion, 
                               boolean keyRequiresRegionContext,
                               Version version,
                               ByteArrayDataInput in,
                               HeapDataOutputStream hdos,
                               final long currentTime)
      throws IOException
  {
      long oplogOffset = -1;
      byte userBits = dis.readByte();

      int idByteCount = (opcode - OPLOG_MOD_ENTRY_1ID) + 1;
//       long debugRecoverModEntryId = this.recoverModEntryId;
      long oplogKeyId = getModEntryId(dis, idByteCount);
//       long debugOplogKeyId = dis.readLong();
//       //assert oplogKeyId == debugOplogKeyId
//       //        : "expected=" + debugOplogKeyId + " actual=" + oplogKeyId
//       assert debugRecoverModEntryId == debugOplogKeyId
//         : "expected=" + debugOplogKeyId + " actual=" + debugRecoverModEntryId
//         + " idByteCount=" + idByteCount
//         + " delta=" + this.lastDelta;
      long drId = DiskInitFile.readDiskRegionID(dis);
      DiskRecoveryStore drs = getOplogSet().getCurrentlyRecovering(drId);
      // read versions
      VersionTag tag = null;
      final boolean withVersions = EntryBits.isWithVersions(userBits);
      long lastModifiedTime = 0L;
      if (withVersions) {
        tag = readVersionsFromOplog(dis);
      } else if (getParent().isUpgradeVersionOnly() && drs != null
          /* Sqlfire 1.1 and 1099 has no version tags */
          && !Version.CURRENT.equals(Version.SQLF_1099)
          && !Version.CURRENT.equals(Version.SQLF_11)) {
        tag = this.createDummyTag(drs, currentTime);
        userBits = EntryBits.setWithVersions(userBits, true);
      }
      // read last modified time for no-versions case
      if (!withVersions) {
        if (EntryBits.isLastModifiedTime(userBits)) {
          lastModifiedTime = InternalDataSerializer.readUnsignedVL(dis);
        }
        else if (tag == null) {
          lastModifiedTime = currentTime;
        }
      }
      OkToSkipResult skipResult = okToSkipModifyRecord(deletedIds, drId, drs,
        oplogKeyId, false, tag);
      if (skipResult.skip()) {
        if (!isPhase2()) {
          incSkipped();
          this.stats.incRecoveryRecordsSkipped();
        }
      } else if (recoverValue && drs.lruLimitExceeded() && !getParent().isOfflineCompacting()) {
        this.stats.incRecoveredValuesSkippedDueToLRU();
        recoverValue = false;
      }

      byte[] objValue = null;
      int valueLength = 0;
      CompactionRecord p2cr = null;
      long crOffset;
      if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
        if (EntryBits.isInvalid(userBits)) {
          objValue = DiskEntry.INVALID_BYTES;
        } else if (EntryBits.isTombstone(userBits)) {
          objValue = DiskEntry.TOMBSTONE_BYTES;
        } else {
          objValue = DiskEntry.LOCAL_INVALID_BYTES;
        }
        crOffset = dis.getCount();
        if (!skipResult.skip()) {
          if (isPhase2()) {
            p2cr = (CompactionRecord)getRecoveryMap().get(oplogKeyId);
            if (p2cr.getOffset() != crOffset) {
              skipResult = OkToSkipResult.SKIP_RECORD;
            }
          }
        }
      }
      else {
        int len = dis.readInt();
        oplogOffset = dis.getCount();
        crOffset = oplogOffset;
        valueLength = len;
        if (!skipResult.skip()) {
          if (isPhase2()) {
            p2cr = (CompactionRecord)getRecoveryMap().get(oplogKeyId);
            if (p2cr.getOffset() != crOffset) {
              skipResult = OkToSkipResult.SKIP_RECORD;
            }
          }
        }
        if (!skipResult.skip() && recoverValue) {
          byte[] valueBytes = new byte[len];
          dis.readFully(valueBytes);
          objValue = valueBytes;
          validateValue(valueBytes, userBits, version, in);
        } else {
          forceSkipBytes(dis, len);
        }
      }
      readEndOfRecord(dis);
      
      if(drs != null && tag != null) {
        //Update the RVV with the new entry
        //This must be done after reading the end of record to make sure
        //we don't have a corrupt record. See bug #45538
        drs.recordRecoveredVersionTag(tag);
      }

      incTotalCount();
      if (!skipResult.skip()) {
        Object key = null;
        Object entry = getRecoveryMap().get(oplogKeyId);
        DiskEntry de = null;
        byte[] keyBytes = null;
        //if the key is not in the recover map, it's possible it
        //was previously skipped. Check the skipped bytes map for the key.
        if(entry == null) {
          keyBytes = (byte[]) skippedKeyBytes.get(oplogKeyId);
          if (keyBytes != null) {
            key = deserializeKey(keyBytes, version, in);
            if (keyRequiresRegionContext) {
              ((KeyWithRegionContext)key).setRegionContext(currentRegion);
            }
          }
        }
        else if (entry instanceof DiskEntry) {
          de = (DiskEntry)entry;
          key = de.getKeyCopy();
        }
        else {
          key = entry;
        }
          if (DiskStoreImpl.TRACE_RECOVERY) {
            logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readModifyEntry oplogKeyId=<" + oplogKeyId + ">"
                        + "drId=" + drId
                        + " key=<" + key
                        + "> userBits=" + userBits
                        + " oplogOffset=" + oplogOffset
                        + " tag=" + tag
                        + " valueLen=" + valueLength);
//                      + " kvMapSize=" + getRecoveryMap().size());
          }

        // Will no longer be null since 1st modify record in any oplog
        // will now be a MOD_ENTRY_WITH_KEY record.
        assert key != null;
      
        if (getParent().isOfflineCompacting()) {
          if (isPhase1()) {
            CompactionRecord cr = (CompactionRecord)key;
            incSkipped(); // we are going to compact the previous record away
            cr.update(crOffset);
          } else { // phase2
            assert p2cr != null;
            // may need to change the key/value bytes for GemFireXD
            keyBytes = p2cr.getKeyBytes();
            if (version != null && !Version.CURRENT.equals(version)) {
              final StaticSystemCallbacks sysCb = GemFireCacheImpl
                  .getInternalProductCallbacks();
              if (sysCb != null) {
                keyBytes = sysCb.fromVersionToBytes(keyBytes, keyBytes.length,
                    true, version, in, hdos);
                objValue = sysCb.fromVersionToBytes(objValue, objValue.length,
                    EntryBits.isSerialized(userBits), version, in, hdos);
              }
            }
            getOplogSet().getChild().copyForwardForOfflineCompact(oplogKeyId,
                keyBytes, objValue, userBits, drId, tag, lastModifiedTime,
                currentTime);
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readModifyEntry copyForward oplogKeyId=<" + oplogKeyId + ">");
            }
            // add it to the deletedIds set so we will ignore it in earlier oplogs
            deletedIds.add(oplogKeyId);
          }
        } else {
       // Check the actual region to see if it has this key from
          // a previous recovered oplog.
          if (de == null && key != null) {
            de = drs.getDiskEntry(key);
          }
          //This may actually be create, if the previous create or modify
          //of this entry was cleared through the RVV clear.
          if (de == null) {
            DiskRegionView drv = drs.getDiskRegionView();
            // and create an entry
          DiskEntry.RecoveredEntry re = createRecoveredEntry(objValue,
              valueLength, userBits, getOplogId(), oplogOffset, oplogKeyId,
              recoverValue, version, in);
            if (tag != null) {
              re.setVersionTag(tag);
            }
            if (lastModifiedTime != 0) {
              re.setLastModifiedTime(lastModifiedTime);
            }
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readModEntryWK init oplogKeyId=<" + oplogKeyId + ">"
                          + "drId=" + drId
                          + "key=<"+ key + ">"
                          + " oplogOffset=" + oplogOffset
                          + " userBits=" + userBits
                          + " valueLen=" + valueLength
                          + " tag=" + tag
                          );
              //                      + " kvMapSize=" + .getRecoveryMap().size()
              //                      + " kvMapKeys=" + laToString(getRecoveryMap().keys()));
            }
          de = initRecoveredEntry(drv, drs.initializeRecoveredEntry(key, re));
            drs.getDiskRegionView().incRecoveredEntryCount();
            if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
              drs.getDiskRegionView().incInvalidOrTombstoneEntryCount();
            }
            this.stats.incRecoveredEntryCreates();
          } else {
          DiskEntry.RecoveredEntry re = createRecoveredEntry(objValue,
              valueLength, userBits, getOplogId(), oplogOffset, oplogKeyId,
              recoverValue, version, in);
            if (tag != null) {
              re.setVersionTag(tag);
            }

            boolean isOldValueToken = de.getValueAsToken() != null ? true : false;
            de = drs.updateRecoveredEntry(key, de, re);
            if (de != null && de.getValueAsToken() == Token.TOMBSTONE && !isOldValueToken) {
              if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
                drs.getDiskRegionView().incInvalidOrTombstoneEntryCount();
              }
            }
            updateRecoveredEntry(drs.getDiskRegionView(), de, re);
            
            this.stats.incRecoveredEntryUpdates();
            
          }
        }
      } else {
        if (DiskStoreImpl.TRACE_RECOVERY) {
          logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY skipping readModifyEntry oplogKeyId=<" + oplogKeyId + ">" + "drId=" + drId);
        }
      }
  }

  private void readVersionTagOnlyEntry(CountingDataInputStream dis, byte opcode) throws IOException
  {
    long drId = DiskInitFile.readDiskRegionID(dis);
    DiskRecoveryStore drs = getOplogSet().getCurrentlyRecovering(drId);
    // read versions
    VersionTag tag = readVersionsFromOplog(dis);
    if (DiskStoreImpl.TRACE_RECOVERY) {
      logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readVersionTagOnlyEntry "
          +" drId="+drId+" tag="+tag);
    }
    readEndOfRecord(dis);
    
    //Update the RVV with the new entry
    if(drs != null) {
      drs.recordRecoveredVersionTag(tag);
    }
  }

  private void validateValue(byte[] valueBytes, byte userBits, Version version,
      ByteArrayDataInput in) {
    if (getParent().isValidating()) {
      if (EntryBits.isSerialized(userBits)) {
        // make sure values are deserializable
        if (!PdxWriterImpl.isPdx(valueBytes)) { // fix bug 43011
          try {
            DiskEntry.Helper.readSerializedValue(valueBytes, version, in, true);
          } catch (SerializationException ex) {
            logger.warning(LocalizedStrings.DEBUG,
                "Could not deserialize recovered value: " + ex.getCause());
          }
        }
      }
    }
  }

  /**
   * Reads an oplog entry of type ModifyWithKey
   * 
   * @param dis
   *          DataInputStream from which the oplog is being read
   * @param opcode
   *          byte whether the id is short/int/long
   * @param deletedIds         
   * @param recoverValue
   * @throws IOException
   */
  private void readModifyEntryWithKey(CountingDataInputStream dis,
                                      byte opcode,
                                      OplogEntryIdSet deletedIds,
                                      boolean recoverValue,
                                      final LocalRegion currentRegion,
                                      final boolean keyRequiresRegionContext,
                                      Version version,
                                      ByteArrayDataInput in,
                                      HeapDataOutputStream hdos,
                                      final long currentTime)
      throws IOException
  {
      long oplogOffset = -1;

      byte userBits = dis.readByte();

      int idByteCount = (opcode - OPLOG_MOD_ENTRY_WITH_KEY_1ID) + 1;
//       long debugRecoverModEntryId = this.recoverModEntryId;
      long oplogKeyId = getModEntryId(dis, idByteCount);
//       long debugOplogKeyId = dis.readLong();
//       //assert oplogKeyId == debugOplogKeyId
//       //        : "expected=" + debugOplogKeyId + " actual=" + oplogKeyId
//       assert debugRecoverModEntryId == debugOplogKeyId
//         : "expected=" + debugOplogKeyId + " actual=" + debugRecoverModEntryId
//         + " idByteCount=" + idByteCount
//         + " delta=" + this.lastDelta;
      long drId = DiskInitFile.readDiskRegionID(dis);
      DiskRecoveryStore drs = getOplogSet().getCurrentlyRecovering(drId);
      
      // read version
      VersionTag tag = null;
      final boolean withVersions = EntryBits.isWithVersions(userBits);
      long lastModifiedTime = 0L;
      if (withVersions) {
        tag = readVersionsFromOplog(dis);
      } else if (getParent().isUpgradeVersionOnly() && drs != null
          /* Sqlfire 1.1 and 1099 has no version tags */
          && !Version.CURRENT.equals(Version.SQLF_1099)
          && !Version.CURRENT.equals(Version.SQLF_11)) {
        tag = this.createDummyTag(drs, currentTime);
        userBits = EntryBits.setWithVersions(userBits, true);
      }
      // read last modified time for no-versions case
      if (!withVersions) {
        if (EntryBits.isLastModifiedTime(userBits)) {
          lastModifiedTime = InternalDataSerializer.readUnsignedVL(dis);
        }
        else if (tag == null) {
          lastModifiedTime = currentTime;
        }
      }
      OkToSkipResult skipResult = okToSkipModifyRecord(deletedIds, drId, drs,
        oplogKeyId, true, tag);
      if (skipResult.skip()) {
        if (!isPhase2()) {
          incSkipped();
          this.stats.incRecoveryRecordsSkipped();
        }
      } else if (recoverValue && drs.lruLimitExceeded() && !getParent().isOfflineCompacting()) {
        this.stats.incRecoveredValuesSkippedDueToLRU();
        recoverValue = false;
      }

      byte[] objValue = null;
      int valueLength = 0;
      CompactionRecord p2cr = null;
      long crOffset;
      if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
        if (EntryBits.isInvalid(userBits)) {
          objValue = DiskEntry.INVALID_BYTES;
        } else if (EntryBits.isTombstone(userBits)) {
          objValue = DiskEntry.TOMBSTONE_BYTES;
        } else {
          objValue = DiskEntry.LOCAL_INVALID_BYTES;
        }
        crOffset = dis.getCount();
        if (!skipResult.skip()) {
          if (isPhase2()) {
            p2cr = (CompactionRecord)getRecoveryMap().get(oplogKeyId);
            if (p2cr.getOffset() != crOffset) {
              skipResult = OkToSkipResult.SKIP_RECORD;
            }
          }
        }
      }
      else {
        int len = dis.readInt();
        oplogOffset = dis.getCount();
        crOffset = oplogOffset;
        valueLength = len;
        if (!skipResult.skip()) {
          if (isPhase2()) {
            p2cr = (CompactionRecord)getRecoveryMap().get(oplogKeyId);
            if (p2cr.getOffset() != crOffset) {
              skipResult = OkToSkipResult.SKIP_RECORD;
            }
          }
        }
        if (!skipResult.skip() && recoverValue) {
          byte[] valueBytes = new byte[len];
          dis.readFully(valueBytes);
          objValue = valueBytes;
          validateValue(valueBytes, userBits, version, in);
        } else {
          forceSkipBytes(dis, len);
        }
      }

      int keyLen = dis.readInt();

      incTotalCount();
      if (skipResult.skip()) {
        if(skipResult.skipKey()) {
          forceSkipBytes(dis, keyLen);
        } else {
          byte[] keyBytes = new byte[keyLen];
          dis.readFully(keyBytes);
          skippedKeyBytes.put(oplogKeyId, keyBytes);
        }
        readEndOfRecord(dis);
        if (DiskStoreImpl.TRACE_RECOVERY) {
          logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY skipping readModEntryWK init oplogKeyId=<" + oplogKeyId + ">" + "drId=" + drId);
        }
      } else {
        // read the key
        byte[] keyBytes = null;
        if (isPhase2()) {
          forceSkipBytes(dis, keyLen);
        } else {
          keyBytes = new byte[keyLen];
          dis.readFully(keyBytes);
        }
        readEndOfRecord(dis);
        if(drs != null && tag != null) {
          //Update the RVV with the new entry
          //This must be done after reading the end of record to make sure
          //we don't have a corrupt record. See bug #45538
          drs.recordRecoveredVersionTag(tag);
        }
        assert oplogKeyId >= 0;
        if (getParent().isOfflineCompacting()) {
          if (isPhase1()) {
            CompactionRecord cr = new CompactionRecord(keyBytes, crOffset);
            getRecoveryMap().put(oplogKeyId, cr);
            drs.getDiskRegionView().incRecoveredEntryCount();
            if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
              drs.getDiskRegionView().incInvalidOrTombstoneEntryCount();
            }
            this.stats.incRecoveredEntryCreates();
          } else { // phase2
            assert p2cr != null;
            // may need to change the key/value bytes for GemFireXD
            keyBytes = p2cr.getKeyBytes();
            if (version != null && !Version.CURRENT.equals(version)) {
              final StaticSystemCallbacks sysCb = GemFireCacheImpl
                  .getInternalProductCallbacks();
              if (sysCb != null) {
                keyBytes = sysCb.fromVersionToBytes(keyBytes, keyBytes.length,
                    true, version, in, hdos);
                objValue = sysCb.fromVersionToBytes(objValue, objValue.length,
                    EntryBits.isSerialized(userBits), version, in, hdos);
              }
            }
            getOplogSet().getChild().copyForwardForOfflineCompact(oplogKeyId,
                keyBytes, objValue, userBits, drId, tag, lastModifiedTime,
                currentTime);
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readModifyEntryWithKey copyForward oplogKeyId=<" + oplogKeyId + ">");
            }
            // add it to the deletedIds set so we will ignore it in earlier oplogs
            deletedIds.add(oplogKeyId);
          }
        } else {
          Object key = deserializeKey(keyBytes, version, in);
          if (keyRequiresRegionContext) {
            ((KeyWithRegionContext)key).setRegionContext(currentRegion);
          }
          /*
          Object oldValue = getRecoveryMap().put(oplogKeyId, key);
          if (oldValue != null) {
            throw new AssertionError(LocalizedStrings.Oplog_DUPLICATE_CREATE.toLocalizedString(oplogKeyId));
          }
          */
          // Check the actual region to see if it has this key from
          // a previous recovered oplog.
          DiskEntry de = drs.getDiskEntry(key);
          if (de == null) {
            DiskRegionView drv = drs.getDiskRegionView();
            // and create an entry
          DiskEntry.RecoveredEntry re = createRecoveredEntry(objValue,
              valueLength, userBits, getOplogId(), oplogOffset, oplogKeyId,
              recoverValue, version, in);
            if (tag != null) {
              re.setVersionTag(tag);
            }
            if (lastModifiedTime != 0) {
              re.setLastModifiedTime(lastModifiedTime);
            }
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG, "" +
              		" oplogKeyId=<" + oplogKeyId + ">"
                          + "drId=" + drId
                          + "key=<"+ key + ">"
                          + " oplogOffset=" + oplogOffset
                          + " userBits=" + userBits
                          + " valueLen=" + valueLength
                          + " tag=" + tag
                          );
              //                      + " kvMapSize=" + .getRecoveryMap().size()
              //                      + " kvMapKeys=" + laToString(getRecoveryMap().keys()));
            }
            de = drs.initializeRecoveredEntry(key, re);
            initRecoveredEntry(drv, de);
            drs.getDiskRegionView().incRecoveredEntryCount();
            if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
              drs.getDiskRegionView().incInvalidOrTombstoneEntryCount();
            }
            this.stats.incRecoveredEntryCreates();
            
          } else {
            DiskId curdid = de.getDiskId();
            assert curdid.getOplogId() != getOplogId() : "Mutiple ModEntryWK in the same oplog for getOplogId()="
                          + getOplogId()
                          + " , curdid.getOplogId()=" + curdid.getOplogId()
                          + " , for drId=" + drId
                          + " , key=" + key;
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY ignore readModEntryWK because getOplogId()="
                          + getOplogId()
                          + " != curdid.getOplogId()=" + curdid.getOplogId()
                          + " for drId=" + drId
                          + " key=" + key);
            }
            //           if (DiskStoreImpl.TRACE_RECOVERY) {
            //             logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readModEntryWK update oplogKeyId=<" + oplogKeyId + ">"
            //                         + "key=<"+ key + ">"
            //                         + " userBits=" + userBits
            //                         + " valueLen=" + valueLength
            //                         );
            //           }
            //           de = drs.updateRecoveredEntry(key, re);
            //           updateRecoveredEntry(drv, de, re);
            //           this.stats.incRecoveredEntryUpdates();
          }
          Object oldEntry = getRecoveryMap().put(oplogKeyId, de);
          if (oldEntry != null) {
            throw new AssertionError(
                LocalizedStrings.Oplog_DUPLICATE_CREATE
                    .toLocalizedString(oplogKeyId));
          }
        }
      }
  }

  /**
   * Reads an oplog entry of type Delete
   * 
   * @param dis
   *          DataInputStream from which the oplog is being read
   * @param opcode
   *          byte whether the id is short/int/long
   * @param parent instance of disk region          
   * @throws IOException
   */
  private void readDelEntry(CountingDataInputStream dis,
                            byte opcode,
                            OplogEntryIdSet deletedIds,
                            DiskStoreImpl parent)
    throws IOException
                                   
  {
      int idByteCount = (opcode - OPLOG_DEL_ENTRY_1ID) + 1;
//       long debugRecoverDelEntryId = this.recoverDelEntryId;
      long oplogKeyId = getDelEntryId(dis, idByteCount);
//       long debugOplogKeyId = dis.readLong();
      readEndOfRecord(dis);
//       assert debugRecoverDelEntryId == debugOplogKeyId
//         : "expected=" + debugOplogKeyId + " actual=" + debugRecoverDelEntryId
//         + " idByteCount=" + idByteCount
//         + " delta=" + this.lastDelta;
      deletedIds.add(oplogKeyId);
      setHasDeletes(true);
      this.stats.incRecoveredEntryDestroys();
      if (DiskStoreImpl.TRACE_RECOVERY) {
        logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY readDelEntry oplogKeyId=<" + oplogKeyId + ">");
      }
  }


  /**
   * Keeps track of the drId of Regions that have records in this oplog that
   * have not yet been recovered.
   * If this count is > 0 then this oplog can not be compacted.
   */
  private final AtomicInteger unrecoveredRegionCount = new AtomicInteger();
  
  private void addUnrecoveredRegion(Long drId) {
    DiskRegionInfo dri = getOrCreateDRI(drId);
    if (dri.testAndSetUnrecovered()) {
      this.unrecoveredRegionCount.incrementAndGet();
    }
  }

  /**
   * For each dri that this oplog has that is currently unrecoverable check to see
   * if a DiskRegion that is recoverable now exists.
   */
  void checkForRecoverableRegion(DiskRegionView dr) {
    if (this.unrecoveredRegionCount.get() > 0) {
      DiskRegionInfo dri = getDRI(dr);
      if (dri != null) {
        if (dri.testAndSetRecovered(dr)) {
          this.unrecoveredRegionCount.decrementAndGet();
        }
      }
    }
  }
  void updateDiskRegion(DiskRegionView dr) {
    DiskRegionInfo dri = getDRI(dr);
    if (dri != null) {
      dri.setDiskRegion(dr);
    }
  }

  /**
   * Returns true if it is ok the skip the current modify record
   * which had the given oplogEntryId.
   * It is ok to skip if any of the following are true:
   *   1. deletedIds contains the id
   *   2. the last modification of the entry was done by a record read
   *      from an oplog other than this oplog
   * @param tag 
   */
  private OkToSkipResult okToSkipModifyRecord(OplogEntryIdSet deletedIds,
                                       long drId,
                                       DiskRecoveryStore drs,
 long oplogEntryId, boolean checkRecoveryMap, VersionTag tag) {
    if (deletedIds.contains(oplogEntryId)) {
      if (DiskStoreImpl.TRACE_RECOVERY) {
        logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY okToSkip because oplogEntryId="
                    + oplogEntryId + " was deleted for drId=" + drId);
      }
      return OkToSkipResult.SKIP_RECORD;
    }
//     if (dr == null || !dr.isReadyForRecovery()) {
//       // Region has not yet been created (it is not in the diskStore drMap).
//       // or it is not ready for recovery (i.e. it is a ProxyBucketRegion).
//       if (getParent().getDiskInitFile().regionExists(drId)
//           || (dr != null && !dr.isReadyForRecovery())) {
//         // Prevent compactor from removing this oplog.
//         // It needs to be in this state until all the regions stored it in
//         // are recovered.
//         if (DiskStoreImpl.TRACE_RECOVERY) {
//           logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY adding unrecoveredRegion drId=" + drId);
//         }
//         addUnrecoveredRegion(drId);
//       } else {
//         // someone must have deleted the region from the initFile (with our public tool?)
//         // so skip this record and don't count it as live so that the compactor can gc it.
//       }
//       if (DiskStoreImpl.TRACE_RECOVERY) {
//         logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY okToSkip because dr=null drId=" + drId);
//       }
//       return true;
//     } else
    if (drs == null) { // we are not currently recovering this region
      if (DiskStoreImpl.TRACE_RECOVERY) {
        logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY okToSkip because drs is null for drId=" + drId);
      }
      // Now when the diskStore is created we recover all the regions immediately.
      // After that they can close and reopen a region but the close code calls
      // addUnrecoveredRegion. So I think at this point we don't need to do anything.
//       // Prevent compactor from removing this oplog.
//       // It needs to be in this state until all the regions stored it in
//       // are recovered.
//       if (DiskStoreImpl.TRACE_RECOVERY) {
//         logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY adding unrecoveredRegion drId=" + drId);
//       }
//       addUnrecoveredRegion(drId);
      return OkToSkipResult.SKIP_RECORD;
    }
    if (!checkRecoveryMap && !getParent().isOfflineCompacting()) {
    Object entry = getRecoveryMap().get(oplogEntryId);
    if (entry != null) {
      //DiskEntry de = drs.getDiskEntry(key);
      DiskEntry de = (DiskEntry)entry;
      if (de != null) {
        DiskId curdid = de.getDiskId();
        if (curdid != null) {
          if (curdid.getOplogId() != getOplogId()) {
            if (DiskStoreImpl.TRACE_RECOVERY) {
              logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY okToSkip because getOplogId()="
                          + getOplogId()
                          + " != curdid.getOplogId()=" + curdid.getOplogId()
                          + " for drId=" + drId
                          + " key=" + de.getKeyCopy());
            }
            return OkToSkipResult.SKIP_RECORD;
          }
        }
      }
    }
    }
    return okToSkipRegion(drs.getDiskRegionView(), oplogEntryId, tag);
  }
  /**
   * Returns true if the drId region has been destroyed or
   * if oplogKeyId preceeds the last clear done on the drId region
   * @param tag 
   */
  private OkToSkipResult okToSkipRegion(DiskRegionView drv,
                                 long oplogKeyId, VersionTag tag) {
    long lastClearKeyId = drv.getClearOplogEntryId();
    if (lastClearKeyId != DiskStoreImpl.INVALID_ID) {
      if (DiskStoreImpl.TRACE_RECOVERY) {
        logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY lastClearKeyId=" + lastClearKeyId
                    + " oplogKeyId=" + oplogKeyId);
      }
      if (lastClearKeyId >= 0) {
        
        if (oplogKeyId <= lastClearKeyId) {
          if (DiskStoreImpl.TRACE_RECOVERY) {
            logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY okToSkip because oplogKeyId="
                        + oplogKeyId
                        + " <= lastClearKeyId=" + lastClearKeyId
                        + " for drId=" + drv.getId());
          }
          // @todo add some wraparound logic
          return OkToSkipResult.SKIP_RECORD;
        }
      } else {
        // lastClearKeyId is < 0 which means it wrapped around
        // treat it like an unsigned value (-1 == MAX_UNSIGNED)
        if (oplogKeyId > 0
            || oplogKeyId <= lastClearKeyId) {
          // If oplogKeyId > 0 then it happened before the clear
          // (assume clear happened after we wrapped around to negative).
          // If oplogKeyId < 0 then it happened before the clear
          // if it is < lastClearKeyId
          if (DiskStoreImpl.TRACE_RECOVERY) {
            logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY okToSkip because oplogKeyId="
                        + oplogKeyId
                        + " <= lastClearKeyId=" + lastClearKeyId
                        + " for drId=" + drv.getId());
          }
          return OkToSkipResult.SKIP_RECORD;
        }
      }
    }
    RegionVersionVector clearRVV = drv.getClearRVV();
    if(clearRVV != null) {
      if (DiskStoreImpl.TRACE_RECOVERY) {
        logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY clearRVV=" + clearRVV
                    + " tag=" + tag);
      }
      if (clearRVV.contains(tag.getMemberID(), tag.getRegionVersion())) {
        if (DiskStoreImpl.TRACE_RECOVERY) {
          logger.info(LocalizedStrings.DEBUG, "TRACE_RECOVERY okToSkip because tag="
              + tag
              + " <= clearRVV=" + clearRVV
              + " for drId=" + drv.getId());
        }
        //For an RVV clear, we can only skip the value during recovery
        //because later modifies may use the oplog key id.
        return OkToSkipResult.SKIP_VALUE;
      }
    }
    
    return OkToSkipResult.DONT_SKIP;
  }

  private long getModEntryId(CountingDataInputStream dis, int idByteCount)
      throws IOException
  {
    return calcModEntryId(getEntryIdDelta(dis, idByteCount));
  }
  private long getDelEntryId(CountingDataInputStream dis, int idByteCount)
      throws IOException
  {
    return calcDelEntryId(getEntryIdDelta(dis, idByteCount));
  }
  private /*HACK DEBUG*/ static long getEntryIdDelta(CountingDataInputStream dis, int idByteCount)
      throws IOException
  {
    assert idByteCount >= 1 && idByteCount <= 8 : idByteCount;
    
    long delta;
    byte firstByte = dis.readByte();
//     if (firstByte < 0) {
//       delta = 0xFFFFFFFFFFFFFF00L | firstByte;
//     } else {
//       delta = firstByte;
//     }
    delta = firstByte;
    idByteCount--;
    while (idByteCount > 0) {
      delta <<= 8;
      delta |= (0x00FF & dis.readByte());
      idByteCount--;
    }
//     this.lastDelta = delta; // HACK DEBUG
    return delta;
  }

//    private long lastDelta;  // HACK DEBUG

  /**
   * Call this when the cache is closed or region is destroyed.
   * Deletes the lock files.
   */
  public void close()
  {
    if (this.closed) {
      return;
    }
    if( logger.fineEnabled()){
      logger.fine("Oplog::close: Store name ="+ parent.getName() + " Oplog ID = "+oplogId);
    }

    basicClose(false);
  }

  /**
   * Close the files of a oplog but don't set any state. Used by unit tests
   */
  public void testClose() {
    try {
      this.crf.outputStream.closeChannel();
    } catch (IOException ignore) {
    }
    try {
      this.crf.raf.close();
    } catch (IOException ignore) {
    }
    this.crf.RAFClosed = true;
    try {
      this.drf.outputStream.closeChannel();
    } catch (IOException ignore) {
    }
    try {
      this.drf.raf.close();
    } catch (IOException ignore) {
    }
    this.drf.RAFClosed = true;
  }
  
  private void basicClose(boolean forceDelete) {
    flushAll();
    synchronized (this.lock/*crf*/) {
      unpreblow(this.crf, getMaxCrfSize());
//       logger.info(LocalizedStrings.DEBUG, "DEBUG closing oplog#" + getOplogId()
//                   + " liveCount=" + this.totalLiveCount.get(),
//                   new RuntimeException("STACK"));

      if (!this.crf.RAFClosed) {
        final OplogFile.FileChannelOutputStream stream = this.crf.outputStream;
        try {
          if (stream != null) {
            stream.closeChannel();
          } else {
            this.crf.channel.close();
          }
        } catch (IOException ignore) {
        }
        try {
          this.crf.raf.close();
        } catch (IOException ignore) {
        }
        this.crf.RAFClosed = true;
        this.stats.decOpenOplogs();
      }
      this.closed = true;
    }
    synchronized (this.lock/*drf*/) {
      unpreblow(this.drf, getMaxDrfSize());
      if (!this.drf.RAFClosed) {
        final OplogFile.FileChannelOutputStream stream = this.drf.outputStream;
        try {
          if (stream != null) {
            stream.closeChannel();
          } else {
            this.drf.channel.close();
          }
        } catch (IOException ignore) {
        }
        try {
          this.drf.raf.close();
        } catch (IOException ignore) {
        }
        this.drf.RAFClosed = true;
      }
    }
    
    if (forceDelete) {
      this.deleteFiles(false);
    }
  }

  /**
   * Used by tests to confirm that an oplog was compacted
   */
  boolean testConfirmCompacted() {
    return this.closed && this.deleted.get()
      && getOplogSize() == 0;
  }

  // @todo add state to determine when both crf and drf and been deleted.
  /**
   * Note that this can return true even when we still need to keep the oplog around
   * because its drf is still needed.
   */
  boolean isDeleted() {
    return this.deleted.get();
  }
  
  /**
   * Destroys this oplog. First it will call close which will cleanly close all
   * Async threads and then the oplog file will be deleted. The
   * deletion of lock files will be taken care of by the close.
   *  
   */
  public void destroy()
  {
    lockCompactor();
    try {
      if (!this.closed) {
        this.basicClose(true /* force delete */);
      } else {
        // do the following even if we were already closed
        deleteFiles(false);
      }
    } finally {
      unlockCompactor();
    }
  }
  /* In offline compaction, after compacted each oplog, only the crf
   * will be deleted. Oplog with drf only will be housekepted later.
   */
  public void destroyCrfOnly()
  {
    lockCompactor();
    try {
      if (!this.closed) {
        this.basicClose(true /* force delete */);
      } else {
        // do the following even if we were already closed
        deleteFiles(true);
      }
    } finally {
      unlockCompactor();
    }
  }

  /**
   * A check to confirm that the oplog has been closed because of the cache
   * being closed
   *  
   */
  private void checkClosed()
  {
    getParent().getCancelCriterion().checkCancelInProgress(null);
    if (!this.closed) {
      return;
    }
    throw new OplogCancelledException("This Oplog has been closed.");
  }

  /**
   * Return the number of bytes needed to encode the given long.
   * Value returned will be >= 1 and <= 8.
   */
  static int bytesNeeded(long v) {
    if (v < 0) {
      v = ~v;
    }
    return ((64 - Long.numberOfLeadingZeros(v)) / 8)+1;
  }

  /**
   * Return absolute value of v.
   */
  static long abs(long v) {
    if (v < 0) {
      return -v;
    } else {
      return v;
    }
  }

  private long calcDelta(long opLogKeyID, byte opCode) {
    long delta;
    if (opCode == OPLOG_DEL_ENTRY_1ID) {
      delta = opLogKeyID - this.writeDelEntryId;
      this.writeDelEntryId = opLogKeyID;
    } else {
      delta = opLogKeyID - this.writeModEntryId;
      this.writeModEntryId = opLogKeyID;
//       logger.info(LocalizedStrings.DEBUG, "DEBUG calcDelta delta=" + delta
//                   + " writeModEntryId=" + oplogKeyId);
    }
    return delta;
  }

  /**
   * This function records all the data for the current op
   * into this.opState.
   * 
   * @param opCode
   *          The int value identifying whether it is create/modify or delete
   *          operation
   * @param entry
   *          The DiskEntry object being operated upon
   * @param value
   *          The byte buffer representing the value
   * @param userBits
   * @throws IOException 
   */
  private void initOpState(byte opCode, DiskRegionView dr, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, int valueLength, byte userBits,
      boolean notToUseUserBits) throws IOException {
    this.opState.initialize(opCode, dr, entry, value, valueLength,
        userBits, notToUseUserBits);
  }

  private void clearOpState() {
    this.opState.clear();
  }

  /**
   * Returns the number of bytes it will take to serialize this.opState.
   */
  private int getOpStateSize() {
    return this.opState.getSize();
  }
  private int getOpStateValueOffset() {
    return this.opState.getValueOffset();
  }

  private byte calcUserBits(DiskEntry.Helper.ValueWrapper value) {
    byte userBits = 0x0;
  
    if (value.isSerializedObject) {
      if (value == DiskEntry.Helper.INVALID_VW) {
        // its the invalid token
        userBits = EntryBits.setInvalid(userBits, true);
      } else if (value == DiskEntry.Helper.LOCAL_INVALID_VW) {
        // its the local-invalid token
        userBits = EntryBits.setLocalInvalid(userBits, true);
      } else if (value == DiskEntry.Helper.TOMBSTONE_VW) {
        // its the tombstone token
        userBits = EntryBits.setTombstone(userBits, true);
      } else {
        if (value.size() == 0) {
          throw new IllegalStateException("userBits==1 and value is zero length");
        }
        userBits = EntryBits.setSerialized(userBits, true);
      }
    }
    return userBits;
  }
  
  /**
   * Returns true if the given entry has not yet been written to this oplog.
   */
  private boolean modNeedsKey(DiskEntry entry) {
    DiskId did = entry.getDiskId();
    synchronized (did) {
      if (did.getOplogId() != getOplogId()) {
        // the last record for it was written in a different oplog
        // so we need the key.
        return true;
      } else {
        return false;
      }
    }
  }

  /**
   * Asif: Modified the code so as to reuse the already created ByteBuffer
   * during transition.  Creates a key/value pair from a region entry
   * on disk. Updates all of the necessary
   * {@linkplain DiskStoreStats statistics} and invokes basicCreate
   * 
   * @param entry
   *          The DiskEntry object for this key/value pair.
   * @param value
   *          The <code>ValueWrapper</code> for the byte data
   * @throws DiskAccessException
   * @throws IllegalStateException
   *  
   */
  public final void create(LocalRegion region, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, boolean async) {

    if (this != getOplogSet().getChild()) {
      getOplogSet().getChild().create(region, entry, value, async);
    }
    else {
      DiskId did = entry.getDiskId();
      boolean exceptionOccured = false;
      byte prevUsrBit = did.getUserBits();
      int len = did.getValueLength();
      try {
        // It is ok to do this outside of "lock" because
        // create records do not need to change.
        byte userBits = calcUserBits(value);
        // save versions for creates and updates even if value is bytearrary in 7.0
        if (entry.getVersionStamp()!=null) {
          if(entry.getVersionStamp().getMemberID() == null) {
            throw new AssertionError("Version stamp should have a member at this point for entry " + entry);
          }
          // pdx and tx will not use version
          userBits = EntryBits.setWithVersions(userBits, true);
        }
        basicCreate(region.getDiskRegion(), entry, value, userBits, async);
      }
      catch (IOException ex) {
        exceptionOccured = true;
        region.getCancelCriterion().checkCancelInProgress(ex);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, region.getFullPath());
      }
      catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        exceptionOccured = true;
        region.getCancelCriterion().checkCancelInProgress(ie);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, region.getFullPath());
      }
      finally {
        if (exceptionOccured) {
          did.setValueLength(len);
          did.setUserBits(prevUsrBit);
        }

      }

    }

  }

  /**
   * Return true if no records have been written to the oplog yet.
   */
  private boolean isFirstRecord() {
    return this.firstRecord;
  }
  
  /**
   * Asif: A helper function which identifies whether to create the entry in the
   * current oplog or to make the switch to the next oplog. This function
   * enables us to reuse the byte buffer which got created for an oplog which no
   * longer permits us to use itself
   * 
   * @param entry
   *          DiskEntry object representing the current Entry
   * @throws IOException
   * @throws InterruptedException
   */
  private void basicCreate(DiskRegion dr, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, byte userBits, boolean async)
      throws IOException, InterruptedException
  {
    DiskId id = entry.getDiskId();
    boolean useNextOplog = false;
    long startPosForSynchOp = -1;
    if (DiskStoreImpl.KRF_DEBUG) {
      // wait for cache close to create krf
      System.out.println("basicCreate KRF_DEBUG");
      Thread.sleep(1000);
    }
    synchronized (this.lock) { // TODO soplog perf analysis shows this as a contention point
      //synchronized (this.crf) {
      final int valueLength = value.size();
      // can't use value buffer after handing it over to opState (except
      //   logging or if useNextOplog=true when OpState ops are skipped here)
      initOpState(OPLOG_NEW_ENTRY_0ID, dr, entry, value, valueLength,
          userBits, false);
      // Asif : Check if the current data in ByteBuffer will cause a
      // potential increase in the size greater than the max allowed
      long temp = (getOpStateSize() + this.crf.currSize);
      if (!this.wroteNewEntryBase) {
        temp += OPLOG_NEW_ENTRY_BASE_REC_SIZE;
      }
      if (this != getOplogSet().getChild()) {
        useNextOplog = true;
      }
      else if (temp > getMaxCrfSize() && !isFirstRecord()) {
        switchOpLog(dr, getOpStateSize(), entry, false);
        // reset the OpState without releasing the value
        this.opState.reset();
        useNextOplog = true;
      }
      else {
        if (this.lockedForKRFcreate) {
          throw new CacheClosedException("The disk store is closed.");
        }
        this.firstRecord = false;
        writeNewEntryBaseRecord(async);
        // Now we can finally call newOplogEntryId.
        // We need to make sure the create records
        // are written in the same order as they are created.
        // This allows us to not encode the oplogEntryId explicitly in the record
        long createOplogEntryId = getOplogSet().newOplogEntryId();
        id.setKeyId(createOplogEntryId);

        // startPosForSynchOp = this.crf.currSize;
        // Asif: Allow it to be added to the OpLOg so increase the
        // size of currenstartPosForSynchOpt oplog
        int dataLength = getOpStateSize();
        // Asif: It is necessary that we set the
        // Oplog ID here without releasing the lock on object as we are
        // writing to the file after releasing the lock. This can cause
        // a situation where the
        // switching thread has added Oplog for compaction while the previous
        // thread has still not started writing. Thus compactor can
        // miss an entry as the oplog Id was not set till then.
        // This is because a compactor thread will iterate over the entries &
        // use only those which have OplogID equal to that of Oplog being
        // compacted without taking any lock. A lock is taken only if the
        // entry is a potential candidate. 
        // Further the compactor may delete the file as a compactor thread does
        // not require to take any shared/exclusive lock at DiskStoreImpl
        // or Oplog level.
        // It is also assumed that compactor thread will take a lock on both
        // entry as well as DiskID while compacting. In case of synch
        // mode we can
        // safely set OplogID without taking lock on DiskId. But
        // for asynch mode
        // we have to take additional precaution as the asynch
        // writer of previous
        // oplog can interfere with the current oplog.
        id.setOplogId(getOplogId());
        // do the io while holding lock so that switch can set doneAppending
        // Write the data to the opLog for the synch mode
        startPosForSynchOp = writeOpLogBytes(this.crf, async, true);
//         if (this.crf.currSize != startPosForSynchOp) {
//           LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//            logger.info(LocalizedStrings.DEBUG, "currSize=" + this.crf.currSize
//                        + " startPosForSynchOp=" + startPosForSynchOp
//                        + " oplog#" + getOplogId());
//           assert false;
//         }
        this.crf.currSize = temp;
//         {
//           LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//           l.info(LocalizedStrings.DEBUG, "create setting size to=" + temp
//                  + " oplog#" + getOplogId());
//         }
//         if (temp != this.crf.raf.getFilePointer())
//         {
//            LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//            l.info(LocalizedStrings.DEBUG, "create setting size to=" + temp
//                   + " fp=" + this.crf.raf.getFilePointer()
//                   + " oplog#" + getOplogId());
//          }
        if (EntryBits.isNeedsValue(userBits)) {
          id.setValueLength(valueLength);
        } else {
          id.setValueLength(0);
        }
        id.setUserBits(userBits);
        
        if (this.logger.finerEnabled()) {
          this.logger
            .finer("Oplog::basicCreate:About to Release ByteBuffer with data for Disk ID = "
                   + id.toString());
        }
        if (this.logger.finerEnabled()) {
          this.logger
            .finer("Oplog::basicCreate:Release ByteBuffer with data for Disk ID = "
                   + id.toString());
        }
        // Asif: As such for any put or get operation , a synch is taken
        // on the Entry object in the DiskEntry's Helper functions.
        // Compactor thread will also take a lock on entry object. Therefore
        // we do not require a lock on DiskID, as concurrent access for
        // value will not occur.
        startPosForSynchOp += getOpStateValueOffset();
          if (DiskStoreImpl.TRACE_WRITES) {
            VersionTag tag = null; 
            if (entry.getVersionStamp()!=null) {
              tag = entry.getVersionStamp().asVersionTag();
            }
            this.logger.info(LocalizedStrings.DEBUG,
                             "TRACE_WRITES basicCreate: id=<" + abs(id.getKeyId())
                             + "> key=<" + entry.getKeyCopy() + ">"
                             + " valueOffset=" + startPosForSynchOp
                             + " userBits=" + userBits
                             + " valueLen=" + valueLength
                             + " valueBytes=<" + value + ">"
                             + " drId=" + dr.getId()
                             + " versionTag=" + tag
                             + " oplog#" + getOplogId());
          }
        id.setOffsetInOplog(startPosForSynchOp);
        addLive(dr, entry);
        // Size of the current oplog being increased 
        // due to 'create' operation. Set the change in stats.
//       {
//         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//         l.info(LocalizedStrings.DEBUG, "create inc=" + dataLength);
//       }
        this.dirHolder.incrementTotalOplogSize(dataLength);          
        incTotalCount();

        //Update the region version vector for the disk store.
        //This needs to be done under lock so that we don't switch oplogs
        //unit the version vector accurately represents what is in this oplog
        RegionVersionVector rvv = dr.getRegionVersionVector();
        final VersionStamp<?> version;
        if (rvv != null && (version = entry.getVersionStamp()) != null) {
          rvv.recordVersion(version.getMemberID(), version.getRegionVersion(), null);
        }

        // getKeyCopy() is a potentially expensive operation in GemFireXD so
        // qualify with EntryLogger.isEnabled() first
        if (EntryLogger.isEnabled()) {
          EntryLogger.logPersistPut(dr.getName(), entry.getKeyCopy(),
              dr.getDiskStoreID());
        }
        clearOpState();
      }
    }
    if (useNextOplog) {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterSwitchingOplog();
      }
      Assert.assertTrue(this != getOplogSet().getChild());
      getOplogSet().getChild().basicCreate(dr, entry, value, userBits, async);
    }
    else {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance()
          .afterSettingOplogOffSet(startPosForSynchOp);
      }
    }
  }

  /**
   * This oplog will be forced to switch to a new oplog
   * 
   * 
   * public void forceRolling() { if (getOplogSet().getChild() == this) {
   * synchronized (this.lock) { if (getOplogSet().getChild() == this) {
   * switchOpLog(0, null); } } if (!this.sync) {
   * this.writer.activateThreadToTerminate(); } } }
   */

  /**
   * This oplog will be forced to switch to a new oplog
   */
  void forceRolling(DiskRegion dr, boolean blocking)
  {
    if (getOplogSet().getChild() == this) {
      synchronized (this.lock) {
        if (getOplogSet().getChild() == this) {
          switchOpLog(dr, 0, null, blocking);
        }
      }
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterSwitchingOplog();
      }
    }
  }

  /**
   * Return true if it is possible that compaction of this oplog will be done.
   */
  private boolean isCompactionPossible() {
    return getOplogSet().isCompactionPossible();
  }
  
  /**
   * Asif: This function is used to switch from one op Log to another , when the
   * size of the current oplog has reached the maximum permissible. It is always
   * called from synch block with lock object being the OpLog File object We
   * will reuse the ByteBuffer Pool. We should add the current Oplog for compaction
   * first & then try to get next directory holder as in case there is only a
   * single directory with space being full, compaction has to happen before it can
   * be given a new directory. If the operation causing the switching is on an
   * Entry which already is referencing the oplog to be compacted, then the compactor
   * thread will skip compaction that entry & the switching thread will roll the
   * entry explicitly.
   * 
   * @param lengthOfOperationCausingSwitch
   *          length of the operation causing the switch
   * @param entryCausingSwitch
   *          DiskEntry object operation on which caused the switching of Oplog.
   *          This can be null if the switching has been invoked by the
   *          forceRolling which does not need an operation on entry to cause
   *          the switch
   */
  private void switchOpLog(DiskRegionView dr, int lengthOfOperationCausingSwitch,
                           DiskEntry entryCausingSwitch, boolean blocking)
  {
    String drName;
    if (dr != null) {
      drName = dr.getName();
    } else {
      drName = getParent().getName();
    }
    flushAll(); // needed in case of async
    lengthOfOperationCausingSwitch += 20; // for worstcase overhead of writing first record
//     logger.info(LocalizedStrings.DEBUG, "DEBUG: recSize="
//                 + lengthOfOperationCausingSwitch
//                 + " crf.currSize=" + this.crf.currSize
//                 + " drf.currSize=" + this.drf.currSize
//                 + " crf.maxSize=" + getMaxCrfSize()
//                 + " drf.maxSize=" + getMaxDrfSize()
//                 , new RuntimeException("STACK"));
    // if length of operation is greater than max Dir Size than an exception
    // is
    // thrown

    if (this.logger.fineEnabled()) {
      this.logger
          .fine("Oplog::switchOpLog: Entry causing Oplog switch has diskID="
              + (entryCausingSwitch != null ? entryCausingSwitch.getDiskId()
                  .toString() : "Entry is null"));
    }    
    
    if (lengthOfOperationCausingSwitch > getParent().getMaxDirSize()) {
      throw new DiskAccessException(LocalizedStrings.Oplog_OPERATION_SIZE_CANNOT_EXCEED_THE_MAXIMUM_DIRECTORY_SIZE_SWITCHING_PROBLEM_FOR_ENTRY_HAVING_DISKID_0.toLocalizedString((entryCausingSwitch != null
                                                                                                                                                                                  ? entryCausingSwitch.getDiskId().toString()
                                                                                                                                                                                  : "\"null Entry\"")),
                                    drName);     
    }
    if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
      CacheObserverHolder.getInstance().beforeSwitchingOplog();
    }

    if (this.logger.fineEnabled()) {
      this.logger.fine("Oplog::switchOpLog: About to add the Oplog = "
                       + this.oplogId
                       + " for compaction. Entry causing the switch is having DiskID = "
                       + (entryCausingSwitch != null ? entryCausingSwitch.getDiskId()
                          .toString() : "null Entry"));
    }
    if (needsCompaction()) {
      addToBeCompacted();
    } else {
      getOplogSet().addInactive(this);
    }

    try {
      DirectoryHolder nextDirHolder = getOplogSet().getNextDir(
          lengthOfOperationCausingSwitch);
      Oplog newOplog = new Oplog(this.oplogId + 1, nextDirHolder, this);
      newOplog.firstRecord = true;
      getOplogSet().setChild(newOplog);

      finishedAppending();
      
      //Defer the unpreblow and close of the RAF. We saw pauses in testing from
      //unpreblow of the drf, maybe because it is freeing pages that were
      //preallocated. Close can pause if another thread is calling force on the
      //file channel - see 50254. These operations should be safe to defer,
      //a concurrent read will synchronize on the oplog and use or reopen the RAF
      //as needed.
      getParent().executeDelayedExpensiveWrite(new Runnable() {
        public void run() {
          // need to truncate crf and drf if their actual size is less than their pregrow size
          unpreblow(Oplog.this.crf, getMaxCrfSize());
          unpreblow(Oplog.this.drf, getMaxDrfSize());
          // Close the crf using closeRAF. We will reopen the crf if we
          // need it to fault in or to read values during compaction.
          closeRAF();
          // I think at this point the drf no longer needs to be open
          synchronized (Oplog.this.lock/*drf*/) {
            if (!Oplog.this.drf.RAFClosed) {
              final OplogFile.FileChannelOutputStream stream = drf.outputStream;
              try {
                if (stream != null) {
                  stream.closeChannel();
                } else {
                  drf.channel.close();
                }
              } catch (IOException ignore) {
              }
              try {
                Oplog.this.drf.raf.close();
              } catch (IOException ignore) {
              }
              Oplog.this.drf.RAFClosed = true;
            }
          }
          
        }
      });

      
      // offline compaction will not execute create Krf in the task, becasue
      // this.totalLiveCount.get() == 0
      if (getParent().isOfflineCompacting()) {
        krfClose(true, false);
      } else {
        if (blocking) {
          createKrf(false);
        }
        else {
          createKrfAsync();
        }
      }
    }
    catch (DiskAccessException dae) {
      // Asif: Remove the Oplog which was added in the DiskStoreImpl
      // for compaction as compaction cannot be done.
      // However, it is also possible that compactor
      // may have done the compaction of the Oplog but the switching thread
      // encountered timeout exception.
      // So the code below may or may not actually
      // ensure that the Oplog has been compacted or not.
      getOplogSet().removeOplog(this.oplogId) ;
      throw dae;
    }    
  }

  /**
   * Schedule a task to create a krf asynchronously.
   */
  protected void createKrfAsync() {
    if (logger.infoEnabled()) {
      this.logger.info(
          LocalizedStrings.DEBUG,
          "createKrfAsync called for oplog: " + this + ", parent: "
              + parent.getName());
    }
    boolean submitted = getParent().executeDiskStoreTask(new Runnable() {
      public void run() {
        // for GemFireXD first wait for first phase DDL replay to finish so that
        // indexes, regions etc. are in a stable state
        boolean signalEnd = getParent().waitBeforeAsyncDiskTask();
        try {
          // return if diskstore is closing
          if (getParent().isClosing()) {
            return;
          }
          createKrf(false);
        } finally {
          if (signalEnd) {
            getParent().endAsyncDiskTask();
          }
        }
      }
    });
    if (!submitted) {
      if (logger.infoEnabled()) {
        this.logger.info(LocalizedStrings.DEBUG,
            "createKrfAsync createKrf job for oplog: " + this + ", parent: "
                + parent.getName() + " could not be submitted successfully");
      }
      this.krfCreationCancelled.set(true);
    }
  }

  /**
   * Used when creating a KRF to keep track of what DiskRegionView a DiskEntry
   * belongs to.
   */
  public static final class KRFEntry {
    private final DiskEntry de;
    private final DiskRegionView drv;
    /** Fix for 42733 - a stable snapshot
     * of the offset so we can sort
     * It doesn't matter that this is stale,
     * we'll filter out these entries later.
     */
    private final long offsetInOplog;
    private final VersionHolder versionTag;

    public KRFEntry(DiskRegionView drv, DiskEntry de, VersionHolder tag) {
      this.de = de;
      this.drv = drv;
      DiskId diskId = de.getDiskId();
      this.offsetInOplog = diskId != null ? diskId.getOffsetInOplog() : 0;
      this.versionTag = tag;
    }

    public DiskEntry getDiskEntry() {
      return this.de;
    }

    public DiskRegionView getDiskRegionView() {
      return this.drv;
    }
    
    public long getOffsetInOplogForSorting() {
      return offsetInOplog;
    }
  }

  private boolean writeOneKeyEntryForKRF(KRFEntry ke, long currentTime)
      throws IOException {
    DiskEntry de = ke.getDiskEntry();
    long diskRegionId = ke.getDiskRegionView().getId();
    long oplogKeyId;
    byte userBits;
    long valueOffset;
    int valueLength;
    Object deKey;
    VersionHolder tag = ke.versionTag;

    synchronized (de) {
      DiskId di = de.getDiskId();
      if (di == null) {
        return false;
      }
      if(de.isRemovedFromDisk()) {
        //the entry was concurrently removed
        return false;
      }
      synchronized (di) {
        // Make sure each one is still in this oplog.
        if (di.getOplogId() != getOplogId()) {
          return false;
        }
        userBits = di.getUserBits();
        oplogKeyId = Math.abs(di.getKeyId());
        valueOffset = di.getOffsetInOplog();
        valueLength = di.getValueLength();
        deKey = de.getKeyCopy();
        if (valueOffset < 0) {
          assert (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits));
        }
      }

      if(tag ==null) {
        if (EntryBits.isWithVersions(userBits) && de.getVersionStamp()!=null) {
          tag = de.getVersionStamp().asVersionTag();
        } else if(de.getVersionStamp() != null) {
          throw new AssertionError("No version bits on entry we're writing to the krf " + de);
        }
      }
    }
    if(DiskStoreImpl.TRACE_WRITES) {
      this.logger.info(LocalizedStrings.DEBUG,
          "TRACE_WRITES krf oplogId=" + oplogId + " key=" + deKey
         + " oplogKeyId=" + oplogKeyId + " de="
         + System.identityHashCode(de) + " vo=" + valueOffset + " vl="
         + valueLength+ " diskRegionId="+diskRegionId+" version tag="+tag);
    }
    byte[] keyBytes = EntryEventImpl.serialize(deKey);
    
    //skip the invalid entries, theire valueOffset is -1
    return writeOneKeyEntryForKRF(keyBytes, userBits, valueLength, diskRegionId,
        oplogKeyId, valueOffset, tag, de.getLastModified(), currentTime);
  }

  private boolean writeOneKeyEntryForKRF(byte[] keyBytes, byte userBits,
      int valueLength, long diskRegionId, long oplogKeyId, long valueOffset,
      VersionHolder tag, long lastModifiedTime, long currentTime)
      throws IOException {
    if (getParent().isValidating()) {
      return false;
    }
    
    if (!getParent().isOfflineCompacting()) {
      assert (this.krf.dos!=null);
    } else {
      if (this.krf.dos == null) {
        // krf already exist, thus not re-opened for write
        return false;
      }
    }
    DataSerializer.writeByteArray(keyBytes, this.krf.dos);
    boolean withVersions = EntryBits.isWithVersions(userBits);
    if (withVersions && tag == null) {
      userBits = EntryBits.setWithVersions(userBits, false);
      withVersions = false;
    }
    if (!withVersions) {
      userBits = EntryBits.setHasLastModifiedTime(userBits);
    }
    this.krf.dos.writeByte(EntryBits.getPersistentBits(userBits));
    InternalDataSerializer.writeArrayLength(valueLength, this.krf.dos);
    DiskInitFile.writeDiskRegionID(this.krf.dos, diskRegionId);
    if (withVersions) {
      serializeVersionTag(tag, this.krf.dos);
    }
    else {
      if (lastModifiedTime == 0) {
         lastModifiedTime = currentTime;
      }
      InternalDataSerializer.writeUnsignedVL(lastModifiedTime, this.krf.dos);
    }
    InternalDataSerializer.writeVLOld(oplogKeyId, this.krf.dos);
    //skip the invalid entries, theire valueOffset is -1
    if(!EntryBits.isAnyInvalid(userBits) && !EntryBits.isTombstone(userBits)) {
      InternalDataSerializer.writeVLOld((valueOffset - this.krf.lastOffset), this.krf.dos);
      // save the lastOffset in krf object
      this.krf.lastOffset = valueOffset;
    }
    this.krf.keyNum ++;
    return true;
  }

  private final AtomicBoolean krfCreated = new AtomicBoolean();

  private final AtomicBoolean krfCreationCancelled = new AtomicBoolean();

  private String getKrfFilePath() {
    return this.diskFile.getPath() + KRF_FILE_EXT;
  }

  public void krfFileCreate() throws IOException {
    // this method is only used by offline compaction. validating will not create krf
    assert (getParent().isValidating() == false);
    
    this.krf.f = new File(getKrfFilePath());
    if (this.krf.f.exists()) {
      throw new IllegalStateException("krf file " + this.krf.f + " already exists.");
    }  
    this.krf.fos = new FileOutputStream(this.krf.f);
    this.krf.bos = new BufferedOutputStream(this.krf.fos, DEFAULT_BUFFER_SIZE);
    this.krf.dos = new DataOutputStream(this.krf.bos);
    //write the disk store id to the krf
    this.krf.dos.writeLong(getParent().getDiskStoreID().getLeastSignificantBits());
    this.krf.dos.writeLong(getParent().getDiskStoreID().getMostSignificantBits());
    this.krf.dos.writeByte(END_OF_RECORD_ID);

    // write product versions
    assert this.gfversion != null;
    // write both gemfire and data versions if the two are different else write
    // only gemfire version; a token distinguishes the two cases while reading
    // like in writeGemFireVersionRecord
    Version dataVersion = getDataVersionIfOld();
    if (dataVersion == null) {
      dataVersion = Version.CURRENT;
    }
    if (this.gfversion == dataVersion) {
      this.gfversion.writeOrdinal(this.krf.dos, false);
    }
    else {
      Version.TOKEN.writeOrdinal(this.krf.dos, false);
      this.krf.dos.writeByte(END_OF_RECORD_ID);
      this.krf.dos.writeByte(OPLOG_GEMFIRE_VERSION);
      this.gfversion.writeOrdinal(this.krf.dos, false);
      this.krf.dos.writeByte(END_OF_RECORD_ID);
      this.krf.dos.writeByte(OPLOG_GEMFIRE_VERSION);
      dataVersion.writeOrdinal(this.krf.dos, false);
    }
    this.krf.dos.writeByte(END_OF_RECORD_ID);

    //Write the total entry count to the krf so that when we recover, 
    //our compaction statistics will be accurate
    InternalDataSerializer.writeUnsignedVL(this.totalCount.get(), this.krf.dos);
    this.krf.dos.writeByte(END_OF_RECORD_ID);

    //Write the RVV to the krf.
    Map<Long, AbstractDiskRegion> drMap = getParent().getAllDiskRegions();
    byte[] rvvBytes = serializeRVVs(drMap, false);
    this.krf.dos.write(rvvBytes);
    this.krf.dos.writeByte(END_OF_RECORD_ID);
  }

  // if IOException happened during krf creation, close and delete it 
  private void closeAndDeleteKrf() {
    try {
      if (this.krf.dos != null) {
        this.krf.dos.close();
        this.krf.dos = null;
      }
    } catch (IOException ignore) {
    }
    try {
      if (this.krf.bos != null) {
        this.krf.bos.close();
        this.krf.bos = null;
      }
    } catch (IOException ignore) {
    }
    try {
      if (this.krf.fos != null) {
        this.krf.fos.close();
        this.krf.fos = null;
      }
    } catch (IOException ignore) {
    }

    if (this.krf.f.exists()) {
       this.krf.f.delete();
    }
  }

  public void krfClose(boolean deleteEmptyKRF, boolean persistIndexes) {
    boolean allClosed = false;
    try {
      if (this.krf.fos != null) {
        DataSerializer.writeByteArray(null, this.krf.dos);
      } else {
        return;
      }

      this.krf.dos.close();
      this.krf.dos = null;
      this.krf.bos.close();
      this.krf.bos = null;
      this.krf.fos.close();
      this.krf.fos = null;

      if (this.krf.keyNum == 0 && deleteEmptyKRF) {
        // this is an empty krf file
        this.krf.f.delete();
        assert this.krf.f.exists() == false;
      } else {
        //Mark that this krf is complete.
        if (!persistIndexes) {
          getParent().getDiskInitFile().krfCreate(this.oplogId);
        }
        if (logger.infoEnabled()) {
          logger.info(LocalizedStrings.Oplog_CREATE_0_1_2, new Object[] {
              toString(), "krf", getParent().getName() });
        }
      }
      
      allClosed = true;
    } catch (IOException e) {
      throw new DiskAccessException("Fail to close krf file "+this.krf.f, e, getParent());
    } finally {
      if (!allClosed) {
        // IOException happened during close, delete this krf
        closeAndDeleteKrf();
      }
    }
  }

  /**
   * Create the KRF file for this oplog. It is ok for this method to be async.
   * finishKRF will be called and it must block until KRF generation is
   * complete.
   * 
   * @param cancel
   *          if true then prevent the krf from being created if possible
   */
  void createKrf(boolean cancel) {
    //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf called for oplog: " + this + " parent: " + this.parent.getName());
    if (cancel) {
      this.krfCreated.compareAndSet(false, true);
      //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf ret 1");
      return;
    }
    if (!couldHaveKrf()) {
      //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf ret 2");
      return;
    }
    final boolean persistIndexes = this.parent.isPersistIndexes();
    // Make sure regions can not become unrecovered while creating the KRF.
    getParent().acquireCompactorReadLock();
    try {
      if (!getParent().allowKrfCreation()) {
        return;
      }
      //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf locking compactor");
      lockCompactor();

      try {
        synchronized(this.lock) {
          // 42733: after set it to true, we will not reset it, since this oplog will be 
          // inactive forever
          this.lockedForKRFcreate = true;
        }
        //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf locked for krfcreate");
        synchronized (this.krfCreated) {
          if (this.krfCreated.get()) {
            //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf ret 3");
            return;
          }

          this.krfCreated.set(true);

          if (this.unrecoveredRegionCount.get() > 0) {
            // if we have unrecovered regions then we can't create
            // a KRF because we don't have the list of live entries.
            //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf ret 4");
            return;
          }

          int tlc = (int) this.totalLiveCount.get();
          
          if (tlc <= 0) {
            // no need to create a KRF since this oplog will be deleted.
            // TODO should we create an empty KRF anyway?
            //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf ret 5");
            return;
          }            

          // logger.info(LocalizedStrings.DEBUG, "DEBUG: tlc=" + tlc);
          Collection<DiskRegionInfo> regions = this.regionMap.values();
          List<KRFEntry> sortedLiveEntries = getSortedLiveEntries(regions);
          if (sortedLiveEntries == null || sortedLiveEntries.isEmpty()) {
            //no need to create a krf if there are no live entries.
            //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf ret 6");
            return;
          }

          if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
            if (!CacheObserverHolder.getInstance().shouldCreateKRFIRF()) {
              //logger.info(LocalizedStrings.DEBUG, "DEBUG: createKrf ret 7");
              return;
            }
          }
          boolean krfCreateSuccess = false;
          try {
            krfFileCreate();

            final OpenHashSet<KRFEntry> notWrittenKRFs = new OpenHashSet<>();
            // sortedLiveEntries are now sorted
            // so we can start writing them to disk.
            if (sortedLiveEntries != null) {
              final long currentTime = getParent().getCache().cacheTimeMillis();
              for (KRFEntry ke : sortedLiveEntries) {
                boolean written = writeOneKeyEntryForKRF(ke, currentTime);
                if (!written) {
                  if (persistIndexes) {
                    notWrittenKRFs.add(ke);
                  }
                }
              }
            }

            // If index persistence is ON then we would delay writing the dif record
            // until the IRF is also generated.
            krfClose(true, persistIndexes);
            krfCreateSuccess = true;
            for(DiskRegionInfo dri : regions) {
              dri.afterKrfCreated();
            }
            if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
              CacheObserverHolder.getInstance().afterKrfCreated();
            }

            if (persistIndexes) {
              writeIRF(sortedLiveEntries, notWrittenKRFs, null, null);
              if (logger.fineEnabled()) {
                logger.info(LocalizedStrings.DEBUG, "createKrf: going to "
                    + "write krfCreate and irfCreate records for: " + this);
              }
              this.parent.flushAndSync(true);
              DiskInitFile initFile = getParent().getDiskInitFile();
              initFile.krfCreate(this.oplogId);
              initFile.irfCreate(this.oplogId);
            }
          } catch (FileNotFoundException ex) {
            // handle exception; we couldn't open the krf file
            throw new IllegalStateException("could not create krf " +
                this.krf.f.getAbsolutePath(), ex);
          } catch (IOException ex) {
            // handle io exceptions; we failed to write to the file
            throw new IllegalStateException("failed writing krf " +
                this.krf.f.getAbsolutePath(), ex);
          } finally {
            synchronized (this.krfCreated) {
              this.krfCreated.notifyAll();
            }
            // if IOException happened in writeOneKeyEntryForKRF(), delete krf here
            if (!krfCreateSuccess) {
              closeAndDeleteKrf();
            }
          }
        }
      } finally {
        unlockCompactor();
      }
    } finally {
      getParent().releaseCompactorReadLock();
    }
  }

  /**
   * Write index records for given list of region entries in this Oplog.
   * 
   * @param sortedLiveEntries
   *          the list of region entries to write
   * @param notWrittenKRFs
   *          any entries that were not successfully written to KRF
   * @param dumpIndexes
   *          the index containers for which to write the index data, or null to
   *          write for all indexes
   * @param loadIndexes
   *          if any index containers have also to be populated with data from
   *          this oplog
   */
  @SuppressWarnings("unchecked")
  public long writeIRF(List<KRFEntry> sortedLiveEntries,
      final OpenHashSet<KRFEntry> notWrittenKRFs,
      Set<SortedIndexContainer> dumpIndexes,
      Map<SortedIndexContainer, SortedIndexRecoveryJob> loadIndexes)
      throws IOException {
    final GemFireCacheImpl.StaticSystemCallbacks sysCb = GemFireCacheImpl
        .getInternalProductCallbacks();
    final boolean traceOn = sysCb.tracePersistFinestON();

    // wait for krf creation to complete if in progress
    if (!this.krfCreated.get()) {
      final long start = System.currentTimeMillis();
      final long maxWait = 60000L;
      synchronized (this.krfCreated) {
        while (!this.krfCreated.get()) {
          if (System.currentTimeMillis() > (start + maxWait)) {
            throw new DiskAccessException("Failed to write index file due " +
                "to missing key file (" + getKrfFilePath() + ')', getParent());
          }
          try {
            this.krfCreated.wait(500L);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            DiskStoreImpl dsi = getParent();
            dsi.getCache().getCancelCriterion().checkCancelInProgress(ie);
            throw new DiskAccessException("Failed to write index file due " +
                "to missing key file (" + getKrfFilePath() + ')', ie, dsi);
          }
        }
      }
    }
    // truncate existing idxkrf if records for all indexes need to be written
    this.idxkrf.initializeForWriting(dumpIndexes == null);
    long numEntries = 0;
    if (sortedLiveEntries != null
        && (numEntries = sortedLiveEntries.size()) > 0) {
      if (notWrittenKRFs != null) {
        numEntries -= notWrittenKRFs.size();
      }
      if (dumpIndexes == null) {
        dumpIndexes = sysCb.getAllLocalIndexes(getParent());
        this.indexesWritten.clear();
      }
      else if (!this.indexesWritten.isEmpty()) {
        // remove the indexes already written for this oplog
        dumpIndexes = new OpenHashSet<>(dumpIndexes);
        dumpIndexes.removeAll(this.indexesWritten);
      }
      if (logger.fineEnabled() || traceOn) {
        logger.fine("writeIRF called for diskstore: " + this.parent.getName()
            + " and oplog id: " + this.oplogId + ", indexes: "
            + dumpIndexes);

        if (logger.finerEnabled() || traceOn) {
          logger.finer("List of krf entries during createKrf of diskstore: "
              + this.parent.getName() + " and oplog id: " + this.oplogId);
          for (KRFEntry ke : sortedLiveEntries) {
            logger.finer("oplogentryid: " + ke.de.getDiskId().getKeyId()
                + " and region key: " + ke.de.getKey());
          }
          logger.finer("List of krf entries during createKrf of diskstore: "
              + this.parent.getName() + " and oplog id: " + this.oplogId
              + " ends");
        }
        if (notWrittenKRFs != null) {
          for (KRFEntry ke : notWrittenKRFs) {
            logger.fine("notWritten: oplogentryid: "
                + ke.de.getDiskId().getKeyId() + " and region key: "
                + ke.de.getKey());
          }
        }
      }
      this.idxkrf.writeIndexRecords(sortedLiveEntries, notWrittenKRFs,
          dumpIndexes, loadIndexes);
      if (logger.fineEnabled()) {
        logger.fine("writeIRF ends for diskstore: " + this.parent.getName()
            + " and oplog id: " + this.oplogId);
      }
      this.indexesWritten.addAll(dumpIndexes);
    }
    if (logger.fineEnabled() || DiskStoreImpl.INDEX_LOAD_DEBUG) {
      logger.info(LocalizedStrings.DEBUG,
          "writeIRF going to flush and close idkkrf for: " + this);
    }
    this.idxkrf.close();
    if (logger.infoEnabled()) {
      logger.info(LocalizedStrings.Oplog_CREATE_0_1_2, new Object[]{
          toString(), this.idxkrf.getIndexFile().getAbsolutePath(),
          getParent().getName()});
    }
    return numEntries;
  }

  public OplogIndex getOplogIndex() {
    return this.idxkrf;
  }

  public Collection<DiskRegionInfo> getTargetRegionsForIndexes(
      Set<SortedIndexContainer> indexes) {
    if (indexes != null) {
      ArrayList<DiskRegionInfo> targetRegions = new ArrayList<DiskRegionInfo>(
          this.regionMap.size());
      OpenHashSet<String> usedRegionIDs = new OpenHashSet<>(indexes.size());
      for (SortedIndexContainer index : indexes) {
        usedRegionIDs.add(index.getBaseRegion().getRegionID());
      }
      for (DiskRegionInfo regionInfo : this.regionMap.values()) {
        DiskRegionView drv = regionInfo.getDiskRegion();
        String baseRegionID = getParentRegionID(drv);
        if (usedRegionIDs.contains(baseRegionID)) {
          targetRegions.add(regionInfo);
        }
      }
      return targetRegions;
    }
    else {
      return getRegionRecoveryMap();
    }
  }

  private String printList(List<KRFEntry> list) {
    StringBuilder sb = new StringBuilder();
    for (KRFEntry ke : list) {
      sb.append(ke.de.getDiskId().getKeyId());
      sb.append(" - ");
      sb.append(ke.de.getKey());
      sb.append(" ** ");
    }
    return sb.toString();
  }

  public List<KRFEntry> getSortedLiveEntries(Collection<DiskRegionInfo> targetRegions) {
    int tlc = (int) this.totalLiveCount.get();
    if (tlc <= 0) {
      // no need to create a KRF since this oplog will be deleted.
      // TODO should we create an empty KRF anyway?
      return null;
    }
    
    KRFEntry[] sortedLiveEntries = new KRFEntry[tlc];
    int idx = 0;
    for (DiskRegionInfo dri : targetRegions) {
          // logger.info(LocalizedStrings.DEBUG, "DEBUG: dri=" + dri);
      if (dri.getDiskRegion() != null) {
        idx = dri.addLiveEntriesToList(sortedLiveEntries, idx);
            // logger.info(LocalizedStrings.DEBUG, "DEBUG: idx=" + idx);
      }
    }
    // idx is now the length of sortedLiveEntries
    Arrays.sort(sortedLiveEntries, 0, idx, new Comparator<KRFEntry>() {
      public int compare(KRFEntry o1, KRFEntry o2) {
        long val1 = o1.getOffsetInOplogForSorting();
        long val2 = o2.getOffsetInOplogForSorting();
        return Long.signum(val1 - val2);
      }
    });
    
    return Arrays.asList(sortedLiveEntries).subList(0, idx);
  }

  /**
   * Asif:This function retrieves the value for an entry being compacted subject to
   * entry referencing the oplog being compacted. Attempt is made to retrieve the
   * value from in memory , if available, else from asynch buffers ( if asynch
   * mode is enabled), else from the Oplog being compacted. It is invoked from
   * switchOplog as well as OplogCompactor's compact function.
   * 
   * @param entry
   *                DiskEntry being compacted referencing the Oplog being compacted
   * @param wrapper
   *                Object of type BytesAndBitsForCompactor. The data if found is
   *                set in the wrapper Object. The wrapper Object also contains
   *                the user bit associated with the entry
   * @return boolean false indicating that entry need not be compacted. If true it
   *         means that wrapper has been appropriately filled with data
   */
  private boolean getBytesAndBitsForCompaction(DiskRegionView dr, DiskEntry entry,
                                               BytesAndBitsForCompactor wrapper)
  {
    // caller is synced on did
    DiskId did = entry.getDiskId();
    byte userBits = 0;
    long oplogOffset = did.getOffsetInOplog();
    SimpleMemoryAllocatorImpl.skipRefCountTracking();
    @Retained @Released Object value = entry._getValueRetain(dr, true);  // OFFHEAP for now copy into heap CD; todo optimize by keeping offheap for life of wrapper
    SimpleMemoryAllocatorImpl.unskipRefCountTracking();
    // TODO:KIRK:OK Object value = entry.getValueWithContext(dr);
    boolean foundData = false;
    if (value == null) {
      // Asif: If the mode is synch it is guaranteed to be present in the disk
      foundData = basicGetForCompactor(dr, oplogOffset, false,
                                       did.getValueLength(),
                                       did.getUserBits(),
                                       wrapper);
      // after we have done the get do one more check to see if the
      // disk id of interest is still stored in the current oplog.
      // Do this to fix bug 40648
      // Since we now call this with the diskId synced I think
      // it is impossible for this oplogId to change.
      if (did.getOplogId() != getOplogId()) {
        // if it is not then no need to compact it
        //           logger.info(LocalizedStrings.DEBUG, "DEBUG skipping #2 did.Oplog#" + did.getOplogId() + " was not oplog#" + getOplogId());
        return false;
      } else {
        // if the disk id indicates its most recent value is in oplogInFocus
        // then we should have found data
        assert foundData : "compactor get failed on oplog#" + getOplogId();
      }
      userBits = wrapper.getBits();
      if (EntryBits.isAnyInvalid(userBits)) {
        if (EntryBits.isInvalid(userBits)) {
          wrapper.setData(DiskEntry.INVALID_BYTES, userBits, DiskEntry.INVALID_BYTES.length, false/* Can not be reused*/);
        } else {
          wrapper.setData(DiskEntry.LOCAL_INVALID_BYTES, userBits, DiskEntry.LOCAL_INVALID_BYTES.length, false/* Can not be reused*/);
        }
      } else if (EntryBits.isTombstone(userBits)) {
        wrapper.setData(DiskEntry.TOMBSTONE_BYTES, userBits, DiskEntry.TOMBSTONE_BYTES.length, false/* Can not be reused*/);
      }
      if (EntryBits.isWithVersions(did.getUserBits())) {
        userBits = EntryBits.setWithVersions(userBits, true);
      }
    } else {
      foundData = true;
      userBits = 0;
      if (EntryBits.isRecoveredFromDisk(did.getUserBits())) {
        userBits = EntryBits.setRecoveredFromDisk(userBits, true);
      }
      if (EntryBits.isWithVersions(did.getUserBits())) {
        userBits = EntryBits.setWithVersions(userBits, true);
      }
      // no need to preserve pendingAsync bit since we will clear it anyway since we
      // (the compactor) are writing the value out to disk.
      if (value == Token.INVALID) {
        userBits = EntryBits.setInvalid(userBits, true);
        wrapper.setData(DiskEntry.INVALID_BYTES, userBits,
                        DiskEntry.INVALID_BYTES.length,
                        false /* Cannot be reused */);

      } else if (value == Token.LOCAL_INVALID) {
        userBits = EntryBits.setLocalInvalid(userBits, true);
        wrapper.setData(DiskEntry.LOCAL_INVALID_BYTES, userBits,
                        DiskEntry.LOCAL_INVALID_BYTES.length,
                        false /* Cannot be reused */);
      } else if (value == Token.TOMBSTONE) {
        userBits = EntryBits.setTombstone(userBits, true);
        wrapper.setData(DiskEntry.TOMBSTONE_BYTES, userBits,
                        DiskEntry.TOMBSTONE_BYTES.length,
                        false /* Cannot be reused */);
      } else if (value instanceof CachedDeserializable) {
        CachedDeserializable proxy = (CachedDeserializable)value;
        if (proxy instanceof StoredObject) {
          @Released StoredObject ohproxy = (StoredObject) proxy;
          try {
            if (ohproxy.isSerialized()) {
              userBits = EntryBits.setSerialized(userBits, true);
            }
            ohproxy.fillSerializedValue(wrapper, userBits);
          } finally {
            OffHeapHelper.releaseWithNoTracking(ohproxy);
          }
        } else {
          userBits = EntryBits.setSerialized(userBits, true);
          proxy.fillSerializedValue(wrapper, userBits);
        }
      } else if (value instanceof byte[]) {
        byte[] valueBytes = (byte[])value;
        // Asif: If the value is already a byte array then the user bit
        // is 0, which is the default value of the userBits variable,
        // indicating that it is non serialized data. Thus it is
        // to be used as it is & not to be deserialized to
        // convert into Object
        wrapper.setData(valueBytes, userBits, valueBytes.length, 
                        false /* the wrapper is not reusable */);
      } else if (Token.isRemoved(value) && value != Token.TOMBSTONE) {
        //TODO - RVV - We need to handle tombstones differently here!
        if (entry.getDiskId().isPendingAsync()) {
          entry.getDiskId().setPendingAsync(false);
          try {
            getOplogSet().getChild().basicRemove(dr, entry, false, false);
          }
          catch (IOException ex) {
            getParent().getCancelCriterion().checkCancelInProgress(ex);
            throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, dr.getName());
          }
          catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            getParent().getCache().getCancelCriterion().checkCancelInProgress(ie);
            throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, dr.getName());
          }
        } else {
          rmLive(dr, entry);
        }
        foundData = false;
      } else if (value instanceof Delta && !((Delta)value).allowCreate()) {
        // skip ListOfDeltas
        foundData = false;
      } else {
        userBits = EntryBits.setSerialized(userBits, true);
        EntryEventImpl.fillSerializedValue(wrapper, value, userBits);
      }
    }
    if (foundData) {
      // since the compactor is writing it out clear the async flag
      entry.getDiskId().setPendingAsync(false);
    }
    return foundData;
  }

  /**
   * Modifies a key/value pair from a region entry on disk. Updates all of the
   * necessary {@linkplain DiskStoreStats statistics} and invokes basicModify
   * 
   * @param entry
   *          DiskEntry object representing the current Entry
   * @param value
   *          The <code>ValueWrapper</code> for the byte data
   * @throws DiskAccessException
   * @throws IllegalStateException
   */
  /*
   * Asif: Modified the code so as to reuse the already created ByteBuffer
   * during transition. Minimizing the synchronization allowing multiple put
   * operations for different entries to proceed concurrently for asynch mode
   */
  public final void modify(LocalRegion region, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, boolean async) {

    if (getOplogSet().getChild() != this) {
      getOplogSet().getChild().modify(region, entry, value, async);
    }
    else {
      DiskId did = entry.getDiskId();
      boolean exceptionOccured = false;
      byte prevUsrBit = did.getUserBits();
      int len = did.getValueLength();
      try {
        byte userBits = calcUserBits(value);
        // save versions for creates and updates even if value is bytearrary in 7.0
        if (entry.getVersionStamp()!=null) {
          if(entry.getVersionStamp().getMemberID() == null) {
            throw new AssertionError("Version stamp should have a member at this point for entry " + entry);
          }
          // pdx and tx will not use version
          userBits = EntryBits.setWithVersions(userBits, true);
        }
        basicModify(region.getDiskRegion(), entry, value, userBits,
            async, false);
      }
      catch (IOException ex) {
        exceptionOccured = true;
        region.getCancelCriterion().checkCancelInProgress(ex);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, region.getFullPath());
      }
      catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        exceptionOccured = true;
        region.getCancelCriterion().checkCancelInProgress(ie);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, region.getFullPath());
      }
      finally {
        if (exceptionOccured) {
          did.setValueLength(len);
          did.setUserBits(prevUsrBit);
        }
      }
    }
  }

  public final void saveConflictVersionTag(LocalRegion region, VersionTag tag, boolean async)
  {
    if (getOplogSet().getChild() != this) {
      getOplogSet().getChild().saveConflictVersionTag(region, tag, async);
    }
    else {
      try {
        basicSaveConflictVersionTag(region.getDiskRegion(), tag, async);
      }
      catch (IOException ex) {
        region.getCancelCriterion().checkCancelInProgress(ex);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_CONFLICT_VERSION_TAG_0.toLocalizedString(this.diskFile.getPath()), ex, region.getFullPath());
      }
      catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        region.getCancelCriterion().checkCancelInProgress(ie);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_CONFLICT_VERSION_TAG_0.toLocalizedString(this.diskFile.getPath()), ie, region.getFullPath());
      }
    }
  }

  private final void copyForwardForOfflineCompact(long oplogKeyId,
                                                  byte[] keyBytes,
                                                  byte[] valueBytes,
                                                  byte userBits,
                                                  long drId, 
                                                  VersionTag tag,
                                                  long lastModifiedTime,
                                                  final long currentTime) {
    try {
      basicCopyForwardForOfflineCompact(oplogKeyId, keyBytes, valueBytes,
          userBits, drId, tag, lastModifiedTime, currentTime);
    } catch (IOException ex) {
      getParent().getCancelCriterion().checkCancelInProgress(ex);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, getParent());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      getParent().getCancelCriterion().checkCancelInProgress(ie);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, getParent());
    }
  }

  private final void copyForwardModifyForCompact(DiskRegionView dr, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, byte userBits) {
    if (getOplogSet().getChild() != this) {
      getOplogSet().getChild().copyForwardModifyForCompact(dr, entry, value, userBits);
    }
    else {
      DiskId did = entry.getDiskId();
      boolean exceptionOccured = false;
      int len = did.getValueLength();
      try {
        // Compactor always says to do an async basicModify so that its writes
        // will be grouped. This is not a true async write; just a grouped one.
        basicModify(dr, entry, value, userBits, true, true);
      }
      catch (IOException ex) {
        exceptionOccured = true;
        getParent().getCancelCriterion().checkCancelInProgress(ex);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, getParent());
      }
      catch (InterruptedException ie) {
        exceptionOccured = true;
        Thread.currentThread().interrupt();
        getParent().getCancelCriterion().checkCancelInProgress(ie);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, getParent());
      } finally {
        if (exceptionOccured) {
          did.setValueLength(len);
        }
      }
    }
  }
  /**
   * Asif: A helper function which identifies whether to modify the entry in the
   * current oplog or to make the switch to the next oplog. This function
   * enables us to reuse the byte buffer which got created for an oplog which no
   * longer permits us to use itself. It will also take acre of compaction if
   * required
   * 
   * @param entry
   *          DiskEntry object representing the current Entry
   * @throws IOException
   * @throws InterruptedException
   */
  private void basicModify(DiskRegionView dr, DiskEntry entry,
                           DiskEntry.Helper.ValueWrapper value,
                           byte userBits, boolean async,
                           boolean calledByCompactor)
    throws IOException, InterruptedException
  {
    DiskId id = entry.getDiskId();
    boolean useNextOplog = false;
    long startPosForSynchOp = -1L;
    int adjustment = 0;
    Oplog emptyOplog = null;
    if (DiskStoreImpl.KRF_DEBUG) {
      // wait for cache close to create krf
      System.out.println("basicModify KRF_DEBUG");
      Thread.sleep(1000);
    }
    synchronized (this.lock) {
//     synchronized (this.crf) {
      if (getOplogSet().getChild() != this) {
        useNextOplog = true;
      } else {
        final int valueLength = value.size();
        // can't use value buffer after handing it over to opState (except
        //   logging or if useNextOplog=true when OpState ops are skipped here)
        initOpState(OPLOG_MOD_ENTRY_1ID, dr, entry, value, valueLength,
            userBits, false);
        adjustment = getOpStateSize();
        assert adjustment > 0;
        long temp = (this.crf.currSize + adjustment);
        if (temp > getMaxCrfSize() && !isFirstRecord()) {
          switchOpLog(dr, adjustment, entry, false);
          // reset the OpState without releasing the value
          this.opState.reset();
          // we can't reuse it since it contains variable length data
          useNextOplog = true;
        }
        else {
          if (this.lockedForKRFcreate) {
            throw new CacheClosedException("The disk store is closed.");
          }
          this.firstRecord = false;
//       {
//         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//         l.info(LocalizedStrings.DEBUG, "modify setting size to=" + temp
//                + " oplog#" + getOplogId());
//       }
          long oldOplogId;
          // do the io while holding lock so that switch can set doneAppending
          // Write the data to the opLog for the synch mode
          startPosForSynchOp = writeOpLogBytes(this.crf, async, true);
//           if (this.crf.currSize != startPosForSynchOp) {
//             LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//             l.info(LocalizedStrings.DEBUG, "currSize=" + this.crf.currSize
//                    + " startPosForSynchOp=" + startPosForSynchOp
//                    + " oplog#" + getOplogId());
//             assert false;
//           }
          this.crf.currSize = temp;
//       {
//         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//         l.info(LocalizedStrings.DEBUG, "modify stratPosForSyncOp=" + startPosForSynchOp
//                + " oplog#" + getOplogId());
//       }
//            if (temp != this.crf.raf.getFilePointer()) {
//              LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//              l.info(LocalizedStrings.DEBUG, "modify setting size to=" + temp
//                     + " fp=" + this.crf.raf.getFilePointer()
//                     + " oplog#" + getOplogId());
//            }
          startPosForSynchOp += getOpStateValueOffset();
          if (DiskStoreImpl.TRACE_WRITES) {
            VersionTag tag = null;
            if (entry.getVersionStamp()!=null) {
              tag = entry.getVersionStamp().asVersionTag();
            }
            this.logger.info(LocalizedStrings.DEBUG,
                             "TRACE_WRITES basicModify: id=<" + abs(id.getKeyId())
                             + "> key=<" + entry.getKeyCopy() + ">"
                             + " valueOffset=" + startPosForSynchOp
                             + " userBits=" + userBits
                             + " valueLen=" + valueLength
                             + " valueBytes=<" + value + ">"
                             + " drId=" + dr.getId()
                             + " versionStamp=" + tag
                             + " oplog#" + getOplogId());
          }
          if (EntryBits.isNeedsValue(userBits)) {
            id.setValueLength(valueLength);
          } else {
            id.setValueLength(0);
          }
          id.setUserBits(userBits);
          if (this.logger.finerEnabled()) {
            this.logger
              .finer("Oplog::basicModify:About to Release ByteBuffer with data for Disk ID = "
                     + id.toString());
          }
          if (this.logger.finerEnabled()) {
            this.logger
              .finer("Oplog::basicModify:Released ByteBuffer with data for Disk ID = "
                     + id.toString());
          }
          synchronized (id) {
            // Need to do this while synced on id
            // now that we compact forward to most recent oplog.
            // @todo darrel: The sync logic in the disk code is so complex
            // a really doubt is is correct.
            // I think we need to do a fresh rewrite of it.
            oldOplogId = id.setOplogId(getOplogId());
            if(EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits)) {
              id.setOffsetInOplog(-1);
            } else {
              id.setOffsetInOplog(startPosForSynchOp);
            }
          }
          // Set the oplog size change for stats
//       {
//         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//         l.info(LocalizedStrings.DEBUG, "modify inc=" + adjustment);
//       }
          this.dirHolder.incrementTotalOplogSize(adjustment);
          this.incTotalCount();

          // getKeyCopy() is a potentially expensive operation in GemFireXD so
          // qualify with EntryLogger.isEnabled() first
          if (EntryLogger.isEnabled()) {
            EntryLogger.logPersistPut(dr.getName(), entry.getKeyCopy(),
                dr.getDiskStoreID());
          }
          if (oldOplogId != getOplogId()) {
            Oplog oldOplog = getOplogSet().getChild(oldOplogId);
            if (oldOplog != null) {
              oldOplog.rmLive(dr, entry);
              emptyOplog = oldOplog;
            }
            addLive(dr, entry);
            // Note if this mod was done to oldOplog then this entry is already in
            // the linked list. All we needed to do in this case is call incTotalCount
          } else {
            getOrCreateDRI(dr).update(entry);
          }
          
          //Update the region version vector for the disk store.
          //This needs to be done under lock so that we don't switch oplogs
          //unit the version vector accurately represents what is in this oplog
          RegionVersionVector rvv = dr.getRegionVersionVector();
          final VersionStamp<?> version;
          if (rvv != null && (version = entry.getVersionStamp()) != null) {
            //TODO: Asif: Temporary fix for Bug #47395.
            //We need to find out the actual cause as to why the DiskEntry's Version Tag does not contain DiskStoreID
            if(version.getMemberID() == null) {
              version.setMemberID(dr.getDiskStoreID());
            }
            rvv.recordVersion(version.getMemberID(), version.getRegionVersion(), null);
          }
          clearOpState();
        }
      }
    }
    if (useNextOplog) {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterSwitchingOplog();
      }
      Assert.assertTrue(getOplogSet().getChild() != this);
      getOplogSet().getChild().basicModify(dr, entry, value, userBits, async,
                                         calledByCompactor);
    }
    else {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance()
          .afterSettingOplogOffSet(startPosForSynchOp);
      }
      if (emptyOplog != null
          && (!emptyOplog.isCompacting() || emptyOplog.calledByCompactorThread())) {
        if (calledByCompactor && emptyOplog.hasNoLiveValues()) {
          // Since compactor will only append to crf no need to flush drf.
          // Before we have the compactor delete an oplog it has emptied out
          // we want to have it flush anything it has written to the current oplog.
          // Note that since sync writes may be done to the same oplog we are doing
          // async writes to any sync writes will cause a flush to be done immediately.
          flushAll(true);
        }
        emptyOplog.handleNoLiveValues();
      }
    }
  }
 
  private void basicSaveConflictVersionTag(DiskRegionView dr, VersionTag tag, boolean async)
  throws IOException, InterruptedException
  {
    boolean useNextOplog = false;
    int adjustment = 0;
    synchronized (this.lock) {
      if (getOplogSet().getChild() != this) {
        useNextOplog = true;
      } else {
        this.opState.initialize(OPLOG_CONFLICT_VERSION, dr.getId(), tag);
        adjustment = getOpStateSize();
        assert adjustment > 0;
        long temp = (this.crf.currSize + adjustment);
        if (temp > getMaxCrfSize() && !isFirstRecord()) {
          switchOpLog(dr, adjustment, null, false);
          // reset the OpState without releasing the value
          this.opState.reset();
          // we can't reuse it since it contains variable length data
          useNextOplog = true;
        }
        else {
          if (this.lockedForKRFcreate) {
            throw new CacheClosedException("The disk store is closed.");
          }
          this.firstRecord = false;
          writeOpLogBytes(this.crf, async, true);
          this.crf.currSize = temp;
          if (DiskStoreImpl.TRACE_WRITES) {
            this.logger.info(LocalizedStrings.DEBUG,
                "TRACE_WRITES basicSaveConflictVersionTag:"
                + " drId=" + dr.getId()
                + " versionStamp=" + tag
                + " oplog#" + getOplogId());
          }
          this.dirHolder.incrementTotalOplogSize(adjustment);
          //Update the region version vector for the disk store.
          //This needs to be done under lock so that we don't switch oplogs
          //unit the version vector accurately represents what is in this oplog
          RegionVersionVector rvv = dr.getRegionVersionVector();
          if(rvv != null) {
            rvv.recordVersion(tag.getMemberID(), tag.getRegionVersion(), null);
          }
          clearOpState();
        }
      }
    }
    if (useNextOplog) {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterSwitchingOplog();
      }
      Assert.assertTrue(getOplogSet().getChild() != this);
      getOplogSet().getChild().basicSaveConflictVersionTag(dr, tag, async);
    }
  }

  private void basicCopyForwardForOfflineCompact(long oplogKeyId,
                                                 byte[] keyBytes,
                                                 byte[] valueBytes,
                                                 byte userBits,
                                                 long drId, 
                                                 VersionTag tag,
                                                 long lastModifiedTime,
                                                 final long currentTime)
    throws IOException, InterruptedException
  {
    boolean useNextOplog = false;
    long startPosForSynchOp = -1L;
    int adjustment = 0;
    synchronized (this.lock) {
//     synchronized (this.crf) {
      if (getOplogSet().getChild() != this) {
        useNextOplog = true;
      } else {
        this.opState.initialize(oplogKeyId, keyBytes, valueBytes, userBits,
            drId, tag, lastModifiedTime, false);
        adjustment = getOpStateSize();
        assert adjustment > 0;
        long temp = (this.crf.currSize + adjustment);
        if (temp > getMaxCrfSize() && !isFirstRecord()) {
          switchOpLog(null, adjustment, null, false);
          // reset the OpState without releasing the value
          this.opState.reset();
          // we can't reuse it since it contains variable length data
          useNextOplog = true;
        } else {
          this.firstRecord = false;
          // do the io while holding lock so that switch can set doneAppending
          // Write the data to the opLog async since we are offline compacting
          startPosForSynchOp = writeOpLogBytes(this.crf, true, true);
          this.crf.currSize = temp;
          startPosForSynchOp += getOpStateValueOffset();
          getOplogSet().getChild().writeOneKeyEntryForKRF(keyBytes, userBits,
              valueBytes.length, drId, oplogKeyId, startPosForSynchOp, tag,
              lastModifiedTime, currentTime);
          if (DiskStoreImpl.TRACE_WRITES) {
            this.logger.info(LocalizedStrings.DEBUG,
                             "TRACE_WRITES basicCopyForwardForOfflineCompact: id=<" + oplogKeyId
                             + "> keyBytes=<" + baToString(keyBytes) + ">"
                             + " valueOffset=" + startPosForSynchOp
                             + " userBits=" + userBits
                             + " valueLen=" + valueBytes.length
                             + " valueBytes=<" + baToString(valueBytes) + ">"
                             + " drId=" + drId
                             + " oplog#" + getOplogId());
          }
          this.dirHolder.incrementTotalOplogSize(adjustment);
          this.incTotalCount();
          clearOpState();
        }
      }
    }
    if (useNextOplog) {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterSwitchingOplog();
      }
      Assert.assertTrue(getOplogSet().getChild() != this);
      getOplogSet().getChild().basicCopyForwardForOfflineCompact(oplogKeyId,
          keyBytes, valueBytes, userBits, drId, tag, lastModifiedTime,
          currentTime);
    }
  }

  private boolean isCompacting() {
    return this.compacting;
  }
  
  private void addLive(DiskRegionView dr, DiskEntry de) {
//     logger.info(LocalizedStrings.DEBUG, "DEBUG: addLive oplog#" + getOplogId()
//                 + " de=" + de);
    getOrCreateDRI(dr).addLive(de);
    incLiveCount();
  }
  private void rmLive(DiskRegionView dr, DiskEntry de) {
    if (getOrCreateDRI(dr).rmLive(de)) {
//       logger.info(LocalizedStrings.DEBUG, "DEBUG: rmLive oplog#" + getOplogId()
//                   + " de=" + de);
      decLiveCount();
    }
  }
  
  private DiskRegionInfo getDRI(Long drId) {
    return this.regionMap.get(drId);
  }
  private DiskRegionInfo getDRI(DiskRegionView dr) {
    return getDRI(dr.getId());
  }
  public DiskRegionInfo getOrCreateDRI(DiskRegionView dr) {
    DiskRegionInfo dri = getDRI(dr);
    if (dri == null) {
      dri = (isCompactionPossible() || couldHaveKrf())
        ? new DiskRegionInfoWithList(dr, couldHaveKrf(), this.krfCreated.get())
        : new DiskRegionInfoNoList(dr);
      DiskRegionInfo oldDri = this.regionMap.putIfAbsent(dr.getId(), dri);
      if (oldDri != null) {
        dri = oldDri;
      }
    }
    return dri;
  }
  
  public boolean needsKrf() {
    return couldHaveKrf() && !krfCreated.get();
  }

  /**
   * @return true if this Oplog could end up having a KRF file.
   */
  private boolean couldHaveKrf() {
    return getOplogSet().couldHaveKrf();
  }

  private DiskRegionInfo getOrCreateDRI(Long drId) {
    DiskRegionInfo dri = getDRI(drId);
    if (dri == null) {
      dri = (isCompactionPossible() || couldHaveKrf())
        ? new DiskRegionInfoWithList(null, couldHaveKrf(), this.krfCreated.get())
        : new DiskRegionInfoNoList(null);
      DiskRegionInfo oldDri = this.regionMap.putIfAbsent(drId, dri);
      if (oldDri != null) {
        dri = oldDri;
      }
    }
    return dri;
  }
  
  /**
   * Removes the key/value pair with the given id on disk.
   * 
   * @param entry
   *          DiskEntry object on which remove operation is called
   */
  public final void remove(LocalRegion region, DiskEntry entry, boolean async, boolean isClear)
  {
    DiskRegion dr = region.getDiskRegion();
    if (getOplogSet().getChild() != this) {
      getOplogSet().getChild().remove(region, entry, async, isClear);
    }
    else {
      DiskId did = entry.getDiskId();
      boolean exceptionOccured = false;
      byte prevUsrBit = did.getUserBits();
      int len = did.getValueLength();
      try {
        basicRemove(dr, entry, async, isClear);
      }
      catch (IOException ex) {
        exceptionOccured = true;
        getParent().getCancelCriterion().checkCancelInProgress(ex);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, dr.getName());
      }
      catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        region.getCancelCriterion().checkCancelInProgress(ie);
        exceptionOccured = true;
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, dr.getName());
      }
      finally {
        if (exceptionOccured) {
          did.setValueLength(len);
          did.setUserBits(prevUsrBit);
        }

      }

    }
  }
  
  /**
   * Write the GC RVV for a single region to disk 
   */
  public final void writeGCRVV(DiskRegion dr) {
    boolean useNextOplog = false;
    synchronized (this.lock) {
      if (getOplogSet().getChild() != this) {
        useNextOplog = true;
      } else {
        try {
          writeRVVRecord(
              this.drf,
              Collections.<Long, AbstractDiskRegion> singletonMap(dr.getId(), dr),
              true);
        } 
        catch (IOException ex) {
          dr.getCancelCriterion().checkCancelInProgress(ex);
          throw new DiskAccessException(
              LocalizedStrings.Oplog_FAILED_RECORDING_RVV_BECAUSE_OF_0.toLocalizedString(this.diskFile
                  .getPath()), ex, dr.getName());
        }
      }
    }
    if(useNextOplog) {
      getOplogSet().getChild().writeGCRVV(dr);
    } else {
      DiskStoreObserver.endWriteGCRVV(dr);
    }
  }

  /**
   *  There're 3 cases to use writeRVV:
   *  1) endGII: DiskRegion.writeRVV(region=null, true), Oplog.writeRVV(true,null)
   *  2) beginGII: DiskRegion.writeRVV(region=this, false), Oplog.writeRVV(false,sourceRVV!=null)
   *  3) clear: DiskRegion.writeRVV(region=this, null), Oplog.writeRVV(null,sourceRVV!=null)
   */
  public void writeRVV(DiskRegion dr, RegionVersionVector sourceRVV, Boolean isRVVTrusted) {
    boolean useNextOplog = false;
    synchronized (this.lock) {
      if (getOplogSet().getChild() != this) {
        useNextOplog = true;
      } else {
      
        try {
          //We'll update the RVV of the disk region while holding the lock on the oplog,
          //to make sure we don't switch oplogs while we're in the middle of this.
          if (sourceRVV != null) {
            dr.getRegionVersionVector().recordVersions(sourceRVV,  null);
          } else {
            // it's original EndGII, not to write duplicate rvv if its trusted
            if (dr.getRVVTrusted()) {
              return;
            }
          }
          if (isRVVTrusted != null) {
            // isRVVTrusted == null means "as is"
            dr.setRVVTrusted(isRVVTrusted);
          }
          writeRVVRecord(
              this.crf,
              Collections.<Long, AbstractDiskRegion> singletonMap(dr.getId(), dr),
              false);
        } 
        catch (IOException ex) {
          dr.getCancelCriterion().checkCancelInProgress(ex);
          throw new DiskAccessException(
              LocalizedStrings.Oplog_FAILED_RECORDING_RVV_BECAUSE_OF_0.toLocalizedString(this.diskFile
                  .getPath()), ex, dr.getName());
        }
      }
    }
    if(useNextOplog) {
      getOplogSet().getChild().writeRVV(dr, sourceRVV, isRVVTrusted);
    }
  }

  private long getMaxCrfSize() {
    return this.maxCrfSize;
  }
  private long getMaxDrfSize() {
    return this.maxDrfSize;
  }
  private void setMaxCrfDrfSize() {
    int crfPct = Integer.getInteger("gemfire.CRF_MAX_PERCENTAGE", 90);
    if (crfPct > 100 || crfPct < 0) {
      crfPct = 90;
    }
    this.maxCrfSize = (long)(this.maxOplogSize * (crfPct / 100.0));
    this.maxDrfSize = this.maxOplogSize - this.maxCrfSize;
  }
  /**
   * 
   * Asif: A helper function which identifies whether to record a removal of
   * entry in the current oplog or to make the switch to the next oplog. This
   * function enables us to reuse the byte buffer which got created for an oplog
   * which no longer permits us to use itself. It will also take acre of
   * compaction if required
   * 
   * @param entry
   *          DiskEntry object representing the current Entry
   * @throws IOException
   * @throws InterruptedException
   */
  private void basicRemove(DiskRegionView dr, DiskEntry entry, boolean async, boolean isClear)
    throws IOException, InterruptedException
  {
    DiskId id = entry.getDiskId();

    boolean useNextOplog = false;
    long startPosForSynchOp = -1;
    Oplog emptyOplog = null;
    if (DiskStoreImpl.KRF_DEBUG) {
      // wait for cache close to create krf
      System.out.println("basicRemove KRF_DEBUG");
      Thread.sleep(1000);
    }
    synchronized (this.lock) {
      if (getOplogSet().getChild() != this) {
        useNextOplog = true;
      } else if ((this.drf.currSize + MAX_DELETE_ENTRY_RECORD_BYTES)
                 > getMaxDrfSize() && !isFirstRecord()) {
        switchOpLog(dr, MAX_DELETE_ENTRY_RECORD_BYTES, entry, false);
        useNextOplog = true;
      } else {
        if (this.lockedForKRFcreate) {
          throw new CacheClosedException("The disk store is closed.");
        }
        long oldOplogId = id.setOplogId(getOplogId());
        if(!isClear) {
          this.firstRecord = false;
          // Ok now we can go ahead and find out its actual size
          // This is the only place to set notToUseUserBits=true
          initOpState(OPLOG_DEL_ENTRY_1ID, dr, entry,
              DiskEntry.Helper.NULL_VW, 0, (byte)0, true);
          int adjustment = getOpStateSize();

          this.drf.currSize += adjustment;
          // do the io while holding lock so that switch can set doneAppending
          if (this.logger.finerEnabled()) {
            this.logger
            .finer(" Oplog::basicRemove: Recording the Deletion of entry in the Oplog with id = "
                + getOplogId()
                + " The Oplog Disk ID for the entry being deleted ="
                + id + " Mode is Synch");
          }

          // Write the data to the opLog for the synch mode
          // @todo if we don't sync write destroys what will happen if
          // we do 1. create k1 2. destroy k1 3. create k1?
          // It would be possible for the crf to be flushed but not the drf.
          // Then during recovery we will find identical keys with different entryIds.
          // I think we can safely have drf writes be async as long as we flush the drf
          // before we flush the crf.
          // However we can't have removes by async if we are doing a sync write
          // because we might be killed right after we do this write.
          startPosForSynchOp = writeOpLogBytes(this.drf, async, true);
          setHasDeletes(true);
          if (DiskStoreImpl.TRACE_WRITES) {
            this.logger.info(LocalizedStrings.DEBUG,
                "TRACE_WRITES basicRemove: id=<" + abs(id.getKeyId())
                + "> key=<" + entry.getKeyCopy() + ">"
                + " drId=" + dr.getId()
                + " oplog#" + getOplogId());
          }

          //                            new RuntimeException("STACK"));
          if (this.logger.finerEnabled()) {
            this.logger
            .finer("Oplog::basicRemove:About to Release ByteBuffer for Disk ID = "
                + id.toString());
          }

          if (this.logger.finerEnabled()) {
            this.logger
            .finer("Oplog::basicRemove:Released ByteBuffer for Disk ID = "
                + id.toString());
          }
          this.dirHolder.incrementTotalOplogSize(adjustment);
        }
        //Set the oplog size change for stats
        //       {
        //         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
        //         l.info(LocalizedStrings.DEBUG, "rm inc=" + adjustment);
        //       }
        id.setOffsetInOplog(-1);

        // getKeyCopy() is a potentially expensive operation in GemFireXD so
        // qualify with EntryLogger.isEnabled() first
        if (EntryLogger.isEnabled()) {
          EntryLogger.logPersistDestroy(dr.getName(), entry.getKeyCopy(),
              dr.getDiskStoreID());
        }
        {
          Oplog rmOplog = null;
          if (oldOplogId == getOplogId()) {
            rmOplog = this;
          } else {
            rmOplog = getOplogSet().getChild(oldOplogId);
          }
          if (rmOplog != null) {
            rmOplog.rmLive(dr, entry);
            emptyOplog = rmOplog;
          }
        }
        clearOpState();
      }
    }
    if (useNextOplog) {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance().afterSwitchingOplog();
      }
      Assert.assertTrue(getOplogSet().getChild() != this);
      getOplogSet().getChild().basicRemove(dr, entry, async, isClear);
    } else {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        CacheObserverHolder.getInstance()
          .afterSettingOplogOffSet(startPosForSynchOp);
      }
      if (emptyOplog != null
          && (!emptyOplog.isCompacting() || emptyOplog.calledByCompactorThread())) {
        emptyOplog.handleNoLiveValues();
      }
    }
  }
  
//   /**
//    * This is only used for an assertion check.
//    */
//   private long lastWritePos = -1;

  /**
   * test hook
   */
  final ByteBuffer getWriteBuf() {
    return this.crf.outputStream.getInternalBuffer();
  }
//  private final void flushNoSync(OplogFile olf) throws IOException {
//    flushAllNoSync(false); // @todo
//    //flush(olf, false);
//  }
  private final void flushAndSync(OplogFile olf) throws IOException {
    flushAll(false); // @todo
    //flush(olf, true);
  }

  private final void flush(OplogFile olf, boolean doSync, boolean dofsync)
      throws IOException {
    try {
      synchronized (this.lock/*olf*/) {
        if (olf.RAFClosed) {
          //         logger.info(LocalizedStrings.DEBUG, "DEBUG: no need to flush because RAFClosed"
          //                     + " oplog#" + getOplogId()
          //                     + ((olf==this.crf)? "crf" : "drf"));
          return;
        }
//        ByteBuffer bb = olf.writeBuf;
        //       logger.info(LocalizedStrings.DEBUG, "DEBUG: flush "
        //                   + " oplog#" + getOplogId()
        //                   + " bb.position()=" + ((bb != null) ? bb.position() : "null")
        //                   + ((olf==this.crf)? "crf" : "drf"));
//        if (bb != null && bb.position() != 0) {
//          bb.flip();
//          int flushed = 0;
//          do {
            //           {
            //             byte[] toPrint = new byte[bb.remaining()];
            //             for (int i=0; i < bb.remaining(); i++) {
            //               toPrint[i] = bb.get(i);
            //             }
            //             logger.info(LocalizedStrings.DEBUG, "DEBUG: flush writing bytes at offset=" + olf.bytesFlushed
            //                         + " position=" + olf.channel.position()
            //                         + " bytes=" + baToString(toPrint)
            //                         + " oplog#" + getOplogId()
            //                         + ((olf==this.crf)? "crf" : "drf"));
            //           }
//            flushed += olf.channel.write(bb);
            //            logger.info(LocalizedStrings.DEBUG, "DEBUG: flush bytesFlushed=" + olf.bytesFlushed
            //                        + " position=" + olf.channel.position()
            //                        + " oplog#" + getOplogId()
            //                        + ((olf==this.crf)? "crf" : "drf"));
//          } while (bb.hasRemaining());
          // update bytesFlushed after entire writeBuffer is flushed to fix bug 41201
//          olf.bytesFlushed += flushed;
//          bb.clear();
//        }
        final OplogFile.FileChannelOutputStream outputStream = olf.outputStream;
        if (outputStream != null) {
          outputStream.flush();
        }
      }
      if (doSync) {
        if (dofsync && !DiskStoreImpl.DISABLE_SYNC_WRITES_FOR_TESTS) {
          // Synch Meta Data as well as content
          olf.channel.force(true);
        }
      }
    } catch (ClosedChannelException ignore) {
      // It is possible for a channel to be closed when our code does not
      // explicitly call channel.close (when we will set RAFclosed).
      // This can happen when a thread is doing an io op and is interrupted.
      // That thread will see ClosedByInterruptException but it will also
      // close the channel and then we will see ClosedChannelException.
    }
  }

  public final void flushAll() {
    flushAll(false);
  }
  
  public final void flushAllNoSync(boolean skipDrf) {
    flushAll(skipDrf, false);
  }
  public final void flushAll(boolean skipDrf) {
    flushAll(skipDrf, true/*doSync*/);
  }

  public final void flushAll(boolean skipDrf, boolean doSync) {
    flushAll(skipDrf, doSync, getParent().getSyncWrites());
  }

  public final void flushAll(boolean skipDrf, boolean doSync, boolean dofsync) {
    try {
//       if (!skipDrf) {
      // @todo if skipDrf then only need to do drf if crf has flushable data
        flush(this.drf, doSync, dofsync);
//       }
      flush(this.crf, doSync, dofsync);
    } catch (IOException ex) {
      getParent().getCancelCriterion().checkCancelInProgress(ex);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, getParent());
    }
  }

  final void flushAllAndSync() {
    lockCompactor();
    try {
      flushAll(false, true, true);
    } finally {
      unlockCompactor();
    }
  }
  
  final void flushAllAndSync(boolean noCompactorLock) {
    flushAll(false, true, true);
  }
  

  /**
   * Asif: Since the ByteBuffer being writen to can have additional bytes which
   * are used for extending the size of the file, it is necessary that the
   * ByteBuffer provided should have limit which is set to the position till
   * which it contains the actual bytes. If the mode is synched write then only
   * we will write up to the capacity & opLogSpace variable have any meaning.
   * For asynch mode it will be zero. Also this method must be synchronized on
   * the file , whether we use synch or asynch write because the fault in
   * operations can clash with the asynch writing. Write the specified bytes to
   * the oplog. Note that since extending a file is expensive this code will
   * possibly write OPLOG_EXTEND_SIZE zero bytes to reduce the number of times
   * the file is extended.
   * 
   *
   * @param olf the file to write the bytes to
   * @return The long offset at which the data present in the ByteBuffer gets
   *         written to
   */
  private long writeOpLogBytes(OplogFile olf, boolean async, boolean doFlushIfSync) throws IOException
  {
    long startPos;
    synchronized (this.lock/*olf*/) {
      Assert.assertTrue(!this.doneAppending);
      if (this.closed) {
        Assert.assertTrue(
                          false,
                          "The Oplog " + this.oplogId
                          + " for store " + getParent().getName()
                          + " has been closed for synch mode while writing is going on. This should not happen");
      }
      // Asif : It is assumed that the file pointer is already at the
      // appropriate position in the file so as to allow writing at the end.
      // Any fault in operations will set the pointer back to the write location.
      // Also it is only in case of synch writing, we are writing more
      // than what is actually needed, we will have to reset the pointer.
      // Also need to add in offset in writeBuf in case we are not flushing writeBuf
      startPos = olf.outputStream.fileOffset();
//                 logger.info(LocalizedStrings.DEBUG, "writeOpLogBytes"
//                             + " position=" + olf.channel.position()
//                             + " writeBufPos=" + olf.writeBuf.position()
//                             + " startPos=" + startPos
//                             + " " + getFileType(olf) + "#" + getOplogId()
//                             + " opStateSize=" + getOpStateSize()
//                             + this.opState.debugStr());
//       Assert.assertTrue(startPos > lastWritePos,
//                         "startPos=" + startPos +
//                         " was not > lastWritePos=" + lastWritePos);
      long bytesWritten = this.opState.write(olf);
      if (!async && doFlushIfSync) {
        flushAndSync(olf);
      }
      getStats().incWrittenBytes(bytesWritten, async);
      
//       // Moved the set of lastWritePos to after write
//       // so if write throws an exception it will not be updated.
//       // This fixes bug 40449.
//       this.lastWritePos = startPos;
    }
    return startPos;
  }

  boolean isRAFOpen() {
    return !this.crf.RAFClosed; // volatile read
  }
  private boolean okToReopen;
  boolean closeRAF() {
    if (this.beingRead) return false;
    synchronized (this.lock/*crf*/) {
      if (this.beingRead) return false;
      if (!this.doneAppending) return false;
      if (this.crf.RAFClosed) {
        return false;
      } else {
        final OplogFile.FileChannelOutputStream stream = crf.outputStream;
        try {
          if (stream != null) {
            stream.flush();
          }
        } catch (IOException ignore) {
        }
        try {
          this.crf.raf.close();
        } catch (IOException ignore) {
        }
        this.crf.RAFClosed = true;
        this.okToReopen = true;
        this.stats.decOpenOplogs();
        return true;
      }
    }
  }
  private volatile boolean beingRead;
  /**
   * If crfRAF has been closed then attempt to reopen the oplog for this read.
   * Verify that this only happens when test methods are invoked.
   * @return true if oplog file is open and can be read from; false if not
   */
  private boolean reopenFileIfClosed() throws IOException {
    synchronized (this.lock/*crf*/) {
      boolean result = !this.crf.RAFClosed;
      if (!result && this.okToReopen) {
        result = true;
        this.crf.raf = new RandomAccessFile(this.crf.f, "r");
        this.stats.incOpenOplogs();
        this.crf.RAFClosed = false;
        this.crf.channel = this.crf.raf.getChannel();
        this.okToReopen = false;
      }
      return result;
    }
  }
  
  private BytesAndBits attemptGet(DiskRegionView dr, long offsetInOplog, boolean bitOnly,
      int valueLength, byte userBits) throws IOException {
    boolean didReopen = false;
    boolean accessedInactive = false;
    try {
      synchronized (this.lock/*crf*/) {
//         if (this.closed || this.deleted.get()) {
//           throw new DiskAccessException("attempting get on "
//                                         + (this.deleted.get() ? "destroyed" : "closed")
//                                         + " oplog #" + getOplogId(), this.owner);
//         }
        this.beingRead = true;
        final long readPosition = offsetInOplog;
        if (/*!getParent().isSync() since compactor groups writes
              && */ (readPosition+valueLength) > this.crf.bytesFlushed
            && !this.closed) {
          flushAllNoSync(true); // fix for bug 41205
        }
        try {
          RandomAccessFile myRAF;
          FileChannel crfChannel;
          if (this.crf.RAFClosed) {
            myRAF = new RandomAccessFile(this.crf.f, "r");
            crfChannel = myRAF.getChannel();
            this.stats.incOpenOplogs();
            if (this.okToReopen) {
              this.crf.RAFClosed = false;
              this.okToReopen = false;
              this.crf.raf = myRAF;
              this.crf.channel = crfChannel;
              didReopen = true;
            }
          } else {
            myRAF = this.crf.raf;
            crfChannel = this.crf.channel;
            accessedInactive = true;
          }
          BytesAndBits bb;
          try {
            final long writePosition = (this.doneAppending)
              ? this.crf.bytesFlushed
              : myRAF.getFilePointer();
            if ((readPosition+valueLength) > writePosition) {
//               logger.info(LocalizedStrings.DEBUG, "DEBUG: crfSize=" + this.crf.currSize
//                           + " fp=" + myRAF.getFilePointer()
//                           + " rp=" + readPosition
//                           + " oplog#" + getOplogId());
              throw new DiskAccessException(LocalizedStrings.Oplog_TRIED_TO_SEEK_TO_0_BUT_THE_FILE_LENGTH_IS_1_OPLOG_FILE_OBJECT_USED_FOR_READING_2.toLocalizedString(new Object[] {readPosition+valueLength,
                  writePosition, "file=" + this.crf.f.getPath() + " size=" + myRAF.getChannel().size() +
                  " flushed=" + this.crf.bytesFlushed + " fp=" + myRAF.getFilePointer() +
                  " doneAppends=" + doneAppending}), dr.getName());
            }
            else if (readPosition < 0) {
              throw new DiskAccessException(LocalizedStrings.Oplog_CANNOT_FIND_RECORD_0_WHEN_READING_FROM_1.toLocalizedString(new Object[] {offsetInOplog, this.diskFile.getPath()}), dr.getName());
            }
            try {
              myRAF.seek(readPosition);
//               {
//                 LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//                 l.info(LocalizedStrings.DEBUG, "after seek rp=" + readPosition
//                        + " fp=" + myRAF.getFilePointer()
//                        + " oplog#" + getOplogId());
//               }
              this.stats.incOplogSeeks();
              // should account faulted in values so use allocator here
              final ByteBuffer valueBuffer = GemFireCacheImpl
                  .getCurrentBufferAllocator().allocate(valueLength, "OPLOG");
              while (valueBuffer.hasRemaining()) {
                if (crfChannel.read(valueBuffer) <= 0) throw new EOFException();
              }
              valueBuffer.flip();
              if (DiskStoreImpl.TRACE_READS) {
                logger.info(LocalizedStrings.DEBUG,
                    "TRACE_READS attemptGet readPosition=" + readPosition
                        + " valueLength=" + valueLength
                        + " value=<" + ClientSharedUtils.toString(valueBuffer) + ">"
                        + " oplog#" + getOplogId());
              }
              this.stats.incOplogReads();
              bb = new BytesAndBits(valueBuffer, userBits);
              // also set the product version for an older product
              final Version version = getProductVersionIfOld();
              if (version != null) {
                bb.setVersion(version);
              }
            }
            finally {
              // if this oplog is no longer being appended to then don't waste disk io
              if (!this.doneAppending) {
                // by seeking back to writePosition
                myRAF.seek(writePosition);
//               {
//                 LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//                 l.info(LocalizedStrings.DEBUG, "after seek wp=" + writePosition
//                        + " position=" + this.crf.channel.position()
//                        + " fp=" + myRAF.getFilePointer()
//                        + " oplog#" + getOplogId());
//               }
                this.stats.incOplogSeeks();
              }
            }
            return bb;
          } finally {
            if (myRAF != this.crf.raf) {
              try {
                myRAF.close();
              } catch (IOException ignore) {
              }
            }
          }
        } finally {
          this.beingRead = false;
//           if (this.closed || this.deleted.get()) {
//             throw new DiskAccessException("attempting get on "
//                                           + (this.deleted.get() ? "destroyed" : "closed")
//                                           + " oplog #" + getOplogId(), this.owner);
//           }
        }
      } // sync
    } finally {
      if (accessedInactive) {
        getOplogSet().inactiveAccessed(this);
      } else if (didReopen) {
        getOplogSet().inactiveReopened(this);
      }
    }
  }
  
  /**
   * Asif: Extracts the Value byte array & UserBit from the OpLog
   * 
   * @param offsetInOplog
   *          The starting position from which to read the data in the opLog
   * @param bitOnly
   *          boolean indicating whether the value needs to be extracted along
   *          with the UserBit or not.
   * @param valueLength
   *          The length of the byte array which represents the value
   * @param userBits
   *          The userBits of the value.
   * @return BytesAndBits object which wraps the extracted value & user bit
   */
  private BytesAndBits basicGet(DiskRegionView dr, long offsetInOplog, boolean bitOnly,
                                int valueLength, byte userBits)
  {
    BytesAndBits bb = null;
    if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits) || bitOnly || valueLength == 0) {
      if (EntryBits.isInvalid(userBits)) {
        bb = new BytesAndBits(DiskEntry.Helper.INVALID_BUFFER, userBits);
      } else if (EntryBits.isTombstone(userBits)) {
        bb = new BytesAndBits(DiskEntry.Helper.TOMBSTONE_BUFFER, userBits);
      } else {
        bb = new BytesAndBits(DiskEntry.Helper.LOCAL_INVALID_BUFFER, userBits);
      }
    }
    else {
      if (offsetInOplog == -1) return null;
      try {
        for (;;) {
          dr.getCancelCriterion().checkCancelInProgress(null);
          boolean interrupted = Thread.interrupted();
          try {
            bb = attemptGet(dr, offsetInOplog, bitOnly, valueLength, userBits);
            break;
          }
          catch (InterruptedIOException e) { // bug 39756
            // ignore, we'll clear and retry.
          }
          finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        } // for
      }
      catch (IOException ex) {
        getParent().getCancelCriterion().checkCancelInProgress(ex);
        throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_READING_FROM_0_OPLOGID_1_OFFSET_BEING_READ_2_CURRENT_OPLOG_SIZE_3_ACTUAL_FILE_SIZE_4_IS_ASYNCH_MODE_5_IS_ASYNCH_WRITER_ALIVE_6
            .toLocalizedString(
                new Object[] {
                    this.diskFile.getPath(),
                    Long.valueOf(this.oplogId),
                    Long.valueOf(offsetInOplog),
                    Long.valueOf(this.crf.currSize),
                    Long.valueOf(this.crf.bytesFlushed),
                    Boolean.valueOf(!dr.isSync()),
                    Boolean.valueOf(false)
                }), ex, dr.getName());
      }
      catch (IllegalStateException ex) {
        checkClosed();
        throw ex;
      }
    }
    return bb;
  }
  
  
  /**
   * Asif: Extracts the Value byte array & UserBit from the OpLog and inserts it
   * in the wrapper Object of type BytesAndBitsForCompactor which is passed
   * 
   * @param offsetInOplog
   *                The starting position from which to read the data in the
   *                opLog
   * @param bitOnly
   *                boolean indicating whether the value needs to be extracted
   *                along with the UserBit or not.
   * @param valueLength
   *                The length of the byte array which represents the value
   * @param userBits
   *                The userBits of the value.
   * @param wrapper
   *                Object of type BytesAndBitsForCompactor. The data is set in the
   *                wrapper Object. The wrapper Object also contains the user
   *                bit associated with the entry
   * @return true if data is found false if not
   */
  private boolean basicGetForCompactor(DiskRegionView dr, long offsetInOplog, boolean bitOnly,
      int valueLength, byte userBits, BytesAndBitsForCompactor wrapper) {
    if (EntryBits.isAnyInvalid(userBits) || EntryBits.isTombstone(userBits) || bitOnly || valueLength == 0) {
      if (EntryBits.isInvalid(userBits)) {
        wrapper.setData(DiskEntry.INVALID_BYTES, userBits,
                        DiskEntry.INVALID_BYTES.length, false /* Cannot be reused */);
      } else if (EntryBits.isTombstone(userBits)) {
        wrapper.setData(DiskEntry.TOMBSTONE_BYTES, userBits,
                        DiskEntry.TOMBSTONE_BYTES.length, false /* Cannot be reused */);
      } else {
        wrapper.setData(DiskEntry.LOCAL_INVALID_BYTES, userBits,
                        DiskEntry.LOCAL_INVALID_BYTES.length, false /* Cannot be reused */);
      }
    }
    else {
      try {
        synchronized (this.lock/*crf*/) {
          final long readPosition = offsetInOplog;
          if (/*!getParent().isSync() since compactor groups writes
                && */ (readPosition+valueLength) > this.crf.bytesFlushed
              && !this.closed) {
            flushAllNoSync(true); // fix for bug 41205
          }
          if (!reopenFileIfClosed()) {
            return false; // fix for bug 40648
          }
          final long writePosition = (this.doneAppending)
            ? this.crf.bytesFlushed
            : this.crf.raf.getFilePointer();
          if ((readPosition+valueLength) > writePosition) {
            throw new DiskAccessException(
              LocalizedStrings.Oplog_TRIED_TO_SEEK_TO_0_BUT_THE_FILE_LENGTH_IS_1_OPLOG_FILE_OBJECT_USED_FOR_READING_2.toLocalizedString(
              new Object[] {readPosition+valueLength, writePosition, this.crf.raf}), dr.getName());
          }
          else if (readPosition < 0) {
            throw new DiskAccessException(
              LocalizedStrings.Oplog_CANNOT_FIND_RECORD_0_WHEN_READING_FROM_1
                .toLocalizedString(
                                   new Object[] { Long.valueOf(offsetInOplog), this.diskFile.getPath()}), dr.getName());
          }
//           if (this.closed || this.deleted.get()) {
//             throw new DiskAccessException("attempting get on "
//                                           + (this.deleted.get() ? "destroyed" : "closed")
//                                           + " oplog #" + getOplogId(), this.owner);
//           }
          try {
            this.crf.raf.seek(readPosition);
//               {
//                 LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//                 l.info(LocalizedStrings.DEBUG, "after seek rp=" + readPosition
//                        + " fp=" + this.crf.raf.getFilePointer()
//                        + " oplog#" + getOplogId());
//               }
            this.stats.incOplogSeeks();
            byte[] valueBytes = null;
            if (wrapper.getBytes().length < valueLength) {
              valueBytes = new byte[valueLength];
              this.crf.raf.readFully(valueBytes);
            }
            else {
              valueBytes = wrapper.getBytes();
              this.crf.raf.readFully(valueBytes, 0, valueLength);
            }
//             this.logger.info(LocalizedStrings.DEBUG, "DEBUG: basicGetForCompact readPosition="
//                              + readPosition
//                              + " length=" + valueLength
//                              + " valueBytes=" + baToString(valueBytes));
            this.stats.incOplogReads();
            Version version = getProductVersionIfOld();
            if (version != null) {
              wrapper.setVersion(version);
            }
            wrapper.setData(valueBytes, userBits, valueLength, true);
          }
          finally {
            // if this oplog is no longer being appended to then don't waste disk io
            if (!this.doneAppending) {
              this.crf.raf.seek(writePosition);
//               {
//                 LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//                 l.info(LocalizedStrings.DEBUG, "after seek wp=" + writePosition
//                        + " position=" + this.crf.channel.position()
//                        + " fp=" + this.crf.raf.getFilePointer()
//                        + " oplog#" + getOplogId());
//               }
              this.stats.incOplogSeeks();
            }
//             if (this.closed || this.deleted.get()) {
//               throw new DiskAccessException("attempting get on "
//                                             + (this.deleted.get() ? "destroyed" : "closed")
//                                             + " oplog #" + getOplogId(), this.owner);
//             }
          }
        }
      }
      catch (IOException ex) {
        getParent().getCancelCriterion().checkCancelInProgress(ex);
        throw new DiskAccessException(
          LocalizedStrings.Oplog_FAILED_READING_FROM_0_OPLOG_DETAILS_1_2_3_4_5_6
          .toLocalizedString(
                             new Object[] {  this.diskFile.getPath(), Long.valueOf(this.oplogId), Long.valueOf(offsetInOplog), Long.valueOf(this.crf.currSize), Long.valueOf(this.crf.bytesFlushed), Boolean.valueOf(/*!dr.isSync() @todo */false), Boolean.valueOf(false)}), ex, dr.getName());

      }
      catch (IllegalStateException ex) {
        checkClosed();
        throw ex;
      }
    }
    return true;
  } 

  private final AtomicBoolean deleted = new AtomicBoolean();
  
  /**
   * deletes the oplog's file(s)
   */
  void deleteFiles(boolean crfOnly) {
    // try doing the removeOplog unconditionally since I'm see an infinite loop
    // in destroyOldestReadyToCompact
    boolean needsDestroy = this.deleted.compareAndSet(false, true);
    if (needsDestroy) {
      // I don't under stand why the compactor would have anything to do with
      // an oplog file that we are removing from disk.
      // So I'm commenting out the following if
      //         if (!isCompactionPossible()) {
      // moved this from close to fix bug 40574
      // If we get to the point that it is ok to close the file
      // then we no longer need the parent to be able to find this
      // oplog using its id so we can unregister it now.
      // If compaction is possible then we need to leave this
      // oplog registered with the parent and allow the compactor to unregister it.
      //         }

    
      deleteCRF();
      if (!crfOnly || !getHasDeletes()) {
        setHasDeletes(false);
        deleteDRF();
        // no need to call removeDrf since parent removeOplog did it
        //getParent().removeDrf(this);
//         getParent().oplogSetRemove(this);
      }

      this.idxkrf.deleteIRF(null);
      //Fix for bug 42495 - Don't remove the oplog from this list
      //of oplogs until it has been removed from the init file. This guarantees
      //that if the oplog is in the init file, the backup code can find it and 
      //try to back it up.
      boolean addToDrfOnly = crfOnly && getHasDeletes();
      getOplogSet().removeOplog(getOplogId(), true, addToDrfOnly ? this : null);
    } else if (!crfOnly && getHasDeletes()) {
      setHasDeletes(false);
      deleteDRF();
      getOplogSet().removeDrf(this); 
//       getParent().oplogSetRemove(this);
    }
    
  }

  public void deleteCRF() {
    oplogSet.crfDelete(this.oplogId);
    DiskStoreBackup inProgressBackup = getParent().getInProgressBackup();
    if(inProgressBackup == null || !inProgressBackup.deferCrfDelete(this)) {
      deleteCRFFileOnly();
    }
  }
  
  public void deleteCRFFileOnly() {
    deleteFile(this.crf);
    // replace .crf at the end with .krf
    if (this.crf.f != null) {
      final File krf = new File(this.crf.f.getAbsolutePath().replaceFirst(
          "\\" + CRF_FILE_EXT + "$", KRF_FILE_EXT));
      if (!krf.exists()) {
        return;
      }
      getParent().executeDelayedExpensiveWrite(new Runnable() {
        public void run() {
          if (!krf.delete()) {
            if (krf.exists()) {
              logger.warning(LocalizedStrings.Oplog_DELETE_FAIL_0_1_2, new Object[] {
                  Oplog.this.toString(),
                  "krf",
                  getParent().getName()});
            }
          } else {
            if (logger.infoEnabled()) {
              logger.info(LocalizedStrings.Oplog_DELETE_0_1_2, new Object[] {
                  Oplog.this.toString(), "krf", getParent().getName() });
            }
          }
        }
      });
    }
  }
  
  public void deleteDRF() {
    getOplogSet().drfDelete(this.oplogId);
    DiskStoreBackup inProgressBackup = getParent().getInProgressBackup();
    if(inProgressBackup == null || !inProgressBackup.deferDrfDelete(this)) {
      deleteDRFFileOnly();
    }
  }
  
  public void deleteDRFFileOnly() {
    deleteFile(this.drf);
  }
  
  /**
   * Returns "crf" or "drf".
   */
  private static String getFileType(OplogFile olf) {
    String name = olf.f.getName();
    int index = name.lastIndexOf('.');
    return name.substring(index+1);
  }
  
  private void deleteFile(final OplogFile olf) {
    synchronized(this.lock) {
      if (olf.currSize != 0) {
        this.dirHolder.decrementTotalOplogSize(olf.currSize);
        olf.currSize = 0;
      }
      if (olf.f == null) return;
      if (!olf.f.exists()) return;
      assert olf.RAFClosed == true;
      if (!olf.RAFClosed || olf.raf != null) {
        try {
          olf.raf.close();
          olf.RAFClosed = true;
        } catch (IOException ignore) {
        }
      }
      
      //Delete the file asynchronously. Based on perf testing, deletes
      //can block at the filesystem level. See #50254
      //It's safe to do this asynchronously, because we have already
      //marked this file as deleted in the init file.
      //Note - could we run out of disk space because the compaction thread is
      //doing this and creating files? For a real fix, you probably need a bounded
      //queue
      getParent().executeDelayedExpensiveWrite(new Runnable() {
        public void run() { 
          if (!olf.f.delete() && olf.f.exists()) {
            logger.warning(LocalizedStrings.Oplog_DELETE_FAIL_0_1_2, new Object[] {Oplog.this.toString(),
                getFileType(olf),
                getParent().getName()});
          }
          else if (logger.infoEnabled()) {
            logger.info(LocalizedStrings.Oplog_DELETE_0_1_2,
                new Object[] {Oplog.this.toString(),
                getFileType(olf),
                getParent().getName()});
            // logger.info(LocalizedStrings.DEBUG, "DEBUG deleteFile " + olf.f,
            // new RuntimeException("STACK"));
          }
        }
      });
    }
  }

  /**
   * Helper function for the test
   * 
   * @return FileChannel object representing the Oplog
   */
  FileChannel getFileChannel()
  {
    return this.crf.channel;
  }


  DirectoryHolder getDirectoryHolder()
  {
    return this.dirHolder;
  }

  /**
   * The current size of Oplog. It may be less than the actual Oplog file size (
   * in case of asynch writing as it also takes into account data present in
   * asynch buffers which will get flushed in course of time o
   * 
   * @return long value indicating the current size of the oplog.
   */
  long getOplogSize()
  {
    // logger.info(LocalizedStrings.DEBUG, "getOplogSize crfSize=" +
    // this.crf.currSize + " drfSize=" + this.drf.currSize);
    return this.crf.currSize + this.drf.currSize;
  }

  boolean isOplogEmpty() {
    return this.crf.currSize <= OPLOG_DISK_STORE_REC_SIZE
      && this.drf.currSize <= OPLOG_DISK_STORE_REC_SIZE;
  }

  void incLiveCount() {
    this.totalLiveCount.incrementAndGet();
  }

  private void decLiveCount() {
    this.totalLiveCount.decrementAndGet();
  }

  /**
   * Return true if a record (crf or drf) has been added to this oplog
   */
  boolean hasBeenUsed() {
    return this.hasDeletes.get() || this.totalCount.get() > 0;
  }
  
  void incTotalCount() {
    if (!isPhase2()) {
      this.totalCount.incrementAndGet();
    }
  }
  private void finishedAppending() {
    synchronized (this.lock/*crf*/) {
      this.doneAppending = true;
    }
    handleNoLiveValues();
    // I'm deadcoding the following because it is not safe unless we change to
    // always recover values. If we don't recover values then
    // an oplog we recovered from may still need to fault values in from memory.
//     if (!getParent().isOverflowEnabled()) {
//       // If !overflow then we can close the file even
//       // when it has recent values because
//       // we will never need to fault values in from this
//       // file since they are all in memory.
//       close();
//     }
  }

  boolean needsCompaction() {
//     logger.info(LocalizedStrings.DEBUG,
//                 "DEBUG isCompactionPossible=" + isCompactionPossible());
    if (!isCompactionPossible()) return false;
//     logger.info(LocalizedStrings.DEBUG,
//                 "DEBUG unrecoveredRegionCount=" + this.unrecoveredRegionCount.get());
    if (this.unrecoveredRegionCount.get() > 0) return false;
//     logger.info(LocalizedStrings.DEBUG,
//                 "DEBUG compactionThreshold=" + parent.getCompactionThreshold());
    if (parent.getCompactionThreshold() == 100) return true;
    if (parent.getCompactionThreshold() == 0) return false;
    // otherwise check if we have enough garbage to collect with a compact
    long rvHWMtmp = this.totalCount.get();
//     logger.info(LocalizedStrings.DEBUG, "DEBUG rvHWM=" + rvHWMtmp);
    if (rvHWMtmp > 0) {
      long tlc = this.totalLiveCount.get();
      if (tlc < 0) {
        tlc = 0;
      }
      double rv = tlc;
//       logger.info(LocalizedStrings.DEBUG, "DEBUG rv=" + rv);
      double rvHWM = rvHWMtmp;
      if (((rv / rvHWM) * 100) <= parent.getCompactionThreshold()) {
        return true;
      }
    } else {
      return true;
    }
    return false;
  }

  public boolean hadLiveEntries() {
    return this.totalCount.get() != 0;
  }
  public boolean hasNoLiveValues() {
    return this.totalLiveCount.get() <= 0
      // if we have an unrecoveredRegion then we don't know how many liveValues we have
      && this.unrecoveredRegionCount.get() == 0
      && !getParent().isOfflineCompacting();
  }

  private void handleEmptyAndOldest(boolean calledByCompactor) {
    if (!calledByCompactor) {
      if (logger.infoEnabled()) {
        logger.info(LocalizedStrings.DEBUG,
                    "Deleting oplog early because it is empty. It is for disk store "
                    + getParent().getName() + " and has oplog#" + oplogId);
      }
    }
    
    destroy();
    getOplogSet().destroyOldestReadyToCompact();
  }
  private void handleEmpty(boolean calledByCompactor) {
    lockCompactor();
    try {
      if (!calledByCompactor) {
        if (logger.infoEnabled()) {
          logger.info(LocalizedStrings.Oplog_CLOSING_EMPTY_OPLOG_0_1,
                      new Object[] {getParent().getName(), toString()});
        }
      }
      cancelKrf();
      close();
      deleteFiles(getHasDeletes());
    } finally {
      unlockCompactor();
    }
  }
    

  void cancelKrf() {
    createKrf(true);
  }

  private final static ThreadLocal isCompactorThread = new ThreadLocal();

  private boolean calledByCompactorThread() {
    if (!this.compacting) return false;
    Object v = isCompactorThread.get();
    return v != null && v == Boolean.TRUE;
  }
  
  private void handleNoLiveValues() {
//     logger.info(LocalizedStrings.DEBUG, "DEBUG handleNoLiveValues"
//                 + " count=" + this.totalLiveCount.get()
//                 + " totalCount=" + this.totalCount.get()
//                 + " doneAppending=" + this.doneAppending
//                 + " needsCompaction=" + needsCompaction());
    if (!this.doneAppending) return;
    if (hasNoLiveValues()) {
      if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
        if (calledByCompactorThread()) {
          // after compaction, remove the oplog from the list & destroy it
          CacheObserverHolder.getInstance().beforeDeletingCompactedOplog(this);
        } else {
          CacheObserverHolder.getInstance().beforeDeletingEmptyOplog(this);
        }
      }
      if (isOldest()) {
        if (calledByCompactorThread()) {
          // do it in this compactor thread
          handleEmptyAndOldest(true);
        } else {
          // schedule another thread to do it
          getParent().executeDiskStoreTask(new Runnable() {
            public void run() {
              handleEmptyAndOldest(false);
            }
          });
        }
      } else {
        if (calledByCompactorThread()) {
          // do it in this compactor thread
          handleEmpty(true);
        } else {
          // schedule another thread to do it
          getParent().executeDiskStoreTask(new Runnable() {
            public void run() {
              handleEmpty(false);
            }
          });
        }
      }
    } else if (needsCompaction()) {
      addToBeCompacted();
    }
  }

  /**
   * Return true if this oplog is the oldest one of those ready to compact
   */
  private boolean isOldest() {
    long myId = getOplogId();
    return getOplogSet().isOldestExistingOplog(myId);
  }

  private boolean added = false;
  private synchronized void addToBeCompacted() {
    if (this.added) return;
    this.added = true;
    getOplogSet().addToBeCompacted(this);
    if (this.logger.fineEnabled()) {
      this.logger.fine("Oplog::switchOpLog: Added the Oplog = " + this.oplogId
                       + " for compacting. ");
    }
  }
  
  private DiskEntry initRecoveredEntry(DiskRegionView drv, DiskEntry de) {
    addLive(drv, de);
    return de;
  }
  /**
   * The oplogId in re points to the oldOplogId.
   * "this" oplog is the current oplog.
   */
  private void updateRecoveredEntry(DiskRegionView drv, DiskEntry de, DiskEntry.RecoveredEntry re) {
    if (getOplogId() != re.getOplogId()) {
      Oplog oldOplog = getOplogSet().getChild(re.getOplogId());
      oldOplog.rmLive(drv, de);
      initRecoveredEntry(drv, de);
    } else {
      getDRI(drv).update(de);
    }
  }

  public void prepareForCompact() {
    this.compacting = true;
  }

  private final Lock compactorLock = new ReentrantLock();

  public void lockCompactor() {
    this.compactorLock.lock();
  }
  public void unlockCompactor() {
    this.compactorLock.unlock();
  }
  /**
   * Copy any live entries last stored in this oplog to the current oplog.
   * No need to copy deletes in the drf.
   * Backup only needs them until all the older crfs are empty.
   */
  public int compact(OplogCompactor compactor) {
    if (!needsCompaction()) {
      return 0; // @todo check new logic that deals with not compacting oplogs which have unrecovered regions
    }
    isCompactorThread.set(Boolean.TRUE);
    assert calledByCompactorThread();
    getParent().acquireCompactorReadLock();
    try {
      if (!compactor.keepCompactorRunning()) {
        return 0;
      }
    lockCompactor();
    try {
      if (hasNoLiveValues()) {
//         logger.info(LocalizedStrings.DEBUG, "DEBUG oplog#" + getOplogId()
//                     + " hasNoLiveValues()=" + hasNoLiveValues());
        handleNoLiveValues();
        return 0; // do this while holding compactorLock
      }

    //logger.info(LocalizedStrings.DEBUG, "DEBUG compacting " + this, new RuntimeException("STACK"));
    //Asif:Start with a fresh wrapper on every compaction so that 
    //if previous run used some high memory byte array which was
    // exceptional, it gets garbage collected.
    long opStart = getStats().getStatTime();
    BytesAndBitsForCompactor wrapper = new BytesAndBitsForCompactor();

    DiskEntry de;
    DiskEntry lastDe = null;
    boolean compactFailed = /*getParent().getOwner().isDestroyed
                              || */ !compactor.keepCompactorRunning();
    int totalCount = 0;
    final ByteArrayDataInput in = new ByteArrayDataInput();
    final HeapDataOutputStream hdos = new HeapDataOutputStream(
        Version.CURRENT);
    for (DiskRegionInfo dri: this.regionMap.values()) {
      final DiskRegionView dr = dri.getDiskRegion();
      if (dr == null) continue;
      boolean didCompact = false;
      while ((de = dri.getNextLiveEntry()) != null) {
        //logger.info(LocalizedStrings.DEBUG, "DEBUG compact de=" + de);
        if (/*getParent().getOwner().isDestroyed
              ||*/ !compactor.keepCompactorRunning()) {
          compactFailed = true;
          break;
        }
        if (lastDe != null) {
                  if (lastDe == de) {
//                     logger.info(LocalizedStrings.DEBUG, "DEBUG duplicate entry " + de
//                                 + " didCompact=" + didCompact
//                                 + " le=" + this.liveEntries
//                                 + " leNext=" + this.liveEntries.getNext()
//                                 + " dePrev=" + de.getPrev()
//                                 + " deNext=" + de.getNext());
                    throw new IllegalStateException("compactor would have gone into infinite loop");
                  }
          assert lastDe != de;
        }
        lastDe = de;
        didCompact = false;
        synchronized (de) { // fix for bug 41797
        DiskId did = de.getDiskId();
        assert did != null;
        synchronized (did) {
          long oplogId = did.getOplogId();
          if (oplogId != getOplogId()) {
//             logger.info(LocalizedStrings.DEBUG, "DEBUG compact skipping#2 entry " + de + " because it is in oplog#" + oplogId + " instead of oplog#" + getOplogId());
            continue;
          }
          boolean toCompact = getBytesAndBitsForCompaction(dr, de, wrapper);
          if (toCompact) {
            byte[] valueBytes = wrapper.getBytes();
            int length = wrapper.getValidLength();
            byte userBits = wrapper.getBits();
            // TODO: compaction needs to get version?
            if (oplogId != did.getOplogId()) {
              // @todo: Is this even possible? Perhaps I should just assert here
              // skip this guy his oplogId changed
              if (!wrapper.isReusable()) {
                wrapper = new BytesAndBitsForCompactor();
              }
              //                 logger.info(LocalizedStrings.DEBUG, "DEBUG compact skipping#3 entry because it is in oplog#" + oplogId + " instead of oplog#" + getOplogId());
              continue;
            }
            Version version = wrapper.getVersion();
            if (version != null && !Version.CURRENT.equals(version)) {
              if (logger.finerEnabled()) {
                logger.finer("Oplog " + this + " Converting "
                    + valueBytes.length + " bytes from version "
                    + version + " to version " + Version.CURRENT);
              }
              final StaticSystemCallbacks sysCb = GemFireCacheImpl
                  .getInternalProductCallbacks();
              if (sysCb != null) {
                byte[] newValueBytes = sysCb.fromVersionToBytes(
                    valueBytes, length, EntryBits.isSerialized(userBits),
                    version, in, hdos);
                if (valueBytes != newValueBytes) {
                  valueBytes = newValueBytes;
                  length = newValueBytes.length;
                }
              }
            }
            // write it to the current oplog
            getOplogSet().getChild().copyForwardModifyForCompact(dr, de,
                DiskEntry.Helper.wrapBytes(valueBytes, length), userBits);
            // the did's oplogId will now be set to the current active oplog
            didCompact = true;
          }
        } // did
        } // de
        if (didCompact) {
          totalCount++;
          getStats().endCompactionUpdate(opStart);
          opStart = getStats().getStatTime();
          //Asif: Check if the value byte array happens to be any of the constant
          //static byte arrays or references the value byte array of underlying RegionEntry.
          // If so for  preventing data corruption across regions  
          //( in case of static byte arrays) & for RegionEntry, 
          //recreate the wrapper
          if (!wrapper.isReusable()) {
            wrapper = new BytesAndBitsForCompactor();
          }
        }
      }
    }
    
    if (!compactFailed) {
//       logger.info(LocalizedStrings.DEBUG, "DEBUG totalCount="+totalCount);
      if (totalCount == 0) {
        // Need to still remove the oplog even if it had nothing to compact.
        handleNoLiveValues();
      }

      // We can't assert hasNoLiveValues() because a race condition exists
      // in which our liveEntries list is empty but the liveCount has not
      // yet been decremented.
    }

    return totalCount;
    } finally {
      unlockCompactor();
      }
    } finally {
      getParent().releaseCompactorReadLock();
      assert calledByCompactorThread();
      isCompactorThread.remove();
    }
  }
  
  public static boolean isCRFFile(String filename) {
    return filename.endsWith(Oplog.CRF_FILE_EXT);
  }
  
  public static boolean isDRFFile(String filename) {
    return filename.endsWith(Oplog.DRF_FILE_EXT);
  }
  
  public static boolean isIRFFile(String filename) {
    return Oplog.IDX_PATTERN.matcher(filename).matches();
  }

  public static String getKRFFilenameFromCRFFilename(String crfFilename) {
    return crfFilename.substring(0, crfFilename.length() - Oplog.CRF_FILE_EXT.length()) + Oplog.KRF_FILE_EXT; 
  }

  long testGetOplogFileLength() throws IOException {
    long result = 0;
    if (this.crf.raf != null) {
      result += this.crf.raf.length();
    }
    if (this.drf.raf != null) {
      result += this.drf.raf.length();
    }
    return result;
  }

  /**
   * This method is called by the async value recovery
   * task to recover the values from the crf if the
   * keys were recovered from the krf.
   * If the defer regions argument is non-null, then disk regions that are marked to be
   * deferred ({@link DiskRegionFlag#DEFER_RECOVERY}) are skipped from recovery
   * and those regions are filled in the passed map as the result.
   * @param diskRecoveryStores
   * @return if further recovery is possible or not.
   * This is to avoid unnecessary SortedLiveEntries if UMM limit has been reached.
   */
  public boolean recoverValuesIfNeeded(
      Map<Long, DiskRecoveryStore> diskRecoveryStores,
      Map<Long, DiskRecoveryStore> deferredRegions, Object sync) {
    //Early out if we start closing the parent.
    if (getParent().isClosing() || diskRecoveryStores.isEmpty()
        || this.regionMap.isEmpty()) {
      return true;
    }

    List<KRFEntry> sortedLiveEntries;

    ObjectObjectHashMap<Long, DiskRegionInfo> targetRegions =
        ObjectObjectHashMap.from(this.regionMap);
    synchronized (sync) {
      //Don't bother to include any stores that have reached the lru limit
      Iterator<DiskRecoveryStore> itr = diskRecoveryStores.values().iterator();
      while(itr.hasNext()) {
        DiskRecoveryStore store = itr.next();
        if(store.lruLimitExceeded()) {
          itr.remove();
        }
      }

      // Get the sorted list of live entries from the target regions
      Iterator<Long> targetItr = targetRegions.keySet().iterator();
      while (targetItr.hasNext()) {
        Long diskRegionId = targetItr.next();
        DiskRecoveryStore drs = diskRecoveryStores.get(diskRegionId);
        if (drs == null) {
          if (this.logger.fineEnabled()) {
            this.logger.fine("Oplog::recoverValuesIfNeeded: skipping region "
                + "with null disk info for id=" + diskRegionId);
          }
          targetItr.remove();
        }
        /*
        else if (deferredRegions != null
            && drs.getDiskRegionView().getFlags()
                .contains(DiskRegionFlag.DEFER_RECOVERY)) {
          if (this.logger.fineEnabled()) {
            this.logger.fine("Oplog::recoverValuesIfNeeded: skipping region "
                + "deferred for value recovery: " + drs);
          }
          targetItr.remove();
          deferredRegions.put(diskRegionId, drs);
        }
        */
        else {
          if (this.logger.fineEnabled()) {
            this.logger.fine("Oplog::recoverValuesIfNeeded: will try "
                + "to recover for region: " + drs);
          }
        }
      }
    }

    if (targetRegions.isEmpty()) {
      // no regions to recover
      return true;
    }

    sortedLiveEntries = getSortedLiveEntries(targetRegions.values());
    if(sortedLiveEntries == null) {
      //There are no live entries in this oplog to recover.
      return true;
    }

    if (this.logger.infoEnabled()) {
      this.logger.info(LocalizedStrings.ONE_ARG,
          "Oplog::recoverValuesIfNeeded: recovering values from " + toString());
    }

    for(KRFEntry entry : sortedLiveEntries) {
      //Early out if we start closing the parent.
      if(getParent().isClosing() || diskRecoveryStores.isEmpty()) {
        return true;
      }

      DiskEntry diskEntry = entry.getDiskEntry();
      DiskRegionView diskRegionView = entry.getDiskRegionView();
      Long diskRegionId = diskRegionView.getId();

      //TODO DAN ok, here's what we need to do
      // 1) lock and obtain the correct RegionEntry that we are recovering too. 
      //    this will likely mean obtaining the correct DiskRecoveryStore, since with
      //    that we can find the region entry I believe.
      // 2) Make sure that the lru limit is not exceeded
      // 3) Update the region entry with the value from disk, assuming the value from
      //    disk is still valid. That is going to be something like 

      synchronized (sync) {
        DiskRecoveryStore diskRecoveryStore = diskRecoveryStores.get(diskRegionId);
        if(diskRecoveryStore == null) {
          continue;
        }
        if(diskRecoveryStore.lruLimitExceeded()) {
          diskRecoveryStores.remove(diskRegionId);
          continue;
        }

        if (CallbackFactoryProvider.getStoreCallbacks().shouldStopRecovery()) {
          diskRecoveryStores.remove(diskRegionId);
          this.logger.info(LocalizedStrings.ONE_ARG,
              "Oplog::recoverValuesIfNeeded: stopping recovery of " +
                  diskRegionId + " as memory consumed is 90% of maxStorageSize");
          // Returning false here as all regions will face the same memory constraint.
          return false;
        }

        synchronized(diskEntry) {
          //Make sure the entry hasn't been modified
          if(diskEntry.getDiskId() != null && diskEntry.getDiskId().getOplogId() == oplogId) {
            //dear lord, this goes through a lot of layers. Maybe we should skip some?
            //* specifically, this could end up faulting in from a different oplog, causing 
            //  us to seek.
            //* Also, there may be lock ordering issues here, Really, I guess I want
            //  a flavor of faultInValue that only faults in from this oplog.
            //* We could have some churn here, opening and closing this oplog
            //* We also might not be buffering adjacent entries? Not sure about that one
            
            //* Ideally, this would fault the thing in only if it were in this oplog and the lru limit wasn't hit
            // and it would return a status if the lru limit was hit to make us remove the store.

            try {
              DiskEntry.Helper.recoverValue(diskEntry, getOplogId(),
                  diskRecoveryStore);
            } catch(RegionDestroyedException e) {
              //This region has been destroyed, stop recovering from it.
              diskRecoveryStores.remove(diskRegionId);
            } catch (LowMemoryException lme) {
              this.logger.info(LocalizedStrings.ONE_ARG,
                      "Oplog::recoverValuesIfNeeded: got low memory exception." +
                          "Stopping the recovery " + toString());
              diskRecoveryStores.remove(diskRegionId);
            }
          }
        }
      }
    }
    return true;
  }

  public static String getParentRegionID(DiskRegionView drv) {
    String rpath;
    if (drv.isBucket()) {
      String bn = PartitionedRegionHelper.getBucketName(drv.getName());
      rpath = PartitionedRegionHelper.getPRPath(bn);
    }
    else {
      rpath = drv.getName();
    }
    rpath = LocalRegion.getIDFromPath(rpath, drv.getUUID());
    return (rpath.charAt(0) == '/') ? rpath : ("/" + rpath);
  }

  /**
   * Recover given indexes from the oplog rather than the oplog index files. For
   * latter, use {@link OplogIndex#recoverIndexes}.
   */
  public long recoverIndexes(
      Map<SortedIndexContainer, SortedIndexRecoveryJob> indexes) {

    // Early out if we start closing the parent.
    if (getParent().isClosing() || indexes.isEmpty()
        || this.regionMap.isEmpty()) {
      return 0;
    }

    final LogWriterI18n logger = this.logger;
    final boolean logEnabled = DiskStoreImpl.INDEX_LOAD_DEBUG
        || logger.fineEnabled();

    // store the affected indexes and the parent region against each disk region
    final ObjectObjectHashMap<Long, IndexData[]> indexRecoveryMap =
        ObjectObjectHashMap.withExpectedSize(this.regionMap.size());
    ArrayList<DiskRegionInfo> targetRegions = new ArrayList<DiskRegionInfo>(
        this.regionMap.size());
    this.idxkrf.getDiskIdToIndexDataMap(null, indexes, 0, indexRecoveryMap,
        targetRegions);

    if (targetRegions.isEmpty()) {
      // no index affected
      return 0;
    }

    List<KRFEntry> sortedLiveEntries = getSortedLiveEntries(targetRegions);
    if (sortedLiveEntries == null) {
      // There are no live entries in this oplog to recover.
      return 0;
    }

    if (logger.infoEnabled()) {
      logger.info(LocalizedStrings.ONE_ARG,
          "Oplog#recoverIndexes: recovering values from " + toString());
    }
    if (logEnabled) {
      logger.info(LocalizedStrings.DEBUG,
          "Oplog#recoverIndexes: recovering for oplog=" + toString()
              + ", indexes=" + indexes + ", targetRegions=" + targetRegions);
    }

    long numRecovered = 0;
    int alreadyAccounted = 0;
    for (KRFEntry entry : sortedLiveEntries) {
      // Early out if we start closing the parent.
      if (getParent().isClosing() || indexRecoveryMap.isEmpty()) {
        return numRecovered;
      }

      DiskEntry diskEntry = entry.getDiskEntry();
      DiskRegionView diskRegionView = entry.getDiskRegionView();
      Long diskRegionId = diskRegionView.getId();

      final IndexData[] affectedIndexes = indexRecoveryMap.get(diskRegionId);
      if (affectedIndexes == null) {
        continue;
      }
      final LocalRegion baseRegion = affectedIndexes[0].index.getBaseRegion();
      synchronized (diskEntry) {
        // Make sure the entry hasn't been modified
        final DiskId diskId = diskEntry.getDiskId();
        if (diskId != null && diskId.getOplogId() == oplogId) {

          @Released Object val = null;
          try {
            val = DiskEntry.Helper.getValueOffHeapOrDiskWithoutFaultIn(diskEntry,
                diskRegionView, baseRegion);
            if (val != null && !Token.isInvalidOrRemoved(val)) {
              for (IndexData indexData : affectedIndexes) {
                SortedIndexKey indexKey = indexData.index.getIndexKey(val,
                    diskEntry);
                indexData.getIndex().accountMemoryForIndex(numRecovered, false);
                indexData.indexJob.addJob(indexKey, diskEntry);
              }
              numRecovered++;
            }
          } catch (RegionDestroyedException rde) {
            // This region has been destroyed, stop recovering from it.
            indexRecoveryMap.remove(diskRegionId);
          } finally {
            OffHeapHelper.release(val);
          }
        }
      }
    }

    if (logEnabled || DiskStoreImpl.INDEX_LOAD_PERF_DEBUG) {
      logger.info(LocalizedStrings.DEBUG, "Oplog#recoverIndexes: "
          + "Processed oplog=" + toString() + " for indexes: " + indexes);
    }

    return numRecovered;
  }

  private byte[] serializeRVVs(Map<Long, AbstractDiskRegion> drMap,
      boolean gcRVV) throws IOException {
    HeapDataOutputStream out = new HeapDataOutputStream(Version.CURRENT);
    //Write the size first
    InternalDataSerializer.writeUnsignedVL(drMap.size(), out);
    //Now write regions RVV.
    for(Map.Entry<Long, AbstractDiskRegion> regionEntry: drMap.entrySet()) {
      //For each region, write the RVV for the region.
      
      Long diskRegionID = regionEntry.getKey();
      AbstractDiskRegion dr = regionEntry.getValue();
      RegionVersionVector rvv = dr.getRegionVersionVector();
      if (rvv == null) {
        continue;
      }
      if (DiskStoreImpl.TRACE_WRITES) {
        this.logger.info(LocalizedStrings.DEBUG, "serializeRVVs: isGCRVV="+gcRVV+" drId="+diskRegionID
            +" rvv="+rvv.fullToString()+" oplog#" + getOplogId());
      }

      //Write the disk region id
      InternalDataSerializer.writeUnsignedVL(diskRegionID, out);
      
      if(gcRVV) {
        //For the GC RVV, we will just write the GC versions
        Map<VersionSource, Long> memberToVersion = rvv.getMemberToGCVersion();
        InternalDataSerializer.writeUnsignedVL(memberToVersion.size(), out);
        for(Entry<VersionSource, Long> memberEntry : memberToVersion.entrySet()) {

          //For each member, write the canonicalized member id, 
          //and the version number for that member
          VersionSource member = memberEntry.getKey();
          Long gcVersion = memberEntry.getValue();
          
          int id = getParent().getDiskInitFile().getOrCreateCanonicalId(member);
          InternalDataSerializer.writeUnsignedVL(id, out);
          InternalDataSerializer.writeUnsignedVL(gcVersion, out);
        }
      } else {
        InternalDataSerializer.writeBoolean(dr.getRVVTrusted(), out);
        //Otherwise, we will write the version and exception list for each member
        Map<VersionSource, RegionVersionHolder> memberToVersion = rvv.getMemberToVersion();
        InternalDataSerializer.writeUnsignedVL(memberToVersion.size(), out);
        for(Map.Entry<VersionSource, RegionVersionHolder> memberEntry : memberToVersion.entrySet()) {

          //For each member, right the canonicalized member id, 
          //and the version number with exceptions for that member
          VersionSource member = memberEntry.getKey();
          RegionVersionHolder versionHolder = memberEntry.getValue();
          int id = getParent().getDiskInitFile().getOrCreateCanonicalId(member);
          InternalDataSerializer.writeUnsignedVL(id, out);
          synchronized(versionHolder) {
            InternalDataSerializer.invokeToData(versionHolder, out);
          }
        }
      }
    }
    byte[] rvvBytes = out.toByteArray();
    return rvvBytes;
  }

//   // Comparable code //
//   public int compareTo(Oplog o) {
//     return getOplogId() - o.getOplogId();
//   }
//   public boolean equals(Object o) {
//     if (o instanceof Oplog) {
//       return compareTo((Oplog)o) == 0;
//     } else {
//       return false;
//     }
//   }
//   public int hashCode() {
//     return getOplogId();
//   }
  @Override
  public String toString() {
    return "oplog#" + getOplogId() /* + "DEBUG" + System.identityHashCode(this) */;
    // return this.parent.getName() + "#oplog#" + getOplogId() /* + "DEBUG" + System.identityHashCode(this) */;
  }

  // //////// Methods used during recovery //////////////

  // ////////////////////Inner Classes //////////////////////

  static final class OplogFile {
    public File f;
    public RandomAccessFile raf;
    public volatile boolean RAFClosed = true;
    public FileChannel channel;
    public FileChannelOutputStream outputStream;
    public long currSize;
    public long bytesFlushed;
    public boolean unpreblown;

    final class FileChannelOutputStream
        extends ChannelBufferUnsafeDataOutputStream {

      private final long baseFileOffset;

      public FileChannelOutputStream(int bufferSize) throws IOException {
        super(OplogFile.this.channel, bufferSize);
        this.baseFileOffset = OplogFile.this.channel.position();
      }

      /** Returns the current file offset including the unflushed data. */
      public final long fileOffset() throws IOException {
        return this.baseFileOffset + this.bytesWritten +
            (this.addrPosition - this.baseAddress);
      }

      @Override
      protected int writeBuffer(final ByteBuffer buffer,
          final WritableByteChannel channel) throws IOException {
        int numWritten = super.writeBuffer(buffer, channel);
        if (numWritten > 0) {
          bytesFlushed += numWritten;
        }
        return numWritten;
      }

      @Override
      protected int writeBufferNoWait(final ByteBuffer buffer,
          final WritableByteChannel channel) throws IOException {
        int numWritten = super.writeBufferNoWait(buffer, channel);
        if (numWritten > 0) {
          bytesFlushed += numWritten;
        }
        return numWritten;
      }
    }
  }

  private static class KRFile {
    public File f;
    FileOutputStream fos;
    BufferedOutputStream bos;
    DataOutputStream dos;
    long lastOffset = 0;
    int keyNum = 0;
  }

  private static String baToString(byte[] ba) {
    return baToString(ba, ba != null ? ba.length : 0);
  }
  private static String baToString(byte[] ba, int len) {
    if ( ba == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (int i=0; i < len; i++) {
      sb.append(ba[i]).append(", ");
    }
    return sb.toString();
  }

  void serializeVersionTag(VersionHolder tag, DataOutput out) throws IOException {
    int entryVersion = tag.getEntryVersion();
    long regionVersion = tag.getRegionVersion();
    VersionSource versionMember = tag.getMemberID();
    long timestamp = tag.getVersionTimeStamp();
    int dsId = tag.getDistributedSystemId();
    serializeVersionTag(entryVersion, regionVersion, versionMember, timestamp, dsId, out);
  }

  byte[] serializeVersionTag(VersionTag tag) throws IOException {
    int entryVersion = tag.getEntryVersion();
    long regionVersion = tag.getRegionVersion();
    VersionSource versionMember = tag.getMemberID();
    long timestamp = tag.getVersionTimeStamp();
    int dsId = tag.getDistributedSystemId();
    return serializeVersionTag(entryVersion, regionVersion, versionMember, timestamp, dsId);
  }
  
  byte[] serializeVersionTag(VersionStamp stamp) throws IOException {
    int entryVersion = stamp.getEntryVersion();
    long regionVersion = stamp.getRegionVersion();
    VersionSource versionMember = stamp.getMemberID();
    long timestamp = stamp.getVersionTimeStamp();
    int dsId = stamp.getDistributedSystemId();
    return serializeVersionTag(entryVersion, regionVersion, versionMember, timestamp, dsId);
  }

  private byte[] serializeVersionTag(int entryVersion, long regionVersion,
      VersionSource versionMember, long timestamp, int dsId)
      throws IOException {
    HeapDataOutputStream out = new HeapDataOutputStream(4 + 8 + 4 + 8 + 4, Version.CURRENT);
    serializeVersionTag(entryVersion, regionVersion, versionMember, timestamp, dsId, out);
    byte[] versionsBytes = out.toByteArray();
    return versionsBytes;
  }

  private void serializeVersionTag(int entryVersion, long regionVersion,
      VersionSource versionMember, long timestamp, int dsId, DataOutput out) throws IOException {
    int memberId = getParent().getDiskInitFile().getOrCreateCanonicalId(versionMember);
    InternalDataSerializer.writeSignedVL(entryVersion, out);
    InternalDataSerializer.writeUnsignedVL(regionVersion, out);
    InternalDataSerializer.writeUnsignedVL(memberId, out);
    InternalDataSerializer.writeUnsignedVL(timestamp, out);
    InternalDataSerializer.writeSignedVL(dsId, out);
  }

  /**
   * Holds all the state for the current operation.
   * Since an oplog can only have one operation in progress at any given
   * time we only need a single instance of this class per oplog.
   */
  private class OpState {
    private byte opCode;
    private byte userBits;
    private boolean notToUseUserBits; // currently only DestroyFromDisk will not use userBits
    /**
     * How many bytes it will be when serialized
     */
    private int size;
    private boolean needsValue;
    private DiskEntry.Helper.ValueWrapper value;
    private int valueLength;
    private int drIdLength; // 1..9
    private final byte[] drIdBytes = new byte[DiskInitFile.DR_ID_MAX_BYTES];
    private byte[] keyBytes;
    private final byte[] deltaIdBytes = new byte[8];
    private int deltaIdBytesLength;
    private long newEntryBase;
    private DiskStoreID diskStoreId;

    private byte[] versionsBytes;
    private long lastModifiedTime;
    private int lmtBytes;
    private short gfversion;
//    private int entryVersion;
//    private long regionVersion;
//    private int memberId; // canonicalId of memberID
    
    public final int getSize() {
      return this.size;
    }

    public String debugStr() {
      StringBuilder sb = new StringBuilder();
      sb.append(" opcode=").append(this.opCode)
        .append(" len=").append(this.valueLength)
        .append(" vb=").append(this.value);
      return sb.toString();
    }

    private final void write(OplogFile olf, byte[] bytes, int byteLength) throws IOException {
//      int offset = 0;
//      final int maxOffset = byteLength;
//      ByteBuffer bb = olf.writeBuf;
//      while (offset < maxOffset) {
//
//        int bytesThisTime = maxOffset - offset;
//        boolean needsFlush = false;
//        if (bytesThisTime > bb.remaining()) {
//          needsFlush = true;
//          bytesThisTime = bb.remaining();
//        }
////         logger.info(LocalizedStrings.DEBUG,
////                     "DEBUG offset=" + offset
////                     + " maxOffset=" + maxOffset
////                     + " bytesThisTime=" + bytesThisTime
////                     + " needsFlush=" + needsFlush
////                     + " bb.remaining()=" + bb.remaining());
//        bb.put(bytes, offset, bytesThisTime);
//        offset += bytesThisTime;
//        if (needsFlush) {
//          flushNoSync(olf);
//        }
//      }
      olf.outputStream.write(bytes, 0, byteLength);
    }
    private final void writeByte(OplogFile olf, byte v) throws IOException {
//      ByteBuffer bb = olf.writeBuf;
//      if (1 > bb.remaining()) {
//        flushNoSync(olf);
//      }
//      bb.put(v);
      olf.outputStream.write(v);
    }

    private final void writeOrdinal(OplogFile olf, short ordinal)
        throws IOException {
//      ByteBuffer bb = olf.writeBuf;
//      if (3 > bb.remaining()) {
//        flushNoSync(olf);
//      }
      // don't compress since we setup fixed size of buffers
//      Version.writeOrdinal(bb, ordinal, false);
      Version.writeOrdinal(olf.outputStream, ordinal, false);
    }

    private final void writeInt(OplogFile olf, int v) throws IOException {
//      ByteBuffer bb = olf.writeBuf;
//      if (4 > bb.remaining()) {
//        flushNoSync(olf);
//      }
//      bb.putInt(v);
      olf.outputStream.writeInt(v);
    }

    private final void writeLong(OplogFile olf, long v) throws IOException {
//      ByteBuffer bb = olf.writeBuf;
//      if (8 > bb.remaining()) {
//        flushNoSync(olf);
//      }
//      bb.putLong(v);
      olf.outputStream.writeLong(v);
    }

    private final void writeUnsignedVL(OplogFile olf, long v, int numBytes)
        throws IOException {
//      ByteBuffer bb = olf.writeBuf;
//      if (numBytes > bb.remaining()) {
//        flushNoSync(olf);
//      }
//      InternalDataSerializer.writeUnsignedVL(v, bb);
      InternalDataSerializer.writeUnsignedVL(v, olf.outputStream);
    }

    public void initialize(long newEntryBase) {
      this.opCode = OPLOG_NEW_ENTRY_BASE_ID;
      this.newEntryBase = newEntryBase;
      this.size = OPLOG_NEW_ENTRY_BASE_REC_SIZE;
    }
    public void initialize(short gfversion) {
      this.opCode = OPLOG_GEMFIRE_VERSION;
      this.gfversion = gfversion;
      this.size = OPLOG_GEMFIRE_VERSION_REC_SIZE;
    }

    public void initialize(DiskStoreID diskStoreId) {
      this.opCode = OPLOG_DISK_STORE_ID;
      this.diskStoreId = diskStoreId;
      this.size = OPLOG_DISK_STORE_REC_SIZE;
    }
    
    public void initialize(Map<Long, AbstractDiskRegion> drMap, boolean gcRVV) throws IOException {
      this.opCode = OPLOG_RVV;
      byte[] rvvBytes = serializeRVVs(drMap, gcRVV);
      this.value = DiskEntry.Helper.wrapBytes(rvvBytes);
      //Size is opCode + length + end of record
      this.valueLength = rvvBytes.length;
      this.size = 1 + rvvBytes.length + 1;
    }

    public void initialize(long oplogKeyId,
        byte[] keyBytes,
        byte[] valueBytes,
        byte userBits,
        long drId, 
        VersionTag tag,
        long lastModifiedTime,
        boolean notToUseUserBits) throws IOException {
      this.opCode = OPLOG_MOD_ENTRY_WITH_KEY_1ID;
      this.size = 1;// for the opcode
      saveUserBits(notToUseUserBits, userBits);

      this.keyBytes = keyBytes;
      this.value = DiskEntry.Helper.wrapBytes(valueBytes);
      this.valueLength = valueBytes.length;
      if (this.userBits == 1 && this.valueLength == 0) {
        throw new IllegalStateException("userBits==1 and valueLength is 0");
      }

      this.needsValue = EntryBits.isNeedsValue(this.userBits);
      this.size += (4 + keyBytes.length);
      saveDrId(drId);
      initVersionsBytes(tag, lastModifiedTime);

      if (this.needsValue) {
        this.size += 4 + this.valueLength;
      }
      this.deltaIdBytesLength = 0;
      {
        long delta = calcDelta(oplogKeyId, this.opCode);
        this.deltaIdBytesLength = bytesNeeded(delta);
        this.size += this.deltaIdBytesLength;
        this.opCode += this.deltaIdBytesLength - 1;
        for (int i=this.deltaIdBytesLength-1; i >= 0; i--) {
          this.deltaIdBytes[i] = (byte)(delta & 0xFF);
          delta >>= 8;
        }
      }

      this.size++; // for END_OF_RECORD_ID
    }
    
    private void initVersionsBytes(VersionTag tag, long lastModifiedTime)
        throws IOException {
      if (EntryBits.isWithVersions(this.userBits)) {
        this.versionsBytes = serializeVersionTag(tag);
        this.size += this.versionsBytes.length;
      }
      else {
        // persist last modified time for no-versions case
        this.userBits = EntryBits.setHasLastModifiedTime(this.userBits);
        initLastModifiedTime(lastModifiedTime);
      }
    }

    private void initVersionsBytes(DiskEntry entry) throws IOException {
      // persist entry version, region version and memberId
      // The versions in entry are initialized to 0. So we will not persist the 3 
      // types of data if region version is 0.
      
      // TODO: This method will be called 2 times, one for persisting into crf
      // another for persisting into krf, since we did not save the byte arrary 
      // for the verstion tag. 
      if (EntryBits.isWithVersions(this.userBits)) {
        VersionStamp stamp = entry.getVersionStamp();
        assert (stamp != null);
        this.versionsBytes = serializeVersionTag(stamp);
        this.size += this.versionsBytes.length;
      }
      else {
        // persist last modified time for no-versions case
        this.userBits = EntryBits.setHasLastModifiedTime(this.userBits);
        initLastModifiedTime(entry.getLastModified());
      }
    }

    private void initLastModifiedTime(long lastModifiedTime) {
      if (lastModifiedTime == 0) {
        lastModifiedTime = getParent().getCache().cacheTimeMillis();
      }
      this.lastModifiedTime = lastModifiedTime;
      this.lmtBytes = InternalDataSerializer
          .getUnsignedVLSize(lastModifiedTime);
      this.size += this.lmtBytes;
    }

    public void initialize(byte opCode,
                           DiskRegionView dr,
                           DiskEntry entry,
                           DiskEntry.Helper.ValueWrapper value,
                           int valueLength,
                           byte userBits,
                           boolean notToUseUserBits) throws IOException
    {
      this.opCode = opCode;
      this.size = 1;// for the opcode
      saveUserBits(notToUseUserBits, userBits);
      
      this.value = value;
      this.valueLength = valueLength;
      if (this.userBits == 1 && this.valueLength == 0) {
        throw new IllegalStateException("userBits==1 and valueLength is 0");
      }

      boolean needsKey = false;
      if (this.opCode == OPLOG_MOD_ENTRY_1ID) {
        if (modNeedsKey(entry)) {
          needsKey = true;
          this.opCode = OPLOG_MOD_ENTRY_WITH_KEY_1ID;
        }
        this.needsValue = EntryBits.isNeedsValue(this.userBits);
        initVersionsBytes(entry);
      } else if (this.opCode == OPLOG_NEW_ENTRY_0ID) {
        needsKey = true;
        this.needsValue = EntryBits.isNeedsValue(this.userBits);
        initVersionsBytes(entry);
      } else if (this.opCode == OPLOG_DEL_ENTRY_1ID) {
        needsKey = false;
        this.needsValue = false;
      }

      if (needsKey) {
        Object key = entry.getKeyCopy();
        this.keyBytes = EntryEventImpl.serialize(key);
        this.size += (4 + this.keyBytes.length);
      } else {
        this.keyBytes = null;
      }
      if (this.opCode == OPLOG_DEL_ENTRY_1ID) {
        this.drIdLength = 0;
      } else {
        long drId = dr.getId();
        saveDrId(drId);
      }
      if (this.needsValue) {
        this.size += 4 + this.valueLength;
      }
      this.deltaIdBytesLength = 0;
      if (this.opCode != OPLOG_NEW_ENTRY_0ID) {
//         if (this.opCode == OPLOG_DEL_ENTRY_1ID) {
//           this.newEntryBase = writeDelEntryId/*abs(entry.getDiskId().getKeyId())*/; this.size += 8; // HACK DEBUG
//         } else {
//           this.newEntryBase = writeModEntryId/*abs(entry.getDiskId().getKeyId())*/; this.size += 8; // HACK DEBUG
//         }
        long keyId = entry.getDiskId().getKeyId();
        if(keyId == 0) {
          Assert.fail("Attempting to write an entry with keyId=0 to oplog. Entry key=" + entry.getKey() + " diskId=" + entry.getDiskId() + " region=" + dr);
        }
        long delta = calcDelta(abs(keyId), this.opCode);
        this.deltaIdBytesLength = bytesNeeded(delta);
        this.size += this.deltaIdBytesLength;
        this.opCode += this.deltaIdBytesLength - 1;
        for (int i=this.deltaIdBytesLength-1; i >= 0; i--) {
          this.deltaIdBytes[i] = (byte)(delta & 0xFF);
          delta >>= 8;
        }
      }

      this.size++; // for END_OF_RECORD_ID
    }

    private void saveUserBits(boolean notToUseUserBits, byte userBits) {
      this.notToUseUserBits = notToUseUserBits;
      if (notToUseUserBits) {
        this.userBits = 0;
      } else {
        this.userBits = EntryBits.getPersistentBits(userBits);
        this.size++; // for the userBits        
      }
    }

    private void saveDrId(long drId) {
      // If the drId is <= 255 (max unsigned byte) then
      // encode it as a single byte.
      // Otherwise write a byte whose value is the number of bytes
      // it will be encoded by and then follow it with that many bytes.
      // Note that drId are not allowed to have a value in the range 1..8 inclusive.
      if (drId >= 0 && drId <= 255) {
        this.drIdLength = 1;
        this.drIdBytes[0] = (byte)drId;
      } else {
        byte bytesNeeded = (byte)Oplog.bytesNeeded(drId);
        this.drIdLength = bytesNeeded+1;
        this.drIdBytes[0] = bytesNeeded;
        for (int i=bytesNeeded; i >=1; i--) {
          this.drIdBytes[i] = (byte)(drId & 0xFF);
          drId >>=8;
        }
      }
      this.size += this.drIdLength;
    }

    public void initialize(byte opCode,
        long drId, 
        VersionTag tag) throws IOException {
      this.opCode = opCode;
      assert this.opCode == OPLOG_CONFLICT_VERSION;
      this.size = 1;// for the opcode
      saveDrId(drId);

      this.versionsBytes = serializeVersionTag(tag);
      this.size += this.versionsBytes.length;
      this.size++; // for END_OF_RECORD_ID
    }
    
    /**
     * Returns the offset to the first byte of the value bytes.
     */
    public int getValueOffset() {
      if (!this.needsValue) return 0;
      int result = this.deltaIdBytesLength
//         + 8 /* HACK DEBUG */
        + this.drIdLength
        + 1/* opcode */
        + 4/* value length */;
      if (this.notToUseUserBits == false) {
        result++;
      }
      if (EntryBits.isWithVersions(this.userBits) && this.versionsBytes != null) {
        result += this.versionsBytes.length;
      }
      if (this.lastModifiedTime != 0) {
        result += this.lmtBytes;
      }

      return result;
    }

    public long write(OplogFile olf) throws IOException {
      long bytesWritten = 0;
      writeByte(olf, this.opCode);
      bytesWritten++;
      if (this.opCode == OPLOG_NEW_ENTRY_BASE_ID) {
        writeLong(olf, this.newEntryBase);
        bytesWritten += 8;
      } else if (this.opCode == OPLOG_DISK_STORE_ID) {
        writeLong(olf, this.diskStoreId.getLeastSignificantBits());
        writeLong(olf, this.diskStoreId.getMostSignificantBits());
        bytesWritten += 16;
      } else if (this.opCode == OPLOG_RVV) {
        this.value.write(olf.outputStream);
        bytesWritten += this.valueLength;
      } else if (this.opCode == OPLOG_GEMFIRE_VERSION) {
        writeOrdinal(olf, this.gfversion);
        bytesWritten++;
      } else if (this.opCode == OPLOG_CONFLICT_VERSION) {
        if (this.drIdLength > 0) {
          write(olf, this.drIdBytes, this.drIdLength);
          bytesWritten += this.drIdLength;
        }
        assert this.versionsBytes.length > 0;
        write(olf, this.versionsBytes, this.versionsBytes.length);
        bytesWritten += this.versionsBytes.length;
      } else {
        if (this.notToUseUserBits == false) {
          writeByte(olf, this.userBits);
          bytesWritten++;
        }
        if (this.deltaIdBytesLength > 0) {
          write(olf, this.deltaIdBytes, this.deltaIdBytesLength);
          bytesWritten += this.deltaIdBytesLength;
//           writeLong(olf, this.newEntryBase); bytesWritten += 8; // HACK DEBUG
        }
        if (this.drIdLength > 0) {
          write(olf, this.drIdBytes, this.drIdLength);
          bytesWritten += this.drIdLength;
        }
        if (EntryBits.isWithVersions(this.userBits) && this.versionsBytes != null
            && this.opCode != OPLOG_DEL_ENTRY_1ID) {
          write(olf, this.versionsBytes, this.versionsBytes.length);
          bytesWritten += this.versionsBytes.length;
        }
        if (this.lastModifiedTime != 0) {
          writeUnsignedVL(olf, this.lastModifiedTime, this.lmtBytes);
          bytesWritten += this.lmtBytes;
        }
        if (this.needsValue) {
          writeInt(olf, this.valueLength);
          bytesWritten += 4;
          if (this.valueLength > 0) {
            this.value.write(olf.outputStream);
            bytesWritten += this.valueLength;
          }
        }
        final byte[] keyBytes = this.keyBytes;
        if (keyBytes != null) {
          final int numKeyBytes = keyBytes.length;
          writeInt(olf, numKeyBytes);
          bytesWritten += 4;
          if (numKeyBytes > 0) {
            write(olf, keyBytes, numKeyBytes);
            bytesWritten += numKeyBytes;
          }
        }
      }
      
      writeByte(olf, END_OF_RECORD_ID);
      bytesWritten++;
      return bytesWritten;
    }

    /**
     * Free up any references to possibly large data.
     */
    void reset() {
      this.needsValue = false;
      this.valueLength = 0;
      this.value = null;
      this.keyBytes = null;
      this.notToUseUserBits = false;
      this.versionsBytes = null;
      this.lastModifiedTime = 0;
    }

    /**
     * Free up any references to possibly large data.
     */
    public void clear() {
      if (this.value != null) {
        this.value.release();
        this.value = null;
      }
      reset();
    }
  }
  
  /**
   * Fake disk entry used to implement the circular linked list of entries
   * an oplog has. Each Oplog will have one OplogDiskEntry whose prev and next
   * fields point to the actual DiskEntrys currently stored in its crf.
   * Items are added at "next" so the most recent entry written will be at next
   * and the oldest item written will be at "prev".
   */
  static class OplogDiskEntry implements DiskEntry, RegionEntry {
    private DiskEntry next = this;
    private DiskEntry prev = this;
    
    public synchronized DiskEntry getPrev() {
      return this.prev;
    }
    public synchronized void setPrev(DiskEntry v) {
      this.prev = v;
    }
    public synchronized DiskEntry getNext() {
      return this.next;
    }
    public synchronized void setNext(DiskEntry v) {
      this.next = v;
    }

    /**
     * returns the number of entries cleared
     * @param rvv 
     * @param pendingKrfTags 
     */
    public synchronized int clear(RegionVersionVector rvv, Map<DiskEntry, VersionHolder> pendingKrfTags) {
      if(rvv == null) {
        if(pendingKrfTags != null) {
          pendingKrfTags.clear();
        }
        return clear();
      } else {
        //Clearing the list is handled in AbstractRegionMap.clear for RVV
        //based clears, because it removes each entry.
        //It needs to be handled there because the entry is synched at that point
        return 0;
      }
    }
    
    /**
     * Clear using an RVV. Remove live entries that are contained within
     * the clear RVV.
     * @param pendingKrfTags 
     */
    private int clearWithRVV(RegionVersionVector rvv, Map<DiskEntry, VersionTag> pendingKrfTags) {
      //TODO this doesn't work, because we can end up removing entries from here before
      //they are removed from the region map. Reverting this to the old, leaky, behavior
      //until I fix the region map code.
      return 0;
//      int result = 0;
//      DiskEntry n = getNext();
//      while (n != this) {
//        DiskEntry nextEntry = n.getNext();
//        VersionSource member = null;
//        long version = -1;
//        if(pendingKrfTags != null) {
//          VersionTag tag = pendingKrfTags.get(n);
//          if(tag != null) {
//            member = tag.getMemberID();
//            version = tag.getRegionVersion();
//          }
//        }
//        if(member == null) {
//          VersionStamp stamp = n.getVersionStamp();
//          member = stamp.getMemberID();
//          version = stamp.getRegionVersion();
//        }
//        
//        if(rvv.contains(member, version)) {
//          result++;
//          remove(n);
//          if(pendingKrfTags != null) {
//            pendingKrfTags.remove(n);
//          }
//        }
//        n = nextEntry;
//      }
//      return result;
    }

    /**
     * Clear without an RVV. Empties the entire list.
     */
    private int clear() {
      int result = 0;
      // Need to iterate over the list and set each prev field to null
      // so that if remove is called it will know that the DiskEntry
      // has already been removed.
      DiskEntry n = getNext();
      setNext(this);
      setPrev(this);
      while (n != this) {
        result++;
        n.setPrev(null);
        n = n.getNext();
      }
      return result;
    }

    public synchronized boolean remove(DiskEntry v) {
      DiskEntry p = v.getPrev();
      if (p != null) {
        v.setPrev(null);
        DiskEntry n = v.getNext();
        v.setNext(null);
        n.setPrev(p);
        p.setNext(n);
        return true;
      } else {
        return false;
      }
    }

    public synchronized void insert(DiskEntry v) {
      assert v.getPrev() == null;
      // checkForDuplicate(v);
      DiskEntry n = getNext();
      setNext(v);
      n.setPrev(v);
      v.setNext(n);
      v.setPrev(this);
    }
    
    public synchronized void replace(DiskEntry old, DiskEntry v) {
      DiskEntry p = old.getPrev();
      if (p != null) {
        old.setPrev(null);
        v.setPrev(p);
        p.setNext(v);
      }

      DiskEntry n = old.getNext();
      if (n != null) {
        old.setNext(null);
        v.setNext(n);
        n.setPrev(v);
      }

      if (getNext() == old) {
        setNext(v);
      }
    }

    // private synchronized void checkForDuplicate(DiskEntry v) {
    // DiskEntry de = getPrev();
    // final long newKeyId = v.getDiskId().getKeyId();
    // while (de != this) {
    // if (de.getDiskId().getKeyId() == newKeyId) {
    // throw new IllegalStateException(
    // "DEBUG: found duplicate for oplogKeyId=" + newKeyId + " de="
    // + System.identityHashCode(v) + " ode="
    // + System.identityHashCode(de) + " deKey=" + v.getKey()
    // + " odeKey=" + de.getKey() + " deOffset="
    // + v.getDiskId().getOffsetInOplog() + " odeOffset="
    // + de.getDiskId().getOffsetInOplog());
    // }
    // de = de.getPrev();
    // }
    // }

    @Override
    public Object getKey() {throw new IllegalStateException();}
    @Override
    public Object getKeyCopy() {throw new IllegalStateException();}
    @Override
    public Object _getValue() {throw new IllegalStateException();}
    @Override
    public Token getValueAsToken() {throw new IllegalStateException();}
    @Override
    public Object _getValueRetain(RegionEntryContext context, boolean decompress) {throw new IllegalStateException();}
    @Override
    public void setValueWithContext(RegionEntryContext context,Object value) {throw new IllegalStateException();}
    @Override
    public void handleValueOverflow(RegionEntryContext context) {throw new IllegalStateException();}
    @Override
    public void afterValueOverflow(RegionEntryContext context) {throw new IllegalStateException();}
    @Override
    public Object prepareValueForCache(RegionEntryContext r, Object val, boolean isEntryUpdate,
        boolean valHasMetadataForGfxdOffHeapUpdate)
    { throw new IllegalStateException("Should never be called");  }
    public void _removePhase1(LocalRegion r) {throw new IllegalStateException();}
    public DiskId getDiskId() {throw new IllegalStateException();}
    public long getLastModified() {throw new IllegalStateException();}
    public boolean isRecovered() {throw new IllegalStateException();}
    public boolean isValueNull() {throw new IllegalStateException();}
    public boolean isRemovedFromDisk() {throw new IllegalStateException();}
  
    public int updateAsyncEntrySize(EnableLRU capacityController) {throw new IllegalStateException();}

    public void _setLastModified(long lastModifiedTime) { throw new IllegalStateException(); }
    public void setLastModified(long lastModifiedTime) { throw new IllegalStateException(); }
    public boolean isLockedForCreate() {throw new IllegalStateException();}
    public Object getRawKey() { throw new IllegalStateException(); }
    public void setOwner(LocalRegion owner, Object previousOwner) { throw new IllegalStateException(); }
    public Object getContainerInfo() { throw new IllegalStateException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object setContainerInfo(LocalRegion owner, Object val) {
      throw new IllegalStateException();
    }

    /**
     * Adds any live entries in this list to liveEntries and returns the index
     * of the next free slot.
     * 
     * @param liveEntries
     *          the array to fill with the live entries
     * @param idx
     *          the first free slot in liveEntries
     * @param drv
     *          the disk region these entries are on
     * @param pendingKrfTags 
     * @return the next free slot in liveEntries
     */
    public synchronized int addLiveEntriesToList(KRFEntry[] liveEntries,
        int idx, DiskRegionView drv, Map<DiskEntry, VersionHolder> pendingKrfTags) {
      DiskEntry de = getPrev();
      while (de != this) {
        VersionHolder tag = null;
        if(pendingKrfTags != null) {
          tag = pendingKrfTags.get(de);
        }
        liveEntries[idx] = new KRFEntry(drv, de, tag);
        idx++;
        de = de.getPrev();
      }
      return idx;
    }
    /* (non-Javadoc)
     * @see com.gemstone.gemfire.internal.cache.DiskEntry#getVersionStamp()
     */
    @Override
    public VersionStamp getVersionStamp() {
      // dummy entry as start of live list
      return null;
    }
    @Override
    public boolean hasStats() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public long getLastAccessed() throws InternalStatisticsDisabledException {
      // TODO Auto-generated method stub
      return 0;
    }
    @Override
    public long getHitCount() throws InternalStatisticsDisabledException {
      // TODO Auto-generated method stub
      return 0;
    }
    @Override
    public long getMissCount() throws InternalStatisticsDisabledException {
      // TODO Auto-generated method stub
      return 0;
    }
    @Override
    public void updateStatsForPut(long lastModifiedTime) {
      // TODO Auto-generated method stub
    }
    @Override
    public VersionTag generateVersionTag(VersionSource member,
        boolean isRemoteVersionSource, boolean withDelta, LocalRegion region,
        EntryEventImpl event) {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public boolean dispatchListenerEvents(EntryEventImpl event)
        throws InterruptedException {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public void setRecentlyUsed() {
      // TODO Auto-generated method stub
    }
    @Override
    public void updateStatsForGet(boolean hit, long time) {
      // TODO Auto-generated method stub
    }
    @Override
    public void txDidDestroy(long currTime) {
      // TODO Auto-generated method stub
    }
    @Override
    public void resetCounts() throws InternalStatisticsDisabledException {
      // TODO Auto-generated method stub
    }
    @Override
    public void makeTombstone(LocalRegion r, VersionTag version)
        throws RegionClearedException {
      // TODO Auto-generated method stub
    }
    @Override
    public void removePhase1(LocalRegion r, boolean clear)
        throws RegionClearedException {
      // TODO Auto-generated method stub
    }
    @Override
    public void removePhase2(LocalRegion r) {
      // TODO Auto-generated method stub
    }
    @Override
    public boolean isRemoved() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isRemovedPhase2() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isTombstone() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean fillInValue(LocalRegion r,
        com.gemstone.gemfire.internal.cache.InitialImageOperation.Entry entry,
        DM mgr, Version targetVersion) {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isOverflowedToDisk(LocalRegion r, DiskPosition dp,
        boolean alwaysFetchPosition) {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public Object getValue(RegionEntryContext context) {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public void setValue(RegionEntryContext context, Object value)
        throws RegionClearedException {
      // TODO Auto-generated method stub
    }
    @Override
    public void setValueWithTombstoneCheck(Object value, EntryEvent event)
        throws RegionClearedException {
      // TODO Auto-generated method stub
    }
    @Override
    public Object getValueInVM(RegionEntryContext context) {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public Object getValueOnDisk(LocalRegion r) throws EntryNotFoundException {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public Object getValueOnDiskOrBuffer(LocalRegion r)
        throws EntryNotFoundException {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public boolean initialImagePut(LocalRegion region, long lastModified,
        Object newValue, boolean wasRecovered, boolean acceptedVersionTag)
        throws RegionClearedException {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean initialImageInit(LocalRegion region, long lastModified,
        Object newValue, boolean create, boolean wasRecovered,
        boolean acceptedVersionTag) throws RegionClearedException {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean destroy(LocalRegion region, EntryEventImpl event,
        boolean inTokenMode, boolean cacheWrite, Object expectedOldValue,
        boolean forceDestroy, boolean removeRecoveredEntry)
        throws CacheWriterException, EntryNotFoundException, TimeoutException,
        RegionClearedException {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public Object getSerializedValueOnDisk(LocalRegion localRegion) {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public Object getValueInVMOrDiskWithoutFaultIn(LocalRegion owner) {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public Object getValueOffHeapOrDiskWithoutFaultIn(LocalRegion owner) {
      // TODO Auto-generated method stub
      return null;
    }
    @Override
    public boolean isUpdateInProgress() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public void setUpdateInProgress(boolean underUpdate) {
      // TODO Auto-generated method stub
    }
    @Override
    public boolean isMarkedForEviction() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public void setMarkedForEviction() {
      // TODO Auto-generated method stub
    }
    @Override
    public void clearMarkedForEviction() {
      // TODO Auto-generated method stub
    }
    @Override
    public boolean isInvalid() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isDestroyed() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isDestroyedOrRemoved() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isDestroyedOrRemovedButNotTombstone() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isInvalidOrRemoved() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public boolean isOffHeap() {
      return false;
    }
    @Override
    public void setValueToNull(RegionEntryContext context) {
      // TODO Auto-generated method stub
    }
    @Override
    public void returnToPool() {
      // TODO Auto-generated method stub
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public Object getOwnerId(Object context) {
      // TODO Auto-generated method stub
      return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean attemptLock(LockMode mode, int flags,
        LockingPolicy lockPolicy, long msecs, Object owner, Object context) {
      // TODO Auto-generated method stub
      return false;
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseLock(LockMode mode, boolean releaseAll, Object owner,
        Object context) {
      // TODO Auto-generated method stub
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int numSharedLocks() {
      // TODO Auto-generated method stub
      return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int numReadOnlyLocks() {
      // TODO Auto-generated method stub
      return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasExclusiveLock(Object owner, Object context) {
      // TODO Auto-generated method stub
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasExclusiveSharedLock(Object ownerId, Object context) {
      // TODO Auto-generated method stub
      return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getState() {
      // TODO Auto-generated method stub
      return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasAnyLock() {
      // TODO Auto-generated method stub
      return false;
    }

    @Override
    public boolean isCacheListenerInvocationInProgress() {
      // TODO Auto-generated method stub
      return false;
    }
    @Override
    public void setCacheListenerInvocationInProgress(boolean isListenerInvoked) {
      // TODO Auto-generated method stub
      
    }
  }

  /**
   * Used as the value in the regionMap. Tracks information about what the
   * region has in this oplog.
   */
  public interface DiskRegionInfo {
    public DiskRegionView getDiskRegion();
    public int addLiveEntriesToList(KRFEntry[] liveEntries, int idx);
    public void addLive(DiskEntry de);
    public void update(DiskEntry entry);
    public void replaceLive(DiskEntry old, DiskEntry de);
    public boolean rmLive(DiskEntry de);
    public DiskEntry getNextLiveEntry();
    public void setDiskRegion(DiskRegionView dr);
    public long clear(RegionVersionVector rvv);
    /**
     * Return true if we are the first guy to set it to true
     */
    public boolean testAndSetUnrecovered();
    public boolean getUnrecovered();
    /**
     * Return true if we are the first guy to set it to false
     */
    public boolean testAndSetRecovered(DiskRegionView dr);
    /**
     * Callback to indicate that this oplog has created a krf.
     */
    public void afterKrfCreated();
  }
  public abstract static class AbstractDiskRegionInfo implements DiskRegionInfo {
    private DiskRegionView dr;
    private boolean unrecovered = false;

    public AbstractDiskRegionInfo(DiskRegionView dr) {
      this.dr = dr;
    }
    public abstract void addLive(DiskEntry de);
    public abstract boolean rmLive(DiskEntry de);
    public abstract DiskEntry getNextLiveEntry();
    public abstract long clear(RegionVersionVector rvv);
    
    final public DiskRegionView getDiskRegion() {
      return this.dr;
    }
    final public void setDiskRegion(DiskRegionView dr) {
      this.dr = dr;
    }
    synchronized public boolean testAndSetUnrecovered() {
      boolean result = !this.unrecovered;
      if (result) {
        this.unrecovered = true;
        this.dr = null;
      }
      return result;
    }

    final synchronized public boolean getUnrecovered() {
      return this.unrecovered;
    }
    final synchronized public boolean testAndSetRecovered(DiskRegionView dr) {
      boolean result = this.unrecovered;
      if (result) {
        this.unrecovered = false;
        this.dr = dr;
      }
      return result;
    }
  }
  public static class DiskRegionInfoNoList extends AbstractDiskRegionInfo {
    private final AtomicInteger liveCount = new AtomicInteger();

    public DiskRegionInfoNoList(DiskRegionView dr) {
      super(dr);
    }
    
    @Override
    public void addLive(DiskEntry de) {
      this.liveCount.incrementAndGet();
    }
    
    
    @Override
    public void update(DiskEntry entry) {
      //nothing to do
    }

    @Override
    public void replaceLive(DiskEntry old, DiskEntry de) {
    }
    
    @Override
    public boolean rmLive(DiskEntry de) {
      return this.liveCount.decrementAndGet() >= 0;
    }

    @Override
    public DiskEntry getNextLiveEntry() {
      return null;
    }
    @Override
    public long clear(RegionVersionVector rvv) {
      return this.liveCount.getAndSet(0);
    }

    public int addLiveEntriesToList(KRFEntry[] liveEntries, int idx) {
      // nothing needed since no linked list
      return idx;
    }

    public void afterKrfCreated() {
      //do nothing
    }
  }
  public static class DiskRegionInfoWithList extends AbstractDiskRegionInfo {
    /**
     * A linked list of the live entries in this oplog. Updates to pendingKrfTags
     * are protected by synchronizing on object.
     */
    private final OplogDiskEntry liveEntries = new OplogDiskEntry();
    /**
     * A map of DiskEntry to the VersionTag that is written to disk associated
     * with this tag. Only needed for async regions so that we can generate a
     * krf with a version tag that matches the the tag we have written to disk
     * for this oplog.
     */
    private Map<DiskEntry, VersionHolder> pendingKrfTags;

    public DiskRegionInfoWithList(DiskRegionView dr, boolean couldHaveKrf, boolean krfExists) {
      super(dr);
      // we need to keep track of the version tags for entries so that we write the correct entry to the krf
      // both in sync and async disk write cases
      if (!krfExists
          && couldHaveKrf) {
        pendingKrfTags = ObjectObjectHashMap.withExpectedSize(200);
      } else {
        pendingKrfTags = null;
      }
    }

    @Override
    public void addLive(DiskEntry de) {
      synchronized(liveEntries) {
        this.liveEntries.insert(de);
        if(pendingKrfTags != null && de.getVersionStamp() != null) {
          //Remember the version tag of the entry as it was written to the crf.
          pendingKrfTags.put(de, new CompactVersionHolder(de.getVersionStamp()));
        }
      }
    }
    
    @Override
    public void update(DiskEntry de) {
      if(pendingKrfTags != null && de.getVersionStamp() != null) {
        //Remember the version tag of the entry as it was written to the crf.
        pendingKrfTags.put(de, new CompactVersionHolder(de.getVersionStamp()));
      }
    }
    
    @Override
    public void replaceLive(DiskEntry old, DiskEntry de) {
      synchronized (liveEntries) {
        this.liveEntries.replace(old, de);
        if (pendingKrfTags != null && de.getVersionStamp() != null) {
          // Remember the version tag of the entry as it was written to the crf.
          pendingKrfTags.remove(old);
          pendingKrfTags.put(de, new CompactVersionHolder(de.getVersionStamp()));
        }
      }
    }

    @Override
    public boolean rmLive(DiskEntry de) {
      synchronized(liveEntries) {
        boolean removed = this.liveEntries.remove(de);
        if(removed && pendingKrfTags != null) {
          pendingKrfTags.remove(de);
        }
        return removed;
      }
    }

    @Override
    public DiskEntry getNextLiveEntry() {
      DiskEntry result = this.liveEntries.getPrev();
      if (result == this.liveEntries) {
        result = null;
      }
      return result;
    }
    @Override
    public long clear(RegionVersionVector rvv) {
      synchronized(this.liveEntries) {
        return this.liveEntries.clear(rvv, this.pendingKrfTags);
      }
    }
    /**
     * Return true if we are the first guy to set it to true
     */
    @Override
    synchronized public boolean testAndSetUnrecovered() {
      boolean result = super.testAndSetUnrecovered();
      if (result) {
        this.liveEntries.clear();
      }
      return result;
    }

    public int addLiveEntriesToList(KRFEntry[] liveEntries, int idx) {
      synchronized(liveEntries) {
        int result = this.liveEntries.addLiveEntriesToList(liveEntries, idx,
            getDiskRegion(), pendingKrfTags);
        return result;
      }
    }
    
    public void afterKrfCreated() {
      synchronized(liveEntries) {
        this.pendingKrfTags = null;
      }
    }
  }

  /**
   * Used during offline compaction to hold information that may need to be copied forward.
   */
  private static class CompactionRecord {
    private final byte[] keyBytes;
    private long offset;

    public CompactionRecord(byte[] kb, long offset) {
      this.keyBytes = kb;
      this.offset = offset;
    }

    public void update(long offset) {
      this.offset = offset;
    }

    public byte[] getKeyBytes() {
      return this.keyBytes;
    }
    public long getOffset() {
      return this.offset;
    }
  }

  /**
   * Map of OplogEntryIds (longs).
   * Memory is optimized by using an int[] for ids in the unsigned int range.
   */
  public static class OplogEntryIdMap {
    private final TStatelessIntObjectHashMap ints = new TStatelessIntObjectHashMap((int)DiskStoreImpl.INVALID_ID);
    private final TStatelessLongObjectHashMap longs = new TStatelessLongObjectHashMap(DiskStoreImpl.INVALID_ID);

    public Object put(long id, Object v) {
      Object result;
      if (id >= 0 && id <= 0x00000000FFFFFFFFL) {
        result = this.ints.put((int)id, v);
      } else {
        result = this.longs.put(id, v);
      }
      return result;
    }
    public int size() {
      return this.ints.size() + this.longs.size();
    }

    public Object get(long id) {
      Object result;
      if (id >= 0 && id <= 0x00000000FFFFFFFFL) {
        result = this.ints.get((int)id);
      } else {
        result = this.longs.get(id);
      }
      return result;
    }
    
    public Iterator iterator() {
      return new Iterator();
    }

    public class Iterator {
      private boolean doingInt = true;
      TStatelessIntObjectIterator intIt = ints.iterator();
      TStatelessLongObjectIterator longIt = longs.iterator();
      public boolean hasNext() {
        if (this.intIt.hasNext()) {
          return true;
        } else {
          doingInt = false;
          return this.longIt.hasNext();
        }
      }
      public void advance() {
        if (doingInt) {
          this.intIt.advance();
        } else {
          this.longIt.advance();
        }
      }
      public long key() {
        if (doingInt) {
          return this.intIt.key();
        } else {
          return this.longIt.key();
        }
      }
      public Object value() {
        if (doingInt) {
          return this.intIt.value();
        } else {
          return this.longIt.value();
        }
      }
    }
  }

  void finishKrf() {
    createKrf(false);
  }

  void prepareForClose() {
    try {
      finishKrf();
    } catch(CancelException e) {
      //workaround for 50465
      logger.fine("Got a cancel exception while creating a krf during shutown", e);
    }
  }

  private Object deserializeKey(byte[] keyBytes, final Version version,
      final ByteArrayDataInput in) {
    if (!getParent().isOffline() || !PdxWriterImpl.isPdx(keyBytes)) {
      return EntryEventImpl.deserialize(keyBytes, version, in);
    }
    else {
      return new RawByteKey(keyBytes);
    }
  }

  /**
   * If this OpLog is from an older version of the product, then return that
   * {@link Version} else return null.
   */
  public Version getProductVersionIfOld() {
    final Version version = this.gfversion;
    if (version == null) {
      // check for the case of diskstore upgrade from 6.6 to >= 7.0
      if (getParent().isUpgradeVersionOnly()) {
        // assume previous release version
        return Version.GFE_66;
      }
      else {
        return null;
      }
    }
    else if (version == Version.CURRENT) {
      return null;
    }
    else {
      // version changed so return that for VersionedDataStream
      return version;
    }
  }

  /**
   * If this OpLog has data that was written by an older version of the product,
   * then return that {@link Version} else return null.
   */
  public Version getDataVersionIfOld() {
    final Version version = this.dataVersion;
    if (version == null) {
      // check for the case of diskstore upgrade from 6.6 to >= 7.0
      if (getParent().isUpgradeVersionOnly()) {
        // assume previous release version
        return Version.GFE_66;
      }
      else {
        return null;
      }
    }
    else if (version == Version.CURRENT) {
      return null;
    }
    else {
      // version changed so return that for VersionedDataStream
      return version;
    }
  }

  /**
   * Used in offline mode to prevent pdx deserialization of keys.
   * The raw bytes are a serialized pdx.
   * @author darrel
   * @since 6.6
   */
  private static class RawByteKey implements Sendable {
    final byte[] bytes;
    final int hashCode;
    
    public RawByteKey(byte[] keyBytes) {
      this.bytes = keyBytes;
      this.hashCode = Arrays.hashCode(keyBytes);
    }
    
    @Override
    public int hashCode() {
      return this.hashCode;
    }
    
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof RawByteKey)) {
        return false;
      }
      return Arrays.equals(this.bytes, ((RawByteKey)other).bytes);
    }

    public void sendTo(DataOutput out) throws IOException {
      out.write(this.bytes);
    }
    
  }

  public File getIndexFileIfValid(boolean deleteIndexFile) {
    return this.idxkrf.getIndexFileIfValid(deleteIndexFile);
  }

  public boolean isNewOplog() {
    return this.newOplog;
  }

  /**
   * Enumeration of operation log file types.
   * @author rholmes
   */
  enum OplogFileType {
    OPLOG_CRF,          // Creates and updates
    OPLOG_DRF,          // Deletes
    OPLOG_KRF           // Keys
  }
  
  /**
   * Enumeration of the possible results of
   * the okToSkipModifyRecord
   * @author dsmith
   *
   */
  private static enum OkToSkipResult {
    SKIP_RECORD, //Skip reading the key and value
    SKIP_VALUE,  //skip reading just the value
    DONT_SKIP;   //don't skip the record
    
    public boolean skip() {
      return this != DONT_SKIP;
    }
    
    public boolean skipKey() {
      return this == SKIP_RECORD;
    }
  }
  

  public String getDiskFilePath() {
    assert this.diskFile != null;
    return this.diskFile.getPath();
  }
  
  public String getDiskFileName() {
    assert this.diskFile != null;
    return this.diskFile.getName();
  }
}
