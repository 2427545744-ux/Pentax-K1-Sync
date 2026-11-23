#!/usr/bin/env python3
"""
Patches upstream SpektraLab engine sources for clean Android NDK 27 (Clang 18 / libc++ 18) + mobile Vulkan GPU support:
1. src/core/cam16.cpp: Replaces C++20 experimental std::jthread (disabled by default in Android NDK libc++)
   with standard exception-safe std::thread + explicit .join().
2. src/gpu/vulkan_gpu.cpp: Allows Android mobile integrated GPUs (Adreno/Mali/PowerVR/Xclipse) that
   do not fuse fma() under SPIR-V precise to fall back to SpektraLab's exact Dekker product (fused_fma_ = 0).
3. src/pipeline/edge_raster.cpp: Appends /system/fonts to font search paths on Android.
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

    if old_block in txt:
        txt = txt.replace(old_block, new_block, 1)
    else:
        # Generic fallback if upstream reformatted whitespace
        txt = txt.replace("std::jthread", "std::thread")
        print("WARNING: Applied generic std::jthread -> std::thread replacement in cam16.cpp.")

    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)
    print("Patched cam16.cpp (std::jthread -> std::thread) for Android NDK libc++.")

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
    if old_line not in txt:
        print("WARNING: Exact check_math_mode line not found in vulkan_gpu.cpp; skipping FMA patch.")
    else:
        txt = txt.replace(old_line, new_block, 1)

    old_probe = "            probe_cpu_storage_range(detail);"
    new_probe = "            if (properties_.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) probe_cpu_storage_range(detail);"
    if old_probe in txt:
        txt = txt.replace(old_probe, new_probe, 1)

    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(txt)
    print("Patched vulkan_gpu.cpp for Android mobile GPUs.")

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
        print("Patched edge_raster.cpp with Android /system/fonts fallback.")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: patch_spektra_android.py <spektra_engine_dir>")
    patch_cam16_jthread(sys.argv[1])
    patch_vulkan_gpu(sys.argv[1])
    patch_edge_raster(sys.argv[1])
