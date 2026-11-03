# Office Ledger Android

Private-by-default manual office attendance tracker with a native one-tap Android home-screen widget.

## Build an APK
Open the **Actions** tab and select **Build Office Ledger APK**, or push to `main`. Download the `OfficeLedger-debug-apk` artifact from the completed workflow; extract `app-debug.apk` to install it.

## Keep your attendance history private
This **public** repository intentionally contains **no personal check-in history**. It builds an app that starts with an empty log. After installing, use **Import JSON** to load your private Office Ledger backup on your phone. Do not commit personal exports, itineraries, or email URLs.

## Notes
- One tap logs the local calendar date without opening the app.
- Repeated taps do not add extra attendance credits.
- Rolling 13-week counts cap each eligible weekday at one day.
- Manual check-ins are not location or badge verification.
- On-device app data, with JSON export/import.
