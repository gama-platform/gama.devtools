# Static GAMA Dependencies Analysis

`DependencyGraphBuilder` uses the JDK's `jdeps` command to discover static dependencies between
compiled libraries and bundles. Graph storage and traversal use only Java collections: adjacency
sets, a live reverse view, and iterative breadth-first search. No third-party graph library is needed.
`GraphToHtml` produces the interactive HTML explorer with direct and transitive dependencies,
importers, and potential service providers/consumers.

Service relationships are inferred from `META-INF/services` entries and service type references in
JAR bytecode; they are potential relationships, not proof of runtime service use. Repeated references
between the same libraries are stored once. Self-loops are retained for direct queries; transitive
queries exclude their starting library, including when there are cycles.

Analysis requires a full JDK 21 or newer and compiled inputs compatible with its `jdeps`.
Input archives are never modified. A failed `jdeps` invocation or an import/export error is reported
as an exception rather than a successful, incomplete graph.

## Standalone use

From the repository root:

```sh
./gama.ui.devtools/generate_dependencies_graph.sh /path/to/libraries /path/to/output/graph
```

The script uses `JAVA_HOME` when set, otherwise `java` and `javac` from `PATH`. It compiles into
`gama.ui.devtools/target/dependency-graph-classes`, without Eclipse or external JARs, then writes
`graph.html` and `graph.dot`. The output directory must already exist. Arguments are optional:
the input defaults to `..` and the output prefix to `./graph2`, relative to the caller's directory.

To render an existing DOT file after compiling:

```sh
java -cp gama.ui.devtools/target/dependency-graph-classes \
  gama.ui.devtools.dependencies.GraphToHtml input.dot output.html
```

## DOT compatibility

DOT export preserves library names, isolated vertices, self-loops and directed edges. It does not
save library paths or service metadata. Import replaces those with empty state, so an imported graph
can still be explored without the original libraries. A failed import leaves the previous graph intact.

The importer accepts the flat directed subset used by this exporter and the previous exporter,
including the bundled `graph.dot` snapshot:

- `digraph` with an optional graph name and optional `strict` prefix;
- quoted, ordinary identifier, or numeric node IDs, and optional `label` attributes on nodes;
- directed edges and edge chains, with optional semicolons and labels declared before or after edges;
- whitespace, `//` comments and `/* ... */` comments;
- quoted strings with escaped quotes, backslashes, newlines, carriage returns and tabs
  (`\"`, `\\`, `\n`, `\r`, `\t`).

Unlabelled nodes use their IDs as library names. Different IDs with the same label are rejected to
avoid merging distinct libraries. Parallel edges are collapsed into a single relationship.
This is not a general Graphviz parser: subgraphs, ports, HTML labels, string concatenation, default
attributes, edge attributes and attributes other than node `label` are rejected with a diagnostic.

## Example usage

```java
        final DependencyGraphBuilder builder = new DependencyGraphBuilder();
        final String librariesPathStr = "/path/to/target/libraries"; 

        Set<Path> libraries;
        
        builder.resolveLibrariesInDirectory(librariesPathStr);

        libraries = builder.getLibraries(); 

        builder.listServices();

        builder.generate();

        builder.checkServicesDependenciesRelations();

        GraphToHtml.generateHtml(builder,"./graph.html");

        builder.exportGraph("./graph.dot");
```

## DependencyGraphBuilder methods

| Scope | Return type | method | parameters |
| --- | --- | --- | --- |
| public | boolean | exportGraph | String outputPathStr |
| public | Set<String> | getDirectDependencies | String library |
| public | boolean | addLibraries | Set<Path> libraries |
| public | boolean | exportGraph | Path outputPath |
| public | DependencyGraph | getDependenciesGraph |  |
| public | Set<String> | getDependencies | String name |
| public | void | generate |  |
| public | void | listServices |  |
| public | boolean | fromDot | String dotFilePathStr |
| public | Set<String> | getDirectImporters | String library |
| public | boolean | resolveLibrariesInDirectory | Path path |
| public | void | checkServicesDependenciesRelations |  |
| public | Set<String> | getLibraryDirectConsumers | String library |
| public | Path | getLibraryPath | String name |
| public | Set<Path> | getLibraries |  |
| public | DependencyGraphBuilder | DependencyGraphBuilder |  |
| public | Set<String> | getLibraryDirectProviders | String library |
| public | void | setLibraries | Set<Path> libraries |
| public | void | processLibrariesNames |  |
| public | DependencyGraph | getImportsGraph |  |
| public | boolean | addLibrary | Path library |
| public | Set<String> | getLibraryServices | String library |
| public | Set<String> | getLibraryProviders | String library |
| public | void | generate | boolean verbose |
| public | Set<String> | getImporters | String name |
| public | boolean | fromDot | Path dotFilePath |
| public | Set<String> | getLibraryConsumers | String library |
| public | boolean | resolveLibrariesInDirectory | String pathStr |

`DependencyGraph` exposes `vertexSet()`, `successorsOf(name)`, `reachableFrom(name)` and `reversed()`,
plus `addVertex(name)` and `addEdge(source, target)`. Both endpoints must exist before adding an edge.
It replaces the graph-library types previously returned by the builder.
Vertex/adjacency sets and `getLibraries()` are read-only views; use the builder's library setters/adders
to update inputs and invalidate cached results. Relationship query results from the builder are
independent mutable sets, as required by the HTML renderer. Unknown library queries throw
`IllegalArgumentException`. Import/export and library discovery retain their boolean success return
values, but throw on failure.

## Regression checks

Run from the repository root with a full JDK:

```sh
mkdir -p gama.ui.devtools/target/dependency-graph-tests
javac --release 21 -d gama.ui.devtools/target/dependency-graph-tests \
  gama.ui.devtools/src/gama/ui/devtools/dependencies/*.java \
  gama.ui.devtools/tests/DependencyGraphTest.java
java -cp gama.ui.devtools/target/dependency-graph-tests DependencyGraphTest .
```

The checks cover cycles, self-loops, repeated edges, reverse and transitive queries, DOT round trips
and the legacy snapshot, rejected DOT syntax, HTML rendering, service relationships and real `jdeps`
analysis of temporary compiled JARs.