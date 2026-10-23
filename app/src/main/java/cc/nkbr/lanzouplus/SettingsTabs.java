package cc.nkbr.lanzouplus;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;

/**
 * One settings page with lazy sections and the application's shared fade motion.
 *
 * <p>Every page composes the same owner-standard rows and panels; a row appears on exactly one
 * page. The generic search/download rows used to be bundled into one {@code buildSearchTogglePanel}
 * call under "列表与外观", so download rows and search rows showed twice. Each row now lives on the
 * one page it belongs to and no panel owns a second copy of any setting.</p>
 */
final class SettingsTabs {
  private static final String[] NAMES={"列表与外观","搜索与索引","源与市场","下载","账号与权限","关于与更新"};
  private static final String INDEX_PAGE="搜索与索引";
  private final MainActivity owner;
  private final Map<String,View> pages=new LinkedHashMap<>();
  private FrameLayout stage;
  private LinearLayout tabs;
  private int transition;
  SettingsTabs(MainActivity owner){this.owner=owner;}

  void show(){
    owner.ensureAdbShellStarted();owner.primaryBase(3);owner.pageKind=4;owner.activeSource=null;owner.clearFolderTrail();owner.systemBackAction=null;owner.primaryHeader("设置");
    HorizontalScrollView scroll=new HorizontalScrollView(owner);scroll.setHorizontalScrollBarEnabled(false);
    tabs=new LinearLayout(owner);tabs.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(tabs);
    owner.root.addView(scroll,new LinearLayout.LayoutParams(-1,owner.dp(48)));
    stage=new FrameLayout(owner);owner.root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
    select(owner.selectedSettingsCategory,false);
  }

  private void select(String name,boolean animate){
    if(!Arrays.asList(NAMES).contains(name))name=NAMES[0];
    final String leaving=owner.selectedSettingsCategory,selected=name;owner.selectedSettingsCategory=selected;tabs.removeAllViews();
    for(String label:NAMES){View tab=owner.underlineFilter(label,label.equals(selected),()->select(label,true));tabs.addView(tab,new LinearLayout.LayoutParams(-2,owner.dp(46)));}
    if(INDEX_PAGE.equals(leaving)&&!INDEX_PAGE.equals(selected))releaseIndexPage();
    View next=pages.get(selected);if(next==null){next=page(selected);pages.put(selected,next);}
    if(next.getParent()==stage)return;
    final View incoming=next,previous=stage.getChildCount()==0?null:stage.getChildAt(0);int token=++transition;
    Runnable replace=()->{if(token!=transition)return;stage.removeAllViews();stage.addView(incoming,new FrameLayout.LayoutParams(-1,-1));incoming.setAlpha(animate&&owner.motionEnabled()?0f:1f);if(animate&&owner.motionEnabled())incoming.animate().alpha(1f).setDuration(180).start();};
    if(previous!=null&&animate&&owner.motionEnabled()){previous.animate().cancel();previous.animate().alpha(0f).setDuration(90).withEndAction(replace).start();}else replace.run();
  }

  /**
   * Drops the index page's view references when it leaves the stage, so the 1 s poll loop stops
   * re-arming itself while the card is hidden, and rebuilds it on return so a fresh card resumes
   * the visible polling. Only this lazy page needs the release: every other page is a plain list.
   */
  private void releaseIndexPage(){
    pages.remove(INDEX_PAGE);
    owner.indexProgressText=null;owner.indexCurrentText=null;owner.indexUpdatedText=null;
    owner.indexProgressBar=null;owner.indexControlButton=null;owner.indexFailuresLink=null;
  }

  private View page(String name){
    LinearLayout body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(owner.dp(8),owner.dp(8),owner.dp(8),owner.dp(24));
    switch(name){
      case "列表与外观":
        add(body,owner.buildUnifiedSliderPanel(owner.buildListDisplaySettings(),owner.buildCompositeHydrationSettings(),owner.buildAutoExpandPageSettings()));
        addRow(body,owner.settingsSwitchRow("浏览列表时自动翻页",owner.sessionAutoExpand,checked->{owner.sessionAutoExpand=checked;owner.persistSearchSettings();if(checked&&owner.folderPullScroll!=null)owner.folderPullScroll.post(owner::maybeAutoExpand);}));
        addRow(body,owner.settingsSwitchRow("列表源展示链接",owner.showSourceLinks,checked->{owner.showSourceLinks=checked;owner.persistSearchSettings();}));break;
      case "搜索与索引":
        add(body,owner.buildIndexProgressCard());
        add(body,owner.buildUnifiedSliderPanel(owner.buildSearchSettingsPanel(),owner.buildIndexSliderSettings()));
        addRow(body,owner.buildSearchModeRow());
        addRow(body,owner.buildHomeSearchDefaultRow());
        addRow(body,owner.buildUaSettingsRow());
        addRow(body,owner.settingsSwitchRow("蓝奏云链接：直接列表解析打开",owner.directLanzouListOpen,checked->{owner.directLanzouListOpen=checked;owner.persistSearchSettings();}));
        addRow(body,owner.buildLanzouBaseOriginRow());
        addRow(body,owner.settingsSwitchRow("公共域名族自动换线",owner.sessionLanzouTimeoutFailover,checked->{owner.sessionLanzouTimeoutFailover=checked;owner.persistSearchSettings();}));
        addRow(body,owner.settingsSwitchRow("WebView 兜底解析",owner.sessionWebViewFallback,checked->{owner.sessionWebViewFallback=checked;owner.persistSearchSettings();}));
        addRow(body,owner.settingsSwitchRow("网页外部打开",owner.sessionOpenWebExternal,checked->{owner.sessionOpenWebExternal=checked;owner.persistSearchSettings();}));
        owner.refreshIndexProgressCard();break;
      case "源与市场":
        add(body,owner.buildSourceSettingsPanel());
        addRow(body,owner.settingsSwitchRow("自动更新资源市场",owner.getSharedPreferences("market",0).getBoolean("auto_update",true),enabled->owner.getSharedPreferences("market",0).edit().putBoolean("auto_update",enabled).apply()));
        addRow(body,owner.settingsAction(R.drawable.ic_refresh,"更新资源市场",v->owner.marketPages().showMarket(true)));
        addRow(body,owner.settingsAction(R.drawable.ic_folder,"lzpmarket 仓库工程",v->owner.marketPages().showProjects()));
        addRow(body,owner.settingsAction(R.drawable.ic_add,"快捷分类",v->CategoryEditor.shortcuts(owner)));break;
      case "下载":
        add(body,owner.buildUnifiedSliderPanel(owner.buildDownloadSettingsPanel()));
        addRow(body,owner.buildDownloadPathPanel());
        addRow(body,owner.settingsSwitchRow("批量下载以单个项目显示",owner.sessionBatchDownloadSingleItem,checked->{owner.sessionBatchDownloadSingleItem=checked;owner.persistSearchSettings();if(owner.pageKind==2)owner.renderDownloads(owner.downloadQuery);}));
        addRow(body,owner.buildAutoInstallDownloadsRow());break;
      case "账号与权限":
        add(body,owner.premiumAccountCard());add(body,owner.buildAdbPermissionCard());
        add(body,owner.buildUnifiedSliderPanel(owner.buildAdbInstallThreadSettings()));
        addRow(body,owner.settingsAction(R.drawable.ic_folder,owner.getString(R.string.settings_storage_permission),v->OnboardingFlow.requestStoragePermission(owner)));
        addRow(body,owner.settingsAction(R.drawable.ic_download,owner.getString(R.string.settings_battery_permission),v->OnboardingFlow.requestBatteryPermission(owner)));break;
      default: {
        String channel=OnboardingFlow.selectedChannel(owner)==UpdateChannel.TEST?"测试版":"稳定版";
        addRow(body,valueRow(R.drawable.ic_refresh,owner.getString(R.string.settings_update_channel),channel,"更新通道，当前 "+channel+"，点击选择",v->owner.showUpdateChannelDialog()));
        addRow(body,owner.settingsAction(R.drawable.ic_refresh,"检查更新",v->owner.checkForUpdates(true)));
        addRow(body,owner.settingsAction(R.drawable.ic_copy,"关于软件",v->owner.showSoftwareDetailsDialog(false)));
        addRow(body,valueRow(R.drawable.ic_delete_file,"清理范围",owner.cacheCleanupScopeLabel(),"清理范围，当前 "+owner.cacheCleanupScopeLabel(),v->owner.showCacheCleanupScopeDialog()));
        addRow(body,owner.settingsAction(R.drawable.ic_delete_file,"清理缓存",v->owner.showCacheCleanupDialog()));break;
      }
    }
    ScrollView scroll=new ScrollView(owner);scroll.setFillViewport(true);scroll.setContentDescription("设置 · "+name);scroll.addView(body);return scroll;
  }
  private void add(LinearLayout body,View child){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=owner.dp(12);body.addView(child,params);}
  private void addRow(LinearLayout body,View child){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,owner.settingsRowHeight());params.bottomMargin=owner.dp(12);body.addView(child,params);}
  private LinearLayout valueRow(int icon,String title,String value,String description,View.OnClickListener click){
    LinearLayout row=owner.settingsRowShell();
    row.setBackground(owner.filterRipple(new ColorDrawable(Color.TRANSPARENT)));
    row.setClickable(true);
    row.setFocusable(true);
    row.setContentDescription(description);
    row.setOnClickListener(click);
    row.addView(owner.settingsLeadingIcon(icon),owner.settingsIconBox());
    row.addView(owner.settingsRowTitle(title),new LinearLayout.LayoutParams(0,-1,1));
    TextView current=owner.text(value,13,owner.PRIMARY);
    current.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    current.setPadding(owner.dp(12),0,owner.dp(4),0);
    row.addView(current,new LinearLayout.LayoutParams(-2,-1));
    return row;
  }
}
