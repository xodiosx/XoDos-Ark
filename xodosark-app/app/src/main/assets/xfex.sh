#!/data/data/app.xodos2/files/usr/bin/bash
# =====================================================================
#  xfex  –  Wine (FEX) launcher
#  All environment variables live in $PREFIX/opt/env  and are sourced
#  below.  Nothing here is hard-coded anymore.
# =====================================================================

#kill -9 $(pgrep -f "termux.x11") &>/dev/null

# -------- LOAD ALL ENVIRONMENT VARIABLES --------
. /data/data/app.xodos2/files/usr/opt/env
PREFIX=/data/user/0/app.xodos2/files/usr
rm -f $PREFIX/tmp/ntsync_userspace.v10*

WINE_BIN="$PREFIX/opt/winece/arm64-v8a/bin"

for file in "$WINE_BIN"/*; do
    [ -f "$file" ] && [ -x "$file" ] || continue
    ln -sf "$file" "$PREFIX/bin/$(basename "$file")"
done
#pkill -f wine
pkill -f ib.exe
pkill -f zenity
$PREFIX/opt/winece/arm64-v8a/bin/wineserver -k

(
while true; do
  echo "# 🖼️ Initializing graphics...⌛" || exit 0
  sleep 7
  echo "# ⚙️ Starting Wine...⌛" || exit 0
  sleep 7
  echo "# 💻 Preparing windows environment...⌛" || exit 0
  sleep 7
  pkill -f zenity
done
) | env MANGOHUD=0 DISABLE_MANGOHUD=1 LD_PRELOAD="" GTK_THEME=Adwaita:dark zenity --progress --pulsate --auto-close --no-cancel \
--title="XoDos windows launcher" &

PROGRAM="$1"

# ---------- Auto-detect resolution ----------
if [ "$STARTUP_COMPATIBILITY_MODE" = "1" ]; then
	autores=""
else
	autores=$(DISPLAY=:0 xrandr | grep current | awk '{print $8$9$10}' | tr -d ,)
fi
if [ ! "$autores" = "1280x1024" ] && [ ! "$autores" = "" ]; then
	export RESOLUTION=$autores
	echo "$autores">$PREFIX/opt/last-resolution.conf
elif [ -e $PREFIX/opt/last-resolution.conf ]; then
	export RESOLUTION=$(cat $PREFIX/opt/last-resolution.conf)
fi

stop-all(){
echo "stoping wine"
pkill -f ib.exe
pkill -f zenity
$PREFIX/opt/winece/arm64-v8a/bin/wineserver -k
}

# ---------- Hardware detection ----------
cpu_info=$(grep -i "Hardware" /proc/cpuinfo)
cpu_info2=$(getprop ro.hardware.chipname)
gpu_info=$(getprop ro.hardware.gpu)
gpu_info2=$(getprop ro.hardware.vulkan)
gpu_info3=$(getprop ro.hardware.egl)
chkgpu="$gpu_info $gpu_info2 $gpu_info3"
chkcpu="$cpu_info $cpu_info2"
ugpu=$(echo "$chkgpu" | tr ' ' '\n' | sort -u | tr '\n' ' ')
ucpu=$(echo "$chkcpu" | grep -o "\bQualcomm\b" )
echo "your GPU is $ugpu "
echo "your CPU is $ucpu $cpu_info2  "

CFG="$PREFIX/opt/drv"

# ---------- virgl toggle ----------
if grep -q 'virgl' "$CFG"; then
    echo "virgl server starting,,,"
    touch "${PREFIX}/opt/virgl/virgl-enabled"
else
  rm -rf "${PREFIX}/opt/virgl/virgl-enabled"
fi

# ---------- optional external config ----------
if [ "$SOURCE_CONFIG" = "1" ] && [ -f "$CONFIG_SCRIPT" ]; then
    source "$CONFIG_SCRIPT"
fi

echo "Android Version: $ANDROID_VERSION"
echo "CPU Type: $CPU_TYPE"
echo "Wine Type: fex"
echo "DXVK File: $DXVK_FILE"
echo "Driver File: $DRIVER_FILE"
echo "Cores: $PRIMARY_CORES"
echo "WINEPREFIX: $WINEPREFIX"

# ---------- cores sanity check ----------
if [ "$PRIMARY_CORES" == "" ]; then
    export PRIMARY_CORES=0-1
    echo "export PRIMARY_CORES=$PRIMARY_CORES" > "$PREFIX/cores"
else
    export PRIMARY_CORES=$PRIMARY_CORES
    echo "export PRIMARY_CORES=$PRIMARY_CORES" > "$PREFIX/cores"
fi

# =====================================================================
#  start()  –  prepares the prefix, installs Wine the first time.
# =====================================================================
start(){
cd /data/data/app.xodos2/files/usr/opt/apps

echo $VK_ICD_FILENAMES
export WINEPREFIX=$HOME/.wine
CFG="$PREFIX/opt/drv"

if grep -q 'virtio' "$CFG"; then
    echo "Venus server enabled,,,"
    #unset MESA_GLTHREAD
    export VN_DEBUG=vtest
    export VK_ICD_FILENAMES=/data/data/app.xodos2/files/usr/share/vulkan/icd.d/virtio_icd.aarch64.json
else
    rm -rf "${PREFIX}/opt/virgl/virgl-enabled"
fi

if [ ! -e $PREFIX/opt/virgl/virgl-enabled ]; then
	unset MESA_GL_VERSION_OVERRIDE
	unset MESA_GLSL_VERSION_OVERRIDE
	unset MESA_EXTENSION_OVERRIDE
	export MESA_VK_WSI_PRESENT_MODE=mailbox
	export MESA_VK_WSI_DEBUG=blit
	export ZINK_DEBUG=compact
	export ZINK_DESCRIPTORS=lazy
	export MESA_SHADER_CACHE=512MB
	export MESA_SHADER_CACHE_DISABLE=false
	export MESA_LOADER_DRIVER_OVERRIDE=zink
	export MESA_NO_ERROR=1
	export MESA_DEBUG=0
	export USE_HEAP=1
	export DISPLAY=:0
	export XDG_RUNTIME_DIR=$PREFIX/tmp:${TMPDIR}
	export WINEESYNC_TERMUX=1
	export DXVK_HUD=fps,version,devinfo
	export WINEDEBUG=-all

	unset GALLIUM_HUD
else
	echo " useing android wrapper driver if it's not supported please install pkg install mesa and Install wineD3D10"
	export MESA_EXTENSION_OVERRIDE='-GL_EXT_texture_sRGB_decode GL_EXT_polygon_offset_clamp'
fi
. $PREFIX/opt/drv 
. $PREFIX/opt/env

if [ ! -e $WINEPREFIX/.update-timestamp ]; then
	echo "installing... Wine-10.0 to $WINEPREFIX. please wait,,пожалуйста, подождите,,请稍等,, お待ちください,, कृपया प्रतीक्षा करें,, ،،رﺎﻈﺘﻧﻻﺍ ﻰﺟﺮﻳ "
	rm -rf $WINEPREFIX
	unset BOX64_DYNAREC_BIGBLOCK
	unset WINEESYNC
	unset WINEESYNC_TERMUX
	unset BOX64_DYNAREC_CALLRET
	if [ -e $PREFIX/opt/hangover-wine/lib/wine/aarch64-windows/*.dll ]; then
		cp -a $PREFIX/opt/hangover-wine/lib/wine/aarch64-windows/* $PREFIX/opt/winece/arm64-v8a/lib/wine/aarch64-windows/ &>/dev/null
	fi
	#unset $DISPLAY
	taskset -c $PRIMARY_CORES \
	  $PREFIX/opt/winece/arm64-v8a/bin/wine wineboot -u >${LOG_PATH}_wineboot.txt 2>&1

	if [ ! -e $WINEPREFIX/.update-timestamp ]; then
		RED='\033[1;31m'
		echo -e “${RED}error,,Cannot install. See ${LOG_PATH}_wineboot.txt${WHITE}”
		
$PREFIX/opt/winece/arm64-v8a/bin/wineserver -k
		pkill -f wine
		sleep 1
		exit
	fi
	export DISPLAY=:0
	echo "disable">$WINEPREFIX/.update-timestamp

	echo "Installing DirectX,,,"
	export WINEPREFIX="$HOME/.wine"
	echo "and Wine components,,,,"
	for f in /data/data/app.xodos2/files/usr/wincomponents/*.tzst; do
		echo "Extracting components $f to $WINEPREFIX/drive_c/windows/..."
		zstd -d "$f" -c | tar -x -C "$WINEPREFIX/drive_c/windows/" &> null
	done

	echo "Installing Start Menu shortcuts"
	#cp -r $PREFIX/opt/prefix/start/* "$WINEPREFIX/drive_c/ProgramData/Microsoft/Windows/Start Menu"
	WINE_APP="$PREFIX/opt/apps/"
WINE_XOD="$PREFIX/xod/"
START_MENU="$WINEPREFIX/drive_c/ProgramData/Microsoft/Windows/Start Menu/apps"

mkdir -p "$START_MENU"

for DIR in "$WINE_APP" "$WINE_XOD"; do
    [ -d "$DIR" ] || continue

    find "$DIR" -type f \( -iname "*.exe" -o -iname "*.bat" \) -print0 |
    while IFS= read -r -d '' file; do
        name="$(basename "$file")"
        ln -sf "$file" "$START_MENU/$name"
    done
done
	rm -rf "$WINEPREFIX/dosdevices/z:"
	ln -sf /data/data/app.xodos2/files "$WINEPREFIX/dosdevices/z:"
	ln -sf /sdcard//home/USB_STORAGE "$WINEPREFIX/dosdevices/d:"
	ln -sf /sdcard/Android/data/app.xodos2/files//home/USB_STORAGE "$WINEPREFIX/dosdevices/e:"
	
	mkdir -p "$WINEPREFIX/drive_x"
# Write the correct serial (X in little-endian hex)
echo "58000000" > "$WINEPREFIX/drive_x/.windows-serial"

chmod 771 "$WINEPREFIX/drive_x"

# Create RELATIVE symlink
ln -sfn ../drive_x "$WINEPREFIX/dosdevices/x:"

# Force CD-ROM registry
$PREFIX/opt/winece/arm64-v8a/bin/wine reg add 'HKEY_LOCAL_MACHINE\Software\Wine\Drives' /v 'x:' /t REG_SZ /d 'cdrom' /f 2>/dev/null
	echo "Installing registry tweaks"
	cp $PREFIX/opt/prefix/marlett.ttf $WINEPREFIX/drive_c/windows/Fonts
	$PREFIX/opt/winece/arm64-v8a/bin/wine regedit $PREFIX/opt/prefix/user.reg

	WINE_USER_DIR=~/.wine/drive_c/users/steamuser
	rm -rf "$WINE_USER_DIR/Desktop"
	rm -rf "$WINE_USER_DIR/Downloads"
	mkdir -p /data/data/app.xodos2/files/home/Desktop
	mkdir -p /data/data/app.xodos2/files/home/USB_STORAGE
	ln -sf /data/data/app.xodos2/files/home/Desktop "$WINE_USER_DIR/Desktop"
	ln -sf /data/data/app.xodos2/files/home/USB_STORAGE "$WINE_USER_DIR/Documents/Downloads/"
	ln -sf /data/data/app.xodos2/files/home/USB_STORAGE "$WINE_USER_DIR/Downloads"

	echo "Setting direct x11 registry"
	$PREFIX/opt/winece/arm64-v8a/bin/wine regedit $PREFIX/opt/prefix/DefaultDLLsOverrides.reg

	mkdir -p "$WINEPREFIX/xfexmeta"
	$PREFIX/opt/winece/arm64-v8a/bin/wine regedit $PREFIX/opt/prefix/fix-services.reg &>/dev/null
	$PREFIX/opt/winece/arm64-v8a/bin/wine regedit $PREFIX/opt/prefix/mediafoundation/mediafoundation.reg &>/dev/null

	touch $WINEPREFIX/xfexmeta/services-fix-applied
	$PREFIX/bin/tar -xf $PREFIX/opt/prefix/fix-fonts.tar.xz -C $WINEPREFIX/drive_c/windows
	touch $WINEPREFIX/xfexmeta/fonts-fix-applied
	touch $WINEPREFIX/xfexmeta/dxdlls-fix-applied

	if [ ! "$STARTUP_COMPATIBILITY_MODE" = "1" ]; then
		echo "Installing PhysX 9.10, 7-Zip, Visual C++ Redistributable"
		DISPLAY=:0 taskset -c $PRIMARY_CORES $PREFIX/opt/winece/arm64-v8a/bin/wine explorer /desktop=shell,640x480 cmd /c "start Z:\\usr\\opt\\apps\\install.bat" >${LOG_PATH}_redist.txt 2>&1
	fi
	sleep 1
	touch "${WINEPREFIX}/.ready"
fi

xusb

export DISPLAY=:0
echo "loading"
}

start
sleep 1

# =====================================================================
#  Load all extra config files  (env is already loaded at the top)
# =====================================================================
if [ -f "$PREFIX/opt/env" ]; then
  . $PREFIX/opt/env
fi

if [ -f "$PREFIX/opt/drv" ]; then
  echo "setting drivers"
  . $PREFIX/opt/drv
fi

if [ -f "$PREFIX/opt/hud" ]; then
  echo "setting hud"
  . $PREFIX/opt/hud
fi

if [ -f "$PREFIX/opt/dbg" ]; then
  echo "setting debug"
  . $PREFIX/opt/dbg
fi

if [ -f "$PREFIX/opt/dyna" ]; then
  echo "setting dynarecs"
  . $PREFIX/opt/dyna
fi

if [ -f "$PREFIX/opt/cores" ]; then
  echo "setting cores"
  . $PREFIX/opt/cores
fi

if [ -f "$PREFIX/opt/sync" ]; then
  echo "setting wine sync"
  . $PREFIX/opt/sync
fi

# ---------- cores sanity check #2 ----------
if [ "$PRIMARY_CORES" == "" ]; then
    export PRIMARY_CORES=0-1
    echo "export PRIMARY_CORES=$PRIMARY_CORES" > "$PREFIX/cores"
else
    export PRIMARY_CORES=$PRIMARY_CORES
    echo "export PRIMARY_CORES=$PRIMARY_CORES" > "$PREFIX/cores"
fi

echo "set HUD"
CONFIG_FILE="/data/data/app.xodos2/files/usr/opt/hud"
chmod +x $CONFIG_FILE
source $CONFIG_FILE

# =====================================================================
#  Adrenotools branch
# =====================================================================

# =====================================================================
#  Virtio / Virpipe branches
# =====================================================================
HUDD="$PREFIX/opt/hud"
CFG="$PREFIX/opt/drv"

if grep -q 'virtio' "$CFG"; then
    echo "Venus server enabled,,,"
    #unset MESA_GLTHREAD
    export VN_DEBUG=vtest
    export VK_ICD_FILENAMES=/data/data/app.xodos2/files/usr/share/vulkan/icd.d/virtio_icd.aarch64.json
else
    rm -rf "${PREFIX}/opt/virgl/virgl-enabled"
fi

if grep -q 'virpipe' "$CFG"; then
    echo "virgl server enabled,,,"
    #unset MESA_GLTHREAD
   # unset GALLIUM_DRIVER
  #  unset MESA_LOADER_DRIVER_OVERRIDE
  # unset LIBGL_DRIVERS_PATH
   # unset ZINK_DEBUG
   # unset VN_DEBUG
   # unset VK_ICD_FILENAMES
    #unset MANGOHUD
    unset DXVK_HUD
    export MESA_GL_VERSION_OVERRIDE=$MESA_GL_VERSION_OVERRIDE
    export MESA_GLSL_VERSION_OVERRIDE=$MESA_GLSL_VERSION_OVERRIDE
    export MESA_EXTENSION_OVERRIDE=$MESA_EXTENSION_OVERRIDE
    export GALLIUM_DRIVER=virpipe
else
    rm -rf "${PREFIX}/opt/virgl/virgl-enabled"
fi
# =====================================================================
#  Ensure X: (CD-ROM) drive exists — safe on every launch
#  Mirrors Winlator's createDosdevicesSymlinks(container, true)
# =====================================================================
# Create X: drive
mkdir -p "$WINEPREFIX/drive_x"
# Write the correct serial (X in little-endian hex)
echo "58000000" > "$WINEPREFIX/drive_x/.windows-serial"

chmod 771 "$WINEPREFIX/drive_x"

# Create RELATIVE symlink
ln -sfn ../drive_x "$WINEPREFIX/dosdevices/x:"

# Force CD-ROM registry
$PREFIX/opt/winece/arm64-v8a/bin/wine reg add 'HKEY_LOCAL_MACHINE\Software\Wine\Drives' /v 'x:' /t REG_SZ /d 'cdrom' /f 2>/dev/null

# =====================================================================
#  Input bridge
# =====================================================================
if [ -f $PREFIX/bin/ib.exe ]; then
    echo "starting input bridge,,,"
    DISPLAY=:0 taskset -c $PRIMARY_CORES $PREFIX/opt/winece/arm64-v8a/bin/wine explorer start /min /unix $PREFIX/bin/ib.exe &>/dev/null &
fi

export VKD3D_FEATURE_LEVEL=12_0

# =====================================================================
#  Launch the program
# =====================================================================
EXE_DIR=$(dirname "$PROGRAM")
EXE_NAME=$(basename "$PROGRAM")
$PREFIX/opt/winece/arm64-v8a/bin/wine regedit $PREFIX/xod/colors/BreezeDark.reg 

EXE_PATH="$@"
if [ -z "$PROGRAM" ]; then
  echo "Usage: run \"$1\" /path/to/program.exe"
  echo "Usage: or from desktop right click and choose open with wine "
  cd $PREFIX/opt/apps/
  taskset -c $PRIMARY_CORES $PREFIX/opt/winece/arm64-v8a/bin/wine explorer /desktop=shell,$RESOLUTION $PREFIX/opt/apps/winhandler.exe &
else
  echo "loading..$PROGRAM"
  echo $EXE_DIR
  cd "$EXE_DIR"
  if ! grep -q 'winhandler' "$PROGRAM"; then
    cd $PREFIX/opt/apps/
    taskset -c $PRIMARY_CORES $PREFIX/opt/winece/arm64-v8a/bin/wine $PREFIX/opt/apps/winhandler.exe &
    cd "$EXE_DIR"
    sleep 1
    taskset -c $PRIMARY_CORES $PREFIX/opt/winece/arm64-v8a/bin/wine "$EXE_NAME"  &> ${LOG_PATH}_debug.txt  &
  else
    taskset -c $PRIMARY_CORES $PREFIX/opt/winece/arm64-v8a/bin/wine "$EXE_NAME"  &> ${LOG_PATH}_debug.txt  &
  fi
fi

# =====================================================================
#  Optional Steam hook
# =====================================================================
if [ -e $PREFIX/steam/steam22.exe ]; then
  echo " steam,,,"
fi

sleep 12
echo "wine fex is running..."
pkill -f zenity
pkill -f services.exe
sleep 2