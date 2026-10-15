package cc.nkbr.lanzouplus;

import java.util.*;
import org.json.*;

/** One selected category intersected with the union of selected paths. Never scans remote folders. */
final class HomeSearchScope {
  final MainActivity owner;
  final List<SourceDestinationPopup.Destination> paths=new ArrayList<>();
  String category="";
  HomeSearchScope(MainActivity owner){this.owner=owner;}
  void choose(){owner.io.execute(()->{try{List<Models.Source> sources=owner.core.knownCategorySources();owner.runOnUiThread(()->SourceDestinationPopup.select(owner,new SourcePathCatalog(owner,sources),selected->{paths.clear();category="";for(SourceDestinationPopup.Destination destination:selected){if(destination.categoryOnly()){if(!"全部".equals(destination.category))category=destination.category;}else paths.add(destination);}owner.sessionSearchCategory="全部";owner.sessionHomeSourceCategory=category.isEmpty()?"全部":category;refreshLabel();}));}catch(Exception error){owner.runOnUiThread(()->owner.showNotice(owner.friendlyError(error),true));}});}
  void refreshLabel(){if(owner.homeCategoryPicker!=null){String label=paths.isEmpty()?"全部路径":paths.size()==1?paths.get(0).name():paths.size()+" 个路径";if(!category.isEmpty())label+=" · "+category;owner.homeCategoryPicker.value.setText(label);owner.homeCategoryPicker.trigger.setContentDescription("搜索路径："+label+"，点击更改一个或多个路径");}}
  List<Models.Source> sources(){List<Models.Source> out=new ArrayList<>();for(SourceDestinationPopup.Destination path:paths){Models.Source copy=new Models.Source();MainActivity.copySource(path.composite,copy);copy.nodeId=path.nodeId;out.add(copy);}return out;}
  List<Models.Item> cachedItems(String query)throws Exception{
    Set<String> allowed=ids();List<Models.Item> cached=owner.core.cachedPartialIndexMatches(query,allowed,owner.fuzzyIndexEnabled());
    List<Models.Item> out=new ArrayList<>();Set<String> local=new HashSet<>(),remote=new HashSet<>();
    for(Models.Source source:sources())if(source.kind==Models.SOURCE_COMPOSITE){for(Models.Item item:owner.core.collectLocalPathItems(source))local.add(item.url);}else if(source.kind==Models.SOURCE_REPOSITORY){for(Models.Source child:owner.core.cachedRepositorySources(source))remote.add(LanzouCore.sourceId(child));}else remote.add(LanzouCore.sourceId(source));
    for(Models.Item item:cached)if(local.contains(item.url)||remote.contains(item.sourceId))out.add(item);return out;
  }
  Set<String> ids()throws Exception{
    if(paths.isEmpty()&&category.isEmpty())return null;
    LinkedHashSet<String> ids=new LinkedHashSet<>();for(Models.Source source:owner.core.knownCategorySources())if((category.isEmpty()||SourceCategories.matches(source.categories,Collections.singletonList(category)))&&inPaths(source))ids.add(LanzouCore.sourceId(source));
    if(category.isEmpty())for(Models.Source source:sources())ids.add(LanzouCore.sourceId(source));return ids;
  }
  boolean inPaths(Models.Source source){if(paths.isEmpty())return true;for(SourceDestinationPopup.Destination path:paths){if(LanzouCore.sourceId(path.composite).equals(LanzouCore.sourceId(source)))return true;if(path.composite.kind==Models.SOURCE_REPOSITORY&&path.composite.url.equals(source.repositoryUrl))return true;}return false;}
  private boolean wholeSourceInPaths(Models.Source source){if(paths.isEmpty())return true;for(SourceDestinationPopup.Destination path:paths)if(path.nodeId.isEmpty()&&(LanzouCore.sourceId(path.composite).equals(LanzouCore.sourceId(source))||path.composite.kind==Models.SOURCE_REPOSITORY&&path.composite.url.equals(source.repositoryUrl)))return true;return false;}
  /** A category entry matches the source node's title, description and its own labels. */
  private static String categoryMatchText(String title,String description,List<String> categories){StringBuilder out=new StringBuilder(title==null?"":title).append(' ').append(description==null?"":description);for(String value:categories)out.append(' ').append(value);return out.toString().toLowerCase(Locale.ROOT);}
  List<Models.Item> categoryItems(String query)throws Exception{
    List<Models.Item> out=new ArrayList<>();String needle=query.toLowerCase(Locale.ROOT);
    for(Models.Source source:owner.core.knownCategorySources()){
      if(!inPaths(source))continue;
      if(wholeSourceInPaths(source)&&SourceCategories.matches(source.categories,Collections.singletonList(category))&&owner.folderMatchRank(categoryMatchText(source.title,source.description,source.categories),needle,owner.sessionSearchFuzzyMatching)>=0){Models.Item item=new Models.Item();item.title=source.title;item.url=source.url.isEmpty()?source.id:source.url;item.shareUrl=source.url;item.source=source.publisher;item.sourceId=LanzouCore.sourceId(source);item.sourceEntry=true;item.folder=source.kind==Models.SOURCE_OFFICIAL||source.kind==Models.SOURCE_COMPOSITE||source.kind==Models.SOURCE_REPOSITORY;item.iconUrl=source.iconUrl.isEmpty()?source.avatarUrl:source.iconUrl;item.categories.addAll(source.categories);out.add(item);}
      if(source.kind==Models.SOURCE_COMPOSITE)for(Models.SourceMember member:source.members){if(member.kind==Models.MEMBER_FOLDER||!SourceCategories.matches(member.categories,Collections.singletonList(category)))continue;if(owner.folderMatchRank(categoryMatchText(member.title,member.description,member.categories),needle,owner.sessionSearchFuzzyMatching)<0)continue;if(!memberInPaths(source,member))continue;Models.Item item=new Models.Item();item.title=member.title;item.url=item.shareUrl=member.url;item.password=member.password;item.iconUrl=member.iconUrl;item.source=source.title;item.sourceId=LanzouCore.sourceId(source);item.sourceEntry=true;item.folder=member.kind!=Models.MEMBER_FILE;item.folderId=member.kind==Models.MEMBER_REMOTE_FOLDER?"remote:"+member.id:member.id;item.categories.addAll(member.categories);out.add(item);}
    }return out;
  }
  private boolean memberInPaths(Models.Source source,Models.SourceMember member){if(paths.isEmpty())return true;Map<String,String> parents=new HashMap<>();for(Models.SourceMember value:source.members)parents.put(value.id,value.parentId);for(SourceDestinationPopup.Destination path:paths){if(!LanzouCore.sourceId(source).equals(LanzouCore.sourceId(path.composite)))continue;if(path.nodeId.isEmpty())return true;String id=member.parentId;Set<String> seen=new HashSet<>();while(!id.isEmpty()&&seen.add(id)){if(path.nodeId.equals(id))return true;id=parents.getOrDefault(id,"");}}return false;}
}
