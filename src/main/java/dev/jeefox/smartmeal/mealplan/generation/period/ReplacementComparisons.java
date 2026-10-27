package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compare complete period purchases, including package rounding and usable pantry stock. */
public final class ReplacementComparisons {
    private ReplacementComparisons() {}

    private static Map<Long, BasketItem> purchases(Basket b) {
        var result = new TreeMap<Long, BasketItem>();
        b.items().stream()
                .filter(i -> i.needToBuyG() != null && i.needToBuyG().signum() > 0)
                .forEach(i -> result.put(i.productId(), i));
        return result;
    }

    public static Alternative compare(
            View old,
            PlanningContext context,
            Selection original,
            Selection next,
            Document baseline,
            Document changed) {
        var before = purchases(baseline.shopping());
        var after = purchases(changed.shopping());
        var added =
                after.values().stream()
                        .filter(i -> !before.containsKey(i.productId()))
                        .map(i -> new ChangedProduct(i.productId(), i.productName()))
                        .toList();
        var removed =
                before.values().stream()
                        .filter(i -> !after.containsKey(i.productId()))
                        .map(i -> new ChangedProduct(i.productId(), i.productName()))
                        .toList();
        var cost = changed.shopping().estimatedTotal();
        var delta =
                cost == null || baseline.shopping().estimatedTotal() == null
                        ? null
                        : cost.subtract(baseline.shopping().estimatedTotal());
        int saved = original.recipe().preparationTimeMin() - next.recipe().preparationTimeMin();
        var goals = new ArrayList<ReplacementGoal>();
        if (added.isEmpty()) goals.add(ReplacementGoal.SAME_PRODUCTS);
        if (delta != null && delta.signum() < 0) goals.add(ReplacementGoal.CHEAPER);
        if (saved > 0) goals.add(ReplacementGoal.FASTER);
        var day =
                changed.days().stream()
                        .filter(d -> d.date().equals(original.date()))
                        .findFirst()
                        .orElseThrow();
        var allocated =
                day.entries().stream()
                        .filter(e -> e.id().equals(original.id()))
                        .findFirst()
                        .orElseThrow();
        var impact =
                new ReplacementImpact(
                        added,
                        removed,
                        before.size(),
                        after.size(),
                        saved,
                        old.result().options().usePantry(),
                        context.pantry().revision(),
                        baseline.shopping().estimatedTotal(),
                        context.asOf(),
                        allocated.personPortions());
        String key =
                hash(
                        old.id()
                                + ":"
                                + old.revision()
                                + ":"
                                + context.fingerprint()
                                + ":"
                                + context.pantry().revision()
                                + ":"
                                + next.recipeFingerprint()
                                + ":"
                                + next.portions().stripTrailingZeros().toPlainString()
                                + ":"
                                + canonical(baseline.shopping())
                                + ":"
                                + canonical(changed.shopping()));
        return new Alternative(
                next.recipe().recipeId(),
                next.recipe().name(),
                next.portions(),
                next.recipe().preparationTimeMin(),
                day.nutrition(),
                cost,
                delta,
                changed.shopping().budgetStatus(),
                changed.shopping().priceQuality(),
                next.recipeFingerprint(),
                next.recipe(),
                goals,
                impact,
                key);
    }

    private static final tools.jackson.databind.ObjectMapper JSON =
            new tools.jackson.databind.ObjectMapper();

    private static String canonical(Object value) {
        return canonicalNode(JSON.valueToTree(value));
    }

    private static String canonicalNode(tools.jackson.databind.JsonNode node) {
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        if (node.isObject())
            return "{"
                    + node.properties().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(
                                    e ->
                                            JSON.writeValueAsString(e.getKey())
                                                    + ":"
                                                    + canonicalNode(e.getValue()))
                            .collect(java.util.stream.Collectors.joining(","))
                    + "}";
        if (node.isArray()) {
            var values = new ArrayList<String>();
            node.forEach(n -> values.add(canonicalNode(n)));
            return "[" + String.join(",", values) + "]";
        }
        return node.toString();
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Comparator<Alternative> order(ReplacementGoal goal) {
        Comparator<Alternative> cost =
                Comparator.comparing(
                        Alternative::newPurchaseCost, Comparator.nullsLast(BigDecimal::compareTo));
        Comparator<Alternative> added =
                Comparator.comparingInt(a -> a.impact().addedProducts().size());
        Comparator<Alternative> time = Comparator.comparingInt(Alternative::preparationTimeMin);
        var first =
                switch (goal) {
                    case SAME_PRODUCTS -> added.thenComparing(cost).thenComparing(time);
                    case CHEAPER -> cost.thenComparing(added).thenComparing(time);
                    case FASTER -> time.thenComparing(added).thenComparing(cost);
                };
        return first.thenComparing(Alternative::recipeId)
                .thenComparing(Alternative::candidateKey)
                .thenComparing(Alternative::portions);
    }

    public static List<Alternative> shortlist(List<Alternative> all) {
        var result = new LinkedHashMap<String, Alternative>();
        for (var goal : ReplacementGoal.values()) {
            var identities = new HashSet<String>();
            int count = 0;
            for (var a :
                    all.stream()
                            .filter(x -> x.goals().contains(goal))
                            .sorted(order(goal))
                            .toList()) {
                // Multiple portion sizes are not separate dish suggestions.
                if (!identities.add(identity(a.proposedRecipe()))) continue;
                result.putIfAbsent(a.candidateKey() + ":" + a.portions(), a);
                if (++count == 3) break;
            }
        }
        return List.copyOf(result.values());
    }

    public static String identity(dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot recipe) {
        return recipe.components().isEmpty()
                ? recipe.recipeId().toString()
                : recipe.components().stream()
                        .map(c -> c.recipe().recipeId().toString())
                        .sorted()
                        .toList()
                        .toString();
    }
}
