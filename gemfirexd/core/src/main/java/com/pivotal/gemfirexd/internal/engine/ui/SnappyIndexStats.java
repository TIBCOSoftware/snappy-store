package com.pivotal.gemfirexd.internal.engine.ui;

/**
 * Created by rishim on 7/3/17.
 */
public class SnappyIndexStats {
  private String indexName;
  private long rowCount = 0;
  private long sizeInMemory = 0;

  public SnappyIndexStats(String indexName) {
    this.indexName = indexName;
  }

  public SnappyIndexStats(String indexName, long rowCount, long sizeInMemory) {
    this.indexName = indexName;
    this.sizeInMemory = sizeInMemory;
    this.rowCount = rowCount;
  }

  public String getIndexName() {
    return indexName;
  }

  public long getRowCount() {
    return rowCount;
  }

  public long getSizeInMemory() {
    return sizeInMemory;
  }
}
