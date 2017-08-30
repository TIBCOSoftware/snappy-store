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
package sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class TestFunctions {

  public static int getMaxCid(int tid) throws SQLException {
    boolean[] success = new boolean[1];
    int cid = 0;
    cid = getMaxCid(tid, success);
    while (!success[0]) {
      cid = getMaxCid(tid, success);
    }
    return cid;
  }

  // retrying for HA test when node went down
  private static int getMaxCid(int tid, boolean[] success) throws SQLException {
    int cid = 0;
    Connection conn = DriverManager.getConnection("jdbc:default:connection");
    PreparedStatement ps1;
    success[0] = true;
    ps1 = conn.prepareStatement(
        "select max(cid) as lastcid from trade.networth where tid=?");
    ps1.setInt(1, tid);
    ResultSet rs = ps1.executeQuery();
    if (rs.next()) {
      cid = rs.getInt("LASTCID");
    }
    rs.close();
    conn.close();
    return cid;
  }

  public static int getMaxCidForBug41005(int tid) throws SQLException {
    int cid = 0;
    Connection conn = DriverManager.getConnection("jdbc:default:connection");
    PreparedStatement ps1 = conn.prepareStatement(
        "select max(cid) as lastcid from trade.networth where tid=?");
    ps1.setInt(1, tid);
    ResultSet rs = ps1.executeQuery();
    if (rs.next()) {
      cid = rs.getInt("LASTCID");
    }
    rs.close();
    conn.close();

    return cid;
  }

  public static int getMaxCidBug41222() throws SQLException {
    Connection conn = DriverManager.getConnection("jdbc:default:connection");
    PreparedStatement ps1 = conn.prepareStatement(
        "select ID from EMP.TESTTABLE");
    ResultSet rs = ps1.executeQuery();
    rs.next();
    int val = rs.getInt(1);
    rs.close();
    conn.close();
    return val;
  }
}
