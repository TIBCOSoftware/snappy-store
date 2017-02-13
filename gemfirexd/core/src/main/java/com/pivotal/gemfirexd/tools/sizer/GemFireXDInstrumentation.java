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
package com.pivotal.gemfirexd.tools.sizer;

import java.lang.instrument.Instrumentation;

import com.gemstone.gemfire.internal.size.ReflectionSingleObjectSizer;
import com.gemstone.gemfire.internal.size.SingleObjectSizer;
/**
 * Class that honors <B>"-javaagent:.../lib/gemfirexd-tools.jar"</B>
 * It gets a handle to instrumentation agent for estimating 
 * object size in memory.
 * <BR>
 * If any of the classes below is altered, please test it in following way
 * as I am yet to find java agent instrumentation before starting
 * VM in a DUnit, a hydra test will be required.<BR>
 * <BR>
 * <ul>
 * <li>GemFireXDInstrumentation</li>
 * <li>ObjectSizer</li>
 * </ul>
 * <BR>
 * Second mechanism of instrumenting after JVM is launched doesn't works
 * correctly in current version 1.6<BR>
 * <BR>
 * 1. start locator <BR>
 * 2. start server with -javaagent:.../lib/gemfirexd.jar<BR>
 * 3. start ij<BR>
 * 4. execute 'select * from sys.memoryanalytics"<BR>
 * <BR>
 * @author soubhikc
 * @since 1.6
 */
public final class GemFireXDInstrumentation implements SingleObjectSizer {

  private static Instrumentation instAgent = null;

  private static String agentArguments = null;
  
  private static final GemFireXDInstrumentation theInstance;
  
  static {
    theInstance = new GemFireXDInstrumentation();
  }

  public static void premain(String agentArgs, Instrumentation inst) {
    instAgent = inst;
    agentArguments = agentArgs;
  }

  public static void agentmain(String agentArgs, Instrumentation inst) {
    instAgent = inst;
    agentArguments = agentArgs;
  }

  GemFireXDInstrumentation() {
  }

  public boolean isAgentAttached() {
    return (instAgent != null);
  };
  
  public long sizeof(Object objectToSize) {
    
    if (objectToSize == null) {
      return 0;
    }
    if (instAgent == null) {
      return ReflectionSingleObjectSizer.INSTANCE.sizeof(objectToSize);
    }

    return instAgent.getObjectSize(objectToSize);
  }

  public void attachVirtualMachineAgent() {

    /*
    int pid = OSProcess.getId();

    System.out.println(ObjectSizer.logPrefix + "Attempting to attach to VirtualMachine " + pid);
    Class<?> thisClass = GemFireXDInstrumentation.class;
    String agentJar = thisClass.getProtectionDomain().getCodeSource().getLocation().getFile();
    System.out.println(ObjectSizer.logPrefix + agentJar);

    String agent = System.getProperties().getProperty("java.home")
    + File.separator + "lib" + File.separator + "management-agent.jar";
    
    System.out.println(ObjectSizer.logPrefix + AttachProvider.providers());

    VirtualMachine vm = null;
    try {
      System.out.println(ObjectSizer.logPrefix + "Starting to attach" );
      vm = VirtualMachine.attach(Integer.toString(pid));
    } catch (AttachNotSupportedException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
    String connectorAddr = vm.getAgentProperties().getProperty(
        "com.sun.management.jmxremote.localConnectorAddress");
    if (connectorAddr == null) {
      String agent = vm.getSystemProperties().getProperty("java.home")
          + File.separator + "lib" + File.separator + "management-agent.jar";
      vm.loadAgent(agent);

    URL clsUrl = thisClass.getResource(thisClass.getSimpleName() + ".class");
    if( clsUrl != null) {
      conn = clsUrl.openConnection();
      if( conn instanceof JarURLConnection) {
         agentJar = ()
      }
      
    }
      System.out.println(ObjectSizer.logPrefix + InternalDistributedSystem.class.getProtectionDomain().getCodeSource());

      try {
        vm.loadAgent("/soubhikc1/builds/v3gemfirexddev/build-artifacts/linux/product-gfxd/lib/gemfirexd-tools.jar");
      } catch (AgentLoadException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      } catch (AgentInitializationException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      } catch (IOException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
      */
      
//      connectorAddr = vm.getAgentProperties().getProperty(
//          "com.sun.management.jmxremote.localConnectorAddress");
//    }
//    JMXServiceURL serviceURL = new JMXServiceURL(connectorAddr);
//    JMXConnector connector = JMXConnectorFactory.connect(serviceURL);
//    MBeanServerConnection mbsc = connector.getMBeanServerConnection();
//    ObjectName objName = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
//    Set<ObjectName> mbeans = mbsc.queryNames(objName, null);
//    for (ObjectName name : mbeans) {
//      ThreadMXBean threadBean;
//      threadBean = ManagementFactory.newPlatformMXBeanProxy(mbsc, name
//          .toString(), ThreadMXBean.class);
//      long threadIds[] = threadBean.getAllThreadIds();
//      for (long threadId : threadIds) {
//        ThreadInfo threadInfo = threadBean.getThreadInfo(threadId);
//        System.out.println(threadInfo.getThreadName() + " / "
//            + threadInfo.getThreadState());
//      }
//    }
//    logger.flush();
  }

  public static GemFireXDInstrumentation getInstance() {
    return theInstance;
  }

}
