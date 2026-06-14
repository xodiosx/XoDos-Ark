//! Android module – application context, PulseAudio, virgl, proot, and rootfs containers.

pub mod proot;
pub mod rootfs_fetch;

use anyhow::Result;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

// --------------------------------------------------------------------------
// application_context (container‑aware)
// --------------------------------------------------------------------------

/// Sentinel file written after successful extract; if present, rootfs is ready.
pub const ROOTFS_READY_SENTINEL: &str = ".xodos2_rootfs_ok";

/// Base directory for container rootfs slots.
pub const CONTAINERS_SUBDIR: &str = "containers";

/// Number of fixed container slots (1‑based).
pub const NUM_CONTAINERS: u32 = 3;

static APPLICATION_CONTEXT: Mutex<Option<ApplicationContext>> = Mutex::new(None);

#[derive(Clone, Debug)]
pub struct ApplicationContext {
    pub cache_dir: PathBuf,
    pub data_dir: PathBuf,
    pub native_library_dir: PathBuf,
    /// If set, proot binds this into the guest as `/android` and `/root/Android`.
    pub external_storage_path: Option<PathBuf>,
}

impl ApplicationContext {
    /// Initialize from paths (JNI). Call before any other native method.
    pub fn init_from_paths(
        data_dir: PathBuf,
        cache_dir: PathBuf,
        native_library_dir: PathBuf,
        external_storage_path: Option<PathBuf>,
    ) -> Result<()> {
        let ctx = ApplicationContext {
            cache_dir,
            data_dir,
            native_library_dir,
            external_storage_path,
        };
        *APPLICATION_CONTEXT
            .lock()
            .map_err(|e| anyhow::anyhow!("ApplicationContext lock poisoned: {:?}", e))? = Some(ctx);
        Ok(())
    }
}

pub fn get_application_context() -> Result<ApplicationContext> {
    APPLICATION_CONTEXT
        .lock()
        .map_err(|e| anyhow::anyhow!("ApplicationContext lock poisoned: {:?}", e))?
        .clone()
        .ok_or_else(|| anyhow::anyhow!("ApplicationContext not initialized"))
}

// ---------- Container helpers ----------

/// Returns the rootfs directory for a given 1‑based container ID.
pub fn container_rootfs_dir(container_id: u32) -> Result<PathBuf> {
    if container_id < 1 || container_id > NUM_CONTAINERS {
        anyhow::bail!("invalid container id {}", container_id);
    }
    Ok(get_application_context()?
        .data_dir
        .join(CONTAINERS_SUBDIR)
        .join(container_id.to_string()))
}

/// Rootfs is ready iff the extract sentinel exists.
pub fn has_rootfs(root: &Path) -> bool {
    root.join(ROOTFS_READY_SENTINEL).exists()
}

/// Check if a specific container has a rootfs installed.
pub fn has_container_rootfs(container_id: u32) -> bool {
    container_rootfs_dir(container_id)
        .map(|p| has_rootfs(&p))
        .unwrap_or(false)
}

/// Returns a list of container IDs that are currently installed.
pub fn installed_containers() -> Vec<u32> {
    (1..=NUM_CONTAINERS)
        .filter(|&id| has_container_rootfs(id))
        .collect()
}

/// Returns the first installed container ID, or `None` if none.
pub fn first_installed_container() -> Option<u32> {
    (1..=NUM_CONTAINERS).find(|&id| has_container_rootfs(id))
}

// --------------------------------------------------------------------------
// pulse_host (unchanged from previous, but included for completeness)
// --------------------------------------------------------------------------

pub const HOST_PULSE_TCP_PORT: u16 = 4713;
pub const GUEST_PULSE_RUNTIME_MOUNT: &str = "/run/xodos2-pulse";
pub const GUEST_PULSE_UNIX_SOCKET: &str = "/run/xodos2-pulse/native";
pub const PULSE_PREFIX_SUBDIR: &str = "pulse";

fn linker() -> &'static str {
    match std::env::consts::ARCH {
        "aarch64" | "x86_64" => "/system/bin/linker64",
        "arm" | "x86" => "/system/bin/linker",
        _ => "/system/bin/linker64",
    }
}

fn exec_from_app_data(exe: &Path) -> bool {
    exe.to_string_lossy().contains("/files/")
}

pub fn host_pulse_runtime_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("pulse-run")
}

pub fn guest_pulse_server_env() -> String {
    format!("unix:{}", GUEST_PULSE_UNIX_SOCKET)
}

static PULSE_SUPERVISOR_STARTED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub fn spawn_host_pulseaudio_if_present() {
    use std::sync::atomic::Ordering;
    if PULSE_SUPERVISOR_STARTED
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return;
    }
    if let Err(e) = std::thread::Builder::new()
        .name("pulse-supervisor".into())
        .spawn(pulse_supervisor_main)
    {
        log::warn!("pulse: supervisor thread: {:?}", e);
        PULSE_SUPERVISOR_STARTED.store(false, Ordering::SeqCst);
    }
}

fn pulse_supervisor_main() {
    loop {
        if let Some((exe, rt, prefix, port)) = prepare() {
            run_until_exit(exe, rt, prefix, port);
        }
        std::thread::sleep(std::time::Duration::from_secs(3));
    }
}

fn prepare() -> Option<(PathBuf, PathBuf, Option<PathBuf>, u16)> {
    let ctx = get_application_context().ok()?;
    let rt = host_pulse_runtime_dir(&ctx.data_dir);
    let packaged = ctx.data_dir.join(PULSE_PREFIX_SUBDIR);
    let candidates = [
        packaged.join("bin/pulseaudio"),
        ctx.native_library_dir.join("pulseaudio"),
        ctx.data_dir.join("bin/pulseaudio"),
    ];
    let exe = candidates.iter().find(|p| p.is_file())?.clone();
    let prefix = exe.starts_with(&packaged).then_some(packaged);
    Some((exe, rt, prefix, HOST_PULSE_TCP_PORT))
}

fn ld_modules(prefix: Option<&PathBuf>) -> Option<std::ffi::OsString> {
    let root = prefix?;
    let parts = [
        root.join("lib"),
        root.join("lib/pulseaudio"),
        root.join("lib/pulseaudio/modules"),
    ];
    std::env::join_paths(parts.iter()).ok()
}

fn run_until_exit(exe: PathBuf, runtime_dir: PathBuf, pulse_prefix: Option<PathBuf>, port: u16) {
    if std::fs::create_dir_all(&runtime_dir).is_err() {
        return;
    }
    let runtime_dir = std::fs::canonicalize(&runtime_dir).unwrap_or(runtime_dir);
    let tmpdir = runtime_dir.join("tmp");
    let _ = std::fs::create_dir_all(&tmpdir);
    let unix_sock = runtime_dir.join("native");
    let _ = std::fs::remove_file(runtime_dir.join("pulse/native"));
    let _ = std::fs::remove_file(&unix_sock);
    let _ = std::fs::remove_file(runtime_dir.join("pulse/pid"));
    let _ = std::fs::remove_file(runtime_dir.join("pulse/pid.lock"));

    let err_path = runtime_dir.join("pulseaudio-stderr.log");
    let mut log = match OpenOptions::new().create(true).append(true).open(&err_path) {
        Ok(f) => f,
        Err(_) => return,
    };
    let _ = writeln!(
        &mut log,
        "\n--- pulse {:?} exe={:?} sock={:?} ---",
        std::time::SystemTime::now(),
        exe,
        unix_sock
    );
    let _ = log.flush();
    let stderr = match log.try_clone() {
        Ok(c) => std::process::Stdio::from(c),
        Err(_) => std::process::Stdio::null(),
    };

    use std::process::Command;
    let mut cmd = if exec_from_app_data(&exe) {
        let mut c = Command::new(linker());
        c.arg(&exe);
        c
    } else {
        Command::new(&exe)
    };

    cmd.arg("-n")
    .arg("--use-pid-file=no")
    .arg("--disable-shm=yes")
    .arg("--exit-idle-time=-1")
    .arg("--daemonize=no")
    .arg("--log-target=stderr")
    .arg("--log-level=debug")
    // 1. Load the actual audio output FIRST – this becomes the default sink
    .arg("-L")
    .arg("module-aaudio-sink sink_name=xodosark-out")
    // 2. Then the null sink (if you still need it for mixing)
    .arg("-L")
    .arg("module-null-sink sink_name=xodosark-mix")
    // 3. Finally the network / unix protocol modules
    .arg("-L")
    .arg(format!(
        "module-native-protocol-unix socket={}",
        unix_sock.display()
    ))
    .arg("-L")
    .arg(format!(
        "module-native-protocol-tcp listen=127.0.0.1 port={} auth-anonymous=1",
        port
    ))
        .arg("-L")
        .arg("module-aaudio-sink sink_name=xodos2-out")
        .env("PULSE_RUNTIME_PATH", &runtime_dir)
        .env("XDG_RUNTIME_DIR", &runtime_dir)
        .env("HOME", &runtime_dir)
        .env("TMPDIR", &tmpdir)
        .env_remove("PULSE_SERVER")
        .env_remove("PULSE_COOKIE")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(stderr);

    if let Some(ref pfx) = pulse_prefix {
        cmd.env("PULSE_DLPATH", pfx.join("lib/pulseaudio/modules"));
    }
    if let Some(ld) = ld_modules(pulse_prefix.as_ref()) {
        cmd.env("LD_LIBRARY_PATH", ld);
    }

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            let _ = writeln!(&mut log, "spawn failed: {:?}", e);
            log::warn!("pulse: spawn failed: {:?}", e);
            return;
        }
    };
    let pid = child.id();
    let _ = writeln!(&mut log, "pid={}", pid);

    for _ in 0..100 {
        if unix_sock.exists() {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    if !unix_sock.exists() {
        log::warn!("pulse: socket missing after wait; see {:?}", err_path);
    }

    match child.wait() {
        Ok(s) => {
            let _ = writeln!(&mut log, "exit: {}", s);
            log::warn!("pulse: exited: {}", s);
        }
        Err(e) => log::warn!("pulse: wait: {:?}", e),
    }
}

// --------------------------------------------------------------------------
// virgl_host (unchanged)
// --------------------------------------------------------------------------

const VIRGL: &str = "virgl";

pub fn host_virgl_runtime_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("virgl-run")
}

fn exe_candidates(ctx: &ApplicationContext) -> [PathBuf; 3] {
    [
        ctx.data_dir
            .join(VIRGL)
            .join("bin/virgl_test_server_android"),
        ctx.native_library_dir.join("virgl_test_server_android"),
        ctx.data_dir.join("bin/virgl_test_server_android"),
    ]
}

fn exec_from_app_data_virgl(exe: &Path) -> bool {
    exe.to_string_lossy().contains("/files/")
}

static CHILD: Mutex<Option<std::process::Child>> = Mutex::new(None);

fn uid_line(status: &str) -> Option<u32> {
    let line = status.lines().find(|l| l.starts_with("Uid:"))?;
    line.split_whitespace().nth(1)?.parse().ok()
}

fn pgid_line(status: &str) -> Option<i32> {
    let line = status.lines().find(|l| l.starts_with("Pgid:"))?;
    line.split_whitespace().nth(1)?.parse().ok()
}

fn kill_stragglers() {
    let me = unsafe { libc::getuid() };
    let Ok(rd) = std::fs::read_dir("/proc") else { return };
    for e in rd.flatten() {
        let Ok(pid) = e.file_name().to_string_lossy().parse::<libc::pid_t>() else {
            continue;
        };
        if pid <= 1 {
            continue;
        }
        let p = e.path();
        let Ok(st) = std::fs::read_to_string(p.join("status")) else {
            continue;
        };
        if uid_line(&st) != Some(me) {
            continue;
        }
        let Ok(raw) = std::fs::read(p.join("cmdline")) else {
            continue;
        };
        let cmd = String::from_utf8_lossy(&raw);
        if !cmd.contains("virgl_test_server_android") && !cmd.contains("virgl_render_server") {
            continue;
        }
        unsafe {
            libc::kill(pid, libc::SIGKILL);
        }
    }
}

pub fn stop_if_running() {
    let mut g = match CHILD.lock() {
        Ok(x) => x,
        Err(_) => {
            kill_stragglers();
            rm_socket();
            return;
        }
    };
    if let Some(mut ch) = g.take() {
        let pid = ch.id() as i32;
        if pid > 0 {
            let kill_pg = std::fs::read_to_string(format!("/proc/{pid}/status"))
                .ok()
                .and_then(|s| pgid_line(&s))
                .map(|pg| pg == pid)
                .unwrap_or(false);
            if kill_pg {
                unsafe {
                    libc::kill(-pid, libc::SIGKILL);
                }
            } else {
                let _ = ch.kill();
            }
        } else {
            let _ = ch.kill();
        }
        let _ = ch.wait();
    }
    kill_stragglers();
    rm_socket();
}

fn rm_socket() {
    if let Ok(ctx) = get_application_context() {
        let _ = std::fs::remove_file(host_virgl_runtime_dir(&ctx.data_dir).join("vtest.sock"));
    }
}

pub fn start_if_possible() {
    let ctx = match get_application_context() {
        Ok(c) => c,
        Err(_) => return,
    };
    {
        let g = match CHILD.lock() {
            Ok(x) => x,
            Err(_) => return,
        };
        if g.is_some() {
            return;
        }
    }

    let Some(exe) = exe_candidates(&ctx).into_iter().find(|p| p.is_file()) else {
        log::warn!("virgl: virgl_test_server_android not found");
        return;
    };

    let rt = host_virgl_runtime_dir(&ctx.data_dir);
    if std::fs::create_dir_all(&rt).is_err() {
        return;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&rt, std::fs::Permissions::from_mode(0o700));
    }
    let rt = std::fs::canonicalize(&rt).unwrap_or(rt);
    let sock = rt.join("vtest.sock");
    let _ = std::fs::remove_file(&sock);

    let log_path = rt.join("virgl_host_stderr.log");
    let log_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .ok();
    let stderr = log_file
        .as_ref()
        .and_then(|f| f.try_clone().ok())
        .map(std::process::Stdio::from)
        .unwrap_or(std::process::Stdio::null());
    let stdout = log_file
        .as_ref()
        .and_then(|f| f.try_clone().ok())
        .map(std::process::Stdio::from)
        .unwrap_or(std::process::Stdio::null());

    // --- STEP 1: RESOLVE FULL ABSOLUTE PATHS FIRST ---
    let angle_dir = ctx.data_dir.join(VIRGL).join("angle/vulkan");
    let angle_resolved = angle_dir
        .is_dir()
        .then(|| std::fs::canonicalize(&angle_dir).unwrap_or_else(|_| angle_dir.clone()));

    // Build LD_LIBRARY_PATH using absolute paths dynamically
    let lib = ctx.data_dir.join(VIRGL).join("lib");
    let bin = exe.parent().unwrap_or(Path::new("."));
    let mut ld: Vec<String> = vec![
        lib.to_string_lossy().into_owned(),
        bin.to_string_lossy().into_owned(),
    ];
    if let Some(ref p) = angle_resolved {
        ld.push(p.to_string_lossy().into_owned());
    }
    ld.push(ctx.native_library_dir.to_string_lossy().into_owned());
    ld.push(ctx.data_dir.join("usr/lib").to_string_lossy().into_owned());
    if let Ok(x) = std::env::var("LD_LIBRARY_PATH") {
        if !x.is_empty() {
            ld.push(x);
        }
    }
    let ld_library_path_string = ld.join(":");

    // --- STEP 2: INITIALIZE COMMAND ---
    use std::process::Command;
    let mut cmd = if exec_from_app_data_virgl(&exe) {
        let mut c = Command::new(linker());
        // linker64 only accepts the executable as the first argument.
        // It will read the library paths from the LD_LIBRARY_PATH env we set below.
        c.arg(&exe);
        c
    } else {
        Command::new(&exe)
    };

    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        unsafe {
            cmd.pre_exec(|| {
                let _ = libc::setpgid(0, 0);
                Ok(())
            });
        }
    }

    // --- STEP 3: APPLY ARGUMENTS AND ENVIRONMENT ---
    cmd.arg("--use-egl-surfaceless")
        .arg("--use-gles")
        .arg("--venus")
        .arg("--socket-path")
        .arg(sock.as_os_str());
        
    cmd.current_dir(&rt);
    let rts = rt.to_string_lossy().to_string();
    cmd.env("XDG_RUNTIME_DIR", &rts);
    cmd.env("TMPDIR", &rts);
    cmd.env("ANDROID_VENUS", "1");
    cmd.env("EGL_PLATFORM", "surfaceless");
    cmd.env("MESA_GLES_VERSION_OVERRIDE", "3.2");
    cmd.env("MESA_GL_VERSION_OVERRIDE", "3.3");
    
    // Explicitly inject the fully constructed absolute paths into the environment
    cmd.env("LD_LIBRARY_PATH", &ld_library_path_string); 

    let render = ctx.data_dir.join(VIRGL).join("bin/virgl_render_server");
    if render.is_file() {
        cmd.env("RENDER_SERVER_EXEC_PATH", render.to_string_lossy().as_ref());
    }

    // Build LD_PRELOAD list
    let mut preload_libs: Vec<String> = Vec::new();
    let system_vulkan = "/system/lib64/libvulkan.so";
    if std::path::Path::new(system_vulkan).exists() {
        preload_libs.push(system_vulkan.to_string());
    }
    if let Some(ref p) = angle_resolved {
        cmd.env("ANGLE_LIBS_DIR", p.to_string_lossy().as_ref());

        let crcfix_path = p.join("libcrcfix.so");
        if crcfix_path.exists() {
            preload_libs.push(crcfix_path.to_string_lossy().into_owned());
            log::debug!("virgl: adding LD_PRELOAD={}", crcfix_path.display());
        } else {
            log::warn!("virgl: libcrcfix.so not found at {}", crcfix_path.display());
        }
    }
    if !preload_libs.is_empty() {
        let ld_preload = preload_libs.join(":");
        log::debug!("virgl: LD_PRELOAD={}", ld_preload);
        cmd.env("LD_PRELOAD", &ld_preload);
    }

    cmd.stdin(std::process::Stdio::null()).stdout(stdout).stderr(stderr);

    // --- STEP 4: EXECUTE ---
    match cmd.spawn() {
        Ok(mut child) => {
            std::thread::sleep(std::time::Duration::from_millis(200));
            match child.try_wait() {
                Ok(Some(st)) => {
                    log::warn!("virgl: process exited immediately ({:?})", st);
                    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&log_path)
                    {
                        let _ = writeln!(f, "early exit: {:?}", st);
                    }
                }
                Ok(None) => {
                    if let Ok(mut g) = CHILD.lock() {
                        *g = Some(child);
                    }
                }
                Err(e) => log::warn!("virgl: try_wait: {:?}", e),
            }
        }
        Err(e) => {
            log::warn!("virgl: spawn failed: {:?}", e);
            if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&log_path) {
                let _ = writeln!(f, "spawn: {:?}", e);
            }
        }
    }
}