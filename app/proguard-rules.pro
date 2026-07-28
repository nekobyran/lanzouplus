# Activity/Provider entry points are retained by the manifest-generated rules.
# Repackage and relax internal access so full-mode R8 can merge the remaining code.
-allowaccessmodification
-repackageclasses x

# Shizuku instantiates this UserService by class name in a shell/root process.
-keep class cc.nkbr.lanzouplus.AdbShellService { public <init>(); public <init>(android.content.Context); *; }
