package cc.nkbr.lanzouplus;

import android.view.View;
import android.widget.GridLayout;
import android.widget.ScrollView;
import java.util.*;

/** A view window over the complete source list, retaining its full scroll extent. */
final class SourceListWindow {
  final MainActivity owner;
  final GridLayout grid;
  final ScrollView scroll;
  final List<Models.Source> values;
  final int session;
  final Map<String,View> mounted=new LinkedHashMap<>();
  int first=-1,last=-1,columns=-1,width=-1,slot=-1,generation;
  boolean posted,disposed;
  final View.OnLayoutChangeListener layout=(v,l,t,r,b,ol,ot,or_,ob)->refresh();
  SourceListWindow(MainActivity owner,GridLayout grid,List<Models.Source> values){
    this.owner=owner;this.grid=grid;this.values=new ArrayList<>(values);scroll=owner.pageScroll;session=owner.navigationSession;
    scroll.addOnLayoutChangeListener(layout);refresh();
  }
  void refresh(){if(disposed||posted)return;posted=true;grid.postOnAnimation(()->{posted=false;if(!disposed&&session==owner.navigationSession&&owner.sourceGrid==grid)render();});}
  void reflow(){first=-1;refresh();}
  void dispose(){disposed=true;generation++;scroll.removeOnLayoutChangeListener(layout);mounted.clear();}
  void render(){
    int count=owner.itemColumns(),available=scroll.getWidth()-scroll.getPaddingLeft()-scroll.getPaddingRight();
    if(available<=0)available=owner.root.getWidth()-owner.root.getPaddingLeft()-owner.root.getPaddingRight();if(available<=0)return;
    float font=Math.max(1f,owner.getResources().getConfiguration().fontScale);
    boolean collection=owner.inlineSourcePage!=null&&owner.inlineSourcePage.collection();
    int rowHeight=Math.round(owner.dp(collection?120+(owner.showSourceLinks?32:0):owner.showSourceLinks?104:68)*font),viewport=Math.max(owner.dp(48),scroll.getHeight());
    int preferred=owner.sourceListDisplayCount();
    int viewportRows=(viewport+rowHeight-1)/rowHeight+1;
    int budgetRows=preferred==0?viewportRows+2:Math.max(viewportRows,(preferred+count-1)/count);
    int extra=Math.max(0,(budgetRows-viewportRows)/2);
    int totalRows=(values.size()+count-1)/count,startRow=Math.min(Math.max(0,totalRows-1),Math.max(0,scroll.getScrollY()/rowHeight-extra));
    int endRow=Math.min(totalRows,startRow+budgetRows);
    int start=Math.min(values.size(),startRow*count),end=Math.min(values.size(),endRow*count);
    if(start==first&&end==last&&columns==count&&width==available&&slot==rowHeight)return;
    first=start;last=end;columns=count;width=available;slot=rowHeight;
    Map<String,View> previous=new LinkedHashMap<>(mounted);mounted.clear();Set<String> ids=new LinkedHashSet<>();for(int i=start;i<end;i++)ids.add(owner.sourceKey(values.get(i)));
    owner.sourceSelectionChecks.keySet().retainAll(ids);grid.removeAllViews();grid.setColumnCount(count);
    if(collection){Set<String> items=new HashSet<>();for(int i=start;i<end;i++)items.add(owner.inlineSourcePage.itemKey(values.get(i)));owner.selectionChecks.keySet().retainAll(items);}
    grid.setPadding(0,startRow*rowHeight,0,Math.max(0,(totalRows-endRow)*rowHeight));grid.setMinimumHeight(totalRows*rowHeight);
    int cell=Math.max(1,(available-owner.dp(8)*count)/count);
    mount(start,start,end,count,cell,rowHeight,collection,previous,++generation);
  }
  private void mount(int cursor,int start,int end,int count,int cell,int rowHeight,boolean collection,Map<String,View> previous,int ticket){
    if(disposed||ticket!=generation||session!=owner.navigationSession||owner.sourceGrid!=grid)return;
    int stop=Math.min(end,cursor+4);long began=System.nanoTime();
    for(;cursor<stop;cursor++){
      int i=cursor;
      Models.Source source=values.get(i);String id=owner.sourceKey(source);View row=previous.remove(id);if(row==null)row=collection?owner.inlineSourcePage.row(source):owner.sourceRow(source);
      GridLayout.LayoutParams params=new GridLayout.LayoutParams(GridLayout.spec((i-start)/count),GridLayout.spec((i-start)%count));
      params.width=cell;params.height=rowHeight-owner.dp(2);params.setMargins(owner.dp(4),owner.dp(1),owner.dp(4),owner.dp(1));grid.addView(row,params);mounted.put(id,row);
      if(System.nanoTime()-began>6_000_000L){cursor++;break;}
    }
    if(cursor<end){final int next=cursor;grid.postOnAnimation(()->mount(next,start,end,count,cell,rowHeight,collection,previous,ticket));}
    if(owner.sourceSelectionMode)owner.updateSourceSelectionSummary();
  }
}
