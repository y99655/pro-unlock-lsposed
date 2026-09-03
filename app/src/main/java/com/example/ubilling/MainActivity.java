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
        tv.setText("Universal Billing Hook (LSPosed)\n\n"
                + "对任意接入 Google Play Billing SDK 的应用生效，无包名白名单。\n\n"
                + "原理：反射恒定不混淆的官方 Billing SDK 类，\n"
                + "拦截 queryPurchasesAsync 回灌假“已购”；\n"
                + "自动探测 App 真正查询的 SKU，并叠加内置 SKU 表。\n\n"
                + "使用：在 LSPosed 作用域勾选目标 App，重启即可。\n"
                + "排查：LSPosed 日志过滤 [UBilling]。");
        tv.setTextSize(15f);
        tv.setPadding(40, 40, 40, 40);
        setContentView(tv);
    }
}
