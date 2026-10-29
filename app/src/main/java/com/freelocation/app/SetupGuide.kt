package com.freelocation.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * 不同品牌手机"开发者选项 / 模拟位置"的入口不一样，这里按品牌给出对应的步骤。
 */
object SetupGuide {

    enum class Brand(val label: String) {
        XIAOMI("小米 / Redmi / POCO"),
        HUAWEI("华为"),
        HONOR("荣耀"),
        OPPO("OPPO / 一加 / realme"),
        VIVO("vivo / iQOO"),
        SAMSUNG("三星"),
        MEIZU("魅族"),
        OTHER("其他品牌"),
    }

    fun detect(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): Brand {
        val m = (manufacturer + " " + brand).lowercase(Locale.ROOT)
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> Brand.XIAOMI
            "honor" in m -> Brand.HONOR // 荣耀要先于华为判断，老荣耀的 manufacturer 也是 HUAWEI
            "huawei" in m -> Brand.HUAWEI
            "oppo" in m || "oneplus" in m || "realme" in m -> Brand.OPPO
            "vivo" in m || "iqoo" in m -> Brand.VIVO
            "samsung" in m -> Brand.SAMSUNG
            "meizu" in m -> Brand.MEIZU
            else -> Brand.OTHER
        }
    }

    /** 开启开发者模式的步骤。 */
    fun enableDeveloperSteps(b: Brand): String = when (b) {
        Brand.XIAOMI -> "设置 → 我的设备 → 全部参数与信息，连续点击「OS 版本」（老系统是「MIUI 版本」）7 次"
        Brand.HUAWEI -> "设置 → 关于手机，连续点击「版本号」7 次，按提示输入锁屏密码"
        Brand.HONOR -> "设置 → 关于手机，连续点击「版本号」7 次，按提示输入锁屏密码"
        Brand.OPPO -> "设置 → 关于手机 → 版本信息，连续点击「版本号」7 次"
        Brand.VIVO -> "设置 → 系统管理 → 关于手机 → 版本信息，连续点击「软件版本号」7 次"
        Brand.SAMSUNG -> "设置 → 关于手机 → 软件信息，连续点击「编译编号」7 次"
        Brand.MEIZU -> "设置 → 关于手机，连续点击「版本号」7 次"
        Brand.OTHER -> "设置 → 关于手机（可能在「软件信息」或「版本信息」里），连续点击「版本号」7 次"
    }

    /** 开发者选项所在位置。 */
    fun developerOptionsPath(b: Brand): String = when (b) {
        Brand.XIAOMI -> "设置 → 更多设置 → 开发者选项"
        Brand.HUAWEI -> "设置 → 系统和更新 → 开发人员选项"
        Brand.HONOR -> "设置 → 系统和更新 → 开发人员选项"
        Brand.OPPO -> "设置 → 其他设置（或「系统设置」）→ 开发者选项"
        Brand.VIVO -> "设置 → 系统管理 → 开发者选项"
        Brand.SAMSUNG -> "设置 → 开发者选项（在列表最底部）"
        Brand.MEIZU -> "设置 → 辅助功能 → 开发者选项"
        Brand.OTHER -> "设置 → 系统 → 开发者选项（也可以直接在设置顶部搜索「开发者」）"
    }

    /** 选择模拟位置应用的菜单名称。 */
    fun mockAppItemName(b: Brand): String = when (b) {
        Brand.HUAWEI, Brand.HONOR -> "选择模拟位置信息应用"
        Brand.SAMSUNG -> "选择模拟位置应用"
        else -> "选择模拟位置信息应用"
    }

    fun fullGuide(context: Context, b: Brand): String {
        val app = context.getString(R.string.app_name)
        val extra = when (b) {
            Brand.HUAWEI, Brand.HONOR ->
                "\n\n提示：华为/荣耀的这一项在「开发人员选项」里比较靠下，在「调试」分组中。" +
                    "如果找不到，在设置顶部搜索「模拟位置」。"
            Brand.XIAOMI -> "\n\n提示：这一项在「调试」分组中，在「USB 调试」下面不远。"
            else -> "\n\n提示：如果找不到，在设置顶部的搜索框搜索「模拟位置」。"
        }
        return "当前识别为：${b.label}\n\n" +
            "1. 开启开发者模式\n${enableDeveloperSteps(b)}\n\n" +
            "2. 打开开发者选项\n${developerOptionsPath(b)}\n\n" +
            "3. 找到「${mockAppItemName(b)}」，选择「$app」" +
            extra +
            "\n\n完成后返回本应用即可开始。"
    }

    /**
     * 尝试直接打开开发者选项。部分 ROM 不支持标准 Intent，依次尝试，都失败则打开「关于手机」。
     * @return 是否成功打开了开发者选项本身
     */
    fun openDeveloperOptions(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DevelopmentSettings")),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
            ),
        )
        for (i in candidates) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        try {
            context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** 开发者模式是否已经打开。 */
    fun isDeveloperModeOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) {
        false
    }
}
