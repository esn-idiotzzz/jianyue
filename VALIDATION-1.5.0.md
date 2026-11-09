# 简阅 1.5.0 验证

## 版本与构建

- 基线：1.4.7（versionCode 69）。
- 目标：1.5.0（versionCode 70）。
- 包名 `com.joe.jmlite`，最低 API 26，targetSdk 36，`arm64-v8a`。
- Release 构建成功：`work/release-1.5.0.log`，`BUILD SUCCESSFUL`。
- APK metadata：`versionCode='70' versionName='1.5.0'`，`arm64-v8a`。
- `zipalign -c 4` 通过；APK v2 签名校验通过，证书 SHA-256 为 `80a767202c9dd934d5bba2ec97a779205749bda57c2110c64390850c14022c6c`。
- APK SHA-256：`2f8ee94e0db8b29fe168cb89909772a8c15c74cec5c84f7b53acba9cdb85f0d4`。

## 自动化测试

- JVM：355/355 成功，0 失败（`work/jvm-1.5.0.log`）。
- Android 核心仪器测试：6/6 成功（ReaderFrameworkTest 2、BookListRecyclingTest、DownloadInputFocusTest、FailedSourcesDialogTest、ShelfLongPressTest）。
- Android15BackTest 的 4 项“系统边缘返回手势注入”用例在模拟器上仍存在 SystemUI 注入不提交的问题；与 1.4.7 一致，非本次封面/历史改动路径。

## 模拟器实测（Android 15）

### JM 历史封面

- 构造 JM 历史记录 999 + `files/jm-history-covers/999.jpg`，历史 JM 漫画列表正常显示橙色封面。
- 完全强制停止并重新打开软件后，历史 JM 封面仍显示，不依赖阅读器临时缓存。
- 长按该历史记录 → 删除 → 确认后：
  - `jm_online_history.json` 的 `items` 为空；
  - `files/jm-history-covers` 为空；
  - `files/history-covers` 为空。
- 无封面历史记录 998 显示 22dp 书籍线稿占位图标（截图：`histplaceholder.png`）。
- 历史列表与 JM 漫画详情均使用 `HistoryCovers` 同一份持久封面缓存。

### 阅读器“设为封面”

- 本地两页测试书 777：第 1 页红色、第 2 页绿色。
- 阅读到第 2 页，长按 → “设为封面” → 确定。
- `Store.meta.json` 写入 `cover=file:///.../files/local-covers/777.cover` 和 `coverUpdated`，原 `updated=1000` 未被改写，下载排序不变。
- 书架列表和书籍详情页均显示绿色第 2 页；强制停止并重开软件后仍保持绿色。
- 在线搜索/在线阅读记录不满足 `jmlite://library/download:*`，不会写入书架封面。

### 其他

- 书架、历史、全部下载、本地/在线漫画详情等封面占位统一为 22dp 书籍线稿图标，外框圆角保持原尺寸。

## 代码改动

- `JmPageLoader`：JM 历史封面保存到 `filesDir/jm-history-covers`，旧 `cacheDir` 封面自动迁移；保存后触发历史封面刷新标记。
- `HistoryCovers`：支持 JM 本地 file 封面，持久磁盘缓存不按大小淘汰；列表与详情共用；删除历史时删除原文件和缓存。
- `CoverLoader`：缓存键包含自定义封面路径/版本，优先加载阅读器设置的封面。
- `Store`：新增 `setCover`，更新封面但不改下载排序时间。
- `LocalCoverStore`：阅读器设置封面后写入本地文件并同步 Store 元数据。
- `ReaderViewModel`：`editCover` 后仅为本地 Manga 同步书架封面；在线记录跳过。
- `BookInfoActivity`：详情封面与列表共用缓存，本地详情恢复时重新读取 Store；占位改线稿图标。
- `MainActivity`：历史行/书架封面使用统一缓存与图标，封面变更后按版本刷新。
- `AllDownloadsActivity`：占位改 22dp 书籍线稿图标。
- 版本 1.5.0；GitHub 只保留本版本。

## 未覆盖/限制

- 没有对真实 JM 站点、真实扩展网络限速和超大数据集做长时间压力测试。
- 系统返回手势 4 项仪器用例受当前模拟器 SystemUI 输入注入影响，未作为本次封面逻辑失败。
