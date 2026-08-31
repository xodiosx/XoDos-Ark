//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.
//!
//! A rootfs is considered **proot‑compatible** only when **all** of these exist:
//! * `.xodos2_rootfs_ok` sentinel
//! * `etc/os-release` or ( `usr/bin` and `root` ) – broad compatibility
//! * `sys/.empty` directory
//! If any of them is missing, the container is treated as non‑proot and a
//! lightweight Android shell is launched with `PREFIX` pointing to its `/usr`.

//! Proot argv and environment for the interactive shell, plus PTY‑spawn logic.

use super::{get_application_context, has_rootfs};
use anyhow::{Context, Result};
use nix::pty::{forkpty, ForkptyResult, Winsize};
use nix::unistd::{dup, execve, Pid};
use std::ffi::CString;
use std::fs::{self, File};
use std::io::Write;
use std::os::fd::IntoRawFd;
use std::os::unix::io::{FromRawFd, RawFd};
use std::path::{Path, PathBuf};
use log::{info, warn, error};

// --------------------------------------------------------------------------
// Compatibility check
// --------------------------------------------------------------------------

fn is_proot_compatible(rootfs: &Path) -> bool {
    let compatible = has_rootfs(rootfs)
        && rootfs.join("sys/.empty").is_dir()
        && (rootfs.join("etc/os-release").exists()
            || (rootfs.join("usr/bin").is_dir() && rootfs.join("root").is_dir()));
    info!("proot: rootfs {:?} proot_compatible={}", rootfs, compatible);
    compatible
}

// --------------------------------------------------------------------------
// Proot binary and loader paths
// --------------------------------------------------------------------------

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
    info!("proot: using proot={:?}, loader={:?}", proot, loader);
    Ok((proot, loader))
}

// --------------------------------------------------------------------------
// Argument builder
// --------------------------------------------------------------------------

pub(super) fn build_exec_args(
    rootfs: &Path,
) -> Result<(Vec<CString>, Vec<CString>)> {
    let ctx = get_application_context()?;
    let mut argv: Vec<CString> = Vec::new();
    let mut env: Vec<CString> = Vec::new();

    if is_proot_compatible(rootfs) {
        // ---------- Minimal, proven proot command ----------
        let (proot, _loader) = proot_and_loader_paths()?;
        argv.push(CString::new(proot.to_string_lossy().as_bytes())?);

        argv.push(CString::new("--change-id=0:0").unwrap());
        argv.push(CString::new("--link2symlink").unwrap());
        argv.push(CString::new("--kill-on-exit").unwrap());
        argv.push(CString::new("--sysvipc").unwrap());
        argv.push(CString::new(format!("--rootfs={}", rootfs.display())).unwrap());

        // Essential binds
        argv.push(CString::new("--bind=/dev").unwrap());
        argv.push(CString::new("--bind=/proc").unwrap());
        argv.push(CString::new("--bind=/sys").unwrap());

        // Bind a writable /tmp inside the rootfs
        let tmp_dir = rootfs.join("tmp");
        fs::create_dir_all(&tmp_dir)?;
        argv.push(CString::new(format!("--bind={}:/tmp", tmp_dir.display())).unwrap());

        argv.push(CString::new("--cwd=/root").unwrap());

        // Clear environment and set only necessary variables
        argv.push(CString::new("/usr/bin/env").unwrap());
        argv.push(CString::new("-i").unwrap());

        env.push(CString::new("HOME=/root").unwrap());
        env.push(CString::new("TERM=xterm-256color").unwrap());
        env.push(CString::new("LANG=C.UTF-8").unwrap());
        env.push(CString::new("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin").unwrap());

        // Shell detection – use full path without stripping
        let standard_shells = [
            "/usr/bin/bash", "/bin/bash",
            "/usr/bin/sh", "/bin/sh",
            "/usr/bin/dash", "/bin/dash",
            "/usr/bin/ash", "/bin/ash",
        ];

        info!("proot: checking shells in rootfs {:?}: {:?}", rootfs, standard_shells);

        let shell_path = standard_shells.iter()
            .find(|s| {
                let exists = rootfs.join(s.trim_start_matches('/')).exists();
                info!("proot: checking shell {}: exists={}", s, exists);
                exists
            })
            .ok_or_else(|| {
                error!("proot: no usable shell found in rootfs {:?}", rootfs);
                anyhow::anyhow!("no usable shell found in rootfs")
            })?;

        info!("proot: selected shell: {}", shell_path);

        argv.push(CString::new(shell_path.to_string()).unwrap());
        argv.push(CString::new("-l").unwrap());
    } else {
        // ---------- fallback: Termux-style Native Bionic environment ----------
        warn!("proot: rootfs {:?} is not proot-compatible, using fallback", rootfs);
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

    // Log the final argv and env for debugging
    let argv_strings: Vec<String> = argv.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    info!("proot: final argv = {:?}", argv_strings);
    let env_strings: Vec<String> = env.iter().map(|s| s.to_string_lossy().into_owned()).collect();
    info!("proot: final env = {:?}", env_strings);

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
            info!("proot: child about to execve: {:?}", argv_refs);
            if execve(argv[0].as_c_str(), &argv_refs, &env_refs).is_err() {
                error!("proot: execve failed: {:?}", std::io::Error::last_os_error());
                unsafe { nix::libc::_exit(1) };
            }
            unreachable!();
        }
        ForkptyResult::Parent { child, master } => {
            info!("proot: forkpty succeeded, child pid = {}", child);
            let master_read_fd = dup(&master).context("dup master for read")?.into_raw_fd();
            let master_write_fd = master.into_raw_fd();
            let master_read = unsafe { File::from_raw_fd(master_read_fd) };
            let master_write = unsafe { File::from_raw_fd(master_write_fd) };
            let stdin: Box<dyn Write + Send> = Box::new(master_write);
            Ok((ChildProcess { pid: child }, master_read, stdin, master_write_fd))
        }
    }
}