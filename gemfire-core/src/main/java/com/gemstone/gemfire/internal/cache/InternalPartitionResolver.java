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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.cache;

import com.gemstone.gemfire.cache.PartitionResolver;
import com.gemstone.gemfire.cache.Region;

/**
 * Extension used for internal partition resolvers to avoid creating an
 * <code>EntryOperation</code> for {@link PartitionResolver#getRoutingObject}
 * when key, value, callbackArg are being passed separately.
 *
 * @author swale
 * @since 7.5
 */
public interface InternalPartitionResolver<K, V> extends
    PartitionResolver<K, V> {

  /**
   * Returns object associated with operation which allows the Partitioned
   * Region to store associated data together.
   */
  Object getRoutingObject(Object key, Object val, Object callbackArg,
      Region<?, ?> region);

  String[] getPartitioningColumns();

  /**
   * Return the number of partitioning columns in the table.
   */
  int getPartitioningColumnsCount();

  /**
   * Get the master table (i.e. the root in collocation chain).
   */
  String getMasterTable(boolean rootMaster);

  /**
   * Get the string for display (e.g. used in DDL).
   */
  String getDDLString();
}
