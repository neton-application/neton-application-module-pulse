#!/usr/bin/env bash
#
# PulseKit 崩溃符号 / R8 mapping 上传工具。
#
# 让每个发布版本的崩溃堆栈可读:
#   - iOS   原生崩溃是「地址 + slide」→ 需要 `nm -n` 符号表（地址→函数名）。
#   - Android .so 原生帧同理。
#   - Android Java/Kotlin 崩溃是**混淆的文本栈** → 需要 R8 的 mapping.txt 反混淆。
#
# 后端按 (appKey, buildNumber) 存，所以**每发一个版本上传一次**，buildNumber 必须等于该版本
# 客户端上报的构建号（iOS=CFBundleVersion、Android=versionCode）。重传会整份替换。
#
# 用法:
#   iOS dSYM:
#     pulse-symbols-upload.sh --base <adminApiBase> --token <bearer> \
#       --app-key <key> --build <n> --ios-dsym /path/Weey.app.dSYM
#   Android 原生 .so:
#     pulse-symbols-upload.sh ... --app-key <key> --build <n> --android-so /path/libfoo.so
#   Android R8 mapping:
#     pulse-symbols-upload.sh ... --app-key <key> --build <n> --android-mapping /path/mapping.txt
#
#   --base   admin API 根（如 https://admin.weey.chat 或 http://127.0.0.1:8080），
#            脚本会拼 /admin/pulse/crash/{symbols,mapping}/upload。
#   --token  admin 登录后的 Bearer token。
#
# 依赖: curl、jq（可选，仅美化输出）、nm（iOS/Android 原生符号）。
set -euo pipefail

BASE="" TOKEN="" APP_KEY="" BUILD="" IOS_DSYM="" ANDROID_SO="" ANDROID_MAPPING=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$2"; shift 2;;
    --token) TOKEN="$2"; shift 2;;
    --app-key) APP_KEY="$2"; shift 2;;
    --build) BUILD="$2"; shift 2;;
    --ios-dsym) IOS_DSYM="$2"; shift 2;;
    --android-so) ANDROID_SO="$2"; shift 2;;
    --android-mapping) ANDROID_MAPPING="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

[[ -z "$BASE" || -z "$TOKEN" || -z "$APP_KEY" || -z "$BUILD" ]] && {
  echo "必填: --base --token --app-key --build，以及三个来源之一(--ios-dsym/--android-so/--android-mapping)" >&2
  exit 2
}

# nm -n 风格文本:后端只认「地址 符号名」行，`nm -n` / `atos -o` 列表 / 手写表都行。
nm_text() {
  local bin="$1"
  command -v nm >/dev/null || { echo "需要 nm (Xcode CLT / NDK)" >&2; exit 3; }
  # -n 按地址排序；过滤掉无地址行。
  nm -n "$bin" 2>/dev/null | awk 'NF>=3 && $1 ~ /^[0-9a-fA-F]+$/'
}

# dSYM 是个 bundle，真正的 Mach-O 在 Contents/Resources/DWARF/<name>。
dsym_binary() {
  local dsym="$1"
  if [[ -d "$dsym" ]]; then find "$dsym/Contents/Resources/DWARF" -type f | head -1
  else echo "$dsym"; fi
}

post_json() { # $1=path  $2=json
  local url="${BASE%/}$1"
  echo ">> POST $url"
  local resp
  resp=$(curl -sS -X POST "$url" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    --data-binary "$2")
  if command -v jq >/dev/null; then echo "$resp" | jq .; else echo "$resp"; fi
}

# 用 jq 安全构造 JSON（符号/mapping 文本含换行/引号）。
build_json() { # $1=endpointKind(symbols|mapping) 文本从 stdin
  if command -v jq >/dev/null; then
    jq -Rs --arg appKey "$APP_KEY" --argjson build "$BUILD" \
      '{appKey:$appKey, buildNumber:$build, text:.}'
  else
    echo "需要 jq 来安全编码文本" >&2; exit 3
  fi
}

if [[ -n "$IOS_DSYM" ]]; then
  bin=$(dsym_binary "$IOS_DSYM"); echo "iOS binary: $bin"
  json=$(nm_text "$bin" | build_json)
  post_json "/admin/pulse/crash/symbols/upload" "$json"
fi
if [[ -n "$ANDROID_SO" ]]; then
  json=$(nm_text "$ANDROID_SO" | build_json)
  post_json "/admin/pulse/crash/symbols/upload" "$json"
fi
if [[ -n "$ANDROID_MAPPING" ]]; then
  json=$(cat "$ANDROID_MAPPING" | build_json)
  post_json "/admin/pulse/crash/mapping/upload" "$json"
fi
echo "done."
