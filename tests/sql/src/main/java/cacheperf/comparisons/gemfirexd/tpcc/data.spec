//------------------------------------------------------------------------------
// entry counts, and sizes
//------------------------------------------------------------------------------

statspec totalEntriesCustomer *server* PartitionedRegionStats *CUSTOMER* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=none
;
statspec totalBytesCustomer *server* PartitionedRegionStats *CUSTOMER* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=none
;
expr bytesPerCustomer = totalBytesCustomer / totalEntriesCustomer ops=max?
;

//statspec totalEntriesDistrict *server* PartitionedRegionStats *DISTRICT* dataStoreEntryCount
//filter=none combine=combineAcrossArchives ops=max? trimspec=none
//;
//statspec totalBytesDistrict *server* PartitionedRegionStats *DISTRICT* dataStoreBytesInUse
//filter=none combine=combineAcrossArchives ops=max? trimspec=none
//;
//expr bytesPerDistrict = totalBytesDistrict / totalEntriesDistrict ops=max?
//;

//statspec totalEntriesItem *server* PartitionedRegionStats *ITEM* dataStoreEntryCount
//filter=none combine=combineAcrossArchives ops=max? trimspec=none
//;
//statspec totalBytesItem *server* PartitionedRegionStats *ITEM* dataStoreBytesInUse
//filter=none combine=combineAcrossArchives ops=max? trimspec=none
//;
//expr bytesPerItem = totalBytesItem / totalEntriesItem ops=max?
//;

statspec totalEntriesOrder *server* PartitionedRegionStats *ORDER* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=none
;
statspec totalBytesOrder *server* PartitionedRegionStats *ORDER* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=none
;
expr bytesPerOrder = totalBytesOrder / totalEntriesOrder ops=max?
;

statspec totalEntriesStock *server* PartitionedRegionStats *STOCK* dataStoreEntryCount
filter=none combine=combineAcrossArchives ops=max? trimspec=none
;
statspec totalBytesStock *server* PartitionedRegionStats *STOCK* dataStoreBytesInUse
filter=none combine=combineAcrossArchives ops=max? trimspec=none
;
expr bytesPerStock = totalBytesStock / totalEntriesStock ops=max?
;

//statspec totalEntriesWarehouse *server* PartitionedRegionStats *WAREHOUSE* dataStoreEntryCount
//filter=none combine=combineAcrossArchives ops=max? trimspec=none
//;
//statspec totalBytesWarehouse *server* PartitionedRegionStats *WAREHOUSE* dataStoreBytesInUse
//filter=none combine=combineAcrossArchives ops=max? trimspec=none
//;
//expr bytesPerWarehouse = totalBytesWarehouse / totalEntriesWarehouse ops=max?
//;
