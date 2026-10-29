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
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Discovers immutable releases without reusing a cached mutable composite descriptor. */
public final class UpdateSite {

	private UpdateSite() {}

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
