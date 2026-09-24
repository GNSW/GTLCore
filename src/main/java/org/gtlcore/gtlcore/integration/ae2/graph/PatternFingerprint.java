package org.gtlcore.gtlcore.integration.ae2.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PatternFingerprint {

    private PatternFingerprint() {}

    public static String of(IPatternDetails pattern) {
        return new Context().of(pattern);
    }

    /** Copy callback results on the server thread; workers only encode these values. */
    public static Values capture(IPatternDetails pattern) {
        var inputs = new ArrayList<Input>();
        for (var input : pattern.getInputs()) {
            var choices = new ArrayList<Choice>();
            long multiplier = input.getMultiplier();
            for (var possible : input.getPossibleInputs()) choices.add(new Choice(possible, input.getRemainingKey(possible.what())));
            inputs.add(new Input(multiplier, List.copyOf(choices)));
        }
        return new Values(pattern.getClass().getName(), pattern.getDefinition(), pattern.supportsPushInputsToExternalInventory(),
                List.copyOf(inputs), List.of(pattern.getOutputs().clone()));
    }

    public record Choice(GenericStack stack, AEKey remaining) {}

    public record Input(long multiplier, List<Choice> choices) {

        public Input {
            choices = List.copyOf(choices);
        }
    }

    public record Values(String type, AEKey definition, boolean external, List<Input> inputs, List<GenericStack> outputs) {

        public Values {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    /** Request-local immutable-key serialization cache, never a cache of mutable pattern semantics. */
    public static final class Context {

        private final Map<AEKey, String> keys = new LinkedHashMap<>(16, 0.75f, true);
        private final MessageDigest digest;
        private int retainedCharacters;

        public Context() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        public String key(AEKey key) {
            String old = keys.get(key);
            if (old != null) return old;
            String value = PatternFingerprint.key(key);
            if (value.length() <= 1_048_576) {
                // A large catalog must not permanently fill this cache with its
                // first definitions. Nearby recipes reuse the current frontier's
                // input/output keys; evict old encodings while keeping both caps.
                while (!keys.isEmpty() && (keys.size() >= 8192 || retainedCharacters + value.length() > 1_048_576)) {
                    var oldest = keys.entrySet().iterator();
                    retainedCharacters -= oldest.next().getValue().length();
                    oldest.remove();
                }
                keys.put(key, value);
                retainedCharacters += value.length();
            }
            return value;
        }

        public String hash(String value) {
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }

        public String of(IPatternDetails pattern) {
            StringBuilder text = new StringBuilder(pattern.getClass().getName());
            // Encoded pattern NBT is normally unique and can have highly colliding
            // hashes (adjacent input/output variants). Do not fill the reusable
            // material-key LRU with definitions that are serialized only once.
            text.append('|');
            canonical(pattern.getDefinition().toTagGeneric(), text);
            text.append('|').append(pattern.supportsPushInputsToExternalInventory());
            for (var input : pattern.getInputs()) {
                text.append(";i:").append(input.getMultiplier());
                for (var possible : input.getPossibleInputs()) {
                    text.append('|').append(key(possible.what())).append(':').append(possible.amount());
                    AEKey remaining = input.getRemainingKey(possible.what());
                    text.append('>').append(remaining == null ? "-" : key(remaining));
                }
            }
            for (var output : pattern.getOutputs()) text.append(";o:").append(key(output.what())).append(':').append(output.amount());
            return hash(text.toString());
        }

        public String of(Values pattern) {
            StringBuilder text = new StringBuilder(pattern.type());
            text.append('|');
            canonical(pattern.definition().toTagGeneric(), text);
            text.append('|').append(pattern.external());
            for (var input : pattern.inputs()) {
                text.append(";i:").append(input.multiplier());
                for (var choice : input.choices()) {
                    text.append('|').append(key(choice.stack().what())).append(':').append(choice.stack().amount());
                    text.append('>').append(choice.remaining() == null ? "-" : key(choice.remaining()));
                }
            }
            for (var output : pattern.outputs()) text.append(";o:").append(key(output.what())).append(':').append(output.amount());
            return hash(text.toString());
        }
    }

    public static String key(AEKey key) {
        return canonical(key.toTagGeneric());
    }

    private static String canonical(Tag tag) {
        StringBuilder value = new StringBuilder();
        canonical(tag, value);
        return value.toString();
    }

    private static void canonical(Tag tag, StringBuilder value) {
        if (tag instanceof CompoundTag compound) {
            value.append('{');
            var keys = compound.getAllKeys();
            if (keys.size() <= 2) {
                // Most encoded stack/NBT wrappers have only one or two fields.
                // Keep canonical ordering without allocating a sort array.
                var iterator = keys.iterator();
                if (iterator.hasNext()) {
                    String first = iterator.next();
                    if (iterator.hasNext()) {
                        String second = iterator.next();
                        if (first.compareTo(second) > 0) {
                            String swap = first;
                            first = second;
                            second = swap;
                        }
                        field(compound, first, value);
                        field(compound, second, value);
                    } else field(compound, first, value);
                }
            } else {
                String[] sorted = keys.toArray(String[]::new);
                Arrays.sort(sorted);
                for (String key : sorted) field(compound, key, value);
            }
            value.append('}');
        } else if (tag instanceof ListTag list) {
            value.append('[');
            for (Tag entry : list) {
                canonical(entry, value);
                value.append(';');
            }
            value.append(']');
        } else {
            value.append(tag.getId()).append(':');
            if (tag instanceof StringTag string) quoted(string.getAsString(), value);
            else if (tag instanceof NumericTag number) {
                // The same SNBT suffixes as Tag.toString(), without constructing
                // a StringTagVisitor and another builder for every scalar.
                switch (tag.getId()) {
                    case Tag.TAG_BYTE -> value.append(number.getAsByte()).append('b');
                    case Tag.TAG_SHORT -> value.append(number.getAsShort()).append('s');
                    case Tag.TAG_INT -> value.append(number.getAsInt());
                    case Tag.TAG_LONG -> value.append(number.getAsLong()).append('L');
                    case Tag.TAG_FLOAT -> value.append(number.getAsFloat()).append('f');
                    case Tag.TAG_DOUBLE -> value.append(number.getAsDouble()).append('d');
                    default -> value.append(tag);
                }
            } else value.append(tag);
        }
    }

    private static void field(CompoundTag compound, String key, StringBuilder value) {
        value.append(key.length()).append(':').append(key).append('=');
        canonical(compound.get(key), value);
    }

    /** Same delimiter selection and escaping as StringTag.quoteAndEscape. */
    private static void quoted(String text, StringBuilder value) {
        int opening = value.length();
        value.append(' ');
        char delimiter = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\\') value.append('\\');
            else if (character == '\"' || character == '\'') {
                if (delimiter == 0) delimiter = character == '\"' ? '\'' : '\"';
                if (delimiter == character) value.append('\\');
            }
            value.append(character);
        }
        if (delimiter == 0) delimiter = '\"';
        value.setCharAt(opening, delimiter);
        value.append(delimiter);
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
