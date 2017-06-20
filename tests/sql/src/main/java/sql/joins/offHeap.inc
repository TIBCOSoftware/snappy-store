/* currently not running tests with offheap enabled. hence commented.  
// want off-heap memory defined and used in the test; either all tables will be off-heap or only some tables
hydra.GemFirePrms-offHeapMemorySize=350m;               // this sets off-heap memory for older tests (ignored in newer tests)
hydra.gemfirexd.FabricServerPrms-offHeapMemorySize=350m;  // this sets off-heap memory for newer tests (ignored in older tests)
sql.SQLPrms-isOffheap = true;                           // true means all tables are offheap (via offheap keyword on create table statement)
RANDOMINCLUDE $JTESTS/sql/joins/randomOffHeap.inc;      // if randomly chosen this overrides the above setting of SQLPrms-isOffheap 
                                                        // and sets SQLPrms-randomOffHeap for a mix of heap/off-heap tables

// verify off-heap memory at the end of the test
INITTASK taskClass = memscale.OffHeapMemoryLifecycleListener taskMethod = install runMode=always;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = waitForOffHeapSilence;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = verifyOffHeapMemoryConsistencyOnce;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = closeAllOffHeapRegions;
CLOSETASK taskClass = memscale.OffHeapHelper taskMethod = verifyOffHeapMemoryConsistencyOnce;
ENDTASK taskClass = memscale.OffHeapMemoryLifecycleListener taskMethod = checkForErrors;

*/
