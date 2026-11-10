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
3. Before the Maven build, the action reads the modules of `gama.plugin.parent/pom.xml`, finds each feature's
   plugins (and included features) in its `feature.xml`, and rewrites `gama.plugin.p2updatesite/category.xml`:
   - each feature gets the union of its plugins' categories. A feature `pom.xml` can also declare
     `<gama.category>` itself;
   - a feature without any category ends up in `gama.category.other` (with a warning);
   - an unknown category id **fails the build**.

The generated `category.xml` must still be committed in the plugin repository: Tycho needs it for local builds.
Only the version regenerated in CI ends up on the published update site, so a stale local copy is harmless.

## Usage

In the plugin repository's `.github/workflows/build.yml`, after Java is set up and before the Maven build:

```yaml
      - name: Generate p2 categories
        uses: gama-platform/gama.devtools/.github/actions/p2-categories@main
```

Inputs (all optional):

| Input        | Default                         | Description                                          |
|--------------|---------------------------------|------------------------------------------------------|
| `path`       | `.`                             | Root of the plugin repository                        |
| `categories` | `categories.xml` of this action | URL or path of an alternative `categories.xml`       |

## Running it locally

With a JDK 17+ and a clone of `gama.devtools` next to the plugin repository:

```bash
GAMA_CATEGORIES_URL=../gama.devtools/gama.ui.devtools/categories.xml \
  java ../gama.devtools/.github/actions/p2-categories/GenerateCategory.java .
```

Without `GAMA_CATEGORIES_URL`, the script downloads `categories.xml` from the `main` branch on GitHub.

## Adding a category

Add a `<category id="gama.category.xxx" name="Label"/>` entry to `gama.ui.devtools/categories.xml` on `main`.
Every plugin repository picks it up at its next CI build.
