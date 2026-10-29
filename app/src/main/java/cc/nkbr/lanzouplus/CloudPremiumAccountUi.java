package cc.nkbr.lanzouplus;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Premium account management using the native Android controls and the existing protocol. */
final class CloudPremiumAccountUi {
  interface Factory {
    CloudPremiumAccountProtocol.Account open() throws CloudFilesClient.CloudException;
  }

  private enum Section {
    PROFILE("个人资料与容量"),
    PASSWORD("修改密码"),
    MEMBERSHIP("会员套餐与充值"),
    ORDERS("订单记录");

    final String title;
    Section(String title) { this.title = title; }
  }

  private static final int ORDER_PAGE_SIZE = 20;
  private static final String[] CAPACITY_KEYS = {
      "usedSize", "totalSize", "vipSize", "rewardSize"
  };

  private final MainActivity owner;
  private final Factory factory;
  private final Runnable passwordChanged;
  private final List<View> controls = new ArrayList<>();
  private final List<View> previouslyEnabled = new ArrayList<>();
  private final List<EditText> passwordInputs = new ArrayList<>();

  private AlertDialog dialog;
  private LinearLayout body;
  private CloudPremiumAccountProtocol.Account account;
  private Section section;
  private int generation;
  private int orderPage = 1;
  private boolean busy;
  private boolean confirmingOrder;

  private CloudPremiumAccountUi(MainActivity owner, Factory factory, Runnable passwordChanged) {
    this.owner = owner;
    this.factory = factory;
    this.passwordChanged = passwordChanged;
  }

  static void choose(MainActivity owner, Factory factory, Runnable passwordChanged) {
    Section[] sections = Section.values();
    String[] labels = new String[sections.length];
    for (int i = 0; i < sections.length; i++) labels[i] = sections[i].title;
    AlertDialog picker = new AlertDialog.Builder(owner)
        .setTitle("优享账号管理")
        .setItems(labels, (d, which) ->
            new CloudPremiumAccountUi(owner, factory, passwordChanged).show(sections[which]))
        .setNegativeButton("取消", null)
        .create();
    owner.showRounded(picker);
  }

  private void show(Section selected) {
    section = selected;
    body = new LinearLayout(owner);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(owner.dp(18), owner.dp(6), owner.dp(18), owner.dp(8));

    ScrollView scroll = new ScrollView(owner);
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    LinearLayout panel = new LinearLayout(owner);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1,
        Math.max(owner.dp(160), Math.min(owner.dp(460),
            owner.safeContentHeight() - owner.dp(180)))));

    dialog = new AlertDialog.Builder(owner)
        .setTitle(section.title)
        .setView(panel)
        .setNegativeButton("关闭", null)
        .setNeutralButton("刷新", null)
        .create();
    dialog.setOnDismissListener(d -> {
      generation++;
      dropPasswordInputs();
      controls.clear();
      previouslyEnabled.clear();
      account = null;
    });
    owner.showRounded(dialog);
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> read());
    read();
  }

  private boolean current(int request) {
    return !owner.activityClosing() && dialog != null && dialog.isShowing()
        && generation == request;
  }

  private void enabled(boolean enabled) {
    busy = !enabled;
    if(enabled){
      for(View control:previouslyEnabled)control.setEnabled(true);
      previouslyEnabled.clear();
    }else{
      previouslyEnabled.clear();
      for(View control:controls){
        if(control.isEnabled())previouslyEnabled.add(control);
        control.setEnabled(false);
      }
    }
    if (dialog != null && dialog.isShowing())
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(enabled);
  }

  /** Opens a single account-bound handle and reads only the selected official section. */
  private void read() {
    if (busy) return;
    final int request = ++generation;
    final Section requested = section;
    enabled(false);
    dropPasswordInputs();
    controls.clear();
    body.removeAllViews();
    body.addView(owner.text("正在读取…", 13, owner.MUTED));

    owner.io.execute(() -> {
      CloudPremiumAccountProtocol.Account opened = account;
      JSONObject result = null;
      JSONObject related = null;
      String error = "";
      try {
        if (opened == null) opened = factory.open();
        switch (requested) {
          case PROFILE:
            result = opened.profile();
            break;
          case PASSWORD:
            result = new JSONObject();
            break;
          case MEMBERSHIP:
            result = opened.vipPackages();
            related = opened.paymentWays();
            break;
          case ORDERS:
            result = opened.orderHistory(orderPage, ORDER_PAGE_SIZE);
            break;
        }
      } catch (Exception failure) {
        error = message(failure);
      }
      final CloudPremiumAccountProtocol.Account handle = opened;
      final JSONObject data = result;
      final JSONObject auxiliary = related;
      final String problem = error;
      owner.runOnUiThread(() -> {
        if (!current(request)) return;
        account = handle;
        enabled(true);
        body.removeAllViews();
        if (!problem.isEmpty()) {
          body.addView(owner.text(problem, 13, owner.TEXT));
          if (requested == Section.ORDERS && orderPage > 1) {
            Button previous = action("返回上一页");
            previous.setOnClickListener(v -> {
              orderPage--;
              read();
            });
          }
          return;
        }
        switch (requested) {
          case PROFILE:
            renderProfile(data);
            break;
          case PASSWORD:
            renderPassword();
            break;
          case MEMBERSHIP:
            renderMembership(data, auxiliary);
            break;
          case ORDERS:
            renderOrders(data);
            break;
        }
      });
    });
  }

  private void renderProfile(JSONObject data) {
    JSONObject profile = data.optJSONObject("profile");
    JSONObject capacity = data.optJSONObject("capacity");
    if (profile == null || capacity == null) {
      body.addView(owner.text("官方资料响应缺少数据", 13, owner.TEXT));
      return;
    }

    addSection("账号资料");
    int rows = 0;
    if (profile.has("userName")) {
      addFact("昵称", scalar(profile.opt("userName")));
      rows++;
    }
    Iterator<String> keys = profile.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if ("userName".equals(key) || sensitive(key)) continue;
      rows += addValue(label(key), profile.opt(key), 0);
    }
    if (rows == 0) addHint("官方没有返回可显示的账号资料。");

    addSection("容量信息");
    rows = 0;
    for (String key : CAPACITY_KEYS) {
      if (!capacity.has(key)) continue;
      addFact(label(key), sizeText(capacity.opt(key)));
      rows++;
    }
    keys = capacity.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (knownCapacity(key) || sensitive(key)) continue;
      rows += addValue(label(key), capacity.opt(key), 0);
    }
    if (rows == 0) addHint("官方没有返回可显示的容量数据。");

    addSection("修改昵称");
    addHint("昵称须为 2 至 24 个字符。保存后重新读取官方资料。");
    EditText nickname = textInput("昵称", InputType.TYPE_CLASS_TEXT);
    nickname.setText(profile.optString("userName", ""));
    Button save = action("保存昵称");
    save.setOnClickListener(v -> {
      String value = nickname.getText().toString();
      if (CloudPremiumAccountProtocol.trimWhitespace(value).codePointCount(
          0, CloudPremiumAccountProtocol.trimWhitespace(value).length()) < 2) {
        nickname.setError("昵称至少需要 2 个字符");
        return;
      }
      write(handle -> handle.setNickname(value), result -> {
        owner.showNotice("昵称已保存", false);
        read();
      });
    });
  }

  private void renderPassword() {
    addHint("新密码需要 6 至 18 个字符且不含空白字符。修改成功后将更新本机凭据并使旧会话失效。");
    EditText oldPassword = passwordInput("当前密码");
    EditText newPassword = passwordInput("新密码");
    EditText confirmation = passwordInput("确认新密码");
    Button save = action("修改密码");
    save.setOnClickListener(v -> {
      String oldValue = oldPassword.getText().toString();
      String nextValue = newPassword.getText().toString();
      String confirmationValue = confirmation.getText().toString();
      if (!nextValue.equals(confirmationValue)) {
        confirmation.setError("两次输入的新密码不一致");
        return;
      }
      clearPasswords();
      write(handle -> handle.changePassword(oldValue, nextValue, confirmationValue), result -> {
        owner.showNotice("密码已修改，请使用新密码重新登录", false);
        dialog.dismiss();
        passwordChanged.run();
      });
    });
  }

  private void renderMembership(JSONObject packages, JSONObject paymentWays) {
    JSONArray tiers = packages.optJSONArray("list");
    JSONArray ways = paymentWays.optJSONArray("list");
    if (tiers == null || ways == null) {
      body.addView(owner.text("会员套餐或支付方式响应缺少列表", 13, owner.TEXT));
      return;
    }
    List<Choice> offers = new ArrayList<>();
    for (int i = 0; i < tiers.length(); i++) {
      JSONObject tier = tiers.optJSONObject(i);
      JSONArray options = tier == null ? null : tier.optJSONArray("packages");
      if (options == null) continue;
      for (int j = 0; j < options.length(); j++) {
        JSONObject option = options.optJSONObject(j);
        if (option == null) continue;
        String id = identifier(option.opt("pkgId"));
        Object price = option.opt("price");
        if (id.isEmpty() || !(price instanceof Number)) continue;
        String title = option.optString("pkgName", "");
        if (title.isEmpty()) title = "套餐 " + id;
        offers.add(new Choice(id, title + " · ¥" + priceText((Number) price)));
      }
    }
    List<Choice> methods = new ArrayList<>();
    for (int i = 0; i < ways.length(); i++) {
      JSONObject way = ways.optJSONObject(i);
      if (way == null) continue;
      String id = identifier(way.opt("wayId"));
      if (id.isEmpty()) continue;
      String title = way.optString("name", "");
      methods.add(new Choice(id, title.isEmpty() ? "方式 " + id : title));
    }

    addSection("会员套餐");
    addHint("价格由官方接口返回，创建订单时会重新读取最新报价。");
    if (offers.isEmpty()) {
      addHint("当前没有可购买的会员套餐。");
      return;
    }
    final Choice[] selectedOffer = {null};
    Button pickOffer = action("选择会员套餐");
    pickOffer.setOnClickListener(v -> select("选择会员套餐", offers, selectedOffer,
        pickOffer, "会员套餐"));

    addSection("支付方式");
    if (methods.isEmpty()) {
      addHint("当前没有可用的支付方式。");
      return;
    }
    final Choice[] selectedMethod = {null};
    Button pickMethod = action("选择支付方式");
    pickMethod.setOnClickListener(v -> select("选择支付方式", methods, selectedMethod,
        pickMethod, "支付方式"));

    addHint("下单前会再次确认套餐与金额；订单生成后由外部支付入口完成付款。");
    Button create = action("创建支付订单");
    create.setOnClickListener(v -> {
      if (selectedOffer[0] == null) {
        owner.showNotice("请先选择会员套餐", true);
        return;
      }
      if (selectedMethod[0] == null) {
        owner.showNotice("请先选择支付方式", true);
        return;
      }
      confirmOrder(selectedOffer[0], selectedMethod[0]);
    });
  }

  private void select(String title, List<Choice> options, Choice[] selection,
                      Button button, String prefix) {
    if (busy) return;
    String[] labels = new String[options.size()];
    int selected = -1;
    for (int i = 0; i < options.size(); i++) {
      labels[i] = options.get(i).label;
      if (selection[0] != null && selection[0].id.equals(options.get(i).id)) selected = i;
    }
    AlertDialog picker = new AlertDialog.Builder(owner)
        .setTitle(title)
        .setSingleChoiceItems(labels, selected, (d, which) -> {
          selection[0] = options.get(which);
          button.setText(prefix + "：" + selection[0].label);
          button.setContentDescription(prefix + "，" + selection[0].label);
          d.dismiss();
        })
        .setNegativeButton("取消", null)
        .create();
    owner.showRounded(picker);
  }

  /** The confirmation is the only path that can start the non-repeatable order submission. */
  private void confirmOrder(Choice offer, Choice method) {
    if (busy || confirmingOrder) return;
    confirmingOrder = true;
    AlertDialog confirm = new AlertDialog.Builder(owner)
        .setTitle("确认创建支付订单")
        .setMessage("会员套餐：" + offer.label + "\n支付方式：" + method.label
            + "\n\n确认后将向官方接口创建一个新订单。")
        .setNegativeButton("取消", null)
        .setPositiveButton("确认创建", (d, which) ->
            write(handle -> handle.createOrder(offer.id, method.id), checkout -> {
              dialog.dismiss();
              CloudPaymentUi.show(owner, checkout);
            }))
        .create();
    confirm.setOnDismissListener(d -> confirmingOrder = false);
    owner.showRounded(confirm);
  }

  private void renderOrders(JSONObject history) {
    JSONArray orders = history.optJSONArray("list");
    if (orders == null) {
      body.addView(owner.text("订单响应缺少列表", 13, owner.TEXT));
      return;
    }
    addSection("第 " + orderPage + " 页 · 每页 " + ORDER_PAGE_SIZE + " 条");
    addHint("订单状态仅在点击查询后读取；不会自动重复请求。");
    if (orders.length() == 0) {
      addHint("本页没有订单记录。");
    }
    for (int i = 0; i < orders.length(); i++) {
      JSONObject order = orders.optJSONObject(i);
      if (order == null) {
        addHint("订单 " + (i + 1) + " 的数据格式异常。");
        continue;
      }
      String id = identifier(order.opt("orderId"));
      addSection(id.isEmpty() ? "订单 " + (i + 1) : "订单 " + id);
      Iterator<String> keys = order.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (sensitive(key)) continue;
        addValue(label(key), order.opt(key), 0);
      }
      if (!id.isEmpty()) {
        Button query = action("查询此订单支付状态");
        query.setContentDescription("查询订单 " + id + " 的支付状态");
        query.setOnClickListener(v -> queryOrder(id));
      }
    }

    LinearLayout pager = new LinearLayout(owner);
    pager.setOrientation(LinearLayout.HORIZONTAL);
    pager.setGravity(Gravity.CENTER_VERTICAL);
    Button previous = owner.toolbarTextButton("上一页");
    Button next = owner.toolbarTextButton("下一页");
    previous.setEnabled(orderPage > 1);
    next.setEnabled(orders.length() == ORDER_PAGE_SIZE);
    controls.add(previous);
    controls.add(next);
    pager.addView(previous, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
    pager.addView(next, new LinearLayout.LayoutParams(0, owner.dp(48), 1));
    body.addView(pager);
    previous.setOnClickListener(v -> {
      if (orderPage > 1) {
        orderPage--;
        read();
      }
    });
    next.setOnClickListener(v -> {
      orderPage++;
      read();
    });

    addSection("按订单编号查询");
    EditText orderId = textInput("订单编号", InputType.TYPE_CLASS_TEXT);
    Button query = action("查询支付状态");
    query.setOnClickListener(v -> {
      String id = orderId.getText().toString().trim();
      if (id.isEmpty()) {
        orderId.setError("请输入订单编号");
        return;
      }
      queryOrder(id);
    });
  }

  private void queryOrder(String orderId) {
    write(handle -> handle.queryOrder(orderId), result ->
        owner.showNotice(result.optBoolean("paid", false)
            ? "官方已确认该订单支付成功" : "该订单尚未支付成功", false));
  }

  private void write(AccountWork work, ResultHandler success) {
    if (busy || account == null) return;
    final CloudPremiumAccountProtocol.Account handle = account;
    final int request = ++generation;
    enabled(false);
    owner.io.execute(() -> {
      JSONObject result = null;
      String error = "";
      try {
        result = work.run(handle);
      } catch (Exception failure) {
        error = message(failure);
      }
      final JSONObject done = result;
      final String problem = error;
      owner.runOnUiThread(() -> {
        if (!current(request)) return;
        enabled(true);
        if (!problem.isEmpty()) {
          owner.showNotice(problem, true);
          return;
        }
        success.accept(done);
      });
    });
  }

  private String message(Exception failure) {
    if (failure instanceof CloudFilesClient.CloudException
        && failure.getMessage() != null && !failure.getMessage().trim().isEmpty())
      return failure.getMessage().trim();
    return owner.friendlyError(failure);
  }

  private void addSection(String title) {
    TextView label = owner.text(title, 14, owner.TEXT);
    label.setTypeface(Typeface.DEFAULT_BOLD);
    label.setPadding(0, owner.dp(14), 0, owner.dp(6));
    body.addView(label, new LinearLayout.LayoutParams(-1, -2));
  }

  private void addHint(String text) {
    TextView note = owner.text(text, 12, owner.MUTED);
    note.setPadding(0, owner.dp(4), 0, owner.dp(8));
    body.addView(note, new LinearLayout.LayoutParams(-1, -2));
  }

  private void addFact(String title, String value) {
    TextView row = owner.text(title + "\n" + value, 13, owner.TEXT);
    row.setPadding(0, owner.dp(6), 0, owner.dp(6));
    row.setTextIsSelectable(true);
    body.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  /** Shows the actual reply fields, excluding credential material and opaque objects. */
  private int addValue(String key, Object value, int depth) {
    if (value instanceof JSONObject && depth < 2) {
      int rows = 0;
      JSONObject nested = (JSONObject) value;
      Iterator<String> keys = nested.keys();
      while (keys.hasNext()) {
        String child = keys.next();
        if (sensitive(child)) continue;
        rows += addValue(key + " / " + label(child), nested.opt(child), depth + 1);
      }
      return rows;
    }
    if (value instanceof JSONArray) {
      JSONArray list = (JSONArray) value;
      int rows = 0;
      for (int i = 0; i < list.length(); i++)
        rows += addValue(key + " " + (i + 1), list.opt(i), depth + 1);
      return rows;
    }
    if (value instanceof JSONObject) return 0;
    addFact(key, scalar(value));
    return 1;
  }

  private EditText textInput(String label, int type) {
    EditText input = owner.sourceInput(label, type);
    input.setSingleLine(true);
    input.setContentDescription(label);
    body.addView(input, new LinearLayout.LayoutParams(-1, owner.dp(52)));
    controls.add(input);
    return input;
  }

  private EditText passwordInput(String label) {
    addHint(label);
    EditText input = textInput(label,
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    input.setSaveEnabled(false);
    passwordInputs.add(input);
    return input;
  }

  private void clearPasswords() {
    for (EditText field : passwordInputs) field.setText("");
  }

  private void dropPasswordInputs() {
    clearPasswords();
    passwordInputs.clear();
  }

  private Button action(String label) {
    Button button = owner.toolbarTextButton(label);
    button.setContentDescription(label);
    body.addView(button, new LinearLayout.LayoutParams(-1, owner.dp(48)));
    controls.add(button);
    return button;
  }

  private static boolean knownCapacity(String key) {
    for (String expected : CAPACITY_KEYS) if (expected.equals(key)) return true;
    return false;
  }

  private static boolean sensitive(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.contains("password") || lower.contains("passwd")
        || lower.contains("pwd") || lower.contains("token")
        || lower.contains("cookie") || lower.contains("secret")
        || lower.contains("session") || lower.contains("authorization")
        || lower.contains("accesskey") || lower.contains("apikey")
        || lower.contains("signature") || lower.equals("auth")
        || lower.equals("sign") || lower.equals("k3")
        || lower.equals("vei") || lower.equals("formhash")
        || lower.equals("api_key");
  }

  private static String label(String key) {
    switch (key) {
      case "userName": return "昵称";
      case "userId": return "账号编号";
      case "usedSize": return "已使用空间";
      case "totalSize": return "总空间";
      case "vipSize": return "会员空间";
      case "rewardSize": return "奖励空间";
      case "orderId": return "订单编号";
      case "pkgId": return "套餐编号";
      case "pkgName": return "套餐名称";
      case "wayId": return "支付方式编号";
      case "price": return "价格";
      case "amt": return "金额";
      default: return key;
    }
  }

  private static String scalar(Object value) {
    if (value == null || value == JSONObject.NULL) return "—";
    if (value instanceof Boolean) return ((Boolean) value) ? "是" : "否";
    return String.valueOf(value);
  }

  private static String identifier(Object value) {
    return value instanceof Number || value instanceof String ? value.toString() : "";
  }

  private static String priceText(Number price) {
    return new BigDecimal(price.toString()).stripTrailingZeros().toPlainString();
  }

  private static String sizeText(Object value) {
    if (!(value instanceof Number)) return scalar(value);
    double bytes = ((Number) value).doubleValue();
    if (bytes < 0 || Double.isNaN(bytes) || Double.isInfinite(bytes))
      return scalar(value);
    String[] units = {"字节", "KB", "MB", "GB", "TB", "PB"};
    int index = 0;
    while (bytes >= 1024 && index < units.length - 1) {
      bytes /= 1024;
      index++;
    }
    if (index == 0) return scalar(value) + " 字节";
    return String.format(Locale.CHINA, "%.2f %s", bytes, units[index]);
  }

  private static final class Choice {
    final String id;
    final String label;
    Choice(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  private interface AccountWork {
    JSONObject run(CloudPremiumAccountProtocol.Account handle)
        throws CloudFilesClient.CloudException;
  }

  private interface ResultHandler {
    void accept(JSONObject result);
  }
}
