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
package com.pivotal.gemfirexd.internal.engine.map;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

import junit.framework.TestCase;

public class DHMTest extends TestCase {

  public static void main(String[] args) {
    DHMTest t = new DHMTest();
    t.test1M();
    t.__test50M();
  }

  public void test1M() {

    DenseIntValueHashMap<String> map = new DenseIntValueHashMap<>(new DHMDefaultSerializer(), 100);
//    DenseIntValueHashMap<String> map = new DenseIntValueHashMap<>(new com.pivotal.gemfirexd.internal.engine.map.DHMBase64Serializer(), 32);
//    HashMap<String, Integer> map = new HashMap<>(32);
//    ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>(32);
//    TObjectIntHashMap map = new TObjectIntHashMap(32);

    final Random r = new Random(System.currentTimeMillis());

    final int count = 1000000;
    Runtime rt = Runtime.getRuntime();
    System.out.println("populating keys");
/*
    String[] keys = new String[count];
    for (int i = 0; i < count; i++) {
      keys[i] = UUID.randomUUID().toString();
    }
*/

    long lastReport = System.currentTimeMillis();
    System.gc();
    System.gc();
    System.gc();
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    System.out.println("starting inserts.. in-memory keys taking " + (rt.totalMemory() - rt.freeMemory()) / 1048576.0 + " mb");

    System.out.printf("Count %d, Memory = %.2f mb", map.size(), (rt.totalMemory() - rt.freeMemory()) / 1048576.0).println();
    for (int i = 0; i < count; i++) {
      try {
        String k = UUID.randomUUID().toString();
        map.put(k, i % 113);
        assert map.get(k) == i % 113;
        if (System.currentTimeMillis() - lastReport > 1000) {
          System.gc();
          System.gc();
          System.gc();
          System.out.printf("\rCount %d, Memory = %.2f mb", map.size(), (rt.totalMemory() - rt.freeMemory()) / 1048576.0).println();
          lastReport = System.currentTimeMillis();
        }
      } catch (RuntimeException e) {
        e.printStackTrace();
        break;
      }
    }

    System.gc();
    System.gc();
    System.gc();
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    System.out.printf("\rCount %d, Memory = %.2f mb ", map.size(), (rt.totalMemory() - rt.freeMemory()) / 1048576.0);

/*
    for (int c = 0; c < keys.length; c++) {
      Object o = map.get(keys[c]);
      assert o != null;
    }
*/


  }

  public void test1MAddRemove() throws BrokenBarrierException, InterruptedException {

    final int count = 1000000;
    DenseIntValueHashMap<String> map = new DenseIntValueHashMap<>(new DHMDefaultSerializer(), 100);

    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " populating keys");
    final String[] keys = new String[count];
    for (int i = 0; i < count; i++) {
      keys[i] = UUID.randomUUID().toString();
    }

    doARWork(map, keys, null);
  }


  public void testConcurrent5MAddRemove() throws InterruptedException {

    final int count = 5000000;
    final Thread[] concurrentTs = new Thread[10];
    final DenseIntValueHashMap<String> map = new DenseIntValueHashMap<>(new DHMDefaultSerializer(), 100);

    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " populating keys");
    final String[] keys = new String[count];
    for (int i = 0; i < count; i++) {
      keys[i] = UUID.randomUUID().toString();
    }

    final CyclicBarrier barrier = new CyclicBarrier(concurrentTs.length, null);

    for (int i = 0; i < concurrentTs.length; i++) {
      concurrentTs[i] = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            doARWork(map, keys, barrier);
          } catch (BrokenBarrierException e) {
            e.printStackTrace();
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
      }, "testConcurrent1MAddRem-" + i);
    }

    for (int i = 0; i < concurrentTs.length; i++) {
      concurrentTs[i].start();
    }

    for (int i = 0; i < concurrentTs.length; i++) {
      concurrentTs[i].join();
    }

    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " SUCCESS");
  }

  protected void doARWork(final DenseIntValueHashMap map, final String[] keys, final CyclicBarrier barrier) throws BrokenBarrierException, InterruptedException {

    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " starting inserts...");

    for (int i = 0; i < keys.length; i++) {
      try {
        map.put(keys[i], i % 113);
        int _r = map.get(keys[i]);
        assert _r % 113 == i % 113 : "r=" + _r + ",i=" + i;
      } catch (RuntimeException e) {
        e.printStackTrace();
        break;
      }
    }

    if (barrier != null) {
      System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " waiting...");
      barrier.await();
    }
    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " starting verification...");

    for (int c = 0; c < keys.length; c++) {
      int o = map.get(keys[c]);
      assert o % 113 == c % 113 : "o=" + o + ",c=" + c;
    }

    if (barrier != null) {
      System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " waiting...");
      barrier.await();
    }
    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " starting remove operations...");
    for (int c = 0; c < keys.length; c++) {
      int o = map.remove(keys[c]);
      assert o == -1 || o % 113 == c % 113 : "o=" + o + ",c=" + c;
    }

    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " DONE");
  }

  public void testConcurrentRandomOps() throws InterruptedException {

    final int count = 1000000;
    final long oneMinute = (60 * 1000);
    final Thread[] concurrentTs = new Thread[10];
    final DenseIntValueHashMap<String> map = new DenseIntValueHashMap<>(new DHMDefaultSerializer(), 100);

    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " populating keys");
    final String[] keys = new String[count];
    for (int i = 0; i < count; i++) {
      keys[i] = UUID.randomUUID().toString();
    }

    for (int i = 0; i < concurrentTs.length; i++) {
      concurrentTs[i] = new Thread(new Runnable() {
        @Override
        public void run() {
          long startTime = System.currentTimeMillis();

          final Random r = new Random(startTime);

          System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " started operations");
          for (; ; ) {
            // do 10k ops before checking.
            for (int iter = 0; iter < 10000; iter++) {
              int index = r.nextInt(keys.length - 1);
              map.put(keys[index], index%113);
              int o = map.get(keys[index]);
              assert o == -1 || o == index%113 : "o=" + o + " idx=" + index + " mod=" + (index%113);
              index = r.nextInt(keys.length - 1);
              o = map.get(keys[index]);
              assert o == -1 || o == index%113 : "o=" + o + " idx=" + index + " mod=" + (index%113);
              index = r.nextInt(keys.length - 1);
              o = map.remove(keys[index]);
              assert o == -1 || o == index%113;
            }

            long elapsed = (System.currentTimeMillis() - startTime);
            if ((elapsed % 1000) == 0) {
              System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " elapsed " + (elapsed / 1000) + " seconds");
            }
            if (elapsed > oneMinute)
              break;
          }

        }
      }, "testConcurrent1MAddRem-" + i);
    }

    for (int i = 0; i < concurrentTs.length; i++) {
      concurrentTs[i].start();
    }

    for (int i = 0; i < concurrentTs.length; i++) {
      concurrentTs[i].join();
    }

    System.out.println(new java.sql.Timestamp(System.currentTimeMillis()) + " " + Thread.currentThread().getName() + " SUCCESS");
  }


  public void __test50M() {

    DenseIntValueHashMap<String> map = new DenseIntValueHashMap<>(new DHMDefaultSerializer(), 32);
//    DenseIntValueHashMap<String> map = new DenseIntValueHashMap<>(new com.pivotal.gemfirexd.internal.engine.map.DHMBase64Serializer(), 32);

    final int count = 50000000;
    final Runtime rt = Runtime.getRuntime();

    long lastReport = System.currentTimeMillis();
    System.gc();
    System.gc();
    System.gc();
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    System.out.println("starting inserts.. in-memory keys taking " + (rt.totalMemory() - rt.freeMemory()) / 1048576.0 + " mb");

    System.out.printf("Count %d, Memory = %.2f mb", map.size(), (rt.totalMemory() - rt.freeMemory()) / 1048576.0).println();
    for (int i = 0; i < count; i++) {
      try {
        final String k = UUID.randomUUID().toString();
        map.put(k, i % 113);
        if (System.currentTimeMillis() - lastReport > 10000) {
          System.out.printf("\rCount %d, Memory = %.2f mb", map.size(), (rt.totalMemory() - rt.freeMemory()) / 1048576.0).println();
          lastReport = System.currentTimeMillis();
        }
      } catch (RuntimeException e) {
        e.printStackTrace();
        break;
      }
    }

    System.gc();
    System.gc();
    System.gc();
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    System.gc();
    System.gc();
    System.gc();
    System.out.printf("\rCount %d, Memory = %.2f mb ", map.size(), (rt.totalMemory() - rt.freeMemory()) / 1048576.0);
  }

  private String[] getStaticKeys(int count) {
    final boolean generateKeys = false;
    if (generateKeys) {
      System.out.println("populating keys");
      StringBuilder sb = new StringBuilder("new String[] {");
      for (int i = 0; i < count; i++) {
        sb.append("\"").append(UUID.randomUUID().toString()).append("\",\n");
      }

      sb.append("};");
      System.out.println(sb.toString());
      System.exit(565);
    }

    return new String[]{"fe1e7fbc-8eb5-4252-95be-69f3eeb6a882",
        "0773284f-9c09-4c00-aebd-e699e4b797be",
        "de594b70-2dc7-453b-b930-8206c264644c",
        "59da0d29-49b8-4c72-9e4f-b2b35b8d7b90",
        "4b892092-8650-4564-9190-a02cb8e3a828",
        "228e22e7-3253-4e9b-b7a3-9a9d32638567",
        "4249e206-7fca-4d61-8a05-6bff6f47bcfa",
        "a0e74522-3d45-4cc6-93ca-ee45cf2b6b3b",
        "ab67023f-0b00-4633-81f3-a586c50c4924",
        "61830265-55ad-4291-a2e1-4323411dea8a",
        "46de7803-8531-4d2d-b4b0-a3a33e86d04c",
        "7c84983f-9be3-41eb-ad31-e31ceb258af5",
        "cdbf1d3a-d4fe-49e9-8e5d-a38a34b0ca3e",
        "1f9c22b5-a16c-4666-8529-0d6286ecf41a",
        "2aedb0fa-308c-40e1-ba56-0ac1ed802d22",
        "61e30dbe-4cf0-41e0-b0ef-501fcd6ba9fe",
        "2a840b9d-7a01-4678-897e-a617502c296d",
        "346275b1-3d8e-446b-87ed-fff959442b13",
        "4f3d4a24-5977-4c02-8608-2a0531ac2996",
        "ea4cf8cd-017a-43d2-ad2c-9b6e26801143",
        "6b135ab1-bebb-457d-815c-476910c2a798",
        "8314e6a2-9f6f-4516-a685-c6faedc09794",
        "b8122157-942f-415a-b822-dfbc753b78b1",
        "a08989c8-440c-4146-9843-6a92efa49c26",
        "78e7da93-6ecb-47a4-bb78-989cbcbdbc12",
        "ddc94cb9-11f6-4a65-ab90-59c2c0aba50e",
        "f1280740-a5ac-4909-b841-43473a66f13b",
        "9b8c8ddc-5ab8-4011-9cdd-d883687ca41a",
        "af66656e-a907-4ae4-9fe4-157373d5b428",
        "50ed52e4-e65f-42d0-b1c5-f43ba80d0a2e",
        "99c889d7-d19c-4444-8b34-16f3c5eab828",
        "8a98c691-c202-4632-9394-72fe218063d1",
        "76af995a-0535-4172-af41-21fe0a01546e",
        "bc79bd9f-6e8f-4e39-9c08-8bfe5885a3f4",
        "c4fceb09-ef03-4c4d-a7cb-1c4b5a2f3628",
        "4792e26a-b233-471d-803a-e8fcb2bc5c95",
        "5e417a3a-9d73-4146-8985-b83f52ac9fb1",
        "820b0721-ce18-459e-86a2-6730572e7dde",
        "93440fc9-ca1a-47bb-a0eb-4c5c72dff1d3",
        "576737eb-8398-40d6-aa0d-afd22893064b",
        "df07ea35-b581-466f-ad22-da17e1aee887",
        "775b0b8e-087a-4f09-9e89-ee3f217182e9",
        "de3b2bd3-c2f6-4eb6-8654-75f5674c3203",
        "474ecbf8-4bdd-4067-a1fc-196ea290a32a",
        "eab37491-7844-4043-adda-58824cb57562",
        "0e70ef8f-6783-4952-90af-d342713e9208",
        "391f2db8-2c51-4c5a-9b32-ecaf089aa275",
        "114ad264-6a3e-4e9e-b942-b0b3507492c9",
        "ac044bcf-c020-4dc9-b51d-8e74f1e0d0a2",
        "aecfdf85-6349-48bf-867f-562090e0c25b",
        "84f99b97-0943-412b-a5a4-02b0c281930b",
        "fee0982c-fe9b-44cd-9215-694f801df03f",
        "f02c3eb2-92f6-4ff5-9d8a-362fee4f9efa",
        "0d3f2401-eceb-4283-8b4c-74ddd09007cf",
        "ea98192e-d29a-422d-bfa3-f8185e7ac5db",
        "e0ecb9b8-51a4-4320-9acd-3b25921f5f34",
        "5d408b95-b577-442e-acc4-58843d8363ac",
        "c0518401-cf5e-4332-9740-1e298a5e0d96",
        "f499a184-ceb0-4506-bcd9-e8564fa73ec3",
        "c0a9ab75-05e5-428e-949c-1c99d7280e20",
        "c8605ef6-7cad-48e4-96ff-f7f4f9cf60e2",
        "09b8e6c2-50bf-4591-91ad-8d7ddc0131b6",
        "ed72107c-a19f-4974-a3e3-1d222ffe838d",
        "2e707612-133d-4cca-b1c9-918229df4776",
        "325dffac-55e5-45e9-9aa3-126a6c99d881",
        "0578e4e2-f904-4732-9de9-4ea9d85063dd",
        "be78432b-cf1b-489d-9b83-47ef3a3a3613",
        "f68b4e51-06b4-4b4c-af14-e601b3e9f9a0",
        "3ec87921-090e-42b9-85f9-e4720356966b",
        "1391fd83-d40e-4419-be03-98196f8a7954",
        "5deecde5-d6e2-4e57-a756-0240ab6e3c7d",
        "8d78b41f-4afc-4754-a11c-1f5865594e29",
        "fd4290ac-3dd2-44bb-897b-440e5365f6c2",
        "b7f408b5-87f1-4c89-9e0b-1e03506a5fec",
        "13c847e0-0e7f-49af-98df-fe64736ea7f5",
        "daae55c9-8de7-4e96-bb1c-27455d81e0e1",
        "d32c1452-a2ca-495c-9d5b-91c75e00765c",
        "f8aded07-9fc3-4d88-9240-609036ae1c8d",
        "569f21d8-984b-4794-a9c8-776687556445",
        "6ed1e9ab-f368-4da1-840c-ed89fda19f86",
        "3228cab4-b90b-4ac2-a478-d91a53356bd5",
        "d58c3b95-6da4-49e7-afc4-44c1a239d03f",
        "7fc01760-f754-4cb7-9c2a-d5f6d4237916",
        "c0af267e-a55f-4fd4-ac52-444ee563c334",
        "1c97d7c9-0f75-4441-88f3-4bf6cfd1f06a",
        "e0410fc1-0f51-4953-80ac-5b34c1e91d69",
        "1b3b97d1-c1cd-416d-b09c-2a565a51fe52",
        "fa2c879b-ed24-4497-9b88-c2f667f47de2",
        "a4510320-9d41-4eff-a84f-3cfe6e1c8376",
        "5ac2d8c3-4199-4e28-ace4-389263eeb39e",
        "33c0861c-5743-4d88-a36c-d324d38fe6a7",
        "3b4a0359-75dc-4b2a-a656-697e4a86e1bf",
        "fbad8e7d-d3d1-4d79-ae6c-a2e251a35b6a",
        "be24017b-a757-4eaa-a353-8982330a6971",
        "c8a00f33-265d-4653-8fa8-4be3a1525dfd",
        "18336ed1-a69b-4ea7-a219-59e1a5ff82d6",
        "cb549ccf-1d6d-497c-bb03-6f0defd3c647",
        "09d3e662-96ca-4dda-8657-28e8f96e11bf",
        "27e3958d-f11b-4255-8f95-7006c642a8cf",
        "4a47af2a-41c0-4134-83d2-6048c2604034",
    };

  }

}
