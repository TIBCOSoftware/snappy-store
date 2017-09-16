package com.gemstone.gemfire.cache;

import java.util.Map;
import java.util.Properties;

import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.GemFireSparkConnectorCacheImpl;


public class GemFireSparkConnectorCacheFactory extends CacheFactory {
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

      Cache cache = GemFireSparkConnectorCacheImpl.create(gfeGridMappings, this.gfeGridPoolProps,
          ds, cacheConfig);

      return cache;
    }
  }

}
