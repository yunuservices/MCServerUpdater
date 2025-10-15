package io.github.projectunified.mcserverupdater;

import io.github.projectunified.mcserverupdater.api.DebugConsumer;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.logging.*;

public final class MCServerUpdater {
    public static final Logger LOGGER = Logger.getLogger("MCServerUpdater");
    private static final String USER_AGENT = "MCServerUpdater (https://github.com/HSGamer/MCServerUpdater)";

    static {
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new Formatter() {
            @Override
            public String format(LogRecord logRecord) {
                StringBuilder builder = new StringBuilder();
                builder.append("[").append(logRecord.getLevel()).append("] ").append(logRecord.getMessage()).append("\n");
                if (logRecord.getThrown() != null) {
                    StringWriter writer = new StringWriter();
                    logRecord.getThrown().printStackTrace(new PrintWriter(writer));
                    builder.append(writer);
                }
                return builder.toString();
            }
        });
        LOGGER.addHandler(handler);
        LOGGER.setLevel(Level.ALL);
        LOGGER.setUseParentHandlers(false);
    }

    public static void main(String[] args) throws Exception {
        OptionParser parser = new OptionParser();
        OptionSpec<Void> help = parser.accepts("help", "Get the list of arguments").forHelp();
        OptionSpec<Void> projects = parser.accepts("projects", "Get the list of projects").forHelp();
        OptionSpec<String> project = parser.accepts("project", "The project to download").withOptionalArg().ofType(String.class).defaultsTo("paper");
        OptionSpec<String> version = parser.accepts("version", "The project version").withOptionalArg().ofType(String.class).defaultsTo("default");
        OptionSpec<String> output = parser.accepts("output", "The output file path").withOptionalArg().ofType(String.class).defaultsTo("server.jar");
        OptionSpec<String> checksumFile = parser.accepts("checksum", "The checksum file path").withOptionalArg().ofType(String.class).defaultsTo("checksum.txt");
        OptionSpec<String> workingDirectory = parser.accepts("working-directory", "The working directory").withOptionalArg().ofType(String.class).defaultsTo(".");
        OptionSet options = parser.parse(args);
        if (options.has(help)) {
            StringWriter writer = new StringWriter();
            parser.printHelpOn(writer);
            BufferedReader reader = new BufferedReader(new StringReader(writer.toString()));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                LOGGER.info(line);
            }
            System.exit(0);
            return;
        }
        if (options.has(projects)) {
            for (String key : UpdateBuilder.getUpdaterNames()) {
                LOGGER.info(key);
            }
            System.exit(0);
            return;
        }
        String projectName = options.valueOf(project);
        String versionName = options.valueOf(version);
        String outputName = options.valueOf(output);
        String checksumFileName = options.valueOf(checksumFile);
        String workingDirectoryName = options.valueOf(workingDirectory);

        UpdateBuilder builder = UpdateBuilder.updateProject(projectName)
                .version(versionName)
                .workingDirectory(workingDirectoryName)
                .outputFile(outputName)
                .checksumFile(checksumFileName)
                .userAgent(USER_AGENT)
                .debugConsumer(new DebugConsumer() {
                    @Override
                    public void consume(String message) {
                        LOGGER.fine(message);
                    }

                    @Override
                    public void consume(Throwable throwable) {
                        LOGGER.log(Level.FINE, throwable.getMessage(), throwable);
                    }

                    @Override
                    public void consume(String message, Throwable throwable) {
                        LOGGER.log(Level.FINE, message, throwable);
                    }
                });

        try {
            LOGGER.info("Start updating...");
            UpdateStatus status = builder.execute();
            if (status.isSuccessStatus()) {
                LOGGER.info(status.getMessage());
                String[] minecraftArgs = filterMinecraftArgs(args);
                boot(outputName, minecraftArgs);
            } else {
                LOGGER.log(Level.SEVERE, "Failed to update", status.getThrowable());
                System.exit(1);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred", e);
            System.exit(1);
        }
    }

    private static String[] filterMinecraftArgs(String[] args) {
        List<String> minecraftArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--project") || args[i].equals("--version") || args[i].equals("--output") || args[i].equals("--checksum") || args[i].equals("--working-directory")) {
                i++; 
                continue;
            }
            minecraftArgs.add(args[i]);
        }
        return minecraftArgs.toArray(new String[0]);
    }

    private static void boot(String jarPath, String[] args) {
        File serverJar = new File(jarPath);
        try (JarFile jarFile = new JarFile(serverJar)) {
            String mainClassName = jarFile.getManifest().getMainAttributes().getValue("Main-Class");
            if (mainClassName == null) {
                throw new RuntimeException("There is no Main-Class in the jar manifest.");
            }
            URL jarURL = serverJar.toURI().toURL();
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL}, MCServerUpdater.class.getClassLoader().getParent());

            Thread thread = new Thread(() -> {
                try {
                    Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
                    MethodHandle mainMethod = MethodHandles.lookup()
                            .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class));
                    mainMethod.invoke((Object) args);
                } catch (Throwable e) {
                    throw new RuntimeException("Error encountered while booting the server.", e);
                }
            }, "ServerBootThread");

            thread.setContextClassLoader(classLoader);
            thread.start();
            thread.join();
        } catch (Exception e) {
            throw new RuntimeException("Failed to boot up " + jarPath, e);
        }
    }
}
