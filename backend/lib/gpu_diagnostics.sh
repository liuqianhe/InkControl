#!/system/bin/sh
# 只按本次启动配置取证。所有入口在子 shell 内运行，不污染 service 的 pid/last 等变量。
# 每 boot：GPU 日志 1024 KiB × 3，首故障 logcat 768 KiB、runtime 64 KiB。
# 最多保留当前及最近三个历史 boot，总量约 16 MiB（另有少量固定元数据）。

ic_gpu_boot_id_valid() {
    [ "${#1}" -eq 36 ] || return 1
    printf '%s\n' "$1" | grep -qxE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
}

ic_gpu_starttime() {
    case "$1" in ''|*[!0-9]*) return 1;; esac
    # comm 可能含空格和右括号；从最后一个右括号后计数，starttime 是余下第 20 项。
    sed 's/^.*) //' "${IC_GPU_PROC:-/proc}/$1/stat" 2>/dev/null | awk 'NF>=20 && $1!="Z" && $1!="X" {print $20}'
}

ic_gpu_owner_alive() {
    case "$1:$2" in *[!0-9:]*|:*|*:) return 1;; esac
    [ "$(ic_gpu_starttime "$1")" = "$2" ] && kill -0 "$1" 2>/dev/null
}

ic_gpu_prepare() {
    [ "$(awk -F= '$1=="display.gpu" {print $2; exit}' "$IC_BOOT" 2>/dev/null)" = 1 ] || return 1
    gpu_boot=$(cat "${IC_GPU_PROC:-/proc}/sys/kernel/random/boot_id" 2>/dev/null) || return 1
    ic_gpu_boot_id_valid "$gpu_boot" || return 1
    [ -d "$IC_STATE" ] || return 1
    gpu_state=$(cd "$IC_STATE" && pwd -P) || return 1
    gpu_root=$gpu_state/gpu-diagnostics
    [ ! -L "$gpu_root" ] || return 1
    mkdir -p "$gpu_root" || return 1
    gpu_dir=$gpu_root/$gpu_boot
    [ ! -L "$gpu_dir" ] || return 1
    mkdir -p "$gpu_dir" || return 1
    [ ! -L "$gpu_dir/managed" ] || return 1
    : > "$gpu_dir/managed" || return 1

    # 只清理直接子目录中的、由本模块创建的规范 boot_id；不跟随符号链接。
    # 当前 boot 始终保留；历史按目录 mtime 排序，避免 UUID 字典序误删最近证据。
    for gpu_old in "$gpu_root"/*; do
        [ "$gpu_old" != "$gpu_dir" ] && [ -d "$gpu_old" ] && [ ! -L "$gpu_old" ] || continue
        ic_gpu_boot_id_valid "${gpu_old##*/}" || continue
        [ -f "$gpu_old/managed" ] && [ ! -L "$gpu_old/managed" ] || continue
        printf '%s %s\n' "$(stat -c %Y "$gpu_old")" "${gpu_old##*/}"
    done | sort -rn | awk 'NR>3 {print $2}' | while IFS= read -r gpu_old_id; do
        ic_gpu_boot_id_valid "$gpu_old_id" || continue
        [ "$gpu_old_id" != "$gpu_boot" ] && [ ! -L "$gpu_root/$gpu_old_id" ] || continue
        rm -rf -- "$gpu_root/$gpu_old_id"
    done
}

ic_gpu_diagnostics_start() (
    umask 077
    ic_gpu_prepare || exit 1
    gpu_lock=$gpu_dir/logger.lock
    if ! mkdir "$gpu_lock" 2>/dev/null; then
        [ -d "$gpu_lock" ] && [ ! -L "$gpu_lock" ] && [ ! -L "$gpu_lock/owner" ] || exit 1
        # 缺少 owner 视为另一个 service 正在启动，不能抢锁。
        read -r gpu_pid gpu_time < "$gpu_lock/owner" || exit 1
        ic_gpu_owner_alive "$gpu_pid" "$gpu_time" && exit 0
        # 回收也需互斥，并重新读 owner；只用 rmdir 移除空锁，不递归误删新锁。
        mkdir "$gpu_lock/reap" 2>/dev/null || exit 1
        if read -r gpu_pid gpu_time < "$gpu_lock/owner" && ! ic_gpu_owner_alive "$gpu_pid" "$gpu_time"; then
            rm -f "$gpu_lock/owner"
        fi
        rmdir "$gpu_lock/reap" 2>/dev/null
        rmdir "$gpu_lock" 2>/dev/null && mkdir "$gpu_lock" 2>/dev/null || exit 1
    fi
    # 拒绝已存在的日志链接，避免 logcat 跟随链接写到取证目录之外。
    for gpu_file in gpu.log gpu.log.1 gpu.log.2; do
        if [ -L "$gpu_dir/$gpu_file" ]; then rmdir "$gpu_lock"; exit 1; fi
    done
    # -n 是历史文件数，加上当前文件共三个。后台进程不继承 service 的终端或管道。
    logcat -b main -b system -b crash -v threadtime -f "$gpu_dir/gpu.log" -r 1024 -n 2 \
        'EinkGPU:V' 'EGL:V' 'libEGL:V' '*:S' </dev/null >/dev/null 2>&1 &
    gpu_pid=$!
    gpu_time=$(ic_gpu_starttime "$gpu_pid")
    # 快速退出可能来不及读取 stat；0 仍是完整回执，下一轮能识别失效并重试。
    printf '%s %s\n' "$gpu_pid" "${gpu_time:-0}" > "$gpu_lock/owner"
)

ic_gpu_diagnostics_first_fault() (
    umask 077
    ic_gpu_prepare || exit 1
    # mkdir 即一次性提交点：失败或中途退出也保留第一次现场，不被后续轮询覆盖。
    gpu_fault=$gpu_dir/first-fault
    mkdir "$gpu_fault" 2>/dev/null || exit 0
    gpu_pid=$1
    {
        printf 'boot_id=%s\ntime=%s\n' "$gpu_boot" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        printf 'hwc_pid=%s\nhwc_starttime=%s\n' "$gpu_pid" "$(ic_gpu_starttime "$gpu_pid")"
        printf 'service_pid=%s\nservice_starttime=%s\n' "$$" "$(ic_gpu_starttime "$$")"
        if [ -f "$gpu_dir/logger.lock/owner" ] && [ ! -L "$gpu_dir/logger.lock" ] && [ ! -L "$gpu_dir/logger.lock/owner" ]; then
            printf 'logger_pid_starttime='
            head -c 128 "$gpu_dir/logger.lock/owner"
        fi
    } > "$gpu_fault/identity.txt"
    head -c 65536 "$IC_STATE/runtime.json" > "$gpu_fault/runtime.json" 2>/dev/null
    # 保留三个缓冲的完整 tag（包括崩溃上下文），不清空系统缓冲。
    # KILL 超时可处理不响应 TERM 的 logcat；timeout 缺失时快速失败，保护照常执行。
    timeout -s KILL 3 logcat -d -b main -b system -b crash -v threadtime -t 2048 '*:V' \
        2>/dev/null | head -c 786432 > "$gpu_fault/logcat.txt"
    : > "$gpu_fault/attempted"
)
