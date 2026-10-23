package cc.nkbr.lanzouplus;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent single-project shortcuts shown below the home search field. */
final class HomeShortcutStore {
  static final String PREFS="home_shortcuts_v1",KEY="items";
  private static final String NODE_MARKER="#local-node=";

  static final class Entry {
    String id="",rootId="",nodeId="",title="",icon="",url="",password="",repositoryUrl="",originPath="";
    byte kind=Models.SOURCE_OFFICIAL;
    boolean childDirectory;
    int slot=-1;

    static Entry fromSource(Models.Source source){
      if(source==null)return null;
      Entry entry=new Entry();
      entry.id=LanzouCore.sourceId(source);
      entry.rootId=rootId(entry.id);
      entry.nodeId=source.nodeId==null?"":source.nodeId;
      entry.title=first(source.title,source.originPath,source.url);
      entry.icon=icon(source);
      entry.url=source.url==null?"":source.url;
      entry.password=source.password==null?"":source.password;
      entry.repositoryUrl=source.repositoryUrl==null?"":source.repositoryUrl;
      entry.originPath=source.originPath==null?"":source.originPath;
      entry.kind=source.kind;
      entry.childDirectory=source.childDirectory;
      return entry.id.isEmpty()?null:entry;
    }

    Models.Source source(MainActivity owner){
      Models.Source resolved=null;
      try{resolved=owner.core.sourceById(rootId);}catch(Exception ignored){}
      Models.Source source=new Models.Source();
      if(resolved!=null)MainActivity.copySource(resolved,source);
      else{
        source.id=rootId;source.kind=kind;source.title=title;source.url=url;source.password=password;
        source.repositoryUrl=repositoryUrl;source.originPath=originPath;source.childDirectory=childDirectory;
        source.iconUrl=icon;
      }
      if(!nodeId.isEmpty())source.nodeId=nodeId;
      if(source.title.isEmpty())source.title=title;
      if(source.iconUrl.isEmpty())source.iconUrl=icon;
      return source;
    }

    private static String icon(Models.Source source){
      if(source.iconUrl!=null&&!source.iconUrl.isEmpty())return source.iconUrl;
      if(source.avatarUrl!=null&&!source.avatarUrl.isEmpty())return source.avatarUrl;
      for(Models.SourceMember member:source.members)if(member.iconUrl!=null&&!member.iconUrl.isEmpty())return member.iconUrl;
      return "";
    }
  }

  private final SharedPreferences preferences;
  HomeShortcutStore(Context context){preferences=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

  synchronized List<Entry> load(){
    ArrayList<Entry> out=new ArrayList<>();
    try{
      JSONArray values=new JSONArray(preferences.getString(KEY,"[]"));
      for(int i=0;i<values.length();i++){
        JSONObject value=values.optJSONObject(i);
        if(value==null)continue;
        Entry entry=new Entry();
        entry.id=value.optString("id");
        entry.rootId=value.optString("root");
        entry.nodeId=value.optString("node");
        entry.title=value.optString("title");
        entry.icon=value.optString("icon");
        entry.url=value.optString("url");
        entry.password=value.optString("password");
        entry.repositoryUrl=value.optString("repository");
        entry.originPath=value.optString("originPath");
        entry.kind=(byte)value.optInt("kind");
        entry.childDirectory=value.optBoolean("childDirectory");
        entry.slot=value.has("slot")?value.optInt("slot",-1):-1;
        if(!entry.id.isEmpty())out.add(entry);
      }
    }catch(Exception ignored){}
    normalizeSlots(out);
    return out;
  }

  synchronized boolean add(Entry entry){
    if(entry==null||entry.id.isEmpty())return false;
    List<Entry> values=load();
    for(int i=0;i<values.size();i++)if(values.get(i).id.equals(entry.id)){
      if(entry.slot<0)entry.slot=values.get(i).slot;
      values.set(i,entry);persistSorted(values);return true;
    }
    if(entry.slot<0)entry.slot=firstFreeSlot(values,new java.util.HashSet<Integer>());
    values.add(entry);persistSorted(values);return true;
  }

  /** Puts a project into the exact empty cell the user tapped. */
  synchronized boolean addAt(Entry entry,int slot){
    if(entry==null||entry.id.isEmpty()||slot<0)return false;
    List<Entry> values=load();
    for(int i=values.size()-1;i>=0;i--)if(values.get(i).id.equals(entry.id))values.remove(i);
    for(Entry existing:values)if(existing.slot==slot)return false;
    entry.slot=slot;
    values.add(entry);persistSorted(values);return true;
  }

  synchronized boolean replace(int index,Entry entry){
    List<Entry> values=load();
    if(index<0||index>=values.size()||entry==null||entry.id.isEmpty())return false;
    for(int i=0;i<values.size();i++)if(i!=index&&values.get(i).id.equals(entry.id))return false;
    entry.slot=values.get(index).slot;
    values.set(index,entry);persistSorted(values);return true;
  }

  /** Apply one confirmed selection without moving shortcuts outside its edited scope. */
  synchronized void applySelection(List<Entry> selected,java.util.Set<String> editableIds,int anchorSlot){
    java.util.LinkedHashMap<String,Entry> chosen=new java.util.LinkedHashMap<>();
    for(Entry entry:selected)if(entry!=null&&!entry.id.isEmpty())chosen.put(entry.id,entry);
    List<Entry> values=load();
    values.removeIf(entry->editableIds.contains(entry.id)&&!chosen.containsKey(entry.id));
    int next=Math.max(0,anchorSlot);
    for(Entry entry:chosen.values()){
      int retained=-1;
      for(int i=0;i<values.size();i++)if(values.get(i).id.equals(entry.id)){retained=i;break;}
      if(retained>=0){entry.slot=values.get(retained).slot;values.set(retained,entry);}
      else{while(occupied(values,next))next++;entry.slot=next++;values.add(entry);}
    }
    persistSorted(values);
  }

  synchronized void remove(int index){
    List<Entry> values=load();
    if(index<0||index>=values.size())return;
    values.remove(index);persistSorted(values);
  }

  synchronized boolean move(int fromIndex,int toSlot){
    List<Entry> values=load();
    if(fromIndex<0||fromIndex>=values.size()||toSlot<0)return false;
    for(int i=0;i<values.size();i++)if(i!=fromIndex&&values.get(i).slot==toSlot)return false;
    values.get(fromIndex).slot=toSlot;persistSorted(values);return true;
  }

  private void persistSorted(List<Entry> values){
    normalizeSlots(values);
    values.sort((left,right)->Integer.compare(left.slot,right.slot));
    JSONArray encoded=new JSONArray();
    for(Entry entry:values){
      try{
        encoded.put(new JSONObject().put("id",entry.id).put("root",entry.rootId).put("node",entry.nodeId)
            .put("title",entry.title).put("icon",entry.icon).put("url",entry.url).put("password",entry.password)
            .put("repository",entry.repositoryUrl).put("originPath",entry.originPath).put("kind",entry.kind)
            .put("childDirectory",entry.childDirectory).put("slot",entry.slot));
      }catch(Exception ignored){}
    }
    preferences.edit().putString(KEY,encoded.toString()).apply();
  }

  private static void normalizeSlots(List<Entry> values){
    java.util.HashSet<Integer> used=new java.util.HashSet<>();
    for(Entry entry:values){
      if(entry.slot>=0&&used.add(entry.slot))continue;
      entry.slot=firstFreeSlot(values,used);
      used.add(entry.slot);
    }
    values.sort((left,right)->Integer.compare(left.slot,right.slot));
  }

  private static int firstFreeSlot(List<Entry> values,java.util.Set<Integer> used){
    int slot=0;
    while(used.contains(slot)||occupied(values,slot))slot++;
    return slot;
  }

  private static boolean occupied(List<Entry> values,int slot){
    for(Entry entry:values)if(entry.slot==slot)return true;
    return false;
  }

  static String rootId(String id){
    int marker=id==null?-1:id.indexOf(NODE_MARKER);
    return marker<0?(id==null?"":id):id.substring(0,marker);
  }

  private static String first(String... values){
    for(String value:values)if(value!=null&&!value.trim().isEmpty())return value.trim();
    return "";
  }
}
