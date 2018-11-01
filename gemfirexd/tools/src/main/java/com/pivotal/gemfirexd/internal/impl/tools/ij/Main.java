/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.tools.ij.Main

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

import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedInput;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedOutput;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.tools.JDBCDisplayUtil;

import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.Reader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.io.IOException;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.Connection;
import java.sql.SQLException;

import java.util.*;
// GemStone changes BEGIN
import java.io.File;

import jline.console.ConsoleReader;
import jline.console.history.FileHistory;
import jline.console.completer.StringsCompleter;
// GemStone changes END

/**
 * This is the controller for ij. It uses two parsers:
 * one to grab the next statement, and another to
 * see if it is an ij command, and if so execute it.
 * If it is not an ij command, it is treated as a JSQL
 * statement and executed against the current connection.
 * ijParser controls the current connection, and so contains
 * all of the state information for executing JSQL statements.
 * <p>
 * This was written to facilitate a test harness for language
 * functionality tests.
 *
 *
 */
public class Main {
	private utilMain utilInstance;

	/**
	 * ij can be used directly on a shell command line through
	 * its main program.
	 * @param args allows 1 file name to be specified, from which
	 *    input will be read; if not specified, stdin is used.
	 */
	public static void main(String[] args)	
		throws IOException 
	{
		mainCore(args, new Main(true));
	}

	public static void mainCore(String[] args, Main main)
		throws IOException 
	{
		LocalizedInput in = null;
		InputStream in1 = null;
		Main me;
		String file;
		String inputResourceName;
		boolean gotProp;
		Properties connAttributeDefaults = null;

		LocalizedResource langUtil = LocalizedResource.getInstance();
		LocalizedOutput out = langUtil.getNewOutput(System.out);

                // Validate arguments, check for --help.
		if (util.invalidArgs(args)) {
			util.Usage(out);
      		return;
		}

		// load the property file if specified
		gotProp = util.getPropertyArg(args);

		// get the default connection attributes
		connAttributeDefaults = util.getConnAttributeArg(args);

		// readjust output to gemfirexd.ui.locale and gemfirexd.ui.codeset if 
                // they were loaded from a property file.
		langUtil.init();
		out = langUtil.getNewOutput(System.out);
                main.initAppUI();

		file = util.getFileArg(args);
		inputResourceName = util.getInputResourceNameArg(args);
		if (inputResourceName != null) {
			in = langUtil.getNewInput(util.getResourceAsStream(inputResourceName));
			if (in == null) {
				out.println(utilMain.convertGfxdMessageToSnappy(langUtil.getTextMessage("IJ_IjErroResoNo",inputResourceName)));
				return;
			}
		} else if (file == null) {
			in = langUtil.getNewInput(System.in);
                        out.flush();
    	        } else {
                    try {
                        in1 = new FileInputStream(file);
                        if (in1 != null) {
                            in1 = new BufferedInputStream(in1, utilMain.BUFFEREDFILESIZE);
                            in = langUtil.getNewInput(in1);
                        }
                    } catch (FileNotFoundException e) {
                        if (Boolean.getBoolean("ij.searchClassPath")) {
                            in = langUtil.getNewInput(util.getResourceAsStream(file));
                        }
                        if (in == null) {
                        out.println(utilMain.convertGfxdMessageToSnappy(langUtil.getTextMessage("IJ_IjErroFileNo",file)));
            		  return;
                        }
                    }
                }

		final String outFile = util.getSystemProperty("ij.outfile");
		if (outFile != null && outFile.length()>0) {
			LocalizedOutput oldOut = out;
			FileOutputStream fos = (FileOutputStream) AccessController.doPrivileged(new PrivilegedAction() {
				public Object run() {
					FileOutputStream out = null;
					try {
						out = new FileOutputStream(outFile);
					} catch (FileNotFoundException e) {
						out = null;
					}
					return out;
				}
			});
			out = langUtil.getNewOutput(fos);

			if (out == null)
			   oldOut.println(utilMain.convertGfxdMessageToSnappy(langUtil.getTextMessage("IJ_IjErroUnabTo", outFile)));
	
		}

		// the old property name is deprecated...
		String maxDisplayWidth = util.getSystemProperty("maximumDisplayWidth");
		if (maxDisplayWidth==null) 
			maxDisplayWidth = util.getSystemProperty("ij.maximumDisplayWidth");
		if (maxDisplayWidth != null && maxDisplayWidth.length() > 0) {
			try {
				int maxWidth = Integer.parseInt(maxDisplayWidth);
				JDBCDisplayUtil.setMaxDisplayWidth(maxWidth);
			}
			catch (NumberFormatException nfe) {
				out.println(utilMain.convertGfxdMessageToSnappy(langUtil.getTextMessage("IJ_IjErroMaxiVa", maxDisplayWidth)));
			}
		}

		/* Use the main parameter to get to
		 * a new Main that we can use.  
		 * (We can't do the work in Main(out)
		 * until after we do all of the work above
		 * us in this method.
		 */
		me = main.getMain(out);

// GemStone changes BEGIN
		if (in != null && in.isStandardInput() && System.console() != null
		    && !"jline.UnsupportedTerminal".equals(
		        System.getProperty("jline.terminal"))) {
		  // use jline for reading input
		  final String encode;
		  if ((encode = in.getEncoding()) != null) {
		    System.setProperty("input.encoding", encode);
		    System.setProperty("jline.WindowsTerminal.input.encoding",
		        encode);
		  }

		  final String historyFileName = System.getProperty(
		      utilMain.basePrompt + ".history", "." + utilMain.basePrompt + ".history");
		  // setup the input stream
		  final ConsoleReader reader = new ConsoleReader();
		  reader.setBellEnabled(false);
		  File histFile = new File(historyFileName);
		  if (historyFileName.length() > 0) {
		    final FileHistory hist;
		    // skip doInit in FileHistory to enable setting max-size before load
		    if (histFile.isAbsolute()) {
		      hist = new FileHistory(new File(historyFileName), false);
		    }
		    else {
		      hist = new FileHistory(new File((System
		          .getProperty("user.home")), historyFileName), false);
		    }
		    hist.setMaxSize(50000);
		    hist.load();
		    reader.setHistory(hist);
		  }
			Runtime.getRuntime().addShutdownHook(new Thread() {
				public void run() {
					try {
						reader.getTerminal().restore();
						((FileHistory)reader.getHistory()).flush();
					} catch (Exception e) {
						// restoration failed!
					}
				}
			});
		  // simple string completion for builtin ij commands
		  reader.addCompleter(new StringsCompleter(new String[]{
		      "PROTOCOL", "protocol", "DRIVER", "driver",
		      "CONNECT CLIENT", "connect client",
		      "CONNECT PEER", "connect peer",
		      "SET CONNECTION", "set connection",
		      "SHOW CONNECTIONS", "show connections",
		      "AUTOCOMMIT ON;", "AUTOCOMMIT OFF;",
		      "autocommit on;", "autocommit off;",
		      "DISCONNECT", "DISCONNECT CURRENT;", "DISCONNECT ALL;",
		      "disconnect", "disconnect current;", "disconnect all;",
		      "SHOW SCHEMAS;", "show schemas;",
		      "SHOW TABLES IN", "show tables in",
		      "SHOW VIEWS IN", "show views in",
		      "SHOW PROCEDURES IN", "show procedures in",
		      "SHOW SYNONYMS IN", "show synonyms in",
		      "SHOW INDEXES IN", "SHOW INDEXES FROM",
		      "show indexes in", "show indexes from",
                      "SHOW IMPORTEDKEYS IN", "SHOW IMPORTEDKEYS FROM",
                      "show importedkeys in", "show importedkeys from",
		      "SHOW MEMBERS", "show members",
		      "DESCRIBE", "describe", "COMMIT;", "commit;",
		      "ROLLBACK;", "rollback;", "PREPARE", "prepare",
		      "EXECUTE", "execute", "REMOVE", "remove", "RUN", "run",
		      "ELAPSEDTIME ON;", "ELAPSEDTIME OFF;",
		      "elapsedtime on;", "elapsedtime off;",
		      "MAXIMUMDISPLAYWIDTH", "maximumdisplaywidth",
		      "MAXIMUMLINEWIDTH", "maximumlinewidth",
		      "PAGING ON;", "paging on;", "PAGING OFF;", "paging off;",
		      "ASYNC", "async", "WAIT FOR", "wait for",
		      "GET", "GET CURSOR", "GET SCROLL INSENSITIVE CURSOR",
		      "get", "get cursor", "get scroll insensitive cursor",
		      "NEXT", "next", "FIRST", "first", "LAST", "last",
		      "PREVIOUS", "previous", "ABSOLUTE", "absolute",
		      "RELATIVE", "relative", "AFTER LAST", "after last",
		      "BEFORE FIRST", "before first", "CLOSE", "close",
		      "GETCURRENTROWNUMBER", "getcurrentrownumber",
		      "LOCALIZEDDISPLAY ON;", "LOCALIZEDDISPLAY OFF;",
		      "localizeddisplay on;", "localizeddisplay off;",
		      "EXIT;", "exit;", "QUIT;", "quit;", "HELP;", "help;",
		      "EXPLAIN ", "explain ",
		      "EXPLAINMODE ON;", "explainmode on;",
		      "EXPLAINMODE OFF;", "explainmode off;",
		    }));
		  // set the default max display width to terminal width
		  int termWidth;
		  if ((maxDisplayWidth == null || maxDisplayWidth.length() == 0)
		      && (termWidth = reader.getTerminal().getWidth()) > 4) {
		    JDBCDisplayUtil.setMaxLineWidth(termWidth, false);
		  }
		  in.setConsoleReader(reader);
		}
// GemStone changes END
		/* Let the processing begin! */
		me.go(in, out, connAttributeDefaults);
		in.close(); out.close();
	}

	/**
	 * Get the right Main (according to 
	 * the JDBC version.
	 *
	 * @return	The right main (according to the JDBC version).
	 */
	public Main getMain(LocalizedOutput out)
	{
		return new Main(out);
	}

	/**
	 * Get the right utilMain (according to 
	 * the JDBC version.
	 *
	 * @return	The right utilMain (according to the JDBC version).
	 */
	public utilMain getutilMain(int numConnections, LocalizedOutput out)
	{
		return new utilMain(numConnections, out);
	}

	/**
		Give a shortcut to go on the utilInstance so
		we don't expose utilMain.
	 */
	private void go(LocalizedInput in, LocalizedOutput out , 
				   Properties connAttributeDefaults)
	{
		LocalizedInput[] inA = { in } ;
		utilInstance.go(inA, out,connAttributeDefaults);
	}

	/**
	 * create an ij tool waiting to be given input and output streams.
	 */
	public Main() {
		this(null);
	}

	public Main(LocalizedOutput out) {
		if (out == null) {
	        out = LocalizedResource.getInstance().getNewOutput(System.out);
		}
		utilInstance = getutilMain(1, out);
		utilInstance.initFromEnvironment();
	}

	/**
	 * This constructor is only used so that we 
	 * can get to the right Main based on the
	 * JDBC version.  We don't do any work in
	 * this constructor and we only use this
	 * object to get to the right Main via
	 * getMain().
	 */
	public Main(boolean trash)
	{
	}
  private void initAppUI(){
    //To fix a problem in the AppUI implementation, a reference to the AppUI class is
    //maintained by this tool.  Without this reference, it is possible for the
    //AppUI class to be garbage collected and the initialization values lost.
    //langUtilClass = LocalizedResource.class;

		// adjust the application in accordance with gemfirexd.ui.locale and gemfirexd.ui.codeset
	LocalizedResource.getInstance();	
  }
  
}
