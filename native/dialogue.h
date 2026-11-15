#pragma once

namespace Dialogue {
struct Choice { Tile* tile; UInt32 token; };
std::vector<Choice> choices;
std::atomic<UInt32> pendingChoice{0};
std::atomic<UInt32> pendingSkip{0};
UInt32 speechToken = 0;
std::string published = "STATE DIALOGUE CLOSED\n";
std::string previous;
void* previousMenu = nullptr;
UInt32 nextToken = 0;

// Tile values are owned by the UI. Read them only in the game-loop callback.
Tile::Value* Value(Tile* tile, UInt32 id) {
    if (!tile || !tile->values.data || tile->values.size > 4096) return nullptr;
    for (UInt32 i = 0; i < tile->values.size; ++i) {
        auto* value = tile->values.data[i];
        if (value && value->id == id) return value;
    }
    return nullptr;
}
std::string Text(Tile* tile, int depth = 0) {
    if (!tile || depth > 6) return {};
    auto* value = Value(tile, 0xFC4);
    if (value && value->str && value->str[0])
        return GameTextToUtf8(std::string(value->str, strnlen_s(value->str, 8192)).c_str());
    auto* node = tile->childList.m_listHead.data;
    for (unsigned count = 0; node && count < 256; node = node->next, ++count) {
        std::string text = Text(node->child, depth + 1);
        if (!text.empty()) return text;
    }
    return {};
}

void Update() {
    static ULONGLONG lastReadAt = 0;
    const ULONGLONG now = GetTickCount64();
    if (now - lastReadAt < 100 && pendingChoice.load() == 0 && pendingSkip.load() == 0) return;
    lastReadAt = now;
    void* menu = g_gameReady.load(std::memory_order_relaxed)
            && g_activityBlock.load() == ActivityBlock::None
            && reinterpret_cast<UInt8*>(0x11F308F)[0x3F1]
            ? *reinterpret_cast<void**>(0x11D9510) : nullptr;
    std::vector<Tile*> tiles;
    std::vector<std::string> texts;
    std::string speaker;
    std::string spokenText;
    bool ready = false;
    bool speaking = false;
    if (menu) {
        auto* bytes = static_cast<UInt8*>(menu);
        const UInt32 state = *reinterpret_cast<UInt32*>(bytes + 0x28);
        const UInt32 goodbye = *reinterpret_cast<UInt32*>(bytes + 0x2C);
        if (state > 3) menu = nullptr;
        else {
            auto** menuTiles = reinterpret_cast<Tile**>(bytes + 0x30);
            speaker = Text(menuTiles[1]);
            ready = state == 2 && goodbye == 0;
            speaking = state == 3;
            if (speaking) spokenText = Text(menuTiles[2]);
            Tile* list = menuTiles[3];
            auto* node = list ? list->childList.m_listHead.data : nullptr;
            for (unsigned count = 0; node && count < 256; node = node->next, ++count) {
                Tile* tile = node->child;
                if (!tile || !tile->name.m_data || std::strncmp(tile->name.m_data, "topic_", 6)) continue;
                const std::string text = Text(tile);
                if (!text.empty()) tiles.push_back(tile);
            }
            std::sort(tiles.begin(), tiles.end(), [](Tile* a, Tile* b) {
                auto* av = Value(a, 0xFAC); auto* bv = Value(b, 0xFAC);
                return (av ? av->num : 0) < (bv ? bv->num : 0);
            });
            texts.clear();
            for (auto* tile : tiles) texts.push_back(Text(tile));
        }
    }
    std::string identity = menu ? Base64Encode(speaker) + (ready ? " 1\n" : " 0\n") : "CLOSED";
    identity += speaking ? "SPEECH " + Base64Encode(spokenText) + "\n" : "SILENT\n";
    for (size_t i = 0; i < tiles.size(); ++i)
        identity += std::to_string(reinterpret_cast<uintptr_t>(tiles[i])) + ":" + Base64Encode(texts[i]) + "\n";
    if (identity != previous || menu != previousMenu) {
        choices.clear();
        speechToken = 0;
        if (speaking) {
            if (++nextToken == 0) ++nextToken;
            speechToken = nextToken;
        }
        std::string packet = menu ? "STATE DIALOGUE BEGIN " + Base64Encode(speaker) + (ready ? " 1\n" : " 0\n")
                                  : "STATE DIALOGUE CLOSED\n";
        for (size_t i = 0; menu && i < tiles.size(); ++i) {
            if (++nextToken == 0) ++nextToken;
            choices.push_back({tiles[i], nextToken});
            packet += "STATE DIALOGUECHOICE " + std::to_string(nextToken) + " " + Base64Encode(texts[i]) + "\n";
        }
        if (menu) packet += "STATE DIALOGUESPEECH " + std::to_string(speechToken) + "\nSTATE DIALOGUE END\n";
        { std::lock_guard<std::mutex> lock(g_snapshotMutex); published = std::move(packet); }
        previous = std::move(identity);
        previousMenu = menu;
    }
    const UInt32 skip = pendingSkip.exchange(0);
    if (menu && speaking && skip != 0 && skip == speechToken) {
        previous.clear();
        pendingChoice.store(0);
        // Button zero advances the current spoken response through the same
        // handler used by the game's dialogue input.
        ThisStdCall<void>(0x7624F0, menu, 0, static_cast<Tile*>(nullptr));
        return;
    }
    const UInt32 selected = pendingChoice.exchange(0);
    if (!menu || !ready || !selected) return;
    for (const auto& choice : choices) {
        if (choice.token != selected) continue;
        Tile* tile = choice.tile;
        // Dispatch the existing tile through DialogMenu::HandleSelection. It owns
        // skill checks, result scripts, barter, goodbye and response playback.
        previous.clear();
        pendingChoice.store(0);
        ThisStdCall<void>(0x7624F0, menu, -1, tile);
        break;
    }
}
}
