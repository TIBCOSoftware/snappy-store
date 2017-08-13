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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.distributed.OplogCancelledException;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl.OplogCompactor;
import com.gemstone.gemfire.internal.cache.Oplog.OplogDiskEntry;
import com.gemstone.gemfire.internal.cache.persistence.BytesAndBits;
import com.gemstone.gemfire.internal.cache.persistence.DiskRegionView;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;

/**
 * An oplog used for overflow-only regions.
 * For regions that are persistent (i.e. they can be recovered) see {@link Oplog}.
 * 
 * @author Darrel Schneider
 * 
 * @since prPersistSprint2
 */
class OverflowOplog implements CompactableOplog {

  /** Extension of the oplog file * */
  static final String CRF_FILE_EXT = ".crf";

  /** The file which will be created on disk * */
  private final File diskFile;

  /** boolean marked true when this oplog is closed * */
  private volatile boolean closed;

  private final OplogFile crf = new OplogFile();

  /** preallocated space available for writing to* */
  // volatile private long opLogSpace = 0L;
  /** The stats for this store */
  private final DiskStoreStats stats;

  /** The store that owns this Oplog* */
  private final DiskStoreImpl parent;
  
  /**
   * The oplog set this oplog is part of 
   */
  private final OverflowOplogSet oplogSet;

  /** oplog id * */
  protected final int oplogId;

  /** Directory in which the file is present* */
  private final DirectoryHolder dirHolder;

  /** The max Oplog size (user configurable).
   * Set to zero when oplog is deleted.
   */
  private long maxOplogSize;

  private final OpState opState;

  /**
   * Set to true when this oplog will no longer be written to.
   * Never set to false once it becomes true.
   */
  private boolean doneAppending = false;

  protected LogWriterI18n logger = null;

  private final OplogDiskEntry liveEntries = new OplogDiskEntry();

  private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

  // ///////////////////// Constructors ////////////////////////
//   /**
//    * Creates new <code>Oplog</code> for the given region.
//    * 
//    * @param oplogId
//    *          int identifying the new oplog
//    * @param dirHolder
//    *          The directory in which to create new Oplog
//    * 
//    * @throws DiskAccessException
//    *           if the disk files can not be initialized
//    */
//   OverflowOplog(int oplogId, DiskStoreImpl parent, DirectoryHolder dirHolder) {
//     this.oplogId = oplogId;
//     this.parent = parent;
//     this.dirHolder = dirHolder;
//     this.logger = this.parent.getCache().getLoggerI18n();
//     this.opState = new OpState();
//     long maxOplogSizeParam = this.parent.getMaxOplogSizeInBytes();
//     long availableSpace = this.dirHolder.getAvailableSpace();
//     if (availableSpace < maxOplogSizeParam) {
//       this.maxOplogSize = availableSpace;
//     } else {
//       this.maxOplogSize = maxOplogSizeParam;
//     }
//     this.stats = this.parent.getStats();

//     this.closed = false;
//     String n = this.parent.getName();
//     this.diskFile = new File(this.dirHolder.getDir(),
//                              "OVERFLOW"
//                              + n + "_" + oplogId);
//     try {
//       createCrf();
//     }
//     catch (IOException ex) {
//       throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_CREATING_OPERATION_LOG_BECAUSE_0.toLocalizedString(ex), this.parent);
//     }
//   }

  /**
   * Asif: A copy constructor used for creating a new oplog based on the
   * previous Oplog. This constructor is invoked only from the function
   * switchOplog
   * 
   * @param oplogId
   *          integer identifying the new oplog
   * @param dirHolder
   *          The directory in which to create new Oplog
   */
  OverflowOplog(int oplogId, OverflowOplogSet parent, DirectoryHolder dirHolder, long minSize) {
    this.oplogId = oplogId;
    this.parent = parent.getParent();
    this.oplogSet = parent;
    this.dirHolder = dirHolder;
    this.opState = new OpState();
    this.logger = this.parent.getCache().getLoggerI18n();
    long maxOplogSizeParam = this.parent.getMaxOplogSizeInBytes();
    if (maxOplogSizeParam < minSize) {
      maxOplogSizeParam = minSize;
    }
    long availableSpace = this.dirHolder.getAvailableSpace();
    if (availableSpace < minSize
    // fix for bug 42464
        && !this.parent.isCompactionEnabled()) {
      availableSpace = minSize;
    }
    if (availableSpace < maxOplogSizeParam
    // fix for bug 42464
        && !this.parent.isCompactionEnabled() && availableSpace > 0) {
      // createOverflowOplog decided that we should use this dir. So do it.
      this.maxOplogSize = availableSpace;
    } else {
      this.maxOplogSize = maxOplogSizeParam;
    }
    this.stats = this.parent.getStats();

    this.closed = false;
    String n = this.parent.getName();
    this.diskFile = new File(this.dirHolder.getDir(), "OVERFLOW"
                             + n + "_" + oplogId);
    try {
      createCrf(parent.getActiveOverflowOplog());
    }
    catch (IOException ex) {
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_CREATING_OPERATION_LOG_BECAUSE_0.toLocalizedString(ex), this.parent);
    }
  }

  private DiskStoreImpl getParent() {
    return this.parent;
  }
  
  private OverflowOplogSet getOplogSet() {
    return this.oplogSet;
  }

  private void preblow() {
    this.dirHolder.incrementTotalOplogSize(this.maxOplogSize);
      final OplogFile olf = getOLF();
      try {
   	olf.raf.setLength(this.maxOplogSize);
        olf.raf.seek(0);
      }
      catch (IOException ioe) {
        // @todo need a warning since this can impact perf.
//         if (this.logger.warningEnabled()) {
//           this.logger.warning(
//               LocalizedStrings.Oplog_OPLOGCREATEOPLOGEXCEPTION_IN_PREBLOWING_THE_FILE_A_NEW_RAF_OBJECT_FOR_THE_OPLOG_FILE_WILL_BE_CREATED_WILL_NOT_BE_PREBLOWNEXCEPTION_STRING_IS_0,
//               ioe, null);
//         }
        // I don't think I need any of this. If setLength throws then
        // the file is still ok.
//         raf.close();
//         if (!this.opLogFile.delete() && this.opLogFile.exists()) {
//           throw new DiskAccessException(LocalizedStrings.NewLBHTreeDiskRegion_COULD_NOT_DELETE__0_.toLocalizedString(this.opLogFile.getAbsolutePath()), this.owner);
//         }
//         f = new File(this.diskFile.getPath() + OPLOG_FILE_EXT);
//         if (logger.fineEnabled())
//           logger.fine("Creating operation log file " + f);
//         this.opLogFile = f;
//         raf = new RandomAccessFile(f, "rw");
      }
  }
  /**
   * Creates the crf oplog file
   * 
   * @throws IOException
   */
  private void createCrf(OverflowOplog previous) throws IOException
  {
    File f = new File(this.diskFile.getPath() + CRF_FILE_EXT);

    if (logger.fineEnabled()) {
      logger.fine("Creating operation log file " + f);
    }
    this.crf.f = f;
    this.crf.raf = new RandomAccessFile(f, "rw");
    this.crf.writeBuf = allocateWriteBuf(previous);
    preblow();
    if (logger.infoEnabled()) {
      logger.info(LocalizedStrings.Oplog_CREATE_0_1_2,
                  new Object[] {toString(),
                                "crf",
                                this.parent.getName()});
    }
    this.crf.channel = this.crf.raf.getChannel();

    this.stats.incOpenOplogs();
  }

  private static ByteBuffer allocateWriteBuf(OverflowOplog previous) {
    ByteBuffer result = null;
    if (previous != null) {
      result = previous.consumeWriteBuf();
    }
    if (result == null) {
      result = ByteBuffer.allocateDirect(Integer.getInteger("WRITE_BUF_SIZE", 32768));
    }
    return result;
  }

  private ByteBuffer consumeWriteBuf() {
    synchronized (this.crf) {
      ByteBuffer result = this.crf.writeBuf;
      this.crf.writeBuf = null;
      return result;
    }
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

  /** the oplog identifier * */
  public int getOplogId()
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
    OverflowOplog retryOplog = null;
    long offset = 0;
    synchronized (id) {
      int opId = (int)id.getOplogId();
      if (opId != getOplogId()) {
        // the oplog changed on us so we need to do a recursive
        // call after unsyncing
        retryOplog = this.getOplogSet().getChild(opId);
      } else {
        // fetch this while synced so it will be consistent with oplogId
        offset = id.getOffsetInOplog();
      }
    }
    if (retryOplog != null) {
      return retryOplog.getBytesAndBits(dr, id, faultingIn, bitOnly);
    }
    BytesAndBits bb = null;
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
          "TRACE_READS Overflow getBytesAndBits: "
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
    // Asif: If bb is still null then entry has been compacted to the Htree
    // or in case of concurrent get & put , to a new OpLog ( Concurrent Get
    // &
    // Put is not possible at this point).
    // Asif: Since the compacter takes a lock on Entry as well as DiskId , the
    // situation below
    // will not be possible and hence commenting the code
    /*
     * if (bb == null) { // TODO: added by mitul, remove it later
     * Assert.assertTrue(id.getOplogId() != this.oplogId);
     * if(logger.errorEnabled()) { logger.error("Oplog::getBytesAndBits: DiskID
     * ="+id + " is experiencing problem & going in a potential recursion.
     * Asynch Writer alive at this point is = "+(this.writer != null &&
     * this.writer.isAlive())); } bb =
     * this.parent.getBytesAndBitsWithoutLock(id, faultingIn, bitOnly); }
     */

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

  void freeEntry(DiskEntry de) {
    rmLive(de);
  }

  /**
   * Call this when the cache is closed or region is destroyed. Deletes the lock
   * files and if it is Overflow only, deletes the oplog file as well
   *  
   */
  public void close()
  {
    if (this.closed) {
      return;
    }
    if( logger.fineEnabled()){
      logger.fine("Oplog::close: Store name ="+ parent.getName() + " Oplog ID = "+oplogId);
    }

    basicClose();
  }

  /**
   * Close the files of a oplog but don't set any state. Used by unit tests
   */
  public void testClose() {
    try {
      this.crf.channel.close();
    } catch (IOException ignore) {
    }
    try {
      this.crf.raf.close();
    } catch (IOException ignore) {
    }
  }
  
  private void basicClose() {
    flushAll();
    synchronized (this.crf) {
//       logger.info(LocalizedStrings.DEBUG, "DEBUG closing oplog#" + getOplogId()
//                   + " liveCount=" + this.totalLiveCount.get(),
//                   new RuntimeException("STACK"));
      if (!this.crf.RAFClosed) {
        try {
          this.crf.channel.close();
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
    
    this.deleteFiles();
  }
  
  /**
   * Destroys this oplog. First it will call close which will cleanly close all
   * Async threads. The
   * deletion of lock files will be taken care of by the close. Close will also
   * take care of deleting the files if it is overflow only mode
   *  
   */
  public void destroy()
  {
    if (!this.closed) {
      lockCompactor();
      try {
        this.basicClose();
      } finally {
        unlockCompactor();
      }
    }
  }

  /**
   * A check to confirm that the oplog has been closed because of the cache
   * being closed
   *  
   */
  private void checkClosed()
  {
    this.parent.getCancelCriterion().checkCancelInProgress(null);
    if (!this.closed) {
      return;
    }
    throw new OplogCancelledException("This Oplog has been closed.");
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

  private void clearOpState() {
    this.opState.clear();
  }

  /**
   * Returns the number of bytes it will take to serialize this.opState.
   */
  private int getOpStateSize() {
    return this.opState.getSize();
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
        userBits = EntryBits.setSerialized(userBits, true);
      }
    }
    return userBits;
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
   * @return true if modify was done; false if this file did not have room
   */
  public final boolean modify(DiskRegion dr, DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, boolean async) {
    try {
      byte userBits = calcUserBits(value);
      return basicModify(entry, value, userBits, async);
    } catch (IOException ex) {
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, dr.getName());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      dr.getCancelCriterion().checkCancelInProgress(ie);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, dr.getName());
    }
  }
  public final boolean copyForwardForOverflowCompact(DiskEntry entry,
      DiskEntry.Helper.ValueWrapper value, byte userBits) {
    try {
      return basicModify(entry, value, userBits, true);
    } catch (IOException ex) {
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, getParent().getName());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      getParent().getCancelCriterion().checkCancelInProgress(ie);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, getParent().getName());
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
   * @return true if modify was done; false if this file did not have room
   * @throws IOException
   * @throws InterruptedException
   */
  private boolean basicModify(DiskEntry entry,
                              DiskEntry.Helper.ValueWrapper value,
                              byte userBits, boolean async)
    throws IOException, InterruptedException
  {
    DiskId id = entry.getDiskId();
    long startPosForSynchOp = -1L;
    OverflowOplog emptyOplog = null;
    synchronized (this.crf) {
      final int valueLength = value.size();
      // can't use ByteBuffer after handing it over to OpState
      this.opState.initialize(value, valueLength, userBits);
      int adjustment = getOpStateSize();
      assert adjustment > 0;
      //       {
      //         LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
      //         l.info(LocalizedStrings.DEBUG, "modify setting size to=" + temp
      //                + " oplog#" + getOplogId());
      //       }
      int oldOplogId;
      // do the io while holding lock so that switch can set doneAppending
      // Write the data to the opLog for the synch mode
      startPosForSynchOp = writeOpLogBytes(async);
      if (startPosForSynchOp == -1) {
        return false;
      } else {
        //           if (this.crf.currSize != startPosForSynchOp) {
        //             LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
        //             l.info(LocalizedStrings.DEBUG, "currSize=" + this.crf.currSize
        //                    + " startPosForSynchOp=" + startPosForSynchOp
        //                    + " oplog#" + getOplogId());
        //             assert false;
        //           }
        //this.crf.currSize = temp;
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
        //            this.logger.info(LocalizedStrings.DEBUG,
        //                             "basicModify: id=<" + abs(id.getKeyId())
        //                             + "> key=<" + entry.getKey() + ">"
        //                             + " valueOffset=" + startPosForSynchOp
        //                             + " userBits=" + userBits
        //                             + " valueLen=" + valueLength
        //                             + " valueBytes=<" + baToString(value) + ">"
        //                             + " oplog#" + getOplogId());
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
          oldOplogId = (int)id.setOplogId(getOplogId());
          id.setOffsetInOplog(startPosForSynchOp);
          if (EntryBits.isNeedsValue(userBits)) {
            id.setValueLength(valueLength);
          } else {
            id.setValueLength(0);
          }
          id.setUserBits(userBits);
        }
        // Note: we always call rmLive (and addLive) even if this mod was to an entry
        // last modified in this same oplog to cause the list to be sorted by offset
        // so when the compactor iterates over it will read values with a sequential scan
        // instead of hopping around the oplog.
        if (oldOplogId > 0) {
          OverflowOplog oldOplog = this.getOplogSet().getChild(oldOplogId);
          if (oldOplog != null) {
            if (oldOplog.rmLive(entry)) {
              if (oldOplogId != getOplogId()) {
                emptyOplog = oldOplog;
              }
            }
          }
        }
        addLive(entry);
      }
      clearOpState();
    }
    if (LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER) {
      CacheObserverHolder.getInstance()
        .afterSettingOplogOffSet(startPosForSynchOp);
    }
    if (emptyOplog != null
        && (!emptyOplog.isCompacting() || emptyOplog.calledByCompactorThread())) {
      if (emptyOplog.calledByCompactorThread() && emptyOplog.hasNoLiveValues()) {
        flushAll();
      }
      emptyOplog.handleNoLiveValues();
    }
    return true;
  }

  /**
   * Removes the key/value pair with the given id on disk.
   * 
   * @param entry
   *          DiskEntry object on which remove operation is called
   */
  public final void remove(DiskRegion dr, DiskEntry entry) {
    try {
      basicRemove(dr, entry);
    } catch (IOException ex) {
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, dr.getName());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      dr.getCancelCriterion().checkCancelInProgress(ie);
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0_DUE_TO_FAILURE_IN_ACQUIRING_READ_LOCK_FOR_ASYNCH_WRITING.toLocalizedString(this.diskFile.getPath()), ie, dr.getName());
    }
  }

  /**
   * 
   * Asif: A helper function which identifies whether to record a removal of
   * entry in the current oplog or to make the switch to the next oplog. This
   * function enables us to reuse the byte buffer which got created for an oplog
   * which no longer permits us to use itself.
   * 
   * @param entry
   *          DiskEntry object representing the current Entry
   * @throws IOException
   * @throws InterruptedException
   */
  private void basicRemove(DiskRegion dr, DiskEntry entry)
    throws IOException, InterruptedException
  {
    DiskId id = entry.getDiskId();

    // no need to lock since this code no longer does io
    if (EntryBits.isNeedsValue(id.getUserBits())) {
      // oplogId already done up in DiskStoreImpl
      long oldOffset = id.getOffsetInOplog();
      if (oldOffset != -1) {
        id.setOffsetInOplog(-1);
        //       this.logger.info(LocalizedStrings.DEBUG,
        //                        "basicRemove: id=<" + abs(id.getKeyId())
        //                        + "> key=<" + entry.getKey() + ">");
        if (rmLive(entry)) {
          if (!isCompacting() || calledByCompactorThread()) {
            handleNoLiveValues();
          }
        }
      }
    }
  }


//   /**
//    * This is only used for an assertion check.
//    */
//   private long lastWritePos = -1;

//   /**
//    * test hook
//    */
//   public final ByteBuffer getWriteBuf() {
//     return this.crf.writeBuf;
//   }
  private final void flush() throws IOException {
    final OplogFile olf = this.crf;
    synchronized (olf) {
      if (olf.RAFClosed) {
//         logger.info(LocalizedStrings.DEBUG, "DEBUG: no need to flush because RAFClosed"
//                     + " oplog#" + getOplogId() + "crf);
        return;
      }
      try {
      ByteBuffer bb = olf.writeBuf;
//       logger.info(LocalizedStrings.DEBUG, "DEBUG: flush "
//                   + " oplog#" + getOplogId()
//                   + " bb.position()=" + ((bb != null) ? bb.position() : "null")
//                   + "crf);
      if (bb != null && bb.position() != 0) {
        bb.flip();
        int flushed = 0;
        do {
//           {
//             byte[] toPrint = new byte[bb.remaining()];
//             for (int i=0; i < bb.remaining(); i++) {
//               toPrint[i] = bb.get(i);
//             }
//             logger.info(LocalizedStrings.DEBUG, "DEBUG: flush writing bytes at offset=" + olf.bytesFlushed
//                         + " position=" + olf.channel.position()
//                         + " bytes=" + baToString(toPrint)
//                         + " oplog#" + getOplogId()
//                         + "crf");
//           }
          flushed += olf.channel.write(bb);
//            logger.info(LocalizedStrings.DEBUG, "DEBUG: flush bytesFlushed=" + olf.bytesFlushed
//                        + " position=" + olf.channel.position()
//                        + " oplog#" + getOplogId()
//                        + "crf");
        } while (bb.hasRemaining());
        // update bytesFlushed after entire writeBuffer is flushed to fix bug 41201
        olf.bytesFlushed += flushed;
//             logger.info(LocalizedStrings.DEBUG, "DEBUG: flush bytesFlushed=" + olf.bytesFlushed
//                        + " position=" + olf.channel.position()
//                         + " oplog#" + getOplogId()
//                         + "crf");
        bb.clear();
      }
      } catch (ClosedChannelException ignore) {
        // It is possible for a channel to be closed when our code does not
        // explicitly call channel.close (when we will set RAFclosed).
        // This can happen when a thread is doing an io op and is interrupted.
        // That thread will see ClosedByInterruptException but it will also
        // close the channel and then we will see ClosedChannelException.
      }
    }
  }

  public final void flushAll() {
    try {
      flush();
    } catch (IOException ex) {
      throw new DiskAccessException(LocalizedStrings.Oplog_FAILED_WRITING_KEY_TO_0.toLocalizedString(this.diskFile.getPath()), ex, this.parent);
    }
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
   * @return The long offset at which the data present in the ByteBuffer gets
   *         written to
   */
  private long writeOpLogBytes(boolean async) throws IOException {
    long startPos = -1L;
    final OplogFile olf = this.crf;
    synchronized (olf) {
      if (this.doneAppending) {
        return -1;
      }
      if (this.closed) {
        Assert.assertTrue(
                          false,
                          toString()
                          + " for store " + this.parent.getName()
                          + " has been closed for synch mode while writing is going on. This should not happen");
      }
      // Asif : It is assumed that the file pointer is already at the
      // appropriate position in the file so as to allow writing at the end.
      // Any fault in operations will set the pointer back to the write location.
      // Also it is only in case of synch writing, we are writing more
      // than what is actually needed, we will have to reset the pointer.
      // Also need to add in offset in writeBuf in case we are not flushing writeBuf
      long curFileOffset = olf.channel.position() + olf.writeBuf.position();
      startPos = allocate(curFileOffset, getOpStateSize());
      if (startPos != -1) {
        if (startPos != curFileOffset) {
          flush();
          olf.channel.position(startPos);
          olf.bytesFlushed = startPos;
          this.stats.incOplogSeeks();
        }
        if (DiskStoreImpl.TRACE_WRITES) {
          this.logger.info(LocalizedStrings.DEBUG,
                           "TRACE_WRITES writeOpLogBytes"
                           + " startPos=" + startPos
                           + " oplog#" + getOplogId());
        }
        long oldBytesFlushed = olf.bytesFlushed;
        long bytesWritten = this.opState.write();
        if ((startPos + bytesWritten) > olf.currSize) {
          olf.currSize = startPos + bytesWritten;
        }
        if (DiskStoreImpl.TRACE_WRITES) {
          this.logger.info(LocalizedStrings.DEBUG,
                           "TRACE_WRITES writeOpLogBytes"
                           + " bytesWritten=" + bytesWritten
                           + " oldBytesFlushed=" + oldBytesFlushed
                           + " bytesFlushed=" + olf.bytesFlushed
                           + " oplog#" + getOplogId());
        }
        if (oldBytesFlushed != olf.bytesFlushed) {
          // opState.write must have done an implicit flush
          // so we need to do an explicit flush so the value
          // can be read back in entirely from disk.
          flush();
        }
        getStats().incWrittenBytes(bytesWritten, async);
      
//       // Moved the set of lastWritePos to after write
//       // so if write throws an exception it will not be updated.
//       // This fixes bug 40449.
//       this.lastWritePos = startPos;
      }
//       logger.info(LocalizedStrings.DEBUG, "DEBUG writeOpLogBytes"
//                   + " startPos=" + startPos
//                   + " length=" + getOpStateSize()
//                   + " curFileOffset=" + curFileOffset
//                   + " position=" + olf.channel.position()
//                   + " writeBufPos=" + olf.writeBuf.position()
//                   + " startPos=" + startPos
//                   + " oplog#" + getOplogId());
    }
    return startPos;
  }

  private BytesAndBits attemptGet(DiskRegionView dr, long offsetInOplog,
                                  int valueLength, byte userBits) throws IOException {
    synchronized (this.crf) {
      //         if (this.closed || this.deleted.get()) {
      //           throw new DiskAccessException("attempting get on "
      //                                         + (this.deleted.get() ? "destroyed" : "closed")
      //                                         + " oplog #" + getOplogId(), this.owner);
      //         }
      final long readPosition = offsetInOplog;
      assert readPosition >= 0;
      RandomAccessFile myRAF = this.crf.raf;
      BytesAndBits bb = null;
      long writePosition = 0;
      if (!this.doneAppending) {
        writePosition = myRAF.getFilePointer();
        bb = attemptWriteBufferGet(writePosition, readPosition, valueLength, userBits);
        if (bb == null) {
          if (/*!getParent().isSync() since compactor groups writes
                && */ (readPosition+valueLength) > this.crf.bytesFlushed
                      && !this.closed) {
            flushAll(); // fix for bug 41205
            writePosition = myRAF.getFilePointer();
          }
        }
      }
      if (bb == null) {
        myRAF.seek(readPosition);
        try {
          //               {
          //                 LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
          //                 l.info(LocalizedStrings.DEBUG, "after seek rp=" + readPosition
          //                        + " fp=" + myRAF.getFilePointer()
          //                        + " oplog#" + getOplogId());
          //               }
          this.stats.incOplogSeeks();
          byte[] valueBytes = new byte[valueLength];
          myRAF.readFully(valueBytes);
          ByteBuffer valueBuffer = ByteBuffer.wrap(valueBytes);
//           if (EntryBits.isSerialized(userBits)) {
//             try {
//               com.gemstone.gemfire.internal.util.BlobHelper.deserializeBlob(valueBytes);
//             } catch (IOException ex) {
//               if (DiskStoreImpl.TRACE_WRITES) {
//                 this.logger.info(LocalizedStrings.DEBUG,
//                                  "TRACE_WRITES readPos=" + readPosition
//                                  + " len=" + valueLength
//                                  +  " doneApp=" + doneAppending
//                                  + " userBits=" + userBits
//                                  + " oplog#" + getOplogId()
//                                  );
//               }
//               throw new RuntimeException("DEBUG readPos=" + readPosition + " len=" + valueLength +  "doneApp=" + doneAppending + " userBits=" + userBits, ex);
//             } catch (ClassNotFoundException ex2) {
//               throw new RuntimeException(ex2);
//             }
//           }
          if (DiskStoreImpl.TRACE_READS) {
            logger.info(LocalizedStrings.DEBUG,
                "TRACE_READS Overflow attemptGet readPosition=" + readPosition
                    + " valueLength=" + valueLength
                    + " value=<" + ClientSharedUtils.toString(valueBuffer) + ">"
                    + " oplog#" + getOplogId());
          }
          this.stats.incOplogReads();
          bb = new BytesAndBits(valueBuffer, userBits);
        }
        finally {
          // if this oplog is no longer being appended to then don't waste disk io
          if (!this.doneAppending) {
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
      }
      return bb;
    } // sync
  }

  private BytesAndBits attemptWriteBufferGet(long writePosition, long readPosition,
                                             int valueLength, byte userBits) {
    BytesAndBits bb = null;
    ByteBuffer writeBuf = this.crf.writeBuf;
    int curWriteBufPos = writeBuf.position();
    if (writePosition <= readPosition
        && (writePosition+curWriteBufPos) >= (readPosition+valueLength)) {
      int bufOffset = (int)(readPosition - writePosition);
      byte[] valueBytes = new byte[valueLength];
      int oldLimit = writeBuf.limit();
      writeBuf.limit(curWriteBufPos);
      writeBuf.position(bufOffset);
      writeBuf.get(valueBytes);
      writeBuf.position(curWriteBufPos);
      writeBuf.limit(oldLimit);
      ByteBuffer valueBuffer = ByteBuffer.wrap(valueBytes);
      if (DiskStoreImpl.TRACE_READS) {
        logger.info(LocalizedStrings.DEBUG,
            "TRACE_READS attemptWriteBufferGet readPosition=" + readPosition
                + " valueLength=" + valueLength
                + " value=<" + ClientSharedUtils.toString(valueBuffer) + ">"
                + " oplog#" + getOplogId());
      }
      bb = new BytesAndBits(valueBuffer, userBits);
    }
    return bb;
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
            bb = attemptGet(dr, offsetInOplog, valueLength, userBits);
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
  
  private final AtomicBoolean deleted = new AtomicBoolean();
  
  /**
   * deletes the oplog's file(s)
   */
  void deleteFiles() {
    boolean needsDestroy = this.deleted.compareAndSet(false, true);
    if (needsDestroy) {
      this.getOplogSet().removeOverflow(this);
      deleteFile();
    }
  }

  private void deleteFile() {
    final OplogFile olf = getOLF();
    if (this.maxOplogSize != 0) {
      this.dirHolder.decrementTotalOplogSize(this.maxOplogSize);
      this.maxOplogSize = 0;
      olf.currSize = 0;
    }
    if (olf.f == null) return;
    if (!olf.f.exists()) return;
    if (!olf.f.delete() && olf.f.exists()) {
      throw new DiskAccessException(LocalizedStrings.Oplog_COULD_NOT_DELETE__0_.toLocalizedString(olf.f.getAbsolutePath()), this.parent);
    }
    if (logger.infoEnabled()) {
      logger.info(LocalizedStrings.Oplog_DELETE_0_1_2,
                  new Object[] {toString(),
                                "crf",
                                this.parent.getName()});
//       logger.info(LocalizedStrings.DEBUG,
//                   "DEBUG deleteFile", new RuntimeException("STACK"));
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
  long getOplogSize() {
//     {
//       LogWriterI18n l = parent.getOwner().getCache().getLoggerI18n();
//       l.info(LocalizedStrings.DEBUG, "getOplogSize crfSize=" + this.crf.currSize + " drfSize=" + this.drf.currSize);
//     }
    return this.crf.currSize;
  }

  /**
   * The HighWaterMark of recentValues.
   */
  private final AtomicLong totalCount = new AtomicLong(0);
  /**
   * The number of records in this oplog that contain the most recent
   * value of the entry.
   */
  private final AtomicLong totalLiveCount = new AtomicLong(0);

  private long allocate(long suggestedOffset, int length) {
    if (suggestedOffset+length > this.maxOplogSize) {
      flushAll();
      this.doneAppending = true;
      return -1;
    } else {
      return suggestedOffset;
    }
  }
  private void addLive(DiskEntry de) {
    this.totalCount.incrementAndGet();
    this.totalLiveCount.incrementAndGet();
    if (isCompactionPossible()) {
      this.liveEntries.insert(de);
    }
  }
  private boolean rmLive(DiskEntry de) {
    if (isCompactionPossible()) {
      if (this.liveEntries.remove(de)) {
        this.totalLiveCount.decrementAndGet();
        return true;
      } else {
        return false;
      }
    } else {
      this.totalLiveCount.decrementAndGet();
      return true;
    }
  }

  /**
   * Return true if it is possible that compaction of this oplog will be done.
   */
  private boolean isCompactionPossible() {
    return getParent().isCompactionPossible();
  }

  boolean needsCompaction() {
//     logger.info(LocalizedStrings.DEBUG,
//                 "DEBUG isCompactionPossible=" + isCompactionPossible());
    if (!isCompactionPossible()) return false;
//     logger.info(LocalizedStrings.DEBUG,
//                 "DEBUG compactionThreshold=" + parent.getCompactionThreshold());
    if (getParent().getCompactionThreshold() == 100) return true;
    if (getParent().getCompactionThreshold() == 0) return false;
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

  public boolean hasNoLiveValues() {
    return this.totalLiveCount.get() <= 0;
  }

  private void handleEmpty(boolean calledByCompactor) {
    if (!calledByCompactor) {
      if (logger.infoEnabled()) {
        logger.info(LocalizedStrings.Oplog_CLOSING_EMPTY_OPLOG_0_1,
                    new Object[] {this.parent.getName(), toString()});
      }
    }
    destroy();
  }

  private void handleNoLiveValues() {
    if (!this.doneAppending) {
      return;
    }
    // At one point this method was a noop for the pure overflow case.
    // But it turns out it was cheaper to delete empty oplogs and create
    // new ones instead of overwriting the empty one.
    // This is surprising and may be specific to Linux ext3.
    // So at some point we may want to comment out the following block
    // of code and check performance again.
    if (hasNoLiveValues()) {
      getOplogSet().removeOverflow(this);
      if (calledByCompactorThread()) {
        handleEmpty(true);
      } else {
        getParent().executeDiskStoreTask(new Runnable() {
          public void run() {
            handleEmpty(false);
          }
        });
        
      }
    } else if (!isCompacting() && needsCompaction()) {
      addToBeCompacted();
    }
  }

  private void addToBeCompacted() {
    getOplogSet().addOverflowToBeCompacted(this);
  }
  
  private GemFireCacheImpl getGemFireCache() {
    return this.parent.getCache();
  }


  long testGetOplogFileLength() throws IOException {
    long result = 0;
    if (this.crf.raf != null) {
      result += this.crf.raf.length();
    }
    return result;
  }

  private final OplogFile getOLF() {
    return this.crf;
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

  // //////// Methods used during recovery //////////////

  // ////////////////////Inner Classes //////////////////////

  private static class OplogFile {
    public File f;
    public RandomAccessFile raf;
    public boolean RAFClosed;
    public FileChannel channel;
    public ByteBuffer writeBuf;
    public long currSize; // HWM
    public long bytesFlushed;
  }

  /**
   * Holds all the state for the current operation.
   * Since an oplog can only have one operation in progress at any given
   * time we only need a single instance of this class per oplog.
   */
  private class OpState {
    private byte userBits;
    /**
     * How many bytes it will be when serialized
     */
    private int size;
    private boolean needsValue;
    private DiskEntry.Helper.ValueWrapper value;

    public final int getSize() {
      return this.size;
    }

    /**
     * Free up any references to possibly large data.
     */
    public void clear() {
      if (this.value != null) {
        this.value.release();
      }
      this.value = null;
    }

    private void write(ByteBuffer buffer, final int byteLength) throws IOException {
      final int position = buffer.position();
      ByteBuffer bb = getOLF().writeBuf;
      if (!this.value.buffer.writeSerializationHeader(buffer, bb)) {
        flush();
        if (!this.value.buffer.writeSerializationHeader(buffer, bb)) {
          throw new IllegalStateException("Overflow: failed to write header for " +
              this.value.buffer + " -- write buffer with limit = " +
              bb.limit() + " too small?");
        }
      }
      int bytesThisTime;
      while ((bytesThisTime = buffer.remaining()) > 0) {
        boolean needsFlush = false;
        int availableSpace = bb.remaining();
        if (bytesThisTime > availableSpace) {
          needsFlush = true;
          bytesThisTime = availableSpace;
          buffer.limit(buffer.position() + bytesThisTime);
        }
//         logger.info(LocalizedStrings.DEBUG,
//                     "DEBUG " + OverflowOplog.this.toString()
//                     + " offset=" + offset
//                     + " bytes.length=" + bytes.length
//                     + " maxOffset=" + maxOffset
//                     + " bytesThisTime=" + bytesThisTime
//                     + " needsFlush=" + needsFlush
//                     + " bb.position()=" + bb.position()
//                     + " bb.limit()=" + bb.limit()
//                     + " bb.remaining()=" + bb.remaining());
        bb.put(buffer);
        if (needsFlush) {
          flush();
          // restore the limit to original
          buffer.limit(byteLength);
        }
      }
      // rewind the incoming buffer back to the start
      if (position == 0) buffer.rewind();
      else buffer.position(position);
    }

    public void initialize(DiskEntry.Helper.ValueWrapper value,
                           int valueLength,
                           byte userBits)
    {
      this.userBits = userBits;
      this.value = value;

      this.size = 0;
      this.needsValue = EntryBits.isNeedsValue(this.userBits);
      if (this.needsValue) {
        this.size += valueLength;
      }
    }
    public long write() throws IOException {
      int valueLength = 0;
      if (this.needsValue && (valueLength = this.value.size()) > 0) {
        try {
          write(this.value.getBufferRetain(), valueLength);
        } finally {
          this.value.release();
          // done with value so clear it immediately
          clear();
        }
      }
      return valueLength;
    }
  }

//   private static String baToString(byte[] ba) {
//     if ( ba == null) return "null";
//     StringBuffer sb = new StringBuffer();
//     for (int i=0; i < ba.length; i++) {
//       sb.append(ba[i]).append(", ");
//     }
//     return sb.toString();
//   }

  private DiskEntry getNextLiveEntry() {
    DiskEntry result = this.liveEntries.getPrev();
    if (result == this.liveEntries) {
      result = null;
    }
    return result;
  }

  @Override
  public String toString() {
    return "oplog#OV" + getOplogId();
  }
  private boolean compacting;
  private boolean isCompacting() {
    return this.compacting;
  }
  public void prepareForCompact() {
    this.compacting = true;
  }

  private final static ThreadLocal isCompactorThread = new ThreadLocal();
  
  private boolean calledByCompactorThread() {
    if (!this.compacting) return false;
    Object v = isCompactorThread.get();
    return v != null && v == Boolean.TRUE;
  }

  private final Lock compactorLock = new ReentrantLock();
  private void lockCompactor() {
    this.compactorLock.lock();
  }
  private void unlockCompactor() {
    this.compactorLock.unlock();
  }

  public int compact(OplogCompactor compactor) {
    if (!needsCompaction()) {
      return 0;
    }
    isCompactorThread.set(Boolean.TRUE);
    getParent().acquireCompactorReadLock();
    try {
    lockCompactor();
    try {
      if (hasNoLiveValues()) {
        handleNoLiveValues();
        return 0;
      }
      //Asif:Start with a fresh wrapper on every compaction so that 
      //if previous run used some high memory byte array which was
      // exceptional, it gets garbage collected.
      long opStart = getStats().getStatTime();
      BytesAndBitsForCompactor wrapper = new BytesAndBitsForCompactor();

      DiskEntry de;
      DiskEntry lastDe = null;
      boolean compactFailed = !compactor.keepCompactorRunning();
      int totalCount = 0;
      boolean didCompact = false;
      while ((de = getNextLiveEntry()) != null) {
//         logger.info(LocalizedStrings.DEBUG, "DEBUG compact de=" + de);
        if (!compactor.keepCompactorRunning()) {
          compactFailed = true;
          break;
        }
        if (lastDe != null) {
          if (lastDe == de) {
//             logger.info(LocalizedStrings.DEBUG, "DEBUG duplicate entry " + de
//                         + " didCompact=" + didCompact
//                         + " le=" + this.liveEntries
//                         + " lePrev=" + this.liveEntries.getPrev()
//                         + " dePrev=" + de.getPrev()
//                         + " deNext=" + de.getNext());
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
            if (oplogId == -1) {
              // to prevent bug 42304 do a rmLive call
              rmLive(de);
            }
//             logger.info(LocalizedStrings.DEBUG, "DEBUG compact skipping#2 entry " + de + " because it is in oplog#" + oplogId + " instead of oplog#" + getOplogId());
            continue;
          }
          //Bug 42304 - If the entry has been invalidated, don't copy it forward.
          boolean toCompact = getBytesAndBitsForCompaction(de, wrapper);
          if (toCompact) {
            byte[] valueBytes = wrapper.getBytes();
            int length = wrapper.getValidLength();
            byte userBits = wrapper.getBits();
            if (oplogId != did.getOplogId()) {
              // @todo: Is this even possible? Perhaps I should just assert here
              // skip this guy his oplogId changed
              if (did.getOplogId() == -1) {
                // to prevent bug 42304 do a rmLive call
                rmLive(de);
              }
              if (!wrapper.isReusable()) {
                wrapper = new BytesAndBitsForCompactor();
              }
//               logger.info(LocalizedStrings.DEBUG, "DEBUG compact skipping#3 entry because it is in oplog#" + oplogId + " instead of oplog#" + getOplogId());
              continue;
            }
            if(EntryBits.isAnyInvalid(userBits)) {
              rmLive(de);
              if (!wrapper.isReusable()) {
                wrapper = new BytesAndBitsForCompactor();
              }
              continue;
            }
            // write it to the current oplog
            getOplogSet().copyForwardForOverflowCompact(de,
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
    
      if (!compactFailed) {
        if (totalCount == 0) {
          // Need to still remove the oplog even if it had nothing to compact.
          handleNoLiveValues();
        }
      }
      return totalCount;
    } finally {
      unlockCompactor();
      isCompactorThread.remove();
    } 
    } finally {
      getParent().releaseCompactorReadLock();
    }
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
  private boolean getBytesAndBitsForCompaction(DiskEntry entry,
                                               BytesAndBitsForCompactor wrapper)
  {
    // caller is synced on did
    DiskId did = entry.getDiskId();
    byte userBits = 0;
    long oplogOffset = did.getOffsetInOplog();
    boolean foundData = false;
    if (entry.isValueNull()) {
      // Asif: If the mode is synch it is guaranteed to be present in the disk
      foundData = basicGetForCompactor(oplogOffset, false,
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
    } else {
      entry.getDiskId().markForWriting();
      rmLive(entry);
      foundData = false;
    }
    if (foundData) {
      // since the compactor is writing it out clear the async flag
      entry.getDiskId().setPendingAsync(false);
    }
    return foundData;
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
  private boolean basicGetForCompactor(long offsetInOplog, boolean bitOnly,
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
        synchronized (this.crf) {
          final long readPosition = offsetInOplog;
          if (/*!getParent().isSync() since compactor groups writes
                && */ (readPosition+valueLength) > this.crf.bytesFlushed
              && !this.closed) {
            flushAll(); // fix for bug 41205
          }
          final long writePosition = (this.doneAppending)
            ? this.crf.bytesFlushed
            : this.crf.raf.getFilePointer();
          if ((readPosition+valueLength) > writePosition) {
            throw new DiskAccessException(
              LocalizedStrings.Oplog_TRIED_TO_SEEK_TO_0_BUT_THE_FILE_LENGTH_IS_1_OPLOG_FILE_OBJECT_USED_FOR_READING_2.toLocalizedString(
                                                                                                                                        new Object[] {readPosition+valueLength, writePosition, this.crf.raf}), getParent().getName());
          }
          else if (readPosition < 0) {
            throw new DiskAccessException(
              LocalizedStrings.Oplog_CANNOT_FIND_RECORD_0_WHEN_READING_FROM_1
                .toLocalizedString(
                                   new Object[] { Long.valueOf(offsetInOplog), this.diskFile.getPath()}), getParent().getName());
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
        throw new DiskAccessException(
          LocalizedStrings.Oplog_FAILED_READING_FROM_0_OPLOG_DETAILS_1_2_3_4_5_6
          .toLocalizedString(
                             new Object[] {  this.diskFile.getPath(), Long.valueOf(this.oplogId), Long.valueOf(offsetInOplog), Long.valueOf(this.crf.currSize), Long.valueOf(this.crf.bytesFlushed), Boolean.valueOf(/*!dr.isSync() @todo */false), Boolean.valueOf(false)}), ex, getParent().getName());

      }
      catch (IllegalStateException ex) {
        checkClosed();
        throw ex;
      }
    }
    return true;
  } 
}
