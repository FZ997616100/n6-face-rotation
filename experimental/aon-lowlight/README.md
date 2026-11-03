# AON 弱光 32x 实验（可选 Root 模块）

这是独立、可选的 Root 实验，主 APK 不依赖它。仅在本机 OPPO Find N6 / ColorOS 17 固件上验证过安装和加载；其他机型、固件以及其他 Root 管理器未实测。不作为默认安装项，也不保证弱光识别率改善。

本实验在两份 AON 配置中追加最高 32x 自动增益档，保留原 200 ms 曝光上限和 5 fps 参数。它修改厂商共用的 AON 配置，因此可能影响其他使用 AON 的系统功能，**不遵循主 APK 中所选的应用范围**。

## 从源码构建

需要 Python 3 和已安装的官方 Android NDK；脚本不下载工具、不连接手机、不读取本机厂商文件。本次公开构建在 Windows、NDK 28.2.13676358 上核验；Linux/macOS 的构建入口未实测。

```powershell
python experimental/aon-lowlight/build.py --ndk "C:/Android/Sdk/ndk/28.2.13676358"
```

也可用 `ANDROID_NDK_HOME` 或 `ANDROID_NDK_ROOT` 指定 NDK。`--output` 可指定产物目录；默认写入仓库的 `outputs/aon-lowlight/`。目标为 ARM64 Android API 35，使用 C++17，生成 `n6-aon-lowlight-experimental-v0.1.zip`。不保证 ZIP 的时间戳、压缩字节或跨 NDK 版本的二进制完全一致。

ZIP 仅含 `module.prop`、`customize.sh`、`post-fs-data.sh` 和自编译的 `aon-profile-patcher`。不包含厂商 PB、厂商 native blob、设备日志、用户配置或签名密钥。

## 兼容校验与恢复

安装脚本读取设备原有的两份 PB；只有 SHA256 同时匹配以下本机固件基线，才在模块目录生成副本。原 `/vendor` 文件不被覆写。

| 设备原文件 | SHA256 |
| --- | --- |
| `/vendor/lib64/camera/swangoosefrontmain_3.pb` | `ede83ff01cf0f0991232c09944303b09547c17bcc1e018b4d23bf1bcd03cd16f` |
| `/vendor/lib64/camera/swangoosefrontsub_4.pb` | `0858b8837c24d3453d0c44d71b620f91f2662a4732ab56212174be28240fc17d` |

这两个文件哈希是安装兼容边界；脚本不另行检查型号或系统 build，也不能凭哈希匹配保证其他设备行为一致。补丁器另外验证原九行档位结构和值，保留原九行及未知字段，仅追加克隆行。

安装后须重启才能重新加载 DSP 配置。停用或卸载模块后也须重启才能恢复。每次开机在覆盖挂载前检查原 PB 哈希，若系统更新导致不匹配，会写入 `disable` 和 `skip_mount`，阻止继续挂载旧配置；安装时不匹配则直接拒绝。遇到拒绝时请勿删除或绕过校验。

## 验证边界

本机曾观察到候选加载后的硬件编程迹象，用户主观反馈“有点用但一般”。I2C 编程记录不是独立寄存器 readback，也不是光学增益或识别率测量。没有同场、同条件的原 16x 对照，因此不能据此宣称识别率、暗光距离或稳定性提升已经证实。

本目录保留已测 ZIP 所用的补丁器源码和相同模块脚本内容；构建入口只将本机固定路径改为可传入的官方 NDK 路径，并将产物写至独立输出目录。完整研究目录、厂商原件、设备记录和密钥不属于公开源码。
