# gama.devtools

Eclipse development tools for [GAMA](https://github.com/gama-platform/gama): wizards, launches, clean and
rebuild of the GAMA plugins, local release builds without installing Maven, configuration repairs,
reports and source installation. **GAMA > Get started...** guides a new development workspace from
prerequisite checks through source installation and building to running GAMA.

Update site: `https://gama-platform.org/gama.devtools/` (in Eclipse, *Help > Install New Software...*).

For GAMA development, use **Eclipse IDE for Java and DSL Developers 2026-03 or newer**, running on
Java 21 or newer, with PDE installed. After installing these tools and restarting Eclipse, accept
the setup prompt in a workspace without `gama.core`, or choose **GAMA > Get started...**. The wizard
helps register a separate **full JDK 25**; Eclipse's own Java runtime need not change.

See [Getting started](gama.ui.devtools/README.md#getting-started) for the recommended guided path
and the manual alternative. The [GAMA developer wiki](https://github.com/gama-platform/gama/wiki/developingGAMA)
also covers the source build and contribution workflow.

| Project | Content |
|---|---|
| `gama.ui.devtools` | The plugin. See its [README](gama.ui.devtools/README.md) for the commands. |
| `gama.feature.devtools` | The feature installed from the update site. |
| `gama.p2site.devtools` | The update site, published by `.github/workflows/publish-devtools.yaml`. |

Build with `mvn -DskipTests package` (JDK 21+, Maven 3.9). Run the publishing tests with:

```
python3 -m unittest discover -s gama.p2site.devtools -p 'test_publish.py'
```

The JDK-only dependency graph checks and standalone analyzer are documented in
[Static GAMA Dependencies Analysis](gama.ui.devtools/README_DEPENDENCIES_GRAPH.md).
