package cc.nkbr.lanzouplus;

import android.view.*;
import android.widget.*;
import java.util.*;

/** Selected tags and a category picker for local collections. */
final class FolderCategoryBar {
  static void install(MainActivity owner,Models.Source source){
    if(!MainActivity.compositeSource(source))return;
    LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);HorizontalScrollView scroll=new HorizontalScrollView(owner);scroll.setHorizontalScrollBarEnabled(false);LinearLayout strip=new LinearLayout(owner);scroll.addView(strip);row.addView(scroll,new LinearLayout.LayoutParams(0,owner.dp(44),1));
    ImageButton edit=owner.iconButton(R.drawable.ic_expand,"选择目录分类");row.addView(edit,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(48)));
    owner.root.addView(row,Math.max(0,owner.root.getChildCount()-1),new LinearLayout.LayoutParams(-1,owner.dp(44)));
    Runnable[] render={null};
    render[0]=()->{strip.removeAllViews();if(owner.selectedFolderCategories.isEmpty()){strip.addView(owner.text("全部分类",12,owner.MUTED));return;}for(String label:new ArrayList<>(owner.selectedFolderCategories)){TextView tag=owner.sourceTag(label);tag.setOnLongClickListener(v->{SourceCategoryPicker.editExistingSelection(owner,Collections.singletonList(source),owner.selectedFolderCategories,label,()->{apply(owner);render[0].run();});return true;});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,owner.dp(30));p.rightMargin=owner.dp(6);strip.addView(tag,p);}};
    edit.setOnClickListener(v->SourceCategoryPicker.showExisting(owner,Collections.singletonList(source),owner.selectedFolderCategories,values->{SourceCategories.replace(owner.selectedFolderCategories,values);apply(owner);render[0].run();}));render[0].run();
  }
  private static void apply(MainActivity owner){owner.folderSearchPreviousQuery="";owner.queueCurrentSourceSearch(owner.currentSourceQuery);}
  static boolean matches(MainActivity owner,Models.Source source,Models.Item item){if(owner.selectedFolderCategories.isEmpty())return true;List<String> labels=new ArrayList<>(item.categories);labels.add(item.folder?"子目录":"单软件");return SourceCategories.matches(labels,owner.selectedFolderCategories);}
}
