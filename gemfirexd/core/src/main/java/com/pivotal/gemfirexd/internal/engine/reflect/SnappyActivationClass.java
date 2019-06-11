/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.engine.reflect;

import com.pivotal.gemfirexd.internal.engine.sql.execute.SnappyActivation;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;

public class SnappyActivationClass implements GeneratedClass {

  private final boolean returnRows;
  private final int classLoaderVersion;
  boolean isPrepStmt;
  boolean isUpdateOrDeleteOrPut;

  public SnappyActivationClass(LanguageConnectionContext lcc, boolean returnRows,
      boolean isPrepStmt, boolean isUpdateOrDeleteOrPut) {
    this.returnRows = returnRows;
    this.classLoaderVersion = lcc.getLanguageConnectionFactory()
        .getClassFactory().getClassLoaderVersion();
    this.isPrepStmt = isPrepStmt;
    this.isUpdateOrDeleteOrPut = isUpdateOrDeleteOrPut;
  }

  public int getClassLoaderVersion() {
    return this.classLoaderVersion;
  }

  public GeneratedMethod getMethod(String simpleName) throws StandardException {
    return null;
  }

  public final String getName() {
    return "SnappyActivation";
  }

  public final Object newInstance(final LanguageConnectionContext lcc, final boolean addToLCC,
      final ExecPreparedStatement eps) throws StandardException {
    SnappyActivation sa = new SnappyActivation(lcc, eps, this.returnRows, this.isPrepStmt,
        this.isUpdateOrDeleteOrPut);
    if (isPrepStmt) {
      sa.initialize_pvs();
    }
    return sa;
  }
}
