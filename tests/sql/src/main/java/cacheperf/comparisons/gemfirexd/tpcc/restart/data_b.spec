//------------------------------------------------------------------------------
// entry counts, and sizes
//------------------------------------------------------------------------------

statspec totalEntriesCustomer_b *server* PartitionedRegionStats *CUSTOMER* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_b
;
statspec totalBytesCustomer_b *server* PartitionedRegionStats *CUSTOMER* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_b
;
expr bytesPerCustomer_b = totalBytesCustomer_b / totalEntriesCustomer_b ops=max?
;

statspec totalEntriesOrder_b *server* PartitionedRegionStats *ORDER* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_b
;
statspec totalBytesOrder_b *server* PartitionedRegionStats *ORDER* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_b
;
expr bytesPerOrder_b = totalBytesOrder_b / totalEntriesOrder_b ops=max?
;

statspec totalEntriesStock_b *server* PartitionedRegionStats *STOCK* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_b
;
statspec totalBytesStock_b *server* PartitionedRegionStats *STOCK* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_b
;
expr bytesPerStock_b = totalBytesStock_b / totalEntriesStock_b ops=max?
;
