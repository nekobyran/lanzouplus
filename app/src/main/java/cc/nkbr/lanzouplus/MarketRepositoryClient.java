package cc.nkbr.lanzouplus;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.json.*;

/** Repository content and cached presentation belong to GitHub; the market records its identity. */
final class MarketRepositoryClient {
  static final String MARKET_ORIGIN="https://lanzoumarket.nkbr.cc";
  /** Public GitHub repository that publishes the ordinary market catalog and announcements. */
  static final String MARKET_STATIC_ORIGIN="https://raw.githubusercontent.com/nekobyran/lanzouplus-market/main/";
  static final String STATIC_CATALOG_FILE="market.json";
  static final String STATIC_ANNOUNCEMENTS_FILE="announcements.json";
  /** Whole static catalog ceiling; one bounded read covers every local page of one session. */
  static final int CATALOG_LIMIT=2*1024*1024;
  static final int SOURCE_LIMIT=8*1024*1024;
  /** Published metadata ceiling, matching the market server's repository.json limit. */
  static final int METADATA_LIMIT=512*1024;
  /** Published README ceiling, matching the market server's README limit. */
  static final int README_LIMIT=2*1024*1024;
  /** Encrypted {@code sources.lzp} ceiling, matching the server codec container maximum. */
  static final int ENCRYPTED_LIMIT=SOURCE_LIMIT+512*1024;
  /** On-disk cache ceiling: plaintext sources plus README and metadata. */
  static final int CACHE_LIMIT=SOURCE_LIMIT+README_LIMIT+METADATA_LIMIT+1024*1024;
  /** One catalog row: the canonical link plus the server's real snapshot state. */
  static final class CatalogEntry {
    final String githubUrl,repositoryName,title,publisherName,description,version,versionMode;
    final String status,updatedAt,addedAt;
    final long snapshotRevision;
    /** {@code project} rows point at GitHub repositories; {@code index} rows at an HTTPS index file. */
    final String type,url;
    final long builtAt,entryCount,sourceCount;
    final int coveredSourceCount;
    final long sizeBytes;
    /** Published index bundled with a project row, or {@code null} when the project has none. */
    final StaticIndexInfo index;
    CatalogEntry(String githubUrl,String repositoryName,String title,String publisherName,
        String description,String version,String versionMode,String status,long snapshotRevision,
        String updatedAt,String addedAt){
      this(githubUrl,repositoryName,title,publisherName,description,version,versionMode,status,
          snapshotRevision,updatedAt,addedAt,null);
    }
    CatalogEntry(String githubUrl,String repositoryName,String title,String publisherName,
        String description,String version,String versionMode,String status,long snapshotRevision,
        String updatedAt,String addedAt,StaticIndexInfo index){
      this.githubUrl=githubUrl;this.repositoryName=repositoryName;this.title=title;
      this.publisherName=publisherName;this.description=description;this.version=version;
      this.versionMode=versionMode;this.status=status;this.snapshotRevision=snapshotRevision;
      this.updatedAt=updatedAt;this.addedAt=addedAt;
      this.type="project";this.url=githubUrl;this.builtAt=0L;this.entryCount=0L;this.sourceCount=0L;
      this.coveredSourceCount=0;
      this.sizeBytes=0L;
      this.index=index;
    }
    /** One shared static index: the download link replaces the GitHub repository link. */
    private CatalogEntry(String url,String title,String description,long builtAt,long entryCount,
        long sourceCount,int coveredSourceCount,long sizeBytes){
      this.githubUrl=url;this.repositoryName=title;this.title=title;this.publisherName="";
      this.description=description;this.version="";this.versionMode="";this.status="index";
      this.snapshotRevision=0L;this.updatedAt="";this.addedAt="";this.type="index";this.url=url;
      this.builtAt=builtAt;this.entryCount=entryCount;this.sourceCount=sourceCount;
      this.coveredSourceCount=Math.max(0,Math.min((int)Math.max(0,sourceCount),coveredSourceCount));
      this.sizeBytes=Math.max(0L,sizeBytes);
      this.index=null;
    }
    static CatalogEntry parse(JSONObject value)throws Exception{
      if("index".equals(value.optString("type","").trim()))return parseIndex(value);
      String url=MarketRepository.canonicalUrl(value.getString("githubUrl"));
      String repositoryName=value.optString("repositoryName",url.substring(url.lastIndexOf('/')+1)).trim();
      String title=value.optString("title",repositoryName).trim();
      String publisherName=value.optString("publisherName",url.substring("https://github.com/".length(),url.lastIndexOf('/'))).trim();
      String version=value.optString("version","").trim();
      String mode=MarketRepository.isVersionMode(value.optString("versionMode",""))
          ?value.optString("versionMode"):(version.isEmpty()?"":MarketRepository.detectedVersionMode(version));
      return new CatalogEntry(url,repositoryName,title.isEmpty()?repositoryName:title,publisherName,
          value.optString("description",""),version,mode==null?"":mode,value.optString("status"),
          value.optLong("snapshotRevision",0),value.optString("updatedAt"),value.optString("addedAt"),
          indexInfo(value));
    }
    /** Catalog rows may repeat the repository's published index so the card needs no extra read. */
    private static StaticIndexInfo indexInfo(JSONObject value){
      JSONObject index=value.optJSONObject("index");
      if(index==null)return null;
      String url=index.optString("url","").trim();
      if(!url.startsWith("https://"))return null;
      int declared=Math.max(0,index.optInt("sourceCount",0));
      int covered=index.has("coveredSourceCount")?index.optInt("coveredSourceCount",declared):declared;
      return new StaticIndexInfo("",index.optString("name",value.optString("title","")),
          index.optLong("builtAt",0L),index.optInt("entryCount",0),declared,url,
          index.optLong("size",index.optLong("sizeBytes",0L)),covered);
    }
    private static CatalogEntry parseIndex(JSONObject value)throws Exception{
      String url=value.optString("url","").trim();
      if(!url.startsWith("https://"))throw new IOException("索引条目必须使用 HTTPS 下载地址");
      String title=value.optString("title","").trim();
      if(title.isEmpty())throw new IOException("索引条目缺少标题");
      return new CatalogEntry(url,title,value.optString("description",""),
          value.optLong("builtAt",0L),value.optLong("entryCount",0L),value.optLong("sourceCount",0L),
          value.optInt("coveredSourceCount",value.optInt("sourceCount",0)),value.optLong("size",0L));
    }
    JSONObject json()throws Exception{
      if(isIndex())return new JSONObject().put("type","index").put("title",title).put("url",url)
          .put("description",description).put("builtAt",builtAt).put("entryCount",entryCount)
          .put("sourceCount",sourceCount).put("coveredSourceCount",coveredSourceCount)
          .put("size",sizeBytes);
      JSONObject out=new JSONObject().put("githubUrl",githubUrl).put("repositoryName",repositoryName)
          .put("title",title).put("publisherName",publisherName).put("description",description)
          .put("version",version).put("versionMode",versionMode).put("status",status)
          .put("snapshotRevision",snapshotRevision).put("updatedAt",updatedAt).put("addedAt",addedAt)
          .put("type","project");
      if(index!=null)out.put("index",indexJson(index));
      return out;
    }
    private static JSONObject indexJson(StaticIndexInfo info)throws Exception{
      return new JSONObject().put("url",info.path).put("size",info.sizeBytes)
          .put("builtAt",info.builtAt).put("entryCount",info.entryCount)
          .put("sourceCount",info.sourceCount).put("coveredSourceCount",info.coveredSourceCount);
    }
    String displayTitle(){return title==null||title.trim().isEmpty()?repositoryName:title.trim();}
    String displayPublisher(){return publisherName==null?"":publisherName.trim();}
    boolean isIndex(){return "index".equals(type);}
    /** Build time from the catalog, shown as the card's 建立日期; empty when the entry omits it. */
    String builtAtText(){
      if(builtAt<=0L)return "";
      return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date(builtAt));
    }
    boolean hasVersion(){return !version.isEmpty()&&MarketRepository.isVersionMode(versionMode);}
  }

  /**
   * One bounded page of the market catalog. A list of links alone cannot show that the server
   * re-snapshotted a repository, so the page keeps {@code snapshotRevision}, {@code status} and
   * the timestamps and exposes {@link #fingerprint()} for change detection.
   */
  static final class Catalog {
    final List<CatalogEntry> entries;
    final long total;
    final int offset,limit;
    Catalog(List<CatalogEntry> entries,long total,int offset,int limit){this.entries=entries;this.total=total;this.offset=offset;this.limit=limit;}
    List<String> urls(){List<String> out=new ArrayList<>();for(CatalogEntry entry:entries)out.add(entry.githubUrl);return out;}
    String fingerprint(){StringBuilder out=new StringBuilder();for(CatalogEntry entry:entries)out.append(entry.githubUrl).append('\u0001').append(entry.type).append('\u0001').append(entry.snapshotRevision).append('\u0001').append(entry.updatedAt).append('\u0001').append(entry.status).append('\u0001').append(entry.title).append('\u0001').append(entry.publisherName).append('\u0001').append(entry.description).append('\u0001').append(entry.version).append('\u0001').append(entry.versionMode).append('\u0001').append(entry.builtAt).append('\u0001').append(entry.entryCount).append('\u0001').append(entry.sourceCount).append('\u0001').append(entry.coveredSourceCount).append('\u0001').append(entry.sizeBytes).append('\u0001').append(entry.index==null?"":entry.index.path).append('\u0001').append(entry.index==null?0:entry.index.sizeBytes).append('\u0001').append(entry.index==null?0:entry.index.builtAt).append('\u0001').append(entry.index==null?0:entry.index.coveredSourceCount).append('\n');return out.toString();}
  }
  interface Transport {
    byte[] get(String url,int limit)throws Exception;
    default JSONObject post(String url,JSONObject value)throws Exception{return httpPost(url,value);}
  }
  /**
   * One repository snapshot. A metadata-only preview carries the name, publisher, version,
   * description, icon and owner avatar without the encrypted source document, so a market row
   * never downloads or decodes {@code sources.lzp}. {@link #sourcesLoaded} separates the two.
   */
  static final class Document {
    final String githubUrl,rawRoot,ownerAvatar;
    final MarketRepository metadata;
    final String sourceJson,readme;
    final boolean sourcesLoaded;
    final long checkedAt;
    private List<Models.Source> parsedSources;
    Document(String url,String root,String avatar,MarketRepository metadata,String sourceJson,String readme,boolean sourcesLoaded,long checkedAt){this.githubUrl=url;rawRoot=root;ownerAvatar=avatar;this.metadata=metadata;this.sourceJson=sourceJson;this.readme=readme;this.sourcesLoaded=sourcesLoaded;this.checkedAt=checkedAt;}
    Models.Source source()throws Exception{
      Models.Source out=new Models.Source();out.kind=Models.SOURCE_REPOSITORY;out.id=out.url=githubUrl;
      out.title=metadata.title;out.publisher=metadata.publisher.isEmpty()?ownerLogin():metadata.publisher;out.description=metadata.description;
      out.avatarUrl=metadata.publisherAvatar.isEmpty()?ownerAvatar:metadata.assetUrl(rawRoot,metadata.publisherAvatar);
      out.iconUrl=metadata.assetUrl(rawRoot,metadata.icon);out.repositoryVersion=metadata.version;out.repositoryVersionMode=metadata.versionMode;
      out.categories.addAll(metadata.categories);int types=0;for(Models.Source source:sources()){if(source.categories.contains(SourceCategories.LANZOU))types|=1;if(source.categories.contains(SourceCategories.OTHER))types|=2;}SourceCategories.automatic(out.categories,types);return out;
    }
    /** The GitHub owner login: the documented publisher fallback when metadata omits one. */
    String ownerLogin(){String identity=githubUrl.substring("https://github.com/".length());return identity.substring(0,identity.indexOf('/'));}
    synchronized List<Models.Source> sources()throws Exception{
      if(!sourcesLoaded)return Collections.emptyList();
      if(parsedSources==null){parsedSources=LanzouCore.parseSourceDocument(sourceJson);for(Models.Source value:parsedSources)value.repositoryUrl=githubUrl;}
      List<Models.Source> values=new ArrayList<>();for(Models.Source value:parsedSources)values.add(LanzouCore.copySource(value));return values;
    }
    String sourceIconUrl(){try{return metadata.assetUrl(rawRoot,metadata.icon);}catch(IOException error){return "";}}
    String sourcePublisher(){return metadata.publisher.isEmpty()?ownerLogin():metadata.publisher;}
    /** README is session state and deliberately omitted from the disk snapshot. */
    JSONObject json()throws Exception{return new JSONObject().put("githubUrl",githubUrl).put("rawRoot",rawRoot).put("ownerAvatar",ownerAvatar).put("metadata",metadata.json).put("sourceJson",sourceJson).put("sourcesLoaded",sourcesLoaded).put("checkedAt",checkedAt);}
  }
  private final File directory;
  private final Transport transport;
  private static final java.util.concurrent.atomic.AtomicLong REFRESH_SEQUENCE=
      new java.util.concurrent.atomic.AtomicLong();
  /** Activity recreation creates a new client, so README reuse ends with that client. */
  private final ConcurrentHashMap<String,Document> sessionReadmes=new ConcurrentHashMap<>();
  private final Map<String,Object> readmeLocks=new ConcurrentHashMap<>();
  private final Map<String,Object> repositoryLocks=new ConcurrentHashMap<>();
  private final Map<String,Document> memory=new HashMap<>();
  private final Map<String,Long> modified=new HashMap<>();
  /** Session snapshot of the static catalog so paging never repeats a GitHub request. */
  private final Object catalogLock=new Object();
  private List<CatalogEntry> sessionCatalog;
  MarketRepositoryClient(File directory){this(directory,MarketRepositoryClient::httpGet);}
  MarketRepositoryClient(File directory,Transport transport){this.directory=directory;this.transport=transport;}

  synchronized Document cached(String raw)throws Exception{
    File file=cacheFile(raw);if(!file.isFile())return null;
    String key=file.getName();if(memory.containsKey(key)&&Objects.equals(modified.get(key),file.lastModified()))return memory.get(key);
    JSONObject value=new JSONObject(new String(read(file,CACHE_LIMIT),StandardCharsets.UTF_8));
    String sourceJson=value.getString("sourceJson");
    boolean sourcesLoaded=value.getBoolean("sourcesLoaded");
    Document result=new Document(value.getString("githubUrl"),value.getString("rawRoot"),value.optString("ownerAvatar"),new MarketRepository(value.getJSONObject("metadata")),sourceJson,"",sourcesLoaded,value.getLong("checkedAt"));memory.put(key,result);modified.put(key,file.lastModified());return result;
  }

  Document load(String raw,boolean force)throws Exception{
    String url=MarketRepository.canonicalUrl(raw);Object lock=repositoryLocks.computeIfAbsent(url,key->new Object());
    synchronized(lock){return loadRepository(url,force);}
  }
  /**
   * Metadata-only preview of one repository: the files a market row needs, and nothing else.
   * A cached snapshot (preview or complete) is reused as-is, so a list of rows reads each
   * repository once. The encrypted source document is never requested here.
   */
  Document preview(String raw)throws Exception{
    String url=MarketRepository.canonicalUrl(raw);Object lock=repositoryLocks.computeIfAbsent(url,key->new Object());
    synchronized(lock){return previewLocked(url,false);}
  }
  Document preview(String raw,boolean force)throws Exception{
    String url=MarketRepository.canonicalUrl(raw);
    synchronized(repositoryLocks.computeIfAbsent(url,key->new Object())){
      Document result=previewLocked(url,force);
      if(force)sessionReadmes.remove(url);
      return result;
    }
  }
  /**
   * Forced {@code lzpmarket.json} read for an update check. It reuses the per-repository lock and
   * touches no README, icon or {@code sources.lzp}; an unchanged version keeps the README session,
   * and {@link #previewLocked} drops a session README only when its version or mode differs from
   * the fresh metadata.
   */
  Document checkVersion(String raw)throws Exception{
    String url=MarketRepository.canonicalUrl(raw);Object lock=repositoryLocks.computeIfAbsent(url,key->new Object());
    synchronized(lock){return previewLocked(url,true);}
  }
  /** Detail reads README without downloading the encrypted source or replacing an installed snapshot. */
  Document readme(String raw,boolean force)throws Exception{
    String url=MarketRepository.canonicalUrl(raw);synchronized(readmeLocks.computeIfAbsent(url,key->new Object())){
      Document remembered=sessionReadmes.get(url);
      if(!force&&remembered!=null)return remembered;
      Document cached=cached(url),preview=previewLocked(url,true);
      return readmeDocument(url,preview,cached);
    }
  }
  /**
   * README for a metadata preview that was just loaded. The update decision uses that preview's
   * {@code lzpmarket.json} version, so README, icons and {@code sources.lzp} never gate it.
   */
  Document readme(Document preview,boolean force)throws Exception{
    String url=MarketRepository.canonicalUrl(preview.githubUrl);synchronized(readmeLocks.computeIfAbsent(url,key->new Object())){
      Document remembered=sessionReadmes.get(url);
      if(!force&&remembered!=null&&remembered.metadata.version.equals(preview.metadata.version)
          &&remembered.metadata.versionMode.equals(preview.metadata.versionMode))return remembered;
      return readmeDocument(url,preview,cached(url));
    }
  }
  private Document readmeDocument(String url,Document preview,Document cached)throws Exception{
    String readme=new String(transport.get(freshUrl(preview.rawRoot+preview.metadata.readme),README_LIMIT),StandardCharsets.UTF_8);
    Document result=new Document(url,preview.rawRoot,preview.ownerAvatar,preview.metadata,preview.sourceJson,readme,preview.sourcesLoaded,preview.checkedAt);
    if(cached==null||!cached.sourcesLoaded||preview.sourcesLoaded)save(result);
    sessionReadmes.put(url,result);
    return result;
  }
  /**
   * Reads the metadata a market row needs. An existing snapshot keeps its decoded sources and
   * saved category edits: the same version refreshes the timestamp only, and a newer upstream
   * version leaves the stored snapshot alone so a later {@link #load} still refetches the
   * encrypted document. A session README survives when it already describes the fresh metadata
   * version and mode. A repository with no snapshot yet is stored as a metadata-only preview.
   */
  private Document previewLocked(String url,boolean force)throws Exception{
    Document old=cached(url);
    if(old!=null&&!force&&System.currentTimeMillis()-old.checkedAt<60*60*1000L)return old;
    String identity=url.substring("https://github.com/".length());
    String root=rawRoot(identity);
    String metadataUrl=force?freshUrl(root+"lzpmarket.json"):root+"lzpmarket.json";
    MarketRepository metadata=new MarketRepository(new JSONObject(new String(transport.get(metadataUrl,METADATA_LIMIT),StandardCharsets.UTF_8)));
    if(old==null){Document fresh=new Document(url,root,avatarUrl(identity),metadata,"","",false,System.currentTimeMillis());save(fresh);return fresh;}
    if(old.metadata.versionMode.equals(metadata.versionMode)&&MarketRepository.compareVersions(metadata.versionMode,metadata.version,old.metadata.version)<0)return old;
    if(!upstreamAhead(metadata,old.metadata)){Document kept=new Document(url,root,avatarUrl(identity),metadata,old.sourceJson,old.readme,old.sourcesLoaded,System.currentTimeMillis());save(kept);return kept;}
    Document remembered=sessionReadmes.get(url);
    if(remembered!=null&&(!remembered.metadata.version.equals(metadata.version)
        ||!remembered.metadata.versionMode.equals(metadata.versionMode)))
      sessionReadmes.remove(url,remembered);
    return new Document(url,root,avatarUrl(identity),metadata,"","",false,System.currentTimeMillis());
  }
  /**
   * Reads the metadata and, when the cached snapshot cannot be reused, the encrypted source
   * document. Neither an unchanged version nor a metadata-only preview re-downloads
   * {@code sources.lzp} for a version whose sources are already decoded.
   */
  private Document fetch(String url,boolean force)throws Exception{
    Document old=cached(url);
    if(old!=null&&old.sourcesLoaded&&!force&&System.currentTimeMillis()-old.checkedAt<60*60*1000L){
      Document session=sessionReadmes.get(url);
      if(session!=null&&session.sourcesLoaded)return session;
      String readme=new String(transport.get(freshUrl(old.rawRoot+old.metadata.readme),README_LIMIT),StandardCharsets.UTF_8);
      Document result=new Document(url,old.rawRoot,old.ownerAvatar,old.metadata,old.sourceJson,readme,true,old.checkedAt);
      sessionReadmes.put(url,result);return result;
    }
    String identity=url.substring("https://github.com/".length());
    String root=rawRoot(identity);
    String metadataUrl=force?freshUrl(root+"lzpmarket.json"):root+"lzpmarket.json";
    MarketRepository metadata=new MarketRepository(new JSONObject(new String(transport.get(metadataUrl,METADATA_LIMIT),StandardCharsets.UTF_8)));
    String sourceJson="",readme="";boolean sourcesLoaded=false,readmeLoaded=false;
    if(old!=null&&old.sourcesLoaded&&metadata.versionMode.equals(old.metadata.versionMode)){
      int compared=MarketRepository.compareVersions(metadata.versionMode,metadata.version,old.metadata.version);
      if(compared<0||compared==0&&!force){
        sourceJson=old.sourceJson;sourcesLoaded=true;if(compared<0)metadata=old.metadata;
        Document session=sessionReadmes.get(url);
        if(!force&&session!=null&&session.metadata.version.equals(metadata.version)){readme=session.readme;readmeLoaded=true;}
      }
    }
    if(!sourcesLoaded){
      String sourcesUrl=force?freshUrl(root+metadata.sources):root+metadata.sources;
      sourceJson=new String(MarketSourceCodec.decode(transport.get(sourcesUrl,ENCRYPTED_LIMIT)),StandardCharsets.UTF_8);
      LanzouCore.parseSourceDocument(sourceJson);
      sourcesLoaded=true;
    }
    if(!readmeLoaded)readme=new String(transport.get(freshUrl(root+metadata.readme),README_LIMIT),StandardCharsets.UTF_8);
    Document result=new Document(url,root,avatarUrl(identity),metadata,sourceJson,readme,sourcesLoaded,System.currentTimeMillis());
    save(result);sessionReadmes.put(url,result);return result;
  }
  /** True when the freshly read metadata describes a version above the cached snapshot. */
  private static boolean upstreamAhead(MarketRepository fresh,MarketRepository cached)throws Exception{
    return !fresh.versionMode.equals(cached.versionMode)||MarketRepository.compareVersions(fresh.versionMode,fresh.version,cached.version)>0;
  }
  private Document loadRepository(String raw,boolean force)throws Exception{
    return fetch(MarketRepository.canonicalUrl(raw),force);
  }
  /**
   * The published root of a repository. GitHub serves raw files through the {@code HEAD}
   * ref, which resolves the default branch, so a preview needs no REST API call.
   */
  static String rawRoot(String identity){return "https://raw.githubusercontent.com/"+identity+"/HEAD/";}
  /** GitHub serves the owner avatar directly; a missing owner answers 404 like any raw read. */
  static String avatarUrl(String identity){return "https://github.com/"+identity.substring(0,identity.indexOf('/'))+".png?size=96";}

  /** Public GitHub repository identity, independent of a published lzpmarket manifest. */
  static final class GithubInfo {
    final String name,owner,avatar,description;
    final long stars,loadedAt;
    GithubInfo(String name,String owner,String avatar,String description,long stars,long loadedAt){
      this.name=name;this.owner=owner;this.avatar=avatar;this.description=description;this.stars=stars;this.loadedAt=loadedAt;
    }
  }
  private static final class GithubInfoGate{volatile long generation;}
  private final Map<String,GithubInfo> githubInfoCache=new ConcurrentHashMap<>();
  private final Map<String,GithubInfoGate> githubInfoGates=new ConcurrentHashMap<>();

  GithubInfo githubInfo(String raw,boolean force)throws Exception{
    String url=MarketRepository.canonicalUrl(raw);
    GithubInfoGate gate=githubInfoGates.computeIfAbsent(url,key->new GithubInfoGate());
    long observedGeneration=gate.generation;
    synchronized(gate){
      GithubInfo cached=loadGithubInfo(url);
      /*
       * A refresh that completed while this call waited is reused. A force call that starts
       * after that refresh observes the new generation and therefore performs its own refresh;
       * sequential force calls never collapse on a millisecond boundary.
       */
      if(cached!=null&&gate.generation!=observedGeneration)return cached;
      if(!force&&cached!=null&&System.currentTimeMillis()-cached.loadedAt<10*60*1000L)return cached;
      String identity=url.substring("https://github.com/".length());
      JSONObject repository=new JSONObject(new String(transport.get("https://api.github.com/repos/"+identity,128*1024),StandardCharsets.UTF_8));
      JSONObject owner=repository.getJSONObject("owner");
      GithubInfo info=new GithubInfo(repository.optString("name",identity.substring(identity.indexOf('/')+1)),
          owner.optString("login",identity.substring(0,identity.indexOf('/'))),
          owner.optString("avatar_url",avatarUrl(identity)),repository.isNull("description")?"":repository.optString("description",""),
          repository.optLong("stargazers_count",-1),System.currentTimeMillis());
      githubInfoCache.put(url,info);
      File file=githubInfoFile(url);
      if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("无法创建 GitHub 信息缓存");
      JSONObject saved=new JSONObject().put("name",info.name).put("owner",info.owner).put("avatar",info.avatar).put("description",info.description).put("stars",info.stars).put("loadedAt",info.loadedAt);
      try(FileOutputStream output=new FileOutputStream(file)){output.write(saved.toString().getBytes(StandardCharsets.UTF_8));}
      gate.generation++;
      return info;
    }
  }
  private GithubInfo loadGithubInfo(String url)throws Exception{
    GithubInfo cached=githubInfoCache.get(url);
    if(cached!=null)return cached;
    File file=githubInfoFile(url);
    if(!file.isFile())return null;
    JSONObject saved=new JSONObject(new String(read(file,128*1024),StandardCharsets.UTF_8));
    cached=new GithubInfo(saved.getString("name"),saved.getString("owner"),saved.getString("avatar"),saved.getString("description"),saved.getLong("stars"),saved.getLong("loadedAt"));
    githubInfoCache.put(url,cached);
    return cached;
  }

  GithubInfo cachedGithubInfo(String raw)throws Exception{
    return loadGithubInfo(MarketRepository.canonicalUrl(raw));
  }
  private File githubInfoFile(String url)throws Exception{
    return new File(new File(directory,"github-info"),cacheFile(url).getName());
  }

  synchronized void save(Document value)throws Exception{
    File file=cacheFile(value.githubUrl);if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建仓库缓存目录");
    File temp=new File(directory,file.getName()+".tmp");
    try(FileOutputStream output=new FileOutputStream(temp)){output.write(value.json().toString().getBytes(StandardCharsets.UTF_8));}
    if(!temp.renameTo(file))throw new IOException("无法保存仓库缓存");
    memory.put(file.getName(),value);modified.put(file.getName(),file.lastModified());
  }

  /**
   * The whole ordinary catalog, read once from the public GitHub static repository and
   * remembered for this client session. {@code force} bypasses both that snapshot and any
   * HTTP cache, which the submission duplicate check requires.
   */
  List<CatalogEntry> catalogAll(boolean force)throws Exception{
    if(!force)synchronized(catalogLock){if(sessionCatalog!=null)return new ArrayList<>(sessionCatalog);}
    /* The GitHub read stays outside the lock so badge and page reads never wait on a route. */
    List<CatalogEntry> fresh=readStaticCatalog(force);
    synchronized(catalogLock){sessionCatalog=fresh;return new ArrayList<>(fresh);}
  }
  /** One local page of the session catalog; the network read already happened. */
  Catalog catalog(int offset,int limit)throws Exception{
    List<CatalogEntry> all=catalogAll(false);
    int from=Math.max(0,Math.min(offset,all.size()));
    int to=Math.max(from,Math.min(all.size(),from+Math.max(0,limit)));
    return new Catalog(new ArrayList<>(all.subList(from,to)),all.size(),from,to-from);
  }
  private List<CatalogEntry> readStaticCatalog(boolean force)throws Exception{
    String address=MARKET_STATIC_ORIGIN+STATIC_CATALOG_FILE;
    JSONObject value=new JSONObject(new String(
        transport.get(force?freshUrl(address):address,CATALOG_LIMIT),StandardCharsets.UTF_8));
    if(value.optInt("schema",0)!=1)throw new IOException("市场静态清单格式版本不受支持");
    JSONArray rows=value.optJSONArray("repositories");
    if(rows==null)throw new IOException("市场清单未读取完整，请重试");
    List<CatalogEntry> entries=new ArrayList<>();
    for(int i=0;i<rows.length();i++){
      JSONObject row=rows.optJSONObject(i);
      if(row==null)continue;
      try{entries.add(CatalogEntry.parse(row));}
      catch(Exception invalid){if(!"index".equals(row.optString("type","").trim()))throw invalid;}
    }
    return Collections.unmodifiableList(entries);
  }
  /**
   * GitHub owner and repository names are case-insensitive, so identity comparisons use the
   * lowercase canonical link. A stored or typed {@code .git} suffix is already removed.
   */
  static String catalogKey(String url)throws IOException{
    return MarketRepository.canonicalUrl(url).toLowerCase(Locale.ROOT);
  }
  /** Ceiling for one downloaded static index; the store re-checks the file size on import. */
  static final int INDEX_LIMIT=64*1024*1024;

  static final class IndexDownload{
    final byte[] bytes;
    final JSONObject document;
    final int entryCount,sourceCount;
    IndexDownload(byte[] bytes,JSONObject document,int entryCount,int sourceCount){
      this.bytes=bytes;this.document=document;this.entryCount=entryCount;this.sourceCount=sourceCount;
    }
  }

  /** Downloads a market index link once and validates its shape before any local write. */
  IndexDownload downloadIndex(String raw)throws Exception{
    String url=raw==null?"":raw.trim();
    if(!url.startsWith("https://"))throw new IOException("索引下载地址必须使用 HTTPS");
    byte[] bytes=transport.get(url,INDEX_LIMIT);
    JSONObject document=validateStaticIndex(bytes);
    JSONArray entries=document.getJSONArray("entries");
    return new IndexDownload(bytes,document,entries.length(),
        document.optInt("sourceCount",0));
  }

  /**
   * A market index is a JSON object with an {@code entries} array. {@code schema} is optional for
   * plain exports but, when present, must be 1; a wrong or empty shape names the broken field.
   */
  static JSONObject validateStaticIndex(byte[] bytes)throws Exception{
    if(bytes==null||bytes.length==0)throw new IOException("索引文件为空");
    String text=new String(bytes,StandardCharsets.UTF_8).trim();
    if(text.isEmpty())throw new IOException("索引文件为空");
    JSONObject value;
    try{value=new JSONObject(text);}
    catch(JSONException error){throw new IOException("索引文件不是有效的 JSON 对象");}
    int schema=value.optInt("schema",0);
    if(schema!=0&&schema!=1)throw new IOException("索引文件版本不受支持（schema="+schema+"）");
    JSONArray entries=value.optJSONArray("entries");
    if(entries==null)throw new IOException("索引文件缺少 entries 数组");
    if(entries.length()==0)throw new IOException("索引文件 entries 为空");
    for(int i=0;i<entries.length();i++)if(entries.optJSONObject(i)==null)
      throw new IOException("索引文件第 "+(i+1)+" 个条目不是对象");
    return value;
  }

  interface SubmissionProgress{void status(String message);}

  static final class Submission{
    final String githubUrl,title,repositoryName,publisherName,description,version,versionMode,operation;
    final String publishedVersion,publishedVersionMode;
    final boolean serverCurrent;
    final boolean submitted;
    Submission(String githubUrl,String title,String repositoryName,String publisherName,
        String description,String version,String versionMode,String operation,boolean submitted){
      this(githubUrl,title,repositoryName,publisherName,description,version,versionMode,
          operation,submitted,"","",false);
    }
    Submission(String githubUrl,String title,String repositoryName,String publisherName,
        String description,String version,String versionMode,String operation,boolean submitted,
        String publishedVersion,String publishedVersionMode,boolean serverCurrent){
      this.githubUrl=githubUrl;this.title=title;this.repositoryName=repositoryName;
      this.publisherName=publisherName;this.description=description;this.version=version;
      this.versionMode=versionMode;this.operation=operation;this.submitted=submitted;
      this.publishedVersion=publishedVersion==null?"":publishedVersion;
      this.publishedVersionMode=publishedVersionMode==null?"":publishedVersionMode;
      this.serverCurrent=serverCurrent;
    }
    boolean created(){return submitted&&"created".equals(operation);}
    boolean updated(){return submitted&&"updated".equals(operation);}
    boolean current(){return !submitted||"current".equals(operation);}
  }

  /** Server rejection with a stable machine code for actionable UI copy. */
  static final class SubmissionRejected extends IOException{
    final int status;
    final String code;
    SubmissionRejected(int status,String code,String message){
      super(message);this.status=status;this.code=code;
    }
  }

  static String automaticSubmissionDescription(MarketRepository metadata){
    String value=metadata.description==null?"":metadata.description.trim();
    if(value.isEmpty())value=metadata.title.trim();
    if(value.isEmpty())value=metadata.name;
    if(value.codePointCount(0,value.length())<=30)return value;
    return value.substring(0,value.offsetByCodePoints(0,30));
  }

  /**
   * Reads the published catalog once before a GitHub write. A same/lower version or a
   * changed version mode is rejected here, so one-click publishing does not create a
   * repository commit only to have the market report that nothing can be submitted.
   */
  Submission precheck(String raw,JSONObject metadataValue)throws Exception{
    String url=MarketRepository.canonicalUrl(raw),key=catalogKey(url);
    MarketRepository metadata=new MarketRepository(new JSONObject(metadataValue.toString()));
    metadata.validateSubmission(url);
    String versionMode=MarketRepository.resolveVersionMode(metadata.versionMode,metadata.version);
    CatalogEntry listed=null;
    for(CatalogEntry entry:catalogAll(true))if(!entry.isIndex()&&catalogKey(entry.githubUrl).equals(key)){listed=entry;break;}
    if(listed==null||!listed.hasVersion())return null;
    if(!versionMode.equals(listed.versionMode))throw new SubmissionRejected(409,
        "repository_version_mode_changed",
        "市场已收录版本格式为 "+listed.versionMode+"，当前工程为 "+versionMode
            +"；同一仓库不能切换版本格式，请恢复原格式后再提交");
    if(MarketRepository.compareVersions(versionMode,metadata.version,listed.version)<=0){
      return new Submission(url,metadata.title,metadata.name,metadata.publisher,
          automaticSubmissionDescription(metadata),metadata.version,versionMode,"current",false,
          listed.version,listed.versionMode,false);
    }
    return null;
  }

  Submission upload(String raw,SubmissionProgress progress)throws Exception{
    String url=MarketRepository.canonicalUrl(raw),key=catalogKey(url);
    if(progress!=null)progress.status("正在读取 GitHub 市场清单并检查版本…");
    CatalogEntry listed=null;
    for(CatalogEntry entry:catalogAll(true))if(!entry.isIndex()&&catalogKey(entry.githubUrl).equals(key)){listed=entry;break;}
    if(progress!=null)progress.status("正在读取 GitHub 工程资料…");
    String identity=url.substring("https://github.com/".length()),root=rawRoot(identity);
    MarketRepository metadata=new MarketRepository(new JSONObject(
        new String(transport.get(freshUrl(root+"lzpmarket.json"),METADATA_LIMIT),StandardCharsets.UTF_8)));
    metadata.validateSubmission(url);
    String versionMode=MarketRepository.resolveVersionMode(metadata.versionMode,metadata.version);
    String publisher=metadata.publisher.isEmpty()?ownerLogin(url):metadata.publisher;
    String description=automaticSubmissionDescription(metadata);
    if(listed!=null&&listed.hasVersion()){
      /* Comparing values that use different version modes would order unrelated formats. */
      if(!versionMode.equals(listed.versionMode))throw new SubmissionRejected(409,
          "repository_version_mode_changed",
          "市场已收录的版本格式与当前仓库不同，请合并这两条记录后再上传");
      if(MarketRepository.compareVersions(versionMode,metadata.version,listed.version)<=0){
        return new Submission(url,metadata.title,metadata.name,publisher,description,
            metadata.version,versionMode,"current",false,listed.version,listed.versionMode,false);
      }
    }
    if(progress!=null)progress.status("正在验证源与 README…");
    String sourceJson=new String(MarketSourceCodec.decode(
        transport.get(freshUrl(root+metadata.sources),ENCRYPTED_LIMIT)),StandardCharsets.UTF_8);
    if(LanzouCore.parseSourceDocument(sourceJson).isEmpty())throw new IOException("仓库至少需要一个有效源");
    transport.get(freshUrl(root+metadata.readme),README_LIMIT);
    if(progress!=null)progress.status("正在申请市场检测码…");
    JSONObject preflight=transport.post(MARKET_ORIGIN+"/api/v1/repositories/preflight",
        new JSONObject().put("githubUrl",url));
    String code=preflight.getString("verificationCode");
    if(progress!=null)progress.status(listed==null?"正在上传新市场资料…":"正在更新市场目录…");
    JSONObject saved=transport.post(MARKET_ORIGIN+"/api/v1/repositories",
        new JSONObject().put("githubUrl",url).put("verificationCode",code)
            .put("description",description));
    String operation=saved.optString("operation",saved.optBoolean("created")?"created":"updated");
    if("current".equals(operation)){
      return new Submission(url,metadata.title,metadata.name,publisher,description,
          metadata.version,versionMode,"current",false,
          saved.optString("version",""),saved.optString("versionMode",""),true);
    }
    return new Submission(url,saved.optString("title",metadata.title),
        saved.optString("repositoryName",metadata.name),saved.optString("publisherName",publisher),
        saved.optString("description",description),metadata.version,versionMode,operation,true,
        saved.optString("version",""),saved.optString("versionMode",""),false);
  }

  private static String ownerLogin(String repositoryUrl){
    String identity=repositoryUrl.substring("https://github.com/".length());
    return identity.substring(0,identity.indexOf('/'));
  }

  private static JSONObject httpPost(String endpoint,JSONObject value)throws Exception{
    HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
    connection.setConnectTimeout(12000);connection.setReadTimeout(30000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
    connection.setRequestProperty("User-Agent","LanzouPlus-Market");connection.setRequestProperty("Accept","application/json");
    try{
      byte[] body=value.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);
      try(OutputStream output=connection.getOutputStream()){output.write(body);}
      int status=connection.getResponseCode();
      if(status<200||status>=300){try(InputStream input=connection.getErrorStream()){
        throw submissionFailure(status,input==null?new byte[0]:bytes(input,64*1024));
      }}
      try(InputStream input=connection.getInputStream()){return new JSONObject(new String(bytes(input,64*1024),StandardCharsets.UTF_8));}
    }finally{connection.disconnect();}
  }

  static SubmissionRejected submissionFailure(int status,byte[] response){
    String detail="",code="";
    try{JSONObject error=new JSONObject(new String(response,StandardCharsets.UTF_8)).optJSONObject("error");
      if(error!=null){detail=error.optString("message").trim();code=error.optString("code").trim();}
    }catch(JSONException ignored){}
    return new SubmissionRejected(status,code,
        "无法上传市场（HTTP "+status+"）"+(detail.isEmpty()?"，请稍后重试":"："+detail));
  }

  private File cacheFile(String raw)throws Exception{return new File(directory,MarketRepository.canonicalUrl(raw).substring("https://github.com/".length()).replace('/','_')+".json");}
  static String freshUrl(String url){
    char separator=url.indexOf('?')<0?'?':'&';
    return url+separator+"lzp-refresh="+System.currentTimeMillis()+REFRESH_SEQUENCE.incrementAndGet();
  }
  static byte[] httpGet(String raw,int limit)throws Exception{
    if(GithubPublicTransport.supports(raw))return GithubPublicTransport.get(raw,limit);
    URL url=new URL(raw);if(!url.getProtocol().equals("https"))throw new IOException("仓库资源必须使用 HTTPS");
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(12000);connection.setReadTimeout(20000);connection.setRequestProperty("User-Agent","LanzouPlus-Market");connection.setRequestProperty("Accept","application/vnd.github+json");
    try{int status=connection.getResponseCode();if(status!=200)throw new IOException("仓库读取失败（HTTP "+status+"）");try(InputStream input=connection.getInputStream()){return bytes(input,limit);}}finally{connection.disconnect();}
  }
  private static byte[] read(File file,int limit)throws IOException{try(InputStream input=new FileInputStream(file)){return bytes(input,limit);}}
  static byte[] bytes(InputStream input,int limit)throws IOException{
    ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
    while((count=input.read(buffer))!=-1){if(output.size()+count>limit)throw new IOException("仓库文件超过容量上限");output.write(buffer,0,count);}return output.toByteArray();
  }
}
