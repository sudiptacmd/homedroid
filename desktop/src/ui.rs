use crate::{
    app::{self, lock, App},
    camera::Options,
    config::Config,
    http::{self, invalid},
};
use serde_json::{json, Value};
use std::{
    io::{self, BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
    time::Duration,
};
const ORIGIN: &str = "http://127.0.0.1:8891";
fn reply(s: &mut TcpStream, status: u16, kind: &str, body: &[u8]) -> io::Result<()> {
    write!(s,"HTTP/1.1 {status} {}\r\nContent-Type: {kind}\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; script-src 'nonce-{{NONCE}}'; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; form-action 'self'\r\n\r\n",if status==200{"OK"}else{"Error"},body.len())?;
    s.write_all(body)
}
fn json_reply(s: &mut TcpStream, status: u16, v: &Value) -> io::Result<()> {
    reply(s, status, "application/json", v.to_string().as_bytes())
}
pub fn bind() -> io::Result<TcpListener> {
    TcpListener::bind("127.0.0.1:8891").map_err(|e| {
        invalid(format!(
            "Cannot bind 127.0.0.1:8891 (is Homedroid Desktop already running?): {e}"
        ))
    })
}
pub fn serve(listener: TcpListener, app: Arc<App>) -> io::Result<()> {
    let count = Arc::new(AtomicUsize::new(0));
    for s in listener.incoming() {
        let mut s = s?;
        s.set_read_timeout(Some(Duration::from_secs(5)))?;
        s.set_write_timeout(Some(Duration::from_secs(3)))?;
        if count.fetch_add(1, Ordering::SeqCst) >= 24 {
            count.fetch_sub(1, Ordering::SeqCst);
            let _ = reply(&mut s, 503, "text/plain", b"Too many clients");
            continue;
        }
        let a = app.clone();
        let c = count.clone();
        std::thread::spawn(move || {
            let _ = handle(&mut s, &a);
            c.fetch_sub(1, Ordering::SeqCst);
        });
    }
    Ok(())
}
fn handle(s: &mut TcpStream, app: &Arc<App>) -> io::Result<()> {
    let mut r = BufReader::new(s.try_clone()?);
    let request = http::line(&mut r, 4096)?.ok_or_else(|| invalid("empty request"))?;
    let p: Vec<_> = request.split_whitespace().collect();
    if p.len() != 3 || p[2] != "HTTP/1.1" {
        return reply(s, 400, "text/plain", b"Bad request");
    }
    let (method, path) = (p[0], p[1]);
    let h = http::headers(&mut r)?;
    if h.get("host").map(String::as_str) != Some("127.0.0.1:8891")
        || h.get("origin").is_some_and(|o| o != ORIGIN)
        || h.get("sec-fetch-site").is_some_and(|s| s == "cross-site")
    {
        return reply(s, 403, "text/plain", b"Local origin only");
    }
    if method == "GET" {
        return match path{
        "/"=>{let page=include_str!("ui.html").replace("__TOKEN__",&app.token);let nonce=&app.token;write!(s,"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; script-src 'nonce-{nonce}'; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; form-action 'self'\r\n\r\n{page}",page.len())},
        "/api/status"=>json_reply(s,200,&app.status()),"/stream"=>stream(s,app),"/favicon.ico"=>reply(s,200,"image/svg+xml",b"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'><rect width='64' height='64' rx='18' fill='#b1f177'/><path d='M16 44V20h8v9h16v-9h8v24h-8v-9H24v9z' fill='#132015'/></svg>"),_=>reply(s,404,"text/plain",b"Not found")};
    }
    if method != "POST" {
        return reply(s, 405, "text/plain", b"Method not allowed");
    }
    if h.get("origin").map(String::as_str) != Some(ORIGIN)
        || h.get("x-homedroid-token") != Some(&app.token)
    {
        return reply(
            s,
            403,
            "text/plain",
            b"Invalid local control token or Origin",
        );
    }
    if h.contains_key("transfer-encoding")
        || h.get("content-type").map(String::as_str) != Some("application/json")
    {
        return reply(s, 400, "text/plain", b"Expected JSON with Content-Length");
    }
    let n = h
        .get("content-length")
        .and_then(|s| s.parse::<usize>().ok())
        .filter(|&n| n <= 65536)
        .ok_or_else(|| invalid("invalid request length"))?;
    let mut b = vec![0; n];
    r.read_exact(&mut b)?;
    let value: Value = match serde_json::from_slice(&b) {
        Ok(v) => v,
        Err(_) => return reply(s, 400, "text/plain", b"Invalid JSON"),
    };
    let _guard = lock(&app.mutation);
    let result = (|| -> io::Result<()> {
        match path {
            "/api/pair" => {
                let address = value["address"]
                    .as_str()
                    .ok_or_else(|| invalid("address required"))?;
                let code = value["code"]
                    .as_str()
                    .ok_or_else(|| invalid("code required"))?;
                app.update_config(app::pair(address, code)?)
            }
            "/api/unpair" => {
                app.set_camera(false, lock(&app.options).clone())?;
                app.update_config(Config::default())
            }
            "/api/settings" => {
                let mut c = lock(&app.config).clone();
                c.allow_commands = value["allowCommands"]
                    .as_bool()
                    .ok_or_else(|| invalid("allowCommands must be boolean"))?;
                app.update_config(c)
            }
            "/api/control" => app.control_camera(&value),
            "/api/preset" => {
                let name = value["name"].as_str().ok_or_else(|| invalid("preset name required"))?;
                let mut c = lock(&app.config).clone();
                if value["delete"] == true { c.camera_presets.remove(name); }
                else { c.camera_presets.insert(name.to_owned(), value["settings"].clone()); }
                app.update_config(c)
            }
            "/api/webcam" => {
                let options: Options = serde_json::from_value(value["options"].clone())
                    .map_err(|e| invalid(e.to_string()))?;
                app.set_camera(
                    value["enabled"]
                        .as_bool()
                        .ok_or_else(|| invalid("enabled must be boolean"))?,
                    options,
                )
            }
            _ => Err(invalid("unknown endpoint")),
        }
    })();
    match result {
        Ok(()) => json_reply(s, 200, &json!({"ok":true})),
        Err(e) => json_reply(s, 400, &json!({"error":e.to_string()})),
    }
}
fn stream(s: &mut TcpStream, app: &App) -> io::Result<()> {
    if !app.camera_on.load(Ordering::SeqCst) {
        return reply(
            s,
            503,
            "text/plain",
            b"Enable the webcam in Homedroid Desktop first",
        );
    }
    write!(s,"HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=hdframe\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCross-Origin-Resource-Policy: same-origin\r\n\r\n")?;
    let mut seen = 0;
    loop {
        let frame = lock(&app.latest);
        let frame = app
            .changed
            .wait_timeout_while(frame, Duration::from_secs(2), |f| f.sequence == seen)
            .unwrap_or_else(|e| e.into_inner())
            .0;
        if !app.camera_on.load(Ordering::SeqCst) {
            return Ok(());
        }
        if frame.sequence == seen {
            continue;
        }
        seen = frame.sequence;
        let jpeg = frame.jpeg.clone();
        let capture = frame.capture_ms;
        drop(frame);
        if jpeg.is_empty() {
            continue;
        }
        write!(
            s,
            "--hdframe\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\nX-Frame: {seen}\r\n",
            jpeg.len()
        )?;
        if let Some(t) = capture {
            write!(s, "X-Time: {t}\r\n")?;
        }
        s.write_all(b"\r\n")?;
        s.write_all(&jpeg)?;
        s.write_all(b"\r\n")?;
    }
}
