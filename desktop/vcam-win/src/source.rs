//! IMFMediaSourceEx + IMFMediaStream2 implementation, the class factory and the DLL exports.
use crate::ring::{Ring, FRAME_BYTES, HEIGHT, WIDTH};
use std::{
    ffi::c_void,
    sync::{
        atomic::{AtomicBool, AtomicU32, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
use windows::{
    core::{implement, ComObject, IUnknown, Interface, Result, GUID, HRESULT, PCWSTR, PROPVARIANT},
    Win32::{
        Foundation::{
            BOOL, CLASS_E_NOAGGREGATION, E_FAIL, E_NOINTERFACE, E_NOTIMPL, E_POINTER, HINSTANCE,
            HMODULE, MAX_PATH, S_FALSE, S_OK,
        },
        Media::MediaFoundation::*,
        System::{
            Com::{IClassFactory, IClassFactory_Impl},
            LibraryLoader::GetModuleFileNameW,
            Registry::{
                RegCloseKey, RegCreateKeyExW, RegDeleteTreeW, RegSetValueExW, HKEY,
                HKEY_LOCAL_MACHINE, KEY_WRITE, REG_OPTION_NON_VOLATILE, REG_SZ,
            },
        },
    },
};

/// {6F3A82F0-5436-4C16-A591-888B064632A1}
pub const CLSID_HOMEDROID_VCAM: GUID = GUID::from_u128(0x6F3A82F0_5436_4C16_A591_888B064632A1);
const CLSID_KEY: &str = "SOFTWARE\\Classes\\CLSID\\{6F3A82F0-5436-4C16-A591-888B064632A1}";
const FPS: u64 = 30;
const FRAME_DURATION: i64 = 10_000_000 / FPS as i64;
/// PINNAME_VIDEO_CAPTURE
const PIN_VIDEO_CAPTURE: GUID = GUID::from_u128(0xfb6c4281_0353_11d1_905f_0000c0cc16ba);

static MODULE: Mutex<isize> = Mutex::new(0);

fn pack(hi: u32, lo: u32) -> u64 {
    (u64::from(hi) << 32) | u64::from(lo)
}

struct SendIface<T>(T);
unsafe impl<T> Send for SendIface<T> {}
unsafe impl<T> Sync for SendIface<T> {}

fn media_type() -> Result<IMFMediaType> {
    unsafe {
        let t = MFCreateMediaType()?;
        t.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        t.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)?;
        t.SetUINT64(&MF_MT_FRAME_SIZE, pack(WIDTH as u32, HEIGHT as u32))?;
        t.SetUINT64(&MF_MT_FRAME_RATE, pack(FPS as u32, 1))?;
        t.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, pack(1, 1))?;
        t.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        t.SetUINT32(&MF_MT_ALL_SAMPLES_INDEPENDENT, 1)?;
        t.SetUINT32(&MF_MT_DEFAULT_STRIDE, (WIDTH * 4) as u32)?;
        t.SetUINT32(&MF_MT_SAMPLE_SIZE, FRAME_BYTES as u32)?;
        Ok(t)
    }
}

// ---------------------------------------------------------------- stream

struct Shared {
    queue: SendIface<IMFMediaEventQueue>,
    running: AtomicBool,
    shutdown: AtomicBool,
    /// Outstanding RequestSample calls.
    wanted: AtomicU32,
}

#[implement(IMFMediaStream2)]
struct Stream {
    shared: Arc<Shared>,
    source: SendIface<IMFMediaSource>,
    descriptor: SendIface<IMFStreamDescriptor>,
}

impl Stream {
    fn spawn_pacer(shared: Arc<Shared>) {
        std::thread::spawn(move || {
            let ring = Ring::open().ok();
            let mut ring = ring;
            let mut frame = vec![0u8; FRAME_BYTES];
            let begin = Instant::now();
            let mut next = Instant::now();
            while !shared.shutdown.load(Ordering::SeqCst) {
                next += Duration::from_nanos(1_000_000_000 / FPS);
                let now = Instant::now();
                if next > now {
                    std::thread::sleep(next - now);
                } else {
                    next = now;
                }
                if !shared.running.load(Ordering::SeqCst)
                    || shared.wanted.load(Ordering::SeqCst) == 0
                {
                    continue;
                }
                if ring.is_none() {
                    ring = Ring::open().ok();
                }
                if ring.as_ref().and_then(|r| r.read(&mut frame)).is_none() && ring.is_none() {
                    frame.fill(0);
                }
                shared.wanted.fetch_sub(1, Ordering::SeqCst);
                let time = begin.elapsed().as_nanos() as i64 / 100;
                let _ = unsafe { queue_sample(&shared.queue.0, &frame, time) };
            }
        });
    }
}

unsafe fn queue_sample(queue: &IMFMediaEventQueue, frame: &[u8], time: i64) -> Result<()> {
    let sample = MFCreateSample()?;
    let buffer = MFCreateMemoryBuffer(FRAME_BYTES as u32)?;
    let mut ptr = std::ptr::null_mut();
    buffer.Lock(&mut ptr, None, None)?;
    std::ptr::copy_nonoverlapping(frame.as_ptr(), ptr, FRAME_BYTES);
    buffer.Unlock()?;
    buffer.SetCurrentLength(FRAME_BYTES as u32)?;
    sample.AddBuffer(&buffer)?;
    sample.SetSampleTime(time)?;
    sample.SetSampleDuration(FRAME_DURATION)?;
    queue.QueueEventParamUnk(MEMediaSample.0 as u32, &GUID::zeroed(), S_OK, &sample)
}

impl IMFMediaEventGenerator_Impl for Stream_Impl {
    fn GetEvent(&self, flags: MEDIA_EVENT_GENERATOR_GET_EVENT_FLAGS) -> Result<IMFMediaEvent> {
        unsafe { self.shared.queue.0.GetEvent(flags.0) }
    }
    fn BeginGetEvent(
        &self,
        callback: Option<&IMFAsyncCallback>,
        state: Option<&IUnknown>,
    ) -> Result<()> {
        unsafe { self.shared.queue.0.BeginGetEvent(callback, state) }
    }
    fn EndGetEvent(&self, result: Option<&IMFAsyncResult>) -> Result<IMFMediaEvent> {
        unsafe { self.shared.queue.0.EndGetEvent(result) }
    }
    fn QueueEvent(
        &self,
        met: u32,
        ext: *const GUID,
        status: HRESULT,
        value: *const PROPVARIANT,
    ) -> Result<()> {
        unsafe {
            self.shared
                .queue
                .0
                .QueueEventParamVar(met, ext, status, value)
        }
    }
}

impl IMFMediaStream_Impl for Stream_Impl {
    fn GetMediaSource(&self) -> Result<IMFMediaSource> {
        Ok(self.source.0.clone())
    }
    fn GetStreamDescriptor(&self) -> Result<IMFStreamDescriptor> {
        Ok(self.descriptor.0.clone())
    }
    fn RequestSample(&self, _token: Option<&IUnknown>) -> Result<()> {
        self.shared.wanted.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
}

impl IMFMediaStream2_Impl for Stream_Impl {
    fn SetStreamState(&self, _value: MF_STREAM_STATE) -> Result<()> {
        Ok(())
    }
    fn GetStreamState(&self) -> Result<MF_STREAM_STATE> {
        Ok(if self.shared.running.load(Ordering::SeqCst) {
            MF_STREAM_STATE_RUNNING
        } else {
            MF_STREAM_STATE_STOPPED
        })
    }
}

// ---------------------------------------------------------------- source

#[implement(IMFMediaSourceEx, IMFGetService)]
struct Source {
    queue: IMFMediaEventQueue,
    stream_shared: Arc<Shared>,
    stream: Mutex<Option<IMFMediaStream2>>,
    descriptor: IMFStreamDescriptor,
    announced: AtomicBool,
}

impl Source {
    fn create() -> Result<IMFMediaSourceEx> {
        unsafe {
            let mt = media_type()?;
            let descriptor = MFCreateStreamDescriptor(0, &[Some(mt.clone())])?;
            descriptor.GetMediaTypeHandler()?.SetCurrentMediaType(&mt)?;
            let stream_shared = Arc::new(Shared {
                queue: SendIface(MFCreateEventQueue()?),
                running: AtomicBool::new(false),
                shutdown: AtomicBool::new(false),
                wanted: AtomicU32::new(0),
            });
            let source = ComObject::new(Source {
                queue: MFCreateEventQueue()?,
                stream_shared: stream_shared.clone(),
                stream: Mutex::new(None),
                descriptor: descriptor.clone(),
                announced: AtomicBool::new(false),
            });
            let iface: IMFMediaSourceEx = source.to_interface();
            let stream: IMFMediaStream2 = Stream {
                shared: stream_shared.clone(),
                source: SendIface(iface.cast()?),
                descriptor: SendIface(descriptor),
            }
            .into();
            *source
                .stream
                .lock()
                .map_err(|_| windows::core::Error::from(E_FAIL))? = Some(stream);
            Stream::spawn_pacer(stream_shared);
            Ok(iface)
        }
    }
}

impl IMFMediaEventGenerator_Impl for Source_Impl {
    fn GetEvent(&self, flags: MEDIA_EVENT_GENERATOR_GET_EVENT_FLAGS) -> Result<IMFMediaEvent> {
        unsafe { self.queue.GetEvent(flags.0) }
    }
    fn BeginGetEvent(
        &self,
        callback: Option<&IMFAsyncCallback>,
        state: Option<&IUnknown>,
    ) -> Result<()> {
        unsafe { self.queue.BeginGetEvent(callback, state) }
    }
    fn EndGetEvent(&self, result: Option<&IMFAsyncResult>) -> Result<IMFMediaEvent> {
        unsafe { self.queue.EndGetEvent(result) }
    }
    fn QueueEvent(
        &self,
        met: u32,
        ext: *const GUID,
        status: HRESULT,
        value: *const PROPVARIANT,
    ) -> Result<()> {
        unsafe { self.queue.QueueEventParamVar(met, ext, status, value) }
    }
}

impl IMFMediaSource_Impl for Source_Impl {
    fn GetCharacteristics(&self) -> Result<u32> {
        Ok(MFMEDIASOURCE_IS_LIVE.0 as u32)
    }
    fn CreatePresentationDescriptor(&self) -> Result<IMFPresentationDescriptor> {
        unsafe {
            let pd = MFCreatePresentationDescriptor(Some(&[Some(self.descriptor.clone())]))?;
            pd.SelectStream(0)?;
            Ok(pd)
        }
    }
    fn Start(
        &self,
        _pd: Option<&IMFPresentationDescriptor>,
        _time_format: *const GUID,
        start: *const PROPVARIANT,
    ) -> Result<()> {
        unsafe {
            let stream = self
                .stream
                .lock()
                .map_err(|_| windows::core::Error::from(E_FAIL))?
                .clone()
                .ok_or_else(|| windows::core::Error::from(E_FAIL))?;
            let event = if self.announced.swap(true, Ordering::SeqCst) {
                MEUpdatedStream
            } else {
                MENewStream
            };
            self.queue
                .QueueEventParamUnk(event.0 as u32, &GUID::zeroed(), S_OK, &stream)?;
            self.stream_shared.running.store(true, Ordering::SeqCst);
            self.stream_shared.queue.0.QueueEventParamVar(
                MEStreamStarted.0 as u32,
                &GUID::zeroed(),
                S_OK,
                std::ptr::null(),
            )?;
            self.queue
                .QueueEventParamVar(MESourceStarted.0 as u32, &GUID::zeroed(), S_OK, start)
        }
    }
    fn Stop(&self) -> Result<()> {
        unsafe {
            self.stream_shared.running.store(false, Ordering::SeqCst);
            self.stream_shared.queue.0.QueueEventParamVar(
                MEStreamStopped.0 as u32,
                &GUID::zeroed(),
                S_OK,
                std::ptr::null(),
            )?;
            self.queue.QueueEventParamVar(
                MESourceStopped.0 as u32,
                &GUID::zeroed(),
                S_OK,
                std::ptr::null(),
            )
        }
    }
    fn Pause(&self) -> Result<()> {
        Err(MF_E_INVALID_STATE_TRANSITION.into())
    }
    fn Shutdown(&self) -> Result<()> {
        unsafe {
            self.stream_shared.shutdown.store(true, Ordering::SeqCst);
            self.stream_shared.running.store(false, Ordering::SeqCst);
            let _ = self.stream_shared.queue.0.Shutdown();
            self.queue.Shutdown()
        }
    }
}

impl IMFMediaSourceEx_Impl for Source_Impl {
    fn GetSourceAttributes(&self) -> Result<IMFAttributes> {
        unsafe {
            let mut a = None;
            MFCreateAttributes(&mut a, 2)?;
            a.ok_or_else(|| windows::core::Error::from(E_FAIL))
        }
    }
    fn GetStreamAttributes(&self, _id: u32) -> Result<IMFAttributes> {
        unsafe {
            let mut a = None;
            MFCreateAttributes(&mut a, 3)?;
            let a = a.ok_or_else(|| windows::core::Error::from(E_FAIL))?;
            a.SetGUID(&MF_DEVICESTREAM_STREAM_CATEGORY, &PIN_VIDEO_CAPTURE)?;
            a.SetUINT32(&MF_DEVICESTREAM_STREAM_ID, 0)?;
            a.SetUINT32(&MF_DEVICESTREAM_FRAMESERVER_SHARED, 1)?;
            Ok(a)
        }
    }
    fn SetD3DManager(&self, _manager: Option<&IUnknown>) -> Result<()> {
        Ok(())
    }
}

impl IMFGetService_Impl for Source_Impl {
    fn GetService(
        &self,
        _service: *const GUID,
        _riid: *const GUID,
        _out: *mut *mut c_void,
    ) -> Result<()> {
        Err(E_NOTIMPL.into())
    }
}

// ---------------------------------------------------------------- COM exports

#[implement(IClassFactory)]
struct Factory;

impl IClassFactory_Impl for Factory_Impl {
    fn CreateInstance(
        &self,
        outer: Option<&IUnknown>,
        riid: *const GUID,
        out: *mut *mut c_void,
    ) -> Result<()> {
        if out.is_null() || riid.is_null() {
            return Err(E_POINTER.into());
        }
        if outer.is_some() {
            return Err(CLASS_E_NOAGGREGATION.into());
        }
        let source = Source::create()?;
        unsafe { source.query(riid, out).ok() }
    }
    fn LockServer(&self, _lock: BOOL) -> Result<()> {
        Ok(())
    }
}

#[no_mangle]
pub extern "system" fn DllMain(module: HINSTANCE, reason: u32, _reserved: *mut c_void) -> BOOL {
    if reason == 1 {
        if let Ok(mut m) = MODULE.lock() {
            *m = module.0 as isize;
        }
    }
    BOOL(1)
}

#[no_mangle]
pub extern "system" fn DllCanUnloadNow() -> HRESULT {
    S_FALSE
}

#[no_mangle]
pub unsafe extern "system" fn DllGetClassObject(
    clsid: *const GUID,
    riid: *const GUID,
    out: *mut *mut c_void,
) -> HRESULT {
    if clsid.is_null() || riid.is_null() || out.is_null() {
        return E_POINTER;
    }
    if *clsid != CLSID_HOMEDROID_VCAM {
        return E_NOINTERFACE;
    }
    let factory: IClassFactory = Factory.into();
    factory.query(riid, out)
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

unsafe fn set_string(key: HKEY, name: Option<&str>, value: &str) -> Result<()> {
    let value = wide(value);
    let bytes = std::slice::from_raw_parts(value.as_ptr().cast::<u8>(), value.len() * 2);
    let name = name.map(wide);
    RegSetValueExW(
        key,
        name.as_ref().map_or(PCWSTR::null(), |n| PCWSTR(n.as_ptr())),
        0,
        REG_SZ,
        Some(bytes),
    )
    .ok()
}

/// Registers the in-process COM server under HKLM (administrator).
#[no_mangle]
pub unsafe extern "system" fn DllRegisterServer() -> HRESULT {
    let module =
        HMODULE(*MODULE.lock().map(|m| m).unwrap_or_else(|e| e.into_inner()) as *mut c_void);
    let mut path = [0u16; MAX_PATH as usize];
    let n = GetModuleFileNameW(module, &mut path) as usize;
    if n == 0 {
        return E_FAIL;
    }
    let dll = String::from_utf16_lossy(&path[..n]);
    let result = (|| -> Result<()> {
        let mut key = HKEY::default();
        RegCreateKeyExW(
            HKEY_LOCAL_MACHINE,
            PCWSTR(wide(&format!("{CLSID_KEY}\\InprocServer32")).as_ptr()),
            0,
            PCWSTR::null(),
            REG_OPTION_NON_VOLATILE,
            KEY_WRITE,
            None,
            &mut key,
            None,
        )
        .ok()?;
        let r = set_string(key, None, &dll)
            .and_then(|_| set_string(key, Some("ThreadingModel"), "Both"));
        let _ = RegCloseKey(key);
        r
    })();
    match result {
        Ok(()) => S_OK,
        Err(e) => e.code(),
    }
}

#[no_mangle]
pub unsafe extern "system" fn DllUnregisterServer() -> HRESULT {
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, PCWSTR(wide(CLSID_KEY).as_ptr())).to_hresult()
}
