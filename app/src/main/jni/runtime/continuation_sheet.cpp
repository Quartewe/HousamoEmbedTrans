#include "runtime/continuation_sheet.hpp"
#include "rapidjson/stringbuffer.h"
#include "rapidjson/writer.h"

namespace het::continuation {
using namespace utage;
namespace {
enum Column { Command, Arg1, Arg2, Arg3, Arg4, Arg5, Arg6, Text, Columns };
using Row = std::array<std::string, Columns>;
using Rows = std::vector<Row>;

std::string Cell(const rapidjson::Value& value, const char* key) {
    auto it = value.FindMember(key);
    if (it == value.MemberEnd())
        return {};
    if (it->value.IsString())
        return {it->value.GetString(), it->value.GetStringLength()};
    if (!it->value.IsNumber())
        return {};
    rapidjson::StringBuffer buffer;
    rapidjson::Writer<rapidjson::StringBuffer> writer(buffer);
    it->value.Accept(writer);
    return {buffer.GetString(), buffer.GetSize()};
}

void WaitFade(Rows& rows) {
    rows.push_back({"WaitFadeObjects", "AllCharacterLayers"});
    rows.push_back({"WaitFadeObjects", "AllBgLayers"});
}
void Compile(const rapidjson::Value& steps, const std::string& prefix, Sheet& out, Rows& rows) {
    out.entry = prefix + "_0";
    for (rapidjson::SizeType i = 0; i < steps.Size(); ++i) {
        const auto& step = steps[i];
        const auto label = prefix + "_" + std::to_string(i);
        rows.push_back({"*" + label});
        if (Cell(step, "type") == "dialogue") {
            out.steps[label].speaker = Cell(step, "speaker");
            rows.push_back({"Text", "", "", "", "", "", "", Cell(step, "text")});
        } else {
                std::string action = Cell(step, "action"), fade = Cell(step, "fade");
                if (action == "bg")
                    rows.push_back({"Bg", Cell(step, "bg"), "", "", "", "", fade});
                else
                    WaitFade(rows);
        }
    }
    WaitFade(rows);
    rows.push_back({"Jump", "*" + out.merge});
}
} // namespace

bool BuildSheet(void* manager, const rapidjson::Value& steps, const std::string& prefix, Sheet& out) {
    Root settings(api.Get(manager, "get_SettingDataManager")), macros(api.Get(manager, "get_MacroManager"));
    out.table = Root(api.Get(manager, "get_ScenarioDataTbl"));
    out.key = Root(api.string_new(prefix.c_str()));
    Root grid = NewObject("Utage", "StringGrid"), sheet = NewObject("Utage", "AdvImportScenarioSheet");
    out.scenario = NewObject("Utage", "AdvScenarioData");
    void* str = api.Class("System", "String");
    if (!str || !out.table.Get() || !settings.Get() || !macros.Get() || !grid.Get() || !sheet.Get() ||
        !out.scenario.Get())
        return false;
    int csv = 0;
    void* args[] = {out.key.Get(), out.key.Get(), &csv};
    auto klass = api.object_get_class(grid.Get());
    if (!api.Invoke(api.Overload(klass, ".ctor", 3, 1, str), grid.Get(), args))
        return false;
    Rows rows{{"Command", "Arg1", "Arg2", "Arg3", "Arg4", "Arg5", "Arg6", "Text"}};
    Compile(steps, prefix, out, rows);
    const void* addRow = nullptr;
    for (auto& row : rows) {
        Root array(api.array_new(str, Columns));
        if (!array.Get())
            return false;
        if (!addRow)
            addRow = api.Overload(klass, "AddRow", 1, 0, api.object_get_class(array.Get()));
        auto slots = reinterpret_cast<void**>(static_cast<unsigned char*>(array.Get()) +
                                              g_runtime_config.layout.il2cpp_array.first_element);
        for (size_t i = 0; i < row.size(); ++i) {
            // Macro/entity import examines every cell. Give it a neutral
            // text cell and install the authored dialogue on the imported row.
            bool literal = i == Text && (row[Command] == "Text" || row[Command] == "Selection");
            const std::string& value = row[i];
            void* cell = literal ? api.string_new("text")
                                 : api.string_new_len(value.data(), static_cast<uint32_t>(value.size()));
            if (!cell)
                return false;
            api.gc_wbarrier_set_field(array.Get(), slots + i, cell);
        }
        void* rowArgs[] = {array.Get()};
        if (!api.Invoke(addRow, grid.Get(), rowArgs))
            return false;
    }
    int first = 0;
    void* headerArgs[] = {&first};
    void* importArgs[] = {grid.Get(), settings.Get(), macros.Get()};
    void *ctorArgs[] = {sheet.Get()}, *initArgs[] = {settings.Get()};
    if (!api.Call(grid.Get(), "ParseHeader", 1, headerArgs) || !api.Call(sheet.Get(), ".ctor", 3, importArgs))
        return false;
    Root imported(api.Get(sheet.Get(), "get_Rows"));
    if (api.Int(imported.Get(), "get_Count") != static_cast<int>(rows.size()))
        return false;
    for (size_t index = 1; index < rows.size(); ++index) {
        if (rows[index][Command] != "Text" && rows[index][Command] != "Selection")
            continue;
        int at = static_cast<int>(index);
        void* getArgs[] = {&at};
        void* row = nullptr;
        if (!api.Call(imported.Get(), "get_Item", 1, getArgs, &row))
            return false;
        void* array = api.Get(row, "get_Strings");
        if (!array || api.Int(row, "get_Length") <= Text)
            return false;
        const auto& text = rows[index][Text];
        Root value(api.string_new_len(text.data(), static_cast<uint32_t>(text.size())));
        if (!value.Get())
            return false;
        auto slots = reinterpret_cast<void**>(static_cast<unsigned char*>(array) +
                                              g_runtime_config.layout.il2cpp_array.first_element);
        api.gc_wbarrier_set_field(array, slots + Text, value.Get());
    }
    if (!api.Call(out.scenario.Get(), ".ctor", 1, ctorArgs) ||
        !api.Call(out.scenario.Get(), "Init", 1, initArgs))
        return false;
    Root entry(api.string_new(out.entry.c_str()));
    void* entryArgs[] = {entry.Get()};
    void* found = nullptr;
    if (!api.Call(out.scenario.Get(), "FindScenarioLabelData", 1, entryArgs, &found) || !found)
        return false;
    void* registerArgs[] = {out.key.Get(), out.scenario.Get()};
    return api.Call(out.table.Get(), "Add", 2, registerArgs);
}

void UnregisterSheet(Sheet& sheet) {
    void* args[] = {sheet.key.Get()};
    api.Call(sheet.table.Get(), "Remove", 1, args);
}
} // namespace het::continuation
