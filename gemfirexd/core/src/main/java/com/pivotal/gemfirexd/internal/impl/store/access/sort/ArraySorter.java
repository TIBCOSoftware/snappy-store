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

package com.pivotal.gemfirexd.internal.impl.store.access.sort;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Comparator;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholdListener;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataInputStream;
import com.gemstone.gemfire.internal.shared.unsafe.ChannelBufferUnsafeDataOutputStream;
import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gemfire.internal.util.ArraySortedCollectionWithOverflow;
import com.gemstone.gemfire.internal.util.TIntObjectHashMapWithDups;
import com.gemstone.gnu.trove.TIntArrayList;
import com.gemstone.gnu.trove.TObjectHashingStrategy;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.RegionAndKey;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRow;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapRowWithLobs;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.property.PropertyUtil;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortController;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortInfo;
import com.pivotal.gemfirexd.internal.iapi.store.access.SortObserver;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.ScanControllerRowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.ScanManager;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Sort;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.TransactionManager;
import com.pivotal.gemfirexd.internal.iapi.types.DataType;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.impl.io.DirFile;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

/**
 * Optimized implementation of the Derby sorter using
 * {@link ArraySortedCollectionWithOverflow}.
 * 
 * @author swale
 * @since gfxd 2.0
 */
public final class ArraySorter extends ArraySortedCollectionWithOverflow
    implements Sort, SortController {

  static final int DEFAULT_MAX_ARRAY_SIZE = 65536;
  static final TIntArrayList ZERO_LIST = new TIntArrayList(0);

  static final long BASE_CLASS_OVERHEAD = ReflectionSingleObjectSizer
      .sizeof(ArraySorter.class);

  final ExecRowSerializer serializer;
  GemFireTransaction tran;
  final SortObserver sortObserver;
  private int numRowsInput;
  private int numRowsOutput;
  private TIntArrayList mergeRunsSize;

  public static final class ExecRowSerializer extends DataSerializer {

    private ExecRow templateRow;
    private GemFireContainer container;
    private RowFormatter formatter;

    /**
     * Type of row which is one of the static values below.
     */
    private byte rowType;
    private static final byte BYTEARRAY = 1;
    private static final byte ARRAY_OF_BYTEARRAY = 2;
    private static final byte OFFHEAP_ROW = 3;
    private static final byte OFFHEAP_ROW_WITH_LOBS = 4;
    private static final byte DVDARRAY = 5;
    private static final byte DYNAMIC = 6;

    private int rowsWritten;
    private int rowsRead;

    private static byte getRowType(Object rowData) {
      final Class<?> cls = rowData.getClass();
      if (cls == byte[].class) {
        return BYTEARRAY;
      }
      else if (cls == byte[][].class) {
        return ARRAY_OF_BYTEARRAY;
      }
      else if (cls == OffHeapRow.class) {
        return OFFHEAP_ROW;
      }
      else if (cls == OffHeapRowWithLobs.class) {
        return OFFHEAP_ROW_WITH_LOBS;
      }
      else if (cls == DataValueDescriptor[].class) {
        return DVDARRAY;
      }
      else {
        SanityManager.THROWASSERT("ExecRowSerializer: unknown row format "
            + cls + ": " + rowData);
        // never reached
        throw new AssertionError("not expected to be reached");
      }
    }

    void initialize(Object rowData) {
      boolean useRowTypeOnly = true;
      if (this.container != null) {
        if (this.container.isOffHeap()) {
          // for offheap case we can always have a combination of offheap+heap
          // e.g. transactional entries
          useRowTypeOnly = false;
        }
        else {
          // check if any schema changed from LOBs to non-LOBs or vice-versa
          if (this.container.hasLobs()) {
            useRowTypeOnly = this.container.hasLobsInAllSchema();
          }
          else {
            useRowTypeOnly = !this.container.hasLobsInAnySchema();
          }
        }
      }
      if (useRowTypeOnly) {
        this.rowType = getRowType(rowData);
      }
      else {
        this.rowType = DYNAMIC;
      }
      if (GemFireXDUtils.TraceTempFileIO) {
        SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
            "Writing rows of type = " + getRowType(this.rowType));
      }
    }

    private static String getRowType(final byte rowType) {
      switch (rowType) {
        case BYTEARRAY:
          return "BYTEARRAY";
        case ARRAY_OF_BYTEARRAY:
          return "ARRAY_OF_BYTEARRAY";
        case OFFHEAP_ROW:
          return "OFFHEAP_ROW";
        case OFFHEAP_ROW_WITH_LOBS:
          return "OFFHEAP_ROW_WITH_LOBS";
        case DVDARRAY:
          return "DVDARRAY";
        case DYNAMIC:
          return "DYNAMIC";
        default:
          return "UNKNOWN";
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean toData(Object o, DataOutput out) throws IOException {
      return toData((ExecRow)o, out);
    }

    final boolean toData(final ExecRow row, final DataOutput out)
        throws IOException {
      final Object rowData = row.getRawRowValue(false);
      if (this.rowType == 0) {
        this.templateRow = row;
        if (row instanceof AbstractCompactExecRow) {
          this.formatter = ((AbstractCompactExecRow)row).getRowFormatter();
          this.container = this.formatter.container;
          if (this.formatter.isTableFormatter()
              && this.formatter.container.singleSchema == null) {
            this.formatter = null;
          }
        }
        initialize(rowData);
      }

      byte rowType = this.rowType;
      if (rowType == DYNAMIC) {
        rowType = getRowType(rowData);
        out.writeByte(rowType);
      }
      switch (rowType) {
        case ARRAY_OF_BYTEARRAY:
          DataSerializer.writeArrayOfByteArrays((byte[][])rowData, out);
          break;
        case BYTEARRAY:
          DataSerializer.writeByteArray((byte[])rowData, out);
          break;
        case OFFHEAP_ROW:
          final OffHeapRow bs = (OffHeapRow)rowData;
          if (bs != null) {
            if (GemFireXDUtils.TraceTempFileIO) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
                  "Writing OffHeap data of length " + bs.getLength());
            }
            bs.toData(out);
          }
          else {
            DataSerializer.writeByteArray(null, out);
          }
          break;
        case OFFHEAP_ROW_WITH_LOBS:
          final OffHeapRowWithLobs bsLobs = (OffHeapRowWithLobs)rowData;
          if (bsLobs != null) {
            if (GemFireXDUtils.TraceTempFileIO) {
              SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
                  "Writing OffHeap lobs data of length " + bsLobs.getLength());
            }
            bsLobs.toData(out);
          }
          else {
            DataSerializer.writeArrayOfByteArrays(null, out);
          }
          break;
        default:
          DataValueDescriptor[] dvds = (DataValueDescriptor[])rowData;
          if (GemFireXDUtils.TraceTempFileIO) {
            StringBuilder sb = new StringBuilder();
            sb.append("Writing DataValueDescriptors of length ").append(
                dvds.length);
            sb.append(" values ");
            try {
              StringBuilder dvdS = toString(dvds, new StringBuilder());
              sb.append(dvdS);
            } catch (StandardException se) {
              throw new GemFireXDRuntimeException(se);
            }
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
                sb.toString());
          }
          DataType.writeDVDArray(dvds, out);
          break;
      }
      DataSerializer.writeTreeSet(row.getAllRegionAndKeyInfo(), out);
      this.rowsWritten++;
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      ExecRow row;
      final GemFireContainer container;
      final RowFormatter formatter;

      byte rowType = this.rowType;
      if (rowType == DYNAMIC) {
        rowType = in.readByte();
      }
      switch (rowType) {
        case BYTEARRAY:
        // OFFHEAP: optimize; allocate in offheap but will need to handle
        // retain/release separately when CompactExecRow is GCed?
        case OFFHEAP_ROW:
          byte[] bytes = DataSerializer.readByteArray(in);
          container = this.container;
          formatter = this.formatter;
          row = container.newExecRowFromBytes(bytes,
              formatter != null ? formatter : container.getRowFormatter(bytes));
          break;
        case ARRAY_OF_BYTEARRAY:
        // OFFHEAP: optimize; allocate in offheap but will need to handle
        // retain/release separately when CompactExecRow is GCed?
        case OFFHEAP_ROW_WITH_LOBS:
          byte[][] byteArrays = DataSerializer.readArrayOfByteArrays(in);
          container = this.container;
          formatter = this.formatter;
          row = container.newExecRowFromByteArrays(byteArrays,
              formatter != null ? formatter : container.getRowFormatter(
                  byteArrays));
          break;
        default:
          DataValueDescriptor[] dvds = DataType.readDVDArray(in);
          if (GemFireXDUtils.TraceTempFileIO) {
            StringBuilder sb = new StringBuilder();
            sb.append("Read DataValueDescriptors of length ").append(
                dvds.length);
            sb.append(" values ");
            try {
              StringBuilder dvdS = toString(dvds, new StringBuilder());
              sb.append(dvdS);
            } catch (StandardException se) {
              throw new GemFireXDRuntimeException(se);
            }
            SanityManager.DEBUG_PRINT(GfxdConstants.TRACE_TEMP_FILE_IO,
                sb.toString());
          }
          row = this.templateRow.getNewNullRow();
          row.setRowArray(dvds);
          break;
      }
      row.setAllRegionAndKeyInfo(DataSerializer.<RegionAndKey> readTreeSet(in));
      this.rowsRead++;
      return row;
    }

    public int getRowsWritten() {
      return this.rowsWritten;
    }

    public int getRowsRead() {
      return this.rowsRead;
    }

    public ExecRow getTemplateRow() {
      return this.templateRow;
    }

    private StringBuilder toString(DataValueDescriptor[] dvd, StringBuilder sb)
        throws StandardException {
      for (int i = 0; i < dvd.length; i++) {
        sb.append(dvd[i].getString()).append("(").append(dvd[i].getTypeName())
            .append("),");
      }
      return sb;
    }

    @Override
    public int getId() {
      throw new InternalGemFireError(
          "ExecRowSerializer.getId: not expected to be invoked");
    }

    @Override
    public Class<?>[] getSupportedClasses() {
      throw new InternalGemFireError(
          "ExecRowSerializer.getSupportedClasses: not expected to be invoked");
    }
  }

  private static final ObjectSizer rowSizer = new ObjectSizer() {
    @Override
    public int sizeof(Object o) {
      long size;
      try {
        size = ((ExecRow)o).estimateRowSize();
        return size < Integer.MAX_VALUE ? (int)size : Integer.MAX_VALUE;
      } catch (StandardException se) {
        throw new GemFireXDRuntimeException(se);
      }
    }
  };

  public static final class RowCompare implements Comparator<Object>,
      TObjectHashingStrategy {

    private static final long serialVersionUID = -2971361542262894575L;

    /**
     * Column ordering flags contains:
     * 
     * 2nd bit: 1 if ascending and 0 if descending
     * 
     * 3rd bit: 1 if nullsLow==true and 0 otherwise
     * 
     * Higher order integer contains the column order.
     * 
     * Any change/addition in above scheme will require corresponding change in
     * compare impl which assumes that there is nothing other than these two
     * flags.
     */
    private final long[] columnOrderingFlags;

    private int numComparisons;

    RowCompare(ColumnOrdering[] columnOrdering) {
      this.columnOrderingFlags = new long[columnOrdering.length];
      for (int i = 0; i < columnOrdering.length; i++) {
        final ColumnOrdering order = columnOrdering[i];
        long flags = (order.getIsAscending() ? 0x2 : 0x0);
        if (order.getIsNullsOrderedLow()) {
          flags |= 0x4;
        }
        flags |= (((long)order.getColumnId() + 1L) << Integer.SIZE);
        this.columnOrderingFlags[i] = flags;
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final int compare(final Object o1, final Object o2) {
      final ExecRow r1 = (ExecRow)o1;
      final ExecRow r2 = (ExecRow)o2;

      this.numComparisons++;
      // Get the number of columns we have to compare.
      final long[] columnOrderingFlags = this.columnOrderingFlags;
      final int colsToCompare = columnOrderingFlags.length;
      int r;

      // Compare the columns specified in the column
      // ordering array.
      try {
        for (int i = 0; i < colsToCompare; i++) {
          // Get columns to compare.
          final long flags = columnOrderingFlags[i];
          final int iflags = (int)(flags & 0xffffffff);
          final int column = (int)((flags >>> Integer.SIZE) & 0xffffffff);

          // If the columns don't compare equal, we're done.
          // Return the sense of the comparison.
          // currently only nullsLow is in the flags
          if ((r = r1.compare(r2, column, iflags > 0x2)) != 0) {
            // flags & 0x2 will give 2 or 0, so (-1) gives 1 or -1
            // for ascending and descending respectively
            return r * ((iflags & 0x2) - 1);
          }
        }

        // We made it through all the columns, and they must have
        // all compared equal. So return that the rows compare equal.
        return 0;
      } catch (StandardException se) {
        throw new GemFireXDRuntimeException(se);
      }
    }

    public final int getNumComparisons() {
      return this.numComparisons;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final int computeHashCode(final Object o) {
      final ExecRow r = (ExecRow)o;

      // Get the number of columns we have to use.
      final long[] columnOrderingFlags = this.columnOrderingFlags;
      final int colsToCompare = columnOrderingFlags.length;

      int hash = 0;
      for (int i = 0; i < colsToCompare; i++) {
        // Get columns to compare.
        final int column = (int)((columnOrderingFlags[i] >>> Integer.SIZE)
            & 0xffffffff);
        hash = r.computeHashCode(column, hash);
      }
      return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean equals(final Object o1, final Object o2) {
      final ExecRow r1 = (ExecRow)o1;
      final ExecRow r2 = (ExecRow)o2;

      this.numComparisons++;
      // Get the number of columns we have to compare.
      final long[] columnOrderingFlags = this.columnOrderingFlags;
      final int colsToCompare = columnOrderingFlags.length;

      // Compare the columns specified in the column
      // ordering array.
      try {
        for (int i = 0; i < colsToCompare; i++) {
          // Get columns to compare.
          final int column = (int)((columnOrderingFlags[i] >>> Integer.SIZE)
              & 0xffffffff);
          // If the columns don't compare equal, we're done.
          if (r1.compare(r2, column, false) != 0) {
            return false;
          }
        }

        // We made it through all the columns, and they must have
        // all compared equal. So return that the rows compare equal.
        return true;
      } catch (StandardException se) {
        throw new GemFireXDRuntimeException(se);
      }
    }
  }

  // TODO: PERF: also carry through projection from higher layers even if it
  // has not been pushed down to minimize data written to disk
  // also avoid overflowing CompactExecRows whose value points to something
  // which is still in region's in-memory data, or overflow just the oplogId
  // and disk offset
  protected ArraySorter(GemFireTransaction tran, RowCompare comparator,
      SortObserver sortObserver, final int maxUnsortedArraySize,
      final long maxSortLimit, MemoryThresholdListener mtl, String overflowDir,
      GemFireCacheImpl cache) {
    super(comparator, sortObserver, comparator, rowSizer, maxUnsortedArraySize,
        maxSortLimit, mtl, overflowDir, cache);
    this.serializer = new ExecRowSerializer();
    this.tran = tran;
    this.sortObserver = sortObserver;
    this.mergeRunsSize = ZERO_LIST;
  }

  public static ArraySorter create(GemFireTransaction tran,
      ColumnOrdering[] columnOrdering, SortObserver sortObserver,
      boolean alreadyInOrder, long estimatedRows, int estimatedRowSize,
      long maxSortLimit) {
    final GemFireStore store = Misc.getMemStore();
    DirFile tmpDir = store.getDatabase().getTempDir();
    String overflowDir;
    if (tmpDir != null) {
      overflowDir = tmpDir.getAbsolutePath();
    }
    else {
      overflowDir = PropertyUtil.getSystemProperty("java.io.tmpdir");
      if (overflowDir == null) {
        overflowDir = ".";
      }
    }
    // expand the estimatedRows by factor of 2 and then check if it is smaller
    // than DEFAULT_MAX_ARRAY_SIZE; if true then use that as unsorted array size
    if (estimatedRows <= 1 || estimatedRows > (DEFAULT_MAX_ARRAY_SIZE >> 1)) {
      estimatedRows = (DEFAULT_MAX_ARRAY_SIZE >> 1);
    }
    return new ArraySorter(tran, new RowCompare(columnOrdering), sortObserver,
        (int)(estimatedRows << 1), maxSortLimit, store.thresholdListener(),
        overflowDir, store.getGemFireCache());
  }

  public final ExecRowSerializer getDataSerializer() {
    return this.serializer;
  }

  @Override
  protected OverflowData overflowElementArray(final Object[] elements) {
    OverflowData overflowData = super.overflowElementArray(elements);
    if (this.mergeRunsSize == ZERO_LIST) {
      this.mergeRunsSize = new TIntArrayList();
    }
    this.mergeRunsSize.add(overflowData.size());
    return overflowData;
  }

  @Override
  protected int writeElements(final Object[] elements,
      final ChannelBufferUnsafeDataOutputStream dos) throws IOException {
    int numElements = 0;
    final ExecRowSerializer rowSerializer = this.serializer;
    for (Object element : elements) {
      // last array may have empty elements at the end
      if (element != null) {
        final ExecRow row = (ExecRow)element;
        rowSerializer.toData(row, dos);
        numElements++;
        continue;
      }
      else {
        break;
      }
    }
    return numElements;
  }

  @Override
  protected Object readElement(final ChannelBufferUnsafeDataInputStream in)
      throws ClassNotFoundException, IOException {
    return this.serializer.fromData(in);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean insert(ExecRow row) throws StandardException {
    this.numRowsInput++;
    final SortObserver sortObserver = this.sortObserver;
    if (sortObserver != null
        && (row = sortObserver.insertNonDuplicateKey(row)) == null) {
      return false;
    }
    try {
      if (super.add(row)) {
        this.numRowsOutput++;
        return true;
      }
      else {
        return false;
      }
    } catch (GemFireXDRuntimeException re) {
      if (re.getCause() instanceof StandardException) {
        throw (StandardException)re.getCause();
      }
      else {
        throw re;
      }
    }
  }

  @Override
  public long estimateMemoryUsage(ExecRow sortResultRow)
      throws StandardException {
    long numMemElements = 0;
    // start with basic object overhead
    long overheads = BASE_CLASS_OVERHEAD;
    // add approx overheads for overflowed and other memory data
    final Object[] elements = this.elements;
    // for each object in elements
    overheads += (elements.length * (ReflectionSingleObjectSizer.OBJECT_SIZE
        + ReflectionSingleObjectSizer.REFERENCE_SIZE));
    if (this.overflowOutputChannel != null) {
      // add some overhead for the file channels (not sure how much exactly and
      // sizer may not give the right picture due to native handles involved)
      overheads += 64;
    }
    final int currentArrayIndex = this.currentArrayIndex;
    for (int i = 0; i < currentArrayIndex; i++) {
      final Object elementArray = elements[i];
      if (elementArray instanceof Object[]) {
        final Object[] elems = (Object[])elementArray;
        numMemElements += elems.length;
        // for Object[].length and references
        overheads += (4 + elems.length
            * ReflectionSingleObjectSizer.REFERENCE_SIZE);
      }
      else {
        overheads += (((OverflowData)elementArray).bufferSize()
            + OverflowData.BASE_CLASS_OVERHEAD);
      }
    }
    final Object[] currentArray = this.currentArray;
    if (currentArray != null) {
      numMemElements += this.currentArrayPos;
      // for current Object[].length and references
      overheads += (4 + currentArray.length
          * ReflectionSingleObjectSizer.REFERENCE_SIZE);
    }

    // lastly the overhead of dups map
    final TIntObjectHashMapWithDups dups = this.hashCodeToObjectMapWithDups;
    if (dups != null) {
      // three arrays here: byte[], int[], object[]
      overheads += dups.capacity() *
          (1 + 4 + ReflectionSingleObjectSizer.REFERENCE_SIZE);
      overheads += (3 * ReflectionSingleObjectSizer.OBJECT_SIZE);
    }

    long estimatedRowSize = this.estimatedObjectSize;
    if (estimatedRowSize <= 0) {
      estimatedRowSize = (sortResultRow != null ? sortResultRow
          .estimateRowSize() : 1L);
    }
    return overheads + (numMemElements * estimatedRowSize);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void completedInserts() {
    // remove from transaction
    final GemFireTransaction tran = this.tran;
    if (tran != null) {
      tran.closeMe(this);
      this.tran = null;
    }
  }

  @Override
  public void clear() {
    super.clear();
    this.tran = null;
    this.numRowsInput = 0;
    this.numRowsOutput = 0;
    this.mergeRunsSize = ZERO_LIST;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SortInfo getSortInfo() throws StandardException {
    return new MergeSortInfo("external", this.numRowsInput, this.numRowsOutput,
        numOverflowedElements(), this.mergeRunsSize,
        ((RowCompare)this.comparator).getNumComparisons());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SortController open(TransactionManager tran) throws StandardException {
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ScanManager openSortScan(TransactionManager tran, boolean hold)
      throws StandardException {
    return new ArraySortScan(this, tran, hold);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ScanControllerRowSource openSortRowSource(TransactionManager tran)
      throws StandardException {
    return new ArraySortScan(this, tran, false);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drop(TransactionController tran) throws StandardException {
    super.close();
  }
}
