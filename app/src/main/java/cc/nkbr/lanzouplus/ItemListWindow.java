package cc.nkbr.lanzouplus;

import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.GridLayout;
import android.widget.ScrollView;
import java.util.*;

/** Recycles the visible file and collection rows while preserving paging and selection. */
final class ItemListWindow {
  final MainActivity owner;final GridLayout grid;final ScrollView scroll;final int session;final boolean search;
  List<Models.Item> values=new ArrayList<>();final Map<String,View> mounted=new LinkedHashMap<>();
  /** Detached card views are kept for reuse so flings do not allocate a fresh view tree per row. */
  final ArrayDeque<View> recycler=new ArrayDeque<>(); private static final int RECYCLER_LIMIT=96;
  int limit,renderedLimit=-1,firstRow=-1,lastRow=-1,columns=-1,width=-1,slot=-1;String anchorStart="",anchorEnd="";
  /** Rows kept above and below the viewport. A 3-row margin keeps the mounted set small enough that
   *  the recycler still holds detached cards; a large margin made every entering row inflate brand
   *  new views, which stuttered on flings over lists with thousands of results. */
  private static final int WINDOW_MARGIN_ROWS=3;
  /** Rebuild only when the viewport gets within this many rows of the mounted edge. */
  private static final int WINDOW_HYSTERESIS_ROWS=2;
  boolean posted,disposed,metadataRows;
  final View.OnLayoutChangeListener layout=(v,l,t,r,b,ol,ot,or_,ob)->refresh();
  /** Plain ScrollViews never invalidate the window on drag, so every recycled list listens to scroll offsets. */
  final ViewTreeObserver.OnScrollChangedListener scrollWatcher;
  ItemListWindow(MainActivity owner,GridLayout grid){this(owner,grid,false);}
  /** Search results keep their own grid, so the owning grid is checked through liveGrid. */
  ItemListWindow(MainActivity owner,GridLayout grid,boolean search){this.owner=owner;this.grid=grid;this.search=search;scroll=owner.pageScroll;session=owner.navigationSession;scrollWatcher=()->{owner.onListScrolled();refresh();};scroll.addOnLayoutChangeListener(layout);scroll.getViewTreeObserver().addOnScrollChangedListener(scrollWatcher);}
  void setItems(List<Models.Item> items,int shown){values.clear();values.addAll(items);refreshMetadata();limit=Math.min(shown,values.size());reflow();}
  /** Search results are owned by the activity list, so reference it instead of copying on every batch. */
  void setItemsRef(List<Models.Item> items,int shown){values=items;refreshMetadata();limit=Math.min(shown,values.size());refresh();}
  void refreshMetadata(){if(metadataRows)return;int cap=Math.min(values.size(),400);boolean rows=false;for(int i=0;i<cap;i++){Models.Item value=values.get(i);if(!value.error.isEmpty()||!value.size.isEmpty()||!value.time.isEmpty()||value.folder&&!value.description.isEmpty()){rows=true;break;}}metadataRows=rows;}
  /** Rebinds one already-mounted row after a search result is enriched in place. */
  void updateItem(Models.Item item){if(disposed||item==null)return;View view=mounted.get(item.url);if(view!=null){owner.bindItemCard(view,item);}}
  void dispose(){disposed=true;scroll.removeOnLayoutChangeListener(layout);ViewTreeObserver observer=scroll.getViewTreeObserver();if(observer.isAlive())observer.removeOnScrollChangedListener(scrollWatcher);mounted.clear();}
  void reflow(){firstRow=-1;refresh();}
  /** Cheap identity check so recycled windows only rebuild when the visible slice really changed. */
  private static boolean anchorsIntact(List<Models.Item> values,int from,int to,String start,String end){
    if(from<0||to>=values.size()||from>to)return false;
    return values.get(from).url.equals(start)&&values.get(to).url.equals(end);
  }
  /** Only kicks off deferred icon loads after a drag ends; text and tags stay untouched. */
  void loadPendingIcons(){if(disposed||owner.searchPausedNow()||owner.listScrolling)return;loadIconBatch();}
  /** Feeds at most three deferred icons per frame so a settle does not queue the whole window at once. */
  private void loadIconBatch(){
    if(disposed||owner.searchPausedNow()||owner.listScrolling)return;
    int started=0;
    for(int i=0;i<grid.getChildCount()&&started<3;i++){
      View view=grid.getChildAt(i);Object tag=view.getTag();
      if(tag instanceof MainActivity.ItemCard){MainActivity.ItemCard card=(MainActivity.ItemCard)tag;
        if(card.iconPending&&card.iconUrl!=null&&!card.iconUrl.isEmpty()){card.iconPending=false;owner.loadImage(card.iconUrl,card.icon);started++;}
      }
    }
    if(started>=3)grid.postOnAnimation(this::loadIconBatch);
  }
  /** Re-binds the mounted cards once scrolling settles so deferred icons can load then. */
  void rebindMounted(){loadPendingIcons();}
  void refresh(){if(disposed||posted)return;posted=true;grid.postOnAnimation(()->{posted=false;if(!disposed&&owner.navigationSession==session&&(search?owner.liveGrid==grid:owner.itemsGrid==grid))render();});}
  void render(){
    int count=owner.itemColumns(),available=scroll.getWidth()-scroll.getPaddingLeft()-scroll.getPaddingRight();if(available<=0)return;
    int height=Math.round(owner.dp((metadataRows?120:96)+(owner.showSourceLinks?32:0))*Math.max(1f,owner.getResources().getConfiguration().fontScale));
    int offset=Math.max(0,scroll.getScrollY()-grid.getTop()),rows=(limit+count-1)/count;
    int visibleStart=Math.max(0,offset/height);
    int visibleEnd=Math.min(rows,(offset+scroll.getHeight()+height-1)/height);
    /* Hysteresis: the mounted window is built with a small margin around the viewport and is kept
       until the viewport comes within a couple of rows of its edge. Rebuilding on every visible row
       swap forced a full GridLayout measure per row, which made drags stutter. */
    boolean geometrySame=columns==count&&width==available&&slot==height&&renderedLimit==limit;
    boolean windowCovers=firstRow>=0&&visibleStart-WINDOW_HYSTERESIS_ROWS>=firstRow&&visibleEnd+WINDOW_HYSTERESIS_ROWS<=lastRow;
    boolean anchorsIntact=false;
    if(firstRow>=0&&firstRow<lastRow&&lastRow<=rows){
      int anchorFrom=firstRow*count,anchorTo=Math.min(lastRow*count,limit)-1;
      anchorsIntact=anchorFrom<limit&&anchorTo>=anchorFrom&&anchorsIntact(values,anchorFrom,anchorTo,anchorStart,anchorEnd);
    }
    if(geometrySame&&windowCovers&&anchorsIntact)return;
    int startRow=Math.max(0,visibleStart-WINDOW_MARGIN_ROWS),endRow=Math.min(rows,visibleEnd+WINDOW_MARGIN_ROWS);
    int start=Math.min(limit,startRow*count),end=Math.min(limit,endRow*count);
    String nextStart=start>=end?"":values.get(start).url,nextEnd=start>=end?"":values.get(end-1).url;
    if(geometrySame&&firstRow==startRow&&lastRow==endRow&&anchorStart.equals(nextStart)&&anchorEnd.equals(nextEnd))return;
    anchorStart=nextStart;anchorEnd=nextEnd;renderedLimit=limit;
    firstRow=startRow;lastRow=endRow;columns=count;width=available;slot=height;
    Set<String> wanted=new LinkedHashSet<>();
    for(int i=start;i<end;i++)wanted.add(values.get(i).url);
    /* Detach only cards that left the window; everything inside is reused as-is. */
    Iterator<Map.Entry<String,View>> existing=mounted.entrySet().iterator();
    while(existing.hasNext()){Map.Entry<String,View> entry=existing.next();if(!wanted.contains(entry.getKey())){View recycled=entry.getValue();if(recycled.getParent()==grid)grid.removeView(recycled);if(recycler.size()<RECYCLER_LIMIT)recycler.addLast(recycled);existing.remove();}}
    if(!search)owner.selectionChecks.keySet().retainAll(wanted);
    int topPad=(int)Math.min(Integer.MAX_VALUE/2L,(long)startRow*height),bottomPad=(int)Math.min(Integer.MAX_VALUE/2L,(long)Math.max(0,(rows-endRow))*height),minHeight=(int)Math.min(Integer.MAX_VALUE/2L,(long)rows*height);
    /* GridLayout#setColumnCount always calls requestLayout(), so setting the same value on every
       scroll frame would re-measure the whole 60+ card grid mid-drag. */
    if(grid.getColumnCount()!=count)grid.setColumnCount(count);
    if(grid.getPaddingTop()!=topPad||grid.getPaddingBottom()!=bottomPad)grid.setPadding(0,topPad,0,bottomPad);
    if(grid.getMinimumHeight()!=minHeight)grid.setMinimumHeight(minHeight);
    int cell=Math.max(1,(available-owner.dp(8)*count)/count);
    int childRow=0,childColumn=0;
    for(int i=start;i<end;i++){
      Models.Item item=values.get(i);View view=mounted.get(item.url);
      if(view==null){view=recycler.pollFirst();if(view!=null){Object tag=view.getTag();if(tag instanceof MainActivity.ItemCard){MainActivity.ItemCard card=(MainActivity.ItemCard)tag;if(card.item!=null)owner.selectionChecks.remove(card.item.url);card.item=item;card.iconUrl=null;card.folder=item.folder;card.iconPending=false;card.link.setText("");}owner.bindItemCard(view,item);}else{view=owner.itemRow(item);}mounted.put(item.url,view);}
      else{Object tag=view.getTag();if(!(tag instanceof MainActivity.ItemCard)||((MainActivity.ItemCard)tag).item!=item)owner.bindItemCard(view,item);}
      /* Layout params are immutable per cell slot: re-creating and re-applying them on every window
         shift invalidated the GridLayout measure three times per row. */
      Object rawTag=view.getTag();
      MainActivity.ItemCard card=rawTag instanceof MainActivity.ItemCard?(MainActivity.ItemCard)rawTag:null;
      int cellHeight=height-owner.dp(4);
      boolean needsParams=card==null||card.layoutRow!=childRow||card.layoutColumn!=childColumn||card.layoutColumns!=count||card.layoutCell!=cell||card.layoutHeight!=cellHeight;
      if(needsParams){
        if(card!=null){card.layoutRow=childRow;card.layoutColumn=childColumn;card.layoutColumns=count;card.layoutCell=cell;card.layoutHeight=cellHeight;}
        GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(childRow),GridLayout.spec(childColumn));
        p.width=cell;p.height=cellHeight;p.setMargins(owner.dp(4),owner.dp(2),owner.dp(4),owner.dp(2));
        if(view.getParent()==grid)view.setLayoutParams(p);else grid.addView(view,p);
      }else if(view.getParent()!=grid){
        GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(childRow),GridLayout.spec(childColumn));
        p.width=cell;p.height=cellHeight;p.setMargins(owner.dp(4),owner.dp(2),owner.dp(4),owner.dp(2));
        grid.addView(view,p);
      }
      if(++childColumn>=count){childColumn=0;childRow++;}
    }
  }
}
