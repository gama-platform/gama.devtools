package gama.ui.devtools.dependencies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes flat directed DOT graphs, not the full Graphviz language. */
final class DependencyGraphDot {

    private DependencyGraphDot() {}

    static void write(DependencyGraph graph, Path output) throws IOException {
        Map<String, Integer> ids = new LinkedHashMap<>();
        try (var writer = Files.newBufferedWriter(output)) {
            writer.write("digraph G {\n");
            for (String library : graph.vertexSet()) {
                int id = ids.size() + 1;
                ids.put(library, id);
                writer.write("  " + id + " [ label=\"" + escape(library) + "\" ];\n");
            }
            for (String source : graph.vertexSet()) {
                for (String target : graph.successorsOf(source)) {
                    writer.write("  " + ids.get(source) + " -> " + ids.get(target) + ";\n");
                }
            }
            writer.write("}\n");
        }
    }

    static DependencyGraph read(Path input) throws IOException {
        return new Parser(Files.readString(input)).parse();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private record Token(String text, boolean identifier, boolean quoted) {}
    private record Edge(String source, String target) {}

    private static final class Parser {

        private final String input;
        private int offset;
        private Token token;

        Parser(String input) {
            this.input = input;
            advance();
        }

        DependencyGraph parse() {
            if (keyword("strict")) advance();
            if (!keyword("digraph")) throw error("Expected a directed graph (digraph)");
            advance();
            if (token.identifier()) advance();
            expect("{");
            Map<String, String> labels = new LinkedHashMap<>();
            List<Edge> edges = new ArrayList<>();
            while (!accept("}")) {
                if (accept(";")) continue;
                String source = nodeId();
                labels.putIfAbsent(source, source);
                if (accept("->")) {
                    do {
                        String target = nodeId();
                        labels.putIfAbsent(target, target);
                        edges.add(new Edge(source, target));
                        source = target;
                    } while (accept("->"));
                } else if (accept("[")) {
                    if (!keyword("label")) throw error("Only node label attributes are supported");
                    advance();
                    expect("=");
                    labels.put(source, identifier());
                    expect("]");
                }
                accept(";");
            }
            expect("");
            DependencyGraph graph = new DependencyGraph();
            for (String label : labels.values()) {
                if (!graph.addVertex(label)) throw error("Different node IDs have the same label: " + label);
            }
            for (Edge edge : edges) graph.addEdge(labels.get(edge.source()), labels.get(edge.target()));
            return graph;
        }

        private String nodeId() {
            for (String reserved : List.of("subgraph", "graph", "digraph", "strict", "node", "edge")) {
                if (keyword(reserved)) throw error("Unsupported DOT construct: " + token.text());
            }
            return identifier();
        }

        private String identifier() {
            if (!token.identifier()) throw error("Expected a node ID or label, got '" + token.text() + "'");
            String value = token.text();
            advance();
            return value;
        }

        private boolean keyword(String value) {
            return token.identifier() && !token.quoted() && token.text().equalsIgnoreCase(value);
        }

        private boolean accept(String value) {
            if (token.identifier() || !token.text().equals(value)) return false;
            advance();
            return true;
        }

        private void expect(String value) {
            if (!accept(value)) throw error("Expected '" + value + "', got '" + token.text() + "'");
        }

        private void advance() {
            skipWhitespace();
            if (offset == input.length()) {
                token = new Token("", false, false);
                return;
            }
            char c = input.charAt(offset++);
            if (c == '"') {
                token = new Token(quotedString(), true, true);
            } else if ("{}[];=".indexOf(c) >= 0) {
                token = new Token(String.valueOf(c), false, false);
            } else if (c == '-' && offset < input.length() && input.charAt(offset) == '>') {
                offset++;
                token = new Token("->", false, false);
            } else {
                int start = offset - 1;
                while (offset < input.length()) {
                    char next = input.charAt(offset);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '.' && next < 128) break;
                    offset++;
                }
                String value = input.substring(start, offset);
                if (!value.matches("[a-zA-Z_\\u0080-\\uFFFF][a-zA-Z_0-9\\u0080-\\uFFFF]*"
                        + "|-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
                    throw error("Unsupported DOT token: " + value);
                }
                token = new Token(value, true, false);
            }
        }

        private String quotedString() {
            StringBuilder result = new StringBuilder();
            while (offset < input.length()) {
                char c = input.charAt(offset++);
                if (c == '"') return result.toString();
                if (c == '\\') {
                    if (offset == input.length()) throw error("Unterminated escape");
                    char escaped = input.charAt(offset++);
                    result.append(switch (escaped) {
                        case '\\', '"' -> escaped;
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> throw error("Unsupported DOT escape: \\" + escaped);
                    });
                } else {
                    result.append(c);
                }
            }
            throw error("Unterminated quoted string");
        }

        private void skipWhitespace() {
            while (offset < input.length()) {
                if (Character.isWhitespace(input.charAt(offset))) {
                    offset++;
                } else if (input.startsWith("//", offset)) {
                    int end = input.indexOf('\n', offset + 2);
                    offset = end < 0 ? input.length() : end + 1;
                } else if (input.startsWith("/*", offset)) {
                    int end = input.indexOf("*/", offset + 2);
                    if (end < 0) throw error("Unterminated comment");
                    offset = end + 2;
                } else {
                    return;
                }
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid or unsupported DOT at character " + offset + ": " + message);
        }
    }
}
