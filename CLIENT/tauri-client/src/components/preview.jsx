import { useEffect, useRef } from "react";
import { listen } from "@tauri-apps/api/event";

export default function Preview({ isConnected }) {
  const videoRef = useRef(null);

  useEffect(() => {
    let unlisten;

    const setupListener = async () => {
      // Listen for the frame updates emitted from sender.rs
      unlisten = await listen("frame-update", (event) => {
        if (videoRef.current && isConnected) {
          // Update the src directly using the base64 payload
          videoRef.current.src = `data:image/jpeg;base64,${event.payload}`;
        }
      });
    };

    if (isConnected) {
      setupListener();
    } else {
      // Clear the image when disconnected
      if (videoRef.current) {
        videoRef.current.src = "";
      }
    }

    // Cleanup the listener when the component unmounts or connection drops
    return () => {
      if (unlisten) {
        unlisten();
      }
    };
  }, [isConnected]);

  return (
    <div
      id="videoStreamDiv"
      className={`${
        isConnected ? "block" : "hidden"
      } relative w-full h-full flex items-center justify-center`}
    >
      <img
        ref={videoRef}
        id="videoStream"
        className="max-w-full max-h-full rounded-2xl border border-slate-700/50 video-glow object-contain transition-all duration-500"
        alt="Stream"
      />
    </div>
  );
}