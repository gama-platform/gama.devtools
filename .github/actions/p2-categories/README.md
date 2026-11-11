# p2-categories action

> 🚧 Placeholder documentation: to be completed.

Generates the `category.xml` of a GAMA plugin p2 update site in CI, so that the category list lives in one place
instead of being copied into every plugin repository.

## How it works

1. The list of available categories is [`gama.ui.devtools/categories.xml`](../../../gama.ui.devtools/categories.xml).
   The same file feeds the *New GAMA plugin* wizard of the devtools.
2. Each plugin declares its category in its `pom.xml`:
   ```xml
   <properties>
     <gama.category>gama.category.ai</gama.category>
   </properties>
   ```
   Several categories can be listed, separated by commas.
3. Before the Maven build, the action reads the `<modules>` of the parent pom (`gama.plugin.parent/pom.xml` by
   default, nested aggregator poms are followed), finds each feature's plugins (and included features) in its
   `feature.xml`, and rewrites the update site's `category.xml` (`gama.plugin.p2updatesite/category.xml` by
   default):
   - each feature gets the union of its plugins' categories. A feature `pom.xml` can also declare
     `<gama.category>` itself;
   - a feature without any category ends up in the fallback category, `gama.category.other` by default (with a
     warning), or fails the build if the fallback is set to an empty string;
   - an unknown category id **fails the build**.

The plugin repository commits a placeholder `category.xml`, with no feature and a comment pointing here: Tycho needs
the file to exist, and a local build then produces an empty update site. Only CI builds are published, and in CI the
file is regenerated before Maven starts.

## Usage

In the plugin repository's `.github/workflows/build.yml`, after Java is set up and before the Maven build:

```yaml
      - name: Generate p2 categories
        uses: gama-platform/gama.devtools/.github/actions/p2-categories@main
```

Inputs (all optional). Paths are relative to `path`:

| Input               | Default                                 | Description                                                                 |
|---------------------|-----------------------------------------|-----------------------------------------------------------------------------|
| `path`              | `.`                                     | Root of the plugin repository                                               |
| `parent-pom`        | `gama.plugin.parent/pom.xml`            | `pom.xml` (or its folder) whose `<modules>` list the plugins and features   |
| `output`            | `gama.plugin.p2updatesite/category.xml` | `category.xml` to generate; its folder must exist                           |
| `fallback-category` | `gama.category.other`                   | Category of features without any `<gama.category>`; empty to fail instead   |
| `categories`        | `categories.xml` of this action         | URL or path of an alternative `categories.xml`                              |

### Repositories not built from the plugin template

For example, a repository with a root aggregator `pom.xml` and an update site in `gama.p2site.myplugin/` (the
layout of `gama.devtools` and `gama.copilot`):

```yaml
      - name: Generate p2 categories
        uses: gama-platform/gama.devtools/.github/actions/p2-categories@main
        with:
          parent-pom: pom.xml
          output: gama.p2site.myplugin/category.xml
```

## Running it locally

With a JDK 17+ and a clone of `gama.devtools` next to the plugin repository:

```bash
GAMA_CATEGORIES_URL=../gama.devtools/gama.ui.devtools/categories.xml \
  java ../gama.devtools/.github/actions/p2-categories/GenerateCategory.java .
```

Each input has an environment variable. Unset or blank means the default, except for `GAMA_FALLBACK_CATEGORY`:

| Input               | Variable                 | Default when run outside the action                                                     |
|---------------------|--------------------------|-----------------------------------------------------------------------------------------|
| `parent-pom`        | `GAMA_PARENT_POM`        | `gama.plugin.parent/pom.xml`                                                            |
| `output`            | `GAMA_CATEGORY_OUTPUT`   | `gama.plugin.p2updatesite/category.xml`                                                 |
| `fallback-category` | `GAMA_FALLBACK_CATEGORY` | `gama.category.other` (set but empty: no fallback, the build fails)                     |
| `categories`        | `GAMA_CATEGORIES_URL`    | `https://raw.githubusercontent.com/gama-platform/gama.devtools/main/gama.ui.devtools/categories.xml` |

Generating into the real `category.xml` replaces the placeholder: do not commit the result.

## Adding a category

Add a `<category id="gama.category.xxx" name="Label"/>` entry to `gama.ui.devtools/categories.xml` on `main`.
Every plugin repository picks it up at its next CI build.
