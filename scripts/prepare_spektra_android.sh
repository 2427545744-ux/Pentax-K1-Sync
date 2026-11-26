#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
UPSTREAM_DIR="$PROJECT_DIR/third_party/spektra_upstream"
DEPS_DIR="$PROJECT_DIR/third_party/deps"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets/spektra_engine"

echo "=== [1/5] Fetching SpektraLab upstream engine ==="
if [ ! -d "$UPSTREAM_DIR/engine" ]; then
  rm -rf "$UPSTREAM_DIR"
  mkdir -p "$(dirname "$UPSTREAM_DIR")"
  git clone --depth 1 https://github.com/JamesQiu2005/SpektraLab.git "$UPSTREAM_DIR"
else
  echo "SpektraLab upstream already present at $UPSTREAM_DIR"
fi

ENGINE_DIR="$UPSTREAM_DIR/engine"

echo "=== [2/5] Fetching pinned LibRaw 0.22.2 & Vulkan-Headers ==="
mkdir -p "$DEPS_DIR"
if [ -x "$ENGINE_DIR/setup-deps.sh" ]; then
  bash "$ENGINE_DIR/setup-deps.sh" "$DEPS_DIR"
else
  if [ ! -d "$DEPS_DIR/LibRaw-0.22.2" ]; then
    curl -fsSL -o "$DEPS_DIR/LibRaw-0.22.2.zip" "https://codeload.github.com/LibRaw/LibRaw/zip/refs/tags/0.22.2"
    python3 -c "import zipfile; zipfile.ZipFile('$DEPS_DIR/LibRaw-0.22.2.zip').extractall('$DEPS_DIR')"
  fi
  if [ ! -d "$DEPS_DIR/Vulkan-Headers" ]; then
    git clone --depth 1 --branch vulkan-sdk-1.4.341.0 https://github.com/KhronosGroup/Vulkan-Headers.git "$DEPS_DIR/Vulkan-Headers"
  fi
fi

echo "=== [3/5] Applying Android Vulkan GPU compatibility patches ==="
python3 "$SCRIPT_DIR/patch_spektra_android.py" "$ENGINE_DIR"

echo "=== [4/5] Compiling SpektraLab Vulkan compute shaders (.comp -> .spv) ==="
GLSLANG="$(command -v glslangValidator || command -v glslang || true)"
if [ -z "$GLSLANG" ]; then
  echo "ERROR: glslangValidator not found! Install glslang-tools first." >&2
  exit 1
fi

rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR/vulkan"

SHADER_SRC_DIR="$ENGINE_DIR/src/shaders/vulkan"
SPV_COUNT=0
for comp in "$SHADER_SRC_DIR"/*.comp; do
  name="$(basename "$comp" .comp)"
  "$GLSLANG" -V --target-env vulkan1.0 -I"$SHADER_SRC_DIR" -o "$ASSETS_DIR/vulkan/${name}.spv" "$comp"
  SPV_COUNT=$((SPV_COUNT + 1))
done
echo "Compiled $SPV_COUNT Vulkan SPIR-V compute shaders."

echo "=== [5/5] Staging slimmed 81-wavelength spectral resources & full 121-film catalog ==="
cp -R "$ENGINE_DIR/resources/." "$ASSETS_DIR/"
# Keep minilab/ intact (spk_engine_create requires minilab/index.json & v2 print calibration tables)

if [ -d "$ENGINE_DIR/resources_product/profiles" ]; then
  mkdir -p "$ASSETS_DIR/profiles"
  cp "$ENGINE_DIR/resources_product/profiles/"*.json "$ASSETS_DIR/profiles/"
fi
if [ -f "$ENGINE_DIR/resources_product/neutral_print_filters_colour.json" ]; then
  cp "$ENGINE_DIR/resources_product/neutral_print_filters_colour.json" "$ASSETS_DIR/"
fi
if [ -f "$ENGINE_DIR/resources_product/paper_grades.json" ]; then
  cp "$ENGINE_DIR/resources_product/paper_grades.json" "$ASSETS_DIR/"
fi
if [ -f "$UPSTREAM_DIR/app/SpektraLab/Resources/StockCatalog.json" ]; then
  cp "$UPSTREAM_DIR/app/SpektraLab/Resources/StockCatalog.json" "$ASSETS_DIR/StockCatalog.json"
fi

(cd "$ASSETS_DIR" && find . -type f | sed 's|^./||' | sort > manifest.txt)
echo "Staged slimmed SpektraLab assets ($(wc -l < "$ASSETS_DIR/manifest.txt") files, $(du -sh "$ASSETS_DIR" | cut -f1))."
