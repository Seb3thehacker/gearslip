# Gearslip 0.1.0-alpha

This release lets you read and answer messages by voice, and the speech runs on your phone with no Google services.

## Messages and voice

- Messaging apps such as Signal and Molly open to your recent chats.
- Gearslip reads a message aloud, and you reply by voice. Say "cancel" or "change it" before it sends, or tap a quick reply such as your arrival time.
- Voice works on any phone, GrapheneOS included. Gearslip turns speech into text itself, so it needs no Google account.
- New messages show as a small card at the top right, even while you drive. A bar shows how long until the card closes or the reply sends.
- Only messages, missed calls, alarms, and reminders pop up. Everything else waits on the dashboard.

## Map

- The map stays loaded when you switch screens, so it no longer flashes black.
- Map apps draw more reliably and keep their connection when the phone is locked.
- The Home button shows your map app, so it no longer appears twice.
- The next turn shows in the nav bar when you leave the map.

## Controls and settings

- Steering wheel buttons, control knobs, and D-pads now work.
- To see the whole song title and artist in the nav bar, choose Settings > Player in the nav bar > Song title.
- Choose miles or kilometres under Settings > Units.
- "Button style" is now called "Theme".
- Try the voice assistant and the vehicle data screen under Settings > Experimental features.
- Logs now say why a car ended the connection.

## Known issues

- Spotify, Waze, and Google Messages updated to a version of Google's car library that only accepts Android Auto itself. Their own car screens won't open in Gearslip. Spotify still plays through the Gearslip player, which has the green check.
- Calls go through the car's Bluetooth, as they do in Android Auto. Pair your phone with the car to hear calls on its speakers.

## Install

Download `Gearslip-release.apk` below and open it on your phone. It updates 0.0.05-alpha in place and keeps your settings.

Found a bug, or a car where the buttons don't work? [Open an issue](https://github.com/Seb3thehacker/gearslip/issues) and share the log from the Logs screen in the phone app.
