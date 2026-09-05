//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.
//!
//! A rootfs is considered **proot‑compatible** only when **all** of these exist:
//! * `.xodos2_rootfs_ok` sentinel
//! * `etc/os-release` or ( `usr/bin` and `root` ) – broad compatibility
//! * `sys/.empty` directory
//! If any of them is missing, the container is treated as non‑proot and a
//! lightweight Android shell is launched with `PREFIX` pointing to its `/usr`.

//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.

//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.

use super::{get_application_context, has_rootfs};
use super::{host_pulse_runtime_dir, guest_pulse_server_env, GUEST_PULSE_RUNTIME_MOUNT};
use anyhow::{Context, Result};
use nix::pty::{forkpty, ForkptyResult, Winsize};
use nix::unistd::{dup, execve, Pid};
use std::ffi::CString;
use std::fs::{self, File};
use std::io::Write;
use std::os::fd::IntoRawFd;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::io::{FromRawFd, RawFd};
use std::path::{Path, PathBuf};

// --------------------------------------------------------------------------
// Constants
// --------------------------------------------------------------------------

const DEFAULT_FAKE_KERNEL_RELEASE: &str = "6.17.0-PRoot-Distro";
const DEFAULT_FAKE_KERNEL_VERSION: &str =
    "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000";

// --------------------------------------------------------------------------
// Pulse / profile helpers
// --------------------------------------------------------------------------

const PULSE_CLIENT_NO_SHM: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Host Pulse uses socket IPC only (no memfd across namespaces).\n\
enable-shm = no\n\
enable-memfd = no\n\
";

const GUEST_PROFILE_XODOS2_RUNTIME: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Keep essential runtime defaults across `su - user`.\n\
\n\
: \"${XDG_RUNTIME_DIR:=/run/user/0}\"\n\
: \"${PULSE_SERVER:=unix:/run/xodos2-pulse/native}\"\n\
export XDG_RUNTIME_DIR PULSE_SERVER\n\
\n\
if ! pgrep -x dbus-daemon >/dev/null 2>&1; then\n\
    dbus-daemon --system --fork 2>/dev/null || true\n\
    if command -v dbus-launch >/dev/null 2>&1; then\n\
        eval \"$(dbus-launch --sh-syntax)\" 2>/dev/null || true\n\
        export DBUS_SESSION_BUS_ADDRESS\n\
    fi\n\
fi\n\
\n\
if command -v pactl >/dev/null 2>&1; then\n\
  for _i in 1 2 3 4 5 6 7 8 9 10; do\n\
    pactl info >/dev/null 2>&1 && break\n\
    sleep 0.1\n\
  done\n\
  pactl set-default-sink xodos2-out >/dev/null 2>&1 || true\n\
fi\n\
";

fn write_pulse_guest_client_fragment(data_dir: &Path) -> PathBuf {
    let path = data_dir.join("proot_pulse_client_no_shm.conf");
    if let Err(e) = fs::write(&path, PULSE_CLIENT_NO_SHM) {
        log::warn!("proot: write {:?}: {:?}", path, e);
    }
    path
}

fn write_guest_profile_fragment(data_dir: &Path) -> PathBuf {
    let path = data_dir.join("proot_profile_xodos2_runtime.sh");
    if let Err(e) = fs::write(&path, GUEST_PROFILE_XODOS2_RUNTIME) {
        log::warn!("proot: write {:?}: {:?}", path, e);
    }
    path
}

fn proot_and_loader_paths() -> Result<(PathBuf, PathBuf)> {
    let ctx = get_application_context()?;
    let proot = ctx.native_library_dir.join("libproot.so");
    let loader = ctx.native_library_dir.join("libproot_loader.so");
    if !proot.exists() {
        anyhow::bail!("proot not found: {:?}", proot);
    }
    if !loader.exists() {
        anyhow::bail!("loader not found: {:?}", loader);
    }
    log::info!("proot: using proot={:?}, loader={:?}", proot, loader);
    Ok((proot, loader))
}

fn is_proot_compatible(rootfs: &Path) -> bool {
    let compatible = has_rootfs(rootfs)
        && rootfs.join("sys/.empty").is_dir()
        && (rootfs.join("etc/os-release").exists()
            || (rootfs.join("usr/bin").is_dir() && rootfs.join("root").is_dir()));
    log::info!("proot: rootfs {:?} proot_compatible={}", rootfs, compatible);
    compatible
}

fn ensure_fake_sysdata(rootfs: &Path) -> Result<()> {
    let sysdata_dir = rootfs
        .parent()
        .context("rootfs parent directory")?
        .join("sysdata");
    fs::create_dir_all(&sysdata_dir)?;
    fs::set_permissions(&sysdata_dir, PermissionsExt::from_mode(0o700))?;

    let sys_empty = rootfs.join("sys/.empty");
    fs::create_dir_all(&sys_empty)?;

    let write_if_missing = |path: &Path, content: &str| -> Result<()> {
        if !path.exists() {
            fs::write(path, content)?;
        }
        Ok(())
    };

    write_if_missing(&sysdata_dir.join("loadavg"), "0.12 0.07 0.02 2/165 765\n")?;
    write_if_missing(
        &sysdata_dir.join("stat"),
        "cpu  1957 0 2877 93280 262 342 254 87 0 0\n\
         ctxt 140223\n\
         btime 1680020856\n\
         processes 772\n\
         procs_running 2\n\
         procs_blocked 0\n",
    )?;
    write_if_missing(&sysdata_dir.join("uptime"), "124.08 932.80\n")?;
    write_if_missing(
        &sysdata_dir.join("version"),
        &format!(
            "Linux version {} (proot@xodos2) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) {}\n",
            DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION
        ),
    )?;
    write_if_missing(&sysdata_dir.join("vmstat"), "nr_free_pages 1743136\n")?;
    write_if_missing(&sysdata_dir.join("sysctl_entry_cap_last_cap"), "40\n")?;
    write_if_missing(&sysdata_dir.join("sysctl_inotify_max_user_watches"), "4096\n")?;
    write_if_missing(&sysdata_dir.join("sysctl_kernel_overflowuid"), "65534\n")?;
    write_if_missing(&sysdata_dir.join("sysctl_kernel_overflowgid"), "65534\n")?;
    Ok(())
}

fn fake_proc_bindings(_rootfs: &Path, sysdata_dir: &Path) -> Result<Vec<CString>> {
    let mut binds = Vec::new();
    let pairs = [
        ("/proc/loadavg", "loadavg"),
        ("/proc/stat", "stat"),
        ("/proc/uptime", "uptime"),
        ("/proc/version", "version"),
        ("/proc/vmstat", "vmstat"),
        ("/proc/sys/kernel/cap_last_cap", "sysctl_entry_cap_last_cap"),
        ("/proc/sys/fs/inotify/max_user_watches", "sysctl_inotify_max_user_watches"),
        ("/proc/sys/kernel/overflowuid", "sysctl_kernel_overflowuid"),
        ("/proc/sys/kernel/overflowgid", "sysctl_kernel_overflowgid"),
    ];

    for (real_path, fake_name) in pairs {
        let real = Path::new(real_path);
        let readable = fs::File::open(real).map(|f| f.metadata().is_ok()).unwrap_or(false);
        if !readable {
            let fake_file = sysdata_dir.join(fake_name);
            if fake_file.exists() {
                binds.push(
                    CString::new(format!("--bind={}:{}", fake_file.display(), real_path))
                        .context("bind fake proc")?,
                );
            }
        }
    }
    Ok(binds)
}

pub(super) fn build_exec_args(
    rootfs: &Path,
) -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let mut argv: Vec<CString> = Vec::new();
    let mut env: Vec<CString> = Vec::new();

    if is_proot_compatible(rootfs) {
        // ---------- full PRoot container ----------
        let (proot, loader) = proot_and_loader_paths()?;
        let proot_str = proot.to_string_lossy();
        let loader_str = loader.to_string_lossy();

        argv.push(CString::new(proot_str.as_bytes()).context("proot path")?);

        // 0. Write fake /proc & /sys content (outside rootfs)
        ensure_fake_sysdata(rootfs)?;
        let sysdata_dir = rootfs.parent().context("rootfs parent")?.join("sysdata");

        // 1. Basic rootfs and options
        argv.push(CString::new("-r").unwrap());
        argv.push(CString::new(rootfs.to_string_lossy().as_bytes()).context("rootfs path")?);

        let kernel_release = format!("{} {}", DEFAULT_FAKE_KERNEL_RELEASE, DEFAULT_FAKE_KERNEL_VERSION);
        argv.push(CString::new(format!("--kernel-release={}", kernel_release)).unwrap());

        argv.push(CString::new("-L").unwrap());
        argv.push(CString::new("--link2symlink").unwrap());
        argv.push(CString::new("--sysvipc").unwrap());
        argv.push(CString::new("--kill-on-exit").unwrap());
        argv.push(CString::new("--change-id=0:0").unwrap());

        // Core binds
        argv.push(CString::new("--bind=/dev").unwrap());
        argv.push(CString::new("--bind=/data").unwrap());
        argv.push(CString::new("--bind=/proc").unwrap());
        argv.push(CString::new("--bind=/sys").unwrap());
        argv.push(CString::new("--bind=/system").unwrap());
        argv.push(CString::new("--bind=/apex").unwrap());
        argv.push(CString::new("--bind=/storage").unwrap());

        // GPU / DRM
        if Path::new("/dev/kgsl-3d0").exists() {
            argv.push(CString::new("--bind=/dev/kgsl-3d0:/dev/kgsl-3d0").unwrap());
        }
        if Path::new("/dev/dri").exists() && File::open("/dev/dri").is_ok() {
            argv.push(CString::new("--bind=/dev/dri:/dev/dri").unwrap());
        } else {
            let dummy_dri = ctx.data_dir.join("usr").join("tmp");
            fs::create_dir_all(&dummy_dri)?;
            argv.push(CString::new(format!("--bind={}:/dev/dri", dummy_dri.display())).unwrap());
        }

        // Wayland / X11
        let wayland_runtime = ctx.data_dir.join("usr").join("tmp");
        fs::create_dir_all(&wayland_runtime)?;
        fs::set_permissions(&wayland_runtime, PermissionsExt::from_mode(0o700))?;

        let host_x11_dir = ctx.data_dir.join("usr/tmp").join(".X11-unix");
        fs::create_dir_all(&host_x11_dir)?;
        let guest_x11_dir = rootfs.join("tmp/.X11-unix");
        fs::create_dir_all(&guest_x11_dir)?;
        fs::set_permissions(&host_x11_dir, PermissionsExt::from_mode(0o1777))?;
        fs::set_permissions(&guest_x11_dir, PermissionsExt::from_mode(0o1777))?;

        argv.push(CString::new(format!("--bind={}:/run/user/0", wayland_runtime.display())).unwrap());
        argv.push(CString::new(format!("--bind={}:{}", host_x11_dir.display(), "/tmp/.X11-unix")).unwrap());

        // VirGL
        let virgl_runtime = ctx.data_dir.join("virgl-run");
        fs::create_dir_all(&virgl_runtime)?;
        argv.push(CString::new(format!("--bind={}:/run/xodos2-virgl", virgl_runtime.display())).unwrap());

        // PulseAudio
        let pulse_rt = host_pulse_runtime_dir(&ctx.data_dir);
        fs::create_dir_all(&pulse_rt)?;
        argv.push(CString::new(format!("--bind={}:{}", pulse_rt.display(), GUEST_PULSE_RUNTIME_MOUNT)).unwrap());
        let pulse_client_frag = write_pulse_guest_client_fragment(&ctx.data_dir);
        argv.push(CString::new(format!("--bind={}:/etc/pulse/client.conf.d/99-xodos2-noshm.conf", pulse_client_frag.display())).unwrap());
        let profile_frag = write_guest_profile_fragment(&ctx.data_dir);
        argv.push(CString::new(format!("--bind={}:/etc/profile.d/99-xodos2-runtime.sh", profile_frag.display())).unwrap());

        // /dev/shm
        argv.push(CString::new(format!("--bind={}/tmp:/dev/shm", rootfs.display())).unwrap());

        // SD card
        if let Some(ref sdcard) = ctx.external_storage_path {
            if sdcard.exists() {
                for (guest, host) in [("/android", sdcard), ("/root/android", sdcard), ("/sdcard", sdcard), ("/root/sdcard", sdcard)] {
                    argv.push(CString::new(format!("--bind={}:{}", host.display(), guest)).unwrap());
                }
            }
        }

        // Standard fds
        argv.push(CString::new("--bind=/dev/urandom:/dev/random").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd:/dev/fd").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/0:/dev/stdin").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/1:/dev/stdout").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/2:/dev/stderr").unwrap());

        // Fake proc
        let fake_binds = fake_proc_bindings(rootfs, &sysdata_dir)?;
        argv.extend(fake_binds);

        // Fake SELinux
        argv.push(CString::new(format!("--bind={}/sys/.empty:/sys/fs/selinux", rootfs.display())).unwrap());

        // Extra Android dirs
        for path in ["/vendor", "/odm", "/product", "/system_ext", "/linkerconfig/ld.config.txt", "/plat_property_contexts", "/property_contexts"] {
            if Path::new(path).exists() {
                argv.push(CString::new(format!("--bind={}", path)).unwrap());
            }
        }

        // Ensure /etc/resolv.conf and /etc/hosts exist (non‑critical, don't abort on failure)
        let resolv_conf = rootfs.join("etc/resolv.conf");
        if !resolv_conf.exists() {
            if let Some(parent) = resolv_conf.parent() {
                fs::create_dir_all(parent).ok();
            }
            if let Err(e) = fs::write(&resolv_conf, "nameserver 8.8.8.8\nnameserver 8.8.4.4\n") {
                log::warn!("proot: failed to write resolv.conf: {:?}", e);
            }
        }
        let hosts = rootfs.join("etc/hosts");
        if !hosts.exists() {
            if let Some(parent) = hosts.parent() {
                fs::create_dir_all(parent).ok();
            }
            if let Err(e) = fs::write(&hosts, "127.0.0.1 localhost.localdomain localhost\n::1 localhost.localdomain localhost ip6-localhost ip6-loopback\n") {
                log::warn!("proot: failed to write hosts: {:?}", e);
            }
        }

        // Shell detection with BusyBox fallback
        let standard_shells = [
            "/usr/bin/bash", "/bin/bash",
            "/usr/bin/sh", "/bin/sh",
            "/usr/bin/dash", "/bin/dash",
            "/usr/bin/ash", "/bin/ash",
        ];

        let shell_path = standard_shells.iter()
            .find(|s| path_exists_in_rootfs(rootfs, s))
            .map(|s| (*s, false))
            .or_else(|| {
                if path_exists_in_rootfs(rootfs, "/usr/bin/busybox") {
                    Some(("/usr/bin/busybox", true))
                } else if path_exists_in_rootfs(rootfs, "/bin/busybox") {
                    Some(("/bin/busybox", true))
                } else {
                    None
                }
            })
            .ok_or_else(|| anyhow::anyhow!("no usable shell found in rootfs"))?;

        let (shell_binary, is_busybox) = shell_path;
        if is_busybox {
            argv.push(CString::new(shell_binary).unwrap());
            argv.push(CString::new("sh").unwrap());
            argv.push(CString::new("-i").unwrap());
        } else {
            argv.push(CString::new(shell_binary).unwrap());
            argv.push(CString::new("-l").unwrap());
            argv.push(CString::new("-i").unwrap());
        }let (proot, loader) = proot_and_loader_paths()?;
let proot_str = proot.to_string_lossy();
let loader_str = loader.to_string_lossy();   // <-- ADD THIS LINE

        // Environment (no PROOT_LOADER)
        env.extend(vec![
         CString::new(format!("PROOT_LOADER={}", loader_str)).unwrap(),
            CString::new(format!("PROOT_TMP_DIR={}", ctx.cache_dir.display())).unwrap(),
            CString::new("HOME=/root").unwrap(),
            CString::new("TERM=xterm-256color").unwrap(),
            CString::new("LANG=C.UTF-8").unwrap(),
            CString::new("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/data/data/app.xodos2/files/usr/bin").unwrap(),
            CString::new("TMPDIR=/tmp").unwrap(),
            CString::new("XDG_RUNTIME_DIR=/run/user/0").unwrap(),
            CString::new("WAYLAND_DISPLAY=wayland-xodos2").unwrap(),
            CString::new("XDG_SESSION_TYPE=wayland").unwrap(),
            CString::new("QT_QPA_PLATFORM=wayland").unwrap(),
            CString::new("QT_QUICK_BACKEND=software").unwrap(),
            CString::new("VTEST_SOCKET_NAME=/run/xodos2-virgl/vtest.sock").unwrap(),
            CString::new("USER=root").unwrap(),
            CString::new("LOGNAME=root").unwrap(),
            CString::new(format!("PULSE_SERVER={}", guest_pulse_server_env())).context("PULSE_SERVER")?,
        ]);

        if rootfs.join("nix/store").is_dir() {
            if let Some(pos) = env.iter().position(|s| s.to_str().map_or(false, |v| v.starts_with("PATH="))) {
                let current_path = env[pos].to_str().unwrap_or("PATH=").to_string();
                let new_path = format!("PATH=/root/.nix-profile/bin:/run/current-system/sw/bin:{}", &current_path[5..]);
                env[pos] = CString::new(new_path).unwrap();
            }
            env.push(CString::new("ENV=/root/.bashrc").unwrap());
        }
    } else {
        // Fallback Bionic environment
        log::warn!("proot: rootfs {:?} is not proot-compatible, using fallback", rootfs);
        let prefix = ctx.data_dir.join("usr");
        let prefix_str = prefix.to_string_lossy().into_owned();
        let home_dir = "/data/data/app.xodos2/files/home";
        let tmp_dir = format!("{}/tmp", prefix_str);

        let _ = fs::create_dir_all(&tmp_dir);
        let _ = fs::create_dir_all(home_dir);

        #[cfg(target_pointer_width = "64")]
        let linker = "/system/bin/linker64";
        #[cfg(target_pointer_width = "32")]
        let linker = "/system/bin/linker";

        let bash_path = format!("{}/bin/bash", prefix_str);
        let sh_path = format!("{}/bin/sh", prefix_str);
        let (shell_path, is_bionic) = if Path::new(&bash_path).exists() {
            (bash_path, true)
        } else if Path::new(&sh_path).exists() {
            (sh_path, true)
        } else {
            ("/system/bin/sh".to_string(), false)
        };

        if is_bionic {
            argv.push(CString::new(linker).unwrap());
        }
        argv.push(CString::new(shell_path).unwrap());
        argv.push(CString::new("-l").unwrap());

        env.extend(vec![
            CString::new(format!("PREFIX={}", prefix_str)).unwrap(),
            CString::new(format!("HOME={}", home_dir)).unwrap(),
            CString::new(format!("TMPDIR={}", tmp_dir)).unwrap(),
            CString::new(format!("PATH={}/bin:/system/bin:/system/xbin", prefix_str)).unwrap(),
            CString::new(format!("LD_LIBRARY_PATH={}/lib", prefix_str)).unwrap(),
            CString::new("TERM=xterm-256color").unwrap(),
            CString::new("DISPLAY=:0").unwrap(),
            CString::new("PS1=[XoDos-Ark\\W]\\$ ").unwrap(),
        ]);
    }

    // Log final argv/env at Debug level
    log::debug!("proot: final argv = {:?}", argv.iter().map(|s| s.to_string_lossy().into_owned()).collect::<Vec<_>>());
    log::debug!("proot: final env = {:?}", env.iter().map(|s| s.to_string_lossy().into_owned()).collect::<Vec<_>>());

    Ok((argv, env))
}

pub struct ChildProcess {
    pub pid: Pid,
}

impl Drop for ChildProcess {
    fn drop(&mut self) {
        let _ = nix::sys::signal::kill(self.pid, nix::sys::signal::Signal::SIGTERM);
    }
}

pub fn fork_pty_shell_in_rootfs(
    rootfs: &Path,
    initial_rows: u16,
    initial_cols: u16,
) -> Result<(ChildProcess, File, Box<dyn Write + Send>, RawFd)> {
    let (argv, env) = build_exec_args(rootfs)?;
    let argv_refs: Vec<&std::ffi::CStr> = argv.iter().map(|s| s.as_c_str()).collect();
    let env_refs: Vec<&std::ffi::CStr> = env.iter().map(|s| s.as_c_str()).collect();

    let winsize = Winsize {
        ws_row: initial_rows.max(1),
        ws_col: initial_cols.max(1),
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    let result = unsafe { forkpty(Some(&winsize), None).context("forkpty failed")? };

    match result {
        ForkptyResult::Child => {
            log::debug!("proot: child about to execve: {:?}", argv_refs);
            if execve(argv[0].as_c_str(), &argv_refs, &env_refs).is_err() {
                log::error!("proot: execve failed: {:?}", std::io::Error::last_os_error());
                unsafe { nix::libc::_exit(1) };
            }
            unreachable!();
        }
        ForkptyResult::Parent { child, master } => {
            log::debug!("proot: forkpty succeeded, child pid = {}", child);
            let master_read_fd = dup(&master).context("dup master for read")?.into_raw_fd();
            let master_write_fd = master.into_raw_fd();
            let master_read = unsafe { File::from_raw_fd(master_read_fd) };
            let master_write = unsafe { File::from_raw_fd(master_write_fd) };
            let stdin: Box<dyn Write + Send> = Box::new(master_write);
            Ok((ChildProcess { pid: child }, master_read, stdin, master_write_fd))
        }
    }
}

pub(super) fn path_exists_in_rootfs(rootfs: &Path, relative_path: &str) -> bool {
    let full_path = rootfs.join(relative_path.trim_start_matches('/'));
    if full_path.exists() {
        return true;
    }
    if full_path.is_symlink() {
        if let Ok(target) = fs::read_link(&full_path) {
            let resolved = if target.is_relative() {
                full_path.parent().unwrap_or(Path::new("/")).join(target)
            } else {
                let target_str = target.to_string_lossy();
                if target_str.starts_with('/') {
                    rootfs.join(&target_str[1..])
                } else {
                    rootfs.join(target_str.as_ref())
                }
            };
            return resolved.exists();
        }
    }
    false
}