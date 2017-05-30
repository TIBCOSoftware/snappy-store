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
package com.gemstone.gemfire.cache.client.internal;

import java.io.Serializable;
import java.util.Set;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.execute.AbstractExecution;
import com.gemstone.gemfire.internal.cache.execute.MemberMappedArgument;
import com.gemstone.gemfire.internal.cache.execute.ServerRegionFunctionExecutor;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.tier.sockets.ChunkedMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.Message;
import com.gemstone.gemfire.internal.cache.tier.sockets.Part;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.shared.Version;

/**
 * Does a Execution of function on server region
 * It does not get the resul from the server (follows Fire&Forget approch)
 * @author Kishor Bachhav
 * @since 5.8Beta
 */
public class ExecuteRegionFunctionNoAckOp {

  private ExecuteRegionFunctionNoAckOp() {
    // no instances allowed
  }

  /**
   * Does a execute Function on a server using connections from the given pool
   * to communicate with the server.
   * @param pool the pool to use to communicate with the server.
   * @param region the name of the region to do the put on
   * @param function to be executed
   * @param serverRegionExecutor which will return argument and filter
   */
  public static void execute(ExecutablePool pool, String region,
      Function function, ServerRegionFunctionExecutor serverRegionExecutor,
      byte hasResult) {
    LogWriterI18n logger = pool.getLoggerI18n();
    AbstractOp op = new ExecuteRegionFunctionNoAckOpImpl(logger, region,
        function, serverRegionExecutor, hasResult);
    try {
      if (logger.fineEnabled()) {
        logger
            .fine("ExecuteRegionFunctionNoAckOp#execute : Sending Function Execution Message:"
                + op.getMessage() + " to Server using pool: " +pool);
      }
      pool.execute(op);
    }
    catch (Exception ex) {
      if (logger.fineEnabled()) {
        logger
            .fine("ExecuteRegionFunctionNoAckOp#execute : Exception occured while Sending Function Execution Message:"
                + op.getMessage() + " to server using pool: " +pool, ex);
      }
      if (ex.getMessage() != null)
        throw new FunctionException(ex.getMessage(), ex);
      else
        throw new FunctionException(
            "Unexpected exception during function execution:", ex);
    }
  }

  public static void execute(ExecutablePool pool, String region,
      String functionId, ServerRegionFunctionExecutor serverRegionExecutor,
      byte hasResult, boolean isHA, boolean optimizeForWrite) {
    LogWriterI18n logger = pool.getLoggerI18n();
    AbstractOp op = new ExecuteRegionFunctionNoAckOpImpl(logger, region,
        functionId, serverRegionExecutor, hasResult, isHA, optimizeForWrite);
    try {
      if (logger.fineEnabled()) {
        logger
            .fine("ExecuteRegionFunctionNoAckOp#execute : Sending Function Execution Message:"
                + op.getMessage() + " to Server using pool: " +pool);
      }
      pool.execute(op);
    }
    catch (Exception ex) {
      if (logger.fineEnabled()) {
        logger
            .fine("ExecuteRegionFunctionNoAckOp#execute : Exception occured while Sending Function Execution Message:"
                + op.getMessage() + " to server using pool: " +pool, ex);
      }
      if (ex.getMessage() != null)
        throw new FunctionException(ex.getMessage(), ex);
      else
        throw new FunctionException(
            "Unexpected exception during function execution:", ex);
    }
  }
  
  private static class ExecuteRegionFunctionNoAckOpImpl extends AbstractOp {

    LogWriterI18n logger = null;

    /**
     * @throws com.gemstone.gemfire.SerializationException if serialization fails
     */
    public ExecuteRegionFunctionNoAckOpImpl(LogWriterI18n lw, String region,
        Function function, ServerRegionFunctionExecutor serverRegionExecutor, byte hasResult) {
      super(lw, MessageType.EXECUTE_REGION_FUNCTION, 8 + serverRegionExecutor
          .getFilter().size());
      logger = lw;
      byte isReExecute = 0;
      int removedNodesSize = 0;
      byte functionState = AbstractExecution.getFunctionState(function.isHA(),
          function.hasResult(), function.optimizeForWrite());
      Set routingObjects = serverRegionExecutor.getFilter();
      Object args = serverRegionExecutor.getArguments();
      MemberMappedArgument memberMappedArg = serverRegionExecutor.getMemberMappedArgument();
      getMessage().addBytesPart(new byte[]{functionState});
      getMessage().addStringPart(region);
      if(serverRegionExecutor.isFnSerializationReqd()){
        getMessage().addStringOrObjPart(function); 
      }
      else{
        getMessage().addStringOrObjPart(function.getId()); 
      }
      getMessage().addObjPart(args);
      getMessage().addObjPart(memberMappedArg);
      getMessage().addBytesPart(new byte[]{isReExecute});
      getMessage().addIntPart(routingObjects.size());
      for (Object key : routingObjects) {
        getMessage().addStringOrObjPart(key);
      }
      getMessage().addIntPart(removedNodesSize);
    }

    public ExecuteRegionFunctionNoAckOpImpl(LogWriterI18n lw, String region,
        String functionId, ServerRegionFunctionExecutor serverRegionExecutor, byte hasResult, boolean isHA, boolean optimizeForWrite) {
      super(lw, MessageType.EXECUTE_REGION_FUNCTION, 8 + serverRegionExecutor
          .getFilter().size());
      logger = lw;
      byte isReExecute = 0;
      int removedNodesSize = 0;
      byte functionState = AbstractExecution.getFunctionState(isHA,
          hasResult==(byte)1?true:false, optimizeForWrite);
      
      Set routingObjects = serverRegionExecutor.getFilter();
      Object args = serverRegionExecutor.getArguments();
      MemberMappedArgument memberMappedArg = serverRegionExecutor.getMemberMappedArgument();
      getMessage().addBytesPart(new byte[]{functionState});
      getMessage().addStringPart(region);
      getMessage().addStringOrObjPart(functionId); 
      getMessage().addObjPart(args);
      getMessage().addObjPart(memberMappedArg);
      getMessage().addBytesPart(new byte[]{isReExecute});
      getMessage().addIntPart(routingObjects.size());
      for (Object key : routingObjects) {
        getMessage().addStringOrObjPart(key);
      }
      getMessage().addIntPart(removedNodesSize);
    }
    
    @Override  
    protected Object processResponse(Message msg) throws Exception {
      final int msgType = msg.getMessageType();
      if (msgType == MessageType.REPLY) {
      return null;
    }
      else {
        Part part = msg.getPart(0);
        if (msgType == MessageType.EXCEPTION) {
          Throwable t = (Throwable)part.getObject();
          logger
              .warning(
                  LocalizedStrings.EXECUTE_FUNCTION_NO_HAS_RESULT_RECEIVED_EXCEPTION,
                  t);
        }
        else if (isErrorResponse(msgType)) {
          logger
              .warning(
                  LocalizedStrings.EXECUTE_FUNCTION_NO_HAS_RESULT_RECEIVED_EXCEPTION,
                  part.getString());
        }
        else {
          throw new InternalGemFireError("Unexpected message type "
              + MessageType.getString(msgType));
        }
        return null;
      }
    
    }

    @Override  
    protected boolean isErrorResponse(int msgType) {
      return msgType == MessageType.EXECUTE_REGION_FUNCTION_ERROR;
    }

    @Override  
    protected long startAttempt(ConnectionStats stats) {
      return stats.startExecuteFunction();
    }

    @Override  
    protected void endSendAttempt(ConnectionStats stats, long start) {
      stats.endExecuteFunctionSend(start, hasFailed());
    }

    @Override  
    protected void endAttempt(ConnectionStats stats, long start) {
      stats.endExecuteFunction(start, hasTimedOut(), hasFailed());
    }

    @Override  
    protected Message createResponseMessage() {
      return new Message(1, Version.CURRENT_GFE);
    }
  }
}
