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

package com.pivotal.gemfirexd.internal.engine.distributed.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import com.gemstone.gemfire.internal.lang.StringUtils;
import com.google.common.io.ByteStreams;
import org.apache.log4j.Logger;

public class LogFileUtils {

  private static Logger logger = Logger.getLogger(LogFileUtils.class);

  private static final long defaultBytes = 1024 * 100;

  /**
   * Get the recent part of the log files with default length of bytes
   */
  public static Map<String, Object> getLog(File logDir, String logFile) {
    return getLog(logDir, logFile, null, defaultBytes);
  }

  /**
   * Get the part of the log files given the offset and desired length of bytes
   */
  public static Map<String, Object> getLog(File logDir, String logFile, Long offset, Long byteLength) {

    Map<String, Object> logsMap = new HashMap<String, Object>();

    if (logFile == null || StringUtils.isEmpty(logFile)) {
      logger.error("Unable to get log file.." + logFile);

      logsMap.put("logText", "Unable to get log file : " + logFile);
      logsMap.put("startIndex", 0L);
      logsMap.put("endIndex", 0L);
      logsMap.put("totalLength", 0L);

      return logsMap;
    }

    try {
      File[] files = getSortedFiles(logFile, logDir);
      Long[] fileLengths = new Long[files.length];
      Long totalLength = 0L;
      for (int i = 0; i < files.length; i++) {
        fileLengths[i] = files[i].length();
        totalLength += files[i].length();
      }

      // set byteLength if not specified
      if(byteLength == null){
        byteLength = defaultBytes;
      }

      // set offset if not specified
      if (offset == null) {
        offset = totalLength - byteLength;
      }

      Long startIndex;
      if (offset < 0) {
        startIndex = 0L;
      } else if (offset > totalLength) {
        startIndex = totalLength;
      } else {
        startIndex = offset;
      }

      Long endIndex = Math.min(startIndex + byteLength, totalLength);

      logger.debug("Getting log from " + startIndex + " to " + endIndex);

      // getting log text
      String logText = getOffsetBytes(files, fileLengths, totalLength, startIndex, endIndex);

      logger.debug("Got log of length " + logText.length() + " bytes");

      logsMap.put("logText", logText);
      logsMap.put("startIndex", startIndex);
      logsMap.put("endIndex", endIndex);
      logsMap.put("totalLength", totalLength);

    } catch (Exception e) {
      logger.error("Error getting logs due to exception: " + e.getMessage(), e);

      logsMap.put("logText", "Error getting logs due to exception: " + e.getMessage());
      logsMap.put("startIndex", 0L);
      logsMap.put("endIndex", 0L);
      logsMap.put("totalLength", 0L);

    }

    return logsMap;
  }

  private static File[] getSortedFiles(String logFileName, File logDirectory) {
    File[] files = logDirectory.listFiles(new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.toLowerCase().startsWith(logFileName.toLowerCase());
      }
    });
    // sort files in descending order
    Arrays.sort(files, new LogFileComparator(LogFileComparator.DESCENDING_ORDER));
    return files;
  }

  /**
   * Return a string containing data across a set of files. The `startIndex`
   * and `endIndex` is based on the cumulative size of all the files take in
   * the given order. See figure below for more details.
   */
  private static String getOffsetBytes(File[] files,
      Long[] fileLengths,
      Long totalLength,
      Long start,
      Long end) {

    if (files.length != fileLengths.length) {
      logger.debug("Log Files count and their lengths counts are not matching");
      return "Error: Log Files count and their lengths counts are not matching";
    }

    Long startIndex = Math.max(start, 0);
    Long endIndex = Math.min(end, totalLength);

    int stringBufferSize = (int)(endIndex - startIndex);
    StringBuffer stringBuffer = new StringBuffer(stringBufferSize);
    Long sum = 0L;

    for (int i = 0; i < files.length; i++) {
      Long startIndexOfFile = sum;
      Long endIndexOfFile = sum + fileLengths[i];

      logger.debug("Processing file " + files[i] + ", with start index = " + startIndexOfFile +
          ", end index = " + endIndex);

      /*
                                      ____________
       range 1:                      |            |
                                     |   case A   |

       files:   |==== file 1 ====|====== file 2 ======|===== file 3 =====|

                     |   case B  .       case C       .    case D    |
       range 2:      |___________.____________________.______________|

       */

      if (startIndex <= startIndexOfFile && endIndex >= endIndexOfFile) {
        // Case C: read the whole file
        stringBuffer.append(getOffsetBytes(
            files[i].getAbsolutePath(), fileLengths[i], 0L, fileLengths[i]));
      } else if (startIndex > startIndexOfFile && startIndex < endIndexOfFile) {
        // Case A and B: read from [start of required range] to [end of file / end of range]
        Long effectiveStartIndex = startIndex - startIndexOfFile;
        Long effectiveEndIndex = Math.min(endIndex - startIndexOfFile, fileLengths[i]);
        stringBuffer.append(getOffsetBytes(
            files[i].getAbsolutePath(), fileLengths[i], effectiveStartIndex, effectiveEndIndex));
      } else if (endIndex > startIndexOfFile && endIndex < endIndexOfFile) {
        // Case D: read from [start of file] to [end of require range]
        Long effectiveStartIndex = Math.max(startIndex - startIndexOfFile, 0);
        Long effectiveEndIndex = endIndex - startIndexOfFile;
        stringBuffer.append(getOffsetBytes(
            files[i].getAbsolutePath(), fileLengths[i], effectiveStartIndex, effectiveEndIndex));
      }
      sum += fileLengths[i];
    }

    return stringBuffer.toString();
  }

  /**
   * Return a string containing part of a file from byte 'start' to 'end'.
   */
  private static String getOffsetBytes(String filePath, Long fileLength, Long start, Long end) {

    File file = new File(filePath);
    if (file == null || StringUtils.isEmpty(filePath)) {
      logger.debug("File is not specified.." + filePath);
      return "File is not specified..";
    }

    Long effectiveStart = Math.max(0, start);
    Long effectiveEnd = Math.min(fileLength, end);

    int buffLength = (int)(effectiveEnd - effectiveStart);
    byte[] buff = new byte[buffLength];
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(file);
      if (fis != null) {
        ByteStreams.skipFully(fis, effectiveStart);
        ByteStreams.readFully(fis, buff);
      } else {
        logger.debug("Could not read File [ " + filePath + " ]");
        return "Could not read File [ " + filePath + " ]";
      }

    } catch (FileNotFoundException e) {
      logger.error("FileNotFoundException: " + e.getMessage(), e);
    } catch (IOException e) {
      logger.error("IOException: " + e.getMessage(), e);
    } finally {
      if (fis != null) {
        try {
          fis.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    return new String(buff);
  }

  /**
   * Comparator to sort log files by file name as human expect
   */
  private static class LogFileComparator implements Comparator<File> {

    public static final boolean ASCENDING_ORDER = true;
    public static final boolean DESCENDING_ORDER = false;

    private Boolean isAsceding;

    LogFileComparator() {
      this.isAsceding = true;
    }

    LogFileComparator(Boolean order) {
      this.isAsceding = order;
    }

    @Override
    public int compare(File f1, File f2) {
      int n1 = extractNumber(f1.getName());
      int n2 = extractNumber(f2.getName());

      return isAsceding ? (n1 - n2) : (n2 - n1);
    }

    private int extractNumber(String name) {
      int i;
      try {
        int s = name.indexOf(".log") + 5;

        String number = name.substring(s);
        i = Integer.parseInt(number);
      } catch (Exception e) {
        // if filename does not match the format
        // then default to 0
        i = 0;
      }
      return i;
    }
  }

}
