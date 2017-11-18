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
package com.pivotal.gemfirexd.tools.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.gemstone.gemfire.internal.Assert;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedInput;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedOutput;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.impl.tools.ij.Main;
import com.pivotal.gemfirexd.internal.impl.tools.ij.utilMain;
import com.pivotal.gemfirexd.internal.shared.common.SharedUtils;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * Miscellaneous tools for gfxd utility. Currently only "gfxd run &lt;script&gt;".
 * 
 * @author swale
 * @since 7.0
 */
public class MiscTools extends ToolsBase {

  // top-level command names keys and values
  static final String RUN;

  // command name usage and description message keys
  private static final String RUN_DESC_KEY;

  private static final Map<String, String> validCommands;

  // command options
  private static final String SCRIPT_URL;
  private static final String SCRIPT_PATH;
  public static final String PATH_TOKEN;
  private static final String ENCODING;
  private static final String IGNORE_ERRORS;
  private static final String PARAM;
  private static final String NUMTIMESTORUN;

  private String scriptURL;
  // GemStone changes BEGIN
  private String scriptPath = null;
  private Map<String, String> params = new HashMap<>();
  // GemStone changes END
  private String encoding;
  private boolean ignoreErrors;
  private int numTimesToRun = 1;

  static {
    RUN = LocalizedResource.getMessage("MISCTOOLS_RUN");

    RUN_DESC_KEY = "MISCTOOLS_RUN_DESC";

    SCRIPT_URL = LocalizedResource.getMessage("TOOLS_FILE");
    SCRIPT_PATH = LocalizedResource.getMessage("TOOLS_PATH_ARG");
    PATH_TOKEN = "<" + MiscTools.SCRIPT_PATH + ">";
    ENCODING = LocalizedResource.getMessage("MISCTOOLS_ENCODING");
    IGNORE_ERRORS = LocalizedResource.getMessage("MISCTOOLS_RUN_IGNORE_ERRORS");
    PARAM = LocalizedResource.getMessage("TOOLS_PARAM_ARG");
    NUMTIMESTORUN = LocalizedResource.getMessage("TOOLS_NUMTIMESTORUN_ARG");

    validCommands = new LinkedHashMap<String, String>();
    validCommands.put(RUN, RUN_DESC_KEY);
  }

  public static void main(String[] args) {
    final MiscTools instance = new MiscTools();
    instance.invoke(args);
  }

  public static Map<String, String> getValidCommands() {
    return validCommands;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected Map<String, String> getCommandToDescriptionKeyMap() {
    return validCommands;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected ProcessCommand getCommandProcessor(final String cmd) {
    if (RUN.equals(cmd)) {
      return new ProcessCommand() {
        @Override
        public final void addCommandOptions(final Options opts) {
          addFileOption(opts);
          addPathOption(opts);
          addEncodingOption(opts);
          addIgnoreErrorsOption(opts);
          addParamOption(opts);
          addNumTimesToRun(opts);
        }

        @Override
        public final void executeCommand(final CommandLine cmdLine,
            final String cmd, final String cmdDescKey) throws ParseException,
            IOException, SQLException {
          final Connection conn = getConnection(cmdLine, cmd, cmdDescKey);

          if (encoding == null) {
            encoding = "UTF-8";
          }
          final InputStream scriptInput = SharedUtils.openURL(scriptPath, scriptURL);
          final LocalizedResource lr = LocalizedResource.getInstance();
          final PrintStream out = outputStream != null ? outputStream : System.out;
          final LocalizedOutput lo = lr.getNewEncodedOutput(out,
              encoding);
          final LocalizedInput li = lr
              .getNewEncodedInput(scriptInput, encoding);

          final Main ijE = new Main(false);
          final utilMain um;
          if (ignoreErrors) {
            um = ijE.getutilMain(1, lo);
          }
          else {
            um = new utilMain(1, lo, new Hashtable<String, Object>(), scriptPath, params, numTimesToRun);
          }
          um.goScript(conn, li, true);
        }
      };
    }
    else {
      return null;
    }
  }

  protected void addFileOption(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_FILE_URL_ARG")).hasArg().isRequired(true)
        .withValueSeparator('=').withDescription(LocalizedResource.getMessage(
            "MISCTOOLS_SCRIPT_MESSAGE")).create(SCRIPT_URL);
    opts.addOption(opt);
  }
  
  protected void addPathOption(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_PATH_ARG")).hasArg().isRequired(false)
        .withValueSeparator('=').withDescription(LocalizedResource.getMessage(
            "MISCTOOLS_PATH_MESSAGE")).create(SCRIPT_PATH);
    opts.addOption(opt);
  }

  protected void addEncodingOption(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "MISCTOOLS_CHARSET")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "MISCTOOLS_ENCODING_MESSAGE")).create(ENCODING);
    opts.addOption(opt);
  }

  protected void addIgnoreErrorsOption(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withDescription(
        LocalizedResource.getMessage("MISCTOOLS_RUN_IGNORE_ERRORS_MESSAGE"))
        .create(IGNORE_ERRORS);
    opts.addOption(opt);
  }

  protected void addParamOption(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_PARAM_ARG")).hasArg().isRequired(false)
        .withValueSeparator(':').withDescription(LocalizedResource.getMessage(
            "MISCTOOLS_PARAM_MESSAGE")).create(PARAM);
    opts.addOption(opt);
  }

  protected void addNumTimesToRun(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_NUMTIMESTORUN_ARG")).hasArg().isRequired(false)
        .withValueSeparator('=').withDescription(LocalizedResource.getMessage(
            "MISCTOOLS_NUMTIMESTORUN_MESSAGE")).create(NUMTIMESTORUN);
    opts.addOption(opt);
  }

  protected Connection getConnection(final CommandLine cmdLine,
      final String cmd, final String cmdDescKey)
      throws ParseException, SQLException {
    final ConnectionOptions connOpts = new ConnectionOptions();
    final Iterator<?> iter = cmdLine.iterator();
    GfxdOption opt;
    while (iter.hasNext()) {
      opt = (GfxdOption)iter.next();
      if (SCRIPT_URL.equals(opt.getOpt())) {
        this.scriptURL = opt.getValue();
      }
      else if (SCRIPT_PATH.equals(opt.getOpt())) {
        String path = opt.getValue();
        if( !new File(path).isDirectory() ) {
          Assert.fail(path + " not a directory"); 
        }
        this.scriptPath = path;
      }
      else if (ENCODING.equals(opt.getOpt())) {
        this.encoding = opt.getValue();
      }
      else if (IGNORE_ERRORS.equals(opt.getOpt())) {
        this.ignoreErrors = true;
      }
      else if (PARAM.equals(opt.getOpt())) {
        final String[] param = opt.getValue().split("=");
        for (String existingKey : params.keySet()) {
          if (existingKey.indexOf(param[0]) == 0
              || param[0].indexOf(existingKey) == 0) {
            Assert.fail(existingKey + " and " + param[0] + " cannot be subset of each other." +
                "The parameter names must atleast differ in the first character");
          }
        }
        if (param.length != 2) {
          Assert.fail("Parameter value not found. Passed in => [" + opt.getValue() + "]");
        }
        params.put(param[0], param[1]);
      }
      else if (NUMTIMESTORUN.equals(opt.getOpt())) {
        numTimesToRun = Integer.parseInt(opt.getValue());
      }
      else if (!handleCommonOption(opt, cmd, cmdDescKey)) {
        if (!handleConnectionOption(opt, connOpts)) {
          Assert.fail(opt.toString());
        }
      }
    }

    return getConnection(connOpts, cmd, cmdDescKey);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getUsageString(String cmd, String cmdDescKey) {
    return LocalizedResource.getMessage("TOOLS_COMMON_DESC");
  }
}
