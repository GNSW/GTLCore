// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;

/** Separates requested external material from outputs of accepted physical batches. */
public final class OutputObligations<K> {

    /** A guaranteed return; amount is the total for this physical dispatch, not a per-run amount. */
    public record ReturnChoice<K>(int inputSlot, K predicted, Set<K> allowed, long amount) {
        public ReturnChoice {
            if (inputSlot < 0 || amount <= 0) throw new IllegalArgumentException("Invalid return choice");
            Objects.requireNonNull(predicted);
            allowed = Set.copyOf(allowed);
            if (!allowed.contains(predicted)) throw new IllegalArgumentException("Predicted return is not allowed");
        }
    }

    public record Flight<K>(long id, String recipe, long runs, Map<K, Long> remaining, boolean ambiguous,
                            List<ReturnChoice<K>> choices) {

        public Flight(long id, String recipe, long runs, Map<K, Long> remaining, boolean ambiguous) {
            this(id, recipe, runs, remaining, ambiguous, List.of());
        }

        public Flight {
            if (id <= 0 || runs <= 0 || recipe.isEmpty()) throw new IllegalArgumentException("Invalid output owner");
            remaining = GraphRecipe.amounts(remaining);
            choices = List.copyOf(choices);
            validate(remaining, choices);
        }
    }

    /** predicted is the obligation retired; the caller must credit only the actual received key. */
    public record Receipt<K>(K predicted, long amount, long preparingAmount, boolean changed) {}

    public record Snapshot<K>(Map<K, Long> external, List<Flight<K>> flights, long nextId) {

        public Snapshot {
            external = GraphRecipe.amounts(external);
            flights = List.copyOf(flights);
        }
    }

    private final Map<K, Long> external = new LinkedHashMap<>();
    private final Map<Long, Ticket<K>> flights = new LinkedHashMap<>();
    private final Map<K, Deque<Ticket<K>>> byKey = new HashMap<>();
    private final Map<K, Deque<ChoiceOwner<K>>> alternatives = new HashMap<>();
    private long nextId = 1;

    public OutputObligations(Map<K, Long> external) {
        this.external.putAll(GraphRecipe.amounts(external));
    }

    public OutputObligations(Snapshot<K> state) {
        external.putAll(state.external());
        for (Flight<K> flight : state.flights()) add(flight);
        if (state.nextId() < nextId) throw new IllegalArgumentException("Output owner sequence moved backwards");
        nextId = state.nextId();
    }

    public void dispatch(String recipe, long runs, Map<K, Long> outputs, boolean ambiguous) {
        dispatch(recipe, runs, outputs, ambiguous, List.of());
    }

    public long dispatch(String recipe, long runs, Map<K, Long> outputs, boolean ambiguous, List<ReturnChoice<K>> choices) {
        if (!capacityAvailable()) throw new IllegalStateException("Too many in-flight batches");
        if (!compatible(outputs, choices)) throw new IllegalStateException("Overlapping return domains");
        long id = nextId;
        add(new Flight<>(id, recipe, runs, outputs, ambiguous, choices));
        return id;
    }

    /** Identical return contracts are interchangeable; different overlapping domains are not. */
    public boolean compatible(Map<K, Long> outputs, List<ReturnChoice<K>> choices) {
        if (choices.isEmpty() && alternatives.isEmpty()) return true;
        validate(outputs, choices);
        Map<K, Long> fixed = new HashMap<>(outputs);
        for (var choice : choices) reduce(fixed, choice.predicted(), choice.amount());
        for (K key : fixed.keySet()) if (alternatives.containsKey(key)) return false;
        for (var choice : choices) for (K key : choice.allowed()) {
            var group = alternatives.get(key);
            if (external.containsKey(key) || group == null && byKey.containsKey(key) ||
                    group != null && !interchangeable(choice, group.getFirst().choice)) return false;
        }
        return true;
    }

    private static <K> boolean interchangeable(ReturnChoice<K> left, ReturnChoice<K> right) {
        return left.predicted().equals(right.predicted()) && left.allowed().equals(right.allowed());
    }

    private static <K> void validate(Map<K, Long> outputs, List<ReturnChoice<K>> choices) {
        if (choices.isEmpty()) return;
        Map<K, ReturnChoice<K>> domain = new HashMap<>();
        Map<K, Long> fixed = new HashMap<>(outputs);
        for (var choice : choices) {
            for (K key : choice.allowed()) {
                var previous = domain.putIfAbsent(key, choice);
                if (previous != null && !interchangeable(previous, choice)) throw new IllegalArgumentException("Overlapping return choices");
            }
            long left = fixed.getOrDefault(choice.predicted(), 0L) - choice.amount();
            if (left < 0) throw new IllegalArgumentException("Return exceeds predicted output");
            if (left == 0) fixed.remove(choice.predicted());
            else fixed.put(choice.predicted(), left);
        }
        for (K key : fixed.keySet()) if (domain.containsKey(key)) throw new IllegalArgumentException("Return overlaps fixed output");
    }

    private void add(Flight<K> flight) {
        if (flight.id() == Long.MAX_VALUE) throw new IllegalArgumentException("Output owner sequence exhausted");
        var ticket = new Ticket<>(flight);
        if (!compatible(ticket.remaining, flight.choices())) throw new IllegalArgumentException("Overlapping saved return owners");
        if (flights.putIfAbsent(flight.id(), ticket) != null) throw new IllegalArgumentException("Duplicate output owner");
        for (K key : ticket.remaining.keySet()) byKey.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(ticket);
        for (var choice : flight.choices()) {
            var owner = new ChoiceOwner<>(ticket, choice);
            ticket.choices.add(owner);
            var group = alternatives.get(choice.predicted());
            if (group == null) {
                group = new ArrayDeque<>();
                for (K key : choice.allowed()) alternatives.put(key, group);
            }
            group.addLast(owner);
        }
        if (ticket.remaining.isEmpty()) flights.remove(flight.id());
        nextId = Math.max(nextId, flight.id() + 1);
    }

    /** Called only after an actual return, never on a simulated insertion. */
    public void returned(K key, long amount) {
        receive(key, amount, 0);
    }

    public Receipt<K> receive(K key, long amount, long preparingId) {
        CheckedAmounts.nonNegative(amount);
        var group = alternatives.get(key);
        if (group != null) {
            if (amount > receivable(key)) throw new IllegalStateException("Return exceeds choice owners");
            ReturnChoice<K> contract = group.getFirst().choice;
            K predicted = contract.predicted();
            long remaining = amount, preparing = 0;
            while (remaining > 0) {
                var variable = group.getFirst();
                long received = Math.min(remaining, variable.remaining);
                reduce(variable.ticket.remaining, predicted, received);
                variable.remaining -= received;
                remaining -= received;
                if (variable.ticket.id == preparingId) preparing = CheckedAmounts.add(preparing, received);
                if (variable.remaining == 0) {
                    group.removeFirst();
                    variable.ticket.choices.remove(variable);
                }
                if (!variable.ticket.remaining.containsKey(predicted)) {
                    var queue = byKey.get(predicted);
                    queue.remove(variable.ticket);
                    if (queue.isEmpty()) byKey.remove(predicted);
                }
                if (variable.ticket.remaining.isEmpty()) flights.remove(variable.ticket.id);
            }
            if (group.isEmpty()) for (K allowed : contract.allowed()) alternatives.remove(allowed, group);
            return new Receipt<>(predicted, amount, preparing, !predicted.equals(key));
        }
        long original = amount, preparing = 0;
        long supplied = Math.min(amount, external.getOrDefault(key, 0L));
        reduce(external, key, supplied);
        amount -= supplied;
        var queue = byKey.get(key);
        while (amount > 0 && queue != null && !queue.isEmpty()) {
            Ticket<K> ticket = queue.peek();
            long returned = Math.min(amount, ticket.remaining.getOrDefault(key, 0L));
            if (ticket.id == preparingId) preparing = CheckedAmounts.add(preparing, returned);
            reduce(ticket.remaining, key, returned);
            amount -= returned;
            if (!ticket.remaining.containsKey(key)) queue.removeFirst();
            if (ticket.remaining.isEmpty()) {
                flights.remove(ticket.id);
            }
        }
        if (queue != null && queue.isEmpty()) byKey.remove(key);
        if (amount != 0) throw new IllegalStateException("Return has no output owner");
        return new Receipt<>(key, original, preparing, false);
    }

    /** Maximum real receipt; alternative keys do not add inventory to the exact forecast. */
    public long receivable(K key) {
        var group = alternatives.get(key);
        if (group == null) return CheckedAmounts.add(external(key), inFlight(key));
        long amount = 0;
        for (var owner : group) amount = CheckedAmounts.add(amount, owner.remaining);
        return amount;
    }

    public Set<K> receivableKeys() {
        Set<K> keys = new LinkedHashSet<>(external.keySet());
        keys.addAll(byKey.keySet());
        keys.addAll(alternatives.keySet());
        return Set.copyOf(keys);
    }

    public boolean hasChoices() { return !alternatives.isEmpty(); }

    public boolean variableKey(K key) { return alternatives.containsKey(key); }

    /** Commit a successful handoff. A synchronous receipt may already have retired this owner. */
    public void confirm(long id) {
        Ticket<K> ticket = flights.get(id);
        if (ticket != null) ticket.ambiguous = false;
    }

    /** Only the runtime's no-receipts rejection path may discard a prepared owner. */
    public void reject(long id) {
        Ticket<K> ticket = flights.remove(id);
        if (ticket == null) return;
        for (K key : ticket.remaining.keySet()) {
            var queue = byKey.get(key);
            queue.remove(ticket);
            if (queue.isEmpty()) byKey.remove(key);
        }
        for (var choice : ticket.choices) {
            var group = alternatives.get(choice.choice.predicted());
            group.remove(choice);
            if (group.isEmpty()) for (K key : choice.choice.allowed()) alternatives.remove(key, group);
        }
    }

    private static <K> void reduce(Map<K, Long> map, K key, long count) {
        long remaining = map.getOrDefault(key, 0L) - count;
        if (remaining < 0) throw new IllegalStateException("Negative output obligation");
        if (remaining == 0) map.remove(key);
        else map.put(key, remaining);
    }

    public Map<K, Long> external() {
        return GraphRecipe.amounts(external);
    }

    public void addExternal(Map<K, Long> additional) {
        for (K key : additional.keySet()) if (alternatives.containsKey(key)) throw new IllegalStateException("External supply overlaps return domain");
        additional.forEach((key, count) -> external.merge(key, CheckedAmounts.nonNegative(count), CheckedAmounts::add));
    }

    /** Postpone unreceived supply to make room for a physical batch's returns. */
    public void deferExternal(K key, long amount) {
        reduce(external, key, CheckedAmounts.nonNegative(amount));
    }

    public long external(K key) {
        return external.getOrDefault(key, 0L);
    }

    public long inFlight(K key) {
        long count = 0;
        var tickets = byKey.get(key);
        if (tickets != null) for (Ticket<K> ticket : tickets) count = CheckedAmounts.add(count, ticket.remaining.getOrDefault(key, 0L));
        return count;
    }

    public Map<K, Long> inFlight() {
        Map<K, Long> result = new LinkedHashMap<>();
        for (Ticket<K> ticket : flights.values()) ticket.remaining.forEach((key, count) -> result.merge(key, count, CheckedAmounts::add));
        return GraphRecipe.amounts(result);
    }

    public Map<K, Long> all() {
        Map<K, Long> result = new LinkedHashMap<>(external);
        inFlight().forEach((key, count) -> result.merge(key, count, CheckedAmounts::add));
        return GraphRecipe.amounts(result);
    }

    public boolean capacityAvailable() {
        return flights.size() < 4096 && nextId < Long.MAX_VALUE;
    }

    public boolean hasFlights() {
        return !flights.isEmpty();
    }

    public boolean ambiguous() {
        return flights.values().stream().anyMatch(ticket -> ticket.ambiguous);
    }

    public void clear() {
        external.clear();
        flights.clear();
        byKey.clear();
        alternatives.clear();
    }

    public Snapshot<K> snapshot() {
        return new Snapshot<>(external, flights.values().stream().map(ticket -> new Flight<>(ticket.id, ticket.recipe, ticket.runs, ticket.remaining, ticket.ambiguous,
                ticket.choices.stream().map(owner -> new ReturnChoice<>(owner.choice.inputSlot(), owner.choice.predicted(), owner.choice.allowed(), owner.remaining)).toList())).toList(), nextId);
    }

    private static final class Ticket<K> {

        final long id, runs;
        final String recipe;
        boolean ambiguous;
        final Map<K, Long> remaining;
        final List<ChoiceOwner<K>> choices;

        Ticket(Flight<K> flight) {
            id = flight.id();
            runs = flight.runs();
            recipe = flight.recipe();
            ambiguous = flight.ambiguous();
            remaining = new LinkedHashMap<>(flight.remaining());
            choices = flight.choices().isEmpty() ? List.of() : new ArrayList<>();
        }
    }

    private static final class ChoiceOwner<K> {
        final Ticket<K> ticket;
        final ReturnChoice<K> choice;
        long remaining;

        ChoiceOwner(Ticket<K> ticket, ReturnChoice<K> choice) {
            this.ticket = ticket;
            this.choice = choice;
            remaining = choice.amount();
        }
    }
}
