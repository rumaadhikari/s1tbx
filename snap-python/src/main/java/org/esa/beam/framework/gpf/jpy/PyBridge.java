package org.esa.beam.framework.gpf.jpy;

import org.esa.beam.util.Debug;
import org.esa.beam.util.SystemUtils;
import org.esa.beam.util.io.TreeCopier;
import org.jpy.PyLib;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.List;

import static org.esa.beam.util.SystemUtils.LOG;

/**
 * This class is used to establish the bridge between Java and Python.
 * It basically let's a given Python interpreter execute the file 'beampyutil.py' found in the 'beampy'
 * folder of the unpacked BEAM-Python module.
 * <p>
 * 'beampyutil.py' again configures 'jpy' by selecting and unpacking appropriate
 * jpy tools and binaries found as 'jpy.&lt;platform&gt;-&lt;python-version&gt;.zip' in the 'lib' folder of the unpacked BEAM-Python module.
 * 'beampyutil.py' will then call 'jpyutil.py' to write the Java- and Python-side configuration files 'jpyutil.properties'
 * and 'jpyconfig.py'.
 * <p>
 * Then, 'jpyutil.properties' will be used by jpy's {@code PyLib} class to identify its correct binaries.
 * {@code PyLib} is finally used to start an embedded Python interpreter using the shared Python library that belongs to the Python
 * interpreter that was used to execute 'beampyutil.py'.
 * <p>
 * The following system properties can be used to configure this class:
 * <p>
 * <ol>
 * <li>{@code snap.pythonExecutable}: The python executable to be used with BEAM. The default value is {@code "python"}.</li>
 * <li>{@code snap.forcePythonConfig}: Forces reconfiguration of the bridge for each BEAM run. The default value is {@code "true"}</li>
 * </ol>
 *
 * @author Norman Fomferra
 */
class PyBridge {

    public static final String BEAMPY_PY_FILENAME = "beampy.py";
    public static final String BEAMPYUTIL_PY_FILENAME = "beampyutil.py";
    public static final String BEAMPYUTIL_LOG_FILENAME = "beampyutil.log";
    public static final String FORCE_PYTHON_CONFIG_PROPERTY = "snap.forcePythonConfig";
    public static final String PYTHON_EXECUTABLE_PROPERTY = "snap.pythonExecutable";
    public static final String PYTHON_MODULE_INSTALL_DIR_PROPERTY = "snap.pythonModuleDir";
    public static final String JPY_JAVA_API_CONFIG_FILENAME = "jpyconfig.properties";
    public static final String JPY_DEBUG_PROPERTY = "jpy.debug";
    public static final String JPY_CONFIG_PROPERTY = "jpy.config";

    private static final Path MODULE_CODE_BASE_PATH = findModuleCodeBasePath();
    public static final String BEAMPY_DIR_NAME = "beampy";

    private static boolean established;

    /**
     * Establishes the BEAM-Python bridge.
     */
    public synchronized static void establish() throws IOException {
        if (established) {
            return;
        }

        String pythonExecutable = System.getProperty(PYTHON_EXECUTABLE_PROPERTY, "python");

        Path pythonModuleInstallDir;
        String pythonModuleDirStr = System.getProperty(PYTHON_MODULE_INSTALL_DIR_PROPERTY);
        if (pythonModuleDirStr != null) {
            pythonModuleInstallDir = Paths.get(pythonModuleDirStr);
        } else {
            pythonModuleInstallDir = Paths.get(SystemUtils.getApplicationDataDir(true).getPath(), "snap-python");
        }

        boolean forcePythonConfig = System.getProperty(FORCE_PYTHON_CONFIG_PROPERTY, "true").equalsIgnoreCase("true");

        Path beampyDir = installPythonModule(pythonExecutable, pythonModuleInstallDir, forcePythonConfig);

        synchronized (PyLib.class) {
            if (!established) {
                //PyLib.Diag.setFlags(PyLib.Diag.F_ALL);
                String pythonVersion = PyLib.getPythonVersion();
                LOG.info("Running Python " + pythonVersion);
                if (!PyLib.isPythonRunning()) {
                    PyLib.startPython(beampyDir.toString());
                } else {
                    extendSysPath(beampyDir.toString());
                }
                established = true;
            }
        }
    }

    public static Path installPythonModule(String pythonExecutable,
                                           Path pythonModuleInstallDir,
                                           boolean forcePythonConfig) throws IOException {
        Path beampyDir = pythonModuleInstallDir.resolve(BEAMPY_DIR_NAME);
        if (forcePythonConfig || !Files.isDirectory(beampyDir)) {
            unpackPythonModuleDir(beampyDir);
        }

        Path jpyConfigFile = beampyDir.resolve(JPY_JAVA_API_CONFIG_FILENAME);
        if (forcePythonConfig || !Files.exists(jpyConfigFile)) {
            // Configure jpy Python-side
            configureJpy(pythonExecutable, beampyDir);
        }
        if (!Files.exists(jpyConfigFile)) {
            throw new IOException(String.format("Python configuration incomplete.\n" +
                                                        "Missing file '%s'.\n" +
                                                        "Please check log file '%s'.",
                                                jpyConfigFile,
                                                beampyDir.resolve(BEAMPYUTIL_LOG_FILENAME)));
        }

        // Configure jpy Java-side
        System.setProperty(JPY_CONFIG_PROPERTY, jpyConfigFile.toString());
        if (Debug.isEnabled() && System.getProperty(JPY_DEBUG_PROPERTY) == null) {
            System.setProperty(JPY_DEBUG_PROPERTY, "true");
        }

        return beampyDir;
    }

    private static void unpackPythonModuleDir(Path pythonModuleDir) throws IOException {
        TreeCopier.copyDir(getResourcePath("beampy-examples"), pythonModuleDir.resolve("examples"));
        TreeCopier.copyDir(getResourcePath("beampy-tests"), pythonModuleDir.resolve("tests"));
        TreeCopier.copy(getResourcePath(BEAMPY_DIR_NAME), pythonModuleDir);
        LOG.info("SNAP-Python module directory: " + pythonModuleDir);
    }

    /**
     * Extends Python's system path (it's global {@code sys.path} variable) by the given path, if not already present.
     *
     * @param path The new module path.
     */
    public static void extendSysPath(String path) {
        if (path != null) {
            String code = String.format("" +
                                                "import sys;\n" +
                                                "p = '%s';\n" +
                                                "if not p in sys.path: sys.path.append(p)",
                                        path.replace("\\", "\\\\"));
            PyLib.execScript(code);
        }
    }

    private static void configureJpy(String pythonExecutable, Path beampyDir) throws IOException {
        LOG.info("Configuring BEAM-Python bridge...");

        // "java.home" is always present
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable);
        command.add(BEAMPYUTIL_PY_FILENAME);
        command.add("--snap_home");
        command.add(System.getProperty("snap.home", Paths.get(".").toAbsolutePath().normalize().toString()));
        //command.add(SystemUtils.getApplicationHomeDir().getPath());
        command.add("--java_module");
        command.add(MODULE_CODE_BASE_PATH.toFile().getPath());
        command.add("--force");
        command.add("--log_file");
        command.add(BEAMPYUTIL_LOG_FILENAME);
        if (Debug.isEnabled()) {
            command.add("--log_level");
            command.add("DEBUG");
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            command.add("--java_home");
            command.add(javaHome);
        }
        String osArch = System.getProperty("os.arch");  // "os.arch" is always present
        if (osArch != null) {
            command.add("--req_arch");
            command.add(osArch);
        }
        String commandLine = toCommandLine(command);
        LOG.info(String.format("Executing command: [%s]\n", commandLine));
        try {
            Process process = new ProcessBuilder()
                    .command(command)
                    .directory(beampyDir.toFile()).start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException(String.format("Python configuration failed.\nCommand [%s]\nfailed with return code %s.", commandLine, exitCode));
            }
        } catch (InterruptedException e) {
            throw new IOException(String.format("Python configuration failed.\nCommand [%s]\nfailed with exception %s.", commandLine, e.getMessage()), e);
        }
    }

    private static String toCommandLine(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String arg : command) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(arg.contains(" ") ? String.format("\"%s\"", arg) : arg);
        }
        return sb.toString();
    }

    private static Path getResourcePath(String resource) {
        return MODULE_CODE_BASE_PATH.resolve(resource);
    }

    private static Path findModuleCodeBasePath() {
        try {
            URI uri = PyBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri);
            if (Files.isRegularFile(path)) {
                try {
                    FileSystem fileSystem = FileSystems.newFileSystem(path, PyBridge.class.getClassLoader());
                    return fileSystem.getPath("/");
                } catch (ProviderNotFoundException e) {
                    // ok
                }
            }
            return path;
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Failed to detect the module's code base path", e);
        }
    }
}
