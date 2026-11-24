//! Bounded HTTP/1.1, chunked, multipart and NDJSON readers. No async runtime.
use std::collections::BTreeMap;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

pub fn invalid(s: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, s.into())
}
pub fn line<R: BufRead>(r: &mut R, max: usize) -> io::Result<Option<String>> {
    let mut b = Vec::new();
    loop {
        let available = r.fill_buf()?;
        if available.is_empty() {
            if b.is_empty() {
                return Ok(None);
            }
            return Err(invalid("truncated line"));
        }
        let n = available
            .iter()
            .position(|&x| x == b'\n')
            .map_or(available.len(), |n| n + 1);
        if b.len() + n > max {
            return Err(invalid("line too long"));
        }
        let done = available[n - 1] == b'\n';
        b.extend_from_slice(&available[..n]);
        r.consume(n);
        if done {
            break;
        }
    }
    b.pop();
    if b.last() == Some(&b'\r') {
        b.pop();
    }
    String::from_utf8(b)
        .map(Some)
        .map_err(|_| invalid("invalid UTF-8 line"))
}
pub fn headers<R: BufRead>(r: &mut R) -> io::Result<BTreeMap<String, String>> {
    let mut h = BTreeMap::new();
    let mut total = 0;
    loop {
        let s = line(r, 8192)?.ok_or_else(|| invalid("truncated headers"))?;
        total += s.len();
        if total > 32768 {
            return Err(invalid("headers too large"));
        }
        if s.is_empty() {
            return Ok(h);
        }
        let (k, v) = s.split_once(':').ok_or_else(|| invalid("invalid header"))?;
        let k = k.to_ascii_lowercase();
        let strict = matches!(
            k.as_str(),
            "content-length" | "transfer-encoding" | "host" | "origin" | "x-homedroid-token"
        );
        if h.insert(k, v.trim().to_owned()).is_some() && strict {
            return Err(invalid("duplicate header"));
        }
    }
}
#[derive(Clone, Debug)]
pub struct Address {
    pub authority: String,
    host: String,
    port: u16,
}
impl Address {
    pub fn parse(s: &str) -> io::Result<Self> {
        let s = s.strip_prefix("http://").unwrap_or(s);
        if s.is_empty()
            || s.chars()
                .any(|c| c.is_whitespace() || c.is_control() || "/?#@\\".contains(c))
        {
            return Err(invalid("use a phone hostname or IP, optionally :port"));
        }
        let (host, port) = if let Some(rest) = s.strip_prefix('[') {
            let (ip, tail) = rest
                .split_once(']')
                .ok_or_else(|| invalid("invalid IPv6 address"))?;
            ip.parse::<std::net::Ipv6Addr>()
                .map_err(|_| invalid("invalid IPv6 address"))?;
            let port = if tail.is_empty() {
                8800
            } else {
                tail.strip_prefix(':')
                    .ok_or_else(|| invalid("invalid port"))?
                    .parse()
                    .map_err(|_| invalid("invalid port"))?
            };
            (ip.to_owned(), port)
        } else {
            let (host, port) = match s.split_once(':') {
                Some((h, p)) => (
                    h,
                    p.parse()
                        .map_err(|_| invalid("invalid port (bracket IPv6 addresses)"))?,
                ),
                None => (s, 8800),
            };
            if host.is_empty()
                || !host
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || ".-_".contains(c))
            {
                return Err(invalid("invalid hostname"));
            }
            (host.to_owned(), port)
        };
        if port == 0 {
            return Err(invalid("port must be nonzero"));
        }
        let authority = if host.contains(':') {
            format!("[{host}]:{port}")
        } else {
            format!("{host}:{port}")
        };
        Ok(Self {
            authority,
            host,
            port,
        })
    }
    pub fn connect(&self) -> io::Result<TcpStream> {
        let mut last = invalid("address has no IPs");
        for a in (self.host.as_str(), self.port).to_socket_addrs()? {
            match TcpStream::connect_timeout(&a, Duration::from_secs(5)) {
                Ok(s) => {
                    s.set_nodelay(true)?;
                    s.set_read_timeout(Some(Duration::from_secs(45)))?;
                    s.set_write_timeout(Some(Duration::from_secs(5)))?;
                    return Ok(s);
                }
                Err(e) => last = e,
            }
        }
        Err(last)
    }
}
#[derive(Debug)]
enum Mode {
    Close,
    Length(u64),
    Chunked { left: usize, crlf: bool, done: bool },
}
pub struct Body<R: BufRead> {
    inner: R,
    mode: Mode,
}
impl<R: BufRead> Body<R> {
    pub fn new(inner: R, h: &BTreeMap<String, String>) -> io::Result<Self> {
        let mode = if let Some(te) = h.get("transfer-encoding") {
            if !te.eq_ignore_ascii_case("chunked") || h.contains_key("content-length") {
                return Err(invalid("unsupported/ambiguous transfer encoding"));
            }
            Mode::Chunked {
                left: 0,
                crlf: false,
                done: false,
            }
        } else if let Some(n) = h.get("content-length") {
            Mode::Length(n.parse().map_err(|_| invalid("invalid content length"))?)
        } else {
            Mode::Close
        };
        Ok(Self { inner, mode })
    }
}
impl<R: BufRead> Read for Body<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        if out.is_empty() {
            return Ok(0);
        }
        match &mut self.mode {
            Mode::Close => self.inner.read(out),
            Mode::Length(left) => {
                if *left == 0 {
                    return Ok(0);
                }
                let n = out.len().min((*left).min(usize::MAX as u64) as usize);
                let n = self.inner.read(&mut out[..n])?;
                if n == 0 {
                    return Err(invalid("truncated body"));
                }
                *left -= n as u64;
                Ok(n)
            }
            Mode::Chunked { left, crlf, done } => {
                if *done {
                    return Ok(0);
                }
                if *left == 0 {
                    if *crlf {
                        let mut b = [0; 2];
                        self.inner.read_exact(&mut b)?;
                        if b != *b"\r\n" {
                            return Err(invalid("invalid chunk terminator"));
                        }
                    }
                    let s = line(&mut self.inner, 1024)?
                        .ok_or_else(|| invalid("truncated chunk header"))?;
                    *left = usize::from_str_radix(s.split(';').next().unwrap_or("").trim(), 16)
                        .map_err(|_| invalid("invalid chunk size"))?;
                    if *left > 64 * 1024 * 1024 {
                        return Err(invalid("chunk too large"));
                    }
                    if *left == 0 {
                        headers(&mut self.inner)?;
                        *done = true;
                        return Ok(0);
                    }
                    *crlf = true;
                }
                let n = out.len().min(*left);
                let n = self.inner.read(&mut out[..n])?;
                if n == 0 {
                    return Err(invalid("truncated chunk"));
                }
                *left -= n;
                Ok(n)
            }
        }
    }
}
pub struct Response {
    pub status: u16,
    pub headers: BTreeMap<String, String>,
    pub body: BufReader<Body<BufReader<TcpStream>>>,
    pub socket: TcpStream,
}
pub fn request(
    address: &str,
    token: &str,
    method: &str,
    path: &str,
    json: Option<&serde_json::Value>,
) -> io::Result<Response> {
    let address = Address::parse(address)?;
    if !token.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err(invalid("invalid bearer token"));
    }
    let mut s = address.connect()?;
    let body = json.map(ToString::to_string).unwrap_or_default();
    write!(
        s,
        "{method} {path} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n",
        address.authority
    )?;
    if !token.is_empty() {
        write!(s, "Authorization: Bearer {token}\r\n")?;
    }
    if json.is_some() {
        write!(
            s,
            "Content-Type: application/json\r\nContent-Length: {}\r\n",
            body.len()
        )?;
    }
    write!(s, "\r\n{body}")?;
    let socket = s.try_clone()?;
    let mut r = BufReader::new(s);
    let status = line(&mut r, 8192)?.ok_or_else(|| invalid("empty HTTP response"))?;
    let mut parts = status.split_whitespace();
    if !matches!(parts.next(), Some("HTTP/1.1" | "HTTP/1.0")) {
        return Err(invalid("invalid HTTP version"));
    }
    let status = parts
        .next()
        .ok_or_else(|| invalid("missing status"))?
        .parse()
        .map_err(|_| invalid("invalid HTTP status"))?;
    let headers = headers(&mut r)?;
    Ok(Response {
        status,
        body: BufReader::new(Body::new(r, &headers)?),
        headers,
        socket,
    })
}
pub fn json(
    address: &str,
    token: &str,
    method: &str,
    path: &str,
    value: Option<&serde_json::Value>,
) -> io::Result<serde_json::Value> {
    let r = request(address, token, method, path, value)?;
    let mut b = Vec::new();
    r.body.take(1024 * 1024 + 1).read_to_end(&mut b)?;
    if b.len() > 1024 * 1024 {
        return Err(invalid("response too large"));
    }
    if r.status != 200 {
        return Err(failure(r.status, &b));
    }
    if b.is_empty() {
        return Ok(serde_json::Value::Null);
    }
    serde_json::from_slice(&b).map_err(|e| invalid(e.to_string()))
}
/// Error for a non-200 reply, using the phone's own `{"error": "..."}` text when present.
pub fn failure(status: u16, body: &[u8]) -> io::Error {
    match serde_json::from_slice::<serde_json::Value>(body)
        .ok()
        .and_then(|v| v["error"].as_str().map(str::to_owned))
    {
        Some(m) if !m.is_empty() && m.len() < 300 => invalid(format!("Phone: {m}")),
        _ => status_error(status),
    }
}
/// Like [`failure`] but reads the (small) body from a streaming response first.
pub fn failure_of(mut r: Response) -> io::Error {
    let mut b = Vec::new();
    let _ = (&mut r.body).take(4096).read_to_end(&mut b);
    failure(r.status, &b)
}
pub fn status_error(status: u16) -> io::Error {
    invalid(match status {
        401 => "Pair again: phone revoked this computer".into(),
        403 => "Pairing code is wrong or expired".into(),
        409 => {
            "Phone says this is already in use, or the webcam is not enabled on the phone".into()
        }
        _ => format!("Phone returned HTTP {status}"),
    })
}
pub fn boundary(content_type: &str) -> io::Result<String> {
    if !content_type
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .eq_ignore_ascii_case("multipart/x-mixed-replace")
    {
        return Err(invalid("phone did not return MJPEG"));
    }
    for s in content_type.split(';').skip(1) {
        if let Some((k, v)) = s.trim().split_once('=') {
            if k.eq_ignore_ascii_case("boundary") {
                let v = v.trim().trim_matches('"');
                if !v.is_empty() && v.len() <= 70 && v.bytes().all(|c| (33..=126).contains(&c)) {
                    return Ok(format!("--{v}"));
                }
            }
        }
    }
    Err(invalid("missing multipart boundary"))
}
pub struct Frame {
    pub jpeg: Vec<u8>,
    pub capture_ms: Option<u64>,
}
pub fn frame<R: BufRead>(r: &mut R, boundary: &str) -> io::Result<Option<Frame>> {
    let mut preamble = 0;
    loop {
        let Some(s) = line(r, 8192)? else {
            return Ok(None);
        };
        if s == boundary {
            break;
        }
        if s == format!("{boundary}--") {
            return Ok(None);
        }
        preamble += s.len() + 2;
        if preamble > 16384 {
            return Err(invalid("multipart boundary missing"));
        }
    }
    let h = headers(r)?;
    let n: usize = h
        .get("content-length")
        .ok_or_else(|| invalid("frame needs Content-Length"))?
        .parse()
        .map_err(|_| invalid("invalid frame length"))?;
    if !(4..=16 * 1024 * 1024).contains(&n) {
        return Err(invalid("invalid frame size"));
    }
    let mut jpeg = vec![0; n];
    r.read_exact(&mut jpeg)?;
    let mut crlf = [0; 2];
    r.read_exact(&mut crlf)?;
    if crlf != *b"\r\n" {
        return Err(invalid("invalid multipart terminator"));
    }
    Ok(Some(Frame {
        jpeg,
        capture_ms: h.get("x-time").and_then(|s| s.parse().ok()),
    }))
}
pub fn ndjson<R: BufRead>(r: &mut R) -> io::Result<Option<serde_json::Value>> {
    loop {
        match line(r, 65536)? {
            None => return Ok(None),
            Some(s) if s.trim().is_empty() => continue,
            Some(s) => {
                return serde_json::from_str(&s)
                    .map(Some)
                    .map_err(|e| invalid(e.to_string()))
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    #[test]
    fn chunks() {
        let h = BTreeMap::from([("transfer-encoding".into(), "chunked".into())]);
        let mut b = Body::new(
            Cursor::new(b"3;foo=bar\r\nabc\r\n2\r\nde\r\n0\r\nFoo: end\r\n\r\n"),
            &h,
        )
        .unwrap();
        let mut out = String::new();
        b.read_to_string(&mut out).unwrap();
        assert_eq!(out, "abcde");
    }
    #[test]
    fn truncated_chunk() {
        let h = BTreeMap::from([("transfer-encoding".into(), "chunked".into())]);
        for s in ["3\r\nab", "q\r\n", "1\r\naXX0\r\n\r\n"] {
            assert!(Body::new(Cursor::new(s), &h)
                .unwrap()
                .read_to_end(&mut Vec::new())
                .is_err());
        }
    }
    #[test]
    fn multipart() {
        let mut r = Cursor::new(
            b"--hdframe\r\nContent-Length: 4\r\nX-Time: 123\r\n\r\n1234\r\n--hdframe--\r\n",
        );
        let f = frame(&mut r, "--hdframe").unwrap().unwrap();
        assert_eq!(f.jpeg, b"1234");
        assert_eq!(f.capture_ms, Some(123));
        assert!(frame(&mut r, "--hdframe").unwrap().is_none());
        assert_eq!(
            boundary("multipart/x-mixed-replace; boundary=\"hdframe\"").unwrap(),
            "--hdframe"
        );
    }
    #[test]
    fn lines() {
        let mut r = Cursor::new(b"\n{\"type\":\"ping\"}\r\n{\"type\":\"hello\"}\n");
        assert_eq!(ndjson(&mut r).unwrap().unwrap()["type"], "ping");
        assert_eq!(ndjson(&mut r).unwrap().unwrap()["type"], "hello");
        assert!(ndjson(&mut r).unwrap().is_none());
        assert!(line(&mut Cursor::new(b"abcdef\n"), 4).is_err());
        assert!(ndjson(&mut Cursor::new(b"{}")).is_err());
    }
    #[test]
    fn failure_text() {
        assert_eq!(
            failure(409, br#"{"error":"One webcam stream is already running"}"#).to_string(),
            "Phone: One webcam stream is already running"
        );
        assert!(failure(401, b"nope").to_string().contains("Pair again"));
    }

    #[test]
    fn addresses() {
        assert_eq!(Address::parse("[::1]").unwrap().authority, "[::1]:8800");
        for s in ["x/a", "a\r\nX:y", "https://foo", "foo:0", "user@host"] {
            assert!(Address::parse(s).is_err());
        }
    }
}
