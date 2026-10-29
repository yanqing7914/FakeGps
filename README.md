# FakeGps

免 root 的 Android 模拟定位工具，用于定位相关功能的开发和测试。

基于 Android 公开的测试定位接口（`LocationManager.addTestProvider` / `setTestProviderLocation`），接管系统的 gps 和 network 定位来源。

## 功能

- 地图选点（高德瓦片）、坐标输入（GCJ-02 / BD-09 / WGS84）、地点搜索（Photon / OpenStreetMap）
- 定点模拟、路线模拟（多种速度，可往返）、悬浮摇杆
- 收藏夹、按品牌（小米 / 华为 / 荣耀 / OPPO / vivo / 三星 / 魅族）的设置引导
- 诊断日志导出、崩溃日志导出

## 已知限制

- 依赖系统的模拟定位接口。只读系统定位的 App 能正常收到模拟位置；
  自带定位 SDK 的 App（如高德）在开着移动数据时会用基站 / 网络自行定位，可能在几秒后回到真实位置。
- Android 会给模拟位置打上 mock 标记，部分 App 会识别并拒绝。本项目不隐藏该标记。
- 不支持 HarmonyOS NEXT（HarmonyOS 5 及以上），该系统不能安装 APK，模拟定位接口也只对系统应用开放。

## 构建

需要 JDK 17、Android SDK 35（platform + build-tools 35.0.0）。

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里设置 `sdk.dir`，该文件不入库。

## 测试

```bash
./scripts/regression.sh          # 单元测试 + 编译 + 设备回归测试
./scripts/regression.sh --unit   # 只跑 JVM 单元测试
```

| 层级 | 位置 | 覆盖内容 |
| --- | --- | --- |
| JVM 单元测试 | `app/src/test` | 坐标系转换、路线插值、品牌识别、搜索结果解析、坐标输入解析、诊断日志格式化 |
| 设备回归测试 | `app/src/androidTest` | 开始后 gps/network 报告目标点且带 mock 标记、不接管 fused、订阅方只收到模拟坐标、停止后来源被移除、反复开始/停止稳定、诊断导出可用 |

设备测试默认在名为 `test34` 的 Android 14 模拟器上运行；连着真机时会在真机上运行。
设备测试只能证明标准 Android 上的行为没有退化，测不到 HyperOS 等定制系统的后台策略，也测不到第三方 App 自己的定位逻辑，这部分需要真机手动验证。

## 使用说明

1. 打开开发者选项，在「选择模拟位置信息应用」里选 FakeGps
2. 授予定位和通知权限，按提示允许后台运行（不受电池优化限制）
3. 选点后点「开始模拟」

请只在你有权测试的应用和场景中使用，不要用于考勤打卡、签到等需要真实位置的场合。
