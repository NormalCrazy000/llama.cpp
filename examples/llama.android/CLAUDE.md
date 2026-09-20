# llama.android - agent notes

Style rules and the bans on commits, PRs and reviewer replies live in the root [AGENTS.md](../../AGENTS.md). Read it first. What follows is only what this directory adds.

See [README.md](README.md) for what the HTTP endpoint does and how to call it. This file is about what not to break.

## Invariants

**One funnel into the engine.** `MainActivity.sendMessage()` is the only place that calls `engine.sendUserPrompt()`. The send button and `ChatHttpServer` both go through it, serialized by `chatMutex`.

The engine already refuses a concurrent prompt: `sendUserPrompt` checks for `State.ModelReady` and throws while generating, so the native state is safe on its own. `chatMutex` is there for the two things that check does not cover. It makes the second caller wait instead of failing, which over HTTP would be a lost message and a 503, and in the UI path an uncaught exception inside `lifecycleScope.launch`, so a crash. It also keeps `messages` and `lastAssistantMsg` from interleaving tokens of two answers.

Note that parallelism 1 alone does not serialize a generation: `emit` suspends on every token while the collector hops to the main thread, and the dispatcher thread is free to run another coroutine in between. A new path that calls the engine directly bypasses both the mutex and the message bookkeeping.

**State lives in C++ globals.** `current_position` and `chat_msgs` in `lib/src/main/cpp/ai_chat.cpp` persist across turns. The conversation is not stateless and never resets on its own: `reset_long_term_states()` is only reachable from `unload()`, which also frees the model.

**`contextUsed` and `contextTotal` read the native side off the dispatcher on purpose.** Every other call in `InferenceEngineImpl` goes through `llamaDispatcher`. These two do not, because that dispatcher is busy during generation and the counter would freeze exactly when it matters. The race is on a plain int and is accepted. Do not "fix" it by moving them onto the dispatcher.

**`setSystemPrompt` works only right after `loadModel`.** `_readyForSystemPrompt` turns false as soon as any prompt is processed. The app currently never calls it, so the conversation runs with no system prompt.

## Build

```bash
./gradlew :app:assembleDebug --offline
```

Run it from this directory. Touching `ai_chat.cpp` triggers a CMake rebuild of llama.cpp, so it is not a free edit.

R8 is on for debug too. Native methods survive it through the default proguard rules, but anything that relies on reflection does not.

## Scope

This is a fork of a public repo. Changes here stay in the fork. `AGENTS.md` asks to pause and confirm before introducing a new pattern or a new component, and that still applies: the point is to keep the diff against upstream small and reviewable, even with no maintainer to convince.
