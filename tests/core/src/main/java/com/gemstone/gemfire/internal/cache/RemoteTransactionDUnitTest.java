/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package com.gemstone.gemfire.internal.cache;

import hydra.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.UserTransaction;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.AttributesMutator;
import com.gemstone.gemfire.cache.CacheEvent;
import com.gemstone.gemfire.cache.CacheListener;
import com.gemstone.gemfire.cache.CacheLoader;
import com.gemstone.gemfire.cache.CacheLoaderException;
import com.gemstone.gemfire.cache.CacheTransactionManager;
import com.gemstone.gemfire.cache.CacheWriter;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EntryEvent;
import com.gemstone.gemfire.cache.EntryNotFoundException;
import com.gemstone.gemfire.cache.IllegalTransactionStateException;
import com.gemstone.gemfire.cache.ExpirationAction;
import com.gemstone.gemfire.cache.ExpirationAttributes;
import com.gemstone.gemfire.cache.InterestPolicy;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.LoaderHelper;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionEvent;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.RegionShortcut;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.SubscriptionAttributes;
import com.gemstone.gemfire.cache.TransactionDataRebalancedException;
import com.gemstone.gemfire.cache.TransactionEvent;
import com.gemstone.gemfire.cache.TransactionException;
import com.gemstone.gemfire.cache.TransactionId;
import com.gemstone.gemfire.cache.TransactionListener;
import com.gemstone.gemfire.cache.TransactionWriter;
import com.gemstone.gemfire.cache.TransactionWriterException;
import com.gemstone.gemfire.cache.UnsupportedOperationInTransactionException;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ClientRegionFactory;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionAdapter;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.execute.RegionFunctionContext;
import com.gemstone.gemfire.cache.partition.PartitionRegionHelper;
import com.gemstone.gemfire.cache.query.CqAttributes;
import com.gemstone.gemfire.cache.query.CqAttributesFactory;
import com.gemstone.gemfire.cache.query.CqEvent;
import com.gemstone.gemfire.cache.query.CqListener;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.cache.util.CacheListenerAdapter;
import com.gemstone.gemfire.cache.util.CacheWriterAdapter;
import com.gemstone.gemfire.cache30.CacheTestCase;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.execute.CustomerIDPartitionResolver;
import com.gemstone.gemfire.internal.cache.execute.InternalFunctionService;
import com.gemstone.gemfire.internal.cache.execute.data.CustId;
import com.gemstone.gemfire.internal.cache.execute.data.Customer;
import com.gemstone.gemfire.internal.cache.execute.data.Order;
import com.gemstone.gemfire.internal.cache.execute.data.OrderId;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;

import dunit.DistributedTestCase;
import dunit.Host;
import dunit.SerializableCallable;
import dunit.VM;

/**
 * @author sbawaska
 *
 */
@SuppressWarnings("serial")
public class RemoteTransactionDUnitTest extends CacheTestCase {

  final protected String CUSTOMER = "custRegion";
  final protected String ORDER = "orderRegion";
  final protected String D_REFERENCE = "distrReference";

  private final boolean isRepeatableRead = false;

  private final SerializableCallable getNumberOfTXInProgress = new SerializableCallable() {
    public Object call() throws Exception {
      TXManagerImpl mgr = getGemfireCache().getTxManager();
      return mgr.hostedTransactionsInProgressForTest();
    }
  };

  private final SerializableCallable verifyNoTxState = new SerializableCallable() {
    public Object call() throws Exception {
      TXManagerImpl mgr = getGemfireCache().getTxManager();
      assertEquals(
          "unexpected TXns in progress: "
              + mgr.getHostedTransactionsInProgress(), 0,
          mgr.hostedTransactionsInProgressForTest());
      return null;
    }
  };

  private final SerializableCallable waitForPendingCommit = new SerializableCallable() {

    public Object call() throws Exception {
      TXManagerImpl.waitForPendingCommitForTest();
      return null;
    }
  };

  /**
   * @param name
   */
  public RemoteTransactionDUnitTest(String name) {
    super(name);
  }

  protected enum OP {
    PUT, GET, DESTROY, INVALIDATE, KEYS, VALUES, ENTRIES, PUTALL, GETALL
  }
  
  @Override
  public void tearDown2() throws Exception {
//    try { Thread.sleep(5000); } catch (InterruptedException e) { } // FOR MANUAL TESTING OF STATS - DON"T KEEP THIS
    invokeInEveryVM(verifyNoTxState);
    closeAllCache();
    super.tearDown2();
  }
  
  void createRegion(boolean accessor, int redundantCopies, InterestPolicy interestPolicy) {
    AttributesFactory af = new AttributesFactory();
    af.setScope(Scope.DISTRIBUTED_ACK);
    af.setDataPolicy(DataPolicy.REPLICATE);
    af.setConcurrencyChecksEnabled(getConcurrencyChecksEnabled());
    getCache().createRegion(D_REFERENCE,af.create());
    af = new AttributesFactory();
    af.setConcurrencyChecksEnabled(getConcurrencyChecksEnabled());
    if (interestPolicy != null) {
      af.setSubscriptionAttributes(new SubscriptionAttributes(interestPolicy));
    }
    af.setPartitionAttributes(new PartitionAttributesFactory<CustId, Customer>()
        .setTotalNumBuckets(4).setLocalMaxMemory(accessor ? 0 : 1)
        .setPartitionResolver(new CustomerIDPartitionResolver("resolver1"))
        .setRedundantCopies(redundantCopies).create());
    getCache().createRegion(CUSTOMER, af.create());
    af.setPartitionAttributes(new PartitionAttributesFactory<OrderId, Order>()
        .setTotalNumBuckets(4).setLocalMaxMemory(accessor ? 0 : 1)
        .setPartitionResolver(new CustomerIDPartitionResolver("resolver2"))
        .setRedundantCopies(redundantCopies).setColocatedWith(CUSTOMER).create());
    getCache().createRegion(ORDER, af.create());
  }

  protected boolean getConcurrencyChecksEnabled() {
    return false;
  }

  void populateData() {
    Region custRegion = getCache().getRegion(CUSTOMER);
    Region orderRegion = getCache().getRegion(ORDER);
    Region refRegion = getCache().getRegion(D_REFERENCE);
    for (int i=0; i<5; i++) {
      CustId custId = new CustId(i);
      Customer customer = new Customer("customer"+i, "address"+i);
      OrderId orderId = new OrderId(i, custId);
      Order order = new Order("order"+i);
      custRegion.put(custId, customer);
      orderRegion.put(orderId, order);
      refRegion.put(custId,customer);
    }
  }

  protected void initAccessorAndDataStore(VM accessor, VM datastore, final int redundantCopies) {
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        createRegion(true/*accessor*/, redundantCopies, null);
        return null;
      }
    });

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        createRegion(false/*accessor*/, redundantCopies, null);
        populateData();
        return null;
      }
    });
  }

  private void initAccessorAndDataStore(VM accessor, VM datastore1,
      VM datastore2, final int redundantCopies) {
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        createRegion(false/*accessor*/, redundantCopies, null);
        return null;
      }
    });

    initAccessorAndDataStore(accessor, datastore1, redundantCopies);
  }

  private void initAccessorAndDataStoreWithInterestPolicy(VM accessor, VM datastore1, VM datastore2, final int redundantCopies) {
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        createRegion(false/*accessor*/, redundantCopies, InterestPolicy.ALL);
        return null;
      }
    });

    initAccessorAndDataStore(accessor, datastore1, redundantCopies);
  }

  protected class DoOpsInTX extends SerializableCallable {
    private final OP op;
    Customer expectedCust;
    Customer expectedRefCust = null;
    Order expectedOrder;
    Order expectedOrder2;
    Order expectedOrder3;
    DoOpsInTX(OP op) {
      this.op = op;
    }
    public Object call() throws Exception {
      CacheTransactionManager mgr = getGemfireCache().getTxManager();
      getLogWriter().fine("testTXPut starting tx");
      mgr.begin();
      Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
      Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
      Region<CustId,Customer> refRegion = getCache().getRegion(D_REFERENCE);
      CustId custId = new CustId(1);
      OrderId orderId = new OrderId(1, custId);
      OrderId orderId2 = new OrderId(2, custId);
      OrderId orderId3 = new OrderId(3, custId);
      switch (this.op) {
      case PUT:
        expectedCust = new Customer("foo", "bar");
        expectedOrder = new Order("fooOrder");
        expectedOrder2 = new Order("fooOrder2");
        expectedOrder3 = new Order("fooOrder3");
        custRegion.put(custId, expectedCust);
        orderRegion.put(orderId, expectedOrder);
        Map orders = new HashMap();
        orders.put(orderId2, expectedOrder2);
        orders.put(orderId3, expectedOrder3);
        getGemfireCache().getLoggerI18n().fine("SWAP:doingPutAll");
        //orderRegion.putAll(orders);
        refRegion.put(custId,expectedCust);
        Set<OrderId> ordersSet = new HashSet<OrderId>();
        ordersSet.add(orderId);ordersSet.add(orderId2);ordersSet.add(orderId3);
        //validateContains(custId, ordersSet, true);
        break;
      case GET:
        expectedCust = custRegion.get(custId);
        expectedOrder = orderRegion.get(orderId);
        expectedRefCust = refRegion.get(custId);
        assertNotNull(expectedCust);
        assertNotNull(expectedOrder);
        assertNotNull(expectedRefCust);
        validateContains(custId, Collections.singleton(orderId), true,true);
        break;
      case DESTROY:
        validateContains(custId, Collections.singleton(orderId), true);
        custRegion.destroy(custId);
        orderRegion.destroy(orderId);
        refRegion.destroy(custId);
        validateContains(custId, Collections.singleton(orderId), false);
        break;
      case INVALIDATE:
        validateContains(custId, Collections.singleton(orderId), true);
        custRegion.invalidate(custId);
        orderRegion.invalidate(orderId);
        refRegion.invalidate(custId);
        validateContains(custId,Collections.singleton(orderId),true,false);
        break;
      default:
        throw new IllegalStateException();
      }
      return mgr.getTransactionId();
    }
  };

  void validateContains(CustId custId, Set<OrderId> orderId, boolean doesIt)  {
    validateContains(custId,orderId,doesIt,doesIt);
  }
  
  void validateContains(CustId custId, Set<OrderId> ordersSet, boolean containsKey,boolean containsValue) {
    Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
    Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
    Region<CustId, Order> refRegion = getCache().getRegion(D_REFERENCE);
    boolean rContainsKC = custRegion.containsKey(custId);
    boolean rContainsKO = containsKey;
    for (OrderId o : ordersSet) {
      getGemfireCache().getLoggerI18n().fine("SWAP:rContainsKO:"+rContainsKO+" containsKey:"+orderRegion.containsKey(o));
      rContainsKO = rContainsKO && orderRegion.containsKey(o);
    }
    boolean rContainsKR = refRegion.containsKey(custId);
    
    boolean rContainsVC = custRegion.containsValueForKey(custId);
    boolean rContainsVO = containsValue;
    for (OrderId o: ordersSet) {
      rContainsVO = rContainsVO && orderRegion.containsValueForKey(o);
    }
    boolean rContainsVR = refRegion.containsValueForKey(custId);
    
    
    assertEquals(containsKey,rContainsKC);
    assertEquals(containsKey,rContainsKO);
    assertEquals(containsKey,rContainsKR);
    assertEquals(containsValue,rContainsVR);
    assertEquals(containsValue,rContainsVC);
    assertEquals(containsValue,rContainsVO);
    
    
    if(containsKey) {
      Region.Entry eC =  custRegion.getEntry(custId);
      for (OrderId o : ordersSet) {
        assertNotNull(orderRegion.getEntry(o));
      }
      Region.Entry eR = refRegion.getEntry(custId);
      assertNotNull(eC);
      assertNotNull(eR);
//      assertEquals(1,custRegion.size());
  //    assertEquals(1,orderRegion.size());
    //  assertEquals(1,refRegion.size());
      
    } else {
      //assertEquals(0,custRegion.size());
      //assertEquals(0,orderRegion.size());
      //assertEquals(0,refRegion.size());
      try {
        Region.Entry eC =  custRegion.getEntry(custId);
        assertNull("should have had an EntryNotFoundException:"+eC,eC);
        
      } catch(EntryNotFoundException enfe) {
        // this is what we expect
      }
      try {
        for (OrderId o : ordersSet) {
          assertNull("should have had an EntryNotFoundException:"+orderRegion.getEntry(o),orderRegion.getEntry(o));
        }
        
      } catch(EntryNotFoundException enfe) {
        // this is what we expect
      }
      try {
        Region.Entry eR =  refRegion.getEntry(custId);
        assertNull("should have had an EntryNotFoundException:"+eR,eR);
      } catch(EntryNotFoundException enfe) {
        // this is what we expect
      }
      
    }
    
  }


  void verifyAfterCommit(OP op) {
    Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
    Region<CustId, Customer> refRegion = getCache().getRegion(D_REFERENCE);
    Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
    CustId custId = new CustId(1);
    OrderId orderId = new OrderId(1, custId);
    Customer expectedCust;
    Customer expectedRef;
    switch (op) {
    case PUT:
      expectedCust = new Customer("foo", "bar");
      expectedRef = expectedCust;
      assertNotNull(custRegion.getEntry(custId));
      assertEquals(expectedCust, custRegion.getEntry(custId).getValue());
      /*
      assertNotNull(orderRegion.getEntry(orderId));
      assertEquals(expectedOrder, orderRegion.getEntry(orderId).getValue());
      
      assertNotNull(orderRegion.getEntry(orderId2));
      assertEquals(expectedOrder2, orderRegion.getEntry(orderId2).getValue());
      
      assertNotNull(orderRegion.getEntry(orderId3));
      assertEquals(expectedOrder3, orderRegion.getEntry(orderId3).getValue());
      */
      assertNotNull(refRegion.getEntry(custId));
      assertEquals(expectedRef, refRegion.getEntry(custId).getValue());
      
      //Set<OrderId> ordersSet = new HashSet<OrderId>();
      //ordersSet.add(orderId);ordersSet.add(orderId2);ordersSet.add(orderId3);
      //validateContains(custId, ordersSet, true);
      break;
    case GET:
      expectedCust = custRegion.get(custId);
      orderRegion.get(orderId);
      expectedRef = refRegion.get(custId);
      validateContains(custId, Collections.singleton(orderId), true);
      break;
    case DESTROY:
      assertTrue(!custRegion.containsKey(custId));
      assertTrue(!orderRegion.containsKey(orderId));
      assertTrue(!refRegion.containsKey(custId));
      validateContains(custId, Collections.singleton(orderId), false);
      break;
    case INVALIDATE:
      boolean validateContainsKey = true;
      if (!((GemFireCacheImpl)custRegion.getCache()).isClient()) {
        assertTrue(custRegion.containsKey(custId));
        assertTrue(orderRegion.containsKey(orderId));
        assertTrue(refRegion.containsKey(custId));
      }
      assertNull(custRegion.get(custId));
      assertNull(orderRegion.get(orderId));
      assertNull(refRegion.get(custId));
      validateContains(custId,Collections.singleton(orderId),validateContainsKey,false);
      break;
    default:
      throw new IllegalStateException();
    }
  }
  
  
  void verifyAfterRollback(OP op) {
    Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
    Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
    Region<CustId, Customer> refRegion = getCache().getRegion(D_REFERENCE);
    assertNotNull(custRegion);
    assertNotNull(orderRegion);
    assertNotNull(refRegion);
    
    CustId custId = new CustId(1);
    OrderId orderId = new OrderId(1, custId);
    OrderId orderId2 = new OrderId(2, custId);
    OrderId orderId3 = new OrderId(3, custId);
    Customer expectedCust;
    Order expectedOrder;
    Customer expectedRef;
    switch (op) {
    case PUT:
      expectedCust = new Customer("customer1", "address1");
      expectedOrder = new Order("order1");
      expectedRef = new Customer("customer1", "address1");
      assertEquals(expectedCust, custRegion.getEntry(custId).getValue());
      assertEquals(expectedOrder, orderRegion.getEntry(orderId).getValue());
      getCache().getLogger().info("SWAP:verifyRollback:"+orderRegion);
      getCache().getLogger().info("SWAP:verifyRollback:"+orderRegion.getEntry(orderId2));
      assertNull(getGemfireCache().getTxManager().getTXState());
      assertNull(""+orderRegion.getEntry(orderId2),orderRegion.getEntry(orderId2));
      assertNull(orderRegion.getEntry(orderId3));
      assertNull(orderRegion.get(orderId2));
      assertNull(orderRegion.get(orderId3));
      assertEquals(expectedRef, refRegion.getEntry(custId).getValue());
      validateContains(custId, Collections.singleton(orderId), true);
      break;
    case GET:
      expectedCust = custRegion.getEntry(custId).getValue();
      expectedOrder = orderRegion.getEntry(orderId).getValue();
      expectedRef = refRegion.getEntry(custId).getValue();
      validateContains(custId, Collections.singleton(orderId), true);
      break;
    case DESTROY:
      assertTrue(!custRegion.containsKey(custId));
      assertTrue(!orderRegion.containsKey(orderId));
      assertTrue(!refRegion.containsKey(custId));
      validateContains(custId, Collections.singleton(orderId), true);
      break;
    case INVALIDATE:
      assertTrue(custRegion.containsKey(custId));
      assertTrue(orderRegion.containsKey(orderId));
      assertTrue(refRegion.containsKey(custId));
      assertNull(custRegion.get(custId));
      assertNull(orderRegion.get(orderId));
      assertNull(refRegion.get(custId));
      validateContains(custId,Collections.singleton(orderId),true,true);
      break;
    default:
      throw new IllegalStateException();
    }
  }
  public void testDummy(){
    
  }
  
  public void Bug44146_testTXCreationAndCleanupAtCommit() throws Exception {
    doBasicChecks(true);
  }

  public void Bug44146_testTXCreationAndCleanupAtRollback() throws Exception {
    doBasicChecks(false);
  }

  private void doBasicChecks(final boolean commit) throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);

    final TXId txId = (TXId)accessor.invoke(new DoOpsInTX(OP.PUT));

    datastore.invoke(new SerializableCallable("verify tx") {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertTrue(mgr.isHostedTxInProgress(txId));
        return null;
      }
    });
    
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.internalSuspend();
        assertNotNull(tx);
        mgr.resume(tx);
        if (commit) {
          mgr.commit();
          TXManagerImpl.waitForPendingCommitForTest();
        } else {
          mgr.rollback();
        }
        return null;
      }
    });
    
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertFalse(mgr.isHostedTxInProgress(txId));
        return null;
      }
    });
    if (commit) {
      accessor.invoke(new SerializableCallable() {
        public Object call() throws Exception {
          verifyAfterCommit(OP.PUT);
          return null;
        }
      });
    } else {
      accessor.invoke(new SerializableCallable() {
        public Object call() throws Exception {
          verifyAfterRollback(OP.PUT);
          return null;
        }
      });
    }
  }

  public void Bug44146_testPRTXGet() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);

    final TXId txId = (TXId)accessor.invoke(new DoOpsInTX(OP.GET));

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.getTXState();
        if (isRepeatableRead) {
          assertEquals("unexpected regions: " + tx.getRegions(),
              2, tx.getRegions().size());// 2 PRs only on accessor
        }
        else {
          assertEquals("unexpected regions: " + tx.getRegions(),
              0, tx.getRegions().size());// 0 PRs
        }
        /* (nothing in TXState for READ_COMMITTED)
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof BucketRegion || r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = rs.readEntry(key);
            assertNotNull(es.getValue(r));
            assertFalse(es.isDirty());
          }
        }
        */
        return null;
      }
    });

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertFalse(mgr.isHostedTxInProgress(txId));
        TXStateInterface tx = mgr.getHostedTXState(txId);
        // no TXRegionStates for reads on store at READ_COMMITTED
        assertNull(tx);
        return null;
      }
    });

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.commit();
        verifyAfterCommit(OP.GET);
        return null;
      }
    });
  }

  public void Bug44146_testPRTXGetOnRemoteWithLoader() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);


    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        AttributesMutator am = getCache().getRegion(CUSTOMER).getAttributesMutator();
        am.setCacheLoader(new CacheLoader() {
          public Object load(LoaderHelper helper)
          throws CacheLoaderException {
            return new Customer("sup dawg", "add");
          }
          
          public void close() { }
        });
        return null;
      }
    });

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        Region cust = getCache().getRegion(CUSTOMER);
        Customer s = (Customer)cust.get(new CustId(8));
        assertTrue(cust.containsKey(new CustId(8)));
        TXStateInterface tx = mgr.internalSuspend();
        assertFalse(cust.containsKey(new CustId(8)));
        mgr.resume(tx);
        mgr.commit();
        Customer s2 = (Customer)cust.get(new CustId(8));
        Customer ex = new Customer("sup dawg", "add");
        assertEquals(ex,s);
        assertEquals(ex,s2);
        return null;
      }
    });
  }
  
  /**
   * Make sure that getEntry returns null properly and values when it should
   */
  public void Bug44146_testPRTXGetEntryOnRemoteSide() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);


    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        return null;
      }
    });

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(CUSTOMER);
        CustId sup = new CustId(7);
        Region.Entry e = cust.getEntry(sup);
        assertNull(e);
        CustId custId = new CustId(5);
        cust.put(custId, new Customer("customer5", "address5"));
        
        Region.Entry ee = cust.getEntry(custId);
        assertNotNull(ee);
        
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.begin();
        Region.Entry e2 = cust.getEntry(sup);
        assertNull(e2);
        mgr.commit();
        Region.Entry e3 = cust.getEntry(sup);
        assertNull(e3);
        
        
        mgr.begin();
        Customer dawg = new Customer("dawg", "dawgaddr");
        cust.put(sup, dawg);
        Region.Entry e4 = cust.getEntry(sup);
        assertNotNull(e4);
        assertEquals(dawg,e4.getValue());
        mgr.commit();
        
        Region.Entry e5 = cust.getEntry(sup);
        assertNotNull(e5);
        assertEquals(dawg,e5.getValue());
        return null;
      }
    });
  }

  public void Bug44146_testPRTXGetOnLocalWithLoader() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);


    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        AttributesMutator am = getCache().getRegion(CUSTOMER).getAttributesMutator();
        am.setCacheLoader(new CacheLoader() {
          public Object load(LoaderHelper helper)
          throws CacheLoaderException {
            return new Customer("sup dawg", "addr");
          }
          
          public void close() { }
        });
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.begin();
        Region cust = getCache().getRegion(CUSTOMER);
        CustId custId = new CustId(6);
        Customer s = (Customer)cust.get(custId);
        mgr.commit();
        Customer s2 = (Customer)cust.get(custId);
        Customer expectedCust = new Customer("sup dawg", "addr");
        assertEquals(s, expectedCust);
        assertEquals(s2, expectedCust);
        return null;
      }
    });

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        return null;
      }
    });
  }

  public void Bug44146_testTXPut() {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);

    final TXId txId = (TXId)accessor.invoke(new DoOpsInTX(OP.PUT));

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertTrue(mgr.isHostedTxInProgress(txId));
        TXStateInterface tx = mgr.getHostedTXState(txId);
        final int n = tx.getProxy().batchingEnabled() ? 2 : 3;
        assertEquals("unexpected regions: " + tx.getRegions(),
            n, tx.getRegions().size());// 2 buckets for the two puts we
                                       // did in the accessor
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof BucketRegion
              || r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = (TXEntryState)rs.readEntry(key);
            assertNotNull(es.getValue(r));
            assertTrue(es.isDirty());
          }
        }
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        final TXManagerImpl mgr = getGemfireCache().getTxManager();
        final TXStateInterface tx = mgr.getTXState();
        assertNotNull(tx);
        getCache().getLogger().fine("SWAP:accessorTXState:" + tx);
        assertEquals("unexpected regions: " + tx.getRegions(),
            3, tx.getRegions().size()); // 2 buckets for the two puts we did in
                                        // the accessor +1 for the dist_region
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof PartitionedRegion
              || r instanceof DistributedRegion);
        }
        mgr.commit();
        verifyAfterCommit(OP.PUT);
        assertNull(mgr.getTXState());
        return null;
      }
    });
  }

  public void Bug44146_testTXInvalidate() {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);

    final TXId txId = (TXId)accessor.invoke(new DoOpsInTX(OP.INVALIDATE));

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertTrue(mgr.isHostedTxInProgress(txId));
        TXStateInterface tx = mgr.getHostedTXState(txId);
        final int n = tx.getProxy().batchingEnabled() ? 2 : 3;
        assertEquals("unexpected regions: " + tx.getRegions(),
            n, tx.getRegions().size());// 2 buckets for the two puts we
                                       // did in the accessor
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof BucketRegion
              || r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = (TXEntryState)rs.readEntry(key);
            Log.getLogWriter().warning(
                "SUP IS IT NULL(" + key + "):" + es.getValue(r));
            assertNotNull(es.getValue(r));
            assertTrue(es.isDirty());
          }
        }
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        final TXManagerImpl mgr = getGemfireCache().getTxManager();
        final TXStateInterface tx = mgr.getTXState();
        assertNotNull(tx);
        assertEquals("unexpected regions: " + tx.getRegions(),
            3, tx.getRegions().size()); // 2 buckets for the two puts we did in
                                        // the accessor plus the dist. region
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof PartitionedRegion
              || r instanceof DistributedRegion);
        }
        mgr.commit();
        verifyAfterCommit(OP.INVALIDATE);
        return null;
      }
    });
  }

  
  public void Bug44146_testTXDestroy() {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);

    
    final TXId txId = (TXId)accessor.invoke(new DoOpsInTX(OP.DESTROY));

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertTrue(mgr.isHostedTxInProgress(txId));
        TXStateInterface tx = mgr.getHostedTXState(txId);
        final int n = tx.getProxy().batchingEnabled() ? 2 : 3;
        assertEquals("unexpected regions: " + tx.getRegions(),
            n, tx.getRegions().size());// 2 buckets for the two puts we
                                       // did in the accessor
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof BucketRegion
              || r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = (TXEntryState)rs.readEntry(key);
            assertNull(es.getValue(r));
            assertTrue(es.isDirty());
          }
        }
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        final TXManagerImpl mgr = getGemfireCache().getTxManager();
        final TXStateInterface tx = mgr.getTXState();
        assertNotNull(tx);
        assertEquals("unexpected regions: " + tx.getRegions(),
            3, tx.getRegions().size()); // 2 buckets for the two puts we did in
                                        // the accessor plus the dist. region
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof PartitionedRegion
              || r instanceof DistributedRegion);
        }
        mgr.commit();
        verifyAfterCommit(OP.DESTROY);
        return null;
      }
    });
  }

  /**
   * Disabled till non-TX, TX conflict detection is complete in new TX model.
   */
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTxPutIfAbsent() {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);

    final CustId newCustId = new CustId(10);
    final Customer updateCust = new Customer("customer10", "address10");
    final TXId txId = (TXId)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        Region<CustId, Customer> cust = getCache().getRegion(CUSTOMER);
        Region<CustId, Customer> rr = getCache().getRegion(D_REFERENCE);
        Customer expectedCust = new Customer("customer"+1, "address"+1);
        getGemfireCache().getLoggerI18n().fine("SWAP:doingPutIfAbsent");
        CustId oldCustId = new CustId(1);
        Customer old = cust.putIfAbsent(oldCustId, updateCust);
        assertTrue("expected:"+expectedCust+" but was "+old, expectedCust.equals(old));
        //transaction should be bootstrapped
        old = rr.putIfAbsent(oldCustId, updateCust);
        assertTrue("expected:"+expectedCust+" but was "+old, expectedCust.equals(old));
        //now a key that does not exist
        old = cust.putIfAbsent(newCustId, updateCust);
        assertNull(old);
        old = rr.putIfAbsent(newCustId, updateCust);
        assertNull(old);
        Region<OrderId, Order> order = getCache().getRegion(ORDER);
        Order oldOrder = order.putIfAbsent(new OrderId(10, newCustId), new Order("order10"));
        assertNull(old);
        assertNull(oldOrder);
        assertNotNull(cust.get(newCustId));
        assertNotNull(rr.get(newCustId));
        TXStateInterface tx = mgr.internalSuspend();
        assertNull(cust.get(newCustId));
        assertNull(rr.get(newCustId));
        mgr.resume(tx);
        cust.put(oldCustId, new Customer("foo", "bar"));
        rr.put(oldCustId, new Customer("foo", "bar"));
        return mgr.getTransactionId();
      }
    });
    
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(CUSTOMER);
        int hash1 = PartitionedRegionHelper.getHashKey((PartitionedRegion)cust, new CustId(1));
        int hash10 = PartitionedRegionHelper.getHashKey((PartitionedRegion)cust, new CustId(10));
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertTrue(mgr.isHostedTxInProgress(txId));
        TXStateInterface tx = mgr.getHostedTXState(txId);
        // 2 buckets for the two puts we did in the accessor one dist. region,
        // and one more bucket if Cust1 and Cust10 resolve to different buckets
        assertEquals("unexpected regions: " + tx.getRegions(),
            3 + (hash1 == hash10 ? 0 : 1), tx.getRegions().size());
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof BucketRegion || r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = (TXEntryState)rs.readEntry(key);
            assertNotNull(es.getValue(r));
            assertTrue("key:"+key+" r:"+r.getFullPath(), es.isDirty());
          }
        }
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.commit();
        Region<CustId, Customer> cust = getCache().getRegion(CUSTOMER);
        Region<CustId, Customer> rr = getCache().getRegion(D_REFERENCE);
        assertEquals(updateCust, cust.get(newCustId));
        assertEquals(updateCust, rr.get(newCustId));
        //test conflict
        mgr.begin();
        CustId conflictCust = new CustId(11);
        cust.putIfAbsent(conflictCust, new Customer("name11", "address11"));
        TXStateInterface tx = mgr.internalSuspend();
        cust.put(conflictCust, new Customer("foo", "bar"));
        mgr.resume(tx);
        try {
          mgr.commit();
          fail("expected exception not thrown");
        } catch (ConflictException cce) {
        }
        return null;
      }
    });
    
  }
  
  public VM getVMForTransactions(VM accessor, VM datastore) {
    return accessor;
  }
  
  public void Bug44146_testTxRemove() {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);

    final CustId custId = new CustId(1);
    final TXId txId = (TXId)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> cust = getCache().getRegion(CUSTOMER);
        Region<CustId, Customer> ref = getCache().getRegion(D_REFERENCE);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        Customer customer = new Customer("customer1", "address1");
        Customer fakeCust = new Customer("foo", "bar");
        assertFalse(cust.remove(custId, fakeCust));
        assertTrue(cust.remove(custId, customer));
        assertFalse(ref.remove(custId, fakeCust));
        assertTrue(ref.remove(custId, customer));
        TXStateInterface tx = mgr.internalSuspend();
        assertNotNull(cust.get(custId));
        assertNotNull(ref.get(custId));
        mgr.resume(tx);
        return mgr.getTransactionId();
      }
    });
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertTrue(mgr.isHostedTxInProgress(txId));
        TXStateInterface tx = mgr.getHostedTXState(txId);
        final int n = tx.getProxy().batchingEnabled() ? 1 : 2;
        assertEquals("unexpected regions: " + tx.getRegions(),
            n, tx.getRegions().size());// 1 bucket for the put we
                                       // did in the accessor
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof BucketRegion
              || r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = (TXEntryState)rs.readEntry(key);
            assertNull(es.getValue(r));
            assertTrue("key:" + key + " r:" + r.getFullPath(), es.isDirty());
          }
        }
        return null;
      }
    });
    final CustId conflictCust = new CustId(2);
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        final TXManagerImpl mgr = getGemfireCache().getTxManager();
        final TXStateInterface tx = mgr.getTXState();
        assertNotNull(tx);
        assertEquals("unexpected regions: " + tx.getRegions(),
            2, tx.getRegions().size()); // 1 bucket for the two put we did in
                                        // the accessor, one dist. region
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof PartitionedRegion
              || r instanceof DistributedRegion);
        }
        mgr.commit();
        Region<CustId, Customer> cust = getCache().getRegion(CUSTOMER);
        Region<CustId, Customer> rr = getCache().getRegion(D_REFERENCE);
        assertNull(cust.get(custId));
        assertNull(rr.get(custId));
        // check conflict
        mgr.begin();
        Customer customer = new Customer("customer2", "address2");
        getGemfireCache().getLoggerI18n().fine("SWAP:removeConflict");
        assertTrue(cust.remove(conflictCust, customer));
        return null;
      }
    });
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        Region<CustId, Customer> cust = getCache().getRegion(CUSTOMER);
        mgr.begin();
        // TODO: TX: also check for non-TX put once conflict detection for
        // non-TX + TX mix is done
        // create conflict
        try {
          cust.put(conflictCust, new Customer("foo", "bar"));
          fail("expected exception not thrown");
        } catch (ConflictException ce) {
          // expected
        }
        mgr.commit();
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.commit();
        return null;
      }
    });
  }

  /**
   * Disabled till non-TX, TX conflict detection is complete in new TX model.
   */
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTxReplace() {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);
    
    final CustId custId = new CustId(1);
    final Customer updatedCust = new Customer("updated", "updated");
    final TXId txId = (TXId)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> cust = getCache().getRegion(CUSTOMER);
        Region<CustId, Customer> ref = getCache().getRegion(D_REFERENCE);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        Customer customer = new Customer("customer1", "address1");
        Customer fakeCust = new Customer("foo", "bar");
        assertFalse(cust.replace(custId, fakeCust, updatedCust));
        assertTrue(cust.replace(custId, customer, updatedCust));
        assertFalse(ref.replace(custId, fakeCust, updatedCust));
        assertTrue(ref.replace(custId, customer, updatedCust));
        TXStateInterface tx = mgr.internalSuspend();
        assertEquals(cust.get(custId), customer);
        assertEquals(ref.get(custId), customer);
        mgr.resume(tx);
        return mgr.getTransactionId();
      }
    });
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertTrue(mgr.isHostedTxInProgress(txId));
        TXStateInterface tx = mgr.getHostedTXState(txId);
        // 2 buckets for the two puts we did in the accessor one dist. region,
        // and one more bucket if Cust1 and Cust10 resolve to different buckets
        assertEquals("unexpected regions: " + tx.getRegions(),
            2, tx.getRegions().size());
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof BucketRegion || r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = (TXEntryState)rs.readEntry(key);
            assertNotNull(es.getValue(r));
            assertTrue("key:"+key+" r:"+r.getFullPath(), es.isDirty());
          }
        }
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.commit();
        Region<CustId, Customer> cust = getCache().getRegion(CUSTOMER);
        Region<CustId, Customer> rr = getCache().getRegion(D_REFERENCE);
        assertEquals(updatedCust, cust.get(custId));
        assertEquals(updatedCust, rr.get(custId));
        //check conflict
        mgr.begin();
        CustId conflictCust = new CustId(2);
        Customer customer = new Customer("customer2", "address2");
        getGemfireCache().getLoggerI18n().fine("SWAP:removeConflict");
        assertTrue(cust.replace(conflictCust, customer, new Customer(
            "conflict", "conflict")));
        TXStateInterface tx = mgr.internalSuspend();
        cust.put(conflictCust, new Customer("foo", "bar"));
        mgr.resume(tx);
        try {
          mgr.commit();
          fail("expected exception not thrown");
        } catch (ConflictException e) {
        }
        return null;
      }
    });
  }

  /**
   * When we have narrowed down on a target node for a transaction, test that we
   * work correctly even if that node does not host primary for subsequent
   * entries.
   */
  public void Bug44146_testNonColocatedTX() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);

    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        createRegion(false, 1, null);
        return null;
      }
    });
    
    initAccessorAndDataStore(accessor, datastore1, 1);

    final TXId txId = (TXId)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        put10Entries(custRegion, orderRegion);
        return mgr.getTransactionId();
      }
    });
    // check for TXState's on both datastores
    final Integer txOnDatastore1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    final Integer txOnDatastore2 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    // we expect transactions to be distributed on both stores
    assertEquals(2, txOnDatastore1 + txOnDatastore2);

    // check for all 10 entries in TX view on both nodes

    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXState txState = mgr.getHostedTXState(txId).getLocalTXState();
        final TXStateInterface currentTX = mgr.getTXState();
        mgr.masqueradeAs(txState);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              txState.getEntry(new KeyInfo(custId, null, null), custRegion,
                  false, false).getValue());
          assertEquals(new Order("order" + index), txState.getEntry(
              new KeyInfo(new OrderId(index, custId), null, null), orderRegion,
              false, false).getValue());
        }
        mgr.masqueradeAs(currentTX);
        return null;
      }
    });
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXState txState = mgr.getHostedTXState(txId).getLocalTXState();
        final TXStateInterface currentTX = mgr.getTXState();
        mgr.masqueradeAs(txState);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              txState.getEntry(new KeyInfo(custId, null, null), custRegion,
                  false, false).getValue());
          assertEquals(new Order("order" + index), txState.getEntry(
              new KeyInfo(new OrderId(index, custId), null, null), orderRegion,
              false, false).getValue());
        }
        mgr.masqueradeAs(currentTX);
        return null;
      }
    });
    // check on accessor
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              custRegion.getEntry(custId).getValue());
          assertEquals(new Order("order" + index),
              orderRegion.get(new OrderId(index, custId)));
        }
        return null;
      }
    });

    // abort the TX and check no entries
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.rollback();
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          if (index < 5) {
            assertEquals(new Customer("customer" + index, "address" + index),
                custRegion.get(custId));
            assertEquals(new Order("order" + index),
                orderRegion.getEntry(new OrderId(index, custId)).getValue());
          }
          else {
            assertNull(custRegion.get(custId));
            assertNull(orderRegion.getEntry(new OrderId(index, custId)));
          }
        }
        return null;
      }
    });
    // check no TX
    datastore1.invoke(verifyNoTxState);
    datastore2.invoke(verifyNoTxState);

    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          if (index < 5) {
            assertEquals(new Customer("customer" + index, "address" + index),
                custRegion.getEntry(custId).getValue());
            assertEquals(new Order("order" + index),
                orderRegion.get(new OrderId(index, custId)));
          }
          else {
            assertNull(custRegion.get(custId));
            assertNull(orderRegion.getEntry(new OrderId(index, custId)));
          }
        }
        return null;
      }
    });
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          if (index < 5) {
            assertEquals(new Customer("customer" + index, "address" + index),
                custRegion.get(custId));
            assertEquals(new Order("order" + index),
                orderRegion.get(new OrderId(index, custId)));
          }
          else {
            assertNull(custRegion.get(custId));
            assertNull(orderRegion.getEntry(new OrderId(index, custId)));
          }
        }
        return null;
      }
    });

    // do it again and commit this time

    final TXId txId2 = (TXId)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        put10Entries(custRegion, orderRegion);
        return mgr.getTransactionId();
      }
    });

    // check for all 10 entries in TX view on both nodes

    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXState txState = mgr.getHostedTXState(txId2).getLocalTXState();
        final TXStateInterface currentTX = mgr.getTXState();
        mgr.masqueradeAs(txState);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              txState.getEntry(new KeyInfo(custId, null, null), custRegion,
                  false, false).getValue());
          assertEquals(new Order("order" + index), txState.getEntry(
              new KeyInfo(new OrderId(index, custId), null, null), orderRegion,
              false, false).getValue());
        }
        mgr.masqueradeAs(currentTX);
        return null;
      }
    });
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXState txState = mgr.getHostedTXState(txId2).getLocalTXState();
        final TXStateInterface currentTX = mgr.getTXState();
        mgr.masqueradeAs(txState);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              txState.getEntry(new KeyInfo(custId, null, null), custRegion,
                  false, false).getValue());
          assertEquals(new Order("order" + index), txState.getEntry(
              new KeyInfo(new OrderId(index, custId), null, null), orderRegion,
              false, false).getValue());
        }
        mgr.masqueradeAs(currentTX);
        return null;
      }
    });
    // check on accessor
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              custRegion.get(custId));
          assertEquals(new Order("order" + index),
              orderRegion.getEntry(new OrderId(index, custId)).getValue());
        }
        return null;
      }
    });

    // commit the TX and check all entries
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.commit();
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              custRegion.getEntry(custId).getValue());
          assertEquals(new Order("order" + index),
              orderRegion.get(new OrderId(index, custId)));
        }
        return null;
      }
    });
    // check no TX
    accessor.invoke(waitForPendingCommit);
    datastore1.invoke(verifyNoTxState);
    datastore2.invoke(verifyNoTxState);

    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              custRegion.get(custId));
          assertEquals(new Order("order" + index),
              orderRegion.getEntry(new OrderId(index, custId)).getValue());
        }
        return null;
      }
    });
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        LocalRegion custRegion = (LocalRegion)getCache().getRegion(CUSTOMER);
        LocalRegion orderRegion = (LocalRegion)getCache().getRegion(ORDER);
        for (int index = 0; index < 10; ++index) {
          final CustId custId = new CustId(index);
          assertEquals(new Customer("customer" + index, "address" + index),
              custRegion.getEntry(custId).getValue());
          assertEquals(new Order("order" + index),
              orderRegion.get(new OrderId(index, custId)));
        }
        return null;
      }
    });
  }

  private void put10Entries(Region<CustId, Customer> custRegion,
      Region<OrderId, Order> orderRegion) {
    for (int i = 0; i < 10; i++) {
      CustId custId = new CustId(i);
      Customer customer = new Customer("customer" + i, "address" + i);
      OrderId orderId = new OrderId(i, custId);
      Order order = new Order("order" + i);
      if (i >= 5) {
        custRegion.create(custId, customer);
        orderRegion.create(orderId, order);
      }
      else {
        custRegion.put(custId, customer);
        orderRegion.put(orderId, order);
      }
    }
  }

  public void Bug44146_testListenersForPut() {
    doTestListeners(OP.PUT);
  }

  public void Bug44146_testListenersForDestroy() {
    doTestListeners(OP.DESTROY);
  }

  public void Bug44146_testListenersForInvalidate() {
    doTestListeners(OP.INVALIDATE);
  }

  private void doTestListeners(final OP op) {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(CUSTOMER);
        cust.getAttributesMutator().addCacheListener(new TestCacheListener(true));
        cust.getAttributesMutator().setCacheWriter(new TestCacheWriter(true));
        Region order = getCache().getRegion(ORDER);
        order.getAttributesMutator().addCacheListener(new TestCacheListener(true));
        order.getAttributesMutator().setCacheWriter(new TestCacheWriter(true));
        getGemfireCache().getTxManager().addListener(new TestTxListener(true));
        if (!getGemfireCache().isClient()) {
          getGemfireCache().getTxManager().setWriter(new TestTxWriter(true));
        }
        return null;
      }
    });

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(CUSTOMER);
        cust.getAttributesMutator().addCacheListener(new TestCacheListener(false));
        cust.getAttributesMutator().setCacheWriter(new TestCacheWriter(false));
        Region order = getCache().getRegion(ORDER);
        order.getAttributesMutator().addCacheListener(new TestCacheListener(false));
        order.getAttributesMutator().setCacheWriter(new TestCacheWriter(false));
        getGemfireCache().getTxManager().addListener(new TestTxListener(false));
        if (!getGemfireCache().isClient()) {
          getGemfireCache().getTxManager().setWriter(new TestTxWriter(false));
        }
        return null;
      }
    });

    accessor.invoke(new DoOpsInTX(op));
    
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.commit();
        TXManagerImpl.waitForPendingCommitForTest();
        return null;
      }
    });

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TestTxListener l = (TestTxListener)getGemfireCache().getTxManager().getListener();
        assertTrue(l.isListenerInvoked());
        return null;
      }
    });
    SerializableCallable verifyListeners = new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(CUSTOMER);
        Region order = getCache().getRegion(ORDER);
        throwListenerException(cust);
        throwListenerException(order);
        throwWriterException(cust);
        throwWriterException(order);
        if (!getGemfireCache().isClient()) {
          throwTransactionCallbackException();
        }
        return null;
      }
      private void throwTransactionCallbackException() throws Exception {
        TestTxListener l = (TestTxListener)getGemfireCache().getTxManager().getListener();
        if (l.ex != null) {
          throw l.ex;
        }
        TestTxWriter w = (TestTxWriter)getGemfireCache().getTxManager().getWriter();
        if (w.ex != null) {
          throw w.ex;
        }
      }
      private void throwListenerException(Region r) throws Exception {
        Exception e = null;
        CacheListener listener = r.getAttributes().getCacheListeners()[0];
        if (listener instanceof TestCacheListener) {
          e = ((TestCacheListener)listener).ex;
        } else {
//          e = ((ClientListener)listener).???
        }
        if (e != null) {
          throw e;
        }
      }
      private void throwWriterException(Region r) throws Exception {
        Exception e = null;
        CacheListener listener = r.getAttributes().getCacheListeners()[0];
        if (listener instanceof TestCacheListener) {
          e = ((TestCacheListener)listener).ex;
        } else {
//        e = ((ClientListener)listener).???
        }
        if (e != null) {
          throw e;
        }
      }
    };
    accessor.invoke(verifyListeners);
    datastore.invoke(verifyListeners);
  }

  abstract class CacheCallback {
    protected boolean isAccessor;
    protected Exception ex = null;
    protected void verifyOrigin(EntryEvent event) {
      try {
        assertEquals(!isAccessor, event.isOriginRemote());
      } catch (Exception e) {
        ex = e;
      }
    }
    protected void verifyPutAll(EntryEvent event) {
      CustId knownCustId = new CustId(1);
      OrderId knownOrderId = new OrderId(2, knownCustId);
      if (event.getKey().equals(knownOrderId)) {
        try {
          assertTrue(event.getOperation().isPutAll());
          assertNotNull(event.getTransactionId());
        } catch (Exception e) {
          ex = e;
        }
      }
    }

  }
  
  class TestCacheListener extends CacheCallback implements CacheListener {
    TestCacheListener(boolean isAccessor) {
      this.isAccessor = isAccessor;
    }
    public void afterCreate(EntryEvent event) {
      verifyOrigin(event);
      verifyPutAll(event);
    }
    public void afterUpdate(EntryEvent event) {
      verifyOrigin(event);
      verifyPutAll(event);
    }
    public void afterDestroy(EntryEvent event) {
      verifyOrigin(event);
    }
    public void afterInvalidate(EntryEvent event) {
      verifyOrigin(event);
    }
    public void afterRegionClear(RegionEvent event) {
    }
    public void afterRegionCreate(RegionEvent event) {
    }
    public void afterRegionDestroy(RegionEvent event) {
    }
    public void afterRegionInvalidate(RegionEvent event) {
    }
    public void afterRegionLive(RegionEvent event) {
    }
    public void close() {
    }
  }

  class TestCacheWriter extends CacheCallback implements CacheWriter {
    TestCacheWriter(boolean isAccessor) {
      this.isAccessor = isAccessor;
    }
    public void beforeCreate(EntryEvent event) throws CacheWriterException {
      getGemfireCache().getLogger().info("SWAP:beforeCreate:"+event+" op:"+event.getOperation());
      verifyOrigin(event);
      verifyPutAll(event);
    }
    public void beforeUpdate(EntryEvent event) throws CacheWriterException {
      getGemfireCache().getLogger().info("SWAP:beforeCreate:"+event+" op:"+event.getOperation());
      verifyOrigin(event);
      verifyPutAll(event);
    }
    public void beforeDestroy(EntryEvent event) throws CacheWriterException {
      verifyOrigin(event);
    }
    public void beforeRegionClear(RegionEvent event)
        throws CacheWriterException {
    }
    public void beforeRegionDestroy(RegionEvent event)
        throws CacheWriterException {
    }
    public void close() {
    }
  }

  abstract class txCallback {
    protected boolean isAccessor;
    protected Exception ex = null;
    protected void verify(TransactionEvent txEvent) {
      for (CacheEvent e : txEvent.getEvents()) {
        verifyOrigin(e);
        verifyPutAll(e);
      }
    }
    private void verifyOrigin(CacheEvent event) {
      try {
        assertEquals(!isAccessor, event.isOriginRemote()); //change to !isAccessor after fixing #41498
      } catch (Exception e) {
        ex = e;
      }
    }
    private void verifyPutAll(CacheEvent p_event) {
      if (!(p_event instanceof EntryEvent)) {
        return;
      }
      EntryEvent event = (EntryEvent)p_event;
      CustId knownCustId = new CustId(1);
      OrderId knownOrderId = new OrderId(2, knownCustId);
      if (event.getKey().equals(knownOrderId)) {
        try {
          assertTrue(event.getOperation().isPutAll());
          assertNotNull(event.getTransactionId());
        } catch (Exception e) {
          ex = e;
        }
      }
    }
  }
  
  class TestTxListener extends txCallback implements TransactionListener {
    private volatile boolean listenerInvoked;
    TestTxListener(boolean isAccessor) {
      this.isAccessor = isAccessor;
    }
    public void afterCommit(TransactionEvent event) {
      listenerInvoked = true;
      verify(event);
    }
    public void afterFailedCommit(TransactionEvent event) {
      verify(event);
    }
    public void afterRollback(TransactionEvent event) {
      listenerInvoked = true;
      verify(event);
    }
    public boolean isListenerInvoked() {
      return this.listenerInvoked;
    }
    public void close() {
    }
  }

  class TestTxWriter extends txCallback implements TransactionWriter {
    public TestTxWriter(boolean isAccessor) {
      this.isAccessor = isAccessor;
    }
    public void beforeCommit(TransactionEvent event) {
      verify(event);
    }
    public void close() {
    }
  }

  /**
   * Disabled till TransactionWriter/Listener is complete in new TX model.
   */
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testRemoteExceptionThrown() {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);
    
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        getGemfireCache().getTxManager().setWriter(new TransactionWriter() {
          public void close() {
          }
          public void beforeCommit(TransactionEvent event)
              throws TransactionWriterException {
            throw new TransactionWriterException("TestException");
          }
        });
        return null;
      }
    });
    
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        getGemfireCache().getTxManager().begin();
        Region r = getCache().getRegion(CUSTOMER);
        r.put(new CustId(8), new Customer("name8", "address8"));
        try {
          getGemfireCache().getTxManager().commit();
          fail("Expected exception not thrown");
        } catch (Exception e) {
          assertEquals("TestException", e.getCause().getMessage());
        }
        return null;
      }
    });
  }

  public void Bug44146testSize() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        assertEquals(5, custRegion.size());
        assertNotNull(mgr.getTXState());
        return null;
      }
    });
    datastore1.invoke(verifyNoTxState);
    datastore2.invoke(verifyNoTxState);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        Region orderRegion = getCache().getRegion(ORDER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertNotNull(mgr.getTXState());
        CustId custId = new CustId(5);
        OrderId orderId = new OrderId(5, custId);
        custRegion.put(custId, new Customer("customer5", "address5"));
        orderRegion.put(orderId, new Order("order5"));

        TXStateInterface tx = mgr.internalSuspend();
        // RegionEntries are already in the region due to locking but size
        // information should still be correct
        Set<?> entries = custRegion.entrySet();
        Collection<?> values = custRegion.values();
        Collection<?> keys = custRegion.keySet();
        assertEquals(5, custRegion.size());
        assertEquals(5, entries.size());
        assertEquals(5, values.size());
        assertEquals(5, keys.size());
        // actually iterate the entries to confirm the size
        int size = 0;
        for (Object val : entries) {
          assertNotNull(val);
          assertNotNull(((Map.Entry<?, ?>)val).getValue());
          ++size;
        }
        assertEquals(5, size);
        // now values
        size = 0;
        for (Object val : values) {
          assertNotNull(val);
          ++size;
        }
        assertEquals(5, size);
        // now keys
        size = 0;
        for (Object val : keys) {
          assertNotNull(val);
          ++size;
        }
        assertEquals(5, size);

        mgr.resume(tx);
        // refresh the entries for TX
        entries = custRegion.entrySet();
        values = custRegion.values();
        keys = custRegion.keySet();
        assertEquals(6, custRegion.size());
        assertEquals(6, entries.size());
        assertEquals(6, values.size());
        assertEquals(6, keys.size());
        // actually iterate the entries to confirm the size
        size = 0;
        for (Object val : entries) {
          assertNotNull(val);
          assertNotNull(((Map.Entry<?, ?>)val).getValue());
          ++size;
        }
        assertEquals(6, size);
        // now values
        size = 0;
        for (Object val : values) {
          assertNotNull(val);
          ++size;
        }
        assertEquals(6, size);
        // now keys
        size = 0;
        for (Object val : keys) {
          assertNotNull(val);
          ++size;
        }
        assertEquals(6, size);

        return mgr.getTransactionId();
      }
    });
    final Integer txOnDatastore1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    final Integer txOnDatastore2 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    // we expect transaction to be distributed to one store since gets
    // do not create TXStates in READ_COMMITTED
    assertEquals(1, txOnDatastore1 + txOnDatastore2);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.commit();
        TXManagerImpl.waitForPendingCommitForTest();
        return null;
      }
    });
    final Integer txOnDatastore1_1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    final Integer txOnDatastore2_2 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    // expect transactions to be cleaned up on both stores
    assertEquals(0, txOnDatastore1_1 + txOnDatastore2_2);
  }

  public void Bug44146_testKeysIterator() {
    doTestIterator(OP.KEYS, 0, OP.PUT);
  }

  public void Bug44146_testValuesIterator() {
    doTestIterator(OP.VALUES, 0, OP.PUT);
  }

  public void Bug44146_testEntriesIterator() {
    doTestIterator(OP.ENTRIES, 0, OP.PUT);
  }

  public void Bug44146_testKeysIterator1() {
    doTestIterator(OP.KEYS, 1, OP.PUT);
  }

  public void Bug44146_testValuesIterator1() {
    doTestIterator(OP.VALUES, 1, OP.PUT);
  }

  public void Bug44146_testEntriesIterator1() {
    doTestIterator(OP.ENTRIES, 1, OP.PUT);
  }

  public void Bug44146_testKeysIteratorOnDestroy() {
    doTestIterator(OP.KEYS, 0, OP.DESTROY);
  }

  public void Bug44146_testValuesIteratorOnDestroy() {
    doTestIterator(OP.VALUES, 0, OP.DESTROY);
  }

  public void Bug44146_testEntriesIteratorOnDestroy() {
    doTestIterator(OP.ENTRIES, 0, OP.DESTROY);
  }

  public void Bug44146_testKeysIterator1OnDestroy() {
    doTestIterator(OP.KEYS, 1, OP.DESTROY);
  }

  public void Bug44146_testValuesIterator1OnDestroy() {
    doTestIterator(OP.VALUES, 1, OP.DESTROY);
  }

  public void Bug44146_testEntriesIterator1OnDestroy() {
    doTestIterator(OP.ENTRIES, 1, OP.DESTROY);
  }

  private void doTestIterator(final OP iteratorType, final int redundancy, final OP op) {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, redundancy);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<?, ?> custRegion = getCache().getRegion(CUSTOMER);
        Set<?> originalSet;
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        switch (iteratorType) {
        case KEYS:
          originalSet = getCustIdSet(5);
          assertTrue(originalSet.containsAll(custRegion.keySet()));
          assertEquals(5, custRegion.keySet().size());
          break;
        case VALUES:
          originalSet = getCustomerSet(5);
          assertTrue(originalSet.containsAll(custRegion.values()));
          assertEquals(5, custRegion.values().size());
          break;
        case ENTRIES:
          Set<CustId> originalKeySet = getCustIdSet(5);
          Set<Customer> originalValueSet = getCustomerSet(5);
          Set<Object> entrySet = new HashSet<Object>();
          for (Iterator<?> it = custRegion.entrySet().iterator(); it.hasNext();) {
            entrySet.add(it.next());
          }
          Region.Entry<?, ?> entry;
          for (Iterator<?> it = entrySet.iterator(); it.hasNext();) {
            entry = (Region.Entry<?, ?>)it.next();
            assertTrue(originalKeySet.contains(entry.getKey()));
            assertTrue(originalValueSet.contains(entry.getValue()));
          }
          assertEquals(5, custRegion.entrySet().size());
          break;
        default:
            throw new IllegalArgumentException();
        }
        assertNotNull(mgr.getTXState());
        return null;
      }
    });
    // for READ_COMMITTED no TXState will be created
    if (iteratorType == OP.KEYS || !this.isRepeatableRead) {
      datastore1.invoke(verifyNoTxState);
      datastore2.invoke(verifyNoTxState);
    }
    else {
      // for values iteration, a get has to be fired on datastores
      // to get the underlying value so they will have TXState
      final Integer txOnDatastore1 = (Integer)datastore1
          .invoke(getNumberOfTXInProgress);
      final Integer txOnDatastore2 = (Integer)datastore2
          .invoke(getNumberOfTXInProgress);
      // we expect entries to be distributed on both stores
      assertTrue((txOnDatastore1 + txOnDatastore2) >= 1);
      assertTrue((txOnDatastore1 + txOnDatastore2) <= 2);
    }

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
        
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertNotNull(mgr.getTXState());
        int expectedSetSize = 0;
        switch (op) {
        case PUT:
          CustId custId = new CustId(5);
          OrderId orderId = new OrderId(5, custId);
          custRegion.put(custId, new Customer("customer5", "address5"));
          orderRegion.put(orderId, new Order("order5"));
          expectedSetSize = 6;
          break;
        case DESTROY:
          CustId custId1 = new CustId(4);
          OrderId orderId1 = new OrderId(4, custId1);
          custRegion.destroy(custId1);
          orderRegion.destroy(orderId1);
          expectedSetSize = 4;
          break;
        default:
          throw new IllegalStateException();
        }
        
        Set<?> expectedSet;
        switch (iteratorType) {
        case KEYS:
          expectedSet = getCustIdSet(expectedSetSize);
          assertTrue(expectedSet.containsAll(custRegion.keySet()));
          assertEquals(expectedSetSize, custRegion.keySet().size());
          break;
        case VALUES:
          expectedSet = getCustomerSet(expectedSetSize);
          assertTrue(expectedSet.containsAll(custRegion.values()));
          assertEquals(expectedSetSize, custRegion.values().size());
          break;
        case ENTRIES:
          Set<CustId> originalKeySet = getCustIdSet(expectedSetSize);
          Set<Customer> originalValueSet = getCustomerSet(expectedSetSize);
          Set<Object> entrySet = new HashSet<Object>();
          for (Iterator<?> it = custRegion.entrySet().iterator(); it.hasNext();) {
            entrySet.add(it.next());
          }
          Region.Entry<?, ?> entry;
          for (Iterator<?> it = entrySet.iterator(); it.hasNext();) {
            entry = (Region.Entry<?, ?>)it.next();
            assertTrue(originalKeySet.contains(entry.getKey()));
            assertTrue(originalValueSet.contains(entry.getValue()));
          }
          assertEquals(expectedSetSize, custRegion.entrySet().size());
          break;
        default:
          throw new IllegalArgumentException();
        }
        
        return null;
      }
    });
    final Integer txOnDatastore1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    final Integer txOnDatastore2 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    if (iteratorType == OP.KEYS || !this.isRepeatableRead) {
      // single put/destroy will be distributed to as many stores as redundancy
      assertEquals(1 + redundancy, txOnDatastore1 + txOnDatastore2);
    }
    else {
      // both stores due to the gets fired before
      assertTrue((txOnDatastore1 + txOnDatastore2) >= 1);
      assertTrue((txOnDatastore1 + txOnDatastore2) <= 2);
    }

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.commit();
        TXManagerImpl.waitForPendingCommitForTest();
        return null;
      }
    });
    final Integer txOnDatastore1_1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    final Integer txOnDatastore2_2 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    assertEquals(0, txOnDatastore1_1 + txOnDatastore2_2);

    datastore1.invoke(new SerializableCallable() {
      CustId custId;
      Customer customer;
      PartitionedRegion custRegion;
      int originalSetSize;
      int expectedSetSize;
      
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        custRegion = (PartitionedRegion)getCache().getRegion(CUSTOMER);
        mgr.begin();
        doLocalOp();
        Set<?> expectedSet;
        switch (iteratorType) {
        case KEYS:
          expectedSet = getExpectedCustIdSet();
          assertEquals(expectedSet, custRegion.keySet());
          assertEquals(expectedSetSize, custRegion.keySet().size());
          break;
        case VALUES:
          expectedSet = getExpectedCustomerSet();
          assertEquals(expectedSet, custRegion.values());
          assertEquals(expectedSetSize, custRegion.values().size());
          break;
        case ENTRIES:
          Set<?> originalKeySet = getExpectedCustIdSet();
          Set<?> originalValueSet = getExpectedCustomerSet();
          Set<Object> entrySet = new HashSet<Object>();
          Region.Entry<?, ?> entry;
          for (Iterator<?> it = custRegion.entrySet().iterator(); it.hasNext();) {
            entrySet.add(it.next());
          }
          for (Iterator<?> it = entrySet.iterator(); it.hasNext();) {
            entry = (Region.Entry<?, ?>)it.next();
            assertTrue(originalKeySet.contains(entry.getKey()));
            assertTrue(originalValueSet.contains(entry.getValue()));
          }
          assertEquals(expectedSetSize, custRegion.entrySet().size());
          break;
        default:
            throw new IllegalArgumentException();
        }
        mgr.commit();
        return null;
      }
      
      private void doLocalOp() {
        switch (op) {
        case PUT:
          for (int i=6;;i++) {
            custId = new CustId(i);
            customer = new Customer("customer"+i, "address"+i);
            int bucketId = PartitionedRegionHelper.getHashKey(custRegion, custId);
            InternalDistributedMember primary = custRegion.getBucketPrimary(bucketId);
            if (primary.equals(getGemfireCache().getMyId())) {
              custRegion.put(custId, customer);
              break;
            }
          }
          originalSetSize = 6;
          expectedSetSize = 7;
          break;
        case DESTROY:
          for (int i=3;; i--) {
            custId = new CustId(i);
            customer = new Customer("customer"+i, "address"+i);
            int bucketId = PartitionedRegionHelper.getHashKey(custRegion, custId);
            InternalDistributedMember primary = custRegion.getBucketPrimary(bucketId);
            if (primary.equals(getGemfireCache().getMyId())) {
              custRegion.destroy(custId);
              break;
            }
          }
          originalSetSize = 4;
          expectedSetSize = 3;
          break;
          default:
            throw new IllegalStateException();
        }
      }
      
      private Set<CustId> getExpectedCustIdSet() {
        Set<CustId> retVal = getCustIdSet(originalSetSize);
        switch (op) {
        case PUT:
          retVal.add(custId);
          break;
        case DESTROY:
          retVal.remove(custId);
          break;
        default:
          throw new IllegalStateException();
        }
        return retVal;
      }
      
      private Set<Customer> getExpectedCustomerSet() {
        Set<Customer> retVal = getCustomerSet(originalSetSize);
        switch (op) {
        case PUT:
          retVal.add(customer);
          break;
        case DESTROY:
          retVal.remove(customer);
          break;
        default:
          throw new IllegalStateException();
        }
        return retVal;
      }
      
    });
  }

  public void Bug44146_testKeyIterationOnRR() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<Object, Object> rr = getCache().getRegion(D_REFERENCE);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        CustId custId = new CustId(5);
        Customer customer = new Customer("customer5", "address5");
        custRegion.put(custId, customer);
        Set<?> origSet = rr.keySet();
        Set<Object> set = new HashSet<Object>();
        Iterator<?> it = origSet.iterator();
        int i = 0;
        while (it.hasNext()) {
          i++;
          set.add(it.next());
        }
        assertEquals(5, i);
        assertTrue(getCustIdSet(5).equals(set));
        rr.put(custId, customer);
        origSet = rr.keySet();
        set.clear();
        it = origSet.iterator();
        i = 0;
        while (it.hasNext()) {
          i++;
          set.add(it.next());
        }
        assertEquals(6, i);
        assertTrue(getCustIdSet(6).equals(set));
        assertEquals(customer, rr.get(custId));
        TXStateInterface tx = mgr.internalSuspend();
        origSet = rr.keySet();
        set.clear();
        it = origSet.iterator();
        i = 0;
        while (it.hasNext()) {
          i++;
          set.add(it.next());
        }
        assertEquals(5, i);
        assertEquals(getCustIdSet(5), set);
        assertEquals(5, set.size());
        assertNull(rr.get(custId));
        mgr.resume(tx);
        mgr.commit();
        // now check in region after commit
        set = rr.keySet();
        assertEquals(6, set.size());
        assertTrue(getCustIdSet(6).equals(set));
        assertEquals(customer, rr.get(custId));
        return null;
      }
    });
  }

  public void Bug44146_testValuesIterationOnRR() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<CustId, Customer> rr = getCache().getRegion(D_REFERENCE);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        CustId custId = new CustId(5);
        Customer customer = new Customer("customer5", "address5");
        custRegion.put(custId, customer);
        Collection<?> origSet = rr.values();
        Iterator<?> it = origSet.iterator();
        Set<Object> set = new HashSet<Object>();
        int i = 0;
        while (it.hasNext()) {
          i++;
          set.add(it.next());
        }
        assertEquals(5, i);
        assertTrue(getCustomerSet(5).equals(set));
        assertEquals(5, set.size());
        rr.put(custId, customer);
        origSet = rr.values();
        it = origSet.iterator();
        set.clear();
        i = 0;
        while (it.hasNext()) {
          i++;
          set.add(it.next());
        }
        assertEquals(6, i);
        assertTrue(getCustomerSet(6).equals(set));
        assertEquals(6, set.size());
        assertEquals(customer, rr.get(custId));
        TXStateInterface tx = mgr.internalSuspend();
        origSet = rr.values();
        it = origSet.iterator();
        set.clear();
        i = 0;
        while (it.hasNext()) {
          i++;
          set.add(it.next());
        }
        assertEquals(5, i);
        assertTrue(getCustomerSet(5).equals(set));
        assertEquals(5, set.size());
        assertNull(rr.get(custId));
        mgr.resume(tx);
        mgr.commit();
        return null;
      }
    });
    // check successful commit
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<Object, Object> rr = getCache().getRegion(D_REFERENCE);
        assertTrue(getCustomerSet(6).equals(custRegion.values()));
        assertTrue(getCustomerSet(6).equals(rr.values()));
        return null;
      }
    });
  }

  public void Bug44146_testEntriesIterationOnRR() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        Region rr = getCache().getRegion(D_REFERENCE);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        CustId custId = new CustId(5);
        Customer customer = new Customer("customer5", "address5");
        custRegion.put(custId, customer);
        Set set = rr.entrySet();
        Iterator it = set.iterator();
        int i=0;
        while (it.hasNext()) {
          i++;
          it.next();
        }
        assertEquals(5, i);
        //assertTrue(getCustIdSet(5).equals(set));
        assertEquals(5, rr.entrySet().size());
        rr.put(custId, customer);
        set = rr.entrySet();
        //assertTrue(getCustIdSet(6).equals(set));
        it = set.iterator();
        i=0;
        while (it.hasNext()) {
          i++;
          it.next();
        }
        assertEquals(6, i);
        assertEquals(6, rr.entrySet().size());
        assertNotNull(rr.get(custId));
        TXStateInterface tx = mgr.internalSuspend();
        //assertEquals(getCustIdSet(5), rr.entrySet());
        // we will see the TX entry in region due to locking but size will
        // still be adjusted correctly
        assertEquals(5, rr.entrySet().size());
        // iteration over entries should give proper value
        int size = 0;
        for (Object entry : rr.entrySet()) {
          assertNotNull(((Map.Entry<?, ?>)entry).getValue());
          ++size;
        }
        assertEquals(5, size);
        assertNull(rr.get(custId));
        mgr.resume(tx);
        mgr.commit();
        return null;
      }
    });
  }

  public void Bug44146_testIllegalIteration() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);

    SerializableCallable doIllegalIteration = new SerializableCallable() {
      public Object call() throws Exception {
        Region r = getCache().getRegion(CUSTOMER);
        Set keySet = r.keySet();
        Set entrySet = r.entrySet();
        Set valueSet = (Set)r.values();
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.begin();
        // now we allow for using non-TX iterators in TX context
        keySet.size();
        entrySet.size();
        valueSet.size();
        keySet.iterator();
        entrySet.iterator();
        valueSet.iterator();
        // TX iterators
        keySet = r.keySet();
        entrySet = r.entrySet();
        valueSet = (Set)r.values();
        mgr.commit();
        // don't allow for TX iterator after TX has committed
        try {
          keySet.size();
          fail("Expected exception not thrown");
        } catch (IllegalTransactionStateException expected) {
          //ignore
        }
        try {
          entrySet.size();
          fail("Expected exception not thrown");
        } catch (IllegalTransactionStateException expected) {
          //ignore
        }
        try {
          valueSet.size();
          fail("Expected exception not thrown");
        } catch (IllegalTransactionStateException expected) {
          //ignore
        }
        try {
          keySet.iterator();
          fail("Expected exception not thrown");
        } catch (IllegalTransactionStateException expected) {
          //ignore
        }
        try {
          entrySet.iterator();
          fail("Expected exception not thrown");
        } catch (IllegalTransactionStateException expected) {
          //ignore
        }
        try {
          valueSet.iterator();
          fail("Expected exception not thrown");
        } catch (IllegalTransactionStateException expected) {
          //ignore
        }
        return null;
      }
    };

    accessor.invoke(doIllegalIteration);
    datastore1.invoke(doIllegalIteration);
  }

  final CustId expectedCustId = new CustId(6);
  final Customer expectedCustomer = new Customer("customer6", "address6");
  final Customer expectedCustomerUpdate = new Customer("cust6", "addr6");
  final OrderId expectedOrderId = new OrderId(6, expectedCustId);
  final Order expectedOrder = new Order("order6");
  final Order expectedOrderUpdate = new Order("o6");

  class TXFunction implements Function {
    static final String id = "TXFunction";
    public void execute(FunctionContext context) {
      final Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
      final Object args = context.getArguments();
      //final String bbFlag = "OP_DONE";
      getGemfireCache().getLogger().fine("SWAP:callingPut");
      if (args instanceof Object[]) {
        // distributed function case; put in different regions
        Object[] memberIds = (Object[])args;
        DistributedMember myId = getGemfireCache().getMyId();
        if (memberIds[0].equals(myId)) { // datastore1
          custRegion.put(expectedCustId, expectedCustomer);
        }
        else if (memberIds[1].equals(myId)) { // datastore2
          Region<Object, Object> orderRegion = getCache().getRegion(ORDER);
          orderRegion.put(expectedOrderId, expectedOrder);
        }
        else { // accessor
          Region<Object, Object> refRegion = getCache().getRegion(D_REFERENCE);
          refRegion.put(expectedCustId, expectedCustomer);
        }
      }
      else {
        custRegion.put(expectedCustId, expectedCustomer);
      }
      GemFireCacheImpl.getInstance().getLogger().warning(" XXX DOIN A PUT ",new Exception());
      context.getResultSender().lastResult(Boolean.TRUE);
    }
    public String getId() {
      return id;
    }
    public boolean hasResult() {
      return true;
    }
    public boolean optimizeForWrite() {
      return true;
    }
    public boolean isHA() {
      return false;
    }
  }
  
  enum Executions {
    OnRegion,
    OnMember
  }

  public void Bug44146_testTxFunctionOnRegions() {
    doTestTxFunction(Executions.OnRegion);
  }

  public void Bug44146_testTxFunctionOnMembers() {
    doTestTxFunction(Executions.OnMember);
  }

  private void doTestTxFunction(final Executions e) {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 1);

    SerializableCallable registerFunction = new SerializableCallable() {
      public Object call() throws Exception {
        FunctionService.registerFunction(new TXFunction());
        return null;
      }
    };

    accessor.invoke(registerFunction);
    datastore1.invoke(registerFunction);
    datastore2.invoke(registerFunction);

    final DistributedMember store1Id = (DistributedMember)datastore1
        .invoke(new SerializableCallable() {
          public Object call() throws Exception {
            return getGemfireCache().getMyId();
          }
        });
    final DistributedMember store2Id = (DistributedMember)datastore2
        .invoke(new SerializableCallable() {
          public Object call() throws Exception {
            return getGemfireCache().getMyId();
          }
        });
    // first try distributed non-colocated function execution
    final TXId txId = (TXId)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        Region<Object, Object> orderRegion = getCache().getRegion(ORDER);
        Region<Object, Object> refRegion = getCache().getRegion(D_REFERENCE);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = null;
        @SuppressWarnings("rawtypes")
        final Set<Region> regions = new HashSet<Region>();
        regions.add(custRegion);
        regions.add(orderRegion);
        regions.add(refRegion);
        final ArrayList<Object> expectedList = new ArrayList<Object>();
        mgr.begin();
        switch (e) {
          case OnRegion:
            expectedList.add(Boolean.TRUE);
            expectedList.add(Boolean.TRUE);
            assertEquals(expectedList, FunctionService.onRegion(custRegion)
                .withArgs(new Object[] { store1Id, store2Id })
                .execute(TXFunction.id).getResult());
            assertEquals(expectedCustomer, custRegion.get(expectedCustId));
            assertEquals(expectedOrder, orderRegion.get(expectedOrderId));
            assertNull(refRegion.get(expectedCustId));
            tx = mgr.internalSuspend();
            assertNull(custRegion.get(expectedCustId));
            assertNull(orderRegion.get(expectedOrderId));
            assertNull(refRegion.get(expectedCustId));
            mgr.resume(tx);
            // add another result for onRegions() call below
            expectedList.add(Boolean.TRUE);
            break;
          case OnMember:
            expectedList.add(Boolean.TRUE);
            expectedList.add(Boolean.TRUE);
            expectedList.add(Boolean.TRUE);
            assertEquals(expectedList, FunctionService.onMembers(system)
                .withArgs(new Object[] { store1Id, store2Id })
                .execute(TXFunction.id).getResult());
            tx = mgr.internalSuspend();
            assertNull(custRegion.get(expectedCustId));
            assertNull(orderRegion.get(expectedOrderId));
            assertNull(refRegion.get(expectedCustId));
            mgr.resume(tx);
            assertEquals(expectedCustomer, custRegion.get(expectedCustId));
            assertEquals(expectedOrder, orderRegion.get(expectedOrderId));
            assertEquals(expectedCustomer, refRegion.get(expectedCustId));
            refRegion.destroy(expectedCustId);
            break;
        }
        custRegion.destroy(expectedCustId);
        orderRegion.destroy(expectedOrderId);
        GemFireCacheImpl.getInstance().getLogger().warning("TX SUSPENDO:" + tx);
        assertNull(custRegion.get(expectedCustId));
        assertNull(orderRegion.get(expectedOrderId));
        assertNull(refRegion.get(expectedCustId));
        assertEquals(expectedList, InternalFunctionService.onRegions(regions)
            .withArgs(new Object[] { store1Id, store2Id })
            .execute(TXFunction.id).getResult());
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        assertEquals(expectedOrder, orderRegion.get(expectedOrderId));
        assertEquals(expectedCustomer, refRegion.get(expectedCustId));

        tx = mgr.internalSuspend();
        assertNull(custRegion.get(expectedCustId));
        assertNull(orderRegion.get(expectedOrderId));
        assertNull(refRegion.get(expectedCustId));
        mgr.resume(tx);
        return tx.getTransactionId();
      }
    });

    Integer txOnDatastore1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    Integer txOnDatastore2 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    assertEquals(2, txOnDatastore1 + txOnDatastore2);

    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        Region<Object, Object> orderRegion = getCache().getRegion(ORDER);
        Region<Object, Object> refRegion = getCache().getRegion(D_REFERENCE);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.getHostedTXState(txId);
        // force expected transaction
        TXStateInterface txOrig = mgr.getTXState();
        mgr.setTXState(tx);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        assertEquals(expectedOrder, orderRegion.get(expectedOrderId));
        assertEquals(expectedCustomer, refRegion.get(expectedCustId));
        mgr.internalSuspend();
        assertNull(custRegion.get(expectedCustId));
        assertNull(orderRegion.get(expectedOrderId));
        assertNull(refRegion.get(expectedCustId));
        mgr.setTXState(txOrig);
        return null;
      }
    });

    txOnDatastore1 = (Integer)datastore1.invoke(getNumberOfTXInProgress);
    txOnDatastore2 = (Integer)datastore2.invoke(getNumberOfTXInProgress);
    assertEquals(2, txOnDatastore1 + txOnDatastore2);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.commit();
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        Region<Object, Object> orderRegion = getCache().getRegion(ORDER);
        Region<Object, Object> refRegion = getCache().getRegion(D_REFERENCE);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        assertEquals(expectedOrder, orderRegion.get(expectedOrderId));
        assertEquals(expectedCustomer, refRegion.get(expectedCustId));
        // a put that goes to both the nodes will ensure commit complete for
        // verifyNoTxState check below
        refRegion.put(expectedCustId, expectedCustomerUpdate);
        assertEquals(expectedCustomerUpdate, refRegion.get(expectedCustId));
        return null;
      }
    });

    accessor.invoke(verifyNoTxState);
    datastore1.invoke(verifyNoTxState);
    datastore2.invoke(verifyNoTxState);

    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        Region<Object, Object> orderRegion = getCache().getRegion(ORDER);
        Region<Object, Object> refRegion = getCache().getRegion(D_REFERENCE);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        assertEquals(expectedOrder, orderRegion.get(expectedOrderId));
        assertEquals(expectedCustomerUpdate, refRegion.get(expectedCustId));
        custRegion.put(expectedCustId, expectedCustomerUpdate);
        assertEquals(expectedCustomerUpdate, custRegion.get(expectedCustId));
        return null;
      }
    });

    // now test for conflict
    final TXId txId1 = (TXId)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        PartitionedRegion custRegion = (PartitionedRegion)getCache().getRegion(
            CUSTOMER);
        final Set<Object> filter = new HashSet<Object>();
        filter.add(expectedCustId);
        switch (e) {
          case OnRegion:
            FunctionService.onRegion(custRegion).withFilter(filter)
                .execute(TXFunction.id).getResult();
            break;
          case OnMember:
            DistributedMember owner = custRegion.getOwnerForKey(custRegion
                .getKeyInfo(expectedCustId));
            FunctionService.onMember(system, owner).execute(TXFunction.id)
                .getResult();
            break;
        }
        /* TODO: TX: enable non-TX, TX conflict detection once implemented
        TXStateInterface tx = mgr.internalSuspend();
        custRegion.put(expectedCustId, new Customer("Cust6", "updated6"));
        mgr.resume(tx);
        try {
          mgr.commit();
          fail("expected conflict not thrown");
        } catch (ConflictException expected) {
        }
        */
        return mgr.getTransactionId();
      }
    });
    final ExpectedException expected = addExpectedException(
        ConflictException.class.getName(), datastore1);
    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        PartitionedRegion custRegion = (PartitionedRegion)getCache().getRegion(
            CUSTOMER);
        final Set<Object> filter = new HashSet<Object>();
        filter.add(expectedCustId);
        try {
          switch (e) {
            case OnRegion:
              FunctionService.onRegion(custRegion).withFilter(filter)
                  .execute(TXFunction.id).getResult();
              break;
            case OnMember:
              DistributedMember owner = custRegion.getOwnerForKey(custRegion
                  .getKeyInfo(expectedCustId));
              FunctionService.onMember(system, owner).execute(TXFunction.id)
                  .getResult();
              break;
          }
          fail("did not get expected exception");
        } catch (FunctionException fe) {
          if (!(fe.getCause() instanceof ConflictException)) {
            throw fe;
          }
          // got expected exception
          mgr.rollback();
        }
        mgr.begin();
        try {
          switch (e) {
            case OnRegion:
              FunctionService.onRegion(custRegion).execute(TXFunction.id)
                  .getResult();
              break;
            case OnMember:
              FunctionService.onMembers(system).execute(TXFunction.id)
                  .getResult();
              break;
          }
          fail("did not get expected exception");
        } catch (FunctionException fe) {
          if (!(fe.getCause() instanceof ConflictException)) {
            throw fe;
          }
          // got expected exception
          mgr.rollback();
        }
        mgr.begin();
        try {
          custRegion.put(expectedCustId, new Customer("Cust6", "updated6"));
          fail("did not get expected exception");
        } catch (ConflictException ce) {
          // got expected exception
          mgr.rollback();
        }
        assertEquals(expectedCustomerUpdate, custRegion.get(expectedCustId));
        // check in the other active transaction
        // force other active transaction
        TXStateInterface tx = mgr.getHostedTXState(txId1);
        TXStateInterface txOrig = mgr.getTXState();
        mgr.setTXState(tx);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        mgr.setTXState(txOrig);
        return null;
      }
    });
    expected.remove();
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        assertEquals(expectedCustomerUpdate, custRegion.get(expectedCustId));
        // check in the other active transaction
        // force other active transaction
        TXStateInterface tx = mgr.getHostedTXState(txId1);
        TXStateInterface txOrig = mgr.getTXState();
        mgr.setTXState(tx);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        mgr.setTXState(txOrig);
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        TXStateInterface tx = mgr.internalSuspend();
        assertEquals(expectedCustomerUpdate, custRegion.get(expectedCustId));
        mgr.resume(tx);
        mgr.commit();
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        custRegion.destroy(expectedCustId);
        assertNull(custRegion.get(expectedCustId));
        return null;
      }
    });

    accessor.invoke(waitForPendingCommit);
    accessor.invoke(verifyNoTxState);
    datastore1.invoke(verifyNoTxState);
    datastore2.invoke(verifyNoTxState);

    // now try with filter and random firing/verifying nodes
    VM execVM1, execVM2;
    switch (PartitionedRegion.rand.nextInt(2)) {
      case 0: execVM1 = accessor; execVM2 = datastore1; break;
      case 1: execVM1 = datastore1; execVM2 = datastore2; break;
      default: execVM1 = datastore2; execVM2 = accessor; break;
    }
    final TXId txId2 = (TXId)execVM1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        PartitionedRegion custRegion = (PartitionedRegion)getCache().getRegion(
            CUSTOMER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();

        final Set<Object> filter = new HashSet<Object>();
        filter.add(expectedCustId);
        switch (e) {
          case OnRegion:
            FunctionService.onRegion(custRegion).withFilter(filter)
                .execute(TXFunction.id).getResult();
            break;
          case OnMember:
            DistributedMember owner = custRegion.getOwnerForKey(custRegion
                .getKeyInfo(expectedCustId));
            FunctionService.onMember(system, owner).execute(TXFunction.id)
                .getResult();
            break;
        }
        TXStateInterface tx = mgr.internalSuspend();
        assertNull(custRegion.get(expectedCustId));
        mgr.resume(tx);
        return tx.getTransactionId();
      }
    });

    txOnDatastore1 = (Integer)datastore1.invoke(getNumberOfTXInProgress);
    txOnDatastore2 = (Integer)datastore2.invoke(getNumberOfTXInProgress);
    assertEquals(2, txOnDatastore1 + txOnDatastore2);

    execVM2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.getHostedTXState(txId2);
        // force expected transaction
        TXStateInterface txOrig = mgr.getTXState();
        mgr.setTXState(tx);
        tx = mgr.internalSuspend();
        assertNull(custRegion.get(expectedCustId));
        mgr.resume(tx);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        mgr.setTXState(txOrig);
        return null;
      }
    });

    execVM1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.commit();
        TXManagerImpl.waitForPendingCommitForTest();
        return null;
      }
    });

    execVM2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        custRegion.put(expectedCustId, expectedCustomerUpdate);
        assertEquals(expectedCustomerUpdate, custRegion.get(expectedCustId));
        return null;
      }
    });
    execVM1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<Object, Object> custRegion = getCache().getRegion(CUSTOMER);
        assertEquals(expectedCustomerUpdate, custRegion.get(expectedCustId));
        return null;
      }
    });
  }

  /**
   * Still need to update this test for the new TX model.
   */
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testNestedTxFunction() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);
    
    class NestedTxFunction2 extends FunctionAdapter {
      static final String id = "NestedTXFunction2";
      @Override
      public void execute(FunctionContext context) {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertNotNull(mgr.getTXState());
        try {
          mgr.commit();
          fail("expected exception not thrown");
        } catch (UnsupportedOperationInTransactionException e) {
        }
        context.getResultSender().lastResult(Boolean.TRUE);
      }
      @Override
      public String getId() {
        return id;
      }
    }
    class NestedTxFunction extends FunctionAdapter {
      static final String id = "NestedTXFunction";
      @Override
      public void execute(FunctionContext context) {
        Region r = null;
        if (context instanceof RegionFunctionContext) {
          r = PartitionRegionHelper.getLocalDataForContext((RegionFunctionContext)context);
        } else {
          r = getCache().getRegion(CUSTOMER);
        }
        assertNotNull(getGemfireCache().getTxManager().getTXState());
        PartitionedRegion pr = (PartitionedRegion)getCache().getRegion(CUSTOMER);
        Set filter = new HashSet();
        filter.add(expectedCustId);
        getLogWriter().info("SWAP:inside NestedTxFunc calling func2:");
        r.put(expectedCustId, expectedCustomer);
        FunctionService.onRegion(pr).withFilter(filter).execute(new NestedTxFunction2()).getResult();
        assertNotNull(getGemfireCache().getTxManager().getTXState());
        context.getResultSender().lastResult(Boolean.TRUE);
      }
      @Override
      public boolean optimizeForWrite() {
        return true;
      }
      @Override
      public String getId() {
        return id;
      }
    }
    
    
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        PartitionedRegion pr = (PartitionedRegion)getCache().getRegion(CUSTOMER);
        mgr.begin();
        Set filter = new HashSet();
        filter.add(expectedCustId);
        FunctionService.onRegion(pr).withFilter(filter).execute(new NestedTxFunction()).getResult();
        assertNotNull(getGemfireCache().getTxManager().getTXState());
        mgr.commit();
        assertEquals(expectedCustomer, pr.get(expectedCustId));
        return null;
      }
    });
  }

  public void Bug44146_testDRFunctionExecution() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    
    class CreateDR extends SerializableCallable {
      private final boolean isAccessor;
      public CreateDR(boolean isAccessor) {
        this.isAccessor = isAccessor;
      }
      public Object call() throws Exception {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(isAccessor? DataPolicy.EMPTY : DataPolicy.REPLICATE);
        af.setScope(Scope.DISTRIBUTED_ACK);
        af.setConcurrencyChecksEnabled(getConcurrencyChecksEnabled());
        getCache().createRegion(CUSTOMER, af.create());
        if (isAccessor) {
          Region custRegion = getCache().getRegion(CUSTOMER);
          for (int i=0; i<5; i++) {
            CustId custId = new CustId(i);
            Customer customer = new Customer("customer"+i, "address"+i);
            custRegion.put(custId, customer);
          }
        }
        return null;
      }
    }

    datastore1.invoke(new CreateDR(false));
    datastore2.invoke(new CreateDR(false));
    accessor.invoke(new CreateDR(true));
    
    SerializableCallable registerFunction = new SerializableCallable() {
      public Object call() throws Exception {
        FunctionService.registerFunction(new TXFunction());
        return null;
      }
    };

    accessor.invoke(registerFunction);
    datastore1.invoke(registerFunction);
    datastore2.invoke(registerFunction);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        FunctionService.onRegion(custRegion).execute(TXFunction.id).getResult();
        assertNotNull(mgr.getTXState());
        TXStateInterface tx= mgr.internalSuspend();
        assertNull(mgr.getTXState());
        getGemfireCache().getLogger().fine("SWAP:callingget");
        assertNull("expected null but was:" + custRegion.get(expectedCustId),
            custRegion.get(expectedCustId));
        mgr.resume(tx);
        mgr.commit();
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        return null;
      }
    });

    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        final Region custRegion = getCache().getRegion(CUSTOMER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        FunctionService.onRegion(custRegion).execute(new FunctionAdapter() {
          @Override
          public String getId() {
            return "LocalDS";
          }
          @Override
          public void execute(FunctionContext context) {
            assertNotNull(getGemfireCache().getTxManager().getTXState());
            custRegion.destroy(expectedCustId);
            context.getResultSender().lastResult(Boolean.TRUE);
          }
        }).getResult();
        TXStateInterface tx = mgr.internalSuspend();
        assertEquals(custRegion.get(expectedCustId), expectedCustomer);
        mgr.resume(tx);
        mgr.commit();
        assertNull(custRegion.get(expectedCustId));
        return null;
      }
    });
  }

  /**
   * Still need to update this test for the new TX model.
   */
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTxFunctionWithOtherOps() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);

    SerializableCallable registerFunction = new SerializableCallable() {
      public Object call() throws Exception {
        FunctionService.registerFunction(new TXFunction());
        return null;
      }
    };

    accessor.invoke(registerFunction);
    datastore1.invoke(registerFunction);
    datastore2.invoke(registerFunction);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        try {
          FunctionService.onRegion(custRegion).execute(TXFunction.id).getResult();
          fail("Expected exception not thrown");
        } catch (TransactionException expected) {
        }
        Set filter = new HashSet();
        filter.add(expectedCustId);
        FunctionService.onRegion(custRegion).withFilter(filter).execute(TXFunction.id).getResult();
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        TXStateInterface tx = mgr.internalSuspend();
        assertNull(custRegion.get(expectedCustId));
        mgr.resume(tx);
        return null;
      }
    });

    final Integer txOnDatastore1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    final Integer txOnDatastore2 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    assertEquals(2, txOnDatastore1 + txOnDatastore2);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.commit();
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        custRegion.destroy(expectedCustId);
        return null;
      }
    });
    //test onMembers
    SerializableCallable getMember = new SerializableCallable() {
      public Object call() throws Exception {
        return getGemfireCache().getMyId();
      }
    };
    final InternalDistributedMember ds1 = (InternalDistributedMember)datastore1.invoke(getMember);
    final InternalDistributedMember ds2 = (InternalDistributedMember)datastore2.invoke(getMember);
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        PartitionedRegion pr = (PartitionedRegion)getCache().getRegion(CUSTOMER);
        //get owner for expectedKey
        DistributedMember owner = pr.getOwnerForKey(pr.getKeyInfo(expectedCustId));
        //get key on datastore1
        CustId keyOnOwner = null;
        keyOnOwner = getKeyOnMember(owner, pr);
        
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        //bootstrap tx on owner
        pr.get(keyOnOwner);
        Set<DistributedMember> members = new HashSet<DistributedMember>();
        members.add(ds1);members.add(ds2);
        try {
          FunctionService.onMembers(system, members).execute(TXFunction.id).getResult();
          fail("expected exception not thrown");
        } catch (TransactionException expected) {
        }
        FunctionService.onMember(system, owner).execute(TXFunction.id).getResult();
        assertEquals(expectedCustomer, pr.get(expectedCustId));
        TXStateInterface tx = mgr.internalSuspend();
        assertNull(pr.get(expectedCustId));
        mgr.resume(tx);
        return null;
      }
    });
    final Integer txOnDatastore1_1 = (Integer)datastore1
        .invoke(getNumberOfTXInProgress);
    final Integer txOnDatastore2_1 = (Integer)datastore2
        .invoke(getNumberOfTXInProgress);
    assertEquals(2, txOnDatastore1_1 + txOnDatastore2_1);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.commit();
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        custRegion.destroy(expectedCustId);
        return null;
      }
    });
    //test function execution on data store
    final DistributedMember owner = (DistributedMember)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        PartitionedRegion pr = (PartitionedRegion)getCache().getRegion(CUSTOMER);
        return pr.getOwnerForKey(pr.getKeyInfo(expectedCustId));
      }
    });

    SerializableCallable testFnOnDs = new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        PartitionedRegion pr = (PartitionedRegion)getCache().getRegion(CUSTOMER);
        CustId keyOnDs = getKeyOnMember(pr.getMyId(), pr);
        mgr.begin();
        pr.get(keyOnDs);
        Set filter  = new HashSet();
        filter.add(keyOnDs);
        FunctionService.onRegion(pr).withFilter(filter).execute(TXFunction.id).getResult();
        assertEquals(expectedCustomer, pr.get(expectedCustId));
        TXStateInterface tx = mgr.internalSuspend();
        assertNull(pr.get(expectedCustId));
        mgr.resume(tx);
        return null;
      }
    };
    SerializableCallable closeTx = new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.commit();
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        custRegion.destroy(expectedCustId);
        return null;
      }
    };

    if (owner.equals(ds1)) {
      datastore1.invoke(testFnOnDs);
      final Integer txOnDatastore1_2 = (Integer)datastore1
          .invoke(getNumberOfTXInProgress);
      final Integer txOnDatastore2_2 = (Integer)datastore2
          .invoke(getNumberOfTXInProgress);
      // ds1 has a local transaction, not remote
      assertEquals(0, txOnDatastore1_2 + txOnDatastore2_2);
      datastore1.invoke(closeTx);
    }
    else {
      datastore2.invoke(testFnOnDs);
      final Integer txOnDatastore1_2 = (Integer)datastore1
          .invoke(getNumberOfTXInProgress);
      final Integer txOnDatastore2_2 = (Integer)datastore2
          .invoke(getNumberOfTXInProgress);
      // ds1 has a local transaction, not remote
      assertEquals(0, txOnDatastore1_2 + txOnDatastore2_2);
      datastore2.invoke(closeTx);
    }

    //test that function is rejected if function target is not same as txState target
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        PartitionedRegion pr = (PartitionedRegion)getCache().getRegion(CUSTOMER);
        CustId keyOnDs1 = getKeyOnMember(ds1, pr);
        CustId keyOnDs2 = getKeyOnMember(ds2, pr);
        mgr.begin();
        pr.get(keyOnDs1);//bootstrap txState
        Set filter = new HashSet();
        filter.add(keyOnDs2);
        try {
          FunctionService.onRegion(pr).withFilter(filter).execute(TXFunction.id).getResult();
          fail("expected Exception not thrown");
        } catch (TransactionDataRebalancedException expected) {
        }
        FunctionService.onMember(system, ds2).execute(TXFunction.id).getResult();
        mgr.commit();
        return null;
      }
    });
  }

  /**
   * @return first key found on the given member
   */
  CustId getKeyOnMember(final DistributedMember owner,
      PartitionedRegion pr) {
    CustId retVal = null;
    for (int i=0; i<5; i++) {
      CustId custId = new CustId(i);
      DistributedMember member = pr.getOwnerForKey(pr.getKeyInfo(custId));
      if (member.equals(owner)) {
        retVal = custId;
        break;
      }
    }
    return retVal;
  }

  protected Set<Customer> getCustomerSet(int size) {
    Set<Customer> expectedSet = new HashSet<Customer>();
    for (int i=0; i<size; i++) {
      expectedSet.add(new Customer("customer"+i, "address"+i));
    }
    return expectedSet;
  }

  Set<CustId> getCustIdSet(int size) {
    Set<CustId> expectedSet = new HashSet<CustId>();
    for (int i=0; i<size; i++) {
      expectedSet.add(new CustId(i));
    }
    return expectedSet;
  }

  public void Bug44146_testRemoteJTACommit() {
    doRemoteJTA(true);
  }

  public void Bug44146_testRemoteJTARollback() {
    doRemoteJTA(false);
  }

  private void doRemoteJTA(final boolean isCommit) {
    Host host = Host.getHost(0);
    VM acc = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(acc, datastore, 0);
    VM accessor = getVMForTransactions(acc, datastore);

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        getGemfireCache().getTxManager().addListener(new TestTxListener(false));
        return null;
      }
    });
    final CustId expectedCustId = new CustId(6);
    final Customer expectedCustomer = new Customer("customer6", "address6");
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        getGemfireCache().getTxManager().addListener(new TestTxListener(true));
        Region custRegion = getCache().getRegion(CUSTOMER);
        Context ctx = getCache().getJNDIContext();
        UserTransaction tx = (UserTransaction)ctx.lookup("java:/UserTransaction");
        assertEquals(Status.STATUS_NO_TRANSACTION, tx.getStatus());
        tx.begin();
        assertEquals(Status.STATUS_ACTIVE, tx.getStatus());
        custRegion.put(expectedCustId, expectedCustomer);
        assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        return null;
      }
    });
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        assertNull(custRegion.get(expectedCustId));
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region custRegion = getCache().getRegion(CUSTOMER);
        Context ctx = getCache().getJNDIContext();
        UserTransaction tx = (UserTransaction)ctx.lookup("java:/UserTransaction");
        if (isCommit) {
          tx.commit();
          assertEquals(expectedCustomer, custRegion.get(expectedCustId));
        } else {
          tx.rollback();
          assertNull(custRegion.get(expectedCustId));
        }
        return null;
      }
    });
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TestTxListener l = (TestTxListener)getGemfireCache().getTxManager()
            .getListener();
        assertTrue(l.isListenerInvoked());
        return null;
      }
    });
  }

  public void Bug44146_testOriginRemoteIsTrueForRemoteReplicatedRegions() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);

    class OriginRemoteRRWriter extends CacheWriterAdapter {
      int fireC =0 ;
      int fireD =0 ;
      int fireU =0 ;
      @Override
      public void beforeCreate(EntryEvent event)
      throws CacheWriterException {
        if (!event.isOriginRemote()) {
          throw new CacheWriterException(
              "SUP?? This CREATE is supposed to be isOriginRemote");
        }
        fireC++;
      }

      @Override
      public void beforeDestroy(EntryEvent event) throws CacheWriterException {
        getGemfireCache().getLoggerI18n().fine(
            "SWAP:writer:createEvent:" + event);
        if (!event.isOriginRemote()) {
          throw new CacheWriterException(
              "SUP?? This DESTROY is supposed to be isOriginRemote");
        }
        fireD++;
      }

      @Override
      public void beforeUpdate(EntryEvent event) throws CacheWriterException {
        if (!event.isOriginRemote()) {
          throw new CacheWriterException(
              "SUP?? This UPDATE is supposed to be isOriginRemote");
        }
        fireU++;
      }
    }

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region refRegion = getCache().getRegion(D_REFERENCE);
        refRegion.getAttributesMutator().setCacheWriter(new OriginRemoteRRWriter());
        return null;
      }
    });

    accessor.invoke(new DoOpsInTX(OP.PUT));

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.internalSuspend();
        assertNotNull(tx);
        mgr.resume(tx);
        mgr.commit();
        return null;
      }
    });

    accessor.invoke(new DoOpsInTX(OP.DESTROY));

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.internalSuspend();
        assertNotNull(tx);
        mgr.resume(tx);
        mgr.commit();
        return null;
      }
    });

    accessor.invoke(new DoOpsInTX(OP.PUT));

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.internalSuspend();
        assertNotNull(tx);
        mgr.resume(tx);
        mgr.commit();
        TXManagerImpl.waitForPendingCommitForTest();
        return null;
      }
    });

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region refRegion = getCache().getRegion(D_REFERENCE);
        OriginRemoteRRWriter w = (OriginRemoteRRWriter)refRegion
            .getAttributes().getCacheWriter();
        assertEquals(1, w.fireC);
        assertEquals(1, w.fireD);
        assertEquals(1, w.fireU);
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region refRegion = getCache().getRegion(D_REFERENCE);
        assertNull(refRegion.getAttributes().getCacheWriter());
        return null;
      }
    });
  }

  public void Bug44146_testRemoteCreateInReplicatedRegion() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);

    accessor.invoke(new DoOpsInTX(OP.PUT));

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region refRegion = getCache().getRegion(D_REFERENCE);
        refRegion.create("sup","dawg");
        return null;
      }
    });

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        TXStateInterface tx = mgr.internalSuspend();
        assertNotNull(tx);
        mgr.resume(tx);
        mgr.commit();
        return null;
      }
    });

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region refRegion = getCache().getRegion(D_REFERENCE);
        assertEquals("dawg",refRegion.get("sup"));
        return null;
      }
    });
  }

  public void Bug44146_testRemoteTxCleanupOnCrash() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);

    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(CUSTOMER);
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        cust.put(new CustId(6), new Customer("customer6", "address6"));
        return null;
      }
    });
    final InternalDistributedMember member = (InternalDistributedMember)accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        return getGemfireCache().getMyId();
      }
    });
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        assertEquals(1, mgr.hostedTransactionsInProgressForTest());
        mgr.memberDeparted(member, true);
        assertEquals(0, mgr.hostedTransactionsInProgressForTest());
        return null;
      }
    });
  }

  public void Bug44146_testNonColocatedPutAll() {
    doNonColocatedbulkOp(OP.PUTALL);
  }

  public void Bug44146_testNonColocatedGetAll() {
    doNonColocatedbulkOp(OP.GETALL);
  }

  private void doNonColocatedbulkOp(final OP op) {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);

    // TODO: TX: putAll does not work on remote nodes for replicated regions;
    // fix that and add to the tests for that
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Map<CustId, Customer> custMap = new HashMap<CustId, Customer>();
        Map<CustId, Customer> custGetMap = new HashMap<CustId, Customer>();
        Map<CustId, Customer> custGetResMap = new HashMap<CustId, Customer>();
        Map<CustId, Customer> custOrigMap = new HashMap<CustId, Customer>();
        Map<CustId, Customer> custOrigGetMap = new HashMap<CustId, Customer>();
        for (int i = 0; i < 10; i++) {
          CustId cId = new CustId(i);
          Customer c = new Customer("name" + i, "addr" + i);
          custMap.put(cId, c);
        }
        for (int i = 3; i < 10; i++) {
          CustId cId = new CustId(i);
          Customer c = new Customer("name" + i, "addr" + i);
          custGetMap.put(cId, c);
        }
        for (int i = 0; i < 5; i++) {
          CustId cId = new CustId(i);
          Customer c = new Customer("customer" + i, "address" + i);
          custOrigMap.put(cId, c);
        }
        for (int i = 3; i < 5; i++) {
          CustId cId = new CustId(i);
          Customer c = new Customer("customer" + i, "address" + i);
          custOrigGetMap.put(cId, c);
        }
        custGetResMap.putAll(custOrigMap);
        custGetResMap.putAll(custGetMap);
        final GemFireCacheImpl cache = getGemfireCache();
        final TXManagerImpl txMgr = cache.getTxManager();
        txMgr.begin();
        final Region<CustId, Customer> r = cache.getRegion(CUSTOMER);
        final Region<CustId, Customer> rr = cache.getRegion(D_REFERENCE);
        switch (op) {
          case PUTALL:
            r.putAll(custMap);
            rr.putAll(custMap);
            assertMapEquals(custMap, r.getAll(custMap.keySet()));
            assertMapEquals(custMap, rr.getAll(custMap.keySet()));
            break;
          case GETALL:
            r.putAll(custGetMap);
            rr.putAll(custGetMap);
            assertMapEquals(custGetResMap, r.getAll(custMap.keySet()));
            assertMapEquals(custGetMap, r.getAll(custGetMap.keySet()));
            assertMapEquals(custGetResMap, rr.getAll(custMap.keySet()));
            assertMapEquals(custGetMap, rr.getAll(custGetMap.keySet()));
            break;
          default:
            break;
        }
        // suspend and check no entries in region
        TXStateInterface tx = txMgr.internalSuspend();
        switch (op) {
          case PUTALL:
            assertMapEquals(custOrigMap, r.getAll(custMap.keySet()));
            assertMapEquals(custOrigMap, rr.getAll(custMap.keySet()));
            break;
          case GETALL:
            assertMapEquals(custOrigGetMap, r.getAll(custGetMap.keySet()));
            assertMapEquals(custOrigMap, r.getAll(custMap.keySet()));
            assertMapEquals(custOrigGetMap, rr.getAll(custGetMap.keySet()));
            assertMapEquals(custOrigMap, rr.getAll(custMap.keySet()));
            break;
          default:
            break;
        }
        // check no entries in region after abort
        txMgr.resume(tx);
        txMgr.rollback();
        switch (op) {
          case PUTALL:
            assertMapEquals(custOrigMap, r.getAll(custMap.keySet()));
            assertMapEquals(custOrigMap, rr.getAll(custMap.keySet()));
            break;
          case GETALL:
            assertMapEquals(custOrigGetMap, r.getAll(custGetMap.keySet()));
            assertMapEquals(custOrigMap, r.getAll(custMap.keySet()));
            assertMapEquals(custOrigGetMap, rr.getAll(custGetMap.keySet()));
            assertMapEquals(custOrigMap, rr.getAll(custMap.keySet()));
            break;
          default:
            break;
        }
        // do again and check in region after commit
        txMgr.begin();
        switch (op) {
          case PUTALL:
            r.putAll(custMap);
            rr.putAll(custMap);
            assertMapEquals(custMap, r.getAll(custMap.keySet()));
            assertMapEquals(custMap, rr.getAll(custMap.keySet()));
            break;
          case GETALL:
            r.putAll(custGetMap);
            rr.putAll(custGetMap);
            assertMapEquals(custGetResMap, r.getAll(custMap.keySet()));
            assertMapEquals(custGetMap, r.getAll(custGetMap.keySet()));
            assertMapEquals(custGetResMap, rr.getAll(custMap.keySet()));
            assertMapEquals(custGetMap, rr.getAll(custGetMap.keySet()));
            break;
          default:
            break;
        }
        txMgr.commit();
        switch (op) {
          case PUTALL:
            assertMapEquals(custMap, r.getAll(custMap.keySet()));
            assertMapEquals(custMap, rr.getAll(custMap.keySet()));
            break;
          case GETALL:
            assertMapEquals(custGetResMap, r.getAll(custMap.keySet()));
            assertMapEquals(custGetMap, r.getAll(custGetMap.keySet()));
            assertMapEquals(custGetResMap, rr.getAll(custMap.keySet()));
            assertMapEquals(custGetMap, rr.getAll(custGetMap.keySet()));
            break;
          default:
            break;
        }
        return null;
      }
    });
  }

  public static void assertMapEquals(final Map<?, ?> expected,
      final Map<?, ?> result) {
    // check expected entries are same
    for (Map.Entry<?, ?> entry : expected.entrySet()) {
      assertEquals(entry.getValue(), result.get(entry.getKey()));
    }
    // check null values for any remaining entries
    for (Map.Entry<?, ?> entry : result.entrySet()) {
      if (!expected.containsKey(entry.getKey())) {
        assertNull("expected no value for key: " + entry.getKey()
            + ", result: " + result, entry.getValue());
      }
    }
  }

  public void Bug44146_testDestroyCreateConflation() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    initAccessorAndDataStore(accessor, datastore, 0);

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(D_REFERENCE);
        cust.put("meow","this is a meow, deal with it");
        cust.getAttributesMutator().addCacheListener(new OneUpdateCacheListener());
        cust.getAttributesMutator().setCacheWriter(new OneDestroyAndThenOneCreateCacheWriter());

        return null;
      }
    });
    
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(D_REFERENCE);
        cust.getAttributesMutator().addCacheListener(new OneUpdateCacheListener());
        return null;
      }
    });

    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        CacheTransactionManager mgr = getGemfireCache().getTxManager();
        mgr.begin();
        Region cust = getCache().getRegion(D_REFERENCE);
        cust.destroy("meow");
        cust.create("meow","this is the new meow, not the old meow");
        mgr.commit();
        TXManagerImpl.waitForPendingCommitForTest();
        return null;
      }
    });
    
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(D_REFERENCE);
        OneUpdateCacheListener rat = (OneUpdateCacheListener)cust.getAttributes().getCacheListener();
        if(!rat.getSuccess()) {
          fail("The OneUpdateCacheListener didnt get an update");
        } 
        return null;
      }
    });
    
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region cust = getCache().getRegion(D_REFERENCE);
        OneDestroyAndThenOneCreateCacheWriter wri = (OneDestroyAndThenOneCreateCacheWriter)cust.getAttributes().getCacheWriter();
        wri.checkSuccess();
        return null;
      }
    });
  }

  class OneUpdateCacheListener extends CacheListenerAdapter {
    boolean success = false;
    
    public boolean getSuccess() {
      return success;
    }
    
    @Override
    public void afterCreate(EntryEvent event) {
      fail("create not expected");
    }
    @Override
    public void afterUpdate(EntryEvent event) {
      if(!success) {
        System.out.println("WE WIN!");
        success = true;
      } else {
        fail("Should have only had one update");
      }
    }
    @Override
    public void afterDestroy(EntryEvent event) {
      fail("destroy not expected");
    }
    @Override
    public void afterInvalidate(EntryEvent event) {
      fail("invalidate not expected");
    }
  }
  
  class OneDestroyAndThenOneCreateCacheWriter extends CacheWriterAdapter {
    private boolean oneDestroy;
    private boolean oneCreate;
    
    public void checkSuccess() throws Exception {
      if(oneDestroy && oneCreate) {
        // chill
      } else {
        fail("Didn't get both events. oneDestroy="+oneDestroy+" oneCreate="+oneCreate);
      }
    }

    @Override
    public void beforeCreate(EntryEvent event) throws CacheWriterException {
     if(!oneDestroy) {
       fail("destroy should have arrived in writer before create");
     } else {
       if(oneCreate) {
         fail("more than one create detected! expecting destroy then create");
       } else {
         oneCreate = true;
       }
     }
    }

    @Override
    public void beforeUpdate(EntryEvent event) throws CacheWriterException {
        fail("update not expected");
    }

    @Override
    public void beforeDestroy(EntryEvent event) throws CacheWriterException {
      if(oneDestroy) {
        fail("only one destroy expected");
      } else {
        if(oneCreate) {
          fail("destroy is supposed to precede create");
        } else {
          oneDestroy = true;
        }
      }
    }
  }

  protected Integer startServer(VM vm) {
    return (Integer) vm.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        int port = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
        CacheServer s = getCache().addCacheServer();
        s.setPort(port);
        s.start();
        return port;
      }
    });
  }
  protected void createClientRegion(VM vm, final int port, final boolean isEmpty, final boolean ri,final boolean CQ) {
    vm.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        ClientCacheFactory ccf = new ClientCacheFactory();
        ccf.addPoolServer("localhost"/*getServerHostName(Host.getHost(0))*/, port);
        ccf.setPoolSubscriptionEnabled(true);
        ccf.set("log-level", getDUnitLogLevel());
        ClientCache cCache = getClientCache(ccf);
        ClientRegionFactory<Integer, String> crf = cCache
            .createClientRegionFactory(isEmpty ? ClientRegionShortcut.PROXY
                : ClientRegionShortcut.CACHING_PROXY);
        crf.setConcurrencyChecksEnabled(getConcurrencyChecksEnabled());
        crf.addCacheListener(new ClientListener());
        Region r = crf.create(D_REFERENCE);
        Region cust = crf.create(CUSTOMER);
        Region order = crf.create(ORDER);
        if (ri) {
          r.registerInterestRegex(".*");
          cust.registerInterestRegex(".*");
          order.registerInterestRegex(".*");
        }
        if(CQ) {
          CqAttributesFactory cqf = new CqAttributesFactory();
          cqf.addCqListener(new ClientCQListener());
          CqAttributes ca = cqf.create();
          cCache.getQueryService().newCq("SELECT * FROM "+cust.getFullPath(), ca).execute();
        }
        return null;
      }
    });
  }

  protected class ClientCQListener implements CqListener {

    boolean invoked = false;
    public void onError(CqEvent aCqEvent) {
      // TODO Auto-generated method stub
      
    }

    public void onEvent(CqEvent aCqEvent) {
      // TODO Auto-generated method stub
      invoked =true;
      
    }

    public void close() {
      // TODO Auto-generated method stub
      
    }
    
  }
  
protected static class ClientListener extends CacheListenerAdapter {
    boolean invoked = false;
    int invokeCount = 0;
    int invalidateCount = 0;
    int putCount = 0;
    boolean putAllOp = false;
    boolean isOriginRemote = false;
    int creates;
    int updates;
    
    @Override
    public void afterCreate(EntryEvent event) {
      event.getRegion().getCache().getLogger().warning("ZZZ AFTER CREATE:"+event.getKey());
      invoked = true;
      invokeCount++;
      putCount++;
      creates++;
      event.getRegion().getCache().getLogger().warning("ZZZ AFTER CREATE:"+event.getKey()+" isPutAll:"+event.getOperation().isPutAll()+" op:"+event.getOperation());
      putAllOp = event.getOperation().isPutAll();
      isOriginRemote = event.isOriginRemote();
    }
    @Override
    public void afterUpdate(EntryEvent event) {
    	event.getRegion().getCache().getLogger().warning("ZZZ AFTER UPDATE:"+event.getKey()+" isPutAll:"+event.getOperation().isPutAll()+" op:"+event.getOperation());
        putAllOp = event.getOperation().isPutAll();
      invoked = true;
      invokeCount++;
      putCount++;
      updates++;
      isOriginRemote = event.isOriginRemote();
    }
    
    @Override
    public void afterInvalidate(EntryEvent event) {
      event.getRegion().getCache().getLogger().warning("ZZZ AFTER UPDATE:"+event.getKey());
      invoked = true;
      invokeCount++;
      invalidateCount++;
      isOriginRemote = event.isOriginRemote();
    }
    
    public void reset() {
    	invoked = false;
    	invokeCount = 0;
    	invalidateCount = 0;
    	putCount = 0;
    	isOriginRemote = false;
    	creates = 0;
    	updates = 0;
    }
  }
  
  protected static class ServerListener extends CacheListenerAdapter {
    boolean invoked = false;
    int creates;
    int updates;
    @Override
    public void afterCreate(EntryEvent event) {
      invoked = true;
      creates++;
      assertTrue(event.isOriginRemote());
    }
    @Override
    public void afterUpdate(EntryEvent event) {
      invoked = true;
      updates++;
      assertTrue(event.isOriginRemote());
    }
    @Override
    public void afterDestroy(EntryEvent event) {
      invoked = true;
      assertTrue(event.isOriginRemote());
    }
    @Override
    public void afterInvalidate(EntryEvent event) {
      invoked = true;
      assertTrue(event.isOriginRemote());
    }
  }
  
  protected static class ServerWriter extends CacheWriterAdapter {
    boolean invoked = false;
    @Override
    public void beforeCreate(EntryEvent event) throws CacheWriterException {
      invoked = true;
      event.getRegion().getCache().getLogger().info("SWAP:writer:"+event);
      assertTrue(event.isOriginRemote());
    }
    @Override
    public void beforeUpdate(EntryEvent event) throws CacheWriterException {
      invoked = true;
      event.getRegion().getCache().getLogger().info("SWAP:writer:"+event);
      assertTrue(event.isOriginRemote());
    }
    @Override
    public void beforeDestroy(EntryEvent event) throws CacheWriterException {
      invoked = true;
      event.getRegion().getCache().getLogger().info("SWAP:writer:"+event);
      assertTrue(event.isOriginRemote());
    }
  }
  
  
  public void Bug44146_testTXWithRI() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(datastore);
    
    createClientRegion(client, port, false, true,false);
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
        Region<CustId,Customer> refRegion = getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        OrderId orderId = new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
        orderRegion.put(orderId, new Order("fooOrder"));
        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        assertTrue(cl.invoked);
        return null;
      }
    });
  }
  
  private static final String EMPTY_REGION = "emptyRegionName";
  
  public void Bug44146_testBug43176() {
    Host host = Host.getHost(0);
    VM datastore = host.getVM(0);
    VM client = host.getVM(1);
    
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        AttributesFactory<Integer, String> af = new AttributesFactory<Integer, String>();
        af.setScope(Scope.DISTRIBUTED_ACK);
        af.setDataPolicy(DataPolicy.EMPTY);
        af.setConcurrencyChecksEnabled(getConcurrencyChecksEnabled());
        getCache().createRegionFactory(af.create()).create(EMPTY_REGION);
        af.setDataPolicy(DataPolicy.REPLICATE);
        getCache().createRegionFactory(af.create()).create(D_REFERENCE);
        return null;
      }
    });
    
    final int port = startServer(datastore);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        ClientCacheFactory ccf = new ClientCacheFactory();
        ccf.addPoolServer("localhost"/*getServerHostName(Host.getHost(0))*/, port);
        ccf.setPoolSubscriptionEnabled(true);
        ccf.set("log-level", getDUnitLogLevel());
        ClientCache cCache = getClientCache(ccf);
        ClientRegionFactory<Integer, String> crf = cCache
            .createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY);
        crf.addCacheListener(new ClientListener());
        crf.setConcurrencyChecksEnabled(getConcurrencyChecksEnabled());
        Region r = crf.create(D_REFERENCE);
        Region empty = crf.create(EMPTY_REGION);
        r.registerInterest("ALL_KEYS", InterestResultPolicy.KEYS_VALUES);
        empty.registerInterest("ALL_KEYS", InterestResultPolicy.KEYS_VALUES);
        return null;
      }
    });
    
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region ref = getCache().getRegion(D_REFERENCE);
        Region empty = getCache().getRegion(EMPTY_REGION);
        getGemfireCache().getCacheTransactionManager().begin();
        ref.put("one", "value1");
        empty.put("eone", "valueOne");
        getCache().getLogger().info("SWAP:callingCommit");
        getGemfireCache().getCacheTransactionManager().commit();
        assertTrue(ref.containsKey("one"));
        assertEquals("value1", ref.get("one"));
        assertFalse(empty.containsKey("eone"));
        assertNull(empty.get("eone"));
        return null;
      }
    });
    
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region empty = getCache().getRegion(EMPTY_REGION);
        final ClientListener l = (ClientListener) empty.getAttributes().getCacheListeners()[0];
        WaitCriterion wc = new WaitCriterion() {
          public boolean done() {
            return l.invoked;
          }
          public String description() {
            return "listener invoked:"+l.invoked;
          }
        };
        DistributedTestCase.waitForCriterion(wc, 10*1000, 200, true);
        return null;
      }
    });
  }
  
  public void Bug44146_testTXWithRICommitInDatastore() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(datastore);
    
    createClientRegion(client, port, false, true,false);
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
        Region<CustId,Customer> refRegion = getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        OrderId orderId = new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
        orderRegion.put(orderId, new Order("fooOrder"));
        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        assertTrue(cl.invoked);
        return null;
      }
    });
  }

  public void Bug44146_testListenersNotInvokedOnSecondary() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    
    initAccessorAndDataStoreWithInterestPolicy(accessor, datastore1, datastore2, 1);
    SerializableCallable registerListener = new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        custRegion.getAttributesMutator().addCacheListener(new ListenerInvocationCounter());
        return null;
      }
    };
    datastore1.invoke(registerListener);
    datastore2.invoke(registerListener);
    
    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getCacheTransactionManager().begin();
        CustId custId = new CustId(1);
        Customer customer = new Customer("customerNew", "addressNew");
        custRegion.put(custId, customer);
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    SerializableCallable getListenerCount = new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        ListenerInvocationCounter l = (ListenerInvocationCounter) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:listenerCount:"+l.invocationCount);
        return l.invocationCount;
      }
    };
    
    int totalInvocation = (Integer) datastore1.invoke(getListenerCount) + (Integer)datastore2.invoke(getListenerCount);
    assertEquals(1, totalInvocation);
  }

  private class ListenerInvocationCounter extends CacheListenerAdapter {
    private int invocationCount = 0;

    @Override
    public void afterUpdate(EntryEvent event) {
      invocationCount++;
    }

    @Override
    public void close() {
      invocationCount = 0;
    }
  }

  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTXWithCQCommitInDatastore() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(datastore);
    
    createClientRegion(client, port, false, true,true);
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        Region<OrderId, Order> orderRegion = getCache().getRegion(ORDER);
        Region<CustId,Customer> refRegion = getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        OrderId orderId = new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
        orderRegion.put(orderId, new Order("fooOrder"));
        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        
        assertTrue(((ClientCQListener)custRegion.getCache().getQueryService().getCqs()[0].getCqAttributes().getCqListener()).invoked);
        assertTrue(cl.invoked);
        return null;
      }
    });
  }
  
  
  
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTXWithCQCommitInDatastoreConnectedToAccessor() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(accessor);
    
    createClientRegion(client, port, false, true,true);
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
//        orderRegion.put(orderId, new Order("fooOrder"));
//        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        assertTrue(cl.invoked);
        assertTrue(((ClientCQListener)custRegion.getCache().getQueryService().getCqs()[0].getCqAttributes().getCqListener()).invoked);
        return null;
      }
    });
  }
  
  
  
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTXWithCQCommitInDatastoreConnectedToDatastore() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(datastore);
    
    createClientRegion(client, port, false, true,true);
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
//        orderRegion.put(orderId, new Order("fooOrder"));
//        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        assertTrue(cl.invoked);
        assertTrue(((ClientCQListener)custRegion.getCache().getQueryService().getCqs()[0].getCqAttributes().getCqListener()).invoked);
        return null;
      }
    });
  }
  
  
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTXWithCQCommitInAccessorConnectedToDatastore() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(datastore);
    
    createClientRegion(client, port, false, true,true);
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
//        orderRegion.put(orderId, new Order("fooOrder"));
//        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        assertTrue(cl.invoked);
        assertTrue(((ClientCQListener)custRegion.getCache().getQueryService().getCqs()[0].getCqAttributes().getCqListener()).invoked);
        return null;
      }
    });
  }
  
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testTXWithCQCommitInAccessorConnectedToAccessor() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(accessor);
    
    createClientRegion(client, port, false, true,true);
    accessor.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
//        orderRegion.put(orderId, new Order("fooOrder"));
//        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        assertTrue(cl.invoked);
        assertTrue(((ClientCQListener)custRegion.getCache().getQueryService().getCqs()[0].getCqAttributes().getCqListener()).invoked);
        return null;
      }
    });
  }
  
  
  
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testCQCommitInDAtastoreConnectedToAccessor() throws Exception {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    VM client = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore, 0);
    int port = startServer(accessor);
    
    createClientRegion(client, port, false, true,true);
    datastore.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        CustId custId = new CustId(1);
        new OrderId(1, custId);
        getCache().getCacheTransactionManager().begin();
        custRegion.put(custId, new Customer("foo", "bar"));
//        orderRegion.put(orderId, new Order("fooOrder"));
//        refRegion.put(custId, new Customer("foo", "bar"));
        getCache().getCacheTransactionManager().commit();
        return null;
      }
    });
    
    Thread.sleep(10000);
    client.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId, Customer> custRegion = getCache().getRegion(CUSTOMER);
        getCache().getRegion(ORDER);
        getCache().getRegion(D_REFERENCE);
        ClientListener cl = (ClientListener) custRegion.getAttributes().getCacheListeners()[0];
        getCache().getLogger().info("SWAP:CLIENTinvoked:"+cl.invoked);
        assertTrue(cl.invoked);
        assertTrue(((ClientCQListener)custRegion.getCache().getQueryService().getCqs()[0].getCqAttributes().getCqListener()).invoked);
        return null;
      }
    });
  }

  /**
   * Non-TX, TX conflict detection not implemented yet.
   */
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testBug33073() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore1 = host.getVM(1);
    VM datastore2 = host.getVM(2);
    
    initAccessorAndDataStore(accessor, datastore1, datastore2, 0);
    final CustId custId = new CustId(19);
    
    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId,Customer> refRegion = getCache().getRegion(D_REFERENCE);
        assertNull(refRegion.get(custId));
        getCache().getCacheTransactionManager().begin();
        refRegion.put(custId, new Customer("name1", "address1"));
        return null;
      }
    });
    datastore2.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        Region<CustId,Customer> refRegion = getCache().getRegion(D_REFERENCE);
        assertNull(refRegion.get(custId));
        refRegion.put(custId, new Customer("nameNew", "addressNew"));
        return null;
      }
    });
    datastore1.invoke(new SerializableCallable() {
      public Object call() throws Exception {
        try {
          getCache().getCacheTransactionManager().commit();
          fail("expected conflict not thrown");
        } catch (ConflictException cc) {
        }
        return null;
      }
    });
  }

  /**
   * Non-TX, TX conflict detection not implemented yet.
   */
  public void DISABLED_TILL_NEW_TX_IMPL_COMPLETE_testBug43081() throws Exception {
    createRegion(false, 0, null);
    Context ctx = getCache().getJNDIContext();
    UserTransaction tx = (UserTransaction)ctx.lookup("java:/UserTransaction");
    assertEquals(Status.STATUS_NO_TRANSACTION, tx.getStatus());
    Region pr = getCache().getRegion(CUSTOMER);
    Region rr = getCache().getRegion(D_REFERENCE);
    // test all ops
    for (int i=0; i<6; i++) {
      pr.put(new CustId(1), new Customer("name1", "address1"));
      rr.put("key1", "value1");
      tx.begin();
      switch (i) {
      case 0:
        pr.get(new CustId(1));
        rr.get("key1");
        break;
      case 1:
        pr.put(new CustId(1), new Customer("nameNew", "addressNew"));
        rr.put("key1", "valueNew");
        break;
      case 2:
        pr.invalidate(new CustId(1));
        rr.invalidate("key1");
        break;
      case 3:
        pr.destroy(new CustId(1));
        rr.destroy("key1");
        break;
      case 4:
        Map m = new HashMap();
        m.put(new CustId(1), new Customer("nameNew", "addressNew"));
        pr.putAll(m);
        m = new HashMap();
        m.put("key1", "valueNew");
        rr.putAll(m);
        break;
      case 5:
        Set s = new HashSet();
        s.add(new CustId(1));
        pr.getAll(s);
        s = new HashSet();
        s.add("key1");
        pr.getAll(s);
        break;
      case 6:
        pr.getEntry(new CustId(1));
        rr.getEntry("key1");
        break;
      default:
        break;
      }
      
      assertEquals(Status.STATUS_ACTIVE, tx.getStatus());
      final CountDownLatch latch = new CountDownLatch(1);
      Thread t = new Thread(new Runnable() {
        public void run() {
          Context ctx = getCache().getJNDIContext();
          try {
            ctx.lookup("java:/UserTransaction");
          } catch (NamingException e) {
            e.printStackTrace();
          }
          Region pr = getCache().getRegion(CUSTOMER);
          Region rr = getCache().getRegion(D_REFERENCE);
          pr.put(new CustId(1), new Customer("name11", "address11"));
          rr.put("key1", "value1");
          latch.countDown();
        }
      });
      t.start();
      latch.await();
      try {
        pr.put(new CustId(1), new Customer("name11", "address11"));
        tx.commit();
        fail("expected exception not thrown");
      } catch (RollbackException e) {
      }
    }
  }

  public void Bug44146_testBug45556() {
    Host host = Host.getHost(0);
    VM accessor = host.getVM(0);
    VM datastore = host.getVM(1);
    final String name = getName();

    class CountingListener extends CacheListenerAdapter {
      private int count;

      @Override
      public void afterCreate(EntryEvent event) {
        getLogWriter().info("afterCreate invoked for " + event);
        count++;
      }

      @Override
      public void afterUpdate(EntryEvent event) {
        getLogWriter().info("afterUpdate invoked for " + event);
        count++;
      }
    }

    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region r = getCache().createRegionFactory(
            RegionShortcut.REPLICATE_PROXY).create(name);
        r.getAttributesMutator().addCacheListener(new CountingListener());
        return null;
      }
    });
    datastore.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region r = getCache().createRegionFactory(RegionShortcut.REPLICATE)
            .create(name);
        r.getAttributesMutator().addCacheListener(new CountingListener());
        r.put("key1", "value1");
        return null;
      }
    });
    final TXId txid = (TXId)accessor
        .invoke(new SerializableCallable() {
          @Override
          public Object call() throws Exception {
            Region r = getCache().getRegion(name);
            CacheTransactionManager tm = getCache()
                .getCacheTransactionManager();
            getCache().getLogger().fine("SWAP:BeginTX");
            tm.begin();
            r.put("txkey", "txvalue");
            return tm.suspend();
          }
        });
    datastore.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region rgn = getCache().getRegion(name);
        assertNull(rgn.get("txkey"));
        TXManagerImpl txMgr = getGemfireCache().getTxManager();
        TXStateProxy tx = txMgr.getHostedTXState(txid);
        assertEquals(1, tx.getRegions().size());
        for (LocalRegion r : tx.getRegions()) {
          assertTrue(r instanceof DistributedRegion);
          TXRegionState rs = tx.readRegion(r);
          for (Object key : rs.getEntryKeys()) {
            TXEntryState es = (TXEntryState)rs.readEntry(key);
            assertEquals("txkey", key);
            assertNotNull(es.getValue(r));
            if (key.equals("txkey"))
              assertTrue(es.isDirty());
          }
        }
        return null;
      }
    });
    accessor.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region rgn = getCache().getRegion(name);
        assertNull(rgn.get("txkey"));
        CacheTransactionManager mgr = getCache().getCacheTransactionManager();
        mgr.resume(txid);
        mgr.commit();
        CountingListener cl = (CountingListener)rgn.getAttributes()
            .getCacheListeners()[0];
        assertEquals(0, cl.count);
        assertEquals("txvalue", rgn.get("txkey"));
        return null;
      }
    });
    datastore.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region rgn = getCache().getRegion(name);
        CountingListener cl = (CountingListener)rgn.getAttributes()
            .getCacheListeners()[0];
        assertEquals(2, cl.count);
        return null;
      }
    });
  }

  public void Bug44146_testExpirySuspend_bug45984() {
    Host host = Host.getHost(0);
    VM vm1 = host.getVM(0);
    VM vm2 = host.getVM(1);
    final String regionName = getName();

    // create region with expiration
    vm1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        RegionFactory<String, String> rf = getCache().createRegionFactory();
        rf.setEntryTimeToLive(new ExpirationAttributes(1,
            ExpirationAction.DESTROY));
        rf.setScope(Scope.DISTRIBUTED_ACK);
        rf.create(regionName);
        return null;
      }
    });

    // create replicate region
    vm2.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().createRegionFactory(RegionShortcut.REPLICATE).create(
            regionName);
        return null;
      }
    });

    vm1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        final Region<String, String> r = getCache().getRegion(regionName);
        r.put("key", "value");
        r.put("nonTXkey", "nonTXvalue");
        getCache().getCacheTransactionManager().begin();
        r.put("key", "newvalue");
        // wait for entry to expire
        DistributedTestCase.pause(5000);
        TransactionId tx = getCache().getCacheTransactionManager().suspend();
        assertTrue(r.containsKey("key"));
        assertTrue(r.containsKey("nonTXkey"));
        getCache().getCacheTransactionManager().resume(tx);
        getCache().getCacheTransactionManager().commit();
        WaitCriterion wc2 = new WaitCriterion() {
          @Override
          public boolean done() {
            return !r.containsKey("key") && !r.containsKey("nonTXKey");
          }

          @Override
          public String description() {
            return "did not expire";
          }
        };
        DistributedTestCase.waitForCriterion(wc2, 30000, 5, true);
        return null;
      }
    });
  }

  public void Bug44146_testRemoteFetchVersionMessage() {
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    final String regionName = getName();

    final VersionTag tag = (VersionTag)vm0.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        LocalRegion r = (LocalRegion)getCache().createRegionFactory(
            RegionShortcut.REPLICATE).create(regionName);
        r.put("key", "value");
        return r.getRegionEntry("key").getVersionStamp().asVersionTag();
      }
    });

    vm1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.EMPTY);
        af.setScope(Scope.DISTRIBUTED_ACK);
        DistributedRegion r = (DistributedRegion)getCache().createRegion(
            regionName, af.create());
        r.cache.getLogger().info("SWAP:sending:remoteTagRequest");
        VersionTag remote = r.fetchRemoteVersionTag("key");
        r.cache.getLogger().info("SWAP:remoteTag:" + remote);
        try {
          remote = r.fetchRemoteVersionTag("nonExistentKey");
          fail("expected exception not thrown");
        } catch (EntryNotFoundException e) {
        }
        assertEquals(tag, remote);
        return null;
      }
    });
  }

  public void Bug44146_testTransactionWithRemoteVersionFetch() {
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);
    final String regionNameNormal = getName() + "_normal";
    final String regionName = getName();

    vm0.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        getCache().createRegionFactory(RegionShortcut.REPLICATE)
            .create(regionName);
        Region n = getCache().createRegionFactory(RegionShortcut.REPLICATE)
            .create(regionNameNormal);
        n.put("key", "value");
        n.put("key", "value1");
        n.put("key", "value2");
        return null;
      }
    });

    final VersionTag tag = (VersionTag)vm1.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region r = getCache().createRegionFactory(RegionShortcut.REPLICATE)
            .create(regionName);
        AttributesFactory af = new AttributesFactory();
        af.setDataPolicy(DataPolicy.NORMAL);
        af.setScope(Scope.DISTRIBUTED_ACK);
        Region n = getCache().createRegion(regionNameNormal, af.create());
        TXManagerImpl mgr = getGemfireCache().getTxManager();
        mgr.begin();
        r.put("key", "value");
        assertTrue(mgr.getTXState().getLocalTXState() != null);
        getCache().getLogger().fine("SWAP:doingPutInNormalRegion");
        n.put("key", "value");
        getCache().getLogger().fine("SWAP:commiting");
        mgr.commit();
        return ((LocalRegion)n).getRegionEntry("key").getVersionStamp()
            .asVersionTag();
      }
    });

    vm0.invoke(new SerializableCallable() {
      @Override
      public Object call() throws Exception {
        Region n = getCache().getRegion(regionNameNormal);
        VersionTag localTag = ((LocalRegion)n).getRegionEntry("key")
            .getVersionStamp().asVersionTag();
        assertEquals(tag.getEntryVersion(), localTag.getEntryVersion());
        assertEquals(tag.getRegionVersion(), localTag.getRegionVersion());
        return null;
      }
    });
  }
}
