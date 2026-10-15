package cc.nkbr.lanzouplus;

import android.view.*;
import android.widget.*;
import java.util.*;

/** Source tags in local collection lists; remote Lanzou file lists have no category bar. */
final class FolderCategoryBar {
  static void install(MainActivity owner,Models.Source source){
    owner.folderCategorySelection="";if(!MainActivity.compositeSource(source))return;
    LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);HorizontalScrollView scroll=new HorizontalScrollView(owner);scroll.setHorizontalScrollBarEnabled(false);LinearLayout strip=new LinearLayout(owner);scroll.addView(strip);row.addView(scroll,new LinearLayout.LayoutParams(0,owner.dp(44),1));
    ImageButton edit=owner.iconButton(R.drawable.ic_add,"编辑项目分类");edit.setOnClickListener(v->{List<Models.Item> selected=owner.selectedItems();if(selected.size()==1)CategoryEditor.member(owner,source,selected.get(0));else CategoryEditor.source(owner,source);});row.addView(edit,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(44)));
    owner.root.addView(row,Math.max(0,owner.root.getChildCount()-1),new LinearLayout.LayoutParams(-1,owner.dp(44)));
    List<String> labels=new ArrayList<>();for(Models.SourceMember member:source.members)labels.addAll(member.categories);labels=SourceCategories.normalized(labels);labels.add(0,"全部");final List<String> choices=labels;Runnable[] render={null};
    render[0]=()->{strip.removeAllViews();for(String label:choices){boolean selected=label.equals(owner.folderCategorySelection)||label.equals("全部")&&owner.folderCategorySelection.isEmpty();View tab=owner.underlineFilter(label,selected,()->{owner.folderCategorySelection=label.equals("全部")?"":label;owner.folderSearchPreviousQuery="";owner.queueCurrentSourceSearch(owner.currentSourceQuery);render[0].run();});strip.addView(tab,new LinearLayout.LayoutParams(-2,owner.dp(42)));}};render[0].run();
  }
}
