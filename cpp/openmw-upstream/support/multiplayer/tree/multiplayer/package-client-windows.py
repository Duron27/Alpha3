#!/usr/bin/env python3
"""Packages the OpenMW programs of a finished Windows build, with the multiplayer mod built in, into a zip you can share.

    cmake --build . --target client-package          (from the build folder; see multiplayer/package.cmake)
    python multiplayer/package-client-windows.py --build-dir <build> [--config Release] [--out <folder>]

Makes openmw-multiplayer-client-Windows-<cpu>.zip in --out (default: the build folder). The programs, the DLLs that the dependency
script (CI/before_script.msvc.sh) put next to them, the Qt and OpenSceneGraph plugins, resources/, the Visual C++ runtime DLLs
(from Visual Studio's redistributable folder, so a clean machine needs nothing installed), the license and a README.
The build must have been configured with OPENMW_BUILTIN_MULTIPLAYER=ON (the default), which puts the mod into
resources/vfs/mp.omwscripts. Nothing in the build folder is changed.
"""
import argparse, glob, os, platform, re, shutil, subprocess, sys, tempfile, zipfile

# openmw-mp-server.exe has its own zip (target mpserver-bundle)
SKIP_FILES = re.compile(r'\.(pdb|ilk|exp|lib|appdata\.xml)$|^openmw-mp-server\.exe$|^multiplayer$', re.I)
SKIP_DIRS = {'multiplayer'}
REQUIRED = ['openmw.exe', 'openmw.cfg', 'defaults.bin']


def cache_value(build, name):
    try:
        for line in open(os.path.join(build, 'CMakeCache.txt'), errors='replace'):
            if line.startswith(name + ':'):
                return line.split('=', 1)[1].strip()
    except OSError:
        pass
    return None


def find_crt():
    """The folder with the Visual C++ runtime DLLs (msvcp140.dll...) of the newest Visual Studio found."""
    roots = []
    vswhere = os.path.join(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)'), 'Microsoft Visual Studio', 'Installer',
                           'vswhere.exe')
    if os.path.isfile(vswhere):
        out = subprocess.run([vswhere, '-products', '*', '-latest', '-property', 'installationPath'],
                             capture_output=True, text=True).stdout.strip()
        if out:
            roots.append(out)
    for pattern in (r'C:\Program Files*\Microsoft Visual Studio\*\*',):
        roots += glob.glob(pattern)
    for root in roots:
        found = sorted(glob.glob(os.path.join(root, 'VC', 'Redist', 'MSVC', '*', 'x64', 'Microsoft.VC*.CRT')))
        if found:
            return found[-1]
    return None


README = """OpenMW multiplayer client (Windows)
===================================

Unpack the whole folder anywhere (not into Program Files) and run openmw-launcher.exe.

  1. Start openmw-launcher.exe. Under Data Files, point it at your Morrowind installation (the folder with Morrowind.esm; the
     wizard, openmw-wizard.exe, can also install Morrowind from a CD or the Steam files). Make a profile, press Play.
  2. In the main menu press "Multiplayer", type the server's address and port, and join. The multiplayer mod is built in:
     do NOT also add it to openmw.cfg (it would load twice).
  3. To host a game, use openmw-mp-server.exe from the separate server zip.

What is in here
  openmw.exe, openmw-launcher.exe, openmw-wizard.exe, openmw-cs.exe   the game, launcher, installer wizard and the editor
  bsatool, esmtool, niftest, openmw-iniimporter, openmw-essimporter,
  openmw-navmeshtool, openmw-bulletobjecttool                         the command line tools
  resources\\       what they read (scripts, shaders, translations...), including the multiplayer mod
  openmw.cfg ...   their configuration; do not edit (your own goes in Documents\\My Games\\OpenMW\\openmw.cfg)
  *.dll, platforms, styles, imageformats, iconengines, osgPlugins-*   the libraries they use, including the Visual C++ runtime
  LICENSE.txt      the GNU GPL that OpenMW is released under (the source: gitlab.com/duron27/openmw-multiplayer)

Needs Windows 10 or newer (64 bit) and a graphics card with OpenGL 3.3 or newer.
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--build-dir', default='.', help='the finished build (default: the current folder)')
    ap.add_argument('--config', default='Release', help='the configuration folder with the programs (default: Release)')
    ap.add_argument('--source-dir', help='the source tree (default: the one the build was made from)')
    ap.add_argument('--out', help='where the zip goes (default: the build folder)')
    a = ap.parse_args()
    if platform.system() != 'Windows':
        sys.exit('package-client-windows.py is for Windows builds (package-client.py does Linux)')
    build = os.path.abspath(a.build_dir)
    out = os.path.abspath(a.out or build)
    source = os.path.abspath(a.source_dir or cache_value(build, 'CMAKE_HOME_DIRECTORY') or os.path.dirname(build))
    exe_dir = os.path.join(build, a.config)
    if not os.path.isdir(exe_dir):
        exe_dir = build
    for need in REQUIRED:
        if not os.path.isfile(os.path.join(exe_dir, need)):
            sys.exit('missing in %s: %s (build it first: cmake --build . --config %s)' % (exe_dir, need, a.config))
    scripts = os.path.join(exe_dir, 'resources', 'vfs', 'mp.omwscripts')
    if not os.path.isfile(scripts):
        sys.exit('%s is missing (the multiplayer scripts): configure with -DOPENMW_BUILTIN_MULTIPLAYER=ON' % scripts)
    crt = find_crt()
    if not crt:
        sys.exit('cannot find the Visual C++ runtime DLLs (install the Build Tools with the C++ workload)')

    cpu = {'AMD64': 'x86_64', 'ARM64': 'arm64'}.get(platform.machine(), platform.machine())
    name = 'openmw-multiplayer-client-Windows-%s' % cpu
    work = tempfile.mkdtemp(prefix='openmw-client-')
    try:
        stage = os.path.join(work, name)
        for root, dirs, files in os.walk(exe_dir):
            rel = os.path.relpath(root, exe_dir)
            dirs[:] = [d for d in dirs if not (rel == '.' and d in SKIP_DIRS)]
            os.makedirs(os.path.join(stage, rel), exist_ok=True)
            for f in files:
                if not SKIP_FILES.search(f):
                    shutil.copy2(os.path.join(root, f), os.path.join(stage, rel, f))
        print('Visual C++ runtime from %s' % crt)
        for dll in glob.glob(os.path.join(crt, '*.dll')):
            shutil.copy2(dll, stage)
        if os.path.isfile(os.path.join(source, 'LICENSE')):
            shutil.copy2(os.path.join(source, 'LICENSE'), os.path.join(stage, 'LICENSE.txt'))
        else:
            sys.exit('no LICENSE in %s: the GPL text must travel with the programs' % source)
        with open(os.path.join(stage, 'README.txt'), 'w', newline='\r\n') as f:
            f.write(README.replace('\\\\', '\\'))

        os.makedirs(out, exist_ok=True)
        zip_path = os.path.join(out, name + '.zip')
        if os.path.exists(zip_path):
            os.remove(zip_path)
        with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for root, dirs, files in os.walk(stage):
                for f in files:
                    path = os.path.join(root, f)
                    z.write(path, os.path.relpath(path, work))
        print('%s (%d MB)' % (zip_path, os.path.getsize(zip_path) // (1024 * 1024)))
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
