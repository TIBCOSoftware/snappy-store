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
package com.gemstone.gemfire.management.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.lang.StringUtils;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * @author jdeppe
 * @author jblum
 * @since 7.0
 */
@SuppressWarnings("unused")
public class JettyHelper {

  private static final String FILE_PATH_SEPARATOR = System.getProperty(
      "file.separator");
  private static final String USER_DIR = System.getProperty("user.dir");

  private static final String USER_NAME = System.getProperty("user.name");

  private static String bindAddress = "0.0.0.0";

  private static int port = 0;

  public static Server initJetty(final String bindAddress, final int port,
      final LogWriterI18n log) throws Exception {
    final Server jettyServer = new Server();

    // Add a handler collection here, so that each new context adds itself
    // to this collection.
    jettyServer.setHandler(new HandlerCollection());

    // bind on address and port
    setAddressAndPort(jettyServer, bindAddress, port);

    if (bindAddress != null && !bindAddress.isEmpty()) {
      JettyHelper.bindAddress = bindAddress;
    }
    JettyHelper.port = port;

    return jettyServer;
  }

  public static Server startJetty(final Server jetty) throws Exception {
    jetty.start();
    return jetty;
  }

  public static Server addWebApplication(final Server jetty,
      final String webAppContext, final String warFilePath) throws IOException {
    WebAppContext webapp = new WebAppContext();
    webapp.setContextPath(webAppContext);
    webapp.setWar(warFilePath);
    webapp.setParentLoaderPriority(false);

    File tmpPath = new File(getWebAppBaseDirectory(webAppContext));
    Files.createDirectories(tmpPath.toPath());
    webapp.setTempDirectory(tmpPath);

    ((HandlerCollection) jetty.getHandler()).addHandler(webapp);

    return jetty;
  }

  private static void setAddressAndPort(final Server jettyServer,
      final String bindAddress, final int port) {
    HttpConfiguration httpConfig = new HttpConfiguration();
    ServerConnector connector = new ServerConnector(jettyServer, new HttpConnectionFactory(httpConfig));
    connector.setPort(port);

    if (!StringUtils.isBlank(bindAddress)) {
      connector.setHost(bindAddress);
    }
    jettyServer.addConnector(connector);
  }

  private static String getWebAppBaseDirectory(final String context) {
    String underscoredContext = context.replace("/", "_");
    final String workingDirectory = USER_DIR
        .concat(FILE_PATH_SEPARATOR)
        .concat("GemFire_" + USER_NAME)
        .concat(FILE_PATH_SEPARATOR)
        .concat("services")
        .concat(FILE_PATH_SEPARATOR)
        .concat("http")
        .concat(FILE_PATH_SEPARATOR)
        .concat(
            (StringUtils.isBlank(bindAddress)) ? "0.0.0.0" : bindAddress)
        .concat("_")
        .concat(String.valueOf(port)
        .concat(underscoredContext));

    return workingDirectory;
  }

  private static final CountDownLatch latch = new CountDownLatch(1);

  private static String normalizeWebAppArchivePath(
      final String webAppArchivePath) {
    return (webAppArchivePath.startsWith(File.separator) ? new File(
        webAppArchivePath) :
        new File(".", webAppArchivePath)).getAbsolutePath();
  }

  private static String normalizeWebAppContext(final String webAppContext) {
    return (webAppContext.startsWith(
        "/") ? webAppContext : "/" + webAppContext);
  }

}
