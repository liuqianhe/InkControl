#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/lib/common.sh"
. "$MODDIR/lib/gpu_diagnostics.sh"
# 开机等待、加载检查及运行监测之前开始留存；取证失败不能阻断原有保护。
ic_gpu_diagnostics_start 2>/dev/null || :
[ -f "$IC_STATE/error" ] && exit 1
# 热应用要调用 settings 等系统服务；late_start 时它们可能还没起来，先等开机完成（最多 3 分钟）。
for attempt in $(seq 1 90); do [ "$(getprop sys.boot_completed)" = 1 ] && break; sleep 2; done
[ "$(getprop sys.boot_completed)" = 1 ] || { echo '系统未在 3 分钟内完成启动；增强保持关闭' > "$IC_STATE/error"; exit 1; }
ic_apply_hot || { echo '启动期配置应用失败' > "$IC_STATE/error"; exit 1; }
if [ -f "$MODDIR/system/vendor/lib64/hw/libeinkgpu.so" ]; then
    for attempt in $(seq 1 90); do
        pid=$(pidof android.hardware.graphics.composer@2.1-service | awk '{print $1}')
        if [ -n "$pid" ] && [ "$pid" = "$last" ]; then stable=$((stable + 1)); else last=$pid; stable=0; fi
        if [ "$stable" -ge 20 ]; then
            if grep -q libeinkgpu.so "/proc/$pid/maps"; then echo 0 > "$IC_STATE/pending"; break; fi
        fi
        sleep 2
    done
    if [ "$stable" -lt 20 ] || ! grep -q libeinkgpu.so "/proc/$pid/maps"; then
        ic_gpu_diagnostics_first_fault "$pid" 2>/dev/null || :
        echo '显示服务未稳定加载；已停用，完整重启返回原厂' > "$IC_STATE/error"
        touch "$IC_STATE/disable_hwc" "$MODDIR/disable"
    fi
fi
. "$MODDIR/lib/guard.sh"
next_backup=0
while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
    # 复用 Magisk 启动服务；没有额外 Android 常驻服务。始终读取本次启动配置。
    if [ "$(awk -F= '$1=="display.gpu" {print $2}' "$IC_BOOT")" = 1 ]; then
        ic_gpu_diagnostics_start 2>/dev/null || :
        pid=$(pidof android.hardware.graphics.composer@2.1-service | awk '{print $1}')
        if [ -n "$pid" ]; then
            "$MODDIR/bin/eink_status" status "$pid" > "$IC_STATE/runtime.tmp" 2>/dev/null && mv "$IC_STATE/runtime.tmp" "$IC_STATE/runtime.json"
            if ! grep -q '"helper_loaded":true' "$IC_STATE/runtime.json" || grep -qE '"gpu_errors":[1-9][0-9]*' "$IC_STATE/runtime.json"; then
                ic_gpu_diagnostics_first_fault "$pid" 2>/dev/null || :
                echo '运行监测发现显示后端异常；已禁止下一次加载，完整重启回到原厂' > "$IC_STATE/error"
                touch "$IC_STATE/disable_hwc"
                setprop sys.inkcontrol.ready 0
            fi
        fi
    fi
    now=$(date +%s)
    if [ "$(awk -F= '$1=="guard.config" {print $2}' "$IC_BOOT")" = 1 ] && [ "$now" -ge "$next_backup" ]; then backup_now; next_backup=$((now + 21600)); fi
    sleep 60
done
