/*

   Derby - Class com.pivotal.gemfirexd.internal.tools.JDBCDisplayUtil

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

package com.pivotal.gemfirexd.internal.tools;

import java.io.PrintStream;
import java.io.PrintWriter;

import java.security.AccessController;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Vector;


import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.PagedLocalizedOutput;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.RedirectedLocalizedOutput;
import com.pivotal.gemfirexd.internal.impl.tools.ij.ijException;
import com.pivotal.gemfirexd.internal.shared.common.StopWatch;
import jline.console.ConsoleReader;

/**
	
	This class contains utility methods for displaying JDBC objects and results.
	
	<p>
	All of the methods are static. The output stream
	to write to is always passed in, along with the
	JDBC objects to display.

 */
public class JDBCDisplayUtil {

	// used to control display
	static final private int MINWIDTH = 4;
	static private int maxWidth = 128;
    static public boolean showSelectCount = false;
	static public boolean showSelectRows = true; //GemStone Addition

    static {
        // initialize the locale support functions to default value of JVM 
        LocalizedResource.getInstance();
    }

	//-----------------------------------------------------------------
	// Methods for displaying and checking errors

	/**
		Print information about the exception to the given PrintWriter.
		For non-SQLExceptions, does a stack trace. For SQLExceptions,
		print a standard error message and walk the list, if any.

		@param out the place to write to
		@param e the exception to display
	 */
	static public void ShowException(PrintWriter out, Throwable e) {
		if (e == null) return;

		if (e instanceof SQLException)
			ShowSQLException(out, (SQLException)e);
		else
			e.printStackTrace(out);
	}

	/**
		Print information about the SQL exception to the given PrintWriter.
		Walk the list of exceptions, if any.

		@param out the place to write to
		@param e the exception to display
	 */
	static public void ShowSQLException(PrintWriter out, SQLException e) {
		String errorCode;

		if (getSystemBoolean("gfxd.showErrorCode")) {
			errorCode = LocalizedResource.getMessage("UT_Error0", LocalizedResource.getNumber(e.getErrorCode()));
		}
		else {
			errorCode = "";
		}

		while (e!=null) {
			String p1 = mapNull(e.getSQLState(),LocalizedResource.getMessage("UT_NoSqlst"));
			String p2 = mapNull(e.getMessage(),LocalizedResource.getMessage("UT_NoMessa"));
			out.println(LocalizedResource.getMessage("UT_Error012", p1, p2,errorCode));
			doTrace(out, e);
			e=e.getNextException();
		}
	}

	/**
		Print information about the SQL warnings for the connection
		to the given PrintWriter.
		Walks the list of exceptions, if any.

		@param out the place to write to
		@param theConnection the connection that may have warnings.
	 */
	static public void ShowWarnings(PrintWriter out, Connection theConnection) {
	    try {
		// GET CONNECTION WARNINGS
		SQLWarning warning = null;

		if (theConnection != null) {
			ShowWarnings(out, theConnection.getWarnings());
		}

		if (theConnection != null) {
			theConnection.clearWarnings();
		}
	    } catch (SQLException e) {
			ShowSQLException(out, e);
	    }
	} // ShowWarnings

	/**
		@param out the place to write to
		@param warning the SQLWarning
	*/
	static public int /* GemStoneChange void */ ShowWarnings(PrintWriter out, SQLWarning warning) {
	  int numWarnings = 0; // GemStoneAddition
		while (warning != null) {
		  numWarnings++; // GemStoneAddition
			String p1 = mapNull(warning.getSQLState(),LocalizedResource.getMessage("UT_NoSqlst_7"));
			String p2 = mapNull(warning.getMessage(),LocalizedResource.getMessage("UT_NoMessa_8"));
			out.println(LocalizedResource.getMessage("UT_Warni01", p1, p2));
			warning = warning.getNextWarning();
		}
		return numWarnings; // GemStoneAddition
	}

	/**
		Print information about the SQL warnings for the ResultSet
		to the given PrintWriter.
		Walk the list of exceptions, if any.
	
		@param out the place to write to
		@param rs the ResultSet that may have warnings on it
	 */
	static public int /* GemStoneChange void */ ShowWarnings(PrintWriter out, ResultSet rs) {
	    int result = 0;
	    try {
		// GET RESULTSET WARNINGS
		SQLWarning warning = null;

		if (rs != null) {
			result = ShowWarnings(out, rs.getWarnings());
		}

		if (rs != null) {
			rs.clearWarnings();
		}
	    } catch (SQLException e) {
			ShowSQLException(out, e);
	    }
	    return result; // GemStoneAddition
	} // ShowResultSetWarnings

	/**
		Print information about the SQL warnings for the Statement
		to the given PrintWriter.
		Walks the list of exceptions, if any.

		@param out the place to write to
		@param s the Statement that may have warnings on it
	 */
	static public void ShowWarnings(PrintWriter out, Statement s)
	{
	    try {
		// GET STATEMENT WARNINGS
		SQLWarning warning = null;

		if (s != null) {
			ShowWarnings(out, s.getWarnings());
		}

		if (s != null) {
			s.clearWarnings();
		}
	    } catch (SQLException e) {
			ShowSQLException(out, e);
	    }
	} // ShowStatementWarnings

	//-----------------------------------------------------------------
	// Methods for displaying and checking results

	// REMIND: make this configurable...
	static final private int MAX_RETRIES = 0;

	/**
		Pretty-print the results of a statement that has been executed.
		If it is a select, gathers and prints the results.  Display
		partial results up to the first error.
		If it is not a SELECT, determine if rows were involved or not,
		and print the appropriate message.

		@param out the place to write to
		@param stmt the Statement to display
		@param conn the Connection against which the statement was executed

		@exception SQLException on JDBC access failure
	 */
	static public void DisplayResults(PrintWriter out, Statement stmt, Connection conn,
	    ConsoleReader reader /* GemStoneAddition */,
	    StopWatch timer /* GemStoneAddition */, boolean displayCount /* GemStoneAddition */)
      throws SQLException
	{
		indent_DisplayResults( out, stmt, conn, 0, null, null,
		    reader /* GemStoneAddition */,
		    timer /* GemStoneAddition */, displayCount /* GemStoneAddition */);
	}

	static private void indent_DisplayResults
	(PrintWriter out, Statement stmt, Connection conn, int indentLevel,
	 int[] displayColumns, int[] displayColumnWidths,
	 ConsoleReader reader /* GemStoneAddition */,
	 StopWatch timer /* GemStoneAddition */, boolean displayCount /* GemStoneAddition */)
		throws SQLException {

		checkNotNull(stmt, "Statement");

		ResultSet rs = stmt.getResultSet();
		if (rs != null) {
			indent_DisplayResults(out, rs, conn, indentLevel, 
								  displayColumns, displayColumnWidths,
								  reader /* GemStoneAddition */,
								  timer /* GemStoneAddition */);
			rs.close(); // let the result set go away
		}
		else {
			if (displayCount) {
			    DisplayUpdateCount(out, stmt.getUpdateCount(), indentLevel);
			}
		}

		ShowWarnings(out,stmt);
	} // DisplayResults

	/**
		@param out the place to write to
		@param count the update count to display
		@param indentLevel number of tab stops to indent line
	 */
	static void DisplayUpdateCount(PrintWriter out, int count, int indentLevel ) {
		if (count == 1) {
			indentedPrintLine( out, indentLevel, LocalizedResource.getMessage("UT_1RowInserUpdatDelet"));
		}
		else if (count >= 0) {
			indentedPrintLine( out, indentLevel, LocalizedResource.getMessage("UT_0RowsInserUpdatDelet", LocalizedResource.getNumber(count)));
		}
		else {
			indentedPrintLine( out, indentLevel, LocalizedResource.getMessage("UT_StateExecu"));
		}
	}

	/**
	    Calculates column display widths from the default widths of the
	    result set.
	 */
	static private int[] getColumnDisplayWidths(ResultSetMetaData rsmd, int[] dispColumns,
												boolean localizedOutput)
		throws SQLException {
		int count = (dispColumns == null) ? rsmd.getColumnCount() : dispColumns.length;
		int[] widths = new int[count];

		for(int i=0; i<count; i++) {
			int colnum = (dispColumns == null) ? (i + 1) : dispColumns[i];
			int dispsize = localizedOutput
				? LocalizedResource.getInstance().getColumnDisplaySize(rsmd, colnum)
                : rsmd.getColumnDisplaySize(colnum);
// GemStone changes BEGIN
			final int maxWidth = getMaxColumnWidth(
			    rsmd.getColumnLabel(colnum));
// GemStone changes END
			widths[i] = Math.min(maxWidth,
				Math.max((rsmd.isNullable(colnum) == ResultSetMetaData.columnNoNulls)?
				0 : MINWIDTH, dispsize));
		}
		return widths;
	}


    /**
       @param out the place to write to
       @param resultSets list of <code>ResultSet</code>s to display
       @param conn the connection against which the <code>ResultSet</code>s
            were retrieved
       @param displayColumns column numbers to display, <code>null</code> if all
       @param displayColumnWidths column widths, in characters, if
            <code>displayColumns</code> is specified

       @exception SQLException on JDBC access failure
    */
    static public void DisplayMultipleResults(PrintWriter out, List resultSets,
                                              Connection conn,
                                              int[] displayColumns,
                                              int[] displayColumnWidths,
                                              ConsoleReader reader /* GemStoneAddition */,
                                              StopWatch timer /* GemStoneAddition */)
        throws SQLException
    {
        indent_DisplayResults( out, resultSets, conn, 0, displayColumns,
                               displayColumnWidths,
                               reader /* GemStoneAddition */,
                               timer /* GemStoneAddition */);
    }

    /**
       @param out the place to write to
       @param rs the <code>ResultSet</code> to display
       @param conn the connection against which the <code>ResultSet</code>
            was retrieved
       @param displayColumns column numbers to display, <code>null</code> if all
       @param displayColumnWidths column widths, in characters, if
            <code>displayColumns</code> is specified

       @exception SQLException on JDBC access failure
    */
	static public void DisplayResults(PrintWriter out, ResultSet rs, Connection conn,
									  int[] displayColumns, int[] displayColumnWidths,
									  ConsoleReader reader /* GemStoneAddition */,
									  StopWatch timer /* GemStoneAddition */)
		throws SQLException
	{
		indent_DisplayResults( out, rs, conn, 0, displayColumns, 
							   displayColumnWidths,
							   reader /* GemStoneAddition */,
							   timer /* GemStoneAddition */);
	}

    static private void indent_DisplayResults
        (PrintWriter out, ResultSet rs, Connection conn, int indentLevel,
         int[] displayColumns, int[] displayColumnWidths,
         ConsoleReader reader /* GemStoneAddition */,
         StopWatch timer /* GemStoneAddition */)
        throws SQLException {
        List resultSets = new ArrayList();
        resultSets.add(rs);
        indent_DisplayResults( out, resultSets, conn, 0, displayColumns, 
                               displayColumnWidths,
                               reader /* GemStoneAddition */,
                               timer /* GemStoneAddition */);
    }

    static private void indent_DisplayResults
        (PrintWriter out, List resultSets, Connection conn, int indentLevel,
         int[] displayColumns, int[] displayColumnWidths,
         ConsoleReader reader /* GemStoneAddition */,
         StopWatch timer /* GemStoneAddition */)
        throws SQLException {

        ResultSetMetaData rsmd = null;

        //get metadata from the first ResultSet
        if (resultSets != null && resultSets.size() > 0)
            rsmd = ((ResultSet)resultSets.get(0)).getMetaData();

        checkNotNull(rsmd, "ResultSetMetaData");
        Vector nestedResults;
        int numberOfRowsSelected = 0;

        // autocommit must be off or the nested cursors
        // are closed when the outer statement completes.
        if (!conn.getAutoCommit())
            nestedResults = new Vector();
        else
            nestedResults = null;

        if(displayColumnWidths == null)
            displayColumnWidths = getColumnDisplayWidths(rsmd,
                                                         displayColumns,true);

// GemStone changes BEGIN
        if (rsmd != null) {
          adjustColumnWidths(displayColumns, displayColumnWidths,
              resultSets, rsmd, nestedResults, false, reader);
        }
        // page results for interactive sessions
        int termWidth;
        if (reader != null && (termWidth = reader.getTerminal().getWidth()) > MINWIDTH
            && pageResults && !(out instanceof RedirectedLocalizedOutput)) {
          final String continuePrompt = LocalizedResource.getMessage(
              "UTIL_GFXD_Continue_Prompt");
          out = new PagedLocalizedOutput(out, reader.getInput(), termWidth,
              reader.getTerminal().getHeight(), continuePrompt, timer);
        }
        try {
// GemStone changes END
        int len = indent_DisplayBanner(out,rsmd, indentLevel, displayColumns,
                                       displayColumnWidths);

        // When displaying rows, keep going past errors
        // unless/until the maximum # of errors is reached.
        int retry = 0;

        ResultSet rs = null;
        boolean doNext = true;
        for (int i = 0; i< resultSets.size(); i++) {
            rs = (ResultSet)resultSets.get(i);
            doNext = true;
            while (doNext){
                try {
// GemStone changes BEGIN
                  if (currentResults != null) {
                    doNext = true;
                  }
                  else
// GemStone changes END
                    doNext = rs.next();
                    if (doNext) {

                        DisplayRow(out, rs, rsmd, len, nestedResults, conn,
                                   indentLevel, displayColumns,
                                   displayColumnWidths);
                        ShowWarnings(out, rs);
                        numberOfRowsSelected++;
                    }
                } catch (SQLException e) {
                    // REVISIT: might want to check the exception
                    // and for some, not bother with the retry.
                    if (++retry > MAX_RETRIES)
                        throw e;
                    else
                        ShowSQLException(out, e);
                }
            }
        }
        if (showSelectCount == true) {
            if (numberOfRowsSelected == 1) {
                out.println();
                indentedPrintLine(out, indentLevel,
                                  LocalizedResource.getMessage("UT_1RowSelec"));
            } else if (numberOfRowsSelected >= 0) {
                out.println();
                indentedPrintLine(out, indentLevel,
                        LocalizedResource.getMessage("UT_0RowsSelec",
                            LocalizedResource.getNumber(numberOfRowsSelected)));
            }
        }

        DisplayNestedResults(out, nestedResults, conn, indentLevel,
            reader /* GemStoneAddition */, timer /* GemStoneAddition */);
        nestedResults = null;
// GemStone changes BEGIN
        } catch (IllegalStateException ise) {
          // indicates force end of output by user so just ignore
        }
// GemStone changes END
    }


	/**
		@param out the place to write to
		@param nr the vector of results
		@param conn the Connection against which the ResultSet was retrieved
		@param indentLevel number of tab stops to indent line

		@exception SQLException thrown on access error
	 */
	static private void DisplayNestedResults(PrintWriter out, Vector nr, Connection conn, int indentLevel,
	    ConsoleReader reader /* GemStoneAddition */,
	    StopWatch timer /* GemStoneAddition */)
		throws SQLException {

		if (nr == null) return;

		String b=LocalizedResource.getMessage("UT_JDBCDisplayUtil_16");
		String oldString="0";

		for (int i=0; i < nr.size(); i++) {
			LocalizedResource.OutputWriter().println();

			//just too clever to get the extra +s
			String t = Integer.toString(i);
			if (t.length() > oldString.length()) {
				oldString = t;
				b=b+LocalizedResource.getMessage("UT_JDBCDisplayUtil_17");
			}

			LocalizedResource.OutputWriter().println(b);
			LocalizedResource.OutputWriter().println(LocalizedResource.getMessage("UT_Resul0", LocalizedResource.getNumber(i)));
			LocalizedResource.OutputWriter().println(b);
			indent_DisplayResults(out, (ResultSet) nr.elementAt(i), conn,
								  indentLevel, null, null,
								  reader /* GemStoneAddition */,
								  timer /* GemStoneAddition */);
		}
	}

	/**
		Fetch the next row of the result set, and if it
		exists format and display a banner and the row.

		@param out the place to write to
		@param rs the ResultSet in use
		@param conn the Connection against which the ResultSet was retrieved

		@exception SQLException on JDBC access failure
	 */
	static public void DisplayNextRow(PrintWriter out, ResultSet rs, Connection conn,
	    ConsoleReader reader /* GemStoneAddition */,
	    StopWatch timer /* GemStoneAddition */)
		throws SQLException
	{
		indent_DisplayNextRow( out, rs, conn, 0, null, (rs == null) ? null
							   : getColumnDisplayWidths(rs.getMetaData(), null, true),
							   reader /* GemStoneAddition */,
							   timer /* GemStoneAddition */);
	}

	static private void indent_DisplayNextRow(PrintWriter out, ResultSet rs, Connection conn, int indentLevel,
											  int[] displayColumns, int[] displayColumnWidths,
											  ConsoleReader reader /* GemStoneAddition */,
											  StopWatch timer /* GemStoneAddition */)
		throws SQLException {

		Vector nestedResults;

		// autocommit must be off or the nested cursors
		// are closed when the outer statement completes.
		if (!conn.getAutoCommit())
			nestedResults = new Vector();
		else
			nestedResults = null;

		checkNotNull(rs, "ResultSet");

		ResultSetMetaData rsmd = rs.getMetaData();
		checkNotNull(rsmd, "ResultSetMetaData");

		// Only print stuff out if there is a row to be had.
		if (rs.next()) {
			int rowLen = indent_DisplayBanner(out, rsmd, indentLevel, displayColumns, displayColumnWidths);
    		DisplayRow(out, rs, rsmd, rowLen, nestedResults, conn, indentLevel,
					   null, null );
		}
		else {
			indentedPrintLine( out, indentLevel, LocalizedResource.getMessage("UT_NoCurreRow"));
		}

		ShowWarnings(out, rs);

		DisplayNestedResults(out, nestedResults, conn, indentLevel,
		    reader /* GemStoneAddition */,
		    timer /* GemStoneAddition */);
		nestedResults = null;

	} // DisplayNextRow

	/**
		Display the current row of the result set along with
		a banner. Assume the result set is on a row.

		@param out the place to write to
		@param rs the ResultSet in use
		@param conn the Connection against which the ResultSet was retrieved

		@exception SQLException on JDBC access failure
	 */
	static public void DisplayCurrentRow(PrintWriter out, ResultSet rs, Connection conn,
	    ConsoleReader reader /* GemStoneAddition */,
	    StopWatch timer /* GemStoneAddition */)
		throws SQLException
	{
		indent_DisplayCurrentRow( out, rs, conn, 0, null, (rs == null) ? null
								  : getColumnDisplayWidths(rs.getMetaData(), null, true),
								  reader /* GemStoneAddition */,
								  timer /* GemStoneAddition */);
	}

	static private void indent_DisplayCurrentRow(PrintWriter out, ResultSet rs, Connection conn, 
												 int indentLevel, int[] displayColumns, int[] displayColumnWidths,
												 ConsoleReader reader /* GemStoneAddition */,
												 StopWatch timer /* GemStoneAddition */)
		throws SQLException {

		Vector nestedResults;

		if (rs == null) {
			indentedPrintLine( out, indentLevel, LocalizedResource.getMessage("UT_NoCurreRow_19"));
			return;
		}

		// autocommit must be off or the nested cursors
		// are closed when the outer statement completes.
		if (!conn.getAutoCommit())
			nestedResults = new Vector();
		else
			nestedResults = null;

		ResultSetMetaData rsmd = rs.getMetaData();
		checkNotNull(rsmd, "ResultSetMetaData");

// GemStone changes BEGIN
		ArrayList results = new ArrayList(1);
		results.add(rs);
		adjustColumnWidths(displayColumns, displayColumnWidths,
		    results, rsmd, nestedResults, true, reader);
// GemStone changes END
		int rowLen = indent_DisplayBanner(out, rsmd, indentLevel, displayColumns, displayColumnWidths);
   		DisplayRow(out, rs, rsmd, rowLen, nestedResults, conn, indentLevel,
				   displayColumns, displayColumnWidths );

		ShowWarnings(out, rs);

		DisplayNestedResults(out, nestedResults, conn, indentLevel,
		    reader /* GemStoneAddition */,
		    timer /* GemStoneAddition */);
		nestedResults = null;

	} // DisplayNextRow

	/**
		Print a banner containing the column labels separated with '|'s
		and a line of '-'s.  Each field is as wide as the display
		width reported by the metadata.

		@param out the place to write to
		@param rsmd the ResultSetMetaData to use

		@exception SQLException on JDBC access failure
	 */
	static public int DisplayBanner(PrintWriter out, ResultSetMetaData rsmd )
		throws SQLException
	{
		return indent_DisplayBanner( out, rsmd, 0, null, 
									 getColumnDisplayWidths(rsmd, null, true) );
	}

	static private int indent_DisplayBanner(PrintWriter out, ResultSetMetaData rsmd, int indentLevel,
											int[] displayColumns, int[] displayColumnWidths )
		throws SQLException	{

		StringBuilder buf = new StringBuilder();

		int numCols = displayColumnWidths.length;
		int rowLen;

		// do some precalculation so the buffer is allocated only once
		// buffer is twice as long as the display length plus one for a newline
		rowLen = (numCols - 1); // for the column separators
		if ( rowLen < 0 ) {
		  return 0;
		}
		for (int i=1; i <= numCols; i++)
			rowLen += displayColumnWidths[i-1];
		buf.ensureCapacity(rowLen);

		// get column header info
		// truncate it to the column display width
		// add a bar between each item.
		for (int i=1; i <= numCols; i++) {
			int colnum = displayColumns==null ? i : displayColumns[i-1];

			if (i>1)
				buf.append('|');

			String s = rsmd.getColumnLabel(colnum);

			int w = displayColumnWidths[i-1];

			if (s.length() < w) {
				
				buf.append(s);

				// try to paste on big chunks of space at a time.
				int k = w - s.length();
				for (; k >= 64; k -= 64)
					buf.append(
          "                                                                ");
				for (; k >= 16; k -= 16)
					buf.append("                ");
				for (; k >= 4; k -= 4)
					buf.append("    ");
				for (; k > 0; k--)
					buf.append(' ');
			}
			else if (s.length() > w)  {
				if (w > 1) 
					buf.append(s.substring(0,w-1));
				if (w > 0) 
					buf.append('&');
			}
			else {
				buf.append(s);
			}
		}

		buf.setLength(Math.min(rowLen, 1024));
		indentedPrintLine( out, indentLevel, buf);

		// now print a row of '-'s
		for (int i=0; i<Math.min(rowLen, 1024); i++)
			buf.setCharAt(i, '-');
		indentedPrintLine( out, indentLevel, buf);

		buf = null;

		return rowLen;
	} // DisplayBanner

	/**
		Print one row of a result set, padding each field to the
		display width and separating them with '|'s

		@param out the place to write to
		@param rs the ResultSet to use
		@param rsmd the ResultSetMetaData to use
		@param rowLen
		@param nestedResults
		@param conn
		@param indentLevel number of tab stops to indent line
	    @param displayColumns A list of column numbers to display
	    @param displayColumnWidths If displayColumns is set, the width of
								columns to display, in characters.

		@exception SQLException thrown on JDBC access failure
	 */
	static private int /* GemStoneChange void */ DisplayRow(PrintWriter out, ResultSet rs, ResultSetMetaData rsmd, int rowLen, Vector nestedResults, Connection conn, int indentLevel,
								   int[] displayColumns, int[] displayColumnWidths )
		throws SQLException
	{
		if (showSelectRows == false) {
			return 0;
		}

		StringBuilder buf = new StringBuilder();
		buf.ensureCapacity(rowLen);

		int numCols = displayColumnWidths.length;
		int i;

// GemStone changes BEGIN
		String[] row = null;
		if (currentResults != null) {
		  row = currentResults.removeFirst();
		  if (currentResults.size() == 0) {
		    currentResults = null;
		  }
		}
// GemStone changes END
		// get column header info
		// truncate it to the column display width
		// add a bar between each item.
		for (i=1; i <= numCols; i++){
			int colnum = displayColumns==null ? i : displayColumns[i-1];
			if (i>1)
				buf.append('|');

			String s;
// GemStone changes BEGIN
			if (row != null) {
			  s = row[i - 1];
			}
			else {
			  s = getColumnDisplayString(rs, rsmd, colnum,
			      nestedResults);
			}
			/* (original code)
			switch (rsmd.getColumnType(colnum)) {
			default:
				s = LocalizedResource.getInstance().getLocalizedString(rs, rsmd, colnum );
				break;
			case Types.JAVA_OBJECT:
			case Types.OTHER:
			{
				Object o = rs.getObject(colnum);
				if (o == null) { s = "NULL"; }
				else if (o instanceof ResultSet && nestedResults != null)
				{
					s = LocalizedResource.getMessage("UT_Resul0_20", LocalizedResource.getNumber(nestedResults.size()));
					nestedResults.addElement(o);
				}
				else
				{
					try {
						s = rs.getString(colnum);
					} catch (SQLException se) {
						// oops, they don't support refetching the column
						s = o.toString();
					}
				}
			}
			break;
			}
			if (s==null) s = "NULL";
			*/
// GemStone changes END

			int w = displayColumnWidths[i-1];
            if (out instanceof RedirectedLocalizedOutput) {
              w = s.length()+1;
            }
			if (s.length() < w) {
				StringBuilder fullS = new StringBuilder(s);
				fullS.ensureCapacity(w);
				for (int k=s.length(); k<w; k++)
					fullS.append(' ');
				s = fullS.toString();
			}
			else if (s.length() > w)
				// add the & marker to know it got cut off
// GemStone changes BEGIN
			{
				// skip the marker if its all trailing blanks
				switch (rsmd.getColumnType(i)) {
				  case Types.CHAR:
				  case Types.NCHAR:
				    boolean truncate = false;
				    for (int j = s.length() - 1; j >= w; j--) {
				      if (s.charAt(j) != ' ') {
				        truncate = true;
				        break;
				      }
				    }
				    if (truncate) {
				      s = s.substring(0, w - 1) + '&';
				    }
				    else {
				      s = s.substring(0, w);
				    }
				    break;
				  default:
				    s = s.substring(0, w - 1) + '&';
				    break;
				}
			}
				/* (original code)
				s = s.substring(0,w-1)+"&";
				*/
// GemStone changes END

			buf.append(s);
		}
		indentedPrintLine( out, indentLevel, buf);

// GemStone changes BEGIN
		return buf.length() + (indentLevel * 2);
// GemStone changes END
	} // DisplayRow

	/**
		Check if an object is null, and if it is, throw an exception
		with an informative parameter about what was null.
		The exception is a run-time exception that is internal to ij.

		@param o the object to test
		@param what the information to include in the error if it is null
	 */
	public static void checkNotNull(Object o, String what) {
		if (o == null) {
			throw ijException.objectWasNull(what);
		}
	} // checkNotNull

	/**
		Map the string to the value if it is null.

		@param s the string to test for null
		@param nullValue the value to use if s is null

		@return if s is non-null, s; else nullValue.
	 */
	static public String mapNull(String s, String nullValue) {
		if (s==null) return nullValue;
		return s;
	}

	/**
		If the property gfxd.exceptionTrace is true, display the stack
		trace to the print stream. Otherwise, do nothing.

		@param out the output stream to write to
		@param e the exception to display
	 */
	static public void doTrace(PrintWriter out, Exception e) {
		if (getSystemBoolean("gfxd.exceptionTrace")) {
			e.printStackTrace(out);
		    out.flush();
		}
	}

	static public void setMaxDisplayWidth(int maxDisplayWidth) {
		maxWidth = maxDisplayWidth;
// GemStone changes BEGIN
		maxWidthIsExplicit = true;
// GemStone changes END
	}

	static	private	void	indentedPrintLine( PrintWriter out, int indentLevel, String text )
	{
		indent( out, indentLevel );
		out.println( text );
	}

	static	private	void	indentedPrintLine( PrintWriter out, int indentLevel, StringBuilder text )
	{
		if (!JDBCDisplayUtil.showSelectRows) {
			return;
		}
		indent( out, indentLevel );
		out.println( text );
	}

	static	private	void	indent( PrintWriter out, int indentLevel )
	{
		for ( int ictr = 0; ictr < indentLevel; ictr++ ) { out.print( "  " ); }
	}

	// ================

	static public void ShowException(PrintStream out, Throwable e) {
		if (e == null) return;

		if (e instanceof SQLException)
			ShowSQLException(out, (SQLException)e);
		else
			e.printStackTrace(out);
	}

	static public void ShowSQLException(PrintStream out, SQLException e) {
		String errorCode;

		if (getSystemBoolean("gfxd.showErrorCode")) {
			errorCode = " (errorCode = " + e.getErrorCode() + ")";
		}
		else {
			errorCode = "";
		}

		while (e!=null) {
			out.println("ERROR "+mapNull(e.getSQLState(),"(no SQLState)")+": "+
				 mapNull(e.getMessage(),"(no message)")+errorCode);
			doTrace(out, e);
			e=e.getNextException();
		}
	}

	static public void ShowWarnings(PrintStream out, Connection theConnection) {
	    try {
		// GET CONNECTION WARNINGS
		SQLWarning warning = null;

		if (theConnection != null) {
			ShowWarnings(out, theConnection.getWarnings());
		}

		if (theConnection != null) {
			theConnection.clearWarnings();
		}
	    } catch (SQLException e) {
			ShowSQLException(out, e);
	    }
	} // ShowWarnings

	static public void ShowWarnings(PrintStream out, SQLWarning warning) {
		while (warning != null) {
			out.println("WARNING "+
				mapNull(warning.getSQLState(),"(no SQLState)")+": "+
				mapNull(warning.getMessage(),"(no message)"));
			warning = warning.getNextWarning();
		}
	}

	static public void ShowWarnings(PrintStream out, ResultSet rs) {
	    try {
		// GET RESULTSET WARNINGS
		SQLWarning warning = null;

		if (rs != null) {
			ShowWarnings(out, rs.getWarnings());
		}

		if (rs != null) {
			rs.clearWarnings();
		}
	    } catch (SQLException e) {
			ShowSQLException(out, e);
	    }
	} // ShowResultSetWarnings

	static public void ShowWarnings(PrintStream out, Statement s)
	{
	    try {
		// GET STATEMENT WARNINGS
		SQLWarning warning = null;

		if (s != null) {
			ShowWarnings(out, s.getWarnings());
		}

		if (s != null) {
			s.clearWarnings();
		}
	    } catch (SQLException e) {
			ShowSQLException(out, e);
	    }
	} // ShowStatementWarnings

	static public void DisplayResults(PrintStream out, Statement stmt, Connection conn )
		throws SQLException
	{
		indent_DisplayResults( out, stmt, conn, 0, null, null);			
	}

	static private void indent_DisplayResults
	(PrintStream out, Statement stmt, Connection conn, int indentLevel,
	 int[] displayColumns, int[] displayColumnWidths)
		throws SQLException {

		checkNotNull(stmt, "Statement");

		ResultSet rs = stmt.getResultSet();
		if (rs != null) {
			indent_DisplayResults(out, rs, conn, indentLevel, displayColumns,
								  displayColumnWidths);
			rs.close(); // let the result set go away
		}
		else {
			DisplayUpdateCount(out,stmt.getUpdateCount(), indentLevel);
		}

		ShowWarnings(out,stmt);
	} // DisplayResults

	static void DisplayUpdateCount(PrintStream out, int count, int indentLevel ) {
		if (count == 1) {
			indentedPrintLine( out, indentLevel, "1 row inserted/updated/deleted");
		}
		else if (count >= 0) {
			indentedPrintLine( out, indentLevel, count+" rows inserted/updated/deleted");
		}
		else {
			indentedPrintLine( out, indentLevel, "Statement executed.");
		}
	}

	static public void DisplayResults(PrintStream out, ResultSet rs, Connection conn)
		throws SQLException
	{
		indent_DisplayResults( out, rs, conn, 0, null, null);
	}

	static private void indent_DisplayResults
	(PrintStream out, ResultSet rs, Connection conn, int indentLevel,
	 int[] displayColumns, int[] displayColumnWidths)
		throws SQLException {
		ResultSetMetaData rsmd = rs.getMetaData();
		checkNotNull(rsmd, "ResultSetMetaData");
		Vector nestedResults;
    int numberOfRowsSelected = 0;

		// autocommit must be off or the nested cursors
		// are closed when the outer statement completes.
		if (!conn.getAutoCommit())
			nestedResults = new Vector();
		else
			nestedResults = null;

		if(displayColumnWidths == null)
			displayColumnWidths = getColumnDisplayWidths(rsmd, displayColumns, false);

		int len = indent_DisplayBanner(out,rsmd, indentLevel, displayColumns,
									   displayColumnWidths);

		// When displaying rows, keep going past errors
		// unless/until the maximum # of errors is reached.
		boolean doNext = true;
		int retry = 0;
		while (doNext) {
			try {
				doNext = rs.next();
				if (doNext) {

		    		DisplayRow(out, rs, rsmd, len, nestedResults, conn, 
							   indentLevel, displayColumns, 
							   displayColumnWidths);
					ShowWarnings(out, rs);
					numberOfRowsSelected++;
				}
			} catch (SQLException e) {
				// REVISIT: might want to check the exception
				// and for some, not bother with the retry.
				if (++retry > MAX_RETRIES)
					throw e;
				else
					ShowSQLException(out, e);
			}
		}
		if (showSelectCount == true) {
		   if (numberOfRowsSelected == 1) {
			   out.println();
			   indentedPrintLine( out, indentLevel, "1 row selected");
		   } else if (numberOfRowsSelected >= 0) {
			   out.println();
		       indentedPrintLine( out, indentLevel, numberOfRowsSelected + " rows selected");
		   }
		}

		DisplayNestedResults(out, nestedResults, conn, indentLevel );
		nestedResults = null;
	}

	static private void DisplayNestedResults(PrintStream out, Vector nr, Connection conn, int indentLevel )
		throws SQLException {

		if (nr == null) return;

		String s="+ ResultSet #";
		String b="++++++++++++++++";
		String oldString="0";

		for (int i=0; i < nr.size(); i++) {
			System.out.println();

			//just too clever to get the extra +s
			String t = Integer.toString(i);
			if (t.length() > oldString.length()) {
				oldString = t;
				b=b+"+";
			}

			System.out.println(b);
			System.out.println(s+i+" +");
			System.out.println(b);
			indent_DisplayResults(out, (ResultSet) nr.elementAt(i), conn, 
								  indentLevel, null, null);
		}
	}

	static public void DisplayNextRow(PrintStream out, ResultSet rs, Connection conn )
		throws SQLException
	{
		indent_DisplayNextRow( out, rs, conn, 0, null, (rs == null) ? null
							   : getColumnDisplayWidths(rs.getMetaData(),null,false) );
	}

	static private void indent_DisplayNextRow(PrintStream out, ResultSet rs, Connection conn, int indentLevel,
											  int[] displayColumns, int[] displayColumnWidths )
		throws SQLException {

		Vector nestedResults;

		// autocommit must be off or the nested cursors
		// are closed when the outer statement completes.
		if (!conn.getAutoCommit())
			nestedResults = new Vector();
		else
			nestedResults = null;

		checkNotNull(rs, "ResultSet");

		ResultSetMetaData rsmd = rs.getMetaData();
		checkNotNull(rsmd, "ResultSetMetaData");

		// Only print stuff out if there is a row to be had.
		if (rs.next()) {
			int rowLen = indent_DisplayBanner(out, rsmd, indentLevel, null, null);
    		DisplayRow(out, rs, rsmd, rowLen, nestedResults, conn, indentLevel,
					   displayColumns, displayColumnWidths);
		}
		else {
			indentedPrintLine( out, indentLevel, LocalizedResource.getMessage("UT_NoCurreRow"));
		}

		ShowWarnings(out, rs);

		DisplayNestedResults(out, nestedResults, conn, indentLevel );
		nestedResults = null;

	} // DisplayNextRow

	static public void DisplayCurrentRow(PrintStream out, ResultSet rs, Connection conn )
		throws SQLException
	{
		indent_DisplayCurrentRow( out, rs, conn, 0, null, (rs == null) ? null
								  : getColumnDisplayWidths(rs.getMetaData(),null,false) );
	}

	static private void indent_DisplayCurrentRow(PrintStream out, ResultSet rs, Connection conn, 
												 int indentLevel, int[] displayColumns, int[] displayColumnWidths )
		throws SQLException {

		Vector nestedResults;

		if (rs == null) {
			indentedPrintLine( out, indentLevel, LocalizedResource.getMessage("UT_NoCurreRow_19"));
			return;
		}

		// autocommit must be off or the nested cursors
		// are closed when the outer statement completes.
		if (!conn.getAutoCommit())
			nestedResults = new Vector();
		else
			nestedResults = null;

		ResultSetMetaData rsmd = rs.getMetaData();
		checkNotNull(rsmd, "ResultSetMetaData");

		int rowLen = indent_DisplayBanner(out, rsmd, indentLevel, displayColumns, displayColumnWidths);
   		DisplayRow(out, rs, rsmd, rowLen, nestedResults, conn, indentLevel,
				   displayColumns, displayColumnWidths);

		ShowWarnings(out, rs);

		DisplayNestedResults(out, nestedResults, conn, indentLevel );
		nestedResults = null;

	} // DisplayNextRow

	static public int DisplayBanner(PrintStream out, ResultSetMetaData rsmd )
		throws SQLException
	{
		return indent_DisplayBanner( out, rsmd, 0, null,
									 getColumnDisplayWidths(rsmd,null,false) );
	}

	static private int indent_DisplayBanner(PrintStream out, ResultSetMetaData rsmd, int indentLevel,
											int[] displayColumns, int[] displayColumnWidths )
		throws SQLException	{

		StringBuilder buf = new StringBuilder();

		int numCols = displayColumnWidths.length;
		int rowLen;

		// do some precalculation so the buffer is allocated only once
		// buffer is twice as long as the display length plus one for a newline
		rowLen = (numCols - 1); // for the column separators
		for (int i=1; i <= numCols; i++) {
			rowLen += displayColumnWidths[i-1];
		}
		buf.ensureCapacity(rowLen);

		// get column header info
		// truncate it to the column display width
		// add a bar between each item.
		for (int i=1; i <= numCols; i++) {
			int colnum = displayColumns==null ? i : displayColumns[i-1];

			if (i>1)
				buf.append('|');

			String s = rsmd.getColumnLabel(colnum);

			int w = displayColumnWidths[i-1];

			if (s.length() < w) {
				// build a string buffer to hold the whitespace
				StringBuilder blanks = new StringBuilder(s);
				blanks.ensureCapacity(w);

				// try to paste on big chunks of space at a time.
				for (int k=blanks.length()+64; k<=w; k+=64)
					blanks.append(
          "                                                                ");
				for (int k=blanks.length()+16; k<=w; k+=16)
					blanks.append("                ");
				for (int k=blanks.length()+4; k<=w; k+=4)
					blanks.append("    ");
				for (int k=blanks.length(); k<w; k++)
					blanks.append(' ');

				buf.append(blanks);
				// REMIND: could do more cleverness, like keep around
				// past buffers to reuse...
			}
			else if (s.length() > w)  {
				if (w > 1) 
					buf.append(s.substring(0,w-1));
				if (w > 0) 
					buf.append('&');
			}
			else {
				buf.append(s);
			}
		}

		buf.setLength(Math.min(rowLen, 1024));
		indentedPrintLine( out, indentLevel, buf);

		// now print a row of '-'s
		for (int i=0; i<Math.min(rowLen, 1024); i++)
			buf.setCharAt(i, '-');
		indentedPrintLine( out, indentLevel, buf);

		buf = null;

		return rowLen;
	} // DisplayBanner

	static private void DisplayRow(PrintStream out, ResultSet rs, ResultSetMetaData rsmd, int rowLen, Vector nestedResults, Connection conn, int indentLevel,
								   int[] displayColumns, int[] displayColumnWidths)
		throws SQLException
	{
		StringBuilder buf = new StringBuilder();
		buf.ensureCapacity(rowLen);

		int numCols = displayColumnWidths.length;
		int i;

		// get column header info
		// truncate it to the column display width
		// add a bar between each item.
		for (i=1; i <= numCols; i++){
			int colnum = displayColumns==null ? i : displayColumns[i-1];
			if (i>1)
				buf.append('|');

			String s;
			switch (rsmd.getColumnType(colnum)) {
			default:
				s = rs.getString(colnum);
				break;
			case Types.JAVA_OBJECT:
			case Types.OTHER:
			{
				Object o = rs.getObject(colnum);
				if (o == null) { s = "NULL"; }
				else if (o instanceof ResultSet && nestedResults != null)
				{
					s = "ResultSet #"+nestedResults.size();
					nestedResults.addElement(o);
				}
				else
				{
					try {
						s = rs.getString(colnum);
					} catch (SQLException se) {
						// oops, they don't support refetching the column
						s = o.toString();
					}
				}
			}
			break;
			}

			if (s==null) s = "NULL";

			int w = displayColumnWidths[i-1];
			if (s.length() < w) {
				StringBuilder fullS = new StringBuilder(s);
				fullS.ensureCapacity(w);
				for (int k=s.length(); k<w; k++)
					fullS.append(' ');
				s = fullS.toString();
			}
			else if (s.length() > w)
				// add the & marker to know it got cut off
				s = s.substring(0,w-1)+"&";

			buf.append(s);
		}
		indentedPrintLine( out, indentLevel, buf);

	} // DisplayRow

	static public void doTrace(PrintStream out, Exception e) {
		if (getSystemBoolean("gfxd.exceptionTrace")) {
			e.printStackTrace(out);
		    out.flush();
		}
	}

	static	private	void	indentedPrintLine( PrintStream out, int indentLevel, String text )
	{
		indent( out, indentLevel );
		out.println( text );
	}

	static	private	void	indentedPrintLine( PrintStream out, int indentLevel, StringBuilder text )
	{
		indent( out, indentLevel );
		out.println( text );
	}

	static	private	void	indent( PrintStream out, int indentLevel )
	{
		for ( int ictr = 0; ictr < indentLevel; ictr++ ) { out.print( "  " ); }
	}
	
	// ==========================
    
    /**
     * Get an ij boolean system property.
     *
     * @param name name of the property
     */
    private static boolean getSystemBoolean(final String name) {

        return ((Boolean) AccessController
                .doPrivileged(new java.security.PrivilegedAction() {

                    public Object run() {
                        return Boolean.getBoolean(name) ?
                            Boolean.TRUE : Boolean.FALSE;

                    }

                })).booleanValue();
    }
// GemStone changes BEGIN
    private static int maxLineWidth = 0;
    private static int maxLineWidthImplicit = 0;
    private static boolean maxWidthIsExplicit = false;
    private static boolean maxLineWidthIsExplicit = false;
    private static boolean pageResults = true;

    private static final HashMap<String, Integer> columnWidths =
        new HashMap<String, Integer>();

    private static ArrayDeque<String[]> currentResults;

    public static void setMaxLineWidth(int width, boolean explicit) {
      if (!explicit) {
        maxLineWidth = width - 1;
        maxLineWidthImplicit = width - 1;
        maxLineWidthIsExplicit = false;
      }
      else if (width == 0) {
        // reset to implcit width
        maxLineWidth = maxLineWidthImplicit;
        maxLineWidthIsExplicit = false;
      }
      else {
        maxLineWidth = width - 1;
        maxLineWidthIsExplicit = true;
      }
    }

    public static int getMaxDisplayWidth() {
      return maxWidth;
    }

    public static int getMaxColumnWidth(String columnName) {
      final Integer width = columnName != null ? columnWidths.get(columnName)
          : null;
      return width == null ? maxWidth : width.intValue();
    }

    public static int getMaxColumnWidthExplicit(String columnName) {
      final Integer width = columnName != null ? columnWidths.get(columnName)
          : null;
      if (width != null) {
        return width.intValue();
      }
      else if (maxWidthIsExplicit) {
        return maxWidth;
      }
      else {
        return -1;
      }
    }

    public static int getMaxLineWidth() {
      if (maxLineWidthIsExplicit) {
        return maxLineWidth + 1;
      }
      else {
        return 0;
      }
    }

    public static void setMaxDisplayWidth(String columnName,
        int maxDisplayWidth) {
      if (maxDisplayWidth < 0) {
        columnWidths.remove(columnName);
      }
      else {
        columnWidths.put(columnName, maxDisplayWidth);
      }
    }

    public static void setPaging(boolean on) {
      pageResults = on;
    }

    private static String getColumnDisplayString(ResultSet rs,
        ResultSetMetaData rsmd, int colNum, Vector nestedResults)
        throws SQLException {
      String s;
      switch (rsmd.getColumnType(colNum)) {
        case Types.JAVA_OBJECT:
        case Types.OTHER: {
          Object o = rs.getObject(colNum);
          if (o == null) {
            s = "NULL";
          }
          else if (o instanceof ResultSet && nestedResults != null) {
            s = LocalizedResource.getMessage("UT_Resul0_20",
                LocalizedResource.getNumber(nestedResults.size()));
            nestedResults.addElement(o);
          }
          else {
            try {
              s = rs.getString(colNum);
            } catch (SQLException se) {
              // oops, they don't support refetching the column
              s = o.toString();
            }
          }
          break;
        }
        default:
          s = LocalizedResource.getInstance()
              .getLocalizedString(rs, rsmd, colNum);
          break;
      }
      if (s == null) {
        s = "NULL";
      }
      return s;
    }

    /**
     * Adjust the column widths as per the actual result widths adjusted
     * to total terminal width stripping blanks from end if required and
     * also proportionately reducing the widths. The widths are calculated
     * from the first pageful of result assuming the next results to be similar
     * but if not then in worst case the coming results may get truncated
     * sometimes in a non-optimal way.
     */
    private static void adjustColumnWidths(int[] columns, int[] columnWidths,
        List results, ResultSetMetaData rsmd, Vector<?> nestedResults,
        boolean singleRow, ConsoleReader reader) throws SQLException {
      // do nothing for non-interactive sessions
      if (maxLineWidth == 0) {
        return;
      }
      // assume result set is already positioned on the row
      final int numColumns = columnWidths.length;
      boolean adjustForLabels = true;
      currentResults = null;
      int totalWidth = (numColumns - 1); // for the '|' separators
      for (int w : columnWidths) {
        totalWidth += w;
      }
      if (reader != null) {
        int numRows;
        if (singleRow) {
          numRows = 1;
        }
        else {
          numRows = reader.getTerminal().getHeight() - 1;
          if (numRows <= 1) {
            numRows = 2;
          }
        }
        // we go through below even if totalWidth does not exceed maxLineWidth
        // since there are cases where columnWidth can be truncating the value
        // while there is space available in the line
        currentResults = new ArrayDeque<String[]>(numRows);
        final int[] newColumnWidths = new int[numColumns];
        final int[] newColumnAndLabelWidths = new int[numColumns];
        boolean hasAdjustedColumns = false;
        int resultIndex = 0;
        ResultSet rs = (ResultSet)results.get(resultIndex++);
        outer:
        while (numRows-- > 0) {
          if (!singleRow && !rs.next()) {
            // move onto subsequent resultset if current is fully consumed.
            resultIndex++;
            do {
              if (resultIndex >= results.size()) {
                break outer;
              }
              rs = (ResultSet)results.get(resultIndex++);
            } while (!rs.next());
          }
          String[] row = new String[numColumns];
          String s;
          for (int i = 0; i < numColumns; i++) {
            int colNum = (columns == null ? (i + 1) : columns[i]);
            s = getColumnDisplayString(rs, rsmd, colNum, nestedResults);
            // width adjustment only for char type columns
            boolean isCharType = false;
            switch (rsmd.getColumnType(colNum)) {
              case Types.CHAR:
              case Types.VARCHAR:
              case Types.LONGVARCHAR:
              case Types.NCHAR:
              case Types.NVARCHAR:
              case Types.LONGNVARCHAR:
              case Types.CLOB:
                isCharType = true;
              case Types.BINARY:
              case Types.VARBINARY:
              case Types.LONGVARBINARY:
              case Types.BLOB:
              case Types.NUMERIC:
              case Types.DECIMAL:
              case Types.DATE:
              case Types.TIME:
              case Types.TIMESTAMP:
              case Types.JAVA_OBJECT:
              case Types.OTHER:
                final int len = (s != null && s.length() > MINWIDTH) ? s
                    .length() : MINWIDTH;
                if (len > newColumnWidths[i]) {
                  if (len > MINWIDTH) {
                    if (isCharType) {
                      // don't count the trailing blanks
                      int index;
                      for (index = s.length() - 1; index >= 0; index--) {
                        if (s.charAt(index) != ' ') {
                          break;
                        }
                      }
                      if (index >= newColumnWidths[i]) {
                        newColumnWidths[i] = index + 1;
                      }
                    }
                    else {
                      newColumnWidths[i] = s.length();
                    }
                    final int maxWidth = getMaxColumnWidthExplicit(
                        rsmd.getColumnLabel(colNum));
                    if (maxWidth > 0 && newColumnWidths[i] > maxWidth) {
                      newColumnWidths[i] = maxWidth;
                    }
                  }
                  else {
                    newColumnWidths[i] = MINWIDTH;
                  }
                  newColumnAndLabelWidths[i] = newColumnWidths[i];
                }
                hasAdjustedColumns = true;
                break;
            }
            // expand for column labels if required
            String colLabel = rsmd.getColumnLabel(colNum);
            int labelLen, maxWidth;
            if (colLabel != null) {
              labelLen = colLabel.trim().length();
              if ((maxWidth = getMaxColumnWidthExplicit(colLabel)) > 0
                  && labelLen > maxWidth) {
                labelLen = maxWidth;
              }
              if ((labelLen > columnWidths[i] || newColumnWidths[i] > 0)
                  && newColumnAndLabelWidths[i] < labelLen) {
                newColumnAndLabelWidths[i] = labelLen;
              }
            }
            row[i] = s;
          }
          currentResults.addLast(row);
          if (!hasAdjustedColumns) {
            // nothing will be changed, so break out now
            break;
          }
        }
        totalWidth = (numColumns - 1); // for the '|' separator
        // to find the total reduction
        int totalReduction = 0;
        // to find the total of adjustable width columns
        int totalAdjustableWidth = 0;
        for (int i = 0; i < columnWidths.length; i++) {
          final int newWidth = newColumnAndLabelWidths[i];
          if (newWidth > 0) {
            totalWidth += newWidth;
            if (columnWidths[i] > newWidth) {
              // reduce the share of columns that were increased due to
              // column label widths
              if (newColumnWidths[i] == newColumnAndLabelWidths[i]) {
                totalReduction += (columnWidths[i] - newWidth);
              }
              else {
                int c_l = (columnWidths[i] - newWidth);
                totalReduction += (c_l * c_l)
                    / (columnWidths[i] - newColumnAndLabelWidths[i]);
              }
            }
            totalAdjustableWidth += newWidth;
          }
          else {
            totalWidth += columnWidths[i];
          }
        }
        // in the second round increase or decrease the sizes proportionately
        // so that columns occupy as much width as possible in anticipation
        // of the rows to come in next pages
        boolean computeWidths = true;
        if (totalWidth > maxLineWidth) {
          // truncate only for the case when maxLineWidth has been explicitly
          // set by the user; divide the amount proportionately among columns
          // that are being adjusted
          if (maxLineWidthIsExplicit) {
            int toReduce = totalWidth - maxLineWidth;
            totalWidth = (numColumns - 1); // for the '|' separator
            for (int i = 0; i < columnWidths.length; i++) {
              final int newWidth = newColumnAndLabelWidths[i];
              if (newWidth > 0) {
                newColumnAndLabelWidths[i] -= (newWidth * toReduce)
                    / totalAdjustableWidth;
                if (newColumnAndLabelWidths[i] < MINWIDTH) {
                  newColumnAndLabelWidths[i] = MINWIDTH;
                }
                columnWidths[i] = newColumnAndLabelWidths[i];
              }
              totalWidth += columnWidths[i];
            }
            // adjust the other columns as last resort if adjustable columns
            // are not enough
            final int initTotalWidth = totalWidth;
            if (initTotalWidth > maxLineWidth) {
              toReduce = (initTotalWidth - maxLineWidth);
              totalWidth = (numColumns - 1); // for the '|' separator
              for (int i = 0; i < columnWidths.length; i++) {
                if (columnWidths[i] > MINWIDTH) {
                  columnWidths[i] -= (columnWidths[i] * toReduce)
                      / initTotalWidth;
                  if (columnWidths[i] < MINWIDTH) {
                    columnWidths[i] = MINWIDTH;
                  }
                }
                totalWidth += columnWidths[i];
              }
            }
            adjustForLabels = false;
          }
          else {
            // restore the columns where newColumnWidths was > columnWidths
            // and go back to the width in columnWidths
            totalWidth = (numColumns - 1); // for the '|' separator
            for (int i = 0; i < columnWidths.length; i++) {
              if (newColumnWidths[i] > 0 &&
                  columnWidths[i] > newColumnWidths[i]) {
                columnWidths[i] = newColumnWidths[i];
              }
              totalWidth += columnWidths[i];
            }
          }
          computeWidths = false;
        }
        else if (totalWidth < maxLineWidth && totalReduction > 0) {
          adjustForLabels = false;
          // now proportionately increase the widths
          final int availableWidth = (maxLineWidth - totalWidth);
          for (int i = 0; i < columnWidths.length; i++) {
            final int newWidth = newColumnAndLabelWidths[i];
            if (newWidth > 0) {
              if (columnWidths[i] > newWidth) {
                // reduce the share of columns that were increased due to
                // column label widths
                final int myReduction;
                if (newColumnWidths[i] == newColumnAndLabelWidths[i]) {
                  myReduction = (columnWidths[i] - newWidth);
                }
                else {
                  int c_l = (columnWidths[i] - newWidth);
                  myReduction = (c_l * c_l)
                      / (columnWidths[i] - newColumnWidths[i]);
                  newColumnWidths[i] = newColumnAndLabelWidths[i];
                }
                newColumnWidths[i] += (myReduction * availableWidth)
                    / totalReduction;
                if (newColumnWidths[i] > columnWidths[i]) {
                  newColumnWidths[i] = columnWidths[i];
                }
              }
              else if (newColumnAndLabelWidths[i] > newColumnWidths[i]) {
                newColumnWidths[i] = newColumnAndLabelWidths[i];
              }
            }
          }
        }
        if (computeWidths) {
          totalWidth = (numColumns - 1); // for the '|' separator
          for (int i = 0; i < columnWidths.length; i++) {
            if (newColumnWidths[i] > 0) {
              columnWidths[i] = newColumnWidths[i];
            }
            totalWidth += columnWidths[i];
          }
        }
        if (currentResults.size() == 0) {
          currentResults = null;
        }
      }
      // finally also increase the width for labels if possible
      if (adjustForLabels
          && (!maxLineWidthIsExplicit || totalWidth < maxLineWidth)) {
        for (int i = 0; i < numColumns; i++) {
          int colNum = (columns == null ? (i + 1) : columns[i]);
          String colLabel = rsmd.getColumnLabel(colNum);
          int labelLen, maxWidth;
          if (colLabel != null) {
            labelLen = colLabel.trim().length();
            if ((maxWidth = getMaxColumnWidthExplicit(colLabel)) > 0
                && labelLen > maxWidth) {
              labelLen = maxWidth;
            }
            if (columnWidths[i] < labelLen) {
              totalWidth += (labelLen - columnWidths[i]);
              if (maxLineWidthIsExplicit && totalWidth > maxLineWidth) {
                break;
              }
              columnWidths[i] = labelLen;
            }
          }
        }
      }
    }
// GemStone changes END
}



