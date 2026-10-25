package com.inkcontrol.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 首页大功能（分类）的显示顺序。
 * 纯界面偏好：不进入功能配置、不参与后端校验，因此单独放在 SharedPreferences 里。
 * 值为分类名以分号连接的序列；空串表示使用默认顺序。
 */
final class UiCategoryOrder {
    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("ui-category-order", Context.MODE_PRIVATE);
    }
    /** 保存的顺序；空串表示默认顺序。 */
    static String saved(Context context) {
        return preferences(context).getString("order", "");
    }
    static void save(Context context, String value) {
        preferences(context).edit().putString("order", value).commit();
    }
    private UiCategoryOrder() { }
}
