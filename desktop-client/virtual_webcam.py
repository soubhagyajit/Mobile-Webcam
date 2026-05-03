import sys
import ctypes
import cv2

# Load DLL
softcam = ctypes.cdll.LoadLibrary('./softcam.dll')
softcam.scCreateCamera.restype = ctypes.c_void_p
softcam.scCreateCamera.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_float]
softcam.scSendFrame.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

def main():
    # Use detected resolution from your logs (960x720)
    width = int(sys.argv[1]) if len(sys.argv) > 1 else 960
    height = int(sys.argv[2]) if len(sys.argv) > 2 else 720
    fps = 30.0

    cam = softcam.scCreateCamera(width, height, fps)
    
    # Direct stream access
    cap = cv2.VideoCapture("http://localhost:8080/video")
    
    # PERFORMANCE: Minimal buffering for real-time
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    while True:
        ret, frame = cap.read()
        if not ret: break

        # If the stream is 960x720 but you want 1280x720, resize here
        # INTER_NEAREST is the fastest possible resize
        if frame.shape[1] != width or frame.shape[0] != height:
            frame = cv2.resize(frame, (width, height), interpolation=cv2.INTER_NEAREST)

        # .ctypes.data is the fastest way to pass memory to the DLL
        softcam.scSendFrame(cam, frame.ctypes.data)

    cap.release()

if __name__ == "__main__":
    main()