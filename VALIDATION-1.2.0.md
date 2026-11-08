# 简阅 1.2.0 验证

安装包：`JianYue-1.2.0-arm64-v8a.apk`。本地交付并上传 GitHub；1.1.8/1.1.9 及更早版本保留。

- 包名 `com.joe.jmlite`，versionName `1.2.0`，versionCode `54`，最低 API 26，targetSdk 36，arm64-v8a。
- 大小 25,809,114 字节；SHA-256：`4a951aac56c8c1c58f9e4e3ef57721aae2d7004049faf23226eff758b02fcc95`。
- 签名证书 SHA-256：`80a767202c9dd934d5bba2ec97a779205749bda57c2110c64390850c14022c6c`；签名、zipalign、badging 校验通过。

## 本次修复

1. **进入动画未完成前返回手势统一无效**
   - `PageMotion.enter` 用 ValueAnimator 真实生命周期；`PageMotion.folder` 用 push 动画监听。
   - 手势开始时若进入未完成：不收缩、不预览、不导航；持按到进入完成再松手也无作用；进入完成后下一次手势才恢复。
   - 书籍信息、历史漫画详情、下载/仓库全网搜索、最近下载、仓库图源与书籍夹保持一致，取消原 enterDirect 移出分支，避免白屏。
2. **五个页面预返回恢复**
   - 根因是 DecorView Animation 被窗口转场吞掉，`isEntering` 永久 true。
   - Android 15 测试打开六类页面 450ms 后断言 `isEntering=false`，恢复缩放、目标预览和灰度。
3. **灰度到目标页过渡**
   - 松手不再瞬间把遮罩 alpha 归零，改为 160ms 淡出；目标视差 220ms 回正，减少闪一下。
4. **目标页清晰度**
   - BackPreview 全分辨率快照（最多 3 层），减少从 3/4 预览到真实页面的模糊→清晰突变。
5. **图源预返回与封面**
   - RepositoryPanel 打开 OnlineSearch 前补 `BackPreview.capture`。
   - 图源搜索封面自身用 Path 圆角裁剪 + 显式 outline，软件快照也保持圆角；返回热门列表前后不变。
6. **稳定性**
   - 动画结束后的 View 树删除/构建 post 到下一帧，修复快速点击进入/返回的 `ViewGroup.dispatchDraw` 空 child 崩溃。
   - 手工快速打开/返回 15 次无 FATAL，进程存活。

## 验证结果

- **359 项 JVM 测试全部成功**，0 失败、0 跳过、0 中止（`release-1.2.0-tests.log`）。
- **Android 15 / API 35 模拟器 5 项仪器测试全部成功**（`a15-120.log`，`OK (5 tests)`）：
  - 24 次真实 SystemUI 左右快速交替返回，方向/预览标记正确、连续回调可用。
  - 主页系统侧滑回桌面再热恢复 6 轮。
  - 书籍夹返回 4 轮，胶囊快照/真实胶囊正常。
  - 六类工具栏按钮返回在窗口内 pop 后关闭、无纯黑帧，并断言五个子页面进入态结束。
  - 进入动画中侧滑不导航，进入完成后再次侧滑正常回根目录。
- 模拟器条件：Android 15 / API 35，2560×1440 横屏；临时 `qemu.hw.mainkeys=0` 并重启 framework 以附着 SystemUI 边缘手势；测试后恢复。

## 未覆盖

- 未覆盖所有厂商 ROM、长期使用、全应用帧率。
- 真实图源登录/Cookie、浏览器多级网页历史和弹窗未做真实站点交互。
- 未用逐帧图像识别统计目标页重复像素；结论基于全分辨率快照、层级和仪器断言。
- 图源网站、下载、阅读、书籍数据结构未改。

## 主要源码

- `app/src/main/java/com/joe/jmlite/MainActivity.java`
- `app/src/main/java/com/joe/jmlite/PredictiveBack.java`
- `app/src/main/java/com/joe/jmlite/PageMotion.java`
- `app/src/main/java/com/joe/jmlite/BackPreview.java`
- `app/src/main/java/com/joe/jmlite/AllDownloadsActivity.java`
- `app/src/main/java/com/joe/jmlite/BookInfoActivity.java`
- `mihon/app/src/main/java/com/joe/jmlite/OnlineSearchActivity.kt`
- `mihon/app/src/main/java/com/joe/jmlite/OnlineMangaActivity.kt`
- `mihon/app/src/main/java/com/joe/jmlite/RepositoryPanel.kt`
- `mihon/app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt`
- `mihon/app/build.gradle.kts`、`build-mihon.ps1`
- 测试：`PredictiveBackTest.kt`、`Android15BackTest.kt`
