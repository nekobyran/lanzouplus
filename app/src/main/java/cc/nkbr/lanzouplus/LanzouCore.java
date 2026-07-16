package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

final class LanzouCore {
  private static final String ANDROID_UA="Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 Chrome/138 Mobile Safari/537.36";
  private static final String DESKTOP_SEARCH_UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
  private static final int[] POS={15,35,29,24,33,16,1,38,10,9,19,31,40,27,22,23,25,13,6,11,39,18,20,8,14,21,32,26,2,30,7,4,17,5,3,28,34,37,12,36};
  private static final String MASK="3000176000856006061501533003690027800375";
  private static final long NO_DEADLINE=Long.MAX_VALUE;
  private static final byte UA_UNKNOWN=-1,UA_ANDROID=0,UA_DESKTOP=1;
  private static final byte TEMPLATE_UNKNOWN=0,TEMPLATE_MINIMAL=1,TEMPLATE_CLASSIC=2;
  // Template/UA defaults are replaced only when a runtime HTML template changes;
  // zt=4 backoff therefore remains learned for the rest of that source session.
  private static final int MINIMAL_ANDROID_INITIAL_PAGE_INTERVAL_MS=1800,MINIMAL_ANDROID_NEXT_PAGE_INTERVAL_MS=500;
  private static final int MINIMAL_DESKTOP_INITIAL_PAGE_INTERVAL_MS=2100,MINIMAL_DESKTOP_NEXT_PAGE_INTERVAL_MS=1000;
  private static final int CLASSIC_REFLOW_ANDROID_INITIAL_PAGE_INTERVAL_MS=2100,CLASSIC_REFLOW_ANDROID_NEXT_PAGE_INTERVAL_MS=800;
  private static final int CLASSIC_ANDROID_INITIAL_PAGE_INTERVAL_MS=1800,CLASSIC_ANDROID_NEXT_PAGE_INTERVAL_MS=500;
  private static final int CLASSIC_DESKTOP_INITIAL_PAGE_INTERVAL_MS=1800,CLASSIC_DESKTOP_NEXT_PAGE_INTERVAL_MS=500;
  private static final int UNKNOWN_INITIAL_PAGE_INTERVAL_MS=2100,UNKNOWN_NEXT_PAGE_INTERVAL_MS=1000;
  private static final int MAX_PAGE_INTERVAL_MS=8400,MAX_BROWSE_SESSIONS=64,MAX_PROBE_WORKERS=32,COMPOSITE_WARM_WORKERS=32;
  private static final long SESSION_TTL_MS=5*60*1000L;
  private static final long SOURCE_PROBE_TIMEOUT_MS=35*1000L,SOURCE_PROBE_UA_GAP_MS=2100L;
  private static final int PAGE_SIZE=50;
  private static final int DIRECT_INITIAL_WAIT_MS=1900,DIRECT_RETRY_WAIT_MS=250;
  private static final String FOLDER_MARKER="#lanzou-folder=";
  private static final String LOCAL_NODE_MARKER="#local-node=";
  private static final String USER_SOURCE_PREFS="user-sources-v1";
  private static final String USER_SOURCE_KEY="sources";
  private static final String REMOVED_SOURCE_KEY="removed";
  private static final String COMPOSITE_MEMBER_PREFS="composite-members-v1";
  private static final String CANONICAL_SOURCE_ORIGIN="https://www.lanzouw.com";
  private static final int RULE_LIMIT=512*1024;
  private static final Pattern ICON_DATE=Pattern.compile("(?:^|/)(\\d{4})/(\\d{2})/(\\d{2})(?:/|$)");
  private static final Pattern INVALID_FILE_NAME=Pattern.compile("[\\\\/:*?\"<>|]");
  private static final Pattern LANZOU_TYPO_HOST=Pattern.compile("(?:[a-z0-9-]+\\.)*laozouw\\.com");
  private static final Pattern LANZOU_HOST=Pattern.compile("(?i)(?:[a-z0-9-]+\\.)*lanzou[a-z0-9]?\\.com");
  private static final ConcurrentHashMap<String,Pattern> PARSE_PATTERNS=new ConcurrentHashMap<>();
  private static final Object PAGE_RATE_LOCK=new Object();
  private static final Object USER_SOURCE_LOCK=new Object();
  private static final Object BUILT_IN_SOURCE_LOCK=new Object();
  private static final Map<String,Long> NEXT_PAGE_AT=new HashMap<>();
  private static final ThreadLocal<BooleanSupplier> ASYNC_PAGE_CANCEL=new ThreadLocal<>();
  private static final ThreadLocal<ConnectionTracker> SEARCH_CONNECTIONS=new ThreadLocal<>();
  /**
   * In-process source generations.  A re-test reserves one generation before
   * doing network I/O, then applies its result only if no newer add/remove/
   * import/re-test action has touched the same normalized URL.
   */
  private static final Map<String,Long> SOURCE_REVISIONS=new HashMap<>();
  private static long sourceRevisionSequence;
  private static final ConcurrentHashMap<String,SourceProfile> SOURCE_PROFILES=new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String,Models.Source> SOURCE_OVERRIDES=new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String,Models.SourceMember> COMPOSITE_MEMBER_CACHE=new ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicBoolean COMPOSITE_WARM_RUNNING=new java.util.concurrent.atomic.AtomicBoolean(),COMPOSITE_WARM_REQUESTED=new java.util.concurrent.atomic.AtomicBoolean();
  private static volatile List<Models.Source> BUILT_IN_SOURCE_CACHE;
  private static volatile byte[] BUILT_IN_SOURCE_HINTS;
  private final Context context;
  private final Object sourceNameIndexLock=new Object();
  private volatile List<SourceNameEntry> sourceNameIndex;
  private volatile int sourceNameIndexRevision;
  private final ConcurrentHashMap<String,DirectLink> browseSessions=new ConcurrentHashMap<>();
  private final ThreadPoolExecutor searchPool=newSearchPool();
  private final ThreadPoolExecutor compositeWarmPool=newCompositeWarmPool();
  private final ScheduledThreadPoolExecutor searchScheduler=newSearchScheduler();
  LanzouCore(Context c){context=c.getApplicationContext();loadPersistedCompositeMembers();searchPool.execute(()->{try{sourceNameIndex();}catch(Exception ignored){}});startCompositeWarmup();}
  static final class DirectLink { String url,fileName,html,cookie,rootUrl,folderId,title,description,endpoint,folderEndpoint,fid;DirectLink target,page;Map<String,String> form;Models.Folder metadata;long createdAt;boolean authorized;byte ua,template; }
  private static final class SourceNameEntry{final Models.Source source;final Models.SourceMember member;final String id,folded;SourceNameEntry(Models.Source source,Models.SourceMember member,String id,String folded){this.source=source;this.member=member;this.id=id;this.folded=folded;}}
  static final class SourceRetestPlan { final Models.Source source;final String key;final boolean userSource;final long revision;SourceRetestPlan(Models.Source source,String key,boolean userSource,long revision){this.source=source;this.key=key;this.userSource=userSource;this.revision=revision;} }
  private static final class SourceRetestUnit{final SourceRetestPlan plan;final int memberIndex;SourceRetestUnit(SourceRetestPlan plan,int memberIndex){this.plan=plan;this.memberIndex=memberIndex;}}
  private static final class SourceRetestUnitResult{final SourceRetestUnit unit;final Models.Source official;final Models.SourceMember member;final String error;SourceRetestUnitResult(SourceRetestUnit unit,Models.Source official,Models.SourceMember member,String error){this.unit=unit;this.official=official;this.member=member;this.error=error;}boolean success(){return error.isEmpty();}}
  private static final class SourceRetestAggregate{final SourceRetestPlan plan;final Models.Source value;int remaining,failures;SourceRetestAggregate(SourceRetestPlan plan,int remaining){this.plan=plan;this.value=copySource(plan.source);this.remaining=remaining;}}
  static final class SourceProfile{
    volatile byte directoryUa=UA_UNKNOWN,searchUa=UA_UNKNOWN,directorySeen,directoryAvailable,searchSeen,searchAvailable,androidTemplate=TEMPLATE_UNKNOWN,desktopTemplate=TEMPLATE_UNKNOWN;
    private int androidInitial=UNKNOWN_INITIAL_PAGE_INTERVAL_MS,androidNext=UNKNOWN_NEXT_PAGE_INTERVAL_MS,desktopInitial=UNKNOWN_INITIAL_PAGE_INTERVAL_MS,desktopNext=UNKNOWN_NEXT_PAGE_INTERVAL_MS;
    SourceProfile(){}SourceProfile(int hint){applyHint(hint);}
    synchronized void applyHint(int hint){int directory=hint&3,search=hint>>2&3;if(directoryUa==UA_UNKNOWN&&directory!=0)directoryUa=directory==2?UA_DESKTOP:UA_ANDROID;if(searchUa==UA_UNKNOWN&&search!=0)searchUa=search==2?UA_DESKTOP:UA_ANDROID;observeTemplate(UA_ANDROID,(byte)(hint>>4&3));observeTemplate(UA_DESKTOP,(byte)(hint>>6&3));}
    synchronized void observeTemplate(byte ua,byte template){if(template!=TEMPLATE_MINIMAL&&template!=TEMPLATE_CLASSIC)return;if(ua==UA_DESKTOP){if(desktopTemplate==template)return;desktopTemplate=template;}else{if(androidTemplate==template)return;androidTemplate=template;}refreshIntervals();}
    private void refreshIntervals(){androidInitial=templateInitial(UA_ANDROID,androidTemplate,desktopTemplate);androidNext=templateNext(UA_ANDROID,androidTemplate,desktopTemplate);desktopInitial=templateInitial(UA_DESKTOP,desktopTemplate,androidTemplate);desktopNext=templateNext(UA_DESKTOP,desktopTemplate,androidTemplate);}
    synchronized int interval(byte ua,int page){return ua==UA_DESKTOP?(page<=1?desktopInitial:desktopNext):(page<=1?androidInitial:androidNext);}
    synchronized int backoff(byte ua,int page){int value=interval(ua,page),next=Math.min(MAX_PAGE_INTERVAL_MS,Math.max(value+300,value*2));if(ua==UA_DESKTOP){if(page<=1)desktopInitial=next;else desktopNext=next;}else if(page<=1)androidInitial=next;else androidNext=next;return next;}
  }
  private static final class PageResult{final Models.Folder folder;final boolean usable;PageResult(Models.Folder folder,boolean usable){this.folder=folder;this.usable=usable;}}
  private static final class UaProbe{Models.Folder folder;DirectLink session;Exception error;long elapsed;int items;boolean directory,search;}
  private static final class ShareCancelledException extends IOException{ShareCancelledException(){super("分享已取消");}}
  private static final class PageRateLimitedException extends IOException{PageRateLimitedException(String message){super(message);}}
  private static final class PageWaitException extends IOException{final long delayMillis;PageWaitException(long delayMillis){super("目录请求等待调度");this.delayMillis=delayMillis;}}
  private static final class SearchApiPage{DirectLink page;JSONArray items;int state,count,total=-1;boolean available,used;}
  private static final class SourceSearchState{
    final Models.Source source;final Models.SourceMember hydrate;final List<Models.Item> local;final String needle,foldedNeedle,groupKey,displaySource;final LinkedHashMap<String,Models.Item> merged=new LinkedHashMap<>();final ArrayDeque<Models.Item> folders=new ArrayDeque<>();final Set<String> folderKeys=new HashSet<>(),pageFingerprints=new HashSet<>();
    boolean remoteAvailable,remoteUsed,localEmitted,apiDone,apiQueued,apiInFlight,dirDone,dirFailed,dirQueued,dirInFlight,dirReady=true,replayed;volatile boolean active,terminal;int page=1,folderApiPage,firstApiFolderCount;byte directoryUa=UA_UNKNOWN;Models.Item folder;DirectLink directorySession;ScheduledFuture<?> pageFuture;
    SourceSearchState(Models.Source source,String query,List<Models.Item> local){this(source,query,local,sourceId(source),source.title);}
    SourceSearchState(Models.Source source,String query,List<Models.Item> local,String group){this(source,query,local,group,source.title);}
    SourceSearchState(Models.Source source,String query,List<Models.Item> local,String group,String displaySource){this.source=source;groupKey=group;this.displaySource=firstNonEmpty(displaySource,source.title);hydrate=null;this.local=local;needle=query==null?"":query.trim();foldedNeedle=needle.toLowerCase(Locale.ROOT);boolean composite=source.kind==Models.SOURCE_COMPOSITE;apiDone=composite||source.kind==Models.SOURCE_SINGLE||!source.searchable;if(source.kind==Models.SOURCE_SINGLE){dirDone=true;return;}if(composite){dirDone=true;return;}Models.Item root=new Models.Item();root.folder=true;root.title=source.title;root.url=root.shareUrl=source.url;root.password=source.password==null?"":source.password;root.source=this.displaySource;folders.add(root);folderKeys.add(searchFolderKey(root.url));}
    SourceSearchState(Models.Source owner,Models.SourceMember member,String query){source=owner;groupKey=sourceId(owner);displaySource=owner.title;hydrate=copyMember(member);Models.SourceMember cached=effectiveCompositeMember(member);local=cached.title.isEmpty()?Collections.emptyList():Collections.singletonList(itemFromMember(cached,displaySource));needle=query==null?"":query.trim();foldedNeedle=foldSearchQuery(needle);apiDone=local.isEmpty()||!matches(local.get(0),foldedNeedle);dirDone=true;}
  }
  private static String foldSearchQuery(String value){return value.toLowerCase(Locale.ROOT);}
  static final class ImportResult { final int added,duplicates,invalid;ImportResult(int added,int duplicates,int invalid){this.added=added;this.duplicates=duplicates;this.invalid=invalid;} }
  static final class AddResult {final int line;final String url;final Models.Source source;final String message;final boolean added,duplicate;AddResult(int line,String url,Models.Source source,String message,boolean added,boolean duplicate){this.line=line;this.url=url;this.source=source==null?null:copySource(source);this.message=message==null?"":message;this.added=added;this.duplicate=duplicate;}}
  static final class AddBatchResult {final int added,duplicates,failed,childAdded,childDuplicates,childEmpty,childFailed;final List<AddResult> lines;AddBatchResult(int added,int duplicates,int failed,int childAdded,int childDuplicates,int childEmpty,int childFailed,List<AddResult> lines){this.added=added;this.duplicates=duplicates;this.failed=failed;this.childAdded=childAdded;this.childDuplicates=childDuplicates;this.childEmpty=childEmpty;this.childFailed=childFailed;this.lines=Collections.unmodifiableList(lines);}}
  private static final class BatchSource {final int line;String url,password,error;Models.Source source;boolean added,duplicate,child;BatchSource(int line){this.line=line;}}
  private static final class ChildDiscovery {final List<BatchSource> rows=new ArrayList<>();int empty,failed;}
  private static final class FolderSeed {final Models.Item item;final String path;final boolean root;FolderSeed(Models.Item item,String path,boolean root){this.item=item;this.path=path;this.root=root;}}
  private interface ItemBatch { void accept(List<Models.Item> items); }
  private interface SearchPageBatch { void accept(List<Models.Item> items,int page,int pageItems,int sourceFound); }
  private interface ConnectionTracker { void opened(HttpURLConnection connection);void closed(HttpURLConnection connection); }
  private static final class SearchCancelled extends IOException {}
  private static final class BatchBudget{
    private final long deadlineNanos;
    BatchBudget(long timeoutMillis){deadlineNanos=timeoutMillis==0?NO_DEADLINE:System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);}
    long deadline(){return deadlineNanos;}
  }

  private static final class SearchNetworkJob{final SourceSearchState state;final boolean api;SearchNetworkJob(SourceSearchState state,boolean api){this.state=state;this.api=api;}}

  /** Lightweight source-session scheduler; only actual HTTP work reaches searchPool. */
  private final class SearchCoordinator implements ConnectionTracker{
    final List<SourceSearchState> states;final LinkedHashMap<String,List<SourceSearchState>> groups=new LinkedHashMap<>();final ArrayDeque<String> waitingGroups=new ArrayDeque<>();final ArrayDeque<SourceSearchState> apiReady=new ArrayDeque<>(),directoryReady=new ArrayDeque<>();final Map<String,Integer> remaining=new HashMap<>();final Set<String> activeGroups=new HashSet<>(),failedGroups=new HashSet<>();final Map<String,ScheduledFuture<?>> rotations=new HashMap<>();final List<Models.Item> out;final Set<String> seen;final Models.SearchOptions options;final Models.Progress progress;final int total,maxActive,matchedCompositeLeafCount,httpLimit;final java.util.concurrent.atomic.AtomicInteger done=new java.util.concurrent.atomic.AtomicInteger(),found=new java.util.concurrent.atomic.AtomicInteger(),pagesSeen=new java.util.concurrent.atomic.AtomicInteger();
    final Set<Future<?>> httpFutures=Collections.newSetFromMap(new IdentityHashMap<>());final Set<HttpURLConnection> openConnections=Collections.newSetFromMap(new IdentityHashMap<>());final Object resultLock=new Object();
    int active,runningHttp,apiBurst;volatile boolean paused,cancelled;
    SearchCoordinator(List<SourceSearchState> states,List<Models.Item> out,Set<String> seen,Models.SearchOptions options,Models.Progress progress){this.states=states;this.out=out;this.seen=seen;this.options=options;this.progress=progress;int matched=0;for(SourceSearchState state:states){if(options.apiOnly&&state.hydrate==null&&state.source.kind!=Models.SOURCE_COMPOSITE){state.dirDone=true;state.folders.clear();}groups.computeIfAbsent(state.groupKey,key->new ArrayList<>()).add(state);if(state.hydrate!=null&&!state.apiDone)matched++;}for(Map.Entry<String,List<SourceSearchState>> entry:groups.entrySet()){waitingGroups.addLast(entry.getKey());remaining.put(entry.getKey(),entry.getValue().size());}total=groups.size();maxActive=Math.max(1,Math.min(options.concurrency,total));matchedCompositeLeafCount=matched;httpLimit=Math.max(1,maxActive+Math.min(matchedCompositeLeafCount,COMPOSITE_WARM_WORKERS));}
    List<Models.Item> run()throws InterruptedException{
      boolean interrupted=false;
      try{synchronized(this){refillActiveLocked();pumpNetworkLocked();}while(true){if(isSearchCancelled(progress)){synchronized(this){cancelLocked();}break;}if(progress!=null){synchronized(this){if(cancelled||done.get()>=total)break;paused=true;}boolean resume;try{resume=progress.awaitIfPaused();}catch(RuntimeException ignored){resume=false;}synchronized(this){paused=false;if(!resume){cancelLocked();break;}refillActiveLocked();pumpNetworkLocked();}}synchronized(this){if(cancelled||done.get()>=total)break;wait(50L);pumpNetworkLocked();}}}catch(InterruptedException error){interrupted=true;synchronized(this){cancelLocked();}}
      synchronized(this){cancelTimersLocked();while(runningHttp>0)try{wait(50L);}catch(InterruptedException error){interrupted=true;cancelLocked();}}
      if(interrupted)throw new InterruptedException();return out;
    }
    private boolean stopped(SourceSearchState state){return cancelled||!state.active||isSearchCancelled(progress);}
    private void refillActiveLocked(){if(paused)return;String current="";boolean changed=false;while(!cancelled&&active<maxActive&&!waitingGroups.isEmpty()){String key=waitingGroups.removeFirst();if(activeGroups.contains(key)||remaining.getOrDefault(key,0)<=0)continue;List<SourceSearchState> members=groups.get(key);if(members==null||members.isEmpty())continue;activeGroups.add(key);active++;changed=true;current=members.get(0).source.title;for(SourceSearchState state:members){if(state.terminal)continue;state.active=true;emitLocalLocked(state);if(state.folder==null&&!state.dirDone)startNextFolderLocked(state);queueApiLocked(state);queueDirectoryLocked(state);finishSourceLocked(state,false);}scheduleRotationLocked(key);}if(changed)publishActivityLocked(current);}
    private void emitLocalLocked(SourceSearchState state){if(state.localEmitted)return;state.localEmitted=true;List<Models.Item> batch=new ArrayList<>();synchronized(state.merged){for(Models.Item item:state.local){item.source=state.displaySource;if(matches(item,state.foldedNeedle)&&state.merged.putIfAbsent(item.url,item)==null)batch.add(item);}}publishBatch(state,batch);}
    private void startNextFolderLocked(SourceSearchState state){if(state.folder!=null||state.dirDone)return;if(state.folders.isEmpty()){state.dirDone=true;finishSourceLocked(state,false);return;}state.folder=state.folders.removeFirst();state.directorySession=null;state.directoryUa=sourceProfile(state.folder.url).directoryUa;if(state.directoryUa==UA_UNKNOWN)state.directoryUa=UA_ANDROID;state.page=1;state.folderApiPage=0;state.firstApiFolderCount=0;state.replayed=false;state.pageFingerprints.clear();state.dirReady=true;}
    private void queueApiLocked(SourceSearchState state){if(state.active&&!state.terminal&&!state.apiDone&&!state.apiQueued&&!state.apiInFlight){state.apiQueued=true;apiReady.addLast(state);}}
    private void queueDirectoryLocked(SourceSearchState state){if(state.active&&!state.terminal&&!state.dirDone&&state.dirReady&&!state.dirQueued&&!state.dirInFlight){state.dirQueued=true;directoryReady.addLast(state);}}
    private SearchNetworkJob pollNetworkLocked(){while(true){boolean takeApi=!apiReady.isEmpty()&&(directoryReady.isEmpty()||apiBurst<3);ArrayDeque<SourceSearchState> queue=takeApi?apiReady:directoryReady;if(queue.isEmpty()){queue=takeApi?directoryReady:apiReady;takeApi=!takeApi;}SourceSearchState state=queue.pollFirst();if(state==null)return null;if(takeApi)state.apiQueued=false;else state.dirQueued=false;if(!state.active||state.terminal||takeApi&&(state.apiDone||state.apiInFlight)||!takeApi&&(state.dirDone||state.dirInFlight||!state.dirReady))continue;if(takeApi){state.apiInFlight=true;apiBurst++;}else{state.dirInFlight=true;state.dirReady=false;apiBurst=0;}return new SearchNetworkJob(state,takeApi);}}
    private void pumpNetworkLocked(){if(cancelled||paused)return;while(runningHttp<httpLimit){SearchNetworkJob job=pollNetworkLocked();if(job==null)return;SearchHttpTask task=new SearchHttpTask(job);runningHttp++;httpFutures.add(task);try{searchPool.execute(task);}catch(RejectedExecutionException error){task.cancel(false);return;}}}
    private final class SearchHttpTask extends FutureTask<Void>{final SearchNetworkJob job;final java.util.concurrent.atomic.AtomicBoolean started;SearchHttpTask(SearchNetworkJob job){this(job,new java.util.concurrent.atomic.AtomicBoolean());}private SearchHttpTask(SearchNetworkJob job,java.util.concurrent.atomic.AtomicBoolean started){super(()->{started.set(true);executeNetwork(job);return null;});this.job=job;this.started=started;}@Override protected void done(){synchronized(SearchCoordinator.this){httpFutures.remove(this);if(!started.get())abandonNetworkLocked(job);SearchCoordinator.this.notifyAll();}}}
    private void abandonNetworkLocked(SearchNetworkJob job){runningHttp--;if(job.api){job.state.apiInFlight=false;queueApiLocked(job.state);}else{job.state.dirInFlight=false;job.state.dirReady=true;queueDirectoryLocked(job.state);}refillActiveLocked();pumpNetworkLocked();}
    private void executeNetwork(SearchNetworkJob job){SEARCH_CONNECTIONS.set(this);try{if(job.api)executeApi(job.state);else executeDirectory(job.state);}finally{SEARCH_CONNECTIONS.remove();synchronized(this){runningHttp--;refillActiveLocked();pumpNetworkLocked();notifyAll();}}}
    @Override public void opened(HttpURLConnection connection){boolean close;synchronized(this){close=cancelled;if(!close)openConnections.add(connection);}if(close)connection.disconnect();}
    @Override public void closed(HttpURLConnection connection){synchronized(this){openConnections.remove(connection);}}
    private void executeApi(SourceSearchState state){boolean completed=false,failed=false;try{if(state.hydrate!=null)hydrateCompositeSearchState(state);else searchSourceApi(state,new BatchBudget(0),items->publishBatch(state,items),(items,page,pageItems,sourceFound)->publishPage(state,items,page,pageItems,sourceFound),()->stopped(state));completed=true;}catch(SearchCancelled ignored){}catch(Exception ignored){completed=true;failed=true;}synchronized(this){state.apiInFlight=false;if(completed)state.apiDone=true;else queueApiLocked(state);if(failed&&state.hydrate!=null)state.dirFailed=true;finishSourceLocked(state,failed);}}
    private void hydrateCompositeSearchState(SourceSearchState state)throws Exception{if(stopped(state))throw new SearchCancelled();Models.SourceMember live=hydrateCompositeIcon(state.hydrate);if(stopped(state))throw new SearchCancelled();Models.Item item=itemFromMember(live,state.displaySource);item.sourceId=state.groupKey;List<Models.Item> batch=new ArrayList<>(1);boolean updated=false;synchronized(state.merged){if(matches(item,state.foldedNeedle)){updated=state.merged.put(item.url,item)!=null;if(!updated)batch.add(item);}}publishBatch(state,batch);if(updated)publishUpdate(item);}
    private void executeDirectory(SourceSearchState state){DirectLink prepared=null;Models.Folder listing=null;List<Models.Item> folderBatch=null;int folderCount=0;Exception failure=null;boolean preparing=state.directorySession==null,folderPaging=!preparing&&state.folderApiPage>0;BooleanSupplier stop=()->stopped(state);try{checkSearchCancelled(stop);if(preparing){prepared=browseSession(state.folder.url,state.folder.password,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SOURCE_PROBE_TIMEOUT_MS),false,state.directoryUa);checkSearchCancelled(stop);}else{ASYNC_PAGE_CANCEL.set(stop);if(folderPaging){int[] raw={0};folderBatch=apiFolders(state.directorySession,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SOURCE_PROBE_TIMEOUT_MS),state.folderApiPage,true,raw,sourceProfile(state.folder.url));folderCount=raw[0];}else listing=browsePreparedSearchPage(state,stop);checkSearchCancelled(stop);}}catch(Exception error){failure=error;}finally{ASYNC_PAGE_CANCEL.remove();}
      synchronized(this){state.dirInFlight=false;if(cancelled)return;if(failure!=null){handleDirectoryFailureLocked(state,failure);return;}if(preparing){state.directorySession=prepared;state.directoryUa=prepared.ua;state.firstApiFolderCount=prepared.folderEndpoint.isEmpty()?0:-1;state.dirReady=true;queueDirectoryLocked(state);return;}if(folderPaging)acceptFolderPageLocked(state,folderBatch,folderCount);else acceptListingLocked(state,listing);}}
    private void acceptListingLocked(SourceSearchState state,Models.Folder listing){List<Models.Item> batch=mergeListingLocked(state,listing.items);publishPage(state,batch,state.page,listing.items.size(),mergedSize(state));int pageLimit=pageLimit();if(listing.hasMore&&state.page<pageLimit){int completedPage=state.page++;scheduleDirectoryLocked(state,sourceProfile(state.folder.url).interval(state.directoryUa,completedPage));return;}if(state.firstApiFolderCount<0){state.folderApiPage=1;scheduleDirectoryLocked(state,sourceProfile(state.folder.url).interval(state.directoryUa,state.page));return;}if(options.recursiveFolders&&state.firstApiFolderCount>=PAGE_SIZE&&pageLimit>=2){state.folderApiPage=2;scheduleDirectoryLocked(state,sourceProfile(state.folder.url).interval(state.directoryUa,1));return;}completeFolderLocked(state);}
    private void acceptFolderPageLocked(SourceSearchState state,List<Models.Item> items,int rawCount){int page=state.folderApiPage;List<Models.Item> batch=mergeListingLocked(state,items);publishPage(state,batch,page,rawCount,mergedSize(state));if(page==1){state.firstApiFolderCount=rawCount;if(options.recursiveFolders&&rawCount>=PAGE_SIZE&&pageLimit()>=2){state.folderApiPage=2;scheduleDirectoryLocked(state,sourceProfile(state.folder.url).interval(state.directoryUa,1));}else completeFolderLocked(state);}else if(rawCount>=PAGE_SIZE&&page<pageLimit()){state.folderApiPage=page+1;scheduleDirectoryLocked(state,sourceProfile(state.folder.url).interval(state.directoryUa,page));}else completeFolderLocked(state);}
    private List<Models.Item> mergeListingLocked(SourceSearchState state,List<Models.Item> items){List<Models.Item> batch=new ArrayList<>();synchronized(state.merged){for(Models.Item item:items){inherit(item,state.folder);if(item.folder&&options.recursiveFolders&&state.folderKeys.add(searchFolderKey(item.url)))state.folders.addLast(item);if(matches(item,state.foldedNeedle)&&state.merged.putIfAbsent(item.url,item)==null)batch.add(item);}}return batch;}
    private int mergedSize(SourceSearchState state){synchronized(state.merged){return state.merged.size();}}
    private int pageLimit(){return options.untilLastPage?(options.maxPages>0?options.maxPages:1000):1;}
    private void completeFolderLocked(SourceSearchState state){cancelPageTimerLocked(state);state.folder=null;state.directorySession=null;startNextFolderLocked(state);queueDirectoryLocked(state);}
    private void handleDirectoryFailureLocked(SourceSearchState state,Exception error){if(error instanceof SearchCancelled){state.dirReady=true;queueDirectoryLocked(state);return;}if(error instanceof PageWaitException){scheduleDirectoryLocked(state,((PageWaitException)error).delayMillis);return;}if(!state.replayed&&recoverableBrowseError(error)){resetDirectoryForReplayLocked(state,error);return;}state.dirDone=true;state.dirFailed=true;finishSourceLocked(state,true);}
    private void resetDirectoryForReplayLocked(SourceSearchState state,Exception error){state.replayed=true;invalidateBrowseSessions(state.folder.url,state.folder.password);state.directorySession=null;state.page=1;state.folderApiPage=0;state.firstApiFolderCount=0;state.pageFingerprints.clear();scheduleDirectoryLocked(state,sourceProfile(state.folder.url).interval(state.directoryUa,1));}
    private void scheduleDirectoryLocked(SourceSearchState state,long delayMillis){cancelPageTimerLocked(state);state.dirReady=false;state.pageFuture=searchScheduler.schedule(()->{synchronized(SearchCoordinator.this){state.pageFuture=null;if(cancelled||state.terminal)return;state.dirReady=true;queueDirectoryLocked(state);pumpNetworkLocked();notifyAll();}},Math.max(0L,delayMillis),TimeUnit.MILLISECONDS);}
    private void scheduleRotationLocked(String key){if(options.sourceSwitchDelayMillis<=0||rotations.containsKey(key)||waitingGroups.isEmpty())return;rotations.put(key,searchScheduler.schedule(()->rotateSourceLocked(key),options.sourceSwitchDelayMillis,TimeUnit.MILLISECONDS));}
    private void rotateSourceLocked(String key){synchronized(this){rotations.remove(key);if(cancelled||paused||!activeGroups.contains(key)||remaining.getOrDefault(key,0)<=0){if(paused&&activeGroups.contains(key))scheduleRotationLocked(key);return;}if(waitingGroups.isEmpty()){scheduleRotationLocked(key);return;}List<SourceSearchState> members=groups.get(key);if(members!=null)for(SourceSearchState state:members)if(!state.terminal){apiReady.remove(state);directoryReady.remove(state);state.apiQueued=state.dirQueued=false;state.active=false;}activeGroups.remove(key);active--;waitingGroups.addLast(key);refillActiveLocked();publishActivityLocked(members==null||members.isEmpty()?"":members.get(0).source.title);pumpNetworkLocked();notifyAll();}}
    private void finishSourceLocked(SourceSearchState state,boolean failed){if(state.terminal||!state.dirDone||!state.apiDone)return;state.terminal=true;state.active=false;cancelPageTimerLocked(state);if(failed||state.dirFailed)failedGroups.add(state.groupKey);int left=remaining.compute(state.groupKey,(key,value)->Math.max(0,(value==null?1:value)-1));if(left>0)return;ScheduledFuture<?> rotation=rotations.remove(state.groupKey);if(rotation!=null)rotation.cancel(false);if(activeGroups.remove(state.groupKey))active--;int value=done.incrementAndGet();if(progress!=null)synchronized(progress){if(failedGroups.contains(state.groupKey))progress.onFailure(state.source.title);progress.onProgress(value,total,found.get(),state.source.title);}refillActiveLocked();publishActivityLocked(state.source.title);}
    private void publishActivityLocked(String current){if(progress!=null)synchronized(progress){if(!cancelled)progress.onActivity(active,total,current);}}
    private void stampSource(SourceSearchState state,List<Models.Item> items){if(items==null)return;for(Models.Item item:items){item.source=firstNonEmpty(item.source,state.displaySource);item.sourceId=state.groupKey;}}
    private void publishBatch(SourceSearchState state,List<Models.Item> items){if(items==null||items.isEmpty()||cancelled)return;stampSource(state,items);List<Models.Item> fresh;synchronized(resultLock){fresh=addFresh(items,seen,out,found);}if(progress!=null&&!fresh.isEmpty())synchronized(progress){if(cancelled)return;progress.onBatch(Collections.unmodifiableList(fresh));progress.onProgress(done.get(),total,found.get(),state.source.title);}}
    private void publishUpdate(Models.Item item){if(cancelled)return;synchronized(resultLock){for(int i=0;i<out.size();i++)if(out.get(i).url.equals(item.url)){out.set(i,item);break;}}if(progress!=null)synchronized(progress){if(!cancelled)progress.onItemUpdated(item);}}
    private void publishPage(SourceSearchState state,List<Models.Item> items,int page,int pageItems,int sourceFound){if(cancelled)return;stampSource(state,items);List<Models.Item> fresh;synchronized(resultLock){fresh=addFresh(items,seen,out,found);}int count=pagesSeen.incrementAndGet();if(progress!=null)synchronized(progress){if(cancelled)return;progress.onBatch(Collections.unmodifiableList(fresh));progress.onProgress(done.get(),total,found.get(),state.source.title);progress.onPage(state.source.title,page,pageItems,sourceFound,count);}}
    private void cancelPageTimerLocked(SourceSearchState state){if(state.pageFuture!=null){state.pageFuture.cancel(false);state.pageFuture=null;}}
    private void cancelTimersLocked(){for(SourceSearchState state:states)cancelPageTimerLocked(state);for(ScheduledFuture<?> future:rotations.values())future.cancel(false);rotations.clear();}
    private void cancelLocked(){if(cancelled)return;cancelled=true;apiReady.clear();directoryReady.clear();cancelTimersLocked();for(Future<?> future:new ArrayList<>(httpFutures))future.cancel(true);for(HttpURLConnection connection:new ArrayList<>(openConnections))connection.disconnect();notifyAll();}
  }

  DirectLink resolveDirect(String shareUrl)throws Exception{
    Exception last=null;
    for(int attempt=0;attempt<2;attempt++){
      try{
        NetSession session=new NetSession();
        DirectLink share=session.getGuarded(shareUrl,"");
        String fileTitle=cleanFileName(cap(share.html,"(?is)<title[^>]*>(.*?)</title>"));
        String transfer=cap(share.html,"(?is)id=[\"']downurl[\"'][^>]*href=[\"']([^\"']+)");
        if(transfer.isEmpty())transfer=cap(share.html,"(?is)<a[^>]+href=[\"']([^\"']*/tp/[^\"']+)");
        DirectLink page=share;
        if(!transfer.isEmpty())page=session.getGuarded(new URL(new URL(share.url),transfer).toString(),share.url);

        String base=cap(page.html,"vkjxld\\s*=\\s*['\"]([^'\"]+)['\"]");
        String token=cap(page.html,"hyggid\\s*=\\s*['\"]([^'\"]+)['\"]");
        if(!base.isEmpty()&&!token.isEmpty()){
          // vkjxld + hyggid is only a bootstrap page.  It deliberately waits before
          // allowing ajax.php to exchange file/sign for the actual CDN URL.
          String[] candidates={base+token+"&lanosso2",base+token};
          for(String bootstrap:candidates){
            DirectLink verify=session.get(bootstrap,page.url);
            String file=cap(verify.html,"['\"]file['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
            String sign=cap(verify.html,"['\"]sign['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
            if(file.isEmpty()||sign.isEmpty())continue;
            String ajax=cap(verify.html,"url\\s*:\\s*['\"]([^'\"]*ajax\\.php[^'\"]*)['\"]");
            if(ajax.isEmpty())ajax="ajax.php";
            Map<String,String> form=new LinkedHashMap<>();
            form.put("file",file);form.put("el","2");form.put("sign",sign);
            String endpoint=new URL(new URL(verify.url),ajax).toString();
            for(int exchange=0;exchange<2;exchange++){
              Thread.sleep(exchange==0?DIRECT_INITIAL_WAIT_MS:DIRECT_RETRY_WAIT_MS);
              JSONObject data=new JSONObject(session.post(endpoint,form,verify));
              String direct=data.optString("url");
              if(data.optInt("zt")==1&&direct.startsWith("http"))return direct(direct,fileTitle);
              if(!directExchangePending(data))throw new IOException(data.optString("inf","蓝奏验证失败"));
            }
            throw new IOException("蓝奏验证未返回真实直链");
          }
          throw new IOException("蓝奏验证未返回真实直链");
        }

        String sign=cap(page.html,"(?:ajaxdata|sign)\\s*[:=]\\s*['\"]([^'\"]+)['\"]");
        if(sign.isEmpty())throw new IOException("未找到下载签名");
        Map<String,String> form=new LinkedHashMap<>();
        form.put("action","downprocess");form.put("sign",sign);form.put("p","");
        JSONObject data=new JSONObject(session.post(new URL(new URL(page.url),"/ajaxm.php").toString(),form,page));
        if(data.optInt("zt")!=1)throw new IOException(data.optString("inf","蓝奏解析失败"));
        String path=data.optString("url"),dom=data.optString("dom").replaceAll("/+$","");
        String url=path.startsWith("http")?path:dom+"/file/"+path.replaceAll("^[/?]+","");
        if(!url.startsWith("http"))throw new IOException("蓝奏返回了无效直链");
        return direct(url,fileTitle);
      }catch(Exception error){last=error;}
    }
    throw last==null?new IOException("直链解析失败"):last;
  }

  private static boolean directExchangePending(JSONObject data){String info=data.optString("inf").trim();if(DIRECT_TERMINAL_INFO.matcher(info).find())return false;if(data.optInt("zt")==1)return!data.optString("url").startsWith("http");return info.isEmpty()||info.equals("0")||info.contains("稍后")||info.contains("验证")||info.contains("处理中")||info.contains("等待");}
  private static final Pattern DIRECT_TERMINAL_INFO=Pattern.compile("取消|来晚|删除|不存在|失效|密码|禁止|违规|封禁|关闭|拒绝|错误|失败");

  private static DirectLink direct(String url,String title){DirectLink out=new DirectLink();out.url=url;out.fileName=title.isEmpty()?"download.bin":title;return out;}
  private static String cleanFileName(String title){String value=INVALID_FILE_NAME.matcher(strip(title)).replaceAll("_").trim();return value.isEmpty()?"download.bin":value;}

  /** Small per-resolution cookie jar; keeps the ACW and CDN verification sessions isolated. */
  private static final class NetSession{
    private final Map<String,LinkedHashMap<String,String>> jar=new HashMap<>();
    DirectLink getGuarded(String url,String referer)throws Exception{
      DirectLink page=get(url,referer);String value=acwCookie(page.html);if(value.isEmpty())return page;put(new URL(url).getHost(),"acw_sc__v2",value);return get(url,referer);
    }
    DirectLink get(String url,String referer)throws Exception{
      HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent",ANDROID_UA);c.setRequestProperty("Accept-Encoding","gzip");if(!referer.isEmpty())c.setRequestProperty("Referer",referer);String cookie=cookies(new URL(url).getHost());if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);c.getResponseCode();capture(c);DirectLink page=new DirectLink();page.url=c.getURL().toString();page.cookie=cookies(new URL(page.url).getHost());page.html=body(c);c.disconnect();return page;
    }
    String post(String url,Map<String,String> data,DirectLink referer)throws Exception{
      HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setDoOutput(true);c.setRequestMethod("POST");c.setRequestProperty("User-Agent",ANDROID_UA);c.setRequestProperty("Accept","application/json, text/javascript, */*");c.setRequestProperty("Referer",referer.url);c.setRequestProperty("Origin",origin(referer.url));c.setRequestProperty("X-Requested-With","XMLHttpRequest");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");String cookie=cookies(new URL(url).getHost());if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);byte[] bytes=form(data).getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}c.getResponseCode();capture(c);String result=body(c);c.disconnect();return result;
    }
    private LinkedHashMap<String,String> bucket(String host){LinkedHashMap<String,String> values=jar.get(host);if(values==null){values=new LinkedHashMap<>();jar.put(host,values);}return values;}
    private void capture(HttpURLConnection c){mergeSetCookies(bucket(c.getURL().getHost()),c.getHeaderFields());}
    private void put(String host,String name,String value){bucket(host).put(name,value);}
    private String cookies(String host){Map<String,String> values=jar.get(host);return values==null?"":cookieHeader(values);}
  }

  /**
   * Verifies and stores one user source. Capability detection inspects both
   * UA-specific pages without issuing a query, so zero-match sources remain
   * searchable while directory and search preferences can be learned safely.
   */
  Models.Source addUserSource(String url,String password)throws Exception{
    Models.Source source=resolveUserSource(url,password);
    storeUserSource(source);
    return copySource(source);
  }

  AddBatchResult addUserSourcesBatch(String raw)throws InterruptedException{return addUserSourcesBatch(raw,0,false);}
  AddBatchResult addUserSourcesBatch(String raw,boolean discoverNonEmptyChildren)throws InterruptedException{return addUserSourcesBatch(raw,0,discoverNonEmptyChildren);}

  /** Parse, probe concurrently through the single-source core, then persist all successes in one commit. */
  AddBatchResult addUserSourcesBatch(String raw,int concurrency)throws InterruptedException{return addUserSourcesBatch(raw,concurrency,false);}
  AddBatchResult addUserSourcesBatch(String raw,int concurrency,boolean discoverNonEmptyChildren)throws InterruptedException{
    List<BatchSource> rows=new ArrayList<>();
    if(raw==null)return batchResult(rows);
    if(raw.length()>RULE_LIMIT){BatchSource row=new BatchSource(0);row.error="批量内容过大";rows.add(row);return batchResult(rows);}
    Map<String,Models.Source> existing=new HashMap<>();
    try{for(Models.Source source:sources())if(source.kind!=Models.SOURCE_COMPOSITE)existing.put(source.url,source);}catch(IOException ignored){}
    Set<String> seen=new HashSet<>();int lineNumber=0;
    try(BufferedReader reader=new BufferedReader(new StringReader(raw))){
      for(String rawLine;(rawLine=reader.readLine())!=null;){lineNumber++;String line=rawLine.trim();if(line.isEmpty())continue;BatchSource row=new BatchSource(lineNumber);rows.add(row);try{int split=firstWhitespace(line);String password="";if(split>=0){password=line.substring(split).trim();line=line.substring(0,split);if(firstWhitespace(password)>=0)throw new IOException("每行仅支持链接和一个密码");}row.url=normalizeUserSourceUrl(line);row.password=password;if(password.length()>64||containsControl(password))throw new IOException("密码格式无效");Models.Source duplicate=existing.get(row.url);if(duplicate!=null){row.source=duplicate;row.duplicate=true;}else if(!seen.add(row.url))row.duplicate=true;}catch(Exception error){row.error=safeBatchError(error);}}
    }catch(IOException impossible){return batchResult(rows);}
    List<BatchSource> pending=new ArrayList<>();for(BatchSource row:rows)if(row.error==null&&!row.duplicate)pending.add(row);
    int limit=concurrency<=0?Math.min(MAX_PROBE_WORKERS,pending.size()):Math.min(Math.max(1,concurrency),Math.min(MAX_PROBE_WORKERS,pending.size()));
    ExecutorService workers=pending.isEmpty()?null:Executors.newFixedThreadPool(Math.max(1,limit));List<Future<Models.Source>> futures=new ArrayList<>();
    try{
      if(workers!=null)for(BatchSource row:pending)futures.add(workers.submit(()->resolveUserSource(row.url,row.password)));
      for(int i=0;i<futures.size();i++)try{pending.get(i).source=futures.get(i).get();}catch(ExecutionException error){pending.get(i).error=safeBatchError(error.getCause());}
    }finally{for(Future<?> future:futures)if(!future.isDone())future.cancel(true);if(workers!=null)workers.shutdownNow();}
    ChildDiscovery discovered=new ChildDiscovery();if(discoverNonEmptyChildren){List<BatchSource> roots=new ArrayList<>(rows);for(BatchSource row:roots)if(row.source!=null&&row.error==null)try{ChildDiscovery value=discoverChildSources(row.source,row.line);discovered.rows.addAll(value.rows);discovered.empty+=value.empty;discovered.failed+=value.failed;}catch(Exception error){discovered.failed++;}rows.addAll(discovered.rows);pending.addAll(discovered.rows);}
    commitUserSourceBatch(pending);return batchResult(rows,discovered.empty,discovered.failed);
  }

  private ChildDiscovery discoverChildSources(Models.Source source,int inputLine)throws Exception{
    ChildDiscovery out=new ChildDiscovery();if(source==null||source.kind==Models.SOURCE_COMPOSITE||source.url.isEmpty())return out;
    Models.Item root=new Models.Item();root.title=source.title;root.url=source.url;root.password=source.password;root.folder=true;
    ArrayDeque<FolderSeed> pending=new ArrayDeque<>();pending.add(new FolderSeed(root,source.title,true));Set<String> seen=new HashSet<>();
    while(!pending.isEmpty()){
      FolderSeed seed=pending.removeFirst();DirectLink target=parseFolderTarget(seed.item.url);String key=target.rootUrl+'\n'+target.folderId;if(!seen.add(key))continue;
      boolean nonempty=false;int firstApiFolderCount=0;List<Models.Item> children=new ArrayList<>();
      try{
        for(int page=1;page<=100;page++){
          Models.Folder listing=browsePage(seed.item.url,seed.item.password,true,page,NO_DEADLINE,true);if(page==1)firstApiFolderCount=listing.apiFolderCount;if(!listing.items.isEmpty())nonempty=true;for(Models.Item item:listing.items)if(item.folder){inherit(item,seed.item);children.add(item);}if(!listing.hasMore)break;if(page==100)throw new IOException("目录文件超过100页，未完整展开");
        }
        if(firstApiFolderCount>=PAGE_SIZE){DirectLink session=browseSession(seed.item.url,seed.item.password,NO_DEADLINE,false);SourceProfile profile=sourceProfile(seed.item.url);for(int page=2;page<=100;page++){int[] count={0};List<Models.Item> batch=apiFolders(session,NO_DEADLINE,page,true,count,profile);if(!batch.isEmpty())nonempty=true;for(Models.Item item:batch){inherit(item,seed.item);children.add(item);}if(count[0]<PAGE_SIZE)break;if(page==100)throw new IOException("目录子文件夹超过100页，未完整展开");}}
      }catch(Exception error){out.failed++;continue;}
      if(!seed.root){if(nonempty){String identity=redactedFolderIdentity(seed.item.url);Models.Source child=new Models.Source();child.id=child.url=identity;child.password=seed.item.password;child.title=firstNonEmpty(seed.item.title,seed.path);child.searchable=source.searchable;child.childDirectory=true;child.originPath=seed.path;child.originUrl=identity;BatchSource row=new BatchSource(inputLine);row.child=true;row.url=child.url;row.password=child.password;row.source=child;out.rows.add(row);}else out.empty++;}
      for(Models.Item child:children){String path=seed.path.isEmpty()?child.title:seed.path+" / "+child.title;pending.addLast(new FolderSeed(child,path,false));}
    }
    return out;
  }

  private Models.Source resolveUserSource(String url,String password)throws Exception{
    String normalized=normalizeUserSourceUrl(url),pwd=password==null?"":password.trim();
    if(pwd.length()>64||containsControl(pwd))throw new IOException("密码格式无效");
    Exception singleError;try{return singleSource(normalized,pwd,probeSingleSource(normalized,pwd));}catch(Exception error){singleError=error;}
    try{return detectSource(normalized,pwd);}catch(Exception directoryError){if(singleError instanceof ShareCancelledException)throw singleError;if(directoryError instanceof ShareCancelledException)throw directoryError;throw directoryError;}
  }

  private static Models.Source singleSource(String rootUrl,String password,Models.SourceMember raw)throws IOException{
    if(raw==null||raw.url.isEmpty()||(raw.kind!=Models.MEMBER_FILE&&raw.kind!=Models.MEMBER_DIRECTORY))throw new IOException("不是可识别的单软件源");
    Models.SourceMember member=copyMember(raw);member.id="file";member.parentId="";member.lightweight=false;member.metadataLoaded=true;member.detailsLoaded=true;
    Models.Source source=new Models.Source();source.kind=Models.SOURCE_SINGLE;source.id=source.url=rootUrl;source.password=password;source.title=firstNonEmpty(member.title,memberFallbackTitle(member.url));source.publisher=source.title;source.avatarUrl=member.iconUrl;source.description=member.description;source.searchable=false;source.members.add(member);return source;
  }

  Models.Source addCompositeSource(String title,String publisher,String avatarUrl,String description,Collection<Models.SourceMember> rawMembers)throws Exception{
    String name=cleanRuleCell(title),author=cleanRuleCell(publisher),avatar=avatarUrl==null?"":avatarUrl.trim(),summary=description==null?"":description.trim();
    if(name.isEmpty()||name.length()>160)throw new IOException("请输入有效的合集标题");if(author.length()>80||summary.length()>4000||containsControl(author))throw new IOException("合集信息格式无效");if(avatar.length()>2048||containsControl(avatar))throw new IOException("头像链接格式无效");
    LinkedHashMap<String,Models.SourceMember> unique=new LinkedHashMap<>();if(rawMembers!=null)for(Models.SourceMember value:rawMembers){if(value==null)continue;String url=normalizeUserSourceUrl(value.url),pwd=value.password==null?"":value.password.trim();if(pwd.length()>64||containsControl(pwd))throw new IOException("成员密码格式无效");Models.SourceMember input=new Models.SourceMember();input.url=url;input.password=pwd;unique.putIfAbsent(memberKey(input),input);}
    List<Models.SourceMember> inputs=new ArrayList<>(unique.values()),resolved=new ArrayList<>(Collections.nCopies(inputs.size(),null));List<Future<Models.SourceMember>> futures=new ArrayList<>();int failures=0;ExecutorService workers=inputs.isEmpty()?null:Executors.newFixedThreadPool(Math.min(MAX_PROBE_WORKERS,inputs.size()));try{if(workers!=null)for(Models.SourceMember input:inputs)futures.add(workers.submit(()->probeCompositeNode(input.url,input.password)));for(int i=0;i<futures.size();i++)try{Models.SourceMember member=futures.get(i).get();member.id=newMemberId(resolved);resolved.set(i,member);}catch(ExecutionException error){Models.SourceMember failed=copyMember(inputs.get(i));failed.id=newMemberId(resolved);failed.title="未识别成员 "+(i+1);failed.error=sourceTestError(error.getCause());resolved.set(i,failed);failures++;}}finally{for(Future<?> future:futures)if(!future.isDone())future.cancel(true);if(workers!=null)workers.shutdownNow();}
    Models.Source source=new Models.Source();source.id="local:"+UUID.randomUUID();source.kind=Models.SOURCE_COMPOSITE;source.title=name;source.publisher=author;source.avatarUrl=avatar;source.description=summary;source.searchable=false;source.error=failures==0?"":failures+" 个成员异常";source.members.addAll(resolved);String softwareIcon=firstSoftwareIcon(source);if(!softwareIcon.isEmpty())source.avatarUrl=softwareIcon;storeUserSource(source);return copySource(source);
  }

  Models.Source addCompositeFolder(String sourceId,String parentId,String title)throws Exception{
    Models.Source source=editableComposite(sourceId);String name=cleanRuleCell(title),parent=parentId==null?"":parentId;if(name.isEmpty()||name.length()>160)throw new IOException("请输入有效的文件夹名称");requireCompositeParent(source,parent);Models.SourceMember folder=new Models.SourceMember();folder.id=newMemberId(source.members);folder.parentId=parent;folder.kind=Models.MEMBER_FOLDER;folder.title=name;source.members.add(folder);storeUserSource(source);return compositeView(source,parent,compositeNodeTitle(source,parent));
  }

  Models.Source renameCompositeFolder(String sourceId,String nodeId,String title)throws Exception{
    Models.Source source=editableComposite(sourceId);String id=nodeId==null?"":nodeId,name=cleanRuleCell(title);if(id.isEmpty()||name.isEmpty()||name.length()>160)throw new IOException("请输入有效的文件夹名称");Models.SourceMember target=null;for(Models.SourceMember member:source.members)if(member.id.equals(id)&&member.kind==Models.MEMBER_FOLDER){target=member;break;}if(target==null)throw new IOException("本地文件夹已不存在");target.title=name;storeUserSource(source);touchSourceRevisionLocked(sourceRootId(sourceId));return copySource(source);
  }

  Models.Source addCompositeNode(String sourceId,String parentId,String url,String password)throws Exception{
    Models.Source source=editableComposite(sourceId);String parent=parentId==null?"":parentId;requireCompositeParent(source,parent);Models.SourceMember member=probeCompositeNode(url,password);for(Models.SourceMember old:source.members)if(old.parentId.equals(parent)&&!member.url.isEmpty()&&memberKey(old).equals(memberKey(member)))throw new IOException("该源已在当前文件夹内");member.id=newMemberId(source.members);member.parentId=parent;source.members.add(member);String softwareIcon=firstSoftwareIcon(source);if(!softwareIcon.isEmpty())source.avatarUrl=softwareIcon;storeUserSource(source);return compositeView(source,parent,compositeNodeTitle(source,parent));
  }

  private Models.Source editableComposite(String rawId)throws Exception{String root=sourceRootId(rawId);for(Models.Source source:sources())if(source.kind==Models.SOURCE_COMPOSITE&&source.id.equals(root))return copySource(source);throw new IOException("自建合集已不存在");}
  private static void requireCompositeParent(Models.Source source,String parent)throws IOException{if(parent==null||parent.isEmpty())return;for(Models.SourceMember member:source.members)if(member.id.equals(parent)&&member.kind==Models.MEMBER_FOLDER)return;throw new IOException("目标文件夹已不存在");}
  private static String compositeNodeTitle(Models.Source source,String node){if(node==null||node.isEmpty())return source.title;for(Models.SourceMember member:source.members)if(member.id.equals(node))return member.title;return source.title;}
  private static String newMemberId(Collection<Models.SourceMember> members){Set<String> used=new HashSet<>();if(members!=null)for(Models.SourceMember member:members)if(member!=null)used.add(member.id);String id;do{id="n"+Long.toString(ThreadLocalRandom.current().nextLong()&Long.MAX_VALUE,36);}while(used.contains(id));return id;}

  private Models.SourceMember probeCompositeNode(String rawUrl,String rawPassword)throws Exception{
    try{return probeSingleSource(rawUrl,rawPassword);}catch(Exception singleError){String url=normalizeUserSourceUrl(rawUrl),password=rawPassword==null?"":rawPassword.trim();try{Models.Source remote=detectSource(url,password);Models.SourceMember member=new Models.SourceMember();member.kind=Models.MEMBER_REMOTE_FOLDER;member.title=remote.title;member.url=remote.url;member.password=remote.password;member.iconUrl=remote.avatarUrl;member.description=remote.description;member.searchable=remote.searchable;return member;}catch(Exception directoryError){if(singleError instanceof ShareCancelledException)throw singleError;throw directoryError;}}
  }

  Models.SourceMember probeSingleSource(String rawUrl,String rawPassword)throws Exception{
    String url=normalizeUserSourceUrl(rawUrl),password=rawPassword==null?"":rawPassword.trim();if(password.length()>64||containsControl(password))throw new IOException("密码格式无效");Exception last=null;
    for(byte ua=UA_ANDROID;ua<=UA_DESKTOP;ua++)try{long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SOURCE_PROBE_TIMEOUT_MS);DirectLink page=getGuarded(url,deadline,userAgent(ua));requireLanzouPage(page.url);requireActiveShare(page);if(isDirectorySharePage(page.html))return probeSingleDirectory(url,password,deadline,ua);if(!isSingleFileSharePage(page.html))throw new IOException("不是可识别的文件分享页");return probeSingleFile(url,password,page);}catch(Exception error){last=error;}
    throw last==null?new IOException("单软件源暂不可用"):last;
  }

  private static boolean isDirectorySharePage(String html){return html.contains("filemoreajax.php")||!cap(html,"url\\s*:\\s*['\"]([^'\"]*filemoreajax\\.php\\?file=\\d+[^'\"]*)['\"]").isEmpty();}

  private static boolean isSingleFileSharePage(String html){if(html==null||html.isEmpty()||isDirectorySharePage(html))return false;String title=strip(cap(html,"(?is)<title[^>]*>(.*?)</title>")),description=strip(cap(html,"(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)"));return !cap(html,"(?is)id=[\"']downurl[\"'][^>]*href=[\"']([^\"']+)").isEmpty()||!cap(html,"(?is)<iframe[^>]+src=[\"'][^\"']*/fn\\?[^\"']+[\"']").isEmpty()||html.matches("(?is).*class=[\"'][^\"']*\\bappfile\\b[^\"']*[\"'].*")||html.matches("(?is).*id=[\"']filenajax[\"'].*")||title.matches("(?is).+?\\s*-\\s*(?:蓝奏云|lanzou)\\s*")&&description.matches("(?is).*(?:文件)?大小\\s*[:：].*");}

  private Models.SourceMember probeSingleDirectory(String url,String password,long deadline,byte ua)throws Exception{
    Models.Item only=null;for(int page=1;page<=100;page++){PageResult result=browsePageUa(url,password,true,page,deadline,true,sourceProfile(url),ua);for(Models.Item item:result.folder.items){if(item.folder)throw new IOException("单软件目录不能包含子文件夹");if(only!=null&&!only.url.equals(item.url))throw new IOException("该目录包含多个软件");only=item;}if(!result.folder.hasMore)break;if(page==100)throw new IOException("单软件目录分页过多");}if(only==null)throw new IOException("该目录没有可下载软件");Models.SourceMember out=memberFromItem(only);out.kind=Models.MEMBER_DIRECTORY;out.password=password;out.lightweight=true;out.refreshedAt=System.currentTimeMillis();return out;
  }

  private static Models.SourceMember probeSingleFile(String url,String password,DirectLink page)throws Exception{
    String html=page.html;if(!isSingleFileSharePage(html))throw new IOException("不是可识别的单文件分享页");Models.SourceMember out=new Models.SourceMember();out.kind=Models.MEMBER_FILE;out.url=url;out.password=password;out.lightweight=true;out.refreshedAt=System.currentTimeMillis();String rawTitle=firstNonEmpty(strip(divInner(html,"appname")),strip(elementByIdInner(html,"filenajax")),strip(cap(html,"(?is)<title[^>]*>(.*?)</title>")));rawTitle=rawTitle.replaceFirst("(?i)[\\s_-]*(?:蓝奏云|lanzou).*$","").trim();out.title=rawTitle.isEmpty()?"未命名软件":rawTitle;out.iconUrl=firstNonEmpty(cap(html,"(?is)<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"']([^\"']+)"),cap(html,"(?is)<img[^>]+class=[\"'][^\"']*(?:appico|file-ico|n_file_ico)[^\"']*[\"'][^>]+src=[\"']([^\"']+)"),cap(html,"(?is)<img[^>]+src=[\"']([^\"']+)[\"'][^>]+class=[\"'][^\"']*(?:appico|file-ico|n_file_ico)"),cap(html,"(?is)class=[\"'][^\"']*appico[^\"']*[\"'][^>]+style=[\"'][^\"']*background\\s*:\\s*url\\(([^)]+)\\)"));if(!out.iconUrl.isEmpty())try{out.iconUrl=new URL(new URL(page.url),out.iconUrl.replace("\"","").replace("'","")).toString();}catch(Exception ignored){}out.size=strip(firstNonEmpty(cap(html,"(?is)(?:文件)?大小\\s*[：:]?\\s*</?[^>]*>\\s*([^<\\r\\n]+)"),cap(html,"(?is)class=[\"'][^\"']*(?:filesize|n_filesize)[^\"']*[\"'][^>]*>(.*?)</"),cap(html,"(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"'][^\"']*(?:文件)?大小\\s*[:：]\\s*([^\"']+)")));out.time=cap(html,"((?:19|20)\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2})").replace('/','-').replace('.','-');out.description=fileDescriptionFromHtml(html);return out;
  }

  private static String fileDescriptionFromHtml(String html){String value=strip(divInner(html,"appdes"));if(value.isEmpty())value=strip(divInner(html,"file-des"));if(value.isEmpty())value=strip(divInner(html,"n_file_info"));if(value.isEmpty())value=strip(cap(html,"(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)"));return value.replaceFirst("^(?:文件)?(?:描述|简介)\\s*[：:]\\s*","").trim();}

  static String sourceId(Models.Source source){if(source==null)return"";String id=!source.id.isEmpty()?source.id:source.kind==Models.SOURCE_COMPOSITE?"":source.url;return source.kind==Models.SOURCE_COMPOSITE&&!source.nodeId.isEmpty()?id+LOCAL_NODE_MARKER+source.nodeId:id;}
  private static String sourceRootId(String id){if(id==null)return"";int marker=id.indexOf(LOCAL_NODE_MARKER);return marker<0?id:id.substring(0,marker);}
  private static String memberKey(Models.SourceMember member){return member.url+'\n'+member.password;}
  private static String sourceContentKey(Models.Source source){if(source==null)return"";if(source.kind!=Models.SOURCE_COMPOSITE)return"official\n"+source.url;List<String> members=new ArrayList<>();for(Models.SourceMember member:source.members)members.add(member.kind+"\n"+member.parentId+'\n'+(member.lightweight?"":member.title)+'\n'+memberKey(member));Collections.sort(members);StringBuilder out=new StringBuilder("composite");for(String member:members)out.append('\n').append(member);return out.toString();}
  private static Models.SourceMember memberFromItem(Models.Item item){Models.SourceMember out=new Models.SourceMember();out.title=item.title;out.url=firstNonEmpty(item.shareUrl,item.url);out.password=item.password;out.iconUrl=item.iconUrl;out.size=item.size;out.time=item.time;out.description=item.description;out.lightweight=true;out.refreshedAt=System.currentTimeMillis();return out;}
  private static Models.Item itemFromMember(Models.SourceMember member,String source){Models.Item out=new Models.Item();out.title=firstNonEmpty(member.title,memberFallbackTitle(member.url));out.url=out.shareUrl=member.url;out.password=member.password;out.iconUrl=member.iconUrl;out.size=member.size;out.time=member.time;out.description=member.description;out.error=member.error;out.folder=member.kind==Models.MEMBER_FOLDER||member.kind==Models.MEMBER_REMOTE_FOLDER;if(out.folder&&out.iconUrl.isEmpty())out.iconUrl="https://images.bakstotre.com/images/folder.gif";out.folderId=member.kind==Models.MEMBER_FOLDER?member.id:member.kind==Models.MEMBER_REMOTE_FOLDER?"remote:"+member.id:"";out.source=source;return out;}
  private static String memberFallbackTitle(String url){if(url==null||url.isEmpty())return"未命名软件";int slash=url.lastIndexOf('/');return slash>=0&&slash+1<url.length()?url.substring(slash+1):url;}
  private static String firstSoftwareIcon(Models.Source source){if(source==null)return"";for(Models.SourceMember member:source.members)if(member.kind!=Models.MEMBER_FOLDER&&member.kind!=Models.MEMBER_REMOTE_FOLDER&&!member.iconUrl.isEmpty())return member.iconUrl;return"";}

  private Models.Source detectSource(String normalized,String pwd)throws Exception{
    UaProbe android=probeSourceUa(normalized,pwd,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SOURCE_PROBE_TIMEOUT_MS),UA_ANDROID);if(android.session!=null)Thread.sleep(SOURCE_PROBE_UA_GAP_MS);UaProbe desktop=probeSourceUa(normalized,pwd,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SOURCE_PROBE_TIMEOUT_MS),UA_DESKTOP);SourceProfile profile=sourceProfile(normalized);byte androidBit=uaBit(UA_ANDROID),desktopBit=uaBit(UA_DESKTOP),directoryAvailable=(byte)((android.directory?androidBit:0)|(desktop.directory?desktopBit:0)),searchAvailable=(byte)((android.search?androidBit:0)|(desktop.search?desktopBit:0)),preferredDirectory=preferredUa(android.directory,android.items,android.elapsed,desktop.directory,desktop.items,desktop.elapsed),preferredSearch=preferredUa(android.search,0,android.elapsed,desktop.search,0,desktop.elapsed);
    synchronized(profile){profile.directorySeen|=directoryAvailable;profile.directoryAvailable|=directoryAvailable;profile.searchSeen|=searchAvailable;profile.searchAvailable|=searchAvailable;if(preferredDirectory!=UA_UNKNOWN)profile.directoryUa=preferredDirectory;if(preferredSearch!=UA_UNKNOWN)profile.searchUa=preferredSearch;}
    UaProbe chosen=profile.directoryUa==UA_DESKTOP?desktop:android;if(!chosen.directory)chosen=android.directory?android:desktop;if(!chosen.directory||chosen.session==null)throw probeFailure(android,desktop);Models.Folder folder=chosen.folder==null?copyFolder(chosen.session.metadata):chosen.folder;DirectLink session=chosen.session;
    String resolved=normalizeUserSourceUrl(session.page.url);
    Models.Source source=new Models.Source();source.id=source.url=resolved;source.password=pwd;
    source.title=firstNonEmpty(folder.title,session.metadata.title,resolved);
    source.publisher=folder.publisher;source.avatarUrl=folder.avatarUrl;source.description=folder.description;
    source.searchable=android.search||desktop.search;
    return source;
  }

  private static Exception probeFailure(UaProbe android,UaProbe desktop){if(android.error instanceof ShareCancelledException||desktop.error instanceof ShareCancelledException)return new ShareCancelledException();if(android.error!=null)return android.error;if(desktop.error!=null)return desktop.error;return new IOException("蓝奏目录暂不可用");}

  private static int firstWhitespace(String value){for(int i=0;i<value.length();i++)if(Character.isWhitespace(value.charAt(i)))return i;return-1;}
  private static String safeBatchError(Throwable error){String value=sourceTestError(error).replace('\r',' ').replace('\n',' ').replace('\t',' ').trim();return value.length()>160?value.substring(0,160):value;}
  private static AddBatchResult batchResult(List<BatchSource> rows){return batchResult(rows,0,0);}
  private static AddBatchResult batchResult(List<BatchSource> rows,int childEmpty,int childFailed){List<AddResult> results=new ArrayList<>(rows.size());int added=0,duplicates=0,failed=0,childAdded=0,childDuplicates=0;for(BatchSource row:rows){if(row.added){added++;if(row.child)childAdded++;}else if(row.duplicate){duplicates++;if(row.child)childDuplicates++;}else failed++;results.add(new AddResult(row.line,row.url==null?"":row.url,row.source,row.error==null?"":row.error,row.added,row.duplicate));}return new AddBatchResult(added,duplicates,failed,childAdded,childDuplicates,childEmpty,childFailed,results);}

  List<Models.SourceTestResult> retestSources(Collection<Models.Source> selected,Models.SourceTestProgress progress)throws InterruptedException{return retestSources(selected,0,progress);}

  List<Models.SourceTestResult> retestSources(Collection<Models.Source> selected,int concurrency,Models.SourceTestProgress progress)throws InterruptedException{
    LinkedHashMap<String,Models.Source> unique=new LinkedHashMap<>();if(selected!=null)for(Models.Source source:selected){String id=sourceId(source);if(source!=null&&!id.isEmpty())unique.putIfAbsent(id,copySource(source));}if(unique.isEmpty())return Collections.emptyList();
    List<SourceRetestPlan> plans=new ArrayList<>(unique.size());for(Models.Source source:unique.values())plans.add(beginSourceRetest(source));List<SourceRetestUnit> units=new ArrayList<>();LinkedHashMap<String,SourceRetestAggregate> aggregates=new LinkedHashMap<>();for(SourceRetestPlan plan:plans){int count=plan.source.kind==Models.SOURCE_COMPOSITE?realMemberCount(plan.source):1;SourceRetestAggregate aggregate=new SourceRetestAggregate(plan,count);aggregates.put(plan.key,aggregate);if(count==0){aggregate.remaining=1;units.add(new SourceRetestUnit(plan,-2));}else if(plan.source.kind==Models.SOURCE_COMPOSITE){for(int i=0;i<plan.source.members.size();i++)if(plan.source.members.get(i).kind!=Models.MEMBER_FOLDER)units.add(new SourceRetestUnit(plan,i));/* flattened equivalent: for(int i=0;i<count;i++)units.add */}else units.add(new SourceRetestUnit(plan,-1));}
    int limit=concurrency<=0?units.size():Math.min(concurrency,units.size());ExecutorService workers=Executors.newFixedThreadPool(Math.max(1,limit));CompletionService<SourceRetestUnitResult> completed=new ExecutorCompletionService<>(workers);IdentityHashMap<Future<SourceRetestUnitResult>,SourceRetestUnit> pending=new IdentityHashMap<>();for(SourceRetestUnit unit:units){Future<SourceRetestUnitResult> future=completed.submit(()->retestUnit(unit));pending.put(future,unit);}List<Models.SourceTestResult> results=new ArrayList<>(plans.size());int logicalDone=0;
    try{for(int unitDone=1;unitDone<=units.size();unitDone++){Future<SourceRetestUnitResult> future=completed.take();SourceRetestUnit unit=pending.remove(future);SourceRetestUnitResult value;try{value=future.get();}catch(ExecutionException error){value=new SourceRetestUnitResult(unit,null,null,sourceTestError(error.getCause()));}SourceRetestAggregate aggregate=aggregates.get(unit.plan.key);if(unit.memberIndex<0){if(value.official!=null)copySourceInto(value.official,aggregate.value);else{aggregate.value.error=value.error;aggregate.failures++;}}else{Models.SourceMember old=aggregate.value.members.get(unit.memberIndex);if(value.member!=null){aggregate.value.members.set(unit.memberIndex,copyMember(value.member));}else{old.error=value.error;aggregate.failures++;}}aggregate.remaining--;String memberTitle=unit.memberIndex>=0?firstNonEmpty(aggregate.value.members.get(unit.memberIndex).title,"成员 "+(unit.memberIndex+1)):aggregate.value.title;if(progress!=null)progress.onMember(unit.plan.key,memberTitle,unitDone,units.size(),value.success());if(aggregate.remaining==0){boolean success=aggregate.failures==0;if(aggregate.value.kind==Models.SOURCE_COMPOSITE)aggregate.value.error=success?"":aggregate.failures+" 个成员异常";boolean applied;try{applied=applyRetestResult(aggregate.plan,aggregate.value);}catch(Exception error){aggregate.value.error="测试结果保存失败："+sourceTestError(error);success=false;applied=false;}Models.SourceTestResult result=new Models.SourceTestResult(copySource(aggregate.value),aggregate.plan.key,success,aggregate.plan.userSource,applied);results.add(result);logicalDone++;if(progress!=null)progress.onResult(result,logicalDone,plans.size());}}}finally{for(Map.Entry<Future<SourceRetestUnitResult>,SourceRetestUnit> task:pending.entrySet())task.getKey().cancel(true);for(SourceRetestPlan plan:plans)if(aggregates.get(plan.key).remaining>0)invalidateRetest(plan);workers.shutdownNow();}return results;
  }

  private SourceRetestPlan beginSourceRetest(Models.Source source){synchronized(USER_SOURCE_LOCK){String key=sourceId(source);boolean userSource=hasUserSourceLocked(key);long revision=touchSourceRevisionLocked(key);return new SourceRetestPlan(copySource(source),key,userSource,revision);}}

  private SourceRetestUnitResult retestUnit(SourceRetestUnit unit){if(unit.memberIndex==-2)return new SourceRetestUnitResult(unit,copySource(unit.plan.source),null,"");if(unit.memberIndex<0){Models.SourceTestResult result=retestSource(unit.plan);return new SourceRetestUnitResult(unit,result.success?result.source:null,null,result.success?"":result.source.error);}Models.SourceMember old=unit.plan.source.members.get(unit.memberIndex);try{Models.SourceMember value;if(old.kind==Models.MEMBER_REMOTE_FOLDER)value=probeCompositeNode(old.url,old.password);else try{value=probeSingleSource(old.url,old.password);}catch(Exception typeChanged){value=probeCompositeNode(old.url,old.password);}value.id=old.id;value.parentId=old.parentId;value.error="";return new SourceRetestUnitResult(unit,null,value,"");}catch(Exception error){return new SourceRetestUnitResult(unit,null,null,sourceTestError(error));}}

  static int realMemberCount(Models.Source source){int count=0;if(source!=null)for(Models.SourceMember member:source.members)if(member.kind!=Models.MEMBER_FOLDER)count++;return count;}

  private Models.SourceTestResult retestSource(SourceRetestPlan plan){Models.Source value;boolean success;try{value=resolveUserSource(plan.source.url,plan.source.password);value.id=plan.key;value.url=plan.source.url;value.password=plan.source.password;value.error="";success=true;}catch(Exception error){value=copySource(plan.source);value.error=sourceTestError(error);success=false;}return new Models.SourceTestResult(copySource(value),plan.key,success,plan.userSource,false);}

  /** Compare-and-set: a later add/remove/import/re-test always wins. */
  private boolean applyRetestResult(SourceRetestPlan plan,Models.Source value)throws Exception{synchronized(USER_SOURCE_LOCK){if(!sourceRevisionMatchesLocked(plan))return false;boolean userNow=hasUserSourceLocked(plan.key);Set<String> removed=loadRemovedSourcesLocked(context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE));if(removed.contains(plan.key)||userNow!=plan.userSource)return false;if(plan.userSource)storeUserSourceLocked(value);else{if(userNow)return false;SOURCE_OVERRIDES.put(plan.key,copySource(value));}invalidateSourceNameIndex();return true;}}

  private void invalidateRetest(SourceRetestPlan plan){synchronized(USER_SOURCE_LOCK){if(sourceRevisionMatchesLocked(plan))touchSourceRevisionLocked(plan.key);}}

  private static String sourceTestError(Throwable error){for(Throwable value=error;value!=null;value=value.getCause()){if(value instanceof ShareCancelledException)return"分享已取消";if(value instanceof SocketTimeoutException)return"连接超时";if(value instanceof UnknownHostException||value instanceof ConnectException||value instanceof NoRouteToHostException)return"网络连接失败";}String message=error==null?"":error.getMessage();return message==null||message.trim().isEmpty()?"暂时无法连接":message.trim();}

  private UaProbe probeSourceUa(String url,String password,long deadline,byte ua){UaProbe probe=new UaProbe();long started=System.nanoTime();try{PageResult page=browsePageUa(url,password,true,1,deadline,false,sourceProfile(url),ua);probe.folder=page.folder;probe.items=page.folder.items.size();probe.directory=true;probe.session=browseSession(url,password,deadline,false,ua);}catch(Exception error){probe.error=error;try{probe.session=browseSession(url,password,deadline,false,ua);}catch(Exception ignored){}}probe.search=probe.session!=null&&hasSearchCapability(probe.session.page);probe.elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);return probe;}
  private static byte preferredUa(boolean android,int androidItems,long androidMillis,boolean desktop,int desktopItems,long desktopMillis){if(android&&desktop){if(androidItems!=desktopItems)return desktopItems>androidItems?UA_DESKTOP:UA_ANDROID;return desktopMillis<androidMillis?UA_DESKTOP:UA_ANDROID;}if(desktop)return UA_DESKTOP;if(android)return UA_ANDROID;return UA_UNKNOWN;}

  List<Models.Source> sources() throws IOException {return sources(loadRemovedSources());}

  private List<Models.Source> sources(Set<String> removed) throws IOException {
    LinkedHashMap<String,Models.Source> merged=new LinkedHashMap<>();
    for(Models.Source source:builtInSources()){String id=sourceId(source);Models.Source value=copySource(source),override=SOURCE_OVERRIDES.get(id);if(override!=null)value=copySource(override);if(!removed.contains(id))merged.putIfAbsent(id,value);}
    for(Models.Source source:loadUserSources()){String id=sourceId(source);if(removed.contains(id))continue;Models.Source baseline=merged.get(id);merged.put(id,source.overlay&&baseline!=null&&baseline.kind==Models.SOURCE_COMPOSITE?mergeCompositeOverlay(baseline,source):source);}
    return new ArrayList<>(merged.values());
  }

  private Models.Source builtInSource(String id)throws IOException{for(Models.Source source:builtInSources())if(sourceId(source).equals(id))return copySource(source);return null;}
  private static Models.Source mergeCompositeOverlay(Models.Source baseline,Models.Source overlay){Models.Source out=copySource(baseline);if(overlay.metadataOverride){out.title=overlay.title;out.publisher=overlay.publisher;out.description=overlay.description;}Set<String> ids=new HashSet<>();for(Models.SourceMember member:out.members)ids.add(member.id);for(Models.SourceMember member:overlay.members){Models.SourceMember added=copyMember(member);if(added.id.isEmpty()||ids.contains(added.id))added.id=newMemberId(out.members);ids.add(added.id);out.members.add(added);}String icon=firstSoftwareIcon(out);if(!icon.isEmpty())out.avatarUrl=icon;out.error=overlay.error;return out;}
  private static Models.Source compositeOverlay(Models.Source baseline,Models.Source current){Models.Source out=new Models.Source();out.id=baseline.id;out.kind=Models.SOURCE_COMPOSITE;out.overlay=true;out.metadataOverride=!baseline.title.equals(current.title)||!baseline.publisher.equals(current.publisher)||!baseline.description.equals(current.description);if(out.metadataOverride){out.title=current.title;out.publisher=current.publisher;out.description=current.description;}Set<String> baselineIds=new HashSet<>();for(Models.SourceMember member:baseline.members)baselineIds.add(member.id);for(Models.SourceMember member:current.members)if(!baselineIds.contains(member.id))out.members.add(copyMember(member));out.error=current.error;return out;}

  private List<Models.Source> builtInSources()throws IOException{
    List<Models.Source> cached=BUILT_IN_SOURCE_CACHE;if(cached!=null)return cached;
    synchronized(BUILT_IN_SOURCE_LOCK){cached=BUILT_IN_SOURCE_CACHE;if(cached!=null)return cached;List<Models.Source> parsed=new ArrayList<>();
      try(InputStream in=context.getAssets().open("h")){byte[] values=new byte[in.available()];int offset=0,read;while(offset<values.length&&(read=in.read(values,offset,values.length-offset))>0)offset+=read;BUILT_IN_SOURCE_HINTS=offset==values.length?values:Arrays.copyOf(values,offset);}catch(Exception ignored){BUILT_IN_SOURCE_HINTS=null;}
      try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("s"),StandardCharsets.UTF_8))){String line;boolean searchable=false;while((line=reader.readLine())!=null){if(line.equals("1")||line.equals("0")){searchable=line.charAt(0)=='1';continue;}String[] cells=line.split("\\t",-1);if(cells.length<2)continue;Models.Source source=new Models.Source();source.title=cells[0];source.id=source.url=CANONICAL_SOURCE_ORIGIN+cells[1];source.searchable=searchable;source.password=cells.length>2?cells[2]:"";source.childDirectory=cells.length>3&&cells[3].equals("child");source.originPath=cells.length>4?cleanRuleCell(cells[4]):"";source.originUrl=cells.length>5?cells[5].trim():"";parsed.add(source);}}
      try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("c"),StandardCharsets.UTF_8))){Models.Source source=null;Set<String> ids=new HashSet<>();String line;while((line=reader.readLine())!=null){String[] cells=line.split("\\t",-1);if(cells.length==0)continue;if(cells[0].equals("C")&&cells.length>=4){source=new Models.Source();source.kind=Models.SOURCE_COMPOSITE;source.id=cells[1];source.title=cleanRuleCell(cells[2]);source.publisher=cleanRuleCell(cells[3]);source.avatarUrl=cells.length>4?cells[4]:"";source.description=cells.length>5?cells[5]:"";if(source.id.startsWith("local:")&&!source.title.isEmpty()){parsed.add(source);ids.clear();}else source=null;continue;}if(source==null||cells.length<5||!ids.add(cells[1]))continue;Models.SourceMember member=new Models.SourceMember();member.kind=cells[0].equals("D")?Models.MEMBER_FOLDER:cells[0].equals("R")?Models.MEMBER_REMOTE_FOLDER:cells[0].equals("L")?Models.MEMBER_DIRECTORY:Models.MEMBER_FILE;member.id=cells[1];member.parentId=cells[2];member.title=cleanRuleCell(cells[3]);member.lightweight=member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY;if(member.kind!=Models.MEMBER_FOLDER&&!cells[4].isEmpty())try{member.url=normalizeUserSourceUrl(CANONICAL_SOURCE_ORIGIN+cells[4]);}catch(Exception ignored){}member.password=cells.length>5?cells[5]:"";member.error=cells.length>6?cells[6]:"";member.searchable=cells.length>7&&cells[7].equals("1");member.iconUrl=cells.length>8?cells[8]:"";member.size=cells.length>9?cleanRuleCell(cells[9]):"";member.time=cells.length>10?cleanRuleCell(cells[10]):"";if(member.kind==Models.MEMBER_FOLDER||!member.url.isEmpty())source.members.add(member);}}
      catch(FileNotFoundException ignored){}
      cached=Collections.unmodifiableList(parsed);BUILT_IN_SOURCE_CACHE=cached;applyBuiltInHints(cached);return cached;
    }
  }

  List<Models.Source> recommendations(){List<Models.Source> out=new ArrayList<>();try(BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("r"),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){String[] cells=line.split("\\t",3);if(cells.length<2)continue;Models.Source source=new Models.Source();source.title=cleanRuleCell(cells[0]);source.id=source.url=normalizeUserSourceUrl(cells[1].startsWith("/")?CANONICAL_SOURCE_ORIGIN+cells[1]:cells[1]);source.password=cells.length>2?cleanRuleCell(cells[2]):"";if(!source.title.isEmpty())out.add(source);}}catch(Exception ignored){}return out;}

  String exportSourceRules()throws IOException{try{return sourceDocument(sources()).toString(2)+"\n";}catch(Exception error){throw new IOException("规则导出失败",error);}}
  String exportSourceRules(Collection<String> sourceIds)throws IOException{try{LinkedHashSet<String> selected=new LinkedHashSet<>();if(sourceIds!=null)for(String id:sourceIds)if(id!=null&&!id.trim().isEmpty())selected.add(sourceRootId(id.trim()));List<Models.Source> values=new ArrayList<>();for(Models.Source source:sources())if(selected.contains(sourceRootId(sourceId(source))))values.add(source);return sourceDocument(values).toString(2)+"\n";}catch(Exception error){throw new IOException("规则导出失败",error);}}

  ImportResult importSourceRules(String raw)throws IOException{
    if(raw==null||raw.length()>RULE_LIMIT)throw new IOException("规则文件过大");
    synchronized(USER_SOURCE_LOCK){SharedPreferences preferences=context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE);LinkedHashMap<String,Models.Source> saved=new LinkedHashMap<>(),knownIds=new LinkedHashMap<>(),knownContent=new LinkedHashMap<>();for(Models.Source source:loadUserSourcesLocked()){saved.put(sourceId(source),source);knownIds.put(sourceId(source),source);knownContent.put(sourceContentKey(source),source);}try{for(Models.Source source:sources()){knownIds.putIfAbsent(sourceId(source),source);knownContent.putIfAbsent(sourceContentKey(source),source);}}catch(IOException ignored){}
      Set<String> removed=loadRemovedSourcesLocked(preferences),touched=new LinkedHashSet<>();int added=0,duplicates=0,invalid=0;JSONArray values;try{Object parsed=new JSONTokener(raw).nextValue();if(!(parsed instanceof JSONObject)||((JSONObject)parsed).optInt("version")!=2)throw new IOException("仅支持 v2 源规则");values=((JSONObject)parsed).optJSONArray("sources");if(values==null)throw new IOException("v2 源规则缺少 sources");}catch(IOException error){throw error;}catch(Exception error){throw new IOException("源规则格式无效",error);}for(int i=0;i<values.length();i++)try{Models.Source source=sourceFromJson(values.getJSONObject(i));String id=sourceId(source),content=sourceContentKey(source);if(knownIds.containsKey(id)||knownContent.containsKey(content)){duplicates++;removed.remove(id);touched.add(id);continue;}saved.put(id,source);knownIds.put(id,source);knownContent.put(content,source);removed.remove(id);touched.add(id);added++;}catch(Exception ignored){invalid++;}
      try{if(!preferences.edit().putString(USER_SOURCE_KEY,sourceDocument(saved.values()).toString()).putString(REMOVED_SOURCE_KEY,new JSONArray(removed).toString()).commit())throw new IOException("规则保存失败");}catch(JSONException error){throw new IOException("规则合并失败",error);}for(String key:touched){touchSourceRevisionLocked(key);if(saved.containsKey(key))SOURCE_OVERRIDES.remove(key);}return new ImportResult(added,duplicates,invalid);
    }
  }

  String fetchSourceRules(String raw)throws IOException{
    URL current=validatedPublicHttps(raw);for(int redirects=0;redirects<5;redirects++){HttpURLConnection connection=(HttpURLConnection)current.openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent",DESKTOP_SEARCH_UA);connection.setRequestProperty("Accept","text/plain, application/json;q=0.9, */*;q=0.5");connection.setRequestProperty("Accept-Encoding","identity");try{int code=connection.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String location=connection.getHeaderField("Location");if(location==null||location.isEmpty())throw new IOException("规则链接跳转无效");current=validatedPublicHttps(new URL(current,location).toString());continue;}if(code!=200)throw new IOException("规则获取失败 HTTP "+code);long length=connection.getContentLengthLong();if(length>RULE_LIMIT)throw new IOException("规则文件过大");try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream(length>0?(int)length:4096)){byte[] buffer=new byte[4096];int total=0;for(int count;(count=input.read(buffer))>0;){total+=count;if(total>RULE_LIMIT)throw new IOException("规则文件过大");output.write(buffer,0,count);}return output.toString("UTF-8");}}finally{connection.disconnect();}}throw new IOException("规则链接跳转过多");
  }

  int searchableSourceCount(Models.Source extra)throws IOException{return searchableSourceCount(extra,null);}
  int searchableSourceCount(Models.Source extra,Set<String> allowedIds)throws IOException{return searchableSources(extra,allowedIds).size();}
  private LinkedHashMap<String,Models.Source> searchableSources(Models.Source extra)throws IOException{return searchableSources(extra,null);}
  private LinkedHashMap<String,Models.Source> searchableSources(Models.Source extra,Set<String> allowedIds)throws IOException{List<Models.Source> selected=new ArrayList<>();Set<String> removed=loadRemovedSources();for(Models.Source source:sources(removed)){String id=sourceId(source);if(allowedIds==null||allowedIds.contains(id))selected.add(source);}if(extra!=null&&!sourceId(extra).isEmpty()&&!removed.contains(sourceId(extra))&&(allowedIds==null||allowedIds.contains(sourceId(extra)))){boolean present=false;for(Models.Source source:selected)if(sourceId(source).equals(sourceId(extra))){present=true;break;}if(!present)selected.add(0,extra);}LinkedHashSet<String> claimed=new LinkedHashSet<>();for(Models.Source source:selected)if(source.kind==Models.SOURCE_COMPOSITE)for(Models.SourceMember member:source.members)claimed.add(member.url);LinkedHashMap<String,Models.Source> unique=new LinkedHashMap<>();for(Models.Source source:selected)if(source.kind==Models.SOURCE_COMPOSITE)unique.putIfAbsent(sourceId(source),source);for(Models.Source source:selected)if(source.kind==Models.SOURCE_OFFICIAL&&!claimed.contains(source.url))unique.putIfAbsent(sourceId(source),source);return unique;}

  private void storeUserSource(Models.Source source)throws Exception{
    synchronized(USER_SOURCE_LOCK){storeUserSourceLocked(source);touchSourceRevisionLocked(sourceId(source));}
  }

  private void commitUserSourceBatch(List<BatchSource> rows){
    synchronized(USER_SOURCE_LOCK){
      try{
        SharedPreferences preferences=context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE);Set<String> removed=loadRemovedSourcesLocked(preferences),known=new HashSet<>(),touched=new LinkedHashSet<>();LinkedHashMap<String,Models.Source> saved=new LinkedHashMap<>();
        for(Models.Source source:loadUserSourcesLocked()){saved.put(sourceId(source),source);if(source.kind!=Models.SOURCE_COMPOSITE&&!removed.contains(sourceId(source)))known.add(sourceId(source));}
        for(Models.Source source:builtInSources())if(source.kind!=Models.SOURCE_COMPOSITE&&!removed.contains(sourceId(source)))known.add(sourceId(source));
        int count=0;for(BatchSource row:rows){if(row.source==null||row.error!=null)continue;String key=sourceId(row.source);if(known.contains(key)){row.duplicate=true;continue;}known.add(key);saved.put(key,copySource(row.source));removed.remove(key);touched.add(key);row.added=true;count++;}
        if(count==0)return;
        if(!preferences.edit().putString(USER_SOURCE_KEY,sourceDocument(saved.values()).toString()).putString(REMOVED_SOURCE_KEY,new JSONArray(removed).toString()).commit())throw new IOException("用户源保存失败");
        for(String key:touched){touchSourceRevisionLocked(key);SOURCE_OVERRIDES.remove(key);}
      }catch(Exception error){String message=safeBatchError(error);for(BatchSource row:rows)if(row.added||row.duplicate){row.added=row.duplicate=false;row.error=message;}}
    }
  }

  private void storeUserSourceLocked(Models.Source source)throws Exception{String key=sourceId(source);if(key.isEmpty())throw new IOException("源身份无效");Models.Source value=copySource(source),baseline=builtInSource(key);if(baseline!=null&&baseline.kind==Models.SOURCE_COMPOSITE&&value.kind==Models.SOURCE_COMPOSITE)value=compositeOverlay(baseline,value);LinkedHashMap<String,Models.Source> saved=new LinkedHashMap<>();for(Models.Source old:loadUserSourcesLocked())saved.put(sourceId(old),old);saved.put(key,value);SharedPreferences preferences=context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE);Set<String> removed=loadRemovedSourcesLocked(preferences);removed.remove(key);if(!preferences.edit().putString(USER_SOURCE_KEY,sourceDocument(saved.values()).toString()).putString(REMOVED_SOURCE_KEY,new JSONArray(removed).toString()).commit())throw new IOException("用户源保存失败");SOURCE_OVERRIDES.remove(key);}

  Models.Source updateCompositeMetadata(String sourceId,String title,String publisher,String description)throws Exception{synchronized(USER_SOURCE_LOCK){Models.Source source=editableComposite(sourceId);String name=cleanRuleCell(title),author=cleanRuleCell(publisher),summary=description==null?"":description.trim();if(name.isEmpty()||name.length()>160)throw new IOException("请输入有效的合集标题");if(author.length()>80||summary.length()>4000||containsControl(author))throw new IOException("合集信息格式无效");source.title=name;source.publisher=author;source.description=summary;String icon=firstSoftwareIcon(source);if(!icon.isEmpty())source.avatarUrl=icon;storeUserSourceLocked(source);touchSourceRevisionLocked(sourceRootId(sourceId));return copySource(source);}}

  List<Models.Source> restoreOfficialSources()throws Exception{synchronized(USER_SOURCE_LOCK){List<Models.Source> built=builtInSources(),users=loadUserSourcesLocked(),kept=new ArrayList<>();Set<String> builtIds=new HashSet<>();Map<String,Models.Source> baselines=new HashMap<>();for(Models.Source source:built){String id=sourceId(source);builtIds.add(id);baselines.put(id,source);}for(Models.Source source:users){String id=sourceId(source),root=sourceRootId(id);Models.Source baseline=baselines.get(root);if(baseline==null){kept.add(source);continue;}if(baseline.kind==Models.SOURCE_COMPOSITE&&source.kind==Models.SOURCE_COMPOSITE){Models.Source overlay=source.overlay?copySource(source):compositeOverlay(baseline,source);overlay.overlay=true;overlay.metadataOverride=false;overlay.title=overlay.publisher=overlay.avatarUrl=overlay.description="";kept.add(overlay);}}SharedPreferences preferences=context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE);Set<String> removed=loadRemovedSourcesLocked(preferences);removed.removeAll(builtIds);if(!preferences.edit().putString(USER_SOURCE_KEY,sourceDocument(kept).toString()).putString(REMOVED_SOURCE_KEY,new JSONArray(removed).toString()).commit())throw new IOException("恢复官方源失败");for(String id:builtIds){SOURCE_OVERRIDES.remove(id);touchSourceRevisionLocked(id);}startCompositeWarmup();return sources(removed);}}

  List<Models.Source> resetAllSources()throws Exception{synchronized(USER_SOURCE_LOCK){SharedPreferences preferences=context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE);Set<String> touched=new LinkedHashSet<>();for(Models.Source source:builtInSources())touched.add(sourceId(source));for(Models.Source source:loadUserSourcesLocked())touched.add(sourceRootId(sourceId(source)));touched.addAll(loadRemovedSourcesLocked(preferences));touched.addAll(SOURCE_OVERRIDES.keySet());if(!preferences.edit().remove(USER_SOURCE_KEY).remove(REMOVED_SOURCE_KEY).commit())throw new IOException("重置源列表失败");context.getSharedPreferences(COMPOSITE_MEMBER_PREFS,Context.MODE_PRIVATE).edit().clear().apply();SOURCE_OVERRIDES.clear();COMPOSITE_MEMBER_CACHE.clear();SOURCE_PROFILES.clear();browseSessions.clear();startCompositeWarmup();for(String id:touched)if(!id.isEmpty())touchSourceRevisionLocked(id);return sources(Collections.emptySet());}}

  void removeSources(Collection<String> sourceIds)throws Exception{synchronized(USER_SOURCE_LOCK){Set<String> remove=new HashSet<>();for(String id:sourceIds)if(id!=null&&!id.isEmpty())remove.add(id);Map<String,Models.Source> baselines=new HashMap<>();for(Models.Source source:builtInSources())baselines.put(sourceId(source),source);SharedPreferences preferences=context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE);List<Models.Source> users=new ArrayList<>();for(Models.Source source:loadUserSourcesLocked()){String id=sourceId(source);if(!remove.contains(id)){users.add(source);continue;}Models.Source baseline=baselines.get(id);if(baseline!=null&&baseline.kind==Models.SOURCE_COMPOSITE&&source.kind==Models.SOURCE_COMPOSITE)users.add(source.overlay?source:compositeOverlay(baseline,source));}Set<String> hidden=loadRemovedSourcesLocked(preferences);hidden.addAll(remove);if(!preferences.edit().putString(USER_SOURCE_KEY,sourceDocument(users).toString()).putString(REMOVED_SOURCE_KEY,new JSONArray(hidden).toString()).commit())throw new IOException("源移除失败");for(String key:remove){touchSourceRevisionLocked(key);SOURCE_OVERRIDES.remove(key);}}}

  private List<Models.Source> loadUserSources(){synchronized(USER_SOURCE_LOCK){return loadUserSourcesLocked();}}

  private Set<String> loadRemovedSources(){synchronized(USER_SOURCE_LOCK){return loadRemovedSourcesLocked(context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE));}}

  private static Set<String> loadRemovedSourcesLocked(SharedPreferences preferences){Set<String> out=new LinkedHashSet<>();try{JSONArray values=new JSONArray(preferences.getString(REMOVED_SOURCE_KEY,"[]"));for(int i=0;i<values.length();i++){String value=values.optString(i);if(!value.isEmpty())out.add(sourceKey(value));}}catch(Exception ignored){}return out;}

  private List<Models.Source> loadUserSourcesLocked(){
    List<Models.Source> out=new ArrayList<>();SharedPreferences preferences=context.getSharedPreferences(USER_SOURCE_PREFS,Context.MODE_PRIVATE);String raw=preferences.getString(USER_SOURCE_KEY,"[]");
    try{
      Object parsed=new JSONTokener(raw==null?"[]":raw).nextValue();JSONArray values;boolean changed;if(parsed instanceof JSONObject){JSONObject root=(JSONObject)parsed;values=root.optJSONArray("sources");changed=root.optInt("version")!=2;}else if(parsed instanceof JSONArray){values=(JSONArray)parsed;changed=true;}else return out;if(values==null)return out;
      Map<String,Models.Source> baselines=new HashMap<>();try{for(Models.Source source:builtInSources())baselines.put(sourceId(source),source);}catch(IOException ignored){}
      LinkedHashMap<String,Models.Source> unique=new LinkedHashMap<>();for(int i=0;i<values.length();i++){JSONObject value=values.optJSONObject(i);if(value==null){changed=true;continue;}try{JSONArray rawMembers=value.optJSONArray("members");if(value.optInt("kind")==Models.SOURCE_COMPOSITE&&rawMembers!=null)for(int j=0;j<rawMembers.length();j++){JSONObject rawMember=rawMembers.optJSONObject(j);if(rawMember==null||!rawMember.has("id")||!rawMember.has("parent")||!rawMember.has("searchable")){changed=true;break;}int kind=rawMember.optInt("kind");if((kind==Models.MEMBER_FILE||kind==Models.MEMBER_DIRECTORY)&&(!rawMember.optBoolean("lightweight")||rawMember.has("title")||rawMember.has("size")||rawMember.has("time")||rawMember.has("description")))changed=true;}Models.Source source=sourceFromJson(value);String id=sourceId(source);Models.Source baseline=baselines.get(id);if(baseline!=null&&baseline.kind==Models.SOURCE_COMPOSITE&&source.kind==Models.SOURCE_COMPOSITE&&!source.overlay){source=compositeOverlay(baseline,source);changed=true;}else if(source.overlay&&baseline==null){changed=true;continue;}if(unique.put(id,source)!=null)changed=true;if(value.optInt("v",1)!=2)changed=true;}catch(Exception ignored){changed=true;}}
      out.addAll(unique.values());if(changed)preferences.edit().putString(USER_SOURCE_KEY,sourceDocument(out).toString()).commit();
    }catch(Exception ignored){}
    return out;
  }

  private static Models.SourceMember copyMember(Models.SourceMember value){Models.SourceMember out=new Models.SourceMember();out.kind=value.kind;out.id=value.id;out.parentId=value.parentId;out.title=value.title;out.url=value.url;out.password=value.password;out.iconUrl=value.iconUrl;out.size=value.size;out.time=value.time;out.description=value.description;out.error=value.error;out.searchable=value.searchable;out.lightweight=value.lightweight;out.metadataLoaded=value.metadataLoaded;out.iconLoaded=value.iconLoaded;out.detailsLoaded=value.detailsLoaded;out.refreshedAt=value.refreshedAt;return out;}
  private static Models.Source copySource(Models.Source value){Models.Source out=new Models.Source();copySourceInto(value,out);return out;}
  private static void copySourceInto(Models.Source value,Models.Source out){out.id=value.id;out.nodeId=value.nodeId;out.kind=value.kind;out.title=value.title;out.url=value.url;out.password=value.password;out.searchable=value.searchable;out.error=value.error;out.publisher=value.publisher;out.avatarUrl=value.avatarUrl;out.description=value.description;out.originPath=value.originPath;out.originUrl=value.originUrl;out.childDirectory=value.childDirectory;out.overlay=value.overlay;out.metadataOverride=value.metadataOverride;out.members.clear();for(Models.SourceMember member:value.members)out.members.add(copyMember(member));}
  private static JSONObject sourceJson(Models.Source value)throws JSONException{JSONObject out=new JSONObject().put("v",2).put("id",value.id).put("kind",value.kind).put("title",value.title).put("url",value.url).put("password",value.password).put("searchable",value.searchable).put("error",value.error).put("publisher",value.publisher).put("avatar",value.avatarUrl).put("description",value.description).put("childDirectory",value.childDirectory).put("originPath",value.originPath).put("originUrl",value.originUrl).put("overlay",value.overlay).put("metadataOverride",value.metadataOverride);JSONArray members=new JSONArray();for(Models.SourceMember member:value.members){boolean light=value.kind!=Models.SOURCE_SINGLE&&(member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY||member.lightweight);JSONObject item=new JSONObject().put("kind",member.kind).put("id",member.id).put("parent",member.parentId).put("url",member.url).put("password",member.password).put("icon",member.iconUrl).put("error",member.error).put("searchable",member.searchable).put("lightweight",light);if(!light)item.put("title",member.title).put("size",member.size).put("time",member.time).put("description",member.description);members.put(item);}return out.put("members",members);}
  private static JSONObject sourceDocument(Collection<Models.Source> sources)throws JSONException{JSONArray values=new JSONArray();for(Models.Source source:sources)values.put(sourceJson(source));return new JSONObject().put("version",2).put("sources",values);}
  private static Models.Source sourceFromJson(JSONObject value)throws Exception{
    Models.Source source=new Models.Source();source.kind=(byte)value.optInt("kind",Models.SOURCE_OFFICIAL);source.title=cleanRuleCell(value.optString("title"));source.password=value.optString("password");source.searchable=value.optBoolean("searchable");source.error=value.optString("error");source.publisher=cleanRuleCell(value.optString("publisher"));source.avatarUrl=value.optString("avatar").trim();source.description=value.optString("description");source.childDirectory=value.optBoolean("childDirectory");source.originPath=cleanRuleCell(value.optString("originPath"));source.originUrl=value.optString("originUrl").trim();source.overlay=value.optBoolean("overlay");source.metadataOverride=value.optBoolean("metadataOverride");
    if(source.kind==Models.SOURCE_COMPOSITE){
      source.id=value.optString("id").trim();if(!source.id.startsWith("local:")||source.id.length()>96)throw new IOException("自建合集身份无效");source.url="";JSONArray members=value.optJSONArray("members");if(members==null)members=new JSONArray();LinkedHashSet<String> unique=new LinkedHashSet<>(),ids=new LinkedHashSet<>(),folderIds=new LinkedHashSet<>();
      for(int i=0;i<members.length();i++){JSONObject raw=members.getJSONObject(i);Models.SourceMember member=new Models.SourceMember();member.kind=(byte)raw.optInt("kind",Models.MEMBER_UNKNOWN);if(member.kind<Models.MEMBER_FILE||member.kind>Models.MEMBER_REMOTE_FOLDER)member.kind=Models.MEMBER_FILE;member.id=cleanRuleCell(raw.optString("id"));if(member.id.isEmpty())member.id="m"+Integer.toString(i,36);if(!ids.add(member.id))throw new IOException("自建合集节点重复");if(member.kind==Models.MEMBER_FOLDER)folderIds.add(member.id);member.parentId=cleanRuleCell(raw.optString("parent"));member.password=raw.optString("password");if(member.password.length()>64||containsControl(member.password))throw new IOException("自建合集成员无效");if(member.kind!=Models.MEMBER_FOLDER){member.url=normalizeUserSourceUrl(raw.optString("url"));if(!unique.add(member.parentId+'\n'+memberKey(member)))throw new IOException("自建合集成员重复");}member.lightweight=member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY||raw.optBoolean("lightweight");member.title=member.lightweight?"":cleanRuleCell(raw.optString("title"));member.iconUrl=raw.optString("icon").trim();member.size=member.lightweight?"":cleanRuleCell(raw.optString("size"));member.time=member.lightweight?"":cleanRuleCell(raw.optString("time"));member.description=member.lightweight?"":raw.optString("description");member.error=cleanRuleCell(raw.optString("error"));member.searchable=raw.optBoolean("searchable");source.members.add(member);}
      if(!source.overlay)for(Models.SourceMember member:source.members)if(!member.parentId.isEmpty()&&!folderIds.contains(member.parentId))member.parentId="";source.searchable=false;if((!source.overlay||source.metadataOverride)&&source.title.isEmpty())throw new IOException("自建合集标题无效");
    }else if(source.kind==Models.SOURCE_SINGLE){
      source.overlay=false;source.metadataOverride=false;source.url=normalizeUserSourceUrl(value.optString("url"));source.id=source.url;source.searchable=false;JSONArray members=value.optJSONArray("members");if(members==null||members.length()!=1)throw new IOException("单软件源结构无效");JSONObject raw=members.getJSONObject(0);int memberKind=raw.optInt("kind");if(memberKind!=Models.MEMBER_FILE&&memberKind!=Models.MEMBER_DIRECTORY)throw new IOException("单软件源成员无效");Models.SourceMember member=new Models.SourceMember();member.kind=(byte)memberKind;member.id="file";member.url=normalizeUserSourceUrl(raw.optString("url",source.url));if(member.kind==Models.MEMBER_FILE&&!member.url.equals(source.url))throw new IOException("单文件源链接不一致");member.password=raw.optString("password",source.password);if(member.password.length()>64||containsControl(member.password))throw new IOException("单软件源密码无效");member.title=cleanRuleCell(raw.optString("title",source.title));member.iconUrl=raw.optString("icon",source.avatarUrl).trim();member.size=cleanRuleCell(raw.optString("size"));member.time=cleanRuleCell(raw.optString("time"));member.description=raw.optString("description",source.description);member.error=cleanRuleCell(raw.optString("error"));member.lightweight=false;member.metadataLoaded=true;member.detailsLoaded=true;source.title=firstNonEmpty(member.title,source.title,memberFallbackTitle(member.url));source.avatarUrl=firstNonEmpty(member.iconUrl,source.avatarUrl);source.description=firstNonEmpty(member.description,source.description);source.members.add(member);
    }else{source.kind=Models.SOURCE_OFFICIAL;source.overlay=false;source.metadataOverride=false;source.url=normalizeUserSourceUrl(value.optString("url"));source.id=source.url;if(source.title.isEmpty())source.title=source.url;}
    return source;
  }
  private static String cleanRuleCell(String value){return(value==null?"":value).replace('\t',' ').replace('\r',' ').replace('\n',' ').trim();}
  private static URL validatedPublicHttps(String raw)throws IOException{
    try{URL url=new URL(raw==null?"":raw.trim());String host=url.getHost().toLowerCase(Locale.ROOT);if(!url.getProtocol().equalsIgnoreCase("https")||host.isEmpty()||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443)||host.equals("localhost")||host.endsWith(".local"))throw new IOException("请输入有效的 HTTPS 规则链接");for(InetAddress address:InetAddress.getAllByName(host))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())throw new IOException("规则链接不能访问本地网络");return url;}catch(IOException error){throw error;}catch(Exception error){throw new IOException("请输入有效的 HTTPS 规则链接",error);}
  }
  private static String sourceKey(String id){return id;}
  private long touchSourceRevisionLocked(String key){long revision=++sourceRevisionSequence;if(revision==0)revision=++sourceRevisionSequence;SOURCE_REVISIONS.put(key,revision);invalidateSourceNameIndex();return revision;}
  private static boolean sourceRevisionMatchesLocked(SourceRetestPlan plan){Long current=SOURCE_REVISIONS.get(plan.key);return current!=null&&current==plan.revision;}
  private boolean hasUserSourceLocked(String key){for(Models.Source source:loadUserSourcesLocked())if(sourceId(source).equals(key))return true;return false;}
  private static String userAgent(byte ua){return ua==UA_DESKTOP?DESKTOP_SEARCH_UA:ANDROID_UA;}
  private static byte otherUa(byte ua){return ua==UA_DESKTOP?UA_ANDROID:UA_DESKTOP;}
  private static byte uaBit(byte ua){return(byte)(1<<(ua&1));}
  private static int templateInitial(byte ua,byte template,byte peerTemplate){if(template==TEMPLATE_CLASSIC)return ua==UA_DESKTOP?CLASSIC_DESKTOP_INITIAL_PAGE_INTERVAL_MS:CLASSIC_ANDROID_INITIAL_PAGE_INTERVAL_MS;if(template==TEMPLATE_MINIMAL){if(ua==UA_DESKTOP)return MINIMAL_DESKTOP_INITIAL_PAGE_INTERVAL_MS;return peerTemplate==TEMPLATE_MINIMAL?MINIMAL_ANDROID_INITIAL_PAGE_INTERVAL_MS:CLASSIC_REFLOW_ANDROID_INITIAL_PAGE_INTERVAL_MS;}return UNKNOWN_INITIAL_PAGE_INTERVAL_MS;}
  private static int templateNext(byte ua,byte template,byte peerTemplate){if(template==TEMPLATE_CLASSIC)return ua==UA_DESKTOP?CLASSIC_DESKTOP_NEXT_PAGE_INTERVAL_MS:CLASSIC_ANDROID_NEXT_PAGE_INTERVAL_MS;if(template==TEMPLATE_MINIMAL){if(ua==UA_DESKTOP)return MINIMAL_DESKTOP_NEXT_PAGE_INTERVAL_MS;return peerTemplate==TEMPLATE_MINIMAL?MINIMAL_ANDROID_NEXT_PAGE_INTERVAL_MS:CLASSIC_REFLOW_ANDROID_NEXT_PAGE_INTERVAL_MS;}return UNKNOWN_NEXT_PAGE_INTERVAL_MS;}
  private static ThreadPoolExecutor newSearchPool(){ThreadPoolExecutor pool=new ThreadPoolExecutor(0,Integer.MAX_VALUE,30L,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread thread=new Thread(r,"lanzou-search-http");thread.setDaemon(true);return thread;});pool.allowCoreThreadTimeOut(true);return pool;}
  private static ThreadPoolExecutor newCompositeWarmPool(){ThreadPoolExecutor pool=new ThreadPoolExecutor(COMPOSITE_WARM_WORKERS,COMPOSITE_WARM_WORKERS,30L,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r->{Thread thread=new Thread(r,"lanzou-composite-warm");thread.setDaemon(true);return thread;});pool.allowCoreThreadTimeOut(true);return pool;}
  private static ScheduledThreadPoolExecutor newSearchScheduler(){ScheduledThreadPoolExecutor out=new ScheduledThreadPoolExecutor(1,r->{Thread thread=new Thread(r,"lanzou-search-timer");thread.setDaemon(true);return thread;});out.setRemoveOnCancelPolicy(true);out.setKeepAliveTime(30L,TimeUnit.SECONDS);out.allowCoreThreadTimeOut(true);return out;}
  private static String profileKey(String url){String root=parseFolderTarget(url).rootUrl;try{return normalizeUserSourceUrl(root);}catch(Exception ignored){return root;}}
  private static SourceProfile sourceProfile(String url){return SOURCE_PROFILES.computeIfAbsent(profileKey(url),key->new SourceProfile(builtInHint(key)));}
  private static int builtInHint(String key){List<Models.Source> sources=BUILT_IN_SOURCE_CACHE;byte[] hints=BUILT_IN_SOURCE_HINTS;if(sources==null||hints==null)return 0;for(int i=0;i<sources.size()&&i<hints.length;i++)if(sources.get(i).url.equals(key))return hints[i]&255;return 0;}
  private static void applyBuiltInHints(List<Models.Source> sources){byte[] hints=BUILT_IN_SOURCE_HINTS;if(hints==null)return;for(int i=0;i<sources.size()&&i<hints.length;i++){SourceProfile profile=SOURCE_PROFILES.get(sources.get(i).url);if(profile!=null)profile.applyHint(hints[i]&255);}}
  private static boolean capabilityAllowed(byte seen,byte available,byte ua){byte bit=uaBit(ua);return(seen&bit)==0||(available&bit)!=0;}
  private static boolean containsControl(String value){for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return true;return false;}
  private static String normalizeUserSourceUrl(String raw)throws IOException{
    try{String value=(raw==null?"":raw.trim()).replace('。','.').replace('．','.');URI uri=new URI(value);String scheme=uri.getScheme(),host=uri.getHost(),path=uri.getRawPath();boolean typo=host!=null&&LANZOU_TYPO_HOST.matcher(host.toLowerCase(Locale.ROOT)).matches();if(scheme==null||(!scheme.equalsIgnoreCase("http")&&!scheme.equalsIgnoreCase("https"))||host==null||(!LANZOU_HOST.matcher(host).matches()&&!typo)||uri.getRawUserInfo()!=null||uri.getPort()!=-1)throw new IOException("请输入有效的蓝奏目录链接");if(path==null||path.isEmpty()||path.equals("/"))throw new IOException("请输入蓝奏目录分享链接");return CANONICAL_SOURCE_ORIGIN+path;}catch(IOException error){throw error;}catch(Exception ignored){throw new IOException("请输入有效的蓝奏目录链接");}
  }

  Models.Folder browse(String url,String password,boolean refresh) throws Exception {
    return browsePage(url,password,refresh,1,NO_DEADLINE);
  }

  Models.Folder browseSource(Models.Source source,boolean refresh)throws Exception{return source.kind==Models.SOURCE_COMPOSITE?compositeSnapshot(source):source.kind==Models.SOURCE_SINGLE?singleSnapshot(source):browse(source.url,source.password,refresh);}

  Models.Folder browseSourceMetadata(Models.Source source)throws Exception{
    if(source.kind==Models.SOURCE_COMPOSITE)return compositeSnapshot(source);if(source.kind==Models.SOURCE_SINGLE)return singleSnapshot(source);String pwd=source.password==null?"":source.password;SourceProfile profile=sourceProfile(source.url);byte first=profile.directoryUa==UA_UNKNOWN?UA_ANDROID:profile.directoryUa;Exception last=null;
    for(int attempt=0;attempt<2;attempt++){byte ua=attempt==0?first:otherUa(first);if(!capabilityAllowed(profile.directorySeen,profile.directoryAvailable,ua))continue;try{DirectLink session=browseSession(source.url,pwd,NO_DEADLINE,false,ua);Models.Folder out=copyFolder(session.metadata);out.page=1;out.url=source.url;out.password=pwd;out.folderId=session.target.folderId;profile.directoryUa=ua;return out;}catch(Exception error){last=error;}}
    throw last==null?new IOException("蓝奏目录暂不可用"):last;
  }

  boolean hasFolderCache(Models.Source source,int page){return source.kind==Models.SOURCE_SINGLE||source.kind==Models.SOURCE_COMPOSITE?source.kind==Models.SOURCE_SINGLE||sourceFolderCache(source,page).isFile():hasFolderCache(source.url,source.password,page);}

  private File sourceFolderCache(Models.Source source,int page){return new File(context.getCacheDir(),"source-v3-"+Integer.toHexString(sourceCacheFingerprint(source).hashCode())+"-p"+Math.max(1,page)+".json");}

  private static String sourceCacheFingerprint(Models.Source source){StringBuilder out=new StringBuilder(sourceId(source)).append('\n').append(source.title).append('\n').append(source.publisher).append('\n').append(source.avatarUrl).append('\n').append(source.description);for(Models.SourceMember member:source.members){out.append('\n').append(member.kind).append('\t').append(member.id).append('\t').append(member.parentId).append('\t').append(member.url).append('\t').append(member.password).append('\t').append(member.iconUrl).append('\t').append(member.error);if(!member.lightweight)out.append('\t').append(member.title).append('\t').append(member.size).append('\t').append(member.time).append('\t').append(member.description);}return out.toString();}

  Models.Folder compositeSnapshot(Models.Source source){
    Models.Folder out=new Models.Folder();out.title=source.title;out.publisher=source.publisher;out.avatarUrl=source.avatarUrl;out.description=source.description;out.url="";out.page=1;out.hasMore=false;
    for(Models.SourceMember stored:source.members){if(!stored.parentId.equals(source.nodeId))continue;Models.SourceMember member=stored.kind==Models.MEMBER_FOLDER?copyMember(stored):effectiveCompositeMember(stored);if(!member.error.isEmpty())out.failedMembers++;if(member.kind!=Models.MEMBER_FOLDER&&member.url.isEmpty())continue;Models.Item item=itemFromMember(member,source.title);if(member.kind==Models.MEMBER_FOLDER){if(member.title.isEmpty())continue;item.folder=true;item.folderId=member.id;item.url=LOCAL_NODE_MARKER+source.id+'#'+member.id;item.shareUrl="";}out.items.add(item);}
    applyAvatarFallback(out);return out;
  }

  private static Models.Folder singleSnapshot(Models.Source source){
    Models.Folder out=new Models.Folder();out.title=source.title;out.publisher=source.publisher;out.avatarUrl=source.avatarUrl;out.description=source.description;out.url=source.url;out.password=source.password;out.page=1;out.hasMore=false;if(source.members.size()==1){Models.Item item=itemFromMember(source.members.get(0),source.title);item.folder=false;out.items.add(item);}applyAvatarFallback(out);return out;
  }

  void hydrateCompositeSource(Models.Source source,boolean refresh,Models.Progress progress)throws InterruptedException{
    List<Models.SourceMember> pending=new ArrayList<>();for(Models.SourceMember member:source.members)if(member.parentId.equals(source.nodeId)&&(member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY)&&!member.url.isEmpty()&&(refresh||!effectiveCompositeMember(member).detailsLoaded))pending.add(copyMember(member));if(pending.isEmpty())return;
    ExecutorService workers=Executors.newFixedThreadPool(Math.min(MAX_PROBE_WORKERS,pending.size()));ExecutorCompletionService<Models.SourceMember> completed=new ExecutorCompletionService<>(workers);List<Future<Models.SourceMember>> futures=new ArrayList<>();for(Models.SourceMember input:pending)futures.add(completed.submit(()->{try{return hydrateCompositeMember(input,refresh);}catch(Exception error){Models.SourceMember failed=effectiveCompositeMember(input);failed.lightweight=true;if(failed.title.isEmpty())failed.title=memberFallbackTitle(input.url);failed.error=sourceTestError(error);failed.refreshedAt=System.currentTimeMillis();return failed;}}));
    int done=0;try{while(done<pending.size()&&(progress==null||!progress.isCancelled())){Future<Models.SourceMember> future=completed.poll(100,TimeUnit.MILLISECONDS);if(future==null)continue;Models.SourceMember member;try{member=future.get();}catch(ExecutionException error){done++;if(progress!=null)progress.onFailure(source.title);continue;}done++;if(progress!=null){progress.onItemUpdated(itemFromMember(member,source.title));progress.onProgress(done,pending.size(),done,member.title);}}}finally{for(Future<?> future:futures)if(!future.isDone())future.cancel(true);workers.shutdownNow();}
  }

  private Models.SourceMember hydrateCompositeMember(Models.SourceMember input,boolean refresh)throws Exception{
    Models.SourceMember cached=effectiveCompositeMember(input);if(!refresh&&cached.detailsLoaded)return cached;Models.SourceMember live=probeSingleSource(input.url,input.password);live.id=input.id;live.parentId=input.parentId;live.lightweight=live.metadataLoaded=live.iconLoaded=live.detailsLoaded=true;live.refreshedAt=System.currentTimeMillis();if(live.title.isEmpty())live.title=input.title;if(live.iconUrl.isEmpty())live.iconUrl=input.iconUrl;live.error="";cacheCompositeMember(live,true,input.title);return live;
  }

  private Models.SourceMember hydrateCompositeMetadata(Models.SourceMember input)throws Exception{
    Models.SourceMember cached=effectiveCompositeMember(input);if(cached.metadataLoaded)return cached;Models.SourceMember live=probeSingleSource(input.url,input.password);live.id=input.id;live.parentId=input.parentId;live.lightweight=live.metadataLoaded=true;live.iconLoaded=live.detailsLoaded=false;live.refreshedAt=System.currentTimeMillis();if(live.title.isEmpty())live.title=input.title;live.iconUrl=input.iconUrl;live.description="";live.error="";cacheCompositeMember(live,true,input.title);return live;
  }

  private Models.SourceMember hydrateCompositeIcon(Models.SourceMember input)throws Exception{Models.SourceMember cached=effectiveCompositeMember(input);if(cached.iconLoaded)return cached;Models.SourceMember live=probeSingleSource(input.url,input.password);live.id=input.id;live.parentId=input.parentId;live.lightweight=live.metadataLoaded=live.iconLoaded=true;live.detailsLoaded=false;live.refreshedAt=System.currentTimeMillis();if(live.title.isEmpty())live.title=input.title;if(live.iconUrl.isEmpty())live.iconUrl=input.iconUrl;live.description="";live.error="";cacheCompositeMember(live,true,input.title);return live;}

  private static Models.SourceMember cachedCompositeMember(Models.SourceMember input){Models.SourceMember value=effectiveCompositeMember(input);return value.metadataLoaded||value.detailsLoaded?value:null;}

  private static Models.SourceMember effectiveCompositeMember(Models.SourceMember input){Models.SourceMember out=copyMember(input),cached=COMPOSITE_MEMBER_CACHE.get(memberKey(input));if(cached==null)return out;if(!cached.title.isEmpty())out.title=cached.title;if(!cached.size.isEmpty())out.size=cached.size;if(!cached.time.isEmpty())out.time=cached.time;if(!cached.iconUrl.isEmpty())out.iconUrl=cached.iconUrl;if(!cached.description.isEmpty())out.description=cached.description;out.error=cached.error;out.refreshedAt=cached.refreshedAt;out.metadataLoaded=cached.metadataLoaded;out.iconLoaded=cached.iconLoaded;out.detailsLoaded=cached.detailsLoaded;return out;}

  private void cacheCompositeMember(Models.SourceMember value,boolean persist,String baselineTitle){Models.SourceMember prior=COMPOSITE_MEMBER_CACHE.put(memberKey(value),copyMember(value));if(!(prior==null?baselineTitle:prior.title).equals(value.title))invalidateSourceNameIndex();if(persist)persistCompositeMember(value);}

  private String compositeMemberPreferenceKey(Models.SourceMember value){return UUID.nameUUIDFromBytes(memberKey(value).getBytes(StandardCharsets.UTF_8)).toString();}

  private void persistCompositeMember(Models.SourceMember value){try{JSONObject raw=new JSONObject().put("t",value.title).put("z",value.size).put("d",value.time).put("i",value.iconUrl).put("c",value.description).put("m",value.metadataLoaded).put("p",value.iconLoaded).put("f",value.detailsLoaded).put("a",value.refreshedAt);context.getSharedPreferences(COMPOSITE_MEMBER_PREFS,Context.MODE_PRIVATE).edit().putString(compositeMemberPreferenceKey(value),raw.toString()).apply();}catch(Exception ignored){}}

  private void loadPersistedCompositeMembers(){try{SharedPreferences preferences=context.getSharedPreferences(COMPOSITE_MEMBER_PREFS,Context.MODE_PRIVATE);for(Models.Source source:sources())if(source.kind==Models.SOURCE_COMPOSITE)for(Models.SourceMember member:source.members)if(member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY){String raw=preferences.getString(compositeMemberPreferenceKey(member),"");if(raw.isEmpty())continue;JSONObject value=new JSONObject(raw);Models.SourceMember cached=copyMember(member);cached.title=cleanRuleCell(value.optString("t",cached.title));cached.size=cleanRuleCell(value.optString("z"));cached.time=cleanRuleCell(value.optString("d"));cached.iconUrl=value.optString("i",cached.iconUrl);cached.description=value.optString("c");cached.metadataLoaded=value.optBoolean("m");cached.iconLoaded=value.optBoolean("p");cached.detailsLoaded=value.optBoolean("f");cached.refreshedAt=value.optLong("a");COMPOSITE_MEMBER_CACHE.put(memberKey(cached),cached);}}catch(Exception ignored){}}

  private void warmCompositeMetadata(){try{List<Models.SourceMember> pending=new ArrayList<>();for(Models.Source source:sources())if(source.kind==Models.SOURCE_COMPOSITE)for(Models.SourceMember member:source.members)if((member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY)&&!member.url.isEmpty()&&!effectiveCompositeMember(member).metadataLoaded)pending.add(copyMember(member));if(pending.isEmpty())return;ExecutorCompletionService<Models.SourceMember> completed=new ExecutorCompletionService<>(compositeWarmPool);int next=0,running=0,limit=Math.min(COMPOSITE_WARM_WORKERS,pending.size());while(running<limit){submitCompositeWarm(completed,pending.get(next++));running++;}while(running>0){try{completed.take().get();}catch(ExecutionException ignored){}running--;if(next<pending.size()){submitCompositeWarm(completed,pending.get(next++));running++;}}}catch(InterruptedException error){Thread.currentThread().interrupt();}catch(Exception ignored){}}

  private void submitCompositeWarm(ExecutorCompletionService<Models.SourceMember> completed,Models.SourceMember member){completed.submit(()->hydrateCompositeMetadata(member));}

  private void startCompositeWarmup(){COMPOSITE_WARM_REQUESTED.set(true);if(!COMPOSITE_WARM_RUNNING.compareAndSet(false,true))return;searchPool.execute(()->{try{do{COMPOSITE_WARM_REQUESTED.set(false);warmCompositeMetadata();}while(COMPOSITE_WARM_REQUESTED.get());}finally{COMPOSITE_WARM_RUNNING.set(false);if(COMPOSITE_WARM_REQUESTED.get())startCompositeWarmup();}});}

  Models.Source compositeFolderSource(Models.Source current,String nodeId,String title)throws IOException{if(current==null||current.kind!=Models.SOURCE_COMPOSITE)throw new IOException("不是自建合集目录");for(Models.SourceMember member:current.members)if(member.kind==Models.MEMBER_FOLDER&&member.id.equals(nodeId))return compositeView(current,nodeId,firstNonEmpty(title,member.title));throw new IOException("文件夹已不存在");}

  private static Models.Source compositeView(Models.Source root,String nodeId,String title){Models.Source out=copySource(root);out.nodeId=nodeId==null?"":nodeId;if(title!=null&&!title.isEmpty())out.title=title;return out;}

  private static void applyAvatarFallback(Models.Folder folder){if(folder==null||!folder.avatarUrl.isEmpty())return;for(Models.Item item:folder.items)if(item!=null&&!item.iconUrl.isEmpty()){folder.avatarUrl=item.iconUrl;return;}}

  Models.Folder browsePage(String url,String password,boolean refresh,int page) throws Exception {
    return browsePage(url,password,refresh,page,NO_DEADLINE);
  }

  boolean hasFolderCache(String url,String password,int page){return folderCache(url,password,page).isFile();}

  boolean sameFolder(Models.Folder first,Models.Folder second)throws Exception{JSONObject a=toJson(first),b=toJson(second);a.remove("nextPageReadyAt");b.remove("nextPageReadyAt");return a.toString().equals(b.toString());}

  private File folderCache(String url,String password,int page){String pwd=password==null?"":password;return new File(context.getCacheDir(),"folder-v3-"+Integer.toHexString((url+'\n'+pwd).hashCode())+"-p"+Math.max(1,page)+".json");}

  /** Loads every 50-item page. UI code should normally use browsePage so page one can render immediately. */
  Models.Folder browseAll(String url,String password,boolean refresh) throws Exception {
    return browseAll(url,password,refresh,NO_DEADLINE);
  }

  private Models.Folder browseAll(String url,String password,boolean refresh,long deadline)throws Exception{Models.Folder out=browsePage(url,password,refresh,1,deadline);int page=1;while(out.hasMore&&page<100){checkDeadline(deadline);Models.Folder next=browsePage(url,password,refresh,++page,deadline);out.items.addAll(next.items);out.hasMore=next.hasMore;out.page=next.page;out.nextPageReadyAt=next.nextPageReadyAt;}return out;}

  /** Flattens selected files and folders without concurrently hitting the same directory. */
  List<Models.Item> collectFiles(Collection<Models.Item> roots,Models.FolderProgress progress)throws Exception{
    LinkedHashMap<String,Models.Item> files=new LinkedHashMap<>();ArrayDeque<Models.Item> folders=new ArrayDeque<>();Set<String> queued=new HashSet<>();int completed=0;
    if(roots!=null)for(Models.Item item:roots)if(item!=null){if(item.folder)enqueueFolder(folders,queued,item,null);else addFile(files,item);}
    if(progress!=null)progress.onProgress(0,files.size(),"");
    while(!folders.isEmpty()){
      Models.Item folder=folders.removeFirst();completed++;int firstApiFolderCount=0;
      for(int page=1;page<=100;page++){
        Models.Folder listing=browsePage(folder.url,folder.password,true,page,NO_DEADLINE,true);if(page==1)firstApiFolderCount=listing.apiFolderCount;for(Models.Item item:listing.items){inherit(item,folder);if(item.folder)enqueueFolder(folders,queued,item,folder);else addFile(files,item);}if(progress!=null)progress.onProgress(completed,files.size(),folder.title);if(!listing.hasMore)break;if(page==100)throw new IOException("目录文件超过100页，未完整展开");
      }
      if(firstApiFolderCount>=PAGE_SIZE){DirectLink session=browseSession(folder.url,folder.password,NO_DEADLINE,false);SourceProfile profile=sourceProfile(folder.url);for(int page=2;page<=100;page++){int[] count={0};List<Models.Item> batch=apiFolders(session,NO_DEADLINE,page,true,count,profile);for(Models.Item item:batch){inherit(item,folder);enqueueFolder(folders,queued,item,folder);}if(progress!=null)progress.onProgress(completed,files.size(),folder.title);if(count[0]<PAGE_SIZE)break;if(page==100)throw new IOException("目录子文件夹超过100页，未完整展开");}}
    }
    return new ArrayList<>(files.values());
  }

  private static boolean enqueueFolder(Deque<Models.Item> folders,Set<String> queued,Models.Item item,Models.Item parent){if(item.url.isEmpty())return false;inherit(item,parent);DirectLink target=parseFolderTarget(item.url);String key=target.rootUrl+'\n'+target.folderId;if(!queued.add(key))return false;folders.addLast(item);return true;}
  private static String searchFolderKey(String url){DirectLink target=parseFolderTarget(url);return target.rootUrl+'\n'+target.folderId;}
  private static void inherit(Models.Item item,Models.Item parent){if(parent==null)return;if(!parent.password.isEmpty())item.password=parent.password;if(!parent.source.isEmpty())item.source=parent.source;}
  private static void addFile(Map<String,Models.Item> files,Models.Item item){String key=firstNonEmpty(item.shareUrl,item.url);if(!key.isEmpty())files.putIfAbsent(key,item);}

  /** Best-effort share-page description; never blocks a download with a parse failure. */
  String fileDescription(String shareUrl)throws Exception{
    try{if(shareUrl==null||shareUrl.trim().isEmpty())return"";String url=shareUrl.trim();requireLanzouPage(url);DirectLink page=getGuarded(url,NO_DEADLINE,ANDROID_UA);requireLanzouPage(page.url);String value=strip(divInner(page.html,"appdes"));if(value.isEmpty())value=strip(divInner(page.html,"file-des"));if(value.isEmpty())value=strip(divInner(page.html,"n_file_info"));if(value.isEmpty())value=strip(cap(page.html,"(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)"));value=value.replaceFirst("^(?:文件)?(?:描述|简介)\\s*[：:]\\s*","").trim();if(value.matches("(?s)^(?:文件)?大小\\s*[：:].*")||value.equals("蓝奏云"))return"";return value;}catch(Exception ignored){return"";}
  }

  private Models.Folder browsePage(String url,String password,boolean refresh,int requestedPage,long deadline) throws Exception {
    return browsePage(url,password,refresh,requestedPage,deadline,false);
  }

  private Models.Folder browsePage(String url,String password,boolean refresh,int requestedPage,long deadline,boolean strictFolders) throws Exception {
    int page=Math.max(1,requestedPage);String pwd=password==null?"":password;
    File cache=folderCache(url,pwd,page);Models.Folder cached=null;if(cache.isFile())try{cached=fromJson(read(cache));}catch(Exception ignored){cached=null;}if(cached!=null&&!refresh)return cached;
    SourceProfile profile=sourceProfile(url);byte first=profile.directoryUa==UA_UNKNOWN?UA_ANDROID:profile.directoryUa;PageResult empty=null;Exception last=null;
    for(int attempt=0;attempt<2;attempt++){byte ua=attempt==0?first:otherUa(first);if(!capabilityAllowed(profile.directorySeen,profile.directoryAvailable,ua))continue;try{PageResult result=browsePageUa(url,pwd,refresh,page,deadline,strictFolders,profile,ua);if(result.usable){profile.directoryUa=ua;return result.folder;}if(empty==null)empty=result;}catch(Exception error){last=error;}}
    if(empty!=null)return empty.folder;if(cached!=null&&!strictFolders)return cached;throw last==null?new IOException("蓝奏目录暂不可用"):last;
  }

  private PageResult browsePageUa(String url,String pwd,boolean refresh,int page,long deadline,boolean strictFolders,SourceProfile profile,byte ua)throws Exception{
    File cache=folderCache(url,pwd,page);
    DirectLink session=browseSession(url,pwd,deadline,refresh&&page==1,ua);Models.Folder out=copyFolder(session.metadata);out.page=page;out.url=url;out.password=pwd;out.folderId=session.target.folderId;
    LinkedHashMap<String,Models.Item> items=new LinkedHashMap<>();
    if(page==1){
      if(session.target.folderId.isEmpty())for(Models.Item folder:staticFolders(session))items.put(folder.url,folder);
      if(!session.folderEndpoint.isEmpty()){int[] count=strictFolders?new int[1]:null;List<Models.Item> folders=apiFolders(session,deadline,1,strictFolders,count,profile);out.apiFolderCount=count==null?folders.size():count[0];for(Models.Item folder:folders)items.put(folder.url,folder);}
    }
    Map<String,String> form=new LinkedHashMap<>(session.form);form.put("pg",String.valueOf(page));
    JSONObject data=postListing(session,form,deadline,page,profile);JSONArray array=data.optJSONArray("text");int valid=0,state=data.optInt("zt",-1);if(state==1&&(array==null||page>1&&array.length()==0))throw new IOException("目录分页返回空内容");
    if(state==1){session.authorized=true;}if(state==1&&array!=null)for(int i=0;i<array.length();i++){
      JSONObject value=array.optJSONObject(i);if(value==null)continue;Models.Item item=item(value,origin(session.page.url),out.title,pwd);if(item.url.isEmpty()||item.url.endsWith("/-1"))continue;valid++;items.put(item.url,item);
    }
    out.items.addAll(items.values());out.hasMore=state==1&&array!=null&&array.length()>=PAGE_SIZE&&valid>0;applyAvatarFallback(out);
    out.nextPageReadyAt=out.hasMore?pageReadyAt(pageRateKey(session)):0L;
    boolean usable=state==1&&valid>0||!out.items.isEmpty();if(usable)writeAtomic(cache,toJson(out).toString());return new PageResult(out,usable);
  }

  private DirectLink browseSession(String url,String password,long deadline,boolean force)throws Exception{
    SourceProfile profile=sourceProfile(url);return browseSession(url,password,deadline,force,profile.directoryUa==UA_UNKNOWN?UA_ANDROID:profile.directoryUa);
  }

  private DirectLink browseSession(String url,String password,long deadline,boolean force,byte ua)throws Exception{
    DirectLink target=parseFolderTarget(url);String key=target.rootUrl+'\n'+password+'\n'+target.folderId+'\n'+ua;long now=System.currentTimeMillis();pruneBrowseSessions(now);DirectLink hit=browseSessions.get(key);
    if(!force&&hit!=null&&now-hit.createdAt<SESSION_TTL_MS)return hit;if(hit!=null)browseSessions.remove(key,hit);
    DirectLink session=new DirectLink();session.target=target;session.ua=ua;session.page=getGuarded(target.rootUrl,deadline,userAgent(ua));requireLanzouPage(session.page.url);requireActiveShare(session.page);session.template=pageTemplate(session.page.html);sourceProfile(target.rootUrl).observeTemplate(ua,session.template);session.createdAt=now;
    session.endpoint=sameOriginEndpoint(session.page,cap(session.page.html,"url\\s*:\\s*['\"]([^'\"]*filemoreajax\\.php\\?file=\\d+[^'\"]*)['\"]"),"/filemoreajax.php");session.folderEndpoint=sameOriginEndpoint(session.page,cap(session.page.html,"url\\s*:\\s*['\"]([^'\"]*foldermoreajax\\.php\\?file=\\d+[^'\"]*)['\"]"),"/foldermoreajax.php");
    session.fid=cap(session.endpoint,"[?&]file=([^&#]+)");session.form=formValues(session.page.html);session.form.put("fid",session.fid);session.form.put("lx",firstNonEmpty(session.form.get("lx"),"2"));session.form.put("rep",firstNonEmpty(session.form.get("rep"),"0"));session.form.put("up",firstNonEmpty(session.form.get("up"),"1"));session.form.put("vip",firstNonEmpty(session.form.get("vip"),"0"));
    if(!target.folderId.isEmpty()){session.form.put("folder_id",target.folderId);session.form.put("ls","1");}
    if(!password.isEmpty()){session.form.put("pwd",password);session.form.put("ls","1");}
    String searchEndpoint=searchEndpoint(session.page),searchSign=searchSign(session.page.html);
    Models.Folder meta=new Models.Folder();meta.url=url;meta.password=password;meta.folderId=target.folderId;meta.title=strip(divInner(session.page.html,"user-title"));if(meta.title.isEmpty())meta.title=strip(cap(session.page.html,"(?is)<title[^>]*>(.*?)</title>"));
    meta.publisher=strip(divInner(session.page.html,"user-name")).replaceAll("的分享文件.*$","").trim();
    meta.avatarUrl=cap(session.page.html,"(?is)class=[\"'](?:user-ico-img|passwddiv-userico-img)[\"'][^>]*style=[\"'][^\"']*background\\s*:\\s*url\\(([^)]+)\\)").replace("\\/","/").replaceAll("^[\"']|[\"']$","");
    meta.description=strip(divInner(session.page.html,"user-radio"));meta.saveUrl=cap(session.page.html,"(?is)<div[^>]+id=[\"']save[\"'][^>]*>\\s*<a[^>]+href=[\"']([^\"']+)");meta.remoteSearch=!searchEndpoint.isEmpty()&&!searchSign.isEmpty();
    if(!target.title.isEmpty())meta.title=target.title;if(!target.description.isEmpty())meta.description=target.description;session.metadata=meta;browseSessions.put(key,session);pruneBrowseSessions(now);return session;
  }

  private void pruneBrowseSessions(long now){if(browseSessions.size()<MAX_BROWSE_SESSIONS)return;String oldestKey=null;DirectLink oldest=null;for(Map.Entry<String,DirectLink> entry:browseSessions.entrySet()){DirectLink value=entry.getValue();if(now-value.createdAt>=SESSION_TTL_MS){browseSessions.remove(entry.getKey(),value);continue;}if(oldest==null||value.createdAt<oldest.createdAt){oldest=value;oldestKey=entry.getKey();}}if(browseSessions.size()>=MAX_BROWSE_SESSIONS&&oldestKey!=null)browseSessions.remove(oldestKey,oldest);}

  private static Models.Folder copyFolder(Models.Folder value){Models.Folder out=new Models.Folder();out.title=value.title;out.publisher=value.publisher;out.avatarUrl=value.avatarUrl;out.description=value.description;out.saveUrl=value.saveUrl;out.url=value.url;out.password=value.password;out.folderId=value.folderId;out.remoteSearch=value.remoteSearch;out.failedMembers=value.failedMembers;return out;}

  private static List<Models.Item> staticFolders(DirectLink session)throws Exception{
    List<Models.Item> out=new ArrayList<>();String block=elementByIdInner(session.page.html,"folder");if(block.isEmpty())return out;
    Matcher opens=parsePattern("(?is)<div[^>]*class=[\"'][^\"']*\\bmbxfolder\\b[^\"']*[\"'][^>]*>").matcher(block);
    while(opens.find()){
      String inner=balancedDivInner(block,opens.end());String href=cap(inner,"(?is)<a[^>]+href=[\"']([^\"']+)");if(href.isEmpty())continue;String filename=divInner(inner,"filename"),description=strip(divInner(filename,"filesize"));String title=cap(filename,"(?is)^\\s*([^<]+)");if(title.isEmpty()){title=strip(filename);if(!description.isEmpty())title=title.replace(description,"").trim();}title=strip(title);if(title.isEmpty())continue;
      Models.Item item=new Models.Item();item.folder=true;item.title=title;item.description=description;item.size=description;item.url=new URL(new URL(session.page.url),href).toString();item.shareUrl=item.url;item.iconUrl="https://images.bakstotre.com/assets/images/type/folder.gif";item.source=session.metadata.title;item.password=session.metadata.password;out.add(item);
    }
    return out;
  }

  private static List<Models.Item> apiFolders(DirectLink session,long deadline,int page,boolean strict,int[] rawCount,SourceProfile profile)throws Exception{
    List<Models.Item> out=new ArrayList<>();try{
      if(session.folderEndpoint.isEmpty())return out;Map<String,String> form=new LinkedHashMap<>(session.form);form.put("pg",String.valueOf(page));JSONObject data=postPage(session,session.folderEndpoint,form,deadline,page,profile,"目录子文件夹接口返回异常");int state=data.optInt("zt",-1);if(state==2)return out;JSONArray array=data.optJSONArray("text");if(array==null)return out;if(rawCount!=null)rawCount[0]=array.length();
      for(int i=0;i<array.length();i++){JSONObject value=array.optJSONObject(i);if(value==null)continue;String id=firstNonEmpty(value.optString("fol_id"),value.optString("folder_id"),value.optString("id"));String title=strip(firstNonEmpty(value.optString("name"),value.optString("folder_name"),value.optString("name_all")));if(id.isEmpty()||id.equals("null")||title.isEmpty())continue;String description=strip(firstNonEmpty(value.optString("folder_des"),value.optString("description"),value.optString("introduce"),value.optString("info"),value.optString("size")));
        Models.Item item=new Models.Item();item.folder=true;item.folderId=id;item.title=title;item.description=description;item.size=description;item.url=apiFolderUrl(value,session.target.rootUrl,id,title,description);item.shareUrl=item.url;item.iconUrl="https://images.bakstotre.com/assets/images/type/folder.gif";item.source=session.metadata.title;item.password=session.metadata.password;out.add(item);}
    }catch(Exception error){if(strict)throw error;}return out;
  }

  private static JSONObject postListing(DirectLink session,Map<String,String> form,long deadline,int page,SourceProfile profile)throws Exception{return postPage(session,session.endpoint,form,deadline,page,profile,"目录接口返回异常");}

  private static JSONObject postPage(DirectLink session,String endpoint,Map<String,String> form,long deadline,int page,SourceProfile profile,String errorMessage)throws Exception{
    if(endpoint.isEmpty())throw new IOException("未找到目录接口");String url=new URL(new URL(session.page.url),endpoint).toString(),key=pageRateKey(session);JSONObject data=null;
    BooleanSupplier asyncCancelled=ASYNC_PAGE_CANCEL.get();if(asyncCancelled!=null){checkSearchCancelled(asyncCancelled);long wait=reservePageSlot(key,profile.interval(session.ua,page));if(wait>0)throw new PageWaitException(wait);String raw=post(url,form,session.page,deadline,userAgent(session.ua));checkSearchCancelled(asyncCancelled);if(!raw.trim().startsWith("{"))throw new IOException(errorMessage);data=new JSONObject(raw);int state=data.optInt("zt",-1);if(state==1||state==2)return data;if(state==4){int delay=profile.backoff(session.ua,page);deferPageSlot(key,delay);throw new PageRateLimitedException(data.optString("info","目录请求过快，请稍后重试"));}throw new IOException(data.optString("info",data.optString("msg",errorMessage)));}
    for(int attempt=0;attempt<3;attempt++){int interval=profile.interval(session.ua,page);awaitPageSlot(key,deadline,interval,null);String raw=post(url,form,session.page,deadline,userAgent(session.ua));if(!raw.trim().startsWith("{"))throw new IOException(errorMessage);data=new JSONObject(raw);int state=data.optInt("zt",-1);if(state==1||state==2)return data;if(state==4){int backedOff=profile.backoff(session.ua,page);deferPageSlot(key,backedOff);continue;}throw new IOException(data.optString("info",data.optString("msg",errorMessage)));}
    throw new IOException(data==null?errorMessage:data.optString("info","目录请求过快，请稍后重试"));
  }

  private static String pageRateKey(DirectLink session){return originUnchecked(session.page.url)+'|'+session.fid+'|'+session.target.folderId;}
  private static void awaitPageSlot(String key,long deadline,long intervalMillis,BooleanSupplier cancelled)throws Exception{
    while(true){long wait;
      checkSearchCancelled(cancelled);
      synchronized(PAGE_RATE_LOCK){long now=System.nanoTime(),next=NEXT_PAGE_AT.getOrDefault(key,0L);if(now>=next){NEXT_PAGE_AT.put(key,now+TimeUnit.MILLISECONDS.toNanos(intervalMillis));return;}wait=next-now;}
      checkDeadline(deadline);Thread.sleep(Math.max(1L,Math.min(200L,TimeUnit.NANOSECONDS.toMillis(wait))));
    }
  }
  private static void deferPageSlot(String key,long intervalMillis){synchronized(PAGE_RATE_LOCK){long next=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(intervalMillis);if(next>NEXT_PAGE_AT.getOrDefault(key,0L))NEXT_PAGE_AT.put(key,next);}}
  private static long reservePageSlot(String key,long intervalMillis){synchronized(PAGE_RATE_LOCK){long now=System.nanoTime(),next=NEXT_PAGE_AT.getOrDefault(key,0L);if(now<next)return Math.max(1L,TimeUnit.NANOSECONDS.toMillis(next-now)+1L);NEXT_PAGE_AT.put(key,now+TimeUnit.MILLISECONDS.toNanos(intervalMillis));return 0L;}}
  static boolean isSearchCancelled(Models.Progress progress){if(Thread.currentThread().isInterrupted())return true;if(progress==null)return false;try{return progress.isCancelled();}catch(RuntimeException ignored){return true;}}
  private static boolean isSearchCancelled(BooleanSupplier cancelled){if(Thread.currentThread().isInterrupted())return true;try{return cancelled!=null&&cancelled.getAsBoolean();}catch(RuntimeException ignored){return true;}}
  private static void checkSearchCancelled(BooleanSupplier cancelled)throws SearchCancelled{if(isSearchCancelled(cancelled))throw new SearchCancelled();}
  private static void awaitSearchSlot(BooleanSupplier cancelled)throws SearchCancelled{checkSearchCancelled(cancelled);}
  private static long searchNetworkDeadline(BatchBudget budget){long next=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SOURCE_PROBE_TIMEOUT_MS),outer=budget.deadline();return outer==NO_DEADLINE?next:Math.min(outer,next);}
  private static long pageReadyAt(String key){synchronized(PAGE_RATE_LOCK){long left=NEXT_PAGE_AT.getOrDefault(key,0L)-System.nanoTime();return left<=0?System.currentTimeMillis():System.currentTimeMillis()+TimeUnit.NANOSECONDS.toMillis(left);}}

  List<Models.Item> search(String query,Models.Progress progress) throws Exception {
    return search(query,null,Collections.emptyList(),new Models.SearchOptions(),null,progress);
  }

  List<Models.Item> search(String query,Models.Source extra,List<Models.Item> extraLocal,Models.Progress progress) throws Exception {
    return search(query,extra,extraLocal,new Models.SearchOptions(),null,progress);
  }

  List<Models.Item> search(String query,Models.Source extra,List<Models.Item> extraLocal,Models.SearchOptions options,Models.Progress progress) throws Exception {
    return search(query,extra,extraLocal,options,null,progress);
  }

  private static boolean apiSearchCapable(Models.Source source){
    if(source==null||source.kind==Models.SOURCE_SINGLE)return false;
    if(source.kind!=Models.SOURCE_COMPOSITE)return source.searchable;
    for(Models.SourceMember member:source.members)if(member.kind==Models.MEMBER_REMOTE_FOLDER&&member.searchable&&!member.url.isEmpty())return true;
    return false;
  }

  List<Models.Item> search(String query,Models.Source extra,List<Models.Item> extraLocal,Models.SearchOptions options,Set<String> allowedSourceIds,Models.Progress progress) throws Exception {
    Models.SearchOptions searchOptions=(options==null?new Models.SearchOptions():options).normalized();
    LinkedHashMap<String,Models.Source> unique=searchableSources(extra,allowedSourceIds);unique.entrySet().removeIf(entry->entry.getValue().kind==Models.SOURCE_SINGLE||searchOptions.apiOnly&&!apiSearchCapable(entry.getValue()));String extraKey=sourceId(extra);List<Models.Source> src=new ArrayList<>(unique.size());Models.Source first=extraKey.isEmpty()?null:unique.get(extraKey);if(first!=null)src.add(first);for(Models.Source source:unique.values())if(source!=first&&source.password!=null&&!source.password.isEmpty())src.add(source);for(Models.Source source:unique.values())if(source!=first&&(source.password==null||source.password.isEmpty()))src.add(source);final List<Models.Item> local=extraLocal==null?Collections.emptyList():extraLocal;
    List<Models.Item> out=Collections.synchronizedList(new ArrayList<>());if(src.isEmpty())return out;
    List<SourceSearchState> states=new ArrayList<>(src.size());Set<String> remoteSeen=new HashSet<>();
    for(Models.Source source:src){
      List<Models.Item> cached=new ArrayList<>();if(sourceId(source).equals(extraKey))cached.addAll(local);
      if(source.kind==Models.SOURCE_COMPOSITE){
        String group=sourceId(source);Set<String> scope=compositeScope(source);
        if(!searchOptions.apiOnly){
          states.add(new SourceSearchState(source,query,cached,group));
          for(Models.SourceMember member:source.members)if(inCompositeScope(member,scope)&&(member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY)&&!member.url.isEmpty()&&compositeMemberMatches(member,query))states.add(new SourceSearchState(source,member,query));
        }
        for(Models.SourceMember member:source.members)if(inCompositeScope(member,scope)&&member.kind==Models.MEMBER_REMOTE_FOLDER&&(!searchOptions.apiOnly||member.searchable)&&!member.url.isEmpty()&&remoteSeen.add(member.url+'\n'+member.password)){Models.Source nested=remoteSource(member);states.add(new SourceSearchState(nested,query,Collections.emptyList(),group,source.title));}
      }else states.add(new SourceSearchState(source,query,cached));
    }
    try{return runSearchCoordinator(states,out,ConcurrentHashMap.newKeySet(),searchOptions,progress);}finally{searchPool.purge();}
  }

  /** Cached-only items used by the one-character home search path. */
  List<Models.Item> localSourceItems(Set<String> allowedSourceIds)throws IOException{List<Models.Item> out=new ArrayList<>();for(Models.Source source:sources())if(source.kind==Models.SOURCE_COMPOSITE&&(allowedSourceIds==null||allowedSourceIds.contains(sourceId(source))))for(Models.SourceMember member:source.members)if(member.kind==Models.MEMBER_FILE||member.kind==Models.MEMBER_DIRECTORY){Models.SourceMember cached=effectiveCompositeMember(member);if(!cached.title.isEmpty()){Models.Item item=itemFromMember(cached,source.title);item.sourceId=sourceId(source);out.add(item);}}return out;}

  /**
   * Immediate, network-free source-name matches.  Source roots retain their
   * identity so the UI can open the exact official/user entry, while members
   * keep their real file/directory kind (a single-file source is never painted
   * as a folder).  URL identity also makes these seeds de-duplicate naturally
   * against later API and paginated results.
   */
  private void invalidateSourceNameIndex(){sourceNameIndexRevision++;sourceNameIndex=null;}

  private List<SourceNameEntry> sourceNameIndex()throws IOException{
    List<SourceNameEntry> cached=sourceNameIndex;if(cached!=null)return cached;
    while(true){int revision=sourceNameIndexRevision;List<SourceNameEntry> built=new ArrayList<>();for(Models.Source source:sources()){String id=sourceId(source);if(!source.title.isEmpty())built.add(new SourceNameEntry(source,null,id,foldSearchQuery(source.title)));if(source.kind==Models.SOURCE_COMPOSITE)for(Models.SourceMember stored:source.members){if(stored.kind==Models.MEMBER_FOLDER)continue;Models.SourceMember member=effectiveCompositeMember(stored);if(!member.url.isEmpty()&&!member.title.isEmpty())built.add(new SourceNameEntry(source,member,id,foldSearchQuery(member.title)));}}built=Collections.unmodifiableList(built);synchronized(sourceNameIndexLock){cached=sourceNameIndex;if(cached!=null)return cached;if(revision==sourceNameIndexRevision){sourceNameIndex=built;return built;}}}
  }

  List<Models.Item> sourceNameItems(String query,Set<String> allowedSourceIds)throws IOException{
    String needle=foldSearchQuery(query==null?"":query.trim());LinkedHashMap<String,Models.Item> matched=new LinkedHashMap<>();if(needle.isEmpty())return new ArrayList<>();
    for(SourceNameEntry entry:sourceNameIndex()){
      if(allowedSourceIds!=null&&!allowedSourceIds.contains(entry.id)||!entry.folded.contains(needle))continue;Models.Source source=entry.source;Models.Item item;
      if(entry.member==null){if(source.kind==Models.SOURCE_SINGLE&&!source.members.isEmpty())item=itemFromMember(effectiveCompositeMember(source.members.get(0)),source.title);else{item=new Models.Item();item.title=source.title;item.url=source.url.isEmpty()?"source-entry:"+entry.id:source.url;item.shareUrl=source.url;item.password=source.password;item.iconUrl=source.avatarUrl;item.description=source.description;item.source=source.title;item.folder=source.kind!=Models.SOURCE_SINGLE;}item.sourceEntry=true;item.sourceId=entry.id;}
      else{Models.SourceMember member=effectiveCompositeMember(entry.member);item=itemFromMember(member,source.title);item.sourceId=entry.id;if(member.kind==Models.MEMBER_REMOTE_FOLDER)item.folder=true;else if(member.kind==Models.MEMBER_DIRECTORY||member.kind==Models.MEMBER_FILE)item.folder=false;}
      matched.putIfAbsent(item.url,item);
    }
    return new ArrayList<>(matched.values());
  }

  Models.Source sourceById(String id)throws IOException{if(id==null||id.isEmpty())return null;for(Models.Source source:sources())if(sourceId(source).equals(id))return copySource(source);return null;}

  private static Models.Source remoteSource(Models.SourceMember member){Models.Source out=new Models.Source();out.id=out.url=member.url;out.title=member.title;out.password=member.password;out.publisher=member.title;out.avatarUrl=member.iconUrl;out.description=member.description;out.error=member.error;out.searchable=member.searchable;return out;}

  private static Set<String> compositeScope(Models.Source source){if(source==null||source.nodeId.isEmpty())return null;Set<String> scope=new HashSet<>();scope.add(source.nodeId);boolean changed;do{changed=false;for(Models.SourceMember member:source.members)if(member.kind==Models.MEMBER_FOLDER&&scope.contains(member.parentId)&&scope.add(member.id))changed=true;}while(changed);return scope;}
  private static boolean inCompositeScope(Models.SourceMember member,Set<String> scope){return scope==null||scope.contains(member.parentId);}
  private static boolean compositeMemberMatches(Models.SourceMember member,String query){String title=effectiveCompositeMember(member).title,needle=foldSearchQuery(query==null?"":query.trim());return !title.isEmpty()&&(needle.isEmpty()||title.toLowerCase(Locale.ROOT).contains(needle));}

  private List<Models.Item> runSearchCoordinator(List<SourceSearchState> states,List<Models.Item> out,Set<String> seen,Models.SearchOptions options,Models.Progress progress)throws InterruptedException{return new SearchCoordinator(states,out,seen,options,progress).run();}

  static List<Models.Item> addFresh(List<Models.Item> items,Set<String> seen,List<Models.Item> out,java.util.concurrent.atomic.AtomicInteger found){List<Models.Item> fresh=new ArrayList<>();for(Models.Item x:items)if(seen.add(x.url)){out.add(x);fresh.add(x);found.incrementAndGet();}return fresh;}

  Models.SourceSearch searchSource(Models.Source source,String query,List<Models.Item> localItems)throws Exception{
    return searchSource(source,query,localItems,new BatchBudget(0),null,null,new Models.SearchOptions().normalized(),null);
  }

  Models.SourceSearch searchSource(Models.Source source,String query,List<Models.Item> localItems,BatchBudget budget,ItemBatch localStream,SearchPageBatch pageStream,Models.SearchOptions options,BooleanSupplier cancelled)throws Exception{
    SourceSearchState state=new SourceSearchState(source,query,localItems==null?Collections.emptyList():localItems);Exception directoryError=null;try{searchSourceApi(state,budget,localStream,pageStream,cancelled);}catch(SearchCancelled error){throw error;}catch(Exception ignored){}try{searchSourceDirectory(state,budget,localStream,pageStream,options,cancelled);}catch(SearchCancelled error){throw error;}catch(Exception error){directoryError=error;}Models.SourceSearch result=new Models.SourceSearch();result.remoteAvailable=state.remoteAvailable;result.remoteUsed=state.remoteUsed;synchronized(state.merged){result.items.addAll(state.merged.values());}if(directoryError!=null)throw directoryError;return result;
  }

  private void searchSourceApi(SourceSearchState state,BatchBudget budget,ItemBatch localStream,SearchPageBatch pageStream,BooleanSupplier cancelled)throws Exception{
    emitSourceLocal(state,localStream,cancelled);awaitSearchSlot(cancelled);Exception remoteError=null;String password=state.source.password==null?"":state.source.password;SourceProfile profile=sourceProfile(state.source.url);byte firstUa=profile.searchUa==UA_UNKNOWN?UA_ANDROID:profile.searchUa;boolean validResponse=false,emitted=false;
    for(int attempt=0;attempt<2;attempt++){byte ua=attempt==0?firstUa:otherUa(firstUa);if(!capabilityAllowed(profile.searchSeen,profile.searchAvailable,ua))continue;try{SearchApiPage api=searchApiUa(state.source,state.needle,budget,cancelled,ua,attempt>0);checkSearchCancelled(cancelled);byte bit=uaBit(ua);if(api.available)synchronized(profile){profile.searchSeen|=bit;profile.searchAvailable|=bit;}state.remoteAvailable|=api.available;state.remoteUsed|=api.used;if(!api.available)continue;if(api.state!=1&&api.state!=2)throw new IOException("源内搜索失败");validResponse=true;if(api.state==1){List<Models.Item> batch=pageStream==null?null:new ArrayList<>();int sourceFound;synchronized(state.merged){for(int i=0;i<api.count;i++){JSONObject value=api.items.optJSONObject(i);if(value==null)continue;Models.Item item=item(value,origin(api.page.url),state.displaySource,password);if(!item.url.isEmpty()&&state.merged.putIfAbsent(item.url,item)==null&&batch!=null)batch.add(item);}sourceFound=state.merged.size();}if(api.count>0){profile.searchUa=ua;if(pageStream!=null){pageStream.accept(batch,1,api.count,sourceFound);checkSearchCancelled(cancelled);}emitted=true;remoteError=null;break;}}if(api.state==2||api.count==0)continue;}catch(SearchCancelled cancelledError){throw cancelledError;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SearchCancelled();}catch(SocketTimeoutException timeout){remoteError=timeout;}catch(Exception error){checkSearchCancelled(cancelled);remoteError=error;}}
    if(!emitted&&pageStream!=null){int sourceFound;synchronized(state.merged){sourceFound=state.merged.size();}pageStream.accept(Collections.emptyList(),1,0,sourceFound);checkSearchCancelled(cancelled);}if(validResponse)remoteError=null;if(remoteError!=null)throw remoteError;
  }

  private void searchSourceDirectory(SourceSearchState state,BatchBudget budget,ItemBatch localStream,SearchPageBatch pageStream,Models.SearchOptions options,BooleanSupplier cancelled)throws Exception{emitSourceLocal(state,localStream,cancelled);searchDirectoryTree(state.source,state.foldedNeedle,state.merged,pageStream,options,budget,cancelled);}
  private static void emitSourceLocal(SourceSearchState state,ItemBatch stream,BooleanSupplier cancelled)throws SearchCancelled{if(state.localEmitted)return;state.localEmitted=true;List<Models.Item> batch=stream==null?null:new ArrayList<>();synchronized(state.merged){for(Models.Item item:state.local){item.source=state.displaySource;if(matches(item,state.foldedNeedle)&&state.merged.putIfAbsent(item.url,item)==null&&batch!=null)batch.add(item);}}if(batch!=null&&!batch.isEmpty()){checkSearchCancelled(cancelled);stream.accept(batch);checkSearchCancelled(cancelled);}}

  /**
   * Searches one directory or a complete folder tree. Every directory owns its
   * page limit, while malformed/inaccessible child directories are skipped so
   * one bad branch cannot hold the source worker open.
   */
  private void searchDirectoryTree(Models.Source source,String foldedNeedle,LinkedHashMap<String,Models.Item> merged,SearchPageBatch pageStream,Models.SearchOptions options,BatchBudget budget,BooleanSupplier cancelled)throws Exception{
    ArrayDeque<Models.Item> pending=new ArrayDeque<>();Set<String> directories=new HashSet<>();Exception failure=null;Models.Item root=new Models.Item();root.folder=true;root.title=source.title;root.url=source.url;root.shareUrl=source.url;root.password=source.password==null?"":source.password;root.source=source.title;enqueueFolder(pending,directories,root,null);int pageLimit=options.maxPages>0?options.maxPages:1000;
    while(!pending.isEmpty()){
      checkSearchCancelled(cancelled);checkSearchBudget(budget);Models.Item folder=pending.removeFirst();int firstApiFolderCount=0;boolean replayed=false;Set<String> pageFingerprints=new HashSet<>();
      for(int page=1;page<=pageLimit;page++){
        checkSearchCancelled(cancelled);awaitSearchSlot(cancelled);Models.Folder listing;
        try{listing=browseSearchPage(folder,page,pageFingerprints,budget,cancelled);}catch(SearchCancelled error){throw error;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SearchCancelled();}catch(Exception error){checkSearchCancelled(cancelled);Exception pageError=error;if(!replayed&&recoverableBrowseError(error))try{prepareSearchReplay(folder,page,budget,cancelled);replayed=true;pageFingerprints.clear();firstApiFolderCount=0;page=0;continue;}catch(SearchCancelled cancelledError){throw cancelledError;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SearchCancelled();}catch(Exception recoveryError){pageError=recoveryError;}if(failure==null)failure=pageError;break;}
        if(page==1)firstApiFolderCount=listing.apiFolderCount;List<Models.Item> batch=pageStream==null?null:new ArrayList<>();
        for(Models.Item item:listing.items){inherit(item,folder);if(item.folder&&options.recursiveFolders)enqueueFolder(pending,directories,item,folder);if(matches(item,foldedNeedle)&&merged.putIfAbsent(item.url,item)==null&&batch!=null)batch.add(item);}
        if(pageStream!=null){pageStream.accept(batch,page,listing.items.size(),merged.size());checkSearchCancelled(cancelled);}if(!listing.hasMore)break;
      }
      if(!options.recursiveFolders||firstApiFolderCount<PAGE_SIZE||pageLimit<2)continue;
      try{
        awaitSearchSlot(cancelled);DirectLink session=browseSession(folder.url,folder.password,searchNetworkDeadline(budget),false);SourceProfile profile=sourceProfile(folder.url);
        for(int page=2;page<=pageLimit;page++){
          checkSearchCancelled(cancelled);checkSearchBudget(budget);int[] rawCount={0};List<Models.Item> children=apiFolders(session,searchNetworkDeadline(budget),page,true,rawCount,profile),batch=pageStream==null?null:new ArrayList<>();
          for(Models.Item item:children){inherit(item,folder);enqueueFolder(pending,directories,item,folder);if(matches(item,foldedNeedle)&&merged.putIfAbsent(item.url,item)==null&&batch!=null)batch.add(item);}
          if(pageStream!=null){pageStream.accept(batch,page,rawCount[0],merged.size());checkSearchCancelled(cancelled);}if(rawCount[0]<PAGE_SIZE)break;
        }
      }catch(SearchCancelled error){throw error;}catch(Exception error){checkSearchCancelled(cancelled);if(Thread.currentThread().isInterrupted())throw new SearchCancelled();if(failure==null)failure=error;}
      if(!options.recursiveFolders)break;
    }
    if(failure!=null)throw failure;
  }

  private Models.Folder browseSearchPage(Models.Item folder,int page,Set<String> fingerprints,BatchBudget budget,BooleanSupplier cancelled)throws Exception{
    checkSearchCancelled(cancelled);checkSearchBudget(budget);Models.Folder listing=browsePage(folder.url,folder.password,true,page,searchNetworkDeadline(budget),true);String fingerprint=pageFingerprint(listing.items);if(page>1&&!fingerprints.add(fingerprint))throw new IOException("目录分页重复");if(page==1)fingerprints.add(fingerprint);return listing;
  }

  private Models.Folder browsePreparedSearchPage(SourceSearchState state,BooleanSupplier cancelled)throws Exception{
    checkSearchCancelled(cancelled);DirectLink session=state.directorySession;Models.Folder out=copyFolder(session.metadata);out.page=state.page;out.url=state.folder.url;out.password=state.folder.password;out.folderId=session.target.folderId;out.apiFolderCount=Math.max(0,state.firstApiFolderCount);LinkedHashMap<String,Models.Item> items=new LinkedHashMap<>();
    if(state.page==1&&session.target.folderId.isEmpty())for(Models.Item folder:staticFolders(session))items.put(folder.url,folder);Map<String,String> form=new LinkedHashMap<>(session.form);form.put("pg",String.valueOf(state.page));JSONObject data=postListing(session,form,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SOURCE_PROBE_TIMEOUT_MS),state.page,sourceProfile(state.folder.url));JSONArray array=data.optJSONArray("text");int valid=0,code=data.optInt("zt",-1);if(code==1&&(array==null||state.page>1&&array.length()==0))throw new IOException("目录分页返回空内容");if(code==1){session.authorized=true;if(array!=null)for(int i=0;i<array.length();i++){JSONObject value=array.optJSONObject(i);if(value==null)continue;Models.Item item=item(value,origin(session.page.url),out.title,state.folder.password);if(item.url.isEmpty()||item.url.endsWith("/-1"))continue;valid++;items.put(item.url,item);}}
    out.items.addAll(items.values());out.hasMore=code==1&&array!=null&&array.length()>=PAGE_SIZE&&valid>0;out.nextPageReadyAt=out.hasMore?pageReadyAt(pageRateKey(session)):0L;if(!out.items.isEmpty())writeAtomic(folderCache(state.folder.url,state.folder.password,state.page),toJson(out).toString());if(!out.items.isEmpty()){String fingerprint=pageFingerprint(out.items);if(state.page>1&&!state.pageFingerprints.add(fingerprint))throw new IOException("目录分页重复");if(state.page==1)state.pageFingerprints.add(fingerprint);}return out;
  }

  private void prepareSearchReplay(Models.Item folder,int failedPage,BatchBudget budget,BooleanSupplier cancelled)throws Exception{invalidateBrowseSessions(folder.url,folder.password);SourceProfile profile=sourceProfile(folder.url);byte ua=profile.directoryUa==UA_UNKNOWN?UA_ANDROID:profile.directoryUa;awaitSearchDelay(profile.interval(ua,failedPage),budget,cancelled);}
  private void invalidateBrowseSessions(String url,String password){DirectLink target=parseFolderTarget(url);String pwd=password==null?"":password;for(byte ua=UA_ANDROID;ua<=UA_DESKTOP;ua++)browseSessions.remove(target.rootUrl+'\n'+pwd+'\n'+target.folderId+'\n'+ua);}
  private static boolean recoverableBrowseError(Exception error){return !(error instanceof ShareCancelledException)&&(error instanceof IOException||error instanceof JSONException);}
  private static String pageFingerprint(List<Models.Item> items){if(items.isEmpty())return"0";return items.size()+"\n"+items.get(0).url+"\n"+items.get(items.size()-1).url;}
  private static void awaitSearchDelay(long millis,BatchBudget budget,BooleanSupplier cancelled)throws Exception{long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(true){checkSearchCancelled(cancelled);checkSearchBudget(budget);long left=until-System.nanoTime();if(left<=0)return;Thread.sleep(Math.max(1L,Math.min(200L,TimeUnit.NANOSECONDS.toMillis(left))));}}

  private static void checkSearchBudget(BatchBudget budget)throws SocketTimeoutException{long deadline=budget.deadline();if(deadline!=NO_DEADLINE&&System.nanoTime()>=deadline)throw new SocketTimeoutException("搜索批次已到等待上限");}

  private SearchApiPage searchApiUa(Models.Source source,String needle,BatchBudget budget,BooleanSupplier cancelled,byte ua,boolean force)throws Exception{
    awaitSearchSlot(cancelled);String password=source.password==null?"":source.password;DirectLink session=browseSession(source.url,password,searchNetworkDeadline(budget),force,ua),page=session.page;checkSearchCancelled(cancelled);String endpoint=searchEndpoint(page),sign=searchSign(page.html);SearchApiPage out=new SearchApiPage();out.page=page;out.available=!endpoint.isEmpty()&&!sign.isEmpty();if(!out.available)return out;Map<String,String> form=new LinkedHashMap<>();form.put("wd",needle);form.put("sign",sign);if(!password.isEmpty())form.put("pwd",password);String api=new URL(new URL(page.url),endpoint).toString(),rateKey="search|"+origin(page.url)+'|'+cap(endpoint,"[?&]file=([^&#]+)");JSONObject data=postSearchPage(api,form,page,rateKey,searchNetworkDeadline(budget),cancelled,sourceProfile(source.url),ua);out.used=true;out.state=data.optInt("zt",-1);if(out.state==1){out.items=data.optJSONArray("item");if(out.items==null)out.items=data.optJSONArray("text");out.count=out.items==null?0:out.items.length();out.total=data.optInt("total",-1);}return out;
  }

  private static JSONObject postSearchPage(String url,Map<String,String> form,DirectLink page,String rateKey,long deadline,BooleanSupplier cancelled,SourceProfile profile,byte ua)throws Exception{
    JSONObject data=null;for(int attempt=0;attempt<3;attempt++){checkSearchCancelled(cancelled);int interval=profile.interval(ua,1);awaitPageSlot(rateKey,deadline,interval,cancelled);checkSearchCancelled(cancelled);data=new JSONObject(post(url,form,page,deadline,userAgent(ua)));checkSearchCancelled(cancelled);if(data.optInt("zt",-1)!=4)return data;int backedOff=profile.backoff(ua,1);deferPageSlot(rateKey,backedOff);checkDeadline(deadline);}return data;
  }

  private static Models.Item item(JSONObject o,String base,String source,String password)throws Exception{
    Models.Item x=new Models.Item();x.title=strip(firstNonEmpty(o.optString("name_all"),o.optString("name")));String id=firstNonEmpty(o.optString("id"),o.optString("url"));if(!id.isEmpty())x.url=new URL(new URL(base),id.startsWith("http")?id:"/"+id).toString();x.shareUrl=x.url;x.size=o.optString("size");x.description=strip(firstNonEmpty(o.optString("description"),o.optString("introduce"),o.optString("info")));x.source=source;x.password=password==null?"":password;x.folder=o.optBoolean("folder")||o.optInt("folder")==1||o.optString("type").equalsIgnoreCase("folder");
    String type=o.optString("icon").trim(),custom=o.optString("ico").trim();boolean hasCustom=o.optInt("p_ico")==1||"1".equals(o.optString("p_ico"));
    if(hasCustom&&!custom.isEmpty())x.iconUrl=custom.startsWith("http")?custom:"https://image.dmpdmp.com/image/ico/"+custom+"?x-oss-process=image/auto-orient,1/resize,m_fill,w_100,h_100/format,png";
    else if(!type.isEmpty())x.iconUrl=type.startsWith("http")?type:"https://images.bakstotre.com/assets/images/type/"+type+".gif";
    x.time=firstNonEmpty(o.optString("time"),o.optString("time_all"),o.optString("date"),dateFromIcon(custom));return x;
  }
  private static boolean matches(Models.Item item,String foldedQuery){return foldedQuery==null||foldedQuery.isEmpty()||item.title.toLowerCase(Locale.ROOT).contains(foldedQuery);}
  private static String dateFromIcon(String icon){Matcher m=ICON_DATE.matcher(icon);return m.find()?m.group(1)+"-"+m.group(2)+"-"+m.group(3):"";}
  private static String firstNonEmpty(String... values){for(String value:values)if(value!=null&&!value.trim().isEmpty())return value.trim();return"";}
  private static byte pageTemplate(String html){String value=html==null?"":html.toLowerCase(Locale.ROOT);boolean minimal=value.contains("/assets/share/folder2.css")&&(value.contains("/assets/share/folder3.css")||value.contains("/assets/share/folder4.css"))&&value.contains("user-top")&&value.contains("fileview");if(minimal)return TEMPLATE_MINIMAL;boolean hasInfo=value.contains("id=\"info\"")||value.contains("id='info'");boolean classic=value.contains("/assets/img/t0.css")&&(value.contains("/assets/share/folder6.css")||value.contains("/assets/share/folder8.css")||value.contains("/assets/share/folder9.css"))&&hasInfo;return classic?TEMPLATE_CLASSIC:TEMPLATE_UNKNOWN;}
  private static String searchEndpoint(DirectLink page){return sameOriginEndpoint(page,cap(page.html,"url\\s*:\\s*['\"]([^'\"]*/search/s\\.php\\?file=\\d+[^'\"]*)['\"]"),"/search/s.php");}
  private static String searchSign(String html){return cap(html,"['\"]sign['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");}
  private static boolean hasSearchCapability(DirectLink page){return !searchEndpoint(page).isEmpty()&&!searchSign(page.html).isEmpty();}
  private static String sameOriginEndpoint(DirectLink page,String raw,String expectedPath){if(raw.isEmpty())return"";try{URL base=new URL(page.url),endpoint=new URL(base,raw);if(!base.getProtocol().equalsIgnoreCase(endpoint.getProtocol())||!base.getHost().equalsIgnoreCase(endpoint.getHost())||effectivePort(base)!=effectivePort(endpoint)||!endpoint.getPath().endsWith(expectedPath))return"";return endpoint.toString();}catch(Exception ignored){return"";}}
  private static int effectivePort(URL url){return url.getPort()>=0?url.getPort():url.getDefaultPort();}
  private static void requireLanzouPage(String value)throws IOException{try{URL url=new URL(value);if(!url.getProtocol().equalsIgnoreCase("https")||!LANZOU_HOST.matcher(url.getHost()).matches())throw new IOException("蓝奏目录跳转到了不受信任的地址");}catch(IOException error){throw error;}catch(Exception ignored){throw new IOException("蓝奏目录返回地址无效");}}
  private static void requireActiveShare(DirectLink page)throws ShareCancelledException{String html=page==null||page.html==null?"":page.html;if(html.contains("filemoreajax.php")||pageTemplate(html)!=TEMPLATE_UNKNOWN)return;String text=strip(html).replaceAll("\\s+","").replace("，","").replace(",","");if(text.contains("分享已取消")||text.contains("文件取消分享")||text.contains("文件已取消分享")||text.contains("已被取消分享")||text.contains("文件不存在或已删除"))throw new ShareCancelledException();}
  private static DirectLink getGuarded(String url,long deadline)throws Exception{return getGuarded(url,deadline,ANDROID_UA);}
  private static DirectLink getGuarded(String url,long deadline,String userAgent)throws Exception{return getGuarded(url,deadline,userAgent,"");}
  private static DirectLink getGuarded(String url,long deadline,String userAgent,String initialCookie)throws Exception{DirectLink p=getPage(url,initialCookie,deadline,userAgent);String value=acwCookie(p.html);if(!value.isEmpty())p=getPage(url,mergeCookie(p.cookie,"acw_sc__v2",value),deadline,userAgent);return p;}
  private static DirectLink getPage(String url,String cookie,long deadline,String userAgent)throws Exception{checkDeadline(deadline);HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();trackConnection(c,true);try{applyTimeouts(c,deadline);c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Accept-Encoding","gzip");if(cookie!=null&&!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);c.getResponseCode();DirectLink page=new DirectLink();page.url=c.getURL().toString();page.cookie=captureCookies(cookie,c);page.html=body(c);return page;}finally{c.disconnect();trackConnection(c,false);}}
  private static String post(String url,Map<String,String> data,DirectLink page,long deadline)throws Exception{return post(url,data,page,deadline,ANDROID_UA);}
  private static String post(String url,Map<String,String> data,DirectLink page,long deadline,String userAgent)throws Exception{synchronized(page){checkDeadline(deadline);HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();trackConnection(c,true);try{applyTimeouts(c,deadline);c.setDoOutput(true);c.setRequestMethod("POST");c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Referer",page.url);c.setRequestProperty("Origin",origin(page.url));if(!page.cookie.isEmpty())c.setRequestProperty("Cookie",page.cookie);c.setRequestProperty("X-Requested-With","XMLHttpRequest");c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");byte[] b=form(data).getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream o=c.getOutputStream()){o.write(b);}c.getResponseCode();page.cookie=captureCookies(page.cookie,c);return body(c);}finally{c.disconnect();trackConnection(c,false);}}}
  private static void trackConnection(HttpURLConnection connection,boolean open){ConnectionTracker tracker=SEARCH_CONNECTIONS.get();if(tracker!=null){if(open)tracker.opened(connection);else tracker.closed(connection);}}
  private static String captureCookies(String existing,HttpURLConnection connection){LinkedHashMap<String,String> values=cookieValues(existing);mergeSetCookies(values,connection.getHeaderFields());return cookieHeader(values);}
  private static String mergeCookie(String existing,String name,String value){LinkedHashMap<String,String> values=cookieValues(existing);values.put(name,value);return cookieHeader(values);}
  private static LinkedHashMap<String,String> cookieValues(String header){LinkedHashMap<String,String> values=new LinkedHashMap<>();if(header!=null)for(String part:header.split(";")){int split=part.indexOf('=');if(split>0)values.put(part.substring(0,split).trim(),part.substring(split+1).trim());}return values;}
  static void mergeSetCookies(Map<String,String> values,Map<String,List<String>> headers){for(Map.Entry<String,List<String>> header:headers.entrySet())if(header.getKey()!=null&&header.getKey().equalsIgnoreCase("Set-Cookie"))for(String raw:header.getValue()){String first=raw.split(";",2)[0];int split=first.indexOf('=');if(split>0)values.put(first.substring(0,split).trim(),first.substring(split+1).trim());}}
  static String cookieHeader(Map<String,String> values){StringBuilder out=new StringBuilder();for(Map.Entry<String,String> value:values.entrySet()){if(out.length()>0)out.append("; ");out.append(value.getKey()).append('=').append(value.getValue());}return out.toString();}
  static String acwCookie(String html){String arg=cap(html,"arg1\\s*=\\s*['\"]([A-Fa-f0-9]{40})['\"]");if(arg.isEmpty())return"";String digits="0123456789abcdef";StringBuilder value=new StringBuilder(40);for(int i=0;i<40;i+=2){int mixed=((Character.digit(arg.charAt(POS[i]-1),16)<<4)|Character.digit(arg.charAt(POS[i+1]-1),16))^((Character.digit(MASK.charAt(i),16)<<4)|Character.digit(MASK.charAt(i+1),16));value.append(digits.charAt(mixed>>>4)).append(digits.charAt(mixed&15));}return value.toString();}
  private static void applyTimeouts(HttpURLConnection c,long deadline)throws SocketTimeoutException{if(deadline==NO_DEADLINE){c.setConnectTimeout(12000);c.setReadTimeout(25000);return;}int remaining=remainingMillis(deadline);int connect=Math.max(1,Math.min(4000,remaining/3));c.setConnectTimeout(connect);c.setReadTimeout(Math.max(1,remaining-connect));}
  private static int remainingMillis(long deadline)throws SocketTimeoutException{long nanos=deadline-System.nanoTime();if(nanos<=0)throw new SocketTimeoutException("蓝奏源搜索超时");return(int)Math.min(Integer.MAX_VALUE,Math.max(1L,TimeUnit.NANOSECONDS.toMillis(nanos)));}
  private static void checkDeadline(long deadline)throws SocketTimeoutException{if(deadline!=NO_DEADLINE)remainingMillis(deadline);}
  static String body(HttpURLConnection c)throws Exception{InputStream raw=c.getResponseCode()<400?c.getInputStream():c.getErrorStream();if(raw==null)return"";try(InputStream in="gzip".equalsIgnoreCase(c.getContentEncoding())?new GZIPInputStream(raw):raw){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))>0;)o.write(b,0,n);return o.toString("UTF-8");}}
  static String form(Map<String,String> m)throws Exception{StringBuilder b=new StringBuilder();for(Map.Entry<String,String>e:m.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}return b.toString();}
  private static Map<String,String> formValues(String h){Map<String,String> out=new HashMap<>(),vars=new HashMap<>();Matcher vm=parsePattern("(?:var\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*['\"]([^'\"]*)['\"]").matcher(h);while(vm.find())vars.put(vm.group(1),vm.group(2));Matcher fields=parsePattern("['\"](lx|uid|puid|rep|t|k|up|webfoldersign|ls|vip)['\"]\\s*:\\s*(?:['\"]([^'\"]*)['\"]|([A-Za-z_][A-Za-z0-9_]*))").matcher(h);while(fields.find())out.putIfAbsent(fields.group(1),fields.group(2)!=null?fields.group(2):vars.getOrDefault(fields.group(3),""));return out;}
  private static DirectLink parseFolderTarget(String value){DirectLink out=new DirectLink();out.rootUrl=out.folderId=out.title=out.description="";String url=value==null?"":value.trim();int marker=url.indexOf(FOLDER_MARKER);if(marker<0){out.rootUrl=url;return out;}out.rootUrl=url.substring(0,marker);String fragment=url.substring(marker+FOLDER_MARKER.length());String[] parts=fragment.split("&");if(parts.length>0)out.folderId=decode(parts[0]);for(int i=1;i<parts.length;i++){int split=parts[i].indexOf('=');if(split<1)continue;String key=parts[i].substring(0,split),decoded=decode(parts[i].substring(split+1));if(key.equals("title"))out.title=decoded;else if(key.equals("description"))out.description=decoded;}return out;}
  private static String virtualFolderUrl(String root,String id,String title,String description)throws Exception{return root.replaceAll("#.*$","")+FOLDER_MARKER+URLEncoder.encode(id,"UTF-8")+"&title="+URLEncoder.encode(title,"UTF-8")+"&description="+URLEncoder.encode(description,"UTF-8");}
  /** Preserve an API-provided public child share URL; otherwise persist the exact re-enterable folder identity. */
  private static String apiFolderUrl(JSONObject value,String root,String id,String title,String description)throws Exception{for(String key:new String[]{"share_url","folder_url","url","href"}){String raw=value.optString(key).trim();if(raw.isEmpty())continue;try{String candidate=new URL(new URL(root),raw).toString();URL parsed=new URL(candidate);if(parsed.getProtocol().equalsIgnoreCase("https")&&LANZOU_HOST.matcher(parsed.getHost()).matches()&&!parsed.getPath().isEmpty()&&!parsed.getPath().equals("/"))return candidate.replaceAll("#.*$","");}catch(Exception ignored){}}return virtualFolderUrl(root,id,title,description);}
  private static String redactedFolderIdentity(String value){if(value==null)return"";int marker=value.indexOf(FOLDER_MARKER);if(marker<0)return value;int end=value.indexOf('&',marker+FOLDER_MARKER.length());return end<0?value:value.substring(0,end);}
  private static String decode(String value){try{return URLDecoder.decode(value,"UTF-8");}catch(Exception ignored){return value;}}
  private static String originUnchecked(String url){try{return origin(url);}catch(Exception ignored){return url;}}
  private static Pattern parsePattern(String expression){Pattern cached=PARSE_PATTERNS.get(expression);if(cached!=null)return cached;Pattern compiled=Pattern.compile(expression),existing=PARSE_PATTERNS.putIfAbsent(expression,compiled);return existing==null?compiled:existing;}
  private static String cap(String s,String p){Matcher m=parsePattern(p).matcher(s);return m.find()?unescape(m.group(1).trim()):"";}
  private static String divInner(String s,String cls){Matcher open=parsePattern("(?is)<div[^>]*class=[\"'][^\"']*\\b"+Pattern.quote(cls)+"\\b[^\"']*[\"'][^>]*>").matcher(s);return open.find()?balancedDivInner(s,open.end()):"";}
  private static String elementByIdInner(String s,String id){Matcher open=parsePattern("(?is)<div[^>]*\\bid=[\"']"+Pattern.quote(id)+"[\"'][^>]*>").matcher(s);return open.find()?balancedDivInner(s,open.end()):"";}
  private static String balancedDivInner(String s,int contentStart){Matcher tag=parsePattern("(?is)</?div\\b[^>]*>").matcher(s);tag.region(Math.max(0,contentStart),s.length());int depth=1;while(tag.find()){if(tag.group().trim().startsWith("</"))depth--;else depth++;if(depth==0)return s.substring(contentStart,tag.start());}return"";}
  private static String strip(String s){return unescape(s.replaceAll("(?is)<br\\s*/?>","\n").replaceAll("(?is)<[^>]+>","").trim());}
  private static String unescape(String s){return s.replace("&amp;","&").replace("&#39;","'").replace("&quot;","\"").replace("&lt;","<").replace("&gt;",">");}
  static String origin(String u)throws Exception{URL x=new URL(u);return x.getProtocol()+"://"+x.getHost();}
  private static String read(File f)throws Exception{try(FileInputStream in=new FileInputStream(f)){byte[]b=new byte[(int)f.length()];int n=in.read(b);return new String(b,0,n,StandardCharsets.UTF_8);}}
  private static void writeAtomic(File f,String s)throws Exception{File t=new File(f.getPath()+".tmp");try(FileOutputStream o=new FileOutputStream(t)){o.write(s.getBytes(StandardCharsets.UTF_8));o.getFD().sync();}if(!t.renameTo(f)){f.delete();if(!t.renameTo(f))throw new IOException("缓存替换失败");}}
  private static JSONObject toJson(Models.Folder f)throws Exception{JSONObject o=new JSONObject();o.put("title",f.title).put("publisher",f.publisher).put("avatar",f.avatarUrl).put("description",f.description).put("save",f.saveUrl).put("url",f.url).put("password",f.password).put("folderId",f.folderId).put("page",f.page).put("hasMore",f.hasMore).put("remoteSearch",f.remoteSearch).put("failedMembers",f.failedMembers).put("nextPageReadyAt",f.nextPageReadyAt);JSONArray a=new JSONArray();for(Models.Item x:f.items)a.put(new JSONObject().put("title",x.title).put("url",x.url).put("shareUrl",x.shareUrl).put("size",x.size).put("time",x.time).put("icon",x.iconUrl).put("source",x.source).put("password",x.password).put("description",x.description).put("folderId",x.folderId).put("error",x.error).put("folder",x.folder));o.put("items",a);return o;}
  private static Models.Folder fromJson(String s)throws Exception{JSONObject o=new JSONObject(s);Models.Folder f=new Models.Folder();f.title=o.optString("title");f.publisher=o.optString("publisher");f.avatarUrl=o.optString("avatar");f.description=o.optString("description");f.saveUrl=o.optString("save");f.url=o.optString("url");f.password=o.optString("password");f.folderId=o.optString("folderId");f.page=o.optInt("page",1);f.hasMore=o.optBoolean("hasMore");f.remoteSearch=o.optBoolean("remoteSearch");f.failedMembers=o.optInt("failedMembers");f.nextPageReadyAt=o.optLong("nextPageReadyAt");JSONArray a=o.optJSONArray("items");if(a!=null)for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);Models.Item x=new Models.Item();x.title=j.optString("title");x.url=j.optString("url");x.shareUrl=j.optString("shareUrl",x.url);x.size=j.optString("size");x.time=j.optString("time");x.iconUrl=j.optString("icon");x.source=j.optString("source");x.password=j.optString("password");x.description=j.optString("description");x.folderId=j.optString("folderId");x.error=j.optString("error");x.folder=j.optBoolean("folder");f.items.add(x);}return f;}
}
