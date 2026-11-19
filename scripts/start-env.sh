#!/usr/bin/env bash
# 由启动脚本加载。保留调用方显式设置的值，再补齐 local.env 中的配置。
load_local_env() {
  [[ -f "$REPO_ROOT/scripts/local.env" ]] || return 0
  local name index=0
  local -a names values
  for name in FDE_DB_PASSWORD FDE_DB_URL FDE_DB_USERNAME FDE_JWT_SECRET \
    FDE_INTERNAL_TOKEN FDE_JAVA_BASE_URL FDE_STORAGE_ROOT FDE_RUNTIME_DIR FDE_SERVER_PORT \
    FDE_DSH_HOST FDE_DSH_PORT FDE_DSH_OPEN FDE_NO_BACKEND FDE_WORKBENCH_ALLOWED_ORIGINS; do
    if [[ -n "${!name+x}" ]]; then
      names[$index]="$name"
      values[$index]="${!name}"
      index=$((index + 1))
    fi
  done

  # 该文件是 Bash 配置，使用普通赋值即可，无须逐行 export。
  # shellcheck disable=SC1091
  source "$REPO_ROOT/scripts/local.env" || return 1
  while [[ "$index" -gt 0 ]]; do
    index=$((index - 1))
    printf -v "${names[$index]}" '%s' "${values[$index]}"
  done
}

# 相对存储路径固定以仓库根为基准。Java 和 DSH 即使从不同目录启动也使用同一位置。
set_storage_root() {
  export FDE_STORAGE_ROOT="${FDE_STORAGE_ROOT:-$REPO_ROOT/data}"
  case "$FDE_STORAGE_ROOT" in
    /* | [A-Za-z]:/*) ;;
    *) FDE_STORAGE_ROOT="$REPO_ROOT/$FDE_STORAGE_ROOT" ;;
  esac
}
