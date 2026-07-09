// virtual - cam file
use std::fs;
use std::io::Read;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;
use zune_jpeg::JpegDecoder;

static CAM_RUNNING: AtomicBool = AtomicBool::new(false);
static CAM_THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);
static mut FRAME_COUNTER: u32 = 0;

#[link(name = "softcam")]
unsafe extern "system" {
    fn scCreateCamera(width: i32, height: i32, frame_rate: f32) -> *mut c_void;
    fn scWaitForConnection(camera: *mut c_void, timeout_sec: f32) -> bool;
    fn scSendFrame(camera: *mut c_void, image_data: *const u8);
    fn scDeleteCamera(camera: *mut c_void) -> bool;
}

/// Finds the first occurrence of `marker` in `buffer`, if any.
fn find_marker(buffer: &[u8], marker: &[u8]) -> Option<usize> {
    if buffer.len() < marker.len() {
        return None;
    }
    for i in 0..=(buffer.len() - marker.len()) {
        if buffer[i..i + marker.len()] == *marker {
            return Some(i);
        }
    }
    None
}

/// Pulls exactly one complete MJPEG frame (JPEG bytes only) out of the stream,
/// consuming the corresponding bytes from `buffer`. Does NOT decode — kept
/// deliberately cheap so this can run in a tight loop that never blocks on CPU work.
fn read_next_jpeg(
    reader: &mut impl Read,
    buffer: &mut Vec<u8>,
    chunk: &mut [u8],
) -> Option<Vec<u8>> {
    const HEADER_MARKER: [u8; 4] = [13, 10, 13, 10]; // \r\n\r\n

    let header_end = loop {
        if let Some(pos) = find_marker(buffer, &HEADER_MARKER) {
            break pos;
        }
        let n = reader.read(chunk).ok()?;
        buffer.extend_from_slice(&chunk[..n]);
    };

    let header_text = String::from_utf8_lossy(&buffer[0..header_end]);
    let content_length: usize = header_text
        .lines()
        .find_map(|line| line.strip_prefix("Content-Length: "))
        .and_then(|v| v.parse().ok())?;

    let frame_len = header_end + 4 + content_length;
    while buffer.len() < frame_len {
        let n = reader.read(chunk).ok()?;
        buffer.extend_from_slice(&chunk[..n]);
    }

    let jpeg = buffer[(header_end + 4)..frame_len].to_vec();
    buffer.drain(..frame_len);
    Some(jpeg)
}

/// Decodes JPEG bytes and writes BGR pixels into `out`. No flip, no resize —
/// expects the source to already match `width`/`height` exactly.
fn decode_to_bgr(jpeg_bytes: &[u8], width: u32, height: u32, out: &mut [u8]) -> bool {
    let mut decoder = JpegDecoder::new(jpeg_bytes);
    let Ok(pixels) = decoder.decode() else {
        return false;
    };

    let Some((dec_w, dec_h)) = decoder.dimensions() else {
        return false;
    };
    if dec_w as u32 != width || dec_h as u32 != height {
        return false;
    }

    for (src, dst) in pixels.chunks_exact(3).zip(out.chunks_exact_mut(3)) {
        dst[0] = src[2]; // B
        dst[1] = src[1]; // G
        dst[2] = src[0]; // R
    }

    true
}

#[tauri::command]
pub fn init_cam(on: bool, height: u32, width: u32) {
    println!("Cam Init {}", on);
    if on {
        if CAM_RUNNING.load(Ordering::Relaxed) {
            return; // already running, do nothing
        }
        CAM_RUNNING.store(true, Ordering::Relaxed);
        let handle = std::thread::spawn(move || {
            start_cam(height, width);
        });
        *CAM_THREAD.lock().unwrap() = Some(handle);
    } else {
        CAM_RUNNING.store(false, Ordering::Relaxed);
        if let Some(handle) = CAM_THREAD.lock().unwrap().take() {
            handle.join().unwrap();
        }
    }
}

fn start_cam(height: u32, width: u32) {
    println!("Cam loop started");

    let cam = unsafe { scCreateCamera(width as i32, height as i32, 30.0) };
    unsafe { scWaitForConnection(cam, 30.0) };

    // shared "latest frame" slot — reader overwrites it continuously,
    // decoder grabs whatever's newest whenever it's ready. No queueing,
    // no backlog: this is what fixes the falling-behind problem.
    let latest_jpeg: Arc<Mutex<Option<Vec<u8>>>> = Arc::new(Mutex::new(None));
    let latest_jpeg_reader = Arc::clone(&latest_jpeg);

    // Reader thread: ONLY reads bytes + finds frame boundaries. Never decodes,
    // so it can drain the socket as fast as the network delivers data,
    // regardless of how long decoding takes elsewhere.
    thread::spawn(move || {
        let mut reader = reqwest::blocking::get("http://localhost:8080/video")
            .expect("failed to connect to stream");
        let mut buffer: Vec<u8> = Vec::new();
        let mut chunk = [0u8; 16384];

        while CAM_RUNNING.load(Ordering::Relaxed) {
            let Some(jpeg) = read_next_jpeg(&mut reader, &mut buffer, &mut chunk) else {
                continue;
            };
            // overwrite, don't queue — always keep only the newest frame
            *latest_jpeg_reader.lock().unwrap() = Some(jpeg);
        }
    });

    let mut bgr_frame = vec![0u8; (width * height * 3) as usize];

    // Decoder/sender loop: grabs whatever's newest, decodes, sends.
    // Runs at its own pace — never wades through backlog.
    while CAM_RUNNING.load(Ordering::Relaxed) {
        let jpeg = latest_jpeg.lock().unwrap().take(); // take() empties the slot too
        let Some(jpeg) = jpeg else {
            thread::sleep(Duration::from_millis(5));
            continue;
        };

        let decode_start = std::time::Instant::now();
        if !decode_to_bgr(&jpeg, width, height, &mut bgr_frame) {
            continue;
        }
        println!("Decode took: {:?}", decode_start.elapsed());

        let send_start = std::time::Instant::now();
        unsafe { scSendFrame(cam, bgr_frame.as_ptr()) };
        println!("scSendFrame took: {:?}", send_start.elapsed());
    }

    unsafe { scDeleteCamera(cam) };
}