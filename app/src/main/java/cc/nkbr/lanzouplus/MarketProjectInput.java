package cc.nkbr.lanzouplus;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Both editor input modes produce the same standard source document. */
final class MarketProjectInput {
  interface Resolver { List<Models.Source> resolve(String link)throws Exception; }
  static List<String> links(String raw){
    LinkedHashSet<String> values=new LinkedHashSet<>();
    for(String line:raw.split("\\r?\\n")){String value=line.trim();if(!value.isEmpty())values.add(value);}
    return new ArrayList<>(values);
  }
  static String resolve(String raw,Resolver resolver)throws Exception{
    List<String> links=links(raw);if(links.isEmpty())throw new IOException("请输入打包源链接");
    LinkedHashMap<String,Models.Source> values=new LinkedHashMap<>();
    for(String link:links)for(Models.Source source:resolver.resolve(link))values.putIfAbsent(LanzouCore.sourceId(source),source);
    if(values.isEmpty())throw new IOException("链接中没有可打包的源");
    return LanzouCore.sourceDocument(values.values()).toString();
  }
  static String savedLinks(MarketProject project){
    List<String> links=new ArrayList<>();if(project==null)return "";
    for(int i=0;i<project.sourcePaths.length();i++)try{
      String link=new JSONObject(project.sourcePaths.getString(i)).optString("link");if(!link.isEmpty())links.add(link);
    }catch(JSONException ignored){}
    return String.join("\n",links);
  }
}
