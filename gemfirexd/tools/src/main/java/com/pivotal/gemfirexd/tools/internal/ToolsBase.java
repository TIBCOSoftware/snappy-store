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

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.GemFireTerminateError;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.internal.iapi.tools.i18n.LocalizedResource;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.GfxdUtilLauncher;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.MissingArgumentException;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import jline.console.ConsoleReader;

/**
 * Base class for command-line launcher tools. Encapsulates the common
 * connection options used by all the new tools.
 *
 * @author swale
 * @since 7.0
 */
public abstract class ToolsBase {

  protected Options currentOpts;

  public static PrintStream outputStream;

  /**
   * Encapsulates values of connection options.
   */
  protected static class ConnectionOptions {
    String connectionUrl;
    String clientBindAddress;
    int clientPort = -1;
    int mcastPort = -1;
    String locators;
    String user;
    String password;
    String extraConnProps;
    final StringBuilder properties = new StringBuilder();
  }

  // common command arguments
  protected static final String CONNECTION_URL;
  protected static final String CLIENT_PORT;
  protected static final String CLIENT_BIND_ADDRESS;
  protected static final String MCAST_PORT;
  protected static final String MCAST_ADDRESS;
  protected static final String LOCATORS;
  protected static final String BIND_ADDRESS;
  protected static final String AUTH_PROVIDER;
  protected static final String USERNAME;
  protected static final String PASSWORD;
  // TODO: allow for passing as -<prop>=<value> like in server/locator scripts
  protected static final String EXTRA_CONN_PROPS;
  protected static final String SYSTEM_PROPERTY;

  // common top-level options
  private static final String HELP;

  /**
   * Interface to be implemented for processing of a command including adding
   * command specific options, and then processing and executing the command.
   */
  protected interface ProcessCommand {
    /**
     * Add options specific to the command.
     */
    void addCommandOptions(Options opts);

    /**
     * Execute the command.
     */
    void executeCommand(CommandLine cmdLine, String cmd,
        String cmdDescKey) throws ParseException, IOException, SQLException;
  }

  static {
    CONNECTION_URL = LocalizedResource.getMessage("TOOLS_CONNECTION_URL");
    CLIENT_PORT = LocalizedResource.getMessage("TOOLS_CLIENT_PORT");
    CLIENT_BIND_ADDRESS = LocalizedResource.getMessage("TOOLS_CLIENT_ADDRESS");
    MCAST_PORT = LocalizedResource.getMessage("TOOLS_MCAST_PORT");
    MCAST_ADDRESS = LocalizedResource.getMessage("TOOLS_MCAST_ADDRESS");
    LOCATORS = LocalizedResource.getMessage("TOOLS_LOCATORS");
    BIND_ADDRESS = LocalizedResource.getMessage("TOOLS_BIND_ADDRESS");
    AUTH_PROVIDER = LocalizedResource.getMessage("TOOLS_AUTH_PROVIDER");
    USERNAME = LocalizedResource.getMessage("TOOLS_USERNAME");
    PASSWORD = LocalizedResource.getMessage("TOOLS_PASSWORD");
    EXTRA_CONN_PROPS = LocalizedResource.getMessage("TOOLS_EXTRA_CONN_PROPS");
    SYSTEM_PROPERTY = LocalizedResource.getMessage("TOOLS_SYSTEM_PROPERTY");

    HELP = LocalizedResource.getMessage("TOOLS_HELP");
  }

  protected void invoke(final String[] args) {
    final String cmd = args[0];
    final String cmdDescKey = getCommandToDescriptionKeyMap().get(cmd);
    final CommandLineParser parser = new GfxdParser();

    try {
      final ProcessCommand processor = getCommandProcessor(cmd);
      if (processor != null) {
        this.currentOpts = buildCommandOptions(processor);
        // parse the command line arguments
        final CommandLine cmdLine = parser.parse(this.currentOpts, args);
        processor.executeCommand(cmdLine, cmd, cmdDescKey);
      }
      else {
        throw new ParseException(args[0]);
      }
    } catch (ParseException pe) {
      final GfxdHelpFormatter helpFormatter = new GfxdHelpFormatter();
      if (args.length == 2
          && cmd != null
          && (args[1].equals(helpFormatter.getOptPrefix() + HELP) || args[1]
              .equals(helpFormatter.getLongOptPrefix() + HELP))) {
        showUsage(null, cmd, cmdDescKey);
      }
      else {
        final String errMsg = LocalizedResource
            .getMessage("TOOLS_PARSE_EXCEPTION") + " " + pe.getMessage();
        if (cmd != null) {
          showUsage(errMsg, cmd, cmdDescKey);
        }
        else {
          System.err.println(errMsg);
        }
        // pe.printStackTrace(System.err);
        throw new GemFireTerminateError("exiting due to parse exception", 1, pe);
      }
    } catch (IOException ioe) {
      System.err.println(LocalizedResource.getMessage("TOOLS_OTHER_EXCEPTION")
          + " " + ioe.getMessage());
      ioe.printStackTrace(System.err);
      throw new GemFireTerminateError("exiting due to IO exception", 1, ioe);
    } catch (SQLException sqle) {
      System.err.println(LocalizedResource.getMessage("TOOLS_OTHER_EXCEPTION")
          + " " + sqle.getMessage());
      sqle.printStackTrace(System.err);
      throw new GemFireTerminateError("exiting due to SQL exception", 1, sqle);
    }
  }

  protected Options buildCommandOptions(final ProcessCommand processor) {
    final Options opts = new Options();
    addConnectionOptions(opts);
    addOptionalConnectionOptions(opts);
    addCommonOptions(opts);
    processor.addCommandOptions(opts);
    return opts;
  }

  protected void showUsage(String header, String cmd, String cmdDescKey) {
    final ConsoleReader reader = GfxdUtilLauncher.getConsoleReader();
    int width = reader != null ? reader.getTerminal().getWidth() : 80;
    final StringPrintWriter pw = new StringPrintWriter();
    if (header != null) {
      pw.println(header);
      pw.println();
    }

    final GfxdHelpFormatter formatter = new GfxdHelpFormatter();
    formatter.printHelp(pw, width, cmd,
        LocalizedResource.getMessage(cmdDescKey), this.currentOpts, 2,
        GfxdHelpFormatter.DEFAULT_DESC_PAD, getUsageString(cmd, cmdDescKey),
        true);
    // add the examples, if any
    printUsageExamples(formatter, pw, width, cmd, cmdDescKey);

    GfxdUtilLauncher.printUsage(pw.toString(),
        SanityManager.DEFAULT_MAX_OUT_LINES, reader);
  }

  /**
   * Get the mapping from command-name to its description key that can be used
   * to lookup the localized description string using {@link LocalizedResource}.
   */
  protected abstract Map<String, String> getCommandToDescriptionKeyMap();

  /**
   * Get a processor specific to the provided command.
   */
  protected abstract ProcessCommand getCommandProcessor(String cmd);

  /**
   * Get the usage string (without any formatting etc.) in case of an exception
   * or when explicitly requested.
   */
  protected abstract String getUsageString(String cmd, String cmdDescKey);

  protected void printUsageExamples(final GfxdHelpFormatter formatter,
      final StringPrintWriter pw, int width, final String cmd,
      final String cmdDescKey) {
    int exampleNumber = 1;
    for (;;) {
      final String exampleKey = cmdDescKey + "_EXAMPLE" + exampleNumber;
      final String example = LocalizedResource.getMessage(exampleKey, cmd);
      if (example.startsWith(exampleKey)) {
        break;
      }
      if (exampleNumber == 1) {
        pw.println();
        pw.println(LocalizedResource.getMessage("TOOLS_EXAMPLES_HEADER"));
      }
      pw.println();
      formatter.printWrapped(pw, width, example);
      exampleNumber++;
    }
  }

  protected void addConnectionOptions(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_URL_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_CONNECTION_URL_MESSAGE")).create(CONNECTION_URL);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_PORT_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_CLIENT_PORT_MESSAGE")).create(CLIENT_PORT);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_ADDRESS_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_CLIENT_ADDRESS_MESSAGE")).create(CLIENT_BIND_ADDRESS);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_USERNAME_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_USERNAME_MESSAGE")).create(USERNAME);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_PASSWORD_ARG")).hasOptionalArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_PASSWORD_MESSAGE")).create(PASSWORD);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_EXTRA_CONN_PROPS_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_EXTRA_CONN_PROPS_MESSAGE")).create(EXTRA_CONN_PROPS);
    opts.addOption(opt);
  }

  protected void addOptionalConnectionOptions(final Options opts) {
    GfxdOption opt;
    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_LOCATORS_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_LOCATORS_MESSAGE")).create(LOCATORS);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_PORT_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_MCAST_PORT_MESSAGE")).create(MCAST_PORT);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_ADDRESS_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_MCAST_ADDRESS_MESSAGE")).create(MCAST_ADDRESS);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_ADDRESS_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_BIND_ADDRESS_MESSAGE")).create(BIND_ADDRESS);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_AUTH_PROVIDER_ARG")).hasArg().withValueSeparator('=')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_AUTH_PROVIDER_MESSAGE")).create(AUTH_PROVIDER);
    opts.addOption(opt);
  }

  protected void addCommonOptions(final Options opts) {
    GfxdOption opt;

    opt = new GfxdOptionBuilder().withArgName(LocalizedResource.getMessage(
        "TOOLS_SYSTEM_PROPERTY_ARG")).hasArg().withValueSeparator('D')
        .withDescription(LocalizedResource.getMessage(
            "TOOLS_SYSTEM_PROPERTY_MESSAGE")).create(SYSTEM_PROPERTY);
    opts.addOption(opt);

    opt = new GfxdOptionBuilder()
        .withDescription(LocalizedResource.getMessage("TOOLS_HELP_MESSAGE"))
        .withLongOpt(HELP).create(HELP);
    opts.addOption(opt);
  }

  protected boolean handleCommonOption(final GfxdOption opt, final String cmd,
      final String cmdDescKey) throws ParseException {
    if (SYSTEM_PROPERTY.equals(opt.getOpt())) {
      String optValue = opt.getValue();
      int indexOfEquals = optValue.indexOf('=');
      if (indexOfEquals >= 0) {
        System.setProperty(optValue.substring(0, indexOfEquals),
            optValue.substring(indexOfEquals + 1));
      }
      else {
        throw new MissingArgumentException(opt);
      }
    }
    else if (HELP.equals(opt.getOpt())) {
      showUsage(null, cmd, cmdDescKey);
      throw new GemFireTerminateError(null, 0);
    }
    else {
      return false;
    }
    return true;
  }

  protected boolean handleConnectionOption(final GfxdOption opt,
      final ConnectionOptions connOpts) {
    String optValue;
    if ((optValue = opt.getOptionValue(MCAST_ADDRESS)) != null) {
      connOpts.properties.append(';').append(MCAST_ADDRESS).append('=')
          .append(optValue);
      connOpts.mcastPort = DistributionConfig.DEFAULT_MCAST_PORT;
    }
    else if ((optValue = opt.getOptionValue(MCAST_PORT)) != null) {
      connOpts.mcastPort = Integer.parseInt(optValue);
    }
    else if ((optValue = opt.getOptionValue(LOCATORS)) != null) {
      connOpts.locators = optValue;
      connOpts.properties.append(';').append(LOCATORS).append('=')
          .append(optValue);
    }
    else if ((optValue = opt.getOptionValue(BIND_ADDRESS)) != null) {
      connOpts.properties.append(';').append(BIND_ADDRESS).append('=')
          .append(optValue);
    }
    else if ((optValue = opt.getOptionValue(AUTH_PROVIDER)) != null) {
      connOpts.properties.append(';').append(AUTH_PROVIDER).append('=')
          .append(optValue);
    }
    else if ((optValue = opt.getOptionValue(CLIENT_BIND_ADDRESS)) != null) {
      connOpts.clientBindAddress = optValue;
    }
    else if ((optValue = opt.getOptionValue(CONNECTION_URL)) != null) {
      connOpts.connectionUrl = optValue;
    }
    else if ((optValue = opt.getOptionValue(CLIENT_PORT)) != null) {
      connOpts.clientPort = Integer.parseInt(optValue);
    }
    else if ((optValue = opt.getOptionValue(USERNAME)) != null) {
      connOpts.user = optValue;
    }
    else if ((optValue = opt.getOptionValue(PASSWORD)) != null) {
      connOpts.password = optValue;
    }
    else if ((optValue = opt.getOptionValue(EXTRA_CONN_PROPS)) != null) {
      connOpts.extraConnProps = optValue;
    }
    else {
      return false;
    }
    return true;
  }

  protected void handleConnectionOptions(final ConnectionOptions connOpts,
      final String cmd, final String cmdDescKey) {
    if (connOpts.mcastPort >= 0 || connOpts.locators != null) {
      // client options should not be present in this case
      if (connOpts.clientPort >= 0 || connOpts.clientBindAddress != null) {
        showUsage(LocalizedResource.getMessage(
            "TOOLS_BOTH_EMBEDDED_CLIENT_ERROR"), cmd, cmdDescKey);
        throw new GemFireTerminateError(
            "exiting due to incorect connection options", 1);
      }
      if (connOpts.mcastPort > 0) {
        connOpts.properties.append(';').append(MCAST_PORT).append('=')
            .append(connOpts.mcastPort);
      }
    }
    else {
      if (connOpts.clientBindAddress == null) {
        connOpts.clientBindAddress = "localhost";
      }
      if (connOpts.clientPort < 0) {
        connOpts.clientPort = FabricService.NETSERVER_DEFAULT_PORT;
      }
    }
    if (connOpts.password != null && connOpts.password.length() == 0) {
      // read from console
      final Console cons = System.console();
      if (cons == null) {
        throw new IllegalStateException(
            LocalizedResource.getMessage("TOOLS_NO_CONSOLE_EXCEPTION"));
      }
      final char[] pwd = cons.readPassword(LocalizedResource
          .getMessage("UTIL_password_Prompt"));
      if (pwd != null) {
        connOpts.password = new String(pwd);
      }
    }
    // set "read-timeout=0" by default for thin client connections
    boolean addReadTimeout = (connOpts.clientPort > 0);
    if (connOpts.extraConnProps != null) {
      connOpts.properties.append(';').append(connOpts.extraConnProps);
      if (connOpts.extraConnProps.contains("read-timeout=")) {
        addReadTimeout = false;
      }
    }
    if (addReadTimeout) {
      connOpts.properties.append(";read-timeout=0");
    }
  }

  protected Connection getConnection(final ConnectionOptions connOpts,
      final String cmd, final String cmdDescKey) throws SQLException {
    handleConnectionOptions(connOpts, cmd, cmdDescKey);
    final StringBuilder urlBuilder = new StringBuilder();
    if (connOpts.connectionUrl != null) {
      Assert.assertTrue(connOpts.connectionUrl.startsWith("jdbc:"),
          "url must start with jdbc:. passed url=" + connOpts.connectionUrl);
      urlBuilder.append(connOpts.connectionUrl);
    } else if (connOpts.clientPort < 0) {
      String protocol = GfxdUtilLauncher.isSnappyStore()
          ? Attribute.SNAPPY_PROTOCOL : Attribute.PROTOCOL;
      urlBuilder.append(protocol).append(";host-data=false");
    }
    else {
      String protocol = GfxdUtilLauncher.isSnappyStore()
          ? Attribute.SNAPPY_DNC_PROTOCOL : Attribute.DNC_PROTOCOL;
      final String hostName = connOpts.clientBindAddress != null
          ? connOpts.clientBindAddress : "localhost";
      urlBuilder.append(protocol).append(hostName).append(':')
          .append(connOpts.clientPort).append('/');
    }
    if (connOpts.properties.length() > 0) {
      urlBuilder.append(connOpts.properties);
    }
    if (connOpts.user != null) {
      if (connOpts.connectionUrl != null) {
        System.out.println("Using connection url " + urlBuilder.toString());
      }
      return DriverManager.getConnection(urlBuilder.toString(), connOpts.user,
          connOpts.password);
    }
    else {
      return DriverManager.getConnection(urlBuilder.toString());
    }
  }
}
