#pragma once
#include "housamo.hpp"
#include "runtime/utage_runtime.hpp"

// One live scenario owns its raw column arrays. Keep the first value of each
// overwritten cell, including empty cells used by localized-only options.
class ContinuationOriginalRows {
public:
    void BeforeWrite(void* column_array, void* current_text);
    bool Restore(void* column_array, std::string& text) const;
    void RememberDisplay(const QuestWriteBlock& block, const QuestTargetSet& targets);
    bool DisplaySnapshot(QuestWriteBlock& block, QuestTargetSet& targets) const;
private:
    mutable std::mutex mutex_;
    std::unordered_map<void*, std::string> rows_;
    QuestTargetSet display_targets_;
    std::string display_scene_, display_language_;
    std::unordered_map<OrderKey, std::string, OrderKeyHash> display_texts_;
};
struct QuestDisplayRows {
    het::utage::Root scenario;
    std::shared_ptr<ContinuationOriginalRows> rows;
};
// Pins each live scenario while its registered display cells are being changed.
std::vector<QuestDisplayRows> GetQuestDisplayRows();
std::shared_ptr<ContinuationOriginalRows> GetContinuationOriginalRows(void* scenario);
std::string HashContinuationSource(const std::string& source_document, bool legacy = false);
void PublishContinuationSource(void* scenario, const ScenarioParseResult& parsed, const QuestPtrSet& targets);
bool IsContinuationScenario(void* scenario);
bool IsContinuationInputButton(void* selection);
bool IsContinuationLabel(const std::string& label);
