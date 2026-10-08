package cc.nkbr.lanzouplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** One shared history/favorites surface, independent of the host Activity. */
final class SharedBrowserPopup {
  private static final String PREFS="web-browser-v2";
  private static final String HISTORY="history",FAVORITES="favorites";

  private static final class Entry {
    final String title,url;
    final long at;
    Entry(String title,String url,long at){this.title=title;this.url=url;this.at=at;}
  }

  static void show(Activity host,boolean favoritesMode,Consumer<String> openUrl){
    SharedPreferences prefs=host.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    String key=favoritesMode?FAVORITES:HISTORY;
    List<Entry> entries=read(prefs.getString(key,"[]"));
    if(entries.isEmpty()){
      android.widget.Toast.makeText(host,favoritesMode?"暂无网页收藏":"暂无浏览历史",android.widget.Toast.LENGTH_SHORT).show();
      return;
    }
    int accent=themeColor(host,android.R.attr.colorAccent,0xff3576c9);
    int text=themeColor(host,android.R.attr.textColorPrimary,Color.DKGRAY);
    int surface=themeColor(host,android.R.attr.colorBackground,Color.WHITE);
    int muted=Color.argb(190,Color.red(text),Color.green(text),Color.blue(text));
    Set<String> selected=new LinkedHashSet<>();
    LinearLayout panel=new LinearLayout(host);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(host,12),dp(host,4),dp(host,12),0);
    ScrollView scroll=new ScrollView(host);
    LinearLayout list=new LinearLayout(host);
    list.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(list);
    panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(host,380)));
    LinearLayout actions=new LinearLayout(host);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    TextView primary=button(host,favoritesMode?"打开所选":"加入收藏",accent);
    TextView secondary=button(host,favoritesMode?"移出收藏":"删除",accent);
    LinearLayout.LayoutParams buttonParams=new LinearLayout.LayoutParams(0,dp(host,44),1);
    buttonParams.setMargins(dp(host,4),0,dp(host,4),0);
    actions.addView(primary,buttonParams);
    LinearLayout.LayoutParams secondaryParams=new LinearLayout.LayoutParams(0,dp(host,44),1);
    secondaryParams.setMargins(dp(host,4),0,dp(host,4),0);
    actions.addView(secondary,secondaryParams);
    panel.addView(actions,new LinearLayout.LayoutParams(-1,dp(host,52)));
    AlertDialog dialog=new AlertDialog.Builder(host)
        .setTitle(favoritesMode?"网页收藏":"浏览历史")
        .setView(panel).setNegativeButton("关闭",null).create();

    final Runnable[] refresh={null};
    refresh[0]=()->{
      list.removeAllViews();
      for(Entry entry:new ArrayList<>(entries)){
        LinearLayout row=new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(host,4),dp(host,3),dp(host,4),dp(host,3));
        CheckBox checkbox=new CheckBox(host);
        checkbox.setClickable(false);
        checkbox.setChecked(selected.contains(entry.url));
        checkbox.setVisibility(selected.isEmpty()?View.INVISIBLE:View.VISIBLE);
        row.addView(checkbox,new LinearLayout.LayoutParams(dp(host,42),dp(host,54)));
        LinearLayout words=new LinearLayout(host);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title=label(host,entry.title,14,text);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView url=label(host,entry.url,11,muted);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        words.addView(title,new LinearLayout.LayoutParams(-1,dp(host,28)));
        words.addView(url,new LinearLayout.LayoutParams(-1,dp(host,26)));
        row.addView(words,new LinearLayout.LayoutParams(0,dp(host,54),1));
        row.setOnClickListener(v->{
          if(selected.isEmpty()){dialog.dismiss();openUrl.accept(entry.url);}
          else{if(!selected.add(entry.url))selected.remove(entry.url);refresh[0].run();}
        });
        row.setOnLongClickListener(v->{selected.add(entry.url);refresh[0].run();return true;});
        list.addView(row,new LinearLayout.LayoutParams(-1,dp(host,62)));
      }
    };
    primary.setOnClickListener(v->{
      if(selected.isEmpty())return;
      if(favoritesMode){
        for(Entry entry:entries)if(selected.contains(entry.url)){
          dialog.dismiss();openUrl.accept(entry.url);return;
        }
      }else{
        List<Entry> saved=read(prefs.getString(FAVORITES,"[]"));
        for(Entry entry:entries)if(selected.contains(entry.url)){
          saved.removeIf(e->e.url.equals(entry.url));
          saved.add(0,entry);
        }
        prefs.edit().putString(FAVORITES,write(saved)).apply();
        selected.clear();refresh[0].run();
      }
    });
    secondary.setOnClickListener(v->{
      entries.removeIf(e->selected.contains(e.url));
      prefs.edit().putString(key,write(entries)).apply();
      selected.clear();refresh[0].run();
    });
    dialog.setOnShowListener(d->{
      Window window=dialog.getWindow();
      if(window!=null){
        GradientDrawable background=new GradientDrawable();
        background.setColor(surface);background.setCornerRadius(dp(host,20));
        window.setBackgroundDrawable(background);
      }
      refresh[0].run();
    });
    dialog.setOnDismissListener(d->{
      if(host instanceof LanzouWebActivity)((LanzouWebActivity)host).loadState();
    });
    dialog.show();
  }

  private static List<Entry> read(String raw){
    List<Entry> entries=new ArrayList<>();
    try{
      JSONArray array=new JSONArray(raw);
      for(int i=0;i<array.length();i++){
        JSONObject item=array.optJSONObject(i);
        if(item==null)continue;
        String url=item.optString("u","");
        if(!url.isEmpty())entries.add(new Entry(item.optString("t",url),url,item.optLong("a")));
      }
    }catch(org.json.JSONException ignored){}
    return entries;
  }
  private static String write(List<Entry> entries){
    JSONArray output=new JSONArray();
    for(Entry entry:entries){
      JSONObject item=new JSONObject();
      try{item.put("t",entry.title);item.put("u",entry.url);item.put("a",entry.at);}
      catch(org.json.JSONException ignored){}
      output.put(item);
    }
    return output.toString();
  }
  private static TextView button(Activity host,String title,int accent){
    TextView button=label(host,title,14,accent);
    button.setGravity(Gravity.CENTER);
    GradientDrawable bg=new GradientDrawable();
    bg.setColor(Color.argb(24,Color.red(accent),Color.green(accent),Color.blue(accent)));
    bg.setCornerRadius(dp(host,12));
    button.setBackground(bg);
    button.setClickable(true);button.setFocusable(true);
    return button;
  }
  private static TextView label(Activity host,String title,int size,int color){
    TextView text=new TextView(host);
    text.setText(title);text.setTextSize(size);text.setTextColor(color);
    text.setGravity(Gravity.CENTER_VERTICAL);
    return text;
  }
  private static int themeColor(Activity host,int attribute,int fallback){
    TypedValue result=new TypedValue();
    if(!host.getTheme().resolveAttribute(attribute,result,true))return fallback;
    if(result.resourceId!=0){
      try{return host.getResources().getColor(result.resourceId,host.getTheme());}
      catch(android.content.res.Resources.NotFoundException ignored){return fallback;}
    }
    return result.data;
  }
  private static int dp(Activity host,float value){return (int)(value*host.getResources().getDisplayMetrics().density+.5f);}
}
