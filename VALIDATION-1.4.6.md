# 简阅 1.4.6 验证

## 版本与基线

- 基线：1.4.3 正式版（versionCode 65，commit `79f58454ddce77240a0b59ab9af276c650103ba9`）。
- 目标：1.4.6（versionCode 68），跳过已废弃并从 GitHub 移除的 1.4.4（66）、1.4.5（67）。
- 包名 `com.joe.jmlite`，最低 API 26，targetSdk 36，`arm64-v8a`。
- 正式签名证书 SHA-256：`80a767202c9dd934d5bba2ec97a779205749bda57c2110c64390850c14022c6c`。

## 构建与 APK

- Release 构建成功：`work/release-1.4.6.log`，`BUILD SUCCESSFUL`。
- APK metadata：`versionCode='68' versionName='1.4.6'`，`arm64-v8a`，`sdkVersion:'26'`。
- `zipalign -c 4` 通过。
- APK v2 签名校验通过，证书 SHA-256 与正式证书一致。
- APK SHA-256：`22a70c6727ee67e0fb525d450feed7ed152c01ef44121dd528517492dbfa4fbb`。

## 自动化测试

- JVM：355/355 成功，0 失败（`work/jvm-1.4.6-final3.log`；`tests/jvm-runner/results.log`）。
- Android 仪器测试：12/12 成功（`work/instrument-full-1.4.6.log`）。
  - Android15BackTest 6/6（系统手势导航已开启）。
  - ReaderFrameworkTest 2/2：单章全书预取、位置优先、可见页保护、队列/线程/临时目录释放。
  - BookListRecyclingTest、DownloadInputFocusTest、FailedSourcesDialogTest、ShelfLongPressTest 各 1/1。

## 模拟器实测（Android 15）

- 在“浏览”输入本地已有编号 123，点击“在线阅读”，出现“本地已有漫画，是否跳转”，右下角“取消”“跳转”（UI XML：`uip.xml`）。
- 点击“跳转”进入书架/书籍夹并定位到本地书籍；封面显示主题蓝框，触摸屏幕后蓝框消失（截图：`jump.png`）。
- 本地书在书籍夹时，跳转后进入对应书籍夹；点击左上角返回回到导航栏“浏览”页，不新增页面（UI XML：`uifold.xml`、`uiback.xml`）。
- 普通书架根目录跳转直接切换书架页并定位，不新增 Activity。
- 升级路径：先安装 1.4.5（versionCode 67），启动后更新弹窗显示“最新版本：1.4.6”并提供“下载 APK”；随后覆盖安装 1.4.6（versionCode 68）成功，书架、书籍夹与本地书籍数据保留（截图：`update146.png`、`upgrade.png`）。
- 手势导航模式设置为 gestural 后运行系统返回测试，12 项仪器测试全部通过。

## 代码改动

- 阅读器视觉/返回行为回退到 1.4.2：`WebtoonViewer.kt`、`WebtoonTransitionHolder.kt` 与 1.4.2 完全一致。
- 在线图片预取：
  - 单章（JM 直读的“全书”）从当前位置向两侧发散，最终缓存全部页面。
  - 多章在线漫画等待当前章节全部页面完成后，按详情章节列表加载前二、后二章节；位置变化时更新窗口并保持前后各两章。
  - `OnlineSession` 使用 6 个图片线程、有界任务队列、失败页隔离和退出清理。
- JM 在线阅读本地命中提示、跳转定位、蓝框触摸消失、隐私/书籍夹返回浏览逻辑。
- 在线历史只在浏览页搜索全网漫画、仓库图源进入时置顶；历史详情/阅读保存进度不改变顺序。
- 仓库图源热门列表进入的详情封面占位改为 22dp 书籍线稿图标，圆角外框不变。

## 未覆盖/限制

- 真实 JM 站点与真实第三方图源的网络连通性、限速和长时间阅读未做端到端压力测试。
- 隐私书架跳转在模拟器上验证了无密码定位代码路径；不同 OEM 的返回手势仍以 Android 15 gestural 为准。
- 在线图片预取遵循阅读器“仅 WiFi 预加载”设置；关闭该限制时才在移动网络执行完整预取。

## GitHub 发布

- `v1.4.4` 的正式 release 和 tag 已删除；`v1.4.5` 在 GitHub 不存在（仅本地废弃构建）。
- `v1.4.6` 已发布为 latest，target commit `2c42cca954c292c6b3afe552642d88ff705402ef`，body 含 `jianyue-version-code:68`。
- `releases/latest/download/update.json` 返回 1.4.6 / versionCode 68 / APK SHA-256 `22a70c...4fbb`。
