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

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Instances of this class are like {@link Timer}, but are associated
 * with a "swarm", which can be cancelled as a group with 
 * {@link #cancelSwarm(Object)}.
 * 
 * @see Timer
 * @see TimerTask
 * @author jpenney
 *
 * TODO -- with Java 1.5, this will be a template type so that the swarm's
 * class can be specified.
 */
public final class SystemTimer {

  /**
   * Extra debugging for this class
   */
//  private static final boolean DEBUG = true;
  static final boolean DEBUG = false;

  /**
   * This is how this timer logs debugging and/or warnings
   */
  protected final LogWriterI18n log;

  /**
   * the underlying {@link Timer}
   */
  final private Timer timer;
  
  /**
   * True if this timer has been cancelled
   */
  private boolean cancelled = false;
  
  /**
   * the swarm to which this timer belongs
   */
  final private Object /* T */ swarm;
  
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append("SystemTimer[");
    sb.append("swarm = " + swarm);
//    sb.append("; timer = " + timer);
    sb.append("]");
    return sb.toString();
  }
  
  /**
   * List of all of the swarms in the system
   * 
   * @guarded.By self
   */
  // <T, HashMap<Object, ArrayList<WeakReference<SystemTimer>>>>
  static private final HashMap allSwarms = new HashMap();
  
  /**
   * Add the given timer is in the given swarm.  Used only by constructors.
   * 
   * @param swarm swarm to add the timer to
   * @param t timer to add
   */
  static private void addToSwarm(Object /* T */ swarm, SystemTimer t) {
    // Get or add list of timers for this swarm...
    ArrayList /* ArrayList<WeakReference<SystemTimer>> */ swarmSet;
    synchronized (allSwarms) {
      swarmSet = (ArrayList)allSwarms.get(swarm);
      if (swarmSet == null) {
        if (DEBUG) {
          t.log.info(LocalizedStrings.DEBUG, "SystemTimer#addToSwarm: created swarm " + swarm);
        }
        swarmSet = new ArrayList();
        allSwarms.put(swarm, swarmSet);
      }
    } // synchronized
    
    // Add the timer to the swarm's list
    if (DEBUG) {
      t.log.info(LocalizedStrings.DEBUG, "SystemTimer#addToSwarm: adding timer <" + t + ">"
//          , new Exception()
          );
    }
    WeakReference /* WeakReference<SystemTimer> */ wr = new WeakReference(t);
    synchronized (swarmSet) {
      swarmSet.add(wr);
    } // synchronized
  }
  
  /**
   * time that the last sweep was done
   * 
   * @see #sweepAllSwarms(LogWriterI18n)
   */
  private static long lastSweepAllTime = 0;
  
  /**
   * Interval, in milliseconds, to sweep all swarms, measured from when
   * the last sweep finished
   * 
   * @see #sweepAllSwarms(LogWriterI18n)
   */
  private static final long SWEEP_ALL_INTERVAL = 2 * 60 * 1000; // 2 minutes
  
  /**
   * Manually garbage collect {@link #allSwarms}, if it hasn't happened
   * in a while.
   * 
   * @param l logger to use
   * @see #lastSweepAllTime
   */
  static private void sweepAllSwarms(LogWriterI18n l) {
    if (System.currentTimeMillis() < lastSweepAllTime + SWEEP_ALL_INTERVAL) {
      // Too soon.
      return;
    }
    synchronized (allSwarms) {
      Iterator it = allSwarms.entrySet().iterator();
      while (it.hasNext()) { // iterate over allSwarms
        Map.Entry entry = (Map.Entry)it.next();
        ArrayList swarm = (ArrayList)entry.getValue();
        synchronized (swarm) {
          Iterator it2 = swarm.iterator();
          while (it2.hasNext()) { // iterate over current swarm
            WeakReference wr = (WeakReference)it2.next();
            SystemTimer st = (SystemTimer)wr.get();
            if (st == null) {
              // Remove stale reference
              it2.remove();
              continue;
            }
            // Get rid of a cancelled timer; it's not interesting.
            if (st.cancelled) {
              it2.remove();
              continue;
            }
          } // iterate over current swarm
          if (swarm.size() == 0) { // Remove unused swarm
           it.remove(); 
           if (DEBUG) {
             l.info(LocalizedStrings.DEBUG, "SystemTimer#sweepAllSwarms: removed unused swarm" 
                 + entry.getKey());
           }
          } // Remove unused swarm
        } // synchronized swarm
      } // iterate over allSwarms
    } // synchronized allSwarms
    
    // Collect time at END of sweep.  It means an extra call to the system
    // timer, but makes this potentially less active.
    lastSweepAllTime = System.currentTimeMillis();
  }
  
  /**
   * Remove given timer from the swarm.
   * @param t timer to remove
   * 
   * @see #cancel()
   */
  static private void removeFromSwarm(SystemTimer t) {
    synchronized (allSwarms) {
      // Get timer's swarm
      ArrayList swarmSet = (ArrayList)allSwarms.get(t.swarm);
      if (swarmSet == null) {
        if (DEBUG) {
          t.log.info(LocalizedStrings.DEBUG, "SystemTimer#removeFromSwarm: timer already removed: " + t);
        }
        return; // already gone
      }
      
      // Remove timer from swarm
      if (DEBUG) {
        t.log.info(LocalizedStrings.DEBUG, "SystemTimer#removeFromSwarm: removing timer <" + t + ">");
      }
      synchronized (swarmSet) {
        Iterator it = swarmSet.iterator();
        while (it.hasNext()) {
          WeakReference ref = (WeakReference)it.next();
          SystemTimer t2 = (SystemTimer)ref.get();
          if (t2 == null) { 
            // Since we've discovered an empty reference, we should remove it.
            it.remove();
            continue;
          }
          if (t2 == t) {
            it.remove();
            // Don't keep sweeping once we've found it; just quit.
            break;
          }
          if (t2.cancelled) {
            // But if we happen to run across a cancelled timer,
            // remove it.
            it.remove();
            continue;
          }
        } // while
        
        // While we're here, if the swarm has gone to zero size, 
        // we should remove it.
        if (swarmSet.size() == 0) {
          allSwarms.remove(t.swarm); // last reference
          if (DEBUG) {
            t.log.info(LocalizedStrings.DEBUG, "SystemTimer#removeFromSwarm: removed last reference to " 
                + t.swarm);
          }
        }
      } // synchronized swarmSet
    } // synchronized allSwarms
    
    sweepAllSwarms(t.log); // Occasionally check global list, use any available logger :-)
  }
  
  /**
   * Cancel all outstanding timers
   * @param swarm the swarm to cancel
   */
  public static void cancelSwarm(Object /* T */ swarm) {
    Assert.assertTrue(swarm instanceof InternalDistributedSystem); // TODO
    // Find the swarmSet and remove it
    ArrayList swarmSet;
    synchronized (allSwarms) {
      swarmSet = (ArrayList)allSwarms.get(swarm);
      if (swarmSet == null) {
        return; // already cancelled
      }
      // Remove before releasing synchronization, so any fresh timer ends up
      // in a new set with same key
      allSwarms.remove(swarmSet);
    } // synchronized
    
    // Empty the swarmSet
    synchronized (swarmSet) {
      Iterator it = swarmSet.iterator();
      while (it.hasNext()) {
        WeakReference wr = (WeakReference)it.next();
        SystemTimer st = (SystemTimer)wr.get();
//        it.remove();  Not necessary, we're emptying the list...
        if (st != null) {
          st.cancelled = true; // for safety :-)
          st.timer.cancel(); // st.cancel() would just search for it again
        }
      } // while
    } // synchronized
  }

  public int timerPurge() {
    if (DEBUG) {
      log.info(LocalizedStrings.DEBUG, "SystemTimer#timerPurge of " + this);
    }
    return this.timer.purge();
  }

  // This creates a non-daemon timer thread.  We don't EVER do this...
//  /**
//   * @see Timer#Timer()
//   * 
//   * @param swarm the swarm this timer belongs to
//   */
//  public SystemTimer(DistributedSystem swarm) {
//    this.timer = new Timer();
//    this.swarm = swarm;
//    addToSwarm(swarm, this);
//  }

  /**
   * @see Timer#Timer(boolean)
   * @param swarm the swarm this timer belongs to, currently must be a DistributedSystem
   * @param isDaemon whether the timer is a daemon.  Must be true for GemFire use.
   * @param log logger to indicate problems with this timer
   */
  public SystemTimer(Object /* T */ swarm, boolean isDaemon, LogWriterI18n log) {
    Assert.assertTrue(isDaemon); // we don't currently allow non-daemon timers
    Assert.assertTrue(swarm instanceof InternalDistributedSystem, 
        "Attempt to create swarm on " + swarm); // TODO allow template class?
    this.timer = new Timer(isDaemon);
    this.swarm = swarm;
    this.log = log;
    addToSwarm(swarm, this);
  }

  /**
   * @param name the name to give the timer thread
   * @param swarm the swarm this timer belongs to, currently must be a DistributedMember
   * @param isDaemon whether the timer is a daemon.  Must be true for GemFire use.
   * @param log logger to indicate problems with this timer
   */
  public SystemTimer(String name, Object /* T */ swarm, boolean isDaemon, LogWriterI18n log) {
    Assert.assertTrue(isDaemon); // we don't currently allow non-daemon timers
    Assert.assertTrue(swarm instanceof InternalDistributedSystem, 
        "Attempt to create swarm on " + swarm); // TODO allow template class?
    this.timer = new Timer(name, isDaemon);
    this.swarm = swarm;
    this.log = log;
    addToSwarm(swarm, this);
  }

  private void checkCancelled() throws IllegalStateException {
    if (this.cancelled) {
      throw new IllegalStateException("This timer has been cancelled.");
    }
  }
  
  /**
   * @see Timer#schedule(TimerTask, long)
   */
  public void schedule(SystemTimerTask task, long delay) {
    checkCancelled();
    if (DEBUG) {
      Date tilt = new Date(System.currentTimeMillis() + delay);
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-mm-DD HH:mm:ss.SSS");
      log.info(LocalizedStrings.DEBUG, "SystemTimer#schedule (long): " + this + ": expect task " + task 
          + " to fire around " + sdf.format(tilt));
    }
    timer.schedule(task, delay);
  }

  /**
   * @see Timer#schedule(TimerTask, Date)
   */
  public void schedule(SystemTimerTask task, Date time) {
    checkCancelled();
    if (DEBUG) {
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-mm-DD HH:mm:ss.SSS");
      log.info(LocalizedStrings.DEBUG, "SystemTimer#schedule (Date): " + this + ": expect task " + task 
          + " to fire around " + sdf.format(time));
    }
    timer.schedule(task, time);
  }

  // Not currently used, so don't complicate things
//  /**
//   * @see Timer#schedule(TimerTask, long, long)
//   */
//  public void schedule(SystemTimerTask task, long delay, long period) {
//    // TODO add debug statement
//    checkCancelled();
//    timer.schedule(task, delay, period);
//  }

  // Not currently used, so don't complicate things
//  /**
//   * @see Timer#schedule(TimerTask, Date, long) 
//   */
//  public void schedule(SystemTimerTask task, Date firstTime, long period) {
//    // TODO add debug statement
//    checkCancelled();
//    timer.schedule(task, firstTime, period);
//  }

  /**
   * @see Timer#scheduleAtFixedRate(TimerTask, long, long)
   */
  public void scheduleAtFixedRate(SystemTimerTask task, long delay, long period) {
    // TODO add debug statement
    checkCancelled();
    timer.scheduleAtFixedRate(task, delay, period);
  }

  /**
   * @see Timer#schedule(TimerTask, long, long)
   */
  public void schedule(SystemTimerTask task, long delay, long period) {
    // TODO add debug statement
    checkCancelled();
    timer.schedule(task, delay, period);
  }

  // Not currently used, so don't complicate things
//  /**
//   * @see Timer#scheduleAtFixedRate(TimerTask, Date, long)
//   */
//  public void scheduleAtFixedRate(SystemTimerTask task, Date firstTime,
//                                  long period) {
//    // TODO add debug statement
//    checkCancelled();
//    timer.scheduleAtFixedRate(task, firstTime, period);
//  }


  /**
   * @see Timer#cancel()
   */
  public void cancel() {
    this.cancelled = true;
    timer.cancel();
    removeFromSwarm(this);
  }

  /**
   * Cover class to track behavior of scheduled tasks
   * 
   * @see TimerTask
   * @author jpenney
   */
  public abstract static class SystemTimerTask extends TimerTask {
    
    /**
     * the logger for this task
     * @return the logger
     */
    public abstract LogWriterI18n getLoggerI18n();
    
    /**
     * This is your executed action
     */
    public abstract void run2();
    
    /**
     * Does debug logging, catches critical errors, then delegates to
     * {@link #run2()}
     */
    @Override
    final public void run() {
      if (SystemTimer.DEBUG) {
        getLoggerI18n().info(LocalizedStrings.DEBUG, "SystemTimer.MyTask: starting " + this);
      }
      try {
        this.run2();
      }
      catch (CancelException ignore) {
        // ignore: TimerThreads can fire during or near cache closure
      }
      catch (VirtualMachineError e) {
        SystemFailure.initiateFailure(e);
        throw e;
      }
      catch (Throwable t) {
        SystemFailure.checkFailure();
        getLoggerI18n().warning(LocalizedStrings.SystemTimer_TIMER_TASK_0_ENCOUNTERED_EXCEPTION, this, t);
        // Don't rethrow, it will just get eaten and kill the timer
      }
      if (SystemTimer.DEBUG) {
        getLoggerI18n().info(LocalizedStrings.DEBUG, "SystemTimer.MyTask: finished " + this);
      }
    }
  }

}
