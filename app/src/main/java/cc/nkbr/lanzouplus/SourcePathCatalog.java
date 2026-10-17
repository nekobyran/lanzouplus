package cc.nkbr.lanzouplus;

import android.view.View;
import java.util.*;

/** Read-only mode of the existing destination picker, also used by project packaging. */
final class SourcePathCatalog implements SourceDestinationPopup.Catalog {
  static List<Models.Source> selectedSources(LanzouCore core,List<SourceDestinationPopup.Destination> paths)throws Exception{
    LinkedHashMap<String,Models.Source> selected=new LinkedHashMap<>();List<Models.Source> known=core.knownCategorySources();
    for(SourceDestinationPopup.Destination path:paths){
      List<Models.Source> values=new ArrayList<>();
      if(path.categoryOnly()){for(Models.Source source:known)if("全部".equals(path.category)||SourceCategories.matches(source.categories,Collections.singletonList(path.category)))values.add(source);}
      else if(path.composite.kind==Models.SOURCE_REPOSITORY)values.addAll(core.repositorySources(path.composite,false));else values.add(path.composite);
      for(Models.Source source:values){Models.Source copy=new Models.Source();MainActivity.copySource(source,copy);copy.nodeId="";
        if(!path.nodeId.isEmpty()&&copy.kind==Models.SOURCE_COMPOSITE){Set<String> ids=new HashSet<>();ids.add(path.nodeId);boolean changed;do{changed=false;for(Models.SourceMember member:copy.members)if(ids.contains(member.parentId)&&ids.add(member.id))changed=true;}while(changed);copy.members.removeIf(member->!ids.contains(member.id));for(Models.SourceMember member:copy.members)if(member.id.equals(path.nodeId))member.parentId="";}
        String id=LanzouCore.sourceId(copy);Models.Source old=selected.get(id);if(old==null)selected.put(id,copy);else if(old.kind==Models.SOURCE_COMPOSITE){Set<String> members=new HashSet<>();for(Models.SourceMember member:old.members)members.add(member.id);for(Models.SourceMember member:copy.members)if(members.add(member.id))old.members.add(member);}
      }
    }return new ArrayList<>(selected.values());
  }
  private final MainActivity owner;
  private final List<Models.Source> values;
  private final List<SourceDestinationPopup.Destination> initial;
  private final Map<String,Models.Source> resolved=new java.util.concurrent.ConcurrentHashMap<>();
  SourcePathCatalog(MainActivity owner,List<Models.Source> values){this(owner,values,Collections.emptyList());}
  SourcePathCatalog(MainActivity owner,List<Models.Source> values,List<SourceDestinationPopup.Destination> initial){this.owner=owner;this.values=values;this.initial=new ArrayList<>(initial);}
  public List<SourceDestinationPopup.Destination> initialSelection(){return initial;}
  public List<Models.Source> sources(){return values;}
  public boolean includeSourcePaths(){return true;}
  public Map<String,Set<String>> categories(){Map<String,Set<String>> out=new LinkedHashMap<>();for(Models.Source source:values)for(String category:source.categories)out.computeIfAbsent(category,k->new LinkedHashSet<>()).add(LanzouCore.sourceId(source));return out;}

  /**
   * Path entries under one node: local self-built folders and the locally stored directory
   * members (a Lanzou link or an imported remote folder). Directory members are resolved into
   * their own child source here, so selecting one packs that directory and expanding it browses
   * the remote folders on demand.
   */
  public List<Models.SourceMember> folders(Models.Source source,String nodeId){
    List<Models.SourceMember> out=new ArrayList<>();if(source==null)return out;
    for(Models.SourceMember member:source.members)if(member.parentId.equals(nodeId)&&pathEntry(member)){prepare(source,member);out.add(member);}
    return out;
  }

  /** Local folders keep their parent subtree; directory members become their own source. */
  static boolean pathEntry(Models.SourceMember member){
    return member.kind==Models.MEMBER_DIRECTORY||member.kind==Models.MEMBER_FOLDER||member.kind==Models.MEMBER_REMOTE_FOLDER;
  }

  private void prepare(Models.Source parent,Models.SourceMember member){
    if(member.kind!=Models.MEMBER_DIRECTORY&&member.kind!=Models.MEMBER_REMOTE_FOLDER)return;
    String key=resolveKey(parent,member);if(resolved.containsKey(key))return;
    Models.Source child=childSource(parent,member);if(child!=null)resolved.put(key,child);
  }

  private static String resolveKey(Models.Source parent,Models.SourceMember member){return LanzouCore.sourceId(parent)+"#path="+member.id;}

  /** The selectable source behind a Lanzou directory or remote folder member. */
  static Models.Source childSource(Models.Source parent,Models.SourceMember member){
    String url=member.url==null?"":member.url.trim();if(url.isEmpty())return null;
    Models.Source child=new Models.Source();child.kind=Models.SOURCE_OFFICIAL;child.id=url;child.url=child.originUrl=url;
    child.parentId=LanzouCore.sourceId(parent);child.title=member.title==null||member.title.isEmpty()?url:member.title;
    child.password=member.password==null?"":member.password;child.searchable=parent.searchable;
    child.originPath=SourceCategoryPicker.memberPath(parent,member);child.childDirectory=true;child.repositoryUrl=parent.repositoryUrl;return child;
  }

  public void loadFolderPaths(Models.Source source,String nodeId,String category,java.util.function.Consumer<List<Models.SourceMember>> done){
    if(source.kind==Models.SOURCE_COMPOSITE){done.accept(folders(source,nodeId));return;}
    if(!"全部".equals(category)){done.accept(Collections.emptyList());return;}
    owner.io.execute(()->{try{
      List<Models.SourceMember> out=new ArrayList<>();
      if(source.kind==Models.SOURCE_REPOSITORY){for(Models.Source child:owner.core.repositorySources(source,false)){Models.SourceMember member=new Models.SourceMember();member.kind=Models.MEMBER_FOLDER;member.id=LanzouCore.sourceId(child);member.title=child.title;member.url=child.url;resolved.put(member.id,child);out.add(member);}}
      else if(source.kind==Models.SOURCE_OFFICIAL){Models.Folder folder=owner.core.browseSource(source,false);for(Models.Item item:folder.items)if(item.folder){Models.Source child=owner.core.nestedChildSource(source,item);if(child==null)continue;Models.SourceMember member=new Models.SourceMember();member.kind=Models.MEMBER_REMOTE_FOLDER;member.id=LanzouCore.sourceId(child);member.url=child.url;member.title=child.title;resolved.put(member.id,child);out.add(member);}}
      done.accept(out);
    }catch(Exception error){owner.runOnUiThread(()->owner.showNotice("路径读取失败："+owner.friendlyError(error),true));done.accept(Collections.emptyList());}});
  }
  public Models.Source pathSource(Models.Source parent,Models.SourceMember member){String key=resolveKey(parent,member);return resolved.containsKey(key)?resolved.get(key):resolved.containsKey(member.id)?resolved.get(member.id):parent;}
  public String pathNodeId(Models.Source parent,Models.SourceMember member){return resolved.containsKey(resolveKey(parent,member))||resolved.containsKey(member.id)?"":member.id;}
  public Models.Source createFolder(Models.Source source,String nodeId,String title){throw new UnsupportedOperationException();}
  public void addNodes(Models.Source source,String nodeId,List<String[]> links){throw new UnsupportedOperationException();}
  public Models.Source addSource(String url,String password,String title){throw new UnsupportedOperationException();}
  public void addCategoryMembers(String category,Set<String> sourceIds){throw new UnsupportedOperationException();}
  public void applied(String summary,boolean failed){owner.showNotice(summary,failed);}
}
