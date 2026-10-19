#!/usr/bin/env bash
set -euo pipefail

# OpenClaw 번들의 모델 카탈로그 소스에서 앱용 JSON을 추출한다.
# setup-assets.sh 실행 후, 또는 OpenClaw 버전과 무관하게 언제든 실행 가능.
# tar.gz.bin이 있으면 그 안의 계약 경로를 기준으로 추출하고, 없을 때만 디렉토리 소스를 본다.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ASSETS_DIR="${ANDCLAW_ASSETS_DIR:-$PROJECT_DIR/install_time_assets/src/main/assets}"
OPENCLAW_TAR="$ASSETS_DIR/openclaw-arm64.tar.gz.bin"
LEGACY_MODELS_RELATIVE_PATH="usr/local/lib/node_modules/openclaw/node_modules/@earendil-works/pi-ai/dist/models.generated.js"
PLUGIN_MANIFEST_ROOTS=(
  "usr/local/lib/node_modules/openclaw/dist/extensions"
  "root/.openclaw/andclaw-bundled-plugins/npm/node_modules/@openclaw"
)
OUTPUT="${ANDCLAW_MODEL_CATALOG_OUTPUT:-$PROJECT_DIR/app/src/main/assets/model-catalog.json}"
TEMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

mkdir -p "$(dirname "$OUTPUT")"

generate_from_legacy_models() {
  local models_js="$1"
  node - "$models_js" "$OUTPUT" <<'NODE'
const fs = require('fs');
const vm = require('vm');
const modelsPath = process.argv[2];
const output = process.argv[3];
let content = fs.readFileSync(modelsPath, 'utf8');
content = content.replace(/^\/\/.*\n/gm, '').replace('export const MODELS =', 'var MODELS =');
const ctx = {};
vm.runInNewContext(content, ctx);
const catalogByProvider = {};
const sourcePriorityByKey = new Map();
for (const [provider, providerModels] of Object.entries(ctx.MODELS)) {
  const normalizedProvider = String(provider).trim().toLowerCase();
  const canonicalProvider = ['codex', 'openai-codex'].includes(normalizedProvider)
    ? 'openai'
    : normalizedProvider;
  const sourcePriority = normalizedProvider === 'openai' ? 2 : 1;
  catalogByProvider[canonicalProvider] ??= new Map();
  for (const model of Object.values(providerModels)) {
    const id = typeof model?.id === 'string' ? model.id.trim() : '';
    if (!id) continue;
    const key = canonicalProvider + '\0' + id.toLowerCase();
    if ((sourcePriorityByKey.get(key) ?? -1) >= sourcePriority) continue;
    sourcePriorityByKey.set(key, sourcePriority);
    catalogByProvider[canonicalProvider].set(id.toLowerCase(), {
      id,
      name: model.name || id,
      provider: canonicalProvider,
      contextWindow: model.contextWindow || 0,
      maxTokens: model.maxTokens || 0,
      reasoning: !!model.reasoning,
      images: Array.isArray(model.input) ? model.input.some(input => ['image', 'images', 'vision', 'video'].includes(String(input).toLowerCase())) : false,
    });
  }
}
const catalog = {};
for (const provider of Object.keys(catalogByProvider).sort()) {
  catalog[provider] = Array.from(catalogByProvider[provider].values())
    .sort((left, right) => left.id.localeCompare(right.id));
}
fs.writeFileSync(output, JSON.stringify(catalog));
const providers = Object.keys(catalog).length;
const models = Object.values(catalog).reduce((s, a) => s + a.length, 0);
console.log('Generated model-catalog.json from legacy registry: ' + providers + ' providers, ' + models + ' models (' + fs.statSync(output).size + ' bytes)');
NODE
}

generate_from_plugin_manifests() {
  local manifest_root="$1"
  local manifest_paths_file="$2"
  node - "$manifest_root" "$manifest_paths_file" "$OUTPUT" <<'NODE'
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const manifestRoot = process.argv[2];
const manifestPathsFile = process.argv[3];
const output = process.argv[4];
const manifestPaths = fs.readFileSync(manifestPathsFile, 'utf8')
  .split(/\r?\n/)
  .map(line => line.trim())
  .filter(Boolean)
  .sort();

const catalog = {};
const modelIndex = new Map();

function canonicalProvider(provider) {
  const normalized = String(provider).trim().toLowerCase();
  return ['codex', 'openai-codex'].includes(normalized) ? 'openai' : normalized;
}

function supportsImages(model) {
  const inputs = Array.isArray(model.input)
    ? model.input
    : (Array.isArray(model.inputModalities) ? model.inputModalities : []);
  return inputs.some(input => ['image', 'images', 'vision', 'video'].includes(String(input).toLowerCase()));
}

function supportsReasoning(model) {
  if (model.reasoning === true) return true;
  if (Array.isArray(model.supportedReasoningEfforts) && model.supportedReasoningEfforts.length > 0) return true;
  return false;
}

function addModel(provider, model, defaults = {}, sourcePriority = 1) {
  if (!model || typeof model !== 'object') return;
  const id = typeof model.id === 'string' && model.id.trim()
    ? model.id.trim()
    : (typeof model.model === 'string' ? model.model.trim() : '');
  if (!id) return;

  const normalizedProvider = canonicalProvider(model.provider || provider);
  if (!normalizedProvider) return;

  const row = {
    id,
    name: (typeof model.name === 'string' && model.name.trim())
      ? model.name
      : ((typeof model.displayName === 'string' && model.displayName.trim()) ? model.displayName : id),
    provider: normalizedProvider,
    contextWindow: Number.isFinite(model.contextWindow)
      ? model.contextWindow
      : (Number.isFinite(model.contextTokens) ? model.contextTokens : (defaults.contextWindow || 0)),
    maxTokens: Number.isFinite(model.maxTokens) ? model.maxTokens : (defaults.maxTokens || 0),
    reasoning: supportsReasoning(model),
    images: supportsImages(model),
  };
  if (typeof model.description === 'string' && model.description.trim()) {
    row.description = model.description.trim();
  }
  if (typeof model.isDefault === 'boolean') {
    row.isDefault = model.isDefault;
  }
  if (Array.isArray(model.supportedReasoningEfforts)) {
    row.supportedReasoningEfforts = model.supportedReasoningEfforts.map(String);
  }

  const key = normalizedProvider + '\0' + id.toLowerCase();
  const existing = modelIndex.get(key);
  if (!existing) {
    catalog[normalizedProvider] ??= [];
    const index = catalog[normalizedProvider].length;
    catalog[normalizedProvider].push(row);
    modelIndex.set(key, { index, priority: sourcePriority });
    return;
  }

  const existingRow = catalog[normalizedProvider][existing.index];
  catalog[normalizedProvider][existing.index] = sourcePriority > existing.priority
    ? { ...existingRow, ...row }
    : { ...row, ...existingRow };
  existing.priority = Math.max(existing.priority, sourcePriority);
}

function extractBalancedSection(content, startIndex, openChar, closeChar) {
  let depth = 0;
  let inString = false;
  let quote = '';
  let escaping = false;
  for (let index = startIndex; index < content.length; index += 1) {
    const ch = content[index];
    if (escaping) {
      escaping = false;
      continue;
    }
    if (inString) {
      if (ch === '\\') {
        escaping = true;
      } else if (ch === quote) {
        inString = false;
      }
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      inString = true;
      quote = ch;
      continue;
    }
    if (ch === openChar) {
      depth += 1;
    } else if (ch === closeChar) {
      depth -= 1;
      if (depth === 0) {
        return content.slice(startIndex, index + 1);
      }
    }
  }
  return '';
}

function extractAssignedArray(content, name) {
  const nameIndex = content.indexOf(name);
  if (nameIndex < 0) return '';
  const equalsIndex = content.indexOf('=', nameIndex);
  if (equalsIndex < 0) return '';
  const arrayStart = content.indexOf('[', equalsIndex);
  if (arrayStart < 0) return '';
  return extractBalancedSection(content, arrayStart, '[', ']');
}

function extractNumberConst(content, name) {
  const match = new RegExp('const\\s+' + name + '\\s*=\\s*([^;]+);').exec(content);
  if (!match) return null;
  const value = vm.runInNewContext('Number(' + match[1] + ')', {}, { timeout: 1000 });
  return Number.isFinite(value) ? value : null;
}

function collectManifestCatalog(plugin) {
  const providers = plugin?.modelCatalog?.providers;
  if (!providers || typeof providers !== 'object' || Array.isArray(providers)) return;

  for (const [provider, providerCatalog] of Object.entries(providers)) {
    const models = providerCatalog?.models;
    if (!Array.isArray(models)) continue;

    const sourcePriority = plugin?.id === 'openai' && canonicalProvider(provider) === 'openai' ? 2 : 1;
    for (const model of models) {
      addModel(provider, model, {}, sourcePriority);
    }
  }
}

function resolveProviderCatalogPath(plugin, manifestPath) {
  const manifestDir = path.dirname(manifestPath);
  const candidates = [];
  if (typeof plugin?.providerCatalogEntry === 'string' && plugin.providerCatalogEntry.trim()) {
    const entryPath = path.normalize(path.join(manifestDir, plugin.providerCatalogEntry.trim()));
    candidates.push(entryPath);
    candidates.push(path.join(path.dirname(entryPath), 'provider-catalog.js'));
  }
  candidates.push(path.join(manifestDir, 'dist/provider-catalog.js'));
  return candidates.find(candidate => {
    if (!fs.existsSync(candidate)) return false;
    const content = fs.readFileSync(candidate, 'utf8');
    return content.includes('FALLBACK_CODEX_MODELS');
  }) || null;
}

function collectCodexProviderCatalog(plugin, manifestPath) {
  const providerIds = Array.isArray(plugin?.providers) ? plugin.providers.map(String) : [];
  if (plugin?.id !== 'codex' && !providerIds.includes('codex')) return;

  const providerCatalogPath = resolveProviderCatalogPath(plugin, manifestPath);
  if (!providerCatalogPath) return;

  const content = fs.readFileSync(providerCatalogPath, 'utf8');
  const fallbackModelsLiteral = extractAssignedArray(content, 'FALLBACK_CODEX_MODELS');
  if (!fallbackModelsLiteral) return;

  const fallbackModels = vm.runInNewContext('(' + fallbackModelsLiteral + ')', {}, { timeout: 1000 });
  if (!Array.isArray(fallbackModels)) return;

  const defaults = {
    contextWindow: extractNumberConst(content, 'DEFAULT_CONTEXT_WINDOW') || 272000,
    maxTokens: extractNumberConst(content, 'DEFAULT_MAX_TOKENS') || 128000,
  };
  fallbackModels.forEach(model => addModel('openai', model, defaults, 0));
}

for (const relativePath of manifestPaths) {
  const manifestPath = path.join(manifestRoot, relativePath);
  const plugin = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  collectManifestCatalog(plugin);
  collectCodexProviderCatalog(plugin, manifestPath);
}

const sortedCatalog = {};
for (const provider of Object.keys(catalog).sort()) {
  sortedCatalog[provider] = catalog[provider].sort((left, right) => left.id.localeCompare(right.id));
}

const providers = Object.keys(sortedCatalog).length;
const models = Object.values(sortedCatalog).reduce((sum, entries) => sum + entries.length, 0);
if (models === 0) {
  throw new Error('No model catalog rows found in OpenClaw plugin manifests');
}

fs.writeFileSync(output, JSON.stringify(sortedCatalog));
console.log('Generated model-catalog.json from plugin manifests: ' + providers + ' providers, ' + models + ' models (' + fs.statSync(output).size + ' bytes)');
NODE
}

MANIFEST_PATHS="$TEMP_DIR/plugin-manifest-paths.txt"
: > "$MANIFEST_PATHS"

if [[ -f "$OPENCLAW_TAR" ]]; then
  TAR_LIST="$TEMP_DIR/openclaw-tar-list.txt"
  tar -tzf "$OPENCLAW_TAR" > "$TAR_LIST"

  if grep -Fxq "$LEGACY_MODELS_RELATIVE_PATH" "$TAR_LIST"; then
    tar xzf "$OPENCLAW_TAR" -C "$TEMP_DIR" "$LEGACY_MODELS_RELATIVE_PATH"
    generate_from_legacy_models "$TEMP_DIR/$LEGACY_MODELS_RELATIVE_PATH"
    exit 0
  fi

  grep -E '^(usr/local/lib/node_modules/openclaw/dist/extensions|root/.openclaw/andclaw-bundled-plugins/npm/node_modules/@openclaw)/.*/openclaw\.plugin\.json$' "$TAR_LIST" > "$MANIFEST_PATHS" || true
  if [[ ! -s "$MANIFEST_PATHS" ]]; then
    echo "OpenClaw model catalog source not found in $OPENCLAW_TAR" >&2
    echo "Expected legacy registry or plugin manifests with modelCatalog.providers/providerCatalogEntry." >&2
    exit 1
  fi

  tar xzf "$OPENCLAW_TAR" -C "$TEMP_DIR" -T "$MANIFEST_PATHS"

  PROVIDER_CATALOG_CANDIDATES="$TEMP_DIR/plugin-provider-catalog-candidates.txt"
  node - "$TEMP_DIR" "$MANIFEST_PATHS" "$PROVIDER_CATALOG_CANDIDATES" <<'NODE'
const fs = require('fs');
const path = require('path');

const manifestRoot = process.argv[2];
const manifestPathsFile = process.argv[3];
const output = process.argv[4];
const candidates = new Set();
const manifestPaths = fs.readFileSync(manifestPathsFile, 'utf8')
  .split(/\r?\n/)
  .map(line => line.trim())
  .filter(Boolean);

for (const relativePath of manifestPaths) {
  const manifestPath = path.join(manifestRoot, relativePath);
  const plugin = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const providerIds = Array.isArray(plugin?.providers) ? plugin.providers.map(String) : [];
  if (plugin?.id !== 'codex' && !providerIds.includes('codex')) continue;

  const manifestDir = path.dirname(relativePath);
  if (typeof plugin?.providerCatalogEntry === 'string' && plugin.providerCatalogEntry.trim()) {
    const entryPath = path.normalize(path.join(manifestDir, plugin.providerCatalogEntry.trim())).replace(/\\/g, '/');
    candidates.add(entryPath);
    candidates.add(path.join(path.dirname(entryPath), 'provider-catalog.js').replace(/\\/g, '/'));
  }
  candidates.add(path.join(manifestDir, 'dist/provider-catalog.js').replace(/\\/g, '/'));
}

fs.writeFileSync(output, Array.from(candidates).join('\n') + '\n');
NODE

  EXTRA_MODEL_CATALOG_PATHS="$TEMP_DIR/plugin-extra-model-catalog-paths.txt"
  grep -Fxf "$PROVIDER_CATALOG_CANDIDATES" "$TAR_LIST" > "$EXTRA_MODEL_CATALOG_PATHS" || true
  if [[ -s "$EXTRA_MODEL_CATALOG_PATHS" ]]; then
    tar xzf "$OPENCLAW_TAR" -C "$TEMP_DIR" -T "$EXTRA_MODEL_CATALOG_PATHS"
  fi
  generate_from_plugin_manifests "$TEMP_DIR" "$MANIFEST_PATHS"
  exit 0
fi

OPENCLAW_DIR="$ASSETS_DIR/openclaw"
if [[ -f "$OPENCLAW_DIR/$LEGACY_MODELS_RELATIVE_PATH" ]]; then
  generate_from_legacy_models "$OPENCLAW_DIR/$LEGACY_MODELS_RELATIVE_PATH"
  exit 0
fi

for root in "${PLUGIN_MANIFEST_ROOTS[@]}"; do
  if [[ -d "$OPENCLAW_DIR/$root" ]]; then
    (cd "$OPENCLAW_DIR" && find "$root" -path '*/openclaw.plugin.json' -type f | sort) >> "$MANIFEST_PATHS"
  fi
done

if [[ ! -s "$MANIFEST_PATHS" ]]; then
  echo "OpenClaw model catalog source not found under $ASSETS_DIR" >&2
  echo "Run scripts/setup-assets.sh first." >&2
  exit 1
fi

generate_from_plugin_manifests "$OPENCLAW_DIR" "$MANIFEST_PATHS"
