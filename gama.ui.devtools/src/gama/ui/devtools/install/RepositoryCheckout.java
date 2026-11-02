package gama.ui.devtools.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import gama.ui.devtools.install.InstallModel.Repository;

/** Clones or fast-forwards repositories and only stashes user changes after explicit confirmation. */
public final class RepositoryCheckout {

	private RepositoryCheckout() {}

	public static final class LocalChangesException extends IOException {

		private static final long serialVersionUID = 1L;
		private final Path directory;

		LocalChangesException(final Path directory) {
			this.directory = directory;
		}

		public Path directory() {
			return directory;
		}
	}

	public static void prepare(final Repository source, final Path directory, final IProgressMonitor monitor)
			throws Exception {
		ProgressMonitor progress = new ProgressMonitor() {
			@Override public void start(final int tasks) {}
			@Override public void beginTask(final String title, final int total) { monitor.subTask(source.name() + ": " + title); }
			@Override public void update(final int completed) {}
			@Override public void endTask() {}
			@Override public boolean isCancelled() { return monitor.isCanceled(); }
			@Override public void showDuration(final boolean enabled) {}
		};
		if (monitor.isCanceled()) throw new OperationCanceledException();
		if (!Files.exists(directory) || isEmpty(directory)) {
			try (Git git = Git.cloneRepository().setURI(source.url()).setDirectory(directory.toFile())
					.setBranch(source.branch()).setProgressMonitor(progress).setTimeout(60).call()) {
				// Closing the handle releases the newly cloned repository.
			}
			return;
		}
		try (Git git = Git.open(directory.toFile())) {
			if (!git.getRepository().getWorkTree().toPath().toRealPath().equals(directory.toRealPath())) {
				throw new IOException("Choose the repository root, not a subdirectory: " + directory);
			}
			String origin = git.getRepository().getConfig().getString("remote", "origin", "url");
			if (!sameRemote(origin, source.url())) {
				throw new IOException(directory + " has a different origin (" + origin + "). Choose another directory or URL.");
			}
			if (git.getRepository().getRepositoryState() != RepositoryState.SAFE) {
				throw new IOException(directory + " has an unfinished Git operation. Resolve it before installing.");
			}
			if (!git.status().call().isClean()) {
				throw new LocalChangesException(directory);
			}
			String remoteBranch = Constants.R_REMOTES + "origin/" + source.branch();
			git.fetch().setRemote("origin").setRefSpecs(new RefSpec("+refs/heads/" + source.branch() + ":" + remoteBranch))
					.setProgressMonitor(progress).setTimeout(60).call();
			if (monitor.isCanceled()) throw new OperationCanceledException();
			String localBranch = Constants.R_HEADS + source.branch();
			if (git.getRepository().exactRef(localBranch) == null) {
				git.branchCreate().setName(source.branch()).setStartPoint(remoteBranch).call();
			}
			// Check ancestry before switching branches so divergence does not leave a different branch checked out.
			try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
				var local = walk.parseCommit(git.getRepository().resolve(localBranch));
				var remote = walk.parseCommit(git.getRepository().resolve(remoteBranch));
				if (!walk.isMergedInto(local, remote)) {
					throw new IOException("Branch " + source.branch() + " has local commits in " + directory
							+ ". Update it manually; the installer only fast-forwards.");
				}
			}
			git.checkout().setName(source.branch()).call();
			var result = git.merge().include(git.getRepository().exactRef(remoteBranch))
					.setFastForward(FastForwardMode.FF_ONLY).call();
			if (!result.getMergeStatus().isSuccessful()) {
				throw new IOException("Cannot fast-forward " + directory + ": " + result.getMergeStatus());
			}
		}
	}

	public static void stash(final Path directory, final IProgressMonitor monitor) throws Exception {
		if (monitor.isCanceled()) throw new OperationCanceledException();
		try (Git git = Git.open(directory.toFile())) {
			if (git.getRepository().getRepositoryState() != RepositoryState.SAFE) {
				throw new IOException(directory + " has an unfinished Git operation. Resolve it before installing.");
			}
			if (git.status().call().isClean()) return;
			if (git.stashCreate().setIncludeUntracked(true).call() == null) {
				throw new IOException("Could not stash the local changes in " + directory + ".");
			}
		}
	}

	private static boolean isEmpty(final Path path) throws IOException {
		if (!Files.isDirectory(path)) return false;
		try (var entries = Files.list(path)) { return entries.findAny().isEmpty(); }
	}

	static boolean sameRemote(final String first, final String second) throws Exception {
		if (first == null) return false;
		URIish a = new URIish(first), b = new URIish(second);
		return java.util.Objects.equals(a.getHost(), b.getHost())
				&& normalizedPath(a.getPath()).equals(normalizedPath(b.getPath()));
	}

	private static String normalizedPath(final String path) {
		return path.replaceAll("^/|\\.git$|/$", "");
	}
}
