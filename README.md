# MeshCall Offline — Direct LAN Audio/Video Call (Test Build)

This is an Android **source project**, not a prebuilt or certified APK. It is intended for a direct one-to-one WebRTC audio/video call between two Android phones on the same local Wi-Fi/hotspot.

## Included in this project
- Your supplied portrait as launcher icon and in-app image resource.
- UDP LAN peer discovery (UDP port 45454).
- TCP JSON signaling server (TCP port 45455).
- WebRTC SDP offer/answer and ICE candidate exchange.
- Microphone audio track with mute/unmute.
- Camera2 capture and local preview.
- Remote video track renderer.
- Basic direct peer-to-peer call UI.
- GitHub Actions workflow to build a debug APK.

## Two-phone test
1. Build the debug APK from GitHub Actions and install it on both phones.
2. Grant Camera and Microphone permissions.
3. Connect both phones to the same Wi-Fi or hotspot. The hotspot must permit clients to communicate with each other; some phones block client-to-client traffic.
4. Open MeshCall on both phones. On each phone press **Start LAN Discovery**.
5. On each phone press **Initialize Audio + Camera** and wait for the ready message.
6. When the other phone appears in Nearby peers, press **Call selected / all discovered peers** on one phone.
7. Accept Android audio routing/permission prompts if shown. Both phones should show local and remote video when the connection succeeds.

## Important limitations — read before use
- This source has **not been built or tested on physical Android phones in this environment**. A successful GitHub build and two-device test are still required.
- Direct WebRTC connection is attempted with host ICE candidates only. No STUN/TURN server is configured, so this is for the same reachable LAN only.
- Some Android hotspot implementations isolate connected clients; in that case peer discovery or calling will fail.
- This is a direct peer-to-peer calling prototype. It does not implement RTP forwarding through a third phone, multi-hop media relay, group SFU, or seamless live-route failover.
- A ZIP or APK build completing does not prove that a live call works on every device/network. Camera hardware, Android version, permissions, Wi-Fi isolation, and audio routing can affect results.

## Build
Push the project to a GitHub repository. Open **Actions** and run **Build MeshCall APK**. Download the `MeshCall-debug-apk` artifact. This produces a debug APK, not a Play Store release.
