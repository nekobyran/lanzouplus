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
    int concurrency=16;
    /** Fair active-source time slice; 0 keeps an active source until it finishes. */
    long sourceSwitchDelayMillis=0L;
    boolean untilLastPage=true;
    /** Search every nested folder discovered below a source. Session-only UI option. */
    boolean recursiveFolders;
    /** Use only Lanzou's search API; never browse or paginate directory pages. */
    boolean apiOnly;
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

    SearchOptions withApiOnly(boolean value){
      apiOnly=value;
      return this;
    }

    SearchOptions normalized(){
      long sourceSlice=sourceSwitchDelayMillis==0?0L:Math.max(1000L,Math.min(60000L,sourceSwitchDelayMillis));
      return new SearchOptions(Math.max(1,Math.min(4096,concurrency)),sourceSlice,untilLastPage,Math.max(0,Math.min(1000,maxPages))).withRecursiveFolders(recursiveFolders).withApiOnly(apiOnly);
    }
  }
  interface Progress {
    void onProgress(int done,int total,int found,String current);
    /** Logical sources currently scheduled; independent from the bounded HTTP worker count. */
    default void onActivity(int active,int total,String current) {}

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
  }
  interface FolderProgress {
    void onProgress(int folders,int files,String current);
  }
}
