use crate::http::invalid;
use serde::{Deserialize, Serialize};
use std::io;
use zune_core::{colorspace::ColorSpace, options::DecoderOptions};
use zune_jpeg::JpegDecoder;
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Options {
    pub camera: String,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub quality: u32,
    pub device: Option<String>,
    pub mirror: bool,
}
impl Default for Options {
    fn default() -> Self {
        Self {
            camera: "back".into(),
            width: 1280,
            height: 720,
            fps: 30,
            quality: 80,
            device: None,
            mirror: false,
        }
    }
}
impl Options {
    pub fn validate(&self) -> io::Result<()> {
        if self.camera.is_empty()
            || self.camera.len() > 64
            || !self
                .camera
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
            || !(2..=4096).contains(&self.width)
            || !(2..=2160).contains(&self.height)
            || !(1..=60).contains(&self.fps)
            || !(1..=100).contains(&self.quality)
        {
            return Err(invalid(
                "invalid camera, dimensions, fps (1–60), or quality (1–100)",
            ));
        }
        Ok(())
    }
    pub fn path(&self) -> String {
        format!(
            "/desktop/v1/webcam?camera={}&width={}&height={}&fps={}&quality={}",
            self.camera, self.width, self.height, self.fps, self.quality
        )
    }
}
pub fn decode(jpeg: &[u8], mirror: bool) -> io::Result<(Vec<u8>, usize, usize)> {
    let options = DecoderOptions::default()
        .jpeg_set_out_colorspace(ColorSpace::RGB)
        .set_max_width(4096)
        .set_max_height(2160);
    let mut decoder = JpegDecoder::new_with_options(jpeg, options);
    let mut rgb = decoder
        .decode()
        .map_err(|e| invalid(format!("JPEG: {e}")))?;
    let info = decoder
        .info()
        .ok_or_else(|| invalid("JPEG has no dimensions"))?;
    let (w, h) = (info.width as usize, info.height as usize);
    if w == 0 || h == 0 || rgb.len() != w * h * 3 {
        return Err(invalid("invalid decoded RGB frame"));
    }
    if mirror {
        for row in rgb.chunks_exact_mut(w * 3) {
            for x in 0..w / 2 {
                for c in 0..3 {
                    row.swap(x * 3 + c, (w - 1 - x) * 3 + c);
                }
            }
        }
    }
    Ok((rgb, w, h))
}
/// BT.601 limited-range YUYV; chroma is averaged over each pixel pair.
#[cfg(any(target_os = "linux", test))]
pub fn yuyv(rgb: &[u8], width: usize, height: usize, stride: usize) -> io::Result<Vec<u8>> {
    if width == 0
        || width & 1 != 0
        || height == 0
        || width > 4096
        || height > 2160
        || stride < width * 2
        || stride > 65536
        || rgb.len() != width * height * 3
    {
        return Err(invalid("YUYV requires even width and valid RGB/stride"));
    }
    let mut out = vec![0; stride * height];
    let yuv = |p: &[u8]| {
        let (r, g, b) = (p[0] as i32, p[1] as i32, p[2] as i32);
        (
            (66 * r + 129 * g + 25 * b + 128) / 256 + 16,
            (-38 * r - 74 * g + 112 * b + 128).div_euclid(256) + 128,
            (112 * r - 94 * g - 18 * b + 128).div_euclid(256) + 128,
        )
    };
    for (src, dst) in rgb
        .chunks_exact(width * 3)
        .zip(out.chunks_exact_mut(stride))
    {
        for x in 0..width / 2 {
            let (y0, u0, v0) = yuv(&src[x * 6..x * 6 + 3]);
            let (y1, u1, v1) = yuv(&src[x * 6 + 3..x * 6 + 6]);
            dst[x * 4..x * 4 + 4].copy_from_slice(&[
                y0.clamp(0, 255) as u8,
                ((u0 + u1) / 2).clamp(0, 255) as u8,
                y1.clamp(0, 255) as u8,
                ((v0 + v1) / 2).clamp(0, 255) as u8,
            ]);
        }
    }
    Ok(out)
}
#[cfg(target_os = "linux")]
pub struct Sink {
    path: String,
    file: std::fs::File,
    size: (usize, usize),
    stride: usize,
    image_size: usize,
}
#[cfg(target_os = "linux")]
impl Sink {
    pub fn describe(&self) -> String {
        format!("v4l2loopback {}", self.path)
    }
    pub fn new(options: &Options) -> io::Result<Self> {
        use std::{
            fs::OpenOptions,
            os::{fd::AsRawFd, unix::fs::OpenOptionsExt},
        };
        let paths = if let Some(p) = &options.device {
            vec![p.clone()]
        } else {
            (0..64).map(|n| format!("/dev/video{n}")).collect()
        };
        for path in paths {
            let Ok(file) = OpenOptions::new()
                .read(true)
                .write(true)
                .custom_flags(libc::O_NONBLOCK)
                .open(&path)
            else {
                continue;
            };
            let mut cap = [0u32; 26];
            if crate::platform::ioctl(file.as_raw_fd(), 0x80685600, &mut cap).is_ok() {
                let driver = unsafe { std::slice::from_raw_parts(cap.as_ptr().cast::<u8>(), 16) };
                if driver.starts_with(b"v4l2 loopback") {
                    return Ok(Self {
                        path,
                        file,
                        size: (0, 0),
                        stride: 0,
                        image_size: 0,
                    });
                }
            }
        }
        Err(invalid("No writable v4l2loopback device. Install v4l2loopback-dkms (Debian/Ubuntu) or v4l2loopback-dkms + matching kernel headers (Arch), then: sudo modprobe v4l2loopback exclusive_caps=1 card_label=\"Homedroid Camera\""))
    }
    pub fn write(&mut self, rgb: &[u8], w: usize, h: usize) -> io::Result<()> {
        use std::{io::Write, os::fd::AsRawFd};
        if self.size != (w, h) {
            // v4l2_format's union is 8-byte aligned on our 64-bit Linux targets.
            #[repr(C)]
            struct Format {
                kind: u32,
                pad: u32,
                data: [u32; 50],
            }
            let mut fmt = Format {
                kind: 2,
                pad: 0,
                data: [0; 50],
            };
            fmt.data[0] = w as u32;
            fmt.data[1] = h as u32;
            fmt.data[2] = u32::from_le_bytes(*b"YUYV");
            fmt.data[3] = 1;
            fmt.data[4] = (w * 2) as u32;
            fmt.data[5] = (w * h * 2) as u32;
            crate::platform::ioctl(self.file.as_raw_fd(), 0xc0d05605, &mut fmt)?;
            if fmt.data[0] != w as u32
                || fmt.data[1] != h as u32
                || fmt.data[2] != u32::from_le_bytes(*b"YUYV")
            {
                return Err(invalid("v4l2loopback rejected frame size / YUYV"));
            }
            self.stride = fmt.data[4] as usize;
            self.image_size = fmt.data[5] as usize;
            if self.image_size > 64 * 1024 * 1024 {
                return Err(invalid("invalid v4l2 buffer size"));
            }
            self.size = (w, h);
        }
        let mut data = yuyv(rgb, w, h, self.stride)?;
        data.resize(self.image_size.max(data.len()), 0);
        match self.file.write(&data) {
            Ok(n) if n == data.len() => Ok(()),
            Ok(_) => Err(invalid("short v4l2 frame write")),
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => Ok(()),
            Err(e) => Err(e),
        }
    }
}
#[cfg(windows)]
pub use crate::platform::CameraSink as Sink;
#[cfg(target_os = "macos")]
pub struct Sink;
#[cfg(target_os = "macos")]
impl Sink {
    pub fn describe(&self) -> String {
        String::new()
    }
    pub fn new(_: &Options) -> io::Result<Self> {
        Err(invalid("macOS: use http://127.0.0.1:8891/stream in OBS; native camera needs a signed system extension"))
    }
    pub fn write(&mut self, _: &[u8], _: usize, _: usize) -> io::Result<()> {
        Ok(())
    }
}
/// Width and height from the JPEG start-of-frame header, without decoding.
pub fn jpeg_size(j: &[u8]) -> Option<(usize, usize)> {
    let mut i = 2;
    if j.get(..2) != Some(&[0xff, 0xd8]) {
        return None;
    }
    while i + 4 <= j.len() {
        if j[i] != 0xff {
            i += 1;
            continue;
        }
        let m = j[i + 1];
        if m == 0xff {
            i += 1;
            continue;
        }
        if m == 0xd8 || m == 0x01 || (0xd0..=0xd7).contains(&m) {
            i += 2;
            continue;
        }
        let len = u16::from_be_bytes([j[i + 2], j[i + 3]]) as usize;
        if (0xc0..=0xcf).contains(&m) && !matches!(m, 0xc4 | 0xc8 | 0xcc) {
            let b = j.get(i + 5..i + 9)?;
            return Some((
                u16::from_be_bytes([b[2], b[3]]) as usize,
                u16::from_be_bytes([b[0], b[1]]) as usize,
            ));
        }
        i += 2 + len;
    }
    None
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn conversion() {
        assert_eq!(
            yuyv(&[0, 0, 0, 255, 255, 255], 2, 1, 4).unwrap(),
            [16, 128, 235, 128]
        );
        assert_eq!(
            yuyv(&[255, 0, 0, 255, 0, 0], 2, 1, 4).unwrap(),
            [82, 90, 82, 240]
        );
        assert_eq!(yuyv(&[0; 6], 2, 1, 8).unwrap().len(), 8);
        assert!(yuyv(&[0; 3], 1, 1, 2).is_err());
    }
    #[test]
    fn decode_fixture() {
        let jpeg = include_bytes!("../tests/test.jpg");
        assert_eq!(jpeg_size(jpeg), Some((320, 240)));
        let (rgb, w, h) = decode(jpeg, false).unwrap();
        assert_eq!((w, h), (320, 240));
        let px = |rgb: &[u8], x: usize, y: usize| -> [i32; 3] {
            let i = (y * w + x) * 3;
            [rgb[i] as i32, rgb[i + 1] as i32, rgb[i + 2] as i32]
        };
        let near = |a: [i32; 3], b: [i32; 3]| a.iter().zip(b).all(|(x, y)| (x - y).abs() < 30);
        assert!(near(px(&rgb, 40, 30), [255, 0, 0]));
        assert!(near(px(&rgb, 280, 30), [0, 255, 0]));
        let (mirrored, _, _) = decode(jpeg, true).unwrap();
        assert!(near(px(&mirrored, 40, 30), [0, 255, 0]));
        assert!(near(px(&mirrored, 280, 30), [255, 0, 0]));
        let out = yuyv(&rgb, w, h, w * 2).unwrap();
        assert_eq!(out.len(), w * h * 2);
        // Red top-left pixel pair: Y~82, V high.
        assert!((out[80] as i32 - 82).abs() < 12 && out[83] > 200);
    }

    #[test]
    fn jpeg_header() {
        let j = [
            0xff, 0xd8, 0xff, 0xe0, 0, 4, 0, 0, 0xff, 0xc0, 0, 11, 8, 2, 208, 5, 0, 3, 1, 0x22, 0,
        ];
        assert_eq!(jpeg_size(&j), Some((1280, 720)));
        assert_eq!(jpeg_size(b"nope"), None);
    }
    #[test]
    fn corrupt_jpeg() {
        assert!(decode(b"bad jpeg", false).is_err());
    }
}
