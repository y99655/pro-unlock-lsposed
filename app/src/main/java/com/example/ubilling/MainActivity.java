package com.example.ubilling;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * kill vip 模块的设置页 —— 给每个 HOOK 通道 / 去广告功能一个"打勾总开关"(v1.12)。
 *
 * 勾选状态存进模块自己的 SharedPreferences("ubilling_settings")；被 hook 的目标 App
 * 进程里用 Settings.channelOn()（底层 XSharedPreferences）读到同一份配置。因此：
 *   - 勾选某通道 -> 该通道对 LSPosed 作用域里勾选的 App 生效；
 *   - 取消勾选 -> 该通道完全不挂载（相当于临时移除该通道）。
 *
 * ★ 生效时机：改完勾选后，请【软重启（LSPosed 内）】或【手动重启被 hook 的目标 App】，
 *   让新配置被目标进程重新读到。若勾选"全关"，建议同时把不想处理的 App 从作用域去掉。
 *
 * 默认全部开启（向后兼容：不打开本页时，行为与 v1.11 完全一致）。键名与 Settings 类一致：
 *   ch_billing/ch_uvip/ch_netlab/ch_methodrule/ch_autovip/ch_db/ch_adblock。
 */
public class MainActivity extends Activity {

    private static final String PREFS = Settings.PREFS_FILE; // "ubilling_settings"

    /** 每个通道一行：{ 键, 标题, 说明 }。 */
    private static final String[][] CHANNELS = {
            {"ch_billing",    "【A】Google Play Billing 解锁",
                    "针对接入 Google Play Billing SDK 的 App，回灌\"已购\"记录实现解锁。"},
            {"ch_uvip",       "【B】SharedPreferences 自动 VIP (UVip)",
                    "拦截 SP 的 getXxx，按付费/会员/PRO/去广告/到期多语义回灌解锁值（含观测学习）。"},
            {"ch_netlab",     "【D】联网鉴权抗 hook 自测 (NetLab)",
                    "模拟 OkHttp 响应篡改/SSL pinning/WebView JS 注入面（默认仅观测）。仅自有/授权 App。"},
            {"ch_methodrule", "【E】具名方法返回值改写 (MethodRule)",
                    "按规则表把指定\"类.方法\"返回强制改写（规则为空即跳过）。仅自有/授权 App。"},
            {"ch_autovip",    "【F】VIP/PRO 自动盲扫 (Auto)",
                    "按类名/方法名/结构盲扫会员判定并注入（两级闸门，宽泛默认仅观测）。"},
            {"ch_db",         "【G】SQLite/DB 会员盲扫 (DB)",
                    "hook SQLite 读取出口，按列名语义改写会员列（默认仅观测）。"},
            {"ch_adblock",    "【I】第三方广告 SDK 去广告 (AdBlock)",
                    "v1.13 默认放行广告类防闪退(仅观测[UAd])；确需强屏蔽请改 HARD_BLOCK=true。"},
    };

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("kill vip — 通道开关");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(0, 122, 255));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("打勾 = 该通道对 LSPosed 作用域勾选的 App 生效；\n"
                + "不打勾 = 该通道完全不挂载。\n"
                + "\n"
                + "★ 改完需【软重启 / 重启目标 App】才生效。\n"
                + "★ 默认全部开启；只对你自己/获授权 App 使用。");
        sub.setTextSize(13f);
        sub.setTextColor(Color.rgb(120, 120, 120));
        sub.setPadding(0, dp(6), 0, dp(4));
        root.addView(sub);

        // 全开 / 全关 便捷按钮
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(4), 0, dp(8));
        Button allOn = new Button(this);
        allOn.setText("全部开启");
        allOn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAll(true); }
        });
        Button allOff = new Button(this);
        allOff.setText("全部关闭");
        allOff.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAll(false); }
        });
        btnRow.addView(allOn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp2.leftMargin = dp(8);
        btnRow.addView(allOff, lp2);
        root.addView(btnRow);

        // 逐通道勾选框
        for (String[] ch : CHANNELS) {
            addChannelRow(ch[0], ch[1], ch[2]);
        }

        TextView foot = new TextView(this);
        foot.setText("广告护栏：B/F/G 注入前会跳过\"广告服务控制上下文\"（防 VIP 解锁把广告激活），"
                + "跳过分录 [UAdGuard]。去广告请勾【I】。");
        foot.setTextSize(12f);
        foot.setTextColor(Color.rgb(150, 150, 150));
        foot.setPadding(0, dp(8), 0, 0);
        root.addView(foot);

        setContentView(sv);
    }

    private void addChannelRow(final String key, String title, String desc) {
        CheckBox cb = new CheckBox(this);
        cb.setText(title + "\n" + desc);
        cb.setTextSize(14f);
        cb.setPadding(0, dp(4), 0, dp(4));
        cb.setChecked(prefs().getBoolean(key, true));   // 默认开
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                prefs().edit().putBoolean(key, isChecked).commit();
            }
        });
        root.addView(cb);
    }

    private void setAll(boolean val) {
        android.content.SharedPreferences.Editor e = prefs().edit();
        for (String[] ch : CHANNELS) e.putBoolean(ch[0], val);
        e.commit();
        // 刷新 UI 勾选态
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v instanceof CheckBox) ((CheckBox) v).setChecked(val);
        }
    }

    private android.content.SharedPreferences prefs() {
        // MODE_WORLD_READABLE：让 LSPosed(XSharedPreferences) 能在被 hook 的进程里读到；
        // LSPosed 的"New XSharedPreferences"会自动让此模式文件可跨进程读取(需 xposedminversion>=93)。
        return getSharedPreferences(PREFS, MODE_WORLD_READABLE);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
