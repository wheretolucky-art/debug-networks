# PPL Coach

A personal gym app for a Push/Pull/Legs lean bulk: workout logging with next-session targets, a rest timer, bodyweight and sleep check-ins, diet tracking against a 7-day meal plan, PRs and progress charts, and a weekly report to send to a coach every Sunday.

- `web/` is the app itself, a single web page that also works in a phone browser.
- `android/` wraps it as an Android app (APK). GitHub builds it on every push to `main` or `gym-app` that changes this folder, tries it on an Android 14 emulator, and puts the results on the `gym-app-build` branch.

The APK GitHub builds is unsigned. It is signed with the PPL Coach release key, which is kept private and never stored in this repository. Every update must be signed with that same key, or Android will refuse to install it over the app already on the phone.

Everything logged stays on the phone. Nothing is sent to this repository or to any server. Use **Settings → Back up now** every week. Tests use made-up data only.

## How weights are entered

Barbell, Smith machine and EZ bar: plates only, not the bar. Leg press and hack squat: plates only, not the sled. Dumbbells: one dumbbell. Cables and machines: the stack number. Assisted pull-ups: minus the assistance (`-20`).

## Plan codes

The coach sends a new plan as a code the app imports in **Settings → Update plan from coach**. History is kept by exercise `id`, so an exercise that carries on into the new plan keeps the same `id`.

```json
{
  "type": "ppl-plan",
  "name": "Week 2",
  "rotation": ["pushA", "pullA", "legsA", "pushB", "pullB", "legsB", "rest"],
  "sessions": {
    "pushA": {
      "name": "Push A", "focus": "Chest", "kind": "push",
      "exercises": [
        { "id": "bench", "name": "Barbell bench press", "equip": "barbell", "muscles": ["chest"],
          "sets": 3, "min": 6, "max": 10, "rir": "1–2", "rest": 180, "cue": "Bar to lower chest" }
      ]
    }
  },
  "diet": {
    "targets": { "kcal": 2700, "protein": 130, "carbs": 390, "fat": 70 },
    "days": [ { "name": "Day 1", "meals": [ { "name": "Breakfast", "text": "60 g oats + 250 ml milk", "kcal": 373, "p": 16, "c": 52, "f": 11.5 } ] } ]
  }
}
```

- `kind`: `push`, `pull` or `legs` (sets the colour).
- `equip`: `barbell`, `smith`, `ez`, `db`, `stack`, `plates` or `bw`. It sets how weight is entered and how much the app suggests adding (2.5 kg, 5 kg for plate-loaded machines, the next pin for stacks).
- `muscles`: any of `chest`, `back`, `sidedelts`, `reardelts`, `frontdelts`, `biceps`, `triceps`, `quads`, `hamstrings`, `glutes`, `calves`, `abs`. Used for weekly sets per muscle.
- Optional per exercise: `note` (how to enter the weight) and `noKg: true` (reps only).
- `diet` is optional; send only `targets`, only `days`, or both.

## Progression rule

Double progression. When every set reaches the top of the rep range, the app suggests the next weight up and the bottom of the range. Below the bottom of the range two sessions in a row, it suggests dropping about 10%. Otherwise it asks for one more rep per set at the same weight.
