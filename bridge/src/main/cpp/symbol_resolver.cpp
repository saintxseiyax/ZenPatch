// SPDX-License-Identifier: GPL-3.0-only
/**
 * symbol_resolver.cpp – Symbol resolution for libart.so using dl_iterate_phdr + ELF parsing.
 *
 * LSPlant's InitInfo requires two callbacks:
 *   art_symbol_resolver        – exact symbol name lookup
 *   art_symbol_prefix_resolver – first symbol whose name starts with a given prefix
 *
 * Both are implemented here without dlopen/dlsym, which are blocked for non-public symbols
 * on Android 7+ (ANDROID_DLEXT_FORCE_LOAD / private namespace restrictions).
 *
 * Approach
 * --------
 *  1. dl_iterate_phdr()   – locate libart.so and record its load-bias (base address).
 *  2. Walk PT_LOAD segments to find the ELF header in memory, then parse PT_DYNAMIC.
 *  3. From PT_DYNAMIC extract DT_SYMTAB, DT_STRTAB, DT_SYMENT, DT_HASH / DT_GNU_HASH.
 *  4. Build an in-memory symbol map (name → absolute address) on first call.
 *  5. Provide resolve_art_symbol() and resolve_art_symbol_prefix() to callers.
 *
 * Architecture
 * ------------
 * Handles both arm64-v8a (ELF64) and armeabi-v7a / x86_64 (ELF32/ELF64).
 * Uses the correct Elf32_*/Elf64_* types via the __LP64__ preprocessor guard.
 */

#include <jni.h>
#include <android/log.h>
#include <link.h>
#include <elf.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include <string>
#include <string_view>
#include <unordered_map>
#include <mutex>
#include <vector>
#include <algorithm>
#include <cstdint>
#include <cstdio>

#define LOG_TAG "ZenPatch_SymbolResolver"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

// ---------------------------------------------------------------------------
// ELF type aliases – pick 32 or 64 depending on compilation ABI
// ---------------------------------------------------------------------------
#if defined(__LP64__)
using Elf_Ehdr  = Elf64_Ehdr;
using Elf_Phdr  = Elf64_Phdr;
using Elf_Dyn   = Elf64_Dyn;
using Elf_Sym   = Elf64_Sym;
using Elf_Addr  = Elf64_Addr;
using Elf_Half  = Elf64_Half;
using Elf_Word  = Elf64_Word;
using Elf_Xword = Elf64_Xword;
#else
using Elf_Ehdr  = Elf32_Ehdr;
using Elf_Phdr  = Elf32_Phdr;
using Elf_Dyn   = Elf32_Dyn;
using Elf_Sym   = Elf32_Sym;
using Elf_Addr  = Elf32_Addr;
using Elf_Half  = Elf32_Half;
using Elf_Word  = Elf32_Word;
using Elf_Xword = Elf32_Word;  // Elf32 has no Xword; use Word
#endif

// ---------------------------------------------------------------------------
// Internal state
// ---------------------------------------------------------------------------
namespace {

struct LibArtInfo {
    uintptr_t   load_bias = 0;   ///< ELF load bias (== base for PIE)
    bool        found     = false;
    // First PT_LOAD vaddr, needed to compute the ELF header offset.
    uintptr_t   first_load_vaddr = 0;
};

struct SymbolCache {
    bool                                        built = false;
    std::unordered_map<std::string, uintptr_t>  map;
    std::vector<std::pair<std::string, uintptr_t>> sorted; ///< sorted for prefix search
};

static std::mutex       g_mutex;
static LibArtInfo       g_art_info;
static SymbolCache      g_cache;

// ---------------------------------------------------------------------------
// dl_iterate_phdr callback – find libart.so
// ---------------------------------------------------------------------------
static int phdr_callback(struct dl_phdr_info* info, size_t /*size*/, void* data) {
    auto* ai = static_cast<LibArtInfo*>(data);
    if (!info->dlpi_name || info->dlpi_name[0] == '\0') return 0;

    // Match "libart.so" anywhere in the path (handles /apex/… and /system/lib… paths)
    const char* basename = strrchr(info->dlpi_name, '/');
    basename = basename ? basename + 1 : info->dlpi_name;

    if (strcmp(basename, "libart.so") != 0) return 0;

    ai->load_bias = static_cast<uintptr_t>(info->dlpi_addr);
    ai->found     = true;

    // Record the first PT_LOAD virtual address so we can locate the ELF header.
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_LOAD) {
            ai->first_load_vaddr = info->dlpi_phdr[i].p_vaddr;
            break;
        }
    }

    LOGI("Found libart.so: load_bias=0x%" PRIxPTR " first_load_vaddr=0x%" PRIxPTR,
         ai->load_bias, ai->first_load_vaddr);
    return 1; // stop iteration
}

// ---------------------------------------------------------------------------
// Parse libart.so's ELF dynamic symbol table and populate g_cache.map
// ---------------------------------------------------------------------------
static bool build_symbol_cache(const LibArtInfo& ai) {
    // The ELF header lives at (load_bias + first_load_vaddr) for PIE libs where
    // first_load_vaddr == 0, which is the common case.  For non-PIE the header
    // is at the absolute base_addr stored in load_bias directly.
    const auto* ehdr = reinterpret_cast<const Elf_Ehdr*>(
        ai.load_bias + ai.first_load_vaddr);

    // Basic ELF magic check
    if (ehdr->e_ident[EI_MAG0] != ELFMAG0 ||
        ehdr->e_ident[EI_MAG1] != ELFMAG1 ||
        ehdr->e_ident[EI_MAG2] != ELFMAG2 ||
        ehdr->e_ident[EI_MAG3] != ELFMAG3) {
        LOGE("ELF magic mismatch at 0x%" PRIxPTR, reinterpret_cast<uintptr_t>(ehdr));
        return false;
    }

    // Walk program headers to find PT_DYNAMIC
    const auto* phdrs = reinterpret_cast<const Elf_Phdr*>(
        reinterpret_cast<uintptr_t>(ehdr) + ehdr->e_phoff);

    const Elf_Dyn* dynamic = nullptr;
    for (Elf_Half i = 0; i < ehdr->e_phnum; ++i) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const Elf_Dyn*>(ai.load_bias + phdrs[i].p_vaddr);
            break;
        }
    }
    if (!dynamic) {
        LOGE("PT_DYNAMIC segment not found in libart.so");
        return false;
    }

    // Extract DT_SYMTAB, DT_STRTAB, DT_STRSZ, DT_SYMENT, DT_HASH / DT_GNU_HASH
    const Elf_Sym*  symtab   = nullptr;
    const char*     strtab   = nullptr;
    Elf_Xword       strsz    = 0;
    Elf_Xword       syment   = sizeof(Elf_Sym);
    // GNU hash fields
    const uint32_t* gnu_hash = nullptr;
    // SysV hash
    const Elf_Word* sysv_hash = nullptr;
    uint32_t        sysv_nbucket = 0;
    uint32_t        sysv_nchain  = 0;

    for (const Elf_Dyn* dyn = dynamic; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const Elf_Sym*>(ai.load_bias + dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(ai.load_bias + dyn->d_un.d_ptr);
                break;
            case DT_STRSZ:
                strsz  = dyn->d_un.d_val;
                break;
            case DT_SYMENT:
                syment = dyn->d_un.d_val;
                break;
            case DT_GNU_HASH:
                gnu_hash = reinterpret_cast<const uint32_t*>(ai.load_bias + dyn->d_un.d_ptr);
                break;
            case DT_HASH:
                sysv_hash = reinterpret_cast<const Elf_Word*>(ai.load_bias + dyn->d_un.d_ptr);
                break;
            default:
                break;
        }
    }

    if (!symtab || !strtab) {
        LOGE("DT_SYMTAB or DT_STRTAB not found in libart.so PT_DYNAMIC");
        return false;
    }

    // Determine the number of symbols.
    // Priority: GNU hash → SysV hash → fallback heuristic.
    uint32_t sym_count = 0;

    if (gnu_hash) {
        // GNU hash layout: nbuckets | symoffset | bloom_size | bloom_shift | bloom[] | buckets[] | chains[]
        const uint32_t nbuckets   = gnu_hash[0];
        const uint32_t symoffset  = gnu_hash[1];
        const uint32_t bloom_size = gnu_hash[2];
        // bloom words follow header[4], then nbuckets bucket entries, then chains
        const uint32_t* buckets = gnu_hash + 4 + bloom_size * (sizeof(Elf_Addr) / sizeof(uint32_t));
        const uint32_t* chains  = buckets + nbuckets;

        // Find the highest bucket value to get last symbol index
        uint32_t last_sym = symoffset;
        for (uint32_t i = 0; i < nbuckets; ++i) {
            if (buckets[i] > last_sym) last_sym = buckets[i];
        }
        // Walk the chain from last_sym until the LSB (end marker) is set
        if (last_sym >= symoffset) {
            uint32_t chain_idx = last_sym - symoffset;
            while ((chains[chain_idx] & 1) == 0) {
                ++last_sym;
                ++chain_idx;
            }
            sym_count = last_sym + 1;
        }
    } else if (sysv_hash) {
        sysv_nbucket = sysv_hash[0];
        sysv_nchain  = sysv_hash[1];
        sym_count    = sysv_nchain; // nchain == number of symbol table entries
    } else {
        // Heuristic: compute from strtab start – symtab start, divided by entry size.
        // This is imprecise but beats nothing.
        uintptr_t strtab_addr = reinterpret_cast<uintptr_t>(strtab);
        uintptr_t symtab_addr = reinterpret_cast<uintptr_t>(symtab);
        if (strtab_addr > symtab_addr && syment > 0) {
            sym_count = static_cast<uint32_t>((strtab_addr - symtab_addr) / syment);
        }
        if (sym_count == 0 || sym_count > 500000u) sym_count = 200000u; // safety cap
    }

    LOGI("Building symbol cache: sym_count=%u", sym_count);
    g_cache.map.reserve(sym_count);

    uint32_t added = 0;
    for (uint32_t i = 0; i < sym_count; ++i) {
        const Elf_Sym& sym = symtab[i];
        if (sym.st_name == 0 || sym.st_value == 0) continue;
        if (sym.st_name >= strsz && strsz != 0) continue;

        const char* name = strtab + sym.st_name;
        if (name[0] == '\0') continue;

        uintptr_t addr = ai.load_bias + sym.st_value;
        g_cache.map.emplace(name, addr);
        ++added;
    }

    // Build sorted list for prefix search
    g_cache.sorted.reserve(g_cache.map.size());
    for (const auto& kv : g_cache.map) {
        g_cache.sorted.emplace_back(kv.first, kv.second);
    }
    std::sort(g_cache.sorted.begin(), g_cache.sorted.end(),
              [](const auto& a, const auto& b){ return a.first < b.first; });

    LOGI("Symbol cache built: %u symbols added", added);
    g_cache.built = true;
    return true;
}

// ---------------------------------------------------------------------------
// Ensure cache is initialised (call once, guarded by mutex)
// ---------------------------------------------------------------------------
static bool ensure_cache() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_cache.built) return true;

    if (!g_art_info.found) {
        dl_iterate_phdr(phdr_callback, &g_art_info);
        if (!g_art_info.found) {
            LOGE("libart.so not found in process memory");
            return false;
        }
    }
    return build_symbol_cache(g_art_info);
}

} // namespace

// ===========================================================================
// Public API used by hook_engine.cpp via InitInfo lambdas
// ===========================================================================

/**
 * Resolve an exact symbol name in libart.so.
 * Returns the absolute address or nullptr if not found.
 */
extern "C" void* resolve_art_symbol(std::string_view symbol_name) {
    if (!ensure_cache()) return nullptr;
    if (symbol_name.empty()) return nullptr;

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_cache.map.find(std::string(symbol_name));
    if (it == g_cache.map.end()) {
        LOGD("Symbol not found: %.*s", static_cast<int>(symbol_name.size()), symbol_name.data());
        return nullptr;
    }
    LOGD("Resolved %.*s → 0x%" PRIxPTR,
         static_cast<int>(symbol_name.size()), symbol_name.data(), it->second);
    return reinterpret_cast<void*>(it->second);
}

/**
 * Resolve the first symbol in libart.so whose name starts with symbol_prefix.
 * Returns the absolute address or nullptr if not found.
 */
extern "C" void* resolve_art_symbol_prefix(std::string_view symbol_prefix) {
    if (!ensure_cache()) return nullptr;
    if (symbol_prefix.empty()) return nullptr;

    std::lock_guard<std::mutex> lock(g_mutex);

    // Binary search for the first entry >= prefix, then check it starts with prefix.
    std::string prefix_str(symbol_prefix);
    auto it = std::lower_bound(
        g_cache.sorted.begin(), g_cache.sorted.end(),
        prefix_str,
        [](const auto& entry, const std::string& val) { return entry.first < val; });

    while (it != g_cache.sorted.end()) {
        if (it->first.compare(0, prefix_str.size(), prefix_str) == 0) {
            LOGD("Prefix resolved %.*s → %s @ 0x%" PRIxPTR,
                 static_cast<int>(symbol_prefix.size()), symbol_prefix.data(),
                 it->first.c_str(), it->second);
            return reinterpret_cast<void*>(it->second);
        }
        break; // sorted, so once prefix doesn't match we're done
    }

    LOGD("Prefix not found: %.*s", static_cast<int>(symbol_prefix.size()), symbol_prefix.data());
    return nullptr;
}

/**
 * Return the base load address of libart.so (used for diagnostics).
 */
extern "C" uintptr_t resolve_libart_base() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_art_info.found) {
        dl_iterate_phdr(phdr_callback, &g_art_info);
    }
    return g_art_info.found ? g_art_info.load_bias : 0;
}
