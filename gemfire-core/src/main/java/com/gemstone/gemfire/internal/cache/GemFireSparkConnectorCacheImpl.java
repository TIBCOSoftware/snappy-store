package com.gemstone.gemfire.internal.cache;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.internal.locator.GetAllServersRequest;
import com.gemstone.gemfire.cache.client.internal.locator.GetAllServersResponse;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.ServerLocation;
import com.gemstone.gemfire.distributed.internal.tcpserver.TcpClient;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.admin.remote.DistributionLocatorId;

/**
 * Created by ashahid on 3/30/17.
 */
public class GemFireSparkConnectorCacheImpl extends GemFireCacheImpl {

  // The properties need to be passed from the lead & servers as
  // snappydata.store.connector.gemfire-grid-one and snappydata.store.connector.gemfire.pingInterval etc
  public static final String connectorPrefix = "snappydata.connector.";
  public static final String gfeGridNamePrefix = connectorPrefix + "gemfire-grid";
  public static final String gfeGridPropsPrefix = connectorPrefix + "grid.";
  public static final String propFreeConnTimeout = "freeConnectionTimeout";
  public static final String propLoadConditioningInterval = "loadConditioningInterval";
  public static final String propSocketBufferSize = "socketBufferSize";
  public static final String propThreadLocalConnections = "threadLocalConnections";
  public static final String propReadTimeout = "readTimeout";
  public static final String propMinConnections = "minConnections";
  public static final String propMaxConnections = "maxConnections";
  public static final String propIdleTimeout = "idleTimeout";
  public static final String propRetryAttempts = "retryAttempts";
  public static final String propPingInterval = "pingInterval";
  public static final String propStatisticInterval = "statisticInterval";
  public static final String propServerGroup = "serverGroup";
  public static final String propPRSingleHopEnabled = "prSingleHopEnabled";
  public static final String propMultiUserAuthentication = "multiUserAuthentication";

  private final Map<String, String> gfeGridMappings;
  private final Map<String, String> gfeGridPoolProps;
  private final String defaultGrid;


  public GemFireSparkConnectorCacheImpl(PoolFactory pf, Map<String, String> gfeGridMappings,
      Map<String, String> gfeGridPoolProps,
      DistributedSystem system, CacheConfig cacheConfig) {
    super(false, pf, system, cacheConfig);
    this.defaultGrid = gfeGridMappings.remove(gfeGridNamePrefix);
    this.gfeGridMappings = gfeGridMappings;
    this.gfeGridPoolProps = gfeGridPoolProps;
  }

  public static GemFireCacheImpl create(Map<String, String> gfeGridMappings,
      Map<String, String> gfeGridPoolProps,
      DistributedSystem system, CacheConfig cacheConfig) {

    return new GemFireSparkConnectorCacheImpl(null, gfeGridMappings, gfeGridPoolProps, system, cacheConfig).init();
  }

  public Map<String, String> getGfeGridPoolProps() {
    return Collections.unmodifiableMap(this.gfeGridPoolProps);
  }

  public Map<String, String> getGfeGridMappings() {
    return Collections.unmodifiableMap(this.gfeGridMappings);
  }

  public String getDefaultGrid() {
    return this.defaultGrid;
  }

  @Override
  public boolean hasPool() {
    return true;
  }

  @Override
  protected GemFireCacheImpl init() {
    super.init();
    //check if system will have a default pool
    boolean willHaveDefaultPool = this.getDefaultPool() != null || this.defaultGrid != null;

    AttributesFactory af = new AttributesFactory();
    af.setDataPolicy(DataPolicy.EMPTY);
    UserSpecifiedRegionAttributes ra = (UserSpecifiedRegionAttributes)af.create();
    ra.requiresPoolName = !willHaveDefaultPool;
    this.setRegionAttributes(ClientRegionShortcut.PROXY.toString(), ra);
    this.clientpf = null;
    return this;
  }

  @Override
  protected void checkValidityForPool() {
  }

  public void configureDefaultPool() {
    if (this.defaultGrid != null) {
      PoolFactory gridDefaultPool = createAndConfigurePoolFactory(this.defaultGrid, this.gfeGridPoolProps);
      if (this.getDefaultPool() == null) {
        this.clientpf = gridDefaultPool;
        this.determineDefaultPool();
        this.clientpf = null;
      } else {
        gridDefaultPool.create("Default-Grid-Pool");
      }
    }
  }

  public QueryService getRemoteGemFireQueryService() {
    Pool p = getDefaultPool();
    if (p == null) {
      throw new IllegalStateException("Client cache does not have a default pool. " +
          "Use getQueryService(String poolName) instead.");
    } else {
      return p.getQueryService();
    }
  }


  public PoolFactory createAndConfigurePoolFactory(String remoteLocators,
      Map<String, String> gfeGridPoolProps) {

    PoolFactory pf = PoolManager.createFactory();

    setPoolProps(pf, gfeGridPoolProps);

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
        pf.addLocator(locator.getHost().getHostName(), locator.getPort());
      }
    }
    return pf;
  }

  private void setPoolProps(PoolFactory pf, Map<String, String> gfeGridPoolProps) {
    String val = gfeGridPoolProps.get(gfeGridPropsPrefix + propFreeConnTimeout);
    if (val != null) {
      pf.setFreeConnectionTimeout(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propLoadConditioningInterval);
    if (val != null) {
      pf.setLoadConditioningInterval(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propMultiUserAuthentication);
    if (val != null) {
      pf.setMultiuserAuthentication(Boolean.parseBoolean(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propSocketBufferSize);
    if (val != null) {
      pf.setSocketBufferSize(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propThreadLocalConnections);
    if (val != null) {
      pf.setThreadLocalConnections(Boolean.parseBoolean(val));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propReadTimeout);
    if (val != null) {
      pf.setReadTimeout(Integer.parseInt(val.trim()));
    }


    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propMinConnections);
    if (val != null) {
      pf.setMinConnections(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propMaxConnections);
    if (val != null) {
      pf.setMaxConnections(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propIdleTimeout);
    if (val != null) {
      pf.setIdleTimeout(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propRetryAttempts);
    if (val != null) {
      pf.setRetryAttempts(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propPingInterval);
    if (val != null) {
      pf.setPingInterval(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propStatisticInterval);
    if (val != null) {
      pf.setStatisticInterval(Integer.parseInt(val.trim()));
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propServerGroup);
    if (val != null) {
      pf.setServerGroup(val.trim());
    }

    val = gfeGridPoolProps.get(gfeGridPropsPrefix + propPRSingleHopEnabled);
    if (val != null) {
      pf.setPRSingleHopEnabled(Boolean.parseBoolean(val.trim()));
    } else {
      pf.setPRSingleHopEnabled(true);
    }
  }

}
