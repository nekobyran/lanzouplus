package cc.nkbr.lanzouplus;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Keeps search text and ranking out of the UI thread; only the latest request is displayed. */
final class SourceListSearch {
  static final class Entry {
    final Models.Source source;
    String[] fields;
    final boolean members;
    final List<String> categories;
    Entry(Models.Source source,boolean members){
      this.source=source;
      this.members=members;
      categories=SourceCategoryPicker.labels(source);
    }
    int rank(String query,boolean fuzzy){return rank(query,fuzzy?3:0);}
    int rank(String query,int level){
      if(fields==null)fields=MainActivity.sourceListCorpus(source,members).toLowerCase(Locale.ROOT).split("\n");
      int best=Integer.MAX_VALUE;
      for(String field:fields){
        if(field.length()<2)continue;
        boolean title=field.startsWith("t:");
        int rank=LanzouCore.matchRank(field.substring(2),query,title?level:0);
        if(rank>=0)best=Math.min(best,rank+(title?0:3));
        if(best==0)break;
      }
      return best==Integer.MAX_VALUE?-1:best;
    }
  }
  static final class Result {
    final List<Models.Source> values=new ArrayList<>();
    int lanzou,web;
  }
  static final class Ranked {
    final Models.Source source;final int rank;
    Ranked(Models.Source source,int rank){this.source=source;this.rank=rank;}
  }
  static Result filter(List<Entry> entries,List<String> categories,String query,boolean fuzzy,BooleanSupplier cancelled){
    return filter(entries,categories,query,fuzzy?3:0,cancelled);
  }
  static Result filter(List<Entry> entries,List<String> categories,String query,int level,BooleanSupplier cancelled){
    String key=query.toLowerCase(Locale.ROOT);Result result=new Result();List<Ranked> matches=new ArrayList<>();
    for(Entry entry:entries){
      if(cancelled.getAsBoolean())return null;
      Models.Source source=entry.source;
      if(source.kind==Models.SOURCE_WEB)result.web++;else result.lanzou++;
      if(!SourceCategories.matches(entry.categories,categories))continue;
      int rank=key.isEmpty()?0:entry.rank(key,level);
      if(rank>=0)matches.add(new Ranked(source,rank));
    }
    matches.sort((a,b)->{
      int rank=Integer.compare(a.rank,b.rank);if(rank!=0)return rank;
      int left=kind(a.source),right=kind(b.source);
      return left!=right?Integer.compare(left,right):a.source.title.compareToIgnoreCase(b.source.title);
    });
    for(Ranked value:matches)result.values.add(value.source);
    return result;
  }
  private static int kind(Models.Source source){return source.kind==Models.SOURCE_WEB?2:source.kind==Models.SOURCE_SINGLE?1:0;}

  private final MainActivity owner;
  private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r->{Thread t=new Thread(r,"source-list-filter");t.setDaemon(true);return t;});
  private List<Models.Source> indexedList;
  private int indexedRevision=-1;
  private boolean indexedMembers;
  private List<Entry> index=Collections.emptyList();
  private volatile int generation;
  SourceListSearch(MainActivity owner){this.owner=owner;worker.allowCoreThreadTimeOut(true);}
  void submit(android.widget.GridLayout grid,List<Models.Source> all,String query,android.widget.TextView heading){
    final int token=++generation,session=owner.navigationSession,revision=owner.sourceDataRevision;
    final List<String> categories=new ArrayList<>(owner.selectedSourceCategories);
    if(categories.isEmpty()&&!"全部".equals(owner.activeSourceCategory))categories.add(owner.activeSourceCategory);
    final boolean members=categories.isEmpty()&&owner.sessionSearchRecursiveFolders;
    final int fuzzyLevel=owner.searchFuzzyLevel();
    final List<Models.Source> snapshot=new ArrayList<>(all);
    worker.getQueue().clear();
    worker.execute(()->{
      if(token!=generation)return;
      if(indexedList!=all||indexedRevision!=revision||indexedMembers!=members){
        List<Entry> built=new ArrayList<>(snapshot.size());
        for(Models.Source source:snapshot){if(token!=generation)return;built.add(new Entry(source,members));}
        index=built;indexedList=all;indexedRevision=revision;indexedMembers=members;
      }
      Result result=filter(index,categories,query,fuzzyLevel,()->token!=generation);
      if(result==null)return;
      owner.runOnUiThread(()->{
        if(token!=generation||session!=owner.navigationSession||grid!=owner.sourceGrid||all!=owner.sourcePageSources)return;
        if(owner.sourceListWindow!=null)owner.sourceListWindow.dispose();
        grid.removeAllViews();owner.sourceSelectionChecks.clear();owner.visibleSources.clear();owner.visibleSources.addAll(result.values);
        if(owner.inlineSourcePage!=null)owner.inlineSourcePage.applied(result.values);
        heading.setText("列表 · "+(categories.isEmpty()?"全部":String.join(" / ",categories))+" · 蓝奏云源 "+result.lanzou+" · 非蓝奏云源 "+result.web+" · 显示 "+result.values.size());
        owner.sourceListWindow=new SourceListWindow(owner,grid,result.values);
        if(owner.sourceSelectionMode)owner.updateSourceSelectionSummary();
      });
    });
  }
  void close(){generation++;worker.shutdownNow();}
}
