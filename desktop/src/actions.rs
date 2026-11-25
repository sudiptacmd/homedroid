use crate::http::invalid;
use serde::{Deserialize, Serialize};
use std::{
    io,
    process::{Command, Stdio},
    time::Duration,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    Char(char),
    Ctrl,
    Shift,
    Alt,
    Meta,
    Enter,
    Tab,
    Escape,
    Space,
    Backspace,
    Delete,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    F(u8),
    PrintScreen,
    Media(u8),
}
pub fn key(s: &str) -> io::Result<Key> {
    use Key::*;
    let k = match s.to_ascii_lowercase().as_str() {
        "ctrl" => Ctrl,
        "shift" => Shift,
        "alt" => Alt,
        "meta" => Meta,
        "enter" => Enter,
        "tab" => Tab,
        "escape" => Escape,
        "space" => Space,
        "backspace" => Backspace,
        "delete" => Delete,
        "up" => Up,
        "down" => Down,
        "left" => Left,
        "right" => Right,
        "home" => Home,
        "end" => End,
        "pageup" => PageUp,
        "pagedown" => PageDown,
        "printscreen" => PrintScreen,
        _ => {
            if s.starts_with('f') && s.len() > 1 {
                if let Ok(n) = s[1..].parse::<u8>() {
                    if (1..=24).contains(&n) {
                        return Ok(F(n));
                    }
                }
            }
            let mut c = s.chars();
            match (c.next(), c.next()) {
                (Some(c), None) if !c.is_control() => Char(c),
                _ => return Err(invalid(format!("unknown key: {s}"))),
            }
        }
    };
    Ok(k)
}
pub fn media(s: &str) -> io::Result<Key> {
    [
        "play_pause",
        "next",
        "previous",
        "stop",
        "volume_up",
        "volume_down",
        "mute",
    ]
    .iter()
    .position(|&k| k == s)
    .map(|n| Key::Media(n as u8))
    .ok_or_else(|| invalid("unknown media key"))
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Action {
    Hotkey {
        keys: Vec<String>,
    },
    Key {
        key: String,
        #[serde(default = "one")]
        repeat: u16,
    },
    Text {
        text: String,
    },
    Media {
        key: String,
    },
    Open {
        target: String,
    },
    Launch {
        path: String,
        #[serde(default)]
        args: Vec<String>,
    },
    Command {
        command: String,
        #[serde(default)]
        shell: bool,
    },
    Mouse {
        action: String,
        #[serde(default)]
        x: i32,
        #[serde(default)]
        y: i32,
        #[serde(default)]
        dy: i32,
    },
    Sequence {
        steps: Vec<Step>,
    },
}
fn one() -> u16 {
    1
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Step {
    #[serde(rename = "delayMs", default)]
    pub delay_ms: u64,
    #[serde(flatten)]
    pub action: Action,
}
impl Action {
    pub fn validate(&self, allow: bool) -> io::Result<()> {
        let mut count = 0;
        let mut delay = 0;
        self.check(allow, 0, &mut count, &mut delay)
    }
    fn check(
        &self,
        allow: bool,
        depth: usize,
        count: &mut usize,
        delay: &mut u64,
    ) -> io::Result<()> {
        *count += 1;
        if depth > 8 || *count > 128 {
            return Err(invalid("sequence is too large"));
        }
        let safe = |s: &str, max: usize| -> io::Result<()> {
            if s.is_empty() || s.len() > max || s.contains('\0') {
                Err(invalid("empty, oversized or NUL-containing action field"))
            } else {
                Ok(())
            }
        };
        match self {
            Self::Hotkey { keys } => {
                if keys.is_empty() || keys.len() > 8 {
                    return Err(invalid("hotkey needs 1–8 keys"));
                }
                for s in keys {
                    key(s)?;
                }
            }
            Self::Key { key: k, repeat } => {
                key(k)?;
                if !(1..=100).contains(repeat) {
                    return Err(invalid("repeat must be 1–100"));
                }
            }
            Self::Text { text } => {
                safe(text, 8192)?;
            }
            Self::Media { key: k } => {
                media(k)?;
            }
            Self::Open { target } => {
                safe(target, 4096)?;
                if target.starts_with('-') {
                    return Err(invalid("target must not be an option"));
                }
            }
            Self::Launch { path, args } => {
                safe(path, 4096)?;
                if args.len() > 128 {
                    return Err(invalid("too many arguments"));
                }
                for a in args {
                    if a.len() > 8192 || a.contains('\0') {
                        return Err(invalid("invalid argument"));
                    }
                }
            }
            Self::Command { command, .. } => {
                if !allow {
                    return Err(invalid(
                        "Commands are disabled. Enable Allow commands locally.",
                    ));
                }
                safe(command, 8192)?;
            }
            Self::Mouse { action, x, y, dy } => {
                if !["click", "right_click", "move", "scroll"].contains(&action.as_str())
                    || x.abs_diff(0) > 100_000
                    || y.abs_diff(0) > 100_000
                    || dy.abs_diff(0) > 10_000
                {
                    return Err(invalid("invalid mouse action or coordinates"));
                }
            }
            Self::Sequence { steps } => {
                if steps.is_empty() {
                    return Err(invalid("empty sequence"));
                }
                for s in steps {
                    *delay = delay.saturating_add(s.delay_ms);
                    if s.delay_ms > 10_000 || *delay > 60_000 {
                        return Err(invalid("sequence delays are too long"));
                    }
                    s.action.check(allow, depth + 1, count, delay)?;
                }
            }
        };
        Ok(())
    }
}
pub trait Input {
    fn chord(&mut self, keys: &[Key]) -> io::Result<()>;
    fn text(&mut self, text: &str) -> io::Result<()>;
    fn mouse(&mut self, action: &str, x: i32, y: i32, dy: i32) -> io::Result<()>;
}
pub fn detach(mut command: Command) -> io::Result<()> {
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x00000008 | 0x00000200);
    }
    let mut child = command.spawn()?;
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
}
pub fn open(target: &str) -> io::Result<()> {
    #[cfg(target_os = "linux")]
    let mut c = Command::new("xdg-open");
    #[cfg(target_os = "macos")]
    let mut c = Command::new("open");
    #[cfg(windows)]
    {
        return crate::platform::open(target);
    }
    #[cfg(not(windows))]
    {
        c.arg(target);
        detach(c)
    }
}
static DRY_RUN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
/// Dry-run validates and logs actions without touching the keyboard, mouse or processes.
pub fn set_dry_run(on: bool) {
    DRY_RUN.store(on, std::sync::atomic::Ordering::SeqCst);
}
pub fn dry_run() -> bool {
    DRY_RUN.load(std::sync::atomic::Ordering::SeqCst)
}
pub fn execute(action: &Action, input: &mut Option<Box<dyn Input>>, allow: bool) -> io::Result<()> {
    action.validate(allow)?;
    if dry_run() && !matches!(action, Action::Sequence { .. }) {
        eprintln!("[dry-run] {action:?}");
        return Ok(());
    }
    match action {
        Action::Open { target } => open(target),
        Action::Launch { path, args } => {
            let mut c = Command::new(path);
            c.args(args);
            detach(c)
        }
        Action::Command { command, shell } => {
            let mut c = if *shell {
                #[cfg(windows)]
                let mut c = Command::new("cmd.exe");
                #[cfg(windows)]
                c.args(["/C", command]);
                #[cfg(not(windows))]
                let mut c = Command::new("/bin/sh");
                #[cfg(not(windows))]
                c.args(["-c", command]);
                c
            } else {
                let args = split_command(command)?;
                let mut c = Command::new(&args[0]);
                c.args(&args[1..]);
                c
            };
            c.stdin(Stdio::null());
            detach(c)
        }
        Action::Sequence { steps } => {
            for s in steps {
                std::thread::sleep(Duration::from_millis(s.delay_ms));
                execute(&s.action, input, allow)?;
            }
            Ok(())
        }
        _ => {
            if input.is_none() {
                *input = Some(crate::platform::input()?);
            }
            let i = input.as_mut().ok_or_else(|| invalid("input unavailable"))?;
            match action {
                Action::Hotkey { keys } => i.chord(
                    &keys
                        .iter()
                        .map(|s| key(s))
                        .collect::<io::Result<Vec<_>>>()?,
                ),
                Action::Key { key: k, repeat } => {
                    for _ in 0..*repeat {
                        i.chord(&[key(k)?])?;
                    }
                    Ok(())
                }
                Action::Text { text } => i.text(text),
                Action::Media { key: k } => i.chord(&[media(k)?]),
                Action::Mouse { action, x, y, dy } => i.mouse(action, *x, *y, *dy),
                _ => unreachable!(),
            }
        }
    }
}
/// Minimal quoted argument splitting, deliberately without shell expansion.
fn split_command(s: &str) -> io::Result<Vec<String>> {
    let mut out = Vec::new();
    let mut word = String::new();
    let mut quote = None;
    let mut escape = false;
    let mut started = false;
    for c in s.chars() {
        if escape {
            word.push(c);
            escape = false;
        } else if c == '\\' && quote != Some('\'') {
            escape = true;
            started = true;
        } else if Some(c) == quote {
            quote = None;
        } else if quote.is_none() && (c == '\'' || c == '"') {
            quote = Some(c);
            started = true;
        } else if c.is_whitespace() && quote.is_none() {
            if started {
                out.push(std::mem::take(&mut word));
                started = false;
            }
        } else {
            word.push(c);
            started = true;
        }
    }
    if escape || quote.is_some() {
        return Err(invalid("unclosed command quote or escape"));
    }
    if started {
        out.push(word);
    }
    if out.is_empty() {
        return Err(invalid("empty command"));
    }
    Ok(out)
}
#[cfg(test)]
mod doc_tests {
    use super::*;

    #[test]
    fn every_documented_action_parses_and_validates() {
        for j in [
            r#"{"kind":"hotkey","keys":["ctrl","shift","m"]}"#,
            r#"{"kind":"key","key":"enter","repeat":3}"#,
            r#"{"kind":"key","key":"a"}"#,
            r#"{"kind":"text","text":"héllo"}"#,
            r#"{"kind":"media","key":"volume_up"}"#,
            r#"{"kind":"open","target":"https://example.com"}"#,
            r#"{"kind":"launch","path":"/usr/bin/true","args":["x"]}"#,
            r#"{"kind":"command","command":"ls","shell":true,"computer":"c-1"}"#,
            r#"{"kind":"mouse","action":"scroll","dy":-3}"#,
            r#"{"kind":"sequence","steps":[{"kind":"key","key":"tab","delayMs":10},{"kind":"text","text":"x"}]}"#,
        ] {
            let a: Action = serde_json::from_str(j).unwrap_or_else(|e| panic!("{j}: {e}"));
            assert!(a.validate(true).is_ok(), "{j}");
        }
    }

    #[test]
    fn rejects_bad_input() {
        for j in [
            r#"{"kind":"hotkey","keys":[]}"#,
            r#"{"kind":"hotkey","keys":["nope"]}"#,
            r#"{"kind":"key","key":"a","repeat":0}"#,
            r#"{"kind":"key","key":"a","repeat":1000}"#,
            r#"{"kind":"media","key":"eject"}"#,
            r#"{"kind":"text","text":""}"#,
            r#"{"kind":"open","target":"--help"}"#,
            r#"{"kind":"mouse","action":"drag"}"#,
            r#"{"kind":"sequence","steps":[]}"#,
        ] {
            let a: Action = serde_json::from_str(j).unwrap();
            assert!(a.validate(true).is_err(), "{j}");
        }
        assert!(serde_json::from_str::<Action>(r#"{"kind":"key"}"#).is_err());
    }

    #[test]
    fn commands_need_permission() {
        let a: Action = serde_json::from_str(r#"{"kind":"command","command":"ls"}"#).unwrap();
        assert!(a.validate(false).is_err());
        let mut input = None;
        assert!(execute(&a, &mut input, false).is_err());
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn keys() {
        assert_eq!(key("f24").unwrap(), Key::F(24));
        assert_eq!(key("é").unwrap(), Key::Char('é'));
        assert!(key("f25").is_err());
        assert!(key("\n").is_err());
        assert_eq!(key("CTRL").unwrap(), Key::Ctrl);
    }
    #[test]
    fn validation() {
        let a: Action = serde_json::from_str(
            r#"{"kind":"sequence","steps":[{"kind":"command","command":"echo test","delayMs":1}]}"#,
        )
        .unwrap();
        assert!(a.validate(false).is_err());
        assert!(a.validate(true).is_ok());
        let a: Action =
            serde_json::from_str(r#"{"kind":"mouse","action":"move","x":-2147483648}"#).unwrap();
        assert!(a.validate(false).is_err());
        assert!(serde_json::from_str::<Action>(r#"{"kind":"unknown"}"#).is_err());
    }
    #[test]
    fn commands() {
        assert_eq!(
            split_command("app 'hello world' \"\" a\\ b").unwrap(),
            ["app", "hello world", "", "a b"]
        );
        assert!(split_command("'oops").is_err());
    }
}
