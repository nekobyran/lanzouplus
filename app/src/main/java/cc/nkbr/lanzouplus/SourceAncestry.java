package cc.nkbr.lanzouplus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the parent of child-directory sources from catalog paths.
 *
 * <p>Root sources are addressed by their title; child sources by their full
 * {@code originPath}. A link is created only when the complete path matches
 * exactly one catalog entry inside the same repository, or exactly one entry
 * across the whole catalog when the repository is unknown. Partial or fuzzy
 * path matches are never used.</p>
 *
 * <p>Explicit {@code parentId} values always win: {@link #link(List)} only
 * fills blanks, and {@link #chain(Models.Source, List)} follows an existing
 * {@code parentId} before falling back to the path index.</p>
 */
final class SourceAncestry {
  private static final String SEP=" / ";

  private SourceAncestry() {}

  /** Fills missing child-directory parents and returns how many sources changed. */
  static int link(List<Models.Source> sources) {
    if(sources==null||sources.isEmpty())return 0;
    Catalog catalog=new Catalog(sources);
    int linked=0;
    for(Models.Source source:sources) {
      if(source==null||!source.childDirectory||!isBlank(source.parentId))continue;
      String path=normalize(source.originPath);
      if(path.isEmpty())continue;
      int cut=path.lastIndexOf(SEP);
      if(cut<=0)continue;
      String parentPath=path.substring(0,cut).trim();
      Models.Source parent=catalog.byOrigin(source);
      if(parent==null)parent=catalog.unique(parentPath,repositoryKey(source));
      if(parent==null||parent==source)continue;
      String parentId=sourceId(parent);
      if(parentId.isEmpty()||parentId.equals(sourceId(source)))continue;
      if(catalog.wouldCycle(source,parent))continue;
      source.parentId=parentId;
      linked++;
    }
    return linked;
  }

  /**
   * Returns the known ancestors of {@code source} ordered root first, parent
   * last. The source itself is excluded. Unknown or ambiguous links end the
   * walk.
   */
  static List<Models.Source> chain(Models.Source source,List<Models.Source> sources) {
    if(source==null||sources==null||sources.isEmpty())return new ArrayList<>();
    Catalog catalog=new Catalog(sources);
    List<Models.Source> ancestors=new ArrayList<>();
    Set<Models.Source> visited=Collections.newSetFromMap(new IdentityHashMap<>());
    visited.add(source);
    Models.Source current=source;
    while(true) {
      Models.Source parent=catalog.byExplicitId(current.parentId,repositoryKey(current));
      if(parent==null)parent=catalog.byOrigin(current);
      if(parent==null&&isBlank(current.parentId))parent=catalog.byPath(current,repositoryKey(current));
      if(parent==null||parent==current||!visited.add(parent))break;
      ancestors.add(0,parent);
      current=parent;
    }
    return ancestors;
  }

  private static final class Catalog {
    private final Map<String,List<Models.Source>> byScopedPath=new LinkedHashMap<>();
    private final Map<String,List<Models.Source>> byPath=new LinkedHashMap<>();
    private final Map<String,List<Models.Source>> byScopedId=new LinkedHashMap<>();
    private final Map<String,List<Models.Source>> byId=new LinkedHashMap<>();

    Catalog(List<Models.Source> sources) {
      for(Models.Source source:sources) {
        if(source==null)continue;
        String repository=repositoryKey(source);
        String path=pathOf(source);
        if(!path.isEmpty()) {
          add(byScopedPath,scoped(repository,path),source);
          add(byPath,path,source);
        }
        String id=sourceId(source);
        if(!id.isEmpty()) {
          add(byScopedId,scoped(repository,id),source);
          add(byId,id,source);
        }
      }
    }

    Models.Source unique(String path,String repository) {
      return single(byScopedPath.get(scoped(repository,path)),repository.isEmpty()?byPath.get(path):null);
    }

    Models.Source byPath(Models.Source source,String repository) {
      String path=normalize(source.originPath);
      int cut=path.lastIndexOf(SEP);
      if(cut<=0)return null;
      return unique(path.substring(0,cut).trim(),repository);
    }

    Models.Source byExplicitId(String rawId,String repository) {
      String id=normalize(rawId);
      if(id.isEmpty())return null;
      return single(byScopedId.get(scoped(repository,id)),repository.isEmpty()?byId.get(id):null);
    }

    Models.Source byOrigin(Models.Source child){
      if(!child.childDirectory)return null;
      String repository=repositoryKey(child),url=isBlank(child.parentId)?normalize(child.originUrl):normalize(child.parentId);
      if(url.equals(sourceId(child))||!LanzouCore.isLanzouSourceUrl(url))return null;
      Models.Source known=byExplicitId(url,repository);if(known!=null)return known;
      String path=normalize(child.originPath);int cut=path.lastIndexOf(SEP);if(cut<=0)return null;
      String parentPath=path.substring(0,cut);
      Models.Source parent=new Models.Source();parent.id=parent.url=url;parent.repositoryUrl=child.repositoryUrl;
      int separator=parentPath.lastIndexOf(SEP);parent.title=separator<0?parentPath:parentPath.substring(separator+SEP.length());
      parent.childDirectory=parentPath.contains(SEP);parent.originPath=parentPath;
      return parent;
    }

    boolean wouldCycle(Models.Source child,Models.Source parent) {
      Set<Models.Source> visited=Collections.newSetFromMap(new IdentityHashMap<>());
      visited.add(child);
      Models.Source current=parent;
      while(current!=null&&visited.add(current)) {
        if(current==child)return true;
        Models.Source next=byExplicitId(current.parentId,repositoryKey(current));
        if(next==null)next=byPath(current,repositoryKey(current));
        current=next;
      }
      return current==child;
    }

    private static Models.Source single(List<Models.Source> scoped,List<Models.Source> global) {
      Models.Source match=only(scoped);
      if(match!=null)return match;
      if(scoped!=null&&!scoped.isEmpty())return null;
      return only(global);
    }

    private static Models.Source only(List<Models.Source> candidates) {
      Models.Source match=null;
      if(candidates==null)return null;
      for(Models.Source candidate:candidates) {
        if(candidate==null)continue;
        if(match!=null&&(!sourceId(match).equals(sourceId(candidate))||!repositoryKey(match).equals(repositoryKey(candidate))))return null;
        match=candidate;
      }
      return match;
    }

    private static void add(Map<String,List<Models.Source>> target,String key,Models.Source source) {
      List<Models.Source> values=target.get(key);
      if(values==null)target.put(key,values=new ArrayList<>());
      values.add(source);
    }
  }

  private static String pathOf(Models.Source source) {
    return normalize(source.childDirectory?source.originPath:source.title);
  }

  private static String sourceId(Models.Source source) {return LanzouCore.sourceId(source);}

  private static String repositoryKey(Models.Source source) {
    return source==null?"":normalize(source.repositoryUrl).toLowerCase(Locale.ROOT);
  }

  private static String scoped(String repository,String value) {
    return repository+"\u0000"+value;
  }

  private static String normalize(String value) {return value==null?"":value.trim();}

  private static boolean isBlank(String value) {
    return normalize(value).isEmpty();
  }
}
