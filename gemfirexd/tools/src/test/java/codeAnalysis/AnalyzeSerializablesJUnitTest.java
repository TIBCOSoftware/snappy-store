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
package codeAnalysis;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.internal.shared.Version;

import com.pivotal.gemfirexd.TestUtil;
import junit.framework.Test;
import junit.framework.TestCase;
import codeAnalysis.decode.CompiledAttribute;
import codeAnalysis.decode.CompiledClass;
import codeAnalysis.decode.CompiledField;
import codeAnalysis.decode.CompiledMethod;


/**
 * @author bruces
 * 
 */
public class AnalyzeSerializablesJUnitTest extends TestCase {
  /** all loaded classes */
  private static Map<String, CompiledClass> classes = new HashMap<String, CompiledClass>();
  private static boolean DISABLED = true;
  private static boolean ClassesNotFound;
  
  public AnalyzeSerializablesJUnitTest() {
  }
  
  @Override
  public void setUp() {
  }
  
  @Override
  public void tearDown() {
  }
  
  public void testLoadClasses() throws Exception {
    System.out.println("testLoadClasses starting");
    List<String> excludedClasses = loadExcludedClasses();
    excludedClasses.addAll(loadOpenBugs());

    String cp = System.getProperty("java.class.path");
    String[] entries = cp.split(File.pathSeparator);
    String gfxdjar = null;
    for (int i=0; i<entries.length; i++) {
      System.out.println("examining '" + entries[i] + "'");
      if (entries[i].matches(".*snappydata-store-core-[0-9\\.]*(-(SNAPSHOT|BETA|rc))?[0-9\\.]*" +
          ".jar")) {
        gfxdjar = entries[i];
        break;
      }
    }
    if (gfxdjar != null) {
      System.out.println("loading class files from " + gfxdjar);
      long start = System.currentTimeMillis();
      loadClasses(new File(gfxdjar), excludedClasses);
      long finish = System.currentTimeMillis();
      System.out.println("done loading classes.  elapsed time = "
          + (finish-start)/1000 + " seconds");
    }
    else {
      // when running in Eclipse there is no gemfirexd.jar in the classpath
      System.out.println("...unable to locate gemfirexd.jar");

      // in eclipse you can put your build-artifacts/XXX/classes directory
      // into this variable and run the tests
      String classesDir =
          "/home/shobhit/gemfire/cedar_dev_Oct12/build-artifacts/linux/classes";

      if (!(new File(classesDir).exists())) {
        System.out.println(
            "error: unable to locate product classes tree using " + classesDir);
        ClassesNotFound = true;
        throw new AssertionError("unable to locate product classes. " +
            "gemfirexd.jar not found in classpath");
      }
      System.out.println("loading class files from " + classesDir);
      long start = System.currentTimeMillis();
      loadClasses(classesDir, true, excludedClasses);
      long finish = System.currentTimeMillis();
      System.out.println("done loading classes.  elapsed time = "
          + (finish-start)/1000 + " seconds");
    }
    DISABLED = false;
  }

  private List<String> loadExcludedClasses() throws Exception {
    String testsDir = TestUtil.getResourcesDir();
    String recordsDir =  testsDir + "/codeAnalysis/";
    List<String> excludedClasses = new LinkedList<String>();
    File exclusionsFile = new File(recordsDir+"excludedClasses.txt");
    FileReader fr = new FileReader(exclusionsFile);
    BufferedReader br = new BufferedReader(fr);
    try {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.length() > 0 && !line.startsWith("#")) {
          excludedClasses.add(line);
        }
      }
    } finally {
      fr.close();
    }
    return excludedClasses;
  }
  
  private List<String> loadOpenBugs() throws Exception {
    String testsDir = TestUtil.getResourcesDir();
    String recordsDir =  testsDir + "/codeAnalysis/";
    List<String> excludedClasses = new LinkedList<String>();
    File exclusionsFile = new File(recordsDir+"openBugs.txt");
    FileReader fr = new FileReader(exclusionsFile);
    BufferedReader br = new BufferedReader(fr);
    try {
      String line;
      // each line should have bug#,full-class-name
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.length() > 0 && !line.startsWith("#")) {
          String[] split = line.split(",");
          if (split.length != 2) {
            DISABLED = true; // don't run the other tests
            fail("unable to load classes due to misformatted line in openBugs.txt: " + line);
          }
          excludedClasses.add(line.split(",")[1].trim());
        }
      }
    } finally {
      fr.close();
    }
    return excludedClasses;
  }
  
  private void removeExclusions(Map<String, CompiledClass> classes, List<String> exclusions) {
    for (String exclusion: exclusions) {
      exclusion = exclusion.replace('.', '/');
      classes.remove(exclusion);
    }
  }
  
  
  public void testDataSerializables() throws Exception {
    System.out.println("testDataSerializables starting");
    if (ClassesNotFound) {
      System.out.println("... test not run due to not being able to locate product class files");
      return;
    }
    if (DISABLED) {
      System.out.println("... test is disabled");
      return;
    }
    String testsDir = TestUtil.getResourcesDir();
    String recordsDir =  testsDir + "/codeAnalysis/";
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
    Collections.sort(goldRecord);
    
    String diff = CompiledClassUtils.diffSortedClassesAndMethods(goldRecord, toDatas);
    if (diff.length() > 0) {
      System.out.println("++++++++++++++++++++++++++++++testDataSerializables found discrepencies++++++++++++++++++++++++++++++++++++");
      System.out.println(diff);
      fail("\n\nCruise Control monitors:  DO NOT DISABLE THIS TEST!\n"+
          "File bugs against each failing class and add to junit/codeAnalysis/openBugs.txt\n\n"
          + diff+"\n\nDevelopers: If the class is not persisted or sent over the wire add it to the excludedClasses.txt file in the "
          + "\ngemfirexd/GemFireXDTests/junit/codeAnalysis directory.  Otherwise copy actualDataSerializables.dat "
          + "\nto that directory and rename to sanctionedDataSerializables.txt.");
    }
  }
  
  public void testSerializables() throws Exception {
    System.out.println("testSerializables starting");
    if (ClassesNotFound) {
      System.out.println("... test not run due to not being able to locate product class files");
      return;
    }
    if (DISABLED) {
      System.out.println("... test is disabled");
      return;
    }
    String testsDir = TestUtil.getResourcesDir();
    String recordsDir = testsDir + "/codeAnalysis/";
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
    Collections.sort(goldRecord);

    String diff = CompiledClassUtils.diffSortedClassesAndVariables(goldRecord, serializables);
    classes.clear();
    if (diff.length() > 0) {
      System.out.println("++++++++++++++++++++++++++++++testSerializables found discrepencies++++++++++++++++++++++++++++++++++++");
      System.out.println(diff);
      fail("\n\nCruise Control monitors:  DO NOT DISABLE THIS TEST!\n"+
            "File bugs against each failing class and add to junit/codeAnalysis/openBugs.txt\n\n"
            +diff+"\n\nDevelopers: If the class is not persisted or sent over the wire add it to the excludedClasses.txt file in the "
            + "\ngemfirexd/GemFireXDTests/junit/codeAnalysis directory.  Otherwise if this doesn't "
            + "\nbreak backward compatibility move the file actualSerializables.dat to the "
            + "\ncodeAnalysis test directory and rename to sanctionedSerializables.txt");
    }
  }
  
  /**
   * load the classes from the given files and directories
   * @param excludedClasses TODO
   */
  public void loadClasses(String directory, boolean recursive, List<String> excludedClasses) {
    String[] filenames = new String[]{ directory };
    List<File> classFiles = CompiledClassUtils.findClassFiles("", filenames, recursive);
    Map<String, CompiledClass> newClasses = CompiledClassUtils.parseClassFiles(classFiles);
    removeExclusions(newClasses, excludedClasses);
    classes.putAll(newClasses);
  }
  
  /**
   * load the classes from the given jar file
   */
  public void loadClasses(File jar, List<String> excludedClasses) {
    Map<String, CompiledClass> newClasses = CompiledClassUtils.parseClassFiles(jar);
    removeExclusions(newClasses, excludedClasses);
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
