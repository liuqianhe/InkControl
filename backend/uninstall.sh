#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/lib/common.sh"
ic_prepare_restore
cs_request_restore
ic_log '模块卸载，恢复账本和用户备份保留'
