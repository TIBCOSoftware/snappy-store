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
package com.gemstone.gemfire.cache30;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.LogWriterImpl;

/**
 * Tests populating a region with data that is ever-increasing in
 * size.  It is used for testing the "Heap LRU" feature that helps
 * prevent out of memory errors.
 */
public class TestHeapLRU {

  public static void main(String[] args) throws Exception {
    DistributedSystem system =
      DistributedSystem.connect(new java.util.Properties());
    Cache cache = CacheFactory.create(system);
    AttributesFactory factory = new AttributesFactory();

    factory.setEvictionAttributes(EvictionAttributes.createLRUHeapAttributes(null, EvictionAction.OVERFLOW_TO_DISK));
    factory.setDiskSynchronous(true);
    factory.setDiskStoreName(cache.createDiskStoreFactory()
                             .setDiskDirs(new java.io.File[] { new java.io.File(System.getProperty("user.dir"))})
                             .create("TestHeapLRU")
                             .getName());
    Region region =
      cache.createRegion("TestDiskRegion",
                           factory.create());

    ThreadGroup tg =
      LogWriterImpl.createThreadGroup("Annoying threads", (LogWriterI18n)null);
    Thread thread = new Thread(tg, "Annoying thread") {
        public void run() {
          try {
            while (true) {
              System.out.println("Annoy...");
              Object[] array = new Object[10 /* * 1024 */];
              for (int i = 0; i < array.length; i++) {
                array[i] = new byte[1024];
                Thread.sleep(10);
              }

              System.out.println("SYSTEM GC");
              System.gc();
              Thread.sleep(1000);
            }

          } catch (InterruptedException ex) {
            System.err.println("Interrupted"); // FIXME should throw
          }
        }
      };
    thread.setDaemon(true);
//     thread.start();

//    ArrayList list = new ArrayList();
    for (int i = 0; i < Integer.MAX_VALUE; i++) {
      if (i % 1000 == 0) {
//         System.out.println("i = " + i);
//         list = new ArrayList();

      } else {
//         list.add(new Integer(i));
      }

      Integer key = new Integer(i % 10000);
      long[] value = new long[2000];
//       System.out.println("Put " + key + " -> " + value);
      region.put(key, value);
    }
  }

}
