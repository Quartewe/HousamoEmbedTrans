package com.quarty.housamoembedtrans.translation.request;

/** Additional main-response contract; never included in a body-repair request. */
public final class InternalTermsPrompt {
    private InternalTermsPrompt() { }

    public static String appendTo(String originalPrompt) {
        return originalPrompt + "\n\n" + String.join("\n",
            "本次主翻译启用 Context 内部词典。以下 terms 协议补充并取代原提示词中仅有 summary、translation、complete 的事件顺序要求；其他正文规则不变。",
            "临时术语用于保持当前 Context 内的译名一致。",
            "1. 优先使用正式角色词典、游戏术语库中非空的目标语言译名；正式译名缺失时，使用已提供的 Context 临时译名。已有译名直接沿用，不重复输出，不另起译名。",
            "如果已有译名加上普通修饰词即可准确表达完整名称，直接组合使用，不为该扩展形式另建临时词条。",
            "2. 仅从本次 Scene 的原文和说话人名称中提取需要稳定译名的名称或固定概念。正式词条存在但目标语言译名为空，仍视为缺少译名。不从历史摘要或背景资料中额外扩充术语。",
            "当前 Scene 没有需要新增的术语时，不新增术语，输出空 terms 列表。不要为了输出术语而强行提取词语，也不要求每个 Scene 都产生新术语。",
            "3. 不收录普通词语、泛指称呼、完整句子、招式名称，以及咒语、咏唱中的专名或仪式用语。不因词语生僻、使用外文或片假名、带有引号而将其收录。不为特定作品、章节、角色或具体词项设置特殊收录规则。",
            "4. 每条新增记录只包含原文名称与本次采用的目标语言译名。原文名称必须保持原样，不自行创造、推断或扩展别名。同一原文名称只输出一次。",
            "5. 在输出摘要前确定本次采用的译名。摘要、新增术语记录和正文必须使用一致的译名。",
            "6. 输出顺序为：summary → terms → 正文翻译 → complete。terms 事件必须输出一次；没有新增术语时，输出空列表：",
            "{\"type\":\"terms\",\"terms\":[]}",
            "有新增术语时，使用以下结构：",
            "{\"type\":\"terms\",\"terms\":[{\"term\":\"原文名称\",\"translation\":\"目标语言译名\"}]}"
        );
    }
}
