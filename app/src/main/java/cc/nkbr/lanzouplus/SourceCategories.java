package cc.nkbr.lanzouplus;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** The shared category contract used by source storage, export and the market. */
final class SourceCategories {
  static final String LANZOU="蓝奏云源",OTHER="非蓝奏云源";
  private static final Pattern SPACE=Pattern.compile("[\\p{Z}\\p{javaWhitespace}]+");

  static String name(String value){
    return SPACE.matcher(Normalizer.normalize(value,Normalizer.Form.NFKC)).replaceAll(" ").trim();
  }

  static List<String> normalized(Iterable<String> values){
    LinkedHashMap<String,String> names=new LinkedHashMap<>();
    for(String value:values){
      String label=name(value);
      if(!label.isEmpty())names.putIfAbsent(label.toLowerCase(Locale.ROOT),label);
    }
    return new ArrayList<>(names.values());
  }

  static boolean builtIn(String value){String label=name(value);return LANZOU.equals(label)||OTHER.equals(label);}

  static void replace(List<String> target,Iterable<String> values){
    List<String> labels=normalized(values);
    target.clear();target.addAll(labels);
  }

  static void automatic(List<String> target,int types){
    List<String> labels=normalized(target);
    labels.removeIf(label->LANZOU.equals(label)&&(types&1)==0||OTHER.equals(label)&&(types&2)==0);
    if((types&1)!=0&&!labels.contains(LANZOU))labels.add(LANZOU);
    if((types&2)!=0&&!labels.contains(OTHER))labels.add(OTHER);
    target.clear();target.addAll(labels);
  }

  static List<String> read(JSONObject value)throws JSONException{
    List<String> labels=new ArrayList<>();
    if(!value.has("categories"))return labels;
    JSONArray values=value.getJSONArray("categories");
    for(int i=0;i<values.length();i++){
      Object label=values.get(i);
      if(!(label instanceof String))throw new JSONException("分类必须是字符串数组");
      labels.add((String)label);
    }
    return normalized(labels);
  }

  static boolean matches(Iterable<String> values,Collection<String> selected){
    List<String> filters=normalized(selected);
    if(filters.isEmpty())return true;
    for(String value:normalized(values))for(String filter:filters)if(value.equalsIgnoreCase(filter))return true;
    return false;
  }

  static List<Models.Source> filterSources(Collection<Models.Source> sources,Collection<String> selected){
    List<Models.Source> out=new ArrayList<>();
    for(Models.Source source:sources)if(matches(source.categories,selected))out.add(source);
    return out;
  }

  static List<Models.SourceMember> filterMembers(Collection<Models.SourceMember> members,Collection<String> selected){
    List<Models.SourceMember> out=new ArrayList<>();
    for(Models.SourceMember member:members)if(matches(member.categories,selected))out.add(member);
    return out;
  }

  static List<String> collect(Collection<Models.Source> sources){
    List<String> labels=new ArrayList<>();
    for(Models.Source source:sources){labels.addAll(source.categories);for(Models.SourceMember member:source.members)labels.addAll(member.categories);}
    return normalized(labels);
  }
}
