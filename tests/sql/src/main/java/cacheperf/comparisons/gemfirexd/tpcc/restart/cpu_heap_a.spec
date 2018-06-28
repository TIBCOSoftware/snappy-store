statspec clients_a *client* TPCCStats * vmCount
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;
statspec servers_a *server* TPCCStats * vmCount
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;

//------------------------------------------------------------------------------
// cpu
//------------------------------------------------------------------------------

statspec cpuClient_a *client* SystemStats * cpuActive
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;
expr vmCPUClient_a = cpuClient_a / clients_a ops = mean?
;

statspec cpuServer_a *server* SystemStats * cpuActive
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;
expr vmCPUServer_a = cpuServer_a / servers_a ops = mean?
;

//------------------------------------------------------------------------------
// heap
//------------------------------------------------------------------------------

statspec heapClient_a *client* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;
statspec heapClientPeak_a *client* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_a
;
statspec totalHeapClient_a *client* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;
statspec maxHeapClient_a *client* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_a
;
expr vmHeapClient_a = heapClient_a / clients_a ops = mean?
;
expr vmHeapClientPct_a = heapClient_a / totalHeapClient_a ops = mean?
;
expr vmHeapClientPeakPct_a = heapClientPeak_a / maxHeapClient_a ops = max?
;

statspec heapServer_a *server* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;
statspec heapServerPeak_a *server* VMMemoryUsageStats vmHeapMemoryStats usedMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_a
;
statspec totalHeapServer_a *server* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=mean! trimspec=transactions_a
;
statspec maxHeapServer_a *server* VMMemoryUsageStats vmHeapMemoryStats maxMemory
filter=none combine=combineAcrossArchives ops=max! trimspec=transactions_a
;
expr vmHeapServer_a = heapServer_a / servers_a ops = mean?
;
expr vmHeapServerPct_a = heapServer_a / totalHeapServer_a ops = mean?
;
expr vmHeapServerPeakPct_a = heapServerPeak_a / maxHeapServer_a ops = max?
;
