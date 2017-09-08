statspec clients_b *client* TPCCStats * vmCount
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;
statspec servers_b *server* TPCCStats * vmCount
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;

//------------------------------------------------------------------------------
// cpu
//------------------------------------------------------------------------------

statspec cpuClient_b *client* SystemStats * cpuActive
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;
expr vmCPUClient_b = cpuClient_b / clients_b ops = mean?
;

statspec cpuServer_b *server* SystemStats * cpuActive
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;
expr vmCPUServer_b = cpuServer_b / servers_b ops = mean?
;

//------------------------------------------------------------------------------
// heap
//------------------------------------------------------------------------------

statspec heapClient_b *client* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;
statspec heapClientPeak_b *client* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_b
;
statspec totalHeapClient_b *client* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;
statspec maxHeapClient_b *client* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_b
;
expr vmHeapClient_b = heapClient_b / clients_b ops = mean?
;
expr vmHeapClientPct_b = heapClient_b / totalHeapClient_b ops = mean?
;
expr vmHeapClientPeakPct_b = heapClientPeak_b / maxHeapClient_b ops = max?
;

statspec heapServer_b *server* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;
statspec heapServerPeak_b *server* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_b
;
statspec totalHeapServer_b *server* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_b
;
statspec maxHeapServer_b *server* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_b
;
expr vmHeapServer_b = heapServer_b / servers_b ops = mean?
;
expr vmHeapServerPct_b = heapServer_b / totalHeapServer_b ops = mean?
;
expr vmHeapServerPeakPct_b = heapServerPeak_b / maxHeapServer_b ops = max?
;
