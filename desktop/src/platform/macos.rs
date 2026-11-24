//! macOS input through CoreGraphics events (needs the Accessibility permission). Not
//! compiled or tested on the machine this was written on.
use crate::{
    actions::{Input, Key},
    http::invalid,
};
use std::{
    ffi::c_void,
    io::{self, Read},
    process::Command,
};

#[repr(C)]
#[derive(Clone, Copy)]
struct CGPoint {
    x: f64,
    y: f64,
}

#[link(name = "CoreGraphics", kind = "framework")]
#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    fn CGEventCreate(source: *const c_void) -> *mut c_void;
    fn CGEventCreateKeyboardEvent(source: *const c_void, key: u16, down: bool) -> *mut c_void;
    fn CGEventCreateMouseEvent(
        source: *const c_void,
        kind: u32,
        at: CGPoint,
        button: u32,
    ) -> *mut c_void;
    fn CGEventCreateScrollWheelEvent2(
        source: *const c_void,
        units: u32,
        count: u32,
        w1: i32,
        w2: i32,
        w3: i32,
    ) -> *mut c_void;
    fn CGEventGetLocation(event: *const c_void) -> CGPoint;
    fn CGEventSetFlags(event: *mut c_void, flags: u64);
    fn CGEventKeyboardSetUnicodeString(event: *mut c_void, len: usize, text: *const u16);
    fn CGEventPost(tap: u32, event: *mut c_void);
    fn CFRelease(object: *const c_void);
}

const HID_TAP: u32 = 0;
const SHIFT: u64 = 0x20000;
const CTRL: u64 = 0x40000;
const ALT: u64 = 0x80000;
const CMD: u64 = 0x100000;

pub fn random_token() -> io::Result<String> {
    let mut b = [0u8; 32];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut b)?;
    Ok(b.iter().map(|b| format!("{b:02x}")).collect())
}

pub fn input() -> io::Result<Box<dyn Input>> {
    Ok(Box::new(Mac))
}

struct Mac;

fn post(event: *mut c_void, flags: Option<u64>) -> io::Result<()> {
    if event.is_null() {
        return Err(invalid(
            "CoreGraphics refused the event (grant Accessibility permission)",
        ));
    }
    unsafe {
        if let Some(f) = flags {
            CGEventSetFlags(event, f);
        }
        CGEventPost(HID_TAP, event);
        CFRelease(event);
    }
    Ok(())
}

fn code(k: Key) -> io::Result<(u16, u64)> {
    use Key::*;
    Ok(match k {
        Ctrl => (59, CTRL),
        Shift => (56, SHIFT),
        Alt => (58, ALT),
        Meta => (55, CMD),
        Enter => (36, 0),
        Tab => (48, 0),
        Escape => (53, 0),
        Space => (49, 0),
        Backspace => (51, 0),
        Delete => (117, 0),
        Up => (126, 0),
        Down => (125, 0),
        Left => (123, 0),
        Right => (124, 0),
        Home => (115, 0),
        End => (119, 0),
        PageUp => (116, 0),
        PageDown => (121, 0),
        F(n) => (
            *[
                122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111, 105, 107, 113, 106, 64, 79,
                80, 90,
            ]
            .get(n as usize - 1)
            .ok_or_else(|| invalid("macOS keyboards have no F21-F24"))?,
            0,
        ),
        PrintScreen => return Err(invalid("printscreen is not a macOS key")),
        Media(_) => return Err(invalid("media keys are handled separately")),
        Char(c) => {
            let lower = c.to_ascii_lowercase();
            let ansi: &[(char, u16)] = &[
                ('a', 0),
                ('s', 1),
                ('d', 2),
                ('f', 3),
                ('h', 4),
                ('g', 5),
                ('z', 6),
                ('x', 7),
                ('c', 8),
                ('v', 9),
                ('b', 11),
                ('q', 12),
                ('w', 13),
                ('e', 14),
                ('r', 15),
                ('y', 16),
                ('t', 17),
                ('1', 18),
                ('2', 19),
                ('3', 20),
                ('4', 21),
                ('6', 22),
                ('5', 23),
                ('=', 24),
                ('9', 25),
                ('7', 26),
                ('-', 27),
                ('8', 28),
                ('0', 29),
                (']', 30),
                ('o', 31),
                ('u', 32),
                ('[', 33),
                ('i', 34),
                ('p', 35),
                ('l', 37),
                ('j', 38),
                ('\'', 39),
                ('k', 40),
                (';', 41),
                ('\\', 42),
                (',', 43),
                ('/', 44),
                ('n', 45),
                ('m', 46),
                ('.', 47),
                ('`', 50),
            ];
            let (_, vk) = ansi
                .iter()
                .find(|(ch, _)| *ch == lower)
                .ok_or_else(|| invalid("character is not on the macOS ANSI layout; use text"))?;
            (*vk, if c.is_ascii_uppercase() { SHIFT } else { 0 })
        }
    })
}

fn script(source: &str) -> io::Result<()> {
    let status = Command::new("osascript").args(["-e", source]).status()?;
    if status.success() {
        Ok(())
    } else {
        Err(invalid("osascript failed"))
    }
}

fn media(n: u8) -> io::Result<()> {
    script(match n {
        0 => "tell application \"Music\" to playpause",
        1 => "tell application \"Music\" to next track",
        2 => "tell application \"Music\" to previous track",
        3 => "tell application \"Music\" to stop",
        4 => "set volume output volume ((output volume of (get volume settings)) + 6)",
        5 => "set volume output volume ((output volume of (get volume settings)) - 6)",
        _ => "set volume output muted not (output muted of (get volume settings))",
    })
}

impl Input for Mac {
    fn chord(&mut self, keys: &[Key]) -> io::Result<()> {
        if let [Key::Media(n)] = keys {
            return media(*n);
        }
        let mut codes = Vec::new();
        let mut flags = 0;
        for &k in keys {
            let (vk, f) = code(k)?;
            flags |= f;
            if !codes.iter().any(|&(c, _)| c == vk) {
                codes.push((vk, f != 0));
            }
        }
        let mut pressed: Vec<u16> = Vec::new();
        let mut result = Ok(());
        let mut held = 0;
        for &(vk, modifier) in &codes {
            if modifier {
                held |= code_flag(vk);
            }
            let r = post(
                unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), vk, true) },
                Some(held | if modifier { 0 } else { flags }),
            );
            match r {
                Ok(()) => pressed.push(vk),
                Err(e) => {
                    result = Err(e);
                    break;
                }
            }
        }
        let mut release = Ok(());
        for &vk in pressed.iter().rev() {
            if let Err(e) = post(
                unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), vk, false) },
                Some(0),
            ) {
                release = Err(e);
            }
        }
        result.and(release)
    }

    fn text(&mut self, text: &str) -> io::Result<()> {
        for c in text.chars() {
            if c.is_control() && c != '\n' && c != '\t' {
                return Err(invalid("unsupported control character"));
            }
            let mut buf = [0u16; 2];
            let units = c.encode_utf16(&mut buf);
            for down in [true, false] {
                let ev = unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), 0, down) };
                if !ev.is_null() {
                    unsafe { CGEventKeyboardSetUnicodeString(ev, units.len(), units.as_ptr()) };
                }
                post(ev, None)?;
            }
        }
        Ok(())
    }

    fn mouse(&mut self, action: &str, x: i32, y: i32, dy: i32) -> io::Result<()> {
        let here = unsafe {
            let e = CGEventCreate(std::ptr::null());
            let p = if e.is_null() {
                CGPoint { x: 0.0, y: 0.0 }
            } else {
                CGEventGetLocation(e)
            };
            if !e.is_null() {
                CFRelease(e);
            }
            p
        };
        let button = |down: u32, up: u32, b: u32| -> io::Result<()> {
            post(
                unsafe { CGEventCreateMouseEvent(std::ptr::null(), down, here, b) },
                None,
            )?;
            post(
                unsafe { CGEventCreateMouseEvent(std::ptr::null(), up, here, b) },
                None,
            )
        };
        match action {
            "move" => post(
                unsafe {
                    CGEventCreateMouseEvent(
                        std::ptr::null(),
                        5,
                        CGPoint {
                            x: f64::from(x),
                            y: f64::from(y),
                        },
                        0,
                    )
                },
                None,
            ),
            "click" => button(1, 2, 0),
            "right_click" => button(3, 4, 1),
            "scroll" => post(
                unsafe { CGEventCreateScrollWheelEvent2(std::ptr::null(), 1, 1, dy, 0, 0) },
                None,
            ),
            _ => Err(invalid("unknown mouse action")),
        }
    }
}

fn code_flag(vk: u16) -> u64 {
    match vk {
        59 => CTRL,
        56 => SHIFT,
        58 => ALT,
        _ => CMD,
    }
}
