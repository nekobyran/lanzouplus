package cc.nkbr.lanzouplus;

import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Reconstructs the same part:v1 files produced by both cloud providers. */
final class CloudFragmentTransfer {
  static CloudFilesClient.DownloadResult download(CloudFilesClient client,
      CloudFilesClient.CloudItem folder,String destination)throws CloudFilesClient.CloudException{
    CloudFilesClient.ShareInfo info=client.shareInfo(folder);
    if(!info.fragmented)throw invalid("普通文件夹不支持作为单个文件下载");
    OrdinaryCloudClient.FragmentManifest manifest=parse(info.fragmentExternalLinks);
    File target=new File(destination);
    if(target.isDirectory()||destination.endsWith(File.separator))target=new File(target,manifest.originalName);
    File parent=target.getAbsoluteFile().getParentFile();
    if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))throw invalid("无法访问下载目录");
    File temporary=new File(parent,".lzp-parts-"+UUID.randomUUID());
    if(!temporary.mkdir())throw invalid("无法创建分片下载临时目录");
    try{
      if(target.exists())throw invalid("目标文件已存在，请更换保存路径");
      Map<Integer,String> folders=new TreeMap<>();
      for(CloudFilesClient.CloudItem item:children(client,folder.id))if(item.folder){
        try{
          int index=Integer.parseInt(item.name);
          if(index>=1&&index<=manifest.partCount&&folders.put(index,item.id)!=null)
            throw invalid("分片目录存在重复片号");
        }catch(NumberFormatException ignored){}
      }
      if(folders.size()!=manifest.partCount)throw invalid("分片目录不完整");
      List<File> pieces=new ArrayList<>();
      for(int index=1;index<=manifest.partCount;index++){
        String expected=manifest.uploadFileName(index);
        CloudFilesClient.CloudItem found=null;
        for(CloudFilesClient.CloudItem item:children(client,folders.get(index)))
          if(!item.folder&&expected.equals(item.name)){
            if(found!=null)throw invalid("分片子目录中存在重复文件");
            found=item;
          }
        if(found==null)throw invalid("缺少第 "+index+" 个分片");
        File piece=new File(temporary,"piece-"+index);
        client.download(found,piece.getAbsolutePath());pieces.add(piece);
      }
      File complete=new File(temporary,"complete");
      restore(manifest,pieces,complete);
      if(target.exists())throw invalid("目标文件已存在，请更换保存路径");
      if(!complete.renameTo(target))throw invalid("无法保存合并后的文件");
      return new CloudFilesClient.DownloadResult(folder.id,target.getAbsolutePath(),target.length(),false,manifest.originalName);
    }catch(CloudFilesClient.CloudException error){throw error;}
    catch(IOException error){throw new CloudFilesClient.CloudException(CloudFilesClient.ERROR_NETWORK,"分片文件读取或保存失败",error);}
    finally{OrdinaryCloudClient.deleteTree(temporary);}
  }

  private static List<CloudFilesClient.CloudItem> children(CloudFilesClient client,String id)
      throws CloudFilesClient.CloudException{
    List<CloudFilesClient.CloudItem> items=new ArrayList<>();
    for(int page=1;;page++){
      CloudFilesClient.CloudPage result=client.listFolder(id,page,50);items.addAll(result.items);
      if(!result.hasMore)return items;
    }
  }

  static OrdinaryCloudClient.FragmentManifest parse(String links)throws CloudFilesClient.CloudException{
    OrdinaryCloudClient.FragmentManifest manifest=null;Set<Integer> slots=new TreeSet<>();
    try{
      for(String line:links.split("\\r?\\n")){
        if(line.trim().isEmpty())continue;
        String[] fields=line.trim().split(":",9);
        if(fields.length!=9||!"part".equals(fields[0])||!"v1".equals(fields[1]))throw invalid("分片外链格式无效");
        if(!fields[4].matches("[01]+")||!fields[5].matches("[01]+"))throw invalid("分片片号无效");
        int index=Integer.parseInt(fields[4],2),total=Integer.parseInt(fields[5],2);
        long bytes=Long.parseLong(fields[6]);
        String name=new String(Base64.decode(fields[7],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8);
        if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.indexOf('\0')>=0)
          throw invalid("分片原文件名无效");
        String mode="b".equals(fields[2])?"byte_split":"z".equals(fields[2])?"archive_volume":"";
        if(mode.isEmpty()||(!"f".equals(fields[3])&&!"a".equals(fields[3])))throw invalid("分片模式无效");
        int width=OrdinaryCloudClient.binaryWidth(total);
        if(total<2||total>4096||bytes<=0||index<1||index>total||fields[4].length()!=width||fields[5].length()!=width)
          throw invalid("分片参数无效");
        OrdinaryCloudClient.FragmentManifest current=new OrdinaryCloudClient.FragmentManifest(mode,name,bytes,total,width,"a".equals(fields[3]));
        if(manifest==null)manifest=current;
        else if(!manifest.descriptionMarker().equals(current.descriptionMarker()))throw invalid("分片来自不同文件");
        if(!slots.add(index))throw invalid("分片外链包含重复片号");
      }
    }catch(CloudFilesClient.CloudException error){throw error;}
    catch(RuntimeException error){throw invalid("分片外链参数无法解析");}
    if(manifest==null||slots.size()!=manifest.partCount)throw invalid("分片外链不完整");
    return manifest;
  }

  static void restore(OrdinaryCloudClient.FragmentManifest manifest,List<File> pieces,File target)
      throws IOException,CloudFilesClient.CloudException{
    if(pieces.size()!=manifest.partCount)throw invalid("分片数量不完整");
    if(target.exists())throw invalid("目标文件已存在");
    File joined="byte_split".equals(manifest.mode)?target:new File(target.getParentFile(),target.getName()+".volume");
    if(joined.exists())throw invalid("分卷临时文件已存在");
    try{
      try(OutputStream output=new FileOutputStream(joined)){
        for(File piece:pieces)try(InputStream input=new FileInputStream(piece)){copy(input,output);}
      }
      if("archive_volume".equals(manifest.mode)){
        if(manifest.archiveInput){
          if(!joined.renameTo(target))throw new IOException("archive move failed");
        }else try(ZipFile zip=new ZipFile(joined)){
          ZipEntry entry=zip.getEntry(manifest.originalName);
          if(entry==null||entry.isDirectory())throw invalid("分卷中缺少原文件");
          try(InputStream input=zip.getInputStream(entry);OutputStream output=new FileOutputStream(target)){copy(input,output);}
        }
      }
      if(("byte_split".equals(manifest.mode)||!manifest.archiveInput)&&target.length()!=manifest.originalBytes){
        target.delete();throw invalid("合并后的文件长度与分片记录不一致");
      }
    }catch(IOException|CloudFilesClient.CloudException error){target.delete();throw error;}
    finally{if(!joined.equals(target))joined.delete();}
  }

  private static void copy(InputStream input,OutputStream output)throws IOException{
    byte[] buffer=new byte[64*1024];int size;
    while((size=input.read(buffer))!=-1)output.write(buffer,0,size);
  }
  private static CloudFilesClient.CloudException invalid(String message){
    return new CloudFilesClient.CloudException(CloudFilesClient.ERROR_INVALID,message);
  }
}
