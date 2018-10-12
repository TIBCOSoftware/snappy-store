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

package batterytest;

import hydra.FileUtil;
import hydra.HostHelper;
import hydra.HostHelper.OSType;
import hydra.HydraRuntimeException;
import hydra.HydraTimeoutException;
import hydra.Log;
import hydra.MasterController;
import hydra.ProcessMgr;
import hydra.ProductVersionHelper;
import hydra.VmPrms;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;
import java.util.Vector;

import resultsUtil.BugReportTemplate;
import resultsUtil.RegressionSummary;
import util.TestHelper;
import batterytest.greplogs.GrepLogs;
import batterytest.greplogs.GrepLogs.GrepLogsException;

import com.gemstone.gemfire.LogWriter;

/**
 *  Set up a batterytest by creating a test file containing a list of
 *  tests to run, relative to $JTESTS if the file is otherwise not
 *  found.  See the grammar in <A
 *  href="batterytest_grammar.txt">batterytest_grammar.txt</A>.  For
 *  example,
 *
 *  <p>
 *
 *  <blockquote><pre>
 *     tests.bt:
 *         collections/collperf.conf numHosts=5
 *         locks/locktest.conf
 *         include ${JTESTS}/license/license.bt
 *  </pre></blockquote>
 *
 *  <p>
 *
 *  This will run the tests
 *  <code>$JTESTS/collections/collperf.conf</code> and
 *  <code>$JTESTS/locks/locktest.conf</code>.
 *
 *  <p>
 *
 *  A .bt file can live anywhere, for example, in the same directory
 *  as the tests it references.
 *
 *  <p>
 *
 *  Tests can be commented out using C or C++ style comments.
 *  Multiple tests can be listed on the same line as long as they are
 *  separated by whitespace.  A test name can only contain whitespace
 *  if it is quoted.
 *
 *  <p>
 *
 *  A test can depend on any number of "system properties" used in the
 *  hydra configuration file via the ${} syntax.  Properties are
 *  passed to the hydra master and used when parsing the hydra
 *  configuration file.  They are intended for use only when using
 *  regular hydra configuration parameters would be cumbersome, for
 *  example, when scaling the number of hosts used in a test.  See <A
 *  href="../hydra/hydra_grammar.txt">hydra_grammar.txt</A> for more
 *  details on using properties in test configurations.
 *
 *  <p>
 *
 *  Default values for system properties can optionally be specified
 *  in a .prop file by the same name and in the same directory as the
 *  test, whether that is relative to the current directory or to
 *  $JTESTS.  Batterytest will seek the .prop file automatically.
 *  There is no need to indicate to batterytest whether such a file
 *  exists, and there is no way to force batterytest to look in
 *  another location for this file.
 *
 *  <p>
 *
 *  The {@link HostHelper#ONLY_ON_PLATFORMS_PROP onlyOnPlatforms}
 *  system property can be used to specify the platforms on which a
 *  test is be run.  If <code>onlyOnPlatforms</code> is set to a value
 *  that doesn't contain the current platform, the test is not run.
 *
 *  <p>
 *
 *  Values for properties can also be specified after the test name in
 *  the .bt file.  Any properties specified in the .bt file will
 *  override those in the .prop file.  A property value cannot contain
 *  whitespace.  They can be given on the same line as the test or on
 *  subsequent lines.  Each property must be separated from the next
 *  with whitespace.
 *
 *  <p>
 *
 *  To override hydra configuration parameters for all tests in a
 *  suite, create a "local.conf" file in the directory where
 *  batterytest is being run.  Or, specify an alternative local.conf
 *  on the command line.
 *
 *  <p>
 *
 *  Usage:
 *  <blockquote><pre>
 *    java -DGEMFIRE=&lt;path_to_gemfire_product_tree&gt;
 *         -DJTESTS=&lt;path_to_test_classes&gt;
 *         -DtestFileName=&lt;batterytest_input_file&gt;
 *         [-DHADOOP_DIST=&lt;path_to_Hadoop_distribution&gt;]
 *         [-DEXTRA_JTESTS=&lt;path_to_test_auxiliary_classes&gt;]
 *         [-DGFMON=&lt;path_to_product-testcopy_in_gfmon_product_tree&gt;]
 *         [-DREGRESSION_EXTRA_PATH=&lt;extra_path_elements_in_autogenerated_regression_directory&gt;]
 *         [-DRELEASE_DIR=&lt;path_to_gemfire_release_dir&gt;]
 *         [-DJPROBE=&lt;path_to_jprobe_product_tree&gt;]
 *         [-DlocalConf=&lt;local_conf_filename(default:$pwd/local.conf)&gt;]
 *         [-DresultDir=&lt;cwd_for_batterytest(default:$cwd)&gt;]
 *         [-DnumTimesToRun=&lt;number_of_times_to_run_tests_in_input_file(default:1)&gt;]
 *         [-DlogLevel=&lt;batterytest_log_level(default:info)&gt]
 *         [-DprovideRegressionSummary=&lt;whether_to_provide_a_regression_summary_report(default:true)&gt;]
 *         [-DremovePassedTest=&lt;whether_to_remove_test_result_dir_for_passed_tests(default:false)&gt;]
 *         [-DcodeCoverage=&lt;javaagent_command_for_code_coverage(default:null)&gt;]
 *         [-DgrepLogs=&lt;search logs for suspect strings(default:false)&gt;]
 *         [-DgrepLogsHeapMB=&lt;max_size_of_greplogs_heap_in_megabytes(default:unspecified)&gt;]
 *         [-DgrepLogsWaitSec=&lt;time_in_seconds_to_wait_for_grepLogs_to_complete(default:3600)&gt;]
 *         [-DnukeHungTest=&lt;whether_to_nuke_hung_test_and_keep_going(default:true)&gt;]
 *         [-DmoveRemoteDirs=&lt;whether_to_move_remote_dirs_to_master_dir(default:false)&gt;]
 *         [-DmoveHadoopData=&lt;whether_to_move_hadoop_data_dirs_to_master_dir(default:false)&gt;]
 *         [-DparseOnly=&lt;whether_to_only_parse_test_config(default:false)&gt;]
 *         [-DmasterClasspath=&lt;additional_classpath_for_master(default:null)&gt;]
 *         [-DmasterHeapMB=&lt;max_size_of_master_controller_heap_in_megabytes(default:256)&gt;]
 *         [-DprovideBugReportTemplate=&lt;true if failed run should create a bug report template file(default:false)&gt;]
 *         [-DprovidePropertiesForJenkins=&lt;true if run should create jenkins.prop file for Jenkins to use(default:false)&gt;]
 *         [-DprovideXMLReport=&lt;true if run should create an XML report(default:false)&gt;]
 *         batterytest.BatteryTest [options]
 *  Where options are:
 *         -until date/time
 *               Executes tests until a specific date.  Dates are
 *               specified according to {@link #FORMAT}.
 *         -for duration time-unit
 *               The amount of time tests should be executed.
 *               "duration" is an integer and time-unit is one of
 *               "minutes", "hours", or "days"
 *         -interrupt
 *              Interrupts the currently running test if time specified with -for or -until has elapsed.
 *         -continue
 *              Continues a test run that ended prematurely.  It
 *              consults oneliner.txt to determine how many tests have
 *              already been run.  It assumes that battery test is
 *              being run with the same .bt file as previous runs.
 *  </pre></blockquote>
 *
 *  <p>
 *
 *  Example:
 *
 *  <blockquote><pre>
 *     java -classpath $JTESTS:$GEMFIRE/lib/gemfire.jar
 *          -DGEMFIRE=$GEMFIRE
 *          -DJTESTS=$JTESTS
 *          -DJPROBE=/export/pippin1/users/tools/jprobe501
 *          -DtestFileName=$JTESTS/smoketest/smoketest.bt
 *          -DnumTimesToRun=2
 *          batterytest.BatteryTest
 *  </pre></blockquote>
 *
 *  <p>
 *
 *  BatteryTest runs each test in the input file, one after the other.
 *  If <code>numTimesToRun</code> is greater than 1, it repeats the
 *  test suite the specified number of times.
 *
 *  <p>
 *
 *  The fully expanded list of tests being run is written to "batterytest.bt",
 *  and is overwritten every time batterytest is invoked.  Tests using
 *  "onlyOnPlatforms" are omitted on non-matching platforms.
 *  Results, progress, and a summary report are written to
 *  "batterytest.log".  The verbosity of this log is controlled via
 *  <code>logLevel</code> (see {@link com.gemstone.gemfire.LogWriter}.
 *  A one-line description of each test result is written to
 *  "oneliner.txt".  These files are appended to, if they already
 *  exist.  Delete or move them between runs if desired.  A result of
 *  "P" means pass, "F" means fail, and "H" means hung.
 *
 *  <p>
 *
 *  BatteryTest spawns a process to run a test then watches for that
 *  process to disappear. If, when the process dies, the test
 *  directory contains a file named "in_progress.txt" or "hang.txt",
 *  or if the process fails to die within the batterytest timeout
 *  (currently hardwired at 216000 seconds), BatteryTest reports the
 *  test as hung and terminates its run unless
 *  <code>nukeHungTest</code> is set true.  If these conditions are
 *  absent, batterytest reports the test's pass/fail status and moves
 *  on to the next test.  The test is reported as failing if it
 *  produces a file named "failed.txt" or it
 *  produces a file named "errors.txt".  If none of the failure
 *  conditions hold, the test is reported as passing.
 *
 *  <p>
 *  If <code>moveRemoteDirs</code> is true, batterytest executes the script
 *  "movedirs.sh" in the test directory, if it exists and the test was
 *  not left running.
 *
 *  <p>
 *  For tests using Hadoop, BatteryTest kills all leftover Hadoop processes by
 *  default and copies the log and data files to the test result directory. It
 *  does this by executing <code>nukehadoop.sh</code> and <code>movehadoop.sh
 *  </code>, respectively. To move the data files instead of removing them, set
 *  <code>moveHadoopData=true</code>. The data files are placed in the
 *  <code>movedHadoopData</code> subdirectory of the test result directory.
 *  To skip killing the processes for a test
 *  hang, set <code>nukeHungTest=false</code>. This causes BatteryTest to skip
 *  executing the Hadoop scripts and terminate, leaving the Hadoop cluster
 *  intact. To clean up the cluster at a later time, execute the scripts
 *  manually.
 *
 *  <p>
 *
 *  If <code>parseOnly</code> is true, batterytest sets this property in the
 *  hydra master controller, instructing it to parse the test configurations
 *  without running the tests.  This is useful for expanding configurations
 *  that use includes, and comparing them to other runs.
 *
 *  @author Lise Storc
 *  @since 1.0
 */
public class BatteryTest {

  /** bump this whenever the Jenkins platform has a change */
  private static final int PLATFORM_ID = 1;

  public static final String MOVE_HADOOP_DATA = "moveHadoopData";
  public static final String JACOCO_FN = "jacoco.exec";

  /** The format of the date used for the -until option
   *
   * @see SimpleDateFormat */
  public static final String FORMAT = "MM/dd/yyyy hh:mm a";

  /** DateFormat used to parse dates */
  private static DateFormat df = new SimpleDateFormat(FORMAT);

  /** How long to wait for the test to exit after its progress has halted */
  private static final int EXIT_WAIT_SEC = 60;

  private static final String sep = File.separator;

  public static void main( String[] args ) {
    try {
      boolean result = runbattery(args);
      if ( ! result )
        logError( "runbattery() returned false" );
    } 
    catch (VirtualMachineError e) {
      // Don't try to handle this; let thread group catch it.
      throw e;
    }
    catch( Throwable t ) {
      logError( TestHelper.getStackTrace( t ) );
    }
    System.exit(0);
  }

  /**
   * Consults <code>oneliner.txt</code> to determine how many tests
   * have already been run.  This method supports the
   * <code>-continue</code> option.
   *
   * @param resultDirName
   *        Name of directory containing test results (and
   *        oneliner.txt) 
   *
   * @return The number of tests that have already been executed
   *
   * @author David Whitlock
   * @since 2.0.3
   */ 
  private static int countAlreadyRun(String resultDirName) {
    int alreadyRun = 0;

    File resultDir = new File(resultDirName);
    if (resultDir.exists()) {
      File oneliner = new File(resultDir, "oneliner.txt");
      if (oneliner.exists()) {
        try {
          BufferedReader br =
            new BufferedReader(new FileReader(oneliner));
          while (br.ready()) {
            br.readLine();
            alreadyRun++;
          }

        } catch (IOException ex) {
          System.err.println("While reading oneliner.txt:");
          ex.printStackTrace(System.err);
        }
      }
    }

    return alreadyRun;
  }

  public static boolean runbattery(String[] args) {

    OSType osType = HostHelper.getLocalHostOS();

    // get the required settings
    String jtests = System.getProperty( "JTESTS" );
    String gemfire = System.getProperty( "GEMFIRE" );
    String testFileName = System.getProperty( "testFileName" );
    if (jtests == null) {
      usage("Missing JTESTS");
      return false;

    } else if (gemfire == null) {
      usage("Missing GEMFIRE");
      return false;

    } else if (testFileName == null ) {
      usage("Missing testFileName");
      return false;
    }

    // get the optional settings
    String extraJtests = System.getProperty( "EXTRA_JTESTS" );
    String hadoopDist = System.getProperty( "HADOOP_DIST" );
    String gfmon = System.getProperty("GFMON");
    String regressionDir = System.getProperty("REGRESSION_EXTRA_PATH");
    String releaseDir = System.getProperty("RELEASE_DIR");
    String jprobe = System.getProperty( "JPROBE" );
    String codeCoverage = System.getProperty( "codeCoverage", null );
    if (codeCoverage != null && codeCoverage.length() == 0) codeCoverage = null;
    String localConf = System.getProperty("localConf");
    if (localConf == null) {
      if (FileUtil.exists("local.conf")) {
        localConf = FileUtil.absoluteFilenameFor("local.conf");
      }
    } else if (FileUtil.exists(localConf)) {
      localConf = FileUtil.absoluteFilenameFor(localConf);
    } else {
      usage("File not found: " + localConf);
      return false;
    }
    String resultDir = System.getProperty( "resultDir", System.getProperty( "user.dir" ) );
    int masterHeapMB = Integer.getInteger( "masterHeapMB", 512).intValue();
    int numTimesToRun = Integer.getInteger( "numTimesToRun", new Integer(1) ).intValue();
    String logLevel = System.getProperty( "logLevel", "info" );
    boolean provideRegressionSummary = Boolean.valueOf(System.getProperty("provideRegressionSummary", "true")).booleanValue();
    boolean removePassedTest = Boolean.getBoolean( "removePassedTest" );
    boolean grepLogs = Boolean.getBoolean( "grepLogs" );
    Integer grepLogsHeapMB = Integer.getInteger( "grepLogsHeapMB" );
    int grepLogsWaitSec = Integer.getInteger( "grepLogsWaitSec", 3600 );
    boolean nukeHungTest = Boolean.valueOf(System.getProperty("nukeHungTest","true"));
    boolean moveRemoteDirs = Boolean.getBoolean("moveRemoteDirs");
    boolean moveHadoopData = Boolean.getBoolean(MOVE_HADOOP_DATA);
    boolean parseOnly = Boolean.getBoolean("parseOnly");
    boolean provideBugReportTemplate = Boolean.valueOf(System.getProperty("provideBugReportTemplate", "false")).booleanValue();
    boolean providePropertiesForJenkins = Boolean.valueOf(System.getProperty("providePropertiesForJenkins", "false")).booleanValue();
    boolean provideXMLReport = Boolean.valueOf(System.getProperty("provideXMLReport", "false")).booleanValue();

    // Parse the command line arguments
    Date until = new Date(Long.MAX_VALUE);
    int alreadyRun = 0;
    boolean interruptTest = false;

    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("-until")) {
        if (i + 3 >= args.length) {
          usage("Missing date arguments");
          return false;
        } 

        StringBuffer sb = new StringBuffer();
        sb.append(args[++i]);
        sb.append(" ");
        sb.append(args[++i]);
        sb.append(" ");
        sb.append(args[++i]);
        String date = sb.toString();
        try {
          until = df.parse(date);

        } catch (ParseException ex) {
          usage("Malformed date/time: " + date);
          return false;
        }

      } else if (args[i].equals("-for")) {
        if (i + 2 >= args.length) {
          usage("Missing duration and/or time-units");
        }

        i++;
        int duration;
        try {
          duration = Integer.parseInt(args[i]);

        } catch (NumberFormatException ex) {
          usage("Malformed duration: " + args[i]);
          return false;
        }

        String unit = args[++i].toLowerCase();
        Calendar now = Calendar.getInstance();

        if (unit.startsWith("minute")) {
          now.roll(Calendar.MINUTE, duration);

        } else if (unit.startsWith("hour")) {
          now.roll(Calendar.HOUR, duration);
        
        } else if (unit.startsWith("day")) {
          now.roll(Calendar.DAY_OF_MONTH, duration);

        } else {
          usage("Unknown time unit: " + unit);
          return false;
        }

        until = now.getTime();

      } else if (args[i].equals("-continue")) {
        alreadyRun = countAlreadyRun(resultDir);

      } else if (args[i].equals("-interrupt")) {
        interruptTest = true;
        nukeHungTest = true;

      } else {
        usage("Unknown command line option: " + args[i]);
        return false;
      }
    }

    // open the batterytest log file, append if it already exists
    log = Log.createLogWriter( "batterytest", "batterytest", logLevel, true );
    log.info(ProcessMgr.processAndBuildInfoString());
    log.info( "Batterytest PID is " + ProcessMgr.getProcessId() );

    // log the batterytest configuration
    log.info( "\nJTESTS = " + jtests +
              "\nEXTRA_JTESTS = " + extraJtests +
              "\nGEMFIRE = " + gemfire +
              "\nHADOOP_DIST = " + hadoopDist +
              "\nGFMON = " + gfmon +
              "\nREGRESSION_EXTRA_PATH = " + regressionDir +
              "\nRELEASE_DIR = " + releaseDir +
              "\nJPROBE = " + jprobe +
              "\ntestFileName = " + testFileName +
              "\nlocalConf = " + localConf +
              "\nresultDir = " + resultDir +
              "\nmasterHeapMB = " + masterHeapMB +
              "\nnumTimesToRun = " + numTimesToRun +
              "\nlogLevel = " + logLevel +
              "\nprovideRegressionSummary = " + provideRegressionSummary +
              "\nremovePassedTest = " + removePassedTest +
              "\ncodeCoverage = " + codeCoverage +
              "\ngrepLogs = " + grepLogs +
              "\ngrepLogsHeapMB = " + grepLogsHeapMB +
              "\ngrepLogsWaitSec = " + grepLogsWaitSec +
              "\nnukeHungTest = " + nukeHungTest +
              "\nmoveRemoteDirs = " + moveRemoteDirs +
              "\nmoveHadoopData = " + moveHadoopData +
              "\nparseOnly = " + parseOnly +
              "\nprovideBugReportTemplate = " + provideBugReportTemplate +
              "\nprovideXMLReport = " + provideXMLReport);

    // get the list of tests from the batterytest input file
    log.info( "Parsing batterytest input file: " + testFileName + "..." );
    Vector tests = null;
    try {
      tests = ConfigParser.parseFile( testFileName );
      if ( log.fineEnabled() ) {
        for ( int i = 0; i < tests.size(); i++ ) {
          log.fine( "Test " + i + " = " + tests.get(i) );
        }
      }
    } catch( FileNotFoundException e ) {
      log.severe( "Batterytest input file not found: " + testFileName, e );
      return false;
    }

    // find the longest test name for later printing
    int maxsize = 0;
    for ( int i = 0; i < tests.size(); i++ ) {
      BatteryTestConfig test = (BatteryTestConfig) tests.elementAt(i);
      maxsize = Math.max( maxsize, test.getName().length() );
    }

    // log info about test run
    log.info( "Number of tests in " + testFileName + " is " + tests.size() + "..." );
    log.info( "Running test file " + numTimesToRun + " times..." );
    int totalTests = tests.size() * numTimesToRun;
    log.info( "Running a total of " + totalTests + " (" + numTimesToRun + "*" + tests.size() + ") tests" );
    log.info("Will execute tests until " + df.format(until));
    log.info("Have previously executed " + alreadyRun + " tests");

    // touch oneline.txt so we can start tail'ing it immediately
    try {
      File dir = new File(resultDir);
      dir.mkdirs();
      //dir.setWritable(true);
      (new File(dir, "oneliner.txt")).createNewFile();

    } catch (IOException ex) {
      log.severe("Couldn't touch oneliner.txt", ex);
    }

    // write the properties for jenkins
    if (providePropertiesForJenkins) {
      providePropertiesForJenkins();
    }

    // run the tests
    int passed = 0;
    int failed = 0;
    int hung   = 0;
    int testnum = 0;
  OUTER:
    for ( int i = 0; i < numTimesToRun; i++ ) {
      for ( int j = 0; j < tests.size(); j++ ) {
        ++testnum;
        BatteryTestConfig btc = (BatteryTestConfig) tests.elementAt( j );
        String relativetest = btc.getName();

        if (testnum <= alreadyRun) {
          log.info("Skipping test " + testnum + " of " + totalTests + ": "
                  + btc + " because it has already been run");
          continue;

        } else if ((new Date()).after(until)) {
          log.info("Stopping battery test because it is after " +
                   df.format(until));
          break OUTER;
        }

        String test = null;
	if ( FileUtil.exists( relativetest ) )
	  test = FileUtil.absoluteFilenameFor( relativetest );
        else
          if ( extraJtests != null ) {
            test = extraJtests + sep + relativetest;
            if (! FileUtil.exists( test )) {
              String oldTest = test;
              test = jtests + sep + relativetest;
	      log.info( "test file not found in " + oldTest + " trying in " + test);
            }
          } else {
            test = jtests + sep + relativetest;
          }
        // bring in default properties not overridden in the .bt file, if any
        int index = test.indexOf( ".conf" );
        if ( index != -1 ) { // hydra test
	  String propFileName = test.substring( 0, index ) + ".prop";
          btc.fillInWithDefaultsFrom( propFileName );
        }

	log.info( "Running test " + testnum + " of " + totalTests + ": " + btc );

        // get the host
	String host = HostHelper.getLocalHost();

	// create the command
	String cmd = null;
	String type = null;
	if ( test.indexOf( ".conf" ) != -1 ) {
	  type = "hydra";
	  cmd = System.getProperty( "java.home" ) + sep + "bin" + sep + "java";
	  String vmType = VmPrms.defaultType();
	  if ( vmType != null ) {
	    cmd += " -" + vmType;
	  }
	  cmd = cmd + " -classpath "
                    + System.getProperty( "masterClassPath", "" )
                    + File.pathSeparator
                    + System.getProperty( "java.class.path" );
	  cmd = cmd + " -Xmx" + masterHeapMB + "m";
          cmd = cmd + " -DJTESTS=" + jtests;
          if ( hadoopDist != null ) {
            cmd = cmd + " -DHADOOP_DIST=" + hadoopDist;
          }
          if ( extraJtests != null ) {
            cmd = cmd + " -DEXTRA_JTESTS=" + extraJtests;
          }
          if (gfmon != null) {
	    cmd = cmd + " -DGFMON=" + gfmon;
          }
          if (regressionDir != null) {
	    cmd = cmd + " -DREGRESSION_EXTRA_PATH=" + regressionDir;
          }
          if (releaseDir != null) {
	    cmd = cmd + " -DRELEASE_DIR=" + releaseDir;
          }
          if ( jprobe != null )
	    cmd = cmd +
	          " -DJPROBE=" + jprobe;
	  cmd = cmd + " -Dsun.rmi.transport.tcp.handshakeTimeout=3600000";

          if (System.getProperty("java.vm.vendor").startsWith("Sun") 
          && !System.getProperty("java.version").startsWith("1.4")) {
            cmd = cmd + " -XX:+HeapDumpOnOutOfMemoryError";
          }
          cmd = cmd +
	        " -Dgemfire.home=" + gemfire +
	        " -DconfigFileName=" + test +
	        " -DparseOnly=" + parseOnly +
	        " -D" + MOVE_HADOOP_DATA + "=" + moveHadoopData +
		btc.getPropertyString() + "hydra.MasterController";
        } else if ( test.indexOf( ".pl" ) != -1 ) {
	  type = "script";
	  cmd = "perl " + test;
	} else { // just try to run it
	  type = "unknown";
	  cmd = test;
	}

        // create the working directory
	String conf = FileUtil.filenameFor( test );
	String base = resultDir + sep;
	if ( conf.lastIndexOf( "." ) == -1 )
	  base = base + conf;
        else
	  base = base + conf.substring( 0, conf.lastIndexOf( "." ) );
	Date d = new Date();
	String ds = d.toString();
	String workdir = base + "-" + month( ds.substring(4,7) ) + ds.substring(8,10) +
	                        "-" + ds.substring(11,13) + ds.substring(14,16) + ds.substring(17,19);
	FileUtil.mkdir( workdir );
        if (!parseOnly) logStatus(host, workdir);

        // copy the local.conf file to the working directory
        if (type.equals("hydra") && (localConf != null || codeCoverage != null)) {
          String testLocalConf = workdir + sep + "local.conf";
          if (localConf != null) {
            FileUtil.copyFile(localConf, testLocalConf);
          }
          if (codeCoverage != null) {
            String cc = "\nhydra.VmPrms-extraVMArgs += \"" + codeCoverage + "\";\n";
            FileUtil.appendToFile(testLocalConf, cc);
          }
        }

        // write the system.properties to the working directory
	String testname = (new File( base )).getName();
        btc.writePropertiesToFile( workdir + sep + testname + ".prop" );

        // set the logfile
	String logfile = null;
	if ( type.equals("hydra") )
	  logfile = "bgexecmaster.log";
        else
	  logfile = "bgexectest.log";

        // start the test
        long starttime = System.currentTimeMillis();
	int pid = ProcessMgr.bgexec( host, cmd, workdir, logfile );
	log.info( "PID=" + pid );
	log.info( "DIR=" + workdir );
	log.info( "Waiting for test to complete..." );
        if (!parseOnly) {
	  MasterController.sleepForMs( 15000 ); // give it time to be noticed
        }

        // build monitored filenames
        String spawnfile      = workdir + sep + "in_master.txt";
        String inprogressfile = workdir + sep + "in_progress.txt";
        String hangfile       = workdir + sep + "hang.txt";
        String errorfile      = workdir + sep + "errors.txt";

        // wait for test to finish
	boolean progressFileExists = true;
        boolean masterProcessExists = true;
        boolean timeoutOnProcessExists = false;
        boolean hangFileExists = false;
        try {
          do {
            if (interruptTest && (new Date()).after(until)) {
              // fake a hung test
              log.info("Interrupting battery test because it is after " +
                       df.format(until));
              FileUtil.appendToFile(hangfile,
                       "Batterytest interrupted test at deadline configured with -for or -until");
              break;
            }
            MasterController.sleepForMs( 2500 );
            progressFileExists = FileUtil.exists( inprogressfile );
            masterProcessExists = ProcessMgr.processExists( host, pid );
          } while ( progressFileExists && masterProcessExists );
          // now either the progress file or master process is gone

          // make sure master does not hang when test does not hang
          hangFileExists = FileUtil.exists( hangfile );
          if ( ! hangFileExists && masterProcessExists ) {
            log.info( "Master process is no longer in progress, waiting "
                    + EXIT_WAIT_SEC + " seconds for it to exit" );
            masterProcessExists =
                  ! ProcessMgr.waitForDeath( host, pid, EXIT_WAIT_SEC );
          }
          // now if the master is still with us, treat as a hang (see below)
        } catch (HydraTimeoutException e) {
          timeoutOnProcessExists = true;
        }

        // note how long the test took to run
        long elapsedSec = (System.currentTimeMillis() - starttime)/1000;

        // determine the pass/fail and termination status
	boolean success = true;
        boolean terminateEarly = false;

        // make sure the test process really got underway
        if ( ! FileUtil.exists( spawnfile ) ) {
	  terminateEarly = false;
	  success = false;
          String msg = "Master process failed to get underway, see bgexecmaster_<pid>.log";
          log.severe( msg );
        } else { // reduce the noise
          FileUtil.deleteFile( spawnfile );
        }
	// and hasn't reported a possible hang
        if ( hangFileExists ) {
	  terminateEarly = true;
	  success = false;
          String msg = "Test reported a possible hang, see hang.txt";
          log.severe( msg );
        }
        // and the spawned test process really completed
        if ( ! hangFileExists && masterProcessExists ) {
	  terminateEarly = true;
	  success = false;
          String msg = "Master process failed to exit within " + EXIT_WAIT_SEC
                     + " seconds of completion, treating as a hang";
          log.severe( msg );
        }
	// and doesn't claim to still be in progress
        if (FileUtil.exists(inprogressfile)) {
	  terminateEarly = true;
	  success = false;
          String msg = "Master process died but claims to be in progress, see in_progress.txt";
          log.severe( msg );
        }
        // and hasn't documented any errors
        if ( FileUtil.exists( errorfile ) ) {
	  success = false;
          String msg = "Test failed with errors, see errors.txt";
          log.severe( msg );
        }
        // and doesn't report a failure
        if ( FileUtil.exists(workdir + sep + "failed.txt") ) {
          success = false;
          String msg = "Test reported failure";
          log.severe( msg );
        }
        // and batterytest wasn't unable to determine whether master exists
        if (timeoutOnProcessExists) {
	  terminateEarly = true;
          success = false;
          String msg = "Timed out trying to determine whether master process "
                     + "exists: " + pid + ", possibly due to machine overload, "
                     + "treating as hang";
          log.severe( msg );
        }

	// report result to oneliner.txt
	StringBuffer oneliner = new StringBuffer( 100 );
	StringBuffer dir = new StringBuffer( relativetest );
	for ( int n = 0; n < maxsize - relativetest.length(); n++ )
	  dir.append( " " );
	oneliner.append( dir.toString() ).append( "    " );
        if ( success ) {
	  log.info( "RESULT: Test PASSED" );
	  oneliner.append( "P    " );
	  ++passed;
	} else if ( terminateEarly ) {
	  log.info( "RESULT: Test HUNG" );
	  oneliner.append( "H    " );
	  ++hung;
	} else {
	  log.info( "RESULT: Test FAILED" );
	  oneliner.append( "F    " );
	  ++failed;
	}

	// oneliner.append( elapsedSec ).append( "    " ).append( workdir );
	NumberFormat nf = NumberFormat.getIntegerInstance();
	nf.setMinimumIntegerDigits(2);
	String hrs = "00";
	if (elapsedSec >= 3600)
		hrs = nf.format(elapsedSec / 3600).toString();
	String mins = "00";
	if (elapsedSec >= 60)
		mins = nf.format((elapsedSec % 3600) / 60).toString();
	String secs = nf.format(elapsedSec % 60).toString();
	oneliner.append(hrs + ":" + mins + ":" + secs + "    " + workdir);
	FileOutputStream fos = null;
	try {
	  fos = new FileOutputStream( resultDir + sep + "oneliner.txt", true );
	} catch( FileNotFoundException e ) {
	  log.severe( "Unable to open oneliner.txt", e );
	}
	PrintWriter pw = new PrintWriter( fos );
	pw.println( oneliner.toString() );
	pw.close();

	// terminate early if conditions warrant
        if ( terminateEarly ) {
          if ( nukeHungTest ) {
            if (interruptTest) {
              log.severe( "Interrupting due to deadline configured with -for or -until, nuking test processes for currently running test" );
            } else {
              log.severe( "Proceeding past hung test, nuking test processes" );
            }
	    try {
	      if (osType == OSType.windows) {
	        log.severe(ProcessMgr.fgexec(workdir + sep + "nukerun.bat", 1800));
	      } else {         
	        log.severe(ProcessMgr.fgexec("sh " + workdir + sep + "nukerun.sh", 600));
              }
	    } catch( HydraRuntimeException e ) {
	      log.severe( "Automatic nuke failed -- clean up manually", e );
	    }
	    try {
	      if (osType == OSType.windows) {
	        log.severe(ProcessMgr.fgexec(workdir + sep + "nukehadoop.bat", 1800));
	      } else {         
	        log.severe(ProcessMgr.fgexec("sh " + workdir + sep + "nukehadoop.sh", 600));
              }
	    } catch( HydraRuntimeException e ) {
	      log.severe( "Automatic nuke failed -- clean up manually", e );
	    }

	  } else {
            reportStatus( totalTests, passed, failed, hung );
            log.severe( "Terminating early, see last test result" );
            if (!parseOnly) logStatus(host, workdir);
            return ( failed + hung == 0 ) ? true : false;
	  }
        }

        // at this point, we've already nuked a hung test or exited due to a hang
        // so it's OK to move the jacoco file in all cases
        if (!parseOnly) {
          String jacocofn = workdir + sep + JACOCO_FN;
          if (FileUtil.exists(jacocofn)) {
            if (osType == OSType.unix) {
              try {
                log.severe("Moving jacoco output to batterytest directory");
	        String jacocofn2 = resultDir + sep + (new File(workdir)).getName() + "_" + JACOCO_FN;
                log.severe(ProcessMgr.fgexec("/bin/mv " + jacocofn + " " + jacocofn2, 120));
              } catch (HydraRuntimeException e) {
                log.severe("Automatic move for jacoco failed -- move file manually", e);
              }
            } else {
              log.severe("Automatic move for jacoco not supported on this platform -- move file manually");
            }
          }
        }

        { //network cleanup (always do this)
          String script = null;
          if (sep.equals("/")) {
            script = workdir + sep + "netclean.sh";
          } else {
            script = workdir + sep + "netclean.bat";
          }
          if (FileUtil.exists(script)) {
            log.severe("Cleaning up network connections");
            try {
              if (osType == OSType.windows) {
                log.severe(ProcessMgr.fgexec(script, 300));
              } else {
                log.severe(ProcessMgr.fgexec("sh " + script, 300));
              }
	    } catch (HydraRuntimeException e) {
	      log.severe("Automatic network connection cleanup failed -- clean up manually", e);
	    }
	  }
        }

        if (!parseOnly && moveRemoteDirs && (!terminateEarly || nukeHungTest)) {

          log.severe("Moving remote directories to test result directory");
	  try {
            if (osType == OSType.windows) {
              log.severe(ProcessMgr.fgexec(workdir + sep + "movedirs.bat", 600));
            } else {
              log.severe(ProcessMgr.fgexec("sh " + workdir + sep + "movedirs.sh", 600));
            }
	  } catch (HydraRuntimeException e) {
	    log.severe("Automatic move failed -- clean up manually", e);
	  }
        }

        { // always nuke and clean hadoop no matter what
          try {
            if (osType == OSType.windows) {
              log.severe(ProcessMgr.fgexec(workdir + sep + "nukehadoop.bat", 1800));
            } else {         
              log.severe(ProcessMgr.fgexec("sh " + workdir + sep + "nukehadoop.sh", 600));
            }
          } catch (HydraRuntimeException e) {
            log.severe("Automatic nuke failed -- clean up manually", e);
          }
          if (moveHadoopData) {
            log.severe("Moving Hadoop log and data directories to the test result directory");
          } else {
            log.severe("Moving Hadoop log directories to the test result directory and removing Hadoop data directories");
          }
          try {
            if (osType == OSType.windows) {
              log.severe(ProcessMgr.fgexec(workdir + sep + "movehadoop.bat", 600));
            } else {
              log.severe(ProcessMgr.fgexec("sh " + workdir + sep + "movehadoop.sh", 600));
            }
          } catch (HydraRuntimeException e) {
            log.severe("Automatic clean failed -- clean up manually", e);
          }
        }

        log.severe("Adding read permission to all files in test result directory");
        try {
          ProcessMgr.setReadPermission(workdir);
        } catch (Exception e) {
          // don't let any exceptions keep battery test from moving on
          log.severe(TestHelper.getStackTrace(e));
          log.severe("Failed to add read permission to all files in test result directory. Please change permissions manually.");
        }

        if (!parseOnly) logStatus(host, workdir);

        if (provideRegressionSummary) {
          // update the regression summary file after each run so results
          // can be deleted (see removePassedTest below)
          Log.getLogWriter().info("Generating summary file...");
          try {
             RegressionSummary regrSumm =
               new RegressionSummary(RegressionSummary.ContinueFromExisting,
                                     resultDir, resultDir);
             regrSumm.doSummary();
          } catch (Exception e) {
             // don't let any exceptions keep battery test from moving on
             log.info(TestHelper.getStackTrace(e));
          }
        }

        if (provideXMLReport) {
          log.info("Generating XML report for " + workdir + "...");
          try {
            XMLReport.createReport(btc.getName(), btc.toTestProps(),
                                   localConf, workdir, testFileName);
          } catch (Exception e) {
            // don't let any exceptions keep battery test from moving on
            log.warning("XML report generation failed for " + workdir
                       + "\n" + TestHelper.getStackTrace(e));
          }
        }

        if (provideBugReportTemplate) {
          if (!success || terminateEarly) { // test failed
            try {
              log.info("Creating bug report template file for " + workdir + "...");
              long startTime = System.currentTimeMillis();
              BugReportTemplate.createTemplateFile(workdir, false);
              long endTime = System.currentTimeMillis();
              log.info("Bug report template generated in " + 
                  (endTime - startTime) + " ms");
            } catch (Exception e) {
              // don't let any exceptions keep battery test from moving on
              log.info(TestHelper.getStackTrace(e));
            }
          }
        }

        // only grep on successful runs; we don't need to see more suspect strings
        // on failed runs because we analyze all failures anyway
        if (grepLogs && success) {
          try {
            javaGrepLogs(workdir, grepLogsHeapMB, grepLogsWaitSec);
          } catch (HydraRuntimeException hre) {
            log.severe(TestHelper.getStackTrace(hre));
          } catch (HydraTimeoutException hte) {
            log.severe(TestHelper.getStackTrace(hte));
          }
        } 

        // delete test results if indicated
        if (removePassedTest && success) {
          log.info("Test passed, deleting " + workdir);
          if(! FileUtil.rmdir(workdir, false)) {
            log.warning("Problems deleting: " + workdir);
          }
        }
        if (terminateEarly && interruptTest) {
          log.severe( "Terminating early due to deadline configured with -for or -until, see last test result" );
          break OUTER;
        }
      }
    }
    reportStatus( totalTests, passed, failed, hung );
    return ( failed + hung == 0 ) ? true : false;
  }
  private static void reportStatus( int total, int passed, int failed, int hung ) {
    StringBuffer msg = new StringBuffer( 100 );
    msg.append( "\n" ).append( "==== STATUS REPORT ====" );
    msg.append( "  Total: " ).append( total );
    msg.append( "  Tried: " ).append( passed + failed + hung );
    msg.append( "  Passed: " ).append( passed );
    msg.append( "  Failed: " ).append( failed );
    msg.append( "  Hung: " ).append( hung );
    msg.append( "  Remaining: " ).append( total - (passed + failed + hung) );
    msg.append( "  ====" );
    log.info( msg.toString() );
  }

  private static void providePropertiesForJenkins() {
    StringBuffer sb = new StringBuffer();

    sb.append("platform.id").append('=')
      .append(PLATFORM_ID).append('\n');

    sb.append("os.version").append('=')
      .append(System.getProperty("os.name")).append(" ")
      .append(System.getProperty("os.version")).append('\n');

    sb.append("java.version").append('=')
      .append(System.getProperty("java.version")).append('\n');

    Properties p = ProductVersionHelper.getInfo();
    if (p != null) {
      sb.append("product.version").append('=')
        .append(p.getProperty(ProductVersionHelper.PRODUCT_VERSION)).append('\n');

      sb.append("source.repository").append('=')
        .append(p.getProperty(ProductVersionHelper.SOURCE_REPOSITORY)).append('\n');

      sb.append("source.revision").append('=')
        .append(p.getProperty(ProductVersionHelper.SOURCE_REVISION)).append('\n');
    }
    try {
      FileUtil.writeToFile("jenkins.prop", sb.toString());
    } catch (HydraRuntimeException e) {
      log.severe(TestHelper.getStackTrace(e));
    }
  }

  private static void usage(String s) {
    PrintStream out = System.out;
    out.println("\n** " + s + "\n");
    out.println( "Usage: java ");
    out.println("  -DGEMFIRE=<path_to_gemfire_product_tree> ");
    out.println("  -DJTESTS=<path_to_test_classes> ");
    out.println("  -DtestFileName=<batterytest_input_file> ");
    out.println("  [-DHADOOP_DIST=<path_to_Hadoop_distribution>] ");
    out.println("  [-DEXTRA_JTESTS=<path_to_extra_test_classes>] ");
    out.println("  [-DGFMON=<path_to_product-testcopy_in_gfmon_product_tree>]");
    out.println("  [-DREGRESSION_EXTRA_PATH=<extra_path_elements_in_autogenerated_regression_director>] ");
    out.println("  [-DRELEASE_DIR=&lt;path_to_gemfire_release_dir&gt;]");
    out.println("  [-DJPROBE=<path_to_jprobe_product_tree>] ");
    out.println("  [-DlocalConf=<local_conf_filename] ");
    out.println("  [-DmasterHeapMB=<max_size_of_master_controller_heap_in_megabytes(default:256)>]");
    out.println("  [-DnumTimesToRun=<number_of_times_to_run_tests_in_input_file(default:1)>] ");
    out.println("  [-DlogLevel=<batterytest_log_level(default:info)>]");
    out.println("  [-DprovideRegressionSummary=<whether_to_provide_a_regression_summary_report(default:true)>]");
    out.println("  [-DremovePassedTest=<whether_to_remove_test_result_dirs_for_passed_test(default:false)>]");
    out.println("  [-DcodeCoverage=<javaagent_command_for_code_coverage(default:null)>]");
    out.println("  [-DgrepLogs=<search_logs_for_suspect_strings(default:false)>]");
    out.println("  [-DgrepLogsHeapMB=<max_size_of_greplogs_heap_in_megabytes(default:unspecified)>]");
    out.println("  [-DgrepLogsHeapWaitSec=<time_in_seconds_to_wait_for_grepLogs_to_complete(default:3600)]");
    out.println("  [-DnukeHungTest=<whether_to_nuke_hung_test_and_keep_going(default:true)>] ");
    out.println("  [-DmoveRemoteDirs=<whether_to_move_remote_dirs_to_master_dir(default:false)>] ");
    out.println("  [-DmoveHadoopData=<whether_to_move_hadoop_data_dirs_to_master_dir(default:false)>] ");
    out.println("  [-DparseOnly=<whether_to_only_parse_test_config(default:false)>] ");
    out.println("  [-DprovideBugReportTemplate=<whether_to_create_bug_report_template(default:false)>] ");
    out.println("  [-DprovidePropertiesForJenkins=<whether_to_create_properties_file_for_Jenkins(default:false)>] ");
    out.println("  [-DprovideXMLReport=<whether_to_create_XML_report(default:false)>] ");
    out.println("batterytest.BatteryTest [options]");
    out.println("");
    out.println("Where options are:");
    out.println("  -until date/time");
    out.println("       Executes tests until a specific date.  Dates are");
    out.println("       specified according to the \"" + FORMAT + "\"");
    out.println("       SimpleDateFormat.  Example: " + df.format(new Date()));
    out.println("  -for duration time-unit");
    out.println("       The amount of time tests should be executed.");
    out.println("       \"duration\" is an integer and time-unit is one of");
    out.println("       \"minutes\", \"hours\", or \"days\"");
    out.println("  -interrupt");
    out.println("       Interrupts the currently running test if time specified");
    out.println("       with -for or -until has elapsed.");
    out.println("  -continue");
    out.println("       Continues a test run that ended prematurely.  It");
    out.println("       consults oneliner.txt to determine how many tests have");
    out.println("       already been run.  It assumes that battery test is");
    out.println("       being run with the same .bt file as previous runs.");
    out.println("");
  }

  private static String month( String m ) {
    if ( m.equalsIgnoreCase( "Jan" ) ) return "01";
    if ( m.equalsIgnoreCase( "Feb" ) ) return "02";
    if ( m.equalsIgnoreCase( "Mar" ) ) return "03";
    if ( m.equalsIgnoreCase( "Apr" ) ) return "04";
    if ( m.equalsIgnoreCase( "May" ) ) return "05";
    if ( m.equalsIgnoreCase( "Jun" ) ) return "06";
    if ( m.equalsIgnoreCase( "Jul" ) ) return "07";
    if ( m.equalsIgnoreCase( "Aug" ) ) return "08";
    if ( m.equalsIgnoreCase( "Sep" ) ) return "09";
    if ( m.equalsIgnoreCase( "Oct" ) ) return "10";
    if ( m.equalsIgnoreCase( "Nov" ) ) return "11";
    if ( m.equalsIgnoreCase( "Dec" ) ) return "12";
    return null;
  }

  /**
   * Record the status of the local host (such as "ps" and "free").
   */
  private static void logStatus(String host, String workdir) {
    String status = "";
    try {
      status += "\n" + ProcessMgr.getProcessStatus(60) + "\n";
    } catch (HydraRuntimeException e) {
      logError("Unable to log process status: " + TestHelper.getStackTrace(e));
    } catch (HydraTimeoutException e) {
      logError("Unable to log process status: " + TestHelper.getStackTrace(e));
    }
    try {
      status += "\n" + ProcessMgr.getMemoryStatus(60) + "\n";
    } catch (HydraRuntimeException e) {
      logError("Unable to log memory status: " + TestHelper.getStackTrace(e));
    } catch (HydraTimeoutException e) {
      logError("Unable to log memory status: " + TestHelper.getStackTrace(e));
    }
    String fn = workdir + File.separator + "hoststats_" + host + ".txt";
    FileUtil.appendToFile(fn, status);
  }

  private static void logError( String msg ) {
    if ( log == null )
      System.err.println( msg );
    else
      log.severe( msg );
  }
  private static LogWriter log;

  /**
   * Invoke the GrepLogs utility.
   */
  public static void javaGrepLogs(String workdir, Integer maxHeap, int maxWaitSec) {
    String sep = File.separator;

    String userDir = System.getProperty("user.dir");
    String suspectDir = userDir + sep + "suspectStrings";
    FileUtil.mkdir(suspectDir);

    String bgexecDir = suspectDir + sep + "bgexeclogs";
    FileUtil.mkdir(bgexecDir);

    File tmp = new File(workdir);
    String grepOutput = suspectDir + sep + tmp.getName() + ".txt";

    String xmx = (maxHeap == null) ? "" : "-Xmx" + maxHeap + "m ";

    log.info("Grepping logs for suspect strings: " + workdir);
    String cmd = System.getProperty("java.home") + sep + "bin" + sep + "java "
               + "-classpath " + System.getProperty("java.class.path") + " "
               + "-Duser.dir=" + userDir + " " + xmx
               + "batterytest.greplogs.GrepLogs -hydra -type battery -out "
               + grepOutput + " " + workdir;
    int pid = ProcessMgr.bgexec(cmd, bgexecDir, null);

    log.info("Waiting " + maxWaitSec + " seconds for greplogs PID=" + pid
            + " to complete");
    String host = HostHelper.getLocalHost();
    if (!ProcessMgr.waitForDeath(host, pid, maxWaitSec)) {
      String err = "Waited more than " + maxWaitSec
                 + " seconds for greplogs PID=" + pid + " to complete";
      boolean killed = ProcessMgr.killProcessWait(host, pid, 30);
      if (killed) {
        throw new HydraTimeoutException(err + ", killed process");
      } else {
        throw new HydraTimeoutException(err + ", failed to kill process");
      }
    }
    log.info("greplogs PID=" + pid + " has completed");
  }
}
