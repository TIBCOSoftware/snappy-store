package com.gemstone.gemfire.cache;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.internal.locator.GetAllServersRequest;
import com.gemstone.gemfire.cache.client.internal.locator.GetAllServersResponse;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.distributed.internal.tcpserver.TcpClient;
import com.gemstone.gemfire.internal.admin.remote.DistributionLocatorId;
import com.gemstone.gemfire.internal.cache.GemFireSparkConnectorCacheImpl;


public class GemFireSparkConnectorCacheFactory extends CacheFactory {

  private final Map<String, String> gfeGridMappings;
  private final String remoteGemFireLocators;

  public GemFireSparkConnectorCacheFactory() {
    super();
    this.gfeGridMappings = null;
    this.remoteGemFireLocators = null;
  }

  public GemFireSparkConnectorCacheFactory(Properties props, String remoteGemfireLocators,
      Map<String, String> gfeGridMappings) {
    super(props);
    this.gfeGridMappings = gfeGridMappings;
    this.remoteGemFireLocators = remoteGemfireLocators;
  }


  public Cache create()
      throws TimeoutException, CacheWriterException,
      GatewayException,
      RegionExistsException {
    synchronized (CacheFactory.class) {
      DistributedSystem ds = null;
      if (this.dsProps.isEmpty()) {
        // any ds will do
        ds = InternalDistributedSystem.getConnectedInstance();
      }
      if (ds == null) {
        ds = DistributedSystem.connect(this.dsProps);
      }
      PoolFactory defaultPF = null;

      if (remoteGemFireLocators != null) {
        defaultPF = this.createAndConfigurePoolFactory(remoteGemFireLocators);
      }
      Cache cache = GemFireSparkConnectorCacheImpl.create(defaultPF, gfeGridMappings, ds, cacheConfig);

      for (Map.Entry<String, String> gridEntry : gfeGridMappings.entrySet()) {
        String key = gridEntry.getKey().trim();
        String gridName = key.substring(key.indexOf('.') + 1);
        String locators = gridEntry.getValue().trim();
        PoolFactory pf = this.createAndConfigurePoolFactory(locators);
        pf.create(gridName);
      }
      return cache;
    }
  }


  private PoolFactory createAndConfigurePoolFactory(String remoteLocators) {
    PoolFactory pf = PoolManager.createFactory();
    pf.setReadTimeout(30000);

    StringTokenizer remoteLocatorsTokenizer = new StringTokenizer(remoteLocators, ",");
    DistributionLocatorId[] locators = new DistributionLocatorId[remoteLocatorsTokenizer
        .countTokens()];
    int i = 0;
    while (remoteLocatorsTokenizer.hasMoreTokens()) {
      locators[i++] = new DistributionLocatorId(remoteLocatorsTokenizer.nextToken());
    }
    List<ServerLocation> servers = new ArrayList<ServerLocation>();
    for (DistributionLocatorId locator : locators) {
      try {

        InetSocketAddress addr = new InetSocketAddress(locator.getHost(), locator.getPort());
        GetAllServersRequest req = new GetAllServersRequest("");
        Object res = TcpClient.requestToServer(addr.getAddress(), addr.getPort(), req, 2000);
        if (res != null) {
          servers.addAll((List<ServerLocation>)((GetAllServersResponse)res).getServers());
        }
      } catch (Exception e) {
        System.out.println("Unable to get remote gfe servers from locator = " + locator);
      }
    }

    List<ServerLocation> prefServers = null;
    if (servers.size() > 0) {
      String sparkIp = System.getenv("SPARK_LOCAL_IP");
      String hostName = null;
      try {
        hostName = sparkIp != null ? InetAddress.getByName(sparkIp).getCanonicalHostName() :
            InetAddress.getLocalHost().getCanonicalHostName();
      } catch (Exception e) {
        hostName = "";
      }
      int spacing = servers.size() / 3;
      if (spacing != 0) {
        prefServers = new ArrayList<ServerLocation>();
        ServerLocation localServer = null;
        for (int j = 0; j < servers.size(); ++j) {
          ServerLocation sl = servers.get(j);
          if (localServer == null && sl.getHostName().equals(hostName)) {
            localServer = sl;
          } else {
            if (j + 1 % spacing == 0) {
              prefServers.add(servers.get(j));
            }
          }
        }
        if (localServer != null) {
          if (prefServers.size() < 3) {
            prefServers.add(0, localServer);
          } else {
            prefServers.set(0, localServer);
          }
        }
      } else {
        // bring local server if any to start position
        int localServerIndex = -1;
        for (int j = 0; j < servers.size(); ++j) {
          ServerLocation sl = servers.get(j);
          if (localServerIndex == -1 && sl.getHostName().equals(hostName)) {
            localServerIndex = j;
            break;
          }
        }
        if (localServerIndex != -1) {
          ServerLocation local = servers.remove(localServerIndex);
          servers.add(0, local);
        }
        prefServers = servers;
      }

      for (ServerLocation srvr : prefServers) {
        pf.addServer(srvr.getHostName(), srvr.getPort());
      }
    } else {
      for (DistributionLocatorId locator : locators) {
        pf.addLocator(locator.getBindAddress(), locator.getPort());
      }
    }
    return pf;
  }

}
