package cc.nkbr.lanzouplus;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only bridge for Android 7-9 files saved in the public Download directory. */
public final class DownloadFileProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}
  private File resolve(Uri uri)throws FileNotFoundException{try{String name=uri.getLastPathSegment();if(name==null||name.isEmpty())throw new FileNotFoundException();File root=android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getCanonicalFile(),file=new File(root,name).getCanonicalFile();if(!root.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException();return file;}catch(IOException error){throw new FileNotFoundException(error.getMessage());}}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("read only");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
  @Override public String getType(Uri uri){String name=uri.getLastPathSegment();return name!=null&&name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")?"application/vnd.android.package-archive":"application/octet-stream";}
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){File file;try{file=resolve(uri);}catch(FileNotFoundException error){throw new IllegalArgumentException(error);}String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]=file.getName();else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file.length();}cursor.addRow(row);return cursor;}
  @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
  @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
  @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
