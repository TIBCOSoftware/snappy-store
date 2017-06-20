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
package cacheRunner;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.AttributesMutator;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.CacheStatistics;
import com.gemstone.gemfire.cache.CacheTransactionManager;
import com.gemstone.gemfire.cache.ConflictException;
import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.ExpirationAction;
import com.gemstone.gemfire.cache.ExpirationAttributes;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.TransactionId;
import com.gemstone.gemfire.cache.TransactionListener;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.query.CqAttributes;
import com.gemstone.gemfire.cache.query.CqAttributesFactory;
import com.gemstone.gemfire.cache.query.CqClosedException;
import com.gemstone.gemfire.cache.query.CqEvent;
import com.gemstone.gemfire.cache.query.CqException;
import com.gemstone.gemfire.cache.query.CqExistsException;
import com.gemstone.gemfire.cache.query.CqListener;
import com.gemstone.gemfire.cache.query.CqQuery;
import com.gemstone.gemfire.cache.query.Index;
import com.gemstone.gemfire.cache.query.IndexStatistics;
import com.gemstone.gemfire.cache.query.IndexType;
import com.gemstone.gemfire.cache.query.Query;
import com.gemstone.gemfire.cache.query.QueryException;
import com.gemstone.gemfire.cache.query.QueryInvalidException;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.RegionNotFoundException;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.cache.query.types.CollectionType;
import com.gemstone.gemfire.cache.query.types.ObjectType;
import com.gemstone.gemfire.cache.query.types.StructType;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.locks.Lock;

/**
 * This class is a command-line application that allows the user to
 * exercise GemFire's {@link com.gemstone.gemfire.cache.Cache
 * API}. The example allows the user to specify a <A
 * HREF="{@docRoot}/../cacheRunner/cache.xml">cache.xml</A>
 * file that specifies a parent region with certain properties and
 * then allows the user to exercise the cache API
 *
 * @author GemStone Systems, Inc.
 * @since 3.0
 */
public class CacheRunner {

  /** Cache <code>Region</code> currently reviewed by this example  */
  private Region currRegion;

  /** The cache used in the example */
  private Cache cache;

  /** This is used to store each Regions original attributes for reset purposes */
 private final HashMap regionDefaultAttrMap = new HashMap();

  /** The cache.xml file used to declaratively configure the cache */
  private File xmlFile = null;

  /** Are input commands echoed to output? */
  private boolean echo = false;

  /** Maps the names of locked "items" (regions or entries) to their
   * <code>Lock</code> */
  private final HashMap lockedItemsMap = new HashMap();
  
  /**
   * Prints information on how this program should be used.
   */
  void showHelp() {
    PrintStream out = System.out;

    out.println();
    out.println("A distributed system is created with properties loaded from your ");
    out.println("gemfire.properties file.  You can specify alternative property files using");
    out.println("-DgemfirePropertyFile=path.");
    out.println();
    out.println("Entry creation and retrieval handles byte[] (default), String, Integer, ");
    out.println("and a complex object.");
    out.println();
    out.println("You have to use mkrgn and chrgn to create and descend into a region");
    out.println("before using entry commands");
    out.println();
    out.println("Use a backward slash (\\) at the end of a line to continue a ");
    out.println("command on the next line. This is particularly useful for the");
    out.println("exec command.");
    out.println();
    out.println("Arguments can be wrapped in double-quotes so they can contain whitespace.");
    out.println();
    out.println("Commands:");
    out.println("ls");
    out.println("     List cache entries and subregions in current region and their stats.");
    out.println();
    out.println("lsAtts");
    out.println("     Lists the names of the region attributes templates stored in the cache.");
    out.println();
    out.println("status");
    out.println("     Display the current region global name, its stats, and the current");
    out.println("     attributes settings");
    out.println();
    out.println("create key [value [str|int|obj]]");
    out.println("     Define a new entry (see mkrgn to create a region). The complex object");
    out.println("     fields are filled based on the value given.");
    out.println();
    out.println("echo");
    out.println("     Toggle the echo setting. When echo is on then input commands are echoed to stdout.");
    out.println();
    out.println("put key value [str|int|obj]");
    out.println("     Associate a key with a value in the current region. As with create, the");
    out.println("     complex object fields are filled based on the value provided.");
    out.println();
    out.println("get key");
    out.println("     Get the value of a cache entry. ");
    out.println();
    out.println("des [-l] [key]");
    out.println("     Destroy an object or current region.  -l is for local destroy");
    out.println();
    out.println("inv [-l] [key]");
    out.println("     Invalidate a cache entry or current region. -l is for local invalidation");
    out.println();
    out.println("exec queryExpr");
    out.println("     Execute a query. All input after the exec command is considered the query string");
    out.println();
    out.println("mkidx name type idxExpr fromClause");
    out.println("     Create an query index.");
    out.println("        name         name of the index");
    out.println("        type         func | pkey");
    out.println("        idxExpr      the indexed expression (e.g. the attribute)");
    out.println("        fromExpr     the FROM clause");    
    out.println();    
    out.println("rmidx name");
    out.println("     Remove an query index.");
    out.println("        name         name of the index");
    out.println();
    out.println("indexes");
    out.println("     Prints information about all indexes in the cache. ");
    out.println();
    out.println("lock [key]");
    out.println("     Lock a cache entry or current region.");
    out.println();
    out.println("unlock [key]");
    out.println("     Unlock a cache entry or current region.");
    out.println();
    out.println("locks");
    out.println("     List the locks you hold");
    out.println();
    out.println("mkrgn name [attributeID]");
    out.println("     Create a subregion with the current attributes settings.");
    out.println("     The current attributes settings can be viewed with the status command");
    out.println("     and modified with the set and reset commands");
    out.println();
    out.println("registerInterest key");
    out.println("     Registers interest in a specific key.");
    out.println();
    out.println("reset");
    out.println("     Return the current attributes settings to the previously saved");
    out.println();
    out.println("save");
    out.println("     Save the current regions attributes");
    out.println();
    out.println("set [loader|listener|writer] [null]");
    out.println("     Add or remove a cache callback.  Accepted values: loader, listener,");
    out.println("     and cache writer. Use the optional null keyword to ");
    out.println("     remove the cache callback");
    out.println();
    out.println("set expiration <attribute> <value> <action>");
    out.println("     Set the value and action for an expiration attribute");
    out.println("       set expiration regionIdleTime 100 destroy");
    out.println("     The above example sets regionIdleTimeout to 100 ms and");
    out.println("     its action to destroy. Accepted attributes: regionIdleTime,");
    out.println("     entryIdleTime, regionTTL, entryTTL. Accepted actions: destroy,");
    out.println("     invalidate, localDestroy, localInvalidate");
    out.println();
    out.println("chrgn name");
    out.println("     Change current region (can use a local or global name)");
    out.println();
    out.println("chrgn ..");
    out.println("      Go up one region (parent region)");
    out.println();
    out.println("chrgn");
    out.println("     Go to cache root level");
    out.println();
    out.println("open");
    out.println("     Creates a Cache");
    out.println();
    out.println("load fileName");
    out.println("     Re-initializes the cache based using a cache.xml file");
    out.println();
    out.println("begin");
    out.println("     Begins a transaction in the current thread");
    out.println();
    out.println("commit");
    out.println("     Commits the current thread's transaction");
    out.println();
    out.println("rollback");
    out.println("     Rolls back the current thread's transaction");
    out.println();
    out.println("forceRolling");
    out.println("     Asks the region to start writing to a new log (if persistence/overflow is turned on).");
    out.println();
    out.println("help or ?");
    out.println("     List command descriptions");
    out.println();
    out.println("exit or quit");
    out.println("     Closes the current cache and exits");
  }

  /**
   * Initializes the <code>Cache</code> for this example program.
   * Uses the {@link LoggingCacheListener}, {@link LoggingCacheLoader},
   * and {@link LoggingCacheWriter}.
   */
  void initialize(boolean isClient) throws Exception {
    Properties props = new Properties();
    props.setProperty("name", "CacheRunner");
    if (this.xmlFile != null) {
      props.setProperty("cache-xml-file",this.xmlFile.toString());
    }
    
    //create the directories where data is going to be stored in case of persistence
    File dir1 =  new File("backupDirectory1");
    dir1.mkdir();
    File dir2 =  new File("backupDirectory2");
    dir2.mkdir();
    
     // Create the appropriate cache
    this.cache = isClient
        ? (Cache) new ClientCacheFactory(props).create()
        : new CacheFactory(props).create();
     
    Iterator rIter = this.cache.rootRegions().iterator();
    if(rIter.hasNext()) {
      this.currRegion = (Region) rIter.next();
    }
    else {
      /* If no root region exists, create one with default attributes */
      System.out.println("No root region in cache. Creating a root, 'root'\nwith keys constrained to String for C cache access.\n");
      RegionFactory regionFactory = cache.createRegionFactory();
      regionFactory.setKeyConstraint(String.class);
      currRegion = regionFactory.create("root");
    }

    AttributesMutator mutator = this.currRegion.getAttributesMutator();
    RegionAttributes currRegionAttributes = this.currRegion.getAttributes();
    if (currRegionAttributes.getCacheListeners().length == 0) {
      mutator.addCacheListener(new LoggingCacheListener());
    }

    if (currRegionAttributes.getCacheLoader() == null) {
      mutator.setCacheLoader(new LoggingCacheLoader());
    }

    if (currRegionAttributes.getCacheWriter() == null) {
      mutator.setCacheWriter(new LoggingCacheWriter());
    }

    if (cache.isServer()) {
      CacheTransactionManager manager = cache.getCacheTransactionManager();
      manager.addListener(new LoggingTransactionListener());
    }

    initRegionDefaultMap();

    cache.getLogger().info("Initialized");
  }

    /** Populate a map that has all the orginal attributes for existing regions */
    private void initRegionDefaultMap() {
      Iterator rrIter = cache.rootRegions().iterator();
      Iterator rSubIter = null;
      Region cRegion = null;
      AttributesFactory fac = null;

      while(rrIter.hasNext()) {
	  cRegion = (Region) rrIter.next();
	  fac = new AttributesFactory(cRegion.getAttributes());
	  regionDefaultAttrMap.put(cRegion.getFullPath(), fac.create());
	  rSubIter = cRegion.subregions(true).iterator();
	  while(rSubIter.hasNext()) {
	      cRegion = (Region) rSubIter.next();
	      fac = new AttributesFactory(cRegion.getAttributes());
	      regionDefaultAttrMap.put(cRegion.getFullPath(), fac.create());
	  }
      }
    }

  /**
   * Sets whether commands from input should be echoed to output.
   * Default is false.
   */
  public void setEcho(boolean echo) {
    this.echo = echo;
  }

  /**
   * Sets the <code>cache.xml</code> file used to declaratively
   * initialize the cache in this example.
   */
  public void setXmlFile(File xmlFile) {
    this.xmlFile = xmlFile;
  }

  /**
   * Parses the command line and runs the <code>CacheRunner</code>
   * example.
   */
  public static void main(String[] args) throws Exception {
    if (!(args.length == 1 || args.length == 2)) {
      System.err.println("Usage: java CacheRunner <cache.xml> [is-client]");
      System.exit(1);
    }
    String xmlFileName = args[0];

    File xmlFile = new File(xmlFileName);
    if (!xmlFile.exists()) {
      System.err.println("Supplied Cache config file <cache.xml> does not exist");
      System.exit(1);

    }
    
    // Parse the boolean isClient argument if it is specified
    boolean isClient = args.length == 2 ? Boolean.parseBoolean(args[1]) : false;
    
    CacheRunner runner = new CacheRunner();
    runner.setXmlFile(xmlFile);
    runner.initialize(isClient);
    runner.go();
    System.exit(0);
  }

  /**
   * Prompts the user for input and executes the command accordingly.
   */
  void go() {
    System.out.println("Enter 'help' or '?' for help at the command prompt.");
    System.out.println("");
    BufferedReader bin = new BufferedReader(new InputStreamReader(System.in));

    while(true) {
      try {
        if (this.cache.isClosed()) {
          System.out.println("Cache is closed, exiting...");
          System.exit(0);
        }
        
        String command = getLine(bin);
        
        if (command == null /* EOF */ ||
                command.startsWith("exit") || command.startsWith("quit")) {
          this.cache.close();
          System.exit(0);
//           return;
        }
        else if (command.startsWith("echo")) {
          this.echo = !this.echo;
          System.out.println("echo is " + (this.echo ? "on." : "off."));
        }
        else if (command.startsWith("run")) {
          run(command);
        }
        else if (command.startsWith("set")) {
          setRgnAttr(command);
        }
        else if (command.startsWith("putAll")) {
          putAll(command);
        }
        else if (command.startsWith("coql")) {
          coql(command);
        }
        else if (command.startsWith("put")) {
          put(command);
        }
        else if (command.startsWith("cre")) {
          create(command);
        }
        else if (command.startsWith("get")) {
          get(command);
        }
        else if (command.startsWith("registerInterest")) {
          registerInterest(command);
        }
        else if (command.startsWith("reset")) {
          reset();
        }
        else if (command.startsWith("inv")) {
          inv(command);
        }
        else if (command.startsWith("des")) {
          des(command);
        }
        else if (command.startsWith("lsAtts")) {
          lsAtts(command);
        }
        else if (command.startsWith("ls")) {
          ls(command);
        }
        else if (command.startsWith("stat")) {
          status(command);
        }
        else if (command.startsWith("mkr")) {
          mkrgn(command);
        }
        else if (command.startsWith("chr")) {
          chrgn(command);
        }
        else if (command.startsWith("open")) {
          open(command);
        }
        else if (command.startsWith("load")) {
          load(command);
        }
        else if (command.startsWith("begin")) {
          begin(command);
        }
        else if (command.startsWith("commit")) {
          commit(command);
        }
        else if (command.startsWith("rollback")) {
          rollback(command);
        }
        else if (command.startsWith("locks")) {
          showlocks();
        }
        else if (command.startsWith("lock")) {
          lock(command);
        }
        else if (command.startsWith("unlock")) {
          unlock(command);
        }
	else if (command.startsWith("save")) {
	  save();
	}
        else if (command.startsWith("help") || command.startsWith("?")){
          showHelp();
        }
        else if (command.startsWith("exec")) {
          exec(command);
        }
        else if (command.startsWith("forceRolling")) {
          forceRolling();
        }
        // case insensitive command
        else if (command.toLowerCase().startsWith("mkidx")) {
          mkidx(command);
        }
        else if (command.toLowerCase().startsWith("rmidx")) {
          rmidx(command);
        }
        else if (command.toLowerCase().startsWith("indexes")) {
          indexes(command);
        }
        else if (command.length() != 0) {
          System.out.println("Unrecognized command. Enter 'help' or '?' to get a list of commands.");
        }
      }
      catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
  
  private String getLine(BufferedReader bin) throws IOException {  
    String prompt = this.currRegion.getFullPath();
    if (this.cache.isServer()) {
      TransactionId xid = this.cache
          .getCacheTransactionManager().getTransactionId();
      if (xid != null) {
        prompt += " (TXN " + xid + ")";
      }
    }

    System.out.print(prompt + "> ");
    System.out.flush();

    StringBuffer cmdBuffer = null;
    boolean keepGoing;
    do {
      keepGoing = false;
      String nextLine = bin.readLine();

      // if nextLine is null then we encountered EOF.
      // In that case, leave cmdBuffer null if it is still null
      
      if (this.echo) {
        System.out.println(nextLine == null ? "EOF" : nextLine);
      }
      
      if (nextLine == null) {
        break;
      }
      else if (cmdBuffer == null) {
        cmdBuffer = new StringBuffer();
      }

      // if last character is a backward slash, replace backward slash with
      // LF and continue to next line
      if (nextLine.endsWith("\\")) {
        nextLine = nextLine.substring(0, nextLine.length() - 1);
        keepGoing = true;
      }
      cmdBuffer.append(nextLine);
      if (keepGoing) {
        cmdBuffer.append('\n');
      }
    } while (keepGoing);

    if (this.echo) System.out.println();
    
    return cmdBuffer == null ? null : cmdBuffer.toString();    
  }

    // ************ Command implementation methods ****************************
  
  /**
   * Executes a Query.
   * @see Query
   */
  void exec(String command) throws QueryException {
    // remove the "exec" command from the string
    String execCmd = new StringTokenizer(command).nextToken();
    String queryString = command.substring(execCmd.length());
    
    try {      
      Query query = this.cache.getQueryService().newQuery(queryString);
      long startNanos = System.nanoTime();
      Object results = query.execute();
      long execNanos = System.nanoTime() - startNanos; 
      System.out.println("Query Executed in " + (execNanos / 1e6) + " ms.");

      if (results instanceof SelectResults) {
        StringBuffer sb = new StringBuffer();

        SelectResults sr = (SelectResults) results;
        CollectionType type = sr.getCollectionType();
        sb.append(sr.size());
        sb.append(" results in a collection of type ");
        sb.append(type);
        sb.append("\n");

        sb.append("  [");
        if (sr.isModifiable()) {
          sb.append("modifiable, ");
        } else {
          sb.append("unmodifiable, ");
        }
        if (type.isOrdered()) {
          sb.append("ordered, ");
        } else {
          sb.append("unordered, ");
        }
        if (type.allowsDuplicates()) {
          sb.append("duplicates");
        } else {
          sb.append("no duplicates");
        }
        sb.append("]\n");

        ObjectType elementType = type.getElementType();
        for (Iterator iter = sr.iterator(); iter.hasNext(); ) {
          Object element = iter.next();
          if (elementType.isStructType()) {
            StructType structType = (StructType) elementType;
            Struct struct = (Struct) element;
            ObjectType[] fieldTypes = structType.getFieldTypes();
            String[] fieldNames = structType.getFieldNames();
            Object[] fieldValues = struct.getFieldValues();

            sb.append("  Struct with ");
            sb.append(fieldTypes.length);
            sb.append(" fields\n");
            
            for (int i = 0; i < fieldTypes.length; i++) {
              ObjectType fieldType = fieldTypes[i];
              String fieldName = fieldNames[i];
              Object fieldValue = fieldValues[i];

              sb.append("    ");
              sb.append(fieldType);
              sb.append(" ");
              sb.append(fieldName);
              sb.append(" = ");
              sb.append(fieldValue);
              sb.append("\n");
            }

          } else {
            sb.append("  ");
            sb.append(element);
          }

          sb.append("\n");
        }

        System.out.println(sb);

      } else {
        // Just a regular object
        System.out.println("results are not SelectResults");
        System.out.println(results);
      }
    }
    catch (UnsupportedOperationException e) {
      // print a nicer message than a stack trace
      System.out.println("Error: Unsupported Feature: " + e.getMessage());
    }
  }
  
  /**
   * Creates an index.
   * Arguments are type, name, idxExpr, fromClause
   */
  void mkidx(String command) throws QueryException {
    List args = new ArrayList();
    parseCommand(command, args);
    if (args.size() < 5) {
      System.out.println("mkidx requires 4 args: name type idxExpr fromClause");
      return;
    }
    String name = (String)args.get(1);
    String type = (String)args.get(2);
    String idxExpr = (String)args.get(3);
    String fromClause = (String)args.get(4);
    
    IndexType indexType;
    if (type.toLowerCase().startsWith("func")) {
      indexType = IndexType.FUNCTIONAL;

    } else if (type.toLowerCase().startsWith("pkey")) {
      indexType = IndexType.PRIMARY_KEY;

    } else {
      throw new UnsupportedOperationException("Currently only support " +
              "functional and primary key indexes. No support for " + type + ".");
    }

    QueryService qs = this.cache.getQueryService();
    qs.createIndex(name, indexType, idxExpr, fromClause);
  }
  
  /**
   * Removes an index.
   * Argument is type
   */
  void rmidx(String command) throws QueryException {
    List args = new ArrayList();
    parseCommand(command, args);
    if (args.size() < 1) {
      System.out.println("rmidx requires 1 arg: name");
      return;
    }
    String name = (String)args.get(1);

    QueryService qs = this.cache.getQueryService();
    Index index = qs.getIndex(this.currRegion, name);
    if (index == null) {
      String s = "There is no index named \"" + name +
        "\" in region " + this.currRegion.getFullPath();
      System.err.println(s);

    } else {
      qs.removeIndex(index);
    }
  }

  /**
   * Prints out information about all of the indexes built in the
   * cache. 
   */
  void indexes(String command) {
    QueryService qs = this.cache.getQueryService();
    Collection indexes = qs.getIndexes();
    StringBuffer sb = new StringBuffer();
    sb.append("There are ");
    sb.append(indexes.size());
    sb.append(" indexes in this cache\n");

    for (Iterator iter = indexes.iterator(); iter.hasNext(); ) {
      Index index = (Index) iter.next();
      sb.append("  ");
      sb.append(index.getType());
      sb.append(" index \"");
      sb.append(index.getName());
      sb.append(" on region ");
      sb.append(index.getRegion().getFullPath());
      sb.append("\n");

      String expr = index.getIndexedExpression();
      if (expr != null) {
        sb.append("    Indexed expression: ");
        sb.append(expr);
        sb.append("\n");
      }

      String from = index.getFromClause();
      if (from != null) {
        sb.append("    From: ");
        sb.append(from);
        sb.append("\n");
      }

      String projection = index.getProjectionAttributes();
      if (projection != null) {
        sb.append("    Projection: ");
        sb.append(projection);
        sb.append("\n");
      }

      sb.append("    Statistics\n");
      IndexStatistics stats = index.getStatistics();
      sb.append("      ");
      sb.append(stats.getTotalUses());
      sb.append(" uses\n");

      sb.append("      ");
      sb.append(stats.getNumberOfKeys());
      sb.append(" keys, ");
      sb.append(stats.getNumberOfValues());
      sb.append(" values\n");

      sb.append("      ");
      sb.append(stats.getNumUpdates());
      sb.append(" updates totaling ");
      sb.append(stats.getTotalUpdateTime() / 1e6);
      sb.append(" ms.\n");
    }
    System.out.println(sb.toString());
  }

  /**
   * Prints out information about the current region
   *
   * @see Region#getStatistics
   */
  void status(String command) throws CacheException {
    PrintStream out = System.out;
    out.println("Current Region:\n\t" + this.currRegion.getFullPath());
    RegionAttributes attrs = this.currRegion.getAttributes();
    if (attrs.getStatisticsEnabled()) {
      CacheStatistics stats = this.currRegion.getStatistics();
      out.println("Stats:\n\tHitCount is " + stats.getHitCount() +
                  "\n\tMissCount is " + stats.getMissCount() +
                  "\n\tLastAccessedTime is " + stats.getLastAccessedTime() +
                  "\n\tLastModifiedTime is " + stats.getLastModifiedTime());
    }
    out.println("Region CacheCallbacks:" +
		"\n\tCacheListeners: " + Arrays.asList(attrs.getCacheListeners()) + 
		"\n\tCacheLoader: " + attrs.getCacheLoader() + 
		"\n\tCacheWriter: " + attrs.getCacheWriter() + 
		"\n\tCustom Entry Idle: " + attrs.getCustomEntryIdleTimeout() +
		"\n\tCustom Entry TTL: " + attrs.getCustomEntryTimeToLive() +
		"\n\tEvictionAttributes: " + attrs.getEvictionAttributes() + 
		"\nRegion Expiration Settings:" + 
		"\n\tRegionIdleTimeOut: " + attrs.getRegionIdleTimeout() +
		"\n\tEntryIdleTimeout: " +  attrs.getEntryIdleTimeout() + 
		"\n\tEntryTimeToLive: " + attrs.getEntryTimeToLive() + 
		"\n\tRegionTimeToLive: " +  attrs.getRegionTimeToLive());
  }

  /**
   * Creates a new subregion of the current region
   *
   * @see Region#createSubregion
   */
  void mkrgn(String command) throws CacheException {
    String name = parseName(command);
    String attrId = parseAttributesId(command);
    if (name != null) {

      RegionAttributes attrs;
      if (attrId != null) {
        
        attrs = this.cache.getRegionAttributes(attrId);
      } else {
        attrs = this.currRegion.getAttributes();
      }
      AttributesFactory fac = new AttributesFactory(attrs);
      attrs = fac.create();
      Region nr = this.currRegion.createSubregion(name, attrs);
      regionDefaultAttrMap.put(nr.getFullPath(), fac.create());
    }
  }

  /**
   * Prints out information about the region entries that are
   * currently locked.
   */
  void showlocks() {
     Iterator iterator = lockedItemsMap.keySet().iterator();
     while(iterator.hasNext()) {
       String name = (String) iterator.next();
       System.out.println("Locked object name " + name);
     }
  }

  /**
   * Locks the current region or an entry in the current region based
   * on the given <code>command</code>.
   *
   * @see Region#getRegionDistributedLock
   * @see Region#getDistributedLock
   */
  void lock(String command) {
    if (command.indexOf(' ') < 0) {
      //Lock the entire region
      String serviceName = this.currRegion.getFullPath();
      if (!lockedItemsMap.containsKey(serviceName)) {
        Lock lock = this.currRegion.getRegionDistributedLock();
        lock.lock();
        lockedItemsMap.put(serviceName, lock);
      }

    }
    else {
      String name = parseName(command);
      if (name != null) {
        if (!lockedItemsMap.containsKey(name)) {
          Lock lock = this.currRegion.getDistributedLock(name);
          lock.lock();
          lockedItemsMap.put(name, lock);
        }
      }
    }
  }

  /**
   * Unlocks the current region or an entry in the current region
   * based on the given <code>command</code>.
   *
   * @see Lock#unlock
   */
  void unlock(String command) {
    if (command.indexOf(' ') < 0) {
      if (lockedItemsMap.containsKey(this.currRegion.getFullPath())) {
        Lock lock = (Lock) lockedItemsMap.remove(this.currRegion.getFullPath());
        lock.unlock();
      }
      else {
        System.out.println(" This region is not currently locked");
      }

    }
    else {
      String name = parseName(command);
      if ((name != null) && (lockedItemsMap.containsKey(name))) {
        Lock lock = (Lock) lockedItemsMap.remove(name);
        lock.unlock();
      }
      else {
        System.out.println(" This entry is not currently locked");
      }

    }

  }

  /**
   * Changes the current region to another as specified by
   * <code>command</code>.
   *
   * @see Cache#getRegion
   * @see Region#getSubregion
   * @see Region#getParentRegion
   */
  void chrgn(String command) throws CacheException {
    LinkedList list = new LinkedList();
    parseCommand(command, list);
    if (list.size() == 1) {
      this.currRegion = this.cache.getRegion("root");
      return;
    }

    String name = (String) list.get(1);
    Region tmpRgn;
    if (name.equals("..")) {
      tmpRgn = this.currRegion.getParentRegion();
    }
    else {
      tmpRgn = this.currRegion.getSubregion(name);
    }
    if (tmpRgn != null)
      this.currRegion = tmpRgn;
    else
      System.out.println("Region " + name + " not found");
  }

  /**
   * Invalidates (either local or distributed) a region or region
   * entry depending on the contents of <code>command</code>.
   *
   * @see Region#invalidateRegion()
   * @see Region#invalidate(Object)
   */
  void inv(String command) throws CacheException {
    LinkedList list = new LinkedList();
    parseCommand(command,list);
    String arg1 = null;
    String arg2 = null;
    boolean inv_l = false;
    switch (list.size()) {
      case 1:
        // inv followed by nothing invalidates the current region
        this.currRegion.invalidateRegion();
        break;
      case 2:
        arg1 = (String) list.get(1);
        inv_l = arg1.equals("-l");
        if (inv_l) {
          // inv -l local invalidates current region
          this.currRegion.localInvalidateRegion();

        }
        else {
          // inv name invalidate the entry name in current region
          this.currRegion.invalidate(arg1);

        }
        break;
      case 3:
       // inv -l name local invalidates name in current region
        arg1 = (String) list.get(1);
        arg2 = (String) list.get(2);
        inv_l = arg1.equals("-l");
        if (inv_l) {
          this.currRegion.localInvalidate(arg2);
        }
        break;
      default:
        break;
    }


  }

  /**
   * Reset the pending region attributes to the current region
   */
  void reset() throws CacheException {
      RegionAttributes defAttrs = (RegionAttributes) 
	  regionDefaultAttrMap.get(this.currRegion.getFullPath());
      if (defAttrs != null) {
	  AttributesMutator mutator = this.currRegion.getAttributesMutator();
	  mutator.initCacheListeners(defAttrs.getCacheListeners());
	  mutator.setCacheLoader(defAttrs.getCacheLoader());
	  mutator.setCacheWriter(defAttrs.getCacheWriter());
	  mutator.setRegionIdleTimeout(defAttrs.getRegionIdleTimeout());
	  mutator.setEntryIdleTimeout(defAttrs.getEntryIdleTimeout());
	  mutator.setCustomEntryIdleTimeout(defAttrs.getCustomEntryIdleTimeout());
	  mutator.setEntryTimeToLive(defAttrs.getEntryTimeToLive());
	  mutator.setCustomEntryTimeToLive(defAttrs.getCustomEntryTimeToLive());
	  mutator.setRegionTimeToLive(defAttrs.getRegionTimeToLive());
	  System.out.println("The attributes for Region " + 
			     this.currRegion.getFullPath() + 
			     " have been reset to the previously saved settings");
      }
  }

  /**
   * Save the region attributes of the current region
   */
  void save() throws CacheException {
      AttributesFactory fac = 
	  new AttributesFactory(this.currRegion.getAttributes());
      regionDefaultAttrMap.put(this.currRegion.getFullPath(), 
			       fac.create());
      System.out.println("The attributes for Region " + 
			 this.currRegion.getFullPath() + 
			 " have been saved.");
  }

  /**
   * Prints out the current region attributes
   *
   * @see Region#getAttributes
   */
  void attr(String command) throws CacheException {
      RegionAttributes regionAttr = this.currRegion.getAttributes();
      System.out.println("region attributes: " + regionAttr.toString());
  }

  /**
   * Registers interest with the server in the specific key. 
   * This is only applicable to client regions and the call throws 
   * an exception if done on a a non-client region. We just
   * let that pop up for the user of this app so they can see what
   * their program will get. 
   *
   * @see Region#registerInterest(Object)
   */
  void registerInterest(String command) throws CacheException {
    String key = parseName(command);
    if (key != null) {
      this.currRegion.registerInterest(key);
      System.out.println("Interest registered in " + key);
    }
  }

  /**
   * Gets a cached object from the current region and prints out its
   * <code>String</code> value.
   *
   * @see Region#get(Object)
   */
  void get(String command) throws CacheException {
    String name = parseName(command);
    if (name != null) {
//      String value =  "null";
      Object valueBytes = this.currRegion.get(name);
      printEntry(name, valueBytes);
    }
  }

  /**
   * Creates a new entry in the current region
   *
   * @see Region#create(Object, Object)
   */
  void create(String command) throws CacheException {
    LinkedList list = new LinkedList();
    parseCommand(command,list);
    if (list.size() < 2 ) {
      System.out.println("Error: create requires a name ");
    }
    else {
      String name = (String) list.get(1);
      if (list.size() > 2) {
        String value = (String) list.get(2);
        if (list.size() > 3) {
          String objectType = (String) list.get(3);
          if (objectType.equalsIgnoreCase("int")) {
            this.currRegion.create(name,Integer.valueOf(value));
          }
          else if (objectType.equalsIgnoreCase("str")) {
            this.currRegion.create(name,value);
          }
          else if (objectType.equalsIgnoreCase("obj")) {
              ExampleObject newObj = new ExampleObject();
              try { 
                newObj.setDoubleField(Double.valueOf(value).doubleValue()) ;
                newObj.setFloatField(Float.valueOf(value).floatValue()) ;
                newObj.setLongField(Long.parseLong(value)) ;
                newObj.setIntField(Integer.parseInt(value)) ;
                newObj.setShortField(Short.parseShort(value)) ;
              } catch (Exception e)  {
		/* leave at defaults (zero) if exception occurs */
              }
              newObj.setStringField(value) ;
              this.currRegion.create(name,newObj);
          }
          else {
            System.out.println("Invalid object type specified. Please see help.");
          }
        }
        else {
          this.currRegion.create(name,value.getBytes());
        }
      }
      else {
        this.currRegion.create(name,null);
      }
    }
  }

  void coql(String command) throws CacheException {
	  LinkedList list = new LinkedList();
	  parseCommand(command, list);
	  String key_str = (String) list.get(1);
	  int eo_id = Integer.parseInt(key_str);
	  
	  CqAttributesFactory cqf1 = new CqAttributesFactory();
	  CqListener EOCQListener = new EOCQEventListener();
	  cqf1.addCqListener(EOCQListener);
	  CqAttributes cqa1 = cqf1.create();
	  String cqName1 = "EOInfoTracker";
	  String queryStr1 = "SELECT ALL * FROM /root/cs_region ii WHERE ii.getInt_field() >= " + eo_id;
	  
	  System.out.println("Query String: "+queryStr1);
	  QueryService cqService = cache.getQueryService();
	  
	  try {
		  CqQuery EOTracker = cqService.newCq(cqName1, queryStr1, cqa1);
		  SelectResults rs1 = EOTracker.executeWithInitialResults();
		  List list1 = rs1.asList();
		  for (int i=0; i<list1.size(); i++) {
			  ExampleObject o = (ExampleObject)list1.get(i);
			  printEntry(o.getKey().toString(), o);
			  // Integer key = o.getKey();
		  }
		  
		  Thread.sleep(100000);
		  EOTracker.close();
	  } catch (InterruptedException e) {
		  // TODO Auto-generated catch block
		  e.printStackTrace();
	  } catch (CqClosedException e) {
		  // TODO Auto-generated catch block
		  e.printStackTrace();
	  } catch (RegionNotFoundException e) {
		  // TODO Auto-generated catch block
		  e.printStackTrace();
	  } catch (QueryInvalidException e) {
		  // TODO Auto-generated catch block
		  e.printStackTrace();
	  } catch (CqExistsException e) {
		  // TODO Auto-generated catch block
		  e.printStackTrace();
	  } catch (CqException e) {
		  // TODO Auto-generated catch block
		  e.printStackTrace();
          }
//	  CQ only
  }
  
  void putAll(String command) throws CacheException {
	  LinkedList list = new LinkedList();
	  parseCommand(command, list);
	  String name = (String) list.get(1);
	  String size_str = (String) list.get(2);
	  int size = Integer.valueOf(size_str).intValue();
	  int start_value = 0;
	  if (list.size() > 3) {
		  String start_str = (String) list.get(3);
		  start_value = Integer.valueOf(start_str).intValue();
	  }
	  Map map = new LinkedHashMap();
	  for (int i=0; i<size; i++) {
		  // map.put(name+i, new Integer(i+start_value));
		  map.put(name+i, new ExampleObject(i+start_value));
	  }
	  this.currRegion.putAll(map);
  }
  
  void run(String command) throws CacheException {
	  LinkedList list = new LinkedList();
	  parseCommand(command, list);
	  if (list.size() <3) {
		  System.out.println("Usage:run numberOfOp sizeOfData");
	  }
	  String num_str = (String) list.get(1);
	  String size_str = (String) list.get(2);
	  
	  int number = 100;
	  if (num_str != null) {
		  number = Integer.parseInt(num_str);
	  }
	  
	  int size = 100;
	  if (size_str != null) {
		  size = Integer.parseInt(size_str);
	  }
	  byte[] buf = new byte[size];
	  for (int i=0;i<size;i++) {
		  buf[i] = 'A';
	  }
	  String value = new String(buf);
	  
	  {
	  long startTime = System.currentTimeMillis();
	  Map map = new LinkedHashMap();
	  for (int i=0; i<number; i++) {
		if (size > 0) {
		  map.put(new Integer(i), value);
		} else {
		  map.put(new Integer(i), new ExampleObject(10));
		}
	  }
	  this.currRegion.putAll(map);
	  long finishTime = System.currentTimeMillis();
	  long sec = (finishTime - startTime)/1000;
	  long usec = (finishTime - startTime)%1000;
	  float rst = (float)number/(finishTime - startTime)*1000;
	  System.out.println("Number of put(): "+number+" Time consumed: "+sec+"."+usec+" Ops/second:"+rst);
	  }
  }

  /**
   * Puts an entry into the current region
   *
   * @see Region#put(Object, Object)
   */
  void put(String command) throws CacheException {
    LinkedList list = new LinkedList();
    parseCommand(command, list);
    if (list.size() < 3 ) {
      System.out.println("Error:put requires a name and a value");
    }
    else {
      String name = (String) list.get(1);
      String value = (String) list.get(2);
      if (list.size() > 3) {
        String objectType = (String) list.get(3);
        if (objectType.equalsIgnoreCase("int")) {
          this.currRegion.put(name,Integer.valueOf(value));
        }
        else if (objectType.equalsIgnoreCase("str")) {
          this.currRegion.put(name,value);
        }
        else if (objectType.equalsIgnoreCase("obj")) {
            ExampleObject newObj = new ExampleObject();
            try { 
              newObj.setDoubleField(Double.valueOf(value).doubleValue()) ;
              newObj.setFloatField(Float.valueOf(value).floatValue()) ;
            } catch (Exception e)  {
		/* leave at 0.0 if exception occurs */
            }
            try { 
              newObj.setLongField(Long.parseLong(value)) ;
              newObj.setIntField(Integer.parseInt(value)) ;
              newObj.setShortField(Short.parseShort(value)) ;
            } catch (Exception e)  {
		/* leave at 0 if exception occurs */
            }
            newObj.setStringField(value) ;
            this.currRegion.put(name,newObj);
        }
        else {
          System.out.println("Invalid object type specified. Please see help.");
        }
      }
      else {
        this.currRegion.put(name,value.getBytes());
      }
    }
  }

  /**
   * Destroys (local or distributed) a region or entry in the current
   * region.
   *
   * @see Region#destroyRegion()
   */
  void des(String command) throws CacheException {

    LinkedList list = new LinkedList();
    parseCommand(command,list);
    String arg1 = null;
    String arg2 = null;
    boolean des_l= false;
    switch (list.size()) {
      case 1:
        // inv followed by nothing invalidates the current region
        this.currRegion.destroyRegion();
        break;
      case 2:
        arg1 = (String) list.get(1);
        des_l= arg1.equals("-l");
        if (des_l) {
          // inv -l local invalidates current region
          this.currRegion.localDestroyRegion();

        }
        else {
          // inv name invalidate the entry name in current region
          this.currRegion.destroy(arg1);

        }
        break;
      case 3:
       // inv -l name local invalidates name in current region
        arg1 = (String) list.get(1);
        arg2 = (String) list.get(2);
        des_l = arg1.equals("-l");
        if (des_l) {
          this.currRegion.localDestroy(arg2);
        }
        break;
      default:
        break;
    }
  }

  /**
   * Lists the contents of the current region.
   *
   * @see Region#entries
   */
  void ls(String command) throws CacheException {
    boolean ls_l = command.indexOf("-l") != -1;
    if (ls_l) {
      System.out.println(this.currRegion.getFullPath() + " attributes:");
      System.out.println("\t" + this.currRegion.getAttributes());
    }
    System.out.println("Region Entries:"+this.currRegion.size());
    Set nameSet = this.currRegion.entrySet();
    int cnt = 0;
    for (Iterator itr = nameSet.iterator(); itr.hasNext();) {
      Region.Entry entry = (Region.Entry) itr.next();
      String name = entry.getKey().toString();
      Object valueBytes = entry.getValue();
      printEntry(name, valueBytes);

      if (ls_l) {
        System.out.println("\t\t" + this.currRegion.get(name));
      }
      cnt++;
      if (cnt >= 100) {
        System.out.println("...");
        System.out.println("Only print the first 100 entries");
        break;
      }
    }
    System.out.println();
    System.out.println("Subregions:");
    Set regionSet = this.currRegion.subregions(false);
    for (Iterator itr = regionSet.iterator(); itr.hasNext();) {
      Region rgn = (Region)itr.next();
      String name = rgn.getName();
      System.out.println("\t" + name);
      if(rgn.getAttributes().getStatisticsEnabled()) {
        CacheStatistics stats = rgn.getStatistics();
        System.out.println("\n\t\t" + stats);
      }
    }
  }

  /**
   * Lists the map of region attributes templates that are stored 
   * in the cache
   */
  void lsAtts(String command) throws CacheException {
    Map attMap = this.cache.listRegionAttributes();
    Iterator itrKeys = attMap.keySet().iterator(); 
    for (Iterator itrVals = attMap.values().iterator(); itrVals.hasNext();) {
      RegionAttributes rAtts = (RegionAttributes)itrVals.next();
      System.out.println("\tid=" + itrKeys.next() + "; scope=" + rAtts.getScope() + "; dataPolicy=" + rAtts.getDataPolicy() + "\n");
    }
  }
 
  /**
   * Prints the key/value pair for an entry 
   * This method recognizes a subset of all possible object types. 
   */
  void printEntry(String key, Object valueBytes) {
    String value = "null";
    if (valueBytes != null) {
      if (valueBytes instanceof byte[]) {
        value = "byte[]: \"" + new String((byte[]) valueBytes) + "\"";
      }
      else if (valueBytes instanceof String) {
        value = "String: \"" + valueBytes + "\"";
      }
      else if (valueBytes instanceof Integer) {
        value = "Integer: \"" + String.valueOf(valueBytes) + "\"";
      }
      else if (valueBytes instanceof ExampleObject) {
	ExampleObject parseObject = (ExampleObject)(valueBytes);
        value = parseObject.getClass().getName() +
                           ": \"" + parseObject.getDoubleField() +
                           "\"(double)"
        + " \"" + parseObject.getLongField() + "\"(long)"
        + " \"" + parseObject.getFloatField() + "\"(float)"
        + " \"" + parseObject.getIntField() + "\"(int)"
        + " \"" + parseObject.getShortField() + "\"(short)"
        + " \"" + parseObject.getStringField() + "\"(String)";
      }
      else {
        value = String.valueOf(valueBytes);
      }
    } // null CHECK
    else {
      value = "No value in cache."; 
    }	
    System.out.println("		 " + key + " -> " + value);
  }

  /**
   * Sets an expiration attribute of the current region
   *
   * @see Region#getAttributesMutator
   */
  void setExpirationAttr(String command) throws Exception {
    AttributesMutator mutator = this.currRegion.getAttributesMutator();
    LinkedList list = new LinkedList();
    parseCommand(command,list);
    ExpirationAttributes attributes = null;
    int time = 0;
//    int index  = -1;;

    // Item 0 is "set" and item 1 is "expiration"
    ListIterator commListIter = list.listIterator(2);
    if (!commListIter.hasNext()) {
	System.err.println("set expiration must have an attribute and a value");
	return;
    } 
    String attrName = (String)commListIter.next();

    String attrValue = null;
    if (!commListIter.hasNext()) {
	System.err.println("set expiration attributes must have either a numeric value or null (no expiration)");
	return;
    } 
    attrValue = (String)commListIter.next();
    if (attrValue.equalsIgnoreCase("null")) {
      time = -1;
      if (commListIter.hasNext()) {
	System.err.println("null expiration attributes can not have an action");	  
	return;
      }
    } else {
	if ((time = parseInt(attrValue)) < 0) {
	    System.err.println("Attribute values are either an integer or the string null");	  
	    return;
	}
    }

    String attrAction = null;
    if (commListIter.hasNext()) {
	attrAction = (String)commListIter.next();
    }

    if (attrName.startsWith("regionIdleTime")) {
	if (time==-1) {
	    mutator.setRegionIdleTimeout(new ExpirationAttributes(0));
	} else {
	    if (null==(attributes=parseExpAction(time,attrAction))) {
		return;
	    }
	    mutator.setRegionIdleTimeout(attributes);
	}
    } else if (attrName.startsWith("entryIdleTime")) {
	if (time==-1) {
	    mutator.setEntryIdleTimeout(new ExpirationAttributes(0));
	} else {
	    if (null==(attributes=parseExpAction(time,attrAction))) {
		return;
	    }
	    mutator.setEntryIdleTimeout(attributes);
	}
    } else if (attrName.startsWith("entryTTL")) {
	if (time==-1) {
	    mutator.setEntryTimeToLive(new ExpirationAttributes(0));
	} else {
	    if (null==(attributes=parseExpAction(time,attrAction))) {
		return;
	    }
	    mutator.setEntryTimeToLive(attributes);
	}
    } else if (attrName.startsWith("regionTTL")) {
	if (time==-1) {
	    mutator.setRegionTimeToLive(new ExpirationAttributes(0));
	} else {
	    if (null==(attributes=parseExpAction(time,attrAction))) {
		return;
	    }
	    mutator.setRegionTimeToLive(attributes);
	}
    } else {
      System.err.println("Unrecognized expiration attribute name: " + attrName);
      return;
    }
  }

  /**
   * Sets a region attribute of the current region
   *
   * @see #setExpirationAttr
   * @see Region#getAttributesMutator
   */
  void setRgnAttr(String command) throws Exception {
    AttributesMutator mutator = this.currRegion.getAttributesMutator();
    String name = parseName(command);
    if (name == null) {
      return;
    }

    if (name.indexOf("exp") != -1) {
      setExpirationAttr(command);
      return;
    }

    final String value = parseValue(command);
    if (name.equals("loader")) {
      if (value != null && value.equalsIgnoreCase("null"))
        mutator.setCacheLoader(null);
      else
        mutator.setCacheLoader(new LoggingCacheLoader());
    }
    else if (name.equals("listener")) {
      if (value != null && value.equalsIgnoreCase("null"))
        mutator.initCacheListeners(null);
      else
        mutator.addCacheListener(new LoggingCacheListener());
    }
    else if (name.equals("writer")) {
      if (value != null && value.equalsIgnoreCase("null"))
        mutator.setCacheWriter(null);
      else
        mutator.setCacheWriter(new LoggingCacheWriter());
    }
    else if (name.equals("txnlistener")) {
      CacheTransactionManager manager =
       this.cache.getCacheTransactionManager();
      if (value != null && value.equalsIgnoreCase("null"))
        manager.initListeners(null);
      else
        manager.addListener(new LoggingTransactionListener());
    }
    else {
      System.err.println("Unrecognized attribute name: " + name);
    }
  }

  /**
   * Specifies the <code>cache.xml</code> file to use when creating
   * the <code>Cache</code>.  If the <code>Cache</code> has already
   * been open, then the existing one is closed.
   *
   * @see CacheFactory#create
   */
  void load(String command) throws CacheException {
    String name = parseName(command);
    if (name != null) {
      Properties env = new Properties();
      env.setProperty("cache-xml-file", name);
      if (cache != null) {
        cache.close();
      }
      this.cache = new CacheFactory(env).create();
      currRegion = cache.getRegion("root");
    }
  }

  /**
   * Opens the <code>Cache</code> and sets the current region to the
   * "root" region.
   *
   * @see Cache#getRegion
   */
  void open(String command) throws Exception {
      if (this.cache != null) {
        this.cache.close();
      }
    this.cache = new CacheFactory(new Properties()).create();
    this.currRegion = cache.getRegion("root");
  }

  /**
   * Begins a transaction in the current thread
   */
  void begin(String command) {
   if (this.cache == null) {
      String s = "The cache is not currently open";
      System.err.println(s);
      return;
    }

    CacheTransactionManager manager =
      this.cache.getCacheTransactionManager();
    manager.begin();
  }

  /**
   * Commits the transaction associated with the current thread.  If
   * the commit fails, information about which region entries caused
   * the conflict can be obtained from the {@link
   * TransactionListener}.
   */
  void commit(String command) {
    if (this.cache == null) {
      String s = "The cache is not currently open";
      System.err.println(s);
      return;
    }

    CacheTransactionManager manager =
      this.cache.getCacheTransactionManager();
    try {
      manager.commit();

    } catch (ConflictException ex) {
      String s = "While committing transaction";
      System.err.println(s + ": " + ex);
    }
  }

  /**
   * Rolls back the transaction associated with the current thread
   */
  void rollback(String command) {
    if (this.cache == null) {
      String s = "The cache is not currently open";
      System.err.println(s);
      return;
    }

    CacheTransactionManager manager =
      this.cache.getCacheTransactionManager();
    manager.rollback();
  }
  
/**
 * 
 */
  void forceRolling()
  {
    System.out.println("\nInvoking forceRolling on region : "+this.currRegion.getName());
    DiskStore ds = this.cache.findDiskStore(this.currRegion.getAttributes().getDiskStoreName());
    if (ds != null) {
      String persistDirString = Arrays.toString(ds.getDiskDirs());
      System.out.println("Look in '" + persistDirString + "' to see the files used for region ");
      System.out.println("persistence.");
      ds.forceRoll();
    }
  }

  // ************ Parsing methods **********************************

  /**
   * Parses a <code>command</code> and expects that the second token
   * is a name.
   */
  private String parseName(String command) {
    int space = command.indexOf(' ');
    if (space < 0) {
      System.err.println("You need to give a name argument for this command");
      return null;
    }
    else {
      int space2 = command.indexOf(' ', space+1);
      if (space2 < 0)
        return command.substring(space+1);
      else
        return command.substring(space+1, space2);
    }
  }

  /**
   * Parses a <code>command</code> and expects that the second token
   * is a name.
   */
  private String parseAttributesId(String command) {
    int space = command.indexOf(' ');
    if (space < 0) {
      System.err.println("You need to give a name argument for this command");
      return null;
    }
    int space2 = command.indexOf(' ', space+1);
    if (space2 < 0) {
      return null;
    }
    int space3 = command.indexOf(' ', space2+1);
    if (space3 < 0)
      return command.substring(space2+1);
    else
      return command.substring(space2+1, space3);
  }

  /**
   * Parses a <code>command</code> and expects that the third token is
   * a value.
   */
    private String parseValue(String command) {
      int space = command.indexOf(' ');
      if (space < 0) {
      return null;
    }
    space = command.indexOf(' ', space+1);
    if (space < 0) {
      return null;
    }
    else {
      int space2 = command.indexOf(' ', space+1);
      if (space2 < 0)
        return command.substring(space+1);
      else
        return command.substring(space+1, space2);
    }
  }

  /**
   * Parses an <code>int</code> from a <code>String</code>
   */
  private int parseInt(String value) {
    try {
      return Integer.parseInt(value);
    }
    catch (Exception e) {
      System.err.println("illegal number: " + value);
      return -1;
    }
  }

  /**
   * Parses a <code>command</code> and places each of its tokens in a
   * <code>List</code>.
   * Tokens are separated by whitespace, or can be wrapped with double-quotes
   */
  private boolean parseCommand(String command, List list) {
    Reader in = new StringReader(command);
    StringBuffer currToken = new StringBuffer();
    String delim = " \t\n\r\f";
    int c;
    boolean inQuotes = false;
    do {
      try {
        c = in.read();
      }
      catch (IOException e) {
        throw new Error("unexpected exception", e);
      }
      
      if (c < 0) break;
      
      if (c == '"') {
        if (inQuotes) {
          inQuotes = false;
          list.add(currToken.toString());
          currToken = new StringBuffer();
        }
        else {
          inQuotes = true;
        }
        continue;
      }
      
      if (inQuotes) {
        currToken.append((char)c);
        continue;
      }
     
      if (delim.indexOf((char)c) >= 0) {
        // whitespace
        if (currToken.length() > 0) {
          list.add(currToken.toString());
          currToken = new StringBuffer();
        }
        continue;
      }
      
      currToken.append((char)c);
    } while (true);
    
    if (currToken.length() > 0) {
      list.add(currToken.toString());
    }
    return true;
  }

  /**
   * Creates <code>ExpirationAttributes</code> from an expiration time
   * and the name of an expiration action.
   */
  private ExpirationAttributes parseExpAction( int expTime,
                                               String actionName) {
      if (actionName == null) {
	  if (expTime > 0) {
	      System.err.println("Setting expiration action to default (invalidate)");
	  }
	  return new ExpirationAttributes(expTime);
      }

    if (actionName.equals("destroy")) {
        return new ExpirationAttributes(expTime, ExpirationAction.DESTROY);
    }
    else if (actionName.startsWith("inv")) {
        return new ExpirationAttributes(expTime, ExpirationAction.INVALIDATE);
    }
    else if (actionName.startsWith("localDes")) {
        return new ExpirationAttributes(expTime, ExpirationAction.LOCAL_DESTROY);
    }
    else if (actionName.startsWith("localInv")) {
        return new ExpirationAttributes(expTime, ExpirationAction.LOCAL_INVALIDATE);
    } else {
      System.err.println("Expiration Action not understood: " + actionName);
      return null;
    }
  }

	class EOCQEventListener implements CqListener {

		public void onError(CqEvent cqEvent) {
			// TODO Auto-generated method stub
		}

		public void onEvent(CqEvent cqEvent) {
			// TODO Auto-generated method stub
			Object key =  cqEvent.getKey();
			ExampleObject newValue = (ExampleObject) cqEvent
					.getNewValue();
			System.out.println("InstructmentInfoObject:"+key+":"+newValue);
		}

		public void close() {
			// TODO Auto-generated method stub
		}
	}
}
