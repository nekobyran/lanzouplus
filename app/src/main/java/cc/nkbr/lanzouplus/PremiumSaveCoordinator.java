package cc.nkbr.lanzouplus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * In-memory scheduler for premium-cloud save jobs.
 *
 * <p>The coordinator owns only queue state. Network work is supplied by the
 * caller, which keeps credentials and Android objects out of this class. A
 * capacity failure blocks every not-yet-started unit for the same account and
 * leaves those units resumable instead of consuming them as failures.</p>
 */
final class PremiumSaveCoordinator {
  static final int MIN_PARALLELISM=1;
  static final int MAX_PARALLELISM=16;
  static final String STATE_WAITING="等待保存";
  static final String STATE_RUNNING="保存中";
  static final String STATE_PAUSED="已暂停";
  static final String STATE_CANCELLED="已取消";
  static final String STATE_COMPLETED="保存完成";
  static final String STATE_FAILED="保存失败";

  private PremiumSaveCoordinator(){}

  interface Operation {
    Outcome save(Request request,String account)throws Exception;
  }

  interface Listener {
    void changed(Task task,Snapshot snapshot);
    void capacityBlocked(Task task,Snapshot snapshot);
    void finished(Task task,Snapshot snapshot,List<Attempt> attempts);
  }

  static final class Request {
    final String key;
    final String title;
    final String url;
    final String password;
    final boolean direct;
    private String resolvedSaveUrl="";

    Request(String key,String title,String url,String password,boolean direct){
      this.key=clean(key);
      this.title=clean(title);
      this.url=clean(url);
      this.password=clean(password);
      this.direct=direct;
    }

    synchronized String resolvedSaveUrl(){return resolvedSaveUrl;}
    synchronized void rememberResolvedSaveUrl(String value){
      String clean=clean(value);
      if(!clean.isEmpty())resolvedSaveUrl=clean;
    }
  }

  static final class Outcome {
    final boolean saved;
    final boolean capacity;
    final int errorCode;
    final String message;

    private Outcome(boolean saved,boolean capacity,int errorCode,String message){
      this.saved=saved;
      this.capacity=capacity;
      this.errorCode=errorCode;
      this.message=clean(message);
    }

    static Outcome success(String message){return new Outcome(true,false,0,message);}
    static Outcome failure(int errorCode,String message){return new Outcome(false,false,errorCode,message);}
    static Outcome capacity(int errorCode,String message){return new Outcome(false,true,errorCode,message);}
  }

  static final class Attempt {
    final Request request;
    final String account;
    final Outcome outcome;

    Attempt(Request request,String account,Outcome outcome){
      this.request=request;
      this.account=account;
      this.outcome=outcome;
    }
  }

  static final class Snapshot {
    final long revision;
    final String state;
    final int total;
    final int done;
    final int succeeded;
    final int failed;
    final int active;
    final int pending;
    final int parallelism;
    final boolean paused;
    final boolean capacityBlocked;
    final boolean cancelled;
    final boolean finished;
    final String capacityAccount;
    final String blockedMessage;

    Snapshot(long revision,String state,int total,int done,int succeeded,int failed,int active,int pending,
             int parallelism,boolean paused,boolean capacityBlocked,boolean cancelled,
             boolean finished,String capacityAccount,String blockedMessage){
      this.revision=revision;this.state=state;
      this.total=total;
      this.done=done;
      this.succeeded=succeeded;
      this.failed=failed;
      this.active=active;
      this.pending=pending;
      this.parallelism=parallelism;
      this.paused=paused;
      this.capacityBlocked=capacityBlocked;
      this.cancelled=cancelled;
      this.finished=finished;
      this.capacityAccount=clean(capacityAccount);
      this.blockedMessage=clean(blockedMessage);
    }

    int percent(){return total<=0?100:(int)Math.min(100,done*100L/total);}
  }

  private static final class Unit {
    final Request request;
    final String account;
    final boolean probe;

    Unit(Request request,String account,boolean probe){
      this.request=request;
      this.account=account;
      this.probe=probe;
    }

    String key(){return request.key+'\n'+account;}
  }

  static final class Task {
    private final Object lock=new Object();
    private final ArrayDeque<Unit> pending=new ArrayDeque<>();
    private final List<Unit> capacityUnits=new ArrayList<>();
    private final List<Attempt> attempts=new ArrayList<>();
    private final Set<String> blockedAccounts=new LinkedHashSet<>();
    private final java.util.Map<String,String> blockedMessages=new java.util.HashMap<>();
    private final Set<String> usedAccounts=new LinkedHashSet<>();
    private final Set<String> successfulKeys=new HashSet<>();
    private final Operation operation;
    private final Listener listener;
    private final ExecutorService executor;
    private int parallelism;
    private int total;
    private int remaining;
    private int succeeded;
    private int failed;
    private int active;
    private boolean paused;
    private boolean cancelled;
    private boolean finished;
    private boolean capacityNotified;
    private boolean finishNotified;
    private long revision;
    private String state=STATE_WAITING;

    private Task(List<Request> requests,List<String> accounts,Set<String> plannedKeys,Set<String> completedKeys,int requestedParallelism,
                 Operation operation,Listener listener){
      this.operation=operation;
      this.listener=listener;
      parallelism=clampParallelism(requestedParallelism);
      ThreadFactory factory=r->{Thread thread=new Thread(r,"premium-save");thread.setDaemon(true);return thread;};
      executor=Executors.newFixedThreadPool(MAX_PARALLELISM,factory);
      LinkedHashSet<String> names=new LinkedHashSet<>();
      if(accounts!=null)for(String account:accounts)if(!clean(account).isEmpty())names.add(clean(account));
      if(requests!=null)for(Request request:requests)if(request!=null)for(String account:names){
        Unit unit=new Unit(request,account,false);if(plannedKeys!=null&&!plannedKeys.contains(unit.key()))continue;total++;usedAccounts.add(account);
        if(completedKeys!=null&&completedKeys.contains(unit.key())){succeeded++;successfulKeys.add(unit.key());}
        else pending.addLast(unit);
      }
      remaining=total-succeeded;
      if(total==0){finished=true;state=STATE_FAILED;}
      else if(remaining==0){finished=true;state=STATE_COMPLETED;}
      else state=STATE_RUNNING;
    }

    Snapshot snapshot(){synchronized(lock){return snapshotLocked();}}

    Set<String> usedAccounts(){synchronized(lock){return Collections.unmodifiableSet(new LinkedHashSet<>(usedAccounts));}}

    Set<String> capacityAccounts(){synchronized(lock){
      LinkedHashSet<String> out=new LinkedHashSet<>();
      for(Unit unit:capacityUnits)out.add(unit.account);
      return Collections.unmodifiableSet(out);
    }}

    Set<String> capacityRequestKeys(String account){synchronized(lock){LinkedHashSet<String> out=new LinkedHashSet<>();String target=clean(account);for(Unit unit:capacityUnits)if(unit.account.equals(target))out.add(unit.request.key);return Collections.unmodifiableSet(out);}}

    List<Attempt> attempts(){synchronized(lock){return Collections.unmodifiableList(new ArrayList<>(attempts));}}

    boolean pause(){
      Snapshot snapshot;
      synchronized(lock){
        if(finished||cancelled||paused)return false;
        paused=true;state=STATE_PAUSED;snapshot=eventSnapshotLocked();
      }
      notifyChanged(snapshot);
      return true;
    }

    boolean resume(){
      synchronized(lock){if(!capacityUnits.isEmpty())return retryCapacityLocked();}
      Snapshot snapshot;
      synchronized(lock){
        if(finished||cancelled||!paused)return false;
        paused=false;state=STATE_RUNNING;lock.notifyAll();snapshot=eventSnapshotLocked();pumpLocked();
      }
      notifyChanged(snapshot);
      return true;
    }

    boolean retryCapacity(){synchronized(lock){return retryCapacityLocked();}}

    private boolean retryCapacityLocked(){
      if(finished||cancelled||capacityUnits.isEmpty())return false;
      LinkedHashSet<String> probes=new LinkedHashSet<>();
      List<Unit> retained=new ArrayList<>();
      for(Unit unit:capacityUnits){
        if(probes.add(unit.account))pending.addFirst(new Unit(unit.request,unit.account,true));
        else retained.add(unit);
      }
      capacityUnits.clear();capacityUnits.addAll(retained);
      capacityNotified=false;paused=false;state=STATE_RUNNING;lock.notifyAll();
      Snapshot snapshot=eventSnapshotLocked();pumpLocked();notifyChanged(snapshot);
      return true;
    }

    boolean switchCapacityTo(String alternateAccount){
      String blocked; synchronized(lock){blocked=capacityUnits.isEmpty()?"":capacityUnits.get(0).account;}
      return switchCapacityTo(blocked,alternateAccount);
    }

    boolean switchCapacityTo(String blockedAccount,String alternateAccount){
      String blockedName=clean(blockedAccount);
      String alternate=clean(alternateAccount);
      if(blockedName.isEmpty()||alternate.isEmpty())return false;
      Snapshot snapshot;boolean shouldFinish=false,promptNext=false;
      synchronized(lock){
        if(finished||cancelled||capacityUnits.isEmpty())return false;
        List<Unit> moving=new ArrayList<>();
        for(java.util.Iterator<Unit> iterator=capacityUnits.iterator();iterator.hasNext();){Unit unit=iterator.next();if(unit.account.equals(blockedName)){moving.add(unit);iterator.remove();}}
        if(moving.isEmpty())return false;
        blockedAccounts.remove(blockedName);blockedMessages.remove(blockedName);usedAccounts.add(alternate);
        for(Unit unit:moving){
          Unit replacement=new Unit(unit.request,alternate,false);
          if(successfulKeys.contains(replacement.key())){
            remaining--;succeeded++;
          }else pending.addLast(replacement);
        }
        paused=false;capacityNotified=!capacityUnits.isEmpty();promptNext=capacityNotified;state=capacityUnits.isEmpty()?STATE_RUNNING:STATE_PAUSED;lock.notifyAll();
        shouldFinish=remaining==0;
        if(shouldFinish)finishLocked();else pumpLocked();
        snapshot=eventSnapshotLocked();
      }
      notifyChanged(snapshot);
      if(promptNext)notifyCapacity(snapshot);
      if(shouldFinish)notifyFinished(snapshot);
      return true;
    }

    boolean setParallelism(int value){
      Snapshot snapshot;
      synchronized(lock){
        if(finished||cancelled)return false;
        int next=clampParallelism(value);if(next==parallelism)return true;
        parallelism=next;snapshot=eventSnapshotLocked();pumpLocked();
      }
      notifyChanged(snapshot);
      return true;
    }

    boolean cancel(){
      Snapshot snapshot;
      synchronized(lock){
        if(finished||cancelled)return false;
        cancelled=true;finished=true;paused=false;state=STATE_CANCELLED;
        pending.clear();capacityUnits.clear();lock.notifyAll();snapshot=eventSnapshotLocked();
      }
      executor.shutdownNow();notifyChanged(snapshot);notifyFinished(snapshot);return true;
    }

    void abandon(){
      synchronized(lock){
        if(finished||cancelled)return;
        finished=true;paused=true;state=STATE_PAUSED;pending.clear();capacityUnits.clear();lock.notifyAll();
      }
      executor.shutdownNow();
    }

    private void pumpLocked(){
      while(!finished&&!cancelled&&!paused&&active<parallelism&&!pending.isEmpty()){
        Unit unit=pending.removeFirst();
        if(blockedAccounts.contains(unit.account)&&!unit.probe){capacityUnits.add(unit);continue;}
        active++;
        executor.execute(()->run(unit));
      }
    }

    private void run(Unit unit){
      Outcome outcome;
      try{outcome=operation.save(unit.request,unit.account);if(outcome==null)outcome=Outcome.failure(-1,"保存未返回结果");}
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();outcome=Outcome.failure(-1,"操作已中断");}
      catch(Exception error){outcome=Outcome.failure(-1,error.getMessage());}
      Snapshot snapshot;boolean firstCapacity=false,shouldFinish=false;
      synchronized(lock){
        active=Math.max(0,active-1);
        if(finished||cancelled)return;
        if(unit.probe&&!outcome.saved&&!outcome.capacity)outcome=Outcome.capacity(outcome.errorCode,outcome.message);
        attempts.add(new Attempt(unit.request,unit.account,outcome));
        if(outcome.capacity){
          blockedAccounts.add(unit.account);blockedMessages.put(unit.account,outcome.message);capacityUnits.add(new Unit(unit.request,unit.account,false));
          for(java.util.Iterator<Unit> iterator=pending.iterator();iterator.hasNext();){
            Unit queued=iterator.next();if(queued.account.equals(unit.account)){capacityUnits.add(new Unit(queued.request,queued.account,false));iterator.remove();}
          }
          state=STATE_PAUSED;
          if(!capacityNotified){capacityNotified=true;firstCapacity=true;}
        }else{
          remaining--;if(outcome.saved){succeeded++;successfulKeys.add(unit.key());}else failed++;
          if(unit.probe){
            blockedAccounts.remove(unit.account);blockedMessages.remove(unit.account);
            for(java.util.Iterator<Unit> iterator=capacityUnits.iterator();iterator.hasNext();){
              Unit blocked=iterator.next();if(blocked.account.equals(unit.account)){pending.addLast(new Unit(blocked.request,blocked.account,false));iterator.remove();}
            }
          }
          if(remaining==0){finishLocked();shouldFinish=true;}else if(!paused)state=capacityUnits.isEmpty()?STATE_RUNNING:STATE_PAUSED;
        }
        if(!finished)pumpLocked();snapshot=eventSnapshotLocked();
      }
      notifyChanged(snapshot);
      if(firstCapacity)notifyCapacity(snapshot);
      if(shouldFinish)notifyFinished(snapshot);
    }

    private void finishLocked(){
      if(finished)return;
      finished=true;paused=false;state=failed==0?STATE_COMPLETED:STATE_FAILED;
      pending.clear();capacityUnits.clear();lock.notifyAll();executor.shutdown();
    }

    private Snapshot snapshotLocked(){
      String account=capacityUnits.isEmpty()?"":capacityUnits.get(0).account,message=blockedMessages.get(account);
      return new Snapshot(revision,state,total,total-remaining,succeeded,failed,active,
          pending.size()+capacityUnits.size(),parallelism,paused||!capacityUnits.isEmpty(),!capacityUnits.isEmpty(),
          cancelled,finished,account,message);
    }

    private Snapshot eventSnapshotLocked(){revision++;return snapshotLocked();}

    private void notifyChanged(Snapshot snapshot){try{if(listener!=null)listener.changed(this,snapshot);}catch(RuntimeException ignored){}}
    private void notifyCapacity(Snapshot snapshot){try{if(listener!=null)listener.capacityBlocked(this,snapshot);}catch(RuntimeException ignored){}}
    private void notifyFinished(Snapshot snapshot){
      synchronized(lock){if(finishNotified)return;finishNotified=true;}
      try{if(listener!=null)listener.finished(this,snapshot,attempts());}catch(RuntimeException ignored){}
    }
  }

  static Task start(List<Request> requests,List<String> accounts,int parallelism,
                     Operation operation,Listener listener){
    return start(requests,accounts,null,Collections.emptySet(),parallelism,operation,listener);
  }

  static Task start(List<Request> requests,List<String> accounts,Set<String> completedKeys,int parallelism,
                    Operation operation,Listener listener){
    return start(requests,accounts,null,completedKeys,parallelism,operation,listener);
  }

  static Task start(List<Request> requests,List<String> accounts,Set<String> plannedKeys,Set<String> completedKeys,int parallelism,
                    Operation operation,Listener listener){
    if(operation==null)throw new IllegalArgumentException("operation");
    Task task=new Task(requests,accounts,plannedKeys,completedKeys,parallelism,operation,listener);
    Snapshot initial;
    synchronized(task.lock){initial=task.eventSnapshotLocked();task.pumpLocked();}
    task.notifyChanged(initial);
    if(initial.finished)task.notifyFinished(initial);
    return task;
  }

  static int clampParallelism(int value){return Math.max(MIN_PARALLELISM,Math.min(MAX_PARALLELISM,value));}

  static boolean indicatesCapacity(String message){
    String value=clean(message).toLowerCase(Locale.ROOT).replaceAll("\\s+","");
    if(value.isEmpty())return false;
    if(value.contains("quota")&&(value.contains("exceed")||value.contains("full")||value.contains("limit")))return true;
    if(value.contains("nospace")||value.contains("insufficientstorage")||value.contains("storagefull"))return true;
    boolean subject=value.contains("空间")||value.contains("容量")||value.contains("存储")||value.contains("储存");
    boolean exhausted=value.contains("不足")||value.contains("已满")||value.contains("不够")||value.contains("超出")||value.contains("用完")||value.contains("上限");
    return subject&&exhausted;
  }

  private static String clean(String value){return value==null?"":value.trim();}
}
