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
      function number(choices, value,   n) {
        if (index("," choices ",", ",@number,")==0 || value!~/^[0-9]+$/) return 0
        n=value+0
        if (length(value)>3 || n<1 || n>999) return 0
        return 1
      }
      function pkglist(list,   n, parts, j) {
        if (list=="") return 1
        n=split(list, parts, ",")
        if (n>200) return 0
        for (j=1; j<=n; j++) if (parts[j]!~/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$/ || length(parts[j])>255) return 0
        return 1
      }
      function apps(choices, value,   mode, semi, second, white, black) {
        if (index("," choices ",", ",@apps,")==0) return 0
        if (value=="") return 1
        semi=index(value, ";")
        if (semi==0) {
          second=index(value, ":")
          if (second>0) {
            mode=substr(value, 1, second-1)
            if (mode!="whitelist" && mode!="blacklist") return 0
            return pkglist(substr(value, second+1))
          }
          return (value=="whitelist" || value=="blacklist")
        }
        mode=substr(value, 1, semi-1)
        if (mode!="whitelist" && mode!="blacklist") return 0
        second=index(substr(value, semi+1), ";")
        if (second==0) return 0
        second=semi+second
        if (index(substr(value, second+1), ";")>0) return 0
        white=substr(value, semi+1, second-semi-1)
        black=substr(value, second+1)
        return pkglist(white) && pkglist(black)
      }
      function countdowns(choices, value,   n, parts, i, d, y, m, dd, dim, step) {        if (index("," choices ",", ",@countdowns,")==0) return 0
        if (value=="") return 1
        n=split(value, parts, ";")
        # 新格式 12 段「事项;日期;农历;显示」×3；旧格式 6 段「事项;日期」×3（农历=否、显示=是）
        if (n!=12 && n!=6) return 0
        step=(n==12)?4:2
        for (i=1; i<=n; i+=step) {
          if (length(parts[i])>48) return 0
          d=parts[i+1]
          if (d!="") {
            if (d !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/) return 0
            y=substr(d,1,4)+0; m=substr(d,6,2)+0; dd=substr(d,9,2)+0
            if (m<1 || m>12 || dd<1) return 0
            if (step==4 && parts[i+2]=="1") {
              if (dd>30) return 0   # 农历月最大 30 天；大小月/闰月语义由 App 侧完整校验
            } else {
              dim=(m==4||m==6||m==9||m==11)?30:(m==2?((y%4==0&&(y%100!=0||y%400==0))?29:28):31)
              if (dd>dim) return 0
            }
          }
          if (step==4 && parts[i+2]!="0" && parts[i+2]!="1") return 0
          if (step==4 && parts[i+3]!="0" && parts[i+3]!="1") return 0
        }
        return 1
      }
      function pkgone(choices, value) {
        # 单个应用包名（「截屏便签」那一格换成的应用）
        if (index("," choices ",", ",@package,")==0) return 0
        if (value=="") return 1
        return pkglist(value)
      }
      function pkgs(choices, value) {
        # 纯包名逗号列表（「目标阅读器」），不带白／黑名单模式
        if (index("," choices ",", ",@packages,")==0) return 0
        if (value=="") return 1
        return pkglist(value)
      }
      function fontpath(value,   n) {
        # 字体目录或字体名：绝对路径/文件名，不含等号、分号、控制字符与 shell 元字符
        if (value=="") return 1
        if (length(value)>200) return 0
        if (value ~ /[=;$`"|&<>*?!]/) return 0
        return 1
      }
      NR==FNR {split($0,s,"\t"); choices[s[1]]=s[2]; known[s[1]]=1; n++; next}
      {if (NF!=2 || seen[$1]++) bad=1}
      $1=="version" {if($2!="1") bad=1; next}
      $1=="revision" {if($2!~/^[0-9]+$/ || length($2)>15) bad=1; next}
      # 旧版残留键已删除：「字体2」（font2.*）并入「字体」；「Google 设置图标修复」（fix.google）、
      # 「隐藏锁屏通知」（lock.notifyhide）并入「锁屏通知」、「过渡动画总开关」（motion.master）、
      # 「全刷扫过」（motion.gc_clear）、「体感息屏唤醒」（wave.master）下线；
      # 「字体换肤」（desktop.font）下线，全局「字体」（font.dir）已完全取代它；
      # 「截屏便签那个位置」（qs.pinlaunch）下线，它原来是原厂那一格的四选一；现在那一格就是
      # 动作表里的「截屏便签」，隐藏／换应用／无都用 qs.panel.btnN 表达；
      # 「锁屏时长」（sys.lock.timeout）整体撤回（那版只在本地试过、没发布），
      # 从后一版降回时配置里会残留这两行，同样放行，App 下次保存时自然清掉；
      # 「设备状态」四项逐项开关（lock.ext.wifi/bt/audio/airplane）现在是正式功能，已并入 known；
      # 旧配置里可能残留同名行，legacy 里继续放行只写了半行的老数据。
      # 旧配置的 lock.notify.value=ext 也照旧放行（ext 是纯字母，走 fontpath 兜底即通过）；
      # App 侧解析时会把它迁成 lock.ext=1 并在下次保存时重写。
      # 「亮屏时间」的档位一开始按秒存（60/120/300/600/1800），后来改成分钟（1/2/5/10/30）：
      # 读到旧的秒值先折算成分钟再校验，否则升级装模块时会判「配置无效」。
      # 容忍旧配置里的残留键，升级不卡开机
      {key=$1; if(sub(/\.value$/,"",key)) {if(key=="sys.screen.timeout") {if($2=="60") $2="1"; else if($2=="120") $2="2"; else if($2=="300") $2="5"; else if($2=="600") $2="10"; else if($2=="1800") $2="30"} legacy=(key ~ /^(font2\.(dir|pick|saf)|fix\.google|lock\.notifyhide|wave\.master|lock\.ext\.(wifi|bt|audio|airplane)|desktop\.font|qs\.pinlaunch|sys\.lock\.timeout)$/) ? 1 : 0; if((!known[key] && !legacy) || $2=="@curve" || $2=="@number" || $2=="@apps" || $2=="@packages" || $2=="@countdowns" || $2=="@path" || $2=="@font" || $2=="@panel" || $2=="@package" || (index("," choices[key] ",", "," $2 ",")==0 && !curve(choices[key], $2) && !number(choices[key], $2) && !apps(choices[key], $2) && !pkgs(choices[key], $2) && !countdowns(choices[key], $2) && !pkgone(choices[key], $2) && !(index("," choices[key] ",", ",@packages,")==0 && fontpath($2)))) bad=1}
       else {legacy=(key ~ /^(font2\.(dir|pick|saf)|fix\.google|lock\.notifyhide|wave\.master|lock\.ext\.(wifi|bt|audio|airplane)|desktop\.font|qs\.pinlaunch|sys\.lock\.timeout)$/) ? 1 : 0; if((!known[key] && !legacy) || ($2!="0" && $2!="1")) bad=1}}
      END {
        if(!seen["version"] || !seen["revision"]) bad=1
        for(key in known) {
          # 旧版配置没有分模式选项、自动刷新项、设备状态总开关与后来新增的开关；仅允许这些新增字段整对缺省，其他字段仍严格检查。
          # lock.ext 是「显示设备状态」总开关、lock.ext.wifi/bt/audio/airplane 是它的四项显示开关，
          # lock.ext.edit 是收着这四项的页面（@panel，值恒为空串）；
          # 旧配置里都没有，缺省按「关」（总开关）/「显示」（四项，App 侧 defaultOn）处理。
          # qs.extend（控制中心拓展）只在 SystemUI 里生效，旧配置里没有，缺省按「关」处理。
          # navbar.white（三大金刚键漂白）同理，旧配置里没有，缺省按「关」处理。
          # fix.safeime（冻结安全输入服务后能输密码）、qs.panel（快捷面板按钮）、qs.panel.close（点完收起面板）、
          # qs.panel.pinsize（截屏便签对齐尺寸）、qs.panel.btn1–5（五个位置的按钮动作）是后来新增的，
          # 旧配置里没有，缺省按「关」处理（pinsize 在 App 侧 defaultOn，缺省按开）。
          # 翻页动画是 0.2.5-r23 新加的一整组，旧配置里整组都没有，缺省按「关」处理。
          # motion.gc_direction / motion.gc_speed / motion.gc_style 是后加的三项，
          # 旧配置里没有，缺省按「自动」/跟随「波形动画速度」/「横向展开」处理。
          # lock.style.order（主题排序）是纯页面项（@panel，值恒为空串），旧配置里整对缺省按「未配置」处理。
          # sys.wifi.sleep / sys.idle.net / sys.idle.sleep / sys.screen.timeout（待机与联网）
          # 是后加的四项，旧配置里没有，缺省按这台机器 ROM 的现值
          # （始终连接 / 1 分钟 / 3 分钟 / 30 分钟）处理。
          # module.autostart（开机自启）是后加的模块设置项：旧配置里整对缺省按「开」处理，
          # 否则升级后第一次开机仍会因系统进程读不到配置而让翻页动画与全刷扫过失效。
          # sys.backup（备份系统设置）与 sys.config（导入与导出设置）都是纯页面项，旧配置里没有，
          # 缺省按「未配置」处理——漏掉它们会让升级时的安装期校验直接判「配置无效」。
          optional=(key ~ /^display\.(alg\.(clear|reading|fast|extreme|video|stock)|hyst\.(fast|extreme|video))$/ || key ~ /^lock\.notifyhide$/ || key ~ /^lock\.ext(\.(wifi|bt|audio|airplane|edit))?$/ || key ~ /^lock\.cover(\.(overlay|time|hint|battery|status|edit|pick|dir|rotate|halo))?$/ || key ~ /^font\.(dir|pick|saf)$/ || key ~ /^font2\.(dir|pick|saf)$/ || key ~ /^refresh\.(master|touch|key|reading|interval|debounce|delay|period|apps)$/ || key ~ /^qs\.extend$/ || key ~ /^navbar\.white$/ || key ~ /^sys\.(backup|config)$/ || key ~ /^sys\.(wifi\.sleep|idle\.(net|sleep)|screen\.timeout)$/ || key ~ /^fix\.safeime$/ || key ~ /^qs\.panel(\.(close|pinsize|btn[1-5]))?$/ || key ~ /^motion\.gc_direction$/ || key ~ /^motion\.gc_speed$/ || key ~ /^motion\.gc_style$/ || key ~ /^pageanim\./ || key ~ /^lock\.style\.order$/ || key ~ /^module\.autostart$/)
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
        [ "$(sed -n 's/^version=//p' "$IC_MODULE/module.prop")" = '0.2.8-r28' ] && [ ! -f "$IC_STATE/error" ] || return 1
    ! ic_module_conflict >/dev/null
}
# 同样替换显示库或配置守护的 Magisk 模块会与墨控争用同一文件；启用时拒绝加载。
# 图标覆盖已下线，gms_settings_icon_fix 不再与本模块争用文件。
ic_module_conflict() {
    IC_MODULES=${IC_MODULES:-/data/adb/modules}
    for module in eink_display epd_cfg_guard a_eink_pipeline_opt a_eink_lock_dither a_eink_lock_motion a_eink_lock_motion2 a_eink_lock_motion3 a_eink_lock_motion4 a_eink_reading_verify a_eink_lock_crop a5pro_epd_probe; do
        [ -d "$IC_MODULES/$module" ] && [ ! -f "$IC_MODULES/$module/disable" ] && [ ! -f "$IC_MODULES/$module/remove" ] && { echo "$module"; return 0; }
    done
    return 1
}
# 开机自启是否要执行：与 App 侧的 defaultOn 保持一致——旧配置里没有这个键也按「开」，
# 只有显式写成 0 才关。这样升级后第一次开机就自动修掉系统进程读不到配置的问题。
ic_autostart() { [ "$(ic_get module.autostart)" != 0 ]; }
# 厂商的进程管控拦下系统进程对墨控 ConfigProvider 的拉起（Unknown authority / it's restricted），
# system_server 与 SystemUI 的 RuntimeConfig 因此一直只拿到「全部关闭」的快照：翻页动画、
# 全刷扫过这些跑在系统进程里的功能全部按原厂瞬切，而锁屏、快捷面板看着完全正常。
# 它不拦 startActivity，所以用无界面的 WakeActivity 把配置进程叫醒一次；Provider 会通知
# 所有已注入进程重读配置。不弹界面、不联网、不需要额外常驻服务。
ic_wake_app() {
    # am start 即使启动失败也可能返回 0（开机未解锁时组件解析不到会打印
    # 「Error type 3 / Activity class ... does not exist」）。必须读回输出判断，
    # 否则会把失败记成成功，日志好看但配置进程其实根本没起来。
    result=$(am start -n com.inkcontrol/com.inkcontrol.ui.WakeActivity 2>&1) || return 1
    printf '%s' "$result" | grep -q 'Error' && return 1
    ic_log '开机自启：已叫醒配置进程'
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
    # display.alg.stock 是「改用原厂显示算法」总开关：开启时五个模式一律不接管，逐模式的选择只保留在配置里。
    ic_stock=0
    if ic_enabled display.alg.stock; then ic_stock=1; fi
    # 原厂算法总开关下发给 native：=1 时 GPU 不接管任何像素量化（A2 二值与灰阶全走原厂 CPU 路径）。
    setprop debug.eink.alg.stock "$ic_stock" || return 1
    for ic_scope in clear reading fast extreme video; do
        ic_algorithm=default
        if [ "$ic_stock" = 0 ] && ic_enabled "display.alg.$ic_scope"; then
            ic_algorithm=$(ic_get "display.alg.$ic_scope.value")
        fi
        case "$ic_scope:$ic_algorithm" in
            clear:default|clear:stock|clear:nearest|clear:bayer16|reading:default|reading:stock|reading:nearest|reading:bayer16|fast:default|fast:stock|fast:bayer|fast:blue|fast:threshold|extreme:default|extreme:stock|extreme:bayer|extreme:blue|extreme:threshold|video:default|video:blue|video:bayer|video:stock|video:threshold) ;;
            *) return 1 ;;
        esac
        setprop "debug.eink.alg.$ic_scope" "$ic_algorithm" || return 1
    done
    for ic_scope in fast extreme video; do
        ic_hysteresis=default
        if [ "$ic_stock" = 0 ] && ic_enabled "display.hyst.$ic_scope"; then
            ic_hysteresis=$(ic_get "display.hyst.$ic_scope.value")
        fi
        case "$ic_hysteresis" in default|on|off) ;; *) return 1 ;; esac
        setprop "debug.eink.hyst.$ic_scope" "$ic_hysteresis" || return 1
    done
    # 融合模式：GPU 显示后端与翻页动画同时启用时，pageanim.master 不再是「挂载哪份 HWC」，
    # 而是把 pageanim.* 配置下发给 libeinkgpu 的分带波形开关。属性即时生效，不需要重启。
    # 只有 GPU 后端开启时才有意义；单独用翻页动画仍走它自己的 HWC 与 resident。
    ic_fusion=0
    if ic_enabled display.gpu && ic_enabled pageanim.master; then ic_fusion=1; fi
    setprop debug.inkcontrol.fusion "$ic_fusion" || return 1
    if [ "$ic_fusion" = 1 ]; then
        # 分带揭示由 GPU 波形层驱动。着色器里 !drive 的区域输出中性驱动字节而不是
        # 控制模板原像素，面板级 forceClearGhosting 定格任何中间帧都不会刷出黑屏；
        # 逐带延迟被压在波形前半段，末帧所有带都走完 LUT 并收敛。
        # 详见 docs/翻页动画融合实现.md 第四、五节。
        setprop debug.inkcontrol.fusion.drive 1 || return 1
        setprop debug.inkcontrol.fusion.armed 1 || return 1
        # 白名单闸门：默认不允许，由 system_server 侧在每次前台切换时按
        # pageanim.readers 名单改写。名单为空即「不触发」，与原版语义一致。
        # 这里只在属性还没被写过时给初值，避免 apply 把当前前台状态冲掉。
        if [ -z "$(getprop debug.inkcontrol.fusion.reader)" ]; then
            setprop debug.inkcontrol.fusion.reader 0 || return 1
        fi
        ic_fusion_reverse=0
        ic_enabled pageanim.reverse && ic_fusion_reverse=1
        setprop debug.inkcontrol.fusion.reverse "$ic_fusion_reverse" || return 1
        ic_fusion_style=$(ic_get pageanim.style.value)
        case "$ic_fusion_style" in
            horizontal) ic_fusion_style=0 ;; vertical) ic_fusion_style=1 ;;
            diag) ic_fusion_style=2 ;; antidiag) ic_fusion_style=3 ;;
            ring) ic_fusion_style=4 ;; center) ic_fusion_style=5 ;; *) ic_fusion_style=0 ;;
        esac
        setprop debug.inkcontrol.fusion.style "$ic_fusion_style" || return 1
        ic_fusion_bands=$(ic_get pageanim.bands.value); [ -n "$ic_fusion_bands" ] || ic_fusion_bands=12
        case "$ic_fusion_bands" in 4|8|12|16|24|32) ;; *) ic_fusion_bands=12 ;; esac
        setprop debug.inkcontrol.fusion.bands "$ic_fusion_bands" || return 1
        ic_fusion_spacing=$(ic_get pageanim.spacing.value); [ -n "$ic_fusion_spacing" ] || ic_fusion_spacing=12
        case "$ic_fusion_spacing" in 6|8|12|16|24|40) ;; *) ic_fusion_spacing=12 ;; esac
        setprop debug.inkcontrol.fusion.spacing "$ic_fusion_spacing" || return 1
    else
        setprop debug.inkcontrol.fusion.drive 0 || return 1
        setprop debug.inkcontrol.fusion.armed 0 || return 1
        setprop debug.inkcontrol.fusion.reader 0 || return 1
    fi
}
# 波形动画能力：system_server（LSPosed motion Hook）用 debug.eink.motion.cap / lockcap
# 判断能否向 HWC 请求波形动画（墨迹晕开/网点长大/扫描线/百叶窗/全刷扫过）。
# 能力列表来自 payload 里 libeinkgpu 的标记；没挂载显示后端时清空，请求会被忽略、等同瞬切。
ic_motion_caps() {
    ic_lib_file=$IC_MODULE/system/vendor/lib64/hw/libeinkgpu.so
    ic_motion=
    ic_lock=
    if [ -f "$ic_lib_file" ]; then
        ic_motion=$(grep -a -o 'EINK_MOTION_V2:[a-z,]*' "$ic_lib_file" 2>/dev/null | head -n 1 | sed 's/^[^:]*://')
        ic_lock=$(grep -a -o 'EINK_LOCK_MOTION_V1:[a-z,]*' "$ic_lib_file" 2>/dev/null | head -n 1 | sed 's/^[^:]*://')
        # 全刷扫过与锁屏过渡依赖不同的标记；锁屏标记缺失时退回主标记里的公共波形。
        [ -n "$ic_lock" ] || ic_lock=$(printf '%s' "$ic_motion" | sed 's/,gcsweep//; s/gcsweep,//; s/^gcsweep$//')
    fi
    setprop debug.eink.motion.cap "$ic_motion" || return 1
    setprop debug.eink.motion.lockcap "$ic_lock" || return 1
}
# ── 系统字体替换 ──────────────────────────────────────────────────────────
# 槽位链：/system/fonts/SysFont.ttf -> /data/fonts/HmctFont.ttf -> 当前字体文件。
# 换字体＝改写 HmctFont.ttf 的指向 + 写 persist.sys.fontfile/fontname，再让界面重建（见 ic_font_activate）。
# 写入前把槽位里原有的字体备份到 $IC_STATE/font-backup.ttf（只备份一次）。
IC_FONT_DIR=${IC_FONT_DIR:-/data/fonts}
ic_font_slot() {
    [ -L "$IC_FONT_DIR/HmctFont.ttf" ] || return 1
    readlink -f "$IC_FONT_DIR/HmctFont.ttf" 2>/dev/null
}
# 列出目录里的 TTF/OTF（含无扩展名的主题字体包），每行「文件名 \t 字节数 \t 绝对路径」。
# 走 root 而不是 App 的存储权限，TF 卡与隐藏目录同样可读。
ic_list_fonts() {
    dir=$1
    [ -n "$dir" ] || dir=/storage/emulated/0/.hmcttheme/com.hmct.theme
    [ -d "$dir" ] || { echo '目录不存在或无法读取' >&2; return 1; }
    for file in "$dir"/*; do
        [ -f "$file" ] || continue
        size=$(stat -c %s "$file" 2>/dev/null) || continue
        [ "$size" -ge 65536 ] || continue
        head -c 4 "$file" | xxd -p | grep -Eq '^(00010000|4f54544f|74727565|74746366)$' || continue
        printf '%s\t%s\t%s\n' "$(basename "$file")" "$size" "$file"
    done
}
IC_FONT_BACKUP=$IC_STATE/font-backup.ttf
# 原厂字体只在第一次替换前备份一次；后来的字体绝不能盖掉这份
ic_font_backup() {
    [ -s "$IC_FONT_BACKUP" ] && return 0
    cp -f "$1" "$IC_FONT_BACKUP" || return 1
    chmod 644 "$IC_FONT_BACKUP" 2>/dev/null
    ic_log "font: 备份原厂字体 $(stat -c %s "$IC_FONT_BACKUP") 字节"
}
# 原子替换字体槽位：先在同目录写临时文件、改好属主/权限/SELinux 标签，再 rename 覆盖。
# 直接 cp 覆盖正在被 mmap 的字体文件，会让渲染中的进程读到半截数据（SystemUI 崩溃、状态栏消失、黑屏），
# rename 走的是「换 inode」：旧进程继续用旧数据，新起的进程读新字体。
ic_font_write() {
    src=$1; dst=$2
    owner=$(stat -c '%u:%g' "$dst") || return 1
    mode=$(stat -c '%a' "$dst" 2>/dev/null) || mode=666
    ctx=$(ls -Z "$dst" 2>/dev/null | awk '{print $1}')
    tmp="$dst.inknew"
    rm -f "$tmp"
    cp -f "$src" "$tmp" || { rm -f "$tmp"; echo '写入临时文件失败（存储空间不足？）' >&2; return 1; }
    chown "$owner" "$tmp" 2>/dev/null
    chmod "$mode" "$tmp" 2>/dev/null
    case "$ctx" in *:*) chcon "$ctx" "$tmp" 2>/dev/null ;; esac
    sync
    mv -f "$tmp" "$dst" || { rm -f "$tmp"; echo '替换字体槽位失败' >&2; return 1; }
    return 0
}
# 微扰 font_scale 触发全局 Activity 重建：每个进程重跑 updateDefaultTypeface() 读新字体。
# 每次把 scale 改成「用户设定值 + 计数器*1.2e-7」再写回原值，既强制重建、又不改变用户实际缩放。
# 已实测（2026-10-25 18:54:15）launcher 在 font_scale 变化后重跑 updateDefaultTypeface。
ic_font_perturb_scale() {
    fs=$(settings get system font_scale 2>/dev/null)
    [ -n "$fs" ] || return 0
    case "$fs" in *.*) ;; *) fs="${fs}.0" ;; esac
    counter=$(cat "$IC_STATE/font_scale_counter" 2>/dev/null || echo 0)
    counter=$((counter + 1))
    new=$(awk -v f="$fs" -v c="$counter" 'BEGIN{printf "%.9f", int(f*100+0.5)/100 + c*0.00000012}')
    settings put system font_scale "$new" 2>/dev/null
    settings put system font_scale "$fs" 2>/dev/null
    echo "$counter" > "$IC_STATE/font_scale_counter"
}
# 让新字体立刻全局生效，不需要重启 zygote——这是海信主题商店「换字体不用重启」的真实做法
# （系统服务 com.android.fontOnline 的 setFont()；2026-10-25 抓日志 + 反编译 framework 核实）：
#   ① HmctFont.ttf 软链指向新字体（每个字体按内容 md5 前缀落到独立路径 inkcontrol2_<hash>.ttf）
#   ② persist.sys.fontfile / fontname / fontchange 三个属性指向它
#   ③ 微扰 font_scale 强制全局 Activity 重建（实测 launcher 重跑 updateDefaultTypeface），
#      桌面、锁屏、状态栏与正在运行的应用全部重读新字体；再发 FONT_CONFIG_CHANGED 广播、重启 SystemUI。
# 框架 Typeface.updateDefaultTypeface() 按 persist.sys.fontfile 的「路径字符串」判断是否重载：路径不变
# 就直接跳过，所以每个字体必须落到不同路径（md5 前缀），否则换了字体框架也不重新加载、看起来“没生效”。
# 字体是全局替换：状态栏、锁屏、桌面与所有应用一起换，不存在“桌面不变”的情况（旧结论是把“整段失效”
# 错当成“局部限制”）。
# 注意：fontname 必须是字体文件名去掉扩展名（系统的取值就是 "193" 这种）；默认字体特例是 sans-serif-medium。
ic_font_activate() {
    target=$1
    [ -f "$target" ] || { echo '字体文件不存在' >&2; return 1; }
    case "$target" in
        /system/*) name=sans-serif-medium ;;
        *) name=$(basename "$target"); name=${name%.*} ;;
    esac
    ln -sfn "$target" "$IC_FONT_DIR/HmctFont.ttf" || { echo '更新字体软链失败' >&2; return 1; }
    chown -h system:system "$IC_FONT_DIR/HmctFont.ttf" 2>/dev/null
    setprop persist.sys.fontfile "$target" || { echo '写入字体属性失败' >&2; return 1; }
    setprop persist.sys.fontname "$name" || { echo '写入字体名失败' >&2; return 1; }
    setprop persist.sys.fontchange 1
    ic_log "font: 激活 $target (name=$name)"
    # 让正在运行的界面也换字：微扰 font_scale 触发全局 Activity 重建（已实测 launcher 重跑 updateDefaultTypeface）
    ic_font_perturb_scale
    # 通知主题服务配置变化（海信 fontOnline 监听此广播重建字体相关资源）
    am broadcast -a hmct.intent.action.FONT_CONFIG_CHANGED >/dev/null 2>&1
    # 系统界面重生：状态栏、通知栏、锁屏立刻换字（它会被系统自动拉起）
    pids=$(pidof com.android.systemui 2>/dev/null)
    [ -n "$pids" ] && kill $pids 2>/dev/null
    return 0
}
# 回到原厂字体。这里**不靠备份内容**判断原厂：首次备份时槽位里装的未必是原厂字体
# （比如用户已经用主题商店换过字体，备份到的就是那个第三方字体）。
# 系统「默认字体」的取值是确定的：fonts_vision.xml 里 sans-serif 走 SysFont.ttf，
# 而 SysFont.ttf -> HmctFont.ttf，切回默认字体就是把软链指回 /system/fonts/Roboto-Medium.ttf。
# 所以直接指回去；备份文件仍保留，作为 /system 那份被改过时的兜底。
IC_FONT_STOCK=/system/fonts/Roboto-Medium.ttf
# 应用字体：字体文件按内容 md5 前缀落到独立路径（inkcontrol2_<hash>.ttf）——
# 框架 Typeface.updateDefaultTypeface() 按 persist.sys.fontfile 的「路径字符串」判重，
# 同一路径就不重载；独立路径保证每次换字体框架都真正重新加载，再由 ic_font_activate
# 全局激活（微扰 font_scale + 广播 + 重启 SystemUI），状态栏、锁屏、桌面与正在运行的
# 应用立刻一起换字，不用重启。
ic_apply_font() {
    src=$1
    [ -n "$src" ] && [ -f "$src" ] || { echo '找不到字体文件' >&2; return 1; }
    case "$src" in
        /storage/*|/sdcard/*|/data/local/tmp/*|/data/user/*|/data/data/*) ;;
        *) echo '字体路径不在允许范围' >&2; return 1 ;;
    esac
    printf '%s' "$src" | grep -q '[[:cntrl:]]' && { echo '字体路径无效' >&2; return 1; }
    # TTF(00010000)、OTF(OTTO)、'true'、TTC(ttcf) 四种合法字体头
    head -c 4 "$src" | xxd -p | grep -Eq '^(00010000|4f54544f|74727565|74746366)$' || {
        echo '不是有效的字体文件（需要 TTF 或 OTF）' >&2; return 1; }
    h=$(md5sum "$src" 2>/dev/null | cut -c1-8)
    dst=$IC_FONT_DIR/inkcontrol2_$h.ttf
    mkdir -p "$IC_STATE" || return 1
    if [ ! -f "$dst" ]; then
        # 新槽位从当前字体复制一份做基底，继承系统的属主、权限与 SELinux 标签
        slot=$(ic_font_slot 2>/dev/null); [ -f "$slot" ] || slot=$IC_FONT_STOCK
        cp -f "$slot" "$dst" 2>/dev/null || return 1
        chmod 666 "$dst" 2>/dev/null
        chcon u:object_r:fonts_app_data_file:s0 "$dst" 2>/dev/null
    fi
    ic_font_backup "$dst" || { echo '备份原厂字体失败' >&2; return 1; }
    ic_font_write "$src" "$dst" || return 1
    ic_font_activate "$dst" || return 1
    size=$(stat -c %s "$dst")
    ic_log "font: $src -> $dst ($size)"
    echo "已应用 $(basename "$src")（$((size / 1024 / 1024)) MB），全局界面（状态栏、锁屏、桌面与正在运行的应用）立即换字，不用重启。"
}
ic_restore_font() {
    if [ -f "$IC_FONT_STOCK" ]; then
        ic_font_activate "$IC_FONT_STOCK" || return 1
        ic_log "font: 恢复原厂字体 -> $IC_FONT_STOCK"
        echo "已恢复原厂字体（$(basename "$IC_FONT_STOCK")），界面已刷新。"
        return 0
    fi
    [ -s "$IC_FONT_BACKUP" ] || { echo '没有原厂字体备份' >&2; return 1; }
    tmp="$IC_STATE/font-restore.tmp"
    cp -f "$IC_FONT_BACKUP" "$tmp" || { echo '读取备份失败' >&2; return 1; }
    dst=$IC_FONT_DIR/inkcontrol2_restore.ttf
    if [ ! -f "$dst" ]; then
        slot=$(ic_font_slot 2>/dev/null); [ -f "$slot" ] || slot=$IC_FONT_STOCK
        cp -f "$slot" "$dst" 2>/dev/null || return 1
        chmod 666 "$dst" 2>/dev/null
        chcon u:object_r:fonts_app_data_file:s0 "$dst" 2>/dev/null
    fi
    ic_font_write "$tmp" "$dst" || { rm -f "$tmp"; return 1; }
    rm -f "$tmp"
    ic_font_activate "$dst" || return 1
    ic_log "font: 恢复原厂字体（来自备份） -> $dst"
    echo "已恢复原厂字体（$(( $(stat -c %s "$dst") / 1024 / 1024 )) MB），界面已刷新。"
}
# 轮换进度：1 起，指向目录里第几张。
IC_COVER_INDEX=$IC_STATE/lockcover.idx
# 进度对应的目录名：和配置里的书封目录不一样就把进度清零，否则换了文件夹会接着上一份的序号走。
IC_COVER_SOURCE=$IC_STATE/lockcover.dir

# 锁屏「透明书封」要叠的那张图：/data/inkcontrol/lockcover.png。
# 为什么不借别的目录：/data/adb 只有 root 进得去，SystemUI 读不到；/data/fonts 是字体目录，
# 书封跟字体没关系。所以单独建 /data/inkcontrol：目录 0771（别人能穿过、列不出内容）、
# 文件 0644，标签沿用 fonts_app_data_file——SystemUI 是 platform_app，只对这个类型有读权限。
# 换图只换文件内容，SystemUI 按 mtime 重新解码，不用重启。
IC_COVER_DIR=${IC_COVER_DIR:-/data/inkcontrol}
IC_COVER_FILE=$IC_COVER_DIR/lockcover.png
# 旧版本把书封放在字体目录里；升级后搬过来，不在字体目录留副本。
IC_COVER_LEGACY=$IC_FONT_DIR/lockcover.png

# 建目录、定权限、搬走旧位置那张。每次写图前都过一遍，开机不用另做初始化。
ic_lockcover_ready() {
    mkdir -p "$IC_COVER_DIR" || return 1
    chmod 0771 "$IC_COVER_DIR" 2>/dev/null
    chcon u:object_r:fonts_app_data_file:s0 "$IC_COVER_DIR" 2>/dev/null
    [ -f "$IC_COVER_LEGACY" ] || return 0
    [ -f "$IC_COVER_FILE" ] || mv -f "$IC_COVER_LEGACY" "$IC_COVER_FILE" 2>/dev/null
    rm -f "$IC_COVER_LEGACY" 2>/dev/null
}

ic_lockcover_state() {
    ic_lockcover_ready
    p=$IC_COVER_FILE
    # 目录与图张数先给：App 的书封页不管有没有图都要显示这两行
    d=$(ic_lockcover_dir)
    echo "dir=$d"
    if [ -n "$d" ] && [ -d "$d" ]; then
        echo "images=$(ic_lockcover_list "$d" | awk 'END{print NR+0}')"
    else
        echo 'images=0'
    fi
    echo "rotate=$( [ "$(ic_get lock.cover.rotate)" = 1 ] && echo 1 || echo 0)"
    if [ ! -f "$p" ]; then
        echo 'present=0'
        return 0
    fi
    echo 'present=1'
    echo "bytes=$(stat -c %s "$p" 2>/dev/null || echo 0)"
    echo "mtime=$(stat -c %y "$p" 2>/dev/null | cut -c1-16)"
    # PNG 的 IHDR 固定在第 16–23 字节：宽、高各 4 字节大端
    echo "size=$(od -An -tu1 -j16 -N8 "$p" 2>/dev/null | awk '{printf "%dx%d", $1*16777216+$2*65536+$3*256+$4, $5*16777216+$6*65536+$7*256+$8}')"
}

# 轮换用：配置里的书封目录，没配或不是绝对路径时输出空。
# 注意读的是 .value 行：不带后缀的那一行是开关（0/1）。
ic_lockcover_dir() {
    d=$(ic_get lock.cover.dir.value)
    case "$d" in /*) printf '%s\n' "$d" ;; esac
}

# 目录里的 PNG，按文件名排序，保证每次轮换顺序固定。
ic_lockcover_list() {
    [ -n "$1" ] && [ -d "$1" ] || return 0
    for f in "$1"/*.png "$1"/*.PNG; do
        [ -f "$f" ] && printf '%s\n' "$f"
    done | sort
}

# 换成目录里的下一张。只在「轮换壁纸」开着、目录里有图时才动；
# 进度存 $IC_STATE/lockcover.idx（1 起），图被删光后按取模回绕。
# 换了文件夹就从头开始：进度旁边记着它属于哪个目录，对不上就先把序号清零。
ic_lockcover_rotate() {
    ic_enabled lock.cover.rotate || return 0
    d=$(ic_lockcover_dir)
    [ -n "$d" ] || return 0
    list=$(ic_lockcover_list "$d")
    [ -n "$list" ] || return 0
    total=$(printf '%s\n' "$list" | awk 'END{print NR+0}')
    [ "$total" -gt 0 ] || return 0
    if [ "$(cat "$IC_COVER_SOURCE" 2>/dev/null)" != "$d" ]; then
        printf '%s\n' "$d" > "$IC_COVER_SOURCE" 2>/dev/null
        printf '%s\n' 0 > "$IC_COVER_INDEX" 2>/dev/null
    fi
    next=$(( $(cat "$IC_COVER_INDEX" 2>/dev/null || echo 0) % total + 1 ))
    file=$(printf '%s\n' "$list" | sed -n "${next}p")
    [ -n "$file" ] || return 0
    ic_lockcover_copy "$file" || return 1
    printf '%s\n' "$next" > "$IC_COVER_INDEX"
    ic_log "lockcover rotate: $next/$total $file"
}

# 换书封文件夹之后调一次：进度归零，轮换开着就直接装第一张，
# 免得锁屏还停在之前手动选的那张、或者接着上一份目录的序号往下走。
ic_lockcover_start() {
    ic_lockcover_ready
    d=$(ic_lockcover_dir)
    printf '%s\n' "$d" > "$IC_COVER_SOURCE" 2>/dev/null
    printf '%s\n' 0 > "$IC_COVER_INDEX" 2>/dev/null
    [ -n "$d" ] && ic_lockcover_rotate 2>/dev/null
    if [ "$(cat "$IC_COVER_INDEX" 2>/dev/null)" = 1 ]; then
        ic_log "lockcover start: $d"
        echo '已换成书封文件夹里的第一张；之后每次熄屏换下一张。'
    else
        echo '书封文件夹已记下，进度从头开始；打开「轮换壁纸」后熄屏才会换图。'
    fi
}

# 换下一张的时机：**解锁之后**，不是熄屏那一刻。
# 锁屏画面在熄屏后每分钟会被原厂重绘一次，如果这期间换了文件，画面就会在眼前换成下一张，
# 屏幕上的上一张还会留成残影。SystemUI 每次解锁往自己的缓存写一个毫秒时间戳，这里读它。
IC_COVER_UNLOCK=/data/user_de/0/com.android.systemui/cache/inkcontrol-unlock

# 解锁后换下一张：亮屏看到的锁屏就是新图，锁屏期间不再动。
# 和「息屏断网」那条循环同一个写法——2 秒一轮只读一个小文件，平时不 fork。
ic_lockcover_watch() {
    # 开机先对齐一次：这时文件里多半还是上一次开机留下的时间戳，
    # 当成「刚解锁」就会把用户开机后看到的那张换掉。
    ic_cover_seen=
    { read -r ic_cover_seen < "$IC_COVER_UNLOCK"; } 2>/dev/null || ic_cover_seen=
    while [ ! -f "$IC_MODULE/disable" ] && [ ! -f "$IC_MODULE/remove" ]; do
        ic_cover_stamp=
        { read -r ic_cover_stamp < "$IC_COVER_UNLOCK"; } 2>/dev/null || ic_cover_stamp=
        # 文件在开机后才出现（第一次解锁）也算变化，照换
        if [ -n "$ic_cover_stamp" ] && [ "$ic_cover_stamp" != "$ic_cover_seen" ]; then
            ic_lockcover_rotate 2>/dev/null || :
            ic_cover_seen=$ic_cover_stamp
        fi
        sleep 2
    done
}

# 校验并写入 $IC_COVER_FILE；成功时不打印任何东西（轮换要用它的返回码）。
ic_lockcover_copy() {
    src=$1
    [ -n "$src" ] && [ -f "$src" ] || { echo '找不到图片文件' >&2; return 1; }
    case "$src" in
        /storage/*|/sdcard/*|/data/local/tmp/*|/data/user/*|/data/data/*) ;;
        *) echo '图片路径不在允许范围' >&2; return 1 ;;
    esac
    printf '%s' "$src" | grep -q '[[:cntrl:]]' && { echo '图片路径无效' >&2; return 1; }
    bytes=$(stat -c %s "$src" 2>/dev/null || echo 0)
    [ "$bytes" -gt 0 ] || { echo '图片是空文件' >&2; return 1; }
    [ "$bytes" -le 8388608 ] || { echo '图片超过 8 MB，请先压小' >&2; return 1; }
    head -c 8 "$src" | od -An -tx1 | tr -d ' \n' | grep -q '^89504e470d0a1a0a$' || {
        echo '不是 PNG 图片（书封图需要带透明通道的 PNG）' >&2; return 1; }
    ic_lockcover_ready || return 1
    dst=$IC_COVER_FILE
    # 先写临时文件再 mv：SystemUI 任何时刻读到的都是完整文件
    cat "$src" > "$dst.new" || { rm -f "$dst.new"; echo '写入失败' >&2; return 1; }
    chmod 644 "$dst.new" 2>/dev/null
    chcon u:object_r:fonts_app_data_file:s0 "$dst.new" 2>/dev/null
    mv -f "$dst.new" "$dst" || { rm -f "$dst.new"; echo '写入失败' >&2; return 1; }
}

ic_lockcover_install() {
    ic_lockcover_copy "$1" || return 1
    bytes=$(stat -c %s "$IC_COVER_FILE" 2>/dev/null || echo 0)
    ic_log "lockcover: $1 -> $IC_COVER_FILE ($bytes bytes)"
    echo "书封图已更新（$((bytes / 1024)) KB）；锁一次屏就能看到。"
}

ic_lockcover_remove() {
    ic_lockcover_ready
    rm -f "$IC_COVER_FILE" || { echo '删除失败' >&2; return 1; }
    ic_log 'lockcover: 已移除'
    echo '书封图已移除；锁屏只剩熄屏前的画面。'
}

# 系统无障碍里按功能开关增删一个组件。系统可能存短名（/.features.x.Y）也可能存全名，两种都认。
# $1 短组件  $2 全组件  $3 期望状态（1 启用 / 0 关闭）
ic_accessibility_sync() {
    ic_short=$1; ic_full=$2; ic_want=$3
    ic_current=$(settings get secure enabled_accessibility_services 2>/dev/null)
    [ "$ic_current" = null ] && ic_current=""
    # 这个设置是冒号分隔（不是逗号）：先剔掉该组件的所有副本，拿到「不含它」的干净列表。
    # 原实现按逗号判断存在性，永远判「不在列表里」，每应用一次配置就再追加一份，实测攒了十几份重复。
    ic_clean=$(printf '%s' "$ic_current" | tr ':' '\n' | grep -vxF "$ic_short" | grep -vxF "$ic_full" | paste -sd: -)
    if [ "$ic_want" = 1 ]; then
        if [ -n "$ic_clean" ]; then ic_next="$ic_clean:$ic_short"; else ic_next="$ic_short"; fi
    else
        ic_next=$ic_clean
    fi
    # 幂等：目标值与现值一致就一个字节都不写，开机与每轮巡检都只读一次设置。
    [ "$ic_next" = "$ic_current" ] && return 0
    if [ -n "$ic_next" ]; then
        settings put secure enabled_accessibility_services "$ic_next" || return 1
    else
        settings delete secure enabled_accessibility_services || return 1
    fi
    if [ "$ic_want" = 1 ]; then
        settings put secure accessibility_enabled 1 || return 1
    fi
    return 0
}
# 自动刷新与体感各有一个随 App 提供的无障碍服务，都按各自开关增删：开关打开即启用、关闭即移除。
# 开机（service.sh）与每次应用配置（inkctl apply）都会走这里，所以重启后不用手动去系统设置里点。
ic_accessibility() {
    if ic_enabled refresh.master; then ic_want_refresh=1; else ic_want_refresh=0; fi
    ic_accessibility_sync com.inkcontrol/.features.refresh.RefreshService \
        com.inkcontrol/com.inkcontrol.features.refresh.RefreshService "$ic_want_refresh" || return 1
    if ic_enabled wave.lock || ic_enabled wave.wake; then ic_want_wave=1; else ic_want_wave=0; fi
    ic_accessibility_sync com.inkcontrol/.features.wave.WaveService \
        com.inkcontrol/com.inkcontrol.features.wave.WaveService "$ic_want_wave" || return 1
    return 0
}
ic_prepare_restore() {
    STATE=$IC_STATE; CFG=/data/system/epd_mode_cfg.xml
    CS_MODULE=$IC_MODULE; CS_HOOK_DIR=/data/adb/post-fs-data.d; CS_SERVICE_DIR=/data/adb/service.d
    CONFIG_SOURCE=$IC_LIB/config_state.sh
    . "$CONFIG_SOURCE"
}
# 翻页动画：把 App 的逐项配置翻译成第三方 native resident 认得的 settings.conf。
# resident 只认这 8 个键；写不成也不阻断其它热应用，配置下一次翻页自然生效。
# 目录固定在墨控自己的状态目录，二进制里的三条硬编码路径已在构建时改到这里。
ic_pageanim_sync() {
    PA_DIR=$IC_STATE/pageanim
    mkdir -p "$PA_DIR" || return 0
    chmod 700 "$PA_DIR"
    PA_STYLE=0
    case "$(ic_get pageanim.style.value)" in
        vertical) PA_STYLE=1 ;; diag) PA_STYLE=2 ;; antidiag) PA_STYLE=3 ;;
        ring) PA_STYLE=4 ;; center) PA_STYLE=5 ;; *) PA_STYLE=0 ;;
    esac
    PA_BANDS=$(ic_get pageanim.bands.value); [ -n "$PA_BANDS" ] || PA_BANDS=12
    PA_SPACING=$(ic_get pageanim.spacing.value); [ -n "$PA_SPACING" ] || PA_SPACING=12
    PA_DEBOUNCE=$(ic_get pageanim.debounce.value); [ -n "$PA_DEBOUNCE" ] || PA_DEBOUNCE=250
    # 只保留包名允许的字符，避免把任意文本写进配置文件。
    PA_READERS=$(ic_get pageanim.readers.value | tr -cd 'A-Za-z0-9_.,')
    PA_REVERSE=0; [ "$(ic_get pageanim.reverse)" = 1 ] && PA_REVERSE=1
    PA_TOUCH=0;   [ "$(ic_get pageanim.touch)" = 1 ]   && PA_TOUCH=1
    PA_KEYS=0;    [ "$(ic_get pageanim.keys)" = 1 ]    && PA_KEYS=1
    PA_TMP="$PA_DIR/settings.conf.tmp"
    {
        echo "readers=$PA_READERS"
        echo "bands=$PA_BANDS"
        echo "spacing=$PA_SPACING"
        echo "style=$PA_STYLE"
        echo "reverse=$PA_REVERSE"
        echo "touch=$PA_TOUCH"
        echo "keys=$PA_KEYS"
        echo "debounce=$PA_DEBOUNCE"
    } > "$PA_TMP" || return 0
    chmod 600 "$PA_TMP"
    mv "$PA_TMP" "$PA_DIR/settings.conf" || return 0
    if ic_enabled pageanim.master; then : > "$PA_DIR/animation.enabled"
    else rm -f "$PA_DIR/animation.enabled"; fi
    return 0
}

# ── 待机与联网 ───────────────────────────────────────────────────────
# 四项都写系统的设置：wifi_sleep_policy、device_idle_constants 与 screen_off_timeout，
# 改完立即生效。
# device_idle_constants 是「键=毫秒」拼成的一整条设置项，别的模块或用户可能也往里写，
# 所以先读现值、只摘掉墨控管的两个键，再把配置拼回去；两个功能都关时整条可能变空，
# 那就删掉该项，让系统回到自己的默认值。
# screen_off_timeout（亮屏时间）是独立的 system 设置项，不与别的键拼串，直接覆盖。
# 选「系统默认」＝墨控不写这项（wifi 策略、亮屏时间直接删掉该项，两个时长键从常量串里摘掉）。
# 两项时长和 WLAN 策略是两层：那项只管 WiFi 射频，这两项管系统什么时候限制应用联网，
# 所以「永不保持」时也照样写，不互相覆盖（移动数据还没断，浅睡限制对两款传输都生效）。
# 配置里的档位换算成毫秒：纯数字 × factor（保网时长存秒，浅睡待机与亮屏时间存分钟）。
# 「系统默认」、空值、非数字一律返回 0，等于不写这一项。
ic_idle_ms() {
    ic_v=$1
    case "$ic_v" in
        '' | *[!0-9]*) echo 0; return 0 ;;
    esac
    # 去掉前导零：shell 算术会把 08 当成八进制而报错。
    while :; do
        case "$ic_v" in
            0?*) ic_v=${ic_v#0} ;;
            *) break ;;
        esac
    done
    echo $((ic_v * $2))
}

# 「亮屏时间」的档位一开始按秒存（60/120/300/600/1800），后来改成分钟（1/2/5/10/30）。
# 旧配置里的秒值先折算成分钟再换算，免得读到 1800 就当成 1800 分钟写进系统。
ic_screen_min() {
    case "$1" in
        60) echo 1 ;;
        120) echo 2 ;;
        300) echo 5 ;;
        600) echo 10 ;;
        1800) echo 30 ;;
        *) echo "$1" ;;
    esac
}

ic_apply_idle() {
    if ic_enabled sys.wifi.sleep; then
        case "$(ic_get sys.wifi.sleep.value)" in
            always) settings put global wifi_sleep_policy 2 ;;
            charging) settings put global wifi_sleep_policy 1 ;;
            never) settings put global wifi_sleep_policy 0 ;;
            default) settings delete global wifi_sleep_policy >/dev/null 2>&1 ;;
        esac || return 1
    fi
    ic_idle_net=0
    ic_idle_sleep=0
    if ic_enabled sys.idle.net; then
        ic_idle_net=$(ic_idle_ms "$(ic_get sys.idle.net.value)" 1000)
    fi
    if ic_enabled sys.idle.sleep; then
        ic_idle_sleep=$(ic_idle_ms "$(ic_get sys.idle.sleep.value)" 60000)
    fi
    ic_idle_now=$(settings get global device_idle_constants 2>/dev/null)
    ic_idle_keep=
    if [ -n "$ic_idle_now" ] && [ "$ic_idle_now" != "null" ]; then
        ic_idle_keep=$(printf '%s' "$ic_idle_now" | tr ',' '\n' \
            | grep -v -E '^light_(after_inactive_to|pre_idle_to)=' | grep -v '^$' | tr '\n' ',')
    fi
    [ "$ic_idle_net" = 0 ] || ic_idle_keep="${ic_idle_keep}light_after_inactive_to=$ic_idle_net,"
    [ "$ic_idle_sleep" = 0 ] || ic_idle_keep="${ic_idle_keep}light_pre_idle_to=$ic_idle_sleep,"
    ic_idle_keep=${ic_idle_keep%,}
    if [ -z "$ic_idle_keep" ]; then
        settings delete global device_idle_constants >/dev/null 2>&1
    else
        settings put global device_idle_constants "$ic_idle_keep" || return 1
    fi
    # 亮屏时间＝系统的「显示 → 休眠」（Settings.System 的 screen_off_timeout，单位毫秒）：
    # 档位存分钟，×60000 换算（旧配置里的秒值先经 ic_screen_min 折算）；「永不」写系统
    # 那一档的原值 2147483645（不是 MAX_VALUE）；「系统默认」＝删掉该项，交回系统自带的值。
    # 0 或非数字（配置被手改坏）不动，免得写坏屏幕超时。
    if ic_enabled sys.screen.timeout; then
        case "$(ic_get sys.screen.timeout.value)" in
            forever) settings put system screen_off_timeout 2147483645 ;;
            default) settings delete system screen_off_timeout >/dev/null 2>&1 ;;
            *)
                ic_screen_ms=$(ic_idle_ms "$(ic_screen_min "$(ic_get sys.screen.timeout.value)")" 60000)
                if [ "${ic_screen_ms:-0}" -gt 0 ]; then
                    settings put system screen_off_timeout "$ic_screen_ms"
                fi
                ;;
        esac || return 1
    fi
    return 0
}

# ── 息屏断网（墨控自己执行）──────────────────────────────────────────
# Android 10 起框架不再按 Settings.Global.wifi_sleep_policy 关 WiFi：实测策略写成 0、
# 系统已进浅睡（mLightState=IDLE），射频仍在。所以这一项由后端自己实现：判断屏幕亮灭，
# 按策略在息屏后关掉 WiFi、亮屏再开回来。
# 只认自己关的那一次（状态文件），不会把用户手动关掉的 WiFi 打开；策略改回「始终连接」/
# 「系统默认」时，下一次 tick 就把之前关掉的 WiFi 开回来。
#
# 这条循环跑在已经休眠的设备上，所以整条链路尽量不 fork 子进程：策略只在配置变化时重算
# （-nt 比对 mtime），背光与充电状态用 shell 内建 read 取，日志超限才清。
# 亮灭判定是例外：背光亮度这台机器并不可靠——e-ink 前光关到 0 时「亮屏」也读得到 0，
# 只看它会把亮屏当成息屏，WiFi 关掉后再也不开回来（息屏断网两档都受影响）。
# 所以背光为 0 时再问一次 PowerManager 的 mWakefulness，结果缓存 5 轮（约 10 秒），
# 背光大于 0 时直接认定亮屏、一次都不用 fork。
#
# svc/dumpsys 必须走绝对路径：Magisk 服务进程的 PATH 以 /sbin 开头，BusyBox 自带的
# svc（runsv 控制器）会把裸命令名抢走，wifi disable/enable 就静默变成打印 Usage。
# 包一层函数是为了让回归测试能用同名函数替身。
ic_wifi_svc() { /system/bin/svc "$@"; }
ic_wifi_dumpsys() { /system/bin/dumpsys "$@"; }
IC_WIFI_MARK=$IC_STATE/wifi-sleep.off
IC_WIFI_LOG=$IC_STATE/idle-wifi.log
IC_WIFI_STAMP=$IC_STATE/wifi-sleep.stamp
IC_WIFI_LOG_MAX=32768
# 设备路径抽成可覆盖变量，回归测试用临时文件替身；真机上一律指向 sysfs / 系统命令。
IC_WIFI_BACKLIGHT=${IC_WIFI_BACKLIGHT:-/sys/class/backlight/sprd_backlight/brightness}
IC_WIFI_NET=${IC_WIFI_NET:-/sys/class/net/wlan0}
IC_WIFI_POWER=${IC_WIFI_POWER:-/sys/class/power_supply/battery/status}

# 当前策略：off（不碰 WiFi）/ never / charging。只在配置变化时算一次。
ic_idle_wifi_mode() {
    ic_enabled sys.wifi.sleep || { echo off; return 0; }
    case "$(ic_get sys.wifi.sleep.value)" in
        never) echo never ;;
        charging) echo charging ;;
        *) echo off ;;
    esac
}

# 插着电时「仅充电保持」不该断网，充电状态变了也要及时开回来。
ic_idle_wifi_charging() {
    ic_power=
    { read -r ic_power < "$IC_WIFI_POWER"; } 2>/dev/null || return 1
    [ "$ic_power" = Charging ] || [ "$ic_power" = Full ]
}

# 这个策略下息屏该不该关 WiFi。返回 0＝要关；只看策略和充电，不看屏幕。
ic_idle_wifi_want_off() {
    case "$1" in
        never) return 0 ;;
        charging) ic_idle_wifi_charging && return 1; return 0 ;;
        *) return 1 ;;
    esac
}

# 动作日志留个上限：WiFi 一直关不掉时会持续追加，超过上限就清空重来。
ic_idle_wifi_log() {
    [ -f "$IC_WIFI_LOG" ] || return 0
    [ "$(wc -c < "$IC_WIFI_LOG" 2>/dev/null || echo 0)" -lt "$IC_WIFI_LOG_MAX" ] || : > "$IC_WIFI_LOG"
}

# 屏幕是不是真的亮着。背光大于 0 一定亮屏（零 fork，正常亮屏走这条）；
# 背光为 0 不能直接判息屏：这台 e-ink 把前光关到 0 时，亮屏读到的也是 0。
# 这时问 PowerManager 的 mWakefulness，结果缓存 5 轮（约 10 秒）省着 fork。
ic_screen_awake() {
    ic_bl=1
    { read -r ic_bl < "$IC_WIFI_BACKLIGHT"; } 2>/dev/null || ic_bl=1
    if [ "$ic_bl" != 0 ]; then ic_awake_ttl=0; return 0; fi
    if [ "${ic_awake_ttl:-0}" -le 0 ]; then
        ic_awake_ttl=5
        # 只在明确读到 Asleep 时判息屏：dumpsys 没起来、输出被截断、开机早期
        # PowerManager 未就绪，一律按亮屏处理——宁可晚关，也不要把 WiFi 误关掉。
        if ic_wifi_dumpsys power 2>/dev/null | grep -q 'mWakefulness=Asleep'; then
            ic_awake_cached=0
        else
            ic_awake_cached=1
        fi
    else
        ic_awake_ttl=$((ic_awake_ttl - 1))
    fi
    [ "${ic_awake_cached:-0}" = 1 ]
}

ic_idle_wifi_tick() {
    ic_wifi_round=$(( ${ic_wifi_round:-0} + 1 ))
    # 网卡在不在，是「关成功 / 开成功」的唯一凭据，不再靠删标记就算完成。
    ic_wlan=0
    [ -e "$IC_WIFI_NET" ] && ic_wlan=1
    if ! ic_screen_awake && ic_idle_wifi_want_off "$1"; then
        if [ "$ic_wlan" = 1 ]; then
            # 网卡还在就再下一次命令：svc 偶尔第一下不生效（开机后首次、Doze 切换瞬间），
            # 每 2 秒重试，直到 wlan0 真的消失；连试 3 次还不行就放慢到每 10 秒一次，
            # 免得驱动异常时每 2 秒拉起一个 java 进程。
            if [ "${ic_wifi_tries:-0}" -lt 3 ] || [ $((ic_wifi_round % 5)) = 0 ]; then
                ic_idle_wifi_log
                : > "$IC_WIFI_MARK" 2>/dev/null
                ic_wifi_svc wifi disable >> "$IC_WIFI_LOG" 2>&1
                ic_wifi_tries=$((ic_wifi_tries + 1))
            fi
        else
            ic_wifi_tries=0
        fi
    elif [ -f "$IC_WIFI_MARK" ]; then
        # 该恢复了：先 enable，只有网卡真的回来才删标记。以前是先删标记再 enable 一次，
        # 亮屏瞬间系统没就绪、这一下失败就再也不会重试，WiFi 永远停在关闭状态。
        if [ "$ic_wlan" = 0 ]; then
            if [ "${ic_wifi_tries:-0}" -lt 3 ] || [ $((ic_wifi_round % 5)) = 0 ]; then
                ic_idle_wifi_log
                ic_wifi_svc wifi enable >> "$IC_WIFI_LOG" 2>&1
                ic_wifi_tries=$((ic_wifi_tries + 1))
            fi
        else
            rm -f "$IC_WIFI_MARK"
            ic_wifi_tries=0
        fi
    else
        ic_wifi_tries=0
    fi
    return 0
}

# 2 秒一轮，正常亮屏时只读一个 sysfs 文件；背光为 0 才按需复核亮灭。
ic_idle_wifi_watch() {
    ic_wifi_round=0
    ic_wifi_tries=0
    # 先解析一次再落基准：基准文件比配置新，-nt 就不成立，第一次进循环不会重算。
    ic_mode=$(ic_idle_wifi_mode)
    : > "$IC_WIFI_STAMP" 2>/dev/null
    while [ ! -f "$IC_MODULE/disable" ] && [ ! -f "$IC_MODULE/remove" ]; do
        # -nt 是 shell 内建测试，不 fork：只有配置被改写时才重新解析策略。
        if [ "$IC_CONFIG" -nt "$IC_WIFI_STAMP" ]; then
            ic_mode=$(ic_idle_wifi_mode)
            : > "$IC_WIFI_STAMP" 2>/dev/null
        fi
        ic_idle_wifi_tick "$ic_mode" 2>/dev/null || :
        sleep 2
    done
}

ic_apply_hot() {
    ic_properties || return 1
    ic_apply_idle || return 1
    ic_accessibility || return 1
    ic_pageanim_sync
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

# ── 系统应用卸载 / 还原 ───────────────────────────────────────────────
# 只对机主（user 0）卸载：分区里的原始 APK 保留，pm install-existing 即可原样收回，
# 还原后仍是系统应用身份（priv-app 的特权权限不会丢）。包名只允许字母数字点下划线，
# 避免把用户输入拼进 shell 命令。
ic_sysapp_valid() {
    case "$1" in
        ''|*[!A-Za-z0-9._]*) return 1 ;;
    esac
    [ ${#1} -le 200 ]
}
ic_sysapp_state() {
    ic_sysapp_valid "$1" || { echo '包名无效' >&2; return 2; }
    if pm list packages --user 0 2>/dev/null | grep -qx "package:$1"; then
        echo 'state=installed'
        pm path "$1" 2>/dev/null | sed -n 's/^package:/path=/p'
    elif pm list packages -u 2>/dev/null | grep -qx "package:$1"; then
        # 系统里还在、但没给机主装：正是 uninstall --user 0 的结果
        echo 'state=removed'
    else
        echo 'state=absent'
    fi
}
ic_sysapp_remove() {
    ic_sysapp_valid "$1" || { echo '包名无效' >&2; return 2; }
    if out=$(pm uninstall --user 0 "$1" 2>&1); then
        echo 'state=removed'
        echo "$out"
        return 0
    fi
    echo "$out" >&2
    return 1
}
ic_sysapp_restore() {
    ic_sysapp_valid "$1" || { echo '包名无效' >&2; return 2; }
    if out=$(pm install-existing --user 0 "$1" 2>&1); then
        echo 'state=installed'
        echo "$out"
        return 0
    fi
    echo "$out" >&2
    return 1
}
