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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;

/**
 * The delegating <tt>ClassLoader</tt> used by GemFire to load classes and other resources. This <tt>ClassLoader</tt>
 * delegates to any <tt>ClassLoader</tt>s added to the list of custom class loaders, thread context <tt>ClassLoader</tt>
 * s unless they have been excluded}, the <tt>ClassLoader</tt> which loaded the GemFire classes, and finally the system
 * <tt>ClassLoader</tt>.
 * <p>
 * The thread context class loaders can be excluded by setting the system property
 * <tt>gemfire.excludeThreadContextClassLoader</tt>:
 * <ul>
 * <li><tt>-Dgemfire.excludeThreadContextClassLoader=true</tt>
 * <li><tt>System.setProperty("gemfire.excludeThreadContextClassLoader", "true");
 * </tt>
 * </ul>
 * <p>
 * Class loading and resource loading order:
 * <ul>
 * <li>1. Any custom loaders in the order they were added
 * <li>2. <tt>Thread.currentThread().getContextClassLoader()</tt> unless excludeTCCL == true
 * <li>3. <tt>ClassPathLoader.class.getClassLoader()</tt>
 * <li>4. <tt>ClassLoader.getSystemClassLoader()</tt> If the attempt to acquire any of the above class loaders results
 * in either a {@link java.lang.SecurityException SecurityException} or a null, then that class loader is quietly
 * skipped. Duplicate class loaders will be skipped.
 * 
 * @author Kirk Lund
 * @since 6.5.1.4
 */
public final class ClassPathLoader {
  public static final String ENABLE_TRACE_PROPERTY = "gemfire.ClassPathLoader.enableTrace";
  public static final String ENABLE_TRACE_DEFAULT_VALUE = "false";
  private final boolean ENABLE_TRACE = false;

  public static final String EXCLUDE_TCCL_PROPERTY = "gemfire.excludeThreadContextClassLoader";
  public static final boolean EXCLUDE_TCCL_DEFAULT_VALUE = false;
  private final boolean excludeTCCL;
  
  // This calculates the location of the extlib directory relative to the
  // location of the gemfire.jar file.  If for some reason the ClassPathLoader
  // class is found in a directory instead of a JAR file (as when testing),
  // then it will be relative to the location of the root of the package and
  // class.
  public static final File EXT_LIB_DIR = new File((new File(ClassPathLoader.class.getProtectionDomain().getCodeSource()
      .getLocation().getPath())).getParent(), "ext");

  // This token is placed into the list of class loaders to determine where
  // to insert the TCCL when in forName(...), getResource(...), etc.
  private static final ClassLoader TCCL_PLACEHOLDER = new ClassLoader() { // This is never used for class loading
  };

  private static final AtomicReference<ClassPathLoader> latest = new AtomicReference<ClassPathLoader>();

  private final List<ClassLoader> classLoaders;
  private final ClassLoaderInterface customLoaderIF;

  private static final Set<ClassLoader> defaultLoaders;
  static {
    defaultLoaders = new HashSet<ClassLoader>();
    try {
      ClassLoader classLoader = ClassPathLoader.class.getClassLoader();
      if (classLoader != null) {
        defaultLoaders.add(classLoader);
      }
    } catch (SecurityException sex) {
      // Nothing to do, just don't add it
    }

    try {
      ClassLoader classLoader = ClassLoader.getSystemClassLoader();
      if (classLoader != null) {
        defaultLoaders.add(classLoader);
      }
    } catch (SecurityException sex) {
      // Nothing to do, just don't add it
    }

    setLatestToDefault();
  }
  
  /**
   * Starting at the files or directories identified by 'files', search for valid
   * JAR files and return a list of their URLs.  Sub-directories will also be
   * searched.
   * 
   * @param files Files or directories to search for valid JAR content.
   * 
   * @return A list of URLs for all JAR files found.
   */
  private static List<URL> getJarURLsFromFiles(final File... files) {
    final List<URL> urls = new ArrayList<URL>();

    Assert.assertTrue(files != null, "file list cannot be null");

    for (File file : files) {
      if (file.exists()) {
        if (file.isDirectory()) {
          urls.addAll(getJarURLsFromFiles(file.listFiles()));
        } else {
          if (!JarClassLoader.hasValidJarContent(file)) {
            warning("Invalid JAR content when attempting to create ClassLoader for file: " + file.getAbsolutePath());
            continue;
          }

          try {
            urls.add(file.toURI().toURL());
          } catch (MalformedURLException muex) {
            warning("Encountered invalid URL when attempting to create ClassLoader for file: " + file.getAbsolutePath() + ":"
                + muex.getMessage());
            continue;
          }
        }
      }
    }

    return urls;
  }

  private ClassPathLoader(final List<ClassLoader> classLoaders,
      ClassLoaderInterface customLoaderIF, final boolean excludeTCCL) {

    Assert.assertTrue(classLoaders != null, "custom loaders must not be null");
    for (ClassLoader classLoader : classLoaders) {
      Assert.assertTrue(classLoader != null, "null classloaders not allowed");
    }

    this.classLoaders = new ArrayList<ClassLoader>(classLoaders);
    this.customLoaderIF = customLoaderIF;
    this.excludeTCCL = excludeTCCL;
  }

  /**
   * Get a copy of the collection of ClassLoaders currently in use.
   * 
   * @return Collection of ClassLoaders currently in use.
   */
  public Collection<ClassLoader> getClassLoaders() {
    List<ClassLoader> classLoadersCopy = new ArrayList<ClassLoader>(this.classLoaders);

    for (int i = 0; i < classLoadersCopy.size(); i++) {
      if (classLoadersCopy.get(i).equals(TCCL_PLACEHOLDER)) {
        if (excludeTCCL) {
          classLoadersCopy.remove(i);
        } else {
          classLoadersCopy.set(i, Thread.currentThread().getContextClassLoader());
        }
        break;
      }
    }

    return classLoadersCopy;
  }

  // This is exposed for testing.
  static ClassPathLoader createWithDefaults(final boolean excludeTCCL) {
    List<ClassLoader> classLoaders = new LinkedList<ClassLoader>();

    classLoaders.add(TCCL_PLACEHOLDER);

    for (final ClassLoader classLoader : defaultLoaders) {
      classLoaders.add(classLoader);
    }
    
    // Add user JAR files from the EXT_LIB_DIR directory using a single ClassLoader
    try {
      if (EXT_LIB_DIR.exists()) {
        if (!EXT_LIB_DIR.isDirectory() || !EXT_LIB_DIR.canRead()) {
          warning("Cannot read from directory when attempting to load JAR files: " + EXT_LIB_DIR.getAbsolutePath());
        } else {
          List<URL> extLibJarURLs = getJarURLsFromFiles(EXT_LIB_DIR);
          ClassLoader classLoader = new URLClassLoader(extLibJarURLs.toArray(new URL[extLibJarURLs.size()]));
          classLoaders.add(classLoader);
        }
      }
    } catch (SecurityException sex) {
      // Nothing to do, just don't add it
    }

    return new ClassPathLoader(classLoaders, null, excludeTCCL);
  }

  public static ClassPathLoader setLatestToDefault() {
    return setLatestToDefault(Boolean.getBoolean(EXCLUDE_TCCL_PROPERTY));
  }

  public static ClassPathLoader setLatestToDefault(final boolean excludeTCCL) {
    ClassPathLoader classPathLoader = createWithDefaults(excludeTCCL);

    // Clean up JarClassLoaders that attached to the previous ClassPathLoader
    ClassPathLoader oldClassPathLoader = latest.getAndSet(classPathLoader);
    if (oldClassPathLoader != null) {
      for (ClassLoader classLoader : oldClassPathLoader.classLoaders) {
        if (classLoader instanceof JarClassLoader) {
          ((JarClassLoader) classLoader).cleanUp();
        }
      }
    }

    return classPathLoader;
  }

  /**
   * Used by GemFireXD to plugin its own class loading mechanism for deployed jars
   * in database and otherwise.
   */
  public static ClassPathLoader setLatestToDefaultWithCustomLoader(
      final boolean excludeTCCL, ClassLoaderInterface customLoader) {
    List<ClassLoader> classLoaders = new LinkedList<ClassLoader>();

    classLoaders.add(TCCL_PLACEHOLDER);

    for (final ClassLoader classLoader : defaultLoaders) {
      classLoaders.add(classLoader);
    }

    ClassPathLoader classPathLoader = new ClassPathLoader(classLoaders,
        customLoader, excludeTCCL);

    // Clean up JarClassLoaders that attached to the previous ClassPathLoader
    ClassPathLoader oldClassPathLoader = latest.getAndSet(classPathLoader);
    if (oldClassPathLoader != null) {
      for (ClassLoader classLoader : oldClassPathLoader.classLoaders) {
        if (classLoader instanceof JarClassLoader) {
          ((JarClassLoader)classLoader).cleanUp();
        }
      }
    }

    return classPathLoader;
  }

  // This is exposed for testing.
  ClassPathLoader addOrReplace(final ClassLoader classLoader) {
    if (ENABLE_TRACE) {
      trace("adding classLoader: " + classLoader);
    }

    List<ClassLoader> classLoadersCopy = new ArrayList<ClassLoader>(this.classLoaders);
    classLoadersCopy.add(0, classLoader);

    // Ensure there is only one instance of this class loader in the list
    ClassLoader removingClassLoader = null;
    int index = classLoadersCopy.lastIndexOf(classLoader);
    if (index != 0) {
      removingClassLoader = classLoadersCopy.get(index);
      if (ENABLE_TRACE) {
        trace("removing previous classLoader: " + removingClassLoader);
      }
      classLoadersCopy.remove(index);
    }

    if (removingClassLoader != null && removingClassLoader instanceof JarClassLoader) {
      ((JarClassLoader) removingClassLoader).cleanUp();
    }

    return new ClassPathLoader(classLoadersCopy, null, this.excludeTCCL);
  }

  /**
   * Add or replace the provided {@link ClassLoader} to the list held by this ClassPathLoader. Then use the resulting
   * list to create a new ClassPathLoader and set it as the latest.
   * 
   * @param classLoader
   *          {@link ClassLoader} to add
   */
  public ClassPathLoader addOrReplaceAndSetLatest(final ClassLoader classLoader) {
    ClassPathLoader classPathLoader = addOrReplace(classLoader);
    latest.set(classPathLoader);
    return classPathLoader;
  }

  // This is exposed for testing.
  ClassPathLoader remove(final ClassLoader classLoader) {
    if (ENABLE_TRACE) {
      trace("removing classLoader: " + classLoader);
    }

    List<ClassLoader> classLoadersCopy = new ArrayList<ClassLoader>();
    classLoadersCopy.addAll(this.classLoaders);

    if (!classLoadersCopy.contains(classLoader)) {
      if (ENABLE_TRACE) {
        trace("cannot remove classLoader since it doesn't exist: " + classLoader);
      }
      return this;
    }

    classLoadersCopy.remove(classLoader);

    if (classLoader instanceof JarClassLoader) {
      ((JarClassLoader) classLoader).cleanUp();
    }

    return new ClassPathLoader(classLoadersCopy, null, this.excludeTCCL);
  }

  /**
   * Remove the provided {@link ClassLoader} from the list held by this ClassPathLoader. Then use the resulting list to
   * create a new ClassPathLoader and set it as the latest. Silently ignores requests to remove non-existent
   * ClassLoaders.
   * 
   * @param classLoader
   *          {@link ClassLoader} to remove
   */
  public ClassPathLoader removeAndSetLatest(final ClassLoader classLoader) {
    ClassPathLoader classPathLoader = remove(classLoader);
    latest.set(classPathLoader);
    return classPathLoader;
  }

  public URL getResource(final String name) {
    if (ENABLE_TRACE) {
      trace(new StringBuilder("getResource(").append(name).append(")"));
    }
    URL url = null;
    ClassLoader tccl = null;
    if (!excludeTCCL) {
      tccl = Thread.currentThread().getContextClassLoader();
    }

    for (ClassLoader classLoader : this.classLoaders) {
      if (classLoader == TCCL_PLACEHOLDER) {
        try {
          if (tccl != null) {
            if (ENABLE_TRACE) {
              trace(new StringBuilder("getResource trying TCCL: ").append(tccl));
            }
            url = tccl.getResource(name);
            if (url != null) {
              if (ENABLE_TRACE) {
                trace("getResource found by TCCL");
              }
              return url;
            }
          } else {
            if (ENABLE_TRACE) {
              trace(new StringBuilder("getResource skipping TCCL because it's null"));
            }
          }
        } catch (SecurityException sex) {
          // Continue to next ClassLoader
        }
      } else if (excludeTCCL || !classLoader.equals(tccl)) {
        if (ENABLE_TRACE) {
          trace(new StringBuilder("getResource trying classLoader: ").append(classLoader));
        }
        url = classLoader.getResource(name);
        if (url != null) {
          if (ENABLE_TRACE) {
            trace(new StringBuilder("getResource found by classLoader: ").append(classLoader));
          }
          return url;
        }
      }
    }

    if (ENABLE_TRACE) {
      trace(new StringBuilder("getResource returning null"));
    }
    return url;
  }

  public Class<?> forName(final String name) throws ClassNotFoundException {
    if (ENABLE_TRACE) {
      trace(new StringBuilder("forName(").append(name).append(")"));
    }
    Class<?> clazz = null;
    ClassLoader tccl = null;
    if (!excludeTCCL) {
      tccl = Thread.currentThread().getContextClassLoader();
    }

    if (this.customLoaderIF != null) {
      if (ENABLE_TRACE) {
        trace(new StringBuilder("forName for class " + name
            + " trying customLoaderIF: ").append(this.customLoaderIF));
      }
      try {
        clazz = this.customLoaderIF.loadApplicationClass(name);
        if (clazz != null) {
          if (ENABLE_TRACE) {
            trace(new StringBuilder("forName for class " + name
                + " found by customLoaderIF: ").append(this.customLoaderIF));
          }
          return clazz;
        }
      } catch (ClassNotFoundException cnfe) {
        // continue to the next classloader
      }
    }

    for (ClassLoader classLoader : this.classLoaders) {
      try {
        if (classLoader == TCCL_PLACEHOLDER) {
          if (tccl != null) {
            if (ENABLE_TRACE) {
              trace(new StringBuilder("forName trying TCCL: ").append(tccl));
            }
            clazz = Class.forName(name, true, tccl);
            if (clazz != null) {
              if (ENABLE_TRACE) {
                trace("forName found by TCCL");
              }
              return clazz;
            } else {
              if (ENABLE_TRACE) {
                trace(new StringBuilder("forName skipping TCCL because it's null"));
              }
            }
          }
        } else if (excludeTCCL || !classLoader.equals(tccl)) {
          if (ENABLE_TRACE) {
            trace(new StringBuilder("forName trying classLoader: ").append(classLoader));
          }
          clazz = Class.forName(name, true, classLoader);
          if (clazz != null) {
            if (ENABLE_TRACE) {
              trace(new StringBuilder("forName found by classLoader: ").append(classLoader));
            }
            return clazz;
          }
        }
      } catch (SecurityException sex) {
        // Continue to next ClassLoader
      } catch (ClassNotFoundException cnfex) {
        // Continue to next ClassLoader
      }
    }

    if (ENABLE_TRACE) {
      trace(new StringBuilder("forName throwing ClassNotFoundException"));
    }
    throw new ClassNotFoundException(name);
  }

  /**
   * See {@link Proxy#getProxyClass(ClassLoader, Class...)}
   */
  public Class getProxyClass(final Class[] classObjs) {
    IllegalArgumentException ex = null;
    ClassLoader tccl = null;
    if (!excludeTCCL) {
      tccl = Thread.currentThread().getContextClassLoader();
    }

    for (ClassLoader classLoader : this.classLoaders) {
      try {
        if (classLoader == TCCL_PLACEHOLDER) {
          if (tccl != null) {
            return Proxy.getProxyClass(tccl, classObjs);
          }
        } else if (excludeTCCL || !classLoader.equals(tccl)) {
          return Proxy.getProxyClass(classLoader, classObjs);
        }
      } catch (SecurityException sex) {
        // Continue to next classloader
      } catch (IllegalArgumentException iaex) {
        ex = iaex;
        // Continue to next classloader
      }
    }

    assert ex != null;
    if (ex != null) {
      throw ex;
    }
    return null;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(getClass().getName());
    sb.append("@").append(System.identityHashCode(this)).append("{");
    sb.append("isLatest=").append(getLatest() == this);
    sb.append(", excludeTCCL=").append(this.excludeTCCL);
    if (this.customLoaderIF != null) {
      sb.append(", customLoaderIF=").append(this.customLoaderIF);
    }
    sb.append(", classLoaders=[");
    for (int i = 0; i < this.classLoaders.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(this.classLoaders.get(i).toString());
    }
    sb.append("]");
    if (!this.excludeTCCL) {
      sb.append(", TCCL=").append(Thread.currentThread().getContextClassLoader());
    }
    sb.append("]}");
    return sb.toString();
  }

  /**
   * Finds the resource with the given name. This method will first search the class loader of the context class for the
   * resource. That failing, this method will invoke {@link #getResource(String)} to find the resource.
   * 
   * @param contextClass
   *          The class whose class loader will first be searched
   * @param name
   *          The resource name
   * @return A <tt>URL</tt> object for reading the resource, or <tt>null</tt> if the resource could not be found or the
   *         invoker doesn't have adequate privileges to get the resource.
   */
  public URL getResource(final Class<?> contextClass, final String name) {
    if (contextClass != null) {
      URL url = contextClass.getResource(name);
      if (url != null) {
        return url;
      }
    }
    return getResource(name);
  }

  /**
   * Returns an input stream for reading the specified resource.
   * 
   * <p>
   * The search order is described in the documentation for {@link #getResource(String)}.
   * </p>
   * 
   * @param name
   *          The resource name
   * 
   * @return An input stream for reading the resource, or <tt>null</tt> if the resource could not be found
   */
  public InputStream getResourceAsStream(final String name) {
    URL url = getResource(name);
    try {
      return url != null ? url.openStream() : null;
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Returns an input stream for reading the specified resource.
   * <p>
   * The search order is described in the documentation for {@link #getResource(Class, String)}.
   * 
   * @param contextClass
   *          The class whose class loader will first be searched
   * @param name
   *          The resource name
   * @return An input stream for reading the resource, or <tt>null</tt> if the resource could not be found
   */
  public InputStream getResourceAsStream(final Class<?> contextClass, final String name) {
    if (contextClass != null) {
      InputStream is = contextClass.getResourceAsStream(name);
      if (is != null) {
        return is;
      }
    }
    return getResourceAsStream(name);
  }

  /**
   * Finds all the resources with the given name. This method will first search
   * the class loader of the context class for the resource. That failing, this
   * method will invoke {@link #getResources(String)} to find the resource.
   * 
   * @param  contextClass
   *         The class whose class loader will first be searched
   *         
   * @param  name
   *         The resource name
   *
   * @return  An enumeration of {@link java.net.URL <tt>URL</tt>} objects for
   *          the resource.  If no resources could  be found, the enumeration
   *          will be empty.  Resources that the class loader doesn't have
   *          access to will not be in the enumeration.
   *
   * @throws  IOException
   *          If I/O errors occur
   */
  public Enumeration<URL> getResources(final Class<?> contextClass, final String name) throws IOException {
    try {
      if (contextClass != null) {
        Enumeration<URL> urls = contextClass.getClassLoader().getResources(name);
        if (urls != null && urls.hasMoreElements()) {
          return urls;
        }
      }
    } catch (IOException ignore) {
      // fall through to invoke getResources(name)
    }
    return getResources(name);
  }

  /**
   * Interface to plugin a custom class loading mechanism (as opposed to a
   * custom ClassLoader). Used by GemFireXD for classes loaded into the instance
   * and persisted by sqlj.install_jar.
   * 
   * @author swale
   * @since 7.0
   */
  public static interface ClassLoaderInterface {

    Class<?> loadApplicationClass(String className)
        throws ClassNotFoundException;
  }

  /**
   * Finds all the resources with the given name.
   * 
   * @param  name
   *         The resource name
   *
   * @return  An enumeration of {@link java.net.URL <tt>URL</tt>} objects for
   *          the resource.  If no resources could  be found, the enumeration
   *          will be empty.  Resources that the class loader doesn't have
   *          access to will not be in the enumeration.
   *
   * @throws  IOException
   *          If I/O errors occur
   */
  public Enumeration<URL> getResources(String name) throws IOException {
    if (ENABLE_TRACE) {
      trace(new StringBuilder("getResources(").append(name).append(")"));
    }
    Enumeration<URL> urls = null;
    ClassLoader tccl = null;
    if (!excludeTCCL) {
      tccl = Thread.currentThread().getContextClassLoader();
    }

    IOException ioException = null;
    for (ClassLoader classLoader : this.classLoaders) {
      ioException = null; // reset to null for next ClassLoader
      if (classLoader == TCCL_PLACEHOLDER) {
        try {
          if (tccl != null) {
            if (ENABLE_TRACE) {
              trace(new StringBuilder("getResources trying TCCL: ").append(tccl));
            }
            urls = tccl.getResources(name);
            if (urls != null && urls.hasMoreElements()) {
              if (ENABLE_TRACE) {
                trace("getResources found by TCCL");
              }
              return urls;
            }
          } else {
            if (ENABLE_TRACE) {
              trace(new StringBuilder("getResources skipping TCCL because it's null"));
            }
          }
        } catch (SecurityException ignore) {
          // Continue to next ClassLoader
        } catch (IOException ignore) {
          ioException = ignore;
          // Continue to next ClassLoader
        }
      } else if (excludeTCCL || !classLoader.equals(tccl)) {
        try {
          if (ENABLE_TRACE) {
            trace(new StringBuilder("getResources trying classLoader: ").append(classLoader));
          }
          urls = classLoader.getResources(name);
          if (urls != null && urls.hasMoreElements()) {
            if (ENABLE_TRACE) {
              trace(new StringBuilder("getResources found by classLoader: ").append(classLoader));
            }
            return urls;
          }
        } catch (IOException ignore) {
          ioException = ignore;
          // Continue to next ClassLoader
        }
      }
    }

    if (ioException != null) {
      if (ENABLE_TRACE) {
        trace(new StringBuilder("getResources throwing IOException"));
      }
      throw ioException;
    }
    
    if (ENABLE_TRACE) {
      trace(new StringBuilder("getResources returning empty enumeration"));
    }
    return urls;
  }
  
  private void trace(final StringBuilder message) {
    trace(message.toString());
  }

  private void trace(final String message) {
    String msg = new StringBuilder(toString()).append(" ").append(message).toString();
    GemFireCacheImpl gfc = GemFireCacheImpl.getInstance();
    if (gfc != null) {
      gfc.getLogger().fine(msg);
    } else {
      System.out.println(msg);
    }
  }

  private static void warning(final String message) {
    GemFireCacheImpl gfc = GemFireCacheImpl.getInstance();
    if (gfc != null) {
      gfc.getLogger().warning(message);
    } else {
      System.err.println(message);
    }
  }

  public ClassLoader asClassLoader() {
    return new ClassLoader() {
      public Class<?> loadClass(String name) throws ClassNotFoundException {
        return ClassPathLoader.this.forName(name);
      }

      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return ClassPathLoader.this.forName(name);
      }

      public URL getResource(String name) {
        return ClassPathLoader.this.getResource(name);
      }

      public Enumeration<URL> getResources(String name) throws IOException {
        return ClassPathLoader.this.getResources(name);
      }

      public InputStream getResourceAsStream(String name) {
        return ClassPathLoader.this.getResourceAsStream(name);
      }
    };
  }
  
  public static ClassPathLoader getLatest() {
    return latest.get();
  }

  public static final ClassLoader getLatestAsClassLoader() {
    return ((ClassPathLoader)latest.get()).asClassLoader();
  }


}
