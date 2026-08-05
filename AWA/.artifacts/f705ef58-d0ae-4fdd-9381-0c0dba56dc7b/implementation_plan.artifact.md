# Implementation Plan - Fix build errors in RTSP-Server

The project currently fails to build because the `RTSP-Server` library is out of sync with the `RootEncoder` library it depends on. This occurred after the project was configured to use local versions of these libraries via `includeBuild`, revealing breaking changes in the latest `RootEncoder` source.

## Proposed Changes

### RTSP-Server Library

#### [MODIFY] [ServerClient.kt](file:///E:/Project%20Files/Others/Android%20Webcam%20Project/RootEncoder/RTSP-Server/rtspserver/src/main/java/com/pedro/rtspserver/server/ServerClient.kt)

Update `sendVideoFrame` and `sendAudioFrame` to match the new `sendMediaFrame` signature in `BaseSender`. The new signature takes a `ByteBuffer` and `MediaFrame.Info` directly, rather than a `MediaFrame` object. `BaseSender` now handles buffer cloning and recycling internally.

#### [MODIFY] [ServerCommandManager.kt](file:///E:/Project%20Files/Others/Android%20Webcam%20Project/RootEncoder/RTSP-Server/rtspserver/src/main/java/com/pedro/rtspserver/server/ServerCommandManager.kt)

1.  **Exhaustive `when` branches**: Update audio and video codec `when` expressions to handle new codecs (`HE_AAC` for audio, `VP8`, `VP9` for video).
2.  **Update `SdpBody` calls**: Align calls to `SdpBody` static methods with their new signatures:
    *   `createAacBody`: Added `isHeAac` boolean.
    *   `createG711Body`: Removed `sampleRate` and `isStereo` (they were unused/fixed in G711).
    *   `createOpusBody`: Added `sampleRate` and `isStereo`.
    *   `createH264Body` / `createH265Body`: Now take `ByteBuffer` instead of Base64 encoded `String`.
    *   `createAV1Body`: Added missing `header` (`ByteBuffer`) parameter.
3.  **Cleanup**: Remove unresolved references to `spsString`, `ppsString`, and `vpsString`.

## Verification Plan

### Automated Tests
- Run `:app:assembleDebug` to verify that the project now compiles successfully.

### Manual Verification
- Deploy the app to a device and verify that RTSP streaming still works as expected (if a device is available and configured).
