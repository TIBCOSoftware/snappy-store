/*
 *
 * Derby - Class org.apache.derbyTesting.functionTests.util.JDBC
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
 * either express or implied. See the License for the specific 
 * language governing permissions and limitations under the License.
 */
package org.apache.derbyTesting.junit;

// GemStone changes BEGIN
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.CacheObserverAdapter;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.gemstone.gemfire.internal.cache.TXStateProxy;
import com.gemstone.gemfire.internal.cache.Token;
import com.gemstone.gemfire.internal.offheap.OffHeapRegionEntryHelper;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gnu.trove.THashMap;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.execute.CallbackStatement;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.message.StatementExecutorMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.ComparisonQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SubQueryInfo;
import com.pivotal.gemfirexd.internal.engine.procedure.ProcedureChunkMessage;
import com.pivotal.gemfirexd.internal.engine.procedure.cohort.ProcedureSender;
import com.pivotal.gemfirexd.internal.engine.sql.conn.GfxdHeapThresholdListener;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireActivation;
import com.pivotal.gemfirexd.internal.engine.sql.execute.AbstractGemFireResultSet;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.ParameterValueSet;
import com.pivotal.gemfirexd.internal.iapi.sql.ResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TriggerDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.store.access.ColumnOrdering;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericParameterValueSet;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.StatementStats;
import com.pivotal.gemfirexd.internal.impl.sql.compile.DropTableNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.FromBaseTable;
import com.pivotal.gemfirexd.internal.impl.sql.compile.SelectNode;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;



// GemStone changes END
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import com.pivotal.gemfirexd.internal.impl.sql.rules.ExecutionEngineRule;
import junit.framework.Assert;
import junit.framework.AssertionFailedError;

/**
 * JDBC utility methods for the JUnit tests.
 * Note that JSR 169 is a subset of JDBC 3 and
 * JDBC 3 is a subset of JDBC 4.
 * The base level for the Derby tests is JSR 169.
 */
public class JDBC {
    
    /**
     * Helper class whose <code>equals()</code> method returns
     * <code>true</code> for all strings on this format: SQL061021105830900
     */
    public static class GeneratedId {
        public boolean equals(Object o) {
            // unless JSR169, use String.matches...
            if (JDBC.vmSupportsJDBC3()) 
            {
                return o instanceof String &&
                ((String) o).matches("SQL[0-9]{15}");
            }
            else
            {
                String tmpstr = (String)o;
                boolean b = true;
                if (!(o instanceof String))
                    b = false;
                if (!(tmpstr.startsWith("SQL")))
                    b = false;
                if (tmpstr.length() != 18)
                    b = false;
                for (int i=3 ; i<18 ; i++)
                {
                    if (Character.isDigit(tmpstr.charAt(i)))
                        continue;
                    else
                    {
                        b = false;
                        break;
                    }
                }
            return b;
            }
        }
        public String toString() {
            return "xxxxGENERATED-IDxxxx";
        }
    }

    /**
     * Constant to pass to DatabaseMetaData.getTables() to fetch
     * just tables.
     */
    public static final String[] GET_TABLES_TABLE = new String[] {"TABLE"};
    /**
     * Constant to pass to DatabaseMetaData.getTables() to fetch
     * just views.
     */
    public static final String[] GET_TABLES_VIEW = new String[] {"VIEW"};
    /**
     * Constant to pass to DatabaseMetaData.getTables() to fetch
     * just synonyms.
     */
    public static final String[] GET_TABLES_SYNONYM =
        new String[] {"SYNONYM"};
    
    /**
     * Types.SQLXML value without having to compile with JDBC4.
     */
    public static final int SQLXML = 2009;
	
    /**
     * Tell if we are allowed to use DriverManager to create database
     * connections.
     */
    private static final boolean HAVE_DRIVER
                           = haveClass("java.sql.Driver");
    
    /**
     * Does the Savepoint class exist, indicates
     * JDBC 3 (or JSR 169). 
     */
    private static final boolean HAVE_SAVEPOINT
                           = haveClass("java.sql.Savepoint");

    /**
     * Does the java.sql.SQLXML class exist, indicates JDBC 4. 
     */
    private static final boolean HAVE_SQLXML
                           = haveClass("java.sql.SQLXML");

    /**
     * Can we load a specific class, use this to determine JDBC level.
     * @param className Class to attempt load on.
     * @return true if class can be loaded, false otherwise.
     */
    static boolean haveClass(String className)
    {
        try {
            Class.forName(className);
            return true;
        } catch (Exception e) {
        	return false;
        }    	
    }
 	/**
	 * Return true if the virtual machine environment
	 * supports JDBC4 or later. JDBC 4 is a superset
     * of JDBC 3 and of JSR169.
     * <BR>
     * This method returns true in a JDBC 4 environment
     * and false in a JDBC 3 or JSR 169 environment.
	 */
	public static boolean vmSupportsJDBC4()
	{
		return HAVE_DRIVER
	       && HAVE_SQLXML;
	}
 	/**
	 * Return true if the virtual machine environment
	 * supports JDBC3 or later. JDBC 3 is a super-set of JSR169
     * and a subset of JDBC 4.
     * <BR>
     * This method will return true in a JDBC 3 or JDBC 4
     * environment, but false in a JSR169 environment.
	 */
	public static boolean vmSupportsJDBC3()
	{
		return HAVE_DRIVER
		       && HAVE_SAVEPOINT;
	}

	/**
 	 * <p>
	 * Return true if the virtual machine environment
	 * supports JDBC2 or later.
	 * </p>
	 */
	public static boolean vmSupportsJDBC2()
	{
		return HAVE_DRIVER;
	}

	/**
	 * Return true if the virtual machine environment
	 * supports JSR169. JSR169 is a subset of JDBC 3
     * and hence a subset of JDBC 4 as well.
     * <BR>
     * This method returns true only in a JSR 169
     * environment.
	 */
	public static boolean vmSupportsJSR169()
	{
		return !HAVE_DRIVER
		       && HAVE_SAVEPOINT;
	}	
	
	/**
	 * Rollback and close a connection for cleanup.
	 * Test code that is expecting Connection.close to succeed
	 * normally should just call conn.close().
	 * 
	 * <P>
	 * If conn is not-null and isClosed() returns false
	 * then both rollback and close will be called.
	 * If both methods throw exceptions
	 * then they will be chained together and thrown.
	 * @throws SQLException Error closing connection.
	 */
	public static void cleanup(Connection conn) throws SQLException
	{
		if (conn == null)
			return;
		if (conn.isClosed())
			return;
		
		SQLException sqle = null;
		try {
			conn.rollback();
		} catch (SQLException e) {
			sqle = e;
		}
		
		try {
			conn.close();
		} catch (SQLException e) {
// GemStone changes BEGIN
		  // ignore exceptions in close
		  /* (original code)
			if (sqle == null)
			    sqle = e;
			else
				sqle.setNextException(e);
			throw sqle;
		  */
// GemStone changes END
		}
	}
	
	/**
	 * Drop a database schema by dropping all objects in it
	 * and then executing DROP SCHEMA. If the schema is
	 * APP it is cleaned but DROP SCHEMA is not executed.
	 * 
	 * TODO: Handle dependencies by looping in some intelligent
	 * way until everything can be dropped.
	 * 

	 * 
	 * @param dmd DatabaseMetaData object for database
	 * @param schema Name of the schema
	 * @throws SQLException database error
	 */
	public static void dropSchema(DatabaseMetaData dmd, String schema) throws SQLException
	{		
		Connection conn = dmd.getConnection();
		//Assert.assertFalse(conn.getAutoCommit());
		Statement s = dmd.getConnection().createStatement();
        
        // Functions - not supported by JDBC meta data until JDBC 4
        // Need to use the CHAR() function on A.ALIASTYPE
        // so that the compare will work in any schema.
        PreparedStatement psf = conn.prepareStatement(
                "SELECT ALIAS FROM SYS.SYSALIASES A, SYS.SYSSCHEMAS S" +
                " WHERE A.SCHEMAID = S.SCHEMAID " +
                " AND CHAR(A.ALIASTYPE) = ? " +
                " AND S.SCHEMANAME = ?");
        psf.setString(1, "F" );
        psf.setString(2, schema);
        java.sql.ResultSet rs = psf.executeQuery();
        dropUsingDMD(s, rs, schema, "ALIAS", "FUNCTION");        

// GemStone changes BEGIN
        // also drop any ProcedureResultProcessors
        psf.setString(1, "R" );
        psf.setString(2, schema);
        rs = psf.executeQuery();
        dropUsingDMD(s, rs, schema, "ALIAS", "ALIAS");        

// GemStone changes END
		// Procedures
		rs = dmd.getProcedures((String) null,
				schema, (String) null);
		
		dropUsingDMD(s, rs, schema, "PROCEDURE_NAME", "PROCEDURE");
		
		// Views
		rs = dmd.getTables((String) null, schema, (String) null,
                GET_TABLES_VIEW);
		
		dropUsingDMD(s, rs, schema, "TABLE_NAME", "VIEW");
		
		// Tables
		rs = dmd.getTables((String) null, schema, (String) null,
                GET_TABLES_TABLE);
		
		dropUsingDMD(s, rs, schema, "TABLE_NAME", "TABLE");
        
        // At this point there may be tables left due to
        // foreign key constraints leading to a dependency loop.
        // Drop any constraints that remain and then drop the tables.
        // If there are no tables then this should be a quick no-op.
        java.sql.ResultSet table_rs = dmd.getTables((String) null, schema, (String) null,
                GET_TABLES_TABLE);

        while (table_rs.next()) {
            String tablename = table_rs.getString("TABLE_NAME");
            rs = dmd.getExportedKeys((String) null, schema, tablename);
            while (rs.next()) {
                short keyPosition = rs.getShort("KEY_SEQ");
                if (keyPosition != 1)
                    continue;
                String fkName = rs.getString("FK_NAME");
                // No name, probably can't happen but couldn't drop it anyway.
                if (fkName == null)
                    continue;
                String fkSchema = rs.getString("FKTABLE_SCHEM");
                String fkTable = rs.getString("FKTABLE_NAME");

                String ddl = "ALTER TABLE " +
                    JDBC.escape(fkSchema, fkTable) +
                    " DROP FOREIGN KEY " +
                    JDBC.escape(fkName);
                s.executeUpdate(ddl);
            }
            rs.close();
        }
        table_rs.close();
        conn.commit();
                
        // Tables (again)
        rs = dmd.getTables((String) null, schema, (String) null,
                GET_TABLES_TABLE);        
        dropUsingDMD(s, rs, schema, "TABLE_NAME", "TABLE");

        // drop UDTs
        psf.setString(1, "A" );
        psf.setString(2, schema);
        rs = psf.executeQuery();
        dropUsingDMD(s, rs, schema, "ALIAS", "TYPE");        
        psf.close();
  
        // Synonyms - need work around for DERBY-1790 where
        // passing a table type of SYNONYM fails.
        rs = dmd.getTables((String) null, schema, (String) null,
                GET_TABLES_SYNONYM);
        
        dropUsingDMD(s, rs, schema, "TABLE_NAME", "SYNONYM");
                
// GemStone changes BEGIN
        // don't try to drop the schema if it is an implicit one
        // for the current user
        boolean doDrop = !schema.equals("APP");
        if (doDrop && conn instanceof EmbedConnection) {
          LanguageConnectionContext lcc = ((EmbedConnection)conn)
              .getLanguageConnection();
          if (lcc != null && schema.equals(lcc.getAuthorizationId())) {
            doDrop = false;
          }
        }
        if (doDrop) {
          try {
            s.executeUpdate("DROP SCHEMA IF EXISTS "
                + JDBC.escape(schema) + " RESTRICT");
          } catch (SQLException sqle) {
            // ignore "No datastore" errors
            if (!"X0Z08".equals(sqle.getSQLState())) {
              throw sqle;
            }
          }
        }
        /* (original code)
		// Finally drop the schema if it is not APP
		if (!schema.equals("APP")) {
			s.executeUpdate("DROP SCHEMA " + JDBC.escape(schema) + " RESTRICT");
		}
        */
// GemStone changes END
		conn.commit();
		s.close();
	}
	
	/**
	 * DROP a set of objects based upon a ResultSet from a
	 * DatabaseMetaData call.
	 * 
	 * TODO: Handle errors to ensure all objects are dropped,
	 * probably requires interaction with its caller.
	 * 
	 * @param s Statement object used to execute the DROP commands.
	 * @param rs DatabaseMetaData ResultSet
	 * @param schema Schema the objects are contained in
	 * @param mdColumn The column name used to extract the object's
	 * name from rs
	 * @param dropType The keyword to use after DROP in the SQL statement
	 * @throws SQLException database errors.
	 */
	private static void dropUsingDMD(
			Statement s, java.sql.ResultSet rs, String schema,
			String mdColumn,
			String dropType) throws SQLException
	{
		String dropLeadIn = "DROP " + dropType + " ";
		
        // First collect the set of DROP SQL statements.
        ArrayList ddl = new ArrayList();
        GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
		while (rs.next())
		{
            String objectName = rs.getString(mdColumn);
            String raw = dropLeadIn + JDBC.escape(schema, objectName);
            if ( "TYPE".equals( dropType ) ) { raw = raw + " restrict "; }
            // move child tables at the start
            if (cache != null) {
              Region<?, ?> region = Misc.getRegionByPath(
                  Misc.getRegionPath(schema, objectName, null), false);
              PartitionAttributes<?, ?> pattrs;
              if (region != null &&
                  (pattrs = region.getAttributes().getPartitionAttributes()) != null &&
                  pattrs.getColocatedWith() != null) {
                ddl.add(0, raw);
                continue;
              }
            }
            ddl.add( raw );
		}
		rs.close();
        if (ddl.isEmpty())
            return;
                
        // Execute them as a complete batch, hoping they will all succeed.
        s.clearBatch();
        int batchCount = 0;
        for (Iterator i = ddl.iterator(); i.hasNext(); )
        {
            Object sql = i.next();
            if (sql != null) {
                s.addBatch(sql.toString());
                batchCount++;
            }
        }

		int[] results;
        boolean hadError;
		try {
		    results = s.executeBatch();
		    Assert.assertNotNull(results);
		    Assert.assertEquals("Incorrect result length from executeBatch",
		    		batchCount, results.length);
            hadError = false;
		} catch (BatchUpdateException batchException) {
			results = batchException.getUpdateCounts();
			Assert.assertNotNull(results);
			Assert.assertTrue("Too many results in BatchUpdateException",
					results.length <= batchCount);
            hadError = true;
		}
		
        // Remove any statements from the list that succeeded.
		boolean didDrop = false;
		for (int i = 0; i < results.length; i++)
		{
			int result = results[i];
			if (result == -3 /* Statement.EXECUTE_FAILED*/)
				hadError = true;
			else if (result == -2/*Statement.SUCCESS_NO_INFO*/)
				didDrop = true;
			else if (result >= 0)
				didDrop = true;
			else
				Assert.fail("Negative executeBatch status");
            
            if (didDrop)
                ddl.set(i, null);
		}
        s.clearBatch();
        if (didDrop) {
            // Commit any work we did do.
            s.getConnection().commit();
        }

        // If we had failures drop them as individual statements
        // until there are none left or none succeed. We need to
        // do this because the batch processing stops at the first
        // error. This copes with the simple case where there
        // are objects of the same type that depend on each other
        // and a different drop order will allow all or most
        // to be dropped.
        if (hadError) {
            do {
                hadError = false;
                didDrop = false;
                for (ListIterator i = ddl.listIterator(); i.hasNext();) {
                    Object sql = i.next();
                    if (sql != null) {
                        try {
                            s.executeUpdate(sql.toString());
                            i.set(null);
                            didDrop = true;
                        } catch (SQLException e) {
                            hadError = true;
                        }
                    }
                }
                if (didDrop)
                    s.getConnection().commit();
            } while (hadError && didDrop);
        }
	}
	
	/**
	 * Assert all columns in the ResultSetMetaData match the
	 * table's defintion through DatabaseMetadDta. Only works
	 * if the complete select list correspond to columns from
	 * base tables.
	 * <BR>
	 * Does not require that the complete set of any table's columns are
	 * returned.
	 * @throws SQLException 
	 * 
	 */
	public static void assertMetaDataMatch(DatabaseMetaData dmd,
			java.sql.ResultSetMetaData rsmd) throws SQLException
	{
		for (int col = 1; col <= rsmd.getColumnCount(); col++)
		{
			// Only expect a single column back
		    java.sql.ResultSet column = dmd.getColumns(
		    		rsmd.getCatalogName(col),
		    		rsmd.getSchemaName(col),
		    		rsmd.getTableName(col),
		    		rsmd.getColumnName(col));
		    
		    Assert.assertTrue("Column missing " + rsmd.getColumnName(col),
		    		column.next());
		    
		    Assert.assertEquals(column.getInt("DATA_TYPE"),
		    		rsmd.getColumnType(col));
		    
		    Assert.assertEquals(column.getInt("NULLABLE"),
		    		rsmd.isNullable(col));
		    
		    Assert.assertEquals(column.getString("TYPE_NAME"),
		    		rsmd.getColumnTypeName(col));
		    
		    column.close();
		}
	}
    
    /**
     * Assert a result set is empty.
     * If the result set is not empty it will
     * be drained before the check to see if
     * it is empty.
     * The ResultSet is closed by this method.

     */
    public static void assertEmpty(java.sql.ResultSet rs)
    throws SQLException
    {
        assertDrainResults(rs, 0);
    }
	
    /**
     * 
     * @param rs
     */
    public static void assertClosed(java.sql.ResultSet rs)
    {
        try { 
            rs.next();
            Assert.fail("ResultSet not closed");
        }catch (SQLException sqle){
            Assert.assertEquals("XCL16", sqle.getSQLState());
        }
        
        
    }
    
    /**
     * Assert that no warnings were returned from a JDBC getWarnings()
     * method such as Connection.getWarnings. Reports the contents
     * of the warning if it is not null.
     * @param warning Warning that should be null.
     */
    public static void assertNoWarnings(SQLWarning warning)
    {
        if (warning == null)
            return;
        
        Assert.fail("Expected no SQLWarnings - got: " + warning.getSQLState() 
                + " " + warning.getMessage());
    }
    
    /**
     * Assert that the statement has no more results(getMoreResults) and it 
     * indeed does not return any resultsets(by checking getResultSet). 
     * Also, ensure that update count is -1.
     * @param s Statement holding no results.
     * @throws SQLException Exception checking results.
     */
    public static void assertNoMoreResults(Statement s) throws SQLException
    {
    	Assert.assertFalse(s.getMoreResults());
        Assert.assertTrue(s.getUpdateCount() == -1);
        Assert.assertNull(s.getResultSet());
    }
    
    /**
     * Assert that a ResultSet representing generated keys is non-null
     * and of the correct type. This method leaves the ResultSet
     * open and does not fetch any date from it.
     * 
     * @param description For assert messages
     * @param keys ResultSet returned from getGeneratedKeys().
     * @throws SQLException
     */
    public static void assertGeneratedKeyResultSet(
            String description, java.sql.ResultSet keys) throws SQLException
    {
        
        Assert.assertNotNull(description, keys);
        
        // Requirements from section 13.6 JDBC 4 specification
        Assert.assertEquals(
                description + 
                " - Required CONCUR_READ_ONLY for generated key result sets",
                java.sql.ResultSet.CONCUR_READ_ONLY, keys.getConcurrency());
        
        int type = keys.getType();
        if ( (type != java.sql.ResultSet.TYPE_FORWARD_ONLY) &&
             (type != java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE))
        {
            Assert.fail(description +
                    " - Invalid type for generated key result set" + type);
        }
        
        

    }
    
    /**
     * Drain a ResultSet and assert it has at least one row.
     *
     * The ResultSet is closed by this method.
     */
    public static void assertDrainResultsHasData(java.sql.ResultSet rs)
    throws SQLException
    {
        int rowCount = assertDrainResults(rs, -1);
        Assert.assertTrue("ResultSet expected to have data", rowCount > 0);
    }    
    
	/**
	 * Drain a single ResultSet by reading all of its
	 * rows and columns. Each column is accessed using
	 * getString() and asserted that the returned value
	 * matches the state of ResultSet.wasNull().
	 *
	 * Provides simple testing of the ResultSet when the
	 * contents are not important.
     * 
     * The ResultSet is closed by this method.
	 *
	 * @param rs Result set to drain.
     * @return the number of rows seen.

	 * @throws SQLException
	 */
	public static int assertDrainResults(java.sql.ResultSet rs)
	    throws SQLException
	{
		return assertDrainResults(rs, -1);
	}

	/**
	 * Does the work of assertDrainResults() as described
	 * above.  If the received row count is non-negative,
	 * this method also asserts that the number of rows
	 * in the result set matches the received row count.
     * 
     * The ResultSet is closed by this method.
	 *
	 * @param rs Result set to drain.
	 * @param expectedRows If non-negative, indicates how
	 *  many rows we expected to see in the result set.
     *  @return the number of rows seen.
	 * @throws SQLException
	 */
	public static int assertDrainResults(java.sql.ResultSet rs,
	    int expectedRows) throws SQLException
	{
		java.sql.ResultSetMetaData rsmd = rs.getMetaData();
		
		int rows = 0;
		while (rs.next()) {
			for (int col = 1; col <= rsmd.getColumnCount(); col++)
			{
				String s = rs.getString(col);
				Assert.assertEquals(s == null, rs.wasNull());
                if (rs.wasNull())
                    assertResultColumnNullable(rsmd, col);
			}
			rows++;
		}
		rs.close();

		if (expectedRows >= 0)
			Assert.assertEquals("Unexpected row count:", expectedRows, rows);
        
        return rows;
	}
    
    /**
     * Assert that a column is nullable in its ResultSetMetaData.
     * Used when a utility method checking the contents of a
     * ResultSet sees a NULL value. If the value is NULL then
     * the column's definition in ResultSetMetaData must allow NULLs
     * (or not disallow NULLS).
     * @param rsmd Metadata of the ResultSet
     * @param col Position of column just fetched that was NULL.
     * @throws SQLException Error accessing meta data
     */
    private static void assertResultColumnNullable(java.sql.ResultSetMetaData rsmd, int col)
    throws SQLException
    {
        Assert.assertFalse(rsmd.isNullable(col) == java.sql.ResultSetMetaData.columnNoNulls); 
    }
	
    /**
     * Takes a result set and an array of expected colum names (as
     * Strings)  and asserts that the column names in the result
     * set metadata match the number, order, and names of those
     * in the array.
     *
     * @param rs ResultSet for which we're checking column names.
     * @param expectedColNames Array of expected column names.
     */
    public static void assertColumnNames(java.sql.ResultSet rs,
        String [] expectedColNames) throws SQLException
    {
    	java.sql.ResultSetMetaData rsmd = rs.getMetaData();
        int actualCols = rsmd.getColumnCount();

        Assert.assertEquals("Unexpected column count:",
            expectedColNames.length, rsmd.getColumnCount());

        for (int i = 0; i < actualCols; i++)
        {
            Assert.assertEquals("Column names do not match:",
                expectedColNames[i], rsmd.getColumnName(i+1));
        }
    }
    /**
     * Takes a result set and an array of expected column types
     * from java.sql.Types 
     * and asserts that the column types in the result
     * set metadata match the number, order, and names of those
     * in the array.
     * 
     * No length information for variable length types
     * can be passed. For ResultSets from JDBC DatabaseMetaData
     * the specification only indicates the types of the
     * columns, not the length.
     *
     * @param rs ResultSet for which we're checking column names.
     * @param expectedTypes Array of expected column types.
     */
    public static void assertColumnTypes(java.sql.ResultSet rs,
        int[] expectedTypes) throws SQLException
    {
    	java.sql.ResultSetMetaData rsmd = rs.getMetaData();
        int actualCols = rsmd.getColumnCount();

        Assert.assertEquals("Unexpected column count:",
                expectedTypes.length, rsmd.getColumnCount());

        for (int i = 0; i < actualCols; i++)
        {
            Assert.assertEquals("Column types do not match for column " + (i+1),
                    expectedTypes[i], rsmd.getColumnType(i+1));
        }
    }
    /**
     * Takes a Prepared Statement and an array of expected parameter types
     * from java.sql.Types 
     * and asserts that the parameter types in the ParamterMetaData
     * match the number and order of those
     * in the array.
     * @param ps PreparedStatement for which we're checking parameter names.
     * @param expectedTypes Array of expected parameter types.
     */
    public static void assertParameterTypes (PreparedStatement ps,
	        int[] expectedTypes) throws SQLException
	    {
		ParameterMetaData pmd = ps.getParameterMetaData();
	        int actualParams = pmd.getParameterCount();

	        Assert.assertEquals("Unexpected parameter count:",
	                expectedTypes.length, pmd.getParameterCount());

	        for (int i = 0; i < actualParams; i++)
	        {
	            Assert.assertEquals("Types do not match for parameter " + (i+1),
	                    expectedTypes[i], pmd.getParameterType(i+1));
	        }
	    }
    
    /**
     * Check the nullability of the column definitions for
     * the ResultSet matches the expected values.
     * @param rs
     * @param nullability
     * @throws SQLException 
     */
    public static void assertNullability(java.sql.ResultSet rs,
            boolean[] nullability) throws SQLException
    {
    	java.sql.ResultSetMetaData rsmd = rs.getMetaData();
        int actualCols = rsmd.getColumnCount();

        Assert.assertEquals("Unexpected column count:",
                nullability.length, rsmd.getColumnCount());

        for (int i = 0; i < actualCols; i++)
        {
            int expected = nullability[i] ?
            		java.sql.ResultSetMetaData.columnNullable : java.sql.ResultSetMetaData.columnNoNulls;
            Assert.assertEquals("Column nullability do not match for column " + (i+1),
                    expected, rsmd.isNullable(i+1));
        }       
    }
    /**
     * Asserts a ResultSet returns a single row with a single
     * column equal to the passed in String value. The value can
     * be null to indicate SQL NULL. The comparision is make
     * using assertFullResultSet in trimmed string mode.
     *  As a side effect, this method closes the ResultSet.
     */
    public static void assertSingleValueResultSet(java.sql.ResultSet rs,
            String value) throws SQLException
    {
        String[] row = new String[] {value};
        String[][] set = new String[][] {row};
        assertFullResultSet(rs, set);
    }
    
    /**
     * assertFullResultSet() using trimmed string comparisions.
     * Equal to
     * <code>
     * assertFullResultSet(rs, expectedRows, true)
     * </code>
     *  As a side effect, this method closes the ResultSet.
     */
    public static void assertFullResultSet(java.sql.ResultSet rs,
            String [][] expectedRows)
            throws SQLException
     {
        assertFullResultSet(rs, expectedRows, true);
     }

    /**
     * Takes a result set and a two-dimensional array and asserts
     * that the rows and columns in the result set match the number,
     * order, and values of those in the array.  Each row in
     * the array is compared with the corresponding row in the
     * result set. As a side effect, this method closes the ResultSet.
     *
     * Will throw an assertion failure if any of the following
     * is true:
     *
     *  1. Expected vs actual number of columns doesn't match
     *  2. Expected vs actual number of rows doesn't match
     *  3. Any column in any row of the result set does not "equal"
     *     the corresponding column in the expected 2-d array.  If
     *     "allAsTrimmedStrings" is true then the result set value
     *     will be retrieved as a String and compared, via the ".equals()"
     *     method, to the corresponding object in the array (with the
     *     assumption being that the objects in the array are all 
     *     Strings).  Otherwise the result set value will be retrieved
     *     and compared as an Object, which is useful when asserting
     *     the JDBC types of the columns in addition to their values.
     *
     * NOTE: It follows from #3 that the order of the rows in the
     * in received result set must match the order of the rows in
     * the received 2-d array.  Otherwise the result will be an
     * assertion failure.
     *
     * @param rs The actual result set.
     * @param expectedRows 2-Dimensional array of objects representing
     *  the expected result set.
     * @param allAsTrimmedStrings Whether or not to fetch (and compare)
     *  all values from the actual result set as trimmed Strings; if
     *  false the values will be fetched and compared as Objects.  For
     *  more on how this parameter is used, see assertRowInResultSet().
     */
    public static void assertFullResultSet(java.sql.ResultSet rs,
        Object [][] expectedRows, boolean allAsTrimmedStrings)
        throws SQLException
    {
        assertFullResultSet( rs, expectedRows, allAsTrimmedStrings, true );
    }

    /**
     * Takes a result set and a two-dimensional array and asserts
     * that the rows and columns in the result set match the number,
     * order, and values of those in the array.  Each row in
     * the array is compared with the corresponding row in the
     * result set.
     *
     * Will throw an assertion failure if any of the following
     * is true:
     *
     *  1. Expected vs actual number of columns doesn't match
     *  2. Expected vs actual number of rows doesn't match
     *  3. Any column in any row of the result set does not "equal"
     *     the corresponding column in the expected 2-d array.  If
     *     "allAsTrimmedStrings" is true then the result set value
     *     will be retrieved as a String and compared, via the ".equals()"
     *     method, to the corresponding object in the array (with the
     *     assumption being that the objects in the array are all 
     *     Strings).  Otherwise the result set value will be retrieved
     *     and compared as an Object, which is useful when asserting
     *     the JDBC types of the columns in addition to their values.
     *
     * NOTE: It follows from #3 that the order of the rows in the
     * in received result set must match the order of the rows in
     * the received 2-d array.  Otherwise the result will be an
     * assertion failure.
     *
     * @param rs The actual result set.
     * @param expectedRows 2-Dimensional array of objects representing
     *  the expected result set.
     * @param allAsTrimmedStrings Whether or not to fetch (and compare)
     *  all values from the actual result set as trimmed Strings; if
     *  false the values will be fetched and compared as Objects.  For
     *  more on how this parameter is used, see assertRowInResultSet().
     * @param closeResultSet If true, the ResultSet is closed on the way out.
     */
    public static void assertFullResultSet(java.sql.ResultSet rs,
        Object [][] expectedRows, boolean allAsTrimmedStrings, boolean closeResultSet)
        throws SQLException
    {
        int rows;
        java.sql.ResultSetMetaData rsmd = rs.getMetaData();

        // Assert that we have the right number of columns. If we expect an
        // empty result set, the expected column count is unknown, so don't
        // check.
        if (expectedRows.length > 0) {
            Assert.assertEquals("Unexpected column count:",
                expectedRows[0].length, rsmd.getColumnCount());
        }

        for (rows = 0; rs.next(); rows++)
        {
            /* If we have more actual rows than expected rows, don't
             * try to assert the row.  Instead just keep iterating
             * to see exactly how many rows the actual result set has.
             */
            if (rows < expectedRows.length)
            {
                assertRowInResultSet(rs, rows + 1,
                    expectedRows[rows], allAsTrimmedStrings);
            }
        }

        if ( closeResultSet ) { rs.close(); }

        // And finally, assert the row count.
        Assert.assertEquals("Unexpected row count:", expectedRows.length, rows);
    }

    /**
     * Similar to assertFullResultSet(...) above, except that this
     * method takes a BitSet and checks the received expectedRows
     * against the columns referenced by the BitSet.  So the assumption
     * here is that expectedRows will only have as many columns as
     * there are "true" bits in the received BitSet.
     *
     * This method is useful when we expect there to be a specific
     * ordering on some column OC in the result set, but do not care
     * about the ordering of the non-OC columns when OC is the
     * same across rows.  Ex.  If we have the following results with
     * an expected ordering on column J:
     *
     *   I    J
     *   -    -
     *   a    1
     *   b    1
     *   c    2
     *   c    2
     *
     * Then this method allows us to verify that J is sorted as
     * "1, 1, 2, 2" without having to worry about whether or not
     * (a,1) comes before (b,1).  The caller would simply pass in
     * a BitSet whose content was {1} and an expectedRows array
     * of {{"1"},{"1"},{"2"},{"2"}}.
     *
     * For now this method always does comparisons with
     * "asTrimmedStrings" set to true, and always closes
     * the result set.
     */
    public static void assertPartialResultSet(java.sql.ResultSet rs,
        Object [][] expectedRows, BitSet colsToCheck)
        throws SQLException
    {
        int rows;

        // Assert that we have the right number of columns. If we expect an
        // empty result set, the expected column count is unknown, so don't
        // check.
        if (expectedRows.length > 0) {
            Assert.assertEquals("Unexpected column count:",
                expectedRows[0].length, colsToCheck.cardinality());
        }

        for (rows = 0; rs.next(); rows++)
        {
            /* If we have more actual rows than expected rows, don't
             * try to assert the row.  Instead just keep iterating
             * to see exactly how many rows the actual result set has.
             */
            if (rows < expectedRows.length)
            {
                assertRowInResultSet(rs, rows + 1,
                    expectedRows[rows], true, colsToCheck);
            }
        }

        rs.close();

        // And finally, assert the row count.
        Assert.assertEquals("Unexpected row count:", expectedRows.length, rows);
    }

    /**
     * Assert that every column in the current row of the received
     * result set matches the corresponding column in the received
     * array.  This means that the order of the columns in the result
     * set must match the order of the values in expectedRow.
     *
     * <p>
     * If the expected value for a given row/column is a SQL NULL,
     * then the corresponding value in the array should be a Java
     * null.
     *
     * <p>
     * If a given row/column could have different values (for instance,
     * because it contains a timestamp), the expected value of that
     * row/column could be an object whose <code>equals()</code> method
     * returns <code>true</code> for all acceptable values. (This does
     * not work if one of the acceptable values is <code>null</code>.)
     *
     * @param rs Result set whose current row we'll check.
     * @param rowNum Row number (w.r.t expected rows) that we're
     *  checking.
     * @param expectedRow Array of objects representing the expected
     *  values for the current row.
     * @param asTrimmedStrings Whether or not to fetch and compare
     *  all values from "rs" as trimmed Strings.  If true then the
     *  value from rs.getString() AND the expected value will both
     *  be trimmed before comparison.  If such trimming is not
     *  desired (ex. if we were testing the padding of CHAR columns)
     *  then this param should be FALSE and the expected values in
     *  the received array should include the expected whitespace.  
     *  If for example the caller wants to check the padding of a
     *  CHAR(8) column, asTrimmedStrings should be FALSE and the
     *  expected row should contain the expected padding, such as
     *  "FRED    ".
     */
    private static void assertRowInResultSet(java.sql.ResultSet rs, int rowNum,
        Object [] expectedRow, boolean asTrimmedStrings) throws SQLException
    {
        assertRowInResultSet(
            rs, rowNum, expectedRow, asTrimmedStrings, (BitSet)null);
    }

    /**
     * See assertRowInResultSet(...) above.
     *
     * @param colsToCheck If non-null then for every bit b
     *   that is set in colsToCheck, we'll compare the (b+1)-th column
     *   of the received result set's current row to the i-th column
     *   of expectedRow, where 0 <= i < # bits set in colsToCheck.
     *   So if colsToCheck is { 0, 3 } then expectedRow should have
     *   two objects and we'll check that:
     *
     *     expectedRow[0].equals(rs.getXXX(1));
     *     expectedRow[1].equals(rs.getXXX(4));
     *
     *   If colsToCheck is null then the (i+1)-th column in the
     *   result set is compared to the i-th column in expectedRow,
     *   where 0 <= i < expectedRow.length.
     */
    private static void assertRowInResultSet(java.sql.ResultSet rs,
        int rowNum, Object [] expectedRow, boolean asTrimmedStrings,
        BitSet colsToCheck) throws SQLException
    {
        int cPos = 0;
        java.sql.ResultSetMetaData rsmd = rs.getMetaData();
        for (int i = 0; i < expectedRow.length; i++)
        {
            cPos = (colsToCheck == null)
                ? (i+1)
                : colsToCheck.nextSetBit(cPos) + 1;

            Object obj;
            if (asTrimmedStrings)
            {
                // Trim the expected value, if non-null.
                if (expectedRow[i] != null)
                    expectedRow[i] = ((String)expectedRow[i]).trim();

                /* Different clients can return different values for
                 * boolean columns--namely, 0/1 vs false/true.  So in
                 * order to keep things uniform, take boolean columns
                 * and get the JDBC string version.  Note: since
                 * Derby doesn't have a BOOLEAN type, we assume that
                 * if the column's type is SMALLINT and the expected
                 * value's string form is "true" or "false", then the
                 * column is intended to be a mock boolean column.
                 */
                if ((expectedRow[i] != null)
                    && (rsmd.getColumnType(cPos) == Types.SMALLINT))
                {
                    String s = expectedRow[i].toString();
                    if (s.equals("true") || s.equals("false"))
                        obj = (rs.getShort(cPos) == 0) ? "false" : "true";
                    else
                        obj = rs.getString(cPos);
                        
                }
                else
                {
                    obj = rs.getString(cPos);

                }
                
                // Trim the rs string.
                if (obj != null)
                    obj = ((String)obj).trim();

            }
            else
                obj = rs.getObject(cPos);

            boolean ok = (rs.wasNull() && (expectedRow[i] == null))
                || (!rs.wasNull()
                    && (expectedRow[i] != null)
                    && (expectedRow[i].equals(obj)
                        || (obj instanceof byte[] // Assumes byte arrays
                            && Arrays.equals((byte[] )obj,
                                             (byte[] )expectedRow[i])
// GemStone changes BEGIN
                        || (obj instanceof Blob && Arrays.equals(((Blob)obj)
                            .getBytes(1L, (int)((Blob)obj).length()),
                            (byte[])expectedRow[i]))
                        || (obj instanceof Clob
                            && ((Clob)obj).getSubString(1, (int)((Clob)obj)
                                .length()).equals(expectedRow[i])))));
// GemStone changes END
            if (!ok)
            {
                Object expected = expectedRow[i];
                Object found = obj;
                if (obj instanceof byte[]) {
                    expected = bytesToString((byte[] )expectedRow[i]);
                    found = bytesToString((byte[] )obj);
                }
                Assert.fail("Column value mismatch @ column '" +
                    rsmd.getColumnName(cPos) + "', row " + rowNum +
                    ":\n    Expected: >" + expected +
                    "<\n    Found:    >" + found + "<");
            }
            
            if (rs.wasNull())
                assertResultColumnNullable(rsmd, cPos);

        }
    }
    
    /**
     * Assert two result sets have the same contents.
     * MetaData is determined from rs1, thus if rs2 has extra
     * columns they will be ignored. The metadata for the
     * two ResultSets are not compared.
     * <BR>
     * The compete ResultSet is walked for both ResultSets,
     * and they are both closed.
     * <BR>
     * Columns are compared as primitive ints or longs, Blob,
     * Clobs or as Strings.
     * @throws IOException 
     */
    // GemStone changes BEGIN
    // ResultSets do not need to have the rows in the same order
    // to be equivalent. An unordered comparison is especially needed
    // in GemFireXD since rows are not selected in the same order they
    // are inserted. Re-writing this method to not assume same row
    // order.
    public static void assertSameContents(java.sql.ResultSet rs1, java.sql.ResultSet rs2)
            throws SQLException {
        try {
          List<List<Object>> rs1RowList = createListOfRows(rs1);
          List<List<Object>> rs2RowList = createListOfRows(rs2);
          Assert.assertEquals(rs1RowList.size(), rs2RowList.size());
          rs2RowList.removeAll(rs1RowList);
          Assert.assertTrue(rs2RowList.isEmpty());
        } finally {
          rs1.close();
          rs2.close();
        }
    }
    
    /**
     * Helper method for assertSameContents
     * 
     * @author Eric Zoerner
     * @param rs a ResultSet
     * @return list of rows
     * @throws SQLException
     */
    private static List<List<Object>> createListOfRows(java.sql.ResultSet rs)
    throws SQLException {
    	java.sql.ResultSetMetaData rsmd = rs.getMetaData();
      int columnCount = rsmd.getColumnCount();
      List<List<Object>> listOfRows = new ArrayList<List<Object>>();
      while (rs.next()) {
        List<Object> row = new ArrayList<Object>(columnCount);
        for (int col = 1; col <= columnCount; col++) {
              switch (rsmd.getColumnType(col)) {
                case Types.SMALLINT:
                case Types.INTEGER:
                  row.add(rs.getInt(col));
                  break;
                case Types.BIGINT:
                  row.add(rs.getLong(col));
                  break;
                case Types.BLOB:
                  row.add(new BlobComparator(rs.getBlob(col)));
                  break;
                case Types.CLOB:
                  row.add(new ClobComparator(rs.getClob(col)));
                  break;
                default:
                  row.add(rs.getString(col));
                  break;
              }
              if (rs.wasNull()) row.add(null);
              listOfRows.add(row);
        }
      }
      return listOfRows;
    }
    
    /**
     * Helper class for comparing Blob equality
     * @author ezoerner
     *
     */
    static class BlobComparator {
      private Blob b;
      
      BlobComparator(Blob b) {
        this.b = b;
      }
      
      public int hashCode() {
        return this.b.hashCode();
      }
      
      public boolean equals(Object other) {
        if (super.equals(other)) return true;
        if (!(other instanceof BlobComparator)) return false;
        try {
          Blob b1 = this.b;
          Blob b2 = ((BlobComparator)other).b;
          if (b1 == null) return b2 == null;
          if (b2 == null) return b1 == null;
          if (b1.length() != b2.length()) return false;
          InputStream is1 = b1.getBinaryStream();
          InputStream is2 = b2.getBinaryStream();
          if (is1 == null) return is2 == null;
          if (is2 == null) return is1 == null;

          // wrap buffered stream around the binary stream
          is1 = new BufferedInputStream(is1);
          is2 = new BufferedInputStream(is2);
          int by1 = is1.read();
          int by2 = is2.read();
          do {
            if (by1 != by2) return false;
            by1 = is1.read();
            by2 = is2.read();
          } while ( by1 != -1 || by2 != -1);
          is1.close();
          is2.close();
          return true;
        } catch (SQLException sqle) {
          AssertionFailedError ae = new AssertionFailedError();
          ae.initCause(ae);
          throw ae;
        } catch (IOException ioe) {
          AssertionFailedError ae = new AssertionFailedError();
          ae.initCause(ioe);
          throw ae;
        }
      }
    }
    
    /**
     * Helper class for comparing Clob equality
     * @author ezoerner
     *
     */
    static class ClobComparator {
      private Clob b;
      
      ClobComparator(Clob b) {
        this.b = b;
      }
      
      public int hashCode() {
        return this.b.hashCode();
      }
      
      public boolean equals(Object other) {
        if (super.equals(other)) return true;
        if (!(other instanceof ClobComparator)) return false;
        try {
          Clob b1 = this.b;
          Clob b2 = ((ClobComparator)other).b;
          if (b1 == null) return b2 == null;
          if (b2 == null) return b1 == null;
          if (b1.length() != b2.length()) return false;
          Reader is1 = b1.getCharacterStream();
          Reader is2 = b2.getCharacterStream();
          if (is1 == null || is2 == null) return false;

          // wrap buffered reader around the character stream
          is1 = new BufferedReader(is1);
          is2 = new BufferedReader(is2);
          int by1 = is1.read();
          int by2 = is2.read();
          do {
            if (by1 != by2) return false;
            by1 = is1.read();
            by2 = is2.read();
          } while ( by1 != -1 || by2 != -1);
          is1.close();
          is2.close();
          return true;
        } catch (SQLException sqle) {
          AssertionFailedError ae = new AssertionFailedError();
          ae.initCause(ae);
          throw ae;
        } catch (IOException ioe) {
          AssertionFailedError ae = new AssertionFailedError();
          ae.initCause(ioe);
          throw ae;
        }
      }
    }
    // GemStone changes END
    
    
    /**
     * Assert that the ResultSet contains the same rows as the specified
     * two-dimensional array. The order of the results is ignored. Convert the
     * results to trimmed strings before comparing. The ResultSet object will
     * be closed.
     *
     * @param rs the ResultSet to check
     * @param expectedRows the expected rows
     */
    public static void assertUnorderedResultSet(
    		java.sql.ResultSet rs, String[][] expectedRows) throws SQLException {
        assertUnorderedResultSet(rs, expectedRows, true);
    }

    /**
     * Assert that the ResultSet contains the same rows as the specified
     * two-dimensional array. The order of the results is ignored. Objects are
     * read out of the ResultSet with the <code>getObject()</code> method and
     * compared with <code>equals()</code>. If the
     * <code>asTrimmedStrings</code> is <code>true</code>, the objects are read
     * with <code>getString()</code> and trimmed before they are compared. The
     * ResultSet object will be closed when this method returns.
     *
     * @param rs the ResultSet to check
     * @param expectedRows the expected rows
     * @param asTrimmedStrings whether the object should be compared as trimmed
     * strings
     */
    public static void assertUnorderedResultSet(
    		java.sql.ResultSet rs, Object[][] expectedRows, boolean asTrimmedStrings)
                throws SQLException {
// GemStone changes BEGIN
      assertUnorderedResultSet(rs, expectedRows, asTrimmedStrings, false);
    }

    /**
     * Assert that the ResultSet contains the same rows as the specified
     * two-dimensional array. The order of the results is ignored. Objects are
     * read out of the ResultSet with the <code>getObject()</code> method and
     * compared with <code>equals()</code>. If the
     * <code>asTrimmedStrings</code> is <code>true</code>, the objects are read
     * with <code>getString()</code> and trimmed before they are compared. The
     * ResultSet object will be closed when this method returns.
     *
     * @param rs the ResultSet to check
     * @param expectedRows the expected rows
     * @param asTrimmedStrings whether the object should be compared as trimmed
     * strings
     * @param booleansAsInts when using client driver also allow for 0/1 values
     * for boolean values
     */
    public static void assertUnorderedResultSet(
    		java.sql.ResultSet rs, Object[][] expectedRows, boolean asTrimmedStrings,
            boolean booleansAsInts)
                throws SQLException {
// GemStone changes END

        if (expectedRows.length == 0) {
            assertEmpty(rs);
            return;
        }

        java.sql.ResultSetMetaData rsmd = rs.getMetaData();
        Assert.assertEquals("Unexpected column count",
                            expectedRows[0].length, rsmd.getColumnCount());

        ArrayList expected = new ArrayList(expectedRows.length);
        for (int i = 0; i < expectedRows.length; i++) {
            Assert.assertEquals("Different column count in expectedRows",
                                expectedRows[0].length, expectedRows[i].length);
            if (asTrimmedStrings) {
                ArrayList row = new ArrayList(expectedRows[i].length);
                for (int j = 0; j < expectedRows[i].length; j++) {
                    String val = (String) expectedRows[i][j];
                    row.add(val == null ? null : val.trim());
                }
                expected.add(row);
            } else {
// GemStone changes BEGIN
              // change byte[]'s to hex strings for proper comparison
              final Object[] objs = expectedRows[i];
              final ArrayList<Object> row = new ArrayList<Object>(objs.length);
              for (Object o : objs) {
                if (o instanceof byte[]) {
                  final byte[] b = (byte[])o;
                  row.add(ClientSharedUtils.toHexString(b, 0, b.length));
                }
                else if (o instanceof Blob) {
                  final Blob blob = (Blob)o;
                  final byte[] b = blob.getBytes(1L, (int)blob.length());
                  row.add(ClientSharedUtils.toHexString(b, 0, b.length));
                }
                else if (o instanceof Clob) {
                  final Clob clob = (Clob)o;
                  row.add(((Clob)o).getSubString(1, (int)clob.length()));
                }
                else {
                  row.add(o);
                }
              }
              expected.add(row);
                /* (original code)
                expected.add(Arrays.asList(expectedRows[i]));
                */
// GemStone changes END
            }
        }

        ArrayList actual = new ArrayList(expectedRows.length);
// GemStone changes BEGIN
        ArrayList<Object> actual2 = new ArrayList<Object>(expectedRows.length);
// GemStone changes END
        while (rs.next()) {
            ArrayList row = new ArrayList(expectedRows[0].length);
// GemStone changes BEGIN
            ArrayList<Object> row2 = new ArrayList<Object>(
                expectedRows[0].length);
            actual2.add(row2);
// GemStone changes END
            for (int i = 1; i <= expectedRows[0].length; i++) {
                if (asTrimmedStrings) {
                    String s = rs.getString(i);
// GemStone changes BEGIN
                    // with client driver booleans come as smallints (DERBY-4613)
                    // get rid of this once #41029 is fixed
                    String s2 = s;
                    if (booleansAsInts && s != null && s.length() == 1
                        && rsmd.getColumnType(i) == java.sql.Types.SMALLINT) {
                      if (s.charAt(0) == '0') {
                        s2 = "false";
                      }
                      else if (s.charAt(0) == '1') {
                        s2 = "true";
                      }
                    }
                    row2.add(s2 == null ? null : s2.trim());
// GemStone changes END
                    row.add(s == null ? null : s.trim());
                } else {
// GemStone changes BEGIN
                    // change byte[]'s to hex strings for proper comparison
                    final Object o = rs.getObject(i);
                    if (o instanceof byte[]) {
                      final byte[] b = (byte[])o;
                      row.add(ClientSharedUtils.toHexString(b, 0, b.length));
                    }
                    else if (o instanceof Blob) {
                      final Blob blob = (Blob)o;
                      final byte[] b = blob.getBytes(1L, (int)blob.length());
                      row.add(ClientSharedUtils.toHexString(b, 0, b.length));
                    }
                    else if (o instanceof Clob) {
                      final Clob clob = (Clob)o;
                      row.add(((Clob)o).getSubString(1, (int)clob.length()));
                    }
                    else {
                      row.add(o);
                    }
                    /* (original code)
                    row.add(rs.getObject(i));
                    */
// GemStone changes END
                }
                if (rs.wasNull())
                    assertResultColumnNullable(rsmd, i);
            }
            actual.add(row);
        }
        rs.close();

// GemStone changes BEGIN
        if (actual.size() != expectedRows.length) {
          Assert.fail("Unexpected row count " + actual.size() + "{" + actual
                      + "}, expected=" + expectedRows.length);
        }
        if (booleansAsInts && asTrimmedStrings) {
          if (!actual2.containsAll(expected)) {
            // check in the second set
            Assert.assertTrue("Missing rows in ResultSet " + actual
                + ", expected: " + expected,
                actual.containsAll(expected));
          }
          else {
            actual = actual2;
          }
        }
        else {
          Assert.assertTrue("Missing rows in ResultSet " + actual
              + ", expected: " + expected, actual.containsAll(expected));
        }
        actual.removeAll(expected);
        Assert.assertTrue("Extra rows in ResultSet: " + (actual.size() > 0
            ? ArrayUtils.objectString(actual.get(0)) : ""), actual.isEmpty());
        /* (original code)
        Assert.assertEquals("Unexpected row count",
                            expectedRows.length, actual.size());

        Assert.assertTrue("Missing rows in ResultSet",
                          actual.containsAll(expected));

        actual.removeAll(expected);
        Assert.assertTrue("Extra rows in ResultSet", actual.isEmpty());
        */
// GemStone changes END
    }

    /**
     * Asserts that the current schema is the same as the one specified.
     *
     * @param con connection to check schema in
     * @param schema expected schema name
     * @throws SQLException if something goes wrong
     */
    public static void assertCurrentSchema(Connection con, String schema)
            throws SQLException {
        Statement stmt = con.createStatement();
        try {
            JDBC.assertSingleValueResultSet(
                    stmt.executeQuery("VALUES CURRENT SCHEMA"), schema);
        } finally {
            stmt.close();
        }
    }

    /**
     * Convert byte array to String.
     * Each byte is converted to a hexadecimal string representation.
     *
     * @param ba Byte array to be converted.
     * @return Hexadecimal string representation. Returns null on null input.
     */
    private static String bytesToString(byte[] ba)
    {
        if (ba == null) return null;
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < ba.length; ++i) {
            s.append(Integer.toHexString(ba[i] & 0x00ff));
        }
        return s.toString();
    }

	/**
	 * Escape a non-qualified name so that it is suitable
	 * for use in a SQL query executed by JDBC.
	 */
	public static String escape(String name)
	{
	  // GemStone changes BEGIN
	  // need to double internal quotes
	  name = doubleInternalDoubleQuotes(name);
	  // GemStone changes END
		return "\"" + name + "\"";
	}	
	/**
	 * Escape a schama-qualified name so that it is suitable
	 * for use in a SQL query executed by JDBC.
	 */
	public static String escape(String schema, String name)
	{
    // GemStone changes BEGIN
    // need to double internal quotes
    name = doubleInternalDoubleQuotes(name);
    schema = doubleInternalDoubleQuotes(schema);
    // GemStone changes END
		return "\"" + schema + "\".\"" + name + "\"";
	}
	
	// GemStone changes BEGIN
	public static String doubleInternalDoubleQuotes(String s) {
	  return s.replace("\"", "\"\"");
	}
	// GemStone changes END
         
        /**
         * Return Type name from jdbc type
         * 
         * @param jdbcType  jdbc type to translate
         */
        public static String sqlNameFromJdbc(int jdbcType) {
            switch (jdbcType) {
            case Types.BIT          :  return "Types.BIT";
            case Types.BOOLEAN  : return "Types.BOOLEAN";
            case Types.TINYINT      :  return "Types.TINYINT";
            case Types.SMALLINT     :  return "SMALLINT";
            case Types.INTEGER      :  return "INTEGER";
            case Types.BIGINT       :  return "BIGINT";
            
            case Types.FLOAT        :  return "Types.FLOAT";
            case Types.REAL         :  return "REAL";
            case Types.DOUBLE       :  return "DOUBLE";
            
            case Types.NUMERIC      :  return "Types.NUMERIC";
            case Types.DECIMAL      :  return "DECIMAL";
            
            case Types.CHAR         :  return "CHAR";
            case Types.VARCHAR      :  return "VARCHAR";
            case Types.LONGVARCHAR  :  return "LONG VARCHAR";
            case Types.CLOB         :  return "CLOB";
            
            case Types.DATE         :  return "DATE";
            case Types.TIME         :  return "TIME";
            case Types.TIMESTAMP    :  return "TIMESTAMP";
            
            case Types.BINARY       :  return "CHAR () FOR BIT DATA";
            case Types.VARBINARY    :  return "VARCHAR () FOR BIT DATA";
            case Types.LONGVARBINARY:  return "LONG VARCHAR FOR BIT DATA";
            case Types.BLOB         :  return "BLOB";

            case Types.OTHER        :  return "Types.OTHER";
            case Types.NULL         :  return "Types.NULL";
            default : return String.valueOf(jdbcType);
                }
        }

    //Gemstone changes BEGIN
    // SQL Unit Test Helper function
    // Given a statement class, and a two-dimensional array of statements and expected results/sqlcodes, 
    // verify the results by executing all the statements in the array in sequence
    // Throw an assertion on the first mismatching result
    public static void SQLUnitTestHelper(Statement stmt, Object[][] SQLToRun) throws Exception
    {
      final boolean isOffHeap ;
      
      //Disable offheap checks for multi vm tests like dunit tests
      int numMembers = 1;
      InternalDistributedSystem ids = InternalDistributedSystem.getConnectedInstance();
      if(ids != null) {
        numMembers += ids.getAllOtherMembers().size();
      }
      SimpleMemoryAllocatorImpl allocator ;
      try {
        allocator= SimpleMemoryAllocatorImpl.getAllocator();
      }catch(Exception ignore) {
        allocator = null;
      }
      RegionMapClearDetector rmcd = null;
      isOffHeap = allocator != null && numMembers == 1;
      if(isOffHeap) {
        LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
        rmcd = new RegionMapClearDetector();
        CacheObserverHolder.setInstance(rmcd);
        GemFireXDQueryObserverHolder.setInstance(rmcd);
        
      }
      try {
      for (int stmtNumber = 0; stmtNumber < SQLToRun.length; stmtNumber++)
      {    	 
    	  String stmtToExecute = (String)SQLToRun[stmtNumber][0];
    	  Object resultExpected = SQLToRun[stmtNumber][1];
    	  //The below logic of checking free memory will work correctly only if tables have non lob
    	  //because for lob columns, even update can change the free sizes. So only non lob
    	  // tests have tables which are off heap.
    	 
    	    boolean haveResults = false;         // Do we have results from the statement?
          boolean foundResultSetError = false; // Did we find the error in the result set?
          boolean expectOrdered = false;       // Are results expected in a particular order?
          final  long refCountBefore;
          long refCountAfter;
          if( isOffHeap) {
            refCountBefore = JDBC.getTotalRefCount(allocator);
          }else {
            refCountBefore = 0;
          }
          int caseType = -1;
          if(stmtToExecute.toLowerCase().startsWith("insert")) {
            caseType = 0;
          }else if(stmtToExecute.toLowerCase().startsWith("update")) {
            caseType =1;
          }else if(stmtToExecute.toLowerCase().startsWith("select")) {
            caseType =2;
          }else if(stmtToExecute.toLowerCase().startsWith("delete")) {
            caseType =3;
          }else if(stmtToExecute.toLowerCase().startsWith("noexec")) {
            continue;
          }
          // TODO : this sometimes gives incorrect results for
          // queries which ORDER BY some of the columns but not all of them
          //if (stmtToExecute.contains("order by") || stmtToExecute.contains("ORDER BY"))
          //{
        	//  expectOrdered = true;
          //}
          // Write out the statement and ordinal numberfor ease of debugging, 
          //    plus multidimensional arrays as a string for resultsets
          System.out.print("Running statement#" +(stmtNumber+1)+" : "+stmtToExecute+", expected " + 
              (expectOrdered?"ordered":"unordered") + " results = "+
              (resultExpected instanceof String[][]?Arrays.deepToString((String[][])resultExpected):resultExpected));
          System.out.println();
          if (stmtNumber + 1 == 159) {
            System.out.println("debug");
          }
          try
          {
        	  haveResults = stmt.execute(stmtToExecute);
        	
        	  // Do we have results? 
        	  if (haveResults)
        	  {
        		  // Did we expect results? Or a SQL state? Did we get one?
        		  if (resultExpected == null)
        		  {
        			  Assert.fail ("Statement "+stmtToExecute+" returned results but we expected success and zero results!");
        		  }
        		  else if (resultExpected instanceof String)
        		  {
        			  // Maybe we have a result set, check to see if somewhere in result set lies
        			  // this error (i.e. overflow, math error)
        			  java.sql.ResultSet rsTmp = stmt.getResultSet();
        			  if (rsTmp != null) {
        				  try
        				  {
        					  assertDrainResults(rsTmp, -1);
        				  }
        				  catch (SQLException e)
        				  {
                                                 try{
        					  Assert.assertEquals(resultExpected,  e.getSQLState());
        					  // We found an error, and it was the one we were looking for
        					  foundResultSetError=true;
                                                 }catch(AssertionFailedError afe)  {
                                                    throw new AssertionFailedError(afe.toString() + "for statement = "+ stmtToExecute);
                                                 }
        				  }
        			  }
        			  if (foundResultSetError==false)
        			  {
        				  // we didn't find any error at all in the result set but should have
        				  Assert.fail ("Statement "+stmtToExecute+" expected a SQLCODE "+resultExpected+" but succeeded and returned results with no SQLCODE!");
        			  }
        		  }
        		  else
        		  {
        		    // Verify the results, now that we know we expected a result set and we got one
        			  java.sql.ResultSet rs = stmt.getResultSet();
        		    //resultExpected is a 2D array of strings, columns and rows
        		    // Empty result sets are also checked by assertUnorderedResultSet()
        		    // check for ordered results if original stmt had ORDER BY in it
                            try {
        		      if (expectOrdered == false)
        		      {
        		        assertUnorderedResultSet(rs,(String[][])resultExpected);
        		      }
        		      else
        		      {
        		    	assertFullResultSet(rs, (String[][])resultExpected);
        		      }
                            }catch(AssertionFailedError afe)  {
                              throw new AssertionFailedError(afe.toString() + "for statement = "+ stmtToExecute);
                                                 
        		    }
                         }
        	  }
        	  // Else, we have no results, and no exception. resultExpected better be null
        	  else if (resultExpected != null)
        	  {
        		  Assert.fail ("Statement "+stmtToExecute+" returned no results and success, but we expected results looking like " + resultExpected);
        	  }
        	  
        	  if(isOffHeap) {
        	    refCountAfter = JDBC.getTotalRefCount(allocator);
        	     if(caseType == 0 ) {
        	       //it is insert, so 
        	       int numInserted = stmt.getUpdateCount();
        	       Assert.assertTrue("The offheap in use count before and after  statement execution are not conserved",
                     refCountAfter >= refCountBefore);
        	       Assert.assertTrue("The offheap in use count before and after  statement execution are not conserved",
                     refCountAfter - refCountBefore <= numInserted);
        	     }else if(caseType == 3) {
                 //it is delete, so 
        	       int numDeleted = stmt.getUpdateCount();
                 Assert.assertTrue("The offheap in use count before and after  statement execution are not conserved",
                     refCountAfter <= refCountBefore);
                 Assert.assertTrue("The offheap in use count before and after  statement execution are not conserved",
                      refCountBefore - refCountAfter <= numDeleted);
               }else if(caseType == 1 || caseType == 2) {
                 Assert.assertEquals("The offheap in use count before and after  statement execution are unequal",
                     refCountBefore,refCountAfter );
               }
        	     
        	  }else {
        	    refCountAfter = 0;
        	  }
        	  
        	

          }
          catch (SQLException e) {
        	  // We caught an exception during EXECUTE of the statement (before result matching)
        	  // If we were expecting an exception, does it match?
        	  if ((resultExpected != null) && (resultExpected instanceof String))
        	  {
                        try {
        		  Assert.assertEquals(resultExpected,e.getSQLState());
                         }catch(AssertionFailedError afe)  {
                              throw new AssertionFailedError(afe.toString() + "for statement = "+ stmtToExecute);
                         }                         
        		    
        	  }
        	  else
        	  {
        		  // We got an exception when we didn't expect one. Throw it again.
        		  throw new RuntimeException("Failed statement="+stmtToExecute,e);
        	  }
          }finally {
        	//For drop table, since the map clear happens asynchronously
        	// the live chunk count may get changed to 0 , while we are asserting
        	  // do skip the live chunk check for drop table
            if(isOffHeap ) {
              rmcd.waitTillAllClear(); 	
              assertLiveChunksAndRegionEntryValidity(allocator); 
            }
          }
      }
      }finally {
    	  LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
    	  CacheObserverHolder.setInstance(null);
    	  GemFireXDQueryObserverHolder.clearInstance();
      }
    }

  // Gemstone changes END
  public static int getTotalRefCount(SimpleMemoryAllocatorImpl ma) {
    int totalRefCount = 0;
    if (ma != null) {
      List<Chunk> chunks = ma.getFreeList().getLiveChunks();
      for (Chunk chunk : chunks) {
        totalRefCount += chunk.getRefCount();
      }
    }
    return totalRefCount;
  }

  public static void assertLiveChunksAndRegionEntryValidity(
      SimpleMemoryAllocatorImpl ma) {
    if (ma != null) {
      List<Chunk> chunks = ma.getFreeList().getLiveChunks();
      for (Chunk chunk : chunks) {
        Assert.assertEquals(1, chunk.getRefCount());
      }
    }
    GemFireCacheImpl cache = Misc.getGemFireCache();
    Set<LocalRegion> regions = cache.getAllRegions();
    Iterator<LocalRegion> rgnIter = regions.iterator();
    while (rgnIter.hasNext()) {
      LocalRegion lr = rgnIter.next();
      if (lr.getEnableOffHeapMemory()) {
        if (lr instanceof PartitionedRegion) {
          if (((PartitionedRegion)lr).entryCount(true) < 1) {
            continue;
          }
        } else {
          if (lr.entryCount() < 1) {
            continue;
          }
        }
        Iterator<?> keys = lr.keys().iterator();
        while (keys.hasNext()) {
          Object key = keys.next();
          OffHeapRegionEntry ohEntry = (OffHeapRegionEntry) lr
              .basicGetEntry(key);
          boolean doFree = false;
          try {
            // check there is valid value
            if (ohEntry != null) {
              long address = ohEntry.getAddress();
              if (!OffHeapRegionEntryHelper.isAddressInvalidOrRemoved(address)) {
                SimpleMemoryAllocatorImpl.skipRefCountTracking();
                Object val = OffHeapRegionEntryHelper._getValueRetain(ohEntry,
                    false);
                SimpleMemoryAllocatorImpl.unskipRefCountTracking();

                Assert.assertFalse(Token.isInvalidOrRemoved(val));
                if (val instanceof Chunk) {
                  doFree = true;
                  Assert.assertEquals(2, ((Chunk)val).getRefCount());
                }
              }

            }
          } finally {
            if (doFree) {
              SimpleMemoryAllocatorImpl.skipRefCountTracking();
              Chunk.release(ohEntry.getAddress(), true);
              SimpleMemoryAllocatorImpl.unskipRefCountTracking();
            }
          }
        }

      }
    }

    // Check all the OffHeapEntries have valid address.

  }
  
  private static class RegionMapClearDetector extends CacheObserverAdapter 
  implements GemFireXDQueryObserver{
	private int numExpectedRegionClearCalls = 0;
	private int currentCallCount = 0;

		@Override
		public void testExecutionEngineDecision(QueryInfo queryInfo,
	  ExecutionEngineRule.ExecutionEngine engine, String queryText) {
		}

		private void setNumExpectedRegionClearCalls(int numExpected) {
	  this.numExpectedRegionClearCalls = numExpected;
	  this.currentCallCount = 0;
	}
	
	public void waitTillAllClear() throws InterruptedException {
	  synchronized(this) {
		if(this.currentCallCount < numExpectedRegionClearCalls) {
		  this.wait();	
		}
	  }
	}
	  
	@Override
	public void afterRegionCustomEntryConcurrentHashMapClear() {
      synchronized(this) {
    	++this.currentCallCount;
    	if(this.currentCallCount == this.numExpectedRegionClearCalls) {
    	  this.notifyAll();	
    	}
      }
    }

  @Override
  public void afterPKBasedDBSynchExecution(Event.Type type, int numRowsModified,
      Statement stmt) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterBulkOpDBSynchExecution(Event.Type type, int numRowsModified,
      Statement stmt, String sql) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterCommitDBSynchExecution(List<Event> batchProcessed) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public PreparedStatement afterQueryPrepareFailure(Connection conn,
      String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability, int autoGeneratedKeys, int[] columnIndexes,
      String[] columnNames, SQLException sqle) throws SQLException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean afterQueryExecution(CallbackStatement stmt, SQLException sqle)
      throws SQLException {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void afterCommit(Connection conn) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterRollback(Connection conn) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterConnectionClose(Connection conn) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void close() {
    // TODO Auto-generated method stub
    
  }

  @Override
    public void afterQueryParsing(String query, StatementNode qt,
        LanguageConnectionContext lcc) {
      if (qt instanceof DropTableNode) {
        DropTableNode dtn = (DropTableNode) qt;
        String tableToDrop = dtn.getFullName();
        Region<?, ?> rgn = Misc.getRegionForTable(tableToDrop, false);
        if (rgn != null && rgn.getAttributes().getEnableOffHeapMemory()) {
          if (rgn.getAttributes().getDataPolicy().isPartition()) {
            PartitionedRegion pr = (PartitionedRegion) rgn;
            int numBucketsHosted = pr.getRegionAdvisor().getAllBucketAdvisors()
                .size();
            this.setNumExpectedRegionClearCalls(numBucketsHosted);
          } else {
            this.setNumExpectedRegionClearCalls(1);            
          }
        } else {
          this.setNumExpectedRegionClearCalls(0);
        }
      } else {
        this.setNumExpectedRegionClearCalls(0);
      }
    }

  @Override
  public void beforeOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterOptimizedParsedTree(String query, StatementNode qt,
      LanguageConnectionContext lcc) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void subQueryInfoObjectFromOptmizedParsedTree(
      List<SubQueryInfo> qInfos, GenericPreparedStatement gps,
      LanguageConnectionContext lcc) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void queryInfoObjectAfterPreparedStatementCompletion(QueryInfo qInfo,
      GenericPreparedStatement gps, LanguageConnectionContext lcc) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeQueryExecution(EmbedStatement stmt, Activation activation)
      throws SQLException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeBatchQueryExecution(EmbedStatement stmt, int batchSize)
      throws SQLException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterBatchQueryExecution(EmbedStatement stmt, int batchSize) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeQueryExecution(GenericPreparedStatement stmt,
      LanguageConnectionContext lcc) throws StandardException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterQueryExecution(GenericPreparedStatement stmt,
      Activation activation) throws StandardException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterResultSetOpen(GenericPreparedStatement stmt,
      LanguageConnectionContext lcc, ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onEmbedResultSetMovePosition(EmbedResultSet rs, ExecRow newRow,
      ResultSet theResults) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeGemFireActivationCreate(AbstractGemFireActivation ac) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterGemFireActivationCreate(AbstractGemFireActivation ac) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeGemFireResultSetOpen(AbstractGemFireResultSet rs,
      LanguageConnectionContext lcc) throws StandardException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterGemFireResultSetOpen(AbstractGemFireResultSet rs,
      LanguageConnectionContext lcc) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterFlushBatch(ResultSet rs, LanguageConnectionContext lcc)
      throws StandardException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeComputeRoutingObjects(AbstractGemFireActivation activation) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterComputeRoutingObjects(AbstractGemFireActivation activation) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public <T extends Serializable> void beforeQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public <T extends Serializable> void afterQueryDistribution(
      StatementExecutorMessage<T> executorMessage, boolean streaming) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterGemFireResultSetExecuteOnActivation(
      AbstractGemFireActivation activation) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterGemFireResultSetClose(AbstractGemFireResultSet rs,
      String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeEmbedResultSetClose(EmbedResultSet rs, String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void createdGemFireXDResultSet(ResultSet rs) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterQueryExecutionByStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedStatement stmt, String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterQueryExecutionByPrepStatementQueryExecutor(
      GfxdConnectionWrapper wrapper, EmbedPreparedStatement pstmt, String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterResultHolderIteration(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterResultHolderSerialization(GfxdConnectionWrapper wrapper,
      EmbedStatement es) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterResultHolderExecution(GfxdConnectionWrapper wrapper,
      EmbedStatement es, String query) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeResultSetHolderRowRead(RowFormatter rf, Activation act) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterResultSetHolderRowRead(RowFormatter rf, ExecRow row,
      Activation act) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeIndexUpdatesAtRegionLevel(LocalRegion owner,
      EntryEventImpl event, RegionEntry entry) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeForeignKeyConstraintCheckAtRegionLevel() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeUniqueConstraintCheckAtRegionLevel() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterGlobalIndexLookup(LanguageConnectionContext lcc,
      PartitionedRegion indexRegion, Serializable indexKey, Object result) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void scanControllerOpened(Object sc, Conglomerate conglom) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeConnectionCloseByExecutorFunction(long[] connectionIDs) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterConnectionCloseByExecutorFunction(long[] connectionIDs) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeORM(Activation activation, AbstractGemFireResultSet rs) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterORM(Activation activation, AbstractGemFireResultSet rs) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public double overrideDerbyOptimizerIndexUsageCostForHash1IndexScan(
      OpenMemIndex memIndex, double optimzerEvalutatedCost) {
    
    return optimzerEvalutatedCost;
  }

  @Override
  public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
      OpenMemIndex memIndex, double optimzerEvalutatedCost) {
    
    return optimzerEvalutatedCost;
  }

  @Override
  public double overrideDerbyOptimizerCostForMemHeapScan(
      GemFireContainer gfContainer, double optimzerEvalutatedCost) {
    
    return optimzerEvalutatedCost;
  }

  @Override
  public void criticalUpMemoryEvent(GfxdHeapThresholdListener listener) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void criticalDownMemoryEvent(GfxdHeapThresholdListener listener) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void estimatingMemoryUsage(String stmtText, Object resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public long estimatedMemoryUsage(String stmtText, long memused) {
    
    return memused;
  }

  @Override
  public void putAllCalledWithMapSize(int size) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterClosingWrapperPreparedStatement(long wrapperPrepStatementID,
      long wrapperConnectionID) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void updatingColocationCriteria(ComparisonQueryInfo cqi) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void statementStatsBeforeExecutingStatement(StatementStats stats) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void reset() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void subqueryNodeProcessedData(SelectQueryInfo qInfo,
      GenericPreparedStatement gps, String subquery,
      List<Integer> paramPositions) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void insertMultipleRowsBeingInvoked(int numElements) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void keyAndContainerAfterLocalIndexInsert(Object key,
      Object rowLocation, GemFireContainer container) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void keyAndContainerAfterLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void keyAndContainerBeforeLocalIndexDelete(Object key,
      Object rowLocation, GemFireContainer container) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void getAllInvoked(int numKeys) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void getAllGlobalIndexInvoked(int numKeys) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void getAllLocalIndexInvoked(int numKeys) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void getAllLocalIndexExecuted() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void ncjPullResultSetOpenCoreInvoked() {
    // TODO Auto-generated method stub
    
  }
  
  @Override
  public void getStatementIDs(long stID, long rootID, int stLevel) {
    // TODO Auto-generated method stub
    
  }
  
  @Override
  public void ncjPullResultSetVerifyBatchSize(int value) {
    // TODO Auto-generated method stub
    
  }
  
  @Override
  public void ncjPullResultSetVerifyCacheSize(int value) {
    // TODO Auto-generated method stub
    
  }
  
  @Override
  public void ncjPullResultSetVerifyVarInList(boolean value) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void independentSubqueryResultsetFetched(Activation activation,
      ResultSet results) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeInvokingContainerGetTxRowLocation(RowLocation regionEntry) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterGetRoutingObject(Object routingObject) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public long overrideUniqueID(long actualUniqueID, boolean forRegionKey) {
    // TODO Auto-generated method stub
    return actualUniqueID;
  }

  @Override
  public boolean beforeProcedureResultSetSend(ProcedureSender sender,
      EmbedResultSet rs) {
    return true;
  }

  @Override
  public boolean beforeProcedureOutParamsSend(ProcedureSender sender,
      ParameterValueSet pvs) {
    return true;
  }

  @Override
  public void beforeProcedureChunkMessageSend(ProcedureChunkMessage message) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void lockingRowForTX(TXStateProxy tx, GemFireContainer container,
      RegionEntry entry, boolean writeLock) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void attachingKeyInfoForUpdate(GemFireContainer container,
      RegionEntry entry) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public boolean avoidMergeRuns() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public int overrideSortBufferSize(ColumnOrdering[] columnOrdering,
      int sortBufferMax) {
   
    return sortBufferMax;
  }

  @Override
  public void callAtOldValueSameAsNewValueCheckInSM2IIOp() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onGetNextRowCore(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onGetNextRowCoreOfBulkTableScan(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onGetNextRowCoreOfGfxdSubQueryResultSet(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onDeleteResultSetOpen(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onSortResultSetOpen(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onGroupedAggregateResultSetOpen(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onUpdateResultSetOpen(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onUpdateResultSetDoneUpdate(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onDeleteResultSetOpenAfterRefChecks(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onDeleteResultSetOpenBeforeRefChecks(ResultSet resultSet) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void setRoutingObjectsBeforeExecution(Set<Object> routingKeysToExecute) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeDropGatewayReceiver() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeDropDiskStore() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void memberConnectionAuthenticationSkipped(boolean skipped) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void userConnectionAuthenticationSkipped(boolean skipped) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void regionSizeOptimizationTriggered(FromBaseTable fbt,
      SelectNode selectNode) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void invokeCacheCloseAtMultipleInsert() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public boolean isCacheClosedForTesting() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void afterGlobalIndexInsert(boolean posDup) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public boolean needIndexRecoveryAccounting() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void setIndexRecoveryAccountingMap(THashMap map) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeQueryReprepare(GenericPreparedStatement gpst,
      LanguageConnectionContext lcc) throws StandardException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public boolean throwPutAllPartialException() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void afterIndexRowRequalification(Boolean success,
      CompactCompositeIndexKey ccKey, ExecRow row, Activation activation) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeRowTrigger(LanguageConnectionContext lcc, ExecRow execRow,
      ExecRow newRow) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterRowTrigger(TriggerDescriptor trigD,
      GenericParameterValueSet gpvs) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeGlobalIndexDelete() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeDeferredUpdate() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeDeferredDelete() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void bucketIdcalculated(int bid) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void beforeReturningCachedVal(Serializable globalIndexKey,
      Object cachedVal) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterPuttingInCached(Serializable globalIndexKey, Object result) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterSingleRowInsert(Object routingObj) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterQueryPlanGeneration() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void afterLockingTableDuringImport() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public boolean testIndexRecreate() {
    return false;
  }

      @Override
  public void regionSizeOptimizationTriggered2(SelectNode selectNode) {
    // TODO Auto-generated method stub
  }
      @Override
  public void regionPreInitialized(GemFireContainer container) {

	}
  }
       
}
