/*******************************************************************************
 * Copyright (c) 2015, 2016 QNX Software Systems and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.espressif.idf.core.build;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.cdt.build.gcc.core.ClangToolChain;
import org.eclipse.cdt.cmake.core.ICMakeToolChainFile;
import org.eclipse.cdt.cmake.core.ICMakeToolChainManager;
import org.eclipse.cdt.cmake.core.internal.CMakeUtils;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.CommandLauncherManager;
import org.eclipse.cdt.core.ConsoleOutputStream;
import org.eclipse.cdt.core.ErrorParserManager;
import org.eclipse.cdt.core.IConsoleParser;
import org.eclipse.cdt.core.build.CBuildConfiguration;
import org.eclipse.cdt.core.build.IToolChain;
import org.eclipse.cdt.core.envvar.EnvironmentVariable;
import org.eclipse.cdt.core.envvar.IEnvironmentVariable;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ElementChangedEvent;
import org.eclipse.cdt.core.model.IBinary;
import org.eclipse.cdt.core.model.IBinaryContainer;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.ICElementDelta;
import org.eclipse.cdt.core.model.ICModelMarker;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.parser.ExtendedScannerInfo;
import org.eclipse.cdt.core.parser.IScannerInfo;
import org.eclipse.cdt.core.resources.IConsole;
import org.eclipse.cdt.internal.core.model.BinaryRunner;
import org.eclipse.cdt.internal.core.model.CModelManager;
import org.eclipse.cdt.jsoncdb.core.CompileCommandsJsonParser;
import org.eclipse.cdt.jsoncdb.core.ISourceFileInfoConsumer;
import org.eclipse.cdt.jsoncdb.core.ParseRequest;
import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.debug.core.ILaunchMode;
import org.eclipse.launchbar.core.ILaunchBarManager;
import org.eclipse.launchbar.core.target.ILaunchTarget;

import com.espressif.idf.core.IDFConstants;
import com.espressif.idf.core.IDFCorePlugin;
import com.espressif.idf.core.IDFCorePreferenceConstants;
import com.espressif.idf.core.internal.CMakeConsoleWrapper;
import com.espressif.idf.core.internal.CMakeErrorParser;
import com.espressif.idf.core.logging.Logger;
import com.espressif.idf.core.util.DfuCommandsUtil;
import com.espressif.idf.core.util.IDFUtil;
import com.espressif.idf.core.util.ParitionSizeHandler;
import com.google.gson.Gson;

@SuppressWarnings(value = { "restriction" })
public class IDFBuildConfiguration extends CBuildConfiguration
{

	protected static final String COMPILE_COMMANDS_JSON = "compile_commands.json"; //$NON-NLS-1$
	protected static final String COMPONENTS = "components"; // $NON-NLS-1$
	private static final String ESP_IDF_COMPONENTS = "esp_idf_components"; //$NON-NLS-1$
	public static final String CMAKE_GENERATOR = "cmake.generator"; //$NON-NLS-1$
	public static final String CMAKE_ARGUMENTS = "cmake.arguments"; //$NON-NLS-1$
	public static final String CMAKE_ENV = "cmake.environment"; //$NON-NLS-1$
	public static final String BUILD_COMMAND = "cmake.command.build"; //$NON-NLS-1$
	public static final String CLEAN_COMMAND = "cmake.command.clean"; //$NON-NLS-1$

	private ILaunchTarget launchtarget;
	private Map<IResource, IScannerInfo> infoPerResource;
	/**
	 * whether one of the CMakeLists.txt files in the project has been modified and saved by the user since the last
	 * build.<br>
	 * Cmake-generated build scripts re-run cmake if one of the CMakeLists.txt files was modified, but that output goes
	 * through ErrorParserManager and is impossible to parse because cmake outputs to both stderr and stdout and
	 * ErrorParserManager intermixes these streams making it impossible to parse for errors.<br>
	 * To work around that, we run cmake in advance with its dedicated working error parser.
	 */
	private boolean cmakeListsModified;
	private ICMakeToolChainFile toolChainFile;
	private String customBuildDir;
	private IBuildConfiguration buildConfiguration;
	
	public IDFBuildConfiguration(IBuildConfiguration config, String name) throws CoreException
	{
		super(config, name);
		buildConfiguration = config;
		ICMakeToolChainManager manager = IDFCorePlugin.getService(ICMakeToolChainManager.class);
		this.toolChainFile = manager.getToolChainFileFor(getToolChain());
	}

	public IDFBuildConfiguration(IBuildConfiguration config, String name, IToolChain toolChain)
	{
		this(config, name, toolChain, null, "run"); //$NON-NLS-1$
	}

	public IDFBuildConfiguration(IBuildConfiguration config, String name, IToolChain toolChain,
			ICMakeToolChainFile toolChainFile, String launchMode)
	{
		super(config, name, toolChain, launchMode);
		this.toolChainFile = toolChainFile;
	}

	@Override
	public Path getBuildDirectory() throws CoreException
	{
		return Paths.get(getBuildDirectoryURI());

	}

	@Override
	public IContainer getBuildContainer() throws CoreException
	{
		IProject project = getProject();
		IFolder buildRootFolder = project.getFolder(IDFConstants.BUILD_FOLDER);

		IProgressMonitor monitor = new NullProgressMonitor();
		if (!buildRootFolder.exists())
		{
			buildRootFolder.create(IResource.FORCE | IResource.DERIVED, true, monitor);
		}

		return buildRootFolder;
	}

	public IPath getBuildContainerPath() throws CoreException
	{

		if (hasCustomBuild())
		{
			org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(customBuildDir);
			if (!path.toFile().exists())
			{
				path.toFile().mkdirs();
			}
			return path;
		}

		return getBuildContainer().getLocation();
	}

	private boolean hasCustomBuild()
	{
		return this.customBuildDir != null;
	}

	@Override
	public URI getBuildDirectoryURI() throws CoreException
	{
		IPath buildContainerPath = getBuildContainerPath();
		return buildContainerPath.toFile().toURI();
	}

	@Override
	public IBinary[] getBuildOutput() throws CoreException
	{
		ICProject cproject = CoreModel.getDefault().create(getProject());
		IBinaryContainer binaries = cproject.getBinaryContainer();
		IPath outputPath = getBuildContainerPath();
		final IBinary[] outputs = getBuildOutput(binaries, outputPath);
		if (outputs.length > 0)
		{
			return outputs;
		}

		// Give the binary runner a kick and try again.
		BinaryRunner runner = CModelManager.getDefault().getBinaryRunner(cproject);
		runner.start();
		runner.waitIfRunning();
		return getBuildOutput(binaries, outputPath);
	}

	private IBinary[] getBuildOutput(final IBinaryContainer binaries, final IPath outputPath) throws CoreException
	{
		return Arrays.stream(binaries.getBinaries()).filter(b -> b.isExecutable() && outputPath.isPrefixOf(b.getPath()))
				.toArray(IBinary[]::new);
	}

	public ICMakeToolChainFile getToolChainFile()
	{
		return toolChainFile;
	}

	private boolean isLocal() throws CoreException
	{
		IToolChain toolchain = getToolChain();
		return (Platform.getOS().equals(toolchain.getProperty(IToolChain.ATTR_OS))
				|| "linux-container".equals(toolchain.getProperty(IToolChain.ATTR_OS))) //$NON-NLS-1$
				&& (Platform.getOSArch().equals(toolchain.getProperty(IToolChain.ATTR_ARCH)));
	}

	@Override
	public IProject[] build(int kind, Map<String, String> args, IConsole console, IProgressMonitor monitor)
			throws CoreException
	{
		IProject project = getProject();
		ICMakeToolChainFile toolChainFile = getToolChainFile();

		Instant start = Instant.now();

		try
		{
			// Get launch target
			ILaunchBarManager launchBarManager = IDFCorePlugin.getService(ILaunchBarManager.class);
			launchtarget = launchBarManager.getActiveLaunchTarget();
			ILaunchMode activeLaunchMode = launchBarManager.getActiveLaunchMode();

			// Allow build only through esp launch target in run mode
			if (launchtarget != null && !launchtarget.getTypeId().equals(IDFLaunchConstants.ESP_LAUNCH_TARGET_TYPE)
					&& activeLaunchMode != null && activeLaunchMode.getIdentifier().equals("run")) //$NON-NLS-1$
			{
				console.getErrorStream()
						.write("No esp launch target found. Please create/select the correct 'Launch Target'"); //$NON-NLS-1$
				return null;
			}

			// Check for spaces in the project path
			if (!IDFUtil.checkIfIdfSupportsSpaces() && project.getLocation().toOSString().contains(" ")) //$NON-NLS-1$
			{
				console.getErrorStream()
						.write("Project path can’t include space " + project.getLocation().toOSString()); //$NON-NLS-1$
				return null;
			}

			String generator = getProperty(CMAKE_GENERATOR);
			if (generator == null)
			{
				generator = "Ninja"; //$NON-NLS-1$
			}

			project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);

			ConsoleOutputStream infoStream = console.getInfoStream();

			// create build directory
			Path buildDir = getBuildDirectory();
			if (!buildDir.toFile().exists())
			{
				buildDir.toFile().mkdir();
			}

			infoStream.write(String.format(Messages.CMakeBuildConfiguration_BuildingIn, buildDir.toString()));

			// Make sure we have a toolchain file if cross
			if (toolChainFile == null && !isLocal())
			{
				ICMakeToolChainManager manager = IDFCorePlugin.getService(ICMakeToolChainManager.class);
				toolChainFile = manager.getToolChainFileFor(getToolChain());

				if (toolChainFile == null)
				{
					// error
					console.getErrorStream()
							.write(Messages.IDFBuildConfiguration_CMakeBuildConfiguration_NoToolchainFile);
					return null;
				}
			}

			boolean runCMake = cmakeListsModified;
			if (!runCMake)
			{
				switch (generator)
				{
				case "Ninja": //$NON-NLS-1$
					runCMake = !Files.exists(buildDir.resolve("build.ninja")); //$NON-NLS-1$
					break;
				default:
					runCMake = !Files.exists(buildDir.resolve("CMakeFiles")); //$NON-NLS-1$
				}
			}

			if (runCMake)
			{
				deleteCMakeErrorMarkers(project);

				infoStream.write(String.format(Messages.CMakeBuildConfiguration_Configuring, buildDir));
				// clean output to make sure there is no content
				// incompatible with current settings (cmake config would fail)
				cleanBuildDirectory(buildDir);

				List<String> command = new ArrayList<>();

				command.add("cmake"); //$NON-NLS-1$
				command.add("-G"); //$NON-NLS-1$
				command.add(generator);

				if (toolChainFile != null)
				{
					command.add("-DCMAKE_TOOLCHAIN_FILE=" + toolChainFile.getPath().toString()); //$NON-NLS-1$
				}

				command.add("-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"); //$NON-NLS-1$
				if (isCCacheEnabled())
				{
					command.add("-DCCACHE_ENABLE=1"); //$NON-NLS-1$
				}

				if (launchtarget != null)
				{
					String idfTargetName = launchtarget.getAttribute("com.espressif.idf.launch.serial.core.idfTarget", //$NON-NLS-1$
							""); //$NON-NLS-1$
					if (!idfTargetName.isEmpty())
					{
						command.add("-DIDF_TARGET=" + idfTargetName); //$NON-NLS-1$
					}
				}

				if (getToolChain().getTypeId() == ClangToolChain.TYPE_ID)
				{
					command.add("-DIDF_TOOLCHAIN=clang");
				}

				String userArgs = getProperty(CMAKE_ARGUMENTS);
				if (userArgs != null)
				{
					command.addAll(Arrays.asList(userArgs.trim().split("\\s+"))); //$NON-NLS-1$
				}

				// Custom build directory
				int buildDirIndex = command.indexOf("-B"); //$NON-NLS-1$
				if (buildDirIndex != -1)
				{
					this.customBuildDir = command.get(buildDirIndex + 1);
				}
				getProject().setPersistentProperty(
						new QualifiedName(IDFCorePlugin.PLUGIN_ID, IDFConstants.BUILD_DIR_PROPERTY), customBuildDir);

				IContainer srcFolder = project;
				command.add(new File(srcFolder.getLocationURI()).getAbsolutePath());

				infoStream.write(String.join(" ", command) + '\n'); //$NON-NLS-1$

				org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
						getBuildDirectory().toString());
				// hook in cmake error parsing
				IConsole errConsole = new CMakeConsoleWrapper(srcFolder, console);

				// Set PYTHONUNBUFFERED to 1/TRUE to dump the messages back immediately without
				// buffering
				IEnvironmentVariable bufferEnvVar = new EnvironmentVariable("PYTHONUNBUFFERED", "1"); //$NON-NLS-1$ //$NON-NLS-2$

				Process p = startBuildProcess(command, new IEnvironmentVariable[] { bufferEnvVar }, workingDir,
						errConsole, monitor);
				if (p == null)
				{
					console.getErrorStream().write(String.format(Messages.CMakeBuildConfiguration_Failure, "")); //$NON-NLS-1$
					return null;
				}

				watchProcess(p, errConsole);
				cmakeListsModified = false;
			}

			try (ErrorParserManager epm = new ErrorParserManager(project, getBuildDirectoryURI(), this,
					getToolChain().getErrorParserIds()))
			{
				epm.setOutputStream(console.getOutputStream());

				List<String> command = new ArrayList<>();

				String envStr = getProperty(CMAKE_ENV);
				List<IEnvironmentVariable> envVars = new ArrayList<>();
				if (envStr != null)
				{
					List<String> envList = CMakeUtils.stripEnvVars(envStr);
					for (String s : envList)
					{
						int index = s.indexOf("="); //$NON-NLS-1$
						if (index == -1)
						{
							envVars.add(new EnvironmentVariable(s));
						}
						else
						{
							envVars.add(new EnvironmentVariable(s.substring(0, index), s.substring(index + 1)));
						}
					}
				}

				String buildCommand = getProperty(BUILD_COMMAND);
				if (buildCommand == null)
				{
					command.add("cmake"); //$NON-NLS-1$
					command.add("--build"); //$NON-NLS-1$
					command.add("."); //$NON-NLS-1$
					if ("Ninja".equals(generator)) //$NON-NLS-1$
					{
						command.add("--"); //$NON-NLS-1$
						command.add("-v"); //$NON-NLS-1$
					}
				}
				else
				{
					command.addAll(Arrays.asList(buildCommand.split(" "))); //$NON-NLS-1$
				}

				infoStream.write(String.join(" ", command) + '\n'); //$NON-NLS-1$

				org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
						getBuildDirectory().toString());
				Process p = startBuildProcess(command, envVars.toArray(new IEnvironmentVariable[0]), workingDir,
						console, monitor);
				if (p == null)
				{
					console.getErrorStream().write(String.format(Messages.CMakeBuildConfiguration_Failure, "")); //$NON-NLS-1$
					return null;
				}

				watchProcess(p, new IConsoleParser[] { epm });

				final String isSkip = System.getProperty("skip.idf.components"); //$NON-NLS-1$
				if (!Boolean.parseBoolean(isSkip))
				{ // no property defined
					linkBuildComponents(project, monitor);
					project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
				}
				
				// parse compile_commands.json file
				// built-ins detection output goes to the build console, if the user requested
				// output

				processCompileCommandsFile(console, monitor);
				if (DfuCommandsUtil.isDfu() && DfuCommandsUtil.isDfuSupported(launchtarget)) {
					watchProcess(DfuCommandsUtil.dfuBuild(project, infoStream, buildConfiguration, envVars), console);
				}
				project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

				infoStream.write(String.format(Messages.CMakeBuildConfiguration_BuildingComplete, epm.getErrorCount(),
						epm.getWarningCount(), buildDir.toString()));

				Instant finish = Instant.now();
				long timeElapsed = Duration.between(start, finish).toMillis();
				ParitionSizeHandler paritionSizeHandler = new ParitionSizeHandler(project, infoStream, console);
				paritionSizeHandler.startCheckingSize();
				infoStream.write(MessageFormat.format("Total time taken to build the project: {0} ms", timeElapsed)); //$NON-NLS-1$
			}

			// This is specifically added to trigger the indexing since in Windows OS it
			// doesn't seem to happen!
			update(project);
			return new IProject[] { project };
		}
		catch (Exception e)
		{
			throw new CoreException(IDFCorePlugin
					.errorStatus(String.format(Messages.CMakeBuildConfiguration_Building, project.getName()), e));
		}
	}

	private boolean isCCacheEnabled()
	{
		IEclipsePreferences node = IDFCorePreferenceConstants.getPreferenceNode(IDFCorePreferenceConstants.CMAKE_CCACHE_STATUS, null);
		return node.getBoolean(IDFCorePreferenceConstants.CMAKE_CCACHE_STATUS, true);
	}
	
	public void update(IProject project)
	{
		ICProject cproject = CCorePlugin.getDefault().getCoreModel().create(project);
		if (cproject != null)
		{
			ArrayList<ICElement> tuSelection = new ArrayList<>();
			tuSelection.add(cproject);

			ICElement[] cProjectElements = tuSelection.toArray(new ICElement[tuSelection.size()]);
			try
			{
				CCorePlugin.getIndexManager().update(cProjectElements, getUpdateOptions());
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
	}

	private static String getIdfToolsPath()
	{
		return new org.eclipse.core.runtime.Path(IDFUtil.getIDFPath()).toOSString();
	}

	/**
	 * Link build components(build_component_paths) from project_description.json to the project.
	 * 
	 * @param project
	 * @throws Exception
	 */
	protected void linkBuildComponents(IProject project, IProgressMonitor monitor) throws Exception
	{
		final IPath jsonIPath = getBuildContainerPath()
				.append(new org.eclipse.core.runtime.Path(COMPILE_COMMANDS_JSON));
		File jsonDiskFile = jsonIPath.toFile();

		IFolder folder = getIDFComponentsFolder();
		CommandEntry[] sourceFileInfos = null;
		try (Reader in = new FileReader(jsonDiskFile))
		{
			Gson gson = new Gson();
			sourceFileInfos = gson.fromJson(in, CommandEntry[].class);
			for (CommandEntry sourceFileInfo : sourceFileInfos)
			{
				String sourceFile = sourceFileInfo.getFile();
				Logger.log("command::" + sourceFileInfo.getCommand(), true);
				Logger.log("file::" + sourceFile, true);

				org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(sourceFile);
				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path);
				if (file == null)
				{
					createLinkForSourceFileOnly(path.toOSString(), project, folder, monitor);
				}
			}
		}
		catch (Exception e)
		{
			Logger.log(e);
		}
	}

	/**
	 * .../build/ide/esp_idf_components
	 *
	 * @return
	 * @throws CoreException
	 */
	private IFolder getIDFComponentsFolder() throws CoreException
	{
		IProject project = getProject();

		IFolder folder = project.getFolder(getComponentsPath());
		File file = folder.getLocation().toFile();
		if (!file.exists())
		{
			folder.getLocation().toFile().mkdirs();
			IProgressMonitor monitor = new NullProgressMonitor();
			folder.getProject().refreshLocal(IResource.DEPTH_INFINITE, monitor);
		}

		return folder;
	}

	private static IPath getComponentsPath()
	{
		return new org.eclipse.core.runtime.Path(IDFConstants.BUILD_FOLDER).append("ide").append(ESP_IDF_COMPONENTS); //$NON-NLS-1$
	}

	private void setLinkLocation(IResource toLink, IPath rawLinkLocation) throws CoreException
	{
		if (toLink.getType() == IResource.FILE)
		{
			((IFile) toLink).createLink(rawLinkLocation, IResource.REPLACE, new NullProgressMonitor());
		}
		if (toLink.getType() == IResource.FOLDER)
		{
			((IFolder) toLink).createLink(rawLinkLocation, IResource.REPLACE, new NullProgressMonitor());
		}
	}

	private void createLinkForSourceFileOnly(String sourceFile, IProject project, IFolder folder,
			IProgressMonitor monitor) throws Exception
	{
		String sourceFileToSplit = sourceFile;
		if (sourceFile.contains(getIdfToolsPath()))
		{
			sourceFileToSplit = sourceFile.substring(getIdfToolsPath().length(), sourceFile.length());
		}
		String[] segments = new org.eclipse.core.runtime.Path(sourceFileToSplit).segments();

		for (int i = 0; i < (segments.length - 1); i++)
		{
			if (segments[i].equals(COMPONENTS) || segments[i].trim().isEmpty())
			{
				continue;
			}
			folder = folder.getFolder(segments[i]);
			if (!folder.exists())
			{
				folder.create(true, true, new NullProgressMonitor());
			}
		}

		String fileName = new File(sourceFile).getName();
		IFile iFile = folder.getFile(fileName);
		if (!iFile.exists())
		{
			IFile folderLink = folder.getFile(fileName);
			setLinkLocation(folderLink, new org.eclipse.core.runtime.Path(sourceFile));

		}
	}

	protected int getUpdateOptions()
	{
		return IIndexManager.UPDATE_CHECK_TIMESTAMPS | IIndexManager.UPDATE_UNRESOLVED_INCLUDES
				| IIndexManager.UPDATE_EXTERNAL_FILES_FOR_PROJECT;
	}

	@Override
	public void clean(IConsole console, IProgressMonitor monitor) throws CoreException
	{
		IProject project = getProject();
		try
		{
			String generator = getProperty(CMAKE_GENERATOR);

			project.deleteMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, false, IResource.DEPTH_INFINITE);

			ConsoleOutputStream outStream = console.getOutputStream();

			Path buildDir = getBuildDirectory();

			if (!Files.exists(buildDir.resolve("CMakeFiles"))) //$NON-NLS-1$
			{
				outStream.write(Messages.CMakeBuildConfiguration_NotFound);
				return;
			}

			List<String> command = new ArrayList<>();
			String cleanCommand = getProperty(CLEAN_COMMAND);
			if (cleanCommand == null)
			{
				if (generator == null || generator.equals("Ninja")) //$NON-NLS-1$
				{
					command.add("ninja"); //$NON-NLS-1$
					command.add("clean"); //$NON-NLS-1$
				}
				else
				{
					command.add("make"); //$NON-NLS-1$
					command.add("clean"); //$NON-NLS-1$
				}
			}
			else
			{
				command.addAll(Arrays.asList(cleanCommand.split(" "))); //$NON-NLS-1$
			}

			IEnvironmentVariable[] env = new IEnvironmentVariable[0];

			outStream.write(String.join(" ", command) + '\n'); //$NON-NLS-1$

			org.eclipse.core.runtime.Path workingDir = new org.eclipse.core.runtime.Path(
					getBuildDirectory().toString());
			Process p = startBuildProcess(command, env, workingDir, console, monitor);
			if (p == null)
			{
				console.getErrorStream().write(String.format(Messages.CMakeBuildConfiguration_Failure, "")); //$NON-NLS-1$
				return;
			}

			watchProcess(p, console);

			outStream.write(Messages.CMakeBuildConfiguration_BuildComplete);

			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		}
		catch (IOException e)
		{
			throw new CoreException(IDFCorePlugin
					.errorStatus(String.format(Messages.CMakeBuildConfiguration_Cleaning, project.getName()), e));
		}
	}

	/**
	 * @param console the console to print the compiler output during built-ins detection to or <code>null</code> if no
	 *                separate console is to be allocated. Ignored if workspace preferences indicate that no console
	 *                output is wanted.
	 * @param monitor the job's progress monitor
	 */
	private void processCompileCommandsFile(IConsole console, IProgressMonitor monitor) throws CoreException
	{
		IFile file = getCompileCommandsJsonFile(monitor);

		CompileCommandsJsonParser parser = new CompileCommandsJsonParser(
				new ParseRequest(file, new CMakeIndexerInfoConsumer(this::setScannerInformation, getProject()),
						CommandLauncherManager.getInstance().getCommandLauncher(this), console));
		parser.parse(monitor);
	}

	private IFile getCompileCommandsJsonFile(IProgressMonitor monitor) throws CoreException
	{
		IFile file = getBuildContainer().getFile(new org.eclipse.core.runtime.Path(COMPILE_COMMANDS_JSON));
		if (hasCustomBuild())
		{
			org.eclipse.core.runtime.Path compileCmdJsonFile = new org.eclipse.core.runtime.Path(COMPILE_COMMANDS_JSON);
			final IPath jsonIPath = getBuildContainerPath().append(compileCmdJsonFile);

			IFolder folder = getIDFComponentsFolder();
			String fileName = jsonIPath.toFile().getName();
			file = folder.getFile(fileName);
			if (!file.exists())
			{
				IFile folderLink = folder.getFile(fileName);
				setLinkLocation(folderLink, jsonIPath);

			}
			getProject().refreshLocal(IResource.DEPTH_INFINITE, monitor);
		}
		return file;
	}

	/**
	 * Recursively removes any files and directories found below the specified Path.
	 */
	private static void cleanDirectory(Path dir) throws IOException
	{
		SimpleFileVisitor<Path> deltor = new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
			{
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
			{
				super.postVisitDirectory(dir, exc);
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		};
		Path[] files = Files.list(dir).toArray(Path[]::new);
		for (Path file : files)
		{
			Files.walkFileTree(file, deltor);
		}
	}

	private void cleanBuildDirectory(Path buildDir) throws IOException
	{
		if (!Files.exists(buildDir))
			return;
		if (Files.isDirectory(buildDir))
			cleanDirectory(buildDir);
		// TODO: not a directory should we do something?
	}

	/**
	 * Overridden since the ScannerInfoCache mechanism does not satisfy our needs.
	 */
	// interface IScannerInfoProvider
	@Override
	public IScannerInfo getScannerInformation(IResource resource)
	{

		if (infoPerResource == null)
		{
			// no build was run yet, nothing detected
			try
			{
				processCompileCommandsFile(null, new NullProgressMonitor());
			}
			catch (CoreException e)
			{
				Logger.log(e);
			}
		}
		return infoPerResource == null ? null : infoPerResource.get(resource);
	}

	private void setScannerInformation(Map<IResource, IScannerInfo> infoPerResource)
	{
		this.infoPerResource = infoPerResource;
	}

	/**
	 * Overwritten to detect whether one of the CMakeLists.txt files in the project was modified since the last build.
	 */
	@Override
	public void elementChanged(ElementChangedEvent event)
	{
		super.elementChanged(event);
		// Only respond to post change events
		if (event.getType() != ElementChangedEvent.POST_CHANGE)
			return;
		if (!cmakeListsModified)
		{
			processElementDelta(event.getDelta());
		}
	}

	/**
	 * Processes the delta in order to detect whether one of the CMakeLists.txt files in the project has been modified
	 * and saved by the user since the last build.
	 * 
	 * @return <code>true</code> to continue with delta processing, otherwise <code>false</code>
	 */
	private boolean processElementDelta(ICElementDelta delta)
	{
		if (delta == null)
		{
			return true;
		}

		if (delta.getKind() == ICElementDelta.CHANGED)
		{
			// check for modified CMakeLists.txt file
			if (0 != (delta.getFlags() & ICElementDelta.F_CONTENT))
			{
				IResourceDelta[] resourceDeltas = delta.getResourceDeltas();
				if (resourceDeltas != null)
				{
					for (IResourceDelta resourceDelta : resourceDeltas)
					{
						IResource resource = resourceDelta.getResource();
						if (resource.getType() == IResource.FILE
								&& !resource.getFullPath().toOSString().contains(IDFConstants.BUILD_FOLDER))
						{
							String name = resource.getName();
							if (name.equals("CMakeLists.txt") || name.endsWith(".cmake")) //$NON-NLS-1$ //$NON-NLS-2$
							{
								cmakeListsModified = true;
								return false; // stop processing
							}
						}
					}
				}
			}
		}

		// recurse...
		for (ICElementDelta child : delta.getAffectedChildren())
		{
			if (!processElementDelta(child))
			{
				return false; // stop processing
			}
		}
		return true;
	}

	/**
	 * Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public boolean processLine(String line)
	{
		return true;
	}

	/**
	 * Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public boolean processLine(String line, List<Job> jobsArray)
	{
		return true;
	}

	/**
	 * Overwritten since we do not parse console output to get scanner information.
	 */
	// interface IConsoleParser2
	@Override
	public void shutdown()
	{
	}

	/**
	 * Deletes all CMake error markers on the specified project.
	 *
	 * @param project the project where to remove the error markers.
	 * @throws CoreException
	 */
	private static void deleteCMakeErrorMarkers(IProject project) throws CoreException
	{
		project.deleteMarkers(CMakeErrorParser.CMAKE_PROBLEM_MARKER_ID, false, IResource.DEPTH_INFINITE);
	}

	private static class CMakeIndexerInfoConsumer implements ISourceFileInfoConsumer
	{
		/**
		 * gathered IScannerInfo objects or <code>null</code> if no new IScannerInfo was received
		 */
		private Map<IResource, IScannerInfo> infoPerResource = new HashMap<>();
		private boolean haveUpdates;
		private final Consumer<Map<IResource, IScannerInfo>> resultSetter;
		private IProject project;

		/**
		 * @param resultSetter receives the all scanner information when processing is finished
		 * @param iProject
		 */
		public CMakeIndexerInfoConsumer(Consumer<Map<IResource, IScannerInfo>> resultSetter, IProject project)
		{
			this.resultSetter = Objects.requireNonNull(resultSetter);
			this.project = project;
		}

		@Override
		public void acceptSourceFileInfo(String sourceFileName, List<String> systemIncludePaths,
				Map<String, String> definedSymbols, List<String> includePaths, List<String> macroFiles,
				List<String> includeFiles)
		{
			IFile file = getFileForCMakePath(sourceFileName, project);
			if (file != null)
			{
				systemIncludePaths.addAll(includePaths);

				ExtendedScannerInfo info = new ExtendedScannerInfo(definedSymbols,
						systemIncludePaths.stream().toArray(String[]::new), macroFiles.stream().toArray(String[]::new),
						includeFiles.stream().toArray(String[]::new), includePaths.stream().toArray(String[]::new));
				infoPerResource.put(file, info);
				haveUpdates = true;
			}
		}

		/**
		 * Gets an IFile object that corresponds to the source file name given in CMake notation.
		 *
		 * @param sourceFileName the name of the source file, in CMake notation. Note that on windows, CMake writes
		 *                       filenames with forward slashes (/) such as {@code H://path//to//source.c}.
		 * @param project2
		 * @return a IFile object or <code>null</code>
		 */
		private IFile getFileForCMakePath(String sourceFileName, IProject project)
		{
			org.eclipse.core.runtime.Path path = new org.eclipse.core.runtime.Path(sourceFileName);
			IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path);
			if (file != null)
			{
				return file;
			}

			String sourceFile = path.toOSString();
			String pathtolookfor = new org.eclipse.core.runtime.Path(getIdfToolsPath()).append(COMPONENTS).toOSString(); // $NON-NLS-1$
			int startIndex = sourceFile.indexOf(pathtolookfor);
			if (startIndex == -1) // esp-idf/examples/
			{
				pathtolookfor = getIdfToolsPath();
				startIndex = sourceFile.indexOf(pathtolookfor);
			}
			String relativePath = sourceFile.substring(startIndex + pathtolookfor.length() + 1);

			IPath projectPath = getComponentsPath().append(relativePath);
			IResource resourcePath = project.findMember(projectPath);
			if (resourcePath != null && resourcePath instanceof IFile)
			{
				return (IFile) resourcePath;
			}
			return null;
		}

		@Override
		public void shutdown()
		{
			if (haveUpdates)
			{
				// we received updates
				resultSetter.accept(infoPerResource);
				infoPerResource = null;
				haveUpdates = false;
			}
		}
	}

	public void setLaunchTarget(ILaunchTarget target)
	{
		this.launchtarget = target;
	}

}
