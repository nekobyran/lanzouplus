package cc.nkbr.lanzouplus;

import android.view.View;
import android.widget.GridLayout;
import android.widget.ScrollView;
import java.util.*;

/** Recycles the visible file and collection rows while preserving paging and selection. */
final class ItemListWindow {
  final MainActivity owner;final GridLayout grid;final ScrollView scroll;final int session;
  final List<Models.Item> values=new ArrayList<>();final Map<String,View> mounted=new LinkedHashMap<>();
  int limit,first=-1,last=-1,columns=-1,width=-1,slot=-1;boolean posted,disposed,metadataRows;
  final View.OnLayoutChangeListener layout=(v,l,t,r,b,ol,ot,or_,ob)->refresh();
  ItemListWindow(MainActivity owner,GridLayout grid){this.owner=owner;this.grid=grid;scroll=owner.pageScroll;session=owner.navigationSession;scroll.addOnLayoutChangeListener(layout);}
  void setItems(List<Models.Item> items,int shown){values.clear();values.addAll(items);metadataRows=false;for(Models.Item value:values)if(!value.error.isEmpty()||!value.size.isEmpty()||!value.time.isEmpty()||value.folder&&!value.description.isEmpty()){metadataRows=true;break;}limit=Math.min(shown,values.size());first=-1;refresh();}
  void dispose(){disposed=true;scroll.removeOnLayoutChangeListener(layout);mounted.clear();}
  void reflow(){first=-1;refresh();}
  void refresh(){if(disposed||posted)return;posted=true;grid.postOnAnimation(()->{posted=false;if(!disposed&&owner.navigationSession==session&&owner.itemsGrid==grid)render();});}
  void render(){
    int count=owner.itemColumns(),available=scroll.getWidth()-scroll.getPaddingLeft()-scroll.getPaddingRight();if(available<=0)return;
    int height=Math.round(owner.dp((metadataRows?120:96)+(owner.showSourceLinks?32:0))*Math.max(1f,owner.getResources().getConfiguration().fontScale));
    int offset=Math.max(0,scroll.getScrollY()-grid.getTop()),rows=(limit+count-1)/count;
    int startRow=Math.min(Math.max(0,rows-1),Math.max(0,offset/height-3));
    int endRow=Math.min(rows,(offset+scroll.getHeight()+height-1)/height+4);
    int start=Math.min(limit,startRow*count),end=Math.min(limit,endRow*count);
    if(first==start&&last==end&&columns==count&&width==available&&slot==height)return;
    first=start;last=end;columns=count;width=available;slot=height;
    Map<String,View> old=new LinkedHashMap<>(mounted);mounted.clear();Set<String> ids=new LinkedHashSet<>();for(int i=start;i<end;i++)ids.add(values.get(i).url);owner.selectionChecks.keySet().retainAll(ids);
    grid.removeAllViews();grid.setColumnCount(count);grid.setPadding(0,startRow*height,0,Math.max(0,(rows-endRow)*height));grid.setMinimumHeight(rows*height);
    int cell=Math.max(1,(available-owner.dp(8)*count)/count);
    for(int i=start;i<end;i++){
      Models.Item item=values.get(i);View view=old.get(item.url);if(view==null)view=owner.itemRow(item);owner.bindItemCard(view,item);
      GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec((i-start)/count),GridLayout.spec((i-start)%count));p.width=cell;p.height=height-owner.dp(4);p.setMargins(owner.dp(4),owner.dp(2),owner.dp(4),owner.dp(2));grid.addView(view,p);mounted.put(item.url,view);
    }
  }
}
