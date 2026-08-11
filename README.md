# 🤖 JunAI

> **A local-first AI assistant for Android — built to chat, understand commands, remember, learn, and interact with your device.**

JunAI is an experimental on-device AI assistant that combines a local language model, intent understanding, function calling, an AI agent, long-term memory, RAG, screen understanding, and **learn-by-demonstration** capabilities in one Android application.

The goal is simple: make an assistant that doesn't just answer — **it can understand what you want, take actions, learn workflows, and become more useful over time.**

---

## ✨ What JunAI Can Do

### 🧠 Local AI Chat

JunAI runs its conversational model locally on the device using **Qwen3 0.6B (GGUF)**.

- Local/on-device inference
- Streaming responses
- Conversation history
- Hinglish-friendly interaction
- Custom Jun persona/instructions
- Stop/interrupt generation
- No cloud LLM required for normal chat

### 🎯 Intent & Command Understanding

JunAI uses a dedicated intent layer before executing actions.

- Hinglish normalization
- Synonym handling
- Multi-signal intent matching
- Confidence scoring
- Entity/target extraction
- Negation detection
- Context-aware routing
- Fallback handling for unknown commands

This allows commands to be expressed naturally instead of requiring one exact phrase.

### ⚡ FunctionGemma Command Router

Unknown command-shaped requests can be routed through **FunctionGemma 270M** for function-call interpretation.

Supported command functions include:

- Open apps
- Call contacts
- Play / pause music
- Create reminders
- Create notes
- Web search
- Open settings
- Tell time / date
- Tell battery level

FunctionGemma is used as an action router, not as JunAI's general chat model.

---

## 🤖 AI Agent

JunAI includes an agent architecture for more complex tasks instead of limiting every request to a single action.

### Agent capabilities

- System control tasks
- Screen-reading tasks
- Web research
- Multi-step tasks
- Task continuation / resume
- Goal and context handling
- Step planning
- Action execution
- Verification and failure handling
- Confidence-based decisions
- Confirmation and cancellation flows

The architecture is designed around a pipeline similar to:

**Goal → Context → Plan → Action → Verification → Result**

---

## 🎥 Learn by Demonstration — Record & Replay

One of JunAI's core ideas is that the assistant can **learn a workflow from a user's demonstration**.

JunAI can record interaction information such as:

- Taps
- Text input
- UI identifiers
- Resource IDs
- Labels / content descriptions
- App and package context
- Screen transitions

Recorded interactions can be stored as **macros** and replayed later through the replay engine.

### 🔐 Sensitive input protection

The recording system is designed to avoid capturing sensitive fields such as passwords, PINs, and OTP-style inputs.

---

## 👁️ Screen & UI Understanding

Through Android accessibility capabilities, JunAI can inspect screen/UI information for supported agent and learning workflows.

It can work with:

- Visible UI elements
- Text and labels
- Resource identifiers
- Screen context
- UI hierarchy information
- Screen-to-screen transitions

This forms an important foundation for automation, learning, and agent interaction.

---

## 🧠 Long-Term Memory

JunAI has a persistent memory layer for information learned about the user and the system.

Memory infrastructure includes:

- Semantic facts
- Preferences
- User information
- Importance scoring
- Confidence tracking
- Access tracking
- Memory reinforcement
- Memory compression

### 🕸️ Knowledge Graph

JunAI also maintains a graph-based representation of knowledge using nodes and relationships, allowing related facts to be connected instead of stored only as isolated entries.

---

## 📚 RAG & Knowledge Base

JunAI includes a local knowledge-base/RAG architecture with curated information across multiple domains.

Current knowledge domains include:

- 🌍 General Knowledge
- 💻 Technology
- 🩺 Health Care
- 🎓 Education
- 💼 Career
- 💰 Finance
- 🍳 Cooking
- 🧬 AI & ML
- 👨‍💻 Programming
- 🌐 Web Development
- 🎬 Films
- 🎮 Apps & Games
- 🌱 Lifestyle

The knowledge pack is downloaded and stored locally rather than being bundled directly into the APK.

### 🔎 Semantic Matching

JunAI uses an on-device embedding engine for semantic similarity, allowing related queries to be matched by meaning rather than exact wording.

---

## 🌐 Web Search

When local knowledge is not enough, JunAI has a web-search pipeline for retrieving external information and presenting it conversationally.

The architecture supports a local-first approach where web retrieval can complement the on-device knowledge and model layers.

---

## 📈 Self-Learning

JunAI contains a learning infrastructure designed to improve its behavior from interaction and user feedback.

It supports concepts such as:

- Failed queries
- Pending learning items
- User-taught knowledge
- Trained commands
- Skills
- Aliases
- Related questions
- Confidence and reinforcement
- Learning statistics

The aim is for JunAI to improve from **what the user teaches it**, rather than relying only on its original model behavior.

---

## 👀 Passive Learning

With explicit permissions, JunAI can observe supported app interactions in the background learning layer.

Passive learning can capture:

- Screen structures
- UI elements
- App context
- Screen transitions
- Interaction relationships
- Confidence information

The system uses an **allow-list / default-deny permission model** and includes automatic expiry for older captured learning data.

---

## 🎙️ Voice Interaction

JunAI supports voice-based interaction through the normal assistant pipeline.

**Speech → Text → Intent / AI → Response → Speech**

Voice features include speech input and text-to-speech output, with voice interaction integrated into the assistant experience.

---

## 🧰 Built-in Utilities

JunAI also includes several built-in assistant utilities:

- 📝 Notes
- ✅ Todo
- ⏰ Reminders
- 🧮 Calculator
- 🌐 Translator
- 🎵 Music controls
- 🎨 Drawing
- ⚙️ Settings
- 🔋 Battery information
- 🎲 Dice
- 🪙 Coin flip
- 😂 Jokes

---

## 🎵 Music Control

Music actions are integrated into JunAI's command system.

- Play
- Pause
- Next track
- Previous track
- Stop

---

## 🫧 Jun Floating Assistant

JunAI includes a floating/bubble-style assistant experience designed to keep Jun accessible outside the main chat screen.

The floating assistant layer supports concepts such as:

- Floating bot interaction
- Expressions
- Mood/state
- Speech
- Notifications
- Battery awareness
- Foreground-app awareness

---

## 📦 On-Device Model System

Large AI assets are handled separately from the base APK through a runtime model-download system.

### Current model stack

| Component | Purpose |
|---|---|
| **Qwen3 0.6B** | General local chat and understanding |
| **FunctionGemma 270M** | Natural-language → function routing |
| **Universal Sentence Encoder** | Semantic similarity / matching |
| **Knowledge Pack** | Curated local RAG knowledge |

Models are downloaded to app-private storage and managed by JunAI's model system.

---

## 🏗️ Architecture Overview

```text
                         ┌─────────────────────┐
                         │       User          │
                         │  Text / Voice / UI  │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │   Intent / Router   │
                         └──────┬───────┬──────┘
                                │       │
                    ┌───────────┘       └────────────┐
                    ▼                                ▼
             ┌─────────────┐                  ┌─────────────┐
             │  Qwen3 LLM  │                  │ FunctionGemma│
             │  Local Chat │                  │    Actions   │
             └──────┬──────┘                  └──────┬──────┘
                    │                                │
                    └────────────┬───────────────────┘
                                 ▼
                         ┌─────────────────┐
                         │    AI Agent     │
                         │ Plan / Execute  │
                         └────────┬────────┘
                                  │
             ┌────────────────────┼────────────────────┐
             ▼                    ▼                    ▼
       ┌───────────┐        ┌────────────┐       ┌────────────┐
       │   Apps    │        │   Screen   │       │ Web Search │
       │ / Device  │        │  / UI      │       │            │
       └───────────┘        └────────────┘       └────────────┘

              ┌────────────────────────────────────┐
              │ Memory • RAG • Knowledge Graph     │
              │ Learning • Record & Replay         │
              └────────────────────────────────────┘
```

---

## 🛠️ Technology Stack

- **Kotlin**
- **Android**
- **Jetpack Compose**
- **Room Database**
- **Coroutines**
- **MediaPipe / LiteRT components**
- **Llamatik / llama.cpp**
- **GGUF models**
- **Qwen3 0.6B**
- **FunctionGemma 270M**
- **Universal Sentence Encoder**
- **Android Accessibility Services**
- **WorkManager**

---

## 🔒 Local-First Philosophy

JunAI is designed around a **local-first architecture** wherever practical.

Core AI capabilities can run on the Android device using locally stored models, while optional web retrieval provides access to information that isn't available locally.

This architecture is intended to give the user more control over their data, models, and assistant behavior.

---

## 🚧 Project Status

JunAI is an **actively developed experimental project**.

Some capabilities are still evolving, and model accuracy, device compatibility, and automation reliability can vary depending on the Android device, model, application, and task.

The project prioritizes real on-device experimentation over claiming capabilities that have not been validated.

---

## 🗺️ Roadmap

The long-term direction of JunAI includes:

- More reliable autonomous task execution
- Better screen/UI understanding
- Stronger learn-by-demonstration workflows
- Improved Hinglish understanding
- Better local RAG retrieval
- More robust memory and knowledge relationships
- More device/app integrations
- Improved model efficiency on mobile hardware

---

## 🤝 Contributing

JunAI is primarily an experimental development project. If you want to explore the architecture, experiment with the code, or suggest improvements, feel free to open an issue or discussion.

---

## 📄 License

See the repository for the current project license and distribution terms.

---

<div align="center">

**Built with Kotlin, local AI, experimentation, and a lot of debugging. 🛠️🤖**

</div>
