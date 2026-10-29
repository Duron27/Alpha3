#!/usr/bin/env python3
"""Packages the OpenMW programs of a finished build, with the multiplayer mod built in, into zips you can share.

    cmake --build . --target client-package          (from the build folder; see multiplayer/package.cmake)
    python3 multiplayer/package-client.py --build-dir <build> [--source-dir <source>] [--out <folder>] [--only portable|light]

Makes two zips in --out (default: the build folder):
  openmw-multiplayer-client-<system>-<cpu>-portable.zip  the programs, their files and the libraries they need
  openmw-multiplayer-client-<system>-<cpu>-light.zip     the same without the libraries (they come from the system)

Whatever programs were built are packaged (openmw is required). Nothing in the build folder is changed. The multiplayer scripts
come from <build>/multiplayer (the copy CMake makes; the source folder's copy if there is none) and are added to the built-in
scripts, whatever OPENMW_BUILTIN_MULTIPLAYER is set to in the build. Linux only for now: the portable zip needs ldd, and the
programs need as new a C library as the machine that built them (the README in each zip says which).
"""
import argparse, glob, os, platform, re, shutil, subprocess, sys, tempfile

PROGRAMS = ['openmw', 'openmw-launcher', 'openmw-wizard', 'openmw-cs', 'bsatool', 'esmtool', 'niftest',
            'openmw-iniimporter', 'openmw-essimporter', 'openmw-navmeshtool', 'openmw-bulletobjecttool']
QT_PROGRAMS = ['openmw-launcher', 'openmw-wizard', 'openmw-cs']
SUFFIX = '.' + platform.machine()  # the real programs are <name>.x86_64; a script with the plain name starts each (as upstream's package)
# files next to the programs that they read at run time (the first two are needed by every package)
ROOT_FILES = ['openmw.cfg', 'defaults.bin', 'defaults-cs.bin', 'gamecontrollerdb.txt']
# files in resources/ that exist only because somebody put them in a build folder (experiments), not the product
STRAY = ['vfs/shaders/badMB2.omwfx', 'vfs/shaders/bluetest.omwfx']
# what any desktop Linux provides itself, and upstream's package also leaves to it: the C library, the graphics stack (OpenGL, EGL,
# drm; the Vulkan loader is bundled, the programs link it whether or not a machine can use it), X11 / xcb / Wayland and xkbcommon, input and audio services, udev, dbus. Never bundled: they must match the machine.
EXCLUDE = re.compile(r'^(ld-linux|libc\.|libm\.|libdl\.|libpthread|librt\.|libresolv|libutil\.|libnsl|libanl|libBrokenLocale|'
    r'libthread_db|libGL\.|libGLX|libGLdispatch|libOpenGL|libEGL|libGLESv|libdrm|libgbm|libX11\.|libX11-xcb|libXau|libXdmcp|libXext\.|libxcb\.|libwayland|'
    r'libpulse|libpipewire|libsystemd|libudev|libdbus-1|libaudit|libcap\.|libnvidia|'
    r'libjack|libdecor|libinput|libmtdev|libgtk-|libgdk-|libatk|libsmartcols|libepoxy|libz\.|'
    r'libstdc|libgcc_s)')
# ...but the portable package DOES carry the C++ runtime (libstdc++, libgcc_s): the programs and libraries need a very recent one, the
# system's may be older, and a newer one is safe to use in place of an older one. glib, harfbuzz, freetype, fontconfig, pcre2 and the
# like are bundled too, as upstream's package does: a desktop without them is common.
RUNTIME = re.compile(r'^(libstdc\+\+|libgcc_s)')
OSG_PLUGINS = ['bmp', 'dae', 'dds', 'freetype', 'jpeg', 'osg', 'png', 'serializers_osg', 'tga']  # CMakeLists.txt USED_OSG_PLUGINS
# what the Qt programs (launcher, wizard, OpenMW-CS) need to show a window: the platform back ends, SVG, OpenGL for X11/Wayland
QT_PLUGINS = {'platforms': ['libqxcb.so', 'libqwayland-egl.so', 'libqwayland-generic.so', 'libqoffscreen.so'],
              'imageformats': ['libqsvg.so', 'libqjpeg.so', 'libqico.so', 'libqgif.so'],
              'iconengines': ['libqsvgicon.so'],
              'xcbglintegrations': ['libqxcb-glx-integration.so', 'libqxcb-egl-integration.so'],
              'wayland-decoration-client': None, 'wayland-graphics-integration-client': None,
              'wayland-shell-integration': ['libxdg-shell.so', 'libwl-shell-plugin.so']}  # (not the KDE layer-shell)


def sh(*args, **kw):
    try:
        return subprocess.run(args, capture_output=True, text=True, **kw)
    except FileNotFoundError:  # a tool that is not installed (rpm on a Debian system...)
        return subprocess.CompletedProcess(args, 127, '', 'not found')


def library_deps(path):
    found = {}
    for line in sh('ldd', path).stdout.splitlines():
        m = re.match(r'\s*(\S+) => (/\S+)', line)
        if m:
            found[m.group(1)] = os.path.realpath(m.group(2))
    return found


def max_symbol_version(files, prefix):
    """The newest GLIBC_x.y (or GLIBCXX_x.y.z) any of the files asks for: the system must have at least that."""
    best = ()
    for f in files:
        for token in re.findall(prefix + r'(\d+(?:\.\d+)*)', sh('objdump', '-T', f).stdout):
            version = tuple(int(x) for x in token.split('.'))
            if version > best:
                best = version
    return '.'.join(str(x) for x in best) if best else None


def copy_tree(src, dst, skip=()):
    for root, dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        os.makedirs(os.path.join(dst, rel), exist_ok=True)
        for f in files:
            if os.path.normpath(os.path.join(rel, f)) in skip:
                continue
            shutil.copy2(os.path.join(root, f), os.path.join(dst, rel, f))


def cache_value(build, name):
    try:
        for line in open(os.path.join(build, 'CMakeCache.txt'), errors='replace'):
            if line.startswith(name + ':'):
                return line.split('=', 1)[1].strip()
    except OSError:
        pass
    return None


def find_plugin_dirs(libs):
    """OpenSceneGraph's plugin folder and Qt's, found next to the libraries the programs actually use."""
    osg = None
    for n, real in libs.items():
        if n.startswith('libosgDB.so'):
            found = glob.glob(os.path.join(os.path.dirname(real), 'osgPlugins-*'))
            osg = found[0] if found else None
    qt = None
    for n, real in libs.items():
        if n.startswith('libQt6Core.so'):
            lib = os.path.dirname(real)
            for cand in (os.path.join(lib, 'qt6', 'plugins'), os.path.join(lib, 'qt', 'plugins'),
                         os.path.join(lib, '..', 'qt6', 'plugins')):
                if os.path.isdir(cand):
                    qt = os.path.normpath(cand)
                    break
    return osg, qt


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--build-dir', default='.', help='the finished build (default: the current folder)')
    ap.add_argument('--source-dir', help='the source tree (default: the one the build was made from)')
    ap.add_argument('--out', help='where the zips go (default: the build folder)')
    ap.add_argument('--only', choices=['portable', 'light'])
    ap.add_argument('--strip', default=None, help='the strip tool (default: the build\'s, else "strip")')
    a = ap.parse_args()
    if platform.system() != 'Linux':
        sys.exit('package-client.py only knows Linux so far (the libraries to bundle are found with ldd)')
    build = os.path.abspath(a.build_dir)
    out = os.path.abspath(a.out or build)
    source = a.source_dir or cache_value(build, 'CMAKE_HOME_DIRECTORY') or os.path.dirname(build)
    source = os.path.abspath(source)
    strip = a.strip or cache_value(build, 'CMAKE_STRIP') or 'strip'
    system = '%s-%s' % (platform.system(), platform.machine())

    present = [p for p in PROGRAMS if os.path.isfile(os.path.join(build, p))]
    if 'openmw' not in present:
        sys.exit('%s has no openmw program: build it first (cmake --build .)' % build)
    for p in PROGRAMS:
        if p not in present:
            print('not built, left out: %s' % p)
    root_files = [f for f in ROOT_FILES if os.path.isfile(os.path.join(build, f))]
    for need in ('openmw.cfg', 'defaults.bin'):
        if need not in root_files:
            sys.exit('missing in the build folder: %s' % need)
    if not os.path.isdir(os.path.join(build, 'resources')):
        sys.exit('missing in the build folder: resources')
    mod = os.path.join(build, 'multiplayer', 'mod')
    if not os.path.isfile(os.path.join(mod, 'mp.omwscripts')):
        mod = os.path.join(source, 'multiplayer', 'mod')
    if not os.path.isfile(os.path.join(mod, 'mp.omwscripts')):
        sys.exit('cannot find the multiplayer mod (multiplayer/mod/mp.omwscripts) in the build or the source folder')
    builtin_base = os.path.join(source, 'files', 'data', 'builtin.omwscripts')
    builtin_mw = os.path.join(source, 'files', 'data-mw', 'builtin.omwscripts.in')
    for f in (builtin_base, builtin_mw):
        if not os.path.isfile(f):
            sys.exit('missing in the source folder: %s (is --source-dir right?)' % f)

    work = tempfile.mkdtemp(prefix='openmw-client-')
    try:
        stage = os.path.join(work, 'shared')  # what both packages hold; the folder inside each zip is made from it
        os.makedirs(stage)

        print('programs (stripped)')
        for p in present:
            dst = os.path.join(stage, p + SUFFIX)
            r = sh(strip, '--strip-all', '-o', dst, os.path.join(build, p))
            if r.returncode:  # no strip tool: ship them as they are (much bigger)
                print('  could not strip %s (%s), copied as is' % (p, r.stderr.strip()[:60]))
                shutil.copy2(os.path.join(build, p), dst)
            os.chmod(dst, 0o755)

        print('resources, with the multiplayer scripts built in')
        res = os.path.join(stage, 'resources')
        copy_tree(os.path.join(build, 'resources'), res, skip=[os.path.normpath(s) for s in STRAY])
        for sub in ('scripts/mp', 'l10n', 'textures'):  # what files/data/CMakeLists.txt copies into resources/vfs
            if os.path.isdir(os.path.join(mod, sub)):
                copy_tree(os.path.join(mod, sub), os.path.join(res, 'vfs', sub))
        # the mod's script list is a built-in list of its own (the engine loads every resources/vfs/*.omwscripts), so a
        # synced copy from a server can replace it
        shutil.copy2(os.path.join(mod, 'mp.omwscripts'), os.path.join(res, 'vfs', 'mp.omwscripts'))
        builtin = open(builtin_base).read()
        open(os.path.join(res, 'vfs', 'builtin.omwscripts'), 'w').write(builtin)
        # vfs-mw/builtin.omwscripts is files/data-mw/builtin.omwscripts.in with the base list put in
        os.makedirs(os.path.join(res, 'vfs-mw'), exist_ok=True)
        open(os.path.join(res, 'vfs-mw', 'builtin.omwscripts'), 'w').write(
            open(builtin_mw).read().replace('@BUILTIN_SCRIPTS@', builtin))
        for f in root_files:
            shutil.copy2(os.path.join(build, f), os.path.join(stage, f))
        # what upstream's package carries besides the programs: the license (GPL: it must travel with them), the icon, desktop entries
        if os.path.isfile(os.path.join(source, 'LICENSE')):
            shutil.copy2(os.path.join(source, 'LICENSE'), os.path.join(stage, 'LICENSE.txt'))
        if os.path.isfile(os.path.join(build, 'resources', 'openmw.png')):
            shutil.copy2(os.path.join(build, 'resources', 'openmw.png'), os.path.join(stage, 'openmw.png'))
        for d in ('org.openmw.launcher.desktop', 'org.openmw.cs.desktop'):
            if os.path.isfile(os.path.join(build, d)):
                os.makedirs(os.path.join(stage, 'desktop files'), exist_ok=True)
                shutil.copy2(os.path.join(build, d), os.path.join(stage, 'desktop files', d))

        # --- libraries
        libs = {}
        for p in present:
            libs.update(library_deps(os.path.join(build, p)))
        if not libs:
            sys.exit('ldd found no libraries: cannot make the portable package here')
        osgdir, qtroot = find_plugin_dirs(libs)
        plugin_files = []
        if osgdir:
            plugin_files = [os.path.join(osgdir, 'osgdb_%s.so' % n) for n in OSG_PLUGINS
                            if os.path.isfile(os.path.join(osgdir, 'osgdb_%s.so' % n))]
            if len(plugin_files) != len(OSG_PLUGINS):
                print('warning: OpenSceneGraph plugins missing in %s' % osgdir)
        elif any(n.startswith('libosgDB') for n in libs):
            print('warning: no OpenSceneGraph plugin folder found: the portable package will not read textures')
        for f in plugin_files:
            libs.update(library_deps(f))
        qtfiles = []
        if qtroot and any(p in present for p in QT_PROGRAMS):
            for d, names in QT_PLUGINS.items():
                for f in sorted(glob.glob(os.path.join(qtroot, d, '*.so'))):
                    if names is None or os.path.basename(f) in names:
                        qtfiles.append((d, f))
                        libs.update(library_deps(f))
        bundle = {n: r for n, r in libs.items() if not EXCLUDE.match(n) or RUNTIME.match(n)}
        # libraries that are loaded while running, so that ldd cannot see them: SDL2 can be a thin layer (sdl2-compat, Fedora's) that
        # opens the real SDL3 itself
        for n, real in list(bundle.items()):
            if n.startswith('libSDL2-2.0.so') and b'libSDL3.so.0' in open(real, 'rb').read():
                sdl3 = os.path.join(os.path.dirname(real), 'libSDL3.so.0')
                if os.path.exists(sdl3):
                    print('SDL2 here is sdl2-compat: libSDL3 goes along')
                    for dn, dreal in library_deps(sdl3).items():
                        if not EXCLUDE.match(dn) or RUNTIME.match(dn):
                            bundle.setdefault(dn, dreal)
                    bundle['libSDL3.so.0'] = os.path.realpath(sdl3)

        # the C library the files need, for the README and the checks the start scripts make
        program_paths = [os.path.join(build, p) for p in present]
        glibc = max_symbol_version(program_paths + list(bundle.values()), 'GLIBC_') or \
            (sh('ldd', '--version').stdout.splitlines() or ['', ''])[0].split()[-1]
        glibcxx = max_symbol_version(program_paths, 'GLIBCXX_') or '?'

        # --- scripts and notes
        osgname = os.path.basename(osgdir) if osgdir else 'osgPlugins'
        # no LD_LIBRARY_PATH: the programs and bundled libraries find lib/ by their own RPATH, and a variable would also
        # reach tools they start (grep, gdb for the crash log) and make those load the bundled libraries instead of their own
        env = ('export OSG_LIBRARY_PATH="$HERE/lib/%s"\n'
               'export QT_PLUGIN_PATH="$HERE/plugins"\n' % osgname)
        header = '#!/bin/sh\n# %s\nHERE="$(cd "$(dirname "$0")" && pwd)"\ncd "$HERE" || exit 1\n'
        what = {
            'openmw': 'Starts OpenMW, the game. Options are passed on: ./openmw --help',
            'openmw-launcher': 'Starts the launcher: choose your Morrowind data and settings here first.',
            'openmw-cs': 'Starts OpenMW-CS, the construction set.',
            'openmw-wizard': 'Starts the installation wizard (finds or installs Morrowind).',
        }
        # checks that run before a program starts: say what is wrong instead of a bare loader error
        check = '''
# Can everything this program needs be found? If not, say what is wrong instead of a bare loader error.
if [ -z "$OPENMW_SKIP_CHECK" ] && command -v ldd >/dev/null 2>&1; then
    problems=$(ldd "./$PROGRAM" 2>&1 | grep 'not found' | sed 's/^[[:space:]]*//' | sort -u)
    if [ -n "$problems" ]; then
        {
            echo "$PROGRAM cannot start on this system:"
            echo "$problems" | sed 's/^/    /'
            echo
            if echo "$problems" | grep -q GLIBC; then
                echo "This system's C library is older than this package needs (glibc @GLIBC@ or newer; this one has $(ldd --version 2>&1 | head -n 1 | sed 's/.* //'))."
                echo "The programs cannot run on it. See README.txt: they can run in a container with a recent system."
            else
                echo "These libraries belong to a normal desktop Linux system (OpenGL/graphics drivers, X11 or Wayland, fontconfig...)."
                echo "Install them with your package manager and try again."
            fi
            echo "./diagnose.sh prints what to send when asking for help. (OPENMW_SKIP_CHECK=1 skips this check.)"
        } >&2
        exit 1
    fi
fi
'''.replace('@GLIBC@', glibc)
        bodies = {}
        for prog in present:
            bodies[prog] = lambda portable, prog=prog: (header % what.get(prog, 'Runs %s.' % prog)) + \
                'PROGRAM=%s%s\n' % (prog, SUFFIX) + (env if portable else '') + check + 'exec "./$PROGRAM" "$@"\n'
        programs_list = ' '.join(p + SUFFIX for p in present)
        bodies['diagnose.sh'] = lambda portable: (header % 'Prints what is needed to find out why a program does not start. Send the output when asking for help.') + \
            (env if portable else '') + '''echo "== system"
( . /etc/os-release 2>/dev/null && echo "$PRETTY_NAME" )
uname -srm
echo "glibc: $(ldd --version 2>&1 | head -n 1)"
echo "this package needs glibc @GLIBC@ or newer@RUNTIME@"
echo "session: ${XDG_SESSION_TYPE:-?}  DISPLAY=${DISPLAY:-}  WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-}"
for p in @PROGRAMS@; do
    [ -x "./$p" ] || continue
    echo "== $p"
    ldd "./$p" 2>&1 | grep 'not found' || echo "everything it needs was found"
done
echo "== where the main libraries are loaded from"
LD_DEBUG=libs ./openmw@SUFFIX@ --version 2>&1 | grep -E "calling init: .*(libosg\\.so|libMyGUIEngine|libstdc)" | sed 's/.*calling init: //' | head -n 6
echo "== graphics"
(glxinfo -B 2>/dev/null || echo "(glxinfo is not installed)") | head -n 14
'''.replace('@GLIBC@', glibc).replace('@SUFFIX@', SUFFIX).replace('@PROGRAMS@', programs_list).replace(
                '@RUNTIME@', '' if portable else ' and libstdc++ with GLIBCXX_%s' % glibcxx)

        commit = sh('git', '-C', source, 'rev-parse', '--short', 'HEAD').stdout.strip() or 'unknown'
        dirty = ' plus uncommitted changes' if sh('git', '-C', source, 'status', '--porcelain', '--untracked-files=no').stdout.strip() else ''
        direct = set()
        for p in present:
            for line in sh('readelf', '-d', os.path.join(build, p)).stdout.splitlines():
                m = re.search(r'NEEDED.*\[(.*)\]', line)
                if m and not EXCLUDE.match(m.group(1)):
                    direct.add(m.group(1))
        packages = set()  # (rpm systems only) the packages the libraries come from
        for path in [bundle[n] for n in direct if n in bundle] + plugin_files + [f for _, f in qtfiles]:
            r = sh('rpm', '-qf', '--qf', '%{NAME}\\n', path)
            if r.returncode == 0:
                packages.update(r.stdout.split())
        # where each package comes from: the distribution itself, a Copr project, RPM Fusion (FFmpeg)
        origin = {'copr': {}, 'rpmfusion': []}
        for pkg in sorted(packages):
            vendor = sh('rpm', '-q', '--qf', '%{VENDOR}', pkg).stdout
            if 'Copr' in vendor:
                user = vendor.split()[-1]
                project = '?'
                for repo in glob.glob('/etc/yum.repos.d/_copr:copr.fedorainfracloud.org:%s:*.repo' % user):
                    project = os.path.basename(repo)[:-5].split(':')[-1]
                origin['copr'].setdefault('%s/%s' % (user, project), []).append(pkg)
            elif 'RPM Fusion' in vendor:
                origin['rpmfusion'].append(pkg)

        def readme(portable):
            title = 'OpenMW with the multiplayer mod built in (%s, built from %s%s)' % (system, commit, dirty)
            text = title + '\n' + '=' * len(title) + '\n'
            text += """
What is in this folder
  openmw                the game
  openmw-launcher       the launcher: tell it where Morrowind is, change settings
  openmw-wizard         finds or installs Morrowind
  openmw-cs             the construction set (editor)
  bsatool esmtool niftest openmw-iniimporter openmw-essimporter openmw-navmeshtool openmw-bulletobjecttool
                        the command-line tools
  resources/            what they read (scripts, shaders, translations...), including the multiplayer mod
  openmw.cfg ...        their configuration; do not edit (your own goes in ~/.config/openmw/openmw.cfg)
  *@SUFFIX@            the real programs. Do not run these: run the file of the same name without the suffix, a script that
                        tells the program where its libraries are (./openmw-launcher, ./openmw ...)
  diagnose.sh           prints what to send when asking for help
  LICENSE.txt           the GNU GPL that OpenMW is released under (the source: gitlab.com/duron27/openmw-multiplayer)
  (programs that were not built are not in the folder)

You need
  * Morrowind (the game's data files; OpenMW does not include them)
  * a Linux system with glibc %s or newer (Fedora 44, current Arch or openSUSE Tumbleweed...): the programs were built on
    one and will not start on an older system, whatever else is installed (on an older one see "If it does not start")
  * OpenGL drivers (Mesa or the vendor's) and X11 or Wayland
""" % glibc
            if portable:
                text += ("  * nothing else: the libraries are in lib/ (and plugins/). Start the programs by their plain names\n"
                         "    (./openmw-launcher, ./openmw ...), not the files ending in @SUFFIX@.\n")
            else:
                text += ("  * these libraries from your system (this is the light package: they are not included). OpenMW needs its own\n"
                         "    patched OpenSceneGraph (github.com/OpenMW/osg): the ordinary OSG will not do.\n")
                if packages:
                    text += "    On Fedora:\n"
                    for project, pkgs in sorted(origin['copr'].items()):
                        text += "      sudo dnf copr enable %s      # for: %s\n" % (project, ' '.join(pkgs))
                    if origin['rpmfusion']:
                        text += ("      # FFmpeg (%s) is from RPM Fusion (rpmfusion.org/Configuration); Fedora's own\n"
                                 "      # libavcodec-free works as well\n" % ' '.join(origin['rpmfusion']))
                    text += "      sudo dnf install %s\n" % ' '.join(sorted(packages))
                text += ("    Other distributions: the same libraries under their names (OpenMW's OpenSceneGraph 3.6 with its plugins,\n"
                         "    MyGUI 3.4, Bullet, SDL2, OpenAL, FFmpeg, Boost, yaml-cpp, lz4, SQLite, ICU, unshield, Qt 6 with SVG\n"
                         "    support). If one is missing, ldd ./openmw | grep 'not found' shows which.\n"
                         "    The portable package has all of them included.\n")
            text += """
Starting
  1. ./openmw-launcher     pick your Morrowind "Data Files" folder and the game files, press Play.
     Without the launcher: put  data="/path/to/Morrowind/Data Files"  and  content=Morrowind.esm  in ~/.config/openmw/openmw.cfg
     and run ./openmw  (or ./openmw --data "/path/to/Data Files" --content Morrowind.esm).
  2. Multiplayer: Multiplayer on the main menu -> Host a game, or Join a server (address, port, password), then start or load
     a character. The mod is part of this package: do NOT also add it to openmw.cfg (data=...multiplayer/mod and
     content=mp.omwscripts), it would load twice. A dedicated server is the separate openmw-mp-server zip.

Your own settings, data paths and saves live in ~/.config/openmw and ~/.local/share/openmw, not in this folder.

If it does not start
  * Start the programs by their plain names (./openmw-launcher, ./openmw ...), not the files ending in @SUFFIX@: the scripts
    check the system first and name what is missing, and (portable package) tell the programs where lib/ is.
  * A message about "GLIBC_@GLIBC@ not found" or a library such as libosg.so.162 means the system is older than this package needs.
    The programs cannot run on it. The way around that is a container with a recent system: with Distrobox
        distrobox create --name openmw --image fedora:44 && distrobox enter openmw
    unpack this zip inside it (it shares your files, display and graphics) and start it from there.
  * ./diagnose.sh prints the system, what each program cannot find and where its libraries load from. Send that output when asking
    for help.
"""
            return text.replace('@GLIBC@', glibc).replace('@SUFFIX@', SUFFIX)

        def finish(variant, portable):
            target = os.path.join(work, 'openmw-multiplayer-client')
            shutil.copytree(stage, target, symlinks=True)
            for name, body in bodies.items():
                path = os.path.join(target, name)
                open(path, 'w').write(body(portable))
                os.chmod(path, 0o755)
            if portable:
                lib = os.path.join(target, 'lib')
                os.makedirs(os.path.join(lib, osgname))
                for n, real in bundle.items():
                    shutil.copy2(real, os.path.join(lib, n))
                for f in plugin_files:
                    shutil.copy2(f, os.path.join(lib, osgname, os.path.basename(f)))
                for d, f in qtfiles:  # Qt looks in <program folder>/plugins by itself
                    os.makedirs(os.path.join(target, 'plugins', d), exist_ok=True)
                    shutil.copy2(f, os.path.join(target, 'plugins', d, os.path.basename(f)))
                # where Qt looks for its plugins: this Qt does not work it out from where it is, so say so (relative to the programs)
                open(os.path.join(target, 'qt.conf'), 'w').write('[Paths]\nPlugins = plugins\n')
                # a path to lib/ inside the programs themselves, so that they find it even if started without their script
                if shutil.which('patchelf'):
                    for prog in present:
                        sh('patchelf', '--force-rpath', '--set-rpath', '$ORIGIN/lib', os.path.join(target, prog + SUFFIX))
                    # the bundled libraries keep the search paths they were built with (a system folder such as
                    # /usr/lib/<arch>/libproxy); make them look next to themselves first
                    for n in bundle:
                        if os.path.isfile(os.path.join(lib, n)) and not os.path.islink(os.path.join(lib, n)):
                            sh('patchelf', '--force-rpath', '--set-rpath', '$ORIGIN', os.path.join(lib, n))
                    for d, f in qtfiles:
                        sh('patchelf', '--force-rpath', '--set-rpath', '$ORIGIN/../../lib',
                           os.path.join(target, 'plugins', d, os.path.basename(f)))
                else:
                    print('note: patchelf not found, the programs rely on their scripts to find lib/')
            open(os.path.join(target, 'README.txt'), 'w').write(readme(portable))
            final = os.path.join(out, 'openmw-multiplayer-client-%s-%s.zip' % (system, variant))
            if os.path.exists(final):
                os.remove(final)
            os.makedirs(out, exist_ok=True)
            r = sh('cmake', '-E', 'tar', 'cf', final, '--format=zip', 'openmw-multiplayer-client', cwd=work)
            shutil.rmtree(target)
            if r.returncode:
                sys.exit('could not make the zip: ' + r.stderr)
            print('%-82s %5.0f MB' % (final, os.path.getsize(final) / 1e6))

        for variant, portable in (('portable', True), ('light', False)):
            if a.only and a.only != variant:
                continue
            finish(variant, portable)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
