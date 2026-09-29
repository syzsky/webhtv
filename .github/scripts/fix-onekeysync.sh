#!/usr/bin/env bash
set -euo pipefail

FILE="app/src/main/java/com/fongmi/android/tv/ui/dialog/OneKeySyncDialog.java"

if [ ! -f "$FILE" ]; then
    echo "⚠️  $FILE 不存在，跳过修复"
    exit 0
fi

# 检查是否需要修复：toRemote 默认值必须是 true（手机→电视方向）
if ! grep -q "private boolean toRemote = true;" "$FILE"; then
    echo "🔧 修复 toRemote 默认值"
    sed -i 's/private boolean toRemote = false;/private boolean toRemote = true;/g' "$FILE"
    sed -i 's/private boolean toRemote = true;/private boolean toRemote = true;/g' "$FILE"
fi

# 检查 mode 参数：推送到 TV 必须是 mode=1
if ! grep -q 'String mode = toRemote ? "1" : "2";' "$FILE"; then
    echo "🔧 修复同步 mode 参数"
    sed -i 's/String mode = toRemote ? "2" : "1";/String mode = toRemote ? "1" : "2";/g' "$FILE"
fi

# 确保本地角色显示正确（发送方 = 手机）
if grep -q 'binding.localRole.setText(toRemote ? R.string.sync_receiver : R.string.sync_sender);' "$FILE"; then
    echo "🔧 修复本地角色显示"
    sed -i 's/binding.localRole.setText(toRemote ? R.string.sync_receiver : R.string.sync_sender);/binding.localRole.setText(toRemote ? R.string.sync_sender : R.string.sync_receiver);/g' "$FILE"
fi

echo "✅ OneKeySyncDialog 修复完成"
