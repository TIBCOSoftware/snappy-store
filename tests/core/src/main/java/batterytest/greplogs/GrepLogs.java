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
package batterytest.greplogs;
import java.io.*;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author kbanks
 */
public class GrepLogs {

  public static class GrepLogsException extends Exception { 
    GrepLogsException(String msg) { super(msg); }
  }

  public PrintStream output = System.out;
  /** Its ok to call system exit and write to System.err */
  private boolean hydraMode = false;
  private int threshold = 0;
  private int errorLimit = 5000;
  private int repeatLimit = 5;
  
  /** All gfxd unittest result directories start with this prefix */
  private static String GFXD_UNITTEST_PREFIX = "snappydata-store-";
  private static String PARALLEL_SUFFIX = "-parallel";
  private String type = "";
  private File resultPath = null;
  
  private boolean skipLogMsgs = false;
  private final List testExpectStrs;
 
  private static final Pattern logFilePattern = Pattern.compile(".*\\.(?:log|out|txt)");
  private static final Pattern logFileIgnorePattern = Pattern.compile(".*(?:mergedLogs.txt|errors.txt|derby.log|batterytest.log|resultReport.txt)");
  
  
  /**
   * @param args
   */
  public static void main(String[] args) throws GrepLogsException {
    GrepLogs grep = new GrepLogs(args);
    grep.process();
  }
  
  public void process() throws GrepLogsException {
    int count = grepForErrors();
    boolean failure = false; 
    StringBuilder summary = new StringBuilder();
    if ( count != 0 ) {
      if ( count > errorLimit) {
        summary.append("ERROR COUNT LIMIT EXCEEDED and search had been stopped!!\n")
               .append("FAILED: Search found a total of ")
               .append(count).append(" suspect strings.\n");
        failure = true; 
      } else {
        if ( count <= threshold ) {
          summary.append("WARNING: Search found a total of ")
                 .append(count).append(" suspect strings (")
                 .append(threshold).append(") allowed.\n");
          
        } else {
          summary.append("FAILED: Search found a total of ")
                  .append(count).append(" suspect strings (")
                  .append(threshold).append(") allowed.\n");
          failure = true; 
        }      
      }
      if (count < threshold) {
        summary.append("         Consider setting -threshold lower (congratulations!)\n");
      }
    } else {
      summary.append("OK: Search found no suspect strings (")
             .append(threshold)
             .append(" allowed).\n");
    }
    output.print(summary.toString());
    if ( ! hydraMode) {
      System.err.print(summary.toString());
    }
    if ( failure ) {
      throw new GrepLogsException("Suspect strings were found in the logs.");
    }
  }

public GrepLogs(String[] cmdLine) throws GrepLogsException {
    for(int i = 0; i < cmdLine.length; i++) {
      
      if( cmdLine[i].equals("-h")) {
        usage();
      } else if(cmdLine[i].equals("-hydra")) {
        hydraMode = true;
      } else if(cmdLine[i].equals("-out")) {     
        if(++i == cmdLine.length) {
          usage(); //out of arguments
        }
        try {
          File outFile = new File(cmdLine[i]);
          File base = outFile.getParentFile();
          if(base == null) base = new File(".");
          if (!base.exists()) {
            if (!base.mkdirs()) {
              String msg = "Unable to create, " + base.getAbsolutePath();
              throw new FileNotFoundException(msg); 
            }
          }
          output = new PrintStream(new FileOutputStream(outFile), true);
        } catch (FileNotFoundException e) {
          String msg = "Unable to write to output file " + cmdLine[i];
          if ( ! hydraMode ) {
            System.err.println(msg);
          }
          throw new GrepLogsException(msg);
        }
      } else if(cmdLine[i].equals("-type")) {
        if(++i == cmdLine.length) {
          usage(); //out of arguments
        }
        type = cmdLine[i];
      } else if(cmdLine[i].equals("-limit")) {
        if(++i == cmdLine.length) {
          usage(); //out of arguments
        }
        errorLimit = Integer.parseInt(cmdLine[i]);
      } else if(cmdLine[i].equals("-repeats")) {
        if(++i == cmdLine.length) {
          usage(); //out of arguments
        }
        repeatLimit = Integer.parseInt(cmdLine[i]);
      } else if(cmdLine[i].equals("-threshold")) {
        if(++i == cmdLine.length) {
          usage(); //out of arguments
        }
        threshold = Integer.parseInt(cmdLine[i]);
      } else {
        resultPath = new File(cmdLine[i]);   
      }
    }
    
    if ( resultPath == null || (! resultPath.exists())) {
      throw new IllegalArgumentException("results directory did not exist");
    }
    if ( output == null ) {
      throw new IllegalArgumentException("output file was not specified");
    }
    if ( type.equals("")) {
      type = resultPath.getName();
      // Hack for GemFireXD, strip off gemfirexd-
      type = type.replace(GFXD_UNITTEST_PREFIX, "");
      if (type.endsWith(PARALLEL_SUFFIX)) {
        type = type.substring(0, type.length() - PARALLEL_SUFFIX.length());
      }
    }
    testExpectStrs = ExpectedStrings.create(type);
    skipLogMsgs = ExpectedStrings.skipLogMsgs(type);
  }

public int grepForErrors() {
  int errorCount = 0;
  Set logs = new HashSet();
  findAllLogs(resultPath, logs);
  for(Iterator iter = logs.iterator(); iter.hasNext(); ) {
    File log = (File) iter.next();
    errorCount += examineLog(log);
    if ( errorCount > errorLimit) {
      output.println("\n\nError count limit exceeded limit of " + errorLimit);
      output.println("Stopping grepLogs to prevent filling disk.");
      output.println("errorcntlimit=" + errorLimit);
      output.println("errors=" + errorCount);
      return errorCount;
    }
  }
  return errorCount;
}

private int examineLog(File log) {
  
  int count = 0 ;
  LineNumberReader reader = null;
  
  try {
    reader = new LineNumberReader(new FileReader(log));
  } catch( FileNotFoundException fnfe) {
    if ( ! hydraMode) {
      System.err.println("ERROR: cannot open " + log.getAbsolutePath());
    }
    return count++;
  }
  
  LogConsumer consumer = new LogConsumer(skipLogMsgs, testExpectStrs, log.getAbsolutePath(), repeatLimit);
  
  StringBuilder buffer = new StringBuilder();
  
  String line = null;
  try { 
    while( (line = reader.readLine()) != null) {
      StringBuilder suspectString = consumer.consume(line);
      if(suspectString != null) {
        count++;
        buffer.append(suspectString);
     // This stops printing if we hit a global error count limit which
        // is defined at the top of this script
        if ( count == errorLimit ) {
           buffer.append("\n\nError count limit exceeded limit of ")
                .append(errorLimit)
                .append("\nStopping grepLogs to prevent filling disk.\n")
                .append("errorcntlimit=")
                .append(errorLimit)
                .append("errors=")
                .append(count)
                .append("\n");
          output.print(buffer.toString());
          return count;
        }
      }
    } 
  } catch(IOException ioe) {
    if ( ! hydraMode ) {
      System.err.println("ERROR: reading " + log.getAbsolutePath() 
                         + "near line " + reader.getLineNumber());
    }
    return count++;
  } finally {
    if ( reader != null) try { reader.close(); } catch (IOException ignore) {}
  }
  StringBuilder suspectString = consumer.close();
  if(suspectString != null) {
    buffer.append(suspectString);
    count++;
  }
  output.print(buffer.toString());
  return count;
}

private void findAllLogs(File search, Set logs) {
   if ( search.isDirectory() ) {
    File[] search2 = search.listFiles();
    if ( search2 == null ) {
      return; //empty directory
    }
    for( int i=0; i < search2.length; i++) {
      findAllLogs(search2[i], logs);
    }
  } else {
    String path = search.getPath();
    if (logFilePattern.matcher(path).matches()
        && ! logFileIgnorePattern.matcher(path).matches()) {
      logs.add(search); 
    }
    return;
  }
}

private static void usage() throws GrepLogsException {
    System.err.println("Usage: java GrepLogs {<args>} <pathtoresults>/"
                       + "[dunit|java|query|smoke|moresmoke|perf]");
    System.err.println();
    System.err.println("-out <fname>     output file (default is stdout)");
    System.err.println("-threshold <num> number of strings to tolerate "
                       + "(default is 0)");
    System.err.println("-limit <num>     maximum number of errors");
    System.err.println("-repeats <num>   Stop counting after <num> hits");
    System.err.println("-type <str>      test type, one of:");
    System.err.println("   junit, dunit, java, query, smoke, moresmoke, perf");
    System.err.println("   battery.  Default based on directory name.");
    System.err.println("-hydra           Dont write to System.err");
    System.err.println("-h               this message");
    System.err.println();
    System.err.println("If the test type is unknown then batterytest type "
                       + "defaults will be used.");
    throw new GrepLogsException("Usage");
}

}
