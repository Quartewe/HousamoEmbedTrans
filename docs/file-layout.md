# HET 与游戏层文件结构

核对日期：2026-09-22。以下结构依据当前源码整理，文件随功能使用而创建，不表示每台设备上都已存在。示例使用 Android 主用户 `0`；实际根路径由 `getFilesDir()` / `ApplicationInfo.dataDir` 决定。

## 1. 两端根目录

| 所属端 | 示例根目录 | 用途 |
| --- | --- | --- |
| HET | `/data/user/0/com.quarty.housamoembedtrans/files/` | 配置、Scene、Context、Group、任务及管理状态 |
| 游戏层 HET 输出 | `/data/user/0/jp.co.lifewonders.housamo/files/housamo_embed_trans/` | 正常 Scene 副本与 PageRec 导出；Native 的 `base_dir` |
| 游戏原生外部文件 | `/sdcard/Android/data/jp.co.lifewonders.housamo/files/` | 游戏自身资源缓存 |

HET 的 `scenes/<scene>.json` 与游戏 `housamo_embed_trans/scenes/<scene>.json` 通过 Scene 同步端口交换，是不同的物理文件。Context、Group、翻译任务和 HET 管理状态不会因此在游戏端生成对应目录。

## 2. HET 私有文件

```text
files/
├── config.json                         # 用户设置
├── runtime.json                        # 用户运行时资源，可覆盖 APK 内置资源
├── chardict.json                        # 用户角色字典
├── gameterms.json                       # 用户术语资源
├── prompt_drafts/
│   ├── translation.json                # 翻译提示词草稿
│   └── summary.json                    # 摘要提示词草稿
├── scenes/
│   ├── <scene>.json                    # 一个 Scene 一份 JSON，含正文、分支、译文等
│   ├── .annotations/<scene>.json       # HET 专用附加信息，不进入 Scene 导出/同步文档
│   ├── .incoming/                      # Scene 写入暂存
│   └── .pending_quarantine/            # 异常内容隔离
├── scene_contexts/
│   ├── index.json                      # ID 与内部文件名映射、活跃指针
│   ├── contexts/<storage_name>.json    # 单个 Context
│   ├── groups/<storage_name>.json      # 单个 Group
│   └── .txn/                           # 多文件事务恢复资料
├── translation_jobs/<requestId>/
│   ├── request.json
│   ├── state.json
│   ├── progress.json                   # 随执行阶段产生
│   ├── result.json                     # 随执行阶段产生
│   └── error.json                      # 随执行阶段产生
├── summary_jobs/<requestId>/
│   ├── request.json
│   └── state.json
├── pending_scene_apply/<scene>/
│   ├── scene.json                      # 待应用 Scene
│   └── state.json
├── scene_conflicts/                    # 条目含 game.json、het.json、state.json
├── scene_mutation_pool/
│   ├── meta.json
│   └── entries/                        # Scene 修改操作状态及适用的 Scene 数据
├── pending_process/
│   ├── index.json
│   ├── entries/
│   └── .txn/
├── rejected_api_results/               # 等待处理的完整 API 结果
├── bundled_preset_import.json          # 内置预设的一次性导入状态
└── logs/YYYY-MM-DD.log                 # HET 应用日志
```

这里只列主要业务文件，省略部分锁文件、恢复日志、备份和临时文件。各任务阶段的文件不保证同时存在；存在 `result.json` 不代表 Scene 已提交或游戏已 ACK。摘要成功写回目标后清理任务，不保留长期完成历史。

`storage_name` 是内部文件名，不应直接当作 Context/Group 的 ID 或显示名称，映射以 `scene_contexts/index.json` 为准。Group 有序引用 Context，Context 有序引用 Scene，正文仍保存在独立 Scene JSON 中；同一 Scene 可以被多个 Context 引用。

例如一个主线 Group 可以引用十六章对应的 Context，每章再引用多个 Scene。这是引用关系，不是将十六章正文合并为一个 JSON。预设来源为 APK 的 `assets/preset/manifest.json` 及其列出的文件，导入后由上述 Store 管理，活跃指针不从预设导入。

## 3. 游戏层 HET 输出

```text
/data/user/0/jp.co.lifewonders.housamo/files/housamo_embed_trans/
├── logs/YYYY-MM-DD.log                # 游戏侧模块 Java / Native 日志
├── scenes/
│   ├── quest_main2-1.json              # 正常捕获/同步副本
│   └── <scene>.json
└── page_rec/
    └── <首次导出时间戳毫秒>-<游戏进程PID>/
        ├── quest_main2-1.json          # 一个完整 Scene 一份文件
        ├── quest_main2-2.json
        ├── quest_main3-1.json
        └── <scene>.json
```

PageRec 复用正常 Scene 结构和分支组装：同一 Scene 的正文与分支保存在同一 JSON，不是每页一份，也不是全部 Scene 共用一个文件。一次进入剧情可能导出本章多个已加载 Scene；范围取决于已初始化且能够解析的剧情数据，不保证一次导出所有章节。

会话目录在该进程首次导出时创建，后续章节可以写入同一目录。同名 Scene 内容相同时跳过重复写入；变化时先写 `<scene>.json.tmp`，再重命名替换该会话中的正式文件。新游戏进程创建新会话目录，不自动清理旧导出。

PageRec 开启后，捕获输出到 `page_rec/`，这些文件不参与同步、翻译或 Quest 回写。游戏与 HET 的连接、正常 `scenes/` 端口仍保留；导出隔离不表示停止已有正常 Scene 的同步。切换模式需重启游戏。

游戏配置与字典优先通过 HET 用户文件 Provider 读取，无法取得可用的覆盖文件时回退到模块 APK assets，不从游戏 `housamo_embed_trans/` 寻找另一份 `runtime.json`。修改仓库 assets 不等于更新已安装 APK 或 HET 用户覆盖文件。

## 4. 游戏原生资源

以下第二章缓存路径来自本次实机读取：

```text
/sdcard/Android/data/jp.co.lifewonders.housamo/files/
└── UnityCache/Shared/main2.chapter/<缓存版本标识>/
    ├── __data                          # Unity 资源包，含剧情表等数据
    └── __info                          # Unity 缓存信息

/sdcard/Android/obb/jp.co.lifewonders.housamo/
└── main.<资源版本号>.jp.co.lifewonders.housamo.obb
```

`main2.chapter` 是设备缓存实例，不是所有剧情必须遵循的命名契约。缓存包、OBB、运行时 `AdvScenarioData` 与 Scene JSON 属于不同数据层；PageRec 导出的是解析后的 Scene，不是复制缓存或 OBB。游戏侧模块 Java / Native 日志同时写入 logcat 和游戏私有的 `housamo_embed_trans/logs/`；HET 导出 ZIP 时分别以 `het/`、`game/` 收录两端的最近 14 天日志，读取游戏日志需要游戏保持连接。

## 5. 源码入口

| 文件结构 | 实现入口 |
| --- | --- |
| 游戏根目录、配置读取、端口注册 | [MainHook.java](../app/src/main/java/com/quarty/housamoembedtrans/MainHook.java) |
| 游戏正常 Scene | [scene_store.cpp](../app/src/main/jni/translation/store/scene_store.cpp) |
| PageRec 会话及文件写入 | [page_rec.cpp](../app/src/main/jni/scene/page_rec.cpp) |
| HET Scene、附加信息、冲突、待应用 | [scene/store](../app/src/main/java/com/quarty/housamoembedtrans/scene/store/) |
| Context、Group、索引 | [context/store](../app/src/main/java/com/quarty/housamoembedtrans/context/store/) |
| 配置和提示词 | [storage/config](../app/src/main/java/com/quarty/housamoembedtrans/storage/config/) |
| 翻译任务 | [TranslationJobStore.java](../app/src/main/java/com/quarty/housamoembedtrans/translation/job/TranslationJobStore.java) |
| 摘要任务 | [SummaryJobStore.java](../app/src/main/java/com/quarty/housamoembedtrans/summary/job/SummaryJobStore.java) |

业务生命周期见 [HET 层开发文档](het-layer-development.md) 与 [游戏层开发文档](game-layer-development.md)。
