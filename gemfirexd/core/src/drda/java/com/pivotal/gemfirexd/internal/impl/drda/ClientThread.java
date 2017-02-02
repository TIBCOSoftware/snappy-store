/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.drda.ClientThread

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
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

package com.pivotal.gemfirexd.internal.impl.drda;

import java.io.*;
import java.net.*;
import java.security.*;

import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.SystemProperties;
import com.pivotal.gemfirexd.Attribute;
import io.snappydata.thrift.common.SnappyTSocket;

final class ClientThread extends Thread {

	NetworkServerControlImpl parent;
	ServerSocket serverSocket;
	private int timeSlice;
	private int connNum;
    
    ClientThread (NetworkServerControlImpl nsi, ServerSocket ss) {
        
        // Create a more meaningful name for this thread (but preserve its
        // thread id from the default name).
        NetworkServerControlImpl.setUniqueThreadName(this, "NetworkServerThread");
        
        parent=nsi;
        serverSocket=ss;
        timeSlice=nsi.getTimeSlice();
    }
	
    @SuppressWarnings("unchecked")
    public void run() 
    {
        Socket clientSocket = null;
        
        for (;;) { // Nearly infinite loop. The loop is terminated if
                   // 1) We are shut down or 2) SSL won't work. In all
                   // other cases we just continue and try another
                   // accept on the socket.

            try { // Check for all other exceptions....

                try { // Check for underlying InterruptedException,
                      // SSLException and IOException

                    try{ // Check for PrivilegedActionException

                        clientSocket = 
                            (Socket) AccessController.doPrivileged(
                                 new PrivilegedExceptionAction() {
                                     public Object run() throws IOException
                                     {
                                         return serverSocket.accept();
                                     }
                                 }
                                 );

// GemStone changes BEGIN
                        if (parent.getKeepAlive()) {
                          final SystemProperties props = SystemProperties
                              .getServerInstance();
                          int defaultIdle = props.getInteger(Attribute.KEEPALIVE_IDLE,
                              SnappyTSocket.DEFAULT_KEEPALIVE_IDLE);
                          int defaultInterval = props.getInteger(Attribute.KEEPALIVE_INTVL,
                              SnappyTSocket.DEFAULT_KEEPALIVE_INTVL);
                          int defaultCount = props.getInteger(Attribute.KEEPALIVE_CNT,
                              SnappyTSocket.DEFAULT_KEEPALIVE_CNT);
                          ClientSharedUtils.setKeepAliveOptions(clientSocket,
                              null, defaultIdle, defaultInterval, defaultCount);
                        }
                        else {
                          clientSocket.setKeepAlive(false);
                        }
                        clientSocket.setTcpNoDelay(true);
                        /* (original code)
                        clientSocket.setKeepAlive(parent.getKeepAlive());
                        */
// GemStone changes END
                        
                        // Set time out: Stops DDMReader.fill() from
                        // waiting indefinitely when timeSlice is set.
                        if (timeSlice > 0)
                            clientSocket.setSoTimeout(timeSlice);
                        
                        //create a new Session for this socket
                        parent.addSession(clientSocket);
                        
                    } catch (PrivilegedActionException e) {
                        // Just throw the underlying exception
                        throw e.getException();
                    } // end inner try/catch block
                    
                } catch (InterruptedException ie) {
                    // This is a shutdown and we'll just exit the
                    // thread. NOTE: This is according to the logic
                    // before this rewrite. I am not convinced that it
                    // is allways the case, but will not alter the
                    // behaviour since it is not within the scope of
                    // this change (DERBY-2108).
                    return;

                } catch (javax.net.ssl.SSLException ssle) {
                    // SSLException is a subclass of
                    // IOException. Print stack trace and...
                    
                    parent.consoleExceptionPrintTrace(ssle);
                    
                    // ... we need to do a controlled shutdown of the
                    // server, since SSL for some reason will not
                    // work.
                    // DERBY-3537: circumvent any shutdown security checks
                    parent.directShutdownInternal();
                    
                    return; // Exit the thread
                    
                } catch (IOException ioe) {
                    // IOException causes this thread to stop.  No
                    // console error message if this was caused by a
                    // shutdown
                    synchronized (parent.getShutdownSync()) {
                        if (!parent.getShutdown()) {
                            parent.consoleExceptionPrintTrace(ioe);
                            if (clientSocket != null)
                                clientSocket.close();
                        }
                    }
                    return; // Exit the thread
                }
            } catch (Exception e) {
                // Catch and log all other exceptions
                
                parent.consoleExceptionPrintTrace(e);
                try {
                    if (clientSocket != null)
                        clientSocket.close();
                } catch (IOException closeioe)
                {
                    parent.consoleExceptionPrintTrace(closeioe);
                }
            } // end outer try/catch block
            
        } // end for(;;)
        
    }// end run()
}







