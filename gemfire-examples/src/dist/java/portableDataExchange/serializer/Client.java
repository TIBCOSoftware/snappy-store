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
package portableDataExchange.serializer;

import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.query.Query;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.pdx.PdxSerializer;
import com.gemstone.gemfire.pdx.ReflectionBasedAutoSerializer;

/**
 * A sample gemfire client that writes and queries PositionPdx objects on a 
 * server.
 * <p>
 * 
 * @author GemStone Systems, Inc.
 * @since 6.6
 */
public class Client {
  
  public static void main(String[] args) throws Exception {
    PdxSerializer serializer;
    if (args.length > 0 && args[0].equals("auto")) {
      //Use the auto serializer. This serializer serializes classes 
      //using reflection. Here, we tell the auto serializer to handle
      //all classes in the portableDataExchange.serializer package.
      String[] allowedClasses = new String[]{ "portableDataExchange.serializer.*" };
      serializer = new ReflectionBasedAutoSerializer(allowedClasses);
    } 
    else {
      //Use a manually defined serializer.
      serializer = new ExamplePdxSerializer();
    }
    
    ClientCacheFactory cf = new ClientCacheFactory();
    cf.set("cache-xml-file", "client.xml");
    cf.setPdxSerializer(serializer);
    ClientCache cache = cf.create();

    System.out.println("Created the GemFire Cache");
    
    // Get the example Region from the Cache which is declared in the Cache XML file.
    Region<String, PortfolioPdx> region = cache.<String, PortfolioPdx>getRegion("Portfolios");

    System.out.println("Obtained the Region from the Cache");

    // Populate the Region with some PortfolioPdx objects.
    PortfolioPdx port1 = new PortfolioPdx(1 /*ID*/, 10 /*size*/);
    PortfolioPdx port2 = new PortfolioPdx(2 /*ID*/, 20 /*size*/);
    PortfolioPdx port3 = new PortfolioPdx(3 /*ID*/, 30 /*size*/);
    region.put("Key1", port1);
    region.put("Key2", port2);
    region.put("Key3", port3);

    System.out.println("Populated some PortfolioPdx Objects");

    //find the pool
    Pool pool = PoolManager.find("examplePool");

    // Get the QueryService from the pool
    QueryService qrySvc = pool.getQueryService();

    System.out.println("Got the QueryService from the Pool");

    // Execute a Query which returns a ResultSet.    
    Query qry = qrySvc.newQuery("SELECT DISTINCT * FROM /Portfolios");
    SelectResults<PortfolioPdx> results = (SelectResults<PortfolioPdx>) qry.execute();

    System.out.println("ResultSet Query returned " + results.size() + " rows");

    // Execute a Query which returns a StructSet.
    QueryService qrySvc1 = pool.getQueryService();
    Query qry1 = qrySvc1.newQuery("SELECT DISTINCT id, status FROM /Portfolios WHERE id > 1");
    SelectResults<Struct> results1 = (SelectResults<Struct>) qry1.execute();

    System.out.println("StructSet Query returned " + results1.size() + " rows");

    // Iterate through the rows of the query result.
    int rowCount = 0;
    for (Struct si : results1) {
      rowCount++;
      System.out.println("Row " + rowCount + " Column 0 is named " + si.getStructType().getFieldNames()[0] + ", value is " + si.getFieldValues()[0]);
      System.out.println("Row " + rowCount + " Column 1 is named " + si.getStructType().getFieldNames()[1] + ", value is " + si.getFieldValues()[1]);
    }

    // Close the GemFire Cache.
    cache.close();

    System.out.println("Closed the GemFire Cache");
  }
}
