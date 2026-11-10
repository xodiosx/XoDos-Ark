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

pub const ROOTFS_READY_SENTINEL: &str = ".xodos2_rootfs_ok";
pub const CONTAINERS_SUBDIR: &str = "containers";
pub const NUM_CONTAINERS: u32 = 3;

static APPLICATION_CONTEXT: Mutex<Option<ApplicationContext>> = Mutex::new(None);

#[derive(Clone, Debug)]
pub struct ApplicationContext {
    pub cache_dir: PathBuf,
    pub data_dir: PathBuf,
    pub native_library_dir: PathBuf,
    pub external_storage_path: Option<PathBuf>,
}

impl ApplicationContext {
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
            .map_err(|e| anyhow::anyhow!("lock poisoned: {:?}", e))? = Some(ctx);

        // Start native host services immediately after the context exists.
        spawn_xodos_host_if_present();

        Ok(())
    }
}

pub fn get_application_context() -> Result<ApplicationContext> {
    APPLICATION_CONTEXT
        .lock()
        .map_err(|e| anyhow::anyhow!("lock poisoned: {:?}", e))?
        .clone()
        .ok_or_else(|| anyhow::anyhow!("ApplicationContext not initialized"))
}

// --------------------------------------------------------------------------
// xodos_host – native XoDos host bridge daemon
// --------------------------------------------------------------------------

static XODOS_HOST_STARTED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

/// Start xodos-host automatically and keep it alive.
/// The function is safe to call multiple times.
pub fn spawn_xodos_host_if_present() {
    use std::sync::atomic::Ordering;

    if XODOS_HOST_STARTED
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return;
    }

    if let Err(e) = std::thread::Builder::new()
        .name("xodos-host-supervisor".into())
        .spawn(xodos_host_supervisor_main)
    {
        log::warn!("xodos-host: supervisor thread failed: {:?}", e);
        XODOS_HOST_STARTED.store(false, Ordering::SeqCst);
    }
}

fn xodos_host_supervisor_main() {
    loop {
        let Some((exe, home, prefix, log_path)) = prepare_xodos_host() else {
            // Binary is not installed yet. Keep checking so installing it
            // later does not require restarting the application.
            std::thread::sleep(std::time::Duration::from_secs(3));
            continue;
        };

        run_xodos_host_until_exit(exe, home, prefix, log_path);

        // If xodos-host exits, automatically start it again.
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
}

fn prepare_xodos_host() -> Option<(PathBuf, PathBuf, PathBuf, PathBuf)> {
    let ctx = get_application_context().ok()?;

    // Adjust/add candidates here if your binary is stored elsewhere.
    let candidates = [
        ctx.data_dir.join("usr/bin/xodos-host"),
        ctx.data_dir.join("bin/xodos-host"),
        ctx.native_library_dir.join("xodos-host"),
        ctx.data_dir.join("xodos-host"),
    ];

    let exe = candidates.iter().find(|p| p.is_file())?.clone();

    let home = ctx.data_dir.join("home");
    let prefix = ctx.data_dir.join("usr");
    let runtime = ctx.data_dir.join("xrun");

    let _ = std::fs::create_dir_all(&runtime);

    let log_path = runtime.join("xodos-host.log");

    Some((exe, home, prefix, log_path))
}

fn run_xodos_host_until_exit(
    exe: PathBuf,
    home: PathBuf,
    prefix: PathBuf,
    log_path: PathBuf,
) {
    let log_file = match OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
    {
        Ok(f) => f,
        Err(e) => {
            log::warn!("xodos-host: cannot open log {:?}: {:?}", log_path, e);
            return;
        }
    };

    let _ = writeln!(
        &log_file,
        "\n--- xodos-host start {:?} exe={:?} ---",
        std::time::SystemTime::now(),
        exe
    );

    let stderr = match log_file.try_clone() {
        Ok(f) => std::process::Stdio::from(f),
        Err(_) => std::process::Stdio::null(),
    };

    let stdout = match log_file.try_clone() {
        Ok(f) => std::process::Stdio::from(f),
        Err(_) => std::process::Stdio::null(),
    };

    use std::process::Command;

    // xodos-host is a native Android/Bionic executable.
    // When it lives under app-private /files/, invoke it through
    // Android's native linker.
    let mut cmd = if exec_from_app_data(&exe) {
        let mut c = Command::new(linker());
        c.arg(&exe);
        c
    } else {
        Command::new(&exe)
    };

    cmd.env("HOME", &home)
        .env("PREFIX", &prefix)
        .env("PATH", format!(
            "{}/bin:/system/bin:/system/xbin",
            prefix.display()
        ))
        // Do not inherit an environment that could interfere with
        // native Bionic loading.
        .env_remove("LD_PRELOAD")
        .env_remove("LD_LIBRARY_PATH")
        .stdin(std::process::Stdio::null())
        .stdout(stdout)
        .stderr(stderr);

    log::info!("xodos-host: starting {:?}", exe);

    match cmd.spawn() {
        Ok(mut child) => {
            let pid = child.id();

            log::info!("xodos-host: started pid={}", pid);

            match child.wait() {
                Ok(status) => {
                    log::warn!(
                        "xodos-host: pid={} exited with {}",
                        pid,
                        status
                    );
                }
                Err(e) => {
                    log::warn!(
                        "xodos-host: pid={} wait failed: {:?}",
                        pid,
                        e
                    );
                }
            }
        }

        Err(e) => {
            log::warn!(
                "xodos-host: failed to spawn {:?}: {:?}",
                exe,
                e
            );
        }
    }
}

// ---------- Container helpers ----------

pub fn container_rootfs_dir(container_id: u32) -> Result<PathBuf> {
    if container_id < 1 || container_id > NUM_CONTAINERS {
        anyhow::bail!("invalid container id {}", container_id);
    }
    Ok(get_application_context()?
        .data_dir
        .join(CONTAINERS_SUBDIR)
        .join(container_id.to_string()))
}

pub fn has_rootfs(root: &Path) -> bool {
    root.join(ROOTFS_READY_SENTINEL).exists()
}

pub fn has_container_rootfs(container_id: u32) -> bool {
    container_rootfs_dir(container_id)
        .map(|p| has_rootfs(&p))
        .unwrap_or(false)
}

pub fn installed_containers() -> Vec<u32> {
    (1..=NUM_CONTAINERS)
        .filter(|&id| has_container_rootfs(id))
        .collect()
}

pub fn first_installed_container() -> Option<u32> {
    (1..=NUM_CONTAINERS)
        .find(|&id| has_container_rootfs(id))
}

// --------------------------------------------------------------------------
// pulse_host
// --------------------------------------------------------------------------

pub const HOST_PULSE_TCP_PORT: u16 = 4713;
pub const GUEST_PULSE_RUNTIME_MOUNT: &str = "/run/xodos2-pulse";
pub const GUEST_PULSE_UNIX_SOCKET: &str = "/run/xodos2-pulse/native";
pub const PULSE_PREFIX_SUBDIR: &str = "pulse";

fn linker() -> &'static str {
    match std::env::consts::ARCH {
        "aarch64" | "x86_64" => "/system/bin/linker64",
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

fn run_until_exit(
    exe: PathBuf,
    runtime_dir: PathBuf,
    pulse_prefix: Option<PathBuf>,
    port: u16,
) {
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
    let mut log = match OpenOptions::new()
        .create(true)
        .append(true)
        .open(&err_path)
    {
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
        .arg("-L")
        .arg("module-aaudio-sink sink_name=xodosark-out")
        .arg("-L")
        .arg("module-null-sink sink_name=xodosark-mix")
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
    .arg("module-aaudio-sink sink_name=xodos2-out");

// ── NEW: conditionally load the mic source ──
// The flag is written/deleted by MicManager on the Kotlin side.
// Read on every loop iteration, so toggling the switch is picked up
// on the next daemon restart (which happens automatically on app restart).
let mic_enabled = get_application_context()
    .ok()
    .map(|ctx| ctx.data_dir.join("mic_enabled").exists())
    .unwrap_or(false);

if mic_enabled {
    cmd.arg("-L")
        .arg("module-sles-source rate=16000 channels=1 source_name=xodosark-mic");
}
// 

cmd.env("PULSE_RUNTIME_PATH", &runtime_dir)
    .env("XDG_RUNTIME_DIR", &runtime_dir)
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
// virgl_host – dual server support with separate sockets
// --------------------------------------------------------------------------

const VENUS_SOCK: &str = "venus.sock";
const ANGLE_SOCK: &str = "vtest.sock";

static VIRGL_STATE: Mutex<Option<VirglState>> = Mutex::new(None);

struct VirglState {
    venus_child: Option<std::process::Child>,
    angle_child: Option<std::process::Child>,
}

impl Drop for VirglState {
    fn drop(&mut self) {
        if let Some(ref mut c) = self.venus_child {
            let _ = c.kill();
            let _ = c.wait();
        }
        if let Some(ref mut c) = self.angle_child {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}

pub fn host_virgl_runtime_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("virgl-run")
}

/// Locate a VirGL binary with smart fallback.
fn find_virgl_binary(ctx: &ApplicationContext, name: &str) -> Option<PathBuf> {
    let candidates = [
        ctx.data_dir.join("usr/bin").join(name),
        ctx.data_dir.join("virgl/bin").join(name),
        ctx.native_library_dir.join(name),
        ctx.data_dir.join("bin").join(name),
    ];
    candidates.into_iter().find(|p| p.is_file())
}

fn prepare_runtime_dir(ctx: &ApplicationContext) -> Result<PathBuf> {
    let rt = host_virgl_runtime_dir(&ctx.data_dir);
    std::fs::create_dir_all(&rt)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&rt, std::fs::Permissions::from_mode(0o700));
    }
    Ok(std::fs::canonicalize(&rt).unwrap_or(rt))
}

/// Build LD_LIBRARY_PATH only for legacy fallback binaries.
fn build_ld_paths(ctx: &ApplicationContext) -> String {
    let mut paths = Vec::new();
    paths.push(ctx.data_dir.join("virgl/lib").to_string_lossy().into_owned());
    paths.push(ctx.native_library_dir.to_string_lossy().into_owned());
    if let Ok(angle_dir) = ctx.data_dir.join("virgl/angle/vulkan").canonicalize() {
        paths.push(angle_dir.to_string_lossy().into_owned());
    }
    paths.join(":")
}

fn spawn_server(
    ctx: &ApplicationContext,
    binary_name: &str,
    socket_name: &str,
    extra_envs: &[(&str, &str)],
) -> Option<std::process::Child> {
    let binary = find_virgl_binary(ctx, binary_name)?;
    let rt = prepare_runtime_dir(ctx).ok()?;
    let sock = rt.join(socket_name);
    let _ = std::fs::remove_file(&sock);

    let using_usr_bin = binary.starts_with(&ctx.data_dir.join("usr/bin"));

    let log_path = rt.join(format!("{}.log", socket_name));
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

    use std::process::Command;
    // CRITICAL: Trust the native OS loader if using usr/bin prefix so RPATH is honored.
    let mut cmd = if using_usr_bin {
        Command::new(&binary)
    } else if exec_from_app_data(&binary) {
        let mut c = Command::new(linker());
        c.arg(&binary);
        c
    } else {
        Command::new(&binary)
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

    // Set the specific arguments depending on the backend and if it's the regular prefix binary
    if socket_name == VENUS_SOCK {
        cmd.args(&["--no-virgl", "--venus"]);
    } else if socket_name == ANGLE_SOCK {
        if using_usr_bin {
            // New prefix binary does not accept --use-egl-surfaceless
            cmd.arg("--angle-gl");
        } else {
            // Fallback legacy binary expects surfaceless flag
            cmd.args(&["--use-gles", "--use-egl-surfaceless"]);
        }
    }

    cmd.arg("--socket-path").arg(&sock);
    cmd.current_dir(&rt);
    
    let rt_str = rt.to_string_lossy().to_string();
    cmd.env("XDG_RUNTIME_DIR", &rt_str)
        .env("TMPDIR", &rt_str);

    // CRITICAL: Unset LD_LIBRARY_PATH for usr/bin prefix to avoid symbol mixing.
if using_usr_bin {
    // For the Android‑specific binary, we must provide the correct libepoxy.
    if binary_name == "virgl_test_server_android" {
        let opt_lib = ctx.data_dir.join("usr/opt/virglrenderer-android/lib");
        if opt_lib.exists() {
            cmd.env("LD_LIBRARY_PATH", opt_lib);
        } else {
            // fallback (should not happen in your setup)
            cmd.env_remove("LD_LIBRARY_PATH");
        }
    } else {
        cmd.env_remove("LD_LIBRARY_PATH");
    }
    cmd.env("PATH", ctx.data_dir.join("usr/bin").to_string_lossy().into_owned());
} else {
    let ld_path = build_ld_paths(ctx);
    cmd.env("LD_LIBRARY_PATH", &ld_path);
}

    for &(k, v) in extra_envs {
        cmd.env(k, v);
    }

    // RENDER_SERVER_EXEC_PATH
    let render_path = if using_usr_bin {
        ctx.data_dir.join("usr/libexec/virgl_render_server")
    } else {
        ctx.data_dir.join("virgl/bin/virgl_render_server")
    };
    if render_path.is_file() {
        cmd.env("RENDER_SERVER_EXEC_PATH", render_path);
    }

    // Venus specific environment
    if socket_name == VENUS_SOCK {
        let usr = ctx.data_dir.join("usr");
        let icd_json = usr.join("share/vulkan/icd.d/wrapper_icd.aarch64.json");
        if icd_json.exists() {
            cmd.env("VK_ICD_FILENAMES", icd_json);
        }
        
        if !using_usr_bin {
            let wrapper = usr.join("lib/libvulkan_wrapper.so");
            if wrapper.exists() {
                let existing = std::env::var("LD_PRELOAD").unwrap_or_default();
                let new_preload = if existing.is_empty() {
                    wrapper.to_string_lossy().into_owned()
                } else {
                    format!("{}:{}", wrapper.display(), existing)
                };
                cmd.env("LD_PRELOAD", &new_preload);
            }
        }
    }

    // Angle fallback environment logic
    if socket_name == ANGLE_SOCK && !using_usr_bin {
        if let Some(angle_dir) = ctx.data_dir.join("virgl/angle/vulkan").canonicalize().ok() {
            let crcfix = angle_dir.join("libcrcfix.so");
            if crcfix.exists() {
                let existing = std::env::var("LD_PRELOAD").unwrap_or_default();
                let new_preload = if existing.is_empty() {
                    crcfix.to_string_lossy().into_owned()
                } else {
                    format!("{}:{}", crcfix.display(), existing)
                };
                cmd.env("LD_PRELOAD", &new_preload);
            }
            cmd.env("ANGLE_LIBS_DIR", angle_dir);
        }
    }

    cmd.stdin(std::process::Stdio::null())
        .stdout(stdout)
        .stderr(stderr);

    match cmd.spawn() {
        Ok(mut child) => {
            std::thread::sleep(std::time::Duration::from_millis(200));
            match child.try_wait() {
                Ok(Some(st)) => {
                    log::warn!("virgl: {} exited early: {:?}", socket_name, st);
                    None
                }
                Ok(None) => Some(child),
                Err(e) => {
                    log::warn!("virgl: try_wait: {:?}", e);
                    None
                }
            }
        }
        Err(e) => {
            log::warn!("virgl: spawn {} failed: {:?}", socket_name, e);
            None
        }
    }
}

/// Starts servers according to mask (bit0 = Venus, bit1 = Angle).
pub fn start_virgl_servers(mask: u32) {
    let ctx = match get_application_context() {
        Ok(c) => c,
        Err(_) => return,
    };

    let mut state = VIRGL_STATE.lock().unwrap();
    if state.is_some() {
        return;
    }

    let mut new_state = VirglState {
        venus_child: None,
        angle_child: None,
    };

    if mask & 1 != 0 {
        new_state.venus_child = spawn_server(
            &ctx,
            "virgl_test_server",
            VENUS_SOCK,
            &[("ANDROID_VENUS", "1")],
        );
    }
    if mask & 2 != 0 {
        new_state.angle_child = spawn_server(
            &ctx,
            "virgl_test_server_android",
            ANGLE_SOCK,
            &[],
        );
    }

    if new_state.venus_child.is_none() && new_state.angle_child.is_none() {
        log::warn!("virgl: no servers started");
        return;
    }
    *state = Some(new_state);
}

pub fn stop_if_running() {
    let mut state = VIRGL_STATE.lock().unwrap();
    if let Some(mut s) = state.take() {
        if let Some(ref mut c) = s.venus_child {
            let _ = c.kill();
            let _ = c.wait();
        }
        if let Some(ref mut c) = s.angle_child {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
    if let Ok(ctx) = get_application_context() {
        let rt = host_virgl_runtime_dir(&ctx.data_dir);
        let _ = std::fs::remove_file(rt.join(VENUS_SOCK));
        let _ = std::fs::remove_file(rt.join(ANGLE_SOCK));
    }
}

/// Legacy wrapper – starts Angle only.
pub fn start_if_possible() {
    start_virgl_servers(2);
}
