package gama.ui.devtools.install;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Discovers immutable releases without reusing a cached mutable composite descriptor. */
public final class UpdateSite {

	public static final URI REPOSITORY = URI.create("https://gama-platform.org/gama.devtools/");
	public static final String FEATURE_ID = "gama.feature.devtools.feature.group";

	public record Release(IInstallableUnit unit, URI repository) {}

	private UpdateSite() {}

	public static Release latest(IMetadataRepositoryManager metadata, IProgressMonitor monitor)
			throws IOException, ProvisionException {
		SubMonitor progress = SubMonitor.convert(monitor, 100);
		URI[] releases = releases(REPOSITORY, progress.split(20));
		Release newest = null;
		SubMonitor loading = progress.split(80).setWorkRemaining(releases.length);
		for (URI release : releases) {
			var repository = metadata.loadRepository(release, loading.split(1));
			for (IInstallableUnit candidate : repository.query(QueryUtil.createIUQuery(FEATURE_ID), null)) {
				if (newest == null || candidate.getVersion().compareTo(newest.unit().getVersion()) > 0) {
					newest = new Release(candidate, release);
				}
			}
		}
		if (newest == null) throw new IOException("The update site does not publish GAMA Developer Tools: " + REPOSITORY);
		return newest;
	}

	public static URI[] releases(URI site, IProgressMonitor monitor) throws IOException {
		if (monitor.isCanceled()) throw new OperationCanceledException();
		URI descriptor = site.resolve("compositeContent.xml?check=" + UUID.randomUUID());
		var connection = descriptor.toURL().openConnection();
		connection.setUseCaches(false);
		connection.setRequestProperty("Cache-Control", "no-cache, no-store");
		connection.setRequestProperty("Pragma", "no-cache");
		connection.setConnectTimeout(15_000);
		connection.setReadTimeout(15_000);
		try (var input = connection.getInputStream()) {
			if (monitor.isCanceled()) throw new OperationCanceledException();
			var factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
			if (!"repository".equals(root.getTagName())
					|| !root.getAttribute("type").equals(
							"org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository")) {
				throw new IOException("The update site did not return a composite metadata repository: " + site);
			}
			var releases = new LinkedHashSet<URI>();
			var children = root.getElementsByTagName("child");
			for (int i = 0; i < children.getLength(); i++) {
				String location = ((Element) children.item(i)).getAttribute("location");
				URI release = site.resolve(location).normalize();
				if (location.isBlank() || !site.getScheme().equals(release.getScheme())
						|| !site.getRawAuthority().equals(release.getRawAuthority())
						|| !release.getPath().startsWith(site.resolve("releases/").getPath())
						|| release.getQuery() != null || release.getFragment() != null) {
					throw new IOException("Invalid release location in the update site: " + location);
				}
				releases.add(release);
			}
			if (releases.isEmpty()) throw new IOException("The update site does not list any published releases: " + site);
			if (monitor.isCanceled()) throw new OperationCanceledException();
			return releases.toArray(URI[]::new);
		} catch (ParserConfigurationException | SAXException | IllegalArgumentException e) {
			throw new IOException("Cannot read the update site's release list: " + site, e);
		}
	}
}
