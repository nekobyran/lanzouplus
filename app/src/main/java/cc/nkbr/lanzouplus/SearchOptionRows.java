package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.widget.*;

/** Per-scope fuzzy and recursive switches, aligned with the search backend matrix. */
final class SearchOptionRows {
  static final String[] FUZZY={"关闭","紧凑","标准","宽松"};
  static final int FILE_FUZZY=0,FILE_RECURSIVE=1,SEARCH_FUZZY=2,SEARCH_RECURSIVE=3;
  private static final int FILE_COLUMN_DP=104,SEARCH_COLUMN_DP=86,ROW_HEIGHT_DP=46;

  static LinearLayout create(MainActivity owner,int[] values,Runnable changed){
    LinearLayout panel=new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    CheckBox[] fileBox={null},searchBox={null};
    TextView[] levelView={null,null};
    int[] remembered={
      values[FILE_FUZZY]>0?normalizeLevel(values[FILE_FUZZY]):2,
      values[SEARCH_FUZZY]>0?normalizeLevel(values[SEARCH_FUZZY]):2
    };
    Runnable render=()->{
      if(fileBox[0]!=null)fileBox[0].setChecked(values[FILE_FUZZY]>0);
      if(searchBox[0]!=null)searchBox[0].setChecked(values[SEARCH_FUZZY]>0);
      if(levelView[0]!=null)renderLevel(levelView[0],"文件列表页模糊匹配档位",values[FILE_FUZZY]);
      if(levelView[1]!=null)renderLevel(levelView[1],"搜索模糊匹配档位",values[SEARCH_FUZZY]);
    };
    fileBox[0]=checkBox(owner,"文件列表页模糊匹配");
    searchBox[0]=checkBox(owner,"搜索模糊匹配");
    panel.addView(toggleRow(owner,"模糊匹配",fileBox[0],searchBox[0]),new LinearLayout.LayoutParams(-1,owner.dp(ROW_HEIGHT_DP)));
    CheckBox fileRecursive=checkBox(owner,"文件列表页递归"),searchRecursive=checkBox(owner,"搜索递归");
    fileRecursive.setChecked(values[FILE_RECURSIVE]>0);
    searchRecursive.setChecked(values[SEARCH_RECURSIVE]>0);
    panel.addView(toggleRow(owner,"递归搜索",fileRecursive,searchRecursive),new LinearLayout.LayoutParams(-1,owner.dp(ROW_HEIGHT_DP)));
    levelView[0]=levelValue(owner);
    levelView[1]=levelValue(owner);
    panel.addView(levelRow(owner,levelView[0],levelView[1]),new LinearLayout.LayoutParams(-1,owner.dp(ROW_HEIGHT_DP)));
    fileBox[0].setOnCheckedChangeListener((button,checked)->{
      if(checked)values[FILE_FUZZY]=remembered[0];
      else{
        if(values[FILE_FUZZY]>0)remembered[0]=normalizeLevel(values[FILE_FUZZY]);
        values[FILE_FUZZY]=0;
      }
      render.run();
      changed.run();
    });
    searchBox[0].setOnCheckedChangeListener((button,checked)->{
      if(checked)values[SEARCH_FUZZY]=remembered[1];
      else{
        if(values[SEARCH_FUZZY]>0)remembered[1]=normalizeLevel(values[SEARCH_FUZZY]);
        values[SEARCH_FUZZY]=0;
      }
      render.run();
      changed.run();
    });
    fileRecursive.setOnCheckedChangeListener((button,checked)->{
      values[FILE_RECURSIVE]=checked?1:0;
      changed.run();
    });
    searchRecursive.setOnCheckedChangeListener((button,checked)->{
      values[SEARCH_RECURSIVE]=checked?1:0;
      changed.run();
    });
    levelView[0].setOnClickListener(v->showLevelPicker(owner,"文件列表页模糊匹配",fileBox[0],values,remembered,FILE_FUZZY,0,render,changed));
    levelView[1].setOnClickListener(v->showLevelPicker(owner,"搜索模糊匹配",searchBox[0],values,remembered,SEARCH_FUZZY,1,render,changed));
    render.run();
    panel.setTag("search-option-rows");
    return panel;
  }

  private static LinearLayout toggleRow(MainActivity owner,String title,CheckBox file,CheckBox search){
    LinearLayout row=new LinearLayout(owner);
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView label=owner.text(title,15,owner.TEXT);
    label.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    row.addView(label,new LinearLayout.LayoutParams(0,owner.dp(ROW_HEIGHT_DP),1));
    row.addView(file,new LinearLayout.LayoutParams(owner.dp(FILE_COLUMN_DP),owner.dp(ROW_HEIGHT_DP)));
    row.addView(search,new LinearLayout.LayoutParams(owner.dp(SEARCH_COLUMN_DP),owner.dp(ROW_HEIGHT_DP)));
    return row;
  }

  private static LinearLayout levelRow(MainActivity owner,TextView file,TextView search){
    LinearLayout row=new LinearLayout(owner);
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView label=owner.text("匹配档位",15,owner.TEXT);
    label.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    row.addView(label,new LinearLayout.LayoutParams(0,owner.dp(ROW_HEIGHT_DP),1));
    row.addView(file,new LinearLayout.LayoutParams(owner.dp(FILE_COLUMN_DP),owner.dp(ROW_HEIGHT_DP)));
    row.addView(search,new LinearLayout.LayoutParams(owner.dp(SEARCH_COLUMN_DP),owner.dp(ROW_HEIGHT_DP)));
    return row;
  }

  private static CheckBox checkBox(MainActivity owner,String description){
    CheckBox box=new CheckBox(owner);
    box.setGravity(Gravity.CENTER);
    box.setContentDescription(description);
    return box;
  }

  private static TextView levelValue(MainActivity owner){
    TextView value=owner.text("",12,owner.PRIMARY);
    value.setGravity(Gravity.CENTER);
    value.setClickable(true);
    value.setFocusable(true);
    value.setPadding(owner.dp(4),0,owner.dp(4),0);
    value.setBackground(owner.filterRipple(new ColorDrawable(Color.TRANSPARENT)));
    return value;
  }

  private static void showLevelPicker(MainActivity owner,String label,CheckBox box,int[] values,int[] remembered,int slot,int rememberedSlot,Runnable render,Runnable changed){
    int current=normalizeLevel(values[slot]);
    AlertDialog dialog=new AlertDialog.Builder(owner)
        .setTitle(label)
        .setSingleChoiceItems(FUZZY,current,(prompt,which)->{
          values[slot]=which;
          if(which>0)remembered[rememberedSlot]=which;
          box.setChecked(which>0);
          render.run();
          changed.run();
          prompt.dismiss();
        })
        .setNegativeButton("取消",null)
        .create();
    owner.showRounded(dialog);
  }

  private static void renderLevel(TextView view,String label,int level){
    int normalized=normalizeLevel(level);
    view.setText(FUZZY[normalized]);
    view.setContentDescription(label+"，当前 "+FUZZY[normalized]+"，点击选择");
  }

  private static int normalizeLevel(int level){
    return Math.max(0,Math.min(3,level));
  }
}
