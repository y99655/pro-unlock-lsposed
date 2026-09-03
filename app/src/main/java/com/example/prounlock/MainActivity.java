package com.example.prounlock;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 极简占位 Activity（模块本身不需要 UI，仅便于在桌面找到入口）。
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("ProUnlock (LSPosed)\n\n针对 com.mobilecad.app 的 PRO 解锁模块。\n"
                + "在 LSPosed 中勾选目标应用并重启即可生效。\n"
                + "原理：Hook Google Play Billing 的 queryPurchasesAsync，\n"
                + "回灌 unlock_pro / unlock_pro_2 的已购记录。");
        tv.setTextSize(16f);
        tv.setPadding(40, 40, 40, 40);
        setContentView(tv);
    }
}
