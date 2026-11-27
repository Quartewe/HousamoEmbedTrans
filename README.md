# Live A Hero Embed Trans

这是从 HousamoEmbedTrans 分出的 Android 剧情翻译模块，目标游戏为 Live A Hero。两个项目共享 Git 历史。翻译服务、Scene 格式和管理界面沿用 HET，角色词典、游戏术语和内置剧情预设从空白开始。

| 适配项 | 当前值 |
| --- | --- |
| 游戏 | `jp.co.lifewonders.liveahero`，4.17.2（975），arm64-v8a |
| 模块 | `com.quarty.liveaheroembedtrans` |
| libil2cpp.so Build ID | `0d28b22d6b945fb52304c9ad82a8b1fc300de140` |

游戏、模块和 RVA 的在线更新均已停用，模块从内置 `runtime.json` 加载默认配置。LAH 的回想入口尚待确认，Scene 详情暂时无法直接打开游戏回想。

主页状态卡片下方可选择 LSP 或 Root。LSP 沿用 LSPosed；Root 由翻译服务请求 su 授权并等待用户启动 LAH。切换接入方式后，已经启动的游戏可能需要重启。

公共接入代码同步自 HET `1fb7786`，包含独立 Java 引导、服务管理、双版本打包和主页单选修复。后续同步了 `cc5db50` 的官译覆盖判断、`3c32fa2` 的原文／译文切换和 `167ea78` 的社区译文导入代码。Root 的 Java 类仍位于 `com.quarty.housamoembedtrans` 命名空间，与 APK 的应用包名分别配置。

## 本地开发

在 Windows 上按运行环境选择构建参数。

```powershell
.\gradlew.bat :app:assembleDebug -PhetVariant=arm64-v8a
.\gradlew.bat :app:assembleDebug -PhetVariant=x86-houdini
```

每次构建生成 `app/build/outputs/apk/debug/app-debug.apk`。两个版本都包含 ARM64 业务库和 bootstrap；ARM64 版携带 ARM64 helper 与 ShadowHook 2.0.0，x86-houdini 版携带 x86_64 helper 与 ShadowHook 1.0.10。CMake 配置时获取 AndKittyInjector、KittyMemoryEx 的 main，源码保存在构建缓存，网络可访问 GitHub 是配置依赖。

## 当前验证与待适配内容

MuMu 的 x86-houdini 包已在 LAH 4.17.2 的《白羽の矢》中完成捕获、固定测试文本回写、恢复原文和选项回退：共捕获 287 条正文与 39 个选项。测试文本及测试数据已恢复。该验证早于本轮新增的原文切换与社区导入同步；未验证实际模型翻译、完整续写播放、LSP 路线及 ARM64 真机。

普通捕获按正文和选项逐条统计目标语言官译覆盖，只有全部覆盖且来源非空时才跳过。零星官方提示不会再挡住整段剧情。PageRec 完整导出仍保留。

原文／译文切换的回写代码已经同步，剧情内按钮仍沿用 HET 的窗口查找方式，LAH 的普通窗口及 Echo 窗口需要继续适配。模块未主动开启 AUTO 时保留游戏自身的自动播放状态。Scene 详情打开回想尚未实现；当前可在游戏中手动进入剧情。

社区译文入口显示“暂未启用”，资源仓库地址留空，不读取 Housamo 的社区剧情。本地文件导入可继续使用。角色资料、游戏术语、内置预设和品牌图标保持空白或中性默认值。

## 同步上游

先 fetch 并检查提交涉及的路径，再挑选公共代码。`res:` 开头的提交通常是 Housamo 数据更新，但前缀不能代替文件检查：本轮 `chore(runtime)` 同样只更新了 Housamo 5.21.3 的运行时身份，因此没有导入。LAH 保留自己的包名、runtime、词典、预设和更新器状态；普通 merge 不会自动排除这些路径。

本轮同步截止于 HET `14e0c35`，其中公共功能已纳入上述提交；`fdecf86` 的 Housamo 资源与 `14e0c35` 的运行时数据均未导入。

本地 `lah` 分支以 HET 的 `067170a3` 为起点。后续维护者可以通过共同历史对比两边的修改，再合并上游改动或挑选单个提交。[HET 上游项目](https://github.com/Quartewe/HousamoEmbedTrans)
