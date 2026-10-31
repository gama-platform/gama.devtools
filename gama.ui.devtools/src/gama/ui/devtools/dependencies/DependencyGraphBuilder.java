package gama.ui.devtools.dependencies;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;

import java.util.List;
import java.util.LinkedList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.net.URI;

/**
 * Builds static library dependency graphs and potential service-provider relationships using the JDK's
 * {@code jdeps} analyzer.
 *
 * <p>Callers provide the library archives, generate the dependency graph, and then query or export the
 * results. Service relationships are inferred from service descriptors and bytecode references; they
 * describe potential rather than confirmed runtime usage.
 */
public class DependencyGraphBuilder {

    private static final String servicePathStr = "META-INF/services/";

    /**
     * Location of the native java dependency analyzer executable
     */
    private static final String jdepsLocation = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "jdeps.exe" : "jdeps").toString();

    /**
     * Map linking
     */
    private Map<String,Path> librariesNameToPath = new LinkedHashMap<>();

    /**
     * libraries
     */
    private Set<Path> libraries = null;

    /**
     * X -> Y means X imports Y
     */
    private DependencyGraph dependenciesGraph = null;

    /**
     * X -> Y means X is imported by Y
     */
    private DependencyGraph importsGraph = null;

     /**
     * X -> Y means X is consuming  a service provided by Y
     * 
     * successors of X are its providers
     */   
    private DependencyGraph serviceConsumingGraph = null;

     /**
     * X -> Y means X is providing a service consumed by Y
     * 
     * successors of X are its consumers
     */   
    private DependencyGraph serviceProvidingGraph = null;

    /**
     * Set of all the known services
     */
    private Set<String> services;

    /**
     * map linking each service to the set of provider libraries
     */
    private Map<String,Set<String>> serviceToLibraryMap;

    /**
     * map linking each library to the set of services it provides
     */
    private Map<String,Set<String>> libraryToServicesMap;

    public DependencyGraphBuilder() {
        libraries = new LinkedHashSet<Path>();
        services = new HashSet<String>();
        serviceToLibraryMap = new HashMap<String,Set<String>>();
        libraryToServicesMap = new HashMap<String,Set<String>>();
    }

    /**
     * Generate the dependency Graph
     * 
     * @param verbose
     *              if true, prints the dependencies relationships in the standard output;
     * 
     */
    public void generate(boolean verbose) {

        DependencyGraph graph = new DependencyGraph();
        librariesNameToPath.keySet().forEach(graph::addVertex);
        if (libraries.isEmpty()) {
            dependenciesGraph = graph;
            importsGraph = graph.reversed();
            return;
        }

        List<String> arguments = libraries.stream()
            .map(p -> p.toString())
            .collect(Collectors.toCollection(LinkedList::new));

        arguments.addFirst("--recursive");
        arguments.addFirst("base");
        arguments.addFirst("--multi-release");
        arguments.addFirst(jdepsLocation);

        ProcessBuilder pb = new ProcessBuilder(arguments).redirectErrorStream(true);
        Process process = null;

        try {

            process = pb.start();
            StringBuilder diagnostics = new StringBuilder();

            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {

                String line;

                while ((line = reader.readLine()) != null) {

                    if (line.isBlank() || Character.isWhitespace(line.charAt(0)))
                        continue;

                    int arrow = line.indexOf(" -> ");
                    if (arrow < 0) {
                        diagnostics.append(line).append('\n');
                        continue;
                    }
                    Path dependencyPath = Path.of(line.substring(arrow + 4).strip());
                    
                    if (! Files.exists(dependencyPath))
                        continue;

                    String importer = line.substring(0, arrow).strip();

                    graph.addEdge(importer, dependencyPath.getFileName().toString());

                    if(verbose)
                        System.out.println(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("jdeps failed (exit " + exitCode + "): " + diagnostics);
            }
            dependenciesGraph = graph;
            importsGraph = graph.reversed();

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute jdeps on " + libraries, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running jdeps", e);
        } finally {
            if (process != null && process.isAlive()) process.destroy();
        }
    }

    /**
     * Generate the dependency Graph
     */
    public void generate() {
        generate(false);
    }

    /**
     * Resolve the libraries to be inside a directory.
     * The resolved libraries are added to the 
     * already found libraries.
     * 
     * @param path
     *           Path of the target directory
     * 
     * @return boolean success value of the operation
     */
    public boolean resolveLibrariesInDirectory(Path path)
    {
        path = path.toAbsolutePath();

        if(! Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }

        try (Stream<Path> stream = Files.walk(path)) {

            final Set<Path> foundLibraries =  stream
                    .filter(p -> p.toString().endsWith(".jar")
                                || Files.exists(p.resolve("META-INF/MANIFEST.MF")))
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            this.libraries.addAll(foundLibraries);
            processLibrariesNames();

            return true;

        } catch (IOException e)
        {
            throw new UncheckedIOException("Cannot resolve libraries in " + path, e);
        }
        
    }

    /**
     * Resolve the libraries to be inside a directory.
     * The resolved libraries are added to the 
     * already found libraries.
     * 
     * @param pathStr
     *              String representation of the
     *              path of the target directory
     *              (Will be casted to Path)
     * 
     * @return boolean success value of the operation
     */
    public boolean resolveLibrariesInDirectory(String pathStr) {
        return resolveLibrariesInDirectory(Path.of(pathStr));
    }

    /**
     * Manually set the target libraries to be analyzed
     * 
     * @param libraries
     *                the libraries to analyze
     */
    public void setLibraries(Set<Path> libraries) {
        this.libraries = new LinkedHashSet<Path>(libraries);
        processLibrariesNames();
    }

    /**
     * Manually add a library to be analyzed
     * 
     * @param library
     *                Path of the library to add
     * 
     * @return true if library was not already present else false
     */
    public boolean addLibrary(Path library)
    {
        boolean added = libraries.add(library);
        if (added) processLibrariesNames();
        return added;
    }

    /**
     * Manually add a libraries to be analyzed
     * 
     * @param libraries
     *                the libraries to add
     * 
     * @return true if library was not already present else false
     */
    public boolean addLibraries(Set<Path> libraries)
    {
        boolean added = this.libraries.addAll(libraries);
        if (added) processLibrariesNames();
        return added;
    }

    /**
     * Associate every library's name to its Path
    */    
    public void processLibrariesNames(){
        librariesNameToPath.clear();
        libraries.forEach(p -> librariesNameToPath.put(p.getFileName().toString(),p));
        dependenciesGraph = null;
        importsGraph = null;
        serviceConsumingGraph = null;
        serviceProvidingGraph = null;
        services.clear();
        serviceToLibraryMap.clear();
        libraryToServicesMap.clear();
    }

    /**
     * Export the graph in DOT format
     * 
     * @param outputPath
     *                 the path of the desired output file
     * 
     * @return success boolean value
    */    
    public boolean exportGraph(Path outputPath)
    {
        try {
            DependencyGraphDot.write(getDependenciesGraph(), outputPath);

            return true;

        } catch (IOException e)
        {
            throw new UncheckedIOException("Cannot export dependency graph to " + outputPath, e);
        }
    }

    /**
     * Export the graph in DOT format
     * 
     * @param outputPathStr
     *                    the String representing the path 
     *                    of the desired output file
     * 
     * @return success boolean value
    */  
    public boolean exportGraph(String outputPathStr) {
        return exportGraph(Path.of(outputPathStr));
    }

    /**
     * Get the dependency graph
     * Generate it if was not generated yet
     * 
     * @return the Dependency Graph
    */
    public DependencyGraph getDependenciesGraph() {
        if (dependenciesGraph == null)
            generate();

        return dependenciesGraph;
    }

    /**
     * Get the imports graph
     * Generate it if was not generated yet
     * 
     * @return the Imports Graph
    */
    public DependencyGraph getImportsGraph() {
        if (importsGraph == null)
            generate();

        return importsGraph;
    }

    /**
     * Get the Set of libraries
     * @return an unmodifiable view of the library paths
    */
    public Set<Path> getLibraries() {
        return Collections.unmodifiableSet(libraries);
    }

    /**
     * Get the Path of a library from its name
     * @return Path if library is found else null
    */
    public Path getLibraryPath(String name) {
        return librariesNameToPath.getOrDefault(name,null);
    }

    /**
     * get the set of libraries importing the target library (recursively)
     * @param name
     *           name of the target library
     * 
     * @return the list of libraries (names) importing the input library
    */
    public Set<String> getImporters(String name) {
        return getImportsGraph().reachableFrom(name);
    }

    /**
     * get the set of libraries imported by the target library (recursively)
     * @param name
     *           name of the target library
     * 
     * @return the list of libraries (names) imported by the input library
    */
    public Set<String> getDependencies(String name) {
        return getDependenciesGraph().reachableFrom(name);
    }

    /**
     * Returns the libraries directly imported by the named library.
     *
     * @param library the library name
     * @return a mutable set of direct dependency names
     */
    public Set<String> getDirectDependencies(String library)
    {
        return new LinkedHashSet<>(getDependenciesGraph().successorsOf(library));
    }

    /**
     * Returns the libraries that directly import the named library.
     *
     * @param library the library name
     * @return a mutable set of direct importer names
     */
    public Set<String> getDirectImporters(String library)
    {
        return new LinkedHashSet<>(getImportsGraph().successorsOf(library));
    }

    /**
     * Load a flat directed DOT graph, replacing paths and service metadata with empty state.
     * @param dotFilePathStr
     *                     Swtring representing the path to the .dot graph
     * 
     * @return boolean success value
     */
    public boolean fromDot(String dotFilePathStr)
    {
        try {
            DependencyGraph graph = DependencyGraphDot.read(Path.of(dotFilePathStr));
            libraries.clear();
            processLibrariesNames();
            dependenciesGraph = graph;
            importsGraph = graph.reversed();
            serviceConsumingGraph = new DependencyGraph();
            graph.vertexSet().forEach(serviceConsumingGraph::addVertex);
            serviceProvidingGraph = serviceConsumingGraph.reversed();

            return true;

        } catch (IOException e) {
            throw new UncheckedIOException("Cannot import dependency graph from " + dotFilePathStr, e);
        }
    }

    /**
     * Load a graph from a dot format graph file
     * @param dotFilePath
     *                     Path to the .dot graph
     * 
     * @return boolean success value
     */
    public boolean fromDot(Path dotFilePath)
    {
        return fromDot(dotFilePath.toString());
    }


    /**
     * build a service to provider libraries map,
     * and library to provided services map, by listing
     * the services declared in each library's 
     * 'META-INF/services' directory
     */
    public void listServices()
    {
        services.clear();
        serviceToLibraryMap.clear();
        libraryToServicesMap.clear();
        serviceConsumingGraph = null;
        serviceProvidingGraph = null;
        for(Path library : libraries)
        {
            final String filename = library.getFileName().toString();
            Set<String> thisLibraryServices;

            if(Files.isDirectory(library))
                continue;
            
            // retrive services declared in META-INF/services/*
            try (ZipFile zipFile = new ZipFile(library.toString())) {

                thisLibraryServices = zipFile.stream()
                    .map(ZipEntry::getName)
                    .filter(entry -> entry.startsWith(servicePathStr) && ! entry.endsWith("/"))
                    .map(entry -> entry.replaceAll(".*/","").replaceAll("\\.","/")) // a service is a/b/c/ClassName (relevent for bytecode research)
                    .collect(Collectors.toSet());

                // binding the current library as a provider of each found services
                thisLibraryServices.forEach(service -> {
                    serviceToLibraryMap.computeIfAbsent(service, 
                        key -> new HashSet<String>()
                    ).add(filename);

                    libraryToServicesMap.computeIfAbsent(filename, 
                        key -> new HashSet<String>()
                    ).add(service);
                });

                services.addAll(thisLibraryServices);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Compute the consume/provide of services relations 
     * among known libraries and services.
     * 
     * A service declared in META-INF/services will
     * follow the exemple pattern: a.b.c.className
     * 
     * A service is considered potentially consumed by
     * a library if one of its .class files contains
     * the hard coded string a/b/c/className
     * 
     */
    public void checkServicesDependenciesRelations()
    {
        serviceConsumingGraph = new DependencyGraph();
        serviceProvidingGraph = serviceConsumingGraph.reversed();

        Set<String> names = dependenciesGraph == null ? librariesNameToPath.keySet() : dependenciesGraph.vertexSet();
        names.forEach(serviceConsumingGraph::addVertex);

        for (Path library : libraries)
        {
            if(Files.isDirectory(library))
                continue;

            String filename = library.getFileName().toString();

            try {
                URI zipUri = URI.create("jar:" + library.toUri().toString());
                Map<String, String> env = new HashMap<>();

                ZipFile zipFile = new ZipFile(library.toString());

                // listing all class files entries
                Set<String> entries = zipFile.stream().map(ZipEntry::getName)
                    .filter(entry -> entry.endsWith(".class"))
                    .collect(Collectors.toSet());

                zipFile.close();

                FileSystem zipfs = FileSystems.newFileSystem(zipUri, env);

                for(String entry: entries)
                {
                    Path entryPath = zipfs.getPath(entry);
                    byte[] content = Files.readAllBytes(entryPath);

                    for (String service : services)
                    {
                        if (contains(content, service.getBytes(StandardCharsets.UTF_8)))
                        {
                            serviceToLibraryMap.get(service).forEach(provider -> {
                                serviceConsumingGraph.addEdge(filename,provider);
                            });
                        }
                    }
                }

                zipfs.close();
                
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Retrieve the set of all providers
     * of services the target library potentially
     * consumes (recursively)
     * 
     * @param library
     *              name of the target library 
     * 
     * @return the library names providing services consumed by the target
     */
    public Set<String> getLibraryProviders(String library)
    {
        if (serviceConsumingGraph == null) {
            listServices();
            checkServicesDependenciesRelations();
        }

        return serviceConsumingGraph.reachableFrom(library);
    }

    /**
     * Retrieve the set of potential consumers
     * of services declared in the 'META-INF/services'
     * of the target library (recursive)
     * 
     * @param library
     *              name of the target library 
     * 
     * @return the library names that potentially consume services from the target
     */
    public Set<String> getLibraryConsumers(String library)
    {
        if (serviceProvidingGraph == null) {
            listServices();
            checkServicesDependenciesRelations();
        }

        return serviceProvidingGraph.reachableFrom(library);
    }

    /**
     * Retrieve the set of direct providers
     * of services the target library potentially
     * consumes
     * 
     * @param library
     *              name of the target library 
     * 
     * @return the library names directly providing services consumed by the target
     */
    public Set<String> getLibraryDirectProviders(String library)
    {
        if (serviceConsumingGraph == null) {
            listServices();
            checkServicesDependenciesRelations();
        }

        return new LinkedHashSet<>(serviceConsumingGraph.successorsOf(library));
    }

    /**
     * Retrieve the set of potential direct consumers
     * of services declared in the 'META-INF/services'
     * of the target library
     * 
     * @param library
     *              name of the target library 
     * 
     * @return the library names that directly consume services from the target
     */
    public Set<String> getLibraryDirectConsumers(String library)
    {
        if (serviceProvidingGraph == null) {
            listServices();
            checkServicesDependenciesRelations();
        }
        
        return new LinkedHashSet<>(serviceProvidingGraph.successorsOf(library));
    }


    /**
     * Retrieve the set of services declared 
     * in the 'META-INF/services'of the target 
     * library
     * 
     * @param library
     *              name of the target library 
     * 
     * @return the service names declared by the target library
     */
    public Set<String> getLibraryServices(String library)
    {
        return new HashSet<String>(libraryToServicesMap.getOrDefault(library,Collections.emptySet()));
    }

    /** Whether {@code target} occurs in {@code array}. */
    private static boolean contains(final byte[] array, final byte[] target) {
        outer: for (int i = 0; i <= array.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) { if (array[i + j] != target[j]) continue outer; }
            return true;
        }
        return false;
    }
}
