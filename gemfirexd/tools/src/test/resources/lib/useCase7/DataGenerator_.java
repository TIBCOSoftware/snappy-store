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

//package cacheperf.comparisons.gemfirexd.useCase7.datagen;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Stack;
import java.util.StringTokenizer;

public class DataGenerator_ {

  protected String url = "jdbc:gemfirexd://localhost:1530/";

  protected String driver = "com.pivotal.gemfirexd.jdbc.ClientDriver";

  int[] rowCount = new int[] { 100000 };

  enum RelationalOperator {
    Equal, GreaterThan, LessThan;
  };

  class Repeater {
    String columnName;

    int numTimes;

    public Repeater(String columnName, int numTimes) {
      this.columnName = columnName;
      this.numTimes = numTimes;
    }

    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      Repeater other = (Repeater)obj;
      return columnName.equals(other.columnName);
    }

    public int hashCode() {
      return columnName.hashCode();
    }

    public String toString() {
      return "repeat=" + numTimes;
    }
  }

  final static Stack<Repeater> repeaters = new Stack<Repeater>();

  static class ColumnRange {
    final String qColName;

    final Object start;

    final Object increment;

    final int minNumber;

    final int maxNumber;

    final String foreachColumn;

    final boolean isDecimal;

    final Repeater repeatRetVal;

    String[] valueList;

    final boolean randomizeValueList;

    boolean hasDependentForeach;

    Object currentValue;

    int currentIndex;

    final String foreignKeyColumn;

    int fkValueListIndex = 0;

    int currentLimit;

    int currentRepeatCount;

    final Random rand = new Random();

    private ColumnRange(String qColName, Object start, Object increment,
        int minNumber, int maxNumber, String foreachColumn, boolean isDecimal,
        String[] valueList, boolean randomize) {
      this.qColName = qColName;
      this.start = start;
      this.increment = increment;
      this.minNumber = minNumber;
      this.maxNumber = maxNumber;
      this.foreachColumn = foreachColumn;
      this.isDecimal = isDecimal;
      this.repeatRetVal = repeaters.empty() ? null : repeaters.peek();
      this.valueList = valueList;
      this.randomizeValueList = randomize;
      this.foreignKeyColumn = null;
      this.currentIndex = -1;
      this.currentLimit = -1;
      this.currentRepeatCount = 0;
    }

    private ColumnRange(String qColName, Object start, Object increment,
        int minNumber, int maxNumber, String foreignKeyColumn) {
      this.qColName = qColName;
      this.start = start;
      this.increment = increment;
      this.minNumber = minNumber;
      this.maxNumber = maxNumber;
      this.foreachColumn = null;
      this.isDecimal = false;
      this.repeatRetVal = repeaters.empty() ? null : repeaters.peek();
      // will be later populated, once FK column is done.
      this.valueList = null;
      this.randomizeValueList = false;
      this.foreignKeyColumn = foreignKeyColumn;
      this.currentIndex = -1;
      this.currentLimit = -1;
      this.currentRepeatCount = 0;
    }

    protected String goForRepeatValue() {
      if (repeatRetVal != null) {
        // first time fetch the value.
        if (currentRepeatCount == 0) {
          currentRepeatCount++;
          return null;
        }
        if (currentRepeatCount++ <= repeatRetVal.numTimes) {
          return this.currentValue != null ? this.currentValue.toString()
              : "RepeatNoCurVal";
        }
        else {
          currentRepeatCount = 0;
        }
      }
      return null;
    }

    public String getNext(int row, HashMap<String, Object> columnNameMapping,
        HashMap<String, ArrayList<String>> columnValueMapping) {

      String retVal = goForRepeatValue();
      if (retVal != null) {
        return retVal;
      }

      if (this.foreignKeyColumn != null && this.valueList == null) {
        this.valueList = columnValueMapping.get(this.foreignKeyColumn).toArray(
            new String[0]);
        System.out.println("Initialized foreignKeyColumn "
            + this.foreignKeyColumn + " with "
            + (this.valueList != null ? this.valueList.length : 0) + " values");
      }

      if (!this.hasDependentForeach) {
        ColumnRange range = this;
        // increment the parents iteratively, if any, on rollover
        while (range.getNext()) {
          if (range.foreachColumn != null) {
            range = (ColumnRange)columnNameMapping.get(range.foreachColumn);
          }
          else {
            break;
          }
        }
      }
      else {
        // parent will always be incremented by the child rollover
        if (this.currentValue == null) {
          getNext();
        }
      }
      return this.currentValue != null ? this.currentValue.toString()
          : "NOCurVAL";
    }

    private boolean getNext() {
      boolean lastReached = false;
      if (this.valueList != null && this.valueList.length > 0
          && this.minNumber == -1 && this.maxNumber == -1) {
        this.currentIndex = this.randomizeValueList ? rand
            .nextInt(this.valueList.length - 1) : this.currentIndex + 1;
        this.currentValue = this.valueList[this.currentIndex];
        if (this.currentIndex >= (this.valueList.length - 1)) {
          lastReached = true;
          this.currentIndex = -1;
        }
      }
      else {
        this.currentIndex++;
        if (this.currentValue == null
            || (this.currentLimit > 0 && this.currentIndex > this.currentLimit)) {
          if (this.minNumber == this.maxNumber) {
            this.currentLimit = this.minNumber;
          }
          else {
            this.currentLimit = rand.nextInt(this.maxNumber - this.minNumber)
                + this.minNumber;
          }
          this.currentValue = this.start;
          this.currentIndex = 0;
          if (foreignKeyColumn != null) {
            this.fkValueListIndex++;
            if (fkValueListIndex >= this.valueList.length) {
              this.fkValueListIndex = 0;
            }
            this.currentValue = this.valueList[fkValueListIndex];
          }
          lastReached = true;
        }
        else {
          if (this.foreignKeyColumn != null && this.valueList != null) {
            currentValue = this.valueList[this.fkValueListIndex];
          }
          else {
            if (this.isDecimal) {
              currentValue = ((BigDecimal)currentValue)
                  .add((BigDecimal)increment);
            }
            else {
              currentValue = (Integer)currentValue + (Integer)increment;
            }
          }
        }
      }
      return lastReached;
    }

    public String toString() {
      StringBuilder sb = new StringBuilder("colName=").append(qColName).append(
          " ");
      sb.append("value list: ");
      if (this.valueList != null) {
        int _i = 0;
        for (; _i <= 20 && _i < this.valueList.length; _i++) {
          sb.append(this.valueList[_i]).append(", ");
        }
        if (this.valueList.length > 20)
          sb.append(".......").append(this.valueList.length)
              .append(" total values. ");
        sb.append(";randomizeValueList=").append(this.randomizeValueList);
      }
      else {
        sb.append("start=").append(start).append(";increment=")
            .append(increment);
        if (this.minNumber != -1 || this.maxNumber != -1) {
          sb.append(";number=").append(this.minNumber);
          if (this.minNumber != this.maxNumber) {
            sb.append('-').append(this.maxNumber);
          }
        }
        if (this.foreachColumn != null) {
          sb.append(";foreach=").append(this.foreachColumn);
        }
      }
      sb.append(";hasDependentForeach=").append(this.hasDependentForeach);
      if (repeatRetVal != null)
        sb.append(";").append(this.repeatRetVal);
      return sb.toString();
    }

    public final static String nullToken = "[null]";

    public final static String skipToken = "[skip]";

    static enum specialTokens {
      valuelist {
        public ColumnRange parse(String col, String line, Connection conn)
            throws Exception {
          String[] elems = line.substring(2).split(",");
          System.out.println(elems.length);
          if (elems.length > 1 && line.indexOf("bashexecute") <= 0) {
            System.out.println(Arrays.toString(elems));

            int minNumber = -1;
            int maxNumber = -1;
            String foreignKeyColumn = null;
            String number;
            for (String elem : elems) {
              elem = elem.trim();
              int spaceIndex = elem.indexOf(' ');
              String name = elem.substring(0, spaceIndex).trim();
              if ("valuelist".equalsIgnoreCase(name)) {
                foreignKeyColumn = elem.substring(spaceIndex + 1).trim();
              }
              else if ("number".equalsIgnoreCase(name)) {
                number = elem.substring(spaceIndex + 1);
                int rangeIndex = number.indexOf('-');
                if (rangeIndex > 0) {
                  minNumber = Integer.parseInt(number.substring(0, rangeIndex)
                      .trim());
                  maxNumber = Integer.parseInt(number.substring(rangeIndex + 1)
                      .trim());
                }
                else {
                  minNumber = maxNumber = Integer.parseInt(number.trim());
                }
              }
            }

            if (foreignKeyColumn == null || minNumber == -1 || maxNumber == -1) {
              throw new IllegalArgumentException(
                  "either foreignKey or cardinality (number) not mentioned.");
            }

            System.out.println("Registering foreign key valuelist for column "
                + col + " foreignKeyColumn=" + foreignKeyColumn + ": " + line);
            return new ColumnRange(col, null, null, minNumber, maxNumber,
                foreignKeyColumn);
          }
          else if (line.indexOf("bashexecute") > 0) {
            // String valueListCmd = "bash -c \"" +
            // line.substring(line.indexOf('[') + 1, line.indexOf(']')) + "\"";
            String valueListCmd = line.substring(line.indexOf('[') + 1,
                line.indexOf(']'));
            ArrayList<String> cmd = new ArrayList<String>(4);
            cmd.add("bash");
            cmd.add("--norc");
            cmd.add("-c");
            // cmd.add("echo {A..Z}{A..Z},|sed 's/ //g'");
            cmd.add(valueListCmd);
            System.out.println("executing:" + cmd);
            ProcessBuilder procBuilder = new ProcessBuilder(cmd);

            // Process pr = Runtime.getRuntime().exec(valueListCmdArr);
            Process pr = procBuilder.start();
            BufferedReader buf = new BufferedReader(
                new java.io.InputStreamReader(pr.getInputStream()));

            ArrayList<String> outputLines = new ArrayList<String>();
            String outline = null; // buf.readLine();
            while ((outline = buf.readLine()) != null) {
              // System.out.println("adding " + outline);
              outputLines.add(outline);
            }

            buf = new BufferedReader(new java.io.InputStreamReader(
                pr.getErrorStream()));
            while ((outline = buf.readLine()) != null) {
              System.out.println(outline);
            }

            pr.waitFor();
            // String valueList = values.toString();
            // valueList = "  " + valueList.substring(1, valueList.length()-1);

            // return this.parse(col, valueList, conn);

            String[] values = outputLines.get(0).split(",");

            if (outputLines.size() > 1) {
              System.err
                  .println("More than one line output is getting IGNORED for now ....");
            }

            boolean randomize = false;
            if (line.endsWith("randomize")) {
              randomize = true;
            }

            System.out.println("Registering value map for column " + col + ": "
                + values.length);
            // + Arrays.toString(valueStr));
            return new ColumnRange(col, null, null, -1, -1, null, false,
                values, randomize);
          }
          else {
            String valueListQuery = line.substring(line.indexOf('[') + 1,
                line.indexOf(']'));
            ResultSet r = conn.createStatement().executeQuery(valueListQuery);
            ArrayList<String> values = new ArrayList<String>();
            while (r.next()) {
              values.add(r.getString(1));
            }

            boolean randomize = false;
            if (line.endsWith("randomize")) {
              randomize = true;
            }

            System.out.println("Registering dynamic value map for column "
                + col + ": " + line + " with " + values.size() + " elements ");
            return new ColumnRange(col, null, null, -1, -1, null, false,
                values.toArray(new String[0]), randomize);
          }
        }
      },
      random {
        @Override
        public ColumnRange parse(String col, String line, Connection conn) {
          int beginIndex = line.indexOf("[");
          if (beginIndex == -1) {
            throw new IllegalArgumentException("Incomplete randome specifier: "
                + line);
          }
          int endIndex = line.indexOf("]");
          if (endIndex == -1) {
            throw new IllegalArgumentException("Incomplete randome specifier: "
                + line);
          }

          String valueSpec = line.substring(beginIndex + 1, endIndex);

          if (valueSpec.indexOf("-") <= 0) {
            System.out.println("Registering dynamic value map for column "
                + col + ": " + line + " with " + valueSpec + " length");
            // only fixed length is mentioned.
            return new ColumnRandom(col, Integer.parseInt(valueSpec), -1, -1);
          }
          else {
            String[] valueLengths = valueSpec.split("-");
            int begin = Integer.parseInt(valueLengths[0]), end = Integer
                .parseInt(valueLengths[1]);
            System.out.println("Registering dynamic value map for column "
                + col + ": " + line + " from " + begin + " to " + end
                + " length");
            // a range but fixed length.
            return new ColumnRandom(col, begin, end, -1);
          }
        }
      },
      format {
        @Override
        public ColumnRange parse(String col, String line, Connection conn)
            throws Exception {

          int beginLength = -1, endLength = -1;
          int prec = -1;
          final String fSpec = line.substring(name().length()).trim();
          int optBegin = fSpec.indexOf("[");
          int optEnd = fSpec.indexOf("]");
          int dot = fSpec.indexOf(".");

          if (optBegin != -1) {
            if (optEnd == -1) {
              throw new IllegalArgumentException(
                  "[ .. ] mismatch in the format specifier in line: " + line);
            }

            int optionalportion = (optEnd - optBegin) - 1; // minus 1 cause [ &
                                                           // ] is inclusive.

            // adjust for [ & ], so substract 2.
            beginLength = fSpec.length() - 2 - optionalportion;
            endLength = beginLength + optionalportion;
            if (dot != -1) {
              // adjust for dot.
              beginLength -= 1;
              endLength -= 1;
              prec = fSpec.length() - dot - 1;
            }
          }
          else {
            if (dot == -1) {
              beginLength = fSpec.length();
            }
            else {
              beginLength = fSpec.length() - 1; // exclude '.' from total
                                                // length.
              prec = fSpec.length() - dot;
            }
          }

          System.out.println("Registering dynamic value map for column " + col
              + ": " + line + " from " + beginLength
              + (endLength != -1 ? " to " + endLength + " length " : "")
              + " with " + prec + " precision");
          return new ColumnRandom(col, beginLength, endLength, prec);
        }
      },
      UUID {
        @Override
        public ColumnRange parse(String col, String line, Connection conn) {
          return new ColumnRandom(col, this);
        }

        @Override
        public String getNext() {
          return java.util.UUID.randomUUID().toString();
        }
      },
      LMTS {
        @Override
        public ColumnRange parse(String col, String line, Connection conn) {
          return new ColumnRandom(col, -1, -1, -1);
        }
      },
      LCTS {
        @Override
        public ColumnRange parse(String col, String line, Connection conn) {
          System.out.println("Registering LCTS value for column " + col + ": "
              + line);
          return new ColumnRandom(col, -1, -1, -1);
        }
      },
      nullvalue {

        @Override
        public boolean lineStartsWith(String line) {
          return line.startsWith(nullToken);
        }

        @Override
        public ColumnRange parse(String col, String line, Connection conn)
            throws Exception {
          System.out.println("Registering NULL value for column " + col + ": "
              + line);
          return new ColumnRandom(col, this);
        }

        public String getNext() {
          return nullToken;
        }
      },

      skipvalue {

        @Override
        public boolean lineStartsWith(String line) {
          return line.startsWith(skipToken);
        }

        @Override
        public ColumnRange parse(String col, String line, Connection conn)
            throws Exception {
          System.out
              .println("NOT Registering, rather skipping value for column "
                  + col + ": " + line);
          return new ColumnRandom(col, this);
        }

        public String getNext() {
          return skipToken;
        }
      };

      public boolean lineStartsWith(String line) {
        return line.startsWith(name());
      }

      public String getNext() {
        return null;
      }

      public abstract ColumnRange parse(String col, String line, Connection conn)
          throws Exception;

    }; // end of enum specialTokens

    static Object parse(String col, String line, Connection conn)
        throws Exception {
      if (line.indexOf(',') > 0 || line.indexOf('[') >= 0
          || line.indexOf("::") >= 0) {
        if (line.startsWith("::")) {

          line = line.substring("::".length());

          if (specialTokens.valuelist.lineStartsWith(line)) {
            return specialTokens.valuelist.parse(col, line, conn);
          }
          else if (specialTokens.random.lineStartsWith(line)) {
            return specialTokens.random.parse(col, line, conn);
          }
          else if (specialTokens.format.lineStartsWith(line)) {
            return specialTokens.format.parse(col, line, conn);
          }
          else if (specialTokens.UUID.lineStartsWith(line)) {
            return specialTokens.UUID.parse(col, line, conn);
          }
          else if (specialTokens.LCTS.lineStartsWith(line)) {
            return specialTokens.LCTS.parse(col, line, conn);
          }
          else if (specialTokens.LMTS.lineStartsWith(line)) {
            return specialTokens.LMTS.parse(col, line, conn);
          }
          else {
            System.out.println("Registering range for column " + col + ": "
                + line);
            String[] elems = line.substring(2).split(",");
            String start = null;
            String increment = null;
            int minNumber = -1;
            int maxNumber = -1;
            String foreachColumn = null;
            String number;
            for (String elem : elems) {
              elem = elem.trim();
              int spaceIndex = elem.indexOf(' ');
              String name = elem.substring(0, spaceIndex).trim();
              if ("start".equalsIgnoreCase(name)) {
                start = elem.substring(spaceIndex + 1).trim();
              }
              else if ("increment".equalsIgnoreCase(name)) {
                increment = elem.substring(spaceIndex + 1).trim();
              }
              else if ("number".equalsIgnoreCase(name)) {
                number = elem.substring(spaceIndex + 1);
                int rangeIndex = number.indexOf('-');
                if (rangeIndex > 0) {
                  minNumber = Integer.parseInt(number.substring(0, rangeIndex)
                      .trim());
                  maxNumber = Integer.parseInt(number.substring(rangeIndex + 1)
                      .trim());
                }
                else {
                  minNumber = maxNumber = Integer.parseInt(number.trim());
                }
              }
              else if ("foreach".equalsIgnoreCase(name)) {
                foreachColumn = elem.substring(spaceIndex + 1).trim();
              }
              else {
                throw new IllegalArgumentException("unknown token '" + name
                    + "'");
              }
            }
            if (start == null || increment == null) {
              throw new IllegalArgumentException(
                  "either start or increment not specified in range");
            }
            if (start.indexOf('.') >= 0 || increment.indexOf('.') >= 0) {
              return new ColumnRange(col, new BigDecimal(start),
                  new BigDecimal(increment), minNumber, maxNumber,
                  foreachColumn, true, null, false);
            }
            else {
              return new ColumnRange(col, Integer.parseInt(start),
                  Integer.parseInt(increment), minNumber, maxNumber,
                  foreachColumn, false, null, false);
            }
          }
        }
        else if (specialTokens.nullvalue.lineStartsWith(line)) {
          return specialTokens.nullvalue.parse(col, line, conn);
        }
        else if (specialTokens.skipvalue.lineStartsWith(line)) {
          return specialTokens.skipvalue.parse(col, line, conn);
        }
        else {
          System.out.println("Registering value map for column " + col + ": "
              + line);
          return new ColumnRange(col, null, null, -1, -1, null, false,
              line.split(","), false);
        }
      }
      else {
        return null;
      }
    }
  }

  static class ColumnRandom extends ColumnRange {

    final int beginLen;

    final int endLen;

    final int prec;

    final specialTokens fixedToken;

    public ColumnRandom(String col, int beginLen, int endLen, int prec) {
      super(col, null, null, 0, 0, null);
      this.beginLen = beginLen;
      this.endLen = endLen;
      this.prec = prec;
      this.fixedToken = null;
    }

    public ColumnRandom(String col, specialTokens token) {
      super(col, null, null, 0, 0, null);
      this.beginLen = -1;
      this.endLen = -1;
      this.prec = -1;
      this.fixedToken = token;
    }

    public String getNext(DataGenerator_ generator, int type, int cLen,
        int cPrec, HashSet<String> primaryKey, int tableIndex, String colName,
        int i) {

      String retVal = goForRepeatValue();
      if (retVal != null) {
        return retVal;
      }

      if (fixedToken != null) {
        this.currentValue = fixedToken.getNext();
      }
      else {
        int precision = (this.prec == -1 ? cPrec : this.prec);
        if (endLen == -1) {
          // fixed length.
          this.currentValue = generator.generateValues(type,
              (this.beginLen == -1 ? cLen : this.beginLen), precision,
              primaryKey, tableIndex, colName, i, this.beginLen != -1,
              qColName.substring(qColName.lastIndexOf(".") + 1) + "  ");
        }
        else {
          // configured variable length different than schema declared length.
          this.currentValue = generator.generateValues(type,
              rand.nextInt(endLen - beginLen) + beginLen, precision,
              primaryKey, tableIndex, colName, i, true,
              qColName.substring(qColName.lastIndexOf(".") + 1) + "  ");
        }
      }

      return currentValue.toString();
    }

    public String toString() {
      StringBuilder sb = new StringBuilder("colName=").append(qColName).append(
          " ");
      if (fixedToken != null) {
        sb.append("fixedToken=").append(fixedToken);
      }
      else {
        sb.append("beginLength=").append(beginLen);
        sb.append(";endLength=").append(endLen);
        sb.append(";prec=").append(prec);
      }

      return sb.toString();
    }
  }

  public DataGenerator_(String[] args) {
    if (args.length == 0) {
      System.out.println("Usage <script> <tablenames> "
          + "[<host:port> <rowcount> <column mapping file>]");
      System.exit(1);
    }
    try {
      if (args.length > 1) {
        if (args[1].startsWith("jdbc:")) {
          url = args[1];
        } else {
          url = "jdbc:gemfirexd://" + args[1];
        }
        System.out.println("url=" + url);
      }
      final Connection conn = getConnection();
      HashMap<String, Object> columnNameMapping = null;
      HashSet<String> columnsMapped = null;
      HashMap<String, RelationalOperator> colMapRelation = null;
      HashMap<String, ArrayList<String>> columnValueMapping = null;
      if (args.length > 3) {
        // setup the column mapping
        columnNameMapping = new HashMap<String, Object>();
        columnsMapped = new HashSet<String>();
        colMapRelation = new HashMap<String, RelationalOperator>();
        columnValueMapping = new HashMap<String, ArrayList<String>>();
        FileReader freader = new FileReader(args[3]);
        BufferedReader in = new BufferedReader(freader);
        String line;
        boolean skipMode = false;

        while ((line = in.readLine()) != null) {

          if (line.startsWith("*/")) {
            skipMode = false;
            continue;
          }
          else if (line.startsWith("##")) {
            System.out.println();
            continue;
          }
          else if (skipMode || line.startsWith("#")) {
            continue;
          }
          else if (line.startsWith("/*")) {
            skipMode = true;
            continue;
          }
          else if (line.startsWith("::foreach")) {
            StringTokenizer tokens = new StringTokenizer(line, " ");
            tokens.nextToken(); // skip over ::foreach

            String columnName = tokens.nextToken();
            if (!tokens.nextToken().equalsIgnoreCase("repeat")) {
              throw new Exception("Invalid specification in line: " + line);
            }
            int numTimes = Integer.parseInt(tokens.nextToken());
            repeaters.push(new Repeater(columnName, numTimes));
            continue;
          }
          else if (line.startsWith("::hcaerof")) {
            StringTokenizer tokens = new StringTokenizer(line, " ");
            tokens.nextToken(); // skip over ::foreach

            String columnName = tokens.nextToken();
            Repeater existing = repeaters.pop();
            if (!existing.columnName.equalsIgnoreCase(columnName)) {
              throw new Exception("foreach nesting mismatch for line: " + line);
            }
            continue;
          }

          RelationalOperator op = RelationalOperator.Equal;
          line = line.trim();
          if (line.length() > 0) {
            String[] mapping = line.split("=");
            if (mapping.length == 1) {
              mapping = line.split("<");
              assert mapping.length != 0;
              if (mapping.length > 1) {
                op = RelationalOperator.LessThan;
              }
            }
            String col = mapping[0].trim();
            String mappedColumnS = mapping[1].trim();
            Object mappedColumn;
            if ((mappedColumn = ColumnRange.parse(col, mappedColumnS, conn)) == null) {
              mappedColumn = mappedColumnS;
              System.out
                  .println("Registering mapping for column " + col + " to "
                      + mappedColumnS + " with " + op.name() + " relation");
            }
            else {
              String parentCol = ((ColumnRange)mappedColumn).foreachColumn;
              if (parentCol != null) {
                Object parentColRange = columnNameMapping.get(parentCol);
                if (parentColRange == null) {
                  parentColRange = new ColumnRange(col, null, null, -1, -1,
                      null, false, null, false);
                  ((ColumnRange)parentColRange).hasDependentForeach = true;
                  columnNameMapping.put(parentCol, parentColRange);
                }
                else if (parentColRange instanceof ColumnRange) {
                  ((ColumnRange)parentColRange).hasDependentForeach = true;
                }
                else {
                  throw new IllegalArgumentException(
                      "no value specifier for parent '" + parentCol
                          + "' found for child '" + col + "'");
                }
              }
            }
            if (columnNameMapping.put(col, mappedColumn) != null) {
              System.err.println("Multiple mappings for column " + col);
              System.exit(2);
            }

            if (mappedColumn == mappedColumnS) {
              columnsMapped.add(mappedColumnS);
              colMapRelation.put(mappedColumnS, op);
            }
          }
        }
        if (!repeaters.empty()) {
          throw new Exception("Invalid ::foreach pairing ...");
        }
        in.close();
      }
      final DatabaseMetaData meta = conn.getMetaData();
      final String[] types = { "TABLE" };
      final ArrayList<String> prevTables = new ArrayList<String>();
      int tableIndex = -1;
      for (String fullTableName : args[0].split(",")) {
        ResultSet rsColumns = null;
        fullTableName = fullTableName.trim();
        String schemaName = null;
        String tableName = fullTableName;
        int schemaIndex;
        if ((schemaIndex = fullTableName.indexOf('.')) >= 0) {
          schemaName = fullTableName.substring(0, schemaIndex);
          tableName = fullTableName.substring(schemaIndex + 1);
        } else {
          System.out.println("");
          System.out.println("ERROR: FullTableName should be mentioned with schema name. Input=" + fullTableName);
          System.out.println("");
          System.exit(2);
        }

        if (args.length > 2) {
          if (args[2].indexOf(',') > 0) {
            String[] multipleRowCount = args[2].split(",");
            rowCount = new int[multipleRowCount.length];
            for (int i = 0; i < multipleRowCount.length; i++) {
              rowCount[i] = Integer.parseInt(multipleRowCount[i]);
            }
            tableIndex++;
          }
          else {
            rowCount[0] = Integer.parseInt(args[2]);
            tableIndex = 0;
          }
        }

        ResultSet rs = meta.getTables(null, schemaName, tableName, types);
        while (rs.next()) {
          long startTime = System.currentTimeMillis();
          if (!tableName.equals(rs.getString("TABLE_NAME"))) {
            throw new RuntimeException("unexpected table name "
                + rs.getString("TABLE_NAME") + ", expected: " + tableName);
          }
          ResultSet keyList = meta.getPrimaryKeys(null, schemaName, tableName);
          HashSet<String> importedKeys = null;
          HashSet<String> primaryKey = null;
          while (keyList.next()) {
            if (primaryKey == null) {
              primaryKey = new HashSet<String>();
            }
            primaryKey.add(keyList.getString("COLUMN_NAME"));
          }

          ResultSet importList = meta.getImportedKeys(null, schemaName,
              tableName);
          while (importList.next()) {
            if (importedKeys == null) {
              importedKeys = new HashSet<String>();
            }
            importedKeys.add(importList.getString("PKCOLUMN_NAME") + " FK "
                + importList.getString("FKTABLE_NAME") + " FK COL "
                + importList.getString("FKCOLUMN_NAME"));
          }

          System.out.println();
          System.out.println("Processing Table:" + tableName
              + " with primary key: " + primaryKey + " for "
              + rowCount[tableIndex] + " rows");
          if (importedKeys != null) {
            System.out.println("Foreign Keys for Table:" + tableName + " "
                + importedKeys + " for " + rowCount[tableIndex] + " rows");
          }
          FileWriter fstream = new FileWriter(tableName + ".dat");
          BufferedWriter out = new BufferedWriter(fstream);
          rsColumns = meta.getColumns(null, schemaName, tableName, null);
          ArrayList<Object[]> columnsMeta = new ArrayList<Object[]>();
          while (rsColumns.next()) {
            Object[] columnMeta = new Object[4];
            columnMeta[0] = rsColumns.getString("COLUMN_NAME");
            columnMeta[1] = rsColumns.getInt("DATA_TYPE");
            columnMeta[2] = rsColumns.getInt("COLUMN_SIZE");
            columnMeta[3] = rsColumns.getInt("DECIMAL_DIGITS");
            columnsMeta.add(columnMeta);
          }

          final char sep = getFieldSeparator();
          String prevVal = null;
          StringBuilder columnList = new StringBuilder();
          for (int i = 0; i < rowCount[tableIndex]; ++i) {
            StringBuilder sb = new StringBuilder();
            int j = 0;
            for (Object[] columnMeta : columnsMeta) {
              String colName = (String)columnMeta[0];
              String qColName = fullTableName + '.' + colName;
              String value = null;
              // check for any column mapping
              if (columnNameMapping != null) {
                Object mappedColumn;
                if ((mappedColumn = columnNameMapping.get(qColName)) != null) {
                  Class<?> colClass = mappedColumn.getClass();
                  if (colClass == String.class) {
                    ArrayList<String> columnValues = columnValueMapping
                        .get(mappedColumn);
                    value = columnValues.get(i % columnValues.size());
                    if (i == 0) {
                      System.out.println("Mapping column " + qColName + " to "
                          + mappedColumn);
                    }
                  }
                  else if (colClass == String[].class) {
                    String[] mappedValues = (String[])mappedColumn;
                    value = mappedValues[i % mappedValues.length];
                    if (i == 0) {
                      System.out.println("Mapping column " + qColName
                          + " to values: " + Arrays.toString(mappedValues));
                    }
                  }
                  else if (colClass == ColumnRandom.class) {
                    value = ((ColumnRandom)mappedColumn).getNext(this,
                        (Integer)columnMeta[1], (Integer)columnMeta[2],
                        (Integer)columnMeta[3], primaryKey, tableIndex,
                        colName, i);
                    if (i == 0) {
                      System.out.println("Mapping column " + qColName + " to: "
                          + mappedColumn);
                    }
                  }
                  else {
                    value = ((ColumnRange)mappedColumn).getNext(i,
                        columnNameMapping, columnValueMapping);
                    if (i == 0) {
                      System.out.println("Mapping column " + qColName + " to: "
                          + mappedColumn);
                    }
                  }
                }
              }
              if (value == null) {
                value = generateValues((Integer)columnMeta[1],
                    (Integer)columnMeta[2], (Integer)columnMeta[3], primaryKey,
                    tableIndex, colName, i, false, null);
              }

              // if this is a mapped column then store its values
              if (columnsMapped != null && columnsMapped.contains(qColName)) {
                final ArrayList<String> columnValues;
                if (i == 0) {
                  columnValues = new ArrayList<String>();
                  System.out.println("Registering values for mapped column "
                      + qColName);
                  columnValueMapping.put(qColName, columnValues);
                }
                else {
                  columnValues = columnValueMapping.get(qColName);
                }

                String storeValue = value;

                // adjust the value before storing
                RelationalOperator op = colMapRelation != null ? colMapRelation
                    .get(qColName) : null;
                if (op != null && op != RelationalOperator.Equal) {
                  if (op == RelationalOperator.LessThan) {
                    if (i == 0) {
                      System.out.println("Reducing value for mapped column "
                          + qColName);
                    }
                    int type = (Integer)columnMeta[1];
                    switch (type) {
                      case java.sql.Types.BIGINT:
                      case java.sql.Types.INTEGER:
                      case java.sql.Types.SMALLINT: {
                        long lv = Long.parseLong(value);
                        lv -= (lv * 0.05); // reduce by 20%
                        storeValue = Long.toString(lv);
                        break;
                      }
                      case java.sql.Types.DOUBLE: {
                        double dv = Double.parseDouble(value);
                        dv -= (dv * 0.05); // reduce by 20%
                        storeValue = Double.toString(dv);
                        break;
                      }
                      case java.sql.Types.FLOAT: {
                        float fv = Float.parseFloat(value);
                        fv -= (fv * 0.05); // reduce by 20%
                        storeValue = Float.toString(fv);
                        break;
                      }
                      case java.sql.Types.DECIMAL:
                      case java.sql.Types.NUMERIC: {
                        BigDecimal bv = new BigDecimal(value.toCharArray());
                        bv = bv.movePointLeft(1);
                        storeValue = bv.toPlainString();
                        break;
                      }
                      default:
                        throw new RuntimeException(
                            "cannot handle column of type " + type);
                    }

                  }
                }

                columnValues.add(storeValue);
              }

              if (value == ColumnRange.nullToken) {
                if (i == 0) {
                  columnList.append(colName).append(sep);
                }
                sb.append(sep);
              }
              else if (value == ColumnRange.skipToken) {
                // skip this column entirely.
                if (i == 0) {
                  System.out.println("Skipping value entry for " + qColName);
                }
              }
              else {
                if (i == 0) {
                  columnList.append(colName).append(sep);
                }
                sb.append(value).append(sep);
              }
              ++j;
            } // end of ColumnMetadata

            if (i == 0) {
              columnList.setCharAt(columnList.length() - 1, '\n');
              out.write(columnList.toString());
              columnList = null; // render it unusable so that accidentally we
                                 // don't write again.
            }
            sb.setCharAt(sb.length() - 1, '\n');
            out.write(sb.toString());
          }
          out.close();
          System.out.println("    generation time elapsed: "
              + (System.currentTimeMillis() - startTime));
        }
        prevTables.add(fullTableName);
        // tableIndex = -1;
      }
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(2);
    }
  }

  protected String generateValues(int type, int cLen, int prec,
      HashSet<String> primaryKey, int tableIndex, String colName, int i,
      boolean absoluteLength, String baseValue) {
    String value;
    switch (type) {
      case java.sql.Types.CHAR:
      case java.sql.Types.VARCHAR:
      case java.sql.Types.CLOB: {
        value = generateString(cLen, absoluteLength);
        if (absoluteLength && baseValue != null
            && value.length() > baseValue.length()) {
          value = baseValue
              + (value.substring(0, value.length() - baseValue.length()));
        }
        break;
      }
      case java.sql.Types.BIGINT:
      case java.sql.Types.INTEGER:
        if (primaryKey == null || !primaryKey.contains(colName)) {
          value = String.valueOf((int)(Math.random() * rowCount[tableIndex]));
        } else {
          value = String.valueOf(i + 1);
        }
        break;
      case java.sql.Types.SMALLINT:
        if (primaryKey == null || !primaryKey.contains(colName)) {
          value = String.valueOf((int)(Math.random() * (rowCount[tableIndex] % Short.MAX_VALUE)));
        } else {
          value = String.valueOf(i + 1);
        }
        break;
      case java.sql.Types.DOUBLE: {
        value = String.valueOf(Math.random() * rowCount[tableIndex]);
        break;
      }
      case java.sql.Types.FLOAT: {
        value = Float.toString((float)(Math.random() * rowCount[tableIndex]));
        break;
      }
      case java.sql.Types.DATE:
        value = generateDate(null);
        break;
      case java.sql.Types.TIMESTAMP:
        value = generateTimestamp(null);
        break;
      case java.sql.Types.DECIMAL:
      case java.sql.Types.NUMERIC: {
        if (primaryKey == null || !primaryKey.contains(colName)) {
          value = generateNumeric(cLen, prec, absoluteLength);
        }
        else {
          value = String.valueOf(i + 1);
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
    return value;
  }

  protected char getFieldSeparator() {
    return ',';
  }

  protected String getFormatterTimestamp() {
    return "yyyy-MM-dd HH:mm:ss";
  }

  protected String getFormatterDate() {
    return "yyyy-MM-dd";
  }

  protected String getBaseDate() {
    return "2012-01-01";
  }

  protected String getBaseTime() {
    return "2012-01-01 01:01:00";
  }

  static final char[] chooseChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
      .toCharArray();

  static final char[] chooseBytes = "0123456789abcdef".toCharArray();

  protected String generateString(int len, boolean absoluteLength) {
    // first randomly choose the size of string to be generated
    if (len > 32000) {
      len = 32000;
    }
    if (!absoluteLength)
      len = (int)(Math.random() * 9 * len / 10) + 1 + (len / 10);
    final char[] chars = new char[len];
    for (int i = 0; i < len; i++) {
      chars[i] = chooseChars[(int)(Math.random() * chooseChars.length)];
    }
    return new String(chars);
  }

  protected String generateBytes(int len, boolean absoluteLength) {
    // first randomly choose the size of string to be generated
    if (len > 32000) {
      len = 32000;
    }
    if (!absoluteLength)
      len = (int)(Math.random() * 9 * len / 10) + 1 + (len / 10);
    len <<= 1;
    final char[] chars = new char[len];
    for (int i = 0; i < len; i++) {
      chars[i] = chooseBytes[(int)(Math.random() * chooseBytes.length)];
    }
    return new String(chars);
  }

  protected String generateDate(Date baseDate) {

    final SimpleDateFormat formatterDate = new SimpleDateFormat(
        getFormatterDate());
    try {
      baseDate = formatterDate.parse(getBaseDate());
    } catch (ParseException e) {
      e.printStackTrace();
      System.exit(2);
    }

    double addOn = Math.random() * 86400000 * 100;

    long newTime = baseDate.getTime() + (long)addOn;
    Date newDate = new Date(newTime);

    return formatterDate.format(newDate);
  }

  protected String generateTimestamp(Date baseDate) {

    final SimpleDateFormat formatterTimestamp = new SimpleDateFormat(
        getFormatterTimestamp());
    try {
      baseDate = formatterTimestamp.parse(getBaseTime());
    } catch (ParseException e) {
      e.printStackTrace();
      System.exit(2);
    }

    double addOn = Math.random() * 86400000 * 100;

    long newTime = baseDate.getTime() + (long)addOn;
    Date newDate = new Date(newTime);

    return formatterTimestamp.format(newDate);
  }

  static final char[] chooseInts = new char[] { '0', '1', '2', '3', '4', '5',
      '6', '7', '8', '9' };

  protected String generateNumeric(int len, int prec, boolean absoluteLength) {
    int major = len - prec;
    // first randomly choose the size of string to be generated
    if (major > 1) {
      if (major > 15) {
        major = 15;
      }
      if (!absoluteLength)
        major = (int)(Math.random() * major) + 1;
    }
    if (prec > 1 && !absoluteLength) {
      prec = (int)(Math.random() * prec) + 1;
    }
    len = major + prec;
    char[] chars = new char[len + 1];
    int i;
    for (i = 0; i < major; i++) {
      chars[i] = chooseInts[(int)(Math.random() * chooseInts.length)];
    }
    if (i < len) {
      chars[i] = '.';
      while (++i <= len) {
        chars[i] = chooseInts[(int)(Math.random() * chooseInts.length)];
      }
    }
    return new String(chars, 0, i);
  }

  public Connection getConnection() {
    Connection con = null;
    try {
      Class.forName(driver);
    } catch (java.lang.ClassNotFoundException e) {
      System.err.print("ClassNotFoundException: ");
      System.err.println(e.getMessage());
      System.exit(3);
    }

    try {
      java.util.Properties p = new java.util.Properties();
      p.setProperty("user", "locatoradmin");
      p.setProperty("password", "locatorpassword");
      con = DriverManager.getConnection(url, p);
    } catch (SQLException ex) {
      System.err.println("SQLException: " + ex.getMessage());
      System.exit(3);
    }

    return con;
  }

  public static void main(String[] args) {
    new DataGenerator_(args);
  }
}
