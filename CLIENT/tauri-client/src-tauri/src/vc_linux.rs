// vc_linux.rs
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;
use zune_jpeg::JpegDecoder;
use v4l::video::Output;
use v4l::{Device, FourCC};

static CAM_RUNNING: AtomicBool = AtomicBool::new(false);
static CAM_THREAD: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

// --- unchanged from vc_windows.rs ---

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

fn read_next_jpeg(
    reader: &mut impl Read,
    buffer: &mut Vec<u8>,
    chunk: &mut [u8],
) -> Option<Vec<u8>> {
    const HEADER_MARKER: [u8; 4] = [13, 10, 13, 10];

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

// --- new: decodes straight to RGB, no BGR swap needed for v4l2loopback's RGB3 format ---

fn decode_to_rgb(jpeg_bytes: &[u8], width: u32, height: u32, out: &mut [u8]) -> bool {
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
    out.copy_from_slice(&pixels);
    true
}

#[tauri::command]
pub fn init_cam(on: bool, height: u32, width: u32) {
    println!("Cam Init {}", on);
    if on {
        if CAM_RUNNING.load(Ordering::Relaxed) {
            return;
        }
        CAM_RUNNING.store(true, Ordering::Relaxed);
        let handle = thread::spawn(move || {
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
    println!("Cam loop started (linux)");

    // NOTE: path likely needs to be configurable/discovered rather than
    // hardcoded to video0 — see discussion below.
    let mut dev = Device::with_path("/dev/video0").expect("Failed to open v4l2loopback device");

    let mut fmt = dev.format().expect("Failed to read current format");
    fmt.width = width;
    fmt.height = height;
    fmt.fourcc = FourCC::new(b"RGB3");
    let fmt = dev.set_format(&fmt).expect("Failed to set format");
    println!("Linux vcam format in use:\n{}", fmt);

    let latest_jpeg: Arc<Mutex<Option<Vec<u8>>>> = Arc::new(Mutex::new(None));
    let latest_jpeg_reader = Arc::clone(&latest_jpeg);

    thread::spawn(move || {
        let mut reader = reqwest::blocking::get("http://localhost:8080/video")
            .expect("failed to connect to stream");
        let mut buffer: Vec<u8> = Vec::new();
        let mut chunk = [0u8; 16384];

        while CAM_RUNNING.load(Ordering::Relaxed) {
            let Some(jpeg) = read_next_jpeg(&mut reader, &mut buffer, &mut chunk) else {
                continue;
            };
            *latest_jpeg_reader.lock().unwrap() = Some(jpeg);
        }
    });

    let mut rgb_frame = vec![0u8; (width * height * 3) as usize];

    while CAM_RUNNING.load(Ordering::Relaxed) {
        let jpeg = latest_jpeg.lock().unwrap().take();
        let Some(jpeg) = jpeg else {
            thread::sleep(Duration::from_millis(5));
            continue;
        };

        if !decode_to_rgb(&jpeg, width, height, &mut rgb_frame) {
            continue;
        }

        // this is the part I'm least certain compiles as-is — see note below
        if let Err(e) = dev.write_all(&rgb_frame) {
            eprintln!("Failed writing frame to v4l2loopback: {:?}", e);
        }
    }
}