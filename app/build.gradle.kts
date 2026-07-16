plugins { id("com.android.application") }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-g:none") }

android {
 namespace = "cc.nkbr.lanzouplus"
 compileSdk = 36
 buildFeatures { buildConfig = true }
 androidResources { additionalParameters += listOf("--no-xml-namespaces", "--no-compile-sdk-metadata") }
 defaultConfig { applicationId = "cc.nkbr.lanzouplus"; minSdk = 24; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
 flavorDimensions += "catalog"
 productFlavors {
  create("empty") {
   dimension = "catalog"
   isDefault = true
   applicationId = "cc.nkbr.lanzouplus"
   buildConfigField("boolean", "IS_FULL", "false")
   buildConfigField("String", "OFFICIAL_URL", "\"https://lanzouplus.nkbr.cc/\"")
   resValue("string", "app_name", "LanzouPlus")
  }
 }
 buildTypes {
  getByName("release") { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
 }
}