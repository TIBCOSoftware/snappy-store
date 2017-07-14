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
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.admin.remote.DistributionLocatorId;
import com.gemstone.gemfire.internal.cache.GemFireSparkConnectorCacheImpl;


public class GemFireSparkConnectorCacheFactory extends CacheFactory {

  public static final String gfeGridNamePrefix = "spark.gemfire-grid";
  public static final String gfeGridPropsPrefix = "spark.gemfire.grid.";
  public static final String propFreeConnTimeout = "freeConnectionTimeout";
  public static final String propLoadConditioningInterval= "loadConditioningInterval";
  public static final String propSocketBufferSize =  "socketBufferSize";
  public static final String propThreadLocalConnections =  "threadLocalConnections";
  public static final String propReadTimeout =  "readTimeout";
  public static final String propMinConnections =  "minConnections";
  public static final String propMaxConnections =  "maxConnections";
  public static final String propIdleTimeout =  "idleTimeout";
  public static final String propRetryAttempts =  "retryAttempts";
  public static final String propPingInterval =  "pingInterval";
  public static final String propStatisticInterval =  "statisticInterval";
  public static final String propServerGroup =  "serverGroup";
  public static final String propPRSingleHopEnabled =  "prSingleHopEnabled";




  private final Map<String, String> gfeGridMappings;
  private final Map<String, String> gfeGridPoolProps;


  public GemFireSparkConnectorCacheFactory() {
    super();
    this.gfeGridMappings = null;
    this.gfeGridPoolProps = null;
  }

  public GemFireSparkConnectorCacheFactory(Properties props, Map<String, String> gfeGridMappings,
      Map<String, String> gfeGridPoolProps) {
    super(props);
    this.gfeGridMappings = gfeGridMappings;
    this.gfeGridPoolProps = gfeGridPoolProps;
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
       String defaultGrid =  gfeGridMappings.remove(gfeGridNamePrefix);
      if (defaultGrid != null) {
        defaultPF = this.createAndConfigurePoolFactory(defaultGrid);
      }
      Cache cache = GemFireSparkConnectorCacheImpl.create(defaultPF, gfeGridMappings, ds, cacheConfig);

      for (Map.Entry<String, String> gridEntry : gfeGridMappings.entrySet()) {
        String key = gridEntry.getKey().trim();
        String gridName =   key.split("\\.")[2]; //key.substring(key.indexOf('.') + 1);
        String locators = gridEntry.getValue().trim();
        PoolFactory pf = this.createAndConfigurePoolFactory(locators);
        pf.create(gridName);
      }
      return cache;
    }
  }


  private PoolFactory createAndConfigurePoolFactory(String remoteLocators) {

    PoolFactory pf = PoolManager.createFactory();

    this.setPoolProps(pf);

    StringTokenizer remoteLocatorsTokenizer = new StringTokenizer(remoteLocators, ",");
    DistributionLocatorId[] locators = new DistributionLocatorId[remoteLocatorsTokenizer
        .countTokens()];
    int i = 0;
    while (remoteLocatorsTokenizer.hasMoreTokens()) {
      locators[i++] = new DistributionLocatorId(remoteLocatorsTokenizer.nextToken().trim());
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
            SocketCreator.getLocalHost().getCanonicalHostName();
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

  private void setPoolProps(PoolFactory pf) {
    String val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propFreeConnTimeout );
    if (val != null) {
      pf.setFreeConnectionTimeout(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propLoadConditioningInterval );
    if (val != null) {
      pf.setLoadConditioningInterval(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propSocketBufferSize );
    if (val != null) {
      pf.setSocketBufferSize(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propThreadLocalConnections );
    if (val != null) {
      pf.setThreadLocalConnections(Boolean.parseBoolean(val));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propReadTimeout );
    if (val != null) {
      pf.setReadTimeout(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propMinConnections );
    if (val != null) {
      pf.setMinConnections(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propMaxConnections );
    if (val != null) {
      pf.setMaxConnections(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propIdleTimeout );
    if (val != null) {
      pf.setIdleTimeout(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propRetryAttempts );
    if (val != null) {
      pf.setRetryAttempts(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propPingInterval );
    if (val != null) {
      pf.setPingInterval(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propStatisticInterval );
    if (val != null) {
      pf.setStatisticInterval(Integer.parseInt(val.trim()));
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propServerGroup );
    if (val != null) {
      pf.setServerGroup(val.trim());
    }

    val = this.gfeGridPoolProps.get(gfeGridPropsPrefix + propPRSingleHopEnabled );
    if (val != null) {
      pf.setPRSingleHopEnabled(Boolean.parseBoolean(val.trim()));
    } else {
      pf.setPRSingleHopEnabled(true);
    }
  }

}
