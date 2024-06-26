// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.actions;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.Utils.getSystemProperty;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.gui.HelpAwareOptionPane.ButtonSpec;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.SaveLayersDialog;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.ImageProvider.ImageSizes;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.PlatformManager;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Restarts JOSM as it was launched. Comes from "restart" plugin, originally written by Upliner.
 * <br><br>
 * Mechanisms have been improved based on #8561 discussions and
 * <a href="http://lewisleo.blogspot.jp/2012/08/programmatically-restart-java.html">this article</a>.
 * @since 5857
 */
public class RestartAction extends JosmAction {

    private static final String APPLE_OSASCRIPT = "/usr/bin/osascript";
    private static final String APPLE_APP_PATH = "/JOSM.app/Contents/";

    // AppleScript to restart OS X package
    private static final String RESTART_APPLE_SCRIPT =
              "tell application \"System Events\"\n"
            + "repeat until not (exists process \"JOSM\")\n"
            + "delay 0.2\n"
            + "end repeat\n"
            + "end tell\n"
            + "tell application \"JOSM\" to activate";

    // Make sure we're able to retrieve restart commands before initiating shutdown (#13784)
    private static final List<String> cmd = determineRestartCommands();

    /**
     * Constructs a new {@code RestartAction}.
     */
    public RestartAction() {
        super(tr("Restart"), "restart", tr("Restart the application."),
                Shortcut.registerShortcut("file:restart", tr("File: {0}", tr("Restart")), KeyEvent.VK_J, Shortcut.ALT_CTRL_SHIFT), false, false);
        setHelpId(ht("/Action/Restart"));
        setToolbarId("action/restart");
        if (MainApplication.getToolbar() != null) {
            MainApplication.getToolbar().register(this);
        }
        setEnabled(isRestartSupported());
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        restartJOSM();
    }

    /**
     * Determines if restarting the application should be possible on this platform.
     * @return {@code true} if the mandatory system property {@code sun.java.command} is defined, {@code false} otherwise.
     * @since 5951
     */
    public static boolean isRestartSupported() {
        return !cmd.isEmpty();
    }

    /**
     * Restarts the current Java application.
     */
    public static void restartJOSM() {
        // If JOSM has been started with property 'josm.restart=true' this means
        // it is executed by a start script that can handle restart.
        // Request for restart is indicated by exit code 9.
        String scriptRestart = getSystemProperty("josm.restart");
        if ("true".equals(scriptRestart)) {
            MainApplication.exitJosm(true, 9, SaveLayersDialog.Reason.RESTART);
        }

        // Log every related environmentvariable for debug purpose
        if (isDebugSimulation()) {
            logEnvironment();
        }
        Logging.info("Restart "+cmd);
        if (isDebugSimulation()) {
            Logging.debug("Restart cancelled to get debug info");
            return;
        }

        // Leave early if restart is not possible
        if (!isRestartSupported())
            return;

        // Initiate shutdown with a chance for user to cancel
        if (!MainApplication.exitJosm(false, 0, SaveLayersDialog.Reason.RESTART))
            return;

        // execute the command in a shutdown hook, to be sure that all the
        // resources have been disposed before restarting the application
        Runtime.getRuntime().addShutdownHook(new Thread("josm-restarter") {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(cmd.toArray(new String[0]));
                } catch (IOException e) {
                    Logging.error(e);
                }
            }
        });
        // exit
        System.exit(0);
    }

    private static boolean isDebugSimulation() {
        return Logging.isDebugEnabled() && Config.getPref().getBoolean("restart.debug.simulation");
    }

    private static void logEnvironment() {
        logEnvironmentVariable("java.home");
        logEnvironmentVariable("java.class.path");
        logEnvironmentVariable("java.library.path");
        logEnvironmentVariable("jnlpx.origFilenameArg");
        logEnvironmentVariable("sun.java.command");
    }

    private static void logEnvironmentVariable(String var) {
        Logging.debug("{0}: {1}", var, getSystemProperty(var));
    }

    private static boolean isExecutableFile(File f) {
        try {
            return f.isFile() && f.canExecute();
        } catch (SecurityException e) {
            Logging.error(e);
            return false;
        }
    }

    private static List<String> determineRestartCommands() {
        try {
            // special handling for OSX .app package (both legacy and jpackage-based)
            if (PlatformManager.isPlatformOsx() && (
                    getSystemProperty("java.library.path").contains(APPLE_APP_PATH) ||
                    getSystemProperty("java.class.path").contains(APPLE_APP_PATH))) {
                return getAppleCommands();
            } else if (getSystemProperty("jpackage.app-path") != null) {
                return Arrays.asList(getSystemProperty("jpackage.app-path"));
            } else {
                return getCommands();
            }
        } catch (IOException e) {
            Logging.error(e);
            return Collections.emptyList();
        }
    }

    private static List<String> getAppleCommands() throws IOException {
        if (!isExecutableFile(new File(APPLE_OSASCRIPT))) {
            throw new IOException("Unable to find suitable osascript at " + APPLE_OSASCRIPT);
        }
        final List<String> cmd = new ArrayList<>();
        cmd.add(APPLE_OSASCRIPT);
        for (String line : RESTART_APPLE_SCRIPT.split("\n", -1)) {
            cmd.add("-e");
            cmd.add(line);
        }
        return cmd;
    }

    private static List<String> getCommands() throws IOException {
        final List<String> cmd = new ArrayList<>();
        // java binary
        cmd.add(getJavaRuntime());
        // vm arguments
        addVMArguments(cmd);
        // Determine webstart JNLP file. Use jnlpx.origFilenameArg instead of jnlp.application.href,
        // because only this one is present when run from j2plauncher.exe (see #10795)
        final String jnlp = getSystemProperty("jnlpx.origFilenameArg");
        // program main and program arguments (be careful a sun property. might not be supported by all JVM)
        final String javaCommand = getSystemProperty("sun.java.command");
        if (javaCommand == null) {
            throw new IOException("Unable to retrieve sun.java.command property");
        }
        String[] mainCommand = javaCommand.split(" ", -1);
        if (javaCommand.endsWith(".jnlp") && jnlp == null) {
            // see #11751 - jnlp on Linux
            Logging.debug("Detected jnlp without jnlpx.origFilenameArg property set");
            cmd.addAll(Arrays.asList(mainCommand));
        } else {
            // look for a .jar in all chunks to support paths with spaces (fix #9077)
            StringBuilder sb = new StringBuilder(mainCommand[0]);
            for (int i = 1; i < mainCommand.length && !mainCommand[i-1].endsWith(".jar"); i++) {
                sb.append(' ').append(mainCommand[i]);
            }
            String jarPath = sb.toString();
            // program main is a jar
            if (jarPath.endsWith(".jar")) {
                // if it's a jar, add -jar mainJar
                cmd.add("-jar");
                cmd.add(new File(jarPath).getPath());
            } else {
                // else it's a .class, add the classpath and mainClass
                cmd.add("-cp");
                cmd.add('"' + getSystemProperty("java.class.path") + '"');
                cmd.add(mainCommand[0].replace("jdk.plugin/", "")); // Main class appears to be invalid on Java WebStart 9
            }
            // add JNLP file.
            if (jnlp != null) {
                cmd.add(jnlp);
            }
        }
        // finally add program arguments
        cmd.addAll(MainApplication.getCommandLineArgs());
        return cmd;
    }

    private static String getJavaRuntime() throws IOException {
        final String java = getSystemProperty("java.home") + File.separator + "bin" + File.separator +
                (PlatformManager.isPlatformWindows() ? "java.exe" : "java");
        if (!isExecutableFile(new File(java))) {
            throw new IOException("Unable to find suitable java runtime at "+java);
        }
        return java;
    }

    private static void addVMArguments(Collection<String> cmd) {
        List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        Logging.debug("VM arguments: {0}", arguments);
        for (String arg : arguments) {
            // When run from jp2launcher.exe, jnlpx.remove is true, while it is not when run from javaws
            // Always set it to false to avoid error caused by a missing jnlp file on the second restart
            arg = arg.replace("-Djnlpx.remove=true", "-Djnlpx.remove=false");
            // if it's the agent argument : we ignore it otherwise the
            // address of the old application and the new one will be in conflict
            if (!arg.contains("-agentlib")) {
                cmd.add(arg);
            }
        }
    }

    /**
     * Returns a new {@code ButtonSpec} instance that performs this action.
     * @return A new {@code ButtonSpec} instance that performs this action.
     */
    public static ButtonSpec getRestartButtonSpec() {
        return new ButtonSpec(
                tr("Restart"),
                ImageProvider.get("restart", ImageSizes.LARGEICON),
                tr("Restart the application."),
                ht("/Action/Restart"),
                isRestartSupported()
        );
    }

    /**
     * Returns a new {@code ButtonSpec} instance that do not perform this action.
     * @return A new {@code ButtonSpec} instance that do not perform this action.
     */
    public static ButtonSpec getCancelButtonSpec() {
        return new ButtonSpec(
                tr("Cancel"),
                new ImageProvider("cancel"),
                tr("Click to restart later."),
                null /* no specific help context */
        );
    }

    /**
     * Returns default {@code ButtonSpec} instances for this action (Restart/Cancel).
     * @return Default {@code ButtonSpec} instances for this action.
     * @see #getRestartButtonSpec
     * @see #getCancelButtonSpec
     */
    public static ButtonSpec[] getButtonSpecs() {
        return new ButtonSpec[] {
                getRestartButtonSpec(),
                getCancelButtonSpec()
        };
    }
}
