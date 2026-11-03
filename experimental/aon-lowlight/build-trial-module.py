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
version=0.1-test
versionCode=1
author=N6 Face Rotation
description=Experimental AON automatic gain limit 32x; retains 5 fps and 200 ms cap. Recognition improvement unverified. Disable and reboot to restore.
''',
    'customize.sh': '''#!/system/bin/sh
SKIPUNZIP=0
ui_print "- N6 AON automatic gain experiment (32x)"
ui_print "- Original vendor files remain unchanged"
MAIN=/vendor/lib64/camera/swangoosefrontmain_3.pb
SUB=/vendor/lib64/camera/swangoosefrontsub_4.pb
[ "$(sha256sum "$MAIN" | cut -d ' ' -f 1)" = ede83ff01cf0f0991232c09944303b09547c17bcc1e018b4d23bf1bcd03cd16f ] || abort "Main AON profile differs from tested version"
[ "$(sha256sum "$SUB" | cut -d ' ' -f 1)" = 0858b8837c24d3453d0c44d71b620f91f2662a4732ab56212174be28240fc17d ] || abort "Inner AON profile differs from tested version"
set_perm "$MODPATH/aon-profile-patcher" 0 0 0755
DEST="$MODPATH/system/vendor/lib64/camera"
mkdir -p "$DEST"
"$MODPATH/aon-profile-patcher" patch "$MAIN" "$DEST/swangoosefrontmain_3.pb" 435.0107421875 || abort "Main profile generation failed"
"$MODPATH/aon-profile-patcher" patch "$SUB" "$DEST/swangoosefrontsub_4.pb" 435.0107421875 || abort "Inner profile generation failed"
set_perm_recursive "$MODPATH/system" 0 0 0755 0644 u:object_r:vendor_file:s0
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
ui_print "- Both profiles generated locally; no vendor file included in ZIP"
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
archive = args.output / 'n6-aon-lowlight-experimental-v0.1.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for name in files:
        z.write(STAGE / name, name)
    z.write(binary, 'aon-profile-patcher')
print(json.dumps({'zip': str(archive), 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
                  'files': list(files) + ['aon-profile-patcher'], 'contains_vendor_pb': False}))
