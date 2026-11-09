package gama.ui.devtools.install;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import gama.ui.devtools.install.InstallModel.RemoteProject;
import gama.ui.devtools.install.InstallModel.Repository;

/** Discovers projects without cloning every repository in the community organization. */
public final class CommunityCatalog {

	public record Catalog(List<RemoteProject> projects, List<String> unavailable) {}

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	public Catalog load(final String branch, final IProgressMonitor monitor) throws Exception {
		List<RemoteProject> projects = new ArrayList<>();
		List<String> unavailable = new ArrayList<>();
		for (int page = 1; ; page++) {
			var repositories = request("/orgs/gama-platform-plugins/repos?per_page=100&page=" + page, monitor)
					.getAsJsonArray();
			for (JsonElement element : repositories) {
				var repo = element.getAsJsonObject();
				String name = repo.get("name").getAsString();
				monitor.subTask("Listing " + name + " (" + branch + ")");
				Repository repository = new Repository(name, repo.get("clone_url").getAsString(), branch,
						InstallModel.OTHER_CATEGORY);
				JsonElement tree = request("/repos/gama-platform-plugins/" + name + "/git/trees/"
						+ URLEncoder.encode(branch, StandardCharsets.UTF_8) + "?recursive=1", monitor);
				if (tree == null) {
					unavailable.add(name + " (no branch " + branch + ")");
					continue;
				}
				if (tree.getAsJsonObject().get("truncated").getAsBoolean()) {
					throw new IOException("GitHub truncated the project list for " + name + ". Try that repository separately.");
				}
				int before = projects.size();
				Map<String, String> poms = new LinkedHashMap<>();
				Map<String, String> categories = new LinkedHashMap<>();
				for (JsonElement entry : tree.getAsJsonObject().getAsJsonArray("tree")) {
					var file = entry.getAsJsonObject();
					String path = file.get("path").getAsString();
					if (file.get("type").getAsString().equals("blob")
							&& (path.equals("pom.xml") || path.endsWith("/pom.xml"))) {
						poms.put(path, file.get("sha").getAsString());
					}
				}
				for (JsonElement entry : tree.getAsJsonObject().getAsJsonArray("tree")) {
					String path = entry.getAsJsonObject().get("path").getAsString();
					if (path.equals(".project") || path.endsWith("/.project")) {
						String directory = path.equals(".project") ? "" : path.substring(0, path.length() - 9);
						projects.add(new RemoteProject(repository, directory,
								category(name, directory, poms, categories, monitor)));
					}
				}
				if (before == projects.size()) unavailable.add(name + " (no Eclipse projects)");
			}
			if (repositories.size() < 100) break;
		}
		projects.sort(Comparator.comparing(RemoteProject::toString));
		return new Catalog(List.copyOf(projects), List.copyOf(unavailable));
	}

	private String category(final String repository, final String directory, final Map<String, String> poms,
			final Map<String, String> categories, final IProgressMonitor monitor) throws Exception {
		if (categories.containsKey(directory)) return categories.get(directory);
		int separator = directory.lastIndexOf('/');
		String inherited = directory.isEmpty() ? InstallModel.OTHER_CATEGORY
				: category(repository, separator < 0 ? "" : directory.substring(0, separator), poms, categories, monitor);
		String sha = poms.get(directory.isEmpty() ? "pom.xml" : directory + "/pom.xml");
		String category = inherited;
		if (sha != null) {
			var blob = request("/repos/gama-platform-plugins/" + repository + "/git/blobs/" + sha, monitor).getAsJsonObject();
			if (!blob.get("encoding").getAsString().equals("base64")) {
				throw new IOException("Unsupported GitHub encoding for " + repository + "/" + directory + "/pom.xml");
			}
			try (var input = new ByteArrayInputStream(
					Base64.getMimeDecoder().decode(blob.get("content").getAsString()))) {
				category = InstallModel.category(input, inherited);
			}
		}
		categories.put(directory, category);
		return category;
	}

	private JsonElement request(final String path, final IProgressMonitor monitor) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com" + path))
				.timeout(Duration.ofSeconds(60)).header("Accept", "application/vnd.github+json")
				.header("User-Agent", "GAMA-Developer-Tools").GET().build();
		var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		try {
			while (true) {
				if (monitor.isCanceled()) throw new OperationCanceledException();
				try {
					var result = response.get(200, TimeUnit.MILLISECONDS);
					if (result.statusCode() == 404 && path.contains("/git/trees/")) return null;
					if (result.statusCode() != 200) {
						throw new IOException("GitHub returned HTTP " + result.statusCode() + " for " + path
								+ ". Check connectivity and the public API rate limit. " + result.body());
					}
					return JsonParser.parseString(result.body());
				} catch (TimeoutException e) {
					// Poll only to allow cancellation of the HTTP request.
				} catch (ExecutionException e) {
					throw new IOException("Cannot contact GitHub: " + e.getCause().getMessage(), e.getCause());
				}
			}
		} finally {
			if (!response.isDone()) response.cancel(true);
		}
	}
}
