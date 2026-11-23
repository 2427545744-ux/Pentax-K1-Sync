cmake_minimum_required(VERSION 3.22.1)
project(k1spektra LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(SPEKTRA_ENGINE_DIR "${PROJECT_ROOT}/third_party/spektra_upstream/engine")
set(DEPS_DIR "${PROJECT_ROOT}/third_party/deps")
set(LIBRAW_DIR "${DEPS_DIR}/LibRaw-0.22.2")
set(VULKAN_HEADERS_DIR "${DEPS_DIR}/Vulkan-Headers/include")

if(NOT EXISTS "${SPEKTRA_ENGINE_DIR}/include/spektralab/spk_engine.h")
    message(FATAL_ERROR "SpektraLab upstream engine missing at ${SPEKTRA_ENGINE_DIR}. Run scripts/prepare_spektra_android.sh first!")
endif()
if(NOT EXISTS "${LIBRAW_DIR}/libraw/libraw.h")
    message(FATAL_ERROR "LibRaw 0.22.2 missing at ${LIBRAW_DIR}. Run scripts/prepare_spektra_android.sh first!")
endif()
if(NOT EXISTS "${VULKAN_HEADERS_DIR}/vulkan/vulkan_core.h")
    message(FATAL_ERROR "Vulkan-Headers missing at ${VULKAN_HEADERS_DIR}. Run scripts/prepare_spektra_android.sh first!")
endif()

add_library(spk_volk STATIC "${SPEKTRA_ENGINE_DIR}/third_party/volk/volk.c")
set_target_properties(spk_volk PROPERTIES LINKER_LANGUAGE C)
target_include_directories(spk_volk PUBLIC
    "${SPEKTRA_ENGINE_DIR}/third_party/volk"
    "${VULKAN_HEADERS_DIR}"
)
target_link_libraries(spk_volk PUBLIC dl)

file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS "${LIBRAW_DIR}/src/*.cpp")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX "/[^/]*_ph\\.cpp$")

add_library(spk_libraw STATIC ${LIBRAW_SOURCES})
target_include_directories(spk_libraw PUBLIC "${LIBRAW_DIR}")
target_compile_definitions(spk_libraw PUBLIC LIBRAW_NODLL PRIVATE LIBRAW_NOTHREADS)
target_compile_features(spk_libraw PUBLIC cxx_std_11)
target_compile_options(spk_libraw PRIVATE -w -O2)
target_link_libraries(spk_libraw PUBLIC m)

add_library(spk_raw_io STATIC
    "${SPEKTRA_ENGINE_DIR}/src/io/raw_decoder.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/io/raw_headroom.cpp"
    "${SPEKTRA_ENGINE_DIR}/host/white_balance.cpp"
)
target_compile_features(spk_raw_io PUBLIC cxx_std_20)
target_include_directories(spk_raw_io PUBLIC
    "${SPEKTRA_ENGINE_DIR}/src"
    "${SPEKTRA_ENGINE_DIR}/host"
)
target_compile_options(spk_raw_io PRIVATE -O3)
target_link_libraries(spk_raw_io PUBLIC spk_libraw)

add_library(spektralab_core STATIC
    "${SPEKTRA_ENGINE_DIR}/src/core/blob.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/cam16.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/colour.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/curves.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/diffusion_psf.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/digital_intermediate.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/hanatos.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/hdr_curves.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/json.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/latitude_fit.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/minilab.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/negative_hue.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/numeric.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/params.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/print_lut.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/printing.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/profile.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/scan_hdr.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/setup_cache.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/core/spectral.cpp"
)
target_compile_features(spektralab_core PUBLIC cxx_std_20)
target_include_directories(spektralab_core PUBLIC
    "${SPEKTRA_ENGINE_DIR}/src/core"
    "${SPEKTRA_ENGINE_DIR}/include"
)
target_compile_options(spektralab_core PRIVATE -O3)

add_library(spektralab_engine STATIC
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/blur.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/contrast_mask.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/disk_checkpoint.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/edge_raster.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/engine.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/lens.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/overscan.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/pipeline.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/scene_latitude.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/scene_layers.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/pipeline/windows_unported.cpp"
    "${SPEKTRA_ENGINE_DIR}/src/gpu/vulkan_gpu.cpp"
)
target_compile_features(spektralab_engine PUBLIC cxx_std_20)
target_include_directories(spektralab_engine PUBLIC
    "${SPEKTRA_ENGINE_DIR}/include"
    "${SPEKTRA_ENGINE_DIR}/src"
    "${SPEKTRA_ENGINE_DIR}/third_party/stb"
)
target_compile_options(spektralab_engine PRIVATE -O3)
target_link_libraries(spektralab_engine PUBLIC spektralab_core spk_volk)

add_library(k1spektra SHARED spektra_jni.cpp)
target_compile_features(k1spektra PRIVATE cxx_std_20)
target_include_directories(k1spektra PRIVATE
    "${SPEKTRA_ENGINE_DIR}/include"
    "${SPEKTRA_ENGINE_DIR}/src"
    "${SPEKTRA_ENGINE_DIR}/src/core"
    "${SPEKTRA_ENGINE_DIR}/host"
)
target_link_libraries(k1spektra PRIVATE
    spektralab_engine
    spk_raw_io
    jnigraphics
    android
    log
    m
)
