/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.util.Iterator;

/**
 * Base class for iterators for regions that are backed by disk regions and may
 * have entries overflowed to disk. There are two modes of operation possible
 * for disk region iterators.
 * <p>
 * a) The first mode is iteration over a single DiskRegion where the in-memory
 * entries of the region are returned first, and then the first hasNext() call
 * as false should have the caller invoke {@link #initDiskIterator()} which
 * will then cause the iterator to start returning overflowed disk entries.
 * <p>
 * b) In the second mode the iteration is to be done over multiple disk regions.
 * Here in-memory entries of all the regions should be returned first which is
 * done by setting the region repeatedly using {@link #setRegion(LocalRegion)}
 * after the hasNext() has returned as false. At the end of all in-memory entry
 * iteration, the {@link #initDiskIterator()} should be invoked by the caller
 * at the end after all regions are done, and the final iteration by
 * implementations should be over the overflowed entries for all the regions
 * that were set by {@link #setRegion(LocalRegion)} during in-memory iteration.
 */
public interface DiskRegionIterator extends Iterator<RegionEntry> {

  boolean initDiskIterator();

  void setRegion(LocalRegion region);
}
