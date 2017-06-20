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
package com.pivotal.gemfirexd.internal.engine.reflect;

import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.sql.execute.GemFireActivationFactory;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedClass;
import com.pivotal.gemfirexd.internal.iapi.services.loader.GeneratedMethod;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecPreparedStatement;

/**
 * @author soubhikc
 * 
 */
public final class GemFireActivationClass implements GeneratedClass {

  private final QueryInfo querynodes;
  private final int classLoaderVersion;

  public GemFireActivationClass(LanguageConnectionContext lcc, QueryInfo qi) {
    querynodes = qi;
    this.classLoaderVersion = lcc.getLanguageConnectionFactory()
        .getClassFactory().getClassLoaderVersion();
  }

  public QueryInfo getQueryInfo() {
    return querynodes;
  }

  public int getClassLoaderVersion() {
    return this.classLoaderVersion;
  }

  public GeneratedMethod getMethod(String simpleName) throws StandardException {
    return null;
  }

  public final String getName() {
    return "GemFireActivation";
  }

  public final Object newInstance(final LanguageConnectionContext lcc,
      final boolean addToLCC, final ExecPreparedStatement eps)
      throws StandardException {
    return GemFireActivationFactory.get(eps, lcc, addToLCC, this);
  }
}
