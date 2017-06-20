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
package quickstart;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;

/**
 * This example measures the time it takes for an acknowledgment to arrive back
 * at the producer. For this example, the producer region has distributed-ack
 * scope while the consumer region has distributed-no-ack scope. The producer
 * takes a timestamp, puts an entry value, waits for the ACK, and compares the
 * current timestamp with the timestamp taken before the put. Please refer to
 * the quickstart guide for instructions on how to run this example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 4.1.1
 */
public class BenchmarkAckConsumer {

  public static void main(String[] args) throws Exception {

    // Create the cache which causes the cache-xml-file to be parsed
    Cache cache = new CacheFactory()
        .set("name", "BenchmarkAckConsumer")
        .set("cache-xml-file", "xml/BenchmarkAckConsumer.xml")
        .create();

    System.out.println();
    System.out.println("Please start the BenchmarkAckProducer, and press Enter when the benchmark finishes.");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();

    // Close the cache and disconnect from GemFire distributed system
    System.out.println("Closing the cache and disconnecting.");
    cache.close();
  }
}
