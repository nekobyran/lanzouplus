buildscript {
 repositories { google(); mavenCentral() }
 dependencies { classpath("com.android.tools.build:gradle:8.11.0") }
}
allprojects {
 repositories {
  exclusiveContent {
   forRepository { maven { url = uri("https://maven.aliyun.com/repository/public") } }
   filter {
    includeGroup("org.apache.commons")
    includeGroup("org.tukaani")
    includeGroup("commons-codec")
    includeGroup("commons-io")
   }
  }
  google(); mavenCentral()
 }
}
