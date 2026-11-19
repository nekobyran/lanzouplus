package cc.nkbr.lanzouplus;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Activity/会话级线程池工厂。页面销毁后迟到的 Handler、动画或网络回调仍会 submit，
 * 此时只丢弃任务，避免 ThreadPoolExecutor.AbortPolicy 抛出 RejectedExecutionException；
 * 线程池未关闭时的排队、饱和与拒绝行为保持不变。
 */
final class QuietExecutors {
  private QuietExecutors() {}

  static ThreadPoolExecutor fixed(int workers) {
    return fixed(workers, Executors.defaultThreadFactory());
  }

  static ThreadPoolExecutor fixed(int workers, ThreadFactory factory) {
    return new Pool(workers, workers, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
  }

  static ThreadPoolExecutor single(ThreadFactory factory) {
    return fixed(1, factory);
  }

  static ScheduledThreadPoolExecutor scheduled(int workers, ThreadFactory factory) {
    return new Scheduler(workers, factory);
  }

  static final class Pool extends ThreadPoolExecutor {
    Pool(int core, int max, long keepAlive, TimeUnit unit, BlockingQueue<Runnable> queue, ThreadFactory factory) {
      super(core, max, keepAlive, unit, queue, factory);
    }

    @Override public void execute(Runnable task) {
      try {
        super.execute(task);
      } catch (RejectedExecutionException rejected) {
        if (!isShutdown()) throw rejected;
      }
    }
  }

  static final class Scheduler extends ScheduledThreadPoolExecutor {
    Scheduler(int workers, ThreadFactory factory) {
      super(workers, factory);
    }

    @Override public void execute(Runnable task) {
      try {
        super.execute(task);
      } catch (RejectedExecutionException rejected) {
        if (!isShutdown()) throw rejected;
      }
    }

    @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
      try {
        return super.schedule(task, delay, unit);
      } catch (RejectedExecutionException rejected) {
        if (!isShutdown()) throw rejected;
        return new Dropped();
      }
    }

    private static final class Dropped extends FutureTask<Object> implements ScheduledFuture<Object> {
      Dropped() {
        super(() -> {}, null);
        cancel(false);
      }

      @Override public long getDelay(TimeUnit unit) { return 0L; }

      @Override public int compareTo(java.util.concurrent.Delayed other) { return 0; }
    }
  }
}
