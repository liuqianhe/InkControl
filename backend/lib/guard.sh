# epd_mode_cfg.xml 守护：被 post-fs-data.sh / service.sh / test_guard.sh 引用
# 路径可用环境变量覆盖，方便在测试目录里验证

CFG="${CFG:-/data/system/epd_mode_cfg.xml}"
GUARD_DIR="${GUARD_DIR:-/data/adb/inkcontrol/guard}"
GOOD="$GUARD_DIR/good.xml"
PREV="$GUARD_DIR/good.prev.xml"
LOG="$GUARD_DIR/guard.log"

log() {
  mkdir -p "$GUARD_DIR"
  echo "$(date '+%F %T') $*" >> "$LOG"
  if [ "$(wc -l < "$LOG")" -gt 400 ]; then
    tail -n 200 "$LOG" > "$LOG.tmp" && mv -f "$LOG.tmp" "$LOG"
  fi
}

# 返回值：0 完好；1 损坏但系统能扛住（只会丢设置）；2 会让 system_server 起不来；3 文件不存在
check() {
  f="$1"
  [ -f "$f" ] || return 3
  size=$(wc -c < "$f")
  # 文件系统损坏常见的是整块变成 0 字节
  [ "$size" -gt 0 ] || return 2
  [ "$(tr -d '\000' < "$f" | wc -c)" -eq "$size" ] || return 2
  # 以下两种情况会在 readConfigFileLocked 里抛出未捕获异常
  grep -qE '<[A-Za-z]' "$f" || return 2
  if grep -oE "mode=(\"[^\"]*\"|'[^']*')" "$f" | grep -vqxE "mode=[\"'][-+]?[0-9]{1,9}[\"']"; then
    return 2
  fi
  # 严格比对 FastXmlSerializer 写出的格式
  [ "$(head -n 1 "$f")" = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" ] || return 1
  [ "$(grep -cx '<config>' "$f")" -eq 1 ] || return 1
  [ "$(tail -n 1 "$f")" = "</config>" ] || return 1
  if grep -vxE "<\?xml version='1\.0' encoding='utf-8' standalone='yes' \?>|<config>|</config>|<cfg pkg=\"[A-Za-z0-9._]+\" mode=\"[0-9]{1,9}\" />" "$f" | grep -q .; then
    return 1
  fi
  return 0
}

install_file() {
  tmp="$CFG.guardtmp"
  cp -f "$1" "$tmp" || return 1
  chown 1000:1000 "$tmp"
  chmod 600 "$tmp"
  chcon u:object_r:system_data_file:s0 "$tmp" 2>/dev/null || restorecon "$tmp" 2>/dev/null
  mv -f "$tmp" "$CFG"
}

# 损坏的文件留一份，最多保留 3 份
keep_bad() {
  [ -f "$1" ] || return 0
  cp -f "$1" "$GUARD_DIR/bad-$(date '+%Y%m%d-%H%M%S').xml"
  ls -t "$GUARD_DIR"/bad-*.xml 2>/dev/null | tail -n +4 | while read -r old; do rm -f "$old"; done
}

# 开机时、system_server 启动前调用
guard_boot() {
  mkdir -p "$GUARD_DIR"
  # AtomicFile：.bak 存在时系统会删掉正文、改用 .bak
  if [ -f "$CFG.bak" ]; then
    check "$CFG.bak"; s=$?
    if [ $s -eq 0 ]; then
      log ".bak 存在且完好，交给系统处理"
      return 0
    fi
    log ".bak 已损坏（状态 $s），删除"
    keep_bad "$CFG.bak"
    rm -f "$CFG.bak"
  fi

  check "$CFG"; s=$?
  [ $s -eq 0 ] && return 0

  if [ $s -eq 3 ]; then
    reason="文件不存在"
  else
    reason="文件损坏（状态 $s）"
    keep_bad "$CFG"
  fi

  for b in "$GOOD" "$PREV"; do
    if check "$b"; then
      if install_file "$b" && check "$CFG"; then
        log "$reason，已用 $(basename "$b") 替换"
        return 0
      fi
      log "$reason，用 $(basename "$b") 替换失败"
    fi
  done

  if [ $s -eq 2 ]; then
    # 没有可用备份：移走坏文件，系统按默认值启动，总比卡开机强
    mv -f "$CFG" "$CFG.broken" 2>/dev/null
    log "$reason，且没有可用备份，已移走坏文件，系统将使用默认设置"
  elif [ $s -eq 1 ]; then
    log "$reason，没有可用备份；不影响开机，保持原样"
  fi
}

# 开机完成后调用：当前文件完好且有变化时更新备份
backup_now() {
  mkdir -p "$GUARD_DIR"
  # .bak 存在说明系统正在写，跳过
  [ -f "$CFG.bak" ] && return 0
  tmp="$GUARD_DIR/snap.tmp"
  cp -f "$CFG" "$tmp" 2>/dev/null || return 0
  if check "$tmp"; then
    if ! cmp -s "$tmp" "$GOOD"; then
      [ -f "$GOOD" ] && mv -f "$GOOD" "$PREV"
      mv -f "$tmp" "$GOOD"
      log "备份已更新（$(grep -c '<cfg ' "$GOOD") 条应用设置）"
    fi
  fi
  rm -f "$tmp"
}
