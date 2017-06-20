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
package com.pivotal.gemfirexd.internal.tools.dataextractor.loader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import com.pivotal.gemfirexd.internal.tools.dataextractor.extractor.GemFireXDDataExtractorImpl;
import com.pivotal.gemfirexd.internal.tools.dataextractor.utils.ExtractorUtils;

public class GemFireXDDataExtractorLoaderImpl extends GemFireXDDataExtractorImpl{

  private static final String DRIVER_STRING = "io.snappydata.jdbc.ClientDriver";
  private static final String LOADER_OUTPUT_DIR = "EXTRACTED_LOADER";
  private String stringDelimiter = "\"";
  private String columnDelimiter = ",";
  private String RECOMMENDED_FILE_ARG = "recommended";
  private String HOST_ARG = "host";
  private String PORT_ARG = "port";
  private String host;
  private String port;
  
  public GemFireXDDataExtractorLoaderImpl() throws Exception {
    extractorProperties.setProperty(RECOMMENDED_FILE_ARG, "");
    extractorProperties.setProperty(HOST_ARG, "localhost");
    extractorProperties.setProperty(PORT_ARG, "1527");
    extractorProperties.setProperty(STRING_DELIMITER, DEFAULT_STRING_DELIMITER);
  }

  public static void main(String[] args) throws Exception {
    GemFireXDDataExtractorLoaderImpl loader = new GemFireXDDataExtractorLoaderImpl();
    
    try {
      loader.processArgs(args);
      loader.consumeProperties();
      loader.configureLogger();
      loader.loadDriver(DRIVER_STRING);
      //loader.setupConnection();
      loader.playFile();
    }
    finally {
      if (logFileHandler != null) {
        logFileHandler.flush();
        logFileHandler.close();
      }
    }
  }

  public String getSalvageDirName() {
    return LOADER_OUTPUT_DIR;
  }
  
  @Override
  public void consumeProperties() throws IOException {
    super.consumeProperties();
    host = extractorProperties.getProperty(HOST_ARG);
    port = extractorProperties.getProperty(PORT_ARG);
  }
  private void setupConnection() throws SQLException {
    Properties properties = this.createConnectionProperties(false);
    properties.put("skip-constraint-checks", "true");
    super.getConnection("jdbc:gemfirexd://" + host + ":" + port, properties);
  }
  
  void playFile() throws FileNotFoundException, IOException, SQLException {
    String fileName = extractorProperties.getProperty(RECOMMENDED_FILE_ARG);
    if (fileName.equals("")) {
      logSevere("Please provide the recommended file from the salvager tool");
      return;
    }
    playFile(new File(fileName));
  }
  
  private void playFile(File file) throws FileNotFoundException, IOException, SQLException {
    if (!file.exists()) {
      logSevere("The specified Recommended.txt file: " + file.getAbsolutePath() + " does not exist");
      return;
    }
    
    //After getting the correct recommended file setup the connection
    setupConnection();
    stringDelimiter = extractorProperties.getProperty(STRING_DELIMITER);
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new FileReader(file));
      String line = reader.readLine();
      if (line != null) {
        //first line should be ddl
        try {
          logInfo("Loading .sql file: " + line);
          loadDDL(line);
        }
        catch (Exception e) {
          logSevere("Unable to load sql file: " + line, e);
        }
      }
      else {
        logSevere("No ddl or possibly empty Recommended.txt located at: " + file.getAbsolutePath());
        return;
      }
      line = reader.readLine();
      while (line != null) {
        try {
          loadCSV(line);
        }
        catch (Exception e) {
          logSevere("Unable to load csv file: " + line, e);
        }
        line = reader.readLine();
      }
    }
    finally {
      if (reader != null) {
        reader.close();
      }
    }
  }
  
  private void loadDDL(String ddlFileName) throws IOException, SQLException {
    List<String> ddlStatements = ExtractorUtils.readSqlStatements(ddlFileName);
    ExtractorUtils.executeDdlFromSqlFile(jdbcConn, ddlStatements);
  }

  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
  private void loadCSV(String csvFileName) throws SQLException {
    PreparedStatement statement = null;
    try {
      String[] path = csvFileName.split(Pattern.quote(""+File.separatorChar));
      String shortName = path[path.length-1];
      String[] splitShortName = shortName.split("-");
      String schema = splitShortName[1];
      String tableName = splitShortName[2];
      final String callString = "CALL SYSCS_UTIL.IMPORT_TABLE_EX ('" + schema + "', '" + tableName + "', '" + csvFileName + "' , '" +
      		columnDelimiter + "', '" + stringDelimiter + "', null, 0, " + 
          "0, " +  /* don't lock the table */
          "6, " + /* threads to use for import */
          "0, " +  /* case insensitive table name */
          "null, " +  /* use the default import implementation */
          "null)"; /* unused, null required */
      logInfo("Executing :" + callString);
      statement = jdbcConn.prepareStatement(callString);
      statement.execute();
    }
    finally {
      if (statement != null) {
        statement.close();
      }
    }
  }

  public void processArgs(String []args) throws Exception {
    for (String arg : args) {
      if (arg.equals(HELP_OPT)) {
        extractorProperties.setProperty(HELP_OPT, Boolean.toString(Boolean.TRUE));
      } else {
        String [] tokens = arg.split("=");
        if (tokens.length < 2) {
          throw new Exception("Invalid argument : " +  arg);
        }
        String key = tokens[0].trim();
        String value = tokens[1].trim();

        if (extractorProperties.containsKey(key)) {
          extractorProperties.setProperty(key, value);
        } else {
          throw new Exception("Invalid option : " + key);
        }
      }
    }
  }
}



