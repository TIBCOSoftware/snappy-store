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

package com.gemstone.gemfire.internal.shared;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.Map;

/**
 * Enumerated type for client / server and p2p version.
 * 
 * There are dependencies in versioning code that require newer versions to have
 * ordinals higher than older versions in order to protect against
 * deserialization problems. Client/server code also uses greater-than
 * comparison of ordinals in backward-compatibility checks.
 * 
 * @since 5.7
 */
public final class Version implements Comparable<Version> {

  /** The name of this version */
  private final transient String name;

  /** The product name of this version */
  private final transient String productName;

  /** The suffix to use in toDataPre / fromDataPre method names */
  private final transient String methodSuffix;

  // the major, minor and release bits of the release
  private final byte majorVersion;

  private final byte minorVersion;

  private final byte release;

  private final byte patch;

  /**
   * Set to non-null if the underlying GemFire version is different from product
   * version (e.g. for GemFireXD)
   */
  private Version gemfireVersion;

  /** byte used as ordinal to represent this <code>Version</code> */
  private final short ordinal;

  public static final String Version_REMOTE_VERSION_NOT_SUPPORTED =
      "Peer or client version with ordinal {0} not supported. "
      + "Last known version is {1}";

  private static final Method getGFEClientCommands;

  public static final int NUM_OF_VERSIONS = 34;

  private static final Version[] VALUES = new Version[NUM_OF_VERSIONS];

  /**
   * Reserved token that cannot be used for product version but as a flag in
   * internal contexts.
   */
  private static final byte TOKEN_ORDINAL = -1;

  private static final int TOKEN_ORDINAL_INT = (TOKEN_ORDINAL & 0xFF);

  public static final Version TOKEN = new Version("", "TOKEN", (byte)-1,
      (byte)0, (byte)0, (byte)0, TOKEN_ORDINAL);

  private static final byte GFE_56_ORDINAL = 0;

  public static final Version GFE_56 = new Version("GFE", "5.6", (byte)5,
      (byte)6, (byte)0, (byte)0, GFE_56_ORDINAL);

  private static final byte GFE_57_ORDINAL = 1;

  public static final Version GFE_57 = new Version("GFE", "5.7", (byte)5,
      (byte)7, (byte)0, (byte)0, GFE_57_ORDINAL);

  private static final byte GFE_58_ORDINAL = 3;

  public static final Version GFE_58 = new Version("GFE", "5.8", (byte)5,
      (byte)8, (byte)0, (byte)0, GFE_58_ORDINAL);

  private static final byte GFE_603_ORDINAL = 4;

  public static final Version GFE_603 = new Version("GFE", "6.0.3", (byte)6,
      (byte)0, (byte)3, (byte)0, GFE_603_ORDINAL);

  private static final byte GFE_61_ORDINAL = 5;

  public static final Version GFE_61 = new Version("GFE", "6.1", (byte)6,
      (byte)1, (byte)0, (byte)0, GFE_61_ORDINAL);

  private static final byte GFE_65_ORDINAL = 6;

  public static final Version GFE_65 = new Version("GFE", "6.5", (byte)6,
      (byte)5, (byte)0, (byte)0, GFE_65_ORDINAL);

  private static final byte GFE_651_ORDINAL = 7;

  public static final Version GFE_651 = new Version("GFE", "6.5.1", (byte)6,
      (byte)5, (byte)1, (byte)0, GFE_651_ORDINAL);

  private static final byte GFE_6516_ORDINAL = 12;

  public static final Version GFE_6516 = new Version("GFE", "6.5.1.6", (byte)6,
      (byte)5, (byte)1, (byte)6, GFE_6516_ORDINAL);

  private static final byte GFE_66_ORDINAL = 16;

  public static final Version GFE_66 = new Version("GFE", "6.6", (byte)6,
      (byte)6, (byte)0, (byte)0, GFE_66_ORDINAL);

  private static final byte GFE_662_ORDINAL = 17;

  public static final Version GFE_662 = new Version("GFE", "6.6.2", (byte)6,
      (byte)6, (byte)2, (byte)0, GFE_662_ORDINAL);

  private static final byte GFE_6622_ORDINAL = 18;

  public static final Version GFE_6622 = new Version("GFE", "6.6.2.2", (byte)6,
      (byte)6, (byte)2, (byte)2, GFE_6622_ORDINAL);

  private static final byte GFE_70_ORDINAL = 19;

  public static final Version GFE_70 = new Version("GFE", "7.0", (byte)7,
      (byte)0, (byte)0, (byte)0, GFE_70_ORDINAL);

  private static final byte GFE_701_ORDINAL = 20;

  public static final Version GFE_701 = new Version("GFE", "7.0.1", (byte)7,
      (byte)0, (byte)1, (byte)0, GFE_701_ORDINAL);

  private static final byte GFE_7099_ORDINAL = 21;

  /**
   * This version is an intermediate one created to test rolling upgrades. It is
   * compatible with <code>GFXD_11</code> in all respects except for artifical
   * changes in a couple of P2P messages and marking as compatible with GFE_701.
   */
  public static final Version SQLF_1099 = new Version("SQLF", "1.0.99",
      (byte)1, (byte)0, (byte)99, (byte)0, GFE_7099_ORDINAL, GFE_70);

  /**
   * This is the GemFire conterpart of GFXD_1099 for testing rolling upgrades
   * and it uses the same ordinal as GFE_701 to maintain compatibility with the
   * ordinals being used on GemFireXD branch. Ordinals is same as that for
   * GFXD_1099 which is not an issue.
   */
  public static final Version GFE_7099 = new Version("GFE", "7.0.99", (byte)7,
      (byte)0, (byte)99, (byte)0, GFE_7099_ORDINAL);

  /** match the ordinal with Pivotal GemFire 7.1 for compatibility */
  private static final byte GFE_71_ORDINAL = 22;

  public static final Version GFE_71 = new Version("GFE", "7.1", (byte)7,
      (byte)1, (byte)0, (byte)0, GFE_71_ORDINAL);

  private static final byte SQLF_11_ORDINAL = 23;

  /**
   * SQLFire 1.1 has a separate version since it has changed the RowFormatter
   * formatting for ALTER TABLE add/drop column support. However, its underlying
   * GemFire version will remain at GFE_7x.
   */
  public static final Version SQLF_11 = new Version("SQLF", "1.1", (byte)1,
      (byte)1, (byte)0, (byte)0, SQLF_11_ORDINAL, GFE_7099);

  private static final byte GFE_75_ORDINAL = 24;

  public static final Version GFE_75 = new Version("GFE", "7.5", (byte)7,
      (byte)5, (byte)0, (byte)0, GFE_75_ORDINAL);



  private static final byte GFXD_10_ORDINAL = 25;

  public static final Version GFXD_10 = new Version("GFXD", "1.0", (byte)1,
      (byte)0, (byte)0, (byte)0, GFXD_10_ORDINAL, GFE_75);

  private static final byte GFXD_101_ORDINAL = 26;

  public static final Version GFXD_101 = new Version("GFXD", "1.0.1", (byte)1,
      (byte)0, (byte)1, (byte)0, GFXD_101_ORDINAL, GFE_75);

  private static final byte GFXD_1011_ORDINAL = 27;

  public static final Version GFXD_1011 = new Version("GFXD", "1.0.1.1",
      (byte)1, (byte)0, (byte)1, (byte)1, GFXD_1011_ORDINAL, GFE_75);

  private static final byte GFXD_13_ORDINAL = 28;

  public static final Version GFXD_13 = new Version("GFXD", "1.3",
      (byte)1, (byte)3, (byte)0, (byte)0, GFXD_13_ORDINAL, GFE_75);

  private static final byte GFXD_1302_ORDINAL = 29;

  public static final Version GFXD_1302 = new Version("GFXD", "1.3.0.2",
      (byte)1, (byte)3, (byte)0, (byte)2, GFXD_1302_ORDINAL, GFE_75);

  private static final byte GFXD_14_ORDINAL = 30;

  public static final Version GFXD_14 = new Version("GFXD", "1.4",
      (byte)1, (byte)4, (byte)0, (byte)0, GFXD_14_ORDINAL, GFE_75);

  private static final byte GFXD_20_ORDINAL = 31;

  public static final Version GFXD_20 = new Version("GFXD", "2.0",
      (byte)2, (byte)0, (byte)0, (byte)0, GFXD_20_ORDINAL, GFE_75);

  private static final byte GFE_80_ORDINAL = GFXD_14_ORDINAL;
  public static final Version GFE_80 = new Version("GFE", "8.0", (byte)8,
      (byte)0, (byte)0, (byte)0, GFE_80_ORDINAL, false /* overwrite */);

  private static final byte GFXD_155_ORDINAL = 32;

  public static final Version GFXD_155 = new Version("GFXD", "1.5.5",
      (byte)1, (byte)5, (byte)5, (byte)0, GFXD_155_ORDINAL, GFE_80);

  private static final byte STORE_162_ORDINAL = 33;

  public static final Version STORE_162 = new Version("STORE", "1.6.2",
      (byte)1, (byte)6, (byte)2, (byte)0, STORE_162_ORDINAL, GFE_80);


  /**
   * This constant must be set to the most current version of GFE/GFXD/STORE.
   */
  public static final Version CURRENT = STORE_162;
  public static final Version CURRENT_GFE = CURRENT.getGemFireVersion();

  /**
   * A lot of versioning code needs access to the current version's ordinal
   */
  public static final short CURRENT_ORDINAL = CURRENT.ordinal();
  public static final short CURRENT_GFE_ORDINAL = CURRENT_GFE.ordinal();

  public static final short NOT_SUPPORTED_ORDINAL = 59;

  /**
   * version ordinal for test Backward compatibility.
   */
  private static final byte validOrdinalForTesting = 2;

  public static final Version TEST_VERSION = new Version("TEST", "VERSION",
      (byte)0, (byte)0, (byte)0, (byte)0, validOrdinalForTesting);

  static {
    // set JGroupsVersion by reflection
    try {
      Class<?> c = Class.forName("com.gemstone.org.jgroups.JGroupsVersion");
      Field f = c.getField("CURRENT_ORDINAL");
      f.setAccessible(true);
      f.setShort(null, CURRENT_ORDINAL);
    } catch (ClassNotFoundException ignore) {
      // this is expected for clients so ignore
    } catch (Exception e) {
      ClientSharedUtils.getLogger(Version.class).warn(
          "Failed to set JGroupsVersion.CURRENT_ORDINAL: " + e);
    }

    Method m;
    try {
      Class<?> cls = Class
          .forName("com.gemstone.gemfire.internal.cache.tier.sockets.CommandInitializer");
      m = cls.getMethod("getCommands", Version.class);
    } catch (Exception e) {
      m = null;
    }
    getGFEClientCommands = m;
  }

  /** Creates a new instance of <code>Version</code> */
  private Version(String product, String name, byte major, byte minor,
      byte release, byte patch, byte ordinal) {
    this(product, name, major, minor, release, patch, ordinal, true /* overwrite*/);
  }
  /** Creates a new instance of <code>Version</code> */
  private Version(String product, String name, byte major, byte minor,
      byte release, byte patch, byte ordinal, boolean overwrite) {
    this.productName = product;
    this.name = name;
    this.majorVersion = major;
    this.minorVersion = minor;
    this.release = release;
    this.patch = patch;
    this.ordinal = ordinal;
    this.methodSuffix = this.productName + "_" + this.majorVersion + "_"
        + this.minorVersion + "_" + this.release + "_" + this.patch;
    this.gemfireVersion = null;
    if (overwrite && ordinal != TOKEN_ORDINAL) {
      VALUES[this.ordinal] = this;
    }
  }

  /**
   * Creates a new instance of <code>Version</code> with a different underlying
   * GemFire version
   */
  private Version(String product, String name, byte major, byte minor,
      byte release, byte patch, byte ordinal, Version gemfireVersion) {
    this(product, name, major, minor, release, patch, ordinal);
    this.gemfireVersion = gemfireVersion;
  }

  /** Return the <code>Version</code> represented by specified ordinal */
  public static Version fromOrdinal(short ordinal, boolean forGFEClients)
      throws UnsupportedGFXDVersionException {
    // Un-version client(client's prior to release 5.7) doesn't send version
    // byte in the handshake. So the next byte in the handshake has value 59 and
    // is interpreted as version byte. We are not supporting version 59 to
    // distinguish version client from unversion. Please use version ordinal 60
    // after 58 if required.
    if (ordinal == NOT_SUPPORTED_ORDINAL) {
      throw new UnsupportedGFXDVersionException(
          "Un-versioned clients are not supported. ");
    }
    if (ordinal == TOKEN_ORDINAL) {
      return TOKEN;
    }
    // for GFE clients also check that there must be a commands object mapping
    // for processing (e.g. GFXD product versions will not work)
    final Version version;
    if ((VALUES.length < ordinal + 1) || (version = VALUES[ordinal]) == null
        || (forGFEClients && getClientCommands(version) == null)) {
      throw new UnsupportedGFXDVersionException(MessageFormat.format(
          Version_REMOTE_VERSION_NOT_SUPPORTED, ordinal, CURRENT.name));
    }
    return version;
  }

  private static Map<?, ?> getClientCommands(Version version) {
    if (getGFEClientCommands == null) {
      return null;
    }
    try {
      return (Map<?, ?>)getGFEClientCommands.invoke(null, version);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * return the version corresponding to the given ordinal, or CURRENT if the
   * ordinal isn't valid
   * 
   * @param ordinal
   * @return the corresponding ordinal
   */
  public static Version fromOrdinalOrCurrent(short ordinal) {
    if (ordinal == TOKEN_ORDINAL) {
      return TOKEN;
    }
    final Version version;
    if ((VALUES.length < ordinal + 1) || (version = VALUES[ordinal]) == null) {
      return CURRENT;
    }
    return version;
  }

  /**
   * Write the given ordinal (result of {@link #ordinal()}) to given
   * {@link DataOutput}. This keeps the serialization of ordinal compatible with
   * previous versions writing a single byte to DataOutput when possible, and a
   * token with 2 bytes if it is large.
   * 
   * @param out
   *          the {@link DataOutput} to write the ordinal write to
   * @param ordinal
   *          the version to be written
   * @param compressed
   *          if true, then use single byte for ordinal < 128, and three bytes
   *          for beyond that, else always use three bytes where the first byte
   *          is {@link #TOKEN_ORDINAL}; former mode is useful for
   *          interoperatibility with previous versions while latter to use
   *          fixed size for writing version; typically former will be used for
   *          P2P/client-server communications while latter for persisting to
   *          disk; we use the token to ensure that
   *          {@link #readOrdinal(DataInput)} can deal with both
   *          compressed/uncompressed cases seemlessly
   */
  public static void writeOrdinal(DataOutput out, short ordinal,
      boolean compressed) throws IOException {
    if (compressed && ordinal <= Byte.MAX_VALUE) {
      out.writeByte(ordinal);
    }
    else {
      out.writeByte(TOKEN_ORDINAL);
      out.writeShort(ordinal);
    }
  }

  /**
   * Write this {@link Version}'s ordinal (result of {@link #ordinal()}) to
   * given {@link DataOutput}. This keeps the serialization of ordinal
   * compatible with previous versions writing a single byte to DataOutput when
   * possible, and a token with 2 bytes if it is large.
   * 
   * @param out
   *          the {@link DataOutput} to write the ordinal write to
   * @param compressed
   *          if true, then use single byte for ordinal < 128, and three bytes
   *          for beyond that, else always use three bytes where the first byte
   *          is {@link #TOKEN_ORDINAL}; former mode is useful for
   *          interoperatibility with previous versions while latter to use
   *          fixed size for writing version; typically former will be used for
   *          P2P/client-server communications while latter for persisting to
   *          disk; we use the token to ensure that
   *          {@link #readOrdinal(DataInput)} can deal with both
   *          compressed/uncompressed cases seemlessly
   */
  public final void writeOrdinal(DataOutput out, boolean compressed)
      throws IOException {
    writeOrdinal(out, this.ordinal, compressed);
  }

  /**
   * Fixed number of bytes required for serializing this version when
   * "compressed" flag is false in {@link #writeOrdinal(DataOutput, boolean)}.
   */
  public static final int uncompressedSize() {
    return 3;
  }

  /**
   * Fixed number of bytes required for serializing this version when
   * "compressed" flag is true in {@link #writeOrdinal(DataOutput, boolean)}.
   */
  public final int compressedSize() {
    if (ordinal <= Byte.MAX_VALUE) {
      return 1;
    }
    else {
      return 3;
    }
  }

  /**
   * Write the given ordinal (result of {@link #ordinal()}) to given
   * {@link ByteBuffer}. This keeps the serialization of ordinal compatible with
   * previous versions writing a single byte to DataOutput when possible, and a
   * token with 2 bytes if it is large.
   * 
   * @param buffer
   *          the {@link ByteBuffer} to write the ordinal write to
   * @param ordinal
   *          the version to be written
   * @param compressed
   *          if true, then use single byte for ordinal < 128, and three bytes
   *          for beyond that, else always use three bytes where the first byte
   *          is {@link #TOKEN_ORDINAL}
   */
  public static void writeOrdinal(ByteBuffer buffer, short ordinal,
      boolean compressed) throws IOException {
    if (compressed && ordinal <= Byte.MAX_VALUE) {
      buffer.put((byte)ordinal);
    }
    else {
      buffer.put(TOKEN_ORDINAL);
      buffer.putShort(ordinal);
    }
  }

  /**
   * Reads ordinal as written by {@link #writeOrdinal} from given
   * {@link DataInput}.
   */
  public static short readOrdinal(DataInput in) throws IOException {
    final byte ordinal = in.readByte();
    if (ordinal != TOKEN_ORDINAL) {
      return ordinal;
    }
    else {
      return in.readShort();
    }
  }

  /**
   * Return the <code>Version</code> reading from given {@link DataInput} as
   * serialized by {@link #writeOrdinal(DataOutput, boolean)}.
   * 
   * If the incoming ordinal is greater than or equal to current ordinal then
   * this will return null or {@link #CURRENT} indicating that version is same
   * as that of {@link #CURRENT} assuming that peer will support this JVM.
   * 
   * This method is not meant to be used for client-server protocol since
   * servers cannot support higher version clients, rather is only meant for
   * P2P/JGroups messaging where a mixed version of servers can be running at
   * the same time. Similarly cannot be used when recovering from disk since
   * higher version data cannot be read.
   * 
   * @param in
   *          the {@link DataInput} to read the version from
   * @param returnNullForCurrent
   *          if true then return null if incoming version >= {@link #CURRENT}
   *          else return {@link #CURRENT}
   */
  public static Version readVersion(DataInput in, boolean returnNullForCurrent)
      throws UnsupportedGFXDVersionException, IOException {
    return fromOrdinalCheck(readOrdinal(in), returnNullForCurrent);
  }

  /**
   * Return the <code>Version</code> represented by specified ordinal while not
   * throwing exception if given ordinal is higher than any known ones.
   */
  public static Version fromOrdinalCheck(short ordinal,
      boolean returnNullForCurrent) throws UnsupportedGFXDVersionException {
    if (ordinal >= CURRENT.ordinal) {
      return returnNullForCurrent ? null : CURRENT;
    }
    else {
    }
    return fromOrdinal(ordinal, false);
  }

  /**
   * Reads ordinal as written by {@link #writeOrdinal} from given
   * {@link InputStream}. Returns -1 on end of stream.
   */
  public static short readOrdinalFromInputStream(InputStream is)
      throws IOException {
    final int ordinal = is.read();
    if (ordinal != -1) {
      if (ordinal != TOKEN_ORDINAL_INT) {
        return (short)ordinal;
      }
      else {
        // two byte ordinal
        final int ordinalPart1 = is.read();
        final int ordinalPart2 = is.read();
        if ((ordinalPart1 | ordinalPart2) >= 0) {
          return (short)((ordinalPart1 << 8) | ordinalPart2);
        }
        else {
          return -1;
        }
      }
    }
    else {
      return -1;
    }
  }

  public Version getGemFireVersion() {
    return this.gemfireVersion != null ? this.gemfireVersion : this;
  }

  public final String getMethodSuffix() {
    return this.methodSuffix;
  }

  public final String getProductName() {
    return this.productName;
  }

  public final String getName() {
    return this.name;
  }

  public final short getMajorVersion() {
    return this.majorVersion;
  }

  public final short getMinorVersion() {
    return this.minorVersion;
  }

  public final short getRelease() {
    return this.release;
  }

  public final short getPatch() {
    return this.patch;
  }

  public final short ordinal() {
    return this.ordinal;
  }

  /**
   * Returns whether this <code>Version</code> is compatible with the input
   * <code>Version</code>
   * 
   * @param version
   *          The <code>Version</code> to compare
   * @return whether this <code>Version</code> is compatible with the input
   *         <code>Version</code>
   */
  public boolean compatibleWith(Version version) {
    return true;
  }

  /**
   * Finds the Version instance corresponding to the given ordinal and returns
   * the result of compareTo(Version)
   *
   * @param other the ordinal of the other Version object
   * @return negative if this version is older, positive if this version
   * is newer, 0 if this is the same version
   */
  public final int compareTo(short other) {
    // first try to find the actual Version object
    try {
      Version v = fromOrdinalCheck(other, false);
      // short min/max can't overflow int, so use (a-b)
      return (this.ordinal - v.ordinal);
    } catch (UnsupportedGFXDVersionException ugve) {
      // failing that we use the old method of comparing Versions
      return this.ordinal - other;
    }
  }

  /**
   * {@inheritDoc}
   */
  public final int compareTo(Version o) {
    if (o != null) {
      // short min/max can't overflow int, so use (a-b)
      return (this.ordinal - o.ordinal);
    } else {
      return 1;
    }
  }

  /**
   * Returns a string representation for this <code>Version</code>.
   * 
   * @return the name of this operation.
   */
  @Override
  public String toString() {
    if (this.gemfireVersion == null) {
      return this.productName + ' ' + this.name + '(' + this.ordinal + ')';
    }
    else {
      return this.productName + ' ' + this.name + '(' + this.ordinal + ")["
          + this.gemfireVersion.toString() + ']';
    }
  }

  public static String toString(short ordinal) {
    if (ordinal <= CURRENT.ordinal) {
      try {
        return fromOrdinal(ordinal, false).toString();
      } catch (UnsupportedGFXDVersionException uve) {
        // ignored in toString()
      }
    }
    return "UNKNOWN[ordinal=" + ordinal + ']';
  }

  public byte[] toBytes() {
    byte[] bytes = new byte[2];
    bytes[0] = (byte)(ordinal >> 8);
    bytes[1] = (byte)ordinal;
    return bytes;
  }

  public static Version fromBytes(byte[] bytes)
      throws UnsupportedGFXDVersionException {
    if (bytes.length != 2) {
      throw new IllegalArgumentException(
          "Illegal length for bytes, should be 2: " + bytes.length);
    }
    short ordinal = (short)((bytes[0] << 8) + (bytes[1] & 0xff));
    return fromOrdinal(ordinal, false);
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (other != null && other.getClass() == Version.class) {
      return this.ordinal == ((Version)other).ordinal;
    }
    else {
      return false;
    }
  }

  public boolean equals(Version other) {
    if (other != null) {
      return this.ordinal == other.ordinal;
    }
    else {
      return this.ordinal == CURRENT.ordinal;
    }
  }

  @Override
  public int hashCode() {
    int result = 17;
    final int mult = 37;
    result = mult * result + this.ordinal;
    return result;
  }
}
