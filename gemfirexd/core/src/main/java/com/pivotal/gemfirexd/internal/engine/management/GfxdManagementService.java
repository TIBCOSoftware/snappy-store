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
package com.pivotal.gemfirexd.internal.engine.management;

import java.util.Hashtable;
import java.util.Map;
import java.util.logging.Level;

import javax.management.ObjectName;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.management.impl.InternalManagementService;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

/**
 *
 * @author Abhishek Chaudhari
 * @since gfxd 1.0
 */
public class GfxdManagementService {
  public static final String DISABLE_MANAGEMENT_PROPERTY =
      InternalDistributedSystem.DISABLE_MANAGEMENT_PROPERTY;

  private static final Object INSTANCE_LOCK = GfxdManagementService.class;
  private static final Map<GemFireStore, GfxdManagementService> INSTANCES = new Hashtable<GemFireStore, GfxdManagementService>();

  private InternalManagementService internalManagementService;
  private static boolean loggedDisabled;

  private GfxdManagementService(GemFireStore store) {
    this.internalManagementService = InternalManagementService.getInstance(store);
  }

  /**
   * Returns a newly created or the existing instance of the management service
   * for a cache.
   *
   * @param store
   *          GemFireStore for which to get the management service.
   */
  public static GfxdManagementService getInstance(GemFireStore store) {
    GfxdManagementService foundInstance = null;
    if (store != null) { // store should be not-null
      foundInstance = INSTANCES.get(store);
      if (foundInstance == null && store != null) { // first level check
        synchronized (INSTANCE_LOCK) {
          foundInstance = INSTANCES.get(store);
          if (foundInstance == null) { // second level check
            foundInstance = new GfxdManagementService(store);
            INSTANCES.clear(); // clear old instances if existed.
            INSTANCES.put(store, foundInstance);
          }
        }
      }
    }
    return foundInstance;
  }

  public static boolean isManagementDisabled() {
    return Boolean.getBoolean(DISABLE_MANAGEMENT_PROPERTY);
  }

  public static <T> void handleEvent(int eventId, T eventData) {
//    System.out.println("ABHISHEK: GfxdManagementService.handleEvent: isManagementDisabled :: "+isManagementDisabled());
    GemFireStore store = GemFireStore.getBootedInstance();
    if (!isManagementDisabled() && store != null) {
      // will create GfxdManagementService for the first call if it doesn't exist
      GfxdManagementService managementService = getInstance(store);
      if (managementService != null) {
        managementService.handleEventForManagement(eventId, eventData);
        InternalManagementService mgmt = managementService.internalManagementService;
        if (mgmt != null && mgmt.isStopped()) { // it was a stop event
          managementService.stopService();
        }
      }
    } else if (!loggedDisabled) {
      logInfo("Management is disabled, MBeans won't be available.", null);
      loggedDisabled = true;
    }
  }

  private static void logInfo(String message, Throwable t) {
    LogWriter logger = Misc.getCacheLogWriterNoThrow();
    if (logger != null && logger.infoEnabled()) {
      logger.info(message, t);
    } else {
      SanityManager.DEBUG_PRINT(Level.INFO.toString().toLowerCase(), message, t);
    }
  }

  private <T> void handleEventForManagement(int eventId, T eventData) {
    if(internalManagementService != null && (!internalManagementService.isStopped())){
      internalManagementService.handleEvent(eventId, eventData);
    }
  }

  public <T> Object registerMBean(final T bean, final ObjectName objectName) {
    return internalManagementService.registerMBean(bean, objectName);
  }

  public void unregisterMBean(final ObjectName objectName) {
    internalManagementService.unregisterMBean(objectName);
  }

  /**
   * Returns whether this member is running the management service.
   *
   * @return True if this member is running the management service, false otherwise.
   */
  public boolean isManager() {
    return internalManagementService.isManager();
  }

  /**
   * Starts the management service on this member.
   */
  public void startManager() {
    internalManagementService.startManager();
  }

  /**
   * Stops the management service running on this member.
   */
  public void stopManager() {
    internalManagementService.stopManager();
  }

  public boolean isStopped() {
    return internalManagementService.isStopped();
  }

  public void stopService() {
    synchronized (INSTANCE_LOCK) {
      INSTANCES.clear(); // clear old instances if existed.
    }
    if (!internalManagementService.isStopped()) {
      internalManagementService.stopService();
    }
  }
}
