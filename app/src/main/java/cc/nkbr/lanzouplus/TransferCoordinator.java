package cc.nkbr.lanzouplus;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent admission gate for already-resolved transfers. Zero means memory-adaptive unlimited mode. */
final class TransferCoordinator<T> {
  interface Starter<T> { void start(T task,Runnable completed); }
  private static final long MIB=1024L*1024L;
  private static final int MIN_ADAPTIVE=8;
  private final ArrayDeque<T> ready=new ArrayDeque<>();
  private final int limit;
  private final Starter<T> starter;
  private int active;
  private boolean draining;

  TransferCoordinator(int configured,Starter<T> starter){limit=effectiveLimit(configured);this.starter=starter;}
  static int adaptiveUnlimitedLimit(){long heap=Runtime.getRuntime().maxMemory();long byHeap=heap/(8L*MIB);return (int)Math.max(MIN_ADAPTIVE,byHeap);}
    static int effectiveLimit(int configured){int adaptive=adaptiveUnlimitedLimit(),desired=configured<=0?adaptive:Math.max(1,configured);return Math.max(1,Math.min(desired,adaptive));}

  void enqueue(T task){boolean run;synchronized(this){ready.addLast(task);run=!draining;if(run)draining=true;}if(run)drain();}
  boolean remove(T task){synchronized(this){return ready.remove(task);}}
  private void completed(AtomicBoolean once){if(!once.compareAndSet(false,true))return;boolean run;synchronized(this){active=Math.max(0,active-1);run=!draining;if(run)draining=true;}if(run)drain();}
  private void drain(){
    for(;;){
      List<T> tasks=new ArrayList<>();
      synchronized(this){while(active<limit&&!ready.isEmpty()){active++;tasks.add(ready.removeFirst());}if(tasks.isEmpty()){draining=false;return;}}
      for(T task:tasks){AtomicBoolean once=new AtomicBoolean();try{starter.start(task,()->completed(once));}catch(RuntimeException error){completed(once);}}
    }
  }
  synchronized int activeCount(){return active;}
  synchronized int pendingCount(){return ready.size();}
}
