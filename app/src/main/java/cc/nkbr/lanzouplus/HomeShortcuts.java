package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Home shortcut strip below the search field, with a persistent add and paged project tiles. */
final class HomeShortcuts {
  static final int SUGGESTED_RECOMMENDATION_OFFSET_DP=194;
  static final int PORTRAIT_CAPACITY=5,LANDSCAPE_CAPACITY=8;
  private static final int STRIP_HEIGHT_DP=100,PAGER_HEIGHT_DP=88,DOT_HEIGHT_DP=12;
  private static final int ICON_DP=52,LABEL_DP=20,TILE_GAP_DP=3;

  private final MainActivity owner;
  private final FrameLayout stage;
  private final HomeShortcutStore store;
  private final LinearLayout strip;
  private final SnapPager pager;
  private final LinearLayout dots;
  private final List<View> dotViews=new ArrayList<>();
  private final List<LinearLayout> renderedPages=new ArrayList<>();
  private final List<Integer> shortcutCounts=new ArrayList<>();
  private final List<Integer> addCounts=new ArrayList<>();
  private List<HomeShortcutStore.Entry> entries=new ArrayList<>();
  private int width,currentPage;
  private boolean searchFocused;

  static HomeShortcuts install(MainActivity owner,FrameLayout homeStage,int width){
    HomeShortcuts shortcuts=new HomeShortcuts(owner,homeStage,width);
    FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(
        Math.max(1,shortcuts.width),owner.dp(STRIP_HEIGHT_DP),Gravity.CENTER);
    homeStage.addView(shortcuts.strip,params);
    shortcuts.strip.setTranslationY(owner.dp(90));
    shortcuts.refresh();
    homeStage.post(shortcuts::reflow);
    return shortcuts;
  }

  private HomeShortcuts(MainActivity owner,FrameLayout stage,int width){
    this.owner=owner;
    this.stage=stage;
    this.width=Math.max(1,width);
    store=new HomeShortcutStore(owner);
    strip=new LinearLayout(owner);
    strip.setOrientation(LinearLayout.VERTICAL);
    strip.setGravity(Gravity.CENTER_HORIZONTAL);
    strip.setClipChildren(false);
    strip.setClipToPadding(false);
    pager=new SnapPager(owner);
    pager.setContentDescription("首页快捷项目，左右滑动翻页");
    pager.setOnPageChanged(page->{
      currentPage=page;
      updateDots(page);
    });
    strip.addView(pager,new LinearLayout.LayoutParams(-1,owner.dp(PAGER_HEIGHT_DP)));
    dots=new LinearLayout(owner);
    dots.setOrientation(LinearLayout.HORIZONTAL);
    dots.setGravity(Gravity.CENTER);
    dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    strip.addView(dots,new LinearLayout.LayoutParams(-1,owner.dp(DOT_HEIGHT_DP)));
  }

  /** Search mode owns the whole lower canvas, so the shortcut strip yields until search closes. */
  void setSearchFocused(boolean focused){
    searchFocused=focused;
    strip.animate().cancel();
    if(focused){
      if(!owner.motionEnabled()||strip.getVisibility()!=View.VISIBLE){
        strip.setAlpha(0f);
        strip.setVisibility(View.INVISIBLE);
        return;
      }
      strip.animate().alpha(0f).setDuration(140).withEndAction(()->{
        if(searchFocused){
          strip.setAlpha(0f);
          strip.setVisibility(View.INVISIBLE);
        }
      }).start();
      return;
    }
    strip.setVisibility(View.VISIBLE);
    if(owner.motionEnabled()){
      strip.setAlpha(0f);
      strip.animate().alpha(1f).setDuration(180).start();
    }else strip.setAlpha(1f);
  }

  /** Rebuilds pages after the root or source collection changes. */
  void refresh(){
    entries=store.load();
    rebuild(currentPage);
  }

  /** Recomputes width and capacity after a layout, split-screen or configuration change. */
  void reflow(){
    int nextWidth=measuredWidth();
    int pageBefore=pager.getCurrentPage();
    width=nextWidth;
    ViewGroup.LayoutParams raw=strip.getLayoutParams();
    if(raw instanceof FrameLayout.LayoutParams){
      FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)raw;
      if(params.width!=width){
        params.width=width;
        strip.setLayoutParams(params);
      }
    }
    rebuild(pageBefore<0?currentPage:pageBefore);
    if(searchFocused)setSearchFocused(true);
  }

  void showPage(int page){
    int safe=Math.max(0,Math.min(pager.getPageCount()-1,page));
    currentPage=safe;
    pager.goToPage(safe,false);
    updateDots(safe);
  }

  int currentPage(){return pager.getCurrentPage();}
  int pageCount(){return pager.getPageCount();}
  int capacity(){return capacityFor(isLandscape());}
  int width(){return width;}
  View view(){return strip;}
  int renderedShortcutCount(int page){
    return page<0||page>=shortcutCounts.size()?0:shortcutCounts.get(page);
  }
  int renderedAddCount(int page){
    return page<0||page>=addCounts.size()?0:addCounts.get(page);
  }

  static int capacityFor(boolean landscape){
    return landscape?LANDSCAPE_CAPACITY:PORTRAIT_CAPACITY;
  }

  static int pageCountForSlots(List<HomeShortcutStore.Entry> values,boolean landscape){
    int capacity=capacityFor(landscape),highest=-1,occupied=0;
    if(values!=null)for(HomeShortcutStore.Entry entry:values){
      if(entry==null||entry.slot<0)continue;
      highest=Math.max(highest,entry.slot);
    }
    int pages=highest<0?1:(highest+capacity)/capacity;
    if(values!=null)for(HomeShortcutStore.Entry entry:values){
      if(entry!=null&&entry.slot>=0&&entry.slot<pages*capacity)occupied++;
    }
    return occupied==pages*capacity?pages+1:pages;
  }

  static int pageStartFor(int page,boolean landscape){
    int capacity=capacityFor(landscape);
    return page<=0?0:page*capacity;
  }

  private int measuredWidth(){
    int available=stage.getWidth();
    if(available<=0)available=width;
    return Math.max(1,Math.min(available,owner.dp(900)));
  }

  private boolean isLandscape(){
    int stageWidth=stage.getWidth(),stageHeight=stage.getHeight();
    if(stageWidth>0&&stageHeight>0)return stageWidth>stageHeight;
    return owner.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
  }

  private void rebuild(int requestedPage){
    if(entries==null)entries=new ArrayList<>();
    int pages=pageCountForSlots(entries,isLandscape());
    currentPage=Math.max(0,Math.min(pages-1,requestedPage));
    LinearLayout pagesView=new LinearLayout(owner);
    pagesView.setOrientation(LinearLayout.HORIZONTAL);
    renderedPages.clear();
    shortcutCounts.clear();
    addCounts.clear();
    for(int page=0;page<pages;page++)pagesView.addView(pageView(page),
        new LinearLayout.LayoutParams(width,ViewGroup.LayoutParams.MATCH_PARENT));
    pager.installPages(pagesView,pages,width,currentPage);
    rebuildDots(pages,currentPage);
  }

  private View pageView(int page){
    LinearLayout row=new LinearLayout(owner);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.TOP);
    row.setPadding(0,0,0,0);
    int capacity=capacity();
    int start=pageStartFor(page,isLandscape());
    int shortcuts=0,adds=0;
    for(int slot=start;slot<start+capacity;slot++){
      HomeShortcutStore.Entry entry=entryAtSlot(slot);
      if(entry!=null){
        row.addView(shortcutTile(entry,slot),tileParams());
        shortcuts++;
      }else{
        row.addView(addTile(page,slot),tileParams());
        adds++;
      }
    }
    renderedPages.add(row);
    shortcutCounts.add(shortcuts);
    addCounts.add(adds);
    return row;
  }

  private LinearLayout.LayoutParams tileParams(){
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f);
    params.setMarginStart(owner.dp(TILE_GAP_DP));
    params.setMarginEnd(owner.dp(TILE_GAP_DP));
    return params;
  }

  private View shortcutTile(HomeShortcutStore.Entry entry,int slot){
    LinearLayout tile=new LinearLayout(owner);
    tile.setOrientation(LinearLayout.VERTICAL);
    tile.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
    tile.setPadding(0,owner.dp(2),0,0);
    tile.setClickable(true);
    tile.setFocusable(true);
    String title=entry.title==null||entry.title.trim().isEmpty()?"未命名项目":entry.title.trim();
    tile.setContentDescription("打开快捷项 "+title);
    tile.setBackground(owner.filterRipple(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)));

    ImageView icon=new ImageView(owner);
    icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
    icon.setBackground(owner.filterRipple(owner.shape(owner.SURFACE,17)));
    icon.setClipToOutline(true);
    icon.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    if(entry.icon==null||entry.icon.isEmpty()){
      icon.setImageResource(entry.kind==Models.SOURCE_SINGLE?R.drawable.ic_file:R.drawable.ic_folder);
      icon.setColorFilter(owner.PRIMARY);
    }else{
      icon.clearColorFilter();
      owner.loadImage(entry.icon,icon);
    }
    tile.addView(icon,new LinearLayout.LayoutParams(owner.dp(ICON_DP),owner.dp(ICON_DP)));

    TextView label=owner.text(title,11,owner.TEXT);
    label.setSingleLine(true);
    label.setGravity(Gravity.CENTER);
    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
    label.setPadding(owner.dp(2),0,owner.dp(2),0);
    tile.addView(label,new LinearLayout.LayoutParams(-1,owner.dp(LABEL_DP)));

    tile.setOnClickListener(v->open(entry));
    tile.setOnLongClickListener(v->{
      manage(entry,slot);
      return true;
    });
    return tile;
  }

  private View addTile(int page,int slot){
    LinearLayout tile=new LinearLayout(owner);
    tile.setOrientation(LinearLayout.VERTICAL);
    tile.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
    tile.setPadding(0,owner.dp(2),0,0);
    tile.setClickable(true);
    tile.setFocusable(true);
    tile.setContentDescription("在第 "+(page+1)+" 页第 "+(slot-page*capacity()+1)+" 个位置添加快捷项目");
    tile.setBackground(owner.filterRipple(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)));

    ImageView plus=new ImageView(owner);
    plus.setImageResource(R.drawable.ic_add);
    plus.setColorFilter(owner.PRIMARY);
    plus.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    plus.setPadding(owner.dp(12),owner.dp(12),owner.dp(12),owner.dp(12));
    plus.setBackground(owner.filterRipple(owner.shape(owner.SURFACE,17)));
    plus.setClipToOutline(true);
    plus.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    tile.addView(plus,new LinearLayout.LayoutParams(owner.dp(ICON_DP),owner.dp(ICON_DP)));

    TextView label=owner.text("添加",11,owner.MUTED);
    label.setSingleLine(true);
    label.setGravity(Gravity.CENTER);
    tile.addView(label,new LinearLayout.LayoutParams(-1,owner.dp(LABEL_DP)));
    tile.setOnClickListener(v->choose(null,slot));
    return tile;
  }

  private void open(HomeShortcutStore.Entry entry){
    Models.Source source=entry.source(owner);
    if(source==null||source.kind==Models.SOURCE_COMPOSITE&&source.members.isEmpty()&&source.url.isEmpty()){
      owner.showNotice("这个快捷项目已不存在，长按可替换或删除",true);
      return;
    }
    owner.openSourcePage(source);
  }

  private void manage(HomeShortcutStore.Entry entry,int slot){
    AlertDialog dialog=new AlertDialog.Builder(owner)
        .setTitle(entry.title==null||entry.title.isEmpty()?"管理快捷项目":entry.title)
        .setItems(new String[]{"替换","删除"},(ignored,which)->{
          if(which==0)choose(entry,slot);
          else{
            int current=indexOf(entry.id);
            if(current<0)return;
            store.remove(current);
            refresh();
            owner.showNotice("已删除快捷项目",false);
          }
        })
        .setNegativeButton("取消",null)
        .create();
    owner.showRounded(dialog);
  }

  private void choose(HomeShortcutStore.Entry replacing,int targetSlot){
    HomeShortcutPicker.choose(owner,store.load(),replacing,picked->{
      if(replacing!=null){boolean replacement=false;for(HomeShortcutStore.Entry entry:picked.entries)if(entry.id.equals(replacing.id)||indexOf(entry.id)<0){replacement=true;break;}if(!replacement){owner.showNotice("请选择一个新的快捷项目",true);return;}}
      store.applySelection(picked.entries,picked.editableIds,targetSlot);
      refresh();
      owner.showNotice("快捷项目已保存",false);
    });
  }

  private int indexOf(String id){
    List<HomeShortcutStore.Entry> values=store.load();
    for(int i=0;i<values.size();i++)if(values.get(i).id.equals(id))return i;
    return -1;
  }

  private HomeShortcutStore.Entry entryAt(int index){
    List<HomeShortcutStore.Entry> values=store.load();
    return index<0||index>=values.size()?null:values.get(index);
  }

  private HomeShortcutStore.Entry entryAtSlot(int slot){
    for(HomeShortcutStore.Entry entry:entries)if(entry.slot==slot)return entry;
    return null;
  }

  private void rebuildDots(int pages,int selected){
    dots.removeAllViews();
    dotViews.clear();
    dots.setVisibility(pages>1?View.VISIBLE:View.INVISIBLE);
    if(pages<=1){
      dots.setContentDescription("快捷项目共 1 页");
      return;
    }
    for(int i=0;i<pages;i++){
      View dot=new View(owner);
      LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(owner.dp(5),owner.dp(5));
      params.setMarginStart(owner.dp(3));
      params.setMarginEnd(owner.dp(3));
      dot.setLayoutParams(params);
      dot.setClickable(true);
      dot.setFocusable(true);
      dot.setContentDescription("第 "+(i+1)+" 页，共 "+pages+" 页");
      int page=i;
      dot.setOnClickListener(v->showPage(page));
      dots.addView(dot);
      dotViews.add(dot);
    }
    updateDots(selected);
  }

  private void updateDots(int selected){
    for(int i=0;i<dotViews.size();i++){
      View dot=dotViews.get(i);
      dot.setBackground(owner.solidShape(i==selected?owner.PRIMARY:owner.DIV,3));
      dot.setAlpha(i==selected?1f:.55f);
      dot.setContentDescription("第 "+(i+1)+" 页，共 "+dotViews.size()+" 页"+(i==selected?"，当前页":""));
    }
    if(!dotViews.isEmpty())dots.setContentDescription("快捷项目第 "+(selected+1)+" 页，共 "+dotViews.size()+" 页");
  }

  /**
   * A compact one-page-at-a-time horizontal scroller. Fast swipes move one page; slower drags
   * settle on the nearest page. The snap rule follows cover-os Pager (MIT).
   */
  private static final class SnapPager extends HorizontalScrollView {
    private final OverScroller scroller;
    private final int touchSlop,minimumVelocity,maximumVelocity;
    private VelocityTracker velocityTracker;
    private Runnable pageChanged;
    private int pageWidth,pageCount,currentPage,downPage,pendingPage=-1;
    private boolean suppressPageCallback;

    SnapPager(Context context){
      super(context);
      scroller=new OverScroller(context);
      ViewConfiguration configuration=ViewConfiguration.get(context);
      touchSlop=configuration.getScaledTouchSlop();
      minimumVelocity=configuration.getScaledMinimumFlingVelocity();
      maximumVelocity=configuration.getScaledMaximumFlingVelocity();
      setHorizontalScrollBarEnabled(false);
      setHorizontalFadingEdgeEnabled(false);
      setFillViewport(true);
      setOverScrollMode(View.OVER_SCROLL_NEVER);
      setClipChildren(false);
      setClipToPadding(false);
    }

    void setOnPageChanged(PageChanged listener){
      pageChanged=()->{
        if(listener!=null)listener.changed(currentPage);
      };
    }

    void installPages(LinearLayout content,int pages,int width,int selected){
      suppressPageCallback=true;
      removeAllViews();
      addView(content,new LayoutParams(Math.max(width,width*Math.max(1,pages)),ViewGroup.LayoutParams.MATCH_PARENT));
      pageCount=Math.max(1,pages);
      pageWidth=Math.max(1,width);
      currentPage=Math.max(0,Math.min(pageCount-1,selected));
      pendingPage=currentPage;
      downPage=currentPage;
      scrollTo(currentPage*pageWidth,0);
      suppressPageCallback=false;
      notifyPage();
    }

    int getPageCount(){return pageCount;}
    int getCurrentPage(){return currentPage;}

    void goToPage(int page,boolean animated){
      int target=Math.max(0,Math.min(pageCount-1,page));
      currentPage=target;
      scroller.forceFinished(true);
      if(isLayoutRequested()||getWidth()==0){pendingPage=target;notifyPage();return;}
      int dx=target*pageWidth-getScrollX();
      if(animated&&Math.abs(dx)>0){
        scroller.startScroll(getScrollX(),0,dx,0,260);
        postInvalidateOnAnimation();
      }else scrollTo(target*pageWidth,0);
      notifyPage();
    }

    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
      super.onLayout(changed,left,top,right,bottom);
      if(pendingPage>=0){
        currentPage=pendingPage;
        scrollTo(currentPage*pageWidth,0);
        pendingPage=-1;
        notifyPage();
      }
    }

    @Override public boolean onTouchEvent(MotionEvent event){
      if(pageWidth<=0)return super.onTouchEvent(event);
      int action=event.getActionMasked();
      if(action==MotionEvent.ACTION_DOWN){
        scroller.forceFinished(true);
        if(velocityTracker==null)velocityTracker=VelocityTracker.obtain();
        else velocityTracker.clear();
        downPage=Math.round(getScrollX()/(float)pageWidth);
      }
      if(velocityTracker!=null)velocityTracker.addMovement(event);
      boolean handled=super.onTouchEvent(event);
      if(action==MotionEvent.ACTION_MOVE&&handled)getParent().requestDisallowInterceptTouchEvent(true);
      if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
        int velocity=0;
        if(velocityTracker!=null&&action==MotionEvent.ACTION_UP){
          velocityTracker.computeCurrentVelocity(1000,maximumVelocity);
          velocity=Math.round(velocityTracker.getXVelocity());
        }
        if(velocityTracker!=null){
          velocityTracker.recycle();
          velocityTracker=null;
        }
        goToPage(releaseTarget(velocity),true);
      }
      return handled;
    }

    private int releaseTarget(int velocity){
      int nearest=Math.round(getScrollX()/(float)pageWidth);
      if(Math.abs(velocity)>Math.max(minimumVelocity,touchSlop*8)){
        if(velocity<0)return Math.max(nearest,downPage+1);
        return Math.min(nearest,downPage-1);
      }
      return nearest;
    }

    @Override public void fling(int velocityX){
      // Touch release selects one page in onTouchEvent, so the platform fling remains disabled.
    }

    @Override public void computeScroll(){
      if(scroller.computeScrollOffset()){
        scrollTo(scroller.getCurrX(),0);
        postInvalidateOnAnimation();
      }else super.computeScroll();
    }

    @Override protected void onScrollChanged(int left,int top,int oldLeft,int oldTop){
      super.onScrollChanged(left,top,oldLeft,oldTop);
      if(pageWidth<=0||pendingPage>=0)return;
      int page=Math.max(0,Math.min(pageCount-1,Math.round(left/(float)pageWidth)));
      if(page!=currentPage){
        currentPage=page;
        notifyPage();
      }
    }

    private void notifyPage(){
      if(!suppressPageCallback&&pageChanged!=null)pageChanged.run();
    }

    private interface PageChanged {
      void changed(int page);
    }
  }
}
