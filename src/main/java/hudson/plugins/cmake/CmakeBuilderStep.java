/*
 * The MIT License
 *
 * Copyright 2018 Martin Weber
 */
package hudson.plugins.cmake;

import java.util.List;
import java.util.Map;

import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

/**
 * Generates the build scripts with <tt>cmake</tt> and runs the appropriate
 * build tool.<br>
 * Similar to {@code CMakeBuilder}, but pipeline compatible.
 *
 * @author Martin Weber
 */
public class CmakeBuilderStep extends AbstractStep {

    private static final long serialVersionUID = 1L;
    /**
     * the name of cmake´s buildscript generator or {@code null} if the default
     * generator should be used
     */
    private String generator;
    private String buildDir;
    private String sourceDir;
    private String buildType;
    private String cmakeArgs;
    private String preloadScript;
    private boolean cleanBuild;

    private List<BuildToolStep> toolSteps;

    /**
     * Minimal constructor.
     *
     * @param installation
     *            the name of the cmake tool installation from the global config
     *            page.
     */
    @DataBoundConstructor
    public CmakeBuilderStep(String installation) {
        super(installation);
    }

    /**
     * Sets the name of the build-script generator.
     *
     * @param generator
     *            the name of cmake´s build-script generator or {@code null} or
     *            empty if the default generator should be used
     */
    @DataBoundSetter
    public void setGenerator(String generator) {
        this.generator = Util.fixEmptyAndTrim(generator);
    }

    public String getGenerator() {
        return generator;
    }

    @DataBoundSetter
    public void setSourceDir(String sourceDir) {
        this.sourceDir = Util.fixEmptyAndTrim(sourceDir);
    }

    public String getSourceDir() {
        return this.sourceDir;
    }

    @DataBoundSetter
    public void setBuildDir(String buildDir) {
        this.buildDir= Util.fixEmptyAndTrim(buildDir);
    }

    public String getBuildDir() {
        return buildDir;
    }

    @DataBoundSetter
    public void setBuildType(String buildType) {
        this.buildType = Util.fixEmptyAndTrim(buildType);
    }

    public String getBuildType() {
        return this.buildType;
    }

    @DataBoundSetter
    public void setCleanBuild(boolean cleanBuild) {
        this.cleanBuild = cleanBuild;
    }

    public boolean isCleanBuild() {
        return this.cleanBuild;
    }

    @DataBoundSetter
    public void setPreloadScript(String preloadScript) {
        this.preloadScript = Util.fixEmptyAndTrim(preloadScript);
    }

    public String getPreloadScript() {
        return this.preloadScript;
    }

    @DataBoundSetter
    public void setCmakeArgs(String cmakeArgs) {
        this.cmakeArgs= Util.fixEmptyAndTrim(cmakeArgs);
    }

    public String getCmakeArgs() {
        return cmakeArgs;
    }

    /**
     * Sets the toolSteps property.
     */
    @DataBoundSetter
    public void setSteps(List<BuildToolStep> toolSteps) {
        this.toolSteps = toolSteps;
    }

    /**
     * Gets the toolSteps property.
     *
     * @return the current toolSteps property.
     */
    public List<BuildToolStep> getSteps() {
        return toolSteps;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(this, context);
    }

    /**
     * Constructs the command line to invoke cmake.
     *
     * @param cmakeBin
     *            the name of the cmake binary, either as an absolute or
     *            relative file system path.
     * @param generator
     *            the name of the build-script generator or {@code null}
     * @param preloadScript
     *            name of the pre-load a script to populate the cache or
     *            {@code null}
     * @param theSourceDir
     *            source directory, must not be {@code null}
     * @param buildType
     *            build type argument for cmake or {@code null} to pass none
     * @param cmakeArgs
     *            additional arguments, separated by spaces to pass to cmake or
     *            {@code null}
     * @return the argument list, never {@code null}
     */
    private static ArgumentListBuilder buildCMakeCall(final String cmakeBin,
            final String generator, final String preloadScript,
            final FilePath theSourceDir, final String buildType,
            final String cmakeArgs) {
        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(cmakeBin);
        if (generator != null) {
            args.add("-G").add(generator); //$NON-NLS-1$
        }
        if (preloadScript != null) {
            args.add("-C").add(preloadScript); //$NON-NLS-1$
        }
        if (buildType != null) {
            args.add("-D").add("CMAKE_BUILD_TYPE=" + buildType); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (cmakeArgs != null) {
            args.addTokenized(cmakeArgs);
        }
        args.add(theSourceDir.getRemote());
        return args;
    }

    /**
     * Constructs the command line to invoke the actual build tool.
     *
     * @param toolBin
     *            the name of the build tool binary, either as an absolute or
     *            relative file system path.
     * @param toolArgs
     *            addional arguments, separated by spaces to pass to cmake or
     *            {@code null}
     * @return the argument list, never {@code null}
     */
    private static ArgumentListBuilder buildBuildToolCall(final String toolBin,
            String... toolArgs) {
        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(toolBin);
        if (toolArgs != null) {
            args.add(toolArgs);
        }
        return args;
    }

    /**
     * Constructs the command line to have the actual build tool invoked with
     * cmake.
     *
     * @param cmakeBin
     *            the name of the cmake tool binary, either as an absolute or
     *            relative file system path.
     * @param theBuildDir
     *            the build directory path
     * @param toolArgs
     *            addional build tool arguments, separated by spaces to pass to
     *            cmake or {@code null}
     * @return the argument list, never {@code null}
     */
    private static ArgumentListBuilder buildBuildToolCallWithCmake(
            final String cmakeBin, FilePath theBuildDir, String... toolArgs) {
        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(cmakeBin);
        args.add("--build"); //$NON-NLS-1$
        args.add(theBuildDir.getRemote());
        if (toolArgs != null) {
            args.add(toolArgs);
        }
        return args;
    }

    /**
     * Overridden for better type safety.
     */
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    // //////////////////////////////////////////////////////////////////
    // inner classes
    // //////////////////////////////////////////////////////////////////
    private static class Execution
            extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;

        private final CmakeBuilderStep step;

        Execution(CmakeBuilderStep cmakeBuilderStep, StepContext context) {
            super(context);
            this.step = cmakeBuilderStep;
        }

        /*
         * (non-Javadoc)
         *
         * @see org.jenkinsci.plugins.workflow.steps.
         * SynchronousNonBlockingStepExecution#run()
         */
        @Override
        protected Void run() throws Exception {
            final StepContext context = getContext();
            final TaskListener listener = context.get(TaskListener.class);
            final Launcher launcher = context.get(Launcher.class);
            final Node node = context.get(Node.class);
            final EnvVars env = context.get(EnvVars.class);

            CmakeTool installToUse = step.getSelectedInstallation();
            // Raise an error if the cmake installation isn't found
            if (installToUse == null) {
                throw new AbortException(
                        Messages.getString("No_installation_selected")); //$NON-NLS-1$
            }

            // Get the CMake version for this node, installing it if necessary
            installToUse = installToUse.forNode(node, listener)
                    .forEnvironment(env);

            final String cmakeBin = installToUse.getAbsoluteCommand(node,
                    "cmake"); //$NON-NLS-1$
            final FilePath workSpace = context.get(FilePath.class);
            /*
             * Determine remote build directory path. Clean it, if requested.
             * Create it.
             */
            final String buildDir = step.getBuildDir();
            FilePath theBuildDir = LaunchUtils.makeRemotePath(workSpace,
                    buildDir);
            if (buildDir != null) {
                if (step.isCleanBuild()
                        && !buildDir.equals(step.getSourceDir())) {
                    // avoid deleting source dir
                    listener.getLogger().format(
                            Messages.getString("Cleaning_build_dir"), theBuildDir.getRemote()); //$NON-NLS-1$
                    theBuildDir.deleteRecursive();
                }
                theBuildDir.mkdirs();
            }

            /* Invoke cmake in build dir */
            FilePath theSourceDir = LaunchUtils.makeRemotePath(workSpace,
                    step.sourceDir);
            ArgumentListBuilder cmakeCall = buildCMakeCall(cmakeBin,
                    step.getGenerator(), step.getPreloadScript(), theSourceDir,
                    step.getBuildType(), step.getCmakeArgs());
            // invoke cmake

            int exitCode;
            if (0 != (exitCode = launcher.launch().pwd(theBuildDir).envs(env)
                    .stdout(listener).cmds(cmakeCall).join())) {
                // invocation failed
                throw new AbortException(
                        String.format(Messages.getString("Exited_with_error_code"), //$NON-NLS-1$
                                step.getCommandBasename(), exitCode));
            }

            if (step.getSteps() != null) {
                final EnvVars envs = new EnvVars(env);

                String buildTool = null;
                boolean needBuildTool = false;
                for (BuildToolStep toolStep : step.getSteps()) {
                    if (!toolStep.getWithCmake()) {
                        needBuildTool = true;
                        break;
                    }
                }
                if (needBuildTool) {
                    /* parse CMakeCache.txt to get the actual build tool */
                    FilePath cacheFile = theBuildDir.child("CMakeCache.txt"); //$NON-NLS-1$
                    buildTool = cacheFile.act(new BuildToolEntryParser());
                    if (buildTool == null) {
                        throw new AbortException(String.format(
                                Messages.getString("Failed_to_get_var_value"), //$NON-NLS-1$
                                CmakeBuilder.ENV_VAR_NAME_CMAKE_BUILD_TOOL,
                                cacheFile.getRemote()));
                    }
                }

                /* invoke each build tool step in build dir */
                for (BuildToolStep toolStep : step.getSteps()) {
                    ArgumentListBuilder toolCall;
                    if (!toolStep.getWithCmake()) {
                        // invoke directly
                        toolCall = buildBuildToolCall(buildTool,
                                toolStep.getCommandArguments(envs));
                    } else {
                        // invoke through 'cmake --build <dir>'
                        toolCall = buildBuildToolCallWithCmake(cmakeBin,
                                theBuildDir,
                                toolStep.getCommandArguments(envs));
                    }
                    final EnvVars stepEnv = new EnvVars(envs).overrideAll(
                            toolStep.getEnvironmentVars(envs, listener));
                    if (0 != (exitCode = launcher.launch().pwd(theBuildDir)
                            .envs(stepEnv).stdout(listener).cmds(toolCall)
                            .join())) {
                        throw new AbortException(String.format(
                                Messages.getString("Exited_with_error_code"), //$NON-NLS-1$
                                buildTool, exitCode));
                    }
                }
            }
            return null;
        }

    } // Execution

    /**
     * Descriptor for {@link CmakeBuilderStep}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     */
    @Extension(optional = true)
    public static final class DescriptorImpl
            extends AbstractStep.DescriptorImpl {

        @Override
        public String getFunctionName() {
            return "cmakeBuild"; //$NON-NLS-1$
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return Messages.getString("CmakeBuilderStep.Descriptor.DisplayName"); //$NON-NLS-1$
        }

    } // DescriptorImpl
}
