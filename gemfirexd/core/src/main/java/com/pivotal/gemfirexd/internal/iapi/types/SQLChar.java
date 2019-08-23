/*

   Derby - Class com.pivotal.gemfirexd.internal.iapi.types.SQLChar

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.iapi.types;

// GemStone changes BEGIN
import com.gemstone.gemfire.internal.DSCODE;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.offheap.ByteSource;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.distributed.ByteArrayDataOutput;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
// GemStone changes END
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.cache.ClassSize;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.LocaleFinder;
import com.pivotal.gemfirexd.internal.iapi.services.io.ArrayInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.DerbyIOException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatIdInputStream;
import com.pivotal.gemfirexd.internal.iapi.services.io.Storable;
import com.pivotal.gemfirexd.internal.iapi.services.io.StreamStorable;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.shared.common.ResolverUtils;
import com.pivotal.gemfirexd.internal.shared.common.StoredFormatIds;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.InputStream;
import java.io.ObjectOutput;
import java.io.ObjectInput;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.io.EOFException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.text.RuleBasedCollator;
import java.text.CollationKey;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Calendar;

/**

The SQLChar represents a CHAR value with UCS_BASIC collation.
SQLChar may be used directly by any code when it is guaranteed
that the required collation is UCS_BASIC, e.g. system columns.
<p>
The state may be in char[], a String, or an unread stream, depending
on how the datatype was created.  
<p>
Stream notes:
<p>
When the datatype comes from the database layer and the length of the bytes
necessary to store the datatype on disk exceeds the size of a page of the
container holding the data then the store returns a stream rather than reading
all the bytes into a char[] or String.  The hope is that the usual usage case
is that data never need be expanded in the derby layer, and that client can
just be given a stream that can be read a char at a time through the jdbc
layer.  Even though SQLchar's can't ever be this big, this code is shared
by all the various character datatypes including SQLClob which is expected
to usually larger than a page.
<p>
The state can also be a stream in the case of insert/update where the client
has used a jdbc interface to set the value as a stream rather than char[].  
In this case the hope is that the usual usage case is that stream never need
be read until it is passed to store, read once, and inserted into the database.

**/

public class SQLChar
    extends DataType implements StringDataValue, StreamStorable
{
    /**************************************************************************
     * static fields of the class
     **************************************************************************
     */

    /**
     * threshold, that decides when we return space back to the VM
     * see getString() where it is used
     */
    protected final static int RETURN_SPACE_THRESHOLD = 4096;

    /**
     * when we know that the array needs to grow by at least
     * one byte, it is not performant to grow by just one byte
     * instead this amount is used to provide a reasonable growby size.
     */
    private final static int GROWBY_FOR_CHAR = 64;


    private static final int BASE_MEMORY_USAGE = 
        ClassSize.estimateBaseFromCatalog( SQLChar.class);

    /**
        Static array that can be used for blank padding.
    */
    private static final char[] BLANKS = new char[40];
    private static final byte[] BLANKSB = new byte[40];
    static {
      for (int i = 0; i < BLANKS.length; i++) {
        BLANKS[i] = ' ';
      }
      for (int i = 0; i < BLANKSB.length; i++) {
        BLANKSB[i] = (byte)' ';
      }
    }

    /**************************************************************************
     * Fields of the class
     **************************************************************************
     */

    /*
     * object state
     */

    // Don't use value directly in most situations. Use getString()
    // OR use the rawData array if rawLength != -1.
    protected     String  value;

    // rawData holds the reusable array for reading in SQLChars. It contains a
    // valid value if rawLength is greater than or equal to 0. See getString() 
    // to see how it is converted to a String. Even when converted to a String
    // object the rawData array remains for potential future use, unless 
    // rawLength is > 4096. In this case the rawData is set to null to avoid
    // huge memory use.
    protected     char[]  rawData;
    protected     int     rawLength = -1;

    // For null strings, cKey = null.
    private CollationKey cKey; 

// GemStone changes BEGIN
    // now we always store the stream upfront else it tries to re-read
    // in HA retries for example
    // TODO: optimize this by reading it once on first access and storing
    // somewhere in temp area, for example
    /* (original code)
    /**
     * The value as a stream in the on-disk format.
     *
    InputStream stream;

    /* Locale info (for International support) *
    // we get locale directly from static FabricDatabase
    private LocaleFinder localeFinder;
    */
// GemStone changes END


    /**************************************************************************
     * Constructors for This class:
     **************************************************************************
     */

    /**
     * no-arg constructor, required by Formattable.
     **/
    public SQLChar()
    {
    }

    public SQLChar(String val)
    {
        value = val;
    }

    /**************************************************************************
     * Private/Protected methods of This class:
     **************************************************************************
     */

    /**************************************************************************
     * Public Methods of This class:
     **************************************************************************
     */
// GemStone changes BEGIN

    /**
     * Copy the characters to given array assuming it is large enough.
     */
    public final int getCharArray(char[] chars, int offset)
        throws StandardException {
      if (this.rawLength != -1) {
        System.arraycopy(this.rawData, 0, chars, offset, this.rawLength);
        return this.rawLength;
      }
      if (this.value != null) {
        final int len = this.value.length();
        this.value.getChars(0, len, chars, offset);
        return len;
      }
      /*
      try {
        if (readFromStream()) {
          System.arraycopy(this.rawData, 0, chars, offset, this.rawLength);
          return this.rawLength;
        }
      } catch (IOException ioe) {
        throwStreamingIOException(ioe);
      }
      */
      return 0;
    }

    public static void appendBlanks(char[] ca, int offset, int howMany) {
      while (howMany > 0) {
        int count = howMany > BLANKS.length ? BLANKS.length : howMany;
        System.arraycopy(BLANKS, 0, ca, offset, count);
        howMany -= count;
        offset += count;
      }
    }

    public static void appendBlanks(ByteArrayDataOutput buffer, int howMany) {
      while (howMany > 0) {
        int count = howMany > BLANKSB.length ? BLANKSB.length : howMany;
        buffer.write(BLANKSB, 0, count);
        howMany -= count;
      }
    }
// GemStone changes END

    /**************************************************************************
     * Public Methods of DataValueDescriptor interface:
     *     Mostly implemented in Datatype.
     **************************************************************************
     */

    /**
     * Get Boolean from a SQLChar.
     *
     * <p>
     * Return false for only "0" or "false" for false. No case insensitivity. 
     * Everything else is true.
     * <p>
     * The above matches JCC.
     *
     *
     * @see DataValueDescriptor#getBoolean
     *
     * @exception StandardException     Thrown on error
     **/
    public boolean getBoolean()
        throws StandardException
    {
        if (isNull()) 
            return false;

        // match JCC, match only "0" or "false" for false. No case 
        // insensitivity. everything else is true.

        String cleanedValue = getString().trim();

        return !(cleanedValue.equals("0") || cleanedValue.equals("false"));
    }

    /**
     * Get Byte from a SQLChar.
     *
     * <p>
     * Uses java standard Byte.parseByte() to perform coercion.
     *
     * @see DataValueDescriptor#getByte
     *
     * @exception StandardException thrown on failure to convert
     **/
    public byte getByte() throws StandardException
    {
        if (isNull()) 
            return (byte)0;

        try 
        {
            return Byte.parseByte(getString().trim());
        } 
        catch (NumberFormatException nfe) 
        {
            throw StandardException.newException(
                    SQLState.LANG_FORMAT_EXCEPTION, "byte", (String)null);
        }
    }

    /**
     * Get Short from a SQLChar.
     *
     * <p>
     * Uses java standard Short.parseShort() to perform coercion.
     *
     * @see DataValueDescriptor#getShort
     *
     * @exception StandardException thrown on failure to convert
     **/
    public short getShort() throws StandardException
    {
        if (isNull()) 
            return (short)0;

        try 
        {
            return Short.parseShort(getString().trim());

        } 
        catch (NumberFormatException nfe) 
        {
            throw StandardException.newException(
                    SQLState.LANG_FORMAT_EXCEPTION, "short", (String)null);
        }
    }

    /**
     * Get int from a SQLChar.
     *
     * <p>
     * Uses java standard Short.parseInt() to perform coercion.
     *
     * @see DataValueDescriptor#getInt
     *
     * @exception StandardException thrown on failure to convert
     **/
    public int  getInt() throws StandardException
    {
        if (isNull()) 
            return 0;

        try 
        {
            return Integer.parseInt(getString().trim());
        } 
        catch (NumberFormatException nfe) 
        {
          // GemStone changes BEGIN
          throw StandardException.newException(
              SQLState.LANG_FORMAT_EXCEPTION, "int (" + nfe.getMessage() + ")", (String)null);
            /*(original code) throw StandardException.newException(
                    SQLState.LANG_FORMAT_EXCEPTION, "int");*/
          // GemStone changes END
        }
    }

    /**
     * Get long from a SQLChar.
     *
     * <p>
     * Uses java standard Short.parseLong() to perform coercion.
     *
     * @see DataValueDescriptor#getLong
     *
     * @exception StandardException thrown on failure to convert
     **/
    public long getLong() throws StandardException
    {
        if (isNull()) 
            return 0;

        try 
        {
            return Long.parseLong(getString().trim());

        } 
        catch (NumberFormatException nfe) 
        {
            throw StandardException.newException(
                    SQLState.LANG_FORMAT_EXCEPTION, "long", (String)null);
        }
    }

    /**
     * Get float from a SQLChar.
     *
     * <p>
     * Uses java standard Float.floatValue() to perform coercion.
     *
     * @see DataValueDescriptor#getFloat
     *
     * @exception StandardException thrown on failure to convert
     **/
    public float getFloat() throws StandardException
    {
        if (isNull()) 
            return 0;

        try 
        {
            return Float.parseFloat(getString().trim());
        } 
        catch (NumberFormatException nfe) 
        {
            throw StandardException.newException(
                    SQLState.LANG_FORMAT_EXCEPTION, "float", (String)null);
        }
    }

    /**
     * Get double from a SQLChar.
     *
     * <p>
     * Uses java standard Double.doubleValue() to perform coercion.
     *
     * @see DataValueDescriptor#getDouble
     *
     * @exception StandardException thrown on failure to convert
     **/
    public double getDouble() throws StandardException
    {
        if (isNull()) 
            return 0;

        try 
        {
            return Double.parseDouble(getString().trim());
        } 
        catch (NumberFormatException nfe) 
        {
            throw StandardException.newException(
                SQLState.LANG_FORMAT_EXCEPTION, "double", (String)null);
        }
    }

    /**
     * Get date from a SQLChar.
     *
     * @see DataValueDescriptor#getDate
     *
     * @exception StandardException thrown on failure to convert
     **/
    public Date getDate(Calendar cal) 
        throws StandardException
    {
        return getDate(cal, getString(), getLocaleFinder());
    }

    /**
     * Static function to Get date from a string.
     *
     * @see DataValueDescriptor#getDate
     *
     * @exception StandardException thrown on failure to convert
     **/
    public static Date getDate(
    java.util.Calendar  cal, 
    String              str, 
    LocaleFinder        localeFinder) 
        throws StandardException
    {
        if( str == null)
            return null;

        SQLDate internalDate = new SQLDate(str, false, localeFinder);

        return internalDate.getDate(cal);
    }

    /**
     * Get time from a SQLChar.
     *
     * @see DataValueDescriptor#getTime
     *
     * @exception StandardException thrown on failure to convert
     **/
    public Time getTime(Calendar cal) throws StandardException
    {
        return getTime( cal, getString(), getLocaleFinder());
    }

    /**
     * Static function to Get Time from a string.
     *
     * @see DataValueDescriptor#getTime
     *
     * @exception StandardException thrown on failure to convert
     **/
    public static Time getTime(
    Calendar        cal, 
    String          str, 
    LocaleFinder    localeFinder) 
        throws StandardException
    {
        if( str == null)
            return null;
        SQLTime internalTime = new SQLTime( str, false, localeFinder, cal);
        return internalTime.getTime( cal);
    }

    /**
     * Get Timestamp from a SQLChar.
     *
     * @see DataValueDescriptor#getTimestamp
     *
     * @exception StandardException thrown on failure to convert
     **/
    public Timestamp getTimestamp( Calendar cal) throws StandardException
    {
        return getTimestamp( cal, getString(), getLocaleFinder());
    }

    /**
     * Static function to Get Timestamp from a string.
     *
     * @see DataValueDescriptor#getTimestamp
     *
     * @exception StandardException thrown on failure to convert
     **/
    public static Timestamp getTimestamp(
    java.util.Calendar  cal, 
    String              str, 
    LocaleFinder        localeFinder)
        throws StandardException
    {
        if( str == null)
            return null;

        SQLTimestamp internalTimestamp = 
            new SQLTimestamp( str, false, localeFinder, cal);

        return internalTimestamp.getTimestamp( cal);
    }

    /**************************************************************************
     * Public Methods of StreamStorable interface:
     **************************************************************************
     */
    public InputStream returnStream()
    {
// GemStone changes BEGIN
        return null;
        /* (original code)
        return stream;
        */
// GemStone changes END
    }

    /**
     * Set this value to the on-disk format stream.
     */
    public final void setStream(InputStream newStream) throws StandardException
    {
// GemStone changes BEGIN
        try {
          if (!readFromStream(newStream)) {
            this.value = null;
            this.rawLength = -1;
            this.rawData = null;
            this.cKey = null;
          }
        } catch (IOException ioe) {
          throwStreamingIOException(ioe);
        }
        /* (original code)
        this.value = null;
        this.rawLength = -1;
        this.stream = newStream;
        cKey = null;
        */
// GemStone changes END
    }

    public void loadStream() throws StandardException
    {
        getString();
    }


    /**
     * @exception StandardException     Thrown on error
     */
    public Object   getObject() throws StandardException
    {
        return getString();
    }

    /**
     * @exception StandardException     Thrown on error
     */
    public InputStream  getStream() throws StandardException
    {
// GemStone changes BEGIN
        return null;
        /* (original code)
        return stream;
        */
// GemStone changes END
    }
    /**
     * CHAR/VARCHAR/LONG VARCHAR implementation. 
     * Convert to a BigDecimal using getString.
     */
    public int typeToBigDecimal()  throws StandardException
    {
        return java.sql.Types.CHAR;
    }

    /**
     * @exception StandardException     Thrown on error
     */
    public int getLength() throws StandardException {
        if (rawLength != -1)
            return rawLength;
// GemStone changes BEGIN
        /* (original code)
        if (stream != null) {
          if (stream instanceof Resetable && stream instanceof ObjectInput) {
            try {
              int clobLength = 0;
              // If we have the stream length encoded.
              // just read that.
              int utf8len = readCharacterLength((ObjectInput)stream);
              if (utf8len != 0) {
                clobLength = utf8len;
                return clobLength;
              }
              // Otherwise we will have to read the whole stream.
              int skippedCharSize = (int)UTF8Util.skipUntilEOF(stream);
              clobLength = skippedCharSize;
              return clobLength;
            } catch (IOException ioe) {
              throwStreamingIOException(ioe);
            } finally {
              try {
                ((Resetable)stream).resetStream();
              } catch (IOException ioe) {
                throwStreamingIOException(ioe);
              }
            }
          }
        }
        */
// GemStone changes END
        String tmpString = getString();
        if (tmpString == null) {
            return 0;
        } else {
            int clobLength = tmpString.length();
            return clobLength;
        }
    }

    /*
    private int readCharacterLength(ObjectInput in) throws IOException {
         int utflen = in.readUnsignedShort();
        return utflen;
    }
    */

    private void throwStreamingIOException(IOException ioe) throws StandardException {
// GemStone changes BEGIN
      DerbyIOException dioe;
      if (ioe instanceof DerbyIOException
          && (dioe = (DerbyIOException)ioe).getSQLState() != null) {
        throw StandardException.newPreLocalizedException(dioe.getSQLState(),
            dioe, dioe.getLocalizedMessage());
      }
// GemStone changes END
		throw StandardException.
			newException(SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION,
						 ioe, getTypeName());
	}

    public String getTypeName()
    {
        return TypeId.CHAR_NAME;
    }

    /**
     * If possible, use getCharArray() if you don't really
     * need a string.  getString() will cause an extra 
     * char array to be allocated when it calls the the String() 
     * constructor (the first time through), so may be
     * cheaper to use getCharArray().
     *
     * @exception StandardException     Thrown on error
     */
    public String getString() throws StandardException
    {
// GemStone changes BEGIN
        if (this.value != null) {
          return this.value;
        }
        if (this.rawLength != -1) {
          if (this.rawData != null) {
            this.value = ClientSharedUtils.newWrappedString(
                this.rawData, 0, this.rawLength);
            return this.value;
          }
          SanityManager.THROWASSERT("rawData null with rawLength="
              + this.rawLength);
        }
        /*
        try {
          if (readFromStream()) {
            this.value = ClientSharedUtils.newWrappedString(
                this.rawData, 0, this.rawLength);
            return this.value;
          }
        } catch (IOException ioe) {
          throwStreamingIOException(ioe);
        }
        */
        return null;
        /* (original code)
        if (value == null) {

            int len = rawLength;

            if (len != -1) {

                // data is stored in the char[] array

                value = new String(rawData, 0, len);
                if (len > RETURN_SPACE_THRESHOLD) {
                    // free up this char[] array to reduce memory usage
                    rawData = null;
                    rawLength = -1;
                    cKey = null;
                }

            } else if (stream != null) {

                // data stored as a stream
                try {

                    if (stream instanceof FormatIdInputStream) {
                        readExternal((FormatIdInputStream) stream);
                    } else {
                        readExternal(new FormatIdInputStream(stream));
                    }
                    stream = null;

                    // at this point the value is only in the char[]
                    // so call again to convert to a String
                    return getString();

                } catch (IOException ioe) {

                    throw StandardException.newException(
                            SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION, 
                            ioe, 
                            "java.sql.String");
                }
            }
        }

        return value;
        */
// GemStone changes END
    }

    /**
     * Get a char array.  Typically, this is a simple
     * getter that is cheaper than getString() because
     * we always need to create a char array when
     * doing I/O.  Use this instead of getString() where
     * reasonable.
     * <p>
     * <b>WARNING</b>: may return a character array that has spare
     * characters at the end.  MUST be used in conjunction
     * with getLength() to be safe.
     * 
     * @exception StandardException     Thrown on error
     */
    public char[] getCharArray() throws StandardException
    {
// GemStone changes BEGIN
      return getCharArray(false);
    }

    public final char[] getCharArray(boolean noCopy) throws StandardException {
        if (this.rawLength != -1) {
          return this.rawData;
        }
        final String v = this.value;
        if (v != null) {
          final int vlen = v.length();
          if (noCopy) {
            final char[] internalChars = ResolverUtils
                .getInternalCharsOnly(v, vlen);
            if (internalChars != null) {
              return internalChars;
            }
          }
          this.rawData = ResolverUtils.getChars(v, vlen);
          this.rawLength = vlen;
          this.value = null;
          return this.rawData;
        }
        /*
        try {
          if (readFromStream()) {
            return this.rawData;
          }
        } catch (IOException ioe) {
          throwStreamingIOException(ioe);
        }
        */
        return null;
        /* (original code)
        if (isNull())
        {
            return (char[])null;
        }
        else if (rawLength != -1)
        {
            return rawData;
        }
        else
        {
            // this is expensive -- we are getting a
            // copy of the char array that the 
            // String wrapper uses.
            getString();
            rawData = value.toCharArray();
            rawLength = rawData.length;
            cKey = null;
            return rawData;
        }
        */
// GemStone changes END
    }


    /*
     * Storable interface, implies Externalizable, TypedFormat
     */

    /**
        Return my format identifier.

        @see com.pivotal.gemfirexd.internal.iapi.services.io.TypedFormat#getTypeFormatId
    */
    public int getTypeFormatId() {
        return StoredFormatIds.SQL_CHAR_ID;
    }

    /**
     * see if the String value is null.
     @see Storable#isNull
    */
    public boolean isNull()
    {
        return ((value == null) && (rawLength == -1)/* && (stream == null) */);
    }

    /**
        The maximum stored size is based upon the UTF format
        used to stored the String. The format consists of
        a two byte length field and a maximum number of three
        bytes for each character.
        <BR>
        This puts an upper limit on the length of a stored
        String. The maximum stored length is 65535, these leads to
        the worse case of a maximum string length of 21844 ((65535 - 2) / 3).
        <BR>
        Strings with stored length longer than 64K is handled with
        the following format:
        (1) 2 byte length: will be assigned 0.
        (2) UTF formated string data.
        (3) terminate the string with the following 3 bytes:
            first byte is:
            +---+---+---+---+---+---+---+---+
            | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 0 |
            +---+---+---+---+---+---+---+---+
            second byte is:
            +---+---+---+---+---+---+---+---+
            | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
            +---+---+---+---+---+---+---+---+
            third byte is:
            +---+---+---+---+---+---+---+---+
            | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
            +---+---+---+---+---+---+---+---+


        The UTF format:
        Writes a string to the underlying output stream using UTF-8 
        encoding in a machine-independent manner. 
        <p>
        First, two bytes are written to the output stream as if by the 
        <code>writeShort</code> method giving the number of bytes to 
        follow. This value is the number of bytes actually written out, 
        not the length of the string. Following the length, each character 
        of the string is output, in sequence, using the UTF-8 encoding 
        for the character. 
        @exception  IOException  if an I/O error occurs.
        @since      JDK1.0


      @exception IOException thrown by writeUTF

      @see java.io.DataInputStream

    */
    public void writeExternal(ObjectOutput out) throws IOException
    {
      // GemStone changes BEGIN
      // support externalized null value
      boolean isNull = isNull();
      out.writeBoolean(isNull);
      if (isNull) {
        return;
      }
      writeData(out, true);
      // GemStone changes END
    }

    void writeData(DataOutput out, boolean createArray)
        throws IOException {
        // never called when value is null
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(!isNull());
        }

        char[] data;
        int strlen = this.rawLength;
        if (strlen < 0) {
          final String v = this.value;
          if (v != null) {
            strlen = v.length();
            data = ResolverUtils.getInternalCharsOnly(v, strlen);
            if (data == null) {
              // it is likely that we will invoke this multiple times, so
              // extract char[] from within String and let the String be GCed
              this.rawData = data = ResolverUtils.getChars(v, strlen);
              this.rawLength = strlen;
              this.value = null;
            }
          }
          else {
            //readFromStream();
            data = this.rawData;
            strlen = this.rawLength;
          }
        }
        else {
          data = this.rawData;
        }

        // byte length will always be at least string length
        int utflen = strlen;

        for (int i = 0 ; (i < strlen) && (utflen <= 65535); i++)
        {
            final int c = data[i];
            if ((c >= 0x0001) && (c <= 0x007F))
            {
                // 1 byte for character
            }
            else if (c > 0x07FF)
            {
                utflen += 2; // 3 bytes for character
            }
            else
            {
                utflen += 1; // 2 bytes for character
            }
        }

        boolean isLongUTF = false;
        int byteArrayLen = utflen;
        // for length than 64K, see format description above
        if (utflen > 65535)
        {
            isLongUTF = true;
            utflen = 0;
            // 3 bytes to terminate the string
            byteArrayLen = byteArrayLen + 3;
        }

        int index = 0;
        byte[] bytes = null;
        if (createArray) {
          // matching writeShort & readUnsignedShort while writing byte length.
          bytes = new byte[byteArrayLen];
          out.writeShort(utflen);
          /*original code
           * bytes = new byte[utflen + 2];
           * bytes[index++] = (byte)((utflen >>> 8) & 0xFF);
           * bytes[index++] = (byte)((utflen >>> 0) & 0xFF);
           */
        }
        else {
          out.writeShort(utflen);
          /*original code
          out.writeByte((byte)((utflen >>> 8) & 0xFF));
          out.writeByte((byte)((utflen >>> 0) & 0xFF));
          */
        }
        for (int i = 0 ; i < strlen ; i++)
        {
            final int c = data[i];
            if ((c >= 0x0001) && (c <= 0x007F))
            {
              if(createArray) {
                bytes[index++] = (byte)(c & 0xFF);
              }else {
                out.writeByte((byte)(c & 0xFF));
              }
            }
            else if (c > 0x07FF)
            {
              if (createArray) {
                bytes[index++] = (byte)(0xE0 | ((c >> 12) & 0x0F));
                bytes[index++] = (byte)(0x80 | ((c >>  6) & 0x3F));
                bytes[index++] = (byte)(0x80 | ((c >>  0) & 0x3F));
              }
              else {
                out.writeByte((byte)(0xE0 | ((c >> 12) & 0x0F)));
                out.writeByte((byte)(0x80 | ((c >>  6) & 0x3F)));
                out.writeByte((byte)(0x80 | ((c >>  0) & 0x3F)));
              }
            }
            else
            {
              if (createArray) {
                bytes[index++] = (byte)(0xC0 | ((c >>  6) & 0x1F));
                bytes[index++] = (byte)(0x80 | ((c >>  0) & 0x3F));
              }
              else {
                out.writeByte((byte)(0xC0 | ((c >>  6) & 0x1F)));
                out.writeByte((byte)(0x80 | ((c >>  0) & 0x3F)));
              }
            }
        }

        if (isLongUTF)
        {
          // write the following 3 bytes to terminate the string:
          // (11100000, 00000000, 00000000)
          if (createArray) {
            bytes[index++] = (byte)(0xE0 & 0xFF);
            bytes[index++] = 0;
            bytes[index++] = 0;
          }
          else {
            out.writeByte((byte)(0xE0 & 0xFF));
            out.writeByte(0);
            out.writeByte(0);
          }
        }
        if(createArray) {
          out.write(bytes);
        }
        //handle zero length string.
        if(! isLongUTF && strlen == 0) {
          out.writeByte(-1);
        }
    }

    /**
     * Reads in a string from the specified data input stream. The 
     * string has been encoded using a modified UTF-8 format. 
     * <p>
     * The first two bytes are read as if by 
     * <code>readUnsignedShort</code>. This value gives the number of 
     * following bytes that are in the encoded string, not
     * the length of the resulting string. The following bytes are then 
     * interpreted as bytes encoding characters in the UTF-8 format 
     * and are converted into characters. 
     * <p>
     * This method blocks until all the bytes are read, the end of the 
     * stream is detected, or an exception is thrown. 
     *
     * @param      in   a data input stream.
     * @exception  EOFException            if the input stream reaches the end
     *               before all the bytes.
     * @exception  IOException             if an I/O error occurs.
     * @exception  UTFDataFormatException  if the bytes do not represent a
     *               valid UTF-8 encoding of a Unicode string.
     * @see        java.io.DataInputStream#readUnsignedShort()
     
     * @see java.io.Externalizable#readExternal
     */
    public void readExternalFromArray(ArrayInputStream in) 
        throws IOException
    {
        arg_passer[0]        = rawData;

        rawLength = in.readDerbyUTF(arg_passer);

        rawData = arg_passer[0];

        // restoreToNull();
        value  = null;
        //stream = null;

        cKey = null;
    }
    char[][] arg_passer = new char[1][];

    // GemStone changes BEGIN
    // adapt readExternal so it can read a stream that was not written
    // by writeExternal. This can happen when the JDBC method
    // PreparedStatement.setCharacterStream is called.
    // See implementation of getString when this.stream is non-null
    public void readExternal(ObjectInput in) throws IOException {
      readExternal(in, true);
    }
        
    private void readExternal(ObjectInput in, boolean canBeNull)
    throws IOException
    {
      // support externalized as null
      if (canBeNull) {
        boolean isNull = in.readBoolean();
        if (isNull) {
          setToNull();
          return;
        }
      }
      readData(in, false /* flag reuse char array if possible*/);
    // GemStone changes END
    }

    void readData(DataInput in, boolean reuseArrayIfPossible)
        throws IOException {
        // if in.available() blocked at 0, use this default string size 

        int utflen = in.readUnsignedShort();

        int requiredLength;
        // minimum amount that is reasonable to grow the array
        // when we know the array needs to growby at least one
        // byte but we dont want to grow by one byte as that
        // is not performant
        int minGrowBy = growBy();
        if (utflen != 0)
        {
            // the object was not stored as a streaming column 
            // we know exactly how long it is
            requiredLength = utflen;
        }
        else
        {
            // the object was stored as a streaming column 
            // and we have a clue how much we can read unblocked 
            // OR
            // The original string was a 0 length string.
            requiredLength = 0; //in.available();
            if (requiredLength < minGrowBy)
                requiredLength = minGrowBy;
        }

        char[] str = new char[requiredLength];
        /* Now cannot reuse the array since it points to String's internal
         * char array. Even then this will be invoked on receiving side on a
         * newly create SQLChar so rawData would always be null.
        if ((rawData == null) || (requiredLength > rawData.length) || !reuseArrayIfPossible) {
            
            str = new char[requiredLength];
        } else {
            str = rawData;
        }
        */
        int arrayLength = str.length;

        // Set these to null to allow GC of the array if required.
        restoreToNull();

        int count = 0;
        int strlen = 0;

readingLoop:
        while ( ((count < utflen) || (utflen == 0)))
        {
            int c;

            try {

                c = in.readUnsignedByte();
            } catch (EOFException eof) {
                if (utflen != 0)
                    throw new EOFException();

                // This is the case for a 0 length string.
                // OR the string was originally streamed in
                // which puts a 0 for utflen but no trailing
                // E0,0,0 markers.
                break readingLoop;
            }

            //UTF encoding shouldn't have any byte as 255 as we see above.
            if (c == 255)      // read EOF
            {
              if (utflen != 0)
                  throw new EOFException();

              break;
            }

            // change it to an unsigned byte
            //c &= 0xFF;

            if (strlen >= arrayLength) // the char array needs to be grown 
            {
                int growby = 0; //in.available();
                // We know that the array needs to be grown by at least one.
                // However, even if the input stream wants to block on every
                // byte, we don't want to grow by a byte at a time.
                // Note, for large data (clob > 32k), it is performant
                // to grow the array by atleast 4k rather than a small amount
                // Even better maybe to grow by 32k but then may be
                // a little excess(?) for small data. 
                // hopefully in.available() will give a fair
                // estimate of how much data can be read to grow the 
                // array by larger and necessary chunks.
                // This performance issue due to 
                // the slow growth of this array was noticed since inserts
                // on clobs was taking a really long time as
                // the array here grew previously by 64 bytes each time 
                // till stream was drained.  (Derby-302)
                // for char, growby 64 seems reasonable, but for varchar
                // clob 4k or 32k is performant and hence
                // growBy() is override correctly to ensure this
                if (growby < minGrowBy)
                    growby = minGrowBy;

                int newstrlength = arrayLength + growby;
                char oldstr[] = str;
                str = new char[newstrlength];

                System.arraycopy(oldstr, 0, str, 0, arrayLength);
                arrayLength = newstrlength;
            }

            /// top fours bits of the first unsigned byte that maps to a 
            //  1,2 or 3 byte character
            //
            // 0000xxxx - 0 - 1 byte char
            // 0001xxxx - 1 - 1 byte char
            // 0010xxxx - 2 - 1 byte char
            // 0011xxxx - 3 - 1 byte char
            // 0100xxxx - 4 - 1 byte char
            // 0101xxxx - 5 - 1 byte char
            // 0110xxxx - 6 - 1 byte char
            // 0111xxxx - 7 - 1 byte char
            // 1000xxxx - 8 - error
            // 1001xxxx - 9 - error
            // 1010xxxx - 10 - error
            // 1011xxxx - 11 - error
            // 1100xxxx - 12 - 2 byte char
            // 1101xxxx - 13 - 2 byte char
            // 1110xxxx - 14 - 3 byte char
            // 1111xxxx - 15 - error

            int char2, char3;
            char actualChar;
            if ((c & 0x80) == 0x00)
            {
                // one byte character
                count++;
                actualChar = (char) c;
            }
            else if ((c & 0x60) == 0x40) // we know the top bit is set here
            { 
                // two byte character
                count += 2;
                if (utflen != 0 && count > utflen) 
                    throw new UTFDataFormatException();       
                char2 = in.readUnsignedByte();
                if ((char2 & 0xC0) != 0x80)
                    throw new UTFDataFormatException();       
                actualChar = (char)(((c & 0x1F) << 6) | (char2 & 0x3F));
            }
            else if ((c & 0x70) == 0x60) // we know the top bit is set here
            {
                // three byte character
                count += 3;
                if (utflen != 0 && count > utflen) 
                    throw new UTFDataFormatException();       
                char2 = in.readUnsignedByte();
                char3 = in.readUnsignedByte();
                if ((c == 0xE0) && (char2 == 0) && (char3 == 0)
                    && (utflen == 0))
                {
                    // we reached the end of a long string,
                    // that was terminated with
                    // (11100000, 00000000, 00000000)
                    break readingLoop;
                }

                if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                    throw new UTFDataFormatException();       
                
                
                actualChar = (char)(((c & 0x0F) << 12) |
                                           ((char2 & 0x3F) << 6) |
                                           (char3 & 0x3F));
            }
            else {

        // GemStone changes BEGIN
              //originally: throw new UTFDataFormatException();
              throw new UTFDataFormatException("char=" + c + " utflen=" + utflen
                  + " count=" + count + " str=" + new String(str));
        // GemStone changes END
            }

            str[strlen++] = actualChar;
        }


        rawData = str;
        if (strlen < utflen) {
          char[] newCharArray = new char[strlen];
          System.arraycopy(str, 0, newCharArray, 0, strlen);
          rawData = newCharArray;
        }

        rawLength = strlen;
        value = null;
        //stream = null;
        cKey = null;
    }

    /**
     * returns the reasonable minimum amount by 
     * which the array can grow . See readExternal. 
     * when we know that the array needs to grow by at least
     * one byte, it is not performant to grow by just one byte
     * instead this amount is used to provide a resonable growby size.
     * @return minimum reasonable growby size
     */
    protected int growBy()
    {
        return GROWBY_FOR_CHAR;  //seems reasonable for a char
    }
    /**
     * @see Storable#restoreToNull
     *
     */
//    final // GemStoneAddition
    public void restoreToNull()
    {
        value = null;
        //stream = null;
        rawLength = -1;
        cKey = null;
// GemStone changes BEGIN
        this.rawData = null;
// GemStone changes END
    }

    /**
        @exception StandardException thrown on error
     */
    public boolean compare(int op,
                           DataValueDescriptor other,
                           boolean orderedNulls,
                           boolean unknownRV)
        throws StandardException
    {
        if (!orderedNulls)      // nulls are unordered
        {
            if (this.isNull() || ((DataValueDescriptor) other).isNull())
                return unknownRV;
        }

        /* When comparing String types to non-string types, we always
         * convert the string type to the non-string type.
         */
        if (! (other instanceof SQLChar))
        {
            return other.compare(flip(op), this, orderedNulls, unknownRV);
        }

        /* Do the comparison */
        return super.compare(op, other, orderedNulls, unknownRV);
    }  
    

    /**
        @exception StandardException thrown on error
     */
    public int compare(DataValueDescriptor other) throws StandardException
    {
        /* Use compare method from dominant type, negating result
         * to reflect flipping of sides.
         */
        if (typePrecedence() < other.typePrecedence())
        {
            return -Integer.signum(other.compare(this));
        }

        // stringCompare deals with null as comparable and smallest
        return stringCompare(this, (SQLChar)other);
    }

    /*
     * CloneableObject interface
     */

    /** From CloneableObject
     *  Shallow clone a StreamStorable without objectifying.  This is used to 
     *  avoid unnecessary objectifying of a stream object.  The only 
     *  difference of this method from getClone is this method does not 
     *  objectify a stream.
     */
    public Object cloneObject()
    {
// GemStone changes BEGIN
        return getClone();
        /* (original code)
        if (stream == null)
            return getClone();
        SQLChar self = (SQLChar) getNewNull();
        self.copyState(this);
        return self;
        */
// GemStone changes END
    }

    /*
     * DataValueDescriptor interface
     */

    /** @see DataValueDescriptor#getClone */
    public DataValueDescriptor getClone()
    {
        try
        {
            return new SQLChar(getString());
        }
        catch (StandardException se)
        {
// GemStone changes BEGIN
          throw GemFireXDRuntimeException.newRuntimeException(
              "Unexpected exception", se);
          /* (original code)
            if (SanityManager.DEBUG)
                SanityManager.THROWASSERT("Unexpected exception", se);
            return null;
          */
// GemStone changes END
        }
    }

    /**
     * @see DataValueDescriptor#getNewNull
     *
     */
    public DataValueDescriptor getNewNull()
    {
        return new SQLChar();
    }

    /** @see StringDataValue#getValue(RuleBasedCollator) */
    public StringDataValue getValue(RuleBasedCollator collatorForComparison)
    {
        if (collatorForComparison == null)
        {//null collatorForComparison means use UCS_BASIC for collation
            return this;            
        } else {
            //non-null collatorForComparison means use collator sensitive
            //implementation of SQLChar
             CollatorSQLChar s = new CollatorSQLChar(collatorForComparison);
             s.copyState(this);
             return s;
        }
    }

    /** 
     * @see DataValueDescriptor#setValueFromResultSet 
     *
     * @exception SQLException      Thrown on error
     * @throws StandardException 
     */
    public final void setValueFromResultSet(ResultSet resultSet, int colNumber,
                                      boolean isNullable)
        throws SQLException, StandardException
    {
            setValue(resultSet.getString(colNumber));
    }

    /**
        Set the value into a PreparedStatement.
    */
    public final void setInto(
    PreparedStatement   ps, 
    int                 position) 
        throws SQLException, StandardException 
    {
        ps.setString(position, getString());
    }



    public void setValue(String theValue) throws StandardException
    {
        //stream = null;
        rawLength = -1;
        cKey = null;

        value = theValue;
// GemStone changes BEGIN
        this.rawData = null;
// GemStone changes END
    }

    public void setValue(boolean theValue) throws StandardException
    {
        // match JCC.
        setValue(theValue ? "1" : "0");
    }

    public void setValue(int theValue)  throws StandardException
    {
        setValue(Integer.toString(theValue));
    }

    public void setValue(double theValue)  throws StandardException
    {
        setValue(Double.toString(theValue));
    }

    public void setValue(float theValue)  throws StandardException
    {
        setValue(Float.toString(theValue));
    }

    public void setValue(short theValue)  throws StandardException
    {
        setValue(Short.toString(theValue));
    }

    public void setValue(long theValue)  throws StandardException
    {
        setValue(Long.toString(theValue));
    }

    public void setValue(byte theValue)  throws StandardException
    {
        setValue(Byte.toString(theValue));
    }

    public void setValue(byte[] theValue) throws StandardException
    {
        if (theValue == null)
        {
            restoreToNull();
            return;
        }

        /*
        ** We can't just do a new String(theValue)
        ** because that method assumes we are converting
        ** ASCII and it will take on char per byte.
        ** So we need to convert the byte array to a
        ** char array and go from there.
        **
        ** If we have an odd number of bytes pad out.
        */
        int mod = (theValue.length % 2);
        int len = (theValue.length/2) + mod;
        char[] carray = new char[len];
        int cindex = 0;
        int bindex = 0;

        /*
        ** If we have a left over byte, then get
        ** that now.
        */
        if (mod == 1)
        {
            carray[--len] = (char)(theValue[theValue.length - 1] << 8);
        }

        for (; cindex < len; bindex+=2, cindex++)
        {
            carray[cindex] = (char)((theValue[bindex] << 8) |
                                (theValue[bindex+1] & 0x00ff));
        }

        setValue(new String(carray));
    }

    /**
        Only to be called when an application through JDBC is setting a
        SQLChar to a java.math.BigDecimal.
    */
    public void setBigDecimal(Number bigDecimal)  throws StandardException
    {
        if (bigDecimal == null)
            setToNull();
        else
            setValue(bigDecimal.toString());
    }

    /** @exception StandardException        Thrown on error */
    public void setValue(Date theValue, Calendar cal) throws StandardException
    {
        String strValue = null;
        if( theValue != null)
        {
            if( cal == null)
                strValue = theValue.toString();
            else
            {
                cal.setTime( theValue);
                StringBuilder sb = new StringBuilder();
                formatJDBCDate( cal, sb);
                strValue= sb.toString();
            }
        }
        setValue( strValue);
    }

    /** @exception StandardException        Thrown on error */
    public void setValue(Time theValue, Calendar cal) throws StandardException
    {
        String strValue = null;
        if( theValue != null)
        {
            if( cal == null)
                strValue = theValue.toString();
            else
            {
                cal.setTime( theValue);
                StringBuilder sb = new StringBuilder();
                formatJDBCTime( cal, sb);
                strValue= sb.toString();
            }
        }
        setValue( strValue);
    }

    /** @exception StandardException        Thrown on error */
    public void setValue(
    Timestamp   theValue, 
    Calendar    cal) 
        throws StandardException
    {
        String strValue = null;
        if( theValue != null)
        {
            if( cal == null)
                strValue = theValue.toString();
            else
            {
                cal.setTime( theValue);
                StringBuilder sb = new StringBuilder();
                formatJDBCDate( cal, sb);
                sb.append( ' ');
                formatJDBCTime( cal, sb);
                int micros = 
                    (theValue.getNanos() + SQLTimestamp.FRACTION_TO_NANO/2) / 
                        SQLTimestamp.FRACTION_TO_NANO;

                if( micros > 0)
                {
                    sb.append( '.');
                    String microsStr = Integer.toString( micros);
                    if(microsStr.length() > SQLTimestamp.MAX_FRACTION_DIGITS)
                    {
                        sb.append(
                            microsStr.substring(
                                0, SQLTimestamp.MAX_FRACTION_DIGITS));
                    }
                    else
                    {
                        for(int i = microsStr.length(); 
                            i < SQLTimestamp.MAX_FRACTION_DIGITS ; i++)
                        {
                            sb.append( '0');
                        }

                        sb.append( microsStr);
                    }
                }
                strValue= sb.toString();
            }
        }
        setValue( strValue);
    }

    private void formatJDBCDate( Calendar cal, StringBuilder sb)
    {
        SQLDate.dateToString( cal.get( Calendar.YEAR),
                              cal.get( Calendar.MONTH) - Calendar.JANUARY + 1,
                              cal.get( Calendar.DAY_OF_MONTH),
                              sb);
    }

    private void formatJDBCTime( Calendar cal, StringBuilder sb)
    {
        SQLTime.timeToString(
            cal.get(Calendar.HOUR), 
            cal.get(Calendar.MINUTE), 
            cal.get(Calendar.SECOND), 
            sb);
    }

    /**
     * Set the value from the stream which is in the on-disk format.
     * @param theStream On disk format of the stream
     * @param valueLength length of the logical value in characters.
     */
    public final void setValue(InputStream theStream, int valueLength)
        throws StandardException
    {
        setStream(theStream);
    }
    
    /**
     * Allow any Java type to be cast to a character type using
     * Object.toString.
     * @see DataValueDescriptor#setObjectForCast
     * 
     * @exception StandardException
     *                thrown on failure
     */
    public void setObjectForCast(
    Object  theValue, 
    boolean instanceOfResultType,
    String  resultTypeClassName) 
        throws StandardException 
    {
        if (theValue == null)
        {
            setToNull();
            return;
        }

// GemStone changes BEGIN
        if (resultTypeClassName != null) {
          if ("java.lang.String".equals(resultTypeClassName)) {
            setValue(theValue.toString());
            return;
          }
        }
        else if (String.class.equals(theValue.getClass())) {
          setValue((String)theValue);
          return;
        }
        /* (original code)
        if ("java.lang.String".equals(resultTypeClassName))
            setValue(theValue.toString());
        else
        */
// GemStone changes END
            super.setObjectForCast(
                theValue, instanceOfResultType, resultTypeClassName);
    }
    
    protected void setFrom(DataValueDescriptor theValue) 
        throws StandardException 
    {
// Gemstone changes BEGIN
      switch (theValue.getTypeFormatId()) {
        case StoredFormatIds.SQL_VARCHAR_ID:
        case StoredFormatIds.SQL_CHAR_ID:
        case StoredFormatIds.SQL_LONGVARCHAR_ID:
          copyFieldsByReference((SQLChar)theValue);
        default:
          setValue(theValue.getString());
      }
    }

    private void copyFieldsByReference(SQLChar theValue)
        throws StandardException {
      this.rawData = theValue.rawData;
      this.rawLength = theValue.rawLength;
      this.cKey = theValue.cKey;
      this.value = theValue.value;
      //this.stream = theValue.stream;
    }
// Gemstone changes END
    /**
     * Normalization method - this method may be called when putting
     * a value into a SQLChar, for example, when inserting into a SQLChar
     * column.  See NormalizeResultSet in execution.
     *
     * @param desiredType   The type to normalize the source column to
     * @param source        The value to normalize
     *
     *
     * @exception StandardException             Thrown for null into
     *                                          non-nullable column, and for
     *                                          truncation error
     */

    public void normalize(
                DataTypeDescriptor desiredType,
                DataValueDescriptor source)
                    throws StandardException
    {

        normalize(desiredType, source.getString());

    }

    protected void normalize(DataTypeDescriptor desiredType, String sourceValue)
        throws StandardException
    {


        int desiredWidth = desiredType.getMaximumWidth();
        int sourceWidth = sourceValue.length();

        /*
        ** If the input is already the right length, no normalization is
        ** necessary - just return the source.
        */
        if (sourceWidth == desiredWidth) {
            setValue(sourceValue);
            return;
        }

        /*
        ** If the input is shorter than the desired type, construct a new
        ** SQLChar padded with blanks to the right length.
        */
        if (sourceWidth < desiredWidth)
        {
            setToNull();

            char[] ca;
// Gemstone changes BEGIN
            /*if ((rawData == null) || (desiredWidth > rawData.length)) {
            
                ca = rawData = new char[desiredWidth];
            } else {
               ca = rawData;
            }*/
// Gemstone changes END
            ca = rawData = new char[desiredWidth];
            sourceValue.getChars(0, sourceWidth, ca, 0);
            SQLChar.appendBlanks(ca, sourceWidth, desiredWidth - sourceWidth);

            rawLength = desiredWidth;

            return;
        }

        /*
        ** Check whether any non-blank characters will be truncated.
        */
        hasNonBlankChars(sourceValue, desiredWidth, sourceWidth);

        /*
        ** No non-blank characters will be truncated.  Truncate the blanks
        ** to the desired width.
        */

        String truncatedString = sourceValue.substring(0, desiredWidth);
        setValue(truncatedString);
    }

    /*
    ** Method to check for truncation of non blank chars.
    */
    protected final void hasNonBlankChars(String source, int start, int end)
        throws StandardException
    {
        /*
        ** Check whether any non-blank characters will be truncated.
        */
        for (int posn = start; posn < end; posn++)
        {
            if (source.charAt(posn) != ' ')
            {
                throw StandardException.newException(
                    SQLState.LANG_STRING_TRUNCATION, 
                    getTypeName(), 
                    StringUtil.formatForPrint(source), 
                    String.valueOf(start));
            }
        }
    }

    ///////////////////////////////////////////////////////////////
    //
    // VariableSizeDataValue INTERFACE
    //
    ///////////////////////////////////////////////////////////////
    
    /**
     * Set the width of the to the desired value.  Used
     * when CASTing.  Ideally we'd recycle normalize(), but
     * the behavior is different (we issue a warning instead
     * of an error, and we aren't interested in nullability).
     *
     * @param desiredWidth  the desired length
     * @param desiredScale  the desired scale (ignored)
     * @param errorOnTrunc  throw an error on truncation
     *
     * @exception StandardException     Thrown when errorOnTrunc
     *      is true and when a shrink will truncate non-white
     *      spaces.
     */
    public void setWidth(int desiredWidth,
                                    int desiredScale, // Ignored
                                    boolean errorOnTrunc)
                            throws StandardException
    {
        int sourceWidth;

        /*
        ** If the input is NULL, nothing to do.
        */
        if (getString() == null)
        {
            return;
        }

        sourceWidth = getLength();

        /*
        ** If the input is shorter than the desired type, construct a new
        ** SQLChar padded with blanks to the right length.  Only
        ** do this if we have a SQLChar -- SQLVarchars don't
        ** pad.
        */
        if (sourceWidth < desiredWidth)
        {
            if (!(this instanceof SQLVarchar))
            {
                StringBuilder    strbuf;

                strbuf = new StringBuilder(getString());
    
                for ( ; sourceWidth < desiredWidth; sourceWidth++)
                {
                    strbuf.append(' ');
                }
    
                setValue(new String(strbuf));
            }
        }
        else if (sourceWidth > desiredWidth && desiredWidth > 0)
        {
            /*
            ** Check whether any non-blank characters will be truncated.
            */
            if (errorOnTrunc)
                hasNonBlankChars(getString(), desiredWidth, sourceWidth);
            //RESOLVE: should issue a warning instead

            /*
            ** Truncate to the desired width.
            */
            setValue(getString().substring(0, desiredWidth));
        }
        return;
    }

    /*
    ** SQL Operators
    */

    /**
     * The = operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left          The value on the left side of the =
     * @param right         The value on the right side of the =
     *
     * @return  A SQL boolean value telling whether the two parameters are equal
     *
     * @exception StandardException     Thrown on error
     */

    public BooleanDataValue equals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException
    {
        boolean comparison;

        if ((left instanceof SQLChar) && (right instanceof SQLChar))
        {
            comparison = stringCompare((SQLChar) left, (SQLChar) right) == 0;
        }
        else
        {
            comparison = stringCompare(left.getString(),
                                       right.getString()) == 0;
        }

        return SQLBoolean.truthValue(left,
                                     right,
                                     comparison);
    }

    /**
     * The <> operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left          The value on the left side of the <>
     * @param right         The value on the right side of the <>
     *
     * @return  A SQL boolean value telling whether the two parameters
     * are not equal
     *
     * @exception StandardException     Thrown on error
     */

    public BooleanDataValue notEquals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException
    {
        boolean comparison;

        if ((left instanceof SQLChar) && (right instanceof SQLChar))
        {
            comparison = stringCompare((SQLChar) left, (SQLChar) right) != 0;
        }
        else
        {
            comparison = stringCompare(left.getString(),
                                       right.getString()) != 0;
        }

        return SQLBoolean.truthValue(left,
                                     right,
                                     comparison);
    }

    /**
     * The < operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left          The value on the left side of the <
     * @param right         The value on the right side of the <
     *
     * @return  A SQL boolean value telling whether the first operand is
     *          less than the second operand
     *
     * @exception StandardException     Thrown on error
     */

    public BooleanDataValue lessThan(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException
    {
        boolean comparison;

        if ((left instanceof SQLChar) && (right instanceof SQLChar))
        {
            comparison = stringCompare((SQLChar) left, (SQLChar) right) < 0;
        }
        else
        {
            comparison = stringCompare(left.getString(),
                                       right.getString()) < 0;
        }

        return SQLBoolean.truthValue(left,
                                     right,
                                     comparison);
    }

    /**
     * The > operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left          The value on the left side of the >
     * @param right         The value on the right side of the >
     *
     * @return  A SQL boolean value telling whether the first operand is
     *          greater than the second operand
     *
     * @exception StandardException     Thrown on error
     */

    public BooleanDataValue greaterThan(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException
    {
        boolean comparison;

        if ((left instanceof SQLChar) && (right instanceof SQLChar))
        {
            comparison = stringCompare((SQLChar) left, (SQLChar) right) > 0;
        }
        else
        {
            comparison = stringCompare(left.getString(),
                                       right.getString()) > 0;
        }

        return SQLBoolean.truthValue(left,
                                     right,
                                     comparison);
    }

    /**
     * The <= operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left          The value on the left side of the <=
     * @param right         The value on the right side of the <=
     *
     * @return  A SQL boolean value telling whether the first operand is
     *          less than or equal to the second operand
     *
     * @exception StandardException     Thrown on error
     */

    public BooleanDataValue lessOrEquals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException
    {
        boolean comparison;

        if ((left instanceof SQLChar) && (right instanceof SQLChar))
        {
            comparison = stringCompare((SQLChar) left, (SQLChar) right) <= 0;
        }
        else
        {
            comparison = stringCompare(left.getString(),
                                       right.getString()) <= 0;
        }

        return SQLBoolean.truthValue(left,
                                     right,
                                     comparison);
    }

    /**
     * The >= operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left          The value on the left side of the >=
     * @param right         The value on the right side of the >=
     *
     * @return  A SQL boolean value telling whether the first operand is
     *          greater than or equal to the second operand
     *
     * @exception StandardException     Thrown on error
     */

    public BooleanDataValue greaterOrEquals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException
    {
        boolean comparison;

        if ((left instanceof SQLChar) && (right instanceof SQLChar))
        {
            comparison = stringCompare((SQLChar) left, (SQLChar) right) >= 0;
        }
        else
        {
            comparison = stringCompare(left.getString(),
                                       right.getString()) >= 0;
        }

        return SQLBoolean.truthValue(left,
                                     right,
                                     comparison);
    }

    /*
    ** Concatable interface
    */
    /**
     * This method implements the char_length function for char.
     *
     * @param result    The result of a previous call to this method, null
     *                  if not called yet
     *
     * @return  A SQLInteger containing the length of the char value
     *
     * @exception StandardException     Thrown on error
     *
     * @see ConcatableDataValue#charLength(NumberDataValue)
     */
    public NumberDataValue charLength(NumberDataValue result)
                            throws StandardException
    {
        if (result == null)
        {
            result = new SQLInteger();
        }

        if (this.isNull())
        {
            result.setToNull();
            return result;
        }

        result.setValue(this.getLength());
        return result;
    }

    /**
     * @see StringDataValue#concatenate
     *
     * @exception StandardException     Thrown on error
     */
    public StringDataValue concatenate(
                StringDataValue leftOperand,
                StringDataValue rightOperand,
                StringDataValue result)
        throws StandardException
    {
        if (leftOperand.isNull() || leftOperand.getString() == null ||
            rightOperand.isNull() || rightOperand.getString() == null)
        {
            result.setToNull();
            return result;
        }

        result.setValue(
                leftOperand.getString().concat(rightOperand.getString()));

        return result;
    }


    /**
     * This method implements the like function for char (with no escape value).
     *
     * @param pattern       The pattern to use
     *
     * @return  A SQL boolean value telling whether the first operand is
     *          like the second operand
     *
     * @exception StandardException     Thrown on error
     */
    public BooleanDataValue like(DataValueDescriptor pattern)
                                throws StandardException
    {
        Boolean likeResult;

        // note that we call getLength() because the length
        // of the char array may be different than the
        // length we should be using (i.e. getLength()).
        // see getCharArray() for more info
        char[] evalCharArray = getCharArray(true);
        char[] patternCharArray = ((SQLChar)pattern).getCharArray(true);
        likeResult = Like.like(evalCharArray, 
                               getLength(),
                                   patternCharArray,
                               pattern.getLength(),
                               null);

        return SQLBoolean.truthValue(this,
                                     pattern,
                                     likeResult);
    }

    /**
     * This method implements the like function for char with an escape value.
     *
     * @param pattern       The pattern to use
     *
     * @return  A SQL boolean value telling whether the first operand is
     *          like the second operand
     *
     * @exception StandardException     Thrown on error
     */

    public BooleanDataValue like(
                             DataValueDescriptor pattern,
                             DataValueDescriptor escape)
                                throws StandardException
    {
        Boolean likeResult;

        if (SanityManager.DEBUG)
            SanityManager.ASSERT(
                             pattern instanceof StringDataValue &&
                             escape instanceof StringDataValue,
            "All three operands must be instances of StringDataValue");

        // ANSI states a null escape yields 'unknown' results 
        //
        // This method is only called when we have an escape clause, so this 
        // test is valid

        if (escape.isNull())
        {
            throw StandardException.newException(SQLState.LANG_ESCAPE_IS_NULL);
        }

        // note that we call getLength() because the length
        // of the char array may be different than the
        // length we should be using (i.e. getLength()).
        // see getCharArray() for more info
        char[] evalCharArray = getCharArray(true);
        char[] patternCharArray = ((SQLChar)pattern).getCharArray(true);
        char[] escapeCharArray = (((SQLChar) escape).getCharArray(true));
        int escapeLength = escape.getLength();

        if (escapeCharArray != null && escapeLength != 1 )
        {
            throw StandardException.newException(
                    SQLState.LANG_INVALID_ESCAPE_CHARACTER,
                    new String(escapeCharArray));
        }
        likeResult = Like.like(evalCharArray, 
                               getLength(),
                                   patternCharArray,
                               pattern.getLength(),
                               escapeCharArray,
                               escapeLength,
                               null);

        return SQLBoolean.truthValue(this,
                                     pattern,
                                     likeResult);
    }

    /**
     * This method implements the locate function for char.
     * @param searchFrom    - The string to search from
     * @param start         - The position to search from in string searchFrom
     * @param result        - The object to return
     *
     * Note: use getString() to get the string to search for.
     *
     * @return  The position in searchFrom the fist occurrence of this.value.
     *              0 is returned if searchFrom does not contain this.value.
     * @exception StandardException     Thrown on error
     */
    public NumberDataValue locate(  StringDataValue searchFrom,
                                    NumberDataValue start,
                                    NumberDataValue result)
                                    throws StandardException
    {
        int startVal;

        if( result == null )
        {
            result = new SQLInteger();
        }
        
        if( start.isNull() )
        {
            startVal = 1;
        }
        else
        {
            startVal = start.getInt();
        }

        if( isNull() || searchFrom.isNull() )
        {
            result.setToNull();
            return result;
        }

        String mySearchFrom = searchFrom.getString();
        String mySearchFor = this.getString();

        /* the below 2 if conditions are to emulate DB2's behavior */
        if( startVal < 1 )
        {
            throw StandardException.newException(
                    SQLState.LANG_INVALID_PARAMETER_FOR_SEARCH_POSITION, 
                    new String(getString()), new String(mySearchFrom), 
                    new Integer(startVal));
        }
        
        if( mySearchFor.length() == 0 )
        {
            result.setValue( startVal );
            return result;
        }

        result.setValue( mySearchFrom.indexOf(mySearchFor, startVal - 1) + 1);
        return result;
    }

    /**
     * The SQL substr() function.
     *
     * @param start     Start of substr
     * @param length    Length of substr
     * @param result    The result of a previous call to this method,
     *                  null if not called yet.
     * @param maxLen    Maximum length of the result
     *
     * @return  A ConcatableDataValue containing the result of the substr()
     *
     * @exception StandardException     Thrown on error
     */
    public ConcatableDataValue substring(
                NumberDataValue start,
                NumberDataValue length,
                ConcatableDataValue result,
                int maxLen)
        throws StandardException
    {
        int startInt;
        int lengthInt;
        StringDataValue stringResult;

        if (result == null)
        {
            result = getNewVarchar();
        }

        stringResult = (StringDataValue) result;

        /* The result is null if the receiver (this) is null or if the length 
         * is negative.
         * We will return null, which is the only sensible thing to do.
         * (If user did not specify a length then length is not a user null.)
         */
        if (this.isNull() || start.isNull() || 
                (length != null && length.isNull()))
        {
            stringResult.setToNull();
            return stringResult;
        }

        startInt = start.getInt();

        // If length is not specified, make it till end of the string
        if (length != null)
        {
            lengthInt = length.getInt();
        }
        else lengthInt = maxLen - startInt + 1;

        /* DB2 Compatibility: Added these checks to match DB2. We currently 
         * enforce these limits in both modes. We could do these checks in DB2 
         * mode only, if needed, so leaving earlier code for out of range in 
         * for now, though will not be exercised
         */
        if ((startInt <= 0 || lengthInt < 0 || startInt > maxLen ||
                lengthInt > maxLen - startInt + 1))
        {
            throw StandardException.newException(
                    SQLState.LANG_SUBSTR_START_OR_LEN_OUT_OF_RANGE);
        }
            
        // Return null if length is non-positive
        if (lengthInt < 0)
        {
            stringResult.setToNull();
            return stringResult;
        }

        /* If startInt < 0 then we count from the right of the string */
        if (startInt < 0)
        {
            // Return '' if window is to left of string.
            if (startInt + getLength() < 0 &&
                (startInt + getLength() + lengthInt <= 0))
            {
                stringResult.setValue("");
                return stringResult;
            }

            // Convert startInt to positive to get substring from right
            startInt += getLength();

            while (startInt < 0)
            {
                startInt++;
                lengthInt--;
            }
        }
        else if (startInt > 0)
        {
            /* java substring() is 0 based */
            startInt--;
        }

        /* Oracle docs don't say what happens if the window is to the
         * left of the string.  Return "" if the window
         * is to the left or right.
         */
        if (lengthInt == 0 ||
            lengthInt <= 0 - startInt ||
            startInt > getLength())
        {
            stringResult.setValue("");
            return stringResult;
        }

        if (lengthInt >= getLength() - startInt)
        {
            stringResult.setValue(getString().substring(startInt));
        }
        else
        {
            stringResult.setValue(
                getString().substring(startInt, startInt + lengthInt));
        }

        return stringResult;
    }

    /**
     * This function public for testing purposes.
     *
     * @param trimType  Type of trim (LEADING, TRAILING, or BOTH)
     * @param trimChar  Character to trim
     * @param source    String from which to trim trimChar
     *
     * @return A String containing the result of the trim.
     */
    private String trimInternal(int trimType, char trimChar, String source)
    {
        if (source == null) {
            return null;
        }
        
        int len = source.length();
        int start = 0;
        if (trimType == LEADING || trimType == BOTH)
        {
            for (; start < len; start++)
                if (trimChar != source.charAt(start))
                    break;
        }

        if (start == len)
            return "";

        int end = len - 1;
        if (trimType == TRAILING || trimType == BOTH)
        {
            for (; end >= 0; end--)
                if (trimChar != source.charAt(end))
                    break;
        }
        if (end == -1)
            return "";

        return source.substring(start, end + 1);
    }

    /**
     * @param trimType  Type of trim (LEADING, TRAILING, or BOTH)
     * @param trimChar  Character to trim from this SQLChar (may be null)
     * @param result    The result of a previous call to this method,
     *                  null if not called yet.
     *
     * @return A StringDataValue containing the result of the trim.
     */
    public StringDataValue ansiTrim(
    int             trimType, 
    StringDataValue trimChar, 
    StringDataValue result)
            throws StandardException 
    {

        if (result == null)
        {
            result = getNewVarchar();
        }

        if (trimChar == null || trimChar.getString() == null)
        {
            result.setToNull();
            return result;
        }


        if (trimChar.getString().length() != 1)
        {
            throw StandardException.newException(
                    SQLState.LANG_INVALID_TRIM_CHARACTER, trimChar.getString());           
        }

        char trimCharacter = trimChar.getString().charAt(0);

        result.setValue(trimInternal(trimType, trimCharacter, getString()));
        return result; 
    }

    /** @see StringDataValue#upper
     *
     * @exception StandardException     Thrown on error
     */
    public StringDataValue upper(StringDataValue result)
                            throws StandardException
    {
        if (result == null)
        {
            result = (StringDataValue) getNewNull();
        }

        if (this.isNull())
        {
            result.setToNull();
            return result;
        }
        
        String upper = getString();
        upper = upper.toUpperCase(getLocale());
        result.setValue(upper);
        return result;
    }

    /** @see StringDataValue#lower 
     *
     * @exception StandardException     Thrown on error
     */
    public StringDataValue lower(StringDataValue result)
                            throws StandardException
    {
        if (result == null)
        {
            result = (StringDataValue) getNewNull();
        }

        if (this.isNull())
        {
            result.setToNull();
            return result;
        }

        
        String lower = getString();
        lower = lower.toLowerCase(getLocale());
        result.setValue(lower);
        return result;
    }

    /*
     * DataValueDescriptor interface
     */

    /** @see DataValueDescriptor#typePrecedence */
    public int typePrecedence()
    {
        return TypeId.CHAR_PRECEDENCE;
    }

    /**
     * Compare two Strings using standard SQL semantics.
     *
     * @param op1               The first String
     * @param op2               The second String
     *
     * @return  -1 - op1 <  op2
     *           0 - op1 == op2
     *           1 - op1 > op2
     */
    public static int stringCompare(String op1, String op2)
    {
        int         posn;
        char        leftchar;
        char        rightchar;
        int         leftlen;
        int         rightlen;
        int         retvalIfLTSpace;
        String      remainingString;
        int         remainingLen;

        /*
        ** By convention, nulls sort High, and null == null
        *
        if (op1 == null || op2 == null)
        {
            if (op1 != null)    // op2 == null
                return -1;
            if (op2 != null)    // op1 == null
                return 1;
            return 0;           // both null
        }
        /*
        ** Compare characters until we find one that isn't equal, or until
        ** one String or the other runs out of characters.
        */

        leftlen = op1.length();
        rightlen = op2.length();

        int shorterLen = leftlen < rightlen ? leftlen : rightlen;

        for (posn = 0; posn < shorterLen; posn++)
        {
            leftchar = op1.charAt(posn);
            rightchar = op2.charAt(posn);
            if (leftchar != rightchar)
            {
                if (leftchar < rightchar)
                    return -1;
                else
                    return 1;
            }
        }

        /*
        ** All the characters are equal up to the length of the shorter
        ** string.  If the two strings are of equal length, the values are
        ** equal.
        */
        if (leftlen == rightlen)
            return 0;

        /*
        ** One string is shorter than the other.  Compare the remaining
        ** characters in the longer string to spaces (the SQL standard says
        ** that in this case the comparison is as if the shorter string is
        ** padded with blanks to the length of the longer string.
        */
        if (leftlen > rightlen)
        {
            /*
            ** Remaining characters are on the left.
            */

            /* If a remaining character is less than a space, 
             * return -1 (op1 < op2) */
            retvalIfLTSpace = -1;
            remainingString = op1;
            posn = rightlen;
            remainingLen = leftlen;
        }
        else
        {
            /*
            ** Remaining characters are on the right.
            */

            /* If a remaining character is less than a space, 
             * return 1 (op1 > op2) */
            retvalIfLTSpace = 1;
            remainingString = op2;
            posn = leftlen;
            remainingLen = rightlen;
        }

        /* Look at the remaining characters in the longer string */
        for ( ; posn < remainingLen; posn++)
        {
            char    remainingChar;

            /*
            ** Compare the characters to spaces, and return the appropriate
            ** value, depending on which is the longer string.
            */

            remainingChar = remainingString.charAt(posn);

            if (remainingChar < ' ')
                return retvalIfLTSpace;
            else if (remainingChar > ' ')
                return -retvalIfLTSpace;
        }

        /* The remaining characters in the longer string were all spaces,
        ** so the strings are equal.
        */
        return 0;
    }

// GemStone changes BEGIN
    /**
     * Compare two SQLChars ignoring case differences.
     * 
     * @exception StandardException
     *              Thrown on error
     */
    protected final int stringCompareIgnoreCase(SQLChar char1, SQLChar char2)
        throws StandardException {
      if (!(char1 instanceof CollationElementsInterface)) {
        return stringCompareIgnoreCase(char1.getCharArray(true),
            char1.getLength(), char2.getCharArray(true), char2.getLength());
      }
      else {
        return stringCompare(char1, char2);
      }
    }

    /**
     * Compare two Strings using standard SQL semantics but ignoring case
     * differences. No case-insensitive comparisons for collators (user should
     * instead use a case-insensitive collation)
     */
    public final int compareIgnoreCase(DataValueDescriptor other)
        throws StandardException {
      /* Use compare method from dominant type, negating result
       * to reflect flipping of sides.
       */
      if (typePrecedence() < other.typePrecedence()) {
        return -Integer.signum(other.compare(this));
      }

      // stringCompare deals with null as comparable and smallest
      final SQLChar otherChar = (SQLChar)other;
      if (!(this instanceof CollationElementsInterface)) {
        return stringCompareIgnoreCase(getCharArray(true), getLength(),
            otherChar.getCharArray(true), otherChar.getLength());
      }
      else {
        return stringCompare(this, otherChar);
      }
    }

    /**
     * Compare two Strings using standard SQL semantics
     * but ignoring case differences.
     *
     * @param op1               The first String
     * @param op2               The second String
     *
     * @return  -1 - op1 <  op2
     *           0 - op1 == op2
     *           1 - op1 > op2
     */
    public static int stringCompareIgnoreCase(String op1, String op2) {
      int posn;
      int leftchar;
      int rightchar;
      int retvalIfLTSpace;
      String remainingString;
      int remainingLen;
  
      /*
      ** By convention, nulls sort High, and null == null
      */
      if (op1 == null || op2 == null) {
        if (op1 != null) // op2 == null
          return -1;
        if (op2 != null) // op1 == null
          return 1;
        return 0; // both null
      }

      final int leftlen = op1.length();
      final int rightlen = op2.length();
      /*
      ** Compare characters until we find one that isn't equal, or until
      ** one String or the other runs out of characters.
      */
      int shorterLen = leftlen < rightlen ? leftlen : rightlen;
      for (posn = 0; posn < shorterLen; posn++) {
        leftchar = op1.charAt(posn);
        rightchar = op2.charAt(posn);
        // no overflow because of numeric promotion of leftchar/rightchar
        int res = leftchar - rightchar;
        if (res != 0) {
          res = Character.toUpperCase(leftchar)
              - Character.toUpperCase(rightchar);
          if (res != 0) {
            return res;
          }
        }
      }

      /*
      ** All the characters are equal up to the length of the shorter
      ** string.  If the two strings are of equal length, the values are
      ** equal.
      */
      if (leftlen == rightlen) return 0;

      /*
      ** One string is shorter than the other.  Compare the remaining
      ** characters in the longer string to spaces (the SQL standard says
      ** that in this case the comparison is as if the shorter string is
      ** padded with blanks to the length of the longer string.
      */
      if (leftlen > rightlen) {
        /*
        ** Remaining characters are on the left.
        */

        /* If a remaining character is less than a space, 
         * return -1 (op1 < op2) */
        retvalIfLTSpace = -1;
        remainingString = op1;
        posn = rightlen;
        remainingLen = leftlen;
      }
      else {
        /*
        ** Remaining characters are on the right.
        */

        /* If a remaining character is less than a space, 
         * return 1 (op1 > op2) */
        retvalIfLTSpace = 1;
        remainingString = op2;
        posn = leftlen;
        remainingLen = rightlen;
      }

      /* Look at the remaining characters in the longer string */
      for (; posn < remainingLen; posn++) {
        int remainingChar;

        /*
        ** Compare the characters to spaces, and return the appropriate
        ** value, depending on which is the longer string.
        */
        remainingChar = Character.toUpperCase(remainingString.charAt(posn));

        if (remainingChar < ' ') return retvalIfLTSpace;
        else if (remainingChar > ' ') return -retvalIfLTSpace;
      }

      /* The remaining characters in the longer string were all spaces,
      ** so the strings are equal.
      */
      return 0;
    }

    /**
     * Compare two Strings using standard SQL semantics
     * but ignoring case differences.
     *
     * @param op1               The first String
     * @param op2               The second String
     *
     * @return  -1 - op1 <  op2
     *           0 - op1 == op2
     *           1 - op1 > op2
     */
    protected static int stringCompareIgnoreCase(char[] op1, int leftlen,
        char[] op2, int rightlen) {
      int posn;
      int leftchar;
      int rightchar;
      int retvalIfLTSpace;
      char[] remainingString;
      int remainingLen;
  
      /*
      ** By convention, nulls sort High, and null == null
      */
      if (op1 == null || op2 == null) {
        if (op1 != null) // op2 == null
          return -1;
        if (op2 != null) // op1 == null
          return 1;
        return 0; // both null
      }
      /*
      ** Compare characters until we find one that isn't equal, or until
      ** one String or the other runs out of characters.
      */
      int shorterLen = leftlen < rightlen ? leftlen : rightlen;
      for (posn = 0; posn < shorterLen; posn++) {
        leftchar = op1[posn];
        rightchar = op2[posn];
        // no overflow because of numeric promotion of leftchar/rightchar
        int res = leftchar - rightchar;
        if (res != 0) {
          res = Character.toUpperCase(leftchar)
              - Character.toUpperCase(rightchar);
          if (res != 0) {
            return res;
          }
        }
      }

      /*
      ** All the characters are equal up to the length of the shorter
      ** string.  If the two strings are of equal length, the values are
      ** equal.
      */
      if (leftlen == rightlen) return 0;

      /*
      ** One string is shorter than the other.  Compare the remaining
      ** characters in the longer string to spaces (the SQL standard says
      ** that in this case the comparison is as if the shorter string is
      ** padded with blanks to the length of the longer string.
      */
      if (leftlen > rightlen) {
        /*
        ** Remaining characters are on the left.
        */

        /* If a remaining character is less than a space, 
         * return -1 (op1 < op2) */
        retvalIfLTSpace = -1;
        remainingString = op1;
        posn = rightlen;
        remainingLen = leftlen;
      }
      else {
        /*
        ** Remaining characters are on the right.
        */

        /* If a remaining character is less than a space, 
         * return 1 (op1 > op2) */
        retvalIfLTSpace = 1;
        remainingString = op2;
        posn = leftlen;
        remainingLen = rightlen;
      }

      /* Look at the remaining characters in the longer string */
      for (; posn < remainingLen; posn++) {
        int remainingChar;

        /*
        ** Compare the characters to spaces, and return the appropriate
        ** value, depending on which is the longer string.
        */
        remainingChar = Character.toUpperCase(remainingString[posn]);

        if (remainingChar < ' ') return retvalIfLTSpace;
        else if (remainingChar > ' ') return -retvalIfLTSpace;
      }

      /* The remaining characters in the longer string were all spaces,
      ** so the strings are equal.
      */
      return 0;
    }

// GemStone changes END

    /** 
     * Compare two SQLChars.  
     *
     * @exception StandardException     Thrown on error
     */
     protected int stringCompare(SQLChar char1, SQLChar char2)
         throws StandardException
     {
         return stringCompare(char1.getCharArray(true), char1.getLength(), 
                              char2.getCharArray(true), char2.getLength());
     }

    /**
     * Compare two Strings using standard SQL semantics.
     *
     * @param op1               The first String
     * @param op2               The second String
     *
     * @return  -1 - op1 <  op2
     *           0 - op1 == op2
     *           1 - op1 > op2
     */
    protected static int stringCompare(
    char[]  op1, 
    int     leftlen, 
    char[]  op2, 
    int     rightlen)
    {
        int         posn;
        char        leftchar;
        char        rightchar;
        int         retvalIfLTSpace;
        char[]      remainingString;
        int         remainingLen;

        /*
        ** By convention, nulls sort High, and null == null
        */
        if (op1 == null || op2 == null)
        {
            if (op1 != null)    // op2 == null
                return -1;
            if (op2 != null)    // op1 == null
                return 1;
            return 0;           // both null
        }
        /*
        ** Compare characters until we find one that isn't equal, or until
        ** one String or the other runs out of characters.
        */
        int shorterLen = leftlen < rightlen ? leftlen : rightlen;
        for (posn = 0; posn < shorterLen; posn++)
        {
            leftchar = op1[posn];
            rightchar = op2[posn];
            if (leftchar != rightchar)
            {
                if (leftchar < rightchar)
                    return -1;
                else
                    return 1;
            }
        }

        /*
        ** All the characters are equal up to the length of the shorter
        ** string.  If the two strings are of equal length, the values are
        ** equal.
        */
        if (leftlen == rightlen)
            return 0;

        /*
        ** One string is shorter than the other.  Compare the remaining
        ** characters in the longer string to spaces (the SQL standard says
        ** that in this case the comparison is as if the shorter string is
        ** padded with blanks to the length of the longer string.
        */
        if (leftlen > rightlen)
        {
            /*
            ** Remaining characters are on the left.
            */

            /* If a remaining character is less than a space, 
             * return -1 (op1 < op2) */
            retvalIfLTSpace = -1;
            remainingString = op1;
            posn = rightlen;
            remainingLen = leftlen;
        }
        else
        {
            /*
            ** Remaining characters are on the right.
            */

            /* If a remaining character is less than a space, 
             * return 1 (op1 > op2) */
            retvalIfLTSpace = 1;
            remainingString = op2;
            posn = leftlen;
            remainingLen = rightlen;
        }

        /* Look at the remaining characters in the longer string */
        for ( ; posn < remainingLen; posn++)
        {
            char    remainingChar;

            /*
            ** Compare the characters to spaces, and return the appropriate
            ** value, depending on which is the longer string.
            */

            remainingChar = remainingString[posn];

            if (remainingChar < ' ')
                return retvalIfLTSpace;
            else if (remainingChar > ' ')
                return -retvalIfLTSpace;
        }

        /* The remaining characters in the longer string were all spaces,
        ** so the strings are equal.
        */
        return 0;
    }
        
    /**
     * This method gets called for the collation sensitive char classes ie
     * CollatorSQLChar, CollatorSQLVarchar, CollatorSQLLongvarchar,
     * CollatorSQLClob. These collation sensitive chars need to have the 
     * collation key in order to do string comparison. And the collation key
     * is obtained using the Collator object that these classes already have.
     * 
     * @return CollationKey obtained using Collator on the string
     * @throws StandardException
     */
    protected CollationKey getCollationKey() throws StandardException
    {
        char tmpCharArray[];

        if (cKey != null)
            return cKey;

        if (rawLength == -1)
        {
            /* materialize the string if input is a stream */
            tmpCharArray = getCharArray();
            if (tmpCharArray == null)
                return null;
        }
        
        int lastNonspaceChar = rawLength;

        while (lastNonspaceChar > 0 && 
               rawData[lastNonspaceChar - 1] == '\u0020')
            lastNonspaceChar--;         // count off the trailing spaces.

        RuleBasedCollator rbc = getCollatorForCollation();      
        cKey = rbc.getCollationKey(new String(rawData, 0, lastNonspaceChar));

        return cKey;
    }

    /*
     * String display of value
     */

    public String toString()
    {
// GemStone changes BEGIN
        if (this.value != null) {
          return this.value;
        }
        if (this.rawLength != -1) {
          return ClientSharedUtils.newWrappedString(
              this.rawData, 0, this.rawLength);
        }
        /*
        try {
          if (readFromStream()) {
            return ClientSharedUtils.newWrappedString(
                this.rawData, 0, this.rawLength);
          }
        } catch (EOFException eofe) {
          // if stream has reached its end already (e.g. this message is for
          // display) then don't throw an exception
          return "NULL";
        } catch (IOException ioe) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "unexpected exception", ioe);
        }
        */
        return "NULL";
        /* (original code)
        if (isNull()) {
            return "NULL";
        }

        if ((value == null) && (rawLength != -1)) {

            return new String(rawData, 0, rawLength);
        }

        if (stream != null) {
            try {
                return getString();
            } catch (Exception e) {
                return e.toString();
            }
        }

        return value;
        */
// GemStone changes END
    }

    /*
     * Hash code
     */
    public int hashCode()
    {
// Gemstone changes BEGIN
        try {
          final char[] data = getCharArray(true);
          if (data != null) {
            final int len = this.rawLength != -1 ? this.rawLength : data.length;
            if (len > 0) {
              return ResolverUtils
                  .getHashCodeOfCharArrayData(data, this.value, len);
            }
          }
        } catch (StandardException se) {
          throw GemFireXDRuntimeException.newRuntimeException(
              "unexpected exception", se);
        }
        return 0;
        /* (original code)
        try {
            if (getString() == null)
            {
                return 0;
            }
        }
        catch (StandardException se)
        {
            if (SanityManager.DEBUG)
                SanityManager.THROWASSERT("Unexpected exception", se);
            return 0;
        }


        /* value.hashCode() doesn't work because of the SQL blank padding 
         * behavior.
         * We want the hash code to be based on the value after the 
         * trailing blanks have been trimmed.  Calling trim() is too expensive
         * since it will create a new object, so here's what we do:
         *      o  Walk from the right until we've found the 1st
         *         non-blank character.
         *      o  Add up the characters from that character to the 1st in
         *         the string and return that as the hash code.
         *
        int index;
        int hashcode = 0;

        // value will have been set by the getString() above
        String lvalue = value;

        // Find 1st non-blank from the right
        for (index = lvalue.length() - 1; 
             index >= 0 && lvalue.charAt(index) == ' '; 
             index--)
        {
            ;
        }

        // Build the hash code
        for ( ; index >= 0; index--)
        {
            hashcode += lvalue.charAt(index);
        }

        return hashcode; 
        */
// Gemstone changes END
    }

    /**
     * Get a SQLVarchar for a built-in string function.  
     *
     * @return a SQLVarchar.
     *
     * @exception StandardException     Thrown on error
     */
    protected StringDataValue getNewVarchar() throws StandardException
    {
        return new SQLVarchar();
    }

// GemStone changes BEGIN
    /* (original code)
    protected void setLocaleFinder(LocaleFinder localeFinder)
    {
        this.localeFinder = localeFinder;
    }
    */
// GemStone changes END

    /** @exception StandardException        Thrown on error */
    private Locale getLocale() throws StandardException
    {
        return getLocaleFinder().getCurrentLocale();
    }

    protected RuleBasedCollator getCollatorForCollation() 
        throws StandardException
    {
        if (SanityManager.DEBUG) {
            // Sub-classes that support collation will override this method,
            // do don't expect it to be called here in the base class.
            SanityManager.THROWASSERT("No support for collators in base class");
        }
        return null;
    }

// GemStone changes BEGIN
    //Asif
    protected final FabricDatabase getLocaleFinder() {
      return Misc.getMemStore().getDatabase();
    }
    /* (original code)
    protected LocaleFinder getLocaleFinder()
    {
        // This is not very satisfactory, as it creates a dependency on
        // the DatabaseContext. It's the best I could do on short notice,
        // though.  -  Jeff
        if (localeFinder == null)
        {
            DatabaseContext dc = (DatabaseContext) 
                ContextService.getContext(DatabaseContext.CONTEXT_ID);
            if( dc != null) {
                localeFinder = dc.getDatabase();
            }
          localeFinder = Misc.getMemStore().getDatabase();
        }

        return localeFinder;
    }
    */
// GemStone changes END

    protected DateFormat getDateFormat() throws StandardException {
        return getLocaleFinder().getDateFormat();
    }
    protected DateFormat getTimeFormat() throws StandardException {
        return getLocaleFinder().getTimeFormat();
    }
    protected DateFormat getTimestampFormat() throws StandardException {
        return getLocaleFinder().getTimestampFormat();
    }

    protected DateFormat getDateFormat( Calendar cal) 
        throws StandardException {
        return setDateFormatCalendar( getLocaleFinder().getDateFormat(), cal);
    }
    protected DateFormat getTimeFormat( Calendar cal) 
        throws StandardException {
        return setDateFormatCalendar( getLocaleFinder().getTimeFormat(), cal);
    }
    protected DateFormat getTimestampFormat( Calendar cal) 
        throws StandardException {
        return setDateFormatCalendar(
                getLocaleFinder().getTimestampFormat(), cal);
    }

    private DateFormat setDateFormatCalendar( DateFormat df, Calendar cal)
    {
        if( cal != null && df.getTimeZone() != cal.getTimeZone())
        {
            // The DateFormat returned by getDateFormat may be cached and used
            // by other threads.  Therefore we cannot change its calendar.
            df = (DateFormat) df.clone();
            df.setCalendar( cal);
        }
        return df;
    }

    public int estimateMemoryUsage()
    {
        int sz = BASE_MEMORY_USAGE + ClassSize.estimateMemoryUsage( value);
// GemStone changes BEGIN
        // now rawData is never an extra copy
        if (this.value == null && this.rawData != null) {
          sz += 2 * this.rawData.length;
        }
        /* (original code)
        if( null != rawData)
            sz += 2*rawData.length;
        */
// GemStone changes END
        // Assume that cKey, stream, and localFinder are shared, 
        // so do not count their memory usage
        return sz;

    } // end of estimateMemoryUsage

    protected void copyState(SQLChar other) {

        this.value = other.value;
        this.rawData = other.rawData;
        this.rawLength = other.rawLength;
        this.cKey = other.cKey;
// GemStone changes BEGIN
        /* (original code)
        this.stream = other.stream;
        this.localeFinder = other.localeFinder;
        */
// GemStone changes END
    }

    /**
     * Gets a trace representation for debugging.
     *
     * @return a trace representation of this SQL Type.
     */
    public String getTraceString() throws StandardException {
        // Check if the value is SQL NULL.
        if (isNull()) {
            return "NULL";
        }

        return (toString());
    }

// GemStone changes BEGIN

  protected final boolean readFromStream(final InputStream stream)
      throws IOException {
    if (stream != null) {
      // since this is an external stream that was not
      // written by writeExternal, it cannot have null in it
      if (stream instanceof FormatIdInputStream) {
        readExternal((FormatIdInputStream)stream, false);
      }
      else {
        readExternal(new FormatIdInputStream(stream), false);
      }
      //this.stream = null;
      return true;
    }
    return false;
  }

  @Override
  public void toData(DataOutput out) throws IOException {
    if (!isNull()) {
      out.writeByte(getTypeId());
      writeData(out, true);
      return;
    }
    this.writeNullDVD(out);
  }

  @Override
  public void fromData(DataInput in) throws IOException,
      ClassNotFoundException {
    readData(in, false /* flag reuse char array if possible */);
  }

  @Override
  public void toDataForOptimizedResultHolder(DataOutput dos)
      throws IOException {
    assert !isNull();
    this.writeData(dos, false);
  }

  @Override
  public void fromDataForOptimizedResultHolder(DataInput dis)
      throws IOException, ClassNotFoundException {
    readData(dis, true /* flag reuse char array if possible */);
  }

  /**
   * Return length of this value in bytes.
   */
  @Override
  public int getLengthInBytes(final DataTypeDescriptor dtd)
      throws StandardException {
    final char[] data = getCharArray(true);
    if (data != null) {
      int strlen = this.rawLength != -1 ? this.rawLength : data.length;
      // comment out the following lines, see #46490 & writeBytes()
      // do not truncate the characters, 
      // as users may input string longer than max width
//      final int maxWidth;;
//      if (dtd != null && strlen > (maxWidth = dtd.getMaximumWidth())) {
//        strlen = maxWidth;
//      }
      int utflen = strlen;
      for (int index = 0; (index < strlen) && (utflen <= 65535); ++index) {
        int c = data[index];
        if ((c >= 0x0001) && (c <= 0x007F)) {
          // 1 byte for character
        }
        else if (c > 0x07FF) {
          utflen += 2; // 3 bytes for character
        }
        else {
          ++utflen; // 2 bytes for character
        }
      }
      return utflen;
    }
    return 0;
  }

  /**
   * Optimized write to a byte array at specified offset
   * 
   * @return number of bytes actually written
   */
  @Override
  public int writeBytes(final byte[] bytes, final int offset,
      final DataTypeDescriptor dtd) {
    // never called when value is null
    assert !isNull();

    final char[] data;
    try {
      data = getCharArray(true);
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception", se);
    }
    int strlen = this.rawLength != -1 ? this.rawLength : data.length;
    // comment out the following lines, see #46490 & getLengthInBytes()
    // do not truncate the characters, 
    // as users may input string longer than max width
//    // truncate if exceeds max width
//    final int maxWidth = dtd.getMaximumWidth();
//    if (strlen > maxWidth) {
//      strlen = maxWidth;
//    }
    int index = offset;
    for (int i = 0; i < strlen; ++i) {
      final int c = data[i];
      if ((c >= 0x0001) && (c <= 0x007F)) {
        bytes[index++] = (byte)(c & 0xFF);
      }
      else if (c > 0x07FF) {
        bytes[index++] = (byte)(0xE0 | ((c >> 12) & 0x0F));
        bytes[index++] = (byte)(0x80 | ((c >> 6) & 0x3F));
        bytes[index++] = (byte)(0x80 | ((c >> 0) & 0x3F));
      }
      else {
        bytes[index++] = (byte)(0xC0 | ((c >> 6) & 0x1F));
        bytes[index++] = (byte)(0x80 | ((c >> 0) & 0x3F));
      }
    }
    return (index - offset);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(final byte[] inBytes, int offset,
      final int columnWidth) {
    final char[] chars = new char[columnWidth];
    final int strlen = readIntoCharsFromByteArray(inBytes, offset, columnWidth,
        chars);
    // if char[] length is much larger than actual length then it is
    // worthwhile to make a copy of required chars only
    if (columnWidth >= (strlen >>> 1)) {
      this.rawData = new char[strlen];
      System.arraycopy(chars, 0, this.rawData, 0, strlen);
    }
    else {
      this.rawData = chars;
    }
    this.rawLength = strlen;
    this.value = null;
    // this.stream = null;
    return columnWidth;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int readBytes(long memOffset, int columnWidth, final ByteSource bs) {
    final char[] chars = new char[columnWidth];
    final int strlen = readIntoCharsFromByteSource(UnsafeHolder.getUnsafe(),
        memOffset, columnWidth, bs, chars);
    // if char[] length is much larger than actual length then it is
    // worthwhile to make a copy of required chars only
    if (columnWidth >= (strlen >>> 1)) {
      this.rawData = new char[strlen];
      System.arraycopy(chars, 0, this.rawData, 0, strlen);
    }
    else {
      this.rawData = chars;
    }
    this.rawLength = strlen;
    this.value = null;
    //this.stream = null;
    return columnWidth;
  }

  @Override
  public int computeHashCode(final int maxWidth, int hash) {
    // never called when value is null
    assert !isNull();

    final char[] data;      
    try {
      data = getCharArray(true);
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception", se);
    }
    int strlen = this.rawLength != -1 ? this.rawLength : data.length;
    // truncate if exceeds max width
    if (maxWidth > 0 && strlen > maxWidth) {
      strlen = maxWidth;
    }
    final int typeId = getTypeFormatId();
    hash = ResolverUtils.getComputeHashOfCharArrayData(hash, strlen, data,
        typeId);
    //add space padding
    for (int i = strlen; i < maxWidth; i++) {
      hash = ResolverUtils.addByteToBucketHash((byte)(0x20 & 0xFF), hash,
          typeId);
    }
    return hash;
  }

  @Override
  public void setRegionContext(LocalRegion region) {
    // initialize the String and raw char array to avoid concurrent get
    // issues since the key will live in the region and will be shared
    try {
      final char[] data = getCharArray(false);
      if (this.rawLength != -1 && this.value == null) {
        this.value = ClientSharedUtils.newWrappedString(data, 0,
            this.rawLength);
      }
    } catch (StandardException se) {
      throw GemFireXDRuntimeException.newRuntimeException(
          "unexpected exception", se);
    }
  }

  @Override
  public boolean canCompareBytesToBytes() {
    return true;
  }

  public static final int readIntoCharsFromByteSource(
      final sun.misc.Unsafe unsafe, long memOffset, final int utflen,
      final ByteSource inBytes, final char[] chars) {
    if (utflen == 0) return 0; // fixes 50281
    int count = 0;
    int strlen = 0;
    final long endAddr = memOffset + utflen;

    // !!ezoerner:20100105 commented out "|| (utflen == 0)" in if
    // condition.
    // If utflen is zero, then
    // columnWidth is zero, and it's just the empty string.
    // this was throwing an ArrayIndexOutOfBoundsException
    // at "str[strlen++] = actualChar;"
    // when columnWidth came in as zero.
    // Furthermore, utflen is set to columnWidth and both of these
    // are never modified, so what is the point of having utflen
    // in the first place?
    while (count < utflen /* || (utflen == 0) */) {
      final int c = unsafe.getByte(memOffset++);

      // UTF encoding shouldn't have any byte as 255 as we see above.
      if ((c & 0xff) == 255) { // read EOF
        break;
      }

      final int char2, char3;
      final char actualChar;
      if ((c & 0x80) == 0x00) {
        // one byte character
        count++;
        actualChar = (char) c;
      } else if ((c & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        count += 2;
        if (SanityManager.DEBUG) {
          if (count > utflen) {
            throw new GemFireXDRuntimeException(
                "UTFDataFormatException. ByteSource = " + inBytes);
          }
        }
        char2 = unsafe.getByte(memOffset++);

        if ((char2 & 0xC0) != 0x80) {
          throw new GemFireXDRuntimeException(
              "UTFDataFormatException. ByteSource = " + inBytes);
        }
        actualChar = (char) (((c & 0x1F) << 6) | (char2 & 0x3F));
      } else if ((c & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        count += 3;
        if (SanityManager.DEBUG) {
          if (count > utflen) {
            throw new GemFireXDRuntimeException(
                "UTFDataFormatException. ByteSource = " + inBytes);
          }
        }
        char2 = unsafe.getByte(memOffset++);
        char3 = unsafe.getByte(memOffset++);
        /*
         * [sumedh] utflen will never be zero if ((c == 0xE0) && (char2 == 0) &&
         * (char3 == 0) && (utflen == 0)) { // we reached the end of a long
         * string, // that was terminated with // (11100000, 00000000, 00000000)
         * break; }
         */
        if (SanityManager.DEBUG) {
          if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80)) {
            throw new GemFireXDRuntimeException(
                "UTFDataFormatException. ByteSource = " + inBytes);
          }
        }
        actualChar = (char) (((c & 0x0F) << 12) | ((char2 & 0x3F) << 6) | (char3 & 0x3F));
      } else {
        throw new GemFireXDRuntimeException(
            "UTFDataFormatException. ByteSource = " + inBytes);
      }
      chars[strlen++] = actualChar;
    }
    assert memOffset == endAddr : "did not read " + (endAddr - memOffset) + " bytes";
    return strlen;
  }

  public static int readIntoCharsFromByteArray(final byte[] inBytes,
      final int offset, final int utflen, final char[] chars) {
    int count = 0;
    int strlen = 0;
    int bi = offset;
    // !!ezoerner:20100105 commented out "|| (utflen == 0)" in if
    // condition.
    // If utflen is zero, then
    // columnWidth is zero, and it's just the empty string.
    // this was throwing an ArrayIndexOutOfBoundsException
    // at "str[strlen++] = actualChar;"
    // when columnWidth came in as zero.
    // Furthermore, utflen is set to columnWidth and both of these
    // are never modified, so what is the point of having utflen
    // in the first place?
    while (count < utflen /*|| (utflen == 0)*/) {
      final int c = inBytes[bi++];
      // UTF encoding shouldn't have any byte as 255 as we see above.
      if ((c & 0xff) == 255) { // read EOF
        break;
      }

      final int char2, char3;
      final char actualChar;
      if ((c & 0x80) == 0x00) {
        // one byte character
        count++;
        actualChar = (char)c;
      }
      else if ((c & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        count += 2;
        if (SanityManager.DEBUG) {
          if (count > utflen) {
            throw new GemFireXDRuntimeException("UTFDataFormatException");
          }
        }
        char2 = inBytes[bi++];
        if ((char2 & 0xC0) != 0x80) {
          throw new GemFireXDRuntimeException("UTFDataFormatException");
        }
        actualChar = (char)(((c & 0x1F) << 6) | (char2 & 0x3F));
      }
      else if ((c & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        count += 3;
        if (SanityManager.DEBUG) {
          if (count > utflen) {
            throw new GemFireXDRuntimeException("UTFDataFormatException");
          }
        }
        char2 = inBytes[bi++];
        char3 = inBytes[bi++];
        /* [sumedh] utflen will never be zero
        if ((c == 0xE0) && (char2 == 0) && (char3 == 0) && (utflen == 0)) {
          // we reached the end of a long string,
          // that was terminated with
          // (11100000, 00000000, 00000000)
          break;
        }
        */
        if (SanityManager.DEBUG) {
          if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80)) {
            throw new GemFireXDRuntimeException("UTFDataFormatException");
          }
        }
        actualChar = (char)(((c & 0x0F) << 12) | ((char2 & 0x3F) << 6)
            | (char3 & 0x3F));
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException");
      }
      chars[strlen++] = actualChar;
    }
    assert (bi - offset) == utflen: "read=" + (bi - offset) + ", expected="
        + utflen;
    return strlen;
  }

  /**
   * Only for tests.
   */
  public static final int readIntoCharsForTesting(final Object inBytes,
      final int offset, final int utflen, final char[] chars) {
    if (inBytes == null || inBytes.getClass() == byte[].class) {
      return readIntoCharsFromByteArray((byte[])inBytes, offset, utflen, chars);
    }
    else {
      final OffHeapByteSource bs = (OffHeapByteSource)inBytes;
      return readIntoCharsFromByteSource(UnsafeHolder.getUnsafe(),
          bs.getUnsafeAddress(offset, utflen), utflen, bs, chars);
    }
  }

  /**
   * Compares string types ignoring case differences without creating any
   * intermediate DVD objects & reads byte[] to the extent required.
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public static final int compareStringIgnoreCase(final byte[] lhs,
      final int lhsOffset, final int lhsColumnWidth, final byte[] rhs,
      final int rhsOffset, final int rhsColumnWidth) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int shorterLen = lhsColumnWidth < rhsColumnWidth ? lhsColumnWidth
        : rhsColumnWidth;
    final int lhsEnd = shorterLen + lhsOffset;
    final int rhsEnd = shorterLen + rhsOffset;

    int ltposn = lhsOffset, rtposn = rhsOffset;

    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final int lb = lhs[ltposn];
      final int rb = rhs[rtposn];
      if ((lb & 0x80) == 0x00) {
        // 1 byte
        if ((rb & 0x80) == 0x00) {
          int res = lb - rb;
          if (res != 0) {
            // compare upper-case
            int lc = Character.toUpperCase(lb);
            int rc = Character.toUpperCase(rb);
            res = lc - rc;
            if (res != 0) {
              return res;
            }
          }
          ltposn++;
          rtposn++;
          continue;
        }
        return -1;
      }
      // left two byte character
      else if ((lb & 0x60) == 0x40) { // we know the top bit is set here
        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          // #42864. handling null written as 2 bytes (-64, -128) in lhs (lb).
          byte byeet = lhs[++ltposn];
          int lc = (((lb & 0x1F) << 6) | (byeet & 0x3F));
          int res = Character.toUpperCase(lc) - Character.toUpperCase(rb);
          // we expect uppercase comparison to also fail for this case
          assert res != 0: "leftChar=" + Character.toString((char)lc)
              + ", rightChar=" + Character.toString((char)rb);
          return res;
        }

        // right is three bytes
        if (((rb & 0x70) == 0x60)) {
          return -1;
        }

        final int l2b = lhs[++ltposn];
        final int lc = (((lb & 0x1F) << 6) | (l2b & 0x3F));
        final int r2b = rhs[++rtposn];
        final int rc = (((rb & 0x1F) << 6) | (r2b & 0x3F));

        int res = lc - rc;
        if (res != 0) {
          // compare upper-case
          res = Character.toUpperCase(lc) - Character.toUpperCase(rc);
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      // left 3 bytes
      else if ((lb & 0x70) == 0x60) { // we know the top bit is set here

        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          return 1;
        }

        // right two bytes
        if ((rb & 0x60) == 0x40) {
          return 1;
        }

        final int l2b = lhs[++ltposn];
        final int l3b = lhs[++ltposn];
        final int lc = (((lb & 0x0F) << 12) | ((l2b & 0x3F) << 6)
            | (l3b & 0x3F));
        final int r2b = rhs[++rtposn];
        final int r3b = rhs[++rtposn];
        final int rc = (((rb & 0x0F) << 12) | ((r2b & 0x3F) << 6)
            | (r3b & 0x3F));

        int res = lc - rc;
        if (res != 0) {
          // compare upper-case
          res = Character.toUpperCase(lc) - Character.toUpperCase(rc);
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + ltposn + " offset=" + lhsOffset + " width=" + lhsColumnWidth
            + " bytes=" + Arrays.toString(lhs));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string.  If the two strings are of equal length, the values are
    ** equal.
    */
    if (lhsColumnWidth == rhsColumnWidth) return 0;

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (lhsColumnWidth > rhsColumnWidth) {
      /*
      ** Remaining characters are on the left.
      */
      final int remainingLen = lhsColumnWidth + lhsOffset;
      return compareTrailingBlanks(lhs, ltposn, remainingLen, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      final int remainingLen = rhsColumnWidth + rhsOffset;
      return compareTrailingBlanks(rhs, rtposn, remainingLen, -1);
    }
  }

  /**
   * Compares string types ignoring case differences without creating any
   * intermediate DVD objects & reads byte[] to the extent required.
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public static final int compareStringIgnoreCase(final UnsafeWrapper unsafe,
      final long lhsAddrOffset, final int lhsColumnWidth,
      final OffHeapByteSource lhs, final long rhsAddrOffset,
      final int rhsColumnWidth, final OffHeapByteSource rhs) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int shorterLen = lhsColumnWidth < rhsColumnWidth ? lhsColumnWidth
        : rhsColumnWidth;
    final long lhsEnd = lhsAddrOffset + shorterLen;
    final long rhsEnd = rhsAddrOffset + shorterLen;

    long ltposn = lhsAddrOffset;
    long rtposn = rhsAddrOffset;

    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final int lb = unsafe.getByte(ltposn);
      final int rb = unsafe.getByte(rtposn);
      if ((lb & 0x80) == 0x00) {
        // 1 byte
        if ((rb & 0x80) == 0x00) {
          int res = lb - rb;
          if (res != 0) {
            // compare upper-case
            int lc = Character.toUpperCase(lb);
            int rc = Character.toUpperCase(rb);
            res = lc - rc;
            if (res != 0) {
              return res;
            }
          }
          ltposn++;
          rtposn++;
          continue;
        }
        return -1;
      }
      // left two byte character
      else if ((lb & 0x60) == 0x40) { // we know the top bit is set here
        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          // #42864. handling null written as 2 bytes (-64, -128) in lhs (lb).
          byte byeet = unsafe.getByte(++ltposn);
          int lc = (((lb & 0x1F) << 6) | (byeet & 0x3F));
          int res = Character.toUpperCase(lc) - Character.toUpperCase(rb);
          // we expect uppercase comparison to also fail for this case
          assert res != 0: "leftChar=" + Character.toString((char)lc)
              + ", rightChar=" + Character.toString((char)rb);
          return res;
        }

        // right is three bytes
        if (((rb & 0x70) == 0x60)) {
          return -1;
        }

        final int l2b = unsafe.getByte(++ltposn);
        final int lc = (((lb & 0x1F) << 6) | (l2b & 0x3F));
        final int r2b = unsafe.getByte(++rtposn);
        final int rc = (((rb & 0x1F) << 6) | (r2b & 0x3F));

        int res = lc - rc;
        if (res != 0) {
          // compare upper-case
          res = Character.toUpperCase(lc) - Character.toUpperCase(rc);
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      // left 3 bytes
      else if ((lb & 0x70) == 0x60) { // we know the top bit is set here

        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          return 1;
        }

        // right two bytes
        if ((rb & 0x60) == 0x40) {
          return 1;
        }

        final int l2b = unsafe.getByte(++ltposn);
        final int l3b = unsafe.getByte(++ltposn);
        final int lc = (((lb & 0x0F) << 12) | ((l2b & 0x3F) << 6)
            | (l3b & 0x3F));
        final int r2b = unsafe.getByte(++rtposn);
        final int r3b = unsafe.getByte(++rtposn);
        final int rc = (((rb & 0x0F) << 12) | ((r2b & 0x3F) << 6)
            | (r3b & 0x3F));

        int res = lc - rc;
        if (res != 0) {
          // compare upper-case
          res = Character.toUpperCase(lc) - Character.toUpperCase(rc);
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + ltposn + " offset="
            + (lhsAddrOffset - lhs.getUnsafeAddress())
            + " width=" + lhsColumnWidth + " bytes="
            + Arrays.toString(lhs.getRowBytes()));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string.  If the two strings are of equal length, the values are
    ** equal.
    */
    if (lhsColumnWidth == rhsColumnWidth) return 0;

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (lhsColumnWidth > rhsColumnWidth) {
      /*
      ** Remaining characters are on the left.
      */
      final long remainingLen = lhsAddrOffset + lhsColumnWidth;
      return compareTrailingBlanks(unsafe, ltposn, remainingLen, 1, lhs);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      final long remainingLen = rhsAddrOffset + rhsColumnWidth;
      return compareTrailingBlanks(unsafe, rtposn, remainingLen, -1, rhs);
    }
  }

  /**
   * Compares string types ignoring case differences without creating any
   * intermediate DVD objects & reads byte[] to the extent required.
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public static final int compareStringIgnoreCase(final UnsafeWrapper unsafe,
      final byte[] lhs, final int lhsOffset, final int lhsColumnWidth,
      final long rhsAddrOffset, final int rhsColumnWidth,
      final OffHeapByteSource rhs) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int shorterLen = lhsColumnWidth < rhsColumnWidth ? lhsColumnWidth
        : rhsColumnWidth;

    final int lhsEnd = shorterLen + lhsOffset;
    int ltposn = lhsOffset;

    long rtposn = rhsAddrOffset;
    final long rhsEnd = rtposn + shorterLen;

    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final int lb = lhs[ltposn];
      final int rb = unsafe.getByte(rtposn);
      if ((lb & 0x80) == 0x00) {
        // 1 byte
        if ((rb & 0x80) == 0x00) {
          int res = lb - rb;
          if (res != 0) {
            // compare upper-case
            int lc = Character.toUpperCase(lb);
            int rc = Character.toUpperCase(rb);
            res = lc - rc;
            if (res != 0) {
              return res;
            }
          }
          ltposn++;
          rtposn++;
          continue;
        }
        return -1;
      }
      // left two byte character
      else if ((lb & 0x60) == 0x40) { // we know the top bit is set here
        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          // #42864. handling null written as 2 bytes (-64, -128) in lhs (lb).
          byte byeet = lhs[++ltposn];
          int lc = (((lb & 0x1F) << 6) | (byeet & 0x3F));
          int res = Character.toUpperCase(lc) - Character.toUpperCase(rb);
          // we expect uppercase comparison to also fail for this case
          assert res != 0: "leftChar=" + Character.toString((char)lc)
              + ", rightChar=" + Character.toString((char)rb);
          return res;
        }

        // right is three bytes
        if (((rb & 0x70) == 0x60)) {
          return -1;
        }

        final int l2b = lhs[++ltposn];
        final int lc = (((lb & 0x1F) << 6) | (l2b & 0x3F));
        final int r2b = unsafe.getByte(++rtposn);
        final int rc = (((rb & 0x1F) << 6) | (r2b & 0x3F));

        int res = lc - rc;
        if (res != 0) {
          // compare upper-case
          res = Character.toUpperCase(lc) - Character.toUpperCase(rc);
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      // left 3 bytes
      else if ((lb & 0x70) == 0x60) { // we know the top bit is set here

        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          return 1;
        }

        // right two bytes
        if ((rb & 0x60) == 0x40) {
          return 1;
        }

        final int l2b = lhs[++ltposn];
        final int l3b = lhs[++ltposn];
        final int lc = (((lb & 0x0F) << 12) | ((l2b & 0x3F) << 6)
            | (l3b & 0x3F));
        final int r2b = unsafe.getByte(++rtposn);
        final int r3b = unsafe.getByte(++rtposn);
        final int rc = (((rb & 0x0F) << 12) | ((r2b & 0x3F) << 6)
            | (r3b & 0x3F));

        int res = lc - rc;
        if (res != 0) {
          // compare upper-case
          res = Character.toUpperCase(lc) - Character.toUpperCase(rc);
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + ltposn + " offset=" + lhsOffset + " width=" + lhsColumnWidth
            + " bytes=" + Arrays.toString(lhs));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string.  If the two strings are of equal length, the values are
    ** equal.
    */
    if (lhsColumnWidth == rhsColumnWidth) return 0;

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (lhsColumnWidth > rhsColumnWidth) {
      /*
      ** Remaining characters are on the left.
      */
      final int remainingLen = lhsColumnWidth + lhsOffset;
      return compareTrailingBlanks(lhs, ltposn, remainingLen, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      final long remainingLen = rhsAddrOffset + rhsColumnWidth;
      return compareTrailingBlanks(unsafe, rtposn, remainingLen, -1, rhs);
    }
  }

  /**
   * Compares string types ignoring case differences without creating any
   * intermediate DVD objects & reads byte[] to the extent required.
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public static final int compareStringIgnoreCase(final String lhs,
      final byte[] rhs, final int rhsOffset, final int rhsColumnWidth) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int lhsEnd = lhs.length();
    final int rhsEnd = rhsOffset + rhsColumnWidth;

    int ltposn = 0, rtposn = rhsOffset;
    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final int lc = lhs.charAt(ltposn);
      int rc = rhs[rtposn];

      // one byte character
      if ((rc & 0x80) == 0x00) {
        if (lc != rc) {
          // compare upper case
          final int res = (Character.toUpperCase(lc) - Character
              .toUpperCase(rc));
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else if ((rc & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        rc = (((rc & 0x1F) << 6) | (rhs[++rtposn] & 0x3F));
        if (lc != rc) {
          // compare upper case
          final int res = (Character.toUpperCase(lc) - Character
              .toUpperCase(rc));
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else if ((rc & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        final int rc2 = rhs[++rtposn];
        final int rc3 = rhs[++rtposn];
        rc = (((rc & 0x0F) << 12) | ((rc2 & 0x3F) << 6) | (rc3 & 0x3F));
        if (lc != rc) {
          // compare upper case
          final int res = (Character.toUpperCase(lc) - Character
              .toUpperCase(rc));
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + rtposn + " offset=" + rhsOffset + " width=" + rhsColumnWidth
            + " bytes=" + Arrays.toString(rhs));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string. If the two strings are of equal length, the values are
    ** equal.
    */
    if (ltposn == lhsEnd && rtposn == rhsEnd) {
      return 0;
    }

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (ltposn < lhsEnd) {
      /*
      ** Remaining characters are on the left.
      */
      return compareTrailingBlanks(lhs, ltposn, lhsEnd, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      return compareTrailingBlanks(rhs, rtposn, rhsEnd, -1);
    }
  }

  /**
   * Compares string types ignoring case differences without creating any
   * intermediate DVD objects & reads byte[] to the extent required.
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public static final int compareStringIgnoreCase(final UnsafeWrapper unsafe,
      final String lhs, final long rhsAddrOffset, final int rhsColumnWidth,
      final OffHeapByteSource rhs) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int lhsEnd = lhs.length();

    long rtposn = rhsAddrOffset;
    final long rhsEnd = rtposn + rhsColumnWidth;

    int ltposn = 0;
    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final int lc = lhs.charAt(ltposn);
      int rc = unsafe.getByte(rtposn);

      // one byte character
      if ((rc & 0x80) == 0x00) {
        if (lc != rc) {
          // compare upper case
          final int res = (Character.toUpperCase(lc) - Character
              .toUpperCase(rc));
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else if ((rc & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        rc = (((rc & 0x1F) << 6) | (unsafe.getByte(++rtposn) & 0x3F));
        if (lc != rc) {
          // compare upper case
          final int res = (Character.toUpperCase(lc) - Character
              .toUpperCase(rc));
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else if ((rc & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        final int rc2 = unsafe.getByte(++rtposn);
        final int rc3 = unsafe.getByte(++rtposn);
        rc = (((rc & 0x0F) << 12) | ((rc2 & 0x3F) << 6) | (rc3 & 0x3F));
        if (lc != rc) {
          // compare upper case
          final int res = (Character.toUpperCase(lc) - Character
              .toUpperCase(rc));
          if (res != 0) {
            return res;
          }
        }
        ltposn++;
        rtposn++;
        continue;
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + rtposn + " offset="
            + (rhsAddrOffset - rhs.getUnsafeAddress())
            + " width=" + rhsColumnWidth + " bytes="
            + Arrays.toString(rhs.getRowBytes()));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string. If the two strings are of equal length, the values are
    ** equal.
    */
    if (ltposn == lhsEnd && rtposn == rhsEnd) {
      return 0;
    }

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (ltposn < lhsEnd) {
      /*
      ** Remaining characters are on the left.
      */
      return compareTrailingBlanks(lhs, ltposn, lhsEnd, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      return compareTrailingBlanks(unsafe, rtposn, rhsEnd, -1, rhs);
    }
  }

  static final int compareTrailingBlanks(final byte[] bytes,
      final int offset, final int endPos, final int retValSign) {

    int posn = offset;
    /* If a remaining character is less than a space, 
     * return 1 (op1 > op2) */
    /* Look at the remaining characters in the longer string */
    while (posn < endPos) {

      /*
      ** Compare the characters to spaces, and return the appropriate
      ** value, depending on which is the longer string.
      */
      char remainingChar;
      int c = bytes[posn];
      if ((c & 0x80) == 0x00) {
        // one byte character
        remainingChar = (char)c;
      }
      else if ((c & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        remainingChar = (char)(((c & 0x1F) << 6) | (bytes[++posn] & 0x3F));
      }
      else if ((c & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        final int char2 = bytes[++posn];
        final int char3 = bytes[++posn];
        remainingChar = (char)(((c & 0x0F) << 12) | ((char2 & 0x3F) << 6)
            | (char3 & 0x3F));
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + posn + " offset=" + offset + " bytes=" + Arrays.toString(bytes));
      }

      if (remainingChar == ' ') {
        posn++;
        continue;
      }
      else {
        return (remainingChar - ' ') * retValSign;
      }
    }
    /* The remaining characters in the longer string were all spaces,
    ** so the strings are equal.
    */
    return 0;
  }

  static final int compareTrailingBlanks(final UnsafeWrapper unsafe,
      final long offset, final long endPos, final int retValSign,
      final OffHeapByteSource bytes) {

    long posn = offset;
    /* If a remaining character is less than a space, 
     * return 1 (op1 > op2) */
    /* Look at the remaining characters in the longer string */
    while (posn < endPos) {

      /*
      ** Compare the characters to spaces, and return the appropriate
      ** value, depending on which is the longer string.
      */
      char remainingChar;
      int c = unsafe.getByte(posn);
      if ((c & 0x80) == 0x00) {
        // one byte character
        remainingChar = (char)c;
      }
      else if ((c & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        remainingChar = (char)(((c & 0x1F) << 6)
            | (unsafe.getByte(++posn) & 0x3F));
      }
      else if ((c & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        final int char2 = unsafe.getByte(++posn);
        final int char3 = unsafe.getByte(++posn);
        remainingChar = (char)(((c & 0x0F) << 12) | ((char2 & 0x3F) << 6)
            | (char3 & 0x3F));
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + posn + " offset=" + offset + " bytes="
            + Arrays.toString(bytes.getRowBytes()));
      }

      if (remainingChar == ' ') {
        posn++;
        continue;
      }
      else {
        return (remainingChar - ' ') * retValSign;
      }
    }
    /* The remaining characters in the longer string were all spaces,
    ** so the strings are equal.
    */
    return 0;
  }

  static final int compareTrailingBlanks(final String s, int posn,
      final int endPos, final int retValSign) {
    char lc;
    while (posn < endPos) {
      lc = s.charAt(posn);
      if (lc == ' ') {
        posn++;
        continue;
      }
      else {
        return (lc - ' ') * retValSign;
      }
    }
    /* The remaining characters in the longer string were all spaces,
    ** so the strings are equal.
    */
    return 0;
  }

  /**
   * compares string types without creating any intermediate DVD objects & reads
   * byte[] to the extent required.
   * 
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public final static int compareString(final byte[] lhs, final int lhsOffset,
      final int lhsColumnWidth, final byte[] rhs, final int rhsOffset,
      final int rhsColumnWidth) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int shorterLen = lhsColumnWidth < rhsColumnWidth ? lhsColumnWidth
        : rhsColumnWidth;
    final int lhsEnd = shorterLen + lhsOffset;
    final int rhsEnd = shorterLen + rhsOffset;

    int ltposn = lhsOffset, rtposn = rhsOffset;

    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final byte lb = lhs[ltposn];
      final byte rb = rhs[rtposn];
      if ((lb & 0x80) == 0x00) {
        // 1 byte
        if ((rb & 0x80) == 0x00) {
          final int res = lb - rb;
          if (res != 0) {
            return res;
          }
          else {
            ltposn++;
            rtposn++;
            continue;
          }
        }

        return -1;
      }
      // left two byte character
      else if ((lb & 0x60) == 0x40) { // we know the top bit is set here
        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          // #42864. handling null written as 2 bytes (-64, -128) in lhs (lb).
          final byte byeet =lhs[++ltposn];
          final int ac = (((lb & 0x1F) << 6) | (byeet & 0x3F));
          return ac - rb;
        }

        // right three bytes
        if (((rb & 0x70) == 0x60)) {
          return -1;
        }

        ltposn++;
        rtposn++;

        final byte l2b = lhs[ltposn];
        final byte r2b = rhs[rtposn];

        int res = l2b - r2b;
        if (res != 0) {
          return res;
        }

        res = lb - rb;
        if (res != 0) {
          return res;
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }

        // actualChar = (char)(((c & 0x1F) << 6) | (char2 & 0x3F));
      }
      // left 3 bytes
      else if ((lb & 0x70) == 0x60) { // we know the top bit is set here
        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          return 1;
        }

        // right two bytes
        if ((rb & 0x60) == 0x40) {
          return 1;
        }

        ltposn += 2;
        rtposn += 2;
        final byte l3b = lhs[ltposn];
        final byte r3b = rhs[rtposn];

        int res = l3b - r3b;
        if (res != 0) {
          return res;
        }

        final byte l2b = lhs[ltposn - 1];
        final byte r2b = rhs[rtposn - 1];
        res = l2b - r2b;
        if (res != 0) {
          return res;
        }

        if (lb != rb) {
          return lb - rb;
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + ltposn + " offset=" + lhsOffset + " width=" + lhsColumnWidth
            + " bytes=" + Arrays.toString(lhs));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string.  If the two strings are of equal length, the values are
    ** equal.
    */
    if (lhsColumnWidth == rhsColumnWidth) {
      return 0;
    }

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (lhsColumnWidth > rhsColumnWidth) {
      /*
      ** Remaining characters are on the left.
      */
      final int remainingLen = lhsColumnWidth + lhsOffset;
      return compareTrailingBlanks(lhs, ltposn, remainingLen, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      final int remainingLen = rhsColumnWidth + rhsOffset;
      return compareTrailingBlanks(rhs, rtposn, remainingLen, -1);
    }
  }

  /**
   * compares string types without creating any intermediate DVD objects & reads
   * byte[] to the extent required.
   * 
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public final static int compareString(final UnsafeWrapper unsafe,
      final long lhsAddrOffset, final int lhsColumnWidth,
      final OffHeapByteSource lhs, final long rhsAddrOffset,
      final int rhsColumnWidth, final OffHeapByteSource rhs) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int shorterLen = lhsColumnWidth < rhsColumnWidth ? lhsColumnWidth
        : rhsColumnWidth;
    final long lhsEnd = lhsAddrOffset + shorterLen;
    final long rhsEnd = rhsAddrOffset + shorterLen;

    long ltposn = lhsAddrOffset;
    long rtposn = rhsAddrOffset;

    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final byte lb = unsafe.getByte(ltposn);
      final byte rb = unsafe.getByte(rtposn);
      if ((lb & 0x80) == 0x00) {
        // 1 byte
        if ((rb & 0x80) == 0x00) {
          final int res = lb - rb;
          if (res != 0) {
            return res;
          }
          else {
            ltposn++;
            rtposn++;
            continue;
          }
        }

        return -1;
      }
      // left two byte character
      else if ((lb & 0x60) == 0x40) { // we know the top bit is set here
        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          // #42864. handling null written as 2 bytes (-64, -128) in lhs (lb).
          final byte byeet = unsafe.getByte(++ltposn);
          final int ac = (((lb & 0x1F) << 6) | (byeet & 0x3F));
          return ac - rb;
        }

        // right three bytes
        if (((rb & 0x70) == 0x60)) {
          return -1;
        }

        ltposn++;
        rtposn++;

        final byte l2b = unsafe.getByte(ltposn);
        final byte r2b = unsafe.getByte(rtposn);

        int res = l2b - r2b;
        if (res != 0) {
          return res;
        }

        res = lb - rb;
        if (res != 0) {
          return res;
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }

        // actualChar = (char)(((c & 0x1F) << 6) | (char2 & 0x3F));
      }
      // left 3 bytes
      else if ((lb & 0x70) == 0x60) { // we know the top bit is set here

        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          return 1;
        }

        // right two bytes
        if ((rb & 0x60) == 0x40) {
          return 1;
        }

        ltposn += 2;
        rtposn += 2;
        final byte l3b = unsafe.getByte(ltposn);
        final byte r3b = unsafe.getByte(rtposn);

        int res = l3b - r3b;
        if (res != 0) {
          return res;
        }

        final byte l2b = unsafe.getByte(ltposn - 1);
        final byte r2b = unsafe.getByte(rtposn - 1);
        res = l2b - r2b;
        if (res != 0) {
          return res;
        }

        if (lb != rb) {
          return lb - rb;
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + ltposn + " offset="
            + (lhsAddrOffset - lhs.getUnsafeAddress())
            + " width=" + lhsColumnWidth + " bytes="
            + Arrays.toString(lhs.getRowBytes()));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string.  If the two strings are of equal length, the values are
    ** equal.
    */
    if (lhsColumnWidth == rhsColumnWidth) {
      return 0;
    }

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (lhsColumnWidth > rhsColumnWidth) {
      /*
      ** Remaining characters are on the left.
      */
      final long remainingLen = lhsAddrOffset + lhsColumnWidth;
      return compareTrailingBlanks(unsafe, ltposn, remainingLen, 1, lhs);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      final long remainingLen = rhsAddrOffset + rhsColumnWidth;
      return compareTrailingBlanks(unsafe, rtposn, remainingLen, -1, rhs);
    }
  }

  /**
   * compares string types without creating any intermediate DVD objects & reads
   * byte[] to the extent required.
   * 
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public final static int compareString(final UnsafeWrapper unsafe,
      final byte[] lhs, final int lhsOffset, final int lhsColumnWidth,
      final long rhsAddrOffset, final int rhsColumnWidth,
      final OffHeapByteSource rhs) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int shorterLen = lhsColumnWidth < rhsColumnWidth ? lhsColumnWidth
        : rhsColumnWidth;
    final int lhsEnd = shorterLen + lhsOffset;
    final long rhsEnd = rhsAddrOffset + shorterLen;

    int ltposn = lhsOffset;
    long rtposn = rhsAddrOffset;

    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final byte lb = lhs[ltposn];
      final byte rb = unsafe.getByte(rtposn);
      if ((lb & 0x80) == 0x00) {
        // 1 byte
        if ((rb & 0x80) == 0x00) {
          final int res = lb - rb;
          if (res != 0) {
            return res;
          }
          else {
            ltposn++;
            rtposn++;
            continue;
          }
        }

        return -1;
      }
      // left two byte character
      else if ((lb & 0x60) == 0x40) { // we know the top bit is set here
        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          // #42864. handling null written as 2 bytes (-64, -128) in lhs (lb).
          final byte byeet = lhs[++ltposn];
          final int ac = (((lb & 0x1F) << 6) | (byeet & 0x3F));
          return ac - rb;
        }

        // right three bytes
        if (((rb & 0x70) == 0x60)) {
          return -1;
        }

        ltposn++;
        rtposn++;

        final byte l2b = lhs[ltposn];
        final byte r2b = unsafe.getByte(rtposn);

        int res = l2b - r2b;
        if (res != 0) {
          return res;
        }

        res = lb - rb;
        if (res != 0) {
          return res;
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
        // actualChar = (char)(((c & 0x1F) << 6) | (char2 & 0x3F));
      }
      // left 3 bytes
      else if ((lb & 0x70) == 0x60) { // we know the top bit is set here

        // right 1 byte
        if ((rb & 0x80) == 0x00) {
          return 1;
        }

        // right two bytes
        if ((rb & 0x60) == 0x40) {
          return 1;
        }

        ltposn += 2;
        rtposn += 2;
        final byte l3b = lhs[ltposn];
        final byte r3b = unsafe.getByte(rtposn);

        int res = l3b - r3b;
        if (res != 0) {
          return res;
        }

        final byte l2b = lhs[ltposn - 1];
        final byte r2b = unsafe.getByte(rtposn - 1);
        res = l2b - r2b;
        if (res != 0) {
          return res;
        }

        if (lb != rb) {
          return lb - rb;
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + ltposn + " offset=" + lhsOffset + " width=" + lhsColumnWidth
            + " bytes=" + Arrays.toString(lhs));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string.  If the two strings are of equal length, the values are
    ** equal.
    */
    if (lhsColumnWidth == rhsColumnWidth) {
      return 0;
    }

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (lhsColumnWidth > rhsColumnWidth) {
      /*
      ** Remaining characters are on the left.
      */
      final int remainingLen = lhsColumnWidth + lhsOffset;
      return compareTrailingBlanks(lhs, ltposn, remainingLen, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      final long remainingLen = rhsAddrOffset + rhsColumnWidth;
      return compareTrailingBlanks(unsafe, rtposn, remainingLen, -1, rhs);
    }
  }

  /**
   * compares string types without creating any intermediate DVD objects & reads
   * byte[] to the extent required.
   * 
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public final static int compareString(final String lhs, final byte[] rhs,
      final int rhsOffset, final int rhsColumnWidth) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int lhsEnd = lhs.length();
    final int rhsEnd = rhsOffset + rhsColumnWidth;

    int ltposn = 0, rtposn = rhsOffset;
    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final int lc = lhs.charAt(ltposn);
      int rc = rhs[rtposn];

      // one byte character
      if ((rc & 0x80) == 0x00) {
        if (lc != rc) {
          return (lc - rc);
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else if ((rc & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        rc = (((rc & 0x1F) << 6) | (rhs[++rtposn] & 0x3F));
        if (lc != rc) {
          return (lc - rc);
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else if ((rc & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        final int rc2 = rhs[++rtposn];
        final int rc3 = rhs[++rtposn];
        rc = (((rc & 0x0F) << 12) | ((rc2 & 0x3F) << 6) | (rc3 & 0x3F));
        if (lc != rc) {
          return (lc - rc);
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + rtposn + " offset=" + rhsOffset + " width=" + rhsColumnWidth
            + " bytes=" + Arrays.toString(rhs));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string. If the two strings are of equal length, the values are
    ** equal.
    */
    if (ltposn == lhsEnd && rtposn == rhsEnd) {
      return 0;
    }

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (ltposn < lhsEnd) {
      /*
      ** Remaining characters are on the left.
      */
      return compareTrailingBlanks(lhs, ltposn, lhsEnd, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      return compareTrailingBlanks(rhs, rtposn, rhsEnd, -1);
    }
  }

  /**
   * compares string types without creating any intermediate DVD objects & reads
   * byte[] to the extent required.
   * 
   * <br>
   * handles null values but not default column values. caller must invoke with
   * appropriate column values extracted as default value bytes.
   */
  public final static int compareString(final UnsafeWrapper unsafe,
      final String lhs, final long rhsAddrOffset, final int rhsColumnWidth,
      final OffHeapByteSource rhs) {
    /*
     ** Compare characters until we find one that isn't equal, or until
     ** one String or the other runs out of characters.
     */
    final int lhsEnd = lhs.length();

    long rtposn = rhsAddrOffset;
    final long rhsEnd = rtposn + rhsColumnWidth;

    int ltposn = 0;
    while (ltposn < lhsEnd && rtposn < rhsEnd) {
      final int lc = lhs.charAt(ltposn);
      int rc = unsafe.getByte(rtposn);

      // one byte character
      if ((rc & 0x80) == 0x00) {
        if (lc != rc) {
          return (lc - rc);
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else if ((rc & 0x60) == 0x40) { // we know the top bit is set here
        // two byte character
        rc = (((rc & 0x1F) << 6) | (unsafe.getByte(++rtposn) & 0x3F));
        if (lc != rc) {
          return (lc - rc);
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else if ((rc & 0x70) == 0x60) { // we know the top bit is set here
        // three byte character
        final int rc2 = unsafe.getByte(++rtposn);
        final int rc3 = unsafe.getByte(++rtposn);
        rc = (((rc & 0x0F) << 12) | ((rc2 & 0x3F) << 6) | (rc3 & 0x3F));
        if (lc != rc) {
          return (lc - rc);
        }
        else {
          ltposn++;
          rtposn++;
          continue;
        }
      }
      else {
        throw new GemFireXDRuntimeException("UTFDataFormatException: posn="
            + rtposn + " offset="
            + (rhsAddrOffset - rhs.getUnsafeAddress(0, 1))
            + " width=" + rhsColumnWidth + " bytes="
            + Arrays.toString(rhs.getRowBytes()));
      }
    } // end of for

    /*
    ** All the characters are equal up to the length of the shorter
    ** string. If the two strings are of equal length, the values are
    ** equal.
    */
    if (ltposn == lhsEnd && rtposn == rhsEnd) {
      return 0;
    }

    /*
    ** One string is shorter than the other.  Compare the remaining
    ** characters in the longer string to spaces (the SQL standard says
    ** that in this case the comparison is as if the shorter string is
    ** padded with blanks to the length of the longer string.
    */
    if (ltposn < lhsEnd) {
      /*
      ** Remaining characters are on the left.
      */
      return compareTrailingBlanks(lhs, ltposn, lhsEnd, 1);
    }
    else {
      /*
      ** Remaining characters are on the right.
      */
      return compareTrailingBlanks(unsafe, rtposn, rhsEnd, -1, rhs);
    }
  }

  static final String getAsString(final byte[] inBytes, final int offset,
      final int columnWidth, final DataTypeDescriptor dtd) {
    final int size = dtd.getMaximumWidth();
    final char[] chars = new char[size];
    int strlen = readIntoCharsFromByteArray(inBytes, offset, columnWidth, chars);
    // TODO: SW: change derby layer that still pads DVDs with blanks in puts
    // pad with blanks if required
    if (size > strlen) {
      appendBlanks(chars, strlen, size - strlen);
    }
    return ClientSharedUtils.newWrappedString(chars, 0, size);
  }

  static final String getAsString(final long memOffset, final int columnWidth,
      final OffHeapByteSource bs, final DataTypeDescriptor dtd) {
    final int size = dtd.getMaximumWidth();
    final char[] chars = new char[size];
    int strlen = readIntoCharsFromByteSource(UnsafeHolder.getUnsafe(),
        memOffset, columnWidth, bs, chars);
    // TODO: SW: change derby layer that still pads DVDs with blanks in puts
    // pad with blanks if required
    if (size > strlen) {
      appendBlanks(chars, strlen, size - strlen);
    }
    return ClientSharedUtils.newWrappedString(chars, 0, size);
  }

  /**
   * lookup path for chars that are non-special ASCII chars
   */
  static final boolean[] IS_NORMAL_ASCII_CHAR = new boolean[128];
  /**
   * lookup path for chars that are either special PXF chars or non-ASCII and
   * gives the number of bytes to write for those
   */
  static final int[] IS_NON_ASCII_OR_SPECIAL_PXF_CHAR = new int[256];

  static {
    // note: the difference in handling of 0x00 byte in writeData vs compare
    // and these methods does not matter; both are valid encodings for 0x00
    for (int c = 0; c < 256; c++) {
      if ((c & 0x80) == 0x00) {
        if ((c != '\\') && (c != RowFormatter.DELIMITER_FOR_PXF) && (c != '\n')
            && (c != '\r')) {
          IS_NORMAL_ASCII_CHAR[c] = true;
          IS_NON_ASCII_OR_SPECIAL_PXF_CHAR[c] = 0;
        }
        else {
          IS_NON_ASCII_OR_SPECIAL_PXF_CHAR[c] = 1;
        }
      }
      else if ((c & 0x60) == 0x40) {
        IS_NON_ASCII_OR_SPECIAL_PXF_CHAR[c] = 2;
      }
      else if ((c & 0x70) == 0x60) {
        IS_NON_ASCII_OR_SPECIAL_PXF_CHAR[c] = 3;
      }
      else {
        // invalid UTF-8 encoding
        IS_NON_ASCII_OR_SPECIAL_PXF_CHAR[c] = -1;
      }
    }
  }

  static final GemFireXDRuntimeException utf8FormatException(byte[] inBytes,
      byte b, int startOffset, int offset, int columnWidth) {
    return new GemFireXDRuntimeException("UTFDataFormatException: offset="
        + startOffset + " currentOffset=" + offset + " b=" + b
        + " columnWidth=" + columnWidth + " bytes=" + Arrays.toString(inBytes));
  }

  static final void writeAsUTF8String(final byte[] inBytes,
      final int startOffset, final int columnWidth, final int maxWidth,
      final ByteArrayDataOutput buffer) {

    if (columnWidth <= 0) {
      return;
    }
    // check for special characters that need to be escaped

    // expand buffer assuming worst case of all special characters
    // using raw byte[] for best performance and avoid checking bounds
    // (or catching ArrayIndexOutOfBounds) so expansion is for worst case
    int bufPos = buffer.ensureCapacity(maxWidth + columnWidth,
        buffer.position());
    final byte[] bufBytes = buffer.getData();

    // Note: below code is very sensitive to changes and we need absolutely
    // the best speed possible here since this byte-by-byte processing has
    // direct relation to PXF TEXT formatter performance which pegs CPU to 100%

    // Changes of apparent no significance have brought perf of this code from
    // ~350MB/s to ~1000MB/s on workstation class CPU (e.g. the while(true)
    // instead of while (offset < endOffset)) so avoid changing this code. If
    // absolutely necessary, then run the RowFormatter.DEBUG_testPXFPerf
    // micro-benchmark to compare the throughput of this method before and after

    final boolean[] isNormal = IS_NORMAL_ASCII_CHAR;

    int offset = startOffset;
    final int endOffset = (startOffset + columnWidth);
    while (true) {
      final byte b = inBytes[offset];
      if (b >= 0 && isNormal[b]) {
        bufBytes[bufPos] = b;
        if (++offset < endOffset) {
          ++bufPos;
          continue;
        }
        else {
          break;
        }
      }

      final int v = IS_NON_ASCII_OR_SPECIAL_PXF_CHAR[b & 0xFF];
      if (v == 1) {
        bufBytes[bufPos] = '\\';
        if (b == '\n') {
          bufBytes[++bufPos] = 'n';
        }
        else {
          bufBytes[++bufPos] = b;
        }
      }
      else if (v == 2) {
        // 2 byte case
        bufBytes[bufPos] = b;
        bufBytes[++bufPos] = inBytes[++offset];
      }
      else if (v == 3) {
        // 3 byte case
        bufBytes[bufPos] = b;
        bufBytes[++bufPos] = inBytes[++offset];
        bufBytes[++bufPos] = inBytes[++offset];
      }
      else {
        throw utf8FormatException(inBytes, b, startOffset, offset, columnWidth);
      }
      if (++offset < endOffset) {
        ++bufPos;
        continue;
      }
      else {
        break;
      }
    }
    buffer.advance(bufPos - buffer.position());
  }

  static final void writeAsUTF8String(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final int maxWidth,
      final OffHeapByteSource bs, final ByteArrayDataOutput buffer) {

    if (columnWidth <= 0) {
      return;
    }
    // check for special characters that need to be escaped

    // expand buffer assuming no special characters
    buffer.ensureCapacity(maxWidth, buffer.position());

    final boolean[] isNormal = IS_NORMAL_ASCII_CHAR;

    long bsOffset = memOffset;
    final long bsEndOffset = (memOffset + columnWidth);
    while (true) {
      final byte b = unsafe.getByte(bsOffset);
      if (b >= 0 && isNormal[b]) {
        buffer.write(b);
        if (++bsOffset < bsEndOffset) {
          continue;
        }
        else {
          break;
        }
      }

      final int v = IS_NON_ASCII_OR_SPECIAL_PXF_CHAR[b & 0xFF];
      if (v == 1) {
        buffer.write('\\');
        if (b == '\n') {
          buffer.write('n');
        }
        else {
          buffer.write(b);
        }
      }
      else if (v == 2) {
        // 2 byte case
        buffer.write(b);
        buffer.write(unsafe.getByte(++bsOffset));
      }
      else if (v == 3) {
        // 3 byte case
        buffer.write(b);
        buffer.write(unsafe.getByte(++bsOffset));
        buffer.write(unsafe.getByte(++bsOffset));
      }
      else {
        final long bsAddr = bs.getUnsafeAddress();
        throw utf8FormatException(bs.getRowBytes(), b,
            (int)(memOffset - bsAddr), (int)(bsOffset - bsAddr), columnWidth);
      }
      if (++bsOffset < bsEndOffset) {
        continue;
      }
      else {
        break;
      }
    }
  }

  static void writeAsUTF8String(final byte[] inBytes, final int startOffset,
      final int columnWidth, final DataTypeDescriptor dtd,
      final ByteArrayDataOutput buffer) {
    int maxWidth = dtd.getMaximumWidth();
    if (maxWidth < columnWidth) {
      maxWidth = columnWidth;
    }

    writeAsUTF8String(inBytes, startOffset, columnWidth, maxWidth, buffer);

    if (maxWidth > columnWidth) {
      appendBlanks(buffer, maxWidth - columnWidth);
    }
  }

  static void writeAsUTF8String(final UnsafeWrapper unsafe,
      final long memOffset, final int columnWidth, final OffHeapByteSource bs,
      final DataTypeDescriptor dtd, final ByteArrayDataOutput buffer) {
    int maxWidth = dtd.getMaximumWidth();
    if (maxWidth < columnWidth) {
      maxWidth = columnWidth;
    }

    writeAsUTF8String(unsafe, memOffset, columnWidth, maxWidth, bs, buffer);

    if (maxWidth > columnWidth) {
      appendBlanks(buffer, maxWidth - columnWidth);
    }
  }

  @Override
  public byte getTypeId() {
    return DSCODE.CHAR_ARRAY;
  }
// GemStone changes END
}
