package gama.ui.devtools.dependencies;

import java.nio.file.Path;
import java.util.Set;

/** Command-line entry point for generating the standalone dependency graph reports. */
public class Main {

    public static void main(String argv[])
    {
        if (argv.length > 2) throw new IllegalArgumentException(
                "Usage: Main [libraries-directory] [output-prefix]");
        final DependencyGraphBuilder builder = new DependencyGraphBuilder();
        final String librariesPathStr = argv.length == 0 ? ".." : argv[0];
        final String outputPrefix = argv.length < 2 ? "./graph2" : argv[1];
        Set<Path> libraries;
        
        System.out.println("Resolving libraries in " + librariesPathStr);
        builder.resolveLibrariesInDirectory(librariesPathStr);

        libraries = builder.getLibraries(); 

        System.out.println("Found " + libraries.size() + " librar" + (libraries.size() == 1 ? "y" : "ies"));
        System.out.println("Listing services");
        builder.listServices();

        System.out.println("Generating the static dependencies graph");
        builder.generate();

        System.out.println("Checking the providing/consuming services relations");
        builder.checkServicesDependenciesRelations();

        System.out.println("Generating HTML graph explorer");
        GraphToHtml.generateHtml(builder, outputPrefix + ".html");

        System.out.println("Exporting the graph in .dot format");
        builder.exportGraph(outputPrefix + ".dot");
        System.out.println("done");
    }
}