plugins { id("com.android.application") }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-g:none") }

android {
 namespace = "cc.nkbr.lanzouplus"
 compileSdk = 36
 buildFeatures { buildConfig = true; aidl = true }
 androidResources { additionalParameters += listOf("--no-xml-namespaces", "--no-compile-sdk-metadata") }
 defaultConfig { applicationId = "cc.nkbr.lanzouplus"; minSdk = 24; targetSdk = 36; versionCode = 1009008; versionName = "1.9.8alpha" }
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

dependencies {
 implementation("org.apache.commons:commons-compress:1.28.0")
 implementation("org.tukaani:xz:1.10")
 implementation("org.commonmark:commonmark:0.21.0")
 implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
 implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.21.0")
 implementation("org.commonmark:commonmark-ext-task-list-items:0.21.0")
 implementation("org.commonmark:commonmark-ext-heading-anchor:0.21.0")
 implementation("dev.rikka.shizuku:api:13.1.5")
 implementation("dev.rikka.shizuku:provider:13.1.5")
 compileOnly("androidx.annotation:annotation:1.3.0")
}