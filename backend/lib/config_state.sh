#!/system/bin/sh
# A03：写入前持久化 before/written，恢复时只修改仍由模块拥有的字段。
# 运行期只使用 Android settings 接口；每应用 XML 仅在 system_server 未启动时写。
STATE=${STATE:-/data/adb/inkcontrol_backend}
CFG=${CFG:-/data/system/epd_mode_cfg.xml}
CS_DIR=${CS_DIR:-$STATE/config_state}
CS_HOOK_DIR=${CS_HOOK_DIR:-/data/adb/post-fs-data.d}
CS_SERVICE_DIR=${CS_SERVICE_DIR:-/data/adb/service.d}
CS_MODULE=${CS_MODULE:-/data/adb/modules/inkcontrol_backend}
CONFIG_SOURCE=${CONFIG_SOURCE:-${MODDIR:-${MODPATH:-${0%/*}}}/config_state.sh}

cs_log() {
  mkdir -p "$CS_DIR"
  printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$CS_DIR/events.log"
  if [ "$(wc -l < "$CS_DIR/events.log")" -gt 400 ]; then
    tail -n 200 "$CS_DIR/events.log" > "$CS_DIR/events.tmp" && mv "$CS_DIR/events.tmp" "$CS_DIR/events.log"
  fi
}

cs_lock() {
  mkdir -p "$CS_DIR" || return 1
  chmod 700 "$STATE" "$CS_DIR" || return 1
  # 异常退出遗留的锁只在确认 owner 不存在时清理；无 owner 的竞争窗口不清理。
  if ! mkdir "$CS_DIR/lock" 2>/dev/null; then
    cs_owner=$(cat "$CS_DIR/lock/pid" 2>/dev/null)
    case "$cs_owner" in ''|*[!0-9]*) return 1 ;; esac
    kill -0 "$cs_owner" 2>/dev/null && return 1
    rm -f "$CS_DIR/lock/pid"
    rmdir "$CS_DIR/lock" 2>/dev/null || return 1
    mkdir "$CS_DIR/lock" 2>/dev/null || return 1
  fi
  cs_owner_pid=$$
  # read 内建打开 /proc/self，得到当前子 shell；不能用 cat（那会读到 cat 自己的 PID）。
  if [ -r /proc/self/stat ]; then read -r cs_owner_pid cs_stat_rest < /proc/self/stat; fi
  echo "$cs_owner_pid" > "$CS_DIR/lock/pid"
  trap 'rm -f "$CS_DIR/lock/pid"; rmdir "$CS_DIR/lock" 2>/dev/null' EXIT
  trap 'exit 1' HUP INT TERM
}

cs_boot_safe() {
  [ "$(getprop sys.boot_completed)" != 1 ] && [ -z "$(pidof system_server)" ]
}

cs_disabled() {
  [ ! -d "$CS_MODULE" ] || [ -f "$CS_MODULE/disable" ] || [ -f "$CS_MODULE/remove" ] || [ -f "$CS_DIR/stopped" ]
}

# 只接受原厂 FastXmlSerializer 的完整格式，拒绝未知标签/重复包名/AtomicFile 正在写入。
# 未知格式保留原文件和待恢复账本，绝不回灌整份历史快照。
cs_xml_valid() {
  [ -f "$1" ] || return 1
  awk '
    NR==1 {if ($0 != "<?xml version=\0471.0\047 encoding=\047utf-8\047 standalone=\047yes\047 ?>") bad=1; next}
    NR==2 {if ($0 != "<config>") bad=1; next}
    $0=="</config>" {if (closed) bad=1; closed=1; next}
    {if (closed || $0 !~ /^<cfg pkg="[A-Za-z0-9._]+" mode="[0-9]+" \/>$/) {bad=1; next}
     split($0,a,"\""); if (seen[a[2]]++ || length(a[4])>9) bad=1}
    END {exit (bad || !closed)}' "$1"
}

cs_value() {
  awk -F '"' -v pkg="$2" '$1=="<cfg pkg=" && $2==pkg {print $4; found=1} END {if (!found) print "absent"}' "$1"
}

# 生成字段修改后的文件，未触及的行按原样保留；before=absent 时只删除模块新增字段。
cs_merge_field() {
  awk -F '"' -v pkg="$2" -v value="$3" '
    $1=="<cfg pkg=" && $2==pkg {found=1; if (value!="absent") print "<cfg pkg=\"" pkg "\" mode=\"" value "\" />"; next}
    $0=="</config>" && !found && value!="absent" {print "<cfg pkg=\"" pkg "\" mode=\"" value "\" />"}
    {print}' "$1" > "$4"
}

cs_global_read() {
  cs_g=$(settings get global dynamic_refresh_mode 2>/dev/null) || return 1
  case "$cs_g" in 0|1) printf '%s\n' "$cs_g" ;; null) echo absent ;; *) return 1 ;; esac
}

cs_global_write() {
  if [ "$1" = absent ]; then
    settings delete global dynamic_refresh_mode >/dev/null 2>&1 || return 1
  else
    settings put global dynamic_refresh_mode "$1" >/dev/null 2>&1 || return 1
  fi
  [ "$(cs_global_read)" = "$1" ]
}

# 安装脚本 source common.sh 时记录初始值；升级不得覆盖已有记录。
cs_capture_install() (
  cs_lock || exit 1
  [ -f "$CS_DIR/install_dynamic" ] && exit 0
  cs_v=$(cs_global_read) || exit 1
  echo "$cs_v" > "$CS_DIR/install_dynamic.tmp" && mv "$CS_DIR/install_dynamic.tmp" "$CS_DIR/install_dynamic"
  cs_log "capture-install dynamic=$cs_v（首次记录前的旧版本改动不可推断）"
)

cs_global_apply() (
  cs_lock || exit 1
  [ -f "$CS_DIR/stopped" ] && exit 0
  cs_now=$(cs_global_read) || exit 1
  if [ -f "$CS_DIR/dynamic.tsv" ]; then
    read -r cs_before cs_written cs_phase < "$CS_DIR/dynamic.tsv"
    # 已有安装周期：用户/系统产生的新值不再被每次开机强制覆盖。
    [ "$cs_phase" = owned ] && [ "$cs_now" = "$cs_written" ] && exit 0
    cs_log "global apply skipped: current=$cs_now，保留安装后改动或已释放字段"
    exit 0
  fi
  cs_before=$cs_now
  # 若安装与首次启动之间用户已修改该项，尊重新值。
  if [ -f "$CS_DIR/install_dynamic" ] && [ "$(cat "$CS_DIR/install_dynamic")" != "$cs_now" ]; then
    cs_log 'global apply skipped: 安装后首次启动前字段已改变'
    exit 0
  fi
  printf '%s 0 owned\n' "$cs_before" > "$CS_DIR/dynamic.tmp" && mv "$CS_DIR/dynamic.tmp" "$CS_DIR/dynamic.tsv" || exit 1
  cs_global_write 0 || { cs_log 'global write failed，保留账本供重试恢复'; exit 1; }
  cs_log "global applied before=$cs_before written=0"
)

cs_global_restore() (
  cs_lock || exit 1
  [ -f "$CS_DIR/dynamic.tsv" ] || exit 0
  read -r cs_before cs_written cs_phase < "$CS_DIR/dynamic.tsv"
  [ "$cs_phase" = owned ] || exit 0
  cs_now=$(cs_global_read) || exit 1
  cs_result=kept
  if [ "$cs_now" = "$cs_written" ]; then
    cs_global_write "$cs_before" || { cs_log 'global restore failed，保留待恢复状态'; exit 1; }
    cs_result=restored
  fi
  printf '%s %s %s\n' "$cs_before" "$cs_written" "$cs_result" > "$CS_DIR/dynamic.tmp" && mv "$CS_DIR/dynamic.tmp" "$CS_DIR/dynamic.tsv"
  cs_log "global restore=$cs_result before=$cs_before written=$cs_written current=$cs_now"
)

# 批量入口：from/to 是 XML 中的完整组合模式，不把 521 直接传给只接受 1..9 的 Binder。
# 先记录受影响包及其 before/written；真正落盘在下次启动、system_server 之前。
cs_queue_batch() (
  case "$1:$2" in *[!0-9:]*|:*) exit 2 ;; esac
  [ "${#1}" -le 9 ] && [ "${#2}" -le 9 ] && [ -n "$2" ] || exit 2
  [ "$1" != "$2" ] || exit 2
  cs_lock || exit 1
  [ -f "$CS_DIR/stopped" ] && exit 1
  [ ! -f "$CFG.bak" ] && cs_xml_valid "$CFG" || exit 1
  cp "$CFG" "$CS_DIR/queue_input.tmp" || exit 1
  cs_xml_valid "$CS_DIR/queue_input.tmp" && cmp -s "$CFG" "$CS_DIR/queue_input.tmp" || exit 1
  [ -f "$CS_DIR/apps.tsv" ] || : > "$CS_DIR/apps.tsv"
  awk -F '"' -v from="$1" -v to="$2" '
    FILENAME==ARGV[1] {split($0,b," "); owned[b[1]]=1; next}
    $1=="<cfg pkg=" && $4==from && !owned[$2] {print $2, $4, to, "queued"}' "$CS_DIR/apps.tsv" "$CS_DIR/queue_input.tmp" > "$CS_DIR/add.tmp"
  cat "$CS_DIR/apps.tsv" "$CS_DIR/add.tmp" > "$CS_DIR/apps.tmp" && mv "$CS_DIR/apps.tmp" "$CS_DIR/apps.tsv" || exit 1
  cs_log "batch queued from=$1 to=$2 count=$(wc -l < "$CS_DIR/add.tmp")，下次启动应用"
  rm -f "$CS_DIR/add.tmp" "$CS_DIR/queue_input.tmp"
)

# 为主整合/已有批量工具提供写前登记 API；只能登记有实际来源证据的 before/written。
# 本模块没有运行期全组合模式 Binder 接口，因此 owned 只用于导入明确的旧写入记录。
cs_record_app() (
  case "$1" in ''|*[!A-Za-z0-9._]*) exit 2 ;; esac
  case "$2" in absent) ;; ''|*[!0-9]*) exit 2 ;; esac
  case "$3" in ''|*[!0-9]*) exit 2 ;; esac
  case "$4" in queued|owned) ;; *) exit 2 ;; esac
  [ "${#2}" -le 9 ] && [ "${#3}" -le 9 ] && [ "$2" != "$3" ] || exit 2
  cs_lock || exit 1
  [ -f "$CS_DIR/apps.tsv" ] || : > "$CS_DIR/apps.tsv"
  if awk -v pkg="$1" '$1==pkg {found=1} END {exit !found}' "$CS_DIR/apps.tsv"; then
    cs_log "record-app refused: $1 已有账本，禁止覆盖安装前值"
    exit 1
  fi
  cat "$CS_DIR/apps.tsv" > "$CS_DIR/apps.tmp" || exit 1
  printf '%s %s %s %s\n' "$1" "$2" "$3" "$4" >> "$CS_DIR/apps.tmp"
  mv "$CS_DIR/apps.tmp" "$CS_DIR/apps.tsv" || exit 1
  cs_log "record-app pkg=$1 before=$2 written=$3 phase=$4（调用方须保留证据）"
)

# apply/restore 的三方合并：字段当前值与预期不符时保留用户修改并释放所有权。
cs_apps_boot() (
  cs_boot_safe || { cs_log 'XML deferred: system_server 已启动'; exit 1; }
  cs_lock || exit 1
  [ -f "$CS_DIR/apps.tsv" ] || exit 0
  [ ! -f "$CFG.bak" ] && cs_xml_valid "$CFG" || { cs_log 'XML blocked: 格式未知/损坏/AtomicFile .bak'; exit 1; }
  cp "$CFG" "$CS_DIR/original.tmp" && cp "$CFG" "$CS_DIR/merged.tmp" || exit 1
  : > "$CS_DIR/apps.next"
  while read -r cs_pkg cs_before cs_written cs_phase cs_extra; do
    case "$cs_pkg" in ''|*[!A-Za-z0-9._]*) exit 1 ;; esac
    case "$cs_before:$cs_written" in *[!0-9a-z:]*|:*) exit 1 ;; esac
    case "$cs_before" in absent) ;; *[!0-9]*|'') exit 1 ;; esac
    case "$cs_written" in *[!0-9]*|'') exit 1 ;; esac
    [ -z "$cs_extra" ] && [ "${#cs_written}" -le 9 ] || exit 1
    cs_now=$(cs_value "$CS_DIR/merged.tmp" "$cs_pkg")
    cs_next=$cs_phase; cs_target=$cs_now
    if [ "$1" = restore ]; then
      case "$cs_phase" in queued|owned)
        if [ "$cs_now" = "$cs_written" ]; then cs_target=$cs_before; cs_next=restored
        elif [ "$cs_now" = "$cs_before" ]; then cs_next=restored
        else cs_next=kept; fi ;;
      esac
    elif [ "$cs_phase" = queued ]; then
      if [ "$cs_now" = "$cs_before" ] || [ "$cs_now" = "$cs_written" ]; then cs_target=$cs_written; cs_next=owned
      else cs_next=kept; fi
    fi
    if [ "$cs_target" != "$cs_now" ]; then
      cs_merge_field "$CS_DIR/merged.tmp" "$cs_pkg" "$cs_target" "$CS_DIR/field.tmp" || exit 1
      mv "$CS_DIR/field.tmp" "$CS_DIR/merged.tmp" || exit 1
    fi
    printf '%s %s %s %s\n' "$cs_pkg" "$cs_before" "$cs_written" "$cs_next" >> "$CS_DIR/apps.next"
  done < "$CS_DIR/apps.tsv"
  cs_xml_valid "$CS_DIR/merged.tmp" || exit 1
  cs_boot_safe && [ ! -f "$CFG.bak" ] && cmp -s "$CFG" "$CS_DIR/original.tmp" || { cs_log 'XML raced: 未写入，保留待处理账本'; exit 1; }
  if ! cmp -s "$CS_DIR/merged.tmp" "$CFG"; then
    # 保留当前文件 uid/gid/mode/SELinux 标签；同目录 rename 原子提交。
    cp -p "$CFG" "$CFG.einktmp" && cat "$CS_DIR/merged.tmp" > "$CFG.einktmp" || exit 1
    chcon --reference="$CFG" "$CFG.einktmp" 2>/dev/null || restorecon "$CFG.einktmp" 2>/dev/null || :
    mv "$CFG.einktmp" "$CFG" || exit 1
  fi
  mv "$CS_DIR/apps.next" "$CS_DIR/apps.tsv" || exit 1
  cs_log "XML $1 complete（字段级三方比较）"
  rm -f "$CS_DIR/original.tmp" "$CS_DIR/merged.tmp"
)

cs_request_restore() {
  mkdir -p "$CS_DIR" || return 1
  touch "$CS_DIR/stopped" "$CS_DIR/restore_requested" || return 1
  cs_global_restore
  if cs_boot_safe; then cs_apps_boot restore; else cs_log '每应用恢复已排队，下一次 system_server 启动前执行'; fi
}

cs_boot() {
  if cs_disabled || [ -f "$CS_DIR/restore_requested" ]; then cs_apps_boot restore
  else cs_apps_boot apply; fi
}

# Magisk disable 后模块自身脚本不会执行；保留独立、具名且只处理此账本的启动钩子。
# 卸载不删除账本/事件，确保开机能完成字段恢复；不复用配置守护的 good.xml。
cs_prepare_hooks() (
  cs_lock || exit 1
  # 钩子路径用单引号写入，支持中文/空格；拒绝会破坏引用的单引号和换行。
  case "$STATE:$CS_DIR:$CFG:$CS_MODULE:$CS_HOOK_DIR:$CS_SERVICE_DIR" in
    *"'"*|*'
'*) exit 1 ;;
  esac
  mkdir -p "$CS_HOOK_DIR" "$CS_SERVICE_DIR" || exit 1
  cp "$CONFIG_SOURCE" "$CS_DIR/config_state.sh.tmp" && mv "$CS_DIR/config_state.sh.tmp" "$CS_DIR/config_state.sh" || exit 1
  for cs_kind in boot service; do
    cs_dir=$CS_HOOK_DIR; [ "$cs_kind" = service ] && cs_dir=$CS_SERVICE_DIR
    {
      echo '#!/system/bin/sh'
      echo '# inkcontrol_backend A03：仅恢复自有字段，账本保留用于审计。'
      printf "STATE='%s'\nCS_DIR='%s'\nCFG='%s'\nCS_MODULE='%s'\n" "$STATE" "$CS_DIR" "$CFG" "$CS_MODULE"
      echo '. "$CS_DIR/config_state.sh"'
      if [ "$cs_kind" = boot ]; then echo 'cs_boot'
      else
        echo 'until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 10; done'
        echo 'if cs_disabled || [ -f "$CS_DIR/restore_requested" ]; then cs_global_restore; fi'
      fi
    } > "$cs_dir/inkcontrol_backend_config_restore.sh.tmp" || exit 1
    chmod 700 "$cs_dir/inkcontrol_backend_config_restore.sh.tmp" && mv "$cs_dir/inkcontrol_backend_config_restore.sh.tmp" "$cs_dir/inkcontrol_backend_config_restore.sh" || exit 1
  done
)
