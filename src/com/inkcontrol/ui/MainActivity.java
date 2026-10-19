package com.inkcontrol.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.BatteryManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.core.FeatureService;
import com.inkcontrol.core.DisplayAlgorithms;
import com.inkcontrol.features.desktop.DesktopPreview;
import com.inkcontrol.features.lock.LockPreview;
import com.inkcontrol.features.motion.Geometry;
import com.inkcontrol.platform.ApplicationServices;
import com.inkcontrol.platform.BuildInfo;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 纯原生界面，不接触系统设置、Root 或进程内 Hook 状态。
 * 所有服务调用都在进程级单线程中执行，页面只渲染后台生成的快照。
 * 「功能」分三级：分类 → 分类内功能列表 → 单项详情（说明、开启前后对比、演示与选项）。
 */
public final class MainActivity extends Activity {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final String FEATURES = "功能", INSTALL = "安装", DIAGNOSE = "诊断", TERMS = "__terms";
    private static final String[] TABS = {FEATURES, INSTALL, DIAGNOSE};
    private static final int[] TAB_GLYPHS = {Ink.Glyph.FEATURES, Ink.Glyph.INSTALL, Ink.Glyph.DIAGNOSE};
    private static final int EXPORT_REQUEST = 100;

    private ApplicationServices services;
    private Ink ink;
    private ApplicationServices.Snapshot snapshot;
    private String loadError = "";
    private String tab = FEATURES, category = "", featureId = "", message = "", output = "";
    private boolean busy, messageFailed, outputExpanded;
    /** 正在提交的单项更改；执行期间按目标值显示，结束后以新快照为准。 */
    private String pendingId = "", pendingValue = "";
    private boolean pendingEnabled;
    /** 详情页里正在预览、尚未使用的选项；空表示与当前设置相同。 */
    private String preview = "";
    private boolean autoplay;
    /** 停留曲线编辑中的草稿。 */
    private String holdMode = "";
    private int[] holdDraft;
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    private ScrollView scroll;
    private LinearLayout body;
    private MotionDemo mainDemo;
    /** 预览选项后滚动到演示处，让用户立刻看到效果。 */
    private boolean scrollToVisual;
    private View visualView;

    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(UiLanguage.wrap(base)); }

    interface Work { String run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        services = new ApplicationServices(this);
        ink = new Ink(this);
        if (state != null) {
            tab = state.getString("tab", FEATURES);
            if (!Arrays.asList(TABS).contains(tab)) tab = FEATURES;
            category = state.getString("category", ""); featureId = state.getString("feature", "");
            if (!featureId.isEmpty()) {
                featureId = Guide.destination(featureId);
                category = FeatureCatalog.get(featureId).group;
            }
        }
        load();
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("tab", tab); state.putString("category", category); state.putString("feature", featureId);
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); render(); }
    @Override public void onBackPressed() {
        if (!featureId.isEmpty()) navigate(category, "");
        else if (!category.isEmpty()) navigate("", "");
        else if (!tab.equals(FEATURES)) switchTab(FEATURES);
        else super.onBackPressed();
    }

    /** 首次读取；用户已改过配置时顺带静默检查一次，否则在未授权 Root 的情况下不主动申请。 */
    private void load() {
        busy = true;
        WORKER.execute(() -> {
            ApplicationServices.Snapshot next = null;
            String error = "", text = "";
            try {
                if (services.configuration().revision != 0) text = services.inspect();
                next = services.snapshot();
            } catch (Exception failure) { error = reason(failure); }
            ApplicationServices.Snapshot loaded = next;
            String failure = error, inspected = text;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                snapshot = loaded; loadError = failure; output = inspected;
                render();
            });
        });
    }

    // ───────────────────────── 页面骨架 ─────────────────────────

    private String pageKey() { return tab + "|" + category + "|" + featureId; }

    private void render() {
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        mainDemo = null; visualView = null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ink.PAPER);
        root.setFitsSystemWindows(true);
        root.addView(header());
        View bar = messageBar();
        if (bar != null) root.addView(bar);

        scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setVerticalScrollbarThumbDrawable(ink.solid(2));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ink.dp(16), ink.dp(4), ink.dp(16), ink.dp(28));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(ink.rule(1.5f));
        root.addView(navigation());
        setContentView(root);

        if (snapshot == null && loadError.isEmpty()) body.addView(note("正在读取配置…"));
        else if (!loadError.isEmpty()) body.addView(card(column(heading("配置读取失败", 18), paragraph("原文件已保留，未做任何修改。\n" + loadError, 14))));
        else if (tab.equals(INSTALL)) installation();
        else if (tab.equals(DIAGNOSE)) diagnostics();
        else if (category.equals(TERMS)) glossary();
        else if (!featureId.isEmpty()) featurePage(FeatureCatalog.get(featureId));
        else if (!category.isEmpty()) categoryPage(Guide.CATEGORIES.get(category));
        else home();

        // 在首次绘制前恢复滚动位置；post 会先画顶部再跳回，墨水屏上多一次整屏刷新。
        Integer saved = scrollPositions.get(pageKey());
        View anchor = scrollToVisual ? visualView : null;
        scrollToVisual = false;
        if (anchor != null || saved != null && saved > 0) {
            ScrollView target = scroll;
            int y = saved == null ? 0 : saved;
            target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    target.getViewTreeObserver().removeOnPreDrawListener(this);
                    target.scrollTo(0, anchor != null ? Math.max(0, anchor.getTop() - ink.dp(8)) : y);
                    return true;
                }
            });
        }
        if (autoplay && mainDemo != null) { MotionDemo demo = mainDemo; demo.post(demo::play); }
        autoplay = false;
    }

    private View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ink.dp(20), ink.dp(14), ink.dp(16), ink.dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String title, subtitle, back = "";
        boolean sub = tab.equals(FEATURES) && !category.isEmpty();
        if (sub && category.equals(TERMS)) { title = "名词解释"; subtitle = "界面里出现的专业词是什么意思。"; back = "功能"; }
        else if (sub && !featureId.isEmpty()) {
            FeatureDefinition f = FeatureCatalog.get(featureId);
            title = f.title; subtitle = Guide.get(f.id).summary.isEmpty() ? f.description : Guide.get(f.id).summary; back = category;
        } else if (sub) { title = category; subtitle = Guide.CATEGORIES.get(category).intro; back = "功能"; }
        else { title = "墨控"; subtitle = "v" + BuildInfo.VERSION + " · 墨水屏增强 · 全部默认关闭"; }

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        if (!back.isEmpty()) {
            String target = back;
            TextView backView = ink.text("‹ " + target, 15, Ink.MEDIUM);
            backView.setPadding(0, ink.dp(4), ink.dp(16), ink.dp(10));
            backView.setContentDescription(ink.t("返回 " + target));
            backView.setOnClickListener(v -> onBackPressed());
            titles.addView(backView);
        }
        titles.addView(ink.text(title, sub ? 26 : 30, Ink.BOLD));
        row.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        String label;
        boolean filled = false;
        if (snapshot == null) label = busy ? "读取中" : "未读取";
        else if (!snapshot.checked()) label = snapshot.failure.equals("尚未检查环境") ? "未检查" : "环境异常";
        else if (snapshot.ready()) { label = "● 就绪"; filled = true; }
        else label = "未就绪";
        TextView badge = ink.text(label, 13, Ink.MEDIUM);
        badge.setPadding(ink.dp(12), ink.dp(7), ink.dp(12), ink.dp(7));
        badge.setBackground(filled ? ink.solid(16) : ink.outline(16));
        badge.setTextColor(filled ? Ink.PAPER : Ink.INK);
        badge.setContentDescription(ink.t("环境状态：" + label));
        badge.setOnClickListener(v -> switchTab(DIAGNOSE));
        row.addView(badge);
        box.addView(row);

        // Keep the brand/title wide enough on 320dp screens in both languages.
        LinearLayout details = new LinearLayout(this);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(ink.text(subtitle, 13, Ink.REGULAR), new LinearLayout.LayoutParams(0, -2, 1));
        TextView language = ink.chip("中 / EN", false, v -> UiLanguage.choose(this));
        language.setContentDescription(ink.t("语言 / Language"));
        details.addView(withMargin(language, 6));
        box.addView(spaced(details, 6));
        return box;
    }

    /** 最近一次操作的结果；不再每次弹窗，失败和页面级操作才弹出完整内容。 */
    private View messageBar() {
        if (message.isEmpty() && !busy) return null;
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(ink.dp(16), 0, ink.dp(16), ink.dp(8));
        bar.setLayoutParams(params);
        bar.setPadding(ink.dp(14), ink.dp(10), ink.dp(6), ink.dp(10));
        boolean inverted = messageFailed && !busy;
        bar.setBackground(inverted ? ink.solid(10) : ink.outline(10));
        String shown = busy ? "处理中，请稍候…" : (messageFailed ? "✕ " : "✓ ") + message;
        TextView text = ink.text(shown, 14, Ink.REGULAR);
        text.setTextColor(inverted ? Ink.PAPER : Ink.INK);
        text.setMaxLines(2);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setContentDescription(ink.t("操作结果"));
        bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (!busy) {
            TextView close = ink.text("×", 22, Ink.REGULAR);
            close.setTextColor(inverted ? Ink.PAPER : Ink.INK);
            close.setGravity(Gravity.CENTER);
            close.setContentDescription(ink.t("关闭提示"));
            close.setOnClickListener(v -> { message = ""; render(); });
            bar.addView(close, new LinearLayout.LayoutParams(ink.dp(40), ink.dp(36)));
            String full = message;
            bar.setOnClickListener(v -> showText(messageFailed ? "未完成" : "操作结果", full));
        }
        return bar;
    }

    private View navigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(ink.dp(8), ink.dp(6), ink.dp(8), ink.dp(6));
        for (int i = 0; i < TABS.length; i++) {
            String title = TABS[i];
            boolean selected = title.equals(tab);
            int color = selected ? Ink.PAPER : Ink.INK;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, ink.dp(6), 0, ink.dp(6));
            if (selected) item.setBackground(ink.solid(14));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new Ink.Glyph(ink, TAB_GLYPHS[i], color));
            item.addView(icon, new LinearLayout.LayoutParams(ink.dp(24), ink.dp(24)));
            TextView label = ink.text(title, 13, selected ? Ink.BOLD : Ink.REGULAR);
            label.setTextColor(color);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, ink.dp(4), 0, 0);
            item.addView(label);
            item.setContentDescription(ink.t(title + (selected ? "，当前页" : "")));
            item.setOnClickListener(v -> switchTab(title));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ink.dp(58), 1);
            params.setMargins(ink.dp(4), 0, ink.dp(4), 0);
            nav.addView(item, params);
        }
        return nav;
    }

    private void switchTab(String next) {
        if (next.equals(tab)) {
            // 再点一次「功能」回到分类首页。
            if (next.equals(FEATURES) && !category.isEmpty()) navigate("", "");
            else if (scroll != null) scroll.scrollTo(0, 0);
            return;
        }
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        tab = next;
        scroll = null;
        render();
    }
    private void navigate(String nextCategory, String nextFeature) {
        if (scroll != null) scrollPositions.put(pageKey(), scroll.getScrollY());
        if (!nextFeature.equals(featureId)) { preview = ""; holdMode = ""; holdDraft = null; }
        // 进入新的下一级页面从顶部开始；返回上一级时保留原位置。
        if (!nextFeature.isEmpty()) scrollPositions.remove(tab + "|" + nextCategory + "|" + nextFeature);
        category = nextCategory; featureId = nextFeature;
        scroll = null;
        render();
    }
    private void openFeature(String id) {
        String destination = Guide.destination(id);
        navigate(FeatureCatalog.get(destination).group, destination);
    }

    // ───────────────────────── 功能：分类首页 ─────────────────────────

    private void home() {
        ApplicationServices.Snapshot s = snapshot;
        int on = 0, pending = 0, faults = 0;
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            String status = status(feature.id);
            if (s.config.requested(feature.id)) on++;
            if (status.equals("待重启")) pending++;
            if (status.startsWith("异常")) faults++;
        }
        LinearLayout stats = new LinearLayout(this);
        stats.addView(stat(String.valueOf(on), "已开启 / " + FeatureCatalog.all().size()), new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(stat(String.valueOf(pending), "待重启"), new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(stat(String.valueOf(faults), "异常"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout overview = column(stats);
        if (!s.ready()) {
            overview.addView(spaced(ink.rule(1), 14));
            String hint = s.checked() ? "环境未就绪：开启增强前请按「安装」页完成安装并完整重启。"
                    : s.failure.equals("尚未检查环境") ? "尚未检查环境。开启任何增强前，先到「安装」完成检查与安装。"
                    : "环境检查未完成：" + s.failure + "。开启增强前请先到「安装」处理。";
            overview.addView(spaced(paragraph(hint, 14), 12));
            overview.addView(spaced(ink.button("前往安装向导", Ink.Kind.SECONDARY, v -> switchTab(INSTALL)), 12));
        }
        body.addView(card(overview));

        for (Guide.Category c : Guide.CATEGORIES.values()) {
            int total = 0, enabled = 0, bad = 0;
            for (String[] section : c.sections) for (int i = 1; i < section.length; i++) {
                total++;
                if (s.config.requested(section[i])) enabled++;
                if (status(section[i]).startsWith("异常")) bad++;
            }
            String meta = (enabled == 0 ? total + " 项" : enabled + " / " + total + " 已开启") + (bad > 0 ? " · " + bad + " 项异常" : "");
            body.addView(spaced(navCard(c.name, c.intro, meta, enabled > 0, "分类 " + c.name, v -> navigate(c.name, "")), 12));
        }
        body.addView(spaced(navCard("名词解释", "刷新方式、残影、关键帧、波形动画……这些词是什么意思。", "", false, "名词解释", v -> navigate(TERMS, "")), 12));

        body.addView(spaced(ink.button("检查当前生效状态", Ink.Kind.PRIMARY, v -> perform("检查生效状态", () -> services.inspect(), false)), 28));
        body.addView(spaced(ink.button("关闭全部增强", Ink.Kind.DANGER, v -> confirm("关闭全部增强", "保留各项选择，并恢复墨控接管的行为。启动期覆盖需完整重启后才完全恢复。",
                () -> perform("关闭全部增强", () -> { services.disableAll(); return "已提交关闭；请完整重启并检查。"; }, true))), 12));
    }

    private View navCard(String title, String text, String meta, boolean strong, String description, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ink.dp(18), ink.dp(16), ink.dp(14), ink.dp(16));
        card.setBackground(ink.pressable(ink.outline(14), ink.box(Ink.PAPER, Ink.INK, 3, 14)));
        LinearLayout content = column(ink.text(title, 20, Ink.BOLD), spaced(paragraph(text, 13), 6));
        if (!meta.isEmpty()) content.addView(spaced(ink.text(meta, 13, strong ? Ink.BOLD : Ink.REGULAR), 8));
        card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(withMargin(ink.text("›", 28, Ink.REGULAR), 12));
        card.setContentDescription(ink.t(description));
        card.setOnClickListener(click);
        return card;
    }

    private void glossary() {
        for (Map.Entry<String, String> term : Guide.TERMS.entrySet()) body.addView(spaced(termView(term.getKey(), term.getValue()), 16));
    }
    private View termView(String term, String text) {
        return column(ink.text(term, 16, Ink.BOLD), spaced(paragraph(text, 14), 4));
    }

    // ───────────────────────── 功能：分类页 ─────────────────────────

    private void categoryPage(Guide.Category c) {
        for (String[] section : c.sections) {
            TextView head = ink.text(section[0], 14, Ink.BOLD);
            head.setPadding(ink.dp(4), ink.dp(20), 0, ink.dp(8));
            body.addView(head);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setBackground(ink.outline(12));
            for (int i = 1; i < section.length; i++) {
                if (i > 1) list.addView(ink.rule(1));
                list.addView(featureRow(FeatureCatalog.get(section[i])));
            }
            body.addView(list);
        }
        if (c.name.equals("动画") || c.name.equals("锁屏")) body.addView(spaced(paragraph("点进每一项可以看到动画演示或效果图。", 13), 16));
        if (c.name.equals("桌面")) body.addView(spaced(ink.button("还原默认设置", Ink.Kind.SECONDARY,
                v -> confirm("还原默认设置", "只把桌面主题、图标、字体、壁纸和组件恢复到默认，不改动桌面布局与图标位置。",
                        () -> perform("还原桌面设置", () -> { services.restoreGroup("桌面"); return "已提交还原；请回到桌面确认。"; }, true))), 20));
    }

    private View featureRow(FeatureDefinition feature) {
        if (DisplayAlgorithms.algorithmFeature(feature.id)) {
            String scope = DisplayAlgorithms.scope(feature.id);
            String chosen = snapshot.config.requested(feature.id) ? snapshot.config.value(feature.id) : "default";
            return navCard(DisplayAlgorithms.name(scope), Guide.get(feature.id).summary,
                    Guide.optionName(feature, chosen) + " · " + Guide.selectionStatus(!chosen.equals("default"), status(feature.id)), snapshot.config.requested(feature.id),
                    feature.title + "，打开算法与迟滞设置", v -> openFeature(feature.id));
        }
        ApplicationServices.Snapshot s = snapshot;
        boolean requested = s.config.requested(feature.id);
        boolean pending = busy && feature.id.equals(pendingId);
        boolean shown = pending ? pendingEnabled : requested;
        boolean child = !feature.dependency.isEmpty();

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ink.dp(child ? 12 : 16), ink.dp(14), ink.dp(8), ink.dp(14));
        // 常态透明，露出外层列表的描边；按下时加粗框反馈。
        row.setBackground(ink.pressable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT), ink.box(Ink.PAPER, Ink.INK, 2, 12)));
        if (child) {
            View bar = new View(this);
            bar.setBackgroundColor(Ink.INK);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ink.dp(3), -1);
            barParams.setMargins(0, ink.dp(2), ink.dp(12), ink.dp(2));
            row.addView(bar, barParams);
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(titleLine(feature, 17));
        String summary = Guide.get(feature.id).summary;
        content.addView(spaced(paragraph(summary.isEmpty() ? feature.description : summary, 13), 5));
        if (child && shown && !s.config.enabled(feature.dependency))
            content.addView(spaced(ink.text("需先开启「" + FeatureCatalog.get(feature.dependency).title + "」", 13, Ink.MEDIUM), 5));
        String status = pending ? "正在保存" : status(feature.id);
        boolean strong = status.equals("已生效") || status.startsWith("异常") || status.equals("待重启");
        String value = valueSummary(feature, pending ? pendingValue : s.config.value(feature.id), shown);
        content.addView(spaced(ink.text(glyph(status) + " " + status + (value.isEmpty() ? "" : " · " + value), 13, strong ? Ink.BOLD : Ink.REGULAR), 7));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));

        CompoundButton toggle = toggle(feature, shown, requested);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
        switchParams.setMargins(ink.dp(10), 0, 0, 0);
        row.addView(toggle, switchParams);
        row.addView(withMargin(ink.text("›", 24, Ink.REGULAR), 8));
        row.setContentDescription(ink.t(feature.title + "，打开详情"));
        row.setOnClickListener(v -> openFeature(feature.id));
        return row;
    }

    /** 列表里显示的当前选择；只有一个选项的功能不显示。 */
    private String valueSummary(FeatureDefinition f, String value, boolean on) {
        if (f.id.equals("motion.hold")) return on ? holdSummary(value) : "温度自适应";
        List<String> real = realValues(f);
        if (real.size() <= 1) return "";
        return Guide.optionName(f, value);
    }
    private static List<String> realValues(FeatureDefinition f) {
        List<String> result = new ArrayList<>(f.values);
        result.remove(FeatureDefinition.CURVE_TOKEN);
        return result;
    }

    private LinearLayout titleLine(FeatureDefinition feature, float sp) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(ink.text(feature.title, sp, Ink.MEDIUM));
        if (feature.experimental) line.addView(withMargin(ink.tag("实验", false), 8));
        if (feature.mode == FeatureDefinition.ApplyMode.REBOOT) line.addView(withMargin(ink.tag("需重启", false), 6));
        return line;
    }

    /** 不让 CompoundButton 自行翻转：只有提交成功的更改才改变显示状态。 */
    private CompoundButton toggle(FeatureDefinition feature, boolean shown, boolean requested) {
        CompoundButton toggle = new CompoundButton(this) {
            @Override public void toggle() { }
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(ink.switchDrawable());
        toggle.setChecked(shown);
        toggle.setContentDescription(ink.t(feature.title));
        toggle.setOnClickListener(v -> {
            boolean next = !requested;
            String value = snapshot.config.value(feature.id);
            if (!begin(feature.id, next, value)) return;
            perform((next ? "开启" : "关闭") + "「" + feature.title + "」", () -> {
                change(feature.id, next, value);
                return "已保存「" + feature.title + "」：" + (next ? "开启" : "关闭");
            }, false);
        });
        return toggle;
    }

    // ───────────────────────── 功能：详情页 ─────────────────────────

    private void featurePage(FeatureDefinition f) {
        if (DisplayAlgorithms.algorithmFeature(f.id)) { displayAlgorithmPage(f); return; }
        ApplicationServices.Snapshot s = snapshot;
        Guide.Entry g = Guide.get(f.id);
        boolean requested = s.config.requested(f.id);
        boolean pending = busy && f.id.equals(pendingId);
        boolean shown = pending ? pendingEnabled : requested;
        String current = pending ? pendingValue : s.config.value(f.id);
        String selected = preview.isEmpty() ? current : preview;

        // 1. 状态
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        String status = pending ? "正在保存" : status(f.id);
        LinearLayout statusTitle = new LinearLayout(this);
        statusTitle.setGravity(Gravity.CENTER_VERTICAL);
        statusTitle.addView(ink.text(shown ? "已开启" : "未开启", 18, Ink.BOLD));
        if (f.experimental) statusTitle.addView(withMargin(ink.tag("实验", false), 8));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT) statusTitle.addView(withMargin(ink.tag("需重启", false), 6));
        LinearLayout statusText = column(statusTitle, spaced(ink.text(glyph(status) + " " + status, 14, Ink.REGULAR), 6));
        if (f.mode == FeatureDefinition.ApplyMode.REBOOT) statusText.addView(spaced(paragraph("↻ 更改后需要完整重启才生效。", 13), 4));
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        if (g.visual != Guide.Visual.HOLD_CURVE) statusRow.addView(toggle(f, shown, requested));
        LinearLayout statusCard = column(statusRow);
        if (!f.dependency.isEmpty() && !s.config.enabled(f.dependency)) dependencyHint(statusCard, f.dependency, "这一项属于「" + FeatureCatalog.get(f.dependency).title + "」，要先开启它才会生效。");
        if (FeatureService.needsWaveBackend(f.id, selected) && !s.config.enabled("display.gpu") && !f.dependency.equals("display.gpu"))
            dependencyHint(statusCard, "display.gpu", "所选效果是波形动画，需要先开启「GPU 显示后端」并完整重启。");
        body.addView(card(statusCard));

        // 2. 说明
        if (!g.what.isEmpty()) body.addView(spaced(section("它做什么", paragraph(g.what, 15)), 20));
        // 3. 演示 / 示意图
        View visual = visual(f, g, selected);
        if (visual != null) {
            body.addView(spaced(visual, 16));
            visualView = visual;
            // 预览了别的选项时，在演示下方直接给出“使用”，不必再滚到列表底部。
            if (g.visual != Guide.Visual.HOLD_CURVE && g.visual != Guide.Visual.BARS && realValues(f).size() > 1 && !selected.equals(current))
                body.addView(spaced(useButton(f, requested, selected), 12));
        }
        // 4. 开启前后
        if (!g.before.isEmpty() || !g.after.isEmpty()) body.addView(spaced(beforeAfter(g), 20));
        // 5. 选项
        if (g.visual == Guide.Visual.HOLD_CURVE) holdEditor(f, requested, current);
        else if (realValues(f).size() > 1) options(f, g, requested, current, selected);
        // 6. 注意
        if (!g.cost.isEmpty() || !g.note.isEmpty()) {
            LinearLayout notes = column();
            if (!g.cost.isEmpty()) notes.addView(paragraph(g.cost, 14));
            if (!g.note.isEmpty()) notes.addView(spaced(ink.text("⚠ " + g.note, 14, Ink.MEDIUM), g.cost.isEmpty() ? 0 : 10));
            body.addView(spaced(section("需要知道", notes), 20));
        }
        // 7. 名词
        if (!g.terms.isEmpty()) {
            LinearLayout terms = column();
            for (int i = 0; i < g.terms.size(); i++) terms.addView(spaced(termView(g.terms.get(i)[0], g.terms.get(i)[1]), i == 0 ? 0 : 14));
            body.addView(spaced(section("名词解释", terms), 20));
        }
    }

    /** 算法页直接选值，保持现状即撤下本页覆盖；不改旧视频、锁屏细线或独立迟滞。 */
    private void displayAlgorithmPage(FeatureDefinition feature) {
        String scope = DisplayAlgorithms.scope(feature.id);
        com.inkcontrol.core.Configuration config = snapshot.config;
        Guide.Entry guide = Guide.get(feature.id);
        boolean pending = busy && pendingId.equals(feature.id);
        boolean requested = pending ? pendingEnabled : config.requested(feature.id);
        String selected = requested ? (pending ? pendingValue : config.value(feature.id)) : "default";
        LinearLayout state = column(heading("算法选择 · " + Guide.optionName(feature, selected), 18),
                spaced(paragraph(pending ? "正在保存" : "算法状态：" + Guide.selectionStatus(!selected.equals("default"), status(feature.id)), 14), 6),
                spaced(paragraph("保持现状时：" + displayBaseline(scope), 14), 8));
        if (!config.enabled("display.gpu")) dependencyHint(state, "display.gpu", "GPU 显示后端未开启，当前沿用系统显示路径；已保存的选择仍会保留。");
        body.addView(card(state));
        body.addView(spaced(paragraph(guide.what, 15), 16));
        LinearLayout options = column();
        for (String value : feature.values) {
            Guide.Option option = guide.options.get(value);
            LinearLayout choice = column(ink.text((value.equals(selected) ? "● " : "○ ") + option.name, 17, Ink.BOLD),
                    spaced(paragraph(option.detail, 14), 6));
            choice.setPadding(ink.dp(16), ink.dp(14), ink.dp(16), ink.dp(14));
            choice.setBackground(ink.box(Ink.PAPER, Ink.INK, value.equals(selected) ? 2.5f : 1, 12));
            choice.setContentDescription(ink.t("算法 " + option.name + (value.equals(selected) ? "，已选择" : "")));
            choice.setOnClickListener(v -> {
                if (!busy && value.equals(selected)) return;
                saveDisplayChoice(feature.id, value, option.name);
            });
            options.addView(spaced(choice, 10));
        }
        body.addView(spaced(section("本模式算法", options), 16));
        if (!DisplayAlgorithms.gray(scope)) displayHysteresis(scope);
        else body.addView(spaced(paragraph("16级灰阶算法没有独立迟滞选项。保持现状会保留锁屏细线处理；明确选择算法会覆盖本模式的灰阶量化。", 14), 18));
        body.addView(spaced(section("需要知道", paragraph(guide.cost, 14)), 20));
        if (scope.equals("video")) body.addView(spaced(paragraph("旧实验室的视频选择继续保存在配置中，本页选「保持现状」即可沿用。新算法选择优先；恢复默认不会清除旧选择。", 14), 12));
    }

    private String displayBaseline(String scope) {
        if (DisplayAlgorithms.gray(scope)) return "沿用原有16级灰阶处理，由原分支决定是否需要抖动；需要抖动且符合锁屏细线条件时，仍沿用「消除锁屏细线」的点量化。该项当前"
                + (snapshot.config.enabled("lock.dither") ? "已开启。" : "未开启。" );
        FeatureDefinition feature = FeatureCatalog.get("display.alg." + scope);
        return Guide.optionName(feature, DisplayAlgorithms.baselineAlgorithm(snapshot.config, scope))
                + (scope.equals("video") && snapshot.config.enabled("experiment.video") ? "（沿用旧视频选择）。" : "。");
    }

    private void displayHysteresis(String scope) {
        com.inkcontrol.core.Configuration config = snapshot.config;
        if (!config.enabled("display.gpu")) {
            body.addView(spaced(section("独立迟滞", paragraph("开启 GPU 显示后端后，可按当前算法设置。视频默认开启，快速和极速默认关闭。", 14)), 20));
            return;
        }
        // 算法提交期间保持原页开关静止，快照更新后才按新算法改变可用性。
        if (!DisplayAlgorithms.supportsHysteresis(config, scope)) {
            body.addView(spaced(section("历史处理", paragraph("原厂误差扩散有内部历史处理，不能使用独立迟滞开关。已保存的独立迟滞选择会保留，切回 Bayer、蓝噪声或固定阈值时继续使用。", 14)), 20));
            return;
        }
        String id = "display.hyst." + scope;
        boolean pending = busy && pendingId.equals(id);
        boolean current = DisplayAlgorithms.hysteresis(config, scope);
        String policy = pending ? pendingValue : DisplayAlgorithms.hysteresisOverride(config, scope);
        boolean shown = pending ? policy.equals("on") || policy.equals("default") && scope.equals("video") : current;
        LinearLayout row = column(ink.text("独立迟滞", 18, Ink.BOLD),
                spaced(paragraph("减少静止区域闪点，可能增加拖尾。与算法选择独立保存。", 14), 6));
        CompoundButton toggle = new CompoundButton(this) {
            @Override public void toggle() { }
            @Override public CharSequence getAccessibilityClassName() { return Switch.class.getName(); }
        };
        toggle.setButtonDrawable(ink.switchDrawable());
        toggle.setChecked(shown);
        toggle.setContentDescription(ink.t(DisplayAlgorithms.name(scope) + "独立迟滞"));
        toggle.setOnClickListener(v -> saveDisplayChoice(id, current ? "off" : "on", current ? "关闭迟滞" : "开启迟滞"));
        row.addView(spaced(toggle, 12));
        row.addView(spaced(paragraph((policy.equals("default") ? "跟随默认：" : "单独设置：") + (shown ? "开启" : "关闭")
                + " · " + (pending ? "正在保存" : "迟滞状态：" + Guide.selectionStatus(!policy.equals("default"), status(id))), 14), 8));
        if (!policy.equals("default")) row.addView(spaced(ink.button("恢复默认（" + (scope.equals("video") ? "开启" : "关闭") + "）", Ink.Kind.SECONDARY,
                v -> saveDisplayChoice(id, "default", "跟随默认迟滞")), 12));
        body.addView(spaced(card(row), 20));
    }

    private void saveDisplayChoice(String id, String value, String label) {
        boolean enabled = !value.equals("default");
        if (!begin(id, enabled, value)) return;
        perform("保存「" + label + "」", () -> {
            change(id, enabled, value);
            return "已保存「" + FeatureCatalog.get(id).title + "」：" + label;
        }, false);
    }

    private void dependencyHint(LinearLayout card, String dependency, String text) {
        card.addView(spaced(ink.rule(1), 14));
        card.addView(spaced(paragraph(text, 14), 12));
        card.addView(spaced(ink.button("前往「" + FeatureCatalog.get(dependency).title + "」", Ink.Kind.SECONDARY, v -> openFeature(dependency)), 10));
    }

    private View section(String title, View content) {
        return column(ink.text(title, 18, Ink.BOLD), spaced(content, 8));
    }

    private View beforeAfter(Guide.Entry g) {
        LinearLayout box = column(ink.text("开启前后", 18, Ink.BOLD));
        if (!g.before.isEmpty()) box.addView(spaced(compare("开启前", g.before, false), 10));
        if (!g.after.isEmpty()) box.addView(spaced(compare("开启后", g.after, true), 8));
        return box;
    }
    private View compare(String label, String text, boolean after) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(ink.dp(14), ink.dp(12), ink.dp(14), ink.dp(12));
        row.setBackground(ink.box(Ink.PAPER, Ink.INK, after ? 2.5f : 1, 10));
        TextView tag = ink.tag(label, after);
        row.addView(tag);
        TextView content = paragraph(text, 14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = ink.dp(12);
        row.addView(content, params);
        return row;
    }

    // ── 演示与示意图 ──

    private int width() { return getResources().getDisplayMetrics().widthPixels - ink.dp(32); }

    private View visual(FeatureDefinition f, Guide.Entry g, String value) {
        ApplicationServices.Snapshot s = snapshot;
        switch (g.visual) {
            case MOTION_SCENE: {
                String scene = f.id.substring("motion.".length());
                MotionDemo demo = demo(scene, value, steps(), origin(), direction(), spread());
                return demoBox("效果演示", demo, timing(value));
            }
            case MOTION_STEPS: {
                String style = s.config.value("motion.app_open");
                if (!style.equals("kwipe")) style = "kiris";
                return demoBox("效果演示（打开应用 · " + Guide.optionName(FeatureCatalog.get("motion.app_open"), style) + "）",
                        demo("app_open", style, Integer.parseInt(value), origin(), direction(), spread()), "共 " + (Integer.parseInt(value) - 1) + " 张中间画面。");
            }
            case MOTION_ORIGIN:
                return demoBox("效果演示（方形展开）", demo("app_open", "kiris", 3, value, direction(), spread()), "");
            case MOTION_DIRECTION:
                return demoBox("效果演示（应用内前进 · 关键帧擦除）", demo("in_app_forward", "kwipe", 3, origin(), value, spread()), "");
            case MOTION_SPREAD:
                return demoBox("效果演示（墨迹晕开）", demo("app_open", "ink", steps(), origin(), direction(), Integer.parseInt(value)),
                        "实际全程约 " + seconds(Integer.parseInt(value) * 11.65 + 117) + " 秒。");
            case LOCK_STYLE: {
                int frameWidth = Math.min(ink.dp(240), width());
                View phone = phone(LockPreview.create(this, Integer.parseInt(value)), frameWidth);
                return framed("效果图 · " + Guide.optionName(f, value), phone, "图中为样例数据，实际显示你的时间、天气和音乐。");
            }
            case DESKTOP_THEME: {
                String kind = s.config.value("desktop.widget");
                return framed("效果图 · " + Guide.optionName(f, value), widget(value, kind), "图为主屏组件（" + Guide.optionName(FeatureCatalog.get("desktop.widget"), kind) + "），图标与字体同一风格。");
            }
            case DESKTOP_WIDGET:
                return framed("效果图 · " + Guide.optionName(f, value), widget(s.config.value("desktop.theme"), value), "使用当前选择的主题「" + Guide.optionName(FeatureCatalog.get("desktop.theme"), s.config.value("desktop.theme")) + "」。");
            case REFRESH_MODES: {
                Map<String, String> marks = new HashMap<>();
                if (f.id.equals("display.apps")) { marks.put("gld", "原来"); marks.put("a2", "开启后"); }
                else marks.put(value.equals("9") ? "a2" : value.equals("2") ? "gc" : "gld", "所选");
                return framed("三种刷新方式", new Charts.RefreshModes(this, ink, marks), "条越长越慢；清晰全刷的实心部分表示先闪黑。");
            }
            case BARS:
                return framed(g.barsCaption, new Charts.Bars(this, ink, g.bars), "");
            default:
                return null;
        }
    }

    private MotionDemo demo(String scene, String style, int steps, String origin, String direction, int spread) {
        MotionDemo demo = new MotionDemo(this, ink, false).configure(scene, style, steps, origin, direction, spread);
        mainDemo = demo;
        return demo;
    }
    private View demoBox(String title, MotionDemo demo, String caption) {
        int w = Math.min(ink.dp(220), width() - ink.dp(40));
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ink.dp(5), ink.dp(5), ink.dp(5), ink.dp(5));
        frame.setBackground(ink.box(Ink.PAPER, Ink.INK, 2.5f, 18));
        frame.addView(demo, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout box = column(ink.text(title, 18, Ink.BOLD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w + ink.dp(10), -2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = ink.dp(12);
        box.addView(frame, params);
        box.addView(spaced(paragraph("点按画面播放一次。演示把时间放慢，方便看清过程。" + (caption.isEmpty() ? "" : "\n" + caption), 13), 10));
        return box;
    }
    private View framed(String title, View content, String caption) {
        LinearLayout box = column(ink.text(title, 18, Ink.BOLD));
        box.addView(spaced(content, 12));
        if (!caption.isEmpty()) box.addView(spaced(paragraph(caption, 13), 10));
        return box;
    }
    private View phone(View screen, int frameWidth) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ink.dp(6), ink.dp(6), ink.dp(6), ink.dp(6));
        frame.setBackground(ink.box(Ink.PAPER, Ink.INK, 2.5f, 22));
        // 屏幕内容按机身内圆角裁切，避免直角露出外框。
        screen.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ink.px(16)); }
        });
        screen.setClipToOutline(true);
        frame.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(frameWidth, (frameWidth - ink.dp(12)) * 2 + ink.dp(12));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        frame.setLayoutParams(params);
        return frame;
    }
    private View widget(String theme, String kind) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(ink.dp(2), ink.dp(2), ink.dp(2), ink.dp(2));
        frame.setBackground(ink.outline(12));
        View content;
        try { content = DesktopPreview.create(this, theme, kind); }
        catch (RuntimeException error) { content = paragraph("预览素材不可用：" + reason(error), 13); }
        frame.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // 组件设计稿 632×432。
        frame.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.round(width() * 432f / 632f)));
        return frame;
    }

    /** 当前设置下的动画参数；子项未开启时取运行端的默认值。 */
    private int steps() { return snapshot.config.enabled("motion.steps") ? Integer.parseInt(snapshot.config.value("motion.steps")) : 2; }
    private String origin() { return snapshot.config.enabled("motion.origin") ? snapshot.config.value("motion.origin") : "touch"; }
    private String direction() { return snapshot.config.enabled("motion.direction") ? snapshot.config.value("motion.direction") : "auto"; }
    private int spread() { return snapshot.config.enabled("motion.spread") ? Integer.parseInt(snapshot.config.value("motion.spread")) : 12; }
    private String timing(String style) {
        if (style.equals("kiris") || style.equals("kwipe")) {
            int hold = currentHold();
            return "按当前设置，实际约 " + seconds((steps() - 1) * hold + 117) + " 秒（" + (steps() - 1) + " 张中间画面，每张停留 " + hold + " 毫秒）。";
        }
        if (style.equals("gcsweep")) return "与原厂全刷用时相同，约 0.9 秒。";
        return "按当前设置，实际约 " + seconds(spread() * 11.65 + 117) + " 秒，仍在一次刷新内完成。";
    }
    private static String seconds(double ms) { return String.format(Locale.ROOT, "%.2f", ms / 1000.0); }

    // ── 选项卡片 ──

    private void options(FeatureDefinition f, Guide.Entry g, boolean requested, String current, String selected) {
        boolean thumbs = g.visual == Guide.Visual.MOTION_SCENE || g.visual == Guide.Visual.LOCK_STYLE;
        LinearLayout list = column(ink.text(g.visual == Guide.Visual.MOTION_SCENE ? "选择动画" : "选择", 18, Ink.BOLD),
                spaced(paragraph("点按卡片先" + (g.visual == Guide.Visual.NONE || g.visual == Guide.Visual.BARS ? "查看说明" : "预览效果") + "，满意后再点下方按钮使用。", 13), 6));
        for (String value : realValues(f)) {
            Guide.Option o = g.options.get(value);
            String name = Guide.optionName(f, value), detail = o == null ? "" : o.detail;
            boolean isCurrent = value.equals(current), isSelected = value.equals(selected);
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(ink.dp(12), ink.dp(12), ink.dp(14), ink.dp(12));
            card.setBackground(ink.box(Ink.PAPER, Ink.INK, isSelected ? 3 : 1.5f, 12));
            if (thumbs) {
                View thumb;
                if (g.visual == Guide.Visual.LOCK_STYLE) thumb = LockPreview.create(this, Integer.parseInt(value));
                else thumb = new MotionDemo(this, ink, true).configure(f.id.substring(7), value, steps(), origin(), direction(), spread());
                FrameLayout frame = new FrameLayout(this);
                frame.setPadding(ink.dp(2), ink.dp(2), ink.dp(2), ink.dp(2));
                frame.setBackground(ink.box(Ink.PAPER, Ink.INK, 1.5f, 6));
                frame.addView(thumb, new FrameLayout.LayoutParams(-1, g.visual == Guide.Visual.LOCK_STYLE ? ink.dp(116) : -2));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ink.dp(62), -2);
                tp.rightMargin = ink.dp(14);
                card.addView(frame, tp);
            }
            LinearLayout content = column(ink.text(name, 16, isSelected ? Ink.BOLD : Ink.MEDIUM));
            if (!detail.isEmpty()) content.addView(spaced(paragraph(detail, 13), 4));
            if (FeatureService.needsWaveBackend(f.id, value) && f.id.startsWith("motion.") && !f.id.equals("motion.gc_clear"))
                content.addView(spaced(ink.text("需要 GPU 显示后端", 12, Ink.REGULAR), 4));
            if (isCurrent || isSelected) {
                LinearLayout tags = new LinearLayout(this);
                if (isCurrent) tags.addView(ink.tag(requested ? "✓ 正在使用" : "已选（未开启）", true));
                if (isSelected && !isCurrent) tags.addView(ink.tag("预览中", false));
                content.addView(spaced(tags, 6));
            }
            card.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
            card.setContentDescription(ink.t("选项 " + name + (isCurrent ? "，正在使用" : "") + (isSelected && !isCurrent ? "，预览中" : "")));
            card.setOnClickListener(v -> {
                if (value.equals(selected)) { if (mainDemo != null) mainDemo.play(); return; }
                preview = value.equals(current) ? "" : value;
                autoplay = true; scrollToVisual = true;
                render();
            });
            list.addView(spaced(card, 10));
        }
        if (!selected.equals(current) || !requested) list.addView(spaced(useButton(f, requested, selected), 14));
        body.addView(spaced(list, 22));
    }

    private View useButton(FeatureDefinition f, boolean requested, String selected) {
        String name = Guide.optionName(f, selected);
        String label = requested ? "使用「" + name + "」" : "开启并使用「" + name + "」";
        return ink.button(label, Ink.Kind.PRIMARY, v -> {
            if (!begin(f.id, true, selected)) return;
            perform("使用「" + name + "」", () -> {
                change(f.id, true, selected);
                return "已保存「" + f.title + "」：" + name;
            }, false);
            preview = "";
        });
    }

    // ── 关键帧停留：温度曲线 ──

    private float batteryTemperature() {
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int t = battery == null ? Integer.MIN_VALUE : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            return t == Integer.MIN_VALUE ? 30f : t / 10f;
        } catch (RuntimeException error) { return 30f; }
    }
    private String savedHoldMode(boolean requested, String value) {
        if (!requested || value.equals("0")) return "auto";
        int[] curve = FeatureDefinition.curve(value);
        if (curve == null) return "fixed";
        for (int v : curve) if (v != curve[0]) return "curve";
        return "fixed";
    }
    private int[] savedHoldValues(String value) {
        int[] curve = FeatureDefinition.curve(value);
        if (curve != null) return curve;
        int fixed = value.matches("[0-9]+") ? Integer.parseInt(value) : 0;
        if (fixed <= 0) return Charts.autoCurve();
        int[] all = new int[FeatureDefinition.TEMPERATURE_BANDS];
        Arrays.fill(all, fixed);
        return all;
    }
    private String holdSummary(String value) {
        String mode = savedHoldMode(true, value);
        if (mode.equals("auto")) return "温度自适应";
        int[] v = savedHoldValues(value);
        return mode.equals("fixed") ? "统一 " + v[0] + " 毫秒" : "自定义曲线";
    }
    /** 当前温度下实际生效的停留时长；未开启或该段为 0 时用自动值。 */
    private int currentHold() {
        int band = FeatureDefinition.band(batteryTemperature());
        if (!snapshot.config.enabled("motion.hold")) return Geometry.autoHoldForBand(band);
        int v = savedHoldValues(snapshot.config.value("motion.hold"))[band];
        return v > 0 ? v : Geometry.autoHoldForBand(band);
    }

    private void holdEditor(FeatureDefinition f, boolean requested, String current) {
        String saved = savedHoldMode(requested, current);
        if (holdMode.isEmpty()) holdMode = saved;
        if (holdDraft == null) holdDraft = savedHoldValues(current);
        float temperature = batteryTemperature();
        int band = FeatureDefinition.band(temperature);
        int[] auto = Charts.autoCurve();

        LinearLayout box = column(ink.text("怎么决定停留时长", 18, Ink.BOLD));
        LinearLayout modes = new LinearLayout(this);
        String[][] choices = {{"auto", "温度自适应"}, {"fixed", "统一时长"}, {"curve", "自定义曲线"}};
        for (int i = 0; i < choices.length; i++) {
            String mode = choices[i][0];
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ink.dp(42), 1);
            if (i > 0) p.leftMargin = ink.dp(8);
            modes.addView(ink.chip(choices[i][1], mode.equals(holdMode), v -> {
                holdMode = mode;
                if (mode.equals("fixed")) { int v0 = holdDraft[band] > 0 ? holdDraft[band] : auto[band]; Arrays.fill(holdDraft, v0); }
                else if (mode.equals("curve") && saved.equals("auto")) holdDraft = auto.clone();
                render();
            }), p);
        }
        box.addView(spaced(modes, 12));

        String explain;
        int[] shownValues;
        if (holdMode.equals("auto")) { explain = "按电池温度自动取“刚好刷完 + 3 帧余量”。天冷时自动变长，避免画面糊在一起。"; shownValues = auto; }
        else if (holdMode.equals("fixed")) { explain = "所有温度都使用同一个时长。天冷时如果低于虚线，动画可能糊。"; shownValues = holdDraft; }
        else { explain = "上下拖动每根柱子，单独设置每个温度段，10 毫秒一档。虚线是该温度刷完所需的时间。"; shownValues = holdDraft; }
        box.addView(spaced(paragraph(explain, 14), 12));

        int now = holdMode.equals("auto") || shownValues[band] <= 0 ? auto[band] : shownValues[band];
        box.addView(spaced(ink.text(String.format(Locale.ROOT, "当前电池温度 %.1f°C → 每张中间画面停留 %d 毫秒", temperature, now), 14, Ink.BOLD), 12));

        Charts.HoldCurve curve = new Charts.HoldCurve(this, ink, shownValues, band, holdMode.equals("curve"),
                values -> { holdDraft = values; render(); });
        box.addView(spaced(curve, 14));
        box.addView(spaced(paragraph("实心柱：设置的时长　虚线：该温度刷完所需　空心柱：低于所需，可能糊", 12), 6));

        if (holdMode.equals("fixed")) {
            LinearLayout stepper = new LinearLayout(this);
            stepper.setGravity(Gravity.CENTER_VERTICAL);
            stepper.addView(ink.button("−", Ink.Kind.SECONDARY, v -> { Arrays.fill(holdDraft, Math.max(30, holdDraft[0] - 10)); render(); }), new LinearLayout.LayoutParams(ink.dp(64), -2));
            TextView value = ink.text(holdDraft[0] + " 毫秒", 22, Ink.BOLD);
            value.setGravity(Gravity.CENTER);
            stepper.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            stepper.addView(ink.button("+", Ink.Kind.SECONDARY, v -> { Arrays.fill(holdDraft, Math.min(FeatureDefinition.CURVE_MAX_MS, holdDraft[0] + 10)); render(); }), new LinearLayout.LayoutParams(ink.dp(64), -2));
            box.addView(spaced(stepper, 14));
            FlowLayout presets = new FlowLayout(this, ink.dp(8), ink.dp(8));
            for (int ms : new int[]{100, 150, 200, 300, 500, 1000}) presets.addView(ink.chip(ms + " 毫秒", holdDraft[0] == ms, v -> { Arrays.fill(holdDraft, ms); render(); }));
            box.addView(spaced(presets, 12));
        } else if (holdMode.equals("curve")) {
            LinearLayout actions = new LinearLayout(this);
            actions.addView(ink.button("从自动值开始", Ink.Kind.SECONDARY, v -> { holdDraft = auto.clone(); render(); }), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            p.leftMargin = ink.dp(10);
            actions.addView(ink.button("整体加长 20%", Ink.Kind.SECONDARY, v -> {
                for (int i = 0; i < holdDraft.length; i++) holdDraft[i] = Math.min(FeatureDefinition.CURVE_MAX_MS, Math.round(holdDraft[i] * 1.2f / 10) * 10);
                render();
            }), p);
            box.addView(spaced(actions, 14));
        }

        boolean changed = !holdMode.equals(saved) || !holdMode.equals("auto") && !Arrays.equals(holdDraft, savedHoldValues(current));
        if (changed) {
            String mode = holdMode;
            int[] values = holdDraft.clone();
            box.addView(spaced(ink.button("保存", Ink.Kind.PRIMARY, v -> {
                String next = mode.equals("auto") ? current : FeatureDefinition.curveValue(values);
                boolean enable = !mode.equals("auto");
                if (!begin(f.id, enable, next)) return;
                perform("保存关键帧停留", () -> {
                    change(f.id, enable, next);
                    return "已保存「关键帧停留」：" + (mode.equals("auto") ? "温度自适应" : mode.equals("fixed") ? "统一 " + values[0] + " 毫秒" : "自定义曲线");
                }, false);
                holdMode = ""; holdDraft = null;
            }), 16));
        }
        body.addView(spaced(box, 22));
    }

    // ───────────────────────── 安装 ─────────────────────────

    private void installation() {
        ApplicationServices.Snapshot s = snapshot;
        Map<String, String> env = s.environment;
        body.addView(card(column(
                heading("开始使用", 20),
                spaced(paragraph("适用于 HLTE203T · Android 10 · 固件 L1732.6.01.02，需要 Magisk 与 LSPosed 类框架（如 Vector）。"
                        + "没有 Root 也能浏览功能说明、观看演示和导出安装包。", 14), 8))));

        boolean checked = s.checked();
        boolean supported = checked && "HLTE203T".equals(env.get("device")) && "29".equals(env.get("sdk"));
        boolean installed = "1".equals(env.get("installed")) && BuildInfo.VERSION.equals(env.get("backend_version"));
        String conflict = env.getOrDefault("conflict", "");
        int hooks = s.hooked.size();

        String checkText = "授予 Root 后读取设备型号、固件和后端状态。";
        if (checked && !supported) checkText += "\n当前设备 " + env.getOrDefault("device", "?") + " 不在适配范围内，增强不会生效。";
        body.addView(step(1, checked, "检查环境", checkText,
                ink.button("检查环境", Ink.Kind.PRIMARY, v -> perform("检查环境", () -> services.inspect(), true))));

        String installText = "导出安装包，在 Magisk →「模块」→「从本地安装」中选择它。";
        if (checked && !installed && !env.getOrDefault("backend_version", "").isEmpty())
            installText += "\n已安装的后端是 " + env.get("backend_version") + "，与本 App 不一致，请重新安装。";
        if (!conflict.isEmpty())
            installText += "\n检测到同类模块 " + conflict + " 仍在启用，它会与墨控争用显示库，请在 Magisk 中停用。";
        body.addView(step(2, installed && conflict.isEmpty(), "安装 Magisk 后端", installText,
                ink.button("导出 Magisk 安装包", Ink.Kind.PRIMARY, v -> exportZip())));

        String hookText = "在框架管理器中启用「墨控」，作用域勾选：系统框架、系统界面、桌面（com.hmct.vision）。";
        if (checked && installed && hooks > 0 && hooks < 3) hookText += "\n目前只有 " + hooks + " / 3 个作用域已加载，请检查勾选后重启。";
        body.addView(step(3, hooks == 3, "启用框架模块", hookText, null));

        body.addView(step(4, s.ready() && hooks == 3, "重启并开启功能",
                "完成以上步骤后完整重启手机，回到这里重新检查环境，然后到「功能」按需开启。所有增强默认关闭，可随时一键全部关闭。",
                ink.button("前往功能", Ink.Kind.SECONDARY, v -> { category = ""; featureId = ""; switchTab(FEATURES); })));
    }

    private View step(int number, boolean done, String title, String text, View actions) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ink.dp(20), 0, 0);
        TextView marker = ink.text(done ? "✓" : String.valueOf(number), 15, Ink.BOLD);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(done ? ink.solid(16) : ink.outline(16));
        marker.setTextColor(done ? Ink.PAPER : Ink.INK);
        marker.setContentDescription(ink.t("第 " + number + " 步" + (done ? "，已完成" : "")));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(ink.dp(32), ink.dp(32));
        markerParams.setMargins(0, 0, ink.dp(14), 0);
        row.addView(marker, markerParams);
        LinearLayout content = column(ink.text(title, 18, Ink.BOLD), spaced(paragraph(text, 14), 8));
        if (actions != null) content.addView(spaced(actions, 12));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    // ───────────────────────── 诊断 ─────────────────────────

    private void diagnostics() {
        ApplicationServices.Snapshot s = snapshot;
        Map<String, String> env = s.environment;
        LinearLayout summary = column(heading("运行环境", 20));
        if (!s.checked()) {
            summary.addView(spaced(paragraph(s.failure.equals("尚未检查环境") ? "尚未检查。点击下方按钮会申请 Root，并读取当前进程与后端状态。"
                    : "检查未完成：" + s.failure + "\n仍可浏览说明和导出 ZIP。", 14), 10));
        } else {
            summary.addView(spaced(fact("设备", env.getOrDefault("device", "?") + " · SDK " + env.getOrDefault("sdk", "?")), 12));
            summary.addView(fact("固件", env.getOrDefault("firmware", "?")));
            summary.addView(fact("后端模块", "1".equals(env.get("installed")) ? "已安装 " + env.getOrDefault("backend_version", "") : "未安装或已停用"));
            summary.addView(fact("框架模块", s.hooked.size() + " / 3 个作用域已加载"));
            if (!env.getOrDefault("conflict", "").isEmpty()) summary.addView(fact("冲突模块", env.get("conflict")));
            summary.addView(fact("整体就绪", s.ready() ? "是" : "否"));
            if (!env.getOrDefault("error", "").isEmpty()) summary.addView(fact("后端错误", env.get("error")));
        }
        body.addView(card(summary));
        body.addView(spaced(ink.button("刷新环境与当前进程确认", Ink.Kind.PRIMARY, v -> perform("刷新环境", () -> services.inspect(), false)), 16));
        body.addView(spaced(ink.button("重试应用已保存配置", Ink.Kind.SECONDARY, v -> perform("重试应用配置", () -> services.retry(), true)), 10));

        if (!output.isEmpty()) {
            LinearLayout raw = new LinearLayout(this);
            raw.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(ink.text("最近一次完整输出", 16, Ink.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(ink.text(outputExpanded ? "收起 ▾" : "展开 ▸", 14, Ink.MEDIUM));
            head.setOnClickListener(v -> { outputExpanded = !outputExpanded; render(); });
            raw.addView(head);
            if (outputExpanded) {
                TextView text = ink.text(output, 11, Ink.MONO);
                text.setTextIsSelectable(true);
                raw.addView(spaced(text, 12));
            }
            body.addView(spaced(card(raw), 24));
        }
    }
    private View fact(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ink.dp(6), 0, ink.dp(6));
        row.addView(ink.text(key, 14, Ink.REGULAR), new LinearLayout.LayoutParams(ink.dp(96), -2));
        row.addView(ink.text(value, 14, Ink.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    // ───────────────────────── 操作 ─────────────────────────

    /** 配置先保存再由 Root 应用；保存成功但应用失败时如实说明，而不是笼统报“未完成”。 */
    private void change(String id, boolean enabled, String value) throws Exception {
        try { services.change(id, enabled, value); }
        catch (Exception error) {
            com.inkcontrol.core.Configuration saved = services.configuration();
            if (saved.requested(id) == enabled && saved.value(id).equals(value))
                throw new IllegalStateException("选择已保存，但未能立即应用：" + reason(error) + "\n完成安装后可在「诊断」重试应用。", error);
            throw error;
        }
    }
    private boolean begin(String id, boolean enabled, String value) {
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return false; }
        pendingId = id; pendingEnabled = enabled; pendingValue = value;
        return true;
    }

    private String status(String id) {
        String value = snapshot == null ? null : snapshot.statuses.get(id);
        return value == null ? "未知" : value;
    }
    private static String glyph(String status) {
        if (status.equals("已生效")) return "●";
        if (status.equals("关闭")) return "○";
        if (status.equals("待重启")) return "↻";
        if (status.startsWith("异常")) return "✕";
        if (status.startsWith("依赖")) return "◌";
        if (status.equals("正在保存")) return "◐";
        return "…";
    }

    private View stat(String number, String label) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(ink.text(number, 30, Ink.BOLD));
        column.addView(spaced(ink.text(label, 12, Ink.REGULAR), 4));
        return column;
    }

    private void confirm(String title, String text, Runnable action) {
        new AlertDialog.Builder(this).setTitle(ink.t(title)).setMessage(ink.t(text)).setNegativeButton(ink.t("取消"), null).setPositiveButton(ink.t("继续"), (dialog, which) -> action.run()).show();
    }
    private void showText(String title, String text) {
        new AlertDialog.Builder(this).setTitle(ink.t(title)).setMessage(ink.t(text)).setPositiveButton(ink.t("知道了"), null).show();
    }

    /**
     * 后台执行并刷新快照。dialog 为 true 时总是弹出完整结果；否则只在失败时弹出。
     * 返回 false 表示已有操作在执行，本次未提交。
     */
    private boolean perform(String label, Work work, boolean dialog) {
        if (busy) { Toast.makeText(this, ink.t("请等待当前操作完成"), Toast.LENGTH_SHORT).show(); return false; }
        busy = true; message = label; messageFailed = false;
        render();
        WORKER.execute(() -> {
            String result;
            boolean failed = false;
            try {
                result = work.run();
                // inspect 不抛异常，以固定前缀返回未完成。
                failed = result != null && result.startsWith("环境检查未完成");
            } catch (Exception error) { result = reason(error); failed = true; }
            ApplicationServices.Snapshot next = null;
            String error = "";
            try { next = services.snapshot(); } catch (Exception failure) { error = reason(failure); }
            String text = result == null ? "" : result;
            boolean failure = failed;
            ApplicationServices.Snapshot loaded = next;
            String loadFailure = error;
            runOnUiThread(() -> {
                busy = false; pendingId = "";
                if (isFinishing() || isDestroyed()) return;
                if (loaded != null) snapshot = loaded;
                loadError = loadFailure;
                message = failure && !text.startsWith("环境检查未完成") ? "未完成：" + text.trim() : text.isEmpty() ? label + "：完成" : text.trim();
                messageFailed = failure;
                output = text;
                render();
                if (dialog || failure) showText(failure ? "未完成" : "操作结果", text);
            });
        });
        return true;
    }
    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private void exportZip() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "inkcontrol_backend-v" + BuildInfo.VERSION + ".zip");
        try { startActivityForResult(intent, EXPORT_REQUEST); }
        catch (android.content.ActivityNotFoundException error) { showText("无法导出", "系统没有可用的文件保存界面。"); }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_REQUEST && result == RESULT_OK && data != null && data.getData() != null) perform("导出 ZIP", () -> {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { if (out == null) throw new java.io.IOException("无法写入目标文件"); services.exportZip(out); }
            return "ZIP 已校验并导出，请在 Magisk 中从本地安装。";
        }, true);
    }

    // ───────────────────────── 排版工具 ─────────────────────────

    private TextView heading(String value, float sp) { return ink.text(value, sp, Ink.BOLD); }
    private TextView paragraph(String value, float sp) { return ink.text(value, sp, Ink.REGULAR); }
    private TextView note(String value) { TextView view = paragraph(value, 15); view.setPadding(ink.dp(4), ink.dp(24), 0, 0); return view; }
    private LinearLayout column(View... children) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (View child : children) column.addView(child);
        return column;
    }
    private LinearLayout card(View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ink.outline(14));
        card.setPadding(ink.dp(18), ink.dp(16), ink.dp(18), ink.dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, ink.dp(8), 0, 0);
        card.setLayoutParams(params);
        card.addView(content);
        return card;
    }
    /** 给视图加上外边距；保留调用方之后传入的 LayoutParams 宽高。 */
    private View spaced(View view, int topDp) {
        ViewGroup.LayoutParams current = view.getLayoutParams();
        LinearLayout.LayoutParams params = current instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) current
                : new LinearLayout.LayoutParams(current == null ? -1 : current.width, current == null ? -2 : current.height);
        params.topMargin = ink.dp(topDp);
        view.setLayoutParams(params);
        return view;
    }
    private View withMargin(View view, int leftDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = ink.dp(leftDp);
        view.setLayoutParams(params);
        return view;
    }
}
