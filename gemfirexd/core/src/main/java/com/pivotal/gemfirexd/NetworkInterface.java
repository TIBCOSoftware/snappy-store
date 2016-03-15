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

package com.pivotal.gemfirexd;

import java.net.Socket;
import java.util.Properties;

import com.pivotal.gemfirexd.thrift.ServerType;

/**
 * Encapsulates a network listener that clients can use to connect using
 * GemFireXD JDBC client driver (URL of the form
 * 'jdbc:gemfirexd://&lt;host&gt;:&lt;port&gt;'). A new interface can be created
 * using {@link FabricServer#startNetworkServer(String, int, Properties)} -- see
 * {@link FabricServer} API for details.
 * 
 * @see FabricServer
 * 
 * @author Soubhik C
 */
public interface NetworkInterface {

  /**
   * Get the type of this server (DRDA or one of the Thrift server
   * configuration).
   */
  ServerType getServerType();

  /**
   * Stop listening on network clients on this network server control.
   */
  void stop();

  /**
   * Whether the network server is listening on the configured port or not.
   * 
   * @return <tt>true</tt> if server socket is open for connection, otherwise
   *         <tt>false</tt>.
   */
  boolean status();

  /**
   * Turn tracing on or off for all sessions on the Network Server.
   * 
   * @param on
   *          true to turn tracing on, false to turn tracing off.
   */
  public void trace(boolean on);

  /**
   * Turn tracing on or off for the specified connection on the Network Server.
   * 
   * @param connNum
   *          connection number. Note: Connection numbers will print in the
   *          GemFireXD error log if logConnections is on.
   * @param on
   *          true to turn tracing on, false to turn tracing off.
   */
  public void trace(int connNum, boolean on);

  /**
   * Turn logging connections on or off. When logging is turned on a message is
   * written to the GemFireXD log each time a connection is made.
   * 
   * @param on
   *          true to turn on, false to turn off
   */
  public void logConnections(boolean on);

  /**
   * Set directory for trace files. The directory must be on the machine where
   * the server is running.
   * 
   * @param traceDirectory
   *          directory for trace files on machine where server is running
   */
  public void setTraceDirectory(String traceDirectory);

  /**
   * Set a new {@link ConnectionListener} for this network server. This will
   * replace any old listener if any.
   * 
   * @param listener
   *          the {@link ConnectionListener} to be added
   */
  public void setConnectionListener(ConnectionListener listener);

  /**
   * Return classpath and version information about the running Network Server.
   * 
   * @return sysinfo output
   */
  public String getSysinfo();

  /**
   * Return detailed session runtime information about sessions, prepared
   * statements, and memory usage for the running Network Server.
   * 
   * @return run time information
   */
  public String getRuntimeInfo();

  /**
   * Set Network Server maxthread parameter. This is the maximum number of
   * threads that will be used for JDBC client connections. setTimeSlice should
   * also be set so that clients will yield appropriately.
   * 
   * @param max
   *          maximum number of connection threads. If &lt;= 0, connection
   *          threads will be created when there are no free connection threads.
   * 
   * @see #setTimeSlice
   */
  public void setMaxThreads(int max);

  /**
   * Returns the current maxThreads setting for the running Network Server
   * 
   * @return maxThreads setting
   * 
   * @see #setMaxThreads
   */
  public int getMaxThreads();

  /**
   * Set Network Server connection time slice parameter after which client
   * connections will yield. This should be set and is only relevant if
   * setMaxThreads &gt; 0.
   * 
   * @param timeslice
   *          number of milliseconds given to each session before yielding to
   *          another session, if &lt;=0, never yield.
   * 
   * @see #setMaxThreads
   */
  public void setTimeSlice(int timeslice);

  /**
   * Return the current timeSlice setting for the running Network Server
   * 
   * @return timeSlice setting
   * 
   * @see #setTimeSlice
   */
  public int getTimeSlice();

  /**
   * Get current Network server properties
   * 
   * @return Properties object containing Network server properties
   */
  public Properties getCurrentProperties();

  /**
   * Get the string representation of this <code>NetworkInterface</code> in
   * '&lt;host&gt;/&lt;bind-address&gt;[&lt;port&gt;]' format.
   */
  public String asString();

  /**
   * Get the total number of current connections to this network server.
   */
  public int getTotalConnections();

  /**
   * Get the host name of this network server.
   */
  public String getHostName();

  /**
   * Get the port of this network server.
   */
  public int getPort();

  /**
   * A listener which can be registered on {@link NetworkInterface}
   * in order to receive events about connections created or destroyed for a
   * client on the DRDA network server.
   * 
   * @author swale
   */
  public interface ConnectionListener {

    /**
     * Indicates that a new connection has been opened to this network server.
     * 
     * @param clientSocket
     *          the {@link Socket} object created for the client
     * @param connectionNumber
     *          the connection number for this client connection that gets
     *          incremented for every new client connection
     */
    void connectionOpened(Socket clientSocket, int connectionNumber);

    /**
     * Indicates that the a connection to this network server has been closed.
     * 
     * @param clientSocket
     *          the {@link Socket} object created for the client
     * @param connectionNumber
     *          the connection number for this client connection that gets
     *          incremented for every new client connection
     */
    void connectionClosed(Socket clientSocket, int connectionNumber);

    /**
     * Close any artifacts of this connection listener. Invoked when the network
     * server has been stopped cleanly.
     */
    void close();
  }
}
