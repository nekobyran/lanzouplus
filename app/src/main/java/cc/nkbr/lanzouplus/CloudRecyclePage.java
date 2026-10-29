package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native recycle workspace; the protocol owns authentication and confirmed mutations. */
final class CloudRecyclePage {
  interface Factory { CloudRecycleProtocol.Bin open()throws CloudFilesClient.CloudException; }
  interface Action { void run(CloudRecycleProtocol.Bin bin)throws CloudFilesClient.CloudException; }
  private final MainActivity owner;
  private final Factory factory;
  private final Runnable back;
  private final List<CloudRecycleProtocol.Entry> entries=new ArrayList<>();
  private CloudRecycleProtocol.Bin bin;
  private LinearLayout screen;
  private ListView list;
  private TextView summary;
  private ImageButton refresh,empty;
  private Button retry;
  private BaseAdapter adapter;
  private int generation,page,total,restorePosition,restoreTop;
  private boolean loading,mutating,hasMore,failedAppend,closed;
  private String failure="";

  CloudRecyclePage(MainActivity owner,Factory factory,Runnable back){
    this.owner=owner;this.factory=factory;this.back=back;
  }
  void saveState(Bundle state){
    state.putInt("cloudRecycleFirst",list==null?restorePosition:list.getFirstVisiblePosition());
    state.putInt("cloudRecycleTop",list==null||list.getChildCount()==0?restoreTop:list.getChildAt(0).getTop());
  }
  void restoreState(Bundle state){
    restorePosition=state==null?0:state.getInt("cloudRecycleFirst",0);
    restoreTop=state==null?0:state.getInt("cloudRecycleTop",0);
  }
  void close(){closed=true;generation++;}
  private boolean alive(int request){
    return !closed&&!owner.activityClosing()&&generation==request;
  }
  private boolean current(int request){
    return alive(request)&&owner.pageKind==7&&owner.root==screen;
  }
  void show(){
    if(list!=null){restorePosition=list.getFirstVisiblePosition();restoreTop=list.getChildCount()==0?0:list.getChildAt(0).getTop();}
    closed=false;owner.base();screen=owner.root;owner.pageKind=7;
    owner.activeSource=null;owner.clearFolderTrail();
    owner.systemBackAction=()->{close();back.run();};
    owner.primaryHeader("回收站");
    ImageButton previous=owner.iconButton(R.drawable.ic_back,"返回管理");
    previous.setOnClickListener(v->{close();back.run();});
    owner.pageHeaderRow.addView(previous,0,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    refresh=owner.iconButton(R.drawable.ic_refresh,"刷新回收站");
    refresh.setOnClickListener(v->reload());owner.pageHeaderRow.addView(refresh,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    empty=owner.iconButton(R.drawable.ic_delete_record,"清空回收站");
    empty.setOnClickListener(v->confirmEmpty());owner.pageHeaderRow.addView(empty,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    summary=owner.text("正在读取回收站…",12,owner.MUTED);
    summary.setMaxLines(2);summary.setGravity(Gravity.CENTER_VERTICAL);
    summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    screen.addView(summary,new LinearLayout.LayoutParams(-1,owner.dp(44)));
    list=new ListView(owner);list.setDivider(new android.graphics.drawable.ColorDrawable(owner.DIV));list.setDividerHeight(owner.dp(1));
    adapter=new BaseAdapter(){
      public int getCount(){return entries.size();}
      public Object getItem(int position){return entries.get(position);}
      public long getItemId(int position){return position;}
      public View getView(int position,View recycled,ViewGroup parent){
        TextView row=recycled instanceof TextView?(TextView)recycled:owner.text("",14,owner.TEXT);
        CloudRecycleProtocol.Entry entry=entries.get(position);
        row.setPadding(owner.dp(14),owner.dp(12),owner.dp(14),owner.dp(12));
        row.setMinHeight(owner.dp(64));row.setMaxLines(3);
        row.setText(entry.name+"\n"+(entry.folder?"文件夹":entry.size)+(entry.modifiedAt.isEmpty()?"":" · "+entry.modifiedAt));
        row.setContentDescription(entry.name+(entry.folder?"，文件夹":"，文件")+"，点击管理");
        return row;
      }
    };
    list.setAdapter(adapter);list.setOnItemClickListener((p,v,position,id)->menu(entries.get(position)));
    list.setOnScrollListener(new AbsListView.OnScrollListener(){
      public void onScrollStateChanged(AbsListView view,int state){}
      public void onScroll(AbsListView view,int first,int visible,int count){
        if(count>0&&first+visible>=count-2)load(true);
      }
    });
    screen.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    retry=owner.toolbarTextButton("重试加载");retry.setOnClickListener(v->{boolean append=failedAppend;failure="";load(append);});
    screen.addView(retry,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    render();
    load(false);
  }
  private void reload(){if(loading||mutating)return;bin=null;restorePosition=0;load(false);}
  private void render(){
    boolean busy=loading||mutating;
    refresh.setEnabled(!busy);empty.setEnabled(!busy&&!entries.isEmpty());list.setEnabled(!mutating);
    retry.setVisibility(failure.isEmpty()?View.GONE:View.VISIBLE);retry.setEnabled(!busy);
    summary.setText(mutating?"正在处理…":!failure.isEmpty()?failure:loading?
        (entries.isEmpty()?"正在读取回收站…":failedAppend?"正在加载更多…":"正在刷新回收站…"):
        entries.isEmpty()?"回收站为空":entries.size()+" 个项目"+(total>entries.size()?" / 共 "+total+" 个":""));
    adapter.notifyDataSetChanged();
  }
  private void load(boolean append){
    if(closed||loading||mutating||append&&(!hasMore||entries.isEmpty()||!failure.isEmpty()))return;
    final int requested=++generation,wanted=append?page+1:1;
    final CloudRecycleProtocol.Bin existing=bin;
    loading=true;failure="";failedAppend=append;render();
    owner.io.execute(()->{
      CloudRecycleProtocol.Page found=null;CloudRecycleProtocol.Bin opened=existing;String error="";
      try{if(opened==null)opened=factory.open();found=opened.list(wanted,50);}
      catch(Exception problem){error=owner.friendlyError(problem);}
      final CloudRecycleProtocol.Page result=found;final CloudRecycleProtocol.Bin handle=opened;final String problem=error;
      owner.runOnUiThread(()->{
        if(!alive(requested))return;loading=false;
        if(result==null){failure=problem;render();return;}
        bin=handle;if(!append)entries.clear();
        int before=entries.size();Set<String> seen=new HashSet<>();
        for(CloudRecycleProtocol.Entry entry:entries)seen.add(key(entry));
        for(CloudRecycleProtocol.Entry entry:result.items)if(seen.add(key(entry)))entries.add(entry);
        page=result.page;total=result.total;
        hasMore=result.hasMore&&!result.items.isEmpty()&&(!append||entries.size()>before);
        render();
        if(restorePosition>0&&entries.size()>restorePosition){list.setSelectionFromTop(restorePosition,restoreTop);restorePosition=0;}
        list.post(()->{if(current(requested)&&(restorePosition>=entries.size()||list.getLastVisiblePosition()>=entries.size()-1))load(true);});
      });
    });
  }
  private static String key(CloudRecycleProtocol.Entry entry){return(entry.folder?"folder:":"file:")+entry.id;}
  private void menu(CloudRecycleProtocol.Entry entry){
    if(loading||mutating)return;
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(entry.name)
        .setItems(new String[]{"恢复","永久删除"},(d,choice)->{
          if(choice==0)mutate(bin->bin.restore(entry.id,entry.folder),"已恢复");
          else confirm("永久删除这个项目？",entry.name+"\n删除后无法恢复。",()->mutate(bin->bin.purge(entry.id,entry.folder),"已永久删除"));
        }).setNegativeButton("取消",null).create();owner.showRounded(dialog);
  }
  private void confirmEmpty(){
    if(loading||mutating||entries.isEmpty())return;
    confirm("清空回收站？","将永久删除回收站内的全部文件和文件夹，操作后无法恢复。",()->mutate(CloudRecycleProtocol.Bin::empty,"回收站已清空"));
  }
  private void confirm(String title,String message,Runnable action){
    AlertDialog dialog=new AlertDialog.Builder(owner).setTitle(title).setMessage(message)
        .setNegativeButton("取消",null).setPositiveButton("永久删除",(d,w)->action.run()).create();owner.showRounded(dialog);
  }
  private void mutate(Action action,String message){
    if(closed||loading||mutating||bin==null)return;
    final int requested=++generation;final CloudRecycleProtocol.Bin handle=bin;
    mutating=true;failure="";render();
    owner.io.execute(()->{
      String error="";try{action.run(handle);}catch(Exception problem){error=owner.friendlyError(problem);}
      final String problem=error;
      owner.runOnUiThread(()->{
        if(!alive(requested))return;mutating=false;
        if(!problem.isEmpty()){if(current(requested))owner.showNotice(problem,true);render();return;}
        if(current(requested))owner.showNotice(message,false);reload();
      });
    });
  }
}
