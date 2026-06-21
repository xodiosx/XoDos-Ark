//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.
//!
//! A rootfs is considered **proot‑compatible** only when **all** of these exist:
//! * `.xodos2_rootfs_ok` sentinel
//! * `etc/os-release`
//! * `sys/.empty` directory
//! If any of them is missing, the container is treated as non‑proot and a
//! lightweight Android shell is launched with `PREFIX` pointing to its `/usr`.

use super::{get_application_context, has_rootfs};
use super::{host_pulse_runtime_dir, guest_pulse_server_env, GUEST_PULSE_RUNTIME_MOUNT};
use anyhow::{Context, Result};
use nix::pty::{forkpty, ForkptyResult, Winsize};
use nix::unistd::{dup, execve, Pid};
use std::ffi::CString;
use std::fs::File;
use std::io::Write;
use std::os::fd::IntoRawFd;
use std::os::unix::io::{FromRawFd, RawFd};
use std::path::Path;

// --------------------------------------------------------------------------
// Pulse / profile helpers (unchanged)
// --------------------------------------------------------------------------

const PULSE_CLIENT_NO_SHM: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Host Pulse uses socket IPC only (no memfd across namespaces).\n\
enable-shm = no\n\
enable-memfd = no\n\
";

const GUEST_PROFILE_XODOS2_RUNTIME: &str = "\
# xodos2: bind-mounted; do not edit.\n\
# Keep essential runtime defaults across `su - user` (login shells read /etc/profile.d).\n\
# Only sets defaults when variables are unset; user exports remain authoritative.\n\
\n\
: \"${XDG_RUNTIME_DIR:=/run/user/0}\"\n\
: \"${PULSE_SERVER:=unix:/run/xodos2-pulse/native}\"\n\
export XDG_RUNTIME_DIR PULSE_SERVER\n\
\n\
# Best-effort: set default sink if server becomes reachable.\n\
if command -v pactl >/dev/null 2>&1; then\n\
  for _i in 1 2 3 4 5 6 7 8 9 10; do\n\
    pactl info >/dev/null 2>&1 && break\n\
    sleep 0.1\n\
  done\n\
  pactl set-default-sink xodos2-out >/dev/null 2>&1 || true\n\
fi\n\
";

fn write_pulse_guest_client_fragment(data_dir: &std::path::Path) -> std::path::PathBuf {
    let path = data_dir.join("proot_pulse_client_no_shm.conf");
    if let Err(e) = std::fs::write(path.as_path(), PULSE_CLIENT_NO_SHM) {
        log::warn!("proot: write {:?}: {:?}", path, e);
    }
    path
}

fn write_guest_profile_fragment(data_dir: &std::path::Path) -> std::path::PathBuf {
    let path = data_dir.join("proot_profile_xodos2_runtime.sh");
    if let Err(e) = std::fs::write(path.as_path(), GUEST_PROFILE_XODOS2_RUNTIME) {
        log::warn!("proot: write {:?}: {:?}", path, e);
    }
    path
}

fn proot_and_loader_paths() -> Result<(std::path::PathBuf, std::path::PathBuf)> {
    let ctx = get_application_context()?;
    let proot = ctx.native_library_dir.join("libproot.so");
    let loader = ctx.native_library_dir.join("libproot_loader.so");
    if !proot.exists() {
        anyhow::bail!("proot not found: {:?}", proot);
    }
    if !loader.exists() {
        anyhow::bail!("loader not found: {:?}", loader);
    }
    Ok((proot, loader))
}

// --------------------------------------------------------------------------
// Compatibility check
// --------------------------------------------------------------------------

fn is_proot_compatible(rootfs: &Path) -> bool {
    has_rootfs(rootfs)
        && rootfs.join("etc/os-release").exists()
        && rootfs.join("sys/.empty").is_dir()
}

// --------------------------------------------------------------------------
// Argument builder
// --------------------------------------------------------------------------

pub(super) fn build_exec_args(
    rootfs: &std::path::Path,
) -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let (proot, loader) = proot_and_loader_paths()?;
    let proot_str = proot.to_string_lossy();
    let loader_str = loader.to_string_lossy();

    let mut argv: Vec<CString> = vec![CString::new(proot_str.as_bytes()).context("proot path")?];

    if is_proot_compatible(rootfs) {
        // ---------- full PRoot container ----------
        argv.push(CString::new("-r").unwrap());
        argv.push(CString::new(rootfs.to_string_lossy().as_bytes()).context("rootfs path")?);
        argv.push(CString::new("-L").unwrap());
        argv.push(CString::new("--link2symlink").unwrap());
        argv.push(CString::new("--sysvipc").unwrap());
        argv.push(CString::new("--kill-on-exit").unwrap());
        argv.push(CString::new("--root-id").unwrap());
        argv.push(CString::new("--bind=/dev").unwrap());
        argv.push(CString::new("--bind=/data").unwrap());
        argv.push(CString::new("--bind=/proc").unwrap());
        argv.push(CString::new("--bind=/sys").unwrap());
        argv.push(CString::new("--bind=/system").unwrap());
        argv.push(CString::new("--bind=/apex").unwrap());
        argv.push(CString::new("--bind=/storage").unwrap());

        // GPU device nodes
        if Path::new("/dev/kgsl-3d0").exists() {
            argv.push(CString::new("--bind=/dev/kgsl-3d0:/dev/kgsl-3d0").unwrap());
        }
        if Path::new("/dev/dri").exists() && File::open("/dev/dri").is_ok() {
            argv.push(CString::new("--bind=/dev/dri:/dev/dri").unwrap());
        } else {
            log::info!("proot: skip bind /dev/dri (missing or not accessible)");
            argv.push(CString::new("--bind=/data/data/app.xodos2/files/usr/tmp:/dev/dri").unwrap());
        }

        let wayland_runtime = ctx.data_dir.join("usr").join("tmp");
        let _ = std::fs::create_dir_all(&wayland_runtime);
        let host_x11_dir = ctx.data_dir.join("tmp").join(".X11-unix");
        let _ = std::fs::create_dir_all(&host_x11_dir);
        let guest_x11_dir = rootfs.join("tmp/.X11-unix");
        let _ = std::fs::create_dir_all(&guest_x11_dir);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(&wayland_runtime, std::fs::Permissions::from_mode(0o700));
            let _ = std::fs::set_permissions(&host_x11_dir, std::fs::Permissions::from_mode(0o1777));
            let _ = std::fs::set_permissions(&guest_x11_dir, std::fs::Permissions::from_mode(0o1777));
        }
        argv.push(CString::new(format!("--bind={}:/run/user/0", wayland_runtime.display())).unwrap());
        argv.push(
            CString::new(format!("--bind={}:{}", host_x11_dir.display(), "/tmp/.X11-unix"))
                .context("x11 unix socket bind")?,
        );
        let virgl_runtime = ctx.data_dir.join("virgl-run");
        let _ = std::fs::create_dir_all(&virgl_runtime);
        argv.push(
            CString::new(format!("--bind={}:/run/xodos2-virgl", virgl_runtime.display()))
                .context("virgl runtime bind")?,
        );
        let pulse_rt = host_pulse_runtime_dir(&ctx.data_dir);
        let _ = std::fs::create_dir_all(&pulse_rt);
        argv.push(
            CString::new(format!("--bind={}:{}", pulse_rt.display(), GUEST_PULSE_RUNTIME_MOUNT))
                .context("pulse runtime bind")?,
        );
        let pulse_client_frag = write_pulse_guest_client_fragment(&ctx.data_dir);
        argv.push(
            CString::new(format!(
                "--bind={}:/etc/pulse/client.conf.d/99-xodos2-noshm.conf",
                pulse_client_frag.display()
            ))
            .context("pulse client no-shm bind")?,
        );
        let profile_frag = write_guest_profile_fragment(&ctx.data_dir);
        argv.push(
            CString::new(format!(
                "--bind={}:/etc/profile.d/99-xodos2-runtime.sh",
                profile_frag.display()
            ))
            .context("profile.d runtime bind")?,
        );
        argv.push(CString::new(format!("--bind={}/tmp:/dev/shm", rootfs.display())).unwrap());
        if let Some(ref sdcard) = ctx.external_storage_path {
            if sdcard.exists() {
                argv.push(CString::new(format!("--bind={}:/android", sdcard.display())).unwrap());
                argv.push(CString::new(format!("--bind={}:/root/android", sdcard.display())).unwrap());
                argv.push(CString::new(format!("--bind={}:/sdcard", sdcard.display())).unwrap());
                argv.push(CString::new(format!("--bind={}:/root/sdcard", sdcard.display())).unwrap());
            }
        }

        argv.push(CString::new("--bind=/dev/urandom:/dev/random").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd:/dev/fd").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/0:/dev/stdin").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/1:/dev/stdout").unwrap());
        argv.push(CString::new("--bind=/proc/self/fd/2:/dev/stderr").unwrap());
        argv.push(
            CString::new(format!("--bind={}/proc/.loadavg:/proc/loadavg", rootfs.display())).unwrap(),
        );
        argv.push(
            CString::new(format!("--bind={}/proc/.stat:/proc/stat", rootfs.display())).unwrap(),
        );
        argv.push(
            CString::new(format!("--bind={}/proc/.uptime:/proc/uptime", rootfs.display())).unwrap(),
        );
        argv.push(
            CString::new(format!("--bind={}/proc/.version:/proc/version", rootfs.display())).unwrap(),
        );
        argv.push(
            CString::new(format!("--bind={}/proc/.vmstat:/proc/vmstat", rootfs.display())).unwrap(),
        );
        argv.push(
            CString::new(format!(
                "--bind={}/proc/.sysctl_entry_cap_last_cap:/proc/sys/kernel/cap_last_cap",
                rootfs.display()
            ))
            .unwrap(),
        );
        argv.push(
            CString::new(format!(
                "--bind={}/proc/.sysctl_inotify_max_user_watches:/proc/sys/fs/inotify/max_user_watches",
                rootfs.display()
            ))
            .unwrap(),
        );
        argv.push(
            CString::new(format!("--bind={}/sys/.empty:/sys/fs/selinux", rootfs.display())).unwrap(),
        );
        // Choose a shell that actually exists

// ── Shell detection (inside `if is_proot_compatible(rootfs)` block) ──
let standard_shells: &[&str] = &[
    "bin/bash", "usr/bin/bash",
    "bin/sh", "usr/bin/sh",
    "bin/dash", "usr/bin/dash",
    "bin/ash", "usr/bin/ash",
];

let shell_info = standard_shells
    .iter()
    .find(|c| rootfs.join(c).exists())
    .map(|&path| {
        // strip leading "usr/" if present, to use as absolute path
        let binary = path.strip_prefix("usr/").unwrap_or(path);
        (binary, binary) // (binary, applet) – same for standard shells
    })
    .or_else(|| {
        // Fallback to BusyBox if no standard shell found
        if rootfs.join("bin/busybox").exists() || rootfs.join("usr/bin/busybox").exists() {
            Some(("busybox", "sh"))
        } else {
            None
        }
    });

let (binary, applet) = shell_info
    .ok_or_else(|| anyhow::anyhow!("no usable shell found in rootfs"))?;

if binary == "busybox" {
    // BusyBox needs the applet name as first argument
    argv.push(CString::new("/bin/busybox").unwrap());
    argv.push(CString::new(applet).unwrap());   // "sh"
    argv.push(CString::new("-i").unwrap());
} else {
    argv.push(CString::new(format!("/{}", binary)).unwrap());
    argv.push(CString::new("-l").unwrap());
    argv.push(CString::new("-i").unwrap());
}
    } else {
        // ---------- fallback: Android system shell with container prefix ----------
       // argv.push(CString::new("-0").unwrap());
        argv.push(CString::new("/system/bin/sh").unwrap());
        argv.push(CString::new("-c").unwrap());

        // FIX: use ctx.data_dir, not bare `data_dir`
        let prefix = ctx.data_dir.join("usr");
        let prefix_str = prefix.to_string_lossy().into_owned();

        // Use a writable home directory that always exists
        let safe_home = "/data/data/app.xodos2/files/";
        // Create a tmp directory inside the container's usr for TMPDIR (if possible)
        let tmp_dir = format!("{}/tmp", prefix_str);

        let fallback_script = format!(
            "export PREFIX='{prefix}'; \
             export DISPLAY=:0; \
             export PATH=$PREFIX/bin:$PATH; \
             export LD_LIBRARY_PATH=$PREFIX/lib:$LD_LIBRARY_PATH; \
             export TMPDIR='{tmp}'; \
             export HOME='{home}'; \
             mkdir -p \"{tmp}\" \"{home}\" 2>/dev/null; \
             if [ -d \"$PREFIX\" ]; then cd \"$PREFIX\" || true; else cd \"{home}\" || true; fi; \
             if [ -f \"$PREFIX/bin/bash\" ]; then exec bash -l || true; else exec sh -i || true; fi; \
             export PS1='[XoDos-Ark\\W]# '; \
             ",
            prefix = prefix_str,
            tmp = tmp_dir,
            home = safe_home,
        );
        argv.push(CString::new(fallback_script.as_bytes()).context("fallback script")?);
    }

    // Common environment for both modes
    let mut env: Vec<CString> = vec![
        CString::new(format!("PROOT_LOADER={}", loader_str)).unwrap(),
        CString::new(format!("PROOT_TMP_DIR={}", ctx.cache_dir.display())).unwrap(),
        CString::new("HOME=/root").unwrap(),
        CString::new("TERM=xterm-256color").unwrap(),
        CString::new("LANG=C.UTF-8").unwrap(),
        CString::new("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games:/system/bin:/system/xbin").unwrap(),
        CString::new("TMPDIR=/tmp").unwrap(),
        CString::new("XDG_RUNTIME_DIR=/run/user/0").unwrap(),
        CString::new("WAYLAND_DISPLAY=wayland-xodos2").unwrap(),
        CString::new("XDG_SESSION_TYPE=wayland").unwrap(),
        CString::new("QT_QPA_PLATFORM=wayland").unwrap(),
       // CString::new("USER=root").unwrap(),
     //   CString::new("LOGNAME=root").unwrap(),
    ];
    env.push(CString::new("QT_QUICK_BACKEND=software").unwrap());
    env.push(CString::new("VTEST_SOCKET_NAME=/run/xodos2-virgl/vtest.sock").unwrap());
    env.push(CString::new("VTEST_RENDERER_SOCKET_NAME=/run/xodos2-virgl/vtest.sock").unwrap());
    if is_proot_compatible(rootfs) {
       // env.insert(3, CString::new("PS1=[\\u@\\h \\W]\\$ ").unwrap());
        env.push(CString::new("USER=root").unwrap());         
    env.push(CString::new("LOGNAME=root").unwrap());      
        env.push(
            CString::new(format!("PULSE_SERVER={}", guest_pulse_server_env()))
                .context("PULSE_SERVER")?,
        );
        env.push(CString::new("XWAYLAND_NO_GLAMOR=1").unwrap());
        env.push(CString::new("MOZ_FAKE_NO_SANDBOX=1").unwrap());
    }

    Ok((argv, env))
}

// --------------------------------------------------------------------------
// PTY shell spawn
// --------------------------------------------------------------------------

pub struct ChildProcess {
    pub pid: Pid,
}

impl Drop for ChildProcess {
    fn drop(&mut self) {
        let _ = nix::sys::signal::kill(self.pid, nix::sys::signal::Signal::SIGTERM);
    }
}

/// Spawn a shell inside the given rootfs directory.
///
/// * If the rootfs is proot‑compatible (sentinel + os-release + sys/.empty exist),
///   a full PRoot container is launched with `/bin/bash -i`.
/// * Otherwise, a lightweight Android shell is started with environment variables
///   pointing to the container's `/usr` and a safe working directory.
pub fn fork_pty_shell_in_rootfs(
    rootfs: &Path,
    initial_rows: u16,
    initial_cols: u16,
) -> Result<(ChildProcess, std::fs::File, Box<dyn Write + Send>, RawFd)> {
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
            if execve(argv[0].as_c_str(), &argv_refs, &env_refs).is_err() {
                unsafe { nix::libc::_exit(1) };
            }
            unreachable!();
        }
        ForkptyResult::Parent { child, master } => {
            let master_read_fd = dup(&master).context("dup master for read")?.into_raw_fd();
            let master_write_fd = master.into_raw_fd();
            let master_read = unsafe { std::fs::File::from_raw_fd(master_read_fd) };
            let master_write = unsafe { std::fs::File::from_raw_fd(master_write_fd) };
            let stdin: Box<dyn Write + Send> = Box::new(master_write);
            Ok((ChildProcess { pid: child }, master_read, stdin, master_write_fd))
        }
    }
}

