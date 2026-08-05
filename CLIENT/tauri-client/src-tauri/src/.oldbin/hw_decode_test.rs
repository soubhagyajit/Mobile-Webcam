// Standalone hardware-decode probe with automatic software fallback.
// Tries hw first; if the decoder rejects the actual bitstream (as on the
// SM-J6), reconnects fresh and retries in pure software mode.

use ffmpeg_next as ff;
use ffmpeg_sys_next as ffi;
use std::ptr;

static mut HW_PIX_FMT: ffi::AVPixelFormat = ffi::AVPixelFormat::AV_PIX_FMT_NONE;

unsafe extern "C" fn get_hw_format(
    _ctx: *mut ffi::AVCodecContext,
    pix_fmts: *const ffi::AVPixelFormat,
) -> ffi::AVPixelFormat {
    let mut p = pix_fmts;
    unsafe {
        while *p != ffi::AVPixelFormat::AV_PIX_FMT_NONE {
            if *p == HW_PIX_FMT {
                return *p;
            }
            p = p.add(1);
        }
    }
    eprintln!("hw_decode_test: decoder did not offer our hw pixel format");
    ffi::AVPixelFormat::AV_PIX_FMT_NONE
}

/// Attempts to attach a hw device context. Returns true only if the
/// lookup + device creation succeeded — NOT a guarantee decode will
/// actually work once real frames arrive (that's the J6 case).
unsafe fn try_attach_hw(
    decoder: &mut ff::decoder::Video,
    decoder_codec: &ff::Codec,
    hwaccel_type: ffi::AVHWDeviceType,
) -> bool {
    let mut i = 0;
    let mut matched_fmt: Option<ffi::AVPixelFormat> = None;
    loop {
        let config = ffi::avcodec_get_hw_config(decoder_codec.as_ptr(), i);
        if config.is_null() {
            break;
        }
        let cfg = *config;
        if (cfg.methods & ffi::AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX as i32) != 0
            && cfg.device_type == hwaccel_type
        {
            matched_fmt = Some(cfg.pix_fmt);
            break;
        }
        i += 1;
    }

    let Some(fmt) = matched_fmt else {
        return false;
    };

    let mut hw_device_ctx: *mut ffi::AVBufferRef = ptr::null_mut();
    let ret =
        ffi::av_hwdevice_ctx_create(&mut hw_device_ctx, hwaccel_type, ptr::null(), ptr::null_mut(), 0);
    if ret < 0 {
        return false;
    }

    HW_PIX_FMT = fmt;
    let ctx_ptr = decoder.as_mut_ptr();
    (*ctx_ptr).hw_device_ctx = ffi::av_buffer_ref(hw_device_ctx);
    (*ctx_ptr).get_format = Some(get_hw_format);
    true
}

/// Opens a fresh RTSP connection + decoder. `use_hw = false` forces pure
/// software — this is what we retry with once hw decode has failed once.
fn open_stream(
    url: &str,
    use_hw: bool,
    hwaccel_type: ffi::AVHWDeviceType,
) -> Result<(ff::format::context::Input, usize, ff::decoder::Video), ff::Error> {
    let mut opts = ff::Dictionary::new();
    opts.set("rtsp_transport", "tcp");

    let ictx = ff::format::input_with_dictionary(url, opts)?;

    let video_stream = ictx
        .streams()
        .best(ff::media::Type::Video)
        .expect("no video stream in RTSP source");
    let video_stream_index = video_stream.index();
    let codec_params = video_stream.parameters();

    let decoder_codec =
        ff::decoder::find(codec_params.id()).expect("no H.264 decoder available");

    let context = ff::codec::context::Context::from_parameters(codec_params)?;
    let mut decoder = context.decoder().video()?;

    if use_hw {
        let attached = unsafe { try_attach_hw(&mut decoder, &decoder_codec, hwaccel_type) };
        if !attached {
            println!("hw_decode_test: hw attach failed outright, using software this run");
        }
    }

    Ok((ictx, video_stream_index, decoder))
}

fn main() {
    ff::init().expect("ffmpeg init failed");

    let hwaccel_type = ffi::AVHWDeviceType::AV_HWDEVICE_TYPE_D3D11VA;
    let url = "rtsp://192.168.31.12:8554/awa";

    let mut use_hw = true;
    let mut sws_ctx: Option<ff::software::scaling::Context> = None;
    let mut frame_count = 0;

    loop {
        let (mut ictx, video_stream_index, mut decoder) =
            open_stream(url, use_hw, hwaccel_type).expect("failed to open RTSP stream");

        println!(
            "hw_decode_test: attempting {} decode",
            if use_hw { "hardware" } else { "software" }
        );

        let mut hw_failed = false;

        for (stream, packet) in ictx.packets() {
            if stream.index() != video_stream_index {
                continue;
            }

            if decoder.send_packet(&packet).is_err() {
                if use_hw {
                    eprintln!("hw_decode_test: decode rejected under hardware mode — falling back");
                    hw_failed = true;
                    break;
                } else {
                    panic!("send_packet failed in software mode too — not a hw issue");
                }
            }

            let mut decoded_frame = ff::util::frame::Video::empty();
            while decoder.receive_frame(&mut decoded_frame).is_ok() {
                frame_count += 1;

                let mut sw_frame = ff::util::frame::Video::empty();
                let source_frame: &ff::util::frame::Video = if use_hw {
                    let ok = unsafe {
                        ffi::av_hwframe_transfer_data(
                            sw_frame.as_mut_ptr(),
                            decoded_frame.as_ptr(),
                            0,
                        ) >= 0
                    };
                    if !ok {
                        eprintln!("frame #{frame_count}: hwframe transfer failed, skipping");
                        continue;
                    }
                    &sw_frame
                } else {
                    &decoded_frame
                };

                let (width, height) = (source_frame.width(), source_frame.height());

                if sws_ctx.is_none() {
                    sws_ctx = Some(
                        ff::software::scaling::Context::get(
                            source_frame.format(),
                            width,
                            height,
                            ff::format::Pixel::BGR24,
                            width,
                            height,
                            ff::software::scaling::Flags::BILINEAR,
                        )
                        .expect("failed to build sws scaling context"),
                    );
                }

                let mut bgr_frame = ff::util::frame::Video::empty();
                sws_ctx
                    .as_mut()
                    .unwrap()
                    .run(source_frame, &mut bgr_frame)
                    .expect("sws_scale failed");

                println!(
                    "frame #{frame_count}: {width}x{height}, {} BGR bytes ({})",
                    bgr_frame.data(0).len(),
                    if use_hw { "hw" } else { "sw" }
                );

                if frame_count >= 60 {
                    println!("hw decode test: 60 frames OK, stopping");
                    return;
                }
            }
        }

        if hw_failed {
            use_hw = false;
            sws_ctx = None; // pixel format differs between a transferred hw frame and a native sw frame
            continue;
        } else {
            break;
        }
    }
}