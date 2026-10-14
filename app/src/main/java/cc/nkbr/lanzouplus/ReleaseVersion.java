package cc.nkbr.lanzouplus;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric release ordering with an explicit alpha stage below its matching final release. */
final class ReleaseVersion implements Comparable<ReleaseVersion> {
  private static final Pattern FORMAT=Pattern.compile(
      "(?i)^(?:v)?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-?alpha(?:\\.(0|[1-9][0-9]*))?)?$");

  final long major,minor,patch,alphaNumber;
  final boolean alpha;

  private ReleaseVersion(long major,long minor,long patch,boolean alpha,long alphaNumber){
    this.major=major;this.minor=minor;this.patch=patch;this.alpha=alpha;this.alphaNumber=alphaNumber;
  }

  static ReleaseVersion parse(String value)throws IOException{
    String raw=value==null?"":value.trim();
    Matcher match=FORMAT.matcher(raw);
    if(!match.matches())throw new IOException("版本号格式无效: "+raw);
    try{
      long major=Long.parseLong(match.group(1));
      long minor=Long.parseLong(match.group(2));
      long patch=Long.parseLong(match.group(3));
      boolean alpha=raw.toLowerCase(java.util.Locale.ROOT).contains("alpha");
      long number=match.group(4)==null?0:Long.parseLong(match.group(4));
      return new ReleaseVersion(major,minor,patch,alpha,number);
    }catch(NumberFormatException error){throw new IOException("版本号超出范围: "+raw,error);}
  }

  boolean isAlpha(){return alpha;}

  @Override public int compareTo(ReleaseVersion other){
    int value=Long.compare(major,other.major);
    if(value==0)value=Long.compare(minor,other.minor);
    if(value==0)value=Long.compare(patch,other.patch);
    if(value!=0)return value;
    if(alpha!=other.alpha)return alpha?-1:1;
    return alpha?Long.compare(alphaNumber,other.alphaNumber):0;
  }
}
