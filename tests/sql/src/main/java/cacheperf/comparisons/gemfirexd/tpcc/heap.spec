//------------------------------------------------------------------------------
// heap
//------------------------------------------------------------------------------

statspec clients *client* TPCCStats * vmCount
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions
;
statspec heapClient *client* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions
;
statspec heapClientPeak *client* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions
;
statspec totalHeapClient *client* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions
;
statspec maxHeapClient *client* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions
;
expr vmHeapClient = heapClient / clients ops = mean?
;
expr vmHeapClientPct = heapClient / totalHeapClient ops = mean?
;
expr vmHeapClientPeakPct = heapClientPeak / maxHeapClient ops = max?
;

statspec servers *server* TPCCStats * vmCount
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions
;
statspec heapServer *server* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions
;
statspec heapServerPeak *server* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions
;
statspec totalHeapServer *server* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions
;
statspec maxHeapServer *server* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions
;
expr vmHeapServer = heapServer / servers ops = mean?
;
expr vmHeapServerPct = heapServer / totalHeapServer ops = mean?
;
expr vmHeapServerPeakPct = heapServerPeak / maxHeapServer ops = max?
;
