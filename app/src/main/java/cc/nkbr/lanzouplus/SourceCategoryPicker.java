package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** Categories are searchable tags. Directory browsing is a separate local-only view. */
final class SourceCategoryPicker {
  static void editSelection(MainActivity owner,Collection<String> selected,String previous,Runnable changed){
    AlertDialog actions=new AlertDialog.Builder(owner).setTitle(previous).setItems(new String[]{"删除标签","更换标签"},(dialog,choice)->{
      if(choice==0){selected.remove(previous);changed.run();return;}
      EditText input=owner.sourceInput("分类名称",android.text.InputType.TYPE_CLASS_TEXT);input.setText(previous);input.setSelectAllOnFocus(true);
      LinearLayout panel=new LinearLayout(owner);panel.setPadding(owner.dp(20),0,owner.dp(20),0);panel.addView(input,new LinearLayout.LayoutParams(-1,owner.dp(52)));
      AlertDialog edit=new AlertDialog.Builder(owner).setTitle("更换分类").setView(panel).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
      edit.setOnShowListener(d->edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
        String label=SourceCategories.name(input.getText().toString());if(label.isEmpty()){input.setError("请输入分类名称");return;}
        List<String> values=new ArrayList<>(selected);int index=values.indexOf(previous);if(index>=0)values.set(index,label);selected.clear();selected.addAll(SourceCategories.normalized(values));CategoryEditor.rememberShortcut(owner,label);changed.run();edit.dismiss();
      }));owner.showRounded(edit);
    }).create();owner.showRounded(actions);
  }
  static List<String> labels(Models.Source source){
    List<String> out=new ArrayList<>(source.categories);
    if(source.childDirectory)out.add("子目录");
    if(source.kind==Models.SOURCE_COMPOSITE)out.add("自建合集");
    else if(source.kind==Models.SOURCE_REPOSITORY)out.add("GitHub源");
    else if(source.kind==Models.SOURCE_WEB)out.add("网页源");
    else if(source.kind==Models.SOURCE_SINGLE)out.add("单软件");
    else out.add("列表源");
    return SourceCategories.normalized(out);
  }
  static List<String> labels(Models.SourceMember member){
    List<String> out=new ArrayList<>(member.categories);
    out.add(member.kind==Models.MEMBER_FILE?"单软件":"子目录");
    return SourceCategories.normalized(out);
  }
  static boolean matches(Models.Source source,Collection<String> selected){return SourceCategories.matches(labels(source),selected);}
  static boolean matches(Models.SourceMember member,Collection<String> selected){return SourceCategories.matches(labels(member),selected);}
  static List<String> collect(List<Models.Source> sources){
    List<String> out=new ArrayList<>();
    for(Models.Source source:sources){out.addAll(labels(source));for(Models.SourceMember member:source.members)out.addAll(labels(member));}
    return SourceCategories.normalized(out);
  }

  static AlertDialog show(MainActivity owner,List<Models.Source> known,Collection<String> initial,Consumer<List<String>> done){
    return new Picker(owner,known,initial,done).show();
  }
  static AlertDialog show(MainActivity owner,List<Models.Source> known,Collection<String> initial,Consumer<List<String>> done,Runnable cancel){
    Picker picker=new Picker(owner,known,initial,done);picker.cancel=cancel;return picker.show();
  }

  private static final class Picker {
    final MainActivity owner;
    final List<Models.Source> known;
    final LinkedHashSet<String> selected=new LinkedHashSet<>();
    final Consumer<List<String>> done;
    final List<String> options=new ArrayList<>(),visible=new ArrayList<>();
    LinearLayout chips;
    EditText search;
    BaseAdapter adapter;
    AlertDialog dialog;
    Runnable cancel;

    Picker(MainActivity owner,List<Models.Source> known,Collection<String> initial,Consumer<List<String>> done){
      this.owner=owner;this.known=known;this.done=done;selected.addAll(SourceCategories.normalized(initial));
    }
    AlertDialog show(){
      LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(18),0,owner.dp(18),0);
      LinearLayout input=new LinearLayout(owner);input.setGravity(Gravity.CENTER_VERTICAL);
      search=owner.sourceInput("搜索或添加分类",android.text.InputType.TYPE_CLASS_TEXT);
      input.addView(search,new LinearLayout.LayoutParams(0,owner.dp(50),1));
      ImageButton add=owner.iconButton(R.drawable.ic_add,"添加常驻分类");input.addView(add,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));panel.addView(input);
      HorizontalScrollView tags=new HorizontalScrollView(owner);tags.setHorizontalScrollBarEnabled(false);
      chips=new LinearLayout(owner);chips.setGravity(Gravity.CENTER_VERTICAL);tags.addView(chips);panel.addView(tags,new LinearLayout.LayoutParams(-1,owner.dp(44)));
      ListView list=new ListView(owner);list.setDividerHeight(0);
      adapter=new BaseAdapter(){
        public int getCount(){return visible.size();} public Object getItem(int position){return visible.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
          CheckBox row=recycled instanceof CheckBox?(CheckBox)recycled:new CheckBox(owner);
          String label=visible.get(position);row.setText(label);row.setTextColor(owner.TEXT);row.setMinHeight(owner.dp(48));row.setButtonTintList(android.content.res.ColorStateList.valueOf(owner.PRIMARY));
          row.setOnCheckedChangeListener(null);row.setChecked(selected.contains(label));
          row.setOnCheckedChangeListener((button,checked)->{if(checked)selected.add(label);else selected.remove(label);renderChips();});return row;
        }
      };
      list.setAdapter(adapter);panel.addView(list,new LinearLayout.LayoutParams(-1,Math.min(owner.dp(200),Math.max(owner.dp(48),owner.safeContentHeight()-owner.dp(280)))));
      Button directories=owner.toolbarTextButton("按目录查看分类");directories.setContentDescription("查看各级目录拥有的分类");panel.addView(directories,new LinearLayout.LayoutParams(-1,owner.dp(48)));
      dialog=new AlertDialog.Builder(owner).setTitle("选择分类").setView(panel).setNegativeButton("取消",null)
          .setNeutralButton("清空",null).setPositiveButton("应用",null).create();
      dialog.setOnShowListener(d->{if(cancel!=null)dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,dialog,cancel));dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{selected.clear();renderChips();adapter.notifyDataSetChanged();});dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,dialog,()->done.accept(new ArrayList<>(selected))));});
      if(cancel!=null)dialog.setOnKeyListener((d,key,event)->{if(key!=android.view.KeyEvent.KEYCODE_BACK)return false;if(event.getAction()==android.view.KeyEvent.ACTION_UP)HistoryPopupNavigation.switchCollection(owner,dialog,cancel);return true;});
      search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){filter();}});
      add.setOnClickListener(v->{String label=SourceCategories.name(search.getText().toString());if(label.isEmpty()){search.setError("请输入分类名称");return;}CategoryEditor.rememberShortcut(owner,label);selected.add(label);search.setText("");refreshOptions();renderChips();});
      directories.setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,dialog,()->directory(null,"",new ArrayList<>())));
      refreshOptions();renderChips();owner.showRounded(dialog);return dialog;
    }
    void refreshOptions(){options.clear();List<String> all=new ArrayList<>(collect(known));all.addAll(CategoryEditor.getShortcuts(owner));all.addAll(selected);options.addAll(SourceCategories.normalized(all));filter();}
    void filter(){String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);visible.clear();for(String label:options)if(label.toLowerCase(Locale.ROOT).contains(query))visible.add(label);adapter.notifyDataSetChanged();}
    void renderChips(){
      chips.removeAllViews();
      if(selected.isEmpty()){TextView empty=owner.text("未限定分类",12,owner.MUTED);chips.addView(empty);return;}
      for(String label:new ArrayList<>(selected)){
        TextView chip=owner.sourceTag(label);chip.setTextSize(12);chip.setContentDescription(label+"，长按删除或更换");
        chip.setOnLongClickListener(v->{AlertDialog actions=new AlertDialog.Builder(owner).setTitle(label).setItems(new String[]{"删除标签","更换标签"},(d,index)->{
          if(index==0){selected.remove(label);renderChips();adapter.notifyDataSetChanged();}
          else replace(label);
        }).create();owner.showRounded(actions);return true;});
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,owner.dp(32));params.rightMargin=owner.dp(6);chips.addView(chip,params);
      }
    }
    void replace(String previous){
      EditText input=owner.sourceInput("更换为分类名称",android.text.InputType.TYPE_CLASS_TEXT);input.setText(previous);input.setSelectAllOnFocus(true);
      LinearLayout panel=new LinearLayout(owner);panel.setPadding(owner.dp(20),0,owner.dp(20),0);panel.addView(input,new LinearLayout.LayoutParams(-1,owner.dp(52)));
      AlertDialog edit=new AlertDialog.Builder(owner).setTitle("更换分类").setView(panel).setNegativeButton("取消",null).setPositiveButton("更换",null).create();
      edit.setOnShowListener(d->edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=SourceCategories.name(input.getText().toString());if(value.isEmpty()){input.setError("请输入分类名称");return;}selected.remove(previous);selected.add(value);CategoryEditor.rememberShortcut(owner,value);refreshOptions();renderChips();edit.dismiss();}));owner.showRounded(edit);
    }
    void directory(Models.Source source,String nodeId,List<String> ancestors){
      LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(18),owner.dp(4),owner.dp(18),0);
      List<String> available=new ArrayList<>();List<DirectoryEntry> entries=new ArrayList<>();
      if(source==null){
        available.addAll(collect(known));
        for(Models.Source value:known)if(value.repositoryUrl.isEmpty())entries.add(new DirectoryEntry(value,"",value.title));
      }else if(source.kind==Models.SOURCE_REPOSITORY){
        available.addAll(labels(source));for(Models.Source value:known)if(source.url.equals(value.repositoryUrl)){available.addAll(labels(value));entries.add(new DirectoryEntry(value,"",value.title));}
      }else if(source.kind==Models.SOURCE_COMPOSITE){
        if(nodeId.isEmpty())available.addAll(labels(source));
        for(Models.SourceMember member:source.members){
          if(member.id.equals(nodeId))available.addAll(labels(member));
          if(member.parentId.equals(nodeId)){available.addAll(labels(member));if(member.kind==Models.MEMBER_FOLDER)entries.add(new DirectoryEntry(source,member.id,member.title));}
        }
      }else available.addAll(labels(source));
      String heading=ancestors.isEmpty()?"本地源目录":String.join(" › ",ancestors);
      TextView path=owner.text(heading,13,owner.MUTED);path.setMaxLines(2);panel.addView(path,new LinearLayout.LayoutParams(-1,-2));
      LinearLayout labels=new LinearLayout(owner);labels.setOrientation(LinearLayout.VERTICAL);panel.addView(labels,new LinearLayout.LayoutParams(-1,-2));
      List<String> current=SourceCategories.normalized(available);
      AlertDialog[] page={null};
      if(current.isEmpty())labels.addView(owner.text("此级没有分类，可进入下一级查看",12,owner.MUTED));
      else{
        HorizontalScrollView scroll=new HorizontalScrollView(owner);scroll.setHorizontalScrollBarEnabled(false);LinearLayout tags=new LinearLayout(owner);scroll.addView(tags);labels.addView(scroll,new LinearLayout.LayoutParams(-1,owner.dp(44)));
        for(String label:current){TextView tag=owner.sourceTag(label);tag.setContentDescription("选择分类 "+label);tag.setOnClickListener(v->{selected.add(label);HistoryPopupNavigation.switchCollection(owner,page[0],this::show);});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,owner.dp(34));p.rightMargin=owner.dp(6);tags.addView(tag,p);}
      }
      ListView list=new ListView(owner);list.setDividerHeight(0);
      list.setAdapter(new BaseAdapter(){public int getCount(){return entries.size();}public Object getItem(int p){return entries.get(p);}public long getItemId(int p){return p;}public View getView(int p,View old,ViewGroup parent){TextView row=old instanceof TextView?(TextView)old:owner.text("",14,owner.TEXT);row.setMinHeight(owner.dp(48));row.setGravity(Gravity.CENTER_VERTICAL);row.setText(entries.get(p).title+"  ›");return row;}});
      panel.addView(list,new LinearLayout.LayoutParams(-1,Math.min(owner.dp(230),Math.max(owner.dp(48),owner.safeContentHeight()-owner.dp(240)))));
      page[0]=new AlertDialog.Builder(owner).setTitle("目录中的分类").setView(panel).setNegativeButton(source==null?"返回分类选择":"返回上级",null).setNeutralButton("选择分类",null)
          .setPositiveButton("应用选择",null).create();
      Runnable back=()->{if(source==null){show();return;}List<String> trail=new ArrayList<>(ancestors);if(!trail.isEmpty())trail.remove(trail.size()-1);if(!nodeId.isEmpty()){String parent="";for(Models.SourceMember member:source.members)if(member.id.equals(nodeId)){parent=member.parentId;break;}directory(source,parent,trail);}else if(!source.repositoryUrl.isEmpty()){Models.Source repository=null;for(Models.Source value:known)if(value.kind==Models.SOURCE_REPOSITORY&&value.url.equals(source.repositoryUrl)){repository=value;break;}directory(repository,"",trail);}else directory(null,"",trail);};
      page[0].setOnShowListener(d->{page[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,page[0],()->done.accept(new ArrayList<>(selected))));page[0].getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,page[0],back));page[0].getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->HistoryPopupNavigation.switchCollection(owner,page[0],this::show));});
      page[0].setOnKeyListener((d,key,event)->{if(key!=android.view.KeyEvent.KEYCODE_BACK)return false;if(event.getAction()==android.view.KeyEvent.ACTION_UP)HistoryPopupNavigation.switchCollection(owner,page[0],back);return true;});
      list.setOnItemClickListener((parent,view,position,id)->{DirectoryEntry entry=entries.get(position);List<String> trail=new ArrayList<>(ancestors);trail.add(entry.title);HistoryPopupNavigation.switchCollection(owner,page[0],()->directory(entry.source,entry.nodeId,trail));});
      owner.showRounded(page[0]);
    }
  }
  private static final class DirectoryEntry {
    final Models.Source source;final String nodeId,title;
    DirectoryEntry(Models.Source source,String nodeId,String title){this.source=source;this.nodeId=nodeId;this.title=title;}
  }
}
