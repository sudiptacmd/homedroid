use crate::{
    actions::{Input, Key},
    http::invalid,
};
use std::io;
use windows::{
    core::{w, PCWSTR},
    Win32::{
        Foundation::*,
        Media::MediaFoundation::*,
        System::Com::*,
        UI::{
            Input::KeyboardAndMouse::*, Shell::ShellExecuteW, WindowsAndMessaging::SW_SHOWNORMAL,
        },
    },
};
fn err(e: impl std::fmt::Display) -> io::Error {
    invalid(e.to_string())
}
pub fn random_token() -> io::Result<String> {
    let a = unsafe { CoCreateGuid() }.map_err(err)?;
    let b = unsafe { CoCreateGuid() }.map_err(err)?;
    Ok(format!("{:032x}{:032x}", a.to_u128(), b.to_u128()))
}
pub fn open(target: &str) -> io::Result<()> {
    let t: Vec<u16> = target.encode_utf16().chain(Some(0)).collect();
    let h = unsafe {
        ShellExecuteW(
            None,
            w!("open"),
            PCWSTR(t.as_ptr()),
            None,
            None,
            SW_SHOWNORMAL,
        )
    };
    if h.0 as usize <= 32 {
        Err(invalid("Windows could not open target"))
    } else {
        Ok(())
    }
}
pub fn input() -> io::Result<Box<dyn Input>> {
    Ok(Box::new(WinInput))
}
struct WinInput;
fn send(events: &[INPUT]) -> io::Result<()> {
    if unsafe { SendInput(events, std::mem::size_of::<INPUT>() as i32) } != events.len() as u32 {
        Err(invalid("SendInput failed (target may be elevated)"))
    } else {
        Ok(())
    }
}
fn keyboard(vk: VIRTUAL_KEY, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: vk,
                wScan: scan,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}
fn vk(k: Key) -> io::Result<(VIRTUAL_KEY, u8)> {
    use Key::*;
    Ok((
        match k {
            Ctrl => VK_CONTROL,
            Shift => VK_SHIFT,
            Alt => VK_MENU,
            Meta => VK_LWIN,
            Enter => VK_RETURN,
            Tab => VK_TAB,
            Escape => VK_ESCAPE,
            Space => VK_SPACE,
            Backspace => VK_BACK,
            Delete => VK_DELETE,
            Up => VK_UP,
            Down => VK_DOWN,
            Left => VK_LEFT,
            Right => VK_RIGHT,
            Home => VK_HOME,
            End => VK_END,
            PageUp => VK_PRIOR,
            PageDown => VK_NEXT,
            F(n) => VIRTUAL_KEY(VK_F1.0 + n as u16 - 1),
            PrintScreen => VK_SNAPSHOT,
            Media(n) => [
                VK_MEDIA_PLAY_PAUSE,
                VK_MEDIA_NEXT_TRACK,
                VK_MEDIA_PREV_TRACK,
                VK_MEDIA_STOP,
                VK_VOLUME_UP,
                VK_VOLUME_DOWN,
                VK_VOLUME_MUTE,
            ][n as usize],
            Char(c) => {
                if c as u32 > 65535 {
                    return Err(invalid("use text for non-BMP characters"));
                }
                let v = unsafe { VkKeyScanW(c as u16) };
                if v == -1 {
                    return Err(invalid(
                        "character is not mapped in Windows keyboard layout",
                    ));
                }
                return Ok((VIRTUAL_KEY(v as u16 & 255), (v as u16 >> 8) as u8));
            }
        },
        0,
    ))
}
fn extended(k: VIRTUAL_KEY) -> KEYBD_EVENT_FLAGS {
    if [
        VK_RCONTROL,
        VK_RMENU,
        VK_LWIN,
        VK_RWIN,
        VK_INSERT,
        VK_DELETE,
        VK_HOME,
        VK_END,
        VK_PRIOR,
        VK_NEXT,
        VK_LEFT,
        VK_RIGHT,
        VK_UP,
        VK_DOWN,
        VK_SNAPSHOT,
        VK_MEDIA_PLAY_PAUSE,
        VK_MEDIA_NEXT_TRACK,
        VK_MEDIA_PREV_TRACK,
        VK_MEDIA_STOP,
        VK_VOLUME_UP,
        VK_VOLUME_DOWN,
        VK_VOLUME_MUTE,
    ]
    .contains(&k)
    {
        KEYEVENTF_EXTENDEDKEY
    } else {
        KEYBD_EVENT_FLAGS(0)
    }
}
impl Input for WinInput {
    fn chord(&mut self, keys: &[Key]) -> io::Result<()> {
        let mut codes = Vec::new();
        for &k in keys {
            let (v, mods) = vk(k)?;
            for (bit, m) in [(1, VK_SHIFT), (2, VK_CONTROL), (4, VK_MENU)] {
                if mods & bit != 0 && !codes.contains(&m) {
                    codes.push(m);
                }
            }
            if !codes.contains(&v) {
                codes.push(v);
            }
        }
        let downs: Vec<_> = codes.iter().map(|&k| keyboard(k, 0, extended(k))).collect();
        let ups: Vec<_> = codes
            .iter()
            .rev()
            .map(|&k| keyboard(k, 0, extended(k) | KEYEVENTF_KEYUP))
            .collect();
        let r = send(&downs);
        let release = send(&ups);
        r.and(release)
    }
    fn text(&mut self, s: &str) -> io::Result<()> {
        for c in s.encode_utf16() {
            send(&[
                keyboard(VIRTUAL_KEY(0), c, KEYEVENTF_UNICODE),
                keyboard(VIRTUAL_KEY(0), c, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP),
            ])?;
        }
        Ok(())
    }
    fn mouse(&mut self, a: &str, x: i32, y: i32, dy: i32) -> io::Result<()> {
        let event = |flags, dx, dy, data| INPUT {
            r#type: INPUT_MOUSE,
            Anonymous: INPUT_0 {
                mi: MOUSEINPUT {
                    dx,
                    dy,
                    mouseData: data,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        };
        match a {
            "move" => {
                use windows::Win32::UI::WindowsAndMessaging::{
                    GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN,
                };
                let (w, h) = unsafe {
                    (
                        GetSystemMetrics(SM_CXSCREEN).max(2),
                        GetSystemMetrics(SM_CYSCREEN).max(2),
                    )
                };
                send(&[event(
                    MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE,
                    (x.clamp(0, w - 1) as i64 * 65535 / (w - 1) as i64) as i32,
                    (y.clamp(0, h - 1) as i64 * 65535 / (h - 1) as i64) as i32,
                    0,
                )])
            }
            "scroll" => send(&[event(MOUSEEVENTF_WHEEL, 0, 0, (dy * 120) as u32)]),
            "click" => send(&[
                event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0),
                event(MOUSEEVENTF_LEFTUP, 0, 0, 0),
            ]),
            "right_click" => send(&[
                event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0),
                event(MOUSEEVENTF_RIGHTUP, 0, 0, 0),
            ]),
            _ => Err(invalid("invalid mouse action")),
        }
    }
}
#[allow(dead_code)]
#[path = "../../vcam-win/src/ring.rs"]
mod ring;
pub struct CameraSink {
    ring: ring::Ring,
    _camera: IMFVirtualCamera,
}
impl CameraSink {
    pub fn describe(&self) -> String {
        "Homedroid Camera (Windows 11 virtual camera)".into()
    }
    pub fn new(_: &crate::camera::Options) -> io::Result<Self> {
        unsafe {
            CoInitializeEx(None, COINIT_MULTITHREADED)
                .ok()
                .map_err(err)?;
            MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(err)?;
            let camera = MFCreateVirtualCamera(
                MFVirtualCameraType_SoftwareCameraSource,
                MFVirtualCameraLifetime_Session,
                MFVirtualCameraAccess_CurrentUser,
                w!("Homedroid Camera"),
                w!("{6F3A82F0-5436-4C16-A591-888B064632A1}"),
                None,
            )
            .map_err(|e| {
                invalid(format!(
                    "Windows 11 camera: {e}; run install-camera once as administrator"
                ))
            })?;
            let ring = ring::Ring::create().map_err(err)?;
            camera.Start(None).map_err(err)?;
            Ok(Self {
                ring,
                _camera: camera,
            })
        }
    }
    pub fn write(&mut self, rgb: &[u8], w: usize, h: usize) -> io::Result<()> {
        self.ring.publish(rgb, w, h).map_err(err)
    }
}
impl Drop for CameraSink {
    fn drop(&mut self) {
        unsafe {
            let _ = self._camera.Stop();
            let _ = MFShutdown();
            CoUninitialize();
        }
    }
}
pub fn install_camera() -> io::Result<()> {
    use windows::Win32::System::LibraryLoader::*;
    let dll = std::env::current_exe()?.with_file_name("homedroid_vcam.dll");
    let path: Vec<u16> = dll
        .as_os_str()
        .to_string_lossy()
        .encode_utf16()
        .chain(Some(0))
        .collect();
    unsafe {
        let module = LoadLibraryW(PCWSTR(path.as_ptr())).map_err(err)?;
        let f = GetProcAddress(module, windows::core::s!("DllRegisterServer"))
            .ok_or_else(|| invalid("DLL has no DllRegisterServer"))?;
        let register: unsafe extern "system" fn() -> windows::core::HRESULT =
            std::mem::transmute(f);
        let result = register().ok().map_err(err);
        let _ = FreeLibrary(module);
        result
    }
}
