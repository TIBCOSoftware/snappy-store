/* currently not running tests with offheap enabled. hence commented.
hydra.GemFirePrms-offHeapMemorySize=350m;               // this sets off-heap memory for older tests (ignored in newer tests)
hydra.gemfirexd.FabricServerPrms-offHeapMemorySize=350m;  // this sets off-heap memory for newer tests (ignored in older tests)
sql.SQLPrms-isOffheap = true;                           // true means all tables are offheap (via offheap keyword on create table statement)

// verify off-heap memory at the end of the test and when members are taken down
INITTASK taskClass = memscale.OffHeapMemoryLifecycleListener taskMethod = install runMode=always;
CLOSETASK taskClass = util.AEQHelper taskMethod = waitForAsyncEventQueuesToDrain;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = waitForOffHeapSilence;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = verifyOffHeapMemoryConsistencyOnce;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = closeAllOffHeapRegions;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = verifyOffHeapMemoryConsistencyOnce;
ENDTASK taskClass = memscale.OffHeapMemoryLifecycleListener taskMethod = checkForErrors;

*/
