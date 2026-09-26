# 游戏层开发文档

本文面向维护游戏注入、剧情提取和翻译回写的开发者。整理日期为 2026-09-20，依据当前源码、`runtime.json` 及原有 `offset.md`。HET 服务、任务调度和持久化见 [HET 层开发文档](het-layer-development.md)。安装和使用流程不在本文范围内。

领域术语与行为契约见 [CONTEXT.md](CONTEXT.md)。本文提供代码导航和对象访问说明，不替代它们。

## 1. 进程边界与代码入口

游戏层包含注入游戏进程的 Java 和 Native；HET 层是运行翻译服务、管理任务与持久状态的应用进程。Java 不等于 HET，Native 也不承担提供商调用。

| 内容 | 当前入口 | 职责 |
| --- | --- | --- |
| 游戏 Java 启动 | [MainHook.java](../app/src/main/java/com/quarty/housamoembedtrans/MainHook.java) | `Application.attach`、配置读取、Native 初始化、游戏端口、JNI 转发 |
| 服务连接 | [TranslationServiceClient.java](../app/src/main/java/com/quarty/housamoembedtrans/bridge/TranslationServiceClient.java) | Binder 连接、请求提交、结果接收、终态租约与 ACK |
| 跨进程接口 | [AIDL 目录](../app/src/main/aidl/com/quarty/housamoembedtrans/translation/) | `ITranslationService`、`ITranslationCallback`、`IGameScenePort`、`IGameObbPort` |
| Native 初始化与配置 | [init.cpp](../app/src/main/jni/init.cpp)、[init_config.cpp](../app/src/main/jni/init_config.cpp) | JNI、运行时配置、目标库初始化 |
| 结构与接口声明 | [housamo.hpp](../app/src/main/jni/housamo.hpp) | `RuntimeConfig`、`OrderKey`、Scene 和 Quest 回写结构 |
| Hook | [hook.cpp](../app/src/main/jni/runtime/hook.cpp) | 剧情捕获与当前显示对象观察 |
| 对象读取 | [ilcpp_reader.cpp](../app/src/main/jni/runtime/ilcpp_reader.cpp) | IL2CPP 指针、字符串等读取辅助函数 |
| 剧情解析 | [scenario_catcher.cpp](../app/src/main/jni/scene/scenario_catcher.cpp) | label 收集、页面命令解析、分支组织、回写目标登记 |
| Scene 构建 | [scene_builder.cpp](../app/src/main/jni/scene/scene_builder.cpp) | 从解析结果构建 Scene、补充翻译上下文 |
| 本地提交与派发 | [native_translation_pipeline.cpp](../app/src/main/jni/translation/pipeline/native_translation_pipeline.cpp) | 同一 Scene 派生磁盘 JSON 与请求，先保存再提交 |
| 请求跟踪 | [translation_dispatcher.cpp](../app/src/main/jni/translation/pipeline/translation_dispatcher.cpp) | 不可变请求、pending 请求及桥接派发 |
| 编解码与磁盘 | [codec](../app/src/main/jni/translation/codec/)、[scene_store.cpp](../app/src/main/jni/translation/store/scene_store.cpp) | Scene、请求、结果的不同格式及本地存储 |
| 结果 JNI 桥接 | [translation_callback_bridge.cpp](../app/src/main/jni/bridge/translation_callback_bridge.cpp) | 完整结果、失败与局部 patch 的 Native 入口 |
| 游戏内存回写 | [quest_writer.cpp](../app/src/main/jni/runtime/quest_writer.cpp) | 回写队列、Unity 线程消费、正文与 Selection 显示刷新 |
| 捕获门禁 | [scene_production_policy.cpp](../app/src/main/jni/scene/scene_production_policy.cpp) | Scene 生产租约和同步期间的捕获约束 |

## 2. 启动与 Hook

`MainHook` 在目标应用 `Application.attach` 后调用 `initializeTarget`，读取启动配置、建立 HET 连接、初始化 ShadowHook 并调用 `nativeStart`。游戏 Scene 端口需在 Native 策略及游戏镜像目录就绪后启用；连接服务与 Unity 资源加载不是同一件事。

`runtime.json` 提供目标游戏版本、函数 RVA 和对象布局；用户配置提供功能开关。配置解析入口在 `init_config.cpp`，业务读取使用 `g_runtime_config`，不要在访问代码中另写一套偏移常量。

| Hook / 入口 | 当前用途 |
| --- | --- |
| `AdvDataManager.FindScenarioData` | 先调用原函数，再用返回的 `AdvScenarioData*` 和传入的 `entry_label` 进入 `CatchScenario` |
| `PageTextChange` | 原函数返回后记录 `AdvPage*`，供当前页刷新使用 |
| `AdvUguiSelection.Init` | 原函数返回后登记当前选择按钮及其数据/文本组件 |
| `AdvUguiSelection.ClearAll` | 原函数执行前清空已登记按钮映射 |
| `InitBase` / `InitText` | 仅 `EnablePageRecDebug` 开启时安装，记录已初始化 label，供 PageRec 按 Scene 导出 |
| `il2cpp_runtime_invoke` | QuestWriter 识别 `UnityEngine.UnitySynchronizationContext.ExecuteTasks` 返回时机并消费回写队列 |

`AddSelection`、`ShowSelection` 出现在配置中不代表当前 Hook 安装了它们。当前选项刷新也不靠重新调用这两个方法。

## 3. RVA、字段偏移与对象关系

### 3.1 如何读偏移

函数 RVA 相对于加载后的 `libil2cpp.so` 基址；字段 offset 相对于某个对象。数组下标、列号、结构步长和函数地址属于不同概念。

```text
函数地址 = il2cpp_base + RVA
object + field -> 读取该字段中的指针，再进入目标对象
object + field => 直接读取该字段的值
```

`Il2CppString.Chars` 是对象内部 UTF-16 数据的起始地址，不再解引用指针；字符串 Length 是 UTF-16 code unit 数量，不是 UTF-8 字节数。

```cpp
// 示意：调用前仍需满足对象生命周期、索引及长度约束。
const auto& layout = g_runtime_config.layout;
void* row = read_ptr(cmd, layout.adv_command.row_data);
void* strings = read_ptr(row, layout.string_grid_row.strings);
void* raw = read_ptr(strings, layout.il2cpp_array.first_element
    + layout.text_columns.raw * layout.il2cpp_array.pointer_size);
std::string text = read_il2cpp_string(raw);
```

不能把 `row + strings字段 + 数组头 + 列偏移` 一次相加：`strings` 字段存的是另一个对象的指针。`List<T>` 的 `Items` 也指向数组对象；遍历数量取 `Size`，数组容量用于边界核对，不当作有效元素数量。

### 3.2 剧情对象图

```text
FindScenarioData(entry_label)
  -> AdvScenarioData
       Name -> Scene 名
       ScenarioLabels -> Dictionary<string, AdvScenarioLabelData>
         Entries -> entry 数组（按 DictionaryEntry.Size 取结构）
           Key -> label 名
           Value -> AdvScenarioLabelData
             Next -> 同 bucket 内的后续 label
             PageDataList -> List<AdvScenarioPageData>
               CommandList -> List<AdvCommand>
                 Type -> 命令类型字符串
                 RowData -> StringGridRow
                   RowIndex => 原始行号
                   Strings -> Il2CppString[]（原文、官方译文、条件等列）
               ScenarioLabelData -> 页面所属 label
               PageNo => label 内页号
       JumpDataList -> scenario 级跳转资料
```

`ScenarioLabels` 决定当前可访问的 bucket；字典枚举顺序不是叙事顺序。`Next` 用于组织同 bucket 内 label 的顺序，但本身不定义 bucket 边界。`afterBattle` 等命名也不是结束标记。

`AdvScenarioLabelData.CommandList` 是 label 的完整命令列表；当前逐页解析以 `PageDataList -> CommandList` 为主。`ScenarioLabelCommand`、`MessageWindowName`、`TextDataList` 可辅助定位，不应只因字段存在就作为新的捕获入口。

### 3.3 命令字段

| 命令 | 数据路径与用途 |
| --- | --- |
| `Text` | `RowData.Strings[TextColumns.Raw]` 取正文，其他语言列取官方译文 |
| `Character` | `CharacterInfo -> NameText` 取显示说话人，不从素材/立绘列猜名字 |
| `CharacterOff` | 清空当前说话人；空说话人表示旁白 |
| `Selection` | RowData 取选项文本，`JumpLabel` 关联后续 label |
| `Jump` | `JumpLabel` 取目标，RowData 的 `ConditionColumn` 取条件；非空条件参与 If 结构 |

当前解析器也识别 `JumpRandom`、`JumpSubroutine` 类型并尝试读取跳转信息。这只是现有解析分支，不证明它能预测随机目标、恢复完整子程序调用图或访问当前 scenario 之外的指针集。

说话人随页内命令顺序更新，不能只检查首条命令。`AdvCommandText.IndexPageData` 不是 `CommandList` 下标；调试 Hook 需要比较命令对象与 `self` 找到实际位置。

### 3.4 当前配置快照

下表来自 [runtime.json](../app/src/main/assets/runtime.json)，`GameVersion=5.19.0`；[构建配置](../app/build.gradle.kts) 当前仅包含 `arm64-v8a`。这些表是配置快照，不是本次设备验证结果。该 JSON 不记录目标 `libil2cpp.so` 的哈希或 build ID，不能仅凭版本字符串确认适用于另一个二进制或 ABI。

| RVA 配置项 | 值 |
| --- | --- |
| `RVA_FindScenarioData` | `0x2191D7C` |
| `RVA_InitBase` | `0x217E574` |
| `RVA_InitText` | `0x2187428` |
| `RVA_PageTextChange` | `0x21CB71C` |
| `RVA_AddSelection` | `0x21CE054` |
| `RVA_ShowSelection` | `0x21CE47C` |
| `RVA_RemakeText` | `0x21C85A4` |
| `RVA_UguiSelectionInit` | `0x21E703C` |
| `RVA_UguiSelectionClearAll` | `0x21E7484` |
| `RVA_UiTextSetText` | `0x47D1B60` |

除特别说明外，字段值均为对象内字节偏移。`TextColumns` 与 `ConditionColumn` 是列号；`PointerSize` 和 `DictionaryEntry.Size` 是步长。`AdvCommandCharacter.NameText` 相对于 CharacterInfo 对象。

| Layout 分组 | 字段和值 |
| --- | --- |
| `Il2CppString` | `Length=0x10`；`Chars=0x14` |
| `Il2CppArray` | `Length=0x18`；`FirstElement=0x20`；`PointerSize=8` |
| `Il2CppList` | `Items=0x10`；`Size=0x18` |
| `AdvPage` | `CurrentData=0x88`；`Engine=0xD0` |
| `AdvEngine` | `SelectionManager=0x48` |
| `AdvSelectionManager` | `Selections=0x28`；`IsShowing=0x38` |
| `AdvSelection` | `Text=0x18`；`RowData=0x58` |
| `AdvUguiSelection` | `Text=0x20`；`Data=0x28` |
| `AdvScenarioPageData` | `CommandList=0x10`；`TextDataList=0x18`；`ScenarioLabelData=0x20`；`PageNo=0x38`；`MessageWindowName=0x40` |
| `ScenarioLabelData` | `PageDataList=0x10`；`ScenarioLabel=0x18`；`Next=0x20`；`CommandList=0x28`；`ScenarioLabelCommand=0x30` |
| `AdvScenarioData` | `Name=0x10`；`JumpDataList=0x28`；`ScenarioLabels=0x30` |
| `Il2CppDictionary` | `Entries=0x18`；`Count=0x20` |
| `DictionaryEntry` | `HashCode=0x00`；`Key=0x08`；`Value=0x10`；`Size=0x18` |
| `AdvCommand` | `RowData=0x10`；`Type=0x20` |
| `StringGridRow` | `RowIndex=0x18`；`Strings=0x20` |
| `AdvCommandCharacter` | `CharacterInfo=0x38`；`NameText=0x18` |
| `AdvCommandSelection` | `JumpLabel=0x38` |
| `AdvCommandJump` | `JumpLabel=0x38`；`ExpressionParser=0x40`；`ConditionColumn=2` |
| `TextColumns` | `Raw=8`；`En=11`；`ZhTw=12`；`ZhCn=13` |

## 4. 从捕获到翻译请求

```text
FindScenarioData 返回
  -> 捕获暂停代次 / Scene 生产租约
  -> CatchScenario：收集 label、建立顺序、解析页面与分支
  -> ScenarioParseResult + QuestTargetSet
  -> 根据现有磁盘 Scene 状态分流
     complete  -> SubmitSceneToWriter（读取已有译文）
     pending   -> SubmitExistingScene（复用磁盘 Scene 构造请求）
     not_found -> scene_builder -> shared_ptr<const Scene>
                  -> NativeTranslationPipeline
                  -> 编码 Scene JSON 与 TranslationRequest
                  -> SceneStore 提交
                  -> TranslationDispatcher -> 游戏 Java -> HET
```

解析先区分成功、官方译文跳过与失败，再提交回写目标集。按现有规则，只要任意被解析文本命中目标语言官方译文，就跳过该剧情。`CatchScenario` 还维护当前进程的 `caught_scenarios` 捕获记录；磁盘状态分流不意味着完全没有内存去重。

### 顺序与分支

`OrderKey = {label_index, page_no, cmd_index, sub_index}` 是本地对象定位与顺序键。`page_no` 来自游戏页对象，局部解析用的 `page_index` 不能替代它；不同 label 的页号相同不等于 OrderKey 重复。当前方案不重新编号游戏页号。

相同跳转目标的多个选项放在一个 `ChoiceBranch.options` 中，共用正文。条件分支通过现有合流逻辑组织。展开时 `active` 阻止递归回到当前链中的 label，`root_visited` 避免同一根下重复展开已归属正文；后续路径保留跳转信息，不再复制正文。

这是一份供翻译使用的结构，不是游戏脚本解释器。不能承诺模拟任意条件、循环次数或随机执行结果。旧 `offset.md` 中 `id=scene:pageNo:ordinal:...` 和单选项 `following_text` 示例属于早期构想；当前格式以 `housamo.hpp` 与 `codec` 实现为准。

### 发布和暂停

解析结果的所有权保持在局部，Scene 构建完成后以 `shared_ptr<const Scene>` 发布；磁盘 JSON 和请求从同一份数据派生。`ProcessCaptured` 在本地提交成功后才派发；若文件已存在，重新从磁盘构造请求。仅解析调试模式在保存后停止派发。

捕获暂停代次贯穿 Hook、构建和提交，提交与暂停转换共享同步边界。`stop_catch` 是暂停语义，不是让 worker 永久退出的信号。修改这里时需同时检查排队任务、在途任务以及恢复路径。

## 5. HET 通信、保存与 ACK

大 JSON 经 `ParcelFileDescriptor` 传输；Binder 负责控制信息和文件描述符。`ITranslationCallback` 区分 `onQuestPatch`、`onSceneCompleted`、`onTranslationFailed`，三者不能混为一条显示完成链。

| 路径 | 主要行为 | 确认含义 |
| --- | --- | --- |
| 局部 patch | Java 接收后调用 `nativeApplyQuestPatch`，按 pending 请求的 `seq_to_order[seq - 1]` 转成有值所有权的回写块 | best-effort 游戏内存更新，不是可靠终态 ACK |
| 完整成功结果 | 默认先保存 HET Scene，再向在线游戏投递；游戏 `nativeApplySceneResult` 接受后通过终态租约 ACK | 游戏已保存结果，不代表当前屏幕刷新 |
| 翻译失败 | 走失败回调与对应终态确认 | 失败通知已处理，不是成功同步 |

`MainHook.handleSceneResult` 持有 `leaseToken` 和连接代次，Native 接受结果后才调用 `acknowledgeTerminal`，成功后再通知 Native 完成确认。不能用“已发送”推导“已显示”。

保存策略还包括已批准的 Scene 缺失回退：只有确认 HET 原始 Scene 及备份均不存在，才转由游戏保存；冲突、损坏、删除意图和 I/O 错误不能当作缺失。详细状态语义见 `CONTEXT.md` 的“Scene 保存回退”。游戏离线时保留结果等待连接，不因此重新请求模型。

Scene 同步门禁与运行期 API 调度不同：启动先完成规定门禁；运行中同步不应让已接纳请求的模型执行等待。同步成功也不代表 Unity 正在消费画面回写队列。

## 6. QuestWriter 与显示刷新

### 6.1 线程和生命周期

Binder/JNI 收到 patch 后只提交回写事件。当前生产实现通过 `il2cpp_runtime_invoke` 的方法元数据识别 `UnitySynchronizationContext.ExecuteTasks`，在原调用成功返回后每次最多消费一个块；不在游戏循环中等待网络。不要将 Android 主线程等同于 Unity 游戏线程。

`QuestTargetSet` 保存 scenario 和页面等候选对象引用，**不拥有或保活这些托管对象**。当前消费代码会检查 scenario 名并在写入时解析对应页面、命令和字符串槽位，但指针非空或大于阈值不能证明对象生命周期有效。互斥锁保护 C++ 容器，不自动保证其中的 Unity 对象存活。

进入后台时，服务仍可进行翻译；游戏 Unity 回调停止执行时，画面回写事件只能等待后续执行时机。后台翻译与后台持续刷新游戏画面是两项不同能力。

### 6.2 源文本与当前显示

```text
QuestWriteBlock（Scene、目标语言、OrderKey 与替换文本）
  -> 查 QuestTargetSet
  -> 定位 page -> CommandList[cmd_index] -> RowData.Strings[Raw]
  -> il2cpp_string_new 创建托管字符串
  -> GC write barrier 替换 raw 引用
  -> 如果影响当前 Text 页：RemakeText
  -> 如果影响当前已显示选项：更新 AdvSelection.Text 与 UI.Text
```

当前实现先收集该块的写入位置和新字符串，再执行 write barrier；不能据此把历史探针的“回读失败整块回滚”描述成当前生产保证。

修改正文 raw 会影响后续建页；当前正文缓存还需要通过 `RemakeText` 刷新。已显示选项有独立的对象链：

```text
AdvPage.Engine -> AdvEngine.SelectionManager
  -> IsShowing（单字节布尔）
  -> Selections: List<AdvSelection>
       RowData -> 与本块命中的 RowData 对应
       Text -> 托管选项文本

AdvUguiSelection.Data -> AdvSelection
AdvUguiSelection.Text -> UnityEngine.UI.Text
```

通过 `RowData` 匹配当前选项，更新 `AdvSelection.Text` 并调用已有 `UI.Text.set_text`。不要为刷新文字重跑 `Init`、`Show`、`AddSelection` 或重建按钮监听。`ClearAll` 清掉观察表；取出的裸指针快照不会因此获得保活。

raw 写入后若 Selection 刷新不完整，当前代码记录警告，不把它当作 raw 提交失败。诊断“已保存但没显示”时应分别检查磁盘结果、回写队列消费、当前页和按钮刷新。

## 7. 配套资源入口

游戏私有输出、HET 对应文件、PageRec 按 Scene 导出及游戏原生缓存的区别见 [HET 与游戏层文件结构](file-layout.md)。

运行时资源检查见 [RuntimeResourceUpdater.java](../app/src/main/java/com/quarty/housamoembedtrans/storage/config/RuntimeResourceUpdater.java)。HET 应用开启及手动点击资源版本时，读取主仓库 `runtime.json`，先要求远端 `GameVersion` 匹配已安装游戏，再在配置锁内比较当前本地与远端规范化 JSON 的 SHA-256；hash 不同或本地覆盖无效时原子更新。对象键递归排序，数组保持原序，格式空白不参与 hash。版本一致只是配置准入条件，不代替对新二进制的 RVA 验证。

OBB 走独立的 [GameObbPort.java](../app/src/main/java/com/quarty/housamoembedtrans/bridge/GameObbPort.java) 与 `IGameObbPort`。游戏端负责自己的 OBB 目录操作，HET 负责版本匹配和下载。OBB 资源就绪、Native Hook 安装成功、Scene 捕获成功要分别判断，不能以“服务已连接”证明游戏已完成加载。

## 8. 调试与版本维护

Native 日志 tag 为 `HousamoTrans`，游戏 Java 也使用对应前缀。Application attach 初始化日志后，Java 日志和 Native `LOGD/LOGI/LOGW/LOGE` 通过同一条 `logging.Log` 队列按日保存到游戏私有目录 `files/housamo_embed_trans/logs/`，并保留 logcat 输出。Native 通过 JNI 传递完整 UTF-8 文本，Scene 表头也进入文件。日志写入不依赖 HET 在线；HET 导出时通过独立的 `IGameLogPort` 获取最近 14 天的文件快照，不经过 Scene 同步或翻译队列。按 `scene`、`requestId` 和时间关联两进程记录。

| 观察目标 | 优先查找的日志或入口 |
| --- | --- |
| 是否注入并初始化 | `MainHook.initializeTarget`、ShadowHook 安装结果 |
| 是否捕获剧情 | `[FindScenarioData]`、`[ScenarioCatcher]`，关注 entry、Scene、解析状态和门禁原因 |
| 是否保存及派发 | `[NativeTranslationPipeline]` 的 commit / encode / dispatcher 日志 |
| Unity 是否消费 | `[QuestWriterMainThread] Unity ExecuteTasks observed` 与 `applying` |
| 是否实际改写 | `WriteQuestBlock succeeded`、`RemakeSelection` 警告，结合当前页信息 |
| 完整结果是否确认 | `Applied Scene result`、ACK/lease 失败日志；这不是画面证据 |

排查闪退还需同时保存 Android crash buffer、native tombstone 和时间点；只过滤模块 tag 会漏掉系统崩溃信息。提供商输出、HET 保存、游戏保存和画面刷新分别取证。

升级游戏版本时：

1. 记录版本名、版本码、ABI、实际 `libil2cpp.so` 哈希或 build ID，以及入口定位依据。
2. 核对函数签名、RVA 和对象字段类型；尤其不要把单字节布尔按 int 读取。
3. 在 `runtime.json` 更新配置；新增字段同时核对 Java 配置检查、Native 解析与使用方。
4. 分别验证捕获、分支顺序、已译文复用、局部回写、当前 Text/Selection 刷新和重进剧情。
5. 将编译结果与设备运行记录分开保存。其他 ABI、进后台、场景重载后的对象生命周期需要各自证据。

## 9. 原 offset.md 的历史验证边界

原文记录了 `diag_scenario_bucket.js` 的 `quest_chara_moritaka01` 样本：44 个 label、234 页、190 个文本项；另有 Shino 的 alt/after label 样本。这些是历史样本计数，不是所有剧情的结构约束。

原文还记录 Housamo 5.19.0 的 Frida probe：一次写入 38 项（35 Text、3 Selection），当前页调用一次 `RemakeText`；已显示的三个选项需要额外更新 `AdvSelection.text` 和三个 `UI.Text.set_text`。这些记录支持区分源文本、正文缓存和选项显示缓存；本次整理未重跑探针，也未独立复核原始输出。

历史脚本使用 `AdvPage.LateUpdate` 消费待执行操作，当前生产实现使用 `UnitySynchronizationContext.ExecuteTasks`。历史探针中的预检、回读、回滚与跨版本 fixture 保护不应直接视为现有 QuestWriter 的全部行为。原文没有记录 restore 成功事件，不能把恢复流程列为已经运行验证。

原文提到的 `AdvPage +0x90 TextData`、`+0x98 CurrentCommand`、`+0xA8 CharacterInfo` 作为历史线索保留；它们不在当前 `Layout.AdvPage` 配置中，未经目标版本核验不得据此新增生产读取。

整理本文时只做了文档与当前源码、配置的静态核对，没有执行构建、API 调用或设备测试。
