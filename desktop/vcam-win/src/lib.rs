//! Homedroid virtual camera: an in-process COM media source for `MFCreateVirtualCamera`
//! (Windows 11). Experimental. It plays whatever `homedroid-desktop.exe` publishes into the
//! shared-memory ring.
#![cfg(windows)]
#![allow(non_snake_case)]

pub mod ring;
mod source;
