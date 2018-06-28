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
package com.gemstone.gemfire.management.internal;

import java.util.concurrent.TimeUnit;

import javax.management.ObjectName;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.management.internal.beans.QueryDataFunction;


/**
 * Management Related Constants are defined here
 * @author rishim
 *
 */
public interface ManagementConstants {

  /* *********** Constant Strings used in Federation BEGIN ****************** */
  String MGMT_FUNCTION_ID    = ManagementFunction.class.getName();

  String QUERY_DATA_FUNCTION = QueryDataFunction.class.getName();

  int    DEFAULT_QUERY_LIMIT = 1000;

  long   GII_WAIT_TIME_IN_MILLIS = 500; // unused currently

  int    NUM_THREADS_FOR_GII     = 10; // unused currently

  int    REFRESH_TIME = DistributionConfig.DEFAULT_JMX_MANAGER_UPDATE_RATE;

  String MONITORING_REGION = "_monitoringRegion";
  String MONITORING_REGION_PATH = "/" + MONITORING_REGION;

  String NOTIFICATION_REGION = "_notificationRegion";

//  String cascadingSeparator = "/"; // Unused
  /* *********** Constant Strings used in Federation END ******************** */


  /* ************ Constants for JMX/MBean Interface BEGIN ******************* */
  int    ZERO                 = 0;
  int    NOT_AVAILABLE_INT    = -1;
  long   NOT_AVAILABLE_LONG   = -1l;
  float  NOT_AVAILABLE_FLOAT  = -1.0f;
  double NOT_AVAILABLE_DOUBLE = -1.0;

  String[]     NO_DATA_STRING     = new String[0];

  ObjectName[] NO_DATA_OBJECTNAME = new ObjectName[0];

  String UNDEFINED = "UNDEFINED";

  int    RESULT_INDEX = 0;

  TimeUnit nanoSeconds = TimeUnit.NANOSECONDS;

  /** Equivalent to SEVERE level **/
  String DEFAULT_ALERT_LEVEL = "severe";
  /* ************ Constants for JMX/MBean Interface END ********************* */

  /* ************ ObjectName Strings for MBeans BEGIN *********************** */
  // 1. Basic elements
  String OBJECTNAME__DEFAULTDOMAIN = "GemFire";

  /**
   * Key value separator for ObjectName
   */
  String KEYVAL_SEPARATOR = ",";

  /**
   * Key value separator for ObjectName
   */
  String DOMAIN_SEPARATOR = ":";

  /**
   * Prefix used for all the ObjectName Strings
   */
  String OBJECTNAME__PREFIX = OBJECTNAME__DEFAULTDOMAIN + DOMAIN_SEPARATOR;

  // 2. Actual ObjectNames and/or ObjectName structures
  String OBJECTNAME__DISTRIBUTEDSYSTEM_MXBEAN      = OBJECTNAME__PREFIX + "service=System,type=Distributed";

  String OBJECTNAME__MEMBER_MXBEAN                 = OBJECTNAME__PREFIX + "type=Member,member={0}";

  String OBJECTNAME__MANAGER_MXBEAN                = OBJECTNAME__PREFIX + "service=Manager,type=Member,member={0}";

  String OBJECTNAME__DISTRIBUTEDREGION_MXBEAN      = OBJECTNAME__PREFIX + "service=Region,name={0},type=Distributed";

  String OBJECTNAME__REGION_MXBEAN                 = OBJECTNAME__PREFIX + "service=Region,name={0},type=Member,member={1}";

  String OBJECTNAME__DISTRIBUTEDLOCKSERVICE_MXBEAN = OBJECTNAME__PREFIX + "service=LockService,name={0},type=Distributed";

  String OBJECTNAME__LOCKSERVICE_MXBEAN            = OBJECTNAME__PREFIX + "service=LockService,name={0},type=Member,member={1}";

  String OBJECTNAME__ASYNCEVENTQUEUE_MXBEAN        = OBJECTNAME__PREFIX + "service=AsyncEventQueue,queue={0},type=Member,member={1}";

  String OBJECTNAME__GATEWAYSENDER_MXBEAN          = OBJECTNAME__PREFIX + "service=GatewaySender,gatewaySender={0},type=Member,member={1}";

  String OBJECTNAME__GATEWAYRECEIVER_MXBEAN        = OBJECTNAME__PREFIX + "service=GatewayReceiver,type=Member,member={0}";

  String OBJECTNAME__CLIENTSERVICE_MXBEAN          = OBJECTNAME__PREFIX + "service=CacheServer,port={0},type=Member,member={1}";

  String OBJECTNAME__DISKSTORE_MXBEAN              = OBJECTNAME__PREFIX + "service=DiskStore,name={0},type=Member,member={1}";

  String OBJECTNAME__LOCATOR_MXBEAN                = OBJECTNAME__PREFIX + "service=Locator,type=Member,member={0}";

  String AGGREGATE_MBEAN_PATTERN                   = OBJECTNAME__PREFIX + "*,type=Distributed";

  String GATEWAY_SENDER_PATTERN                    = OBJECTNAME__PREFIX + "service=GatewaySender,*";

  String NOTIFICATION_HUB_LISTENER                 = OBJECTNAME__PREFIX + "service=NotificationHubListener";

  // 3. Object Name keys
  String OBJECTNAME_MEMBER_APPENDER = "member";

  // 4. GFXD ObjectName Constants
  String OBJECTNAME__GEMFIREXDDOMAIN    = "GemFireXD";

  String OBJECTNAME__PREFIX_GFXD      = OBJECTNAME__GEMFIREXDDOMAIN + DOMAIN_SEPARATOR;

  String AGGREGATE_MBEAN_PATTERN_GFXD = OBJECTNAME__PREFIX_GFXD + "type=Aggregate"; //unused currently

  String OBJECTNAME__CLUSTER_MXBEAN            = OBJECTNAME__PREFIX_GFXD + "service=Cluster";

  String OBJECTNAME__STATEMENT_MXBEAN          = OBJECTNAME__PREFIX_GFXD + "service=Statement,type=Member,member={0},name={1}";

  String OBJECTNAME__AGGREGATESTATEMENT_MXBEAN = OBJECTNAME__PREFIX_GFXD + "service=Statement,type=Aggregate,name={0}";

  String OBJECTNAME__GFXDMEMBER_MXBEAN         = OBJECTNAME__PREFIX_GFXD + "group={0},type=Member,member={1}";

  String OBJECTNAME__TABLE_MXBEAN              = OBJECTNAME__PREFIX_GFXD + "group={0},service=Table,type=Member,member={1},table={2}";

  String OBJECTNAME__AGGREGATETABLE_MXBEAN     = OBJECTNAME__PREFIX_GFXD + "service=Table,type=Aggregate,table={0}";

  String OBJECTNAME__AGGREGATEMEMBER_MXBEAN = OBJECTNAME__PREFIX_GFXD + "service=Cluster";

  /* ************ ObjectName Strings for MBeans END ************************* */

  // 5. Other Constants
  int MAX_SHOW_LOG_LINES     = 100;
  int DEFAULT_SHOW_LOG_LINES = 30;

  String LINUX_SYSTEM = "Linux";

  // Object Name keys

  /**
   * Factor converting bytes to MB
   */
  long MBFactor = 1024 * 1024;

  String PULSE_URL = "http://{0}:{1}/pulse";

  String DEFAULT_HOST_NAME = "localhost";


  int NOTIF_REGION_MAX_ENTRIES = 10;

}
