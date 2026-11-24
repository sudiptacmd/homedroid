//! Single-writer shared-memory frame slot between `homedroid-desktop.exe` (writer) and the
//! virtual-camera media source DLL (reader). Frames are always 1280x720 BGRA; the writer
//! scales and letterboxes. A sequence counter (odd while writing) lets the reader copy
//! without locks and drop torn frames.
use std::sync::atomic::{AtomicU64, Ordering};
use windows::{
    core::{w, PCWSTR},
    Win32::{
        Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE},
        System::Memory::{
            CreateFileMappingW, MapViewOfFile, OpenFileMappingW, UnmapViewOfFile,
            FILE_MAP_ALL_ACCESS, FILE_MAP_READ, MEMORY_MAPPED_VIEW_ADDRESS, PAGE_READWRITE,
        },
    },
};

pub const WIDTH: usize = 1280;
pub const HEIGHT: usize = 720;
pub const FRAME_BYTES: usize = WIDTH * HEIGHT * 4;
const HEADER: usize = 64;
const NAME: PCWSTR = w!("Local\\HomedroidVirtualCamera");

pub struct Ring {
    map: HANDLE,
    view: MEMORY_MAPPED_VIEW_ADDRESS,
    writer: bool,
}

// The mapping is plain memory guarded by the sequence counter.
unsafe impl Send for Ring {}
unsafe impl Sync for Ring {}

impl Ring {
    pub fn create() -> windows::core::Result<Self> {
        unsafe {
            let map = CreateFileMappingW(
                INVALID_HANDLE_VALUE,
                None,
                PAGE_READWRITE,
                0,
                (HEADER + FRAME_BYTES) as u32,
                NAME,
            )?;
            let view = MapViewOfFile(map, FILE_MAP_ALL_ACCESS, 0, 0, HEADER + FRAME_BYTES);
            if view.Value.is_null() {
                let e = windows::core::Error::from_win32();
                let _ = CloseHandle(map);
                return Err(e);
            }
            Ok(Self {
                map,
                view,
                writer: true,
            })
        }
    }

    pub fn open() -> windows::core::Result<Self> {
        unsafe {
            let map = OpenFileMappingW(FILE_MAP_READ.0, false, NAME)?;
            let view = MapViewOfFile(map, FILE_MAP_READ, 0, 0, HEADER + FRAME_BYTES);
            if view.Value.is_null() {
                let e = windows::core::Error::from_win32();
                let _ = CloseHandle(map);
                return Err(e);
            }
            Ok(Self {
                map,
                view,
                writer: false,
            })
        }
    }

    fn base(&self) -> *mut u8 {
        self.view.Value.cast()
    }

    fn seq(&self) -> &AtomicU64 {
        // The view is page aligned and the counter lives at offset 0.
        unsafe { &*self.base().cast::<AtomicU64>() }
    }

    /// Scales (nearest neighbour, aspect preserved, black bars) and publishes an RGB24 frame.
    pub fn publish(&self, rgb: &[u8], w: usize, h: usize) -> windows::core::Result<()> {
        if !self.writer || w == 0 || h == 0 || rgb.len() != w * h * 3 {
            return Err(windows::core::Error::from(
                windows::Win32::Foundation::E_INVALIDARG,
            ));
        }
        let start = self.seq().load(Ordering::Relaxed) | 1;
        self.seq().store(start, Ordering::SeqCst);
        let dst = unsafe { std::slice::from_raw_parts_mut(self.base().add(HEADER), FRAME_BYTES) };
        scale_into(rgb, w, h, dst);
        self.seq().store(start + 1, Ordering::SeqCst);
        Ok(())
    }

    /// Copies the newest frame into `out` (FRAME_BYTES long). Returns its sequence number,
    /// or None if nothing was published yet or the frame was overwritten mid-copy.
    pub fn read(&self, out: &mut [u8]) -> Option<u64> {
        if out.len() != FRAME_BYTES {
            return None;
        }
        let before = self.seq().load(Ordering::SeqCst);
        if before == 0 || before & 1 == 1 {
            return None;
        }
        unsafe {
            std::ptr::copy_nonoverlapping(self.base().add(HEADER), out.as_mut_ptr(), FRAME_BYTES)
        };
        (self.seq().load(Ordering::SeqCst) == before).then_some(before)
    }
}

impl Drop for Ring {
    fn drop(&mut self) {
        unsafe {
            let _ = UnmapViewOfFile(self.view);
            let _ = CloseHandle(self.map);
        }
    }
}

/// RGB24 -> BGRA at WIDTH x HEIGHT, fit inside with black bars.
pub fn scale_into(rgb: &[u8], w: usize, h: usize, dst: &mut [u8]) {
    dst.fill(0);
    let (ow, oh) = if w * HEIGHT >= h * WIDTH {
        (WIDTH, (h * WIDTH / w).max(1))
    } else {
        ((w * HEIGHT / h).max(1), HEIGHT)
    };
    let (x0, y0) = ((WIDTH - ow) / 2, (HEIGHT - oh) / 2);
    for y in 0..oh {
        let sy = y * h / oh;
        let src_row = &rgb[sy * w * 3..(sy + 1) * w * 3];
        let dst_row = &mut dst[((y0 + y) * WIDTH + x0) * 4..((y0 + y) * WIDTH + x0 + ow) * 4];
        for (x, d) in dst_row.chunks_exact_mut(4).enumerate() {
            let s = &src_row[(x * w / ow) * 3..][..3];
            d.copy_from_slice(&[s[2], s[1], s[0], 255]);
        }
    }
}
