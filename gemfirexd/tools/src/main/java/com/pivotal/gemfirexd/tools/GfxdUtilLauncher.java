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

package com.pivotal.gemfirexd.tools;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.internal.GemFireTerminateError;
import com.gemstone.gemfire.internal.GemFireUtilLauncher;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.tools.ij;
import com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher;
import com.pivotal.gemfirexd.tools.internal.JarTools;
import com.pivotal.gemfirexd.tools.internal.MiscTools;
import jline.console.ConsoleReader;
import jline.console.history.FileHistory;

/**
 * Extends GemFireUtilLauncher to map the GemFireXD utilities to their
 * corresponding classes. This class is intended to be called from
 * <code>gfxd or gfxd.bat</code> from the GemFireXD product tree.
 * <p>
 * Usage: gfxd <utility> <utility arguments>
 * 
 * @author kbanks
 * @see GemFireUtilLauncher
 */
public class GfxdUtilLauncher extends GemFireUtilLauncher {

  /**
   * Special internal argument to just resolve the full path from the given path
   * including any symlinks (see #43722).
   */
  protected static final String GET_CANONICAL_PATH_ARG = "--get-canonical-path";

  protected static boolean snappyStore;

  static {
    String scriptName = System.getenv("SNAPPY_SCRIPT_NAME");
    SCRIPT_NAME = scriptName != null && scriptName.length() > 0
        ? scriptName : "gfxd";
  }

  public static boolean isSnappyStore() {
    return snappyStore;
  }

  /**
   * Returns a mapping of utility names to the class used to spawn them for
   * GemFireXD.
   **/
  @Override
  protected Map<String, CommandEntry> getTypes() {
    // using a linked hashmap to get a consistent logical order
    Map<String, CommandEntry> m =
      new LinkedHashMap<String, CommandEntry>();
    m.put("server", new CommandEntry(GfxdServerLauncher.class, LocalizedResource
        .getMessage("UTIL_Server_Usage"), false));
    m.put("locator", new CommandEntry(GfxdDistributionLocator.class,
        LocalizedResource.getMessage("UTIL_Locator_Usage"), false));
    m.put("agent", new CommandEntry(GfxdAgentLauncher.class, LocalizedStrings
            .GemFireUtilLauncher_Agent_Usage.toString(new Object[] { LocalizedResource
            .getMessage("FS_PRODUCT")  }), false));
    m.put(SCRIPT_NAME, new CommandEntry(ij.class, LocalizedResource
        .getMessage("UTIL_GFXD_Usage"), false));
    //m.put("gemfire", SystemAdmin.class);
    // [sumedh] add all the commands supported by the "gemfire" script
    // (except "help") individually and use GfxdSystemAdmin to invoke
    // SystemAdmin appropriately (see bug #41469)
    for (String cmd : GfxdSystemAdmin.getValidCommands()) {
      if (!"help".equals(cmd) && !cmd.contains("locator")) {
        m.put(cmd, new CommandEntry(GfxdSystemAdmin.class, LocalizedResource
            .getMessage("UTIL_" + cmd.replace('-', '_') + "_ShortDesc"), true));
      }
    }
    // JarTools utilities
    for (Map.Entry<?, ?> cmdEntry : JarTools.getValidCommands().entrySet()) {
      String cmd = (String)cmdEntry.getKey();
      String cmdDescKey = (String)cmdEntry.getValue();
      m.put(cmd, new CommandEntry(JarTools.class, LocalizedResource
          .getMessage(cmdDescKey), true));
    }
    // MiscTools utilities
    for (Map.Entry<?, ?> cmdEntry : MiscTools.getValidCommands().entrySet()) {
      String cmd = (String)cmdEntry.getKey();
      String cmdDescKey = (String)cmdEntry.getValue();
      m.put(cmd, new CommandEntry(MiscTools.class, LocalizedResource
          .getMessage(cmdDescKey), true));
    }

    // GfxdDdlUtils is accessed by reflection since we need to build it last
    // after DdlUtils has been built
    Class<?> gfxdDdlUtilsClass;
    try {
      gfxdDdlUtilsClass = Class
          .forName("com.pivotal.gemfirexd.tools.internal.GfxdDdlUtils");
    } catch (LinkageError | ClassNotFoundException e) {
      // ddlutils dir is likely not on path, so ignore it
      gfxdDdlUtilsClass = null;
    } catch (Exception e) {
      throw new InternalGemFireError(e);
    }
    if (gfxdDdlUtilsClass != null) {
      try {
        for (Object entry : ((Map<?, ?>)gfxdDdlUtilsClass.getMethod(
            "getValidCommands").invoke(null)).entrySet()) {
          Map.Entry<?, ?> cmdEntry = (Map.Entry<?, ?>)entry;
          String cmd = (String)cmdEntry.getKey();
          String cmdDescKey = (String)cmdEntry.getValue();
          m.put(cmd, new CommandEntry(gfxdDdlUtilsClass, LocalizedResource
              .getMessage(cmdDescKey), true));
        }
      } catch (Exception e) {
        throw new InternalGemFireError(e);
      }
    }
    return m;
  }

  protected GfxdUtilLauncher() {
    ClientSharedUtils.setThriftDefault(false);
  }

 /**
  * This method should be overridden if the name of the script is different.
  * @return the name of the script used to launch this utility.
  **/
  @Override
  protected String scriptName() {
    return SCRIPT_NAME;
  }

  /**
   * @see GemFireUtilLauncher#main(String[])
   **/
  public static void main(final String[] args) {
    GfxdUtilLauncher launcher = new GfxdUtilLauncher();
    try {
      // no args will default to using ij
      if (args.length == 0) {
        launcher.invoke(new String[] { SCRIPT_NAME });
      }
      // short-circuit for the internal "--get-canonical-path" argument used by
      // script to resolve the full path including symlinks (#43722)
      else if (args.length == 2 && GET_CANONICAL_PATH_ARG.equals(args[0])) {
        try {
          System.out.println(new File(args[1]).getCanonicalPath());
        } catch (IOException ioe) {
          // in case of any exception print the given path itself
          System.out.println(args[1]);
        }
        return;
      }
      else {
        launcher.validateArgs(args);
        launcher.invoke(args);
      }
    } catch (GemFireTerminateError term) {
      System.exit(term.getExitCode());
    } catch (RuntimeException re) {
      // look for a GemFireTerminateError inside
      Throwable cause = re.getCause();
      while (cause != null) {
        if (cause instanceof GemFireTerminateError) {
          System.exit(((GemFireTerminateError)cause).getExitCode());
        }
        cause = cause.getCause();
      }
      throw re;
    }
  }

  public static ConsoleReader getConsoleReader() {
    // don't try to do paging if we have no associated console
    if (System.console() != null) {
      try {
        // use jline to go into the character reading mode
        if (!"jline.UnsupportedTerminal".equals(System
            .getProperty("jline.terminal"))) {
          final ConsoleReader reader = new ConsoleReader();
          Runtime.getRuntime().addShutdownHook(new Thread() {
            public  void run() {
              try {
                reader.getTerminal().restore();
                ((FileHistory)reader.getHistory()).flush();
              } catch (Exception e) {
                // restoration failed!
              }
            }
          });
          return reader;
        }
      } catch (IOException ioe) {
      }
    }
    return null;
  }

  public static void printUsage(final String usageOutput, int maxLines,
      final ConsoleReader reader) {
    // don't try to do paging if we have no associated console
    if (reader != null) {
      // use jline to go into the character reading mode
      maxLines = reader.getTerminal().getHeight() - 1;
      reader.setBellEnabled(false);
      SanityManager.printPagedOutput(System.out, System.in, usageOutput,
          maxLines, LocalizedResource.getMessage("UTIL_GFXD_Continue_Prompt"),
          false);
    }
    else {
      System.out.println(usageOutput);
    }
  }

  /**
   * Calls the <code>public static void main(String[] args)</code> method of the
   * class associated with the utility name. This sets the current command in
   * case the common {@link GfxdSystemAdmin} class gets invoked (for directly
   * invoking sub-commands of the "gemfire" command) to let
   * {@link GfxdSystemAdmin} launch with appropriate arguments and then invokes
   * the {@link GemFireUtilLauncher#invoke} method of the super class.
   * 
   * @param args
   *          the first argument is the utility name, the remainder comprises
   *          the arguments to be passed
   * 
   * @see GemFireUtilLauncher#invoke
   */
  @Override
  protected void invoke(String[] args) {
    // set the current command to be executed in GfxdSystemAdmin (its possible
    // that GfxdSystemAdmin never gets invoked but setting this does not harm
    // anything)
    //GfxdSystemAdmin.setCurrentCommand(args[0]);
    super.invoke(args);
  }

  /**
   * Print help information for this utility. This method is intentionally
   * non-static so that getTypes() can dynamically display the list of supported
   * utilites supported by child classes.
   * 
   * @param context
   *          print this message before displaying the regular help text
   **/
  @Override
  protected void usage(String context) {
    final ConsoleReader reader = getConsoleReader();
    final int width = reader != null ? reader.getTerminal().getWidth(): 80;
    final String lineSep = SanityManager.lineSeparator;
    final StringBuilder result = new StringBuilder();

    splitLine(context, width, 0, result);
    result.append(lineSep);

    final String indent = "   ";
    final Map<String, CommandEntry> types = getTypes();

    final String[] selfHelp = types.remove(scriptName()).usage.split("\\r?\\n");
    types.remove(SCRIPT_NAME); // Remove gfxd usage either way.
    for (String helpLine : selfHelp) {
      splitLine(helpLine, width, 0, result);
    }
    result.append(lineSep);

    splitLine(LocalizedResource.getMessage("UTIL_GFXD_Tools_Message"), width,
        0, result);
    int maxLength = 0;
    int len;
    for (String cmd : types.keySet()) {
      len = cmd.length();
      if (len > maxLength) {
        maxLength = len;
      }
    }
    maxLength += 4;
    final StringBuilder sb = new StringBuilder();
    final int toolsIndent = maxLength + indent.length() + 1;
    if (toolsIndent >= (width - 6)) {
      // can't do much formatting if the result will not fit in width properly
      for (Map.Entry<String, CommandEntry> entry : types.entrySet()) {
        len = entry.getKey().length();
        sb.append(indent).append(entry.getKey()).append(indent);
        sb.append(entry.getValue().usage).append(lineSep);
      }
    }
    else {
      for (Map.Entry<String, CommandEntry> entry : types.entrySet()) {
        final StringBuilder line = new StringBuilder();
        len = entry.getKey().length();
        line.append(indent).append(entry.getKey());
        // add appropriate spaces
        for (int cnt = len; cnt <= maxLength; cnt++) {
          line.append(' ');
        }
        line.append(entry.getValue().usage);
        splitLine(line, width, toolsIndent, sb);
      }
    }
    result.append(indent);
    result.append(LocalizedResource.getMessage("UTIL_GFXD_Tools_Usage",
        scriptName(), sb.toString()));
    result.append(lineSep);

    printUsage(result.toString(), SanityManager.DEFAULT_MAX_OUT_LINES, reader);

    throw new GemFireTerminateError("exiting after usage", 1);
  }

  @Override
  protected void listCommands() {
    final Map<String, CommandEntry> types = getTypes();
    TreeSet<String> sorted = new TreeSet<String>();
    sorted.addAll(types.keySet());
    sorted.add("help");
    for (String key : sorted) {
      System.out.println(key);
    }
    throw new GemFireTerminateError(null, 0);
  }

  public static void splitLine(final CharSequence line, final int width,
      final int indent, final StringBuilder result) {
    final String lineSep = SanityManager.lineSeparator;
    final int len = line.length();
    if (len <= width) {
      result.append(line);
      result.append(lineSep);
      return;
    }
    // build the indentation string
    char[] indentStr = null;
    if (indent > 0) {
      indentStr = new char[indent];
      for (int index = 0; index < indent; index++) {
        indentStr[index] = ' ';
      }
    }
    // keep adding lines with indentation to the result
    int offset = 0;
    int end = width;
    int lineIndent = 0;
    for (;;) {
      if (lineIndent > 0) {
        result.append(indentStr);
      }
      // break at word boundary
      if (end != len) {
        final int origEnd = end;
        while (!Character.isWhitespace(line.charAt(end - 1))) {
          // don't do anything if we don't find a word boundary
          if (--end <= offset) {
            end = origEnd;
            break;
          }
        }
      }
      result.append(line.subSequence(offset, end));
      result.append(lineSep);
      if (end == len) {
        break;
      }
      offset = end;
      end += (width - indent);
      if (end > len) {
        end = len;
      }
      lineIndent = indent;
    }
  }
}
