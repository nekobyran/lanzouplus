package cc.nkbr.lanzouplus;

/** The stable channel excludes prereleases; test includes every non-draft valid release. */
enum UpdateChannel {
  STABLE("stable"), TEST("test");

  private final String queryValue;
  UpdateChannel(String queryValue){this.queryValue=queryValue;}
  String queryValue(){return queryValue;}

  /** Existing installations remain stable unless the user explicitly selects test. */
  static UpdateChannel fromPreference(String stored){
    return "test".equals(stored)?TEST:STABLE;
  }
}
