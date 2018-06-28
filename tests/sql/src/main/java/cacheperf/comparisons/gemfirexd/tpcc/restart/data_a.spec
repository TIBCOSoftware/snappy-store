//------------------------------------------------------------------------------
// entry counts, and sizes
//------------------------------------------------------------------------------

statspec totalEntriesCustomer_a *server* PartitionedRegionStats *CUSTOMER* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_a
;
statspec totalBytesCustomer_a *server* PartitionedRegionStats *CUSTOMER* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_a
;
expr bytesPerCustomer_a = totalBytesCustomer_a / totalEntriesCustomer_a ops=max?
;

statspec totalEntriesOrder_a *server* PartitionedRegionStats *ORDER* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_a
;
statspec totalBytesOrder_a *server* PartitionedRegionStats *ORDER* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_a
;
expr bytesPerOrder_a = totalBytesOrder_a / totalEntriesOrder_a ops=max?
;

statspec totalEntriesStock_a *server* PartitionedRegionStats *STOCK* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_a
;
statspec totalBytesStock_a *server* PartitionedRegionStats *STOCK* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=transactions_a
;
expr bytesPerStock_a = totalBytesStock_a / totalEntriesStock_a ops=max?
;
