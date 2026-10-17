package cc.nkbr.lanzouplus;

import android.view.Gravity;
import android.widget.*;

/** Shared secondary source page for GitHub subscriptions and local collections. */
final class SourceSecondaryPage {
  static final class Identity {
    final ImageView icon,avatar;
    final TextView text;
    Identity(ImageView icon,ImageView avatar,TextView text){this.icon=icon;this.avatar=avatar;this.text=text;}
  }
  static Identity begin(MainActivity owner,Models.Source source,int pageKind,Runnable back,Runnable refresh){
    owner.pageDirection=0;owner.base();owner.pageKind=pageKind;owner.primaryHeader(source.title);
    ImageButton previous=owner.iconButton(R.drawable.ic_back,"返回");previous.setOnClickListener(v->back.run());
    owner.pageHeaderRow.addView(previous,0,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(48)));
    ImageButton reload=owner.iconButton(R.drawable.ic_refresh,"刷新当前源");reload.setOnClickListener(v->refresh.run());
    owner.pageHeaderRow.addView(reload,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(48)));
    LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(owner.dp(8),owner.dp(4),owner.dp(8),owner.dp(8));
    ImageView icon=new ImageView(owner);icon.setScaleType(ImageView.ScaleType.CENTER_CROP);icon.setImageResource(R.drawable.ic_folder);
    row.addView(icon,new LinearLayout.LayoutParams(owner.dp(44),owner.dp(44)));
    ImageView avatar=new ImageView(owner);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);avatar.setImageResource(R.drawable.ic_globe);avatar.setColorFilter(owner.MUTED);
    LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(owner.dp(28),owner.dp(28));avatarParams.leftMargin=owner.dp(10);row.addView(avatar,avatarParams);
    TextView text=owner.text("",12,owner.MUTED);text.setPadding(owner.dp(10),0,0,0);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
    Identity result=new Identity(icon,avatar,text);update(owner,result,source.title,source.publisher,source.repositoryVersion,source.description,source.iconUrl,source.avatarUrl);
    owner.root.addView(row,new LinearLayout.LayoutParams(-1,-2));return result;
  }
  static void update(MainActivity owner,Identity identity,String title,String publisher,String version,String description,String icon,String avatar){
    identity.text.setText((publisher.isEmpty()?title:publisher)+(version.isEmpty()?"":" · "+version)+(description.isEmpty()?"":"\n"+description));
    if(!icon.isEmpty())owner.loadImage(icon,identity.icon);
    if(!avatar.isEmpty())owner.loadProfileImage(avatar,identity.avatar);
  }
}
