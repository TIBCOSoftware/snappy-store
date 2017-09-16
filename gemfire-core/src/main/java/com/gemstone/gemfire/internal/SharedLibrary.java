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

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gnu.trove.THashSet;
import com.gemstone.gemfire.pdx.internal.unsafe.UnsafeWrapper;

import java.io.File;
import java.net.URL;
import java.net.URLDecoder;

/**
 * The class puts in one place the code that will determine the
 * name of the GemFire shared library.
 * This aids debugging.
 */
public class SharedLibrary {

  /** A suffix added on to distinguish between the Linux and Solaris library 
   *  names since they reside in the same directory. 
   */
  private final static String SOLARIS_LIBRARY_SUFFIX = "_sol";   
  /**
   * System property to set to true enable debug information regarding native library loading.
   * @since 5.1
   */
  public final static String LOADLIBRARY_DEBUG_PROPERTY = "gemfire.loadLibrary.debug";
  
  public final static String DISABLE_NATIVE_LIBRARY = "gemfire.disable.nativelibrary";
  
  public static final String USE_DEBUG_VERSION = "gemfire.nativelibrary.usedebugversion";

  public static final boolean debug = Boolean.getBoolean(LOADLIBRARY_DEBUG_PROPERTY);
  
  public static final boolean disable = Boolean.getBoolean(DISABLE_NATIVE_LIBRARY);
  
  public static final boolean useDebugVersion = Boolean.getBoolean(USE_DEBUG_VERSION);
  
  public static final THashSet loadedLibraries = new THashSet();

  private static final boolean is64Bit;
  private static final int referenceSize;
  private static final int objectHeaderSize;
  
  private static final UnsafeWrapper unsafe;
  static {
    UnsafeWrapper tmp = null;
    try {
      tmp = new UnsafeWrapper();
    } catch (RuntimeException ignore) {
    } catch (Error ignore) {
    }
    unsafe = tmp;
  }

  static {
    int sunbits = Integer.getInteger("sun.arch.data.model", 0).intValue(); // also used by JRockit
    if (sunbits == 64) {
      is64Bit = true;
    } else if (sunbits == 32) {
      is64Bit = false;
    } else {
      int ibmbits = Integer.getInteger("com.ibm.vm.bitmode", 0).intValue();
      if (ibmbits == 64) {
        is64Bit = true;
      } else if (ibmbits == 32) {
        is64Bit = false;
      } else {
        if (unsafe != null) {
          is64Bit = (new UnsafeWrapper()).getAddressSize() == 8;
        } else {
          is64Bit = false;
        }
      }
    }
    if (!is64Bit) {
      referenceSize = 4;
      objectHeaderSize = 8;
    } else {
      int scaleIndex = 0;
      int tmpReferenceSize = 0;
      int tmpObjectHeaderSize = 0;
      if (unsafe != null) {
        // Use unsafe to figure out the size of an object reference since we might
        // be using compressed oops.
        scaleIndex = unsafe.arrayScaleIndex(Object[].class);
        if (scaleIndex == 4) {
          // compressed oops
          tmpReferenceSize = 4;
          tmpObjectHeaderSize = 12;
        } else if (scaleIndex == 8) {
          tmpReferenceSize = 8;
          tmpObjectHeaderSize = 16;
        } else {
          System.out.println("Unexpected arrayScaleIndex " + scaleIndex + ". Using max heap size to estimate reference size.");
          scaleIndex = 0;
        }
      }
      if (scaleIndex == 0) {
        // If our heap is > 32G then assume large oops. Otherwise assume compressed oops.
        if (Runtime.getRuntime().maxMemory() > (32L*1024*1024*1024)) {
          tmpReferenceSize = 8;
          tmpObjectHeaderSize = 16;
        } else {
          tmpReferenceSize = 4;
          tmpObjectHeaderSize = 12;
        }
      }
      referenceSize = tmpReferenceSize;
      objectHeaderSize = tmpObjectHeaderSize;
    }
  }
  /**
   * @return true if this process is 64bit
   * @throws RuntimeException if sun.arch.data.model doesn't fit expectations
   */
  public static boolean is64Bit() {
    return is64Bit;
  }

  /**
   * @return true if this process is running on Solaris, no effort is made to 
   * distinguish between sparc and x86, the library will simply fail to load.
   * @throws RuntimeException if sun.arch.data.model doesn't fit expectations
   */
  public static boolean isSolaris() {
    String osName = System.getProperty("os.name");
    return osName.equals("SunOS");
  }
    /**
     * Returns the os specific name of the GemFire shared library.
     * @param baseName TODO
     */
    public static String getName(String baseName) {
      StringBuffer result = new StringBuffer(baseName);
      if (isSolaris()) {
        result.append(SOLARIS_LIBRARY_SUFFIX);
      }
      if (is64Bit()) {
        result.append("64");
      }
      if (useDebugVersion) {
        result.append("_g");
      }
      return result.toString();
    }

    public static boolean loadLibrary(final String library) {
      File libraryPath = null ;
      try {
        URL gemfireJarURL = GemFireVersion.getJarURL();

        if (gemfireJarURL == null) {
          throw new InternalGemFireError("Unable to locate jar file.");
        }       

        String gemfireJar = null;
        try {
          gemfireJar = URLDecoder.decode(gemfireJarURL.getFile(), "UTF-8");
        } catch ( java.io.UnsupportedEncodingException uee ) {
          //This should never happen because UTF-8 is required to be implemented
          throw new RuntimeException(uee);
        }
        int index = gemfireJar.lastIndexOf("/");
        if ( index == -1 ) {
          throw new InternalGemFireError("Unable to parse gemfire.jar path.");
        }
        String libDir = gemfireJar.substring(0, index+1);
        libraryPath = new File(libDir, System.mapLibraryName(library));
        if (libraryPath.exists()) {
          System.load(libraryPath.getPath());
          if (debug) {
            String msg = "Successfully loaded libraryPath " + libraryPath;
            logInitMessage(LogWriterImpl.INFO_LEVEL, msg,  null);
          }
          return true;
        }

        System.loadLibrary(library);

        if (debug) {
          String msg = "Successfully loaded library " + library;
          logInitMessage(LogWriterImpl.INFO_LEVEL, msg, null);
        }
        return true;
        
      } catch (InternalGemFireError ige) {
        /** Unable to make a guess as to where the gemfire native library 
            is based on its position relative to gemfire.jar. */
        final String errMsg = "Problem loading library from URL path: "
            + (libraryPath != null ? libraryPath : library) + ": " + ige;
        if (debug) {
          System.err.println(errMsg);
          ige.printStackTrace(System.err);
        }
        logInitMessage(LogWriterImpl.WARNING_LEVEL, errMsg, ige);
      } catch (UnsatisfiedLinkError ule) {
        /** Unable to load the gemfire native library in the product tree,
            This is very unexpected and should not happen.
            Reattempting using System.loadLibrary */
        final String errMsg = "Problem loading library from URL path: "
            + (libraryPath != null ? libraryPath : library) + ": " + ule;
        if (debug) {
          System.err.println(errMsg);
          ule.printStackTrace(System.err);
        }
        logInitMessage(LogWriterImpl.WARNING_LEVEL, errMsg, null);
      }
      
      return false;
    }
    /**
     * Returns the size in bytes of a C pointer in this
     * shared library,  returns 4 for a 32 bit shared library,
     * and 8 for a 64 bit shared library . 
     * This method makes a native call, so you can't use it to
     * determine which library to load .
     * 
     */
    public static int pointerSizeBytes() { return SmHelper.pointerSizeBytes();}

    /**
     * Accessor method for the is64Bit flag
     * @return returns a boolean indicating if the 64bit native library was loaded.
     * @since 5.1
     */
    public final static boolean getIs64Bit() { return PureJavaMode.is64Bit(); }
    
    public final static synchronized boolean register(final String libName) {
      
        if (disable) {
          return false;
        }
      
        if (loadedLibraries.contains(libName)) {
          return true;
        }
      
        boolean retVal = loadLibrary(getName(libName));
        
        if (retVal) {
          loadedLibraries.add(libName);
        }
        return retVal;
    }

    public static void logInitMessage(int logLevel, String msg, Throwable t) {
      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      final GemFireCacheImpl.StaticSystemCallbacks sysCb;
      final LogWriter logger = cache != null ? cache.getLogger() : null;

      if (logger != null) {
        logger.info(msg, t);
      } else if (cache != null
          && (sysCb = GemFireCacheImpl.getInternalProductCallbacks()) != null) {
        sysCb.log(LogWriterImpl.levelToString(logLevel), msg, t);
      } else if (logLevel >= LogWriterImpl.WARNING_LEVEL) {
        System.out.println("NanoTimer::" + msg);
        if (t != null) {
          t.printStackTrace();
        }
      }
    }

    public static int getReferenceSize() {
      return referenceSize;
    }

    public static int getObjectHeaderSize() {
      return objectHeaderSize;
    }
}
