#include "housamo.hpp"
#include <cstdint>
#include <cstddef>
#include <string>

bool valid_ptr(void* ptr) {return ptr != nullptr && reinterpret_cast<uintptr_t>(ptr) > 0x100000; }

void* read_ptr(void* base, size_t offset) { 
    if (!valid_ptr(base)) return nullptr;
    return *reinterpret_cast<void**>(reinterpret_cast<uint8_t*>(base) + offset); 
}

int read_int(void* base, size_t offset) { 
    if (!valid_ptr(base)) return -1;
    return *reinterpret_cast<int*>(reinterpret_cast<uint8_t*>(base) + offset); 
}

static void append_utf8(std::string& out, uint32_t cp) {
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

static std::string utf16to8(const uint16_t* input, size_t length) {
    std::string out;
    out.reserve(length * 3);
    
    for (size_t i = 0; i < length; i++) {
        uint32_t cp = input[i];

        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 < length) {
                uint32_t low = input[i + 1];
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                    i++;
                } else { cp = 0xFFFD; }
            } else { cp = 0xFFFD; }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) { cp = 0xFFFD; }

        append_utf8(out, cp);
    }

    return out;
}

std::string read_il2cpp_string(void* strptr) {
    if (!valid_ptr(strptr)) {
        return "";
    }

    const auto& il2cppstring = g_runtime_config.layout.il2cpp_string;

    int length = *(int*)((uint8_t*)strptr + il2cppstring.length);
    if (length <= 0 || length > 8192) {
        return "";
    }

    auto chars = reinterpret_cast<const uint16_t*>(
    reinterpret_cast<uint8_t*>(strptr) + il2cppstring.chars
    );

    return utf16to8(chars, static_cast<size_t>(length));
}
