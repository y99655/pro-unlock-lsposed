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
        tv.setText("kill vip (LSPosed)  v1.1\n\n"
                + "通用 VIP / PRO 解锁模块。\n\n"
                + "三通道：\n"
                + "A. Google Play Billing 回灌（任意 App）\n"
                + "B. SharedPreferences 全兼容 + 观测学习（任意 App）\n"
                + "C. 选定 App 精确激活（com.mobilecad.app）\n\n"
                + "使用：LSPosed 作用域勾「系统框架」+ 目标 App，重启即可。\n"
                + "排查：LSPosed 日志过滤 [UBilling] / [UVip] / [UPro]。");
        tv.setTextSize(15f);
        tv.setPadding(40, 40, 40, 40);
        setContentView(tv);
    }
}
