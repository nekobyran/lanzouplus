package cc.nkbr.lanzouplus;

/**
 * {@code String.join} only exists from API 26, but the app still supports Android 7. This helper
 * keeps the same behaviour on every supported device.
 */
final class Texts {
  private Texts(){}

  static String join(CharSequence delimiter,Iterable<?> parts){
    if(parts==null)return "";
    StringBuilder builder=new StringBuilder();
    boolean first=true;
    for(Object part:parts){
      if(!first)builder.append(delimiter);
      builder.append(part==null?"":part.toString());
      first=false;
    }
    return builder.toString();
  }

  static String join(CharSequence delimiter,Object... parts){
    if(parts==null)return "";
    StringBuilder builder=new StringBuilder();
    for(int i=0;i<parts.length;i++){
      if(i>0)builder.append(delimiter);
      builder.append(parts[i]==null?"":parts[i].toString());
    }
    return builder.toString();
  }
}
