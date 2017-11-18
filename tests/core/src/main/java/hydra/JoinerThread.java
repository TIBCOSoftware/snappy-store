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

package hydra;

import com.gemstone.gemfire.internal.LogWriterImpl;
import util.TestHelper;

/**
 *  A thread subclass whose instances contain ExceptionThreads.  Its run method
 *  runs and joins the ExceptionThread and processes any exceptions that occur
 *  simply by logging them.  The assumption is that all meaningful exceptions
 *  are handled by the provided Runnable target.
 */
public class JoinerThread extends Thread {

  /** The thread group in which joiner threads run */
  private static ThreadGroup group =
                LogWriterImpl.createThreadGroup( "Joiner Threads",
                                                  Log.getLogWriter().convertToLogWriterI18n());

  /** Holds the ExceptionThread on whose behalf this thread was created */
  private ExceptionThread thread;

  /** Creates an instance with the given name and target. */
  public JoinerThread( Runnable target, String name ) {
     super( group, target, name );
     this.thread = new ExceptionThread( target, name );
  }

  /**
   *  Runs and joins the exception thread.  Handles all exceptions that while
   *  running the target.  None should occur, so all we do is log them.
   */
  public void run() {
    try {
      this.thread.start();
      this.thread.join();
      Throwable t = this.thread.getException();
      if ( t != null ) {
        Log.getLogWriter().severe( TestHelper.getStackTrace( t ) );
      }
    } catch( InterruptedException e ) {
      Log.getLogWriter().severe( "Thread was interrupted" );
    }
  }
}
