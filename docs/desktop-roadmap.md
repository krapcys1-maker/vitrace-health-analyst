# Desktop Roadmap

VitaTrace may later have a desktop companion, but Android comes first because Health Connect data can only be read by an app installed on the phone.

## Future Desktop Role

The desktop app should not bypass Android privacy controls. It should consume exported aggregates from the Android app.

Possible flow:

```text
Health Connect -> VitaTrace Android -> local database -> aggregate export -> VitaTrace Desktop
```

## Not In MVP

- Always-on phone-to-desktop sync.
- Cloud backend.
- Raw health data transfer.
- Direct Health Connect access from desktop.

## Likely First Desktop Feature

Read an exported JSON/SQLite file from the Android app and show deeper charts, reports, and AI summaries from aggregated metrics.

