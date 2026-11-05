# 简阅 3.3.0

## 本版更新

1. 打开应用或回到主界面时，后台检查本仓库的最新 GitHub 正式 Release。发现更高版本且安装包已上传完成后，弹窗显示最新版本及该 Release 的更新内容。
2. 弹窗右下角提供“取消更新”和“Github”。点击“Github”打开该版本的 GitHub 发布下载页；取消不永久忽略此版本。同一主界面实例对同一版本只提示一次，避免返回阅读器时反复打扰。
3. 历史页右上角三个点只保留“关于”，去除 API 填写入口；保留既有网络逻辑与已保存设置。
4. “关于”新增可点击的 GitHub 项目地址。补充 README、第三方项目介绍及许可证索引，保留原有开源许可证和声明。

## 安装与后续更新

- 包名 `com.joe.jmlite`；版本 `3.3.0`；versionCode `13`；Android 8.0 起；仅 `arm64-v8a`。
- 沿用 3.1.0 的发布签名。原版 3.1.0 尚无更新检测，需要先手动安装本版。
- 今后发布更高数字版本的正式 Release（例如 `v3.3.1`），填写更新说明并附上 APK，手机下次打开应用即可检查。仅推送源码或上传空标签不会触发提示。
- 检查需要能访问 GitHub，超时或请求受限时静默跳过；不自动下载或安装。3.3.0 检查到线上同为 3.3.0 时不会弹窗。

## 验证结果

- Release 构建成功，使用 JDK 21 和现有 Android SDK；APK v2 签名及 zipalign 校验通过。
- 签名证书 SHA-256：`80a767202c9dd934d5bba2ec97a779205749bda57c2110c64390850c14022c6c`，与 3.1.0 一致。
- JVM 测试 66 项通过、0 失败，包括原有 53 项和新增的 13 项版本比较、发布信息解析及异常响应测试。
- JVM 测试使用实际 AGP 单元测试 classpath 和项目附带 runner，在英文路径的同一份已暂存源码副本执行；中文路径下的 Java 启动器 classpath 读取失败，未计作通过。
- 离线核对包名、版本、启动 Activity、最低/目标 SDK 与 ABI；95 个内置扩展 APK 和 5 个原许可证/声明资产逐项校验均未改变。
- 发布 APK：25,545,330 字节。SHA-256：`b68b071a5ba24a00b0ab0640c5e8e1c0f5539dcf02be8df6a7da7c4ae12bdeb6`。
- 源码发布排除本机工具链、构建缓存、签名私钥/密码、本机配置及未启用的上游 Firebase 配置。

当前没有连接的 Android 手机，未实测真机弹窗视觉、浏览器跳转或覆盖安装。构建保留了既有 Commons Compress 对可选 ZstdInputStream 的 R8 警告，本版未修改归档实现；不据此宣称 Zstandard 格式已验证。

## 开源来源

主要来源为 [Mihon](https://github.com/mihonapp/mihon)、[JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python) 和 [Keiyoushi](https://github.com/keiyoushi/extensions-source)。具体用途、其他依赖和各自许可证见仓库的 [THIRD_PARTY_NOTICES.md](https://github.com/esn-idiotzzz/jianyue/blob/main/THIRD_PARTY_NOTICES.md)。
