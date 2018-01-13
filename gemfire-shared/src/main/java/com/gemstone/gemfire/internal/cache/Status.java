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
/*
 * Changes for SnappyData distributed computational and data platform.
 *
 * Portions Copyright (c) 2017 SnappyData, Inc. All rights reserved.
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

package com.gemstone.gemfire.internal.cache;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;

import com.gemstone.gemfire.internal.shared.LauncherBase;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.SYNC;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

/**
 * A class that represents the status of a cache server.  Instances
 * of this class are serialized to a status file on disk.
 *
 * @see #SHUTDOWN
 * @see #STARTING
 * @see #RUNNING
 * @see #SHUTDOWN_PENDING
 * @see #WAITING
 */
public class Status {

  public static final int SHUTDOWN = 0;
  public static final int STARTING = 1;
  public static final int RUNNING = 2;
  public static final int SHUTDOWN_PENDING = 3;
  public static final int WAITING = 4;
  public static final int STANDBY = 5;

  /**
   * Version of this class that will be used for backward compatibility in
   * serialization/deserialization.
   */
  private static final int CLASS_VERSION = 1;

  public int state;
  public int pid;

  private final String baseName;
  public String msg;
  public String dsMsg;
  public String exceptionStr;

  private transient Path statusFile;

  private Status(String baseName, int state, int pid, String msg, Throwable t,
      Path statusFile) {
    this.baseName = baseName;
    this.state = state;
    this.pid = pid;
    this.msg = msg;
    if (t != null) {
      StringPrintWriter pw = new StringPrintWriter();
      t.printStackTrace(pw);
      this.exceptionStr = pw.toString();
    }
    this.statusFile = statusFile;
  }

  public static Status create(String baseName, int state, int pid,
      Path statusFile) {
    return create(baseName, state, pid, null, null, statusFile);
  }

  public static Status create(String baseName, int state, int pid,
      String msg, Throwable t, Path statusFile) {
    return new Status(baseName, state, pid, msg, t, statusFile);
  }

  private static void writeString(String s, DataOutputStream out) throws IOException {
    if (s != null) {
      out.writeBoolean(true);
      out.writeUTF(s);
    } else {
      out.writeBoolean(false);
    }
  }

  private static String readString(DataInputStream in) throws IOException {
    return in.readBoolean() ? in.readUTF() : null;
  }

  /**
   * Sets the status of a cache server by serializing a <code>Status</code>
   * instance to a file in the server's working directory.
   */
  public void write() throws IOException {
    try (OutputStream stream = Files.newOutputStream(statusFile,
        CREATE, TRUNCATE_EXISTING, SYNC)) {
      if (!Files.exists(statusFile)) {
        NativeCalls.getInstance().preBlow(statusFile.toString(), 2048, true);
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bos);
      out.writeInt(CLASS_VERSION);
      out.writeInt(state);
      out.writeInt(pid);
      out.writeUTF(baseName);
      writeString(msg, out);
      writeString(dsMsg, out);
      writeString(exceptionStr, out);
      out.flush();
      stream.write(bos.toByteArray());
    }
  }

  /**
   * Reads a cache server's status from a file in its working directory.
   */
  public static Status read(String baseName, Path statusFile)
      throws InterruptedException, IOException {

    int maxTries = 100;
    while (true) {
      try (InputStream stream = Files.newInputStream(statusFile);
           BufferedInputStream bin = new BufferedInputStream(stream, 128);
           DataInputStream in = new DataInputStream(bin)) {

        int version = in.readInt();
        if (version != CLASS_VERSION) {
          throw new IOException(MessageFormat.format(LauncherBase
                  .LAUNCHER_UNREADABLE_STATUS_FILE, statusFile.toAbsolutePath(),
              "Unknown class version = " + version));
        }
        int state = in.readInt();
        int pid = in.readInt();
        String name = in.readUTF();
        String msg = readString(in);
        String dsMsg = readString(in);
        String exceptionStr = readString(in);
        Status status = new Status(name, state, pid, msg, null, statusFile);
        status.dsMsg = dsMsg;
        status.exceptionStr = exceptionStr;

        // See bug 32760
        // Note, only execute the conditional createStatus statement if we are in
        // native mode; if we are in pure Java mode
        // the the process ID identified in the Status object is assumed to exist!
        if (status.state != SHUTDOWN && status.pid > 0
            && !isExistingProcess(status.pid)) {
          status = create(baseName, SHUTDOWN, status.pid, statusFile);
        }

        return status;
      } catch (FileNotFoundException e) {
        Thread.sleep(500);
        if (Files.exists(statusFile)) {
          if (maxTries-- <= 0) {
            throw e;
          }
        } else {
          throw e;
        }
      }
    }
  }

  /**
   * Reads a cache server's status.  If the status file cannot be read
   * because of I/O problems, it will try again.
   */
  public static Status spinRead(String baseName, Path statusFile) {
    final long timeout = (System.currentTimeMillis() + 60000);
    Status status = null;

    while (status == null && System.currentTimeMillis() < timeout) {
      try {
        status = read(baseName, statusFile);
      } catch (Exception e) {
        // try again - the status might have been read in the middle of it
        // being written by the server resulting in an EOFException here
        try {
          Thread.sleep(500);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          status = null;
          break;
        }
      }
    }

    return status;
  }

  /**
   * Removes the status file.
   */
  public static void delete(Path statusFile) throws IOException {
    Files.deleteIfExists(statusFile);
  }

  /**
   * Removes the status file.
   */
  public static void delete(String workingDir, String statusFileName)
      throws IOException {
    delete(Paths.get(workingDir, statusFileName));
  }

  public static boolean isExistingProcess(final int pid) {
    try {
      return NativeCalls.getInstance().isProcessActive(pid);
    } catch (UnsupportedOperationException uoe) {
      // no native API to determine if process exists, so assume it to be true
      return true;
    }
  }

  @Override
  public String toString() {
    final StringBuilder buffer = new StringBuilder();
    buffer.append(shortStatus());
    if (this.dsMsg != null) {
      buffer.append('\n').append(this.dsMsg);
    }
    return buffer.toString();
  }

  public String shortStatus() {
    final StringBuilder buffer = new StringBuilder();
    buffer.append(this.baseName).append(" pid: ").append(pid).append(" status: ");
    switch (state) {
      case SHUTDOWN:
        buffer.append("stopped");
        break;
      case STARTING:
        buffer.append("starting");
        break;
      case RUNNING:
        buffer.append("running");
        break;
      case SHUTDOWN_PENDING:
        buffer.append("stopping");
        break;
      case WAITING:
        buffer.append("waiting");
        break;
      case STANDBY:
        buffer.append("standby");
        break;
      default:
        buffer.append("unknown");
        break;
    }
    if (exceptionStr != null || msg != null) {
      if (msg != null) {
        buffer.append("\n").append(msg);
      } else {
        buffer.append("\nException in ").append(this.baseName);
      }
      if ((state != WAITING && state != RUNNING) || msg == null) {
        buffer.append(" - ").append(LauncherBase.LAUNCHER_SEE_LOG_FILE);
      }
    }
    return buffer.toString();
  }
}
