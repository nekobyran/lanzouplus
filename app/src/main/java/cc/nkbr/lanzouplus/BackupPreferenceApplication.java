package cc.nkbr.lanzouplus;

import android.app.Application;
import android.content.SharedPreferences;

/** Application contexts share the same preference write locks as activities and services. */
public final class BackupPreferenceApplication extends Application {
  @Override public android.content.Context createDeviceProtectedStorageContext(){
    return BackupPreferences.wrapContext(super.createDeviceProtectedStorageContext());
  }
  @Override public android.content.Context createConfigurationContext(android.content.res.Configuration configuration){
    return BackupPreferences.wrapContext(super.createConfigurationContext(configuration));
  }  @Override public SharedPreferences getSharedPreferences(String name,int mode){
    return BackupPreferences.wrap(name,super.getSharedPreferences(name,mode));
  }
}
