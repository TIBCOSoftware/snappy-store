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
 * Changes for SnappyData data platform.
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

package sql.datagen;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

import hydra.Log;

public class ColumnMetaData {
  private final Random rand;

  private String columnName;
  private String fullTableName;
  private int dataType;
  private int columnSize;
  private int decimalDigits;
  private boolean isPrimary;
  private boolean isUnique;
  private MappedColumnInfo mappedColumn;

  private Map<Integer, Long> lastNumMap;
  private Map<Integer, LinkedList<Object>> fkValuesMap;
  private Map<Integer, LinkedList<Object>> fkValuesMap_bk;

  public ColumnMetaData(Random r) {
    lastNumMap = new HashMap<>();
    fkValuesMap = new HashMap<>();
    fkValuesMap_bk = new HashMap<>();
    rand = r;
  }

  public String getColumnName() {
    return columnName;
  }

  public String getFullTableName() {
    return fullTableName;
  }

  public String getFullColumnName() {
    return fullTableName + "." + columnName;
  }

  public void setFullTableName(String fullTableName) {
    this.fullTableName = fullTableName;
  }

  public void setColumnName(String columnName) {
    this.columnName = columnName;
  }

  public int getDataType() {
    return dataType;
  }

  public void setDataType(int dataType) {
    this.dataType = dataType;
  }

  public int getColumnSize() {
    int len = columnSize;
    if (mappedColumn != null) {
      int minlen = mappedColumn.getMinLen();
      int maxlen = mappedColumn.getMaxLen();
      len = minlen;
      if (maxlen != -1) {
        len = rand.nextInt(maxlen - minlen) + minlen;
      }
    }
    return len;
  }

  public boolean isAbsoluteLenth() {
    boolean absolute = false;
    if (mappedColumn != null) {
      int minlen = mappedColumn.getMinLen();
      if (minlen != -1) {
        absolute = true;
      }
    }
    return absolute;
  }

  public void setColumnSize(int columnSize) {
    this.columnSize = columnSize;
  }

  public int getDecimalDigits() {
    if (mappedColumn != null) {
      return mappedColumn.getPrec();
    }
    return decimalDigits;
  }

  public void setDecimalDigits(int decimalDigits) {
    this.decimalDigits = decimalDigits;
  }

  public MappedColumnInfo getMappedColumn() {
    return mappedColumn;
  }

  public void setMappedColumn(MappedColumnInfo mappedColumn) {
    this.mappedColumn = mappedColumn;
  }

  public boolean isPrimary() {
    return isPrimary;
  }

  public boolean isUnique() {
    return isUnique;
  }

  public void setPrimary(boolean isPrimary) {
    this.isPrimary = isPrimary;
  }

  public void setUnique(boolean isUnique) {
    this.isUnique = isUnique;
  }

  public Long getLastNum(int tid) {
    return lastNumMap.get(tid);
  }

  public void setLastNum(int tid, Long value) {
    lastNumMap.put(tid, value);
  }

  public boolean isFKParent() {
    boolean flag = false;
    if (mappedColumn != null) {
      flag = mappedColumn.isFKParent();
    }
    return flag;
  }

  public void addToFKValueMap(int tid, Object val) {
    if (isFKParent()) {
      LinkedList<Object> l = fkValuesMap.get(tid);
      if (l == null) {
        l = new LinkedList<>();
        fkValuesMap.put(tid, l);
      }

      if (!l.contains(val)) {
        l.add(val);
      }
    }
  }

  public Object getRandomValueFromFKList(int tid, boolean unique) {
    Object value = null;
    LinkedList<Object> l = fkValuesMap.get(tid);
    LinkedList<Object> l_bk = fkValuesMap_bk.get(tid);
    if (l != null && !l.isEmpty()) {
      if (unique) {
        value = l.remove();
        if(l_bk == null){
          l_bk = new LinkedList<>();
          fkValuesMap_bk.put(tid, l_bk);
        }
        l_bk.add(value);
      } else {
        int size = l.size();
        int n = (size > 1) ? rand.nextInt(size - 1) : 0;
        value = l.get(n);
      }
    }
    return value;
  }

  public void resetFKValueMap() {
    Log.getLogWriter().info("Reset FKValueMap for " + getFullColumnName());
    for (Integer t : fkValuesMap_bk.keySet()) {
      LinkedList<Object> l = fkValuesMap.get(t);
      LinkedList<Object> l_bk = fkValuesMap_bk.get(t);
      l.addAll(l_bk);
      l_bk.clear();
    }
  }

  @Override
  public String toString() {
    return this.getClass().getName() + '[' +
        "columnName=" + getFullColumnName() +
        ",isFKParent=" + isFKParent() +
        ",dataType=" + dataType +
        ",isPrimary=" + isPrimary() +
        ",isUnique=" + isUnique() +
        ",columnSize=" + columnSize +
        ",decimalDigits=" + decimalDigits +
        ",mappedColumn=" + mappedColumn + ']';
  }
}
