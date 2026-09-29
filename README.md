# Glasses Tunes

Voice control for **Samsung Music** and the **music stored on your phone**
through **Ray-Ban Meta (Gen 2)** glasses.

## Why this exists

Your glasses are already a Bluetooth headset for your phone. Whatever Samsung
Music plays comes out of the glasses' speakers, and tapping the temple sends
play/pause. What you can't do is ask for something to play. "Hey Meta, play…"
only works with Meta's partner services (Spotify, Apple Music, Amazon Music and
others). Meta doesn't let other apps add commands to its assistant, and that
includes the Wearables Device Access Toolkit that developer mode unlocks.

This app fills that gap. It runs on your phone and uses the glasses' own
microphone to take a voice command. It then plays the result in Samsung Music,
or with its built-in player for the audio files on your phone.

```
 glasses mic ──(Bluetooth HFP)──▶ Android speech recognizer ──▶ command parser
                                                                     │
          glasses speakers ◀──(Bluetooth A2DP)── Samsung Music ◀─────┤ media-session "play from search"
                                              └─ built-in player ◀───┘ fallback: MediaStore songs on the phone
```

## How you use it

1. **Tap-tap**: tap the glasses' temple once to pause, then again within about
   1.5 s to resume. The app treats that as "listen". You'll hear a beep in the
   glasses, then say your command.
2. Or press **🎤 Talk** in the notification, or add the **Talk to music**
   Quick Settings tile.

Things you can say:

| Say | Does |
|---|---|
| "play Bohemian Rhapsody by Queen" | that song, then more by the artist |
| "play Queen" / "play songs by Drake" | that artist |
| "play the album Thriller" | the album, in track order |
| "play my workout playlist" | a Samsung Music playlist (Samsung Music mode) |
| "shuffle Drake" / "play the album Abbey Road on shuffle" | shuffled |
| "shuffle everything" / "play my music" | the whole library on shuffle |
| "pause", "play", "next", "previous" | playback control for whatever is playing |
| "volume up" / "volume down" | the phone's media volume |
| "what's playing" | reads out the song and artist |
| "open Maps" / "launch WhatsApp" / "start camera" | opens that app |
| "talk to Gemini" / "hey Google" / "Gemini Live" | opens your phone's assistant (Gemini) listening |

## Auto-start and the lock screen

- **Auto-start**: when your glasses connect over Bluetooth, the connector
  starts on its own, and it stops when they disconnect. The app recognizes the
  glasses by their Bluetooth name (Ray-Ban, Oakley or Meta). This needs
  "Display over other apps". Without it you get a "Glasses connected, tap to
  turn on voice control" notification instead.
- **Screen off or locked**: music commands, playback control, volume and
  "what's playing" all work with the phone in your pocket.
- **Opening apps while locked**: the app does open, but Android keeps it
  behind the lock screen until you unlock. The app tells you so. Apps built for
  the lock screen (camera, calls, navigation) usually show over it. To keep
  the phone unlocked while the glasses are connected, add them under Settings →
  Lock screen → Extend Unlock (Smart Lock) → Trusted devices.
- **Gemini**: "talk to Gemini" opens your default assistant. On the lock
  screen, Gemini only does what its lock-screen setting allows. Android has no
  public way for another app to jump straight into Gemini *Live*.

## Install

1. Get the APK:
   - download `release/GlassesTunes-debug.apk` from this repo, or
   - build it yourself with `./gradlew assembleDebug` (needs the Android SDK and
     JDK 17+). The APK ends up in `app/build/outputs/apk/debug/`.
2. Copy it to your Samsung phone and open it. Allow "install unknown apps"
   when Android asks.
3. Open **Glasses Tunes**:
   - **1 · Grant permissions & start connector** asks for the microphone,
     music-file, notification and Nearby devices (to notice the glasses)
     permissions.
   - **2 · Allow media control access** opens *Notification access*. Turn on
     Glasses Tunes. Android requires this before an app can control Samsung
     Music's playback. The app doesn't read your notifications.
   - **3 · Allow display over other apps** lets the app auto-start when the
     glasses connect and open apps hands-free.
4. Make sure the glasses are connected to the phone over Bluetooth and set as
   the audio device, the same way they are for calls.

The checklist at the top of the app shows what's working. There's also a
**Try a command without speaking** box for testing.

### Samsung tips

- Settings → Apps → Glasses Tunes → Battery → **Unrestricted**. Without this,
  One UI may put the connector to sleep.
- If Samsung Music doesn't respond to searches on your phone model, turn off
  **Use Samsung Music**. The built-in player then plays the same files straight
  from your phone's storage, and the glasses' tap controls still work.

## Limits

- **Not "Hey Meta."** The wake word and Meta AI belong to Meta. Use tap-tap or
  the Talk button instead.
- While you speak, the glasses switch to call-quality Bluetooth for about 2 to
  5 seconds, so the app pauses the music during that time.
- Speech recognition uses the phone's Google speech service, which may need a
  data connection unless offline speech is installed.
- If a double-tap on your glasses is set to skip tracks, leave a short beat
  between the two taps.
- Voice search inside Samsung Music depends on its Android Auto media service.
  If Samsung blocks it on your firmware, the app falls back to the built-in
  player.

## Code map

| File | Role |
|---|---|
| `GlassesService.kt` | foreground connector: gesture → listen → run command |
| `GlassesVoice.kt` | routes the glasses' mic in over Bluetooth and runs speech recognition |
| `CommandParser.kt` | spoken text → command (unit tested) |
| `SamsungMusic.kt` | media-session control and play-from-search for Samsung Music |
| `LocalPlayer.kt` | reads the MediaStore library and runs the built-in player with a media session |
| `LibraryMatcher.kt` | fuzzy match of "play X" against your songs (unit tested) |
| `TapTapDetector.kt` | pause→play gesture detection (unit tested) |
| `TalkTileService.kt` | Quick Settings tile |
| `AppLauncher.kt` | "open <app>" and "talk to Gemini" |
| `GlassesConnection.kt` | auto start/stop when the glasses connect or disconnect |

Run the tests with `./gradlew testDebugUnitTest`.
