package com.example.ubilling;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 极简占位 Activity（模块本身不需要 UI，仅便于在桌面找到入口、查看版本说明）。
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("kill vip (LSPosed)\n\n"
                + "通用 VIP / PRO 解锁模块。\n\n"
                + "A. Google Play Billing 回灌（含 Billing SDK 的 App）\n"
                + "B. SharedPreferences 全兼容 + 观测学习\n"
                + "D. 联网鉴权抗 hook 自测\n"
                + "E. 具名方法返回值改写\n"
                + "F. VIP/PRO 自动盲扫\n"
                + "G. SQLite / DB 会员盲扫\n\n"
                + "使用：LSPosed 作用域勾选目标 App，重启生效。\n"
                + "排查：LSPosed 日志过滤 [UBilling] / [UVip] / [UNet]\n"
                + "     / [URule] / [UAuto] / [UDB]。");
        tv.setTextSize(15f);
        tv.setPadding(40, 40, 40, 40);
        setContentView(tv);
    }
}
