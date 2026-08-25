package cc.nkbr.lanzouplus;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only bridge for public downloads and app-private verified update copies. */
public final class DownloadFileProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}
    private File resolve(Uri uri)throws FileNotFoundException{try{java.util.List<String> segments=uri.getPathSegments();if(segments.isEmpty()||segments.size()>2)throw new FileNotFoundException();Context context=getContext();if(context==null)throw new FileNotFoundException();if(segments.size()==2&&"shared".equals(segments.get(0))){byte[] decoded=android.util.Base64.decode(segments.get(1),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);File file=new File(new String(decoded,java.nio.charset.StandardCharsets.UTF_8)).getCanonicalFile();String path=file.getPath().replace('\\','/'),lower=path.toLowerCase(java.util.Locale.ROOT);if(!(path.startsWith("/storage/")||path.startsWith("/sdcard/"))||lower.contains("/android/data/")||lower.contains("/android/obb/")||!file.isFile())throw new FileNotFoundException();return file;}boolean verified=segments.size()==2&&"verified".equals(segments.get(0));if(segments.size()==2&&!verified)throw new FileNotFoundException();String name=segments.get(segments.size()-1);if(name.isEmpty())throw new FileNotFoundException();File root=(verified?new File(context.getFilesDir(),"verified-updates"):android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)).getCanonicalFile(),file=new File(root,name).getCanonicalFile();if(!root.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException();return file;}catch(Exception error){throw new FileNotFoundException(error.getMessage());}}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("read only");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
  @Override public String getType(Uri uri){String name=uri.getLastPathSegment();return name!=null&&name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")?"application/vnd.android.package-archive":"application/octet-stream";}
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){File file;try{file=resolve(uri);}catch(FileNotFoundException error){throw new IllegalArgumentException(error);}String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]=file.getName();else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file.length();}cursor.addRow(row);return cursor;}
  @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
  @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
  @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
