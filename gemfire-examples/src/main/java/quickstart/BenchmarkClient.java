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
import java.io.IOException;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;

/**
 * This example measures the time it takes for a client put to return in a
 * hierarchical cache configuration. Please refer to the quickstart guide for
 * instructions on how to run this example.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 5.8
 */
public class BenchmarkClient {

  /** Number of samples to time for benchmarking */
  public static final int NUMBER_OF_SAMPLES = Integer.getInteger("benchmark.number-of-samples", 60);

  /** Number of put operations to execute for each sampling */
  public static final int OPERATIONS_PER_SAMPLE = Integer.getInteger("benchmark.operations-per-sample", 5000);

  /** Size in kilobytes of payload to put in cache */
  public static final int PAYLOAD_KB_SIZE = Integer.getInteger("benchmark.payload-bytes", 1024);

  public static void main(String[] args) throws CacheException, IOException {
    BenchmarkClient client = new BenchmarkClient();
    client.go();
    System.exit(0);
  }

  public void go() throws CacheException, IOException {

    // Create the cache which causes the cache-xml-file to be parsed
    ClientCache cache = new ClientCacheFactory()
        .set("name", "BenchmarkClient")
        .set("cache-xml-file", "xml/BenchmarkClient.xml")
        .create();

    // Get the exampleRegion
    Region<String, byte[]> exampleRegion = cache.getRegion("exampleRegion");

    // Total number of benchmark samples to gather...
    int numSamples = NUMBER_OF_SAMPLES;
    long[] benchmarkSamples = new long[numSamples];
    int currentSample = 0;

    // How many puts to perform in one sampling
    int operationsPerSample = OPERATIONS_PER_SAMPLE;
    long totalOperations = numSamples * operationsPerSample;

    // Size of data payload to use...
    int payloadByteSize = PAYLOAD_KB_SIZE; // bytes

    String key = "key";
    byte[] value = new byte[payloadByteSize];

    System.out.println("\nClient/Server benchmark example. ");
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
    bufferedReader.readLine();

    System.out.println("\nBenchmark configuration:");
    System.out.println("\tbenchmark.number-of-samples = " + numSamples);
    System.out.println("\tbenchmark.operations-per-sample = " + operationsPerSample);
    System.out.println("\tbenchmark.payload-bytes = " + payloadByteSize);
    System.out.println("Please wait... this may take several minutes...");

    long startTimeActual = System.currentTimeMillis();

    // Perform benchmark until numSamples are executed
    for (; currentSample < numSamples; currentSample++) {
      for (int i = 0; i < operationsPerSample; i++) {
        // Put will return after full round-trip from consumer
        exampleRegion.put(key, value);
      }
      benchmarkSamples[currentSample] = System.currentTimeMillis();
    }

    System.out.println("\nFinished run. Collating benchmark totals now.");

    long endTimeActual = benchmarkSamples[numSamples - 1];

    // total time in seconds
    double totalTime = (endTimeActual - startTimeActual) / (double) 1000;
    // total samples
    long totalSamples = numSamples;
    // total bytes
    long totalBytes = payloadByteSize * totalOperations;

    // total put operations per sec
    double totalOpsPerSec = totalOperations / totalTime;
    // total kilobytes per sec
    double totalKBPerSec = (totalBytes / (double) 1024) / totalTime;

    // total avg op time in nanoseconds (billionth of a second)
    double totalAvgOpTime = (totalTime * 1000000000) / totalOperations;
    // total avg byte time in milliseconds
    double totalAvgByteTime = (totalTime * 1000) / totalBytes;

    // Find the best sample (which is after hotspot gets warmed up)...
    long bestSampleTime = benchmarkSamples[0] - startTimeActual;
    for (int i = 1; i < benchmarkSamples.length; i++) {
      long timeForSample = benchmarkSamples[i] - benchmarkSamples[i - 1];
      if (timeForSample < bestSampleTime) {
        bestSampleTime = timeForSample;
      }
    }

    // convert bestSampleTime to seconds
    double bestTime = bestSampleTime / (double) 1000;
    // best ops per sec
    double bestOpsPerSec = operationsPerSample / bestTime;
    // best kilobytes per sec
    double bestKBPerSec = (payloadByteSize / (double) 1024) * operationsPerSample / bestTime;

    // best avg op time in nanoseconds (billionth of a second)
    double bestAvgOpTime = (bestTime * 1000000000) / operationsPerSample;
    // best avg byte time in milliseconds
    double bestAvgByteTime = (bestTime * 1000) / (payloadByteSize * operationsPerSample);

    System.out.println("\nClient/Server Cache Benchmark results:");
    System.out.println("");
    System.out.println("\tTotal Time = " + totalTime + " seconds");
    System.out.println("\tTotal Puts = " + totalOperations + " put operations");
    System.out.println("\tTotal Samples = " + totalSamples + " benchmark samples");
    System.out.println("\tTotal Kilobytes = " + (totalBytes / 1024) + " kilobytes");
    System.out.println("");
    System.out.println("\tAverage Puts Per Second = " + totalOpsPerSec + " puts");
    System.out.println("\tAverage Kilobytes Per Second = " + totalKBPerSec + " kilobytes of data");
    System.out.println("\tAverage Operations Time = " + totalAvgOpTime + " nanoseconds per put");
    System.out.println("\tAverage Byte Time = " + totalAvgByteTime + " milliseconds per byte of data");
    System.out.println("");
    System.out.println("\tBest Puts Per Second = " + bestOpsPerSec + " puts");
    System.out.println("\tBest Kilobytes Per Second = " + bestKBPerSec + " kilobytes of data");
    System.out.println("\tBest Operations Time = " + bestAvgOpTime + " nanoseconds per put");
    System.out.println("\tBest Byte Time = " + bestAvgByteTime + " milliseconds per byte of data");
    System.out.println("\tNote: Best sample is representative of performance after JVM warms up.");

    // Close the cache and disconnect from GemFire distributed system
    System.out.println("\nClosing the cache and disconnecting.");
    cache.close();

    System.out.println("\nPlease stop the cacheserver with 'gfsh stop server --dir=server_bms'. ");
  }
}
