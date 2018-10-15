/*
 *
 * Derby - Class BaseTestSetup
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
 * either express or implied. See the License for the specific 
 * language governing permissions and limitations under the License.
 */
package org.apache.derbyTesting.junit;

import java.sql.SQLException;

import com.pivotal.gemfirexd.FabricServiceManager;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestResult;

/**
 * TestSetup/Decorator base class for Derby's JUnit
 * tests. Installs the security manager according
 * to the configuration before executing any setup
 * or tests. Matches the security manager setup
 * provided by BaseTestCase.
 *
 */
public abstract class BaseTestSetup extends TestSetup {
    
    protected BaseTestSetup(Test test) {
        super(test);
    }

    /**
     * Setup the security manager for this Derby decorator/TestSetup
     * and then call the part's run method to run the decorator and
     * the test it wraps.
     */
    public final void run(TestResult result)
    {
        // install a default security manager if one has not already been
        // installed
        if ( System.getSecurityManager() == null )
        {
            if (TestConfiguration.getCurrent().defaultSecurityManagerSetup())
            {
                BaseTestCase.assertSecurityManager();
            }
        }
        
        super.run(result);
    }

// GemStone changes BEGIN
    public final void invokeOnlySetup() throws Exception {
       setUp();
    }
    
    protected static void bootGemFireXDServer() throws SQLException {
      java.util.Properties props = new java.util.Properties();
      props.setProperty("mcast-port", "0");
      System.setProperty("gemfire.mcast-port", "0");
      FabricServiceManager.getFabricServerInstance().start(props, true);
    }

    protected static void stopGemFireXDServer() throws SQLException {
      // stop any existing embedded instance
      System.clearProperty("gemfire.mcast-port");
      try {
        FabricServiceManager.getFabricServerInstance().stop(null);
      } catch (Exception e) {
        // ignored
      }
    }

// GemStone changes END

}
