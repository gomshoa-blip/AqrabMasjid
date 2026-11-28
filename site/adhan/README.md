# Adhan voices for the app

Put the MP3 files in this folder and add one line for each voice in `list.json`:

```json
[
  {"id": "najdi", "ar": "الأذان النجدي", "en": "Najdi adhan", "url": "najdi.mp3", "fajr": "najdi_fajr.mp3"}
]
```

- `id`: short English name, no spaces.
- `url`: the adhan file. `fajr` (optional): the Fajr adhan file.
- Use only recordings you have permission to use.
- The app reads this list from https://masjid.gomshoa.com/adhan/list.json — no new app version is needed.
