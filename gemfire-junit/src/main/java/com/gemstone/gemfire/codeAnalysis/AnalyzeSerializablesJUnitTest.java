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
package com.gemstone.gemfire.codeAnalysis;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

import com.gemstone.gemfire.codeAnalysis.decode.CompiledAttribute;
import com.gemstone.gemfire.codeAnalysis.decode.CompiledClass;
import com.gemstone.gemfire.codeAnalysis.decode.CompiledField;
import com.gemstone.gemfire.codeAnalysis.decode.CompiledMethod;


/**
 * @author bruces
 * 
 */
public class AnalyzeSerializablesJUnitTest extends TestCase {
  /** all loaded classes */
  private static Map<String, CompiledClass> classes = new HashMap<String, CompiledClass>();
  private static boolean ClassesNotFound;
  
  public AnalyzeSerializablesJUnitTest() {
  }
  
  @Override
  public void setUp() {
  }
  
  @Override
  public void tearDown() {
  }
  
  public void testDummy() {
    // the tests in this class are not run in GemFireXD.  A copy of the test
    // is in the gemfirexd/GemFireXDTests/junit/codeAnalysis package.  Any merge
    // changes from GFE should be merged into that package 
  }
  
  public void xtestLoadClasses() throws Exception {
    System.out.println("testLoadClasses starting");
    String cp = System.getProperty("java.class.path");
    String[] entries = cp.split(File.pathSeparator);
    String gfxdjar = null;
    for (int i=entries.length-1; i>=0; i--) {
      System.out.println("examining '" + entries[i] + "'");
      if (entries[i].matches("snappydata-store-[0-9\\.]*(-SNAPSHOT.[0-9]*)?.jar")) {
        gfxdjar = entries[i];
        break;
      }
    }
    if (gfxdjar != null) {
      System.out.println("loading class files from " + gfxdjar);
      long start = System.currentTimeMillis();
      loadClasses(new File(gfxdjar));
      long finish = System.currentTimeMillis();
      System.out.println("done loading classes.  elapsed time = "
          + (finish-start)/1000 + " seconds");
    }
    else {
      // when running in Eclipse there is no gemfire.jar in the classpath
      System.out.println("...unable to locate gemfirexd.jar");
      
      // in eclipse you can put your build-artifacts/XXX/classes directory
      // into this variable and run the tests
      String classesDir = "/srv/gemfire/git-trunk/build-artifacts/linux/gemfirexd/classes";
      
      if (!(new File(classesDir).exists())) {
        System.out.println("error: unable to locate product classes tree using " + classesDir);
        ClassesNotFound = true;
        throw new AssertionError("unable to locate product classes.  gemfirexd.jar not found in classpath");
      }
      System.out.println("loading class files from " + classesDir);
      long start = System.currentTimeMillis();
      loadClasses(classesDir, true);
      long finish = System.currentTimeMillis();
      System.out.println("done loading classes.  elapsed time = "
          + (finish-start)/1000 + " seconds");
    }
  }
  
  
  public void xtestDataSerializables() throws Exception {
    System.out.println("testDataSerializables starting");
    if (ClassesNotFound) {
      System.out.println("... test not run due to not being able to locate product class files");
      return;
    }
    String testsDir = System.getProperty("TESTDIR");
    if (testsDir == null) {
      testsDir = "./tests";
    }
    String recordsDir =  testsDir + "/com/gemstone/gemfire/codeAnalysis/";
    String compareToFileName = recordsDir + "sanctionedDataSerializables.txt";

    String storeInFileName = "actualDataSerializables.dat";
    File storeInFile = new File(storeInFileName);
    if (storeInFile.exists() && !storeInFile.canWrite()) {
      throw new RuntimeException("can't write " + storeInFileName);
    }
    List<ClassAndMethods> toDatas = findToDatasAndFromDatas();
    CompiledClassUtils.storeClassesAndMethods(toDatas, storeInFile);

    File compareToFile = new File(compareToFileName);
    if (!compareToFile.exists()) {
      throw new RuntimeException("can't find " + compareToFileName);
    }
    if (!compareToFile.canRead()) {
      throw new RuntimeException("can't read " + compareToFileName);
    }

    List<ClassAndMethodDetails> goldRecord = CompiledClassUtils.loadClassesAndMethods(compareToFile);
    String diff = CompiledClassUtils.diffSortedClassesAndMethods(goldRecord, toDatas);
    if (diff.length() > 0) {
      System.out.println("++++++++++++++++++++++++++++++testDataSerializables found discrepencies++++++++++++++++++++++++++++++++++++");
      System.out.println(diff);
      fail("Found toData/fromData discrepencies.  Examine log file for discrepencies.  If this doesn't break backward compatibility move the file actualDataSerializables.dat to the com/gemstone/gemfire/codeAnalysis test directory and rename to sanctionedDataSerializables.txt");
    }
  }
  
  public void xtestSerializables() throws Exception {
    System.out.println("testSerializables starting");
    if (ClassesNotFound) {
      System.out.println("... test not run due to not being able to locate product class files");
      return;
    }
    String testsDir = System.getProperty("TESTDIR");
    if (testsDir == null) {
      testsDir = "./tests";
    }
    String recordsDir = testsDir + "/com/gemstone/gemfire/codeAnalysis/";
    String compareToFileName = recordsDir + "sanctionedSerializables.txt";
    File compareToFile = new File(compareToFileName);

    String storeInFileName = "actualSerializables.dat";
    File storeInFile = new File(storeInFileName);
    if (storeInFile.exists() && !storeInFile.canWrite()) {
      throw new RuntimeException("can't write " + storeInFileName);
    }
    
    List<ClassAndVariables> serializables = findSerializables();
    reset();
    CompiledClassUtils.storeClassesAndVariables(serializables, storeInFile);

    
    if (!compareToFile.exists()) {
      throw new RuntimeException("can't find " + compareToFileName);
    }
    if (!compareToFile.canRead()) {
      throw new RuntimeException("can't read " + compareToFileName);
    }
    List<ClassAndVariableDetails> goldRecord = CompiledClassUtils.loadClassesAndVariables(compareToFile);
    String diff = CompiledClassUtils.diffSortedClassesAndVariables(goldRecord, serializables);
    classes.clear();
    if (diff.length() > 0) {
      System.out.println("++++++++++++++++++++++++++++++testSerializables found discrepencies++++++++++++++++++++++++++++++++++++");
      System.out.println(diff);
      fail("Found Serializable field discrepencies.  Examine log file for discrepencies.  If this doesn't break backward compatibility move the file actualSerializables.dat to the com/gemstone/gemfire/codeAnalysis test directory and rename to sanctionedSerializables.txt");
    }
  }
  
  /**
   * load the classes from the given files and directories
   */
  public void loadClasses(String directory, boolean recursive) {
    String[] filenames = new String[]{ directory };
    List<File> classFiles = CompiledClassUtils.findClassFiles("", filenames, recursive);
    Map<String, CompiledClass> newClasses = CompiledClassUtils.parseClassFiles(classFiles);
    classes.putAll(newClasses);
  }
  
  /**
   * load the classes from the given jar file
   */
  public void loadClasses(File jar) {
    Map<String, CompiledClass> newClasses = CompiledClassUtils.parseClassFiles(jar);
    classes.putAll(newClasses);
  }
  
  /**
   * clears all loaded classes
   */
  public void reset() {
    classes.clear();
  }
  
  public List<ClassAndMethods> findToDatasAndFromDatas() {
    List<ClassAndMethods> result = new ArrayList<ClassAndMethods>();
    for (Map.Entry<String, CompiledClass> dentry: classes.entrySet()) {
      CompiledClass dclass = dentry.getValue();
      ClassAndMethods entry = null;
      for (int i=0; i<dclass.methods.length; i++) {
        CompiledMethod method = dclass.methods[i];
        if (!method.isAbstract() &&  method.descriptor().equals("void")) {
          String name = method.name();
          if (name.startsWith("toData") || name.startsWith("fromData")) {
            if (entry == null) {
              entry = new ClassAndMethods(dclass);
            }
            entry.methods.put(method.name(), method);
          }
        }
      }
      if (entry != null) {
        result.add(entry);
      }
    }
    Collections.sort(result);
    return result;
  }
  
  
  public List<ClassAndVariables> findSerializables() {
    List<ClassAndVariables> result = new ArrayList<ClassAndVariables>(2000);
    for (Map.Entry<String, CompiledClass> entry: classes.entrySet()) {
      CompiledClass dclass = entry.getValue();
      if (!dclass.isInterface() && dclass.isSerializableAndNotDataSerializable()) {
        ClassAndVariables cav = new ClassAndVariables(dclass);
        for (int i=0; i<dclass.fields_count; i++) {
          CompiledField f = dclass.fields[i];
          if (!f.isStatic() && !f.isTransient()) {
            cav.variables.put(f.name(), f);
          }
        }
        result.add(cav);
      }
    }
    Collections.sort(result);
    return result;
  }

}
