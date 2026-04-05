package dev.lumenlang.lumen.headless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lumenlang.lumen.pipeline.codegen.TypeEnv;
import dev.lumenlang.lumen.pipeline.conditions.registry.RegisteredCondition;
import dev.lumenlang.lumen.pipeline.language.match.PatternMatcher;
import dev.lumenlang.lumen.pipeline.language.pattern.Pattern;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternPart;
import dev.lumenlang.lumen.pipeline.language.pattern.PatternRegistry;
import dev.lumenlang.lumen.pipeline.language.pattern.Placeholder;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredBlock;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredExpression;
import dev.lumenlang.lumen.pipeline.language.pattern.registered.RegisteredPattern;
import dev.lumenlang.lumen.pipeline.language.tokenization.Token;
import dev.lumenlang.lumen.pipeline.language.tokenization.Tokenizer;
import dev.lumenlang.lumen.pipeline.loop.RegisteredLoop;
import dev.lumenlang.lumen.pipeline.typebinding.TypeRegistry;
import dev.lumenlang.lumen.pipeline.var.RefType;
import dev.lumenlang.lumen.pipeline.var.VarRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pattern search engine with two modes: advanced (real PatternMatcher parsing)
 * and fuzzy (keyword overlap, edit distance, order preservation, placeholder fit).
 * Advanced mode falls back to fuzzy when no real matches are found.
 */
public final class PatternSearch {

    private PatternSearch() {
    }

    /**
     * Searches all registered patterns for the closest matches to the given query.
     * In advanced mode, uses the real PatternMatcher to find exact pattern matches
     * with a pre-populated TypeEnv. Falls back to fuzzy matching if advanced yields nothing.
     *
     * @param query    the Lumen code fragment to match against
     * @param registry the pattern registry containing all patterns
     * @param vars     declared variables as name to ref type id, used in advanced mode
     * @param advanced whether to use real PatternMatcher parsing (default true)
     * @param limit    the maximum number of results to return
     * @return a JSON array of scored pattern matches
     */
    public static @NotNull JsonArray search(@NotNull String query, @NotNull PatternRegistry registry, @Nullable Map<String, String> vars, boolean advanced, int limit) {
        List<Token> tokens = tokenize(query);
        if (tokens.isEmpty()) return new JsonArray();

        if (advanced) {
            JsonArray advancedResults = advancedSearch(tokens, registry, vars, limit);
            if (!advancedResults.isEmpty()) return advancedResults;
        }

        return fuzzySearch(tokens, registry, limit);
    }

    /**
     * Runs real PatternMatcher.match() against all patterns. For each known RefType,
     * creates a fresh TypeEnv where all input tokens are defined as that ref type,
     * then tests all patterns. This allows type bindings that call lookupVar
     * (like PLAYER, ENTITY) to succeed regardless of which RefType a placeholder expects.
     */
    private static @NotNull JsonArray advancedSearch(@NotNull List<Token> tokens, @NotNull PatternRegistry registry, @Nullable Map<String, String> vars, int limit) {
        TypeRegistry types = registry.getTypeRegistry();
        List<ScoredPattern> results = new ArrayList<>();
        List<String> queryWords = tokens.stream().map(t -> t.text().toLowerCase()).toList();

        for (RefType rt : RefType.values()) {
            TypeEnv env = TypeChecker.populatedEnv(vars);
            for (Token token : tokens) {
                if (env.lookupVar(token.text()) == null) env.defineVar(token.text(), new VarRef(rt, token.text()));
            }

            collectMatches(results, registry.getStatements(), "statement", tokens, types, env, queryWords);
            collectBlockMatches(results, registry.getBlocks(), tokens, types, env, queryWords);
            collectExprMatches(results, registry.getExpressions(), tokens, types, env, queryWords);
            collectCondMatches(results, registry.getConditionRegistry().getConditions(), tokens, types, env, queryWords);
            collectLoopMatches(results, registry.getLoopRegistry().getLoops(), tokens, types, env, queryWords);
        }

        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(results.size(), limit); i++) arr.add(results.get(i).asJson());
        return arr;
    }

    /**
     * Collects statement pattern matches, skipping already-found patterns.
     */
    private static void collectMatches(@NotNull List<ScoredPattern> results, @NotNull List<RegisteredPattern> patterns, @NotNull String type, @NotNull List<Token> tokens, @NotNull TypeRegistry types, @NotNull TypeEnv env, @NotNull List<String> queryWords) {
        for (RegisteredPattern rp : patterns) {
            if (alreadyFound(results, rp.pattern()) || !tryMatch(tokens, rp.pattern(), types, env)) continue;
            results.add(new ScoredPattern(rp.pattern(), type, rp.meta().description(), rp.meta().category() != null ? rp.meta().category().name() : null, 2.0, queryWords));
        }
    }

    /**
     * Collects block pattern matches, skipping already-found patterns.
     */
    private static void collectBlockMatches(@NotNull List<ScoredPattern> results, @NotNull List<RegisteredBlock> blocks, @NotNull List<Token> tokens, @NotNull TypeRegistry types, @NotNull TypeEnv env, @NotNull List<String> queryWords) {
        for (RegisteredBlock rb : blocks) {
            if (alreadyFound(results, rb.pattern()) || !tryMatch(tokens, rb.pattern(), types, env)) continue;
            results.add(new ScoredPattern(rb.pattern(), "block", rb.meta().description(), rb.meta().category() != null ? rb.meta().category().name() : null, 2.0, queryWords));
        }
    }

    /**
     * Collects expression pattern matches, skipping already-found patterns.
     */
    private static void collectExprMatches(@NotNull List<ScoredPattern> results, @NotNull List<RegisteredExpression> exprs, @NotNull List<Token> tokens, @NotNull TypeRegistry types, @NotNull TypeEnv env, @NotNull List<String> queryWords) {
        for (RegisteredExpression re : exprs) {
            if (alreadyFound(results, re.pattern()) || !tryMatch(tokens, re.pattern(), types, env)) continue;
            results.add(new ScoredPattern(re.pattern(), "expression", re.meta().description(), re.meta().category() != null ? re.meta().category().name() : null, 2.0, queryWords));
        }
    }

    /**
     * Collects condition pattern matches, skipping already-found patterns.
     */
    private static void collectCondMatches(@NotNull List<ScoredPattern> results, @NotNull List<RegisteredCondition> conds, @NotNull List<Token> tokens, @NotNull TypeRegistry types, @NotNull TypeEnv env, @NotNull List<String> queryWords) {
        for (RegisteredCondition rc : conds) {
            if (alreadyFound(results, rc.pattern()) || !tryMatch(tokens, rc.pattern(), types, env)) continue;
            results.add(new ScoredPattern(rc.pattern(), "condition", rc.meta().description(), rc.meta().category() != null ? rc.meta().category().name() : null, 2.0, queryWords));
        }
    }

    /**
     * Collects loop pattern matches, skipping already-found patterns.
     */
    private static void collectLoopMatches(@NotNull List<ScoredPattern> results, @NotNull List<RegisteredLoop> loops, @NotNull List<Token> tokens, @NotNull TypeRegistry types, @NotNull TypeEnv env, @NotNull List<String> queryWords) {
        for (RegisteredLoop rl : loops) {
            if (alreadyFound(results, rl.pattern()) || !tryMatch(tokens, rl.pattern(), types, env)) continue;
            results.add(new ScoredPattern(rl.pattern(), "loop", rl.meta().description(), rl.meta().category() != null ? rl.meta().category().name() : null, 2.0, queryWords));
        }
    }

    /**
     * Checks if a pattern has already been found in a previous RefType iteration.
     */
    private static boolean alreadyFound(@NotNull List<ScoredPattern> results, @NotNull Pattern pattern) {
        for (ScoredPattern sp : results) {
            if (sp.pattern().raw().equals(pattern.raw())) return true;
        }
        return false;
    }

    /**
     * Attempts a real pattern match, catching exceptions from type bindings.
     */
    private static boolean tryMatch(@NotNull List<Token> tokens, @NotNull Pattern pattern, @NotNull TypeRegistry types, @NotNull TypeEnv env) {
        try {
            return PatternMatcher.match(tokens, pattern, types, env) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Falls back to fuzzy scoring when advanced matching yields no results.
     */
    private static @NotNull JsonArray fuzzySearch(@NotNull List<Token> tokens, @NotNull PatternRegistry registry, int limit) {
        List<String> queryWords = tokens.stream().map(t -> t.text().toLowerCase()).toList();
        List<ScoredPattern> scored = new ArrayList<>();

        for (RegisteredPattern rp : registry.getStatements()) {
            double s = fuzzyScore(queryWords, rp.pattern());
            if (s > 0)
                scored.add(new ScoredPattern(rp.pattern(), "statement", rp.meta().description(), rp.meta().category() != null ? rp.meta().category().name() : null, s, queryWords));
        }
        for (RegisteredBlock rb : registry.getBlocks()) {
            double s = fuzzyScore(queryWords, rb.pattern());
            if (s > 0)
                scored.add(new ScoredPattern(rb.pattern(), "block", rb.meta().description(), rb.meta().category() != null ? rb.meta().category().name() : null, s, queryWords));
        }
        for (RegisteredExpression re : registry.getExpressions()) {
            double s = fuzzyScore(queryWords, re.pattern());
            if (s > 0)
                scored.add(new ScoredPattern(re.pattern(), "expression", re.meta().description(), re.meta().category() != null ? re.meta().category().name() : null, s, queryWords));
        }
        for (RegisteredCondition rc : registry.getConditionRegistry().getConditions()) {
            double s = fuzzyScore(queryWords, rc.pattern());
            if (s > 0)
                scored.add(new ScoredPattern(rc.pattern(), "condition", rc.meta().description(), rc.meta().category() != null ? rc.meta().category().name() : null, s, queryWords));
        }
        for (RegisteredLoop rl : registry.getLoopRegistry().getLoops()) {
            double s = fuzzyScore(queryWords, rl.pattern());
            if (s > 0)
                scored.add(new ScoredPattern(rl.pattern(), "loop", rl.meta().description(), rl.meta().category() != null ? rl.meta().category().name() : null, s, queryWords));
        }

        scored.sort(Comparator.comparingDouble(ScoredPattern::score).reversed());

        JsonArray results = new JsonArray();
        for (int i = 0; i < Math.min(scored.size(), limit); i++) results.add(scored.get(i).asJson());
        return results;
    }

    /**
     * Computes a combined fuzzy score from keyword matching, edit distance,
     * order preservation, and placeholder slot fit.
     */
    private static double fuzzyScore(@NotNull List<String> queryWords, @NotNull Pattern pattern) {
        List<String> literals = extractLiterals(pattern.parts());
        List<Placeholder> placeholders = extractPlaceholders(pattern.parts());
        if (literals.isEmpty() && placeholders.isEmpty()) return 0;
        return (literalScore(queryWords, literals) * 0.45) + (editDistanceScore(queryWords, literals) * 0.25) + (orderScore(queryWords, literals) * 0.15) + (placeholderFit(queryWords, literals, placeholders) * 0.15);
    }

    /**
     * Computes the fraction of pattern literals found exactly in the query words.
     */
    private static double literalScore(@NotNull List<String> queryWords, @NotNull List<String> literals) {
        if (literals.isEmpty()) return 0;
        int matched = 0;
        for (String lit : literals) {
            for (String qw : queryWords) {
                if (qw.equals(lit)) {
                    matched++;
                    break;
                }
            }
        }
        return (double) matched / literals.size();
    }

    /**
     * Computes a fuzzy match score using edit distance for near-miss keywords.
     * Tokens within edit distance 2 of a pattern literal contribute a partial score.
     */
    private static double editDistanceScore(@NotNull List<String> queryWords, @NotNull List<String> literals) {
        if (literals.isEmpty()) return 0;
        double total = 0;
        for (String lit : literals) {
            double best = 0;
            for (String qw : queryWords) {
                if (qw.equals(lit)) {
                    best = 1.0;
                    break;
                }
                int dist = editDistance(qw, lit);
                int maxLen = Math.max(qw.length(), lit.length());
                if (maxLen > 0 && dist <= 2) {
                    best = Math.max(best, 1.0 - ((double) dist / maxLen));
                }
            }
            total += best;
        }
        return total / literals.size();
    }

    /**
     * Rewards patterns where matched literals appear in the same order as in the query.
     * Uses longest common subsequence of matched literals.
     */
    private static double orderScore(@NotNull List<String> queryWords, @NotNull List<String> literals) {
        if (literals.size() <= 1) return 1.0;
        int lcsLen = longestCommonSubsequence(queryWords, literals);
        return (double) lcsLen / literals.size();
    }

    /**
     * Evaluates how well the non-literal query tokens fit the pattern's placeholder slots.
     * A perfect fit means the number of remaining tokens matches the expected placeholder count.
     */
    private static double placeholderFit(@NotNull List<String> queryWords, @NotNull List<String> literals, @NotNull List<Placeholder> placeholders) {
        if (placeholders.isEmpty()) return queryWords.size() == literals.size() ? 1.0 : 0.5;
        int unmatchedTokens = 0;
        for (String qw : queryWords) {
            boolean isLiteral = false;
            for (String lit : literals) {
                if (qw.equals(lit)) {
                    isLiteral = true;
                    break;
                }
            }
            if (!isLiteral) unmatchedTokens++;
        }
        if (unmatchedTokens == 0) return 0.3;
        int diff = Math.abs(unmatchedTokens - placeholders.size());
        return Math.max(0, 1.0 - (diff * 0.3));
    }

    /**
     * Extracts all literal text from pattern parts recursively, in order.
     */
    private static @NotNull List<String> extractLiterals(@NotNull List<PatternPart> parts) {
        List<String> result = new ArrayList<>();
        for (PatternPart part : parts) {
            if (part instanceof PatternPart.Literal lit) {
                result.add(lit.text());
            } else if (part instanceof PatternPart.FlexLiteral flex) {
                result.add(flex.forms().get(0));
            } else if (part instanceof PatternPart.Group group) {
                for (List<PatternPart> alt : group.alternatives()) result.addAll(extractLiterals(alt));
            }
        }
        return result;
    }

    /**
     * Extracts all placeholders from pattern parts recursively.
     */
    private static @NotNull List<Placeholder> extractPlaceholders(@NotNull List<PatternPart> parts) {
        List<Placeholder> result = new ArrayList<>();
        for (PatternPart part : parts) {
            if (part instanceof PatternPart.PlaceholderPart pp) {
                result.add(pp.ph());
            } else if (part instanceof PatternPart.Group group) {
                for (List<PatternPart> alt : group.alternatives()) result.addAll(extractPlaceholders(alt));
            }
        }
        return result;
    }

    /**
     * Computes length of the longest common subsequence between two string lists.
     */
    private static int longestCommonSubsequence(@NotNull List<String> a, @NotNull List<String> b) {
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[a.size()][b.size()];
    }

    /**
     * Computes Levenshtein edit distance between two strings.
     */
    private static int editDistance(@NotNull String a, @NotNull String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    /**
     * Tokenizes a query string into tokens, returning an empty list if tokenization fails.
     */
    private static @NotNull List<Token> tokenize(@NotNull String query) {
        try {
            return new Tokenizer().tokenize(query).get(0).tokens();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Holds a scored pattern match result with all metadata needed for the JSON response.
     */
    private record ScoredPattern(@NotNull Pattern pattern, @NotNull String type, String description, String category,
                                 double score, @NotNull List<String> queryWords) {

        /**
         * Converts this scored pattern to a JSON object for the response.
         */
        private @NotNull JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("pattern", pattern.raw());
            obj.addProperty("type", type);
            obj.addProperty("score", Math.round(score * 1000.0) / 1000.0);
            if (description != null) obj.addProperty("description", description);
            if (category != null) obj.addProperty("category", category);

            List<String> literals = extractLiterals(pattern.parts());
            JsonArray matched = new JsonArray();
            JsonArray missing = new JsonArray();
            for (String lit : literals) {
                boolean found = false;
                for (String qw : queryWords) {
                    if (qw.equals(lit) || editDistance(qw, lit) <= 2) {
                        found = true;
                        break;
                    }
                }
                if (found) matched.add(lit);
                else missing.add(lit);
            }
            obj.add("matchedKeywords", matched);
            obj.add("missingKeywords", missing);

            JsonArray placeholders = new JsonArray();
            for (Placeholder ph : extractPlaceholders(pattern.parts())) {
                JsonObject p = new JsonObject();
                p.addProperty("name", ph.name());
                p.addProperty("type", ph.typeId());
                placeholders.add(p);
            }
            obj.add("placeholders", placeholders);

            return obj;
        }
    }
}
