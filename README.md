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

1. **Say "Jarvis"**, pause, and wait for the beep, then say your command.
2. **Tap-tap**: tap the glasses' temple once to pause, then again within about
   1.5 s to resume. The app treats that as "listen". You'll hear a beep in the
   glasses, then say your command.
3. Or press **🎤 Talk** in the notification, or add the **Talk to music**
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
| "call Mom" / "call John on mobile" / "call 555 123 4567" | places the call |
| "answer" / "decline" | answers or declines a ringing call |
| "text Mom I'm on my way" / "tell John that dinner's ready" | SMS, read back to you first |
| "WhatsApp John see you soon" / "message Mom on WhatsApp saying hi" | new WhatsApp message, read back to you first |
| "read my messages" / "what did Sarah say" | reads new messages from any app aloud |
| "reply sounds good" / "reply to John on my way" | replies in the app the message came from |
| "tap Send" / "scroll down" / "type hello" / "go home" / "press back" | controls whatever is on screen |

## Calls, messages and controlling other apps

| | Screen off & locked? | Needs |
|---|---|---|
| Calls, answer/decline | ✅ | Contacts + Phone permissions |
| Texts (SMS) | ✅ | Contacts + SMS permissions |
| Read & reply to messages from **any** app (WhatsApp, Messenger, Telegram, Signal, Samsung Messages, Instagram…) | ✅ | Notification access |
| New WhatsApp message (not a reply) | ❌ unlock first | Contacts + Screen control |
| Tap / scroll / type / home / back in any app | ❌ unlock first | Screen control |

- **Nothing is sent without your OK.** The app reads every text, WhatsApp
  message and reply back to you ("Text Mom: I'm on my way. Send it?") and
  only sends after a "yes" or "send it". Calls go straight through when the
  name clearly matches a contact; otherwise it asks "Call John Smith?" first.
- **Replies use the reply button in the app's own notification**, the same
  way Android Auto and smartwatches do. That's why they work in any messaging
  app, even while the phone is locked. Turn on **Read new messages aloud as
  they arrive** to hear messages as they come in, then say "Jarvis, reply…".
- **New WhatsApp chats**: WhatsApp has no API for sending, so the app opens
  the chat with your message typed in and uses screen control to press Send.
  The phone has to be unlocked for that to work.
- **Screen control** is an Android accessibility service that you turn on
  yourself. It finds on-screen buttons by their name, so "tap Send" or "tap
  Settings" works in most apps. Games, and apps whose buttons have no labels,
  won't respond. Some banking apps block accessibility services entirely.
- **You can't hang up by voice.** During a call, Android doesn't let other
  apps hear the microphone, so Jarvis pauses. Tap the glasses to hang up
  instead.
- Texts go out from your default SIM and show up in Samsung Messages like any
  other text.

## The "Jarvis" wake word

Whenever the glasses are connected, the app listens for **"Jarvis"** (or "Hey
Jarvis") through the glasses' microphone.

- **Fully offline and free.** It uses [Vosk](https://alphacephei.com/vosk/), an
  open-source speech recognizer, with its small English model built into the
  app. There's no account, no key and no internet, and audio never leaves the
  phone.
- **Only the name on its own triggers it.** "I was talking to Jarvis
  yesterday" doesn't. The recognizer also knows a set of sound-alike words
  ("jars", "service", "harvest", "nervous"…) so those don't trigger it either.
- **Tested on synthetic voices** (`tools/jarvis_eval.py`: 6 voices, with and
  without background noise): 67 of 72 "Jarvis" calls detected, and 0 false
  triggers out of 492 similar-sounding or everyday phrases. Real voices will
  differ; if it misses you, say it a little more slowly.
- **The catch: music sounds worse while Jarvis listens.** Keeping the glasses'
  mic open holds them in phone-call Bluetooth mode, so music drops to
  call quality (mono, muffled) and the glasses' battery drains faster. If
  that bothers you, turn on **Pause Jarvis while music plays**. Music then
  stays full quality, Jarvis listens whenever nothing is playing, and you use
  tap-tap while music is on.
- Turn Jarvis off completely with **Always listen for "Jarvis"** in the app.

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
     permissions. It also asks for Contacts, Phone and SMS access, which are
     only needed for calls and texts.
   - **2 · Allow notification access** opens *Notification access*. Turn on
     Glasses Tunes. Android requires this before an app can control Samsung
     Music's playback, and it's also how the app reads and replies to your
     messages. Messages are only kept in memory on the phone and never leave
     it.
   - **3 · Allow display over other apps** lets the app auto-start when the
     glasses connect and open apps hands-free.
   - **4 · Turn on screen control (optional)** opens Accessibility settings.
     Find Glasses Tunes under *Installed apps* and turn it on.
   - **"Restricted setting"?** Android blocks notification access and
     accessibility for any app installed outside the Play Store until you
     allow them. Go to Settings → Apps → Glasses Tunes → ⋮ (top right) →
     **Allow restricted settings**, then repeat steps 2 and 4.
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

- **Not "Hey Meta."** The glasses handle that wake word themselves and start
  Meta AI, so this app uses "Jarvis".
- While you speak a command, the app pauses the music. Without Jarvis, the
  glasses also switch to call-quality Bluetooth for those 2 to 5 seconds.
- The phone first has to unpack the Jarvis speech model (about 70 MB). This
  happens once, a few seconds after the first start.
- Speech recognition uses the phone's Google speech service, which may need a
  data connection unless offline speech is installed.
- If a double-tap on your glasses is set to skip tracks, leave a short beat
  between the two taps.
- Google Play Protect may warn you about a sideloaded app that can send
  texts and use accessibility. That's expected for this kind of app. You can
  review everything it does in this repo.
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
| `PhoneActions.kt` | contacts, calls, SMS, opening WhatsApp chats |
| `ContactMatcher.kt` | picks the contact and splits "mom I'm late" into name + message (unit tested) |
| `MediaNotificationListener.kt` | Samsung Music control, plus the message inbox for reading and replying |
| `ScreenControlService.kt` | accessibility service: tap, scroll, type, back/home, WhatsApp Send |
| `JarvisWakeWord.kt` | always-on "Jarvis" listener (Vosk, offline) |
| `JarvisDetector.kt` | wake-word grammar and acceptance rule (unit tested) |
| `tools/jarvis_eval.py` | measures detection and false triggers on synthetic speech |

Run the tests with `./gradlew testDebugUnitTest`.
