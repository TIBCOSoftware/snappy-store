statspec bucketCountStock *server* PartitionedRegionStats *STOCK* bucketCount
filter=none combine=raw ops=max? trimspec=stock
;
statspec primaryBucketCountStock *server* PartitionedRegionStats *STOCK* primaryBucketCount
filter=none combine=raw ops=max? trimspec=stock
;
statspec totalEntriesStock *server* PartitionedRegionStats *STOCK* dataStoreEntryCount
filter=none combine=raw ops=max? trimspec=stock
;
statspec totalBytesStock *server* PartitionedRegionStats *STOCK* dataStoreBytesInUse
filter=none combine=raw ops=max? trimspec=stock
;
expr entriesPerBucketStock = totalEntriesStock / bucketCountStock ops=max?
;
expr bytesPerBucketStock = totalBytesStock / bucketCountStock ops=max?
;
