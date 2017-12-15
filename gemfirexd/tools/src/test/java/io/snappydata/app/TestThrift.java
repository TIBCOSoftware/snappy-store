/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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
package io.snappydata.app;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;

import com.gemstone.gemfire.internal.GemFireTerminateError;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import io.snappydata.thrift.*;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

public class TestThrift {

  public static void main(String[] args) throws Exception {
    String locatorHost = "localhost";
    int locatorPort = 1530;

    if (args.length > 0) {
      locatorHost = args[0];
      if (args.length > 1) {
        try {
          locatorPort = Integer.parseInt(args[1]);
        } catch (NumberFormatException nfe) {
          throw new GemFireTerminateError(nfe.toString() +
              "\nUsage: java -cp ... TestThrift [<locator-host> <locator-port>]", 1);
        }
      }
    }

    try {
      // set the third "framedTransport" argument to true to enable framed
      // transport which will also require the locators/servers to use
      // "-thrift-framed-transport=true" option at startup
      run(locatorHost, locatorPort, false);
    } catch (GemFireTerminateError err) {
      System.err.println(err.getMessage());
      System.exit(err.getExitCode());
    }
  }

  public static void run(String locatorHost, int locatorPort,
      boolean framedTransport) throws Exception {
    // This adds the process ID to its unique client ID but this can be
    // something else like a UUID that can be used to distinguish it uniquely
    // on this client machine. The combination of this with the hostName above
    // is assumed to be unique by the server and used to track/retrieve
    // connections from a machine and detect if a machine has failed (and thus
    //   all its connections need to be cleaned up).
    int pid = NativeCalls.getInstance().getProcessId();

    // search only for servers using TCompactProtocol without SSL
    Set<ServerType> serverType = Collections.singleton(ServerType.THRIFT_SNAPPY_CP);
    TSocket socket = new TSocket(locatorHost, locatorPort);
    final TTransport transport;
    // currently there is no search for framed servers and it is assumed
    // that servers are using framed transport when client is so configured
    if (framedTransport) {
      transport = new TFramedTransport(socket);
    } else {
      transport = socket;
    }
    TCompactProtocol inProtocol = new TCompactProtocol(transport);
    TCompactProtocol outProtocol = new TCompactProtocol(transport);
    transport.open();
    // establish a connection to locator which will be used to get the
    // server as per load balancing to which data connection will be made
    LocatorService.Client controlService = new LocatorService.Client(
        inProtocol, outProtocol);

    ClientConnection conn = ClientConnection.open("localhost", pid,
        controlService, serverType, framedTransport);

    // the unique token assigned by server is required in all the calls and
    // helps make the protocol "stateless" in the sense that any thrift
    // connection to the server can be used to fire commands corresponding
    // to this connection
    ByteBuffer token = conn.getToken();

    // create the table
    conn.execute(conn.getId(), "drop table if exists orders",
        null, null, token);
    conn.execute(conn.getId(), "create table orders (" +
        "no_w_id  integer   not null," +
        "no_d_id  integer   not null," +
        "no_o_id  integer   not null," +
        "no_name  varchar(100) not null" +
        ") partition by (no_w_id)", null, null, token);

    System.out.println("Created table");

    // inserts using a prepared statement with parameters for best performance
    final int numRows = 10000;
    PrepareResult pstmt = conn.prepareStatement(conn.getId(),
        "insert into orders values (?, ?, ?, ?)", null, null, token);

    System.out.println("Starting inserts");

    int id, w_id, count;
    String name;
    Row params = new Row(pstmt.getParameterMetaData());
    for (id = 1; id <= numRows; id++) {
      w_id = (id % 98);
      name = "customer-with-order" + id + '_' + w_id;

      params.setInt(0, w_id);
      params.setInt(1, w_id);
      params.setInt(2, id);
      params.setObject(3, name, SnappyType.VARCHAR);

      count = conn.executePreparedUpdate(pstmt.statementId,
          params, null, token).updateCount;
      if (count != 1) {
        throw new GemFireTerminateError(
            "Unexpected count for single insert: " + count, 2);
      }
      if ((id % 500) == 0) {
        System.out.println("Completed " + id + " inserts ...");
      }
    }

    // selects with first round being "warmup"
    pstmt = conn.prepareStatement(conn.getId(), "SELECT * FROM orders " +
            "WHERE no_d_id = ? AND no_w_id = ? AND no_o_id = ?",
        null, null, token);
    params = new Row(pstmt.getParameterMetaData());

    final int numRuns = 50000;
    int rowNum;
    RowSet rs;
    StatementResult sr;

    // first round is warmup for the selects
    for (int runNo = 1; runNo <= 2; runNo++) {
      long start = System.currentTimeMillis();
      if (runNo == 1) {
        System.out.println("Starting warmup selects");
      } else {
        System.out.println("Starting timed selects");
      }
      for (int i = 1; i <= numRuns; i++) {
        rowNum = (i % numRows) + 1;
        w_id = (rowNum % 98);

        params.setInt(0, w_id);
        params.setInt(1, w_id);
        params.setInt(2, rowNum);

        sr = conn.executePrepared(pstmt.statementId, params,
            Collections.emptyMap(), null, token);

        // below will also work as well returning a RowSet directly
        // rs = conn.executePreparedQuery(pstmt.statementId, params, null, token);
        rs = sr.getResultSet();

        int numResults = 0;
        for (Row row : rs.getRows()) {
          row.getInt(1);
          row.getObject(3);
          numResults++;
        }
        if (numResults == 0) {
          throw new GemFireTerminateError(
              "Unexpected 0 results for w_id,d_id = " + w_id, 2);
        }
        if (runNo == 1 && (i % 500) == 0) {
          System.out.println("Completed " + i + " warmup selects ...");
        }
      }
      long end = System.currentTimeMillis();

      if (runNo == 2) {
        System.out.println("Time taken for " + numRuns + " selects: " +
            (end - start) + "ms");
      }
    }

    // drop the table and close the connection
    conn.execute(conn.getId(), "drop table orders", null, null, token);
    conn.close();
  }

  /**
   * A simple extension to the SnappyData client service to encapsulate
   * load-balancing using the locator "control" connection.
   */
  static class ClientConnection extends SnappyDataService.Client {

    ConnectionProperties connProperties;

    private ClientConnection(TCompactProtocol inProtocol,
        TCompactProtocol outProtocol) {
      super(inProtocol, outProtocol);
    }

    public long getId() {
      return this.connProperties.connId;
    }

    public ByteBuffer getToken() {
      return this.connProperties.token;
    }

    /**
     * Open a data connection using given locator connection.
     */
    static ClientConnection open(String hostName, int pid,
        LocatorService.Client controlService,
        Set<ServerType> serverType, boolean framedTransport) throws TException {
      HostAddress preferredServer = controlService.getPreferredServer(
          serverType, null, null);

      System.out.println("Attempting connection to preferred server:port = " +
          preferredServer.getHostName() + ':' + preferredServer.getPort());

      TSocket socket = new TSocket(preferredServer.getHostName(),
          preferredServer.getPort());
      final TTransport transport;
      if (framedTransport) {
        transport = new TFramedTransport(socket);
      } else {
        transport = socket;
      }
      TCompactProtocol inProtocol = new TCompactProtocol(transport);
      TCompactProtocol outProtocol = new TCompactProtocol(transport);
      transport.open();

      Thread currentThread = Thread.currentThread();
      OpenConnectionArgs connArgs = new OpenConnectionArgs()
          .setClientHostName(hostName)
          .setClientID(pid + "|0x" + Long.toHexString(currentThread.getId()))
          .setSecurity(SecurityMechanism.PLAIN);
      ClientConnection connection = new ClientConnection(
          inProtocol, outProtocol);
      connection.connProperties = connection.openConnection(connArgs);
      return connection;
    }

    void close() throws TException {
      closeConnection(connProperties.connId, true, connProperties.token);
      getInputProtocol().getTransport().close();
    }
  }
}
