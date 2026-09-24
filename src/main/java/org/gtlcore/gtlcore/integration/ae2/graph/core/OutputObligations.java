package org.gtlcore.gtlcore.integration.ae2.graph.core;

import java.util.*;

/** Separates requested external material from outputs of accepted physical batches. */
public final class OutputObligations<K> {

    public record Flight<K>(long id, String recipe, long runs, Map<K, Long> remaining, boolean ambiguous) {

        public Flight {
            if (id <= 0 || runs <= 0 || recipe.isEmpty()) throw new IllegalArgumentException("Invalid output owner");
            remaining = GraphRecipe.amounts(remaining);
        }
    }

    public record Snapshot<K>(Map<K, Long> external, List<Flight<K>> flights, long nextId) {

        public Snapshot {
            external = GraphRecipe.amounts(external);
            flights = List.copyOf(flights);
        }
    }

    private final Map<K, Long> external = new LinkedHashMap<>();
    private final Map<Long, Ticket<K>> flights = new LinkedHashMap<>();
    private final Map<K, Deque<Ticket<K>>> byKey = new HashMap<>();
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
        if (flights.size() >= 4096) throw new IllegalStateException("Too many in-flight batches");
        add(new Flight<>(nextId, recipe, runs, outputs, ambiguous));
    }

    private void add(Flight<K> flight) {
        if (flight.id() == Long.MAX_VALUE) throw new IllegalArgumentException("Output owner sequence exhausted");
        var ticket = new Ticket<>(flight);
        if (flights.putIfAbsent(flight.id(), ticket) != null) throw new IllegalArgumentException("Duplicate output owner");
        for (K key : ticket.remaining.keySet()) byKey.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(ticket);
        if (ticket.remaining.isEmpty()) flights.remove(flight.id());
        nextId = Math.max(nextId, flight.id() + 1);
    }

    /** Called only after an actual return, never on a simulated insertion. */
    public void returned(K key, long amount) {
        CheckedAmounts.nonNegative(amount);
        long supplied = Math.min(amount, external.getOrDefault(key, 0L));
        reduce(external, key, supplied);
        amount -= supplied;
        var queue = byKey.get(key);
        while (amount > 0 && queue != null && !queue.isEmpty()) {
            Ticket<K> ticket = queue.peek();
            long returned = Math.min(amount, ticket.remaining.getOrDefault(key, 0L));
            reduce(ticket.remaining, key, returned);
            amount -= returned;
            if (!ticket.remaining.containsKey(key)) queue.removeFirst();
            if (ticket.remaining.isEmpty()) {
                flights.remove(ticket.id);
            }
        }
        if (queue != null && queue.isEmpty()) byKey.remove(key);
        if (amount != 0) throw new IllegalStateException("Return has no output owner");
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
        additional.forEach((key, count) -> external.merge(key, CheckedAmounts.nonNegative(count), CheckedAmounts::add));
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
    }

    public Snapshot<K> snapshot() {
        return new Snapshot<>(external, flights.values().stream().map(ticket -> new Flight<>(ticket.id, ticket.recipe, ticket.runs, ticket.remaining, ticket.ambiguous)).toList(), nextId);
    }

    private static final class Ticket<K> {

        final long id, runs;
        final String recipe;
        final boolean ambiguous;
        final Map<K, Long> remaining;

        Ticket(Flight<K> flight) {
            id = flight.id();
            runs = flight.runs();
            recipe = flight.recipe();
            ambiguous = flight.ambiguous();
            remaining = new LinkedHashMap<>(flight.remaining());
        }
    }
}
