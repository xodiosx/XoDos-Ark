//! `libxodos2` – JNI bridge, PTY sessions, rootfs download, host services.
//! Only compiled for Android.

#[cfg(target_os = "android")]
mod android;

#[cfg(target_os = "android")]
mod combined {
    use super::android::{
      //  self,
        rootfs_fetch,
        proot,
        ApplicationContext,
        container_rootfs_dir, has_container_rootfs, installed_containers, first_installed_container,
     //   has_rootfs,
        spawn_host_pulseaudio_if_present,
        stop_if_running, start_if_possible,
    };

    use anyhow::{Context, Result};
    use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
    use jni::sys::{jboolean, jint};
    use jni::{JNIEnv, JavaVM};
    use std::collections::BTreeMap;
    use std::io::{Read, Write};
    use std::os::unix::io::RawFd;
    use std::path::PathBuf;
    use std::sync::{Mutex, OnceLock};
    use std::thread;

    // --------------------------------------------------------------------------
    // Session management (unchanged except spawning uses container paths)
    // --------------------------------------------------------------------------

    struct PtySession {
        _child: proot::ChildProcess,
        stdin: Box<dyn Write + Send>,
        master_fd: RawFd,
    }

    static SESSIONS: Mutex<BTreeMap<i32, PtySession>> = Mutex::new(BTreeMap::new());
    static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();
    static PTY_RELAY_CLASS: OnceLock<GlobalRef> = OnceLock::new();

    pub(super) fn init(
        data_dir: PathBuf,
        cache_dir: PathBuf,
        native_library_dir: PathBuf,
        external_storage_path: Option<PathBuf>,
    ) -> Result<()> {
        ApplicationContext::init_from_paths(
            data_dir,
            cache_dir,
            native_library_dir,
            external_storage_path,
        )?;
        Ok(())
    }

    pub(super) fn init_pty_output_jni(env: &mut JNIEnv) -> Result<()> {
        if JAVA_VM.get().is_none() {
            let vm = env.get_java_vm().context("JavaVM")?;
            let _ = JAVA_VM.set(vm);
        }
        if PTY_RELAY_CLASS.get().is_none() {
            let cls = env
                .find_class("app/xodos2/PtyOutputRelay")
                .context("find_class PtyOutputRelay")?;
            let g = env
                .new_global_ref(&cls)
                .context("global_ref PtyOutputRelay")?;
            let _ = PTY_RELAY_CLASS.set(g);
        }
        Ok(())
    }

    fn spawn_shell_in_rootfs(
        session_id: i32,
        rows: u16,
        cols: u16,
        rootfs_path: &std::path::Path,
    ) -> Result<()> {
        let mut map = SESSIONS
            .lock()
            .map_err(|e| anyhow::anyhow!("SESSIONS lock: {:?}", e))?;
        if map.contains_key(&session_id) {
            return Ok(());
        }
        let (child, read_file, stdin, master_fd) =
            proot::fork_pty_shell_in_rootfs(rootfs_path, rows, cols)?;
        map.insert(
            session_id,
            PtySession {
                _child: child,
                stdin,
                master_fd,
            },
        );
        drop(map);
        thread::spawn(move || {
            pty_master_reader_loop(session_id, read_file);
        });
        Ok(())
    }

    // ... (pty_master_reader_loop, post_pty_chunk_to_java unchanged, as in previous merged version)

    fn pty_master_reader_loop(session_id: i32, mut master_read: std::fs::File) {
        let mut buf = [0u8; 4096];
        loop {
            match master_read.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    post_pty_chunk_to_java(session_id, &buf[..n]);
                }
                Err(_) => break,
            }
        }
    }

    fn post_pty_chunk_to_java(session_id: i32, bytes: &[u8]) {
        let Some(vm) = JAVA_VM.get() else { return };
        let Some(class_ref) = PTY_RELAY_CLASS.get() else { return };
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return };
        let Ok(arr) = env.byte_array_from_slice(bytes) else { return };
        let Ok(local_cls) = env.new_local_ref(class_ref.as_obj()) else { return };
        let cls = JClass::from(local_cls);
        if let Err(e) = env.call_static_method(
            cls,
            "onPtyOutputChunk",
            "(I[B)V",
            &[JValue::Int(session_id), JValue::Object(arr.as_ref())],
        ) {
            log::warn!("onPtyOutputChunk: {:?}", e);
        }
    }

    pub(super) fn close_session(session_id: i32) -> Result<()> {
        let mut map = SESSIONS
            .lock()
            .map_err(|e| anyhow::anyhow!("SESSIONS lock: {:?}", e))?;
        map.remove(&session_id);
        Ok(())
    }

    pub(super) fn is_session_alive(session_id: i32) -> bool {
        SESSIONS
            .lock()
            .ok()
            .map(|m| m.contains_key(&session_id))
            .unwrap_or(false)
    }

    pub(super) fn write_input(session_id: i32, bytes: &[u8]) -> Result<()> {
        let mut map = SESSIONS
            .lock()
            .map_err(|e| anyhow::anyhow!("SESSIONS lock: {:?}", e))?;
        let s = map
            .get_mut(&session_id)
            .ok_or_else(|| anyhow::anyhow!("no session {}", session_id))?;
        s.stdin.write_all(bytes).map_err(|e| anyhow::anyhow!("stdin write: {}", e))?;
        s.stdin.flush().map_err(|e| anyhow::anyhow!("stdin flush: {}", e))?;
        Ok(())
    }

    pub(super) fn set_pty_window_size(session_id: i32, rows: u16, cols: u16) -> Result<()> {
        let map = SESSIONS
            .lock()
            .map_err(|e| anyhow::anyhow!("SESSIONS lock: {:?}", e))?;
        let fd = map
            .get(&session_id)
            .map(|s| s.master_fd)
            .ok_or_else(|| anyhow::anyhow!("no session {}", session_id))?;
        let ws = libc::winsize {
            ws_row: rows,
            ws_col: cols,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        unsafe {
            if libc::ioctl(fd, libc::TIOCSWINSZ, &ws as *const _) < 0 {
                let err = std::io::Error::last_os_error();
                return Err(anyhow::anyhow!("TIOCSWINSZ: {}", err));
            }
        }
        Ok(())
    }

    // --------------------------------------------------------------------------
    // JNI entry points
    // --------------------------------------------------------------------------

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_init(
        mut env: JNIEnv,
        _: JObject,
        data_dir: JString,
        cache_dir: JString,
        native_library_dir: JString,
        external_storage_dir: jni::sys::jstring,
    ) -> jboolean {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Warn)
                .with_tag("xodos2"),
        );
        std::panic::set_hook(Box::new(|info| {
            log::error!("xodos2: panic: {:?}", info);
        }));

        let data_dir: String = env.get_string(&data_dir).map(|s| s.into()).unwrap_or_default();
        let cache_dir: String = env.get_string(&cache_dir).map(|s| s.into()).unwrap_or_default();
        let native_library_dir: String = env.get_string(&native_library_dir).map(|s| s.into()).unwrap_or_default();

        let external_storage_path: Option<PathBuf> = if external_storage_dir.is_null() {
            None
        } else {
            let jstr = unsafe { JString::from_raw(external_storage_dir) };
            env.get_string(&jstr).ok().map(|s| PathBuf::from(String::from(s)))
        };

        if let Err(e) = init(
            PathBuf::from(data_dir),
            PathBuf::from(cache_dir),
            PathBuf::from(native_library_dir),
            external_storage_path,
        ) {
            log::error!("NativeBridge.init failed: {:?}", e);
            return 0;
        }

        if let Err(e) = init_pty_output_jni(&mut env) {
            log::error!("init_pty_output_jni: {:?}", e);
            return 0;
        }

        spawn_host_pulseaudio_if_present();
        1
    }

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_stopVirglHost(_env: JNIEnv, _: JObject) {
        stop_if_running();
    }

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_startVirglHostIfPossible(_env: JNIEnv, _: JObject) {
        start_if_possible();
    }

    // ---------- Container checks ----------

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_hasContainerRootfs(
        _env: JNIEnv,
        _: JObject,
        container_id: jint,
    ) -> jboolean {
        has_container_rootfs(container_id as u32) as jboolean
    }

    /// Returns a bitmask of installed containers: bit0=container1, bit1=container2, bit2=container3.
    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_getInstalledContainersMask(
        _env: JNIEnv,
        _: JObject,
    ) -> jint {
        let mut mask = 0i32;
        for id in installed_containers() {
            mask |= 1 << (id - 1);
        }
        mask
    }

    /// Returns the first installed container ID (1,2,3) or 0 if none.
    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_getDefaultContainer(
        _env: JNIEnv,
        _: JObject,
    ) -> jint {
        first_installed_container().map(|id| id as jint).unwrap_or(0)
    }

    // ---------- Installation into a specific container ----------

    /// Downloads and extracts a rootfs tarball into the chosen container slot.
    fn install_to_container(
        mut env: JNIEnv,
        container_id: jint,
        url: JString,
        tarball_name: JString,
        callback: JObject,
    ) -> jboolean {
        let vm = env.get_java_vm().expect("get JavaVM");
        let callback_global = env.new_global_ref(callback).expect("global ref for callback");

        let url: String = env.get_string(&url).map(|s| s.into()).unwrap_or_default();
        let tarball_name: String = env.get_string(&tarball_name).map(|s| s.into()).unwrap_or_default();
        let cid = container_id as u32;

        let rootfs_path = match container_rootfs_dir(cid) {
            Ok(p) => p,
            Err(e) => {
                log::error!("installToContainer: invalid container id {}: {:?}", cid, e);
                return 0;
            }
        };

        let result = thread::spawn(move || {
            let progress_fn = Box::new(move |pct: u32, msg: &str| {
                let mut env = vm.attach_current_thread().expect("attach thread");
                let callback = callback_global.as_obj();
                let msg_j = env.new_string(msg).expect("new string");
                let msg_obj: JObject = msg_j.into();
                let args = [JValue::Int(pct as jint), JValue::Object(&msg_obj)];
                let _ = env.call_method(callback, "onProgress", "(ILjava/lang/String;)V", &args);
            });
            rootfs_fetch::ensure_rootfs_with_progress(
                &url,
                &tarball_name,
                &rootfs_path,
                Some(progress_fn),
            )
        })
        .join();

        match result {
            Ok(Ok(())) => 1,
            Ok(Err(e)) => {
                log::error!("installToContainer failed: {:?}", e);
                0
            }
            Err(e) => {
                log::error!("installToContainer thread panic: {:?}", e);
                0
            }
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_installToContainer(
        env: JNIEnv,
        _: JObject,
        container_id: jint,
        url: JString,
        tarball_name: JString,
        callback: JObject,
    ) -> jboolean {
        install_to_container(env, container_id, url, tarball_name, callback)
    }

    // ---------- Session spawning ----------

    /// Spawn a session in a specific container.
    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_spawnSessionInContainer(
        _env: JNIEnv,
        _: JObject,
        session_id: jint,
        rows: jint,
        cols: jint,
        container_id: jint,
    ) -> jboolean {
        let r = rows.max(1).min(i32::from(u16::MAX)) as u16;
        let c = cols.max(1).min(i32::from(u16::MAX)) as u16;
        let path = match container_rootfs_dir(container_id as u32) {
            Ok(p) => p,
            Err(e) => {
                log::error!("spawnSessionInContainer: invalid container {}: {:?}", container_id, e);
                return 0;
            }
        };
        match spawn_shell_in_rootfs(session_id, r, c, &path) {
            Ok(()) => 1,
            Err(e) => {
                log::error!("spawnSessionInContainer failed: {:?}", e);
                0
            }
        }
    }

    /// Spawn a session in the first installed container (default). Returns false if none installed.
    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_spawnDefaultSession(
        _env: JNIEnv,
        _: JObject,
        session_id: jint,
        rows: jint,
        cols: jint,
    ) -> jboolean {
        let r = rows.max(1).min(i32::from(u16::MAX)) as u16;
        let c = cols.max(1).min(i32::from(u16::MAX)) as u16;
        let container_id = match first_installed_container() {
            Some(id) => id,
            None => {
                log::error!("spawnDefaultSession: no installed container found");
                return 0;
            }
        };
        let path = match container_rootfs_dir(container_id) {
            Ok(p) => p,
            Err(e) => {
                log::error!("spawnDefaultSession: path error: {:?}", e);
                return 0;
            }
        };
        match spawn_shell_in_rootfs(session_id, r, c, &path) {
            Ok(()) => 1,
            Err(e) => {
                log::error!("spawnDefaultSession failed: {:?}", e);
                0
            }
        }
    }

    // ---------- PTY control (unchanged) ----------

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_closeSession(
        _env: JNIEnv,
        _: JObject,
        session_id: jint,
    ) {
        if let Err(e) = close_session(session_id) {
            log::warn!("closeSession: {:?}", e);
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_isSessionAlive(
        _env: JNIEnv,
        _: JObject,
        session_id: jint,
    ) -> jboolean {
        is_session_alive(session_id) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_setPtyWindowSize(
        _env: JNIEnv,
        _: JObject,
        session_id: jint,
        rows: jint,
        cols: jint,
    ) {
        let r = rows.max(1).min(i32::from(u16::MAX)) as u16;
        let c = cols.max(1).min(i32::from(u16::MAX)) as u16;
        if let Err(e) = set_pty_window_size(session_id, r, c) {
            log::warn!("setPtyWindowSize: {:?}", e);
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_app_xodos2_NativeBridge_writeInput(
        env: JNIEnv,
        _: JObject,
        session_id: jint,
        bytes: jni::objects::JByteArray,
    ) {
        let arr = match env.convert_byte_array(&bytes) {
            Ok(a) => a,
            Err(_) => return,
        };
        if let Err(e) = write_input(session_id, &arr) {
            log::warn!("writeInput: {:?}", e);
        }
    }
}

#[cfg(target_os = "android")]
pub use combined::*;