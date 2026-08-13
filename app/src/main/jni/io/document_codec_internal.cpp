#include "document_codec_internal.hpp"

#include <limits>
#include <unordered_set>

namespace het::translation::document_codec::internal {
namespace {

bool ReadInteger(
    const rapidjson::Value& object,
    const char* name,
    int* output) {
    if (!object.IsObject() || !output) {
        return false;
    }
    const auto member = object.FindMember(name);
    if (member == object.MemberEnd() || !member->value.IsInt()) {
        return false;
    }
    *output = member->value.GetInt();
    return true;
}

}  // namespace

bool ParseOrderKey(
    const rapidjson::Value& value,
    OrderKey* output) {
    if (!value.IsObject() || !output) {
        return false;
    }
    OrderKey order;
    if (!ReadInteger(value, "label_index", &order.label_index)
        || !ReadInteger(value, "page_no", &order.page_no)
        || !ReadInteger(value, "cmd_index", &order.cmd_index)
        || !ReadInteger(value, "sub_index", &order.sub_index)) {
        return false;
    }
    *output = order;
    return true;
}

bool ParseSeqToOrder(
    const rapidjson::Value& value,
    std::vector<OrderKey>* output) {
    if (!value.IsArray() || !output || value.Empty()
        || value.Size()
            > static_cast<rapidjson::SizeType>(
                std::numeric_limits<int>::max())) {
        return false;
    }

    output->clear();
    output->reserve(value.Size());
    std::unordered_set<OrderKey, OrderKeyHash> unique_orders;
    int expected_seq = 1;
    for (const rapidjson::Value& item : value.GetArray()) {
        if (!item.IsObject()) {
            return false;
        }
        const auto seq = item.FindMember("seq");
        const auto order = item.FindMember("order");
        if (seq == item.MemberEnd() || !seq->value.IsInt()
            || seq->value.GetInt() != expected_seq
            || order == item.MemberEnd()) {
            return false;
        }

        OrderKey parsed;
        if (!ParseOrderKey(order->value, &parsed)
            || !unique_orders.insert(parsed).second) {
            return false;
        }
        output->push_back(parsed);
        ++expected_seq;
    }
    return true;
}

bool IsLanguageStringMap(const rapidjson::Value& value) {
    if (!value.IsObject()) {
        return false;
    }
    for (auto member = value.MemberBegin(); member != value.MemberEnd(); ++member) {
        if (member->name.GetStringLength() == 0
            || !member->value.IsString()
            || member->value.GetStringLength() == 0) {
            return false;
        }
    }
    return true;
}

bool IsLanguageBooleanMap(const rapidjson::Value& value) {
    if (!value.IsObject()) {
        return false;
    }
    for (auto member = value.MemberBegin(); member != value.MemberEnd(); ++member) {
        if (member->name.GetStringLength() == 0 || !member->value.IsBool()) {
            return false;
        }
    }
    return true;
}

bool IsTargetTranslated(
    const rapidjson::Value& translated,
    const std::string& target_lang) {
    if (!IsLanguageBooleanMap(translated) || target_lang.empty()) {
        return false;
    }
    const auto member = translated.FindMember(target_lang.c_str());
    return member != translated.MemberEnd() && member->value.GetBool();
}

}  // namespace het::translation::document_codec::internal
