package com.gemstone.gemfire.cache;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;

import com.gemstone.gemfire.GemFireConfigException;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.GemFireSparkConnectorCacheImpl;


public class GemFireSparkConnectorCacheFactory extends CacheFactory {
  private final Map<String, String> gfeGridMappings;
  private final Map<String, String> gfeGridPoolProps;
  private static final String initHelperClass =
          "io.snappydata.spark.gemfire.connector.dsinit.internal.DistributedSystemInitializerHelper";

  private static final String disconnectListenerClass =
          "io.snappydata.spark.gemfire.connector.dsinit.internal.DisconnectListener";

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


      try {
        Class connClass = Class.forName(initHelperClass);
        Constructor c = connClass.getConstructor(new Class[]{CacheFactory.class});
        Object obj = c.newInstance(new Object[]{this});
        Method m = connClass.getMethod("configure", new Class[0]);
        m.invoke(obj, new Object[0]);
      } catch(Exception e) {
         throw new GemFireConfigException("Problem configuring distributed system",e);
      }

    synchronized (CacheFactory.class) {
      DistributedSystem ds = null;
      if (this.dsProps.isEmpty()) {
        // any ds will do
        ds = InternalDistributedSystem.getConnectedInstance();
      }
      if (ds == null) {
        ds = DistributedSystem.connect(this.dsProps);
      }
      try {
        InternalDistributedSystem.DisconnectListener disconnectListener =
                (InternalDistributedSystem.DisconnectListener)Class.forName(disconnectListenerClass).
                        newInstance();
        ((InternalDistributedSystem)ds).addDisconnectListener(disconnectListener);
      } catch(Exception e) {
        throw new GemFireConfigException("Problem configuring distributed system",e);
      }
      Cache cache = GemFireSparkConnectorCacheImpl.create(gfeGridMappings, this.gfeGridPoolProps,
          ds, cacheConfig);

      return cache;
    }
  }

}
