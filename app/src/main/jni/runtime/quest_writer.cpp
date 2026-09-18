#include "translation/native_translation_pipeline.hpp"
#include "translation/store/scene_store.hpp"
#include "translation/pipeline/translation_pipeline_internal.hpp"
#include "housamo.hpp"
#include "scene/block_queue.hpp"
#include "shadowhook.h"
#include "rapidjson/document.h"

#include <inttypes.h>
#include <thread>
#include <memory>

namespace {
    struct ItemWriteBlock {
        void* strings;
        void** raw_slot;
        void* managed_string;
    };

    struct SelectionRefreshItem {
        void* page_data;
        void* row_data;
        void** raw_slot;
    };

    struct VisibleSelectionItem {
        void* data;     // AdvSelection*
        void* ui_text;  // UnityEngine.UI.Text*
    };

    using TargetSetPtr = std::shared_ptr<const QuestTargetSet>;

    std::unordered_map<std::string, TargetSetPtr> g_quest_target_sets;
    std::mutex quest_submit_mutex;
    BlockingQueue<QuestWriteBlock> g_patch_write_queue;
    std::mutex patch_submit_mutex;
    std::unordered_map<void*, VisibleSelectionItem> g_selection_items;
    std::mutex selection_submit_mutex;

    using Il2CppStringNewFn = void* (*)(const char*);
    using Il2CppWriteBarrierFn = void (*)(void*, void**, void*);
    using DomainGetFn = void* (*)();
    using ThreadAttachFn = void* (*)(void*);
    using ThreadDetachFn = void (*)(void*);
    using RemakeTextFn = void (*)(void*, void*);
    using UiTextSetTextFn = void (*)(void*, void*, void*);

    void* g_il2cpp_handle = nullptr;
    Il2CppStringNewFn g_string_new = nullptr;
    Il2CppWriteBarrierFn g_write_barrier = nullptr;
    DomainGetFn g_domain_get = nullptr;
    ThreadAttachFn g_thread_attach = nullptr;
    ThreadDetachFn g_thread_detach = nullptr;
    RemakeTextFn g_remake_text = nullptr;
    UiTextSetTextFn g_ui_text_set_text = nullptr;

    std::atomic<void*> g_page_text_change_fn{nullptr};

    static bool ValidateQuestTargetSet(
        const QuestTargetSet& target_set, 
        const std::string& scene_name, 
        const size_t expected_size
    ) {
        if (!valid_ptr(target_set.scenario_data_ptr)) {
            LOGW("[ValidateQuestTargetSet] scenario_data_ptr is invalid: %p, scene_name: %s", target_set.scenario_data_ptr, scene_name.c_str());
            return false;
        }

        const auto& layout = g_runtime_config.layout;
        const auto& scenario = layout.adv_scenario_data;

        std::string scenario_name = read_il2cpp_string(read_ptr(target_set.scenario_data_ptr, scenario.name));
        if (scenario_name != scene_name) {
            LOGW("[ValidateQuestTargetSet] scenario_name is invalid: %s, scene_name: %s", scenario_name.c_str(), scene_name.c_str());
            return false;
        }

        return true;
    }

    static bool RemakeSelection(void* adv_page, void* current_data, const std::vector<SelectionRefreshItem>& affected_items) {
        if (affected_items.empty()) {
            return true;
        }

        const auto& layout = g_runtime_config.layout;
        const auto& engine = layout.adv_engine;
        const auto& selection_manager = layout.adv_selection_manager;

        // Text 刷新后页面可能已经变化；raw 写入保留，旧页不再刷新。
        if (read_ptr(adv_page, layout.adv_page.current_data) != current_data) {
            return true;
        }

        void* engine_ptr = read_ptr(adv_page, layout.adv_page.engine);
        if (!valid_ptr(engine_ptr)) {
            LOGW("[RemakeSelection] engine_ptr is invalid: %p", engine_ptr);
            return false;
        }

        void* selection_manager_ptr = read_ptr(engine_ptr, engine.selection_manager);
        if (!valid_ptr(selection_manager_ptr)) {
            LOGW("[RemakeSelection] selection_manager_ptr is invalid: %p", selection_manager_ptr);
            return false;
        }
        // IL2CPP 的 IsShowing 是一个字节，不能用 read_int 读取。
        const auto* showing = static_cast<const std::uint8_t*>(selection_manager_ptr)
            + selection_manager.is_showing;
        if (*showing == 0) {
            return true;
        }

        void* selections = read_ptr(selection_manager_ptr, selection_manager.selections);
        if (!valid_ptr(selections)) {
            LOGW("[RemakeSelection] selections is invalid: %p", selections);
            return false;
        }
        int count = read_int(selections, layout.il2cpp_list.size);
        if (count == 0) {
            return true;
        }
        void* items = read_ptr(selections, layout.il2cpp_list.items);
        if (count < 0 || !valid_ptr(items)
            || count > read_int(items, layout.il2cpp_array.length)) {
            LOGW("[RemakeSelection] invalid selections list: count=%d items=%p", count, items);
            return false;
        }

        std::unordered_map<void*, VisibleSelectionItem> visible_items;
        {
            std::lock_guard<std::mutex> lock(selection_submit_mutex);
            visible_items = g_selection_items;
        }
        // 锁只保护观察表；这份裸指针快照不为 managed 对象保活。
        // 不持有观察表锁调用游戏方法，避免回调重新进入登记/清理时死锁。
        bool complete = true;
        size_t updated = 0;
        for (int index = 0; index < count; ++index) {
            void* data = read_ptr(items, layout.il2cpp_array.first_element
                + static_cast<size_t>(index) * layout.il2cpp_array.pointer_size);
            if (!valid_ptr(data)) {
                LOGW("[RemakeSelection] invalid active selection at index=%d", index);
                complete = false;
                continue;
            }
            void* row_data = read_ptr(data, layout.adv_selection.row_data);
            for (const auto& affected : affected_items) {
                if (affected.page_data != current_data || affected.row_data != row_data) {
                    continue;
                }

                bool matched = false;
                for (const auto& entry : visible_items) {
                    void* self = entry.first;
                    const auto& visible = entry.second;
                    if (visible.data != data
                        || read_ptr(self, layout.adv_ugui_selection.data) != data
                        || read_ptr(self, layout.adv_ugui_selection.text) != visible.ui_text) {
                        continue;
                    }

                    void* replacement = *affected.raw_slot;
                    auto** text_slot = reinterpret_cast<void**>(
                        static_cast<std::uint8_t*>(data) + layout.adv_selection.text);
                    g_write_barrier(data, text_slot, replacement);
                    g_ui_text_set_text(visible.ui_text, replacement, nullptr);
                    matched = true;
                    ++updated;
                }
                if (!matched) {
                    LOGW("[RemakeSelection] active selection has no matching observed button: data=%p row=%p",
                         data, row_data);
                    complete = false;
                }
            }
        }
        LOGI("[RemakeSelection] refreshed=%zu complete=%d", updated, complete);
        return complete;
    }

    static bool WriteQuestBlock(const QuestWriteBlock& block, const QuestTargetSet& target_set) {
        const auto& layout = g_runtime_config.layout;
        const auto& page = layout.adv_scenario_page_data;
        const auto& columns = layout.text_columns;
        const auto& cmd = layout.adv_command;
        const auto& list = layout.il2cpp_list;
        const auto& row = layout.string_grid_row;
        const auto& array = layout.il2cpp_array;

        std::vector<ItemWriteBlock> write_blocks;
        write_blocks.reserve(block.items.size());

        std::unordered_set<void*> affected_text_pages;
        std::vector<SelectionRefreshItem> affected_selection_pages;

        for (const QuestWriteItem& item : block.items) {
            void* page_data = nullptr;
            auto page_it = target_set.target_map.find(item.order);
            if (page_it != target_set.target_map.end()) {
                page_data = page_it->second;
            } else {
                LOGW("[WriteQuestBlock] order not found in target_map for scene_name=%s: label_index=%d, page_no=%d, cmd_index=%d, sub_index=%d",
                     block.scene_name.c_str(),
                     item.order.label_index,
                     item.order.page_no,
                     item.order.cmd_index,
                     item.order.sub_index);
                return false;
            }

            void* commands = read_ptr(read_ptr(page_data, page.command_list), list.items);
            if (commands == nullptr) {
                LOGW("[WriteQuestItem] command list invalid for order=%d,%d,%d,%d",
                    item.order.label_index,
                    item.order.page_no,
                    item.order.cmd_index,
                    item.order.sub_index);
                return false;
            }
            
            int command_count = read_int(read_ptr(page_data, page.command_list), list.size);

            if (item.order.cmd_index < 0 || item.order.cmd_index >= command_count) {
                LOGW("[WriteQuestBlock] cmd_index out of bounds for scene_name=%s: label_index=%d, page_no=%d, cmd_index=%d, sub_index=%d",
                     block.scene_name.c_str(),
                     item.order.label_index,
                     item.order.page_no,
                     item.order.cmd_index,
                     item.order.sub_index);
                return false;
            }

            void* cmd_item = read_ptr(commands, array.first_element + item.order.cmd_index * array.pointer_size);
            if (cmd_item == nullptr) {
                LOGW("[WriteQuestItem] command item invalid for order=%d,%d,%d,%d",
                    item.order.label_index,
                    item.order.page_no,
                    item.order.cmd_index,
                    item.order.sub_index);
                return false;
            }

            void* row_data = read_ptr(cmd_item, cmd.row_data);
            void* strings = read_ptr(row_data, row.strings);
            int len = read_int(strings, array.length);

            std::string type = read_il2cpp_string(read_ptr(cmd_item, cmd.type));

            if (len <= columns.raw || len > 4096) {
                LOGW("[WriteQuestItem] strings array length invalid for order=%d,%d,%d,%d: length=%d",
                    item.order.label_index,
                    item.order.page_no,
                    item.order.cmd_index,
                    item.order.sub_index,
                    len);
                return false;
            }

            auto** raw_slot = reinterpret_cast<void**>(static_cast<std::uint8_t*>(strings) + array.first_element + columns.raw * array.pointer_size);
            void* managed_string = g_string_new(item.replacement_text.c_str());

            if (managed_string == nullptr) {
                LOGW("[WriteQuestItem] ReplaceText failed for order=%d,%d,%d,%d replacement_text=%s",
                    item.order.label_index,
                    item.order.page_no,
                    item.order.cmd_index,
                    item.order.sub_index,
                    item.replacement_text.c_str()
                );
                return false;
            }

            write_blocks.push_back({strings, raw_slot, managed_string});
            if (type == "Text") {
                affected_text_pages.insert(page_data);
            } else if (type == "Selection") {
                affected_selection_pages.push_back({page_data, row_data, raw_slot});
            }
        }

        for (const ItemWriteBlock& write_block : write_blocks) {
            g_write_barrier(write_block.strings, write_block.raw_slot, write_block.managed_string);
        }

        void* adv_page = g_page_text_change_fn.load(std::memory_order_acquire);
        if (!valid_ptr(adv_page)) {
            // 当前没有可用的显示页，跳过刷新。
            return true;
        }

        void* current_data = read_ptr(
            adv_page,
            g_runtime_config.layout.adv_page.current_data
        );
        if (!valid_ptr(current_data)) {
            return true;
        }

        if (affected_text_pages.find(current_data) != affected_text_pages.end()) {
            g_remake_text(adv_page, nullptr);
        }

        std::vector<SelectionRefreshItem> affected_items;

        for (const SelectionRefreshItem& selection_item : affected_selection_pages) {
            if (selection_item.page_data == current_data) {
                affected_items.push_back(selection_item);
            }
        }

        if (!affected_items.empty()
            && !RemakeSelection(adv_page, current_data, affected_items)) {
            // raw 已提交；显示刷新失败不意味着该 Scene 的源数据目标失效。
            LOGW("[WriteQuestBlock] raw write completed but selection refresh was incomplete: scene=%s",
                 block.scene_name.c_str());
        }

        return true;
    }

    static void WorkerLoop() {
        void* domain = g_domain_get();
        void* thread = domain ? g_thread_attach(domain) : nullptr;

        if (thread == nullptr) {
            LOGE("[WorkerLoop] Failed to attach thread to IL2CPP domain");
            g_patch_write_queue.Close();
            return;
        }

        struct DetachOnExit {
            void* thread;
            ~DetachOnExit() {
                g_thread_detach(thread);
            }
        } detach{thread};

        QuestWriteBlock block;
        while (g_patch_write_queue.Pop(&block)) {
            std::shared_ptr<const QuestTargetSet> target_set;
            
            {
                std::lock_guard<std::mutex> lock(quest_submit_mutex);
                auto it = g_quest_target_sets.find(block.scene_name);
                if (it == g_quest_target_sets.end()) {
                    LOGW("[WriteQuestBlock] scene_name=%s not found in target sets", block.scene_name.c_str());
                    continue;
                }

                target_set = it->second;
                if (!ValidateQuestTargetSet(*target_set, block.scene_name, block.items.size())) {
                    LOGW("[WriteQuestBlock] ValidateQuestTargetSet failed for scene_name=%s", block.scene_name.c_str());
                    g_quest_target_sets.erase(it);
                    continue;
                }
            }

            if (!WriteQuestBlock(block, *target_set)) {
                LOGE("[WorkerLoop] WriteQuestBlock failed for scene_name=%s", block.scene_name.c_str());
                std::lock_guard<std::mutex> lock(quest_submit_mutex);

                auto it = g_quest_target_sets.find(block.scene_name);
                if (it != g_quest_target_sets.end() && it->second == target_set) {
                    g_quest_target_sets.erase(it);
                    LOGI("[WorkerLoop] Removed scene_name=%s from target sets due to write failure", block.scene_name.c_str());
                }
            } else {
                LOGI("[WorkerLoop] WriteQuestBlock succeeded for scene_name=%s", block.scene_name.c_str());
            }
        }
    }

    bool ReadPatchItem(const rapidjson::Value& item, const std::string& target_lang, std::vector<QuestWriteItem>& result) {
        const auto translation = item.FindMember("translations");

        if (translation == item.MemberEnd() || !translation->value.IsObject()) {
            LOGE("[ReadPatchItem] item translation is missing or not a object");
            return false;
        }

        const auto& translation_obj = translation->value;
        
        QuestWriteItem write_item;

        const auto order = item.FindMember("order");
        
        if (order == item.MemberEnd() || !order->value.IsObject()) {
            LOGE("[ReadPatchItem] item order is missing or not an object");
            result.clear();
            return false;
        }
        const auto& order_obj = order->value;

        const auto label_index = order_obj.FindMember("label_index");
        const auto page_no = order_obj.FindMember("page_no");
        const auto cmd_index = order_obj.FindMember("cmd_index");
        const auto sub_index = order_obj.FindMember("sub_index");

        if (label_index == order_obj.MemberEnd() || !label_index->value.IsInt()
            || page_no == order_obj.MemberEnd() || !page_no->value.IsInt()
            || cmd_index == order_obj.MemberEnd() || !cmd_index->value.IsInt()
            || sub_index == order_obj.MemberEnd() || !sub_index->value.IsInt()) {
            LOGE("[ReadPatchItem] item order fields are missing or not integers");
            result.clear();
            return false;
        }

        write_item.order.label_index = label_index->value.GetInt();
        write_item.order.page_no = page_no->value.GetInt();
        write_item.order.cmd_index = cmd_index->value.GetInt();
        write_item.order.sub_index = sub_index->value.GetInt();

        const auto text_it = translation_obj.FindMember(target_lang.c_str());
        if (text_it == translation_obj.MemberEnd() || !text_it->value.IsString()) {
            LOGE("[ReadPatchItem] translation for target_lang=%s is missing or not a string", target_lang.c_str());
            return false;
        }
        const auto& text = text_it->value;

        write_item.replacement_text.assign(
            text.GetString(),
            text.GetStringLength());

        result.push_back(std::move(write_item));
        return true;
    }

    bool ReadTranslationToPatch(
        const rapidjson::Value& items,
        const std::string& target_lang,
        std::vector<QuestWriteItem>& result) {
        if (!items.IsArray()) {
            return false;
        }
        for (const rapidjson::Value& item : items.GetArray()) {
            if (!item.IsObject()) {
                LOGE("[ReadTranslationsToPatch] item is not an object");
                result.clear();
                return false;
            }
            const auto type = item.FindMember("type");
            if (type == item.MemberEnd() || !type->value.IsString()) {
                LOGE("[ReadTranslationsToPatch] item type is missing or not a string");
                result.clear();
                return false;
            }
            const std::string type_name(
                type->value.GetString(),
                type->value.GetStringLength());

            if (type_name == "text") {
                if (!ReadPatchItem(item, target_lang, result)) {
                    result.clear();
                    return false;
                }
                continue;
            }
            if (type_name == "if") {
                auto following = item.FindMember("following_text");
                if (following == item.MemberEnd()
                    || !ReadTranslationToPatch(following->value, target_lang, result)) {
                    result.clear();
                    return false;
                }
                continue;
            }
            if (type_name != "choice") {
                LOGE("[ReadTranslationsToPatch] unknown item type: %s", type_name.c_str());
                result.clear();
                return false;
            }

            auto branches = item.FindMember("branches");
            if (branches == item.MemberEnd() || !branches->value.IsArray()) {
                LOGE("[ReadTranslationsToPatch] choice branches are invalid");
                result.clear();
                return false;
            }
            for (const rapidjson::Value& branch : branches->value.GetArray()) {
                if (!branch.IsObject()) {
                    LOGE("[ReadTranslationsToPatch] choice branch is invalid");
                    result.clear();
                    return false;
                }
                auto options = branch.FindMember("options");
                auto following = branch.FindMember("following_text");
                if (options == branch.MemberEnd() || !options->value.IsArray()
                    || following == branch.MemberEnd()
                    || !following->value.IsArray()) {
                    LOGE("[ReadTranslationsToPatch] choice branch content is invalid");
                    result.clear();
                    return false;
                }
                for (const rapidjson::Value& option : options->value.GetArray()) {
                    if (!option.IsObject()) {
                        LOGE("[ReadTranslationsToPatch] choice option is invalid");
                        result.clear();
                        return false;
                    }
                    const auto option_type = option.FindMember("type");
                    if (option_type == option.MemberEnd()
                        || !option_type->value.IsString()
                        || std::string(
                            option_type->value.GetString(),
                            option_type->value.GetStringLength()) != "text"
                        || !ReadPatchItem(option, target_lang, result)) {
                        result.clear();
                        return false;
                    }
                }
                if (!ReadTranslationToPatch(following->value, target_lang, result)) {
                    result.clear();
                    return false;
                }
            }
        }
        return true;
    }
} // namespace
void SubmitPageTextChangeFn(void* adv_page) {
    g_page_text_change_fn.store(adv_page, std::memory_order_release);
    LOGI("[SubmitPageTextChangeFn] Submitted page text change for adv_page=%p", adv_page);
    return;
}

void SubmitSelectionItem(void* self) {
    if (!valid_ptr(self)) {
        LOGW("[SubmitSelectionItem] self is invalid: %p", self);
        return;
    }

    void* adv_selection_ptr = read_ptr(self, g_runtime_config.layout.adv_ugui_selection.data);
    if (!valid_ptr(adv_selection_ptr)) {
        LOGW("[SubmitSelectionItem] adv_selection_ptr is invalid for self=%p", self);   
        return;
    }

    void* text_ptr = read_ptr(self, g_runtime_config.layout.adv_ugui_selection.text);
    if (!valid_ptr(text_ptr)) {
        LOGW("[SubmitSelectionItem] text_ptr is invalid for adv_selection_ptr=%p", self);   
        return;
    }

    {
        std::lock_guard<std::mutex> lock(selection_submit_mutex);
        g_selection_items[self] = {adv_selection_ptr, text_ptr};
    }
    return;
}

void ClearSelectionItems() {
    std::lock_guard<std::mutex> lock(selection_submit_mutex);
    g_selection_items.clear();
    LOGI("[ClearSelectionItems] Cleared all selection items");
}

bool InitQuestWriterRuntime(uintptr_t il2cpp_base, const RuntimeConfig& config) {
    g_il2cpp_handle = shadowhook_dlopen("libil2cpp.so");
    if (!g_il2cpp_handle || g_il2cpp_handle == nullptr) {
        LOGE("[InitQuestWriterRuntime] Failed to load libil2cpp.so");
        return false;
    }

    g_string_new = reinterpret_cast<Il2CppStringNewFn>(shadowhook_dlsym(g_il2cpp_handle, "il2cpp_string_new"));
    g_write_barrier = reinterpret_cast<Il2CppWriteBarrierFn>(shadowhook_dlsym(g_il2cpp_handle, "il2cpp_gc_wbarrier_set_field"));
    g_domain_get = reinterpret_cast<DomainGetFn>(shadowhook_dlsym(g_il2cpp_handle, "il2cpp_domain_get"));
    g_thread_attach = reinterpret_cast<ThreadAttachFn>(shadowhook_dlsym(g_il2cpp_handle, "il2cpp_thread_attach"));
    g_thread_detach = reinterpret_cast<ThreadDetachFn>(shadowhook_dlsym(g_il2cpp_handle, "il2cpp_thread_detach"));
    g_remake_text = reinterpret_cast<RemakeTextFn>(il2cpp_base + config.rva.remake_text);
    g_ui_text_set_text = reinterpret_cast<UiTextSetTextFn>(il2cpp_base + config.rva.ui_text_set_text);

    if (g_string_new == nullptr) {
        LOGE(
            "[QuestWriter] failed to resolve IL2CPP functions "
            "string_new=%p",
            reinterpret_cast<void*>(g_string_new)
        );
        return false;
    }

    if (g_write_barrier == nullptr) {
        LOGE(
            "[QuestWriter] failed to resolve IL2CPP functions "
            "write_barrier=%p",
            reinterpret_cast<void*>(g_write_barrier)
        );
        return false;
    }

    if (g_domain_get == nullptr) {
        LOGE(
            "[QuestWriter] failed to resolve IL2CPP functions "
            "domain_get=%p",
            reinterpret_cast<void*>(g_domain_get)
        );
        return false;
    }

    if (g_thread_attach == nullptr) {
        LOGE(
            "[QuestWriter] failed to resolve IL2CPP functions "
            "thread_attach=%p",
            reinterpret_cast<void*>(g_thread_attach)
        );
        return false;
    }

    if (g_thread_detach == nullptr) {
        LOGE(
            "[QuestWriter] failed to resolve IL2CPP functions "
            "thread_detach=%p",
            reinterpret_cast<void*>(g_thread_detach)
        );
        return false;
    }

    if (g_remake_text == nullptr) {
        LOGE(
            "[QuestWriter] failed to resolve RemakeText: base=%p rva=0x%" PRIxPTR,
            reinterpret_cast<void*>(il2cpp_base),
            config.rva.remake_text
        );
        return false;
    }

    LOGI(
        "[QuestWriter] resolved RemakeText=%p (base=%p, rva=0x%" PRIxPTR ")",
        reinterpret_cast<void*>(g_remake_text),
        reinterpret_cast<void*>(il2cpp_base),
        config.rva.remake_text
    );

    return true;
}

bool StartQuestWriter(uintptr_t il2cpp_base, const RuntimeConfig& config) {
    static std::once_flag once;
    static bool started = false;

    std::call_once(once, [il2cpp_base, &config] {
        if (!InitQuestWriterRuntime(il2cpp_base, config)) {
            return;
        }

        std::thread(WorkerLoop).detach();
        started = true;
    });

    return started;
}

bool SubmitQuestTargetSet(const std::string& scene_name, const QuestTargetSet& target_set) {
    if (scene_name.empty() || target_set.scenario_data_ptr == nullptr || target_set.target_map.empty()) {
        LOGW("[SubmitQuestTargetSet] scene_name is empty or target_set is invalid");
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(quest_submit_mutex);
        if (g_quest_target_sets.find(scene_name) != g_quest_target_sets.end()) {
            LOGW("[SubmitQuestTargetSet] scene_name=%s already exists, overwriting", scene_name.c_str());
        }
        g_quest_target_sets[scene_name] = std::make_shared<const QuestTargetSet>(target_set);
        
    }
    LOGI("[SubmitQuestTargetSet] submitted scene_name=%s", scene_name.c_str());
    return true;
}

bool SubmitPatchToWriter(QuestWriteBlock block) {
    if (block.scene_name.empty() || block.items.empty()) {
        LOGW("[SubmitPatchToWriter] scene_name is empty or items are empty");
        return false;
    }
    std::string scene_name = block.scene_name;
    size_t item_count = block.items.size();
    {
        std::lock_guard<std::mutex> lock(patch_submit_mutex);
        if(g_patch_write_queue.Push(std::move(block))) {
            LOGI("[SubmitPatchToWriter] submitted patch for scene_name=%s with %zu items", scene_name.c_str(), item_count);
        } else {
            LOGE("[SubmitPatchToWriter] failed to submit patch to queue for scene_name=%s with %zu items", scene_name.c_str(), item_count);
            return false;
        }
    }
    return true;
}

bool SubmitSceneToWriter(const std::string& scene_name, const std::string& target_lang) {
    std::string scene_json;
    std::string error;

    if (!het::translation::scene_store::Read(scene_name, &scene_json, &error)) {
        LOGE("[SubmitSceneToWriter] failed to get scene JSON for scene_name=%s: %s", scene_name.c_str(), error.c_str());
        return false;
    }

    rapidjson::Document doc;
    if (doc.Parse(scene_json.c_str()).HasParseError()) {
        LOGE("[SubmitSceneToWriter] failed to parse scene JSON for scene_name=%s", scene_name.c_str());
        return false;
    }

    if (!doc.IsObject()) {
        LOGE("[SubmitSceneToWriter] scene JSON is not an object for scene_name=%s", scene_name.c_str());
        return false;
    }
    const auto items = doc.FindMember("scene_items");
    if (items == doc.MemberEnd() || !items->value.IsArray()) {
        LOGE("[SubmitSceneToWriter] scene JSON does not contain a valid 'scene_items' array for scene_name=%s", scene_name.c_str());
        return false;
    }

    std::vector<QuestWriteItem> g_temp_write_items;
    if (!ReadTranslationToPatch(items->value, target_lang, g_temp_write_items)) {
        LOGE("[SubmitSceneToWriter] failed to read translation to patch for scene_name=%s", scene_name.c_str());
        return false;
    }

    QuestWriteBlock block;
    block.scene_name = scene_name;
    block.target_lang = target_lang;
    block.items = std::move(g_temp_write_items);

    return SubmitPatchToWriter(std::move(block));
}

bool SubmitQuestPatchToWriter(const std::string& request_id, const std::string& patch) {
    auto request =
        het::translation::translation_dispatcher::PeekPendingRequest(request_id);

    if (!request) {
        LOGW("[SubmitQuestPatchJson] request not found: %s",
            request_id.c_str());
        return false;
    }

    rapidjson::Document doc;
    doc.Parse(patch.data(), patch.size());

    if (doc.HasParseError() || !doc.IsObject()) {
        LOGE("[SubmitQuestPatchToWriter] failed to parse patch JSON for request_id=%s", request_id.c_str());
        return false;
    }

    const auto updates = doc.FindMember("updates");
    if (updates == doc.MemberEnd() || !updates->value.IsArray()) {
        return false;
    }

    QuestWriteBlock block;
    block.scene_name = request->scene_name;
    block.target_lang = request->target_lang;

    std::vector<QuestWriteItem> temp_write_items;

    for (const auto& update : updates->value.GetArray()) {
        if (!update.IsObject()) {
            LOGE("[SubmitQuestPatchToWriter] update is not an object for request_id=%s", request_id.c_str());
            temp_write_items.clear();
            return false;
        }
        QuestWriteItem write_item;
        write_item.order = request->seq_to_order[update.FindMember("seq")->value.GetInt() - 1];
        write_item.replacement_text = update.FindMember("text")->value.GetString();
        temp_write_items.push_back(write_item);
    }

    if (!temp_write_items.empty()) {
        block.items = std::move(temp_write_items);
        LOGI("[SubmitQuestPatchToWriter] submitting patch for request_id=%s with %zu items", request_id.c_str(), block.items.size());
        return SubmitPatchToWriter(std::move(block));
    }
    LOGW("[SubmitQuestPatchToWriter] no valid updates for request_id=%s", request_id.c_str());
    return false;
}