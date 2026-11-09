# 简阅 1.4.7 验证

## 版本与构建

- 基线：1.4.6（versionCode 68）。
- 目标：1.4.7（versionCode 69）。
- 包名 `com.joe.jmlite`，最低 API 26，targetSdk 36，`arm64-v8a`。
- Release 构建成功：`work/release-1.4.7.log`，`BUILD SUCCESSFUL`。
- APK metadata：`versionCode='69' versionName='1.4.7'`，`arm64-v8a`。
- `zipalign -c 4` 通过；APK v2 签名校验通过，证书 SHA-256 为 `80a767202c9dd934d5bba2ec97a779205749bda57c2110c64390850c14022c6c`。
- APK SHA-256：`ac2e4ef0724edb61c354fe62cf5a04d0f027e46681927aab544743cd00716d1f`。

## 自动化测试

- JVM：355/355 成功，0 失败（`work/jvm-1.4.7.log`）。
- Android 仪器测试：
  - ReaderFrameworkTest 2/2、BookListRecyclingTest、DownloadInputFocusTest、FailedSourcesDialogTest、ShelfLongPressTest 均通过。
  - Android15BackTest 的 4 项“系统边缘返回手势注入”用例在当前 Android 15 模拟器上未收到 SystemUI 提交，剩余 2 项通过；这是模拟器输入注入问题，不涉及本次改动的设置重置或首次启动路径。

## 首次安装实测（Android 15）

- 清除应用数据后首次启动，使用手册立即弹出。
- 1.2 秒时“我已知晓”为灰色、`enabled=false`；约 6 秒后恢复主题蓝色、`enabled=true`（截图：`first-disabled3.png`、`first-enabled3.png`；UI XML：`first4.xml`、`first5.xml`）。
- 手册显示期间后台静默搜索“禁漫天堂”编号 1，完成后 SharedPreferences 为：
  - `first_run_search_started=true`
  - `first_run_search_completed=true`
- 搜索结果、搜索进度、开始/结束通知均未显示；通知转储中没有该应用的已发布通知。
- 确认手册后再次启动，使用手册不再出现，静默搜索不再执行。

## 阅读器设置重置

- “恢复默认设置”确认框文案改为只恢复“常规”和“滤镜”。
- 重置列表已移除阅读模式相关项：不再删除 `defaultReadingMode`、`defaultOrientationType`、图片缩放/裁剪、双页、导航模式等设置，也不再调用 `setReadingMode`/`setOrientation`。
- 保留“常规”全部选项和“滤镜”全部选项的默认恢复。

## 其他

- 首次静默搜索在 IO 协程中执行，无 Activity/回调依赖，不阻塞首帧。
- 使用手册的 5 秒 Handler 在关闭/确认时移除回调，避免泄漏。
- 版本 1.4.7，versionCode 69；GitHub 旧版本 release/tag 将全部删除，只保留 v1.4.7。
