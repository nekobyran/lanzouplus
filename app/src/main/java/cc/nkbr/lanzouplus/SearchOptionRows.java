package cc.nkbr.lanzouplus;

import android.view.Gravity;
import android.widget.*;

/** Paired selectors share one value; each end changes the same row's selection. */
final class SearchOptionRows {
  static final String[] FUZZY={"关闭","紧凑","标准","宽松"};
  static LinearLayout create(MainActivity owner,int[] values,Runnable changed){
    LinearLayout panel=new LinearLayout(owner);panel.setOrientation(LinearLayout.VERTICAL);
    panel.addView(row(owner,"模糊匹配",FUZZY,values,0,changed));
    panel.addView(row(owner,"递归搜索",new String[]{"关闭","开启"},values,1,changed));
    panel.setTag("search-option-rows");
    return panel;
  }
  private static LinearLayout row(MainActivity owner,String title,String[] choices,int[] values,int slot,Runnable changed){
    LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);
    Button left=owner.toolbarTextButton("‹"),right=owner.toolbarTextButton("›");
    TextView value=owner.text("",13,owner.TEXT);value.setGravity(Gravity.CENTER);
    Runnable render=()->{
      value.setText(title+" · "+choices[values[slot]]);
      left.setContentDescription(title+"，上一个选项，当前 "+choices[values[slot]]);
      right.setContentDescription(title+"，下一个选项，当前 "+choices[values[slot]]);
    };
    left.setOnClickListener(v->{values[slot]=(values[slot]+choices.length-1)%choices.length;render.run();changed.run();});
    right.setOnClickListener(v->{values[slot]=(values[slot]+1)%choices.length;render.run();changed.run();});
    row.addView(left,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(44)));
    row.addView(value,new LinearLayout.LayoutParams(0,owner.dp(44),1));
    row.addView(right,new LinearLayout.LayoutParams(owner.dp(48),owner.dp(44)));render.run();
    return row;
  }
}
