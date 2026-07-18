package cc.nkbr.lanzouplus;

import android.os.ParcelFileDescriptor;

interface IAdbShellService {
  void destroy() = 16777114;
  String installApk(in ParcelFileDescriptor source, long size) = 1;
}
