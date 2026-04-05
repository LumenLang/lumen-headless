package dev.lumenlang.lumen.headless;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lumenlang.lumen.pipeline.events.EventDefRegistry;
import dev.lumenlang.lumen.pipeline.java.compiled.ClassBuilder;
import dev.lumenlang.lumen.pipeline.language.exceptions.LumenScriptException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON line protocol server that reads requests from stdin and writes
 * responses to stdout.
 */
public final class JsonProtocol {

    private static final Gson GSON = new Gson();

    private JsonProtocol() {
    }

    /**
     * Starts the JSON line protocol server, blocking on stdin until exit.
     *
     * @param headless the bootstrapped headless instance
     */
    public static void run(@NotNull HeadlessLumen headless) {
        respond(readyMessage(headless));

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                handle(headless, line.trim());
            }
        } catch (IOException e) {
            System.err.println("IO error: " + e.getMessage());
        }
    }

    /**
     * Dispatches a single JSON request to the appropriate handler.
     */
    private static void handle(@NotNull HeadlessLumen headless, @NotNull String line) {
        JsonObject request;
        try {
            request = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            respond(error("Invalid JSON: " + e.getMessage()));
            return;
        }

        if (!request.has("op") || request.get("op").isJsonNull()) {
            respond(error("missing op field"));
            return;
        }

        switch (request.get("op").getAsString()) {
            case "validate" -> validate(headless, request);
            case "compile" -> compile(headless, request);
            case "info" -> respond(infoMessage(headless));
            case "search" -> findPatterns(headless, request);
            case "check" -> typeCheck(headless, request);
            case "exit" -> System.exit(0);
            default -> respond(error("unknown op: " + request.get("op").getAsString()));
        }
    }

    /**
     * Handles the validate action: parses the Lumen source and returns generated Java.
     */
    private static void validate(@NotNull HeadlessLumen headless, @NotNull JsonObject request) {
        String source = stringField(request, "source");
        if (source == null) {
            respond(error("missing source field"));
            return;
        }

        try {
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("java", headless.compile(source, scriptName(request)));
            respond(response);
        } catch (LumenScriptException e) {
            respond(scriptError(null, e));
        } catch (Exception e) {
            respond(genericError(null, e));
        }
    }

    /**
     * Handles the compile action: parses the Lumen source, generates Java, then compiles it.
     */
    private static void compile(@NotNull HeadlessLumen headless, @NotNull JsonObject request) {
        String source = stringField(request, "source");
        if (source == null) {
            respond(error("missing source field"));
            return;
        }

        String name = scriptName(request);
        String java;
        try {
            java = headless.compile(source, name);
        } catch (LumenScriptException e) {
            respond(scriptError("validate", e));
            return;
        } catch (Exception e) {
            respond(genericError("validate", e));
            return;
        }

        List<HeadlessLumen.CompileError> errors = headless.compileJava(java, "dev.lumenlang.lumen.java.compiled." + ClassBuilder.normalize(name));
        JsonObject response = new JsonObject();
        if (errors.isEmpty()) {
            response.addProperty("ok", true);
            response.addProperty("java", java);
        } else {
            response.addProperty("ok", false);
            response.addProperty("phase", "compile");
            response.addProperty("java", java);
            response.add("errors", compileErrors(errors));
        }
        respond(response);
    }

    /**
     * Handles the search op: searches patterns with optional advanced mode that
     * uses real PatternMatcher parsing with a simulated variable environment.
     */
    private static void findPatterns(@NotNull HeadlessLumen headless, @NotNull JsonObject request) {
        String query = stringField(request, "query");
        if (query == null) {
            respond(error("missing query field"));
            return;
        }

        int limit = request.has("limit") ? request.get("limit").getAsInt() : 10;
        boolean advanced = !request.has("advanced") || request.get("advanced").getAsBoolean();

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("results", PatternSearch.search(query, headless.patternRegistry(), varsMap(request), advanced, limit));
        respond(response);
    }

    /**
     * Handles the check op: tests whether input text matches a type binding
     * with an optional vars map for simulating a variable environment.
     */
    private static void typeCheck(@NotNull HeadlessLumen headless, @NotNull JsonObject request) {
        String type = stringField(request, "type");
        String input = stringField(request, "input");
        if (type == null) {
            respond(error("missing type field"));
            return;
        }
        if (input == null) {
            respond(error("missing input field"));
            return;
        }

        respond(TypeChecker.check(type, input, varsMap(request), headless.typeRegistry()));
    }

    /**
     * Converts a LumenScriptException into a structured error response with per-line errors.
     */
    private static @NotNull JsonObject scriptError(String phase, @NotNull LumenScriptException e) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        if (phase != null) response.addProperty("phase", phase);
        response.add("errors", parseScriptErrors(e));
        return response;
    }

    /**
     * Converts a generic exception into a structured error response.
     */
    private static @NotNull JsonObject genericError(String phase, @NotNull Exception e) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        if (phase != null) response.addProperty("phase", phase);
        JsonArray errors = new JsonArray();
        JsonObject err = new JsonObject();
        err.addProperty("line", -1);
        err.addProperty("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        errors.add(err);
        response.add("errors", errors);
        return response;
    }

    /**
     * Splits a combined LumenScriptException message into individual per-line errors.
     */
    private static @NotNull JsonArray parseScriptErrors(@NotNull LumenScriptException e) {
        JsonArray errors = new JsonArray();
        String[] parts = e.getMessage().split("\nScript error on line ");
        if (parts.length > 1) {
            for (int i = 1; i < parts.length; i++) {
                String full = "Script error on line " + parts[i];
                JsonObject err = new JsonObject();
                err.addProperty("line", errorLine(full));
                err.addProperty("message", full);
                errors.add(err);
            }
        } else {
            JsonObject err = new JsonObject();
            err.addProperty("line", e.line());
            err.addProperty("message", e.getMessage());
            errors.add(err);
        }
        return errors;
    }

    /**
     * Extracts the line number from a "Script error on line N:" prefix.
     */
    private static int errorLine(@NotNull String msg) {
        String prefix = "Script error on line ";
        if (!msg.startsWith(prefix)) return -1;
        int colon = msg.indexOf(':', prefix.length());
        if (colon < 0) return -1;
        try {
            return Integer.parseInt(msg.substring(prefix.length(), colon).trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Converts Java compile errors into a JSON array.
     */
    private static @NotNull JsonArray compileErrors(@NotNull List<HeadlessLumen.CompileError> errors) {
        JsonArray array = new JsonArray();
        for (HeadlessLumen.CompileError err : errors) {
            JsonObject obj = new JsonObject();
            obj.addProperty("line", err.line());
            obj.addProperty("message", err.message());
            array.add(obj);
        }
        return array;
    }

    /**
     * Builds the ready message sent on server startup.
     */
    private static @NotNull JsonObject readyMessage(@NotNull HeadlessLumen headless) {
        JsonObject msg = new JsonObject();
        msg.addProperty("status", "ready");
        msg.addProperty("patterns", headless.patternCount());
        msg.addProperty("types", headless.typeRegistry().allBindings().size());
        msg.addProperty("events", EventDefRegistry.defs().size());
        return msg;
    }

    /**
     * Builds the info response.
     */
    private static @NotNull JsonObject infoMessage(@NotNull HeadlessLumen headless) {
        JsonObject msg = new JsonObject();
        msg.addProperty("ok", true);
        msg.addProperty("patterns", headless.patternCount());
        msg.addProperty("types", headless.typeRegistry().allBindings().size());
        msg.addProperty("events", EventDefRegistry.defs().size());
        return msg;
    }

    /**
     * Creates a simple error JSON response.
     */
    private static @NotNull JsonObject error(@NotNull String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", false);
        obj.addProperty("error", message);
        return obj;
    }

    /**
     * Extracts a string field from a JSON object, returning null if absent.
     */
    private static String stringField(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    /**
     * Extracts the script name from a request, defaulting to "script.luma".
     */
    private static @NotNull String scriptName(@NotNull JsonObject request) {
        String name = stringField(request, "name");
        return name != null ? name : "script.luma";
    }

    /**
     * Extracts the optional "vars" object from a request as a name to ref type id map.
     * Returns null if the field is absent.
     */
    private static @Nullable Map<String, String> varsMap(@NotNull JsonObject request) {
        if (!request.has("vars") || request.get("vars").isJsonNull()) return null;
        Map<String, String> vars = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : request.getAsJsonObject("vars").entrySet()) {
            vars.put(entry.getKey(), entry.getValue().getAsString());
        }
        return vars;
    }

    /**
     * Writes a JSON object as a single line to stdout.
     */
    private static synchronized void respond(@NotNull JsonObject json) {
        System.out.println(GSON.toJson(json));
        System.out.flush();
    }
}
