package cc.nkbr.lanzouplus;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Restores owned, published market repositories as editable local projects. */
final class GithubMarketProjectDiscovery {
  private static final Object RUN_LOCK=new Object();
  private static final Set<String> attempted=new HashSet<>();
  private static final Map<String,List<Runnable>> running=new HashMap<>();
  private static final Pattern MARKDOWN_IMAGE=Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)");
  private static final Pattern HTML_IMAGE=Pattern.compile("<img[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']");
  private final Context context;
  private final GithubAccountClient account;
  private final File projects,cache;
  private final MarketRepositoryClient.Transport transport;

  static final class Result {
    int found,added,kept,failed;
    String message(){return "GitHub 工程查找完成：新增 "+added+" 个，保留 "+kept+" 个"+(failed==0?"":"，验证失败 "+failed+" 个");}
  }

  GithubMarketProjectDiscovery(Context context,GithubAccountClient account,File projects,File cache,
      MarketRepositoryClient.Transport transport){
    this.context=context.getApplicationContext();this.account=account;
    this.projects=projects;this.cache=cache;this.transport=transport;
  }

  /** One automatic attempt per account/process; explicit rescan and fresh login run again. */
  static void start(MainActivity owner,boolean force,Runnable finished){
    GithubAccountClient account=GithubAccountClient.production(owner);
    owner.io.execute(()->{
      String key="";boolean ownsRun=false;
      try{
        GithubAccountClient.Profile profile=account.cachedProfile();
        if(profile==null||!account.hasUsableSession()){
          if(force)owner.runOnUiThread(()->{if(!owner.activityClosing())owner.showNotice("请先登录 GitHub，再查找仓库工程",true);});
          return;
        }
        key=profile.login.toLowerCase(Locale.ROOT);
        synchronized(RUN_LOCK){
          List<Runnable> listeners=running.get(key);
          if(listeners!=null){if(finished!=null)listeners.add(finished);return;}
          if(!force&&attempted.contains(key))return;
          attempted.add(key);listeners=new ArrayList<>();if(finished!=null)listeners.add(finished);
          running.put(key,listeners);ownsRun=true;
        }
        GithubMarketProjectDiscovery discovery=new GithubMarketProjectDiscovery(owner,account,
            new File(owner.getFilesDir(),"market-projects"),
            new File(owner.getCacheDir(),"github-project-discovery"),MarketRepositoryClient::httpGet);
        Result result=discovery.scan(profile.login);
        owner.runOnUiThread(()->{if(!owner.activityClosing())owner.showNotice(result.message(),result.failed>0);});
      }catch(Exception error){
        owner.runOnUiThread(()->{if(!owner.activityClosing())owner.showNotice("GitHub 工程查找失败："+owner.friendlyError(error),true);});
      }finally{
        if(ownsRun){
          List<Runnable> listeners;
          synchronized(RUN_LOCK){listeners=running.remove(key);}
          if(listeners!=null)for(Runnable callback:listeners)owner.runOnUiThread(callback);
        }
      }
    });
  }

  Result scan(String login)throws Exception{
    Result result=new Result();Set<Long> seen=new HashSet<>();
    for(int page=1;;page++){
      JSONArray rows=account.apiArrayForAccount(login,
          "/user/repos?affiliation=owner&visibility=public&per_page=100&page="+page);
      for(int i=0;i<rows.length();i++){
        JSONObject repo=rows.optJSONObject(i);if(!candidate(repo,login))continue;
        long id=repo.getLong("id");if(!seen.add(id))continue;
        account.whileSignedIn(login,()->{});
        result.found++;
        try{
          importRepository(login,repo,result);
        }catch(Exception failure){
          account.whileSignedIn(login,()->{});
          result.failed++;
        }
      }
      if(rows.length()<100)return result;
    }
  }

  private static boolean candidate(JSONObject repo,String login){
    if(repo==null||repo.optBoolean("private",true)||repo.optLong("id")<=0)return false;
    JSONObject owner=repo.optJSONObject("owner");
    String name=repo.optString("name");
    return owner!=null&&login.equalsIgnoreCase(owner.optString("login"))
        &&name.startsWith("lzpmarket-")&&name.length()>10;
  }

  private void importRepository(String login,JSONObject repo,Result result)throws Exception{
    String url=MarketRepository.canonicalUrl(repo.getString("html_url"));
    String owner=repo.getJSONObject("owner").getString("login"),name=repo.getString("name");
    if(!url.equalsIgnoreCase("https://github.com/"+owner+"/"+name))throw new IOException("GitHub 仓库信息不一致");
    MarketRepositoryClient.Document document=new MarketRepositoryClient(cache,transport).load(url,false);
    document.metadata.validateSubmission(url);
    LinkedHashSet<String> managed=new LinkedHashSet<>();
    managed.add(MarketProject.METADATA_ENTRY);managed.add(document.metadata.sources);managed.add(document.metadata.readme);
    JSONObject metadata=new JSONObject(document.metadata.json.toString());
    for(String key:new String[]{"icon","publisherAvatar"}){
      String image=metadata.optString(key);
      if(!image.isEmpty())metadata.put(key,imageUrl(document.rawRoot,image,document.rawRoot,managed));
    }
    String readme=rewriteImages(document.readme,document.rawRoot+document.metadata.readme,document.rawRoot,managed);
    metadata.put("readme","README.md").put("sources",MarketProject.SOURCES_ENTRY).put("repositoryUrl",url);
    MarketProject imported=MarketProject.create(metadata,document.sourceJson,readme);
    imported.resolveAssets(reference->transport.get(reference,MarketRepositoryClient.README_LIMIT));
    GithubProjectPublisher.Association binding=new GithubProjectPublisher.Association(url,owner,name,
        repo.getString("default_branch"),repo.getLong("id"),managed,true);
    account.whileSignedIn(login,()->{
      MarketProject existing=findExisting(login,url,binding.repositoryId);
      if(existing!=null){
        GithubProjectPublisher.Association before=GithubProjectPublisher.loadAssociation(context,login,existing.id);
        GithubProjectPublisher.Association linked=before!=null&&before.repositoryId==binding.repositoryId
            ?new GithubProjectPublisher.Association(url,owner,name,binding.defaultBranch,binding.repositoryId,before.managedFiles,true)
            :binding;
        GithubProjectPublisher.saveAssociation(context,login,existing.id,linked);
        String oldUrl=existing.metadata.optString("repositoryUrl","");
        if(!oldUrl.isEmpty()&&!oldUrl.equals(url)){
          existing.metadata.put("repositoryUrl",url);existing.save(new File(projects,existing.id+".json"));
        }
        result.kept++;
        return;
      }
      File file=new File(projects,imported.id+".json");
      imported.save(file);
      try{GithubProjectPublisher.saveAssociation(context,login,imported.id,binding);}
      catch(Exception error){file.delete();throw error;}
      result.added++;
    });
  }

  private MarketProject findExisting(String login,String url,long repositoryId)throws Exception{
    File[] files=projects.listFiles((directory,name)->name.endsWith(".json"));
    if(files==null)return null;
    for(File file:files){
      MarketProject project=MarketProject.load(file);
      GithubProjectPublisher.Association binding=GithubProjectPublisher.loadAssociation(context,login,project.id);
      if(binding!=null&&(binding.repositoryId==repositoryId||binding.repositoryUrl.equalsIgnoreCase(url)))return project;
      String linked=project.metadata.optString("repositoryUrl","");
      if(!linked.isEmpty()&&MarketRepository.canonicalUrl(linked).equalsIgnoreCase(url))return project;
    }
    return null;
  }

  private static String rewriteImages(String text,String base,String root,Set<String> managed)throws Exception{
    for(Pattern pattern:new Pattern[]{MARKDOWN_IMAGE,HTML_IMAGE}){
      Matcher matcher=pattern.matcher(text);StringBuilder rewritten=new StringBuilder();int cursor=0;
      while(matcher.find()){
        rewritten.append(text,cursor,matcher.start(1));
        rewritten.append(imageUrl(base,matcher.group(1),root,managed));cursor=matcher.end(1);
      }
      rewritten.append(text,cursor,text.length());text=rewritten.toString();
    }
    return text;
  }

  private static String imageUrl(String base,String image,String root,Set<String> managed)throws Exception{
    URL resolved=new URL(new URL(base),image);
    if(!"https".equals(resolved.getProtocol()))throw new IOException("工程图片必须使用 HTTPS");
    String url=resolved.toString();
    if(url.startsWith(root)){
      String path=url.substring(root.length()).split("[?#]",2)[0];
      managed.add(MarketRepository.path(path));
    }
    return url;
  }
}
