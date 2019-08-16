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
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Random;

public class RandomValueGenerator {
  private Random rand;
  private int totalThreads;

  public RandomValueGenerator() {
    DataGenerator datagen = DataGenerator.getDataGenerator();
    totalThreads = datagen.getTotalThreads();
    rand = datagen.rand;
  }

  public Object generateValues(TableMetaData table, ColumnMetaData column,
      int tid) {
    int type = column.getDataType();
    int cLen = column.getColumnSize();
    int prec = column.getDecimalDigits();
    boolean absoluteLength = column.isAbsoluteLenth();
    boolean isPrimary = column.isPrimary();
    boolean isUnique = column.isUnique();

    Long lastNum = column.getLastNum(tid);
    Long newNum = lastNum;
    if (lastNum == null) {
      newNum = (long)tid;
    } else {
      if (isPrimary || isUnique) {
        newNum = getNextNumber(lastNum.longValue(), totalThreads);
      } else {
        if (rand.nextInt(100) < 20) {
          newNum = lastNum;
        } else {
          newNum = getNextNumber(lastNum.longValue(), totalThreads);
        }
      }
    }
    // String newNumStr = getNewNumStr(newNum, (totalThreads + "").length());
    String newNumStr = newNum.toString();
    Object value = null;

    switch (type) {
    case java.sql.Types.CHAR:
    case java.sql.Types.VARCHAR:
    case java.sql.Types.CLOB: {
      String baseValue = column.getColumnName();
      if (isPrimary) {
        value = baseValue + "_" + newNum;
      } else {
        int diff = cLen - baseValue.length() - newNumStr.length() - 2;
        if (diff > 3) {
          String v = generateString(diff, absoluteLength);
          value = baseValue + "_" + newNumStr + "_" + v;
        } else {
          int len = cLen - newNumStr.length() - 1;
          value = newNumStr + "_" + generateString(len, absoluteLength);
        }
      }
      break;
    }
    case java.sql.Types.BIGINT: {
      if (isPrimary || isUnique) {
        value = newNum.longValue();
      } else {
        int l = newNumStr.length();
        String s = generateNumericStr(cLen - l, prec, absoluteLength);
        value = new Long(newNumStr + s);
      }
      break;
    }
    case java.sql.Types.INTEGER: {
      if (isPrimary || isUnique) {
        value = newNum.intValue();
      } else {
        int l = newNumStr.length();
        String s = generateNumericStr(cLen - l, prec, absoluteLength);
        value = new Integer(newNumStr + s);
      }
      break;
    }
    case java.sql.Types.SMALLINT: {
      if (isPrimary || isUnique) {
        value = newNum.shortValue();
      } else {
        int l = newNumStr.length();
        String s = generateNumericStr(cLen - l, prec, absoluteLength);
        value = new Short(newNumStr + s);
      }
      break;
    }
    case java.sql.Types.DOUBLE: {
      if (isPrimary || isUnique) {
        value = new BigDecimal(newNum);
      } else {
        int l = newNumStr.length();
        String s = generateNumericStr(cLen - l, prec, absoluteLength);
        value = new BigDecimal(newNumStr + s);
      }
      break;
    }
    case java.sql.Types.REAL:
    case java.sql.Types.FLOAT: {
      if (isPrimary || isUnique) {
        value = new Float(newNum + "");
      } else {
        int l = newNumStr.length();
        String s = generateNumericStr(cLen - l, prec, absoluteLength);
        value = new Float(newNumStr + s);
      }
      break;
    }
    case java.sql.Types.DATE: {
      value = generateDate(newNum * 183000);
      break;
    }
    case java.sql.Types.TIMESTAMP:
      value = generateTimestamp(newNum * 183000);
      break;
    case java.sql.Types.DECIMAL:
    case java.sql.Types.NUMERIC: {
      if (isPrimary || isUnique) {
        value = new BigDecimal(newNum);
      } else {
        int l = newNumStr.length();
        String s = generateNumericStr(cLen - l, prec, absoluteLength);
        value = new BigDecimal(newNumStr + s);
      }
      break;
    }
    case java.sql.Types.BLOB: {
      value = generateBytes(cLen, absoluteLength);
      break;
    }
    default:
      throw new RuntimeException("cannot handle column of type " + type);
    }

    column.setLastNum(tid, newNum);
    return value;
  }

  protected String getFormatterTimestamp() {
    return "yyyy-MM-dd HH:mm:ss";
  }

  protected String getFormatterDate() {
    return "yyyy-MM-dd";
  }

  protected String getBaseTime() {
    return "2012-01-01 01:01:00";
  }

  final char[] chooseChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
      .toCharArray();

  static final char[] chooseBytes = "0123456789abcdef".toCharArray();

  protected String generateString(int len, boolean absoluteLength) {
    if (len < 1)
      return "";
  
    if (len > 32000) {
      len = 32000;
    }
    if (!absoluteLength)
      len = rand.nextInt(len) + 1;
    
    StringBuilder chars = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      chars.append(chooseChars[rand.nextInt(chooseChars.length)]);
    }
    return chars.toString();
  }

  protected char[] generateBytes(int len, boolean absoluteLength) {
    if (len < 1)
      return new char[0];
  
    if (len > 32000) {
      len = 32000;
    }
    if (!absoluteLength)
      len = rand.nextInt(len) + 1;
    
    final char[] chars = new char[len];
    for (int i = 0; i < len; i++) {
      chars[i] = chooseBytes[rand.nextInt(chooseBytes.length)];
    }
    return chars;
  }

  protected Date generateDate(long offset) {
    final SimpleDateFormat formatterTimestamp = new SimpleDateFormat(
        getFormatterTimestamp());
    java.util.Date baseDate;
    try {
      baseDate = formatterTimestamp.parse(getBaseTime());
    } catch (ParseException e) {
      throw new RuntimeException("Error is formating Date ", e);
    }
    long newTime = baseDate.getTime() + offset;
    return new Date(newTime);
  }

  protected Timestamp generateTimestamp(long offset) {

    final SimpleDateFormat formatterTimestamp = new SimpleDateFormat(
        getFormatterTimestamp());
    java.util.Date baseDate;
    try {
      baseDate = formatterTimestamp.parse(getBaseTime());
    } catch (ParseException e) {
      throw new RuntimeException("Error is formating TimeStamp ", e);
    }
    long newTime = baseDate.getTime() + offset;
    return new Timestamp(newTime);
  }

  final char[] chooseInts = new char[] { '0', '1', '2', '3', '4', '5', '6',
      '7', '8', '9' };

  protected String generateNumericStr(int len, int prec, boolean absoluteLength) {
    int major = len - prec - 1;
    if (major > 1) {
      if (major > 15) {
        major = 15;
      }
      if (!absoluteLength)
        major = rand.nextInt(major) + 1;
    }

    StringBuilder num = new StringBuilder(major);
    if (major > 0) {
      for (int i = 0; i < major; i++) {
        num.append(chooseInts[rand.nextInt(chooseInts.length)]);
      }
    }

    if (prec > 0) {
      if (!absoluteLength)
        prec = rand.nextInt(prec) + 1;

      char[] precChars = new char[prec];
      for (int i = 0; i < prec; i++) {
        precChars[i] = chooseInts[rand.nextInt(chooseInts.length)];
      }

      num.append('.').append(precChars);
    }
    return num.toString();
  }

  protected long getNextNumber(long lastNum, int totalThreads) {
    return lastNum + totalThreads;
  }

  protected String getNewNumStr(long newNum, int digits) {
    String numStr = "";
    int l = (newNum + "").length();
    int diff = digits - l;
    for (int i = 0; i < diff; i++) {
      numStr += "0";
    }
    return numStr + newNum;
  }
}
