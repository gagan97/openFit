## Summary

A single non-finite numeric value in an imported activity file kills the **entire** file-import run, and the activity that triggered it never gets its combined stream. Subsequent runs keep failing at the same point (the activity is retried), so later imports in those runs are affected too.

## Environment

- `robiningelbrecht/dreeve:latest` (v5.3.0), `IMPORT_MODE=files`
- Triggered via the 5-minute daemon cron (`runFileImport`) and reproducible with `bin/console app:import:files -vvv`

## What happens

An imported TCX contained a huge altitude reading — `1.7976931348623157E308` (`Double.MAX_VALUE`), i.e. a "sensor has no value" sentinel written verbatim by the recording app. The import itself succeeds, then:

```
  => Imported 1, skipped 0, failed 0 activity file(s)
  => Calculated moving stream for 1 activities (< 1 ms)

In Json.php line 16:
  [JsonException (7)]
  Inf and NaN cannot be JSON encoded
```

Stack (abridged):

```
Json::encode()                                  src/Infrastructure/Serialization/Json.php:16
Json::encodeAndCompress()                       src/Infrastructure/Serialization/Json.php:57
DbalCombinedActivityStreamRepository->add()     .../CombinedStream/DbalCombinedActivityStreamRepository.php:28
CalculateCombinedStreams->process()             .../CalculateActivityMetrics/Pipeline/CalculateCombinedStreams.php:200
CalculateActivityMetricsCommandHandler->handle() .../CalculateActivityMetricsCommandHandler.php:28
```

The value overflows to INF when combined streams are calculated, and `json_encode` refuses to serialise it.

Impact:
- the affected activity ends up with **no `CombinedActivityStream` row** (charts/dashboard data missing for it),
- the command aborts, so any activity later in the same run gets no metrics either,
- because the activity is retried on every run, the error repeats every 5 minutes until the activity is removed/repaired.

## Reproduction

1. Take a TCX containing `<AltitudeMeters>1.7976931348623157E308</AltitudeMeters>` on a trackpoint (this is what an app that passes through Health Services' "no value" sentinel emits).
2. Drop it into the `watch/` folder and run `bin/console app:import:files -vvv`.
3. The activity imports, then the combined-stream step fails as above; `CombinedActivityStream` has no row for it and every later run logs the same error.

## Suggested fix (any of these helps)

1. Treat non-finite / absurd values as `null` when reading streams (or clamp them), so the combined-stream calculation can't overflow.
2. Wrap the JSON encoding of a combined stream so a bad value is dropped instead of aborting the whole command.
3. Make the failure per-activity rather than per-run: one unprocessable activity shouldn't stop metrics for the rest of the batch.

## Notes

I hit this as the author of the recording app that produced the file: it's fixed on my side (the writer now filters non-finite/absurd values, so the sentinel never reaches the XML) — but a defensive fix here seems worthwhile, because any recorder that forwards a sentinel can permanently wedge the pipeline for one activity and silently starve others.
