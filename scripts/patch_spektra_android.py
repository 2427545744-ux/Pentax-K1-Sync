#!/usr/bin/env python3
"""
Patches upstream SpektraLab engine sources for clean Android NDK 27 + mobile Vulkan GPU support:
1. src/core/cam16.cpp: std::jthread -> exception-safe std::thread + explicit .join().
2. src/gpu/vulkan_gpu.cpp: Allows Android mobile integrated GPUs (Adreno/Mali/PowerVR/Xclipse) to
   fall back to SpektraLab's exact Dekker product when fma() is unfused under SPIR-V precise.
3. src/pipeline/blur.cpp: Eliminates readonly/write SSBO buffer aliasing in Blur::iir() vertical pass
   by using a separate intermediate buffer `tmp`, preventing Adreno/Mali L1 cache hazards (black top-half bug).
4. src/pipeline/edge_raster.cpp: Appends /system/fonts to font search paths on Android.
"""
import os
import sys

def patch_cam16_jthread(engine_dir: str):
    path = os.path.join(engine_dir, "src", "core", "cam16.cpp")
    if not os.path.isfile(path):
        sys.exit(f"Missing {path}")
    with open(path, "r", encoding="utf-8") as f:
        txt = f.read()
    if "ANDROID_STD_THREAD_PATCH" in txt or "std::jthread" not in txt:
        print("cam16.cpp already patched or has no std::jthread.")
        return

    old_block = """        std::vector<std::jthread> workers;
        workers.reserve(3);
        for (size_t t = 1; t < 4; ++t) workers.emplace_back([&, t] {
            for (size_t li = t; li < kCmaxNL; li += 4) row(li);
        });
        for (size_t li = 0; li < kCmaxNL; li += 4) row(li);"""

    new_block = """        // ANDROID_STD_THREAD_PATCH: Android NDK libc++ disables experimental std::jthread by default
        std::vector<std::thread> workers;
        workers.reserve(3);
        try {
            for (size_t t = 1; t < 4; ++t) workers.emplace_back([&, t] {
                for (size_t li = t; li < kCmaxNL; li += 4) row(li);
            });
            for (size_t li = 0; li < kCmaxNL; li += 4) row(li);
            for (auto& w : workers) if (w.joinable()) w.join();
        } catch (...) {
            for (auto& w : workers) if (w.joinable()) w.join();
            throw;
        }"""

    txt = txt.replace(old_block, new_block, 1) if old_block in txt else txt.replace("std::jthread", "std::thread")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)
    print("Patched cam16.cpp (std::jthread -> std::thread).")

def patch_vulkan_gpu(engine_dir: str):
    path = os.path.join(engine_dir, "src", "gpu", "vulkan_gpu.cpp")
    if not os.path.isfile(path):
        sys.exit(f"Missing {path}")
    with open(path, "r", encoding="utf-8") as f:
        txt = f.read()
    if "ANDROID_INTEGRATED_FMA_PATCH" in txt:
        print("vulkan_gpu.cpp already patched.")
        return

    old_line = "if (zero && !exact && properties_.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) {"
    new_block = """// ANDROID_INTEGRATED_FMA_PATCH: Mobile Vulkan GPUs report INTEGRATED_GPU and may
        // lower fma() to mul+add; allow fallback to SpektraLab's exact Dekker double-float.
#ifdef __ANDROID__
        const bool allow_unfused_dekker = (properties_.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ||
                                           properties_.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU);
#else
        const bool allow_unfused_dekker = (properties_.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU);
#endif
        if (zero && !exact && allow_unfused_dekker) {"""
    if old_line in txt:
        txt = txt.replace(old_line, new_block, 1)

    old_probe = "            probe_cpu_storage_range(detail);"
    new_probe = "            if (properties_.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) probe_cpu_storage_range(detail);"
    if old_probe in txt:
        txt = txt.replace(old_probe, new_probe, 1)

    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)
    print("Patched vulkan_gpu.cpp for Android mobile GPUs.")

def patch_blur_no_alias(engine_dir: str):
    path = os.path.join(engine_dir, "src", "pipeline", "blur.cpp")
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        txt = f.read()
    if "ANDROID_IIR_NO_ALIAS_PATCH" in txt:
        print("blur.cpp already patched.")
        return

    old_iir = """    if (!alloc_like(img, out, error)) return false;
    const Image* dummy = acc && acc->buf ? acc : &img;   // no buffer: the sum is still zero, mode 2
    // **Horizontal first, and the order is not a detail.** The reference's
    // `_gaussian_filter_2d_large` is `_iir_horizontal` then `_iir_vertical`,
    // and a separable IIR's two passes commute in exact arithmetic and *not* in
    // floating point: run them the other way round and every value moves in the
    // last place. The transposed path was horizontal-first for this reason --
    // the transpose existed so that pass could be the vertical kernel -- and
    // the first draft of this rewrite had it vertical-first, with a comment
    // claiming the order was preserved. `iir_bitexact.py` against the previous
    // build said otherwise, which is what that gate is for: the parity
    // harnesses hold a 1e-5 tolerance and would have absorbed it.
    //
    // The first pass is plain and the second carries the mixture's multiply-add,
    // which is also where the fused multiply-add landed in the transposed path.
    const uint32_t meta_h[4] = {img.h, img.w, 0u, 1u};
    const uint32_t meta_v[4] = {img.h, img.w, acc ? (acc->buf ? 1u : 2u) : 0u, 0u};
    if (!gpu_->dispatch("spk_iir_df_acc",
                        {gpu::Arg::buf(img.buf), gpu::Arg::buf(coef_buf), gpu::Arg::buf(act_buf),
                         gpu::Arg::buf(dummy->buf), gpu::Arg::buf(wt_buf),
                         gpu::Arg::inline_bytes(meta_h, 4), gpu::Arg::buf(out.buf)},
                        size_t(img.h) * 3, error)) return false;
    if (mix) {
        gpu::BufferRef ab = upload_mix(*mix, error);
        if (!ab) return false;
        if (!gpu_->dispatch("spk_iir_df_fin",
                            {gpu::Arg::buf(out.buf), gpu::Arg::buf(coef_buf), gpu::Arg::buf(act_buf),
                             gpu::Arg::buf(dummy->buf), gpu::Arg::buf(wt_buf),
                             gpu::Arg::inline_bytes(meta_v, 4), gpu::Arg::buf(out.buf),
                             gpu::Arg::buf(mix->x->buf), gpu::Arg::buf(ab)},
                            size_t(img.w) * 3, error)) return false;
    } else if (!gpu_->dispatch("spk_iir_df_acc",
                        {gpu::Arg::buf(out.buf), gpu::Arg::buf(coef_buf), gpu::Arg::buf(act_buf),
                         gpu::Arg::buf(dummy->buf), gpu::Arg::buf(wt_buf),
                         gpu::Arg::inline_bytes(meta_v, 4), gpu::Arg::buf(out.buf)},
                        size_t(img.w) * 3, error)) return false;"""

    new_iir = """    // ANDROID_IIR_NO_ALIAS_PATCH: Avoid aliasing readonly binding 0 with writable binding 6 on mobile Vulkan GPUs
    Image tmp;
    if (!alloc_like(img, tmp, error)) return false;
    if (!alloc_like(img, out, error)) return false;
    const Image* dummy = acc && acc->buf ? acc : &img;
    const uint32_t meta_h[4] = {img.h, img.w, 0u, 1u};
    const uint32_t meta_v[4] = {img.h, img.w, acc ? (acc->buf ? 1u : 2u) : 0u, 0u};
    if (!gpu_->dispatch("spk_iir_df_acc",
                        {gpu::Arg::buf(img.buf), gpu::Arg::buf(coef_buf), gpu::Arg::buf(act_buf),
                         gpu::Arg::buf(dummy->buf), gpu::Arg::buf(wt_buf),
                         gpu::Arg::inline_bytes(meta_h, 4), gpu::Arg::buf(tmp.buf)},
                        size_t(img.h) * 3, error)) return false;
    if (mix) {
        gpu::BufferRef ab = upload_mix(*mix, error);
        if (!ab) return false;
        if (!gpu_->dispatch("spk_iir_df_fin",
                            {gpu::Arg::buf(tmp.buf), gpu::Arg::buf(coef_buf), gpu::Arg::buf(act_buf),
                             gpu::Arg::buf(dummy->buf), gpu::Arg::buf(wt_buf),
                             gpu::Arg::inline_bytes(meta_v, 4), gpu::Arg::buf(out.buf),
                             gpu::Arg::buf(mix->x->buf), gpu::Arg::buf(ab)},
                            size_t(img.w) * 3, error)) return false;
    } else if (!gpu_->dispatch("spk_iir_df_acc",
                        {gpu::Arg::buf(tmp.buf), gpu::Arg::buf(coef_buf), gpu::Arg::buf(act_buf),
                         gpu::Arg::buf(dummy->buf), gpu::Arg::buf(wt_buf),
                         gpu::Arg::inline_bytes(meta_v, 4), gpu::Arg::buf(out.buf)},
                        size_t(img.w) * 3, error)) return false;"""

    if old_iir in txt:
        txt = txt.replace(old_iir, new_iir, 1)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(txt)
        print("Patched blur.cpp (no-alias IIR tmp buffer).")

def patch_edge_raster(engine_dir: str):
    path = os.path.join(engine_dir, "src", "pipeline", "edge_raster.cpp")
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        txt = f.read()
    if "/system/fonts" in txt:
        return
    target = '"/usr/share/fonts/truetype/msttcorefonts"'
    if target in txt:
        txt = txt.replace(target, target + ', "/system/fonts"')
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(txt)
        print("Patched edge_raster.cpp (/system/fonts).")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: patch_spektra_android.py <spektra_engine_dir>")
    patch_cam16_jthread(sys.argv[1])
    patch_vulkan_gpu(sys.argv[1])
    patch_blur_no_alias(sys.argv[1])
    patch_edge_raster(sys.argv[1])
