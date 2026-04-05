# LumenHeadless

Headless validation and tooling for **Lumen** scripts, without a running Minecraft server.

---

## What It Is

[Lumen](https://lumenlang.dev) is a high-performance scripting language for Minecraft servers that compiles scripts directly into native Java code. Normally, validating a script requires a live Bukkit/Paper server to load the plugin.

LumenHeadless removes that requirement. It bootstraps the full Lumen registration system, including all registered patterns/type bindings/events in the actual plugin, and exposes them as a lightweight local process, without a Minecraft server running.

This makes it ideal for:

- **Editor integrations** that show diagnostics as you type
- **AI tooling** that needs to reason about Lumen and validate code

The VS Code extension for Lumen uses LumenHeadless to fully validate scripts.

---

## What It Provides

**Script validation and compilation.** Send a `.luma` script source string, get back the generated Java and any errors. The same pipeline that runs on a live server runs here.

**Pattern search.** Query the full set of registered patterns with real pattern matching or fuzzy scoring.

**Type binding checks.** Test whether a given input token matches a specific Lumen type binding, with support for simulating a variable environment. 

Everything runs in a single long-lived process accessed via a JSON line protocol over stdin/stdout.

## How does it do it?

It embeds the entire Spigot API inside of the jar, along with the full Lumen plugin, and dependencies. Then runs a extracted-version of the JavaPlugin.

---

## Requirements

- Java 17 or newer (JRE is sufficient)
