# DN Office

The office app for **Debug Networks**, a CCTV and network services business in Hazaribagh, Jharkhand: clients, jobs, quotes with PDF, payments, follow-ups, CCTV cards and the ₹1 lakh goal.

- `web/` is the app itself, a single web page that also works as an installable web app.
- `android/` wraps it as an Android app (APK). GitHub builds it on every push to `main` or `dn-office` that changes this folder, tries it on an Android 14 emulator, and puts the results on the `dn-office-build` branch.

The APK GitHub builds is unsigned. It is signed with the Debug Networks release key, which is kept private and never stored in this repository. Every update must be signed with that same key, or Android will refuse to install it over the app already on the phone.

Records are saved only on the phone. Nothing is sent to this repository or to any server. Use **Settings → Back up now** every week.

Tests use made-up records only. This repository is public, so real customer details must never be added to it.
