package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** The same tag editor for source nodes, local members, shortcut preferences and the add form. */
final class CategoryEditor {
  interface Save {void save(List<String> values)throws Exception;}

  /**
   * One live tag list: built-in automatic labels stay, only user labels are editable, and every
   * mutation happens on the UI thread. A save snapshots the labels on that thread before any
   * background work, so the persisted set can never race the editor.
   */
  static final class Tags {
    final MainActivity owner;
    final List<String> labels=new ArrayList<>();
    private final LinearLayout panel,rows;
    private final EditText input;

    Tags(MainActivity owner,Collection<String> initial,boolean offerShortcuts){
      this.owner=owner;labels.addAll(SourceCategories.normalized(initial));
      panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(owner.dp(20),owner.dp(8),owner.dp(20),owner.dp(8));
      rows=new LinearLayout(owner);rows.setGravity(Gravity.CENTER_VERTICAL);HorizontalScrollView tagScroll=new HorizontalScrollView(owner);tagScroll.setHorizontalScrollBarEnabled(false);tagScroll.addView(rows);panel.addView(tagScroll,new LinearLayout.LayoutParams(-1,owner.dp(44)));
      input=owner.sourceInput("输入分类，点击加号保存",android.text.InputType.TYPE_CLASS_TEXT);
      LinearLayout editor=new LinearLayout(owner);editor.setGravity(Gravity.CENTER_VERTICAL);editor.addView(input,new LinearLayout.LayoutParams(0,owner.dp(50),1));
      ImageButton add=owner.iconButton(R.drawable.ic_add,"添加分类");editor.addView(add,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));panel.addView(editor);
      Runnable[] render={null};
      render[0]=()->{rows.removeAllViews();for(String label:new ArrayList<>(labels)){
        TextView chip=owner.sourceTag(label);chip.setTextSize(13);chip.setPadding(owner.dp(12),owner.dp(6),owner.dp(12),owner.dp(6));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,owner.dp(38));params.rightMargin=owner.dp(5);rows.addView(chip,params);
        chip.setContentDescription("分类 "+label+(SourceCategories.builtIn(label)?"，自动分类":"，长按编辑或删除"));
        if(!SourceCategories.builtIn(label))chip.setOnLongClickListener(v->{AlertDialog choices=new AlertDialog.Builder(owner).setTitle(label).setItems(new String[]{"编辑","删除"},(dialog,index)->{if(index==1){labels.remove(label);render[0].run();}else{
          EditText replacement=owner.sourceInput("分类名称",android.text.InputType.TYPE_CLASS_TEXT);replacement.setText(label);
          AlertDialog edit=new AlertDialog.Builder(owner).setTitle("更换分类").setView(replacement).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
          edit.setOnShowListener(ignored->edit.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String name=SourceCategories.name(replacement.getText().toString());if(name.isEmpty()){replacement.setError("请输入分类名称");return;}labels.set(labels.indexOf(label),name);SourceCategories.replace(labels,labels);rememberShortcut(owner,name);render[0].run();edit.dismiss();}));owner.showRounded(edit);
        }}).create();owner.showRounded(choices);return true;});
      }};
      render[0].run();
      add.setOnClickListener(v->{String label=SourceCategories.name(input.getText().toString());if(!label.isEmpty()){rememberShortcut(owner,label);labels.add(label);SourceCategories.replace(labels,labels);input.setText("");render[0].run();}});
      if(offerShortcuts){HorizontalScrollView scroll=new HorizontalScrollView(owner);scroll.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(owner);for(String value:getShortcuts(owner)){Button shortcut=owner.toolbarTextButton(value);shortcut.setOnClickListener(v->{if(!labels.contains(value)){labels.add(value);render[0].run();}});row.addView(shortcut);}scroll.addView(row);panel.addView(scroll);}
    }

    View view(){return panel;}
    /** Called on the UI thread: the label set a save must use. */
    List<String> snapshot(){return new ArrayList<>(labels);}
  }

  static Tags tagEditor(MainActivity owner,Collection<String> initial,boolean offerShortcuts){return new Tags(owner,initial,offerShortcuts);}

  static List<String> getShortcuts(MainActivity owner){
    List<String> out=new ArrayList<>(Arrays.asList(SourceCategories.LANZOU,SourceCategories.OTHER));
    try{JSONArray rows=new JSONArray(owner.getSharedPreferences("market",Context.MODE_PRIVATE).getString("shortcuts","[]"));for(int i=0;i<rows.length();i++)out.add(rows.getString(i));}catch(JSONException ignored){}
    return SourceCategories.normalized(out);
  }
  static void rememberShortcut(MainActivity owner,String raw){
    String label=SourceCategories.name(raw);if(label.isEmpty())return;
    List<String> values=getShortcuts(owner);values.add(label);
    owner.getSharedPreferences("market",Context.MODE_PRIVATE).edit().putString("shortcuts",new JSONArray(SourceCategories.normalized(values)).toString()).apply();
  }
  static void shortcuts(MainActivity owner){show(owner,"快捷分类",getShortcuts(owner),false,values->owner.getSharedPreferences("market",Context.MODE_PRIVATE).edit().putString("shortcuts",new JSONArray(values).toString()).apply());}
  static void source(MainActivity owner,Models.Source source){
    String rawId=LanzouCore.sourceId(source),repositoryUrl=source.repositoryUrl;
    show(owner,"分类 · "+source.title,source.categories,true,values->{
      Models.Source updated=owner.core.updateSourceCategories(rawId,repositoryUrl,values);
      owner.runOnUiThread(()->{MainActivity.copySource(updated,source);owner.sourceDataRevision++;if(owner.sourcePageSources!=null){owner.rebuildCategoryMemberships(owner.sourcePageSources);owner.renderSourceCategories(owner.sourcePageSources);if(owner.sourceGrid!=null)owner.renderSources(owner.sourceGrid,owner.sourcePageSources,owner.sourceFilter==null?"":owner.sourceFilter.getText().toString(),owner.sourceHeading);}});
    });
  }
  static void member(MainActivity owner,Models.Source source,Models.Item item){
    Models.SourceMember found=null;for(Models.SourceMember member:source.members)if(member.id.equals(item.folderId)||!item.url.isEmpty()&&member.url.equals(item.url)){found=member;break;}
    if(found==null)return;
    String rawId=LanzouCore.sourceId(source),repositoryUrl=source.repositoryUrl,memberId=found.id;
    show(owner,"分类 · "+item.title,found.categories,true,values->{
      Models.Source updated=owner.core.updateMemberCategories(rawId,repositoryUrl,memberId,values);
      owner.runOnUiThread(()->{MainActivity.copySource(updated,source);if(owner.pageKind==1&&owner.inlineSourcePage!=null&&owner.inlineSourcePage.collection())owner.inlineSourcePage.refreshCurrent();else owner.applyLocalSourceSearch(owner.currentSourceQuery);});
    });
  }

  private static void show(MainActivity owner,String title,Collection<String> initial,boolean offerShortcuts,Save save){
    Tags tags=new Tags(owner,initial,offerShortcuts);
    AlertDialog prompt=new AlertDialog.Builder(owner).setTitle(title).setView(owner.limitedDialogScroll(tags.view(),420)).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
    prompt.setOnShowListener(dialog->prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      Button button=prompt.getButton(AlertDialog.BUTTON_POSITIVE);button.setEnabled(false);List<String> snapshot=tags.snapshot();
      owner.io.execute(()->{try{save.save(snapshot);owner.runOnUiThread(prompt::dismiss);}catch(Exception error){owner.runOnUiThread(()->{button.setEnabled(true);owner.showNotice(owner.friendlyError(error),true);});}});
    }));
    owner.showRounded(prompt);
  }
}
