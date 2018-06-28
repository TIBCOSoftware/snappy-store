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

package com.gemstone.gemfire.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.admin.jmx.internal.AgentLauncher;
import com.gemstone.gemfire.internal.cache.CacheServerLauncher;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;

/**
 * Maps the GemFire utilities to the launcher that starts them and then invokes
 * that class's main method. Currently this class is only a base class for the 
 * GemFireXD implementation, but eventually the gemfire scripts will be 
 * consolidated to use this class.
 * Current GemFire utilities (as of 6.0):
 * <ul>
 * <li> agent 
 * <li> gemfire
 * <li> cacheserver 
 * </ul>
 * Usage:
 * notYetWritenScript <utility> <utility arguments>
 *
 * @author kbanks
 * @since 6.0
 */
public class GemFireUtilLauncher {

  /**
   * Name of the script that launches {@link #main(String[])}. This should be
   * changed if the script name changes.
   */
  public static String SCRIPT_NAME = "gemfire";

  /**
   * Returns a mapping of utility names to the class used to spawn them.
   * This method is overridedn by GemFireXDUtilLauncher to handle that product's
   * own utility tools.
   **/
  protected Map<String, CommandEntry> getTypes() {
    Map<String, CommandEntry> m = new LinkedHashMap<String, CommandEntry>();
    m.put("cacheserver", new CommandEntry(CacheServerLauncher.class,
        LocalizedStrings.GemFireUtilLauncher_CacheServer_Usage
            .toLocalizedString(), false));
    m.put(SCRIPT_NAME, new CommandEntry(SystemAdmin.class, LocalizedStrings
            .GemFireUtilLauncher_SystemAdmin_Usage.toLocalizedString(), false));
    m.put("agent", new CommandEntry(AgentLauncher.class, LocalizedStrings
        .GemFireUtilLauncher_Agent_Usage.toString(new Object[] { SCRIPT_NAME }), false));
    return m;
  }

  /** 
   * A simple constructor was needed so that {@link #usage(String)} 
   * and {@link #getTypes()} could be non-static methods.
   **/
  protected GemFireUtilLauncher() {}

  public static class CommandEntry {
    public final Class<?> clazz;
    public final String usage;
    public final boolean preserveArgv1;

    public CommandEntry(final Class<?> clazz, final String usage,
        final boolean preserveArg1) {
      this.clazz = clazz;
      this.usage = usage;
      this.preserveArgv1 = preserveArg1;
    }
  }

  /** 
   * This method should be overridden if the name of the script is different.
   * @return the name of the script used to launch this utility. 
   **/
  protected String scriptName() {
    return SCRIPT_NAME; 
  }

  public static String getScriptName() {
    return SCRIPT_NAME.equals("gemfire") && GemFireCacheImpl.gfxdSystem()
        ? "gfxd" : SCRIPT_NAME;
  }

  /** 
   * Print help information for this utility.
   * This method is intentionally non-static so that getTypes() can dynamically
   * display the list of supported utilites supported by child classes.
   * @param context print this message before displaying the regular help text
   **/
  protected void usage(String context) {
    System.out.println(context);
    StringBuilder sb = new StringBuilder();
    sb.append(" help | ");
    for(String key : getTypes().keySet()) {
      sb.append(key).append(" | ");
    }
    sb.deleteCharAt(sb.length()-2); // remove the extra "|"
    String msg = LocalizedStrings.GemFireUtilLauncher_ARGUMENTS
                   .toLocalizedString(new Object[] {scriptName(), sb});
    System.out.println(msg);
    throw new GemFireTerminateError("exiting after usage", 1);
  }

  /**
   * Spawn the utilty passed in as args[0] or display help information
   * @param args a utilty and the arguments to pass to it.
   */
  public static void main(String[] args) {
    GemFireUtilLauncher launcher = new GemFireUtilLauncher();
    try {
      launcher.validateArgs(args);
      launcher.invoke(args);
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

  /**
   * Calls the <code>public static void main(String[] args)</code> method
   * of the class associated with the utility name.  
   * @param args the first argument is the utility name, the remainder 
   *             comprises the arguments to be passed
   */
  protected void invoke(String[] args) {
    CommandEntry clazzEntry = getTypes().get(args[0]);
    if (clazzEntry == null) {
      utilityNotFound(args);
      return;
    }
    final Class<?> clazz = clazzEntry.clazz;
    final String[] argv;
    if (clazzEntry.preserveArgv1) {
      argv = args;
    }
    else {
      int len = args.length - 1;
      argv = new String[len];
      System.arraycopy(args, 1, argv, 0, len);
    }

    Exception ex = null;
    try {
      Method m = clazz.getDeclaredMethod("main", new Class[] {argv.getClass()});
      m.invoke(null, (Object)argv);
    } catch (SecurityException se) {
      ex = se;
    } catch (NoSuchMethodException nsme) {
      ex = nsme;    
    } catch (IllegalArgumentException iae) {
      ex = iae;
    } catch (IllegalAccessException iae) {
      ex = iae;
    } catch (InvocationTargetException ite) {
      ex = ite;
    } finally {
      if (ex != null) {
        String msg = LocalizedStrings.GemFireUtilLauncher_PROBLEM_STARTING_0
                                     .toLocalizedString(args[0]); 
        throw new RuntimeException(msg, ex);
      }
    }
  }

  protected void utilityNotFound(final String[] args) {
    usage(LocalizedStrings.GemFireUtilLauncher_INVALID_UTILITY_0
        .toLocalizedString(args[0]));
  }

  /**
   * Look for variations on help and validate the arguments make sense.
   * A usage mesage is displayed if necesary.
   * The following forms of help are accepted:
   * <code>--help, -help, /help, --h, -h, /h</code>
   **/ 
  protected void validateArgs(String[] args) {
    if (args.length == 0) {
      usage(LocalizedStrings.GemFireUtilLauncher_MISSING_COMMAND
                            .toLocalizedString());
    }
    //Match all major variations on --help
    Pattern help = Pattern.compile("(?:--|-|/){0,1}h(?:elp)*", 
        Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    Matcher matcher = help.matcher(args[0]); 

    if( matcher.matches() ) {
      usage(LocalizedStrings.GemFireUtilLauncher_HELP.toLocalizedString());
    }
    
    if(args[0].equalsIgnoreCase("listcmds")) {
      listCommands();
    }
  }

  protected void listCommands() {
  }
}
