use crate::{
    actions::{Input, Key},
    http::invalid,
};
use std::{
    fs::{File, OpenOptions},
    io::{self, Write},
    os::fd::{AsRawFd, RawFd},
};
use x11rb::{
    connection::Connection,
    protocol::{
        xproto::{
            ConnectionExt as _, BUTTON_PRESS_EVENT, BUTTON_RELEASE_EVENT, KEY_PRESS_EVENT,
            KEY_RELEASE_EVENT, MOTION_NOTIFY_EVENT,
        },
        xtest::ConnectionExt as _,
    },
    rust_connection::RustConnection,
};
fn err(e: impl std::fmt::Display) -> io::Error {
    invalid(e.to_string())
}
fn x11() -> Option<Box<dyn Input>> {
    let (conn, screen) = x11rb::connect(None).ok()?;
    Some(Box::new(XInput {
        root: conn.setup().roots[screen].root,
        conn,
    }))
}
/// X11 (XTEST) on X sessions; /dev/uinput on Wayland, falling back to XWayland.
pub fn input() -> io::Result<Box<dyn Input>> {
    if std::env::var_os("WAYLAND_DISPLAY").is_none() {
        if let Some(x) = x11() {
            return Ok(x);
        }
    }
    match UInput::new() {
        Ok(u) => Ok(Box::new(u)),
        Err(e) => x11().ok_or(e),
    }
}
struct XInput {
    conn: RustConnection,
    root: u32,
}
fn keysym(k: Key) -> u32 {
    use Key::*;
    match k {
        Char(c) if c as u32 <= 255 => c as u32,
        Char(c) => 0x01000000 | c as u32,
        Ctrl => 0xffe3,
        Shift => 0xffe1,
        Alt => 0xffe9,
        Meta => 0xffeb,
        Enter => 0xff0d,
        Tab => 0xff09,
        Escape => 0xff1b,
        Space => 32,
        Backspace => 0xff08,
        Delete => 0xffff,
        Up => 0xff52,
        Down => 0xff54,
        Left => 0xff51,
        Right => 0xff53,
        Home => 0xff50,
        End => 0xff57,
        PageUp => 0xff55,
        PageDown => 0xff56,
        F(n) => 0xffbd + n as u32,
        PrintScreen => 0xff61,
        Media(n) => [
            0x1008ff14, 0x1008ff17, 0x1008ff16, 0x1008ff15, 0x1008ff13, 0x1008ff11, 0x1008ff12,
        ][n as usize],
    }
}
impl XInput {
    fn event(&self, kind: u8, key: u8, x: i16, y: i16) -> io::Result<()> {
        self.conn
            .xtest_fake_input(kind, key, 0, self.root, x, y, 0)
            .map_err(err)?
            .check()
            .map_err(err)
    }
    fn lookup(&self, k: Key) -> io::Result<(u8, bool)> {
        let setup = self.conn.setup();
        let m = self
            .conn
            .get_keyboard_mapping(setup.min_keycode, setup.max_keycode - setup.min_keycode + 1)
            .map_err(err)?
            .reply()
            .map_err(err)?;
        let sym = keysym(k);
        for (i, syms) in m.keysyms.chunks(m.keysyms_per_keycode as usize).enumerate() {
            for (j, &s) in syms.iter().take(2).enumerate() {
                if s == sym {
                    return Ok((setup.min_keycode + i as u8, j == 1));
                }
            }
        }
        Err(invalid("key is not mapped in the current X11 layout"))
    }
}
impl Input for XInput {
    fn chord(&mut self, keys: &[Key]) -> io::Result<()> {
        let mut codes = Vec::new();
        for &k in keys {
            let (c, shift) = self.lookup(k)?;
            if shift {
                let s = self.lookup(Key::Shift)?.0;
                if !codes.contains(&s) {
                    codes.push(s);
                }
            }
            if !codes.contains(&c) {
                codes.push(c);
            }
        }
        let mut pressed = Vec::new();
        let result = (|| {
            for &c in &codes {
                self.event(KEY_PRESS_EVENT, c, 0, 0)?;
                pressed.push(c);
            }
            Ok(())
        })();
        let mut release = Ok(());
        for &c in pressed.iter().rev() {
            if let Err(e) = self.event(KEY_RELEASE_EVENT, c, 0, 0) {
                release = Err(e);
            }
        }
        result.and(release)
    }
    fn text(&mut self, text: &str) -> io::Result<()> {
        // Save and restore an unused keycode, including every layout column.
        let setup = self.conn.setup();
        let map = self
            .conn
            .get_keyboard_mapping(setup.min_keycode, setup.max_keycode - setup.min_keycode + 1)
            .map_err(err)?
            .reply()
            .map_err(err)?;
        let width = map.keysyms_per_keycode as usize;
        let index = map
            .keysyms
            .chunks(width)
            .rposition(|s| s.iter().all(|&k| k == 0))
            .ok_or_else(|| invalid("X11 has no spare keycode for Unicode text"))?;
        let code = setup.min_keycode + index as u8;
        let original = map.keysyms[index * width..(index + 1) * width].to_vec();
        let result = (|| {
            for c in text.chars() {
                let k = match c {
                    '\n' | '\r' => Key::Enter,
                    '\t' => Key::Tab,
                    c if c.is_control() => return Err(invalid("unsupported control character")),
                    c => Key::Char(c),
                };
                let mut syms = vec![keysym(k); width];
                self.conn
                    .change_keyboard_mapping(1, code, map.keysyms_per_keycode, &syms)
                    .map_err(err)?
                    .check()
                    .map_err(err)?;
                self.event(KEY_PRESS_EVENT, code, 0, 0)?;
                let release = self.event(KEY_RELEASE_EVENT, code, 0, 0);
                syms.clear();
                release?;
            }
            Ok(())
        })();
        let restore = self
            .conn
            .change_keyboard_mapping(1, code, map.keysyms_per_keycode, &original)
            .map_err(err)?
            .check()
            .map_err(err);
        result.and(restore)
    }
    fn mouse(&mut self, action: &str, x: i32, y: i32, dy: i32) -> io::Result<()> {
        match action {
            "move" => self.event(
                MOTION_NOTIFY_EVENT,
                0,
                x.clamp(0, i16::MAX as i32) as i16,
                y.clamp(0, i16::MAX as i32) as i16,
            ),
            "click" | "right_click" => {
                let b = if action == "click" { 1 } else { 3 };
                self.event(BUTTON_PRESS_EVENT, b, 0, 0)?;
                self.event(BUTTON_RELEASE_EVENT, b, 0, 0)
            }
            "scroll" => {
                for _ in 0..dy.unsigned_abs() {
                    let b = if dy > 0 { 4 } else { 5 };
                    self.event(BUTTON_PRESS_EVENT, b, 0, 0)?;
                    self.event(BUTTON_RELEASE_EVENT, b, 0, 0)?;
                }
                Ok(())
            }
            _ => Err(invalid("unknown mouse action")),
        }
    }
}
const UINPUT_HELP:&str="Cannot open /dev/uinput. Run sudo modprobe uinput; add /etc/udev/rules.d/70-homedroid-input.rules containing: KERNEL==\"uinput\", SUBSYSTEM==\"misc\", GROUP=\"input\", MODE=\"0660\" ; then sudo udevadm control --reload-rules && sudo udevadm trigger; sudo usermod -aG input $USER and log in again.";
pub fn ioctl<T>(fd: RawFd, request: libc::c_ulong, value: &mut T) -> io::Result<()> {
    if unsafe { libc::ioctl(fd, request as _, value as *mut T) } < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}
fn bit(fd: RawFd, request: libc::c_ulong, value: i32) -> io::Result<()> {
    if unsafe { libc::ioctl(fd, request as _, value) } < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}
struct UInput {
    file: File,
}
impl UInput {
    fn new() -> io::Result<Self> {
        let mut file = OpenOptions::new()
            .write(true)
            .open("/dev/uinput")
            .map_err(|e| invalid(format!("{e}. {UINPUT_HELP}")))?;
        let fd = file.as_raw_fd();
        bit(fd, 0x40045564, 1)?;
        bit(fd, 0x40045564, 2)?;
        for k in 1..=248 {
            bit(fd, 0x40045565, k)?;
        }
        for k in [272, 273] {
            bit(fd, 0x40045565, k)?;
        }
        for a in [0, 1, 8] {
            bit(fd, 0x40045566, a)?;
        }
        // Legacy uinput_user_dev ABI: name[80], input_id[4*u16], ff_effects_max, four abs arrays[64].
        let mut dev = [0u8; 1116];
        let name = b"Homedroid MacroPads";
        dev[..name.len()].copy_from_slice(name);
        dev[80..82].copy_from_slice(&3u16.to_ne_bytes());
        dev[82..84].copy_from_slice(&0x4844u16.to_ne_bytes());
        file.write_all(&dev)?;
        bit(fd, 0x5501, 0)?;
        std::thread::sleep(std::time::Duration::from_millis(250));
        Ok(Self { file })
    }
    fn event(&mut self, kind: u16, code: u16, value: i32) -> io::Result<()> {
        let e = libc::input_event {
            time: libc::timeval {
                tv_sec: 0,
                tv_usec: 0,
            },
            type_: kind,
            code,
            value,
        };
        let b = unsafe {
            std::slice::from_raw_parts(
                (&e as *const libc::input_event).cast::<u8>(),
                std::mem::size_of_val(&e),
            )
        };
        self.file.write_all(b)
    }
    fn sync(&mut self) -> io::Result<()> {
        self.event(0, 0, 0)
    }
}
fn evkey(k: Key) -> io::Result<(u16, bool)> {
    use Key::*;
    let code = match k {
        Ctrl => 29,
        Shift => 42,
        Alt => 56,
        Meta => 125,
        Enter => 28,
        Tab => 15,
        Escape => 1,
        Space => 57,
        Backspace => 14,
        Delete => 111,
        Up => 103,
        Down => 108,
        Left => 105,
        Right => 106,
        Home => 102,
        End => 107,
        PageUp => 104,
        PageDown => 109,
        PrintScreen => 99,
        F(n) if n <= 10 => 58 + n as u16,
        F(11) => 87,
        F(12) => 88,
        F(n) => 183 + (n - 13) as u16,
        Media(n) => [164, 163, 165, 166, 115, 114, 113][n as usize],
        Char(c) => {
            let chars = "1234567890-=qwertyuiop[]asdfghjkl;'`\\zxcvbnm,./ ";
            let codes = [
                2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
                27, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 43, 44, 45, 46, 47, 48, 49, 50,
                51, 52, 53, 57,
            ];
            if let Some(i) = chars.chars().position(|x| x == c.to_ascii_lowercase()) {
                return Ok((codes[i], c.is_ascii_uppercase()));
            }
            if let Some(i) = "!@#$%^&*()_+{}:\"~|<>?".chars().position(|x| x == c) {
                return Ok((
                    [
                        2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 26, 27, 39, 40, 41, 43, 51, 52, 53,
                    ][i],
                    true,
                ));
            }
            return Err(invalid("uinput hotkeys require a US keyboard character"));
        }
    };
    Ok((code, false))
}
impl Input for UInput {
    fn chord(&mut self, keys: &[Key]) -> io::Result<()> {
        let mut codes = Vec::new();
        for &k in keys {
            let (c, shift) = evkey(k)?;
            if shift && !codes.contains(&42) {
                codes.push(42);
            }
            if !codes.contains(&c) {
                codes.push(c);
            }
        }
        let result = (|| {
            for &c in &codes {
                self.event(1, c, 1)?;
            }
            self.sync()
        })();
        let mut release = Ok(());
        for &c in codes.iter().rev() {
            if let Err(e) = self.event(1, c, 0) {
                release = Err(e);
            }
        }
        self.sync()?;
        result.and(release)
    }
    fn text(&mut self, text: &str) -> io::Result<()> {
        for c in text.chars() {
            match c {
                '\n' | '\r' => self.chord(&[Key::Enter])?,
                '\t' => self.chord(&[Key::Tab])?,
                c if c.is_ascii() => self.chord(&[Key::Char(c)])?,
                c => {
                    self.chord(&[Key::Ctrl, Key::Shift, Key::Char('u')])?;
                    for h in format!("{:x}", c as u32).chars() {
                        self.chord(&[Key::Char(h)])?;
                    }
                    self.chord(&[Key::Enter])?;
                }
            }
        }
        Ok(())
    }
    fn mouse(&mut self, a: &str, x: i32, y: i32, dy: i32) -> io::Result<()> {
        match a {
            "move" => {
                self.event(2, 0, x)?;
                self.event(2, 1, y)?;
            }
            "scroll" => self.event(2, 8, dy)?,
            "click" | "right_click" => {
                let b = if a == "click" { 272 } else { 273 };
                self.event(1, b, 1)?;
                self.sync()?;
                self.event(1, b, 0)?;
            }
            _ => return Err(invalid("unknown mouse action")),
        }
        self.sync()
    }
}
impl Drop for UInput {
    fn drop(&mut self) {
        let _ = bit(self.file.as_raw_fd(), 0x5502, 0);
    }
}
pub fn random_token() -> io::Result<String> {
    use std::io::Read;
    let mut b = [0; 32];
    File::open("/dev/urandom")?.read_exact(&mut b)?;
    Ok(b.iter().map(|b| format!("{b:02x}")).collect())
}
