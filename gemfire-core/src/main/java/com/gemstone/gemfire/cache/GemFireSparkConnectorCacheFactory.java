package com.gemstone.gemfire.cache;

import java.util.Properties;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.CacheWriterException;
import com.gemstone.gemfire.cache.GatewayException;
import com.gemstone.gemfire.cache.RegionExistsException;
import com.gemstone.gemfire.cache.TimeoutException;
import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.GemFireSparkConnectorCacheImpl;


public class GemFireSparkConnectorCacheFactory extends CacheFactory {

  public GemFireSparkConnectorCacheFactory() {
    super();
  }

  public GemFireSparkConnectorCacheFactory(Properties props) {
    super(props);
  }


  public Cache create(PoolFactory pf)
         throws TimeoutException, CacheWriterException,
      GatewayException,
      RegionExistsException
  {
       synchronized(CacheFactory.class) {
          DistributedSystem ds = null;
           if (this.dsProps.isEmpty()) {
              // any ds will do
                    ds = InternalDistributedSystem.getConnectedInstance();
              }
           if (ds == null) {
               ds = DistributedSystem.connect(this.dsProps);
              }
            return GemFireSparkConnectorCacheImpl.create(pf, ds, cacheConfig);
          }
      }
}
