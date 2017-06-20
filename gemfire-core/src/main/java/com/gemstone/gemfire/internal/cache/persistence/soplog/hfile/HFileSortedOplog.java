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
package com.gemstone.gemfire.internal.cache.persistence.soplog.hfile;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import com.gemstone.gemfire.cache.hdfs.HDFSIOException;
import com.gemstone.gemfire.internal.cache.persistence.soplog.AbstractSortedReader;
import com.gemstone.gemfire.internal.cache.persistence.soplog.ComponentLogWriter;
import com.gemstone.gemfire.internal.cache.persistence.soplog.DelegatingSerializedComparator;
import com.gemstone.gemfire.internal.cache.persistence.soplog.ReversingSerializedComparator;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedBuffer.BufferIterator;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedOplog;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedOplogFactory.SortedOplogConfiguration;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedReader.Metadata;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedReader.SerializedComparator;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedReader.SortedIterator;
import com.gemstone.gemfire.internal.cache.persistence.soplog.SortedReader.SortedStatistics;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.util.Bytes;
import com.gemstone.gemfire.internal.util.Hex;
import com.gemstone.gemfire.internal.util.LogService;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFile.Reader;
import org.apache.hadoop.hbase.io.hfile.HFile.Writer;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFileContextBuilder;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.util.BloomFilterFactory;
import org.apache.hadoop.hbase.util.BloomFilterWriter;
import org.apache.hadoop.hbase.util.FSUtils;

/**
 * Provides a soplog backed by an HFile.
 * 
 * @author bakera
 */
public class HFileSortedOplog implements SortedOplog {
  public static final byte[] MAGIC          = new byte[] { 0x53, 0x4F, 0x50 };
  public static final byte[] VERSION_1      = new byte[] { 0x1 };
  
  // FileInfo is not visible
  private static final byte[] AVG_KEY_LEN   = "hfile.AVG_KEY_LEN".getBytes();
  private static final byte[] AVG_VALUE_LEN = "hfile.AVG_VALUE_LEN".getBytes();
  
  /** a default bloom filter */
  private static final BloomFilter DUMMY_BLOOM = new BloomFilter() {
    @Override
    public boolean mightContain(byte[] key) {
      return true;
    }
  };

  static final Configuration hconf;
  private static final FileSystem fs;

  static {
    // Leave these HBase properties set to defaults for now
    //
    // hfile.block.cache.size (25% of heap)
    // hbase.hash.type (murmur)
    // hfile.block.index.cacheonwrite (false)
    // hfile.index.block.max.size (128k)
    // hfile.format.version (2)
    // io.storefile.bloom.block.size (128k)
    // hfile.block.bloom.cacheonwrite (false)
    // hbase.rs.cacheblocksonwrite (false)
    // hbase.offheapcache.minblocksize (64k)
    // hbase.offheapcache.percentage (0)
    hconf = new Configuration();

    hconf.setBoolean("hbase.metrics.showTableName", true);
    // [sumedh] should not be required with the new metrics2
    // SchemaMetrics.configureGlobally(hconf);

    try {
      fs = FileSystem.get(hconf);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static enum InternalMetadata {
    /** identifies the soplog as a gemfire file, required */
    GEMFIRE_MAGIC,
    
    /** identifies the soplog version, required */
    VERSION,
    
    /** identifies the statistics data */
    STATISTICS,

    /** identifies the names of embedded comparators */
    COMPARATORS;

    public byte[] bytes() {
      return ("gemfire." + name()).getBytes();
    }
  }
  
  /** the logger */
  private final ComponentLogWriter logger;
  
  /** the configuration */
  private final SortedOplogConfiguration sopConfig;
  
  /** the hfile cache config */
  private final CacheConfig hcache;
  
  /** the hfile location */
  private Path path;
  
  public HFileSortedOplog(File hfile, SortedOplogConfiguration sopConfig) throws IOException {
    assert hfile != null;
    assert sopConfig != null;
    
    this.sopConfig = sopConfig;
    path = fs.makeQualified(new Path(hfile.toString()));

//    hcache = new CacheConfig(hconf, sopConfig.getCacheDataBlocksOnRead(), sopConfig.getBlockCache(),
//        HFileSortedOplogFactory.convertStatistics(sopConfig.getStatistics(), sopConfig.getStoreStatistics()));
    hcache = new CacheConfig(hconf);
    logger = ComponentLogWriter.getSoplogLogWriter(sopConfig.getName(), LogService.logger());
  }

  @Override
  public SortedOplogReader createReader() throws IOException {
    if (logger.fineEnabled()) {
      logger.fine("Creating an HFile reader on " + path);
    }
    
    return new HFileSortedOplogReader();
  }

  @Override
  public SortedOplogWriter createWriter() throws IOException {
    if (logger.fineEnabled()) {
      logger.fine("Creating an HFile writer on " + path);
    }

    return new HFileSortedOplogWriter();  
  }
  
  SortedOplogConfiguration getConfiguration() {
    return sopConfig;
  }
  
  private class HFileSortedOplogReader extends AbstractSortedReader implements SortedOplogReader {
    private final Reader reader;
    private final BloomFilter bloom;
    private final SortedStatistics stats;
    private volatile boolean closed;
    
    public HFileSortedOplogReader() throws IOException {
      reader = HFile.createReader(fs, path, hcache, hconf);
      validate();
      
      stats = new HFileSortedStatistics(reader);
      closed = false;
      
      if (reader.getComparator() instanceof DelegatingSerializedComparator) {
        loadComparators((DelegatingSerializedComparator) reader.getComparator());
      }

      DataInput bin = reader.getGeneralBloomFilterMetadata();
      if (bin != null) {
        final org.apache.hadoop.hbase.util.BloomFilter hbloom = BloomFilterFactory.createFromMeta(bin, reader);
        if (reader.getComparator() instanceof DelegatingSerializedComparator) {
          loadComparators((DelegatingSerializedComparator) hbloom.getComparator());
        }

        bloom = new BloomFilter() {
          @Override
          public boolean mightContain(byte[] key) {
            assert key != null;
            
            long start = sopConfig.getStatistics().getBloom().begin();
            boolean foundKey = hbloom.contains(key, 0, key.length, null);
            sopConfig.getStatistics().getBloom().end(start);
            
            if (logger.finestEnabled()) {
              logger.finest(String.format("Bloom check on %s for key %s: %b", 
                  path, Hex.toHex(key), foundKey));
            }
            return foundKey;
          }
        };
        
      } else {
        bloom = DUMMY_BLOOM;
      }
    }
    
    @Override
    public boolean mightContain(byte[] key) {
      return getBloomFilter().mightContain(key);
    }

    @Override
    public ByteBuffer read(byte[] key) throws IOException {
      assert key != null;
      
      if (logger.finestEnabled()) {
        logger.finest(String.format("Reading key %s from %s", Hex.toHex(key), path));
      }

      long start = sopConfig.getStatistics().getRead().begin();
      try {
        HFileScanner seek = reader.getScanner(true, true);
        if (seek.seekTo(key) == 0) {
          ByteBuffer val = seek.getValue();
          sopConfig.getStatistics().getRead().end(val.remaining(), start);
          
          return val;
        }
        
        sopConfig.getStatistics().getRead().end(start);
        sopConfig.getStatistics().getBloom().falsePositive();
        return null;
        
      } catch (IOException e) {
        sopConfig.getStatistics().getRead().error(start);
        throw (IOException) e.fillInStackTrace();
      }
    }

    @Override
    public SortedIterator<ByteBuffer> scan(
        byte[] from, boolean fromInclusive, 
        byte[] to, boolean toInclusive,
        boolean ascending,
        MetadataFilter filter) throws IOException {
      if (filter == null || filter.accept(getMetadata(filter.getName()))) {
        SerializedComparator tmp = (SerializedComparator) reader.getComparator();
        tmp = ascending ? tmp : ReversingSerializedComparator.reverse(tmp); 
  
//        HFileScanner scan = reader.getScanner(true, false, ascending, false);
        HFileScanner scan = reader.getScanner(true, false, false);
        return new HFileSortedIterator(scan, tmp, from, fromInclusive, to, toInclusive);
      }
      return new BufferIterator(Collections.<byte[], byte[]>emptyMap().entrySet().iterator());
    }

    @Override
    public SerializedComparator getComparator() {
      return (SerializedComparator) reader.getComparator();
    }

    @Override
    public SortedStatistics getStatistics() {
      return stats;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }
    
    @Override
    public void close() throws IOException {
      if (logger.fineEnabled()) {
        logger.fine("Closing reader on " + path);
      }
      reader.close();
      closed = true;
    }

    @Override
    public BloomFilter getBloomFilter() {
      return bloom;
    }

    @Override
    public byte[] getMetadata(Metadata name) throws IOException {
      assert name != null;
      
      return reader.loadFileInfo().get(name.bytes());
    }
    
    @Override
    public File getFile() {
      return new File(path.toUri());
    }
    
    @Override
    public String getFileName() {
      return path.getName();
    }
   
    @Override
    public long getModificationTimeStamp() throws IOException {
      FileStatus[] stats = FSUtils.listStatus(fs, path, null);
      if (stats != null && stats.length == 1) {
        return stats[0].getModificationTime();
      } else {
        return 0;
      }
    }
    
    @Override
    public void rename(String name) throws IOException {
      Path parent = path.getParent();
      Path newPath = new Path(parent, name);
      fs.rename(path, newPath);
      // update path to point to the new path
      path = newPath;
    }
    
    @Override
    public void delete() throws IOException {
      fs.delete(path, false);
    }
    
    @Override
    public String toString() {
      return path.toString();
    }
    
    private byte[] getMetadata(InternalMetadata name) throws IOException {
      return reader.loadFileInfo().get(name.bytes());
    }
    
    private void validate() throws IOException {
      // check magic
      byte[] magic = getMetadata(InternalMetadata.GEMFIRE_MAGIC);
      if (!Arrays.equals(magic, MAGIC)) {
        throw new IOException(LocalizedStrings.Soplog_INVALID_MAGIC.toLocalizedString(Hex.toHex(magic)));
      }
      
      // check version compatibility
      byte[] ver = getMetadata(InternalMetadata.VERSION);
      if (logger.fineEnabled()) {
        logger.fine("Soplog version is " + Hex.toHex(ver));
      }
      
      if (!Arrays.equals(ver, VERSION_1)) {
        throw new IOException(LocalizedStrings.Soplog_UNRECOGNIZED_VERSION.toLocalizedString(Hex.toHex(ver)));
      }
    }
    
    private void loadComparators(DelegatingSerializedComparator comparator) throws IOException {
      byte[] raw = reader.loadFileInfo().get(InternalMetadata.COMPARATORS.bytes());
      assert raw != null;

      DataInput in = new DataInputStream(new ByteArrayInputStream(raw));
      comparator.setComparators(readComparators(in));
    }
    
    private SerializedComparator[] readComparators(DataInput in) throws IOException {
      try {
        SerializedComparator[] comps = new SerializedComparator[in.readInt()];
        assert comps.length > 0;
        
        for (int i = 0; i < comps.length; i++) {
          comps[i] = (SerializedComparator) Class.forName(in.readUTF()).newInstance();
          if (comps[i] instanceof DelegatingSerializedComparator) {
            ((DelegatingSerializedComparator) comps[i]).setComparators(readComparators(in));
          }
        }
        return comps;
        
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
  }
  
  private class HFileSortedOplogWriter implements SortedOplogWriter {
    private final Writer writer;
    private final BloomFilterWriter bfw;
    
    public HFileSortedOplogWriter() throws IOException {
      HFileContext hcontext = new HFileContextBuilder()
          .withBlockSize(sopConfig.getBlockSize())
          .withBytesPerCheckSum(sopConfig.getBytesPerChecksum())
          .withChecksumType(HFileSortedOplogFactory.convertChecksum(
              sopConfig.getChecksum()))
          .withCompression(HFileSortedOplogFactory.convertCompression(
              sopConfig.getCompression()))
          .withDataBlockEncoding(HFileSortedOplogFactory.convertEncoding(
              sopConfig.getKeyEncoding()).getDataBlockEncoding())
          .build();
      writer = HFile.getWriterFactory(hconf, hcache)
          .withPath(fs, path)
          .withFileContext(hcontext)
          .withComparator(sopConfig.getComparator())
          .create();

      bfw = sopConfig.isBloomFilterEnabled() ?
//          BloomFilterFactory.createGeneralBloomAtWrite(hconf, hcache, BloomType.ROW,
//              0, writer, sopConfig.getComparator())
          BloomFilterFactory.createGeneralBloomAtWrite(hconf, hcache, BloomType.ROW,
              0, writer)
          : null;
    }

    @Override
    public void append(byte[] key, byte[] value) throws IOException {
      assert key != null;
      assert value != null;

      if (logger.finestEnabled()) {
        logger.finest(String.format("Appending key %s to %s", Hex.toHex(key), path));
      }

      try {
        writer.append(key, value);
        if (bfw != null) {
          bfw.add(key, 0, key.length);
        }
      } catch (IOException e) {
        throw (IOException) e.fillInStackTrace();
      }
    }

    @Override
    public void append(ByteBuffer key, ByteBuffer value) throws IOException {
      assert key != null;
      assert value != null;

      if (logger.finestEnabled()) {
        logger.finest(String.format("Appending key %s to %s", 
            Hex.toHex(key.array(), key.arrayOffset(), key.remaining()), path));
      }

      try {
        byte[] keyBytes = key.array();
        final int keyOffset = key.arrayOffset();
        final int keyLength = key.remaining();
        if (keyOffset != 0 || keyLength != keyBytes.length) {
          byte[] newKeyBytes = new byte[keyLength];
          System.arraycopy(keyBytes, keyOffset, newKeyBytes, 0, keyLength);
          keyBytes = newKeyBytes;
        }
        byte[] valueBytes = value.array();
        final int valueOffset = value.arrayOffset();
        final int valueLength = value.remaining();
        if (valueOffset != 0 || valueLength != valueBytes.length) {
          byte[] newValueBytes = new byte[valueLength];
          System.arraycopy(valueBytes, valueOffset, newValueBytes, 0,
              valueLength);
          valueBytes = newValueBytes;
        }
        writer.append(keyBytes, valueBytes);
        if (bfw != null) {
          bfw.add(keyBytes, 0, keyLength);
        }
      } catch (IOException e) {
        throw (IOException) e.fillInStackTrace();
      }
    }

    @Override
    public void close(EnumMap<Metadata, byte[]> metadata) throws IOException {
      if (logger.fineEnabled()) {
        logger.fine("Finalizing and closing writer on " + path);
      }

      if (bfw != null) {
        bfw.compactBloom();
        writer.addGeneralBloomFilter(bfw);
      }
      
      // append system metadata
      writer.appendFileInfo(InternalMetadata.GEMFIRE_MAGIC.bytes(), MAGIC);
      writer.appendFileInfo(InternalMetadata.VERSION.bytes(), VERSION_1);
      
      // append comparator info
//      if (writer.getComparator() instanceof DelegatingSerializedComparator) {
//        ByteArrayOutputStream bos = new ByteArrayOutputStream();
//        DataOutput out = new DataOutputStream(bos);
//
//        writecomparatorinfo(out, ((delegatingserializedcomparator) writer.getcomparator()).getcomparators());
//        writer.appendFileInfo(InternalMetadata.COMPARATORS.bytes(), bos.toByteArray());
//      }
      
      // TODO write statistics data to soplog
      // writer.appendFileInfo(Meta.STATISTICS.toBytes(), null);

      // append user metadata
      if (metadata != null) {
        for (Entry<Metadata, byte[]> entry : metadata.entrySet()) {
          writer.appendFileInfo(entry.getKey().name().getBytes(), entry.getValue());
        }
      }
      
      writer.close();
    }
    
    @Override
    public void closeAndDelete() throws IOException {
      if (logger.fineEnabled()) {
        logger.fine("Closing writer and deleting " + path);
      }

      writer.close();
      new File(writer.getPath().toUri()).delete();
    }
    
//    private void writeComparatorInfo(DataOutput out, SerializedComparator[] comparators) throws IOException {
//      out.writeInt(comparators.length);
//      for (SerializedComparator sc : comparators) {
//        out.writeUTF(sc.getClass().getName());
//        if (sc instanceof DelegatingSerializedComparator) {
//          writeComparatorInfo(out, ((DelegatingSerializedComparator) sc).getComparators());
//        }
//      }
//    }
  }
  
  private class HFileSortedIterator implements SortedIterator<ByteBuffer> {
    private final HFileScanner scan;
    private final SerializedComparator comparator;
    
    private final byte[] from;
    private final boolean fromInclusive;

    private final byte[] to;
    private final boolean toInclusive;
    
    private final long start;
    private long bytes;
    
    private boolean foundNext;
    
    private ByteBuffer key;
    private ByteBuffer value;
    
    public HFileSortedIterator(HFileScanner scan, SerializedComparator comparator, 
        byte[] from, boolean fromInclusive, 
        byte[] to, boolean toInclusive) throws IOException {
      this.scan = scan;
      this.comparator = comparator;
      this.from = from;
      this.fromInclusive = fromInclusive;
      this.to = to;
      this.toInclusive = toInclusive;
      
      assert from == null 
          || to == null 
          || comparator.compare(from, 0, from.length, to, 0, to.length) <= 0;
      
      start = sopConfig.getStatistics().getScan().begin();
      foundNext = evalFrom();
    }
    
    @Override
    public ByteBuffer key() {
      return key;
    }
    
    @Override 
    public ByteBuffer value() {
      return value;
    }

    @Override
    public boolean hasNext() {
      if (!foundNext) {
        foundNext = step();
      }
      return foundNext;
    }
    
    @Override
    public ByteBuffer next() {
      long startNext = sopConfig.getStatistics().getScan().beginIteration();
      
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      
      foundNext = false;
      key = scan.getKey();
      value = scan.getValue();
      
      int len = key.remaining() + value.remaining(); 
      bytes += len;
      sopConfig.getStatistics().getScan().endIteration(len, startNext);
      
      return key;
    }
    
    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      sopConfig.getStatistics().getScan().end(bytes, start);
    }

    private boolean step() {
      try {
        if (!scan.isSeeked()) {
          return false;
          
        } else  if (scan.next() && evalTo()) {
          return true;
        }
      } catch (IOException e) {
        throw new HDFSIOException("Error from HDFS during iteration", e);
      }
      return false;
    }
    
    private boolean evalFrom() throws IOException {
      if (from == null) {
        return scan.seekTo() && evalTo();
        
      } else {
        int compare = scan.seekTo(from);
        if (compare < 0) {
          return scan.seekTo() && evalTo();
          
        } else if (compare == 0 && fromInclusive) {
          return true;
          
        } else {
          return step();
        }
      }
    }
    
    private boolean evalTo() throws IOException {
      int compare = -1;
      if (to != null) {
        ByteBuffer key = scan.getKey();
        compare = comparator.compare(
            key.array(), key.arrayOffset(), key.remaining(), 
            to, 0, to.length);
      }

      return compare < 0 || (compare == 0 && toInclusive);
    }
  }
  
  private static class HFileSortedStatistics implements SortedStatistics {
    private final Reader reader;
    private final int keySize;
    private final int valueSize;
    
    public HFileSortedStatistics(Reader reader) throws IOException {
      this.reader = reader;

      byte[] sz = reader.loadFileInfo().get(AVG_KEY_LEN);
      keySize = Bytes.toInt(sz[0], sz[1], sz[2], sz[3]);

      sz = reader.loadFileInfo().get(AVG_VALUE_LEN);
      valueSize = Bytes.toInt(sz[0], sz[1], sz[2], sz[3]);
    }

    @Override
    public long keyCount() {
      return reader.getEntries();
    }

    @Override
    public byte[] firstKey() {
      return reader.getFirstKey();
    }

    @Override
    public byte[] lastKey() {
      return reader.getLastKey();
    }

    @Override
    public double avgKeySize() {
      return keySize;
    }
    
    @Override
    public double avgValueSize() {
      return valueSize;
    }
    
    @Override
    public void close() {
    }
  }
}
