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
/**
 * 
 */
package com.pivotal.pxf.plugins.gemfirexd.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.lib.CombineFileSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.internal.ByteArrayDataInput;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.hadoop.mapred.Key;
import com.pivotal.gemfirexd.hadoop.mapred.Row;
import com.pivotal.gemfirexd.hadoop.mapred.RowInputFormat;
import com.pivotal.pxf.api.io.DataType;
import com.pivotal.pxf.api.utilities.ColumnDescriptor;
import com.pivotal.pxf.api.utilities.InputData;

/**
 * Manages instantiating a loner GemFireXD service and providing its handle
 * (connection) among other things.
 */
public class GemFireXDManager {

  /**
   * Start and shutdown should not mix up.
   */
  private static Object lockObject = new Object();
  private static boolean lonerSystemInUse = false;
  /**
   * The timestamp of the DDL file persisted on HDFS, read when the loner system
   * last came up. It'll tell us if the DDLs' file was updated after last
   * restart of this loner system. We can send this info to accessor, so it
   * restarts their loner instance only when needed.
   */
  private static long latestDDLFilesTimestamp = -1;

  private InputData inputData;
  private String homeDir = "";
  private InputSplit split;
  
  /**
   * String in the format: "schemaname.tablename"
   */
  private String schemaTableName = "";
  private ArrayList<String> gfxdColumnNames;
  private ArrayList<Integer> gfxdColumnTypes;
  private Logger logger;

  public static final byte RESTART_LONER_SYSTEM_CODE = 1;
  public static final byte HOME_DIR_CODE = 2;
  public static final byte SCHEMA_TABLE_NAME_CODE = 3;
  public static final byte SPLIT_CODE = 4;
  public static final int WAIT_ITERATION_LIMIT = 30;
  public static final String DATE_PATTERN = "yyyy.MM.dd'-'HH:mm:ss";
  public static final String DATE_PATTERN_Z = "yyyy.MM.dd'-'HH:mm:ssz";
  public static final String LOCATION_FORMAT = "LOCATION format: 'pxf://namenode_rest_host:namenode_rest_port/hdfsstore_homedir/schemaname.tablename?PROFILE=GemFireXD[&attribute=value]*'";

  /**
   * HAWQ users can define an external table with an additional column with this
   * name, provided it is of type TIMESTAMP.
   */
  public static final String RESERVED_COLUMN_TIMESTAMP = "GFXD_PXF_TS";
  /**
   * HAWQ users can define an external table with an additional column with this
   * name, provided it is of type VARCHAR.
   */
  public static final String RESERVED_COLUMN_EVENTTYPE = "GFXD_PXF_EVENTTYPE";
  public static final String GFXD_PROTOCOL = "jdbc:gemfirexd:";
  public static final String DRIVER_FOR_STAND_ALONE_GFXDIRE = "io.snappydata.jdbc.EmbeddedDriver";
  /**
   * This is only applicable for R/W tables. User can specify its value as
   * either 'true' or 'false'. Default is 'true', if not specified.
   */
  public static final String CHECKPOINT_NAME = "CHECKPOINT";
  /**
   * Indicates start timestamp of data that can be accessed. i.e. ignore data
   * with timestamp less than the specified one.
   */
  public static final String STARTTIME_NAME = "STARTTIME";
  /**
   * Indicates end timestamp of data that can be accessed. i.e. ignore data with
   * timestamp greater than the specified one.
   */
  public static final String ENDTIME_NAME = "ENDTIME";
  /**
   * We do not want to restart the loner system for every request coming to
   * fragmenter (or accessor). In case of concurrent requests coming in, we'll
   * wait for this much time before restarting the loner system. e.g. If a
   * request comes within RESTART_INTERVAL of startTime of the loner system,
   * we'll <b>not</b> restart the system but use the current instance.
   * 
   * This applies to fragmenter alone as accessor will depend solely upon the
   * info sent by fragmenter to decide on restart of loner system.
   * 
   * In milliseconds. TODO Make it configurable?
   */
  public static final long RESTART_INTERVAL = 1 * 60 * 1000;

  /**
   * GFXD_PXF_TS, GFXD_PXF_EVENT_TYPE
   */
  private static final int NUM_OF_RESERVED_COLUMNS = 2;

  public GemFireXDManager(InputData inData) {
    this(inData, false);
  }

  public GemFireXDManager(InputData inData, boolean isFragmenter) {
    this.inputData = inData;
    if (isFragmenter) {
      String[] info = deconstructPath(inData.getDataSource());
      this.homeDir = info[0];
      this.schemaTableName = info[1].toUpperCase(Locale.ENGLISH) + "."
          + info[2].toUpperCase(Locale.ENGLISH);
    }
    this.logger = LoggerFactory.getLogger(GemFireXDManager.class);
  }

  private void loadTableMetaData(ResultSet rs) throws SQLException {
    this.gfxdColumnNames = new ArrayList<String>();
    this.gfxdColumnTypes = new ArrayList<Integer>();
    while (rs.next()) {
      String name = rs.getString("COLUMN_NAME");
      int type = rs.getInt("DATA_TYPE");
      this.logger.debug("gfxd column name, type " + name + ", " + type);
      this.gfxdColumnNames.add(name);
      this.gfxdColumnTypes.add(type);
    }
  }

  /**
   * @return error message, if any.
   * @throws Exception
   */
  public String verifyUserAttributes() {
    String readMode = null, start = null, end = null;
    StringBuilder msg = new StringBuilder();

    // Check CHECKPOINT
    readMode = this.inputData.getUserProperty(CHECKPOINT_NAME);
    if (readMode != null && !"true".equalsIgnoreCase(readMode)
        && !"false".equalsIgnoreCase(readMode)) {
      msg.append("Value for optional attribute '" + CHECKPOINT_NAME
          + "' must either be 'true' or 'false'. ");
      this.logger.error(msg.toString());
    }

    // Check STARTTIME and ENDTIME values
    start = this.inputData.getUserProperty(STARTTIME_NAME);
    end = this.inputData.getUserProperty(ENDTIME_NAME);

    String errorMsg = validateTime(start);
    if (errorMsg == null) {
      errorMsg = validateTime(end);
      if (errorMsg != null) {
        msg.append(errorMsg);
      }
    } else {
      msg.append(errorMsg);
    }

    return msg.toString();
  }

  private String validateTime(String time) {
    if (time == null || time.trim().equals("")) {
      return null;
    }

    SimpleDateFormat sdfz = new SimpleDateFormat(DATE_PATTERN_Z);
    sdfz.setTimeZone(TimeZone.getTimeZone("GMT"));
    String str = null;
    try {
      sdfz.parse(time);
    } catch (ParseException pe) {
      SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN);
      sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
      try {
        sdf.parse(time);
      } catch (ParseException pez) {
        str = "Value of " + STARTTIME_NAME + " and/or " + ENDTIME_NAME
            + " could not be parsed. Expected format is '" + DATE_PATTERN
            + "' without the single quotes, e.g. 2013.12.31-23:59:59 "
            + "or, with timezone, 2013.12.31-23:59:59PST";
        this.logger.error(str);
      }
    }
    return str;
  }

  /**
   * true means no problem, false means bad data.
   * 
   * @return true means no problem, false means bad data.
   * @throws Exception
   */
  public boolean verifyTableSchema() {
    int extColumns = this.inputData.getColumns();

    if ((extColumns <= 0)
        || ((this.gfxdColumnNames.size() + NUM_OF_RESERVED_COLUMNS) < extColumns)) {
      this.logger.error("Number of columns (" + extColumns
          + ") in external table do no match with those ("
          + this.gfxdColumnNames.size() + ") in GemFireXD table.");
      return false;
    } else {
      if (!checkColumns(extColumns)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Assumes that the column sequence in external table definition is not the
   * same as that defined in gfxd table.
   * 
   * @param extColumns
   *          Number of columns defined in external table
   * @return true means no problem, false means bad data.
   */
  private boolean checkColumns(int extColumns) {
    boolean response = true;
    boolean[] match = new boolean[extColumns]; // All default to false.

    for (int extIdx = 0; extIdx < extColumns; extIdx++) {
      ColumnDescriptor extColumn = this.inputData.getColumn(extIdx);
      for (int gfxdIdx = 0; gfxdIdx < this.gfxdColumnNames.size(); gfxdIdx++) {
        if (this.gfxdColumnNames.get(gfxdIdx).equalsIgnoreCase(
            extColumn.columnName())) {
          if (matchColumnTypes(this.gfxdColumnTypes.get(gfxdIdx),
              extColumn.columnTypeCode())) {
            match[extIdx] = true;
            break;
          }
        }
      }

      // Check for reserved columns
      if (extColumn.columnName().equalsIgnoreCase(RESERVED_COLUMN_TIMESTAMP)) {
        // Make sure it has the right type.
        if (extColumn.columnTypeCode() == DataType.TIMESTAMP.getOID()) {
          match[extIdx] = true;
        } else {
          this.logger.error("Column '" + RESERVED_COLUMN_TIMESTAMP
              + "' must be of type 'TIMESTAMP', if defined.");
          response = false;
        }
      }

      // Check for reserved columns
      if (extColumn.columnName().equalsIgnoreCase(RESERVED_COLUMN_EVENTTYPE)) {
        // Make sure it has the right type.
        if (extColumn.columnTypeCode() == DataType.VARCHAR.getOID()) {
          match[extIdx] = true;
        } else {
          this.logger.error("Column '" + RESERVED_COLUMN_EVENTTYPE
              + "' must be of type 'VARCHAR', if defined.");
          response = false;
        }
      }
    }

    for (int i = 0; i < extColumns; i++) {
      if (!match[i]) {
        ColumnDescriptor cd = this.inputData.getColumn(i);
        this.logger.error("Did not find '" + cd.columnName() + "' with type id "
            + cd.columnTypeCode() + " in " + this.schemaTableName);
        response = false;
      }
    }
    return response;
  }

  public Logger getLogger() {
    return this.logger;
  }

  /**
   * 
   * @param sqlType
   *          Type of the column as defined in GemFireXD table.
   * @param pxfType
   *          Type of the column as defined in PXF external table.
   * @return True if columns' types match. False otherwise.
   */
  public static boolean matchColumnTypes(int sqlType, int pxfType) {
    switch (DataType.get(pxfType)) {
    case SMALLINT:
      return sqlType == Types.SMALLINT;

    case INTEGER:
      return sqlType == Types.INTEGER;

    case BIGINT:
      return sqlType == Types.BIGINT;

    case REAL:
      return sqlType == Types.REAL;

    case FLOAT8:
      return sqlType == Types.DOUBLE;

    case VARCHAR:
      return sqlType == Types.VARCHAR;

    case BOOLEAN:
      return sqlType == Types.BOOLEAN;

    case NUMERIC:
      return sqlType == Types.NUMERIC;

    case TIMESTAMP:
      return sqlType == Types.TIMESTAMP;

    case BPCHAR:
      return sqlType == Types.VARCHAR || sqlType == Types.CHAR;

    case BYTEA:
      // http://www.public.iastate.edu/~java/docs/guide/jdbc/mapping.doc.html
      return sqlType == Types.BINARY || sqlType == Types.BLOB;

    case TEXT:
      return sqlType == Types.VARCHAR;

    case DATE:
      return sqlType == Types.DATE;

    case TIME:
      return sqlType == Types.TIME;

    default:
      break;
    }
    return false;
  }

  /**
   * Checks the timestamp of DDL metadata of all the HDFS stores and caches the
   * most recent timestamp value.
   * 
   * @return true if the timestamp of any of the DDL files is changed since last
   *         check.
   */
  public boolean isDDLTimeStampChanged() throws IOException {
    boolean tsChanged = false;
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache == null) {
      return true;
    }
    ArrayList<HDFSStoreImpl> stores = cache.getAllHDFSStores();
    long newTimestamp = -1;

    long ts = latestDDLFilesTimestamp;
    for (HDFSStoreImpl store : stores) {
      newTimestamp = store.getDDLHoplogOrganizer().getCurrentHoplogTimeStamp();
      if (ts < newTimestamp) {
        if (latestDDLFilesTimestamp != -1) {
          this.logger
              .info("Update to DDLs at "
                  + store.getHomeDir()
                  + " detected. Loner system will be restarted. Old and new timestamps are "
                  + latestDDLFilesTimestamp + ", " + newTimestamp);
        }
        ts = newTimestamp;
        // We don't break here because we still need to get the most recent
        // timestamp.
      }
    }

    synchronized (lockObject) {
      if (latestDDLFilesTimestamp < ts) {
        tsChanged = true;
      }
      latestDDLFilesTimestamp = ts;
    }

    return tsChanged;
  }

  /**
   * @throws IOException
   */
  public void updateDDLTimeStampIfNeeded() throws IOException {
    if (latestDDLFilesTimestamp == -1) {
      isDDLTimeStampChanged();
    }
  }

  public boolean shutdown() {
    return shutdown(null);
  }

  public boolean shutdown(Properties props) {
    // This code is lifted from TestUtil.shutdown() with minor changes.
    synchronized (lockObject) {
      try {
        if (props == null) {
          props = new Properties();
          // props.setProperty("mcast-port", "0");
        }
        FabricService service = FabricServiceManager
            .currentFabricServiceInstance();
        if (service != null) {
          service.stop(props);
        }
      } catch (SQLException sqle) {
        if (((sqle.getErrorCode() == 50000) && ("XJ015".equals(sqle
            .getSQLState())))) {
          // we got the expected exception
        } else {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * Return input format
   * 
   * @param input
   * @return
   */
  public InputFormat<Key, Row> getInputFormat() {
    InputFormat<Key, Row> inFormat = new RowInputFormat();
    return inFormat;
  }

  public void configureJob(JobConf jobConf, String homeDirs) {
    String checkpoint = null, start = null, end = null;

    checkpoint = this.inputData.getUserProperty(CHECKPOINT_NAME);
    if (checkpoint == null) {
      checkpoint = "TRUE";
    }

    start = this.inputData.getUserProperty(STARTTIME_NAME);
    end = this.inputData.getUserProperty(ENDTIME_NAME);

    this.logger.debug("Setting checkpoint mode to " + checkpoint);
    jobConf.set(RowInputFormat.CHECKPOINT_MODE, checkpoint);

    if (start != null && !start.trim().equals("")) {
      jobConf.set(RowInputFormat.START_TIME_MILLIS, getTime(start, 0l));
    }
    if (end != null && !end.trim().equals("")) {
      jobConf.set(RowInputFormat.END_TIME_MILLIS,
          getTime(end, System.currentTimeMillis()));
    }
    this.logger.debug("Setting home-dir and schema.table in jobConf: "
        + this.homeDir + ", " + this.schemaTableName);
    jobConf.set(RowInputFormat.HOME_DIR, homeDirs);
    jobConf.set(RowInputFormat.INPUT_TABLE, this.schemaTableName);
  }

  private String getTime(String time, long defaultTime) {
    long longTime = defaultTime;
    SimpleDateFormat sdfz = new SimpleDateFormat(DATE_PATTERN_Z);
    sdfz.setTimeZone(TimeZone.getTimeZone("GMT"));
    try {
      longTime = sdfz.parse(time).getTime();
    } catch (ParseException pe) {
      SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN);
      sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
      try {
        longTime = sdf.parse(time).getTime();
      } catch (ParseException pez) {
        // Should not come here, if fragmenter did its job.
      }
    }
    return String.valueOf(longTime);
  }

  /**
   * Format:
   * "pxf://namenode_host:port/path_of_hdfsstore/schema.tablename?profile=GemFireXD&..."
   * 
   * @param path
   *          = "path_of_hdfsstore/schema.tablename" above.
   * @return A string array where string at index 0 contains hdfsstore path,
   *         string at index 1 contains schema name and string at index 2
   *         contains table name
   * @see #LOCATION_FORMAT
   */
  public static String[] deconstructPath(String path) {
    String[] result = new String[] { "", "", "" };
    // path will not have starting '/'

    // Extract the hdfs store path
    int index = path.lastIndexOf("/");
    if (index <= 0 || index == (path.length()-1)) {
      throw new IllegalArgumentException("Invalid LOCATION: " + path + ". "
          + LOCATION_FORMAT);
    }
    result[0] = "/" + path.substring(0, index);

    // Extract the schema name
    int periodIndex = path.indexOf(".");
    if (periodIndex <= 0 || periodIndex == (path.length()-1)) {
      throw new IllegalArgumentException(
          "No schemaname specified in LOCATION: " + path + ". "
              + LOCATION_FORMAT);
    }
    result[1] = path.substring(index + 1, periodIndex);

    // Extract the table name
    result[2] = path.substring(periodIndex + 1);
    return result;
  }

  public String getHomeDir() {
    return this.homeDir;
  }

  public String getTable() {
    return this.schemaTableName;
  }

  /**
   * Make sure we do not generate a lot of data here as this will be duplicated
   * per split and sent to HAWQ master and later to datanodes.
   * 
   * The sequence in which data is written to out must match the sequence it is
   * read in {@link #readUserData()}
   * 
   * <p>
   * Only called from Fragmenter.
   * 
   * @param cSplit
   * @return
   */
  public byte[] populateUserData(CombineFileSplit cSplit) throws IOException {
    // Construct user data
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutput out = new DataOutputStream(baos);

    // TODO Uncomment below statement (and its corresponding code in
    // readUserData()) when loner system is started from fragmenter as well as
    // from accessor.
    // 1. restart loner
    // out.write(RESTART_LONER_SYSTEM_CODE);
    // out.writeBoolean(this.restartLoner);

    // 2. home dir
    out.write(HOME_DIR_CODE);
    out.writeUTF(this.homeDir);

    // 3. schema.table
    out.write(SCHEMA_TABLE_NAME_CODE);
    out.writeUTF(this.schemaTableName);

    out.write(SPLIT_CODE);
    cSplit.write(out);

    // Serialize it and return
    return baos.toByteArray();
  }

  /**
   * This is only called from Accessor. The sequence in which switch cases
   * appear must match to the sequence followed in writing data to out in
   * {@link #populateUserData(FileSplit)}
   * 
   * @param data
   * @throws IOException
   */
  public void readUserData() throws IOException {
    byte[] data = this.inputData.getFragmentMetadata();
    if (data != null && data.length > 0) {
      boolean done = false;
      ByteArrayDataInput in = new ByteArrayDataInput();
      in.initialize(data, null);
      while (!done) {
        try {
          switch (in.readByte()) {
          case HOME_DIR_CODE:
            this.homeDir = in.readUTF();
            this.logger.debug("Accessor received home dir: " + this.homeDir);
            break;
          case SCHEMA_TABLE_NAME_CODE:
            this.schemaTableName = in.readUTF();
            this.logger.debug("Accessor received schemaTable name: "
                + this.schemaTableName);
            break;
          case SPLIT_CODE:
            this.split = new CombineFileSplit();
            this.split.readFields(in);
            this.logger.debug("Accessor split read, total length: " + this.split.getLength());
            done = true;
            break;
          default:
            this.logger.error("Internal error: Invalid data from fragmenter.");
            done = true;
            break;
          }
        } catch (EOFException eofe) {
          this.logger.error("Internal error: Invalid data from fragmenter.");
          break; // from while().
        }
      }
    }
  }

  public void resetLonerSystemInUse() {
    synchronized (lockObject) {
      lonerSystemInUse = false;
      lockObject.notifyAll();
    }
  }

  public InputSplit getSplit() {
    return this.split;
  }
}
