package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.distributed.DistributedSystem;

/**
 * Created by ashahid on 3/30/17.
 */
public class GemFireSparkConnectorCacheImpl extends GemFireCacheImpl {

  public GemFireSparkConnectorCacheImpl(PoolFactory pf, DistributedSystem system, CacheConfig cacheConfig) {
    super(false, pf, system, cacheConfig);
  }

  public static GemFireCacheImpl create(PoolFactory pf, DistributedSystem system,
      CacheConfig cacheConfig) {
    return new GemFireSparkConnectorCacheImpl(pf, system, cacheConfig).init();
  }

  @Override
  protected GemFireCacheImpl init() {
    PoolFactory temp = this.clientpf;
    super.init();
    //re assign the clientPf which would otherwise be nullified by the super call
    this.clientpf = temp;

    this.determineDefaultPool();
    AttributesFactory af = new AttributesFactory();
    af.setDataPolicy(DataPolicy.EMPTY);
    UserSpecifiedRegionAttributes ra = (UserSpecifiedRegionAttributes)af.create();
    ra.requiresPoolName = true;
    this.setRegionAttributes(ClientRegionShortcut.PROXY.toString(), ra);
    this.clientpf = null;
    return this;
  }

  @Override
  protected void checkValidityForPool() {
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

}
