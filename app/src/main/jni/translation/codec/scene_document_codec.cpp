#include "document_codec.hpp"
#include "document_codec_internal.hpp"
#include "scene/scene_identity.hpp"

#include <rapidjson/document.h>
#include <rapidjson/prettywriter.h>
#include <rapidjson/stringbuffer.h>
#include <rapidjson/writer.h>

#include <limits>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace het::translation::document_codec {
namespace {

using Allocator = rapidjson::Document::AllocatorType;

struct RequestBuildContext {
    std::vector<OrderKey> seq_to_order;
    bool valid = true;

    int AddText(const OrderKey& order) {
        if (seq_to_order.size()
            >= static_cast<size_t>(std::numeric_limits<int>::max())) {
            valid = false;
            return 0;
        }
        seq_to_order.push_back(order);
        return static_cast<int>(seq_to_order.size());
    }
};

struct EncodedSceneItem {
    rapidjson::Value local;
    rapidjson::Value request;
};

bool Fail(std::string* error, const std::string& message) {
    if (error) {
        *error = message;
    }
    return false;
}

rapidjson::Value JsonString(const std::string& value, Allocator& allocator) {
    return rapidjson::Value(
        value.data(),
        static_cast<rapidjson::SizeType>(value.size()),
        allocator);
}

rapidjson::Value JsonStringArray(
    const std::vector<std::string>& values,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kArrayType);
    for (const std::string& value : values) {
        result.PushBack(JsonString(value, allocator), allocator);
    }
    return result;
}

rapidjson::Value JsonI18N(const I18N& value, Allocator& allocator) {
    rapidjson::Value result(rapidjson::kObjectType);
    result.AddMember("en", JsonString(value.en, allocator), allocator);
    result.AddMember("zh_tw", JsonString(value.zh_tw, allocator), allocator);
    result.AddMember("zh_cn", JsonString(value.zh_cn, allocator), allocator);
    return result;
}

rapidjson::Value JsonAlias(const AliasItem& alias, Allocator& allocator) {
    rapidjson::Value result(rapidjson::kObjectType);
    result.AddMember("name", JsonString(alias.name, allocator), allocator);
    result.AddMember("i18n", JsonI18N(alias.i18n, allocator), allocator);
    result.AddMember("called", JsonString(alias.called, allocator), allocator);
    return result;
}

rapidjson::Value JsonAliases(
    const std::vector<AliasItem>& aliases,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kArrayType);
    for (const AliasItem& alias : aliases) {
        result.PushBack(JsonAlias(alias, allocator), allocator);
    }
    return result;
}

rapidjson::Value JsonRelationship(
    const Relationship& relationship,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kObjectType);
    result.AddMember(
        "target",
        JsonString(relationship.target, allocator),
        allocator);
    result.AddMember(
        "type",
        JsonString(relationship.type, allocator),
        allocator);
    return result;
}

rapidjson::Value JsonRelationships(
    const std::vector<Relationship>& relationships,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kArrayType);
    for (const Relationship& relationship : relationships) {
        result.PushBack(
            JsonRelationship(relationship, allocator),
            allocator);
    }
    return result;
}

rapidjson::Value JsonCharacterItem(
    const CharacterItem& character,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kObjectType);
    result.AddMember("name", JsonString(character.name, allocator), allocator);
    result.AddMember("aliases", JsonAliases(character.aliases, allocator), allocator);
    result.AddMember("i18n", JsonI18N(character.i18n, allocator), allocator);
    result.AddMember("school", JsonStringArray(character.school, allocator), allocator);
    result.AddMember("guild", JsonStringArray(character.guild, allocator), allocator);
    result.AddMember(
        "origin_world",
        JsonStringArray(character.origin_world, allocator),
        allocator);
    result.AddMember(
        "relationships",
        JsonRelationships(character.relationships, allocator),
        allocator);
    result.AddMember("info", JsonString(character.info, allocator), allocator);
    result.AddMember(
        "description",
        JsonString(character.description, allocator),
        allocator);
    result.AddMember(
        "speech_style",
        JsonString(character.speech_style, allocator),
        allocator);
    return result;
}

rapidjson::Value JsonCharacterItems(
    const std::vector<CharacterItem>& characters,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kArrayType);
    for (const CharacterItem& character : characters) {
        result.PushBack(JsonCharacterItem(character, allocator), allocator);
    }
    return result;
}

rapidjson::Value JsonCharacter(
    const Character& character,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kObjectType);
    result.AddMember("mc", JsonCharacterItem(character.mc, allocator), allocator);
    result.AddMember(
        "high_weight",
        JsonCharacterItems(character.high_weight, allocator),
        allocator);
    result.AddMember(
        "low_weight",
        JsonCharacterItems(character.low_weight, allocator),
        allocator);
    return result;
}

rapidjson::Value JsonMentionedCharacters(
    const std::vector<MentionedCharacter>& characters,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kArrayType);
    for (const MentionedCharacter& character : characters) {
        rapidjson::Value item(rapidjson::kObjectType);
        item.AddMember("name", JsonString(character.name, allocator), allocator);
        item.AddMember("i18n", JsonI18N(character.i18n, allocator), allocator);
        result.PushBack(item, allocator);
    }
    return result;
}

rapidjson::Value JsonGameTerms(
    const std::vector<GameTerm>& terms,
    Allocator& allocator) {
    rapidjson::Value result(rapidjson::kArrayType);
    for (const GameTerm& term : terms) {
        rapidjson::Value item(rapidjson::kObjectType);
        item.AddMember("term", JsonString(term.term, allocator), allocator);
        item.AddMember("i18n", JsonI18N(term.i18n, allocator), allocator);
        item.AddMember(
            "description",
            JsonString(term.description, allocator),
            allocator);
        result.PushBack(item, allocator);
    }
    return result;
}

rapidjson::Value JsonOrderKey(const OrderKey& order, Allocator& allocator) {
    rapidjson::Value result(rapidjson::kObjectType);
    result.AddMember("label_index", order.label_index, allocator);
    result.AddMember("page_no", order.page_no, allocator);
    result.AddMember("cmd_index", order.cmd_index, allocator);
    result.AddMember("sub_index", order.sub_index, allocator);
    return result;
}

void AddProtectArrays(
    const std::vector<ProtectedToken>& tokens,
    rapidjson::Value* local,
    rapidjson::Value* request,
    Allocator& local_allocator,
    Allocator& request_allocator) {
    local->SetArray();
    request->SetArray();
    for (const ProtectedToken& token : tokens) {
        rapidjson::Value local_item(rapidjson::kObjectType);
        local_item.AddMember(
            "order",
            JsonOrderKey(token.order, local_allocator),
            local_allocator);
        local_item.AddMember(
            "label",
            JsonString(token.label, local_allocator),
            local_allocator);
        local_item.AddMember(
            "origin",
            JsonString(token.origin, local_allocator),
            local_allocator);
        local->PushBack(local_item, local_allocator);

        rapidjson::Value request_item(rapidjson::kObjectType);
        request_item.AddMember(
            "label",
            JsonString(token.label, request_allocator),
            request_allocator);
        request_item.AddMember(
            "origin",
            JsonString(token.origin, request_allocator),
            request_allocator);
        request->PushBack(request_item, request_allocator);
    }
}

EncodedSceneItem EncodeSceneItem(
    const SceneItem& item,
    Allocator& local_allocator,
    Allocator& request_allocator,
    RequestBuildContext& context);

EncodedSceneItem EncodeText(
    const TextItem& text,
    Allocator& local_allocator,
    Allocator& request_allocator,
    RequestBuildContext& context) {
    EncodedSceneItem result{
        rapidjson::Value(rapidjson::kObjectType),
        rapidjson::Value(rapidjson::kObjectType)};

    result.local.AddMember("type", "text", local_allocator);
    result.local.AddMember(
        "order",
        JsonOrderKey(text.order, local_allocator),
        local_allocator);
    result.local.AddMember(
        "speaker",
        JsonString(text.speaker, local_allocator),
        local_allocator);
    result.local.AddMember(
        "text",
        JsonString(text.text, local_allocator),
        local_allocator);
    result.local.AddMember(
        "translations",
        rapidjson::Value(rapidjson::kObjectType),
        local_allocator);

    result.request.AddMember("type", "text", request_allocator);
    result.request.AddMember("seq", context.AddText(text.order), request_allocator);
    result.request.AddMember(
        "speaker",
        JsonString(text.speaker, request_allocator),
        request_allocator);
    result.request.AddMember(
        "text",
        JsonString(text.text, request_allocator),
        request_allocator);
    return result;
}

EncodedSceneItem EncodeChoice(
    const ChoiceBlock& choice,
    Allocator& local_allocator,
    Allocator& request_allocator,
    RequestBuildContext& context) {
    EncodedSceneItem result{
        rapidjson::Value(rapidjson::kObjectType),
        rapidjson::Value(rapidjson::kObjectType)};

    result.local.AddMember("type", "choice", local_allocator);
    result.local.AddMember(
        "order",
        JsonOrderKey(choice.order, local_allocator),
        local_allocator);
    result.local.AddMember(
        "merge_label",
        JsonString(choice.merge_label, local_allocator),
        local_allocator);

    result.request.AddMember("type", "choice", request_allocator);
    result.request.AddMember(
        "merge_label",
        JsonString(choice.merge_label, request_allocator),
        request_allocator);

    rapidjson::Value local_branches(rapidjson::kArrayType);
    rapidjson::Value request_branches(rapidjson::kArrayType);
    for (const ChoiceBranch& branch : choice.branches) {
        rapidjson::Value local_branch(rapidjson::kObjectType);
        rapidjson::Value request_branch(rapidjson::kObjectType);
        local_branch.AddMember(
            "target_label",
            JsonString(branch.target_label, local_allocator),
            local_allocator);
        request_branch.AddMember(
            "target_label",
            JsonString(branch.target_label, request_allocator),
            request_allocator);

        rapidjson::Value local_options(rapidjson::kArrayType);
        rapidjson::Value request_options(rapidjson::kArrayType);
        for (const TextItem& option : branch.options) {
            EncodedSceneItem encoded = EncodeText(
                option,
                local_allocator,
                request_allocator,
                context);
            local_options.PushBack(encoded.local, local_allocator);
            request_options.PushBack(encoded.request, request_allocator);
        }
        local_branch.AddMember("options", local_options, local_allocator);
        request_branch.AddMember("options", request_options, request_allocator);

        rapidjson::Value local_following(rapidjson::kArrayType);
        rapidjson::Value request_following(rapidjson::kArrayType);
        for (const SceneItem& following : branch.following_text) {
            EncodedSceneItem encoded = EncodeSceneItem(
                following,
                local_allocator,
                request_allocator,
                context);
            local_following.PushBack(encoded.local, local_allocator);
            request_following.PushBack(encoded.request, request_allocator);
        }
        local_branch.AddMember(
            "following_text",
            local_following,
            local_allocator);
        request_branch.AddMember(
            "following_text",
            request_following,
            request_allocator);
        local_branches.PushBack(local_branch, local_allocator);
        request_branches.PushBack(request_branch, request_allocator);
    }

    result.local.AddMember("branches", local_branches, local_allocator);
    result.request.AddMember("branches", request_branches, request_allocator);
    return result;
}

EncodedSceneItem EncodeIf(
    const IfBlock& if_block,
    Allocator& local_allocator,
    Allocator& request_allocator,
    RequestBuildContext& context) {
    EncodedSceneItem result{
        rapidjson::Value(rapidjson::kObjectType),
        rapidjson::Value(rapidjson::kObjectType)};

    result.local.AddMember("type", "if", local_allocator);
    result.local.AddMember(
        "order",
        JsonOrderKey(if_block.order, local_allocator),
        local_allocator);
    result.local.AddMember(
        "condition",
        JsonString(if_block.condition, local_allocator),
        local_allocator);
    result.local.AddMember(
        "target_label",
        JsonString(if_block.target_label, local_allocator),
        local_allocator);
    result.local.AddMember(
        "merge_label",
        JsonString(if_block.merge_label, local_allocator),
        local_allocator);

    result.request.AddMember("type", "if", request_allocator);
    result.request.AddMember(
        "condition",
        JsonString(if_block.condition, request_allocator),
        request_allocator);
    result.request.AddMember(
        "target_label",
        JsonString(if_block.target_label, request_allocator),
        request_allocator);
    result.request.AddMember(
        "merge_label",
        JsonString(if_block.merge_label, request_allocator),
        request_allocator);

    rapidjson::Value local_following(rapidjson::kArrayType);
    rapidjson::Value request_following(rapidjson::kArrayType);
    for (const SceneItem& following : if_block.following_text) {
        EncodedSceneItem encoded = EncodeSceneItem(
            following,
            local_allocator,
            request_allocator,
            context);
        local_following.PushBack(encoded.local, local_allocator);
        request_following.PushBack(encoded.request, request_allocator);
    }
    result.local.AddMember(
        "following_text",
        local_following,
        local_allocator);
    result.request.AddMember(
        "following_text",
        request_following,
        request_allocator);
    return result;
}

EncodedSceneItem EncodeSceneItem(
    const SceneItem& item,
    Allocator& local_allocator,
    Allocator& request_allocator,
    RequestBuildContext& context) {
    return std::visit(
        [&](const auto& value) -> EncodedSceneItem {
            using ValueType = std::decay_t<decltype(value)>;
            if constexpr (std::is_same_v<ValueType, TextItem>) {
                return EncodeText(
                    value,
                    local_allocator,
                    request_allocator,
                    context);
            } else if constexpr (std::is_same_v<ValueType, ChoiceBlock>) {
                return EncodeChoice(
                    value,
                    local_allocator,
                    request_allocator,
                    context);
            } else {
                return EncodeIf(
                    value,
                    local_allocator,
                    request_allocator,
                    context);
            }
        },
        item.value);
}

void AddSharedMember(
    rapidjson::Document* local,
    rapidjson::Document* request,
    const char* name,
    rapidjson::Value local_value) {
    rapidjson::Value request_value;
    request_value.CopyFrom(local_value, request->GetAllocator());
    local->AddMember(
        rapidjson::StringRef(name),
        local_value,
        local->GetAllocator());
    request->AddMember(
        rapidjson::StringRef(name),
        request_value,
        request->GetAllocator());
}

rapidjson::Value StageJson(const SceneStage& stage, Allocator& a) {
    using rapidjson::Value;
    Value root(rapidjson::kObjectType), labels(rapidjson::kArrayType), resources(rapidjson::kObjectType);
    for (const auto& label:stage.labels) labels.PushBack(JsonString(label,a),a);
    root.AddMember("labels",labels,a);
    Value chars(rapidjson::kObjectType), layers(rapidjson::kObjectType), bgs(rapidjson::kArrayType);
    for (const auto& character:stage.characters) {
        Value item(rapidjson::kObjectType), patterns(rapidjson::kArrayType);
        for (const auto& pattern:character.second) patterns.PushBack(JsonString(pattern,a),a);
        item.AddMember("patterns",patterns,a);
        chars.AddMember(JsonString(character.first,a),item,a);
    }
    for(const auto& layer:stage.layers) {
        const auto& cfg=layer.second;
        Value item(rapidjson::kObjectType);
        item.AddMember("x",cfg.x,a).AddMember("y",cfg.y,a).AddMember("order",cfg.order,a)
            .AddMember("scale_x",cfg.scale_x,a).AddMember("scale_y",cfg.scale_y,a)
            .AddMember("flip_x",cfg.flip_x,a).AddMember("flip_y",cfg.flip_y,a);
        layers.AddMember(JsonString(layer.first,a),item,a);
    }
    for(const auto& bg:stage.backgrounds) bgs.PushBack(JsonString(bg,a),a);
    resources.AddMember("characters",chars,a).AddMember("layers",layers,a).AddMember("backgrounds",bgs,a);
    root.AddMember("resources",resources,a);
    Value states(rapidjson::kArrayType);
    for(const auto& state:stage.states) {
        Value item(rapidjson::kObjectType),orders(rapidjson::kArrayType),display(rapidjson::kObjectType);
        for(const auto& order:state.text_orders) orders.PushBack(JsonOrderKey(order,a),a);
        item.AddMember("order",JsonOrderKey(state.order,a),a).AddMember("text_orders",orders,a);
        auto arg=[&](Value& v,const char* key,const char* col) {
            auto it=state.parameters.find(col);
            if(it!=state.parameters.end()) v.AddMember(rapidjson::StringRef(key),JsonString(it->second,a),a);
        };
        const bool actor=state.kind=="Character";
        if(actor || state.kind=="Bg") {
            arg(display,actor?"label":"resource","Arg1");
            if(actor) arg(display,"pattern","Arg2");
            arg(display,"layer","Arg3"); arg(display,"x","Arg4"); arg(display,"y","Arg5");
            item.AddMember(rapidjson::StringRef(actor?"character":"background"),display,a);
        } else {
            Value visibility(rapidjson::kObjectType);
            bool off=state.kind=="CharacterOff";
            if(off) arg(display,"target","Arg1");
            display.AddMember("visible",false,a);
            visibility.AddMember(rapidjson::StringRef(off?"characters":"background"),display,a);
            item.AddMember("visibility",visibility,a);
        }
        Value transition(rapidjson::kObjectType);
        arg(transition,"fade","Arg6"); arg(transition,"wait_type","WaitType");
        if(!transition.ObjectEmpty()) item.AddMember("transition",transition,a);
        states.PushBack(item,a);
    }
    root.AddMember("states",states,a);
    return root;
}

}  // namespace

std::string StageBytes(const SceneStage& stage) {
    rapidjson::Document doc;
    auto data=StageJson(stage,doc.GetAllocator());
    rapidjson::StringBuffer buf; rapidjson::Writer<rapidjson::StringBuffer> writer(buf);
    if (!data.Accept(writer)) return {};
    return {buf.GetString(),buf.GetSize()};
}

std::string SourceBytes(const std::vector<SceneItem>& items,const std::vector<ProtectedToken>& protect) {
    rapidjson::Document doc(rapidjson::kObjectType), scratch;
    auto& a=doc.GetAllocator();
    RequestBuildContext index;
    rapidjson::Value rows(rapidjson::kArrayType), tokens, discard;
    for(const auto& item:items) {
        auto entry=EncodeSceneItem(item,a,scratch.GetAllocator(),index);
        rows.PushBack(entry.local,a);
    }
    AddProtectArrays(protect,&tokens,&discard,a,scratch.GetAllocator());
    doc.AddMember("scene_items",rows,a).AddMember("protect",tokens,a);
    rapidjson::StringBuffer buf; rapidjson::Writer<rapidjson::StringBuffer> writer(buf);
    if(!doc.Accept(writer)) return {};
    return {buf.GetString(),buf.GetSize()};
}

bool EncodeCapturedScene(
    const Scene& scene,
    std::string* scene_json,
    TranslationRequest* request,
    std::string* error) {
    if (error) {
        error->clear();
    }
    if (!scene_json || !request) {
        return Fail(error, "output is null");
    }
    if (!scene_identity::IsValid(scene.scene)) {
        return Fail(error, "scene name is invalid or contains a file suffix");
    }
    if (scene.raw_lang.empty()) {
        return Fail(error, "raw language is empty");
    }
    if (scene.target_lang.empty()) {
        return Fail(error, "target language is empty");
    }
    if (g_runtime_config.game_version.empty()) {
        return Fail(error, "game version is empty");
    }

    rapidjson::Document local;
    rapidjson::Document api;
    local.SetObject();
    api.SetObject();
    Allocator& local_allocator = local.GetAllocator();
    Allocator& api_allocator = api.GetAllocator();

    AddSharedMember(
        &local,
        &api,
        "scene",
        JsonString(scene.scene, local_allocator));
    if (!scene.quest_label.empty()) {
        local.AddMember("quest_label", JsonString(scene.quest_label, local_allocator), local_allocator);
    }
    if (!scene.stage.labels.empty()) local.AddMember("stage",StageJson(scene.stage,local_allocator),local_allocator);
    AddSharedMember(
        &local,
        &api,
        "game_version",
        JsonString(g_runtime_config.game_version, local_allocator));
    AddSharedMember(
        &local,
        &api,
        "raw_lang",
        JsonString(scene.raw_lang, local_allocator));

    rapidjson::Value translated(rapidjson::kObjectType);
    rapidjson::Value translated_language = JsonString(
        scene.target_lang,
        local_allocator);
    translated.AddMember(translated_language, false, local_allocator);
    local.AddMember("translated", translated, local_allocator);

    AddSharedMember(
        &local,
        &api,
        "character",
        JsonCharacter(scene.character, local_allocator));
    AddSharedMember(
        &local,
        &api,
        "mentioned_characters",
        JsonMentionedCharacters(scene.mentioned_characters, local_allocator));
    AddSharedMember(
        &local,
        &api,
        "game_terms",
        JsonGameTerms(scene.game_terms, local_allocator));

    rapidjson::Value local_protect;
    rapidjson::Value api_protect;
    AddProtectArrays(
        scene.protect,
        &local_protect,
        &api_protect,
        local_allocator,
        api_allocator);
    local.AddMember("protect", local_protect, local_allocator);
    api.AddMember("protect", api_protect, api_allocator);
    api.AddMember(
        "target_lang",
        JsonString(scene.target_lang, api_allocator),
        api_allocator);

    RequestBuildContext context;
    rapidjson::Value local_items(rapidjson::kArrayType);
    rapidjson::Value api_items(rapidjson::kArrayType);
    for (const SceneItem& item : scene.scene_items) {
        EncodedSceneItem encoded = EncodeSceneItem(
            item,
            local_allocator,
            api_allocator,
            context);
        local_items.PushBack(encoded.local, local_allocator);
        api_items.PushBack(encoded.request, api_allocator);
    }
    if (!context.valid || context.seq_to_order.empty()) {
        return Fail(error, "scene has no valid translatable items");
    }

    rapidjson::Value seq_to_order(rapidjson::kArrayType);
    int seq = 1;
    for (const OrderKey& order : context.seq_to_order) {
        rapidjson::Value item(rapidjson::kObjectType);
        item.AddMember("seq", seq++, local_allocator);
        item.AddMember(
            "order",
            JsonOrderKey(order, local_allocator),
            local_allocator);
        seq_to_order.PushBack(item, local_allocator);
    }

    local.AddMember(
        "provider",
        rapidjson::Value(rapidjson::kObjectType),
        local_allocator);
    local.AddMember(
        "model",
        rapidjson::Value(rapidjson::kObjectType),
        local_allocator);
    local.AddMember("seq_to_order", seq_to_order, local_allocator);
    local.AddMember(
        "summary",
        rapidjson::Value(rapidjson::kObjectType),
        local_allocator);
    local.AddMember("scene_items", local_items, local_allocator);
    api.AddMember("scene_items", api_items, api_allocator);

    rapidjson::StringBuffer local_buffer;
    rapidjson::PrettyWriter<rapidjson::StringBuffer> local_writer(local_buffer);
    if (!local.Accept(local_writer)) {
        return Fail(error, "failed to serialize local scene");
    }

    rapidjson::StringBuffer request_buffer;
    rapidjson::Writer<rapidjson::StringBuffer> request_writer(request_buffer);
    if (!api.Accept(request_writer)) {
        return Fail(error, "failed to serialize translation request");
    }

    std::string normalized_request;
    if (!NormalizeRequestPayload(
            std::string(request_buffer.GetString(), request_buffer.GetSize()),
            &normalized_request,
            error)) {
        return false;
    }

    TranslationRequest output;
    output.scene_name = scene.scene;
    output.target_lang = scene.target_lang;
    output.payload_json = std::move(normalized_request);
    output.seq_to_order = std::move(context.seq_to_order);

    scene_json->assign(local_buffer.GetString(), local_buffer.GetSize());
    *request = std::move(output);
    return true;
}

namespace {

using ProtectedTokenMap = std::unordered_map<
    OrderKey,
    std::vector<ProtectedToken>,
    OrderKeyHash>;

bool UpsertLanguageString(
    rapidjson::Value& object,
    const std::string& target_lang,
    const std::string& value,
    Allocator& allocator) {
    if (!object.IsObject() || target_lang.empty() || value.empty()) {
        return false;
    }
    auto member = object.FindMember(target_lang.c_str());
    if (member == object.MemberEnd()) {
        object.AddMember(
            JsonString(target_lang, allocator),
            JsonString(value, allocator),
            allocator);
    } else {
        // A synced or manually edited Scene may already contain this language.
        // Accept the same result, but never replace a newer translation on replay.
        return member->value.IsString()
            && std::string(member->value.GetString(), member->value.GetStringLength()) == value;
    }
    return true;
}

bool UpsertLanguageBoolean(
    rapidjson::Value& object,
    const std::string& target_lang,
    bool value,
    Allocator& allocator) {
    if (!object.IsObject() || target_lang.empty()) {
        return false;
    }
    auto member = object.FindMember(target_lang.c_str());
    if (member == object.MemberEnd()) {
        rapidjson::Value language = JsonString(target_lang, allocator);
        object.AddMember(language, value, allocator);
    } else {
        member->value.SetBool(value);
    }
    return true;
}

bool ParseProtectedTokens(
    const rapidjson::Value& value,
    const std::unordered_set<OrderKey, OrderKeyHash>& mapped_orders,
    ProtectedTokenMap* output) {
    if (!value.IsArray() || !output) {
        return false;
    }
    output->clear();
    for (const rapidjson::Value& item : value.GetArray()) {
        if (!item.IsObject()) {
            return false;
        }
        const auto order = item.FindMember("order");
        const auto label = item.FindMember("label");
        const auto origin = item.FindMember("origin");
        OrderKey parsed_order;
        if (order == item.MemberEnd()
            || !internal::ParseOrderKey(order->value, &parsed_order)
            || mapped_orders.find(parsed_order) == mapped_orders.end()
            || label == item.MemberEnd() || !label->value.IsString()
            || label->value.GetStringLength() == 0
            || origin == item.MemberEnd() || !origin->value.IsString()
            || origin->value.GetStringLength() == 0) {
            return false;
        }
        ProtectedToken token;
        token.order = parsed_order;
        token.label.assign(
            label->value.GetString(),
            label->value.GetStringLength());
        token.origin.assign(
            origin->value.GetString(),
            origin->value.GetStringLength());
        (*output)[parsed_order].push_back(std::move(token));
    }
    return true;
}

bool RestoreProtectedTokens(
    std::string* translation,
    const std::vector<ProtectedToken>& tokens) {
    if (!translation) {
        return false;
    }
    for (const ProtectedToken& token : tokens) {
        const size_t position = translation->find(token.label);
        if (position == std::string::npos) {
            return false;
        }
        translation->replace(position, token.label.size(), token.origin);
    }
    return true;
}

bool ApplyTranslationsToItems(
    rapidjson::Value& items,
    const std::vector<OrderKey>& mapping,
    const TranslationResult& result,
    ProtectedTokenMap* protected_tokens,
    size_t* cursor,
    Allocator& allocator,
    std::string* error);

bool ApplyTranslationToText(
    rapidjson::Value& item,
    const std::vector<OrderKey>& mapping,
    const TranslationResult& result,
    ProtectedTokenMap* protected_tokens,
    size_t* cursor,
    Allocator& allocator,
    std::string* error) {
    if (!item.IsObject() || !protected_tokens || !cursor
        || *cursor >= mapping.size()
        || *cursor >= result.translations.size()) {
        return Fail(error, "translation cursor is out of range");
    }
    const auto order = item.FindMember("order");
    auto translations = item.FindMember("translations");
    OrderKey parsed_order;
    if (order == item.MemberEnd()
        || !internal::ParseOrderKey(order->value, &parsed_order)
        || !(parsed_order == mapping[*cursor])
        || translations == item.MemberEnd()
        || !internal::IsLanguageStringMap(translations->value)) {
        return Fail(error, "text item does not match translation mapping");
    }

    std::string translated = result.translations[*cursor];
    if (translated.empty()) {
        return Fail(error, "translation text is empty");
    }
    const auto tokens = protected_tokens->find(parsed_order);
    if (tokens != protected_tokens->end()) {
        if (!RestoreProtectedTokens(&translated, tokens->second)) {
            return Fail(error, "translation damaged a protected token");
        }
        protected_tokens->erase(tokens);
    }
    if (!UpsertLanguageString(
            translations->value,
            result.target_lang,
            translated,
            allocator)) {
        return Fail(error, "could not update text translations");
    }
    ++(*cursor);
    return true;
}

bool ValidateStructuralOrder(const rapidjson::Value& item) {
    if (!item.IsObject()) {
        return false;
    }
    const auto order = item.FindMember("order");
    OrderKey parsed;
    return order != item.MemberEnd()
        && internal::ParseOrderKey(order->value, &parsed);
}

bool ApplyTranslationsToItems(
    rapidjson::Value& items,
    const std::vector<OrderKey>& mapping,
    const TranslationResult& result,
    ProtectedTokenMap* protected_tokens,
    size_t* cursor,
    Allocator& allocator,
    std::string* error) {
    if (!items.IsArray()) {
        return Fail(error, "scene_items is not an array");
    }
    for (rapidjson::Value& item : items.GetArray()) {
        if (!item.IsObject()) {
            return Fail(error, "scene item is not an object");
        }
        const auto type = item.FindMember("type");
        if (type == item.MemberEnd() || !type->value.IsString()) {
            return Fail(error, "scene item type is invalid");
        }
        const std::string type_name(
            type->value.GetString(),
            type->value.GetStringLength());
        if (type_name == "text") {
            if (!ApplyTranslationToText(
                    item,
                    mapping,
                    result,
                    protected_tokens,
                    cursor,
                    allocator,
                    error)) {
                return false;
            }
            continue;
        }
        if (!ValidateStructuralOrder(item)) {
            return Fail(error, "structural item OrderKey is invalid");
        }
        if (type_name == "if") {
            auto following = item.FindMember("following_text");
            if (following == item.MemberEnd()
                || !ApplyTranslationsToItems(
                    following->value,
                    mapping,
                    result,
                    protected_tokens,
                    cursor,
                    allocator,
                    error)) {
                return false;
            }
            continue;
        }
        if (type_name != "choice") {
            return Fail(error, "scene item type is unknown");
        }

        auto branches = item.FindMember("branches");
        if (branches == item.MemberEnd() || !branches->value.IsArray()) {
            return Fail(error, "choice branches are invalid");
        }
        for (rapidjson::Value& branch : branches->value.GetArray()) {
            if (!branch.IsObject()) {
                return Fail(error, "choice branch is invalid");
            }
            auto options = branch.FindMember("options");
            auto following = branch.FindMember("following_text");
            if (options == branch.MemberEnd() || !options->value.IsArray()
                || following == branch.MemberEnd()
                || !following->value.IsArray()) {
                return Fail(error, "choice branch content is invalid");
            }
            for (rapidjson::Value& option : options->value.GetArray()) {
                if (!option.IsObject()) {
                    return Fail(error, "choice option is invalid");
                }
                const auto option_type = option.FindMember("type");
                if (option_type == option.MemberEnd()
                    || !option_type->value.IsString()
                    || std::string(
                        option_type->value.GetString(),
                        option_type->value.GetStringLength()) != "text"
                    || !ApplyTranslationToText(
                        option,
                        mapping,
                        result,
                        protected_tokens,
                        cursor,
                        allocator,
                        error)) {
                    if (error && error->empty()) {
                        *error = "choice option is invalid";
                    }
                    return false;
                }
            }
            if (!ApplyTranslationsToItems(
                    following->value,
                    mapping,
                    result,
                    protected_tokens,
                    cursor,
                    allocator,
                    error)) {
                return false;
            }
        }
    }
    return true;
}

}  // namespace

bool ApplyTranslationToScene(
    const std::string& scene_json,
    const TranslationRequest& request,
    const TranslationResult& result,
    std::string* translated_scene_json,
    std::string* error) {
    if (error) {
        error->clear();
    }
    if (!translated_scene_json || scene_json.empty()
        || !scene_identity::IsValid(request.scene_name)
        || request.target_lang.empty()
        || result.provider.empty() || result.model.empty()
        || result.target_lang.empty() || result.summary.empty()
        || request.target_lang != result.target_lang
        || request.seq_to_order.empty()
        || request.seq_to_order.size() != result.translations.size()) {
        return Fail(error, "translation result or request is incomplete");
    }

    rapidjson::Document scene;
    scene.Parse(scene_json.data(), scene_json.size());
    if (scene.HasParseError() || !scene.IsObject()) {
        return Fail(error, "scene JSON is invalid");
    }
    const auto scene_name = scene.FindMember("scene");
    if (scene_name == scene.MemberEnd() || !scene_name->value.IsString()
        || !scene_identity::IsValid(std::string(
            scene_name->value.GetString(),
            scene_name->value.GetStringLength()))
        || std::string(
            scene_name->value.GetString(),
            scene_name->value.GetStringLength()) != request.scene_name) {
        return Fail(error, "scene identity does not match translation request");
    }

    auto translated = scene.FindMember("translated");
    auto provider = scene.FindMember("provider");
    auto model = scene.FindMember("model");
    auto summary = scene.FindMember("summary");
    auto seq_to_order = scene.FindMember("seq_to_order");
    auto scene_items = scene.FindMember("scene_items");
    auto protect = scene.FindMember("protect");
    if (translated == scene.MemberEnd()
        || !internal::IsLanguageBooleanMap(translated->value)
        || provider == scene.MemberEnd()
        || !internal::IsLanguageStringMap(provider->value)
        || model == scene.MemberEnd()
        || !internal::IsLanguageStringMap(model->value)
        || summary == scene.MemberEnd()
        || !internal::IsLanguageStringMap(summary->value)
        || seq_to_order == scene.MemberEnd()
        || scene_items == scene.MemberEnd()
        || !scene_items->value.IsArray()
        || protect == scene.MemberEnd()
        || !protect->value.IsArray()) {
        return Fail(error, "scene translation metadata is invalid");
    }

    std::vector<OrderKey> file_mapping;
    if (!internal::ParseSeqToOrder(seq_to_order->value, &file_mapping)
        || file_mapping != request.seq_to_order) {
        return Fail(error, "scene seq_to_order does not match request");
    }
    std::unordered_set<OrderKey, OrderKeyHash> mapped_orders(
        file_mapping.begin(),
        file_mapping.end());
    ProtectedTokenMap protected_tokens;
    if (!ParseProtectedTokens(
            protect->value,
            mapped_orders,
            &protected_tokens)) {
        return Fail(error, "scene protect metadata is invalid");
    }

    size_t cursor = 0;
    Allocator& allocator = scene.GetAllocator();
    if (!ApplyTranslationsToItems(
            scene_items->value,
            file_mapping,
            result,
            &protected_tokens,
            &cursor,
            allocator,
            error)
        || cursor != result.translations.size()
        || !protected_tokens.empty()) {
        if (error && error->empty()) {
            *error = "translation traversal did not consume all mapped content";
        }
        return false;
    }

    if (!UpsertLanguageString(
            summary->value,
            result.target_lang,
            result.summary,
            allocator)
        || !UpsertLanguageString(
            provider->value,
            result.target_lang,
            result.provider,
            allocator)
        || !UpsertLanguageString(
            model->value,
            result.target_lang,
            result.model,
            allocator)
        || !UpsertLanguageBoolean(
            translated->value,
            result.target_lang,
            true,
            allocator)) {
        return Fail(error, "could not update scene language metadata");
    }
    scene.RemoveMember("target_lang");

    rapidjson::StringBuffer buffer;
    rapidjson::PrettyWriter<rapidjson::StringBuffer> writer(buffer);
    if (!scene.Accept(writer)) {
        return Fail(error, "could not serialize translated scene");
    }
    translated_scene_json->assign(buffer.GetString(), buffer.GetSize());
    return true;
}

}  // namespace het::translation::document_codec

std::string EncodeContinuationSource(const ScenarioParseResult& parsed) {
    return EncodeContinuationSource(parsed.scene_items,parsed.protect);
}
std::string EncodeContinuationSource(const std::vector<SceneItem>& items,const std::vector<ProtectedToken>& protect) {
    return het::translation::document_codec::SourceBytes(items,protect);
}

std::string EncodeSceneStage(const SceneStage& stage) {
    return het::translation::document_codec::StageBytes(stage);
}
