/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.tools.ij.utilMain

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.impl.tools.ij;
                

import com.gemstone.gemfire.internal.GemFireVersion;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.GemFireXDVersion;
import com.pivotal.gemfirexd.internal.iapi.services.info.ProductGenusNames;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.*;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.StopWatch;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.tools.JDBCDisplayUtil;
import jline.console.ConsoleReader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Hashtable;
import java.util.Properties;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.StringReader;
import java.sql.DriverManager;
import java.sql.Driver;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.regex.Pattern;

/**
	This class is utilities specific to the two ij Main's.
	This factoring enables sharing the functionality for
	single and dual connection ij runs.

 */
public class utilMain implements java.security.PrivilegedAction {

	private StatementFinder[] commandGrabber;
	UCode_CharStream charStream;
	ijTokenManager ijTokMgr;
	ij ijParser;
	ConnectionEnv[] connEnv;
	private int currCE;
	private final int		numConnections;
	private boolean fileInput;
	private boolean initialFileInput;
	private boolean mtUse;
	private boolean firstRun = true;
	private LocalizedOutput out = null;
	private Properties connAttributeDefaults;
	private Hashtable ignoreErrors;
	private static final Pattern pattern = Pattern.compile("gemfirexd|gfxd",
	    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	/**
	 * True if to display the error code when
	 * displaying a SQLException.
	 */
	private final boolean showErrorCode;
    
    /**
     * Value of the system property ij.execptionTrace
     */
    private final String ijExceptionTrace;

	/*
		In the goodness of time, this could be an ij property
	 */
	public static final int BUFFEREDFILESIZE = 2048;

	/*
	 * command can be redirected, so we stack up command
	 * grabbers as needed.
	 */
	Stack oldGrabbers = new Stack();

	LocalizedResource langUtil = LocalizedResource.getInstance();
	// GemStone changes BEGIN
	private final String basePath;

	private final Map<String, String> params;

	private final int numTimesToRun;

	static String basePrompt = "gfxd";

	public static void setBasePrompt(String prompt) {
		if (prompt != null && prompt.trim().length() >= 3 && prompt.trim().length() <= 10) {
			basePrompt = prompt.trim();
		}
	}
	// GemStone changes END
	/**
	 * Set up the test to run with 'numConnections' connections/users.
	 *
	 * @param numConnections	The number of connections/users to test.
	 */
	utilMain(int numConnections, LocalizedOutput out)
		throws ijFatalException
	{
		this(numConnections, out, (Hashtable)null, null, null, 1);
	}

	/**
	 * Set up the test to run with 'numConnections' connections/users.
	 *
	 * @param numConnections	The number of connections/users to test.
	 * @param ignoreErrors		A list of errors to ignore.  If null,
	 *							all errors are printed out and nothing
	 *							is fatal.  If non-null, if an error is
	 *							hit and it is in this list, it is silently	
	 *							ignore.  Otherwise, an ijFatalException is
	 *							thrown.  ignoreErrors is used for stress
	 *							tests.
	 * @param basePath base path set with -path command.
	 * @param numTimesToRun number of times a command is to be repeated.
	 */
	public utilMain(int numConnections, LocalizedOutput out, Hashtable ignoreErrors, String basePath,
			Map<String, String> params, int numTimesToRun)
		throws ijFatalException
	{
		/* init the parser; give it no input to start with.
		 * (1 parser for entire test.)
		 */
		charStream = new UCode_CharStream(
						new StringReader(" "), 1, 1);
		ijTokMgr = new ijTokenManager(charStream);
		ijParser = new ij(ijTokMgr, this);
		this.out = out;
		this.ignoreErrors = ignoreErrors;
		this.basePath = basePath;
		this.params = params;
		this.numTimesToRun = numTimesToRun;
		
		showErrorCode = 
			Boolean.valueOf(
					util.getSystemProperty("gfxd.showErrorCode") // GemStone change ij ==> gfxd
					).booleanValue();
        
        ijExceptionTrace = util.getSystemProperty("gfxd.exceptionTrace"); // GemStone change ij ==> gfxd

		this.numConnections = numConnections;
		/* 1 StatementFinder and ConnectionEnv per connection/user. */
		commandGrabber = new StatementFinder[numConnections];
		connEnv = new ConnectionEnv[numConnections];

		for (int ictr = 0; ictr < numConnections; ictr++)
		{
		    commandGrabber[ictr] = new StatementFinder(langUtil.getNewInput(System.in), out, basePath, params);
			connEnv[ictr] = new ConnectionEnv(ictr, (numConnections > 1), (numConnections == 1));
		}

		/* Start with connection/user 0 */
		currCE = 0;
		fileInput = false;
		initialFileInput = false;
		firstRun = true;
	}
	
	/**
	 * Initialize the connections from the environment.
	 *
	 */
	public void initFromEnvironment()
	{
		ijParser.initFromEnvironment();
		
		for (int ictr = 0; ictr < numConnections; ictr++)
		{
			try {
				connEnv[ictr].init(out);
			} catch (SQLException s) {
				JDBCDisplayUtil.ShowException(out, s); // will continue past connect failure
			} catch (ClassNotFoundException c) {
				JDBCDisplayUtil.ShowException(out, c); // will continue past driver failure
			} catch (InstantiationException i) {
				JDBCDisplayUtil.ShowException(out, i); // will continue past driver failure
			} catch (IllegalAccessException ia) {
				JDBCDisplayUtil.ShowException(out, ia); // will continue past driver failure
			}
		}
	}


	/**
	 * run ij over the specified input, sending output to the
	 * specified output. Any prior input and output will be lost.
	 *
	 * @param in source for input to ij
	 * @param out sink for output from ij
	 * @param connAttributeDefaults  connection attributes from -ca ij arg
	 */
	public void go(LocalizedInput[] in, LocalizedOutput out,
				   Properties connAttributeDefaults) throws ijFatalException
	{
		this.out = out;
		this.connAttributeDefaults = connAttributeDefaults;
		
		ijParser.setConnection(connEnv[currCE], (numConnections > 1));
		fileInput = initialFileInput = (!in[currCE].isStandardInput());

		for (int ictr = 0; ictr < commandGrabber.length; ictr++) {
			commandGrabber[ictr].ReInit(in[ictr]);
		}

		if (firstRun) {

		  // GemStone changes BEGIN
		  /*
			// figure out which version this is
			InputStream versionStream = (InputStream) java.security.AccessController.doPrivileged(this);

			// figure out which version this is
			ProductVersionHolder ijVersion = 
				ProductVersionHolder.getProductVersionHolderFromMyEnv(versionStream);

			String version;
			if (ijVersion != null)
			{
				version = "" + ijVersion.getMajorVersion() + "." +
					ijVersion.getMinorVersion();
			}
			else
			{
				version = "?";
			}
			*/
			out.println(convertGfxdMessageToSnappy(
					langUtil.getTextMessage("IJ_IjVers30C199", GemFireVersion.getProductVersion() + " " +
							GemFireVersion.getProductReleaseStage())));
			// GemStone changes END
			for (int i = connEnv.length - 1; i >= 0; i--) { // print out any initial warnings...
				Connection c = connEnv[i].getConnection();
				if (c != null) {
					JDBCDisplayUtil.ShowWarnings(out, c);
				}
			}
			firstRun = false;

      		//check if the property is set to not show select count and set the static variable
      		//accordingly. 
    		boolean showNoCountForSelect = Boolean.valueOf(util.getSystemProperty("gfxd.showNoCountForSelect")).booleanValue(); // GemStone change ij ==> gfxd
      		JDBCDisplayUtil.showSelectCount = !showNoCountForSelect;
			// GemStone changes BEGIN
			boolean showNoRowsForSelect = Boolean.valueOf(util.getSystemProperty("gfxd.showNoRowsForSelect")).booleanValue();
			JDBCDisplayUtil.showSelectRows = !showNoRowsForSelect;
			// GemStone changes END

			//check if the property is set to not show initial connections and accordingly set the
      		//static variable.
    		boolean showNoConnectionsAtStart = Boolean.valueOf(util.getSystemProperty("gfxd.showNoConnectionsAtStart")).booleanValue(); // GemStone change ij ==> gfxd

    		if (!(showNoConnectionsAtStart)) {
         		try {
           			ijResult result = ijParser.showConnectionsMethod(true);
 					displayResult(out,result,connEnv[currCE].getConnection(),
					    -1 /* GemStoneAddition */, true);
         		} catch (SQLException ex) {
           			handleSQLException(out,ex);
         		}
      		}
    	}
		this.out = out;
		runScriptGuts();
		cleanupGo(in);
	}

	/**
	 * Support to run a script. Performs minimal setup
	 * to set the passed in connection into the existing
	 * ij setup, ConnectionEnv.
	 * @param conn
	 * @param in
	 * @param checkShowSelectCount TODO
	 */
	public int goScript(Connection conn,
			LocalizedInput in, boolean checkShowSelectCount /*GemStone changes*/)
	{
	        // GemStone changes BEGIN
		/*JDBCDisplayUtil.showSelectCount = false;*/
	         if(checkShowSelectCount) {
	           boolean showNoCountForSelect = Boolean.valueOf(util.getSystemProperty("gfxd.showNoCountForSelect")).booleanValue(); // GemStone change ij ==> gfxd
	           JDBCDisplayUtil.showSelectCount = !showNoCountForSelect;
	         }
	         else {
	           JDBCDisplayUtil.showSelectCount = false;
	         }

						boolean showNoRowsForSelect = Boolean.valueOf(util.getSystemProperty("gfxd.showNoRowsForSelect")).booleanValue(); // GemStone change ij ==> gfxd
						JDBCDisplayUtil.showSelectRows = !showNoRowsForSelect;

		// GemStone changes END
		connEnv[0].addSession(conn, (String) null);
		fileInput = initialFileInput = !in.isStandardInput();
		commandGrabber[0].ReInit(in);
		return runScriptGuts();
	}
	
	/**
	 * Run the guts of the script. Split out to allow
	 * calling from the full ij and the minimal goScript.
     * @return The number of errors seen in the script.
	 *
	 */
	private int runScriptGuts() {

        int scriptErrorCount = 0;
		
		boolean done = false;
		String command = null;
// GemStone changes BEGIN
		// run any initial script
		final File initFile;
		String initScript = System.getProperty("gfxd.initScript");
		if (initScript == null) {
		  final String homeDir = System.getProperty("user.home");
		  initFile = new File(homeDir, ".gfxd.init");
		}
		else {
		  initFile = new File(initScript);
		}
		if (initFile.exists()) {
		  newInput(initFile.getAbsolutePath());
		}
// GemStone changes END
		while (!ijParser.exit && !done) {
			try{
				ijParser.setConnection(connEnv[currCE], (numConnections > 1));
			} catch(Throwable t){
				//do nothing
				}

// GemStone changes BEGIN
			/* (original code)
			connEnv[currCE].doPrompt(true, out);
			*/
// GemStone changes END
   			try {
   				command = null;
// GemStone changes BEGIN
				command = commandGrabber[currCE].nextStatement(
				    this.connEnv[currCE], this.out);
				/* (original code)
				out.flush();
				command = commandGrabber[currCE].nextStatement();
				*/
// GemStone changes END

				// if there is no next statement,
				// pop back to the top saved grabber.
				while (command == null && ! oldGrabbers.empty()) {
					// close the old input file if not System.in
					if (fileInput) commandGrabber[currCE].close();
					commandGrabber[currCE] = (StatementFinder)oldGrabbers.pop();
					if (oldGrabbers.empty())
						fileInput = initialFileInput;
// GemStone changes BEGIN
					command = commandGrabber[currCE]
					    .nextStatement(null, this.out);
					/* (original code)
					command = commandGrabber[currCE].nextStatement();
					*/
// GemStone changes END
				}

				// if there are no grabbers left,
				// we are done.
				if (command == null && oldGrabbers.empty()) {
					done = true;
				}
				else {
					boolean	elapsedTimeOn = ijParser.getElapsedTimeState();
					long	beginTime = 0;
					long	endTime;

					if (fileInput) {
						out.println(command+";");
						out.flush();
					}

                                        //GemStone changes BEGIN
                                        if(ijParser.getExplainMode()) {
                                           charStream.ReInit(new StringReader(command), 1, 1);
                                        }
                                        else {
					  charStream.ReInit(new StringReader(command), 1, 1);
                                        }
                                        //charStream.ReInit(new StringReader(command), 1, 1);
                                        //GemStone changes END
					ijTokMgr.ReInit(charStream);
					ijParser.ReInit(ijTokMgr);

					if (elapsedTimeOn) {
						beginTime = System.currentTimeMillis();
					}

					ijResult result = ijParser.ijStatement();
					endTime = displayResult(out,result,connEnv[currCE].getConnection(),
					    beginTime /* GemStoneAddition */, true);

					// if something went wrong, an SQLException or ijException was thrown.
					// we can keep going to the next statement on those (see catches below).
					// ijParseException means we try the SQL parser.

					/* Print the elapsed time if appropriate */
					if (elapsedTimeOn) {
// GemStone changes BEGIN
						out.println(langUtil.getTextMessage("IJ_ElapTime0Mil",
						    langUtil.getNumberAsString(endTime)));
						/* (original code)
						endTime = System.currentTimeMillis();
						out.println(langUtil.getTextMessage("IJ_ElapTime0Mil", 
						langUtil.getNumberAsString(endTime - beginTime)));
						*/
// GemStone changes END
					}

					// would like when it completes a statement
					// to see if there is stuff after the ;
					// and before the <EOL> that we will IGNORE
					// (with a warning to that effect)
				}

    			} catch (ParseException e) {
// GemStone changes BEGIN
    			  if (!printConnectUsage(command, out))
// GemStone changes END
 					if (command != null)
                        scriptErrorCount += doCatch(command) ? 0 : 1;
				} catch (TokenMgrError e) {
// GemStone changes BEGIN
				  if (!printConnectUsage(command, out))
// GemStone changes END
 					if (command != null)
                        scriptErrorCount += doCatch(command) ? 0 : 1;
    			} catch (SQLException e) {
                    scriptErrorCount++;
					// SQL exception occurred in ij's actions; print and continue
					// unless it is considered fatal.
					handleSQLException(out,e);
// GemStone changes BEGIN
					if (!"08001".equals(e.getSQLState())
					    && !"08004".equals(e.getSQLState())) {
					  printConnectUsage(command, out);
					}
// GemStone changes END
    			} catch (ijException e) {
                    scriptErrorCount++;
					// exception occurred in ij's actions; print and continue
// GemStone changes BEGIN
    			  	out.println(langUtil.getTextMessage("IJ_IjErro0",
    			  	    getExceptionMessageForDisplay(e)));
    			  	/* (original code)
    			  	out.println(langUtil.getTextMessage("IJ_IjErro0",e.getMessage()));
    			  	*/
// GemStone changes END
					doTrace(e);
    			} catch (Throwable e) {
                    scriptErrorCount++;
// GemStone changes BEGIN
    			  	out.println(langUtil.getTextMessage("IJ_JavaErro0",
    			  	    getExceptionMessageForDisplay(e)));
    			  	/* (original code)
    			  	out.println(langUtil.getTextMessage("IJ_JavaErro0",e.toString()));
    			  	*/
// GemStone changes END
					doTrace(e);
				}

			/* Go to the next connection/user, if there is one */
			currCE = ++currCE % connEnv.length;
		}
        
// GemStone changes BEGIN
		final ConsoleReader reader;
		if (this.commandGrabber != null
		    && this.commandGrabber.length > 0) {
		  reader = commandGrabber[0].getConsoleReader();
		}
		else {
		  reader = null;
		}
		if (reader == null) {
		  out.println();
		}
// GemStone changes END
        return scriptErrorCount;
	}
	
	/**
	 * Perform cleanup after a script has been run.
	 * Close the input streams if required and shutdown
	 * derby on an exit.
	 * @param in
	 */
	private void cleanupGo(LocalizedInput[] in) {

		// we need to close all sessions when done; otherwise we have
		// a problem when a single VM runs successive IJ threads
		try {
			for (int i = 0; i < connEnv.length; i++) {
				connEnv[i].removeAllSessions();
			}
		} catch (SQLException se ) {
			handleSQLException(out,se);
		}
		// similarly must close input files
		for (int i = 0; i < numConnections; i++) {
			try {
				in[i].close();	
			} catch (Exception e ) {
    			  	out.println(langUtil.getTextMessage("IJ_CannotCloseInFile",
					e.toString()));
			}
		}

		/*
			If an exit was requested, then we will be shutting down.
		 */
		if (ijParser.exit || (initialFileInput && !mtUse)) {
			Driver d = null;
			try {
// GemStone changes BEGIN
			    d = DriverManager.getDriver("jdbc:gemfirexd:");
			    /* (original code)
			    d = DriverManager.getDriver("jdbc:derby:");
			    */
// GemStone changes END
			} catch (Throwable e) {
				d = null;
			}
			if (d!=null) { // do we have a driver running? shutdown on exit.
				try {
// GemStone changes BEGIN
				  // do this only if GemFireStore has been booted
				  boolean isBooting = false;
				  try {
				    Class<?> c = Class.forName(
				        "com.pivotal.gemfirexd.internal.engine.store.GemFireStore");
				    if (c != null) {
				      isBooting = c.getMethod("getBootingInstance")
				          .invoke(null) != null;
				    }
				  } catch (Exception e) {
				    isBooting = false;
				  }
				  if (isBooting) {
					DriverManager.getConnection(
					    "jdbc:gemfirexd:;shutdown=true");
				  }
					/* (original code)
					DriverManager.getConnection("jdbc:derby:;shutdown=true");
					*/
// GemStone changes END
				} catch (SQLException e) {
					// ignore the errors, they are expected.
				}
			}
		}
  	}

	private long /* GemStone change: void */ displayResult(LocalizedOutput out, ijResult result, Connection conn,
	    long beginTime /* GemStoneAddition */,
	    boolean displayCount /* GemStoneAddition */) throws SQLException {
	  final StopWatch timer = SharedUtils.newTimer(beginTime);
		// display the result, if appropriate.
		if (result!=null) {
// GemStone changes BEGIN
                  final ConsoleReader reader;
                  if (this.commandGrabber != null
                      && this.commandGrabber.length > 0) {
                    reader = commandGrabber[0].getConsoleReader();
                  }
                  else {
                    reader = null;
                  }
// GemStone changes END
			if (result.isConnection()) {
				if (result.hasWarnings()) {
					JDBCDisplayUtil.ShowWarnings(out,result.getSQLWarnings());
					result.clearSQLWarnings();
				// GemStone changes BEGIN
				} else {
				  out.println(LocalizedResource.getMessage("IJ_UsingConn",
				      connEnv[currCE].getSession().getName()));
				// GemStone changes END
				}
			} else if (result.isStatement()) {
				Statement s = result.getStatement();
				try {
// GemStone changes BEGIN
				    JDBCDisplayUtil.DisplayResults(out,
				        s, connEnv[currCE].getConnection(),
				        reader, timer, displayCount);
				    /* (original code)
				    JDBCDisplayUtil.DisplayResults(out,s,connEnv[currCE].getConnection());
				    */
// GemStone changes END
				} catch (SQLException se) {
				    result.closeStatement();
					throw se;
				}
				result.closeStatement();
			} else if (result.isNextRowOfResultSet()) {
				ResultSet r = result.getNextRowOfResultSet();
				JDBCDisplayUtil.DisplayCurrentRow(out,r,connEnv[currCE].getConnection(),
				    reader /* GemStoneAddition */,
				    timer /* GemStoneAddition */);
// GemStone changes BEGIN
			} else if (result.isHelp()) {
			  if (result.pageResult() && reader != null) {
			    SanityManager.printPagedOutput(System.out,
			        reader.getInput(), result.getHelpMessage(),
			        reader.getTerminal().getHeight() - 1, LocalizedResource
			          .getMessage("UTIL_GFXD_Continue_Prompt"), false);
			  }
			  else {
			    out.println(result.getHelpMessage());
			  }
// GemStone changes END
			} else if (result.isVector()) {
				util.DisplayVector(out,result.getVector());
				if (result.hasWarnings()) {
					JDBCDisplayUtil.ShowWarnings(out,result.getSQLWarnings());
					result.clearSQLWarnings();
				}
			} else if (result.isMulti()) {
			    try {
				    util.DisplayMulti(out,(PreparedStatement)result.getStatement(),result.getResultSet(),connEnv[currCE].getConnection(),
				        reader /* GemStoneAddition */,
				        timer /* GemStoneAddition */);
				} catch (SQLException se) {
				    result.closeStatement();
					throw se;
				}
				result.closeStatement(); // done with the statement now
				if (result.hasWarnings()) {
					JDBCDisplayUtil.ShowWarnings(out,result.getSQLWarnings());
					result.clearSQLWarnings();
				}
			} else if (result.isResultSet()) {
				ResultSet rs = result.getResultSet();
				try {
// GemStone changes BEGIN
					JDBCDisplayUtil.DisplayResults(out,
					    rs, connEnv[currCE].getConnection(),
					    result.getColumnDisplayList(),
					    result.getColumnWidthList(),
					    reader, timer);
					/* (original code)
					JDBCDisplayUtil.DisplayResults(out,rs,connEnv[currCE].getConnection(), result.getColumnDisplayList(), result.getColumnWidthList());
					*/
// GemStone changes END
				} catch (SQLException se) {
					result.closeStatement();
					throw se;
				}
				result.closeStatement();
            } else if (result.isMultipleResultSetResult()) {
              List resultSets = result.getMultipleResultSets();
              try {
                JDBCDisplayUtil.DisplayMultipleResults(out,resultSets,
                                     connEnv[currCE].getConnection(),
                                     result.getColumnDisplayList(),
                                     result.getColumnWidthList(),
                                     reader /* GemStoneAddition */,
                                     timer /* GemStoneAddition */);
              } catch (SQLException se) {
                result.closeStatement();
                throw se;
              }
			} else if (result.isException()) {
				JDBCDisplayUtil.ShowException(out,result.getException());
			}
		}
// GemStone changes BEGIN
		if (timer != null) {
		  timer.stop();
		  return timer.getElapsedMillis();
		}
		else {
		  return -1;
		}
// GemStone changes END
	}

	/**
	 * catch processing on failed commands. This really ought to
	 * be in ij somehow, but it was easier to catch in Main.
	 */
	private boolean doCatch(String command) {
		// this retries the failed statement
		// as a JSQL statement; it uses the
		// ijParser since that maintains our
		// connection and state.

        
	    RedirectedLocalizedOutput redirected = null;
	    try {
				int repeatCommand = numTimesToRun;
				boolean reportRunNum = false;
				String firstToken = ClientSharedUtils.getStatementToken(command, 0);
				// final String c = command.trim().toLowerCase();
				if (firstToken.equalsIgnoreCase("set") || firstToken.equalsIgnoreCase("elapsed")) {
					repeatCommand = 1;
				} else if (repeatCommand > 1) {
					reportRunNum = true;
				}

				int runNumber = 0;
			do { // repeatCommand

			boolean	elapsedTimeOn = ijParser.getElapsedTimeState();
			long	beginTime = 0;
			long	endTime;

			if (elapsedTimeOn) {
				beginTime = System.currentTimeMillis();
			}

			ijResult result = ijParser.executeImmediate(command);
                        if (ijParser.getExplainMode() && command.startsWith("explain ")) {
                          redirected = RedirectedLocalizedOutput.getNewInstance();
                        }
                        else {
                          redirected = null;
                        }
// GemStone changes BEGIN
      boolean displayCount = false;
      if (firstToken.equalsIgnoreCase("insert") || firstToken.equalsIgnoreCase("update")
        || firstToken.equalsIgnoreCase("delete") || firstToken.equalsIgnoreCase("put") ) {
        displayCount = true;
      }
// GemStone changes END
			endTime = displayResult(redirected == null ? out :
			  redirected,result,connEnv[currCE].getConnection(),
			  beginTime /* GemStoneAddition */, displayCount /* GemStoneAddition */);

			/* Print the elapsed time if appropriate */
			if (elapsedTimeOn) {
// GemStone changes BEGIN
				if (reportRunNum) {
					out.println("Run - " + runNumber + " " + langUtil.getTextMessage("IJ_ElapTime0Mil_4",
				    langUtil.getNumberAsString(endTime)));
				}	else {
						out.println(langUtil.getTextMessage("IJ_ElapTime0Mil_4",
								langUtil.getNumberAsString(endTime)));
				}
				/* (original code)
				endTime = System.currentTimeMillis();
				out.println(langUtil.getTextMessage("IJ_ElapTime0Mil_4", 
				langUtil.getNumberAsString(endTime - beginTime)));
				*/
// GemStone changes END
			} else if (reportRunNum) {
				out.println("Run - " + runNumber + " completed ");
			}

			} while ( ++runNumber < repeatCommand);
            return true;

	    } catch (SQLException e) {
			// SQL exception occurred in ij's actions; print and continue
			// unless it is considered fatal.
			handleSQLException(out,e);
	    } catch (ijException i) {
// GemStone changes BEGIN
	  		out.println(langUtil.getTextMessage("IJ_IjErro0_5",
	  		    getExceptionMessageForDisplay(i)));
	  		/* (original code)
	  		out.println(langUtil.getTextMessage("IJ_IjErro0_5", i.getMessage()));
	  		*/
// GemStone changes END
			doTrace(i);
		} catch (ijTokenException ie) {
// GemStone changes BEGIN
	  		out.println(langUtil.getTextMessage("IJ_IjErro0_6",
	  		    getExceptionMessageForDisplay(ie)));
	  		/* (original code)
	  		out.println(langUtil.getTextMessage("IJ_IjErro0_6", ie.getMessage()));
	  		*/
// GemStone changes END
			doTrace(ie);
	    } catch (Throwable t) {
// GemStone changes BEGIN
	  		out.println(langUtil.getTextMessage("IJ_JavaErro0_7",
	  		    getExceptionMessageForDisplay(t)));
	  		/* (original code)
	  		out.println(langUtil.getTextMessage("IJ_JavaErro0_7", t.toString()));
	  		*/
// GemStone changes END
			doTrace(t);
	    }
	    finally {
	       if ( redirected != null) {
	         redirected.waitForCompletion();
	       }
	      
	    }
        return false;
	}

	/**
	 * This routine displays SQL exceptions and decides whether they
	 * are fatal or not, based on the ignoreErrors field. If they
	 * are fatal, an ijFatalException is thrown.
	 * Lifted from ij/util.java:ShowSQLException
	 */
	private void handleSQLException(LocalizedOutput out, SQLException e) 
		throws ijFatalException
	{
		String errorCode;
		String sqlState = null;
		SQLException fatalException = null;

		if (showErrorCode) {
			errorCode = langUtil.getTextMessage("IJ_Erro0", 
			langUtil.getNumberAsString(e.getErrorCode()));
		}
		else {
			errorCode = "";
		}

		boolean syntaxErrorOccurred = false;
		for (; e!=null; e=e.getNextException())
		{
			sqlState = e.getSQLState();
			if ("42X01".equals(sqlState))
				syntaxErrorOccurred = true;
			/*
			** If we are to throw errors, then throw the exceptions
			** that aren't in the ignoreErrors list.  If
			** the ignoreErrors list is null we don't throw
			** any errors.
			*/
		 	if (ignoreErrors != null) 
			{
				if ((sqlState != null) &&
					(ignoreErrors.get(sqlState) != null))
				{
					continue;
				}
				else
				{
					fatalException = e;
				}
			}

			String st1 = JDBCDisplayUtil.mapNull(e.getSQLState(),langUtil.getTextMessage("IJ_NoSqls"));
			String st2 = JDBCDisplayUtil.mapNull(e.getMessage(),langUtil.getTextMessage("IJ_NoMess"));
// GemStone changes BEGIN
			final StringBuilder sb = new StringBuilder(langUtil
			    .getTextMessage("IJ_Erro012",  st1, st2, errorCode));
			if (this.ijExceptionTrace == null) {
			  getExceptionCauseForDisplay(e, sb);
			}
			out.println(sb.toString());
			/* (original code)
			out.println(langUtil.getTextMessage("IJ_Erro012",  st1, st2, errorCode));
			*/
// GemStone changes END
			doTrace(e);
		}
		if (fatalException != null)
		{
			throw new ijFatalException(fatalException);
		}
		if (syntaxErrorOccurred)
			out.println(convertGfxdMessageToSnappy(langUtil.getTextMessage("IJ_SuggestHelp")));
	}

	/**
	 * stack trace dumper
	 */
	private void doTrace(Throwable t) {
		if (ijExceptionTrace != null) {
			t.printStackTrace(out);
		}
		out.flush();
	}

	void newInput(String fileName) {
		FileInputStream newFile = null;
		try {
			newFile = new FileInputStream(fileName);
      	} catch (FileNotFoundException e) {
      	        // GemStone changes BEGIN
      	         /*(original code) throw ijException.fileNotFound();*/
                  if (basePath == null) {
                    throw ijException.fileNotFound();
                  }
                  try {
                    newFile = new FileInputStream(new File(basePath, fileName));
                  } catch (FileNotFoundException e1) {
                    throw ijException.fileNotFound();
                  }
                // GemStone changes END
		}
		if (newFile == null) return;

		// if the file was opened, move to use it for input.
		oldGrabbers.push(commandGrabber[currCE]);
	    commandGrabber[currCE] = 
                new StatementFinder(langUtil.getNewInput(new BufferedInputStream(newFile, BUFFEREDFILESIZE)), null, basePath, params);
		fileInput = true;
	}

	void newResourceInput(String resourceName) {
		InputStream is = util.getResourceAsStream(resourceName);
		if (is==null) throw ijException.resourceNotFound();
		oldGrabbers.push(commandGrabber[currCE]);
	    commandGrabber[currCE] = 
                new StatementFinder(langUtil.getNewEncodedInput(new BufferedInputStream(is, BUFFEREDFILESIZE), "UTF8"), null, basePath, params);
		fileInput = true;
	}

	/**
	 * REMIND: eventually this might be part of StatementFinder,
	 * used at each carriage return to show that it is still "live"
	 * when it is reading multi-line input.
	 */
	static void doPrompt(boolean newStatement, LocalizedOutput out, String tag) 
	 {
		if (newStatement) {
// GemStone changes BEGIN
				out.print(basePrompt + (tag == null ? "" : tag) + "> ");
	  		/* (original code)
	  		out.print("ij"+(tag==null?"":tag)+"> ");
	  		*/
// GemStone changes END
		}
		else {
			out.print("> ");
		}
		out.flush();
	}

// GemStone changes BEGIN
	static String getPrompt(boolean newStatement, String tag) {
	  if (newStatement) {
	    return basePrompt + tag + "> ";
	  }
	  return "> ";
	}

	String getExceptionMessageForDisplay(final Throwable t) {
	  if (this.ijExceptionTrace != null) {
	    return t.getMessage();
	  }
	  StringBuilder sb = new StringBuilder();
	  if (!(t instanceof ijException)) {
	    sb.append(t.getClass().getSimpleName()).append(": ");
	  }
	  sb.append(t.getMessage());
	  return getExceptionCauseForDisplay(t, sb);
	}

	String getExceptionCauseForDisplay(final Throwable t,
	    final StringBuilder sb) {
	  Throwable cause = t;
	  String tSimpleName = t.getClass().getSimpleName();
	  while (cause.getCause() != null) {
	    cause = cause.getCause();
	  }
	  String causeClass;
	  if (!"SQLException".equals(tSimpleName) &&
	     cause != t && !"SqlException".equals(
	      (causeClass = cause.getClass().getSimpleName()))) {
	     sb.append(SanityManager.lineSeparator);
	     sb.append("Caused by: ").append(causeClass);
	     sb.append(": ").append(cause.getMessage());
	     sb.append(SanityManager.lineSeparator);
	     sb.append("\tat ").append(cause.getStackTrace()[0]);
	  }
	  return sb.toString();
	}

	boolean printConnectUsage(final String command,
	    final java.io.PrintWriter out) {
	  // special treatment for "connect"
	  final String connect = "connect";
	  final String clientConnect = "client";
	  final String peerConnect = "peer";
	  final String hostsConnect = "hosts";
	  final String remaining;
	  String trimCommand;
	  if (command != null && (trimCommand = command.trim())
	      .length() >= connect.length() && trimCommand.substring(
	          0, connect.length()).equalsIgnoreCase(connect)) {
	    if (trimCommand.length() > connect.length()) {
	      remaining = trimCommand.substring(connect.length()).trim();
	      if (remaining.length() >= clientConnect.length()
	          && remaining.substring(0, clientConnect.length())
	             .equalsIgnoreCase(clientConnect)) {
	        out.println(LocalizedResource.getMessage(
	            "GFXD_ConnectHelpText_Client",
	            LocalizedResource.getMessage("GFXD_Usage")));
	        return true;
	      }
	      else if (remaining.length() >= peerConnect.length()
	          && remaining.substring(0, peerConnect.length())
	             .equalsIgnoreCase(peerConnect)) {
	        out.println(convertGfxdMessageToSnappy(LocalizedResource.getMessage(
	            "GFXD_ConnectHelpText_Peer",
	            LocalizedResource.getMessage("GFXD_Usage"))));
	        return true;
	      }
	      else if (remaining.length() >= hostsConnect.length()
                  && remaining.substring(0, hostsConnect.length())
                     .equalsIgnoreCase(hostsConnect)) {
                out.println(LocalizedResource.getMessage(
                    "GFXD_ConnectHelpText_Hosts",
                    LocalizedResource.getMessage("GFXD_Usage")));
                return true;
              }
	    }
            out.println(LocalizedResource.getMessage("GFXD_ConnectHelpText",
                LocalizedResource.getMessage("GFXD_ConnectHelpText_Client",
                    LocalizedResource.getMessage("GFXD_Usage")), LocalizedResource
                    .getMessage("GFXD_ConnectHelpText_Peer", ""), LocalizedResource
                    .getMessage("GFXD_ConnectHelpText_Hosts", "")));
	    return true;
	  } else if (command != null) {
	    String cmd = command.trim().length() > 3 ? command.trim().substring(0, 4) : command;
	    if(cmd.equalsIgnoreCase("run ")) {
	      out.println(LocalizedResource.getMessage("IJ_ExceRunnComm",
		  command + "\n" + LocalizedResource.getMessage("IJ_RunUsage")));
	      return true;
	    }
	  }
	  return false;
	}

	public static String convertGfxdMessageToSnappy(String message) {
		if (basePrompt.contains("snappy")) {
			GemFireVersion.getInstance(GemFireXDVersion.class, SharedUtils.GFXD_VERSION_PROPERTIES);
			String productEditionType = GemFireVersion.isEnterpriseEdition() ? "TIBCO ComputeDB" : "SnappyData";
			return pattern.matcher(message).replaceAll(productEditionType);
		} else {
			return message;
		}
	}

// GemStone changes END
	void setMtUse(boolean b) {
		mtUse = b;
	}

    /**
     * Check that the cursor is scrollable.
     *
     * @param rs the ResultSet to check
     * @param operation which operation this is checked for
     * @exception ijException if the cursor isn't scrollable
     * @exception SQLException if a database error occurs
     */
    private void checkScrollableCursor(ResultSet rs, String operation)
            throws ijException, SQLException {
        if (rs.getType() == ResultSet.TYPE_FORWARD_ONLY) {
            throw ijException.forwardOnlyCursor(operation);
        }
    }

	/**
	 * Position on the specified row of the specified ResultSet.
	 *
	 * @param rs	The specified ResultSet.
	 * @param row	The row # to move to.
	 *				(Negative means from the end of the result set.)
	 *
	 * @return	NULL.
	 *
	 * @exception	SQLException thrown on error.
	 *				(absolute() not supported pre-JDBC2.0)
	 */
	ijResult absolute(ResultSet rs, int row)
		throws SQLException
	{
        checkScrollableCursor(rs, "ABSOLUTE");
		// 0 is an *VALID* value for row
		return new ijRowResult(rs, rs.absolute(row));
	}

	/**
	 * Move the cursor position by the specified amount.
	 *
	 * @param rs	The specified ResultSet.
	 * @param row	The # of rows to move.
	 *				(Negative means toward the beginning of the result set.)
	 *
	 * @return	NULL.
	 *
	 * @exception	SQLException thrown on error.
	 *				(relative() not supported pre-JDBC2.0)
	 */
	ijResult relative(ResultSet rs, int row)
		throws SQLException
	{
        checkScrollableCursor(rs, "RELATIVE");
		return new ijRowResult(rs, rs.relative(row));
	}

	/**
	 * Position before the first row of the specified ResultSet
	 * and return NULL to the user.
	 *
	 * @param rs	The specified ResultSet.
	 *
	 * @return	NULL.
	 *
	 * @exception	SQLException thrown on error.
	 *				(beforeFirst() not supported pre-JDBC2.0)
	 */
	ijResult beforeFirst(ResultSet rs)
		throws SQLException
	{
        checkScrollableCursor(rs, "BEFORE FIRST");
		rs.beforeFirst();
		return new ijRowResult(rs, false);
	}

	/**
	 * Position on the first row of the specified ResultSet
	 * and return that row to the user.
	 *
	 * @param rs	The specified ResultSet.
	 *
	 * @return	The first row of the ResultSet.
	 *
	 * @exception	SQLException thrown on error.
	 *				(first() not supported pre-JDBC2.0)
	 */
	ijResult first(ResultSet rs)
		throws SQLException
	{
        checkScrollableCursor(rs, "FIRST");
		return new ijRowResult(rs, rs.first());
	}

	/**
	 * Position after the last row of the specified ResultSet
	 * and return NULL to the user.
	 *
	 * @param rs	The specified ResultSet.
	 *
	 * @return	NULL.
	 *
	 * @exception	SQLException thrown on error.
	 *				(afterLast() not supported pre-JDBC2.0)
	 */
	ijResult afterLast(ResultSet rs)
		throws SQLException
	{
        checkScrollableCursor(rs, "AFTER LAST");
		rs.afterLast();
		return new ijRowResult(rs, false);
	}

	/**
	 * Position on the last row of the specified ResultSet
	 * and return that row to the user.
	 *
	 * @param rs	The specified ResultSet.
	 *
	 * @return	The last row of the ResultSet.
	 *
	 * @exception	SQLException thrown on error.
	 *				(last() not supported pre-JDBC2.0)
	 */
	ijResult last(ResultSet rs)
		throws SQLException
	{
        checkScrollableCursor(rs, "LAST");
		return new ijRowResult(rs, rs.last());
	}

	/**
	 * Position on the previous row of the specified ResultSet
	 * and return that row to the user.
	 *
	 * @param rs	The specified ResultSet.
	 *
	 * @return	The previous row of the ResultSet.
	 *
	 * @exception	SQLException thrown on error.
	 *				(previous() not supported pre-JDBC2.0)
	 */
	ijResult previous(ResultSet rs)
		throws SQLException
	{
        checkScrollableCursor(rs, "PREVIOUS");
		return new ijRowResult(rs, rs.previous());
	}

	/**
	 * Get the current row number
	 *
	 * @param rs	The specified ResultSet.
	 *
	 * @return	The current row number
	 *
	 * @exception	SQLException thrown on error.
	 *				(getRow() not supported pre-JDBC2.0)
	 */
	int getCurrentRowNumber(ResultSet rs)
		throws SQLException
	{
        checkScrollableCursor(rs, "GETCURRENTROWNUMBER");
		return rs.getRow();
	}

	Properties getConnAttributeDefaults ()
	{
		return connAttributeDefaults;
	}

	public final Object run() {
		return  getClass().getResourceAsStream(ProductGenusNames.TOOLS_INFO);
	}

}
