"""Build with an installed official Android NDK; no downloads or device access."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT'),
                    help='Installed Android NDK directory (or ANDROID_NDK_HOME / ANDROID_NDK_ROOT).')
parser.add_argument('--output', type=Path, default=HERE.parents[1] / 'outputs' / 'aon-lowlight')
args = parser.parse_args()
if args.ndk is None:
    parser.error('Specify --ndk or set ANDROID_NDK_HOME / ANDROID_NDK_ROOT; no NDK is downloaded.')
host = {'win32': 'windows-x86_64', 'linux': 'linux-x86_64', 'darwin': 'darwin-x86_64'}.get(sys.platform)
if host is None:
    parser.error('Supported NDK hosts: Windows, Linux, macOS.')
compiler = args.ndk / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin' / ('clang++.exe' if sys.platform == 'win32' else 'clang++')
if not compiler.is_file():
    parser.error(f'NDK compiler not found: {compiler}')
args.output.mkdir(parents=True, exist_ok=True)
binary = args.output.resolve() / 'aon-profile-patcher'
subprocess.run([str(compiler), '--target=aarch64-linux-android35', '-std=c++17', '-O2',
                '-Wall', '-Wextra', '-Werror', '-fPIE', '-pie', '-static-libstdc++',
                str(HERE / 'aon-profile-patcher.cpp'), '-o', str(binary)], check=True)
subprocess.run([sys.executable, str(HERE / 'build-trial-module.py'),
                '--binary', str(binary), '--output', str(args.output.resolve())], check=True)
