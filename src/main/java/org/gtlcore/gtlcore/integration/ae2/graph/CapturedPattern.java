package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import org.cgse.core.CapturedRecipe;
import org.cgse.core.PlanningBudget;
import org.cgse.core.StateFamily;

import java.util.Iterator;
import java.util.List;

/** AE value conversion; combination expansion operates only on the captured core values. */
public final class CapturedPattern {

    static final int MAX_VARIANTS = CapturedRecipe.MAX_VARIANTS;

    public record Candidate(GenericStack stack, AEKey remaining, boolean configuration, boolean reusable,
                            StateFamily<AEKey> family) {

        public Candidate(GenericStack stack, AEKey remaining, boolean configuration, boolean reusable) {
            this(stack, remaining, configuration, reusable, null);
        }

        public Candidate(GenericStack stack, AEKey remaining, boolean configuration) {
            this(stack, remaining, configuration, false);
        }
    }

    public record Input(long multiplier, List<Candidate> candidates, CapturedRecipe.InputMode mode) {

        public Input(long multiplier, List<Candidate> candidates) {
            this(multiplier, candidates, CapturedRecipe.InputMode.LEGACY);
        }

        public Input {
            candidates = List.copyOf(candidates);
        }
    }

    private final CapturedRecipe<AEKey> delegate;

    public CapturedPattern(List<Input> inputs, List<GenericStack> outputs, boolean external, boolean bounded) {
        delegate = new CapturedRecipe<>(inputs.stream().map(input -> new CapturedRecipe.Input<>(input.multiplier(),
                input.candidates().stream().map(candidate -> new CapturedRecipe.Candidate<>(amount(candidate.stack()),
                        candidate.remaining(), candidate.configuration(), candidate.reusable(), candidate.family())).toList(),
                input.mode()))
                .toList(),
                outputs.stream().map(CapturedPattern::amount).toList(), external, bounded);
    }

    private static CapturedRecipe.Amount<AEKey> amount(GenericStack stack) {
        return new CapturedRecipe.Amount<>(stack.what(), stack.amount());
    }

    public int size() {
        return delegate.size();
    }

    public boolean bounded() {
        return delegate.bounded();
    }

    public Iterator<AEKey> dependencies() {
        return delegate.candidateDependencies();
    }

    CapturedRecipe<AEKey> core() {
        return delegate;
    }

    public Expansion expand(PlanningBudget budget) {
        return new Expansion(delegate.expand(budget));
    }

    public final class Expansion {

        private final CapturedRecipe<AEKey>.Expansion delegate;

        private Expansion(CapturedRecipe<AEKey>.Expansion delegate) {
            this.delegate = delegate;
        }

        public boolean hasNext() {
            return delegate.hasNext();
        }

        public CapturedPatternCatalog.Recipe next() {
            var recipe = delegate.next();
            return new CapturedPatternCatalog.Recipe(recipe.slots(), recipe.outputs());
        }
    }
}
