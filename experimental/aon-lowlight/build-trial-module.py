from pathlib import Path
import argparse
import hashlib
import json
import zipfile

parser = argparse.ArgumentParser(description='Package the optional AON experiment without vendor profiles.')
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
STAGE = args.output / 'module-trial'
STAGE.mkdir(parents=True, exist_ok=True)
files = {
    'module.prop': '''id=n6_aon_lowlight_trial
name=N6 AON Low Light - experimental 32x
version=0.2-test
versionCode=2
author=N6 Face Rotation
description=Experimental AON automatic gain limit 32x; retains 5 fps and 200 ms cap. Recognition improvement unverified. Disable and reboot to restore.
''',
    'customize.sh': '''#!/system/bin/sh
SKIPUNZIP=0
ui_print "- N6 AON automatic gain experiment (32x)"
ui_print "- Original vendor files remain unchanged"
MAIN=/vendor/lib64/camera/swangoosefrontmain_3.pb
SUB=/vendor/lib64/camera/swangoosefrontsub_4.pb
MAIN_BASE=ede83ff01cf0f0991232c09944303b09547c17bcc1e018b4d23bf1bcd03cd16f
SUB_BASE=0858b8837c24d3453d0c44d71b620f91f2662a4732ab56212174be28240fc17d
MAIN_32X=9ea078ad7626a3e438da6f1ae3d280856650052f762e0458395ea64db1ba9fdf
SUB_32X=5fd547423c86534b1fd1086a58e646158dd247e7381d0df3fcadc87a0bb448dc
profile_sha() {
    sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1
}
main_hash=$(profile_sha "$MAIN")
sub_hash=$(profile_sha "$SUB")
ui_print "- Main SHA256: ${main_hash:-unreadable}"
ui_print "- Inner SHA256: ${sub_hash:-unreadable}"
set_perm "$MODPATH/aon-profile-patcher" 0 0 0755
DEST="$MODPATH/system/vendor/lib64/camera"
# The patcher creates new files exclusively. A previous install attempt can leave targets in staging.
STAGE_PB=$(mktemp -d "$MODPATH/.aon32-XXXXXX") || abort "Cannot create profile staging directory"
case "$main_hash:$sub_hash" in
    "$MAIN_BASE:$SUB_BASE")
        ui_print "- 已匹配原厂配置，正在生成 32x 副本"
        "$MODPATH/aon-profile-patcher" patch "$MAIN" "$STAGE_PB/${MAIN##*/}" 435.0107421875 || abort "Main profile generation failed"
        "$MODPATH/aon-profile-patcher" patch "$SUB" "$STAGE_PB/${SUB##*/}" 435.0107421875 || abort "Inner profile generation failed"
        ;;
    "$MAIN_32X:$SUB_32X")
        ui_print "- 检测到已挂载的 32x 配置，复用现有副本"
        cp "$MAIN" "$STAGE_PB/${MAIN##*/}" && cp "$SUB" "$STAGE_PB/${SUB##*/}" || abort "Cannot copy existing 32x profiles"
        ;;
    *)
        abort "配置不匹配或不可读。如果已装旧补丁，请停用、重启后再试；仍不匹配请保留本日志。"
        ;;
esac
[ "$(profile_sha "$STAGE_PB/${MAIN##*/}")" = "$MAIN_32X" ] &&
    [ "$(profile_sha "$STAGE_PB/${SUB##*/}")" = "$SUB_32X" ] || abort "Generated module profiles failed SHA256 verification"
mkdir -p "$DEST" && mv -f "$STAGE_PB/${MAIN##*/}" "$STAGE_PB/${SUB##*/}" "$DEST/" || abort "Cannot stage verified profiles"
rmdir "$STAGE_PB"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644 u:object_r:vendor_file:s0
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
ui_print "- Both module profiles verified"
ui_print "- Reboot required for the DSP configuration cache"
''',
    'post-fs-data.sh': '''#!/system/bin/sh
# Check the original files before the module mount so an OTA does not reuse old tuning.
MODDIR=${0%/*}
MAIN=/vendor/lib64/camera/swangoosefrontmain_3.pb
SUB=/vendor/lib64/camera/swangoosefrontsub_4.pb
if [ "$(sha256sum "$MAIN" | cut -d ' ' -f 1)" != ede83ff01cf0f0991232c09944303b09547c17bcc1e018b4d23bf1bcd03cd16f ] ||
   [ "$(sha256sum "$SUB" | cut -d ' ' -f 1)" != 0858b8837c24d3453d0c44d71b620f91f2662a4732ab56212174be28240fc17d ]; then
    touch "$MODDIR/disable" "$MODDIR/skip_mount"
    echo 'Original profile changed; experiment disabled before mounting.' > "$MODDIR/status.txt"
fi
''',
}
for name, data in files.items():
    (STAGE / name).write_text(data, encoding='utf-8', newline='\n')
binary = args.binary
archive = args.output / 'n6-aon-lowlight-experimental-v0.2.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for name in files:
        z.write(STAGE / name, name)
    z.write(binary, 'aon-profile-patcher')
print(json.dumps({'zip': str(archive), 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
                  'files': list(files) + ['aon-profile-patcher'], 'contains_vendor_pb': False}))
