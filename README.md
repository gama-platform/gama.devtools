# gama.devtools

Eclipse development tools for [GAMA](https://github.com/gama-platform/gama): wizards, launches, clean and
rebuild of the GAMA plugins, local release builds without installing Maven, configuration repairs,
reports and source installation.

Update site: `https://gama-platform.org/gama.devtools/` (in Eclipse, *Help > Install New Software...*).

| Project | Content |
|---|---|
| `gama.ui.devtools` | The plugin. See its [README](gama.ui.devtools/README.md) for the commands. |
| `gama.feature.devtools` | The feature installed from the update site. |
| `gama.p2site.devtools` | The update site, published by `.github/workflows/publish-devtools.yaml`. |

Build with `mvn -DskipTests package` (JDK 21+, Maven 3.9). Tests:

```
java gama.ui.devtools/tests/DevToolsMenuTest.java .
python3 -m unittest discover -s gama.p2site.devtools -p 'test_publish.py'
```

The JDK-only dependency graph checks and standalone analyzer are documented in
[Static GAMA Dependencies Analysis](gama.ui.devtools/README_DEPENDENCIES_GRAPH.md).
