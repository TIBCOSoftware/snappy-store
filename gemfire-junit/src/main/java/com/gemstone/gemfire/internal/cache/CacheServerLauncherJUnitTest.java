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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.rmi.AlreadyBoundException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ClientRegionFactory;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.util.CacheListenerAdapter;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.OSProcess;
import com.gemstone.gemfire.internal.PureJavaMode;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager.ResourceObserverAdapter;
import com.gemstone.gemfire.internal.cache.xmlcache.CacheXml;
import com.gemstone.gemfire.internal.util.StopWatch;
import io.snappydata.test.dunit.AvailablePortHelper;
import junit.framework.TestCase;
import quickstart.ProcessWrapper;

/**
 * Tests the CacheServerLauncher.
 *
 * @author Kirk Lund
 * @author John Blum
 * @since 6.0
 */
public class CacheServerLauncherJUnitTest extends TestCase {

  private static String JTESTS = System.getProperty("JTESTS");;
  private static final String CONTROLLER_NAMING_PORT_PROP
      = "CacheServerLauncherJUnitTest.controllerNamingPort";
  private static final String CACHESERVER_NAMING_PORT_PROP
      = "CacheServerLauncherJUnitTest.cacheserverNamingPort";
  private static final String REBALANCE_STATUS_BINDING
      = "CacheServerLauncherJUnitTest.REBALANCE_STATUS_BINDING";
  private static final String FAILSAFE_BINDING
      = "CacheServerLauncherJUnitTest.FAILSAFE_BINDING";

  private int mcastPort;
  private int serverPort;
  private int controllerNamingPort;
  private int cacheserverNamingPort;
  private String cacheserverDirName = null;

  public CacheServerLauncherJUnitTest(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assertTrue(JTESTS != null && JTESTS.length() > 0);
    this.mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    int[] tcpPorts = AvailablePortHelper.getRandomAvailableTCPPorts(3);
    this.serverPort = tcpPorts[0];
    this.controllerNamingPort = tcpPorts[1];
    this.cacheserverNamingPort = tcpPorts[2];
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    invokeFailsafe();
    InternalDistributedSystem instance = InternalDistributedSystem.getAnyInstance();
    if(instance != null) {
      instance.disconnect();
    }
  }

  private void invokeFailsafe() {
    try {
      Registry registry = LocateRegistry.getRegistry(this.cacheserverNamingPort);
      FailSafeRemote failsafe = (FailSafeRemote) registry.lookup(FAILSAFE_BINDING);
      failsafe.kill();
    } catch (RemoteException ignore) {
      // cacheserver was probably stopped already
    } catch (NotBoundException ignore) {
      // cacheserver was probably stopped already
    }
  }

  private void execAndValidate(String[] args, String regex) throws InterruptedException {
    ProcessWrapper csl = new ProcessWrapper(CacheServerLauncher.class, args);
    csl.execute(null);
    if (regex != null) {
      csl.waitForOutputToMatch(regex);
    }
    csl.waitFor();
  }

  private void createCacheXml(File dir,
                              String cacheXmlName,
                              int port,
                              boolean rebalCacheListener) throws IOException {
    File file = new File(dir, cacheXmlName);
    file.createNewFile();
    FileWriter writer = new FileWriter(file);
    try {
      writer.write("<?xml version=\"1.0\"?>\n");
      writer.write("<!DOCTYPE cache PUBLIC\n");
      writer.write("  \"" + CacheXml.LATEST_PUBLIC_ID +"\"\n");
      writer.write("  \"" + CacheXml.LATEST_SYSTEM_ID + "\">\n");

      writer.write("<cache>\n");
      writer.write("  <cache-server port=\"" + port + "\" notify-by-subscription=\"false\"/>\n");
      writer.write("  <region name=\"PartitionedRegion\">\n");
      writer.write("    <region-attributes>\n");
      writer.write("      <partition-attributes redundant-copies=\"0\"/>\n");

      writer.write("      <cache-listener>\n");
      writer.write("        <class-name>com.gemstone.gemfire.internal.cache.CacheServerLauncherJUnitTest$SpecialCacheListener</class-name>\n");
      writer.write("      </cache-listener>\n");

      writer.write("    </region-attributes>\n");
      writer.write("  </region>\n");
      writer.write("</cache>\n");

      writer.flush();
    } finally {
      writer.close();
    }
  }

  private void createCacheXml(File dir, String cacheXmlName, int port, String bindAdd,
      int numServers) throws IOException {
    File file = new File(dir, cacheXmlName);
    file.createNewFile();
    FileWriter writer = new FileWriter(file);
    try {
      writer.write("<?xml version=\"1.0\"?>\n");
      writer.write("<!DOCTYPE cache PUBLIC\n");
      writer.write("  \"" + CacheXml.LATEST_PUBLIC_ID + "\"\n");
      writer.write("  \"" + CacheXml.LATEST_SYSTEM_ID + "\">\n");
      writer.write("<cache>\n");
      for (int i = 0; i < numServers; i++) {
        writer.write("  <cache-server port=\"" + port + "\"" );
        if(bindAdd != null){
          writer.write(" bind-address=\"" + bindAdd + "\"" );
        }
        writer.write("/>\n");
      }
      writer.write("<region name=\"rgn\" />\n");
      writer.write("</cache>\n");
      writer.flush();
    }
    finally {
      writer.close();
    }
  }

  public void testStartStatusStop() throws Exception {
    String testName = "testStartStatusStop";

    this.cacheserverDirName = "CacheServerLauncherJUnitTest_"+testName;
    String logName = testName+".log";
    String cacheXmlName = testName+".xml";

    File dir = new File(this.cacheserverDirName);
    dir.mkdir();

    createCacheXml(dir, cacheXmlName, serverPort, false);

    execAndValidate(new String[] {
        "start",
        "-J-D"+CONTROLLER_NAMING_PORT_PROP+"="+controllerNamingPort,
        "-J-D"+CACHESERVER_NAMING_PORT_PROP+"="+cacheserverNamingPort,
        "-J-Xmx"+Runtime.getRuntime().maxMemory(),
        "mcast-port="+mcastPort,
        "log-file="+logName,
        "cache-xml-file="+cacheXmlName,
        "-dir="+this.cacheserverDirName,
        "-classpath="+JTESTS
        },
        "CacheServer pid: \\d+ status: running");

    execAndValidate(new String[] { "status", "-dir="+this.cacheserverDirName },
        "CacheServer pid: \\d+ status: running");

    execAndValidate(new String[] { "stop", "-dir="+this.cacheserverDirName },
        ".*The CacheServer has stopped\\.");
  }

  private File createCacheServerDotStatFile(final File directory) throws Exception {
    final File cacheServerDotStat = new File(directory, ".cacheserver.stat");
    assertTrue("Failed to create the .cacheserver.stat file!", cacheServerDotStat.createNewFile());
    return cacheServerDotStat;
  }

  public void testStartWithExistingCacheServerDotSerFileCheckStatusAndStop() throws Exception {
    final String testCaseName = "testStartWithExistingCacheServerDotSerFileCheckStatusAndStop";

    final File cacheServerDirectory = new File("CacheServerLauncherJUnitTest_" + testCaseName);

    assertTrue("Failed to make working directory (" + cacheServerDirectory.getAbsolutePath() + ") for cacheserver!",
      cacheServerDirectory.mkdir());

    final File cacheServerDotStatFile = createCacheServerDotStatFile(cacheServerDirectory);

    assertTrue("The .cacheserver.stat file could not be found!", cacheServerDotStatFile.exists());

    final String cacheXmlFileName = testCaseName + ".xml";
    final String logFileName = testCaseName + ".log";

    createCacheXml(cacheServerDirectory, cacheXmlFileName, this.serverPort, null, 0);

    execAndValidate(new String[] {
      "start",
      "cache-xml-file="+cacheXmlFileName,
      "log-file="+logFileName,
      "mcast-port="+mcastPort,
      "-server-port="+this.serverPort,
      "-dir="+cacheServerDirectory.getName(),
      "-classpath="+JTESTS
      },
      "CacheServer pid: \\d+ status: running"
    );

    execAndValidate(new String[] { "status", "-dir="+cacheServerDirectory.getName() },
      "CacheServer pid: \\d+ status: running");

    execAndValidate(new String[] { "stop", "-dir="+cacheServerDirectory.getName() },
      ".*The CacheServer has stopped\\.");

    int count = 0;
    final int increment = 500;

    while (cacheServerDotStatFile.exists() && count < 5000) {
      try {
        Thread.sleep(increment);
      }
      catch (InterruptedException ignore) {
      }
      finally {
        count += increment;
      }
    }

    assertFalse("The .cacheserver.stat file was not properly cleaned up!", cacheServerDotStatFile.exists());
  }

  public void testCacheServerTerminatingAbnormally() throws Exception {
    if (!PureJavaMode.isPure()) {
      final String testCaseName = "testCacheServerTerminatingAbnormally";
      final File cacheServerDirectory = new File("CacheServerLauncherJUnitTest_" + testCaseName);

      assertTrue("Failed to make working directory (" + cacheServerDirectory.getAbsolutePath() + ") for cacheserver!",
        cacheServerDirectory.mkdir());

      final String cacheXmlFileName = testCaseName + ".xml";
      final String logFileName = testCaseName + ".log";

      createCacheXml(cacheServerDirectory, cacheXmlFileName, this.serverPort, null, 0);

      final ProcessWrapper processWrapper = new ProcessWrapper(CacheServerLauncher.class, new String[] {
        "start",
        "cache-xml-file="+cacheXmlFileName,
        "log-file="+logFileName,
        "log-level=info",
        "mcast-port="+mcastPort,
        "-server-port="+this.serverPort,
        "-dir="+cacheServerDirectory.getName(),
        "-classpath="+JTESTS
      });

      processWrapper.execute();
      int pid = 0;

      try {
        processWrapper.waitForOutputToMatch("CacheServer pid: \\d+ status: running");
        processWrapper.waitFor();
        final String processOutput = processWrapper.getOutput();
  
        try {
          final Matcher matcher = Pattern.compile("\\d+").matcher(processOutput);
          assertTrue(matcher.find());
          pid = Integer.parseInt(matcher.group());
        }
        catch (Exception e) {
          fail("Failed to read the PID from the CacheServer process!");
        }
  
        assertTrue("Failed to find CacheServer process with PID (" + pid + ")!", OSProcess.exists(pid));
      } finally {
        processWrapper.destroy();
      }

      // now, we will forcefully kill the CacheServer process
      if (OSProcess.exists(pid)) {
        OSProcess.kill(pid);
        Thread.sleep(5000);
      }

      assertFalse("The CacheServer process with PID (" + pid + ") was not successfully terminated!",
        OSProcess.exists(pid));

      final File dotCacheServerDotStatFile = new File(cacheServerDirectory, ".cacheserver.stat");

      // assert that the .cacheserver.stat file remains...
      assertTrue(dotCacheServerDotStatFile.exists());

      int count = 2;

      // assert the status on the previously terminated CacheServer process at least twice to make sure
      // we get the same PID
      while (count-- > 0) {
        Thread.sleep(500);
        execAndValidate(new String[] { "status", "-dir=" + cacheServerDirectory.getName() },
          "CacheServer pid: " + pid + " status: stopped");
      }

      execAndValidate(new String[] { "stop", "-dir="+cacheServerDirectory.getName() },
        "The CacheServer has stopped.");

      execAndValidate(new String[] { "status", "-dir="+cacheServerDirectory.getName() },
        "CacheServer pid: 0 status: stopped");

      assertFalse(dotCacheServerDotStatFile.exists());
    }
  }

  public void testRebalance() throws Exception {
    String testName = "testRebalance";

    this.cacheserverDirName = "CacheServerLauncherJUnitTest_"+testName;
    String logName = testName+".log";
    String cacheXmlName = testName+".xml";

    Registry registry = LocateRegistry.createRegistry(controllerNamingPort);
    RebalanceStatus status = new RebalanceStatus();
    registry.bind(REBALANCE_STATUS_BINDING, status);
    try {

      File dir = new File(this.cacheserverDirName);
      dir.mkdir();

      createCacheXml(dir, cacheXmlName, serverPort, true);

      // connect local member and create PR...
      Properties props = new Properties();
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, ""+mcastPort);
      DistributedSystem system = DistributedSystem.connect(props);
      Cache cache = CacheFactory.create(system);
      AttributesFactory factory = new AttributesFactory();
      factory.setPartitionAttributes(new PartitionAttributesFactory()
          //.setLocalMaxMemory(localMaxMemory)
          //.setTotalNumBuckets(numBuckets)
          //.setRedundantCopies(redundantCopies)
          //.setColocatedWith(colocatedWith)
          .create());

      RegionAttributes attrs = factory.create();
      Region pr = cache.createRegion("PartitionedRegion", attrs);

      // create 6 different buckets...
      int createdBuckets = 6;
      for (int i = 0; i < createdBuckets; i++) {
        pr.put(Integer.valueOf(i), Integer.valueOf(i));
      }

      // assert that there are 6 local buckets
      PartitionedRegionDataStore dataStore
          = ((PartitionedRegion)pr).getDataStore();
      assertEquals(createdBuckets, dataStore.getBucketsManaged());

      execAndValidate(new String[] {
          "start",
          "-J-D"+CONTROLLER_NAMING_PORT_PROP+"="+controllerNamingPort,
          "-J-D"+CACHESERVER_NAMING_PORT_PROP+"="+cacheserverNamingPort,
          "-J-Xmx"+Runtime.getRuntime().maxMemory(),
          "mcast-port="+mcastPort,
          "log-file="+logName,
          "cache-xml-file="+cacheXmlName,
          "-dir="+this.cacheserverDirName,
          "-classpath="+JTESTS,
          "-rebalance"
          },
          "CacheServer pid: \\d+ status: running");

      assertTrue(status.waitForRebalancingToStart(10*1000));

      assertTrue(status.waitForRebalancingToFinish(10*1000));

      // assert that there are 3 local buckets AFTER the rebalance
      assertEquals(createdBuckets/2, dataStore.getBucketsManaged());

      execAndValidate(new String[] { "status", "-dir="+dir },
          "CacheServer pid: \\d+ status: running");

      execAndValidate(new String[] { "stop", "-dir="+dir },
          ".*The CacheServer has stopped\\.");

    } finally {
      UnicastRemoteObject.unexportObject(status, true);
      UnicastRemoteObject.unexportObject(registry, true);
    }
  }

  public void testCreateBuckets() throws Exception {
    String testName = "testCreateBuckets";

    this.cacheserverDirName = "CacheServerLauncherJUnitTest_"+testName;
    String logName = testName+".log";
    String cacheXmlName = testName+".xml";

    Registry registry = LocateRegistry.createRegistry(controllerNamingPort);
    RebalanceStatus status = new RebalanceStatus();
    registry.bind(REBALANCE_STATUS_BINDING, status);
    try {

      File dir = new File(this.cacheserverDirName);
      dir.mkdir();

      createCacheXml(dir, cacheXmlName, serverPort, true);

      // connect local member and create PR...
      Properties props = new Properties();
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, ""+mcastPort);
      DistributedSystem system = DistributedSystem.connect(props);
      Cache cache = CacheFactory.create(system);
      AttributesFactory factory = new AttributesFactory();
      factory.setPartitionAttributes(new PartitionAttributesFactory()
          //.setLocalMaxMemory(localMaxMemory)
          //.setTotalNumBuckets(numBuckets)
          //.setRedundantCopies(redundantCopies)
          //.setColocatedWith(colocatedWith)
          .create());

      RegionAttributes attrs = factory.create();
      Region pr = cache.createRegion("PartitionedRegion", attrs);

      // assert that there are no local buckets local buckets
      PartitionedRegionDataStore dataStore
          = ((PartitionedRegion)pr).getDataStore();
      assertEquals(0, dataStore.getBucketsManaged());

      execAndValidate(new String[] {
          "start",
          "-J-D"+CONTROLLER_NAMING_PORT_PROP+"="+controllerNamingPort,
          "-J-D"+CACHESERVER_NAMING_PORT_PROP+"="+cacheserverNamingPort,
          "-J-D" + CacheServerLauncher.ASSIGN_BUCKETS + "=true",
          "-J-Xmx"+Runtime.getRuntime().maxMemory(),
          "mcast-port="+mcastPort,
          "log-file="+logName,
          "cache-xml-file="+cacheXmlName,
          "-dir="+this.cacheserverDirName,
          "-classpath="+JTESTS,
          "-rebalance"
          },
          "CacheServer pid: \\d+ status: running");

      assertTrue(status.waitForRebalancingToStart(10*1000));

      assertTrue(status.waitForRebalancingToFinish(10*1000));

      // assert that we have created some buckets now
      assertTrue(pr.getAttributes().getPartitionAttributes()
          .getTotalNumBuckets() / 2 >= dataStore.getBucketsManaged());

      execAndValidate(new String[] { "status", "-dir="+dir },
          "CacheServer pid: \\d+ status: running");

      execAndValidate(new String[] { "stop", "-dir="+dir },
          ".*The CacheServer has stopped\\.");

    } finally {
      UnicastRemoteObject.unexportObject(status, true);
      UnicastRemoteObject.unexportObject(registry, true);
    }
  }
  public void testWithoutServerPort() throws Exception {
    String testName = "testWithoutServerPort";

    this.cacheserverDirName = "CacheServerLauncherJUnitTest_"+testName;
    String logName = testName+".log";
    String cacheXmlName = testName+".xml";

      File dir = new File(this.cacheserverDirName);
      dir.mkdir();
      int xmlPort = AvailablePortHelper.getRandomAvailableTCPPort();
      createCacheXml(dir, cacheXmlName, xmlPort, null, 1);
      execAndValidate(new String[] {
          "start",
          "-J-D"+CONTROLLER_NAMING_PORT_PROP+"="+controllerNamingPort,
          "-J-D"+CACHESERVER_NAMING_PORT_PROP+"="+cacheserverNamingPort,
          "-J-Xmx"+Runtime.getRuntime().maxMemory(),
          "mcast-port="+mcastPort,
          "log-file="+logName,
          "cache-xml-file="+cacheXmlName,
          "-dir="+this.cacheserverDirName,
          "-classpath="+JTESTS
          },
          "CacheServer pid: \\d+ status: running");
    execAndValidate(new String[] { "status", "-dir=" + dir },
        "CacheServer pid: \\d+ status: running");

    ClientCache cache = new ClientCacheFactory().create();
    ClientRegionFactory<Integer, Integer> fact = cache
        .createClientRegionFactory(ClientRegionShortcut.PROXY);
    Pool p = PoolManager.createFactory().addServer("localhost", xmlPort)
        .create("cslPool");
    fact.setPoolName(p.getName());
    Region<Integer, Integer> rgn = fact.create("rgn");
    List<InetSocketAddress> servers = p.getServers();
    Assert.assertTrue(servers.size() == 1);
    Assert.assertTrue(servers.iterator().next().getPort() == xmlPort);
    rgn.put(1, 1); // put should be successful

    execAndValidate(new String[] { "stop", "-dir=" + dir },
        "The CacheServer has stopped\\.");

  }

  public void testServerPortOneCacheServer() throws Exception {
    String testName = "testServerPortOneCacheServer";

    this.cacheserverDirName = "CacheServerLauncherJUnitTest_"+testName;
    String logName = testName+".log";
    String cacheXmlName = testName+".xml";

      File dir = new File(this.cacheserverDirName);
      dir.mkdir();
      int xmlPort = AvailablePortHelper.getRandomAvailableTCPPort();
      createCacheXml(dir, cacheXmlName, xmlPort, null, 1);
      int commandPort = AvailablePortHelper.getRandomAvailableTCPPort();
      execAndValidate(new String[] {
          "start",
          "-J-D"+CONTROLLER_NAMING_PORT_PROP+"="+controllerNamingPort,
          "-J-D"+CACHESERVER_NAMING_PORT_PROP+"="+cacheserverNamingPort,
          "-J-Xmx"+Runtime.getRuntime().maxMemory(),
          "mcast-port="+mcastPort,
          "log-file="+logName,
          "cache-xml-file="+cacheXmlName,
          "-dir="+this.cacheserverDirName,
          "-classpath="+JTESTS,
          "-server-port="+ commandPort
          },
          "CacheServer pid: \\d+ status: running");
    execAndValidate(new String[] { "status", "-dir=" + dir },
        "CacheServer pid: \\d+ status: running");

    ClientCache cache = new ClientCacheFactory().create();
    ClientRegionFactory<Integer, Integer> fact = cache
        .createClientRegionFactory(ClientRegionShortcut.PROXY);
    Pool p = PoolManager.createFactory().addServer("localhost", commandPort)
        .create("cslPool");
    fact.setPoolName(p.getName());
    Region<Integer, Integer> rgn = fact.create("rgn");
    List<InetSocketAddress> servers = p.getServers();
    Assert.assertTrue(servers.size() == 1);
    Assert.assertTrue(servers.iterator().next().getPort() == commandPort);
    rgn.put(1, 1); // put should be successful

    execAndValidate(new String[] { "stop", "-dir=" + dir },
        "The CacheServer has stopped\\.");

  }

  public void testServerPortNoCacheServer() throws Exception {
    String testName = "testServerPortNoCacheServer";

    this.cacheserverDirName = "CacheServerLauncherJUnitTest_"+testName;
    String logName = testName+".log";
    String cacheXmlName = testName+".xml";

      File dir = new File(this.cacheserverDirName);
      dir.mkdir();
      createCacheXml(dir, cacheXmlName, 0, null, 0);
      int commandPort = AvailablePortHelper.getRandomAvailableTCPPort();
      execAndValidate(new String[] {
          "start",
          "-J-D"+CONTROLLER_NAMING_PORT_PROP+"="+controllerNamingPort,
          "-J-D"+CACHESERVER_NAMING_PORT_PROP+"="+cacheserverNamingPort,
          "-J-Xmx"+Runtime.getRuntime().maxMemory(),
          "mcast-port="+mcastPort,
          "log-file="+logName,
          "cache-xml-file="+cacheXmlName,
          "-dir="+this.cacheserverDirName,
          "-classpath="+JTESTS,
          "-server-port="+commandPort,
          "-server-bind-address="+ InetAddress.getLocalHost().getHostName()
          },
          "CacheServer pid: \\d+ status: running");
    execAndValidate(new String[] { "status", "-dir=" + dir },
        "CacheServer pid: \\d+ status: running");

    ClientCache cache = new ClientCacheFactory().create();
    ClientRegionFactory<Integer, Integer> fact = cache
        .createClientRegionFactory(ClientRegionShortcut.PROXY);
    Pool p = PoolManager.createFactory().addServer(InetAddress.getLocalHost().getHostName(), commandPort)
        .create("cslPool");
    fact.setPoolName(p.getName());
    Region<Integer, Integer> rgn = fact.create("rgn");
    List<InetSocketAddress> servers = p.getServers();
    Assert.assertTrue(servers.size() == 1);
    Assert.assertTrue(servers.iterator().next().getPort() == commandPort);
    rgn.put(1, 1); // put should be successful

    execAndValidate(new String[] { "stop", "-dir=" + dir },
        "The CacheServer has stopped\\.");

  }

  public static class SpecialCacheListener<K, V>
  extends CacheListenerAdapter<K, V> implements Declarable {
    static final int CONTROLLER_NAMING_PORT
        = Integer.getInteger(CONTROLLER_NAMING_PORT_PROP).intValue();
    static final int CACHESERVER_NAMING_PORT
        = Integer.getInteger(CACHESERVER_NAMING_PORT_PROP).intValue();
    public SpecialCacheListener() {
      try {
        Registry registry = LocateRegistry.createRegistry(CACHESERVER_NAMING_PORT);
        FailSafe failsafe = new FailSafe();
        registry.bind(FAILSAFE_BINDING, failsafe);
      } catch (RemoteException ignore) {
        throw new InternalGemFireError(ignore);
      } catch (AlreadyBoundException ignore) {
        throw new InternalGemFireError(ignore);
      }

      InternalResourceManager.setResourceObserver(
          new ResourceObserverAdapter() {

        public void rebalancingOrRecoveryStarted(Region region) {
          try {
            InternalDistributedSystem.getAnyInstance().getLogWriter().info("SpecialCacheListener#rebalancingStarted on " + region);
            Registry registry = LocateRegistry.getRegistry(CONTROLLER_NAMING_PORT);
            RebalanceStatusRemote status = (RebalanceStatusRemote) registry.lookup(REBALANCE_STATUS_BINDING);
            if (region.getName().contains("PartitionedRegion")) { // Fix for #43657
              status.rebalancingStarted();
            }
          } catch (RemoteException ignore) {
            throw new InternalGemFireError(ignore);
          } catch (NotBoundException ignore) {
            throw new InternalGemFireError(ignore);
          }
        }

        public void rebalancingOrRecoveryFinished(Region region) {
          try {
            InternalDistributedSystem.getAnyInstance().getLogWriter().info("SpecialCacheListener#rebalancingFinished on " + region);
            Registry registry = LocateRegistry.getRegistry(CONTROLLER_NAMING_PORT);
            RebalanceStatusRemote status = (RebalanceStatusRemote) registry.lookup(REBALANCE_STATUS_BINDING);
            if (region.getName().contains("PartitionedRegion")) { // Fix for #43657
              status.rebalancingFinished();
            }
          } catch (RemoteException ignore) {
            throw new InternalGemFireError(ignore);
          } catch (NotBoundException ignore) {
            throw new InternalGemFireError(ignore);
          }
        }
      });
    }
    public void init(Properties props) {
    }
  }

  public interface RebalanceStatusRemote extends Remote {
    public void rebalancingStarted() throws RemoteException;
    public void rebalancingFinished() throws RemoteException;
  }

  public static class RebalanceStatus
  extends UnicastRemoteObject implements RebalanceStatusRemote {
    private final Object syncMe = new Object();
    private boolean started = false;
    private boolean finished = false;

    public RebalanceStatus() throws RemoteException {
      super();
    }
    public void rebalancingStarted() throws RemoteException {
      synchronized (this.syncMe) {
        this.started = true;
        this.syncMe.notifyAll();
      }
    }
    public void rebalancingFinished() throws RemoteException {
      synchronized (this.syncMe) {
        this.finished = true;
        this.syncMe.notifyAll();
      }
    }
    public boolean waitForRebalancingToStart(long timeout)
    throws InterruptedException {
      synchronized (this.syncMe) {
        StopWatch timer = new StopWatch(true);
        long timeLeft = timeout - timer.elapsedTimeMillis();
        while (!this.started) {
          if (timeLeft <= 0) {
            break;
          }
          this.syncMe.wait(timeLeft);
        }
        return this.started;
      }
    }
    public boolean waitForRebalancingToFinish(long timeout)
    throws InterruptedException {
      synchronized (this.syncMe) {
        StopWatch timer = new StopWatch(true);
        long timeLeft = timeout - timer.elapsedTimeMillis();
        while (!this.finished) {
          if (timeLeft <= 0) {
            break;
          }
          this.syncMe.wait(timeLeft);
        }
        return this.finished;
      }
    }
  }

  public interface FailSafeRemote extends Remote {
    public void kill() throws RemoteException;
  }

  public static class FailSafe
  extends UnicastRemoteObject implements FailSafeRemote {
    static final int CACHESERVER_NAMING_PORT
        = Integer.getInteger(CACHESERVER_NAMING_PORT_PROP).intValue();
    public FailSafe() throws RemoteException {
      super();
    }
    public void kill() throws RemoteException {
      System.exit(0);
    }
  }
}
