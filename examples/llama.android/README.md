# llama.android

Sample Android app running local inference with llama.cpp. Pick a GGUF model file from the device, then chat with it.

This fork adds a local HTTP endpoint that drives the same chat from outside the app.

## HTTP endpoint

The app can listen on port `8080` and accept chat messages over HTTP. A request is handled exactly like a message typed by hand: the text appears in the chat, the model answers in the UI, and the full answer is returned in the HTTP response.

### Enabling it

The endpoint is off by default. To turn it on:

1. Load a GGUF model. The toggle stays disabled until a model is ready.
2. Flip the `HTTP endpoint` switch.

The status line next to the switch shows the address to call, for example `Endpoint: http://192.168.1.34:8080/chat`.

The choice is kept in `SharedPreferences`, so the endpoint comes back on at the next launch once a model is loaded. While the switch is off the port is not bound at all, so a connection attempt is refused instead of hanging.

### Reference

Only one route exists.

```
POST /chat
Content-Type: application/json

{"message": "your prompt"}
```

The body can also be plain text, used as the message when it does not parse as JSON.

The response comes back when the generation is complete, so the call blocks for as long as the model takes to answer:

```json
{"response": "the assistant answer"}
```

Error responses, all JSON:

| Status | Body | Cause |
| --- | --- | --- |
| 400 | `{"error":"empty message"}` | Message missing or blank |
| 404 | `{"error":"use POST /chat"}` | Wrong method or path |
| 503 | `{"error":"..."}` | No model loaded, or generation failed |

### Examples

```bash
# Basic call
curl -X POST http://192.168.1.34:8080/chat \
  -H 'Content-Type: application/json' \
  --max-time 300 \
  -d '{"message":"Hello, who are you?"}'

# Answer text only
curl -s -X POST http://192.168.1.34:8080/chat \
  -H 'Content-Type: application/json' \
  --max-time 300 \
  -d '{"message":"Hello"}' | jq -r .response

# Over USB, to take the network out of the picture
adb forward tcp:8080 tcp:8080
curl -X POST http://localhost:8080/chat -d '{"message":"test"}'
```

## Conversation and context

The endpoint and the UI share one single conversation. A message sent over HTTP sees everything typed by hand before it, and the other way round.

The context is 8192 tokens, hardcoded in `DEFAULT_CONTEXT_SIZE` at `lib/src/main/cpp/ai_chat.cpp`. It is enforced even when the model was trained with a different one. The budget is cumulative: every message and every answer adds up, which is roughly 20 to 40 exchanges for a normal chat.

Previous turns are not resent to the model. They stay in the KV cache, so each turn only pays for its own new tokens. The cache is sized for the full context and allocated when the model loads, so raising the context size costs RAM from the start, not as the chat grows.

The `Context: 1247 / 8192 tokens (15%)` line under the toggle tracks this counter live during generation. When the context fills up, `shift_context()` discards the older half of the conversation and the counter drops by about half.

A single answer is capped at `DEFAULT_PREDICT_LENGTH` (1024 tokens) in `lib/src/main/java/com/arm/aichat/InferenceEngine.kt`.

## Design notes

**Why a hand written server instead of `tools/server`.** The repo does ship a reusable one: `llama-server-impl` in `tools/server/CMakeLists.txt` is a library target, described there as "server logic, reusable by app", and cpp-httplib is already vendored. Building it into the APK is possible.

It was not reused because `server_context` owns its own `llama_context`, while `ai_chat.cpp` owns `g_model` and `g_context`. Two owners of the inference state leave two options: load the model twice, which does not fit on a phone and would give the endpoint a conversation separate from the UI, or let the server own inference and turn the UI into an HTTP client of `127.0.0.1`.

The second option is the better architecture, and is what the llama.cpp webui does. It buys the OpenAI compatible API, streaming and slots for free. It also means rewriting the app rather than adding an endpoint, and pulls `mtmd` and the `llama-ui` assets into the APK. Worth revisiting if streaming or a standard API is ever needed.

## Known limitations

- **No reset.** The conversation never restarts. `reset_long_term_states()` is only reachable from `unload()`, which also frees the model, so the only way to start fresh is to close and reopen the app and reload the GGUF file.
- **No authentication.** Anyone on the same network can send prompts while the endpoint is on. Fine over USB or on a trusted LAN, not fine otherwise.
- **The server lives with the Activity.** It stops in `onDestroy`, so Android reclaiming the app in the background takes the endpoint down with it. A foreground service would be needed to keep it up.
- **Rotation breaks the app.** The Activity does not declare `configChanges`, so rotating destroys it and calls `engine.destroy()`, while `InferenceEngineImpl` keeps its singleton and its stale state. This predates the endpoint.

## Requirements

`android.permission.INTERNET` is declared in `app/src/main/AndroidManifest.xml`. It is needed to listen on a socket, not only to make outbound calls.
