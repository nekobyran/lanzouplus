plugins { id("com.android.application") }

providers.gradleProperty("benchmarkBuildDir").orNull?.takeIf { it.isNotBlank() }?.let { layout.buildDirectory.set(file(it)) }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-g:none") }

val includeFullCatalog = providers.gradleProperty("includeFullCatalog")
    .map(String::toBoolean)
    .orElse(false)
val fullSourceCatalog = file("src/full/assets/s")
val fullRecommendationCatalog = file("src/full/assets/r")
val fullUpdateCatalog = file("src/full/assets/u")
val fullCompositeCatalog = file("src/full/assets/c")

android {
 namespace = "cc.nkbr.lanzouplus"
 compileSdk = 36
 buildFeatures { buildConfig = true; aidl = true }
 androidResources { additionalParameters += listOf("--no-xml-namespaces", "--no-compile-sdk-metadata") }
  defaultConfig { applicationId = "cc.nkbr.lanzouplus"; minSdk = 24; targetSdk = 36; versionCode = 1006000; versionName = "1.6.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
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
  create("full") {
   dimension = "catalog"
   applicationId = "cc.nkbr.lanzoumax"
   versionCode = 60
   versionName = "1.6.0"
   buildConfigField("boolean", "IS_FULL", "true")
   buildConfigField("String", "OFFICIAL_URL", "\"https://lanzoumax.nkbr.cc/\"")
   resValue("string", "app_name", "LanzouMax")
  }
 }
 buildTypes {
  getByName("debug") {
   providers.gradleProperty("benchmarkApplicationIdSuffix").orNull?.takeIf { it.isNotBlank() }?.let { applicationIdSuffix = it }
  }
  getByName("release") { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
 }
}

dependencies {
 implementation("dev.rikka.shizuku:api:13.1.5")
 implementation("dev.rikka.shizuku:provider:13.1.5")
 compileOnly("androidx.annotation:annotation:1.3.0")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

androidComponents {
 beforeVariants(selector().withFlavor("catalog" to "full")) { variant ->
  variant.enable = includeFullCatalog.get() &&
      fullSourceCatalog.isFile && fullSourceCatalog.length() > 0L &&
      fullRecommendationCatalog.isFile && fullRecommendationCatalog.length() > 0L &&
      fullUpdateCatalog.isFile && fullUpdateCatalog.length() > 0L &&
      fullCompositeCatalog.isFile && fullCompositeCatalog.length() > 0L
 }
}




