/*******************************************************************************************************
 *
 * RebuildProcessorJarHandler.java, in gama.ui.devtools, is part of the source code of the GAMA modeling and
 * simulation platform (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.ui.devtools.handlers;

import java.io.InputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.zip.ZipFile;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jdt.ui.jarpackager.IJarDescriptionReader;
import org.eclipse.jdt.ui.jarpackager.IJarBuilder;
import org.eclipse.jdt.ui.jarpackager.IJarBuilderExtension;
import org.eclipse.jdt.ui.jarpackager.IManifestProvider;
import org.eclipse.jdt.ui.jarpackager.IJarExportRunnable;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.devtools.util.GamaProjects;
import gama.ui.devtools.util.Notifier;
import gama.ui.devtools.util.ResourceUtils;
import gama.ui.devtools.release.ProcessorReuse;

/**
 * Regenerates {@code gama.processor/gama.processor.jar} (the annotation processor used by Eclipse in all GAMA plugins)
 * from the sources of {@code gama.processor} and {@code gama.annotations}, using the
 * {@code gama.processor/Generate Processor.jardesc} description, without going through the JAR export wizard.
 */
public class RebuildProcessorJarHandler extends AbstractHandler {

	/** The name of the jar description in gama.processor. */
	public static final String JARDESC = "Generate Processor.jardesc";

	@Override
	public Object execute(final ExecutionEvent event) {
		Shell shell = HandlerUtil.getActiveShell(event);
		IProject proc = GamaProjects.processor();
		if (proc == null || !proc.isOpen()) {
			MessageDialog.openError(shell, "GAMA", "The project '" + GamaProjects.PROCESSOR_PROJECT + "' is not open");
			return null;
		}
		IFile desc = proc.getFile(JARDESC);
		if (!desc.exists()) {
			MessageDialog.openError(shell, "GAMA", "'" + desc.getFullPath() + "' does not exist");
			return null;
		}
		try {
			var source = proc.getLocation().removeLastSegments(1).toFile().toPath().toRealPath();
			String inputs = ProcessorReuse.inputs(source, () -> false);
			JarPackageData data = new JarPackageData();
			try (InputStream in = desc.getContents(true)) {
				IJarDescriptionReader reader = data.createJarDescriptionReader(in);
				try {
					reader.read(data);
				} finally {
					reader.close();
				}
			}
			IFile jar = proc.getFile(GamaProjects.PROCESSOR_JAR);
			data.setJarLocation(jar.getLocation());
			data.setOverwrite(true);
			data.setSaveDescription(false);
			data.setSaveManifest(false);
			data.setJarBuilder(new ProcessorJarBuilder(data.getJarBuilder()));
			IJarExportRunnable runnable = data.createJarExportRunnable(shell);
			new ProgressMonitorDialog(shell).run(true, true, runnable);
			proc.refreshLocal(IResource.DEPTH_ONE, null);
			IStatus status = runnable.getStatus();
			if (status.getSeverity() == IStatus.ERROR) {
				ErrorDialog.openError(shell, "GAMA", "The processor jar could not be generated", status);
			} else if (status.isOK()) {
				ProcessorReuse.record(source, inputs);
				Notifier.show("GAMA annotation processor", GamaProjects.PROCESSOR_JAR
						+ " regenerated. Run 'GAMA > Clean all...' to use it in all the plugins.");
			} else {
				ErrorDialog.openError(shell, "GAMA", "The processor export reported problems; release reuse is disabled.", status);
			}
		} catch (InvocationTargetException e) {
			ErrorDialog.openError(shell, "GAMA", "The processor jar could not be generated",
					ResourceUtils.error(String.valueOf(e.getCause()), e.getCause()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (CoreException | java.io.IOException e) {
			ErrorDialog.openError(shell, "GAMA", "The processor jar could not be generated",
					ResourceUtils.error(e.getMessage(), e));
		}
		return null;
	}

	private static final class ProcessorJarBuilder implements IJarBuilder, IJarBuilderExtension {

		private final IJarBuilder delegate;

		ProcessorJarBuilder(IJarBuilder delegate) { this.delegate = delegate; }

		@Override
		public String getId() { return delegate.getId(); }

		@Override
		public IManifestProvider getManifestProvider() { return delegate.getManifestProvider(); }

		@Override
		public void open(JarPackageData data, Shell shell, MultiStatus status) throws CoreException {
			delegate.open(data, shell, status);
		}

		@Override
		public void writeFile(IFile file, IPath destination) throws CoreException {
			if (!".DS_Store".equals(file.getName()) && !".DS_Store".equals(destination.lastSegment())) {
				delegate.writeFile(file, destination);
			}
		}

		@Override
		public void writeFile(File file, IPath destination) throws CoreException {
			if (".DS_Store".equals(file.getName()) || ".DS_Store".equals(destination.lastSegment())) return;
			if (delegate instanceof IJarBuilderExtension extension) {
				extension.writeFile(file, destination);
			} else {
				throw new CoreException(ResourceUtils.error("The JAR exporter cannot export compiled files.", null));
			}
		}

		@Override
		public void writeArchive(ZipFile archive, IProgressMonitor monitor) {
			delegate.writeArchive(archive, monitor);
		}

		@Override
		public void close() throws CoreException { delegate.close(); }
	}

}
