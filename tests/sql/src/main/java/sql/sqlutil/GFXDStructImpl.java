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
package sql.sqlutil;

import java.io.Serializable;
import java.util.Arrays;

import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.cache.query.internal.StructImpl;
import com.gemstone.gemfire.cache.query.internal.types.StructTypeImpl;
import com.gemstone.gemfire.cache.query.types.ObjectType;
import com.gemstone.gemfire.cache.query.types.StructType;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import hydra.Log;
import sql.SQLPrms;

public class GFXDStructImpl extends StructImpl implements Serializable {
  private StructTypeImpl type;
  private Object[] values;

  public GFXDStructImpl() {};
  
  /** Creates a new instance of StructImpl */
  public GFXDStructImpl(StructTypeImpl type, Object[] values) {
    if (type == null) {
      throw new IllegalArgumentException(LocalizedStrings.StructImpl_TYPE_MUST_NOT_BE_NULL.toLocalizedString());
    }
    this.type = type;
    this.values = values;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Struct)) return false;
    Struct s = (Struct)obj;
    if(!SQLPrms.isSnappyMode()) {
      if (!Arrays.equals(getFieldTypes(), s.getStructType().getFieldTypes())) return false;
      if (!Arrays.equals(getFieldNames(), s.getStructType().getFieldNames())) return false;
    }
    if (!Arrays.equals(getFieldValues(), s.getFieldValues())) return false;
    return true;
  }

  public Object get(String fieldName) {
    return this.values[this.type.getFieldIndex(fieldName)];
  }

  public ObjectType[] getFieldTypes() {
    return this.type.getFieldTypes();
  }


  public String[] getFieldNames() {
    return this.type.getFieldNames();
  }

  public Object[] getFieldValues() {
    if (this.values == null) {
      return new Object[0];
    }
    return this.values;
  }  

  public StructType getStructType() {
    return this.type;
  }
}
