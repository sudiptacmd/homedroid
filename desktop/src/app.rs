//! Shared state and the background threads: MacroPads events, MJPEG intake and
//! virtual-camera output.
use crate::{
    actions::{self, Action},
    camera::{self, Options},
    config::Config,
    http::{self, invalid},
};
use serde_json::{json, Value};
use std::{
    collections::VecDeque,
    io,
    net::{Shutdown, TcpStream},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc, Arc, Condvar, Mutex, MutexGuard,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const BACKOFF: [u64; 4] = [1, 2, 5, 10];

pub fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// The newest camera frame. Consumers only ever see the latest one (frames are dropped, never queued).
pub struct Latest {
    pub sequence: u64,
    pub jpeg: Arc<Vec<u8>>,
    pub capture_ms: Option<u64>,
}

#[derive(Default)]
pub struct Stats {
    pub connection: String,
    pub camera_error: String,
    pub virtual_camera: String,
    pub fps: f64,
    pub latency_ms: Option<u64>,
    pub decode_ms: f64,
    pub width: usize,
    pub height: usize,
    pub last_frame: u64,
    pub cameras: Value,
    pub log: VecDeque<Value>,
}

pub struct App {
    pub config: Mutex<Config>,
    /// Bumped whenever the pairing changes; event threads restart.
    pub generation: AtomicU64,
    pub camera_on: AtomicBool,
    /// Bumped whenever camera options or on/off change; camera threads restart.
    pub camera_generation: AtomicU64,
    pub options: Mutex<Options>,
    pub stats: Mutex<Stats>,
    pub latest: Mutex<Latest>,
    pub changed: Condvar,
    /// True when the phone applies mirroring itself.
    phone_mirrors: AtomicBool,
    event_socket: Mutex<Option<TcpStream>>,
    camera_socket: Mutex<Option<TcpStream>>,
    pub token: String,
    pub mutation: Mutex<()>,
}

fn sleep_unless(changed: impl Fn() -> bool, seconds: u64) {
    for _ in 0..seconds * 10 {
        if changed() {
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
}

impl App {
    pub fn new(config: Config, options: Options, camera_on: bool) -> io::Result<Arc<Self>> {
        Ok(Arc::new(Self {
            config: Mutex::new(config),
            generation: AtomicU64::new(0),
            camera_on: AtomicBool::new(camera_on),
            camera_generation: AtomicU64::new(0),
            options: Mutex::new(options),
            stats: Mutex::new(Stats {
                connection: "Not connected".into(),
                cameras: json!([]),
                ..Stats::default()
            }),
            latest: Mutex::new(Latest {
                sequence: 0,
                jpeg: Arc::new(Vec::new()),
                capture_ms: None,
            }),
            changed: Condvar::new(),
            phone_mirrors: AtomicBool::new(false),
            event_socket: Mutex::new(None),
            camera_socket: Mutex::new(None),
            token: crate::platform::random_token()?,
            mutation: Mutex::new(()),
        }))
    }

    pub fn update_config(&self, c: Config) -> io::Result<()> {
        c.save()?;
        self.replace_config(c);
        Ok(())
    }

    fn replace_config(&self, c: Config) {
        let mut old = lock(&self.config);
        let reconnect = old.token != c.token || old.address != c.address;
        *old = c;
        drop(old);
        if reconnect {
            self.generation.fetch_add(1, Ordering::SeqCst);
            self.camera_generation.fetch_add(1, Ordering::SeqCst);
            Self::close(&self.event_socket);
            Self::close(&self.camera_socket);
            lock(&self.stats).connection = "Reconnecting".into();
            self.clear_frame();
        }
    }

    fn close(s: &Mutex<Option<TcpStream>>) {
        if let Some(s) = lock(s).take() {
            let _ = s.shutdown(Shutdown::Both);
        }
    }

    fn clear_frame(&self) {
        let mut f = lock(&self.latest);
        f.jpeg = Arc::new(Vec::new());
        f.sequence += 1;
        drop(f);
        self.changed.notify_all();
        let mut s = lock(&self.stats);
        s.fps = 0.0;
        s.latency_ms = None;
    }

    pub fn set_camera(&self, on: bool, options: Options) -> io::Result<()> {
        options.validate()?;
        *lock(&self.options) = options;
        self.camera_on.store(on, Ordering::SeqCst);
        self.camera_generation.fetch_add(1, Ordering::SeqCst);
        Self::close(&self.camera_socket);
        lock(&self.stats).camera_error.clear();
        self.clear_frame();
        Ok(())
    }

    fn send_controls(&self, c: &Config, controls: &Value) -> io::Result<()> {
        crate::controls::validate(controls)?;
        let reply = http::json(&c.address, &c.token, "POST", "/desktop/v1/webcam/control", Some(controls))?;
        if let Some(settings) = reply.get("settings").filter(|v| v.is_object()) {
            let mut o = lock(&self.options);
            if let Some(v) = settings["camera"].as_str() { o.camera = v.into(); }
            if let Some(v) = settings["width"].as_u64() { o.width = v as u32; }
            if let Some(v) = settings["height"].as_u64() { o.height = v as u32; }
            if let Some(v) = settings["fps"].as_u64() { o.fps = v as u32; }
            if let Some(v) = settings["quality"].as_u64() { o.quality = v as u32; }
            o.mirror = settings["mirror"].as_bool().unwrap_or(false);
            let mut extra = settings.clone();
            if let Some(map) = extra.as_object_mut() { for key in ["camera", "width", "height", "fps", "quality", "mirror"] { map.remove(key); } }
            o.controls = extra;
        }
        Ok(())
    }

    pub fn control_camera(&self, value: &Value) -> io::Result<()> {
        if !self.camera_on.load(Ordering::SeqCst) { return Err(invalid("Start webcam before changing live controls")); }
        let c = lock(&self.config).clone();
        self.send_controls(&c, value)
    }

    pub fn log(&self, id: &str, kind: &str, result: &io::Result<()>) {
        let mut s = lock(&self.stats);
        if s.log.len() >= 40 {
            s.log.pop_front();
        }
        s.log.push_back(json!({
            "time": now(), "id": id, "kind": kind, "ok": result.is_ok(),
            "error": result.as_ref().err().map(ToString::to_string),
        }));
    }

    pub fn status(&self) -> Value {
        let c = lock(&self.config);
        let s = lock(&self.stats);
        let fresh = now().saturating_sub(s.last_frame) < 3000;
        json!({
            "paired": !c.token.is_empty(),
            "address": c.address,
            "phone": c.phone,
            "computerId": c.computer_id,
            "allowCommands": c.allow_commands,
            "dryRun": actions::dry_run(),
            "connection": s.connection,
            "webcam": self.camera_on.load(Ordering::SeqCst),
            "options": *lock(&self.options),
            "cameraPresets": c.camera_presets,
            "cameraError": s.camera_error,
            "virtualCamera": s.virtual_camera,
            "cameras": s.cameras,
            "fps": if fresh { s.fps } else { 0.0 },
            "latencyMs": if fresh { s.latency_ms } else { None },
            "decodeMs": s.decode_ms,
            "width": s.width,
            "height": s.height,
            "log": s.log,
            "stream": "http://127.0.0.1:8891/stream",
        })
    }

    pub fn start(self: &Arc<Self>) {
        let a = self.clone();
        thread::spawn(move || a.events());
        let a = self.clone();
        thread::spawn(move || a.camera());
        let a = self.clone();
        thread::spawn(move || a.output());
        // Pick up `pair` / `unpair` / settings changes made from another process.
        let a = self.clone();
        thread::spawn(move || loop {
            thread::sleep(Duration::from_secs(2));
            let _guard = lock(&a.mutation);
            if let Ok(c) = Config::load() {
                if c != *lock(&a.config) {
                    a.replace_config(c);
                }
            }
        });
    }

    fn ack(c: &Config, id: &str, result: &io::Result<()>) -> io::Result<()> {
        let body = json!({
            "id": id, "ok": result.is_ok(),
            "error": result.as_ref().err().map(ToString::to_string),
        });
        http::json(&c.address, &c.token, "POST", "/desktop/v1/ack", Some(&body)).map(|_| ())
    }

    fn events(self: Arc<Self>) {
        struct Work {
            id: String,
            value: Value,
            config: Config,
            generation: u64,
            received: Instant,
        }
        let (tx, rx) = mpsc::sync_channel::<Work>(16);
        let worker = self.clone();
        thread::spawn(move || {
            let mut input = None;
            // Re-acks (instead of re-running) an action the phone sent twice.
            let mut done: VecDeque<(String, String, bool, String)> = VecDeque::new();
            for work in rx {
                if worker.generation.load(Ordering::SeqCst) != work.generation {
                    continue;
                }
                let key = (work.config.computer_id.clone(), work.id.clone());
                let result = if let Some(d) = done.iter().find(|d| (&d.0, &d.1) == (&key.0, &key.1))
                {
                    if d.2 {
                        Ok(())
                    } else {
                        Err(invalid(d.3.clone()))
                    }
                } else {
                    let allow = lock(&worker.config).allow_commands;
                    let result = if work.received.elapsed() > Duration::from_secs(8) {
                        Err(invalid("action expired in worker queue"))
                    } else {
                        serde_json::from_value::<Action>(work.value.clone())
                            .map_err(|e| invalid(format!("unsupported action: {e}")))
                            .and_then(|a| actions::execute(&a, &mut input, allow))
                    };
                    done.push_back((
                        key.0,
                        key.1,
                        result.is_ok(),
                        result
                            .as_ref()
                            .err()
                            .map(ToString::to_string)
                            .unwrap_or_default(),
                    ));
                    if done.len() > 256 {
                        done.pop_front();
                    }
                    worker.log(
                        &work.id,
                        work.value["kind"].as_str().unwrap_or("invalid"),
                        &result,
                    );
                    result
                };
                if let Err(e) = Self::ack(&work.config, &work.id, &result) {
                    worker.log(&work.id, "ack", &Err(e));
                }
            }
        });

        let mut attempt = 0;
        loop {
            let c = lock(&self.config).clone();
            let generation = self.generation.load(Ordering::SeqCst);
            if c.token.is_empty() {
                lock(&self.stats).connection = "Not paired".into();
                sleep_unless(|| self.generation.load(Ordering::SeqCst) != generation, 1);
                continue;
            }
            let result = (|| -> io::Result<()> {
                let hello = http::json(&c.address, &c.token, "GET", "/desktop/v1/hello", None)?;
                lock(&self.stats).cameras = hello["cameras"].clone();
                let mut r = http::request(&c.address, &c.token, "GET", "/desktop/v1/events", None)?;
                if r.status != 200 {
                    return Err(http::failure_of(r));
                }
                *lock(&self.event_socket) = Some(r.socket.try_clone()?);
                if self.generation.load(Ordering::SeqCst) != generation {
                    return Ok(());
                }
                lock(&self.stats).connection = "Connecting".into();
                // The phone sends a ping every 15 s; the socket read timeout (45 s) ends a dead stream.
                while let Some(value) = http::ndjson(&mut r.body)? {
                    if self.generation.load(Ordering::SeqCst) != generation {
                        break;
                    }
                    attempt = 0;
                    match value["type"].as_str() {
                        Some("hello" | "ping") => lock(&self.stats).connection = "Connected".into(),
                        Some("action") => {
                            let id = value["id"]
                                .as_str()
                                .filter(|s| !s.is_empty() && s.len() <= 256)
                                .ok_or_else(|| invalid("action has no valid id"))?
                                .to_owned();
                            if let Some(t) = value["action"]["computer"].as_str() {
                                if t != c.computer_id {
                                    let _ =
                                        Self::ack(&c, &id, &Err(invalid("Wrong target computer")));
                                    continue;
                                }
                            }
                            let work = Work {
                                id: id.clone(),
                                value: value["action"].clone(),
                                config: c.clone(),
                                generation,
                                received: Instant::now(),
                            };
                            if tx.try_send(work).is_err() {
                                let _ = Self::ack(&c, &id, &Err(invalid("Action queue full")));
                            }
                        }
                        _ => {}
                    }
                }
                Err(invalid("Phone event stream closed"))
            })();
            Self::close(&self.event_socket);
            if let Err(e) = result {
                lock(&self.stats).connection = e.to_string();
            }
            let seconds = BACKOFF[attempt.min(3)];
            attempt += 1;
            sleep_unless(
                || self.generation.load(Ordering::SeqCst) != generation,
                seconds,
            );
        }
    }

    fn camera(self: Arc<Self>) {
        let mut attempt = 0;
        loop {
            let generation = self.camera_generation.load(Ordering::SeqCst);
            let c = lock(&self.config).clone();
            if !self.camera_on.load(Ordering::SeqCst) || c.token.is_empty() {
                thread::sleep(Duration::from_millis(200));
                continue;
            }
            let opts = lock(&self.options).clone();
            let result = (|| -> io::Result<()> {
                let mut r = http::request(&c.address, &c.token, "GET", &opts.path(), None)?;
                if r.status != 200 {
                    return Err(http::failure_of(r));
                }
                let boundary = http::boundary(
                    r.headers
                        .get("content-type")
                        .map(String::as_str)
                        .unwrap_or(""),
                )?;
                *lock(&self.camera_socket) = Some(r.socket.try_clone()?);
                lock(&self.stats).camera_error.clear();
                let mut controls = opts.controls.clone();
                controls["mirror"] = json!(opts.mirror);
                self.send_controls(&c, &controls)?;
                self.phone_mirrors.store(true, Ordering::SeqCst);
                let (mut count, mut start) = (0u64, Instant::now());
                while self.camera_generation.load(Ordering::SeqCst) == generation {
                    let Some(frame) = http::frame(&mut r.body, &boundary)? else {
                        break;
                    };
                    attempt = 0;
                    count += 1;
                    let mut latest = lock(&self.latest);
                    latest.sequence += 1;
                    latest.jpeg = Arc::new(frame.jpeg);
                    latest.capture_ms = frame.capture_ms;
                    drop(latest);
                    self.changed.notify_all();
                    let mut s = lock(&self.stats);
                    s.last_frame = now();
                    s.latency_ms = frame
                        .capture_ms
                        .and_then(|t| now().checked_sub(t))
                        .filter(|&t| t < 60_000);
                    let elapsed = start.elapsed().as_secs_f64();
                    if elapsed >= 1.0 {
                        s.fps = count as f64 / elapsed;
                        count = 0;
                        start = Instant::now();
                    }
                }
                Err(invalid("Camera stream closed"))
            })();
            Self::close(&self.camera_socket);
            if let Err(e) = result {
                if self.camera_generation.load(Ordering::SeqCst) == generation {
                    let message = e.to_string();
                    eprintln!("{message}");
                    lock(&self.stats).camera_error = message;
                }
            }
            let seconds = BACKOFF[attempt.min(3)];
            attempt += 1;
            sleep_unless(
                || self.camera_generation.load(Ordering::SeqCst) != generation,
                seconds,
            );
        }
    }

    /// Decodes the latest frame into the virtual camera. Without a virtual camera
    /// device only the JPEG header is read, so an idle preview costs almost nothing.
    fn output(self: Arc<Self>) {
        let mut seen = 0;
        let mut generation = u64::MAX;
        let mut sink: Option<camera::Sink> = None;
        loop {
            let latest = lock(&self.latest);
            let latest = self
                .changed
                .wait_timeout_while(latest, Duration::from_secs(1), |l| l.sequence == seen)
                .unwrap_or_else(|e| e.into_inner())
                .0;
            if latest.sequence == seen {
                continue;
            }
            seen = latest.sequence;
            let jpeg = latest.jpeg.clone();
            drop(latest);
            let current = self.camera_generation.load(Ordering::SeqCst);
            if current != generation {
                generation = current;
                sink = None;
                if self.camera_on.load(Ordering::SeqCst) {
                    let opened = camera::Sink::new(&lock(&self.options));
                    let mut s = lock(&self.stats);
                    match opened {
                        Ok(k) => {
                            s.virtual_camera = k.describe();
                            sink = Some(k);
                        }
                        Err(e) => {
                            s.virtual_camera = e.to_string();
                        }
                    }
                } else {
                    lock(&self.stats).virtual_camera.clear();
                }
            }
            if jpeg.is_empty() {
                continue;
            }
            let Some(output) = sink.as_mut() else {
                if let Some((w, h)) = camera::jpeg_size(&jpeg) {
                    let mut s = lock(&self.stats);
                    s.width = w;
                    s.height = h;
                }
                continue;
            };
            let mirror = lock(&self.options).mirror && !self.phone_mirrors.load(Ordering::SeqCst);
            let start = Instant::now();
            match camera::decode(&jpeg, mirror) {
                Ok((rgb, w, h)) => {
                    let ms = start.elapsed().as_secs_f64() * 1000.0;
                    let written = output.write(&rgb, w, h);
                    let mut s = lock(&self.stats);
                    s.width = w;
                    s.height = h;
                    s.decode_ms = ms;
                    if let Err(e) = written {
                        s.virtual_camera = format!("Virtual camera error: {e}");
                        drop(s);
                        sink = None;
                    }
                }
                Err(e) => lock(&self.stats).camera_error = e.to_string(),
            }
        }
    }
}

pub fn pair(address: &str, code: &str) -> io::Result<Config> {
    let addr = http::Address::parse(address)?;
    if code.len() != 6 || !code.bytes().all(|c| c.is_ascii_digit()) {
        return Err(invalid("Pairing code must be six digits"));
    }
    let name = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .or_else(|| {
            std::fs::read_to_string("/etc/hostname")
                .ok()
                .map(|s| s.trim().to_owned())
        })
        .filter(|s| !s.is_empty() && s.len() <= 80)
        .unwrap_or_else(|| "Homedroid Desktop".into());
    let reply = http::json(
        address,
        "",
        "POST",
        "/desktop/v1/pair",
        Some(&json!({
            "code": code, "name": name, "os": std::env::consts::OS,
            "version": env!("CARGO_PKG_VERSION"),
        })),
    )?;
    let field = |key: &str| -> io::Result<String> {
        reply[key]
            .as_str()
            .filter(|s| !s.is_empty() && s.len() < 4096)
            .map(str::to_owned)
            .ok_or_else(|| invalid(format!("pair response missing {key}")))
    };
    let c = Config {
        address: addr.authority,
        token: field("token")?,
        computer_id: field("computerId")?,
        phone: field("phone")?,
        phone_id: field("phoneId")?,
        allow_commands: false,
        camera_presets: Default::default(),
    };
    c.validate()?;
    Ok(c)
}
