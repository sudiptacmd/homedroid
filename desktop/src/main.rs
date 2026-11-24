mod actions;
mod app;
mod camera;
mod config;
mod controls;
mod http;
mod platform;
mod ui;

use std::{io, sync::atomic::Ordering, time::Duration};

const HELP: &str = "Homedroid Desktop: webcam + MacroPads companion for your Homedroid phone

Usage:
  homedroid-desktop [ui]                    Open the local control page (also runs MacroPads)
  homedroid-desktop pair <address[:port]> <code>
                                            Pair with the phone (6-digit code from the phone)
  homedroid-desktop run [--webcam]          Run MacroPads (and optionally the webcam)
  homedroid-desktop webcam [options]        Run the webcam, MacroPads and the local page
  homedroid-desktop status                  Show pairing, connection and camera statistics
  homedroid-desktop unpair                  Forget the pairing on this computer
  homedroid-desktop install-camera          Windows 11: register the virtual camera (administrator, once)
  homedroid-desktop --help

Webcam options:
  --camera back|front|<id>   phone camera (default back)
  --size 1280x720            requested size
  --fps 30                   requested frame rate
  --quality 80               JPEG quality 1-100
  --device /dev/videoN       Linux: v4l2loopback device (default: first one found)
  --mirror / --flip          horizontal / vertical reflection
  --zoom 1.5 --torch --ev 1   zoom, torch and exposure compensation (EV)
  --wb daylight --rotate 90  white balance and clockwise rotation
  --focus continuous|auto|manual --focus-distance 0.5
  --iso 400 --shutter-ns 10000000   manual sensor exposure (specify both)
  --ae-lock --awb-lock --stabilization --anti-banding auto|50hz|60hz

Other options (ui, run, webcam):
  --no-browser               do not open the control page in a browser
  --dry-run                  validate and log MacroPads actions without performing them

Local page:       http://127.0.0.1:8891
MJPEG for OBS:    http://127.0.0.1:8891/stream
";

fn usage(s: &str) -> io::Error {
    http::invalid(s)
}

fn status() -> io::Result<()> {
    let c = config::Config::load()?;
    match http::json("127.0.0.1:8891", "", "GET", "/api/status", None) {
        Ok(s) => {
            println!("Homedroid Desktop is running");
            println!(
                "  phone:       {} ({})",
                s["phone"].as_str().unwrap_or("-"),
                s["address"].as_str().unwrap_or("-")
            );
            println!("  connection:  {}", s["connection"].as_str().unwrap_or("-"));
            println!(
                "  commands:    {}",
                if c.allow_commands {
                    "allowed"
                } else {
                    "refused"
                }
            );
            if s["webcam"] == true {
                println!(
                    "  webcam:      {:.1} fps, {} ms latency, {}x{}, decode {:.1} ms",
                    s["fps"].as_f64().unwrap_or(0.0),
                    s["latencyMs"]
                        .as_u64()
                        .map_or("-".to_string(), |v| v.to_string()),
                    s["width"],
                    s["height"],
                    s["decodeMs"].as_f64().unwrap_or(0.0)
                );
                if let Some(v) = s["virtualCamera"].as_str().filter(|v| !v.is_empty()) {
                    println!("  virtual cam: {v}");
                }
                if let Some(e) = s["cameraError"].as_str().filter(|v| !v.is_empty()) {
                    println!("  camera:      {e}");
                }
            } else {
                println!("  webcam:      off");
            }
        }
        Err(_) => {
            println!("Homedroid Desktop is not running.");
            if c.token.is_empty() {
                println!("  Not paired. Run: homedroid-desktop pair <phone-address> <code>");
            } else {
                println!("  Paired with {} ({})", c.phone, c.address);
            }
        }
    }
    Ok(())
}

fn serve(command: &str, args: &[String]) -> io::Result<()> {
    let mut options = camera::Options::default();
    let mut camera_on = command == "webcam";
    let mut browser = true;
    let mut dry = std::env::var_os("HOMEDROID_DRY_RUN").is_some();
    let mut i = 1;
    while i < args.len() {
        let arg = args[i].as_str();
        match arg {
            "--webcam" if command == "run" => camera_on = true,
            "--mirror" => options.mirror = true,
            "--torch" | "--flip" | "--ae-lock" | "--awb-lock" | "--stabilization" => {
                let key = match arg { "--ae-lock" => "aeLock", "--awb-lock" => "awbLock", _ => &arg[2..] };
                options.controls[key] = serde_json::json!(true);
            }
            "--zoom" | "--ev" | "--wb" | "--rotate" | "--focus" | "--focus-distance" | "--iso" | "--shutter-ns" | "--anti-banding" => {
                i += 1;
                let v = args.get(i).ok_or_else(|| usage(&format!("missing value for {arg}")))?;
                let (key, value) = controls::flag(arg, v)?;
                options.controls[&key] = value;
                if key == "iso" || key == "shutterNs" { options.controls["manualSensor"] = serde_json::json!(true); }
            }
            "--no-browser" => browser = false,
            "--dry-run" => dry = true,
            "--camera" | "--size" | "--fps" | "--quality" | "--device" => {
                i += 1;
                let v = args
                    .get(i)
                    .ok_or_else(|| usage(&format!("missing value for {arg}")))?;
                match arg {
                    "--camera" => options.camera = v.clone(),
                    "--size" => {
                        let (w, h) = v
                            .split_once('x')
                            .ok_or_else(|| usage("size must be WIDTHxHEIGHT"))?;
                        options.width = w.parse().map_err(|_| usage("invalid width"))?;
                        options.height = h.parse().map_err(|_| usage("invalid height"))?;
                    }
                    "--fps" => options.fps = v.parse().map_err(|_| usage("invalid fps"))?,
                    "--quality" => {
                        options.quality = v.parse().map_err(|_| usage("invalid quality"))?
                    }
                    _ => options.device = Some(v.clone()),
                }
            }
            _ => return Err(usage(&format!("unknown option: {arg}\n\n{HELP}"))),
        }
        i += 1;
    }
    options.validate()?;
    actions::set_dry_run(dry);
    let listener = ui::bind()?;
    let app = app::App::new(config::Config::load()?, options, camera_on)?;
    app.start();
    println!("Homedroid Desktop: http://127.0.0.1:8891  (stream: http://127.0.0.1:8891/stream)");
    if dry {
        println!("Dry run: MacroPads actions are logged, not performed.");
    }
    if command == "ui" && browser {
        if let Err(e) = actions::open("http://127.0.0.1:8891") {
            eprintln!("Open http://127.0.0.1:8891 in your browser ({e})");
        }
    }
    let stats = app.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(10));
        if stats.camera_on.load(Ordering::SeqCst) {
            let s = stats.status();
            eprintln!(
                "Camera: {:.1} fps, latency {} ms, decode {:.1} ms",
                s["fps"].as_f64().unwrap_or(0.0),
                s["latencyMs"],
                s["decodeMs"].as_f64().unwrap_or(0.0)
            );
        }
    });
    ui::serve(listener, app)
}

fn run() -> io::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = args.first().map(String::as_str).unwrap_or("ui");
    match command {
        "--help" | "-h" | "help" => {
            print!("{HELP}");
            Ok(())
        }
        "--version" | "-V" => {
            println!("homedroid-desktop {}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        "pair" => {
            if args.len() != 3 {
                return Err(usage(
                    "Usage: homedroid-desktop pair <phone-address[:port]> <6-digit-code>",
                ));
            }
            let c = app::pair(&args[1], &args[2])?;
            c.save()?;
            println!(
                "Paired with {}. Run `homedroid-desktop` to connect.",
                c.phone
            );
            Ok(())
        }
        "unpair" => {
            if args.len() != 1 {
                return Err(usage("unpair takes no arguments"));
            }
            config::Config::default().save()?;
            println!("Pairing removed from this computer. Also remove it on the phone to revoke the token.");
            Ok(())
        }
        "status" => {
            if args.len() != 1 {
                return Err(usage("status takes no arguments"));
            }
            status()
        }
        "install-camera" => {
            #[cfg(windows)]
            {
                platform::install_camera()?;
                println!(
                    "Camera registered. Start the webcam to see \"Homedroid Camera\" in your apps."
                );
                Ok(())
            }
            #[cfg(not(windows))]
            {
                Err(usage("install-camera is for Windows 11 only"))
            }
        }
        "ui" | "run" | "webcam" => serve(command, &args),
        _ if command.starts_with('-') => serve("ui", &{
            let mut v = vec!["ui".to_string()];
            v.extend(args.iter().cloned());
            v
        }),
        _ => Err(usage(&format!("unknown command: {command}\n\n{HELP}"))),
    }
}

fn main() {
    if let Err(e) = run() {
        eprintln!("homedroid-desktop: {e}");
        std::process::exit(1);
    }
}
