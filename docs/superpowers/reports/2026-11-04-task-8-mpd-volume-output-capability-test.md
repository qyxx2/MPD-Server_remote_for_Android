# Task 8 Add-on — MPD Volume and Output Capability Live Test

## Scope

Read-only capability inspection plus a reversible one-step MPD volume test against the configured NAS MPD endpoint. No Android or MPD-Server production code was changed. This is a live MPD protocol test, not an Android UI integration test or a physical DAC hardware qualification.

## Target and preconditions

- Endpoint: `192.168.3.94:6600`
- Greeting: `OK MPD 0.23.5`
- Test date: 2026-11-04 (Asia/Shanghai)
- Initial MPD state: `pause`
- Initial current song ID: `35898`
- Initial MPD volume: `100`

Playback was paused before the volume mutation. The test changed the volume by one point and restored the original value in the same session.

## Live protocol evidence

| Probe | Observed result |
|---|---|
| `commands` | 105 commands; `getvol`, `setvol`, `status`, and `outputs` are all advertised |
| `status` | `volume: 100`, `state: pause`, `songid: 35898`, `bitrate: 821`, `audio: 44100:16:2` |
| `getvol` | `volume: 100` |
| `outputs` output 0 | `outputname: USB DAC`, `plugin: alsa`, `outputenabled: 1`; attributes `allowed_formats=` and `dop=0` |
| `outputs` output 1 | `outputname: HTTP Stream (port 6680)`, `plugin: httpd`, `outputenabled: 0` |
| `currentsong` | Current track reports `Format: 44100:16:2` |
| `config` | Rejected with `ACK [4@0] {config} Command only permitted to local clients` |

### Reversible volume test

| Step | MPD result |
|---|---|
| Initial `getvol` | `100` |
| `setvol 99` | `OK` |
| Follow-up `getvol` | `99` |
| Playback state/song ID during test | Still `pause` / `35898` |
| `setvol 100` restore | `OK` |
| Final `getvol` | `100` — original value restored |

No playback command, queue mutation, output enable/disable operation, database update, or Android app setting was changed.

## Conclusion

1. **MPD-level volume adjustment is supported and live-verified.** The server accepts `setvol`, the subsequent `getvol` confirms the new value, and the original value was restored successfully.
2. **The currently enabled MPD audio output is the configured `USB DAC` ALSA output.** The second HTTPD output is configured but disabled. MPD reports output identity/type/enabled state and the listed output attributes.
3. **Current audio-format information is available.** The observed track/status format is `44100:16:2` (44.1 kHz, 16-bit, 2-channel); the current MPD bitrate field was `821`.
4. **The result does not prove direct hardware-volume control inside the DAC.** `setvol` is MPD's global volume control, not a per-output-ID volume command. The remote `config` command is restricted to local clients, and the output response does not expose a mixer type or DAC hardware model. Therefore this test confirms MPD output-volume adjustment while the USB DAC output is active, but cannot distinguish software attenuation from an ALSA hardware mixer or prove the DAC's physical gain control.
5. **Output metadata is limited.** `outputs` does not provide a complete DAC model/USB descriptor or a supported-sample-rate matrix. `allowed_formats` is empty in the returned attributes; that must not be interpreted as a complete capability list. The `audio` field reports the current stream format, not all formats the DAC can accept.

## Verification boundary

- Live endpoint tested successfully from the connected Ubuntu workstation.
- The test was protocol-level and did not modify Android source, MPD-Server source, MPD configuration, queue, or output routing.
- No source build or Android instrumentation tests were applicable because no application code changed.
- No commit was created; the report is recorded on the existing Task 8 branch as a test-only add-on.
