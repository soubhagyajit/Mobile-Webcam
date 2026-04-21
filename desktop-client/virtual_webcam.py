#!/usr/bin/env python3
"""
Virtual Webcam Bridge - ZERO LAG version
Uses direct MJPEG parsing without cv2.VideoCapture buffering
"""

import cv2
import pyvirtualcam
import numpy as np
import urllib.request
import sys
import signal
import time
from threading import Thread, Lock

class MJPEGReader:
    """Direct MJPEG reader without buffering - eliminates lag"""
    
    def __init__(self, url):
        self.url = url
        self.latest_frame = None
        self.frame_lock = Lock()
        self.running = False
        self.thread = None
        
    def start(self):
        """Start reading frames in background thread"""
        self.running = True
        self.thread = Thread(target=self._read_stream, daemon=True)
        self.thread.start()
        
        # Wait for first frame
        for _ in range(50):  # 5 second timeout
            if self.latest_frame is not None:
                return True
            time.sleep(0.1)
        return False
    
    def _read_stream(self):
        """Read MJPEG stream continuously, keeping only latest frame"""
        while self.running:
            try:
                # Open stream
                stream = urllib.request.urlopen(self.url, timeout=10)
                bytes_buffer = b''
                
                while self.running:
                    # Read chunk
                    chunk = stream.read(4096)
                    if not chunk:
                        break
                    
                    bytes_buffer += chunk
                    
                    # Find JPEG boundaries
                    a = bytes_buffer.find(b'\xff\xd8')  # JPEG start
                    b = bytes_buffer.find(b'\xff\xd9')  # JPEG end
                    
                    if a != -1 and b != -1:
                        jpg = bytes_buffer[a:b+2]
                        bytes_buffer = bytes_buffer[b+2:]
                        
                        # Decode JPEG
                        frame = cv2.imdecode(
                            np.frombuffer(jpg, dtype=np.uint8), 
                            cv2.IMREAD_COLOR
                        )
                        
                        if frame is not None:
                            # Keep ONLY the latest frame (discard old ones)
                            with self.frame_lock:
                                self.latest_frame = frame
                
                stream.close()
                
            except Exception as e:
                print(f"[ER] Stream error: {e}", file=sys.stderr, flush=True)
                time.sleep(1)  # Wait before reconnecting
    
    def read(self):
        """Get the latest frame (non-blocking)"""
        with self.frame_lock:
            return self.latest_frame
    
    def stop(self):
        """Stop reading"""
        self.running = False
        if self.thread:
            self.thread.join(timeout=2)


class VirtualWebcam:
    def __init__(self, stream_url, target_fps=30):
        self.stream_url = stream_url
        self.target_fps = target_fps
        self.running = False
        self.reader = None
        
    def start(self):
        print("Starting virtual webcam (zero-lag mode)...", flush=True)
        
        # Start MJPEG reader
        self.reader = MJPEGReader(self.stream_url)
        
        if not self.reader.start():
            print("[ER] Failed to connect to stream", file=sys.stderr, flush=True)
            return False
        
        # Get first frame to detect resolution
        frame = self.reader.read()
        if frame is None:
            print("[ER] No frame received", file=sys.stderr, flush=True)
            return False
        
        height, width = frame.shape[:2]
        print(f"[OK] Connected to stream", flush=True)
        print(f"[OK] Resolution: {width}x{height}", flush=True)
        
        try:
            with pyvirtualcam.Camera(
                width=width,
                height=height,
                fps=self.target_fps
            ) as cam:
                
                print(f"[OK] Virtual camera: {cam.device}", flush=True)
                print(f"[OK] FPS: {self.target_fps}", flush=True)
                print("[OK] Ready! Use in Zoom/Teams/Discord", flush=True)
                
                self.running = True
                frame_count = 0
                last_frame = None
                
                while self.running:
                    # Get LATEST frame only (no buffering!)
                    frame = self.reader.read()
                    
                    if frame is not None:
                        # Resize if needed
                        if frame.shape[0] != height or frame.shape[1] != width:
                            frame = cv2.resize(frame, (width, height))
                        
                        # Convert BGR → RGB
                        frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                        
                        # Send to virtual camera
                        cam.send(frame_rgb)
                        last_frame = frame_rgb
                        
                        frame_count += 1
                        if frame_count % 30 == 0:
                            print(f"Frames: {frame_count}", flush=True)
                    
                    elif last_frame is not None:
                        # No new frame, repeat last frame
                        cam.send(last_frame)
                    
                    # Sleep to maintain target FPS
                    cam.sleep_until_next_frame()
                    
        except KeyboardInterrupt:
            print("\n[OK] Stopped by user", flush=True)
        except Exception as e:
            print(f"[ER] Error: {e}", file=sys.stderr, flush=True)
            import traceback
            traceback.print_exc()
            return False
        finally:
            self.running = False
            if self.reader:
                self.reader.stop()
        
        return True
    
    def stop(self):
        self.running = False
        if self.reader:
            self.reader.stop()


def signal_handler(sig, frame):
    print("\n[OK] Shutting down...", flush=True)
    sys.exit(0)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python virtual_webcam.py <stream_url> [fps]")
        print("Example: python virtual_webcam.py http://localhost:8080/video 30")
        sys.exit(1)
    
    stream_url = sys.argv[1]
    fps = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    print("=" * 50)
    print("  Virtual Webcam - Zero Lag Edition")
    print("=" * 50)
    
    vcam = VirtualWebcam(stream_url, fps)
    success = vcam.start()
    
    sys.exit(0 if success else 1)