# 简阅 3.7.0

简阅是 Android 漫画阅读应用，提供本地书架、JM 漫画入口、中文图源搜索与浏览、下载和阅读历史。阅读器和图源运行框架基于 Mihon，JM 接口与图片还原参考 JMComic-Crawler-Python，内置扩展来自 Keiyoushi；各项目的来源、用途和许可证见下方说明。

- 项目：[esn-idiotzzz/jianyue](https://github.com/esn-idiotzzz/jianyue)
- 下载：[最新正式版](https://github.com/esn-idiotzzz/jianyue/releases/latest)
- 系统：Android 8.0 及以上，`arm64-v8a`
- 包名：`com.joe.jmlite`

## 3.7.0 更新

- 修复单独图源页空输入时反复展开搜索、点击搜索造成热门书籍重新加载的问题，保留分页及失败重试。
- 搜索展开改为固定标题槽内的绘制裁剪，不再逐帧重测书架，也不再因标题隐藏改变标题栏高度。
- 返回顺序为先收键盘、再收搜索框、最后离开页面；“×”仍可主动关闭搜索。
- 预返回接入所有应用自建页面及可取消弹窗，阅读器内部、阅读器返回及加载入口保持排除。提交手势直接导航，取消才回弹；移除逐帧透明度与圆角刷新，使用临时硬件层并在退出后释放。
- 新增独立隐私书架：书架顶部下拉三分之一屏幕进入，支持四位数字密码、会话解锁、独立分类和收藏目录，书籍/书夹仅能手动移入移出。
- 下载前检查普通/隐私书架的本地记录，已存在时可取消下载或验证密码后跳转位置。
- 更新检查按每次打开应用检查一次，结果在当前页面显示。优先读取官方 Release 的 `update.json`，不可用时再查 GitHub API；不进行前台周期轮询；使用缓存重新验证及 API 限流退避。

## 3.6.0 更新

- 首次打开显示可滚动的六项使用手册，点击底部“我已知晓”后不再弹出；手册确认后再检查版本更新。
- 本地下载、导入漫画及图片目录不能放入在线书籍夹或其子夹，尝试时在屏幕下方显示“本地漫画”胶囊提示，约 3 秒自动消失。
- 长按拖动书籍时保留封面的四个圆角和标题；书架刷新复用下载快照，减少重复磁盘扫描。
- 全网漫画详情显示“上次阅读：具体章节”及页数位置，继续支持从头阅读和续读。
- 书架目录、展开搜索支持预返回手势反馈；阅读器返回 App 保持原有效果，不启用预返回。

## 3.5.0 更新

- JM 下载按钮向行中央融合，再展开为下载进度卡；保留两按钮原有样式，进度以 `1/20` 显示已下载页数与总页数。
- 实际下载完成后通知书架与最近下载刷新，全网漫画也在章节真实落地后加入书架和下载记录。
- 导航顺序为书架、下载、历史、仓库；点击仓库的“<不行请尝试魔法~>”直接打开关于。
- 书架增加与图源浏览一致的展开搜索，支持跨书籍夹搜索 JM 编号和漫画名称，展开时隐藏“+”。
- 最近下载仅显示最近 3 本；点击标题查看全部下载记录并搜索，全部记录页始终显示封面和名称，不提供睁眼/闭眼开关。
- 下载记录独立保存，删除本地书籍保留记录；长按记录可手动移除记录而保留书籍。现有 JM 完整下载会导入，已被旧版彻底删除的记录无法凭空恢复。
- 搜索输入有文字时显示带圆圈的清空按钮；“搜索全网漫画”提示末尾添加与文字等高的透明魔法棒图标。
- 最近下载隐藏时，JM 保持原来的编号显示；全网漫画隐藏封面，书名替换为图源名称。

继续保留 GitHub 更新提示、历史空状态修复、同编号 JM 已入架检查、搜索与封面有界并发、书架拖动合并和排序等既有功能。

## 开源项目介绍与致谢

| 项目 | 在简阅中的用途 | 许可证与说明 |
| --- | --- | --- |
| [Mihon](https://github.com/mihonapp/mihon) | Android 阅读器、分页/条漫、图片缩放与手势、图源 API、网络与本地阅读基础；保留并适配相关源码 | Apache-2.0；保留上游版权，源码位于 `mihon/`，许可证见 [mihon/LICENSE](mihon/LICENSE) |
| [JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python) | JM API、接口加解密及图片还原方法的 Android/Java 适配参考；应用不运行 Python 爬虫 | MIT；见 [LICENSE-JMComic.txt](app/src/main/assets/LICENSE-JMComic.txt) |
| [Keiyoushi extensions-source](https://github.com/keiyoushi/extensions-source) / [extensions](https://github.com/keiyoushi/extensions) | 内置中文图源扩展及其发布 APK；通过 Mihon 图源 API 加载 | 扩展仓库 Apache-2.0；保留各 APK 内第三方声明，95 个扩展/106 个中文图源的来源、版本及校验值见 [清单](app/src/main/assets/bundled-sources.json) 和 [许可证说明](app/src/main/assets/LICENSES-extensions.txt) |
| [Apache Commons](https://commons.apache.org/)、[XZ for Java](https://tukaani.org/xz/java.html)、[Junrar](https://github.com/junrar/junrar)、[SLF4J](https://github.com/qos-ch/slf4j) | 本地压缩包读取、解压与相关工具支持 | Apache-2.0、0BSD、UnRAR 限制许可、MIT 等分别适用；见 [LICENSE-archives.txt](app/src/main/assets/LICENSE-archives.txt) |

其他网络、图片、界面、数据库、图标和构建依赖的介绍与链接集中在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。简阅是独立维护的适配项目，不代表上述上游项目；各组件保持自己的许可证，不能把整包一概视为同一种许可证。

## 构建

需要 JDK 21、Android SDK platform `37.2`、Build Tools `37`，首次构建需要网络下载 Gradle 和 Maven 依赖。

```powershell
.\build-mihon.ps1 -JavaHome C:\path\to\jdk21 -AndroidHome C:\path\to\android-sdk
```

输出位于 `build/`。项目不发布签名私钥、签名密码或本机工具链。构建脚本在没有本地密钥时会生成自己的签名；这种签名不能覆盖安装官方发布包。发布维护者须妥善保留原签名。

应用源码位于 `app/src/main/`，Mihon 适配工程位于 `mihon/`。当前 JVM 逻辑测试位于 `mihon/app/src/test/`；`tests/java/` 和 `build-legacy-tests.ps1` 为旧测试，不作为当前验证结果。每版实际构建、测试和设备验证边界以该版 Release 随附验证说明为准。

## 发布后如何触发更新

1. 提升应用版本名及 `versionCode`，例如 `3.7.0` → `3.7.1`，并用相同发布密钥签名。
2. 在本仓库创建对应 GitHub Release，使用可比较的标签，例如 `v3.7.1`，填写更新说明，同时上传 APK 和构建脚本生成的 `build/update.json`。公开 APK 名称须与元数据中的 `JianYue-版本-arm64-v8a.apk` 一致。
3. 将它发布为最新正式版。草稿和预发布不用于正式版更新提示；Release 正文会显示在应用更新弹窗里。
4. 已安装支持更新检查版本的手机在下次打开应用时检查版本。仅推送源码、创建空标签或上传到其他仓库不会触发更新。

检查更新需要手机能访问 GitHub。3.7.0 起优先读取最新正式 Release 附带的更新元数据，减少对匿名 API 配额的依赖；元数据缺失或无效时回退 API。每次打开检查一次，主元数据通道失败时尝试API；限流时遵守服务器要求。应用不自动下载或安装 APK。尚未含有此功能的 3.1.0 需要先手动安装 3.3.0 或更高版本。

## 内容来源

本项目不托管漫画内容。在线内容、图源可用性与账号状态取决于对应站点和用户网络；阅读、下载及分享请遵守内容权利人与站点的要求。
