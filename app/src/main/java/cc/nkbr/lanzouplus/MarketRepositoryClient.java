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
    final String githubUrl,status,updatedAt,addedAt,description;
    final long snapshotRevision;
    CatalogEntry(String githubUrl,String status,long snapshotRevision,String updatedAt,String addedAt,String description){this.githubUrl=githubUrl;this.status=status;this.snapshotRevision=snapshotRevision;this.updatedAt=updatedAt;this.addedAt=addedAt;this.description=description;}
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
    String fingerprint(){StringBuilder out=new StringBuilder();for(CatalogEntry entry:entries)out.append(entry.githubUrl).append('\u0001').append(entry.snapshotRevision).append('\u0001').append(entry.updatedAt).append('\u0001').append(entry.status).append('\n');return out.toString();}
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
    JSONObject json()throws Exception{return new JSONObject().put("githubUrl",githubUrl).put("rawRoot",rawRoot).put("ownerAvatar",ownerAvatar).put("metadata",metadata.json).put("sourceJson",sourceJson).put("sourcesLoaded",sourcesLoaded).put("readme",readme).put("checkedAt",checkedAt);}
  }
  private final File directory;
  private final Transport transport;
  private final Map<String,Object> repositoryLocks=new ConcurrentHashMap<>();
  private final Map<String,Document> memory=new HashMap<>();
  private final Map<String,Long> modified=new HashMap<>();
  MarketRepositoryClient(File directory){this(directory,MarketRepositoryClient::httpGet);}
  MarketRepositoryClient(File directory,Transport transport){this.directory=directory;this.transport=transport;}

  synchronized Document cached(String raw)throws Exception{
    File file=cacheFile(raw);if(!file.isFile())return null;
    String key=file.getName();if(memory.containsKey(key)&&Objects.equals(modified.get(key),file.lastModified()))return memory.get(key);
    JSONObject value=new JSONObject(new String(read(file,CACHE_LIMIT),StandardCharsets.UTF_8));
    String sourceJson=value.getString("sourceJson");
    boolean sourcesLoaded=value.getBoolean("sourcesLoaded");
    Document result=new Document(value.getString("githubUrl"),value.getString("rawRoot"),value.optString("ownerAvatar"),new MarketRepository(value.getJSONObject("metadata")),sourceJson,value.optString("readme"),sourcesLoaded,value.getLong("checkedAt"));memory.put(key,result);modified.put(key,file.lastModified());return result;
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
    String url=MarketRepository.canonicalUrl(raw);synchronized(repositoryLocks.computeIfAbsent(url,key->new Object())){return previewLocked(url,force);}
  }
  /** Detail reads README without downloading the encrypted source or replacing an installed snapshot. */
  Document readme(String raw,boolean force)throws Exception{
    String url=MarketRepository.canonicalUrl(raw);synchronized(repositoryLocks.computeIfAbsent(url,key->new Object())){
      Document cached=cached(url),preview=previewLocked(url,force);
      if(!force&&!preview.readme.isEmpty())return preview;
      String readme=new String(transport.get(preview.rawRoot+preview.metadata.readme,README_LIMIT),StandardCharsets.UTF_8);
      Document result=new Document(url,preview.rawRoot,preview.ownerAvatar,preview.metadata,preview.sourceJson,readme,preview.sourcesLoaded,preview.checkedAt);
      if(cached==null||!cached.sourcesLoaded||preview.sourcesLoaded)save(result);
      return result;
    }
  }
  /**
   * Reads the metadata a market row needs. An existing snapshot keeps its decoded sources and
   * saved category edits: the same version refreshes the timestamp only, and a newer upstream
   * version leaves the stored snapshot alone so a later {@link #load} still refetches the
   * encrypted document. A repository with no snapshot yet is stored as a metadata-only preview.
   */
  private Document previewLocked(String url,boolean force)throws Exception{
    Document old=cached(url);
    if(old!=null&&!force&&System.currentTimeMillis()-old.checkedAt<60*60*1000L)return old;
    String identity=url.substring("https://github.com/".length());
    String root=rawRoot(identity);
    MarketRepository metadata=new MarketRepository(new JSONObject(new String(transport.get(root+"lzpmarket.json",METADATA_LIMIT),StandardCharsets.UTF_8)));
    if(old==null){Document fresh=new Document(url,root,avatarUrl(identity),metadata,"","",false,System.currentTimeMillis());save(fresh);return fresh;}
    if(old.metadata.versionMode.equals(metadata.versionMode)&&MarketRepository.compareVersions(metadata.versionMode,metadata.version,old.metadata.version)<0)return old;
    if(!upstreamAhead(metadata,old.metadata)){Document kept=new Document(url,root,avatarUrl(identity),metadata,old.sourceJson,old.readme,old.sourcesLoaded,System.currentTimeMillis());save(kept);return kept;}
    return new Document(url,root,avatarUrl(identity),metadata,"","",false,System.currentTimeMillis());
  }
  /**
   * Reads the metadata and, when the cached snapshot cannot be reused, the encrypted source
   * document. Neither an unchanged version nor a metadata-only preview re-downloads
   * {@code sources.lzp} for a version whose sources are already decoded.
   */
  private Document fetch(String url,boolean force)throws Exception{
    Document old=cached(url);
    if(old!=null&&old.sourcesLoaded&&!force&&System.currentTimeMillis()-old.checkedAt<60*60*1000L)return old;
    String identity=url.substring("https://github.com/".length());
    String root=rawRoot(identity);
    MarketRepository metadata=new MarketRepository(new JSONObject(new String(transport.get(root+"lzpmarket.json",METADATA_LIMIT),StandardCharsets.UTF_8)));
    String sourceJson="",readme="";boolean sourcesLoaded=false;
    if(old!=null&&old.sourcesLoaded&&metadata.versionMode.equals(old.metadata.versionMode)){
      int compared=MarketRepository.compareVersions(metadata.versionMode,metadata.version,old.metadata.version);
      if(compared<0||compared==0&&!force){sourceJson=old.sourceJson;readme=old.readme;sourcesLoaded=true;if(compared<0)metadata=old.metadata;}
    }
    if(!sourcesLoaded){
      sourceJson=new String(MarketSourceCodec.decode(transport.get(root+metadata.sources,ENCRYPTED_LIMIT)),StandardCharsets.UTF_8);
      LanzouCore.parseSourceDocument(sourceJson);
      readme=new String(transport.get(root+metadata.readme,README_LIMIT),StandardCharsets.UTF_8);
      sourcesLoaded=true;
    }
    Document result=new Document(url,root,avatarUrl(identity),metadata,sourceJson,readme,sourcesLoaded,System.currentTimeMillis());
    save(result);return result;
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
   * One bounded page of the market catalog, keeping the server's snapshot state so a changed
   * snapshot stays visible even when the link list is unchanged.
   */
  Catalog catalog(int offset,int limit)throws Exception{
    JSONObject value=new JSONObject(new String(transport.get(MARKET_ORIGIN+"/api/v1/repositories?offset="+offset+"&limit="+limit,256*1024),StandardCharsets.UTF_8));
    JSONArray rows=value.getJSONArray("repositories");List<CatalogEntry> entries=new ArrayList<>();
    for(int i=0;i<rows.length();i++){
      JSONObject object=rows.getJSONObject(i);
      entries.add(new CatalogEntry(MarketRepository.canonicalUrl(object.getString("githubUrl")),object.optString("status"),object.optLong("snapshotRevision",0),object.optString("updatedAt"),object.optString("addedAt"),object.optString("description")));
    }
    return new Catalog(entries,value.optLong("total",entries.size()),value.optInt("offset",offset),value.optInt("limit",limit));
  }

  static String submissionDescription(String raw)throws IOException{
    String value=raw.trim();
    if(value.isEmpty())throw new IOException("请填写仓库简介");
    if(value.codePointCount(0,value.length())>30)throw new IOException("简介最多 30 字");
    return value;
  }

  String submit(String raw,String rawDescription)throws Exception{
    String description=submissionDescription(rawDescription),url=MarketRepository.canonicalUrl(raw);
    // Read every live catalog page before any repository validation or write request.
    for(int offset=0;;){
      Catalog page=catalog(offset,100);
      for(CatalogEntry entry:page.entries)if(entry.githubUrl.equalsIgnoreCase(url))throw new IOException("该仓库已收录，无需重复上传");
      offset+=page.entries.size();
      if(offset>=page.total)break;
      if(page.entries.isEmpty())throw new IOException("市场清单未读取完整，请重试");
    }
    String identity=url.substring("https://github.com/".length()),root=rawRoot(identity);
    MarketRepository metadata=new MarketRepository(new JSONObject(new String(transport.get(root+"lzpmarket.json",METADATA_LIMIT),StandardCharsets.UTF_8)));
    metadata.validateSubmission(url);
    String sourceJson=new String(MarketSourceCodec.decode(transport.get(root+metadata.sources,ENCRYPTED_LIMIT)),StandardCharsets.UTF_8);
    if(LanzouCore.parseSourceDocument(sourceJson).isEmpty())throw new IOException("仓库至少需要一个有效源");
    String readme=new String(transport.get(root+metadata.readme,README_LIMIT),StandardCharsets.UTF_8);
    if(readme.trim().isEmpty())throw new IOException("仓库 README 不能为空");
    if(!metadata.icon.isEmpty())transport.get(metadata.assetUrl(root,metadata.icon),2*1024*1024);
    if(!metadata.publisherAvatar.isEmpty())transport.get(metadata.assetUrl(root,metadata.publisherAvatar),2*1024*1024);
    JSONObject preflight=transport.post(MARKET_ORIGIN+"/api/v1/repositories/preflight",new JSONObject().put("githubUrl",url));
    String code=preflight.getString("verificationCode");
    transport.post(MARKET_ORIGIN+"/api/v1/repositories",new JSONObject().put("githubUrl",url).put("verificationCode",code).put("description",description));return url;
  }

  private static JSONObject httpPost(String endpoint,JSONObject value)throws Exception{
    HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
    connection.setConnectTimeout(12000);connection.setReadTimeout(30000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
    try{
      byte[] body=value.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);
      try(OutputStream output=connection.getOutputStream()){output.write(body);}
      int status=connection.getResponseCode();if(status<200||status>=300)throw new IOException("市场收录校验失败（HTTP "+status+"）");try(InputStream input=connection.getInputStream()){return new JSONObject(new String(bytes(input,64*1024),StandardCharsets.UTF_8));}
    }finally{connection.disconnect();}
  }

  private File cacheFile(String raw)throws Exception{return new File(directory,MarketRepository.canonicalUrl(raw).substring("https://github.com/".length()).replace('/','_')+".json");}
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
