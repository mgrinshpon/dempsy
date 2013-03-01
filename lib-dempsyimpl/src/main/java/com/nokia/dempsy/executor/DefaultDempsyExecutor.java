package com.nokia.dempsy.executor;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultDempsyExecutor implements DempsyExecutor
{
   private static Logger logger = LoggerFactory.getLogger(DefaultDempsyExecutor.class);
   private ScheduledExecutorService schedule = null;
   private ThreadPoolExecutor executor = null;
   private AtomicLong numLimited = null;
   private long maxNumWaitingLimitedTasks = -1;
   private int threadPoolSize = -1;
   
   private final long executorCount;
   
   private static final int minNumThreads = 4;
   private static AtomicLong executorCountSequence = new AtomicLong(0);
   
   private double m = 1.25;
   private int additionalThreads = 2;
   private boolean unlimited = false;
   private boolean blocking = false;
   
   public DefaultDempsyExecutor() { executorCount = executorCountSequence.getAndIncrement(); }
   
   /**
    * Create a DefaultDempsyExecutor with a fixed number of threads while setting the
    * maximum number of limited tasks.
    */
   public DefaultDempsyExecutor(int threadPoolSize, int maxNumWaitingLimitedTasks) 
   {
      this.threadPoolSize = threadPoolSize;
      this.maxNumWaitingLimitedTasks = maxNumWaitingLimitedTasks;
      this.executorCount = executorCountSequence.getAndIncrement();
   }
   
   /**
    * <p>The DefaultDempsyExecutor can be set so that the maxNumWaitingLimitedTasks will be
    * ignored so that all submitted tasks, even when submitLimited is used, will be 
    * queued unbounded and will execute.</p>
    * 
    * <p>The default behavior is for the oldest limited tasks to be rejected when
    * the maxNumWaitingLimitedTasks is reached. In other words, the default is 
    * for unlimited = false.</p>
    */
   public void setUnlimited(boolean unlimited) { this.unlimited = unlimited; }
   
   /**
    * <p>If blocking is set to true then submitting limited tasks, once the 
    * maxNumWaitingLimitedTasks is reached, will block until room is available.</p>
    * 
    * <p>The default is {@code blocking = false}</p>
    * 
    * <p>If {@code unlimited} is {@code true} then this setting is effectively ignored.</p>
    */
   public void setBlocking(boolean blocking) { this.blocking = blocking; }
   
   /**
    * <p>Prior to calling start you can set the cores factor and additional
    * cores. Ultimately the number of threads in the pool will be given by:</p> 
    * 
    * <p>num threads = m * num cores + b</p>
    * 
    * <p>Where 'm' is set by setCoresFactor and 'b' is set by setAdditionalThreads</p>
    */
   public void setCoresFactor(double m){ this.m = m; }
   
   /**
    * <p>Prior to calling start you can set the cores factor and additional
    * cores. Ultimately the number of threads in the pool will be given by:</p> 
    * 
    * <p>num threads = m * num cores + b</p>
    * 
    * <p>Where 'm' is set by setCoresFactor and 'b' is set by setAdditionalThreads</p>
    */
   public void setAdditionalThreads(int additionalThreads){ this.additionalThreads = additionalThreads; }
   
   // milliseconds per thread
   private static final long namingTimeoutLimitFactorMillis = 100;
   
   @Override
   public void start()
   {
      if (threadPoolSize == -1)
      {
         // figure out the number of cores.
         int cores = Runtime.getRuntime().availableProcessors();
         int cpuBasedThreadCount = (int)Math.ceil((double)cores * m) + additionalThreads; // why? I don't know. If you don't like it 
                                                                                          //   then use the other constructor
         threadPoolSize = Math.max(cpuBasedThreadCount, minNumThreads);
      }
      executor = (ThreadPoolExecutor)Executors.newFixedThreadPool(threadPoolSize);
      String baseName = "DempsyExc-" + executorCount;
      new ThreadNamer(baseName, threadPoolSize, executor);
      schedule = Executors.newSingleThreadScheduledExecutor();
      new ThreadNamer(baseName + "-sched", 1, schedule);
      numLimited = new AtomicLong(0);
      
      if (maxNumWaitingLimitedTasks < 0)
         maxNumWaitingLimitedTasks = 20 * threadPoolSize;
   }
   
   public int getMaxNumberOfQueuedLimitedTasks() { return (int)maxNumWaitingLimitedTasks; }
   
   public void setMaxNumberOfQueuedLimitedTasks(int maxNumWaitingLimitedTasks) { this.maxNumWaitingLimitedTasks = maxNumWaitingLimitedTasks; }
   
   @Override
   public int getNumThreads() { return threadPoolSize; }
   
   @Override
   public void shutdown()
   {
      if (executor != null)
         executor.shutdown();
      
      if (schedule != null)
         schedule.shutdown();
      
      synchronized(numLimited) { numLimited.notifyAll(); }
   }

   @Override
   public int getNumberPending()
   {
      return executor.getQueue().size();
   }
   
   /**
    * How many {@link Rejectable}s passed to submitLimited are currently pending.
    * This will always return zero when the {@link DefaultDempsyExecutor}
    * is set to {@code unlimited}.
    */
   @Override
   public int getNumberLimitedPending()
   {
      return numLimited.intValue();
   }

   
   public boolean isRunning() { return (schedule != null && executor != null) &&
         !(schedule.isShutdown() || schedule.isTerminated()) &&
         !(executor.isShutdown() || executor.isTerminated()); }
   
   @Override
   public <V> Future<V> submit(Callable<V> r) { return executor.submit(r); }

   @Override
   public <V> Future<V> submitLimited(final Rejectable<V> r)
   {
      if (unlimited) return submit(r);
      
      Callable<V> task = new Callable<V>()
      {
         private Rejectable<V> o = r;

         @Override
         public V call() throws Exception
         {
            long num = numLimited.decrementAndGet();
            if (blocking) { synchronized(numLimited) { numLimited.notifyAll(); } }
            
            if (blocking || num <= maxNumWaitingLimitedTasks)
               return o.call();
            o.rejected();
            return null;
         }
      };
      
      if (blocking && (numLimited.get() > maxNumWaitingLimitedTasks))
      {
         synchronized(numLimited)
         {
            // check again.
            while (numLimited.get() >= maxNumWaitingLimitedTasks && isRunning())
            {
               try { numLimited.wait(); } catch (InterruptedException ie) {}
            }
         }
      }

      numLimited.incrementAndGet();
      
      try
      {
         Future<V> ret = executor.submit(task);
         return ret;
      }
      catch (RejectedExecutionException re)
      {
         numLimited.decrementAndGet();
         r.rejected();
         throw re;
      }
   }
   
   @Override
   public <V> Future<V> schedule(final Callable<V> r, long delay, TimeUnit unit)
   {
      // here we are going to wrap the Callable and the Future to change the 
      // submission to one of the other queues.
      // proxy the return future.
      return new ProxyFuture<V>(r, delay, unit);
   }

   @SuppressWarnings({"rawtypes","unchecked"})
   private class ProxyFuture<V> implements Future<V>, Runnable
   {
      private final AtomicReference<Future> ret;
      private boolean isScheduled = false;
      private final Callable<V> callable;
      
      private ProxyFuture(Callable<V> callable, long delay, TimeUnit unit)
      {
         this.callable = callable;
         this.ret = new AtomicReference<Future>(schedule.schedule(this,delay,unit));
      }
      
      @Override
      public void run()
      {
         // now resubmit the callable we're proxying
         set(submit(callable));
      }

      private void set(Future<V> f)
      {
         Future cur = ret.getAndSet(f);
         if (cur.isCancelled())
            f.cancel(true);
         synchronized(this)
         {
            isScheduled = true;
            this.notifyAll();
         }
      }
      
      @Override
      public boolean cancel(boolean mayInterruptIfRunning){ return ret.get().cancel(mayInterruptIfRunning); }

      @Override
      public boolean isCancelled() { return ret.get().isCancelled(); }

      @Override
      public boolean isDone() { return ret.get().isDone(); }

      @Override
      public synchronized V get() throws InterruptedException, ExecutionException 
      {
         while (!isScheduled)
            this.wait();
         return (V)ret.get().get();
      }

      @Override
      public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
      {
         long cur = System.currentTimeMillis();
         while (ret == null)
            this.wait(unit.toMillis(timeout));
         return (V)ret.get().get(System.currentTimeMillis() - cur,TimeUnit.MILLISECONDS);
      }
   }

   /**
    * Names threads in a thread pool. Only works for fixed size pools.
    */
   private class ThreadNamer implements Runnable
   {
      private final int threadCount;
      private final String baseName;
      private long sequence = 0;
      
      private ThreadNamer(String baseName, int threadCount, Executor executor)
      {
         this.threadCount = threadCount;
         this.baseName = baseName;
         for (int i = 0; i < threadCount; i++)
            executor.execute(this);
      }
      
      @Override
      public synchronized void run()
      {
         String threadName = baseName + "-" + sequence++;
         Thread.currentThread().setName(threadName);
         
         if (sequence >= threadCount)
            notifyAll();
         else
            // block until sequence gets to threadCount
            while (sequence < threadCount)
            {
               try { wait(namingTimeoutLimitFactorMillis * threadCount); } catch (InterruptedException ie) {}
            }
         
         if (sequence < threadCount)
            logger.error("Failed to set all of the name's for the " + 
                  executorCount + "'th " + DefaultDempsyExecutor.class.getSimpleName() + 
                  ". This is either a bug in the Dempsy code OR the JVM is under tremendous load.");
      }
   }
}
