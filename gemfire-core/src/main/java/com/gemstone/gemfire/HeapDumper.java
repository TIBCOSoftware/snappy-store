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
package com.gemstone.gemfire;

import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.sun.management.HotSpotDiagnosticMXBean;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;

/**
 * This class has been picked up from the following Oracle blog
 * https://blogs.oracle.com/sundararajan/programmatically-dumping-heap-from-java-applications
 */
public class HeapDumper {
    // This is the name of the HotSpot Diagnostic MBean
    private static final String HOTSPOT_BEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";
    // field to store the hotspot diagnostic MBean
    private static volatile HotSpotDiagnosticMXBean hotspotMBean;

    private static long lastHeapDumpTime = -1;
    private static synchronized boolean takeDump() {
        long currentTime = System.currentTimeMillis();
        if ((currentTime- lastHeapDumpTime) > 3000) {
            lastHeapDumpTime = currentTime;
            return true;
        } else {
            return false;
        }
    }
    static void dumpHeap(boolean live) {
        boolean dumpProperty = SystemProperties.getServerInstance().getBoolean("dumpheap", false);
        // return if dump property is not set or lastheapdumptime is less than 3 seconds
        if (!dumpProperty || !takeDump()) {
            return;
        }
        lastHeapDumpTime = System.currentTimeMillis();
        String fileName = "heap" + lastHeapDumpTime + ".bin";
        // initialize hotspot diagnostic MBean
        initHotspotMBean();
        try {
            hotspotMBean.dumpHeap(fileName, live);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }
    // initialize the hotspot diagnostic MBean field
    private static void initHotspotMBean() {
        if (hotspotMBean == null) {
            synchronized (HeapDumper.class) {
                if (hotspotMBean == null) {
                    hotspotMBean = getHotspotMBean();
                }
            }
        }
    }
    // get the hotspot diagnostic MBean from the
    // platform MBean server
    private static HotSpotDiagnosticMXBean getHotspotMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            HotSpotDiagnosticMXBean bean =
                    ManagementFactory.newPlatformMXBeanProxy(server,
                            HOTSPOT_BEAN_NAME, HotSpotDiagnosticMXBean.class);
            return bean;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }
}