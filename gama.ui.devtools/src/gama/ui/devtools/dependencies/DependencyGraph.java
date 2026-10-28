package gama.ui.devtools.dependencies;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Directed library relationships, with self-loops and one edge per pair of libraries. */
public final class DependencyGraph {

    private final Map<String, Set<String>> outgoing;
    private final Map<String, Set<String>> incoming;

    public DependencyGraph() {
        this(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private DependencyGraph(Map<String, Set<String>> outgoing, Map<String, Set<String>> incoming) {
        this.outgoing = outgoing;
        this.incoming = incoming;
    }

    public boolean addVertex(String library) {
        Objects.requireNonNull(library, "library");
        if (outgoing.containsKey(library)) return false;
        outgoing.put(library, new LinkedHashSet<>());
        incoming.put(library, new LinkedHashSet<>());
        return true;
    }

    public boolean addEdge(String source, String target) {
        Set<String> successors = neighbors(outgoing, source);
        Set<String> predecessors = neighbors(incoming, target);
        if (!successors.add(target)) return false;
        predecessors.add(source);
        return true;
    }

    public Set<String> vertexSet() {
        return Collections.unmodifiableSet(outgoing.keySet());
    }

    public Set<String> successorsOf(String library) {
        return Collections.unmodifiableSet(neighbors(outgoing, library));
    }

    /** Reachable libraries, excluding the starting library even in a cycle. */
    public Set<String> reachableFrom(String library) {
        neighbors(outgoing, library);
        Set<String> visited = new LinkedHashSet<>();
        var pending = new ArrayDeque<String>();
        visited.add(library);
        pending.add(library);
        while (!pending.isEmpty()) {
            for (String next : outgoing.get(pending.removeFirst())) {
                if (visited.add(next)) pending.addLast(next);
            }
        }
        visited.remove(library);
        return visited;
    }

    /** A live reverse view sharing the same relationships. */
    public DependencyGraph reversed() {
        return new DependencyGraph(incoming, outgoing);
    }

    private static Set<String> neighbors(Map<String, Set<String>> graph, String library) {
        Set<String> result = graph.get(library);
        if (result == null) throw new IllegalArgumentException("Unknown library: " + library);
        return result;
    }
}
