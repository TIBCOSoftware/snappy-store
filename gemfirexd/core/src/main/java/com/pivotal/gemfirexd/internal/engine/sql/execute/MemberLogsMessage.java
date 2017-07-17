package com.pivotal.gemfirexd.internal.engine.sql.execute;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.cache.execute.ResultCollector;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.lang.StringUtils;
import com.google.common.io.ByteStreams;
import com.pivotal.gemfirexd.internal.engine.distributed.message.GfxdFunctionMessage;
import com.pivotal.gemfirexd.internal.engine.distributed.message.MemberExecutorMessage;
import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Logger;


public class MemberLogsMessage extends MemberExecutorMessage {

  private Logger logger = Logger.getLogger(MemberLogsMessage.class);

  private String memberId;
  private Long offset;
  //private int byteLength;
  private Long byteLength;

  /** Default constructor for deserialization. Not to be invoked directly. */
  public MemberLogsMessage() {
    super(true);
  }

  public MemberLogsMessage(
      final ResultCollector<Object, Object> rc) {
    super(rc, null, false, true);
  }

  private MemberLogsMessage(MemberExecutorMessage other) {
    super(other);
  }

  public String getMemberId() {
    return memberId;
  }

  public void setMemberId(String memberId) {
    this.memberId = memberId;
  }

  public Long getOffset() {
    return offset;
  }

  public void setOffset(Long offset) {
    this.offset = offset;
  }

  public Long getByteLength() {
    return byteLength;
  }

  public void setByteLength(Long byteLength) {
    this.byteLength = byteLength;
  }

  @Override
  protected void execute() throws Exception {

    GemFireCacheImpl gemFireCache = GemFireCacheImpl.getExisting();
    InternalDistributedSystem ids = gemFireCache.getDistributedSystem();
    String memberId = ids.getMemberId();

    Map logDetailsMap = new HashMap();
    logDetailsMap.put("id", memberId);
    logDetailsMap.put("name", ids.getName());
    logDetailsMap.put("userDir", getUserDir());
    logDetailsMap.put("logFile", getLogFile());
    logDetailsMap.put("logData", getLog(this.offset, this.byteLength));

    lastResult(logDetailsMap);

  }

  private String getUserDir(){
    return System.getProperty("user.dir");
  }

  private String getLogFile() {
    Logger rootLogger = Logger.getRootLogger();
    Appender appender;
    if (rootLogger != null) {
      Enumeration<?> e = rootLogger.getAllAppenders();
      while (e.hasMoreElements()) {
        appender = (Appender)e.nextElement();
        if (appender instanceof FileAppender) {
          return ((FileAppender)appender).getFile();
        }
      }
    }
    return "";
  }

  /** Get the part of the log files given the offset and desired length of bytes */
  private Map<String, Object> getLog(Long offset, Long byteLength) {

    Map<String, Object> logsMap = new HashMap<String, Object>();

    String logFile = getLogFile();
    if (logFile == null || StringUtils.isEmpty(logFile)) {
      logger.error("Unable to get log file.." + logFile);

      logsMap.put("logText", "Unable to get log file : " + logFile);
      logsMap.put("startIndex", 0L);
      logsMap.put("endIndex", 0L);
      logsMap.put("totalLength", 0L);

      return logsMap;
    }

    try {
      File logDir = new File(getUserDir());
      File[] files = getSortedFiles(logFile, logDir);
      Long[] fileLengths = new Long[files.length];
      Long totalLength = 0L;
      for(int i=0; i < files.length; i++){
        fileLengths[i] = files[i].length();
        totalLength += files[i].length();
      }

      // set offset if not specified
      if(offset == null){
        offset = totalLength - byteLength;
      }

      Long startIndex;
      if (offset < 0) {
        startIndex = 0L;
      } else if (offset > totalLength) {
        startIndex = totalLength;
      } else {
        startIndex = offset;
      }

      Long endIndex = Math.min(startIndex + byteLength, totalLength);

      logger.debug("Getting log from "+startIndex+" to "+ endIndex);

      // getting log text
      String logText = getOffsetBytes(files, fileLengths, totalLength, startIndex, endIndex);

      logger.debug("Got log of length " + logText.length() + " bytes");

      logsMap.put("logText", logText);
      logsMap.put("startIndex", startIndex);
      logsMap.put("endIndex", endIndex);
      logsMap.put("totalLength", totalLength);

    } catch (Exception e){
      logger.error("Error getting logs due to exception: " + e.getMessage(), e);

      logsMap.put("logText", "Error getting logs due to exception: " + e.getMessage());
      logsMap.put("startIndex", 0L);
      logsMap.put("endIndex", 0L);
      logsMap.put("totalLength", 0L);

    }

    return logsMap;
  }

  private File[] getSortedFiles(String logFileName, File logDirectory){
    File[] files = logDirectory.listFiles(new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.toLowerCase().startsWith(logFileName.toLowerCase());
      }
    });
    Arrays.sort(files);
    return files;
  }

  /**
   * Return a string containing data across a set of files. The `startIndex`
   * and `endIndex` is based on the cumulative size of all the files take in
   * the given order. See figure below for more details.
   */
  private String getOffsetBytes(File[] files,
      Long[] fileLengths,
      Long totalLength,
      Long start,
      Long end){

    if(files.length != fileLengths.length){
      logger.debug("Log Files count and their lengths counts are not matching");
      return "Error: Log Files count and their lengths counts are not matching";
    }

    Long startIndex = Math.max(start, 0);
    Long endIndex = Math.min(end, totalLength);

    int stringBufferSize = (int)(endIndex - startIndex);
    StringBuffer stringBuffer = new StringBuffer(stringBufferSize);
    Long sum = 0L;

    for(int i=0; i < files.length; i++){
      Long startIndexOfFile = sum;
      Long endIndexOfFile = sum + fileLengths[i];

      // todo: log debug statement
      logger.debug("Processing file " + files[i] + ", with start index = " + startIndexOfFile +
          ", end index = " + endIndex);

      /*
                                      ____________
       range 1:                      |            |
                                     |   case A   |

       files:   |==== file 1 ====|====== file 2 ======|===== file 3 =====|

                     |   case B  .       case C       .    case D    |
       range 2:      |___________.____________________.______________|

       */

      if (startIndex <= startIndexOfFile  && endIndex >= endIndexOfFile) {
        // Case C: read the whole file
        stringBuffer.append(getOffsetBytes(
            files[i].getAbsolutePath(), fileLengths[i], 0L, fileLengths[i]));
      } else if (startIndex > startIndexOfFile && startIndex < endIndexOfFile) {
        // Case A and B: read from [start of required range] to [end of file / end of range]
        Long effectiveStartIndex = startIndex - startIndexOfFile;
        Long effectiveEndIndex = Math.min(endIndex - startIndexOfFile, fileLengths[i]);
        stringBuffer.append(getOffsetBytes(
            files[i].getAbsolutePath(), fileLengths[i], effectiveStartIndex, effectiveEndIndex));
      } else if (endIndex > startIndexOfFile && endIndex < endIndexOfFile) {
        // Case D: read from [start of file] to [end of require range]
        Long effectiveStartIndex = Math.max(startIndex - startIndexOfFile, 0);
        Long effectiveEndIndex = endIndex - startIndexOfFile;
        stringBuffer.append(getOffsetBytes(
            files[i].getAbsolutePath(), fileLengths[i], effectiveStartIndex, effectiveEndIndex));
      }
      sum += fileLengths[i];
    }

    return stringBuffer.toString();
  }

  /** Return a string containing part of a file from byte 'start' to 'end'. */
  private String getOffsetBytes(String filePath, Long fileLength, Long start, Long end){

    File file = new File(filePath);
    if(file == null || StringUtils.isEmpty(filePath)){
      logger.debug("File is not specified.." + filePath);
      return "File is not specified..";
    }

    Long effectiveStart = Math.max(0, start);
    Long effectiveEnd = Math.min(fileLength, end);

    int buffLength = (int)(effectiveEnd-effectiveStart);
    byte[] buff = new byte[buffLength];
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(file);
      if(fis != null){
        ByteStreams.skipFully(fis, effectiveStart);
        ByteStreams.readFully(fis, buff);
      } else {
        logger.debug("Could not read File [ " + filePath + " ]");
        return "Could not read File [ " + filePath + " ]";
      }

    } catch (FileNotFoundException e) {
      logger.error("FileNotFoundException: " + e.getMessage(), e);
    } catch (IOException e) {
      logger.error("IOException: "+e.getMessage(), e);
    } finally {
      if(fis != null){
        try {
          fis.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    return new String(buff);
  }

  @Override
  public Set<DistributedMember> getMembers() {
    Set<DistributedMember> members = new HashSet<DistributedMember>();
    if(this.memberId != null || !this.memberId.isEmpty()) {
      Set<DistributedMember> distMembers = getAllGfxdMembers();
      Iterator<DistributedMember> itr = distMembers.iterator();
      while(itr.hasNext()){
        DistributedMember distMember = itr.next();
        if(distMember.getId().equalsIgnoreCase(memberId)){
          members.add(distMember);
          break;
        }
      }
    }
    return members;
  }

  @Override
  public void fromData(DataInput in)
      throws IOException, ClassNotFoundException {
    super.fromData(in);
    this.memberId = DataSerializer.readString(in);
    this.offset = DataSerializer.readObject(in);
    this.byteLength = DataSerializer.readObject(in);
  }

  @Override
   public void toData(final DataOutput out) throws IOException {
    super.toData(out);
    DataSerializer.writeString(this.memberId, out);
    DataSerializer.writeObject(this.offset , out);
    DataSerializer.writeObject(byteLength, out);
  }

  @Override
  public void postExecutionCallback() {

  }

  @Override
  public boolean isHA() {
    return false;
  }

  @Override
  public boolean optimizeForWrite() {
    return false;
  }

  @Override
  protected GfxdFunctionMessage clone() {
    return null;
  }

  @Override
  public byte getGfxdID() {
    return MEMBER_LOGS_MESSAGE;
  }
}
