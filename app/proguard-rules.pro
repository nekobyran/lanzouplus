# Activity/Provider entry points are retained by the manifest-generated rules.
# Keep this file intentionally empty so R8 can rename and trim every other member.

# Shizuku instantiates this UserService by class name in a shell/root process.
-keep class cc.nkbr.lanzouplus.AdbShellService { public <init>(); public <init>(android.content.Context); *; }
