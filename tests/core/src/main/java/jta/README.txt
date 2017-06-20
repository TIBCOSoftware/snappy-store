All the jta tests rely on an init task which creates the cache using $JTESTS/jta/cachejta.xml.  This creates a cache with JNDI bindings (for an outside transaction service).

Note that there is only one host in each test, with 1 VM.  The number of threads varies per test.

Each section below describes a grouping of tests in this suite.
------------------------------------------------------------------

This group of tests ignores the root region created at init time (but takes advantage of having the JNDI (tx) context defined for the cache).  JtaPrms.numRandomRootRegions are created (root1, root2 ... rootn) along with jtaRegion as defined by RegionPrms in the conf files.  

These tests create regions and then execute get, update and put (create) in those regions withn the context of a Tx (defined by the JNDIContext).

The test always commits, rolling back if an Exception is caught during commit.
There is no validation, BB counters (number of put, get and update operations on the cache) are printed.

Note:  RR stands for randomRegion, MR stands for mirrored region

jtaSingleRR.conf - uses root or jtaRegion, (local, ack or noAck) 
jtaSingleMR.conf - uses the single, replicate jtaRegion
jtaMultipleRR.conf - uses all regions, (local, ack or noaAck) 
jtaMultipleMR.conf - uses all replicate regions (all except 'root')
------------------------------------------------------------------
jtaTimeOut.conf -  This test doesn't actually perform any operations.  It creates the cache via the cachejta.xml and then performs empty (no operations are executed) transactions.  The transaction is started with a transactionTimeout (via begin).  The test then sleeps for a random time and tests to ensure that an Exception is thrown if the transaction timeout is exceeded.
------------------------------------------------------------------
jtaCacheAndDataSource.conf - jtaRegion with local scope.  Put operations are executed as creates in the cache, updates are made to the database.  Both are done within context of JNDIContext transaction.  Operations are counted, table is displayed (no validation).  The test always commits.  If an Exception is caught on commit and the utx is not null, we call rollback.  There is no connection between the operations being done in GemFire and the DataBase: the cache put is a create with a new key.  The updates are to existing entries in the database.

jtaDataSource.conf - jtaRegion with local scope.  Put (no-op), get and update operations are executed on an external database within the context of JNDIContext transactions.  The test always commits ... if an Exception is caught on commit and the utx is not null, we call rollback.  Counters (number of puts, gets, updates displayed).  The closetask drops the database table.  There is no validation.
------------------------------------------------------------------
[serial/conc]JtaCacheCallback.conf - This test uses a CacheLoader and CacheWriter to synchronize cache and external database activities.  There is only one update operation per transaction, validation is done per iteration to verify dataConsistency between cache and database.  A final validation is also performed as a closeTask.
Note:  Ops are executed on the cache and the CacheWriter actually does the update to the database.

application executes:
utx.begin();
   do ops
     ** DBWriter then executes update to db based on EntryEvent values
utx.commit()

* This test is now serialJtaCacheCallback.conf as we also added a concurrentExecution version, concJtaCacheCacheCallback.conf
** Note that we cannot use a callback with multiple VMs (if there is a PR) as the writer could be invoked in a remote VM (where the data is hosted). This doesn't work because the begin and commit need to be done by the same thread as the database update itself.  It follows that this will also NOT work in client/server configurations (as the Writers are invoked in the bridge server).

[serial/conc]JtaCacheCallbackWithTxWriter.conf
Same as the above test, but with an attempt to execute the commit in the TxWriter. 
(This was a high priority use case/requirements, but like the
DBWriter, this doesn't work when invoked in a remote VM in a thread other than
the thread initiating the UserTransaction.  Now this jtaTxWriter is used to
test that throwning an Exception (randomly) from the TxWriter will
cause the entire transaction to fail.  (So we don't get an inconsistency
between the database and the cache).

application executes:
utx.begin();
   do ops
     ** DBWriter then executes update to db based on EntryEvent values
     ** TxWriter can potentially thrown TransactionWriterException to cause the utx to fail
utx.commit()
------------------------------------------------------------------
gemfireCacheCallback.conf - Similar to jtaCacheCallback, except uses the GemFire CacheTransactionManager (CacheLoader/CacheWriter and TransactionListener) to coordinate between the cache and an oracle db.
------------------------------------------------------------------
gemfireCacheCallbackWithTxWriter.conf - Similar to gemfireCacheCallback.conf except a TransactionWriter is used (instead of the TransactionListener) to coordinate cache and database.
------------------------------------------------------------------

*** Multi VM JTA tests ***
With the introduction of PartitionedRegions, multi-vm versions of the serial/concJtaCacheCallback tests were added.
These tests use a derby network server (vs. an Embedded derby database) so that multiple VMs can access the same derby database.
