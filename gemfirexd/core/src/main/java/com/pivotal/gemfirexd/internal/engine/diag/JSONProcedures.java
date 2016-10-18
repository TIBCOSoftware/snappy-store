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
package com.pivotal.gemfirexd.internal.engine.diag;

import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.pdx.JSONFormatter;
import com.gemstone.gemfire.pdx.PdxInstance;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jayway.jsonpath.JsonPath;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.types.JSON;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;

public final class JSONProcedures {

  // no instances
  private JSONProcedures() {
  }

  // to enable this class to be included in gemfirexd.jar
  public static void dummy() {
  }

  /**
   * Apply a JSON path expression and return result
   * @param column
   * @param jsonPath
   * @return
   * @throws StandardException 
   */
  public static String json_evalPath(JSON column, String jsonPath)
      throws StandardException {
    if (GemFireXDUtils.TraceExecution) {
      SanityManager.DEBUG_PRINT("info", "Json path to be evaluated : " + jsonPath);
    }
    PdxInstance pdxInstance = column.getPdxInstance();
    Object obj = JsonPath.jsonPathQuery(pdxInstance, jsonPath);
    String jsonString = convertObjToJsonString(obj);
    return jsonString;
  }
  
//  public static Boolean JSON_evalAttribute(JSON column, String jsonPath)
//      throws StandardException {
//    PdxInstance pdxInstance = column.getPdxInstance();
//    Object obj = JsonPath.jsonPathQuery(pdxInstance, jsonPath);
//    if(obj == null || (obj instanceof Map && ((Map)obj).isEmpty())){
//      return false;
//    }
//    return true;
//  }
  
  public static String convertObjToJsonString(Object obj) {
    if (obj == null || (obj instanceof Map && ((Map)obj).isEmpty())) {
      return null;
    }
    
    if (obj instanceof Map) {
      return toJSON((Map)obj);
    }
    else if (obj instanceof List) {
      return toJSON((List)obj);
    }
    else if (obj instanceof PdxInstance) {
      return JSONFormatter.toJSON((PdxInstance)obj);
    }
    else {
      return obj.toString();
    }
    
  }
  
  private static <K, V> String toJSON(Map map) {
    StringBuilder sb = new StringBuilder();
    String delim = "";
    for (Map.Entry<K, V> entry : ((Map<K, V>)map).entrySet()) {
      sb.append(delim);
      if (entry.getValue() instanceof PdxInstance) {
        sb.append(JSONFormatter.toJSON((PdxInstance)entry.getValue()));
        delim = ",\n";
      }
      else if (entry.getValue() instanceof List) {
        sb.append(toJSON((List)entry.getValue()));
        delim = ",\n";
      }
      else {
        sb.append(entry.getValue());
        delim = ",";
      }
    }
    return sb.toString();
  }
  
  private static <K, V> String toJSON(List list) {
    StringBuilder sb = new StringBuilder();
    String delim = "";
    for (Object listObject : (List)list) {
      sb.append(delim);
      if (listObject instanceof PdxInstance) {
        sb.append(JSONFormatter.toJSON((PdxInstance)listObject));
        delim = ",\n";
      }
      else {
        sb.append(listObject);
        delim = ",";
      }
    }
    return sb.toString();
  }
}