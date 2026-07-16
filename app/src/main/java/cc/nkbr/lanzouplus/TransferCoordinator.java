package cc.nkbr.lanzouplus;

import java.util.*;

/** Independent admission gate for already-resolved transfers. Zero means unlimited. */
final class TransferCoordinator<T> {
  interface Starter<T> { void start(T task,Runnable completed); }
  private final ArrayDeque<T> ready=new ArrayDeque<>();
  private final int limit;
  private final Starter<T> starter;
  private int active;

  TransferCoordinator(int configured,Starter<T> starter){limit=effectiveLimit(configured);this.starter=starter;}
  static int effectiveLimit(int configured){return configured<=0?Integer.MAX_VALUE:Math.max(1,configured);}

  void enqueue(T task){List<T> start;synchronized(this){ready.addLast(task);start=acquireLocked();}start(start);}
  boolean remove(T task){synchronized(this){return ready.remove(task);}}
  private void completed(){List<T> start;synchronized(this){active=Math.max(0,active-1);start=acquireLocked();}start(start);}
  private List<T> acquireLocked(){List<T> out=new ArrayList<>();while(active<limit&&!ready.isEmpty()){active++;out.add(ready.removeFirst());}return out;}
  private void start(List<T> tasks){for(T task:tasks)try{starter.start(task,this::completed);}catch(RuntimeException error){completed();}}
  synchronized int activeCount(){return active;}
  synchronized int pendingCount(){return ready.size();}
}
