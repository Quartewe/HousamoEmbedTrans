<!-- markdownlint-disable MD033 MD041 -->
<p align="center">
  <img alt="LOGO" src="app\src\main\ic_launcher-playstore.png" width="256" height="256" />
</p>

<div align="center">

# HOUSAMO EMBED TRANS

基于 **[shadowhook](https://github.com/bytedance/android-inline-hook)** 的 **[LSPosed](https://github.com/LSPosed/LSPosed)** 东京放课后召唤师(housamo)的注入式剧情翻译
该项目旨在不破坏游戏体验的情况下尽可能的提供高质量的剧情翻译服务, 该项目不收费 **<font color="red">不等于</font>** api调用不付费

## 目录

- [快速开始](#快速开始)
- [功能亮点](#功能亮点)
- [开发文档](#开发文档)
- [问题反馈](#问题反馈)
- [构建](#构建)
- [许可证](#许可证)
- [致谢](#致谢)

## 快速开始

使用本项目前，请阅读[东京放课后召唤师官方利用规约](https://housamo.info/terms-of-service/)。本项目通过注入方式提取、翻译并修改游戏剧情文本，未经权利人许可的使用涉及以下条款：

| 条款 | 条款内容概述 | 本项目涉及的行为 | 适用说明 |
| --- | --- | --- | --- |
| 第 8 条第 1 项 | 禁止未经许可加工、修改、编辑、复制、转载等游戏内容 | 提取剧情、保存 Scene JSON、翻译正文并回写游戏内存 | 与该条款存在直接冲突 |
| 第 15 条第 1 项第 7 号 | 禁止侵犯官方或第三方的知识产权等权利 | 剧情复制、译文或官方资料的传播 | 是否构成侵权取决于具体内容、授权及适用法律，不能仅凭使用 Hook 判定 |
| 第 15 条第 1 项第 17 号 | 禁止妨碍服务提供的程序修改、使用，包括 BOT、作弊工具等 | LSPosed 注入、ShadowHook 拦截及游戏内存回写 | 可能适用；条款包含“妨碍服务提供”的限定，并非所有修改均自动符合 |
| 第 15 条第 1 项第 19 号 | 禁止协助或诱发前述禁止行为 | 提供工具和使用说明，使其他玩家能够执行相关行为 | 以前述行为被认定属于禁止事项为前提 |

依据第 11 条第 1 号及第 15 条末段，官方可以在不事先通知的情况下 **<font color="red">限制、停止账号使用或删除账号</font>** ；开源、免费及学习用途不等于获得官方授权。

以上依据 2026 年 7 月 21 日修订的日文规约进行对照，属于风险说明，不是法律裁定；条款内容及解释以官方原文为准。

**<font color="red">本项目为非官方工具，使用本项目可能导致游戏账号受到限制、暂停或封禁。请在充分了解相关风险后，自行决定是否使用。项目作者不承诺账号安全，也无法协助解除游戏运营方实施的账号处罚。</font>**

~~不敢用可以[b站](https://space.bilibili.com/523591929)关注我, 我会发剧情视频, 但是不一定是你想看的~~

### 1. 如何安装

从 [Releases](https://github.com/Quartewe/HousamoEmbedTrans/releases) 下载发布附件中的 `HousamoEmbedTrans-v版本号-arm64-v8a.apk`。`Source code` 是源码压缩包，不是安装包；若尚无 APK 附件，可参考下方构建说明自行构建。

| 项目 | 当前要求与范围 |
| --- | --- |
| Android | 最低 Android 9（API 28） |
| 架构 | 当前 APK 仅包含 `arm64-v8a`；其他架构或模拟器的 ARM 转译能力需单独确认 |
| 游戏 | 东京放课后召唤师，包名 `jp.co.lifewonders.housamo` |
| 游戏资源适配 | 当前内置运行时资源版本为 `5.19.0`；其他版本需有匹配资源，不能仅修改版本号绕过检查 |
| 模块环境 | root 使用 LSPosed；非 root 按下文使用 JingMatrix/LSPatch 本地模式。不同设备与系统的兼容性仍需实际确认 |

对于不同用户提供了两种安装方法, 下称 该应用(Housamo Embed Trans) 为 `HET`

#### root环境lsposed用户

1. 安装 HET APK，在 LSPosed 中启用 HET 模块。
2. 作用域勾选东京放课后召唤师（`jp.co.lifewonders.housamo`）。
3. 完全退出并重新启动游戏，使模块生效；若框架提示重启，按其提示操作。
4. 打开 HET、授予通知权限，确认启动游戏后 HET 首页显示“游戏已连接”，再进行下面的接口配置。

#### 非root环境lspatch用户

在安装HET之前, 先完成安装[Shizuku](https://shizuku.rikka.app/zh-hans/), [LSPatch](https://github.com/JingMatrix/LSPatch)这两个应用, 这两个应用将作为支持手段, 并打开开发者模式
**并且在`游戏`内保存好你的<u>账号数据</u>, 此过程会<u>重新安装</u>游戏进程, 在以后每次更新游戏后也需要进行<u>相同操作</u>**
(这边建议保存AuthKey, 位置在`Android/data/jp.co.lifewonders.housamo/files/Data/`, 恢复时重新导入文件即可)

1. 安装下载好的 HET APK。
2. 按照 `Shizuku` 的配置流程开启调试权限, 完成 `Shizuku` 的启动
3. 启动后在 `LSPatch` 里获取 `Shizuku` 授权
4. 在 `LSPatch` 搜索并点击 `housamo` (游戏本体)
5. 进入页面后不需要改动任意选项, 修补模式保持 `本地模式` , 点击修补，并按提示完成修补后 APK 的安装
6. 完成修补后点击 `housamo` (游戏本体), 在模块下点击 `Housamo Embed Trans` , 然后点击应用
7. 先打开 `HET` , 然后**授予通知权限**(这将作为应用通知手段), 然后等待状态变成 `等待游戏连接`
8. 打开 游戏本体 , 然后等待 `HET` 状态变成 `游戏已连接`
9. 到`HET`设置页点击最上方的`OBB配置`, 并点击`检查并补齐OBB`, 等待下载完成后 **重启** 游戏本体

关于`OBB配置`的问题, 有兴趣的可以看[这个文档](https://github.com/quartawa/housamo-obb/blob/main/README.md)

### 2. 如何使用

#### 配置翻译接口

进入 **HET → 设置 → 翻译服务**，填写以下内容，最后点击“保存设置”：

| 设置 | 如何填写 |
| --- | --- |
| 协议 | 按服务方提供的接口选择 OpenAI 兼容或 Anthropic Messages |
| Base URL | 填服务方提供的 API 基础地址，而非网页聊天地址。例如 OpenAI 兼容服务可能提供 `https://你的服务域名/v1`；HET 会补齐对应请求端点 |
| API Key | 填所选服务的调用凭据 |
| 模型 | 填服务方要求的完整模型 ID |
| 目标语言 | 选择希望显示的译文语言 |
| 输出长度与上下文上限 | 按模型及服务方实际限制设置，应用默认值不保证适合所有模型 |

流式输出和结果修复等设置在“翻译与修复”中调整。初次使用可以先保留默认提示词；自定义时需保留要求的输出格式。API 请求、重试和摘要可能产生服务方费用。

#### 完成第一次翻译

1. 在系统的 HET 应用信息中检查电池和后台活动限制，允许后台运行；系统提供自启动或后台锁定选项时，可按需开启。不同厂商入口不同，这些设置也不保证进程永远不被系统停止。
2. 打开 HET，再进入游戏，确认首页显示“游戏已连接”。
3. 进入需要翻译的剧情，到 HET“任务”页查看是否生成任务。若出现恢复或排序选择，先完成对应操作。
4. 任务进入翻译后，可回到游戏阅读。译文会尝试更新到游戏中，实际显示时机取决于模型输出进度和游戏运行状态；非流式模式可能要等待完整响应。
5. 若未出现译文，先查看任务详情中的状态与原因，再按下面的排查表处理，避免反复重跑。

#### 剧情与上下文

- **Scene（剧情）**：一段剧情及其译文，例如活动中的一节故事。
- **Context（剧情上下文）**：按顺序串联多个 Scene，为后续翻译提供前情，例如一整期活动(梦之岛灯笼祭)。
- **Group（上下文分类）**：组织多个 Context，例如把同一系列的多期活动归在一起(主线)。

Scene 由剧情捕获生成，不需要手工创建。首次体验单段翻译不必先创建 Context 和 Group；它们用于组织连续剧情及历史背景。需要关联前情时，在管理页创建或选择对应上下文并“设为活跃”。“活跃”决定后续任务的关联方向，不代表正在运行任务；出现历史映射错误时，按任务详情检查关联关系。

HET 服务首次初始化会导入内置预设，可在“管理”页查看剧情及“梦之岛灯笼祭”上下文，理解译文、摘要和关联关系。导入本身不会请求翻译 API；主动重跑或生成摘要仍可能产生费用。预设只初始化一次，覆盖安装不会自动恢复用户已删除的内容。

完整的设置用途、任务状态与操作说明见 [功能说明](docs/features.md)。

### 3. 常见问题

| 现象 | 优先检查 |
| --- | --- |
| 一直等待游戏连接 | 模块是否启用、作用域是否为游戏、修补后是否启用 HET，以及是否完全退出并重启游戏 |
| 游戏卡在开屏 | 查看是否缺少 OBB；在游戏模块已连接时使用设置中的“OBB 配置”。 |
| 进入剧情没有新任务 | 查看捕获是否暂停、是否已有译文或目标语言官方译文；同时检查任务筛选和最近任务记录 |
| 任务一直等待 | 查看是否等待恢复排序、前序摘要、可用并发额度或用户处理；等待不一定表示请求已发出 |
| API 或校验失败 | 查看任务详情中的错误，核对协议、地址、凭据、模型及长度限制；重试或修复也可能增加用量 |
| 等待保存到 HET | 根据详情处理原始剧情缺失或冲突；已有完整结果会重试保存，不必因此重新翻译 |
| 等待游戏同步 | 保持游戏连接并处理同步冲突；游戏确认保存不等于当前页面已经刷新 |
| 切换应用后进度停住 | 检查 HET 后台限制；游戏自身暂停或冻结也可能推迟画面回写 |

### 4. 如何更新

- **更新 HET**：使用相同签名的新版 APK 覆盖安装，以保留应用数据；更新后完全退出并重启 HET 和游戏，确认模块仍启用。若提示签名不一致，不要直接卸载来绕过，应先确认安装包来源并导出需要保留的数据。
- **更新游戏**：确认 HET 有对应游戏版本的运行时资源。LSPosed 用户核对模块和作用域；LSPatch 用户需按安装流程对新版游戏重新修补并安装，再检查模块是否启用。
- **检查资源**：打开 HET 会检查运行时资源版本，点击首页资源版本也可主动检查；OBB 在设置中单独检查，Release 版本需与游戏匹配。更新运行时资源或补齐 OBB 后重新启动游戏。

## 功能亮点

- **游戏内显示译文**：直接读取剧情文本，将对白和选项译文回写到游戏中，无需截图识别或来回切换翻译窗口。
- **结合剧情上下文翻译**：整理选项、分支与合流关系，结合角色词典、术语和历史摘要，为模型提供理解剧情所需的背景。
- **自选模型与提示词**：支持 OpenAI 兼容接口和 Anthropic Messages 接口，可配置服务地址、模型、目标语言、提示词及输出长度上限。
- **流式与非流式可选**：支持流式接收并逐块尝试回写已校验的译文，也可以等待完整响应后处理；实际显示时机取决于模型输出和游戏运行状态。
- **后台执行与并发任务**：翻译由 HET 服务执行，可配置 API 并发数；同一上下文可选择等待前序摘要、不等待，或携带尚未产生摘要的完整原文。后台运行仍受 Android 系统限制。
- **译文保存与复用**：剧情和译文保存到本地，重进剧情时可复用已有结果；完整结果保存或游戏同步失败时保留结果重试相应步骤，无需因此重新请求翻译。
- **任务与剧情管理**：支持任务取消、重跑、删除和启动恢复排序，以及剧情、上下文和分类管理；同步冲突可由用户确认处理。
- **输出校验与修复**：检查模型输出的结构与内容完整性，支持配置网络重试及结果修复次数，失败原因可在任务中查看。
- **资源检查与日志导出**：支持运行时资源版本检查、按游戏版本补齐缺失 OBB；日志按日期保存，默认可导出近期 14 天的日志压缩包，便于反馈问题。

## 开发文档

- [HET 与游戏层文件结构](docs/file-layout.md)：两端存储目录、Group/Context/Scene 文件关系、PageRec 导出与游戏资源缓存。
- [游戏层开发文档](docs/game-layer-development.md)：IL2CPP 对象与偏移、Hook、剧情提取、Java/JNI 通信和回写链路。
- [HET 层开发文档](docs/het-layer-development.md)：应用服务、任务调度、翻译和摘要执行、持久化、Scene 同步与日志。

## 问题反馈

- 优先在 [GitHub Issues](https://github.com/Quartewe/HousamoEmbedTrans/issues) 反馈，附上 HET / 游戏版本、设备与 Android 版本、LSPosed 或 LSPatch 环境，以及操作步骤。

- 翻译问题请附 Scene 名称、任务请求 ID、发生时间和错误文字。在 **HET 设置页左上角的日志导出入口**选择保存位置，可导出默认近期 14 天的日志 ZIP。游戏闪退还需游戏或系统崩溃日志；公开上传前检查日志中的隐私和请求正文。

- 可以用邮箱<539945613@qq.com>联系我，如果使用人数多了我会开一个qq群

## 构建

### 本地构建

在项目根目录运行以下内容
> .\gradlew.bat :app:assembleDebug --offline --console=plain

### 云端构建

使用`git push origin vXXX`即可，github action已配置完成

## 许可证

本项目原创代码采用 **GNU Affero General Public License v3.0（AGPL-3.0-only）**，完整条款见 [LICENSE](LICENSE)。

第三方代码及依赖保留各自的许可证和版权声明；本许可证不授予游戏本体、OBB 或其他第三方游戏资源的使用与分发权利。

## 致谢

### 开源项目

感谢以下开源项目为本项目提供支持：

| 项目 | 使用版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| [ShadowHook](https://github.com/bytedance/android-inline-hook) | 2.0.0 | Android Native inline hook，拦截游戏原生函数 | [MIT](https://github.com/bytedance/android-inline-hook/blob/main/LICENSE) |
| [Xposed API / XposedBridge](https://github.com/rovo89/XposedBridge) | API 82 | Java 层 Hook 与模块入口；仅用于编译，运行时由框架提供 | [Apache-2.0](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.pom) |
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.10.0 | Material 界面组件、主题与对话框 | [Apache-2.0](https://github.com/material-components/material-components-android/blob/master/LICENSE) |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.6.1 | Activity、主题和界面兼容支持 | [Apache-2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| [RapidJSON](https://github.com/Tencent/rapidjson) | 1.1.0 | C++ 层 JSON 解析与序列化，源码随仓库提供 | [MIT；附带组件许可见原文](app/src/main/jni/third_party/rapidjson/license.txt) |

上表列出直接引用的第三方库及随仓库提供的 RapidJSON。RapidJSON 内附的 `msinttypes` 头文件保留 BSD-3-Clause 声明，详见其原始许可证。各项目的版权与许可证归原作者所有。
