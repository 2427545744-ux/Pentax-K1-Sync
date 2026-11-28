#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include "spektralab/spk_engine.h"
#include "io/raw_decoder.hpp"
#include "white_balance.hpp"
#include "json.hpp"
#include <libraw/libraw.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "K1SpektraJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_engine_mutex;
spk_engine* g_engine = nullptr;
std::string g_last_error;

struct SessionHolder {
    spk_session* session = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    bool is_raw = false;
    bool is_half_size = false;
    std::string camera_model;
};

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jobject create_argb8888_bitmap(JNIEnv* env, uint32_t w, uint32_t h) {
    jclass bmpCls = env->FindClass("android/graphics/Bitmap");
    jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
    if (!bmpCls || !cfgCls) return nullptr;
    jfieldID argbField = env->GetStaticFieldID(cfgCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argbCfg = env->GetStaticObjectField(cfgCls, argbField);
    jmethodID createMid = env->GetStaticMethodID(
        bmpCls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    return env->CallStaticObjectMethod(bmpCls, createMid, static_cast<jint>(w), static_cast<jint>(h), argbCfg);
}

inline uint8_t linear_to_srgb8(float v) {
    float c = std::clamp(v, 0.0f, 1.0f);
    float s = (c <= 0.0031308f) ? (12.92f * c) : (1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f);
    int q = static_cast<int>(s * 255.0f + 0.5f);
    return static_cast<uint8_t>(std::clamp(q, 0, 255));
}

inline float srgb8_to_linear(uint8_t u) {
    const float s = static_cast<float>(u) * (1.0f / 255.0f);
    return (s <= 0.04045f) ? (s / 12.92f) : std::pow((s + 0.055f) / 1.055f, 2.4f);
}

/**
 * Highlight-only, hue-preserving inverse tone mapping for sRGB JPG inputs into SpektraLab:
 * - Shadows, midtones, and skin tones (linear lum <= 0.55, i.e. sRGB <= 195/255): 100% untouched (gain = 1.0).
 * - Upper highlights (linear lum 0.55..1.0, i.e. sRGB 195..255): smooth C1 monotonic shoulder expansion up to
 *   3.2x (+1.68 EV) using a shared per-pixel luminance scalar so RGB chromaticity & hue stay 100% invariant.
 * - 14-bit RAW inputs never call this function and retain native sensor linearity untouched.
 */
inline void convert_srgb8_with_highlight_headroom(uint8_t r8, uint8_t g8, uint8_t b8, float* dst3) {
    static const std::array<float, 256> kSrgbLut = []() {
        std::array<float, 256> tbl{};
        for (int i = 0; i < 256; ++i) {
            tbl[static_cast<size_t>(i)] = srgb8_to_linear(static_cast<uint8_t>(i));
        }
        return tbl;
    }();
    float r = kSrgbLut[r8];
    float g = kSrgbLut[g8];
    float b = kSrgbLut[b8];
    const float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    constexpr float kKnee = 0.55f;
    if (lum > kKnee) {
        const float t = std::clamp((lum - kKnee) / (1.0f - kKnee), 0.0f, 1.0f);
        const float gain = 1.0f + 2.2f * std::pow(t, 2.2f);
        r *= gain;
        g *= gain;
        b *= gain;
    }
    dst3[0] = r;
    dst3[1] = g;
    dst3[2] = b;
}

spk::Json build_open_params_obj(const std::string& base_json, bool is_linear_prophoto) {
    spk::Json obj = spk::Json::object();
    if (!base_json.empty()) {
        std::string parse_err;
        spk::Json parsed;
        if (spk::Json::parse(base_json, parsed, parse_err) && parsed.is_object()) {
            obj = std::move(parsed);
        }
    }
    if (is_linear_prophoto) {
        obj.set("input_color_space", spk::Json(std::string("ProPhoto RGB")));
        obj.set("input_cctf_decoding", spk::Json(false));
    } else {
        // Already decoded via exact sRGB EOTF + highlight-only headroom expansion above sRGB 195
        obj.set("input_color_space", spk::Json(std::string("sRGB")));
        obj.set("input_cctf_decoding", spk::Json(false));
    }
    obj.set("output_color_space", spk::Json(std::string("sRGB")));
    obj.set("output_cctf_encoding", spk::Json(true));
    obj.set("extended_dynamic_range", spk::Json(false));
    obj.set("striped", spk::Json(false));
    return obj;
}

std::string merge_open_params(const std::string& base_json, bool is_linear_prophoto) {
    return build_open_params_obj(base_json, is_linear_prophoto).dump();
}

bool decode_raw_fast_half_size(
    const std::string& pathStr,
    bool useCustomWb,
    double tempKelvin,
    double tint,
    std::vector<float>& outRgb,
    uint32_t& outW,
    uint32_t& outH,
    std::string& outModel,
    std::string& error
) {
    auto raw = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    auto& p = raw->imgdata.params;
    p.half_size = 1;
    p.user_qual = 0;
    p.four_color_rgb = 0;
    p.output_color = 4;
    p.output_bps = 16;
    p.gamm[0] = p.gamm[1] = 1.0;
    p.no_auto_bright = 1;
    p.no_auto_scale = 0;
    p.adjust_maximum_thr = 0.0f;
    p.bright = 1.0f;
    p.use_camera_wb = 1;
    p.use_auto_wb = 0;
    p.use_camera_matrix = 1;
    p.highlight = 0;

    int st = raw->open_file(pathStr.c_str());
    if (st != LIBRAW_SUCCESS) { error = std::string("RAW open: ") + LibRaw::strerror(st); return false; }

    if (useCustomWb) {
        std::array<float, 4> mul{};
        std::string wbErr;
        if (spkhost::multipliers_for(*raw, tempKelvin, tint, mul, wbErr)) {
            for (int c = 0; c < 4; ++c) p.user_mul[c] = mul[c];
            p.use_camera_wb = 0;
        }
    }

    st = raw->unpack();
    if (st != LIBRAW_SUCCESS) { error = std::string("RAW unpack: ") + LibRaw::strerror(st); return false; }
    st = raw->dcraw_process();
    if (st != LIBRAW_SUCCESS) { error = std::string("RAW process: ") + LibRaw::strerror(st); return false; }

    int imgSt = LIBRAW_SUCCESS;
    using ImagePtr = std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)>;
    ImagePtr img(raw->dcraw_make_mem_image(&imgSt), &LibRaw::dcraw_clear_mem);
    if (imgSt != LIBRAW_SUCCESS || !img || img->bits != 16 || img->colors != 3 || img->width == 0 || img->height == 0) {
        error = "RAW half-size output invalid";
        return false;
    }

    outW = img->width;
    outH = img->height;
    outModel = raw->imgdata.idata.model;
    const size_t samples = static_cast<size_t>(outW) * static_cast<size_t>(outH) * 3u;
    outRgb.resize(samples);
    constexpr float inv65535 = 1.0f / 65535.0f;
    for (size_t i = 0; i < samples; ++i) {
        uint16_t v = 0;
        std::memcpy(&v, img->data + i * sizeof(uint16_t), sizeof(uint16_t));
        outRgb[i] = static_cast<float>(v) * inv65535;
    }
    return true;
}

bool compute_raw_wb_multipliers(
    const std::string& pathStr,
    double tempKelvin,
    double tint,
    std::array<float, 4>& outMul,
    std::string& error
) {
    auto raw = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    int st = raw->open_file(pathStr.c_str());
    if (st != LIBRAW_SUCCESS) { error = LibRaw::strerror(st); return false; }
    return spkhost::multipliers_for(*raw, tempKelvin, tint, outMul, error);
}

void downsample_rgb(
    const float* src, uint32_t srcW, uint32_t srcH,
    uint32_t maxEdge,
    std::vector<float>& outRgb, uint32_t& outW, uint32_t& outH
) {
    const uint32_t longest = std::max(srcW, srcH);
    const double scale = (longest > maxEdge) ? (double(maxEdge) / double(longest)) : 1.0;
    outW = std::max(16u, static_cast<uint32_t>(std::lround(srcW * scale)));
    outH = std::max(16u, static_cast<uint32_t>(std::lround(srcH * scale)));
    outRgb.resize(static_cast<size_t>(outW) * static_cast<size_t>(outH) * 3u);
    for (uint32_t y = 0; y < outH; ++y) {
        const uint32_t sy = std::min(srcH - 1u, static_cast<uint32_t>((uint64_t(y) * srcH) / outH));
        for (uint32_t x = 0; x < outW; ++x) {
            const uint32_t sx = std::min(srcW - 1u, static_cast<uint32_t>((uint64_t(x) * srcW) / outW));
            const float* s = src + (static_cast<size_t>(sy) * srcW + sx) * 3u;
            float* d = outRgb.data() + (static_cast<size_t>(y) * outW + x) * 3u;
            d[0] = s[0]; d[1] = s[1]; d[2] = s[2];
        }
    }
}

/**
 * Overlapped-band full-resolution Vulkan renderer (supports 100% native 36.4MP 7360x4912+ with ZERO downscaling!).
 * Every GPU pass is kept strictly <= 5.5 MP (<= 66 MB float32, < 22,000 1D workgroups) so mobile Vulkan
 * GPUs never hit 128 MiB SSBO limits, 65535 2D workgroup folding bugs, or IIR watchdog timeouts.
 */
jobject render_full_rgb_tiled(
    JNIEnv* env,
    const float* fullRgb,
    uint32_t W,
    uint32_t H,
    bool isLinearProphoto,
    const std::string& paramsIn
) {
    if (!g_engine || !fullRgb || W == 0 || H == 0) {
        g_last_error = "暗房引擎或输入图像无效";
        return nullptr;
    }

    spk::Json baseParams = build_open_params_obj(paramsIn, isLinearProphoto);
    const uint64_t totalPixels = static_cast<uint64_t>(W) * static_cast<uint64_t>(H);
    constexpr uint64_t kMaxBandPixels = 5500000ULL;

    if (totalPixels <= kMaxBandPixels) {
        spk_image img{};
        img.data = fullRgb; img.width = W; img.height = H; img.channels = 3;

        char* openReply = nullptr;
        const std::string openJson = baseParams.dump();
        spk_session* sess = spk_open(g_engine, &img, openJson.c_str(), &openReply);
        if (openReply) spk_string_free(openReply);
        if (!sess) {
            const char* e = spk_last_error(g_engine);
            g_last_error = (e && *e) ? e : "spk_open failed";
            return nullptr;
        }

        spk_result res{};
        const spk_status st = spk_render(sess, "full", &res);
        if (st != SPK_OK || !res.rgba16 || res.width != W || res.height != H) {
            const char* e = spk_last_error(g_engine);
            g_last_error = (e && *e) ? e : "spk_render(full) failed";
            spk_result_free(&res); spk_session_release(sess); spk_trim_pool(g_engine, 0);
            return nullptr;
        }

        jobject outBmp = create_argb8888_bitmap(env, W, H);
        AndroidBitmapInfo info{};
        void* dstPixels = nullptr;
        if (!outBmp ||
            AndroidBitmap_getInfo(env, outBmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            AndroidBitmap_lockPixels(env, outBmp, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS || !dstPixels) {
            g_last_error = "无法分配或写入输出 Bitmap";
            spk_result_free(&res); spk_session_release(sess); spk_trim_pool(g_engine, 0);
            return nullptr;
        }

        const uint32_t stridePx = res.row_stride_px >= W ? res.row_stride_px : W;
        auto* dstBase = static_cast<uint8_t*>(dstPixels);
        for (uint32_t y = 0; y < H; ++y) {
            const uint16_t* srcRow = res.rgba16 + static_cast<size_t>(y) * stridePx * 4u;
            uint8_t* dstRow = dstBase + static_cast<size_t>(y) * info.stride;
            for (uint32_t x = 0; x < W; ++x) {
                dstRow[4u * x]     = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x])     * 255u + 32767u) / 65535u);
                dstRow[4u * x + 1] = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x + 1]) * 255u + 32767u) / 65535u);
                dstRow[4u * x + 2] = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x + 2]) * 255u + 32767u) / 65535u);
                dstRow[4u * x + 3] = 255u;
            }
        }
        AndroidBitmap_unlockPixels(env, outBmp);
        spk_result_free(&res); spk_session_release(sess); spk_trim_pool(g_engine, 0);
        return outBmp;
    }

    // Step 1: Lock global auto-exposure on a fast 1024px whole-frame proxy so every band matches identically
    if (baseParams.at("auto_exposure").as_bool(false)) {
        std::vector<float> calRgb;
        uint32_t calW = 0, calH = 0;
        downsample_rgb(fullRgb, W, H, 1024u, calRgb, calW, calH);

        spk_image calImg{};
        calImg.data = calRgb.data(); calImg.width = calW; calImg.height = calH; calImg.channels = 3;

        char* openReply = nullptr;
        const std::string calJson = baseParams.dump();
        spk_session* calSess = spk_open(g_engine, &calImg, calJson.c_str(), &openReply);
        if (openReply) spk_string_free(openReply);
        if (calSess) {
            char* solveReply = nullptr;
            if (spk_solve(calSess, "exposure", &solveReply) == SPK_OK && solveReply) {
                spk::Json parsed;
                std::string perr;
                if (spk::Json::parse(solveReply, parsed, perr) && parsed.is_object()) {
                    const spk::Json& sp = parsed.has("solved_params") ? parsed.at("solved_params") : parsed;
                    if (sp.at("exposure_compensation_ev").is_number()) {
                        const double solvedEv = sp.at("exposure_compensation_ev").as_double(0.0);
                        const double userEv = baseParams.at("exposure_compensation_ev").as_double(0.0);
                        baseParams.set("auto_exposure", spk::Json(false));
                        baseParams.set("exposure_compensation_ev", spk::Json(std::clamp(solvedEv + userEv, -8.0, 8.0)));
                    }
                }
            }
            if (solveReply) spk_string_free(solveReply);
            spk_session_release(calSess);
            spk_trim_pool(g_engine, 0);
        }
    }

    // Disable whole-frame stochastic glare & light leaks during multi-band stitching so bands join seamlessly
    baseParams.set("glare_active", spk::Json(false));
    baseParams.set("leak_kind", spk::Json(std::string("off")));
    if (!baseParams.has("grain_seed")) {
        baseParams.set("grain_seed", spk::Json(double(1337)));
    }

    const double baseFormatMm = baseParams.at("film_format_mm").as_double(36.0);
    const uint32_t fullLongEdge = std::max(W, H);

    jobject outBmp = create_argb8888_bitmap(env, W, H);
    if (!outBmp) { g_last_error = "无法分配全分辨率输出 Bitmap（内存不足）"; return nullptr; }
    AndroidBitmapInfo info{};
    void* dstPixels = nullptr;
    if (AndroidBitmap_getInfo(env, outBmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outBmp, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS || !dstPixels) {
        g_last_error = "无法锁定全分辨率输出 Bitmap";
        return nullptr;
    }

    const uint32_t halo = (W > 9000u) ? 64u : 128u;
    const uint32_t maxBandH = std::max(256u, static_cast<uint32_t>(kMaxBandPixels / std::max(1u, W)));
    const uint32_t coreH = (maxBandH > 2u * halo + 64u) ? (maxBandH - 2u * halo) : std::max(128u, maxBandH / 2u);

    LOGI("Tiled full-res Spektra export %ux%u: coreH=%u, halo=%u", W, H, coreH, halo);

    auto* dstBase = static_cast<uint8_t*>(dstPixels);
    bool allOk = true;

    for (uint32_t y0 = 0; y0 < H; y0 += coreH) {
        const uint32_t y1 = std::min(H, y0 + coreH);
        const uint32_t srcY0 = (y0 > halo) ? (y0 - halo) : 0u;
        const uint32_t srcY1 = std::min(H, y1 + halo);
        const uint32_t bandH = srcY1 - srcY0;

        const uint32_t bandLongEdge = std::max(W, bandH);
        const double scaledFormatMm = std::clamp(
            baseFormatMm * (double(bandLongEdge) / double(fullLongEdge)),
            3.0, 300.0
        );
        spk::Json bandParams = baseParams;
        bandParams.set("film_format_mm", spk::Json(scaledFormatMm));

        spk_image bandImg{};
        bandImg.data = fullRgb + static_cast<size_t>(srcY0) * W * 3u;
        bandImg.width = W; bandImg.height = bandH; bandImg.channels = 3;

        char* openReply = nullptr;
        const std::string bandJson = bandParams.dump();
        spk_session* bandSess = spk_open(g_engine, &bandImg, bandJson.c_str(), &openReply);
        if (openReply) spk_string_free(openReply);
        if (!bandSess) {
            const char* e = spk_last_error(g_engine);
            g_last_error = (e && *e) ? e : "分块暗房会话打开失败";
            allOk = false;
            break;
        }

        spk_result res{};
        const spk_status st = spk_render(bandSess, "full", &res);
        if (st != SPK_OK || !res.rgba16 || res.width != W || res.height != bandH) {
            const char* e = spk_last_error(g_engine);
            g_last_error = (e && *e) ? e : "分块暗房渲染失败";
            spk_result_free(&res); spk_session_release(bandSess); spk_trim_pool(g_engine, 0);
            allOk = false;
            break;
        }

        const uint32_t stridePx = res.row_stride_px >= W ? res.row_stride_px : W;
        const uint32_t localStart = y0 - srcY0;
        const uint32_t copyRows = y1 - y0;

        for (uint32_t r = 0; r < copyRows; ++r) {
            const uint16_t* srcRow = res.rgba16 + static_cast<size_t>(localStart + r) * stridePx * 4u;
            uint8_t* dstRow = dstBase + static_cast<size_t>(y0 + r) * info.stride;
            for (uint32_t x = 0; x < W; ++x) {
                dstRow[4u * x]     = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x])     * 255u + 32767u) / 65535u);
                dstRow[4u * x + 1] = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x + 1]) * 255u + 32767u) / 65535u);
                dstRow[4u * x + 2] = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x + 2]) * 255u + 32767u) / 65535u);
                dstRow[4u * x + 3] = 255u;
            }
        }

        spk_result_free(&res);
        spk_session_release(bandSess);
        spk_trim_pool(g_engine, 0);
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    return allOk ? outBmp : nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeInitEngine(
    JNIEnv* env, jobject /*thiz*/, jstring jResourcesDir
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    const std::string resDir = jstring_to_utf8(env, jResourcesDir);
    if (g_engine) {
        const char* caps = spk_capabilities(g_engine);
        return env->NewStringUTF((std::string("OK|") + (caps ? caps : "{}")).c_str());
    }
    LOGI("Initializing SpektraLab Vulkan engine from %s", resDir.c_str());
    g_engine = spk_engine_create(resDir.c_str(), nullptr);
    if (!g_engine) {
        const char* err = spk_last_error(nullptr);
        g_last_error = (err && *err) ? err : "spk_engine_create failed (no Vulkan compute device)";
        return env->NewStringUTF((std::string("ERR|") + g_last_error).c_str());
    }
    const char* caps = spk_capabilities(g_engine);
    return env->NewStringUTF((std::string("OK|") + (caps ? caps : "{}")).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeGetLastError(
    JNIEnv* env, jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    const char* engErr = g_engine ? spk_last_error(g_engine) : spk_last_error(nullptr);
    std::string msg = !g_last_error.empty() ? g_last_error : (engErr ? engErr : "");
    return env->NewStringUTF(msg.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeReadRawAsShotWb(
    JNIEnv* env, jobject /*thiz*/, jstring jFilePath
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    const std::string pathStr = jstring_to_utf8(env, jFilePath);
    auto raw = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    int st = raw->open_file(pathStr.c_str());
    if (st != LIBRAW_SUCCESS) return env->NewStringUTF("ERR|open failed");
    double kelvin = 5500.0, tint = 0.0;
    if (!spkhost::as_shot_temperature_tint(*raw, kelvin, tint)) return env->NewStringUTF("OK|5500|0");
    char buf[96];
    std::snprintf(buf, sizeof(buf), "OK|%.0f|%.1f", std::clamp(kelvin, 2200.0, 12000.0), std::clamp(tint, -100.0, 100.0));
    return env->NewStringUTF(buf);
}

JNIEXPORT jlong JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeOpenRawFile(
    JNIEnv* env, jobject /*thiz*/,
    jstring jFilePath,
    jboolean useHeadroom,
    jboolean halfSize,
    jboolean useCustomWb,
    jdouble tempKelvin,
    jdouble tint,
    jstring jParamsJson
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    if (!g_engine) { g_last_error = "SpektraLab 引擎尚未初始化"; return 0L; }

    const std::string pathStr = jstring_to_utf8(env, jFilePath);
    const std::string paramsIn = jstring_to_utf8(env, jParamsJson);

    std::vector<float> rgb;
    uint32_t decW = 0, decH = 0;
    std::string modelName, err;
    bool ok = false;

    if (halfSize == JNI_TRUE) {
        ok = decode_raw_fast_half_size(
            pathStr, useCustomWb == JNI_TRUE, tempKelvin, tint,
            rgb, decW, decH, modelName, err
        );
    } else {
        spk::io::DecodedRaw decoded;
        if (useCustomWb == JNI_TRUE) {
            std::array<float, 4> userMul{};
            std::string wbErr;
            if (compute_raw_wb_multipliers(pathStr, tempKelvin, tint, userMul, wbErr)) {
                ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), &userMul, decoded, err);
            } else {
                ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), decoded, err);
            }
        } else if (useHeadroom == JNI_TRUE) {
            ok = spk::io::decode_raw_headroom(std::filesystem::path(pathStr), decoded, err);
            if (!ok) { err.clear(); ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), decoded, err); }
        } else {
            ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), decoded, err);
        }
        if (ok) {
            decW = decoded.width; decH = decoded.height; modelName = decoded.metadata.model;
            rgb = std::move(decoded.rgb);
        }
    }

    if (!ok || decW == 0 || decH == 0 || rgb.empty()) {
        g_last_error = "LibRaw 解码失败: " + err;
        return 0L;
    }

    // Cap interactive RAW preview session to 1800px long edge so mobile Vulkan stays instant & 100% glitch-free
    std::vector<float> sessionRgb;
    const float* uploadPtr = rgb.data();
    uint32_t uploadW = decW, uploadH = decH;
    if (halfSize == JNI_TRUE && std::max(decW, decH) > 1800u) {
        downsample_rgb(rgb.data(), decW, decH, 1800u, sessionRgb, uploadW, uploadH);
        uploadPtr = sessionRgb.data();
    }

    const std::string mergedJson = merge_open_params(paramsIn, true);
    spk_image img{};
    img.data = uploadPtr; img.width = uploadW; img.height = uploadH; img.channels = 3;

    char* reply = nullptr;
    spk_session* sess = spk_open(g_engine, &img, mergedJson.c_str(), &reply);
    if (reply) spk_string_free(reply);
    if (!sess) {
        const char* e = spk_last_error(g_engine);
        g_last_error = (e && *e) ? e : "spk_open RAW session failed";
        return 0L;
    }

    auto* holder = new SessionHolder();
    holder->session = sess; holder->width = uploadW; holder->height = uploadH;
    holder->is_raw = true; holder->is_half_size = (halfSize == JNI_TRUE); holder->camera_model = modelName;
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT jlong JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeOpenBitmap(
    JNIEnv* env, jobject /*thiz*/, jobject jBitmap, jstring jParamsJson
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    if (!g_engine || !jBitmap) { g_last_error = "SpektraLab 引擎或位图为空"; return 0L; }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, jBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        g_last_error = "Bitmap 格式必须为 ARGB_8888";
        return 0L;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        g_last_error = "无法锁定 Bitmap 像素";
        return 0L;
    }

    const uint32_t w = info.width;
    const uint32_t h = info.height;
    std::vector<float> rgb(static_cast<size_t>(w) * static_cast<size_t>(h) * 3u);

    const auto* base = static_cast<const uint8_t*>(pixels);
    for (uint32_t y = 0; y < h; ++y) {
        const uint8_t* row = base + static_cast<size_t>(y) * info.stride;
        float* dst = rgb.data() + static_cast<size_t>(y) * w * 3u;
        for (uint32_t x = 0; x < w; ++x) {
            convert_srgb8_with_highlight_headroom(row[4u * x], row[4u * x + 1], row[4u * x + 2], dst + 3u * x);
        }
    }
    AndroidBitmap_unlockPixels(env, jBitmap);

    const std::string paramsIn = jstring_to_utf8(env, jParamsJson);
    const std::string mergedJson = merge_open_params(paramsIn, false);

    spk_image img{};
    img.data = rgb.data(); img.width = w; img.height = h; img.channels = 3;

    char* reply = nullptr;
    spk_session* sess = spk_open(g_engine, &img, mergedJson.c_str(), &reply);
    if (reply) spk_string_free(reply);
    if (!sess) {
        const char* e = spk_last_error(g_engine);
        g_last_error = (e && *e) ? e : "spk_open Bitmap session failed";
        return 0L;
    }

    auto* holder = new SessionHolder();
    holder->session = sess; holder->width = w; holder->height = h; holder->is_raw = false;
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT jobject JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeRenderBitmapFullTiled(
    JNIEnv* env, jobject /*thiz*/, jobject jBitmap, jstring jParamsJson
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    if (!g_engine || !jBitmap) { g_last_error = "SpektraLab 引擎或位图为空"; return nullptr; }

    AndroidBitmapInfo srcInfo{};
    if (AndroidBitmap_getInfo(env, jBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        srcInfo.width == 0 || srcInfo.height == 0) {
        g_last_error = "Bitmap 格式必须为 ARGB_8888";
        return nullptr;
    }

    const uint32_t W = srcInfo.width;
    const uint32_t H = srcInfo.height;
    const uint64_t totalPixels = static_cast<uint64_t>(W) * static_cast<uint64_t>(H);
    constexpr uint64_t kMaxBandPixels = 5500000ULL;
    const std::string paramsIn = jstring_to_utf8(env, jParamsJson);

    void* srcPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS || !srcPixels) {
        g_last_error = "无法锁定源 Bitmap";
        return nullptr;
    }

    // Small/medium image (<= 5.5MP): direct single-pass render
    if (totalPixels <= kMaxBandPixels) {
        std::vector<float> rgb(static_cast<size_t>(W) * static_cast<size_t>(H) * 3u);
        const auto* base = static_cast<const uint8_t*>(srcPixels);
        for (uint32_t y = 0; y < H; ++y) {
            const uint8_t* row = base + static_cast<size_t>(y) * srcInfo.stride;
            float* dst = rgb.data() + static_cast<size_t>(y) * W * 3u;
            for (uint32_t x = 0; x < W; ++x) {
                convert_srgb8_with_highlight_headroom(row[4u * x], row[4u * x + 1], row[4u * x + 2], dst + 3u * x);
            }
        }
        AndroidBitmap_unlockPixels(env, jBitmap);
        return render_full_rgb_tiled(env, rgb.data(), W, H, false, paramsIn);
    }

    // Streaming overlapped-band mode for large 12MP–36.4MP+ Bitmaps:
    // NEVER allocates a 434 MB full-frame float vector — only converts one <= 66 MB band at a time!
    spk::Json baseParams = build_open_params_obj(paramsIn, false);
    const auto* srcBase = static_cast<const uint8_t*>(srcPixels);

    // Step 1: Lock global auto-exposure on a fast 1024px whole-frame proxy directly from srcPixels
    if (baseParams.at("auto_exposure").as_bool(false)) {
        const uint32_t longest = std::max(W, H);
        const double sc = (longest > 1024u) ? (1024.0 / double(longest)) : 1.0;
        const uint32_t calW = std::max(16u, static_cast<uint32_t>(std::lround(W * sc)));
        const uint32_t calH = std::max(16u, static_cast<uint32_t>(std::lround(H * sc)));
        std::vector<float> calRgb(static_cast<size_t>(calW) * static_cast<size_t>(calH) * 3u);
        for (uint32_t y = 0; y < calH; ++y) {
            const uint32_t sy = std::min(H - 1u, static_cast<uint32_t>((uint64_t(y) * H) / calH));
            const uint8_t* sRow = srcBase + static_cast<size_t>(sy) * srcInfo.stride;
            float* dRow = calRgb.data() + static_cast<size_t>(y) * calW * 3u;
            for (uint32_t x = 0; x < calW; ++x) {
                const uint32_t sx = std::min(W - 1u, static_cast<uint32_t>((uint64_t(x) * W) / calW));
                convert_srgb8_with_highlight_headroom(sRow[4u * sx], sRow[4u * sx + 1], sRow[4u * sx + 2], dRow + 3u * x);
            }
        }
        spk_image calImg{};
        calImg.data = calRgb.data(); calImg.width = calW; calImg.height = calH; calImg.channels = 3;
        char* openReply = nullptr;
        const std::string calJson = baseParams.dump();
        spk_session* calSess = spk_open(g_engine, &calImg, calJson.c_str(), &openReply);
        if (openReply) spk_string_free(openReply);
        if (calSess) {
            char* solveReply = nullptr;
            if (spk_solve(calSess, "exposure", &solveReply) == SPK_OK && solveReply) {
                spk::Json parsed;
                std::string perr;
                if (spk::Json::parse(solveReply, parsed, perr) && parsed.is_object()) {
                    const spk::Json& sp = parsed.has("solved_params") ? parsed.at("solved_params") : parsed;
                    if (sp.at("exposure_compensation_ev").is_number()) {
                        const double solvedEv = sp.at("exposure_compensation_ev").as_double(0.0);
                        const double userEv = baseParams.at("exposure_compensation_ev").as_double(0.0);
                        baseParams.set("auto_exposure", spk::Json(false));
                        baseParams.set("exposure_compensation_ev", spk::Json(std::clamp(solvedEv + userEv, -8.0, 8.0)));
                    }
                }
            }
            if (solveReply) spk_string_free(solveReply);
            spk_session_release(calSess);
            spk_trim_pool(g_engine, 0);
        }
    }

    baseParams.set("glare_active", spk::Json(false));
    baseParams.set("leak_kind", spk::Json(std::string("off")));
    if (!baseParams.has("grain_seed")) {
        baseParams.set("grain_seed", spk::Json(double(1337)));
    }

    const double baseFormatMm = baseParams.at("film_format_mm").as_double(36.0);
    const uint32_t fullLongEdge = std::max(W, H);

    jobject outBmp = create_argb8888_bitmap(env, W, H);
    if (!outBmp) {
        AndroidBitmap_unlockPixels(env, jBitmap);
        g_last_error = "无法分配全分辨率输出 Bitmap（内存不足）";
        return nullptr;
    }
    AndroidBitmapInfo dstInfo{};
    void* dstPixels = nullptr;
    if (AndroidBitmap_getInfo(env, outBmp, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outBmp, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS || !dstPixels) {
        AndroidBitmap_unlockPixels(env, jBitmap);
        g_last_error = "无法锁定全分辨率输出 Bitmap";
        return nullptr;
    }

    const uint32_t halo = (W > 9000u) ? 64u : 128u;
    const uint32_t maxBandH = std::max(256u, static_cast<uint32_t>(kMaxBandPixels / std::max(1u, W)));
    const uint32_t coreH = (maxBandH > 2u * halo + 64u) ? (maxBandH - 2u * halo) : std::max(128u, maxBandH / 2u);
    auto* dstBase = static_cast<uint8_t*>(dstPixels);
    std::vector<float> bandRgb;
    bool allOk = true;

    for (uint32_t y0 = 0; y0 < H; y0 += coreH) {
        const uint32_t y1 = std::min(H, y0 + coreH);
        const uint32_t srcY0 = (y0 > halo) ? (y0 - halo) : 0u;
        const uint32_t srcY1 = std::min(H, y1 + halo);
        const uint32_t bandH = srcY1 - srcY0;

        bandRgb.resize(static_cast<size_t>(W) * static_cast<size_t>(bandH) * 3u);
        for (uint32_t r = 0; r < bandH; ++r) {
            const uint8_t* sRow = srcBase + static_cast<size_t>(srcY0 + r) * srcInfo.stride;
            float* dRow = bandRgb.data() + static_cast<size_t>(r) * W * 3u;
            for (uint32_t x = 0; x < W; ++x) {
                convert_srgb8_with_highlight_headroom(sRow[4u * x], sRow[4u * x + 1], sRow[4u * x + 2], dRow + 3u * x);
            }
        }

        const uint32_t bandLongEdge = std::max(W, bandH);
        const double scaledFormatMm = std::clamp(baseFormatMm * (double(bandLongEdge) / double(fullLongEdge)), 3.0, 300.0);
        spk::Json bandParams = baseParams;
        bandParams.set("film_format_mm", spk::Json(scaledFormatMm));

        spk_image bandImg{};
        bandImg.data = bandRgb.data(); bandImg.width = W; bandImg.height = bandH; bandImg.channels = 3;

        char* openReply = nullptr;
        const std::string bandJson = bandParams.dump();
        spk_session* bandSess = spk_open(g_engine, &bandImg, bandJson.c_str(), &openReply);
        if (openReply) spk_string_free(openReply);
        if (!bandSess) {
            const char* e = spk_last_error(g_engine);
            g_last_error = (e && *e) ? e : "分块暗房会话打开失败";
            allOk = false;
            break;
        }

        spk_result res{};
        const spk_status st = spk_render(bandSess, "full", &res);
        if (st != SPK_OK || !res.rgba16 || res.width != W || res.height != bandH) {
            const char* e = spk_last_error(g_engine);
            g_last_error = (e && *e) ? e : "分块暗房渲染失败";
            spk_result_free(&res); spk_session_release(bandSess); spk_trim_pool(g_engine, 0);
            allOk = false;
            break;
        }

        const uint32_t stridePx = res.row_stride_px >= W ? res.row_stride_px : W;
        const uint32_t localStart = y0 - srcY0;
        const uint32_t copyRows = y1 - y0;
        for (uint32_t r = 0; r < copyRows; ++r) {
            const uint16_t* sRow = res.rgba16 + static_cast<size_t>(localStart + r) * stridePx * 4u;
            uint8_t* dRow = dstBase + static_cast<size_t>(y0 + r) * dstInfo.stride;
            for (uint32_t x = 0; x < W; ++x) {
                dRow[4u * x]     = static_cast<uint8_t>((static_cast<uint32_t>(sRow[4u * x])     * 255u + 32767u) / 65535u);
                dRow[4u * x + 1] = static_cast<uint8_t>((static_cast<uint32_t>(sRow[4u * x + 1]) * 255u + 32767u) / 65535u);
                dRow[4u * x + 2] = static_cast<uint8_t>((static_cast<uint32_t>(sRow[4u * x + 2]) * 255u + 32767u) / 65535u);
                dRow[4u * x + 3] = 255u;
            }
        }

        spk_result_free(&res);
        spk_session_release(bandSess);
        spk_trim_pool(g_engine, 0);
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    AndroidBitmap_unlockPixels(env, jBitmap);
    return allOk ? outBmp : nullptr;
}

JNIEXPORT jobject JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeRenderRawFullTiled(
    JNIEnv* env, jobject /*thiz*/,
    jstring jFilePath,
    jboolean useHeadroom,
    jboolean useCustomWb,
    jdouble tempKelvin,
    jdouble tint,
    jstring jParamsJson
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    if (!g_engine) { g_last_error = "SpektraLab 引擎尚未初始化"; return nullptr; }

    const std::string pathStr = jstring_to_utf8(env, jFilePath);
    const std::string paramsIn = jstring_to_utf8(env, jParamsJson);

    spk::io::DecodedRaw decoded;
    std::string err;
    bool ok = false;
    if (useCustomWb == JNI_TRUE) {
        std::array<float, 4> userMul{};
        std::string wbErr;
        if (compute_raw_wb_multipliers(pathStr, tempKelvin, tint, userMul, wbErr)) {
            ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), &userMul, decoded, err);
        } else {
            ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), decoded, err);
        }
    } else if (useHeadroom == JNI_TRUE) {
        ok = spk::io::decode_raw_headroom(std::filesystem::path(pathStr), decoded, err);
        if (!ok) { err.clear(); ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), decoded, err); }
    } else {
        ok = spk::io::decode_raw_compatible(std::filesystem::path(pathStr), decoded, err);
    }

    if (!ok || decoded.width == 0 || decoded.height == 0 || decoded.rgb.empty()) {
        g_last_error = "LibRaw 全尺寸解码失败: " + err;
        return nullptr;
    }

    return render_full_rgb_tiled(env, decoded.rgb.data(), decoded.width, decoded.height, true, paramsIn);
}

JNIEXPORT jstring JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeSetParams(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jParamsJson
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    auto* holder = reinterpret_cast<SessionHolder*>(handle);
    if (!holder || !holder->session) { g_last_error = "无效的暗房会话"; return env->NewStringUTF("ERR|invalid session"); }
    const std::string json = jstring_to_utf8(env, jParamsJson);
    char* reply = nullptr;
    const spk_status st = spk_set_params(holder->session, json.c_str(), &reply);
    std::string out = reply ? reply : "{}";
    if (reply) spk_string_free(reply);
    if (st != SPK_OK) {
        const char* e = spk_last_error(g_engine);
        g_last_error = (e && *e) ? e : "spk_set_params failed";
        return env->NewStringUTF((std::string("ERR|") + g_last_error).c_str());
    }
    return env->NewStringUTF((std::string("OK|") + out).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeSolve(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jTarget
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    auto* holder = reinterpret_cast<SessionHolder*>(handle);
    if (!holder || !holder->session) { g_last_error = "无效的暗房会话"; return env->NewStringUTF("ERR|invalid session"); }
    const std::string target = jstring_to_utf8(env, jTarget);
    char* reply = nullptr;
    const spk_status st = spk_solve(holder->session, target.c_str(), &reply);
    std::string out = reply ? reply : "{}";
    if (reply) spk_string_free(reply);
    if (st != SPK_OK) {
        const char* e = spk_last_error(g_engine);
        g_last_error = (e && *e) ? e : "spk_solve failed";
        return env->NewStringUTF((std::string("ERR|") + g_last_error).c_str());
    }
    return env->NewStringUTF((std::string("OK|") + out).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeSceneLatitude(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jRequestJson
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    auto* holder = reinterpret_cast<SessionHolder*>(handle);
    if (!holder || !holder->session) { g_last_error = "无效的暗房会话"; return env->NewStringUTF("ERR|invalid session"); }
    const std::string reqStr = jstring_to_utf8(env, jRequestJson);
    const char* reqPtr = reqStr.empty() ? nullptr : reqStr.c_str();
    char* reply = nullptr;
    const spk_status st = spk_scene_latitude(holder->session, reqPtr, &reply);
    std::string out = reply ? reply : "{}";
    if (reply) spk_string_free(reply);
    if (st != SPK_OK) {
        const char* e = spk_last_error(g_engine);
        g_last_error = (e && *e) ? e : "spk_scene_latitude failed";
        return env->NewStringUTF((std::string("ERR|") + g_last_error).c_str());
    }
    return env->NewStringUTF((std::string("OK|") + out).c_str());
}

JNIEXPORT jobject JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeRenderToBitmap(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jTier, jboolean reprintOnly
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    auto* holder = reinterpret_cast<SessionHolder*>(handle);
    if (!holder || !holder->session) { g_last_error = "无效的暗房会话"; return nullptr; }

    const std::string tier = jstring_to_utf8(env, jTier);
    spk_result res{};
    const spk_status st = (reprintOnly == JNI_TRUE)
        ? spk_reprint(holder->session, tier.c_str(), &res)
        : spk_render(holder->session, tier.c_str(), &res);

    if (st != SPK_OK || !res.rgba16 || res.width == 0 || res.height == 0) {
        const char* e = spk_last_error(g_engine);
        g_last_error = (e && *e) ? e : "spk_render failed";
        spk_result_free(&res);
        return nullptr;
    }

    jobject outBmp = create_argb8888_bitmap(env, res.width, res.height);
    if (!outBmp) { g_last_error = "无法分配输出 Bitmap"; spk_result_free(&res); return nullptr; }

    AndroidBitmapInfo info{};
    void* dstPixels = nullptr;
    if (AndroidBitmap_getInfo(env, outBmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, outBmp, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS || !dstPixels) {
        g_last_error = "无法写入输出 Bitmap";
        spk_result_free(&res);
        return nullptr;
    }

    const uint32_t stridePx = res.row_stride_px >= res.width ? res.row_stride_px : res.width;
    auto* dstBase = static_cast<uint8_t*>(dstPixels);
    for (uint32_t y = 0; y < res.height; ++y) {
        const uint16_t* srcRow = res.rgba16 + static_cast<size_t>(y) * stridePx * 4u;
        uint8_t* dstRow = dstBase + static_cast<size_t>(y) * info.stride;
        for (uint32_t x = 0; x < res.width; ++x) {
            dstRow[4u * x]     = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x])     * 255u + 32767u) / 65535u);
            dstRow[4u * x + 1] = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x + 1]) * 255u + 32767u) / 65535u);
            dstRow[4u * x + 2] = static_cast<uint8_t>((static_cast<uint32_t>(srcRow[4u * x + 2]) * 255u + 32767u) / 65535u);
            dstRow[4u * x + 3] = 255u;
        }
    }

    AndroidBitmap_unlockPixels(env, outBmp);
    spk_result_free(&res);
    return outBmp;
}

JNIEXPORT void JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeCloseSession(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    auto* holder = reinterpret_cast<SessionHolder*>(handle);
    if (!holder) return;
    if (holder->session) spk_session_release(holder->session);
    delete holder;
    if (g_engine) spk_trim_pool(g_engine, 32u * 1024u * 1024u);
}

JNIEXPORT jobject JNICALL
Java_com_pentax_k1sync_spektra_SpektraNativeBridge_nativeDecodeRawPreview(
    JNIEnv* env, jobject /*thiz*/, jstring jFilePath, jint maxLongEdge
) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_last_error.clear();
    const std::string pathStr = jstring_to_utf8(env, jFilePath);

    std::vector<float> rgb;
    uint32_t srcW = 0, srcH = 0;
    std::string modelName, err;
    if (!decode_raw_fast_half_size(pathStr, false, 5500.0, 0.0, rgb, srcW, srcH, modelName, err) ||
        srcW == 0 || srcH == 0 || rgb.empty()) {
        g_last_error = "LibRaw 预览解码失败: " + err;
        return nullptr;
    }

    const uint32_t longEdge = std::max(srcW, srcH);
    const float scale = (maxLongEdge > 0 && longEdge > static_cast<uint32_t>(maxLongEdge))
        ? (static_cast<float>(maxLongEdge) / static_cast<float>(longEdge))
        : 1.0f;
    const uint32_t outW = std::max(1u, static_cast<uint32_t>(std::lround(srcW * scale)));
    const uint32_t outH = std::max(1u, static_cast<uint32_t>(std::lround(srcH * scale)));

    jobject bmp = create_argb8888_bitmap(env, outW, outH);
    if (!bmp) return nullptr;

    AndroidBitmapInfo info{};
    void* dstPixels = nullptr;
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bmp, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS || !dstPixels) {
        return nullptr;
    }

    constexpr float M[9] = {
         2.0341930f, -0.7274200f, -0.3067660f,
        -0.2288270f,  1.2317500f, -0.0029220f,
        -0.0085650f, -0.1532730f,  1.1618390f
    };

    auto* dstBase = static_cast<uint8_t*>(dstPixels);
    for (uint32_t y = 0; y < outH; ++y) {
        const uint32_t sy = std::min(srcH - 1u, static_cast<uint32_t>((static_cast<uint64_t>(y) * srcH) / outH));
        uint8_t* dstRow = dstBase + static_cast<size_t>(y) * info.stride;
        for (uint32_t x = 0; x < outW; ++x) {
            const uint32_t sx = std::min(srcW - 1u, static_cast<uint32_t>((static_cast<uint64_t>(x) * srcW) / outW));
            const float* src = rgb.data() + (static_cast<size_t>(sy) * srcW + sx) * 3u;
            const float r = M[0] * src[0] + M[1] * src[1] + M[2] * src[2];
            const float g = M[3] * src[0] + M[4] * src[1] + M[5] * src[2];
            const float b = M[6] * src[0] + M[7] * src[1] + M[8] * src[2];
            dstRow[4u * x]     = linear_to_srgb8(r);
            dstRow[4u * x + 1] = linear_to_srgb8(g);
            dstRow[4u * x + 2] = linear_to_srgb8(b);
            dstRow[4u * x + 3] = 255u;
        }
    }

    AndroidBitmap_unlockPixels(env, bmp);
    return bmp;
}

}  // extern "C"
