package cc.nkbr.lanzouplus;

import java.util.*;

final class Models {
  static final byte SOURCE_OFFICIAL=0,SOURCE_COMPOSITE=1,SOURCE_SINGLE=2;
  static final byte MEMBER_UNKNOWN=0,MEMBER_FILE=1,MEMBER_DIRECTORY=2,MEMBER_FOLDER=3,MEMBER_REMOTE_FOLDER=4;
  static final class Item {
    String title="", url="", shareUrl="", size="", time="", iconUrl="", source="", password="", description="", folderId="", sourceId="", error="";
    boolean folder, sourceEntry;
  }
  static final class Folder {
    String title="", publisher="", avatarUrl="", description="", saveUrl="", url="", password="", folderId="";
    int page=1, apiFolderCount, failedMembers;
    boolean hasMore, remoteSearch;
    long nextPageReadyAt;
    final List<Item> items=new ArrayList<>();
  }
  static final class SourceSearch {
    boolean remoteAvailable, remoteUsed;
    final List<Item> items=new ArrayList<>();
  }
  static final class SourceMember {
    String id="", parentId="", title="", url="", password="", iconUrl="", size="", time="", description="", error="";
    byte kind=MEMBER_UNKNOWN;
    boolean searchable, lightweight, metadataLoaded, iconLoaded, detailsLoaded;
    long refreshedAt;
  }
  static final class Source {
    String id="", nodeId="", title="", url="", password="", error="", publisher="", avatarUrl="", description="", originPath="", originUrl="";
    byte kind=SOURCE_OFFICIAL;
    boolean searchable, overlay, metadataOverride, childDirectory;
    final List<SourceMember> members=new ArrayList<>();
  }
  static final class SourceTestResult {
    final Source source;
    final String originalUrl;
    final boolean success, userSource, applied;
    SourceTestResult(Source source,String originalUrl,boolean success,boolean userSource,boolean applied){this.source=source;this.originalUrl=originalUrl;this.success=success;this.userSource=userSource;this.applied=applied;}
  }
  interface SourceTestProgress {
    void onResult(SourceTestResult result,int done,int total);
    default void onMember(String sourceId,String memberTitle,int done,int total,boolean success) {}
  }
  /** Per-search tuning. LanzouCore consumes a clamped copy for every run. */
  static final class SearchOptions {
    static final int MODE_MIXED=0,MODE_API=1,MODE_DIRECTORY=2,MODE_INDEX=3;
    static final int MASK_API=1,MASK_DIRECTORY=2,MASK_INDEX=4,MASK_ALL=MASK_API|MASK_DIRECTORY|MASK_INDEX;
    int concurrency=0;
    /** Fair active-source time slice; 0 keeps an active source until it finishes. */
    long sourceSwitchDelayMillis=0L;
    boolean untilLastPage=true;
    /** Search every nested folder discovered below a source. Session-only UI option. */
    boolean recursiveFolders;
    /** Directory fuzzy match: exact contains first, then ordered subsequence. API search ignores it. */
    boolean fuzzyMatching;
    /** Legacy single-mode view; modeMask is authoritative for multi-select search backends. */
    int mode=MODE_MIXED,modeMask=MASK_ALL;
    int maxPages;

    SearchOptions() {}
    SearchOptions(int concurrency,long sourceSwitchDelayMillis,boolean untilLastPage){
      this(concurrency,sourceSwitchDelayMillis,untilLastPage,0);
    }

    SearchOptions(int concurrency,long sourceSwitchDelayMillis,boolean untilLastPage,int maxPages){
      this.concurrency=concurrency;
      this.sourceSwitchDelayMillis=sourceSwitchDelayMillis;
      this.untilLastPage=untilLastPage;
      this.maxPages=maxPages;
    }

    SearchOptions withRecursiveFolders(boolean value){
      recursiveFolders=value;
      return this;
    }

    SearchOptions withMode(int value){
      mode=value<MODE_MIXED||value>MODE_INDEX?MODE_MIXED:value;
      modeMask=maskForMode(mode);
      return this;
    }

    SearchOptions withModeMask(int value){
      modeMask=normalizeModeMask(value);
      mode=modeForMask(modeMask);
      return this;
    }

    SearchOptions withFuzzyMatching(boolean value){
      fuzzyMatching=value;
      return this;
    }

    static int normalizeModeMask(int value){int mask=value&MASK_ALL;return mask==0?MASK_DIRECTORY:mask;}
    static int maskForMode(int value){switch(value){case MODE_API:return MASK_API;case MODE_DIRECTORY:return MASK_DIRECTORY;case MODE_INDEX:return MASK_INDEX;default:return MASK_ALL;}}
    static int modeForMask(int mask){mask=normalizeModeMask(mask);return mask==MASK_API?MODE_API:mask==MASK_DIRECTORY?MODE_DIRECTORY:mask==MASK_INDEX?MODE_INDEX:MODE_MIXED;}
    boolean apiEnabled(){return (modeMask&MASK_API)!=0;}
    boolean directoryEnabled(){return (modeMask&MASK_DIRECTORY)!=0;}
    boolean indexEnabled(){return (modeMask&MASK_INDEX)!=0;}
    boolean apiOnly(){return modeMask==MASK_API;}
    boolean directoryOnly(){return modeMask==MASK_DIRECTORY;}
    /** Never admits network work; callers return only persisted search and directory indexes. */
    boolean indexOnly(){return modeMask==MASK_INDEX;}

    SearchOptions normalized(){
      long sourceSlice=sourceSwitchDelayMillis==0?0L:Math.max(1000L,Math.min(60000L,sourceSwitchDelayMillis));
      int mask=normalizeModeMask(modeMask);
      return new SearchOptions(Math.max(0,concurrency),sourceSlice,untilLastPage,Math.max(0,Math.min(1000,maxPages))).withRecursiveFolders(recursiveFolders).withModeMask(mask).withFuzzyMatching((mask&MASK_DIRECTORY)!=0&&fuzzyMatching);
    }
  }
  interface Progress {
    void onProgress(int done,int total,int found,String current);
    /** Logical sources currently scheduled; independent from the bounded HTTP worker count. */
    default void onActivity(int active,int total,String current) {}
    /** Completed API/directory work units; used for smooth progress without redefining source completion. */
    default void onWorkProgress(int doneUnits,int totalUnits) {}

    /**
     * Called from a search worker as soon as one source has produced new,
     * de-duplicated items. Implementations that touch views must marshal the
     * callback to the Android main thread.
     */
    default void onBatch(List<Item> batch) {}
    default void onItemUpdated(Item item) {}
    /** totalPagesSeen is global to one search and is monotonic across workers. */
    default void onPage(String source,int page,int pageItems,int sourceFound,int totalPagesSeen) {}
    default boolean isCancelled(){return false;}
    /** Waits at a source/page boundary; false means the search was superseded. */
    default boolean awaitIfPaused(){return !isCancelled();}
    default void onFailure(String current) {}
    /** A full directory scan for this logical source completed and may advance the persisted index region. */
    default void onIndexSource(String sourceId,String current) {}
  }
  interface FolderProgress {
    void onProgress(int folders,int files,String current);
  }
}
