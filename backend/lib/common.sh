#!/system/bin/sh
# 此文件只读取固定格式配置，不source用户内容，不接受任意命令。
IC_STATE=${IC_STATE:-/data/adb/inkcontrol}
IC_MODULE=${IC_MODULE:-/data/adb/modules/inkcontrol_backend}
IC_VENDOR=${IC_VENDOR:-/vendor/lib64/hw}
IC_STOCK=d1ba01bf135151580c41dc7de13dd1c80876094c8022f58fe9ceecd0c9fb0988
IC_CONFIG=$IC_STATE/configuration.txt
IC_APPLIED=$IC_STATE/applied.txt
IC_BOOT=$IC_STATE/boot.txt
IC_LIB=${IC_LIB:-$MODDIR/lib}

ic_log() { mkdir -p "$IC_STATE"; printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$IC_STATE/events.log"; }
ic_get() { [ -f "$IC_CONFIG" ] && awk -F= -v key="$1" '$1==key {print $2; exit}' "$IC_CONFIG"; }
ic_enabled() {
    [ "$(ic_get "$1")" = 1 ] || return 1
    dependency=$(awk -F'\t' -v key="$1" '$1==key {print $3}' "$IC_LIB/features.tsv")
    [ -z "$dependency" ] || [ "$(ic_get "$dependency")" = 1 ]
}
ic_validate() {
    awk -F= '
      function curve(choices, value,   n, parts, i) {
        if (index("," choices ",", ",@curve,")==0 || substr(value,1,6)!="curve:") return 0
        n=split(substr(value,7), parts, ",")
        if (n!=9) return 0
        for (i=1; i<=n; i++) if (parts[i]!~/^[0-9]+$/ || length(parts[i])>4 || parts[i]+0>2000) return 0
        return 1
      }
      NR==FNR {split($0,s,"\t"); choices[s[1]]=s[2]; known[s[1]]=1; n++; next}
      {if (NF!=2 || seen[$1]++) bad=1}
      $1=="version" {if($2!="1") bad=1; next}
      $1=="revision" {if($2!~/^[0-9]+$/ || length($2)>15) bad=1; next}
      {key=$1; if(sub(/\.value$/,"",key)) {if(!known[key] || $2=="@curve" || (index("," choices[key] ",", "," $2 ",")==0 && !curve(choices[key], $2))) bad=1}
       else if(!known[key] || ($2!="0" && $2!="1")) bad=1}
      END {
        if(!seen["version"] || !seen["revision"]) bad=1
        for(key in known) {
          # rc10 配置没有分模式选项；仅允许新增字段整对缺省，其他字段仍严格检查。
          optional=(key ~ /^display\.(alg\.(clear|reading|fast|extreme|video)|hyst\.(fast|extreme|video))$/)
          if(optional && !seen[key] && !seen[key ".value"]) continue
          if(!seen[key] || !seen[key ".value"]) bad=1
        }
        exit bad
      }
    ' "$IC_LIB/features.tsv" "$1"
}
ic_supported() {
    [ "$(getprop ro.product.device)" = HLTE203T ] && [ "$(getprop ro.build.version.sdk)" = 29 ] && [ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] &&
        { getprop ro.build.display.id; getprop ro.build.version.incremental; } | grep -Fq 'L1732.6.01.02'
}
ic_ready() {
    ic_supported && [ -f "$IC_MODULE/module.prop" ] && [ ! -f "$IC_MODULE/disable" ] && [ ! -f "$IC_MODULE/remove" ] &&
        [ "$(sed -n 's/^version=//p' "$IC_MODULE/module.prop")" = '0.1.0-rc12' ] && [ ! -f "$IC_STATE/error" ] || return 1
    ! ic_module_conflict >/dev/null
}
# 同样替换显示库、配置守护或图标覆盖的 Magisk 模块会与墨控争用同一文件；启用时拒绝加载。
ic_module_conflict() {
    IC_MODULES=${IC_MODULES:-/data/adb/modules}
    for module in eink_display epd_cfg_guard gms_settings_icon_fix a_eink_pipeline_opt a_eink_lock_dither a_eink_lock_motion a_eink_lock_motion2 a_eink_lock_motion3 a_eink_lock_motion4 a_eink_reading_verify a_eink_lock_crop; do
        [ -d "$IC_MODULES/$module" ] && [ ! -f "$IC_MODULES/$module/disable" ] && [ ! -f "$IC_MODULES/$module/remove" ] && { echo "$module"; return 0; }
    done
    return 1
}
ic_properties() {
    # 视频默认走 GPU 蓝噪声。stock 是原厂误差扩散，当前后端有 GPU 实现，但逐行依赖使其明显慢于蓝噪声；只在实验项明确选择时使用。
    sampling=0; cache=0; dither=stock; video=blue
    ic_enabled display.sampling && sampling=1
    ic_enabled experiment.cache && cache=1
    ic_enabled lock.dither && dither=nearest
    ic_enabled experiment.video && video=$(ic_get experiment.video.value)
    setprop debug.inkcontrol.sampling "$sampling" || return 1
    setprop debug.inkcontrol.cache "$cache" || return 1
    setprop debug.eink.lock.dither "$dither" || return 1
    setprop debug.eink.video.dither "$video" || return 1
    # default 保留既有路径：包括锁屏点量化和旧版视频算法选择。
    # 关闭选项或 GPU 后端时明确复位属性，避免停用后遗留之前的选择。
    for ic_scope in clear reading fast extreme video; do
        ic_algorithm=default
        if ic_enabled "display.alg.$ic_scope"; then
            ic_algorithm=$(ic_get "display.alg.$ic_scope.value")
        fi
        case "$ic_scope:$ic_algorithm" in
            clear:default|clear:stock|clear:nearest|clear:bayer16|reading:default|reading:stock|reading:nearest|reading:bayer16|fast:default|fast:bayer|fast:blue|fast:threshold|extreme:default|extreme:bayer|extreme:blue|extreme:threshold|video:default|video:blue|video:bayer|video:stock|video:threshold) ;;
            *) return 1 ;;
        esac
        setprop "debug.eink.alg.$ic_scope" "$ic_algorithm" || return 1
    done
    for ic_scope in fast extreme video; do
        ic_hysteresis=default
        if ic_enabled "display.hyst.$ic_scope"; then
            ic_hysteresis=$(ic_get "display.hyst.$ic_scope.value")
        fi
        case "$ic_hysteresis" in default|on|off) ;; *) return 1 ;; esac
        setprop "debug.eink.hyst.$ic_scope" "$ic_hysteresis" || return 1
    done
}
ic_prepare_restore() {
    STATE=$IC_STATE; CFG=/data/system/epd_mode_cfg.xml
    CS_MODULE=$IC_MODULE; CS_HOOK_DIR=/data/adb/post-fs-data.d; CS_SERVICE_DIR=/data/adb/service.d
    CONFIG_SOURCE=$IC_LIB/config_state.sh
    . "$CONFIG_SOURCE"
}
ic_apply_hot() {
    ic_properties || return 1
    ic_prepare_restore
    cs_prepare_hooks || return 1
    if ic_enabled display.dynamic; then
        rm -f "$CS_DIR/stopped" "$CS_DIR/restore_requested"
        previous=$(awk -F= '$1=="display.dynamic" {print $2}' "$IC_APPLIED" 2>/dev/null)
        if [ "$previous" != 1 ] && [ -f "$CS_DIR/dynamic.tsv" ]; then
            phase=$(awk '{print $3}' "$CS_DIR/dynamic.tsv")
            case "$phase" in restored|kept)
                mkdir -p "$CS_DIR/history"
                cp "$CS_DIR/dynamic.tsv" "$CS_DIR/history/dynamic-$(date +%s)-$$.tsv" || return 1
                rm -f "$CS_DIR/dynamic.tsv" "$CS_DIR/install_dynamic" ;;
            esac
        fi
        cs_global_apply || return 1
    else cs_global_restore || return 1; fi
    cp "$IC_CONFIG" "$IC_APPLIED.tmp" && mv "$IC_APPLIED.tmp" "$IC_APPLIED" || return 1
    ic_ready && setprop sys.inkcontrol.ready 1
    return 0
}
