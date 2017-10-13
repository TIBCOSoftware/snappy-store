/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
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

package com.pivotal.gemfirexd.internal.shared.common.sanity;

import java.io.PrintWriter;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// GemStone changes BEGIN
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import io.snappydata.thrift.snappydataConstants;
// GemStone changes END

/**
 * The SanityService provides assertion checking and debug
 * control.
 * <p>
 * Assertions and debug checks
 * can only be used for testing conditions that might occur
 * in development code but not in production code.	
 * <b>They are compiled out of production code.</b>
 * <p>
 * Uses of assertions should not add AssertFailure catches or
 * throws clauses; AssertFailure is under RuntimeException
 * in the java exception hierarchy. Our outermost system block
 * will bring the system down when it detects an assertion
 * failure.
 * <p>
 * In addition to ASSERTs in code, classes can choose to implement
 * an isConsistent method that would be used by ASSERTs, UnitTests,
 * and any other code wanting to check the consistency of an object.
 * <p>
 * Assertions are meant to be used to verify the state of the system
 * and bring the system down if the state is not correct. Debug checks
 * are meant to display internal information about a running system.
 * <p>
 * @see AssertFailure
 */
public class SanityManager {
	/**
	 * The build tool may be configured to alter
	 * this source file to reset the static final variables
	 * so that assertion and debug checks can be compiled out
	 * of the code.
	 */

	public static final boolean ASSERT = SanityState.ASSERT; // code should use DEBUG
	public static final boolean DEBUG = SanityState.DEBUG;
	
	public static final String DEBUGDEBUG = "DumpSanityDebug";
	
	/**
	 * debugStream holds a pointer to the debug stream for writing out
	 * debug messages.  It is cached at the first debug write request.
	 */
// GemStone changes BEGIN
	public static final boolean DEBUG_ASSERT = SanityState.DEBUG_ASSERT;

	static private final java.io.PrintWriter DEFAULT_WRITER =
	  new java.io.PrintWriter(System.err);
//	static private java.io.PrintWriter debugStream = new java.io.PrintWriter(System.err);
	static private java.io.PrintWriter debugStream = DEFAULT_WRITER;
	/**
	 * DebugFlags holds the values of all debug flags in
	 * the configuration file.
	 */
//	static private Hashtable DebugFlags = new Hashtable();
	static private Map DebugFlags;

	/**
	 * Flag to enable tracing of client server selection, locator detection,
	 * failover etc.
	 */
	public static final String TRACE_CLIENT_HA = "TraceClientHA";

	/**
	 * Flag to enable tracing of client connection create/close stack traces
	 */
	public static final String TRACE_CLIENT_CONN = "TraceClientConn";

	/**
	 * Flag to enable tracing of statement prepare/execution/iteration.
	 */
	public static final String TRACE_CLIENT_STMT = "TraceClientStatement";

	/**
	 * Flag to enable logging of wall clock millis time in the tracing for
	 * {@link #TRACE_CLIENT_STMT}. Also turns on {@link #TRACE_CLIENT_STMT}.
	 */
        public static final String TRACE_CLIENT_STMT_MS =
            "TraceClientStatementMillis";

        /**
	 * Flag to enable tracing of possible memory leaks in some places.
	 */
	public static final String TRACE_MEMORY_LEAK = "TraceMemoryLeak";

        /**
         * Flag to enable tracing of ConcurrentCache operations.
         */
        public static final String TRACE_CACHE = "TraceCache";

        /**
         * Flag to enable tracing for single hop.
         */
        public static final String TRACE_SINGLE_HOP = "TraceSingleHop";

        /** Test flag to run tests in non-transactional mode.
         * If provided as true in system and environment variable, the tests will use 
         * connections with transaction isolation level NONE
         * and autocommit false.   If not, the tests will use connections with READ_COMMITTED
         * isolation level and autocommit true.*/
        public static final String TEST_MODE_NON_TX = "TEST_MODE_NON_TX";
  
	/**
	 * Static boolean set if client HA logging is turned on.
	 */
	public static boolean TraceClientHA = false;

	/**
	 * Static boolean set if client connection creation/close stack
	 * traces are to be traced.
	 */
	public static boolean TraceClientConn = false;

	/**
	 * Static boolean set if client statement verbose logging is turned on.
	 */
	public static boolean TraceClientStatement = false;

	/**
	 * Static boolean set if client statement verbose logging should also
	 * log wall clock time in millis.
	 */
	public static boolean TraceClientStatementMillis = false;

	/**
	 * Set when either of {@link #TRACE_CLIENT_HA} or
	 * {@link #TRACE_CLIENT_STMT} are set.
	 */
	public static boolean TraceClientStatementHA = false;

	/**
	 * Static boolean set if memory leak tracing is turned on.
	 */
	public static boolean TraceMemoryLeak = false;

	/**
	 * Static boolean set if ConcurrentCache tracing is turned on.
	 */
	public static boolean TraceCache = false;
	
	/**
         * Static boolean set if single hop tracing is turned on.
         */
        public static boolean TraceSingleHop = false;

	public static final String DEBUG_TRUE = "gemfirexd.debug.true";

	public static final String DEBUG_FALSE = "gemfirexd.debug.false";

	public static boolean isFineEnabled = false;

	public static boolean isFinerEnabled = false;

	static {
          DebugFlags = new ConcurrentHashMap(16, 0.75f, 4);
        }
// GemStone changes END
	/**
	 * AllDebugOn and AllDebugOff override individual flags
	 */
	static private boolean AllDebugOn = false;
	static private boolean AllDebugOff = false;

	//
	// class interface
	//

	/**
	 * ASSERT checks the condition, and if it is
	 * false, throws AssertFailure.
	 * A message about the assertion failing is
	 * printed.
	 * <p>
	 * @see AssertFailure
	 */
	public static final void ASSERT(boolean mustBeTrue) {
		if (DEBUG)
			if (! mustBeTrue) {
				if (DEBUG) {
					AssertFailure af = new AssertFailure("ASSERT FAILED");
					if (TRACE_ON("AssertFailureTrace")) {
						showTrace(af);
					}
					throw af;
				}
				else
					throw new AssertFailure("ASSERT FAILED");
			}
	}

	/**
	 * ASSERT checks the condition, and if it is
	 * false, throws AssertFailure. The message will
	 * be printed and included in the assertion.
	 * <p>
	 * @see AssertFailure
	 */
	public static final void ASSERT(boolean mustBeTrue, String msgIfFail) {
		if (DEBUG)
			if (! mustBeTrue) {
				if (DEBUG) {
					AssertFailure af = new AssertFailure("ASSERT FAILED " + msgIfFail);
					if (TRACE_ON("AssertFailureTrace")) {
						showTrace(af);
					}
					throw af;
				}
				else
					throw new AssertFailure("ASSERT FAILED " + msgIfFail);
			}
	}

	/**
	 * THROWASSERT throws AssertFailure. This is used in cases where
	 * the caller has already detected the assertion failure (such as
	 * in the default case of a switch). This method should be used,
	 * rather than throwing AssertFailure directly, to allow us to 
	 * centralize all sanity checking.  The message argument will
	 * be printed and included in the assertion.
     * <p>
	 * @param msgIfFail message to print with the assertion
	 *
	 * @see AssertFailure
	 */
	public static final void THROWASSERT(String msgIfFail) {
		// XXX (nat) Hmm, should we check ASSERT here?  The caller is
		// not expecting this function to return, whether assertions
		// are compiled in or not.
		THROWASSERT(msgIfFail, null);
	}

	/**
	 * THROWASSERT throws AssertFailure.
	 * This flavor will print the stack associated with the exception.
	 * The message argument will
	 * be printed and included in the assertion.
     * <p>
	 * @param msg message to print with the assertion
	 * @param t exception to print with the assertion
	 *
	 * @see AssertFailure
	 */
	public static final void THROWASSERT(String msg, Throwable t) {
// GemStone changes BEGIN
		try {
		  Class cls = Class.forName(
		      "com.pivotal.gemfirexd.internal.engine.store.GemFireStore");
		  if (cls != null) {
		    msg = msg + "; myID: " + cls.getMethod("getMyId",
		        (Class[])null).invoke(null, (Object[])null);
		  }
		} catch (Throwable ignored) {
		  // ignore here
		}
// GemStone changes END
		AssertFailure af = new AssertFailure("ASSERT FAILED " + msg, t);
		if (DEBUG) {
			if (TRACE_ON("AssertFailureTrace")) {
				showTrace(af);
			}
		}
		if (t != null) {
			showTrace(t);
		}
		throw af;
	}

	/**
	 * THROWASSERT throws AssertFailure.
	 * This flavor will print the stack associated with the exception.
     * <p>
	 * @param t exception to print with the assertion
	 *
	 * @see AssertFailure
	 */
	public static final void THROWASSERT(Throwable t) {
		THROWASSERT(t.toString(), t);
	}

	/**
     * The DEBUG calls provide the ability to print information or
     * perform actions based on whether a debug flag is set or not.
     * debug flags are set in configurations and picked up by the
     * sanity manager when the monitor finds them (see CONFIG below).
	 * <p>
	 * The message is output to the trace stream, so it ends up in
	 * db2j.LOG. It will include a header line of
	 *   DEBUG <flagname> OUTPUT:
	 * before the message.
	 * <p>
	 * If the debugStream stream cannot be found, the message is printed to
	 * System.out.
     */
	public static final void DEBUG(String flag, String message) {
		if (DEBUG) {
			if (flag == null || DEBUG_ON(flag)) {
				DEBUG_PRINT(flag, message);
			}
		}
	}

	/**
	 * This can be called directly if you want to control
     * what is done once the debug flag has been verified --
	 * for example, if you are calling a routine that prints to
	 * the trace stream directly rather than returning a string to
	 * be printed, or if you want to perform more (or fewer!)
	 *
	 * <p>
     * Calls to this method should be surrounded with
	 *     if (SanityManager.DEBUG) {
	 *     }
	 * so that they can be compiled out completely.
	 *
	 * @return true if the flag has been set to "true"; false
	 * if the flag is not set, or is set to something other than "true".
	 */
	public static final boolean DEBUG_ON(String flag) {
		if (DEBUG) {
			if (AllDebugOn) return true;
			else if (AllDebugOff) return false;
			else {
					Boolean flagValue = (Boolean) DebugFlags.get(flag);
					if (! DEBUGDEBUG.equals(flag)) {
						if (DEBUG_ON(DEBUGDEBUG)) {
							DEBUG_PRINT(DEBUGDEBUG, "DEBUG_ON: Debug flag "+flag+" = "+flagValue);
						}
					}
					if (flagValue == null) return false;
					else return flagValue.booleanValue();
			}
		}
		else return false;
	}
// GemStone changes BEGIN

	public static final void TRACE_SET_IF_ABSENT(String flag) {
	  if (!DebugFlags.containsKey(flag)) {
	    // alter the flags even in insane build to allow explicit tracing
	    if (!DEBUGDEBUG.equals(flag)) {
	      if (TRACE_ON(DEBUGDEBUG)) {
	        DEBUG_PRINT(DEBUGDEBUG, "TRACE_SET_IF_ABSENT: Debug flag " + flag);
	      }
	    }
	    DebugFlags.put(flag, Boolean.TRUE);
	  }
	}

        /**
	 * This is different from DEBUG_ON as it doesn't check for .DEBUG (i.e.
	 * sane/insane build). Flags that can be enabled in production version.
	 */
        public static final boolean TRACE_ON(String flag) {
            if (AllDebugOn) {
              return true;
            }
            else if (AllDebugOff) {
              return false;
            }
            else {
              Boolean flagValue = (Boolean)DebugFlags.get(flag);
              if (!DEBUGDEBUG.equals(flag)) {
                if (TRACE_ON(DEBUGDEBUG)) {
                  DEBUG_PRINT(DEBUGDEBUG, "TRACE_ON: Debug flag " + flag
                      + " = " + flagValue);
                }
              }
              return (flagValue != null ? flagValue.booleanValue() : false);
            }
        }

        /**
         * Returns true only if debug.false/true is explicitly set for the flag.
         */
        public static final boolean TRACE_OFF(String flag) {
            if (AllDebugOn) {
              return false;
            }
            else if (AllDebugOff) {
              return true;
            }
            else {
              Boolean flagValue = (Boolean)DebugFlags.get(flag);
              if (!DEBUGDEBUG.equals(flag)) {
                if (TRACE_ON(DEBUGDEBUG)) {
                  DEBUG_PRINT(DEBUGDEBUG, "TRACE_OFF: Debug flag " + flag
                      + " = " + flagValue);
                }
              }
              return (flagValue != null ? !flagValue.booleanValue() : false);
            }
        }

        public static final void clearFlags(boolean forBoot) {
          // don't clear DebugFlags in boot since they have already been
          // initialized and we will lose all of them
          if (!forBoot) {
            DebugFlags.clear();
          }
          isFineEnabled = false;
          isFinerEnabled = false;
        }

        private static final class FlagsInit {
          static {
            addDebugFlags(System.getProperty(DEBUG_FALSE), false);
            addDebugFlags(System.getProperty(DEBUG_TRUE), true);
            initCustomFlags();
          }

          static void init() {
            // nothing to do here
          }
        }

        private static void initFlags() {
          FlagsInit.init();
        }

        static void initCustomFlags() {
          TraceClientHA = TRACE_ON(TRACE_CLIENT_HA);
          TraceClientConn = TRACE_ON(TRACE_CLIENT_CONN);
          TraceClientStatementMillis = TRACE_ON(TRACE_CLIENT_STMT_MS);
          TraceClientStatement = TraceClientStatementMillis ||
              TRACE_ON(TRACE_CLIENT_STMT);
          TraceClientStatementHA = (TraceClientHA || TraceClientStatement);
          TraceMemoryLeak = TRACE_ON(TRACE_MEMORY_LEAK);
          TraceCache = TRACE_ON(TRACE_CACHE);
          TraceSingleHop = TRACE_ON(TRACE_SINGLE_HOP);
          if (TraceClientStatement) {
            sqlIdMap = new ConcurrentHashMap<Object, Long>(100);
            connIdMap = new ConcurrentHashMap<Object, Long>(100);
            threadIdMap = new ConcurrentHashMap<String, String>(100);
            final Thread hook = new Thread(new Runnable() {
              public void run() {
                if (compactLogCacheSize.get() > 0) {
                  flushCompactCache(false, null);
                }
              }
            });
            Runtime.getRuntime().addShutdownHook(hook);
            compactLogShutdownHook = hook;
          }
          else {
            sqlIdMap = null;
            connIdMap = null;
            threadIdMap = null;
            final Thread hook = compactLogShutdownHook;
            if (hook != null) {
              try {
                Runtime.getRuntime().removeShutdownHook(hook);
              } catch (Exception e) {
                // ignore exceptions at this point
              }
            }
          }
        }

        public static String getStackTrace(Throwable t) {
          return getStackTrace(t, new StringBuilder());
        }

        public static String getStackTrace(final Throwable t,
            final StringBuilder sb) {
          ClientSharedUtils.getStackTrace(t, sb, lineSeparator);
          return sb.toString();
        }

        /*
        /**
         * The format string used to format the timestamp of log messages.
         * Keep this the same as GFE's LogWriterImpl.FORMAT.
         *
        public final static String TIME_FORMAT = "yyyy/MM/dd HH:mm:ss.SSS z";

        private final static SimpleDateFormat timeFormatter =
          new SimpleDateFormat(TIME_FORMAT);

        /**
         * Static instance of current date so don't have to create new one
         * everytime. In any case we need sync on {@link #timeFormatter} so we
         * can put this into sync block too.
         *
        private final static Date currentDate = new Date();
        */

        /** the system line separator */
        public static final String lineSeparator =
            ClientSharedUtils.lineSeparator;

        /** The level string used for GemFireXD debug logging. */
        public static final String DEBUG_LEVEL_STRING = "TRACE";

        /** The name of java.util.logging.Logger for GemFireXD. */
        public static final String LOGGER_NAME = "com.pivotal.gemfirexd";

        public static String clientGfxdLogFile;

        /** Default maximum number of lines output for help at a time. */
        public static final int DEFAULT_MAX_OUT_LINES = 23;

        public static StringBuilder formatHeader(String level,
            String connectionName) {
          final StringBuilder sb = new StringBuilder(lineSeparator);
          final Thread thread = Thread.currentThread();
          final long threadId = thread.getId();
          final long millis = System.currentTimeMillis();
          sb.append('[');
          if (level != null) {
            sb.append(level);
          }
          else {
            sb.append(DEBUG_LEVEL_STRING);
          }
          sb.append(' ');
          ClientSharedUtils.formatDate(millis, sb);
          sb.append(" GFXD:").append(connectionName).append(" <")
              .append(thread.getName()).append("> tid=0x")
              .append(Long.toHexString(threadId)).append("] ");
          return sb;
        }

        public static StringBuilder formatMessage(String level,
            String connectionName, String message, Throwable t) {
          final StringBuilder sb = formatHeader(level, connectionName);
          sb.append(message);
          if (t != null) {
            sb.append(lineSeparator);
            ClientSharedUtils.getStackTrace(t, sb, lineSeparator);
          }
          return sb;
        }

        public static void DEBUG_PRINT(String flag, String message,
            Throwable t) {
            DEBUG_PRINT(flag, message, t, GET_DEBUG_STREAM());
        }
      
        public static void DEBUG_PRINT(String flag, String message, Throwable t,
            java.io.PrintWriter pw) {
          
          if(pw == null) {
            pw = GET_DEBUG_STREAM();
          }

          if (compactLogCacheSize.get() > 0) {
            flushCompactCache(false, null);
          }

          if (pw instanceof GfxdHeaderPrintWriter) {
            ((GfxdHeaderPrintWriter)pw).put(flag, message, t);
          }
          else {
            pw.println(formatMessage(null, flag, message, t).toString());
            pw.flush();
          }
        }

        public static void addDebugFlags(String flags, boolean set) {
          if (flags == null) {
            return;
          }
          final StringTokenizer st = new StringTokenizer(flags, ",");
          String flag;
          while (st.hasMoreTokens()) {
            flag = st.nextToken();
            if (set) {
              DEBUG_SET(flag, false);
            }
            else {
              DEBUG_CLEAR(flag, false);
            }
          }
        }

        public interface PrintWriterFactory {
          java.io.PrintWriter newPrintWriter(String file, boolean appendToFile);
        }

        public static synchronized void SET_DEBUG_STREAM(String gfxdLogFile,
            PrintWriterFactory pwFact) {
          boolean newStreamCreated = false;
          if (debugStream != DEFAULT_WRITER && clientGfxdLogFile == null) {
            // this is the case of boot already done by embedded
            // driver or explicitly set for a connection, so ignore
            return;
          }
          if (gfxdLogFile.equals(clientGfxdLogFile)) {
            // in this case append to existing log-file
            if (debugStream == DEFAULT_WRITER) {
              debugStream = pwFact.newPrintWriter(gfxdLogFile, true);
              newStreamCreated = true;
            }
          }
          else {
            final boolean doAppend;
            if ((doAppend = (debugStream != DEFAULT_WRITER))) {
              try {
                debugStream.close();
              } catch (Exception ex) {
                // ignored
              }
            }
            debugStream = pwFact.newPrintWriter(gfxdLogFile, doAppend);
            clientGfxdLogFile = gfxdLogFile;
            newStreamCreated = true;
          }
          if (newStreamCreated) {
            if (debugStream instanceof GfxdHeaderPrintWriter) {
              isFineEnabled = ((GfxdHeaderPrintWriter)debugStream)
                  .isFineEnabled();
              isFinerEnabled = ((GfxdHeaderPrintWriter)debugStream)
                  .isFinerEnabled();
            }
            // also initialize the debug flags if not done
            initFlags();
          }
        }

        public static synchronized void SET_DEBUG_STREAM_IFNULL(
            java.io.PrintWriter pw) {
          if (debugStream == DEFAULT_WRITER) {
            debugStream = pw;
            clientGfxdLogFile = null;
            if (debugStream instanceof GfxdHeaderPrintWriter) {
              isFineEnabled = ((GfxdHeaderPrintWriter)debugStream)
                  .isFineEnabled();
              isFinerEnabled = ((GfxdHeaderPrintWriter)debugStream)
                  .isFinerEnabled();
            }
            // also initialize the debug flags if not done
            initFlags();
          }
        }

        public static synchronized void CLEAR_DEBUG_STREAM(
            java.io.PrintWriter comparePW) {
          if (comparePW == null || comparePW == debugStream) {
            debugStream = DEFAULT_WRITER;
            clientGfxdLogFile = null;
            isFineEnabled = false;
            isFinerEnabled = false;
          }
        }

        public static final void DEBUG_SET(String flag, boolean refreshCached) {
          // alter the flags even in insane build to allow explicit tracing
          if (!DEBUGDEBUG.equals(flag)) {
            if (TRACE_ON(DEBUGDEBUG))
              DEBUG_PRINT(DEBUGDEBUG, "DEBUG_SET: Debug flag " + flag);
          }
          DebugFlags.put(flag, Boolean.TRUE);
          // refresh statics used by GemFireXD if required
          if (refreshCached) {
            final PrintWriter debugStream = GET_DEBUG_STREAM();
            if (debugStream instanceof GfxdHeaderPrintWriter) {
              ((GfxdHeaderPrintWriter)debugStream).refreshDebugFlag(flag, true);
            }
            initCustomFlags();
          }
        }

        public static final void DEBUG_CLEAR(String flag, boolean refreshCached) {
          // alter the flags even in insane build to allow explicit tracing
          if (!DEBUGDEBUG.equals(flag)) {
            if (TRACE_ON(DEBUGDEBUG))
              DEBUG_PRINT(DEBUGDEBUG, "DEBUG_CLEAR: Debug flag " + flag);
          }
          DebugFlags.put(flag, Boolean.FALSE);
          // refresh statics used by GemFireXD if required
          if (refreshCached) {
            final PrintWriter debugStream = GET_DEBUG_STREAM();
            if (debugStream instanceof GfxdHeaderPrintWriter) {
              ((GfxdHeaderPrintWriter)debugStream).refreshDebugFlag(flag, false);
            }
            initCustomFlags();
          }
        }

        public static void printPagedOutput(final PrintStream out,
            final InputStream in, final String output, final int maxLines,
            final String continuePrompt, final boolean noecho) {
          int lineStart = 0;
          int numLines = 0;
          char c;
          for (int index = 0; index < output.length(); index++) {
            c = output.charAt(index);
            if (c == '\n') {
              // got end of line
              out.println(output.substring(lineStart, index));
              out.flush();
              lineStart = index + 1;
              if (++numLines >= maxLines && lineStart < output.length()) {
                // wait for user input before further output
                out.print(continuePrompt);
                out.flush();
                String chars = ClientSharedUtils.readChars(in, noecho);
                out.println();
                if (chars != null && chars.length() > 0
                    && (chars.charAt(0) == 'q' || chars.charAt(0) == 'Q')) {
                  out.flush();
                  return;
                }
                numLines = 0;
              }
            }
          }
          // print any remaining string
          if (lineStart < output.length()) {
            out.println(output.substring(lineStart));
          }
          out.flush();
        }

        // below two maps are used in compact client logging to use
        // IDs instead of SQL/thread strings
        private static volatile ConcurrentHashMap<Object, Long> sqlIdMap;
        private static volatile ConcurrentHashMap<Object, Long> connIdMap;
        private static final AtomicLong currSeqId = new AtomicLong(0);
        private static volatile ConcurrentHashMap<String, String> threadIdMap;
        private static final int MAX_COMPACT_LOG_CACHE_SIZE = Integer
            .getInteger("gemfirexd.compact-log-cache-lines", 1000);
        private static final Object[] compactLogCache =
            new Object[MAX_COMPACT_LOG_CACHE_SIZE + 4];
        private static final AtomicInteger compactLogCacheSize =
            new AtomicInteger(0);
        private static final StringBuilder compactLogBuilder =
            new StringBuilder(MAX_COMPACT_LOG_CACHE_SIZE
                * 60 /* some estimate of a compact line size */);
        private static volatile Thread compactLogShutdownHook;

        private static final class CompactLogLine {
          final String tid;
          final String opId;
          final Object sql;
          final long sqlId;
          final long connId;
          final ByteBuffer sessionToken;
          final long nanoTime;
          final long milliTime;
          final Throwable t;

          CompactLogLine(String tid, String opId, Object sql, long sqlId,
              long connId, ByteBuffer sessionToken, long nanoTime,
              long milliTime, Throwable t) {
            this.tid = tid;
            this.opId = opId;
            this.sql = sql;
            this.sqlId = sqlId;
            this.connId = connId;
            this.sessionToken = sessionToken;
            this.nanoTime = nanoTime;
            this.milliTime = milliTime;
            this.t = t;
          }

          void toString(StringBuilder sb) {
            sb.append(this.tid).append(' ').append(this.opId).append(' ');
            final Object sql = this.sql;
            final long sqlId;
            if (sql != null) {
              Class<?> cls = sql.getClass();
              if (cls == Integer.class) {
                sb.append(((Integer)sql).intValue()).append(' ');
              }
              else if (cls == String.class) {
                sb.append((String)sql).append(' ');
              }
              else {
                sb.append(sql.toString()).append(' ');
              }
            }
            else if ((sqlId = this.sqlId) != snappydataConstants.INVALID_ID) {
              sb.append("ID=").append(sqlId).append(' ');
            }
            sb.append(this.connId);
            if (this.sessionToken != null
                && this.sessionToken.remaining() > 0) {
              // hex string
              sb.append('@');
              ClientSharedUtils.toHexString(this.sessionToken, sb);
            }
            sb.append(' ').append(this.nanoTime);
            if (this.milliTime > 0) {
              sb.append(' ').append(this.milliTime);
            }
            if (this.t != null) {
              sb.append(" STACK: ");
              ClientSharedUtils.getStackTrace(this.t, sb, lineSeparator);
            }
          }
        }

        static public void DEBUG_PRINT_COMPACT_LINE(Object[] line) {
          assert line.length > 2 : "atleast line should have space for timestamp & threadid";
          assert line[0] == null : "first entry should be left out for timestamp";
          assert line[1] == null : "second entry should be left out for threadid";
          
          line[0] = System.currentTimeMillis();
          // capture a long value, mostly it will hit the cached Long object for tid.
          line[1] = Long.valueOf(Thread.currentThread().getId());          
          logCompactLine(line);
        }

        static public void DEBUG_PRINT_COMPACT(String opId, String opSql,
            long connId, long nanoTime, boolean isStart, Throwable t) {
          DEBUG_PRINT_COMPACT(opId, opSql, connId, null, nanoTime, isStart, t);
        }

        static public void DEBUG_PRINT_COMPACT(String opId, final long sqlId,
            long connId, ByteBuffer sessionToken, long nanoTime,
            boolean isStart, Throwable t) {
          DEBUG_PRINT_COMPACT(opId, null, sqlId, connId, sessionToken,
              nanoTime, isStart, t);
        }

        static public void DEBUG_PRINT_COMPACT(String opId, final String opSql,
            long connId, ByteBuffer sessionToken, long nanoTime,
            boolean isStart, Throwable t) {
          DEBUG_PRINT_COMPACT(opId, opSql, snappydataConstants.INVALID_ID,
              connId, sessionToken, nanoTime, isStart, t);
        }

        static public void DEBUG_PRINT_COMPACT(String opId, final String opSql,
            long sqlId, long connId, ByteBuffer sessionToken, long nanoTime,
            boolean isStart, Throwable t) {
          final Object sql;
          final Thread currentThread = Thread.currentThread();
          final String tname = currentThread.getName();
          String tid = Long.toHexString(currentThread.getId());
          final CompactLogLine logLine;
          final ConcurrentHashMap<String, String> threadIds = threadIdMap;
          if (opSql != null) {
            final ConcurrentHashMap<Object, Long> opSqlIds = sqlIdMap;
            if (opSqlIds != null) {
              Long id = opSqlIds.get(opSql);
              if (id == null) {
                id = Long.valueOf(currSeqId.incrementAndGet());
                Long oldId = opSqlIds.putIfAbsent(opSql, id);
                if (oldId != null) {
                  id = oldId;
                }
                else {
                  // quote the string since it might go across lines
                  logCompactLine(new Object[] { "SQL",
                      SharedUtils.quoteString(opSql, '"'), id });
                }
              }
              sql = id;
            }
            else {
              sql = opSql;
            }
          }
          else {
            sql = null;
          }
          if (threadIds != null) {
            if (!threadIds.containsKey(tname)) {
              threadIds.putIfAbsent(tname, tid);
              logCompactLine(new Object[] { "THREAD", tname, tid });
            }
          }
          else {
            tid = tname + ':' + tid;
          }
          if (isStart && TraceClientStatementMillis) {
            logLine = new CompactLogLine(tid, opId, sql, sqlId, connId,
                sessionToken, nanoTime, System.currentTimeMillis(), t);
          }
          else {
            logLine = new CompactLogLine(tid, opId, sql, sqlId, connId,
                sessionToken, nanoTime, -1, t);
          }
          logCompactLine(logLine);
        }

        static public long getConnectionId(Object conn) {
          if (conn != null) {
            final ConcurrentHashMap<Object, Long> connIds = connIdMap;
            if (connIds != null) {
              Long connId = connIds.get(conn);
              if (connId == null) {
                connId = Long.valueOf(currSeqId.incrementAndGet());
                Long oldConnId = connIds.putIfAbsent(conn, connId);
                if (oldConnId != null) {
                  connId = oldConnId;
                }
                else {
                  logCompactLine(new Object[] { "CONN", conn.hashCode(), connId });
                }
              }
              return connId.longValue();
            }
            else {
              return conn.hashCode();
            }
          }
          else {
            return 0;
          }
        }

        private static final void logCompactLine(final Object line) {
          while (true) {
            int size = compactLogCacheSize.get();
            if (size < MAX_COMPACT_LOG_CACHE_SIZE) {
              if (compactLogCacheSize.compareAndSet(size, size + 1)) {
                compactLogCache[size] = line;
                return;
              }
            }
            else {
              if (flushCompactCache(true, line)) {
                return;
              }
            }
          }
        }

        private static boolean flushCompactCache(final boolean checkSize,
            final Object line) {
          Object[] cache = null;
          boolean doFlush = false;
          synchronized (compactLogCache) {
            int size = compactLogCacheSize.get();
            if ((!checkSize || size >= MAX_COMPACT_LOG_CACHE_SIZE)) {
              // flush the cache
              if (line != null) {
                cache = new Object[size + 1];
                cache[size] = line;
              }
              else {
                cache = new Object[size];
              }
              System.arraycopy(compactLogCache, 0, cache, 0, size);
              if (compactLogCacheSize.compareAndSet(size, 0)) {
                doFlush = true;
              }
            }
          }
          if (doFlush) {
            // now block only those threads that have determined doFlush
            if (cache != null && cache.length > 0) {
              synchronized (compactLogCache) {
                for (Object l : cache) {
                  if (l instanceof CompactLogLine) {
                    ((CompactLogLine)l).toString(compactLogBuilder);
                  }
                  else if (l instanceof Object[]) {
                    final Object[] lineArray = (Object[])l;
                    int i = 0;
                    for (final int z = lineArray.length - 1/*handle last element specially, so -1*/; i < z; i++) {
                      final Object o = lineArray[i];
                      switch (i) {
                        case 0:
                          if (o instanceof Long) {
                            ClientSharedUtils.formatDate((Long)o,
                                compactLogBuilder);
                          }
                          else {
                            compactLogBuilder.append(' ');
                            ClientSharedUtils.objectStringNonRecursive(
                                o, compactLogBuilder);
                          }
                          break;
                        case 1:
                          if (o instanceof Long) {
                            compactLogBuilder.append(' ');
                            longToHexString((Long)o, compactLogBuilder);
                          }
                          else {
                            compactLogBuilder.append(' ');
                            ClientSharedUtils.objectStringNonRecursive(o, compactLogBuilder);
                          }
                          break;
                        default:
                          compactLogBuilder.append(' ');
                          ClientSharedUtils.objectStringNonRecursive(o, compactLogBuilder);
                          break;
                      }
                    }
                    
                    if (lineArray[i] instanceof Throwable) {
                      compactLogBuilder.append(" STACK: ");
                      ClientSharedUtils.getStackTrace((Throwable)lineArray[i],
                          compactLogBuilder, lineSeparator);
                    }
                    else {
                      compactLogBuilder.append(' ').append(lineArray[i]);
                    }
                  }
                  else {
                    compactLogBuilder.append(l);
                  }
                  compactLogBuilder.append(lineSeparator);
                }
                final java.io.PrintWriter pw = GET_DEBUG_STREAM();
                final boolean isServer = (pw instanceof GfxdHeaderPrintWriter);
                if (isServer) {
                  ((GfxdHeaderPrintWriter)pw).put("TRACE:COMPACT", compactLogBuilder.toString(), null);                  
                }
                else {
                  pw.print(compactLogBuilder.toString());
                  pw.flush();
                }
                
                compactLogBuilder.setLength(0);
                
                if(isServer) {
                  compactLogBuilder.append(lineSeparator);                  
                }
                return true;
              }
            }
          }
          return false;
        }

        /**
         * All possible chars for representing a number as a String
         */
        final static char[] digits = {
            '0' , '1' , '2' , '3' , '4' , '5' ,
            '6' , '7' , '8' , '9' , 'a' , 'b' ,
            'c' , 'd' , 'e' , 'f' , 'g' , 'h' ,
            'i' , 'j' , 'k' , 'l' , 'm' , 'n' ,
            'o' , 'p' , 'q' , 'r' , 's' , 't' ,
            'u' , 'v' , 'w' , 'x' , 'y' , 'z'
        };
        
        static final ThreadLocal<char[]> longBuffer = new ThreadLocal<char[]>();
        static final void longToHexString(long i, StringBuilder output) {
          int shift = 4;
          int charPos = 64;
          int radix = 1 << shift;
          long mask = radix - 1;
          char[] buf = longBuffer.get();
          if (buf == null) {
            buf = new char[64];
            longBuffer.set(buf);
          }
          do {
              buf[--charPos] = digits[(int)(i & mask)];
              i >>>= shift;
          } while (i != 0);
          
          output.append("0x");
          output.append(buf, charPos, (64 - charPos));
        }
        
// GemStone changes END
	/**
	 * Set the named debug flag to true.
	 *
	 * <p>
     * Calls to this method should be surrounded with
	 *     if (SanityManager.DEBUG) {
	 *     }
	 * so that they can be compiled out completely.
	 *
	 * @param flag	The name of the debug flag to set to true
	 */
	public static final void DEBUG_SET(String flag) {
// GemStone changes BEGIN
	  DEBUG_SET(flag, true);
	  /* (original code)
		if (DEBUG) {
			if (! DEBUGDEBUG.equals(flag)) {
				if (DEBUG_ON(DEBUGDEBUG))
					DEBUG_PRINT(DEBUGDEBUG, "DEBUG_SET: Debug flag " + flag);
			}

			DebugFlags.put(flag, Boolean.TRUE);
		}
	  */
// GemStone changes END
	}

	/**
	 * Set the named debug flag to false.
	 *
	 * <p>
     * Calls to this method should be surrounded with
	 *     if (SanityManager.DEBUG) {
	 *     }
	 * so that they can be compiled out completely.
	 *
	 * @param flag	The name of the debug flag to set to false
	 */
	public static final void DEBUG_CLEAR(String flag) {
// GemStone changes BEGIN
	  DEBUG_CLEAR(flag, true);
	  /* (original code)
		if (DEBUG) {
			if (! DEBUGDEBUG.equals(flag)) {
				if (DEBUG_ON(DEBUGDEBUG))
					DEBUG_PRINT(DEBUGDEBUG, "DEBUG_CLEAR: Debug flag " + flag);
			}

			DebugFlags.put(flag, Boolean.FALSE);
		}
	  */
// GemStone changes END
	}

	/**
	 * This can be used to have the SanityManager return TRUE
	 * for any DEBUG_ON check. DEBUG_CLEAR of an individual
	 * flag will appear to have no effect.
	 */
	public static final void DEBUG_ALL_ON() {
		if (DEBUG) {
			AllDebugOn = true;
			AllDebugOff = false;
		}
// GemStone changes BEGIN
		// refresh statics used by GemFireXD
		final PrintWriter debugStream = GET_DEBUG_STREAM();
		if (debugStream instanceof GfxdHeaderPrintWriter) {
		  ((GfxdHeaderPrintWriter)debugStream).refreshDebugFlag(
		      null, true);
		}
		initCustomFlags();
// GemStone changes END
	}

	/**
	 * This can be used to have the SanityManager return FALSE
	 * for any DEBUG_ON check. DEBUG_SET of an individual
	 * flag will appear to have no effect.
	 */
	public static final void DEBUG_ALL_OFF() {
		if (DEBUG) {
			AllDebugOff = true;
			AllDebugOn = false;
		}
// GemStone changes BEGIN
		// refresh statics used by GemFireXD
		final PrintWriter debugStream = GET_DEBUG_STREAM();
		if (debugStream instanceof GfxdHeaderPrintWriter) {
		  ((GfxdHeaderPrintWriter)debugStream).refreshDebugFlag(
		      null, false);
                  isFineEnabled = ((GfxdHeaderPrintWriter)debugStream)
                      .isFineEnabled();
                  isFinerEnabled = ((GfxdHeaderPrintWriter)debugStream)
                      .isFinerEnabled();
		}
		initCustomFlags();
// GemStone changes END
	}

	//
	// class implementation
	//

// GemStone changes BEGIN
	public static synchronized void SET_DEBUG_STREAM(
	    java.io.PrintWriter pw) {
	  debugStream = pw;
	  clientGfxdLogFile = null;
          if (debugStream instanceof GfxdHeaderPrintWriter) {
            isFineEnabled = ((GfxdHeaderPrintWriter)debugStream)
                .isFineEnabled();
            isFinerEnabled = ((GfxdHeaderPrintWriter)debugStream)
                .isFinerEnabled();
          }
	  // also initialize the debug flags if not done
	  initFlags();
	  /* (original code)
	static public void SET_DEBUG_STREAM(java.io.PrintWriter pw) {
		debugStream = pw;
	  */
// GemStone changes END
	}

	static public java.io.PrintWriter GET_DEBUG_STREAM() {
		return debugStream;
	}

	static private void showTrace(AssertFailure af) {
		af.printStackTrace();
		java.io.PrintWriter assertStream = GET_DEBUG_STREAM();

		assertStream.println("Assertion trace:");
		af.printStackTrace(assertStream);
		assertStream.flush();
	}

	static public void showTrace(Throwable t) {
		java.io.PrintWriter assertStream = GET_DEBUG_STREAM();

		assertStream.println("Exception trace: ");
		t.printStackTrace(assertStream);
	}

	/**
	 * The DEBUG_PRINT calls provides a convenient way to print debug
	 * information to the db2j.LOG file,  The message includes a header
	 *<p>
	 *	DEBUG <flag> OUTPUT: 
	 * before the message
	 *<p>
	 * If the debugStream stream cannot be found, the message is printed to
	 * System.out.
	 *
	 */
	static public void DEBUG_PRINT(String flag, String message) {
// GemStone changes BEGIN
	  DEBUG_PRINT(flag, message, null);
	  /* (original code)
		java.io.PrintWriter debugStream = GET_DEBUG_STREAM();
		debugStream.println("DEBUG "+flag+" OUTPUT: " + message);
		debugStream.flush();
	  */
// GemStone changes END
	}

	public static void NOTREACHED() {
		THROWASSERT("code should not be reached");
	}
}

