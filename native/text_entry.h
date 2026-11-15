#pragma once

namespace TextEntry {
struct Key { UInt32 session, code; };
std::mutex keyMutex;
std::vector<Key> keys;
std::atomic<UInt32> activeSession{0};
UInt32 nextSession = 0;
Menu* previousMenu = nullptr;
void* previousConsole = nullptr;
std::atomic<bool> openConsole{false};
std::string published = "STATE TEXTENTRY CLOSED\n";

void ClearKeys() {
    std::lock_guard<std::mutex> lock(keyMutex);
    keys.clear();
}
void Reset(bool discardOpen = true) {
    if (discardOpen) openConsole.store(false);
    ClearKeys();
    previousMenu = nullptr;
    previousConsole = nullptr;
    activeSession.store(0);
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    published = "STATE TEXTENTRY CLOSED\n";
}
void Enqueue(UInt32 session, UInt32 code) {
    if (!session || session != activeSession.load()) return;
    if (!((code >= 32 && code <= 126) || code <= 8)) return;
    std::lock_guard<std::mutex> lock(keyMutex);
    if (keys.size() < 128) keys.push_back({session, code});
}
Menu* CurrentMenu() {
    if (g_loadingSave.load() || !Activity::MenuVisible(0x41B)) return nullptr;
    auto* menu = *reinterpret_cast<Menu**>(0x011DAEC4);
    return menu && menu->GetID() == 0x41B ? menu : nullptr;
}
std::string RawText(Menu* menu) {
    // TextEditMenu +0x34 begins the editor's unformatted BSString, before cursor insertion.
    auto* text = *reinterpret_cast<const char**>(reinterpret_cast<UInt8*>(menu) + 0x34);
    return text ? std::string(text, strnlen_s(text, 1023)) : std::string();
}
void NativeKey(Menu* menu, UInt32 code) {
    // The SDK declares a char argument; the actual TextEditMenu handler accepts
    // a full 32-bit code, including the engine's extended editing keys.
    ThisStdCall<bool>(0x007E6620, menu, code);
}
void* Console(bool create = false) {
    return reinterpret_cast<void* (__cdecl*)(bool)>(0x0071B160)(create);
}
bool ConsoleVisible(void* console) {
    auto* interfaceManager = *reinterpret_cast<void**>(0x011D8A80);
    // Console Idle accepts keys only while the interface is in menu mode.
    return console && interfaceManager
            && *reinterpret_cast<UInt8*>(static_cast<UInt8*>(console) + 0x38) != 0
            && ThisStdCall<bool>(0x007023A0, interfaceManager);
}
std::string ConsoleText(void* console) {
    // The console editor displays its input through DebugText at its configured X/Y.
    void* debug = reinterpret_cast<void* (__cdecl*)(bool)>(0x00A0D9E0)(false);
    if (!debug) return "|";
    auto* layout = static_cast<UInt8*>(console);
    char text[2048]{};
    ThisStdCall<bool>(0x00A103E0, debug, text,
            static_cast<float>(*reinterpret_cast<int*>(layout + 0x30)),
            static_cast<float>(*reinterpret_cast<int*>(layout + 0x34)), 1);
    return std::string(text, strnlen_s(text, 2047));
}
void ConsoleKey(void* console, UInt32 code) {
    ThisStdCall<bool>(0x0071B210, console, code);
}
bool UpdateConsole() {
    if (g_loadingSave.load()) return false;
    if (openConsole.exchange(false) && g_gameReady.load()
            && g_activityBlock.load() == ActivityBlock::None
            && !Activity::MenuVisible(0x41B) && !Activity::MenuVisible(0x420)) {
        void* console = Console(true);
        if (console && !ConsoleVisible(console)) {
            // The full interface entry point pushes console menu mode as well as
            // toggling visibility; SetVisible alone leaves Idle rejecting keys.
            reinterpret_cast<void (__cdecl*)()>(0x00703E10)();
        }
    }
    void* console = Console();
    if (!ConsoleVisible(console)) return false;
    if (console != previousConsole) {
        ClearKeys();
        previousConsole = console;
        previousMenu = nullptr;
        if (++nextSession == 0) ++nextSession;
        activeSession.store(nextSession);
    }
    std::vector<Key> pending;
    { std::lock_guard<std::mutex> lock(keyMutex); pending.swap(keys); }
    for (const Key& key : pending) {
        if (key.session != activeSession.load() || !ConsoleVisible(console)
                || g_loadingSave.load()) break;
        if (key.code >= 32 && key.code <= 126) {
            if (ConsoleText(console).size() < 1023) ConsoleKey(console, key.code);
        } else if (key.code == 8) {
            // Native history-up clamps at the oldest saved command.
            ConsoleKey(console, 0x80000003u);
        } else if (key.code == 7) {
            // Native close also removes the console's menu-stack entry.
            reinterpret_cast<void (__cdecl*)()>(0x00703DA0)();
        } else if (key.code == 4) {
            const size_t count = ConsoleText(console).size();
            ConsoleKey(console, 0x80000006u);
            for (size_t i = 0; i < count; ++i) ConsoleKey(console, 0x80000000u);
        } else if (key.code >= 1 && key.code <= 6) {
            const UInt32 codes[] = {0, 0x80000000u, 0x80000001u, 0x80000002u,
                    0, 0x80000008u, 0x80000007u};
            ConsoleKey(console, codes[key.code]);
        }
    }
    if (!ConsoleVisible(console) || g_loadingSave.load()) { Reset(false); return true; }
    std::string packet = "STATE TEXTENTRY " + std::to_string(activeSession.load())
            + " 1 " + Base64Encode("CONSOLE") + ' '
            + Base64Encode(GameTextToUtf8(ConsoleText(console).c_str())) + '\n';
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    published = std::move(packet);
    return true;
}
void Update() {
    if (UpdateConsole()) return;
    previousConsole = nullptr;
    Menu* menu = CurrentMenu();
    if (!menu) { Reset(false); return; }
    if (menu != previousMenu) {
        ClearKeys();
        previousMenu = menu;
        if (++nextSession == 0) ++nextSession;
        activeSession.store(nextSession);
    }
    std::vector<Key> pending;
    { std::lock_guard<std::mutex> lock(keyMutex); pending.swap(keys); }
    for (const Key& key : pending) {
        if (key.session != activeSession.load() || CurrentMenu() != menu) break;
        if (key.code >= 32 && key.code <= 126) {
            if (RawText(menu).size() < 255) NativeKey(menu, key.code);
        } else if (key.code == 1) NativeKey(menu, 0x80000000u); // Backspace.
        else if (key.code == 2) NativeKey(menu, 0x80000001u); // Left.
        else if (key.code == 3) NativeKey(menu, 0x80000002u); // Right.
        else if (key.code == 4) {
            const size_t count = RawText(menu).size();
            NativeKey(menu, 0x80000006u); // End, then delete through the native editor.
            for (size_t i = 0; i < count; ++i) NativeKey(menu, 0x80000000u);
        } else if (key.code == 5) NativeKey(menu, 0x80000008u); // Accept, subject to native validation.
        else if (key.code == 6) NativeKey(menu, 0x80000007u); // Forward delete.
    }
    if (CurrentMenu() != menu) { Reset(); return; }
    std::string raw = RawText(menu);
    int cursor = *reinterpret_cast<int*>(reinterpret_cast<UInt8*>(menu) + 0x44);
    cursor = std::clamp<int>(cursor, 0, static_cast<int>(raw.size()));
    // Insert the display caret before converting the game's byte encoding to UTF-8.
    raw.insert(static_cast<size_t>(cursor), "|");
    auto* title = *reinterpret_cast<Tile**>(reinterpret_cast<UInt8*>(menu) + 0x30);
    auto* accept = *reinterpret_cast<Tile**>(reinterpret_cast<UInt8*>(menu) + 0x2C);
    auto* enabled = Dialogue::Value(accept, 0xFAF);
    std::string prompt = Dialogue::Text(title);
    if (prompt.empty()) prompt = "ENTER TEXT";
    std::string packet = "STATE TEXTENTRY " + std::to_string(activeSession.load())
            + (enabled && enabled->num != 0 ? " 1 " : " 0 ")
            + Base64Encode(prompt) + ' ' + Base64Encode(GameTextToUtf8(raw.c_str())) + '\n';
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    published = std::move(packet);
}
}
