# HET 层开发文档

本文面向维护 HET 应用服务、翻译与摘要任务、持久化和 Scene 同步的开发者。整理日期为 2026-09-20，依据当前源码。游戏注入、IL2CPP 对象、剧情提取与内存回写见 [游戏层开发文档](game-layer-development.md)。安装和使用流程不在本文范围内。

领域术语与行为契约见 [CONTEXT.md](CONTEXT.md)。本文提供代码导航和状态边界说明，不替代它们。

## 1. HET 层入口与模块职责

HET 层拥有翻译任务、提供商请求和本地管理数据。它通过游戏 Java 暴露的端口交换 Scene、结果与配置，不直接解引用游戏 IL2CPP 指针。当前 Manifest 没有为 `TranslationService` 指定独立 `android:process`，因此服务默认与 HET 应用界面处于同一应用进程。

| 模块 | 代码入口 | 职责 |
| --- | --- | --- |
| 应用初始化 | [HousamoApplication.java](../app/src/main/java/com/quarty/housamoembedtrans/HousamoApplication.java) | HET 应用级初始化 |
| 服务与 Binder | [TranslationService.java](../app/src/main/java/com/quarty/housamoembedtrans/translation/TranslationService.java) | 组装运行组件、接纳请求、注册游戏端口和回调、管理连接代次 |
| 调用者校验 | [CallerVerifier.java](../app/src/main/java/com/quarty/housamoembedtrans/bridge/CallerVerifier.java) | 跨进程调用身份约束；服务 exported 不等于任意调用者可操作 |
| 启动门禁 | [StartupCoordinator.java](../app/src/main/java/com/quarty/housamoembedtrans/runtime/StartupCoordinator.java) | 按固定阶段开放恢复、结果补投和 API 工作 |
| 翻译任务存储 | [TranslationJobStore.java](../app/src/main/java/com/quarty/housamoembedtrans/translation/job/TranslationJobStore.java) | 请求与状态持久化、领取顺序、取消、重跑、删除及终态保存状态 |
| 翻译执行 | [TranslationTaskExecutor.java](../app/src/main/java/com/quarty/housamoembedtrans/translation/job/TranslationTaskExecutor.java) | 领取任务、准备历史、调用提供商、处理事件、修复与完成任务 |
| 提供商传输 | [TranslationApiClient.java](../app/src/main/java/com/quarty/housamoembedtrans/provider/TranslationApiClient.java) | HTTP、流式/非流式响应、传输取消和请求诊断 |
| API 容量 | [ApiConcurrencyGate.java](../app/src/main/java/com/quarty/housamoembedtrans/provider/ApiConcurrencyGate.java) | 翻译与摘要共享的并发额度 |
| 请求与输出协议 | [translation/request](../app/src/main/java/com/quarty/housamoembedtrans/translation/request/) | 请求组装、事件解码、结果与 schema 校验 |
| 终态投递 | [TerminalDeliveryCoordinator.java](../app/src/main/java/com/quarty/housamoembedtrans/translation/delivery/TerminalDeliveryCoordinator.java) | 本地结果保存恢复、在线游戏投递和重试协调 |
| Scene 数据 | [SceneStore.java](../app/src/main/java/com/quarty/housamoembedtrans/scene/store/SceneStore.java)、[SceneTranslationResultApplier.java](../app/src/main/java/com/quarty/housamoembedtrans/scene/store/SceneTranslationResultApplier.java) | 原始剧情、译文、写入门禁与结果合并 |
| Scene 同步 | [scene/sync](../app/src/main/java/com/quarty/housamoembedtrans/scene/sync/) | 游戏镜像导出、差异与冲突处理、应用结果及生产策略发布 |
| 历史与摘要 | [context/history](../app/src/main/java/com/quarty/housamoembedtrans/context/history/)、[summary](../app/src/main/java/com/quarty/housamoembedtrans/summary/) | Context/Group 历史解析、摘要请求、摘要任务及压缩策略 |
| 设置与资源 | [ConfigStore.java](../app/src/main/java/com/quarty/housamoembedtrans/storage/config/ConfigStore.java)、[PromptStore.java](../app/src/main/java/com/quarty/housamoembedtrans/storage/config/PromptStore.java) | 配置和提示词持久化；资源更新与角色词典更新也位于该包 |
| 任务界面 | [TranslationQueueActivity.java](../app/src/main/java/com/quarty/housamoembedtrans/ui/TranslationQueueActivity.java) | 展示任务、详情及用户操作入口 |
| 通知与日志 | [TranslationStatusNotification.java](../app/src/main/java/com/quarty/housamoembedtrans/runtime/TranslationStatusNotification.java)、[logging](../app/src/main/java/com/quarty/housamoembedtrans/logging/) | 前台运行状态、错误提示和按日期保存的日志 |

界面与通知负责展示状态。修改列表分组或通知文字不能代替修改任务存储、执行器或投递状态。

## 2. 启动、接纳与调度

### 2.1 启动门禁

服务建立存储并恢复管理导入事务后，通过 `BundledPresetImporter.importOnce` 导入 `assets/preset/manifest.json` 列出的预设，再创建 Scene 同步运行时。预设沿 `ManagementImportModel` 与 `ManagementImportCoordinator` 导入，冲突使用 SKIP、任务使用 KEEP；Context 索引由现有 Store 生成，活跃指针不从预设导入。

`files/bundled_preset_import.json` 在提交前记录 session token 和快照指纹，恢复时继续同一会话；仅在协调器确认 applied 后原子写入 completed 并清理会话。recovery_pending 保留会话，启动失败向现有启动处理链传播。完成标记不按 APK 版本重置，用户删除预设后也不会自动恢复。修改预设清单不会自动给已完成初始化的安装追加数据。

`StartupCoordinator` 的阶段顺序为：

```text
准备并扫描 Translation / Summary Job
  -> Scene Sync
  -> Terminal Redelivery Release
  -> 可选 Context / Group Review
  -> Unified Recovery Decision
  -> API Work
```

API Work 开放前，新请求可以接纳并持久化，但不能被 API worker 领取。结果补投先于 API Work 开放，因为已有结果不需要重新调用模型。启动失败由独立阶段状态记录，不能把等待同步或恢复选择当成提供商正在执行。

运行期 Scene 同步仍串行执行，但不与已接纳任务的 API 执行互相等待。`TranslationService` 分别使用启动协调、同步触发、同步操作、回调 I/O 等 executor；Binder 线程、这些后台线程和 Android UI 线程不能混为一类。

### 2.2 请求接纳与顺序

```text
游戏 Native 请求 -> 游戏 Java -> ITranslationService.enqueueTranslation
  -> 读取 PFD 请求、核对请求身份及接纳条件
  -> TranslationJobStore 持久化 request / state
  -> 启动与恢复排序边界
  -> 按 queue_sequence 进入可领取队列
  -> TranslationTaskExecutor.claimNextQueuedJob
```

恢复快照只包含快照边界前已存在的任务。边界后到达的新任务不能被混入旧任务选择，也不能因用户放弃旧任务而被取消。恢复决定的原子提交划分排序边界：之前接纳的新任务在恢复批次前，之后接纳的在恢复批次后。手动模式允许按 Scene 部分提交，其余保持等待。

重复 `requestId` 走已持久化请求和结果的既有处理路径，不以“重新收到请求”为由复制任务、撤销取消或提升队列位置。任务名、Scene 名和 `requestId` 是不同身份维度；同名 Scene 不代表同一个执行实例。

### 2.3 并发与历史等待

流式开关控制响应读取形式，不决定是否能够并发翻译。API 并发由执行调度及 `ApiConcurrencyGate` 控制：总额度大于 1 时保留一个翻译专用通道，其余共享通道优先满足等待中的摘要；额度为 1 时共享单通道，摘要优先。

`ContextHistoryPreparer` / `HistoryResolver` 在发送前准备历史并检查上下文长度。缺失或不就绪的历史映射不能直接当作空历史继续发送。同上下文前序摘要的策略有“等待”“不等待”“发送所有未产生总结的完整原文”；选择完整原文会增加输入长度，仍需检查实际请求是否超出上下文上限。

## 3. 提供商执行、校验与修复

```text
领取 Job
  -> 当前翻译配置、提示词与历史准备
  -> PreparedApiRequest / 上下文长度检查
  -> 取得 API 并发额度
  -> TranslationApiClient 执行 HTTP
  -> TranslationEventDecoder 解码输出事件
  -> 结果校验、必要的修复请求、局部 patch
  -> 完整合法结果持久化
  -> 本地保存与终态投递
```

当前 `resolveTranslationEndpoint` 按协议处理 OpenAI 兼容的 `/v1/chat/completions` 和 Anthropic `/v1/messages` 路径；Base URL 已包含完整端点时保留相应路径。通过兼容协议调用 Gemini 等模型，不等于使用其原生协议。协议选择、模型名和端点路径需要同时核对。

流式响应读取提供商事件；非流式响应取得完整内容后也进入执行器的事件解码链。这里“流式返回”不表示每个翻译块对应一次独立模型请求，也不保证模型逐块思考。

当前非流式翻译连接将 read timeout 设为 0，以允许生成期间长时间无响应；取消仍可断开请求。这不会取消反向代理或提供商自己的超时。HTTP 成功、输出可解析、业务结果合法、HET 保存成功分别是不同阶段。

网络重试从新的提供商响应开始；解码器或监听器失败不能自动当作网络故障。修复由翻译执行器协调，相关结果检查在 `TranslationResultValidator` 等类中。排查重复费用时应对照 attempt、重试、修复和任务重跑记录，而不只看最终状态。

`SummaryTaskExecutor` 与 `SummaryJobStore` 负责摘要任务，`SummaryRequestAssembler` / `SummaryResultValidator` 负责其请求与输出。摘要与翻译共享 API 容量，但不共用普通翻译任务的取消入口。成功写入目标的摘要任务不保留长期 completed 任务历史，已保存的摘要属于 Context/Group 数据。

## 4. 任务状态、保存和用户操作

任务执行状态与 `delivery_state` 分开保存。[TerminalOutcome.java](../app/src/main/java/com/quarty/housamoembedtrans/translation/delivery/TerminalOutcome.java) 定义投递状态 `pending`、`in_flight`、`acknowledged`、`not_required`。下面按开发语义归纳，不是 UI 全部文案的一一枚举。

| 阶段 | 状态拥有方 / 数据 | 后续动作 |
| --- | --- | --- |
| 等待排序或翻译 | JobStore 的持久任务、排序与门禁信息 | 门禁放行后领取；未获用户选择的任务继续等待 |
| 翻译执行中 | 执行器与 running Job | 请求、解析、修复、更新进度；可产生局部 patch |
| 等待本地保存 | 已保留的完整结果及本地保存原因 | 重试写入 HET Scene，不重跑 API |
| 等待游戏同步 | HET 已保存，终态尚未获得游戏 ACK | 游戏连接且门禁放行后投递 |
| 投递中 | `in_flight` 与投递租约、连接代次 | 匹配的 ACK 或撤销租约；不能依据回调发出就认定成功 |
| 游戏同步完成 | 正常路径本地保存成功且游戏 ACK 有效 | 保留任务结果；不代表屏幕已刷新 |
| 失败 | failed Job 和错误资料 | 根据可执行操作修复后重试或显式重跑 |
| 已取消 | canceled，`delivery_state=not_required` | 保留请求供查看、重跑或删除，不自动继续交付 |
| 需要用户处理 | 历史、数据损坏、冲突等具体原因 | 根据原因修复；不能一概直接重发 API |

HET 缺失 Scene 时的游戏保存回退见 [游戏层开发文档第 5 节](game-layer-development.md#5-het-通信保存与-ack)。该路径获得游戏 ACK 后可以完成，但不能伪造 `local_scene_saved`。两端均缺失时保留结果和原因，不能仅凭译文重建原始 Scene。

取消时先在任务存储的同步边界内持久化取消状态，再让执行和传输观察取消。取消赢得竞态后到达的完整合法结果进入 `RejectedApiResultStore`，未完成流不能作为完整结果保留。重跑复用保留请求并使用当前设置，需等待旧执行与修复退出，不能与旧 attempt 同时运行同一任务。

永久删除清理对应 requestId 的任务目录与调度索引；运行中先取消并等待退出，正在应用结果时受投递租约屏障约束。删除任务不等于删除 Scene、已保存译文或独立等待处理结果。UI 中隐藏条目也不等于这些数据已经删除。

## 5. Scene 同步、冲突与管理

`SceneSyncCoordinator` 协调游戏镜像与 HET Scene；`GameSceneMirrorSource` / `SceneMirrorExportCoordinator` 负责导出侧，`SceneApplyCoordinator` 负责应用侧，`SceneConflictResolver` / `SceneManualConflictController` 处理冲突。`ScenePolicyPublisher` 将生产策略发布给游戏端。

连接代次用于隔离已经断开的旧端口和仍在返回的旧操作。同步应用结果与生产策略完成确认是不同步骤：写完数据流不代表可以提前解除游戏侧门禁。断开、失败和取消需要收束属于该代次的操作，不能解除新连接拥有的门禁。

HET Scene 写入同时受管理状态、冲突和删除意图约束。`SceneDeletionIntentRegistry`、`PendingSceneApplyStore`、`ConflictStore` 与 `TransactionalSceneSlots` 是排查“文件存在但不能自动写入”的入口，不应通过直接覆盖 JSON 绕过它们。

新翻译任务自动同步由 `UserSettings.SceneSync.AutoSyncOnNewTranslation` 控制。关闭它只影响后续新任务触发，不关闭连接同步、手动同步或已保留结果的本地保存恢复。同步完成可以唤醒本地保存，不应再创建一次模型请求。

Context/Group 决定历史关联与摘要路由，`active` 是未来路由指针，不是任务运行状态。角色词典更新入口在 [CharacterDictionaryUpdates.java](../app/src/main/java/com/quarty/housamoembedtrans/storage/config/CharacterDictionaryUpdates.java)；修改配置、词典合并或管理动作时应沿其存储入口操作，避免只改界面或 bundled assets。

## 6. HET 持久化、配置与日志

两端主要目录树、Context/Group 引用关系与 PageRec 隔离目录见 [HET 与游戏层文件结构](file-layout.md)。

以下目录相对于 HET 的 `Context.getFilesDir()`，不是游戏层的 `files/housamo_embed_trans`。两边都可能保存 Scene，但属于不同进程的数据副本。

| 目录 / 文件 | 内容与入口 |
| --- | --- |
| `translation_jobs/<requestId>/` | `request.json`、`state.json`；按阶段产生 `progress.json`、`result.json`、`error.json`，见 TranslationJobStore |
| `summary_jobs/` | 摘要任务恢复和执行资料，见 SummaryJobStore |
| `scenes/` | HET 剧情副本、译文和相关元数据，见 SceneStore |
| `rejected_api_results/` | 完整但失去自动应用资格的 API 结果，见 RejectedApiResultStore |
| `logs/YYYY-MM-DD.log` | HET 应用日志，见 Log / DailyLogStore |

目录表列出主要入口，不是允许手工删除的清单。原子写入、备份与临时事务目录应由各 Store 处理；存在 `result.json` 不足以证明 Scene 已保存或游戏已 ACK。

`ConfigStore` 管理设置，`PromptStore` 管理提示词，`TranslationConfig` 提供执行使用的配置值。调试项中的“不发送思考参数”与给模型发送某个思考强度值不是同一行为；输出上限与模型上下文上限也不同。更改配置后是否影响已准备的请求，必须看请求快照与读取时点，不能承诺所有在途请求立即采用新值。

`logging.Log` 写入 Android 日志并通过日志写入线程保存到 DailyLogStore。每天一个文件，导出默认包含当天在内的近期 14 天，以 ZIP 输出；导出快照记录文件字节边界，之后追加的内容不进入该次快照。14 天是默认导出范围，不代表自动删除更早的日志。

请求与响应正文由独立调试设置控制。设置页导出通过独立的 `IGameLogPort` 管道一并读取已连接游戏的日志快照，ZIP 中分别放在 `het/`、`game/`。游戏未连接时只导出 HET 日志，同时显示缺失提示并写入 `game-unavailable.txt`；传输或落盘失败会报错。模块日志不能代替系统 crash buffer 或 tombstone；跨进程问题仍需对齐双方的 `requestId`、Scene、时间和连接代次。

## 7. HET 开发时的定位顺序

| 现象 | 先检查 | 应区分的情况 |
| --- | --- | --- |
| 新剧情没有任务 | 游戏提交记录、Binder admission、JobStore 持久记录 | 没捕获、没提交、接纳失败、已接纳但等待排序 |
| 长时间没有 API 请求 | StartupCoordinator、恢复选择、历史解析、并发额度 | 等待门禁、等待摘要与真正的 HTTP 执行 |
| 提供商有费用但任务失败 | HTTP attempt、响应结束、输出校验与修复日志 | 上游超时、内容截断、格式或业务校验失败 |
| 翻译完成但没有显示 | result、HET Scene 保存、投递租约、游戏 ACK、QuestWriter | 保存成功、投递成功和屏幕刷新互不等价 |
| 取消后通知仍运行 | 持久取消状态、在途 attempt、通知状态来源 | 取消尚未完成、旧回调、通知展示未更新 |
| 重跑或删除异常 | 任务目录、执行占用、终态租约和 UI 筛选 | 数据仍在但未显示、重跑资格不足、删除被租约阻挡 |

本节按当前源码入口作静态整理，不能替代构建、故障恢复、并发时序或设备集成验证；检查变更时，应从对应状态所有者开始，再沿调用链核对界面和游戏消费者。
